package build.jenesis.launcher;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The on-demand view of an executable jar produced by Jenesis.
 *
 * <p>The bundling step explodes each dependency into its own subfolder of the one store, so every class
 * and resource is a <em>direct</em> entry:</p>
 * <pre>
 *   foo.jar
 *   |- META-INF/jenesis/application.properties (mainClass, classpath, modulepath, modulepath.&lt;name&gt;, classpath.&lt;name&gt;)
 *   |- build/jenesis/launcher/...              (this launcher, shaded into the jar root)
 *   '- jars/&lt;dep&gt;/...                          (a dependency, exploded; what it is for the descriptor names)
 * </pre>
 *
 * <p>The descriptor sits below {@code META-INF/jenesis/} rather than in the root, so it never takes the
 * name of a resource the application carries itself - an {@code application.properties} of its own above
 * all.</p>
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

    static final String APPLICATION = "META-INF/jenesis/application.properties";
    /** The one store a bundle keeps its dependencies in; what each path holds is named, not placed. */
    static final String JARS = "jars/";
    /**
     * Descriptor key prefixes naming what a layer holds on each of its two paths, by
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
    static final class Layer {

        private final List<Jar> modulepath, classpath;

        Layer(List<Jar> modulepath, List<Jar> classpath) {
            this.modulepath = modulepath;
            this.classpath = classpath;
        }

        List<Jar> modulepath() {
            return modulepath;
        }

        List<Jar> classpath() {
            return classpath;
        }
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
        groups.computeIfAbsent(rest.substring(0, slash), group -> new ArrayList<>())
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

    /**
     * The outer jar. Every URL it hands out keeps the standard {@code jar:file:<jar>!/jars/<dep>/<entry>}
     * form, so code reading the URL as text sees the entry it names, but carries a handler of its own:
     * the {@link JarURLConnection} it opens presents the dependency as a jar of its own, rooted at
     * {@code jars/<dep>/}, as the JDK's class path presents a jar. A scanner that lists a class-path folder
     * by opening the jar behind its URL - Flyway lists {@code db/migration/} so - then finds the folder at
     * the root of that jar rather than nothing, as it would in the outer jar, where the folder sits below
     * {@code jars/<dep>/}. A URL that names no dependency, or that is rebuilt from its text, opens as a
     * plain {@code jar:} URL on the outer jar.
     */
    private static final class ZipSource implements Source {

        private final Path path;
        private final ZipFile zip;
        private final String file;
        private final URLStreamHandler handler = new DependencyHandler();
        private final Map<String, DependencyJarFile> views = new ConcurrentHashMap<>();
        private volatile Map<String, List<String>> listings;

        ZipSource(Path path) throws IOException {
            this.path = path.toAbsolutePath();
            this.zip = new ZipFile(this.path.toFile());
            this.file = this.path.toUri().toString();
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
                return new URL(null, "jar:" + file + "!/" + encode(entry), handler);
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
            try {
                for (DependencyJarFile view : views.values()) {
                    view.close();
                }
            } finally {
                zip.close();
            }
        }

        /**
         * The entries below each dependency's folder, directories included and named relative to it, in the
         * order of the outer jar; read once, on the first connection that needs them.
         */
        private Map<String, List<String>> listings() {
            Map<String, List<String>> listings = this.listings;
            if (listings == null) {
                listings = new LinkedHashMap<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    String prefix = prefix(name);
                    if (prefix != null && name.length() > prefix.length()) {
                        listings.computeIfAbsent(prefix, ignored -> new ArrayList<>())
                                .add(name.substring(prefix.length()));
                    }
                }
                this.listings = listings;
            }
            return listings;
        }

        private DependencyJarFile view(String prefix, boolean shared) throws IOException {
            if (!shared) {
                return new DependencyJarFile(path.toFile(), prefix, listings().getOrDefault(prefix, List.of()));
            }
            DependencyJarFile view = views.get(prefix);
            if (view == null) {
                DependencyJarFile created = view(prefix, false);
                view = views.putIfAbsent(prefix, created);
                if (view == null) {
                    view = created;
                } else {
                    created.close();
                }
            }
            return view;
        }

        /** The dependency folder {@code jars/<dep>/} that an entry name lies in, or {@code null} if none. */
        private static String prefix(String entry) {
            if (!entry.startsWith(JARS)) {
                return null;
            }
            int slash = entry.indexOf('/', JARS.length());
            return slash == -1 ? entry + "/" : entry.substring(0, slash + 1);
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

        /**
         * Opens a URL of this jar that names a dependency through a {@link DependencyConnection}, and any
         * other as the JDK's own {@code jar:} handler does. It parses a URL relative to another as that
         * handler does too: below the entry it names, or from the jar's root for a name starting with a
         * slash.
         */
        private final class DependencyHandler extends URLStreamHandler {

            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                String spec = url.getFile();
                int separator = spec.indexOf("!/");
                if (separator != -1 && spec.substring(0, separator).equals(file)) {
                    String entry = decode(spec.substring(separator + 2));
                    String prefix = prefix(entry);
                    if (prefix != null) {
                        return new DependencyConnection(url, prefix, entry.length() > prefix.length()
                                ? entry.substring(prefix.length())
                                : null);
                    }
                }
                return new URL(url.toExternalForm()).openConnection();
            }

            @Override
            protected void parseURL(URL url, String spec, int start, int limit) {
                String part = spec.substring(start, limit);
                String context = url.getPath();
                int separator = context == null ? -1 : context.indexOf("!/");
                if (part.contains("!/") && !part.startsWith("/")) {
                    setURL(url, "jar", null, -1, null, null, part, null, url.getRef());
                } else if (part.startsWith("/") && separator != -1) {
                    setURL(url, "jar", null, -1, null, null,
                            context.substring(0, separator + 1) + part, null, url.getRef());
                } else {
                    super.parseURL(url, spec, start, limit);
                }
            }

            private String decode(String entry) {
                try {
                    return new URI("entry:/" + entry).getPath().substring(1);
                } catch (URISyntaxException e) {
                    return entry;
                }
            }
        }

        /**
         * A connection to an entry of one dependency, whose {@link #getJarFile()} is that dependency as a jar
         * of its own and whose {@link #getEntryName()} and {@link #getJarEntry()} name the entry within it.
         * As the JDK's own does, it hands out one jar per dependency while caches are in use and a jar of its
         * own otherwise, which closes with the stream read from it or is the caller's to close.
         */
        private final class DependencyConnection extends JarURLConnection {

            private final String prefix, entry;
            private DependencyJarFile view;
            private JarEntry jarEntry;

            DependencyConnection(URL url, String prefix, String entry) throws MalformedURLException {
                super(url);
                this.prefix = prefix;
                this.entry = entry;
            }

            @Override
            public void connect() throws IOException {
                if (connected) {
                    return;
                }
                view = view(prefix, getUseCaches());
                if (entry != null) {
                    jarEntry = view.getJarEntry(entry);
                    if (jarEntry == null) {
                        if (!getUseCaches()) {
                            view.close();
                        }
                        throw new FileNotFoundException("JAR entry " + entry + " not found in " + prefix
                                + " of " + path);
                    }
                }
                connected = true;
            }

            @Override
            public JarFile getJarFile() throws IOException {
                connect();
                return view;
            }

            @Override
            public JarEntry getJarEntry() throws IOException {
                connect();
                return jarEntry;
            }

            @Override
            public String getEntryName() {
                return entry;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                connect();
                if (jarEntry == null) {
                    throw new IOException("no entry name specified");
                }
                InputStream in = view.getInputStream(jarEntry);
                if (in == null) {
                    throw new FileNotFoundException("JAR entry " + entry + " not found in " + prefix + " of " + path);
                }
                if (getUseCaches()) {
                    return in;
                }
                DependencyJarFile owned = view;
                return new FilterInputStream(in) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            owned.close();
                        }
                    }
                };
            }

            @Override
            public long getContentLengthLong() {
                try {
                    connect();
                } catch (IOException e) {
                    return -1;
                }
                return jarEntry == null ? -1 : jarEntry.getSize();
            }

            @Override
            public int getContentLength() {
                long length = getContentLengthLong();
                return length > Integer.MAX_VALUE ? -1 : (int) length;
            }

            @Override
            public String getContentType() {
                String type = entry == null ? null : guessContentTypeFromName(entry);
                return type == null ? "content/unknown" : type;
            }

            @Override
            public long getLastModified() {
                try {
                    connect();
                } catch (IOException e) {
                    return 0;
                }
                return jarEntry == null ? 0 : Math.max(jarEntry.getTime(), 0);
            }
        }
    }

    /**
     * One dependency of the outer jar as a jar of its own: its entries are those below {@code jars/<dep>/},
     * named relative to it, and read on demand from the outer jar, which this file opens a second time. It
     * is the raw jar, as a {@code jar:} URL without a {@code #runtime} fragment opens one: a multi-release
     * dependency lists its {@code META-INF/versions/} entries as they are.
     */
    private static final class DependencyJarFile extends JarFile {

        private final String prefix;
        private final List<String> names;

        DependencyJarFile(File file, String prefix, List<String> names) throws IOException {
            super(file, false);
            this.prefix = prefix;
            this.names = names;
        }

        @Override
        public ZipEntry getEntry(String name) {
            ZipEntry original = super.getEntry(prefix + name);
            if (original == null) {
                return null;
            }
            JarEntry entry = new JarEntry(original.getName().substring(prefix.length()));
            entry.setMethod(original.getMethod());
            if (original.getTime() != -1) {
                entry.setTime(original.getTime());
            }
            if (original.getSize() != -1) {
                entry.setSize(original.getSize());
            }
            if (original.getCompressedSize() != -1) {
                entry.setCompressedSize(original.getCompressedSize());
            }
            if (original.getCrc() != -1) {
                entry.setCrc(original.getCrc());
            }
            entry.setComment(original.getComment());
            return entry;
        }

        @Override
        public JarEntry getJarEntry(String name) {
            return (JarEntry) getEntry(name);
        }

        @Override
        public Enumeration<JarEntry> entries() {
            java.util.Iterator<String> iterator = names.iterator();
            return new Enumeration<JarEntry>() {
                @Override
                public boolean hasMoreElements() {
                    return iterator.hasNext();
                }

                @Override
                public JarEntry nextElement() {
                    return getJarEntry(iterator.next());
                }
            };
        }

        @Override
        public Stream<JarEntry> stream() {
            return names.stream().map(this::getJarEntry);
        }

        @Override
        public Stream<JarEntry> versionedStream() {
            return stream();
        }

        @Override
        public int size() {
            return names.size();
        }

        @Override
        public synchronized InputStream getInputStream(ZipEntry entry) throws IOException {
            ZipEntry original = super.getEntry(prefix + entry.getName());
            return original == null ? null : super.getInputStream(original);
        }

        @Override
        public Manifest getManifest() throws IOException {
            ZipEntry manifest = super.getEntry(prefix + MANIFEST_NAME);
            if (manifest == null) {
                return null;
            }
            try (InputStream in = super.getInputStream(manifest)) {
                return new Manifest(in);
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
            if (file == null || !Files.isRegularFile(file) && !Files.isDirectory(file)) {
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
