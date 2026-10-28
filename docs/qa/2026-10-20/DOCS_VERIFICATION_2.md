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
# 2.6.6 documentation verification, second pass

The second documentation check of 2026-10-20, for the 2.6.6 (QA clean) release, in the format of
[this morning's check](DOCS_VERIFICATION.md). One row per document changed since that check
(`git log --name-only --format= 22cde0edb..HEAD -- docs README.md data/testdata/README.md | sort -u`, `docs/qa` left out, on `develop`
at a4d671f44), plus the pages this review found wrong by the cross-checks that were not in that list (tutorial 19, the example
connector's `pom.xml`) and the release documents. Reviewed 2026-10-20.

## How to use this table

- **Outcome** values: *wrong → fixed* (this review found it wrong and fixed it, commit c8555ad96); *updated* (changed in this review
  without being wrong); *verified* (read against the code, configuration or a real run named in the row, no change needed);
  *names-only* (only names, paths or version strings changed since the morning; nothing else re-verified).
- **This review ran commands.** Unlike the morning's check, a small server was run (port 18760, sign-in off, the user `wes`) on
  scratch demo lakes built by `tools/build_demo_lakes.py --only market-risk,trades/endur --lake <scratch> --connectors-dir <scratch>`
  (7 tables, sixty business days ending 2026-10-20; no server was signalled), with the jar built from this branch. The file tools
  (`pack make`, `pack new`, `pack regenerate`, `data profile`, `lake scan`, `lake build`, `sutra gen`) and `drishti.py net` ran on
  scratch folders. Everything was removed afterwards.
- **Commit**: the latest commits that touched the file since the morning; c8555ad96 is this review's fixes.

## What the runs found

- **The snapshot rename left stale outputs.** QA1-201 (25a967514) renamed the input of many examples from the seven-day test data's
  `snapshot-2026-09-22.jsonl.gz` to the sixty-day `snapshot-2026-07-08.jsonl.gz`, but the outputs under them were the old file's
  (4,980 ENDUR documents, 4,933 MUREX, 26 start dates, 74 MUREX product types). Re-run and replaced: ENDUR 4,407 documents (CallNot 50,
  Cash 723, ComFut 883, ComSwap 1,994, Commodity 757), 24 start dates; MUREX 4,198 documents, 68 product types; the 2026-09-23 changes
  file 159 trades; `pack regenerate` 273 adds; `sutra gen` on 400 lines 6/65/74/198/57.
- **`bi statement` no-data output** (written by hand by Fix-9b): the real output on the tutorial's report has **2 pages** (its matrix
  has `pageBreak: before`), not 1; the hint line was right word for word. `file` prints the same `hint:` after `wrote …`.
  On the sixty-day lakes as `wes` (sign-in off, no row rules), 2026-10-08 is 234 pages and 10,154 rows with
  `cut: Positions: The first 10,000 rows only …`; the hint for 2026-10-25 names that day and *2026-10-20 (before)*, for 1999-01-01
  *2026-07-27 (after)*.
- **History.** Every refusal the USER_GUIDE and HISTORY_QUERIES list was asked of `POST /api/v1/command` and answered as written
  (`DRS-4022` for `0`, `-5`, `1.5M`, `3X`, `30.`, `1e3`, `FROM yesterday`, `FROM 2026-02-30`, `FROM … TO`, `TO …`, `DIFF DIFF`, `DIFF`,
  `DIFF 30`, `30 30`; `DRS-4021` for `99999`; `３0` read as 30). Plain words (`GET /api/v1/phrase`): `1000279 30`, `since … until …`,
  `this week` with `DIFF`, a search's *left out* step and `1000279 -5` / `since yesterday` as `DRS-4022`, all as written. `drishti.py
  history` ran with `--changes-only`, `--diff`, `--csv`, `--json` and the errors `DRS-4021`, `DRS-4022`, `DRS-1013` (*its days with data
  run from …*), `DRS-4003` (*knownAt '2026-10-20' is not an instant*), `DRS-4004` (`Trade`, `trades`, `--fields NOPE`), `DRS-1001`.
  Wrong: the *FROM after TO* example used `FROM 2026-10-30`, after the business date, for which the server says *… is in the future*.
- **`drishti.py net`.** The loopback refusal now reads `… is a loopback or wildcard address (loopback): …` and is followed by an
  `error node.yaml:<line>` line; also run: `localhost.`, `2130706433`, port `0`, a trailing dot, `169.254.1.1` (*link-local*), the
  machine's own name (*oryxpro is 127.0.1.1 here: loopback*), and `http://[2001:db8::5]:18611` (accepted). The CLI_GUIDE sequence
  (`net list`, `set node.enabled true` without a key, `set node.secret`, `add-peer`, `net check` with `fanout: lots`) gave the page's lines.
- **`build_demo_lakes.py`.** `--help` lists every option the Windows guide and TOOLS name; `--dry-run` and the real run print `1/3 …`,
  `2/3 no --endur-dir or --source-dir: the synthetic trades stay`, `3/3 done in 534 s.`, the closing lines (*A server already running picks
  the new tables up by itself at its next refresh; restart it to be sure, or pass --server next time*) and *Then try: TRD 1000279 60 …*;
  a progress line every ten committed days (`trading/trade_endur: committed 20/60 days, 90,023 rows so far (through 2026-08-21)`), and
  `Demo lake ready: …: 7 tables, 60 business days …, built in 530 s`.

## Waits for a code fix

| Finding | Page and statement |
|---|---|
| DOCS2-01 | QA_ON_WINDOWS_WITH_CUSTOM_DATA 3.0 (and the QA3A-07 CHANGELOG entry): the heartbeat should name the table being written (`writing trading/trade_endur`); in this run it named Drishti's own work folders, `writing _drishti-lake/staging` and `writing _drishti-lake/locks`, for the whole build. `tools/build_demo_lakes.py` `lake_progress` takes the newest file anywhere under the lake, `_drishti-lake/` included. The page is left as it is. |

## Cross-checks

| Check | Result | How it was checked |
|---|---|---|
| Release order: 2.6.6 QA clean → 2.6.7 (BI runs, gossip off the busy path and HTTP/2, chart any table, mixed Delta/Iceberg storage) → 2.7.0 write-back (item 0 the one database) → 2.8.0 AI | **fixed**: BI_DESIGN's release order, wave label and *Also in 2.6.7*, and IMPLEMENTATION_PLAN's two longer release paragraphs left out mixed storage (its line 11 and table row had it). Right in BI_RUNS, TABLE_CHARTS, DRISHTI_NET (*Planned, 2.6.7*), DEMO_DATA (*planned, 2.6.7*, described as a design, not built), DATABASE (*2.7.0 item 0, design, not built*), WRITE_BACK, SUPER_SCALE, docs/README. No page documents a 2.6.7 feature as built | `grep -n "2\.6\.7"` over `docs/architecture`, docs/README, DEMO_DATA |
| Error codes | every `DRS-` code the changed pages name exists in `ErrorCode` (`DRS-1015` added for the restart wait); the docs tests passed | `test_docs_error_codes.py` |
| Settings | `drishti.loads.refresh-window`, `max-queued-reads`, `rebuild-delay`, `ui.user_cache_seconds`, `server.workers`, `auth.recheck_seconds` are in the catalogue and `application.yaml` (console: `user_cache_seconds: 10`) | `test_docs_settings.py`; `grep` |
| Help addresses | every link and catalogued slug resolves | `test_help*.py`, `test_docs_*.py` |
| Nineteen plugins | **fixed**: `docs/guides/examples/connector/pom.xml` said *not one of the seventeen plugins*. `plugins/` holds 19; the deck now says nineteen (42c55ba00) | `ls -d plugins/*/ \| wc -l`; `grep -rniE "(seventeen|eighteen) plugins" docs` |
| Ten themes | right everywhere outside `docs/qa` and old plan rows | `grep -rniE "(nine|eleven) themes"` |
| Twenty-one tutorials | right (21 numbered tutorials) | `ls docs/tutorials/[0-9]*.md \| wc -l` |
| 66 tables | right in GETTING_STARTED (twice), PACKS, DEMO_DATA, tutorial 1 (twice), the Windows guide, data/testdata/README; no *64 tables* left; the manifest has 66 datasets | `grep -rn "6[0-9] tables"`; `testdata.yaml` |

## Counts

51 rows (verified: 33, wrong → fixed: 14, names-only: 3, updated: 1). The table is sorted by path.

## Documents

| Document | Outcome | What was checked | Commit | How to re-check |
|---|---|---|---|---|
| `CHANGELOG.md` | updated | An *Unreleased* line for this review. The Unreleased entries since the morning read against the pages they name (the rows below). | c8555ad96 | `sed -n 18,22p CHANGELOG.md`. |
| `RELEASE_NOTES.md` (/help/release-notes) | verified | 2.6.5's *Across a cluster* now says the QA1-01/02 fix after the release: the history cache keyed by the peers' versions (HISTORY_QUERIES 11, `SourceRouter.peerVersion`). Its section has no roadmap line; 2.6.0's roadmap is that release's and stays. | earlier | Open /help/release-notes. |
| `data/testdata/README.md` | wrong → fixed | The dataset table was the seven-day data's (*2026-09-22 to 2026-09-30 in the files*, *Day 7*, ENDUR day 1 4,980, 3.92 MB): rebuilt from `testdata.yaml` for every one of its rows (day 1, changes a day, day 60, MB as bytes / 10⁶); the dates 2026-07-08 to 2026-09-30. The market-day paragraph (QA2-201), *66 tables*, the regenerate commands checked. | c8555ad96, c06c04e72, 032589fd6 | Compare a row with `testdata.yaml`'s `days` for that folder. |
| `docs/README.md` (/help/docs-readme) | verified | Rows for BI_RUNS and TABLE_CHARTS (*design, release 2.6.7*), DATABASE (*design, 2.7.0 item 0*), WRITE_BACK (2.7.0, not built) and the AWS Delta lake deck; links resolve. | 47e601387, 97eb3d29c, 6b4d96613 | The docs tests check the links. |
| `docs/admin/CONFIGURATION.md` (/help/configuration) | verified | `refresh-window` (5s), `max-queued-reads` (4096), `rebuild-delay` (5s), `server.workers` and the user stamps (`core/user_stamps.py`), `ui.user_cache_seconds` 10, `auth.recheck_seconds`; the `ReadSlots` refusal text (*… reads already wait …*). | 5155e669e, b370838ef, 76bf4c6df | `grep -n user_cache_seconds drishti-console/config/application.yaml`. |
| `docs/admin/NET.md` (/help/docs-admin-net) | wrong → fixed | The loopback refusal lacked `(loopback)` and the `error node.yaml:2 …` line (real run). The other spellings (`localhost.`, `2130706433`, link-local, the machine's `127.0.1.1` name), the address rules (port 0, trailing dot), joining once ready (SOAK-03), `DRS-5052` for a down or refusing peer (SOAK-01/02) and the refusal log lines (SOAK-06) read against `NetRouting`, `PeerSources`, `SourceHistory`. | c8555ad96, f90ad2ec9, 1e743d8f4 | `drishti.py net set node.advertise-url http://127.0.0.1:18784 --dir <empty folder>`. |
| `docs/admin/OPERATIONS.md` (/help/operations) | verified | `DRISHTI_SESSION_RECHECK_SECONDS`: at once on every worker of the console where it was done (user stamps). | b370838ef | As CONFIGURATION. |
| `docs/admin/PERFORMANCE.md` (/help/docs-admin-performance) | verified | *Resident memory* (SOAK-05): the settings `MALLOC_ARENA_MAX=2`, `-XX:MaxDirectMemorySize`, `-XX:TrimNativeHeapInterval` and the `jcmd` commands; the measured figures are the commit's, not re-run. | 02c1bf1c0 | `jcmd <pid> VM.native_memory summary` on a server started with `-XX:NativeMemoryTracking=summary`. |
| `docs/admin/USER_MANAGEMENT.md` (/help/user-management) | verified | Admin → Tokens shows scopes and last use in the console's time zone; role changes reach every worker. | b370838ef, c654444df | Admin → Tokens. |
| `docs/architecture/BI_DESIGN.md` | wrong → fixed | Release order 12.2, the wave label R and *Also in 2.6.7* left out mixed Delta and Iceberg storage: added, linking DEMO_DATA's design section. Wave R is *planned*. | c8555ad96, 47e601387, 97eb3d29c | `grep -n "Mixed Delta" docs/architecture/BI_DESIGN.md`. |
| `docs/architecture/BI_RUNS.md` | verified | Status *design, not built*, release 2.6.7 between 2.6.6 and 2.7.0 (release order and status only). | 5bb95bd59, 97eb3d29c | Line 18. |
| `docs/architecture/DATABASE.md` | verified | *Design, not built*, 2.7.0 item 0 before write-back (release order and status only). | 6b4d96613 | Line 18. |
| `docs/architecture/DRISHTI_NET.md` | verified | *Planned, 2.6.7: the transport between nodes* is a plan; section 17.1's soak options all exist in `tools/bench/net_soak.py --help` (`--history-share`, `--history-sample`, `--equities`, `--kill-grace`, `--settle`, `--sample-every`, `--warmup`, `--max-growth`, `--max-p99`, `--days`, `--dates`, `--trades`), `users.py` has `--history-share` and `--equities`; `earliestDate` in the digest (`SourcePlugin.earliestDate`). The soak was not run. | 47e601387, 422f3bd34, f90ad2ec9 | `python3 tools/bench/net_soak.py --help`. |
| `docs/architecture/HISTORY_QUERIES.md` | wrong → fixed | The code table's `DRS-4022` example was *FROM 2026-10-30 is after TO 2026-10-19*, a future FROM (the server says *is in the future*): now both messages, as the server gives them. Section 15 refusals, the peer rules of section 12 (`downHolders(kind, date)`, `peerVersion`), `holdsNone`/`NOT_HELD`, `knownAt` `DRS-4003` and `DRS-1013` texts read against the code and the real answers above. | c8555ad96, a4d671f44, 521bf3253 | `drishti.py history trade 1000279 --from 2026-10-15 --to 2026-10-01`. |
| `docs/architecture/IMPLEMENTATION_PLAN.md` | wrong → fixed | The two release paragraphs (*Next releases*, *Next*) listed 2.6.7 without mixed storage: added. Line 11 and the 2.6.7 table row had it. | c8555ad96, 5be91e227, 47e601387 | `grep -n "mixed Delta and Iceberg" docs/architecture/IMPLEMENTATION_PLAN.md`: four lines. |
| `docs/architecture/SUPER_SCALE.md` | verified | The new paragraph points at BI_RUNS, release 2.6.7 (release order only). | 97eb3d29c | Line 28. |
| `docs/architecture/TABLE_CHARTS.md` | verified | *Release 2.6.7*, a design; the *not in 2.6.7* list (release order and status only). | 521bf3253, 10341bdf9, 47e601387 | Line 18. |
| `docs/architecture/WRITE_BACK.md` | verified | 2.7.0, not built; item 0 the one database (DATABASE.md) (release order and status only). | 6b4d96613 | docs/README's row. |
| `docs/connectors/CASSANDRA_CONNECTOR.md` (/help/docs-connectors-cassandra-connector) | verified | `${DRISHTI_CASSANDRA_USERNAME:}` / `PASSWORD:` defaults in `config/connectors.examples/cassandra/`; the `DRS-1001 … which kind 'trade' is routed to, is disabled` text (`SourceRouter`); `DRISHTI_LOADER_HEAP`. | 357dbc752, 455dabd2d | `grep -rn "DRISHTI_CASSANDRA_USERNAME:" config/connectors.examples`. |
| `docs/connectors/CONNECTOR_FILES.md` (/help/connectors) | verified | `${X:}` empty default; the restart wait and `503 DRS-1015 connector … is restarting` (`SourceRouter`, `ErrorCode`); a disabled routed connector beside another (`DRS-1001` text). | 7cbd0cffe, d52c34754, 357dbc752 | `grep -n DRS-1015 drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java`. |
| `docs/connectors/DELTA_CONNECTOR.md` (/help/docs-connectors-delta-connector) | verified | `max-queued-reads` 4096 (`plugin-settings.yaml`, `DeltaSourcePlugin`), the refusal text (`ReadSlots`). | 76bf4c6df | `grep -n max-queued-reads drishti-engine/src/main/resources/drishti/plugin-settings.yaml`. |
| `docs/connectors/DEMO_DATA.md` (/help/docs-connectors-demo-data) | verified | *66 tables*; *Mixed Delta and Iceberg storage (planned, 2.6.7)*: every command is marked *planned* (`--store` does not exist yet: `build_demo_lakes.py --help`), so it is not documented as built; `test_deterministic.py` exists. | 5be91e227, ed330b5f8, c06c04e72 | `python tools/build_demo_lakes.py --help`: no `--store`. |
| `docs/connectors/ICEBERG_CONNECTOR.md` (/help/docs-connectors-iceberg-connector) | verified | The moved-table messages and `relocate` with the library engine (`TableProblems`, `IcebergSourcePlugin`), *… is readable again*, `${DRISHTI_ICEBERG_CREDENTIAL:}`, `config/connectors.examples/iceberg/trade-endur.yaml` exists, `max-queued-reads`. | 76bf4c6df, b79ce17ea, 3a09ee2e4 | `ls config/connectors.examples/iceberg/trade-endur.yaml`. |
| `docs/connectors/ICEBERG_EXTERNAL_CONNECTOR.md` (/help/docs-connectors-iceberg-external-connector) | verified | The *no table … looked in … (the folder is empty)* text (`PathCatalog`), *it holds …*, *the folder does not exist*, DEGRADED when a configured table is not served (`IcebergExternalPlugin.health`). | 3758529e4, a4cb40c14 | `IcebergExternalPluginTest`. |
| `docs/guides/API_GUIDE.md` (/help/api-guide) | wrong → fixed | The statement preview row lacked `cut`, `hint` and the `exact` role; the file row lacked `X-Statement-Hint` (`StatementController`). `earliestDate` in the digest, `DRS-1015`, the `DRS-9164` texts (`MqBindings`) checked. | c8555ad96, f90ad2ec9, 80d626def | `grep -n "X-Statement-Hint" docs/guides/API_GUIDE.md`. |
| `docs/guides/BI_GUIDE.md` (/help/bi-guide) | verified | A role's BI powers reach every holder on every worker (user stamps). | 5155e669e | As CONFIGURATION. |
| `docs/guides/CLI_GUIDE.md` (/help/cli-guide) | wrong → fixed | `pack make`, `pack new` (MUREX), `pack new`/`pack regenerate` (the worked run, both the dry run and the `--priority 20` conflict) and `data profile` showed the seven-day snapshot's output under the renamed `snapshot-2026-07-08` input: re-run and replaced. The `bi statement` no-data example was hand-written with *1 page*: the real output (2 pages) and `file`'s hint line; `full.pdf`'s size. The `net set` loopback refusal text. `bi statement` `cut:` line, `history` options and the `load-files.sh` paragraph checked. | c8555ad96, 579b7d830, 80d626def | Run the `pack make` command of the quick path with `--out <scratch>`: `read 4407 trade document(s)`. |
| `docs/guides/DATA_LOADS.md` (/help/data-loads) | verified | `drishti.loads.refresh-window` and the merged-refresh step result. | 76bf4c6df | `grep -n refresh-window drishti-api/src/main/java/com/ash/drishti/api/SourcePlugin.java`. |
| `docs/guides/DEVELOPER_GUIDE.md` (/help/developer-guide) | verified | *66 Delta tables*; `test_deterministic.py`. | ed330b5f8, c06c04e72 | `ls tools/testdata/test_deterministic.py`. |
| `docs/guides/DISTRIBUTION.md` (/help/distribution) | verified | Skipped recipients in words, a disabled user named, masked sort note, Excel notes, *· 1 page* subject, the Subscribe form's statement note (commit messages and `StatementApiTest`); export `max-rows` paragraph. Not re-run (no mail). | 5e36cc426, 66ff631f8, 01ad4477e | Subscribe on a statement page. |
| `docs/guides/GETTING_STARTED.md` (/help/getting-started) | verified | `Demo lake ready: …: 66 tables, … built in 943 s` matches `demo.py`'s format (`built in N s`); *66 tables* on Windows. | df1c8f5a6, c06c04e72 | `drishti.py lake demo --as-of data`. |
| `docs/guides/IDE_GUIDE.md` (/help/ide-guide) | names-only | The snapshot file name only (25a967514). | 25a967514 | `ls data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz`. |
| `docs/guides/OPERATIONALISING.md` (/help/operationalising) | names-only | The snapshot file name only (25a967514). | 25a967514 | As IDE_GUIDE. |
| `docs/guides/PACKS.md` (/help/packs) | verified | *66 tables* and the `Demo lake ready` line's format. | df1c8f5a6, c06c04e72 | As GETTING_STARTED. |
| `docs/guides/PACK_DEVELOPER_GUIDE.md` (/help/pack-developer-guide) | verified | `lake maintain` finds a table by its folder and keeps its partitions (8d31cfa32); a bad `history:` block is the pack's problem on Admin → Packs and in Health. | 8d31cfa32 | `drishti.py lake maintain --help`. |
| `docs/guides/QA_ON_WINDOWS_WITH_CUSTOM_DATA.md` (/help/docs-guides-qa-on-windows-with-custom-data) | verified | `build_demo_lakes.py`'s options (`--files-only`, `--source-dir`, `--system`, `--only`, `--as-of`, `--keep-synthetic`, `--server`, `--token-file`, `--dry-run`), the numbered steps, the ten-day progress line and the closing lines, run for real (see above); *66 tables*. The heartbeat's *writing trading/trade_endur* **waits for fix DOCS2-01**. | df1c8f5a6, c06c04e72, 6e7bb36d6 | `python tools/build_demo_lakes.py --only trades/endur --lake <scratch> --connectors-dir <scratch> --dry-run`. |
| `docs/guides/REPORTS.md` (/help/reports) | verified | 21: the `DRS-9037` text (`FieldParams`: *… trade.DESK, trade.PORTFOLIO, fields: put it in a field well*); 22: a figure never cut (`StatementFit`). | 02e60df21, b50b0aa83 | Put `param.breakdown` in a values well: Problems. |
| `docs/guides/REPORTS.md` (/help/docs-guides-statements) | wrong → fixed | The no-data example was hand-written (*1 page*): the real output on the tutorial's report (2 pages, the matrix on its own page) and the sentence before it. The hint's wording for `dates: end` (2026-10-25 names that day, nearest *2026-10-20 (before)*; 1999-01-01 *2026-07-27 (after)*), `cut` at 10,000 rows and the `cut:` line, run for real; the figure rules read against `StatementFit` and `StatementLayout` (`exact` role). | c8555ad96, 02e60df21, 80d626def | `drishti.py bi statement preview <report> --date 9999-12-31`. |
| `docs/guides/SUTRA_DEVELOPER_GUIDE.md` (/help/sutra-developer-guide) | wrong → fixed | `sutra gen` on the first 400 lines of the renamed snapshot: the group counts were the old file's (7, 67, 79, 187, 60): re-run (6, 65, 74, 198, 57). | c8555ad96, 25a967514 | Run the page's three commands. |
| `docs/guides/TOOLS.md` (/help/tools) | wrong → fixed | 4.1 to 4.5 and the connector test showed the old snapshot's counts (4,980; 189 changes; the reject lines 190/191; 5,169 rows): `lake scan` and `lake build` re-run (both engines) and the counts replaced (4,407; 159; 160/161; 4,566); run ids and times kept. 4.9's `--data-dir`, `--signal-once`, *refused* and *did not answer* and `built in …, the server told in …` checked against `tools/lake/demo.py`; the net soak row and the two-hour command's options exist. | c8555ad96, df1c8f5a6, 422f3bd34 | Section 4.2's command into a scratch lake: `documents read: 4407`. |
| `docs/guides/TROUBLESHOOTING.md` (/help/troubleshooting) | verified | The address spellings and `(oryxpro is 127.0.1.1 here: loopback)` (real run), `DRS-1001` routed-and-disabled text, `DRS-1015`, `DRS-9164`. | 7cbd0cffe, b50b0aa83, 357dbc752 | As NET. |
| `docs/guides/USER_GUIDE.md` (/help/user-guide) | wrong → fixed | *A range that cannot be read*: every refusal asked of the server, as written; but *`TRD 0` opens trade `0`*: it looks up the id `0` (a pick list when no trade is `0`), so now *stays an id, never a range*. Plain words (`since … until`, `1000015 30`, `fx audusd`, a search's *left out*), *Open the day* (`&via=history`), export and link read against the code and the phrase endpoint. | c8555ad96, eccb5a8fa, 0236fb8f7 | `POST /api/v1/command {"text": "TRD 1000279 -5"}`: `DRS-4022`. |
| `docs/guides/examples/connector/pom.xml` | wrong → fixed | *not one of the seventeen plugins*: nineteen. | c8555ad96 | `ls -d plugins/*/ \| wc -l`: 19. |
| `docs/publications/Drishti-AWS-Delta-Lake.pdf` | names-only | Added with the deck; listed in docs/README (commit message). | c0e0f2a1e | Open it. |
| `docs/publications/Drishti-AWS-Delta-Lake.pptx` | verified | Listed in docs/README with its slide count; not opened in this review. | c0e0f2a1e | Open it. |
| `docs/publications/Drishti-Design-and-Evidence.pptx` | verified | Three slides now say nineteen plugins (commit message). | 42c55ba00 | Open it. |
| `docs/tutorials/01-first-15-minutes.md` (/help/docs-tutorials-01-first-15-minutes) | verified | *66 tables* twice; the `Demo lake ready` line's `built in` format. | df1c8f5a6, c06c04e72 | As GETTING_STARTED. |
| `docs/tutorials/17-run-it-in-production.md` (/help/docs-tutorials-17-run-it-in-production) | verified | Admin → Tokens shows scopes and last use in the console's time zone (USER_MANAGEMENT). | c654444df | Admin → Tokens. |
| `docs/tutorials/19-a-cluster-of-three.md` (/help/docs-tutorials-19-a-cluster-of-three) | wrong → fixed | Not changed since the morning; the loopback refusal it shows lacked `(loopback)`, as NET. | c8555ad96 | As NET. |
| `docs/tutorials/20-statements-and-field-parameters.md` (/help/docs-tutorials-20-statements-and-field-parameters) | verified | The `DRS-9037` text (`FieldParams`). The statement's YAML (model and report from `docs/tutorials/data/`) installed and previewed for real: the bands, header, footer (*Run … · by DESK* / *by PORTFOLIO*), the matrix on its own page; its captured page and row counts (*3 pages, 66 rows*) are of a desk head's statement on the seven-day lakes and were not re-captured (as `wes` on the sixty-day lakes: 234 pages, 10,154 rows, the table cut at 10,000). | b50b0aa83 | `drishti.py bi item new me docs/tutorials/data/desk-params.model.yaml`, the same for the report, then `bi statement preview <id> --date 2026-10-08`. |
| `docs/tutorials/21-an-entity-over-time.md` (/help/docs-tutorials-21-an-entity-over-time) | wrong → fixed | The *dates the wrong way round* row used `FROM 2026-10-30` (after the business date: *… is in the future*): now `FROM 2026-10-15 … TO 2026-10-01`, the real message, with the future case named. The other rows (`DRS-4021`, `DRS-4022` list, `DRS-1013` with *its days with data run from …*), *Open the day*, export and link checked; the EQ headline format (`chg +0.50 (+2.63%)`) against `tools/historycmd.py` only (the scratch lakes held no equities). | c8555ad96, eccb5a8fa, cdb8e84f4 | `drishti.py history trade 1000279 --from 2026-10-15 --to 2026-10-01`. |
