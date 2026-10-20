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
# QA-1 findings: trader and risk analyst (2.6.5, round 1, 2026-10-20)

Tester QA-1. Scope: [QA_PLAN.md](QA_PLAN.md) sections 2, 3, 4.1 (H-1 to H-15), 4.2 and 4.3. Round 1 ran from 08:41 to about 09:20
local time.

## The stack

| Part | What |
|---|---|
| Version | `develop` at `20c236eb0` (2.6.5 and the QA plan). `./mvnw -q -T 4 -DskipTests install`, `drishti-server-2.6.5-exec.jar` |
| Node `qa1-a` | server on 18610, `-Xmx4g`, the seven packs, the demo lakes from `drishti.py lake demo --lake <scratch>/n1/data/delta --connectors-dir <scratch>/n1/connectors` (2026-10-09 to 2026-10-20) |
| Node `qa1-b` | server on 18611, `-Xmx3g`, the `trading` pack only, `trade-murex` off, a 60-day lake from `tools/bench/trades.py --days 60 --min 1500 --max 1500 --systems endur,calypso --counterparties 300` (2026-07-23 to 2026-10-16), then two more days loaded during H-14 (2026-10-19, 2026-10-20) |
| Console | 17610, given `http://192.168.2.77:18610` only |
| Drishti*net | both nodes `enabled: true`, a shared `DRISHTI_NET_SECRET`, `advertise-url` on the real address `192.168.2.77` (`tools/hostaddr.py`), qa1-b seeded with qa1-a; gossip 1 s / 3 s / 10 s |
| Security | on (`DRISHTI_SECURITY_ENABLED`, `DRISHTI_AUTH_ENABLED`), `drishti.security.redact` = the default list plus `MTM` |
| Users | `drishti-dev-admin`, `qa-admin`, `qa-admin2` (admin); `rita` (role `qa-trader`: every kind, `raw: false`; packs core, trading, market-data); `mona` (role `qa-trader`; packs core, trading, market-data, market-risk) |

Requests below use `$B=http://127.0.0.1:18610/api/v1` (node qa1-a) unless they name 18611 (node qa1-b), with an HS256 token of the
user (signed with the stack's `DRISHTI_TOKEN_SECRET`, as the console mints them). Console steps sign in on `http://192.168.2.77:17610`.

## Summary

| Severity | Count | Ids |
|---|---|---|
| P1 | 2 | QA1-01, QA1-02 |
| P2 | 8 | QA1-03 to QA1-10 |
| P3 | 9 | QA1-11 to QA1-19 |

---

## P1

### QA1-01 · P1 · Drishti*net, history cache: history read through a peer never shows the peer's new days

**Steps**
1. Two nodes as above. Trade `10000005` is held only by qa1-b.
2. `GET $B/history/trade/10000005?days=5` (on qa1-a). The last row is 2026-10-16. `cache: miss`.
3. Load one more day into qa1-b's lake: `tools/bench/trades.py --lake <qa1-b lake> --tmp <tmp> --days 61 --end 2026-10-19 --start 2026-10-19
   --min 1500 --max 1500 --systems endur,calypso --counterparties 300` (12 s).
4. `GET http://127.0.0.1:18611/api/v1/history/trade/10000005?days=5`: the last row is 2026-10-19 (`dataVersion` `trade-endur=60@…`).
5. `GET $B/history/trade/10000005?days=5` again, and again minutes later.

**Expected:** the newer day appears after the load (QA_PLAN H-14; HISTORY_QUERIES.md section 11: "a new load changes the version, so the
cache never serves stale data").
**Actual:** qa1-a keeps answering 2026-10-12 … 2026-10-16, `cache: hit`, with `dataVersion`
`trade-endur=34@…|trade-calypso=356@…|trade-murex=517@…`, which is qa1-a's **own** demo lakes' version, not qa1-b's. At the same moment
qa1-a's today view of the same trade (`GET $B/views/trade/10000005`) shows the 2026-10-19 data, so the history page and the view disagree.
A trade asked for the first time after the load (`10000006`) shows the new day; the one asked before never does. Still stale after a
second load (2026-10-20), after qa1-b was stopped and started again (09:10), and at 09:15 (about 13 minutes after the load).
**Reproduced:** three times (trade 10000005 after the 2026-10-19 load; trade 10000007 after the 2026-10-20 load; 10000005 again after
restarting qa1-b). The console shows the same stale rows (`/v/trade/10000005/history?days=5`: *2026-10-09 → 2026-10-16*).
**Evidence:** [img/qa1-stale-history.png](img/qa1-stale-history.png); poll log during the load:

```text
09:01:35 (18611, '2026-10-16', 5, 'trade-endur=59@…|trade-calypso=59@…') miss
09:01:35 (18610, '2026-10-16', 5, 'trade-endur=34@…|trade-calypso=356@…') miss
09:01:43 (18611, '2026-10-19', 5, 'trade-endur=60@…|trade-calypso=59@…') miss
          (18610 never changes: last row 2026-10-16, cache hit)
```

**Doc:** HISTORY_QUERIES.md sections 11 and 12; NET.md (history of an entity held on a peer).

### QA1-02 · P1 · Drishti*net, history: a peer that does not answer makes the asking node say "no data", and that answer sticks

**Steps**
1. Two nodes as above. Freeze qa1-b (`kill -STOP <its pid>`; the same happens when it is stopped with `kill -TERM`).
2. `GET $B/history/trade/10000901?days=10`: `502 DRS-1003 net:qa1-b failed reading the history of trade/10000901: request timed out`
   after 10 s (acceptable).
3. `GET $B/history/trade/10000902?days=10` (qa1-b is now *suspect*).
4. Bring qa1-b back (`kill -CONT`), wait 25 s. `GET http://127.0.0.1:18611/api/v1/history/trade/10000902?days=10` answers 10 rows.
5. `GET $B/history/trade/10000902?days=10` again.

**Expected:** while the peer is away, the error says the node holding the data cannot be reached (as the view does: `DRS-5052 … qa1-b
(suspect)`), or a partial answer with `DRS-1014`; once it is back, the rows.
**Actual:** step 3 answers `404 DRS-1013 trade/10000902 has no data from 2025-05-02 to 2026-10-20`: a statement that the trade has no
history, which is false. Step 5, with the peer back, still answers the same `DRS-1013` (13 ms, cached) while qa1-a's view of the trade and
qa1-b's history both work. The console shows *DRS-1013 · trade/10000910 has no data …* on the history page. The window it names
(2025-05-02 → 2026-10-20) is the whole `max-days` walk, not the 10 days asked.
**Reproduced:** three times (10000902 with `kill -STOP`; 10000906 with `kill -STOP`; 10000910 with `kill -TERM` and a restart of qa1-b).
**Evidence:** [img/qa1-false-no-data.png](img/qa1-false-no-data.png).

```text
09:04:40 10000902 404 51ms DRS-1013 trade/10000902 has no data from 2025-05-02 to 2026-10-20
view 502 DRS-5052 no source here holds trade/10000901, and the nodes that hold trade cannot be reached now: qa1-b (suspect)
09:05:17 10000902 404 13ms DRS-1013 … (peer back; 18611 answers 10 rows)
```

**Doc:** HISTORY_QUERIES.md section 16 (`DRS-1013` "the entity exists but has no data in the window"; `DRS-1014` for a source that does not
answer); tutorial 21 step 8.

---

## P2

### QA1-03 · P2 · Command line: bad ranges never reach the DRS-4021/4022 message

**Steps** (signed in as drishti-dev-admin, on `/t`, type each and press Enter; or `POST $B/command {"text": …}`):

| Typed | Console goes to | `POST /command` |
|---|---|---|
| `TRD 1000001 0` | `/s?q=TRD 1000001 0`: an empty pick list, no message | `list: "TRD 1000001 0", matched: 0` |
| `TRD 1000001 -5` | `/v/trade/1000001`: today's view, the range silently dropped | `ref` only, no `history` |
| `TRD 1000001 99999` | today's view, silently (while `500` correctly gives DRS-4021) | `ref` only |
| `TRD 1000001 1.5M` | today's view, silently | `ref` only |
| `TRD 1000001 DIFF DIFF` | today's view, silently | `DRS-4004 cannot read the condition: DRS-2101 unexpected '$' at 7` |
| `TRD 1000001 FROM 2026-02-30` | `/s?words=…`: *Your words make this search: TRD … Not understood, left out: 1000001, 2026-02-30* | `DRS-4001 '2026-02-30' is not a date` (right, but the console does not show it) |
| `TRD 1000001 FROM yesterday` | plain words, the id left out | `DRS-4004 cannot read the condition: DRS-2101 unexpected '$' at 7` |
| `TRD 1000001 3X` | plain words, the id left out | `DRS-4004 unknown amount suffix 'x' in 3X (use k, m or bn)` |
| `TRD 1000001 DIFF` | plain words | `DRS-4004 no trade has a field 'DIFF'` |
| `TRD 1000001 30 30` | history of 30 days, the second `30` silently ignored | `history.range: 30` |

**Expected:** a clear DRS message on the history page (DRS-4021 / 4022 / 4001), as the endpoint gives for the same ranges
(`?days=0` → `DRS-4022 a history of 0 days is empty: ask for 1 or more`, `?days=-5`, `?range=3X` → `DRS-4022 '3X' is not a range: …`,
`?from=2026-02-30` → `DRS-4022 … is not a date`). Never today's view as if the range was not typed, never a search that drops the id.
**Actual:** as in the table. A trader typing `-5` or `1.5M` sees today's trade and may not notice the range was ignored.
**Reproduced:** twice in the console (09:00 and 09:08) and once through `POST /command`.
**Evidence:** [img/qa1-bad-date-words.png](img/qa1-bad-date-words.png).
**Doc:** QA_PLAN H-2; HISTORY_QUERIES.md section 4.2 ("A range that is read but not allowed … fails with its own code, not with a pick
list"); USER_GUIDE "History".

### QA1-04 · P2 · History: "known at" is always refused, though every source of the kind is Delta Lake

**Steps**
1. `GET $B/history/trade/1000015?days=5&knownAt=2026-10-20T13:30:00Z`
2. `GET $B/history/counterparty/CP-ALDERSHOT?days=3&knownAt=2026-10-20T13:30:00Z`
3. `drishti.py history trade 1000001 --known-at 2026-10-20T13:30:00Z --days 3 --server http://127.0.0.1:18610 --token-file <admin>`
4. Compare: `GET $B/views/trade/1000015` with `X-Drishti-Known-At: 2026-10-20T13:30:00Z` → 200; with `2026-10-20T12:30:00Z` (before the
   lake was built) → `DRS-1001`, so time travel works for the view.

**Expected:** each day as the store stood at that instant (HISTORY_QUERIES.md 7.3, CLI_GUIDE `--known-at`, API_GUIDE `knownAt`: "Delta Lake
and Iceberg"). The connectors of `trade` are `trade-endur`, `trade-calypso`, `trade-murex`, all `plugin: delta`.
**Actual:** `400 DRS-1007 file keeps no earlier versions, so a read 'as known at' an instant cannot be answered from it …; the history of
trade/1000015 was asked for as known at 2026-10-20T13:30:00Z`. The name `file` is not one of the kind's sources in `GET $B/sources`. The
single read applies the refusal only to a dated source that may hold the date (`SourceRouter.refusesKnownAt`), the history path
(`SourceHistory.refuseKnownAt`) to any source without time travel.
**Reproduced:** three times (trade, counterparty, CLI).
**Doc:** HISTORY_QUERIES.md 7.3; CLI_GUIDE `history` (`--known-at`); API_GUIDE history row.

### QA1-05 · P2 · Type-ahead, cluster: after an id, only `30` is offered; `3M`, `YTD` and `30 DIFF` are lost

**Steps**
1. Both nodes up. `GET $B/command/suggest?q=TRD%201000002%20` (or type `TRD 1000002 ` in the console).
2. Freeze qa1-b (`kill -STOP`), wait 14 s, ask again.

**Expected:** `30`, `3M`, `YTD`, `30 DIFF` first (USER_GUIDE "History": "a space in the command line offers the ranges: 30, 3M, YTD, 30
DIFF"), then the entity.
**Actual:** with qa1-b up: `[range 30, entity 1000002, entity 10000020, 10000021, …]`: one range, then other trades whose id starts with
`1000002` from the peer. With qa1-b frozen: `[30, 3M, YTD, 30 DIFF, entity 1000002]`, as documented. The scattered type-ahead merge keeps
one row per entity. Same for `TRD CLY-3000157 `, `CPTY CP-HARBORPT `, `EQ EQ-CSCA ` (a kind qa1-b does not even hold).
**Reproduced:** twice (both nodes up at 09:13 and 09:14; the single-node answer at 08:58 and with qa1-b frozen at 09:16).
**Evidence:** [img/qa1-typeahead-cluster.png](img/qa1-typeahead-cluster.png).
**Doc:** USER_GUIDE "History"; HISTORY_QUERIES.md 4.4.

### QA1-06 · P2 · Type-ahead, masks: for a user with masked fields the range rows lose their label

**Steps**
1. Sign in as `rita` (role without `raw`). Type `CPTY CP-HARBORPT ` (or `TRD 1000002 `).
2. API: `GET $B/command/suggest?q=TRD%201000002%20` as rita, and as drishti-dev-admin.
3. Type `TRD 1000002 3`.

**Expected:** the range rows read `30 — the last 30 days with data`, `3M — the last 3 months`, … for every user.
**Actual:** for rita every range row is retitled with the entity's own title and description: `TRD 1000002 · Commodity · Commodity swap …`
(four identical rows when the peer is down), and for a counterparty `CP-HARBORPT •••` (the masked title), so the ranges cannot be told
from the entity row. Tab still completes `… 30 <GO>`, unseen. `TRD 1000002 3` offers nothing to rita and `30` to the admin.
**Reproduced:** twice (console screenshot; API as rita for TRD 1000002, TRD 1000015, TRD CLY-3000157, EQ EQ-CSCA, CPTY CP-HARBORPT).
**Evidence:** [img/qa1-typeahead-masked.png](img/qa1-typeahead-masked.png).
**Doc:** USER_GUIDE "History"; HISTORY_QUERIES.md 4.4.

### QA1-07 · P2 · History page: *Link* and `Alt+S` copy nothing on the address users are given

**Steps**
1. Sign in at `http://192.168.2.77:17610` (the console's LAN address, the one the cluster advertises; members never advertise
   127.0.0.1). Open `/v/trade/1000001/history?days=30&only=1`.
2. Press `Alt+S`; then click *Link*.
3. Repeat on `http://127.0.0.1:17610`.

**Expected:** the link `…/history?days=30&only=1&to=2026-10-20` on the clipboard and a visible confirmation (keys card: "Alt + S copy a
link to these rows").
**Actual:** on the LAN address nothing is copied and nothing is said: the page is not a secure context, so `navigator.clipboard` is
undefined and there is no fallback (`document.execCommand('copy')` is never called). On 127.0.0.1 the clipboard gets
`http://127.0.0.1:17610/v/trade/1000001/history?days=30&to=2026-10-20`: the link drops `&only=1` (changes only), and still no visible
confirmation.
**Reproduced:** twice on the LAN address (the trade page and `/v/equity/EQ-CSCA/history?range=1M`), once on 127.0.0.1.
**Doc:** USER_GUIDE "History" (Link, `Alt+S`); tutorial 21 step 3 ("The address gains `&only=1`, so a link keeps it") and step 7.

### QA1-08 · P2 · Plain words: the documented history phrases for market kinds become searches

**Steps:** on `/t`, type each and press Enter.

| Typed | Documented as | Actual |
|---|---|---|
| `equity EQ-CSCA last 3 months` | `EQ EQ-CSCA 3M` (USER_GUIDE "One entity over time, in words"; HISTORY_QUERIES.md 15.5) | search *EQ where TICKER = 'EQ-CSCA'*, the time phrase dropped |
| `fx audusd since 2026-10-01` | `FX FX-AUDUSD FROM 2026-10-01` (HISTORY_QUERIES.md 15.5) | search *FX where SPOT_DATE >= '2026-10-01'* |
| `BOOK-RATES-3 last week` | QA_PLAN H-11 | *say what to look for: a kind such as …* |
| `1000015 30` | HISTORY_QUERIES.md 4.2: "plain words, which now understand it" | *say what to look for …* |

Trade phrases work (`trade 1000015 last month`, `… between 2026-10-01 and 2026-10-15`, `… past 30 days`, `… ytd`, `what changed on trade
1000001 this week`). The rule in USER_GUIDE ("the id is the one word … that is not a value of one of the kind's fields") makes the
documented equity example impossible, because `EQ-CSCA` is a value of `TICKER`.
**Reproduced:** twice (09:06 and 09:12).
**Evidence:** [img/qa1-plain-words-equity.png](img/qa1-plain-words-equity.png).
**Doc:** USER_GUIDE lines 1418–1426; HISTORY_QUERIES.md 15.5.

### QA1-09 · P2 · Drishti*net: the loopback check on `advertise-url` is bypassed by the machine's own host name and other loopback spellings

**Steps**
1. `POST http://127.0.0.1:18611/api/v1/net/file/check?id=node.yaml` with node.yaml's text and `advertise-url:` set to each address.
2. `PUT …/net/file?id=node.yaml` (If-Match) with `http://oryxpro:18611`, then with `http://0:18611`; `GET $B/net/members` on both nodes.
3. `DRISHTI_NET_DIR=<copy> drishti.py net set node.advertise-url <url>` for the same addresses.

**Expected:** refused, as `127.0.0.1`, `localhost`, `0.0.0.0`, `[::1]`, `[::]`, `127.0.1.1` and `foo.localhost` are (all correctly refused,
by the check, the PUT, an edit on disk and the CLI).
**Actual:** accepted by the server and the CLI: `http://oryxpro:18611` (this machine's host name; `/etc/hosts` maps it to `127.0.1.1`, the
Ubuntu/Debian default), `http://0:18611` (= 0.0.0.0), `http://localhost.:18611`, `http://ip6-localhost:18611`, `http://2130706433:18611`
and `http://0x7f000001:18611` (127.0.0.1), and `http://172.17.0.1:18611` (the docker bridge). After step 2 qa1-a's member table read
`qa1-b http://oryxpro:18611 alive`, then `qa1-b http://0:18611 alive`. Another machine cannot reach either. Restored to
`http://192.168.2.77:18611` afterwards.
**Reproduced:** twice (server check/PUT, CLI).
**Doc:** NET.md; tutorial 19 step 1; memory rule "members advertise the real IP".

### QA1-10 · P2 · Demo lakes, market history: the embedded price series is not moved to the lake's dates

**Steps**
1. `drishti.py lake demo` (default `--as-of today`), the lakes run 2026-10-09 … 2026-10-20.
2. `EQ EQ-CSCA 3M <GO>` (or `GET $B/history/equity/EQ-CSCA?range=3M`, or `drishti.py history equity EQ-CSCA --range 1M`).
3. `GET $B/entities/equity/EQ-CSCA/raw`: `HISTORY` runs 2026-08-19 … 2026-09-30.

**Expected:** a continuous daily series to today (QA_PLAN H-6: "the series fill before the first stored day").
**Actual:** the header says *no data on 6 days* (2026-10-01, 02, 05, 06, 07, 08): the series still carries the test data's own dates while
the documents were moved to 2026-10-09 … 10-20. The series' last close (2026-09-30, 21.84) is today's price (2026-10-20, 21.84), so
the summary reports *High 21.84 on 2026-09-30*. `CHANGE_1D` is 3.85 % on all seven lake days while the price moves down and up
(21.408 → 21.202 → 21.305 …). The same gap is on `FX FX-AUDUSD`, `EQX EQX-NDX`, `BND BND-CACASC059511` (`missing` lists the same six days).
For a demo against Bloomberg this shows a hole and a wrong daily change on every market page.
**Reproduced:** API, CLI and console (three times).
**Evidence:** [img/qa1-series-gap.png](img/qa1-series-gap.png).
**Doc:** tutorial 21 step 5 (66 days with data, a continuous chart); data/testdata/README.md (`lake demo --as-of`).

---

## P3

### QA1-11 · P3 · `DRS-1013` does not name the first day with data

`GET $B/history/trade/1000015?from=2026-09-01&to=2026-09-30` → `DRS-1013 trade/1000015 has no data from 2026-09-01 to 2026-09-30`; the
console page has no "first day with data" link (also for `EQ-CSCA from=2026-01-01&to=2026-06-30`). Reproduced twice.
**Doc:** tutorial 21 step 8 ("the message names its first day with data"); HISTORY_QUERIES.md 16 ("its first day is 2026-06-20").

### QA1-12 · P3 · FROM after TO: three different behaviours and two documented codes

`TRD 1000001 FROM 2026-10-15 TO 2026-10-01`: `POST /command` → `DRS-4001` (title *command unknown*); `GET …?from=2026-10-15&to=2026-10-01`
→ `DRS-4022`; the console swaps the dates silently and opens `?from=2026-10-01&to=2026-10-15`. Tutorial 21 step 8 documents `DRS-4001`,
HISTORY_QUERIES.md 16 `DRS-4022`. Reproduced twice.

### QA1-13 · P3 · A bad `knownAt` names the business date

`GET $B/history/trade/1000001?days=5&knownAt=garbage` and `…&knownAt=2026-10-20` (a date, not an instant) → `DRS-4003 cannot read business
date 'null' (use yyyy-MM-dd)`. Expected: *knownAt 'garbage' is not an instant (ISO-8601)*. Reproduced twice.

### QA1-14 · P3 · Market history: the chart's hover label stays at the old position after a resize (zoom 200 %)

Open `/v/equity/EQ-CSCA/history?range=3M` at 1440 px, then narrow the window to 720 px (what zoom 200 % does): the page gains a 647 px
horizontal scroll, caused by the hover label *2026-10-20 21.84* left at x = 1367. A reload at 720 px is fine. Same on
`/v/fx-spot/FX-AUDUSD/history?range=1M`. Reproduced twice. **Evidence:** [img/qa1-zoom-overflow.png](img/qa1-zoom-overflow.png).

### QA1-15 · P3 · Market history: number formats and series rows

- The table's *Price* shows `21.657685` and `21.201933` (the chart field has `format: price2`, the column has none); the CLI summary prints
  `chg 1.09 (5.25301205%)` and `avg 21.2912818`.
- Rows from the embedded series show `0 ▸` under *History* and *Identifiers* (not read, rather than zero).
- The x-axis label `10-1…` is overlapped by the cross-hair label `2026-10-20`.

Seen on EQ-CSCA 3M and 1M (console, CLI), twice.

### QA1-16 · P3 · *Open the day* says "as it was shared"

From a history row, `Enter` (or *Open the day* in DIFF) opens `/v/trade/1000015?asOf=2026-10-19&gen=34` with the banner *You are seeing
2026-10-19, as it was shared. Go live*: nothing was shared. Once the back link read *← EQ-CSCA/1000001* (the trail of an earlier page).
Tutorial 21 step 4 says the banner reads *as it was* or *changed since*. Reproduced twice (1000001, 1000015).

### QA1-17 · P3 · Exports differ from the page

- `GET /export/history/trade/1000001.json?days=30&only=1` has 7 rows, the CSV and Excel 1 (tutorial 21 step 7: "With changes only on,
  only the changed days are exported").
- The JSON is oldest first, the page, CSV and Excel newest first.
- In the Excel file the dates are text (`t="inlineStr"`), so they do not sort or filter as dates; numbers are numbers.

Masks hold in all three formats (mona's `MTM` is `•••` in CSV, Excel and JSON, never in *Changed*). Reproduced twice (admin, mona).

### QA1-18 · P3 · CLI and API: unknown kinds and fields

- `drishti.py history TRD 1000001` → `DRS-1001 no source holds TRD/1000001`; `history trades 1000001` → `no source holds trades/1000001`.
  Expected: *TRD is a mnemonic: use the kind trade* / *unknown kind trades*.
- `--fields PRICE,NOPE` (and `GET …?fields=PRICE,NOPE`, `fields=TRADER_ID`) drops the unknown or non-column field silently.

Reproduced twice.

### QA1-19 · P3 · Documentation out of step with 2.6.5

- `docs/architecture/HISTORY_QUERIES.md` line 18: *Status: design, not built. Planned for 2.6.5* (it is built and released).
- Tutorial 21 steps 1 to 3 and CLI_GUIDE `history` show a *Trader* `•••` column; the shipped trading pack's `history.trade.columns` has no
  `TRADER_ID`, so no trade history shows a trader column.
- A peer that times out gives `502 DRS-1003 … request timed out` after 10 s; HISTORY_QUERIES.md 16 describes `DRS-1014` (or a partial
  answer).
- USER_GUIDE "Earlier commands": *TRD 1000001 0* is not described anywhere (see QA1-03).

---

## What was tried and worked

- **H-1:** every range form through `POST /command` and the endpoint: `1`, `30`, `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD`, `FROM a TO b`,
  `FROM a`, `DIFF` after each, lower case, extra spaces, `<GO>`; windows checked by hand against the USNY calendar (2026-10-12 Columbus Day
  left out; `2W` = 9 business days, `30D` = 21, `FROM 2026-10-01 TO 2026-10-15` = 10). The header states the dates.
- **H-2 at the endpoint:** `days=0`, `-5`, `99999`, `367`, `abc`, `range=3X`, `0M`, `2Y`, `from=2026-11-30` (future), `from>to`,
  `from=2026-02-30`, `from=yesterday`, `to=garbage`, `from=2024-01-01`: each a clear `DRS-4021` / `4022` / `5001`. `TRD 1000001 500` and
  `TRD 1000001 2Y` in the console give the DRS-4021 page with its links.
- **H-3:** `TRD 30`, `TRD YTD` (ids), `TRD MX-2000000 30` (pick list `/s?q=…&days=30`, each row opens `/history?days=30`), `TRD MX-2* 30`,
  `TRD limit 30`, `MX-20000001 30` (bare id), aliases `MYBOOK 30`, `MYTRD`, `MYTRD DIFF`, `MYEQ 3M DIFF`.
- **H-4:** 25 kinds read with `days=30` (trade, counterparty, netting set, book, trader, ir-curve, fx-spot, equity, equity-index, bond, credit
  curve, commodity, commodity curve, inflation index, rate fixing, economy, macro indicator, var, pnl-explain, cva, credit limit, news,
  news topic, quote, listed company): the documented shape; `counterparty-trades` answers `DRS-1012` with the reason.
- **H-5:** a trade that does not exist (`DRS-1001`), an entity only on the peer (60 rows through qa1-a), a window before the lake
  (`DRS-1013`), `to` after the newest day (clipped). A cancelled-mid-window trade and a missing middle day do not occur in the synthetic data.
- **H-6:** EQ-CSCA 3M summary checked by hand against the 37 rows (last, first, change, high, low, average); no duplicated day.
- **H-7:** rita and mona through the endpoint, DIFF, `/series` (also `MTM*2` and `MTM > 4050000`), `/entities/…/raw`, CSV, Excel, JSON, the
  CLI and links: `MTM` and `TRADER_ID` are `•••` everywhere, a masked change is never counted (mona's TVAL: `changedDays 0` against the
  admin's 6), rita gets `DRS-5002` on trade-valuation (no market-risk pack) on the page, a link, the CLI and the export.
- **H-8:** `↑ ↓ j k Home End PgUp PgDn`, `C`, `D`, `E`, `R`, `?`, `Esc`, `Enter` on the trade page; `1 3 6 Y T` on the equity page: each does
  what the keys card says; focus stays on the table.
- **H-9:** CSV, Excel and JSON of the 60-day trade (60 rows) and EQ-CSCA 3M (37 rows); file names as documented; numbers are numbers in Excel.
- **H-10:** the short form `/v/trade/1000001?days=30` and `?range=3M` redirect; a pinned link (`&to=2026-10-16`) opened by rita and mona
  shows the same rows within their rights.
- **H-12:** `drishti.py history` with `--days`, `--range`, `--from/--to`, `--fields`, `--diff`, `--changes-only`, `--csv`, `--json`; a bad
  token (`DRS-5010`, exit 1), no token, a stopped server, `--days 0` (exit 2), conflicting options (exit 2), a refused kind (`DRS-5002`),
  `DRS-1012`, `DRS-4021`, `DRS-4022`: same answers as the page, clear errors.
- **H-13:** the ten themes on the trade and equity pages at 1440 px (no overflow, no overlapping controls), 375 px and 1280 px on the trade,
  equity and DIFF pages (no page overflow; the table scrolls inside its box).
- **H-14:** see QA1-01; on the node that holds the data the new day appears within 10 s of the load and the answer is never mixed.
- **H-15:** 60-day trade, 20 trades each: on qa1-b cold median 32 ms (server `tookMs` 28.5), on qa1-a through the peer cold median 50 ms,
  cached 4 ms; the very first call across nodes after start took 1.9 s. EQ-CSCA 1Y 4 ms cached. `days=366` 40 ms; past the limit `DRS-4021`.
- **Security probes:** HTML and script in the id, `range`, `from`, `fields` and the typed command are shown as text; `..%2F..` ids answer
  `DRS-1001`.
- **4.2:** Tab completes the range; Enter opens it; `↑` recalls `TRD 1000015 30 <GO>`; an alias saved with a range; DIFF's *Compare* opens
  `/compare/trade/1000001?from=2026-10-15&to=2026-10-16`; *Open the day* pins the date box; *Go live* returns to 2026-10-20.
- **4.3:** `advertise-url` with `127.0.0.1`, `localhost`, `0.0.0.0`, `[::1]` refused by the server's check, a PUT, a file edited on disk
  (*refused*, logged) and `drishti.py net set`; without `advertise-url` the node advertises `http://192.168.2.77:18611`
  (`advertiseDetected: true`). History of an entity held only on the peer works. Last administrator, twice (n1 disables qa-admin2 while n2
  demotes qa-admin; then n1 demotes qa-admin2 while n2 disables qa-admin): both changes accepted, the cluster repaired itself within 2 s
  (`last-admin-kept` audited on both nodes, *Last administrator kept* on Admin → Health), exactly one enabled administrator left.
  A disabled user's signed token is refused (401).

## Not covered

- H-5: a trade cancelled mid-window and a day missing in the middle (no such data in the demo or synthetic lakes; would need a hand-made lake).
- H-6: a series day that overlaps a stored document day (the demo series and documents do not overlap; see QA1-10).
- H-13: real browser zoom and every theme at phone width (themes were checked at 1440 px only; 375 px and 1280 px in the default theme).
- H-14 in two browser tabs (done through two API pollers, one per node).
- `Space` (drawer) and `F` (fields) keys: `F` opens its checklist; `Space` did nothing on the equity page (not on the keys card either).
