package build.jenesis.launcher.test;

import module java.base;

import java.security.cert.X509Certificate;
import build.jenesis.launcher.Launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LauncherTest {

    @TempDir
    Path directory;

    // A throwaway self-signed EC certificate (CN=Jenesis Test Signer, O=Jenesis) as Base64 DER, used to
    // exercise signer-identity reconstruction from a signature.<dep> entry in application.properties.
    private static final String TEST_SIGNER_CERT =
            "MIIBeDCCAR6gAwIBAgIJAK+PMEagHyyCMAoGCCqGSM49BAMDMDAxEDAOBgNVBAoTB0plbmVzaXMxHDAaBgNVBAMTE0plbm"
            + "VzaXMgVGVzdCBTaWduZXIwHhcNMjYwNjEwMTA1OTEzWhcNMzYwNjA3MTA1OTEzWjAwMRAwDgYDVQQKEwdKZW5lc2lzMR"
            + "wwGgYDVQQDExNKZW5lc2lzIFRlc3QgU2lnbmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/ZfmNxZmPgkfHXmDPk"
            + "enwSWZ14ZciWVjS5ZTqYyQIc6cNbRCaPsyjVjlPFE1yRg38YCzNhODIlXARa78XcbaRaMhMB8wHQYDVR0OBBYEFNuWoP"
            + "xXdyj3dBLO7zZ8XZXvyrtcMAoGCCqGSM49BAMDA0gAMEUCIQCntROQ+WVHLvYOL2I9qGboMoFfhCGPdIF4t/uNv8jdtw"
            + "IgLs3WcP6Qhzm7JQhWCUNwueheAZczd4zDzbhIo4LtKTI=";

    @Test
    void runsClassPathApplication() throws Exception {
        Path bundle = directory.resolve("classpath-app.jar");
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.cp.Main"),
                Map.of("app.jar", TestJars.classJar("demo.cp.Main", TestJars.setPropertyMain("demo.cp.Main"))),
                Map.of());

        String key = "jenesis.test.classpath";
        System.clearProperty(key);
        launch(bundle, key, "ok");

        assertThat(System.getProperty(key)).isEqualTo("ok");
    }

    @Test
    void runsModularApplicationInOwnLayer() throws Exception {
        Path bundle = directory.resolve("modular-app.jar");
        byte[] module = TestJars.automaticModuleJar("demo.module", "demo.mod.Main",
                TestJars.setPropertyMain("demo.mod.Main"));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "demo.module", "mainClass", "demo.mod.Main"),
                Map.of(),
                Map.of("demo-module.jar", module));

        String key = "jenesis.test.modular";
        System.clearProperty(key);
        launch(bundle, key, "ok");

        assertThat(System.getProperty(key)).isEqualTo("ok");
    }

    @Test
    void derivesAutomaticModuleNameFromFileName() throws Exception {
        Path bundle = directory.resolve("derived-app.jar");
        // No manifest header: the module name must be derived from the file name "widgets-1.2.jar".
        byte[] module = TestJars.classJar("demo.widgets.Main", TestJars.setPropertyMain("demo.widgets.Main"));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "widgets", "mainClass", "demo.widgets.Main"),
                Map.of(),
                Map.of("widgets-1.2.jar", module));

        String key = "jenesis.test.derived";
        System.clearProperty(key);
        launch(bundle, key, "ok");

        assertThat(System.getProperty(key)).isEqualTo("ok");
    }

    @Test
    void loadsClassPathResource() throws Exception {
        Path bundle = directory.resolve("resource-app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/res/Main.class", TestJars.readResourceMain("demo.res.Main", "greeting.txt"));
        entries.put("greeting.txt", "hello".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.res.Main"),
                Map.of("resources.jar", TestJars.jar(entries)),
                Map.of());

        String key = "jenesis.test.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("hello");
    }

    @Test
    void modularApplicationCanReadClassPath() throws Exception {
        // A library only on the class path, and a main automatic module that loads it via the context
        // loader - the readability the launcher grants by parenting the layer on the class-path loader.
        Path bundle = directory.resolve("hybrid-app.jar");
        byte[] library = TestJars.classJar("demo.lib.Widget", TestJars.setPropertyMain("demo.lib.Widget"));
        byte[] module = TestJars.automaticModuleJar("demo.app", "demo.app.Main",
                TestJars.loadClassMain("demo.app.Main", "demo.lib.Widget"));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "demo.app", "mainClass", "demo.app.Main"),
                Map.of("library.jar", library),
                Map.of("app.jar", module));

        String key = "jenesis.test.hybrid";
        System.clearProperty(key);
        launch(bundle, key, "ok");

        assertThat(System.getProperty(key)).isEqualTo("ok");
    }

    @Test
    void runsAgentsBeforeMainInDeclaredOrder() throws Exception {
        // Two agents each append their tag to a property; main copies it. That main sees "AB" proves
        // both agents' premain ran, in the order declared by agentClass, before the main class loaded.
        Path bundle = directory.resolve("agent-app.jar");
        String order = "jenesis.test.agent.order";
        Map<String, byte[]> classpath = new LinkedHashMap<>();
        classpath.put("first.jar", TestJars.classJar("demo.agent.First",
                TestJars.appendPropertyPremain("demo.agent.First", order, "A")));
        classpath.put("second.jar", TestJars.classJar("demo.agent.Second",
                TestJars.appendPropertyPremain("demo.agent.Second", order, "B")));
        classpath.put("app.jar", TestJars.classJar("demo.agent.Main",
                TestJars.copyPropertyMain("demo.agent.Main", order)));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.agent.Main", "agentClass", "demo.agent.First,demo.agent.Second"),
                classpath,
                Map.of());

        String key = "jenesis.test.agent.result";
        System.clearProperty(key);
        System.clearProperty(order);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("AB");
    }

    @Test
    void passesAgentArgumentsToPremain() throws Exception {
        // agentClass entries may carry `=<args>` like `-javaagent:jar=args`; the args run to the end.
        Path bundle = directory.resolve("agent-args-app.jar");
        String captured = "jenesis.test.agent.args";
        Map<String, byte[]> classpath = new LinkedHashMap<>();
        classpath.put("echo.jar", TestJars.classJar("demo.agent.Echo",
                TestJars.argumentPremain("demo.agent.Echo", captured)));
        classpath.put("app.jar", TestJars.classJar("demo.agent.Main",
                TestJars.copyPropertyMain("demo.agent.Main", captured)));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.agent.Main", "agentClass", "demo.agent.Echo=hello world"),
                classpath,
                Map.of());

        String key = "jenesis.test.agent.args.result";
        System.clearProperty(key);
        System.clearProperty(captured);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("hello world");
    }

    @Test
    void modulePackageShadowsSamePackageOnClassPath() throws Exception {
        // A module owns package demo.shared; a class-path jar carries another class in that same package.
        // One loader hosts both, so the module owns the package and the class-path copy is invisible -
        // exactly as `java -p modulepath -cp classpath` shadows it.
        Path bundle = directory.resolve("shadow-app.jar");
        byte[] probe = TestJars.automaticModuleJar("demo.module", "demo.shared.Probe",
                TestJars.loadClassMain("demo.shared.Probe", "demo.shared.Ghost"));
        byte[] ghost = TestJars.classJar("demo.shared.Ghost", TestJars.setPropertyMain("demo.shared.Ghost"));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "demo.module", "mainClass", "demo.shared.Probe"),
                Map.of("ghost.jar", ghost),
                Map.of("module.jar", probe));

        assertThatThrownBy(() -> launch(bundle))
                .isInstanceOf(ClassNotFoundException.class)
                .hasMessageContaining("demo.shared.Ghost");
    }

    @Test
    void nonModularApplicationRunsModulePathAgent() throws Exception {
        // No mainModule: the application is non-modular, yet it bundles an agent on the module path. The
        // launcher builds a layer regardless, so the module-path agent's premain still runs before main.
        Path bundle = directory.resolve("mp-agent-app.jar");
        String order = "jenesis.test.mpagent.order";
        byte[] agent = TestJars.appendPropertyPremain("demo.agent.Mp", order, "A");
        byte[] main = TestJars.copyPropertyMain("demo.app.Main", order);
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main", "agentClass", "demo.agent.Mp"),
                Map.of("app.jar", TestJars.classJar("demo.app.Main", main)),
                Map.of("agent.jar", TestJars.classJar("demo.agent.Mp", agent)));

        String key = "jenesis.test.mpagent.result";
        System.clearProperty(key);
        System.clearProperty(order);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("A");
    }

    @Test
    void loadsModuleResourceThroughClassGetResourceAsStream() throws Exception {
        // A module class reads a bundled resource via Class.getResourceAsStream, which resolves through the
        // module's ModuleReader - the path that breaks unless the loader serves findResource(module, name).
        Path bundle = directory.resolve("module-resource-app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/modres/Main.class", TestJars.classResourceMain("demo.modres.Main", "/greeting.txt"));
        entries.put("greeting.txt", "hello".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "modres", "mainClass", "demo.modres.Main"),
                Map.of(),
                Map.of("modres.jar", TestJars.jar(entries)));

        String key = "jenesis.test.module.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("hello");
    }

    @Test
    void extractsNativeLibraryFromClassPathJar() throws Exception {
        // We cannot load a real library, but reaching extraction (vs. "no <name> in java.library.path")
        // proves findLibrary served it: the JVM then fails to load the bogus file and the error names the
        // extracted temp file, whose name carries the launcher's "jenesis-" prefix.
        String mapped = System.mapLibraryName("jenesiscp");
        Path bundle = directory.resolve("native-cp-app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/nat/CpMain.class", TestJars.loadLibraryMain("demo.nat.CpMain", "jenesiscp"));
        entries.put(mapped, "not a real native library".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.nat.CpMain"),
                Map.of("native.jar", TestJars.jar(entries)),
                Map.of());

        assertThatThrownBy(() -> launch(bundle))
                .isInstanceOf(UnsatisfiedLinkError.class)
                .hasMessageContaining("jenesis-");
    }

    @Test
    void extractsNativeLibraryFromModularJar() throws Exception {
        // The unified loader now also extracts native libraries bundled inside a modular jar, lifting the
        // old "modular jars are not handled" limitation. Same probe as the class-path case.
        String mapped = System.mapLibraryName("jenesismod");
        Path bundle = directory.resolve("native-module-app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/nat/Main.class", TestJars.loadLibraryMain("demo.nat.Main", "jenesismod"));
        entries.put(mapped, "not a real native library".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "natmod", "mainClass", "demo.nat.Main"),
                Map.of(),
                Map.of("natmod.jar", TestJars.jar(entries)));

        assertThatThrownBy(() -> launch(bundle))
                .isInstanceOf(UnsatisfiedLinkError.class)
                .hasMessageContaining("jenesis-");
    }

    @Test
    void runsModuleResourceFromExplodedDirectory() throws Exception {
        // The directory layout - classpath/<dep>/ and modulepath/<mod>/ as real folders, served via file: URLs.
        Path bundle = directory.resolve("exploded");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/modres/Main.class", TestJars.classResourceMain("demo.modres.Main", "/greeting.txt"));
        entries.put("greeting.txt", "hello".getBytes(StandardCharsets.UTF_8));
        TestJars.writeDirectory(bundle,
                Map.of("mainModule", "modres", "mainClass", "demo.modres.Main"),
                Map.of(),
                Map.of("modres.jar", TestJars.jar(entries)));

        String key = "jenesis.test.directory.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("hello");
    }

    @Test
    void loadsClassPathResourceFromExplodedDirectory() throws Exception {
        // The traversal confinement must still allow ordinary in-bundle resources from the directory layout.
        Path bundle = directory.resolve("dir-resource");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/dres/Main.class", TestJars.readResourceMain("demo.dres.Main", "greeting.txt"));
        entries.put("greeting.txt", "hello".getBytes(StandardCharsets.UTF_8));
        TestJars.writeDirectory(bundle,
                Map.of("mainClass", "demo.dres.Main"),
                Map.of("resources.jar", TestJars.jar(entries)),
                Map.of());

        String key = "jenesis.test.dir.classpath.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("hello");
    }

    @Test
    void confinesDirectoryResourceLookupToBundleRoot() throws Exception {
        // A secret file sits beside the exploded bundle. A class-path resource name that climbs out with
        // ".." must not read it: DirectorySource confines resolved paths to the bundle root, so the lookup
        // returns null (and reading the null stream throws) instead of leaking a file outside the bundle.
        // The file genuinely exists, so only the confinement check stands between the lookup and the read -
        // without it, this test would observe "TOPSECRET".
        Files.write(directory.resolve("secret.txt"), "TOPSECRET".getBytes(StandardCharsets.UTF_8));
        Path bundle = directory.resolve("confined");
        // From classpath/<dep>/, "../../../secret.txt" resolves to <directory>/secret.txt on disk.
        byte[] main = TestJars.readResourceMain("demo.cf.Main", "../../../secret.txt");
        TestJars.writeDirectory(bundle,
                Map.of("mainClass", "demo.cf.Main"),
                Map.of("app.jar", TestJars.classJar("demo.cf.Main", main)),
                Map.of());

        String key = "jenesis.test.confined";
        System.clearProperty(key);
        assertThatThrownBy(() -> launch(bundle, key)).isInstanceOf(NullPointerException.class);
        assertThat(System.getProperty(key)).isNull();
    }

    @Test
    void exposesAutomaticModuleResourceOnFlatResourceApi() throws Exception {
        // A real `java -p ... -cp ...` serves an automatic module's resources through the flat resource API
        // (automatic modules are open). A class-path main reads one via the context class loader.
        Path bundle = directory.resolve("auto-res-app.jar");
        Map<String, byte[]> module = new LinkedHashMap<>();
        module.put("META-INF/MANIFEST.MF", manifest(Map.of("Automatic-Module-Name", "demo.auto")));
        module.put("demo/auto/Thing.class", TestJars.setPropertyMain("demo.auto.Thing"));
        module.put("demo/auto/data.txt", "auto-value".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main"),
                Map.of("app.jar", TestJars.classJar("demo.app.Main",
                        TestJars.readResourceMain("demo.app.Main", "demo/auto/data.txt"))),
                Map.of("auto.jar", TestJars.jar(module)));

        String key = "jenesis.test.auto.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("auto-value");
    }

    @Test
    void exposesModuleNonPackageResourceOnFlatResourceApi() throws Exception {
        // A resource in no module package (here under META-INF) is served from a module unconditionally,
        // even from an explicit module that opens nothing - matching BuiltinClassLoader.findMiscResource.
        Path bundle = directory.resolve("meta-res-app.jar");
        Map<String, byte[]> module = new LinkedHashMap<>();
        module.put("module-info.class", TestJars.moduleInfo("demo.lib"));
        module.put("demo/lib/Tool.class", TestJars.setPropertyMain("demo.lib.Tool"));
        module.put("META-INF/demo-flat.txt", "flat-value".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main"),
                Map.of("app.jar", TestJars.classJar("demo.app.Main",
                        TestJars.readResourceMain("demo.app.Main", "META-INF/demo-flat.txt"))),
                Map.of("lib.jar", TestJars.jar(module)));

        String key = "jenesis.test.meta.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("flat-value");
    }

    @Test
    void hidesEncapsulatedModulePackageResourceFromFlatResourceApi() throws Exception {
        // A resource inside a non-open package of an explicit module stays encapsulated on the flat resource
        // API, exactly as a real modular launch hides it (the package is neither open nor automatic), so the
        // context-loader lookup returns null and reading it throws.
        Path bundle = directory.resolve("hidden-res-app.jar");
        Map<String, byte[]> module = new LinkedHashMap<>();
        module.put("module-info.class", TestJars.moduleInfo("demo.lib"));
        module.put("demo/lib/Tool.class", TestJars.setPropertyMain("demo.lib.Tool"));
        module.put("demo/lib/secret.txt", "secret-value".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main"),
                Map.of("app.jar", TestJars.classJar("demo.app.Main",
                        TestJars.readResourceMain("demo.app.Main", "demo/lib/secret.txt"))),
                Map.of("lib.jar", TestJars.jar(module)));

        String key = "jenesis.test.hidden.resource";
        System.clearProperty(key);
        assertThatThrownBy(() -> launch(bundle, key)).isInstanceOf(NullPointerException.class);
        assertThat(System.getProperty(key)).isNull();
    }

    @Test
    void reconstructsSignerIdentityFromApplicationProperties() throws Exception {
        // A signed class-path dependency loses its signer when exploded; a signature.<dep> entry in
        // application.properties carries the signer certificate chain (Base64 PKCS#7), and the launcher
        // reconstructs it onto the CodeSource so getCodeSource().getCertificates() reports the original signer.
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(Base64.getDecoder().decode(TEST_SIGNER_CERT)));
        String chain = Base64.getEncoder().encodeToString(
                factory.generateCertPath(List.of(certificate)).getEncoded("PKCS7"));
        String expected = certificate.getSubjectX500Principal().getName();

        Path bundle = directory.resolve("signed-app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("application.properties",
                properties(Map.of("mainClass", "demo.sig.Main", "signature.app.jar", chain)));
        entries.put("classpath/app.jar/demo/sig/Main.class", TestJars.codeSourceSignerMain("demo.sig.Main"));
        Files.write(bundle, TestJars.jar(entries));

        String key = "jenesis.test.signer";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo(expected);
    }

    @Test
    void omitsSignersWithoutSignatureProperty() throws Exception {
        // The feature is strictly opt-in: with no signature.<dep> property the CodeSource carries no signers,
        // so getCertificates() is null and reading it throws.
        Path bundle = directory.resolve("unsigned-app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("application.properties", properties(Map.of("mainClass", "demo.sig.Main")));
        entries.put("classpath/app.jar/demo/sig/Main.class", TestJars.codeSourceSignerMain("demo.sig.Main"));
        Files.write(bundle, TestJars.jar(entries));

        assertThatThrownBy(() -> launch(bundle, "jenesis.test.unsigned"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void exposesModuleClassCodeSourceLocation() throws Exception {
        // A module class carries a CodeSource at the module's exploded folder URL, as a real module-path
        // class does - so getProtectionDomain().getCodeSource().getLocation() resolves rather than being null.
        Path bundle = directory.resolve("module-cs-app.jar");
        byte[] module = TestJars.automaticModuleJar("demo.module", "demo.mod.Main",
                TestJars.codeSourceLocationMain("demo.mod.Main"));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "demo.module", "mainClass", "demo.mod.Main"),
                Map.of(),
                Map.of("mod.jar", module));

        String key = "jenesis.test.module.codesource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).contains("modulepath/mod.jar");
    }

    @Test
    void resolvesResourcesWhenBundlePathHasSpaces() throws Exception {
        // A space in both the bundle path and the resource name forces correct jar: URL percent-encoding.
        Path folder = Files.createDirectories(directory.resolve("with space"));
        Path bundle = folder.resolve("module app.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/modres/Main.class", TestJars.classResourceMain("demo.modres.Main", "/with space.txt"));
        entries.put("with space.txt", "hello".getBytes(StandardCharsets.UTF_8));
        TestJars.writeBundle(bundle,
                Map.of("mainModule", "modres", "mainClass", "demo.modres.Main"),
                Map.of(),
                Map.of("modres.jar", TestJars.jar(entries)));

        String key = "jenesis.test.spaced.resource";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("hello");
    }

    @Test
    void prefersMultiReleaseClassForTheRuntimeVersion() throws Exception {
        int version = Runtime.version().feature();
        Path bundle = directory.resolve("mr-app.jar");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/MANIFEST.MF", multiReleaseManifest());
        jar.put("demo/mr/Main.class", TestJars.constantPropertyMain("demo.mr.Main", "base"));
        jar.put("META-INF/versions/" + version + "/demo/mr/Main.class",
                TestJars.constantPropertyMain("demo.mr.Main", "versioned"));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.mr.Main"),
                Map.of("mr.jar", TestJars.jar(jar)),
                Map.of());

        String key = "jenesis.test.mr";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("versioned");
    }

    @Test
    void ignoresMultiReleaseClassForAFutureVersion() throws Exception {
        int future = Runtime.version().feature() + 1;
        Path bundle = directory.resolve("mr-future-app.jar");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/MANIFEST.MF", multiReleaseManifest());
        jar.put("demo/mr/Main.class", TestJars.constantPropertyMain("demo.mr.Main", "base"));
        jar.put("META-INF/versions/" + future + "/demo/mr/Main.class",
                TestJars.constantPropertyMain("demo.mr.Main", "future"));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.mr.Main"),
                Map.of("mr.jar", TestJars.jar(jar)),
                Map.of());

        String key = "jenesis.test.mr.future";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("base");
    }

    private static byte[] multiReleaseManifest() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        return out.toByteArray();
    }

    @Test
    void exposesPackageImplementationVersionFromManifest() throws Exception {
        Path bundle = directory.resolve("metadata-app.jar");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/MANIFEST.MF", manifest(Map.of("Implementation-Version", "1.2.3")));
        jar.put("demo/meta/Main.class", TestJars.implementationVersionMain("demo.meta.Main"));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.meta.Main"),
                Map.of("meta.jar", TestJars.jar(jar)),
                Map.of());

        String key = "jenesis.test.metadata";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("1.2.3");
    }

    @Test
    void enforcesSealedPackageFromManifest() throws Exception {
        // A Sealed manifest seals the package to the dependency's URL; the class defines with a matching
        // CodeSource, so it loads (no sealing violation) and reports itself sealed.
        Path bundle = directory.resolve("sealed-app.jar");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/MANIFEST.MF", manifest(Map.of("Sealed", "true")));
        jar.put("demo/sealed/Main.class", TestJars.sealedMain("demo.sealed.Main"));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.sealed.Main"),
                Map.of("sealed.jar", TestJars.jar(jar)),
                Map.of());

        String key = "jenesis.test.sealed";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("true");
    }

    private static byte[] manifest(Map<String, String> attributes) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        attributes.forEach(manifest.getMainAttributes()::putValue);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        return out.toByteArray();
    }

    private static byte[] properties(Map<String, String> entries) throws Exception {
        Properties properties = new Properties();
        entries.forEach(properties::setProperty);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, null);
        return out.toByteArray();
    }

    @Test
    void grantsModuleAccessFromProperties() throws Exception {
        // demo.lib is an explicit module that exports nothing. addExports lets the class-path main call into
        // it; addOpens and addReads exercise the same machinery (applied through the Controller, no error).
        Path bundle = directory.resolve("access-app.jar");
        Map<String, byte[]> module = new LinkedHashMap<>();
        module.put("module-info.class", TestJars.moduleInfo("demo.lib"));
        module.put("demo/lib/Tool.class", TestJars.runner("demo.lib.Tool", "granted"));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main",
                        "addExports", "demo.lib/demo.lib=ALL-UNNAMED",
                        "addOpens", "demo.lib/demo.lib=ALL-UNNAMED",
                        "addReads", "demo.lib=java.base"),
                Map.of("app.jar",
                        TestJars.classJar("demo.app.Main", TestJars.callRunMain("demo.app.Main", "demo.lib.Tool"))),
                Map.of("lib.jar", TestJars.jar(module)));

        String key = "jenesis.test.access";
        System.clearProperty(key);
        launch(bundle, key);

        assertThat(System.getProperty(key)).isEqualTo("granted");
    }

    @Test
    void deniesModuleAccessWithoutAddExports() throws Exception {
        // The same bundle without addExports: the class-path main cannot reach the unexported package.
        Path bundle = directory.resolve("denied-app.jar");
        Map<String, byte[]> module = new LinkedHashMap<>();
        module.put("module-info.class", TestJars.moduleInfo("demo.lib"));
        module.put("demo/lib/Tool.class", TestJars.runner("demo.lib.Tool", "granted"));
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main"),
                Map.of("app.jar",
                        TestJars.classJar("demo.app.Main", TestJars.callRunMain("demo.app.Main", "demo.lib.Tool"))),
                Map.of("lib.jar", TestJars.jar(module)));

        assertThatThrownBy(() -> launch(bundle, "jenesis.test.denied"))
                .isInstanceOf(IllegalAccessError.class);
    }

    @Test
    void runsAgentBundlePremain() throws Exception {
        // A bundle with no mainClass, used as a Java agent: runAgents invokes the bundled agent's premain,
        // passing the launcher's arguments (the -javaagent:foo.jar=... value) when the agent declares none.
        Path bundle = directory.resolve("agent-bundle.jar");
        String key = "jenesis.test.agentbundle";
        TestJars.writeBundle(bundle,
                Map.of("agentClass", "demo.agent.Probe"),
                Map.of("probe.jar", TestJars.classJar("demo.agent.Probe", TestJars.argumentPremain("demo.agent.Probe", key))),
                Map.of());

        System.clearProperty(key);
        Launcher.runAgents(bundle, false, "from-javaagent", null);

        assertThat(System.getProperty(key)).isEqualTo("from-javaagent");
    }

    @Test
    void runAgentsDefersToMainForApplicationBundle() throws Exception {
        // A bundle WITH a mainClass is an application: runAgents leaves its agents to Launcher.run.
        Path bundle = directory.resolve("app-with-agent.jar");
        String key = "jenesis.test.deferred";
        TestJars.writeBundle(bundle,
                Map.of("mainClass", "demo.app.Main", "agentClass", "demo.agent.Probe"),
                Map.of("app.jar", TestJars.classJar("demo.app.Main", TestJars.setPropertyMain("demo.app.Main")),
                        "probe.jar", TestJars.classJar("demo.agent.Probe", TestJars.argumentPremain("demo.agent.Probe", key))),
                Map.of());

        System.clearProperty(key);
        Launcher.runAgents(bundle, false, "ignored", null);

        assertThat(System.getProperty(key)).isNull();
    }

    @Test
    void runAgentsResolvesBundleFromClassCodeSource() throws Exception {
        // runAgents(Class, ...) resolves the bundle from the class's own jar - the mechanism a generated,
        // uniquely named Premain-Class uses so several agent bundles, each with its own properties, coexist.
        String key = "jenesis.test.trampoline";
        Properties properties = new Properties();
        properties.setProperty("agentClass", "demo.agent.Probe");
        ByteArrayOutputStream props = new ByteArrayOutputStream();
        properties.store(props, null);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("application.properties", props.toByteArray());
        entries.put("classpath/probe.jar/demo/agent/Probe.class", TestJars.argumentPremain("demo.agent.Probe", key));
        entries.put("marker/Marker.class", TestJars.setPropertyMain("marker.Marker"));
        Path bundle = directory.resolve("trampoline-bundle.jar");
        Files.write(bundle, TestJars.jar(entries));

        Class<?> marker;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {bundle.toUri().toURL()}, null)) {
            marker = loader.loadClass("marker.Marker");
        }
        System.clearProperty(key);
        Launcher.runAgents(marker, false, "from-trampoline", null);

        assertThat(System.getProperty(key)).isEqualTo("from-trampoline");
    }

    @Test
    void rejectsBundleWithoutMainClass() throws Exception {
        Path bundle = directory.resolve("empty-app.jar");
        TestJars.writeBundle(bundle, Map.of(), Map.of(), Map.of());

        assertThatThrownBy(() -> launch(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mainClass");
    }

    private static void launch(Path bundle, String... args) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Launcher.run(bundle, args);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
