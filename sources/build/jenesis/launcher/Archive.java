package build.jenesis.launcher;

import module java.base;

/**
 * The on-demand view of an executable jar produced by Jenesis.
 *
 * <p>The bundling step explodes each dependency into its own subfolder of the one store, so every class
 * and resource is a <em>direct</em> entry:</p>
 * <pre>
 *   foo.jar
 *   |- application.properties     (mainClass, classpath, modulepath, modulepath.&lt;name&gt;, classpath.&lt;name&gt;)
 *   |- build/jenesis/launcher/... (this launcher, shaded into the jar root)
 *   '- jars/&lt;dep&gt;/...             (a dependency, exploded; what it is for the descriptor names)
 * </pre>
 *
 * <p>A jar is stored once and may be named by more than one path, which is how a layer and the application
 * share a dependency without a second copy of it.</p>
 *
 * <p>Because each entry is addressable on its own, the launcher reads class and resource bytes straight
 * from the still-open outer jar (or the exploded directory) on demand and keeps only a name index in
 * memory - never the decompressed bytes. {@link #load(Path)} accepts either a jar file or a directory
 * laid out the same way.</p>
 */
final class Archive implements Closeable {

    static final String APPLICATION = "application.properties";
    /** The one store a bundle keeps its dependencies in; what each path holds is named, not placed. */
    static final String JARS = "jars/";
    /**
     * {@code application.properties} key prefixes naming what a layer holds on each of its two paths, by
     * file name. A layer is a module graph like any other, so it splits the same way the application does:
     * what carries a module identity is resolved, and the rest is the unnamed module of the layer's own
     * loader. Which jar goes where is decided by the build and named here, never re-derived at run time.
     *
     * <p>A layer's path is the application's own key qualified by the layer's name: {@code classpath} is
     * the application's and {@code classpath.render} is the layer {@code render}'s. A layer is named on its
     * own, not under the module that declared it. That is what the build
     * already keys it by - the {@code layer:<name>} dependency group it resolves in, and the pins written
     * against that group - so a name is global and a duplicate is refused there. Keying it here by the
     * declaring module instead would have required the caller to be a named module, which a jar cannot
     * promise: whoever consumes it decides whether it lands on the module path or the class path.</p>
     */
    static final String LAYER_MODULE_PATH = "modulepath.",
            LAYER_CLASS_PATH = "classpath.",
            LAYER_NATIVE_ACCESS = "enableNativeAccess.";

    /** Reads bytes and openable URLs for entries of the outer jar or directory, on demand. */
    interface Source extends Closeable {
        /** An open stream for {@code entry}, or {@code null} if it is absent; the caller closes it. */
        InputStream stream(String entry) throws IOException;

        /** All bytes of {@code entry}, or {@code null} if it is absent. */
        default byte[] open(String entry) throws IOException {
            try (InputStream in = stream(entry)) {
                return in == null ? null : in.readAllBytes();
            }
        }

        URL url(String entry);

        /** The URL of a dependency's exploded folder ({@code prefix}); a stable code-source / seal base. */
        URL baseUrl(String prefix);

        Set<String> names() throws IOException;
    }

    /**
     * A dependency exploded under {@code jars/<name>/}. {@code name} is
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

        /**
         * An open stream for {@code entry} in this dependency's multi-release view, or {@code null} if it is
         * absent; the caller closes it. Streams straight from the still-open jar/directory rather than
         * buffering, so a resource of any size is read without materialising it in full.
         */
        InputStream stream(String entry) {
            try {
                for (int version : versions) {
                    InputStream in = source.stream(prefix + VERSIONS + version + "/" + entry);
                    if (in != null) {
                        return in;
                    }
                }
                return source.stream(prefix + entry);
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
    private final List<Jar> stored = new ArrayList<>();
    private final List<Jar> classpath = new ArrayList<>();
    private final List<Jar> modulepath = new ArrayList<>();
    private final Map<String, Layer> layers = new LinkedHashMap<>();
    private Source source;

    static Archive load(Path location) throws IOException {
        Source source = Files.isDirectory(location)
                ? new DirectorySource(location)
                : new ZipSource(location);
        Archive archive = new Archive();
        archive.source = source;
        archive.index(source);
        archive.bind();
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

    /** What one layer holds: the modules it resolves, and the jars that are its unnamed module. */
    record Layer(List<Jar> modulepath, List<Jar> classpath) {
    }

    /**
     * The bundled module layers, each keyed {@code <declaring module>.<name>}. A layer's jars are stored
     * among the application's and told apart by the declaration alone, which is what keeps them off the
     * application's paths - two versions of one module are the point of a layer, and one configuration
     * cannot hold both.
     */
    Map<String, Layer> layers() {
        return layers;
    }

    /**
     * Closes the underlying jar (or directory) handle. The loader keeps it open to read classes and
     * resources on demand for as long as the application runs, so this is for the paths that load an archive
     * but build no loader from it, and for embedders that discard a loader; afterwards the archive's jars
     * can no longer be read.
     */
    @Override
    public void close() throws IOException {
        source.close();
    }

    private void index(Source source) throws IOException {
        byte[] properties = source.open(APPLICATION);
        if (properties != null) {
            application.load(new ByteArrayInputStream(properties));
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String entry : source.names()) {
            group(entry, JARS, groups);
        }
        collect(groups, JARS, source, stored);
    }

    /**
     * Resolves each {@code modulepath.<name>} and {@code classpath.<name>} declaration against the
     * dependencies already indexed. A layer's modules are bundled among the application's rather than
     * beside them, so a jar both of them need is stored once and simply loaded twice; the declaration is
     * what tells them apart.
     */
    private void bind() {
        Map<String, Jar> byName = new LinkedHashMap<>();
        stored.forEach(jar -> byName.putIfAbsent(jar.name(), jar));
        select(byName, application.getProperty("classpath"), classpath, "classpath");
        select(byName, application.getProperty("modulepath"), modulepath, "modulepath");
        Map<String, List<Jar>> modules = new LinkedHashMap<>(), classes = new LinkedHashMap<>();
        for (String key : application.stringPropertyNames()) {
            Map<String, List<Jar>> target;
            String prefix;
            if (key.startsWith(LAYER_MODULE_PATH)) {
                target = modules;
                prefix = LAYER_MODULE_PATH;
            } else if (key.startsWith(LAYER_CLASS_PATH)) {
                target = classes;
                prefix = LAYER_CLASS_PATH;
            } else {
                continue;
            }
            List<Jar> jars = new ArrayList<>();
            select(byName, application.getProperty(key), jars, key);
            target.put(key.substring(prefix.length()), jars);
        }
        Set<String> names = new LinkedHashSet<>(modules.keySet());
        names.addAll(classes.keySet());
        for (String name : names) {
            layers.put(name, new Layer(
                    modules.getOrDefault(name, List.of()),
                    classes.getOrDefault(name, List.of())));
        }
        if (!stored.isEmpty() && classpath.isEmpty() && modulepath.isEmpty() && layers.isEmpty()) {
            throw new IllegalStateException("This bundle holds " + stored.size()
                    + " jars and names none of them: classpath, modulepath, modulepath.<name> and"
                    + " classpath.<name> are all absent, and a path is read because the descriptor names it");
        }
    }

    /**
     * Resolves a declared comma-separated list of dependency names against the store, in the order it was
     * declared - which is the class path's order, and a deterministic one everywhere else. A name the store
     * does not hold is refused rather than skipped: a path that silently loses an entry fails later and
     * somewhere else.
     */
    private static void select(Map<String, Jar> byName, String declaration, List<Jar> target, String origin) {
        if (declaration == null || declaration.isBlank()) {
            return;
        }
        for (String name : declaration.split(",")) {
            String trimmed = name.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Jar jar = byName.get(trimmed);
            if (jar == null) {
                throw new IllegalStateException(origin + " names " + trimmed
                        + ", which this bundle does not hold");
            }
            target.add(jar);
        }
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
        public InputStream stream(String entry) throws IOException {
            ZipEntry zipEntry = zip.getEntry(entry);
            return zipEntry == null ? null : zip.getInputStream(zipEntry);
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

        @Override
        public void close() throws IOException {
            zip.close();
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
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public InputStream stream(String entry) throws IOException {
            Path file = confine(entry);
            return file != null && Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        }

        @Override
        public URL url(String entry) {
            Path file = confine(entry);
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            try {
                return file.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Failed to build a URL for " + entry, e);
            }
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
            Path base = root.resolve(JARS);
            if (Files.isDirectory(base)) {
                try (Stream<Path> files = Files.walk(base)) {
                    files.filter(Files::isRegularFile).forEach(file -> names.add(
                            JARS + base.relativize(file).toString().replace(File.separatorChar, '/')));
                }
            }
            return names;
        }

        @Override
        public void close() {
        }

        /**
         * Resolves {@code entry} against the bundle root, normalised, or {@code null} if it escapes the root.
         * Entry names reaching {@link #stream}/{@link #url} include arbitrary resource names handed to
         * {@code getResource*}; without this confinement a name like {@code ../../../etc/passwd} would
         * resolve on the real filesystem and read a file outside the bundle. (A symlink within the bundle
         * that points outside is the bundle author's own content and is not guarded here.)
         */
        private Path confine(String entry) {
            Path file = root.resolve(entry).normalize();
            return file.startsWith(root) ? file : null;
        }
    }
}
