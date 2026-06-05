package build.jenesis.launcher;

import module java.base;

/**
 * Serves the in-memory {@code jenesismem:} URL scheme used by {@link MemoryUrlRegistry}.
 *
 * <p>This provider is discovered two ways, matching the two ways the launcher can run:</p>
 * <ul>
 *   <li><b>Shaded onto the class path</b> (the executable-jar case): the
 *       {@code META-INF/services/java.net.spi.URLStreamHandlerProvider} entry that ships with the
 *       launcher is found on the system class path by {@link java.util.ServiceLoader}.</li>
 *   <li><b>As a named module</b>: the {@code provides java.net.spi.URLStreamHandlerProvider}
 *       clause in {@code module-info} registers it.</li>
 * </ul>
 *
 * <p>The provider is inert for every other scheme, so registering it globally has no side effects
 * beyond the launcher's own URLs.</p>
 */
public final class MemoryUrlStreamHandlerProvider extends URLStreamHandlerProvider {

    public MemoryUrlStreamHandlerProvider() {
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if (!MemoryUrlRegistry.SCHEME.equals(protocol)) {
            return null;
        }
        return new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                byte[] data = MemoryUrlRegistry.lookup(url.getPath());
                if (data == null) {
                    throw new FileNotFoundException(url.toString());
                }
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(data);
                    }

                    @Override
                    public long getContentLengthLong() {
                        return data.length;
                    }
                };
            }
        };
    }
}
