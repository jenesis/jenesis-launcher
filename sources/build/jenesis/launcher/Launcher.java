package build.jenesis.launcher;

import module java.base;
import module java.instrument;

/**
 * Entry point for an executable jar produced by Jenesis, and the bootstrap for using such a bundle as a
 * Java agent.
 *
 * <p>Each dependency is exploded into its own subfolder of the one {@code jars/} store, so the launcher
 * reads classes and resources on demand from the still-open outer jar (see {@link Archive}). What a jar is
 * for is named by the descriptor rather than shown by where it sits. {@link #main} - the manifest
 * {@code Main-Class}, where {@code java -jar foo.jar} lands - reproduces
 * {@code java -p modulepath -cp classpath -m mainModule/mainClass}: build a single
 * {@link InMemoryClassLoader} whose unnamed module is what {@code classpath} names and, for what
 * {@code modulepath} names, define a child {@link ModuleLayer} mapping every module to that same
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

    /**
     * System property prefix naming a layer's paths when it is not bundled in this jar. Deliberately not a
     * {@code jenesis.*} key: those configure a build, and this one is read by the application that build
     * produced, wherever it runs and long after the build is over.
     */
    private static final String LAYER_PATH = "jlayer.";

    private static final Map<Module, Map<String, ModuleLayer>> LAYERS = new ConcurrentHashMap<>();

    /**
     * The layer this launcher defined for the application, if it defined one. A caller on the class path is
     * in the unnamed module and has no layer of its own, so this is the layer its own layers hang from: the
     * application's modules are defined to the very loader that holds the class path, and the API module a
     * layer shares is among them.
     */
    private static volatile ModuleLayer application;

    private Launcher() {
    }

    /**
     * The module layer the calling module declared under {@code name}, defined once and cached. The caller is
     * the class of {@code lookup}, which must be the full-privilege lookup that class created with
     * {@link MethodHandles#lookup()}: a caller is named rather than read off the stack, so that what the layer
     * is granted is decided by the module that asked for it, and handing that lookup on is that module's own
     * decision. The layer is a child of the caller's own, so every module it does not itself hold - the API
     * module the caller and the layer share above all - resolves from the caller's layer and is the very same
     * class on both sides.
     *
     * <p>A layer is named on its own. That is already how the build keys it - the {@code layer:<name>}
     * group it resolves in, and the pins written against it - so a name is global and a duplicate is
     * refused there rather than resolved here. Keying it by the calling module instead would have asked
     * the caller to be a named module, which a jar cannot promise: whoever consumes it decides whether it
     * lands on the module path or the class path, and on the class path there is no module to name.</p>
     *
     * <p>The layer is still defined once per caller, because it is a child of the caller's own layer: the
     * same declaration reached from two depths is two layers, which is what lets a module inside a layer
     * declare one of its own.</p>
     *
     * <p>The modules come from this jar when the caller runs inside a bundle that declares them, read on
     * demand like every other bundled class; otherwise from the path named by
     * {@code jlayer.<path>.<name>}, which is how a deployment that unpacked its dependencies supplies
     * them. Native access is granted to the modules {@code enableNativeAccess.<name>} names through
     * {@code lookup}, so the JDK allows it only where the calling module has native access itself.</p>
     *
     * @throws IllegalArgumentException if {@code lookup} does not have full privilege access.
     */
    public static ModuleLayer layer(MethodHandles.Lookup lookup, String name) {
        if (!lookup.hasFullPrivilegeAccess()) {
            throw new IllegalArgumentException("A layer is defined for the class whose lookup is passed, so the"
                    + " lookup must have full privilege access - pass MethodHandles.lookup() from the calling"
                    + " class, not " + lookup);
        }
        return LAYERS.computeIfAbsent(lookup.lookupClass().getModule(), _ -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, key -> define(lookup, key));
    }

    /**
     * The providers of {@code service} in the calling module's {@code name} layer - {@link #layer} followed
     * by a {@link ServiceLoader} over it.
     *
     * <p>{@code ServiceLoader} checks {@code uses} against the calling module and offers no overload that
     * takes a caller, so this method would be refused for a service this launcher cannot name. It therefore
     * adds the service dependence to its own module first, which a module is permitted to do for itself.
     * The calling module needs no {@code uses} clause: the call names the service, which is the declaration
     * this mechanism actually goes on.</p>
     */
    public static <S> ServiceLoader<S> load(MethodHandles.Lookup lookup, String name, Class<S> service) {
        ModuleLayer layer = layer(lookup, name);
        Launcher.class.getModule().addUses(service);
        return ServiceLoader.load(layer, service);
    }

    /**
     * The one provider of {@code service} in the caller's {@code name} layer, instantiated. A layer is
     * reached through the implementation it provides, so finding none or finding several is a mistake in
     * what the layer holds rather than a choice to make here: {@link #load} is what offers the choice.
     *
     * @throws IllegalStateException if the layer provides no implementation, or more than one.
     */
    public static <S> S instance(MethodHandles.Lookup lookup, String name, Class<S> service) {
        List<ServiceLoader.Provider<S>> providers = load(lookup, name, service)
                .stream()
                .toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException("Layer " + name + " provides no " + service.getName()
                    + " - a module in the layer declares it with 'provides " + service.getSimpleName()
                    + " with ...', or names it in META-INF/services");
        }
        if (providers.size() > 1) {
            throw new IllegalStateException("Layer " + name + " provides " + providers.size() + " of "
                    + service.getName() + ", including " + providers.getFirst().type().getName() + " and "
                    + providers.get(1).type().getName()
                    + " - keep one, or ask for all of them with Launcher.load");
        }
        return providers.getFirst().get();
    }

    private static ModuleLayer define(MethodHandles.Lookup lookup, String name) {
        Class<?> caller = lookup.lookupClass();
        Module module = caller.getModule();
        ModuleLayer own = module.getLayer(), hosted = application;
        ModuleLayer parent = own != null ? own : hosted != null ? hosted : ModuleLayer.boot();
        try {
            Archive archive = bundle(caller);
            Archive.Layer bundled = archive == null ? null : archive.layers().get(name);
            if (bundled == null) {
                List<Path> modulepath = paths(name, Archive.LAYER_MODULE_PATH);
                ModuleFinder finder = ModuleFinder.of(modulepath.toArray(Path[]::new));
                java.lang.module.Configuration configuration = parent.configuration().resolveAndBind(
                        finder,
                        ModuleFinder.of(),
                        finder.findAll()
                                .stream()
                                .map(reference -> reference.descriptor().name())
                                .collect(Collectors.toUnmodifiableSet()));
                verify(name, configuration);
                return enableNativeAccess(lookup, name, System.getProperty(LAYER_PATH + Archive.LAYER_NATIVE_ACCESS + name),
                        ModuleLayer.defineModulesWithOneLoader(configuration, List.of(parent),
                                unnamed(paths(name, Archive.LAYER_CLASS_PATH))));
            }
            InMemoryModuleFinder finder = new InMemoryModuleFinder(bundled.modulepath());
            java.lang.module.Configuration configuration = parent.configuration()
                    .resolveAndBind(finder, ModuleFinder.of(), finder.moduleNames());
            verify(name, configuration);
            InMemoryClassLoader loader = new InMemoryClassLoader(archive, bundled.classpath(), finder,
                    ClassLoader.getPlatformClassLoader());
            loader.remote(configuration, List.of(parent));
            return enableNativeAccess(lookup, name, archive.application().getProperty(Archive.LAYER_NATIVE_ACCESS + name),
                    ModuleLayer.defineModules(configuration, List.of(parent), _ -> loader));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to define layer " + name + " for " + module, e);
        }
    }

    /**
     * Grants native access to the modules of a freshly defined layer that {@code declaration} names, a
     * comma-separated list read from {@code jlayer.enableNativeAccess.<name>} for a layer on disk or from
     * {@code enableNativeAccess.<name>} in the bundled descriptor - the layer's equivalent of
     * {@code --enable-native-access}, which cannot name a module the boot layer does not hold. The grant is
     * made through the caller's {@code lookup}, so the JDK checks the module that asked for the layer rather
     * than this launcher: a caller without native access is warned about or refused exactly as if it had
     * called {@link ModuleLayer.Controller#enableNativeAccess} itself, and neither a rewritten property nor
     * a layer this launcher defines gives it more. A layer's class path is an unnamed module, which only
     * {@code ALL-UNNAMED} on the command line or in the manifest reaches.
     */
    private static ModuleLayer enableNativeAccess(MethodHandles.Lookup lookup,
                                                  String name,
                                                  String declaration,
                                                  ModuleLayer.Controller controller) {
        if (declaration == null || declaration.isBlank()) {
            return controller.layer();
        }
        MethodHandle grant;
        try {
            grant = lookup.findVirtual(ModuleLayer.Controller.class, "enableNativeAccess",
                    MethodType.methodType(ModuleLayer.Controller.class, Module.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot grant native access in layer " + name + " through " + lookup, e);
        }
        for (String entry : declaration.split(",")) {
            String module = entry.strip();
            if (!module.isEmpty()) {
                Module target = controller.layer().findModule(module).orElseThrow(() ->
                        new IllegalStateException("Layer " + name + " holds no module " + module
                                + " to enable native access for"));
                try {
                    grant.invoke(controller, target);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException("Cannot grant native access to " + module + " in layer " + name, e);
                }
            }
        }
        return controller.layer();
    }

    /**
     * Refuses a layer that both provides a service and holds the module declaring it: the caller would look
     * the service up against a different class of the same name and find no provider. The build refuses this
     * too, so this is the backstop for a bundle assembled by other means.
     */
    private static void verify(String name, java.lang.module.Configuration configuration) {
        for (ResolvedModule resolved : configuration.modules()) {
            for (ModuleDescriptor.Provides provides : resolved.reference().descriptor().provides()) {
                String contract = packageOf(provides.service());
                for (ResolvedModule declaring : configuration.modules()) {
                    if (declaring.reference().descriptor().packages().contains(contract)) {
                        throw new IllegalStateException("Layer " + name + " provides " + provides.service()
                                + ", but holds " + declaring.name() + ", which declares it - the caller would"
                                + " look the service up against a different class of the same name and find"
                                + " no provider; share that module with the caller instead of isolating it");
                    }
                }
            }
        }
    }

    /**
     * The loader a file-based layer's class path is read by, and the platform loader when it has none. The
     * caller's own class path is deliberately not on this chain: a layer exists to hide the version the
     * caller holds, so its unnamed module is its own rather than a view onto the application's.
     */
    private static ClassLoader unnamed(List<Path> classpath) {
        if (classpath.isEmpty()) {
            return ClassLoader.getPlatformClassLoader();
        }
        URL[] urls = new URL[classpath.size()];
        for (int index = 0; index < urls.length; index++) {
            try {
                urls[index] = classpath.get(index).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Failed to build a URL for " + classpath.get(index), e);
            }
        }
        return new URLClassLoader("jenesis-layer", urls, ClassLoader.getPlatformClassLoader());
    }

    /**
     * One of a layer's two paths, as named by {@code jenesis.layer.modulepath.<module>.<name>} or its
     * {@code classpath} counterpart. Only the module path must be named: a layer that isolates nothing but
     * modules has no class path, and saying so by omission is how that reads.
     */
    private static List<Path> paths(String name, String prefix) {
        String property = LAYER_PATH + prefix + name;
        String declaration = System.getProperty(property);
        if (declaration == null || declaration.isBlank()) {
            if (!prefix.equals(Archive.LAYER_MODULE_PATH)) {
                return List.of();
            }
            throw new IllegalStateException("No layer " + name + " is bundled in this jar, and no "
                    + property + " names where its modules are - a deployment that unpacked its"
                    + " dependencies supplies that property");
        }
        return Arrays.stream(declaration.split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
    }

    /**
     * The bundle the calling class was loaded from, or {@code null} when it was not bundled. A bundled class
     * has a {@code jar:} code source into the outer jar, so the jar it names is the archive to read the
     * layer from. It is the caller's bundle that matters, not this launcher's: the launcher may be the
     * shaded bootstrap in the jar root, an ordinary module-path dependency, or neither.
     */
    private static Archive bundle(Class<?> caller) throws IOException {
        CodeSource source = caller.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        String location = source.getLocation().toString();
        if (location.startsWith("jar:")) {
            int separator = location.indexOf("!/");
            location = separator < 0 ? location.substring(4) : location.substring(4, separator);
        }
        URI uri = URI.create(location);
        if (!"file".equals(uri.getScheme())) {
            return null;
        }
        Path path = Path.of(uri);
        if (!Files.exists(path)) {
            return null;
        }
        Archive archive = Archive.load(path);
        if (archive.application().isEmpty()) {
            archive.close();
            return null;
        }
        return archive;
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
     * Builds the single loader for a bundle: the jars {@code classpath} names are its unnamed module and,
     * when {@code modulepath} names any, they are resolved and mapped to that same loader through
     * a child {@link ModuleLayer} - so one loader hosts the named modules and the unnamed module together,
     * just as {@code java -p modulepath -cp classpath} does (automatic modules read the class path while named
     * ones cannot, and a module shadows a same-named class-path package). Grants this launcher access to a
     * modular main package and applies the {@code addExports}/{@code addOpens}/{@code addReads} and
     * {@code enableNativeAccess} properties.
     */
    private static InMemoryClassLoader prepare(Archive archive) throws Exception {
        String mainClass = archive.application().getProperty("mainClass");
        String mainModule = archive.application().getProperty("mainModule");
        ClassLoader system = ClassLoader.getSystemClassLoader();
        InMemoryClassLoader loader;
        ModuleLayer.Controller controller = null;
        ModuleLayer layer = null;
        List<Archive.Jar> modulepath = archive.modulepath();
        if (!modulepath.isEmpty()) {
            InMemoryModuleFinder finder = new InMemoryModuleFinder(modulepath);
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
            application = layer;
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
     * module name or {@code ALL-UNNAMED}. {@code enableNativeAccess} is the equivalent of
     * {@code --enable-native-access}: a comma-separated list of bundled module names. The class path has no
     * module to name here; its native access is the {@code Enable-Native-Access: ALL-UNNAMED} attribute of
     * this jar's own manifest, which the JVM reads for {@code java -jar} and which also lets this launcher
     * grant the named modules without a warning of its own.
     */
    private static void grantAccess(ModuleLayer.Controller controller, ModuleLayer layer, ClassLoader loader,
                                    Properties application) {
        String nativeAccess = application.getProperty("enableNativeAccess");
        if (nativeAccess != null) {
            for (String module : nativeAccess.split(",")) {
                String name = module.strip();
                if (!name.isEmpty()) {
                    controller.enableNativeAccess(source(layer, name));
                }
            }
        }
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
                new IllegalStateException("Module named by addExports/addOpens/addReads/enableNativeAccess is not bundled: "
                        + module));
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
