package build.jenesis.launcher;

import module java.base;
import module java.instrument;

/**
 * Entry point for an executable jar produced by Jenesis, and the bootstrap for using such a bundle as a
 * Java agent.
 *
 * <p>Each dependency is exploded into its own subfolder of the jar ({@code classpath/<name>/},
 * {@code modulepath/<name>/}), so the launcher reads classes and resources on demand from the still-open
 * outer jar (see {@link Archive}). {@link #main} - the manifest {@code Main-Class}, where
 * {@code java -jar foo.jar} lands - reproduces
 * {@code java -p modulepath -cp classpath -m mainModule/mainClass}: build a single
 * {@link InMemoryClassLoader} whose unnamed module is the {@code classpath/} subfolders and, if there are
 * {@code modulepath/} subfolders, define a child {@link ModuleLayer} mapping every module to that same
 * loader; invoke {@code premain} on each agent named by {@code agentClass} before the main class is loaded;
 * then invoke {@code main}.</p>
 *
 * <p>A bundle with no {@code mainClass} is instead a self-contained Java agent: referenced as
 * {@code -javaagent:foo.jar} or attached dynamically, {@link LauncherAgent} enters {@link #runAgents} to
 * build the same isolated loader and run the bundled agents' {@code premain}/{@code agentmain} against the
 * host's {@link Instrumentation} - the agent's dependencies stay in the bundle's loader, off the host's
 * class path.</p>
 *
 * <p>The boot module layer is immutable, so modular dependencies necessarily form a new layer rather than
 * joining the system loader; this is the faithful, supported way to keep them modular.</p>
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
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException("No 'mainClass' declared in " + Archive.APPLICATION
                    + " of " + location);
        }
        InMemoryClassLoader loader = prepare(archive);
        Thread.currentThread().setContextClassLoader(loader);
        // Run agents before the main class is loaded, mirroring `-javaagent`: a ClassFileTransformer a
        // premain registers must be in place for the JVM to apply it to the main class being defined.
        invokeAgents("premain", LauncherAgent.instrumentation(), null, archive.application(), loader);
        invokeMain(loader.loadClass(mainClass), args);
    }

    /**
     * Bootstraps a bundle used as a Java agent - one with no {@code mainClass} - building its isolated
     * loader and invoking, on each agent named by {@code agentClass}, {@code agentmain} when {@code attach}
     * is set (dynamic attach) or {@code premain} otherwise ({@code -javaagent}), with the host's
     * {@code instrumentation}. A bundle that declares a {@code mainClass} is an application whose agents are
     * run by {@link #run} before {@code main}, so this method does nothing for it. The JVM normally enters
     * here through {@link LauncherAgent}; this is also the embedding entry point.
     */
    public static void runAgents(Path location, boolean attach, String arguments, Instrumentation instrumentation)
            throws Exception {
        Archive archive = Archive.load(location);
        String mainClass = archive.application().getProperty("mainClass");
        if (mainClass != null && !mainClass.isBlank()) {
            // An application bundle: its agents are run by Launcher.run, and no loader is built here, so
            // close the archive we just opened rather than leaking its jar handle until the next GC.
            archive.close();
            return;
        }
        InMemoryClassLoader loader = prepare(archive);
        // Set the context loader only while the agents start, then restore it: the host application keeps
        // running on this thread afterwards and must not inherit the bundle's loader.
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            invokeAgents(attach ? "agentmain" : "premain", instrumentation, arguments, archive.application(), loader);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * Bootstraps the agent bundle whose jar is {@code premainClass}'s code source. This lets several agent
     * bundles coexist in one JVM: the JVM loads a {@code Premain-Class} by binary name only once, so a shared
     * one cannot tell the bundles apart - but a bundle that ships its own uniquely named {@code Premain-Class}
     * (a small generated class whose {@code premain}/{@code agentmain} just call this with its own class) is
     * loaded on its own and resolves to its own jar, with its own {@code application.properties} and
     * dependencies. Otherwise as {@link #runAgents(Path, boolean, String, Instrumentation)}.
     */
    public static void runAgents(Class<?> premainClass, boolean attach, String arguments,
                                 Instrumentation instrumentation) throws Exception {
        runAgents(location(premainClass), attach, arguments, instrumentation);
    }

    /**
     * Builds the single loader for a bundle: the {@code classpath/} subfolders are its unnamed module and,
     * when there are {@code modulepath/} subfolders, they are resolved and mapped to that same loader through
     * a child {@link ModuleLayer} - so one loader hosts the named modules and the unnamed module together,
     * just as {@code java -p modulepath -cp classpath} does (automatic modules read the class path while named
     * ones cannot, and a module shadows a same-named class-path package). Grants this launcher access to a
     * modular main package and applies the {@code addExports}/{@code addOpens}/{@code addReads} properties.
     */
    private static InMemoryClassLoader prepare(Archive archive) throws Exception {
        String mainClass = archive.application().getProperty("mainClass");
        String mainModule = archive.application().getProperty("mainModule");
        ClassLoader system = ClassLoader.getSystemClassLoader();
        InMemoryClassLoader loader;
        ModuleLayer.Controller controller = null;
        ModuleLayer layer = null;
        if (!archive.modulepath().isEmpty()) {
            InMemoryModuleFinder finder = new InMemoryModuleFinder(archive.modulepath());
            // Reproduce `java -m <mainModule>`: root the main module and let resolution pull in its
            // `requires` closure (resolveAndBind also binds services). Unless this is a self-contained module
            // graph - a main module over a pure named-module path - every module is rooted instead, the
            // in-bundle `--add-modules ALL-MODULE-PATH`. Self-containment is broken by an automatic module
            // (declares no `requires`, so a named module it uses only internally is never resolved) or by a
            // class path (an unnamed module readable only through resolved modules); an agent bundle has no
            // main module to root.
            boolean automatic = finder.findAll().stream().anyMatch(reference -> reference.descriptor().isAutomatic());
            boolean selfContainedModuleGraph = mainModule != null
                    && !mainModule.isBlank()
                    && !automatic
                    && archive.classpath().isEmpty();
            Set<String> roots = selfContainedModuleGraph ? Set.of(mainModule) : finder.moduleNames();
            java.lang.module.Configuration configuration = ModuleLayer.boot().configuration()
                    .resolveAndBind(finder, ModuleFinder.of(), roots);
            // Resolve and construct the loader first: defineModules records each module's packages against
            // the loader, after which class loading may begin.
            loader = new InMemoryClassLoader(archive, finder, system);
            controller = ModuleLayer.defineModules(configuration, List.of(ModuleLayer.boot()), _ -> loader);
            layer = controller.layer();
        } else {
            loader = new InMemoryClassLoader(archive, null, system);
        }
        if (controller != null && mainModule != null && !mainModule.isBlank()
                && mainClass != null && !mainClass.isBlank()) {
            // Reproduce `java -m <module>/<class>`, which invokes main without requiring its package to be
            // exported: grant this launcher access to the main package, derived from the declared module and
            // class name so it happens before the main class is defined.
            Module application = layer.findModule(mainModule).orElseThrow(() ->
                    new IllegalStateException("Main module not found on the module path: " + mainModule));
            String packageName = packageOf(mainClass);
            if (application.getPackages().contains(packageName)) {
                Module launcher = Launcher.class.getModule();
                controller.addExports(application, packageName, launcher);
                controller.addOpens(application, packageName, launcher);
            }
        }
        if (controller != null) {
            grantAccess(controller, layer, loader, archive.application());
        }
        return loader;
    }

    /**
     * Invokes {@code method} ({@code premain}/{@code agentmain}) on each agent named by the
     * {@code agentClass} property, in declaration order. The value is a comma-separated list of fully
     * qualified class names, each optionally followed by {@code =<arguments>} (mirroring
     * {@code -javaagent:<jar>=<args>}); a directive's own arguments win, otherwise the agent gets
     * {@code defaultArguments}. The agents are loaded from the bundle's loader, so they may live on the class
     * path or the module path.
     */
    private static void invokeAgents(String method, Instrumentation instrumentation, String defaultArguments,
                                     Properties application, ClassLoader loader) throws Exception {
        String declaration = application.getProperty("agentClass");
        if (declaration == null || declaration.isBlank()) {
            return;
        }
        for (String entry : declaration.split(",")) {
            int equals = entry.indexOf('=');
            String className = (equals == -1 ? entry : entry.substring(0, equals)).strip();
            String arguments = equals == -1 ? defaultArguments : entry.substring(equals + 1);
            if (!className.isEmpty()) {
                invokeAgent(loader.loadClass(className), method, arguments, instrumentation);
            }
        }
    }

    private static void invokeAgent(Class<?> agent, String method, String arguments, Instrumentation instrumentation)
            throws Exception {
        // Mirror the JVM's own agent start-up: prefer <method>(String, Instrumentation), fall back to
        // <method>(String). The two-argument form is only usable when an Instrumentation is available.
        Method entry = instrumentation == null ? null : agentMethod(agent, method, String.class, Instrumentation.class);
        Object[] parameters = entry == null
                ? new Object[] {arguments}
                : new Object[] {arguments, instrumentation};
        if (entry == null) {
            entry = agentMethod(agent, method, String.class);
        }
        if (entry == null) {
            throw new IllegalStateException("Agent class " + agent.getName() + " declares no static "
                    + method + "(String) or " + method + "(String, Instrumentation) method"
                    + (instrumentation == null
                            ? "; without instrumentation only " + method + "(String) can run - declare"
                              + " 'Launcher-Agent-Class: " + LauncherAgent.class.getName()
                              + "' in the manifest to capture it"
                            : ""));
        }
        entry.setAccessible(true);
        try {
            entry.invoke(null, parameters);
        } catch (InvocationTargetException thrownByAgent) {
            rethrowCause(thrownByAgent);
        }
    }

    private static Method agentMethod(Class<?> agent, String name, Class<?>... parameterTypes) {
        try {
            Method method = agent.getDeclaredMethod(name, parameterTypes);
            return Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot == -1 ? "" : className.substring(0, dot);
    }

    /**
     * Applies the optional {@code addExports}, {@code addOpens} and {@code addReads} properties to the
     * bundled modules through the layer's {@link ModuleLayer.Controller} - the in-bundle equivalent of the
     * {@code --add-exports} / {@code --add-opens} / {@code --add-reads} command-line options. Directives are
     * separated by {@code ;}; targets within a directive by {@code ,}. {@code addExports}/{@code addOpens}
     * read {@code module/package=target...}, {@code addReads} reads {@code module=target...}; a target is a
     * module name or {@code ALL-UNNAMED}.
     */
    private static void grantAccess(ModuleLayer.Controller controller, ModuleLayer layer, ClassLoader loader,
                                    Properties application) {
        for (Directive directive : directives(application.getProperty("addExports"), true)) {
            controller.addExports(source(layer, directive.module()), directive.packageName(),
                    target(directive.target(), layer, loader));
        }
        for (Directive directive : directives(application.getProperty("addOpens"), true)) {
            controller.addOpens(source(layer, directive.module()), directive.packageName(),
                    target(directive.target(), layer, loader));
        }
        for (Directive directive : directives(application.getProperty("addReads"), false)) {
            controller.addReads(source(layer, directive.module()), target(directive.target(), layer, loader));
        }
    }

    private record Directive(String module, String packageName, String target) {
    }

    private static List<Directive> directives(String value, boolean qualified) {
        List<Directive> directives = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return directives;
        }
        for (String entry : value.split(";")) {
            String specification = entry.strip();
            if (specification.isEmpty()) {
                continue;
            }
            int equals = specification.indexOf('=');
            if (equals == -1) {
                throw new IllegalStateException("Malformed access directive (expected '='): " + specification);
            }
            String left = specification.substring(0, equals).strip();
            String module;
            String packageName;
            if (qualified) {
                int slash = left.indexOf('/');
                if (slash == -1) {
                    throw new IllegalStateException(
                            "Malformed addExports/addOpens (expected 'module/package'): " + left);
                }
                module = left.substring(0, slash).strip();
                packageName = left.substring(slash + 1).strip();
            } else {
                module = left;
                packageName = null;
            }
            for (String target : specification.substring(equals + 1).split(",")) {
                String trimmed = target.strip();
                if (!trimmed.isEmpty()) {
                    directives.add(new Directive(module, packageName, trimmed));
                }
            }
        }
        return directives;
    }

    private static Module source(ModuleLayer layer, String module) {
        return layer.findModule(module).orElseThrow(() ->
                new IllegalStateException("Module named by addExports/addOpens/addReads is not bundled: " + module));
    }

    private static Module target(String target, ModuleLayer layer, ClassLoader loader) {
        if (target.equals("ALL-UNNAMED")) {
            return loader.getUnnamedModule();
        }
        return layer.findModule(target)
                .or(() -> ModuleLayer.boot().findModule(target))
                .orElseThrow(() -> new IllegalStateException("Target module not found: " + target));
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

    static Path location() throws URISyntaxException {
        return location(Launcher.class);
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("Cannot determine the jar of " + type.getName());
        }
        return Path.of(source.getLocation().toURI());
    }
}
