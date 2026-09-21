# 0007 — Cross-file includes must use namespace-relative paths, never bare filenames

Status: accepted
Date: 2026-08-13

## Context

`demo/exploration/consumer-app/WALKTHROUGH.md` demonstrates a concrete bug
class by hand: `calculator-package` and `consumer-app` each ship their own,
unrelated `constants.i` as a bare filename at their package's PROPATH root.
ABL's `{}` include resolves a bare filename by searching PROPATH **in
order** for a file with that literal name — not by asking which package
"owns" it. With both `src` (consumer-app's own) and the vendored
`pope_packages/example.calculator/src` on PROPATH, whichever one comes
first silently wins the whole file, regardless of which one the including
code actually meant. No compile error, no warning — the class compiles and
runs, it just picks up the wrong constant.

Critically, [propath-generation.md](../spec/propath-generation.md)'s
ordering-strategy open question **cannot fix this**: reordering PROPATH
just moves which side loses, it doesn't stop unrelated packages from
colliding on a generic filename in the first place. This is a naming
problem, not an ordering problem — see the walkthrough's "Finding: PROPATH
order alone cannot fix this" section for the worked example (returns 5
instead of the expected 50 under one ordering, and just moves the same
risk to the consumer's own files under the other).

## Decision

Any include meant to be referenced from outside the file it's textually
adjacent to must use a namespace-relative path, not a bare filename:

```
{example/calculator/constants.i}
```

not

```
{constants.i}
```

and the file must physically live at that namespace-relative location
(e.g. `src/example/calculator/constants.i`), not at the package's PROPATH
root. Since two unrelated packages are already required not to collide on
their namespace directory (per [ADR-0002](0002-oo-abl-only-v1.md)), a
namespace-relative include path is immune to the bare-filename collision
above regardless of PROPATH order — `{example/calculator/constants.i}` can
only ever match one file across the whole resolved PROPATH.

This is enforced by lint/warning, not by the compiler (ABL itself has no
opinion on this), at two points:

- **Author-time**: warn when a package's own source uses a bare-filename
  `{}` include for anything outside strictly-local, same-directory use.
- **Install-time**: when `pope install` resolves a dependency, scan its
  source for bare-filename includes at its PROPATH root and warn the
  *consumer*, since a dependency's own author-time lint (if it even ran
  pope's linter) can't be retroactively trusted.

Neither lint is implemented yet; this ADR records the convention and the
two enforcement points, not a finished tool.

## Consequences

- Removes reliance on getting PROPATH ordering "correct" for this class of
  bug — see the resulting ordering default recorded in
  [propath-generation.md](../spec/propath-generation.md).
- Requires touching existing package layouts that use root-level bare
  includes (move the file under its namespace directory, update the
  include reference) — a one-line-per-include migration, not a rewrite.
- Does not protect against two unrelated packages choosing the *same
  namespace* (e.g. two independently-authored `acme.utils` packages) —
  that's a separate, still-open problem, see the `pope_package_name` uniqueness
  open question in [manifest-schema.md](../spec/manifest-schema.md).
- Lint tooling for both enforcement points is new implementation work, not
  yet scoped in detail — needs a component-scope entry alongside the ones
  in [0006](0006-pope-component-scope-v1.md) once designed.

## Alternatives considered

Relying on PROPATH ordering rules (own source first, or dependencies
first) to resolve the collision — rejected: the walkthrough demonstrates
neither ordering is safe, it only changes which side loses.
