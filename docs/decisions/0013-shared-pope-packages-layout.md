# 0013 — Packages from one registry share one `pope_packages/` root

Status: accepted
Date: 2026-09-18

## Context

The original install layout gave every resolved package its own
dedicated folder (`pope_packages/<packageName>/src/`), added as its own
`buildPath` entry. That's simple, but scales badly: a project with
several dependencies from the same registry — or a transitive graph
that pulls in many more than were declared directly — ends up with one
`buildPath` entry and one top-level folder per package, even though
packages from the same registry have no real reason to be kept apart on
disk.

## Decision

Every package resolved from the same registry (`CatalogRegistry`)
shares one `pope_packages/<registryName>/` root — the registry's own
configured *name*, not its routing prefix — with exactly one
corresponding `buildPath` entry for the whole root, not one per
package. Direct-source dependencies similarly share one
`pope_packages/dependencies/` root. This is safe specifically because a
package's own source tree already mirrors its `package_name` — an
ABL/PROPATH requirement independent of pope (ADR-0002) — so install and
uninstall only ever need to touch a package's own files inside that
shared root, never the whole folder: copying in is per-file, and
removing a package deletes exactly its own tracked files (and now-empty
directories only it owned — see the fix in the `popeUninstall`/
`popePrune` code itself, not a separate ADR).

The old one-folder-per-package layout (`InstallLayout.Isolated`) is kept
for the local-directory fallback registry only, where this sharing
optimization doesn't apply.

## Consequences

- `buildPath`/PROPATH entry count no longer grows with the number of
  resolved packages, only with the number of registries actually used.
- Uninstalling one package from a shared root must delete exactly its
  own files, never the whole shared folder — this constrains
  `popeUninstall`/`popePrune`'s implementation, not just its UX.
- Two different packages from the same registry sharing a literal file
  path would be a real conflict under this layout — already caught
  independently by PROPATH namespace-collision detection, since that
  would already mean two packages declared the same ABL namespace.

## Alternatives considered

- **One folder per package** (the original design) — rejected: the
  `buildPath`/folder count problem above, worse the more transitive
  dependencies a graph actually has.
- **One folder per top-level declared dependency, shared transitively
  underneath it** — rejected: doesn't solve the problem, since the
  registry (not the declaring package) is the natural sharing boundary;
  transitive dependencies from the same registry would still fragment.
