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
# Scale benchmark

This document describes a repeatable benchmark of Drishti's stores. It loads the same trading book into each store at
several sizes, asks the same questions over HTTP, and fits a scaling curve to what it measured. It answers three
questions:

1. **How does each store scale with the size of the book?** [The measured curves](#4-results), one table per store.
2. **What would a million trades a day cost?** [The scaling fits](#5-scaling-fits-and-extrapolations) give a figure for
   each measure. These figures are **extrapolations from measured points, not measurements**, and are labelled as such
   everywhere. Where a store was measured at a million trades a day earlier, that measurement is
   [listed beside them](#6-earlier-measurements-at-a-million-trades-a-day).
3. **How do I run it on a real server?** [Section 7](#7-running-it-at-a-million-trades-a-day) gives the commands,
   the container and heap sizes, and the disk to expect.

Contents:

1. [What is measured](#1-what-is-measured)
2. [The harness](#2-the-harness)
3. [The run of 2026-10-01: machine and settings](#3-the-run-of-2026-10-01-machine-and-settings)
4. [Results](#4-results)
5. [Scaling fits and extrapolations](#5-scaling-fits-and-extrapolations)
6. [Earlier measurements at a million trades a day](#6-earlier-measurements-at-a-million-trades-a-day)
7. [Running it at a million trades a day](#7-running-it-at-a-million-trades-a-day)
8. [Reading the numbers, and their limits](#8-reading-the-numbers-and-their-limits)

## 1. What is measured

The book is the trading pack's generated book (`tools/samplegen/bulk_trades.py`): **N trades a day for three business
days** (2026-09-28 to 2026-09-30), plus the banking packs' samples (17,910 documents of reference, market, risk, credit
and collateral data). The first 750 trades are the samples; the rest are clones in each booking system's own number
range (`MX-30000000 …`, `CLY-4000000 …`, `END-1100000 …`). Every store is loaded with the project's own loader
(`tools/load-<store>.sh … --trades N --days 3`), in the layout its connector reads at scale.

Each measure is one question asked over HTTP from the same machine, with keep-alive connections:

| Measure | Request | What it exercises |
|---|---|---|
| type-ahead | `GET /api/v1/command/suggest?q=TRD CLY-400` | the in-memory id index |
| open a trade (view), first time | `GET /api/v1/views/trade/{id}`, a different trade each repetition | one document read, the Sutra, the links |
| open a trade (view), again | the same trade repeated | the cached path |
| raw document, today | `GET /api/v1/entities/trade/{id}/raw`, a different trade each time | one document read from the store |
| raw document, a past business day | `…/raw?asOf=2026-09-29`, a different trade each time | the same on another day (its id map) |
| three searches | `GET /api/v1/search?q=…`: `TRD where mtm < -50m order by mtm`; `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20`; `TRD book=BOOK-RATES-3` | a condition over every trade of the day, from the day's promoted columns |
| pick list | `GET /api/v1/search?q=TRD END-1100` | an id-prefix list (1,000 rows) |
| desk P&L | `GET /api/v1/entities/desk-pnl/DESK-RATES/raw` | a derived kind: a sum over the desk's trades |
| impact | `GET /api/v1/impact/netting-set/NS-SUMMIT-NY` | F8: every trade linked to a netting set, grouped |
| a search on another business day, first / again | the `mtm` search with `asOf=2026-09-28` | the first time loads that day's columns (cold); again is cached |

For every timed measure the harness asks the question **25 times one after another** and reports the **median and the
95th percentile**, then asks it from **8 concurrent clients for 5 seconds** and reports **requests per second**. The
cold search on another day is asked once (it is only cold once). Every search must answer from columns: the harness
records `partial` and `scanned` from each search's JSON, and a result counts as exact when `partial` is `false` and
`scanned` equals N.

Around the questions it records:

| Measure | How |
|---|---|
| load time | wall time of `tools/load-<store>.sh … --trades N --days 3`, end to end: the plugin build (offline Maven, a few seconds), the samples, and the generated book streamed into the loader |
| store size | on disk: `du -sb` of the lake or file (Delta, Iceberg, DuckDB, files), `pg_database_size` (PostgreSQL), `storageSize + indexSize` (MongoDB), the namespace's `data_used_bytes` (Aerospike); in memory: `used_memory` (Redis) |
| server start | from `java -jar` to `/actuator/health` answering 200 |
| columns ready | after start, until the newest day's search is exact (0 when it already is) |
| live heap | `jcmd <pid> GC.run`, then the `used` of `jcmd <pid> GC.heap_info`, after all questions |
| resident memory | the server's `VmRSS` (and peak `VmHWM`) at the end |

## 2. The harness

Three files in `tools/bench/`, standard library only (Python 3.11 or later, bash, `curl`, `bc`; Docker for the stores
that need a container):

| File | What it does |
|---|---|
| `scale.sh <store> <trades-a-day>` | one store, one size: starts the store's container if it needs one, loads it, starts a Drishti server with the store's Spring profile, waits for health, runs `measure.py`, then stops the server (by its process id) and removes the container, its volume and the scratch data |
| `measure.py` | waits until searches are exact, warms up (5 rounds of every question), measures, appends one JSON line per measure to the output file |
| `report.py <results.jsonl …>` | Markdown tables per store, run-to-run variance, a linear fit per measure with R², and the value of each fit at a million trades a day, labelled as an extrapolation |

```bash
tools/bench/scale.sh delta 10000                       # Delta Lake, engine native, 10,000 trades a day x 3 days
tools/bench/scale.sh postgres 50000 --out results.jsonl
tools/bench/scale.sh delta 10000 --run r2              # a second run of the same size, to see the variance
python3 tools/bench/report.py results.jsonl > report.md
```

Options of `scale.sh`:

| Option | Default | Meaning |
|---|---|---|
| `--days D` | 3 | business days of the book |
| `--run LABEL` | `r1` | tells repeated runs of one size apart |
| `--out FILE` | `$DRISHTI_BENCH_DIR/results.jsonl` | the JSON lines file to append to |
| `--port P` | 18993 | the benchmark server's port |
| `--heap SIZE` | `2g` | the server's `-Xmx` |
| `--container-memory SIZE` | `2g` | the store container's `-m`; Redis's `maxmemory` is three quarters of it |
| `--store-args "…"` | none | extra arguments for the store's container command (`-c shared_buffers=3GB` for PostgreSQL; for MongoDB they replace the default `--wiredTigerCacheSizeGB 0.5`) |
| `--reps`, `--clients`, `--seconds` | 25, 8, 5 | repetitions per measure; concurrent clients and seconds for requests per second |
| `--keep` | off | leave the container and scratch data in place (for a look afterwards) |

Environment: `DRISHTI_BENCH_DIR` (scratch: data, logs, the server's own files; default `/tmp/drishti-bench`),
`DRISHTI_BENCH_MIN_FREE_GB` (default 20: the run refuses to start, and stops between steps, when the machine has less
memory available), `DRISHTI_BENCH_JAVA_HOME` (default `/usr/lib/jvm/java-25-openjdk-amd64`; the script refuses an
older JDK).

The stores and what `scale.sh` starts for them:

| Store | Container | Server profile and settings |
|---|---|---|
| `delta` | none | the default profile, `DRISHTI_DELTA_ROOT` at the scratch lake, `DRISHTI_DELTA_ENGINE=native` |
| `postgres` | `postgres:18-alpine` (as `deploy/compose.data.yaml` and the tests), port 15432 | `postgres` |
| `duckdb` | none | `duckdb`, `DRISHTI_DUCKDB_PATH` at the scratch file |
| `files` | none | `files`, `DRISHTI_FILES_ROOT` at the scratch folder |
| `mongodb` | `mongo:7 --wiredTigerCacheSizeGB 0.5` (as in DEMO_DATA.md), port 37017 | `mongodb` |
| `redis` | `redis:8.2` (as the tests), no persistence, port 16379 | `redis` |
| `aerospike` | `aerospike/aerospike-server:8.1.2.5` (as `deploy/compose.data.yaml`), namespace `test` on a file, port 13000 | `aerospike` |
| `iceberg` | none (path-based tables) | `iceberg`, `DRISHTI_ICEBERG_ROOT` at the scratch folder |

The server runs `DRISHTI_PACKS=market-risk,counterparty-risk` (with the packs they extend: trading, market data,
banking core), `java -Xmx2g -XX:+UseCompactObjectHeaders -jar drishti-server/target/drishti-server-*-exec.jar`, its
identity database, governance and report folders in the scratch folder, the access log and the report scheduler off.

Each result line looks like this:

```json
{"date": "2026-10-01", "at": "2026-10-01T21:38:30+00:00", "run": "r1", "store": "postgres", "engine": "",
 "trades_per_day": 50000, "days": 3, "measure": "search_mtm", "unit": "ms", "median": 6.16, "p95": 7.71, "min": 4.07,
 "max": 7.73, "reps": 25, "errors": 0, "bytes": 25259, "scanned": 50000, "matched": 106, "partial": false, "exact": true,
 "server_ms": 3.36, "rps": 992.4, "rps_clients": 8, "rps_seconds": 5.0, "rps_errors": 0}
```

`server_ms` is the search's own `elapsedMs`; the difference to `median` is HTTP, JSON and the rest of the request.

## 3. The run of 2026-10-01: machine and settings

| | |
|---|---|
| Machine | a developer laptop: AMD Ryzen AI 9 HX 370 (12 cores, 24 threads, up to 5.16 GHz), 61 GB of memory, a Crucial P310 NVMe SSD (1 TB) |
| Shared with | the owner's own Drishti server, seven Docker containers (Kafka, ZooKeeper, PostgreSQL 16, RabbitMQ, ActiveMQ, pgAdmin, Kafka UI) and another build running browser tests; memory available stayed between 38 and 46 GB |
| Operating system | Ubuntu 26.04.1, Linux 7.0.4; Docker 29.8.2 |
| JDK | OpenJDK 25.0.4.1, `-Xmx2g -XX:+UseCompactObjectHeaders`, G1 (the default) |
| Drishti | 1.12.0, this commit; packs `market-risk,counterparty-risk` |
| Containers | each capped at 2 GB (`-m 2g`): PostgreSQL 18 (default settings, `shared_buffers` 128 MB), MongoDB 7 (WiredTiger cache 0.5 GB), Redis 8.2 (`maxmemory` 1.5 GB, no persistence), Aerospike CE 8.1.2.5 (namespace on a file) |
| Scratch | the lakes, the DuckDB file and the JSON-lines files sat on a memory-backed `/tmp` (tmpfs); the containers' volumes on the NVMe disk. At these sizes every store's data fits in the page cache, so all figures are warm-cache figures |
| Sizes | 10,000, 25,000 and 50,000 trades a day, three business days each; 10,000 twice (`r1`, `r2`) to show the run-to-run variance |
| Order | one store at a time, one size at a time: delta, postgres, duckdb, files, mongodb, redis, aerospike, iceberg; each run started from an empty store and ended with the container, its volume and the scratch data removed |
| Time | 43 minutes for the 32 runs (65–92 s each), plus one Redis run again after the fix below |
| Raw results | [`tools/bench/results/2026-10-01.jsonl`](../../tools/bench/results/2026-10-01.jsonl) (865 lines); the tables below are `python3 tools/bench/report.py tools/bench/results/2026-10-01.jsonl` |

Every search in every completed run was exact: `partial: false` and `scanned` equal to the trades a day, on every
store, at every size, on the newest day and on the older one.

**One run found a bug, fixed in the same change.** Redis at 25,000 trades a day never became searchable from columns:
the Redis loader cut a day's columns into chunks of 10,000 rows with `-(-rows / chunkRows)`, a ceiling division in
Python but a truncating one in Java, so 25,000 rows were written as two chunks and the last 5,000 rows were lost; the
connector then held no columns for the day and every search read documents. Days whose size is a multiple of 10,000
(the earlier tests and the million-trade run) were not affected. `DayColumns.chunks` now uses `Math.ceilDiv` (with a
test, `DayColumnsTest`), the same idiom in the Aerospike scan's partition ranges was corrected too (it only split the
scan into more ranges than asked, and looped forever with more than 4,096 `scan-threads`), and the 25,000 run was
repeated with the fixed build; its first attempt is listed under [runs that failed](#runs-that-failed).

## 4. Results

One table per store. In each timed cell: **median / p95** of 25 repetitions, then **requests per second** with 8
concurrent clients; ✓ means every search of that measure was exact. Below each table: the run-to-run variance of the
repeated size, and the store's linear fits ([section 5](#5-scaling-fits-and-extrapolations) explains them).

### Delta Lake (native)

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3; engine native.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 5.6 / 11.3 ms, 544/s | 9.8 / 12.8 ms, 367/s | 8.0 / 11.1 ms, 548/s | 6.3 / 8.4 ms, 469/s |
| open a trade (view), first time | 5.0 / 6.8 ms | 4.6 / 5.8 ms | 4.4 / 6.2 ms | 5.1 / 7.3 ms |
| open a trade (view), again | 3.8 / 5.0 ms, 651/s | 4.0 / 4.6 ms, 699/s | 4.2 / 5.1 ms, 679/s | 2.6 / 5.0 ms, 657/s |
| raw document, today | 14.6 / 24.3 ms | 14.0 / 24.6 ms | 12.0 / 30.4 ms | 12.6 / 27.0 ms |
| raw document, a past business day | 8.8 / 13.4 ms | 6.5 / 9.7 ms | 8.8 / 15.7 ms | 17.0 / 22.1 ms |
| `TRD where mtm < -50m order by mtm` | 1.9 / 2.5 ms, 2,986/s ✓ | 1.9 / 2.6 ms, 2,932/s ✓ | 2.2 / 4.6 ms, 1,896/s ✓ | 6.0 / 8.2 ms, 1,047/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 2.5 / 3.3 ms, 2,350/s ✓ | 1.6 / 2.0 ms, 2,423/s ✓ | 4.5 / 6.2 ms, 1,262/s ✓ | 7.7 / 9.7 ms, 657/s ✓ |
| `TRD book=BOOK-RATES-3` | 1.8 / 2.7 ms, 2,451/s ✓ | 2.2 / 3.1 ms, 2,489/s ✓ | 4.5 / 5.5 ms, 1,358/s ✓ | 7.2 / 9.1 ms, 717/s ✓ |
| pick list `TRD END-1100` | 0.9 / 1.2 ms, 3,553/s ✓ | 0.9 / 1.1 ms, 3,390/s ✓ | 1.3 / 1.6 ms, 2,867/s ✓ | 3.1 / 3.6 ms, 1,962/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 3.2 / 4.9 ms, 816/s | 3.0 / 3.5 ms, 812/s | 4.7 / 5.3 ms, 828/s | 4.9 / 6.1 ms, 797/s |
| impact `netting-set/NS-SUMMIT-NY` | 14.0 / 17.6 ms, 283/s | 15.5 / 17.9 ms, 256/s | 16.4 / 19.6 ms, 260/s | 17.3 / 25.5 ms, 198/s |
| search on another business day, first (cold) | 33.3 ms ✓ | 37.6 ms ✓ | 103 ms ✓ | 156 ms ✓ |
| search on another business day, again | 1.7 / 2.6 ms ✓ | 1.2 / 1.7 ms ✓ | 2.3 / 3.6 ms ✓ | 10.4 / 19.1 ms ✓ |
| opens of many trades, 8 clients | 758/s | 652/s | 740/s | 746/s |
| load time (loader script, end to end) | 7.2 s | 7.7 s | 7.8 s | 10.2 s |
| store size | 57.3 MB | 57.3 MB | 137 MB | 270 MB |
| server start to healthy | 6.8 s | 7.8 s | 6.2 s | 6.2 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.0 s |
| server live heap after a full GC | 99.0 MB | 98.9 MB | 110 MB | 129 MB |
| server resident memory (at the end) | 1,754 MB (peak 1,754) | 1,432 MB (peak 1,504) | 1,060 MB (peak 1,840) | 1,314 MB (peak 1,930) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 11% (median over the timed measures); the most is type-ahead `TRD CLY-400` (5.6 against 9.8 ms, 54%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 8.17 ms | -0.322 ms | 0.11 | flat: about 7.4 ms (does not rise with size) |
| open a trade (view), first time | 4.64 ms | +0.0663 ms | 0.14 | 11.3 ms (weak fit) |
| open a trade (view), again | 4.41 ms | -0.316 ms | 0.69 | flat: about 3.7 ms (does not rise with size) |
| raw document, today | 14.36 ms | -0.438 ms | 0.48 | flat: about 13.3 ms (does not rise with size) |
| raw document, a past business day | 4.78 ms | +2.32 ms | 0.89 | 237 ms |
| `TRD where mtm < -50m order by mtm` | 0.62 ms | +1.01 ms | 0.91 | 101 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 0.72 ms | +1.42 ms | 0.98 | 142 ms |
| `TRD book=BOOK-RATES-3` | 0.83 ms | +1.3 ms | 0.98 | 131 ms |
| pick list `TRD END-1100` | 0.29 ms | +0.532 ms | 0.95 | 53.5 ms |
| desk P&L `desk-pnl/DESK-RATES` | 2.81 ms | +0.469 ms | 0.77 | 49.7 ms |
| impact `netting-set/NS-SUMMIT-NY` | 14.28 ms | +0.642 ms | 0.73 | 78.5 ms |
| search on another business day, first (cold) | 10.18 ms | +30.4 ms | 0.96 | 3,049 ms |
| search on another business day, again | -1.35 ms | +2.2 ms | 0.92 | 219 ms |
| load time (loader script, end to end) | 6.64 s | +0.677 s | 0.92 | 74.4 s |
| store size | 4.19 MB | +53.1 MB | 1.00 | 5,311 MB |
| server start to healthy | 7.39 s | -0.268 s | 0.47 | flat: about 6.7 s (does not rise with size) |
| server live heap after a full GC | 91.40 MB | +7.48 MB | 1.00 | 840 MB |

### PostgreSQL

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 7.3 / 9.2 ms, 376/s | 7.3 / 11.1 ms, 531/s | 7.2 / 13.3 ms, 557/s | 9.0 / 12.8 ms, 358/s |
| open a trade (view), first time | 4.8 / 6.4 ms | 3.4 / 6.6 ms | 3.9 / 6.2 ms | 5.6 / 6.9 ms |
| open a trade (view), again | 4.6 / 6.2 ms, 602/s | 4.3 / 6.0 ms, 688/s | 3.4 / 4.6 ms, 729/s | 4.8 / 5.8 ms, 530/s |
| raw document, today | 3.0 / 9.7 ms | 1.9 / 9.2 ms | 1.8 / 10.1 ms | 4.0 / 15.2 ms |
| raw document, a past business day | 2.1 / 3.5 ms | 1.6 / 2.8 ms | 2.8 / 3.7 ms | 7.1 / 8.3 ms |
| `TRD where mtm < -50m order by mtm` | 2.1 / 3.2 ms, 2,278/s ✓ | 1.1 / 3.1 ms, 2,847/s ✓ | 2.6 / 4.4 ms, 1,832/s ✓ | 6.2 / 7.7 ms, 992/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 1.9 / 2.5 ms, 1,893/s ✓ | 1.7 / 2.4 ms, 2,247/s ✓ | 4.0 / 6.0 ms, 1,161/s ✓ | 8.7 / 11.4 ms, 621/s ✓ |
| `TRD book=BOOK-RATES-3` | 1.8 / 2.8 ms, 1,926/s ✓ | 1.7 / 3.1 ms, 2,339/s ✓ | 5.5 / 6.5 ms, 1,243/s ✓ | 6.9 / 9.0 ms, 726/s ✓ |
| pick list `TRD END-1100` | 1.3 / 1.8 ms, 2,996/s ✓ | 1.0 / 1.3 ms, 3,266/s ✓ | 1.7 / 2.4 ms, 2,671/s ✓ | 1.8 / 2.0 ms, 2,283/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 3.0 / 3.3 ms, 781/s | 2.9 / 3.2 ms, 765/s | 3.1 / 5.7 ms, 666/s | 4.6 / 5.9 ms, 750/s |
| impact `netting-set/NS-SUMMIT-NY` | 19.2 / 26.5 ms, 171/s | 16.8 / 19.1 ms, 182/s | 23.0 / 26.1 ms, 133/s | 20.7 / 24.9 ms, 140/s |
| search on another business day, first (cold) | 26.2 ms ✓ | 36.7 ms ✓ | 44.2 ms ✓ | 148 ms ✓ |
| search on another business day, again | 1.2 / 2.0 ms ✓ | 1.1 / 1.6 ms ✓ | 2.3 / 3.3 ms ✓ | 11.3 / 15.7 ms ✓ |
| opens of many trades, 8 clients | 769/s | 763/s | 712/s | 689/s |
| load time (loader script, end to end) | 14.4 s | 11.9 s | 14.1 s | 22.3 s |
| store size | 192 MB | 192 MB | 397 MB | 739 MB |
| server start to healthy | 6.8 s | 7.8 s | 6.2 s | 7.9 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.1 s |
| server live heap after a full GC | 89.8 MB | 89.4 MB | 99.4 MB | 116 MB |
| server resident memory (at the end) | 1,137 MB (peak 1,172) | 1,120 MB (peak 1,120) | 1,330 MB (peak 1,330) | 1,150 MB (peak 1,521) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 14% (median over the timed measures); the most is `TRD where mtm < -50m order by mtm` (2.1 against 1.1 ms, 60%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 6.66 ms | +0.435 ms | 0.84 | 50.2 ms |
| open a trade (view), first time | 3.57 ms | +0.358 ms | 0.51 | 39.4 ms |
| open a trade (view), again | 4.11 ms | +0.0726 ms | 0.05 | 11.4 ms (weak fit) |
| raw document, today | 1.77 ms | +0.384 ms | 0.45 | 40.1 ms (weak fit) |
| raw document, a past business day | 0.27 ms | +1.31 ms | 0.95 | 131 ms |
| `TRD where mtm < -50m order by mtm` | 0.32 ms | +1.13 ms | 0.94 | 113 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 0.02 ms | +1.72 ms | 1.00 | 172 ms |
| `TRD book=BOOK-RATES-3` | 0.87 ms | +1.31 ms | 0.88 | 132 ms |
| pick list `TRD END-1100` | 1.05 ms | +0.164 ms | 0.71 | 17.4 ms |
| desk P&L `desk-pnl/DESK-RATES` | 2.42 ms | +0.405 ms | 0.90 | 43.0 ms |
| impact `netting-set/NS-SUMMIT-NY` | 18.16 ms | +0.734 ms | 0.28 | 91.5 ms (weak fit) |
| search on another business day, first (cold) | -4.43 ms | +28.7 ms | 0.92 | 2,863 ms |
| search on another business day, again | -1.98 ms | +2.5 ms | 0.93 | 248 ms |
| load time (loader script, end to end) | 10.30 s | +2.26 s | 0.88 | 236 s |
| store size | 55.13 MB | +137 MB | 1.00 | 13,737 MB |
| server start to healthy | 6.86 s | +0.129 s | 0.10 | 19.8 s (weak fit) |
| server live heap after a full GC | 83.08 MB | +6.53 MB | 1.00 | 736 MB |

### DuckDB

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 7.8 / 10.2 ms, 536/s | 7.5 / 10.3 ms, 473/s | 8.5 / 10.2 ms, 502/s | 8.0 / 9.5 ms, 493/s |
| open a trade (view), first time | 5.3 / 6.8 ms | 4.7 / 6.3 ms | 3.8 / 6.4 ms | 4.1 / 6.4 ms |
| open a trade (view), again | 2.7 / 4.5 ms, 686/s | 4.3 / 5.2 ms, 711/s | 2.8 / 5.2 ms, 647/s | 2.8 / 3.6 ms, 732/s |
| raw document, today | 11.6 / 21.8 ms | 11.2 / 15.7 ms | 11.9 / 16.9 ms | 6.0 / 7.9 ms |
| raw document, a past business day | 6.0 / 7.4 ms | 5.9 / 7.9 ms | 6.7 / 11.9 ms | 16.6 / 20.3 ms |
| `TRD where mtm < -50m order by mtm` | 1.2 / 1.8 ms, 2,821/s ✓ | 1.1 / 1.5 ms, 2,824/s ✓ | 2.4 / 3.7 ms, 1,632/s ✓ | 3.6 / 5.1 ms, 984/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 1.7 / 2.5 ms, 2,292/s ✓ | 1.7 / 2.3 ms, 2,243/s ✓ | 4.3 / 6.4 ms, 1,115/s ✓ | 7.9 / 9.2 ms, 618/s ✓ |
| `TRD book=BOOK-RATES-3` | 2.3 / 3.0 ms, 2,393/s ✓ | 1.6 / 2.2 ms, 2,379/s ✓ | 3.7 / 5.1 ms, 1,212/s ✓ | 8.0 / 10.4 ms, 695/s ✓ |
| pick list `TRD END-1100` | 0.8 / 1.2 ms, 3,494/s ✓ | 0.8 / 1.1 ms, 3,386/s ✓ | 1.5 / 2.5 ms, 2,573/s ✓ | 3.4 / 4.3 ms, 1,840/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 3.0 / 4.0 ms, 770/s | 3.1 / 4.5 ms, 782/s | 3.2 / 5.2 ms, 784/s | 3.4 / 5.6 ms, 779/s |
| impact `netting-set/NS-SUMMIT-NY` | 21.2 / 24.5 ms, 100/s | 22.7 / 24.0 ms, 101/s | 23.0 / 26.1 ms, 94/s | 23.8 / 27.1 ms, 88/s |
| search on another business day, first (cold) | 38.3 ms ✓ | 35.5 ms ✓ | 62.5 ms ✓ | 115 ms ✓ |
| search on another business day, again | 1.3 / 2.7 ms ✓ | 1.2 / 2.0 ms ✓ | 2.9 / 4.4 ms ✓ | 4.1 / 6.5 ms ✓ |
| opens of many trades, 8 clients | 497/s | 476/s | 424/s | 336/s |
| load time (loader script, end to end) | 12.5 s | 12.1 s | 15.4 s | 22.3 s |
| store size | 36.5 MB | 36.5 MB | 68.3 MB | 122 MB |
| server start to healthy | 6.3 s | 6.3 s | 6.2 s | 6.2 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.0 s |
| server live heap after a full GC | 87.4 MB | 87.9 MB | 97.8 MB | 114 MB |
| server resident memory (at the end) | 2,261 MB (peak 2,346) | 2,182 MB (peak 2,209) | 2,420 MB (peak 2,533) | 2,404 MB (peak 2,670) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 7% (median over the timed measures); the most is open a trade (view), again (2.7 against 4.3 ms, 44%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 7.68 ms | +0.107 ms | 0.19 | 18.3 ms (weak fit) |
| open a trade (view), first time | 5.01 ms | -0.234 ms | 0.42 | flat: about 4.5 ms (does not rise with size) |
| open a trade (view), again | 3.57 ms | -0.186 ms | 0.22 | flat: about 3.1 ms (does not rise with size) |
| raw document, today | 13.29 ms | -1.31 ms | 0.79 | flat: about 10.2 ms (does not rise with size) |
| raw document, a past business day | 2.55 ms | +2.62 ms | 0.90 | 264 ms |
| `TRD where mtm < -50m order by mtm` | 0.57 ms | +0.635 ms | 0.98 | 64.1 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 0.23 ms | +1.55 ms | 1.00 | 155 ms |
| `TRD book=BOOK-RATES-3` | 0.29 ms | +1.51 ms | 0.98 | 151 ms |
| pick list `TRD END-1100` | 0.07 ms | +0.646 ms | 0.99 | 64.6 ms |
| desk P&L `desk-pnl/DESK-RATES` | 2.98 ms | +0.0816 ms | 0.98 | 11.1 ms |
| impact `netting-set/NS-SUMMIT-NY` | 21.59 ms | +0.455 ms | 0.64 | 67.1 ms |
| search on another business day, first (cold) | 16.56 ms | +19.5 ms | 1.00 | 1,965 ms |
| search on another business day, again | 0.69 ms | +0.717 ms | 0.95 | 72.4 ms |
| load time (loader script, end to end) | 9.65 s | +2.49 s | 0.99 | 258 s |
| store size | 14.98 MB | +21.4 MB | 1.00 | 2,158 MB |
| server start to healthy | 6.27 s | -0.00837 s | 0.54 | flat: about 6.3 s (does not rise with size) |
| server live heap after a full GC | 81.29 MB | +6.47 MB | 1.00 | 728 MB |

### JSON-lines files

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 8.7 / 13.2 ms, 541/s | 7.4 / 8.8 ms, 460/s | 8.9 / 13.4 ms, 542/s | 7.8 / 9.8 ms, 544/s |
| open a trade (view), first time | 3.5 / 4.8 ms | 5.1 / 6.8 ms | 3.4 / 5.5 ms | 4.3 / 6.5 ms |
| open a trade (view), again | 3.8 / 5.4 ms, 624/s | 3.3 / 5.1 ms, 569/s | 4.0 / 4.8 ms, 571/s | 4.0 / 6.3 ms, 580/s |
| raw document, today | 2.0 / 2.8 ms | 1.8 / 2.4 ms | 2.4 / 2.8 ms | 1.1 / 2.5 ms |
| raw document, a past business day | 1.1 / 2.5 ms | 1.1 / 1.8 ms | 0.9 / 2.4 ms | 2.7 / 3.7 ms |
| `TRD where mtm < -50m order by mtm` | 1.1 / 2.5 ms, 2,861/s ✓ | 1.4 / 2.0 ms, 2,516/s ✓ | 2.1 / 3.5 ms, 1,728/s ✓ | 4.0 / 9.3 ms, 958/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 1.6 / 2.0 ms, 2,253/s ✓ | 1.8 / 2.4 ms, 2,157/s ✓ | 3.9 / 5.7 ms, 1,201/s ✓ | 7.8 / 10.4 ms, 623/s ✓ |
| `TRD book=BOOK-RATES-3` | 1.8 / 2.6 ms, 2,484/s ✓ | 3.1 / 4.7 ms, 1,758/s ✓ | 4.3 / 5.4 ms, 1,325/s ✓ | 7.7 / 10.0 ms, 683/s ✓ |
| pick list `TRD END-1100` | 0.8 / 1.2 ms, 3,584/s ✓ | 1.2 / 1.8 ms, 2,519/s ✓ | 2.0 / 2.5 ms, 2,647/s ✓ | 3.2 / 4.4 ms, 1,947/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 4.8 / 5.4 ms, 726/s | 5.7 / 7.1 ms, 666/s | 5.2 / 6.7 ms, 738/s | 3.5 / 5.5 ms, 787/s |
| impact `netting-set/NS-SUMMIT-NY` | 16.8 / 19.2 ms, 255/s | 18.9 / 21.7 ms, 250/s | 19.0 / 21.4 ms, 215/s | 17.1 / 21.1 ms, 187/s |
| search on another business day, first (cold) | 192 ms ✓ | 291 ms ✓ | 213 ms ✓ | 353 ms ✓ |
| search on another business day, again | 1.1 / 1.8 ms ✓ | 1.4 / 2.5 ms ✓ | 2.3 / 5.1 ms ✓ | 4.8 / 9.4 ms ✓ |
| opens of many trades, 8 clients | 735/s | 807/s | 744/s | 717/s |
| load time (loader script, end to end) | 9.2 s | 9.0 s | 11.2 s | 15.4 s |
| store size | 310 MB | 310 MB | 688 MB | 1,322 MB |
| server start to healthy | 6.2 s | 6.2 s | 6.7 s | 7.3 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.0 s |
| server live heap after a full GC | 88.9 MB | 90.2 MB | 102 MB | 122 MB |
| server resident memory (at the end) | 1,133 MB (peak 1,133) | 1,225 MB (peak 1,225) | 1,322 MB (peak 1,399) | 1,240 MB (peak 1,563) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 17% (median over the timed measures); the most is `TRD book=BOOK-RATES-3` (1.8 against 3.1 ms, 52%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 8.30 ms | -0.0423 ms | 0.01 | flat: about 8.2 ms (does not rise with size) |
| open a trade (view), first time | 4.06 ms | -0.00152 ms | 0.00 | flat: about 4.1 ms (does not rise with size) |
| open a trade (view), again | 3.49 ms | +0.124 ms | 0.50 | 15.9 ms (weak fit) |
| raw document, today | 2.24 ms | -0.178 ms | 0.39 | flat: about 1.8 ms (does not rise with size) |
| raw document, a past business day | 0.50 ms | +0.399 ms | 0.77 | 40.4 ms |
| `TRD where mtm < -50m order by mtm` | 0.50 ms | +0.693 ms | 0.99 | 69.7 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 0.11 ms | +1.54 ms | 1.00 | 154 ms |
| `TRD book=BOOK-RATES-3` | 1.15 ms | +1.29 ms | 0.95 | 131 ms |
| pick list `TRD END-1100` | 0.50 ms | +0.549 ms | 0.98 | 55.4 ms |
| desk P&L `desk-pnl/DESK-RATES` | 5.77 ms | -0.411 ms | 0.73 | flat: about 4.8 ms (does not rise with size) |
| impact `netting-set/NS-SUMMIT-NY` | 18.34 ms | -0.175 ms | 0.08 | flat: about 17.9 ms (does not rise with size) |
| search on another business day, first (cold) | 198 ms | +27.1 ms | 0.47 | 2,904 ms (weak fit) |
| search on another business day, again | 0.33 ms | +0.872 ms | 0.99 | 87.5 ms |
| load time (loader script, end to end) | 7.47 s | +1.56 s | 1.00 | 164 s |
| store size | 56.33 MB | +253 MB | 1.00 | 25,362 MB |
| server start to healthy | 5.99 s | +0.261 s | 0.98 | 32.1 s |
| server live heap after a full GC | 81.57 MB | +8.12 MB | 1.00 | 893 MB |

### MongoDB

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 6.3 / 10.4 ms, 477/s | 7.7 / 10.6 ms, 448/s | 6.8 / 8.7 ms, 513/s | 6.9 / 11.8 ms, 479/s |
| open a trade (view), first time | 4.8 / 6.6 ms | 5.5 / 6.5 ms | 3.5 / 6.4 ms | 5.6 / 7.0 ms |
| open a trade (view), again | 2.5 / 5.3 ms, 684/s | 4.8 / 5.9 ms, 658/s | 4.4 / 5.1 ms, 678/s | 4.5 / 5.8 ms, 650/s |
| raw document, today | 1.4 / 2.6 ms | 2.1 / 4.9 ms | 1.7 / 4.1 ms | 3.0 / 4.2 ms |
| raw document, a past business day | 1.3 / 2.3 ms | 2.1 / 3.5 ms | 2.7 / 4.0 ms | 6.2 / 7.2 ms |
| `TRD where mtm < -50m order by mtm` | 1.0 / 1.2 ms, 2,806/s ✓ | 1.4 / 3.2 ms, 2,428/s ✓ | 2.5 / 4.4 ms, 1,763/s ✓ | 4.0 / 5.9 ms, 918/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 2.3 / 3.1 ms, 2,298/s ✓ | 2.7 / 3.3 ms, 2,023/s ✓ | 4.0 / 5.9 ms, 1,163/s ✓ | 9.6 / 11.0 ms, 556/s ✓ |
| `TRD book=BOOK-RATES-3` | 1.6 / 2.4 ms, 2,364/s ✓ | 3.1 / 4.5 ms, 2,042/s ✓ | 4.3 / 5.2 ms, 1,392/s ✓ | 9.4 / 11.9 ms, 637/s ✓ |
| pick list `TRD END-1100` | 1.0 / 1.4 ms, 3,065/s ✓ | 1.1 / 1.5 ms, 2,952/s ✓ | 1.0 / 1.5 ms, 3,010/s ✓ | 2.9 / 3.3 ms, 1,776/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 6.6 / 8.3 ms, 620/s | 3.1 / 3.8 ms, 782/s | 4.6 / 5.5 ms, 794/s | 3.3 / 5.6 ms, 719/s |
| impact `netting-set/NS-SUMMIT-NY` | 17.9 / 20.9 ms, 156/s | 18.5 / 21.3 ms, 145/s | 19.9 / 23.9 ms, 154/s | 22.8 / 27.2 ms, 115/s |
| search on another business day, first (cold) | 14.1 ms ✓ | 15.5 ms ✓ | 25.6 ms ✓ | 184 ms ✓ |
| search on another business day, again | 1.1 / 1.2 ms ✓ | 1.5 / 2.8 ms ✓ | 2.1 / 3.4 ms ✓ | 10.3 / 17.7 ms ✓ |
| opens of many trades, 8 clients | 788/s | 695/s | 727/s | 671/s |
| load time (loader script, end to end) | 10.2 s | 12.7 s | 16.2 s | 17.1 s |
| store size | 47.1 MB | 46.9 MB | 104 MB | 202 MB |
| server start to healthy | 5.8 s | 6.3 s | 6.2 s | 6.3 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.0 s |
| server live heap after a full GC | 109 MB | 109 MB | 132 MB | 174 MB |
| server resident memory (at the end) | 1,254 MB (peak 1,254) | 1,086 MB (peak 1,195) | 1,330 MB (peak 1,330) | 1,406 MB (peak 1,726) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 26% (median over the timed measures); the most is desk P&L `desk-pnl/DESK-RATES` (6.6 against 3.1 ms, 72%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 6.99 ms | -0.0322 ms | 0.01 | flat: about 6.9 ms (does not rise with size) |
| open a trade (view), first time | 4.63 ms | +0.0989 ms | 0.04 | 14.5 ms (weak fit) |
| open a trade (view), again | 3.55 ms | +0.211 ms | 0.14 | 24.6 ms (weak fit) |
| raw document, today | 1.35 ms | +0.29 ms | 0.67 | 30.3 ms |
| raw document, a past business day | 0.44 ms | +1.1 ms | 0.95 | 111 ms |
| `TRD where mtm < -50m order by mtm` | 0.60 ms | +0.681 ms | 0.98 | 68.7 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 0.46 ms | +1.77 ms | 0.97 | 177 ms |
| `TRD book=BOOK-RATES-3` | 0.41 ms | +1.75 ms | 0.96 | 176 ms |
| pick list `TRD END-1100` | 0.43 ms | +0.449 ms | 0.84 | 45.3 ms |
| desk P&L `desk-pnl/DESK-RATES` | 5.28 ms | -0.37 ms | 0.19 | flat: about 4.4 ms (does not rise with size) |
| impact `netting-set/NS-SUMMIT-NY` | 17.04 ms | +1.15 ms | 0.98 | 132 ms |
| search on another business day, first (cold) | -39.26 ms | +41.7 ms | 0.90 | 4,133 ms |
| search on another business day, again | -1.53 ms | +2.22 ms | 0.91 | 221 ms |
| load time (loader script, end to end) | 10.64 s | +1.44 s | 0.73 | 155 s |
| store size | 8.07 MB | +38.6 MB | 1.00 | 3,872 MB |
| server start to healthy | 5.99 s | +0.0624 s | 0.26 | 12.2 s (weak fit) |
| server live heap after a full GC | 92.72 MB | +16.1 MB | 1.00 | 1,702 MB |

### Redis

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 7.2 / 9.3 ms, 537/s | 9.6 / 13.6 ms, 345/s | 7.3 / 9.4 ms, 442/s | 10.4 / 14.1 ms, 346/s |
| open a trade (view), first time | 6.0 / 7.5 ms | 7.0 / 9.2 ms | 6.9 / 8.5 ms | 6.6 / 8.8 ms |
| open a trade (view), again | 5.4 / 6.6 ms, 525/s | 5.2 / 6.7 ms, 589/s | 5.9 / 7.0 ms, 556/s | 5.8 / 7.5 ms, 392/s |
| raw document, today | 1.1 / 2.5 ms | 0.9 / 1.1 ms | 1.5 / 1.9 ms | 2.3 / 2.9 ms |
| raw document, a past business day | 1.1 / 1.8 ms | 1.1 / 1.8 ms | 1.6 / 2.3 ms | 6.7 / 8.6 ms |
| `TRD where mtm < -50m order by mtm` | 2.0 / 2.8 ms, 2,722/s ✓ | 1.3 / 2.2 ms, 2,650/s ✓ | 3.1 / 4.4 ms, 1,081/s ✓ | 7.9 / 10.9 ms, 696/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 1.7 / 3.1 ms, 2,254/s ✓ | 1.7 / 3.5 ms, 2,103/s ✓ | 7.2 / 9.3 ms, 869/s ✓ | 10.6 / 12.6 ms, 588/s ✓ |
| `TRD book=BOOK-RATES-3` | 1.5 / 2.2 ms, 2,466/s ✓ | 3.1 / 3.9 ms, 2,161/s ✓ | 5.7 / 7.5 ms, 1,079/s ✓ | 8.4 / 10.5 ms, 676/s ✓ |
| pick list `TRD END-1100` | 0.6 / 1.9 ms, 3,579/s ✓ | 1.1 / 1.6 ms, 3,164/s ✓ | 1.4 / 2.3 ms, 2,238/s ✓ | 2.9 / 5.3 ms, 1,855/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 5.5 / 6.2 ms, 749/s | 3.3 / 7.3 ms, 758/s | 3.5 / 5.8 ms, 666/s | 4.9 / 5.9 ms, 751/s |
| impact `netting-set/NS-SUMMIT-NY` | 25.0 / 30.1 ms, 114/s | 20.7 / 23.7 ms, 96/s | 31.4 / 37.9 ms, 93/s | 30.9 / 40.8 ms, 71/s |
| search on another business day, first (cold) | 17.6 ms ✓ | 17.3 ms ✓ | 9.4 ms ✓ | 46.2 ms ✓ |
| search on another business day, again | 1.9 / 2.7 ms ✓ | 2.6 / 2.9 ms ✓ | 3.5 / 5.2 ms ✓ | 17.6 / 21.6 ms ✓ |
| opens of many trades, 8 clients | 682/s | 498/s | 582/s | 367/s |
| load time (loader script, end to end) | 13.4 s | 14.6 s | 17.2 s | 20.2 s |
| store size (Redis `used_memory`) | 46.2 MB | 46.1 MB | 97.9 MB | 183 MB |
| server start to healthy | 6.2 s | 9.4 s | 8.8 s | 8.4 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.1 s |
| server live heap after a full GC | 106 MB | 106 MB | 116 MB | 132 MB |
| server resident memory (at the end) | 1,314 MB (peak 1,314) | 1,201 MB (peak 1,262) | 1,396 MB (peak 1,396) | 1,208 MB (peak 1,532) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 21% (median over the timed measures); the most is `TRD book=BOOK-RATES-3` (1.5 against 3.1 ms, 70%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 7.46 ms | +0.49 ms | 0.33 | 56.5 ms (weak fit) |
| open a trade (view), first time | 6.60 ms | +0.0207 ms | 0.01 | 8.7 ms (weak fit) |
| open a trade (view), again | 5.28 ms | +0.123 ms | 0.53 | 17.6 ms |
| raw document, today | 0.71 ms | +0.323 ms | 0.98 | 33.0 ms |
| raw document, a past business day | -0.63 ms | +1.38 ms | 0.91 | 137 ms |
| `TRD where mtm < -50m order by mtm` | -0.10 ms | +1.55 ms | 0.97 | 155 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | -0.04 ms | +2.25 ms | 0.94 | 225 ms |
| `TRD book=BOOK-RATES-3` | 1.04 ms | +1.53 ms | 0.92 | 154 ms |
| pick list `TRD END-1100` | 0.32 ms | +0.498 ms | 0.94 | 50.1 ms |
| desk P&L `desk-pnl/DESK-RATES` | 4.01 ms | +0.119 ms | 0.05 | 15.9 ms (weak fit) |
| impact `netting-set/NS-SUMMIT-NY` | 22.03 ms | +2.09 ms | 0.59 | 231 ms |
| search on another business day, first (cold) | 6.08 ms | +6.96 ms | 0.66 | 702 ms |
| search on another business day, again | -2.62 ms | +3.8 ms | 0.91 | 378 ms |
| load time (loader script, end to end) | 12.63 s | +1.57 s | 0.96 | 169 s |
| store size | 11.84 MB | +34.3 MB | 1.00 | 3,446 MB |
| server start to healthy | 7.84 s | +0.15 s | 0.04 | 22.9 s (weak fit) |
| server live heap after a full GC | 99.58 MB | +6.55 MB | 1.00 | 754 MB |

### Aerospike

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 22.8 / 40.2 ms, 184/s | 9.2 / 12.7 ms, 344/s | 9.1 / 11.2 ms, 360/s | 8.9 / 11.7 ms, 433/s |
| open a trade (view), first time | 8.3 / 15.0 ms | 6.3 / 7.0 ms | 5.2 / 6.6 ms | 4.7 / 6.3 ms |
| open a trade (view), again | 6.5 / 9.0 ms, 377/s | 5.7 / 6.7 ms, 444/s | 4.6 / 6.4 ms, 603/s | 4.8 / 5.2 ms, 598/s |
| raw document, today | 3.4 / 4.2 ms | 6.4 / 26.1 ms | 2.4 / 3.2 ms | 2.8 / 3.7 ms |
| raw document, a past business day | 3.5 / 4.6 ms | 6.1 / 9.8 ms | 2.3 / 2.9 ms | 5.0 / 7.9 ms |
| `TRD where mtm < -50m order by mtm` | 1.5 / 2.3 ms, 1,436/s ✓ | 5.0 / 9.5 ms, 1,809/s ✓ | 2.2 / 2.9 ms, 1,408/s ✓ | 5.0 / 8.0 ms, 983/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 4.0 / 5.1 ms, 1,430/s ✓ | 3.9 / 4.9 ms, 1,824/s ✓ | 6.4 / 7.5 ms, 1,094/s ✓ | 8.0 / 13.6 ms, 547/s ✓ |
| `TRD book=BOOK-RATES-3` | 2.6 / 5.8 ms, 1,090/s ✓ | 5.6 / 6.1 ms, 1,679/s ✓ | 3.2 / 4.4 ms, 1,307/s ✓ | 8.1 / 13.9 ms, 590/s ✓ |
| pick list `TRD END-1100` | 2.1 / 4.1 ms, 1,398/s ✓ | 1.1 / 1.6 ms, 2,516/s ✓ | 0.9 / 1.1 ms, 2,866/s ✓ | 6.2 / 7.3 ms, 1,168/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 5.9 / 7.8 ms, 688/s | 6.1 / 7.6 ms, 624/s | 3.2 / 5.0 ms, 570/s | 5.5 / 7.5 ms, 797/s |
| impact `netting-set/NS-SUMMIT-NY` | 165 / 220 ms, 5/s | 144 / 205 ms, 7/s | 601 / 743 ms, 6/s | 174 / 191 ms, 6/s |
| search on another business day, first (cold) | 66.6 ms ✓ | 109 ms ✓ | 189 ms ✓ | 426 ms ✓ |
| search on another business day, again | 2.5 / 3.5 ms ✓ | 2.7 / 3.7 ms ✓ | 3.1 / 4.0 ms ✓ | 6.5 / 8.7 ms ✓ |
| opens of many trades, 8 clients | 326/s | 553/s | 607/s | 578/s |
| load time (loader script, end to end) | 14.4 s | 16.5 s | 14.9 s | 18.8 s |
| store size | 276 MB | 276 MB | 613 MB | 1,176 MB |
| server start to healthy | 11.0 s | 7.4 s | 7.3 s | 6.8 s |
| after start: newest day searchable from columns | 0.0 s | 0.1 s | 0.1 s | 0.1 s |
| server live heap after a full GC | 88.9 MB | 88.4 MB | 98.8 MB | 117 MB |
| server resident memory (at the end) | 989 MB (peak 1,170) | 1,102 MB (peak 1,229) | 1,271 MB (peak 1,271) | 1,289 MB (peak 1,390) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 48% (median over the timed measures); the most is `TRD where mtm < -50m order by mtm` (1.5 against 5.0 ms, 110%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 16.85 ms | -1.83 ms | 0.25 | flat: about 12.5 ms (does not rise with size) |
| open a trade (view), first time | 7.68 ms | -0.662 ms | 0.60 | flat: about 6.1 ms (does not rise with size) |
| open a trade (view), again | 6.19 ms | -0.33 ms | 0.53 | flat: about 5.4 ms (does not rise with size) |
| raw document, today | 5.02 ms | -0.55 ms | 0.32 | flat: about 3.7 ms (does not rise with size) |
| raw document, a past business day | 4.13 ms | +0.032 ms | 0.00 | 7.3 ms (weak fit) |
| `TRD where mtm < -50m order by mtm` | 2.42 ms | +0.423 ms | 0.19 | 44.7 ms (weak fit) |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 3.16 ms | +1.02 ms | 0.95 | 105 ms |
| `TRD book=BOOK-RATES-3` | 2.60 ms | +0.957 ms | 0.52 | 98.3 ms |
| pick list `TRD END-1100` | -0.09 ms | +1.12 ms | 0.74 | 112 ms |
| desk P&L `desk-pnl/DESK-RATES` | 5.50 ms | -0.141 ms | 0.04 | flat: about 5.2 ms (does not rise with size) |
| impact `netting-set/NS-SUMMIT-NY` | 247 ms | +10 ms | 0.01 | 1,248 ms (weak fit) |
| search on another business day, first (cold) | -2.76 ms | +84.4 ms | 0.98 | 8,432 ms |
| search on another business day, again | 1.43 ms | +0.963 ms | 0.92 | 97.7 ms |
| load time (loader script, end to end) | 14.19 s | +0.825 s | 0.61 | 96.7 s |
| store size | 50.63 MB | +225 MB | 1.00 | 22,558 MB |
| server start to healthy | 9.54 s | -0.606 s | 0.35 | flat: about 8.1 s (does not rise with size) |
| server live heap after a full GC | 81.32 MB | +7.18 MB | 1.00 | 799 MB |

### Apache Iceberg

Trades a day x business days: 10,000 x 3, 10,000 x 3 (r2), 25,000 x 3, 50,000 x 3.

| Measure | 10,000 | 10,000 (r2) | 25,000 | 50,000 |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 8.4 / 11.5 ms, 371/s | 8.0 / 9.9 ms, 411/s | 8.7 / 11.7 ms, 334/s | 7.3 / 10.1 ms, 409/s |
| open a trade (view), first time | 5.7 / 7.8 ms | 4.3 / 5.9 ms | 5.3 / 6.8 ms | 4.5 / 6.1 ms |
| open a trade (view), again | 4.1 / 12.6 ms, 599/s | 4.5 / 5.6 ms, 622/s | 4.1 / 5.5 ms, 577/s | 4.3 / 5.2 ms, 627/s |
| raw document, today | 11.3 / 23.8 ms | 10.9 / 19.2 ms | 10.1 / 19.8 ms | 14.1 / 23.9 ms |
| raw document, a past business day | 10.9 / 19.9 ms | 9.2 / 11.8 ms | 11.5 / 20.7 ms | 17.1 / 31.3 ms |
| `TRD where mtm < -50m order by mtm` | 2.7 / 4.3 ms, 2,025/s ✓ | 3.2 / 4.0 ms, 2,474/s ✓ | 4.3 / 6.9 ms, 1,360/s ✓ | 5.8 / 8.7 ms, 836/s ✓ |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 2.7 / 3.2 ms, 1,800/s ✓ | 3.1 / 3.9 ms, 2,092/s ✓ | 5.0 / 7.0 ms, 967/s ✓ | 9.6 / 14.2 ms, 594/s ✓ |
| `TRD book=BOOK-RATES-3` | 3.0 / 3.8 ms, 2,047/s ✓ | 2.2 / 2.7 ms, 2,164/s ✓ | 4.5 / 6.2 ms, 892/s ✓ | 6.6 / 7.5 ms, 688/s ✓ |
| pick list `TRD END-1100` | 1.0 / 1.9 ms, 2,902/s ✓ | 0.9 / 1.1 ms, 2,958/s ✓ | 2.3 / 3.4 ms, 2,092/s ✓ | 2.0 / 3.5 ms, 2,049/s ✓ |
| desk P&L `desk-pnl/DESK-RATES` | 3.1 / 5.2 ms, 629/s | 6.8 / 9.2 ms, 641/s | 3.6 / 6.3 ms, 679/s | 4.0 / 7.2 ms, 729/s |
| impact `netting-set/NS-SUMMIT-NY` | 19.3 / 21.6 ms, 242/s | 20.0 / 22.6 ms, 170/s | 21.6 / 24.6 ms, 183/s | 24.3 / 29.6 ms, 155/s |
| search on another business day, first (cold) | 33.4 ms ✓ | 57.3 ms ✓ | 123 ms ✓ | 264 ms ✓ |
| search on another business day, again | 2.6 / 3.3 ms ✓ | 3.8 / 4.8 ms ✓ | 5.4 / 12.5 ms ✓ | 5.4 / 10.3 ms ✓ |
| opens of many trades, 8 clients | 699/s | 408/s | 576/s | 658/s |
| load time (loader script, end to end) | 18.2 s | 17.4 s | 22.4 s | 26.4 s |
| store size | 34.8 MB | 34.8 MB | 71.5 MB | 133 MB |
| server start to healthy | 7.8 s | 7.3 s | 8.9 s | 8.8 s |
| after start: newest day searchable from columns | 0.0 s | 0.0 s | 0.0 s | 0.1 s |
| server live heap after a full GC | 98.2 MB | 102 MB | 110 MB | 127 MB |
| server resident memory (at the end) | 1,428 MB (peak 1,428) | 1,026 MB (peak 1,642) | 1,267 MB (peak 1,562) | 1,170 MB (peak 1,718) |

Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. ✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.

Run-to-run variance at 10,000 (r1 against r2): the medians differ by 17% (median over the timed measures); the most is desk P&L `desk-pnl/DESK-RATES` (3.1 against 6.8 ms, 74%).

Linear fit per measure over every run (value = a + b x trades a day), and its value at 1,000,000 trades a day — **an extrapolation from the measured points, not a measurement**:

| Measure | a | b per 10,000 trades | R² | at 1,000,000 (extrapolated) |
|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 8.58 ms | -0.208 ms | 0.47 | flat: about 8.1 ms (does not rise with size) |
| open a trade (view), first time | 5.24 ms | -0.115 ms | 0.11 | flat: about 5.0 ms (does not rise with size) |
| open a trade (view), again | 4.30 ms | -0.0191 ms | 0.03 | flat: about 4.3 ms (does not rise with size) |
| raw document, today | 9.87 ms | +0.734 ms | 0.62 | 83.2 ms |
| raw document, a past business day | 7.99 ms | +1.75 ms | 0.93 | 183 ms |
| `TRD where mtm < -50m order by mtm` | 2.31 ms | +0.712 ms | 0.97 | 73.5 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 1.13 ms | +1.67 ms | 0.99 | 168 ms |
| `TRD book=BOOK-RATES-3` | 1.70 ms | +0.993 ms | 0.97 | 101 ms |
| pick list `TRD END-1100` | 0.87 ms | +0.286 ms | 0.58 | 29.4 ms |
| desk P&L `desk-pnl/DESK-RATES` | 4.98 ms | -0.25 ms | 0.08 | flat: about 4.4 ms (does not rise with size) |
| impact `netting-set/NS-SUMMIT-NY` | 18.52 ms | +1.17 ms | 0.99 | 136 ms |
| search on another business day, first (cold) | -10.19 ms | +54.6 ms | 0.99 | 5,451 ms |
| search on another business day, again | 2.95 ms | +0.562 ms | 0.62 | 59.2 ms |
| load time (loader script, end to end) | 15.96 s | +2.16 s | 0.97 | 232 s |
| store size | 10.28 MB | +24.5 MB | 1.00 | 2,462 MB |
| server start to healthy | 7.40 s | +0.328 s | 0.62 | 40.2 s |
| server live heap after a full GC | 93.37 MB | +6.7 MB | 0.99 | 763 MB |

### Runs that failed

- Redis, 25,000 trades a day (r1): exit 143 — stopped by hand after 4 minutes: the newest day's columns never loaded (searches read documents) because the loader wrote ceil-divided chunks with Java's truncating division (25,000 rows in chunks of 10,000: 2 chunks, the last 5,000 rows left out); fixed (Math.ceilDiv in DayColumns.chunks) and run again

## 5. Scaling fits and extrapolations

For each store and measure, `report.py` fits **value = a + b × N** (N trades a day) by least squares over every run of
that store (four points: 10,000 twice, 25,000, 50,000), and gives R². The value of the fit at 1,000,000 trades a day is
an **extrapolation**: a straight line drawn twenty times beyond the largest measured point. When the fitted slope is
not above zero (the measure does not grow with the book: type-ahead, opening a cached trade), the figure is the mean
of the measured points and is marked *flat*. Resident memory and the time until columns are ready are listed, not
fitted.

### 5.1 Across stores, measured

At the largest size each store ran (median ms of the run labelled r1; requests per second with 8 clients in brackets):

| Measure | Delta Lake (native) (50,000) | PostgreSQL (50,000) | DuckDB (50,000) | JSON-lines files (50,000) | MongoDB (50,000) | Redis (50,000) | Aerospike (50,000) | Apache Iceberg (50,000) |
|---|---|---|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 6.3 (469/s) | 9.0 (358/s) | 8.0 (493/s) | 7.8 (544/s) | 6.9 (479/s) | 10.4 (346/s) | 8.9 (433/s) | 7.3 (409/s) |
| open a trade (view), first time | 5.1 | 5.6 | 4.1 | 4.3 | 5.6 | 6.6 | 4.7 | 4.5 |
| open a trade (view), again | 2.6 (657/s) | 4.8 (530/s) | 2.8 (732/s) | 4.0 (580/s) | 4.5 (650/s) | 5.8 (392/s) | 4.8 (598/s) | 4.3 (627/s) |
| raw document, today | 12.6 | 4.0 | 6.0 | 1.1 | 3.0 | 2.3 | 2.8 | 14.1 |
| raw document, a past business day | 17.0 | 7.1 | 16.6 | 2.7 | 6.2 | 6.7 | 5.0 | 17.1 |
| `TRD where mtm < -50m order by mtm` | 6.0 (1,047/s) | 6.2 (992/s) | 3.6 (984/s) | 4.0 (958/s) | 4.0 (918/s) | 7.9 (696/s) | 5.0 (983/s) | 5.8 (836/s) |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 7.7 (657/s) | 8.7 (621/s) | 7.9 (618/s) | 7.8 (623/s) | 9.6 (556/s) | 10.6 (588/s) | 8.0 (547/s) | 9.6 (594/s) |
| `TRD book=BOOK-RATES-3` | 7.2 (717/s) | 6.9 (726/s) | 8.0 (695/s) | 7.7 (683/s) | 9.4 (637/s) | 8.4 (676/s) | 8.1 (590/s) | 6.6 (688/s) |
| pick list `TRD END-1100` | 3.1 (1,962/s) | 1.8 (2,283/s) | 3.4 (1,840/s) | 3.2 (1,947/s) | 2.9 (1,776/s) | 2.9 (1,855/s) | 6.2 (1,168/s) | 2.0 (2,049/s) |
| desk P&L `desk-pnl/DESK-RATES` | 4.9 (797/s) | 4.6 (750/s) | 3.4 (779/s) | 3.5 (787/s) | 3.3 (719/s) | 4.9 (751/s) | 5.5 (797/s) | 4.0 (729/s) |
| impact `netting-set/NS-SUMMIT-NY` | 17.3 (198/s) | 20.7 (140/s) | 23.8 (88/s) | 17.1 (187/s) | 22.8 (115/s) | 30.9 (71/s) | 174 (6/s) | 24.3 (155/s) |
| search on another business day, first (cold) | 156 | 148 | 115 | 353 | 184 | 46.2 | 426 | 264 |
| search on another business day, again | 10.4 | 11.3 | 4.1 | 4.8 | 10.3 | 17.6 | 6.5 | 5.4 |
| load time (loader script, end to end) | 10.2 s | 22.3 s | 22.3 s | 15.4 s | 17.1 s | 20.2 s | 18.8 s | 26.4 s |
| store size | 270 MB | 739 MB | 122 MB | 1,322 MB | 202 MB | 183 MB | 1,176 MB | 133 MB |
| server start to healthy | 6.2 s | 7.9 s | 6.2 s | 7.3 s | 6.3 s | 8.4 s | 6.8 s | 8.8 s |
| after start: newest day searchable from columns | 0.0 s | 0.1 s | 0.0 s | 0.0 s | 0.0 s | 0.1 s | 0.1 s | 0.1 s |
| server live heap after a full GC | 129 MB | 116 MB | 114 MB | 122 MB | 174 MB | 132 MB | 117 MB | 127 MB |
| server resident memory (at the end) | 1,314 MB | 1,150 MB | 2,404 MB | 1,240 MB | 1,406 MB | 1,208 MB | 1,289 MB | 1,170 MB |

### 5.2 Across stores, extrapolated to a million trades a day

Extrapolated to 1,000,000 trades a day from each store's linear fit (**extrapolations, not measurements**):

| Measure | Delta Lake (native) | PostgreSQL | DuckDB | JSON-lines files | MongoDB | Redis | Aerospike | Apache Iceberg |
|---|---|---|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` | 7.4 ms* | 50.2 ms | 18.3 ms† | 8.2 ms* | 6.9 ms* | 56.5 ms† | 12.5 ms* | 8.1 ms* |
| open a trade (view), first time | 11.3 ms† | 39.4 ms | 4.5 ms* | 4.1 ms* | 14.5 ms† | 8.7 ms† | 6.1 ms* | 5.0 ms* |
| open a trade (view), again | 3.7 ms* | 11.4 ms† | 3.1 ms* | 15.9 ms† | 24.6 ms† | 17.6 ms | 5.4 ms* | 4.3 ms* |
| raw document, today | 13.3 ms* | 40.1 ms† | 10.2 ms* | 1.8 ms* | 30.3 ms | 33.0 ms | 3.7 ms* | 83.2 ms |
| raw document, a past business day | 237 ms | 131 ms | 264 ms | 40.4 ms | 111 ms | 137 ms | 7.3 ms† | 183 ms |
| `TRD where mtm < -50m order by mtm` | 101 ms | 113 ms | 64.1 ms | 69.7 ms | 68.7 ms | 155 ms | 44.7 ms† | 73.5 ms |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 142 ms | 172 ms | 155 ms | 154 ms | 177 ms | 225 ms | 105 ms | 168 ms |
| `TRD book=BOOK-RATES-3` | 131 ms | 132 ms | 151 ms | 131 ms | 176 ms | 154 ms | 98.3 ms | 101 ms |
| pick list `TRD END-1100` | 53.5 ms | 17.4 ms | 64.6 ms | 55.4 ms | 45.3 ms | 50.1 ms | 112 ms | 29.4 ms |
| desk P&L `desk-pnl/DESK-RATES` | 49.7 ms | 43.0 ms | 11.1 ms | 4.8 ms* | 4.4 ms* | 15.9 ms† | 5.2 ms* | 4.4 ms* |
| impact `netting-set/NS-SUMMIT-NY` | 78.5 ms | 91.5 ms† | 67.1 ms | 17.9 ms* | 132 ms | 231 ms | 1,248 ms† | 136 ms |
| search on another business day, first (cold) | 3,049 ms | 2,863 ms | 1,965 ms | 2,904 ms† | 4,133 ms | 702 ms | 8,432 ms | 5,451 ms |
| search on another business day, again | 219 ms | 248 ms | 72.4 ms | 87.5 ms | 221 ms | 378 ms | 97.7 ms | 59.2 ms |
| load time (loader script, end to end) | 74.4 s | 236 s | 258 s | 164 s | 155 s | 169 s | 96.7 s | 232 s |
| store size | 5,311 MB | 13,737 MB | 2,158 MB | 25,362 MB | 3,872 MB | 3,446 MB | 22,558 MB | 2,462 MB |
| server start to healthy | 6.7 s* | 19.8 s† | 6.3 s* | 32.1 s | 12.2 s† | 22.9 s† | 8.1 s* | 40.2 s |
| server live heap after a full GC | 840 MB | 736 MB | 728 MB | 893 MB | 1,702 MB | 754 MB | 799 MB | 763 MB |

\* flat: the measured points do not rise with size (the fitted slope is not above zero), so the figure is their mean, not a fit. † a rising fit with R² below 0.5: noisy points, a weak extrapolation.

### 5.3 How good is the straight line? Checked against the real million

Four stores were measured at a million trades a day earlier on 2026-10-01 on the same machine, with larger heaps and
containers ([section 6](#6-earlier-measurements-at-a-million-trades-a-day)). Beside the extrapolations from this run
(milliseconds unless marked; Redis's earlier run held two days):

| Measure | Delta: extrapolated / measured | PostgreSQL | Aerospike | Redis |
|---|---|---|---|---|
| `TRD where mtm < -50m order by mtm` | 101 / 134 | 113 / 102 | 45† / 132 | 155 / 81–144 |
| `TRD where currency = 'USD' and notional > 500m …` | 142 / 322 | 172 / 225 | 105 / 213 | 225 / 201–257 |
| `TRD book=BOOK-RATES-3` | 131 / 123 | 132 / 189 | 98 / 159 | 154 / 130–244 |
| pick list `TRD END-1100` | 54 / 41 | 17 / 106 | 112 / 225 | 50 / 89–116 |
| a search on another day, first | 3.0 s / 6.6 s | 2.9 s / 1.4 s | 8.4 s / 7.1 s | 0.7 s / 0.3 s |
| a search on another day, again | 219 / 306 | 248 / 90 | 98 / 68 | 378 / 112 |
| impact of `NS-SUMMIT-NY` | 79 / 716–881 | 92† / 217 | 1,248† / 1,600 | 231 / 248 |
| store size, three days | 5.3 GB / 5.2 GB | 13.7 GB / 11 GB (the September partition) | 22.6 GB / — | 3.4 GB / 2.06 GB for two days (1.05 GB a day) |
| load time | 74 s / 82–125 s (writing the book) | 236 s / 174 s | 97 s / 148 s | 169 s / 101 s for two days |

What this says:

- **Store sizes extrapolate well** (within about 25% where both exist): bytes grow linearly with the book.
- **Searches over a day's columns extrapolate to the right order of magnitude**: mostly within a factor of two, in both
  directions. Their cost is linear in the rows read, but at 50,000 trades they take a few milliseconds, so the request's
  fixed costs and noise weigh more than at a million.
- **Impact does not extrapolate.** Its cost grows with the netting set's trades and their links (143,842 trades in
  `NS-SUMMIT-NY` at a million), and Delta's measured figure is ten times the line. Treat the impact figures as a floor.
- **Live heap after a full GC (0.7–0.9 GB extrapolated) is not the heap to give the server.** The earlier runs report
  *heap in use* (2–3.5 GB at a million), which includes garbage between collections and the columns of every day
  opened; size the heap from the connector documents (8 GB or more per million trades a day), not from this line.

## 6. Earlier measurements at a million trades a day

These were measured on 2026-10-01, before this benchmark, by hand on the same workstation with larger limits. They are
real measurements at a million trades a day and are kept, with their date, in each connector's document:

| Store | Book | Server heap, container | Where |
|---|---|---|---|
| Delta Lake | 1,000,000 trades a day x 3 days | heap limit 15.6 GB | [DELTA_CONNECTOR.md › Measured results](../connectors/DELTA_CONNECTOR.md#11-measured-results) |
| PostgreSQL | 1,000,000 x 3 | `-Xmx8g`; PostgreSQL 18 in 12 GB, `shared_buffers` 3 GB | [POSTGRES_CONNECTOR.md › Measured results](../connectors/POSTGRES_CONNECTOR.md#9-measured-results) |
| Aerospike | 1,000,000 x 3 | one CE 8.1 node, file storage | [AEROSPIKE_CONNECTOR.md › Measured results](../connectors/AEROSPIKE_CONNECTOR.md#9-measured-results) |
| Redis | 1,000,000 x 2 | `-Xmx8g`; Redis 8.2, `maxmemory 11gb` | [REDIS_CONNECTOR.md › Measured results](../connectors/REDIS_CONNECTOR.md#9-measured-results) |

DuckDB, the JSON-lines files, MongoDB and Iceberg have not been measured at a million trades a day; their million
figures here and in their connector documents are extrapolations or estimates and say so.

## 7. Running it at a million trades a day

On a machine sized for it (not a shared laptop), the same harness measures a million trades a day. Run one store at a
time, and give `DRISHTI_BENCH_DIR` its own disk (not a memory-backed `/tmp`).

```bash
export DRISHTI_BENCH_DIR=/data/drishti-bench DRISHTI_BENCH_MIN_FREE_GB=24
OUT=/data/drishti-bench/results-1m.jsonl
tools/bench/scale.sh delta     1000000 --heap 16g --out $OUT
tools/bench/scale.sh postgres  1000000 --heap 16g --container-memory 12g --store-args "-c shared_buffers=3GB" --out $OUT
tools/bench/scale.sh duckdb    1000000 --heap 16g --out $OUT
tools/bench/scale.sh files     1000000 --heap 16g --out $OUT
tools/bench/scale.sh mongodb   1000000 --heap 16g --container-memory 12g --store-args "--wiredTigerCacheSizeGB 6" --out $OUT
tools/bench/scale.sh redis     1000000 --days 2 --heap 16g --container-memory 12g --out $OUT
tools/bench/scale.sh aerospike 1000000 --heap 16g --container-memory 8g --out $OUT
tools/bench/scale.sh iceberg   1000000 --heap 16g --out $OUT
python3 tools/bench/report.py tools/bench/results/2026-10-01.jsonl $OUT > report-1m.md
```

Adding the million-trade lines to the small ones gives each fit a point at the far end, and turns the table in
[section 5.3](#53-how-good-is-the-straight-line-checked-against-the-real-million) into a measurement for every store.

Sizing for a million trades a day over three days (the earlier measurements where there are any, else this run's
extrapolations, marked):

| Store | Server heap | Container memory | Data on disk (Redis: in memory) | Load |
|---|---|---|---|---|
| Delta Lake | 16 GB | — | 5.2 GB (measured) | 1.5–2 min (measured) |
| PostgreSQL | 16 GB | 12 GB, `shared_buffers` 3 GB | 11 GB (measured) | 3 min (measured) |
| DuckDB | 16 GB, plus the embedded database's `memory-limit` (native memory) | — | about 2.2 GB (extrapolated) | about 4–5 min (extrapolated) |
| JSON-lines files | 16 GB | — | about 25 GB (extrapolated) | about 3 min (extrapolated) |
| MongoDB | 16 GB | 12 GB, WiredTiger cache 6 GB | about 3.9 GB compressed (extrapolated) | about 3 min (extrapolated) |
| Redis | 16 GB | 12 GB for two days | 2.06 GB for two days (measured) | under 2 min for two days (measured) |
| Aerospike | 16 GB | 8 GB (index in memory, data on the file) | about 22 GB (extrapolated); `scale.sh` sizes the namespace file at 12 KB a trade-day | 2.5 min (measured) |
| Iceberg | 16 GB (the loader takes 6 GB more) | — | about 2.5 GB (extrapolated), plus the loader's spill files, about the size of the JSON | about 4 min (extrapolated) |

Leave room for the generator: `bulk_trades.py` uses every core but one and writes 12,000–20,000 trades a second. The
whole run, eight stores, is about half an hour of loading plus a few minutes of measurement per store. The first search
on another business day is the measure most sensitive to the disk: on a memory-backed scratch folder (as in this run)
it is a warm-cache figure; on a cold disk it can be several times slower.

## 8. Reading the numbers, and their limits

- **At these sizes most questions take a few milliseconds**, and the HTTP round trip, JSON and the view pipeline are a
  large share of that. The differences between stores at 50,000 trades are mostly smaller than the run-to-run
  variance (7–48% between two runs of the same size, median over the timed measures). Compare stores at a million, not
  here.
- **What grows with the book** is what reads every trade of a day: the three searches, the pick list, the first search
  on another day (its columns read from the store), the raw document of a past day (its id map), and the store size.
  Type-ahead, opening a trade and the desk P&L stay flat: they read one document, an in-memory index or a cached result.
- **Aerospike's impact** (144–601 ms) is about ten times the other stores' at the same size, and was 1.6 s at a million
  in the earlier run: the one measure where the store, not the engine, dominates here.
- **Requests per second** come from 8 Python clients on the same machine as the server and the stores; they show how a
  measure changes with the book, not the server's capacity.
- **The extrapolations are straight lines** through four points between 10,000 and 50,000. Read them as orders of
  magnitude; [section 5.3](#53-how-good-is-the-straight-line-checked-against-the-real-million) shows how far they were
  from real measurements where both exist.
- **Resident memory** follows the heap limit and, for DuckDB, the embedded database's own memory (about 1 GB more), not
  the book: it is listed, not fitted.

See also [PERFORMANCE.md](PERFORMANCE.md) for the server's own targets and how to measure one installation, and
[DEMO_DATA.md](../connectors/DEMO_DATA.md) for loading the same book by hand.
