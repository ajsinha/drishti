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
# The in-memory lake: data that never touches a disk

Some feeds may not, by contract, be saved on a disk. Drishti holds them in an **in-memory lake**: ordinary Delta tables (a `_delta_log`
and Parquet files) whose files live in the server's memory, off the Java heap, under a hard cap. A server job, the **replenish job**,
fills each table from its feed; every page, search, BI dataset and the Data view read it exactly as they read a lake on a disk; and a
guard refuses every way the data could reach a disk. Only a user's download leaves the server.

This guide sets one up end to end, explains what the server does with it, and lists every setting. The design and its reasoning are in
[IN_MEMORY_LAKE.md](../architecture/IN_MEMORY_LAKE.md); a worked example is [tutorial 31](../tutorials/31-news-in-memory-only.md).

## Contents

1. [How it works](#1-how-it-works)
2. [Setting up a memory-only feed](#2-setting-up-a-memory-only-feed)
3. [Replenish jobs: sources, modes, when they run](#3-replenish-jobs-sources-modes-when-they-run)
4. [The hard cap and the eviction ladder](#4-the-hard-cap-and-the-eviction-ladder)
5. [Automated compaction](#5-automated-compaction)
6. [Never on disk: what is refused and what is allowed](#6-never-on-disk-what-is-refused-and-what-is-allowed)
7. [Memory, the JVM and the host](#7-memory-the-jvm-and-the-host)
8. [A cluster: the owner node binds the pack](#8-a-cluster-the-owner-node-binds-the-pack)
9. [What an administrator sees](#9-what-an-administrator-sees)
10. [Every setting](#10-every-setting)
11. [Data contract of a memory table](#11-data-contract-of-a-memory-table)
12. [Troubleshooting](#12-troubleshooting)

## 1. How it works

```
 feed (URL, file, connector) ──► replenish job ──► lake writer ──► mem://<store>/<table>   (Delta: _delta_log + Parquet, zstd)
                                     (server)                              │ off-heap chunks, hard cap
 pages, search, BI, Data view ◄── Delta connector (root: mem://<store>) ◄──┘
```

- A **memory store** (`mem://<store>`) is one pool of memory with its own cap: files are lists of fixed-size chunks (direct buffers)
  taken from the pool and given back the moment a file is deleted, so the cap is exact.
- A **memory table** is a Delta table at `mem://<store>/<folder>`; a run of its job is one Delta commit, so a reader sees the whole run
  or none of it, and a reader in the middle of a read finishes on the version it started on.
- The **Delta connector** with `root: mem://<store>` reads it. Nothing else changes: Sutras, mnemonics, searches, BI.
- A **restart empties** the stores, by design. The jobs refill them at start, in warm-up order; meanwhile the connector's health says
  *held in memory only and is empty now* rather than a blank.

## 2. Setting up a memory-only feed

Two files. The connector:

```yaml
# config/connectors/news-store.yaml (config/connectors.examples/memory/news-store.yaml)
plugin: delta
kinds: [news-topic, listed-company, news, quote]
settings:
  root: mem://news
  domain: ""          # the tables sit at mem://news/<kind>: without it the pack's template adds domain: news
```

The store is a lake like any other ([LAKE_GUIDE.md section 6](LAKE_GUIDE.md#6-in-memory-stores)): its own lake file, its root
`mem://<store>`, its cap and contract under `memory:`:

```yaml
# config/lakes/news.yaml
lake: news
root: mem://news
memory:
  max-mb: 256                 # the hard cap
  chunk-kb: 64                # small tables: every file holds at least one chunk
  contract: VX-2026-07        # shown on every refusal and on Admin -> Lakes
```

Its tables' replenish jobs, below, stay in the in-memory lake's own file `config/engines/memory-lake.yaml` (the engine's work, with its switch,
`drishti.engines.memory-lake.enabled`, off as shipped: set it to `true`, or turn it on in Admin → Engines, then restart, since the
stores' caps size the direct memory at start; [OPERATIONS.md section 21](../admin/OPERATIONS.md#21-compute-engines-on-or-off)), or as
before in `application.local.yaml` in the server's working folder or the environment (stores configured there keep the lake on):

```yaml
# config/examples/in-memory-lake.yaml
drishti:
  lake:
    memory:
      tables:                         # the store news is config/lakes/news.yaml (above); a store under stores: here is the older form
        - name: news
          store: news
          source: { url: "https://feeds.example.com/news.jsonl", headers: { Authorization: "Bearer ${VENDOR_TOKEN}" } }
          key: [NEWS_ID]
          every: 15m
          warm-up: 1
          pin: true
```

Restart the server with the shipped start scripts (they raise the JVM's direct-memory limit by the stores' caps, [section 7](#7-memory-the-jvm-and-the-host)).
Check it:

```bash
python3 tools/drishti.py lake memory status
python3 tools/drishti.py lake memory jobs --table news
```

UI: **Admin → Lakes** lists the store (cap, use, contract) and each table (state, bytes, rows, version, last and next refill). Switching
back: remove both and restart; the pack's own connector reads the disk lake again.

## 3. Replenish jobs: sources, modes, when they run

**Sources** (`source:`), one of:

| Source | What the job reads |
|---|---|
| `url` (and `headers`) | an HTTP(S) feed answering JSON Lines or one JSON array (a vendor's REST feed); headers name secrets as `${ENV}` |
| `file` | a JSON Lines file, plain or `.jsonl.gz` (a drop the feed writes; the demo's committed snapshots) |
| `connector` and `kind` | every document of a kind from any connector that can list them (Kafka's topic state, JDBC, files, the vendor feeds) |
| `connector`, `kind` and `ids` or `ids-from` | a connector that computes a document when asked (the news connector), entity by entity: `ids: [NEWS-Apple]`, or `ids-from: listed-company.NEWS_ID` (that field of every row of another memory table) |

A connector whose own lake is in memory is never a source (a memory table is filled from its feed, not from another memory table).

**Rows.** Each document becomes a row: `id` from `key` (fields joined with `|`), `doc` the document, `business_date` from
`business-date` (`today`, a date, or `field:AS_OF`: the first ten characters of a field), and the `columns` promoted beside it. Rows are
sorted by `sort-by` (the key when empty) before they are written: runs of equal values compress best.

**Modes** (`mode:`):

| Mode | A run | Use for |
|---|---|---|
| `replace` (default) | is the whole table: one commit removes every earlier file and adds the new | snapshots: prices, reference lists |
| `append` | adds its rows | ticks, events |
| `merge` | replaces rows by key on the dates the run touches (the rest of those dates kept) | a feed of changes |

**When.** `every: 15m` (or `30s`, `1h`), or `cron: "0 0/15 * * * *"`; on the source's signal (`signal: true`, the default: a file's time or
size, a connector's last update, checked every 10 s); at start in `warm-up` order; after an eviction (30 s later); and on demand
(`drishti.py lake memory refill <table>`, audited). One run of a table at a time; `job-threads` tables at once.

**History.** Every run is recorded (in memory): when, why (warm-up, schedule, signal, after eviction, by a user), the outcome (`ok`,
`failed`, `refused`), rows, bytes, the version and the time taken. Errors are recorded without any value of the data.

## 4. The hard cap and the eviction ladder

`max-mb` is a hard limit: memory is taken only below it. When a write needs room the store frees memory in this order and stops as soon
as there is enough:

1. **Old versions** past `keep-versions` (2 by default: the current and the one a reader may still be on).
2. **The decoded-column cache** (none is kept today).
3. **Whole tables, least recently read first**: never a pinned table (`pin: true`), never the table being written. A table goes whole,
   never file by file; it becomes *evicted, refill queued*, and its pages say so.
4. **Refuse the write.** The commit fails, the table stays at its previous version, whole; the run records *refused: the store is full*;
   Health turns amber.

## 5. Automated compaction

The memory optimizes itself. After every commit and every `every-seconds`, each store's tables are scored by the memory a rewrite would
save; the best is rewritten as one commit (readers on the old version finish on it; its files are freed after):

| Trigger | The rewrite |
|---|---|
| more small files (below `small-file-mb`) than dates (appends) | merges each date's files into one: one footer and one dictionary per column instead of many |
| replaced rows past `dead-rows` of the table (merge mode) | rewrites without them |
| `resort-after` appends | rewrites sorted by `sort-by` |

A rewrite starts only when `headroom-mb` (at most a quarter of the store) plus the table's size is free under the cap, so compaction never
causes an eviction. `idle-only: true` keeps it to quiet periods. Each rewrite records the bytes before and after; Admin → Lakes shows the
memory saved today and in total.

## 6. Never on disk: what is refused and what is allowed

A connector whose root is `mem://` is **memory-only**, and so is what is read from it. One check sits in front of every place Drishti
writes:

| Where Drishti writes | Memory-only data |
|---|---|
| The connector's disk caches: documents (RocksDB), id-map files, index segments | **off** for that connector, whatever its settings |
| A scheduled report (`/me/reports`) | **refused** (`DRS-5219`) when saved and when run |
| A share's picture, its e-mail, a note copying the page's values | **refused** (`DRS-5219`); a share as a link is allowed |
| A BI run's stored result | not stored: the definition is kept, the rows are recomputed when opened |
| DuckDB's temporary spill | **off** on a server with memory stores (a query past `memory-limit` fails instead of spilling) |
| Ask about this page (an outside AI provider) | **refused** (`DRS-5219`) |
| **A user's download** (the Data view) | **allowed**: streamed from memory with no temporary file, audited (the audit line keeps the selection's shape, not its values) |
| Another node of the cluster | answers over Drishti\*net only ([section 8](#8-a-cluster-the-owner-node-binds-the-pack)) |
| Logs and audit | ids and counts only |

A refusal says why, in words: *"This report uses news data held in memory only (contract VX-2026-07); it cannot be delivered by a
scheduled report. Only a download by you may leave the server."*

**The proof.** The server's test `NewsInMemorySentinelTest` fills the news pack from a feed whose every headline, snippet, source and
company name is a unique marker, opens the pages, downloads, tries a report and a refill, then searches the run's data folder, the
process's temporary folder and everything logged: no marker is found.

## 7. Memory, the JVM and the host

- **Direct memory.** The stores live in direct buffers. Every launcher sets `-XX:MaxDirectMemorySize=256m` for the server's own I/O; with
  memory stores the limit must be 256 MB plus the stores' caps. `tools/windows/start-server.ps1` computes it from the configuration
  (`tools/lake/memory_caps.py`); the Docker image and the ECS entrypoint take `DRISHTI_LAKE_MEMORY_MB` (the caps added up); a systemd unit
  or a run configuration adds `-XX:MaxDirectMemorySize=<256 + caps>m` after the existing flag (the later one wins). Admin → Health turns
  **red** when the running limit is below it.
- **Memory is given back.** A deleted or evicted file's chunks return to the pool at once; free chunks are released to the operating
  system after `idle-trim-minutes` of quiet, and at once when a cap is lowered (`Unsafe.invokeCleaner`, Java 21, no preview feature).
- **Host checks** (Admin → Health, *In-memory lake*): no swap (or encrypted swap), no heap dump on out-of-memory unless to a RAM disk, no
  JFR recording at launch, the temporary folder on a RAM disk. Each is amber when not met.
- **The memory budget** shows the stores as an off-heap pool beside the heap pools; busy protection shows each store's use against its
  cap. Neither makes new work wait for a store: the store bounds itself.
- **Sizing.** Memory per table is close to its Parquet size with zstd, plus at most one partly filled chunk per file. Measured on the demo
  tables, [RESOURCES_AND_SCALE.md](../architecture/RESOURCES_AND_SCALE.md#58-the-in-memory-lake-measured): the in-memory files are 4 to 42
  times smaller than the decoded JSON and 1.6 to 3.9 times smaller than the disk lake's Snappy files. Small tables want small chunks
  (`chunk-kb: 64`): a 30 KB log file holds a whole chunk.

## 8. A cluster: the owner node binds the pack

A memory store is owned by one node, which fills it and serves the pack whose connectors read it; the others reach the pack through
Drishti\*net as they reach any pack a peer holds, and keep nothing of it.

```yaml
# config/net/node.yaml on the owner
memory-stores: [news]
```

On the other nodes the same configuration may be present: a store not named in their `memory-stores` is not loaded, its tables show as
*held by its owner node*, the kinds are not offered in their digest, and a request for them is forwarded to the owner. Without
`memory-stores`, each store's `owner:` setting (true by default) decides. A standby owner is not built.

## 9. What an administrator sees

| Where | What |
|---|---|
| Admin → Lakes (`GET /api/v1/admin/lakes/memory`) | each store: cap, used, allocated, contract, refusals; each table: state in words, bytes in memory, the files' bytes, the decoded bytes and their ratio, rows, version, last and next refill, pinned, evictions, refusals, compactions, memory saved |
| `drishti.py lake memory status` / `jobs` / `refill` | the same from the terminal; `--json` for scripts |
| Admin → Health, *In-memory lake* | amber: a store over 85% of its cap, a refused write, a host check; red: a pinned table empty after warm-up, the direct-memory limit below the caps |
| Admin → Health, *Busy protection* | a gauge per memory store (its use against its cap) |
| Admin → Caches, memory budget | the stores as the off-heap pool `memory-lake` |

## 10. Every setting

All under `drishti.lake.memory` (environment `DRISHTI_LAKE_MEMORY_…`; command line `--drishti.lake.memory.…`); the full table is in
[CONFIGURATION.md](../admin/CONFIGURATION.md).

| Setting | Default | Meaning |
|---|---|---|
| `stores.<name>.max-mb` | 0 | the hard cap; 0 holds nothing |
| `stores.<name>.chunk-kb` | 1024 | the chunk size (64 for stores of small tables) |
| `stores.<name>.contract` | | shown on every refusal and on Admin → Lakes |
| `stores.<name>.owner` | true | this node owns the store (node.yaml `memory-stores` wins when set) |
| `stores.<name>.format` | delta | `delta`; Iceberg tables are read and written through the same store ([section 11](#11-data-contract-of-a-memory-table)) |
| `stores.<name>.idle-trim-minutes` | 10 | free chunks go back to the operating system after this much quiet |
| `tables[].name`, `.store`, `.folder` | | the table, its store, its folder (the name) |
| `tables[].source` | | `url` (+ `headers`), `file`, `connector` + `kind` (+ `ids` or `ids-from`) |
| `tables[].mode` | replace | `replace`, `append`, `merge` |
| `tables[].key`, `.sort-by` | | the id's fields; the sort (the key) |
| `tables[].business-date` | today | `today`, a date, `field:<NAME>` |
| `tables[].columns` | | fields promoted beside the document |
| `tables[].every`, `.cron`, `.signal`, `.warm-up` | , , true, | when it runs |
| `tables[].pin`, `.keep-versions`, `.codec` | false, 2, zstd | never evicted; versions kept; `zstd`, `lz4`, `snappy` |
| `history-keep`, `job-threads` | 50, 2 | runs kept per table; jobs at once |
| `compaction.*` | enabled, 30 s idle, 512 MB headroom, 8 MB small files, 0.2 dead rows, 20 appends, 60 s | [section 5](#5-automated-compaction) |

## 11. Data contract of a memory table

A memory table has the Delta connector's layout: `id` (string, the key), `doc` (string, the document as JSON), `business_date` (date,
the partition), and one string column per `columns` entry; Parquet files compressed with zstd at level 3 (Snappy with `codec: snappy`
gives byte-equal files to the disk lake's), row groups of 256 KB, a checkpoint every 10 commits, `keep-versions` versions readable.
The pack's own data contracts (`config/packs/<pack>/contracts/`) describe the documents; holding them in memory changes nothing in them.

Iceberg: the Iceberg engine reads and writes through the same `mem://` store (`warehouse: mem://<store>/…`), and the Iceberg connector
keeps a memory warehouse off every disk as the Delta connector does; the replenish jobs write Delta.

## 12. Troubleshooting

| What you see | Why, and what to do |
|---|---|
| `UP: mem://news is held in memory only and is empty now` | the warm-up has not reached it, or its job failed: `drishti.py lake memory jobs --table <t>` gives the reason |
| a run `refused: the store is full` | raise `max-mb` (and restart, so the launcher raises the direct-memory limit), unpin a table, or lower `keep-versions` |
| Health red, *direct-memory limit* | the server was started without the caps in `-XX:MaxDirectMemorySize`: use the shipped scripts or add the flag |
| `DRS-5219` on a report, a share, an Ask | as the contract asks; download the rows from the Data view instead |
| a table *evicted, a refill is queued* | the store needed room for another table; pin the tables that must stay |
| a run `failed: … connector cannot list its documents` | that connector computes documents when asked: give `ids` or `ids-from`, or read its feed by `url` |
