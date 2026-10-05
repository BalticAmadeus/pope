# 0016 — `pope prepare`: get a package ready to publish

Status: accepted
Date: 2026-10-01

## Context

Publishing a package required a human to hand-edit several things pope
could check or do mechanically — and all three were found as real bugs
in a real published package (`PauliusKu/Translation`) during live use:

- A registry-style `popeDependencies` entry (a caret-range string) only
  resolves for a consumer who happens to have that exact same registry
  configured locally — `Translation` declared a dependency this way,
  and it broke for every consumer who didn't have that registry set up.
- Dev-only project files (`gradlew`, `build.gradle.kts`,
  `pope-registries.properties`, etc.) have no reason to ship inside a
  published package's own repo.
- `popePackageName` can silently drift from the real namespace declared
  in `.cls`/`INTERFACE` files — `Translation` declared `"Translation"`
  while its real code declared `PauliusKup.Translation`.

## Decision

A new, non-interactive `popePrepare` Gradle task (`pope prepare`), run
directly inside a package's own checkout — the same simple pattern as
`pope-init`, not the separate-project `pope.projectRoot` pattern:

1. Re-resolves every `DependencySpec.Registry` entry in the package's
   own declared `popeDependencies` through the configured registry, and
   rewrites it to `DependencySpec.DirectSource` using the real
   `{repoUrl, ref}` that resolution actually used.
2. Adds dev-only files to `.gitignore`, append-only.
3. Fails loudly — never auto-fixes — if `popePackageName` disagrees with
   the real namespace found in `.cls`/`INTERFACE` files. Validates that
   every class is *at or under* the declared name, not that all classes
   share one single flat namespace — a package's classes commonly live
   in sub-namespaces (e.g. `Util.Formatter`, `Util.Substitutable`) below
   its own declared root, which is a normal layout, not a disagreement.

Explicitly out of scope: any git operation (no tag, no push) and any
change to the `"version"` field — both stay a deliberate, manual human
step.

## Consequences

- A prepared package's own dependencies no longer require any consumer
  to have a matching registry configured.
- The exact category of mistake found in the real `Translation` package
  is now mechanically catchable before publishing, not just
  discoverable after a consumer hits it.
- Tagging, pushing, and version-bumping remain manual and reviewable,
  not automated end-to-end.

## Alternatives considered

- **A full `pope publish` that also tags and pushes** — rejected: the
  team wanted publishing kept deliberately manual and reviewable at the
  git level, not automated start to finish.
- **Running via the `pope.projectRoot`-pointing pattern**, like
  `popeStamp` does for a leaf dependency with no Gradle wiring of its
  own — rejected for this workflow specifically: preparing a package is
  something its own author does from inside it, not something run
  against it from a separate project.
- **Auto-fixing a `popePackageName`/namespace mismatch** instead of
  failing loudly — rejected: either side could be the actual mistake,
  so guessing which one to change risks making it worse.
