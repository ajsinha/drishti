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
# Drishti*net soak 4 of 2.6.6 (2026-10-20)

Soak 4 is a two-hour soak on real processes. It checks the two findings of [soak 3](SOAK3_REPORT.md) after Fix-12:

- **SOAK3-01.** A node that has just started or rejoined must never answer `404 DRS-1001` for what only its peers hold.
- **SOAK3-02.** The history cache's `cache-mb` must be sized as real heap.

Both kills come early, the shard at 900 s and the seed at 1,800 s. That leaves a quiet tail of 73 minutes, so the memory gate gets long
steady spans. The checker still asks every administrator/viewer twin in both orders (soak 3's tool change). Besides the tool's own
samples, the history cache's bytes and entries were read from `GET /api/v1/admin/caches` (Admin → Cache) on every server once a minute.
That way a cache filling up to its budget can be told apart from a leak. The soak only records evidence. No product code was changed.
The raw result is [soak4.json](soak4.json).

**Verdict: FAIL.** One check fails: memory. Every other check passes.

- **SOAK3-01 is fixed.** There were **0 wrong answers** out of 146,498 checked, and no `404 DRS-1001` at any time. That includes the
  30 s after each rejoin. Each restarted node had its peers' digests 427 ms (c) and 491 ms (a) after joining. The server logs show
  **0** "still joining" `DRS-5052` refusals, and the checker got 0 refusals that named no down node.
- **SOAK3-02's cache part is fixed.** On all three servers the history cache filled to its 64 MiB budget within 2 minutes. It then held
  between 62.1 and 67.1 MB (63 to 208 entries) for the rest of the run. It is bounded, and it is not what grows.
- **The memory check fails on c: +165.5 MB/h** steady (limit 64). That fit uses 74 samples over a 73-minute span (soak 3 had 4 to 9).
  After a's kill, c was the server the console used for most of its traffic. Its live data's floor rose from about 380 to about 480 MB
  while its history cache stayed at the budget and its view-shape cache had stopped growing (SOAK4-01, P2). a (−6.4) and b (+32.2)
  pass on the same span.

Errors stayed inside the kill windows (0 outside). Latency is report only, and it is the best of the four soaks: every p99 is between
0.34 and 0.48 s, at a load average of 6.6 to 17.2 (mean 12.0).

## 1. The run

| | Soak 1 (2.6.5) | Soak 2 (2.6.6) | Soak 3 (2.6.6) | **Soak 4 (2.6.6)** |
|---|---|---|---|---|
| Command | as in SOAK_REPORT.md | as in SOAK2_REPORT.md | as in SOAK3_REPORT.md (3,600 s, kills at 900 and 2,100) | `drishti-console/.venv/bin/python tools/bench/net_soak.py --work ~/drishti-scale-test/soak4-2026-10-20 --users 20 --seconds 7200 --days 10 --kill-shard-at 900 --restart-shard-at 1200 --kill-seed-at 1800 --restart-seed-at 2100 --history-share 0.30 --history-sample 60 --settle 600 --sample-every 60 --warmup 600 --max-growth 64 --heap 2g --base-port 18710 --console-port 17710 --server-env MALLOC_ARENA_MAX=2 --jvm-arg=-XX:MaxDirectMemorySize=256m --jvm-arg=-XX:TrimNativeHeapInterval=60000` (the options are as `--help` gives them) |
| Code | `develop` at `6f9555146` | `e8375b881` plus `ff15b3893` | `e0e0f4644` plus `3347987b1` | **`develop` at `d0e7a47f7`**: every fix, including Fix-12 (`0d7ffa7b3` SOAK3-01 joining, `6e0b2f0c0` SOAK3-02 cache sizing). `drishti-server-2.6.5-exec.jar` was built in the worktree (`./mvnw -q -T 4 -DskipTests package -pl drishti-server -am`, 75 s). |
| JVM settings | `-Xmx2g` | `-Xmx2g -XX:MaxDirectMemorySize=256m -XX:TrimNativeHeapInterval=60000`, `MALLOC_ARENA_MAX=2` | the same as soak 2 | the same as soak 2, recorded in `soak4.json` `serverLaunch` |
| Machine | Ryzen AI 9 HX 370, 24 CPUs, 60 GB, load average about 11 | load average 6.7 to 13.4, then 41 to 51 | load average 14.6 / 15.0 to 18.8 / 15.3 | `uptime`: **2.98** at 21:32 (before setup); **12.94, 13.72, 13.05** at 22:38 (middle); **11.16, 11.40, 11.77** at 23:37 (end). Sampled each minute during the load: 1-minute load average 6.6 to 17.2, mean 12.0. The soak itself used most of it (its three servers 1.3 to 2.6 CPUs each, the console's workers about 0.9 each). The user's cluster (18480/18791/18792) and an older server on 18795 were up, untouched. Swap was 5 of 7 GB used at the start. |
| Ports and addresses | a, b, c = 18710 to 18712, ref 18713, console 17710; `http://192.168.2.77:<port>` | the same | the same | the same. None of the user's ports were used. |
| Data | 1,500 trades a day per system, 300 counterparties, 10 business days 2026-10-02 to 2026-10-16 | the same | the same | the same (4,500 distinct trades) |
| Setup | lakes and reference 407 s, cluster up 38.3 s, console learnt all three in 1.9 s | 708 s, 39.7 s, 1.4 s | 503 s, 42.9 s, 2.0 s | **364 s** to the load's start (2,860 reference requests, all 200), cluster up **37.5 s**, console learnt all three in **1.9 s** |
| Load | 20 users, 7,200 s, 1,491,085 requests (207.1 a second) | 20 users, 7,201 s, 888,662 (123.4) | 20 users, 3,602 s, 660,768 (183.5), 30 % histories | 20 users, **7,200 s** (21:38:08 to 23:38:08), **1,459,157 requests (202.7 a second)**, 30 % of them histories |
| Checker | 118,166 checked answers | 99,106 | 70,425 (31,880 histories) | **146,498** (66,328 histories) |
| Cache samples (new) | | | | `GET /api/v1/admin/caches` on a, b and c every 60 s, 21:32 to 23:39: 128 samples, history cache `bytes`, `entries`, `hits` and `misses`, the engine's layouts and fingerprints, the Delta memory budget |
| Console | 2 workers | 4 workers, 10 processes | 4 workers, 10 processes | 4 workers, 10 processes |

## 2. Verdict

| Check | Soak 1 | Soak 2 | Soak 3 | **Soak 4** |
|---|---|---|---|---|
| No wrong answers | fail: 32 (SOAK-01) | fail: 77 (SOAK2-01) | fail: 3, `404 DRS-1001` from a node just rejoined (SOAK3-01) | **pass: 0**. No 404 at any time, none after either rejoin. |
| Errors only inside kill windows | pass: 0 outside | pass: 0 outside | pass: 0 outside | **pass**: 0 outside c 900.0 to 1,249.0 s and a 1,800.0 to 2,152.6 s |
| Latency p95 and p99 per action (report only) | pass, p99 at most 0.374 s | pass, p99 1.12 to 1.34 s | pass, p99 0.48 to 0.57 s | **pass**, p99 **0.34 to 0.48 s** |
| Memory flat after warm-up | fail: b +166.4, c +185.3 MB/h (whole run) | pass, steady: a +59.6, b +5.4, c −20.1, console +3.7 | fail, steady: a −11.5, b +183.8, c +66.5, console +10.6 (spans of 4 to 9 samples) | **fail**, steady: a −6.4, b +32.2, **c +165.5**, console +2.1 MB/h (limit 64), on spans of **74 to 79 samples** (SOAK4-01) |
| Masks exercised | not checked | pass: 140 of 140 | pass: 420 of 420 | **pass**: 420 of 420 viewer histories masked; 210 distinct entities, 840 history requests |
| Admin/viewer pairs back to back | not asked | not asked | 15,940 pairs, 0 mask errors | **33,164 pairs**: 16,508 administrator first, 16,656 viewer first; **0 wrong** |
| History cache bounded (new, by hand) | | | (the cache held 150 to 210 MB at `cache-mb: 64`, SOAK3-02) | **pass**: 62.1 to 67.1 MB on every server once full (budget 64 MiB = 67.1 MB) |
| **Overall** | **FAIL** | **FAIL** (SOAK2-01) | **FAIL** (SOAK3-01, SOAK3-02) | **FAIL** (SOAK4-01, memory on c). SOAK3-01 is fixed. SOAK3-02's cache bound is fixed. |

## 3. Latency and errors

Per action, through the console's pages, with the load average of each run:

| Action | p50 / p95 / p99 s (1) | (2) | (3) | **Requests 4** | **Failed 4** | **p50 / p95 / p99 s (4)** |
|---|---|---|---|---:|---:|---|
| trade | 0.072 / 0.208 / 0.304 | 0.094 / 0.422 / 1.263 | 0.069 / 0.227 / 0.524 | 816,233 | 16,978 | **0.070 / 0.176 / 0.371** |
| ctrd | 0.075 / 0.216 / 0.316 | 0.100 / 0.460 / 1.341 | 0.074 / 0.237 / 0.570 | 102,129 | 0 | **0.075 / 0.184 / 0.475** |
| netting-set | 0.091 / 0.214 / 0.303 | 0.107 / 0.386 / 1.207 | 0.087 / 0.232 / 0.493 | 51,298 | 0 | **0.090 / 0.185 / 0.335** |
| search | 0.075 / 0.255 / 0.374 | 0.101 / 0.482 / 1.308 | 0.069 / 0.275 / 0.558 | 51,248 | 0 | **0.075 / 0.205 / 0.401** |
| history | 0.103 / 0.236 / 0.328 | 0.124 / 0.439 / 1.122 | 0.100 / 0.252 / 0.480 | 438,249 | 7,412 | **0.104 / 0.205 / 0.338** |
| Load average | about 11 | 6.7 to 13.4, then 41 to 51 | 14.6 / 15.0 to 18.8 / 15.3 | | | **2.98 before / 12.94 middle / 11.16 end**; 6.6 to 17.2 during, mean 12.0 |
| Requests a second | 207.1 | 123.4 | 183.5 | | | **202.7** |

p95 is the lowest of the four soaks on every action. p99 is within 0.03 to 0.16 s of soak 1's, at about soak 1's load and request
rate. With the same JVM settings as soaks 2 and 3, this again points to SOAK2-03 being contention from the machine.

The load's failures fall in one run of 10-second buckets: 33 buckets, from t = 892 s (c was killed at 900.3) to t = 1,222 s (c rejoined
at 1,219.0). There were none in the seed's window and none anywhere else. They are MUREX pages and histories refused while c, their
only holder, was down. 2.1 % of trade pages failed (soak 1: 2.2 %, soak 2: 2.5 %, soak 3: 3.8 %).

The checker, per kind (right includes `right-links-pending`; unavailable here is all `asked-a-node-going-down`, with **0** refusals
naming no down node):

| Checked | Right 1 | Wrong 1 | Right 2 | Wrong 2 | Right 3 | Wrong 3 | **Right 4** | **Partial 4** | **Timeout 4** | **Unavail. 4** | **Wrong 4** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| trade | 101,566 | 0 | 75,799 | 0 | 33,094 | 1 | **70,208** | 1,118 | 0 | 142 | **0** |
| search | 10,979 | 0 | 8,151 | 0 | 3,409 | 0 | **7,511** | 412 | 0 | 18 | **0** |
| ctrd | 1,049 | 0 | 787 | 0 | 319 | 0 | **726** | 33 | 0 | 2 | **0** |
| history | 2,046 | 32 | 11,854 | 77 | 30,792 | 2 | **65,268** | 926 | 0 | 134 | **0** |
| **All** | 115,640 | 32 | 96,591 | 77 | 67,614 | 3 | **143,713** | 2,489 | 0 | 296 | **0** |

Every partial and unavailable answer fell inside a kill window.

The console's own page check (one page a second): soak 1 had 6,496 good and 0 failed, soak 2 had 5,903 and 0, and soak 3 had 3,132
and 0. Soak 4 had **6,599 good, 0 failed and 0 signed out**.

## 4. Masks: the SOAK2-01 class

`soak4.json` `maskPairs`. A pair is a checked history and its twin, the same entity and range in the other role, asked of the same node
straight after it. Peer-held means the node asked holds none of the entity.

| Order | Pairs 3 | **Pairs 4** | **Peer-held 4** | **First wrong** | **Second wrong** | **Second wrong, peer-held** |
|---|---:|---:|---:|---:|---:|---:|
| Administrator first, then viewer | 7,924 | **16,508** | 7,043 | 0 | 0 | 0 |
| Viewer first, then administrator (SOAK2-01's order) | 8,016 | **16,656** | 7,180 | 0 | 0 | 0 |
| **All** | 15,940 | **33,164** | **14,223** | **0** | **0** | **0** |

The reference's coverage is unchanged from soak 3: 210 distinct histories (60 trades of each system and 30 counterparties), 840
history requests, and 420 of 420 viewer histories that mask `PRICE` or `NAME`. No administrator answer showed `•••`, and no viewer
answer showed a raw value.

## 5. SOAK3-01: answers around the rejoins

| | Shard c, soak 3 | **Shard c, soak 4** | Seed a, soak 3 | **Seed a, soak 4** |
|---|---|---|---|---|
| `Tomcat started` | 20:17:57.425 | **21:58:26.448** | 20:37:53.662 | **22:13:29.700** |
| `node … joined Drishti*net` | 20:17:57.601 | **21:58:26.592** (incarnation 2, 2 saved members) | 20:37:53.955 | **22:13:29.896** (incarnation 2, no seed, 2 saved members) |
| `has joined: it has its peers' digests` (new with Fix-12) | | **21:58:27.019, 427 ms after joining** | | **22:13:30.387, 491 ms after joining** |
| Peers name it `(rejoining)` | a 20:17:57.736 | **a 21:58:26.744**, 0.15 s after it joined | | |
| `404 DRS-1001` from the restarted node | **2** (one admin/viewer pair) | **0** | **1** | **0** |
| `502 DRS-5052 … still joining` from the restarted node | (did not exist) | **0** in its log; **0** checker refusals naming no down node | | **0**; **0** |
| `404 DRS-1001` anywhere in the run | 3 | **0** (checker), **0** in the server and console logs | | |

The checker asked about 20 answers a second, spread over the running nodes, so each restarted node got roughly 200 checks inside its
30 s grace after rejoining. All were right, or partial or refused naming the node that was down. The load's users (about 200 a second
through the console) had no failure after t = 1,222 s, so none in the 30 s after c rejoined and none in a's whole window.

With Fix-12 a restarted node is "joining" until it has its peers' digests. In both restarts that took under half a second. In that
time no read reached it that needed a peer, or one did and waited the up-to-2 s `join-wait` for the digests. Either way the node never
had to refuse with `DRS-5052` "still joining", and it never answered 404. **SOAK3-01 is fixed on this evidence.**

## 6. The kill windows

| | Shard c, 1 | Shard c, 2 | Shard c, 3 | **Shard c, 4** | Seed a, 1 | Seed a, 2 | Seed a, 3 | **Seed a, 4** |
|---|---|---|---|---|---|---|---|---|
| Killed (SIGKILL by PID) | t = 1200.5 s | 1200.5 s | 900.7 s | **t = 900.3 s** (21:53:08) | 3600.3 s | 3600.3 s | 2100.7 s | **t = 1,800.5 s** (22:08:08) |
| Seen suspect or dead by every live member | 3.6 s | 2.2 s | 2.6 s | **2.6 s** | 3.1 s | 2.7 s | 3.7 s | **2.5 s** |
| Console: next good page | 0.02 s | 0.06 s | 0.02 s | **0.03 s** | 0.05 s | 0.05 s | 0.06 s | **0.25 s** |
| Console: CALYPSO search right after | 200, rows | 200, rows | 200, rows | **200, rows** | 200, rows | 200, rows | 200, rows | **200, rows** |
| Load failures in the window | trade 22,007, history 3,841 | 15,354 / 2,812 | 14,208 / 5,927 | **trade 16,978, history 7,412** (t = 892 to 1,222) | none | none | none | **none** |
| Console set-asides | 43 healthy on `HTTP 502` | 4 | 4, c on its health check | **4, c on its health check (`ConnectError`)** | | 4 | 4 | **4, a after 2 failed requests (`ReadError`, `ConnectError`)** |
| Session moves | | none | none | **none** | 10 | 12 | 3 | **12**, all off a: 9 to c, 3 to b |
| Restarted: healthy, all alive | 22.1 s, 22.2 s | 24.7, 24.8 | 30.5, 30.5 | **18.4 s, 18.5 s** | 21.2, 21.2 | 26.4, 26.4 | 26.6, 26.7 | **21.8 s, 21.8 s** (from `peers.json`, no seed) |
| Joined the net vs its port open | 13.9 s before Tomcat | 0.19 s after | 0.18 s after | **0.14 s after**; digests 0.43 s later | 12.4 s before | 0.19 s after | 0.29 s after | **0.20 s after**; digests 0.49 s later |
| Window closes (rejoined + 30 s grace) | 1552.2 s | 1554.8 s | 1261.5 s | **1,249.0 s** | 3952.0 s | 3956.7 s | 2457.5 s | **2,152.6 s** |
| Wrong answers in the window | | 0 | 2 | **0** | | 0 | 1 | **0** |

## 7. Memory, threads and open files

### Steady slopes (the verdict)

The servers are measured by live data after the last collection (`jvm.gc.live.data.size`). The console is measured by the summed
resident memory of its process tree. The steady spans are outside every kill window and outside the 600 s `--settle` after each, and
each span gets a level of its own. With both kills before 2,153 s, the last steady span runs from 2,752.6 to 7,143.2 s: **73 minutes,
74 samples**.

| Process | Soak 1 (whole) | Soak 2 steady | Soak 3 steady | **Soak 4 steady** | **Soak 4 spans (s), samples** | **Soak 4 whole run** | **Soak 4, other fits of the same span** |
|---|---:|---:|---:|---:|---|---:|---|
| a (restarted at 2,100) | −21.7 | +59.6 | −11.5 | **−6.4** | 2,761.5 to 7,143.2 (74) | −6.4 | first half −16.3, second half +2.5; 10-minute minima −9.4 |
| b | +166.4 | +5.4 | +183.8 | **+32.2** | 600 to 900 (5); 2,752.6 to 7,143.2 (74) | +99.9 | first half +40.6, second half +32.1; 10-minute minima **+14.7**, medians +21.3 |
| c (restarted at 1,200) | +185.3 | −20.1 | +66.5 | **+165.5** | 2,752.6 to 7,143.2 (74) | +156.3 | first half −45.4, **second half +67.8, last 60 minutes +218.9**; 10-minute minima **+89.8**, medians +155.3 |
| console (whole tree) | 0.0 | +3.7 | +10.6 | **+2.1** | as b | +3.5 | RSS 782.8 → 899.0 MB over the run |

Live data in 10-minute blocks of the steady span (min / median / max MB):

| Block from (s) | 2,753 | 3,353 | 3,953 | 4,553 | 5,153 | 5,753 | 6,353 | 6,953 |
|---|---|---|---|---|---|---|---|---|
| a | 233 / 238 / 242 | 232 / 238 / 242 | 219 / 229 / 235 | 222 / 230 / 251 | 226 / 229 / 247 | 220 / 229 / 234 | 221 / 231 / 243 | 221 / 235 / 235 |
| b | 330 / 339 / 389 | 332 / 338 / 424 | 335 / 350 / 445 | 337 / 345 / 415 | 337 / 359 / 444 | 339 / 366 / 430 | 339 / 357 / 458 | 352 / 359 / 474 |
| **c** | 376 / 456 / 558 | 397 / 454 / 524 | 382 / 392 / 423 | 381 / 446 / 628 | **477 / 567 / 747** | 417 / 529 / 617 | **464 / 623 / 712** | **479 / 571 / 796** |

### The history cache, sampled alongside (SOAK3-02)

`GET /api/v1/admin/caches`, `history` (`cache-mb: 64` = 67.1 MB), once a minute:

| | a | b | c |
|---|---|---|---|
| Full (first sample at the budget) | 66.5 MB at t = 56 s | 66.9 MB at t = 116 s | 67.0 MB at t = 116 s, and again 2 minutes after its restart |
| Bytes from t = 600 s to the end | 33.1 (just after its restart) to 67.1 MB | 63.2 to 67.1 MB | 24.9 (just after its restart) to 67.1 MB |
| Bytes in the steady span, min / mean / max | 63.9 / 66.1 / 67.1 MB | 63.2 / 66.2 / 67.1 MB | **62.1 / 66.3 / 67.1 MB** |
| Entries | 63 to 208 | 76 to 199 | 69 to 200 |
| At the end: hits / misses (hit rate) | 7,956 / 16,624 (32.4 %) | 11,792 / 23,820 (33.1 %) | **8,400 / 665,402 (1.2 %)** |
| Engine view-shape fingerprints, end (cap 100,000) | 1,819 (since its restart) | 1,823 | **54,182**: 46,964 at t = 2,876, 51,471 at 3,596, 53,523 at 5,036, 54,182 at 7,196 |
| Delta memory budget, end: documents | 102 of 102 MB | 102 of 102 | 102 of 102 |

**The cache is bounded and the bound is now real heap.** In soak 3 a `cache-mb: 64` cache held 150 to 210 MB. In soak 4 each server's
cache reached its budget within two minutes and stayed between 62 and 67 MB for two hours, while live data was 220 to 800 MB. So the
history cache explains none of c's rise. Neither does the view-shape cache. c's fingerprints rose by only about 7,200 over the steady
span, a few MB at the documented ~30 MB per 100,000. The Delta budgets were full from the start.

**Why c and not a or b.** From a's kill on, the console used c for most of its traffic (9 of its 12 session moves went to c, and the
sessions did not move back when a returned). Its history-cache misses (665,402 against 16,624 and 23,820) and fingerprints (54,182 against
about 1,820) show it. Before its kill, a played this part with 51,739 fingerprints and its live data was flat (346 to 395 MB from 600 to 1,740 s). For c's
first four 10-minute blocks the floor of its samples stayed at 376 to 397 MB (medians 392 to 456). From about t = 5,150 s the floor
was 417 to 479 MB, the medians 529 to 623, and single samples reached 600 to 796 MB: +90 MB/h on the 10-minute minima, +155 on the
medians.

The live-data figure is updated only when a GC cycle that touches the old generation ends. That is why the same value often repeats over
consecutive samples. So a rising floor can be old-generation garbage that has not yet been collected, as well as retained objects. These
samples cannot tell the two apart. RSS is consistent with either: c 1,783 → 2,108 MB, its heap used 475 to 1,414 MB.

### Resident memory and the RSS-minus-Xmx gap (`-Xmx2g` = 2,048 MB)

| Process | Soak 1 end − Xmx | Soak 2 end − Xmx | Soak 3 end − Xmx | **Soak 4 RSS start → end (MB)** | **Soak 4 end − Xmx** |
|---|---:|---:|---:|---|---:|
| a, before its kill (t = 1,740) | +884 | +196 | +315 | 1,044 → **1,734** | **−314** |
| a, end (restarted) | −779 | −997 | −926 | 975 → **1,322** | **−726** |
| b | +986 | −355 | −136 | 1,021 → **1,964** | **−84** |
| c (restarted at 1,200) | +928 | +374 | +338 | 1,123 → **2,108** (peak, at the end) | **+60** |
| console (tree) | | 781 → 886 | 777 → 877 | 783 → **899** | |

Every server ends well inside PERFORMANCE.md's "about `-Xmx` plus 0.4 GB with two arenas": the highest is +60 MB (soak 3: +338).

### Threads and open files (after warm-up)

| Process | Threads 3 | **Threads 4** | Open files 3 | **Open files 4** |
|---|---|---|---|---|
| a | 134 to 152 | **133 to 159** (117 to 124 after its restart) | 137 to 165 | **133 to 162** |
| b | 111 to 124 | **112 to 135** | 74 to 140 | **77 to 151** |
| c | 117 to 155 | **116 to 156** | 127 to 167 | **127 to 168** |
| console (tree) | 23 | **23** | 137 to 149 | **136 to 150** |

All are flat, with steps only at the failovers. c's threads (149 to 156) and open files (146 to 168) show no trend over the span in which
its live data rose.

## 8. Server and console logs

| Server | WARN 1 | WARN 2 | WARN 3 | **WARN 4** | Stack-trace lines 1 / 2 / 3 / **4** | Log 3 | **Log 4** | ERROR |
|---|---:|---:|---:|---:|---|---:|---:|---|
| a | 26,697 | 57 | 11 | **13** | 491,248 / 0 / 0 / **0** | 44 KB | **42 KB** | 0 |
| b | 1,055 | 13 | 13 | **13** | 19,090 / 0 / 0 / **0** | 24 KB | **25 KB** | 0 |
| c | 47 | 13 | 1 | **3** | 0 / 0 / 0 / **0** | 36 KB | **38 KB** | 0 |
| ref | 12 | 4 | 1 | **1** | 0 / 0 / 0 / **0** | 20 KB | **19 KB** | 0 |

Every WARN line of soak 4:

| Server | Count | Logger | Message |
|---|---:|---|---|
| all four | 1 each | UserService | Created development admin 'drishti-dev-admin' with the default password |
| a | 2 | ApiExceptionHandler | single refusals at c's kill (21:53:08.148 and .150) |
| a | 2 | ApiExceptionHandler | `drishti-c (suspect)` with 118 more counted, then `(dead)` with 838 more counted |
| a | 5 | ApiExceptionHandler | `request refused N times in the last 60 s naming drishti-c (dead)`, N = 4,754, 5,091, 4,847, 4,854, 4,676 |
| a | 1 | ApiExceptionHandler | 21:58:26.744: `… cannot be reached now: drishti-c (rejoining)` with 328 more counted |
| a | 2 | Gossiper | c suspect and dead |
| b | 3 | ApiExceptionHandler | a single refusal at c's kill, then `(suspect)` and `(dead)` (4 and 34 more counted) |
| b | 5 | ApiExceptionHandler | once-a-minute summaries naming drishti-c (dead): 204, 214, 194, 183, 164 |
| b | 4 | Gossiper | c suspect and dead; a suspect and dead |
| c | 2 | Gossiper | a suspect and dead |

- There were no lines with `still joining`, `DRS-1001`, `not usable`, `RetryBoard`, `deprecated` or `DRS-6011` in any log.
- **Console.** There were 0 WARN lines and no traceback. It set aside the dead node 8 times: c 4 times on its health check, and a 4 times
  after 2 failed requests. It set aside no healthy server and moved sessions 12 times, all off a at its kill (9 to c, 3 to b).

## 9. Soak 3's findings, re-judged

| Finding | Status in soak 4 | Evidence |
|---|---|---|
| SOAK3-01 a node just rejoined answers 404 "no source holds" for peer-held entities | **fixed** | 0 wrong answers and 0 `404 DRS-1001` in 146,498 checks, including about 200 per rejoin inside the 30 s grace; the restarted nodes had their peers' digests 427 and 491 ms after joining; 0 "still joining" refusals were needed (section 5) |
| SOAK3-02 the memory check fails on a one-hour run (b +183.8, c +66.5) | **the cache part is fixed; the gate still fails, on c** | the history cache holds 62 to 67 MB at `cache-mb: 64` on every server (was 150 to 210 MB); b is +32.2 MB/h on 79 samples (was +183.8 on 4 to 9); c is +165.5 on 74 samples (SOAK4-01) |
| SOAK2-01 masks | **still fixed** | 0 errors in 33,164 back-to-back pairs, 16,656 of them viewer first and 7,180 of those peer-held |
| SOAK2-03 latency | **machine contention, confirmed again** | p99 0.34 to 0.48 s at a load average of about 12, with the arena setting on |

## 10. Findings

### SOAK4-01 · P2 · The memory check fails: c's live data rises +165.5 MB/h over the 73-minute steady span (limit 64)

- **Evidence.** Section 7. `soak4.json` `verdict.memoryFlat.perProcess.c`: `slopeMbPerHour` 165.5 on 74 steady samples (2,752.6 to
  7,143.2 s), whole run +156.3, RSS slope +265.8. In the first four 10-minute blocks of the span c's live-data minima were 376 to 397 MB
  (medians 392 to 456). In the last four, from about t = 5,150 s, they were 477, 417, 464 and 479 MB, the medians 567, 529, 623 and 571,
  and single samples reached 600 to 796 MB. The history cache
  held 62.1 to 67.1 MB throughout (`cache-mb: 64`), the view-shape fingerprints grew by about 7,200 entries (a few MB), the Delta budgets
  were full from the start, and threads and open files were flat. a (−6.4) and b (+32.2) pass on the same span. c is the server the
  console used for most of its traffic after a's kill: 665,402 history-cache misses against 16,624 and 23,820.
- **Steps.** The command in section 1, with both kills before 2,153 s. Read `verdict.memoryFlat` in `soak.json`, and
  `GET /api/v1/admin/caches` each minute.
- **Expected.** Live data flat once the caches are full, as a was while it carried the same role before its kill (346 to 395 MB, 19
  minutes) and as a and b were in this span.
- **Actual.** The live-data floor on the busiest node rises by about 100 MB in 73 minutes, mostly after t = 5,150 s, and the gate fails.
- **What it is not.** It is not the history cache (bounded at its budget, the SOAK3-02 fix holds), not the view-shape cache, not threads
  or open files, and not native memory (RSS ends 60 MB above `-Xmx`).
- **What it may be.** `jvm.gc.live.data.size` is updated only when an old-generation cycle ends. So the figure can include old-generation
  garbage that mixed collections have not yet freed on a heap 0.5 to 1.4 GB in use. Or something per request or per session on the
  console-facing node is retained. These samples cannot tell which.
- **Severity.** P2, and the release gate fails. There is no functional effect, and RSS is inside the documented envelope. **Next.**
  Repeat the run with `-Xlog:gc*` on the servers and a `jcmd <pid> GC.class_histogram` of the console-facing node every 10 minutes from
  t = 2,700 s. A real leak shows as one class growing; floating garbage drops after a full mark (`jcmd <pid> GC.run` at the end).

### SOAK4-02 · P3 · The history cache barely hits on the console-facing node (1.2 %)

- **Evidence.** c ended with 8,400 hits and 665,402 misses. a and b had 32 to 33 %. The 64 MiB budget holds 63 to 208 entries (about
  0.3 to 1 MB each), and the load asks many distinct (entity, range, `knownAt`) keys per signature, so entries are evicted before reuse.
- **Effect.** None on correctness. History latency is fine (p99 0.338 s). The cache spends memory and churns on the busiest node for
  almost no benefit under this load.
- **Severity.** P3, an observation for sizing. It is not a release blocker. A real desk re-reading a few histories would hit more. It
  is worth a note in HISTORY_QUERIES.md section 11 that `cache-mb` buys hits only when the working set of keys fits.

## 11. Housekeeping

- Only this soak's own processes were started and stopped: the three servers, the reference, the console and the load, each by the tool
  by PID. A read-only sampler of `/api/v1/admin/caches` and a load-average logger ran in the scratchpad and were stopped by PID. Nothing
  else heavy ran. The user's cluster (18480/17480/18791/18792), the server on 18795, `drishti-stack/run`, `data/stack` and the repository's
  `data/` were not touched.
- The work folder `~/drishti-scale-test/soak4-2026-10-20` (lakes, nodes, logs, `reference.json`) was removed after the run. The figures
  above come from its logs, `soak.json` and the cache samples, all read before removal. `soak.json` is committed as
  [soak4.json](soak4.json), 185 KB.
