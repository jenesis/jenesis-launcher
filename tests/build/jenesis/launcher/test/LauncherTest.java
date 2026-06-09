package build.jenesis.launcher.test;

import module java.base;

import build.jenesis.launcher.Launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LauncherTest {

    @TempDir
    Path directory;

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
    void loadsResourcesThroughInMemoryUrl() throws Exception {
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
