# 0005 — Tool name

Status: accepted
Date: 2026-08-11, updated 2026-08-11

## Context

The project's working name was originally "ppm." That collides with an
established tool: Perl Package Manager, shipped with
ActiveState/Strawberry Perl (`ppm install X`). Continuing to use it risked
confusion in search results, documentation, and package registry naming,
even while only used internally.

## Candidates evaluated

- `opm` — **rejected.** Collides with at least two live, actively
  maintained tools: OpenResty Package Manager (its own registry at
  opm.openresty.org, explicitly positioned like npm/CPAN) and Red Hat's
  Operator Package Manager for OpenShift/Kubernetes Operator catalogs.
  Worse collision risk than `ppm`, not better.
- `oepm` — **selected.** The only notable hit is the Spanish Patent and
  Trademark Office (oepm.es, an unrelated government domain, not a
  software tool). No CLI tool, package registry, or npm/PyPI/GitHub/Maven
  project using this name turned up in searches covering general web
  results, GitHub, and the existing OpenEdge tooling ecosystem
  specifically. Reads clearly as "OpenEdge Package Manager."
- `ablpm` / `abl-pm` — not checked in depth; fallback if `oepm` turns out
  to have a conflict not surfaced by search (e.g. a private/internal
  tool, or a very recent project not yet indexed).
- `oepkg`, `progpm` — not checked; lower priority given `oepm` looked
  clean.

## Decision

Renamed the project (folder, package/module path, binary name, manifest/lock
filenames, all docs) from `ppm` to `oepm` throughout this repo.

This is not yet a fully verified-clean name: confirmation only covered
general web search and GitHub. It has **not** been directly checked
against npm, PyPI, crates.io, or the relevant JVM package coordinates
(Maven Central group ID) — the exact registry to check depends on the
implementation language (see ADR-0001, not yet decided). That check
should happen before registering a domain or publishing anything under
this name publicly — see the open item in the root README.

## Consequences

- All references to the old name (`ppm.json`, `ppm.lock`, the `ppm`
  binary, the package/module path) have been updated to `oepm`
  equivalents.
- If a not-yet-surfaced conflict turns up during the final namespace
  check, expect another rename pass — keep the name out of anything
  expensive to change (published API contracts, external registry
  coordinates) until that check is done.

## Alternatives considered

See "Candidates evaluated" above.

## Update

The project was later renamed again, from `oepm` to `pope`, when it moved
to org ownership. See ADR-0009 for that rename; this ADR remains an
accurate record of the `ppm` → `oepm` decision at the time it was made.
