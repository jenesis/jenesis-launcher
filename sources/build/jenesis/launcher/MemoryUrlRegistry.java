package build.jenesis.launcher;

import module java.base;

/**
 * A process-wide registry that backs the in-memory {@code jenesismem:} URL scheme.
 *
 * <p>The launcher never extracts the nested jars to disk, yet several JDK code paths insist on a
 * {@link java.net.URL} that can be opened: {@link ClassLoader#getResources(String)} (used by
 * {@link java.util.ServiceLoader} for class-path providers) and, for modules loaded into a custom
 * {@link ModuleLayer}, even {@link Class#getResourceAsStream(String)} resolves through
 * {@link java.lang.module.ModuleReader#find(String)} and {@link java.net.URI#toURL()}. Both end up
 * calling {@code URI.toURL()}, which can only succeed if a {@link java.net.URLStreamHandler} is
 * registered for the scheme. Passing an explicit handler to {@code URL.of(uri, handler)} is not an
 * option there because the JDK constructs those URLs itself.</p>
 *
 * <p>So the launcher registers a {@link MemoryUrlStreamHandlerProvider} for the {@code jenesismem:}
 * scheme (via {@code META-INF/services} when shaded onto the class path, and via the module
 * descriptor's {@code provides} clause when run as a module). A resource is registered here once and
 * addressed by an opaque, monotonically increasing id; the handler resolves the id back to the
 * bytes. Ids are never freed, but registration is lazy - it only happens for resources that are
 * actually resolved to a URL, not for every entry that is merely loaded as a class.</p>
 */
final class MemoryUrlRegistry {

    static final String SCHEME = "jenesismem";

    private static final Map<String, byte[]> DATA = new ConcurrentHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private MemoryUrlRegistry() {
    }

    static URI register(byte[] data) {
        String id = Long.toString(SEQUENCE.getAndIncrement());
        DATA.put(id, data);
        return URI.create(SCHEME + ":/" + id);
    }

    static byte[] lookup(String path) {
        if (path == null) {
            return null;
        }
        return DATA.get(path.startsWith("/") ? path.substring(1) : path);
    }
}
