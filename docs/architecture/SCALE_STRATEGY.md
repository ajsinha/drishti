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

# Scale strategy: millions of trades, fast, in a bounded heap

**Status:** agreed direction, 2026-10-18. Phase 1 quick fixes are built (section 12); the rest is planned. Numbers marked
*measured* come from runs on 2026-10-18 (section 4); numbers marked *estimate* are to be replaced by phase 0.

Contents: 1 Goal · 2 Ground rules · 3 How Drishti reads today · 4 Measurements · 5 Memory: what lives in the heap ·
6 Storage and layout · 7 Read paths · 8 Caches · 9 Freshness · 10 Grouped pages: ids only · 11 Concurrency and
time limits · 12 Phases and status · 13 Settings reference · 14 Operations · 15 Risks · 16 Decisions · 17 Rejected

## 1. Goal

| | |
|---|---|
| Volume | ENDUR, CALYPSO and MUREX, each **300,000–600,000 trades a day**, each a **full daily dump** (any field may change daily) |
| Reference data | 10,000 counterparties, 30,000 netting sets and the like, every day |
| History | **at least one year** (about 250 business days), more later |
| Heap | **at most 8 GB** for the server, whatever the volume |
| Truth | the Delta lake, always. Everything else is a cache that can be thrown away and rebuilt from it. |
| Feel | a trade opens, a form search answers and a counterparty or netting-set page shows in under a second, on any date of the year |

Size of one year, a full partition per day (*estimate*, 1–2 KB per trade compressed): 3 systems × 500K × 250 =
**375 million rows**, about 0.4–0.8 TB of Parquet. Ordinary for Delta when every read names its date.

## 2. Ground rules

1. **Delta is the only truth.** Nothing beside it is a mirror of it. Anything kept beside it (RocksDB, memory-mapped
   files, the heap) must be rebuildable from Delta at any moment, keyed by the Delta table version it came from, and
   capped in size.
2. **Name the date, read one day.** Every read carries a business date and touches that day's partition only. A year
   of history costs disk, not speed.
3. **The heap holds ids and numbers, never documents in bulk.** A full JSON document is decoded for the one entity on
   the screen, and dropped. Anything that grows with the number of trades must hold, per trade, an id and a few
   numbers at most, and must be capped (section 5).
4. **Read columns for everything that is not one entity's page.** Searches, filters, lists, totals and BI read promoted
   columns.
5. **Compute when loading or in one pass, never one entity at a time in bulk.**
6. **Interactive work never waits behind background work.**
7. **Freshness follows the table version**, never a timer or a TTL.
8. **Never the system temp folder.** Temporary files go under `<data dir>/tmp` (done, section 12); the system temp
   folder can be a small in-memory file system.
9. **Every limit is a setting, every limit that bites says so** in the log and in Admin → Health, and a build that
   fails (out of memory included) fails cleanly; it never leaves readers waiting.

## 3. How Drishti reads today (2.3.0 plus the phase 1 fixes)

**One trade, given a business date and an id** (the trade page):

1. the date selects one partition folder (`business_date=…/PRODUCT_TYPE=…`); other days are not touched;
2. the connector's **id map** for that table and date (every id → its file, held in memory, built on the first read of
   the date) points at one file;
3. that file is sorted by id; the Parquet reader skips to the pages that can hold the id and decodes them; the trade is
   picked out. *Measured*: p50 about 0.1 s, p95 about 0.35 s on 300K trades, through the console.

**A form search over one day** (Forms → Trading): the promoted columns of that day (the id map's sibling, also held in
memory once loaded) are filtered in memory; no document is read.

**Grouped pages** (counterparty, portfolio, legal-entity, index trades: the `derived` connector `trade-pages`): built
from the trades of the date in **one pass** over each source's files, kept in memory per date, rebuilt when the data's
version changes (section 10 explains why this must become ids only).

**BI**: the BI store (Parquet columns and rollups on disk under `<data dir>/bi-store`, queried with DuckDB), each
partition rebuilt when the data version it was read from changes.

## 4. Measurements (2026-10-18)

### 4.1 The Windows demo machine, 300K real ENDUR trades, before the fixes

| Seen | Cause |
|---|---|
| Thousands of `FilterCompat` log lines a second after start | the grouped pages read up to 50,000 trades **one at a time**, each a Parquet read; the library logged every one (now at WARN) |
| `DRS-1004 timed out reading trade/CLY-3000020` | that rebuild held the 16 read slots; a trade page waited past the 2 s limit |
| Grouped pages incomplete | `max-scan` 50,000 of 300K+ |

### 4.2 This machine, synthetic ENDUR-shaped trades (9.7 KB of JSON each)

| Test | Result |
|---|---|
| `lake build`, 303,780 trades, one day | **42 s**; files of 3K–60K trades by product type |
| Old writer layout | one Parquet row group per file (up to 60K trades); now 1 MB row groups |
| Single trade open, 300K, 4 GB heap (30 random ids, console) | p50 **0.09–0.14 s**, p95 **0.29–0.35 s**, 0 failures, also **during** a grouped-page rebuild |
| Trading form, 300K | 0.45 s |
| Grouped pages, 15K trades, one at a time (before) | **16.6 s** |
| Grouped pages, 15K trades, one pass (after) | **1.9 s**, the same pages |
| Grouped pages, 300K, full documents kept (before) | **out of memory** at 4 GB; readers then waited forever |
| Grouped pages, 300K, compact members, 8 GB | **out of memory** at about 250,000 trades |
| Grouped pages, 300K read, 100,000 kept (`max-stream`), 4 GB | **11.9 s**, heap **668 MB** after collection |
| A load (a restated date) while serving | grouped page updated within **about 20 s**, no restart, no signal |
| Trade documents: what makes them large | `LEGS` 7.7 KB (of which cash flows 2.6 KB), `EVENTS` 1.7 KB, everything else under 0.5 KB |

### 4.3 The extreme case (2026-10-18)

ENDUR, CALYPSO and MUREX, 7 business days (2026-10-07 … 16), 313K–492K trades a day each: **8.2 million trades**
(ENDUR 2.82M, CALYPSO 2.66M, MUREX 2.71M; 1.0–1.3 million a day across the three); 10,000 counterparties and 30,000
netting sets a day. Synthetic documents of the real shapes (ENDUR 9.7 KB, CALYPSO 6.3 KB, MUREX about 14 KB of JSON).
Lake: 12.5 GB of Parquet. Server `-Xmx8g`, grouped pages `store: disk`. Times through the console (page rendered).

| What | Result |
|---|---|
| Load one system's day (`lake build`) | ENDUR 47–69 s, CALYPSO 50–69 s, MUREX 90–161 s (300K–500K trades) |
| Server start | 9 s; id maps of the three newest dates loaded in the background (≈0.3–0.5 s per table and date) |
| Grouped pages, latest date (1,163,754 trades, three systems) | built in **45 s** in the background, **complete**: 10,000 counterparty pages, 30 portfolios, 3 legal entities, 57 indices; 631 MB on disk |
| Heap | **0.85 GB** after the build, **1.16 GB** after the probes (several dates' id maps and columns), of 8 GB; no out of memory, no `DRS-1004` |
| Open one trade, latest date (25 random ids per system) | p50 **15–26 ms**, p95 **19–46 ms**, 0 failures |
| Open one trade, a past date (2026-10-08) | first read of the date **0.37 s** (its id map); then p50 **19–31 ms**, p95 **21–41 ms** |
| Counterparty page (reference), netting-set page | **13 ms**, **71–91 ms** |
| Counterparty trades page (`CTRD`, ~116 trades) | **0.15 s** |
| Portfolio trades page (`PTRD`, 20,000 listed trades, a 7.4 MB page) | **3.2 s**: the page lists 20,000 rows; paging the list is the fix (section 10) |
| Form search, one counterparty over 1.16M trades | **2.1–2.3 s** (target 1 s: section 7) |
| Purge and rebuild (`drishti.py server caches purge trade-pages`) | new pages built in 46 s from the lake while the old ones served; the old folder deleted a minute later |

### 4.4 Concurrency on the extreme lake (2026-10-18)

Many users at once, straight against the server API (`-Xmx8g`, 24 cores): each user loops for 60 s over a mix of 80 %
single trades (random system, random date of the seven), 10 % counterparty-trades pages, 5 % netting sets, 5 % searches
(one counterparty's trades over 1.16M). Three rounds; the fixes between them came out of profiling the round before.

| Round | Users | Requests/s | Trade p50 / p95 | CTRD p50 / p95 | Search p50 | Failed |
|---|---|---|---|---|---|---|
| 1 (as built) | 10 / 50 / 100 | 76 / 110 / 83 | 0.07 / 0.21 · 0.29 / 0.55 · 0.76 / 1.28 s | 0.23 / 0.56 · 1.4 / 2.3 · 3.7 / 5.9 s | 0.43 · 0.40 · 0.46 s | 0 |
| 2 (+ pack access cached) | 10 / 50 / 100 | 65 / 66 / 66 | 0.06 / 0.54 · 0.07 / 3.0 · 0.16 / 5.0 s | 0.10 / 0.14 · 0.10 / 0.20 · 0.12 / 1.6 s | 0.59 · 0.65 · 0.69 s | 913 trades at 100 |
| 3 (+ Parquet footers cached) | 10 / 50 / 100 | **133 / 141 / 122** | **0.03 / 0.08** · 0.18 / 1.0 · 0.53 / 2.4 s | **0.10 / 0.16** · 0.26 / 1.0 · 0.52 / 2.3 s | 0.57 · 0.79 · 0.98 s | **0** |

What profiling found and what was fixed:

1. **Every entity of every page read the user and the user's pack preferences from the identity database** (SQLite,
   one access at a time) to decide whether the kind may be opened: the main bottleneck of round 1. Fixed: each user's
   effective packs cached for 5 s (`PackAccess`), cleared at once by the user's own choice, an administrator's change to
   the user, a pack loaded, unloaded, switched on or off, and the sample-pack setting.
2. **Every single read parsed its Parquet file's footer again**; with 1 MB row groups a file of 60,000 trades has about
   500 groups and a large footer: the bottleneck of round 2. Fixed: footers cached per immutable file (`ParquetBatches`,
   at most 50,000 row groups' worth; a purge of the connector clears them).
3. What remains is the data work itself: decompressing the pages that hold the trade (Snappy), then building the view.
   About 140 requests a second on this machine; latency grows with users beyond that (queueing, no failures). Next steps
   in section 12.

The same probes before the fixes of the day: grouped pages out of memory above about 250,000 trades at 8 GB, or partial
with a cap; with them, complete at 1.16 million a day in 1.2 GB of heap.

## 5. Memory: what lives in the heap

Each holder, what it grows with, and how it is bounded. The rule (section 2.3): per trade at most an id and a few
numbers, and a cap.

| Holder | Grows with | Per unit (*estimate*) | Bounded by | Status |
|---|---|---|---|---|
| Id map (table × date) | trades of a date | ~60 B per id | `id-map-mb` (1 GB per connector), least used dates dropped | fine; move cold dates to files (phase 2) |
| Columns of a date (promoted fields) | trades × columns | ~8–40 B per value | `columns-cache-mb` (1 GB per connector) | fine; cold dates to files (phase 2) |
| Recently read documents | documents read | ~10–50 KB each | `doc-cache-mb` (256 MB per connector) | fine; to RocksDB on disk (phase 2) |
| Type-ahead index | ids of the kept days | ~40 B per id | `index.max-memory` (600 MB), then files | fine |
| Loaded days for reverse links (`parts`) | trades of a day, **documents** | whole day | `cache-mb` 512 MB and `max-load-rows` 200,000 | used only for tables without promoted link columns; keep it so |
| **Grouped pages** (`trade-pages`) | with `store: disk`: **groups** (totals only); members and rows on disk | ~1 KB per group | the number of groups | **done** (section 10); `store: heap` keeps trades × row and is capped by `max-stream` |
| BI store reads | rows of a query | — | DuckDB, spilling to `<data dir>/tmp` | fine |

**The sum of the caps must fit the heap.** Today each Delta connector may cache up to about 2.8 GB, and there are a
dozen connectors: the caps add up to far more than any heap. They do not all fill at once, but nothing stops them.
Phase 1 gives the server **one memory budget** (a share of `-Xmx`) that every connector's caches draw from, so the
total cannot pass it. Until then: the defaults, `-Xmx8g`, and Admin → Caches to watch them.

**Out of memory, today:** with `store: disk` (the trading pack's default) the grouped pages no longer grow with trades.
With `store: heap` they do, capped by `max-stream`. A build that runs out now fails cleanly: it is logged
(`derived pages … ran out of memory: lower its max-stream setting or raise -Xmx`), Admin → Health shows the connector
DOWN with that reason, its pages answer "unavailable", and the rest of the server keeps working.

## 6. Storage and layout

- **A full partition per business date** (`mode: snapshot`). Storing only changed trades was considered and rejected:
  the dumps change too many fields every day (section 17).
- **Partitions**: `business_date` + `PRODUCT_TYPE` for trades; `business_date` for reference data.
- **Files**: sorted by id; at most `file-rows` (250,000) rows; Parquet **row groups of 1 MB** (done; was one group per
  file). A single read decodes about 1 MB. Takes effect for data loaded after the change; Admin → Lakes → Maintain
  (compaction) rewrites older files.
- **Promoted columns** cover every field a search, form, list, grouped page or BI table reads, including values
  inside arrays flattened at load time (for example the first leg's notional as `NOTIONAL`), so none of them needs a
  document.
- **Bloom filters on the id column** (phase 2): a cold date's single read finds its row group without an id map.
- **Maintenance**: Admin → Lakes schedules compaction, checkpoints and vacuum after loads (2.3.0).
- **Retention**: days kept on fast disk, and what happens to older ones, is a lake setting (decision 16.3).

## 7. Read paths and targets

| What | How | Target p95 | Today (*measured*) |
|---|---|---|---|
| Open one trade, any date | id map → one row group → one document | < 300 ms | 0.29–0.35 s at 300K |
| Form search, one day | promoted columns of that day | < 1 s over 1.5M | 0.45 s page at 300K |
| Counterparty, portfolio, legal-entity, index pages | ids and totals in memory; the trade list from columns (section 10) | < 500 ms | — |
| Netting set, counterparty (reference) | one document each | < 300 ms | — |
| Across dates (a trade's history, time series) | DuckDB over the Delta files of the dates asked for | < 2 s for a year of one trade | — |
| BI | BI store, rollups | < 3 s over a quarter | — |
| Load one day, one system (500K) | `lake build` | < 2 min | 42 s for 300K |
| First read of a date | id map and columns of that date | < 5 s for 500K | — |

## 8. Caches: three tiers, all disposable

| Tier | Holds | Where | Bounded by |
|---|---|---|---|
| **Hot** | id maps and columns of today and the dates being looked at; recent documents | heap | the one memory budget (section 5) |
| **Warm** | id maps and columns of other dates | **memory-mapped sorted files**, one per table version and date (as the type-ahead index already does) | `max-disk` per connector |
| **Documents** | full documents already read | **RocksDB** (`drishti-diskcache`, used by the Kafka connector), per connector, compressed | size cap, TTL, version purge, daily reset |

### 8.1 The RocksDB document cache

- **Key**: `connector | table version (commit time) | business date | id`; value: the document as Delta holds it. The
  in-memory document cache uses this key already (`DeltaSourcePlugin.doc`).
- **Filled only by reads.** Never a bulk copy; never a mirror of Delta.
- **Limits, all automatic:**

  | Limit | Removes | Setting (proposed) |
  |---|---|---|
  | Version in the key | documents of a superseded table version; deleted by range when the version changes | — |
  | **TTL** | anything written more than the TTL ago (RocksDB FIFO TTL: whole files, dropped by compaction) | `disk-cache.ttl: 12h` |
  | **Size cap** | the oldest files (FIFO compaction, as today) | `disk-cache.max-gb: 5` |
  | **Daily reset** | everything, at a set hour | `disk-cache.reset-at: 02:00` |

- **No write-ahead log**: after a crash, or when the folder is deleted, it refills from Delta.
- Admin → Caches shows its size, cap, TTL and hit rate, and can purge it.

## 9. Freshness

The TTL limits disk, never freshness. A load is a new Delta commit, so a new table version, so new keys; readers look
up the current version only.

| | How far behind the data the screen can be |
|---|---|
| Single entities, searches, linked entities | about **10 s** after the commit (`refresh-seconds`), at most |
| With the load signal (Admin → Lakes, `--signal`, `lake demo`) | at once: the signal drops the caches |
| Grouped pages | about **10–20 s** (done: they follow the data's version, asked every `version-check`, 10 s) |
| BI reports and boards | the next query: each stored partition records the data version it was built from |
| The RocksDB TTL (planned) | no effect on freshness |
| A page already open in the browser | until reloaded (the page offers a refresh when a newer generation exists) |

## 10. Grouped pages: on disk (built 2026-10-18)

**Why they are in the heap today.** The `derived` connector was built for books of a few thousand trades. It builds
each group's page in advance (the totals and a row per member trade, including the trade's `EVENTS` for the payments
ladder) and keeps every page in memory, once per grouped kind (counterparty, portfolio, legal entity, index: four
copies of every trade's row). Memory is trades × 4 × row: several GB at 300K, whatever the heap.

**Built (2026-10-18), `store: disk`, the trading pack's default:** one pass over each source's day; each member's key
`kind | group | trade id` and its row (once per set of identical columns, shared by the four kinds) go to a sorted
store on disk (`SortedStore`, RocksDB, `<data dir>/derived/<connector>/<build>`); the heap keeps each **group's totals**
(count, sums, extremes, distinct values, the first member by id), never the members. A page is assembled when asked for:
its totals, then its members' ids and rows read from the store in id order (up to `max-members`). One folder per
computation, deleted when a newer one replaces it; the folder is emptied at start. Measured in section 4.3: complete at
1.16 million trades a day, 45 s, 631 MB on disk.

Still to do: the payments ladder totals computed in the pass (today a page sums its rows' `EVENTS`, which works because
the rows are on disk); paging the row list (a 20,000-trade portfolio is a 7 MB page); the trade list could later come
from promoted columns instead of stored rows.

**The earlier plan, ids only (kept for reference):**

| Kept in memory, per group | Read when the page opens |
|---|---|
| the member trade **ids** (sorted, shared between the four kinds: one id list per trade, not four) | the trade list: the members' **promoted columns** for just those ids (the columns of the date are already in memory for searches) |
| the **totals**: count, notional, MTM, distinct currencies and product types, last maturity | — |
| the **payments ladder** sums (next 7, 30, 90 days) and their event counts, computed per trade in the one pass from `EVENTS`, then summed per group | the events of one trade, when it is opened |

Per trade: one id reference per kind and a few numbers. 1.5M trades a day: about 50–100 MB, not GB.

**What changes:** the `derived` connector (members kept as ids; totals and ladders computed in the pass; the row list
served from columns); the four grouped-page Sutras read `$.PAY_7D`-style totals instead of summing
`$.TRADES[*].EVENTS`; the promoted columns of the trade table include every column the row list shows. Paging the row
list (a counterparty with 20,000 trades shows them a page at a time) comes with it.

## 11. Concurrency and time limits

- **Read slots**: single-entity reads take one of `max-concurrent-reads` (16) per connector. Background work now reads
  in one pass on its own thread and takes none (done for grouped pages). Phase 1 gives the rest of the background work
  (warm-up, BI refresh) its own slots.
- **Single read time limit**: `drishti.sources.fetch-timeout` **5 s** (done; was 2 s): the first read of a date waits
  for that date's id map.
- **Search and columns budget**: 20 s (`drishti.search.budget`, `columns-budget`).

## 12. Phases and status

| Phase | Item | Status |
|---|---|---|
| **1. Quick fixes** | grouped pages read in one pass per source, not one trade at a time | **done** (2026-10-18) |
| | grouped pages keep computed values per trade, not documents | **done** |
| | grouped pages rebuild on a data-version change, not a timer | **done** |
| | an out-of-memory build fails cleanly (no reader waits forever) | **done** |
| | `max-stream` bounds the grouped pages (default 100,000) | **done** |
| | 1 MB Parquet row groups in the lake writer | **done** (new loads; compaction rewrites old files) |
| | single-read time limit 5 s | **done** |
| | quiet startup log (library INFO at WARN; one line per large table and date; progress every 100,000) | **done** |
| | temp files under `<data dir>/tmp` | **done** |
| | **grouped pages on disk** (section 10): complete at any number of trades, heap per group only | **done** |
| | Admin → Caches and `drishti.py server caches [purge NAME]`: grouped pages and caches dropped and rebuilt from the lake | **done** |
| | pack access cached per user (no identity database read per entity): 2–4× throughput under load | **done** |
| | Parquet footers cached per file (no footer parse per single read) | **done** |
| | grouped pages list 1,000 trades, the payments ladder over every member computed in the pass (`today()` set), 20,000-trade page 3.2 s/7.4 MB → 0.4 s/486 KB | **done** |
| | form pick lists cached per data version (server form 1.3 s → 0.2 s); long pick lists (over 300 values) found as you type instead of one checkbox each (form page 1.3 MB → 98 KB) | **done** |
| | one memory budget: Delta connectors without their own cache sizes share caches sized from the heap (day loads 5 %, id maps 20 %, columns 20 %, documents 5 %: half the heap in all) | **done** |
| | recent users cached 5 s in the identity store (each request read its user from SQLite); start-up warm-up on its own read slots | **done** |
| | console worker processes (`server.workers`, default 4): one Python process tops at ~40 pages/s | **done** |
| | **phase 1 integration test** (extreme lake, 8 GB): 16 functional checks pass; 50 users: server 146 req/s, console 69 req/s, 0 failures; heap 1.8 GB after collection | **passed** (2026-10-18) |
| | in-list paging (next 1,000 rows of a grouped page); console search under load (3 backend calls and a render per search: ~4 s at 50 users) | open |
| | the RocksDB document cache (section 8.1): **on by default**, one per connector (`<data dir>/cache/<connector>/docs`), 1 GB cap, 12 h TTL, hourly compaction, daily clearing, kept across restarts, skipped with a WARN when it cannot open; −17 % CPU per read for trades read before | **done** (2026-10-18) |
| | row groups of 256 KB and 64 KB pages in the lake writer: +13 % reads a second, −20 % CPU per first read (362,804 trades) | **done** |
| | one Parquet version (1.18.0) for every library (root `pom.xml`); the Parquet page index for single reads **on by default** (`drishti.parquet.page-index`): neutral on 256 KB row groups (812 vs 812 reads/s, same trades), it narrows reads of files with larger groups | **done** |
| | one memory budget for all connector caches (section 5) | next |
| | separate read slots for all background work | next |
| **2. Storage and caches** | id maps on disk, memory-mapped (`id-map-disk`): after a restart 3 ms instead of 729 ms per table date, first read of a past date 0.03–0.06 s instead of 0.24–0.34 s; 44 MB per trade connector | **done** (2026-10-18) |
| | grouped pages kept across restarts (`pages.json` beside the pages): served at once, no rebuild while the data is unchanged; 50 users straight after a restart 151 req/s, 0 failures (before: 395 timeouts) | **done** |
| | **phase 2 integration test** (extreme lake, 8 GB): two starts; heap 1.3 GB after collection | **passed** (2026-10-18) |
| | bloom filters on ids: not added (files sorted by id: statistics, the id map and the page index already find a trade); memory-mapped column sets: open (columns stay in the shared heap budget) | decided / open |
| **0. Measure** | scale benchmark in `drishti-benchmarks`: 3 systems × 300K–600K × 7 → 60 days; every path of section 7; heap and disk; a smaller version in CI against regressions | started (the 2026-10-18 runs, section 4) |
| **2. Storage and caches** | bloom filters on ids; memory-mapped warm tier for id maps and columns; RocksDB document cache (section 8.1) | planned |
| **3. Computed at load** | grouped totals and flattened columns written by the load | planned |
| **4. History** | DuckDB over Delta for anything across dates; BI monthly rollups | planned |
| **5. Operations** | p95 per screen and connector in Admin → Health; cache sizes against the memory budget in Admin → Caches; a sizing guide; retention in Admin → Lakes | planned |

## 13. Settings reference

| Setting | Where | Default | What |
|---|---|---|---|
| `refresh-seconds` | Delta connector | 10 | how often the table's log is checked for a new version |
| `lookback-days` | Delta connector | 10 | how far back a snapshot read looks for the newest partition on or before the date |
| `id-map-mb` | Delta connector | 1024 | id maps kept in the heap |
| `columns-cache-mb` | Delta connector | 1024 | columns of dates kept in the heap |
| `doc-cache-mb` | Delta connector | 256 | documents kept in the heap |
| `cache-mb`, `max-load-rows` | Delta connector | 512, 200000 | whole days loaded for reverse links (tables without promoted link columns) |
| `max-concurrent-reads` | Delta connector | 16 | single-entity reads at once |
| `warm-dates` | Delta connector | 3 | newest dates whose id maps load at start |
| `index.days`, `index.max-memory`, `index.storage`, `index.max-disk` | Delta connector | 1, 600MB, heap, 2GB | the type-ahead index |
| `store` | `derived` connector | `heap` (`disk` in `trade-pages`) | where the grouped pages live |
| `store-dir` | `derived` connector | `<data dir>/derived/<connector>` | the pages' only folder |
| `retire-after` | `derived` connector | 60s | replaced pages on disk kept this long for reads still running |
| `max-stream` | `derived` connector | **100000** | `store: heap` only: most members kept from a one-pass read; a WARN and DEGRADED say when it cuts |
| `version-check` | `derived` connector | **10s** | how often the data's version is asked; a new version rebuilds |
| `refresh` | `derived` connector | 30s (trading pack: 60s) | rebuild age, used only when a source cannot say its version |
| `max-scan` | `derived` connector | 50000 | members read one at a time, for sources that cannot read a day in one pass |
| `drishti.sources.fetch-timeout` | server | **5s** | longest single read (`DRS-1004`) |
| `DRISHTI_LAKE_ROW_GROUP_MB` | `lake build` (Java writer) | **1** | Parquet row group size |
| `drishti.tmp-dir` / `DRISHTI_TMP_DIR` | server | **`<data dir>/tmp`** | temporary files |
| `-Xmx` | server JVM | 4g in the demo configuration | at most 8 GB (section 1) |

## 14. Operations

- **Hardware**: SSD, not a network share; 16–32 GB RAM for an 8 GB heap plus the operating system's file cache
  (*estimate*). On Windows, the data folder excluded from antivirus real-time scanning.
- **Order on a business day**: load the day (`lake build`, one system at a time or in parallel by system), maintenance
  (Admin → Lakes), then let the server pick the version up (10 s) or send the load signal.
- **Watch**: Admin → Health (connector p50/p99 and errors; DOWN with a reason), Admin → Caches (entries and sizes),
  the log lines `… loaded, N ids in F files (T ms)` and `derived pages … built in … s`, and any `WARN … has more than
  … documents`.
- **Disk**: the lake (about 1–3 GB a day for 3 × 500K, *estimate*), `<data dir>/tmp` (DuckDB spill; capped by the
  query limits), the BI store, the type-ahead segment files (`index.max-disk`), later RocksDB (`disk-cache.max-gb`).

## 15. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Grouped pages run out of memory | their pages unavailable | done: pages on disk (`store: disk`); with `store: heap`, a clean failure and `max-stream` |
| The sum of cache caps passes the heap | long garbage collection, timeouts, out of memory | next: one memory budget; meanwhile defaults, `-Xmx8g`, Admin → Caches |
| First read of a date on a 500K table | a few seconds on that read | 5 s limit; `warm-dates`; phase 2 warm tier on disk keeps it after restart |
| A reload of a day while people read it | readers see the old version until the commit, then the new | by design (one commit per load) |
| Large documents (cash flows, events) | slower single reads | 1 MB row groups; promoted columns for everything but the trade page |
| The system temp folder is small (in memory) | the operating system runs out of memory | done: `<data dir>/tmp` |
| Antivirus scanning on Windows | every file read slower | exclude the data folder |

## 16. Decisions

1. **Hardware** for the year-scale server (SSD, RAM).
2. **Where the next steps go** in the delivery waves: grouped pages ids-only, the memory budget and separate read slots
   straight after the 2026-10-19 demo; phases 2–4 as a scale wave before or beside wave C.
3. **Retention**: days on fast disk, and what happens to older ones.
4. **Cache defaults**: TTL 12 h, 5 GB per trade connector for RocksDB (section 8.1).

## 17. Considered and rejected

| Idea | Why not |
|---|---|
| Store only the trades that changed (content hash against the previous day) | the dumps change too many fields every day; a full partition per day is fast when every read names its date |
| A RocksDB copy of the lake | two truths to keep in step; the cache must stay small and disposable |
| LMDB for the caches | fixed map size, no self-trimming, a new native dependency; RocksDB is shipped and trims itself |
| More heap | memory would grow with trades and days; the 8 GB limit is a goal, and holders must be bounded instead |
| Grouped pages that keep whole documents | out of memory at 300K (*measured*) |
