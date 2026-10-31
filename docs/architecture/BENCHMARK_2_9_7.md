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
# The 2.9.7 benchmark with profiling: the baseline

Status: measured (baseline, before the 2.9.7 diet)

This is item **B1** of [IMPLEMENTATION_PLAN.md › 2.9.7 Drill B](IMPLEMENTATION_PLAN.md#297-drill-a-built-and-drill-b-next), run by the
method of [RESOURCES_AND_SCALE.md section 10](RESOURCES_AND_SCALE.md#10-benchmark-profiling-and-soak-method). It measures `develop` at
fa89c7e93 (2026-10-31) **as it is**: no product code was changed. Its figures are the "before" column of section 10.3; B12 (the
after-benchmark) runs the same commands after the diet (B2). Every measure is one JSON line in
[`tools/bench/results/2.9.7-baseline.jsonl`](../../tools/bench/results/2.9.7-baseline.jsonl).

**The headline.** At 200,000 positions one server answers a VaR node in 8 ms and the store sums a 50,000-position basket in 3 ms. Under
the concurrency test, though, it is **CPU-bound at about 30 requests a second**, whatever the number of users. The cause is one code path
that the diet does not name. `VarViews.positionSize`, called through `partsOf` and `largest` (the parts of a scope, ranked by size), builds
a bitset as wide as the whole date for every position of the scope. A request therefore costs positions in the scope × trades of the date
÷ 64. That path takes **about 60% of the server's CPU and 93% of its allocation** (`BitSet.clone`, `BitSet.initWords`). It drives G1
into 2,900 collections and 68 s of pauses in a 6.5-minute run, and ZGC into 134,566 allocation stalls. It starves the trade reads into
`DRS-1004` time-outs. The profile puts this ahead of D1 (section 8).

## Contents

1. [Method](#1-method)
2. [Machine and data](#2-machine-and-data)
3. [Single server against the three-node cluster](#3-single-server-against-the-three-node-cluster)
4. [Users: 50, 100 and 200](#4-users-50-100-and-200)
5. [Java 21 against 25, G1 against ZGC](#5-java-21-against-25-g1-against-zgc)
6. [The loaders: Python path against the Java loader](#6-the-loaders-python-path-against-the-java-loader)
7. [The "before" figures per diet item](#7-the-before-figures-per-diet-item)
8. [What the profile says: the top heap and allocation sites, and the diet order](#8-what-the-profile-says-the-top-heap-and-allocation-sites-and-the-diet-order)
9. [Extrapolated to 2.3M positions](#9-extrapolated-to-23m-positions)
10. [Findings outside the diet, and harness changes](#10-findings-outside-the-diet-and-harness-changes)
11. [How to run it again](#11-how-to-run-it-again)

## 1. Method

The tools are all in `tools/bench/`, documented in [TOOLS.md](../guides/TOOLS.md):

| Step | Tool | What |
|---|---|---|
| data | `vargen.py` | 200,000 positions (one OTC trade each), 2 business dates (2026-09-29, 2026-09-30), 509 scenarios, 4 legal entities, 5 divisions, 100 desks, 1,000 books; **1,500 portfolios**: 10 baskets of 50,000 positions, 90 small baskets, 300 rule portfolios, 100 desk and 1,000 book portfolios; limits on the bank, divisions and desks |
| trades | `trades.py` | 20,000 trades a day per system (ENDUR, CALYPSO, MUREX), the same 2 dates, 2,000 counterparties (the trades mix) |
| loaders | `var_bench.py load` | each kind loaded with the Python path (`--engine drishti`) and with the Java loader (`--engine java`), timed, with the peak resident memory of the loader's whole process tree |
| servers | `var_bench.py run` | one server (`-Xmx8g`) or three servers on Drishti\*net with VaR sharding (`-Xmx4g` each, `replicas: 2`, `target-shard-mb: 64`); packs `trading,counterparty-risk,market-risk`; security on; the demo off; `drishti.var.currencies=USD`, official captures off; the stack's memory flags (`MaxDirectMemorySize=256m`, `TrimNativeHeapInterval`, NMT summary); ports 19480 to 19482 |
| profile | JVM flags | `-XX:StartFlightRecording=settings=profile` for the whole run, plus **its own recording for each date's first open** (`JFR.start` / `JFR.stop`); `-Xlog:gc*` to a file; heap histograms (`GC.class_histogram`, which runs a full GC first) and `GC.heap_info` and `VM.native_memory summary` at start (no date open), with one date open, with two, after the warm-up and at the end |
| load | `users.py --var-share 0.5` | 50% the VaR mix of section 10.1 (60% a node, 20% the bank minus a desk, 10% a stored 50,000-position basket, 10% a desk's contributions by position or a node's P&L path) and 50% the trades mix (80% single trades, 10% counterparty pages, 5% netting sets, 5% searches); 45 s warm-up at 50 users, then **50, 100 and 200 users for 60 s each**; in the cluster each user asks one member, round robin |
| logs | `var_bench.py` | the server's own log file (`drishti.logging.file`): bytes over 120 s at rest after both dates opened, and over the load |
| reading | `jfr_summary.py` | the JDK's `jfr view`s per recording (in the run folder's `views/`); the first open split by stage from its CPU samples |

Each configuration ran alone. **The machine was otherwise idle.** The product owner's 3-node stack was stopped for the benchmark, before
any measured run, and is not restarted by it. Only small smoke runs (2,000 positions, not reported) ran while it was up. During every
measured users window the swap stayed still: no page swapped out, and at most 348 pages (1.4 MB) swapped in. No run is affected.
`MemAvailable` never fell below 33 GB.

Extra runs beside the matrix: Java 25 with compact headers off (`-XX:-UseCompactObjectHeaders`, D5; 50 users, 30 s); a trades-only
reference (`--var-share 0`); and one user alone for 120 s, which gives the per-request cost without contention. `VarTierBenchmark` (the
store alone, no server) ran at 200,000 positions × 3 dates on 21 and on 25.

## 2. Machine and data

| | |
|---|---|
| CPU | AMD Ryzen AI 9 HX 370, 12 cores, 24 hardware threads |
| Memory | 61 GB; swap 8 GB (5.7 GB held by earlier processes; still during every measured run) |
| Disk | NVMe |
| OS | Linux 7.2.6 |
| JDKs | OpenJDK 21.0.12.1 and 25.0.4.1; on 25 the launcher (`tools/javacmd.py`) adds `-XX:+UseCompactObjectHeaders` |
| Data, per date | 200,000 P&L vectors × 509 scenarios: 407 MB of float64; **545 MB** in the lake (Java loader) for both dates; hot file **557 MB** a date (float32 rows, aggregates of **3,424 nodes**: MGMT, LEGAL and the limit-letter hierarchy, the per-currency totals, columns, ids) |
| Trades | 60,000 a day (3 systems), 199 MB lake for 2 days |

## 3. Single server against the three-node cluster

All three members run on the same 24 hardware threads, so the cluster adds no CPU. It shows the cost and the memory of sharding, not
its capacity.

| Java 21, G1 | Single (`-Xmx8g`) | Cluster: a (coordinator, seed) / b / c (`-Xmx4g` each) |
|---|---|---|
| start to healthy | 12.2 s | 20.3 s (a), all up in 47 s |
| shard map | | 7 shards of 32,000 rows (S6 8,000), `[LEGAL_ENTITY, DESK]`; owners b in 6 shards, a in 3, c in 5 (rendezvous hashing, unbalanced at 7 shards) |
| first open, 2026-09-30 / 09-29 | **7.1 s / 6.8 s** (server load 7.0 / 6.8 s) | **16.4 s / 23.2 s** (coordinator's load 12.8 / 19.4 s) |
| the bank's VaR on both | 12,658,051 / 12,869,199 | the same (bit-equal) |
| heap after full GC: start | 323 MB | 332 / 332 / 331 MB |
| one date open | 613 MB | 656 / 510 / 332 MB |
| **two dates open** | **866 MB** | **1,034 / 831 / 517 MB** |
| warm (after 45 s at 50 users) | 933 MB | 1,318 / 1,550 / 1,268 MB |
| end (after 200 users) | 992 MB | 1,675 / 1,892 / 1,783 MB |
| RSS, two dates / end | 3.5 / 5.9 GB | 3.8, 3.4, 2.3 / 5.1, 5.6, 5.4 GB |
| NMT committed, two dates / end | 3.4 / 5.1 GB | 3.6, 3.2, 2.2 / 4.6, 4.7, 4.6 GB |

The cluster puts each date together on the coordinator (84 to 90% of its first-open CPU samples read the lake: the date's columns and
skeleton) while the owners build shard books. Every member's heap after GC keeps growing under load, from 0.5 to 1.0 GB to 1.7 to 1.9 GB.
That growth is caches: views, answers and partials, the D3 and D4 territory. On the single server it rose by only 126 MB.

## 4. Users: 50, 100 and 200

Requests a second (both mixes together), errors, and p50 / p95 / p99 in seconds. Java 21, G1.

| Users | Single: req/s, errors | trade | search | VaR node | bank − desk | basket 50K | contributions | P&L path |
|---|---|---|---|---|---|---|---|---|
| 1 (alone) | 9.8, 0 | 0.008 / 0.014 / 0.021 | 0.17 / 0.21 / 0.24 | 0.008 / 0.017 / 0.020 | **0.63 / 0.90 / 0.98** | **0.45 / 0.54 / 0.63** | 0.12 / 0.15 / 0.16 | 0.004 / 0.007 / 0.009 |
| 50 | **31.0**, 0 | 0.96 / 3.50 / 4.21 | 3.83 / 6.21 / 6.70 | 0.24 / 1.22 / 1.77 | 5.45 / 6.35 / 7.00 | 3.61 / 4.31 / 4.58 | 0.91 / 1.50 / 1.77 | 0.17 / 0.99 / 1.11 |
| 100 | 29.8, 3 | 2.86 / 9.42 / 13.52 | 12.0 / 31.2 / 33.6 | 0.62 / 3.68 / 6.01 | 5.85 / 7.68 / 8.96 | 3.87 / 6.35 / 10.0 | 1.04 / 4.30 / 5.52 | 0.51 / 2.94 / 5.13 |
| 200 | 30.4, **53** | 7.02 / 14.2 / 19.6 | 20.6 / 49.7 / 57.7 | 1.71 / 7.34 / 17.8 | 6.64 / 12.9 / 19.5 | 4.90 / 8.66 / 15.0 | 2.21 / 14.6 / 16.4 | 1.90 / 5.66 / 9.08 |

| Users | Cluster: req/s, errors | trade | search | VaR node | bank − desk | basket 50K | contributions | P&L path |
|---|---|---|---|---|---|---|---|---|
| 50 | 28.6, 0 | 0.12 / 0.27 / 0.36 | 1.88 / 2.50 / 2.53 | 0.11 / 5.04 / 6.10 | 7.90 / 10.5 / 11.0 | 6.32 / 16.3 / 21.5 | 1.23 / 42.2 / 49.2 | 0.07 / 4.54 / 5.29 |
| 100 | 29.9, 18 | 0.27 / 1.95 / 5.02 | 5.51 / 15.0 / 15.1 | 0.20 / 16.2 / **60.1** | 10.8 / 15.3 / 16.5 | 7.31 / 10.7 / 13.2 | 1.51 / 37.1 / 44.3 | 0.12 / 11.8 / **60.1** |
| 200 | 26.1, 43 | 1.92 / 13.5 / 17.7 | 21.3 / 52.3 / 55.6 | 0.65 / 46.9 / **60.1** | 17.7 / 23.0 / 24.3 | 11.6 / 16.5 / 22.5 | 2.82 / 53.5 / 60.1 | 0.34 / 4.22 / 34.8 |

| Trades only (reference, `--var-share 0`) | 50 users | 100 | 200 |
|---|---|---|---|
| req/s, errors | **270**, 0 | 235, 0 | 206, 0 |
| trade p50 / p95 / p99 | 0.11 / 0.51 / 0.78 | 0.28 / 1.20 / 1.89 | 0.62 / 2.94 / 4.81 |

**Reading it.** The throughput does not rise with users: the server is saturated at 50 users already. The heavy VaR questions are
bank minus a desk and the 50,000 baskets, 0.45 to 0.63 s alone. Each costs about 2 to 3 CPU-seconds when 24 threads share them, garbage
collection included. Every **error is `DRS-1004`, a read time-out** of a trade, a netting set or a counterparty page starved of CPU;
no VaR question failed on the single server. Without the VaR share the same server serves 270 requests a second at a trade p95 of 0.5 s.
In the cluster the trade reads are faster (spread over three JVMs), but some VaR questions wait for an owner and hit the 60 s client time-out
(node and path p99 = 60 s at 100 and 200 users). Contributions there have a p95 of 37 to 54 s.

## 5. Java 21 against 25, G1 against ZGC

Single server, `-Xmx8g`.

| | 21 G1 | 25 G1 (compact headers) | 21 generational ZGC | 25 ZGC |
|---|---|---|---|---|
| first open 09-30 / 09-29 | 7.1 / 6.8 s | 6.9 / 6.6 s | 7.6 / 7.0 s | 7.3 / 6.7 s |
| heap after GC: start / two dates / end | 323 / **866** / 992 MB | 297 / **820** / 944 MB | 427 / 997 / 1,144 MB | 392 / 925 / 1,034 MB |
| RSS: two dates / end | 3.5 / 5.9 GB | 3.3 / 7.8 GB | 8.5 / 9.6 GB | 7.0 / 9.7 GB |
| req/s at 50 / 100 / 200 users | 31.0 / 29.8 / 30.4 | **33.3 / 34.5 / 34.3** | 31.0 / 26.9 / 27.3 | 25.9 / 28.7 / 30.2 |
| errors at 200 users | 53 | 61 | **205** | 77 |
| VaR node p99 at 200 users | 17.8 s | **12.2 s** | 20.9 s | 20.7 s |
| basket p99 at 200 users | 15.0 s | 15.4 s | 22.1 s | 22.0 s |
| trade p99 at 200 users | 19.6 s | 17.9 s | 21.1 s | 16.6 s |
| collections; longest pause | 2,938; 214 ms | 2,930; 107 ms | 2,710; 1.4 ms | 2,364; 0.38 ms |
| total pause in the run (about 6.5 min) | **68 s** (GC CPU 18.6 min: about 2.9 threads) | 65 s (17.7 min) | 0.27 s | 0.19 s |
| allocation stalls | | | **134,566** | 98,764 |
| pinned virtual-thread events (≥ 20 ms) | **0** | 52 | 0 | 42 |
| log bytes an hour: at rest / under load | 0 / 1.35 MB | 0 / 1.83 MB | 0 / 5.18 MB | 0 / 2.21 MB |

Java 25 is 7 to 16% faster than 21 on the same G1 and uses 5% less heap with two dates open. With this allocation rate ZGC's sub-millisecond
pauses do not help. Allocating threads stall instead, and ZGC's p99 is no better than G1's: worse on 21, with four times the errors. On
the cluster, 25 against 21 (G1): 33.4 against 28.6 requests a second at 50 users, and the same pattern of time-outs.

## 6. The loaders: Python path against the Java loader

The same files: 20 gzip files a date, 400,000 P&L rows for the two dates. Java 21. Peak resident memory of the loader's whole process
tree.

| Kind | Python path (`--engine drishti`) | Java loader (`--engine java`) |
|---|---|---|
| `pnl-vector`, 400,000 rows × 509 | **failed after 48.7 s at the default writer heap** (`lake: out of memory: lower --batch-rows or raise DRISHTI_LAKE_HEAP`; 1 GB); with `DRISHTI_LAKE_HEAP=4g`: **68.4 s**, peak 2.7 GB | **7.65 s**, peak 3.9 GB |
| table size | 831 MB | 545 MB |
| `portfolio` (3,000 rows, 10 × 50,000 members a date) | 2.6 s, 0.76 GB | 2.0 s, 1.2 GB |
| `risk-hierarchy`, `scenario-set`, `var-limit` | 1.3 to 1.7 s each | 1.6 to 1.8 s each (JVM start) |
| all five kinds | | **14.9 s** |

The Java loader is 9 times faster on the vectors and writes a 34% smaller table. The Python path needs its writer heap raised above the
default to load 200,000 positions at all.

## 7. The "before" figures per diet item

Matching [RESOURCES_AND_SCALE.md section 10.3](RESOURCES_AND_SCALE.md#103-before-and-after-per-item). Unless named, single server, Java 21, G1.

| Item | Measured before (this baseline) | Acceptance (10.3) | Today |
|---|---|---|---|
| **D1** | heap after GC with two dates open **866 MB** (start 323 MB: **+543 MB** for two dates); aggregates 66.9 MB a date (3,424 nodes); the rest **about 205 MB a date, 1,020 B a position** (ids, columns, links, portfolio members, the connector's columns); the store alone (`VarTierBenchmark`): **214 MB for two dates, 492 B a position** besides 8.6 MB of aggregates; skeleton bytes = the difference of the histograms (`histo/single-start.txt` against `single-two_dates.txt`); **basket p95: 0.54 s alone, 4.3 s at 50 users** (the store alone: 2.9 ms) | heap per owner at 2.3M ≤ 20 GB `-Xmx`; basket p95 ≤ 150 ms warm | heap within (section 9); **basket fails, for the parts path, not the skeleton** |
| D2 | two dates: `String` 3.96 M instances, 95 MB (+56 MB for the dates), `byte[]` 205 MB (+120 MB), `HashMap$Node` 1.6 MB, `Integer` 58 KB | 20 to 40% of the non-vector heap | the maps and boxes are already gone (2.9.7's sorted indexes); what is left is the strings, D1's |
| D3 | heap after GC with the caches as the load leaves them: 992 MB single; **1.7 to 1.9 GB per member** in the cluster (two dates 0.5 to 1.0 GB); `MemoryBudget` squeezed once ("heap after a collection passed 85% of -Xmx") under 200 users | total ≤ `cache-share` × `-Xmx`; no OOM | no OOM; the cluster's growth to watch |
| D4 | not measured here (60 dates × hourly versions is the soak's, O8) | | |
| D5 | Java 25, two dates open: **820 MB with compact headers, 865 MB without (−5.2%)**; at start 297 against 322 MB (−7.7%) | ≥ 10% less | not met at 200K (the heap is mostly arrays) |
| D6, D7 | not measured (the API, not the console's pages) | | |
| **D8** | first open of a 200K date **7.1 s / 6.8 s** (09-30 / 09-29; server load 7.0 / 6.8 s); by stage (share of the load's CPU samples): **read 55 to 69%**, sort 22 to 25%, write 3 to 11%, intake 3 to 4%, aggregate 2%; **one thread** (`drishti-var-load-1`) does it all (about 1.2 of 24 hardware threads busy); the read is Parquet record assembly and `BYTE_STREAM_SPLIT` decoding of the vectors; cluster coordinator 16.4 / 23.2 s (read 84 to 90%) | the read at disk speed; total ≥ 30% faster | the read is single-threaded decode, far below disk speed |
| D9 | log bytes over 120 s at rest after both dates opened: **0** (every run); under load **0.03 to 5.2 MB an hour** (trades only 28 KB/h; the `DRS-1004` warnings under the VaR load) | ≤ 10 MB/h at rest, < 1% CPU | met |
| **D10** | pinned events ≥ 20 ms: **21 single: 0**; **21 cluster: 32 on the coordinator** (Tomcat's HTTP/2 between members, `Http2AsyncUpgradeHandler.writeBody` under its monitor, 30 to 96 ms each), 2 on b; 25: 42 to 68 (native frames at start: `NativeFileIO`, Parquet footers, the Delta log); req/s 21 against 25: **31.0 against 33.3** at 50 users (21 is 7% slower) | no pinned event on a request path; 21 within 10% of 25 | the 21 cluster pins on a request path; the speed is within 10% |
| **GC** | p99 at 200 users, node / basket: **G1 17.8 / 15.0 s, ZGC 20.9 / 22.1 s** (Java 21); 25: G1 12.2 / 15.4 s, ZGC 20.7 / 22.0 s; GC CPU (G1) 18.6 min in 6.5 min; ZGC allocation stalls 134,566 (21) | the chosen collector's p99 ≤ the other's | **G1** today; to be decided again once the parts path stops the allocation storm |

## 8. What the profile says: the top heap and allocation sites, and the diet order

**CPU** (Java 21, single, the whole run: 113,902 samples; the first Drishti frame of each sample):

| Share | Where | Called from |
|---|---|---|
| 30.5% | `OrdinalSet.range` | `VarBook.positionMembers` ← `VarViews.positionSize` (25.4%), `ScopeResolver.ref` (3.1%) |
| 25.1% | `OrdinalSet.containsAll` | `VarViews.positionSize` ← `partsOf` |
| 13.2% | `VarMath.order` (sorting a vector for its rank) | `VarCalc.measures` ← `VarViews.standalone` (each part's standalone VaR) |
| 9.0% | `Columns.number` | `VarViews.positionSize` (the market value of each position) |
| 7.5% | `VarViews.largest` and its comparator | `partsOf` (ranking parts by gross size) |
| 1.0% | `PluginManifest.<init>` | building a connector's manifest on each call |

**Allocation** (`jdk.ObjectAllocationSample`): `BitSet.clone` **48.2%**, `BitSet.initWords` **45.2%**. Both come from `OrdinalSet.range`
and `members.and(set)` in `positionSize`: one bitset of the date's width for each position of the scope. Then `VarMath.order` 0.7% and
`VarBook.positionVector` 0.5%. The full views are kept beside the recordings (section 11).

**Heap, two dates open** (the histogram difference from the start, +543 MB): `double[]` +188 MB (aggregates and per-currency totals of
6,848 node-dates), `byte[]` +120 MB and `String` +56 MB (2.32 M strings: trade, position and vector ids, the 10 × 50,000 basket members
of each date, the connector's column texts), `long[]` +87 MB (bitsets and sorted indexes), `int[]` +73 MB (column codes, runs), `String[]`
+8 MB. **What survives** (`jdk.OldObjectSample`): `VarHotFile$In.strings()` and `columns()` (the skeleton read onto the heap at open),
`CurrencyTotals.read`, `VarDate$Portfolio.of` (portfolio members), `DeltaTable.columns` (the trades connectors' column sets),
`IdIndex.kept`, `OrdinalSet$Builder.build` (views).

**The order the profile supports:**

1. **P0, new: the parts path** (`VarViews.positionSize`, `largest`, `standalone` in `partsOf`). It is the only cause of the CPU limit, of
   93% of allocation, of the GC load, of ZGC's stalls and of the trades' time-outs. It also decides D1's basket target: 0.54 s alone
   against 2.9 ms for the store's sum. A position's members are one run of ordinals, so a size test needs no bitset of the date's width.
   It must be fixed before anything else is measured again, or every other gain is hidden under it. **Product code: not changed here.**
2. **D8**, cold loads: one thread, read-bound, 7 s at 200K. It is the one cost that grows linearly to minutes at 2.3M (section 9), and it
   is the cluster's slowest step.
3. **D1**, the skeleton: about 1,020 B a position at the server, the largest heap item. At 200K it is far from the limit (866 MB in
   8 GB), but at 2.3M and beyond it sets `-Xmx`, as the design says. It stays on the plan, after P0; its basket target depends on P0.
4. **GC per role**: G1 in the baseline. Decide again after P0: without the storm, ZGC's pauses may matter for p99.
5. **D3 and D4**: the cluster members' heap after GC doubled under load; to be watched in the soak.
6. **D10**: Tomcat's HTTP/2 between members pins on 21. A third-party monitor: the members' calls could use HTTP/1.1, or run on
   platform threads, on 21. Low urgency: 25 is the target.
7. **D5**: 5 to 8% at 200K, below the 10% target. **D2**: the maps are gone. **D9**: met. All three are low priority.

## 9. Extrapolated to 2.3M positions

**EXTRAPOLATED, not measured.** These figures come from the 200K measurements by the formulas below. The workstation does not run 2.3M
(one heavy job at a time; a million-trade run once took it down).

Let K = P / 200,000 = 11.5 at P = 2.3M; N the nodes (3,424 here; 5,000 assumed at 2.3M, the reference bank of VAR_GUIDE 27.4).

| Quantity | Formula | 200K (measured) | 2.3M (extrapolated) |
|---|---|---|---|
| heap after GC, two dates open, single server | H = H₀ + 2 × (b × P + a × N), H₀ = 323 MB, b = 1,023 B/position, a = 19.5 KB/node (66.9 MB ÷ 3,424) | 866 MB | 0.32 + 2 × (2.35 + 0.10) = **5.2 GB** |
| the same, the store alone (`VarTierBenchmark`) | 2 × (492 B × P + aggregates) | 214 MB | 2 × (1.13 + 0.10) = **2.5 GB** |
| skeleton a date (server; design's estimate 1.84 GB) | b × P | 205 MB | **2.35 GB** (+28% on the design) |
| `-Xmx` need (heap ÷ 0.61, RESOURCES_AND_SCALE 9.5) | (H + working set 0.13 GB) ÷ 0.61 | | **about 8.7 GB** (509 values, USD only; the design's 36 GB adds 1,518 values, four currency books, the 2 GB load sort and 4 GB) |
| hot file a date | 2,783 B × P | 557 MB | **6.4 GB** |
| first open of a date (single-threaded) | 7.0 s × K (the sort a little more) | 7.0 s | **80 to 90 s** |
| a VaR node | constant | 8 ms | 8 ms |
| a 50,000-position basket alone (today's parts path) | 0.45 s × K (basket fixed; each part costs P ÷ 64) | 0.45 s | **5.2 s** |
| the bank minus a desk alone (today's parts path) | 0.63 s × K² (S ≈ P parts, each costing P ÷ 64) | 0.63 s | **about 83 s** |
| the same two after P0 (store's sum only) | the store's measure | 2.9 ms; 1.6 ms | about 3 ms and a few ms (VAR_GUIDE 27.3) |

So at 2.3M the heap is not the first wall. The parts path is: its K² term makes the bank minus a desk a minute and a half of CPU. The
single-threaded first open is next. D1 brings the 2.35 GB a date down, as designed, and becomes the wall at 10M (K = 50: about 10 GB a
date).

## 10. Findings outside the diet, and harness changes

**Product findings (reported, not changed: the baseline must stay the code as it is).**

- **The parts path** (sections 7 and 8): `VarViews.positionSize` allocates and scans a bitset of the date's width for each position of a
  scope.
- **A sharding bug.** `VarLoader.keyCounts` (`drishti-server/.../var/VarLoader.java`, about line 117) skips a kind with no data only when its
  version is `none`. A connector that holds no table for the kind answers `risk-store=none`. With the default
  `kinds: [scenario-pv, pnl-vector]`, a lake without a `scenario-pv` table therefore never gets cut: every date is one shard ("the trades'
  source cannot read [LEGAL_ENTITY, DESK] as columns"). Seen in single and cluster mode, with either loader. Worked around for the
  benchmark by configuration only: `--drishti.var.sharding.kinds=pnl-vector`. `VarShardClusterTest` holds both kinds, so it does not see the bug.
- **The Python path's writer** runs out of memory at its default 1 GB heap on 200,000 positions (section 6).
- **Cluster time-outs**: VaR node and path questions reach the 60 s client time-out at 100 and 200 users, while the same questions on one
  server stay under 18 s.
- **Shard owners unbalanced**: with 7 shards and 3 members, one member owns 6 of them (rendezvous hashing with few shards).

**Harness changes (committed with this report, tools and test sources only):**

- `tools/bench/vargen.py` (new): the synthetic VaR inputs of section 1.
- `tools/bench/var_bench.py` (new): the loader timing and the single and cluster runs with profiling.
- `tools/bench/jfr_summary.py` (new): JFR views and the first-open stage split.
- `tools/bench/users.py`: `--var-share` (the VaR mix of section 10.1), and `--server` with several members.
- `tools/bench/report.py`: lines without a `store` (this benchmark's) are skipped and counted, not a `KeyError`.
- `VarTierBenchmark.histogram()`: the heap histogram is taken in process (the `DiagnosticCommand` MBean). An external `jcmd` attaching to the
  surefire JVM hung on Java 25.

## 11. How to run it again

```bash
B=/home/ashutosh/drishti-bench-scratch                      # on disk, never /tmp (tmpfs) and never the repository's data/
python3 tools/bench/vargen.py --out $B/vargen --positions 200000 --portfolios 1500
python3 tools/bench/var_bench.py load --data $B/vargen --lake $B/lake --engine java --out $B/results.jsonl
DRISHTI_LAKE_HEAP=4g python3 tools/bench/var_bench.py load --data $B/vargen --lake $B/lake-py --engine drishti --out $B/results.jsonl
python3 tools/bench/trades.py --lake $B/lake --tmp $B/tmp --days 2 --end 2026-09-30 --min 20000 --max 20000 --counterparties 2000
for j in 21 25; do
  python3 tools/bench/var_bench.py run --mode single  --java $j --gc g1 --heap 8g --lake $B/lake --work $B/single-$j  --run single-$j-g1  --out $B/results.jsonl
  python3 tools/bench/var_bench.py run --mode cluster --java $j --gc g1 --heap 4g --lake $B/lake --work $B/cluster-$j --run cluster-$j-g1 --out $B/results.jsonl
  python3 tools/bench/var_bench.py run --mode single  --java $j --gc zgc --heap 8g --lake $B/lake --work $B/zgc-$j    --run single-$j-zgc --out $B/results.jsonl
done
python3 tools/bench/jfr_summary.py views $B/single-21/jfr/single-run.jfr --to $B/single-21/views
python3 tools/bench/jfr_summary.py stages $B/single-21/jfr/single-open-2026-09-30.jfr
./mvnw -o test -pl drishti-compute -Dtest=VarTierBenchmark -Ddrishti.var.tier.trades=200000 -Ddrishti.var.tier.dates=3 \
    -Ddrishti.var.tier.histogram=true -Ddrishti.var.tier.dir=$B/tier
```

**The recordings of this baseline** (not in git: about 1.6 GB of JFR) are on the workstation under `/home/ashutosh/drishti-bench-297/`,
one folder per run (`run-single-21-g1`, `run-single-25-g1`, `run-single-21-zgc`, `run-single-25-zgc`, `run-cluster-21-g1`,
`run-cluster-25-g1`, `run-single-25-g1-nocoh`, `run-single-21-g1-trades`, `run-single-21-g1-1user`). Each holds `jfr/` (the run's
recording and one per first open), `histo/` (every histogram), `nmt/`, `logs/` (GC logs and the server logs), `views/` (the `jfr view`s),
`users/` and the VaR status. The lakes and the generated data were deleted after the run.
