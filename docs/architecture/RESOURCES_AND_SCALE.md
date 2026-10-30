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
# Resources and scale: memory, CPU and disk

Status: design, not built. The proposals of this document (marked **Proposal**) are the 2.9.7 memory and CPU diet D1 to D10 and the
caches, disk and loads items O1 to O8 of [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#297-the-order-of-the-remaining-work-and-the-memory-and-cpu-diet-product-owner-2026-10-30);
none is built. Every statement marked **Today** was read in the code or configuration of `develop` at 02f9c3d4d (2026-10-30) and names
the file and line or the setting; where a number is computed rather than measured, the footnote says how.

This is the single design for how Drishti uses memory, CPU and disk, how its caches and memory-mapped files stay bounded and fresh, and
how it scales. Where it overlaps an older document, this one decides; the older one keeps its detail and links here:
[ARCHITECTURE.md section 14](ARCHITECTURE.md#14-concurrency-and-performance) (concurrency),
[section 16.2](ARCHITECTURE.md#162-choosing-a-data-mechanism) and [16.3](ARCHITECTURE.md#163-how-memory-mapped-data-follows-the-lake),
[VAR_SHARDING.md](VAR_SHARDING.md) (the shard machinery), [VAR_GUIDE.md sections 25 to 27](../guides/VAR_GUIDE.md#25-one-business-date-in-memory)
(the VaR date in memory and the hot file), [EXPOSURE_GUIDE.md section 26](../guides/EXPOSURE_GUIDE.md#26-one-date-in-memory-and-the-hot-file),
[DELTA_CONNECTOR.md section 9](../connectors/DELTA_CONNECTOR.md#9-memory), [SCALE_STRATEGY.md](SCALE_STRATEGY.md),
[SUPER_SCALE.md](SUPER_SCALE.md), [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md), [PERFORMANCE.md](../admin/PERFORMANCE.md) and
[OPERATIONS.md section 12.1a](../admin/OPERATIONS.md#121a-direct-memory-and-the-diagnostics-of-a-long-run).

## Contents

1. [Purpose, principles and non-goals](#1-purpose-principles-and-non-goals)
2. [The resource model](#2-the-resource-model)
3. [Inventory of every cache and memory-mapped file today](#3-inventory-of-every-cache-and-memory-mapped-file-today)
4. [Freshness and invalidation](#4-freshness-and-invalidation)
5. [Memory design](#5-memory-design)
6. [CPU design](#6-cpu-design)
7. [Disk design](#7-disk-design)
8. [Observability](#8-observability)
9. [Scale-out](#9-scale-out)
10. [Benchmark, profiling and soak method](#10-benchmark-profiling-and-soak-method)
11. [The work plan](#11-the-work-plan)
12. [Decisions for the product owner](#12-decisions-for-the-product-owner)
13. [Glossary](#13-glossary)

---

## 1. Purpose, principles and non-goals

**Purpose.** A bank runs Drishti for weeks without a restart, on hardware sized from this document, and an administrator can see at any
moment where every gigabyte of memory and disk went and why. The figures that size the hardware (the capacity slides of both VaR decks)
come from the same model as this document ([section 2.3](#23-sizes-at-bank-scale)), so the decks, the docs and the code agree.

**Principles.**

| # | Principle | What it means in practice |
|---|---|---|
| P1 | **Compute on demand; never store derived figures** | A VaR, an exposure profile, a netting-set MtM is computed from trade-grain inputs when asked ([COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md)); what is kept is a cache of an answer or a copy of inputs, both disposable. Official records are the one exception, and they are records, not caches |
| P2 | **Every cache bounded by size and by age, and keyed by data version** | A cache has a byte (or weighed) limit and a TTL or an idle expiry; its key includes the data version of what it was built from, so it is never stale, only cold. A count-only bound is a defect when its entries vary in size |
| P3 | **Off-heap for bulk, heap for maps** | Anything that grows with positions × scenarios (vectors, paths) lives in memory-mapped files on local disk and the page cache; the heap holds what is indexed and small. D1 extends this to the skeleton's ids and columns |
| P4 | **Shard what grows with the bank** | Rows split across owners; anything every member needs must be small or mapped. Adding servers must reduce per-server memory, not copy it |
| P5 | **Measure before changing** | Every D and O item starts from a JFR recording and a heap histogram of the 200K benchmark ([section 10](#10-benchmark-profiling-and-soak-method)) and ends with the same measurement; a change that does not move its number is reverted |
| P6 | **Java 21 and 25 both supported** | The build targets 21 (`pom.xml`, `maven.compiler.release` 21); no 25-only API; a 25-only gain (compact object headers, no pinning in `synchronized`) is switched on only when the JVM is 25, as the Docker image does (`deploy/docker/server.Dockerfile`, its `ENTRYPOINT`) |
| P7 | **VaR equal to the bit across shards** | A node's VaR is its float64 aggregate on every member ([VAR_SHARDING.md section 13](VAR_SHARDING.md#13-decisions)); no memory or CPU change may alter a summation order that this depends on |
| P8 | **Everything visible to an administrator** | Every cache, hot file, mapped file and data folder appears in Admin → Caches or Admin → Health → Disk with its size against its limit, and can be purged; nothing grows where nobody looks |

**Non-goals.** A distributed cache (Redis, Hazelcast) for answers: each server's caches are local and disposable, and the cluster
shares data only through the lake and Drishti\*net. Storing derived figures to save CPU (P1). Arrow for the hot files or shard partials
(measured, no gain: [ARCHITECTURE.md section 16.1](ARCHITECTURE.md#161-data-formats-on-the-wire-json-by-default-arrow-where-it-pays)).
An incubating Java API (the Vector API) in production code ([section 6.7](#67-where-simd-would-and-would-not-help)). Changing the hot
file's format beyond adding sections (format 3 stays readable).

---

## 2. The resource model

### 2.1 Where each kind of data lives

```
                       ┌──────────────────────────── one server (JVM) ────────────────────────────┐
  the lake             │  JAVA HEAP (-Xmx)                       OFF-HEAP, MAPPED (not -Xmx)        │
  (Delta / Iceberg,    │  ─────────────────                      ────────────────────────────       │
   Parquet; S3 or      │  • the skeleton of each open VaR date   • VaR hot files: PMAT, TMAT        │
   local)              │    (ids, columns, links, aggregates)      (float32 rows), AGGS             │
        │              │  • exposure set statistics, index       • exposure hot files: VMAT, EMAT   │
        │ Parquet      │  • answer caches (VaR 512, exposure     • Delta / Iceberg id maps (.idmap) │
        │ decode       │    1,000, BI plan 2,000 ...)            • multi-day type-ahead segments    │
        ▼              │  • connector caches: id maps on heap,   • Arrow IPC to Python workers      │
  ┌───────────┐ read   │    column sets, documents, day parts    ────────────────────────────       │
  │ connectors│──────► │  • layouts, fingerprints, expressions   DIRECT (-XX:MaxDirectMemorySize)   │
  └───────────┘        │  • live topics, view streams            • ChannelIo pool (8 MB at most)    │
        │              │  • identity, settings, pack access      • Netty, Kafka fetch buffers       │
        │              │                                         NATIVE (malloc)                    │
        │              │                                         • RocksDB block cache, memtables   │
        │              │                                         • DuckDB (BI store, memory-limit)  │
        ▼              └────────────┬───────────────────────────────────────────┬──────────────────┘
  LOCAL DISK under the data folder  │ page faults                               │ writes
  ─────────────────────────────────▼───────────────────────────────────────────▼──────────────────
  var/hot/<date>/inputs-*.vhot   ccr/hot/<date>/run-*.xhot   cache/<connector>/idmaps   cache/<connector>/docs (RocksDB)
  index/ (type-ahead segments)   derived/<connector> (grouped pages)   bi-store/ (Parquet layers)   bi-runs/   tmp/ (spills)
  ─────────────────────────────────────────────────────────────────────────────────────────────────
  OPERATING SYSTEM PAGE CACHE: the pages of every mapped and recently read file; shared, evictable, not counted in -Xmx
```

| Tier | Holds | Bounded by (today) | Survives a restart |
|---|---|---|---|
| **Heap** | maps and small arrays: the skeleton, statistics, answers, connector caches | `-Xmx`; each cache its own limit ([section 3](#3-inventory-of-every-cache-and-memory-mapped-file-today)); no total | no |
| **Off-heap, mapped** | the bulk: vectors, paths, id maps, type-ahead segments | the files' sizes; resident pages are the operating system's choice | the files, when kept |
| **Direct** | I/O buffers | `-XX:MaxDirectMemorySize=256m` (every launcher, [5.6](#56-direct-memory-the-296-fix)) | no |
| **Native** | RocksDB, DuckDB, the JVM itself | RocksDB per cache, DuckDB `memory-limit`; glibc `MALLOC_ARENA_MAX=2` (`drishti-stack/stack.py`, `node_env`) | no |
| **Page cache** | the pages of mapped and read files | the machine's free RAM | no |
| **Local disk** | hot files, caches, spills, BI layers | per item, mostly by count ([section 7](#7-disk-design)); no total, no free-space guard | yes (unless deleted at shutdown) |
| **The lake** | everything authoritative | the lake's own retention ([7.6](#76-the-lakes-own-retention)) | yes |

### 2.2 How a request touches each tier

A VaR node question reads one float64 aggregate from the heap (under 1 ms). An ad-hoc scope resolves its positions on the heap skeleton
(ids, columns, bitsets), then sums float32 rows from the mapped `PMAT`: page-cache hits at memory bandwidth, page faults at disk speed
the first time. A trade page reads the Delta id map (mapped from disk), one Parquet row group (decoded on the heap through the 128 KB
direct pool), then caches the document on the heap and in RocksDB. A BI visual reads a Parquet layer through DuckDB (native memory) and
caches the answer on the heap.

### 2.3 Sizes at bank scale

The reference bank of the decks: **2.3 million positions**, **1,518 values a position** (509 for 1-day VaR, 509 sized for 10-day, 250
each for stressed 1-day and 10-day), **15,000 portfolios**, 5,700 nodes, two dates open, four listed currencies, 60 hot dates on disk.
The constants are the measured 100,000-trade benchmark of [VAR_GUIDE.md section 27.3](../guides/VAR_GUIDE.md#273-the-tiered-store-measured-and-extrapolated-as-built-280)
scaled by K = 23 (footnote 1).

| Item | Per position | At 2.3M, 1-day (509) | At 2.3M, 1,518 values | Where |
|---|---|---|---|---|
| vectors in the lake, float64 | 4,072 B (509) | 9.37 GB a date | 27.9 GB a date | the lake |
| the hot file, float32 rows and the rest | 2,377 B (509) | 5.47 GB a date | 16.3 GB a date | local disk, page cache |
| **the skeleton** (ids, columns, links, aggregates) on the heap | **about 800 B** | **1.84 GB a date** | 2.23 GB a date (aggregates widen) | **heap, on every member** |
| of which the aggregates (5,700 nodes and the books) | | 0.20 GB | 0.59 GB | heap |
| a listed currency's book (USD's copy converted) | 2,500 B | 5.75 GB a date | 17.1 GB a date | local disk, page cache |
| a load's spill and new file | | 14.8 GB transient | 44.2 GB transient | local disk |
| 15,000 portfolios' memberships | 16 B a member id | 31 MB (130 positions a portfolio, assumed) | the same | heap |
| a scope's bitset (`OrdinalSet`) | 1 bit | 288 KB | the same | heap, per answer |

**Footnote 1 (the derivation).** From `capacity.py` and `aws_calc.py`, the decks' shared model (outside the repository; numbers only):
`HOT = 237.7 MB × 23 = 5.47 GB`; `HEAP_DATE = 80 MB × 23 = 1.84 GB` (80 MB is the measured heap of an open 100,000-trade date,
VAR_GUIDE 27.3); `AGG = 8.6 MB × 23 = 0.20 GB`; `CCY = 250 MB × 23 = 5.75 GB`; width multiplier `1,518 / 509 = 2.98`, so
`HOTA = 16.3 GB`, `HEAPA = 1.84 + 0.20 × 1.98 = 2.23 GB`; a load `F64A + HOTA = 27.9 + 16.3 = 44.2 GB`. The live heap of one server is
`2 × HEAPA + 2 × HEAP_DATE + 2 (a load's sort) + 4 (the server) = 14.1 GB`, × 1.5 for the collector = **21.2 GB needed**, ÷ 0.61 (39%
headroom) = 34.8 GB, so `-Xmx36g`; the page cache `2 × HOTA + 4 × CCY + 2 × CCYX = 78.4 GB`; one server needs 151 GB of RAM. The skeleton
scales with positions (× 23 from 100,000) and is **not divided by sharding** ([VAR_SHARDING.md section 5.1](VAR_SHARDING.md#51-the-skeleton-in-plain-words)):
this is the case for D1.

**What the 800 bytes are** (read in `drishti-compute/.../var/VarBook.java` (lines 124-195)): per trade a `String` id in `tradeIds`, an entry in
`tradeOrdinal` (`HashMap<String, Integer>`, line 175) and in the upper-case index `tradeUpper` (line 194), 17 int32 column codes and two
float64 numbers (`Columns`), `positionOf`; per position a `String` id, `positionIndex` (`HashMap`, line 186) and `positionUpper`, 18 int32
and six float64 position columns, its run (`posFrom`, `posTo`). The maps' entries, boxed `Integer`s and `String` objects are about half of
it; the columns about a third (VAR_GUIDE 27.1's per-1,000-trade table).

---

## 3. Inventory of every cache and memory-mapped file today

One row per cache or mapped file, read in the code of `develop` (paths shortened: `server/` is
`drishti-server/src/main/java/com/ash/drishti/server/`). **Bound** uses: *bytes* (a weigher in bytes), *count* (entries, whatever their
size), *TTL* (age), *idle* (time since last use). **Admin** is how an administrator sees and clears it today: *Caches* is Admin → Caches
(`GET /api/v1/admin/caches`, `POST /api/v1/admin/caches/{name}/purge`, `server/api/CacheController.java` (lines 68,89)), which lists the engine,
history, the Delta memory budget and each connector, nothing else. A ⚠ marks an unbounded or count-only bound
([3.9](#39-findings-unbounded-and-weakly-bounded)).

### 3.1 VaR

| Cache | Owner, where | Key | Bound | Eviction | Learns of new data | Admin today |
|---|---|---|---|---|---|---|
| open dates | `VarService.dates`, `server/var/VarService.java:73`; heap (skeleton) + mapped | business date | ⚠ count: `drishti.var.hot-dates` 2; a date over `memory-budget-mb` (0: a quarter of the heap) refused | newest N by date (`evictOld`, line 582) | the signal (`VarOfficial.onLoad`); otherwise **never while open** ([4.3](#43-what-happens-without-a-signal-today-read-in-the-code)) | `POST /api/v1/var/reload` (all dates), `GET /api/v1/var/status`; not in Caches |
| answers | `VarService.cache`, line 97; heap | date, currency, version, intraday stamp, view, scope, membership (line 828) | ⚠ count: 512, hard-coded (line 64) | W-TinyLFU; by date prefix on evict or reload | key has the version | `var reload`; not in Caches |
| currency books | `VarService.inCurrency`, `layered`, lines 75, 78; heap + mapped | `date|CCY|version` | with their date | dropped with the date (`retireCurrencies`) | key has the version | as dates |
| build-ahead status | `VarService.aheadState`, line 92; heap | `date|CCY` | ⚠ none (only `put`) | none | — | `var status` |
| views per role | `VarAccess.views`, `server/var/VarAccess.java:56`; heap (bitsets) | `date@version@ccy|levels` | ⚠ none | none | key has the version | none |
| official indexes | `VarOfficialStore.indexes`, line 56; heap | date, currency | ⚠ none | none | — | none |
| position vectors (heap tier only) | `VarBook.positionCache`, `drishti-compute/.../var/VarBook.java:140`; heap | position index | ⚠ none within a date (up to positions × N × 8 bytes: 9.4 GB at 2.3M) | with the date | — | none |
| FX for conversions | `VarLoader.fxCache`, line 763 | date, versions | count 32, then cleared | clear all | key has versions | none |
| shard maps, assembled dates | `VarShards.maps`, `assembled`, lines 80-81 | date, digest | count 64 then cleared; per date | clear all; per date | digest | `GET /api/v1/var/shards` |
| shard books | `VarShardBooks.books`, `server/var/VarShardBooks.java:72`; mapped `<hot>/<date>-shard-<i>/` | date, ccy, map digest, shard, inputs digest | ⚠ none by date (released only on handover or supersede) | handover rule (`VarShards.release`, line 485) | digest | `var shards`; not in Caches |
| **hot files** | `VarTier`, `server/var/VarTier.java`; `drishti.var.tier.dir` (`<data>/var/hot/<date>/inputs-<16 hex>.vhot`) | the inputs' digest | open: `hot-dates` 2; disk: ⚠ count `tier.disk-dates` 60 (counts **files**: currencies and shards included); currencies `currency-retention-dates` 5; **no bytes, no free-space check** | least recently used, never an open one (`VarTier.opened`, lines 240-273); a stale file deleted when the date's new file opens | the digest | `var status` (`tier.files`, `diskBytes`); `var reload` drops open dates, never files |
| load spill | `RowSpill`, `D/spill-*.f64`, `D/build-*.part` | per build | one date's float64 vectors (9.37 GB at 2.3M) while it loads | deleted after the build; at start | — | none |

`keep-on-shutdown` (false): files deleted at shutdown and leftovers at start (`VarTier.start`, lines 92-115; shutdown 432-449).
`close-grace-seconds` (60) keeps an evicted date mapped for running requests.

### 3.2 Exposure

| Cache | Owner, where | Key | Bound | Eviction | Learns of new data | Admin today |
|---|---|---|---|---|---|---|
| open dates | `ExposureService.dates`, `server/exposure/ExposureService.java:100` | date | ⚠ count: `drishti.ccr.exposure.hot-dates` 2; `memory-budget-gb` while loading | newest N | the signal; otherwise never while open | `POST /api/v1/exposure/reload`, `/status`, `/retention` |
| answers | `ExposureService.cache`, line 102 | scope, date, run, versions, parameters, view | ⚠ count: 1,000, hard-coded (line 94) | W-TinyLFU | key | `exposure reload` |
| trade paths | `ExposureService.paths`, line 104 | `date|version|hashCode|size` (line 290: **collision-prone**, [6.5](#65-virtual-threads-and-pinning-on-java-21-d10)) | bytes: 256 MB, hard-coded | W-TinyLFU | key | none |
| views per role | `ExposureAccess.views`, line 68 | `date@version|levels` | ⚠ none | none | key | none |
| official indexes | `ExposureOfficialStore.indexes`, line 52 | date | retention (7 years) | retention purge | — | none |
| **hot files** | `ExposureTier`; `drishti.ccr.exposure.hot-folder` (`<data>/ccr/hot/<date>/run-<16 hex>.xhot`) | inputs' digest | open 2; disk ⚠ count `disk-dates` 60, **overridden by the retention**: a file within `retention.set-matrices-years` (1) or a month-end within `set-matrices-month-end-years` (7) is never trimmed (`ExposureTier.java` (lines 164-171)): about 250 + 84 files; no bytes, no free-space check | least recently used outside the retention | digest | `exposure status`, `exposure retention` |

### 3.3 BI (Rūpaka)

| Cache | Owner, where | Key | Bound | Eviction | Learns of new data | Admin today |
|---|---|---|---|---|---|---|
| query results | `QueryService.cache`, `server/bi/engine/QueryService.java` (lines 92,142-146) | dataset, its hash, layer and live generations, query, row rules, masks | bytes `drishti.bi.query.cache-max-bytes` 64 MiB; `cache-entries` 200; TTL `cache-ttl` 15 m | W-TinyLFU + TTL | the signal drops the dataset (line 880); a version change seen per query | dataset refresh (`POST /api/v1/bi/datasets/{id}/refresh`); not in Caches |
| plan answers | `ResultCache`, `server/bi/plan/ResultCache.java:47` (made in `MqService.java:162`) | plan hash, security, generation | ⚠ count `drishti.bi.plan.cache-entries` 2,000; TTL `cache-ttl` 15 m for current-state answers; idle `cache-idle` 1 h | count, TTL, idle | generation | none |
| latest date | `Layers.latest`, `Layers.java:88` | dataset | TTL `drishti.bi.query.latest-ttl` 30 s | TTL | TTL | none |
| live snapshot | `Layers.liveAt`, line 687, and `live/<key>/<table>/date=live/part.parquet` | dataset, table | TTL `live-ttl` 5 s | TTL | TTL | none |
| layers on disk | `Layers`; `drishti.bi.query.store-dir` (`<data>/bi-store`) | dataset, table, date, generation | ⚠ **no retention**: partitions stay until rebuilt or invalidated | none | the signal invalidates a date whose version moved | `GET /api/v1/bi/datasets/{id}/layers` |
| layer bookkeeping | `Layers.dateGenerations`, `epochs`, lines 111, 889; `DatasetService.parsed`, line 76 | dataset × date; path | ⚠ none | none | — | none |
| transforms | `TransformService.cache`, line 63; result files under `drishti.bi.transform.work-dir` | derived dataset, inputs | TTL `drishti.bi.transform.cache-ttl` 30 m; ⚠ `cache-entries` 20 **per dataset** | oldest first, file included | inputs | none |
| expressions | `Expressions.CACHE`, `server/bi/expr/Expressions.java:49` | text | count `drishti.bi.expr.parse-cache` 10,000, then cleared | clear all | — | none |
| BI runs' results | the run store, `config/runs/runs.yaml` (lines 52-56) (`<data>/bi-runs`) | run | bytes `store.max-bytes` 20 GB, `max-bytes-per-user` 2 GB | oldest | — | Runs tab |

### 3.4 Engine, history, explain

| Cache | Owner, where | Key | Bound | Eviction | Learns of new data | Admin today |
|---|---|---|---|---|---|---|
| layouts | `ViewPipeline.layouts`, `drishti-engine/.../ViewPipeline.java:156` | Sutra, kind, fingerprint | ⚠ count `drishti.engine.layout-cache-size` 10,000 | W-TinyLFU; cleared on a Sutra change and by the signal (`LoadService.java:267`) | Sutra version | Caches: `engine` |
| fingerprints | `ViewPipeline.fingerprints`, line 157 | entity, generation, date, partition | ⚠ count `fingerprint-cache-size` 100,000 | as above | generation | Caches: `engine` |
| compiled expressions | `ElCompiler`, `drishti-rachana` | text | count `drishti.rachana.expression-cache-size` | W-TinyLFU | — | none |
| explain | `ExplainService.cache`, `drishti-engine/.../explain/ExplainService.java:102` | user, entity, date, generation, locale, revision | count `drishti.explain.cache-size` 2,000; TTL 60 s | count, TTL | generation | Caches: `engine` |
| history answers | `HistoryCache`, `drishti-engine/.../history/HistoryCache.java:37` | query + the kind's data version | bytes `drishti.history.cache-mb` 256 | least recently used | version in key | Caches: `history` |
| grouped pages | `DerivedSourcePlugin.cache` (line 110), RocksDB under `<data>/derived/<name>` | date | ⚠ count: 16 dates (line 92); recent pages 32 | least recently used | data version | the connector's purge |
| map panels, recents | `MapSupport` (8 and 32, TTL); `RecentEntities` (10,000) | | count + TTL | | | none |

### 3.5 Lake readers and connectors

| Cache | Owner, where | Key | Bound | Eviction | Learns of new data | Admin today |
|---|---|---|---|---|---|---|
| Delta shared budget: day parts, id maps, column sets, documents | `DeltaSourcePlugin.Shared`, `plugins/drishti-plugin-delta/.../DeltaSourcePlugin.java` (lines 126-136) | owner, kind, table version, commit time, date (`PartKey`) | bytes: 5%, 20%, 20%, 5% of `-Xmx`; a connector's `cache-mb`, `id-map-mb`, `columns-cache-mb`, `doc-cache-mb` give it its own | W-TinyLFU | version in key; `refresh-seconds` 10 | Caches: `memory-budget` row, the connector's purge |
| Delta newest version | `DeltaSourcePlugin` `latest`, line 270 | kind | TTL `refresh-seconds` 10 | TTL | TTL | purge |
| Delta time travel | `travelled`, line 272 | version | ⚠ count 256 | count | — | purge |
| **Delta id maps on disk** | `MappedIdMap`, `id-map-disk.dir` (`<data>/cache/<connector>/idmaps/<kind>/<version>@<ts>/<date>.idmap`) | kind, version, date | ⚠ **none**: no bytes, no dates; weighed as 256 B in the heap cache (`MappedIdMap.java:152`) | other versions' folders deleted when a new map is written (`pruneOtherVersions`, line 450), even while mapped | version in path | purge deletes the folder |
| Delta document disk cache | `DiskCache` (RocksDB), `doc-disk-cache.dir` (`<data>/cache/<connector>/docs`) | table version, date, id | bytes `doc-disk-cache.max-gb` 1; TTL `doc-disk-cache.ttl` 12 h; cleared daily `doc-disk-cache.reset-at` 02:00 | FIFO compaction, TTL, daily | version in key | Caches: the connector |
| Iceberg caches | `IcebergSourcePlugin.java` (lines 174-183) | snapshot, date | per connector: layouts ⚠ count 64; `id-map-mb` 1,024; `columns-cache-mb` 1,024; `cache-mb` 512; `doc-cache-mb` 256 | W-TinyLFU | snapshot | Caches: the connector |
| Iceberg id maps on disk | `IcebergDiskCaches.java` (lines 66-67,129) (`<kind>/<day>@<fingerprint>.idmap`) | day, fingerprint | ⚠ none by bytes or dates | the same day's older fingerprints removed (line 157) | fingerprint | purge |
| Iceberg manifests, deletes | `Planner.MANIFESTS` (`drishti-iceberg/.../scan/Planner.java:61`), `Deletes.FILES` (`.../read/Deletes.java:49`) | path | ⚠ count: `-Ddrishti.iceberg.manifest-cache-entries` 2,000,000; rows: `-Ddrishti.iceberg.delete-cache-rows` 5,000,000 | W-TinyLFU | immutable files | none |
| Iceberg-external | `IcebergExternalPlugin.java` (lines 152-157) | | layouts ⚠ count 64; `id-index-mb` 1,024; `columns-cache-mb` 1,024; `doc-cache-mb` 256 | | | Caches |
| Parquet footers | `ParquetFooters.FOOTERS`, `drishti-lakeio/.../ParquetFooters.java` (lines 43-45) | path and length (immutable files) | ⚠ weighed by row groups: `-Ddrishti.parquet.footer-cache-groups` 50,000 (not bytes) | W-TinyLFU | immutable | the Delta connector's purge |
| type-ahead index | `DatedHitIndex`, `DaySegment`; `drishti.index.dir` (`<data>/index`) | connector, kind, day, table version | `index.days` 1, `index.max-memory` 600 MB, `index.max-disk` 2 GB | oldest day | rebuilt every 6 × `refresh-seconds`; `index.reload-every` 10 m | Admin → Connectors → Index |
| Kafka | `KafkaSourcePlugin.java` (lines 195-205) | entity | `cache-mb` 256; disk `disk-cache.max-gb` 10 (off by default); ⚠ `weights` map (line 322) unbounded | W-TinyLFU; tombstones | offsets | Caches |
| message state (RabbitMQ, ActiveMQ, Kafka state mode) | `MessageStateSource.java` (lines 86,124-132) | entity | `cache-mb` 128; RocksDB `state.max-gb` 10; ⚠ `known`, `weights` unbounded | | | Caches |
| other connectors' columns | Cassandra, JDBC, Redis, Aerospike, DuckDB, Mongo, File (`index-cache-mb`) | | `columns-cache-mb` 1,024 each (not in a shared budget) | W-TinyLFU | | Caches |
| S3 objects | `S3SourcePlugin.java` (lines 157-158) | object | ⚠ count `cache-entries` 10,000; TTL `cache-seconds` 30 | | TTL | Caches |

### 3.6 Identity, collaboration, write-back and the rest of the server

| Cache | Owner, where | Bound | Admin today |
|---|---|---|---|
| pack access per user | `PackAccess.effectiveByUser`, `server/security/PackAccess.java` (lines 63-65) | count 100,000; TTL 5 s; cleared by the user's and an administrator's changes | none (TTL) |
| principals, panel titles, pinned documents | `collab/Principals.java:40` (10,000, 1 min), `PanelTitles.java:56` (500, 5 min), `PinnedDocs.java:40` (200, 1 min) | count + TTL | none |
| snapshot pictures | `SnapshotService`, `drishti.collab.snapshots.cache-size` 50, `cache-ttl` 10 m | count + TTL | none |
| query-by-form specs, distinct values | `api/FormController.java` (lines 199-200,323-324) | 500 entries and a weight of 2,000,000, 12 h | none |
| API tokens, users | `ApiTokenStore` (TTL 30 s, ⚠ no size cap: one entry per token); `JpaUserStore.recent` (5 s) | | none |
| write-back overlays | `EditRecords.overlayCache`, `ownedCache`, `server/edit/EditRecords.java` (lines 72-73) | ⚠ none: one entry per entity read once any edit exists | none |
| connector overlays | `ContentConnectorOverlay.cache`, line 51 | per connector | none |
| reactive compute results | `drishti-compute/.../reactive/ResultCache.java` (lines 59-69) | `max`, `ttl` | none |
| user settings | no server-side cache (`/me/settings` reads the identity database); the console caches them ([3.7](#37-the-console)) | | |

The collaboration and content stores (`FileContentStore`, `S3ContentStore`) are stores, not caches: they read and write through
`ChannelIo` and keep no in-memory copy (soak audit H4, fixed in 2.9.6).

### 3.7 The console

All per worker process (`drishti-console`), in memory unless marked disk.

| Cache | Where | Bound | Invalidation |
|---|---|---|---|
| sign-in checks | `core/auth.py` (lines 42,175-210) | 20,000 entries; TTL `auth.recheck_seconds` 10 | expired first, then oldest; `forget()` and user stamps |
| user settings, BI identity, packs, layouts, pivots | `core/settings.py` (lines 39-62), `core/bi.py` (lines 29-69), `core/packs.py` (lines 60-105), `core/layouts.py` (lines 34-50), `core/pivots.py` (lines 40-63) | ⚠ TTL `ui.user_cache_seconds` 10 s, no size cap (one entry per user) | user stamps on disk (`core/user_stamps.py`) across workers of one host; the TTL across hosts |
| calc permissions, business dates | `core/calc.py` (lines 69-89) (60 s), `core/asof.py` (lines 135-152) (60 s) | ⚠ no size cap | TTL |
| landing figures | `core/market_landing.py:50` (120 s, cleared above 200), `core/trading_landing.py:59` (120 s, above 64) | count + TTL | TTL |
| facts of the day | `core/facts.py` (lines 397-442) | 64 entries, `ui.facts.cache_seconds` 21,600 | FIFO |
| Python results | `core/pyrunner.py` (lines 110-150) | LRU 256 entries, 64 MB (`bi.python.cache_entries`, `cache_max_bytes`), 600 s | bytes + TTL |
| embed origins, JWKS, OIDC discovery | `core/embed.py` (60 s), `core/embed_auth.py` (300 s), `core/oidc.py` (1 h) | TTL | TTL |
| compressed static files (disk) | `core/staticzip.py` (lines 46-52), `static.cache_dir` or the console's tmp | ⚠ one per file version, no cap | file version |
| live channels | `routes/api_routes.py:335` `CHANNELS`; `core/channel.py` (lines 52-68) | 2,000 frames a channel (count); `live.max_subscriptions` 32 a browser | on disconnect; dead subscriptions stay (soak M4) |

No console cache can be purged from Admin → Caches (the page purges the server's).

### 3.8 Temporary files, logs and dumps

| Item | Where | Bound | Cleaned |
|---|---|---|---|
| server temporary files | `TempFiles` (`drishti-common`), `drishti.tmp-dir` (`<data>/tmp/server/drishti-<pid>`) | per use | deleted in `finally`; the process folder at a clean stop; ⚠ a crashed process's folder is listed at start, not removed |
| console temporary files | `<data>/tmp/console` (`run_drishti_web.py` (lines 47-53)) | | ⚠ stale per-process folders stay (871 found in the soak audit) |
| server log | standard output; `drishti-stack/stack.py` (lines 302-304) appends to `run/logs/<node>.log` | ⚠ none | never |
| GC logs | `-Xlog:gc*` in every production launcher | 5 × 20 MB | rotation |
| heap dumps | `-XX:HeapDumpPath` (`run/dumps/<node>`, `<data>/dumps`) | ⚠ none: a dump is about the live heap (up to 36 GB on an owner) | never |
| audit, deploy history | `identity/audit.jsonl` (`FileAuditLog.java:71`), `DeployHistory.java:62` | ⚠ none | never |

### 3.9 Findings: unbounded and weakly bounded

**Unbounded (grow with dates, versions, entities or users, and are never trimmed):**

1. `VarAccess.views` (`server/var/VarAccess.java:56`) and `ExposureAccess.views` (`server/exposure/ExposureAccess.java:68`): per date × version
   × currency × role set; about 1 GB a week at 2.3M with hourly loads (soak audit H10).
2. Shard books (`server/var/VarShardBooks.java:72`; `VarShards.activeDates`, line 425): no release by age (H9); heap and disk.
3. Delta and Iceberg id-map files (`DeltaSourcePlugin.java` (lines 420-464), `IcebergDiskCaches.java` (lines 129-170)): no byte or date cap; mapped old
   versions deleted while mapped (H3); weighed as 256 bytes (`MappedIdMap.java:152`).
4. BI layers on disk (`<data>/bi-store`): no retention.
5. `VarService.aheadState` (line 92), `VarOfficialStore.indexes` (line 56), `Layers.dateGenerations` and `epochs` (lines 111, 889),
   `DatasetService.parsed` (line 76): small, per date or dataset, for ever.
6. `EditRecords.overlayCache`, `ownedCache` (`server/edit/EditRecords.java` (lines 72-73)): per entity read (H11).
7. Kafka and message-state `weights` and `known` (`KafkaSourcePlugin.java:322`, `MessageStateSource.java:86`): per entity.
8. The heap tier's position vectors (`VarBook.java:140`): up to 9.4 GB a date at 2.3M (only with `tier.mode: heap`).
9. The server's log, heap dumps, the audit log and the console's stale temporary folders.

**Bounded by count only (entries of very different sizes, or files of very different sizes):**

10. VaR and exposure hot files on disk: `disk-dates` 60 counts **files** (a 2.3M date at 1,518 values is 16.3 GB; a currency file
    another 17 GB), with no byte cap and no free-space guard; exposure's retention overrides it entirely (about 334 files kept by
    default; at P = 2,000, G = 50 and 100,000 sets 60 to 80 GB a file with `VMAT`, `EMAT` and `NMAT`: EXPOSURE_GUIDE 26.2 and 27.2).
11. Open dates: `hot-dates` 2 (VaR, exposure): a count of dates whose size grows with the bank.
12. Answer caches: VaR 512 (`VarService.java:64`), exposure 1,000 (`ExposureService.java:94`), BI plan answers 2,000
    (`drishti.bi.plan.cache-entries`): an answer can be a few hundred bytes or a 5,000-row contribution table.
13. Layouts 10,000 and fingerprints 100,000 (`drishti.engine.*-cache-size`); explain 2,000; Delta time travel 256; Iceberg layouts 64;
    grouped pages 16 dates (`DerivedSourcePlugin.java:92`); S3 10,000 objects.
14. Parquet footers by row groups (50,000), Iceberg manifests by entries (2,000,000) and deletes by rows (5,000,000): static, shared by
    every connector, outside every budget (H5).
15. BI transforms: 20 per derived dataset, so unbounded in the number of datasets.
16. The console's per-user caches (TTL, no size cap) and its static-file disk cache.

**Not visible in Admin → Caches:** VaR, exposure (answers, open dates, hot files, views, shard books), BI (results, plan answers, layers,
transforms), pack access, the console's caches. ARCHITECTURE section 14 says "Admins see and purge every cache at Admin → Caches": they
see the engine, history, the Delta budget and the connectors.

---

## 4. Freshness and invalidation

### 4.1 The data-load signal (today)

| Way in | Today | Where |
|---|---|---|
| API | `POST /api/v1/packs/{pack}/loads` `{kind, date, status, rows, batch…}` (idempotent per pack, kind, date and batch) | `server/loads/LoadService.java`; [DATA_LOADS.md section 4.1](../guides/DATA_LOADS.md#41-post-packspackloads-announce-a-load) |
| CLI | `drishti.py data landed --pack P --kind K --date D [--status ready|failed] [--rows N] [--batch B]` | `tools/drishti.py`, the `data landed` command |
| UI | **none**: Admin → Packs → Data loads lists the history and expectations but cannot announce (O4) | `drishti-console/web/templates/admin/loads.html` |
| Internal | the PV topic's windows announce themselves (`VarConfiguration.varPvStreamSinks`); `tools/build_demo_lakes.py` posts signals | `server/var/VarConfiguration.java` (lines 105-133) |

On `ready` the server records it, drops the caches of every connector serving the kind (`purgeCaches()`, merged within
`drishti.loads.refresh-window`, 5 s), verifies, evaluates alerts, smoke-tests, tells people
([DATA_LOADS.md section 7](../guides/DATA_LOADS.md#7-what-happens-step-by-step)); then the engines react through `LoadService.onReady`
listeners: VaR (`server/var/VarConfiguration.java:96`, `VarOfficial.onLoad`, line 113: a vector or scenario-PV signal applies the intraday
batches in place, a full run reloads the date and captures it officially), exposure (`server/exposure/ExposureConfiguration.java:69`),
measure alerts (`MeasureAlertConfiguration.java:117`) and BI (`DatasetService.onLoad`, line 1174: invalidates the date's layers whose data
version moved).

**The signal is per server.** `LoadService` does not forward an announcement to Drishti\*net peers (no peer call in the class); each
server that reads the lake must be told ([DATA_LOADS.md, FAQ](../guides/DATA_LOADS.md#13-faq): "announce on each"). In a sharded cluster an
owner that is not told keeps its shard book until the coordinator asks for a newer version (VAR_SHARDING section 13).

### 4.2 Data versions, the inputs' digest and provenance (today)

| Connector | Version of a kind (`SourcePlugin.dataVersion(kind)`) | Version of one date (`dataVersion(kind, date)`) |
|---|---|---|
| Delta | `<table version>@<commit time>` (`DeltaSourcePlugin.stamp`, line 796) | a stamp of **the date's files** in the current snapshot (`DeltaTable.filesStamp`, line 806): a commit to another date leaves it unchanged |
| Iceberg | the current snapshot | a fingerprint of the date's data files in the current snapshot (VAR_GUIDE 25) |
| others | when the connector can say (`dataVersion` default empty, `drishti-api/.../SourcePlugin.java:98`) | the kind's version |

The VaR date's version is `VarLoader.version(date)` (`server/var/VarLoader.java:102`): the vector table's date stamp, plus the scenario-PV
table's. The **inputs' digest** names the hot file (`inputs-<16 hex>.vhot`): SHA-256 over the format, the date, the versions of
`pnl-vector`, `risk-hierarchy`, `scenario-set`, the position rules and the hierarchies' configuration
([VAR_GUIDE.md section 26.2](../guides/VAR_GUIDE.md#262-the-life-of-a-date)); exposure names its file the same way (`run-<16 hex>.xhot`).
Every answer carries `provenance` (data version, batch, scenario set, tier).

### 4.3 What happens without a signal (today, read in the code)

| Holder | Notices a changed lake | Governed by |
|---|---|---|
| Delta and Iceberg connectors (id maps, columns, documents) | within **10 s**: each table's newest version is read every `refresh-seconds` (`DeltaSourcePlugin.java:270`; Iceberg `IcebergSourcePlugin.java:203`); caches are keyed by version | `refresh-seconds` (10) |
| the document cache on disk (RocksDB) | at once for new reads (keyed by table version); old entries go by TTL | `doc-disk-cache.ttl` (12 h) limits disk, not freshness |
| BI layers | the next query: each partition records the data version it was built from | the partition's version check |
| **an open VaR date** | **not at all while it stays open**: `VarService.load` (lines 287-300) returns the open date without comparing `loader.version(d)` with the date's; only a date refused earlier is re-checked. It is noticed when the date is evicted (`hot-dates`, 2, by date order, `evictOld`, line 582) and asked again, on a signal, on `POST /api/v1/var/reload`, or at a restart | **no TTL** |
| an open exposure date | the same pattern (`ExposureService`) | no TTL |
| a VaR shard book | only when a member puts the date together again or ownership moves (VAR_SHARDING section 13) | no TTL |

So without a signal the **newest VaR date, which is always open, stays on its morning data until something drops it.** ARCHITECTURE
section 16.3's sentence "when the connector's cache expires and the version is read again" is true of the connectors and BI, not of the
engines; it is corrected to point here.

### 4.4 Proposal: a version watch for the engines (O9, new)

**Design.** On a request for an open date, at most once every `drishti.var.version-check` (proposed, default 30 s) per date, compare
`loader.version(d)` (a map lookup on the connector's already refreshed layout plus a hash of the date's file list) with the date's
version. If it moved: answer from the open date with `provenance.warnings` "the lake's data of D changed at T; a new version is loading",
start the reload in the background (the same path as the signal's `svc.reload(d)`), and swap when ready; the old date is retired after
`close-grace-seconds`. Exposure gets the same (`drishti.ccr.exposure.version-check`).
**Failure behaviour.** The version cannot be read (log unavailable): keep answering, no warning, try at the next interval. The reload
fails: the old date stays, the warning says why (as a refused date does today).
**Tests.** A restated date with no signal is reloaded within the interval; a request during the reload gets the old figures and the
warning; no double reload under 32 concurrent requests.
**Expected gain.** Staleness without a signal bounded by 30 s plus a load, instead of unbounded. **Measure:** the time from a commit to
the first answer with the new version, in the soak (O8).

### 4.5 Purge and reload per date

| Action | Today | Proposal |
|---|---|---|
| Purge one cache | `POST /api/v1/admin/caches/{name}/purge` (`server/api/CacheController.java:89`), `drishti.py server caches purge NAME`, Admin → Caches; names: `engine`, `history`, each connector, `all` | unchanged, plus the caches that are not listed today (VaR, exposure, BI, pack access: [section 3](#3-inventory-of-every-cache-and-memory-mapped-file-today)) |
| Purge one date (O2) | **not possible**: a purge drops everything of a cache | `POST /api/v1/admin/caches/{name}/purge?date=D` (and `name=all`); `drishti.py server caches purge NAME --date D`; Admin → Caches "Purge a date". Each cache implements `purgeDate(LocalDate)`: connectors drop the date's id map, column set and documents (keys carry the date: `PartKey`), VaR and exposure drop the open date, its answers, views and currency books (as `evictOld` does, plus `VarAccess.views`), and delete its hot files; BI invalidates the date's layers |
| Reload one date (O3) | VaR: `POST /api/v1/var/reload` drops **every** open date (`VarService.reload()`, line 618; `reload(LocalDate)`, line 630, exists but only the signal calls it); `drishti.py var reload`; exposure: `POST /api/v1/exposure/reload`, all dates | `POST /api/v1/var/reload?date=D` and `/exposure/reload?date=D`; `drishti.py var reload --date D`, `exposure reload --date D`; exposure gains `ExposureService.reload(LocalDate)` |
| Announce a load (O4) | API and CLI only | Admin → Packs → Data loads → "Announce a load": kind (from the pack's kinds), date, status, rows, batch, note; it calls the same `POST /packs/{pack}/loads` as the administrator, audited |

**Failure behaviour.** A purge of a date that a request is reading: mapped files close through their readers' guard (`MappedMatrix`), so
the request finishes or gets "unloaded while this answer was computed; ask again", never a crash. A purge on one server does not purge
its peers: the UI says "on this server"; a cluster-wide purge is decision PO-6.
**Tests.** Purge one date: other dates' entries and hot files untouched; hit rate of the other dates unchanged; the audit line names the
date. **Measure:** none needed beyond correctness; the soak (O8) uses it.

---

## 5. Memory design

### 5.1 The heap skeleton (D1)

**Today.** Opening a VaR date (`VarHotFile.open`, `drishti-compute/.../var/VarHotFile.java` (lines 498-547)) reads the small sections onto the
heap: `TIDS` as a `String[]`, `TCOL` and `PCOL` as `Columns` (int32 codes and dictionaries), `PIDS`, `PRUN`, `BOOK`, `NODE`, `AKEY` and
`AGGS` (float64 M × N). `VarBook`'s constructor (`VarBook.java` (lines 151-195)) then builds four hash maps from them: `tradeOrdinal`,
`tradeUpper`, `positionIndex`, `positionUpper`. Measured: about 80 MB an open date of 100,000 trades (VAR_GUIDE 27.3), so about 1.84 GB
at 2.3M and 2.23 GB with 1,518 values; copied to **every** member of a sharded cluster, coordinator and owners alike (`ShardSkeleton`,
VAR_SHARDING section 5). It sets the owners' `-Xmx36g` ([section 2.3](#23-sizes-at-bank-scale), footnote 1).

What a request needs from it:

| Use | Needs | Hot path? |
|---|---|---|
| a node's VaR | the node's aggregate (`AGGS`) | yes, under 1 ms |
| resolve a scope by attribute (`DESK:RATES`, a counterparty, a rule) | the column codes of every trade or position, the dictionaries | yes: a scan of one or a few int32 columns |
| resolve a scope by ids (a basket of 50,000 trades) | id → ordinal | yes: 43 ms of the 52 ms of a 50,000-id basket today (VAR_GUIDE 27.2) |
| the screens' tables (contributions, children) | ids and a few columns of the rows shown | yes, a few thousand rows |
| the planner | books' runs, nodes' children and books | yes, small |
| intraday updates | id → ordinal of the updated trades | a few thousand a batch |

**Options.**

| | (a) Memory-map the skeleton | (b) Dictionary-encode it on the heap (D2) | (c) Shard the skeleton |
|---|---|---|---|
| Design | New optional sections in the hot file (format 4; format 3 still read by today's path): `TIDX` (trade ids sorted, as offsets into a UTF-8 blob, each with its ordinal: a binary search, as `MappedIdMap` does for Delta), `PIDX` (the same for positions); `TCOL`, `PCOL` (int32 codes, fixed width) and `AGGS` mapped instead of copied. The heap keeps the dictionaries (distinct values only), the nodes, the books' runs and small indexes. `tradeOrdinal`, `tradeUpper`, `positionIndex`, `positionUpper` and the `String[]`s go; the upper-case lookup uses a second sorted index | Everything stays on the heap, compactly: ids in one byte blob with int offsets (no `String` per id), each `HashMap<String, Integer>` replaced by a sorted `int[]` permutation and a binary search (no boxed `Integer`, no entry objects); repeated text in dictionaries (columns are already so) | Each member holds the full skeleton only for its own shards, plus a summary of the others (node and book aggregates, per-shard dictionaries, a filter of ids); a scope is resolved by asking each owner for its part (a bitset per shard) |
| Heap saved at 2.3M (estimate, footnote 2) | about 1.6 of 1.84 GB a date; the aggregates (0.2 GB, 0.6 GB at 1,518 values) may stay or be mapped too | about 0.7 to 0.9 of 1.84 GB (the maps and `String`s, not the columns) | about (1 − replicas ÷ owners) of the non-aggregate part: two thirds with 6 owners and 2 replicas |
| Latency | id lookup: a binary search over mapped pages, about 1 to 3 µs warm (22 steps) against about 0.1 µs for a hash probe; a 50,000-id basket about 0.1 s warm, more on a cold first touch; attribute scans read the same int32 columns from the page cache: the same speed when resident | id lookup a binary search on the heap, about 0.3 to 1 µs; scans unchanged | an attribute scope needs a round trip to every owner (1 to 5 ms in a zone); an id scope needs the ids' owners; node answers unchanged |
| Complexity | medium: two sections and a mapped reader; the `MappedMatrix` guard reused for the skeleton's lifetime; `VarBook` keeps its interface (ordinal lookups behind a small `Ids` type with heap and mapped implementations) | small to medium: inside `VarBook` and `Columns` only | large: scope resolution becomes distributed; entitlements, the screens' tables and intraday updates need remote calls |
| Failure modes | a read after unmap (the guard answers "ask again", as for rows today); page faults on a cold date (the first basket slower); a damaged section (CRC32C: the date is rebuilt from the lake) | none new | a slow or lost owner makes even a scope's resolution fail or partial (`DRS-5215`), not only its sum |
| Bit equality (P7) | unaffected: no sum changes | unaffected | unaffected for nodes |
| After a restart | with `keep-on-shutdown`, reopening a date maps instead of decoding 2.3M strings (about 0.25 s a million today) | no change | no change |
| Tests | `VarBookTest` and `VarHotFileTest` against both `Ids` implementations; a property test that every id resolves to the same ordinal on heap and mapped; reopen after unmap under 32 threads; a format-3 file still opens | the same id property test; heap histograms before and after | a cluster test of every scope kind with an owner down |

**Footnote 2.** The 1.84 GB is 800 B a position at 2.3M (footnote 1). By VAR_GUIDE 27.1's per-1,000-trade table: ids and their index
about 150 B a trade, the position index about 120 B of columns plus ids and two map entries a position, the trade columns 84 B; the rest
object headers and map overhead. (a) removes the `String`s, the map entries and the columns from the heap; what stays is the
dictionaries, nodes and runs (about 50 B a position by this estimate). (b) removes the `String` and entry overhead (about half) and keeps
the columns and offsets. The estimate is to be confirmed by the 200K benchmark's heap histogram before D1 is built (P5).

**Recommendation: (a), memory-map the skeleton, using (b)'s compact id layout as its on-disk format; not (c).**
(a) removes exactly the part that sharding cannot divide: it takes a shard owner from `-Xmx36g` to about `-Xmx20g` at 2.3M (about
`-Xmx16g` with the aggregates mapped too), and at 10M from an impractical `-Xmx118g` to `-Xmx36g`–`-Xmx53g`
([section 9.5](#95-capacity-tables)). It reuses proven machinery (the hot file's sections, `MappedMatrix`'s guard, `MappedIdMap`'s binary
search) and makes a restart's reopen faster. Its cost, a slower id lookup, falls on baskets of tens of thousands of ids, which stay well
inside the 250 ms target. (b) alone saves less than half as much and still copies the skeleton to every member; its compact layout is
what (a) writes anyway. (c) saves the most only on large clusters, at the price of a distributed scope resolution and a new class of
partial answers; it stays the next step if 10M positions on many owners still do not fit after (a) (decision PO-1).

**Configuration (proposed).** `drishti.var.tier.skeleton: mapped | heap` (default `mapped` once measured; `heap` keeps today's path and
reads format-3 files); exposure's set index the same (`drishti.ccr.exposure.skeleton`).
**API, CLI, UI.** `GET /api/v1/var/status` and `drishti.py var status` add `skeletonHeapBytes` and `skeletonMappedBytes` per open date;
Admin → Caches shows both (O1).
**Expected gain, and the measurement that confirms it.** Heap after a full GC with two dates open at 200K positions: before about
2 × 160 MB plus currencies, after at most 2 × 25 MB; extrapolated, at most 0.3 GB a date at 2.3M. A 50,000-id basket: p95 at most 150 ms
warm.

### 5.2 One cache budget (D3)

**Today.** Each cache has its own limit and nothing bounds their total ([section 3](#3-inventory-of-every-cache-and-memory-mapped-file-today);
soak audit H5). The Delta connectors share one budget (`DeltaSourcePlugin.Shared`, line 126: 5% + 20% + 20% + 5% of `-Xmx`), but every
connector with a setting of its own, every Iceberg connector (about 2.8 GB of its own caches, `IcebergSourcePlugin.java` (lines 174-183)), the
other connectors' column caches (1 GB each by default), the BI caches, the VaR and exposure answers and the engine's caches come on top.
[SCALE_STRATEGY.md section 5](SCALE_STRATEGY.md#5-memory-what-lives-in-the-heap) names the same problem.

**Design (Proposal).** One process-wide `MemoryBudget` (in `drishti-api`, so plugins use it without Spring): a share of `-Xmx`
(`drishti.memory.cache-share`, proposed 0.5) divided into **weighted pools**, each pool one or more Caffeine caches built with
`maximumWeight` and a weigher in bytes. Pool sizes are set at start and can be changed at run time
(`policy().eviction().setMaximum`).

| Pool | Weight (proposed) | Members | Shrinks first under pressure |
|---|---|---|---|
| `connector.idmaps` | 20 | heap id maps of Delta and Iceberg (mapped ones count against the mapped-file budget, [5.3](#53-releases-with-the-date-d4)) | 2 |
| `connector.columns` | 20 | the column sets of every connector | 2 |
| `connector.documents` | 6 | parsed documents of every connector | 3 |
| `connector.parts` | 4 | whole small days, Iceberg manifests and deletes, Kafka and message state | 3 |
| `engine.answers` | 20 | VaR and exposure answers, exposure trade paths, BI results and plan answers, history answers, transforms | 1 (kept longest) |
| `engine.layouts` | 10 | layouts, fingerprints, compiled expressions, explain | 1 |
| `small` | 20 | pack access, principals, snapshots, Parquet footers (weighed in bytes), form specs and distinct values | 4 |

Eviction is Caffeine's W-TinyLFU (recency and frequency; better than plain least-recently-used on scans). When the heap after a GC passes
`drishti.memory.squeeze-at` (0.85 of `-Xmx`, a `MemoryPoolMXBean` usage-threshold notification on the old generation), `MemoryBudget`
shrinks the pools with the highest number first to half for a minute. The skeletons of open dates are not in the budget: they are data,
bounded by `hot-dates`, and mapped after D1. Hot files and the page cache are outside the heap: the disk budget covers them
([section 7](#7-disk-design)).
**Hot files and the page cache.** Mapped pages compete with lake reads and RocksDB files for free RAM, not with the heap. RAM is sized as
heap + page-cache target + a load + the OS ([section 2.3](#23-sizes-at-bank-scale)); the budget's job is to stop the heap from growing
into the page cache's share.
**Start-up check.** The server adds every configured limit and logs a warning (and Health shows amber) when the caches' limits exceed
the budget, or when `-Xmx` + `MaxDirectMemorySize` + the page-cache target exceeds physical RAM.
**Configuration.** `drishti.memory.cache-share` (0.5), `drishti.memory.weights.<pool>` (the table), `drishti.memory.squeeze-at` (0.85).
A connector's own `cache-mb` stays possible and is counted against its pool.
**Failure behaviour.** A cache that cannot weigh its entries is caught by a guard test: every Caffeine builder in main code with
`maximumSize` and no byte weigher fails the build, with an allow-list for fixed-size entries.
**Tests.** The guard test; every pool filled to its limit at once stays under the share of a 2 GB heap, with no OOM.
**Expected gain.** A steady heap over weeks; no OOM from the sum of caches (the 21 October OOM of the soak audit). **Measure:** the slope
of the heap after GC over the soak's last 6 hours at most 1% of `-Xmx` an hour (PERFORMANCE.md's gate).

### 5.3 Releases with the date (D4)

**Today.** Holders that outlive the date they belong to (verified on `develop`):

| Holder | Where | What stays |
|---|---|---|
| `VarAccess.views`, `ExposureAccess.views` | `server/var/VarAccess.java` (lines 56,69,76); `server/exposure/ExposureAccess.java` (lines 68,78,85) | a view (bitsets of trades ÷ 8 bytes; it pins its book) per date × version × currency × role set; only `computeIfAbsent`, never removed; `evictOld` does not touch them |
| shard books | `server/var/VarShards.java` (lines 425-431) (`activeDates()` includes every date any peer holds), `release()` line 485; `VarShardBooks.java` (lines 72-74) (`used` written, never read) | released only when handed over or superseded, never for age: two owners keep each other's dates for ever (soak audit H9) |
| mapped id maps of old table versions | `plugins/drishti-plugin-delta/.../MappedIdMap.java:152` (`bytes()` returns 256); `DeltaSourcePlugin.Shared.ID_MAPS` (line 131, no removal listener); Iceberg `IcebergDiskCaches.java:129` | a mapping per kind × date × version; `pruneOtherVersions` (line 450) deletes the old files while still mapped, so deleted files hold disk and the address space grows |
| `VarService.aheadState` | `server/var/VarService.java:92` (only `put`, lines 330-369) | a status line per date × currency, for ever |
| `VarOfficialStore.indexes` | `server/var/VarOfficialStore.java:56` (only `put`, lines 126, 139) | every official index ever read |
| BI `Layers.dateGenerations`, `epochs`; `DatasetService.parsed` | `bi/engine/Layers.java` (lines 111,889); `bi/DatasetService.java:76` | an entry per dataset × date, per partition path |
| `EditRecords` overlay and owned caches | `server/edit/EditRecords.java` (lines 72-73) | an entry per entity ever read once any edit exists (soak H11) |
| Kafka and message-state `weights` | `KafkaSourcePlugin.java:322`; `MessageStateSource.java:86` | an entry per distinct entity, removed only on a tombstone |

**Design (Proposal).** One hook, `DateLifecycle.retired(engine, date, version)`, called by `VarService.evictOld`, `retire` and `reload`
and their exposure equivalents; each holder registers and drops its entries (views by the key prefix `date@version`). **Shard books:**
members announce **open** dates separately from **held** ones in the capability digest; a book is released when no member has had its
date open for `drishti.var.sharding.release-after` (proposed 15 minutes) and it is not among the newest `hot-dates`. **Id maps:** a mapped
map is weighed by its file size against a separate mapped-file budget, and a removal listener unmaps it through `FileMaps` behind a
readers' guard; a new table version evicts the old version's keys for the same kind and date. **The small maps** become bounded Caffeine
caches (by date count with an idle expiry). **Tests:** after 60 dates opened in sequence with hourly versions, the counts of views, books
and mappings equal the open dates × currencies. **Expected gain:** no growth per date (soak H3, H9, H10, H11). **Measure:** the
`/proc/<pid>/maps` line count and `lsof -p <pid> | grep -c deleted` flat over the soak.

### 5.4 Compact object headers on 25 (D5)

**Today.** `-XX:+UseCompactObjectHeaders` is added by the Docker images only, and only when the JVM is 25 or later
(`deploy/docker/server.Dockerfile`, its `ENTRYPOINT`; `deploy/docker/ecs/server-entrypoint.sh` (lines 67-69)). The stack runs Java 21
(`drishti-stack/stack.yaml:22`) and adds no such check. Measured: about 10% less heap on 25 (PERFORMANCE.md, *Java 21 against Java 25*).
**Proposal.** The same version check in `drishti-stack/stack.py` (`SERVER_MEMORY_FLAGS`), `tools/javacmd.py`,
`tools/windows/start-server.ps1` and the IntelliJ run configurations; OPERATIONS.md's systemd example shows the conditional. On 25 it is a
product flag (JEP 519); on 24 it was experimental, and 24 is not supported (21 and 25 only). **Test:** `tools/test_launcher_memory.py`
asserts the flag on 25 and its absence on 21. **Measure:** heap after GC on 25 with and without, at 200K: 10 to 20% less expected (less
after D1, which leaves fewer, larger objects).

### 5.5 The garbage collector per role

**Today.** The Docker and ECS images run `-XX:+UseZGC` (`deploy/docker/server.Dockerfile`, `JAVA_TOOL_OPTIONS`;
`deploy/docker/ecs/server-entrypoint.sh:77`) **without** `-XX:+ZGenerational`, so on Java 21, their default, they run the older
non-generational ZGC (generational became the default in 23 and the only mode in 24). PERFORMANCE.md's Docker paragraph says the image
runs `-XX:+ZGenerational`: it does not. The stack runs the default collector, G1, with `-XX:G1PeriodicGCInterval=60000` on one node
(`drishti-stack/stack.yaml:51`).

**Proposal.**

| Role | Heap | On Java 21 | On Java 25 | Why |
|---|---|---|---|---|
| VaR shard owner, coordinator, large single server | 16 to 36 GB | `-XX:+UseZGC -XX:+ZGenerational -XX:SoftMaxHeapSize=<0.8 × Xmx>` | `-XX:+UseZGC -XX:+UseCompactObjectHeaders` | pauses under a millisecond whatever the heap; generational keeps the CPU cost of a large live set (the skeleton) low |
| small server, stack node | up to 4 GB | G1 (the default) with `-XX:G1PeriodicGCInterval=60000` | the same | less overhead than ZGC on a small heap; the periodic collection returns memory |
| lake loader (`drishti-lakeload`), batch tools | any | G1, or `-XX:+UseParallelGC` for a one-shot load | the same | throughput, not pauses |
| benchmark | as the role | both collectors, recorded | both | the choice is confirmed by measurement (P5) |

ZGC collects concurrently and needs headroom: the capacity model's 1.5 × stays. `-XX:+AlwaysPreTouch` is not proposed (slow start, and it
commits the whole heap at once). **Test:** the launcher test asserts `-XX:+ZGenerational` exactly when the JVM is 21 and ZGC is chosen (on
25 the flag is obsolete and only warns). **Measure:** p99 of a node answer and of a 50,000-position basket under the concurrency test, and
the CPU time in GC (JFR `jdk.GarbageCollection`, `jdk.GCPhasePause`), for G1 and generational ZGC, on 21 and 25.

### 5.6 Direct memory: the 2.9.6 fix

**Today (built in 2.9.6).** `-XX:MaxDirectMemorySize=256m` on every launcher; lake reads, hot files, Arrow IPC, the content store and
the day index go through `ChannelIo` (`drishti-api`, line 270): 128 KB slices through a pool of at most 64 reusable direct buffers (8 MB);
`-Djdk.nio.maxCachedBufferSize=262144` on every launcher (`drishti-stack/stack.py:249`, the Docker images, `tools/javacmd.py`, the
loaders); Kafka fetches capped at 4 MB and 1 MB a partition
([OPERATIONS.md section 12.1a](../admin/OPERATIONS.md#121a-direct-memory-and-the-diagnostics-of-a-long-run)). This closed the soak
audit's H1, H2, H4, M1 and M2. Admin → Health shows direct memory against its limit (amber from 80%). Mapped files do not count against
the limit.
**What remains.** `RowSpill` (`drishti-compute`, line 62) allocates a 4 MB direct buffer per build and frees it only through GC.
**Proposal:** `RowSpill` takes its buffer from the `ChannelIo` pool. **Measure:** the `BufferPoolMXBean` "direct" count and bytes flat over
the soak.

---

## 6. CPU design

### 6.1 The console draws less: the client-side renderer (D6)

**Today.** The console (`drishti-console`, uvicorn, `server.workers` processes, default 4: `drishti-console/config/application.yaml:21`,
`run_drishti_web.py` (lines 59-67)) turns each ViewModel into HTML with Jinja (`render(...)` in `routes/*`); charts are already drawn in the
browser (vendored ECharts, `web/static/js/charts.js`, `var-charts.js`, `exposure-charts.js`). Python visuals return chart specifications,
not pictures (`core/pyrunner_worker.py` (lines 747-762)). Measured: about 40 pages a second per worker process (`run_drishti_web.py:63`); one
console of 4 workers about 69 to 70 pages a second at 50 users, against 140 to 150 requests a second for one server
([SCALE_BENCHMARK.md section 8](../admin/SCALE_BENCHMARK.md#8-sizing-for-millions-of-trades-a-day-measured-2026-10-18)). **The console
is the first limit** (the decks' concurrency slide: two consoles of 4 vCPU, 100 to 140 page loads a second, 300 to 500 active people).

**The console capacity model.** Per page: Jinja rendering of 10 to 20 panels, one to five API calls to the server (each a JSON parse and
re-serialisation), the HTML response. The renderer (2.9.7 item 2) moves panel drawing to the browser: the console serves a shell once
(cached), the browser fetches `/api/v1/views/...` ViewModels and their patch stream through the console's proxy (no parse: bytes passed
through) and paints them with the renderer library. What remains on the console per page: authentication, the proxy, the channel.

| | Today | With the renderer (estimate) |
|---|---|---|
| console CPU per page | Jinja render + JSON parse and dump per call | a proxied stream of bytes; the shell cached |
| pages a second per worker | about 40 (measured) | 80 to 120 (2 to 3 ×: the share of console CPU spent rendering, to be measured by `py-spy` in the benchmark) |
| two consoles of 4 workers | 100 to 140 page loads a second | 200 to 400 |
| active people (0.3 to 0.5 requests a second each) | 300 to 500 | 600 to 1,200; the servers (about 650 requests a second for 6 + 2) then bind first |

**Measure:** console CPU seconds per page (`py-spy record` on one worker under `tools/bench/users.py` at 50 users) before and after, and
pages a second at the same p95.

### 6.2 Fewer JSON passes, and Arrow where it pays (D7)

**Today (measured 2026-10-30, [DATA_FORMATS.md section 6](../guides/DATA_FORMATS.md#6-arrow-to-the-browser-where-it-pays)).** Arrow on
request (`Accept: application/vnd.apache.arrow.stream`) for a BI visual's answer and VaR contribution tables (`ArrowAnswers`; no Arrow
allocator: flatbuffers only, `server/bi/engine/ArrowIpcWriter.java`); the browser uses it for the contribution tables (692 KB of JSON to
243 KB, fetched in 59 to 61 ms against 81 to 91 ms). Stayed JSON, measured: the 509-point P&L path, a node's children, the BI grid
(36% smaller raw, 8 to 14% after gzip, slower to parse, painted no sooner), entity pages.
**Proposal.** After the renderer, the browser fetches ViewModels directly (one JSON parse, in the browser) instead of the console parsing
and re-serialising; large tables (more than 1,000 rows) by Arrow where the measurement above showed a gain; search results move from
server-drawn HTML (a 680 KB page for 1,000 rows) to a table the renderer draws (124 KB as JSON, 74 KB as Arrow). **Measure:** server and
console CPU per page (JFR `jdk.ExecutionSample` in Jackson; `py-spy` in `json`) and bytes per page.

### 6.3 Cold loads (D8)

**Today.** A VaR date's first load reads the lake's Parquet (decoded at roughly 0.5 to 1 GB/s a core, VAR_GUIDE 27.4), streams rows
through `VarIntake` to the spill, sorts, sums and writes the hot file: about 10 to 12 s a million trades after the read (VAR_GUIDE 27.3).
The lake loader parses about 30,000 rows a second a thread and writes 24,500 ([PARALLEL_LOADER.md section 11](PARALLEL_LOADER.md#11-measured-and-the-23m-estimate)).
**Proposal (after the profile).** Decode a large file's row groups in parallel (whether the engine's reads do so today is the first thing the profile answers), read
only the columns the engine uses (the vector and the 17 trade texts; never `doc`), and keep the sort's keys as int codes (D1's
dictionaries) instead of strings. **Measure:** the first open of a 200K date, wall and CPU, split into read, intake, sort, aggregate and
write by JFR; the target is the read at the disk's or S3's speed.

### 6.4 Logging (D9)

**Today.** No `logback.xml` or `logback-spring.xml` in main code: the server logs to standard output at the levels of
`application.yaml` (`logging.level`, lines 53-62, which already quiet Parquet and Delta Kernel). `drishti-stack/stack.py` (lines 302-304) appends
it to `run/logs/<node>.log` and `tools/windows/start-server.ps1:152` to `data\logs\server.log`, both **without rotation**; only the GC
logs rotate (5 × 20 MB). The console's log carries no timestamps (soak audit).
**Proposal.** A `logback-spring.xml` with a rolling file appender when `drishti.logging.file` is set (size and time based,
`drishti.logging.max-file-mb` 100, `drishti.logging.max-total-mb` 1024, compressed), standard output otherwise (containers); summaries
every N batches in the loaders and the derived pages (as the derived pages already do every 100,000 records); the console's format gains
`%(asctime)s`. **Measure:** log bytes an hour in the soak; CPU in logging (JFR) under the concurrency test.

### 6.5 Virtual threads and pinning on Java 21 (D10)

**Today.** Requests run on virtual threads (`spring.threads.virtual.enabled: true`, `application.yaml` (lines 20-22); the engine's
`drishtiVirtualExecutor`, `EngineConfiguration.java:71`). On 21, a virtual thread blocked inside `synchronized`, or inside a Caffeine
synchronous `get(key, loader)` (which runs the loader inside `ConcurrentHashMap.compute`), pins its carrier; 25 no longer pins in
`synchronized` (JEP 491). The rule and its guard test (`NoBlockingUnderSynchronizedTest`) are in PERFORMANCE.md (*Java 21 and
virtual-thread pinning*). Found on `develop` that the guard does not catch (it scans the synchronized body, not what it calls):

| Where | What blocks under the monitor | Hot? |
|---|---|---|
| `server/var/VarPvStream.java:115`, `apply` (`synchronized`), and `VarPvStreamPlugin.java:222`, `flushIfDue` | `svc.date(day)`, which can load a whole date from the lake | yes when the PV topic runs: every window |
| `server/exposure/ExposureService.java:291` | `paths.get(key, k -> loader.tradePaths(...))`: a lake read inside Caffeine's synchronous compute | yes: every contribution by trade and every partial scope |
| `drishti-iceberg/.../read/Deletes.java` (lines 118,126) | delete files read inside a synchronous Caffeine `get` | on every Iceberg read with deletes |
| `drishti-engine/.../ExplainService.java:124`; `ViewPipeline.java` (lines 333-451) | derivation and merge inside a synchronous `get` | moderate (CPU, short) |
| `DerivedSourcePlugin` `synchronized(cache)` (lines 246-467, marked `pinning-ok`); `DaySegment` block cache (`DaySegment.java` (lines 357-371)) | short sections, reviewed | low |

**Proposal.** The first three to `SingleFlight` (`drishti-api`) or async caches loading on virtual threads (as the Delta plugin does,
`DeltaSourcePlugin.java` (lines 105-108)); `VarPvStream.apply` takes a `ReentrantLock` and loads the date before taking it. The guard test
extends to the bodies of Caffeine loaders (a loader lambda that calls a known blocking method fails). Also: the VaR and exposure date
loads run on the **common pool** (`CompletableFuture.supplyAsync` with no executor: `VarService.java` (lines 244,277,294),
`VarShardBooks.java:89`, `ExposureService.java` (lines 153,186)), so a 10-second load occupies one of `cores − 1` common-pool threads that parallel
streams and other async code share; they move to a bounded `drishti-engine-load` pool (`drishti.var.load-threads`, proposed 2).
**Measure:** `tools/bench/pinning.sh` on 21 with the PV topic and exposure contributions in the mix: no pinned events; requests a second
on 21 within 10% of 25.

**A correctness finding beside it.** The exposure trade-paths cache key is `date|version|hashCode(sorted ids)|size`
(`ExposureService.java:290`): two different sets of trades with the same size and the same 32-bit hash share an answer. The key must be
the ids themselves or a cryptographic digest of them (SHA-256, as the hot files use). Proposed as a fix with the D10 change.

### 6.6 Thread pools and read slots (today)

| Pool or limit | Size and setting | Where |
|---|---|---|
| requests | virtual, one per request | `application.yaml` (lines 20-22) |
| fetches | virtual, one per fetch, with a deadline (`drishti.sources.fetch-timeout`, 5 s) | `EngineConfiguration.java:71` |
| binding (CPU) | `ForkJoinPool`, `drishti.engine.bind-parallelism` (0: one per core) | `EngineConfiguration.java:77` |
| live frames | 2 platform threads (`drishti-frame-*`) | `EngineConfiguration.java:169` |
| plugins' scheduler | 16 virtual workers | `SourceRegistry.java:180` |
| VaR sums | fixed, `drishti.var.threads` (0: every core), split above `drishti.var.parallel-rows` (4,096) | `ParallelSums.java:55` |
| VaR, exposure single-thread jobs | currencies built ahead, tier close, official captures | `VarService.java:84`, `VarTier.java:68`, `VarOfficial.java:55`, `ExposureTier.java:63` |
| VaR, exposure date loads | the common pool (see 6.5) | `VarService.java:294` |
| Delta reads | `ReadSlots`: `max-concurrent-reads` 16, `max-queued-reads` 4,096 | `DeltaSourcePlugin.java` (lines 317-318) |
| JDBC catalogue, DuckDB | `ReadSlots` 2 and 256 | `TableCatalog.java:84`, DuckDB line 75 |
| BI queries | `drishti.bi.query.max-concurrent` 8, `max-concurrent-per-user` 3, admission 2 s; fragments `drishti.bi.exec.max-fragments` 8 | `application.yaml:763-765, 802` |
| BI's DuckDB | `drishti.bi.query.threads` 2, `memory-limit` 768 MB | `application.yaml` (lines 770-771) |
| BI runs | `workers` 2, engine 2 GB and 2 threads, spill 20 GB | `config/runs/runs.yaml` (lines 34-40) |
| history | `drishti.history.max-concurrent` 16, `parallel-days` 8 | `application.yaml` (lines 214-215) |
| gossip | fixed 8 | `NetService.java:127` |
| database pool | `drishti.db.pool.size` 20 | `application.yaml:921` |
| console | 4 processes, an asyncio loop each, a 64-connection pool to the servers (`backend.pool_size`) | `drishti-console/config/application.yaml` (lines 21,28) |

**Proposal.** One table of pools in Admin → Health (active, queued, rejected per pool), so that a pool at its limit is visible; the sizes
stay where they are. The VaR sum pool and the BI DuckDB threads compete for the same cores on a server that runs both: on a shard owner
`drishti.var.threads` is every core and BI is off; on a coordinator that serves BI, `drishti.var.threads` is half the cores (a
deployment rule in OPERATIONS.md, not code).

### 6.7 Where SIMD would and would not help

**Today.** The VaR sum adds rows into a float64 accumulator in a plain loop over the 509 (or 1,518) scenarios, which the JIT
auto-vectorises (VAR_GUIDE 25); the whole bank from float32 rows runs at about 60 to 70 GB/s on 24 threads, the workstation's memory
bandwidth. No Vector API is used (it is an incubator module in both 21 and 25: excluded by P6).

| Where | Would SIMD help? | Why |
|---|---|---|
| summing float32 rows into float64 (VaR, exposure) | **no**, beyond what the JIT already does | memory-bound: at bandwidth already; widening float32 to float64 is vectorised by C2 on AVX2 (to be checked in the JFR and with `-XX:+PrintAssembly` on the benchmark) |
| scattered baskets | **no** | latency-bound (random rows); sorting the ordinals and prefetching rows help more |
| quantiles, expected shortfall (509 or 1,518 values a scope) | little | a selection over a few thousand values: microseconds |
| exposure quantiles per date (P = 5,000 paths) | some | a partial sort per date over 5,000 floats × 50 dates; a vectorised compare-and-count can halve it; measure first |
| JSON, Parquet decode | indirectly | through libraries (Jackson's fast double parser, Snappy and ZSTD in native code) |

**Bit equality.** Adding row vectors element by element (`acc[j] += row[j]`) keeps each scenario's additions in row order whether the
loop runs one lane or eight, so **vectorising across scenarios never changes a bit**. What changes bits is the order of rows: today
`ParallelSums.sum` (`drishti-compute/.../vector/ParallelSums.java` (lines 76-95)) cuts an ad-hoc set into `min(2 × threads, size ÷ parallel-rows)`
slices and adds the slices' partials in order, so the same set gives the same bits for the same `drishti.var.threads`, but **two servers
with different core counts can differ in the last bits** of a scope that is not a node (the class's comment says the split is by chunk,
which it is not). Nodes are unaffected (their aggregates). **Proposal (with D-items, decision PO-7):** cut into fixed blocks of
`parallel-rows` ordinals aligned to the absolute ordinal (block *b* = ordinals `[b × 4,096, (b + 1) × 4,096)`), each block summed
in row order, the block partials added in block order: the bits then depend only on the set, never on the threads. The cost is a few more
partials (a 2.3M-row scope: 562 partials of 509 doubles, 2.3 MB, added in about a millisecond).

---

## 7. Disk design

### 7.1 What is on local disk, and at bank scale

| Folder (under the data folder) | Today's bound | At 2.3M positions (estimate, footnote 3) |
|---|---|---|
| `var/hot` (VaR hot files, currencies, shard books, spills) | ⚠ 60 files, 5 currency dates; no bytes | 1-day: 60 × 5.47 + 5 × 23 = 443 GB; 1,518 values: about 1,183 GB (the model's NVMe need) on one server, 394 GB a shard owner |
| `ccr/hot` (exposure hot files) | ⚠ 60 files, overridden by the retention (about 334) | 60 to 80 GB a date at P = 2,000, G = 50 and 100,000 sets: tens of TB if the retention keeps a year |
| `cache/<connector>/idmaps` | ⚠ none | about 15 bytes a trade a date (SCALE_BENCHMARK section 8): 35 MB a kind a date; 61 GB a kind if every date of seven years is opened |
| `cache/<connector>/docs` (RocksDB) | 1 GB a connector, 12 h, daily clear | 1 GB a connector |
| `index` (type-ahead) | `index.max-disk` 2 GB a connector | 2 GB a connector |
| `derived/<connector>` (grouped pages) | 16 dates | about 0.55 KB a trade a date: 1.3 GB a date, 20 GB for 16 |
| `bi-store` (layers) | ⚠ none | grows with datasets × dates read |
| `bi-runs` | 20 GB, 2 GB a user | 20 GB |
| `tmp/server`, `tmp/console` | per use; ⚠ crash leftovers | a load's spill: 9.4 GB (1-day) to 27.9 GB (1,518) while a date builds |
| logs, dumps | ⚠ none (GC logs 100 MB) | a heap dump of an owner up to 36 GB |

**Footnote 3.** From footnote 1's constants: `HOT` 5.47 GB, `HOTA` 16.3 GB, `CCY4` 23 GB; NVMe need `HOTA × 60 + CCY4 × 5 + 4 × CCYX +
LOADA` = 1,183 GB; a shard owner holds replicas ÷ owners = 1/3 of it.

### 7.2 Proposal: hot files bounded by size, and the free-space guard (O5)

**Design.** One `DiskBudget` service: each folder that Drishti fills registers its name, path, limit in bytes, and how to free space (a
callback that deletes its least recently used, unopened items). Sizes are kept incrementally by the owners (a hot file's size is known
when it is written) and checked by a walk every `drishti.disk.check-interval` (60 s).

| Setting (proposed) | Default | Meaning |
|---|---|---|
| `drishti.var.tier.max-disk-gb` | 0: 50% of the disk's size | VaR hot files, currencies and shard books together; least recently used beyond, never an open one; `disk-dates` stays as a second bound |
| `drishti.ccr.exposure.max-disk-gb` | 0: 25% of the disk's size | exposure hot files; **the cap wins over the retention** (decision PO-3): a retained date beyond it is deleted from local disk and read from the lake when asked |
| `id-map-disk.max-gb` (Delta, Iceberg connector setting) | 5 | id maps, least recently used dates first |
| `drishti.bi.query.store-max-gb` | 50 | BI layers, least recently read dates first (the newest date of each table kept) |
| `drishti.disk.min-free-percent`, `drishti.disk.min-free-gb` | 10, 20 | the free-space guard, both must hold |

**The free-space guard.** Before anything large is written (a hot file, its spill, a shard book, an id map, a BI layer, a run's result),
the writer asks `DiskBudget.reserve(folder, bytes)`. If the folder is over its cap, its least recently used items are deleted first. If
the disk's free space after the write would fall below the guard, the budget asks every folder to free space down to 90% of its cap and
tries again; if it still would not fit, **the write is refused**: a new VaR date answers `503` with a new code (proposed `DRS-5218`: "not
enough free disk to build 2026-10-30: 12 GB free, 21 GB needed, guard 20 GB"); a date already open keeps answering; the lake is never
touched; Health turns red; the answer says what to free. A write already under way that finds the disk full (another process filled it)
fails, deletes its `.part` file and says so (today it fails with an `IOException`).

### 7.3 Old id-map versions (O6)

**Today** ([3.5](#35-lake-readers-and-connectors)): when a table version's id map is written, every **other** version's folder of the
kind is deleted (`pruneOtherVersions`, `DeltaSourcePlugin.java:450`), including folders still mapped; on Linux the disk space stays held
by the deleted files until they are unmapped, which they never are (H3); on Windows the delete fails and the folder stays until a later
load. So the plan's O6 is about mappings, not files: **Proposal:** keep the current and the previous version of each date (a request
started on the previous version finishes), unmap a version's maps when its keys are evicted (D4) and only then delete the files; cap the
folder (`id-map-disk.max-gb`). **Test:** 50 versions of one date in a row: at most two folders, no `(deleted)` mappings. **Measure:**
`du` of `cache/<connector>/idmaps` and the deleted-file count over the soak.

### 7.4 Temporary files, logs and dumps

**Proposal.** At start, `TempFiles` removes the folders of processes that are no longer running (today it lists them); the console does
the same for `tmp/console`. Logs rotate (D9, [6.4](#64-logging-d9)). Heap dumps: `drishti.disk.max-dumps` (2), the oldest deleted at
start; the dump folder counts in the budget. The audit log and deploy history roll monthly and are kept by `drishti.access-log.keep-days`'s
rule.

### 7.5 "Never fill the disk", case by case

| Case | Today | With O5 to O7 |
|---|---|---|
| many history dates opened | files up to `disk-dates` 60, whatever their size | least recently used beyond the cap; never past the guard |
| exposure with a year's retention | ⚠ every retained date kept (the retention overrides `disk-dates`) | the cap wins; retained dates read from the lake |
| a restatement storm (many versions of one date) | each version a new hot file; the old one deleted when the new opens | the same, and the reservation counts the new file before it is written |
| intraday versions every few minutes | Delta id maps per version, old ones deleted while mapped (held space) | two versions kept, older unmapped then deleted |
| a large BI run | 20 GB store cap | the same, inside the guard |
| a crash during a build | `.part` and `.f64` deleted at the next start | the same |
| repeated OOMs | a heap dump each time | at most `max-dumps` |
| another process fills the disk | writes fail with I/O errors | refused before writing, with a reason; Health red |

### 7.6 The lake's own retention

The lake is not Drishti's to trim, except what Drishti writes: the official results keep their log and removed files `retention-years`
(7: `drishti.var.official.retention-years`, `drishti.ccr.exposure.official.retention-years`); exposure's retention rules remove its own
official records and contributions beyond their windows ([EXPOSURE_GUIDE.md section 27.3](../guides/EXPOSURE_GUIDE.md#273-seven-year-retention-cost-and-the-decision-open-question-2-decided-2026-10-26)).
VaR's input vectors are kept 7 years by the bank's loaders (about 12.4 TB of Parquet at 2.3M, 1-day; 37 TB at 1,518 values, by the
model). Vacuum and compaction run from Admin → Lakes ([SCALE_STRATEGY.md section 6](SCALE_STRATEGY.md#6-storage-and-layout)).

---

## 8. Observability

### 8.1 Admin → Caches, as designed (O1)

**Today.** A table of name, kind (`Layouts and shape fingerprints`, `Connector`, `Shared by the Delta connectors`) and raw statistics,
with Purge buttons (`drishti-console/web/templates/admin/caches.html`); the server's list is the engine, history, the Delta budget and the
connectors (`CacheController.list`, line 68).

**Design (Proposal).** Every cache of [section 3](#3-inventory-of-every-cache-and-memory-mapped-file-today) registers with a
`CacheRegistry` (in `drishti-api`; the server's engines and every plugin) and reports one `CacheReport`. The page shows two totals at the
top (memory: the budget's used and limit and the heap; disk: every folder's used and limit and the free space) and one row per cache:

| Column | Example |
|---|---|
| Name, owner | `var.hot-files`, VaR engine |
| Where | heap · off-heap mapped · native · disk (`<data>/var/hot`) |
| Size now / limit | 11.2 GB / 40 GB (28%) · 412 entries / 512 |
| Age | TTL 15 m · oldest entry 6 m |
| Hit rate (last hour) | 94% (12,431 hits, 802 misses) |
| Dates held | 2026-10-30 (open), 2026-10-29 (open), 31 on disk |
| Freshness | keyed by data version · TTL only · on signal |
| Last purge | 2026-10-30 09:12, by `ops-admin`, date 2026-10-29 |
| Actions | Purge · Purge a date · (hot files) Reload a date |

A row with dates opens a drill-down: per date, open or on disk, its size, its inputs' data versions and digest, when built, last used.

**API shapes.**

```
GET  /api/v1/admin/caches
 -> { "memory": {"budgetBytes": 18253611008, "usedBytes": 6012345678, "heapMaxBytes": 38654705664, "heapUsedBytes": 21474836480},
      "disk":   {"freeBytes": 412316860416, "guardBytes": 21474836480, "folders": [{"name": "var/hot", "usedBytes": 12025908428, "limitBytes": 42949672960}]},
      "caches": [ { "name": "var.hot-files", "owner": "var", "where": "disk", "path": "data/var/hot",
                    "sizeBytes": 12025908428, "limitBytes": 42949672960, "entries": 33, "limitEntries": 60,
                    "ttlSeconds": null, "oldestAgeSeconds": 86400, "hits": 12431, "misses": 802,
                    "freshness": "data-version", "dates": true,
                    "lastPurge": {"at": "2026-10-30T09:12:00Z", "by": "ops-admin", "date": "2026-10-29"} } ] }
GET  /api/v1/admin/caches/{name}/dates
 -> [ {"date": "2026-10-30", "state": "open", "sizeBytes": 5470000000, "versions": {"pnl-vector": "…", "risk-hierarchy": "…"},
       "digest": "438361ce8f808055", "builtAt": "…", "lastUsedAt": "…"} ]
POST /api/v1/admin/caches/{name}/purge            (today's, unchanged)
POST /api/v1/admin/caches/{name}/purge?date=D     (O2)
```

CLI: `drishti.py server caches` (today) gains `--dates NAME` and `purge NAME --date D`. Every purge is audited (today's
`cache-purged`, with the date). The console's own caches get a per-worker report in the same page (a section "Console", purge clears the
worker's caches through a signed internal call).

### 8.2 Admin → Health → Disk (O7)

Every folder under the data folder (the table of [7.1](#71-what-is-on-local-disk-and-at-bank-scale)) with its size, its limit, its
oldest item, and the disk's free space against the guard; amber at 80% of a cap or when free space is under twice the guard, red at the
cap or under the guard; an alert (the existing alert channel, to the `admin` role) on each change to red. `GET /api/v1/admin/health`
gains a `disk` section with the same numbers.

### 8.3 Metrics to export

Prometheus at `/actuator/prometheus` (as today; PERFORMANCE.md, *Server metrics*). Proposed names (Micrometer gauges and counters,
bounded tags):

| Metric | Tags | Meaning |
|---|---|---|
| `drishti_cache_bytes`, `drishti_cache_limit_bytes` | `cache`, `where` | size and limit of each cache |
| `drishti_cache_entries` | `cache` | entries |
| `drishti_cache_hits_total`, `drishti_cache_misses_total`, `drishti_cache_evictions_total` | `cache` | from Caffeine's `recordStats` |
| `drishti_memory_budget_bytes`, `drishti_memory_budget_used_bytes` | `pool` | D3 |
| `drishti_var_skeleton_bytes` | `where` (`heap`, `mapped`) | D1 |
| `drishti_hot_files`, `drishti_hot_file_bytes` | `engine`, `state` (`open`, `disk`) | O5 |
| `drishti_disk_folder_bytes`, `drishti_disk_folder_limit_bytes` | `folder` | O7 |
| `drishti_disk_free_bytes`, `drishti_disk_guard_bytes` | | the guard |
| `drishti_mapped_files`, `drishti_mapped_deleted_files` | | `/proc/self/maps` lines, `(deleted)` ones (Linux) |
| `drishti_pool_active`, `drishti_pool_queued`, `drishti_pool_rejected_total` | `pool` | [6.6](#66-thread-pools-and-read-slots-today) |
| `jvm_buffer_memory_used_bytes{id="direct"}`, `{id="mapped"}` | | already exported by Micrometer's JVM metrics |

### 8.4 JFR profiles and heap histograms in the benchmark

On **Java 21 and 25** alike (the commands are the same):

```bash
# a recording for the whole run (profile settings: allocation samples with stacks, a few per cent overhead)
-XX:StartFlightRecording=name=bench,settings=profile,disk=true,maxage=2h,filename=run/dumps/bench-%p.jfr
# or on a running server
jcmd <pid> JFR.start name=bench settings=profile duration=10m filename=run/dumps/bench.jfr
jcmd <pid> GC.class_histogram > run/dumps/histo-$(date +%H%M).txt    # what the heap is made of (after a full GC)
jcmd <pid> VM.native_memory summary                                # native: threads, GC, internal, other
jfr summary run/dumps/bench.jfr; jfr print --events jdk.VirtualThreadPinned run/dumps/bench.jfr
```

What to look at: `jdk.ObjectAllocationSample` (who allocates: Jackson, `String`, `HashMap$Node`), `jdk.OldObjectSample` (what survives:
leak candidates), `jdk.GCHeapSummary` and `jdk.GarbageCollection` (heap after GC, pauses), `jdk.ExecutionSample` (CPU: the sum loop,
Parquet decode, JSON), `jdk.VirtualThreadPinned` and `jdk.JavaMonitorEnter` (D10, on 21), `jdk.FileRead` and `jdk.FileWrite` (cold
loads). The histogram's top classes for D1 and D2: `java.lang.String`, `byte[]`, `java.util.HashMap$Node`, `java.lang.Integer`. On 25
only, the extras: experimental CPU-time sampling (`jdk.CPUTimeSample`, Linux) and method timing; never needed for a decision that must
hold on 21.

### 8.5 Alerts

| Alert | When | To |
|---|---|---|
| cache near its limit for an hour | a cache at more than 95% with a hit rate under 50% (thrashing) | admin |
| memory budget squeezed | `squeeze()` ran more than 3 times an hour | admin |
| heap after GC high | over 85% of `-Xmx` for 10 minutes | admin |
| direct memory high | over 80% of its limit (exists) | admin |
| a folder near its cap, free disk near the guard | amber, red ([8.2](#82-admin--health--disk-o7)) | admin |
| deleted files still mapped | more than 100 | admin |
| a date refused for disk | `DRS-5218` (proposed) | admin, and the people asking |
| an engine date stale | the version watch found a change and the reload failed ([4.4](#44-proposal-a-version-watch-for-the-engines-o9-new)) | admin |

---

## 9. Scale-out

### 9.1 What shards and what is copied

| Data | In a sharded cluster | Size at 2.3M |
|---|---|---|
| VaR rows (`PMAT`, `TMAT`) | **sharded**: each owner its shards' rows, `replicas` 2 (`VarShardBooks`) | 5.47 GB a date ÷ owners × 2 |
| the skeleton (ids, columns, links, aggregates) | **copied** to every member (`ShardSkeleton`) | 1.84 GB a date, on the heap (D1: mapped) |
| hierarchies, scenario sets, portfolios, FX | copied (read whole by each member) | MB |
| intraday layer | held by every member | a few thousand rows |
| answers, views | each member's own | bounded caches |
| the lake | shared (S3 or a shared file system) | |

### 9.2 The mechanism check

A new measure takes the hot file and sharding only if it passes the four questions of
[ARCHITECTURE.md section 16.2](ARCHITECTURE.md#162-choosing-a-data-mechanism) (vectors per position; any scope on demand; partials
combine simply; too big for one server and needed in under a second), answered with sizes at bank scale. This document adds a fifth
line to the design document's answer: **the measure's skeleton per position and whether it is copied**, since that is what sharding does
not divide.

### 9.3 How exposure by netting set would plug in

Exposure's unit is the netting set (a quantile of the summed paths of a set), so the shard key keeps a set on one owner: an
`ExposureMeasure implements ShardedMeasure<double[][]>` with key order `[COUNTERPARTY, NETTING_SET]` (a counterparty's sets together, so a
counterparty's profile is one owner's), bytes per row = P × G_primary × 4, a partial = the summed E matrix of the scope's sets on that
owner (P × G_primary float64: 2 MB at P = 5,000, G = 50), combined by element-wise addition in shard order; the coordinator takes the
quantiles. The skeleton per set (id, counterparty, legal entity, CSA terms, offsets: about 200 B) is tiny next to VaR's per trade, so
copying it is fine. Its own store and assembly (`ExposureShardBooks`, `ExposureShardAssembly`) reuse `ShardMap`, `ShardOwners`,
`ShardHandover` and `ShardGather` (VAR_SHARDING section 13). Its hot files then fall under `drishti.ccr.exposure.max-disk-gb` per owner.

### 9.4 Console scale, and the sticky-session gap

**Today.** The console is stateless except for live channels: `CHANNELS` (`drishti-console/routes/api_routes.py:335`) holds each open
channel in the worker that opened it; `core/channel_relay.py` (lines 15-40) forwards a channel's calls to that worker **on the same host**
(signed with `DRISHTI_CONSOLE_RELAY_KEY`). Behind a load balancer across hosts, a channel's `POST /channel/{cid}` must reach the host that
holds it: sticky sessions (IMPLEMENTATION_PLAN's known gaps; [LIVE.md](LIVE.md)).
**Proposal, in two steps.** (1) The channel id carries the holding host's address as well as its port (today: the worker's loopback
port), and the relay forwards across hosts over the same signed call: no stickiness needed, one extra hop for a misrouted call. (2) With
the renderer, the browser's live stream goes through the console as a pass-through to the server's own SSE (`/views/{kind}/{id}/stream`),
so the console holds no channel state at all. **Test:** two console hosts behind a round-robin balancer, 100 tabs, every tick delivered.

### 9.5 Capacity tables

Per server, from the capacity model (footnote 1) with 1,518 values a position, two dates open, four listed currencies; **after D1** as
designed in [5.1](#51-the-heap-skeleton-d1), with the skeleton's residue estimated at 50 B a position and the aggregates on the heap
(the "aggregates mapped" column maps them too). Estimated, not measured; to be replaced by the 200K benchmark and the 2.3M run.

| | Heap need | `-Xmx` (÷ 0.61) | Page cache | A load | RAM need | Local NVMe need |
|---|---|---|---|---|---|---|
| **2.3M, one server, before D1** | 21.2 GB | 36 GB | 78.4 GB | 44.2 GB | 151 GB | 1,183 GB |
| 2.3M, one server, after D1 | 12.1 GB | 20 GB (16 GB aggregates mapped) | 78.4 GB | 44.2 GB | 142 GB | 1,183 GB |
| **2.3M, a shard owner of 6 (replicas 2), before** | 21.2 GB | 36 GB | 26.1 GB | 14.7 GB | 69 GB | 394 GB |
| 2.3M, a shard owner of 6, after D1 | 12.1 GB | 20 GB (16 GB) | 26.1 GB | 14.7 GB | 60 GB | 394 GB |
| **10M, one server, before** | 72.2 GB | 118 GB | 341 GB | 192 GB | 612 GB | 5,144 GB |
| 10M, one server, after D1 | 32.3 GB | 53 GB (36 GB) | 341 GB | 192 GB | 573 GB | 5,144 GB |
| **10M, a shard owner of 16 (replicas 2), before** | 72.2 GB | 118 GB | 42.6 GB | 24.0 GB | 146 GB | 643 GB |
| 10M, a shard owner of 16, after D1 | 32.3 GB | 53 GB (36 GB) | 42.6 GB | 24.0 GB | 106 GB | 643 GB |

**Footnote 4 (the derivation).** Before: as footnote 1, with K = positions ÷ 100,000 (23 or 100) for every per-position constant and the
load's sort (2 GB at 2.3M) scaled with positions (8.7 GB at 10M). After: an open USD date = 50 B × positions + the aggregates at 1,518
values (`AGG × 2.98`: 0.59 GB at 2.3M, 2.56 GB at 10M); a currency book = 50 B × positions + `AGG`; then `2 × USD + 2 × currency + sort + 4`
× 1.5. A shard owner holds replicas ÷ owners of the page cache, the load and the disk (1/3 for 6, 1/8 for 16); its heap is not divided.
The 10M aggregates are scaled with positions as the model does (conservative: they grow with nodes, not positions). Computed by a
script from the deck's constants; the arithmetic is reproducible from footnotes 1 and 4.

Reading it: at 2.3M sharding already makes the disk and page cache affordable, and **D1 is what makes the heap shrink**; at 10M without
D1 every owner would need a 118 GB heap whatever the cluster's size, with D1 about 36 to 53 GB, and the next lever is the skeleton's
sharding ((c) of [5.1](#51-the-heap-skeleton-d1)).

### 9.6 The data-load signal in a cluster

Today each server must be announced to ([4.1](#41-the-data-load-signal-today)). **Proposal (decision PO-5):** a `ready` announcement is
forwarded once to every member that serves the kind over Drishti\*net (a signed one-hop call, as shard calls are), idempotent by its key;
each member runs its own refresh. The loader's job keeps one call.

---

## 10. Benchmark, profiling and soak method

### 10.1 The 200K benchmark with the concurrency test

The plan's item 3 (IMPLEMENTATION_PLAN 2.9.7): 200,000 positions and 1,500 portfolios locally, single server against a three-node
sharded cluster on one machine, the Python against the Java loader (within the workstation's limits: one heavy job at a time). Built
from `VarTierBenchmark` (the store), `tools/bench/users.py` (users) and `tools/bench/pinload.py` (the mix). The concurrency test: 50, 100
and 200 users, a VaR mix (60% node questions, 20% bank minus a desk, 10% 50,000-position baskets, 10% contributions and P&L paths) beside
the trades mix of SCALE_STRATEGY section 4.4; requests a second, p50, p95, p99, errors.

### 10.2 JFR on 21 and 25

Every run twice, Java 21 and 25, with the flags of [8.4](#84-jfr-profiles-and-heap-histograms-in-the-benchmark), G1 and generational
ZGC; a heap histogram after a full GC at the start, after warm-up and at the end; the NMT summary; `py-spy` on one console worker. The
recordings are kept beside the results (`tools/bench/results/`), never committed when large.

### 10.3 Before and after, per item

| Item | Measured before and after | Acceptance |
|---|---|---|
| D1 | heap after GC, two dates open; skeleton bytes; basket p95 | heap per owner at 2.3M (extrapolated from 200K) ≤ 20 GB `-Xmx` need-based; basket p95 ≤ 150 ms warm |
| D2 | the histogram's `String`, `HashMap$Node`, `Integer` | 20 to 40% of the non-vector heap gone (if built at all after D1) |
| D3 | heap after GC with every cache full | total ≤ `cache-share` × `-Xmx`; no OOM in the fill test |
| D4 | views, books, mappings, deleted files after 60 dates × hourly versions | equal to the open dates × currencies; zero deleted-but-mapped |
| D5 | heap on 25 with and without compact headers | ≥ 10% less |
| D6 | console CPU per page; pages a second at p95 ≤ 300 ms | 2 × or better |
| D7 | bytes and CPU per page | no page larger or slower than before |
| D8 | first open of a 200K date, split by stage | the read at disk speed; total ≥ 30% faster |
| D9 | log bytes an hour; CPU in logging | ≤ 10 MB an hour at rest, under 1% CPU |
| D10 | pinned events on 21; requests a second 21 against 25 | no pinned event on a request path; 21 within 10% of 25 |
| GC | p99 node and basket under 200 users; GC CPU | the chosen collector's p99 ≤ the other's |

### 10.4 The flat-memory and flat-disk soak (O8)

A soak of 24 hours (8 on the workstation; the full one on a server) at the 200K size: every hour a new business date loaded and
announced, every 10 minutes an intraday version, a restatement of a past date every 3 hours, history dates opened at random, 50 users
of the mix. Sampled every minute: heap after GC, RSS, direct and mapped buffers, `/proc/<pid>/maps` lines, deleted-but-open files, every
cache's size, every folder's size, free disk, threads, pool queues. **Pass:** heap after GC and RSS slopes over the last 6 hours under
PERFORMANCE.md's gates (1% and 2% of the limit an hour); every folder under its cap at every sample and the disk never under the guard;
mapped and deleted-file counts flat; no `OutOfMemoryError`, no thread death (the supervised loops of the soak audit's H6). Disk over N
dates: with 60 dates of hot files at 200K (about 1 GB each at 1,518 values) and `max-disk-gb` set to 20, the `var/hot` folder never above
20 GB.

---

## 11. The work plan

In IMPLEMENTATION_PLAN's 2.9.7 order (renderer → benchmark with profiling → diet → benchmark → soak → fixes → release). Sizes: S up to two
days, M up to a week, L more.

| Step | Item | Depends on | Size | Docs it touches |
|---|---|---|---|---|
| 1 | D6 the renderer (2.9.7 item 2, both phases) | — | L | CLIENT_RENDERER (new), ARCHITECTURE 17, MOBILE_DISPLAY |
| 2 | the 200K benchmark with profiling, on 21 and 25, both collectors (10.1, 10.2) | 1 | M | SCALE_BENCHMARK, VAR_GUIDE 27, this document's figures |
| 3a | D10 pinning fixes, the exposure paths key, engine loads off the common pool | 2 | S | PERFORMANCE (pinning) |
| 3b | D4 releases with the date (with soak-proofing's views, books, id maps) | 2 | M | VAR_SHARDING 13, DELTA_CONNECTOR 6.2 and 9 |
| 3c | D3 one cache budget, the start-up check, the guard test | 3b | M | DELTA_CONNECTOR 9, SCALE_STRATEGY 5, CONFIGURATION |
| 3d | O1 Admin → Caches details (needs D3's registry) | 3c | M | ADMIN_GUIDE, OPERATIONS |
| 3e | O2 purge a date, O3 reload a date, O4 announce a load in the UI, O9 the version watch | 3d | M | DATA_LOADS, VAR_GUIDE 26, EXPOSURE_GUIDE 26, CLI_GUIDE |
| 3f | O5 disk budget and the free-space guard, O6 id-map versions, O7 Health → Disk, 7.4 temp files and dumps | 3b | M | OPERATIONS, TROUBLESHOOTING (`DRS-5218`), EXPOSURE_GUIDE 27.3 |
| 3g | D1 the mapped skeleton (after the profile confirms footnote 2) | 2 | L | VAR_GUIDE 25 to 27, VAR_SHARDING 5.1, the capacity slides |
| 3h | D2, only if the histogram after D1 still shows it | 3g | S | VAR_GUIDE 27.1 |
| 3i | D5 compact headers in every launcher, the GC per role | 2 | S | PERFORMANCE, OPERATIONS 7, the launcher test |
| 3j | D7 fewer JSON passes and Arrow for large tables (after 1) | 1 | M | DATA_FORMATS 6, ARCHITECTURE 16.1 |
| 3k | D8 cold loads | 2 | M | PARALLEL_LOADER, VAR_GUIDE 27 |
| 3l | D9 logging and rotation | — | S | OPERATIONS, the stack's README |
| 3m | the fixed reduction order (6.7), if decided | 2 | S | VAR_GUIDE 25, VAR_SHARDING 12 |
| 4 | the benchmark again: the "after" figures for both decks | 3 | M | SCALE_BENCHMARK, this document 9.5 |
| 5 | O8 the soak (and the soak audit's remaining H and M items) | 3, 4 | M | PERFORMANCE (soak), this document 10.4 |
| 6 | the small fixes | 5 | S | — |
| 7 | the release | 6 | S | CHANGELOG, RELEASE_NOTES |

---

## 12. Decisions for the product owner

| # | Decision | Recommendation |
|---|---|---|
| PO-1 | D1: memory-map the skeleton (a), encode it on the heap (b), or shard it (c)? | (a), with (b)'s layout on disk; (c) only if 10M on many owners still needs it after measurement |
| PO-2 | The cache budget's share of the heap and the pool weights (5.2) | 0.5 of `-Xmx`, the weights proposed; adjustable at run time |
| PO-3 | Exposure: does the disk cap win over the retention of hot files? | yes: retention is the lake's job; a retained date beyond the cap is read from the lake when asked |
| PO-4 | Default disk caps and the guard (7.2) | VaR 50% of the disk, exposure 25%, id maps 5 GB a connector, BI layers 50 GB; guard 10% and 20 GB |
| PO-5 | Forward the data-load signal to every member over Drishti\*net (9.6), or keep "announce on each"? | forward it |
| PO-6 | Admin → Caches purges: this server only, or the whole cluster (with a "whole cluster" switch)? | this server by default, a switch for the cluster, audited on each member |
| PO-7 | A fixed reduction order so that any scope's VaR is equal to the bit whatever the server's core count (6.7) | yes: it costs about a millisecond and closes the last difference between servers |
| PO-8 | The collector per role (5.5): generational ZGC on owners and coordinators, G1 elsewhere | yes, confirmed by the benchmark; fix the Docker image's missing `-XX:+ZGenerational` on 21 now |
| PO-9 | The version watch (4.4): bound an engine's staleness without a signal to 30 s | yes |
| PO-10 | `keep-on-shutdown` in production: keep hot files across restarts (fast restart) now that disk is bounded | yes once O5 is built; stays false by default for workstations |
| PO-11 | The console across hosts: the cross-host relay now, the stateless console with the renderer (9.4) | both, in that order |

---

## 13. Glossary

| Term | Plain words |
|---|---|
| **skeleton** | everything about a VaR date except its numbers: which positions and trades exist, their ids and attributes, which book and node each belongs to, and every node's total. Every server needs all of it to know what a question means |
| **hot file** | a file on the server's own disk holding one business date's VaR (or exposure) numbers in a layout made for fast sums; a copy of lake data, named after what it was built from |
| **page cache** | the part of the machine's free memory where the operating system keeps recently read file pages; reading a page from it is as fast as memory, and the system drops pages when memory is needed |
| **off-heap** | memory a Java program uses outside its own managed area (the heap), so the garbage collector never scans it |
| **memory-mapped** | a file the program reads as if it were memory: the operating system loads the pages that are touched, into the page cache |
| **TTL** | time to live: how long a cached entry may be used before it is thrown away |
| **data version** | a stamp that changes whenever the data changes (for Delta, from its log; for Iceberg, its snapshot); caches keyed by it can never serve old data as new |
| **digest** | a short fingerprint computed from a list of inputs; if any input changes, the fingerprint changes |
| **shard** | one slice of the positions (a range of legal entities and desks), held and summed by the servers that own it |
| **replica** | a second (or further) copy of a shard on another server, so a server can fail without losing answers |
| **coordinator** | the server that takes a question, asks each shard's owner for its part, adds the parts and computes the final figure |
| **JFR** | Java Flight Recorder: a recorder built into the Java runtime that samples where time and memory go, cheaply enough to leave on during a benchmark |

---

## 14. As built (2.9.7)

Step 3 of 2.9.7, built before the baseline benchmark (step 2) by the product owner's choice: every item below proves itself with its own
tests and micro-measurements; the benchmark then measures them together. One entry per item: what was done, its measurement, what
remains.

### 14.1 A1: the exposure trade-paths key

**Done.** `ExposureService.pathsKey`: the date, its data version, a SHA-256 digest of the sorted trade ids (each followed by a NUL, so
`A,BC` and `AB,C` differ) and their count; the paths are loaded through `SingleFlight` outside Caffeine's compute (no pinning on 21).
**Test.** `ExposurePathsKeyTest`: `["Aa","T-1"]` and `["BB","T-1"]` have the same `List.hashCode` and size, and different keys.
**Measure.** A digest of 50,000 ids is about 2 ms, against the lake read it saves (tens to hundreds of ms). **Remains:** nothing.

### 14.2 A2: the viewers' views

**Done.** `VarAccess.views` and `ExposureAccess.views` are Caffeine caches weighed in bytes (the bitsets: `drishti.var.view-cache-mb` 64,
`drishti.ccr.exposure.view-cache-mb` 16), idle for an hour at most, and released with their date (`forget(date)` from `evictOld`,
`reload`, `reload(date)` and a sharded date's swap). A user whose roles change gets a new key; the old one ages out.
**Test.** `VarAccessViewsTest`: 60 dates with eight versions each, two open: 16 views left (before: 480); 100 views of a million trades
under 1 MB stay under 1 MB. **Remains:** nothing.

### 14.3 A3: shard books released for age

**Done.** Each member announces its **open** dates beside the shards it holds (`ShardPeers.State.open`; a member older than 2.9.7 sends
none, and its held dates count as open). A date is in use while open on any member and for `drishti.var.sharding.release-after-seconds`
(900) after; only dates in use get newly owned shards built. Each round, a USD shard book (and its currencies) of a date not in use, not
among the lake's newest `hot-dates`, and not read here for `release-after-seconds`, is released: unmapped, its file kept for the disk
budget. `VarShardBooks.used`, written and never read before, is now that "read here" clock (`lastUsed`).
**Test.** `VarShardSingleNodeTest.aBookOfADateNobodyOpensIsReleasedForAge`: an open date and a newest date keep their books; a closed
one loses both, and opens again from the files. **Remains:** the long soak (O8) on a three-node cluster.

### 14.4 A4 and O6: id-map files released, then deleted

**Done.** The guarded, unmappable id-map file is one class in `drishti-api`, `MappedIdFile` (the Delta and Iceberg `MappedIdMap`s are
thin adapters); `FileMaps` (the shared-arena or cleaner unmapping of the VaR hot files) moved to `drishti-api` beside it. One mapping a
file; every read enters a guard; `close` unmaps once no reader is inside, a later read gets `MappedIdFile.Closed` ("ask again"), and a
cache that finds a closed map maps or reads it again. The id-map caches (the Delta shared and own caches, Iceberg's) unmap on removal.
Pruning: Delta keeps the current and the previous version folder of a kind (a request on the previous finishes) and releases the rest
(unmap, then delete); Iceberg releases a date's earlier maps. At start: Delta keeps the newest two version folders per kind, Iceberg the
newest map per date, half-written `.tmp` files go. Each folder is capped by `id-map-disk.max-gb` (5, a connector setting; PO-4), least
recently mapped first (each open touches the file's time), never the map just written.
**Tests.** `MappedIdFileTest` (shared mapping, release then delete, refused read after release, a replaced or damaged file, the trim
order, 50 versions with zero `(deleted)` lines in `/proc/self/maps`); `MappedIdMapTest.fiftyVersionsOfADateLeaveTwoFolders`;
`IcebergDiskCacheTest.anEarlierMapOfADateIsUnmappedThenDeleted`. The Delta and Iceberg suites: 129 and 192 tests green.
**Remains:** the mapped maps still weigh 256 bytes in the heap caches (their heap cost); their disk and address space are bounded by
the folder cap instead of a separate mapped budget.

### 14.5 A5: the BI layers' retention

**Done.** `Layers.trim(maxBytes, maxDates)`, run by `QueryService` at start and every ten minutes on one daemon thread: per table
(columns and rollups) at most `drishti.bi.query.store-max-dates` (260) dates, the oldest beyond removed; then, beyond
`drishti.bi.query.store-max-gb` (50, PO-4), the least recently built dates, never a table's newest. A partition goes under its build
lock, metadata first; its date's generation moves on (no cached answer reads it) and its `epochs` and lock entries go with it (A6).
**Test.** `LayersRetentionTest`: ten dates of two tables cut to six, then to the cap; a large old date that is its table's newest kept;
live snapshots untouched. **Remains:** the store does not yet reserve space before a build (the guard, O5, covers the VaR and exposure
writers).

### 14.6 A6: the small maps that only grew

**Done.** `VarService.aheadState` (and `aheadQueued`) cleared with their date (eviction, reload); `VarOfficialStore.indexes` a cache of
2,000 and an idle day; `Layers.dateGenerations` and `epochs` caches of 200,000 and an idle week, an entry made again starting at a
value never handed out before (a process-wide counter), so an answer cached under an old generation never matches again; `epochs` and
the partition lock also go when the retention removes a date; `DatasetService.parsed` 5,000 and an idle day; `EditRecords` overlay and
owned caches 100,000 entities each and an idle hour (soak H11; a miss reads the table); Kafka's and the message-state connectors'
`weights` lose an entity when the cache evicts it. Kept by design: the message-state `known` set (one id per live entity: the search
index of the state store, removed on a tombstone).
**Test.** `LayersRetentionTest.generationsAreBoundedAndNeverRepeat` (250,000 dates, at most 200,000 held); the BI, edit, official and
messaging suites green. **Remains:** nothing.

### 14.7 A7: the heap tier's position vectors

**Done.** `VarBook.positionVector` on the heap tier keeps a summed vector only while the date's kept vectors stay within
`VarBook.positionCacheBytes()`, set by the server to a quarter of `drishti.var.memory-budget-mb` (itself a quarter of the heap by
default); past it the vector is summed on each ask (the same bits: the same rows added in the same order). Before, every position ever
asked stayed (up to 9.4 GB a date at 2.3M). The mapped tier (the default) is unaffected: it reads the position's row.
**Test.** `VarBookPositionCacheTest`: with room for five, five are kept, and every vector equals the uncapped book's to the bit, twice.
**Remains:** nothing.

### 14.8 A8: logs, heap dumps and the console's temp leftovers

**Done.** Server log rotation through Spring Boot's own Logback rolling policy (`application.yaml`, `logging.file.name` from
`drishti.logging.file` or `DRISHTI_LOG_FILE`; `drishti.logging.max-file-mb` 100, `max-total-mb` 1024, `max-files` 30 days, compressed);
unset, standard output only (containers, as before). `drishti-stack` and `start-server.ps1 -Background` set it per node and send standard
output to `<node>.out`, with the console appender off (`logging.threshold.console=OFF`), so nothing is written twice. Heap dumps: at
start the oldest beyond `drishti.disk.max-dumps` (2) go from the `-XX:HeapDumpPath` folder (`Dumps`, `DiskConfiguration`). The console's
crash leftovers are removed at its start (`core/tmpdir.py`, `remove_stale`), safely by construction (see CONFIGURATION.md, Temporary
folders); the server's are still only listed (the 2026-10-17 decision stands for the server: not asked to change).
**Tests.** `DumpsTest`; `test_tmpdir.py` (an old folder of a dead process goes; a link inside is unlinked, its target kept; a young
folder, a live process's, one without the marker, a link named like ours stay). **Remains:** the audit log and deploy history roll
monthly (7.4) is not built.

### 14.9 A9 and D10: the pinning on Java 21 the guard missed

**Done.** `VarPvStream.apply` takes a `ReentrantLock` and loads the date before it; the PV stream's `flushIfDue` (which writes the
window's batch to the lake) a `ReentrantLock` too; the exposure trade paths (A1), Iceberg's delete files (`Deletes`) and, found by the
extended guard, the Redis connector's compression dictionaries load through `SingleFlight`, outside Caffeine's compute. The guard
(`NoBlockingUnderSynchronizedTest`) now also flags the calls known to read the lake inside a `synchronized` body, and any synchronous
Caffeine cache's loader lambda that blocks (`theScanRecognisesBlockingLoaders` proves the scan). The remaining `synchronized` blocks it
lists none of; the short CPU sections of 6.5's table (`ExplainService`, `ViewPipeline`, `DerivedSourcePlugin`, `DaySegment`) stay.
**Tests.** The guard (4 tests); `VarPvStreamTest` (6) and `VarIntradayApiTest` (7); the Iceberg and Redis suites. **Measure:**
`tools/bench/pinning.sh` with the PV topic in the mix belongs to the benchmark (step 2/4). **Remains:** that measurement.

### 14.10 A10: date loads off the common pool

**Done.** VaR's dates and on-first-ask currencies load on `drishti-var-load-*`, shard books on `drishti-var-shard-load-*` (both
`drishti.var.load-threads`, 2), exposure's dates on `drishti-exposure-load-*` (`drishti.ccr.exposure.load-threads`, 2): each a
`ForkJoinPool` (`LoadPools`), bounded, whose join of another load on the same pool is compensated (a currency's shard book waits for its
USD book) instead of deadlocking. The "open it in the background" of a trade page runs on a virtual thread, never on a load pool (it
waits for the load). **Test.** `LoadPoolsTest` (a nested wait on a pool of one completes; names; size). **Remains:** the pools' table in
Admin, Health (6.6) shows them through `LoadPools.describe` once O7 lists pools.

### 14.11 O5: hot files bounded in bytes, and the free-space guard

**Done.** `DiskBudget` (`server/disk`): folders register their name, path, cap, size and how to free their least recently used items;
`reserve(folder, bytes, what)` before a build frees the folder down under its cap, checks the disk's free space against the guard
(`drishti.disk.min-free-percent` 10 and `min-free-gb` 20, PO-4), asks every folder to free down to 90% of its cap, and refuses with
`503 DRS-5218` ("not enough free disk to build the VaR hot file of D: X free, Y needed, guard Z ...") before anything is written. The
VaR tier (`var/hot`, `drishti.var.tier.max-disk-gb`, 0: half the disk) reserves in every `target(...)` (dates, currencies, shard books)
three times the largest file on disk (the spill is twice the file); exposure (`ccr/hot`, `drishti.ccr.exposure.max-disk-gb`, 0: a
quarter of the disk) twice; both trim in bytes after each open (`disk-dates` stays a second bound), never an open file; exposure's cap
**wins over the retention** (PO-3). The BI store (`bi-store`, freed by its retention, A5), the dumps, the server's temp folder and the
connectors' `cache` folder are registered too; every folder is checked each `drishti.disk.check-interval` (60 s).
**Tests.** `VarTierDiskCapTest` (four dates over a 250 KB cap: the two least recently used go; the per-date drill-down; purge a date;
a build refused with `DRS-5218` and nothing written), `ExposureTierDiskCapTest` (retained month-ends trimmed by the cap).
**Remains:** the estimate before a load is the largest file on disk, not the date's own size (unknown until its rows are read); a
write that finds the disk full from another process still fails with an I/O error (its `.part` deleted at the next start).

### 14.12 O1, O2 and PO-6: Admin → Caches as designed, purge a date, this server or the cluster

**Done.** `CacheBoard` (`server/caches`) gathers the engine's, history's and each connector's caches (as they report themselves) and
every `CacheReport.Owner`: `VarCaches` (`var.answers`, `var.dates`, `var.hot-files`, `var.views`, `var.shard-books`),
`ExposureCaches` (`exposure.answers`, `.trade-paths`, `.dates`, `.hot-files`, `.views`), `ServerCaches` (`bi.results`, `bi.layers`,
`security.pack-access`, `connectors.id-maps`). `GET /api/v1/admin/caches` answers section 8.1's shape (`memory`, `disk`, `caches` with
`where`, size and limit, entries, TTL and oldest entry, hits and misses where the cache records statistics, `freshness`, `dates`,
`lastPurge`, and the old `type` and `stats`); `GET /api/v1/admin/caches/{name}/dates` the drill-down; `POST .../purge?date=D` one date
(VaR and exposure: the date's answers, views, open date and hot files, deleted once unmapped; BI: the date's partitions of every
dataset; Delta and Iceberg: the date's day parts, id maps and column sets, memory and files, through the new
`SourcePlugin.purgeDate`, whose default purges everything and says so in `purgedWhole`); `scope=cluster` forwards the purge once to
every live member (`PeerCalls`: signed, on behalf of the caller, never forwarded again), the default is this server (PO-6). The console
page shows the totals, the rows, **Purge**, **Purge a date**, **Dates** (a server-drawn fragment) and the **whole cluster** switch; the
CLI `server caches [--dates NAME] [purge NAME [--date D] [--cluster]]`.
**Tests.** `CacheAdminTest` (every new row and the totals; purge a date of VaR and of a Delta connector; last purge by whom and which
date; the dates; a bad date; the cluster scope with no peers); `test_admin.py` (the page's totals, sizes, hit rate, last purge, purge a
date with the cluster scope, the drill-down). **Remains:** the console's own caches are not on the page (section 8.1's "Console"
section); Prometheus gauges per cache (8.3) are not exported.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
