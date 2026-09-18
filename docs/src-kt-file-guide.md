# `src/` Kotlin file guide

A file-by-file reference for everything under `pope/src/`, written for
someone new to the codebase. For each file: what it is, the important
types/functions inside it, and how it connects to the rest.

If you want the runtime story ("what happens when I type `pope install`")
rather than a per-file list, read `docs/spec/kotlin-gradle-files.md`'s
walkthrough sections instead. This doc is the reference; that one is the tour.

## The big picture

`pope` is a Gradle plugin. One project builds one JAR. Another ABL project
applies that JAR and gains five Gradle tasks: `popeInstall`, `popePropath`,
`popePrune`, `popeUninstall`, `popeRegistryAdd`.

The code is split into small single-purpose packages under
`src/main/kotlin/pope/`:

| Package | Responsibility |
|---|---|
| `pope` (root) | The plugin entry point and the five task definitions |
| `manifest/` | Read and write a project's `openedge-project.json` |
| `registry/` | Given a package name + version range, find the package |
| `fetch/` | Actually pull a package's files down with `git` |
| `resolver/` | Walk the whole dependency graph, transitively |
| `lock/` + `integrity/` | Write `pope.lock` and detect tampered packages |
| `propath/` | Turn source roots into an ABL PROPATH |
| `version/` | Parse `X.Y.Z` and match `^X.Y.Z` ranges |
| `suggest/` | "Did you mean X?" typo suggestions and the exceptions that carry them |
| `trust/` | Confirmation prompt for direct-source (non-registry) dependencies |

Data flows roughly left to right: `manifest` tells you what is wanted,
`registry` + `fetch` + `resolver` work out and retrieve what that means,
`lock`/`integrity` record it, `propath` reports it.

---

## `src/main/kotlin/pope/` — the plugin

### `PopePlugin.kt`

The entry point. Everything starts here.

- **`class PopePlugin : Plugin<Project>`** — Gradle instantiates this and
  calls `apply(project)` once when a build script does
  `id("io.github.balticamadeus.pope")`. `apply()` registers the five tasks
  and creates the `pope { }` configuration block.
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
- **The five `project.tasks.register(...)` blocks** — each defines one
  task's `group`, `description`, and `doLast { }` body (the code that runs
  when you invoke the task):
  - **`popeInstall`** — reads the manifest, builds the `Registry`, resolves
    the full graph via `DependencyResolver`, integrity-checks each package
    against `pope.lock` *before* copying anything, installs each package
    (`installPackage` — see below), then writes `pope.lock`, updates
    `dependencies` (only if `-PpopeAdd` was used), and updates `buildPath`.
    Order matters: nothing touches disk until resolution has fully
    succeeded. A direct-source dependency not already trusted in
    `pope.lock` prompts via `TrustPrompt` before it's fetched; `-PpopeTrustAll`
    skips the prompt (CI).
  - **`popePropath`** — reads the manifest, calls `PropathGenerator`,
    prints the resulting absolute paths. Read-only.
  - **`popePrune`** — re-resolves the graph the same way `popeInstall`
    does, then deletes any `pope_packages/` entry and `buildPath` entry
    that is no longer part of that graph. `-PpopeDryRun` reports without
    deleting.
  - **`popeUninstall`** — `-PpopeUninstall=<spec>` resolves `spec` against
    the manifest's own declared dependency keys via `resolveUninstallSpec`
    (exact key, or a bare local name matching one `registryName/localName`
    key — see below), removes it, re-resolves what's left (so anything it
    alone pulled in transitively is cleaned up too, same as `popePrune`),
    and updates `pope_packages/`/`pope.lock`/`buildPath` accordingly.
    Carries forward `trustedDirectSources` entries for packages still in
    the graph, rather than dropping them all.
  - **`popeRegistryAdd`** — appends one entry to
    `pope-registries.properties` via `RegistriesPropertiesFile.add`.
- **Private helper functions at the bottom of the file:**
  - `buildRegistry(extension)` — merges `registries { }` (from the build
    script) and `pope-registries.properties` (from the CLI) into a single
    `PrefixRoutingRegistry`. A prefix *or* a name declared twice, in either
    source, is an error (a dependency can address a registry directly by
    name — see `PrefixRoutingRegistry.kt` below). Falls back to
    `LocalDirectoryRegistry` only when both sources are empty.
  - `buildPathEntryFor(packageKey, resolvedPackage)` — the `buildPath`
    source entry for a resolved package: one shared entry per registry for
    `InstallLayout.SharedRegistryRoot`, one per-package `.../src` entry for
    `Isolated`.
  - `installPackage(...)` / `deleteInstalledPackage(...)` — install/remove
    one resolved package under `pope_packages/`. For
    `SharedRegistryRoot`, only that package's own files are touched (copied
    in, and anything dropped by a version bump removed) since the folder is
    shared with sibling packages; for `Isolated`, the whole dedicated
    folder is wiped and recopied.
  - `removeNowEmptyAncestors(dir, stopAt)` — deletes now-empty ancestor
    directories after a package's files are removed (e.g. an emptied-out
    registry folder), used by `popePrune`/`popeUninstall`.
  - `resolveAddSpec(addSpec, registry, userInputHandler)` — parses
    `-PpopeAdd=name[:range]`. With no `:range`, delegates to
    `findAnyConfirmingTypo` to discover a version and pins it as `^version`.
  - `resolveUninstallSpec(spec, declaredKeys, userInputHandler)` — resolves
    `-PpopeUninstall=<spec>` against the manifest's own declared dependency
    keys (no registry search — uninstall only ever targets something
    already declared): an exact key wins outright, otherwise `spec` is
    matched as a bare local name against the local-name half of any
    declared `"registryName/localName"` key, auto-picking on one match and
    prompting on several.
  - `findAnyConfirmingTypo(...)` — like `registry.findAny`, but a
    `NameNotFoundException` with a suggestion becomes an actual yes/no
    prompt ("did you mean X?") instead of just failing, and a bare name
    matching no configured registry prefix (`NoRegistryPrefixMatchException`)
    falls back to `findAcrossRegistries`.
  - `findAcrossRegistries(localName, registry, userInputHandler)` — searches
    every configured registry (`PrefixRoutingRegistry.findAllMatches`, no
    package fetch) for a bare name. Zero matches returns null (caller
    rethrows the original error, unchanged); one match installs silently;
    more than one prompts the user to choose which registry.

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
  - `hasAny(packageName): Boolean` — cheap existence check, catalog/metadata
    only, must never fetch the real package content. Defaults to
    `findAny(packageName) != null`; `CatalogRegistry` overrides it to avoid
    that fetch. Used by `PrefixRoutingRegistry.findAllMatches` to search
    every registry for a bare name without pulling anything down.
- **`enum class InstallLayout`** — `SharedRegistryRoot` (packages share one
  `pope_packages/<installSubpath>/` folder — a registry's own name, or
  `"dependencies"` for direct-source deps) or `Isolated` (one dedicated
  `pope_packages/<installSubpath ?: packageName>/src/` per package, the
  `LocalDirectoryRegistry` fallback only).
- **`data class ResolvedPackage`** — the result: `packageName`, `version`,
  `sourceDir` (the folder to copy onto PROPATH), `projectDir` (the
  package's own root, where *its* `openedge-project.json` lives, needed
  for transitive resolution), `installSubpath` (where under
  `pope_packages/` this nests; `null` means use the package name directly),
  and `installLayout` (see above).

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
  fetched (via `GitPackageFetcher`). `hasAny` stops one step earlier —
  checks the catalog has a reference, never fetches the package itself.
- `fetchAndBuild` sets `installSubpath` to this registry's own name (not
  its prefix) and `installLayout` to `SharedRegistryRoot`, so every package
  from this registry shares one `pope_packages/<registryName>/` folder.

### `registry/PrefixRoutingRegistry.kt`

- **`data class RegistryEntry(name, prefix, registry)`** — one configured
  registry: its DSL/properties label, its routing prefix, and the
  `Registry` that serves it.
- **`class PrefixRoutingRegistry(entries)`** — a `Registry` that owns no
  packages itself. Routes a package name two ways:
  - `"registryName/localName"` — explicit, bypasses prefix matching, looks
    up the named registry by `RegistryEntry.name` and reconstructs its real
    prefixed name (`prefix + localName`) before delegating.
  - anything else — implicit, routed by longest matching prefix. No match
    throws `NoRegistryPrefixMatchException`, never a silent fallback.
- `findAllMatches(localName)` — every registry that has this bare local
  name, found cheaply via `Registry.hasAny` (no package fetch), paired with
  its `"registryName/localName"` form. Used by `PopePlugin.kt`'s
  `findAcrossRegistries` as the fallback when a bare name matches no
  configured prefix at all.
- This is the registry `popeInstall` normally uses.

### `suggest/DidYouMean.kt`

- **`object DidYouMean`**, one function `suggest(input, candidates): String?`.
- Closest candidate by Levenshtein edit distance, within a length-scaled
  threshold; `null` if nothing is close enough to be a useful guess rather
  than a wild one.

### `suggest/NameNotFoundException.kt`

- **`class NameNotFoundException(message, suggestion: String?)`** — thrown
  when a name isn't found; `suggestion`, if non-null, is the ready-to-use
  corrected name (already in whatever form the caller passed in) a catcher
  can retry directly with. Thrown by `CatalogRegistry.resolve` and
  `PrefixRoutingRegistry.routeExplicit`; turned into an interactive
  yes/no prompt by `PopePlugin.kt`'s `findAnyConfirmingTypo`.

### `suggest/NoRegistryPrefixMatchException.kt`

- **`class NoRegistryPrefixMatchException(message)`** — thrown by
  `PrefixRoutingRegistry.route()` when a bare/dotted name matches no
  configured prefix at all. Distinguished from other `IllegalStateException`s
  (e.g. a real "no version satisfies" failure) so `PopePlugin.kt` knows
  exactly when to fall back to searching every registry.

### `trust/TrustPrompt.kt`

- **`object TrustPrompt`**, one function `confirm(userInputHandler,
  packageKey, repoUrl, ref, path): Boolean`.
- Asks the user to confirm before installing a direct-source dependency
  (bypasses the registry catalog, points straight at an arbitrary git
  repo). Goes through Gradle's own `UserInputHandler` so the prompt is
  synchronized with the console renderer. No interactive input available
  (e.g. CI) declines rather than silently proceeding — use `-PpopeTrustAll`
  there instead.

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

- **`data class LockedPackage`** — `version`, `integrity`, `installSubpath`
  (where it landed under `pope_packages/`; null for older lockfiles),
  `files` (its own relative file paths, meaningful only for
  `SharedRegistryRoot` packages — `install`/`uninstall`/`prune` touch only
  these, never the whole shared folder).
- **`object LockfileReader`**:
  - `read(file): Map<String, LockedPackage>` — reads `pope.lock`'s
    `resolved` object, keyed by package name. Missing file returns an
    empty map.
  - `readTrustedDirectSources(file): Map<String, String>` — reads
    `trustedDirectSources` (package name → `"repoUrl@ref"`), so an
    already-approved direct-source dependency isn't re-prompted while its
    source hasn't changed.

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
| `registry/CatalogRegistryTest.kt` | Catalog clone, version-file discovery, best-match vs `findAny`, `hasAny` (true/false, never fetches the package), `installSubpath`, error messages |
| `registry/LocalDirectoryRegistryTest.kt` | Folder-per-package discovery, version-range check, fallback behavior |
| `registry/PrefixRoutingRegistryTest.kt` | Longest-prefix routing; explicit `registryName/localName` routing; `findAllMatches` across every registry; loud error on no match |
| `registry/PackageMatcherTest.kt` | Unique match, `null` on none, loud error on multiple |
| `registry/RegistriesPropertiesFileTest.kt` | Reading entries, append-only `add`, duplicate name/prefix refusal |
| `suggest/DidYouMeanTest.kt` | Closest-match suggestion by edit distance; `null` when nothing is close enough |
| `fetch/GitCliTest.kt` | stdout capture, non-zero exit throwing, missing-`git` message |
| `fetch/GitPackageFetcherTest.kt` | Bare-clone + worktree cache reuse, incremental fetch, stale worktree recovery |
| `integrity/DirectoryHashTest.kt` | Stable hash regardless of walk order; changes on edit/rename |
| `lock/LockfileReaderTest.kt` | Parsing `resolved` and `trustedDirectSources` entries; empty map for a missing file |
| `lock/IntegrityCheckerTest.kt` | Pass when hashes match, throw on mismatch for a locked version, skip when version differs |
| `resolver/DependencyResolverTest.kt` | Transitive resolution, version/kind conflicts, circular-dependency detection, namespace-collision check |
| `propath/PropathGeneratorTest.kt` | Source roots to absolute paths; `includeTests` appends test roots after |

## `src/functionalTest/kotlin/pope/` — functional tests

These run the **real** plugin through a **real** throwaway Gradle build
using Gradle's `TestKit`, not isolated function calls.

- **`PopePluginFunctionalTest.kt`** — applies the plugin via `includeBuild`
  + `withPluginClasspath()`, using this repo's own fixture packages under
  `src/functionalTest/resources/fixtures/`, and actually runs
  `popeInstall` / `popePropath` / `popeUninstall` / `popeRegistryAdd`,
  asserting on real task output and real files on disk. Covers transitive
  resolution, version conflicts, the one-step add-and-install flow, merged
  `registries{}` + properties-file config, the shared `pope_packages/<registryName>/`
  root vs. direct-source `dependencies/` root, typo "did you mean X?"
  prompts, bare-name install (search every registry, auto-install on one
  match, disambiguate on several), bare-name uninstall (matched against
  already-declared dependencies, same disambiguation), and the `.pope/`
  layout where `projectRoot` points one level up.
- **`PublishedPluginFunctionalTest.kt`** — proves the plugin can be applied
  the way a real separate consumer would: by plugin id + version resolved
  from a Maven repository, not the TestKit classpath shortcut.
