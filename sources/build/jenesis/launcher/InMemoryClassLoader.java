package build.jenesis.launcher;

import module java.base;

/**
 * The single loader for every dependency in an executable jar - both the {@code classpath/} jars (its
 * unnamed module) and the {@code modulepath/} modules (defined to it as named modules through
 * {@link ModuleLayer#defineModules}). This mirrors how a real {@code java -p modulepath -cp classpath}
 * launch works: one application loader hosts the named modules and the unnamed module together, with the
 * {@link ModuleLayer} as metadata on top.
 *
 * <p>It holds no class or resource bytes - only the {@link Archive.Jar} handles and a package-to-module
 * index. Class and resource bytes are read from the still-open outer jar (or directory) on demand and
 * discarded after {@link #defineClass}. On the class path the first jar in iteration order wins, matching
 * class-path precedence; a package owned by a bundled module is served only from that module, so a
 * same-named package on the class path is shadowed - the JDK's own rule for {@code java -p ... -cp ...}.</p>
 *
 * <p>Resources are real entries of a real file, so {@link #findResource(String)} and
 * {@link #findResource(String, String)} hand back standard {@code jar:}/{@code file:} URLs that the JDK's
 * own handlers open - which is what {@link ClassLoader#getResources} (hence {@link java.util.ServiceLoader})
 * and {@link Class#getResourceAsStream} for a module class need. Native libraries cannot be loaded from
 * memory, so {@link #findLibrary(String)} extracts a requested library - from a class-path jar or a bundled
 * module - to a temp file on demand.</p>
 */
final class InMemoryClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final List<Archive.Jar> classpath;
    private final Map<String, String> packageToModule = new HashMap<>();
    private final Map<String, ModuleReader> readers = new LinkedHashMap<>();

    InMemoryClassLoader(List<Archive.Jar> classpath, InMemoryModuleFinder finder, ClassLoader parent)
            throws IOException {
        super("jenesis", parent);
        this.classpath = classpath;
        if (finder != null) {
            // Finder order is sorted by jar name, so a LinkedHashMap keeps the native-library winner stable.
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
        byte[] data = classpathResource(resource);
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
        // The Module/Class layer has already gated encapsulation before calling this, so serve whatever the
        // reader has; its find() yields a jar:/file: URI that the JDK's own handlers open.
        Optional<URI> uri = reader.find(name);
        return uri.isPresent() ? uri.get().toURL() : null;
    }

    @Override
    protected URL findResource(String name) {
        for (Archive.Jar jar : classpath) {
            URL url = jar.url(name);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        List<URL> urls = new ArrayList<>();
        for (Archive.Jar jar : classpath) {
            URL url = jar.url(name);
            if (url != null) {
                urls.add(url);
            }
        }
        return Collections.enumeration(urls);
    }

    private byte[] classpathResource(String name) {
        for (Archive.Jar jar : classpath) {
            byte[] data = jar.open(name);
            if (data != null) {
                return data;
            }
        }
        return null;
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
        // Class path takes precedence, then the bundled modules (deterministic via the LinkedHashMap).
        for (Archive.Jar jar : classpath) {
            for (String resource : jar.names()) {
                if (matchesLibrary(resource, mapped)) {
                    return extractLibrary(mapped, jar.open(resource));
                }
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
