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
# QA round 3, tester B: BI author and platform administrator (2026-10-20)

Persona: a BI author and a platform administrator. Develop at `5d20bd55f` (every fix of rounds 1, 2 and 3A). Report only: no product
code was changed. All data is synthetic.

## Stack

| Part | How |
|---|---|
| Build | `./mvnw -q -T 4 -DskipTests install`, `drishti-server-2.6.5-exec.jar`, JDK 21 |
| Lakes | `tools/build_demo_lakes.py --lake <scratch>/data/delta --connectors-dir <scratch>/data/connectors`: 66 tables, 60 business days 2026-07-27 to 2026-10-20, 777 s, no server signalled |
| Server | 18620, `-Xmx4g`, security on, the seven packs, `DRISHTI_DEMO_ENABLED=false`, `DRISHTI_OFFLINE=true`, data, connectors, BI (a copy of `config/bi`), net, AI and MCP folders in the scratchpad; mail to an SMTP sink (`docs/tutorials/data/smtp-catcher.py`) on 127.0.0.1:2626 |
| Console | 17620, `--server.workers=4`, sign-in on, `--backend.url=http://192.168.2.77:18620` |
| Users | `qaadmin` (admin); `author1` (author: `bi-author`); `rita` (`qa-risk`: raw, `bi`), `hana` (`qa-deskhead`: raw, `bi`, desk `DESK-FX`), `ann` (`qa-analyst`: no raw, `bi`), `dora` (`qa-risk`, disabled), `nora` (viewer, not a member), `carl` (`qa-nobi`: no BI power) |
| Containers | Testcontainers only, from a small holder program on the test class path: Cassandra 5.0.6 with `client_encryption_options` (`require_client_auth: true`, the PKI of `TlsFixture`), and `adobe/s3mock` + `apache/iceberg-rest-fixture:1.10.1` on one network. MinIO could not be pulled (`docker.io` "pull access denied", `quay.io` 401), so S3 is S3Mock |
| Clients | HTTP clients with a cookie jar and a new connection per request (spreads over the four workers); Playwright Chromium, one browser at a time; `pdftotext`, `pdftoppm` and a zip/XML reader for the files |

## Summary

| Severity | Count | Findings |
|---|---|---|
| P1 | 0 | |
| P2 | 7 | QA3B-01, QA3B-02, QA3B-03, QA3B-04, QA3B-05, QA3B-06, QA3B-07 |
| P3 | 5 | QA3B-08, QA3B-09, QA3B-10, QA3B-11, QA3B-12 |

No mask leak was found in the burst to masked recipients (six files read cell by cell).

## Findings

### QA3B-01 · P2 · statements: figures are cut with an ellipsis, so a number reads as another number, and nothing says so

**Steps** (reproduced twice: the preview as `rita` and as `author1`, and the burst PDF mailed to `rita`)
1. A report on a model with `MTM` (`signed0`) and `NOTIONAL`, a statement page (A4 portrait) with a card `Notional` (`w: 4`) and a matrix
   `rows: [trade.DESK], columns: [trade.CURRENCY], values: [MTM]` (seven currencies).
2. `POST /bi/reports/statement/preview` for 2026-10-20, or a subscription in `pdf` run now.

**Expected:** a statement is the formal paper copy: every figure whole (a smaller font, a wrapped header, landscape suggested), or at
least a note that values were cut, as a cut table says *The first 10,000 rows only*.

**Actual:** the card reads `3,617,068,619,773.…`; in the matrix almost every cell is cut: `291,648,02…`, `-428,350,58…`,
`-1,196,646,9…`, row labels `DESK-CO…`, `DESK-RA…` (`img/qa3b-statement-cut-figures-2.png`). `-428,350,58…` reads as a number a
thousand times smaller than the real `-428,350,58x.xx`. No note on the page, in the preview's `cut` or in the delivery's `notes`.
STATEMENTS.md section 4 says a cell wider than its column is cut with an ellipsis; that holds for text, but for a figure on a statement
it misleads.

### QA3B-02 · P2 · statements: the "no data" hint says a whole month holds no data when it does

**Steps** (reproduced twice: 2026-10-25 and 2026-07-26, each as `author1` and `ann`, the API and the designer's Paper tab)
1. The statement of QA3B-01, `period: month`; the lake holds 2026-07-27 to 2026-10-20.
2. Preview with `date: 2026-10-25` (after the newest day) and `date: 2026-07-26` (the day before the first).

**Expected:** the hint names what is missing: no data on that date (the newest is 2026-10-20), or the statement covers the days of
the month that are held.

**Actual:** `hint`: *No data is held for October 2026 (2026-10-01 to 2026-10-31): every band is empty. Choose a date the data covers.*,
and for 2026-07-26 *No data is held for July 2026 (2026-07-01 to 2026-07-31)*. October holds 14 business days and July 5. The designer
shows the same text beside the page count (*Page 1 of 1 · A4 portrait · October 2026 · No data is held for October 2026 …*). For
9999-12-31 and 1999-01-01 the hint is right.

### QA3B-03 · P2 · Iceberg: a moved table is still `UP`, with a stack trace per read, when it comes from the loader through a connector file (QA2-22 not effective)

**Steps** (reproduced twice: with table discovery, and with `kinds: credit-limit` and `refresh-seconds: 2` in the settings)
1. `tools/load-iceberg.sh <scratch>/ice1 --engine native` (the samples, 47 tables).
2. `mv <scratch>/ice1 <scratch>/ice2`.
3. Connector file `qa-ice-moved.yaml`: `plugin: iceberg`, `kinds: [credit-limit]`, `settings: {root: <scratch>/ice2, domain: credit,
   engine: native}`.
4. Watch Admin → Connectors (`GET /admin/connectors`) for two minutes; read `GET /entities/credit-limit/LIM-ALDERSHOT/raw` as of 2026-09-30.

**Expected:** ICEBERG_CONNECTOR.md section 14: `DEGRADED: cannot read credit-limit: its metadata names files under <old folder> that
are not there (…): the table was moved or copied after it was written`, with the `relocate:` to use; one short log line.

**Actual:** `UP`, `problems: []`, at every look for 2 minutes (and 30 s with `refresh-seconds: 2`). Every read answers `502 DRS-1003
qa-ice-moved failed reading credit-limit/LIM-ALDERSHOT` and logs a WARN with a full stack trace (`QuietFailure` … `FileNotFoundException:
no such file or directory: file:<scratch>/ice1/credit/credit-limit/metadata/snap-….avro`). `IcebergMovedTableTest` passes, so the
detection works on the test's table but not on one written by `IcebergLoader` and served through a connector file.

### QA3B-04 · P2 · Iceberg: `relocate` written as the docs write it (plain folders) is ignored without a word

**Steps** (reproduced twice: plain form, then `file:` form, then plain again)
1. As QA3B-03, add `relocate: "<scratch>/ice1 -> <scratch>/ice2"` (ICEBERG_CONNECTOR.md: `relocate: <old folder> -> <new folder>`).
2. Read `credit-limit/LIM-ALDERSHOT` as of 2026-09-30.

**Expected:** the read works, or Health says the prefix matches nothing.

**Actual:** still `502 DRS-1003` and the stack trace with the old path; Health `UP`. With `relocate: "file:<scratch>/ice1 ->
file:<scratch>/ice2"` the read answers 200 (`source: qa-ice-moved`). Back to the plain form: 502 again. The metadata names files as
`file:/…`, the docs never mention the scheme, and with QA3B-03 Health never gives the exact prefix to copy: a dead end.

### QA3B-05 · P2 · Cassandra: a connector that cannot connect leaks a driver session at every retry

**Steps** (seen over 50 minutes; the count grew at each retry)
1. Cassandra 5 with mutual TLS (above). Two connector files against it: `qa-cass-nocert` (`tls.enabled: true`, `ca-file` only, no client
   certificate) and `qa-cass-plain` (no TLS).
2. Both are `DOWN: connecting to Cassandra at localhost:<port>` (right); the RetryBoard restarts them.
3. `qa-cass-plain.yaml` deleted after 10 minutes.

**Expected:** a restart closes the previous `CqlSession`; a deleted connector leaves nothing running.

**Actual:** the driver warns `You have too many session instances: 5 active` (16:06), `6` (16:10), `7` (16:11), `8` (16:23), `9` (16:37),
and sessions `s2` to `s10` each log `[sN] Error connecting to Node(…) trying next node` about once a minute until the server stops
(19 to 20 WARN lines per session in the 30 minutes after the storm below). Each session keeps its own threads and reconnection
schedule, so a connector left `DOWN` overnight grows without bound.

### QA3B-06 · P2 · console (4 workers): BI powers changed on a role in Admin reach its users only after up to 6 s

**Steps** (reproduced twice: grant, then revoke)
1. Role `qa-nobi` (no BI power), user `carl` signed in, 16 pages opened (every worker holds his BI menu).
2. `qaadmin` on the same console: Admin → BI settings, powers of `qa-nobi` = `bi, bi-author` (`PUT /bi/api-roles/qa-nobi`, 200).
3. `carl` opens `/t` and `/bi` for 14 s, one new connection per request.

**Expected:** as for the fixes of QA3A-02 to 06: at once on every worker.

**Actual:** grant: the BI menu is missing on 26 of 64 pages, the last 5.9 s after the change, and `/bi` answers 403 on some of them;
revoke (powers `[]`): the menu still shown on 3 of 79 pages, the last at 4.3 s. `routes/bi_routes.py` `role_powers` forgets only the
administrator's own `bi_me` (and Admin → Roles `save_role` forgets nothing), not the users who hold the role, so each worker keeps its
copy for `ui.user_cache_seconds`. A user's own role change (Admin → Users) is right at once (0 wrong of 323 pages).

### QA3B-07 · P2 · server: after a burst of data-load signals the BI layers are rebuilt over and over; 23 minutes later the server is still at 1,000 % CPU

**Steps** (once, deliberately heavy: about 50 times QA3A-01's 60 signals)
1. Three readers (`rita`, `ann`, `hana`) asking the statement preview in a loop.
2. 8 concurrent clients send `POST /packs/trading/loads {kind: trade}` and `POST /packs/market-risk/loads {kind: trade-valuation}` for
   each of the 60 business days, a new round every 5 s: 2,976 signals, all `201`, the last received about 16:34.

**Expected:** CHANGELOG (QA3A-01): signals merged per connector, *BI layer rebuilds run one date at a time per table, each date once*;
the server settles after the burst.

**Actual:** what held: no `OutOfMemoryError`, `/actuator/health` at most 1.7 s (median 0.3 s), 36 of 36 statement previews right during
the burst (slowest 26 s), numbers unchanged afterwards. What did not: each BI table was rebuilt 24 to 25 times per date
(`file:model:trading-risk trades 2026-10-20` 25 times; 870 rebuilds of that table alone), and four rebuilds failed on a race with a
purge (`DRS-9027 the engine failed: the built file part.parquet.faa065f2.tmp is gone`, `cannot write the layer's metadata …/meta.json`,
`IO Error: Cannot open file "…/part.parquet.cbc52d2d.tmp"`). At 16:55, 20 minutes after the last signal, a thread dump showed 303
virtual threads in `DeltaSourcePlugin.doc` (280 waiting in `ReadSlots.acquire`) fed by `QueryService.rebuild` → `TableBuilder.records` →
`SourceRouter.fetchAll`, the process at 1,000 to 1,600 % CPU, old generation 97 %, 588 full GCs (123 s). It had not dropped below
150 % CPU when it was stopped at 16:58. Not repeated (one heavy run, as the rules say); triage may judge the load unrealistic.

### QA3B-08 · P3 · statements: an empty count card reads `–` on some dates and `0` on others

Reproduced twice (as `author1` and `ann`). The `Trades` card (`count(trade)`) reads `–` for 9999-12-31 and 2026-10-25 and `0` for
1999-01-01 and 2026-07-26; Notional and MTM read `–` on all four. STATEMENTS.md: *a count reads its number*.

### QA3B-09 · P3 · CLI: `bi statement preview` says nothing when no data is held, and "1 pages"

Seen once on the command line; the subject twice (`hana`'s pdf and xlsx mails). `drishti.py bi statement preview <id> --date 9999-12-31` prints
`QA big statement: 1 pages, A4 landscape, December 9999, 0 rows` and no hint line (the API's `hint` is there, the designer shows it; the
`cut:` lines are printed, so the CLI reads the preview). The mail subject reads `… · 1 pages` too.

### QA3B-10 · P3 · statements in Excel carry no notes for the masked reader

Reproduced twice (the Excel burst to `ann`, and the PDF burst beside it). `ann`'s PDF has *Masked for you: MTM.* under each band and
*Not sorted: 'MTM' is masked for you …* under the sorted table, and the delivery's `notes` hold the sort note; her `.xlsx` (sheets
`About`, `Notional`, `MTM`, `Trades`, `By desk sorted by MTM`, `Books`, `MTM by desk and currency`) has neither: the `About` sheet lists
the sheets and rows only. No value leaks (every MTM cell is `•••`, the masked portfolio not grouped on).

### QA3B-11 · P3 · Cassandra: a TLS refusal is only "connecting to Cassandra at …"

Reproduced twice (`qa-cass-nocert` without the client certificate, `qa-cass-plain` without TLS against a TLS-only node): Health and Admin →
Connectors say `DOWN: connecting to Cassandra at localhost:<port>`, the same as a closed port. The cause (the node asked for a client
certificate; the node speaks TLS) is in the driver's WARN only. CASSANDRA_CONNECTOR.md's table sends the reader to check "TLS and the
log-in" among four causes.

### QA3B-12 · P3 · `load-iceberg.sh` to a REST catalog ends in a Java stack trace when the bucket is missing

Seen once (the load after the bucket was made went through). `tools/load-iceberg.sh --catalog rest --uri … --set s3.endpoint=…` with no `warehouse` bucket: `Exception in thread
"main" java.io.UncheckedIOException: java.io.IOException: createTable book: HTTP 500 NoSuchBucketException: The specified bucket does
not exist` and 8 frames. The words are right; the form is not the loader's usual one line.

## Re-test verdicts

| Item | Verdict | Notes |
|---|---|---|
| QA2-12 statement for a date with no data | **Pass** for dates outside every month held; **Fail** inside a held month | 9999-12-31, 1999-01-01: hint in the preview, first line of the file's notes, beside the page count in the designer; QA3B-02 for 2026-10-25 and 2026-07-26 |
| QA2-14 empty cards read `–` | **Pass** with QA3B-08 | values and measures `–`, never blank |
| QA2-17 disabled recipient | **Pass** | `dora`: `skipped`, *the account is disabled* (pdf and xlsx runs) |
| QA2-18 / QA2-19 recipient without access | **Pass** | `nora`: `skipped`, *Nora may not open the report: it is not shared with them, or their roles do not reach it*; an unknown user is refused at save (`DRS-9061 no user 'ghost-user'`) |
| QA2-19 table sorted by a masked field | **Pass** with QA3B-10 | `ann`: drawn in the query's order (DESK-COMM, DESK-CREDIT, …) with *Not sorted: 'MTM' is masked for you …* in the PDF and the delivery's `notes`; `rita`'s order is by MTM |
| QA2-15 Paper tab at 1400 px | **Pass** | Terminal Crimson, Terminal Green, light: sheet 444 px, two pages, no horizontal scroll, no text under 4.5:1 (`img/qa3b-paper-1400-terminal-crimson.png`) |
| QA2-16 Health at 375 px | **Pass** | the three themes: no horizontal scroll, no low-contrast text (`img/qa3b-health-375-terminal-green.png`) |
| QA2-20 one DRS-9037 per parameter | **Pass** | four wells wrong, four problems, each naming the choices (*the axis well takes fields, and the field parameter 'metric' can be MTM, NOTIONAL, measures: put it in a measure well*); `param.nope` names the parameters there are; `/bi/mq`: `DRS-9164` in both directions and *metric is one of its choices [MTM, NOTIONAL], not 'Trades'* |
| QA2-22 Iceberg moved table and relocate | **Fail** | QA3B-03, QA3B-04 |
| QA2-23 a stopped Delta connector is quiet | **Pass** | `credit-store` stopped 16:05 and `collateral-store` 16:15: no `RejectedExecutionException` or stale problem in the next 40 to 50 minutes |
| QA2-201 demo market data in a fresh lake | **Pass** | FX-EURUSD, AUDUSD, USDJPY over 60 days: MID inside BID/ASK on 60 of 60, 59 to 60 distinct mids; bonds `BND-BRVERD054608`, `BND-BRVERD178449`: 60 distinct yields, the yield never still or moving with the price on a price move, 26 distinct durations |
| QA2-203 `iceberg-external`, empty folder | **Pass** | *no table qa.ghost: looked in …/qa/ghost/metadata for version-hint.text, v<N>.metadata.json and a catalog's NNNNN-<uuid>.metadata.json (the folder is empty)*, then *(it holds 00001-abc.metadata.json.tmp)*; `DOWN` (its only table) |
| QA2-204 CLI cut line | **Pass** with QA3B-09 | `cut: Positions: The first 10,000 rows only: …` after `preview`, `file` (pdf) and `file --format xlsx` (15,000 rows, 385 pages landscape) |
| QA3A-01 data-load signals coalescing | **Pass** for health and answers; **Fail** for settling | QA3B-07 |
| QA3A-02 pack choice, 4 workers | **Pass** | `rita` 7 → 2 → 7 packs twice: 80 of 80 pages right |
| Admin switches a pack for everyone | **Pass** | world-economy off and on: 0 wrong of 321 pages |
| A user's roles changed in Admin → Users | **Pass** | BI menu right at once on 323 of 323 pages |
| BI powers on a role (Admin → BI settings) | **Fail** | QA3B-06 |
| The BI workspace | Not run as its own test | workspace pages and members are read from the server on each request (no per-worker copy); used throughout the burst and the workflow below without a stale answer |

## Fresh ground

- **Bursting to masked recipients.** One subscription, five recipients, run as pdf and as xlsx (six files read cell by cell):
  `rita` (raw) every desk and book; `hana` (row rule `trade.DESK in user.desks`, desk DESK-FX) only DESK-FX in every band, card and
  matrix (1 page, 1,428 trades); `ann` (no raw, model masks `trade.MTM`, `trade.PORTFOLIO`) MTM `•••` on the card, the table, every
  matrix cell and every Excel cell, PORTFOLIO `•••` and not grouped on (one row per desk), notional and counts shown, the sort note.
  No figure of another desk or a masked value in any file or note. Table totals are not drawn on statements (documented), so not checked.
- **Cassandra over TLS.** `tools/load-cassandra.sh … --setting tls.enabled=true --setting tls.ca-file=… tls.cert-file=… tls.key-file=…`:
  26,910 rows in 88 s over mutual TLS. A connector file in the `tls:` form of `config/connectors.examples/other/cassandra-tls.yaml`:
  `UP (TLS certificate CN=drishti (tls.cert-file) expires in 19 days (2026-11-09))`; Test lists `cva` on three dates (66 rows each);
  `cva/CVA-ALDERSHOT-FRA` read from it as of 2026-09-30 and its history 10 days. Without the client certificate: `DOWN` (QA3B-05, 11).
- **Iceberg through a REST catalog on S3.** S3Mock and the REST fixture (MinIO not reachable here): `load-iceberg.sh --catalog rest`
  26,910 rows into 47 tables in 18 s (after QA3B-12's bucket); connector `UP`; `sa-ccr/SACCR-ALDERSHOT-FRA` read from it and its history
  10 days. Test says *latest data only; this connector does not list its dates*, yet history finds ten days.
- **Dialogs in Terminal Crimson, Terminal Green and light.** New user, New role, New connector (Admin) and the keys dialog (BI): no text
  under 4.5:1 (3:1 for large text) in any of the 12, focus starts on the first field, Tab follows the form top to bottom
  (`img/qa3b-new-role-light.png`). BI Studio's other editors are panels, not dialogs; the Paper tab is above.
- **A BI author's whole workflow, twice** (through the console's own endpoints, pages opened at each step). In the personal workspace:
  a blank model, its YAML checked and saved, a blank report with a statement page, the draft previewed (1 page); publishing is refused
  with *personal drafts are private: move the item to a shared workspace to publish it*; the subscription form opens from the report,
  saves, and *Run now* gives `1 sent, 1 skipped` (the author's own draft; `rita` may not see it). In a new shared workspace with `rita` as
  viewer: model and report published, the same subscription `2 sent`. No dead end; the subscription form does not say that a statement
  page sends one statement per person (it offers `html` first).
- **Load.** Several thousand console requests (the worker tests above) and about 3,100 server requests in the burst; the console's four
  workers logged no traceback.

## Not covered

MinIO (the image could not be pulled), the Iceberg REST catalog over TLS, table totals on statements (not drawn by design), a second
run of QA3B-07 (one heavy run), every BI Studio editor one by one by keyboard (only the dialogs above and the Paper tab).

All processes started for this round (server, console, SMTP sink, the Testcontainers holder and its three containers, the lake build)
were stopped by their PIDs, and the scratch data and the test PKI folder were deleted.
