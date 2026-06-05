/**
 * Jenesis Launcher
 *
 * A bootstrap for executable jars produced by Jenesis. The launcher is shaded into
 * the jar root and runs the bundled application from nested {@code classpath/} and {@code modulepath/}
 * jars, entirely in memory: non-modular jars become an in-memory class loader, modular and automatic
 * jars are resolved into a fresh {@link java.lang.ModuleLayer}, preserving full modularity without
 * exploding or extracting anything to disk.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.launcher.Launcher
 */
module build.jenesis.launcher {

    exports build.jenesis.launcher;

    provides java.net.spi.URLStreamHandlerProvider
            with build.jenesis.launcher.MemoryUrlStreamHandlerProvider;
}
