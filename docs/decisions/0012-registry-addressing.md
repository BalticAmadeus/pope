# 0012 — Addressing a package: prefix routing, explicit registry/name, bare-name search

Status: accepted
Date: 2026-09-18

## Context

With multiple registries configurable at once (each with its own
routing prefix, e.g. `ba.`, `cw.`), three real situations came up that
plain prefix routing alone doesn't cover:

- A package's local name in its catalog doesn't start with any
  configured prefix (prefixes are a routing convenience, not part of
  the package's real identity), so there needs to be a way to pick a
  registry explicitly.
- A user typing a bare local name (`pope install Util`) has no reason to
  know or type which registry it lives in.
- A typo in any of the above (a prefix, an explicit registry name, or a
  bare local name) should get a helpful correction, not just a bare
  failure.

## Decision

Three ways to address a package, layered so the common case stays
simple:

1. **Prefix-routed** (`"ba.Util"`) — routes to whichever configured
   registry's prefix the name starts with (longest match wins).
2. **Explicit `registryName/localName`** (`"ba/Util"`) — bypasses prefix
   matching entirely, selecting a registry by its own configured name.
   A declared dependency written this way always re-resolves against
   that exact registry, regardless of prefix.
3. **Bare name** (`pope install Util`, no prefix or registry) — searches
   every configured registry (`Registry.hasAny`, metadata-only, no
   fetch) for an exact local-name match; auto-installs on exactly one
   match, prompts to choose on more than one. A non-interactive run
   (e.g. CI) picking the first option deterministically rather than
   blocking is accepted as the only practical behavior TestKit/CI can
   exercise for this prompt.

A typo gets a "did you mean X?" suggestion at **every** level above
— a bad prefix, a bad explicit registry name, and a bad bare local
name each get DidYouMean's closest match, folded into a yes/no
confirmation rather than a bare failure.

Once a bare name resolves (to one match, or a chosen one of several),
its declared dependency key is rewritten to the explicit
`registryName/localName` form in the manifest — so a second install run
doesn't repeat the cross-registry search for something already pinned.

## Consequences

- A package's own catalog local name never has to double as (or be
  constrained by) any registry's routing prefix.
- `pope install <bare name>` works the way most package managers'
  install command does, without requiring the user to know which
  registry something lives in up front.
- Once resolved, a dependency's key in the manifest is unambiguous and
  stable — no repeated cross-registry search on every subsequent
  install.
- A registry name and a registry prefix are two independent pieces of
  config that can both collide — declaring the same name or the same
  prefix twice (DSL + properties file, or twice in one) is a loud,
  explicit error, not a silent pick.

## Alternatives considered

- **Prefix routing only**, no explicit or bare-name addressing —
  rejected: forces every package's local name to already start with a
  configured prefix, which isn't true for every real catalog.
- **Silently picking the first match** on a bare name found in multiple
  registries — rejected: non-deterministic in spirit even if the
  current CI fallback happens to be deterministic in practice; an
  interactive run should always get to choose.
