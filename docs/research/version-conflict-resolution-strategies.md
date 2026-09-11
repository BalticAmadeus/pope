# Version conflict resolution strategies

Background research, not a decision. Prompted by a scenario that existed
in the old monorepo's `demo/packages/` (a `double-calculator-package`
depending on both `calculator-one-package` and `calculator-two-package`,
which wanted incompatible ranges of `example.greeter`) and the resulting
question: must a diamond-dependency version conflict always be a hard
failure, or do other ecosystems have a better answer? That demo tree is
gone with the repo split, but pope's current behavior
(`pope/resolver/DependencyResolver`) is unchanged — it fails loudly. This
doc records the alternatives considered, for a future ADR if this is ever
revisited.

**None of the options below actually solve the underlying conflict** —
that's not a gap in this research, it's a property of the problem. When
two callers require genuinely incompatible versions of a resource that
can only occupy **one slot** (one name on a flat, session-global
PROPATH), no algorithm can satisfy both simultaneously — that's a logical
fact, not a tooling limitation, the same way two people can't both sit in
the one chair. Every option below is a different way of *responding* to
that fact once it's true, not a way of making it not be true:

- Options 1, 2, and 5 **escape** the conflict rather than resolving it —
  by giving each caller its own "slot" (nesting, versioned linker
  identity, a different package name) so there's never actually a shared
  resource to fight over. Only options 1 and 2 need platform support
  pope/ABL doesn't have; option 5 is a naming convention that only helps
  future packages, not ones already colliding.
- Options 3 and 7 **pick a caller to silently or semi-silently disappoint**
  and hope the deviation doesn't matter in practice. Sometimes it doesn't.
  Sometimes that's exactly how production breaks.
- Option 4 **hands the unsolved problem to a human**, who takes on the
  job of verifying the forced choice actually works.
- Option 6 (a real solver) is the closest to "solving" it, but only finds
  an answer *if one exists in the search space* — e.g. a registry version
  both ranges happen to accept. If the requirements are truly disjoint (as
  in the demo, where the registry only has one `example.greeter` version
  at all), a solver correctly reports "no solution" too — more reliably
  than a greedy walk missing one that does exist, but it can't invent
  compatibility that isn't there.

So: tooling can detect-and-refuse (current behavior), detect-and-let-a-human-decide
(option 4), or guess-and-hope (options 3/7). Nothing detects-and-fixes,
because actually fixing it needs either platform-level isolation (ABL
doesn't have it) or someone updating the incompatible code — a
maintainer action, not something a resolver can do.

## At a glance

| # | Option | For pope? |
|---|---|---|
| 1 | Physical side-by-side install (npm) | **Not usable** — needs per-directory isolation ABL/PROPATH doesn't have |
| 2 | Versioned identity, side by side (C++) | **Not usable** — same wall as #1, just at the linker level |
| 3 | Auto-pick a winner (Maven/Gradle) | **Usable, but risky** — silent, can compile against an untested version |
| 4 | Manual override (Yarn/npm/pnpm) | **Usable — best fit if this is ever picked up** |
| 5 | Distinct identity per breaking version (OS packages) | **Usable, but convention-only** — prevention, not a resolver mechanism, doesn't help already-colliding packages |
| 6 | Full-graph constraint solving (Cargo, Bundler) | **Not useful yet** — no payoff until pope has a multi-version registry |
| 7 | Non-fatal warning, proceed anyway (npm peerDeps) | **Usable, but likely wrong fit** — ABL has no runtime check to catch what "proceeding anyway" silently broke |

## The options

### 1. Physical side-by-side install (npm) — not usable for pope

Each package gets its own nested `node_modules/`, so two different
versions of the same package can genuinely coexist on disk at once.
Node's `require()` resolution walks up the directory tree looking in each
ancestor's `node_modules` in turn, so *which* version a given file gets
depends on where in the tree it's resolving from — there's no single
global answer, and there doesn't need to be one.

**Not available to pope.** PROPATH is one flat, ordered, session-global
list (see [propath-generation.md](../spec/propath-generation.md)) — there
is no per-directory scoping mechanism in ABL the way Node's `require()`
algorithm has. Two classes can't both be named
`example.greeter.Greeter` on the same PROPATH; exactly one wins. This is
already the stated reason `docs/spec/lockfile-format.md` rules out an
npm-style "install both" escape hatch — not a policy choice, a platform
constraint.

### 2. Versioned identity, side by side (C++ shared libraries) — not usable for pope

`.so`/`.dll` files can carry a version in their SONAME (e.g. `libfoo.so.1`
vs. `libfoo.so.2`), so multiple major versions can be installed on a
system simultaneously, and each consumer links against whichever it
needs by that distinct name.

This is really the same underlying trick as option 1 — give each version
a distinct *identity* (filename/symbol name in C++, directory position in
npm) so they never collide — just implemented at the linker level instead
of the filesystem-tree level. It hits the same wall for pope: ABL classes
are identified by their fully-qualified namespace path, so
`example.greeter.Greeter` can't be two different things at once on one
PROPATH unless the *package itself* encodes the version into its
namespace (e.g. `example.greeter.v1.Greeter` vs.
`example.greeter.v2.Greeter`). That's not something pope's resolver could
impose automatically — it would be a naming convention individual package
authors would have to opt into, and nothing currently enforces or even
suggests it.

### 3. Automatic conflict resolution — pick a winner (Maven/Gradle) — usable, but risky

Gradle's own dependency resolution (the thing
[ADR-0001](../decisions/0001-implementation-language.md) originally
wanted pope to reuse) doesn't error on a version conflict by default — it
picks the **highest requested version** among the conflicting
requirements and proceeds, only failing if something explicitly opts into
a "strict" constraint. Maven's default strategy is similar in spirit
("nearest wins" by tree depth, rather than highest, but the principle —
pick one automatically instead of failing — is the same).

**Compatible with pope's flat-PROPATH constraint** — it still produces
exactly one final answer, same as today's hard-fail behavior, just chosen
automatically instead of chosen by a human fixing the conflict by hand.
The risk: silent. A package can end up compiled against a version its
author never tested, with no error at all — the same class of bug the
include-collision finding behind
[ADR-0007](../decisions/0007-namespace-relative-includes.md) already
surfaced once in this project (originally worked through in the old
monorepo's `demo/exploration/consumer-app/WALKTHROUGH.md`, now captured in
that ADR's Context section).

### 4. Manual override (Yarn `resolutions` / npm `overrides` / pnpm `overrides`) — usable, best fit

A field in the top-level manifest that lets a human force the *whole
graph* to resolve a given package name to one specific version,
regardless of what individual dependencies asked for — e.g. "no matter
who asks for what range of `example.greeter`, use `1.0.0` everywhere."
The tool doesn't try to be clever; it just does what it's told, and the
override is explicit, visible in the manifest, and reviewable in a code
change like any other declared decision.

**Compatible with pope's flat-PROPATH constraint** (still one final
version) — and notably lower-risk than option 3, since nothing is
*silently* chosen. The human who added the override is explicitly taking
responsibility for verifying the forced version actually works for every
consumer, which is a real cost, but it's a visible, committed cost rather
than a hidden one.

### 5. Give breaking versions a genuinely different identity (OS package managers) — usable, convention-only

Debian/RPM-style systems generally don't support two versions of the same
package name installed system-wide at once either — their real answer to
a breaking change is usually to ship it under a **different package
name** (`libssl1.1` vs. `libssl3`, `python2` vs. `python3`), so there's
never actually a same-name conflict to resolve at the tooling level at
all.

This isn't a resolver strategy so much as an authoring convention that
sidesteps the problem — and it's the same underlying lesson pope already
adopted for a different problem: [ADR-0007](../decisions/0007-namespace-relative-includes.md)
(namespace-relative includes) exists precisely because giving two things
the same bare name and hoping resolution order sorts it out was the bug,
not the fix. The equivalent guidance here would be: a package making a
genuinely breaking change is encouraged to publish under a new
`package_name` (e.g. `example.greeter2`) rather than expecting the
resolver to reconcile two callers who fundamentally can't agree. Costs
nothing to implement (it's a recommendation, not a mechanism) but relies
on package authors actually following it.

### 6. Full-graph constraint solving instead of greedy per-edge walking (Cargo, Bundler, Poetry, pub) — not useful yet

`pope/resolver/DependencyResolver` currently walks the graph depth-first
and fails the moment one edge conflicts with an already-resolved choice —
the *order* dependencies happen to be declared in can determine whether a
resolvable graph gets found (see the "which one gets checked first"
discussion this doc followed on from). A real constraint solver instead
considers the whole graph's requirements together and searches for *any*
assignment of versions that satisfies every constraint, backtracking past
an early choice if it turns out to block a later one.

**Only actually matters once pope supports multiple versions per package
in the registry** — not implemented yet (see "Scope for v1" in
README.md). With today's one-version-per-package-name registry,
there is only ever one candidate to consider per name, so a solver and
the current greedy walk produce the identical result every time. Building
solver sophistication now would be solving a problem the registry can't
even pose yet.

### 7. Non-fatal warning, proceed anyway (npm `peerDependencies`) — usable, likely wrong fit

A different posture entirely: rather than the tool either hard-failing or
silently auto-picking, a dependency can be declared as only wanting
*some* version the top-level project already settled on — and if what's
actually resolved doesn't strictly satisfy that want, npm prints a
warning and proceeds anyway, leaving the judgment call ("is this actually
going to break?") to a human reading the warning, rather than encoding it
as a resolver decision at all.

Distinct from option 3 in an important way: option 3 still tries to
*pick* something automatically (highest wins); this option just *surfaces
the mismatch* and lets the build continue, betting that semver-adjacent
incompatibilities are often survivable in practice and a hard stop is
sometimes more disruptive than useful.

## Assessment

Options 1 and 2 don't fit ABL's platform constraints at all (no
per-directory or per-symbol scoping to exploit). Options 3, 4, 6, and 7
are all compatible with the flat-PROPATH constraint (they all still
produce exactly one final version); option 5 sidesteps the problem
entirely rather than resolving it.

Revised take, now that more options are on the table: **option 4 (manual
override) looks like the better fit than option 3 (auto-pick highest)**
if pope ever moves off hard-failing by default. It solves the same cases
option 3 would, without the silent-behavior risk — an override is
something a human wrote down and can be reviewed, not something the
resolver decided on its own. Option 5 (naming convention) costs nothing
and is worth documenting as author guidance regardless of what the
resolver itself ends up doing, the same way ADR-0007 documents the
include-naming convention. Option 6 (real solver) isn't worth the
investment until multi-version registry support exists — it would be
solving a problem that can't occur yet. Option 7 (warn-and-proceed) is
an interesting third posture but probably the wrong fit for pope
specifically: ABL doesn't have a mechanism to detect *at compile/run time*
whether a version mismatch actually broke something the way JS's dynamic
nature sometimes tolerates it, so "proceed anyway" risks being strictly
worse here than in the ecosystem it's borrowed from.

Not implemented. Not decided. Recorded here so the reasoning doesn't have
to be re-derived from scratch if this comes up again.
