# PROPATH generation

Status: implemented (`pope.propath.PropathGenerator`, driven by
`popePropath` in `PopePlugin.kt`). This was the riskiest, least-proven
part of the project; the vertical slice validated the approach. The
ordering default and the `buildPath` entry-type rules below are current
behavior. The shadow-warning item at the bottom is still open.

## Constraints from the platform (not design choices — facts about ABL)

- PROPATH is searched in order, first match wins. Placing a
  frequently-used entry earlier is a real performance concern, not just
  cosmetic.
- PROPATH is flat and session-global — there is no per-package isolated
  resolution the way `node_modules` nesting allows. Exactly one version of
  each package name can be active in a given project's PROPATH.
- A `.pl` file containing only r-code can be added directly to PROPATH. A
  `.pl` containing other source/resource types must be extracted to a
  directory first, then that directory added instead. pope must know which
  kind of `.pl` it's handling before deciding how to add it.
- A package/namespace directory cannot contain a period in its name.

## What `pope propath` needs to produce

Given a resolved dependency graph (from the lockfile), an ordered list of
filesystem paths (and/or extracted `.pl` directories) suitable for:

1. Direct use as the `PROPATH` environment variable / `-basekey INI`
   propath setting, and/or
2. A `.pf` parameter file, since that's what's actually passed to
   `_progres`/`prowin` in most real dev/build workflows — likely the more
   useful of the two outputs in practice.

## Ordering decision (v1)

**Default: a project's own source first, resolved dependencies after**
(i.e. `buildPath` entries generated for dependencies are appended after
the project's own source roots, not prepended). This is a pragmatic v1
default, not a rigorously justified one — there is no natural precedence
between sibling dependencies either, so ties still fall back to
alphabetical for determinism.

This default only stays low-stakes because of the namespace-relative
include convention (see [ADR-0007](../decisions/0007-namespace-relative-includes.md)
and the include-collision findings that motivated it): PROPATH order determines which
*file* wins a bare-filename collision, but if every cross-file include is
referenced by its namespace-relative path instead of a bare filename,
there's nothing left for order to silently get wrong. Ordering strategy
was deliberately not over-engineered for v1 on this basis — it's revisited
if the include-namespacing convention turns out not to hold everywhere.

## buildPath entry types (decided 2026-09-03)

`buildPath` entries of type `"source"` are always on the PROPATH `pope
propath` prints. Entries of type `"test"` are only included when
explicitly asked for — `pope propath --tests` (`-PpopeIncludeTests`) —
appended after the source roots; plain `pope propath` (the default, used
implicitly by anything build/production-facing) never includes them.
This only affects a manifest's *own* test roots: `GitPackageFetcher`/
`CatalogRegistry` never read a dependency's `"test"` entries at all when
fetching it, so a dependency's own tests can never end up copied into a
consumer's `pope_packages/` or on a consumer's PROPATH, with or without
the flag — see `docs/spec/manifest-schema.md`'s `buildPath` row. Any
other `type` value is ignored everywhere for now (a `"resources"`/images
type was discussed but not decided or built — it would need its own call
on whether/how it belongs on PROPATH at all).

## Open questions

- Whether pope should warn (or fail) when a generated PROPATH would shadow
  an entry already present in the project's existing
  `openedge-project.json` `buildPath`, rather than silently producing a
  second, conflicting source of truth.
