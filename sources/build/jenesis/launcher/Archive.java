package build.jenesis.launcher;

import module java.base;

/**
 * The in-memory view of an executable jar produced by Jenesis.
 *
 * <p>The layout mirrors what the bundling step writes:</p>
 * <pre>
 *   foo.jar
 *   |- META-INF/MANIFEST.MF       (Main-Class: build.jenesis.launcher.Launcher)
 *   |- build/jenesis/launcher/... (this launcher, shaded into the jar root)
 *   |- application.properties     (mainClass=..., mainModule=...)
 *   |- classpath/*.jar            (non-modular dependencies)
 *   '- modulepath/*.jar           (modular and automatic dependencies)
 * </pre>
 *
 * <p>Each nested jar is read once, fully, into a {@code name -> bytes} map. Nothing is written to
 * disk. The same shape is also readable from an exploded directory (handy for tests and for running
 * straight out of a build's output folder), so {@link #load(Path)} accepts either a jar file or a
 * directory.</p>
 */
final class Archive {

    static final String APPLICATION = "application.properties";
    static final String CLASS_PATH = "classpath/";
    static final String MODULE_PATH = "modulepath/";

    /** A nested jar, fully read into memory. {@code name} is the bare file name (for module naming). */
    record Jar(String name, Map<String, byte[]> entries) {
    }

    private final Properties application = new Properties();
    private final List<Jar> classpath = new ArrayList<>();
    private final List<Jar> modulepath = new ArrayList<>();

    static Archive load(Path location) throws IOException {
        Archive archive = new Archive();
        if (Files.isDirectory(location)) {
            archive.loadDirectory(location);
        } else {
            archive.loadJar(location);
        }
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

    private void loadJar(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.equals(APPLICATION)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        application.load(in);
                    }
                } else if (name.startsWith(CLASS_PATH) && name.endsWith(".jar")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        classpath.add(read(fileName(name), in));
                    }
                } else if (name.startsWith(MODULE_PATH) && name.endsWith(".jar")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        modulepath.add(read(fileName(name), in));
                    }
                }
            }
        }
    }

    private void loadDirectory(Path directory) throws IOException {
        Path properties = directory.resolve(APPLICATION);
        if (Files.isRegularFile(properties)) {
            try (InputStream in = Files.newInputStream(properties)) {
                application.load(in);
            }
        }
        loadDirectory(directory.resolve("classpath"), classpath);
        loadDirectory(directory.resolve("modulepath"), modulepath);
    }

    private void loadDirectory(Path folder, List<Jar> target) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> jars = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path jar : jars) {
                try (InputStream in = Files.newInputStream(jar)) {
                    target.add(read(jar.getFileName().toString(), in));
                }
            }
        }
    }

    private static Jar read(String name, InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        // Do not close zip: it would close the shared outer stream owned by the caller.
        return new Jar(name, entries);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }
}
