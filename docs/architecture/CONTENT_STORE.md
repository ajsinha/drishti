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
# The content store: user content every node can reach

Status: **built (first user: Build workbench Designs), Unreleased.** Front-loaded from 2.7.0 item 0. Related:
[DATABASE.md](DATABASE.md) (the one Drishti database), [DRISHTI_NET.md](DRISHTI_NET.md) (several servers),
[CONFIGURATION.md](../admin/CONFIGURATION.md) (`config/store/store.yaml`).

## 1. The problem

User content (screen-designer Designs, workspace drafts, BI items, run records, shares, schedules, settings) was saved on the
node that served the request. In a Drishti*net cluster, when the console fails over to another node, that node has none of it,
and the user sees `404 DRS-5006 no design`. Content must be reachable from every node, and two nodes editing the same item at
the same moment must never silently lose one of the edits.

## 2. The interface

One small interface, `com.ash.drishti.identity.content.ContentStore` (drishti-identity, beside the Drishti database code that
already owns user state). Documents, not SQL: a key names one item; the store keeps its bytes and an opaque version.

| Call | Does |
|---|---|
| `get(key)` → `Optional<Item{bytes, version}>` | Reads one item. |
| `put(key, bytes, expectedVersion)` → new version | Compare-and-set. `expectedVersion` null means *must be absent* (create); otherwise the item must still have that version. A mismatch throws `409 DRS-5057` (content conflict). |
| `list(prefix)` → keys | Every key starting with `prefix`, sorted. |
| `delete(key, expectedVersion)` → boolean | Compare-and-set delete; false when there was nothing; a stale version is `409 DRS-5057`. |
| `changes(since)` → `Changes{keys, cursor}` | Keys written or deleted after `since` (epoch ms), for polling caches. At least once: pass `cursor` back next time (less a small margin for clock skew across nodes); a key may repeat. Push is a later addition for backends that have it (Postgres `LISTEN`, S3 events). |
| `root()` | Where the store is, as text (the cluster check, section 6). |

Keys are `/`-separated paths such as `designs/<owner>/<id>/design.json`: 1 to 16 segments, each 1 to 120 UTF-8 bytes, any
Unicode, no empty, `.` or `..` segment, no control characters, no `\`. A bad key is `400 DRS-5058`. `putLatest(key, bytes)` is a
helper (read the version, put, retry a few times) for writes whose last writer may win (sample documents).

## 3. Backends

Chosen in their own file, `config/store/store.yaml` (never `application.yaml`; the file is named by `drishti.store.file`),
examples in `config/store.examples/`. Every backend passes the same contract test suite (section 7).

**filesystem (the default).** Root `<data folder>/fstore` (`data/fstore`). An item is one file, `items/<encoded key>`; its version
is the SHA-256 of its bytes, so the data file alone is the truth and no read can see bytes and version out of step. A write goes
to a visible sibling `<name>~tmp-<uuid>` and is renamed into place; compare-and-set holds a lock file `<name>~lock`, made with
create-new (atomic on local disks, NFS v3+ and EFS) and broken when older than 30 s (a crashed writer). Renames and deletes retry
with back-off when Windows refuses them because a reader has the file open (the FileSwap pattern). Key segments are encoded
(`%XX` for every byte outside `A-Za-z0-9-_.`, a leading `.` and Windows device names too), so names are visible, portable to
Windows and never contain `~`, which is reserved for the store's own sidecars. A delete leaves an empty
`tombstones/<encoded key>` so `changes` reports it; a later put removes it. Since the version is a content hash, writing back
identical bytes keeps the version (harmless: the content is the same). No hidden files, nothing outside the root.

**sqlite and postgres.** One table, `drishti_content (k, v, version, updated_ms, deleted)`, in the two complete Drishti database
schema files (`drishti-identity/src/main/resources/db/schema-sqlite.sql` and `schema-postgres.sql`; no migrations,
SchemaParityTest keeps them equal). The backend applies the whole schema file at start (idempotent), so it may point at the
Drishti database itself (the usual choice) or at a database of its own. The version is a counter; compare-and-set is
`UPDATE … WHERE k = ? AND version = ?` (or an `INSERT` racing on the primary key for a create). A delete keeps a tombstone row
(`deleted = 1`) so `changes` reports it. Postgres is tested with Testcontainers.

**s3.** Objects `<prefix>/items/<key>`; the version is the ETag. Compare-and-set is the conditional PUT: `If-None-Match: *` for a
create, `If-Match: <etag>` for an update; `412` or `409` answers `DRS-5057`. A delete compares the ETag (a HEAD, then `If-Match`)
and writes a tombstone `<prefix>/tombstones/<key>`. `changes` lists by LastModified (second resolution). Works with AWS S3
(conditional writes) and compatible stores that honour the headers, such as MinIO; tested against an in-process S3 stand-in
that honours the conditional headers, and against MinIO when `DRISHTI_TEST_MINIO=1` (Testcontainers, image `minio/minio`).

**Delta / Iceberg: not primary, design only.** They are poor at single-key compare-and-set and at small hot documents. Later they
may be an optional **history/audit sink**: a writer tails `changes`, appends `(key, version, bytes or hash, at, by)` to a Delta or
Iceberg table, and BI can query who changed what and when. Not built.

## 4. Configuration

```yaml
# config/store/store.yaml
backend: filesystem              # filesystem | sqlite | postgres | s3
filesystem:
  root: ${drishti.data.dir:./data}/fstore
sqlite:
  url: jdbc:sqlite:${drishti.data.dir:./data}/identity/drishti.db   # the Drishti database itself (the usual choice)
postgres:
  url: jdbc:postgresql://db:5432/drishti
  user: drishti
  password: ${DRISHTI_STORE_PASSWORD:}
  pool: 4
s3:
  bucket: drishti-content
  prefix: prod
  region: us-east-1
  endpoint: ""                   # MinIO or another S3-compatible store
  path-style: false
  access-key: ${DRISHTI_STORE_ACCESS_KEY:}
  secret-key: ${DRISHTI_STORE_SECRET_KEY:}
```

A missing file or key means the default shown. `config/store.examples/` has `laptop.yaml` (filesystem), `cluster-nfs.yaml`
(filesystem on a shared mount), `postgres.yaml` and `s3.yaml`.

## 5. Designs: the first user

`drishti.builder.designs.store` is now `content` by default: Designs are kept in the content store as
`designs/<owner>/<id>/design.json` and `designs/<owner>/<id>/samples/<hash of name>.json`. `ContentDesignStore` implements the
existing `DesignStore`, so `DesignService` (ownership, quotas, expiry, roles) is unchanged. Every Design read carries its store
version and every save is a compare-and-set against it, so two nodes saving the same Design at once give one success and one
`409 DRS-5057` (changed elsewhere: reload and try again). The older `file` (`data/designs`) and `jpa` stores can still be named,
for one node only.

**One-time import at start.** With `store: content`, the server imports what the old stores hold:

- **files** (`drishti.builder.designs.dir`, `data/designs`): each Design and its samples are put with *must be absent*, so an
  item already in the store is never overwritten and a rerun is harmless. When every Design is in, the folder is renamed
  `designs-imported-<yyyyMMdd-HHmmss>` (visible, kept, safe to delete later). A failure leaves the folder in place for the next
  start.
- **the identity database** (`drishti_design`, `drishti_design_sample`, used by `store: jpa`): the same, once; a marker item
  `system/imports/designs-jpa.json` records it, so a Design deleted later does not come back. The rows are left in place.

Each import logs how many Designs it brought in, skipped (already there) and failed.

## 6. Several servers

Every node of a cluster must use the **same** store configuration. A filesystem store on a cluster needs a **shared mount**
(NFS, EFS, SMB) at the same root on every node; a local `data/fstore` per node gives each node its own content again. Each node
writes `nodes/<node id>.json` into the store at start (its root as it sees it); a node that finds another member's record with a
different root logs a warning (`content store roots differ`): the same share mounted at different paths, or two configurations
pointing at one store. A node also logs its store at INFO (`content store: filesystem /mnt/drishti/fstore`). A local store per node
cannot see the other nodes' records, so that case is caught by the log line and this rule, not by the check (next step: carry
the root in the Drishti*net capability digest and compare it there). Postgres and S3 need no shared disk.

## 7. Tests

`ContentStoreContract` (drishti-identity tests) runs against every backend: concurrent creates and concurrent updates of one key
(exactly one wins, the rest `DRS-5057`), conflict codes, list by prefix, delete (stale and current), changes (puts and deletes),
binary-safe content (every byte value), a large item (6 MB), Unicode keys, bad keys (`DRS-5058`), and reopening the store keeps
everything. Backends: filesystem, sqlite, postgres (Testcontainers), s3 (stand-in; MinIO opt-in).
`ContentDesignStoreTest`: two in-process nodes on one filesystem root (a Design saved through A is read and updated through B;
concurrent saves give one conflict; owners stay isolated) and the import (idempotent, folder renamed, marker for the database).

## 8. Next steps: what is still kept on one node

Already shared between nodes, not through this store: users, roles and API tokens (IdentitySync); `drishti_preference` (settings,
saved searches, layouts, pivots, workspaces) and sessions (StateSync, ReplicatedPreferences).

The ordered migration list (the inventory of content kept on one node only, 2026-10-21). Item 1 is done; the rest move one at a time
behind the same interface:

1. **Designer drafts** (Designs): done, section 5. Versions, About, form and share live inside the record; ids are random 12-hex.
2. **BI workspaces and items:** `BiStore`, the `drishti_bi_*` tables (workspace, member, item, version, revision, event, user_item,
   usage, setting).
3. **BI runs:** the `drishti_bi_run*` rows, and results under `<data>/bi-runs`. Already forwarded by node id.
4. **Notes:** `drishti_note`.
5. **Collaboration:** threads, comments, mentions, follows, shares, inbox, outbox, holds and seals, access (JPA or `<data>/collab`).
6. **Alerts and monitors:** NOT copied, on purpose (each node evaluates its own).
7. **Embed apps** (`drishti_embed_app`), **pack switches** (`drishti_pack_state`), **audit** (`drishti_audit` and `audit.jsonl`).
8. **Files:** governance proposals (`<data>/governance`); designer bound files (`<data>/dev-sutras`); report outputs
   (`<data>/reports`); load history (`<data>/loads`); pack admin state (`<data>/packs/*`, `uninstalled`, `admin/bi-hidden.txt`);
   UI-written config (`config/connectors`, `config/bi`).
9. **BI store Parquet:** a derived cache. Never migrated.

Then: a read cache driven by `changes`, push for Postgres and S3, the root in the Drishti*net capability digest (section 6), and
the optional Delta/Iceberg history sink (section 3).
