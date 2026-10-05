# 0006 — Package manager component scope for v1

Status: proposed — several rows still open, see "Open items" below
Date: 2026-08-12

## Context

A package manager is not one thing — it's a set of largely independent
components. Before going deep on any single piece (e.g. the dependency
resolver), it's worth mapping the full anatomy and being explicit about
which pieces pope builds itself, which it delegates to existing tooling
(Gradle/Ivy, per ADR-0001), and which are out of scope for v1 entirely.
Guiding principle: v1 builds only what's necessary and has no off-the-shelf
substitute — everything else is configured from existing tooling or
explicitly deferred, not built preemptively.

## The components

| # | Component | What it does | Reference analogy | pope v1 status |
|---|---|---|---|---|
| 1 | Manifest format | Declares a package's identity, version, dependencies | `package.json` | Decided — single file, `openedge-project.json` with pope-specific keys added (`popePackageName`, `popeDependencies`, `package_root`), not a separate `pope.json`. See `docs/spec/manifest-schema.md`. |
| 2 | Dependency resolver | Picks one consistent version per package across the graph; detects unsatisfiable conflicts | npm/Cargo resolver | Decided — delegate to Gradle/Ivy's resolver rather than build our own (ADR-0001) |
| 3 | Registry / repository | Where package versions actually live and can be looked up | npm registry, Maven Central | Decided for v1 — local filesystem directory only, no HTTP registry |
| 4 | Fetcher | Retrieves a package's files from the registry | npm downloading a tarball | Trivial for v1 — registry is a local folder, mostly a file copy |
| 5 | Local cache | Stores already-fetched packages to avoid re-fetching | `~/.npm`, `~/.m2` | Decided — out of scope v1. A cache exists to avoid re-fetch cost from something slower/remote; with the registry itself being a local folder, "fetching" is already just a file copy, so a cache would only be a redundant second local copy with no latency win |
| 6 | Lockfile | Records exact resolved versions for reproducible builds | `package-lock.json` | Decided — `pope.lock`, see `docs/spec/lockfile-format.md` |
| 7 | Installer / linker | Makes resolved dependencies usable by the project | npm's `node_modules` | pope's core, ABL-specific job — generates an ordered PROPATH rather than copying files into a folder |
| 8 | Build/compile hooks | Runs the actual compiler, plus pre/post-install scripts | npm scripts | Decided — delegate to existing PCT/Ant/Gradle rather than reimplement |
| 9 | Publisher | Packages and uploads a new version to a registry | `npm publish` | Out of scope v1 — no registry worth publishing to yet |
| 10 | CLI | The commands a developer actually types | `npm install`, `npm add` | Started — `install` / `propath` stubs exist |
| 11 | Version/semver engine | Parses and compares version numbers/ranges | the `semver` npm package | Likely inherited from Ivy/Gradle's own version matching — needs confirming, not assuming |
| 12 | Auth / access control | Login, tokens, private-registry access | `npm login`, `.npmrc` | Out of scope v1 — nothing to authenticate against yet |
| 13 | Integrity/security verification | Confirms downloaded content wasn't tampered with | checksums in `package-lock.json` | Decided — out of scope v1. Lockfile draft keeps a placeholder `integrity` field for future use, but no hashing/verification is implemented while the "registry" is a trusted local folder — not necessary yet |
| 14 | Graph introspection commands | Dependency tree/"why is this here" tooling | `npm ls`, `npm why` | Out of scope v1, nice-to-have later |

## Decision

For v1, pope builds #1 (manifest), #7 (PROPATH generation — the one
component with no off-the-shelf equivalent), and enough of #10 (CLI) to
glue everything together. Everything reusable from the JVM ecosystem
(#2, #3–4 in simplified local form, #6, likely #11) is configured, not
built from scratch. #9, #12, #14 are explicitly deferred past v1.

## Open items

- **#11, semver engine**: needs confirming that Ivy/Gradle's version
  matching actually covers what we need, rather than assuming it does.

## Alternatives considered

Building a fully custom resolver/registry/cache stack independent of
Gradle/Ivy — rejected as duplicating work ADR-0001 already decided to
avoid.

## Update (2026-10-05)

Several rows in the component table above describe the *original* v1
plan, not what ended up shipping. Left as written above for the
historical record, but anyone citing this table for "what pope v1 does"
should read the actual current state instead (see `README.md`'s
"Status" section for the full list):

- **#2, resolver** — not delegated to Gradle/Ivy as planned; hand-written
  instead (`pope/version/`, `pope/resolver/`). See ADR-0001's Update.
- **#3, registry** — not local-filesystem-only; real remote, git-hosted
  catalog registries are the primary path (`CatalogRegistry`,
  `PrefixRoutingRegistry`), with multi-registry and direct-source
  dependencies. The local-directory registry from this table is now
  only a fallback when no registry is configured at all.
- **#4, fetcher** — not a trivial local file copy; fetches a remote git
  repo via a bare-clone-plus-`git worktree` (`GitPackageFetcher`).
- **#5, local cache** — not out of scope; a real cache exists
  (`~/.pope/cache`), because fetching is no longer just a local file
  copy — it's the whole reason repeat fetches of a remote registry stay
  fast.
- **#9, publisher** — no longer entirely out of scope; `pope prepare`
  (ADR-0016) now does the mechanical part of getting a package ready to
  publish. Tagging and pushing are still a manual, deliberate step, not
  automated.
- **#13, integrity verification** — implemented, not deferred:
  `pope.lock` records a real content hash per package and fails loudly
  if a tag was force-moved to different content.
- **#11, semver engine (the "Open items" entry above)** — resolved, but
  not the way this ADR expected: see ADR-0001's Update.