# 0003 — Database dependencies are declared, never managed

Status: rejected for v1 (2026-08-13) — DB-aware packages are entirely out
of scope for v1, not merely unmanaged. No `db` field exists in the v1
manifest; this ADR is kept for record and to revisit if/when DB support is
ever built.
Date: 2026-08-11

## Context

ABL code is often tightly coupled to a live database connection (schema,
temp-tables mapped to real tables, buffers). Managing database schema —
creation, migration, versioning — is a large, separate problem. Notably,
Progress's own official DevOps Framework documentation states that
dependencies on database artifacts (schema files, structure files, backup
files) and application packages (WAR/OEAR/PAAR) are not yet supported by
their tooling either. If Progress hasn't solved this in their own official
plugin, it is not a reasonable v1 target for pope.

## Decision

A package manifest may declare that it requires a database (and optionally
what it expects to find there), but pope never creates, migrates, connects
to, or otherwise manages a database. This is treated the same way npm
treats `peerDependencies`: declared, checked where feasible, never
resolved automatically.

Packages must additionally declare whether they touch a database at all
(`db: none | required`). This flag is expected to do most of the practical
work: a meaningful fraction of reusable OO ABL code (validators, DTOs,
string/JSON utilities, non-DB business logic) can be fully package-managed
today under this rule, while DB-bound code is clearly flagged as such
rather than silently assumed to "just work" once resolved.

## Consequences

- pope cannot guarantee a resolved dependency graph is actually *runnable*
  end to end for DB-bound packages — only that the code is present on
  PROPATH. This must be documented clearly so it isn't mistaken for a
  gap/bug later.
- DB-aware packages (`db: required`) are out of scope for the v1 demo
  entirely — proving the `db: none`/`db: required` boundary in practice is
  deferred until DB support is actually built, rather than demoed ahead of
  the implementation it's meant to prove.

## Alternatives considered

Attempting schema-aware dependency management (diffing `.df` files,
provisioning temp DBs) — rejected as out of scope for v1; revisit only
after the core resolver/PROPATH mechanism is proven.
