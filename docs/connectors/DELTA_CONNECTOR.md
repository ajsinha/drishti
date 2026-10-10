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
# The Delta Lake connector at scale: a million trades a day, for seven years

This document explains how Drishti serves a book of **1,000,000 trades every business day, kept for seven years**
(about 1,760 business days, 1.76 billion trade-days, about 2.8 TB) from a Delta Lake, with type-ahead in tens of
milliseconds, a trade opening in well under a second, and searches over every trade of a day in a few hundred
milliseconds. It covers what is stored and how, what the connector reads for each question a user asks, how the lake is
written and kept in shape, how much memory the server needs, what was measured, and where the limits are.

Read it if you run Drishti over a large lake, if you load such a lake (your ETL writes what is described here), or if
you change the connector. For setting the connector up the first time, start with
[the walk-through at the end of this document](#walk-through-step-by-step); every setting is in
[CONFIGURATION.md](../admin/CONFIGURATION.md#delta--delta-lake). The Aerospike connector follows the same design with its own
storage: [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md).

## Contents

1. [The problem](#1-the-problem)
2. [The principle: never load a day](#2-the-principle-never-load-a-day)
3. [What is on disk](#3-what-is-on-disk)
4. [Declaring the layout in a pack](#4-declaring-the-layout-in-a-pack)
5. [Writing the lake](#5-writing-the-lake)
6. [How the connector reads](#6-how-the-connector-reads)
7. [Searches, pick lists, derived kinds and impact over columns](#7-searches-pick-lists-derived-kinds-and-impact-over-columns)
8. [Seven years of history](#8-seven-years-of-history)
9. [Memory](#9-memory)
10. [Keeping the lake in shape](#10-keeping-the-lake-in-shape)
11. [Measured results](#11-measured-results)
12. [Limits and trade-offs](#12-limits-and-trade-offs)
13. [Diagnosing](#13-diagnosing)
14. [Settings](#14-settings)
15. [Checklist for production](#15-checklist-for-production)
16. [Engines: native and Hadoop](#16-engines-native-and-hadoop)

Also: [Security: TLS and credentials](#security-tls-and-credentials) (before *As a connector file*).

---

## 1. The problem

A trade document in the trading pack is about 6–7 KB of JSON: terms, legs, every cashflow, schedules, risk,
valuation, settlement instructions. A bank books a million of them; each business day the book is written again with
that day's valuations (a *snapshot* table: every trade, every day). Over seven years that is:

| Quantity | Value |
|---|---|
| Trades per business day | 1,000,000 |
| Business days in seven years | about 1,760 |
| Trade-days (rows) | about 1.76 billion |
| Document text per day | about 7 GB (JSON) |
| On disk per day (Parquet, compressed) | about 1.7 GB |
| On disk for seven years | about 2.8 TB |

The first version of the connector read a table the simple way: to answer anything about a business day it loaded that
whole day (every document) into memory and kept it in a cache. That is right for a few thousand entities and fails at a
million, as measured on 2026-10-01 against 500,000 trades a day:

| Question | What happened |
|---|---|
| open one trade | timed out (the read deadline is 2 s): the whole day, gigabytes of JSON, was being loaded |
| open it again | timed out again: the day was larger than the cache, so it was thrown away as soon as it was loaded |
| type-ahead index | rebuilt every minute by loading the newest day in full |
| a search over the book | read only the first 20,000 trades it could list (`partial`), parsing each document |
| desk P&L (a derived kind) | timed out; read at most 50,000 trades |
| server memory | 8 GB in use, then `OutOfMemoryError` under a few concurrent requests |

## 2. The principle: never load a day

Every question a user asks needs very little of a day:

| Question | What it really needs |
|---|---|
| open trade `MX-20000017` | one document |
| type-ahead `TRD CLY-4083` | the ids, sorted |
| `TRD where mtm < -50m order by mtm` | the id and `mtm` of every trade |
| `TRD book=BOOK-RATES-3` (pick list) | `book` and the pick-list columns of every trade |
| desk P&L | `desk`, `mtm`, `pnl1d`, `risk.dv01` … of every trade |
| impact (F8) of a netting set | `nettingSet` and `mtm` of every trade |
| linked entities (reverse lookups) | the link fields (`nettingSet`, `book`, `counterparty.id`) of every trade |

So the design is: **store each day so that each of these can be read on its own, and read only that.** Concretely:

1. **The fields searches use are stored as their own columns** (*promoted columns*) beside the full document, so a search
   reads a few narrow columns instead of parsing millions of JSON documents.
2. **Rows are sorted by id**, each file holds its own id range, and files are cut into small row groups, so one
   trade's document is found in one small piece of one file.
3. **The connector keeps small, purpose-built structures in memory**, each bounded: a day's ids (an *id map*), a day's
   promoted columns (a *column set*), and recently read documents. Never a whole day of documents.

The pack declares what to promote and how to sort ([section 4](#4-declaring-the-layout-in-a-pack)); the writers follow
it ([section 5](#5-writing-the-lake)); the connector uses it when it is there and still works, slowly, when it is not.

## 3. What is on disk

```
<root>/                                         DRISHTI_DELTA_ROOT, e.g. ./data/delta or s3a://risk-lake/banking
└── trading/                                    the data domain (the connector's `domain`)
    └── trade/                                  one Delta table per kind
        ├── _delta_log/                         the transaction log: one JSON file per commit, plus checkpoints
        │   ├── 00000000000000000000.json
        │   ├── …
        │   └── 00000000000000001800.checkpoint.parquet
        ├── business_date=2026-09-28/           one partition per business day
        ├── business_date=2026-09-29/
        └── business_date=2026-09-30/
            ├── part-00000-….snappy.parquet     ids BBG-60000001 … CLY-4083458  (250,000 rows)
            ├── part-00001-….snappy.parquet     ids CLY-4083459 … IMG-500374
            ├── part-00002-….snappy.parquet     ids IMG-500375 … MX-30083832
            └── part-00003-….snappy.parquet     ids MX-30083833 … WSS-2166540
                                                each file: 250 row groups of 1,000 rows
```

Each row has these columns:

| Column | Type | Content |
|---|---|---|
| `id` | string | the entity's id, `MX-20000017` |
| `doc` | string | the whole JSON document, exactly what a view shows |
| `business_date` | date | the partition column |
| `tradeId`, `productType`, `productName`, `direction`, `currency`, `maturityDate`, `tradeDate`, `book`, `desk`, `status`, `assetClass`, `nettingSet`, `sourceSystem` | string | promoted fields |
| `notional`, `mtm`, `pnl1d` | double | promoted fields |
| `counterparty__id`, `counterparty__name` | string | promoted nested fields (`counterparty.id`: a dot becomes `__`) |
| `risk__dv01` | double | promoted nested field |

One row, as the writer produces it (the document shortened):

```
id                  MX-20000017
business_date       2026-09-30
doc                 {"tradeId": "MX-20000017", "sourceSystem": "Murex", "sourceTradeId": "20000017",
                     "productType": "IRS_FIXFLOAT", "notional": 242000000.0, "mtm": 1875863, "book": "BOOK-RATES-3",
                     "counterparty": {"id": "CP-MERIDIAN-RE", "name": "Meridian Reinsurance Ltd"}, "legs": [ … ], …}
tradeId             MX-20000017
productType         IRS_FIXFLOAT
notional            242000000.0
mtm                 1875863.0
book                BOOK-RATES-3
counterparty__id    CP-MERIDIAN-RE
counterparty__name  Meridian Reinsurance Ltd
risk__dv01          -155245.0
…
```

Why each part is shaped this way:

- **The document stays whole.** A view needs every field, and laying a trade's 200-odd fields out as columns would
  make every reader and writer depend on every product's shape. Only the few fields questions filter, sort, group and
  link by are copied out.
- **Promoted columns are copies.** The document is the truth; the columns exist so that searches do not have to open
  it. A writer must fill both from the same document ([section 5](#5-writing-the-lake)).
- **Sorted by id, a file per id range.** Delta records each file's minimum and maximum `id` in its log; Parquet records
  each row group's minimum and maximum in the file's footer. With rows sorted, a reader looking for one id skips every
  file and row group whose range cannot contain it.
- **Row groups of 1,000 rows.** Reading one document decodes one row group of the `doc` column: about 7 MB of
  documents at 1,000 rows, about 70 MB at 10,000. Smaller groups make single reads cheaper; column scans barely notice,
  since the promoted columns compress to almost nothing either way.
- **Files of 250,000 rows (about 430 MB).** Four files a day keep the log small (seven years is about 7,000 files) and
  each file large enough to read efficiently from object storage.
- **Statistics only on useful columns.** Delta stores per-file minimum and maximum values in the log for statistics
  columns. By default that includes `doc`, which puts two copies of a whole JSON document into the log for every file,
  17–50 KB a commit. The writers set `delta.dataSkippingStatsColumns` to `id`, `business_date` and the promoted columns:
  the log of three days of a million trades is 56 KB.

## 4. Declaring the layout in a pack

The layout belongs to the kind, so the pack that owns the kind declares it, on the Delta connector that stores it. The
trading pack suggests this template (`packs/trading/pack.yaml`, written by `tools/packgen/banking/make_packs.py`); at the first start the server writes it to
`config/connectors/trading-store.yaml`, which is then the site's file ([As a connector file](#as-a-connector-file)):

```yaml
connectors:
  trading-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [trade]
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: trading
      layout:
        trade:
          columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                    tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                    risk.dv01, sourceSystem]
          sort-by: id
          file-rows: 250000
          row-group-rows: 1000
```

| Key | Meaning |
|---|---|
| `layout.<kind>.columns` | Document paths to promote, as written in the document (`mtm`, `counterparty.id`). The column name is the path with `.` replaced by `__`. |
| `layout.<kind>.sort-by` | `id` (the default and the right choice for entity tables): rows sorted by id within each business day. |
| `layout.<kind>.file-rows` | Rows per file (default 250,000). Each file holds a contiguous id range. |
| `layout.<kind>.row-group-rows` | Rows per Parquet row group (default 10,000; the trading pack sets 1,000, which keeps the first open of a trade fast). |

The pack loader flattens the nested settings into the connector's flat settings (`layout.trade.columns =
tradeId,productType,…`), which is what the connector reads; in a connector file you may write them nested or flat.

**Choosing the columns.** Promote every field that a question over the whole book reads:

| Used by | Fields (trading pack) |
|---|---|
| pick-list columns (`columns:` in `pack.yaml`) | `productType`, `direction`, `currency`, `notional`, `mtm`, `maturityDate`, `book` |
| searches people run | `mtm`, `notional`, `currency`, `status`, `assetClass`, `tradeDate`, `maturityDate`, `sourceSystem` |
| derived kinds (desk P&L) | `desk`, `mtm`, `pnl1d`, `risk.dv01`, `book`, `currency`, and their rows: `tradeId`, `productName` |
| impact (F8) measures and roll-ups | `mtm`, `nettingSet` |
| reverse lookups (linked entities) | `nettingSet`, `book`, `desk`, `counterparty.id` |

A search whose condition, ordering or result columns use a field that is **not** promoted is answered from documents
instead ([section 7](#7-searches-pick-lists-derived-kinds-and-impact-over-columns)), so a missing column shows up as
a slow, partial search. Each promoted column costs little: numbers take 8 bytes a row before compression, and
repeated text (books, desks, currencies, product types) compresses to almost nothing.

Kinds that are small (curves, counterparties, books: hundreds or thousands a day) need no layout: the connector reads
them fast either way.

## 5. Writing the lake

Drishti only reads the lake. Whatever writes it must follow the layout. The repository's writers do:

| Writer | What it writes |
|---|---|
| `tools/packgen/banking/make_data.py --lake data/delta` | the banking packs' 1,791 sample documents, 10 business days, every domain; the trade table in its layout |
| `tools/samplegen/lake.py --samples <dir> --root data/delta --domain <d> --days 10` | one pack's samples into one domain; it prints `<table>: <n> entities x 10 business days` per table and writes the newest date twice (the second commit restates one document per kind), so *known at* before that commit shows the original; local disk only |
| `tools/samplegen/bulk_trades.py --trades N --days D` | a large trade book in the layout, replacing the trade table (scale tests) |
| `tools/lake/maintain.py relayout` | rewrites an existing table into its layout, one business day at a time |
| `tools/samplegen/layout.py` | the shared code: reads a pack's layout, promotes fields, sorts, cuts files and row groups, sets the statistics columns |

The bulk generator, for example:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades 1000000 --days 3
```

```
data/delta/trading/trade: 1,000,000 trades x 3 business days (2026-09-28 to 2026-09-30), 4 files a day, 4.85 GB, 82 s
```

It lists and sorts every id first, cuts the list into files, and builds each file's documents on all cores; the main
process writes one file at a time, so memory stays at about two files' rows (under 10 GB) whatever the size of the book.
It takes about 28 seconds per million trade-days on 24 cores.

**Your own loader** (Spark, Databricks, a Python job) writes the same thing. In Spark, for one business day:

```python
from pyspark.sql import functions as F

day = (spark.read.json(source)                                  # the day's documents
       .withColumn("doc", F.to_json(F.struct("*")))
       .select(F.col("tradeId").alias("id"), "doc", F.lit("2026-09-30").cast("date").alias("business_date"),
               "tradeId", "productType", "productName", "direction", "currency", "notional", "mtm", "pnl1d",
               "maturityDate", "tradeDate", "book", "desk", "status", "assetClass",
               F.col("counterparty.id").alias("counterparty__id"), F.col("counterparty.name").alias("counterparty__name"),
               "nettingSet", F.col("risk.dv01").alias("risk__dv01"), "sourceSystem"))

(day.repartitionByRange(4, "id").sortWithinPartitions("id")      # four files, each its own id range, sorted
    .write.format("delta").mode("overwrite")
    .option("replaceWhere", "business_date = '2026-09-30'")
    .option("parquet.block.size", 8 * 1024 * 1024)                # small row groups (about 1,000 trade documents)
    .partitionBy("business_date")
    .save("s3a://risk-lake/banking/trading/trade"))
```

and once, on the table:

```sql
ALTER TABLE delta.`s3a://risk-lake/banking/trading/trade` SET TBLPROPERTIES (
  'delta.dataSkippingStatsColumns' = 'id,business_date,tradeId,productType,productName,direction,currency,notional,mtm,
pnl1d,maturityDate,tradeDate,book,desk,status,assetClass,counterparty__id,counterparty__name,nettingSet,risk__dv01,sourceSystem');
```

What matters, whatever the tool: **the promoted columns hold exactly the document's values**, **each business day is
sorted by id and split into files of disjoint id ranges**, **row groups are small**, and **statistics leave out
`doc`**. Databricks users can let `OPTIMIZE … ZORDER BY (id)` or liquid clustering on `id` keep the order instead.

Numbers are written as `float64` and everything else as text: a column whose every value is a number is a number column;
anything else (including a column with no values yet) is text. Once a table exists, its column types stay as they are.

## 6. How the connector reads

The connector (`plugins/drishti-plugin-delta`) reads through Delta Kernel, the Delta project's Java library, without
Spark, and, by default, without Hadoop (the native engine, [section 16](#16-engines-native-and-hadoop)). Each read path below uses only what it needs.

### 6.1 The table's file list

Every `refresh-seconds` (10) the connector asks Kernel for the table's latest version and its file list, grouped by
business date. Kernel replays the log from the last checkpoint, so the cost depends on the commits since the
checkpoint, not on the table's age. With seven years of daily files (1,800 business days, 7,200 files, a checkpoint) the
list takes **84 ms**. The list also says whether any file carries a *deletion vector* (rows deleted without rewriting
the file); see [section 12](#12-limits-and-trade-offs).

### 6.2 The id map of a day

For each business day it is asked about, the connector builds an **id map**: every id of the day, sorted, each with the
index of the file it is in. It reads only the `id` column of the day's files (a narrow column of short strings), never a
document. A million ids take about 75 MB and well under a second to read. Id maps are kept by memory (`id-map-mb`,
1024), so about a dozen recent days stay; an older day is read again when asked for. A file that carries a deletion
vector is read through Kernel, which applies it: rows deleted in place are not in the id map, so type-ahead does not
offer them.

### 6.3 Opening one entity

`TRD MX-20000017` on 2026-09-30:

1. The file list gives the day's files; for a past date, the newest day on or before it within `lookback-days`.
2. The day's id map gives the file holding `MX-20000017` (a binary search).
3. That file is read with the predicate `id = 'MX-20000017'`. Parquet compares it with each row group's statistics and
   decodes only the one row group (1,000 rows) whose id range can contain it, and only the `id` and `doc` columns.
4. The document is parsed and kept in the document cache (`doc-cache-mb`, 256). Deletion vectors are honoured.

At most `max-concurrent-reads` (16) such reads run at once: each decodes about 7 MB of documents, so a burst of
thousands of reads (an impact analysis, a search falling back to documents) queues instead of exhausting memory.

| | Time |
|---|---|
| first read of a trade (id map of the day already loaded) | 137–157 ms |
| the same trade again (document cache) | 48 ms |
| a trade on a past business day, its id map read first | 288 ms |
| a trade on a 2019 date, in a seven-year table | 22 ms (small test rows) |

### 6.4 Type-ahead

Type-ahead must answer within 30 ms. The connector keeps the newest day's ids in Drishti's search index (`HitIndex`),
rebuilt from the newest day's id map every `6 × refresh-seconds`: no document is read. The index keeps ids sorted per
kind, so `TRD CLY-4083` is a binary search to the first id starting with `cly-4083`, then a walk; a search for every
trade (`TRD` alone) lists the first ids in order without sorting a million entries. Substring matches (`*0042`) scan,
stopping once the limit is reached. New entities from a live source are added to a small pending list and folded into
the sorted arrays in batches. Measured: **29 ms** over HTTP with a million ids.

When a kind's table log or newest day cannot be read at a rebuild, the kind keeps the ids it listed before (none, if
it never could), the failure is logged and shown in Health, and searches of the kind are reported partial with the
reason ("its list of trade entities could not be rebuilt (…); it lists those of 2026-10-01T…"); the next rebuild tries
again.

### 6.5 The column set of a day

The first search of a business day reads the day's **column set**: the id and every promoted column, nothing else.
Text values that repeat (a book, a desk, a currency) are shared, so a million rows of 19 columns take about 230 MB.
Column sets are kept by memory (`columns-cache-mb`, 1024): about four days. After each re-index the newest day's
column set is loaded in the background, so the day's first search finds it ready.

| | Time |
|---|---|
| the newest day's column set, loaded in the background after start | ready before users search |
| the first search on another business day (its column set is read) | 6.6 s |
| any search once the day's column set is loaded | 120–320 ms |

### 6.6 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" (the *Linked entities* panel, impact) is answered from the
promoted text columns of the day: every value equal to the id. No document is read. On a picked date, a counterparty's
netting sets and (F8) trades come this way too. For a table loaded without promoted columns, the lake indexes every
string value that looks like an identifier (capital letters and digits with at least one dash, such as
`CP-NORTHBRIDGE` or `NS-NORTH-01`, up to 64 characters). A table without promoted columns is
loaded whole for this only when its day has at most `max-load-rows` (200,000) rows; otherwise it reports no referrers,
rather than loading gigabytes.

## 7. Searches, pick lists, derived kinds and impact over columns

These run in Drishti's engine, not in the connector, through a small extension of the plugin interface: a source says
which fields it keeps as columns for a kind (`columnar`) and returns a day's values of some fields column by column
(`columns`). Delta and Aerospike implement it; any source can. The router asks the sources in their read order and takes
the first that holds the business day (so recent days can come from one store and older ones from another), and falls
back to documents when none can answer.

### 7.1 Structured search and pick lists

A search (`TRD where currency = 'USD' and notional > 500m order by notional desc limit 20`) or a pick list
(`TRD MX-200001`, `TRD book=BOOK-RATES-3`) is answered from columns when **every field it reads is promoted**: the
condition's fields, the ordering, and the result columns (the query's fields and the kind's pick-list `columns:`). Then:

1. The day's column set is fetched (cached).
2. The condition is evaluated for every row on a small object holding just the condition's fields, with the same
   Rachana-EL semantics as on documents (text compared ignoring case, ISO dates in order, missing values never match).
3. Matching rows are sorted by the ordering; the first `limit` become the result, their values taken from the columns.
4. `scanned` is the number of entities of the day and `partial` is `false`: the answer is exact over the whole book.

**Redaction is applied exactly as on documents.** Before reading, the engine builds a probe document holding every
field the query reads and passes it through the caller's role redaction; any field the role would see masked is masked
in the column values too, so a role that may not see `book` neither matches on it nor sees it.

If any field is not promoted, the search reads documents as before, up to `drishti.search.max-scan` (20,000), and says
`partial`.

### 7.2 Derived kinds

A derived kind (desk P&L: trades grouped by `desk`, `count`, `sum $.mtm` …, see [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds))
is computed from columns when all its expressions are plain paths that are promoted:

1. Rows are grouped by the key column directly (the `desk` value of each row).
2. Each aggregate runs over the column arrays: a sum over `mtm` is a loop over a `double[]`.
3. Only the members it lists get a small document, for its rows table: at most `max-members` (desk P&L: 250). The
   aggregates and `memberCount` count every member.

The result is kept per business day for `refresh` (60 s in the trading pack); after that the last result is served at
once and recomputed in the background, so no reader waits for a recomputation. The first computation runs 15 seconds
after start. Desk P&L of every desk from a million trades: **327 ms** the first time, **48 ms** after.

### 7.3 Impact (F8)

Impact of a netting set finds every trade referencing it (a reverse lookup over columns), then sums the kind's measure
(`mtm`) over all of them from the column set and follows the roll-up fields (`nettingSet`, `creditLimit`) that are
promoted. It lists at most 200 entities per group, with the count of the rest; the total covers every one. Impact of a
netting set with 143,982 trades: **0.7–0.9 s**.

## 8. Seven years of history

Nothing in the read paths grows with history:

- a read concerns one business day: its id map, its column set, one file's row group;
- the file list is read from the last checkpoint; 7,200 files list in 84 ms;
- the caches hold days by memory, not by count, and drop the least recently used.

What grows is the lake (about 1.6 GB a business day) and the log, which `maintain.py` checkpoints and keeps small
([section 10](#10-keeping-the-lake-in-shape)). A user moving to a date in 2019 waits once for that day's id map (about a
second) and, for a search, its column set (several seconds); after that the day is as fast as today.

Measured on a table of 1,800 business days and 7,200 files (small rows, to test the history itself): start-up including
the file list and the newest day's ids **729 ms**; a trade on 2019-12-05 **22 ms**; on 2022-06-15 **13 ms**; the file list
re-read after expiry **84 ms**; an old day's columns **13 ms**.

## 9. Memory

| Held in memory | Per million trades | Bounded by | Default |
|---|---|---|---|
| id map of a day | about 75 MB | `id-map-mb` | 1024 MB (about a dozen days) |
| column set of a day (19 columns) | about 230 MB | `columns-cache-mb` | 1024 MB (about four days) |
| documents read recently | 7 KB each | `doc-cache-mb` | 256 MB (about 35,000 documents) |
| type-ahead index (newest day) | about 150 MB | the newest day only | — |
| whole days of small tables | — | `cache-mb` | 512 MB |

Measured server heap in use with a million trades a day: **2–3.5 GB**. Give the server a heap of at least 8 GB
(`-Xmx8g`) for a million trades a day; raise `columns-cache-mb` if users move between many business days in a session.

## 10. Keeping the lake in shape

`tools/lake/maintain.py` keeps the lake bounded and laid out. Run it nightly (`--daemon`, or `--once` from cron) with
`deploy/lake-maintenance.yaml`:

```yaml
schedule:
  at: "02:30"
  zone: America/New_York
lakes:
  - root: ./data/delta                # or s3://risk-lake/banking with storage-options
    domains: ["*"]
    keep-business-days: 1800          # about seven years back from today; older business days are deleted (null keeps all)
    max-drop-share: 0.5               # deleting more of a table in one run needs --force-drop
    compact: true                     # laid-out tables: re-sort drifted days; others: merge small files
    target-file-mb: 128
    checkpoint: true                  # readers replay a short log
    vacuum-hours: 168                 # files unreferenced for seven days are removed; "known at" reaches back that far
```

See what it would do, changing nothing; the output is one line of JSON per table:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py \
    --config deploy/lake-maintenance.yaml --once --dry-run
```

```json
{"at": "2026-09-30T21:57:32-04:00", "event": "maintained", "dry_run": true, "table": "data/delta/civic/bill", "before": {"files": 10, "mb": 0.03}, "retention": {"cutoff": "2024-10-02", "would_remove_files": 0}, "vacuum": {"files": 0, "dry_run": true}, "after": {"files": 10, "mb": 0.03}}
```

Run it for real with `--once` (from cron), or keep it running with `--daemon` (every day at `schedule.at`). A table that
fails is logged as `"event": "failed"` and the rest go on. The job uses `s3://` URIs (Python), while the server reads
`s3a://` (Hadoop).

For a table whose pack declares a layout, `compact` does not run Delta's plain compaction (which merges files without
sorting them and would break the id ranges). It looks at each business day's files and rewrites, sorted, only the days
that drifted from the layout: an intraday load appended a small file whose ids overlap the others, a file exceeds
`file-rows`, a promoted column is missing. A day already in the layout is left untouched, so the nightly run touches
only what changed. It also keeps file statistics on the id, the date and the promoted columns only, so the log stays small over years of
daily files, and sets the statistics columns on the table.

`relayout` does the same on demand, for a table written another way:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py relayout --root data/delta --domain trading
```

**On Amazon S3** nothing maintains a Delta table by itself: S3 stores files and knows nothing of the log. Run
`maintain.py` against the `s3://` root (a small container, a cron host, AWS Batch), or let Databricks do it with
`OPTIMIZE … ZORDER BY (id)` and its retention settings. Never use S3 lifecycle rules on a Delta table: they delete files
the log still points to and break the table.

## 11. Measured results

**Measured at a million trades a day, by hand, on 2026-10-01.**

On 2026-10-01, a developer workstation (24 cores, server heap limit 15.6 GB), the trading pack's lake with 1,000,000
trades a day over three business days, times over HTTP from the same machine:

| Question | Before the layout | With the layout |
|---|---|---|
| type-ahead `TRD CLY-40834` | 33 ms | 29 ms |
| open a trade, first time | timed out | 137–157 ms |
| open a trade, cached | timed out | 48 ms |
| open a trade on a past business day | timed out | 288 ms |
| `TRD where mtm < -50m order by mtm` | partial (20,000 of 500,000 read), 4.9 s | 134 ms, exact over 1,000,000 (2,017 matches) |
| `TRD where currency = 'USD' and notional > 500m order by notional desc` | partial, 14 s | 322 ms (13,640 matches) |
| `TRD book=BOOK-RATES-3` | partial, 22 s | 123 ms (70,702 matches) |
| pick list `TRD END-1100` | error 500 | 41 ms (1,000 matches) |
| a search on another business day, first / again | — | 6.6 s / 306 ms |
| desk P&L of all desks / another desk | timed out | 327 ms / 48 ms |
| impact of a netting set (143,982 trades) | hung | 716–881 ms |
| server heap in use | 8 GB, then out of memory | 2–3.5 GB |

Writing: `bulk_trades.py` writes a million trades a day for three days in 82–125 s (about 28 s per million trade-days),
4.85–5.2 GB.

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, the default profile with the engine `native`. Medians of 25 requests; every search
exact (`partial: false`, `scanned` equal to the trades a day). The last column carries a straight-line fit (R² beside
it) to a million trades a day: **an extrapolation from the measured points, not a measurement**; "flat" means the
measure does not grow with the book. The million-trade measurement above is the real figure; where the line and it
differ, trust the measurement.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 5.6 | 8.0 | 6.3 | 0.11 | flat, about 7.4 ms |
| open a trade (view), first time (ms) | 5.0 | 4.4 | 5.1 | 0.14 | 11.3 ms (weak fit) |
| a trade's document, today (ms) | 14.6 | 12.0 | 12.6 | 0.48 | flat, about 13.3 ms |
| a trade's document, a past day (ms) | 8.8 | 8.8 | 17.0 | 0.89 | 237 ms |
| `TRD where mtm < -50m order by mtm` (ms) | 1.9 | 2.2 | 6.0 | 0.91 | 101 ms |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 2.5 | 4.5 | 7.7 | 0.98 | 142 ms |
| `TRD book=BOOK-RATES-3` (ms) | 1.8 | 4.5 | 7.2 | 0.98 | 131 ms |
| pick list `TRD END-1100` (ms) | 0.9 | 1.3 | 3.1 | 0.95 | 53.5 ms |
| desk P&L `DESK-RATES` (ms) | 3.2 | 4.7 | 4.9 | 0.77 | 49.7 ms |
| impact of `NS-SUMMIT-NY` (ms) | 14.0 | 16.4 | 17.3 | 0.73 | 78.5 ms |
| a search on another day, first (ms) | 33.3 | 103 | 156 | 0.96 | 3,049 ms |
| a search on another day, again (ms) | 1.7 | 2.3 | 10.4 | 0.92 | 219 ms |
| load (the whole script) (s) | 7.2 | 7.8 | 10.2 | 0.92 | 74.4 s |
| store size (MB) | 57.3 | 137 | 270 | 1.00 | 5,311 MB |
| server live heap after a full GC (MB) | 99.0 | 110 | 129 | 1.00 | 840 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 12. Limits and trade-offs

- **The first search on a business day that is not the newest waits for that day's columns** (6.6 s for a million
  trades). The newest day is loaded in the background; older days are loaded when first asked for and then kept while
  memory allows.
- **Only promoted fields are fast.** A search on any other field reads documents, is limited to 20,000 of them and says
  `partial`. Promote what people search by.
- **Deletion vectors switch columns off for a table.** A table whose files carry deletion vectors (rows deleted in place
  by Databricks or Spark) is still read correctly for single entities and type-ahead, but searches and reverse lookups
  read documents.
  Compaction (`OPTIMIZE`, or `maintain.py`) rewrites the files without them.
- **Effective-mode tables** (a row only when an entity changes, used for reference data) are not read as columns: each
  entity's latest row on or before a date would have to be resolved across days. They are small, so documents serve.
- **Time travel (*known at*)** reads the table as of an earlier version; id maps and single reads work as usual, built
  for that version.
- **Live overlays are not in column searches.** If a live source (a Kafka stream of today's trades) serves the same
  kind ahead of the lake, a view shows the live document, while a search over columns reads the lake's version of the
  day. With the stream off (the default) they agree.
- **Groups are listed, not dumped.** Impact lists 200 entities per group and derived kinds list `max-members` members;
  their totals and counts cover everything.

## 13. Diagnosing

**Admin → Health** shows `UP`, `DOWN: cannot reach <root>/<domain>` (the folder or bucket is not reachable) or
`DOWN: no Delta tables under <root>/<domain>` (reachable, but nothing with a `_delta_log`). There is nothing long-lived
to lose: each read goes to storage afresh, so reads recover as soon as the storage does. While an `s3a://` store is
unreachable, a read that is not cached ends with `DRS-1004 timed out reading …` (S3A retries for longer than
`fetch-timeout`) or `DRS-1003`. The connector lists the domain's tables when it starts and again at every reindex (six
`refresh-seconds`), unless `kinds` is given, so a table added while the server runs is served from the next reindex
with no restart; check with `curl -s $B/sources | jq '.sources[] | select(.name=="trading-store").kinds'`. For a
pack-declared layout the table does not have, says so:

```
trading-store   UP (engine: native; not laid out as the pack declares: trade (12 of 19 columns); searches read documents)
```

`GET /api/v1/admin/health` lists the connector's caches:

```json
"cache": {"engine": "native", "partitions": 0, "tables": 1, "idMaps": 2, "columnSets": 1, "documents": 3, "timeTravel": 0}
```

`timeTravel` counts *known at* versions held. `partitions` (whole days loaded) stays at 0 for a laid-out table; `idMaps` and `columnSets` count the days in memory.

**Was a search answered from columns?** Its JSON says `"scanned": 1000000, "partial": false` (every entity of the day)
when it was; a document search says `"partial": true` with a smaller `scanned`.

| Symptom | Likely cause | What to do |
|---|---|---|
| searches say `partial: true` | a field the query reads is not promoted, or the table is not laid out | check Health; add the field to `layout.<kind>.columns` and run `maintain.py relayout` |
| opening a trade takes seconds | row groups are large (written with defaults), or the day's id map is being read | rewrite with `row-group-rows: 1000` (`relayout --force`); the second open is fast |
| the first search of the morning is slow | the newest day changed and its columns are loading | it is loaded in the background after each re-index; wait a minute after a large load |
| Health: `not laid out as the pack declares` | the writer did not write the promoted columns | `maintain.py relayout --root … --domain …` |
| views say *No data available*; health `DOWN: no Delta tables under ./data/delta/trading` | the lake was not built, or `DRISHTI_DELTA_ROOT` points elsewhere | build it, or set `DRISHTI_DELTA_ROOT` and restart; tables built while the server runs are found within a minute |
| `domain escapes the Delta root` under `failedToStart` | `domain` contains `..` or starts with `/` | a plain folder name |
| an old picked date falls back to the samples | the date is more than `lookback-days` past the newest partition on or before it | load that date, or raise `lookback-days` |
| *known at* shows the latest data | the lake was copied without keeping file times (Delta resolves instants against `_delta_log` modification times) | copy with `cp -p` / `rsync -t`; for S3, times are upload times |
| S3: `DOWN: cannot reach s3a://…` | credentials, region or endpoint | try `aws s3 ls s3://<bucket>/<path>/` with the same credentials |
| `DRS-1004 timed out` on a single read | the read deadline (2 s) passed: very large row groups, or object storage far away | smaller row groups; check `p99Ms` in Health; `drishti.sources.fetch-timeout` |
| the log directory grows by megabytes a day | statistics include the document | `maintain.py` sets `delta.dataSkippingStatsColumns`; or set it yourself |
| Health: `DEGRADED: cannot read trade 2026-09-30: …` | the last read of that table and date failed: pages in a codec the engine does not decompress (BROTLI, LZO), a page that does not decode, a truncated or missing Parquet file, or (`trade log`) a log missing a commit | the reason says which; codecs: [section 16](#16-engines-native-and-hadoop); a damaged file: restore it or rewrite the date. Health turns `UP` once a read of the date succeeds or the table gets a new version |
| a view fails with `DRS-1003 … failed reading` while other dates open | that date's files cannot be read (see the row above); the view does not fall back to another store's data | as above |
| searches say `partial: true` with `failed: [{"source": "trading-store", …}]` | a date or the table's ids could not be read; the type-ahead keeps the ids it listed before | as above; the reason is in the answer and in Health |

## Security: TLS and credentials

A lake on an S3-compatible store (MinIO, Ceph, an internal gateway) with a certificate from a **private CA** is configured on the
connector with the shared `tls.*` settings ([TLS.md](TLS.md)). They are read by the **native engine** (the default,
[section 16](#16-engines-native-and-hadoop)), which reaches S3 through the AWS SDK:

| Setting | Meaning |
|---|---|
| `s3.endpoint: https://minio.example.com:9000` | TLS on. `tls.*` against a missing or `http://` endpoint is a start-up error (`tls.* is set but s3.endpoint is not https://: set s3.endpoint to the store's https:// address`). |
| `tls.ca-file` / `tls.truststore` (+ `tls.truststore-password`) | Trust a private CA (add `tls.trust-jvm-default: true` to keep the public ones too). |
| `tls.cert-file` + `tls.key-file` (+ `tls.key-password`), or `tls.keystore` (+ `tls.keystore-password`) | A client certificate, for a gateway that asks for one. |
| `tls.verify-hostname: false` | The chain is verified, the name is not (a warning at every start). |
| `tls.protocols`, `tls.cipher-suites` | Handed to the HTTP client. |
| `s3.access-key`, `s3.secret-key` | As before: `${ENV}` placeholders or `file:` references, or the AWS credential chain. |

With `engine: hadoop` (Hadoop's S3A) a private CA cannot be given per connector: S3A has no setting for one. `tls.*` with that engine is
refused at start (`tls.* is read by the native engine only: Hadoop's S3A has no setting for a private CA. Set engine: native, or
import the CA into the JVM's truststore (-Djavax.net.ssl.trustStore=...)`); an `https://` endpoint with a public certificate works
with either engine.

### A complete file

`config/connectors/delta-private-ca.yaml`:

```yaml
plugin: delta
kinds: [customer]
description: Customer tables in the data lake over TLS
settings:
  root: s3://lake-prod/delta
  domain: customer
  s3.endpoint: https://minio.example.com:9000
  s3.region: us-east-1
  s3.access-key: ${LAKE_ACCESS_KEY}
  s3.secret-key: ${LAKE_SECRET_KEY}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
```

### Checking it

```
curl --cacert /etc/drishti/tls/ca.pem https://minio.example.com:9000/minio/health/live
openssl s_client -connect minio.example.com:9000 -CAfile /etc/drishti/tls/ca.pem -verify_return_error < /dev/null
```

Health is `UP (engine: native)`; once a certificate has under 30 days left it reads
`UP (engine: native; TLS certificate CN=drishti (tls.cert-file) expires in 19 days (2026-10-30))`. The integration test of the S3
reader (`S3StorageTlsTest`) runs the engine's S3 storage against an HTTPS endpoint that requires a client certificate, and shows the
refusals: an untrusted server (`PKIX path building failed ... unable to find valid certification path to requested target`),
a missing client certificate, and a host name the certificate does not carry.

### Common errors

| Message | Cause and fix |
|---|---|
| `tls.* is set but s3.endpoint is not https://: set s3.endpoint to the store's https:// address` | `tls.*` with no endpoint or an `http://` one. |
| `tls.enabled is true but s3.endpoint is not https:// (not set)` | The same, with `tls.enabled`. |
| `tls.* is read by the native engine only: ...` | `engine: hadoop` with `tls.*`. |
| `tls.ca-file '/etc/drishti/nope.pem': file not found` | A wrong path. |
| `PKIX path building failed: ... unable to find valid certification path to requested target` in the table's problem text | The store's certificate is not signed by a CA in `tls.ca-file`. |

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/trading-store.yaml
plugin: delta
enabled: ${DRISHTI_LAKE_ENABLED:true}
kinds: [trade]
description: Trading lake
settings:
  root: ${DRISHTI_DELTA_ROOT:./data/delta}
  domain: trading
  layout:
    trade:
      columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                risk.dv01, sourceSystem]
      sort-by: id
      file-rows: '250000'
      row-group-rows: '1000'
```

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). A Delta Lake on S3-compatible storage, with the access keys as references, is `config/connectors.examples/other/customer-lake.yaml` (see [`config/connectors.examples/`](../../config/connectors.examples)).

## 14. Settings

In the connector file's `settings:` (see [As a connector file](#as-a-connector-file)):

| Setting | Default | Meaning |
|---|---|---|
| `root` | `./data/delta` | the lake: a local folder or `s3a://bucket/path` |
| `engine` | `DRISHTI_DELTA_ENGINE`, else `native` | `native` (no Hadoop; Windows), `hadoop` (also `abfs://`, `gs://`) or `auto` ([section 16](#16-engines-native-and-hadoop)) |
| `lz4-decoder` | `safe` | LZ4 page decoder: `safe` (pure Java) or `fast` (JNI when available) ([section 16](#16-engines-native-and-hadoop)) |
| `lz4-via-native` | `true` | `hadoop` engine only: read LZ4 files through the native decoder |
| `domain` | empty | the data-domain folder under the root; may not contain `..` or start with `/` |
| `kinds` | every table found | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` (every entity every day) or `effective` (a row when an entity changes) |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | promoted paths ([section 4](#4-declaring-the-layout-in-a-pack)) |
| `layout.<kind>.sort-by`, `file-rows`, `row-group-rows` | `id`, `250000`, `10000` (the trading pack: `1000`) | how writers lay the table out |
| `refresh-seconds` | `10` | how often the table's latest version is checked; the type-ahead index is rebuilt every six |
| `warm-dates` | `3` | after start, in the background, each table's newest N dates get their id map and one document read, so the first read of a recent past date is not the slow one (cold about 5x a warm read: 40-70 ms against 10 ms on the samples, more on a large day); `0` turns it off |
| `id-map-mb` | `1024` | memory for days' id maps |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `doc-cache-mb` | `256` | memory for recently read documents |
| `max-concurrent-reads` | `16` | single-document reads at once |
| `max-load-rows` | `200000` | the largest day loaded whole for reverse lookups when a table has no promoted columns |
| `cache-mb` | `512` | memory for whole days of small tables |
| `id-column`, `doc-column`, `date-column` | `id`, `doc`, `business_date` | column names |
| `source-name` | the connector's name | the name shown in provenance and Health |
| `stale-after` | none | warn when no new data arrived for this long |
| `s3.region` | none | the S3 region |
| `s3.endpoint` | none | an S3-compatible store (MinIO, Ceph); also sets path-style access and TLS by scheme |
| `s3.path-style` | `true` | path-style addressing, only with `s3.endpoint` |
| `s3.access-key`, `s3.secret-key` | none | static credentials; otherwise the AWS chain |
| `hadoop.<key>` | none | passed to Hadoop as `<key>` (see [CONFIGURATION.md](../admin/CONFIGURATION.md#delta--delta-lake)) |

## 15. Checklist for production

1. Declare `layout.<kind>.columns` for every kind with more than a few hundred thousand entities a day, listing the
   fields of its pick-list columns, searches, derived kinds, impact measures and links.
2. Make your loader write the layout ([section 5](#5-writing-the-lake)) and set `delta.dataSkippingStatsColumns`.
3. Check Admin → Health: no `not laid out` message.
4. Run `maintain.py` nightly with `keep-business-days: 1800`, `compact: true`, `checkpoint: true`.
5. Give the server at least 8 GB of heap per million entities a day.
6. Measure on your data: `GET /api/v1/search?q=…` reports `elapsedMs`, `scanned` and `partial`; a view reports its
   `timings`.

## 16. Engines: native and Hadoop

Delta Kernel does no I/O itself: an *engine* lists the log, reads the commit JSON and the Parquet files, and evaluates
expressions. The connector has two, chosen by its `engine` setting (or `DRISHTI_DELTA_ENGINE` for every connector):

| `engine` | What reads the lake | Reads | Use it for |
|---|---|---|---|
| `native` (default) | `NativeEngine` (module `drishti-deltalake`): `java.nio` for local disk, the AWS SDK v2 for S3, parquet-java over its own input files | local paths and `file:` URIs (Windows drive letters, backslashes, UNC shares), `s3://`, `s3a://` | everything, and Windows, where Hadoop's local file system needs `winutils.exe` |
| `hadoop` | Kernel's default engine over Hadoop's `FileSystem` | the above (S3 through S3A), plus `abfs://`, `gs://`, HDFS | lakes in Azure, Google Cloud Storage or HDFS |
| `auto` | `native` on Windows, `hadoop` elsewhere | | a configuration shared by Windows and Linux machines that wants Hadoop on Linux |

**What the native engine is.** It implements Kernel's `Engine` with its own file-system client (sorted listings,
path resolution, byte ranges), Kernel's own JSON handler and expression evaluator (their classes use no Hadoop), and
its own Parquet handler: the footer is read once, the predicate Kernel passes (`id = X`) prunes row groups by their
statistics, only the asked columns are decoded (by Kernel's own column readers), and pages are decompressed by its own
codecs (Snappy, ZSTD, GZIP, LZ4 and LZ4_RAW; delta-rs writes Snappy). No Hadoop `FileSystem`, `Shell` (which looks for `winutils.exe`)
or `Configuration` is loaded: a test runs the whole connector in a class loader that refuses them. Hadoop's jars stay
on the class path only because parquet-java names a few of its types in method signatures. The engine only reads;
Drishti's loaders write with delta-rs.

**What both read the same.** Checkpoints, time travel (*known at*), deletion vectors (Kernel reads them through the
engine's byte ranges), partition values, the single-row-group reads of [section 6.3](#63-opening-one-entity), id maps
and column sets. Every Delta test runs on both engines, and a test compares their answers on the fixture lakes.

**Speed.** On a lake of 10,000 trades a day over three days (`tools/load-delta.sh <root> --trades 10000 --days 3`,
one file a day, row groups of 1,000), a cold JVM, the two engines, two runs each (2026-10-01, a 24-core workstation):

| | native | hadoop |
|---|---|---|
| the table's file list, first / again | 204–210 ms / 8 ms | 442–574 ms / 9–11 ms |
| a day's id map (10,000 ids), cold / again | 225–228 ms / 15–16 ms | 190–285 ms / 14–25 ms |
| a day's 15 promoted columns, cold / again | 58–61 ms / 40–45 ms | 53–67 ms / 32–34 ms |
| one trade (one row group), first / warm median / p95 | 20 ms / 4.3–4.5 ms / 8.2–8.4 ms | 17–18 ms / 5.5–5.7 ms / 9.4–10.3 ms |
| over HTTP: a trade on a past date, cold / again | 43 ms / 6 ms | 116 ms / 11 ms |
| over HTTP: `TRD where mtm < -50m` on a past date, cold / again | 43 ms / 9 ms | 75 ms / 14 ms |

The two are the same within noise for reads; the native engine starts the first read of a table faster (no Hadoop
`FileSystem` to start).

**S3.** Both engines take the same `s3.*` settings. The native engine uses one pooled AWS client per connector,
lists with `ListObjectsV2`, and reads with ranged GETs: a small read (a footer, a deletion vector) fetches at least
`s3.read-block-kb` (1 MiB) and serves the next small reads from it; a column chunk is one GET of its range. Checksums
are only those the store requires, so MinIO, Ceph and S3Mock work.

**Limits.** The native engine reads local disk and S3 only; a connector with `engine: native` and an `abfs://` or
`gs://` root refuses to start with a message saying to use `hadoop`.

**Parquet codecs, per engine.**

| Codec in the Parquet footer | `native` | `hadoop` |
|---|---|---|
| UNCOMPRESSED, SNAPPY (delta-rs's default), GZIP, ZSTD | read | read |
| LZ4_RAW (one LZ4 block per page; current Arrow, pyarrow, delta-rs `"LZ4_RAW"`) | read | read |
| LZ4, the deprecated codec (Hadoop-framed pages, or a raw block as older Arrow and parquet-cpp wrote it) | read | read, through the native decoder (below) |
| BROTLI, LZO | refused, naming the table, file and codec | refused by Hadoop's codec loader (no Hadoop codec class for them) |

LZ4 under the deprecated codec name is ambiguous in the wild, so the native decoder does what Arrow does: it first
tries Hadoop's framing (`[4-byte big-endian uncompressed length][4-byte big-endian compressed length][block]`, one or
more blocks per frame) and accepts it only when the lengths add up exactly to the page's sizes; otherwise it decodes the
page as a single raw LZ4 block. A page that is neither is reported as `a LZ4 Parquet page cannot be decoded: …` with the
table, date and file. The decoder is lz4-java's safe (pure Java) one; set `lz4-decoder: fast` on the connector to use
the fastest the platform has (JNI when its library loads, else unsafe Java). Decoders keep no state, so any number of
reads share them.

**LZ4 on the `hadoop` engine.** parquet-java's reader, as Delta Kernel builds it, loads Hadoop's own LZ4 codec by class
name (`org.apache.hadoop.io.compress.Lz4Codec`) and offers no way to plug another decoder in, and that codec fails on
raw blocks (`LZ4Exception: Malformed input`). So the `hadoop` engine does not decode LZ4 itself: for each data file on
local disk or S3 it reads the footer once (remembered per file, as Parquet files never change), and files that use the
`LZ4` codec are read by the native engine's decoder, the rest by Hadoop. The result is the same rows, in the same order.
This costs one extra footer read per file the first time it is read. `lz4-via-native: false` turns the routing off.
Files on `abfs://`, `gs://` and `hdfs://` are not routed: there, rewrite LZ4 files as Snappy, ZSTD or LZ4_RAW.

Rewriting a date as Snappy stays available and is optional (for other readers of the lake that cannot read LZ4, or
for `abfs://`/`gs://` lakes):

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py relayout --root data/delta \
    --domain trading --kind trade --dates 2026-09-30 --force
```

A table the connector cannot read (BROTLI or LZO pages, a corrupt LZ4 page, a truncated file) fails reads of that date
with `DRS-1003 trading-store failed reading trade/MX-1: trade 2026-09-30 cannot be read: the native Delta engine does
not decompress BROTLI Parquet pages …; rewrite the date with Snappy or ZSTD (tools/lake/maintain.py relayout --force
--dates <date>) [file …]`, searches over the date say `partial: true` and name the connector and reason, the type-ahead
keeps the ids it listed before, and **Admin → Health** shows the connector `DEGRADED: cannot read trade 2026-09-30: …`;
an LZ4 table is `UP` and lists and searches like any other.
**Iceberg** is not part of this: its
connector reads through Hadoop, so it is not supported on Windows (it is off unless the `iceberg` profile is used).

---

## Appendix: walk-through and worked examples

Moved here from the former connector guides, so that everything about this connector is in one document.

### Walk-through, step by step

Your batch jobs (Spark, Databricks, Python `deltalake`) write the end-of-day data of each domain to Delta Lake tables:
years of business dates. This is Drishti's default store: every banking pack reads one domain of `./data/delta`.

1. **Know the data.** One table per kind under `<root>/<domain>/<kind>/`, partitioned by `business_date`, with the
   columns `id`, `doc` and the promoted fields ([section 3](#3-what-is-on-disk)). Writing a day from Python (the same
   calls `tools/samplegen/lake.py` uses): append a new date, or replace one date that was restated:

```python
import json, datetime, pyarrow as pa
from deltalake import write_deltalake

day = datetime.date(2026, 9, 30)
docs = {"MX-20000001": {"tradeId": "MX-20000001", "mtm": 1875863, "businessDate": day.isoformat()}}
table = pa.table({"id": pa.array(list(docs), pa.string()),
                  "doc": pa.array([json.dumps(d) for d in docs.values()], pa.string()),
                  "business_date": pa.array([day] * len(docs), pa.date32())})
write_deltalake("data/delta/trading/trade", table, mode="overwrite",
                partition_by=["business_date"], predicate=f"business_date = '{day.isoformat()}'")
```

   The `predicate` replaces only that date, so the table keeps its history, and the replaced version stays readable
   through *known at* until it is vacuumed. Without a layout everything still works, but a day is read whole, and a
   large day exceeds the read deadline; for millions of entities a day declare one ([section 4](#4-declaring-the-layout-in-a-pack),
   [section 5](#5-writing-the-lake)).
2. **Configure it**, with one of the forms under [Configuration by example](#configuration-by-example).
3. **Load sample data.**

```bash
# every banking domain (reference, market, trading, risk, credit, collateral), ten business days
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta --days 10
# or one pack's samples into one domain
uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/lake.py \
    --samples packs/finance/samples --root data/delta --domain finance --days 10 [--as-of 2026-09-30] [--calendar USNY]
DRISHTI_PACKS=counterparty-risk,market-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

   What `lake.py` writes is described in [section 5](#5-writing-the-lake). For S3, build locally and copy the folders up
   (`aws s3 sync data/delta/trading s3://risk-lake/banking/trading`), bearing in mind that time travel then resolves
   against the upload times.
4. **In the terminal**: `TRD MX-20000001 <GO>`; pick 29 September in the top bar and the numbers change; *Raw JSON* (F9)
   shows `businessDate`. `CPTY CP-NORTHBRIDGE <GO>` on a picked date lists its netting sets (*Netting sets* panel) and F8
   (impact) its trades: the reverse lookups of [section 6.6](#66-reverse-lookups).
5. **Keep the lake bounded** with `tools/lake/maintain.py` ([section 10](#10-keeping-the-lake-in-shape)); Drishti only
   reads the lake. A shorter window suits a smaller site: `keep-business-days: 520` is about two years.
6. **When it goes wrong**: health, the store going down and the common errors are in [section 13](#13-diagnosing).

### Configuration by example

**The pack's suggested template**, as shipped in `packs/trading/pack.yaml` (the layout keys are in [section 4](#4-declaring-the-layout-in-a-pack)); the file `config/connectors/trading-store.yaml` has the same settings:

```yaml
connectors:
  trading-store:                          # routes, health and provenance use this name
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true} # one switch for every pack's lake
    kinds:
    - trade                               # serve only trades from this domain
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}   # one variable moves every pack's lake
      domain: trading                     # tables live under <root>/trading/<kind>/
routes:
  trade: trading-store                    # trades are asked of the lake first
```

Reference data that changes rarely is `effective` (from `packs/banking-core/pack.yaml`):

```yaml
connectors:
  reference-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [counterparty, counterparty-group, issuer, agreement, ccp, legal-entity, book, desk, trader, calendar, csa, clearing-account]
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: reference
      mode.counterparty: effective        # a row only when the counterparty changes
      mode.book: effective
      # ... one mode.<kind> line per kind
```

`packs/finance/pack.yaml` declares its lake without `kinds`, so it serves every table it finds:
`finance-lake: { plugin: delta, enabled: ${DRISHTI_LAKE_ENABLED:true}, settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: finance, lookback-days: 10 } }`.

**Site form**, your own domain, as the connector file `config/connectors/treasury-lake.yaml`:

```yaml
plugin: delta
kinds: [funding-source, hqla-holding]    # what this connector serves
settings:
  root: /srv/lake                        # local folder (or s3a://bucket/path, below)
  domain: treasury                       # /srv/lake/treasury/<kind>/
  mode.funding-source: effective
  lookback-days: '10'                    # snapshot kinds
  refresh-seconds: '10'                  # how long a table's latest version is cached
  cache-mb: '512'                        # partitions kept in memory, by size
```

**The lake in S3 or an S3-compatible store** (MinIO, Ceph). The site moves a pack's lake by writing the connector file of the same
name; the settings are in [section 14](#14-settings). The file replaces the pack's template wholesale, so it repeats `plugin`, `kinds`
and `domain`:

```yaml
# config/connectors/trading-store.yaml
plugin: delta
kinds: [trade]
settings:
  domain: trading
  root: s3a://risk-lake/banking            # read through Hadoop's S3A (or the native engine)
  s3.region: us-east-1
  # credentials: the AWS chain (environment, profile, instance role), or
  # s3.access-key: ${LAKE_ACCESS_KEY}
  # s3.secret-key: ${LAKE_SECRET_KEY}      # or file:/run/secrets/lake-secret-key
  # an S3-compatible store:
  # s3.endpoint: https://minio.bank.example # path-style; TLS when https
  # s3.path-style: "false"                  # only with an endpoint; default true
  # hadoop.fs.s3a.connection.maximum: "200" # any Hadoop setting, prefixed hadoop.
```

The tables are read at `s3a://risk-lake/banking/trading/<kind>`.
`DRISHTI_DELTA_ROOT=s3a://risk-lake/banking` moves every pack's lake at once.

**Maintenance** for a smaller window, as in `deploy/lake-maintenance.yaml` ([section 10](#10-keeping-the-lake-in-shape)):

```yaml
schedule: { at: "02:30", zone: America/New_York }
lakes:
  - root: ./data/delta
    domains: ["*"]
    keep-business-days: 520      # about two years; older partitions are deleted (null keeps all)
    max-drop-share: 0.5
    compact: true
    checkpoint: true
    vacuum-hours: 168
```
