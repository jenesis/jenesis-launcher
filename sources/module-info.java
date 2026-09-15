/**
 * Jenesis Launcher
 *
 * A bootstrap for executable jars produced by Jenesis. The launcher is shaded into the jar root and runs
 * the bundled application out of the one {@code jars/} store the jar keeps its dependencies in, reading
 * class and resource bytes on demand from the still-open outer jar - nothing is held in memory or extracted
 * to disk. What each path holds is named by {@code application.properties} rather than shown by where a jar
 * sits: what {@code classpath} names becomes the unnamed module of a single loader, and what
 * {@code modulepath} names is resolved into a fresh {@link java.lang.ModuleLayer} mapped to that same
 * loader, preserving full modularity. The same machinery serves a module that keeps a dependency private:
 * it declares a layer, requires this module, and asks for it by name, and the dependency is resolved into a
 * layer of its own - read from the same jar, never relocated and never unpacked.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.launcher.Launcher
 */
module build.jenesis.launcher {

    requires java.instrument;

    exports build.jenesis.launcher;
}
