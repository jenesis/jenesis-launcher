package build.jenesis.launcher;

import module java.base;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for an executable jar produced by Jenesis.
 *
 * <p>The jar bundles its dependencies as <em>nested</em> jars under {@code classpath/} and
 * {@code modulepath/} (rather than exploding their classes into one flat jar), so module identity,
 * {@code module-info}, signatures and {@code META-INF/services} survive intact. This class is shaded
 * into the jar root and named as the manifest {@code Main-Class}; {@code java -jar foo.jar} therefore
 * lands here. It then reproduces, in process and entirely from memory, what
 * {@code java -p modulepath -cp classpath -m mainModule/mainClass} would have done:</p>
 *
 * <ol>
 *   <li>read {@code application.properties} ({@code mainClass}, optional {@code mainModule}, optional
 *       {@code agentClass}) and the nested jars (see {@link Archive});</li>
 *   <li>build an {@link InMemoryClassLoader} over the {@code classpath/} jars - the in-memory
 *       equivalent of the unnamed module;</li>
 *   <li>if there is a main module, resolve the {@code modulepath/} jars with an
 *       {@link InMemoryModuleFinder} and define a child {@link ModuleLayer} whose single loader has
 *       the class-path loader as its parent, so automatic modules can read the class path while
 *       named modules cannot - the JDK's own rule;</li>
 *   <li>invoke {@code premain} on each agent named by {@code agentClass}, before the main class is
 *       loaded, so a transformer they register still sees it being defined - exactly as
 *       {@code -javaagent} would (see {@link LauncherAgent} for how the {@link Instrumentation} is
 *       obtained);</li>
 *   <li>invoke {@code main} on the resolved class.</li>
 * </ol>
 *
 * <p>The boot module layer is immutable, so modular dependencies necessarily form a new layer rather
 * than joining the system loader; this is the faithful, supported way to keep them modular.</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        run(location(), args);
    }

    /**
     * Launches the application bundled at {@code location} (a jar file or an exploded directory).
     * Useful for embedding the launcher programmatically, and for pointing it at a fixture without
     * going through {@link #location()}.
     */
    public static void run(Path location, String[] args) throws Exception {
        Archive archive = Archive.load(location);
        String mainClass = archive.application().getProperty("mainClass");
        String mainModule = archive.application().getProperty("mainModule");
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException("No 'mainClass' declared in " + Archive.APPLICATION
                    + " of " + location);
        }

        InMemoryClassLoader classpath = new InMemoryClassLoader(
                archive.classpath(), ClassLoader.getSystemClassLoader());

        ClassLoader context;
        if (mainModule != null && !mainModule.isBlank() && !archive.modulepath().isEmpty()) {
            InMemoryModuleFinder finder = new InMemoryModuleFinder(archive.modulepath());
            java.lang.module.Configuration configuration = ModuleLayer.boot().configuration()
                    .resolveAndBind(finder, ModuleFinder.of(), finder.moduleNames());
            // The static factory returns a Controller, the supported handle a layer creator uses to
            // break encapsulation of the modules it just defined. The single loader has the class-path
            // loader as its parent, so automatic modules can read the class path but named ones cannot.
            ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(
                    configuration, List.of(ModuleLayer.boot()), classpath);
            ModuleLayer layer = controller.layer();
            ClassLoader loader = layer.findLoader(mainModule);
            if (loader == null) {
                throw new IllegalStateException("Main module not found on the module path: " + mainModule);
            }
            // Reproduce `java -m <module>/<class>`, which invokes main without requiring its package to
            // be exported: grant this launcher access to the main package through the controller. This
            // is done from the declared module and class name rather than the loaded class, so it can
            // happen before any agent runs and before the main class is defined.
            Module application = layer.findModule(mainModule).orElseThrow();
            String packageName = packageOf(mainClass);
            if (application.getPackages().contains(packageName)) {
                Module launcher = Launcher.class.getModule();
                controller.addExports(application, packageName, launcher);
                controller.addOpens(application, packageName, launcher);
            }
            context = loader;
        } else {
            context = classpath;
        }

        Thread.currentThread().setContextClassLoader(context);
        // Run agents before the main class is loaded, mirroring `-javaagent`: a ClassFileTransformer a
        // premain registers must be in place for the JVM to apply it to the main class being defined.
        runAgents(archive.application(), context);
        invokeMain(context.loadClass(mainClass), args);
    }

    /**
     * Bootstraps the agents named by the {@code agentClass} property, in declaration order, by invoking
     * each agent class's {@code premain}. The value is a comma-separated list of fully qualified class
     * names, each optionally followed by {@code =<arguments>} (mirroring {@code -javaagent:<jar>=<args>});
     * arguments run to the end of the entry. The agents are loaded from the application's runtime loader,
     * so they may live on the class path or, for a modular application, on the module path.
     */
    private static void runAgents(Properties application, ClassLoader loader) throws Exception {
        String declaration = application.getProperty("agentClass");
        if (declaration == null || declaration.isBlank()) {
            return;
        }
        Instrumentation instrumentation = LauncherAgent.instrumentation();
        for (String entry : declaration.split(",")) {
            int equals = entry.indexOf('=');
            String className = (equals == -1 ? entry : entry.substring(0, equals)).strip();
            // No `=` means no arguments at all (null), matching how the JVM passes agentArgs.
            String arguments = equals == -1 ? null : entry.substring(equals + 1);
            if (!className.isEmpty()) {
                invokePremain(loader.loadClass(className), arguments, instrumentation);
            }
        }
    }

    private static void invokePremain(Class<?> agent, String arguments, Instrumentation instrumentation)
            throws Exception {
        // Mirror the JVM's own agent start-up: prefer premain(String, Instrumentation), fall back to
        // premain(String). The two-argument form is only usable when an Instrumentation was captured.
        Method premain = instrumentation == null ? null : premain(agent, String.class, Instrumentation.class);
        Object[] parameters = premain == null
                ? new Object[] {arguments}
                : new Object[] {arguments, instrumentation};
        if (premain == null) {
            premain = premain(agent, String.class);
        }
        if (premain == null) {
            throw new IllegalStateException("Agent class " + agent.getName() + " declares no static"
                    + " premain(String) or premain(String, Instrumentation) method"
                    + (instrumentation == null
                            ? "; without instrumentation only premain(String) can run - declare"
                              + " 'Launcher-Agent-Class: " + LauncherAgent.class.getName()
                              + "' in the manifest to capture it"
                            : ""));
        }
        premain.setAccessible(true);
        try {
            premain.invoke(null, parameters);
        } catch (InvocationTargetException thrownByAgent) {
            rethrowCause(thrownByAgent);
        }
    }

    private static Method premain(Class<?> agent, Class<?>... parameterTypes) {
        try {
            Method method = agent.getDeclaredMethod("premain", parameterTypes);
            return Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot == -1 ? "" : className.substring(0, dot);
    }

    private static void invokeMain(Class<?> type, String[] args) throws Exception {
        Method method = type.getMethod("main", String[].class);
        try {
            method.invoke(null, (Object) args);
        } catch (IllegalAccessException notExported) {
            // Public main in a package the module does not export: open it reflectively if allowed,
            // otherwise tell the user to export (or open) the main package, as `java -m` requires.
            try {
                method.setAccessible(true);
            } catch (RuntimeException stillClosed) {
                throw new IllegalStateException("Cannot access " + type.getName()
                        + ".main(String[]); export or open its package from the main module", stillClosed);
            }
            method.invoke(null, (Object) args);
        } catch (InvocationTargetException thrownByApplication) {
            rethrowCause(thrownByApplication);
        }
    }

    /** Unwraps a reflective invocation failure so the cause thrown by the callee surfaces directly. */
    private static void rethrowCause(InvocationTargetException wrapper) throws Exception {
        Throwable cause = wrapper.getCause();
        switch (cause) {
            case null -> throw wrapper;
            case Exception exception -> throw exception;
            case Error error -> throw error;
            default -> throw wrapper;
        }
    }

    private static Path location() throws URISyntaxException {
        CodeSource source = Launcher.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("Cannot determine the location of the executable jar");
        }
        return Path.of(source.getLocation().toURI());
    }
}
