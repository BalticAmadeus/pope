# Handover

A short map for picking this project up — what it is, the moving parts,
what to type, and what's built vs. not. Everything here links to the
fuller docs rather than repeating them; read this first, then follow the
links for depth.

## What this is

`pope` is a Gradle plugin that adds dependency management to Progress
OpenEdge ABL projects — install packages from git-hosted registries,
resolve transitive dependencies, generate PROPATH, all driven by a
project's existing `openedge-project.json`. See README.md's "What this
is" / "Status" for the full pitch and what currently works end to end.

## The repos

This used to be one monorepo; it's since split into several real, separate
GitHub repos. This one (`pope`) is `github.com/BalticAmadeus/pope`. The
ones safe to reference in a demo:

| Repo | What it is |
|---|---|
| **`pope`** (this repo, `github.com/BalticAmadeus/pope`) | The plugin itself — everything under `src/`, plus the CLI (`pope`/`pope.bat`, `cli/`), the standalone `pope-init`/`pope-init.bat` bootstrap scripts, and docs. Published as a real Maven-coordinate Gradle plugin (see README.md's "Per-machine setup") — nobody needs to clone this repo to *use* pope, only to develop it. |
| **`PauliusKu/Registry`** | A catalog registry — a small repo holding only reference files (`packages/<name>/<version>.json`) that point at a package's own dedicated repo. No package content lives in a registry itself. |
| **`PauliusKu/Util`**, **`PauliusKu/Language`** | Individual packages, each its own repo, each tagged per version, referenced from the catalog above. |
| **`erudys27/pope-test`** | A real consumer project wired up against the published plugin — the actual "clone + `pope install`" experience, no pope-specific setup needed. |

An older `openedge-package-manager` repo also exists (the original demo/
consumer app), but it predates this split and still uses a local, not
remote, registry setup — don't point anyone to it as a current example.

## Repo layout (this repo, `pope`)

```
docs/                             decisions/ (ADRs), spec/ (design docs), research/
src/main/kotlin/pope/             the plugin - see docs/spec/kotlin-gradle-files.md
src/main/resources/pope/scaffold/ templates/scripts bundled into the plugin jar, for popeInit
src/test/kotlin/pope/             unit tests
src/functionalTest/kotlin/pope/   TestKit tests - run the real plugin
pope / pope.bat                   per-project CLI, copied into each project by popeInit
cli/                              global CLI + its one-time install script
pope-init / pope-init.bat         standalone bootstrap scripts (downloaded once, no pope clone needed)
build.gradle.kts                  plugin build config
```

The Gradle wrapper (`gradlew`/`gradlew.bat`) is checked in - for
developing `pope` itself; a *consumer* project gets its own fresh copy
from `pope-init`, it never needs this repo's copy.

## One-time setup (using pope in a project - no clone needed)

1. Download `pope-init` (`pope-init.bat` on Windows) from this repo, into
   whatever ABL project you want to wire up to pope.
2. Run it — prompts for registries, downloads the Gradle wrapper, writes
   `settings.gradle.kts`/`build.gradle.kts` pointed at the published
   plugin, generates/patches `openedge-project.json`, and offers to
   install the global CLI too. Full detail (including the non-interactive
   path) in README.md's "Per-machine setup".
3. If you skipped that prompt, `cli/install.sh` (`cli\install.ps1` on
   Windows) does the same PATH setup on its own — one-time, safe to
   re-run. Once it's run, bare `pope <command>` works from any pope
   project, any directory — `pope-init` itself is only needed again for
   bootstrapping a *new* project, it doesn't get a global shortcut.

Only someone developing `pope` itself needs to clone this repo. To try a
local change without publishing anything, point a throwaway project's
`settings.gradle.kts` at `includeBuild("<path to your pope clone>")`
instead of the Maven repo, with no version on the `plugins {}` block —
Gradle then rebuilds pope fresh from source on every run.

To actually publish a package or stand up a new registry (not just
consume one), see README.md's "Creating a registry" and "Publishing a
package" sections — the repo-structure/tagging conventions and the
manual "add a version file, don't replace it" publish step live there.

## Workflow

`main` ← `develop` ← `feature/<name>` branches, one feature per branch,
merged into `develop` via PR, then `develop` merged into `main` in
batches once a few features have landed. No CI configured yet - `./gradlew
check` before every merge is on you.

## Commands you'll actually type

```
pope-init                              wire up a new/existing project (interactive)
pope install                           resolve declared dependencies
pope install <package>[:<versionSpec>] add + resolve a dependency in one step
pope uninstall <package>               remove a dependency and clean up its files
pope propath                           print the generated PROPATH
pope propath --tests                   ...also including buildPath's "test" entries
pope registry add [<prefix> <url>]     add a registry (interactive if omitted)
pope prune [--dry-run]                 remove pope_packages/ entries no longer declared
```

`pope` here means whichever CLI applies — the per-project `./pope`/`.\pope.bat`
that never needs anything installed globally, or the global `pope` (once
`cli/install` has run) that works from any project, any directory. See
README.md's "Per-machine setup" for why there are two and when each
applies — it matters, don't assume they're interchangeable by accident.

## What's built vs. decided-but-not-built

README.md's "Status" section is the authoritative, up-to-date list of
what actually works - check there rather than here, so this file doesn't
need updating every time a feature ships. A few things were explicitly
discussed and decided *against* building, for now - worth knowing so they
don't get re-litigated from scratch or assumed to be oversights:

- **Package namespace uniqueness is left as author convention, not
  enforced by the tool.** `popePackageName` is free text; nothing stops two
  authors from picking the same one. The real safety net is
  `DependencyResolver`'s namespace-collision check, which fails loudly
  *if* a project ends up depending on two packages that collide — but
  that's a per-project catch, not a global guarantee. See
  `docs/spec/manifest-schema.md`'s `popePackageName` row and open questions.
- **A `"resources"`/images `buildPath` type** (alongside `"source"`/`"test"`)
  was discussed and deliberately left out — no decided use case yet, and
  it needs its own call on whether/how it belongs on PROPATH at all. See
  `docs/spec/propath-generation.md`'s "buildPath entry types" section.

## Where to read more

- **`docs/spec/kotlin-gradle-files.md`** — the Gradle/build side (root
  `.kts` scripts, wrapper, bootstrap scripts) plus the `pope install` /
  `pope propath` runtime walkthroughs, written for someone new to
  Gradle/Kotlin. Read this to see how the pieces fit together.
- **`docs/src-kt-file-guide.md`** — the per-file reference for the plugin
  code under `src/`: for each `.kt` file, the types/functions it contains
  and how it connects to the rest, plus tables for the tests. Check here
  when you want the logic of one specific file.
- **`docs/spec/manifest-schema.md`**, **`lockfile-format.md`**,
  **`propath-generation.md`** — the design specs for `openedge-project.json`,
  `pope.lock`, and PROPATH generation respectively.
- **`docs/decisions/`** — ADRs, one per real decision, numbered, with
  status. Read these when something in the code looks like an odd choice
  — it's very likely there's a reason written down here.
- **`docs/research/`** — background analysis (comparisons to npm/pip/Maven
  etc.) that informed the decisions above.

## One more thing

Some of this project's history exists as conversation context with an AI
assistant (design discussions, live-verification results, debugging
sessions), not as anything written into this repo. If something about
*why* a piece of code looks the way it does isn't answered by the docs
above, it may simply not be written down anywhere accessible — ask, or
treat the code + tests + docs here as the actual source of truth.
