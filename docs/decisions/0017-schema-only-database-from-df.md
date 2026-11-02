# 0017 - Schema-only databases built from `.df` files

Status: proposed
Date: 2026-11-02

## Context

ABL code that references a database table cannot compile unless a database
with that table is connected. A consumer can resolve and install a DB-bound
package successfully, and the build still fails later at compile time, far
from the cause. [ADR-0003](0003-db-deps-declared-not-managed.md) put
database-aware packages out of scope for v1 and rejected provisioning a
database ("diffing `.df` files, provisioning temp DBs"). The background and
the options considered are in
[database-dependent-packages.md](../research/database-dependent-packages.md).

A colleague proposed a different approach in a call:

- A package ships a `.df` (schema definition) file.
- pope builds an empty, read-only database from it, so the package compiles
  without a real database.
- The database could itself be treated as a package, so it gets version
  control like any other dependency.

More detail on the idea was promised in a file that has not arrived yet, so
this ADR records only what has been checked, and leaves the rest open.

What was checked by hand and then in a spike (`popeDbBuild`), with OpenEdge
12.8.9 on Windows:

- Copying OpenEdge's own `empty` database with `procopy`, then loading a `.df`
  with `prodict/load_df.p` in a single-user batch session, gives a database
  with the schema and no data. It takes a few seconds.
- A class that does `FIND Customer ...` fails to compile with no database
  ("Unknown or ambiguous table") and compiles against that database. It also
  compiles when the database is connected read-only (`-1 -RO`). The database
  only needs to be writable while the schema loads.
- Two `.df` files can be loaded into one database, and a class using tables
  from both compiles.
- Two separate databases can be connected at the same time, and a class using
  a table from each compiles. With only one of them connected it fails.
- Loading a second `.df` that defines a table which already exists exits with
  code 0, reports nothing, and keeps the first definition. The second file's
  fields are ignored without any message.
- A `.df` trailer with a wrong byte count (`0000000000`) is accepted.
- A schema dumped from a real database does not load as it is. OpenEdge's own
  `sports2020` sample (25 tables, 211 fields, 12 sequences) names the storage
  areas `Data Area`, `Index Area` and `LOB Area`, which the empty database does
  not have. The loader then reports "AREA NAME either not found" in a file
  called `<database>.e`, loads nothing at all, and still exits with code 0.
  After removing the `AREA` and `LOB-AREA` lines from a copy of the file, all
  25 tables, 211 fields and 12 sequences load, and a class using real tables, a
  sequence (`NEXT-VALUE`), a `LIKE` temp-table and a buffer compiles against
  the result.

Not checked: other operating systems, `.df` paths containing spaces, timing on a
large schema, and schemas that use table triggers, views or multiple tenants.

## Decision

Proposed, and deliberately narrow:

1. pope may build a schema-only database from `.df` files, using OpenEdge's own
   tools (`procopy` of the `empty` database, then `prodict/load_df.p`), to give
   the ABL compiler something to compile against. The database holds no
   data, and the intended connection is single-user and read-only.
2. pope still never connects to, migrates, or manages a real database, and
   never writes connection settings for one. This replaces only the
   "never creates" part of ADR-0003, and only for throwaway schema-only
   databases.
3. A table may come from one schema file only. If two files in the same build
   define the same table (compared case-insensitively), pope fails before
   loading, naming both files, because OpenEdge would otherwise silently keep
   the first definition.
4. A build only counts as successful if the load program reaches its end and
   OpenEdge left no error file (`<database>.e`) behind. Exit code 0 alone is
   not enough. Otherwise pope fails and shows the first lines of that file.
5. Storage areas (`AREA`, `LOB-AREA`) are removed from a copy of each `.df`
   before it is loaded. They only say where data is stored on disk, which a
   database with no data does not need. The original file is not changed, and
   its bytes are otherwise kept as they are. The removal is a line match, so an
   area name written across several lines would not be caught.
6. OpenEdge is located through the `DLC` environment variable, or an explicit
   `-PpopeDlc`. Without one, pope fails with a message saying how to set it.
   pope does not otherwise require OpenEdge, and every other command keeps
   working without it.
7. The only code for this today is an opt-in, experimental `popeDbBuild` task
   (`-PpopeDbName`, `-PpopeDbSchema`, optional `-PpopeDbDir`). It is not
   called by `popeInstall`, and there are no new manifest keys.

Not decided here, and left for a later ADR once the colleague's input exists:

- **How does a package hand over its `.df` file?** The package needs a way to
  say "my schema is here". It could be a new key in `openedge-project.json`, or
  a fixed folder name such as `db/`, or something else.
- **Is the database a package of its own?** The idea is that a schema gets a
  version number like any other dependency. If so, what counts as a new
  version? Adding a field is probably a small change, and removing one a big
  change, but nobody has decided that yet.
- **One database, or several?** A package might need one database, or a few
  with different names. Both compile, but pope has to pick one way to work.
- **When is the database built, and where is it kept?** It could be built
  during `popeInstall`, or only when the user asks for it. Either way, it needs
  a folder to live in.
- **What if the project already has a real database?** The real one might use
  the same name that the package expects. Then pope must not build a second one
  under that name on top of it.

## Consequences

- DB-bound packages could be compiled in CI or on a clean machine without a
  shared development database, once the missing design above exists.
- pope gains its first dependency on an OpenEdge installation, although only
  for this one opt-in task.
- The tests that use real OpenEdge only run where `DLC` is set. CI runners have
  no licensed OpenEdge, so they skip those, and the build logic is covered
  there only through a fake command runner. A change that breaks the real
  commands can pass CI.
- Only Windows has been verified. The Linux tool names in the code are guesses.
- The "same table twice" rule is a limit on how schema packages can be split.
  Two packages that each add fields to one table would not work.
- If accepted, ADR-0003 becomes partly superseded: declaring is still allowed,
  and creating a schema-only database is allowed, but managing a real one stays
  out.

## Follow-ups to consider later

Not needed for the first version, and not checked yet. Each one could make a
real schema behave differently from the ones tried so far:

- **Table triggers.** A real schema can attach triggers to tables and fields,
  and a trigger names a procedure that has to exist. OpenEdge ships
  `sports2020trgs`, a sample with triggers, to try this on.
- **Views and SQL objects**, such as SQL views, stored procedures and
  constraints, which a plain `.df` may contain.
- **Multi-tenancy and partitioned tables**, which need extra setup in the
  database itself.
- **Other codepages.** The sample schema used ISO-8859-1. UTF-8 and others
  are untested.
- **Large schemas.** Only a 25-table schema was timed, so the cost of a
  schema with hundreds of tables is unknown.
- **Linux.** The tool names are guesses.
- **Paths with spaces** in the `.df` location.
- **Several real schemas loaded together**, and what to do when they overlap.

## Alternatives considered

- **Declare only, never build** (option B in the research note) - a `popeDatabases`
  key that pope reads and compares with the consumer's `dbConnections`, with
  warnings only. Smaller and needs no OpenEdge, but it only reports the
  problem, and does not let anything compile. It is compatible with this ADR
  and could be done first or alongside.
- **Check compatibility by parsing `.df` text** (option C) - report the exact
  missing field at install time without OpenEdge. More precise, but needs a
  `.df` parser, and the package author has to keep the requirements accurate.
- **Provision a full development database for CI** (option E) - rejected here
  for the same reasons as in the research note: the scope is large and it
  needs environment knowledge (paths, ports, data). Only a schema-only
  database with no data is proposed.
- **Treat the `.df` as an ordinary source package** with no new concept - open.
  It reuses versions, the lockfile and integrity checks, but the `.df` is not
  source on PROPATH, and needs a rule for where it is installed.
