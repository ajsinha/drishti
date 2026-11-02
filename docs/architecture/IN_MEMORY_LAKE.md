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
# The in-memory lake: Delta tables that never touch a disk

**Status: designed, not built.** Decisions for the product owner are in [section 14](#14-decisions-for-the-product-owner); the build
checklist is in [section 15](#15-the-build-checklist).

**The request (product owner, 2026-11-02):** some data feeds may not, by contract, be saved on disk; they may stay in memory. Wanted:
"an in-memory Delta lake with a hard limit on RAM and automated eviction", reached through the Java server (the console's Python is
optional). Answers given: (1) it is an in-memory Delta lake, not a VaR feature, though VaR may read from it, and it must be highly
memory efficient; (2) users may download what they see; (3) a server job replenishes the tables (after a restart, after eviction);
(4) every kind of source: Kafka, REST, JDBC, files, the vendor feeds.

## Contents

1. [The idea in one paragraph](#1-the-idea-in-one-paragraph)
2. [Why Parquet on an in-memory file system](#2-why-parquet-on-an-in-memory-file-system)
3. [The memory store: `mem://`](#3-the-memory-store-mem)
4. [Filling the tables: replenish jobs](#4-filling-the-tables-replenish-jobs)
5. [Memory efficiency](#5-memory-efficiency)
6. [The hard limit and the eviction ladder](#6-the-hard-limit-and-the-eviction-ladder)
7. [Reading: every Java reader unchanged](#7-reading-every-java-reader-unchanged)
8. [Never on disk: the guard](#8-never-on-disk-the-guard)
9. [A cluster](#9-a-cluster)
10. [The console's Python](#10-the-consoles-python)
11. [Configuration](#11-configuration)
12. [What an administrator sees](#12-what-an-administrator-sees)
13. [The proof: tests](#13-the-proof-tests)
14. [Decisions for the product owner](#14-decisions-for-the-product-owner)
15. [The build checklist](#15-the-build-checklist)

## 1. The idea in one paragraph

A table of a memory-only feed is an ordinary **Delta table** (its `_delta_log` and its Parquet files) whose files live in the server's
memory instead of on a disk or in S3. Every Java reader and writer of Drishti already reaches a lake through one interface,
`com.ash.drishti.lakeio.Storage` (`LocalStorage` for disk, `S3Storage` for S3); a third implementation, **`MemoryStorage`**, answers
paths of the scheme **`mem://`** from off-heap memory under a **hard byte cap**. A server **replenish job** reads the feed (any connector)
and writes the table with the Java lake writer, exactly as it writes a disk lake; the Delta connector reads it with `root: mem://…` like
any other. When memory runs short the store gives up what it can lose without harm, in a fixed order, and asks the job to refill it; it
never writes a byte to disk, and a guard refuses every other way the data could reach one.

## 2. Why Parquet on an in-memory file system

Yes: the tables stay **Delta, with Parquet files, on a file system simulated in memory**. The alternatives, and why not:

| Way to hold it | Memory per row | What it costs | Verdict |
|---|---|---|---|
| **Parquet files in memory** (compressed, dictionary-encoded) | the smallest: the same bytes as on disk, typically 5 to 15 times smaller than the decoded columns for trade-like data (repeated desks, books, currencies, dates) | a read decodes the pages it needs, as from disk, but with no I/O | **recommended** |
| Decoded Arrow columns | 5 to 15 times more | none to read; every append and every column held whole | as a small bounded cache of hot columns only ([section 5](#5-memory-efficiency)) |
| DuckDB in-memory tables (`memory_limit`) | in between | spills to temporary files when full, which the contract forbids; a whole table reloads on every change | no |
| Java objects (documents in a map) | the largest: 10 to 50 times Parquet | garbage-collection pressure | no |

Keeping the format brings the rest for free: time travel and versions, atomic commits, the same loaders, the same connector, the same
footer and page codecs (zstd, LZ4, Snappy) as the disk lake, and a table can be moved between `mem://` and disk by copying its files
when a contract allows it. The ratios above are the usual ones for such data; the build measures them on the demo's trade and position
tables (on disk and in memory, the same bytes) and the measured figures replace these in [RESOURCES_AND_SCALE](RESOURCES_AND_SCALE.md).

## 3. The memory store: `mem://`

`mem://<store>/<path>`: a **store** is one named pool of memory with its own cap (`mem://feeds/vendor-x/prices/_delta_log/…`).

- **Files as chunks off the heap.** A file is a list of fixed-size chunks (1 MB by default) taken from the store's slab, so a file of
  any size costs at most one partly-filled chunk and the slab never fragments. Off-heap: direct buffers on Java 21, `MemorySegment`
  from one shared `Arena` per store on Java 25 (the same seam as [HIGH_PERFORMANCE_NATIVE](HIGH_PERFORMANCE_NATIVE.md)). Nothing of the
  data is on the Java heap except the listing (a sorted map of names to chunk lists: about 200 bytes a file).
- **The `Storage` contract, met exactly:** sorted listings (`listFrom`), `status`, positional reads (`open` → `RangeReader` over the
  chunks, no copy), `create` (visible when closed), **`createIfAbsent` truly exclusive** (`exclusiveCreates() == true`: a concurrent
  second commit of the same Delta version loses, as on local disk), `delete`.
- **The cap is checked on every chunk taken.** A write that would pass the cap first runs the eviction ladder
  ([section 6](#6-the-hard-limit-and-the-eviction-ladder)); if that frees too little, the write fails with a clear error, the file is
  dropped, and since a Delta commit is the last file written, the table stays at its previous version, whole.
- **A restart empties it.** By design: the replenish jobs refill it ([section 4](#4-filling-the-tables-replenish-jobs)).

## 4. Filling the tables: replenish jobs

A **replenish job** is a server job that reads a source connector and writes a `mem://` table with the Java lake writer (`LakeWriter`,
the one the Java loader uses), one Delta commit a run.

- **Sources:** any connector: Kafka (from an offset, or the topic's state), REST, JDBC, files, the vendor feeds. The job reads them the
  way the Java loader reads JSON Lines today.
- **Modes:** `replace` (the run is the whole table: one commit that removes the old files and adds the new), `append` (new rows added),
  `merge` on a key (the changed rows' files rewritten).
- **When:** on a schedule (`every: 15m`, or a cron line), on the source's signal (a Kafka message, a file arriving), at server start
  (**warm-up**, in priority order), and at once when the store had to evict the table ([section 6](#6-the-hard-limit-and-the-eviction-ladder)).
- **While a table is empty or refilling** its screens say so in words (*"vendor-x prices: held in memory only; refilling since 08:02,
  about a minute"*), never a blank or a zero that looks computed.
- **Every run** is a row in the job's history (rows read, bytes written, the commit's version, the time taken), shown on Admin → Lakes and
  in `drishti.py lake memory jobs`.

## 5. Memory efficiency

The defaults favour bytes over speed, and each is a setting:

- **zstd** at level 3 for every data file (Parquet's best ratio for its decode speed); `codec: lz4` for a table read far more than
  written.
- **Dictionary encoding** on every string column (Parquet's default, kept on), and **rows sorted by the table's key** before writing
  (`sort-by`), which makes runs of equal values and shrinks most columns by half again.
- **Few versions kept:** a version still readable holds its files. Default `keep-versions: 2` (the current and the one a reader may still
  be on); older files are deleted as soon as the commit after them lands, not after a retention week as on disk.
- **Small files merged:** after `compact-after` appends (10) the job rewrites the table's small files into one, in memory, as one
  commit.
- **Checkpoints** every 10 commits (Delta's default), so the log stays a few files.
- **An optional decoded-column cache** for hot columns (`decoded-cache-mb`, default 0: off), counted in the same cap.
- **Footers and statistics** are cached on the heap as for the disk lake (`drishti.parquet.footer-cache-mb`), so a read skips row
  groups by their statistics without touching their pages.

## 6. The hard limit and the eviction ladder

`max-mb` per store is a hard limit: memory is taken only below it. When a write needs room, the store frees memory in this order and
stops as soon as there is enough:

1. **Old versions** past `keep-versions` (their files are no longer reachable).
2. **The decoded-column cache** (if on).
3. **Whole tables, least recently read first**, never a pinned table (`pin: true`) and never the table being written. A table is
   evicted **whole, never file by file** (a Delta table missing some of its files would be corrupt): it becomes *evicted, refill queued*,
   its job is queued, and readers get the words above.
4. **Refuse the write.** The commit fails, the table stays at its previous version, the job's run records *refused: the store is full*,
   and Admin → Health turns amber.

The store also joins the server's memory budget (`MemoryBudget`) as an **off-heap pool**: the Capacity page, the sizing planner and
Admin → Health show its cap and use, and the overload defences (2.9.11 row 8) refuse new loads politely before the machine runs short.

## 7. Reading: every Java reader unchanged

- **The Delta connector** with `root: mem://feeds/vendor-x` reads it like any lake: pages, search, history, BI, the Data view.
- **Readers see one version.** A read takes the table's version at its start and keeps its files alive until it ends, so a replace in
  the middle never breaks it (a reference count per file; the files are freed when the last reader leaves).
- **VaR may read from it** (scenario PVs or P&L kept in memory): the VaR inputs' connector points at `mem://…`. The VaR hot tier for
  such a date is then **memory only** (the hot data held in the off-heap pool, not written to a hot file), bounded like any open date.
- **BI** queries such a table through the server's Arrow reads, not through DuckDB's own Delta reader (which needs files on a disk), and
  DuckDB's `temp_directory` is switched off for those queries so it cannot spill.

## 8. Never on disk: the guard

A connector whose root is `mem://` is **memory-only**, and so is everything computed from it (a capture, a BI dataset, a VaR from its
inputs, a basket's figures). One server-side check, `Retention.mayPersist(...)`, sits in front of every place Drishti writes, and refuses
memory-only data unless the configuration allows that place:

| Where Drishti writes | For memory-only data |
|---|---|
| The lake on disk or S3 (loads, official records, captures, write-back) | refused |
| The connectors' disk caches, Kafka's disk cache, the document cache | off for that connector (memory caches only) |
| VaR and exposure hot files | memory-only hot tier (section 7) |
| The BI store, saved BI runs and their results | the definition is saved, the rows are not (recomputed when opened) |
| DuckDB's temporary spill | off for those queries |
| **Downloads by a user** (Data view, Excel, CSV, JSON Lines) | **allowed** (product owner), streamed from memory with no temporary file on the server, audited per download |
| Scheduled reports, e-mail attachments, inbox and share items carrying rows | refused by default (`allow: [download]` widens it) |
| An outside AI provider | refused |
| The browser's storage (local storage, the basket tray) | ids only, no values |
| Logs and audit | ids and counts only, never values |

The servers that hold memory-only data also need, and Admin → Health checks: **no swap** (or encrypted swap), **no heap dump on
out-of-memory** (or one written to a RAM disk), no JFR recording or core dump to disk, and the temporary folder on a RAM disk. A refused
write says why, in words: *"This view uses vendor-x data held in memory only (contract VX-2026-07); it cannot be saved to a report."*

## 9. A cluster

Two ways, one of them chosen per store ([section 14](#14-decisions-for-the-product-owner)):

- **Each node fills its own** (recommended to start): the job runs on every node that serves the table. Simple and independent; the
  memory is spent once per node.
- **One owner, the others read it over Drishti\*net**: the owner node holds the table, the others read its pages over the encrypted
  node-to-node link (never caching them on disk). Memory spent once; a read on another node costs a network round trip, and the table is
  down while its owner restarts.

## 10. The console's Python

Python cannot read another process's memory, and a second copy in the console would double the memory and could disagree. The console's
Python reads through the server: the same Arrow streams as today (`Accept: application/vnd.apache.arrow.stream`), into pyarrow or polars
in one line, with the server's entitlements and masks applied. Nothing is written by the console.

## 11. Configuration

```yaml
drishti:
  lake:
    memory:
      stores:
        feeds:
          max-mb: 8192            # the hard cap (DRISHTI_LAKE_MEMORY_FEEDS_MAX_MB, -Ddrishti.lake.memory.stores.feeds.max-mb)
          chunk-kb: 1024
          contract: VX-2026-07   # shown on every refusal and on Admin → Lakes
          allow: [download]       # what memory-only data may leave as (default: download only)
          cluster: each-node      # each-node | owner
      tables:
        - name: vendor-x-prices
          store: feeds
          source: { connector: vendor-x-rest }
          mode: replace           # replace | append | merge
          key: [INSTRUMENT_ID, AS_OF]
          sort-by: [INSTRUMENT_ID]
          every: 15m
          warm-up: 1              # start-up order
          pin: true
          keep-versions: 2
          codec: zstd
```

The Delta connector reading it is an ordinary connector file with `root: mem://feeds/vendor-x-prices`. Every setting appears, as all do,
in [CONFIGURATION.md](../admin/CONFIGURATION.md) in YAML, environment and command-line form.

## 12. What an administrator sees

- **Admin → Lakes** lists memory stores and tables: cap, used, per table its bytes, rows, version, last refill, next refill, pinned,
  evictions, refusals, contract.
- **Admin → Health:** amber when a store is above 85% of its cap or a write was refused; red when a pinned table is empty; the host
  checks of [section 8](#8-never-on-disk-the-guard).
- **Admin → Capacity** and the sizing planner: the stores as their own lines (RAM, not disk).
- **CLI:** `drishti.py lake memory status`, `… jobs`, `… refill <table>`.

## 13. The proof: tests

- **The sentinel scan:** load a feed whose values are unique markers, open every page, export, report and BI run on it, then search the
  data folder, the temporary folders, the logs, the dumps folder and the browser's storage for any marker: none may be found.
- **Equality:** the same table on disk and in `mem://` gives byte-equal Parquet and equal answers in every reader.
- **The cap:** writes past the cap evict in the ladder's order and then refuse, and the table is always whole at its last version;
  readers in flight during a replace and an eviction finish on their version.
- **Exclusive commits:** two writers of one version: exactly one wins.
- **Restart:** the store is empty, warm-up refills it in order, screens say *refilling* meanwhile.
- **Memory:** the store's bytes in Native Memory Tracking match its own count; no growth over a soak of repeated replaces.

## 14. Decisions for the product owner

1. **Cluster:** each node fills its own (recommended to start), or one owner read by the others.
2. **What may leave besides downloads:** scheduled reports and e-mail attachments (default: no).
3. **Eviction of whole tables:** allowed for unpinned tables (recommended), or refuse writes only.
4. **Iceberg too:** the same `MemoryStorage` serves Iceberg tables (its readers and loader use the same interface); with Delta first, or
   Delta only.
5. **The first feeds:** which feed and connector go first, for the build's own tests and the first sizing.

## 15. The build checklist

| # | Part | Size |
|---|---|---|
| 1 | `MemoryStorage` (`mem://`): chunks off the heap, the cap, exclusive creates, reference-counted files, tests against the `Storage` contract that `LocalStorage` and `S3Storage` pass | M |
| 2 | Replenish jobs: config, schedule, signals, warm-up, modes replace, append and merge, history, `drishti.py lake memory` | M |
| 3 | The eviction ladder and the off-heap pool in `MemoryBudget`; Capacity and the planner | S to M |
| 4 | The guard: `Retention.mayPersist` at every sink of section 8, the derived-data tag, refusal words, audit | M |
| 5 | Host checks in Admin → Health; DuckDB spill off; the memory-only VaR hot tier | S |
| 6 | Admin → Lakes for memory stores; the screens' *refilling* words | S |
| 7 | Tests of section 13, the sentinel scan first; the measured memory ratios in RESOURCES_AND_SCALE | M |
| 8 | Docs: a guide (setting up a memory-only feed, end to end) and a tutorial, CONFIGURATION, OPERATIONS, the Help entries | S |

About L in all, as one release theme or the larger of two items in one. Java 25's `MemorySegment` makes part 1 simpler; it works on
Java 21 with direct buffers.
