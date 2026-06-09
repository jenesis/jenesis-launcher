package build.jenesis.launcher;

import module java.base;

/**
 * A {@link ModuleReader} over an exploded module ({@link Archive.Jar}), reading entries from the still-open
 * outer jar or directory on demand.
 *
 * <p>{@link #find(String)} returns the entry's standard {@code jar:}/{@code file:} URI, so the JDK resolves
 * module resources - including {@link Class#getResourceAsStream} for a class in a custom layer's loader,
 * which goes through {@link ModuleReader#find} and {@link java.net.URI#toURL} - with its own handlers; no
 * custom URL scheme is required.</p>
 */
final class ArchiveModuleReader implements ModuleReader {

    private final Archive.Jar jar;

    ArchiveModuleReader(Archive.Jar jar) {
        this.jar = jar;
    }

    @Override
    public Optional<URI> find(String name) {
        URL url = jar.url(name);
        if (url == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Malformed URL for " + name, e);
        }
    }

    @Override
    public Optional<InputStream> open(String name) {
        byte[] data = jar.open(name);
        return data == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(data));
    }

    @Override
    public Optional<ByteBuffer> read(String name) {
        byte[] data = jar.open(name);
        return data == null ? Optional.empty() : Optional.of(ByteBuffer.wrap(data).asReadOnlyBuffer());
    }

    @Override
    public Stream<String> list() {
        return jar.names().stream();
    }

    @Override
    public void close() {
    }
}
