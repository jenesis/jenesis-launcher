# AGENTS.md

Jenesis Launcher: the bootstrap the Jenesis build tool shades into an executable jar so that `java -jar`
rebuilds the application's module graph in process instead of merging everything into a flat jar. The build
tool consumes it as `build.jenesis:build.jenesis.launcher`; nobody depends on it directly. `README.md`
covers what it does, the tests and releasing. The user documentation is
[jenesis.build/launcher](https://jenesis.build/launcher/)
([raphw/jenesis-documentation](https://github.com/raphw/jenesis-documentation)).

## Build & test

- **JDK 25 or newer.** The build tool is the `.jenesis/upstream` git submodule (`build/jenesis` links into
  it): `git submodule update --init --depth 1` once, then `java build/jenesis/Project.java` builds and runs
  the tests; `stage` lays out the published artifact under `target/stage/`.
- CI builds under strict pinning; after changing a dependency, run `java build/jenesis/Project.java pin` and
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
  `Launcher` step in raphw/jenesis and with the documentation: `classpath/<jar>/…` and `modulepath/<jar>/…`
  subfolders, the descriptor keys (`mainClass`, `mainModule`, `classpath`, `agentClass`, `addExports`,
  `addOpens`, `addReads`, `signature.<dep>`) and the manifest attributes. A change to any of them is made
  together with the build tool and the documentation.
- Bytes are read from the still-open jar on demand; nothing is merged, held in memory, or extracted, except
  a native library that the JVM can only load from a file.

## Tests

- `tests/` is the `@jenesis.test` module, on JUnit Jupiter with AssertJ. `TestJars` synthesises class files
  and module descriptors with the Class-File API and assembles jars and exploded directories from them, so
  no fixture jar is checked in; `LauncherTest` drives `Launcher#run` end to end over those.
- A change to how the graph is assembled arrives with the test that pins the behaviour it changes, named as
  a sentence stating that behaviour.

## Releasing and the build tool

A release is a commit on `main` whose first line starts with `[release X.Y.Z]`; the release workflow stages
with sources and documentation and publishes through JReleaser. The build tool pin is moved by checking out
the new commit in `.jenesis/upstream`, building, and committing the submodule pointer; the build tool in turn
resolves this artifact as `RELEASE` until a project pins it.
