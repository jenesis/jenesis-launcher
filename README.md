# Jenesis Launcher

![build](https://github.com/raphw/jenesis-launcher/actions/workflows/build.yml/badge.svg)

> ### Powered by [Jenesis](https://github.com/raphw/jenesis)
> _A modern Java build tool: Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

A bootstrap for **executable jars** produced by Jenesis. The launcher is shaded
into the jar root and run as its `Main-Class`, so `java -jar foo.jar` starts the application - while
**retaining full Java modularity**. Modular dependencies are resolved into a fresh
`java.lang.ModuleLayer`; non-modular dependencies become an in-memory class path. Nothing is exploded
into a flat jar and nothing is extracted to disk: the nested jars are read in place, in memory.

## Why not a classic "fat jar"?

The Maven-Shade approach unpacks every dependency and merges their class files into one flat jar. That
destroys exactly what Jenesis cares about: `module-info.class` files collide, `META-INF/services`
entries must be merged by hand, signatures break, and there is no way to reconstruct a module graph at
runtime. This launcher keeps each dependency as an intact **nested jar**, so module identity,
`module-info`, service files and signatures all survive untouched.

The result is faithful to what `java -p modulepath -cp classpath -m mainModule/mainClass` would have
done - reconstructed in process:

* **Modular and automatic jars** (those with a `module-info.class` or an `Automatic-Module-Name`, the
  same ones `PathPlacement.INFERRED` routes to the module path) are resolved by an in-memory
  `ModuleFinder` and defined into a child `ModuleLayer`. The boot layer is immutable, so this is the
  only supported way to add them at runtime, and the right one: they stay real named modules.
* **Non-modular jars** become the unnamed module of that same loader - the analogue of what `-cp`
  produces.
* A **single in-memory loader** hosts both the named modules and the unnamed module, exactly as one
  application loader does under `java -p modulepath -cp classpath`. So automatic modules can read the
  class path while strict named modules cannot, and a package owned by a module shadows the same
  package on the class path - the JDK's own rules.

## Executable-jar layout

The bundling step shades this launcher into the jar root and lays the application out like this:

```
foo.jar
├── META-INF/MANIFEST.MF                                  Main-Class: build.jenesis.launcher.Launcher
├── META-INF/services/
│   └── java.net.spi.URLStreamHandlerProvider             (shaded from the launcher)
├── build/jenesis/launcher/*.class                        the launcher itself (unnamed module at run time)
├── application.properties                                mainClass=..., mainModule=..., agentClass=...
├── classpath/
│   └── *.jar                                             non-modular dependencies
└── modulepath/
    └── *.jar                                             modular and automatic dependencies
```

`application.properties` is the exact file the previous `Bundle` step already wrote
(`mainClass`, when modular `mainModule`, and optionally `agentClass`), so the bundling step only has
to:

1. copy the launcher's own classes and its `META-INF/services` entry into the jar root;
2. keep writing `application.properties`, `classpath/*.jar` and `modulepath/*.jar` as before;
3. set the manifest `Main-Class` to `build.jenesis.launcher.Launcher`;
4. when `agentClass` is present, add `Launcher-Agent-Class: build.jenesis.launcher.LauncherAgent` to the
   manifest (see [Bundled Java agents](#bundled-java-agents)).

## How a launch proceeds

`build.jenesis.launcher.Launcher#main` (or `Launcher#run(Path, String[])` when embedding):

1. locates the running jar via its `CodeSource` and reads it with `Archive` (a jar file or an exploded
   directory both work);
2. reads `application.properties` and every nested jar fully into memory;
3. builds a single `InMemoryClassLoader` over `classpath/` (its unnamed module);
4. if there are `modulepath/` jars, resolves them with `InMemoryModuleFinder` and defines a child layer
   via `ModuleLayer.defineModules(...)`, mapping every module to that same loader; when a `mainModule`
   is declared, the returned `Controller` grants the launcher access to the main package - so `main` is
   invoked even when its package is not exported, exactly as `java -m module/Class` allows. This happens
   whether or not a `mainModule` is declared, so a non-modular application can still reach module-path
   code (for example an agent placed on the module path);
5. sets the thread context class loader, runs any [bundled agents](#bundled-java-agents), and invokes
   `main`.

### Bundled Java agents

The optional `agentClass` property lets an executable jar carry its own Java agents - a comma-separated
list of fully qualified agent class names, each optionally followed by `=<arguments>` (mirroring
`-javaagent:<jar>=<arguments>`; the arguments run to the end of the entry):

```
mainClass=com.example.Main
agentClass=net.bytebuddy.agent.Installer,com.example.Tracing=verbose
```

The launcher invokes each agent's `premain` in declaration order **before the main class is loaded**, so
a `ClassFileTransformer` registered in `premain` still sees the main class being defined - exactly what
`-javaagent` guarantees. As the JVM does, it prefers `premain(String, Instrumentation)` and falls back to
`premain(String)`. The agents are loaded from the application's own runtime loader, so they may live on
the class path or the module path - the launcher builds the module layer even for a non-modular
application, so a module-path agent is reachable either way.

The catch is the `Instrumentation`: `-javaagent:foo.jar` resolves a `Premain-Class` from the agent jar's
*own* class path, which never includes the nested jars, so a bundled agent cannot be passed that way. The
launcher therefore ships [`LauncherAgent`](sources/build/jenesis/launcher/LauncherAgent.java) as the one
agent the JVM does know about. Referencing it from the executable jar's manifest captures a real
`Instrumentation` that the launcher then hands to every bundled agent:

```
Launcher-Agent-Class: build.jenesis.launcher.LauncherAgent   # java -jar foo.jar
Premain-Class:        build.jenesis.launcher.LauncherAgent   # java -javaagent:foo.jar -jar foo.jar
Agent-Class:          build.jenesis.launcher.LauncherAgent   # dynamic attach
```

`Launcher-Agent-Class` is the one to add for a plain `java -jar foo.jar`: the JVM loads it before `main`
and calls its `agentmain` with the `Instrumentation`. Capabilities are read from the same manifest, so add
`Can-Redefine-Classes: true` / `Can-Retransform-Classes: true` if the bundled agents need them. Without
any of these attributes no `Instrumentation` is captured, and only agents that declare `premain(String)`
can run.

### Reading nested jars in memory

The default `ModuleFinder.of(Path...)` and `URLClassLoader` cannot address a jar nested inside another
jar, so the launcher implements its own:

* `InMemoryClassLoader` is the single loader for everything: it defines class-path classes (its unnamed
  module) and module classes straight from the in-memory bytes, serves module resources through
  `findResource(module, name)` so `Class#getResourceAsStream` works inside a module, and exposes
  class-path resources via the `jenesismem:` URL scheme so that `ClassLoader#getResources` - and
  therefore `ServiceLoader` for class-path providers - returns openable URLs.
* `InMemoryModuleFinder` builds a `ModuleDescriptor` per jar (read from `module-info.class`, or derived
  for automatic modules from `Automatic-Module-Name` / the file name, with `META-INF/services`
  providers scanned in), backed by a `MapModuleReader` that serves entries from memory.
* `MemoryUrlStreamHandlerProvider` serves the `jenesismem:` scheme. It is required because the JDK
  resolves module resources (including `Class#getResourceAsStream` for a custom layer's loader) through
  `ModuleReader#find` → `URI#toURL`, which only works if a handler is registered for the scheme. It is
  registered via `META-INF/services` when shaded onto the class path, and via the module descriptor's
  `provides` clause when the launcher runs as a module. It is inert for every other scheme.

## Limitations

* **Native libraries.** A JNI library cannot be loaded from memory. `InMemoryClassLoader` extracts a
  requested library from a class-path jar to a temp file on demand (`findLibrary`). Native libraries
  bundled *inside a modular jar* are not handled; ship those modules unbundled if needed.
* **The boot layer is immutable.** Modular dependencies necessarily form a new layer rather than
  joining the system loader. This is by design and is the faithful way to keep them modular.

## Building

Requires JDK 25+. The build is the project's own Java source - no wrapper, no plugins:

```
java build/jenesis/Project.java          # compile, package, and run the tests
java build/jenesis/Project.java stage    # stage the published artifact under target/stage
```

The tests synthesise class files and nested-jar fixtures in memory with the JDK Class-File API and
drive `Launcher#run` end to end (class-path apps, modular apps in their own layer, automatic-module
naming, in-memory resource URLs, a module reading the class path, and bundled agents whose `premain`
runs - in declaration order, with arguments - before the main class).

## Using it

This artifact is published independently (`build.jenesis:build.jenesis.launcher`) and consumed by the
Jenesis bundling step, which shades it into the executable jars it produces. To run a produced jar:

```
java -jar foo.jar [args...]
```

## License

Apache-2.0. Copyright Rafael Winterhalter.
