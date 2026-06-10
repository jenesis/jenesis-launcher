# Jenesis Launcher

![build](https://github.com/raphw/jenesis-launcher/actions/workflows/build.yml/badge.svg)

> ### Powered by [Jenesis](https://github.com/raphw/jenesis)
> _A modern Java build tool: Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

A bootstrap for **executable jars** produced by Jenesis. The launcher is shaded
into the jar root and run as its `Main-Class`, so `java -jar foo.jar` starts the application - while
**retaining full Java modularity**. Modular dependencies are resolved into a fresh
`java.lang.ModuleLayer`; non-modular dependencies become the unnamed module of the same loader. Each
dependency is exploded into its own subfolder of the outer jar, and the launcher reads class and resource
bytes straight from the still-open jar on demand - nothing is merged into a flat jar, held in memory, or
extracted to disk.

## Why not a classic "fat jar"?

The Maven-Shade approach unpacks every dependency and merges their class files into one **flat** jar.
That destroys exactly what Jenesis cares about: `module-info.class` files collide, `META-INF/services`
entries must be merged by hand, and there is no way to reconstruct a module graph at runtime. This
launcher also explodes each dependency, but into **its own subfolder** (`classpath/<dep>/…`,
`modulepath/<mod>/…`), so nothing is merged: each dependency keeps its own `module-info`, service files
and resources, and the module graph is reconstructed from the subfolders. Because every class is then a
direct entry of the outer jar, the launcher reads it on demand with a plain `java.util.zip.ZipFile` - no
nested-jar trickery, and the dependencies' bytes never sit in the heap.

The result is faithful to what `java -p modulepath -cp classpath -m mainModule/mainClass` would have
done - reconstructed in process:

* **Modular and automatic dependencies** (those with a `module-info.class` or an `Automatic-Module-Name`,
  the same ones `PathPlacement.INFERRED` routes to the module path) are resolved by `InMemoryModuleFinder`
  and defined into a child `ModuleLayer`. The boot layer is immutable, so this is the only supported way to
  add them at runtime, and the right one: they stay real named modules.
* **Non-modular dependencies** become the unnamed module of that same loader - the analogue of what `-cp`
  produces.
* A **single loader** hosts both the named modules and the unnamed module, exactly as one application
  loader does under `java -p modulepath -cp classpath`. So automatic modules can read the class path
  while strict named modules cannot, and a package owned by a module shadows the same package on the
  class path - the JDK's own rules.

## Executable-jar layout

The bundling step shades this launcher into the jar root and lays the application out like this:

```
foo.jar
├── META-INF/MANIFEST.MF                                  Main-Class: build.jenesis.launcher.Launcher
├── build/jenesis/launcher/*.class                        the launcher itself (unnamed module at run time)
├── application.properties                                mainClass=..., mainModule=..., agentClass=...
├── classpath/
│   └── <dependency-jar-name>/...                         a non-modular dependency, exploded
└── modulepath/
    └── <module-jar-name>/...                             a modular or automatic dependency, exploded
```

`application.properties` is the exact file the previous `Bundle` step already wrote
(`mainClass`, when modular `mainModule`, and optionally `agentClass`), so the bundling step only has
to:

1. copy the launcher's own classes into the jar root;
2. explode each dependency into `classpath/<name>/` or `modulepath/<name>/`, where `<name>` is the
   original jar file name (so automatic-module naming is unchanged);
3. set the manifest `Main-Class` to `build.jenesis.launcher.Launcher`;
4. when `agentClass` is present, add `Launcher-Agent-Class: build.jenesis.launcher.LauncherAgent` to the
   manifest (see [Bundled Java agents](#bundled-java-agents)).

## How a launch proceeds

`build.jenesis.launcher.Launcher#main` (or `Launcher#run(Path, String[])` when embedding):

1. locates the running jar via its `CodeSource` and opens it with `Archive` (a jar file or an exploded
   directory both work);
2. reads `application.properties` and indexes the entry names of each `classpath/<dep>/` and
   `modulepath/<mod>/` subfolder - bytes are read later, on demand;
3. builds a single `InMemoryClassLoader` over the `classpath/` subfolders (its unnamed module), holding
   no bytes;
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
*own* class path, which never includes the bundled dependencies, so a bundled agent cannot be passed that
way. The
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

**Agent bundles (no `mainClass`).** A bundle that declares no `mainClass` is itself a Java agent. Give it
`Premain-Class: build.jenesis.launcher.LauncherAgent` (for `-javaagent:foo.jar`) and/or
`Agent-Class: build.jenesis.launcher.LauncherAgent` (for dynamic attach), and use it on a *host*
application:

```
java -javaagent:foo.jar=args -jar your-app.jar
```

The launcher builds the bundle's own loader and runs the bundled `agentClass` agents' `premain` (or
`agentmain`, on attach) against the host's `Instrumentation` - so the agent and its dependencies stay in
the bundle's isolated loader, off the host's class path. The `=args` from the command line reach each
agent that declares no `=<arguments>` of its own. (A bundle that *does* declare a `mainClass` is an
application, and its agents run before its own `main` as above.)

**Multiple agent bundles in one JVM.** The JVM loads a `Premain-Class` by binary name only *once*, so
several bundles all naming `build.jenesis.launcher.LauncherAgent` would collide - the first jar wins and
the rest are silently ignored. For bundles that must coexist, each ships its own **uniquely named**
`Premain-Class`: a tiny generated class whose `premain`/`agentmain` just call
`Launcher.runAgents(ThatClass.class, attach, args, instrumentation)`. Because it is resolved from its own
code source, each bundle loads its own `application.properties` and dependencies into its own isolated
loader, so any number can be attached at once. The Jenesis bundler emits this trampoline (a handful of
Class-File API instructions); `LauncherAgent` is the shared default for the single-agent and application
cases.

Generating the trampoline takes only the JDK Class-File API. For a `binaryName` unique to the bundle
(which becomes the manifest `Premain-Class`/`Agent-Class`):

```java
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/** Bytes of a Premain-Class whose premain/agentmain call Launcher.runAgents(<this class>, attach, args, inst). */
static byte[] trampoline(String binaryName) {
    ClassDesc self = ClassDesc.of(binaryName);
    ClassDesc launcher = ClassDesc.of("build.jenesis.launcher.Launcher");
    ClassDesc instrumentation = ClassDesc.of("java.lang.instrument.Instrumentation");
    MethodTypeDesc agentMethod = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String, instrumentation);
    MethodTypeDesc runAgents = MethodTypeDesc.of(ConstantDescs.CD_void,
            ConstantDescs.CD_Class, ConstantDescs.CD_boolean, ConstantDescs.CD_String, instrumentation);
    return ClassFile.of().build(self, cb -> {
        cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
        cb.withMethodBody("premain", agentMethod, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                .loadConstant(self).iconst_0().aload(0).aload(1)        // runAgents(self, false, args, inst)
                .invokestatic(launcher, "runAgents", runAgents).return_());
        cb.withMethodBody("agentmain", agentMethod, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                .loadConstant(self).iconst_1().aload(0).aload(1)        // runAgents(self, true, args, inst)
                .invokestatic(launcher, "runAgents", runAgents).return_());
    });
}
```

Both methods are branch-free, so the Class-File API computes no stack-map frames and never resolves a class
hierarchy - the generator needs neither the launcher nor the agent's dependencies on its own class path,
and the produced class verifies without them (the call to `Launcher.runAgents` resolves lazily, on first
invocation, when the launcher is on the system class path).

The rest is plain jar assembly (no Class-File API): write those bytes at `binaryName`'s path, set the
manifest `Premain-Class` and `Agent-Class` to `binaryName`, and lay out `application.properties`
(`agentClass=...`), the exploded `classpath/<name>/` and `modulepath/<name>/` dependencies, and the
launcher's own classes as for any bundle. Each such bundle reads *its own* `application.properties` and
dependencies, because `runAgents` resolves them from the trampoline's code source.

### Relaxing module access

A bundled module sometimes needs reflective access a framework expects but its `module-info` does not
declare. Three optional properties grant it - the in-bundle equivalent of `--add-exports` / `--add-opens`
/ `--add-reads`, applied to the bundled modules through the layer's `Controller`:

```
addExports=some.module/some.pkg=ALL-UNNAMED
addOpens=some.module/some.pkg=other.module,yet.another
addReads=some.module=java.sql
```

Directives within a property are separated by `;` and targets within a directive by `,`; a target is a
module name or `ALL-UNNAMED`. The source must be one of the bundled modules (the `Controller` can only
break encapsulation of the modules it defined); targets may be bundled, boot, or the unnamed module.

### Emulating a signed jar

A dependency that shipped as a *signed* jar loses its signer identity when exploded: its signature files
(`META-INF/*.SF`, `*.RSA`/`*.DSA`/`*.EC`) become ordinary entries, so a class-path class would otherwise
define with a `CodeSource` that has no signers. Optional `signature.<dependency>` entries in
`application.properties` restore it - the key suffix is the exploded dependency's name (its
`classpath/<name>/` folder), the value is Base64 of the signer's PKCS#7 certificate chain:

```
mainClass=com.example.Main
signature.guava.jar=MIIF...                  # Base64 of the signer's certificate chain (PKCS#7)
```

For each such class-path dependency the launcher reconstructs a `CodeSigner` and attaches it to that
dependency's `CodeSource`, so `getProtectionDomain().getCodeSource().getCodeSigners()` and
`getCertificates()` report the original signer - the same attested reconstruction the launcher already does
for a package's manifest metadata and sealing. This records the signer the bundler attested at build time;
it is **not** a cryptographic re-verification of the bundled bytes, and it applies to class-path
dependencies (a module-path class carries no signers, as on a real module path). The Base64 value is ASCII,
so it round-trips through the ISO-8859-1 properties file. Dependencies without an entry are unaffected.

### Reading the bundle on demand

Because each dependency is exploded into a subfolder, every class and resource is a direct entry of the
outer jar, so the launcher reads each one only when first needed - no nested-jar addressing required:

* `Archive` opens the outer jar (a `java.util.zip.ZipFile`) or the exploded directory once, indexes the
  entry names, and groups them by `classpath/<name>/` and `modulepath/<name>/` into lazy `Jar` handles.
  Each handle reads an entry's bytes on demand and hands out a standard `jar:`/`file:` URL for it. For a
  multi-release dependency it serves the highest `META-INF/versions/<n>/` entry the runtime supports,
  having noted at index time which releases the dependency actually ships. In the exploded-directory
  layout a requested name is resolved only within the bundle root, so a `..` segment in a resource name
  cannot escape it onto the host filesystem; the jar layout is inherently confined, since entries are
  matched by exact name.
* `InMemoryClassLoader` is the single loader for everything. It holds no class or resource bytes - only
  the `Jar` handles and a package-to-module index - and reads (then discards) a class's bytes from the
  open jar when the VM asks for it. Resources are returned as `jar:`/`file:` URLs, so
  `ClassLoader#getResources` - and therefore `ServiceLoader` for class-path providers - works with the
  JDK's own handlers. `getResource`/`getResources` expose a bundled module's resources exactly as a
  builtin loader does: a resource in a module package only when that package is opened unconditionally
  (or the resource is a `.class` file), and a resource in no package (a top-level entry, anything under
  `META-INF/`) unconditionally - so a resource in a non-open package stays encapsulated, while everything
  a real `java -p ... -cp ...` launch would expose is found.
* `InMemoryModuleFinder` builds a `ModuleDescriptor` per module (read from `module-info.class`, or derived
  for automatic modules from `Automatic-Module-Name` / the original jar file name, with `META-INF/services`
  providers scanned in), backed by an `ArchiveModuleReader` whose `find` returns each entry's `jar:`/`file:`
  URL - so `Class#getResourceAsStream` for a class in the custom layer's loader resolves through the JDK's
  standard handlers, with no custom URL scheme.

## Limitations

The launcher reconstructs the module graph and class loading in memory, faithfully but not exhaustively.
The following are worth knowing before bundling an application.

* **Class identity is reconstructed from the bundle, not a real jar file.** A class-path class is defined
  with a `CodeSource` and a package whose location is the dependency's URL *inside* the outer jar (e.g.
  `jar:file:/…/foo.jar!/classpath/dep.jar/`), populated from that dependency's manifest - so
  `Package#getImplementationVersion`, sealed packages, and `getProtectionDomain().getCodeSource()` all
  work. But it is not a standalone jar on disk, so the "open my own jar file" idiom still fails. **JAR
  signatures are not cryptographically verified** - a dependency's signature files are exploded as ordinary
  entries; optional [`signature.<dep>` entries in `application.properties`](#emulating-a-signed-jar)
  reconstruct a class-path dependency's signer *identity* so `CodeSource#getCodeSigners`/`getCertificates`
  report it, but they attest rather than re-verify. A module class likewise carries a `CodeSource` - its module's exploded-folder URL,
  with no signers (as on a real module path) - though its packages are governed by the module system, not a
  manifest.

* **The module graph is fixed to the bundle plus the default boot modules.** Every bundled module is
  bound as a root against the boot layer; you can add `reads` / `opens` / `exports` edges for bundled
  modules with the [`addReads` / `addOpens` / `addExports`](#relaxing-module-access) properties, but there
  is no in-bundle way to pull in JDK modules that are not resolved by default (for example
  `jdk.incubator.*`, or modules with only qualified exports). A bundled module that `requires` an
  unresolved JDK module fails at startup; the fix is `java --add-modules jdk.incubator.vector -jar
  foo.jar`, which augments the boot layer that the child layer reads.

* **Read on demand, but the jar stays open.** Class and resource bytes are read from the still-open outer
  jar (or directory) when first needed and not retained, so heap use is roughly the entry-name index
  rather than the dependencies' bytes. The trade-off is open file handles for the process lifetime: the
  launcher's own `ZipFile`, plus a `JarURLConnection`-cached `JarFile` once resource URLs are opened.
  `InMemoryClassLoader` is `Closeable` (like `URLClassLoader`), so an embedder that builds and discards
  loaders can release those handles deterministically; under `java -jar` the loader stays open for the
  application's lifetime, as it must.

* **Native libraries.** A JNI library cannot be loaded from memory, so `InMemoryClassLoader` extracts a
  requested library to a temp file on demand (`findLibrary`), from a class-path jar or a bundled module
  (class path takes precedence). The temp file is removed on a normal exit (`deleteOnExit`) but leaks on
  an abrupt kill; if the same library name is bundled in more than one module, the first module by jar
  name wins.

* **The boot layer is immutable.** Modular dependencies necessarily form a new layer rather than joining
  the system loader. This is by design and is the faithful way to keep them modular; it is also why the
  module graph is fixed as described above.

Three behaviours are intentional rather than limitations: a package owned by a module **shadows** the same
package on the class path (the JDK's own rule); a resource inside a **non-open module package** is not on
the flat `getResource`/`getResources` API - `contextClassLoader.getResourceAsStream("some/module/internal.txt")`
finds it only if that package is opened - exactly as a real `java -p ... -cp ...` launch encapsulates it
(everything that launch *would* expose, including `META-INF/` and automatic-module resources, the launcher
now serves); and bundled agents need a manifest attribute to obtain an `Instrumentation` (see
[Bundled Java agents](#bundled-java-agents)).

## Building

Requires JDK 25+. The build is the project's own Java source - no wrapper, no plugins:

```
java build/jenesis/Project.java          # compile, package, and run the tests
java build/jenesis/Project.java stage    # stage the published artifact under target/stage
```

The tests synthesise class files and exploded-bundle fixtures with the JDK Class-File API and drive
`Launcher#run` end to end (class-path and modular apps, automatic-module naming, resources via
`jar:`/`file:` URLs from both a jar and an exploded directory, resource names in the directory layout
confined to the bundle root, a bundle path with spaces, module resources on the flat resource API
honoring JPMS encapsulation (automatic-module and `META-INF/` resources served, a non-open package's
resource hidden), a module reading the class path, split-package shadowing, native-library extraction,
multi-release class selection,
package metadata and sealing from the manifest, signer identity reconstructed from a `signature.<dep>` property,
a module class's `CodeSource` location, `addExports`/`addOpens`/`addReads` grants, bundled
agents whose `premain` runs - in declaration order, with arguments - before the main class, and an agent
bundle with no main started through `runAgents`).

## Using it

This artifact is published independently (`build.jenesis:build.jenesis.launcher`) and consumed by the
Jenesis bundling step, which shades it into the executable jars it produces. To run a produced jar:

```
java -jar foo.jar [args...]
```

A bundle with no `mainClass` is instead a self-contained Java agent (see
[Agent bundles](#bundled-java-agents)):

```
java -javaagent:foo.jar=args -jar your-app.jar
```

## License

Apache-2.0. Copyright Rafael Winterhalter.
