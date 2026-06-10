package build.jenesis.launcher;

import module java.base;

import java.security.cert.CertificateException;
import java.util.jar.Attributes;

/**
 * The single loader for every dependency in an executable jar - both the {@code classpath/} jars (its
 * unnamed module) and the {@code modulepath/} modules (defined to it as named modules through
 * {@link ModuleLayer#defineModules}). This mirrors how a real {@code java -p modulepath -cp classpath}
 * launch works: one application loader hosts the named modules and the unnamed module together, with the
 * {@link ModuleLayer} as metadata on top.
 *
 * <p>It holds no class or resource bytes - only the {@link Archive.Jar} handles and a package-to-module
 * index. Class and resource bytes are read from the still-open outer jar (or directory) on demand and
 * discarded after {@link #defineClass}. On the class path the first jar in iteration order wins, matching
 * class-path precedence; a package owned by a bundled module is served only from that module, so a
 * same-named package on the class path is shadowed - the JDK's own rule for {@code java -p ... -cp ...}.</p>
 *
 * <p>Resources are real entries of a real file, so {@link #findResource(String)} and
 * {@link #findResource(String, String)} hand back standard {@code jar:}/{@code file:} URLs that the JDK's
 * own handlers open - which is what {@link ClassLoader#getResources} (hence {@link java.util.ServiceLoader})
 * and {@link Class#getResourceAsStream} for a module class need. Native libraries cannot be loaded from
 * memory, so {@link #findLibrary(String)} extracts a requested library - from a class-path jar or a bundled
 * module - to a temp file on demand.</p>
 */
final class InMemoryClassLoader extends ClassLoader implements Closeable {

    static {
        registerAsParallelCapable();
    }

    /** {@code application.properties} key prefix for a dependency's optional signer certificate chain. */
    private static final String SIGNATURE_PREFIX = "signature.";

    private final Archive archive;
    private final List<Archive.Jar> classpath;
    private final Map<String, String> packageToModule = new HashMap<>();
    private final Map<String, ModuleDescriptor> descriptors = new HashMap<>();
    private final Map<String, ProtectionDomain> moduleDomains = new HashMap<>();
    private final Map<String, ModuleReader> readers = new LinkedHashMap<>();
    private final Map<String, ProtectionDomain> domains = new ConcurrentHashMap<>();
    private final Map<String, Optional<Manifest>> manifests = new ConcurrentHashMap<>();

    InMemoryClassLoader(Archive archive, InMemoryModuleFinder finder, ClassLoader parent)
            throws IOException {
        super("jenesis", parent);
        this.archive = archive;
        this.classpath = archive.classpath();
        if (finder != null) {
            // Finder order is sorted by jar name, so a LinkedHashMap keeps the native-library winner stable.
            for (ModuleReference reference : finder.findAll()) {
                ModuleDescriptor descriptor = reference.descriptor();
                String module = descriptor.name();
                readers.put(module, reference.open());
                descriptors.put(module, descriptor);
                // A class defined from a module gets a CodeSource at the module's location, as a real
                // module-path class does (with no signers, matching the JDK's module-path loader).
                reference.location().ifPresent(uri -> moduleDomains.put(module, moduleDomain(uri)));
                for (String packageName : descriptor.packages()) {
                    packageToModule.putIfAbsent(packageName, module);
                }
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        String module = packageToModule.get(packageOf(name));
        if (module != null) {
            // The package belongs to a bundled module: serve only the module copy, so a same-package class
            // on the class path is shadowed - the JDK's own rule for `java -p modulepath -cp classpath`.
            byte[] data = moduleResource(module, resource);
            if (data == null) {
                throw new ClassNotFoundException(name);
            }
            // The package is recorded against this loader (defineModules ran first), so the VM assigns the
            // class to its module automatically; do not definePackage for a module package. The module's
            // ProtectionDomain (null if it has no location) gives the class a CodeSource at the module.
            return defineClass(name, data, 0, data.length, moduleDomains.get(module));
        }
        for (Archive.Jar jar : classpath) {
            byte[] data = jar.open(resource);
            if (data != null) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    definePackage(jar, name.substring(0, dot));
                }
                // The dependency's CodeSource lets sealed packages (defined below) be honored.
                return defineClass(name, data, 0, data.length, domain(jar));
            }
        }
        throw new ClassNotFoundException(name);
    }

    private void definePackage(Archive.Jar jar, String packageName) {
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        Manifest manifest = manifest(jar);
        try {
            if (manifest == null) {
                definePackage(packageName, null, null, null, null, null, null, null);
                return;
            }
            // Reproduce URLClassLoader's manifest reading: a per-package section (Name: pkg/) overrides the
            // main attributes; a sealed package is sealed to the dependency's URL, which matches the
            // CodeSource of the classes defined into it, so sealing is honored rather than violated.
            Attributes main = manifest.getMainAttributes();
            Attributes pkg = manifest.getAttributes(packageName.replace('.', '/') + "/");
            URL sealBase = Boolean.parseBoolean(attribute(Attributes.Name.SEALED, pkg, main)) ? jar.url() : null;
            definePackage(packageName,
                    attribute(Attributes.Name.SPECIFICATION_TITLE, pkg, main),
                    attribute(Attributes.Name.SPECIFICATION_VERSION, pkg, main),
                    attribute(Attributes.Name.SPECIFICATION_VENDOR, pkg, main),
                    attribute(Attributes.Name.IMPLEMENTATION_TITLE, pkg, main),
                    attribute(Attributes.Name.IMPLEMENTATION_VERSION, pkg, main),
                    attribute(Attributes.Name.IMPLEMENTATION_VENDOR, pkg, main),
                    sealBase);
        } catch (IllegalArgumentException _) {
            // Raced with another thread defining the same package; harmless.
        }
    }

    private static String attribute(Attributes.Name name, Attributes pkg, Attributes main) {
        String value = pkg == null ? null : pkg.getValue(name);
        return value != null ? value : main.getValue(name);
    }

    private ProtectionDomain domain(Archive.Jar jar) {
        return domains.computeIfAbsent(jar.name(), name ->
                new ProtectionDomain(new CodeSource(jar.url(), signers(name)), null));
    }

    /** A protection domain whose code source is a module's exploded-folder location (no signers, as on the module path). */
    private static ProtectionDomain moduleDomain(URI location) {
        try {
            return new ProtectionDomain(new CodeSource(location.toURL(), (CodeSigner[]) null), null);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Cannot build a code source for module location " + location, e);
        }
    }

    /**
     * The signer certificates to attach to a class-path dependency's {@link CodeSource}, reconstructed from
     * an optional {@code application.properties} entry {@code signature.<dependency>} (Base64 of the signer's
     * PKCS#7 certificate chain), or {@code null} when none is declared. This restores the signer identity that
     * {@link CodeSource#getCodeSigners()} / {@link CodeSource#getCertificates()} report for a dependency that
     * was a signed jar - the same attested reconstruction the loader already does for a package's manifest
     * metadata and sealing. It records the signer the bundler attested; it does not cryptographically re-verify
     * the bundled bytes. The Base64 value is ASCII, so it round-trips through the ISO-8859-1 properties file.
     */
    private CodeSigner[] signers(String name) {
        String encoded = archive.application().getProperty(SIGNATURE_PREFIX + name);
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(
                    new ByteArrayInputStream(Base64.getDecoder().decode(encoded.strip())), "PKCS7");
            return new CodeSigner[] {new CodeSigner(path, null)};
        } catch (CertificateException | IllegalArgumentException e) {
            throw new IllegalStateException("Malformed signer certificate chain in property '"
                    + SIGNATURE_PREFIX + name + "'", e);
        }
    }

    private Manifest manifest(Archive.Jar jar) {
        return manifests.computeIfAbsent(jar.name(), _ -> {
            byte[] bytes = jar.open("META-INF/MANIFEST.MF");
            if (bytes == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Manifest(new ByteArrayInputStream(bytes)));
            } catch (IOException _) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    @Override
    protected Class<?> findClass(String moduleName, String name) {
        // Called by the VM and Class.forName(Module, name) for a class in a module defined to this loader.
        byte[] data = moduleResource(moduleName, name.replace('.', '/') + ".class");
        if (data == null) {
            return null;
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            return loaded != null ? loaded : defineClass(name, data, 0, data.length, moduleDomains.get(moduleName));
        }
    }

    @Override
    protected URL findResource(String moduleName, String name) throws IOException {
        if (moduleName == null) {
            return null;
        }
        ModuleReader reader = readers.get(moduleName);
        if (reader == null) {
            return null;
        }
        // The Module/Class layer has already gated encapsulation before calling this, so serve whatever the
        // reader has; its find() yields a jar:/file: URI that the JDK's own handlers open.
        Optional<URI> uri = reader.find(name);
        return uri.isPresent() ? uri.get().toURL() : null;
    }

    @Override
    protected URL findResource(String name) {
        // Mirror BuiltinClassLoader: a bundled module's resources are visible on the flat resource API too,
        // but only when not encapsulated (see moduleResourceUrls), and modules are consulted before the
        // class path - as in a real `java -p modulepath -cp classpath` launch.
        List<URL> module = moduleResourceUrls(name);
        if (!module.isEmpty()) {
            return module.get(0);
        }
        for (Archive.Jar jar : classpath) {
            URL url = jar.url(name);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        List<URL> urls = new ArrayList<>(moduleResourceUrls(name));
        for (Archive.Jar jar : classpath) {
            URL url = jar.url(name);
            if (url != null) {
                urls.add(url);
            }
        }
        return Collections.enumeration(urls);
    }

    /**
     * The URLs under which {@code name} is visible on the flat resource API from the bundled modules,
     * reproducing the rule a builtin loader applies (so the launcher matches a real
     * {@code java -p modulepath -cp classpath} launch). A resource that maps to a module package is served
     * only when it is a {@code .class} file, a directory, or its package is opened unconditionally (an open
     * or automatic module, or an unqualified {@code opens}); otherwise it stays encapsulated. A resource in
     * no module package (a top-level entry, anything under {@code META-INF/}) is served from every module.
     * Qualified opens and runtime {@code addOpens} do not widen this, matching the JDK.
     */
    private List<URL> moduleResourceUrls(String name) {
        String packageName = toPackageName(name);
        String module = packageToModule.get(packageName);
        if (module != null) {
            URL url = moduleResourceUrl(module, name);
            if (url != null
                    && (name.endsWith(".class") || url.toString().endsWith("/") || isOpen(module, packageName))) {
                return List.of(url);
            }
            return List.of();
        }
        List<URL> urls = new ArrayList<>();
        for (String candidate : readers.keySet()) {
            URL url = moduleResourceUrl(candidate, name);
            if (url != null) {
                urls.add(url);
            }
        }
        return urls;
    }

    private URL moduleResourceUrl(String module, String name) {
        ModuleReader reader = readers.get(module);
        if (reader == null) {
            return null;
        }
        try {
            Optional<URI> uri = reader.find(name);
            return uri.isPresent() ? uri.get().toURL() : null;
        } catch (IOException _) {
            return null;
        }
    }

    /** Whether {@code packageName} of {@code module} is opened unconditionally, matching {@code isOpen} in the JDK. */
    private boolean isOpen(String module, String packageName) {
        ModuleDescriptor descriptor = descriptors.get(module);
        if (descriptor == null) {
            return false;
        }
        if (descriptor.isOpen() || descriptor.isAutomatic()) {
            return true;
        }
        for (ModuleDescriptor.Opens opens : descriptor.opens()) {
            if (!opens.isQualified() && opens.source().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** The package of a resource name (the JDK's {@code Resources.toPackageName}); {@code ""} if it has none. */
    private static String toPackageName(String name) {
        int slash = name.lastIndexOf('/');
        return slash == -1 || slash == name.length() - 1 ? "" : name.substring(0, slash).replace('/', '.');
    }

    private byte[] moduleResource(String module, String name) {
        ModuleReader reader = readers.get(module);
        if (reader == null) {
            return null;
        }
        try {
            Optional<InputStream> stream = reader.open(name);
            if (stream.isEmpty()) {
                return null;
            }
            try (InputStream in = stream.get()) {
                return in.readAllBytes();
            }
        } catch (IOException _) {
            return null;
        }
    }

    private static String packageOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(0, dot);
    }

    @Override
    protected String findLibrary(String name) {
        String mapped = System.mapLibraryName(name);
        // Class path takes precedence, then the bundled modules (deterministic via the LinkedHashMap).
        for (Archive.Jar jar : classpath) {
            for (String resource : jar.names()) {
                if (matchesLibrary(resource, mapped)) {
                    return extractLibrary(mapped, jar.open(resource));
                }
            }
        }
        for (Map.Entry<String, ModuleReader> entry : readers.entrySet()) {
            String resource;
            try (Stream<String> names = entry.getValue().list()) {
                resource = names.filter(candidate -> matchesLibrary(candidate, mapped)).findFirst().orElse(null);
            } catch (IOException _) {
                continue;
            }
            if (resource != null) {
                byte[] data = moduleResource(entry.getKey(), resource);
                if (data != null) {
                    return extractLibrary(mapped, data);
                }
            }
        }
        return null;
    }

    private static boolean matchesLibrary(String resource, String mapped) {
        return resource.equals(mapped) || resource.endsWith("/" + mapped);
    }

    private static String extractLibrary(String mapped, byte[] data) {
        try {
            Path file = Files.createTempFile("jenesis-", "-" + mapped);
            file.toFile().deleteOnExit();
            Files.write(file, data);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract native library " + mapped, e);
        }
    }

    /**
     * Closes the backing jar (or directory) handle - mirroring {@link java.net.URLClassLoader#close()}. The
     * launcher never closes the loader of a running application (it must serve classes on demand for the
     * application's whole lifetime); this lets an embedder that builds and discards loaders release the file
     * descriptor deterministically rather than waiting for the jar handle to be reclaimed on garbage
     * collection. Classes already defined keep working; loading further classes or resources will fail.
     */
    @Override
    public void close() throws IOException {
        archive.close();
    }
}
