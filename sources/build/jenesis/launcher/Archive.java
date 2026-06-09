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

        /** The URL of a dependency's exploded folder ({@code prefix}); a stable code-source / seal base. */
        URL baseUrl(String prefix);

        Set<String> names() throws IOException;
    }

    /**
     * A dependency exploded under {@code classpath/<name>/} or {@code modulepath/<name>/}. {@code name} is
     * the original jar file name (so automatic-module naming is unchanged); {@link #names()} are its entry
     * names with that prefix stripped, presented in the multi-release view. Bytes and URLs are fetched from
     * the {@link Source} lazily.
     *
     * <p>For a multi-release dependency, {@code versions} holds the {@code META-INF/versions/<n>/} versions
     * that are actually present and not above the runtime - highest first - so a lookup checks only those
     * overlays before falling back to the base entry, rather than probing every release.</p>
     */
    static final class Jar {

        private final String name;
        private final String prefix;
        private final List<String> names;
        private final int[] versions;
        private final Source source;

        Jar(String name, String prefix, List<String> names, int[] versions, Source source) {
            this.name = name;
            this.prefix = prefix;
            this.names = names;
            this.versions = versions;
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
                for (int version : versions) {
                    byte[] data = source.open(prefix + VERSIONS + version + "/" + entry);
                    if (data != null) {
                        return data;
                    }
                }
                return source.open(prefix + entry);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + prefix + entry, e);
            }
        }

        URL url(String entry) {
            for (int version : versions) {
                URL url = source.url(prefix + VERSIONS + version + "/" + entry);
                if (url != null) {
                    return url;
                }
            }
            return source.url(prefix + entry);
        }

        /** The dependency's own folder URL - its code source and seal base. */
        URL url() {
            return source.baseUrl(prefix);
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

    private static final String VERSIONS = "META-INF/versions/";

    private static void collect(Map<String, List<String>> groups, String prefix, Source source, List<Jar> target)
            throws IOException {
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            String groupPrefix = prefix + group.getKey() + "/";
            int[] versions = multiReleaseVersions(group.getValue(), source, groupPrefix);
            List<String> names = effectiveNames(group.getValue(), versions);
            names.sort(Comparator.naturalOrder());
            target.add(new Jar(group.getKey(), groupPrefix, names, versions, source));
        }
    }

    /**
     * The {@code META-INF/versions/<n>/} versions a multi-release dependency actually ships that are not
     * above the runtime, highest first; empty if the dependency is not multi-release.
     */
    private static int[] multiReleaseVersions(List<String> names, Source source, String groupPrefix)
            throws IOException {
        byte[] manifest = source.open(groupPrefix + "META-INF/MANIFEST.MF");
        if (manifest == null || !isMultiRelease(manifest)) {
            return new int[0];
        }
        int runtime = Runtime.version().feature();
        SortedSet<Integer> versions = new TreeSet<>(Comparator.reverseOrder());
        for (String name : names) {
            int version = versionOf(name);
            if (version >= 9 && version <= runtime) {
                versions.add(version);
            }
        }
        int[] result = new int[versions.size()];
        int index = 0;
        for (int version : versions) {
            result[index++] = version;
        }
        return result;
    }

    private static boolean isMultiRelease(byte[] manifest) {
        try {
            return Boolean.parseBoolean(new Manifest(new ByteArrayInputStream(manifest))
                    .getMainAttributes().getValue("Multi-Release"));
        } catch (IOException e) {
            return false;
        }
    }

    /** The release of a {@code META-INF/versions/<n>/...} entry, or {@code -1} if it is not a versioned entry. */
    private static int versionOf(String name) {
        if (!name.startsWith(VERSIONS)) {
            return -1;
        }
        String rest = name.substring(VERSIONS.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(rest.substring(0, slash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The multi-release view of the entry names: base entries plus the applicable versioned entries under
     * their base names, with the literal {@code META-INF/versions/} entries removed. For a non-multi-release
     * dependency the names are returned unchanged.
     */
    private static List<String> effectiveNames(List<String> names, int[] versions) {
        if (versions.length == 0) {
            return new ArrayList<>(names);
        }
        Set<Integer> applicable = new HashSet<>();
        for (int version : versions) {
            applicable.add(version);
        }
        Set<String> effective = new LinkedHashSet<>();
        for (String name : names) {
            if (name.startsWith(VERSIONS)) {
                if (applicable.contains(versionOf(name))) {
                    String rest = name.substring(VERSIONS.length());
                    effective.add(rest.substring(rest.indexOf('/') + 1));
                }
                // A version above the runtime (or unparseable) is ignored, as the JDK does.
            } else {
                effective.add(name);
            }
        }
        return new ArrayList<>(effective);
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
            return zip.getEntry(entry) == null ? null : baseUrl(entry);
        }

        @Override
        public URL baseUrl(String entry) {
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
            return Files.isRegularFile(root.resolve(entry)) ? baseUrl(entry) : null;
        }

        @Override
        public URL baseUrl(String entry) {
            try {
                return root.resolve(entry).toUri().toURL();
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
