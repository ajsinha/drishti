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
# Drishti*net soak 2 of 2.6.6 (2026-10-20)

The second two-hour soak on real processes, with the soak-1 fixes (SOAK-01 to SOAK-10) and QA rounds 1 to 3A in place, and the production
JVM settings of [PERFORMANCE.md](../../admin/PERFORMANCE.md). It has the same shape as [soak 1](SOAK_REPORT.md), so the results compare
one to one. The soak only records evidence. No product code was changed. `tools/bench/net_soak.py` gained two options to pass the
production settings (`--jvm-arg`, `--server-env`; commit `ff15b3893`, with tests). The tool ran to the end the first time. The raw
result is [soak2.json](soak2.json).

**Verdict: FAIL.** Three of the four checks pass: errors only in the kill windows, latency (report only), and memory, now measured on
steady spans and on the console's workers too. The wrong-answers check fails: 77 history answers were wrong, all with one cause
(SOAK2-01, P1). An administrator's history of a trade held by a peer came back with a viewer's masked `PRICE` ("•••") in a column
marked `masked: false`, and with `PRICE` missing from the `changed` flags. The history cache keeps days fetched through a peer
already redacted for the first caller, and serves them to every later caller. This is reproduced every time on the soak's own nodes
(section 8). Soak 1's findings SOAK-01, 02, 03, 06, 07, 08 and 09 are fixed on this evidence. SOAK-04 is fixed in the tool, SOAK-05
is much reduced, and SOAK-10 is only partly fixed (SOAK2-02).

## 1. The run

| | Soak 1 (2.6.5) | **Soak 2 (2.6.6)** |
|---|---|---|
| Command | as in SOAK_REPORT.md | `drishti-console/.venv/bin/python tools/bench/net_soak.py --work ~/drishti-scale-test/soak2-2026-10-20 --users 20 --seconds 7200 --days 10 --kill-shard-at 1200 --restart-shard-at 1500 --kill-seed-at 3600 --restart-seed-at 3900 --history-share 0.15 --sample-every 60 --warmup 600 --max-growth 64 --heap 2g --base-port 18710 --console-port 17710 --settle 600 --history-sample 40 --server-env MALLOC_ARENA_MAX=2 --jvm-arg=-XX:MaxDirectMemorySize=256m --jvm-arg=-XX:TrimNativeHeapInterval=60000` |
| Code | `develop` at `6f9555146` | `develop` at `e8375b881` (soak-1 fixes, rounds 1 to 3A) plus `ff15b3893` (the soak's two options); `drishti-server-2.6.5-exec.jar` built in the worktree (`./mvnw -q -T 4 -DskipTests package -pl drishti-server -am`) |
| JVM settings | `-Xmx2g` | **`-Xmx2g -XX:MaxDirectMemorySize=256m -XX:TrimNativeHeapInterval=60000`, environment `MALLOC_ARENA_MAX=2`**, on every server and the reference. Checked in `/proc/<pid>/cmdline` and `/proc/<pid>/environ` of a, b and c, and recorded in `soak2.json` `serverLaunch`. |
| Runtimes | OpenJDK 21.0.12.1, Python 3.13.15 | the same |
| Machine | Ryzen AI 9 HX 370, 24 CPUs, 60 GB, load average about 11 | the same machine, but a QA tester's server (ports 186xx, `-Xmx4g`, 1,200 % CPU at the start) ran throughout. Load average 6.7 to 13.4 for the first hour and **41 to 51 for the last 40 minutes** (sampled at 18:24, 18:34 and 18:44). 6 GB of 7 GB swap was in use. |
| Ports | a, b, c = 18710 to 18712, ref 18713, console 17710 | the same. None of the user's ports were used. |
| Addresses | `http://192.168.2.77:<port>` | the same, never loopback |
| Data | 1,500 trades a day per system, 300 counterparties, 10 business days 2026-10-02 to 2026-10-16, 177 MB | the same (177 MB) |
| Duration | lakes and reference 407 s, cluster up 38.3 s, console learnt all three in 1.9 s, load 7,200 s | lakes and reference 708 s (2,300 requests, all 200), cluster up 39.7 s, console learnt all three in 1.4 s, load 7,201 s (16:50 to 19:03 local time) |
| Load | 20 users, 1,491,085 requests (207.1 a second) | 20 users, **888,662 requests (123.4 a second)**, see SOAK2-03 |
| Checker | 118,166 checked answers | 99,106 checked answers. There are more histories, which are slower, in the checked set. |
| Console | 2 workers | 4 workers (`server.workers` default of the round-3A console), 10 processes in its tree |

## 2. Verdict

| Check | Soak 1 | **Soak 2** |
|---|---|---|
| No wrong answers | fail: 32, all `partial yet nothing missing` (SOAK-01) | **fail: 77**, all `complete answer differs from the reference`: an administrator's history showing viewer masks (SOAK2-01) |
| Errors only inside kill windows | pass: 0 outside | **pass**: 0 outside c 1200.0 to 1554.8 s and a 3600.0 to 3956.7 s |
| Latency p95 and p99 per action | pass (report only), every p99 at most 0.374 s | **pass** (report only). Every p99 is between 1.12 and 1.34 s on a far busier machine (SOAK2-03). |
| Memory flat after warm-up | fail: b +166.4, c +185.3 MB/h (whole run; SOAK-04) | **pass**, steady slopes: a +59.6, b +5.4, c −20.1, console +3.7 MB/h (limit 64) |
| Masks exercised (new) | not checked: 35 distinct histories, no viewer trade history masked (SOAK-09) | **pass**: 140 viewer histories, 140 masking `PRICE` or `NAME`; 280 history requests, 140 distinct entities |
| **Overall** | **FAIL** | **FAIL** (SOAK2-01) |

## 3. Latency and errors

Per action, through the console's pages:

| Action | Requests 1 | Failed 1 | p50 / p95 / p99 s (1) | **Requests 2** | **Failed 2** | **p50 / p95 / p99 s (2)** |
|---|---:|---:|---|---:|---:|---|
| trade | 1,014,506 | 22,007 | 0.072 / 0.208 / 0.304 | 603,953 | 15,354 | 0.094 / 0.422 / 1.263 |
| ctrd | 127,036 | 0 | 0.075 / 0.216 / 0.316 | 75,488 | 0 | 0.100 / 0.460 / 1.341 |
| netting-set | 63,087 | 0 | 0.091 / 0.214 / 0.303 | 37,854 | 0 | 0.107 / 0.386 / 1.207 |
| search | 63,194 | 0 | 0.075 / 0.255 / 0.374 | 37,855 | 0 | 0.101 / 0.482 / 1.308 |
| history | 223,262 | 3,841 | 0.103 / 0.236 / 0.328 | 133,512 | 2,812 | 0.124 / 0.439 / 1.122 |

The load failures fall in one run of 10-second buckets: 33 buckets, 330 s, from 17:22:50 (c killed at t = 1200.5) to 17:28:20 (c
rejoined at 17:28:18.9). There were none in the seed's window and none anywhere else, as in soak 1. They are MUREX pages and
histories refused `DRS-5052` while c, their only holder, is down. 2.5 % of trade pages failed over the run (soak 1: 2.2 %).

The checker, per kind (right includes `right-links-pending`; unavailable includes `asked-a-node-going-down`):

| Checked | Right 1 | Partial 1 | Timeout 1 | Unavail. 1 | Wrong 1 | **Right 2** | **Partial 2** | **Timeout 2** | **Unavail. 2** | **Wrong 2** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| trade | 101,566 | 1,633 | 0 | 224 | 0 | 75,799 | 1,424 | 0 | 243 | 0 |
| search | 10,979 | 531 | 0 | 20 | 0 | 8,151 | 480 | 0 | 20 | 0 |
| ctrd | 1,049 | 62 | 0 | 1 | 0 | 787 | 37 | 0 | 1 | 0 |
| history | 2,046 | 20 | 0 | 3 | 32 | **11,854** | 205 | 0 | 28 | **77** |
| **All** | 115,640 | 2,246 | 0 | 248 | 32 | **96,591** | 2,146 | 0 | 292 | **77** |

Every partial, unavailable and timed-out answer fell inside a kill window. The checker asked 5.8 times as many histories as in soak 1
(`--history-sample 40`).

The console's own page check (one page a second): soak 1 had 6,496 good, 0 failed and 0 signed out. Soak 2 had **5,903 good, 0 failed
and 0 signed out**.

## 4. The wrong answers

`soak2.json` keeps every one (`wrong.all`, 77) and their bodies and diffs (`wrong.samples`, 77; SOAK-08's fix). All 77 share one rule
and one shape:

| | |
|---|---|
| Rule | `complete answer differs from the reference` (77) |
| Request | `GET /api/v1/history/trade/~?id=<id>&days=30` as the **administrator** (77 of 77) |
| Node and holder | **always a peer-held trade**: b asking MUREX trades of c (26), a asking MUREX trades of c (19), c asking CALYPSO (20) and ENDUR (12) trades of a and b. There are none for data the node holds itself and none for counterparties. |
| Trades | 9: MX-50000362 (20), MX-50000411 (16), CLY-30001163 (10), MX-50000015 (9), 10000327 (7), CLY-30000744 (7), 10000535 (4), CLY-30000407 (3), 10001285 (1). For each of them the checked set's viewer request is the same window: `days=30&diff=true`. |
| When | spread over the whole run, t = 204 s to 7,151 s, 1 to 6 in every 5 minutes, inside and outside kill windows |
| Status, partial | `200`, `partial: false`, `problems: []`, the window equal to the reference (2026-10-02 to 2026-10-16, `data-days`) |

The diff, verbatim from the first sample (`soak2.json` `wrong.samples[0]`, t = 204.1 s, node c, CLY-30001163):

```json
[{"path": "rows[0].values.PRICE", "ref": null, "got": "•••"}, {"path": "rows[1].values.PRICE", "ref": null, "got": "•••"}, …
 {"path": "rows[9].values.PRICE", "ref": null, "got": "•••"}]
```

And the sample for CLY-30000744 (t = 1,698.8 s, node c), where PRICE has values and is amended twice:

```json
[{"path": "rows[0].values.PRICE", "ref": 0.066451, "got": "•••"}, …,
 {"path": "rows[4].changed.length", "ref": 5, "got": 4}, {"path": "rows[4].changed[0]", "ref": "PRICE", "got": "TRADE_ACTION"}, …,
 {"path": "rows[8].changed.length", "ref": 4, "got": 3}, {"path": "rows[8].changed[0]", "ref": "PRICE", "got": "QUANTITY"}, …]
```

The administrator gets the viewer's answer: PRICE masked on every row, and the two days on which PRICE changed do not say so. The
`PRICE` column of these answers is `masked: false` (section 8), so the page shows "•••" as if it were the value.

## 5. The kill windows

| | Shard c, soak 1 | **Shard c, soak 2** | Seed a, soak 1 | **Seed a, soak 2** |
|---|---|---|---|---|
| Killed (SIGKILL by PID) | t = 1200.5 s | t = 1200.5 s (17:22:5x) | t = 3600.3 s | t = 3600.3 s |
| Seen suspect or dead by every live member | 3.6 s | **2.2 s** | 3.1 s | **2.7 s** |
| Console: next good page (a CALYPSO trade) | 0.02 s | **0.06 s** | 0.05 s | **0.05 s** |
| Console: CALYPSO search right after | 200, rows | **200, rows** | 200, rows | **200, rows** |
| Load failures in the window | trade 22,007, history 3,841 (`DRS-5052`) | **trade 15,354, history 2,812** (`DRS-5052`), 17:22:50 to 17:28:20 | none | **none** |
| `DRS-1003` answers (SOAK-02) | 127 on a, 107 on b | **0** | | **0** |
| Healthy servers set aside on `HTTP 502` (SOAK-02) | 43, plus 4 session moves | **0, no session moves** | | |
| Console set-asides of the dead node | | 4, c on its failed health check (`ConnectError`) | | 4, a after 2 failed requests (`unreachable (ReadError)`) |
| Session moves off the dead node | | none needed | 10 (4 to b, 6 to c) | **12** (3 to b, 9 to c: `ConnectError` 5, `ReadError` 1, `unreachable (ReadError)` 6) |
| Restarted: healthy, all alive | 22.1 s, 22.2 s | **24.7 s, 24.8 s** | 21.2 s, 21.2 s | **26.4 s, 26.4 s** (from `peers.json`, no seed) |
| Joined the net vs its port open (SOAK-03) | joined **13.9 s before** Tomcat | **joined 0.19 s after** `Tomcat started` (17:28:18.569 → 18.758) | 12.4 s before | **0.19 s after** (18:08:20.981 → 21.171) |
| Window closes (rejoined + 30 s grace) | 1552.2 s | **1554.8 s** | 3952.0 s | **3956.7 s** |

Restarts took 2.6 to 5.2 s longer than in soak 1. That fits the busier machine, and the node now waits for its web server before it
joins.

## 6. Memory, threads and open files

### Steady slopes (the verdict)

The decisive slope is the steady one (DRISHTI_NET.md 17.1). It uses only the samples outside every kill window and the `--settle` 600 s
after each, with a level of its own per span. Live data after the last collection is used for the servers, and summed resident memory
of the whole process tree for the console:

| Process | Soak 1 verdict slope (whole run) | **Soak 2 steady slope** | Soak 2 whole run | Soak 2 steady spans (s) | Soak 2 live, last hour | Soak 1 live, last hour |
|---|---:|---:|---:|---|---:|---:|
| a (restarted at 3,900) | −21.7 | **+59.6** | +59.6 | 4,563 to 7,148 | +91.7 (last 30 min +18.4) | −9.2 |
| b | +166.4 | **+5.4** | +134.4 | 600 to 1,200; 2,155 to 3,600; 4,557 to 7,148 | +3.4 | +7.0 |
| c (restarted at 1,500) | +185.3 | **−20.1** | +82.7 | 2,161 to 3,600; 4,557 to 7,148 | −15.0 | +4.9 |
| console (whole tree) | 0.0, the parent only (SOAK-07) | **+3.7** | +1.1 | as b | RSS +1.4 | n/a |

The whole-run slopes are still high: b +134 and c +83 MB/h. These are the steps when load moved between nodes, as in soak 1. b's live
heap stepped from 256 to 392 MB when a died (t = 3,600 to 4,200), and it held 391 to 407 MB for the last hour. a's +59.6 is its
first span after the restart, while it filled again: 248 MB at t = 4,563, 295 at 5,405, 305 at 6,607 and 307 at 7,148. It is
flattening (SOAK2-04).

The console's workers (soak 1 could not see them, SOAK-07). The tree is 10 processes: the uvicorn parent, 4 workers, 4 helper processes
and 1 more. The four workers' resident memory from t = 600 s to the end:

| Worker pid | RSS at 600 s | RSS at end | Slope MB/h |
|---|---:|---:|---:|
| 2529580 | 93.6 | 97.3 | +0.9 |
| 2529581 | 95.5 | 98.8 | +1.2 |
| 2529582 | 98.1 | 100.4 | −0.6 |
| 2529583 | 92.5 | 98.6 | −0.3 |
| the four helpers (2 threads each) | 106.0 to 106.4 | 106.0 to 106.4 | 0.0 |

The tree's total was 871 MB at 600 s and 886 MB at the end. Threads were 23 throughout and open files 136 to 149. No growth.

### Resident memory and the RSS-minus-Xmx gap (`-Xmx2g` = 2,048 MB)

| Process | Soak 1 RSS start → end (MB) | Soak 1 end − Xmx | **Soak 2 RSS start → end** | **Soak 2 end − Xmx** | RSS slope, last hour: 1 → **2** (MB/h) |
|---|---|---:|---|---:|---|
| a, before its kill (t = 3,542) | 1,114 → 2,932 | +884 | 999 → **2,244** | **+196** | |
| a, end (restarted) | 1,269 | −779 | **1,051** | **−997** | +102 → **+130** |
| b | 1,133 → 3,034 | **+986** | 994 → **1,693** | **−355** | +141 → **+46** |
| c (restarted at 1,500) | 1,062 → 2,976 | **+928** | 1,003 → **2,422** (peak 2,447) | **+374** | +257 → **+104** |
| console | 50.7 (parent only) | | 781 → 886 (tree) | | 0 → **+1.4** |

With `MALLOC_ARENA_MAX=2` and `TrimNativeHeapInterval`, the busiest server ends 374 MB above `-Xmx`, against 928 to 986 MB in soak 1.
That is inside PERFORMANCE.md's budget of "about `-Xmx` plus 0.4 GB with two arenas". RSS still rises slowly over the last hour: c +104
and b +46 MB/h, against +257 and +141 before, while the live heap is flat. This is G1 committing more of the heap. It is not a leak on
this evidence: c's committed heap is still below `-Xmx`.

### Threads and open files (after warm-up)

| Process | Threads 1 | **Threads 2** | Open files 1 | **Open files 2** |
|---|---|---|---|---|
| a | 117 to 153 | 116 to 152 | 153 to 181 | 138 to 165 |
| b | 113 to 144 | 113 to 127 | 82 to 166 | 79 to 150 |
| c | 119 to 154 | 115 to 157 | 155 to 200 | 130 to 188 |
| console | 1 (parent) | 23 (tree) | 21 (parent) | 136 to 149 (tree) |

All flat after warm-up, with steps only at the failovers.

## 7. Server and console logs

| Server | WARN 1 | Stack-trace lines 1 | Log 1 | **WARN 2** | **Stack-trace lines 2** | **Log 2** | ERROR 1 / 2 |
|---|---:|---:|---:|---:|---:|---:|---|
| a | 26,697 | 491,248 | 68 MB | **57** | **0** | **55 KB** | 0 / 0 |
| b | 1,055 | 19,090 | 2.6 MB | **13** | **0** | **25 KB** | 0 / 0 |
| c | 47 | 0 | 67 KB | **13** | **0** | **39 KB** | 0 / 0 |
| ref | 12 | 0 | 25 KB | **4** | **0** | **20 KB** | 0 / 0 |

The log flood is gone (SOAK-06). Server a's WARN lines by message (ids folded), all of them:

| Count | Logger | Message |
|---:|---|---|
| 19 | ApiExceptionHandler | request refused: DRS-5052 no node that answered holds the history of trade/<id>, and the nodes that hold trade cannot be reached now: drishti-c (not answering: the connection failed); try again in a moment … repeats are counted and logged once a minute |
| 19 | ApiExceptionHandler | request refused: DRS-5052 the node drishti-c that holds trade failed reading trade/<id>; repeats are counted and logged once a minute (3 of them with "(1 more refusals before it were counted, not logged)") |
| 5 | ApiExceptionHandler | request refused N times in the last 60 s naming drishti-c (dead); the latest: … (N = 3,969, 3,728, 3,663, 3,628, 2,959) |
| 3 | ApiExceptionHandler | request refused: DRS-5052 no source here holds trade/<id> … drishti-c (suspect) / (dead) / **(alive)** … (515 and 482 more refusals counted) |
| 8 | ConnectorManager | connector file collateral-store / legal-store / market-store / trade-murex .yaml **is not usable: … 'enabled' is true or false** (4 at the start, 4 at the restart; SOAK2-02) |
| 2 | Gossiper | net: member drishti-c … is suspect / is dead: no answer for 13 s |
| 1 | UserService | Created development admin 'drishti-dev-admin' with the default password |

- The 38 single refusals were all logged between 17:22:55.35 and 17:22:57.36, the two seconds before c turned suspect. After that,
  the once-a-minute summaries took over (SOAK2-05).
- **b**: 5 once-a-minute summaries (145 to 169 refusals each), 3 single refusals, 2 Gossiper, 2 `not usable` and the development
  administrator. **c**: 10 `not usable` (5 connectors at each of its two starts), 2 Gossiper (a's kill) and the development
  administrator. **ref**: 3 `not usable` and the development administrator.
- There were none of soak 1's `RetryBoard` retries, `deprecated: connector … defined in the server's own configuration` or
  `DRS-6011` warnings (0 in every log).
- **Console.** It set aside the dead node 8 times: c 4 times on its health check, a 4 times after 2 failed requests. It set aside no
  healthy server (soak 1: 43 on `HTTP 502`). It moved sessions 12 times, all off a at its kill. 4 `answers again; it is used for new
  sessions after 60 s healthy` lines. No traceback.

## 8. Re-checks after the run (on the soak's own nodes, synthetic data)

The soak's logs and `soak.json` were copied aside first. Three short runs restarted a (the seed) and c on the soak's node folders and
lakes, with the same JVM settings, and asked the servers' API directly with the soak's tokens. b was not started. Each server was
stopped by PID afterwards.

1. **Administrator first, then viewer.** For MX-50000411 from a and CLY-30000744 from c: admin `days=30`, then viewer
   `days=30&diff=true`, then admin `days=30` again (twice), then viewer `days=30`, then admin `days=30` and `days=60`. Every
   administrator answer was raw (`PRICE` 0.05371 and 0.066451, column `masked: false`, `PRICE` in `rows[4].changed` for CLY-30000744).
   Every viewer answer was masked (`•••`, column `masked: true`). Nothing wrong in this order. A viewer after an administrator still
   gets the masks: no raw value reached a viewer.
2. **Viewer first, on a cold cache.** For MX-50000362 and MX-50000015 from a, and CLY-30001163 and 10000327 from c: viewer
   `days=30&diff=true`, then admin `days=30` at once and 2 s later. **Every administrator answer was masked**: `PRICE` `•••` on every
   row with the column `masked: false`. The same happened with a viewer `days=30` (no diff) followed by admin `days=30`
   (MX-50000001 from a, CLY-30000001 from c), and with viewer `days=60&diff=true` then admin `days=60` (MX-50000002 from a). That is 7
   of 7 trades, in both directions between nodes.
3. **How long it lasts.** After viewer `days=30` of MX-50000200 from a, the administrator's `days=30` was still masked at 0, 30, 95 and
   225 s. In the soak, MX-50000362 was wrong 20 times over two hours. A trade view (`/api/v1/views/trade/…`) asked viewer first then
   admin showed no mask marker in either answer, so views were not affected in this probe. Counterparty histories were not wrong
   in the soak, because c and a hold counterparties themselves.

Where it comes from, read only and not changed: `drishti-engine/src/main/java/com/ash/drishti/engine/history/HistoryService.java`, about
line 196. The cache key is `kind|id|readFrom|to|lastDays|wanted|knownAt|dataVersion`. It has nothing of the caller's masks or roles.
Days read from the node's own sources are raw in the cache and redacted per caller afterwards (`viewer::redact`). That is why local
trades are never wrong. Days read **through a peer** come back already redacted for the on-behalf-of user, and are cached as if raw.

## 9. Soak 1's findings, re-judged

| Finding | Status in soak 2 | Evidence |
|---|---|---|
| SOAK-01 complete calendar history marked partial while a node is down | **fixed** | 0 `partial yet nothing missing`; the 205 history partials all fell in kill windows |
| SOAK-02 DRS-1003 through a dying peer; console sets aside healthy servers | **fixed** | 0 `DRS-1003` in every log; 0 set-asides of healthy servers; 0 session moves in c's window |
| SOAK-03 a restarting node joins before its port opens | **fixed** | joins 0.19 s after `Tomcat started` (was 12.4 to 13.9 s before). One refusal 36 ms after c joined still named it `(alive)` (SOAK2-05). |
| SOAK-04 memory check fails on load moving | **fixed (tool)** | steady slopes b +5.4, c −20.1 against whole-run +134.4, +82.7 |
| SOAK-05 resident memory creep | **much reduced** with the production settings | busiest end gap +374 MB (was +928 to +986); last-hour RSS slope +46 to +130 MB/h (was +102 to +257) |
| SOAK-06 log flood | **fixed** | a 57 WARN and 55 KB (was 26,697 and 68 MB); 0 stack traces |
| SOAK-07 console memory not measured | **fixed (tool)** | the whole tree and each worker sampled (section 6) |
| SOAK-08 wrong answers under-recorded | **fixed (tool)** | all 77 kept with time, node, rule, `partial`, `problems` and a diff; the cause was visible without a re-run |
| SOAK-09 mask check almost vacuous | **fixed** | 140 of 140 viewer histories mask `PRICE` or `NAME`. This is what exposed SOAK2-01. |
| SOAK-10 retry and deprecated noise | **partly**: the noise is gone, but the connector files the soak writes are rejected | SOAK2-02 |

## 10. Findings

### SOAK2-01 · P1 · An administrator's history of a peer-held entity shows the viewer's masks (wrong data)

- **Evidence.** All 77 wrong answers (section 4; `soak2.json` `wrong.all` and `wrong.samples`). Re-check 2 in section 8 reproduces it
  7 times out of 7, on a and on c. Re-check 3 shows it lasting at least 225 s. In the soak it lasted for the whole run.
- **Steps.** A cluster where node A does not hold trade T and node B does (for example a and c of the soak). As a viewer (a role
  without raw access, `drishti.security.redact=PRICE`), ask A `GET /api/v1/history/trade/~?id=T&days=30` (or with `diff=true`). Then,
  as the administrator, ask A the same `days=30`.
- **Expected.** The administrator sees the raw `PRICE` on every row (the reference: for example 0.072043 for MX-50000362), the `PRICE`
  column `masked: false`, and `PRICE` among `changed` on the days it moved.
- **Actual.** `200`, `partial: false`, `PRICE` `•••` on every row while the column says `masked: false`. `changed` leaves out `PRICE`
  (CLY-30000744: 5 → 4 and 4 → 3 flags on rows 4 and 8). The page shows the mask as if it were the value, and hides amendments of
  the price.
- **Cause (read only).** `HistoryService` caches the days under a key with no notion of the caller's masks. Days fetched from a peer on
  behalf of the first caller come back already redacted for that caller, and every later caller is served them. The reverse order did
  not leak raw values to a viewer, because local redaction runs after the cache (re-check 1). The fix should still check that no
  other per-caller filtering a peer applies (rows or nodes per role) can be shared the same way.
- **Severity.** P1: wrong data on a page, silently, for privileged users, and it lasts until the cache entry goes.

### SOAK2-02 · P3 · The soak's switch-off connector files are rejected: `'enabled' is true or false` (tool, and a product inconsistency)

- **Evidence.** 23 WARN lines in all (a 8, b 2, c 10, ref 3), for example on c at each start:
  `connector file trade-endur.yaml is not usable: trade-endur.yaml: 'enabled' is true or false`, the same for trade-calypso,
  market-store, legal-store and collateral-store, and on a, b and ref for their files. The file
  `nodes/c/connectors/trade-endur.yaml` starts with `plugin: delta` and `enabled: 'false'`.
- **Steps.** Run the soak. `net_soak.connector_files` writes `"enabled": "false"`, a YAML string. `ConnectorFiles` (in `drishti-packs`,
  about line 252) accepts a Boolean or a string holding `${`, but not the string `"false"`.
- **Expected.** The connector is switched off, the documented way (SOAK-10). Also, a quoted `"false"` should be accepted, or refused
  with a message that names the quoting: CONNECTOR_FILES.md's own examples quote `enabled: "${DRISHTI_LAKE_ENABLED:true}"`, which
  resolves to the same string.
- **Actual.** The file is ignored and "the last good configuration keeps running". The noise of soak 1 is gone anyway (no
  `RetryBoard`, no `deprecated`), so the run was not harmed. But SOAK-10's switch-off does not happen, and every start warns 2 to 5
  times.
- **Severity.** P3. The tool fix is one character (`False`, not `"false"`). It was left alone because the soak did not crash.

### SOAK2-03 · P3 (watch) · Latency p99 four times soak 1's and 40 % less throughput, on a much busier machine

- **Evidence.** Section 3. Every p99 was 1.12 to 1.34 s (soak 1: 0.30 to 0.37 s), p50 was 0.094 to 0.124 s (soak 1: 0.072 to 0.103 s),
  and there were 123.4 requests a second (soak 1: 207.1). A QA tester's server used about 12 CPUs throughout. The load average was
  41 to 51 for the last 40 minutes (soak 1: about 11), and swap was mostly full.
- **Expected.** Latency comparable with soak 1.
- **Actual.** Worse, but the run cannot tell machine contention from the change of settings. `MALLOC_ARENA_MAX=2` also makes 20
  users' threads share two malloc arenas. The check is report only, and it passed.
- **Next.** Re-measure on a quiet machine, with the production settings and once without (`--server-env`/`--jvm-arg` left out), for an
  A/B of the arena setting. The soak would need a per-interval latency to tell the two apart within one run.

### SOAK2-04 · P3 (watch) · The restarted seed's steady memory slope is close to the limit

- **Evidence.** a's steady slope is +59.6 MB/h against a limit of 64. Its only steady span is t = 4,563 to 7,148 s, after its restart.
  Live data went 248 → 295 → 305 → 307 MB, +18.4 MB/h over the last 30 minutes. Its threads (116 to 120) and open files (143 to 147)
  were flat.
- **Reading.** It fits a node filling its caches again after a restart, not a leak. A slope measured on one 43-minute span is
  sensitive to that. A longer soak, or a later `--warmup` for restarted nodes, would settle it.

### SOAK2-05 · P3 · The refusal log's once-a-minute limit lets about 40 lines through as a peer dies, and one line names a live node

- **Evidence.** Server a logged 38 single `DRS-5052` refusals between 17:22:55.35 and 17:22:57.36, one per trade id, each saying
  "repeats are counted and logged once a minute". Then the per-minute summaries took over (3,969 refusals in the first full minute).
  At 17:28:18.993, 36 ms after a saw c join (17:28:18.957) and before c's digest had arrived, a refused MX-50001280 with
  `… cannot be reached now: drishti-c (alive)`.
- **Expected.** One line per node and kind of refusal until the summary. A refusal that does not name a node as unreachable while it
  is alive.
- **Actual.** It is small (38 lines against soak 1's 26,697) and only cosmetic.

## 11. Housekeeping

- Only this soak's own processes were started and stopped, each by PID: the three servers, the reference server, the console, the load
  and, for the re-checks, a and c again. The QA tester (ports 186xx), the user's cluster (18480/17480/18791/18792), `drishti-stack/run`,
  `data/stack` and the repository's `data/` were not touched.
- The work folder `~/drishti-scale-test/soak2-2026-10-20` (lakes, nodes, logs, `reference.json`, about 300 MB) was removed after the
  run. The figures above come from its logs and `soak.json`, read before removal. `soak.json` is committed as [soak2.json](soak2.json).
