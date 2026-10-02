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
# The MongoDB connector: a document per entity per day, read as columns

This document explains how Drishti serves dated entities from MongoDB, designed for a book of **1,000,000 trades every
business day** kept for years: how the collections are laid out, what the connector reads for each question, the loader,
retention, sizing, memory, measured results and limits.

Read it if you run Drishti on MongoDB, load MongoDB for Drishti, or change the connector. The same design for Delta Lake
is in [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md) and for Aerospike in [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md);
PostgreSQL is in [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md), dated folders in [FILE_CONNECTOR.md](FILE_CONNECTOR.md)
and the banking sample data in [DEMO_DATA.md](DEMO_DATA.md). Every setting is listed in
[CONFIGURATION.md](../admin/CONFIGURATION.md) and in [section 12](#12-settings). The engine side (columns, searches,
derived kinds, impact) is shared by all of them and explained in
[DELTA_CONNECTOR.md, section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns).

**About the numbers.** The measurements in this document were taken with **10,000 trades a day** over three business
days (plus the banking samples), on a MongoDB container capped at 2 GB of memory; the scale benchmark added 25,000 and
50,000 ([section 9](#9-measured-results)). Figures for a million trades a day are **estimates**, scaled from the
measured size per document and per index entry, or **extrapolations** from the benchmark; they are labelled as such.

## Contents

1. [The problem](#1-the-problem)
2. [The collection layout](#2-the-collection-layout)
3. [Declaring which fields are columns](#3-declaring-which-fields-are-columns)
4. [Loading](#4-loading)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Retention: deleting old days, or TTL](#6-retention-deleting-old-days-or-ttl)
7. [Seven years: sizing, replica sets, sharding, and MongoDB with Delta Lake](#7-seven-years-sizing-replica-sets-sharding-and-mongodb-with-delta-lake)
8. [Memory in the Drishti server](#8-memory-in-the-drishti-server)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)

---

## 1. The problem

A dated source must answer four kinds of question, at very different scales:

| Question | Reads | At a million trades a day |
|---|---|---|
| open one trade on a date | one document | must be one indexed read, whatever the history |
| type-ahead on ids | every id of a kind | a million ids, in memory, refreshed in the background |
| a search, pick list, derived kind or impact over a day | a few fields of every trade of the day | a million rows, never a million whole documents |
| reverse lookup ("trades in netting set X") | one link field of every trade of the day | as above |

The obvious MongoDB layout, one document per trade with an array of daily versions, fails the same way Aerospike's first
layout did: a document is at most 16 MB, so a 7 KB trade reaches the limit after about 2,300 days, and adding a day
rewrites the whole document. A collection per business day avoids that, but then every question about "the newest day
on or before a date" needs the list of collections, and indexes multiply. The layout below keeps one collection per
data domain and lets two small indexes answer everything.

## 2. The collection layout

A data domain (`trading`) is two collections in the database (`drishti` by default):

| Collection | Document | `_id` | Fields |
|---|---|---|---|
| `trading` | one per entity per business day | `trade/MX-20000017/20260930` | `kind`, `id`, `date`, `doc`, `c` (the promoted fields) |
| `trading_columns` | the same keys, only for entities with promoted fields | `trade/MX-20000017/20260930` | `kind`, `id`, `date`, `c` |

A real document as loaded (the document text shortened):

```
db.trading.findOne({_id: "trade/BBG-60000001/20260917"})
{
  _id: 'trade/BBG-60000001/20260917',
  kind: 'trade',
  id: 'BBG-60000001',
  date: 20260917,                                   the business day as a number (yyyyMMdd)
  doc: '{"tradeId":"BBG-60000001","sourceSystem":"Bloomberg TOMS",…}',   the JSON document, as text
  c: {                                              the promoted fields: numbers as doubles, the rest as text
    tradeId: 'BBG-60000001',
    productType: 'GOVT_BOND',
    productName: 'Government bond',
    direction: 'Short',
    currency: 'CHF',
    notional: 198000000,
    mtm: -1072969,
    pnl1d: -23374,
    maturityDate: '2038-04-30',
    tradeDate: '2026-08-31',
    book: 'BOOK-FI-3',
    desk: 'DESK-FI',
    status: 'Live',
    assetClass: 'Fixed income',
    counterparty__id: 'CP-SUMMIT',                  counterparty.id: dots become two underscores
    counterparty__name: 'Summit Clearing LLC',
    nettingSet: 'NS-SUMMIT-NY',
    risk__dv01: -178835,
    sourceSystem: 'Bloomberg TOMS'
  }
}
```

With `--ttl-days` the loader adds `expireAt` (a date) to both documents ([section 6](#6-retention-deleting-old-days-or-ttl)).

**Indexes.** Each collection has two, and nothing else is needed:

| Index | Keys | Serves |
|---|---|---|
| `_id_` | `_id` | a snapshot read (the exact key `kind/id/yyyyMMdd`); an effective read (the last key of the range `kind/id/` … `kind/id/yyyyMMdd`) |
| `day_ids` | `{kind: 1, date: 1, id: 1}` | a kind's business dates (a distinct scan); a day's ids (a covered query); a day's columns in id order, split into id ranges |

Why each part is shaped this way:

- **A document per entity per day.** Each document is one version (about 7 KB) whatever the history; adding a day
  inserts only that day's documents; removing a day removes only its documents.
- **The key carries the date.** Keys sort as `kind/id/yyyyMMdd`, so all of one entity's days are adjacent in the `_id`
  index and the newest one on or before a date is the last key of a short range. An index on `{kind, id, date}` would
  answer the same question and cost another index entry per document; it is not created. (A query also checks
  `id`, so an id containing `/` cannot be confused with another.)
- **`day_ids` ends with `id`.** The ids of a day come from the index alone (no document is fetched), already sorted, which
  is what type-ahead needs and what lets a day be split into id ranges read at once.
- **The date as a number** makes "the dates of a kind" a distinct scan of `day_ids`, and a day an equality match.
- **The `_columns` collection.** MongoDB reads whole documents from storage even when a query projects two fields. A
  day's promoted fields read from `trading` therefore decompress 7 KB per trade; from `trading_columns` about 600 bytes.
  Measured on the 10,000-trade day: mongosh reads the day's `id` and `c` in 100 ms from `trading` and 72 ms from
  `trading_columns`; during development, on a three-million-document collection, a day of a million trades took 5.4 s of
  server time from `trading` and 1.1 s from `trading_columns` (single query), and through the connector 1.5–2.6 s against
  0.4–0.8 s. The cost is a second small write per row and about 7% more disk. The documents keep `c` as well, so an
  operator can query `trading` alone (`db.trading.find({kind: "trade", date: 20260930, "c.mtm": {$lt: -5e7}})`).
- **zstd block compression** (`block_compressor=zstd`), set by the loader when it creates a collection. Measured: the
  10,000-trade sample stores 6,875 bytes a document uncompressed and about 1,050 bytes on disk.

**The document as text, not as an embedded document.** The loader can store `doc` either way (`--doc-format string`, the
default, or `bson`), and the connector reads both. Measured on 100,000 trades during development (20,000 point reads by
`_id`):

| | point read, including the JSON the server parses | of which BSON → JSON | storage on disk | load |
|---|---|---|---|---|
| `string` (default) | 43–82 µs | 0.6–2 µs | 102 MB | 2 s |
| `bson` | 113–163 µs | 65–85 µs | 97 MB | 3 s |

Drishti needs JSON; an embedded document must be written out as JSON on every read (relaxed extended JSON), which more
than doubles the read time and turns integers and dates into BSON types and back. Embedded documents are 5% smaller and
can be queried inside MongoDB; choose `bson` only when operators query fields that are not promoted.

## 3. Declaring which fields are columns

The pack declares the fields to promote on the connector that stores the kind, as for Delta Lake (see
[DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack) for how to choose them). The trading
pack declares them on `trading-store`; the `mongodb` profile (`SPRING_PROFILES_ACTIVE=mongodb`) switches that connector
to the MongoDB plugin and keeps the pack's settings:

```yaml
# packs/trading/pack.yaml (generated)
connectors:
  trading-store:
    settings:
      domain: trading
      layout:
        trade:
          columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                    tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                    risk.dv01, sourceSystem]

# drishti-server application-mongodb.yaml (the profile)
drishti:
  sources:
    connectors:
      trading-store: { plugin: mongodb, settings: { uri: "${DRISHTI_MONGODB_URI:mongodb://localhost:27017}",
                                                     database: "${DRISHTI_MONGODB_DATABASE:drishti}" } }
```

The connector reads `layout.<kind>.columns`; the collection is the connector's `collection` setting, else its `domain`
(`trading`). A path becomes a field of `c` with its dots as two underscores (`MongoLayout.field`). Numbers are stored as
doubles and read as numeric columns; everything else is text. The `sort-by`, `file-rows` and `row-group-rows` keys of the
layout concern files and do not apply.

## 4. Loading

`tools/load-mongodb.sh` loads the banking packs' samples, and optionally a generated book, through `MongoLoader` (in the
plugin):

```bash
tools/load-mongodb.sh mongodb://localhost:27017 drishti                            # the samples: 1,791 documents x 10 days
tools/load-mongodb.sh mongodb://localhost:27017 drishti --trades 10000 --days 3    # and a book of trades, streamed
tools/load-mongodb.sh mongodb://localhost:27017 drishti --keep-days 90             # then delete days beyond the 90 newest
tools/load-mongodb.sh mongodb://localhost:27017 drishti --ttl-days 130             # documents expire 130 days after their date
```

```
mongodb: loaded 17,910 rows into database drishti in 1 s
mongodb: loaded 30,000 rows into database drishti in 3 s
```

How the loader works:

- **Its input is JSON lines**, one per entity per day: `{"domain", "kind", "id", "date", "doc", "columns": {path:
  value}}`, as `make_data.py --jsonl` and `bulk_trades.py --jsonl -` write them (the same input as the Aerospike loader).
  `-` reads standard input, so a generated book is piped in and never written to a file.
- **Each line becomes a replace-or-insert by `_id`** in the domain's collection and, when it has promoted fields, another in
  `<domain>_columns`. Writes go in unordered bulk writes of `--batch` (1,000) operations, at most `--in-flight` (16)
  batches at once on virtual threads; the connection pool is sized above that.
- **Idempotent, and merging**: loading the same rows again replaces each document with itself; a restated entity
  replaces its document of that day. Nothing is deleted: an entity the stream no longer carries keeps its document of
  the day (delete it, or let retention take the day). A load killed half way leaves a day it reloads partly new and
  partly as it was: load the day again to finish it.
- **A new day is not shown half written**: a (kind, day) the collection does not hold yet is recorded in
  `<domain>_loading` before its first document, and the record goes when the load has written every document; the
  connector leaves such days out of its catalogue. A day a killed load began stays hidden until a load of it finishes.
- **Dates are guarded**: a row dated after tomorrow in the business zone is not loaded; the first few are named on
  standard error and the load ends with an error once the other rows are in.
- **Collections are prepared on first use**: created with zstd compression when absent, with the `day_ids` index (and the
  TTL index with `--ttl-days`). Creating an index that exists does nothing.
- **Throughput**: 30,000 trade-days in 3 s (10,000 a second, the generator included); during development 100,000 trade
  documents from a file loaded in 2 s. A million-trade day is **estimated** at one to two minutes on one node, bounded by
  the generator or the source of the rows rather than by the writes.

**Your own writer** (a Kafka consumer, a batch job) writes the same documents: `MongoLayout.record(...)` builds the
domain document and `MongoLayout.columnsRecord(record)` its narrow copy; `MongoLayout.prepare(db, collection, ttl)`
creates the collections and indexes. Any language can write them: the shape is in [section 2](#2-the-collection-layout).

## 5. How the connector reads

### 5.1 The catalogue

Every `refresh-seconds` (60), and at start, the connector reads:

- the kinds: `distinct("kind")` on the domain collection (or the `kinds` setting);
- each kind's business dates: `distinct("date", {kind})`, a scan of the `day_ids` index (no document);
- the ids for type-ahead: for a snapshot kind, the newest day's ids by a covered query on `day_ids` (sorted, no
  document); for an effective kind, the ids in its `_id` range (`kind/` … `kind0`), a covered read of the `_id` index;
- which collections exist (`<collection>_columns` or not) and whether the `day_ids` indexes exist.

The ids replace the type-ahead index (Drishti's sorted `HitIndex`) in one step; every `read-threads`-th id becomes a
boundary for splitting a day into ranges ([section 5.4](#54-a-days-columns)). Then the newest day's columns of each
promoted kind are read in the background.

### 5.2 Opening one entity

`TRD MX-20000017` on 2026-09-30 is one indexed read:

- **snapshot kind**: the kind's newest date on or before the date asked (within `lookback-days`) is known from the
  catalogue; the read is `find({_id: "trade/MX-20000017/20260930"})`, projecting `doc` and `date`. No document that day
  means the trade is gone on that day.
- **effective kind** (or a kind whose dates are not known yet): `find({_id: {$gte: "counterparty/CP-X/", $lte:
  "counterparty/CP-X/20260930"}, id: "CP-X"}).sort({_id: -1}).limit(1)`: the newest version on or before the date.

The document text is handed to the server's JSON parser as it is stored. Measured: a trade's document on a past date in
**6 ms** over HTTP, a trade's full view in **13–17 ms**.

### 5.3 Type-ahead

From the ids in memory: `TRD CLY-30000` answers in **12–20 ms** over HTTP.

### 5.4 A day's columns

Searches, pick lists, derived kinds, impact and reverse lookups need a few fields of every entity of a day. The
connector reads them as a **column set**:

- **From `trading_columns`** when it exists (else from `trading`, and health says so): `find({kind, date, id: {$gte:
  a, $lt: b}}, {_id: 0, id: 1, c: 1}).sort({id: 1}).hint("day_ids")`, so documents come in id order straight from the
  index.
- **In parallel**: the day is split at the catalogue's id boundaries into `read-threads` (8) ranges read at once on
  virtual threads; the ranges concatenated are the whole day sorted by id. Boundaries that no longer fit (new ids)
  only unbalance the ranges; every document is still read once.
- **Decoded into arrays**: a BSON decoder writes each value straight into the range's `double[]` or `String[]` (no
  document object, no boxed number per value); repeated texts (books, desks, currencies) share one string, and a column of
  mostly distinct values (trade ids) stops pooling. During development this cut a million-row day from 3.2–4.4 s
  (documents decoded, then converted) to 1.5–2.6 s from `trading` and 0.4–0.8 s from `trading_columns`.
- **Kept** by memory (`columns-cache-mb`, 1024, weighed by the arrays' real size), and re-read in the background
  `columns-seconds` (300) after it was read, while searches keep using the last read (so a restated day is picked up
  without anyone waiting).
- **Ready**: the newest day is read in the background after each refresh.
- **Bounded**: at most `heavy-reads` (2) day reads at a time per connector; others wait.
- **Not held**: a day the collection does not have (deleted, expired, older than `lookback-days`) is answered "not held",
  so the router asks the next connector for the kind ([section 7](#7-seven-years-sizing-replica-sets-sharding-and-mongodb-with-delta-lake)).

Measured: a cold past day of 10,000 trades read and searched in **13–21 ms**; a search over a cached day in **2–10 ms**
server time.

### 5.5 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the day's column set: every promoted text value equal to
the id. The column set is usually already in memory (impact and searches use it), so the lookup is a scan of arrays.

An index on a link field, `{kind: 1, date: 1, "c.nettingSet": 1, id: 1}` on `trading_columns`, answers the same
question as a covered query. Measured on the 10,000-trade day: 2 ms with the index against 16 ms without (1,482 trades),
for 0.55 MB of index per 30,000 documents (an **estimated** 18 MB per million-trade day, for each link field). The
connector does not need it, because the cached column set is faster still and serves every link field at once; create
it only if operators run such queries in MongoDB.

A kind without promoted fields is answered from its documents: the day's documents (a snapshot kind) or each entity's
latest document on or before the date (an effective kind), at most `max-load-rows` (200,000). Documents stored as text
are filtered on the server first (`doc` contains `"<id>"`), so only candidates travel; each candidate is then checked by
parsing it. Kinds the domain does not hold are never read. `reverse-index: false` turns reverse lookups off.

### 5.6 Derived kinds and impact

Desk P&L and impact (F8) work from the column set exactly as on Delta Lake. Measured: desk P&L **11–13 ms**; impact of
netting set `NS-SUMMIT-NY` **33–59 ms**.

## 6. Retention: deleting old days, or TTL

Two ways, both set at load time; the connector has no retention setting.

**`--keep-days N`**: after loading, for every kind of every domain collection in the database, the loader finds the
kind's dates (a distinct scan) and deletes the days older than its N newest on or before `--as-of` (default: today in
the business zone; a day after it is neither counted nor deleted) from both collections. Every kind is checked first:
when one would lose more than `--max-drop-share` (0.5) of its days, nothing is deleted and the load fails, unless
`--force-drop`. The delete is
`deleteMany({kind, date: {$lt: cutoff}})`, a range of the `day_ids` index. Measured: 21,787 documents (one trade day and
the samples' older days, plus their `_columns` copies) in **386 ms**. A million-trade day is **estimated** at 20–40 s of
deletes once a day; run it outside business hours, as part of the nightly load.

**`--ttl-days N`**: every document gets `expireAt` = its business date plus N days (midnight UTC), and both collections
get a TTL index on it (`expireAfterSeconds: 0`). MongoDB's TTL monitor runs every 60 seconds and deletes what has
expired, with no job to schedule. Measured: 10,000 expired documents deleted in one pass in **173 ms**, 45 s after they
were loaded. The cost at a million documents a day: one more index entry per document (an **estimated** 15–25 MB a day
per collection) and a million single deletes a day spread over the monitor's passes, each updating every index; on a
replica set they replicate like any delete. The TTL index has to be kept forever, which `--keep-days` does not need.

Either way, a deleted day is answered "not held" and the next source for the kind is asked.

## 7. Seven years: sizing, replica sets, sharding, and MongoDB with Delta Lake

**Size per business day of a million trades — estimates** from the measured sizes per document (10,000 trades: 6,875
bytes uncompressed and about 1,050 bytes on disk per domain document, about 75 bytes per `_columns` document, 14–32 bytes
per index entry):

| History in MongoDB | Documents (each collection) | Data on disk (zstd) | Indexes (both collections) |
|---|---|---|---|
| 1 business day | 1 million | about 1.1 GB | about 70–90 MB |
| 90 business days | 90 million | about 100 GB | about 7 GB |
| 1 year (250 days) | 250 million | about 280 GB | about 20 GB |
| 7 years (1,760 days) | 1.76 billion | about 2 TB | about 140 GB |

Uncompressed the domain documents are about 7 GB a day (12 TB for seven years); the WiredTiger cache holds the recent
days' index pages and whatever documents are being read, not the history. Unlike Aerospike, MongoDB does not keep its
indexes in memory by design, so seven years is a matter of disk and of a cache large enough for the indexes of the days
users open.

**Replica sets.** Point the `uri` at the set (`mongodb://m1,m2,m3/?replicaSet=rs0`); credentials go in the URI
(`mongodb://drishti:secret@…/?authSource=admin`). `read-preference: secondaryPreferred` sends Drishti's reads,
including the heavy day reads, to secondaries, away from the loader's writes; reads may then lag the primary by the
replication delay, which only matters while a day is being loaded.

**Sharding.** For very large deployments shard both collections on `{kind: 1, id: "hashed"}`:

```
sh.shardCollection("drishti.trading", {kind: 1, id: "hashed"})
sh.shardCollection("drishti.trading_columns", {kind: 1, id: "hashed"})
```

A point read names `kind` and `id` and goes to one shard; a day read (`kind`, `date`, an id range) goes to every shard,
which read their parts in parallel, and the id ranges the connector issues stay the same. A ranged key `{kind: 1, id: 1}`
also works but concentrates new ids (a booking system's sequence) on one shard. Do not shard on `date`: a whole day would
land on one shard.

**Recent history in MongoDB, the full seven years in Delta Lake.** As with Aerospike, configure both connectors for the
kind, MongoDB first:

```yaml
drishti:
  sources:
    routes:
      trade: trading-store                     # asked first
    connectors:
      trading-store:                           # MongoDB: the last 90 business days (--keep-days 90 or --ttl-days 130)
        plugin: mongodb
        kinds: [trade]
        settings: { uri: "mongodb://m1,m2,m3/?replicaSet=rs0", database: risk, domain: trading,
                    read-preference: secondaryPreferred, "layout.trade.columns": "tradeId,productType,…" }
      trading-history:                         # Delta Lake: every business day for seven years
        plugin: delta
        kinds: [trade]
        settings: { root: "s3a://risk-lake/banking", domain: trading, "layout.trade.columns": "tradeId,productType,…" }
```

A read tries MongoDB first and falls through to Delta Lake for a day MongoDB does not hold; a search, pick list, derived
kind or impact takes the first connector that holds the business day; type-ahead merges both.

## 8. Memory in the Drishti server

| Held | Measured at 10,000 trades | **Estimated** per million trades | Bounded by | Default |
|---|---|---|---|---|
| type-ahead ids | — | about 150 MB | the newest day's ids | — |
| a day's column set (19 fields) | 2 MB | about 220 MB | `columns-cache-mb` | 1024 MB |
| boundaries and dates | negligible | negligible | `read-threads` | — |

Single documents are not cached by the connector (an indexed read takes well under a millisecond on the server). Measured
server heap in use with 10,000 trades a day: 142–268 MB of 2 GB. Give the server at least 8 GB of heap per million
entities a day (an estimate, the same as for Aerospike).

## 9. Measured results

On 2026-10-01, a developer workstation shared with other builds, one MongoDB 7.0.43 node in Docker (2 GB memory limit,
`--wiredTigerCacheSizeGB 0.5`), **10,000 trades a day over three business days plus the banking samples** (35,250
documents in `trading`, 35,250 in `trading_columns`), a Drishti server with the `mongodb` profile and `-Xmx2g`, times over
HTTP (the second of two passes, so without JIT warm-up):

| Question | Time |
|---|---|
| load the samples / load 30,000 trade-days (streamed from the generator) | 1 s / 3 s |
| server start (catalogue and type-ahead included) | 5.3 s |
| type-ahead `TRD CLY-30000` | 12–20 ms |
| open a trade (view), first / again | 17 ms / 13 ms |
| a trade's document on a past date | 6 ms |
| `TRD where mtm < -50m order by mtm` | 8 ms (21 matches of 10,000, `partial: false`) |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 14 ms (113 matches) |
| `TRD book=BOOK-RATES-3` | 10 ms (689 matches) |
| pick list `TRD END-10000` | 7 ms (1,000 matches) |
| desk P&L, one desk / another | 11 ms / 13 ms |
| impact of netting set `NS-SUMMIT-NY` (1,482 trades), first / again | 59 ms / 33 ms |
| a search on another business day, first (cold) / again | 13–21 ms / 7 ms |
| `--keep-days 2`: 21,787 documents deleted | 386 ms |
| TTL monitor: 10,000 expired documents deleted | 173 ms (one pass) |
| server heap in use | 142–268 MB |

Every search scanned all 10,000 trades of the day from columns (`scanned: 10000`, `partial: false`).

**A million trades a day: not measured; extrapolated.** The straight lines through the scale benchmark's points (the
table below) put the newest day's three searches at 70–180 ms, impact of `NS-SUMMIT-NY` at about 130 ms, the first
search on another business day at about 4 s (its columns read from `trading_columns`) and then about 220 ms, and three
days at about 3.9 GB of compressed storage and indexes. These are **extrapolations from 10,000 to 50,000 trades a day,
not measurements**. Development runs before the measurement limit read a million-trade day's columns through the
connector in 0.4–0.8 s from `trading_columns` ([section 5.4](#54-a-days-columns)), far below the line's 4 s: the cold
search jumped from 26 ms at 25,000 trades to 184 ms at 50,000, and one such point steepens a four-point line. Treat that
figure as an upper bound; about 220 MB of heap per cached day remains an estimate.

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, MongoDB 7 in Docker capped at 2 GB, WiredTiger cache 0.5 GB. Medians of 25
requests; every search exact (`partial: false`, `scanned` equal to the trades a day). The last column carries a
straight-line fit (R² beside it) to a million trades a day: **an extrapolation from the measured points, not a
measurement**; "flat" means the measure does not grow with the book. This store has not been measured at a million
trades a day; the last column is the best figure there is, and only an order of magnitude.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 6.3 | 6.8 | 6.9 | 0.01 | flat, about 6.9 ms |
| open a trade (view), first time (ms) | 4.8 | 3.5 | 5.6 | 0.04 | 14.5 ms (weak fit) |
| a trade's document, today (ms) | 1.4 | 1.7 | 3.0 | 0.67 | 30.3 ms |
| a trade's document, a past day (ms) | 1.3 | 2.7 | 6.2 | 0.95 | 111 ms |
| `TRD where mtm < -50m order by mtm` (ms) | 1.0 | 2.5 | 4.0 | 0.98 | 68.7 ms |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 2.3 | 4.0 | 9.6 | 0.97 | 177 ms |
| `TRD book=BOOK-RATES-3` (ms) | 1.6 | 4.3 | 9.4 | 0.96 | 176 ms |
| pick list `TRD END-1100` (ms) | 1.0 | 1.0 | 2.9 | 0.84 | 45.3 ms |
| desk P&L `DESK-RATES` (ms) | 6.6 | 4.6 | 3.3 | 0.19 | flat, about 4.4 ms |
| impact of `NS-SUMMIT-NY` (ms) | 17.9 | 19.9 | 22.8 | 0.98 | 132 ms |
| a search on another day, first (ms) | 14.1 | 25.6 | 184 | 0.90 | 4,133 ms |
| a search on another day, again (ms) | 1.1 | 2.1 | 10.3 | 0.91 | 221 ms |
| load (the whole script) (s) | 10.2 | 16.2 | 17.1 | 0.73 | 155 s |
| store size (MB) | 47.1 | 104 | 202 | 1.00 | 3,872 MB |
| server live heap after a full GC (MB) | 109 | 132 | 174 | 1.00 | 1,702 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 10. Limits and trade-offs

- **Two writes per row.** The `_columns` copy makes day reads three to four times faster for about 7% more disk; the two
  writes are not one transaction, so for the moments a batch is in flight a day's columns may differ from its documents.
  Reload the batch (it is idempotent) after a failed load.
- **The first search on a business day other than the newest waits for that day's columns**; the newest day is read in
  the background.
- **Only promoted fields are fast.** Searches on other fields read documents (20,000 at most, `partial`).
- **Effective-mode kinds** are not read as columns; they are small and read as documents.
- **The catalogue refresh reads every id of the newest day** every `refresh-seconds` (a covered query); with tens of
  millions of entities raise `refresh-seconds`.
- **`--doc-format bson`** makes every read convert BSON to JSON (about twice the read time).
- **MongoDB versions on new Linux kernels.** On Linux 6.19 and newer, MongoDB 8.0 refuses to start (SERVER-121912) and
  8.2 crashed under load in testing; MongoDB 7.0 was used for all measurements and for the plugin's test.

## 11. Diagnosing

`GET /api/v1/admin/health` shows the connector and its catalogue:

```json
{"name": "trading-store", "health": "UP",
 "cache": {"kinds": 1, "datesIndexed": 10, "ids": 10000, "columnSets": 2, "columnSetsMb": 4, "dayReads": 2, "documentReads": 2}}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: cannot reach MongoDB (…)` | wrong `uri`, credentials, or the server is down | check `uri`; the driver reconnects by itself. A connector without `kinds` that has not reached MongoDB since start fails reads (`DRS-1003 … has not reached MongoDB yet`) rather than letting another store answer |
| `DOWN: no documents in drishti.trading` | nothing loaded, or the wrong `database`/`collection` | load with `tools/load-mongodb.sh`; check the connector's `domain` |
| `UP (not laid out: missing index day_ids …)` | the collection was written by another tool | `db.trading.createIndex({kind: 1, date: 1, id: 1}, {name: "day_ids"})` (and on `trading_columns`) |
| `UP (no trading_columns collection: …)` | a writer that does not write the narrow copy | write `MongoLayout.columnsRecord` too, or reload with the loader |
| a trade is in type-ahead but does not open on a date | its day was deleted or expired | with a Delta Lake connector behind, older days come from there |
| searches say `partial: true` | a field the query reads is not promoted | add it to `layout.<kind>.columns` and reload |
| the first search on a past day is slow | that day's columns are being read | expected once per day per `columns-seconds`; raise `read-threads` |
| `dayReads` keeps growing | `columns-cache-mb` too small for the days in use | raise `columns-cache-mb` |

## 12. Settings

On a MongoDB connector (`drishti.sources.connectors.<name>.settings`):

| Setting | Default | Meaning |
|---|---|---|
| `uri` | none (required: without it the plugin stays idle; the `mongodb` profile sets `mongodb://localhost:27017`) | connection string: hosts, replica set, credentials, TLS, compressors |
| `database` | `drishti` | the database |
| `collection` | the connector's `domain`, else `drishti` | the domain's collection (`<collection>_columns` beside it) |
| `read-preference` | `primary` | `primary`, `primaryPreferred`, `secondary`, `secondaryPreferred` or `nearest` |
| `kinds` | what the collection holds | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | the promoted fields ([section 3](#3-declaring-which-fields-are-columns)) |
| `refresh-seconds` | `60` | how often the kinds' dates and the ids are re-read |
| `read-threads` | `8` | id ranges read at once when a day's columns are read |
| `heavy-reads` | `2` | day reads at once per connector |
| `batch-size` | `5000` | documents per cursor batch for day reads |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `columns-seconds` | `300` | after this a day's column set is re-read in the background (the last read is served meanwhile) |
| `max-load-rows` | `200000` | the most documents read for a reverse lookup on a kind without promoted fields |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `connect-timeout-ms` | `3000` | connection and server-selection timeout |
| `source-name` | `mongodb` (a connector: its name) | the name shown in provenance and Health |

Loader options (`tools/load-mongodb.sh` and `MongoLoader`): `--keep-days N`, `--ttl-days N`, `--doc-format string|bson`
(`string`), `--batch N` (1000), `--in-flight N` (16), `--as-of yyyy-MM-dd` (today in the business zone), `--future-days N` (1), `--zone Z` (`DRISHTI_BUSINESS_ZONE`, else `America/New_York`), `--max-drop-share F` (0.5), `--force-drop`, `--trades N --days D` (the script only).

## 13. Checklist for production

1. Declare `layout.<kind>.columns` for every large kind; load with the `columns` in each row so `_columns` is written.
2. Load with the loader, or write `MongoLayout.record` and `columnsRecord` from your own writer; check Admin → Health
   says `UP` (indexes and `_columns` present).
3. Choose the history MongoDB keeps: `--keep-days` in the nightly load, or `--ttl-days`.
4. Run a replica set and read from secondaries (`read-preference: secondaryPreferred`); shard on `{kind: 1, id:
   "hashed"}` when one replica set no longer holds the history.
5. Put a Delta Lake connector behind it for the full seven years, with the same layout.
6. Give the server at least 8 GB of heap per million entities a day; size `columns-cache-mb` for the days users open.
7. Measure on your data (`elapsedMs`, `scanned` and `partial` of a search).
