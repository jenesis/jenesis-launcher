package build.jenesis.launcher;

import module java.base;

/**
 * The on-demand view of an executable jar produced by Jenesis.
 *
 * <p>The bundling step explodes each dependency into its own subfolder of the outer jar, so every class
 * and resource is a <em>direct</em> entry:</p>
 * <pre>
 *   foo.jar
 *   |- application.properties     (mainClass=..., mainModule=..., agentClass=...)
 *   |- build/jenesis/launcher/... (this launcher, shaded into the jar root)
 *   |- classpath/&lt;dep&gt;/...        (a non-modular dependency, exploded)
 *   '- modulepath/&lt;mod&gt;/...        (a modular or automatic dependency, exploded)
 * </pre>
 *
 * <p>Because each entry is addressable on its own, the launcher reads class and resource bytes straight
 * from the still-open outer jar (or the exploded directory) on demand and keeps only a name index in
 * memory - never the decompressed bytes. {@link #load(Path)} accepts either a jar file or a directory
 * laid out the same way.</p>
 */
final class Archive {

    static final String APPLICATION = "application.properties";
    static final String CLASS_PATH = "classpath/";
    static final String MODULE_PATH = "modulepath/";

    /** Reads bytes and openable URLs for entries of the outer jar or directory, on demand. */
    interface Source {
        byte[] open(String entry) throws IOException;

        URL url(String entry);

        Set<String> names() throws IOException;
    }

    /**
     * A dependency exploded under {@code classpath/<name>/} or {@code modulepath/<name>/}. {@code name} is
     * the original jar file name (so automatic-module naming is unchanged); {@link #names()} are its entry
     * names with that prefix stripped. Bytes and URLs are fetched from the {@link Source} lazily.
     */
    static final class Jar {

        private final String name;
        private final String prefix;
        private final List<String> names;
        private final Source source;

        Jar(String name, String prefix, List<String> names, Source source) {
            this.name = name;
            this.prefix = prefix;
            this.names = names;
            this.source = source;
        }

        String name() {
            return name;
        }

        List<String> names() {
            return names;
        }

        byte[] open(String entry) {
            try {
                return source.open(prefix + entry);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + prefix + entry, e);
            }
        }

        URL url(String entry) {
            return source.url(prefix + entry);
        }
    }

    private final Properties application = new Properties();
    private final List<Jar> classpath = new ArrayList<>();
    private final List<Jar> modulepath = new ArrayList<>();

    static Archive load(Path location) throws IOException {
        Source source = Files.isDirectory(location)
                ? new DirectorySource(location)
                : new ZipSource(location);
        Archive archive = new Archive();
        archive.index(source);
        // Deterministic order: class-path "first wins" and module discovery both depend on it.
        archive.classpath.sort(Comparator.comparing(Jar::name));
        archive.modulepath.sort(Comparator.comparing(Jar::name));
        return archive;
    }

    Properties application() {
        return application;
    }

    List<Jar> classpath() {
        return classpath;
    }

    List<Jar> modulepath() {
        return modulepath;
    }

    private void index(Source source) throws IOException {
        byte[] properties = source.open(APPLICATION);
        if (properties != null) {
            application.load(new ByteArrayInputStream(properties));
        }
        Map<String, List<String>> classpathGroups = new LinkedHashMap<>();
        Map<String, List<String>> modulepathGroups = new LinkedHashMap<>();
        for (String entry : source.names()) {
            group(entry, CLASS_PATH, classpathGroups);
            group(entry, MODULE_PATH, modulepathGroups);
        }
        collect(classpathGroups, CLASS_PATH, source, classpath);
        collect(modulepathGroups, MODULE_PATH, source, modulepath);
    }

    private static void group(String entry, String prefix, Map<String, List<String>> groups) {
        if (!entry.startsWith(prefix)) {
            return;
        }
        String rest = entry.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return;
        }
        groups.computeIfAbsent(rest.substring(0, slash), _ -> new ArrayList<>())
                .add(rest.substring(slash + 1));
    }

    private static void collect(Map<String, List<String>> groups, String prefix, Source source, List<Jar> target) {
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            List<String> names = group.getValue();
            names.sort(Comparator.naturalOrder());
            target.add(new Jar(group.getKey(), prefix + group.getKey() + "/", names, source));
        }
    }

    private static final class ZipSource implements Source {

        private final Path path;
        private final ZipFile zip;

        ZipSource(Path path) throws IOException {
            this.path = path.toAbsolutePath();
            this.zip = new ZipFile(this.path.toFile());
        }

        @Override
        public byte[] open(String entry) throws IOException {
            ZipEntry zipEntry = zip.getEntry(entry);
            if (zipEntry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(zipEntry)) {
                return in.readAllBytes();
            }
        }

        @Override
        public URL url(String entry) {
            if (zip.getEntry(entry) == null) {
                return null;
            }
            try {
                return URI.create("jar:" + path.toUri() + "!/" + encode(entry)).toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Failed to build a URL for " + entry, e);
            }
        }

        @Override
        public Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
            return names;
        }

        private static String encode(String entry) {
            try {
                // The multi-argument URI constructor percent-encodes the path component (keeping '/'),
                // exactly what the part after "!/" in a jar: URL needs - spaces, '%' and non-ASCII alike.
                return new URI(null, null, "/" + entry, null).getRawPath().substring(1);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Cannot encode entry " + entry, e);
            }
        }
    }

    private static final class DirectorySource implements Source {

        private final Path root;

        DirectorySource(Path root) {
            this.root = root;
        }

        @Override
        public byte[] open(String entry) throws IOException {
            Path file = root.resolve(entry);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }

        @Override
        public URL url(String entry) {
            Path file = root.resolve(entry);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return file.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Failed to build a URL for " + entry, e);
            }
        }

        @Override
        public Set<String> names() throws IOException {
            Set<String> names = new LinkedHashSet<>();
            if (Files.isRegularFile(root.resolve(APPLICATION))) {
                names.add(APPLICATION);
            }
            for (String prefix : List.of(CLASS_PATH, MODULE_PATH)) {
                Path base = root.resolve(prefix);
                if (Files.isDirectory(base)) {
                    try (Stream<Path> files = Files.walk(base)) {
                        files.filter(Files::isRegularFile).forEach(file -> names.add(
                                prefix + base.relativize(file).toString().replace(File.separatorChar, '/')));
                    }
                }
            }
            return names;
        }
    }
}
