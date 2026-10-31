<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# The Drishti database: one store for everything Drishti owns

Status: **design, not built** (`DrishtiDatabase`, `config/db/database.yaml`, the stamp and `db copy`); the write-back tables are built
(2.8.0, 2026-10-25) in the two existing complete schema files, on the identity database's pool
([WRITE_BACK.md › The design and its reasons, section 19](../guides/WRITE_BACK.md#19-as-built-280-2026-10-25)). To be built as **item 0 of write-back (2.8.0)**, before write-back itself (decided by the product owner,
2026-10-20, as 2.7.0 item 0; write-back moved to 2.8.0 on 2026-10-21). The content store ([CONTENT_STORE.md](CONTENT_STORE.md),
2.7.0) was front-loaded from it. Related:
[WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) (the edit log, lanes and overlays that live here), [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti) (several servers),
[CONFIGURATION.md](../admin/CONFIGURATION.md) (the settings).

## Contents

1. [The decision](#1-the-decision) · 2. [What lives in it, and what does not](#2-what-lives-in-it-and-what-does-not) ·
3. [SQLite or PostgreSQL](#3-sqlite-or-postgresql) · 4. [No migrations: two complete schema files](#4-no-migrations-two-complete-schema-files) ·
5. [The schema version stamp](#5-the-schema-version-stamp) · 6. [Switching and upgrading: `db copy`](#6-switching-and-upgrading-db-copy) ·
7. [Configuration: `config/db/database.yaml`](#7-configuration-configdbdatabaseyaml) · 8. [Several servers](#8-several-servers) ·
9. [Today and the change](#9-today-and-the-change) · 10. [Build plan (2.8.0 item 0)](#10-build-plan-280-item-0) · 11. [Tests](#11-tests) ·
12. [Decisions (product owner, 2026-10-20)](#12-decisions-product-owner-2026-10-20)

## 1. The decision

All relational state that Drishti owns lives in **one database**, the *Drishti database*. Identity already lives in one; from 2.8.0
the write-back edit log, its lanes, overlays and Drishti-owned data live in the same one, and so does anything relational added later.
There is no second store to back up, secure, size or switch.

- **PostgreSQL is the long-term choice** (several servers, a real deployment, backups by the bank's DBAs).
- **SQLite is the default**, for single-node setups: a Windows laptop, a demo machine, a developer. One file, nothing to install.
- **You switch by configuration at any time**, through `drishti.py db copy` (section 6). The code path is the same on both: the
  same tables, the same SQL (with the few dialect differences kept in the two schema files and one small dialect class).

```
                       ┌──────────────────────────── the Drishti database ───────────────────────────┐
  drishti-server ────▶ │ identity: users, roles, packs, preferences, tokens, sessions, audit, notes,  │
  (one pool,           │           shares, threads, alerts, BI workspaces …           (today)        │
   config/db/          │ write-back: drishti_edit_lane, drishti_edit_log, overlays, owned kinds      │
   database.yaml)      │                                                              (2.8.0)        │
                       │ drishti_schema: the version stamp (section 5)                               │
                       └────────────── SQLite file (default)  or  PostgreSQL (long term) ────────────┘

  not here:  DuckDB (the BI engine: compute) · the lakes (Delta, Iceberg) · RocksDB caches · source systems
```

## 2. What lives in it, and what does not

| Lives in the Drishti database | Does not |
|---|---|
| Identity: users, roles, packs per user, preferences, API tokens, sessions, the audit trail | **DuckDB**, the BI engine: it is compute over the lakes and BI files, rebuilt from them, never a system of record |
| Collaboration: notes, shares, threads, comments, mentions, inbox and outbox | The **lakes** (Delta, Iceberg): source data, written only by the loader (`lake build`, `lake publish`) |
| BI workspaces, items, versions, usage and BI runs only with `drishti.bi.store: jpa` / `drishti.bi.runs.store: local` (one node); by default they live in the content store and the `drishti_bi_*` and `drishti_bi_run*` tables are legacy, read once by the import and kept ([CONTENT_STORE.md](CONTENT_STORE.md) section 9); the content store's table `drishti_content` when `config/store/store.yaml` says `sqlite` or `postgres` ([CONTENT_STORE.md](CONTENT_STORE.md)) | **Caches** (RocksDB, layout and fingerprint caches): derived, safe to delete |
| Write-back (2.8.0, built): `drishti_edit_lane`, `drishti_edit_log`, `drishti_edit_request` (changes awaiting approval), `drishti_overlay`, `drishti_owned`, `drishti_edit_version` (each record's version) and `drishti_edit_cursor` (how far the reactive triggers and a follower of an edit primary have read each lane) ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) sections 5, 7, 11 and 19). New tables only: the schema version does not move | **Source systems** (ENDUR, CALYPSO, MUREX, connector databases): Drishti reads them, and writes to them only through a connector's `writes:` |
| Compute approvals (2.8.0, built): `drishti_compute_request` (requests to approve what a connector or pool requires) and `drishti_compute_approval` (the approved items, revocable) ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) section 6) | Locked Python environments, staged jars and request scratch folders: files under `<data dir>/compute/` |
| Administrators' switches (2.8.0, built): `drishti_switch` (commands and packs on or off, each with its hybrid-logical-clock stamp and node) and `drishti_switch_log` (every change, with who and why); the system of record that Drishti*net gossip spreads ([USER_MANAGEMENT.md (Functions)](../admin/USER_MANAGEMENT.md#where-it-is-kept)). New tables only: the schema version does not move | |
| Anything relational added later | Files that are documents by nature (Sutras, packs, connector and net configuration): they stay files under `config/` |

The rule for a new feature: **state Drishti owns and must not lose, with rows and keys, goes in the Drishti database.** A feature
never opens its own SQLite file or its own schema.

## 3. SQLite or PostgreSQL

| | SQLite (default) | PostgreSQL (long term) |
|---|---|---|
| For | one server: a laptop, Windows, a demo, a developer | several servers, production, managed backups |
| Install | nothing: a file under the data folder | a PostgreSQL 14+ server (or RDS / Aurora PostgreSQL) |
| Writers | one at a time (WAL: readers never wait; `busy_timeout`, `IMMEDIATE` transactions) | many |
| Several Drishti servers | each has its own file; nothing is shared (write-back: section 8 and WRITE_BACK.md › The design and its reasons, section 11) | all share one database: identity, roles and the edit log are cluster-wide |
| Backup | copy the file with the server stopped, or `drishti.py db copy` to a new file | the DBA's backups, point-in-time recovery |

The two are equals in the code: every query is tested on both; neither is a "test" database.

## 4. No migrations: two complete schema files

**No database migrations, ever.** No Flyway, no Liquibase, no numbered `ALTER` scripts, no column fix-ups in Java.

- There are **exactly two schema files**, one per kind, each the **complete** schema: every table, column, key and index Drishti uses.
  They move from `drishti-identity` to the module that owns the Drishti database (`db/schema-sqlite.sql`, `db/schema-postgres.sql`).
- They are **applied at every start, idempotently**: `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`. A fresh database
  gets everything; an existing one at the same version is left as it is. A **new table** in a release is simply created by this step.
- A **parity test** reads both files and checks they define the same tables, the same columns (by name, nullability and a mapped type:
  `TEXT`↔`VARCHAR`, `INTEGER` 0/1↔`BOOLEAN`, `INTEGER PRIMARY KEY AUTOINCREMENT`↔`BIGSERIAL`), the same primary, unique and foreign keys
  and the same indexes. A change to one file without the other fails the build.
- A change to an **existing table's shape** (a column added, removed, retyped, a key changed) is made in both files, bumps the schema
  version (section 5), and is reached by an existing database through `db copy` (section 6), never by an `ALTER`.

```
 release N                       release N+1 (a column added to drishti_note)
 schema-sqlite.sql   (v4)        schema-sqlite.sql   (v5)   ─┐  parity test: same tables, columns, keys
 schema-postgres.sql (v4)        schema-postgres.sql (v5)   ─┘
          │                                 │
  existing db (v4) ── start N+1 ──▶ refused: "made by schema 4, this server needs 5: drishti.py db copy …"
          └──────────── drishti.py db copy ──▶ fresh db (v5) built from the complete schema, rows copied ──▶ start
```

Why: a complete file is the truth you can read in one place; there is no chain of scripts to replay, reorder or repair, and SQLite's
limited `ALTER TABLE` never shapes the design.

## 5. The schema version stamp

A one-row table holds the stamp:

```sql
CREATE TABLE IF NOT EXISTS drishti_schema (
    id          INTEGER   PRIMARY KEY CHECK (id = 1),
    version     INTEGER   NOT NULL,          -- the schema files' version, written in their first line
    written_by  TEXT      NOT NULL,          -- the Drishti release that built the database
    written_at  TIMESTAMP NOT NULL
);
```

At start, after the idempotent apply:

| The database | The server |
|---|---|
| empty (new) | builds it from the complete schema and stamps it with the files' version |
| stamped with the files' version | starts |
| stamped with another version (older or newer) | **refuses to start**, naming both versions and the command: `the Drishti database was made by schema 4; this server needs schema 5. Stop the servers and run: drishti.py db copy --from config/db/database.yaml --to <new database.yaml>` |
| not stamped (made before 2.8.0) | compares its tables with the schema; if every existing table already has the schema's columns and keys it stamps it and starts, otherwise it refuses as above |

The version moves only when an existing table changes shape; a release that only adds tables keeps it.

## 6. Switching and upgrading: `db copy`

Switching from SQLite to PostgreSQL, from PostgreSQL to another PostgreSQL, or upgrading across a schema version is **one command**:

```
drishti.py db copy --from config/db/database.yaml --to config/db/database.postgres.yaml [--check] [--batch 5000]
```

1. **Refuses** while a server uses the source (it checks the cluster's members and the SQLite lock); the copy needs the servers stopped.
2. **Builds a fresh target** from the complete schema of the target's kind (it refuses a target that already has tables, unless
   `--replace`).
3. **Copies the rows** table by table in foreign-key order, in batches, columns matched by name: a column new in the target takes its
   declared default; a column the target no longer has is listed and needs `--drop-columns` to proceed. Sequences (PostgreSQL) are set
   past the highest copied key.
4. **Checks** row counts per table and a checksum of every key column; any difference fails the copy and leaves the source untouched.
5. **Stamps** the target with the schema version and prints the line to put in `database.yaml`.

The source is never changed, so going back is pointing `database.yaml` at it again. `--check` does everything except the writes and
reports what would be copied. Admin → Database shows the kind, the version, the size per table and the command for a copy.

## 7. Configuration: `config/db/database.yaml`

The database has its own file, like `config/connectors/` and `config/net/`, never in `application.yaml`. `application.yaml` keeps only
the pointer:

```yaml
drishti:
  db:
    file: ${DRISHTI_DB_FILE:./config/db/database.yaml}   # the Drishti database; ready-made files in config/db.examples/
```

The shipped `config/db/database.yaml`:

```yaml
kind: sqlite                     # sqlite (default, one server) | postgres (several servers, production)
sqlite:
  file: ${drishti.data.dir}/identity/drishti.db     # under the data folder; the same file identity uses today, so an upgrade keeps it
postgres:
  url: ${DRISHTI_DB_URL:${DRISHTI_IDENTITY_DB_URL:}}            # jdbc:postgresql://db:5432/drishti
  user: ${DRISHTI_DB_USER:${DRISHTI_IDENTITY_DB_USER:}}
  password: ${DRISHTI_DB_PASSWORD:${DRISHTI_IDENTITY_DB_PASSWORD:}}   # only a ${ENV} placeholder; a literal is refused at start
pool:
  size: 20                       # PostgreSQL; SQLite (one writer) keeps 8
  min-idle: 2
  idle-timeout: 10m
  max-lifetime: 30m
  connection-timeout: 10s
  leak-threshold: 60s
  statement-timeout: 5m          # PostgreSQL only
```

- **The identity database settings move here:** `drishti.identity.database-url`, `database-user`, `database-password` and
  `database-pool-size` leave `application.yaml`. The identity block keeps what is not about the database (passwords, lockout, the seed
  administrator, `refresh-seconds`). A server that still finds one of the moved keys set starts, uses it, and warns once naming
  `config/db/database.yaml`; the old `DRISHTI_IDENTITY_DB_*` environment names keep working through the placeholders above.
- `drishti.db.pool` in `application.yaml` stays as the default pool of the **database connectors**; the Drishti database's own pool is
  the `pool:` above.
- The password is only ever a `${ENV}` placeholder (the same rule as connector files).

## 8. Several servers

| Setup | The Drishti database | Write-back's edit log ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) section 11) |
|---|---|---|
| one server | SQLite (or PostgreSQL) | in the same database |
| several servers, PostgreSQL | **one shared database**: identity and the edit log cluster-wide | in it: the full guarantee |
| several servers on AWS, no PostgreSQL | SQLite per server | S3 conditional writes |
| several servers, neither | SQLite per server | one named *edit primary* holds it; the others forward |

PostgreSQL is what makes a cluster one system for everything Drishti owns; a cluster on SQLite works, and Admin → Edits and
Admin → Database say plainly what is not shared.

## 9. Today and the change

| Today (2.6.5) | After item 0 |
|---|---|
| `IdentityDatabase` in drishti-identity opens the pool and applies `db/schema-*.sql` (idempotent, "no migrations") | a `DrishtiDatabase` opens the one pool and applies the two complete files; identity, write-back and later features take their connections from it |
| on SQLite three column fix-ups run in Java (`ensureHoldRange`, `ensureTokenScopes`, `ensureShareViewState`) | removed: the columns are in the complete schema; an older database without them is stamped only if it has them, otherwise `db copy` |
| settings in `application.yaml` under `drishti.identity.database-*` | `config/db/database.yaml`; a pointer in `application.yaml` |
| no stamp, no parity test | `drishti_schema` and the parity test |

## 10. Build plan (2.8.0 item 0)

| Step | What | Accepted when |
|---|---|---|
| 0.1 | `DrishtiDatabase` (pool, dialect, idempotent apply), the schema files moved, `drishti_schema`; identity uses it | every identity test passes on SQLite and PostgreSQL unchanged |
| 0.2 | `config/db/database.yaml`, `config/db.examples/`, the pointer, the moved keys with their warning | a 2.6.5 data folder starts on 2.8.0 with its users, roles and tokens and no setting changed |
| 0.3 | the parity test; the column fix-ups removed | a column in one file and not the other fails the build |
| 0.4 | `drishti.py db copy` (and `--check`), the refusal on a version mismatch, Admin → Database | SQLite → PostgreSQL → SQLite round trip gives identical rows; a mismatched stamp refuses to start with the command named |
| 0.5 | docs: CONFIGURATION, the admin guide, a tutorial step (UI, CLI, YAML) | the docs tests pass |

The edit-log tables of write-back ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) section 7) are added to both files in W1, on this base (built
2026-10-25, in the files under `drishti-identity` until they move; the parity test covers them).

## 11. Tests

- **Parity:** the two files define the same tables, columns, keys and indexes.
- **Idempotent:** applying the schema twice changes nothing; a new table is created on an existing database.
- **Stamp:** a database stamped with another version refuses to start and names `db copy`; an unstamped one with the right shape is stamped.
- **Copy:** SQLite → PostgreSQL → SQLite keeps every row (counts and key checksums); a dropped column needs `--drop-columns`; a running
  server blocks the copy.
- **Upgrade:** a 2.6.5 data folder starts with its users and settings; the old environment names still reach PostgreSQL.

## 12. Decisions (product owner, 2026-10-20)

1. **One Drishti database** for all Drishti-owned relational state: identity, the write-back edit log, lanes, overlays and owned data,
   and anything relational later. PostgreSQL long term; SQLite the default for single-node setups; switch by configuration at any time.
2. **No migrations, ever:** two complete schema files (SQLite, PostgreSQL), applied idempotently at start, kept equal by a parity test.
3. **Switching or upgrading is `drishti.py db copy`:** a fresh database from the complete schema with the rows copied; a schema version
   stamp makes the server refuse a mismatch and name the command.
4. **Its own configuration file**, `config/db/database.yaml` (`kind: sqlite | postgres`, the SQLite file under the data folder, the
   PostgreSQL url, user and password as `${ENV}`, the pool); the identity database settings move there; `application.yaml` keeps a pointer.
5. **DuckDB is compute, not this store.**
6. Built as **2.8.0 item 0** (2.7.0 when decided).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
