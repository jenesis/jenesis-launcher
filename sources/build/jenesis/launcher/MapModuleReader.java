package build.jenesis.launcher;

import module java.base;

/**
 * A {@link ModuleReader} over a nested jar that has been fully read into a {@code name -> bytes} map.
 *
 * <p>{@link #open(String)} and {@link #read(String)} hand back the in-memory bytes directly.
 * {@link #find(String)} must return a URI whose {@link java.net.URI#toURL()} can be opened, because
 * the layer's loader resolves module resources (including {@link Class#getResourceAsStream}) through
 * it; the URI therefore points into the {@link MemoryUrlRegistry}.</p>
 */
final class MapModuleReader implements ModuleReader {

    private final Map<String, byte[]> entries;
    private final Map<String, URI> uris = new ConcurrentHashMap<>();

    MapModuleReader(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    @Override
    public Optional<URI> find(String name) {
        byte[] data = entries.get(name);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(uris.computeIfAbsent(name, _ -> MemoryUrlRegistry.register(data)));
    }

    @Override
    public Optional<InputStream> open(String name) {
        byte[] data = entries.get(name);
        return data == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(data));
    }

    @Override
    public Optional<ByteBuffer> read(String name) {
        byte[] data = entries.get(name);
        return data == null ? Optional.empty() : Optional.of(ByteBuffer.wrap(data).asReadOnlyBuffer());
    }

    @Override
    public Stream<String> list() {
        return List.copyOf(entries.keySet()).stream();
    }

    @Override
    public void close() {
    }
}
