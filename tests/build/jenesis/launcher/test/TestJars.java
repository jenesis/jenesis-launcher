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

    private static byte[] main(String binaryName, Consumer<CodeBuilder> body) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withSuperclass(ConstantDescs.CD_Object);
            builder.withMethodBody("main",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
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

    /** Writes an outer executable-jar fixture combining the application properties and nested jars. */
    static void writeBundle(Path target,
                            Map<String, String> application,
                            Map<String, byte[]> classpath,
                            Map<String, byte[]> modulepath) throws IOException {
        Properties properties = new Properties();
        application.forEach(properties::setProperty);
        ByteArrayOutputStream props = new ByteArrayOutputStream();
        properties.store(props, null);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("application.properties", props.toByteArray());
        classpath.forEach((name, bytes) -> entries.put("classpath/" + name, bytes));
        modulepath.forEach((name, bytes) -> entries.put("modulepath/" + name, bytes));
        Files.write(target, jar(entries));
    }
}
