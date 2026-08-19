# Jenesis Launcher

![build](https://github.com/raphw/jenesis-launcher/actions/workflows/build.yml/badge.svg)

> ### [Jenesis](https://jenesis.build) - a modern Java build tool
> _Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

**A bootstrap for executable jars that keeps real Java modularity.** The launcher is shaded into the jar root
and run as its `Main-Class`, so `java -jar foo.jar` starts the application - while modular dependencies are
resolved into a fresh `java.lang.ModuleLayer` and non-modular ones become the unnamed module of the same
loader. Each dependency is exploded into its own subfolder of the outer jar, and class and resource bytes are
read straight from the still-open jar on demand: nothing is merged into a flat jar, held in memory, or
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
java build/jenesis/Project.java stage    # produces the executable jar
java -jar foo.jar [args...]              # run it
java -javaagent:foo.jar=args -jar app.jar   # a bundle with no mainClass is an agent
```

## Building it

Requires a JDK 25 or newer (the module compiles at release 25; CI builds on 26). The build is the project's
own Java source - no wrapper, no plugins:

```bash
git submodule update --init --depth 1     # the pinned Jenesis build tool
java build/jenesis/Project.java           # compile, package, run the tests
java build/jenesis/Project.java stage     # stage the published artifact under target/stage
```

The build tool is tracked as a shallow submodule under `.jenesis/upstream`, pinned to the commit this project
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
and runs `java build/jenesis/Project.java`, which builds and tests in one step.

`.github/workflows/release.yml` fires on a push to `main` whose commit message starts with `[release]` -
`[release 1.2.3]` for an exact version, `[release]` alone to bump the minor of the latest `v*` tag. It stages
with sources and documentation, then hands the tree to JReleaser (`jreleaser.yml`), which signs, publishes to
Maven Central and tags `v<version>`. `project.properties` carries the POM metadata.

## License

Apache License 2.0. Copyright Rafael Winterhalter.
