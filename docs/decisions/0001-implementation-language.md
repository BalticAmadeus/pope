# 0001 — Implementation language

Status: accepted
Date: 2026-08-11, updated 2026-08-12

## Context

The actual compilation of ABL source to r-code always requires shelling out
to the real AVM (`_progres`/`prowin` batch mode) — no implementation
language avoids that. The language choice only governs the CLI, the
manifest/lockfile handling, the dependency resolver, the PROPATH generator,
and the registry client.

Two realistic candidates were compared:

| | JVM (Java/Kotlin) | Go |
|---|---|---|
| Already on ABL dev machines | Very likely yes — PDSOE is Eclipse-based (needs a JVM), the mainstream ABL language server requires Java. If those are already IT-approved and installed, JVM likely needs **no new admin request**. | No — needs a fresh install requiring IT admin rights. |
| Reuses existing dependency-resolution engine | Yes — can build on Ivy/Gradle's mature resolver instead of writing one from scratch. | No — resolver has to be built and hardened from zero, the highest-risk part of the whole project. |
| Integrates into existing OpenEdge build pipeline | Yes — could be a Gradle plugin extending Progress's own DevOps Framework, or slot alongside PCT/Ant. | No — sits beside the build as a separate binary, shells out to the AVM directly. |
| Distribution to end users | Needs a JVM present wherever `pope` runs, not just where it's developed. | Single static binary, zero runtime dependency once you have it. |
| Team contribution barrier | Higher ceremony (build.gradle, classpath, etc.), but the team may already know Java from other tooling. | Lower ceremony, easier for ABL devs without JVM background to read/modify. |
| Cross-compilation for Windows (majority ABL platform) | Works, but less trivial than Go's `GOOS=windows go build`. | Trivial. |

A third candidate, Rust, was considered early on (standalone binary,
some community precedent) but dropped from serious comparison: it shares
Go's toolchain-install and from-scratch-resolver downsides without Go's
lower contribution barrier for a team of ABL developers.

## Decision

Implement pope on the JVM, in Kotlin, as a Gradle plugin — decided
together with the team after reviewing the trade-offs above. The
zero-install advantage on existing ABL dev machines and the ability to
reuse Gradle's own dependency-resolution engine directly outweighed the
standalone-binary simplicity Go offered.

Building as a Gradle plugin (rather than a standalone JVM CLI) is what
makes reusing Gradle's resolver straightforward — pope hooks into Gradle's
existing dependency-resolution APIs instead of reimplementing or
wrapping them from outside. This also settles the shape question left
open in the previous version of this ADR.

## Consequences

- Integrates into existing Ant/PCT/Gradle build pipelines, unlike a
  standalone binary — this was a deciding factor.
- Reuses Gradle's dependency-resolution engine directly instead of
  building a version-range solver and conflict resolution from scratch,
  lowering the highest-risk part of the project.
- Requires Gradle (and a JVM) to be present wherever pope runs — not just
  on dev machines with PDSOE/the language server. This ties pope's
  distribution and UX to Gradle's, and needs addressing for any
  Gradle-less environment (e.g. some CI setups).
- Cross-compilation for Windows is less relevant now than under a
  standalone-binary approach — Gradle/JVM handles platform portability
  itself.

## Alternatives considered

Go — rejected: no free dependency-resolution engine to reuse, and no
existing JVM install to depend on.

Rust — rejected early: same downsides as Go without Go's lower
contribution barrier for the team.
