# 0002 — Target OO ABL packages only in v1

Status: accepted
Date: 2026-08-11

## Context

OpenEdge ABL has no native distribution-unit concept, but OO ABL (classes,
interfaces, enums since OE 10.1) already has a real "package" concept:
types are organized into packages, which are dot-separated logical names
that map directly to physical directory paths under PROPATH, and class
references must be qualified as `package-name.ClassName`.

Procedural ABL (`.p`/`.w`) has no equivalent — files are just located by
filename search across PROPATH, with no namespace or grouping construct
to hang a "package" concept on.

## Decision

v1 of pope resolves, versions, and distributes OO ABL packages only: a
directory tree of `.cls`/interface/enum files whose layout mirrors a
dotted namespace, plus a manifest. Procedural code is out of scope for v1.

## Consequences

- We get a real, compiler-enforced unit of "this code belongs together"
  for free, instead of inventing one.
- A hard constraint inherited from the language: package directories
  cannot contain a period in their name, since ABL interprets the
  component after a period as another directory level. The manifest
  schema and naming conventions must respect this.
- User-defined package names cannot start with `Progress` — reserved by
  the language itself. pope should mirror this pattern for its own
  reserved namespaces if any are introduced later.
- A large amount of existing OpenEdge codebases are procedural and get no
  benefit from pope v1. This is an accepted, explicit limitation, not an
  oversight — see README "Scope for v1."
- Procedural support, if added later, will likely be a lesser-guaranteed
  "loose bundle" mechanism (a versioned directory added to PROPATH) rather
  than a true package, and should be documented as such.

## Alternatives considered

Supporting both from the start — rejected as scope creep that would delay
having anything working, and procedural code has no natural grouping
construct to validate a package boundary against.
