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
# The PostgreSQL connector at scale: a million trades a day, for years

This document explains how Drishti serves a book of **1,000,000 trades every business day** from PostgreSQL, through
the `jdbc` connector's *table mode*, with type-ahead in tens of milliseconds, a trade's document in a primary-key
lookup, and searches over every trade of a day in 100–250 ms, and how the same table keeps **seven years** of history
manageable. It covers the table layout, the loader, each read path, retention, sizing, measurements and limits.

Read it if you run Drishti on PostgreSQL, load PostgreSQL for Drishti, or change the connector. For a first setup see
[CONNECTOR_GUIDE.md](CONNECTOR_GUIDE.md#7-a-database-loaded-for-drishti-jdbc-table-mode); every setting is in
[CONFIGURATION.md](../admin/CONFIGURATION.md#jdbc--a-database). The same design for the other stores is in
[DELTA_CONNECTOR.md](DELTA_CONNECTOR.md) (whose [section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns)
explains how the engine uses a day's columns) and [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md). To build demo data
of any size, see [DEMO_DATA.md](DEMO_DATA.md).

## Contents

1. [The problem](#1-the-problem)
2. [The table layout](#2-the-table-layout)
3. [Declaring the promoted columns](#3-declaring-the-promoted-columns)
4. [Loading](#4-loading)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Retention: dropping months](#6-retention-dropping-months)
7. [Seven years: sizing, and PostgreSQL with Delta Lake](#7-seven-years-sizing-and-postgresql-with-delta-lake)
8. [Memory in the Drishti server](#8-memory-in-the-drishti-server)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)
14. [Query mode: your own SQL](#14-query-mode-your-own-sql)

---

## 1. The problem

Table mode began with one plain table per data domain, `(kind, id, business_date, doc jsonb)`, a row per entity per
business day. At a few thousand entities it is fine. At a million trades a day for years it is not:

| Question | What the first version did |
|---|---|
| type-ahead `TRD CLY-4083` | a `LIKE '%cly-4083%'` query over every row of every day, on each keystroke |
| which business days exist | `SELECT MAX(business_date) …` subqueries on every read |
| a search over the book | read and parsed at most 20,000 whole documents (`partial`) |
| desk P&L, impact | read documents, at most 50,000 |
| linked entities | `jsonb_path_exists` over every document of the day |
| retention | `DELETE` of hundreds of millions of rows, then a long `VACUUM` |
| the table | one heap and one set of indexes for seven years: 1.76 billion rows |

## 2. The table layout

`PostgresLoader` (in the plugin) writes, and the connector reads, this layout for each data domain:

```sql
CREATE TABLE trading.entities (
  kind          text  NOT NULL,
  id            text  NOT NULL,
  business_date date  NOT NULL,
  doc           jsonb COMPRESSION lz4 NOT NULL,
  "tradeId" text, "productType" text, "productName" text, direction text, currency text,
  notional double precision, mtm double precision, "pnl1d" double precision, "maturityDate" text, "tradeDate" text,
  book text, desk text, status text, "assetClass" text, "counterparty__id" text, "counterparty__name" text,
  "nettingSet" text, "risk__dv01" double precision, "sourceSystem" text,
  PRIMARY KEY (kind, id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE trading.entities_y2026m09 PARTITION OF trading.entities
  FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');                       -- one partition a month

CREATE INDEX entities_day_ids ON trading.entities (kind, business_date, id);

CREATE TABLE trading.entity_dates (kind text, business_date date, rows bigint, loaded_at timestamptz,
                                   PRIMARY KEY (kind, business_date));
```

One row, for one trade on one day (the document shortened):

```
kind               trade
id                 MX-30000017
business_date      2026-09-30
doc                {"tradeId": "MX-30000017", "sourceSystem": "Murex", "productType": "IRS_FIXFLOAT", "mtm": 1875863, …}
tradeId            MX-30000017
productType        IRS_FIXFLOAT
notional           242000000
mtm                1875863
book               BOOK-RATES-3
counterparty__id   CP-MERIDIAN-RE
nettingSet         NS-MERIDIAN-RE-ISDA
risk__dv01         -155245
…
```

Why each part is shaped this way:

- **Partitioned by month.** A business day's rows sit in one partition, so every query that names a date reads one
  partition and its indexes. Dropping a month of history is dropping a table: instant, with nothing to vacuum
  ([section 6](#6-retention-dropping-months)). A month holds about 21 million trade rows; seven years is 84 partitions,
  which the planner prunes easily. (Daily partitions would mean 1,760 tables and slower planning; yearly partitions
  would make each one huge and retention coarse.)
- **The document stays whole**, as `jsonb`, so the database can still answer any question about it with SQL. PostgreSQL
  moves large values out of the row into its TOAST storage, compressed with LZ4, so the row itself stays small: the
  promoted columns and a pointer.
- **Promoted columns are copies** of the fields questions filter, sort, group and link by, named as in Delta Lake
  (`counterparty.id` is `"counterparty__id"`, quoted because names keep their case). Numbers are `double precision`,
  everything else `text`. Reading a day's columns reads the small rows, never the documents.
- **The primary key `(kind, id, business_date)`** serves single reads: one index probe in one partition.
- **The index `(kind, business_date, id)`** holds a day's ids in order: type-ahead reads them from the index alone (an
  index-only scan, since the loader vacuums what it wrote), and the connector cuts a day into equal id ranges with it
  to read the columns on several connections at once.
- **`entity_dates`** lists each kind's business dates and when they were loaded, so the connector never asks the large
  table which days exist, and knows when a load replaced a day.

## 3. Declaring the promoted columns

The pack declares the fields on the connector that stores the kind, exactly as for Delta Lake (see
[DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack) for how to choose them). The trading
pack declares them on `trading-store`; the `postgres` profile (`SPRING_PROFILES_ACTIVE=postgres`) switches that
connector to the `jdbc` plugin in table mode and keeps the pack's settings, so the declaration applies unchanged:

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

# drishti-server application-postgres.yaml (the profile)
drishti:
  sources:
    connectors:
      trading-store: { plugin: jdbc, settings: { url: "${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}",
                       user: "${DRISHTI_PG_USER:drishti}", password: "${DRISHTI_PG_PASSWORD:drishti}",
                       table: trading.entities, pool-size: "12" } }
```

The connector serves a path as a column only when the table has that column; a declared path the table lacks shows in
Health ([section 11](#11-diagnosing)).

## 4. Loading

`tools/load-postgres.sh` loads the banking packs' data, and optionally a large generated book:

```bash
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti                               # samples: 1,791 documents x 10 days
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 50000                # and 50,000 trades a day for 3 days
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 1000000 --days 3     # the scale test
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --keep-months 84              # keep seven years
```

```
postgres: loaded 17,910 rows in 2 s
3,000,000 trade-days as JSON lines in 156 s
postgres: loaded 3,000,000 rows in 172 s
```

How the loader works:

- **Its input is JSON lines**, one per entity per day: `{"domain", "kind", "id", "date", "doc", "columns": {path:
  value}}`, the same as the Aerospike loader's. `make_data.py --jsonl` writes the samples; `bulk_trades.py --jsonl -`
  streams a generated book to standard output, piped into the loader (`PostgresLoader -` reads standard input), so a
  million-trade book never sits in a file of tens of gigabytes.
- **`COPY`, in parallel.** Rows are sent with PostgreSQL's `COPY` in batches of 2,000 on `--writers` connections at
  once (8). With a million trades the generator, not the database, sets the pace: 3,000,000 rows took 172 s, of which
  156 s was generating them.
- **The table is made as needed**: the schema, the partitioned table, its indexes and `entity_dates` when missing; a
  month's partition the first time a date in it arrives; a promoted column the first time a path arrives with a value
  (`ALTER TABLE … ADD COLUMN`, which rewrites nothing).
- **A day is replaced whole**: the first time the stream reaches a (kind, business date), that day's existing rows are
  deleted, so loading a day again never duplicates it. `--recreate` drops each domain's table first (the samples' load
  uses it).
- **At the end** each kind's dates and row counts are recorded in `entity_dates`, and every partition written is
  vacuumed and analysed: the visibility map lets type-ahead read a day's ids from the index alone, and the planner has
  fresh statistics.
- **A table of the earlier form** (not partitioned) is refused with `… is a plain table of the old layout: load with
  --recreate`.

**Your own loader** (an ETL job, a Kafka consumer) writes the same rows. In SQL, for one business day:

```sql
CREATE TABLE IF NOT EXISTS trading.entities_y2026m10 PARTITION OF trading.entities FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
BEGIN;
DELETE FROM trading.entities WHERE kind = 'trade' AND business_date = '2026-10-01';
COPY trading.entities (kind, id, business_date, doc, "tradeId", notional, mtm, book, "counterparty__id", "nettingSet") FROM STDIN;
INSERT INTO trading.entity_dates (kind, business_date, rows) VALUES ('trade', '2026-10-01', 1000000)
  ON CONFLICT (kind, business_date) DO UPDATE SET rows = EXCLUDED.rows, loaded_at = now();
COMMIT;
VACUUM (ANALYZE) trading.entities_y2026m10;
```

What matters: **the promoted columns hold exactly the document's values**, **a day is written whole and recorded in
`entity_dates`**, and **the partition is vacuumed after a large load**. Loading inside one transaction, as above, means
readers see the old day or the new one, never half.

## 5. How the connector reads

### 5.1 The catalogue

At start, and every `refresh-seconds` (60), the connector reads:

- `entity_dates`: each kind's business dates and the time of the last load (a few hundred rows). Without that table
  (an earlier table), the dates come from a skip scan of the index: one probe per date, never a scan of the rows.
- the table's columns (`information_schema`), to know which declared paths it can serve as columns.
- when a kind's newest day or the last load changed: that day's ids, from the `(kind, business_date, id)` index
  alone, into Drishti's in-memory type-ahead index. For an `effective` kind (reference data, a row when it changes) the
  ids of every day.

When the last load time changes, the days of columns in memory are dropped and the newest day's columns are read
again in the background.

### 5.2 Opening one entity

`TRD MX-30000017` on 2026-09-30: the snapshot date comes from memory (the kind's newest date on or before the date
asked, within `lookback-days`), then one query:

```sql
SELECT doc::text FROM trading.entities WHERE kind = 'trade' AND id = 'MX-30000017' AND business_date = '2026-09-30'
```

which PostgreSQL answers with one primary-key probe in the September partition. An `effective` kind reads the entity's
newest row on or before the date (`ORDER BY business_date DESC LIMIT 1`).

| | Time over HTTP |
|---|---|
| a trade's document, today / a past day | 43 ms / 30 ms |
| a trade's full view, first / again | 154 ms / 40 ms |

### 5.3 Type-ahead

From memory: the newest day's ids, sorted, searched by prefix. **46 ms** over HTTP with a million ids; no query per
keystroke.

### 5.4 A day's columns

Searches, pick lists, derived kinds, impact and reverse lookups need a few fields of every trade of a day. The connector
reads them as a column set:

1. **Cut the day into ranges.** `percentile_disc` over the day's ids (from the index) gives `scan-threads - 1`
   boundaries (4 ranges by default).
2. **Read the ranges at once**, each on its own pooled connection, streaming (`fetchSize` 20,000):

   ```sql
   SELECT id, mtm, notional, …, book, "nettingSet" FROM trading.entities
    WHERE kind = 'trade' AND business_date = '2026-09-30' AND id >= 'IMG-500375' AND id < 'MX-30083833'
   ```

   No document is read: the documents are in TOAST storage and these columns are in the row.
3. **Keep** it by memory (`columns-cache-mb`, 1024; a million trades of 19 fields is about 230 MB), for
   `columns-seconds` (300), dropped at once when a new load is seen. Repeated texts (books, desks, currencies) share one
   copy.
4. **Bound** the work: at most two days are read at a time.

The newest day is read in the background after each catalogue refresh; on the test server it was ready about 15 s
after start. Another business day is read when first asked for: **1.4 s** for a million trades, then kept.

### 5.5 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the day's promoted text columns: every value equal to
the id. A kind without promoted link columns is answered with SQL over the documents (`jsonb_path_exists`), reading at
most `max-load-rows` (200,000) of them. `reverse-index: false` turns reverse lookups off.

### 5.6 Derived kinds and impact

Desk P&L and impact (F8) work from the column set as on Delta Lake: desk P&L of a desk in **20 ms** once computed (it is
computed in the background after start and kept), impact of a netting set with 143,842 trades in **217 ms**.

## 6. Retention: dropping months

`--keep-months N` on the loader drops every monthly partition older than the newest N months and removes their dates
from `entity_dates`:

```
postgres: dropped trading.entities_y2019m09
```

Dropping a partition is a catalogue change: instant, no rows deleted, nothing to vacuum, the space returned to the
operating system at once. Run it with each load, or from cron. Seven years is `--keep-months 84`.

## 7. Seven years: sizing, and PostgreSQL with Delta Lake

Measured on the test database: 3,005,250 rows of the September partition take **11 GB**, of which the rows themselves
(promoted columns and pointers) are 823 MB and the rest is compressed documents and indexes: about **3.7 GB per million
trades a day**.

| History in PostgreSQL | Rows | Disk (about) | Partitions |
|---|---|---|---|
| 3 months | 63 million | 230 GB | 3 |
| 1 year | 250 million | 0.9 TB | 12 |
| 7 years | 1.76 billion | 6.5 TB | 84 |

Seven years in one PostgreSQL database is possible on a large server with fast storage, and every read path above
touches one partition, so the size of history does not slow reads. Backups, replicas and storage costs grow with it,
though. A common split:

**Recent history in PostgreSQL, the full seven years in Delta Lake.** Configure both connectors for the kind,
PostgreSQL first. A read, a search, a pick list, a derived kind and impact ask the connectors in order and take the
first that holds the business day: recent days from PostgreSQL, older ones from Delta Lake (see
[AEROSPIKE_CONNECTOR.md, section 7](AEROSPIKE_CONNECTOR.md#7-seven-years-sizing-and-aerospike-with-delta-lake) for the
configuration; replace `aerospike` by `jdbc` and its settings).

## 8. Memory in the Drishti server

| Held | Per million trades | Bounded by | Default |
|---|---|---|---|
| type-ahead ids (newest day) | about 150 MB | the newest day only | — |
| a day's column set (19 fields) | about 230 MB | `columns-cache-mb` | 1024 MB |
| each kind's dates | negligible | — | — |

Documents are not cached by the connector: PostgreSQL's own buffers keep what is read often. Measured server heap in
use with a million trades a day: **2.2 GB**. Give the server at least 8 GB of heap per million entities a day, and a
`pool-size` of at least `scan-threads` plus the reads you expect at once (the `postgres` profile uses 12 for the trading
store).

**PostgreSQL itself**: give it `shared_buffers` of a quarter of its memory and `effective_cache_size` of three
quarters; `maintenance_work_mem` of 1 GB speeds the vacuum after loads; `max_wal_size` of 16 GB avoids checkpoints in
the middle of a load. The test server ran with 12 GB of memory and those settings.

## 9. Measured results

On 2026-10-01, a developer workstation (24 cores), PostgreSQL 18 in Docker (12 GB memory limit, `shared_buffers`
3 GB), 1,000,000 trades a day over three business days plus the banking samples (3,017,910 rows), a Drishti server with
the `postgres` profile and `-Xmx8g`, times over HTTP:

| Question | Time |
|---|---|
| load the samples / 3,000,000 trade-days (streamed) | 2 s / 172 s |
| server start | 7 s |
| type-ahead `TRD CLY-40834` | 46 ms |
| open a trade (view), first / again | 154 ms / 40 ms |
| a trade's document, today / a past day | 43 ms / 30 ms |
| `TRD where mtm < -50m order by mtm` | 102 ms (2,041 matches of 1,000,000) |
| `TRD where currency = 'USD' and notional > 500m …` | 225 ms (13,523 matches) |
| `TRD book=BOOK-RATES-3` | 189 ms (70,649 matches) |
| pick list `TRD END-1100` | 106 ms |
| desk P&L, one desk / another | 20 ms / 20 ms |
| impact of a netting set (143,842 trades) | 217 ms |
| a search on another business day, first / again | 1.4 s / 90 ms |
| server heap in use | 2.2 GB |

Every search answered over all 1,000,000 trades (`partial: false`), with the same matches as the Delta Lake and
Aerospike connectors on the same book. Loading a smaller demo book is quick: the samples plus 20,000 trades a day for two
days load in 12 s.

## 10. Limits and trade-offs

- **The first search on a business day other than the newest waits for that day's columns** (1.4 s for a million
  trades); the newest day is read in the background.
- **Only promoted fields are fast.** A search on any other field reads documents (20,000 at most, `partial`).
- **Effective-mode kinds** are not read as columns (each entity's latest row would have to be resolved); they are small
  and read as documents.
- **A load is seen within `refresh-seconds`** (60): until the catalogue refresh, type-ahead and searches answer from
  the previous load.
- **A table being recreated** (`--recreate`) is briefly missing: reads during that moment fail and the catalogue keeps
  its last state until the next refresh. Recreate only for a fresh start.
- **Table and column names** must be plain SQL identifiers; the connector refuses anything else, so a setting cannot
  carry SQL.

## 11. Diagnosing

`GET /api/v1/admin/health` shows the connector and its catalogue:

```json
{"name": "trading-store", "health": "UP", "cache": {"ids": 1000000, "kinds": 1, "datesIndexed": 10, "columnSets": 1}}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: <driver message> (reconnecting)` | the database is unreachable | check `url`; connections reopen by themselves |
| `UP (not laid out as the pack declares: trade (0 of 19 columns); searches read documents)` | the table lacks the promoted columns (an earlier table, or written another way) | reload with `tools/load-postgres.sh`, or add the columns and fill them |
| `UP (catalogue not refreshed: …)` | the last catalogue refresh failed (database restarting, table being recreated) | it retries every `refresh-seconds`; the message clears on success |
| searches say `partial: true` | a field the query reads is not promoted | add it to `layout.<kind>.columns` and reload |
| type-ahead misses new trades | the load finished less than `refresh-seconds` ago | wait for the next refresh |
| loader: `… is a plain table of the old layout: load with --recreate` | a table of the earlier form | `--recreate` |
| a search on an older day is slow every time | `columns-cache-mb` too small for the days users move between | raise it (230 MB per million trades a day) |

## 12. Settings

On a `jdbc` connector in table mode (`drishti.sources.connectors.<name>.settings`):

| Setting | Default | Meaning |
|---|---|---|
| `url`, `user`, `password` | empty | the connection; keep the password in the environment |
| `table` | empty | `schema.table`; setting it turns table mode on |
| `pool-size` | `4` | connections kept; at least `scan-threads` plus the concurrent reads you expect |
| `kinds` | the kinds the table holds | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | the promoted paths ([section 3](#3-declaring-the-promoted-columns)) |
| `refresh-seconds` | `60` | how often the catalogue is read again |
| `scan-threads` | `4` | id ranges of a day read at once |
| `columns-cache-mb` | `1024` | memory for days of promoted columns |
| `columns-seconds` | `300` | how long a day's columns are kept (a new load clears them at once) |
| `max-load-rows` | `200000` | the most documents a reverse lookup reads for a kind without promoted link columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `kind-column`, `id-column`, `date-column`, `doc-column` | `kind`, `id`, `business_date`, `doc` | column names |
| `source-name` | `jdbc` | the name shown in provenance and Health |

Loader options (`PostgresLoader`, through `tools/load-postgres.sh`): `--user`, `--password` (default
`DRISHTI_PG_USER`/`DRISHTI_PG_PASSWORD`, else `drishti`), `--writers` (8), `--recreate`, `--keep-months N`.

## 13. Checklist for production

1. Declare `layout.<kind>.columns` for every large kind; load with the `columns` in each row (or write the columns in
   your own loader from the same document).
2. Load each business day whole, in one transaction, and record it in `entity_dates`; vacuum the partition after.
3. Choose the history PostgreSQL keeps and drop older months (`--keep-months`); keep the full history in Delta Lake.
4. Size the database for 3.7 GB per million entities a day, and the Drishti server for 8 GB of heap per million.
5. Set `pool-size` to at least `scan-threads` plus your concurrent reads.
6. Check Admin → Health, then measure on your data (`elapsedMs`, `scanned` and `partial` of a search).

## 14. Query mode: your own SQL

Table mode needs no SQL from you: the connector writes its own queries over the layout above. When your data already
lives in your own tables, use *query mode* instead: one SQL query per kind, in the connector's settings (in a pack's
`pack.yaml`, or in site configuration):

```yaml
connectors:
  trading-db:
    plugin: jdbc
    settings:
      url: ${DRISHTI_PG_URL}
      query.trade: >
        SELECT t.*, c.legal_name AS counterparty_name FROM trades t JOIN counterparties c ON c.id = t.counterparty_id
         WHERE t.trade_id = :id AND t.business_date = (SELECT MAX(business_date) FROM trades WHERE trade_id = :id AND business_date <= :asOf)
      query.counterparty: SELECT * FROM counterparties WHERE id = ?
```

Each kind has one query (one `query.<kind>` key); a connector can have as many as it serves kinds, and one query can
join as many tables as it needs. Query mode reads one entity at a time: it has no type-ahead, searches, columns or
reverse lookups, so it suits small kinds or a database you cannot reshape; a kind of millions a day belongs in table
mode. See [CONNECTOR_GUIDE.md](CONNECTOR_GUIDE.md#6-a-database-your-own-schema-jdbc-query-mode).
