# What each Kotlin/Gradle file does

A plain-language tour of this repo's Gradle/build side — the root `.kts`
build scripts, the wrapper, the scaffold templates — plus how the plugin
code under `src/` fits together at runtime (the two step-by-step
walkthroughs at the end). Written for a junior programmer who knows some
programming but hasn't necessarily used Gradle or Kotlin before.
Everything about `.cls`, `.i`, `.p`, `.lock`, and `openedge-project.json`
files is left out on purpose; this doc is only about the Kotlin/Gradle
side.

For a per-file reference of the plugin code itself — what each `.kt` file
under `src/` contains — see [`../src-kt-file-guide.md`](../src-kt-file-guide.md).
This doc links to it rather than repeating it.

This repo used to be the whole `pope` monorepo — plugin, a `demo/` app,
and registry content all together. It's since been split (see README.md's
intro): this repo is just the plugin now. Real projects that *use* the
plugin live elsewhere — real, remote catalog registries like
[registry-ba](https://github.com/erudys27/registry-ba) and the packages
they reference (e.g. [calculator](https://github.com/erudys27/calculator)),
plus small throwaway fixture packages this repo carries itself for tests
(`src/functionalTest/resources/fixtures/`). Older docs in `docs/decisions/`
and `docs/research/` may still mention `demo/` or `openedge-package-manager`
— those are historical records of the monorepo era and are left as-is on
purpose, not updated; `openedge-package-manager` itself still uses a local,
not remote, registry setup and shouldn't be pointed to as a current
example.

Two definitions first:

- **Gradle** is the build tool pope is written as a plugin for. A **task**
  is one named unit of work Gradle can run, e.g. `popeInstall`. A `.kts`
  file is a Gradle build script written in the Kotlin language (Gradle
  also supports plain Groovy `.gradle` files, but this repo only uses
  Kotlin ones, hence "`.kts`" — Kotlin Script).
- **A Gradle plugin** is code that adds new tasks (and other capabilities)
  to a Gradle project. pope itself *is* a Gradle plugin — this whole repo
  builds one JAR file that other Gradle projects can apply to gain the
  `popeInstall`/`popeUninstall`/`popePropath`/`popeRegistryAdd`/`popePrune` tasks.

## Root project — building the pope plugin itself

These files, at the repo root, define and build the pope plugin. You
generally don't run these by hand — `pope`/`pope.bat` (below) does that
for you.

- **`settings.gradle.kts`** — the very first file Gradle reads. Just names
  the project (`pope`). One line.
- **`build.gradle.kts`** — the main build script for the plugin. It
  declares:
  - This project *is* a Gradle plugin (`java-gradle-plugin`), written in
    Kotlin, targeting Java 17.
  - The plugin's public identity: id `io.github.balticamadeus.pope`, entry
    class `pope.PopePlugin` (the class Gradle runs when someone applies
    the plugin).
  - Its dependencies: the `org.json` library (for reading/writing JSON)
    and `kotlin-test` (for unit tests). A separate `buildscript {}` block
    also pulls `org.json` onto the *script's own* compile-time classpath —
    needed because this file uses `org.json.JSONObject` directly in its
    own `scaffoldProject` task (see below), which is a different
    classpath than the plugin's compiled output.
  - A second test source set called `functionalTest` (see below), wired
    so `./gradlew check` runs it alongside the normal unit tests.
  - Publishing config (`maven-publish`) — see ADR-0008 — and the
    **`scaffoldProject`** task: not something the plugin itself registers
    (a not-yet-wired project has no build to run a task against yet), so
    it lives here instead, run against pope's own build with
    `-PtargetDir=<path>`. Generates or non-destructively patches a
    project's `openedge-project.json`, Gradle wrapper files (in `.pope/`
    for a fresh project, at the root for an already-set-up one — see
    `scaffold/templates/` below), and `pope-registries.properties`. This
    is what `pope-init` calls under the hood.
- **`gradle.properties`** — a few project-wide settings. Currently just
  one line enabling Kotlin's official code style.
- **`gradle/wrapper/gradle-wrapper.properties`** — pins the exact Gradle
  version so everyone building this project uses the same one, and says
  where to download it from. This is what makes `./gradlew` work without
  anyone installing Gradle by hand first.
- **`gradle/wrapper/gradle-wrapper.jar`** — a small program that reads the
  properties file above and downloads/runs the pinned Gradle version. You
  never edit this by hand.
- **`gradlew`** / **`gradlew.bat`** — the scripts you (or `pope`/`pope.bat`)
  actually run to invoke Gradle (`./gradlew` on Mac/Linux/git-bash,
  `gradlew.bat` on plain Windows cmd). They just launch the wrapper jar
  above.

## `scaffold/templates/` — what `scaffoldProject` renders

Plain text templates with `{{TOKEN}}` placeholders, filled in by the
`scaffoldProject` task above. Not compiled, not Kotlin — just data:
`settings.gradle.kts.template`, `build.gradle.kts.template` (carries a
`{{PROJECT_ROOT_BLOCK}}` token, empty for a legacy root-level layout or
`projectRoot.set(file(".."))` for the `.pope/` layout), `gradle.properties.template`,
`openedge-project.json.template`.

## `src/` — the plugin code and its tests

The full per-file reference lives in **[`../src-kt-file-guide.md`](../src-kt-file-guide.md)**:
for every `.kt` file under `src/`, the types/functions it holds and how it
connects to the rest, plus tables for the unit tests and functional
tests. Read that when you want the logic of one specific file.

What follows here is only the orientation the runtime walkthroughs below
need — how the pieces fit together, not what each file contains.

- **`PopePlugin.kt`** — the entry point. Its `apply()` runs once when a
  project applies `id("io.github.balticamadeus.pope")` and registers five
  tasks: **`popeInstall`** (resolve + install dependencies;
  `-PpopeAdd=<package>[:<versionSpec>]` adds one in the same step),
  **`popeUninstall`** (`-PpopeUninstall=<package>` — remove one
  dependency, re-resolve what's left, and clean up its `pope_packages/`/
  `pope.lock`/`buildPath` entries, same cleanup `popePrune` uses),
  **`popePropath`** (print the PROPATH from `buildPath`'s `"source"`
  entries; `-PpopeIncludeTests` / `pope propath --tests` appends `"test"`
  entries), **`popeRegistryAdd`** (append to `pope-registries.properties`),
  **`popePrune`** (`pope prune [--dry-run]` — remove `pope_packages/` and
  `buildPath` entries no longer in the resolved graph). It also defines
  `PopeExtension`, the `pope {}` block: `projectRoot`, `registryRoot`,
  `cacheDir`, and the `registries {}` container.
- **`manifest/`** — reads and writes `openedge-project.json`
  (`ManifestReader` / `ManifestWriter` / `Manifest`), infers a missing
  `package_name` from `.cls` files (`PackageNameInferrer`), and patches
  `dependencies` / `buildPath` (`DependenciesUpdater`, `BuildPathUpdater`).
- **`registry/`** — given a package name + caret range, finds the package.
  `PrefixRoutingRegistry` routes by longest matching prefix to a
  `CatalogRegistry` (a git catalog repo of reference files) or the
  fallback `LocalDirectoryRegistry`. `RegistriesPropertiesFile` is the
  CLI-editable config source.
- **`fetch/`** — `GitCli` (shells out to `git`) and `GitPackageFetcher`
  (bare-clone-plus-`git worktree` cache, shared by `CatalogRegistry` and
  direct-source deps).
- **`resolver/DependencyResolver.kt`** — walks the whole graph
  transitively, failing loudly on version conflicts, circular
  dependencies, and real-namespace collisions.
- **`lock/` + `integrity/`** — `DirectoryHash` content-hashes an installed
  package, `IntegrityChecker` compares it against `pope.lock`,
  `LockfileReader` reads the existing lock.
- **`propath/PropathGenerator.kt`** — pure function: source roots →
  absolute paths.
- **`version/SemVer.kt`** — `SemVer` (parse/compare `X.Y.Z`) and
  `CaretRange` (`^X.Y.Z` matching).

## Root `pope` / `pope.bat`, `cli/`, and `pope-init` — the command-line layer

Not Kotlin or Gradle files themselves, but worth including since they're
what you actually type:

- **`pope`** (bash) / **`pope.bat`** (Windows) — thin scripts, scaffolded
  into *each* project by `scaffoldProject`, that translate `pope install`/
  `pope propath`/`pope registry add` into the equivalent `./gradlew`
  calls. Find their target project by their own file location, so they
  work with zero global setup.
- **`cli/pope`** / **`cli/pope.bat`** — the same commands, but meant to be
  installed *once* (added to `PATH`) and reused across every project.
  Find their target project by walking upward from your current
  directory looking for `openedge-project.json` — the same way
  `git`/`npm` find their project root — which is what makes it safe to
  have exactly one copy on `PATH`.
- **`cli/install.sh`** / **`cli/install.ps1`** — one-time, idempotent
  setup that adds `cli/` to `PATH`.
- **`pope-init`** / **`pope-init.bat`** — interactive wrapper: prompts for
  registries, calls `scaffoldProject` against your current directory, and
  offers to run the `cli/install` script too.

## Step by step: `pope install ba.calculator`

Say a consumer project has `registries { create("ba") { ... } }`
configured and declares `"ba.calculator": "^1.0.2"`. Here's roughly what
happens:

1. **`pope`/`pope.bat`/`cli/pope`** forwards to
   `gradlew popeInstall "-PpopeAdd=ba.calculator"` (or with no `-PpopeAdd`
   at all, for a plain `pope install` re-resolving what's already
   declared).
2. **`gradlew`** launches the pinned Gradle version and runs the
   `popeInstall` task.
3. In **`PopePlugin.kt`**'s `popeInstall` body:
   a. Reads `openedge-project.json` via **`ManifestReader.kt`**.
   b. Builds the configured `Registry` — merging `registries{}` and
      `pope-registries.properties` entries into one
      **`PrefixRoutingRegistry.kt`** (or falling back to
      **`LocalDirectoryRegistry.kt`** if neither is configured).
   c. If `-PpopeAdd` had no explicit `:versionSpec`, looks up the package
      via the registry's `findAny` to pick whatever version exists and
      turns it into a caret range. Nothing written to disk yet.
   d. Hands the full dependency set to **`DependencyResolver.kt`**, which
      routes `ba.calculator` to the `ba` registry (via
      `PrefixRoutingRegistry` → **`CatalogRegistry.kt`**), fetches it
      through **`GitPackageFetcher.kt`**'s bare-clone-plus-worktree
      cache, then reads *its* `openedge-project.json` and resolves
      whatever it depends on too — recursively, checking version
      conflicts, circular dependencies, and namespace collisions along
      the way.
   e. Each resolved package is hashed (**`DirectoryHash.kt`**) and
      checked against `pope.lock`'s existing entry for that version
      (**`IntegrityChecker.kt`**) *before* anything is copied — a tampered
      or force-moved tag fails loudly here, first.
   f. Each resolved package's source is copied into
      `pope_packages/<installSubpath>/` — every package resolved from one
      registry shares that registry's own `pope_packages/<registryName>/`
      root (`pope_packages/registry-ba/`), and every direct-source
      dependency shares one `pope_packages/dependencies/` root, regardless
      of which registry (if any) declared it. A package's own source root
      already mirrors its real `package_name` as nested folders (a hard
      ABL/PROPATH requirement), so no further per-package subfolder is
      needed — `org/package` nesting comes for free from the package's own
      content. Because multiple packages can share one root, install only
      copies/removes that one package's own files (tracked in `pope.lock`,
      see `lockfile-format.md`), never wipes the whole folder.
   g. Only now, because resolution succeeded, does
      **`DependenciesUpdater.kt`** write the new dependency into
      `openedge-project.json`. If any earlier step failed, this write
      never happens.
   h. `pope.lock` is written from scratch: every resolved package's
      version, cache source path, and integrity hash.
   i. **`BuildPathUpdater.kt`** adds each resolved package's
      `pope_packages/.../src` path to `buildPath`, if not already there.
   j. Gradle prints a summary line: how many dependencies were resolved.

## Step by step: `pope propath`

1. **`pope`/`pope.bat`/`cli/pope`** forwards to `gradlew popePropath`
   (plain `pope propath`) or `gradlew popePropath -PpopeIncludeTests`
   (`pope propath --tests`).
2. **`gradlew`** launches Gradle, applying the plugin the same way as
   above, registering the `popePropath` task.
3. In **`PopePlugin.kt`**'s `popePropath` body:
   a. Reads `openedge-project.json` via **`ManifestReader.kt`** — this
      includes whatever `buildPath` was last written by `popeInstall`,
      split into `sourceRoots` (`type: "source"`) and `testRoots`
      (`type: "test"`).
   b. Calls **`PropathGenerator.kt`**, which turns `sourceRoots` (and, if
      `-PpopeIncludeTests` was passed, `testRoots` too, appended after)
      into absolute folder paths. No files read or written beyond the
      manifest — a pure, in-memory transformation.
   c. Gradle prints the resulting list of absolute paths, one per line —
      that's the PROPATH you'd feed to the ABL compiler/IDE.

Note that `popePropath` never re-resolves or re-copies anything — it just
reports what `buildPath` already says. If you've added a dependency to
`openedge-project.json` by hand without running `pope install`, its
source won't be in `pope_packages/` yet and it won't show up here either.
