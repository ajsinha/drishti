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
# The Redis connector at scale: a million trades a day, in memory

This document explains how Drishti serves a book of **1,000,000 trades every business day** from Redis: single-trade
reads in about 5 milliseconds over HTTP, type-ahead in tens of milliseconds, searches over every trade of a day in 80 to
250 milliseconds, and live updates pushed to open views. Redis keeps everything in memory, so it is the store for
**today and the last few days**; the years of history live in Delta Lake (or Iceberg) behind it, and Drishti asks that
store for any date Redis does not hold. The document covers the key layout, compression, the loader, what the connector
reads for each question, retention, sizing, measured results and limits.

Read it if you run Drishti on Redis, load Redis for Drishti, or change the connector. Every setting is in
[section 12](#12-settings). The same design for Aerospike and Delta Lake is in
[AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md) and [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md); the engine side
(columns, searches, derived kinds, impact) is shared and explained in
[DELTA_CONNECTOR.md, section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns).

## Contents

1. [The problem](#1-the-problem)
2. [The key layout](#2-the-key-layout)
3. [Declaring which fields are columns](#3-declaring-which-fields-are-columns)
4. [Loading](#4-loading)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Retention: TTL on every key](#6-retention-ttl-on-every-key)
7. [Sizing, Redis Cluster, and Redis with Delta Lake](#7-sizing-redis-cluster-and-redis-with-delta-lake)
8. [Memory in the Drishti server](#8-memory-in-the-drishti-server)
9. [Measured results](#9-measured-results)
10. [Limits and trade-offs](#10-limits-and-trade-offs)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)

Also: [Security: TLS and credentials](#security-tls-and-credentials) (before *As a connector file*).

---

## 1. The problem

Redis answers a key lookup in well under a millisecond, which makes it the natural home for the data users open most:
today's book. Three things make a million trades a day hard in Redis:

| Limit | Why it matters |
|---|---|
| **Memory** | Everything Redis holds is in RAM. A trade document is about 6.3 KB of JSON; a million of them is 6 GB a day before Redis's own overhead, so plain JSON would fit one day in a 12 GB instance and no more. |
| **No server-side scans with filters** | Redis cannot filter a million values by a field on the server. Reading one field of every trade of a day as a key per trade would be a million round trips (or a million-key `SCAN`), seconds of work for each search. |
| **No secondary indexes** | "Which business days does this trade have?" and "which trades exist on this day?" must be kept by the writer. |

The design answers each: documents are compressed about 7.4 times with a zstd dictionary trained on the kind's own
documents (about 0.8 KB a trade); the fields searches need are written **column-wise in chunks** of 10,000 values per
day (a whole day of nineteen fields is 16 MB, read with twenty pipelined commands); and the loader keeps each entity's
days and each kind's days beside the documents.

## 2. The key layout

A data domain (`trading`) uses these keys. Braces are Redis Cluster hash tags: keys with the same text between the
first `{` and `}` live in the same slot, on the same node.

| Key | Type | Holds |
|---|---|---|
| `trading:kinds` | SET | the kinds the domain holds |
| `trading:trade:days` | ZSET | the kind's business days (member and score `yyyyMMdd`) |
| `{trading:trade:days}:gens` | HASH | each day's current generation (`yyyyMMdd` → `<g>`); a day without one uses the keys without a suffix |
| `trading:trade:{MX-20000017}` | ZSET | the entity's business days (a hint: a day counts where its generation holds the entity's document) |
| `trading:trade:{MX-20000017}:20260930.<g>` | STRING | the entity's document that day in generation `<g>`, compressed (`…:20260930` without a generation) |
| `{trading:trade:20260930}:cols:<g>` | HASH | the day's ids and promoted fields in generation `<g>`, column-wise in chunks (`…:cols` without one) |
| `{trading:trade:20260930}:cols:<g>:staging` | HASH | a loader's copy, renamed over the one above when complete |
| `{trading:trade:20260930}:cols:loading:<load>` | SET | the ids a running load wrote documents for on the day, until the day's columns list them |
| `trading:loading` | SET | the days loads started and have not finished (`load TAB kind TAB yyyyMMdd TAB generation`) |
| `trading:loader:<load>` | STRING | present while that load runs (a 90-second expiry it renews) |
| `trading:trade:dict` | STRING | the id (hex) of the kind's current zstd dictionary |
| `trading:dict:<id>` | STRING | a zstd dictionary (about 112 KB) |
| `trading:updated` | STRING | when a loader last finished (epoch milliseconds) |
| `trading:changes` | channel | what a loader wrote: `kind TAB id TAB yyyyMMdd`, id `*` for a day's columns |

For one trade on one day (generation `9c41e07a2b5d13f0`, the load that last replaced the day):

```
{trading:trade:days}:gens              HASH   20260929 → 5e0a…, 20260930 → 9c41e07a2b5d13f0
trading:trade:{MX-20000017}            ZSET   20260929 → 20260929, 20260930 → 20260930
trading:trade:{MX-20000017}:20260930.9c41e07a2b5d13f0
                                       STRING 0x03 <4-byte dictionary id> <zstd frame>          about 820 bytes
                                              (6,300 bytes of JSON: {"tradeId":"MX-20000017","sourceSystem":"Murex",…})

{trading:trade:20260930}:cols:9c41e07a2b5d13f0
                                       HASH
  meta                 "version 1790857967807\nrows 1000000\nchunk-rows 10000\nchunks 100\n
                        text tradeId\ntext productType\n…\nnumber notional\nnumber mtm\n…\ntext sourceSystem\n"
  #id:0 … #id:99       the ids, sorted, 10,000 per chunk
  mtm:0 … mtm:99       10,000 doubles per chunk
  book:0 … book:99     10,000 texts per chunk (a dictionary of the chunk's distinct books, then an index per row)
  …                    one field per promoted path per chunk: 2,000 fields for nineteen paths
```

Why each part is shaped this way:

- **A key per entity per day.** Adding a day writes only that day's keys; a day expires on its own
  ([section 6](#6-retention-ttl-on-every-key)); a read is one `GET`.
- **The entity's days** (a small sorted set; for a few days Redis keeps it as a compact listpack of about 80 bytes)
  answer "the latest day on or before D" with `ZREVRANGEBYSCORE … LIMIT 0 1`, which is what an `effective` kind needs.
- **The hash tag on the id** puts an entity's days and its documents in one slot, so the documents of its candidate
  days are read with one `MGET`, also on Redis Cluster ([section 5.2](#52-opening-one-entity)).
- **Generations** make replacing a day atomic for readers. A load writes the day's documents and columns under a
  generation of its own (the load's id), which no reader looks at, then switches the day with one script that sets
  the day's generation and adds the day to the kind's days (`{trading:trade:days}:gens` and `trading:trade:days`
  share a slot: the first key's hash tag is the second key's name). A reader therefore sees the whole old day or the
  whole new one, never new documents with old columns. The replaced generation expires a little later
  (`--keep-replaced-seconds`, 120), so a reader that has not yet learned of the switch still reads a whole old day.
  A day with no generation recorded (loaded before generations, or only ever merged into) uses the keys without a
  suffix.
- **The kind's days** tell the connector which business days exist: a `snapshot` kind is read on its newest day on or
  before the date asked (within `lookback-days`) with a single `GET`, without consulting the entity's days.
- **The day's column hash** lets searches, pick lists, derived kinds, impact and reverse lookups read a day's few narrow
  fields with one `HMGET` per field, never a million keys. Its own hash tag keeps it and its staging copy in one slot,
  so a loader writes a generation's columns with one atomic `RENAME`.
- **The meta's version** (when the loader wrote it) lets the connector keep a day's columns in memory and check with
  one `HGET` whether they changed.

### 2.1 Documents: compression

The first byte of a stored document says how it is stored:

| First byte | Format |
|---|---|
| `{` or `[` | plain JSON (any other writer may store documents this way; they are read as they are) |
| `1` | Deflate (`java.util.zip`, no header) |
| `2` | a zstd frame |
| `3` | four bytes naming a zstd dictionary, then a zstd frame compressed with it |

Measured on 20,000 generated trade documents (6,318 bytes of JSON on average):

| Codec | Bytes a document | Smaller by | Time a document |
|---|---|---|---|
| Deflate level 6 | 1,910 | 3.3 times | 77 µs |
| zstd level 1 | 2,028 | 3.1 times | 11 µs |
| zstd level 3 | 1,991 | 3.2 times | 13 µs |
| zstd level 6 | 1,907 | 3.3 times | 39 µs |
| zstd level 3, 64 KB dictionary | 842 | 7.5 times | 9 µs |
| **zstd level 3, 112 KB dictionary** (the default) | **823** | **7.7 times** | **9 µs** |
| zstd level 6, 112 KB dictionary | 780 | 8.1 times | 34 µs |

A single 6 KB document holds little repetition of its own; what makes trades compressible is what they share with each
other (field names, product names, books, desks, curves, counterparties). A dictionary trained on 2,000 documents of the
kind (0.24 s) carries that shared part, so each document only stores what is its own. The real load of a million trades
stored 6,021 MB of JSON as 811 MB (7.4 times smaller). Decompression with a dictionary takes a few microseconds.

The dictionary is stored once in Redis (`trading:dict:<id>`, no TTL: documents written with it name it) and its id in
`trading:trade:dict`, so later loads reuse it. `--retrain` trains a new one (documents already written keep naming the
old, which stays). The connector reads a dictionary the first time a document names it and keeps it (at most 256).

### 2.2 A day's columns: chunks

Each chunk is a type byte followed by a zstd frame of its values:

| Type | Values | Encoding |
|---|---|---|
| `D` | numbers | the count, then the doubles' bytes transposed (every value's first byte, then every value's second byte …) so zstd finds the bytes amounts share; NaN for null |
| `T` | texts with few distinct values (books, desks, currencies, dates) | the count, a dictionary of the chunk's distinct values, then each row's index plus one as a varint (0 for null) |
| `P` | other texts (ids, names) | the count, then each value's length plus one as a varint (0 for null) and its UTF-8 bytes |

A day of a million trades with the trading pack's nineteen fields is 2,000 chunks totalling **15.8 MB**: about 16
bytes a trade for every searchable field. A path whose values are all numbers is a number column; anything else is
text (numbers in such a column are written as text, `42` without a fraction), as in the Delta Lake and Aerospike
connectors.

## 3. Declaring which fields are columns

The pack declares the fields to promote on the connector that stores the kind, exactly as for Delta Lake (see
[DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack) for how to choose them). The
`redis` profile (`SPRING_PROFILES_ACTIVE=redis`) switches the banking packs' connectors to the Redis plugin and keeps the
packs' settings, so the same declaration applies:

```yaml
# packs/trading/pack.yaml (generated; the pack's suggested template, written to config/connectors/trading-store.yaml at the first start)
connectors:
  trading-store:
    settings:
      domain: trading
      layout:
        trade:
          columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                    tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                    risk.dv01, sourceSystem]

# drishti-server application-redis.yaml (the profile; deprecated, see \"As a connector file\" below)
drishti:
  sources:
    connectors:
      trading-store: { plugin: redis, settings: { uri: "${DRISHTI_REDIS_URI:redis://localhost:6379}",
                                                   cluster: "${DRISHTI_REDIS_CLUSTER:false}" } }
```

The connector reads `layout.<kind>.columns`; the key prefix is the connector's `domain` (`trading`). The loader takes
the values from each line's `columns` object ([section 4](#4-loading)), which the generators fill from the same layout.
The `sort-by`, `file-rows` and `row-group-rows` keys of the layout concern files and do not apply to Redis.

## 4. Loading

`tools/load-redis.sh` loads the banking packs' data, and optionally a large generated book, through `RedisLoader` (in
the plugin):

```bash
tools/load-redis.sh redis://localhost:6379                                  # the samples: 1,791 documents x 10 days
tools/load-redis.sh redis://localhost:6379 --trades 1000000 --days 2       # and a book of a million trades, streamed
tools/load-redis.sh redis://localhost:6379 --merge                         # add to the days instead of replacing them
tools/load-redis.sh redis://localhost:6379 --ttl-days 7                    # every key expires 7 days after it is written
tools/load-redis.sh rediss://loader:secret@redis.internal:6380 --cluster   # TLS, credentials, Redis Cluster
```

```
redis: trading:trade: trained a 114,688-byte zstd dictionary on 2,000 documents
redis: loaded 17,910 rows into redis://localhost:26479 in 4 s; documents 54 MB as JSON, 7 MB stored (7.4 times smaller)
1,000,000 trade-days as JSON lines in 80 s
redis: trading:trade 2026-09-30: 1,000,000 rows, 19 columns in 2,000 chunks of 15,809,834 bytes
redis: loaded 1,000,000 rows into redis://localhost:26479 in 81 s; documents 6,021 MB as JSON, 811 MB stored (7.4 times smaller)
```

With `--trades`, the script merges the samples (`--merge`) and the bulk book, which starts with the 750 sample trades,
replaces the trade days it covers.

How the loader works:

- **Its input is JSON lines**, one per entity per day: `{"domain", "kind", "id", "date", "doc", "columns": {path:
  value}}`. `make_data.py --jsonl` writes the samples; `bulk_trades.py --jsonl -` writes a generated book to standard
  output, piped straight into the loader (`RedisLoader -` reads standard input), so a million-trade book never sits in a
  file of gigabytes.
- **Documents stream.** The main thread reads lines; worker threads (`--threads`, the cores less two) parse and compress
  each and send its commands without waiting: `SADD` of the id to the day's journal, then, once Redis has it, `SET` of
  the document (with `EX` when `--ttl-days` is given) and `ZADD` of the day to the entity's days (with `EXPIRE` and
  `ZREMRANGEBYSCORE` of days older than the retention). At most `--in-flight` lines (2,000) are unanswered at once; all
  commands go over one Lettuce connection, pipelined.
- **A kind's dictionary** is trained on its first `--dict-samples` documents (2,000), held back meanwhile, unless Redis
  already has one for the kind. A kind with fewer documents is trained on what it has (64 at least, else plain zstd).
- **Columns are gathered per kind and day** in compact arrays (`double[]` for numbers, shared strings for texts; about
  230 MB of loader heap per million trades with nineteen fields) and written when the input ends: sorted by id, cut into
  chunks of `--chunk-rows` (10,000), written to the staging hash, given the TTL, and renamed over the generation's
  hash in one step.
- **Days are replaced (the default since this release; loads merged before).** Each day the stream reaches then holds
  exactly the stream's entities, as with the PostgreSQL, DuckDB and file loaders. The documents and columns go under
  the load's own generation; once every day's columns are in, each day is switched to it in one step (one script),
  announced on `<domain>:changes`, and the replaced generation's documents and columns expire
  `--keep-replaced-seconds` (120) later; entities the stream does not carry lose the day from their days. **Readers
  see the old day or the new one, never a mix**, and a new day appears only once all of its documents and columns are
  in. The QA's case: the 10,000-trade book, then the 8,000-trade book, leaves 8,000 trades on the day (it left 10,000).
  Memory: until the replaced generation expires, the day is held twice.
- **A replacement that would drop most of a day is refused.** When the stream lacks more than `--max-drop-share`
  (0.5) of the entities a day holds, the load ends with an error before switching any day (`replacing the day would
  remove 9 of its 10 entities …; to add to the day, load with --merge; to replace it anyway, pass --force-drop`), and
  what it wrote is deleted. A part of a day loaded without `--merge` is therefore not mistaken for the whole book.
- **`--merge`** keeps the behaviour from before: the load writes into the day's current generation in place, and the
  day's columns keep every row Redis holds that this load did not write. A day can be loaded in parts (one booking
  system at a time, or intraday corrections), and loading the same lines twice changes nothing. A one-trade correction
  to a day of a million trades took 3.5 s, almost all of it reading and rewriting the day's 16 MB of columns. Merged
  documents are visible as they arrive (a correction reaches reads at once and searches when the load ends), and a
  trade dropped from the book stays in the day until the day expires. `--merge` and `--replace` together are refused;
  `--replace` alone states the default.
- **Do not run two loads of the same day at once**: the last switch wins, and a merge into a day that another load
  replaces meanwhile writes into the generation being retired.
- **A load that dies half way leaves no document the day's columns do not list.** Each id goes into the day's journal
  before its document is written; the day's journal goes once its columns are in place. The first time a load reaches
  a domain it deletes what dead loads left (entries whose loader key has expired): for a load that was replacing the
  day and never switched it, every document it wrote and its columns (readers never saw them); for a `--merge` load,
  the documents of journaled ids that the day's columns do not list, and that day in those entities' days. A load that
  fails in the process does the same for itself. A dead `--merge` load's documents of ids the columns do list were
  replaced in place and stay so until the day is loaded again. The journal costs one `SADD` per document and, until
  the day's columns are written, a set of the day's ids in Redis (about 60 MB per million trades).
- **Dates are guarded.** A row dated after tomorrow in the business zone (`--future-days`, `--zone`) is not loaded;
  the first few are named on standard error and the load ends with an error once the other rows are in.
- **`--publish`** announces every written entity on `<domain>:changes`, so open views of it refresh at once
  ([section 5.7](#57-live-updates)): as it is written with `--merge`, once its day is switched when replacing. It is
  meant for intraday updates (`--merge --publish`); a bulk load announces only its days.
- **`--codec`** chooses `zstd-dict` (default), `zstd`, `deflate` or `none`; `--level` the compression level (3).
- **Credentials**: in the URI (`redis://user:secret@host:6379`), or `DRISHTI_REDIS_USER` and `DRISHTI_REDIS_PASSWORD`;
  `rediss://` for TLS.

The loader needs heap for the days it gathers: `tools/load-redis.sh` gives it 6 GB, enough for a million trades over
two days in one run (each day's columns are held until the input ends).

**Your own loader** (a Kafka consumer, a batch job) writes the same keys: the document (compressed or plain JSON), the
`ZADD` to the entity's days, the day's column hash (the encoding is in `ColumnCodec` and `DayColumns` and can be called
from Java), the `ZADD` to the kind's days and the `SADD` to the domain's kinds. Writing into the day's current
generation (none: the keys without a suffix) merges; to replace a day atomically, write a new generation's keys and
switch the day as `RedisLoader` does (`HSET {<domain>:<kind>:days}:gens yyyyMMdd <g>` with the `ZADD`, in one
script). A writer that does not maintain the column hash still serves documents; searches over that day then read
documents instead.

**Redis must not evict.** Set `maxmemory` with `maxmemory-policy noeviction` (the default): a full instance then refuses
writes and the loader stops with the error, instead of Redis silently dropping trades.

## 5. How the connector reads

### 5.1 The catalogue

Every `refresh-seconds` (60), and whenever a loader announces a day, the connector reads:

- `trading:kinds`, each kind's days and the days' generations (a few small keys);
- `trading:updated` (when data last changed: the view's freshness);
- for each kind, the newest day's `meta`; only when its version changed, the day's `#id:*` chunks: every id, for
  type-ahead. A million ids are 100 chunks of about 90 KB, read with one `HMGET` and decoded in a few hundred
  milliseconds. An `effective` kind's ids are the union of all its days.

No document is read. The type-ahead index (Drishti's sorted `HitIndex`, see
[DELTA_CONNECTOR.md, section 6.4](DELTA_CONNECTOR.md#64-type-ahead)) is swapped in whole. Then the newest day's columns
are loaded in the background ([section 5.4](#54-a-days-columns)).

### 5.2 Opening one entity

`TRD MX-20000017` on 2026-09-30:

- **A `snapshot` kind** (trades): the kind's newest day on or before the date asked, within `lookback-days`, from the
  catalogue; then one `GET trading:trade:{MX-20000017}:20260930.<g>` in the day's generation. No document that day
  means the trade is gone that day (or expired), and the router asks the next store; the connector first asks once
  (`HGET {trading:trade:days}:gens 20260930`) whether the day was switched to a generation it has not seen yet.
- **An `effective` kind** (a record only when the entity changes), or a kind whose days are not known yet: the entity's
  latest days on or before the date (`ZREVRANGEBYSCORE … LIMIT 0 8`), then their documents in their days'
  generations with one `MGET` (same slot); the newest found wins. Two round trips.

The connector learns that a day was switched from the loader's announcement (at once), from its catalogue refresh, or
from a read that finds nothing in the generation it knows; until then it reads the whole old day, which stays for
`--keep-replaced-seconds`.

The document is decompressed (the dictionary from memory) and parsed. The provenance carries the business day it is
for, `live` when the date asked is the live one, and a generation that changes when the stored document does.

| | Time |
|---|---|
| a trade's document (`/entities/…/raw`), 200 trades | p50 5.5 ms, p99 12.6 ms over HTTP |
| a trade on a past business day, 200 trades | p50 4.1 ms, p99 9.7 ms |
| a trade's full view, first / again | 81 ms / 33 ms |

### 5.3 Type-ahead

From the ids of the newest day, sorted in memory: `TRD CLY-40834` answers in **26 ms** over HTTP (93 ms the first
time) with a million ids.

### 5.4 A day's columns

Searches, pick lists, derived kinds, impact and reverse lookups need a few fields of every trade of a day. The connector
reads them as a **column set**:

1. `HGET {trading:trade:20260930}:cols:<g> meta` in the day's generation: rows, chunks, columns, version.
2. If the generation and version are the ones held in memory, that column set answers.
3. Otherwise one `HMGET` per column (the ids and each promoted path), each naming all of the column's chunks, all sent
   at once: twenty commands, 16 MB, one round trip. Each column is decoded on its own virtual thread.

Column sets are kept by memory (`columns-cache-mb`, 1024; a million trades of nineteen fields is about 220 MB in the
server), and re-read only when the loader has rewritten the day (a new version). At most `heavy-reads` (2) days are
read at once, and the same day only once at a time. A day not in memory took **0.3 s** for a million trades (the first
search on another business day after a restart); the newest day is read in the background after each refresh and was
ready when the server finished starting.

A day the column hash does not hold (never loaded, or expired) answers "not held", and the router asks the next store
for the kind ([section 7](#7-sizing-redis-cluster-and-redis-with-delta-lake)). A field the pack declares but the day was
loaded without makes the search read documents instead, and Health says so.

### 5.5 Reverse lookups

"Which trades reference netting set `NS-SUMMIT-NY`?" comes from the day's promoted text columns: every value equal to
the id. A kind without promoted fields is answered from documents: the day's ids (from its column hash; for an
`effective` kind, every day's ids up to the date), each document read (256 at a time, pipelined) and scanned for the id,
at most `max-load-rows` (200,000). `reverse-index: false` turns reverse lookups off for a connector.

### 5.6 Derived kinds and impact

Desk P&L and impact (F8) work from the column set exactly as on Delta Lake: desk P&L in **14 ms**; impact of a netting
set with 143,842 trades in **0.25 s**.

### 5.7 Live updates

With `live: true` (the default) the connector is a live source. It subscribes to `<domain>:changes` on its own pub/sub
connection:

- `trade TAB MX-20000017 TAB 20260930`: an entity was written. If a view of it is open, the connector reads it again
  and pushes the new document; the view patches the cells that changed. Measured: a one-trade load with `--publish`
  reached an open view's stream as a patch of its MTM cell.
- `trade TAB * TAB 20260930`: a day's columns were written. The catalogue is refreshed at once (new days, new ids,
  re-read columns).

Every message, and `<domain>:updated` at each refresh, set the connector's `lastUpdate`, so views show how fresh the
data is and a connector's `stale-after` can warn. Pub/sub is fire-and-forget: a server that is down when a message is
published misses it, and the next refresh (`refresh-seconds`) catches up on days and ids; an open view of an entity
changed meanwhile refreshes when it is opened again.

## 6. Retention: TTL on every key

Load with `--ttl-days N` and every key the loader writes expires N days after it is written: the documents with `SET …
EX`, the entity's days and the day's column hash with `EXPIRE`. The entity's days also lose days more than N days
before `--as-of` (default: today in the business zone; never the newest date loaded) (`ZREMRANGEBYSCORE`), and so do
the days of each kind loaded (with their generations), once every day is written. When that would take more than `--max-drop-share` (0.5) of a
kind's days, nothing is taken from the kind's days and the load fails, unless `--force-drop`. An entity no longer
loaded disappears N days after its last write. No job runs; Redis expires keys by itself.

A day whose keys have expired but which a list still names answers "not held": a read finds no document, a search finds
no column hash, and the router asks the next store. The dictionaries have no TTL (a few hundred kilobytes a kind).

Choose N from memory ([section 7](#7-sizing-redis-cluster-and-redis-with-delta-lake)): with a million trades a day,
each business day kept costs about 1.05 GB of Redis memory. TTLs count calendar days from the write; to keep five
business days over weekends and holidays, use `--ttl-days 8`.

## 7. Sizing, Redis Cluster, and Redis with Delta Lake

### 7.1 Memory per million trades

Measured with Redis 8.2, a million trades a day, the default codec:

| Held | Per million trades per day |
|---|---|
| documents (about 811 bytes stored, plus Redis's key and allocation overhead) | about 1.0 GB |
| the day's column hash (19 fields) | 16 MB |
| the entities' days (one small sorted set per trade, for all days) | about 0.1 GB, once |
| **in total** (`used_memory` with two days loaded: 2.06 GB) | **about 1.05 GB per day** |

Plain JSON would need about 7 GB a day (6.3 KB a document plus overhead); zstd without a dictionary about 2.2 GB.

| Days kept in Redis | Memory (million trades a day) | With one replica |
|---|---|---|
| 1 business day | about 1.1 GB | 2.2 GB |
| 5 business days | about 5.3 GB | 10.6 GB |
| 10 business days | about 10.5 GB | 21 GB |
| 20 business days (a month) | about 21 GB | 42 GB |
| 250 business days (a year) | about 260 GB | 520 GB |
| 1,760 business days (seven years) | about 1.8 TB | 3.7 TB |

Leave headroom: Redis's fragmentation (`mem_fragmentation_ratio`, typically 1.1 to 1.3) and, when RDB or AOF persistence
is on, the copy-on-write of the background save (up to the write rate during the save) come on top of `used_memory`.
Keep `maxmemory` at about 70 % of the machine's RAM with persistence, 85 % without.

### 7.2 Redis Cluster

Set `cluster: true` (or give several URIs) on the connector and `--cluster` to the loader. Lettuce discovers the
topology from the seed nodes and refreshes it every minute and on every redirect. The layout is made for it:

- an entity's days and documents share a slot (tag `{MX-20000017}`), so the `MGET` of its documents runs on one
  node; documents of a day spread evenly over every node;
- a kind's days and their generations share a slot, so the loader's switch script runs on one node;
- a day's column hash is one key on one node (16 MB per million trades), read with twenty `HMGET`s;
- the catalogue keys (`kinds`, `<kind>:days`, `updated`) are small;
- pub/sub reaches every node's subscribers (`PUBLISH` is propagated across the cluster).

Three primaries with 16 GB each hold about 30 business days of a million trades a day with room to spare; add a replica
per primary for failover. The loader's switch script declares both keys it touches.

### 7.3 Recent days in Redis, history in Delta Lake

Redis holds days users open many times a day; seven years of history in memory is 1.8 TB of RAM for days rarely opened.
Configure both connectors for the kind, Redis first:

```yaml
drishti:
  sources:
    routes:
      trade: trading-store                     # asked first
    connectors:
      trading-store:                           # Redis: the last five business days, TTL-expired, live
        plugin: redis
        kinds: [trade]
        settings: { uri: "rediss://drishti:${REDIS_PASSWORD}@redis.internal:6380", domain: trading, lookback-days: 10,
                    "layout.trade.columns": "tradeId,productType,…" }
      trading-history:                         # Delta Lake: every business day for seven years
        plugin: delta
        kinds: [trade]
        settings: { root: "s3a://risk-lake/banking", domain: trading, lookback-days: 10,
                    "layout.trade.columns": "tradeId,productType,…" }
```

How Drishti uses them:

- **A read** tries Redis first, then every other connector serving the kind, until one holds the entity on that date.
  Today and recent days come from Redis in milliseconds; a date older than the TTL is not found there and is read from
  Delta Lake.
- **A search, a pick list, a derived kind, impact** asks the connectors that keep the needed fields as columns, in the
  same order, and takes the first that holds the business day: recent days from Redis, older ones from Delta Lake.
- **Type-ahead** merges both; the same id appears once.
- **Live views** tick from Redis (`--publish` on the intraday writer).

Load each business day into both (the same JSON lines feed `RedisLoader` and the Delta Lake writer), or write Delta Lake
at end of day and Redis intraday.

## 8. Memory in the Drishti server

| Held | Per million trades | Bounded by | Default |
|---|---|---|---|
| type-ahead ids | about 150 MB | the newest day's ids | — |
| a day's column set (19 fields) | about 220 MB | `columns-cache-mb` | 1024 MB |
| zstd dictionaries | about 112 KB each | 256 dictionaries | — |
| the kinds' days | negligible | — | — |

Single documents are not cached by the connector (Redis answers in well under a millisecond). Measured server heap in
use with a million trades a day: **1.46 GB** after the run of measurements (two days' column sets in memory). Give the
server at least 4 GB of heap per million entities a day, 8 GB to keep several days' columns.

## 9. Measured results

**Measured at a million trades a day, by hand, on 2026-10-01.**

On 2026-10-01, a developer workstation (24 cores), one Redis 8.2 instance in Docker (`maxmemory 11gb`, no persistence),
1,000,000 trades a day over two business days plus the banking samples (3,018,045 keys, `used_memory` 2.06 GB), a
Drishti server with the `redis` profile and `-Xmx8g`, times over HTTP:

| Question | Time |
|---|---|
| load the samples (17,910 documents) | 4 s |
| load 1,000,000 trade-days / 2,000,000 trade-days (streamed from the generator) | 81 s / 97 s |
| a one-trade intraday correction with `--merge --publish` (merging the day's columns) | 3.5 s |
| server start, with a million ids and the newest day's columns ready | 9.6 s |
| type-ahead `TRD CLY-40834`, first / again | 93 ms / 26 ms |
| open a trade (view), first / again | 81 ms / 33 ms |
| a trade's document, today / a past day (p50 of 200) | 5.5 ms / 4.1 ms |
| `TRD where mtm < -50m order by mtm`, first / again | 144 ms / 81 ms (2,041 matches of 1,000,000) |
| `TRD where currency = 'USD' and notional > 500m …`, first / again | 257 ms / 201 ms (13,523 matches) |
| `TRD book=BOOK-RATES-3`, first / again | 244 ms / 130 ms (70,649 matches) |
| pick list `TRD END-1100`, first / again | 116 ms / 89 ms |
| desk P&L, one desk / another | 14 ms / 14 ms |
| impact of a netting set (143,842 trades) | 248 ms |
| a search on another business day, first after a restart / again | 305 ms / 112 ms (2,039 matches) |
| server heap in use | 0.97 GB after start, 1.46 GB after the measurements |

Every search reported `partial: false` and `scanned: 1000000`.

**Client choice, measured.** Lettuce and Jedis 5.2 against the same instance (50,000 stored trades, 820-byte values):

| | Lettuce 6.8 (one connection) | Jedis 5.2 |
|---|---|---|
| `GET` from 64 threads | 228,000 a second, p50 0.27 ms, p99 0.59 ms (virtual threads) | 144,000 a second, p50 0.41 ms, p99 0.94 ms (a pool of 64, platform threads) |
| `SET`, pipelined | 287,000 a second (2,000 in flight from one thread) | 1,073,000 a second (pipelines of 2,000) |

Reads decide: the server reads from many virtual threads at once, and Lettuce multiplexes them over one connection
without a pool or pinned carrier threads (Jedis blocks inside `synchronized` code, which pinned a virtual thread's carrier
before Java 24). Jedis pipelines write faster from one thread, but the loader is bound by its input (the generator wrote
12,000 to 20,000 trades a second, and the loader kept up) and sends two commands a trade without a TTL, four with one:
at most about 80,000 commands a second, under a third of what Lettuce sustains. Lettuce also gives Redis Cluster, TLS and pub/sub with one API.

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, Redis 8.2 in Docker capped at 2 GB (`maxmemory` 1.5 GB). Medians of 25 requests;
every search exact (`partial: false`, `scanned` equal to the trades a day). The last column carries a straight-line fit
(R² beside it) to a million trades a day: **an extrapolation from the measured points, not a measurement**; "flat" means
the measure does not grow with the book. The million-trade measurement above is the real figure; where the line and it
differ, trust the measurement.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 7.2 | 7.3 | 10.4 | 0.33 | 56.5 ms (weak fit) |
| open a trade (view), first time (ms) | 6.0 | 6.9 | 6.6 | 0.01 | 8.7 ms (weak fit) |
| a trade's document, today (ms) | 1.1 | 1.5 | 2.3 | 0.98 | 33.0 ms |
| a trade's document, a past day (ms) | 1.1 | 1.6 | 6.7 | 0.91 | 137 ms |
| `TRD where mtm < -50m order by mtm` (ms) | 2.0 | 3.1 | 7.9 | 0.97 | 155 ms |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 1.7 | 7.2 | 10.6 | 0.94 | 225 ms |
| `TRD book=BOOK-RATES-3` (ms) | 1.5 | 5.7 | 8.4 | 0.92 | 154 ms |
| pick list `TRD END-1100` (ms) | 0.6 | 1.4 | 2.9 | 0.94 | 50.1 ms |
| desk P&L `DESK-RATES` (ms) | 5.5 | 3.5 | 4.9 | 0.05 | 15.9 ms (weak fit) |
| impact of `NS-SUMMIT-NY` (ms) | 25.0 | 31.4 | 30.9 | 0.59 | 231 ms |
| a search on another day, first (ms) | 17.6 | 9.4 | 46.2 | 0.66 | 702 ms |
| a search on another day, again (ms) | 1.9 | 3.5 | 17.6 | 0.91 | 378 ms |
| load (the whole script) (s) | 13.4 | 17.2 | 20.2 | 0.96 | 169 s |
| Redis `used_memory` (MB) | 46.2 | 97.9 | 183 | 1.00 | 3,446 MB |
| server live heap after a full GC (MB) | 106 | 116 | 132 | 1.00 | 754 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 10. Limits and trade-offs

- **Memory is the limit.** About 1.05 GB per million trades per day kept, plus replicas and headroom
  ([section 7](#7-sizing-redis-cluster-and-redis-with-delta-lake)). Keep days users open often; put the rest in Delta
  Lake.
- **Only promoted fields are fast.** Searches on other fields read documents (20,000 at most, `partial`).
- **Effective-mode kinds** are not read as columns (each entity's latest day would have to be resolved); they are small
  and read as documents. Their type-ahead reads every day's ids.
- **A day's columns are written at the end of a load.** A replacing load's documents are in Redis while it loads,
  under a generation readers do not look at; readers switch to the whole day at the end. With `--merge` a correction
  to an existing day is visible to reads at once and to searches when that load ends. An intraday writer should load
  in batches with `--merge` (one per minute, say): each batch rereads and rewrites the day's columns (about 3 s per
  million trades).
- **Replacing a day holds it twice for a while** (`--keep-replaced-seconds`, 120): size Redis for one more day than
  you keep, or lower it (0 deletes the replaced generation at once; a reader that has not seen the switch then finds
  the old day gone and reads the new one).
- **The loader's heap grows with the days in one input** (about 230 MB per million trades per day): load a long history
  one day per run.
- **Pub/sub is not durable.** A missed change is caught at the next refresh for days and ids; an open view of a changed
  entity refreshes when reopened.
- **A dictionary is per kind.** Documents of a kind whose shape changes a lot compress less until `--retrain`.
- **Redis Cluster is supported but was not part of the measured run** (one instance).

## 11. Diagnosing

`GET /api/v1/admin/health` shows the connector and its catalogue:

```json
{"name": "trading-store", "health": "UP", "live": true, "lastUpdate": "2026-10-01T12:32:48.435Z",
 "cache": {"kinds": 1, "datesIndexed": 10, "ids": 1000000, "columnSets": 2, "columnSetsMb": 438, "dictionaries": 1,
           "liveViews": 0}}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| the plugin is listed as installed but not configured | a Redis plugin on the class path with no `uri` (for example no `redis` profile) | nothing to do: it stays idle; set `uri` on a connector to use it |
| `DOWN: cannot reach Redis at redis://…` | Redis unreachable, wrong URI or credentials | check `uri`, `user`/`password`, TLS (`rediss://`); the connector retries every refresh |
| `DOWN: lost the connection to Redis at redis://… (reconnecting)` or `DOWN: reads fail: …` | Redis went away or rejects commands; health says so at once, not at the next refresh | the connection reconnects by itself; while health is `DOWN` reads fail at once (`fail-fast`) instead of each waiting `timeout-ms`, and one check every `recheck-ms` (1 s) asks Redis again: health is `UP` and reads resume as soon as it answers |
| `…; reads fail at once until it answers (checked every 1000 ms)` | the breaker is open: Redis is known to be down | users see `DRS-1003 … the Redis store is not reachable …` at once and searches are `partial`; fix Redis, the connector recovers by itself |
| loader: `replacing the day would remove N of its M entities …` | the stream lacks most of a day Redis holds: a part of a day loaded without `--merge`, or a truncated input | `--merge` to add to the day; `--force-drop` if the day really shrank |
| `UP (not laid out as the pack declares: trade (12 of 19 columns) …)` | the day was loaded without some declared fields | load lines whose `columns` carry every declared path |
| a trade is in type-ahead but does not open on a date | its key for that day expired or was never loaded | `ZRANGE trading:trade:{ID} 0 -1`, `HGET {trading:trade:days}:gens yyyyMMdd`, `EXISTS trading:trade:{ID}:yyyyMMdd.<g>`; with Delta Lake behind, older days come from there |
| a new day is not served | its load has not finished (columns are written at the end) | wait for the loader's last line; the day joins `trading:trade:days` then |
| `zstd dictionary … is missing` | a dictionary key was deleted | never delete `<domain>:dict:*`; reload the affected days |
| searches say `partial: true` | a field the query reads is not promoted | add it to `layout.<kind>.columns` and reload |
| loader: `OOM command not allowed when used memory > 'maxmemory'` | Redis is full | shorter `--ttl-days`, more memory, or more shards |
| loader: `OutOfMemoryError` | too many days in one input | load fewer days per run, or more `-Xmx` |
| open views do not tick | the writer does not `--publish`, or `live: false` | publish changes on `<domain>:changes` |

Useful commands: `INFO memory` (`used_memory`, `mem_fragmentation_ratio`), `MEMORY USAGE <key>`,
`HGETALL {trading:trade:days}:gens`, `HGET {trading:trade:20260930}:cols:<g> meta`, `ZRANGE trading:trade:days 0 -1`,
`SUBSCRIBE trading:changes`.

## Security: TLS and credentials

Redis is reached over TLS with the shared `tls.*` settings ([TLS.md](TLS.md)): a private CA, a PKCS12 or JKS store, a client
certificate for a server that runs with `tls-auth-clients yes`, protocol versions and cipher suites. The address decides whether
TLS is used: **`rediss://`** (two s) is TLS, `redis://` is plain text. For a cluster, every seed URI is `rediss://`.

| Setting | Meaning for Redis |
|---|---|
| `uri: rediss://host:6380` | TLS on. With no `tls.*` the server certificate is checked against the JVM's authorities and the host name. |
| `tls.ca-file` or `tls.truststore` (+ `tls.truststore-password`) | Trust a private CA. |
| `tls.cert-file` + `tls.key-file` (+ `tls.key-password`), or `tls.keystore` (+ `tls.keystore-password`) | The client certificate. |
| `tls.verify-hostname: false` | Check the certificate chain but not the name (logs a warning at every start). |
| `tls.insecure-trust-all: true` | Check nothing. Refused unless `DRISHTI_ALLOW_INSECURE_TLS=true` is in the environment. |
| `tls.protocols`, `tls.cipher-suites` | Handed to the client's TLS engine. |
| `user`, `password` | The Redis ACL user and its password (`${ENV}` placeholder or `file:` reference), as before. |

`tls.*` against a `redis://` URI is a start-up error, not a silent downgrade (`tls.enabled is true but uri is not rediss://`),
and so is a missing or unreadable file (it names the setting and the file). The earlier way, a Lettuce option in the URI such as
`rediss://host:6380?verifyPeer=CA`, still works and logs a deprecation warning that names `tls.verify-hostname`; the `tls.*`
settings are the supported form. The load tool (`RedisLoader`, the command line) takes the URI only and uses the JVM's trust:
for a private CA, run it with `-Djavax.net.ssl.trustStore=...`.

### A complete file: mutual TLS, private CA

`config/connectors/redis-tls.yaml` (the same file is in `config/connectors.examples/other/`):

```yaml
plugin: redis
kinds: [trade]
description: Trades in Redis over mutual TLS
settings:
  domain: trading
  uri: rediss://redis.example.com:6380
  user: drishti
  password: ${DRISHTI_REDIS_PASSWORD}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
    cert-file: /etc/drishti/tls/client.pem
    key-file: /etc/drishti/tls/client.key
```

The server side (the configuration used by the integration test, `RedisTlsTest`, against `redis:8.2`):

```
redis-server --port 0 --tls-port 6380 \
  --tls-cert-file /tls/server.pem --tls-key-file /tls/server.key --tls-ca-cert-file /tls/ca.pem \
  --tls-auth-clients yes
```

The server certificate must name the host Drishti connects to (a `subjectAltName` of `DNS:redis.example.com`); `tls-auth-clients
no` turns the client-certificate requirement off, and then the `cert-file`/`key-file` lines are not needed.

### Checking it from the command line

```
openssl s_client -connect redis.example.com:6380 -CAfile /etc/drishti/tls/ca.pem \
  -cert /etc/drishti/tls/client.pem -key /etc/drishti/tls/client.key -verify_return_error < /dev/null
redis-cli --tls --cacert /etc/drishti/tls/ca.pem --cert /etc/drishti/tls/client.pem --key /etc/drishti/tls/client.key \
  -h redis.example.com -p 6380 --user drishti --askpass ping
```

`Verify return code: 0 (ok)` from the first and `PONG` from the second mean the server and the files are right; what is left is
Drishti's side. Health then reads (captured from the integration test; the sentence about expiry appears once a certificate
has less than 30 days left):

```
UP (TLS certificate CN=drishti (tls.cert-file) expires in 19 days (2026-10-30))
```

### Common errors

These are the messages from a real Redis 8 in the integration test.

| Message | Cause and fix |
|---|---|
| `tls.enabled is true but uri is not rediss:// (Redis over TLS is rediss://host:6380): redis://h:6379` | The URI says plain text. Use `rediss://` and the TLS port. |
| `tls.* is set but uri is not rediss://: use rediss://host:port for Redis over TLS` | `tls.*` keys with a `redis://` URI. |
| `tls.ca-file '/etc/drishti/tls/nope.pem': file not found` | A wrong path; the same message form for every `tls.*` file ([TLS.md, section 8](TLS.md#8-start-up-checks-and-their-messages)). |
| `RedisConnectionException: Unable to connect to localhost/<unresolved>:33157` caused by `SSLHandshakeException: (certificate_required) Received fatal alert: certificate_required` | The server asks for a client certificate (`tls-auth-clients yes`) and none was configured. Set `tls.cert-file` and `tls.key-file`. |
| the same, caused by `(certificate_unknown) PKIX path building failed: ... unable to find valid certification path to requested target` | The server certificate is not signed by a CA in `tls.ca-file`. Put the issuing CA in the bundle. |
| the same, caused by `(certificate_unknown) No subject alternative names matching IP address 127.0.0.2 found` | The certificate does not name the address in the URI. Connect by a name it carries, or re-issue it with the name as a SAN. |
| health `DOWN` with `Connection refused` | The port speaks plain text (`port`) or nothing: with `--port 0` only the `tls-port` listens. |

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/trading-store.yaml
plugin: redis
kinds: [trade]
description: Trading documents in Redis
settings:
  domain: trading
  uri: ${DRISHTI_REDIS_URI:redis://localhost:6379}
  cluster: ${DRISHTI_REDIS_CLUSTER:false}
  layout:
    trade:
      columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                risk.dv01, sourceSystem]
```

The `redis` profile (`SPRING_PROFILES_ACTIVE=redis`) did this for the banking stores and still works, but is deprecated. A file replaces the pack's template wholesale, so it repeats the promoted `layout` the pack declared ([section 3](#3-declaring-which-fields-are-columns)).

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). Ready-made files for this store are in [`config/connectors.examples/redis/`](../../config/connectors.examples/redis).

## 12. Settings

In the connector file's `settings:` (see [As a connector file](#as-a-connector-file)):

| Setting | Default | Meaning |
|---|---|---|
| `uri` | none (required) | Lettuce URI, e.g. `redis://localhost:6379`; `rediss://` for TLS, `redis://user:secret@host:port/db` for credentials; several comma-separated for Cluster seed nodes |
| `cluster` | `false` | `true` for Redis Cluster (also implied by several URIs) |
| `user`, `password` | none | credentials (override the URI's) |
| `domain` | `drishti` | the data domain: the key prefix (`trading:…`) |
| `kinds` | what `<domain>:kinds` lists | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | the promoted fields, read from the day's column hash ([section 3](#3-declaring-which-fields-are-columns)) |
| `refresh-seconds` | `60` | how often the kinds' days, the ids and `updated` are re-read (also on every announced day) |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `heavy-reads` | `2` | days' column sets read at once |
| `max-load-rows` | `200000` | the most documents read for a reverse lookup on a kind without promoted fields |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `live` | `true` | subscribe to `<domain>:changes` and push changed entities to open views |
| `timeout-ms` | `5000` | connect and command timeout |
| `fail-fast` | `true` | while health says Redis is down, reads and column reads fail at once (`DRS-1003`, a message without host or credentials) instead of each waiting `timeout-ms`; `false` to always wait |
| `recheck-ms` | `1000` | how often a down Redis is asked again (`PING`); reads resume at its first answer |
| `source-name` | the connector's name | the name shown in provenance and Health |

`RedisLoader <file|-> [uri]` options:

| Option | Default | Meaning |
|---|---|---|
| `--cluster` | off | Redis Cluster |
| `--ttl-days N` | none (no expiry) | every key expires N days after it is written; the kinds' days keep N days back from `--as-of` |
| `--replace` | on (the default) | each day loaded holds exactly the stream's entities, switched atomically |
| `--merge` | off | add to each day instead: keep the entities the stream does not carry, write in place (before this release, the default) |
| `--keep-replaced-seconds N` | `120` | how long a replaced day's generation stays readable (0: deleted at once) |
| `--as-of yyyy-MM-dd` | today in the business zone | the date retention counts back from |
| `--future-days N` | `1` | how many days after today a business date may be |
| `--zone Z` | `DRISHTI_BUSINESS_ZONE`, else `America/New_York` | the business zone |
| `--max-drop-share F` | `0.5` | the largest share of a kind's days one run may drop, and of a day's entities a replacement may drop |
| `--force-drop` | off | drop even more than that |
| `--codec` | `zstd-dict` | `zstd-dict`, `zstd`, `deflate` or `none` |
| `--level N` | `3` | compression level |
| `--chunk-rows N` | `10000` | values per column chunk |
| `--in-flight N` | `2000` | lines sent and not yet answered |
| `--threads N` | cores less two | parsing and compressing threads |
| `--publish` | off | announce every written entity on `<domain>:changes` |
| `--retrain` | off | train new dictionaries even where Redis has one |
| `--dict-samples N` | `2000` | documents a kind's dictionary is trained on |
| `--dict-kb N` | `112` | dictionary size |

## 13. Checklist for production

1. Declare `layout.<kind>.columns` for every large kind; load lines whose `columns` carry those fields.
2. Size Redis for the days you keep (about 1.05 GB per million trades per day, plus replicas and 30 % headroom), set
   `maxmemory` and keep `maxmemory-policy noeviction`.
3. Choose the days Redis keeps and load with `--ttl-days`.
4. Put a Delta Lake (or Iceberg) connector behind it for the full history, with the same layout.
5. Use TLS (`rediss://`) and an ACL user with only the commands the connector needs (`GET`, `MGET`, `HGET`, `HMGET`,
   `HGETALL`, `SMEMBERS`, `ZRANGE`, `ZREVRANGEBYSCORE`, `PING`, `SUBSCRIBE`) and a separate one for the loader (which
   also needs `EVAL` for the day switch).
6. For live views, have the intraday writer load in small batches with `--merge --publish`; load each full book
   without `--merge` (each day replaced).
7. Give the server at least 4 GB of heap per million entities a day.
8. Check Admin → Health, then measure on your data (`elapsedMs`, `scanned` and `partial` of a search).

---

## Appendix: quick start

Moved here from the former connector guide. From nothing to a running view:

```bash
docker run -d --name redis -m 2g -p 6379:6379 redis:8 --maxmemory 1gb
tools/load-redis.sh redis://localhost:6379                         # the samples, 10 business days
tools/load-redis.sh redis://localhost:6379 --trades 10000          # and 10,000 trades a day for 3 days
SPRING_PROFILES_ACTIVE=redis DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

Use a free host port if a Redis of yours already listens on 6379 (`-p 16379:6379`, and the URI to match). Health
should show `UP` with the catalogue counts; see [Diagnosing](#11-diagnosing) otherwise.
