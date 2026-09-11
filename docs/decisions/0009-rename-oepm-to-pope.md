# 0009 — Rename oepm to pope, move to org ownership

Status: accepted
Date: 2026-09-11

## Context

The tool is moving from a personal GitHub account (`erudys27/oepm-tool`)
to an org-owned repo under BalticAmadeus. The org-provided repo is named
`pope`, not `oepm`/`oepm-tool`. Continuing to call the tool `oepm` while
it lives at a `pope`-named repo would leave the CLI command, task names,
plugin coordinate, and on-disk artifact names permanently out of sync
with the project's actual name.

## Decision

Renamed the project throughout this repo, from `oepm` to `pope`:

- The CLI command word, all Gradle task names, and Gradle project
  property (`-P`) names.
- The Gradle extension DSL block name and the plugin coordinate: group
  `io.github.erudys27` → `io.github.balticamadeus`, id
  `io.github.erudys27.oepm` → `io.github.balticamadeus.pope`,
  `implementationClass` `oepm.OepmPlugin` → `pope.PopePlugin`.
- Kotlin package (`oepm` → `pope`) and class names (`OepmPlugin` →
  `PopePlugin`, `OepmExtension` → `PopeExtension`).
- On-disk artifact names used by every consuming project: `oepm.lock` →
  `pope.lock`, `oepm_packages/` → `pope_packages/`,
  `oepm-registries.properties` → `pope-registries.properties`, the
  `.oepm/` scaffold subfolder → `.pope/`.
- CLI script filenames (`oepm`/`oepm.bat`/`oepm-init`/`oepm-init.bat` and
  their `cli/` counterparts) and their content.
- Scaffold templates under `scaffold/templates/`, and their token names
  (`{{OEPM_TOOL_PATH}}` → `{{POPE_TOOL_PATH}}`).
- Documentation: `README.md`, `HANDOVER.md`, `docs/spec/`,
  `docs/research/`, and all ADRs except `0005-naming.md` (see below).

This is the project's second rename; the first (`ppm` → `oepm`) is
recorded in ADR-0005, which is left untouched as an accurate record of
that decision at the time it was made, rather than retroactively rewritten.

Scope stayed narrow otherwise: `registry-ba`, `registry-cw`,
`calculator`, `logger`, `greeter`, and the demo repo
`openedge-package-manager` all stay under `erudys27`, as repos, unmoved
and unrenamed — except that the demo repo's `build.gradle.kts` and its
`.oepm`-family on-disk artifacts needed migrating too, since it consumes
this plugin and there was no backward-compat exception carved out for
already-existing consumer projects.

Clean break: no redirect or deprecation note was left in the old
`erudys27/oepm-tool` repo. Full git history (all commits, branches, tags)
was preserved across the move.

## Consequences

- New plugin coordinate for anyone applying it by id/version:
  `io.github.balticamadeus.pope`.
- Every existing consumer project (including the demo repo) needed a
  one-time migration pass to pick up the renamed on-disk artifacts and
  plugin id — this isn't backward compatible with the `oepm`-named
  layout.
- The old `erudys27/oepm-tool` repo's fate (delete vs. leave inactive) is
  a separate, still-open decision, not addressed by this rename.

## Alternatives considered

- Keep the tool named `oepm` and just change the repo/remote URL,
  leaving the CLI command, task names, and on-disk artifacts as `oepm`
  regardless of the new repo's name. Rejected: would leave the tool's own
  name permanently inconsistent with the repo it lives in.
- Rename the code/CLI to `pope` but leave already-existing on-disk
  artifact names (`oepm.lock`, `oepm_packages/`, etc.) as a
  backward-compat exception. Rejected in favor of a full, consistent
  rename, accepting the one-time migration cost for existing consumer
  projects.

See ADR-0005 for the project's prior naming history (`ppm` → `oepm`).
