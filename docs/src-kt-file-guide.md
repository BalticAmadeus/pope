# `src/` Kotlin file guide

A file-by-file reference for everything under `pope/src/`, written for
someone new to the codebase. For each file: what it is, the important
types/functions inside it, and how it connects to the rest.

If you want the runtime story ("what happens when I type `pope install`")
rather than a per-file list, read `docs/spec/kotlin-gradle-files.md`'s
walkthrough sections instead. This doc is the reference; that one is the tour.

## The big picture

`pope` is a Gradle plugin. One project builds one JAR. Another ABL project
applies that JAR and gains four Gradle tasks: `popeInstall`, `popePropath`,
`popePrune`, `popeRegistryAdd`.

The code is split into small single-purpose packages under
`src/main/kotlin/pope/`:

| Package | Responsibility |
|---|---|
| `pope` (root) | The plugin entry point and the four task definitions |
| `manifest/` | Read and write a project's `openedge-project.json` |
| `registry/` | Given a package name + version range, find the package |
| `fetch/` | Actually pull a package's files down with `git` |
| `resolver/` | Walk the whole dependency graph, transitively |
| `lock/` + `integrity/` | Write `pope.lock` and detect tampered packages |
| `propath/` | Turn source roots into an ABL PROPATH |
| `version/` | Parse `X.Y.Z` and match `^X.Y.Z` ranges |

Data flows roughly left to right: `manifest` tells you what is wanted,
`registry` + `fetch` + `resolver` work out and retrieve what that means,
`lock`/`integrity` record it, `propath` reports it.

---

## `src/main/kotlin/pope/` — the plugin

### `PopePlugin.kt`

The entry point. Everything starts here.

- **`class PopePlugin : Plugin<Project>`** — Gradle instantiates this and
  calls `apply(project)` once when a build script does
  `id("io.github.balticamadeus.pope")`. `apply()` registers the four tasks and
  creates the `pope { }` configuration block.
- **`abstract class PopeExtension`** — the `pope { }` block you can put in a
  consumer's `build.gradle.kts`. Properties:
  - `projectRoot` — where the ABL project actually lives. Defaults to the
    directory holding `build.gradle.kts`; set to `file("..")` when Gradle's
    files sit in a `.pope/` subfolder.
  - `registryRoot` — root folder for the old single-folder local registry
    (the fallback registry).
  - `cacheDir` — where fetched packages are cached. Defaults to
    `~/.pope/cache`. Override with `-PpopeCacheDir=...`.
  - `registries { }` — a named container of `GitRegistrySpec` entries.
- **`abstract class GitRegistrySpec`** — one `registries { }` entry. `name`
  is just a DSL label; `prefix` (e.g. `"ba."`) is the real routing key,
  `catalogUrl` points at the catalog git repo, `catalogRef` is the branch
  (default `"main"`).
- **The four `project.tasks.register(...)` blocks** — each defines one
  task's `group`, `description`, and `doLast { }` body (the code that runs
  when you invoke the task):
  - **`popeInstall`** — reads the manifest, builds the `Registry`, resolves
    the full graph via `DependencyResolver`, integrity-checks each package
    against `pope.lock` *before* copying anything, copies each package into
    `pope_packages/<subpath>/src`, then writes `pope.lock`, updates
    `dependencies` (only if `-PpopeAdd` was used), and updates `buildPath`.
    Order matters: nothing touches disk until resolution has fully
    succeeded.
  - **`popePropath`** — reads the manifest, calls `PropathGenerator`,
    prints the resulting absolute paths. Read-only.
  - **`popePrune`** — re-resolves the graph the same way `popeInstall`
    does, then deletes any `pope_packages/` folder and `buildPath` entry
    that is no longer part of that graph. `-PpopeDryRun` reports without
    deleting.
  - **`popeRegistryAdd`** — appends one entry to
    `pope-registries.properties` via `RegistriesPropertiesFile.add`.
- **Private helper functions at the bottom of the file:**
  - `buildRegistry(extension)` — merges `registries { }` (from the build
    script) and `pope-registries.properties` (from the CLI) into a single
    `PrefixRoutingRegistry`. A prefix declared twice in either source is an
    error. Falls back to `LocalDirectoryRegistry` only when both sources
    are empty.
  - `findStalePopePackagesDirs(...)` / `removeNowEmptyAncestors(...)` —
    used by `popePrune` to find `src` folders under `pope_packages/` that
    are not in the expected set, and to clean up emptied-out parent
    folders afterward.
  - `resolveAddSpec(addSpec, registry)` — parses `-PpopeAdd=name[:range]`.
    With no `:range`, it calls `registry.findAny(name)` to discover a
    version and pins it as `^version`.

### `manifest/Manifest.kt`

Pure data. No logic.

- **`sealed interface DependencySpec`** — a dependency is one of:
  - `DependencySpec.Registry(versionSpec)` — a caret-range string like
    `"^1.0.0"`; the package name must be fully qualified (`"ba.greeter"`)
    so a registry prefix can route it.
  - `DependencySpec.DirectSource(repoUrl, ref)` — an inline
    `{ repoUrl, ref }` object; fetched straight from git, no registry
    involved.
- **`data class Manifest`** — the parsed form of `openedge-project.json`:
  `name`, `version`, `packageName`, `dependencies` (map of name →
  `DependencySpec`), `sourceRoots` (`buildPath` entries of type
  `"source"`; the first also acts as the package root), `testRoots`
  (`buildPath` entries of type `"test"`; only ever used for *this*
  project's own PROPATH, never a dependency's).

### `manifest/ManifestReader.kt`

- **`object ManifestReader`**, one public `read(file): Manifest`.
- Parses `buildPath` into `sourceRoots` / `testRoots`.
- If `package_name` is missing from the JSON, calls
  `inferAndPersistPackageName(...)` which uses `PackageNameInferrer` to
  derive it from `.cls` files and writes it back to disk, so inference
  runs at most once.
- `parseDependencySpec(...)` turns each `dependencies` value into a
  `DependencySpec.Registry` (string value) or `DependencySpec.DirectSource`
  (object value), throwing a clear error on anything else.

### `manifest/ManifestWriter.kt`

- **`object ManifestWriter`**, one public `write(file, json)`.
- Exists purely for formatting. `org.json.JSONObject` is `HashMap`-backed
  and will not serialize keys in a stable order, so this writes a fixed
  key order (`name`, `version`, `oeversion`, `package_name`,
  `dependencies`, `buildPath`, then anything else) with 2-space indent.
- Every pope code path that writes the manifest goes through here.

### `manifest/PackageNameInferrer.kt`

- **`object PackageNameInferrer`**, one public `infer(sourceDir): String`.
- Walks a source folder, reads every `.cls` file, extracts the namespace
  from its `class <namespace>.<Name>:` declaration (via the
  `classDeclaration` regex).
- If all files agree on one namespace, returns it. Zero matches or
  disagreement is a loud `IllegalStateException` (ADR-0002: fail, do not
  guess).
- Used by `ManifestReader` (auto-infer) and by the `scaffoldProject` task.

### `manifest/BuildPathUpdater.kt`

- **`object BuildPathUpdater`**, two public functions:
  - `ensureSourceEntries(manifestFile, paths)` — adds each path as a
    `{ type: "source", path: ... }` entry to `buildPath` if not already
    present. Additive only; never removes or reorders, so hand edits
    survive. Called at the end of `popeInstall`.
  - `pruneStalePopePackagesEntries(manifestFile, expectedPaths, dryRun)` —
    the opposite, for `popePrune`. Removes only `"source"` entries whose
    path starts with `"pope_packages/"` and is not in `expectedPaths`.
    Returns the removed paths; `dryRun` computes without writing.

### `manifest/DependenciesUpdater.kt`

- **`object DependenciesUpdater`**, one public
  `addDependency(manifestFile, packageName, versionSpec)`.
- Adds/overwrites one entry in the manifest's `dependencies` map on disk.
  This is what `-PpopeAdd=...` uses instead of a hand edit. Only called
  after resolution succeeds.

### `propath/PropathGenerator.kt`

- **`object PropathGenerator`**, one pure function
  `generate(projectDir, manifest, includeTests): List<String>`.
- Takes `manifest.sourceRoots` (plus `testRoots` if `includeTests`),
  resolves each against `projectDir`, returns the absolute paths. That
  list is the PROPATH. No I/O, no side effects.

### `registry/Registry.kt`

- **`interface Registry`** — the contract every registry implements:
  - `resolve(packageName, versionSpec): ResolvedPackage` — find the best
    version satisfying the range, fetch it.
  - `findAny(packageName): ResolvedPackage?` — find the highest available
    version, ignoring any range. Used when the user did not specify one.
- **`data class ResolvedPackage`** — the result: `packageName`, `version`,
  `sourceDir` (the folder to copy onto PROPATH), `projectDir` (the
  package's own root, where *its* `openedge-project.json` lives, needed
  for transitive resolution), and `installSubpath` (a cosmetic hint for
  where under `pope_packages/` this should nest, e.g. `"ba/calculator"`;
  `null` means use the package name directly).

### `registry/LocalDirectoryRegistry.kt`

- **`class LocalDirectoryRegistry(root)`** — the original v1 registry. One
  subfolder per package under `root`, each with its own
  `openedge-project.json`.
- Only used as the fallback when no other registry is configured.
- `findAny` lists candidate folders, reads each manifest, and uses
  `PackageMatcher.selectUnique` to pick the one folder whose
  `package_name` matches. `resolve` then also checks the version against
  the caret range.

### `registry/CatalogRegistry.kt`

- **`class CatalogRegistry(registryName, prefix, catalogUrl, catalogRef, cacheDir)`**
  — the real remote registry.
- The catalog is a small git repo that holds **no package content**, only
  reference files at `packages/<local_name>/<version>.json`, each pointing
  at a real package repo + tag (`{ repoUrl, version, ref }`).
- `local_name` = the package name with this registry's `prefix` stripped.
  It is only a lookup key.
- `ensureCatalogCloned()` clones the catalog (or fetches + hard-resets it
  if already cloned) into `cacheDir/_catalog`.
- `findAllReferences(localName)` reads every version file in that
  package's catalog folder.
- `resolve` picks the highest version satisfying the caret range;
  `findAny` picks the highest overall. Only the one chosen version is ever
  fetched (via `GitPackageFetcher`).
- `fetchAndBuild` sets `installSubpath` to `"<prefix>/<localName>"` so the
  install layout is nested by registry.

### `registry/PrefixRoutingRegistry.kt`

- **`class PrefixRoutingRegistry(delegatesByPrefix)`** — a `Registry` that
  owns no packages itself. It routes each package name to the delegate
  registry whose prefix matches, longest prefix wins.
- No matching prefix is a loud error, never a silent fallback.
- This is the registry `popeInstall` normally uses.

### `registry/PackageMatcher.kt`

- **`object PackageMatcher`**, one generic function `selectUnique(...)`.
- Given a list of `(location, Manifest)` candidates and a target
  `packageName`, returns the single candidate whose manifest declares that
  name. More than one match is a loud error (`package_name` must be
  unique). Zero matches returns `null`.
- Shared helper; currently used by `LocalDirectoryRegistry`.

### `registry/RegistriesPropertiesFile.kt`

- **`data class RegistryFileEntry`** — `name`, `prefix`, `catalogUrl`,
  `catalogRef?`.
- **`object RegistriesPropertiesFile`** — reads and appends
  `pope-registries.properties`, the CLI-editable alternative to the
  `registries { }` DSL. One property per field, namespaced by registry
  name:
  ```
  ba.prefix=ba.
  ba.catalogUrl=https://github.com/erudys27/registry-ba.git
  ```
  - `read(file)` — parses all entries, sorted by name, failing loudly on a
    half-declared entry.
  - `add(file, name, prefix, catalogUrl)` — appends only, never rewrites
    existing lines; refuses a duplicate name or prefix. Used by
    `popeRegistryAdd`.

### `fetch/GitCli.kt`

- **`object GitCli`**, one function `run(workingDir, vararg args): String`.
- A thin wrapper around shelling out to the system `git` executable via
  `ProcessBuilder`. Returns stdout on success.
- **`class GitCommandFailedException`** — thrown on a non-zero exit,
  carrying the command and stderr. A missing `git` binary throws a
  separate, friendlier `IllegalStateException`.

### `fetch/GitPackageFetcher.kt`

- **`object GitPackageFetcher`**, one public
  `fetch(packageName, repoUrl, ref, destDir): ResolvedPackage`.
- Shared by `CatalogRegistry` and direct-source dependencies; only how
  `repoUrl`/`ref` are discovered differs.
- Cache layout inside `destDir`:
  - `_bare.git/` — one bare clone per package (git history only, no
    working files).
  - `<sanitized-ref>/` — one `git worktree` checkout per ref actually
    used.
- Logic: `ensureBareRepo` clones once; `ensureWorktree` reuses an existing
  correct checkout, does an incremental `git fetch` only for a genuinely
  new ref, and drops/re-adds a stale or half-written worktree. Never a
  full re-clone.
- After checkout it reads the package's own `openedge-project.json`, takes
  the first `buildPath` source entry as the package root, and returns a
  `ResolvedPackage`.

### `resolver/DependencyResolver.kt`

- **`object DependencyResolver`**, one public
  `resolveAll(rootDependencies, registry, directSourceCacheDir): Map<String, ResolvedPackage>`.
- Walks the **entire** dependency graph. For every package it resolves, it
  reads that package's own `openedge-project.json` and resolves its
  dependencies too, recursively, until the graph is flat.
- `resolveOne(...)` is the recursive worker. Key rules it enforces:
  - **Circular dependency** (key already on the current path) is a loud
    error.
  - **One resolution per key.** If a key is resolved again with an
    incompatible requirement, `checkNoConflict(...)` throws: a version
    range that does not match the already-resolved version, a
    direct-source spec with a different `repoUrl`/`ref`, or a
    registry-vs-direct-source mismatch.
  - `DirectSource` deps are keyed by their bare name, never an inherited
    registry prefix (the class doc explains why: two differently-routed
    parents sharing one repo would otherwise get two keys).
- After the whole graph is known, `checkNoNamespaceCollision(...)` groups
  resolved packages by their real `package_name`. Two different keys that
  resolve to the same real ABL namespace would silently shadow each other
  on PROPATH, so this fails loudly instead. (This is the
  `PROPATH namespace collision` check referenced in project memory.)

### `lock/LockfileReader.kt`

- **`data class LockedPackage`** — `version`, `integrity`.
- **`object LockfileReader`**, one function `read(file): Map<String, LockedPackage>`.
- Reads the existing `pope.lock`'s `resolved` object, keyed by package
  name. Missing file returns an empty map.

### `lock/IntegrityChecker.kt`

- **`object IntegrityChecker`**, one function
  `verify(packageName, version, freshIntegrity, existingLock)`.
- If `pope.lock` already has this package at this exact version and the
  freshly computed hash differs, it throws. This catches a registry
  serving different content for an already-locked version, e.g. a git tag
  force-moved. Called by `popeInstall` *before* anything is copied into
  `pope_packages/`.

### `integrity/DirectoryHash.kt`

- **`object DirectoryHash`**, one function `hash(dir): String`.
- Content hash of a resolved package's source tree, for `pope.lock`'s
  `integrity` field. Modeled on Go's `dirhash.Hash1`: SHA-256 each file,
  build a manifest of `"<sha256>  <relative-path>"` lines sorted by path,
  then SHA-256 that manifest. Independent of walk order and OS path
  separators; sensitive to renames. Returns `"sha256:<hex>"`.

### `version/SemVer.kt`

Two small related pieces in one file.

- **`data class SemVer(major, minor, patch)`** — parses and compares plain
  `X.Y.Z` strings (`parse` rejects anything else). `Comparable`, so
  `maxByOrNull` works directly.
- **`object CaretRange`** — npm-style caret ranges, the only range syntax
  v1 supports:
  - `^1.2.3` → `>=1.2.3 <2.0.0`
  - `^0.2.3` → `>=0.2.3 <0.3.0` (stricter once major is 0)
  - `^0.0.3` → `>=0.0.3 <0.0.4` (stricter still)
  - `satisfies(range, version): Boolean`.

---

## `src/test/kotlin/pope/` — unit tests

One test file per main file, in the same package layout. Each tests its
counterpart in isolation, using real temp directories and real local git
repos where needed, with no Gradle build involved.

| Test file | Exercises |
|---|---|
| `version/CaretRangeTest.kt` | `SemVer` parsing/compare and `CaretRange.satisfies` edge cases (the 0.x tightening rules) |
| `manifest/ManifestReaderTest.kt` | Parsing `openedge-project.json`, `buildPath` splitting, `package_name` auto-infer and write-back, dependency-shape errors |
| `manifest/ManifestWriterTest.kt` | Fixed key order and indentation of written manifests |
| `manifest/PackageNameInferrerTest.kt` | Namespace extraction from `.cls` files; failure on disagreement or no matches |
| `manifest/BuildPathUpdaterTest.kt` | Additive `ensureSourceEntries`; selective `pruneStalePopePackagesEntries` and its dry-run |
| `manifest/DependenciesUpdaterTest.kt` | Adding a `dependencies` entry without disturbing the rest of the file |
| `registry/CatalogRegistryTest.kt` | Catalog clone, version-file discovery, best-match vs `findAny`, `installSubpath`, error messages |
| `registry/LocalDirectoryRegistryTest.kt` | Folder-per-package discovery, version-range check, fallback behavior |
| `registry/PrefixRoutingRegistryTest.kt` | Longest-prefix routing; loud error on no match |
| `registry/PackageMatcherTest.kt` | Unique match, `null` on none, loud error on multiple |
| `registry/RegistriesPropertiesFileTest.kt` | Reading entries, append-only `add`, duplicate name/prefix refusal |
| `fetch/GitCliTest.kt` | stdout capture, non-zero exit throwing, missing-`git` message |
| `fetch/GitPackageFetcherTest.kt` | Bare-clone + worktree cache reuse, incremental fetch, stale worktree recovery |
| `integrity/DirectoryHashTest.kt` | Stable hash regardless of walk order; changes on edit/rename |
| `lock/LockfileReaderTest.kt` | Parsing `resolved` entries; empty map for a missing file |
| `lock/IntegrityCheckerTest.kt` | Pass when hashes match, throw on mismatch for a locked version, skip when version differs |
| `resolver/DependencyResolverTest.kt` | Transitive resolution, version/kind conflicts, circular-dependency detection, namespace-collision check |
| `propath/PropathGeneratorTest.kt` | Source roots to absolute paths; `includeTests` appends test roots after |

## `src/functionalTest/kotlin/pope/` — functional tests

These run the **real** plugin through a **real** throwaway Gradle build
using Gradle's `TestKit`, not isolated function calls.

- **`PopePluginFunctionalTest.kt`** — applies the plugin via `includeBuild`
  + `withPluginClasspath()`, using this repo's own fixture packages under
  `src/functionalTest/resources/fixtures/`, and actually runs
  `popeInstall` / `popePropath` / `popeRegistryAdd`, asserting on real
  task output and real files on disk. Covers transitive resolution,
  version conflicts, the one-step add-and-install flow, merged
  `registries{}` + properties-file config, `pope_packages/` nesting by
  registry prefix vs. direct-source, and the `.pope/` layout where
  `projectRoot` points one level up.
- **`PublishedPluginFunctionalTest.kt`** — proves the plugin can be applied
  the way a real separate consumer would: by plugin id + version resolved
  from a Maven repository, not the TestKit classpath shortcut.
