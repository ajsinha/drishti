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
| **The test data** | `data/testdata/` (committed, about 120 MB of `.jsonl.gz`) | sixty business days of every dataset the seven packs read: up to 5,000 trades a day per source system (ENDUR, CALYPSO, MUREX), counterparties, market data, agreements, netting sets, risk results; a first-day snapshot and each later day's changes | `tools/testdata/make_testdata.py` (`--check` says whether the files are current) |
| **The demo lakes** | `data/delta/` (generated, never committed) | the test data as Delta tables, one per dataset, sixty daily snapshots each: what the packs' connectors read | `drishti.py lake demo` (one command, about 15 minutes) |
| **The samples** | `config/packs/<pack>/samples/<kind>.jsonl` (committed) | a few hundred documents a pack, the same shape, that the `demo` source serves live and ticking, with no store at all | `tools/packgen/banking/make_data.py` and each pack's generator |

The other stores (PostgreSQL, DuckDB, Iceberg, MongoDB, Redis, Aerospike, Cassandra, JSON-lines files) are loaded with the samples (ten business
days) and, for scale tests, a generated trade book: the scripts in [the other stores](#postgresql).

## The test data and the demo lakes (the default)

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

(the IDE run configuration **Build demo lakes**). Measured for sixty days: **943 s (about 15 minutes), 85 tables, about 2.7 GB**, sixty business days
ending on today's business date (the test data's 2026-07-08 to 2026-09-30, moved; `--as-of data` keeps them). The trades go to one
table per source system, `data/delta/trading/trade_endur`, `trade_calypso` and `trade_murex`, partitioned by business date and
`PRODUCT_TYPE`, each read by its own connector (`trade-endur`, `trade-calypso`, `trade-murex`) and served together as one kind,
`trade`; every other dataset goes to `data/delta/<domain>/<kind>/`, partitioned by business date. Datasets of static reference data
(world-economy, news-and-markets) are built from those packs' samples, the same on every day. The real output, the options
(`--lake`, `--only`, `--with-real`, `--server`, `--no-signal`) and what a running server is told are in
[GETTING_STARTED.md, Step 5](../guides/GETTING_STARTED.md#step-5--build-the-demo-lakes); the datasets, one row each (key, connector,
table, partitions, documents on day 1, changes a day, size), are in [data/testdata/README.md](../../data/testdata/README.md).

| What | Value |
|---|---|
| Business days | 60 (USNY calendar; the test data's 2026-07-08 to 2026-09-30) |
| Trades | 5,000 per source system on the last day, 15,000 in all (4,407 ENDUR, 3,471 CALYPSO, 4,198 MUREX on the first); about 3–7% amended each day (`TRADE_VERSION` moves) |
| Source systems | `ENDUR` (ids `1000001` …), `CALYPSO` (`CLY-3000001` …), `MUREX` (`MX-20000001` …): ids unique across them |
| Tables | 64: 3 trade tables, 46 test-data datasets of the five banking packs, 15 reference tables of world-economy and news-and-markets |
| Disk | about 2.2 GB (the committed test data: about 120 MB) |
| Counterparties | fictitious look-alikes (`CP-NORTHPLAINS`, `CP-VAULTGUARD`, …), with UEN, country of risk, legal entity, agreements and netting sets holding their trades |

Re-running `lake demo` rebuilds every table it writes (the folder is removed first) and is safe while a server runs: the server gets
the data-load signal for each table and date, or, when none is reachable, finds `data/bi-store/lake-rebuilt.json` at its next start
and clears its stored BI columns and rollups; a running server also sees each rebuilt Delta date by itself. The test data itself is
regenerated with `python3 tools/testdata/make_testdata.py` (deterministic, the same bytes on every Python version: `tools/testdata/test_testdata.py` and `test_deterministic.py` guard it).

## Mixed Delta and Iceberg storage

Since 2.6.7 (decided by the product owner on 2026-10-20) each source system's trades can be stored in **Delta Lake or Apache Iceberg**,
chosen per system when the demo lakes are built, so one installation proves that the connectors, history, search, the grouped pages and
BI answer the same whatever the storage. Every other dataset stays in Delta.

| Command (`drishti-console/.venv/bin/python tools/build_demo_lakes.py …`; Windows `drishti-console\.venv\Scripts\python.exe tools\build_demo_lakes.py …`) | Result |
|---|---|
| (no `--store`) | everything in Delta, as before |
| `--store calypso=iceberg` | CALYPSO in Iceberg, ENDUR and MUREX in Delta (the mixed case) |
| `--store endur=delta --store calypso=iceberg --store murex=iceberg` | any mix, one `--store` per system |
| `--store all=iceberg` | every system in Iceberg (`all=` first, then each system over it: `--store all=iceberg --store endur=delta`) |
| `--store all=delta` | every system back in Delta |
| `--only trades/calypso --store calypso=iceberg --as-of <date>` | one system rebuilt and moved; the others kept as they are |
| `--source-dir <folder> --system calypso --files-only --store calypso=iceberg` | your own CALYPSO files into Iceberg |
| any of these with `--dry-run` | every step said, nothing changed |

- **Where:** an Iceberg system's table is `<data>/iceberg/trading/trade_<system>`, beside the Delta lake (`data/iceberg` for the default
  `data/delta`; `--lake /srv/data/delta` gives `/srv/data/iceberg`; `DRISHTI_ICEBERG_ROOT` moves it). Its Delta table is removed: each
  system is in one store.
- **How it is written:** the system is built as Delta first (by `lake demo`, or by `lake build` of your files into a staging folder,
  `<data>/build/iceberg-stage`, removed after), so its documents and promoted columns are exactly those of a Delta build; then
  `tools/lake/stores.py` streams the rows into Drishti's own Iceberg loader (`IcebergLoader`, `engine: native`: no Hadoop, so it works on
  Windows), started from the server's exec jar (`drishti-server/target/drishti-server-*-exec.jar` or `DRISHTI_JAR`: build the server first;
  heap `DRISHTI_LOADER_HEAP`, default 2g). The loader's progress lines (`iceberg: … rows read`, `iceberg: written in … s`) and the usual
  30-second heartbeat show while it copies.
- **Connector files:** the build writes `trade-<system>.yaml` (in `config/connectors`, or `--connectors-dir`) from the trading pack's
  template with `plugin: iceberg`, `engine: native` and `table: {trade: trade_<system>}` (the Iceberg connector's `table.<kind>` setting,
  as the Delta connector's), or back with `plugin: delta` when the system returns to Delta; the text it replaces is kept in
  `<data>/connectors/history/`. One connector serves each system, never two; the trading pack's union of the three systems is unchanged.
  A running server restarts a switched connector by itself (live reload); `lake demo` leaves an Iceberg file as it is.
- **What a run keeps:** a full build without `--store` puts every system in Delta. A partial build (`--only`, `--files-only`) keeps each
  system it does not name in the store it has; `--only` with a `--store` that moves another system rebuilds that system too (it says so);
  `--files-only` cannot move a system whose trades it does not load, and says to run `--only trades/<system> --store …` first.
- **The closing lines** list every system's store and table, and what was done to each connector file:

  ```text
    Stores (each source system's trades):
      ENDUR    delta    …/data/delta/trading/trade_endur
      CALYPSO  iceberg  …/data/iceberg/trading/trade_calypso
      MUREX    delta    …/data/delta/trading/trade_murex
    Connector files:
      trade-endur: reads delta already (…/config/connectors/trade-endur.yaml)
      trade-calypso: now reads iceberg, was delta (…/config/connectors/trade-calypso.yaml; a running server restarts this connector by itself)
      trade-murex: reads delta already (…/config/connectors/trade-murex.yaml)
  ```

- **With a server:** `--server URL` tells it once at the end (pack trading, kind `trade`, the newest date), as for your files.
- **Proof:** `tools/lake/stores_equality.py --work <an empty folder> --port <a free port>` builds the same five synthetic business days
  (1,000 trades a system a day from the committed test data) all-Delta and mixed (ENDUR Delta, CALYPSO and MUREX Iceberg) and asks a server
  over each, one after the other: six trade pages, `TRD <id> 10` for each, two counterparty-trades pages, three searches and a BI model query
  (sum of `QUANTITY` by source system, currency and product type). Measured on 2026-10-21: **18 answers, the same** (only timings, the
  data-version stamps and the table generation in the provenance panel differ, as they name the storage).
- **Maintenance:** `lake maintain` for the Delta tables; the Iceberg tables with `IcebergLoader --keep-days` or the Iceberg maintenance of
  [ICEBERG_CONNECTOR.md, section 9](ICEBERG_CONNECTOR.md#9-retention-and-maintenance).
- **Default:** an option for now; the demo default becomes mixed once 2.6.7's QA is clean on it.

## The sizes

| Size | Command | What you get | When to use it |
|---|---|---|---|
| **samples** | none: the `demo` source serves them | the packs' samples (765 trades, 2,571 documents in the five banking packs), today only, ticking | a first look with no store, writing a pack |
| **the demo lakes** | `drishti.py lake demo` | 15,000 trades a day and every other dataset, sixty business days | demos, QA, the tutorials |
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

It generates, then loads, in separate steps ([DATA_FORMATS.md](../guides/DATA_FORMATS.md)): `make_data.py --lake --no-load` writes the
history as JSON Lines rows (`<data>/build/lake-rows/banking-history.jsonl` and `banking-restated.jsonl`), `tools/samplegen/load_rows.py`
loads them (rewriting the whole lake the packs read, in the layout each declares; the restatement as a second commit); then
`tools/samplegen/bulk_trades.py` writes the book as one JSONL file a day (`<data>/build/bulk/murex/trades-<date>.jsonl.gz`) and loads each
with `lake build`, replacing **one source system's** trade table (`trade_murex`, or `--system CALYPSO`) and leaving every other table as
it is (`--no-lake` stops after the JSONL). Neither step rewrites the packs' samples, the Sutra tests or the feed. The server reads `data/delta` (or `DRISHTI_DELTA_ROOT`) and
picks a new load up within a minute; no restart is needed. The layout: [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md).

For your own export (a large JSON array or JSON Lines of real trades), `drishti.py lake build` streams it into a Delta table with flat
memory, partitions and promoted columns ([TOOLS.md](../guides/TOOLS.md)).

## PostgreSQL

```bash
docker compose -f deploy/docker/compose.data.yaml up -d postgres                                       # or your own server
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

Design: [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md). (`load-iceberg.sh` builds the plugin with Maven; on Windows use
`tools\windows\load-iceberg.ps1`, or put the demo lakes' trades in Iceberg with `build_demo_lakes.py --store`: [above](#mixed-delta-and-iceberg-storage).)

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
docker compose -f deploy/docker/compose.data.yaml up -d aerospike
tools/load-aerospike.sh localhost:3000 test                                  # the samples
tools/load-aerospike.sh localhost:3000 test --trades 10000                   # and a book
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

`--ttl-days N` lets Aerospike expire each day after N days. The default namespace of the Docker image keeps data in memory and is too
small for a million-trade book; see [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md#4-loading).

## Apache Cassandra

Cassandra (or ScyllaDB) is yours to run; a single development node is enough for the samples. Then:

```bash
tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1                   # the samples, a keyspace per domain
tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1 --trades 10000   # and a book of 10,000 trades a day
cp config/connectors.examples/cassandra/*.yaml config/connectors/                                  # read the domains from Cassandra
```

Each business day is written under a new generation and switched to at the end, so a reload never shows half a day. `--ttl-days N`
expires cells after N days; `--keep-days N` drops the older days. See [CASSANDRA_CONNECTOR.md](CASSANDRA_CONNECTOR.md#4-loading).

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
[Generating a pack from JSON Lines](../guides/PACK_GENERATORS_GUIDE.md#generating-a-pack-from-json-lines) and
[CLI_GUIDE.md](../guides/CLI_GUIDE.md).

## Your bank's names

The demo bank is *Drishti Bank*, and its legal entities are *Drishti Bank plc* (`LE-LDN`), *Drishti Bank Securities Inc.* (`LE-NY`),
*Drishti Bank Europe AG* (`LE-FRA`) and *Drishti Securities Japan K.K.* (`LE-TKY`), with branch forms such as *Drishti Bank plc,
Singapore Branch*. To demonstrate with other names, edit `config/demo/names.yaml` (2.9.4):

```yaml
bank: Example Bank                       # the bank as a whole (the top of the risk hierarchy)
short-name: EXAMPLE
legal-entities:
  LE-NY: Example Securities Inc.
  LE-LDN: Example Bank plc
  LE-FRA: Example Bank Europe AG
  LE-TKY: Example Securities Japan K.K.
# branches:                              # optional: a branch's own full name
#   "Drishti Bank plc, Singapore Branch": Example Bank plc, Singapore Branch
```

The defaults are the names above, so the file changes nothing until you edit it. The names are applied **as the demo lakes are
built**: `drishti.py lake demo` and `tools/build_demo_lakes.py` (both take `--names FILE`, default `config/demo/names.yaml`; with
`--dry-run` they print the mapping and build nothing) rename, in every kind as it is loaded, each value that **is** an old name, and
each value that **starts with** an old name followed by a comma (a branch keeps its suffix). The committed test data
(`data/testdata`) is never rewritten. **LEIs and BICs stay as they are**: they are identifiers. In the committed data the names are in:

| Where | Field | Names |
|---|---|---|
| `core/legal-entity` (test data, lake `reference/legal-entity`) | `NAME` | the four legal entities |
| `market-risk/risk-hierarchy` (test data, lake `risk/risk-hierarchy`) | `NAME` | the bank, at the top of both trees |
| `config/packs/core/samples` | `legal-entity.jsonl` `NAME`, `catalog.jsonl` subtitles | the four legal entities |
| the retired example packs (`examples/packs/climate-risk`, `liquidity-risk`, `operational-risk`; `finance`) | `legalEntityName`, `legalEntity.name` | the legal entities |

So after an edit rebuild those two datasets (`--only core/legal-entity,market-risk/risk-hierarchy`, or a full `lake demo`). The
packs' samples, served without a lake, follow the file when their generators are run again: `python3 tools/packgen/banking/make_data.py`
(the core samples; the catalogue's subtitles too) and an example pack's `make.py`. The VaR tree's top node is named by a server setting
of its own, the VaR root name (the PV-VaR work of 2.9.4, listed in [CONFIGURATION.md](../admin/CONFIGURATION.md) under the VaR
engine): the VaR tree's top name is set there.

## The samples

The packs' samples in `config/packs/<pack>/samples/` are what the `demo` source serves live: one JSON Lines file per kind
(`<kind>.jsonl`, one document a line, split into `<kind>.part-001.jsonl` … only above about 50 MB) and a `catalog.jsonl` listing every
sample's kind, id, title and subtitle (the type-ahead's text). The five banking packs' samples are written by

```bash
drishti-console/.venv/bin/python tools/packgen/banking/make_data.py          # add --check to verify instead of write
```

which prints (real output on a fresh clone):

```text
wrote 2571 documents into 5 packs' samples; 271 trading test files
feeds: …/data/feeds/fixing.jsonl
```

(world-economy and news-and-markets have their own generators under `tools/packgen/`.) The plain run also writes the sample feed file
`data/feeds/fixing.jsonl` (one fixing series a line, `FIX SOFR-HISTORY`; `lake demo` writes it too). Every `load-*.sh` script runs
`make_data.py --jsonl` or `--lake`, which write rows only: they never rewrite the samples, the Sutra tests or an existing feed. The samples per pack:

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
| `netting-set` (counterparty-risk) | each listed trade's `MTM` walks; each asset class's total (`BY_ASSET`) is its trades (plus the unlisted ones, fixed) and `NET_MTM` is the sum; `UTILISATION` moves with it (exposure itself, EE and PFE, is not stored on the netting set since 2.9.0: the exposure engine computes it) | collateral (moves on margin calls), the agreement |
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
| `tools/load-delta.sh [root] [--trades N] [--days D]` | `make_data.py --lake --no-load` (JSONL rows), `samplegen/load_rows.py` (into Delta), then `bulk_trades.py` (JSONL, then `lake build`) |
| `tools/load-postgres.sh [jdbc-url] [--trades N] [--days D] [--keep-months N] [--user U] [--password P] [--writers N]` | `make_data.py --jsonl`, then `PostgresLoader` (samples with `--recreate`), then the bulk book streamed |
| `tools/load-iceberg.sh [root] [--trades N] [--days D] [--keep-days N] [--catalog rest --uri U --warehouse W]` | `make_data.py --jsonl`, then `IcebergLoader` (each day sorted by id), then the bulk book streamed |
| `tools/load-duckdb.sh [database] [--trades N] [--days D] [--keep-days N] [--memory-limit M] [--parsers N] [--threads N]` | `make_data.py --jsonl`, then `DuckDbLoader` (samples with `--recreate`), then the bulk book streamed; each load builds `<database>.loading` and renames it over the database |
| `tools/load-mongodb.sh [uri] [db] [--trades N] [--days D] [--keep-days N \| --ttl-days N] [--doc-format string\|bson]` | `make_data.py --jsonl`, then `MongoLoader`, then the bulk book streamed |
| `tools/load-redis.sh [uri] [--trades N] [--days D] [--ttl-days N] [--merge] [--publish] [--cluster]` | `make_data.py --jsonl`, then `RedisLoader`, then the bulk book streamed |
| `tools/load-aerospike.sh [hosts] [namespace] [--trades N] [--days D] [--ttl-days N]` | `make_data.py --jsonl`, then `AerospikeLoader`, then the bulk book streamed |
| `tools/load-cassandra.sh [contact-points] [datacenter] [--create] [--create-keyspace RF] [--trades N] [--days D] [--ttl-days N] [--keep-days N]` | `make_data.py --jsonl` and the bulk book as one stream into `CassandraLoader` |
| `tools/packgen/banking/make_data.py` | the samples, `--lake` (JSONL rows, then loaded; `--no-load`), `--jsonl`, `--check` |
| `tools/samplegen/load_rows.py --rows F --restated F --root R` | loads dated loader rows (JSONL) into the lake |
| `tools/samplegen/bulk_trades.py` | a large trade book for one source system: JSONL a day, then `lake build` (`--no-lake`), or loader rows (`--jsonl`) |

`lake demo` and `lake build` need only the console's Python (`drishti-console/.venv`, with PyYAML, deltalake and pyarrow). The
`load-*.sh` scripts need `uv` (Python), Java 21 or newer (`JAVA_HOME`; the scripts default to Java 21), and Maven's offline cache for the
Java loaders (run `./mvnw install` once).
