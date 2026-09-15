/**
 * Jenesis Launcher
 *
 * A bootstrap for executable jars produced by Jenesis. The launcher is shaded into the jar root and runs
 * the bundled application from its {@code classpath/} and {@code modulepath/} subfolders, reading class and
 * resource bytes on demand from the still-open outer jar - nothing is held in memory or extracted to disk.
 * Non-modular dependencies become the unnamed module of a single loader; modular and automatic ones are
 * resolved into a fresh {@link java.lang.ModuleLayer} mapped to that same loader, preserving full modularity.
 * The same machinery serves a module that keeps a dependency private: it declares a layer, requires this
 * module, and asks for it by name, and the dependency is resolved into a layer of its own - read from the
 * same jar, never relocated and never unpacked.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.launcher.Launcher
 */
module build.jenesis.launcher {

    requires java.instrument;

    exports build.jenesis.launcher;
}
