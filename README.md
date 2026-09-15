# Jenesis Launcher

[![release](https://img.shields.io/github/v/release/jenesis/jenesis-launcher?label=release)](https://github.com/jenesis/jenesis-launcher/releases/latest)
![build](https://github.com/jenesis/jenesis-launcher/actions/workflows/build.yml/badge.svg)

> ### [Jenesis](https://jenesis.build) - a modern Java build tool
> _Java-native config, plugin-free, with `module-info.java` treated as a feature, not an afterthought._

**A bootstrap for executable jars that keeps real Java modularity.** The launcher is shaded into the jar root
and run as its `Main-Class`, so `java -jar foo.jar` starts the application - while modular dependencies are
resolved into a fresh `java.lang.ModuleLayer` and non-modular ones become the unnamed module of the same
loader. Each dependency is exploded into its own subfolder of the jar's one `jars/` store - what a jar is
for the descriptor names, rather than where it sits - and class and resource bytes are read straight from the
still-open jar on demand: nothing is merged into a flat jar or held in memory, and only native libraries are
ever extracted to disk.

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

The build tool writes `mainClass`, `mainModule`, `classpath` and `modulepath` into the jar's
`application.properties`, naming every jar it stored; the agent, module-access and signer keys the launcher
also understands are for jars assembled by other means.

## Module layers

A module can keep a dependency private - two versions of one library in one JVM, with no package relocated.
It declares the layer, requires this module, and asks for it by name:

```java
module my.library {
    requires build.jenesis.launcher;
    requires my.library.spi;          // the API module, shared with the layer
}

Renderer r = Launcher.load("render", Renderer.class).findFirst().orElseThrow();
```

The layer's dependencies are bundled among the application's in the same `jars/` store, and
`layer.modulepath.my.library.render=<jar>,<jar>` in `application.properties` says which are its, with a
`layer.classpath.` counterpart for the jars that carry no module identity. A layer splits the two paths
exactly as the application does, because a library worth isolating usually drags a long tail of jars that
were never modularized: what is named is resolved, the rest is the unnamed module of the layer's own
loader, and the layer's automatic modules read it as they would on a real `-cp`. So a jar the layer and
the application both need is stored **once** and simply loaded twice, and two versions stand side by side
because each is named after the jar it came from. The declaring module is part of the key because a layer
may itself hold a module that declares one - nesting is unbounded - and the runtime builds the same key
from the calling module, so nothing extra has to travel. What keeps a layer's modules off the application's
module path is that `modulepath` does not name them: every path is spelled out, so nothing is included by
sitting somewhere. They are read from the still-open jar by a second `InMemoryClassLoader`: nothing is
relocated and nothing is unpacked.

The layer is a child of the caller's, so every module it does not itself hold resolves from the caller - the
API module above all, which is therefore the *same* class on both sides, and the call across the boundary is
an ordinary interface call. The calling module needs no `uses` clause; `load` adds the service dependence to
this module, which `ServiceLoader` otherwise refuses because it checks `uses` against the caller and offers
no overload that takes one. Which module calls decides whose layer a name means, so two modules may each
declare `render` without colliding.

Outside a bundle - a deployment that unpacked its dependencies - `jenesis.layer.modulepath.<module>.<name>`
and `jenesis.layer.classpath.<module>.<name>` name the layer's two paths instead, jar by jar. A layer on
disk is read from those files the way `java -p … -cp …` reads any module graph; the in-memory reading above
is only for the case that has no files to name. The same code runs either way.

Nesting needs nothing further: `Launcher.layer` parents a layer on its *caller's*, so a module sitting
inside one layer that asks for another gets a child of the first, and the API module it shares resolves
from there rather than from the application.

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
