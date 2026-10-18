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

# Scale strategy: millions of trades, fast

**Status:** proposal, 2026-10-18. Nothing here is built yet; it is the plan agreed in discussion. The numbers marked
*estimate* are to be replaced by the measurements of phase 0.

## 1. The goal

| | |
|---|---|
| Volume | **600,000 trades a day** or more, from ENDUR as a **full daily dump** (plus CALYPSO and MUREX) |
| History | **at least one year** (about 250 business days), more later |
| Truth | the Delta lake, always. Everything else is a cache that can be thrown away and rebuilt from it. |
| Feel | a trade opens, a form search answers and a counterparty page shows in under a second, on any date of the year |

Size of one year, kept as a full partition per day (*estimate*, 1–2 KB per trade compressed, more for large ENDUR
documents): 600K × 250 = **150 million rows**, about **150–300 GB** of Parquet. That is an ordinary size for Delta when
every read names its date, as every Drishti read does.

## 2. Ground rules

1. **Delta is the only truth.** No other store is a mirror of it. Anything kept beside it (RocksDB, memory-mapped
   files, memory) must be rebuildable from Delta at any moment, keyed by the Delta table version it came from, and
   capped in size.
2. **Name the date, read one day.** Every read carries a business date; it touches that day's partition only. A year
   of history costs disk, not speed.
3. **Read columns; open a document only for the one trade looked at.** Searches, filters, grouped pages and BI read
   promoted columns. The full JSON document is decoded for the trade on the screen, never in bulk at serving time.
4. **Compute at load time, not at view time.** Totals that pages show (counterparty, portfolio, legal entity, index)
   are written by the load, not rebuilt from documents while someone waits.
5. **Interactive work never waits behind background work.** Separate read slots and budgets.
6. **Freshness follows the table version, never a timer or a TTL.**

## 3. What happens today (2.3.0), and what it showed at 300K

A single trade read, with a business date and an id:

1. the date selects one partition folder (`business_date=…/PRODUCT_TYPE=…`); other days are not touched;
2. an in-memory **id map** of that table and date (every id → its file) points at one file;
3. that file is sorted by id with 1,000-row groups, so one row group is decoded and the trade picked from it.

Once the date's id map is in memory this takes milliseconds to tens of milliseconds. **Delta is not the bottleneck.**
What hurt at 300K ENDUR trades on the Windows demo machine (2026-10-18):

| Observation | Cause in the code |
|---|---|
| Thousands of `FilterCompat` log lines at start, slow start | the **grouped pages** (`trade-pages`: counterparty, portfolio, legal-entity, index trades) read up to `max-scan` 50,000 trades **one at a time** (`DerivedSourcePlugin.compute`), each a row-group decode; the log lines are now at WARN (since 2026-10-18) |
| Grouped pages incomplete above 50,000 trades | `max-scan` 50,000 (a WARN line says so since 2026-10-18) |
| Searches slow, "connector timed out" | those reads share **16 read slots** (`max-concurrent-reads`) with the pages people open; the grouped pages rebuild every **60 s** (`refresh`) while used, and once per date picked |
| Admin → Caches for `trade-endur`: `idMaps=1, columnSets=1, documents=21729` | counts are **entries**: one id map = one whole date (all its ids); 21,729 full documents cached. The 256 MB document cache full at that count means **about 12 KB per document** (*estimate*), so one single-trade read decodes about 12 MB (a 1,000-row group) |
| Memory pressure (*suspected*) | each Delta connector may cache up to ~2.8 GB (id maps 1 GB, columns 1 GB, loaded days 512 MB, documents 256 MB); several connectors on a 4 GB heap |

The indexes are complete: an id map holds every id of its date, the type-ahead every id of the newest date. Their
limits are memory caps, not id counts.

## 4. Storage: a full partition per day

The ENDUR dump is a full copy every day and many fields of a trade can change daily, outside our control. So:

- **Keep a full partition per business date** (`mode: snapshot`, as today). Considered and rejected: storing only the
  trades that changed (section 11).
- **Layout** (pack `layout`, written by `lake build`): partitions `business_date` + `PRODUCT_TYPE`; files of at most
  `file-rows` (250,000); sorted by id; **row groups sized to the documents**: 1,000 rows suits small documents, large
  ENDUR documents want **100–200** so a single read decodes a few MB at most (phase 0 decides).
- **Promoted columns cover every field a search, form, list, grouped page or BI table reads**, including values
  inside arrays flattened at load time (for example the first leg's notional as `NOTIONAL`), so none of them needs the
  document.
- **Parquet bloom filters on the id column**, so a single read can find its row group without the id map in memory
  (a cold date stays fast).
- **Maintenance**: Admin → Lakes schedules compaction, checkpoints and vacuum after every load (built in 2.3.0).
  **Retention**: how many days stay on fast disk and when older days move or go is a lake setting (decision 10.3).

## 5. Read paths

| What | How it reads | Target (p95) |
|---|---|---|
| Open one trade, any date | id map (or bloom filter) → one row group → one document; cached (section 6) | < 300 ms |
| Trading form search, one day | promoted columns of that day only (already so in 2.3.0) | < 1 s over 600K |
| Counterparty, portfolio, legal-entity, index pages | **tables written by the load** (section 7), read as columns | < 500 ms |
| Linked entities of a page | promoted link columns (already so) | < 300 ms |
| Anything across dates (a trade's history, time series) | **DuckDB over the Delta files** of the dates asked for; nothing held in memory | < 2 s for a year of one trade |
| BI | the BI store (DuckDB over Parquet columns, rollups) as today | < 3 s over a quarter |
| Load one day's 600K dump | `lake build` (Java writer), then the load signal | < 5 min |

## 6. Caches: three tiers, all disposable

| Tier | Holds | Where | Bounded by |
|---|---|---|---|
| **Hot** | id maps and columns of today and the dates being looked at; recent documents | heap | one memory budget for all connectors, set from the heap (not a fixed cap per connector) |
| **Warm** | id maps and columns of other dates | **memory-mapped sorted files**, written once per table version and date (the type-ahead index already works this way) | `max-disk` per connector |
| **Documents** | full trade documents already read | **RocksDB** (`drishti-diskcache`, already used by the Kafka connector), per connector, compressed | size cap, TTL, version purge, daily reset |

### 6.1 The RocksDB document cache

- **Key**: `connector | table version (commit time) | business date | trade id`. The value is the document as Delta
  holds it. This is the key the in-memory document cache already uses (`DeltaSourcePlugin.doc`).
- **Filled only by reads.** A document goes in when it is read, never by a bulk copy. It is not a mirror of Delta.
- **Four limits, all automatic:**

  | Limit | Removes | Setting (proposed) |
  |---|---|---|
  | Version in the key | documents of a superseded table version: never read again after a load, deleted by range at once | — |
  | **TTL** | anything written more than the TTL ago (RocksDB FIFO TTL: whole files, dropped by compaction; RocksDB schedules TTL compactions itself, plus a daily compaction as a safety net) | `disk-cache.ttl: 12h` |
  | **Size cap** | the oldest files once reached (FIFO compaction, as today) | `disk-cache.max-gb: 5` |
  | **Daily reset** | everything, at a set hour | `disk-cache.reset-at: 02:00` |

- **No write-ahead log**: after a crash, or after the folder is deleted, it refills from Delta.
- Admin → Caches shows its size, cap, TTL and hit rate, and can purge it.

### 6.2 Does a TTL let the screen fall behind the data? No.

The TTL limits **disk**, not **freshness**. The connector checks the Delta log every 10 seconds (`refresh-seconds`);
a load is a new commit, so a new table version, so new keys. A reader looks up the current version only and never
sees a document of an old version, however long that document's TTL still runs. So:

| | How far behind the data the screen can be |
|---|---|
| Single trades, searches, linked entities | about **10 seconds** after the load's commit, at most |
| With the load signal (Admin → Lakes, `--signal`, `lake demo`) | at once: the signal drops the caches |
| BI reports and boards | the next query after the commit: each stored partition records the data version it was read from and is rebuilt when Delta holds other data for that date (`Layers`, already so in 2.3.0) |
| The TTL (12 h) | **no effect on freshness**: an expired document is re-read from Delta, which costs one read |
| A page already open in the browser | what it showed when drawn, until reloaded (the page offers a refresh when a newer generation exists) |
| Grouped pages today (2.3.0) | up to `refresh` (60 s) without the load signal: they follow a timer, not the version. Phase 1 fixes this (section 9). |

## 7. Computed at load

`lake build` (or a step right after it, run by the load) writes, per business date:

- **grouped tables**: counterparty, portfolio, legal entity and index totals with their member lists, a few thousand
  rows a day, as their own Delta tables; the `derived` connector reads them as columns instead of reading trades;
- **flattened columns** of the trade table (section 4).

Until then (phase 1), the grouped pages are made cheaper in place: batch reads (one pass per file instead of one
decode per trade), a rebuild when the table version changes instead of on a timer, and their own low-priority read
slots.

## 8. Concurrency and memory

- **Read slots**: interactive reads (a page, a search) and background reads (warm-up, grouped pages, BI refresh)
  get separate slots; a page never queues behind background work.
- **One memory budget**: the heap is split between connectors' hot caches by one setting, sized from `-Xmx`, so the
  caps can never add up to more than the heap.
- **Heap**: 8–16 GB for a year-scale server (*estimate*, phase 0 measures).

## 9. Phases

| Phase | Contents | Why in this order |
|---|---|---|
| **0. Measure** | a scale benchmark (`drishti-benchmarks`): synthetic ENDUR-sized documents, 600K a day × 20, then × 60 days, on a server-class machine (the development laptop's limit is 10K trades); p50/p95 of every path in section 5; memory and disk | real numbers replace the estimates; a guard against regressions |
| **1. Quick fixes** | batch reads in the Delta connector; grouped pages rebuilt on a version change, not a timer; separate background read slots; row-group size for large documents; one memory budget | each is small, and together they remove what the 300K demo showed |
| **2. Storage and caches** | bloom filters on ids; memory-mapped warm tier for id maps and columns; RocksDB document cache with size cap, TTL, version purge and daily reset (section 6) | flat memory and fast restarts however many days are kept |
| **3. Computed at load** | grouped tables and flattened columns written by the load (section 7) | removes the heaviest serving-time work |
| **4. History** | DuckDB over Delta for anything across dates; BI monthly rollups | a year stays fast without memory growth |
| **5. Operations** | p95 per screen and per connector in Admin → Health; cache sizes and hit rates in Admin → Caches; a sizing guide; retention settings in Admin → Lakes | to see that it holds as the data grows |

Before any of these, settings only (no code) for a demo on a laptop: `-Xmx8g` or more; `refresh: 30m` for
`trade-pages`; Admin → Lakes → Maintain after loading; the shown dates opened once before the audience arrives; the
data folder excluded from antivirus real-time scanning.

## 10. Decisions to take

1. **Hardware** for the year-scale server: SSD, 16–32 GB RAM (*estimate*; phase 0 confirms).
2. **Where the phases go** in the delivery waves: phases 0–1 straight after the 2026-10-19 demo; phases 2–4 as a scale
   wave before or beside wave C.
3. **Retention**: how many days on fast disk, and what happens to older ones.
4. **TTL and caps**: defaults proposed in section 6.1 (12 h, 5 GB per trade connector).
5. **Row-group size** for ENDUR: from phase 0 on real document sizes.

## 11. Considered and rejected

| Idea | Why not |
|---|---|
| Store only the trades that changed (content hash against the previous day) | the dump changes too many fields every day, outside our control; and a full partition per day is fast enough when every read names its date |
| A RocksDB copy of the lake | two truths to keep in step; the cache must stay small and disposable (rule 1) |
| LMDB for the caches | fixed map size and no self-trimming (disk must be managed by hand), and a new native dependency; RocksDB is already shipped and trims itself |
| More RAM alone | memory grows with the days kept; it hides the one-at-a-time reads instead of removing them |
