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
# Demo data: the test data, the demo lakes, and every other store

> **For staging, small setups and proofs of concept.** The loaders here, `drishti.py lake demo`, `lake build`, `data ingest` (and
> `--watch`) and `pack make` are not a production ETL; Drishti does not ingest your data. Production data is loaded by your own ETL,
> which ends by telling Drishti the batch landed: [DATA_LOADS.md](../guides/DATA_LOADS.md).

Drishti's demo data comes in three forms, all production-shaped (UPPER_SNAKE attributes, as the source systems name them), all
fictitious, all deterministic:

| Form | Where | What it is | Built by |
|---|---|---|---|
| **The test data** | `data/testdata/` (committed, about 24 MB of `.jsonl.gz`) | seven business days of every dataset the seven packs read: 5,000 trades a day per source system (ENDUR, CALYPSO, MUREX), counterparties, market data, agreements, netting sets, risk results; a first-day snapshot and each later day's changes | `tools/testdata/make_testdata.py` (`--check` says whether the files are current) |
| **The demo lakes** | `data/delta/` (generated, never committed) | the test data as Delta tables, one per dataset, seven daily snapshots each: what the packs' connectors read | `drishti.py lake demo` (one command, about a minute) |
| **The samples** | `config/packs/<pack>/samples/<kind>.jsonl` (committed) | a few hundred documents a pack, the same shape, that the `demo` source serves live and ticking, with no store at all | `tools/packgen/banking/make_data.py` and each pack's generator |

The other stores (PostgreSQL, DuckDB, Iceberg, MongoDB, Redis, Aerospike, JSON-lines files) are loaded with the samples (ten business
days) and, for scale tests, a generated trade book: the scripts in [the other stores](#postgresql).

## The test data and the demo lakes (the default)

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

(the IDE run configuration **Build demo lakes**). On a fresh clone: **53 s, 807 MB peak memory, 64 tables**, seven business days
ending on today's business date (the test data's 2026-09-22 to 2026-09-30, moved; `--as-of data` keeps them). The trades go to one
table per source system, `data/delta/trading/trade_endur`, `trade_calypso` and `trade_murex`, partitioned by business date and
`PRODUCT_TYPE`, each read by its own connector (`trade-endur`, `trade-calypso`, `trade-murex`) and served together as one kind,
`trade`; every other dataset goes to `data/delta/<domain>/<kind>/`, partitioned by business date. Datasets of static reference data
(world-economy, news-and-markets) are built from those packs' samples, the same on every day. The real output, the options
(`--lake`, `--only`, `--with-real`, `--server`, `--no-signal`) and what a running server is told are in
[GETTING_STARTED.md, Step 5](../guides/GETTING_STARTED.md#step-5--build-the-demo-lakes); the datasets, one row each (key, connector,
table, partitions, documents on day 1, changes a day, size), are in [data/testdata/README.md](../../data/testdata/README.md).

| What | Value |
|---|---|
| Business days | 7 (USNY calendar) |
| Trades | 5,000 a day per source system, 15,000 in all; about 3–7% amended each day (`TRADE_VERSION` moves) |
| Source systems | `ENDUR` (ids `1000001` …), `CALYPSO` (`CLY-3000001` …), `MUREX` (`MX-20000001` …): ids unique across them |
| Tables | 64: 3 trade tables, 46 test-data datasets of the five banking packs, 15 reference tables of world-economy and news-and-markets |
| Disk | about 243 MB |
| Counterparties | fictitious look-alikes (`CP-NORTHPLAINS`, `CP-VAULTGUARD`, …), with UEN, country of risk, legal entity, agreements and netting sets holding their trades |

Re-running `lake demo` rebuilds every table it writes (the folder is removed first) and is safe while a server runs: the server gets
the data-load signal for each table and date, or, when none is reachable, finds `data/bi-store/lake-rebuilt.json` at its next start
and clears its stored BI columns and rollups; a running server also sees each rebuilt Delta date by itself. The test data itself is
regenerated with `python3 tools/testdata/make_testdata.py` (deterministic; `tools/testdata/test_testdata.py` guards it).

## The sizes

| Size | Command | What you get | When to use it |
|---|---|---|---|
| **samples** | none: the `demo` source serves them | the packs' samples (765 trades, 2,571 documents in the five banking packs), today only, ticking | a first look with no store, writing a pack |
| **the demo lakes** | `drishti.py lake demo` | 15,000 trades a day and every other dataset, seven business days | demos, QA, the tutorials |
| **a store of your choice, small** | `tools/load-<store>.sh` | the samples as ten business days ending 2026-09-30 | trying PostgreSQL, DuckDB, … with the same data |
| **large** | `tools/load-<store>.sh … --trades 1000000 --days 3`, or `tools/samplegen/bulk_trades.py` | a million trades a day for 3 days | scale tests, performance measurements |

`--trades N` (the `load-*.sh` scripts) adds a book of N trades a day for `--days D` business days (default 3). The book's first trades
are the samples; the rest are clones of them, numbered from a higher range of each system's ids (`MX-30000000` …, `CLY-4000000` …),
keeping their template's product, portfolio, counterparty and legal entity (so every link opens) and scaling the amounts
(`NOTIONAL`, `QUANTITY`, `AMOUNT` at every level) by a factor between 0.2 and 5. Every size is reproducible: the same command writes
the same data. On a laptop keep books to about 10,000 trades; a million trades a day is for a server.

## Delta Lake

The demo lakes above are the normal way. The older script writes the samples as ten days of history, with an optional book:

```bash
tools/load-delta.sh                              # the samples as 10 business days: data/delta
tools/load-delta.sh --trades 10000               # and 10,000 trades a day for 3 days
tools/load-delta.sh /tmp/lake --trades 20000     # somewhere else
```

It runs `make_data.py --lake` (which rewrites the whole lake the packs read, in the layout each declares) and then
`tools/samplegen/bulk_trades.py`, which replaces **one source system's** trade table (`trade_murex`, or `--system CALYPSO`) with the
book and leaves every other table as it is. `bulk_trades.py` on its own: about 1.7 KB per trade per day on disk (a million trades × 3
days is about 5.1 GB), about 30 seconds per million trade-days on 24 cores. The server reads `data/delta` (or `DRISHTI_DELTA_ROOT`) and
picks a new load up within a minute; no restart is needed. The layout: [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md).

For your own export (a large JSON array or JSON Lines of real trades), `drishti.py lake build` streams it into a Delta table with flat
memory, partitions and promoted columns ([TOOLS.md](../guides/TOOLS.md)).

## PostgreSQL

```bash
docker compose -f deploy/compose.data.yaml up -d postgres                                       # or your own server
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti                                 # the samples, 10 days
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 10000                  # and a book
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

The profile is deprecated; the same connectors as files are in `config/connectors.examples/postgres/` (copy them to
`config/connectors/`; the example `trading-store.yaml` is one trade connector, see
[CONNECTOR_FILES.md, section 4](CONNECTOR_FILES.md#4-examples-one-per-store)). Measured in an earlier release: the samples in 2 s, a
million trades × 3 days in about 3 minutes and 11 GB. User and password come from `--user`/`--password`, else
`DRISHTI_PG_USER`/`DRISHTI_PG_PASSWORD`, else `drishti`. The samples' load recreates each domain's table; a running server sees a new
load within a minute. Layout and design: [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md).

## Apache Iceberg

```bash
tools/load-iceberg.sh ./data/iceberg                       # the samples
tools/load-iceberg.sh ./data/iceberg --trades 10000        # and a book, for a laptop
SPRING_PROFILES_ACTIVE=iceberg DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

Design: [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md). (Not on Windows: it needs Hadoop's file systems.)

## DuckDB

```bash
tools/load-duckdb.sh data/duckdb/drishti.duckdb                              # the samples
tools/load-duckdb.sh data/duckdb/drishti.duckdb --trades 10000 --days 3      # and a book, for a laptop
SPRING_PROFILES_ACTIVE=duckdb DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

No server to run: one file holds every data domain, and the server reads it in-process. Each load writes a new file and renames it
over the old one, so it can run while the server answers; the server picks it up within 10 s. The database defaults to
`DRISHTI_DUCKDB_PATH`, else `data/duckdb/drishti.duckdb`. Design: [DUCKDB_CONNECTOR.md](DUCKDB_CONNECTOR.md).

## MongoDB

```bash
docker run -d --name drishti-mongo -m 2g -p 27017:27017 mongo:7 --wiredTigerCacheSizeGB 0.5
tools/load-mongodb.sh mongodb://localhost:27017 drishti                       # the samples
tools/load-mongodb.sh mongodb://localhost:27017 drishti --trades 10000        # and a book, for a laptop
SPRING_PROFILES_ACTIVE=mongodb DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

Design: [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md).

## Redis

```bash
docker run -d --name drishti-redis -m 2g -p 6380:6379 redis:8 --maxmemory 1gb
tools/load-redis.sh redis://localhost:6380                       # the samples
tools/load-redis.sh redis://localhost:6380 --trades 10000        # and a book, for a laptop
DRISHTI_REDIS_URI=redis://localhost:6380 SPRING_PROFILES_ACTIVE=redis DRISHTI_PACKS=market-risk,counterparty-risk \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

(Port 6380 on the host leaves a Redis you already run on 6379 alone.) Redis holds everything in memory: about 1 GB per million trades
a day, so keep large books for a server. Each load replaces the business days it carries (`--merge` adds to them instead). Design:
[REDIS_CONNECTOR.md](REDIS_CONNECTOR.md).

## Aerospike

```bash
docker compose -f deploy/compose.data.yaml up -d aerospike
tools/load-aerospike.sh localhost:3000 test                                  # the samples
tools/load-aerospike.sh localhost:3000 test --trades 10000                   # and a book
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

`--ttl-days N` lets Aerospike expire each day after N days. The default namespace of the Docker image keeps data in memory and is too
small for a million-trade book; see [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md#4-loading).

## JSON-lines files (no store at all)

```bash
tools/load-files.sh                              # the samples: data/files
tools/load-files.sh --trades 10000               # and 10,000 trades a day for 3 days
SPRING_PROFILES_ACTIVE=files DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

One file per kind per business day, `data/files/<domain>/<date>/<kind>.jsonl`: see [FILE_CONNECTOR.md](FILE_CONNECTOR.md).

## Your own JSONL

`drishti.py data ingest` (the module `tools/ingest_jsonl.py`) loads any JSON Lines (plain documents or loader envelopes; a folder or
single files) into a Delta lake or a files store, using the layout the connectors read. A pack made by `drishti.py pack make` (or
`tools/packgen/pack_from_jsonl.py`) carries the id and date fields it needs. For a large single export use `drishti.py lake build`
instead (streamed, flat memory).

```bash
# Delta lake (default): <root>/<domain>/<kind>/business_date=...; serve with DRISHTI_DELTA_ROOT
drishti-console/.venv/bin/python tools/drishti.py data ingest --from data/trade.jsonl --pack config/packs/my-bank --lake /tmp/lake
DRISHTI_PACKS=my-bank DRISHTI_DELTA_ROOT=/tmp/lake java -jar drishti-server/target/drishti-server-*-exec.jar
# JSON-lines files (stdlib + PyYAML only): <root>/<domain>/<date>/<kind>.jsonl; serve with DRISHTI_FILES_ROOT
drishti-console/.venv/bin/python tools/drishti.py data ingest --from your-folder/ --domain my-bank --key trade=TRADE_ID --date trade=BUSINESS_DATE \
    --store files --root /tmp/files --dry-run
```

`--mode overwrite-dates` (default) replaces only the business dates present, so a re-run is idempotent; `append`; `replace`. Without
`--pack`, give `--domain`, `--key`, `--date` per kind. Documents without the date are reported and skipped. See
[Generating a pack from JSON Lines](../guides/PACK_DEVELOPER_GUIDE.md#generating-a-pack-from-json-lines) and
[CLI_GUIDE.md](../guides/CLI_GUIDE.md).

## The samples

The packs' samples in `config/packs/<pack>/samples/` are what the `demo` source serves live: one JSON Lines file per kind
(`<kind>.jsonl`, one document a line, split into `<kind>.part-001.jsonl` … only above about 50 MB) and a `catalog.json` listing every
sample's kind, id, title and subtitle (the type-ahead's text). The five banking packs' samples are written by

```bash
drishti-console/.venv/bin/python tools/packgen/banking/make_data.py          # add --check to verify instead of write
```

which prints (real output on a fresh clone):

```text
wrote 2571 documents into 5 packs' samples; 271 trading test files
feeds: …/data/feeds/fixing/SOFR-HISTORY.csv
```

(world-economy and news-and-markets have their own generators under `tools/packgen/`.) Every `load-*.sh` script runs it first, along
with the sample feed file `data/feeds/fixing/SOFR-HISTORY.csv` (which `lake demo` writes too). The samples per pack:

| Pack | Samples | Kinds | Live | On disk |
|---|---|---|---|---|
| `core` | 129 | 10 | 30 (books) | 247 KB |
| `market-data` | 220 | 20 | 119 (curves, FX, equities, indices, commodities, bonds, credit curves) | 409 KB |
| `trading` | 765 | 1 | 0 (trades do not tick; their valuations do) | 8.0 MB |
| `counterparty-risk` | 534 | 10 | 66 (netting sets) | 643 KB |
| `market-risk` | 923 | 6 | 765 (one trade valuation per trade) | 736 KB |
| `world-economy` | 9,582 | 13 | 0 | 19.0 MB |
| `news-and-markets` | 35 | 2 | 0 | 16 KB |

### Live walks by kind

A live sample ticks while someone watches it (the `demo` source, [DEMO_CONNECTOR.md](DEMO_CONNECTOR.md#74-coherent-walks-a-group-that-moves-together)).
Its `_meta.walk` (the `_meta` block keeps its lower-case keys; the paths inside the walk are the document's UPPER_SNAKE names) moves a headline **and everything that must agree with it**, so a table, a chart and a headline never
disagree. The specs are built by `tools/packgen/common/walks.py` from the finished sample; `DemoPackWalksTest` ticks every sample of
every kind below 40 times and checks the identities in the second column. The kinds of the seven packs that walk:

| Kind (pack) | Moves together, and the identity that holds after any tick | Stays still, and why |
|---|---|---|
| `ir-curve` (market-data) | one shift for the curve (a small tilt and per-tenor blur): every `ZERO_RATE` (%), every `QUOTE`, every `FORWARDS[].RATE` (%); each `DF = exp(-ZERO_RATE/100 × tenor)`; `TEN_Y` is the 10Y zero rate; `SLOPE_2S_10S` is 10Y less 2Y in bp | `MATURITY`, instruments, conventions |
| `fx-spot` | `MID` with `BID` and `ASK` by the same amount (the spread is constant); `CHANGE_1D = MID / previous close - 1`; today's `HISTORY` point is `MID` | the earlier history |
| `equity` | `PRICE`; `MARKET_CAP`; `CHANGE_1D`; today's history point and today's bar (`OHLC`: close is the price, high and low stretch, volume builds) | the bars before today, identifiers, beta |
| `equity-index` | `LEVEL`; `CHANGE_1D`; today's history point | constituents and weights (a rebalance, not a tick) |
| `commodity` | `FRONT`; today's bar (close, high, low, volume) | contract specification |
| `commodity-curve` | `FRONT` with every month (parallel, a little blur); `FRONT` is the first month; `ROLL`; `SHAPE` flips between backwardation and contango when the first two months swap | open interest (end of day) |
| `credit-curve` | `SPREAD_5Y` with every tenor's spread; `HAZARD = SPREAD / 1e4 / (1 - recovery)`; `SURVIVAL = exp(-HAZARD × tenor)`; `PD_1Y = 1 - SURVIVAL(1Y)` | recovery, clause (terms) |
| `bond` | `PRICE`; `YIELD` from the price and modified duration; `Z_SPREAD` moves against the price; today's history point | the coupon schedule, the terms |
| `trade-valuation` (market-risk) | `MTM`; `PNL_1D = MTM - opening MTM` | the trade's terms (in the `trade` kind, which does not tick) |
| `netting-set` (counterparty-risk) | each listed trade's `MTM` walks; each asset class's total (`BY_ASSET`) is its trades (plus the unlisted ones, fixed) and `NET_MTM` is the sum; the exposure profile scales as one; `EE_PEAK` and `PFE_PEAK` are its maxima; `UTILISATION = PFE_PEAK / limit` | collateral (moves on margin calls), the agreement |
| `book` (core) | each of the ten largest trades' `MTM` (`TOP_TRADES`) walks; `MTM` is those plus the rest (fixed) | trade count and DV01 (end of day) |

The retired example packs in `examples/packs/` keep their own walks (key risk indicators, card and deposit accounts, LCR, intraday
liquidity, containers, vessels) in their older camelCase shape. Samples that are not `live` keep the same walk, so switching one to live
needs no other change. To see it, open `/v/ir-curve/CRV-USD-BASIS` with the demo source on (`DRISHTI_DEMO_ENABLED=true`): the 10Y cell,
the other zero rates, the discount factors, the forwards and the curve in the chart all change at each tick.

## JSON lines: one format for every loader

Every loader except Delta Lake's reads the same JSON lines, one per entity per business day. A real line written by
`make_data.py --jsonl` (the document shortened):

```json
{"domain": "trading", "kind": "trade", "id": "MX-20000001", "date": "2026-09-17",
 "doc": "{\"SOURCE_SYSTEM\": \"MUREX\", \"TRADE_ID\": \"MX-20000001\", \"TRADE_STATUS\": \"AMENDED\", …}",
 "columns": {"TRADE_ID": "MX-20000001", "SOURCE_SYSTEM": "MUREX", "PRODUCT_TYPE": "IRS_FIXFLOAT", "TRADE_STATUS": "AMENDED",
             "CURRENCY": "AUD", "QUANTITY": 242000000.0, "MATURITY_DATE": "2032-06-25", "COUNTERPARTY": "CP-MERIDIAN-RE",
             "LEGAL_ENTITY": "LE-NY", "PORTFOLIO": "BOOK-RATES-3", "TRADER_ID": "TRDR-ASHAH", …}}
```

`columns` carries the fields the pack promotes (the trading pack's `layout.trade.columns`), taken from the same document. Two
generators write it:

```bash
drishti-console/.venv/bin/python tools/packgen/banking/make_data.py --jsonl data/build/banking.jsonl   # the samples, 10 days: 25,710 lines
drishti-console/.venv/bin/python tools/samplegen/bulk_trades.py --trades 10000 --days 3 --jsonl - \
    | <a loader reading standard input>                                                                   # a book, streamed
```

(`make_data.py --jsonl` took 11 s on a fresh clone and wrote 25,710 lines over the ten days 2026-09-17 to 2026-09-30, 7,650 of them
trades.) So any store a new connector adds can be loaded with the same demo data.

## The scripts

| Script | What it does |
|---|---|
| `tools/drishti.py lake demo [--lake DIR] [--as-of today\|data\|DATE] [--only …] [--with-real FILE] [--server URL] [--no-signal]` | the demo lakes from `data/testdata` (`tools/lake/demo.py`) |
| `tools/drishti.py lake build --from FILE --pack DIR --kind K …` | one Delta table from a large JSON export, streamed (`tools/lake/build.py`) |
| `tools/testdata/make_testdata.py [--check]` | writes `data/testdata` |
| `tools/load-files.sh [root] [--trades N] [--days D]` | `make_data.py --jsonl`, then `JsonlLoader` into `<root>/<domain>/<date>/<kind>.jsonl` |
| `tools/load-delta.sh [root] [--trades N] [--days D]` | `make_data.py --lake`, then `bulk_trades.py` |
| `tools/load-postgres.sh [jdbc-url] [--trades N] [--days D] [--keep-months N] [--user U] [--password P] [--writers N]` | `make_data.py --jsonl`, then `PostgresLoader` (samples with `--recreate`), then the bulk book streamed |
| `tools/load-iceberg.sh [root] [--trades N] [--days D] [--keep-days N] [--catalog rest --uri U --warehouse W]` | `make_data.py --jsonl`, then `IcebergLoader` (each day sorted by id), then the bulk book streamed |
| `tools/load-duckdb.sh [database] [--trades N] [--days D] [--keep-days N] [--memory-limit M] [--parsers N] [--threads N]` | `make_data.py --jsonl`, then `DuckDbLoader` (samples with `--recreate`), then the bulk book streamed; each load builds `<database>.loading` and renames it over the database |
| `tools/load-mongodb.sh [uri] [db] [--trades N] [--days D] [--keep-days N \| --ttl-days N] [--doc-format string\|bson]` | `make_data.py --jsonl`, then `MongoLoader`, then the bulk book streamed |
| `tools/load-redis.sh [uri] [--trades N] [--days D] [--ttl-days N] [--merge] [--publish] [--cluster]` | `make_data.py --jsonl`, then `RedisLoader`, then the bulk book streamed |
| `tools/load-aerospike.sh [hosts] [namespace] [--trades N] [--days D] [--ttl-days N]` | `make_data.py --jsonl`, then `AerospikeLoader`, then the bulk book streamed |
| `tools/packgen/banking/make_data.py` | the samples, `--lake`, `--jsonl`, `--check` |
| `tools/samplegen/bulk_trades.py` | a large trade book for one source system, into the lake or as JSON lines |

`lake demo` and `lake build` need only the console's Python (`drishti-console/.venv`, with PyYAML, deltalake and pyarrow). The
`load-*.sh` scripts need `uv` (Python), Java 21 or newer (`JAVA_HOME`; the scripts default to Java 21), and Maven's offline cache for the
Java loaders (run `./mvnw install` once).
