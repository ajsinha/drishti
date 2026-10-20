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
# Drishti*net two-hour soak of 2.6.5 (2026-10-20)

The two-hour soak on real processes that the exit criteria ask for ([QA_PLAN.md](QA_PLAN.md) section 8;
[DRISHTI_NET.md](../../architecture/DRISHTI_NET.md) section 17.1). The soak only records evidence. No product code was changed, and
`tools/bench` needed no fix: the tool ran to the end the first time. The raw result is [soak.json](soak.json).

**Verdict: FAIL.** Two of the four checks fail. Wrong answers: 32 history answers, all of them complete answers marked partial
(SOAK-01, and likely SOAK-02). Memory: the slopes of b and c fail because load moved between nodes at the failovers. The last hour is
flat (SOAK-04). Latency passes, and every error fell inside a kill window. No answer had wrong rows, wrong values or a leaked mask.

## 1. The run

| | |
|---|---|
| Command | `drishti-console/.venv/bin/python tools/bench/net_soak.py --work ~/drishti-scale-test/soak-2026-10-20 --users 20 --seconds 7200 --days 10 --kill-shard-at 1200 --restart-shard-at 1500 --kill-seed-at 3600 --restart-seed-at 3900 --history-share 0.15 --sample-every 60 --warmup 600 --max-growth 64 --heap 2g --base-port 18710 --console-port 17710` |
| Code | `develop` at `6f9555146`; server `drishti-server-2.6.5-exec.jar` built in the soak's worktree (`./mvnw -q -T 4 -DskipTests install`) |
| Runtimes | OpenJDK 21.0.12.1 (`-Xmx2g` per server), Python 3.13.15 (the console's venv) |
| Machine | AMD Ryzen AI 9 HX 370, 24 logical CPUs, 60 GB RAM, Linux 7.1.5. Other test consoles were running on the machine (load average about 11 at the start). |
| Ports | servers a, b, c = 18710, 18711, 18712; the reference server 18713; console 17710. None of the user's ports were used. |
| Addresses | every member advertised `http://192.168.2.77:<port>` (this machine's real address, from `tools/hostaddr.py`), never a loopback address |
| Data | synthetic, `tools/bench/trades.py`: 1,500 trades a day per system (ENDUR, CALYPSO, MUREX: 4,500 distinct trades), 300 counterparties, 10 business days from 2026-10-02 to 2026-10-16. Lake `shared` (ENDUR and CALYPSO) is read by a and b; lake `murex` is read by c alone; lake `all` is read by the reference server. 177 MB in all. |
| Duration | 15:35:48Z to 17:43:30Z (2 h 08 min). Lakes and the reference (2,055 requests, all 200) took 407 s, the cluster came up in 38.3 s, the console learned all three servers in 1.9 s, then the load ran for 7,200 s. |
| Load | `users.py`, 20 users on the console: 1,491,085 requests (207.1 a second) |
| Checker | 118,166 checked answers, each compared with the reference server holding all the data |

## 2. Verdict

| Check | Result | Numbers |
|---|---|---|
| No wrong answers | **fail** | 32 wrong, all `history`. The 5 kept samples are complete answers marked partial (section 4). |
| Errors only inside kill windows | pass | 0 errors outside the windows c 1200.0 to 1552.2 s and a 3600.0 to 3952.0 s |
| Latency p95 and p99 per action | pass | every p99 is at most 0.374 s (`--max-p99` not set, so this is a report only) |
| Memory flat after warm-up | **fail** | a −21.7, b **+166.4**, c **+185.3** MB/h of live heap (limit 64). These are steps when load moved, not a slope; the last hour is flat (section 6). |

## 3. Latency and errors

The load, per action, through the console's pages:

| Action | Requests | Failed | p50 s | p95 s | p99 s |
|---|---:|---:|---:|---:|---:|
| trade | 1,014,506 | 22,007 | 0.072 | 0.208 | 0.304 |
| ctrd (counterparty trades) | 127,036 | 0 | 0.075 | 0.216 | 0.316 |
| netting-set | 63,087 | 0 | 0.091 | 0.214 | 0.303 |
| search | 63,194 | 0 | 0.075 | 0.255 | 0.374 |
| **history** | **223,262** | **3,841** | **0.103** | **0.236** | **0.328** |

Every load failure fell in the 10-second buckets from the one holding c's kill to the one ending at about t = 1521 s, when c answered
again. There were none in the seed's window and none anywhere else.

Failures were 47 % of trade pages and 37 % of history pages at the run's average rate, while MUREX (the only peer-only data) is 33 % of
trades and 27 % of histories. Both overshoot by the same factor, about 1.4. That fits throughput rising inside the window, since a MUREX
refusal comes back fast. It is not a sign of extra failures.

The checker, per kind of request (right includes 335 `right-links-pending` trades; unavailable includes 247 "asked a node going down"):

| Checked | Right | Partial | Timeout | Unavailable | Wrong |
|---|---:|---:|---:|---:|---:|
| trade | 101,566 | 1,633 | 0 | 224 | 0 |
| search | 10,979 | 531 | 0 | 20 | 0 |
| ctrd | 1,049 | 62 | 0 | 1 | 0 |
| **history** | **2,046** | **20** | **0** | **3** | **32** |
| **All** | **115,640** | **2,246** | **0** | **248** | **32** |

The console's own page check (one page a second for the whole run): 6,496 good, 0 failed, 0 signed out.

## 4. The wrong answers

The soak keeps five samples per kind (SOAK-08). These are the five, verbatim from `soak.json` (`got` and `ref` are left out because
they are equal: same masked columns, window, baseline and all 10 rows):

```json
{"t": 1212.4, "node": "a", "q": {"what": "history", "kind": "trade", "id": "CLY-30001298", "params": {"range": "3M"}, "role": "viewer", "date": "2026-10-16", "refStatus": 200}, "status": 200}
{"body": "{\"ref\": {\"kind\": \"trade\", \"id\": \"CLY-30001298\"}, \"mnemonic\": \"TRD\", \"title\": \"VALIDATED \\u00b7 845,600,000\", \"shape\": \"document\", \"basis\": \"documents\", \"window\": {\"asked\": \"3M\", \"from\": \"2026-07-17\", \"to\": \"2026-10-16\", \"days\": 64, \"mode\": \"calendar\", \"baseline\": null, \"missing\": [], \"knownAt\": null, \"loadedTo\": null, \"loadedFrom\": \"2026-10-02\"}, \"columns\": [{\"path\": \"TRADE_STATUS\", \"label\": \"Trade status\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"TRADE_ACTION\", \"label\": \"Trade action\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": false}, {\"path\": \"TR"}
```
```json
{"t": 1230.2, "node": "b", "q": {"what": "history", "kind": "counterparty", "id": "CP-00089", "params": {"range": "3M"}, "role": "admin", "date": "2026-10-16", "refStatus": 200}, "status": 200}
{"body": "{\"ref\": {\"kind\": \"counterparty\", \"id\": \"CP-00089\"}, \"mnemonic\": \"CPTY\", \"title\": \"Counterparty 00089 \\u00b7 A\", \"shape\": \"document\", \"basis\": \"documents\", \"window\": {\"asked\": \"3M\", \"from\": \"2026-07-17\", \"to\": \"2026-10-16\", \"days\": 64, \"mode\": \"calendar\", \"baseline\": null, \"missing\": [], \"knownAt\": null, \"loadedTo\": null, \"loadedFrom\": \"2026-10-02\"}, \"columns\": [{\"path\": \"NAME\", \"label\": \"Name\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"RATING\", \"label\": \"Rating\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"SECTOR\", \"label\": \"Sector\", "}
```
```json
{"t": 1273.2, "node": "b", "q": {"what": "history", "kind": "counterparty", "id": "CP-00089", "params": {"range": "3M"}, "role": "admin", "date": "2026-10-16", "refStatus": 200}, "status": 200}
{"body": "{\"ref\": {\"kind\": \"counterparty\", \"id\": \"CP-00089\"}, \"mnemonic\": \"CPTY\", \"title\": \"Counterparty 00089 \\u00b7 A\", \"shape\": \"document\", \"basis\": \"documents\", \"window\": {\"asked\": \"3M\", \"from\": \"2026-07-17\", \"to\": \"2026-10-16\", \"days\": 64, \"mode\": \"calendar\", \"baseline\": null, \"missing\": [], \"knownAt\": null, \"loadedTo\": null, \"loadedFrom\": \"2026-10-02\"}, \"columns\": [{\"path\": \"NAME\", \"label\": \"Name\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"RATING\", \"label\": \"Rating\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"SECTOR\", \"label\": \"Sector\", "}
```
```json
{"t": 1303.8, "node": "b", "q": {"what": "history", "kind": "trade", "id": "10001306", "params": {"range": "3M"}, "role": "viewer", "date": "2026-10-16", "refStatus": 200}, "status": 200}
{"body": "{\"ref\": {\"kind\": \"trade\", \"id\": \"10001306\"}, \"mnemonic\": \"TRD\", \"title\": \"MATURED \\u00b7 600\", \"shape\": \"document\", \"basis\": \"documents\", \"window\": {\"asked\": \"3M\", \"from\": \"2026-07-17\", \"to\": \"2026-10-16\", \"days\": 64, \"mode\": \"calendar\", \"baseline\": null, \"missing\": [], \"knownAt\": null, \"loadedTo\": null, \"loadedFrom\": \"2026-10-02\"}, \"columns\": [{\"path\": \"TRADE_STATUS\", \"label\": \"Trade status\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"TRADE_ACTION\", \"label\": \"Trade action\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": false}, {\"path\": \"TRADE_VERSION\", "}
```
```json
{"t": 1315.2, "node": "b", "q": {"what": "history", "kind": "trade", "id": "CLY-30001298", "params": {"range": "3M"}, "role": "viewer", "date": "2026-10-16", "refStatus": 200}, "status": 200}
{"body": "{\"ref\": {\"kind\": \"trade\", \"id\": \"CLY-30001298\"}, \"mnemonic\": \"TRD\", \"title\": \"VALIDATED \\u00b7 845,600,000\", \"shape\": \"document\", \"basis\": \"documents\", \"window\": {\"asked\": \"3M\", \"from\": \"2026-07-17\", \"to\": \"2026-10-16\", \"days\": 64, \"mode\": \"calendar\", \"baseline\": null, \"missing\": [], \"knownAt\": null, \"loadedTo\": null, \"loadedFrom\": \"2026-10-02\"}, \"columns\": [{\"path\": \"TRADE_STATUS\", \"label\": \"Trade status\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": true}, {\"path\": \"TRADE_ACTION\", \"label\": \"Trade action\", \"type\": \"text\", \"format\": null, \"masked\": false, \"key\": false}, {\"path\": \"TR"}
```

All five are `range=3M` histories of data that a and b hold themselves (CALYPSO and ENDUR trades, a counterparty). All were asked
inside c's kill window (t = 1212 to 1315 s) and all got status 200 with rows equal to the reference. The sample body is cut at 600
characters before `partial` and `problems`. Everything else in the essence matches, so the checker ruled them wrong for being marked
partial with nothing missing.

A re-run on the soak's own nodes after the run reproduced this every time (SOAK-01).

## 5. The kill windows

| | Shard c (MUREX holder) | Seed a (the console's server) |
|---|---|---|
| Killed (SIGKILL by PID) | t = 1200.5 s | t = 3600.3 s |
| Seen suspect or dead by every live member | 3.6 s | 3.1 s |
| Console: next good page (a CALYPSO trade) | 0.02 s | 0.05 s |
| Console: CALYPSO search right after | 200, rows present | 200, rows present |
| Load failures in the window | trade 22,007, history 3,841: MUREX pages and histories refused (`DRS-5052`) | **none** |
| Checker in the window | the 32 wrong histories are most likely all here (5 of them sampled at t = 1212 to 1315 s); partial and unavailable answers named c | unavailable only where the checker asked a just as it died |
| Console | healthy a and b set aside again and again on `HTTP 502` (43 such lines); 4 session moves on `HTTP 502` (a → b three times, b → a once), see SOAK-02 | 10 session moves off a (`ReadError`, `ConnectError`): 4 to b, 6 to c, across the console's workers (`soak.json` `consoleFailovers`, one worker's view, lists 2). Sessions stayed there: a recovered server is used only for new sessions, as documented. |
| Restarted | t = 1500. Healthy after 22.1 s, all three alive after 22.2 s. It joined the net 13.9 s before its port opened (SOAK-03). | t = 3900, with no seed, from `peers.json` (2 saved members). Healthy and all alive after 21.2 s. It joined 12.4 s before its port opened. |
| Recovery | load failures stop in the bucket ending about t = 1521 s, when c answered | nothing to recover: no failures |
| Window closes | 1552.2 s (rejoined + 30 s grace) | 3952.0 s |

## 6. Memory, threads and open files

The verdict's slope covers live data after the last collection, from 600 s after each process's last start to the end. The verdict
column below is that slope; the last hour is t = 4200 to 7200 s:

| Process | Verdict slope (MB/h) | Live, last hour (MB/h) | Live, last 30 min (MB/h) | RSS, last hour (MB/h) |
|---|---:|---:|---:|---:|
| a (restarted at 3900) | −21.7 | −9.2 | −29.6 | +102.3 |
| b | **+166.4** | +7.0 | +6.1 | +140.7 |
| c (restarted at 1500) | **+185.3** | +4.9 | −25.6 | +256.9 |
| console | 0.0 (not measured, SOAK-07) | | | |

Samples (MB; threads from `/proc` and from the JVM; open files from `/proc`):

| Process | t s | RSS | Heap used | Live after GC | Threads | JVM threads | Open files |
|---|---:|---:|---:|---:|---:|---:|---:|
| a | 0 (start) | 1,114 | 264 | 110 | 100 | 62 | 97 |
| a | 600 (after warm-up) | 2,667 | 894 | 473 | 142 | 106 | 181 |
| a | 3,542 (before its kill) | 2,932 | 585 | 489 | 143 | 107 | 172 |
| a | 4,562 (restart + warm-up) | 1,189 | 352 | 194 | 120 | 84 | 166 |
| a | 7,143 (end) | 1,269 | 214 | 186 | 123 | 87 | 159 |
| b | 0 (start) | 1,133 | 149 | 108 | 90 | 52 | 78 |
| b | 600 (after warm-up) | 1,306 | 270 | 123 | 118 | 82 | 93 |
| b | 1,501 | 1,374 | 172 | 128 | 114 | 78 | 83 |
| b | 1,801 (the console moved sessions to b) | 2,604 | 1,125 | 367 | 140 | 104 | 124 |
| b | 3,602 | 2,888 | 892 | 479 | 141 | 105 | 148 |
| b | 7,143 (end) | 3,034 | 1,221 | 475 | 143 | 107 | 150 |
| c | 0 (start) | 1,062 | 213 | 0 | 97 | 59 | 89 |
| c | 600 (after warm-up) | 1,837 | 784 | 334 | 122 | 86 | 168 |
| c | 2,161 (restart + warm-up) | 1,657 | 568 | 344 | 127 | 91 | 173 |
| c | 3,602 (a killed) | 2,032 | 654 | 338 | 139 | 95 | 172 |
| c | 4,202 | 2,784 | 882 | 541 | 151 | 115 | 182 |
| c | 7,143 (end) | 2,976 | 1,024 | 524 | 153 | 117 | 179 |
| console | 0, 600, 7,143 | 50.7 | | | 1 | | 21 |

Reading:

- **Live heap.** It rises in steps when a node takes over work: b +240 MB between t = 1500 and 1800, after the console set a aside
  during c's window; c +200 MB when a was killed. It then holds level: b 442 to 495 MB over the last hour, c 510 to 560, a 182 to 199.
  The failing slopes come from these steps falling inside the measured window, not from steady growth (SOAK-04).
- **Resident memory** keeps rising slowly on every server while the live heap is flat, about 100 to 130 MB an hour over the last half
  hour, up to 3.0 GB at `-Xmx2g` (SOAK-05).
- **Threads** are flat after warm-up: a 141 to 153 (120 to 125 after its restart), b 139 to 144, c 146 to 154. JVM live threads track
  them. No growth.
- **Open files** are flat: a 158 to 181, b 148 to 166, c 155 to 200. No growth.

## 7. Server and console logs

No `ERROR` line in any server log. The `WARN` lines, grouped by message (ids, dates and paths folded), top 10 for a; b and c are
summarised:

| Server | WARN | Stack-trace lines | Log size |
|---|---:|---:|---:|
| a | 26,697 | 491,248 | 68 MB |
| b | 1,055 | 19,090 | 2.6 MB |
| c | 47 | 0 | 67 KB |
| ref | 12 | 0 | 25 KB |

| Count | Logger | Message (server a) |
|---:|---|---|
| 21,391 | ApiExceptionHandler | request failed: DRS-5052 no source here holds trade/<id>, and the nodes that hold trade cannot be reached now: drishti-c (dead); try again in a moment |
| 3,614 | ApiExceptionHandler | request failed: DRS-5052 no node that answered holds the history of trade/<id>, and the nodes that hold trade cannot be reached now: drishti-c (dead) … |
| 758 | ApiExceptionHandler | request failed: DRS-5052 no source here holds trade/<id> … drishti-c (suspect) … |
| 653 | ApiExceptionHandler | request failed: DRS-5052 the node drishti-c that holds trade failed reading trade/<id> (first at 12:03:16, the kill was at 12:03:15) |
| 127 | ApiExceptionHandler | request failed: **DRS-1003** net:drishti-c failed reading the history of trade/<id>: ConnectException (first at 12:03:16) |
| 110 | ApiExceptionHandler | request failed: DRS-5052 no node that answered holds the history of trade/<id> … drishti-c (suspect) … |
| 3 | ApiExceptionHandler | request failed: DRS-5052 no source here holds trade/<id> … drishti-c (**alive**) … (12:08:24, c had joined but its port was not open) |
| 2 | ConnectorManager | deprecated: connector 'trade-murex' is defined in the server's own configuration … (the soak's `--drishti.sources.connectors.*.enabled=false` flags) |
| 2 | MaskCoverage | DRS-6011 the field mask 'trader' (drishti.security.redact) names no field of the loaded packs' samples … |
| 2 | MaskCoverage | DRS-6011 the field mask 'patientName' … |

- **b** has the same shapes: 774 `DRS-5052 … (dead)`, 122 `DRS-5052 the node drishti-c … failed reading`, 107 `DRS-1003 … ConnectException`,
  28 `(suspect)`, 11 history `(dead)`, plus start-up warnings.
- **c** and **ref** carry only start-up warnings: the development administrator; three `DRS-6011` masks; `deprecated` connectors; and
  `RetryBoard` retries of `collateral-store`, `legal-store`, `market-store` and `risk-store`, whose tables the synthetic lake does not
  hold (SOAK-10).
- **Console.** 55 `server … set aside` lines: 43 `HTTP 502` against the healthy a and b, and 12 against the node that was down
  (`ConnectError`, `ReadError`, a failed health check). Also 51 `answers again; it is used for new sessions after 60 s healthy` lines and 14
  `console moved` lines: 4 on `HTTP 502` in c's window, 10 at a's kill. No traceback.

## 8. Re-checks after the run (on the soak's own nodes, synthetic data)

The four probes below re-started a, b and c on the soak's node folders and lakes, killed c by PID, and asked the servers directly. The
soak's logs had been set aside first, so they hold only the soak itself.

1. **The wrong answers.** With all three up, `GET /api/v1/history/trade/~?id=CLY-30001298&range=3M` (and the same for `10001306` and
   counterparty `CP-00089`) answers `partial: false`, 10 rows, window `2026-07-17` to `2026-10-16`, `loadedFrom: 2026-10-02`. With c
   killed (6 s and 36 s later), a and b answer the same 10 rows with `partial: true` and this problem:
   `{"code": "DRS-5052", "source": "net", "days": ["2026-07-17", …, "2026-10-01"], "message": "the nodes that hold trade cannot be reached now: drishti-c (dead): 54 days of the window may be held there"}`.
   `days=30`, `days=60` and `days=30&diff=true` (data-day windows) stay `partial: false`. The 54 days are all before 2026-10-02, the
   first day of any lake.
2. **The 502s that are not DRS-505x.** Under 20 threads of load on a and b, c killed: views of MUREX trades answer `502 DRS-5052`
   (exempted by the console). In the first 5 s, histories of MUREX trades answer `502 DRS-1003` (472 of them) or `200` partial with a
   `DRS-1003` problem (175):
   `{"title":"source failed","status":502,"detail":"DRS-1003 net:drishti-c failed reading the history of trade/MX-50001144: ConnectException","code":"DRS-1003"}`.
3. **The restart.** In the same load with c restarted after 60 s, 5,902 history answers were `502 DRS-1003 … ConnectException` from
   10 to 30 s after the restart. c's log: `joined Drishti*net` at 13:57:59.7, `Tomcat started on port 18712` at 13:58:18.4.
4. Sequential console pages with c down (`/v/trade/MX-…`, `/v/trade/MX-…/history?…`, ENDUR pages, counterparty-trades, netting set,
   trading form): MUREX pages `502`, the others `200`. No set-aside without the load's concurrency at the moment of failure.

## 9. Findings

### SOAK-01 · P2 · A complete history with a calendar range is marked partial while a node is down

- **Evidence.** All 5 sampled wrong answers in section 4; re-check 1 in section 8 reproduces it every time.
- **Steps.** Three-node cluster as in the soak. Kill c, the MUREX holder, which holds no CALYPSO or ENDUR data. Ask a or b for
  `GET /api/v1/history/trade/~?id=CLY-30001298&range=3M`, or for any history whose calendar window starts before the cluster's first
  loaded day.
- **Expected.** The same answer as with c up: complete, `partial: false`. Every row is present, and no node holds days before
  2026-10-02. Alternatively, a node that is down while its last known coverage cannot hold the days asked is not named.
  [DRISHTI_NET.md](../../architecture/DRISHTI_NET.md) 17.1 says "one marked partial with nothing missing … [is] wrong".
- **Actual.** `200`, all 10 rows right, but `partial: true` with
  `DRS-5052 … drishti-c (dead): 54 days of the window may be held there`, naming the 54 business days 2026-07-17 to 2026-10-01. The
  page shows a complete history as partial. It fails the soak's *no wrong answers* check (32 wrong answers, at most 5 of them sampled).
- **Severity.** P2: the rows and values are right, but the warning is false. It is a wrong answer by the soak's rules.

### SOAK-02 · P2 · History through a peer that just died answers DRS-1003, so the console sets aside healthy servers

- **Evidence.** Section 7 (127 + 107 `DRS-1003 … ConnectException` on a and b); console log (43 `set aside … HTTP 502` against healthy
  servers, 4 session moves a → b and b → a during c's window); re-checks 2 and 3 in section 8.
- **Steps.** Load on a and b. SIGKILL c. Before c is suspect (about 3 to 5 s), or while it is restarting (SOAK-03), ask a history of
  a trade only c holds.
- **Expected.** As for views (`502 DRS-5052 the node drishti-c that holds trade failed reading …`): a refusal or partial carrying
  `DRS-5052` and naming the node. The console's `peer_problem` (`drishti-console/core/backend.py`) exempts only `DRS-505x`.
- **Actual.** `502 DRS-1003 net:drishti-c failed reading the history of trade/MX-…: ConnectException`, or `200` partial with a
  `DRS-1003` problem. The console counts it against the healthy server that answered: after 2 in a row it sets the server aside and
  moves sessions, which explains the load moving between nodes (SOAK-04). The checker's rules call a partial with a code other than
  `DRS-5052` or `DRS-1014` wrong, so some of the 27 unsampled wrong answers may be this.
- **Severity.** P2: degraded fail-over behaviour, and the wrong code.

### SOAK-03 · P2 · A restarting node announces itself to the net 12 to 19 s before it accepts connections

- **Evidence.** Soak c: `joined Drishti*net` at 12:08:23.9, `Tomcat started` at 12:08:37.7 (13.9 s). Soak a: 12:48:25.2 and
  12:48:37.6 (12.4 s). Re-check 3: 18.7 s, with 5,902 history `502 DRS-1003 ConnectException` in the gap and 3
  `DRS-5052 … drishti-c (alive)` refusals in the soak.
- **Steps.** Restart a node of a running cluster under load. Compare its `net: node … joined` line with `Tomcat started`, and watch
  the peers' answers for data only it holds.
- **Expected.** The node joins, or peers treat it as alive, only once its web server answers, for example on `ApplicationReadyEvent`
  or after a successful probe. Until then, peers keep answering `DRS-5052` naming it.
- **Actual.** Peers route to it as soon as it gossips, and get `ConnectException`.
- **Severity.** P2.

### SOAK-04 · P3 · The memory check fails on load moving between nodes, not on growth (tool)

- **Evidence.** Section 6. b's live heap stepped from 128 to 367 MB at t = 1500 to 1800, when the console moved sessions to b; c's
  stepped from 338 to 541 MB when a died. The last hour's live slopes are b +7.0, c +4.9 and a −9.2 MB/h.
- **Expected.** A check that tells a leak from a node taking more work. For example, measure from `--warmup` after the last kill window
  or the node's last restart, whichever is later, or run longer.
- **Actual.** b +166.4 and c +185.3 MB/h, so the check fails.
- **Severity.** P3 (`tools/bench/soak_check.py memory_trend`). On this evidence no leak is shown.

### SOAK-05 · P3 (watch) · Resident memory creeps while the live heap is flat

- **Evidence.** Over the last half hour, RSS rose at about 100 to 130 MB/h on every server: b 2,972 → 3,034, c 2,925 → 2,976 and
  a 1,209 → 1,269 MB from t = 5,400 to 7,143 s. Live heap was flat over the same time. RSS reached 3.0 GB at `-Xmx2g`.
- **Expected.** RSS levels off once the heap is committed.
- **Actual.** Still rising slowly at the end of two hours. Most likely G1 committing and touching more heap regions; a native or
  off-heap leak is not ruled out.
- **Next.** A longer run with `-XX:NativeMemoryTracking=summary` and `jcmd VM.native_memory` at start and end. If the growth is native,
  this becomes P1.

### SOAK-06 · P3 · Every expected peer-down refusal is logged at WARN with a full stack trace

- **Evidence.** Server a: 26,697 WARN lines carrying 491,248 stack-trace lines, 68 MB in c's six-minute window (about 11 MB a minute).
  Nearly all are `DRS-5052 … cannot reach now`. The re-checks of section 8, about four minutes of 20-thread load with c down in all,
  grew a's and b's logs to 511 MB and 513 MB.
- **Expected.** One line without a stack trace, or a rate-limited summary, for a documented and expected answer.
- **Actual.** The disk fills at about 11 MB a minute per server while a node is down.

### SOAK-07 · P3 · The soak does not measure the console's memory (tool)

- **Evidence.** The console's samples are the uvicorn parent process (1 thread, 50.7 MB and 21 open files on every one of 120 samples).
  The workers (two processes) are not sampled.
- **Expected.** The workers' resident memory, threads and open files, summed or listed one per worker.
- **Actual.** The console's slope of 0.0 MB/h says nothing about the console.

### SOAK-08 · P3 · Wrong answers are under-recorded (tool)

- **Evidence.** 32 wrong answers, only 5 kept, with no time for the other 27. The kept body is cut at 600 characters, before `partial`
  and `problems`, which are what made them wrong.
- **Expected.** For every wrong answer: the time, node, request and the checker's reason (the rule that failed), plus `partial` and
  `problems` in each sample.
- **Actual.** The cause had to be reproduced after the run (section 8).

### SOAK-09 · P3 · The soak's mask check covers almost nothing (coverage)

- **Evidence.** The checked set holds only 35 distinct history requests (`--sample 60`: 10 trades per system and 5 counterparties).
  None of the 15 viewer trade histories has a masked column: the synthetic trades carry no field that `drishti.security.redact` names,
  and every server warns `DRS-6011 the field mask 'trader' … masks nothing`. Only 2 viewer counterparty histories carry masks.
- **Expected.** Viewer trade histories with at least one masked field (for example `TRADER_ID` in the synthetic trades and the mask
  list), so that "never shown as changed" and "the same on every row" are exercised.
- **Actual.** The mask rules passed, but almost vacuously.

### SOAK-10 · P3 · Retry warnings for stores the lake does not hold (noise)

- **Evidence.** On c and ref, `RetryBoard: connector 'collateral-store' | 'legal-store' | 'market-store' | 'risk-store' failed
  (attempt n): connection lost: DOWN: cannot reach <lake>/…`, about 8 attempts each, then quiet. Also `deprecated: connector 'trade-…'
  is defined in the server's own configuration`, caused by the soak's own `--drishti.sources.connectors.*.enabled=false` flags.
- **Expected.** A pack store with no table in the lake is reported once (Health), not retried with WARN. The soak switches connectors
  off the documented way.
- **Actual.** Start-up noise, which makes real warnings harder to see. The `DRS-6011` part is already on the Wave E backlog.

## 10. Housekeeping

- Only the soak's own processes were started and stopped, each by PID: three servers, the reference server, the console and the load.
  The re-checks did the same. The user's cluster, ports, `drishti-stack/run`, `data/stack` and the repository's `data/` were not touched.
- The work folder `~/drishti-scale-test/soak-2026-10-20` was removed after the run, except the report files (`soak.json`,
  `soak-report.txt`, `run.log` and the re-check outputs). The lakes, node folders and logs (about 300 MB) are gone. The counts above come
  from those logs before removal.
