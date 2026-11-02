# Database-dependent packages and pope

Research note. Describes why some ABL code only compiles with a database,
what the surrounding tooling does about it, and what pope could do. See
`docs/decisions/` for what pope has actually decided. Today that is
[ADR-0003](../decisions/0003-db-deps-declared-not-managed.md): DB-aware
packages are out of scope for v1 and no `db` field exists in the manifest.

Basis: Progress and Riverside public documentation read on 2026-10-22, plus
the pope docs in this repo. Statements about the `.df` file format and ABL
compiler behaviour that were not confirmed in a fetched page are marked
"unverified". Nothing here was tested against a real OpenEdge install.

## 1. The problem

A pope package is source on PROPATH (ADR-0004). Source that references a
database object cannot be compiled by the AVM unless a database with the
right schema is connected at compile time. Typical triggers (general ABL
knowledge, unverified against the language reference):

- `FOR EACH Customer`, `FIND`, `CREATE`, or any buffer on a database table.
- `DEFINE TEMP-TABLE ... LIKE Customer` or `LIKE-SEQUENTIAL`.
- `DEFINE BUFFER b FOR Customer`, `BUFFER-COPY` between table buffers.
- Field references such as `Customer.Name` in a `DEFINE VARIABLE ... LIKE`.

Consequence for pope: a consumer can resolve and install a DB-bound package
successfully, the files land on PROPATH, and the build still fails at compile
time (or the code fails at run time) because the consumer's connected schema
does not match what the package expects. ADR-0003 already records that pope
cannot guarantee a resolved graph is runnable for such packages.

The user-visible symptom is the part to fix first: the failure is far from
its cause, and nothing in the install output hints at it.

## 2. What the existing tooling does

### Compile time needs a connection

- Progress's AI coding assistant docs for `openedge-project.json` state that
  the AVM needs an active database connection with the correct logical name
  and aliases to compile.
- The Java-based language server does not connect to the database. It reads
  schema only from `.df` dump files and Progress recommends keeping the
  schema file in the code repository.
- DevOps Framework docs say projects that need database data require
  connection details during compilation. For CI they suggest a test database
  and single-user mode (`-1`) to avoid the cost of starting a database
  server.
- For DataServer targets (Oracle, SQL Server, ODBC), a schema holder
  database holds everything the compiler needs, and no connection to the
  real data source is required. This is a special case and not a general
  "compile from a dump" mode.

### Declaration in `openedge-project.json`

The project file already has a database section, owned by the extension:

```
"dbConnections": [
  {
    "name": "sp2k",
    "connect": "-db db/sports2020 -ld sp2k -RO",
    "schemaFile": "dump/sports2020.df",
    "aliases": ["sports"]
  }
]
```

- `name`: logical name of the connection.
- `connect`: connection parameters passed to OpenEdge. Environment specific
  (paths, hosts, ports, read-only flags).
- `schemaFile`: path to a `.df` dump, used by the language server.
- `aliases`: alternative logical names.

So a consumer project already says which databases it connects to and holds
a `.df` for each. A pope package that needs a database is making an
assumption about exactly these entries: a logical name, optionally aliases,
and a schema that contains certain tables and fields.

### Build side

- PCT (Riverside Ant tasks) has `PCTCreateDatabase` (empty database, or from
  a structure file, or a copy of an existing one), `PCTLoadSchema` (loads a
  `.df`, `srcFile` mandatory, `commitWhenErrors` controls failure) and
  `DBConnection`/`PCTConnection` (attributes include `dbName`, `paramFile`,
  `dbDir`, `singleUser`).
- The Progress OpenEdge DevOps Framework (Gradle) has a `LoadDBSchema` task,
  a `dbConnections` task that source-entry tasks depend on, and a global
  `abl { dbConnection { ... } }` block.
- Both give a way to build a throwaway database from a `.df` for CI.

### Dependency tooling does not cover it

The DevOps Framework dependency page states that dependencies on database
artifacts (schema file, structure file, backup file) and on application
packages (WAR, OEAR, PAAR) are not yet supported. The Riverside Maven design
(see [riverside-dependency-management.md](riverside-dependency-management.md))
ships compiled `.pl` libraries and says nothing about databases. Compiled
r-code is itself coupled to the schema it was compiled against (r-code
carries a CRC check for referenced tables, unverified here), so the problem
does not go away for binary distribution either.

Net: nobody in this ecosystem manages database dependencies. pope would not
be catching up, it would be deciding whether to go first.

## 3. What a DB-bound package needs, split by layer

Separating these is the key to a small design:

1. Declaration: "I need a database named X containing at least these
   tables and fields". Pure metadata, can live in the manifest.
2. Compile-time verification: is a matching database configured in the
   consumer project? Can be checked without OpenEdge installed if schema is
   read from `.df` text.
3. Provisioning: create the database, load the schema, migrate it. Needs an
   OpenEdge install, environment knowledge (paths, ports, auth) and policy.
   This is the large, risky part and ADR-0003 rightly rejected it.

pope can plausibly own layers 1 and 2 and leave layer 3 to PCT, Gradle or
the user.

## 4. Options for pope

Ordered by effort. These are suggestions, not decisions.

### A. Status quo plus honest documentation (smallest)

Keep ADR-0003. Add to user docs: what compiles without a DB, what does not,
and the recommended pattern of keeping DB access behind an interface in a
separate package so most packages stay DB-free. Do nothing in code.

- Pro: no risk, no schema change.
- Con: the failure stays silent at install time, and the DB-bound packages
  most teams actually have remain second-class.

### B. Declare the database requirement, never manage it (small)

Revive the intent of ADR-0003 with a pope-prefixed key (the `pope` prefix
rule from manifest-schema.md), for example:

```
"popeDatabases": {
  "sports": { "schemaFile": "db/sports.df" }
}
```

Key = logical database name the code expects. `schemaFile` points at a `.df`
shipped inside the package. This is the package's declared contract.

What pope does with it:

- `pope install` prints the databases required by the resolved graph, and
  from which package.
- pope compares them with the consumer's `dbConnections` (names and
  aliases) and warns when a required logical name is not configured. Warn,
  do not fail, consistent with how the tool version check (ADR-0014)
  behaves.
- Never touches `connect` strings.

Consumer config stays authoritative. pope only reads `dbConnections`; it
must not write it, because those entries hold environment specific
connection data and are owned by the extension. Whether `ManifestWriter`
preserves that array on write is the same round-trip question as in the
Riverside note, section 3.

### C. Verify schema compatibility from `.df` text (medium)

Add a small `.df` parser (the format is line oriented: `ADD TABLE`,
`ADD FIELD ... OF ... AS ...`, `ADD INDEX`, unverified in detail) and check
that the consumer's `schemaFile` for the matching logical name contains
what the package declared or shipped.

Two sub-variants:

- C1. Package ships a full `.df` and pope checks that the consumer schema is
  a superset (every table and field of the package schema exists with the
  same type). Simple, but over-strict if the package only uses a few tables.
- C2. Package declares a minimal requirement list (tables and fields,
  similar to a peer dependency range) and pope checks only those. Better
  semantics, more manifest surface, and an author burden to keep it accurate.
  A tool that derives the list from `.df` plus source scanning is a possible
  later step, probably too much.

Gives real value: a mismatch is reported at install time with the exact
missing field, instead of a compile error deep inside a transitive package.
Limits: the `.df` may be stale relative to the live database, and
`dbConnections.schemaFile` is optional. If it is absent, report "cannot
verify", never "ok".

Cross-package conflicts become possible once several packages declare the
same logical name: two packages that need different types for the same
field. Reuse the existing conflict machinery's reporting style (see
[version-conflict-resolution-strategies.md](version-conflict-resolution-strategies.md)),
but this is a schema diff problem, not a version problem, and should start
as a plain report.

### D. Lockfile awareness (small, depends on B or C)

`pope.lock` stores a content hash per package. If a package ships a `.df`,
it is already covered by that hash. What is missing is a record of which
database requirements were seen at lock time, so `pope install` in CI can
detect that the declared requirements changed. Low value until B exists.

### E. Provision a throwaway schema database for CI (large, opt-in)

A command or Gradle task that creates an empty database, loads the union of
declared `.df` files and exposes a `connect` string, so that CI can compile
DB-bound packages without a shared dev database. PCT and the DevOps
Framework already have the building blocks, so the likely right shape is
documentation and a recipe, not a pope feature. If pope ever does it, it
needs an OpenEdge install, which pope otherwise does not require, and it
would break the "plain git, no server" positioning. Recommend against until
there is a concrete user.

## 5. Recommendation

1. Do A now. It is documentation only and removes the "is this a bug?"
   confusion that ADR-0003 predicted.
2. Plan B as the first code step, and write a new ADR that supersedes the
   "rejected for v1" status of ADR-0003 with the narrower rule: declared and
   checked, never provisioned. The old `db: none | required` flag is
   probably unnecessary, because absence of `popeDatabases` already means
   "no database".
3. Treat C2 as a follow-up once B has been used on real packages. Do not
   build the `.df` parser before there is a real package to test it on.
4. Do not build E. Document the PCT and DevOps Framework recipe instead.

Why this order: B gives the install-time signal that is currently missing for
very little code, touches only the manifest reader and one report step, and
cannot damage a project. C adds correctness at the price of a parser and an
accuracy burden on package authors.

## 6. Touchpoints in pope

For estimating the work (not yet checked line by line against source):

- `Manifest`, `ManifestReader`, `ManifestWriter`: new optional key
  `popeDatabases`, preserved on write like other unknown keys.
- A reader for the consumer's `dbConnections` (read only).
- Resolver/install report: collect declarations across the resolved graph.
- A new check next to the existing version and namespace checks, emitting
  warnings.
- Docs: `docs/spec/manifest-schema.md` and a new ADR. A functional test with
  a project that contains both `dbConnections` and `popeDatabases`.
- `popeToolVersion` major-mismatch behaviour (ADR-0014) applies unchanged to
  the new key.

## 7. Open questions

- What does the AVM do when the consumer's logical name differs from the one
  the package expects but an alias maps it? Aliases are created with
  `CREATE ALIAS` at run time and `dbConnections.aliases` is documented, but
  compile-time alias resolution rules were not confirmed.
- Is `dbConnections.schemaFile` mandatory in practice, or only for the
  language server? Progress docs describe it as used for completion.
- Should a package be allowed to depend on a database that only a sibling
  package provides (for example a "schema" package that contains only the
  `.df`)? That would make the `.df` a normal pope dependency and avoid a new
  concept, but it blurs "source is the primary artifact" and needs a rule
  for where the file lands in `pope_packages/`.
- Multiple databases and schema holders: is one logical name per package
  enough, or do packages need several?
- Does Riverside's extension tolerate an unknown top-level key next to
  `dbConnections`? It tolerated the renamed `popeDependencies`, so likely
  yes, but a collision with a future extension key is the same risk that
  motivated the `pope` prefix.
- Which real packages (the team's own, not the demo ones) would exercise
  this? A design without one is a guess.

## Sources

- [Configure database connection, OpenEdge AI coding assistant (12.8)](https://docs.progress.com/bundle/openedge-ai-coding-assistant-128/page/Configure-database-connection.html)
- [Manage dependencies, OpenEdge DevOps Framework](https://docs.progress.com/bundle/openedge-devops-framework/page/Manage-dependencies.html)
- [Compile source code, OpenEdge DevOps Framework](https://docs.progress.com/bundle/openedge-devops-framework/page/Compile-source-code.html)
- [LoadDBSchema, OpenEdge DevOps Framework](https://docs.progress.com/bundle/openedge-devops-framework/page/LoadDBSchema.html)
- [Compiling ABL procedures (DataServer schema holder)](https://docs.progress.com/bundle/openedge-oracle-dataserver/page/Compiling-ABL-procedures.html)
- [PCTCreateDatabase, Riverside PCT wiki](https://wiki.rssw.eu/pct/PCTCreateDatabase)
- [PCTLoadSchema, Riverside PCT wiki](https://wiki.rssw.eu/pct/PCTLoadSchema.md)
- [DBConnection, Riverside PCT wiki](https://wiki.rssw.eu/pct/DBConnection.md)
- [04 OpenEdgeProject, Riverside workshop](https://wiki.rssw.eu/workshop/04-OpenEdgeProject)
