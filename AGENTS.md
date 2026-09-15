# AGENTS.md

Jenesis Launcher: the bootstrap the Jenesis build tool shades into an executable jar so that `java -jar`
rebuilds the application's module graph in process instead of merging everything into a flat jar. The build
tool consumes it as `build.jenesis:build.jenesis.launcher`; nobody depends on it directly. `README.md`
covers what it does, the tests and releasing. The user documentation is
[jenesis.build/launcher](https://jenesis.build/launcher/)
([jenesis/jenesis-documentation](https://github.com/jenesis/jenesis-documentation)).

## Build & test

- **JDK 25 or newer.** The build tool is the `build/.upstream` git submodule (`build/jenesis` links into
  it): `git submodule update --init --depth 1` once, then `java build/jenesis/Make.java` builds and runs
  the tests; `stage` lays out the published artifact under `target/stage/`.
- CI builds under strict pinning; after changing a dependency, run `java build/jenesis/Make.java pin` and
  commit the rewritten pins.

## How the code is written

- Six classes under `sources/build/jenesis/launcher/`, Java 25 with `import module java.base;`, no
  dependencies: the jar is shaded into other people's applications, so it must stay small and must never
  pull a library into their class path.
- The launcher is faithful to `java -p modulepath -cp classpath -m module/main`: one class loader hosts the
  named modules of a child `ModuleLayer` and the unnamed module of the class path, and the JDK's own rules
  (an automatic module reads the class path, a strict module does not; a module's package shadows the class
  path) are reproduced, not improved on. A behaviour the JDK does not have is not added here.
- The jar layout and the `application.properties` descriptor are the contract with the build tool's
  `Launcher` step in jenesis/jenesis and with the documentation: one `jars/<jar>/…` store, the descriptor
  keys (`mainClass`, `mainModule`, `classpath`, `modulepath`, `agentClass`, `addExports`, `addOpens`,
  `addReads`, `signature.<dep>`, `layer.<declaring module>.<name>`) and the manifest attributes.
  A change to any of them is made together with the build tool and the documentation.
- Every path is spelled out. A jar is on the class path, the module path or in a layer because the
  descriptor names it there, never because of the folder it sits in, and a name the store does not hold is
  refused rather than skipped. One jar may be named by several paths, which is what lets a layer and the
  application share a dependency without a second copy.
- A module layer is the same graph again under a name of its own. Its dependencies are bundled among the
  application's in the one store, so a jar a layer and the application both need is stored once and
  simply loaded twice, and two versions stand side by side because a bundled dependency is named after the
  jar it came from. `layer.<declaring module>.<name>` names what each holds. The declaring module is part of
  the key because a layer may itself hold a module that declares one, so nesting is unbounded and two
  unrelated libraries may each call theirs the same thing; the runtime builds the same key from the calling
  module, so nothing extra travels. What keeps a layer's modules off the application's module path is that
  `modulepath` does not name them - two versions of one module are the point of a layer, and one
  configuration cannot hold both.
  `Launcher.layer`/`Launcher.load` define it on demand, as a child of the caller's layer, so every module
  the layer does not itself hold - the API module it shares with the caller above all - resolves from the
  caller and is the same class on both sides. Which module calls decides whose layer a name means, so two
  modules may each declare `render`. `load` adds the service dependence to this module rather than asking
  the caller for a `uses` clause, because `ServiceLoader` checks `uses` against the caller and offers no
  overload that takes one.
- Bytes are read from the still-open jar on demand; nothing is merged, held in memory, or extracted, except
  a native library that the JVM can only load from a file.

## Tests

- `tests/` is the `@jenesis.test` module, on JUnit Jupiter with AssertJ. `TestJars` synthesises class files
  and module descriptors with the Class-File API and assembles jars and exploded directories from them, so
  no fixture jar is checked in; `LauncherTest` drives `Launcher#run` end to end over those.
- A change to how the graph is assembled arrives with the test that pins the behaviour it changes, named as
  a sentence stating that behaviour.

## Releasing and the build tool

A release is a manual run of the release workflow from the Actions tab, so any commit is releasable: its
optional `sha` input names the commit (default: the head it runs on) and its optional `tag` input names the tag
(`vX.Y.Z`; default: the next minor of the latest tag). It stages with sources and documentation and publishes
through JReleaser. The build tool pin is moved by checking out
the new commit in `build/.upstream`, building, and committing the submodule pointer; the build tool in turn
resolves this artifact as `RELEASE` until a project pins it.
