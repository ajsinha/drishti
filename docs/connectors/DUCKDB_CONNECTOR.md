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
# The DuckDB connector: a book in one embedded file

This document explains how Drishti serves dated entities from a **DuckDB** file through the `duckdb` connector: one
file holds every data domain, the Drishti server reads it in-process with no database server to run, and a load
replaces the file while the server keeps answering. It covers the file layout, the loader and its file swap, each read
path, retention, sizing, memory, measurements and limits.

DuckDB is an embedded columnar analytic database. It suits a desk, a laptop, a demo or a single server that holds a
large book in one file and wants searches over a whole day without running PostgreSQL, Aerospike or a lake. Read this
if you run Drishti on DuckDB, load a DuckDB file for Drishti, or change the connector. The same design for the other
stores is in [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md) (whose section 7 explains how the engine uses a day's columns)
and [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md), whose table mode this connector mirrors setting for setting. Every
setting is also in [CONFIGURATION.md](../admin/CONFIGURATION.md). To build demo data of any size, see
[DEMO_DATA.md](DEMO_DATA.md).

**About the numbers.** Every measurement here was taken on 2026-10-01 with **10,000 trades a day over three business
days** plus the banking samples (47,910 rows), a Drishti server with `-Xmx2g` and DuckDB limited to 1 GB; the scale
benchmark added 25,000 and 50,000 ([section 9](#9-measured-results)). Figures for a million trades a day are
**estimates** or **extrapolations** from those measurements and say so where they appear.

## Contents

1. [The problem](#1-the-problem)
2. [The file layout](#2-the-file-layout)
3. [Declaring the promoted columns](#3-declaring-the-promoted-columns)
4. [Loading, and replacing the file under a running server](#4-loading-and-replacing-the-file-under-a-running-server)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Retention: dropping days](#6-retention-dropping-days)
7. [Sizing, and DuckDB with Delta Lake](#7-sizing-and-duckdb-with-delta-lake)
8. [Memory in the Drishti server](#8-memory-in-the-drishti-server)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)
14. [Future work: reading the lake directly](#14-future-work-reading-the-lake-directly)

---

## 1. The problem

Drishti needs four things from a dated store, whatever it is: one entity on a business date (a point read), the
newest day's ids for type-ahead, a few fields of every entity of a day for searches, pick lists, derived kinds,
impact and reverse lookups (a column set), and each kind's business dates. At a million trades a day, a trade
document of 6–7 KB, kept for years, a store must answer the first in milliseconds, the third in about a second, and
never read a day of documents to do it.

A columnar engine is the natural home for the third question: a day's `mtm`, `notional` and `book` are three narrow
columns, read without touching the documents. DuckDB adds three constraints of its own that shape this design:

| DuckDB property | Consequence here |
|---|---|
| one process may write a file, or several may read it, never both | the server opens the file read-only; a load writes a new file and renames it over the old one ([section 4](#4-loading-and-replacing-the-file-under-a-running-server)) |
| it runs inside the Java process | its memory counts against the server's machine: the connector limits it (`memory-limit`) |
| new files use an old storage format by default, which keeps strings of a few kilobytes uncompressed | the loader writes the 1.5 format, where documents are compressed with ZSTD: ten times smaller ([section 7](#7-sizing-and-duckdb-with-delta-lake)) |

## 2. The file layout

`DuckDbLoader` (in the plugin) writes, and the connector reads, one DuckDB file (for example
`data/duckdb/drishti.duckdb`) with a schema per data domain:

```sql
CREATE SCHEMA trading;

CREATE TABLE trading.entities (
  kind          VARCHAR NOT NULL,
  id            VARCHAR NOT NULL,
  business_date DATE    NOT NULL,
  doc           VARCHAR NOT NULL,                       -- the whole document, JSON text
  "tradeId" VARCHAR, "productType" VARCHAR, "productName" VARCHAR, direction VARCHAR, currency VARCHAR,
  notional DOUBLE, mtm DOUBLE, "pnl1d" DOUBLE, "maturityDate" VARCHAR, "tradeDate" VARCHAR,
  book VARCHAR, desk VARCHAR, status VARCHAR, "assetClass" VARCHAR, "counterparty__id" VARCHAR,
  "counterparty__name" VARCHAR, "nettingSet" VARCHAR, "risk__dv01" DOUBLE, "sourceSystem" VARCHAR
);                                                      -- rows written in (kind, business_date, id) order

CREATE TABLE trading.entity_dates (kind VARCHAR, business_date DATE, rows BIGINT, loaded_at TIMESTAMPTZ,
                                   PRIMARY KEY (kind, business_date));
```

The other domains (`reference`, `market`, `risk`, `credit`, `collateral`) have the same two tables with their own
promoted columns. One row, for one trade on one day (the document shortened):

```
kind               trade
id                 CLY-4000834
business_date      2026-09-29
doc                {"tradeId": "CLY-4000834", "sourceSystem": "Calypso", "sourceTradeId": "4000834", "productType": "CLN", …}
tradeId            CLY-4000834
productType        CLN
notional           …
mtm                …
book               …
counterparty__id   …
nettingSet         …
```

Why each part is shaped this way:

- **One file, a schema per domain.** The six connectors of the banking packs read one file through one DuckDB
  instance ([section 5.1](#51-one-shared-read-only-instance)), so there is one memory limit and one buffer pool, and a
  load replaces every domain at once, consistently.
- **Rows in `(kind, business_date, id)` order.** DuckDB stores a table in row groups of 122,880 rows and keeps the
  minimum and maximum of every column of every row group (its zone maps). Because each day of each kind is written
  contiguously and sorted by id, a query naming a kind and a day skips every row group of other days, and a query
  naming an id as well reads one row group. A million trades a day is about eight row groups a day.
- **No index.** DuckDB can build an ART index on `(kind, id, business_date)`, and it was measured: on the test file
  the planner still chose a sequential scan with zone-map filters for the point read (DuckDB 1.5.6 uses an index scan
  for simple single-column predicates), the read took the same 5–8 ms with or without the index, and the index made
  the file larger and the load slower. The loader therefore builds none; the sort order does the work.
- **The document stays whole**, as JSON text, so any question about it can still be asked in SQL (DuckDB's `json`
  extension is built into the JDBC driver). In the 1.5 storage format DuckDB compresses it with ZSTD.
- **Promoted columns are copies** of the fields questions filter, sort, group and link by, named as in Delta Lake
  (`counterparty.id` is `counterparty__id`). Numbers are `DOUBLE` (stored with DuckDB's ALP compression), everything
  else `VARCHAR` (dictionary-compressed: a few hundred books cost almost nothing). Reading a day's columns reads these
  narrow columns, never the documents.
- **`entity_dates`** lists each kind's business dates, their row counts and when they were loaded, so the connector
  never scans the table to learn which days exist. A file without it (written by another tool) still works: the
  connector counts the days with one grouped scan of two columns.

## 3. Declaring the promoted columns

The pack declares the fields on the connector that stores the kind, exactly as for Delta Lake and PostgreSQL. The
trading pack declares them on `trading-store`; the `duckdb` profile (`SPRING_PROFILES_ACTIVE=duckdb`) switches that
connector to the `duckdb` plugin and keeps the pack's settings, so the declaration applies unchanged:

```yaml
# packs/trading/pack.yaml (generated)
connectors:
  trading-store:
    settings:
      layout:
        trade:
          columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                    tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                    risk.dv01, sourceSystem]

# drishti-server application-duckdb.yaml (the profile)
drishti:
  sources:
    connectors:
      trading-store: { plugin: duckdb, settings: { path: "${DRISHTI_DUCKDB_PATH:data/duckdb/drishti.duckdb}",
                       memory-limit: "${DRISHTI_DUCKDB_MEMORY:1GB}", table: trading.entities, pool-size: "8" } }
```

The connector serves a path as a column only when the table has that column; a declared path the table lacks shows
in Health ([section 11](#11-diagnosing)).

## 4. Loading, and replacing the file under a running server

`tools/load-duckdb.sh` loads the banking packs' data, and optionally a generated book:

```bash
tools/load-duckdb.sh data/duckdb/drishti.duckdb                              # samples: 1,791 documents x 10 days
tools/load-duckdb.sh data/duckdb/drishti.duckdb --trades 10000 --days 3      # and 10,000 trades a day for 3 days
tools/load-duckdb.sh data/duckdb/drishti.duckdb --keep-days 2557             # keep seven years
```

```
duckdb: 17,910 rows staged in 1 s
duckdb: loaded 17,910 rows into data/duckdb/drishti.duckdb (15 MB) in 2 s
30,000 trade-days as JSON lines in 2 s
duckdb: 30,000 rows staged in 2 s
duckdb: trading: kept 5,250 rows of the current file
duckdb: loaded 30,000 rows into data/duckdb/drishti.duckdb (33 MB) in 3 s
```

### 4.1 Why the loader never writes the file the server reads

DuckDB takes a lock on its file: one process may open it to write, or any number may open it read-only, never both.
The server holds the file read-only for as long as it runs, so a loader that opened it to write would fail. The loader
therefore builds a new file and swaps it in:

1. **Stage.** The stream is parsed on `--parsers` threads (8): the reader cuts it into batches of 1,000 lines, at most
   twice as many batches as parsers are in flight, and the batches are taken back in their original order. Each row is
   appended with DuckDB's appender, as it comes, to `<database>.stage`, a scratch file with one table per data domain.
   A promoted path seen with a value for the first time becomes a column of the stage table.
2. **Build.** A new file, `<database>.loading`, is written in the 1.5 storage format, in one transaction. The current
   file is attached read-only (allowed while the server holds it). For each data domain:
   - the rows of the current file that stay are copied in the order they were written, which is already sorted;
     a (kind, business date) the stream carries is left out, so **a day in the stream replaces that day whole**;
   - then each staged (kind, business date) is inserted **sorted by id**; when the stream carries the same entity
     twice for a day, the last one wins;
   - `entity_dates` records each day, its rows and its load time (kept days keep theirs).
3. **Swap.** The new file is checkpointed, closed, and renamed over `<database>` in one atomic rename. The stage file
   and DuckDB's spill directory (`<database>.tmp`) are deleted.

A lock file (`<database>.lock`) keeps two loads of one file apart. `--recreate` drops the existing rows of each data
domain the stream reaches (the samples' load uses it); domains the stream does not reach are copied unchanged.

### 4.2 How the server notices

Every `refresh-seconds` (10) each connector checks the file: one `stat` of its identity (inode), modification time and
size. When nothing changed, that is all: the file is read-only, so its contents cannot have changed either. When the
file was replaced, the shared instance opens the new file as a new **generation**: new reads go to it at once; reads in
flight finish on the old instance, which is closed when the last of them returns. On Linux and macOS a file renamed over
stays readable through the handles already open on it, so nothing fails during the swap. Each connector then reads its
domain's dates, columns and ids again and drops the days of columns it kept.

Measured: two loads (samples, then the book) ran while a client read a trade every 50 ms; both swaps were picked up
within 10 s (`generation: 3` in Health), and none of the 200 reads failed. The reads made between the two loads
answered 404, correctly: `--recreate` on the samples' load had replaced the trading domain with the samples alone until
the book's load put the trades back.

### 4.3 Your own loader

Write the same layout, then rename. In SQL, from any DuckDB 1.5 client:

```sql
ATTACH 'data/duckdb/drishti.duckdb' AS cur (READ_ONLY);
ATTACH 'data/duckdb/drishti.duckdb.loading' AS nxt (STORAGE_VERSION 'v1.5.0');
BEGIN;
CREATE SCHEMA nxt.trading;
CREATE TABLE nxt.trading.entities AS SELECT * FROM cur.trading.entities WHERE NOT (kind = 'trade' AND business_date = DATE '2026-10-01');
INSERT INTO nxt.trading.entities BY NAME SELECT * FROM read_json('trades-2026-10-01.jsonl') ORDER BY id;   -- your rows
CREATE TABLE nxt.trading.entity_dates AS SELECT * FROM cur.trading.entity_dates WHERE NOT (kind = 'trade' AND business_date = DATE '2026-10-01');
INSERT INTO nxt.trading.entity_dates VALUES ('trade', DATE '2026-10-01', 1000000, now());
COMMIT;
CHECKPOINT nxt;
DETACH nxt;
-- then, outside DuckDB: mv data/duckdb/drishti.duckdb.loading data/duckdb/drishti.duckdb
```

What matters: **each day sorted by id and written contiguously**, **the promoted columns hold exactly the document's
values**, **a day recorded in `entity_dates`**, **storage version 1.5**, and **an atomic rename** of a closed file.

### 4.4 The cost of rewriting the file, and the production pattern

Every load rewrites the whole file. For the samples and a book of thousands or tens of thousands of trades that takes
seconds. At a million trades a day it would mean copying the whole history on each daily load: a year at the estimated
0.7 GB per million trades a day ([section 7](#7-sizing-and-duckdb-with-delta-lake)) is about 180 GB to rewrite every
night, which is not reasonable. For large histories:

- **one file per month**, `drishti-2026-09.duckdb`, `drishti-2026-10.duckdb`, …: a daily load rewrites only the current
  month's file (about 15 GB estimated at a million trades a day); retention is deleting files. The connector reads one
  file today; reading a directory of monthly files attached together is future work (a view over the attached files,
  or one connector per file in the router's store order);
- **or DuckDB for recent days and Delta Lake for the years**, as below.

## 5. How the connector reads

### 5.1 One shared, read-only instance

The connectors of one server that name the same `path` share one DuckDB instance (`DuckDbFile`), opened with
`duckdb.read_only=true`, the first connector's `memory-limit` and `threads`, and its spill directory under the JVM's
temporary directory. Each read borrows a connection duplicated from that instance (`DuckDBConnection.duplicate()`):
DuckDB runs statements of different connections of one instance at the same time, each using up to `threads` cores.
Concurrent reads are bounded by a semaphore of `pool-size` permits per connector (the six banking connectors together
allow 28). Concurrent reads on duplicated connections were verified (eight virtual threads at once), as was the refusal
of any write on the read-only instance.

Table names are always fully qualified with the database name DuckDB gives the file (`"drishti".trading.entities`),
because a file named like a schema (`trading.duckdb`) would otherwise make `trading.entities` ambiguous.

### 5.2 The catalogue

When a generation opens (at start and after each load), the connector reads:

- `entity_dates`: each kind's business dates and the time of the last load;
- the table's columns (`duckdb_columns()`), to know which declared paths it can serve as columns;
- each kind's newest day's ids (every id it ever had for an `effective` kind) into Drishti's in-memory type-ahead
  index;

then reads the newest day's columns of every laid-out kind in the background, so the first search finds them.

### 5.3 Opening one entity

`TRD CLY-4000834` on 2026-09-29: the snapshot date comes from memory (the kind's newest date on or before the date
asked, within `lookback-days`), then one query:

```sql
SELECT doc, CAST(business_date AS VARCHAR) FROM "drishti".trading.entities
 WHERE kind = 'trade' AND business_date = CAST('2026-09-29' AS DATE) AND id = 'CLY-4000834'
```

The zone maps leave the one row group whose id range holds the id; DuckDB scans its `id` column and decompresses the
document of the matching row only. An `effective` kind reads the entity's newest row on or before the date
(`ORDER BY business_date DESC LIMIT 1`). Measured: 15 ms over HTTP for a trade's document, today or a past day; 5–8 ms
for the query itself.

### 5.4 Type-ahead

From memory: the newest day's ids, sorted, searched by prefix; no query per keystroke. 18 ms over HTTP.

### 5.5 A day's columns

Searches, pick lists, derived kinds, impact and reverse lookups need a few fields of every trade of a day. The
connector reads them as a column set:

1. **Cut the day into ranges.** `quantile_disc(id, [0.25, 0.5, 0.75])` over the day's ids gives `scan-threads - 1`
   boundaries (4 ranges by default).
2. **Read the ranges at once**, each on its own duplicated connection:

   ```sql
   SELECT id, mtm, notional, …, book, "nettingSet" FROM "drishti".trading.entities
    WHERE kind = 'trade' AND business_date = CAST('2026-09-30' AS DATE) AND id >= ? AND id < ? ORDER BY id
   ```

   DuckDB scans in parallel within one query too; the ranges spread the work of turning rows into Java values over
   several threads. No document is read: DuckDB reads only the columns named.
3. **Keep** it by memory (`columns-cache-mb`, 1024; a million trades of 19 fields is about 230 MB, estimated), for
   `columns-seconds` (300), dropped at once when a new file is opened. Repeated texts (books, desks, currencies) share
   one copy.
4. **Bound** the work: at most two days are read at a time.

The newest day is read in the background; another business day is read when first asked for: 59 ms for the first
search on a past day with 10,000 trades, then 9 ms. A day's columns query on the test file took 21 ms in DuckDB.

### 5.6 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the day's promoted text columns: every value equal to
the id. A kind without promoted link columns is answered from the documents, reading at most `max-load-rows` (200,000)
of them:

```sql
SELECT id FROM (SELECT id, doc FROM "drishti".trading.entities WHERE kind = ? AND business_date = ? LIMIT 200000)
 WHERE contains(doc, '"NS-SUMMIT-NY"') ORDER BY id
```

(the quoted id: a string value equal to it anywhere in the document; an `effective` kind takes each entity's latest
document with `arg_max(doc, business_date)`). `reverse-index: false` turns reverse lookups off.

### 5.7 Derived kinds and impact

Desk P&L and impact work from the column set as on Delta Lake and PostgreSQL: desk P&L of `DESK-RATES` in 34 ms, then
11 ms; impact of netting set `NS-SUMMIT-NY` in 60 ms, on the 10,000-trade book.

## 6. Retention: dropping days

`--keep-days N` on the loader drops every business date more than N calendar days before `--as-of` (default: today
in the business zone), never counted from the newest date in the file or the load, and their dates, while it builds the
new file:

```
duckdb: hist: dropped 1 business dates before 2026-09-01
```

A run that would drop more than `--max-drop-share` (0.5) of a domain's rows fails before the new file replaces the old
one (`… retention would drop … rows …; nothing was dropped`), and the current file stays as it was; `--force-drop`
overrides it. A row dated after tomorrow in the business zone is not loaded at all, and the load ends with an error
naming it, so one mis-dated row can neither appear as the newest day nor move the cut-off.

Because the new file is written from scratch, dropped days leave no free space behind: the file shrinks at once, with
no `VACUUM` or compaction step. Seven years is `--keep-days 2557`. Retention applies to every kind of the domain,
including `effective` kinds: an entity whose last change is older than the cut-off disappears with it. Keep reference
data in a domain with a longer `--keep-days`, or reload its current state, if that matters.

## 7. Sizing, and DuckDB with Delta Lake

Measured on 2026-10-01: a file holding only the generated book, 10,000 trades a day over three days (30,000 trade
documents of 6–7 KB, about 190 MB of JSON), is **20 MB**: about **0.7 KB per trade a day** with documents, promoted
columns and dates. The samples plus that book make a 33 MB file.

The storage format matters more than anything else here. Written in DuckDB's default (older) format, the same 35,250
trading documents took 242 MB, stored uncompressed (DuckDB keeps strings of a few kilobytes out of its string
compression in that format). In the 1.5 format, which the loader always writes (`STORAGE_VERSION 'v1.5.0'`), DuckDB
compressed them with ZSTD to 22 MB.

| History in DuckDB, a million trades a day (**estimates**) | Rows | File (about) |
|---|---|---|
| 1 day | 1 million | 0.7 GB |
| 3 months | 63 million | 45 GB |
| 1 year | 250 million | 180 GB |
| 7 years | 1.76 billion | 1.2 TB |

These estimates scale the measured 0.7 KB per trade a day linearly. The generated book is built from a few hundred
templates, which compress better than real trades may; plan with a margin and measure a day of your own data. Even so,
the comparison is clear: PostgreSQL measured 3.7 GB per million trades a day, of which compressed documents and
indexes are most; DuckDB keeps no index, compresses whole blocks of documents together, and stores promoted columns in
compressed columns.

**Recent history in DuckDB, the years in Delta Lake.** Configure both connectors for the kind, DuckDB first. A read, a
search, a pick list, a derived kind and impact ask the connectors in order and take the first that holds the business
day: `columns(…)` returns nothing for a day the file does not hold, so the router asks Delta Lake next. Keep the DuckDB
file to the days users work with (`--keep-days 31`, about 15 GB at a million trades a day, estimated) and the full
history in the lake.

## 8. Memory in the Drishti server

| Held | 10,000 trades a day (measured) | A million trades a day (**estimate**) | Bounded by | Default |
|---|---|---|---|---|
| type-ahead ids (newest day) | a few MB | about 150 MB | the newest day only | — |
| a day's column set (19 fields) | about 2 MB | about 230 MB | `columns-cache-mb` | 1024 MB |
| DuckDB's buffers and working memory (outside the Java heap) | under 1 GB | up to the limit | `memory-limit` | 1 GB |

Documents are not cached by the connector: DuckDB's buffer pool keeps the blocks read often. Measured server heap in
use with the samples and 10,000 trades a day: **141 MB** of a 2 GB heap. DuckDB's memory is native, outside the Java
heap: size the machine for the heap plus `memory-limit`. For a million trades a day, give the server about 4 GB of
heap (estimate: ids, two or three days of columns and the engine's working set) and DuckDB 2–4 GB.

## 9. Measured results

On 2026-10-01, a developer workstation (24 cores, shared with other builds), DuckDB 1.5.6 (JDBC driver
`org.duckdb:duckdb_jdbc:1.5.6.0`) embedded in a Drishti server with the `duckdb` profile and `-Xmx2g`,
`memory-limit` 1 GB, the banking samples plus **10,000 trades a day over three business days** (47,910 rows), times
over HTTP:

| Question | Time |
|---|---|
| load the samples / 30,000 trade-days (streamed) | 2 s / 3 s (the whole script: 11 s, with building the plugin) |
| file size, samples + book / book alone | 33 MB / 20 MB |
| server start | 5.2 s |
| type-ahead `TRD CLY-400` | 18 ms |
| open a trade (view), first / again | 61 ms / 30 ms |
| a trade's document, today / a past day | 15 ms / 14 ms |
| `TRD where mtm < -50m order by mtm` | 10–14 ms (21 matches of 10,000) |
| `TRD where currency = 'USD' and notional > 500m …` | 20 ms |
| `TRD book=BOOK-RATES-3` | 10 ms |
| pick list `TRD END-1100` | 20 ms |
| desk P&L `DESK-RATES`, first / again | 34 ms / 11 ms |
| impact of netting set `NS-SUMMIT-NY` | 60 ms |
| a search on another business day, first / again | 59 ms / 9 ms |
| a load while the server runs: new file served | within 10 s, no failed read |
| server heap in use | 141 MB |

Every search answered over all 10,000 trades of the day (`partial: false`, `scanned: 10000`).

**A million trades a day: not measured; extrapolated.** The straight lines through the scale benchmark's points (the
table below) put the newest day's three searches at 64–155 ms, the first search on another business day at about 2 s and
then about 70 ms, the file for three days at about 2.2 GB (0.7 GB a day, as estimated in [section
7](#7-sizing-and-duckdb-with-delta-lake)), and a trade's document today flat at about 10 ms; a document on a past day
rises with the size of that day's id lookup (about 260 ms on the line). These are **extrapolations from 10,000 to 50,000
trades a day, not measurements**. Loading is limited by the generator and by rewriting the file ([section
4.4](#44-the-cost-of-rewriting-the-file-and-the-production-pattern)).

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, DuckDB embedded, `memory-limit` 1 GB. Medians of 25 requests; every search exact
(`partial: false`, `scanned` equal to the trades a day). The last column carries a straight-line fit (R² beside it) to a
million trades a day: **an extrapolation from the measured points, not a measurement**; "flat" means the measure does
not grow with the book. This store has not been measured at a million trades a day; the last column is the best figure
there is, and only an order of magnitude.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 7.8 | 8.5 | 8.0 | 0.19 | 18.3 ms (weak fit) |
| open a trade (view), first time (ms) | 5.3 | 3.8 | 4.1 | 0.42 | flat, about 4.5 ms |
| a trade's document, today (ms) | 11.6 | 11.9 | 6.0 | 0.79 | flat, about 10.2 ms |
| a trade's document, a past day (ms) | 6.0 | 6.7 | 16.6 | 0.90 | 264 ms |
| `TRD where mtm < -50m order by mtm` (ms) | 1.2 | 2.4 | 3.6 | 0.98 | 64.1 ms |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 1.7 | 4.3 | 7.9 | 1.00 | 155 ms |
| `TRD book=BOOK-RATES-3` (ms) | 2.3 | 3.7 | 8.0 | 0.98 | 151 ms |
| pick list `TRD END-1100` (ms) | 0.8 | 1.5 | 3.4 | 0.99 | 64.6 ms |
| desk P&L `DESK-RATES` (ms) | 3.0 | 3.2 | 3.4 | 0.98 | 11.1 ms |
| impact of `NS-SUMMIT-NY` (ms) | 21.2 | 23.0 | 23.8 | 0.64 | 67.1 ms |
| a search on another day, first (ms) | 38.3 | 62.5 | 115 | 1.00 | 1,965 ms |
| a search on another day, again (ms) | 1.3 | 2.9 | 4.1 | 0.95 | 72.4 ms |
| load (the whole script) (s) | 12.5 | 15.4 | 22.3 | 0.99 | 258 s |
| store size (MB) | 36.5 | 68.3 | 122 | 1.00 | 2,158 MB |
| server live heap after a full GC (MB) | 87.4 | 97.8 | 114 | 1.00 | 728 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 10. Limits and trade-offs

- **One writer, by swapping files.** Loads never write the file the server reads; each load rewrites the whole file.
  Fine up to a few months of a large book or years of a small one; beyond that, one file per month or DuckDB with
  Delta Lake ([section 4.4](#44-the-cost-of-rewriting-the-file-and-the-production-pattern)).
- **The swap needs Linux or macOS semantics.** Renaming a file over one that is open works there; on Windows the
  rename fails while the server holds the file. Run loads into a new path and switch `path` instead, or stop the
  server.
- **A load is seen within `refresh-seconds`** (10). Until then type-ahead and searches answer from the previous file.
- **Disk during a load**: the stage file (documents uncompressed) and the new file exist beside the old one until the
  swap; allow about three times the file size plus the day's raw JSON.
- **Only promoted fields are fast.** A search on any other field reads documents (at most 20,000, `partial`).
- **Effective-mode kinds** are not read as columns; they are small and read as documents.
- **DuckDB 1.5 or later reads the file**, because of the storage format. The connector bundles 1.5.6.
- **The memory limit is per instance**: the first connector on a file sets `memory-limit` and `threads` for all the
  connectors on it; give them the same values.
- **Retention drops `effective` history too** ([section 6](#6-retention-dropping-days)).

## 11. Diagnosing

`GET /api/v1/admin/health` shows each connector and its catalogue:

```json
{"name": "trading-store", "health": "UP", "cache": {"ids": 10000, "kinds": 1, "datesIndexed": 10, "columnSets": 2, "generation": 3}}
```

`generation` counts the files opened since start: it goes up by one after each load.

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: no DuckDB file at …` | the file was never loaded, or `path` is wrong | run `tools/load-duckdb.sh`; the connector opens the file within `refresh-seconds` once it exists. Until then reads fail with `DRS-1003` (the connector cannot tell what it holds, so it does not let another store answer instead) |
| `DOWN: DuckDB file … not opened: …` | the file is locked by a writer, damaged, or of a newer DuckDB | do not open the file read-write while the server runs; load through the loader's swap |
| `UP (catalogue not refreshed: trading.entities is not in …)` | the file has no such domain | check `table`, or load that domain |
| `UP (not laid out as the pack declares: trade (0 of 19 columns); searches read documents)` | the file lacks the promoted columns (written another way) | reload with the loader, with the `columns` in each row |
| searches say `partial: true` | a field the query reads is not promoted | add it to `layout.<kind>.columns` and reload |
| type-ahead misses new trades, `generation` unchanged | the load finished less than `refresh-seconds` ago, or failed before the rename | wait; check the loader's output |
| loader: `another load of … is running` | two loads of one file at once | wait for the first |
| a `.stage`, `.loading` or `.tmp` beside the file | a load was killed | harmless: the next load deletes them |
| the server's resident memory exceeds its heap by about a gigabyte | DuckDB's native memory | expected; bounded by `memory-limit` |

## 12. Settings

On a `duckdb` connector (`drishti.sources.connectors.<name>.settings`):

| Setting | Default | Meaning |
|---|---|---|
| `path` | none (required) | the DuckDB file; connectors with the same path share one instance |
| `table` | none (required) | `schema.entities`, the data domain this connector serves |
| `memory-limit` | `1GB` | DuckDB's memory limit for the shared instance (native memory, outside the heap); the first connector on a file sets it |
| `threads` | every core | DuckDB's threads for the shared instance; the first connector on a file sets it |
| `pool-size` | `4` | reads of this connector at once; at least `scan-threads` plus the concurrent reads you expect |
| `kinds` | the kinds the file holds | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | the promoted paths ([section 3](#3-declaring-the-promoted-columns)) |
| `refresh-seconds` | `10` | how often the file is checked for a new load (one `stat` when nothing changed) |
| `scan-threads` | `4` | id ranges of a day read at once |
| `columns-cache-mb` | `1024` | memory for days of promoted columns |
| `columns-seconds` | `300` | how long a day's columns are kept (a new file clears them at once) |
| `max-load-rows` | `200000` | the most documents a reverse lookup reads for a kind without promoted link columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | `duckdb` | the name shown in provenance and Health |

The `duckdb` profile reads `DRISHTI_DUCKDB_PATH` (default `data/duckdb/drishti.duckdb`) and `DRISHTI_DUCKDB_MEMORY`
(default `1GB`).

Loader options (`DuckDbLoader FILE|- DATABASE`, through `tools/load-duckdb.sh [database]`): `--recreate`,
`--keep-days N` (0: keep everything), `--as-of yyyy-MM-dd` (today in the business zone), `--future-days N` (1), `--zone Z` (`DRISHTI_BUSINESS_ZONE`, else `America/New_York`), `--max-drop-share F` (0.5), `--force-drop`, `--parsers N` (8, or the cores if fewer), `--memory-limit` (`2GB`),
`--threads N` (every core). The script also takes `--trades N --days D` for a generated book.

## 13. Checklist for production

1. Declare `layout.<kind>.columns` for every large kind; load with the `columns` in each row.
2. Load through the loader (or your own writer of the same layout, then an atomic rename); never open the served file
   read-write.
3. Choose the history the file keeps (`--keep-days`); keep the full history in Delta Lake, or one file per month.
4. Size the disk for the file three times over during a load, and the machine for the heap plus `memory-limit`.
5. Give every connector on a file the same `memory-limit` and `threads`.
6. Check Admin → Health (`UP`, `generation`), then measure on your data (`elapsedMs`, `scanned` and `partial` of a
   search), and measure the file size of one day of your own documents before extrapolating.

## 14. Future work: reading the lake directly

DuckDB can read Parquet, Delta Lake and Iceberg tables in place, which would let one connector serve the Delta Lake
written for the `delta` connector without a load. The JDBC driver 1.5.6 bundles the `json` and `parquet` extensions;
the `delta` and `iceberg` extensions are not bundled and DuckDB would download them on first use. The connector does
not download extensions at run time, so a `lake` mode (`delta_scan('data/delta/trading/trade')` per day, with the same
column sets) is left for when the extensions can be shipped with Drishti or installed from a local repository. Reading
the lake's Parquet files directly without the Delta log is not safe (it would see removed files), so it is not offered.
