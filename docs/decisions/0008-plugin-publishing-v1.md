# 0008 — Publishing the plugin, minimally

Status: accepted
Date: 2026-08-24

## Context

[ADR-0006](0006-pope-component-scope-v1.md) marked "Publisher" (#9) out of
scope for v1: "no registry worth publishing to yet." That was true at the
time — everything lived in one monorepo, and every consumer applied the
plugin via `includeBuild("<path to your clone>")`.

That's no longer sufficient. The plan is now to split this one repo into
three: the `pope` tool itself, a registry repo holding installable
packages, and a demo repo. `includeBuild` takes a literal filesystem
path — it only works when a consumer's clone of the tool repo happens to
sit at a path they've hardcoded, which breaks the moment the tool lives
in a separate repo from its consumers. Consumers need to reference the
plugin by **coordinate** (`id("io.github.balticamadeus.pope") version
"x.y.z"`), not by path.

## Decision

Publish the plugin using Gradle's built-in `maven-publish` plugin,
targeting a plain **git-repo-hosted Maven repository** — a folder laid
out in standard Maven format, committed to a git repo (most likely this
same repo, or a small dedicated one), not a hosted package registry
service (GitHub Packages, GitLab Package Registry). This needs no new
account, token, or infrastructure — just files in a repo already
reachable the same way this repo is.

The actual repository URL is a Gradle property (`popePublishRepoUrl`),
defaulting to a local disposable folder (`build/local-maven-repo`) so
`./gradlew publish` works out of the box without deciding final hosting
up front.

`java-gradle-plugin` (already applied) plus `maven-publish` together
auto-generate the plugin marker publication — the small artifact that
lets a consumer resolve by plugin id instead of raw group:artifact
coordinates — with no extra configuration needed.

Proven via a new functional test
(`PublishedPluginFunctionalTest.kt`) that applies the plugin to a
throwaway consumer project by id + version only — no `includeBuild`, no
TestKit `withPluginClasspath()` shortcut — resolving from `mavenLocal()`
(standing in for "some Maven-format repo," since the mechanism is
identical regardless of which one).

## Consequences

- Consumers gain a second valid way to apply the plugin (by version, via
  a configured Maven repository) alongside the existing `includeBuild`
  path — `includeBuild` remains the right choice when actively developing
  `pope` itself, not for consuming a released version.
- The project needs an actual versioning discipline once this is used for
  real (currently a single fixed `0.1.0-SNAPSHOT`) — not built yet, kept
  a manual `version =` bump for now rather than adding release
  automation prematurely.
- Still explicitly deferred, unchanged from ADR-0006: multiple registry
  repos at once, a fetch/cache layer for remote registries, and hosted
  package-registry services (GitHub/GitLab Packages) with their
  authentication requirements.

## Alternatives considered

- **GitHub Packages / GitLab Package Registry** — real hosted Maven
  registries, but need per-consumer auth token setup, which adds friction
  disproportionate to what a 3-repo split actually requires right now.
  Worth revisiting once auth infrastructure exists for other reasons.
- **`mavenLocal()` as the real distribution mechanism** — rejected: it's
  per-machine (`~/.m2`), so publishing there on one machine does nothing
  for a colleague on another. Only useful as this ADR's test mechanism,
  not as the actual answer to "where do other people get the plugin."
