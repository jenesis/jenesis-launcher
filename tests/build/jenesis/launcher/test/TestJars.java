package build.jenesis.launcher.test;

import module java.base;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/**
 * Synthesises class files, nested jars, and outer executable-jar fixtures in memory, so the launcher
 * can be exercised end to end without a build step or any artifacts on disk. Class bodies are emitted
 * with the JDK Class-File API, keeping the tests dependency-free.
 */
final class TestJars {

    private static final ClassDesc CD_System = ClassDesc.of("java.lang.System");
    private static final ClassDesc CD_Thread = ClassDesc.of("java.lang.Thread");
    private static final ClassDesc CD_ClassLoader = ClassDesc.of("java.lang.ClassLoader");
    private static final ClassDesc CD_InputStream = ClassDesc.of("java.io.InputStream");

    private TestJars() {
    }

    /** A class whose {@code main} runs {@code System.setProperty(args[0], args[1])}. */
    static byte[] setPropertyMain(String binaryName) {
        return main(binaryName, code -> code
                .aload(0).iconst_0().aaload()
                .aload(0).iconst_1().aaload()
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * A class whose {@code main} stores its own code source's location into
     * {@code System.setProperty(args[0], getCodeSource().getLocation().toExternalForm())} - exercising the
     * {@code CodeSource} a module class carries. Throws if the class has no code source.
     */
    static byte[] codeSourceLocationMain(String binaryName) {
        ClassDesc protectionDomain = ClassDesc.of("java.security.ProtectionDomain");
        ClassDesc codeSource = ClassDesc.of("java.security.CodeSource");
        ClassDesc url = ClassDesc.of("java.net.URL");
        return main(binaryName, code -> code
                .loadConstant(ClassDesc.of(binaryName))
                .invokevirtual(ConstantDescs.CD_Class, "getProtectionDomain", MethodTypeDesc.of(protectionDomain))
                .invokevirtual(protectionDomain, "getCodeSource", MethodTypeDesc.of(codeSource))
                .invokevirtual(codeSource, "getLocation", MethodTypeDesc.of(url))
                .invokevirtual(url, "toExternalForm", MethodTypeDesc.of(ConstantDescs.CD_String))
                .astore(1)
                .aload(0).iconst_0().aaload()
                .aload(1)
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * A class whose {@code main} stores its own code source's leaf signer distinguished name into
     * {@code System.setProperty(args[0], cert.getSubjectX500Principal().getName())} - exercising the signer
     * identity reconstructed from {@code signatures.properties}. Throws if the code source carries no signers.
     */
    static byte[] codeSourceSignerMain(String binaryName) {
        ClassDesc protectionDomain = ClassDesc.of("java.security.ProtectionDomain");
        ClassDesc codeSource = ClassDesc.of("java.security.CodeSource");
        ClassDesc certificate = ClassDesc.of("java.security.cert.Certificate");
        ClassDesc x509Certificate = ClassDesc.of("java.security.cert.X509Certificate");
        ClassDesc x500Principal = ClassDesc.of("javax.security.auth.x500.X500Principal");
        return main(binaryName, code -> code
                .loadConstant(ClassDesc.of(binaryName))
                .invokevirtual(ConstantDescs.CD_Class, "getProtectionDomain", MethodTypeDesc.of(protectionDomain))
                .invokevirtual(protectionDomain, "getCodeSource", MethodTypeDesc.of(codeSource))
                .invokevirtual(codeSource, "getCertificates", MethodTypeDesc.of(certificate.arrayType()))
                .iconst_0()
                .aaload()
                .checkcast(x509Certificate)
                .invokevirtual(x509Certificate, "getSubjectX500Principal", MethodTypeDesc.of(x500Principal))
                .invokevirtual(x500Principal, "getName", MethodTypeDesc.of(ConstantDescs.CD_String))
                .astore(1)
                .aload(0).iconst_0().aaload()
                .aload(1)
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /** A class whose {@code main} runs {@code System.setProperty(args[0], value)} for a fixed {@code value}. */
    static byte[] constantPropertyMain(String binaryName, String value) {
        return main(binaryName, code -> code
                .aload(0).iconst_0().aaload()
                .loadConstant(value)
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * A class whose {@code main} copies a system property into another:
     * {@code System.setProperty(args[0], System.getProperty(source))}. Used to observe, from the
     * application, a value an agent's {@code premain} wrote before {@code main} ran.
     */
    static byte[] copyPropertyMain(String binaryName, String source) {
        return main(binaryName, code -> code
                .aload(0).iconst_0().aaload()
                .loadConstant(source)
                .invokestatic(CD_System, "getProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String))
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * A class whose {@code main} reads {@code resource} via the thread context class loader and stores
     * its contents into {@code System.setProperty(args[0], contents)} - exercising the in-memory
     * resource URL path.
     */
    static byte[] readResourceMain(String binaryName, String resource) {
        return main(binaryName, code -> code
                .invokestatic(CD_Thread, "currentThread", MethodTypeDesc.of(CD_Thread))
                .invokevirtual(CD_Thread, "getContextClassLoader", MethodTypeDesc.of(CD_ClassLoader))
                .loadConstant(resource)
                .invokevirtual(CD_ClassLoader, "getResourceAsStream",
                        MethodTypeDesc.of(CD_InputStream, ConstantDescs.CD_String))
                .invokevirtual(CD_InputStream, "readAllBytes",
                        MethodTypeDesc.of(ConstantDescs.CD_byte.arrayType()))
                .astore(1)
                .aload(0).iconst_0().aaload()
                .new_(ConstantDescs.CD_String)
                .dup()
                .aload(1)
                .invokespecial(ConstantDescs.CD_String, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_byte.arrayType()))
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * A class whose {@code main} resolves {@code target} through the thread context class loader
     * (proving reachability across the layer's parent chain) and then runs
     * {@code System.setProperty(args[0], args[1])}.
     */
    static byte[] loadClassMain(String binaryName, String target) {
        ClassDesc cdClass = ClassDesc.of("java.lang.Class");
        return main(binaryName, code -> code
                .loadConstant(target)
                .iconst_0()
                .invokestatic(CD_Thread, "currentThread", MethodTypeDesc.of(CD_Thread))
                .invokevirtual(CD_Thread, "getContextClassLoader", MethodTypeDesc.of(CD_ClassLoader))
                .invokestatic(cdClass, "forName",
                        MethodTypeDesc.of(cdClass, ConstantDescs.CD_String, ConstantDescs.CD_boolean, CD_ClassLoader))
                .pop()
                .aload(0).iconst_0().aaload()
                .aload(0).iconst_1().aaload()
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * An agent class whose {@code premain(String)} appends {@code tag} to the system property
     * {@code key}: {@code System.setProperty(key, System.getProperty(key, "") + tag)}. Appending makes
     * the invocation order of several agents observable.
     */
    static byte[] appendPropertyPremain(String binaryName, String key, String tag) {
        return premain(binaryName, code -> code
                .loadConstant(key)
                .loadConstant(key)
                .loadConstant("")
                .invokestatic(CD_System, "getProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .loadConstant(tag)
                .invokevirtual(ConstantDescs.CD_String, "concat",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String))
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * An agent class whose {@code premain(String args)} stores the arguments it was given:
     * {@code System.setProperty(key, args)}. Used to prove the {@code agentClass=...=<args>} arguments
     * reach the agent.
     */
    static byte[] argumentPremain(String binaryName, String key) {
        return premain(binaryName, code -> code
                .loadConstant(key)
                .aload(0)
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /**
     * A class whose {@code main} reads {@code resource} via {@code <ThisClass>.class.getResourceAsStream}
     * and stores its contents into {@code System.setProperty(args[0], contents)}. Unlike
     * {@link #readResourceMain}, this resolves through the owning module's
     * {@link java.lang.module.ModuleReader}, exercising the module resource path.
     */
    static byte[] classResourceMain(String binaryName, String resource) {
        ClassDesc cdClass = ClassDesc.of("java.lang.Class");
        return main(binaryName, code -> code
                .loadConstant(ClassDesc.of(binaryName))
                .loadConstant(resource)
                .invokevirtual(cdClass, "getResourceAsStream",
                        MethodTypeDesc.of(CD_InputStream, ConstantDescs.CD_String))
                .invokevirtual(CD_InputStream, "readAllBytes",
                        MethodTypeDesc.of(ConstantDescs.CD_byte.arrayType()))
                .astore(1)
                .aload(0).iconst_0().aaload()
                .new_(ConstantDescs.CD_String)
                .dup()
                .aload(1)
                .invokespecial(ConstantDescs.CD_String, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_byte.arrayType()))
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /** A class whose {@code main} runs {@code System.loadLibrary(library)}. */
    static byte[] loadLibraryMain(String binaryName, String library) {
        return main(binaryName, code -> code
                .loadConstant(library)
                .invokestatic(CD_System, "loadLibrary",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                .return_());
    }

    /** A class whose {@code main} stores its own package's {@code Implementation-Version} into {@code args[0]}. */
    static byte[] implementationVersionMain(String binaryName) {
        ClassDesc cdClass = ClassDesc.of("java.lang.Class");
        ClassDesc cdPackage = ClassDesc.of("java.lang.Package");
        return main(binaryName, code -> code
                .aload(0).iconst_0().aaload()
                .loadConstant(ClassDesc.of(binaryName))
                .invokevirtual(cdClass, "getPackage", MethodTypeDesc.of(cdPackage))
                .invokevirtual(cdPackage, "getImplementationVersion", MethodTypeDesc.of(ConstantDescs.CD_String))
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /** A class whose {@code main} stores whether its own package is sealed into {@code args[0]}. */
    static byte[] sealedMain(String binaryName) {
        ClassDesc cdClass = ClassDesc.of("java.lang.Class");
        ClassDesc cdPackage = ClassDesc.of("java.lang.Package");
        return main(binaryName, code -> code
                .aload(0).iconst_0().aaload()
                .loadConstant(ClassDesc.of(binaryName))
                .invokevirtual(cdClass, "getPackage", MethodTypeDesc.of(cdPackage))
                .invokevirtual(cdPackage, "isSealed", MethodTypeDesc.of(ConstantDescs.CD_boolean))
                .invokestatic(ConstantDescs.CD_String, "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_boolean))
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /** A {@code module-info.class} for an explicit module that requires {@code java.base} and exports nothing. */
    static byte[] moduleInfo(String moduleName) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(moduleName), builder ->
                builder.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)));
    }

    /** A class with a {@code public static void run(String)} that runs {@code System.setProperty(arg, value)}. */
    static byte[] runner(String binaryName, String value) {
        return method(binaryName, "run", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String), code -> code
                .aload(0)
                .loadConstant(value)
                .invokestatic(CD_System, "setProperty",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_());
    }

    /** A class whose {@code main} directly calls {@code target.run(args[0])} - which needs {@code target}'s
     * package exported to the caller. */
    static byte[] callRunMain(String binaryName, String target) {
        return main(binaryName, code -> code
                .aload(0).iconst_0().aaload()
                .invokestatic(ClassDesc.of(target), "run",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                .return_());
    }

    private static byte[] main(String binaryName, Consumer<CodeBuilder> body) {
        return method(binaryName, "main",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()), body);
    }

    private static byte[] premain(String binaryName, Consumer<CodeBuilder> body) {
        return method(binaryName, "premain",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String), body);
    }

    private static byte[] method(String binaryName, String name, MethodTypeDesc descriptor, Consumer<CodeBuilder> body) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withSuperclass(ConstantDescs.CD_Object);
            builder.withMethodBody(name, descriptor,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    body::accept);
        });
    }

    /** Packs entries into a jar (zip) image. */
    static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A jar holding a single class. */
    static byte[] classJar(String binaryName, byte[] classBytes) throws IOException {
        return jar(Map.of(binaryName.replace('.', '/') + ".class", classBytes));
    }

    /** A jar holding a class plus an {@code Automatic-Module-Name} manifest header. */
    static byte[] automaticModuleJar(String moduleName, String binaryName, byte[] classBytes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", out.toByteArray());
        entries.put(binaryName.replace('.', '/') + ".class", classBytes);
        return jar(entries);
    }

    /** Writes an outer executable-jar fixture, exploding each dependency into its own subfolder. */
    static void writeBundle(Path target,
                            Map<String, String> application,
                            Map<String, byte[]> classpath,
                            Map<String, byte[]> modulepath) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("application.properties", applicationProperties(application));
        explode(entries, "classpath/", classpath);
        explode(entries, "modulepath/", modulepath);
        Files.write(target, jar(entries));
    }

    /** Writes the same fixture as an exploded directory (for the directory-layout launch path). */
    static void writeDirectory(Path root,
                               Map<String, String> application,
                               Map<String, byte[]> classpath,
                               Map<String, byte[]> modulepath) throws IOException {
        Files.createDirectories(root);
        Files.write(root.resolve("application.properties"), applicationProperties(application));
        explodeToDirectory(root.resolve("classpath"), classpath);
        explodeToDirectory(root.resolve("modulepath"), modulepath);
    }

    private static byte[] applicationProperties(Map<String, String> application) throws IOException {
        Properties properties = new Properties();
        application.forEach(properties::setProperty);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, null);
        return out.toByteArray();
    }

    private static void explode(Map<String, byte[]> entries, String section, Map<String, byte[]> jars) throws IOException {
        for (Map.Entry<String, byte[]> jar : jars.entrySet()) {
            String prefix = section + jar.getKey() + "/";
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar.getValue()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        entries.put(prefix + entry.getName(), zip.readAllBytes());
                    }
                }
            }
        }
    }

    private static void explodeToDirectory(Path base, Map<String, byte[]> jars) throws IOException {
        for (Map.Entry<String, byte[]> jar : jars.entrySet()) {
            Path group = base.resolve(jar.getKey());
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar.getValue()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        Path file = group.resolve(entry.getName());
                        Files.createDirectories(file.getParent());
                        Files.write(file, zip.readAllBytes());
                    }
                }
            }
        }
    }
}
