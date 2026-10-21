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

Status: **built (Designs; collaboration and notes; embed apps, pack switches and audit; governance proposals; load history), Unreleased.** Front-loaded from 2.7.0 item 0. Related:
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

The ordered migration list (the inventory of content kept on one node only, 2026-10-21). Items marked done are in the content store
(sections 5, 9 and 10); the rest move one at a time behind the same interface:

1. **Designer drafts** (Designs): done, section 5. Versions, About, form and share live inside the record; ids are random 12-hex.
2. **BI workspaces and items:** done, section 9. `BiStore`, the `drishti_bi_*` tables (workspace, member, item, version, revision, event,
   user_item, usage, setting).
3. **BI runs:** done, section 9. The `drishti_bi_run*` rows, and results and snapshots under `<data>/bi-runs`.
4. **Notes:** done (section 10.2). `drishti_note` is only the source of the one-time note import; notes live in comment threads.
5. **Collaboration:** done (section 10.3): threads, comments, revisions, mentions, follows, note links, shares and recipients, inbox,
   outbox, holds and seals. Still per node: the access log (`drishti_access`, who read what), next.
6. **Alerts and monitors:** NOT copied, on purpose (each node evaluates its own).
7. **Embed apps** (`drishti_embed_app`), **pack switches** (`drishti_pack_state`), **audit** (`drishti_audit` and `audit.jsonl`):
   done (section 10.4).
8. **Files:** governance proposals (`<data>/governance`): done (section 10.5); load history (`<data>/loads`): done (section 10.6).
   Next, in this order: report outputs (`<data>/reports`), designer bound files (`<data>/dev-sutras`), pack admin state (`<data>/packs/*`, `uninstalled`,
   `admin/bi-hidden.txt`; a pack jar stays a local file materialised from the store, the store being the truth). UI-written config
   (`config/connectors`, `config/bi`): done for connectors and data loads overrides, section 11.
9. **BI store Parquet:** a derived cache. Never migrated.

Then: a read cache driven by `changes`, push for Postgres and S3, the root in the Drishti*net capability digest (section 6), and
the optional Delta/Iceberg history sink (section 3).

## 9. BI content and BI runs

`drishti.bi.store` and `drishti.bi.runs.store` are `content` by default: BI workspaces, items and their history, and BI runs with their
results, live in the content store, so every node of a cluster sees the same BI content and the same runs. `ContentBiStore` implements
`BiStore` (now an interface; `JpaBiStore` is the old identity-database store), so `BiService` (workspace membership, item visibility,
audiences, governance) is unchanged; `ContentRunRecords`, `ContentRunRecords.Shares`, `ContentRunRecords.Schedules` and `ContentRunStore`
implement the runs' `RunRecords`, `RunShares`, `RunSchedules.Store` and `RunStore`, so run visibility, shares and the signature rule on
results are unchanged.

**Key layout.** Every segment is escaped (letters, digits, `-` and `.` kept, other bytes `_xx`; past 100 bytes a prefix and a hash);
numbers are zero-padded to ten digits so keys sort.

| Key | What |
|---|---|
| `bi/workspaces/<ws>.json` | a workspace |
| `bi/members/<ws>/<user>.json` | one member of a shared workspace |
| `bi/items/<item>.json` | an item with its draft body and catalogue fields |
| `bi/history/<item>/versions/<n>.json` | a published version |
| `bi/history/<item>/revisions/<seq>.json` | a saved state (every save, restore, publish, import) |
| `bi/history/<item>/events/<seq>.json` | a governance event (request, approve, certify, ...) |
| `bi/users/<user>/marks.json` | a person's favourites and last opens, by item |
| `bi/usage/<yyyy-mm-dd>/<node>.json` | the views one node counted that day, by item (`imported.json`: the import's) |
| `bi/settings/<name>.json` | a BI setting |
| `bi/runs/rows/<run id>.json` | a run (the old `drishti_bi_run` row) |
| `bi/runs/shares/<run id>/<recipient>.json` | a share of a run |
| `bi/runs/schedules/<id>.json` | a scheduled run |
| `bi/runs/results/<result key>/answer.json` | a result, with the signature it was computed for |
| `bi/runs/results/<result key>/meta.json` | its owner, size and last open (the budget and the sweeper read only these) |
| `bi/runs/snapshots/<name>/<version>.json` | a promoted snapshot |

Item documents sit apart from their history (`bi/history/`) so listing the items reads item documents only.

**Safe concurrent edits.** A read of an item or workspace remembers, for the request's thread, the version it returned (30 s); the save
that follows is a compare-and-set against it. Two nodes (or two requests) saving the same item give one success and one `409 DRS-5057`
(changed elsewhere: reload and try again), beside BI's own `baseEtag` check. A save of a key the thread has not read lately writes over
what is there, as the database did. New revision and event numbers, and new snapshot versions, are taken by a create that must not exist
yet, so two nodes never take the same number. Marks and the day's views are read-change-write loops on compare-and-set.

**Listing and search.** The catalogue's listings (all items, a workspace's items, the published ones, all workspaces, the runs lists) come
from a per-node snapshot of the prefix at most `drishti.bi.store-list-ttl` old (default `1s`); documents whose version did not change are not
parsed again, and this node's own writes drop the snapshot at once. A single item, workspace or run is always read from the store. A
listing on another node may lag by the TTL; an edit never does (the compare-and-set decides). Next: a snapshot refreshed from `changes`
instead of a re-list, once `changes` is cheap on every backend (section 8).

**Events and usage.** Governance events are per-item documents (`events/<seq>`): they are rare, belong to the item's audit trail, and must
be the same on every node. Views are append-heavy, so each node counts into its own document per day (`bi/usage/<day>/<node>.json`): no two
nodes write the same document, a write is a small compare-and-set on this node's segment, and `views` sums the segments of the days asked.

**Runs across nodes.** A run's status and result read from any node (the row and the result are in the store); `RunForwardFilter` still
sends a run's actions to the node that holds it, and is kept for nodes whose runs store is `local`. The lease is a compare-and-set on the
run's document: `renew` writes only while the row still names this node, `adopt` only while it is still the dead node's with its lease
lapsed, so exactly one node wins (BI_RUNS.md 13.3). A late save from a node that lost a run (a higher attempt on another node) is dropped.
A due schedule is taken by one node (`claim`: a compare-and-set on its `nextAt`), so a schedule fires once in a cluster.

**One-time import at start.** With `content`, the server imports, once, the identity database's `drishti_bi_*` rows (marker
`system/imports/bi-jpa.json`) and the `drishti_bi_run`, `drishti_bi_run_share`, `drishti_bi_run_schedule` rows with the results and snapshots
under `store.dir` (`<data>/bi-runs`; marker `system/imports/bi-runs.json`). Every document is created with "must be absent" (a rerun is
harmless; a person's marks already in the store win); the marker keeps an item or run deleted later from coming back. The rows and the files
are left in place.

**One node back on the old stores.** `drishti.bi.store: jpa` and `drishti.bi.runs.store: local` put one node back on its identity database
and `store.dir`; it then sees only its own BI content and runs (forwarding by node id still reaches the others' runs).

**Tests.** `ContentBiStoreTest` (drishti-identity): two nodes on one filesystem root (a workspace and an item made on A are read, edited and
published on B; marks, views and settings; delete), two saves of one item give one conflict, concurrent revisions never share a number, and
the import (once; a deleted item stays deleted; nothing counted twice; the rows kept). `ContentRunsTest` (drishti-server): a run submitted on
A has its status and result read on B (never across signatures); a dead node's run adopted by exactly one of two nodes through the lease and
finished; renew only by the holder and a loser's late save dropped; a share made on A seen on B; a schedule claimed once; the import of rows
and the result folder. `RunClusterTest` runs its two servers on one content store root.

## 10. Collaboration, notes and the admin data

Each moved store keeps its interface, so the services above it (rights, limits, retention, holds, seals) are unchanged; a switch per
type names the old store for one node only. At start each runs a **one-time import** of the old rows and files: every item keeps its
id, number and hashes, an item already in the store is skipped, a marker `system/imports/<what>.json` records the import (so
something deleted later does not come back), and the old tables and files are left in place.

### 10.1 Several documents, one change: `TxContentStore`

Collaboration writes several documents for one action (a share, its recipients' indexes, their inbox rows; a comment, its index
entries). Two choices were open: one document per aggregate, or ordered compare-and-set writes with a recoverable intent record. Both
are used:

- **One document per thread** (`collab/threads/<id>.json`: the thread, its comments, the sealed revision chain, mentions, followers and
  note links). A reply is one compare-and-set of that document, so the hash chain can never fork between two nodes, and a conflict is
  retried on a fresh read (the work has no outside effect).
- **Transactions across documents** (`com.ash.drishti.identity.content.TxContentStore`, what `CollabTx` runs on). Inside `run` every
  write is buffered and reads see it; a failure writes nothing. The commit checks each document still has the version the work read
  (else `409 DRS-5057`, nothing written), writes an **intent record** `system/tx/<time>-<uuid>.json` holding every write (key, expected
  version, bytes) when there is more than one, applies the writes in order (each a compare-and-set), and removes the record. When
  another node wins one of the writes, the writes already applied are put back and the commit is `409 DRS-5057`. A process that dies
  mid-commit leaves the record; `recover()` (at start and every `drishti.identity.refresh-seconds`, on any node) rolls it forward after
  60 s: a write whose document still has the expected version is applied, one already there is skipped, one changed since by someone
  else is left out and logged.
- Index documents (`collab/entities/…`, `collab/comments/…`, `collab/mentions/…`, `collab/shares-sent/…`, `collab/shares-to/…`,
  `collab/inbox-seq/…`) are written in the same transaction, and readers check every entry against the document it points to, so an
  index without its document changes nothing.
- **Sequences** (inbox rows, outbox deliveries, holds, proposals) are counter documents (`…/counters/<name>`) advanced by
  compare-and-set outside any transaction: cluster-wide, never reused, never rolled back (a failed transaction leaves a gap).

### 10.2 Notes

Notes already live in comment threads (the deprecated `/notes` API is a facade over them), so they moved with collaboration. A note's
number is a create of `collab/note-links/<number>`: two nodes adding a note at once cannot take one number (the loser takes the next).

### 10.3 Collaboration (`drishti.collab.store`: `content`, default; `jpa` or `file` for one node)

| Key | Holds |
|---|---|
| `collab/threads/<thread>.json` | the thread document (above) |
| `collab/entities/<kind>/<sha1 of entity>/<thread>` | an entity's threads |
| `collab/comments/<comment>` | the comment's thread |
| `collab/mentions/<sha1 of target>/<comment>` | who was mentioned where |
| `collab/note-links/<note, 19 digits>` | legacy note number to comment |
| `collab/shares/<share>.json` | a share and its recipients |
| `collab/shares-sent/<sha1 of sender>/<share>`, `collab/shares-to/<sha1 of user>/<share>` | sent and received lists |
| `collab/inbox/<sha1 of user>.json` | a user's inbox rows (newest `inbox.keep`), one read for the bell |
| `collab/inbox-seq/<seq, 19 digits>` | the user of each row, for another node's live hub (`after(seq)`) |
| `collab/outbox/<seq, 19 digits>.json` | one delivery; a claim is a compare-and-set to `sending` with a lease, so two nodes' dispatchers send it once; an expired lease makes it due again |
| `collab/holds/<id, 19 digits>.json` | a legal hold; a release is a compare-and-set |
| `collab/seals/<seal key>-<hash>.json` | tamper-evidence seals (thread heads, comment rows, holds) |
| `collab/counters/inbox`, `outbox`, `holds` | the sequences |

The import reads the identity database's collaboration tables (marker `system/imports/collab-jpa.json`) and, when present, the files
under `drishti.collab.dir` (`collab-file.json`). The revision chains keep their hashes, so `GET /admin/collab/verify` still holds.

### 10.4 Admin data in the identity database

| Data | Switch | Key |
|---|---|---|
| Embed apps (`drishti_embed_app`) | `drishti.embed.store` (`content` / `jpa`) | `admin/embed-apps/<id>.json`; a save is a compare-and-set against the version read, so two administrators editing one application on two nodes get one `409 DRS-5057` |
| Pack switches (`drishti_pack_state`) | `drishti.packs.state-store` (`content` / `jpa`) | `admin/pack-state.json` (every pack's state; nodes re-read it every refresh interval) |
| Audit (`drishti_audit`, and `audit.jsonl` through the earlier import into it) | `drishti.audit.store` (`content` / `jpa`) | `audit/<yyyy-MM-dd>/<node>-<nnnn>.jsonl` and `audit-heads/<node>.json` |

**Audit is append-only**, so no two nodes write one document: each node appends to its own per-day segments (a new segment past
512 KB), one JSON line per event, each chained by hash to that node's previous line; `audit-heads/<node>.json` holds the node's count
and newest hash, so removed newest lines show. A read merges every node's segments newest first; the compliance verifier walks each
node's chain. The node is `drishti.net.node-id` (else host and port). The table's rows are imported once (marker
`system/imports/audit-jpa.json`, written first so only one node copies).

### 10.5 Governance proposals (`drishti.governance.store`: `content`, default; `file` for one node)

`governance/proposals/P-NNNNNN.json`, numbered from `governance/counters/proposals` (raised past the imported numbers), read from the
store every time. A decision is a compare-and-set: two approvers on two nodes cannot both decide one proposal (the second gets
`PROPOSAL_CONFLICT`). The folder `<data>/governance` is imported once (`system/imports/governance-files.json`).

### 10.6 Load history (`drishti.loads.store`: `content`, default; `file` for one node)

`loads/<pack>.json` (the announcements and late flags of one pack), read every time and changed by compare-and-set with a retry on a
fresh read, so two nodes receiving announcements at once lose none, a batch announced on one node is a duplicate on the other, and
a late notice goes out once. A pack's old file `<data>/loads/<pack>.json` is imported the first time the pack is read (the document's
existence is the marker) and left in place. Per-pack overrides saved through Admin are the shared documents `loads/config/<pack>.yaml` (section 11; `drishti.loads.config-store`: `content`, default, or `file`); the folder `<data>/loads/config` is imported once (`system/imports/loads-config.json`) and kept.

### 10.7 Left on each node, on purpose

Alerts and monitors (each node evaluates its own); the BI store's Parquet (a derived cache); users, roles, tokens and preferences
(IdentitySync and StateSync already share them).

### 10.8 Backup

The content store root (`data/fstore`, or the `drishti_content` table, or the bucket) is now the backup of everything above, together
with the identity database for what is still there (users, roles, tokens, preferences, access log). Copy it with the server stopped,
or take a filesystem snapshot: every item is one file written by rename, so a copy taken while it runs holds whole items, but a
multi-document change may be half in (its intent record under `system/tx/` is copied too, and the next start finishes it).

## 11. UI-written configuration: shared overlays (as built for connectors and load overrides)

The decision (2026-10-21): **Admin updates are shared across the cluster; the files stay the base (GitOps), and every edit made
through Admin or the UI is an overlay in the content store that every node reads.** (Option 2 of the four that were listed: one
writer node, overlay, store as the truth, a shared config folder.)

**One poller per node.** `ContentWatch` (drishti-identity) calls `changes(cursor)` every `drishti.store.watch-ms` (2 s) and hands the
changed keys to the listeners whose prefix they start with; the window overlaps the last one by 2 s of clock skew, and a key is handed
over only when its version moved. One walk of the store per interval, however many areas listen.

**Connectors** (`drishti.connectors.store`: `content`, default; `file`). `ConnectorFiles` reads through a `ConnectorOverlay`:

| Key | Holds |
|---|---|
| `config/connectors/<name>.yaml` | the text saved through Admin, exactly as saved (`${VAR}` unresolved, masked credentials never written) |
| `config/connectors/<name>.hidden` | an Admin delete of a connector the files define (the file stays on disk, hidden on every node) |
| `config/connectors-history/<name>/<yyyyMMddTHHmmssSSS>.yaml` | every earlier effective text (Restore lists them with the file history) |

The effective text is the overlay if present, else the file. Every node's `ConnectorManager` gets the change from the watch and
hot-reloads as for a file edit; at start the overlaid names are applied by the first scan (the start-up properties come from the
files alone). A save compares the etag the edit was based on with the store's current effective text and writes by compare-and-set,
so two administrators on two nodes get one `409 DRS-5032`. Reset removes the overlay. There is nothing to import (the files were and
stay the base). Pack template updates (CONNECTOR_FILES.md, "Template updates") write through the same overlay.

**Data loads overrides** (`drishti.loads.config-store`: `content`, default; `file`): `loads/config/<pack>.yaml`, read on every use,
saved by compare-and-set; the folder `<data>/loads/config` imported once (`system/imports/loads-config.json`) and kept.

**`config/bi`**: nothing is written there by the UI (BI content is in the store, section 9), so there is nothing to share.

**Not built yet (next):** the pack admin state (`packs/added.yaml`, `packs/installed/` archives materialised to a local cache on each
node, `packs/settings/`, `packs/deploy-history.jsonl`, `packs/samples-mode`, `<data>/uninstalled/`, `<data>/admin/bi-hidden.txt`), with
the in-place restart (`Restarter`) triggered on every node when another node installs or uninstalls a pack; report outputs
(`<data>/reports`), designer bound files (`<data>/dev-sutras`) and the collaboration access log (`drishti_access`, per-node day segments
merged when read, like the audit). The pack state needs the store opened in the environment post-processor (packs load before the
Spring context), so each node materialises the shared state to its local folders before the packs are read.
