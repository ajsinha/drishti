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
# QA round 4: short adversarial re-check of Fix-8, Fix-9a/9b and Fix-10 (2026-10-20)

Develop at `e0e0f4644`. This is a report only. No product code was changed, and all data is synthetic. The stopping rule was:
no P1 or P2 means QA is done for 2.6.6.

**Verdict: not done.** One P2 is still open. **QA3B-03 is not fixed**: a moved Iceberg table loaded by `load-iceberg.sh` still
reads `UP` / *running* in Admin → Connectors. Each read answers a generic `502 DRS-1003` and logs a full stack trace. This was
reproduced twice. Every other P1 and P2 re-tested is fixed, including SOAK2-01 (P1), QA3A-01 (P1) and QA3B-07. There are 6 new
findings, all P3.

## Stack

| Part | How |
|---|---|
| Build | `./mvnw -q -T 4 -DskipTests package -pl drishti-server -am`, `drishti-server-2.6.5-exec.jar`, JDK 21 |
| Demo lakes | `tools/build_demo_lakes.py --lake <scratch>/data/delta --connectors-dir <scratch>/data/connectors`: 66 tables, 60 business days ending 2026-10-20, 756 s |
| Server m | 18620, `-Xmx3g -XX:MaxDirectMemorySize=256m`, `MALLOC_ARENA_MAX=2`, security on, the seven packs, `DRISHTI_DEMO_ENABLED=false`, `DRISHTI_OFFLINE=true`, data, connectors and BI (a copy of `config/bi` plus a statement report) in the scratchpad, a Drishti\*net member, `redact=PRICE,TRADER_ID,NAME,GDP_PER_CAPITA` |
| Net pair (SOAK2) | `tools/bench/trades.py` lakes (200 trades a day per system, 10 days to 2026-10-16). a = ENDUR+CALYPSO on 18620 and c = MUREX on 18621, launched as `net_soak.py` launches them: one cluster key, `redact=PRICE,TRADER_ID,NAME`, `-Xmx2g`. The viewer token has role `viewer`, the admin token role `admin`. Later e = world-economy only (empty lake) on 18621 beside m, and i = counterparty-risk only (empty lake, no net) on 18621 |
| Console | 17620, `--server.workers=4`, sign-in on, `--backend.url=http://192.168.2.77:18620` |
| Users | `qaadmin` (admin), `trader1` (trader), `trader2` and `soak-viewer` (viewer), `carl` (`qa-nobi`: no raw, BI powers changed during the tests) |
| Clients | HTTP clients with a cookie jar and **a new connection per request** (spread over the four workers), as in round 3. One browser-like client at a time |

## Verdicts

| Finding | Verdict | Re-test (each twice unless said) |
|---|---|---|
| **QA3A-01** (P1) signal storm, out of memory | **Fixed** | `build_demo_lakes.py --only trades/murex --server … --token-file …` while 3 readers loop the statement preview: *1 data-load signal(s) (each table once, its newest date)*, 165 s in all. Health 3 to 17 ms, no full GC, no WARN. 857 previews, 0 errors, slowest 3.0 s |
| **QA3A-02** (P2) pack choice, 4 workers | **Fixed** | trader1 and trader2: 7 → 2 → 7 packs, 20 pages each way: 80 of 80 right. An administrator's assignment (Admin → Users, 2 ↔ 7 packs): 80 of 80 right |
| **QA3A-03** (P2) saved or reset layout | **Fixed** | `PUT`, then `DELETE /api/layout/endur-comswap/trade`: own layout on 20 of 20 pages after the save and 0 of 20 after the reset, twice. Saved pivots were not run as pages (the same stamp; code read) |
| **QA3A-04** (P2) disabled user, sign-out | **Fixed** | Disable trader2 and then trader1 while 4 threads open `/t`: **0** pages served that were asked after the disable returned (the request takes about 0.5 s). Sign-out with a copied cookie: 0 pages, twice |
| **QA3A-05** (P2) `build_demo_lakes.py` signals 18480 | **Fixed** | No `--server`: *Server: not told (--no-signal)*. `--server` sends one signal per table for its newest date. See QA4-06 for the closing line |
| **QA3A-06** (P3) second browser, old theme | **Fixed** | Browsers A and B of trader1, theme crimson then blue: 20 of 20 right in each browser, twice |
| **QA3A-07** (P3) heartbeat and summary | **Fixed** | The heartbeat names the table (`writing trading/trade_murex`, MB growing); *told: 1 data-load signals in 0 s*; *done in 165 s*. A refused token now reads *refused 2 (403 … DRS-5002 …)* |
| **QA3A-08** (P3) closing lines after `--files-only --system calypso` | **Fixed** | *Then try: TRD CLY-3000028 60 · … (CALYPSO trades, from your files)*; the id exists (200 as of 2026-10-20). See QA4-06 |
| **QA3B-01** (P2) figures cut | **Fixed** | A4 portrait, a seven-currency matrix of `MTM` and the Notional card: every figure whole (`3,617,068,619,773.65`, `2,192,181,297.64`, `-1,196,646,967.14`), on the preview and the PDF (`img/qa4-statement-whole-figures.png`). Only a text row label is cut (`DESK-STRU…`), as STATEMENTS.md allows |
| **QA3B-02** (P2) "no data" hint for the month | **Fixed** | 2026-10-25: *No data is held for 2026-10-25 … nearest date with data is 2026-10-20 (before)*. 2026-07-26 (after: 2026-07-27), 2026-10-18 (a Sunday: *2026-10-16 (before) and 2026-10-19 (after)*), 9999-12-31 and 1999-01-01 are right too |
| **QA3B-03** (P2) moved Iceberg table still `UP` | **Not fixed** | **QA4-01** below |
| **QA3B-04** (P2) `relocate` in plain form ignored | **Partly** | Reads now work with the plain form, `file:///…` with a trailing `/`, and `file:/…`: 200 from `qa-ice-moved`. A pair without ` -> ` fails the start naming it. A Windows-form pair (`'C:\Users\qa\ice1 -> D:\lake\ice2'`) is accepted, but Health does not say *relocate … is set, but no old folder in it holds …*: Health stays `UP` (the QA4-01 cause). Also QA4-05 |
| **QA3B-05** (P2) Cassandra session leak | **Fixed (tests only)** | `CassandraConnectFailureTest` (4) and `CassandraTlsTest` (5) on Testcontainers Cassandra: 9 of 9 pass, and the containers are removed. Not re-run as a connector file on a live server for 50 minutes |
| **QA3B-06** (P2) BI powers on a role, 4 workers | **Fixed** | Admin → BI settings (`PUT /bi/api-roles/qa-nobi`), grant then revoke, twice: 0 wrong of 1,592 / 846 / 1,770 / 1,903 pages and `/bi` answers. Admin → Roles (`POST /admin/api/roles/qa-nobi`): 0 wrong of about 6,100 pages over four changes |
| **QA3B-07** (P2) server does not settle after a burst | **Fixed** | 1,000 signals (8 clients, `trading/trade` and `market-risk/trade-valuation` over 60 dates) in 67 s, all 201, while 6 readers loop the preview. CPU peaks at 818 % and is back to the readers' 120 to 150 % within 15 s of the last signal. Then 55 to 80 % (readers only), and **1 to 4 %** once the readers stop. Health 2 to 16 ms throughout, 0 full GCs, 0 WARN or ERROR. 8 full trade-page passes for 1,000 signals. No rebuild race. The readers during the burst: 1,264 previews, 0 errors, slowest 2.2 s |
| **QA3B-08** (P3) empty count `–` or `0` | **Fixed** | The Trades card reads `0` on all five empty dates. See QA4-03 for the date after the business date |
| **QA3B-09** (P3) CLI hint, "1 pages" | **Fixed** | `bi statement preview --file … --date 9999-12-31`: *1 page*, `hint:` line. See QA4-03 (*3 rows*) |
| **QA3B-10** (P3) Excel without notes | **Fixed** | carl (MTM masked through the dataset's `security.masked`): `.xlsx` sheets carry *Masked for you: MTM.* and the full *Not sorted: 'MTM' is masked for you, and its order would tell the masked figures.* Every MTM cell is `•••` in PDF and Excel |
| **QA3B-11** (P3) Cassandra TLS refusal named | **Fixed (tests only)** | `CassandraTlsTest` passes. Not seen on Admin → Connectors |
| **QA3B-12** (P3) `load-iceberg.sh` stack trace | **Fixed** | REST catalog unreachable: *iceberg: the load failed: cannot reach the REST catalog at http://127.0.0.1:1: null (ClosedChannelException; -Ddrishti.loader.trace=true prints the stack trace)*, then one closing line, no frames. The `: null` is cosmetic. The missing-bucket case was not run (no S3 here) |
| **SOAK2-01** (P1) admin history with viewer masks | **Fixed** | Two real servers (a, c), one cluster key. Peer-held trades (MUREX from a; CALYPSO and ENDUR from c) asked viewer → admin → viewer → admin and admin → viewer → admin → viewer, `days=30` with and without `diff`, 18 trades, within the cache's life. **288 of 288 answers right**: the admin raw with `PRICE` `masked: false` and `PRICE` among `changed` where it moved (MX-50000016: 2 days); the viewer `•••` with `masked: true` |
| SOAK2-01 sweep: views | **Fixed** | `/api/v1/views/trade/<id>` for 22 peer-held trades in both orders: the admin's Price shown (for example `3.2446%`, `864.3047`), the viewer's `•••`, every time. Trades whose PRICE is null read blank for the admin, as their history shows |
| SOAK2-01 sweep: map panels | **Fixed where testable** | On the node holding `economy` (m), ECON-USA, GBR and JPN asked viewer-first and admin-first: the viewer gets *the field is masked for your role*, the admin gets 213 shaded countries. On a node reading `economy` only from its peer (e), the map is empty for both: QA4-02 |
| **SOAK2-02** (P3) `enabled: "false"` | **Fixed** | The net pair's switched-off files written with a quoted `"false"`: `DISABLED`, no *not usable* line. `"FALSE"`, `"False "`, `no` and `"false"` switch it off; `"TRUE"` switches it on; `"nope"` and `"yes"` are refused naming the value. See QA4-04 for placeholders |
| **SOAK2-05** (P3) refusal log lines | **Fixed** | c stopped (SIGTERM), then killed (SIGKILL), while a takes 99,320 and then 70,320 requests for MUREX trades in 90 s and 80 s. a logs **one line per state of c** (`left`; `failed`, `not answering`, `suspect`, `dead`, `rejoining`), each counting the refusals before it, plus the once-a-minute summary. At rejoin it says `drishti-c (rejoining)`, never `(alive)` |
| `build_demo_lakes.py` without deltalake | **Fixed** | `/usr/bin/python3` (3.14, no deltalake): exits at once, in 0.2 s: *this Python (/usr/bin/python3) lacks deltalake: run the script with the console's environment, drishti-console/.venv/bin/python (Windows: …\Scripts\python.exe)* |
| `--only`, `--files-only`, `--source-dir/--system` | **Pass** with QA4-06 | `--only trades/murex` (165 s) and `--only core/counterparty` (10 s); `--source-dir <2 synthetic CALYPSO files> --system calypso --files-only --server …`: 400 rows, 11 s, the trades readable as of each file's date |

## New findings

| Severity | Count | Findings |
|---|---|---|
| P1 | 0 | |
| P2 | 0 new (QA3B-03 still open, as QA4-01) | QA4-01 |
| P3 | 6 | QA4-02 to QA4-07 |

### QA4-01 · P2 (QA3B-03 not fixed) · a moved Iceberg table from `load-iceberg.sh` is still `UP`, with a generic 502 and a stack trace per read

**Steps** (reproduced twice: hot-added on server m at 18620, and present at start on server i at 18621)
1. `tools/load-iceberg.sh <scratch>/ice1 --engine native --spill-dir <scratch>/spill` (the samples: 25,710 rows, 47 tables).
2. `mv <scratch>/ice1 <scratch>/ice2`.
3. Connector file `qa-ice-moved.yaml`: `plugin: iceberg`, `kinds: [credit-limit]`, `settings: {root: <scratch>/ice2, domain: credit,
   engine: native}`. On m, `credit-store` is switched off so that the read reaches Iceberg; i has no other source of the kind.
4. `GET /api/v1/admin/connectors`, `POST /api/v1/admin/connectors/qa-ice-moved/test`, and
   `GET /api/v1/entities/credit-limit/LIM-ALDERSHOT/raw?asOf=2026-09-30`, twice each, over two minutes.

**Expected** (CHANGELOG, ICEBERG_CONNECTOR.md 14): the badge reads *degraded*, with `DEGRADED: cannot read credit-limit: its metadata
names files under <scratch>/ice1 … relocate: '<scratch>/ice1 -> <scratch>/ice2'`. The 502 says the table names files that are not
there. One log line per read.

**Actual:** `{"state":"RUNNING","health":"UP","problems":[],"level":"ok","label":"running"}` at every look. Test says `ok: true,
health: UP`, with credit-limit *nothing found for this kind*, `dates: []`. Every read answers `502 DRS-1003 qa-ice-moved failed reading
credit-limit/LIM-ALDERSHOT` and logs a WARN with the whole chain: `SourceFailure` ← `IcebergSourcePlugin$QuietFailure` ←
`UncheckedIOException: reading the manifest list of snapshot 2683060062499122660 of file:<scratch>/ice1/credit/credit-limit` ←
`NoSuchFileException …/ice1/…/snap-….avro`. That is 36 to 37 `at` lines per read.

**Note:** `IcebergMovedConnectorFileTest` passes. That test loads 2 rows of one date with `IcebergLoader.main`. Here the connector's
own Test finds no dates for the kind, so nothing marks the table moved before a read. On the read path, the `QuietFailure` comes out
of the plugin as the cause, apparently without passing through `TableProblems.failed` (code read only). The read with a `relocate`
is right (QA3B-04).

### QA4-02 · P3 · a map panel on a node that reads its kind only from a peer is empty, and says no entity exists

**Steps** (reproduced on ECON-USA, ECON-GBR, ECON-JPN, each asked four times as viewer and as admin): m holds `economy` (demo lakes).
e (18621) has the world-economy pack with its connectors switched off, in the same cluster. Ask e
`GET /api/v1/views/economy/ECON-USA` as the admin.

**Expected:** the same map as on m: 213 linked countries and the GDP-per-capita shade for the admin, and *masked for your role* for
a viewer.

**Actual:** the page itself comes from the peer (strip `GDP per capita (USD) 90,027` for the admin and `•••` for the viewer, both
right). But the map reads `link: {codes: [], note: "no economy entity matches ECON-{iso3}"}` and `shade: {items: [], note: "no
economy entity has $.GDP_PER_CAPITA"}` for every caller. `MapSupport.listing` asks `SourceRouter.list`, which asks local plugins
only, so the per-signature keys added for SOAK2-01 never see peer data. The note says something false (the entities exist). It is
probably older than the fix. DRISHTI_NET.md says the map panels' listing and documents come partly from peers.

### QA4-03 · P3 · statements: notes cut mid-sentence, and a count `0` beside "Not available"

Reproduced twice for each part: 2026-10-25 and 9999-12-31 as the admin, and the preview and the PDF as carl.
- For a date after the business date, every card's note is cut. It reads `Not available: DRS-9020 the as-of date 202…` (Notional,
  MTM) and `… 2026-…` (Trades), so the reason is lost. The Trades card then reads **`0`** right above *Not available*. The count was
  not asked, yet it reads as "no trades". The matrix and table notes are whole.
- For a masked reader, the table note is cut on paper: *… Masked for you: MTM. Not sorted: 'MTM' is masked fo…*
  (`img/qa4-statement-masked-note-cut.png`). The Excel and the delivery's notes have it whole.
- `drishti.py bi statement preview --date 9999-12-31` prints *1 page, …, 3 rows*, and the API has `rows: 3`, beside the hint *every
  band is empty*. The three rows are the empty cards.

### QA4-04 · P3 · connector files: `enabled` as a `${VAR:…}` placeholder is listed as on while off, and a bad default is not refused

**Steps** (on c, editing `trade-endur.yaml` while it runs; each value reproduced at least twice across `false` / `FALSE` / `FaLsE`)
- `enabled: "${QA4_UNSET:false}"`: the state is `DISABLED` (right). But `GET /api/v1/admin/connectors` lists `"enabled": true`:
  `ConnectorManager.summary` uses the file's unresolved text. The pack templates write `"${DRISHTI_LAKE_ENABLED:true}"`, so
  `DRISHTI_LAKE_ENABLED=false` shows every lake connector as on and off at once.
- `enabled: "${QA4_UNSET:nope}"` switches the connector off silently (`Boolean.parseBoolean`), while a literal `"nope"` is refused
  naming the value. The CHANGELOG says anything else is refused.

### QA4-05 · P3 · Iceberg: a connector whose `relocate` cannot be read logs a NullPointerException stack trace at every retry

**Steps** (seen at 20:20:11 and 20:20:16 on i): set `relocate: '/no/arrow/here'` in a running Iceberg connector file.

**Expected:** the start fails naming the pair (it does: *relocate: '/no/arrow/here' cannot be read: it needs one ' -> ' …*), and
nothing else.

**Actual:** at each RetryBoard attempt there is also `WARN SourceRegistry: closing qa-ice-moved failed` with
`java.lang.NullPointerException: Cannot invoke "…LakeTables.close()" because "this.lake" is null` at
`IcebergSourcePlugin.close(IcebergSourcePlugin.java:681)`, and a stack trace.

### QA4-06 · P3 · `build_demo_lakes.py --server`: the closing line says the server was told when it was not

**Steps** (reproduced twice: no server listening, and a token without `loads:write`)
1. `build_demo_lakes.py --only core/counterparty … --server http://192.168.2.77:18629 --token-file …` (nothing on 18629).
2. The same with `--server http://192.168.2.77:18620 --token-file <carl's token>`.

**Actual:** the step lines are right (*no server at …:18629: left …/bi-store/lake-rebuilt.json …*; *took 0 of 2 data-load signals,
refused 2 (403 … DRS-5002 …)*). But the last line of both runs is *The server at http://192.168.2.77:186xx was told once per table it
reads (see the lines above); it reads the new data now.*

**Expected:** a closing line that fits the outcome: not told (no server, or refused) and what to do.

### QA4-07 · P3 · `load-iceberg.sh`: the one-line error reads `: null`

Seen in one run: *cannot reach the REST catalog at http://127.0.0.1:1: null (ClosedChannelException; …)*. The exception has no
message, and the line prints `null` for it.

## Fresh ground and load

- **Masks under load.** 288 history answers plus 88 view answers in both orders: every admin answer raw, every viewer answer masked.
- **Net kill and rejoin.** No `DRS-1003`. The 404s after the rejoin were only for ids the lakes do not hold (MX-50000200 and up).
- **Four-worker console.** About 15,000 pages over new connections: 0 stale pages in every case above. Console log: no traceback.
- **Server under BI load.** Two loops of 3 readers each, for 420 s and 600 s, beside the `--only` load and the 1,000-signal burst. The
  loops asked 857 and 1,264 previews (239 pages each): 0 errors, medians 0.37 s and 0.35 s, slowest 3.0 s. RSS 0.9 → 2.3 GB with `-Xmx3g`, old
  generation 25 to 81 %, 0 full GCs.

## Not covered

Saved pivots as pages (same stamp as layouts, code read), Cassandra as a live connector file over 50 minutes (the fix's own
Testcontainers tests were run), a real Windows path for `relocate` (parsed only), the S3 missing-bucket form of QA3B-12 (no S3 here),
and the map panel's per-signature cache across peers (not reachable: QA4-02).

All processes started for this round (the net pair, servers m, e and i, the console, the monitors and the loads) were stopped by their
PIDs. The scratch lakes, the Iceberg folders and the console data were deleted. The soak ports (1871x/1771x) and the user's cluster
(18480/17480/18791/18792, `drishti-stack/run`, `data/stack`, the repository's `data/`) were not touched.
