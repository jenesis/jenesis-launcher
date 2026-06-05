package build.jenesis.launcher;

import module java.base;

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
 *   <li>read {@code application.properties} ({@code mainClass}, optional {@code mainModule}) and the
 *       nested jars (see {@link Archive});</li>
 *   <li>build an {@link InMemoryClassLoader} over the {@code classpath/} jars - the in-memory
 *       equivalent of the unnamed module;</li>
 *   <li>if there is a main module, resolve the {@code modulepath/} jars with an
 *       {@link InMemoryModuleFinder} and define a child {@link ModuleLayer} whose single loader has
 *       the class-path loader as its parent, so automatic modules can read the class path while
 *       named modules cannot - the JDK's own rule;</li>
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

        Class<?> main;
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
            main = loader.loadClass(mainClass);
            context = loader;
            // Reproduce `java -m <module>/<class>`, which invokes main without requiring its package to
            // be exported: grant this launcher access to the main package through the controller.
            Module application = main.getModule();
            if (application.isNamed()) {
                Module launcher = Launcher.class.getModule();
                String packageName = main.getPackageName();
                controller.addExports(application, packageName, launcher);
                controller.addOpens(application, packageName, launcher);
            }
        } else {
            main = classpath.loadClass(mainClass);
            context = classpath;
        }

        Thread.currentThread().setContextClassLoader(context);
        invokeMain(main, args);
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
            Throwable cause = thrownByApplication.getCause();
            switch (cause) {
                case null -> throw thrownByApplication;
                case Exception exception -> throw exception;
                case Error error -> throw error;
                default -> throw thrownByApplication;
            }
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
