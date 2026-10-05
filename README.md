# pope - a package manager for Progress OpenEdge ABL

## What this is

An experiment in bringing dependency management, versioning, and modular
code distribution to OpenEdge ABL. See `docs/research/` for the
comparative analysis (npm, pip, Maven/Ivy, existing OpenEdge tooling)
this design is based on.

This repo is just the plugin. The demo/consumer app and the registries/
packages it depends on live in their own separate repos - see "Remote
registries" below.

## Per-machine setup

No clone needed - pope is resolved by version from its published Maven repo 
(https://balticamadeus.github.io/pope/), the same way any other Gradle plugin is. 
That's just the maven-repo branch of this repo served via GitHub Pages, not a 
hosted registry service - chosen so publishing needs no new account or token, 
only a git branch pushed to on release.

1. **Wire up your ABL project** - download `pope-init` (`pope-init.bat`
   on Windows) once, from
   [github.com/BalticAmadeus/pope](https://github.com/BalticAmadeus/pope),
   and then you can run it from your every project's own directory that you want to wire up:
   ```
   path-to-file/pope-init [<version>]      # path-to-file\pope-init.bat on Windows
   ```
   It writes the Gradle wrapper, `settings.gradle.kts`, and
   `build.gradle.kts` (pointed at the published plugin), then lets
   Gradle finish the rest: generating/patching `openedge-project.json`,
   prompting for registries, and offering to install the global CLI
   (step 2). Safe to re-run. See `HANDOVER.md` and
   `docs/spec/kotlin-gradle-files.md` for what it actually does.
2. **(Optional) Install the global CLI**, so `pope install`/`uninstall`/
   `propath`/`prune`/`registry add` work from any project without a
   `./`/`.\` prefix. `pope-init` offers this already - said no, or want
   it later? Run it directly:
   ```
   ~/.pope/cli/install.sh      # %USERPROFILE%.pope\cli\install.ps1 on Windows
   ```
   One-time, idempotent; open a new terminal after. This is the global
   `cli/pope`, not the per-project `pope`/`pope.bat`: it finds its
   target project by walking up from your current directory (safe to
   put on PATH), while the per-project copy is tied to its own file
   location instead (zero-setup, e.g. in CI).

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
pope prepare                           get this package ready to publish
```

## Creating a registry

A registry is just a plain git repo, no server or tooling involved - one
reference file per package version, at
`packages/<name>/<version>.json`. The **filename** is the version - the
file's content just says where to fetch it from:
```json
{
  "repoUrl": "https://github.com/yourorg/calculator.git",
  "ref": "v1.0.0"
}
```
`ref` is passed straight to git, so it can be anything git understands -
a tag (as above), a branch name, or a raw commit SHA - it doesn't need
to match the version number at all. Create the repo, add that file,
commit, push. That's the whole registry.
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
Run `pope-init` (or `pope-init.bat` on Windows) inside the package's own
directory to generate/patch `openedge-project.json`, which looks like this:
```json
{
  "name": "your-package",
  "version": "1.0.0",
  "popePackageName": "yourorg.yourpackage",
  "popeDependencies": {},
  "buildPath": [{ "type": "source", "path": "src" }],
  "popeToolVersion": "1.2.0"
}
```
Fold your org into
`popePackageName`/the namespace (e.g. `yourorg.yourpackage`, not bare
`yourpackage`) so it doesn't collide with someone else's package of the
same name - pope only catches an actual collision at resolve time (see
"PROPATH namespace-collision detection" above).

Before tagging, run `pope prepare` (global CLI) or `./gradlew
popePrepare` from the package's own checkout - it pins any
registry-style `popeDependencies` entries to their real `{repoUrl, ref}`
(a caret-range dependency only resolves for a consumer who happens to
have that same registry configured), refreshes `popeToolVersion`,
gitignores dev-only files, and fails loudly if `popePackageName`
disagrees with the real namespace in `.cls` files. It never touches git
or `"version"` - tag (`git tag v1.0.0`) and push yourself.

## Remote registries

A registry maps a prefix to a catalog repo, so a dependency like
`"Paulius.Util": "^0.0.1"` resolves without a full `{repoUrl, ref}`.
Add one with `pope registry add [<prefix> <url> [<name>]]` (interactive
if omitted) - writes to `pope-registries.properties` at the project
root (committed, mergeable with any registries declared in
`build.gradle.kts` instead; a prefix declared in both is a
duplicate-prefix error). Then install by prefixed name
(`pope install Paulius/Util`) or let pope search every registry
(`pope install Util`). See
[Registry](https://github.com/PauliusKu/Registry) for a real catalog
registry, and [Util](https://github.com/PauliusKu/Util) for what a
resolvable package looks like.

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
- **Tool-version compatibility check** - every manifest pope writes gets
  stamped with `popeToolVersion`; `pope install` warns (doesn't fail) if
  a resolved dependency's major version differs from the one currently
  running. `pope version` prints your own installed version; the
  `popeStamp` Gradle task (`gradlew popeStamp`, no `pope` CLI shortcut)
  refreshes the stamp on a package with no Gradle wiring of its own, e.g.
  a leaf dependency - point a separate project's `pope.projectRoot` at
  it and run the task from there.
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
