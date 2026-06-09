package build.jenesis.launcher;

import module java.base;

/**
 * The single in-memory loader for every nested jar in an executable jar - both the {@code classpath/}
 * jars (its unnamed module) and the {@code modulepath/} modules (defined to it as named modules through
 * {@link ModuleLayer#defineModules}). This mirrors how a real {@code java -p modulepath -cp classpath}
 * launch works: one application loader hosts the named modules and the unnamed module together, with the
 * {@link ModuleLayer} as metadata on top.
 *
 * <p>Class-path jars are merged into a flat {@code name -> bytes} map; on a clash the first jar in
 * iteration order wins, matching class-path precedence. Module classes and resources are read on demand
 * through the {@link MapModuleReader}s the {@link InMemoryModuleFinder} already built, so no bytes are
 * duplicated. A package owned by a bundled module is served only from that module: a same-named package
 * on the class path is <em>shadowed</em> (invisible), exactly as {@code java -p ... -cp ...} does.</p>
 *
 * <p>Resources are exposed through the {@code jenesismem:} URL scheme so {@link ClassLoader#getResources}
 * - and therefore {@link java.util.ServiceLoader} for class-path providers - returns openable URLs;
 * {@link #findResource(String, String)} serves module resources so {@link Class#getResourceAsStream}
 * keeps working for a class in a module. Native libraries cannot be loaded from memory, so
 * {@link #findLibrary(String)} extracts a requested library - from a class-path jar or a bundled
 * module - to a temp file on demand.</p>
 */
final class InMemoryClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> classes = new HashMap<>();
    private final Map<String, List<byte[]>> resources = new HashMap<>();
    private final Map<String, List<URL>> resourceUrls = new ConcurrentHashMap<>();
    private final Map<String, String> packageToModule = new HashMap<>();
    private final Map<String, ModuleReader> readers = new HashMap<>();

    InMemoryClassLoader(List<Archive.Jar> classpath, InMemoryModuleFinder finder, ClassLoader parent)
            throws IOException {
        super("jenesis", parent);
        for (Archive.Jar jar : classpath) {
            for (Map.Entry<String, byte[]> entry : jar.entries().entrySet()) {
                String name = entry.getKey();
                byte[] data = entry.getValue();
                resources.computeIfAbsent(name, _ -> new ArrayList<>()).add(data);
                if (name.endsWith(".class")) {
                    classes.putIfAbsent(name, data);
                }
            }
        }
        if (finder != null) {
            for (ModuleReference reference : finder.findAll()) {
                String module = reference.descriptor().name();
                readers.put(module, reference.open());
                for (String packageName : reference.descriptor().packages()) {
                    packageToModule.putIfAbsent(packageName, module);
                }
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        String module = packageToModule.get(packageOf(name));
        if (module != null) {
            // The package belongs to a bundled module: serve only the module copy, so a same-package class
            // on the class path is shadowed - the JDK's own rule for `java -p modulepath -cp classpath`.
            byte[] data = moduleResource(module, resource);
            if (data == null) {
                throw new ClassNotFoundException(name);
            }
            // The package is recorded against this loader (defineModules ran first), so the VM assigns the
            // class to its module automatically; do not definePackage for a module package.
            return defineClass(name, data, 0, data.length);
        }
        byte[] data = classes.get(resource);
        if (data == null) {
            throw new ClassNotFoundException(name);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String packageName = name.substring(0, dot);
            if (getDefinedPackage(packageName) == null) {
                try {
                    definePackage(packageName, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException _) {
                    // Raced with another thread defining the same package; harmless.
                }
            }
        }
        return defineClass(name, data, 0, data.length);
    }

    @Override
    protected Class<?> findClass(String moduleName, String name) {
        // Called by the VM and Class.forName(Module, name) for a class in a module defined to this loader.
        byte[] data = moduleResource(moduleName, name.replace('.', '/') + ".class");
        if (data == null) {
            return null;
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            return loaded != null ? loaded : defineClass(name, data, 0, data.length);
        }
    }

    @Override
    protected URL findResource(String moduleName, String name) throws IOException {
        if (moduleName == null) {
            return null;
        }
        ModuleReader reader = readers.get(moduleName);
        if (reader == null) {
            return null;
        }
        // The Module/Class layer has already gated encapsulation before calling this, so serve whatever
        // the reader has. The URI points into the MemoryUrlRegistry, so its toURL() is openable.
        Optional<URI> uri = reader.find(name);
        if (uri.isEmpty()) {
            return null;
        }
        return uri.get().toURL();
    }

    @Override
    protected URL findResource(String name) {
        List<URL> urls = urls(name);
        return urls.isEmpty() ? null : urls.getFirst();
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        return Collections.enumeration(urls(name));
    }

    private List<URL> urls(String name) {
        List<byte[]> data = resources.get(name);
        if (data == null) {
            return List.of();
        }
        return resourceUrls.computeIfAbsent(name, _ -> {
            List<URL> urls = new ArrayList<>(data.size());
            for (byte[] bytes : data) {
                try {
                    urls.add(MemoryUrlRegistry.register(bytes).toURL());
                } catch (MalformedURLException e) {
                    throw new IllegalStateException("Failed to expose resource " + name, e);
                }
            }
            return urls;
        });
    }

    private byte[] moduleResource(String module, String name) {
        ModuleReader reader = readers.get(module);
        if (reader == null) {
            return null;
        }
        try {
            Optional<InputStream> stream = reader.open(name);
            if (stream.isEmpty()) {
                return null;
            }
            try (InputStream in = stream.get()) {
                return in.readAllBytes();
            }
        } catch (IOException _) {
            return null;
        }
    }

    private static String packageOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(0, dot);
    }

    @Override
    protected String findLibrary(String name) {
        String mapped = System.mapLibraryName(name);
        // Class-path jars take precedence, then the bundled modules.
        for (Map.Entry<String, List<byte[]>> entry : resources.entrySet()) {
            if (matchesLibrary(entry.getKey(), mapped)) {
                return extractLibrary(mapped, entry.getValue().getFirst());
            }
        }
        for (Map.Entry<String, ModuleReader> entry : readers.entrySet()) {
            String resource;
            try (Stream<String> names = entry.getValue().list()) {
                resource = names.filter(candidate -> matchesLibrary(candidate, mapped)).findFirst().orElse(null);
            } catch (IOException _) {
                continue;
            }
            if (resource != null) {
                byte[] data = moduleResource(entry.getKey(), resource);
                if (data != null) {
                    return extractLibrary(mapped, data);
                }
            }
        }
        return null;
    }

    private static boolean matchesLibrary(String resource, String mapped) {
        return resource.equals(mapped) || resource.endsWith("/" + mapped);
    }

    private static String extractLibrary(String mapped, byte[] data) {
        try {
            Path file = Files.createTempFile("jenesis-", "-" + mapped);
            file.toFile().deleteOnExit();
            Files.write(file, data);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract native library " + mapped, e);
        }
    }
}
