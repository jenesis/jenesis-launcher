package build.jenesis.launcher;

import module java.base;

/**
 * Loads classes and resources for the non-modular ("class-path") nested jars straight from memory.
 *
 * <p>This is the in-memory analogue of the unnamed module that {@code -cp} produces. All class-path
 * jars are merged into one loader; on a class-name clash the first jar in iteration order wins, which
 * matches class-path precedence. The loader is also used as the <em>parent</em> of the module
 * layer's loader (see {@link Launcher}), so automatic modules in the layer can read these classes as
 * the JDK's own launcher allows, while strict named modules cannot - exactly the readability rules
 * of {@code java -p modulepath -cp classpath}.</p>
 *
 * <p>Resources are exposed through the {@code jenesismem:} URL scheme so that
 * {@link ClassLoader#getResources(String)} - and therefore {@link java.util.ServiceLoader} for
 * class-path providers - returns openable URLs. Native libraries cannot be loaded from memory, so
 * {@link #findLibrary(String)} extracts a requested library to a temp file on demand.</p>
 */
final class InMemoryClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> classes = new HashMap<>();
    private final Map<String, List<byte[]>> resources = new HashMap<>();
    private final Map<String, List<URL>> resourceUrls = new ConcurrentHashMap<>();

    InMemoryClassLoader(List<Archive.Jar> jars, ClassLoader parent) {
        super("jenesis-classpath", parent);
        for (Archive.Jar jar : jars) {
            for (Map.Entry<String, byte[]> entry : jar.entries().entrySet()) {
                String name = entry.getKey();
                byte[] data = entry.getValue();
                resources.computeIfAbsent(name, _ -> new ArrayList<>()).add(data);
                if (name.endsWith(".class")) {
                    classes.putIfAbsent(name, data);
                }
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] data = classes.get(name.replace('.', '/') + ".class");
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

    @Override
    protected String findLibrary(String name) {
        String mapped = System.mapLibraryName(name);
        for (Map.Entry<String, List<byte[]>> entry : resources.entrySet()) {
            String resource = entry.getKey();
            if (resource.equals(mapped) || resource.endsWith("/" + mapped)) {
                try {
                    Path file = Files.createTempFile("jenesis-", "-" + mapped);
                    file.toFile().deleteOnExit();
                    Files.write(file, entry.getValue().getFirst());
                    return file.toAbsolutePath().toString();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to extract native library " + mapped, e);
                }
            }
        }
        return null;
    }
}
