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
# Demo data: building it in each store, small to a million trades a day

> **For staging, small setups and proofs of concept.** The loaders here, `drishti.py data ingest` (and `--watch`) and `pack make` are not a
> production ETL; Drishti does not ingest your data. Production data is loaded by your own ETL, which ends by telling Drishti the batch landed:
> [DATA_LOADS.md](../guides/DATA_LOADS.md).

Nothing under `data/` is in git: the demo data is always generated, deterministically, by the scripts below. Each
store has one script with the same options, so the same demo can be built small for a laptop or large for a scale test.

## The sizes

| Size | Command option | What you get | When to use it |
|---|---|---|---|
| **small** | (none) | the banking packs' 1,791 sample documents (750 trades) for 10 business days ending 30 September 2026 | a demo on a laptop, a first look, development |
| **medium** | `--trades 50000` | the samples, and a trade book of 50,000 trades a day for 3 days | a demo that shows searches over a real-looking book |
| **large** | `--trades 1000000 --days 3` | a million trades a day for 3 days | scale tests, performance measurements |

`--trades N` replaces the **whole trade table** with a book of N trades for only `--days D` business days (the sample
trade days outside those D days are gone; for a 10-day history use the small size). The book's first 750 trades are the samples, booked in six
trading systems with their own ids (Murex `MX-`, Calypso `CLY-`, Endur `END-`, Imagine `IMG-`, Bloomberg TOMS `BBG-`,
Wall Street Systems `WSS-`). `--days D` sets how many business days the book covers (default 3). Every size is
reproducible: the same command writes the same data.

## Delta Lake (the default store)

```bash
tools/load-delta.sh                              # small: data/delta
tools/load-delta.sh --trades 50000               # medium
tools/load-delta.sh --trades 1000000 --days 3    # large
tools/load-delta.sh /tmp/lake --trades 20000     # somewhere else
```

| Size | Time (24 cores) | Disk |
|---|---|---|
| samples plus 20,000 trades × 2 days | 6 s | 67 MB |
| 1,000,000 trades × 3 days | about 85 s | 4.9 GB |

The server reads `data/delta` (or `DRISHTI_DELTA_ROOT`) and picks a new load up within a minute; no restart is
needed. The lake is written in the layout the packs declare: see [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md).

## PostgreSQL

```bash
docker compose -f deploy/compose.data.yaml up -d postgres                                       # or your own server
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti                                 # small
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 50000                  # medium
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 1000000 --days 3       # large
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

| Size | Time | Disk |
|---|---|---|
| samples | 2 s | under 100 MB |
| samples plus 20,000 trades × 2 days | 12 s | about 150 MB |
| 1,000,000 trades × 3 days | about 3 minutes | 11 GB |

User and password come from `--user`/`--password`, else `DRISHTI_PG_USER`/`DRISHTI_PG_PASSWORD`, else `drishti`. The
samples' load recreates each domain's table; a running server sees a new load within a minute. Layout and design:
[POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md).

## Apache Iceberg

```bash
tools/load-iceberg.sh ./data/iceberg                       # small
tools/load-iceberg.sh ./data/iceberg --trades 10000        # medium for a laptop
SPRING_PROFILES_ACTIVE=iceberg DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

Design: [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md).

## DuckDB

```bash
tools/load-duckdb.sh data/duckdb/drishti.duckdb                              # small
tools/load-duckdb.sh data/duckdb/drishti.duckdb --trades 10000 --days 3      # medium for a laptop (about 3 s for the book)
SPRING_PROFILES_ACTIVE=duckdb DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

No server to run: one file holds every data domain, and the server reads it in-process. Each load writes a new file
and renames it over the old one, so it can run while the server answers; the server picks it up within 10 s. The
database defaults to `DRISHTI_DUCKDB_PATH`, else `data/duckdb/drishti.duckdb`. Design:
[DUCKDB_CONNECTOR.md](DUCKDB_CONNECTOR.md).

## MongoDB

```bash
docker run -d --name mongo -m 2g -p 27017:27017 mongo:7 --wiredTigerCacheSizeGB 0.5
tools/load-mongodb.sh mongodb://localhost:27017 drishti                       # small
tools/load-mongodb.sh mongodb://localhost:27017 drishti --trades 10000        # medium for a laptop (3 s)
SPRING_PROFILES_ACTIVE=mongodb DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

Design: [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md).

## Redis

```bash
docker run -d --name redis -m 2g -p 6379:6379 redis:8 --maxmemory 1gb
tools/load-redis.sh redis://localhost:6379                       # small
tools/load-redis.sh redis://localhost:6379 --trades 10000        # medium for a laptop
SPRING_PROFILES_ACTIVE=redis DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

Redis holds everything in memory: about 1 GB per million trades a day, so keep large books for a server. Each load
replaces the business days it carries (`--merge` adds to them instead). Design: [REDIS_CONNECTOR.md](REDIS_CONNECTOR.md).

## Aerospike

```bash
docker compose -f deploy/compose.data.yaml up -d aerospike
tools/load-aerospike.sh localhost:3000 test                                  # small
tools/load-aerospike.sh localhost:3000 test --trades 50000                   # medium
tools/load-aerospike.sh localhost:3000 test --trades 1000000 --days 3        # large (needs file or device storage)
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

| Size | Time |
|---|---|
| samples | 1 s |
| 1,000,000 trades × 3 days | about 2.5 minutes |

`--ttl-days N` lets Aerospike expire each day after N days. The default namespace of the Docker image keeps data in
memory and is too small for the large size; see [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md#4-loading).

## JSON-lines files (no store at all)

```bash
tools/load-files.sh                              # small: data/files
tools/load-files.sh --trades 10000               # and 10,000 trades a day for 3 days (9 s, 274 MB)
SPRING_PROFILES_ACTIVE=files DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

One file per kind per business day, `data/files/<domain>/<date>/<kind>.jsonl`: see [FILE_CONNECTOR.md](FILE_CONNECTOR.md).

## Your own JSONL

`tools/ingest_jsonl.py` loads any JSON Lines (plain documents or loader envelopes; a folder or single files) into either store,
using the layout the connectors read. A pack made by `tools/packgen/pack_from_jsonl.py` carries the id and date fields it needs.

```bash
# Delta lake (default): <root>/<domain>/<kind>/business_date=...; serve with DRISHTI_DELTA_ROOT
uv run --with pyyaml --with deltalake --with pyarrow python tools/ingest_jsonl.py --from data/trade.jsonl --pack config/packs/my-bank --lake /tmp/lake
DRISHTI_PACKS=my-bank DRISHTI_DELTA_ROOT=/tmp/lake java -jar drishti-server/target/drishti-server-*-exec.jar
# JSON-lines files (stdlib + PyYAML only): <root>/<domain>/<date>/<kind>.jsonl; serve with DRISHTI_FILES_ROOT
uv run --with pyyaml python tools/ingest_jsonl.py --from data/jsonl --domain my-bank --key trade=tradeId --date trade=businessDate \
    --store files --root /tmp/files --dry-run
```

`--mode overwrite-dates` (default) replaces only the business dates present, so a re-run is idempotent; `append`; `replace`.
Without `--pack`, give `--domain`, `--key`, `--date` per kind. Documents without the date are reported and skipped.
See [Generating a pack from JSON Lines](../guides/PACK_DEVELOPER_GUIDE.md#generating-a-pack-from-json-lines).

## The samples

The banking packs' samples in `config/packs/<pack>/samples/` are what the `demo` source serves live; they are written by

```bash
python3 tools/packgen/banking/make_data.py
```

which every script above also runs first, along with the sample feed file `data/feeds/fixing/SOFR-HISTORY.csv`.

### Live walks by kind

A live sample ticks while someone watches it (the `demo` source, [DEMO_CONNECTOR.md](DEMO_CONNECTOR.md#74-coherent-walks-a-group-that-moves-together)).
Its `_meta.walk` moves a headline **and everything that must agree with it**, so a table, a chart and a headline never
disagree. The specs are written by `tools/packgen/common/walks.py`; `DemoPackWalksTest` ticks every sample of every kind
below 40 times and checks the identities in the second column.

| Kind (pack) | Moves together, and the identity that holds after any tick | Stays still, and why |
|---|---|---|
| `ir-curve` (market-data) | one shift for the curve (a small tilt and per-tenor blur): every zero rate (%), every quote, every forward (%); each `df = exp(-zero/100 x tenor)`; `tenY` is the 10Y zero rate; `slope2s10s` is 10Y less 2Y in bp | `maturity`, instruments, conventions |
| `fx-spot` | `mid` with `bid` and `ask` by the same amount (the spread is constant); `change1d = mid / previous close - 1`; today's history point is `mid` | the earlier history |
| `equity` | `price`; `marketCap = price x shares x fx`; `change1d`; today's history point and today's bar (close is the price, high and low stretch, volume builds) | the 60-day bars before today, identifiers, beta |
| `equity-index` | `level`; `change1d`; today's history point | constituents and weights (a rebalance, not a tick) |
| `commodity` | `front`; today's bar (close, high, low, volume) | contract specification |
| `commodity-curve` | `front` with every month (parallel, a little blur); `front` is the first month; `roll`; `shape` flips between backwardation and contango when the first two months swap | open interest (end of day) |
| `credit-curve` | `spread5y` with every tenor's spread; `hazard = spread / 1e4 / (1 - recovery)`; `survival = exp(-hazard x tenor)`; `spread5y` is the 5Y point; `pd1y = 1 - survival(1Y)` | recovery, clause (terms) |
| `bond` | `price`; `yield = coupon - (price - 100) / (duration x 100)`; `zSpread` moves against the price by 100/duration bp a point; today's history point | the coupon schedule, the terms |
| `trade` (trading) | with leg PVs that add up to the MTM (96 swaps, exotics and options): the floating leg's PV walks, the other legs move against it a little, **`mtm` is the sum of the leg PVs** and each leg's cashflow PVs are rescaled to add up to their leg; otherwise `mtm` walks and the leg PVs follow in proportion (cross-currency swaps and swaptions, whose legs are in another currency or are the underlying); always `valuation.npv` is `mtm`, `pnl1d = mtm - opening MTM`, and the P&L explain still adds up (its biggest driver absorbs the move) | risk, sensitivities, schedules, the P&L history, execution and lifecycle (end of day) |
| `netting-set` (counterparty-risk) | each listed trade's MTM walks; each asset class's total is its trades (plus the unlisted ones, fixed) and `netMtm` is the sum; the exposure profile (`ee`, `pfe`) scales as one; `eePeak` and `pfePeak` are its maxima; `utilisation = pfePeak / limit` | collateral (moves on margin calls), the agreement |
| `book` (banking-core) | each of the ten largest trades' MTM walks; `mtm` is those plus the rest (fixed) | trade count and DV01 (end of day) |
| `key-risk-indicator` (operational-risk) | `value` (never below 0); `status` re-judged against `amber` and `red`; today's history point | thresholds and the definition (policy) |
| `card-account` (retail-banking) | `balance`; `utilisation = balance / limit` | statements and the category spend (closed periods) |
| `deposit-account` (retail-banking) | `balance`; the latest ledger line carries it as its running balance and its amount is how it got there (`amount = balance - previous line's balance`); this month's history point | the ledger before the latest line |
| `lcr` (liquidity-risk) | HQLA and each run-off balance walk; every flow is `balance x rate` (signs kept); `netOutflows = outflows - min(inflows, 75% of outflows)`; `lcr = hqla / netOutflows`; `buffer = hqla - netOutflows`; today's trend point | the minimum, the as-of date |
| `intraday-liquidity` (liquidity-risk) | the latest hour's usage; `peakUsage` is the highest hour of the day; `usagePct = peakUsage / available` | the large-payment log (what already happened) |
| `container` (logistics) | `currentC`; the reading of the current clock hour (`H1` to `H24`) is `currentC` | set point, humidity, power (settings) |
| `port`, `vessel`, `shipment` (logistics) | single readings (a congestion index, a berth wait, a position, a speed, an ETA delay): there is no detail list to keep in step | route and milestones (what has happened) |

Samples that are not `live` keep the same walk, so switching one to live needs no other change. To see it, open
`/v/ir-curve/CRV-USD-BASIS` with the demo source live: the 10Y cell, the other zero rates, the discount factors, the
forwards and the curve in the chart all change at each tick.

## JSON lines: one format for every loader

Every loader except Delta Lake's reads the same JSON lines, one per entity per business day:

```json
{"domain": "trading", "kind": "trade", "id": "MX-20000001", "date": "2026-09-30",
 "doc": "{\"tradeId\": \"MX-20000001\", …}", "columns": {"mtm": 1875863.0, "book": "BOOK-RATES-3", "counterparty.id": "CP-MERIDIAN-RE"}}
```

`columns` carries the fields the pack promotes, taken from the same document. Two generators write it:

```bash
python3 tools/packgen/banking/make_data.py --jsonl data/banking.jsonl                    # the samples, 10 days
uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py \
    --trades 50000 --days 3 --jsonl - | <a loader reading standard input>                # a book, streamed
```

So any store a new connector adds can be loaded with the same demo data.

## The scripts

| Script | What it does |
|---|---|
| `tools/load-files.sh [root] [--trades N] [--days D]` | `make_data.py --jsonl`, then `JsonlLoader` into `<root>/<domain>/<date>/<kind>.jsonl` |
| `tools/load-delta.sh [root] [--trades N] [--days D]` | `make_data.py --lake`, then `bulk_trades.py` |
| `tools/load-postgres.sh [jdbc-url] [--trades N] [--days D] [--keep-months N] [--user U] [--password P] [--writers N]` | `make_data.py --jsonl`, then `PostgresLoader` (samples with `--recreate`), then the bulk book streamed |
| `tools/load-iceberg.sh [root] [--trades N] [--days D] [--keep-days N] [--catalog rest --uri U --warehouse W]` | `make_data.py --jsonl`, then `IcebergLoader` (each day sorted by id), then the bulk book streamed |
| `tools/load-duckdb.sh [database] [--trades N] [--days D] [--keep-days N] [--memory-limit M] [--parsers N] [--threads N]` | `make_data.py --jsonl`, then `DuckDbLoader` (samples with `--recreate`), then the bulk book streamed; each load builds `<database>.loading` and renames it over the database |
| `tools/load-mongodb.sh [uri] [db] [--trades N] [--days D] [--keep-days N \| --ttl-days N] [--doc-format string\|bson]` | `make_data.py --jsonl`, then `MongoLoader`, then the bulk book streamed |
| `tools/load-redis.sh [uri] [--trades N] [--days D] [--ttl-days N] [--merge] [--publish] [--cluster]` | `make_data.py --jsonl`, then `RedisLoader`, then the bulk book streamed |
| `tools/load-aerospike.sh [hosts] [namespace] [--trades N] [--days D] [--ttl-days N]` | `make_data.py --jsonl`, then `AerospikeLoader`, then the bulk book streamed |
| `tools/packgen/banking/make_data.py` | the samples, `--lake`, `--jsonl`, `--check` |
| `tools/samplegen/bulk_trades.py` | a large trade book, into the lake or as JSON lines |

They need `uv` (Python), Java 21 or newer (`JAVA_HOME`; the scripts default to Java 21), and Maven's offline cache for the loaders (run `./mvnw install` once).
