# OpenEdge platform research notes

Background facts that informed the ADRs. Not a design doc itself — see
`docs/decisions/` and `docs/spec/` for what was actually decided.

## PROPATH

- Searched in order, first match wins; libraries placed earlier in
  PROPATH resolve faster.
- Flat and session-global — no per-package isolated resolution like
  `node_modules` nesting.
- If PROPATH changes mid-session, libraries present on the old list but
  not the new one are closed; ones present on both stay open.
- Source: [Procedure Libraries and PROPATH](https://documentation.progress.com/output/ua/OpenEdge_latest/dpabl/procedure-libraries-and-propath.html),
  [Why and how to use a procedure library](https://knowledgebase.progress.com/articles/Article/P19568)

## Procedure Libraries (.pl)

- Most common way to bundle ABL code. Can contain r-code only, or a mix of
  r-code plus other source/resource files.
- A .pl with only r-code can be added directly to PROPATH. A .pl with
  other content types must be extracted to a directory first.
- Database artifacts (schema/structure/backup files) and application
  packages (WAR/OEAR/PAAR) are explicitly *not yet supported* as
  dependency types even in Progress's own official DevOps Framework
  tooling.
- Source: [Manage dependencies — OpenEdge DevOps Framework](https://docs.progress.com/bundle/openedge-devops-framework/page/Manage-dependencies.html)

## OO ABL packages

- Since OE 10.1, ABL supports classes. User-defined types are organized
  into packages: logical, dot-separated pathnames that map directly to
  physical directories under PROPATH.
- Class references must be qualified as `package-name.ClassName`.
- A class definition file cannot sit in a directory whose name contains a
  period — ABL interprets the component after the period as another
  directory level.
- A user-defined package name cannot start with `Progress` (reserved).
- Source: [Classes](https://documentation.progress.com/output/ua/OpenEdge_latest/gsgnp/classes.html),
  [Type-name syntax](https://documentation.progress.com/output/ua/OpenEdge_latest/pdsoe/PLUGINS_ROOT/com.openedge.pdt.langref.help/rfi1424920637647.html),
  [Using the CLASS construct](https://documentation.progress.com/output/ua/OpenEdge_latest/dvoop/using-the-class-construct.html)

## Existing tooling ecosystem (relevant to language/integration choices)

- PCT (Ant tasks for OpenEdge) — Java, considered the de facto standard
  by the community ("you should/must use it").
- The mainstream ABL language server (Riverside Software) — runs via a
  Java command line; dependency management is Maven-based.
- Progress's official OpenEdge DevOps Framework — Gradle-based (JVM).
- Community shops already use Ivy-resolved dependencies to hand-craft a
  PROPATH, compile against it, package output into .pl files, and publish
  to Artifactory — i.e., the workflow pope should slot into or formalize,
  not compete with.
- Newer/emerging community tooling shows some drift toward Rust
  (tree-sitter grammar, a from-scratch Rust LSP) and TypeScript (VS Code
  extension layer), but no existing precedent for Go specifically.
- `openedge-project.json` (used by vscode-abl) already carries `name`,
  `version`, and a `buildPath` propath entry intended for future
  dependency management — worth aligning with once pope's schema is
  stable (see docs/spec/manifest-schema.md).
- Source: [vscode-abl GitHub](https://github.com/vscode-abl/vscode-abl),
  [openedge-project.json discussion](https://github.com/orgs/vscode-abl/discussions/20),
  [awesome-openedge-abl](https://github.com/clement-brodu/awesome-openedge-abl),
  [ABL procedure library dependency resolution with Ant/PCT](https://community-archive.progress.com/forums/00019/11701.html)
