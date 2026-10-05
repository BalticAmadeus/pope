# 0014 — Stamp and warn-check the tool version a package was written with

Status: accepted
Date: 2026-09-25

## Context

A package is fetched and built independently of whatever `pope` version
the consuming project happens to be running. If a package's
`openedge-project.json` was last written by a materially older or newer
`pope`, its manifest shape or behavior could disagree with what the
currently-running tool expects — silently, with no signal to the user
that the two versions might not agree.

## Decision

Every manifest write that goes through `ManifestWriter.write()` — which
is every write path in the tool, not just `popeInit` — auto-stamps a
`popeToolVersion` field with the currently-running `pope`'s own version
(`PopeVersion.current()`, read via `Package.getImplementationVersion()`
off the plugin jar's own manifest; this only resolves to a real value
when `pope` is applied from an actual published jar, not via
`includeBuild`/TestKit's `withPluginClasspath()` — in dev testing it's
`null`).

`pope install` compares each resolved package's stamped
`popeToolVersion` against the running tool's own version and **warns**
(never fails) when the **major** version differs. This is a v1,
deliberately unproven heuristic — a false positive must never be able to
block an otherwise-working install.

`pope version` prints the running plugin's own version. `pope stamp`
(point `pope.projectRoot` at a package with no Gradle wiring of its own)
lets a package author refresh just this stamp with no other manifest
changes, for a leaf dependency that otherwise has nothing else to patch.

## Consequences

- Every manifest write anywhere in the tool now carries this stamp for
  free, with no per-call-site opt-in needed.
- A real version disagreement is visible (as a warning) instead of
  silent, without the check being trusted enough yet to block anything.
- `PopeVersion.current()` returning `null` (dev/test runs) must be
  treated as "nothing to compare" on both sides, not as a mismatch.

## Alternatives considered

- **Fail hard on a mismatch** — rejected: punishes a working install
  over a heuristic that hasn't been proven reliable yet.
- **Derive compatibility from the manifest schema itself** (e.g. a
  schema version) rather than the tool's own version string — more
  correct long-term, deferred: no versioned schema registry exists yet
  to check against.
- **A package declares its own minimum-compatible pope version** by
  hand, instead of the tool auto-stamping its own version on every
  write — rejected for v1: adds a field package authors would have to
  remember to maintain themselves, instead of something that just
  happens automatically.
