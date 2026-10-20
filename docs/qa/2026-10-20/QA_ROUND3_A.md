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
# QA round 3, tester A: multi-worker console, Fix-4/Fix-5 re-tests (2026-10-20)

Persona: trader and risk analyst, plus platform operator. Develop at `bf0141e78` (every fix of rounds 1 and 2, and the settings
stamp of the multi-worker console).

## Stack

| Part | How |
|---|---|
| Lakes | `tools/build_demo_lakes.py --lake <scratch>/delta --connectors-dir <scratch>/conn` (66 tables, 60 business days 2026-07-27 to 2026-10-20, 799 s) |
| Server | 18610, `-Xmx4g`, security on, the seven packs, `DRISHTI_DEMO_ENABLED=false`, `DRISHTI_OFFLINE=true`, `DRISHTI_DATA_DIR`, `DRISHTI_DELTA_ROOT`, `DRISHTI_CONNECTORS_DIR` in the scratchpad |
| Console | 17610, `--server.workers=4` (the Windows default), sign-in on, `--backend.url=http://192.168.2.77:18610` |
| Users | `qaadmin` (admin), `trader1` (trader), `trader2` (viewer) |
| Clients | HTTP clients with their own cookie jar, one new connection per request (a browser spreads its pages over several connections, so over the workers; a single keep-alive connection always lands on one worker and hides every bug below); Playwright Chromium for the pages |

## Summary

| Severity | Count | Findings |
|---|---|---|
| P1 | 1 | QA3A-01 |
| P2 | 4 | QA3A-02, QA3A-03, QA3A-04, QA3A-05 |
| P3 | 3 | QA3A-06, QA3A-07, QA3A-08 |

## Findings

### QA3A-01 · P1 · server: a 60-day `--only` load into a running server drives it out of memory

**Steps** (reproduced twice, `trades/murex` and `trades/calypso`)
1. Server on the demo lakes as above (`-Xmx4g`), idle, healthy (`/actuator/health` in a few ms).
2. `DRISHTI_SERVER=http://192.168.2.77:18610 DRISHTI_TOKEN=<admin token> python tools/build_demo_lakes.py --only trades/murex --lake <scratch>/delta --connectors-dir <scratch>/conn`.
3. Watch the server while `lake demo` sends its 60 data-load signals (one per business date) after writing the table.

**Expected:** the server takes the new table (the summary says *a running server also re-reads a rebuilt table by itself*) and stays usable.

**Actual:** each signal clears the BI layers and starts a full `derived pages trade-pages (latest): reading every trade document in one pass`
(36 s to 200 s each, overlapping). Run 1: the signals took about 630 s on top of the 193 s build; 4 of 60 timed out; the server then sat at
about 1,600 % CPU for over five minutes after the last signal, old generation 99 %, 703 full GCs (760 s of GC), `OutOfMemoryError: Java heap
space` (also `derived pages trade-pages for live ran out of memory`), SQLite errors from the identity store (`cannot rollback - no transaction
is active`), health 5 s, sign-in through the console `503` after 5 s, the console logging `server … unreachable: ReadTimeout`. Run 2
(`trades/calypso`, fresh server): at 51 of 60 signals the same state, old generation 99.7 %, 340 full GCs, health 10 s. Both servers had to be
killed.

Heap histograms of both runs: **878,620 and 617,988 `java.lang.VirtualThread`** with 1.6 GB and 1.1 GB of `StackChunk`, 657,272
`TimeoutException`, 877,964 `CompletableFuture$AsyncSupply` (run 1). A thread dump (cut short by the OOM) shows the visible virtual threads
parked in `DeltaSourcePlugin.doc` on `slots.acquireUninterruptibly()` (DeltaSourcePlugin.java:629): work whose caller has timed out stays
queued for the document-read semaphore, and each new signal adds a full pass of all trades on top.

**Evidence:** server logs and histograms kept during the run (scratch, deleted); the lines above are copied from them.

### QA3A-02 · P2 · console: the pack switcher comes back as the old choice on most pages (4 workers)

**Steps** (reproduced twice, trader1 and trader2)
1. Sign in; open `/t` a dozen times (every worker now holds the user's packs).
2. Packs menu: show only two packs, *Apply* (`POST /api/packs` 200).
3. Open the next 20 pages.

**Expected:** every page shows the two packs, as a theme change now does.

**Actual:** run 1 (trader1, 7 → 2 packs): `[2, 7, 7, 2, 2, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7]`: 17 of 20 pages show all seven packs.
Run 2 (trader2, 7 → 2): `[7, 7, 2, 2, 2, 2, 2, 2, 2, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7]`: 13 of 20. Settles after 60 s. `core/packs.py` keeps
the user's packs per worker for 60 s and `routes/api_routes.py` `choose_packs` forgets only the saving worker's copy; the settings stamp of
bf0141e78 does not cover it (an administrator's pack assignment, `forget_all`, is the same).

### QA3A-03 · P2 · console: a saved or reset view layout flips back and forth for a minute (4 workers)

**Steps** (reproduced twice: save, then reset)
1. As `qaadmin`, open `/v/trade/1000001` eight times (Sutra `endur-comswap`, no own layout).
2. Save a layout (`PUT /api/layout/endur-comswap/trade`, the first panel hidden) as Layout mode's *Save* does: 200.
3. Open the view 20 times and look for `data-layout-mine`.
4. *Reset* (`DELETE /api/layout/endur-comswap/trade`): 200; open it 20 times again.

**Expected:** own layout on every page after the save; the Sutra's on every page after the reset.

**Actual:** after the save, 15 of 20 pages show the Sutra's layout; after the reset, 4 of 20 still show the saved one. `core/layouts.py`
(60 s, per worker; `forget` only on the saving worker). `core/pivots.py` (saved pivots, 60 s) has the same shape; not exercised.

### QA3A-04 · P2 · console: a user disabled from Admin keeps working for about 10 s on the same console

**Steps** (reproduced twice)
1. trader1 signed in, a dozen pages opened.
2. `qaadmin` on the same console: Admin → Users → trader1 → disable (`POST /admin/api/users/trader1/enabled {"enabled": false}`, 200).
3. trader1 keeps opening `/t`.

**Expected:** CONFIGURATION.md (`recheck_seconds`): *Disabling, demoting or signing out takes effect within this time, **at once on the
console an administrator used***; the route's docstring says the same.

**Actual:** run 1: 1,548 of 3,814 pages still served, the last 9.5 s after the disable; run 2: 1,321 of 5,106, last at 9.6 s. Only the worker
that served the administrator forgets the session check (`auth.forget(user=…)`); the other three keep theirs for `auth.recheck_seconds`.
The same for sign-out: a copy of the signed-out cookie was served 0 pages in one run and 972 pages for 9.9 s in the other, depending on which
worker took the sign-out. Within the documented 10 s window, but not "at once" as the page promises; a fix or a doc correction.

### QA3A-05 · P2 · tools: `build_demo_lakes.py` signals the server on localhost:18480 whatever lake it builds, and has no `--server`

**Steps:** `tools/build_demo_lakes.py --lake <scratch>/delta --connectors-dir <scratch>/conn` with no server of that lake running and
nothing set in `DRISHTI_SERVER`.

**Expected:** a build into another lake leaves other servers alone, or asks which server reads it.

**Actual:** `Server: http://localhost:18480 refused 3960 of 3960 data-load signals (401 … DRS-5010 missing bearer token)`: 3,960 requests to
the default port, where the user's own server was running on another lake (refused only because sign-in is on there; a server with sign-in
off would have taken them: BI layers cleared and derived pages rebuilt for a lake it does not read, as QA3A-01 shows). `build_demo_lakes.py`
has no `--server` or `--no-signal` to pass to `lake demo`; only the environment variable helps. Reproduced once by run; not repeated on
purpose so as not to touch the user's server again (the cause is `tools/lake/demo.py` `DEFAULT_SERVER`).

### QA3A-06 · P3 · the same user's second browser shows the old theme on some pages for 30 s

**Steps** (reproduced twice): trader1 signed in in browser A and browser B; A changes the theme; B opens 20 pages at once.

**Actual:** run 1 (crimson → blue): `blue ×3, crimson ×5, blue ×3, crimson ×9`; run 2 (blue → green): green on 9, blue on 11. The settings
stamp is per browser, so B carries none; each worker's 30-s copy decides. Browser A, the case of the fix, is right on 20 of 20 pages
(re-test below). Density the same (19 of 20 right in one run).

### QA3A-07 · P3 · `build_demo_lakes.py`: misleading heartbeat and summary while the server is signalled

- The heartbeat says `writing trading/trade_murex` with the lake's size flat at 1,786 MB for ten minutes, while the tool is only waiting on
  the server's data-load signals (QA3A-01); the closing line says `… 1 tables … 193 s` while the step took about 820 s.
- `refused 4 of 60 data-load signals (timed out; a token with loads:write: --token-file or DRISHTI_TOKEN)`: the token was given and
  accepted; four timed out. *Refused* and the token hint mislead.
- Reproduced in both runs of QA3A-01.

### QA3A-08 · P3 · `build_demo_lakes.py --files-only --system calypso` ends with ENDUR-only suggestions

After loading two synthetic CALYPSO files the closing lines read *if a server was running, restart it so it reads the new tables. Then try:
TRD 1000279 60 · …* (ENDUR ids). The running server had already re-read the replaced table without a restart (`CLY-3000301` 200, a removed
`CLY-3000157` 404 `DRS-1001`). Seen once (the closing lines are fixed text in `tools/build_demo_lakes.py`).

## Re-test verdicts

| Item | Verdict | Notes |
|---|---|---|
| Settings stamp (bf0141e78), same browser | **Pass** | theme twice (wallstreet, crimson) and density twice: 20 of 20 pages right each time with a new connection per page; another user untouched |
| Settings stamp, second browser of the same user | **Open** | QA3A-06 (P3) |
| Pins, saved searches | Not run as pages | the same `user_settings` path and stamp as the theme (code read) |
| History chart hover after resize (QA1-14) | **Pass** | hover at 90 % of a 1400 px chart, narrowed to 700 px: no horizontal scroll (twice) |
| History number formats (QA1-15) | **Pass** | EQ-CSCA 3M: 21.84, +2.46, +12.69 % (checked by hand), 3.85 %, 34.3bn |
| *Open the day* from history, back link (QA1-16) | **Pass** | twice: `?asOf=…&via=history`, *from its history* shown, *Back to the history* keeps `?days=60&diff=1` |
| Entity reads during a connector-file swap (QA2-202) | **Pass** | `/views/trade/1000001` hammered while `trade-endur.yaml` changed: 1,032 and 1,152 answers, all 200, slowest 3.1 s; no 404. DRS-1015 (a start over 30 s) not forced |
| `build_demo_lakes.py` full | **Pass** with QA3A-05 | 66 tables, 799 s, a progress line every 10 days per table and the 30-s heartbeat |
| `--only` | **Fail** | QA3A-01, QA3A-07 (the table itself is right: 273,821 rows over 60 days) |
| `--files-only --source-dir --system` | **Pass** with QA3A-08 | 2 synthetic files, 600 rows, 16 s; `--system 'x/../../..'` refused by argparse |
| 60-day demo data (QA2-201) | **Pass** | fx-spot 720 rows: MID inside BID/ASK on all, every pair moves; 32 bonds, 1,884 day-to-day moves: yield never moves with price; 600 equity rows: PRICE = the last OHLC close; trade 1000279: 10 amended days in 60 |

## Fresh ground

- **History at 375 px, all ten themes** (trade 60 days and EQ-CSCA 3M): no page-level horizontal scroll in any of the 20 cases (the table
  scrolls inside its frame), no script error. `img/qa3a-history-375-light.png`, `img/qa3a-eq-375-wallstreet.png`.
- **Keyboard on history:** `j j k End Home PageDown` move the selected row as the keys card says; `Enter` opens the day; `Alt+←` returns to
  the history. Netting set and SIMM by keys only: not reached (time).
- **Long session:** about 60,000 console requests over 45 minutes (the tests above) instead of 30 minutes of clicking: the four workers stayed
  at 100 to 103 MB each, no traceback in the console log. The server side failed (QA3A-01).
- **Two users:** trader2's theme never moved when trader1 changed his (40 of 40).

## Not covered

BI permissions and `bi_me` (30-s per-worker copy, same shape as QA3A-02, code read only), live views across workers (`core/channel_relay.py`),
server failover state (`core/cluster.py`: the pool and session pins are per worker, so one session may sit on different servers on different
workers after a failover; not run, it needs two servers), the business-date and as-of cookies (cookie-based, per browser by design),
command history and aliases, a password change.
