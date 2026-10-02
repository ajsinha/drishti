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
# The Aerospike connector at scale: a million trades a day, for years

This document explains how Drishti serves a book of **1,000,000 trades every business day** from Aerospike, with
single-trade reads in a few milliseconds, type-ahead in tens of milliseconds and searches over every trade of a day in
a few hundred milliseconds, and how to keep **seven years** of history affordable. It covers the record layout, why it
replaced the old one, what the connector reads for each question, the loader, retention, sizing, measured results and
limits.

Read it if you run Drishti on Aerospike, load Aerospike for Drishti, or change the connector. For a first setup see
[CONNECTOR_GUIDE.md](CONNECTOR_GUIDE.md#9-a-key-value-store-aerospike); every setting is in
[CONFIGURATION.md](../admin/CONFIGURATION.md#aerospike). The same design for Delta Lake is in
[DELTA_CONNECTOR.md](DELTA_CONNECTOR.md); the two share the engine side (columns, searches, derived kinds, impact), which
is explained there in [section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns).

## Contents

1. [The problem](#1-the-problem)
2. [The record layout](#2-the-record-layout)
3. [Declaring which fields are bins](#3-declaring-which-fields-are-bins)
4. [Loading](#4-loading)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Retention: TTL instead of a maintenance job](#6-retention-ttl-instead-of-a-maintenance-job)
7. [Seven years: sizing, and Aerospike with Delta Lake](#7-seven-years-sizing-and-aerospike-with-delta-lake)
8. [Memory in the Drishti server](#8-memory-in-the-drishti-server)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)

---

## 1. The problem

The first Aerospike layout kept **one record per entity**, key `kind/id`, with **a bin per business date** (`d20260930`)
holding that day's document. A dated read was one key lookup, which is what Aerospike does best. It does not survive a
large book over years:

| Limit | Why it breaks |
|---|---|
| **Record size** | Aerospike records are at most 8 MB (the default write-block size). A trade document is about 7 KB, so a record of every business day of one trade reaches 8 MB after about 1,150 days: four and a half years. Seven years cannot be written. |
| **Rewriting** | Adding a day rewrote the whole record (every past day's document) for every trade, every day. |
| **Scans** | To learn which dates and ids exist, and which entities reference which, the connector scanned the whole set at start and every minute, reading every document of every day: millions of records times hundreds of days. |
| **Searches** | Nothing could be read without whole documents, so searches and aggregates read and parsed documents, at most 20,000 of them. |

## 2. The record layout

A data domain (`trading`) now uses three sets in the namespace:

| Set | Record | Key | Bins |
|---|---|---|---|
| `trading` | one per entity per business day | `trade/MX-20000017/20260930` | `kind`, `id`, `date`, `doc`, and the promoted fields |
| `trading_ix` | one per entity (its index) | `trade/MX-20000017` | `kind`, `id`, `dates` |
| `trading_kinds` | one per kind | `trade` | `kind`, `dates` |

For one trade on one day:

```
set trading, key "trade/MX-20000017/20260930"
  kind              "trade"
  id                "MX-20000017"
  date              20260930                                  the business day as a number (yyyyMMdd)
  doc               "{\"tradeId\":\"MX-20000017\",\"sourceSystem\":\"Murex\",…,\"businessDate\":\"2026-09-30\"}"
  tradeId           "MX-20000017"                             promoted fields: text …
  productType       "IRS_FIXFLOAT"
  book              "BOOK-RATES-3"
  desk              "DESK-RATES"
  counterparty_id   "CP-MERIDIAN-RE"
  counterpar_f5fe   "Meridian Reinsurance Ltd"                counterparty.name (too long for a bin name: see below)
  notional          242000000.0                               … and numbers, stored as doubles
  mtm               1875863.0
  risk_dv01         -155245.0

set trading_ix, key "trade/MX-20000017"
  kind              "trade"
  id                "MX-20000017"
  dates             [20260928, 20260929, 20260930]            sorted, each day once

set trading_kinds, key "trade"
  kind              "trade"
  dates             [20260917, 20260918, …, 20260930]
```

Why each part is shaped this way:

- **A record per entity per day.** Each record is one document (about 7 KB) whatever the history, far below the 8 MB
  limit; adding a day writes only that day's records; expiring a day ([section 6](#6-retention-ttl-instead-of-a-maintenance-job))
  removes only that day's records.
- **The index record per entity** answers "which days does this trade have?" in one key lookup, so a read never
  scans. For an `effective` kind (a record only when the entity changes) it gives the entity's latest day on or before
  the one asked. Seven years of dates is a list of about 1,760 numbers: 14 KB.
- **The kind's dates** tell the connector which business days exist without scanning a single document: a `snapshot`
  kind is read on the newest day on or before the date asked (within `lookback-days`).
- **Promoted fields as bins** let searches, derived kinds, impact and reverse lookups read a day's few narrow bins with a
  server-side filter, never documents ([section 5.4](#54-a-days-columns)).
- **The date as a number bin** lets Aerospike filter a scan by day on the server (`date == 20260930`).

**Bin names are at most 15 characters** in Aerospike. A promoted path becomes a bin name by replacing dots with
underscores (`counterparty.id` → `counterparty_id`); a longer name is cut to 10 characters, followed by an underscore and
the first 4 hex digits of the path's SHA-1 (`counterparty.name` → `counterpar_f5fe`), so two long paths never share a bin.
The reserved names (`kind`, `id`, `date`, `doc`, `dates`) are never used for a promoted field. The loader and the
connector share this rule (`AerospikeLayout.bin`).

## 3. Declaring which fields are bins

The pack declares the fields to promote on the connector that stores the kind, exactly as for Delta Lake (see
[DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack) for how to choose them). The
trading pack declares them on `trading-store`; the `aerospike` profile (`SPRING_PROFILES_ACTIVE=aerospike`) switches that
connector to the Aerospike plugin and keeps the pack's settings, so the same declaration applies:

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

# drishti-server application-aerospike.yaml (the profile)
drishti:
  sources:
    connectors:
      trading-store: { plugin: aerospike, settings: { hosts: "${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}",
                                                       namespace: "${DRISHTI_AEROSPIKE_NAMESPACE:test}" } }
```

The connector reads `layout.<kind>.columns`; the set is the connector's `domain` (`trading`). The `sort-by`, `file-rows`
and `row-group-rows` keys of the layout concern files and do not apply to Aerospike.

## 4. Loading

`tools/load-aerospike.sh` loads the banking packs' data, and optionally a large generated book, through
`AerospikeLoader` (in the plugin):

```bash
tools/load-aerospike.sh localhost:3000 test                                   # the samples: 1,791 documents x 10 days
tools/load-aerospike.sh localhost:3000 test --trades 1000000 --days 3        # and a book of a million trades, streamed
tools/load-aerospike.sh localhost:3000 test --ttl-days 90                    # documents expire after 90 days
```

```
aerospike: loaded 17,910 rows into namespace test in 1 s
3,000,000 trade-days as JSON lines in 146 s
aerospike: loaded 3,000,000 rows into namespace test in 147 s
```

How the loader works:

- **Its input is JSON lines**, one per entity per day: `{"domain", "kind", "id", "date", "doc", "columns": {path:
  value}}`. `make_data.py --jsonl` writes the samples; `bulk_trades.py --jsonl -` writes a generated book to standard
  output, piped straight into the loader (`AerospikeLoader -` reads standard input), so a million-trade book never sits
  in a file of tens of gigabytes. The `columns` are the fields the pack promotes, taken from the same document.
- **Each line becomes two writes**: the day's record (a put of all its bins) and the entity's index record (an
  operation that sets `kind` and `id` and appends the date to `dates`, sorted and unique). Each kind's dates record is
  written once at the end.
- **Up to 128 writes are in flight**, on virtual threads; the client allows 256 connections per node (Aerospike's
  default of 100 is too few for this). It loads about 20,000 rows a second against one local node, including the index
  operations.
- **`--ttl-days N`** gives every day's record an expiry ([section 6](#6-retention-ttl-instead-of-a-maintenance-job)),
  counted from when it is written.
- **Dates are guarded**: a row dated after tomorrow in the business zone (`--future-days N`, 1; `--zone Z`) is not
  loaded; the first few are named on standard error and the load ends with an error once the other rows are in.
- **Writes are idempotent**: loading a day again replaces its records; a date is added to an index only once.

**Your own loader** (a Kafka consumer, a batch job) writes the same three things with the Aerospike client: the day's
record with its promoted bins, the index record's `dates` (`ListOperation.append` with an ordered, unique list policy)
and, once per load, the kind's dates. `AerospikeLayout.write` and `AerospikeLayout.addDates` do exactly that and can be
called from Java.

**The namespace must be able to hold the data.** The default namespace of the Aerospike Docker image keeps data in
memory with a small size limit; three million 7 KB records do not fit. Use storage on a device or a file, for example:

```
namespace test {
    replication-factor 1
    default-ttl 0
    storage-engine device {
        file /opt/aerospike/data/test.dat
        filesize 64G
    }
}
```

## 5. How the connector reads

### 5.1 The catalogue

Every `refresh-seconds` (60) the connector scans two small things:

- the `<domain>_kinds` set: each kind's business dates (a few records);
- the `<domain>_ix` set, reading only the `kind` and `id` bins: every entity's id, for type-ahead.

No document is read. A million index records scan in a few seconds in the background; the type-ahead index (Drishti's
sorted `HitIndex`, see [DELTA_CONNECTOR.md, section 6.4](DELTA_CONNECTOR.md#64-type-ahead)) is then swapped in whole.
Then the newest day's columns are loaded in the background ([section 5.4](#54-a-days-columns)).

### 5.2 Opening one entity

`TRD MX-20000017` on 2026-09-30 is two key reads:

1. Get `trading_ix / trade/MX-20000017`, bin `dates`: the days this trade has.
2. Choose the day: for a `snapshot` kind, the kind's newest day on or before the date asked (within `lookback-days`),
   if this trade has it; for an `effective` kind, the trade's own latest day on or before it.
3. Get `trading / trade/MX-20000017/20260930`, bin `doc`.

| | Time |
|---|---|
| a trade's document (`/entities/…/raw`) | 22–30 ms over HTTP |
| a trade's full view, first / again | 79–134 ms / 49 ms |
| a trade on a past business day | 22 ms |

### 5.3 Type-ahead

From the ids of the index set, sorted in memory: `TRD CLY-40834` answers in **52 ms** over HTTP with a million ids.

### 5.4 A day's columns

Searches, pick lists, derived kinds, impact and reverse lookups need a few fields of every trade of a day. The
connector reads them as a **column set** ([DELTA_CONNECTOR.md, section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns)
explains how the engine uses it):

- **A filtered scan**: Aerospike evaluates `kind == "trade" and date == 20260930` on the server and returns only
  matching records, and only the `id` bin and the promoted bins: no document leaves the server.
- **In parallel**: the set's 4,096 partitions are split into `scan-threads` (8) ranges scanned at once; a single
  sequential scan of a three-million-record set took about 20 s, the parallel scan about 7 s.
- **Kept**: by memory (`columns-cache-mb`, 1024; a million trades of 19 fields is about 230 MB), refreshed after
  `columns-seconds` (300) so a day being loaded is re-read.
- **Ready**: the newest day is loaded in the background after each catalogue refresh; on the test server it was ready
  14 s after start.
- **Bounded**: at most two scans run at a time per connector. Aerospike refuses scans beyond its own limit with error 22
  ("operation not allowed at this time"). A refused scan never becomes an error page: a reverse lookup finds no
  referrers, and a search reads documents instead (and says `partial`).

### 5.5 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the day's promoted text bins: every value equal to the
id. A kind without promoted bins is answered by scanning that day's documents on the server side (filtered by kind and
date), at most `max-load-rows` (200,000); an `effective` kind takes each entity's latest document on or before the date.
Kinds the domain does not hold are never scanned. `reverse-index: false` turns reverse lookups off for a connector.

### 5.6 Derived kinds and impact

Desk P&L and impact (F8) work from the column set exactly as on Delta Lake. Desk P&L is computed once in the background
after start and kept, recomputed behind the scenes every `refresh` (the last result is served meanwhile): **29–42 ms**.
Impact of a netting set with 143,842 trades: **1.6 s**.

## 6. Retention: TTL instead of a maintenance job

Aerospike expires records by itself. Load with `--ttl-days N` (or write with a `WritePolicy.expiration`) and each day's
records disappear N days after they were written, with no job to run and no compaction to schedule. The index records
are not given a TTL (they are small and still useful), so a trade's `dates` may list days whose records have expired:

- a read for such a day finds no record and answers "not held", so the next source for the kind is asked;
- a search on such a day finds no rows and answers "not held", so the next source is asked
  ([section 7](#7-seven-years-sizing-and-aerospike-with-delta-lake)).

A namespace-wide `default-ttl` does the same for every set.

## 7. Seven years: sizing, and Aerospike with Delta Lake

Aerospike keeps its **primary index in memory**: 64 bytes for every record (Community Edition; Enterprise Edition can
keep it on flash). Seven years of a million trades a day is about 1.76 billion day records:

| History in Aerospike | Day records | Primary index in RAM | Data (about 7.5 KB a record, uncompressed) |
|---|---|---|---|
| 30 business days | 30 million | about 1.9 GB | about 225 GB |
| 90 business days | 90 million | about 5.8 GB | about 675 GB |
| 1 year (250 days) | 250 million | about 16 GB | about 1.9 TB |
| 7 years (1,760 days) | 1.76 billion | about 113 GB | about 13 TB |

(The index records add a million entries; the kinds' records are negligible.) Seven years in Aerospike is possible
on a large cluster, especially with Enterprise Edition's index on flash and compression, but it is a lot of memory and
disk for days users rarely open. The design that serves both needs well:

**Recent history in Aerospike, the full seven years in Delta Lake.** Configure both connectors for the kind, with
Aerospike first:

```yaml
drishti:
  sources:
    routes:
      trade: trading-store                     # asked first
    connectors:
      trading-store:                           # Aerospike: the last 90 business days, TTL-expired
        plugin: aerospike
        kinds: [trade]
        settings: { hosts: "aero1:3000,aero2:3000", namespace: risk, domain: trading, lookback-days: 10,
                    "layout.trade.columns": "tradeId,productType,…" }
      trading-history:                         # Delta Lake: every business day for seven years
        plugin: delta
        kinds: [trade]
        settings: { root: "s3a://risk-lake/banking", domain: trading, lookback-days: 10,
                    "layout.trade.columns": "tradeId,productType,…" }
```

How Drishti uses them:

- **A read** tries the routed connector first, then every other connector serving the kind, until one holds the
  entity on that date. Today and recent days come from Aerospike in milliseconds; a date older than the TTL is not
  found there and is read from Delta Lake.
- **A search, a pick list, a derived kind, impact** asks the connectors that keep the needed fields as columns, in the
  same order, and takes the first that holds the business day: recent days from Aerospike, older ones from Delta Lake.
  (This fall-through is covered by `SourceRouterTest.aSearchOnADateOneStoreDoesNotHoldReadsTheNextThatDoes`.)
- **Type-ahead** merges both; the same id appears once.

## 8. Memory in the Drishti server

| Held | Per million trades | Bounded by | Default |
|---|---|---|---|
| type-ahead ids | about 150 MB | the index set's ids | — |
| a day's column set (19 fields) | about 230 MB | `columns-cache-mb` | 1024 MB |
| the kinds' dates | negligible | — | — |

Single reads are not cached by the connector (Aerospike answers them in about a millisecond). Measured server heap in
use with a million trades a day: **2.75 GB**. Give the server at least 8 GB of heap per million entities a day.

## 9. Measured results

**Measured at a million trades a day, by hand, on 2026-10-01.**

On 2026-10-01, a developer workstation (24 cores), one Aerospike Community Edition 8.1 node in Docker with file
storage, 1,000,000 trades a day over three business days plus the banking samples (3,017,910 day records), a Drishti
server with the `aerospike` profile, times over HTTP:

| Question | Time |
|---|---|
| load the samples / load 3,000,000 trade-days (streamed) | 1 s / 147 s |
| server start, then the newest day's columns ready | 8 s, then 14 s |
| type-ahead `TRD CLY-40834` | 52 ms |
| open a trade (view), first / again | 79–134 ms / 49 ms |
| a trade's document, today / a past day | 30 ms / 22 ms |
| `TRD where mtm < -50m order by mtm` | 132 ms (2,041 matches of 1,000,000) |
| `TRD where currency = 'USD' and notional > 500m …` | 213 ms (13,523 matches) |
| `TRD book=BOOK-RATES-3` | 159 ms (70,649 matches) |
| pick list `TRD END-1100` | 225 ms |
| desk P&L, one desk / another | 42 ms / 29 ms |
| impact of a netting set (143,842 trades) | 1.6 s |
| a search on another business day, first / again | 7.1 s / 68 ms |
| server heap in use | 2.75 GB |

Before this design the same book could not be held: the old layout's records would have exceeded 8 MB after four and a
half years, and every search read at most 20,000 documents.

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, one Aerospike CE 8.1 node in Docker capped at 2 GB, namespace on a file. Medians
of 25 requests; every search exact (`partial: false`, `scanned` equal to the trades a day). The last column carries a
straight-line fit (R² beside it) to a million trades a day: **an extrapolation from the measured points, not a
measurement**; "flat" means the measure does not grow with the book. The million-trade measurement above is the real
figure; where the line and it differ, trust the measurement.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 22.8 | 9.1 | 8.9 | 0.25 | flat, about 12.5 ms |
| open a trade (view), first time (ms) | 8.3 | 5.2 | 4.7 | 0.60 | flat, about 6.1 ms |
| a trade's document, today (ms) | 3.4 | 2.4 | 2.8 | 0.32 | flat, about 3.7 ms |
| a trade's document, a past day (ms) | 3.5 | 2.3 | 5.0 | 0.00 | 7.3 ms (weak fit) |
| `TRD where mtm < -50m order by mtm` (ms) | 1.5 | 2.2 | 5.0 | 0.19 | 44.7 ms (weak fit) |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 4.0 | 6.4 | 8.0 | 0.95 | 105 ms |
| `TRD book=BOOK-RATES-3` (ms) | 2.6 | 3.2 | 8.1 | 0.52 | 98.3 ms |
| pick list `TRD END-1100` (ms) | 2.1 | 0.9 | 6.2 | 0.74 | 112 ms |
| desk P&L `DESK-RATES` (ms) | 5.9 | 3.2 | 5.5 | 0.04 | flat, about 5.2 ms |
| impact of `NS-SUMMIT-NY` (ms) | 165 | 601 | 174 | 0.01 | 1,248 ms (weak fit) |
| a search on another day, first (ms) | 66.6 | 189 | 426 | 0.98 | 8,432 ms |
| a search on another day, again (ms) | 2.5 | 3.1 | 6.5 | 0.92 | 97.7 ms |
| load (the whole script) (s) | 14.4 | 14.9 | 18.8 | 0.61 | 96.7 s |
| store size (MB) | 276 | 613 | 1,176 | 1.00 | 22,558 MB |
| server live heap after a full GC (MB) | 88.9 | 98.8 | 117 | 1.00 | 799 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 10. Limits and trade-offs

- **The first search on a business day other than the newest waits for that day's columns** (7.1 s for a million
  trades on one node); the newest day is loaded in the background. More nodes and `scan-threads` shorten it.
- **Only promoted fields are fast.** Searches on other fields read documents (20,000 at most, `partial`).
- **Effective-mode kinds** are not read as columns (each entity's latest day would have to be resolved); they are
  small and read as documents.
- **The catalogue refresh scans the index set** every `refresh-seconds`; with tens of millions of entities, raise
  `refresh-seconds` (new entities then appear in type-ahead later; reading them by id always works).
- **Aerospike's scan limits** are shared with every other client of the cluster; the connector runs at most two scans at
  a time; a refused scan leaves a reverse lookup empty and turns a search into a document search.
- **Seven years in Aerospike is memory-heavy** ([section 7](#7-seven-years-sizing-and-aerospike-with-delta-lake)): keep
  recent history there and the rest in Delta Lake.

## 11. Diagnosing

`GET /api/v1/admin/health` shows the connector and its catalogue:

```json
{"name": "trading-store", "health": "UP", "cache": {"ids": 1000000, "columnSets": 1, "datesIndexed": 10, "kinds": 1}}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: not connected to Aerospike` | the cluster is unreachable | check `hosts`; the client reconnects by itself |
| a trade is in type-ahead but does not open on a date | its record for that day expired (TTL) or was never loaded | check the index record's `dates`; with a Delta Lake connector behind, older days come from there |
| searches say `partial: true` | a field the query reads is not a promoted bin | add it to `layout.<kind>.columns` and reload |
| the first search after a load is slow | the newest day's columns are loading | they load in the background after each refresh (`refresh-seconds`) |
| linked entities missing for a large kind | no promoted link bins, and the day exceeds `max-load-rows` | promote the link fields (`nettingSet`, `book`, `counterparty.id`) |
| loader: `max connections … would be exceeded` | an old loader with the client's default of 100 connections | use the current loader (256 connections per node) |
| error 22 `operation not allowed at this time` in logs | too many concurrent scans on the cluster | the connector already limits itself to two; check other scanning clients |
| `stop writes` / out of space while loading | the namespace's storage is too small | file or device storage, sized by [section 7](#7-seven-years-sizing-and-aerospike-with-delta-lake) |

## 12. Settings

On an Aerospike connector (`drishti.sources.connectors.<name>.settings`):

| Setting | Default | Meaning |
|---|---|---|
| `hosts` | `localhost:3000` | seed nodes, comma separated |
| `namespace` | `test` | the namespace |
| `set` | the connector's `domain`, else `drishti` | the data domain's set (`<set>_ix` and `<set>_kinds` beside it) |
| `kinds` | what the kinds set lists | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | the promoted fields, stored as bins ([section 3](#3-declaring-which-fields-are-bins)) |
| `refresh-seconds` | `60` | how often the kinds' dates and the ids are re-read |
| `scan-threads` | `8` | partition ranges scanned at once when reading a day's columns |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `columns-seconds` | `300` | how long a day's column set is kept before it is read again |
| `max-load-rows` | `200000` | the most documents scanned for a reverse lookup on a kind without promoted bins |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `connect-timeout-ms` | `3000` | connection timeout |
| `user`, `password` | none | credentials, if the cluster requires them |
| `source-name` | the connector's name | the name shown in provenance and Health |

## 13. Checklist for production

1. Declare `layout.<kind>.columns` for every large kind; load with the `columns` in each row.
2. Give the namespace device or file storage sized for the history you keep there ([section 7](#7-seven-years-sizing-and-aerospike-with-delta-lake)).
3. Choose the history Aerospike keeps and load with `--ttl-days` (or a namespace `default-ttl`).
4. Put a Delta Lake connector behind it for the full seven years, with the same layout.
5. Give the server at least 8 GB of heap per million entities a day.
6. Check Admin → Health, then measure on your data (`elapsedMs`, `scanned` and `partial` of a search).
