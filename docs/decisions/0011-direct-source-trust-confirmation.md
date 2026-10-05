# 0011 — Confirm before installing a direct-source dependency

Status: accepted
Date: 2026-09-16

## Context

A `DependencySpec.DirectSource` dependency (`{repoUrl, ref}`) bypasses
every configured registry entirely — it's an arbitrary git repo named
directly in a manifest, possibly several levels deep in the transitive
graph, not something the installing user necessarily chose or even
knows about. Installing it silently would let a dependency's own
dependency point anywhere, with no visibility into what's actually
about to be fetched and resolved onto PROPATH.

## Decision

Before fetching a direct-source dependency not already approved,
`popeInstall` asks the user to confirm it via `TrustPrompt` — showing
the full path through the graph that pulled it in (`via: A -> B -> C`)
and the exact source (`repoUrl@ref`), through Gradle's own
`UserInputHandler` (the same internal API Gradle's own prompts like
`gradle init` use, rather than raw stdin). An approval is remembered in
`pope.lock`'s `trustedDirectSources` map, keyed by `packageKey` to
`repoUrl@ref` — so re-approving on every subsequent install isn't
needed, but a **different** `repoUrl`/`ref` for the same key (a
dependency's declared source changed) requires re-approval.

`-PpopeTrustAll` skips the prompt non-interactively (CI). With no
interactive input available and no `-PpopeTrustAll`, the prompt
declines rather than silently proceeding.

## Consequences

- A direct-source dependency's actual origin is always visible before
  it's fetched, not just after.
- Approval is sticky per exact source, not per package key alone — a
  dependency's source silently changing underneath an already-approved
  key re-triggers the prompt instead of trusting the old approval.
- CI/non-interactive runs need `-PpopeTrustAll` for any project with a
  direct-source dependency, or the install fails rather than hanging or
  silently proceeding.

## Alternatives considered

- **No confirmation at all** — rejected: a direct-source dependency can
  point at anything, including something injected several levels deep
  in a transitive graph the top-level consumer never directly declared.
- **Trust once per packageKey, regardless of source changing** —
  rejected: would let a dependency's source silently change (to a
  different repo or ref) without ever re-prompting, defeating the
  point of asking at all.
