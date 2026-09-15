# Jenesis Launcher

[![release](https://img.shields.io/github/v/release/jenesis/jenesis-launcher?label=release)](https://github.com/jenesis/jenesis-launcher/releases/latest)
![build](https://github.com/jenesis/jenesis-launcher/actions/workflows/build.yml/badge.svg)

> ### [Jenesis](https://jenesis.build) - a modern Java build tool
> _Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

**A bootstrap for executable jars that keeps real Java modularity.** The launcher is shaded into the jar root
and run as its `Main-Class`, so `java -jar foo.jar` starts the application - while modular dependencies are
resolved into a fresh `java.lang.ModuleLayer` and non-modular ones become the unnamed module of the same
loader. Each dependency is exploded into its own subfolder of the outer jar, and class and resource bytes are
read straight from the still-open jar on demand: nothing is merged into a flat jar or held in memory, and
only what the JVM can read no other way - a native library, and the jars of a bundled module layer - is ever
extracted to disk.

📖 **The user documentation lives at [jenesis.build/launcher](https://jenesis.build/launcher/).** How a
launch proceeds, the jar layout, bundled agents, module-access grants, troubleshooting, and the full
descriptor reference are all there. What follows is for people working *on* this repository.

## Getting it

You do not normally depend on this artifact yourself. It is published as
`build.jenesis:build.jenesis.launcher` and consumed by the Jenesis build tool, which shades it into the jars
it produces when a project asks for one:

```properties
# build.jenesis/packaging.properties
launcher=true
```

```bash
java build/jenesis/Make.java          # the jar lands under target/build/…/launcher/bundle/output/launcher/
java -jar foo.jar [args...]              # run it
java -javaagent:foo.jar=args -jar app.jar   # a hand-assembled jar with no mainClass is an agent
```

The build tool writes `mainClass`, `mainModule` and `classpath` into the jar's `application.properties`; the
agent, module-access and signer keys the launcher also understands are for jars assembled by other means.

## Bundled module layers

A project can isolate a dependency and its closure in a run-time `ModuleLayer` of its own, so two versions of
one library coexist without relocating a package. Those jars travel under `layers/<name>/` and are stored
**whole** rather than exploded: a layer is read back as a module path, where an automatic module takes its
name from its jar file name and a signed jar is only verifiable intact.

Before `main`, the launcher unpacks each one and announces it as `jenesis.layer.<name>`:

```
foo.jar
|- application.properties
|- build/jenesis/launcher/...
|- modulepath/<mod>/...          each dependency, exploded
'- layers/render/isolated.jar    each layer's jars, whole
```

```java
ModuleFinder finder = ModuleFinder.of(Path.of(System.getProperty("jenesis.layer.render")));
```

That is the same property a filesystem deployment is launched with, so the application's own layer code is
identical wherever it runs and needs nothing from this launcher. A module path can only be read from files -
`ModuleFinder.of` takes paths, not streams - which is why a layer inside the jar is written to a temporary
directory, for the same reason a native library is. An exploded bundle already is a directory, so its layers
are read where they lie and nothing is copied, and a layer an explicit `-D` already points at is left alone,
which is what lets one be patched in place.

## Building it

Requires a JDK 25 or newer (the module compiles at release 25; CI builds on 26). The build is the project's
own Java source - no wrapper, no plugins:

```bash
git submodule update --init --depth 1     # the pinned Jenesis build tool
java build/jenesis/Make.java           # compile, package, run the tests
java build/jenesis/Make.java stage     # stage the published artifact under target/stage
```

The build tool is tracked as a shallow submodule under `build/.upstream`, pinned to the commit this project
builds against, so a fresh clone plus that one command is the whole setup.

## Tests

The suite is the reason this project can be trusted with a class loader. It synthesises class files and
exploded-bundle fixtures with the JDK Class-File API and drives `Launcher#run` end to end, covering:

- **Layout and loading** - class-path and modular applications, automatic-module naming, declared class-path
  order, a rejected duplicate module name, split-package shadowing, and a strict module's non-exported main.
- **Resources** - `jar:` and `file:` URLs from both a jar and an exploded directory, names confined to the
  bundle root, a bundle path with spaces, `getResources` across a module and the class path, and module
  resources honouring encapsulation (a non-open package's resource stays hidden).
- **Faithfulness to the JDK** - multi-release class and resource selection, native-library extraction, package
  metadata and sealing from the manifest, a sealing violation across class-path jars, a module class's
  `CodeSource` location, and signer identity reconstructed from a `signature.<dep>` property.
- **Agents and grants** - `premain` in declaration order with arguments, `agentmain` on attach, an agent
  bundle with no main started through `runAgents`, and `addExports` / `addOpens` / `addReads`.

A change to how the graph is assembled should arrive with the test that pins the behaviour it changes.

## Continuous integration and releases

`.github/workflows/build.yml` runs on every push and pull request: it checks out the submodule, sets up a JDK,
and runs `java build/jenesis/Make.java`, which builds and tests in one step.

`.github/workflows/release.yml` is dispatched by hand from the Actions tab, so any commit is releasable: the
optional `sha` input names the commit (default: the head it runs on) and the optional `tag` input names the tag
(`v1.2.3` or `1.2.3`; left empty, the minor of the latest `v*` tag is bumped). It stages
with sources and documentation, then hands the tree to JReleaser (`jreleaser.yml`), which signs, publishes to
Maven Central and tags `v<version>`. `project.properties` carries the POM metadata.

## License

Apache License 2.0 - see [LICENSE](LICENSE). Copyright Rafael Winterhalter.

The license covers the launcher itself, and travels with the code: a jar that shades the launcher in
redistributes Apache-licensed bytes under these terms. It does not extend to the application that jar
starts, or to the dependencies the launcher resolves, which keep the licenses their own authors chose.
