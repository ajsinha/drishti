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
# Drishti*net soak 3 of 2.6.6 (2026-10-20)

Soak 3 is a one-hour soak on real processes. It targets the class of [soak 2](SOAK2_REPORT.md)'s SOAK2-01: an administrator and a viewer
ask for the same peer-held history, in both orders. Fix-10, the history cache keyed by the caller's security signature, was in place.
The soak only records evidence. No product code was changed.

The checker of `tools/bench/net_soak.py` now asks every checked history range in both roles. Each time it picks one, it asks the
**twin** at once: the same entity and range, the other role, on the same node. So an administrator and a viewer meet back to back,
well within the history cache's life (commit `3347987b1`, with a test, DRISHTI_NET.md 17.1). `soak.json` `maskPairs` counts the pairs.
The tool ran to the end the first time. The raw result is [soak3.json](soak3.json).

**Verdict: FAIL.** The tool's verdict fails on two checks. Neither is the SOAK2-01 class.

- **SOAK2-01 is fixed.** The checker asked 15,940 administrator/viewer pairs back to back: 7,924 administrator first and 8,016 viewer
  first. 6,769 of them were peer-held on the node asked. **No answer was masked wrongly**, in either order. The reference's 420 viewer
  histories all mask `PRICE` or `NAME`.
- **The wrong-answers check fails on 3 answers.** All three are `404 DRS-1001` "no source holds". They came from a node that had just
  restarted, within half a second of it joining the net and before it had its peers' digests (SOAK3-01, P2). One of them was an
  administrator/viewer pair, and both roles got the same 404.
- **The memory check fails.** Server b's steady slope was +183.8 MB/h and c's +66.5 (limit 64). The run was half as long as soak 2's,
  with the same two kills and 600 s settles, so the steady spans were only 4 to 9 samples each. The longer fits of the same samples
  are +80 to +90 MB/h for b and −72 to −86 MB/h for c (SOAK3-02, P2, confirm on a two-hour run).

Errors stayed inside the kill windows (0 outside). Latency, which is report only, is back to near soak 1: every p99 is between 0.48 and
0.57 s, with load averages of 10 to 19.

## 1. The run

| | Soak 1 (2.6.5) | Soak 2 (2.6.6) | **Soak 3 (2.6.6)** |
|---|---|---|---|
| Command | as in SOAK_REPORT.md | as in SOAK2_REPORT.md (7,200 s, `--history-share 0.15`, `--history-sample 40`) | `drishti-console/.venv/bin/python tools/bench/net_soak.py --work ~/drishti-scale-test/soak3-2026-10-20 --users 20 --seconds 3600 --days 10 --kill-shard-at 900 --restart-shard-at 1200 --kill-seed-at 2100 --restart-seed-at 2400 --history-share 0.30 --history-sample 60 --settle 600 --sample-every 60 --warmup 600 --max-growth 64 --heap 2g --base-port 18710 --console-port 17710 --server-env MALLOC_ARENA_MAX=2 --jvm-arg=-XX:MaxDirectMemorySize=256m --jvm-arg=-XX:TrimNativeHeapInterval=60000` (the options are as `--help` gives them) |
| Code | `develop` at `6f9555146` | `develop` at `e8375b881` plus `ff15b3893` | **`develop` at `e0e0f4644`** (every fix, including Fix-10 for SOAK2-01 and its sweep `7d5302f2e`) plus `3347987b1` (the checker's back-to-back pairs). `drishti-server-2.6.5-exec.jar` was built in the worktree (`./mvnw -q -T 4 -DskipTests package -pl drishti-server -am`). |
| JVM settings | `-Xmx2g` | `-Xmx2g -XX:MaxDirectMemorySize=256m -XX:TrimNativeHeapInterval=60000`, `MALLOC_ARENA_MAX=2` | the same as soak 2, recorded in `soak3.json` `serverLaunch` |
| Machine | Ryzen AI 9 HX 370, 24 CPUs, 60 GB, load average about 11 | the same machine; load average 6.7 to 13.4, then **41 to 51** for the last 40 minutes | the same machine. Load average **14.6 at the start** (19:48, other builds running), **15.0 to 18.8 in the middle** (20:18 to 20:43), and **15.3 at the end** (20:53), falling to 9.8 at 20:58 after the run. Another agent's lake build was running, and the round-4 QA server on 186xx used 0.4 to 4 CPUs while it was up. Swap was 5 of 7 GB at the start. |
| Ports and addresses | a, b, c = 18710 to 18712, ref 18713, console 17710; `http://192.168.2.77:<port>` | the same | the same. None of the user's ports were used. |
| Data | 1,500 trades a day per system, 300 counterparties, 10 business days 2026-10-02 to 2026-10-16 | the same | the same |
| Setup | lakes and reference 407 s, cluster up 38.3 s, console learnt all three in 1.9 s | 708 s, 39.7 s, 1.4 s | **503 s** (2,860 reference requests, all 200), cluster up **42.9 s**, console learnt all three in **2.0 s** |
| Load | 20 users, 7,200 s, 1,491,085 requests (207.1 a second) | 20 users, 7,201 s, 888,662 requests (123.4 a second) | 20 users, **3,602 s** (19:57:27 to 20:57:27), **660,768 requests (183.5 a second)**, 30 % of them histories |
| Checker | 118,166 checked answers | 99,106 | **70,425**, of which 31,880 are histories. The pairs double the rate at which histories are asked. |
| Console | 2 workers | 4 workers, 10 processes | 4 workers, 10 processes |

## 2. Verdict

| Check | Soak 1 | Soak 2 | **Soak 3** |
|---|---|---|---|
| No wrong answers | fail: 32 (SOAK-01) | fail: 77, all administrator histories with viewer masks (SOAK2-01) | **fail: 3**, all `404 DRS-1001` from a node just rejoined (SOAK3-01). **Masks: 0 wrong.** |
| Errors only inside kill windows | pass: 0 outside | pass: 0 outside | **pass**: 0 outside c 900.0 to 1261.5 s and a 2100.0 to 2457.5 s |
| Latency p95 and p99 per action (report only) | pass, every p99 at most 0.374 s | pass, p99 1.12 to 1.34 s | **pass**, p99 0.48 to 0.57 s |
| Memory flat after warm-up | fail: b +166.4, c +185.3 MB/h (whole run) | pass, steady: a +59.6, b +5.4, c −20.1, console +3.7 | **fail**, steady: a −11.5, **b +183.8**, **c +66.5**, console +10.6 MB/h (limit 64), on spans of 4 to 9 samples (SOAK3-02) |
| Masks exercised | not checked | pass: 140 of 140 viewer histories masked | **pass**: 420 of 420 viewer histories masked; 210 distinct entities, 840 history requests |
| Admin/viewer pairs back to back (new) | not asked | not asked; only by chance, when the load asked the same range | **15,940 pairs**: 7,924 administrator first, 8,016 viewer first; **0 mask errors** |
| **Overall** | **FAIL** | **FAIL** (SOAK2-01) | **FAIL** (SOAK3-01, SOAK3-02). SOAK2-01 is fixed. |

## 3. Latency and errors

Per action, through the console's pages, with the load average of each run:

| Action | p50 / p95 / p99 s (1) | p50 / p95 / p99 s (2) | **Requests 3** | **Failed 3** | **p50 / p95 / p99 s (3)** |
|---|---|---|---:|---:|---|
| trade | 0.072 / 0.208 / 0.304 | 0.094 / 0.422 / 1.263 | 371,036 | 14,208 | **0.069 / 0.227 / 0.524** |
| ctrd | 0.075 / 0.216 / 0.316 | 0.100 / 0.460 / 1.341 | 45,916 | 0 | **0.074 / 0.237 / 0.570** |
| netting-set | 0.091 / 0.214 / 0.303 | 0.107 / 0.386 / 1.207 | 23,045 | 0 | **0.087 / 0.232 / 0.493** |
| search | 0.075 / 0.255 / 0.374 | 0.101 / 0.482 / 1.308 | 23,073 | 0 | **0.069 / 0.275 / 0.558** |
| history | 0.103 / 0.236 / 0.328 | 0.124 / 0.439 / 1.122 | 197,698 | 5,927 | **0.100 / 0.252 / 0.480** |
| Load average | about 11 | 6.7 to 13.4, then 41 to 51 | | | **14.6 / 15.0 to 18.8 / 15.3** (start / middle / end) |
| Requests a second | 207.1 | 123.4 | | | **183.5** |

p50 and p95 are back to soak 1's. p99 is 0.15 to 0.25 s above soak 1's and less than half of soak 2's, on a machine loaded more than in
soak 1 and much less than at the end of soak 2. This fits soak 2's SOAK2-03 being mostly contention from the machine, not the arena
setting: the JVM settings were the same in soaks 2 and 3. A clean A/B of `MALLOC_ARENA_MAX` on a quiet machine is still not done.

The load failures fall in one run of 10-second buckets: 34 buckets, from t = 893 s (c was killed at 900.7) to t = 1,233 s (c rejoined
at 1,231.2). There were none in the seed's window and none anywhere else. They are MUREX pages and histories refused `DRS-5052` while
c, their only holder, was down. 3.8 % of trade pages failed (soak 1: 2.2 %, soak 2: 2.5 %). The share is higher because c's window is a
larger part of a one-hour run.

The checker, per kind (right includes `right-links-pending`; unavailable includes `asked-a-node-going-down`):

| Checked | Right 1 | Wrong 1 | Right 2 | Wrong 2 | **Right 3** | **Partial 3** | **Timeout 3** | **Unavail. 3** | **Wrong 3** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| trade | 101,566 | 0 | 75,799 | 0 | 33,094 | 1,091 | 0 | 189 | **1** |
| search | 10,979 | 0 | 8,151 | 0 | 3,409 | 392 | 0 | 15 | 0 |
| ctrd | 1,049 | 0 | 787 | 0 | 319 | 34 | 0 | 1 | 0 |
| history | 2,046 | 32 | 11,854 | 77 | **30,792** | 904 | 0 | 182 | **2** |
| **All** | 115,640 | 32 | 96,591 | 77 | **67,614** | 2,421 | 0 | 387 | **3** |

Every partial, unavailable and timed-out answer fell inside a kill window. The checker asked 2.6 times as many histories as in soak 2,
in half the time.

The console's own page check (one page a second): soak 1 had 6,496 good and 0 failed; soak 2 had 5,903 good and 0 failed. Soak 3 had
**3,132 good, 0 failed and 0 signed out**.

## 4. Masks: the SOAK2-01 class

`soak3.json` `maskPairs`. A pair is a checked history and its twin, the same entity and range in the other role, asked of the same node
straight after it. Peer-held means the node asked holds none of the entity, so its days come through a peer: MUREX trades from a and b,
ENDUR and CALYPSO trades from c, and counterparties from b.

| Order | Pairs | Peer-held | First answer wrong | Second answer wrong | Second wrong, peer-held | Of which a mask |
|---|---:|---:|---:|---:|---:|---:|
| Administrator first, then viewer | **7,924** | 3,361 | 0 | 0 | 0 | **0** |
| Viewer first, then administrator (SOAK2-01's order) | **8,016** | 3,408 | 1 | 1 | 1 | **0** |
| **All** | **15,940** | **6,769** | 1 | 1 | 1 | **0** |

The one viewer-first pair with wrong answers is SOAK3-01's pair: c asked for ENDUR trade 10000345, from 2026-10-06 to 2026-10-15, at
t = 1,230.9 s. Both roles got `404 DRS-1001`. Neither answer carried a row or a mask.

Coverage of the reference:

| | Soak 2 | **Soak 3** |
|---|---:|---:|
| Distinct histories checked | 140 | **210** (60 trades of each system and 30 counterparties) |
| History requests in the checked set | 280 | **840** (two ranges per entity, each in both roles) |
| Viewer histories that mask `PRICE` or `NAME` | 140 of 140 | **420 of 420** |
| Administrator answers with a viewer's mask | **77** (SOAK2-01) | **0** |
| Viewer answers with a raw value | 0 | **0** |

In soak 2 an administrator and a viewer met on one range only by chance, when the load's user asked the range the checker had asked as
a viewer. In soak 3 they met 15,940 times, on every range and entity, half of them in each order and 42 % of them peer-held. Not one
administrator answer showed `•••`, and not one viewer answer showed a raw value.

## 5. The wrong answers

`soak3.json` keeps all of them under `wrong.all` and `wrong.samples`.

| t (s) | Local time | Node | Request | Answer |
|---:|---|---|---|---|
| 1,230.9 | 20:17:57.7 | c (restarted) | viewer `GET /api/v1/history/trade/~?id=10000345&from=2026-10-06&to=2026-10-15` | `404 DRS-1001 no source holds trade/10000345 (asked file, trade-murex; trade-endur, which kind 'trade' is routed to, is disabled)` |
| 1,230.9 | 20:17:57.7 | c (restarted) | the same as the administrator (the twin) | the same `404 DRS-1001` |
| 2,427.5 | 20:37:54.3 | a (restarted) | administrator `GET /api/v1/views/trade/MX-50001412` | `404 DRS-1001 no source holds trade/MX-50001412` |

The two restarted nodes' logs:

| | c (shard) | a (seed) |
|---|---|---|
| `Tomcat started` | 20:17:57.425 | 20:37:53.662 |
| `node … joined Drishti*net` | 20:17:57.601 | 20:37:53.955 |
| `member drishti-a / drishti-b joined` (peers seen) | 20:17:57.800 / .801 | 20:37:54.244 / .246 (b, c) |
| `StateSync … queued` | 20:17:57.894 / .922 | 20:37:54.424 / .458 |
| The wrong answer | about 20:17:57.7, between joining and seeing its peers | about 20:37:54.3, just after seeing its peers and before StateSync |

The local times of the wrong answers come from the soak's `t`. The load's clock starts 900.7 s before a's first refusal naming c
(20:12:27.597). They are good to about 0.1 s. In both cases the node had joined, was answering, and did not yet know which peers hold
the entity. So it said the entity does not exist (404) when it should have said "a holder cannot be reached now" (`502 DRS-5052`). Its
peers did this correctly at the same moment, naming the node `(rejoining)` (server a, 20:17:57.736). Both answers fall inside the kill
windows, but a 404 is wrong at any time.

## 6. The kill windows

| | Shard c, soak 1 | Shard c, soak 2 | **Shard c, soak 3** | Seed a, soak 1 | Seed a, soak 2 | **Seed a, soak 3** |
|---|---|---|---|---|---|---|
| Killed (SIGKILL by PID) | t = 1200.5 s | t = 1200.5 s | **t = 900.7 s** (20:12:27) | t = 3600.3 s | t = 3600.3 s | **t = 2100.7 s** (20:32:27) |
| Seen suspect or dead by every live member | 3.6 s | 2.2 s | **2.6 s** | 3.1 s | 2.7 s | **3.7 s** |
| Console: next good page | 0.02 s | 0.06 s | **0.02 s** | 0.05 s | 0.05 s | **0.06 s** |
| Console: CALYPSO search right after | 200, rows | 200, rows | **200, rows** | 200, rows | 200, rows | **200, rows** |
| Load failures in the window | trade 22,007, history 3,841 | trade 15,354, history 2,812 | **trade 14,208, history 5,927** (`DRS-5052`), t = 893 to 1,233 | none | none | **none** |
| Console set-asides | 43 healthy servers on `HTTP 502` | 4, c on its health check | **4, c on its health check (`ConnectError`)** | | 4, a after 2 failed requests | **4, a after 2 failed requests (`ReadError`, `RemoteProtocolError`)** |
| Session moves | | none | **none** | 10 | 12 | **3, all a to c** |
| Restarted: healthy, all alive | 22.1 s, 22.2 s | 24.7 s, 24.8 s | **30.5 s, 30.5 s** | 21.2 s, 21.2 s | 26.4 s, 26.4 s | **26.6 s, 26.7 s** (from `peers.json`, no seed) |
| Joined the net vs its port open | 13.9 s before Tomcat | 0.19 s after | **0.18 s after** (57.425 → 57.601) | 12.4 s before | 0.19 s after | **0.29 s after** (53.662 → 53.955) |
| Window closes (rejoined + 30 s grace) | 1552.2 s | 1554.8 s | **1261.5 s** | 3952.0 s | 3956.7 s | **2457.5 s** |
| Wrong answers in the window | | 0 | **2** (SOAK3-01) | | 0 | **1** (SOAK3-01) |

## 7. Memory, threads and open files

### Steady slopes (the verdict)

Live data after the last collection is used for the servers. The console is measured by the summed resident memory of its process tree.
The steady spans are outside every kill window and outside the `--settle` 600 s after each, with a level of their own per span:

| Process | Soak 1 (whole run) | Soak 2 steady | **Soak 3 steady** | Soak 3 steady spans (s), samples | Soak 3 whole run | Soak 3, longer fits of the same samples |
|---|---:|---:|---:|---|---:|---|
| a (restarted at 2,400) | −21.7 | +59.6 | **−11.5** | 3,062 to 3,542 (9) | −11.5 | 2,641 to 3,600: +43.3; before its kill, 600 to 2,100: −36.0 |
| b | +166.4 | +5.4 | **+183.8** | 600 to 900 (5); 1,862 to 2,100 (4); 3,058 to 3,542 (9) | +214.9 | 1,261 to 2,100: −103.6; 2,461 to 3,600: **+90.1**; 2,641 to 3,600: +80.6 |
| c (restarted at 1,200) | +185.3 | −20.1 | **+66.5** | 1,862 to 2,100 (4); 3,058 to 3,542 (9) | +121.4 | 2,461 to 3,600: −86.2; 2,641 to 3,600: −71.7 |
| console (whole tree) | 0.0 (parent only) | +3.7 | **+10.6** | as b | +1.2 | RSS 869.3 → 877.0 MB over 600 to 3,542 s (+1.2) |

b's live data, MB:

| t (s) | 600 | 900 | 1,262 | 1,861 | 2,100 | 2,461 | 2,641 | 3,061 | 3,301 | 3,542 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| b | 270 | 289 | 280 | 293 | 283 | 393 | 413 | 408 | 440 | 430 |

b stepped from about 285 to about 395 MB when a died (t = 2,100 to 2,461), as in soak 2 (256 to 392). After that it rose by 37 MB in
18 minutes: 393, 401, 409, 413, 408, 412, 419, 411, 409, 411, 408, 407, 419, 417, 440, 417, 422, 430, 430. In soak 2 it held 391 to 407
for the last hour. c held 520 to 574 MB over the same time with no trend. a refilled after its restart: 182 at t = 2,461, 308 at 2,641,
then 323 to 331. The verdict's figures come from 4 to 9 samples a span. On spans that short, 20 MB of noise from one collection to the
next is worth ±200 MB/h (b's two earlier spans give +358 and −207 when fitted alone).

### Resident memory and the RSS-minus-Xmx gap (`-Xmx2g` = 2,048 MB)

| Process | Soak 1 end − Xmx | Soak 2 end − Xmx | **Soak 3 RSS start → end (MB)** | **Soak 3 end − Xmx** |
|---|---:|---:|---|---:|
| a, before its kill (t = 2,041) | +884 | +196 | 991 → **2,363** | **+315** |
| a, end (restarted) | −779 | −997 | **1,122** | **−926** |
| b | +986 | −355 | 990 → **1,912** | **−136** |
| c (restarted at 1,200) | +928 | +374 | 1,079 → **2,386** (peak 2,388) | **+338** |
| console (tree) | | 781 → 886 | 777 → **877** | |

The busiest server ends 338 MB above `-Xmx`, inside PERFORMANCE.md's "about `-Xmx` plus 0.4 GB with two arenas", as in soak 2 (+374).
RSS still rises over the last 18 minutes, by about 500 MB/h for b and c, while their committed heap stays below `-Xmx` (b 0.6 to 1.1 GB,
c 0.7 to 1.4 GB). This is G1 committing heap after the failover moved load. It is not a native leak on this evidence.

### Threads and open files (after warm-up)

| Process | Threads 2 | **Threads 3** | Open files 2 | **Open files 3** |
|---|---|---|---|---|
| a | 116 to 152 | **134 to 152** (117 to 124 after its restart) | 138 to 165 | **137 to 165** |
| b | 113 to 127 | **111 to 124** | 79 to 150 | **74 to 140** |
| c | 115 to 157 | **117 to 155** | 130 to 188 | **127 to 167** |
| console (tree) | 23 | **23** | 136 to 149 | **137 to 149** |

All are flat, with steps only at the failovers. The console's four workers ran at 91 to 103 MB from t = 600 s to the end, and its four
helpers at 105 to 106 MB.

## 8. Server and console logs

| Server | WARN 1 | WARN 2 | **WARN 3** | Stack-trace lines 1 / 2 / **3** | Log 2 | **Log 3** | ERROR |
|---|---:|---:|---:|---|---:|---:|---|
| a | 26,697 | 57 | **11** | 491,248 / 0 / **0** | 55 KB | **44 KB** | 0 |
| b | 1,055 | 13 | **13** | 19,090 / 0 / **0** | 25 KB | **24 KB** | 0 |
| c | 47 | 13 | **1** | 0 / 0 / **0** | 39 KB | **36 KB** | 0 |
| ref | 12 | 4 | **1** | 0 / 0 / **0** | 20 KB | **20 KB** | 0 |

Every WARN line of soak 3:

| Server | Count | Logger | Message |
|---|---:|---|---|
| all four | 1 each | UserService | Created development admin 'drishti-dev-admin' with the default password |
| a | 2 | ApiExceptionHandler | single refusals at c's kill (20:12:27.597 and .607), the second with "(7 more refusals before it were counted, not logged)" |
| a | 2 | ApiExceptionHandler | `drishti-c (suspect)` with 161 more counted, then `(dead)` with 778 more counted |
| a | 5 | ApiExceptionHandler | `request refused N times in the last 60 s naming drishti-c (dead)`, N = 4,751, 3,477, 4,151, 4,173, 2,996 |
| a | 1 | ApiExceptionHandler | 20:17:57.736: `… cannot be reached now: drishti-c (rejoining)` with 742 more counted |
| b | 3 | ApiExceptionHandler | single refusals at c's kill, `(suspect)` and `(dead)` (33 more counted) |
| b | 5 | ApiExceptionHandler | once-a-minute summaries naming drishti-c (dead): 195, 201, 186, 182, 150 |
| b | 4 | Gossiper | c suspect and dead; a suspect and dead |

- **SOAK2-02 is fixed.** There were no `connector file … is not usable: 'enabled' is true or false` lines (soak 2 had 23).
- **SOAK2-05 is fixed.** a logged 2 single refusals at c's kill, not 38. The refusal 136 ms after c joined names it `(rejoining)`, not
  `(alive)`.
- There were no `RetryBoard`, `deprecated` or `DRS-6011` lines in any log.
- **Console.** There were 0 WARN lines and no traceback. It set aside the dead node 8 times: c 4 times on its health check, and a 4
  times after 2 failed requests. It set aside no healthy server and moved sessions 3 times, all off a at its kill.

## 9. Soak 2's findings, re-judged

| Finding | Status in soak 3 | Evidence |
|---|---|---|
| SOAK2-01 an administrator's history of a peer-held entity shows the viewer's masks | **fixed** | 0 mask errors in 15,940 back-to-back pairs, 8,016 of them viewer first and 3,408 of those peer-held (section 4) |
| SOAK2-02 the soak's connector files are rejected | **fixed** | 0 `not usable` lines (was 23) |
| SOAK2-03 latency four times soak 1's | **mostly machine contention** | p99 0.48 to 0.57 s with the same JVM settings at a load average of 15 to 19 (soak 2: 1.12 to 1.34 s at 41 to 51). The A/B on a quiet machine is still open. |
| SOAK2-04 the restarted seed's slope is near the limit | **fixed on this run** | a −11.5 MB/h after its restart (was +59.6). The run is short. |
| SOAK2-05 about 40 refusal lines as a peer dies, one naming a live node | **fixed** | 2 single lines at the kill, then summaries; `(rejoining)`, not `(alive)` |

## 10. Findings

### SOAK3-01 · P2 · A node that has just rejoined answers 404 "no source holds" for entities only its peers hold

- **Evidence.** All 3 wrong answers (section 5; `soak3.json` `wrong.all` and `wrong.samples`). One came from c 0.1 to 0.2 s after it
  joined, at 20:17:57.7 (joined 57.601, peers seen 57.800). The other came from a about 0.35 s after it joined, at 20:37:54.3 (joined
  53.955, peers seen 54.244, StateSync 54.424). The requests were a history of an ENDUR trade from c, in both roles, and a MUREX trade
  page from a: entities the node holds none of.
- **Steps.** In a three-node net, kill a node and restart it. In the first half second after its `joined Drishti*net` line, ask it for
  an entity that only its peers hold, for example `GET /api/v1/views/trade/MX-…` of a, or `GET /api/v1/history/trade/~?id=<ENDUR id>`
  of c.
- **Expected.** The answer from the peer, or `502 DRS-5052` saying the holders cannot be reached yet. That is what the peers answered
  for the rejoining node at the same moment (`drishti-c (rejoining)`).
- **Actual.** `404 DRS-1001 no source holds trade/MX-50001412`. For a history it is "… trade-endur, which kind 'trade' is routed to, is
  disabled". A user sees "entity not found" for a trade that exists. Both roles get the same answer, and no mask or data is wrong.
- **Severity.** P2. It is a false "does not exist", short (under half a second per restart) and only after a restart. It was not seen
  in soak 2, which checked about half as often. The fix may be for a node to answer `DRS-5052` (rejoining) for kinds it routes to peers
  until it has its peers' digests, or to join only once it has them. It was not reproduced on demand after the run.

### SOAK3-02 · P2 · The memory check fails on a one-hour run: b's steady slope +183.8 MB/h, c's +66.5 (limit 64)

- **Evidence.** Section 7. The verdict's steady spans have 4 to 9 samples each, because a one-hour run with kills at 900 and 2,100 s and
  600 s settles leaves only three short spans. Fitted over the last 18 minutes (19 samples), b rises +90 MB/h (393 → 430 MB, mostly
  in two late steps) and c falls −86 MB/h. b's threads (111 to 124) and open files (74 to 140) are flat, and its RSS gap is −136 MB.
- **Expected.** Live data flat after the failover step, as b was in soak 2 (391 to 407 MB for an hour).
- **Actual.** b's live data still climbs slowly after a's kill moved load onto it. On this length of run the gate cannot tell a cache
  filling up from growth. One new thing is that the history cache now keeps peer-read days once per security signature (Fix-10), and
  this soak asks histories at twice soak 2's share and in two roles all the time. So a slower fill in b, which holds no counterparties
  and reads both from peers, is plausible.
- **Severity.** P2, and the gate fails. It is not a leak on this evidence. **Next:** a two-hour run, or this one with the kills at 1,200
  and 3,600 s as in soak 2, to measure b's slope on spans of 20 samples or more. Check also that the history cache's entries are
  bounded per signature.

## 11. Housekeeping

- Only this soak's own processes were started and stopped, each by PID by the tool: the three servers, the reference, the console and
  the load. No re-check processes were started. The round-4 QA tester (ports 186xx), the user's cluster (18480/17480/18791/18792),
  `drishti-stack/run`, `data/stack` and the repository's `data/` were not touched.
- The work folder `~/drishti-scale-test/soak3-2026-10-20` (lakes, nodes, logs, `reference.json`, about 500 MB) was removed after the
  run. The figures above come from its logs and `soak.json`, read before removal. `soak.json` is committed as [soak3.json](soak3.json),
  102 KB.
