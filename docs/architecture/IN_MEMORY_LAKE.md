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

**Status: built in 2.9.11 on Java 21, with the parts section 15 marks as not built yet; the product owner's decisions are taken**
([section 14](#14-decisions-for-the-product-owner), 2026-11-02): the owner node binds the pack and the others reach it through the pack;
nothing leaves but downloads; whole unpinned tables may be evicted; Iceberg as well as Delta; news first; compaction automated so the
memory keeps optimizing itself. How to use it: [the in-memory lake guide](../guides/IN_MEMORY_LAKE_GUIDE.md) and
[tutorial 31](../tutorials/31-news-in-memory-only.md). The build checklist, with what is built, is in [section 15](#15-the-build-checklist).

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
9. [A cluster: the owner node binds the pack](#9-a-cluster-the-owner-node-binds-the-pack)
10. [The console's Python](#10-the-consoles-python)
11. [Configuration](#11-configuration)
12. [What an administrator sees](#12-what-an-administrator-sees)
13. [The proof: tests](#13-the-proof-tests)
13a. [Automated compaction: the memory optimizes itself](#13a-automated-compaction-the-memory-optimizes-itself)
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

- **Files as chunks off the heap, on Java 21.** A file is a list of fixed-size chunks (1 MB by default) taken from the store's chunk
  pool, so a file of any size costs at most one partly-filled chunk and the pool never fragments. Nothing of the data is on the Java
  heap except the listing (a sorted map of names to chunk lists: about 200 bytes a file). The build uses only what Java 21 has without
  preview flags ([section 3a](#3a-on-java-21)); nothing in it needs Java 25.
- **The `Storage` contract, met exactly:** sorted listings (`listFrom`), `status`, positional reads (`open` → `RangeReader` over the
  chunks, no copy), `create` (visible when closed), **`createIfAbsent` truly exclusive** (`exclusiveCreates() == true`: a concurrent
  second commit of the same Delta version loses, as on local disk), `delete`.
- **The cap is checked on every chunk taken.** A write that would pass the cap first runs the eviction ladder
  ([section 6](#6-the-hard-limit-and-the-eviction-ladder)); if that frees too little, the write fails with a clear error, the file is
  dropped, and since a Delta commit is the last file written, the table stays at its previous version, whole.
- **A restart empties it.** By design: the replenish jobs refill it ([section 4](#4-filling-the-tables-replenish-jobs)).

## 3a. On Java 21

**Decided (product owner, 2026-11-02):** production may stay on Java 21, so the store is built on Java 21, with no preview feature
(Java 21's foreign-memory API is a preview there and is not used).

- **The chunk pool:** direct `ByteBuffer`s of `chunk-kb`, allocated on demand up to the store's cap and **reused**: a deleted or evicted
  file's chunks go back to the pool's free list at once, so the cap is exact and never waits for a garbage collection (Java frees an
  unreachable direct buffer only after a collection notices it).
- **Giving memory back:** when a store shrinks (its cap lowered, or the pool idle well below its cap for a while), chunks beyond what is
  in use are released explicitly (`sun.misc.Unsafe.invokeCleaner`, in `jdk.unsupported` on Java 21), not left to the collector.
- **The JVM's direct-memory limit:** every launcher sets `-XX:MaxDirectMemorySize=256m` today (the server's own I/O buffers). A server with
  memory stores needs that limit raised by the stores' caps: the start scripts, the Docker images and the Windows script compute
  `256m + the stores' max-mb` from the configuration, and Admin → Health turns red when the running limit is below it (the store would
  refuse writes long before its cap otherwise). The sizing planner adds the stores to the server's RAM line.
- **Reads without copies:** a `RangeReader` over the chunks hands the Parquet reader read-only slices of the buffers; a page that spans two
  chunks is the only copy.
- **Java 25 later, if production moves:** the pool sits behind one small interface, so a `MemorySegment` implementation can replace it
  without changing anything above it; it is not planned.

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
3. **Whole tables, least recently read first** (allowed by the product owner), never a pinned table (`pin: true`) and never the table being written. A table is
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
- **Iceberg too** (decided): an Iceberg table is a metadata file, manifests and Parquet data files, all reached through the same
  `Storage` interface, so `MemoryStorage` serves it unchanged; the Iceberg loader writes it, `catalog: path` with `warehouse: mem://…`,
  and its maintenance (expire snapshots, rewrite data files) runs in memory like Delta's. Delta is built first, Iceberg straight after it
  in the same item.
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
| Scheduled reports, e-mail attachments, inbox and share items carrying rows | **refused** (product owner): only downloads leave |
| An outside AI provider | refused |
| Another node of the cluster | over Drishti\*net only, to answer a request for the pack (section 9); never written to its disk |
| The browser's storage (local storage, the basket's sheet) | ids only, no values |
| Logs and audit | ids and counts only, never values |

The servers that hold memory-only data also need, and Admin → Health checks: **no swap** (or encrypted swap), **no heap dump on
out-of-memory** (or one written to a RAM disk), no JFR recording or core dump to disk, and the temporary folder on a RAM disk. A refused
write says why, in words: *"This view uses vendor-x data held in memory only (contract VX-2026-07); it cannot be saved to a report."*

## 9. A cluster: the owner node binds the pack

**Decided (product owner):** a memory store is **owned by one node**, which **binds the pack** whose connectors read it (news, first),
and the other nodes reach that pack **remotely, through the pack**, over Drishti\*net, as they reach any pack a peer holds today (view
and data forwarding, search shares; [CLUSTER_DEPLOYMENT](CLUSTER_DEPLOYMENT.md), `peer-only-packs`).

- **The owner** (`node.yaml`: `memory-stores: [news]`, and the pack listed for it) holds the store, runs the replenish jobs and the
  compaction, and answers its pack's reads for the whole cluster. The memory is spent once.
- **The other nodes** do not load the store; a request for the pack's kinds on them is forwarded to the owner over the encrypted
  node-to-node link, and the answer is shown, never cached on their disks (their forwarding caches are memory-only for a memory-only
  pack, the guard of [section 8](#8-never-on-disk-the-guard)).
- **While the owner restarts** the pack's screens on every node say *"news: its node is restarting; back in about a minute"*, and its
  warm-up refills the store first, then reopens the pack.
- **A second owner** (a standby that also fills the store and takes over when the first is down) is a later option, not in the first
  build; it doubles the memory.

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
          owner: true             # this node owns the store and binds its pack (others reach the pack over Drishti*net)
          format: delta           # delta | iceberg (a table may say otherwise)
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
      compaction:                 # section 13a: on by default, per store and per table
        enabled: true
        idle-only: false          # true: only when no request has arrived for idle-seconds
        idle-seconds: 30
        headroom-mb: 512          # memory kept free for a compaction's new files (the old are freed after its commit)
        small-file-mb: 8          # files below this are merged
        dead-rows: 0.2            # rewrite a file when this share of its rows is deleted or replaced (merge mode)
        resort-after: 20          # appends after which the table is rewritten sorted by sort-by
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

## 13a. Automated compaction: the memory optimizes itself

**Decided (product owner):** compaction runs by itself, so the store keeps getting smaller for the same data without anyone asking.
A compactor per store wakes after every commit and every minute, scores each table by the memory a rewrite would save, and rewrites the
best one first, one table at a time, as an ordinary atomic commit (readers on the old version finish on it; its files are freed after):

| Trigger | What the rewrite does | Why it saves memory |
|---|---|---|
| Many small files (appends, Kafka batches) | merges them into files of about 64 MB | one footer, one dictionary per column and better compression instead of hundreds |
| Rows deleted or replaced (merge mode) above `dead-rows` | rewrites the files without them | dead rows stop costing memory |
| Appends out of key order, after `resort-after` | rewrites the table sorted by `sort-by` | runs of equal values: dictionary and RLE shrink most columns by half again |
| A column's dictionary grown past Parquet's limit (falls back to plain) | rewrites with the dictionary rebuilt per file | dictionary encoding back |
| Old versions and the log | expires versions past `keep-versions`, writes a checkpoint | the log stays a few files; old files go |

- **Bounded:** a rewrite needs room for its new files before the old are freed; it only starts when `headroom-mb` (and the table's
  estimated new size) is free under the cap, so compaction never pushes the store into eviction. If there is no room, the table with the
  best saving per byte of headroom goes first, or the compactor waits.
- **Gentle:** at most one rewrite at a time per store, on one thread at low priority; `idle-only: true` restricts it to quiet periods.
- **Measured:** every rewrite records bytes before and after; Admin → Lakes shows the memory saved today and in total, and the table's
  compression ratio.
- **For Iceberg** the same compactor uses Iceberg's own rewrite and snapshot expiry.

## 14. Decisions for the product owner

All taken by the product owner on 2026-11-02:

1. **Cluster:** the owner node keeps the store and binds the pack; the other nodes access it remotely through the pack
   ([section 9](#9-a-cluster-the-owner-node-binds-the-pack)).
2. **What may leave:** downloads only; no scheduled reports, e-mail attachments, inbox or share items carrying its rows.
3. **Eviction of whole tables:** yes, for unpinned tables, with the replenish job refilling them.
4. **Iceberg too:** yes, Delta first and Iceberg in the same item.
5. **The first feed: news** (the `news-store` connector's kinds `news`, `news-topic`, `quote`, `listed-company`, filled from the news
   plugin by a replenish job); with **automated compaction** so the memory keeps optimizing itself
   ([section 13a](#13a-automated-compaction-the-memory-optimizes-itself)).

## 15. The build checklist

| # | Part | Size | Built (2.9.11) |
|---|---|---|---|
| 1 | `MemoryStorage` (`mem://`): chunks off the heap, the cap, exclusive creates, reference-counted files, tests against the `Storage` contract that `LocalStorage` and `S3Storage` pass | M | built: `drishti-lakeio` `com.ash.drishti.lakeio.mem` (`ChunkPool`, `MemoryStorage`, `MemoryStores`); the contract suite (`StorageContract`) passes on local disk and in memory; the Delta engine, the lake writer and Iceberg's file access take `mem://` |
| 2 | Replenish jobs: config, schedule, signals, warm-up, modes replace, append and merge, history, `drishti.py lake memory` | M | built: `drishti-server` `memlake` (`MemoryLake`, `ReplenishSource`); sources by URL, file, a connector's documents, or a connector by ids; cron as well as `every`; a signal is a file's time or a connector's last update (a Kafka message signals through its connector's state) |
| 3 | The eviction ladder and the off-heap pool in `MemoryBudget`; Capacity and the planner | S to M | built, but for the planner: the sizing workbook does not have a line for the stores yet; the decoded-column cache (step 2) is not built, so the ladder goes from old versions to whole tables |
| 3a | Automated compaction (section 13a): the scoring, the headroom rule, merge, dead rows, re-sort, dictionary rebuild, expiry and checkpoints; bytes saved on Admin → Lakes | M | built (`MemoryCompactor`), but for the dictionary rebuild trigger (a rewrite rebuilds dictionaries, but a fallen-back dictionary is not detected yet); checkpoints are Kernel's every 10 commits |
| 3b | The owner node binds the pack: `node.yaml` `memory-stores`, forwarding of the pack's reads over Drishti\*net with memory-only forwarding caches, the *restarting* words | S to M | built: `memory-stores`, local routing and the digest leave the kinds to the owner (`MemoryBinding`); forwarding keeps nothing on disk (it caches nothing on disk today); the cluster-wide *its node is restarting* words are not built (a peer's screen shows the usual peer-unavailable words) |
| 3c | Iceberg on `mem://`: the loader's warehouse, path catalog, its maintenance in memory; the same tests as Delta | S to M | partly: the Iceberg engine reads and writes `mem://` (a data file written and read back), the Iceberg connector keeps a memory warehouse off every disk; replenish jobs write Delta only, and Iceberg's table commit, catalog and maintenance on `mem://` are not tested |
| 3d | **News first:** `news-store` on `mem://news`, its replenish job from the news plugin, the news pack bound to its owner node; the sentinel scan and the measured memory on it | S | built: `config/connectors.examples/memory/news-store.yaml`, `config/examples/in-memory-lake.yaml`, `NewsInMemorySentinelTest`; the news connector fills it by ids (`ids-from`) |
| 4 | The guard: `Retention.mayPersist` at every sink of section 8, the derived-data tag, refusal words, audit | M | built at the connectors' disk caches, scheduled reports, shares (picture, e-mail, copied values), BI run results, DuckDB's spill, Ask, downloads (allowed, audited); not yet: the derived-data tag on VaR and exposure captures, official records and write-back from memory-only inputs |
| 5 | Host checks in Admin → Health; DuckDB spill off; the memory-only VaR hot tier | S | built but for the VaR hot tier (VaR inputs on `mem://` still build their hot file on disk) |
| 6 | Admin → Lakes for memory stores; the screens' *refilling* words | S | built: Admin → Lakes, Health; the connector's health and the tables' words; the Sutra pages themselves do not show the words yet |
| 7 | Tests of section 13, the sentinel scan first; the measured memory ratios in RESOURCES_AND_SCALE | M | built: the sentinel scan, equality (byte-equal Parquet), the cap and the ladder, exclusive commits, chunk reuse over a soak of replaces; measured in [RESOURCES_AND_SCALE.md 5.8](RESOURCES_AND_SCALE.md#58-the-in-memory-lake-measured); not yet: Native Memory Tracking against the store's own count in a long soak |
| 8 | Docs: a guide (setting up a memory-only feed, end to end) and a tutorial, CONFIGURATION, OPERATIONS, the Help entries | S | built: [the guide](../guides/IN_MEMORY_LAKE_GUIDE.md), [tutorial 31](../tutorials/31-news-in-memory-only.md), CONFIGURATION, OPERATIONS 12.6, Help |

About L to XL in all with compaction, the owner binding and Iceberg; built in 2.9.11, on Java 21 (section 3a), with the direct-memory
limit in every launcher as part of item 1 (`tools/lake/memory_caps.py`, `DRISHTI_LAKE_MEMORY_MB`).
