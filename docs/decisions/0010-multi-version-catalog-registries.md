# 0010 — A catalog folder holds multiple versions, resolve picks the highest match

Status: accepted
Date: 2026-09-07

## Context

The initial `CatalogRegistry` design let a package's catalog folder hold
exactly one reference file, implicitly "the" version. That can't express
a package with a real release history — publishing a new version meant
overwriting the only file, destroying the record of what was previously
published and breaking any consumer pinned to a caret range that the
new version no longer satisfies.

## Decision

A package's catalog folder (`packages/<name>/`) can hold any number of
version files, one per published version (see ADR-0015 for how a
version/ref are expressed in each). `resolve(versionSpec)` picks the
highest version satisfying the caret range; `findAny()` (used for
bare-name install with no version given) picks the highest version
overall, with no range filter. Only the one version actually picked is
ever fetched — the rest stay untouched catalog metadata.

## Consequences

- Publishing a new version is strictly additive: add another
  `<version>.json` file, never replace an existing one.
- A consumer pinned to an older caret range keeps resolving to the
  version it's compatible with, even after newer versions are
  published alongside it.
- The catalog folder itself is the full, inspectable version history of
  a package — no separate index or metadata file needed.

## Alternatives considered

- **One reference file per package, overwritten on each publish** — the
  original, rejected design; loses history and breaks any consumer not
  pinned to the exact latest version the moment a new one is published.
- **A single index file listing all versions**, rather than one file per
  version — rejected: one file per version is simpler to publish
  (append-only, no file to read-modify-write) and is already how git
  tags/releases work.
