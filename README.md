# pope - a package manager for Progress OpenEdge ABL

## What this is

An experiment in bringing dependency management, versioning, and modular
code distribution to OpenEdge ABL. See `docs/research/` for the
comparative analysis (npm, pip, Maven/Ivy, existing OpenEdge tooling)
this design is based on.

This repo is just the plugin. The demo/consumer app and the registries/
packages it depends on live in their own separate repos - see "Remote
registries" below.

## Status

The core loop works end to end, against real, remote, git-hosted
registries:

- **Multi-registry, prefix-routed resolution** - any number of registries,
  each with its own routing prefix (e.g. `ba.`, `cw.`).
- **Catalog-based registries** - a registry is a small git repo holding
  only reference files, no package content. Fetches through a
  bare-clone-plus-`git worktree` cache, so repeat fetches are local.
  Every package from a registry shares one `pope_packages/<registryName>/`
  folder; direct-source dependencies share `pope_packages/dependencies/`.
- **Multi-version registries** - a package's catalog folder can hold any
  number of version files; install picks the highest one satisfying the
  caret range.
- **Direct-source dependencies** - a package can depend on another by
  inline `{repoUrl, ref}`, no registry involved.
- **Transitive resolution** with version-conflict detection across the
  whole graph, and **PROPATH namespace-collision detection** (two
  packages sharing a real ABL namespace fail loudly instead of silently
  shadowing each other).
- **Integrity verification** - `pope.lock` records a content hash per
  package; a tag force-moved to different content fails loudly.
- **`buildPath` test entries** - `type: "test"` is excluded from PROPATH
  by default, included with `pope propath --tests`. Never leaks from a
  dependency into a consumer.
- **`pope prune [--dry-run]`** - removes `pope_packages/`/`buildPath`
  entries no longer part of the resolved graph.
- **`pope uninstall <package>`** - removes a dependency and cleans up its
  `pope_packages/`/`pope.lock`/`buildPath` entries in one step. A bare
  local name works too if it matches exactly one declared dependency, and
  a typo gets a "did you mean X?" prompt against your declared dependencies.
- **Bare-name install** - `pope install <name>` with no registry given
  searches every configured registry; auto-installs on one match, prompts
  to choose on several. Typos get a "did you mean X?" prompt too.
- Backward compatible: no `registries {}` configured falls back to a
  plain local-directory registry.

See `docs/decisions/` for what's been decided and why. Still missing:
include-collision linting, and real Gradle/Ivy-based resolution - version
matching (`pope/version/`) and graph resolution (`pope/resolver/`) are
hand-written instead, a known deviation from ADR-0001 worth raising with
the team before treating as settled.

## Per-machine setup

No clone needed - pope is resolved by version from its published Maven
repo (`https://balticamadeus.github.io/pope/`), the same way any other
Gradle plugin is.

1. **Wire up your ABL project** - download `pope-init` (`pope-init.bat`
   on Windows) once, from
   [github.com/BalticAmadeus/pope](https://github.com/BalticAmadeus/pope),
   and run it from your project's own directory:
   ```
   ./pope-init <version>      # e.g. ./pope-init 1.2.0
   ```
   It writes the Gradle wrapper, `settings.gradle.kts`, and
   `build.gradle.kts` (pointed at the published plugin), then lets
   Gradle finish the rest: generating/patching `openedge-project.json`,
   prompting for registries, and offering to install the global CLI
   (step 2). Safe to re-run. See `HANDOVER.md` and
   `docs/spec/kotlin-gradle-files.md` for what it actually does.
2. **(Optional) Install the global CLI**, so `pope install`/`uninstall`/
   `propath`/`prune`/`registry add` work from any project without a
   `./`/`.\` prefix - `pope-init` offers to do this for you, or run it
   directly:
   ```
   cli/install.sh      # cli\install.ps1 on Windows
   ```
   One-time, idempotent. Open a new terminal afterward for `PATH` to
   apply. This is `cli/pope`, **not** the per-project `pope`/`pope.bat` -
   the per-project one finds its target by its own file location (works
   with zero global setup, e.g. in CI); `cli/pope` finds its target by
   walking up from your current directory, which is what makes it safe
   to put on `PATH`. Don't put a per-project copy on `PATH` instead - it
   would silently operate on wherever that file happens to live.

### Commands

```
pope-init                              wire up a new/existing project (interactive)
pope version                           print the installed pope plugin version
pope install                           resolve declared dependencies
pope install <package>[:<versionSpec>] add + resolve a dependency in one step
pope uninstall <package>               remove a dependency and clean up its files
pope propath [--tests]                 print the generated PROPATH
pope registry add [<prefix> <url>]     add a registry (interactive if omitted)
pope prune [--dry-run]                 remove pope_packages/ entries no longer declared
```

## Creating a registry

A registry is just a plain git repo, no server or tooling involved - one
reference file per package version, at
`packages/<name>/<version>.json`:
```json
{
  "repoUrl": "https://github.com/yourorg/calculator.git",
  "version": "1.0.0",
  "ref": "v1.0.0"
}
```
Create the repo, add that file, commit, push. That's the whole registry.
Publishing a new version means adding another `<version>.json` file, not
replacing the old one (see "Multi-version registries" above). Then point
a consumer project at it - see below.

## Publishing a package

A package is its own git repo, tagged at each version:
```
your-package/
  openedge-project.json
  src/
    yourorg/
      yourpackage/
        YourClass.cls        (class yourorg.yourpackage.YourClass:)
```
```json
{
  "name": "your-package",
  "version": "1.0.0",
  "popePackageName": "yourorg.yourpackage",
  "popeDependencies": {},
  "buildPath": [{ "type": "source", "path": "src" }]
}
```
The folder structure under `src/` must mirror the class namespace - an
ABL requirement, not a pope one. Fold your org into `popePackageName`/the
namespace (e.g. `yourorg.yourpackage`, not bare `yourpackage`) so it
doesn't collide with someone else's package of the same name - pope
doesn't enforce this itself, it only catches an actual collision at
resolve time (see "PROPATH namespace-collision detection" above). Tag
the repo (`git tag v1.0.0`) matching whatever `ref` a registry's
reference file points at.

## Remote registries

Two mergeable config sources - a prefix declared in both, or twice in
one, is a duplicate-prefix error.

**`pope-registries.properties`** (project root, committed, CLI-mutable):
```
ba.prefix=ba.
ba.catalogUrl=https://github.com/erudys27/registry-ba.git
```
Add to it with `pope registry add [<prefix> <url> [<name>]]` (interactive
if omitted).

**`registries {}`** in `build.gradle.kts` (hand-authored):
```kotlin
pope {
    registries {
        create("ba") {
            prefix.set("ba.")
            catalogUrl.set("https://github.com/erudys27/registry-ba.git")
        }
    }
}
```

A dependency like `"ba.calculator": "^1.0.0"` routes to whichever
registry's prefix it starts with. You can also install by the registry's
own name plus its local name, e.g. `pope install registry-ba/calculator` -
or just `pope install calculator` and let pope search every registry for
it. See
[registry-ba](https://github.com/erudys27/registry-ba) for a real catalog
registry, and the packages it references (e.g.
[calculator](https://github.com/erudys27/calculator)) for what a
resolvable package repo looks like. If neither source has any entries,
`popeInstall` falls back to a plain local-directory registry
(`registryRoot`).

