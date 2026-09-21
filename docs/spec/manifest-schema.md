# Manifest schema

Status: implemented — single-file design: pope-specific keys are added
directly to `openedge-project.json` rather than a separate `pope.json`.
The key names/types below are what `pope.manifest.Manifest` /
`ManifestReader` / `ManifestWriter` actually read and write today; the
open questions at the bottom are the only parts still unsettled. Depends on
[ADR-0002](../decisions/0002-oo-abl-only-v1.md) (OO ABL only) and
[ADR-0004](../decisions/0004-source-as-primary-artifact.md) (source as
primary artifact) staying as accepted.

## File

`openedge-project.json` — the vscode-abl ecosystem's existing per-project
config file, already carrying `name`, `version`, `oeversion`, and
`buildPath`. One per package, at the root of the package's directory tree
(the directory that itself represents the top of the OO ABL package
namespace, per [ADR-0002](../decisions/0002-oo-abl-only-v1.md)).

## Why one file instead of a separate pope.json

A prior draft of this spec kept pope's manifest in its own `pope.json` to
avoid coupling early iteration to a file format owned by another project.
That's no longer the plan: one file is preferable to two as long as it
works correctly (no field collisions), and prototyping shows it can.

Two of `openedge-project.json`'s existing fields were checked for
collision before deciding this:

- **`name`** — the extension's existing `name` is a free-form project
  label (e.g. `"calculator-package"`), but pope requires this value to
  exactly equal the OO ABL package namespace (e.g. `"example.calculator"`)
  for dependency resolution to work (a consumer's `popeDependencies` map is
  keyed by this value). These are genuinely different constraints on the
  same field name, so pope does **not** reuse `name` — it adds a separate
  `popePackageName` key instead, leaving the extension's `name` untouched.
- **`version`** — no equivalent conflict found; both the extension and
  pope mean "this package's version." Reused as-is, one field, no new key.

A real collision surfaced later: an unprefixed `dependencies` key (the
original name of what's now `popeDependencies`) collided with a
`dependencies` shape the vscode-abl extension's own schema expects,
breaking its language server on any project carrying pope's dependency
map. Fixed by renaming to `popeDependencies`, and — since a schema this
opaque can't be checked exhaustively for future collisions either — every
new key pope adds to this file is now prefixed with `pope_` on the same
principle, `popePackageName` included, even though `package_name` itself
was never observed to collide.

## Fields

| Field | Owner | Notes |
|---|---|---|
| `name` | vscode-abl extension (existing) | Free-form project label. Not read by pope — do not assume it matches the namespace. |
| `version` | shared (existing) | Semver. Reused directly by pope; no separate version field. |
| `oeversion` | vscode-abl extension (existing) | Target OpenEdge version. May end up doing double duty for a pope min-version check — not confirmed, see open questions. |
| `buildPath` | vscode-abl extension (existing) | Ordered list of `{type, path}` entries. `type: "source"` is what pope reads as this package's own source roots (`Manifest.sourceRoots`) — pope's generated entries for resolved dependencies are also always `"source"`, added into this same list (see [propath-generation.md](propath-generation.md) for the open question on ordering and on warning when a generated entry would shadow one already present here). `buildPath[0]` of type `"source"` also serves as `package_root` — see below, no separate field. `type: "test"` (`Manifest.testRoots`, decided 2026-09-03) is only ever read for *this* manifest's own PROPATH — `pope propath --tests` includes it, plain `pope propath` doesn't — and is never read at all when this manifest belongs to a dependency being fetched (`GitPackageFetcher`/`CatalogRegistry` only ever look at `sourceRoots`), so a dependency's own test folders can never end up copied into a consumer's `pope_packages/` or referenced on a consumer's `buildPath`, regardless of the flag. Any other `type` value is read by nothing and simply ignored (forward-compatible with a future type, e.g. resources/images, which would need its own decision on whether/how it appears on PROPATH — not decided yet). |
| `popePackageName` | pope (new) | The OO ABL package namespace this manifest describes (e.g. `example.calculator`). No leading period-containing directory components (language constraint). Cannot start with `Progress` (reserved). Uniqueness within a single registry directory is enforced at resolve time (`LocalDirectoryRegistry.findAny` fails loudly if two folders declare the same `popePackageName`) — not enforced across separate registries. Independent of whatever key a *dependent* package uses to reference this one (see `popeDependencies` below) — a package's own `popePackageName` never needs a registry's routing prefix baked in. |
| `popeDependencies` | pope (new) | Map of dependency key → spec, one of two shapes (decided 2026-08-25, see `pope.manifest.DependencySpec`): <br>**Registry** — a plain caret-range string (`"ba.calculator": "^1.0.0"`, unchanged since 2026-08-13). The key is normally a fully-qualified, registry-routed name (prefix included) — `pope.registry.PrefixRoutingRegistry` routes it by longest matching configured prefix. Alternatively, the key can be `"registryName/localName"` (new) to bypass prefix-based routing entirely and resolve directly against the registry with that declared name (its DSL/properties-file label, not its prefix), using its own local name — the real prefixed name is reconstructed internally (`registry's prefix + localName`) before delegating, so the resolved package still lands under its real prefix in `pope_packages/`, same as the implicit form. This mirrors the `popePackageName` row above: just as a package's own `popePackageName` never needs a routing prefix baked in, a *dependent* referencing it explicitly by registry doesn't need to know that prefix either. Registry names must be unique across `registries{}` and `pope-registries.properties` combined (enforced in `PopePlugin.kt`'s `buildRegistry()`) — otherwise which registry `registryName/` refers to would be ambiguous. <br>**DirectSource** — `{ "repoUrl": "...", "ref": "v1.0.1" }` (new). Fetched by a plain git clone, no registry lookup at all — lets a package depend on another without either side needing a catalog entry. Always keyed by its own bare declared key, root-level or transitive — no inherited prefix (tried, then dropped 2026-08-26: inheriting the enclosing package's prefix made two *differently-routed* packages sharing the exact same direct-source dependency — e.g. a `ba.`-routed and a `cw.`-routed package both depending on the same `greeter` repo/ref — trip the `popePackageName`-collision check as a false positive, since they'd land on different inherited keys despite being the identical dependency. Keeping the bare key instead means they land on the *same* key and get correctly deduped by `DependencyResolver`'s existing repoUrl/ref-equality check, while two direct-source deps that are genuinely different things sharing a bare name are still caught — by that same repoUrl/ref mismatch, which is more precise than a namespace-based check). |

`db` was previously sketched as a field here (`none`/`required`) but is
dropped entirely — [ADR-0003](../decisions/0003-db-deps-declared-not-managed.md)
(the ADR that would have justified it) is rejected for v1. DB-aware
packages are out of scope, not merely unmanaged, so there is nothing for
this field to flag.

`abl.min_oe_version` from the earlier draft is also dropped for now,
pending confirmation of whether it would just duplicate the existing
`oeversion` field (see open questions).

`package_root` from the earlier draft is dropped as a separate field
(decided 2026-08-13) — it's derived from `buildPath[0].path` instead,
since every example built so far has them identical, and a derived value
can't drift out of sync with `buildPath` the way a second stored field
could.

## Example

```json
{
  "name": "consumer-app",
  "version": "1.0.0",
  "oeversion": "12.8",
  "popePackageName": "example.consumer",
  "popeDependencies": {
    "example.calculator": "^1.0.0"
  },
  "buildPath": [
    { "type": "source", "path": "src" },
    { "type": "source", "path": "pope_packages/example.calculator/src" }
  ]
}
```

## Open questions

- Whether to support a `devDependencies`-equivalent (e.g. ABLUnit test
  packages) separately from runtime dependencies.
- Whether `oeversion` can double as the min-OE-version check the earlier
  `abl.min_oe_version` draft field was for, rather than adding a new field.
- Whether scope stays OO-ABL-only ([ADR-0002](../decisions/0002-oo-abl-only-v1.md))
  — if that changes, `popePackageName`'s namespace-directory semantics here
  would need rework, not just a footnote.
- Whether source is really the right primary resolved artifact
  ([ADR-0004](../decisions/0004-source-as-primary-artifact.md)) — affects
  whether this manifest ever needs a compiled-artifact-adjacent field.
- `popePackageName` uniqueness is now enforced within a single registry
  directory: `LocalDirectoryRegistry.findAny` fails loudly if two folders
  both declare the same `popePackageName`, rather than silently picking
  whichever one the filesystem happens to list first. Still open: this
  only catches the collision within one `registryRoot` — nothing stops
  two *separate* registries (or a registry and a hand-copied
  `pope_packages/` entry) from independently claiming the same
  `popePackageName`.
