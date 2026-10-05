# 0015 — Catalog references: version from the filename, ref independent

Status: accepted
Date: 2026-09-30

## Context

A catalog registry's reference file originally combined `version` and
derived its git tag as `v<version>` — which only worked for a package
tagged exactly that way. It couldn't point a published version at a
branch, an untagged commit, or any tag that doesn't look like
`v<semver>`.

A URL encoding the ref (e.g. GitHub's own `/tree/<ref>` browsing links)
was considered and rejected: it's GitHub-specific, not something
`git clone`/`fetch` understands without first parsing it back apart —
work git itself doesn't need — and a bare `pope install <name>` with no
version given needs a real, comparable semver to pick "the highest"
available; a raw ref alone (a branch name, a commit hash) isn't
comparable or orderable at all.

## Decision

The catalog folder's **filename** (`packages/<name>/<version>.json`) is
the one place a version lives — nothing inside the file needs to repeat
it. The file's content is `{repoUrl, ref}`, where `ref` is a genuinely
independent field: it can be a tag, a branch name, or a raw commit SHA —
whatever `GitPackageFetcher`'s `git worktree add` already accepts. A
missing `ref` is a hard, fail-loud error, with no silent fallback to a
default branch — resolving to "whatever `main` currently is" would make
a published version stop being a stable, reproducible pin.

`CatalogRegistry.fetchAndBuild` additionally verifies the fetched
package's own declared `version` (from its `openedge-project.json`)
actually matches the filename, catching a catalog/package disagreement
instead of silently trusting either side.

## Consequences

- Every version a registry has ever published for a package is just
  "which files exist in `packages/<name>/`" — no parsing needed to
  enumerate them.
- A package can be published at any ref git understands, not only a
  `v<version>`-shaped tag.
- A missing or wrong `ref` fails immediately and clearly, rather than
  resolving to an unpredictable default.

## Alternatives considered

- **GitHub-URL-encoded ref** — rejected, see Context.
- **Deriving `ref` as `v<version>`** — the prior, abandoned design;
  rejected for the reason in Context (can't express a branch, a raw
  commit, or a non-`v`-prefixed tag).
- **Keeping both a `version` field inside the file and the filename** —
  rejected as redundant; the filename alone is sufficient, and the
  inside-the-file copy was already unused dead weight.
