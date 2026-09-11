# 0004 — Source is the primary resolved artifact; compiled .pl is a secondary build output

Status: accepted
Date: 2026-08-11

## Context

Procedure Library (.pl) files are the standard way to bundle ABL code, but
they aren't a uniform artifact type: a .pl containing only r-code can be
added directly to PROPATH, while one containing other source or resource
types must be extracted to a directory first. Compiled r-code is also
OpenEdge-version- and often platform-sensitive, unlike source.

## Decision

pope resolves and locks **source** packages (`.cls`/`.i`/resources). A
package is portable across OE versions as source. Compiling to `.pl` is an
explicit, separate, opt-in step layered on top, parameterized by target OE
version — never something the resolver itself produces or depends on.

## Consequences

- pope avoids having to solve r-code cross-version/cross-platform
  compatibility as a v1 problem.
- Consumers needing compiled artifacts (e.g. for IP protection or startup
  performance) need an explicit `pope build --target-oe=<version>` style
  step, not implied by `pope install`.
- The registry (even the local-directory one in v1) must be able to serve
  source packages without assuming a specific OE version exists to
  compile them.

## Alternatives considered

Treating compiled `.pl` as the primary distributed artifact (closer to how
compiled binaries work in other ecosystems) — rejected because it would
tie every resolved dependency to a specific OE version at resolution time,
which source-based resolution avoids.
