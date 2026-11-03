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
# The data inbox: files dropped in a folder, loaded into the lake automatically

*Release 2.9.11.f (product owner, 2026-11-03): "an automatic data loader: a `dataload/inbox` and `dataload/outbox` folder inside data. I
will place jsonl, jsonl.gz, tar, tar.gz etc. files there and every file will also have a `<filename>.ready` file. Every 10 minutes the
system should see how many ready files there are and ingest them using Java into Delta. If a file named `ready.all` is there it should
ingest all files. Once a file is ingested it should be placed in the outbox along with its ready file so the system does not process the
same file again. Design it in a robust way. Once a file has been loaded and committed to the lake the server also needs to know that
data has moved. A separate Maven module; it can work as a thread in the server." Status: **built** (module `drishti-dataload`, the server's wiring in `server.dataload`, `drishti.py dataload`; section 14 says what was built
and what is still (planned)); the questions of section 13 were
decided as recommended by the product owner (2026-11-03).*

## 1. What is reused, never rewritten

| Need | Already in Drishti | Used as |
|---|---|---|
| Read JSON Lines, `.gz`, `.zst`, `.zip`, `.tar`, `.tar.gz` with every archive guard (no `..`, links, devices, bombs; caps on entries, sizes and ratio) | `drishti-lakeload`: `Archives`, `TarStream`, `GzipMembers`, `SourceReader` | the inbox's reader: unchanged |
| Write Delta (and Iceberg) in parallel, **one commit per business date**, a table lock, a run log, a manifest, exit codes 0 loaded, 2 loaded with rejects, 1 failed (nothing committed), 3 locked | `drishti-lakeload`: `LakeLoad`, `Loader`, `DeltaTarget`, `IcebergTarget`, `LoadConfig`, `LoadReport` | the inbox's writer: `LakeLoad.run` with a configuration the inbox builds |
| Map a dataset to its table, the pack's layout and promoted columns | `LoadConfig`, `PackLayout`, `DatasetLoads` | routing, section 5 |
| Tell the server that data has moved: refresh the pack's connectors (coalesced), verify, alerts, smoke, notices, the Data loads page | `drishti-server` `loads`: `LoadService`, `RefreshCoalescer`, `LoadNotifier`, `LoadStore` | called in the same JVM, the same steps as the HTTP data-load signal |
| Schedules, engine switches, busy protection, the data folder, temp files | the server's scheduler, `config/engines/*.yaml`, admission, the data folder and temp-file helper | as every engine |

The new module only **finds, claims, routes, records and moves** files; everything that reads or writes data is the loader that exists.

## 2. The folders

All under the data folder (`drishti.dataload.dir`, default `<data>/dataload`), on one file system so a move is an atomic rename:

| Folder | Holds | Who writes |
|---|---|---|
| `inbox/` | the files and their `.ready` markers, and `ready.all` | the person or the upstream job |
| `inprocess/<run>/` | files claimed by a run, while they load | Drishti |
| `outbox/<business day>/<run>/` | each loaded file with its `.ready` and a report `<file>.loaded.json` | Drishti |
| `failed/<business day>/<run>/` | a file that could not be loaded, its `.ready` and `<file>.failed.json` (the reason in words, the loader's report) | Drishti |
| `ledger/` | one line per file handled (JSON Lines): name, size, SHA-256, run, outcome, tables and versions committed | Drishti |

`inprocess`, `failed` and `ledger` are additions to the two folders asked for: without them a crash in the middle, a bad file, or the same
file dropped twice cannot be handled safely (sections 7 and 8).

## 3. The hand-shake

1. The writer puts the data file in `inbox/`, then, **when it is complete**, an empty `<file>.ready` beside it (`trades-2026-11-03.jsonl.gz`
   and `trades-2026-11-03.jsonl.gz.ready`). A file without its `.ready` is never touched.
2. **`ready.all`** in `inbox/` makes every data file in the inbox ready in that cycle, with or without its own `.ready`; after the cycle
   `ready.all` goes to the outbox of that run, so it applies once.
3. **Stable files only**: a file is taken when its size and modification time have not changed for `stable-seconds` (default 30), even
   with a `.ready`, a second guard against a writer that sets the marker early (and a must under `ready.all`).
4. Names: visible names only (no leading dot), no links (refused into `failed/`), extensions `.jsonl`, `.ndjson`, `.json`, each plain or
   `.gz`/`.zst`, `.zip`, `.tar`, `.tar.gz`/`.tgz`. Anything else is moved to `failed/` with the reason.
5. **Optional contents of `.ready`** (YAML or JSON; empty is fine): `pack`, `kind`, `table`, `business-date`, `mode`
   (`replace` the dates it holds, or `append`; the default is the table's `load-mode` in the lake configuration, LAKE_CONFIG.md, and `replace` until that exists), `sha256` and `rows` (checked before and after loading). They override the
   routing of section 5 for that file.

## 4. One cycle

**Runs never overlap** (product owner, 2026-11-03: "move all files into an inprocess folder, a timestamped subfolder inside it, so that
if a load takes more than 10 minutes another schedule does not start a redundant load or a conflict"). A run's id is its start time and
node, `20261103T120000Z-drishti-main`, and names its folder `inprocess/<run>/`. A cycle that is due while another run still holds the
inbox lock does not start: it is recorded as *skipped: run 20261103T115000Z-drishti-main still loading (12 min)* and the next cycle
checks again; **Load now** says the same. Files are moved into the run's folder before anything is read, so a file is in exactly one
place: the inbox (waiting), one run's folder (loading), the outbox (loaded) or failed.


Every `interval` (default 10 minutes, `drishti.dataload.interval`; also **Load now**, `POST /api/v1/admin/dataload/run` and `drishti.py dataload run`):

1. **One runner.** Only the node configured as the inbox's owner runs (`drishti.dataload.owner: true`); and the run takes
   `dataload/inbox.lock` (created atomically, holding the node and a heartbeat; a lock whose heartbeat is older than three intervals is
   taken over and the takeover logged), so two servers on one shared folder never both run.
2. **Busy check.** When busy protection says memory or CPU is short, the cycle is skipped and says so; the next cycle runs.
3. **Take the whole batch, greedily** (product owner, 2026-11-03: "a greedy way to handle the batch"): every file that is ready (it has
   its own `.ready`, or `ready.all` is in the inbox) and stable at that moment is claimed at once; a file without its `.ready` when no
   `ready.all` is there is never taken, in one step, into the run's folder, oldest first; files that become ready while the run
   loads wait for the next cycle. `max-files` and `max-gb` are off by default (no limit); an administrator may set them to bound one run,
   and then the rest stays in the inbox for the next cycle.
4. **Claim**: each file and its `.ready` are renamed into `inprocess/<run>/`; the ledger line `claimed` is written first.
5. **Skip duplicates**: a file whose SHA-256 the ledger already shows as `loaded` goes straight to `outbox/` with *already loaded in run
   … on …*, and is not loaded again (`drishti.dataload.reload-duplicates: false`).
6. **Route** (section 5) and **load**: the files of one table go to one `LakeLoad` run (its own table lock; one Delta commit per business
   date, as today). Tables load one after another, so the server's memory is bounded by one load.
7. **Record**: the ledger line `loaded` with the tables, dates, Delta versions, rows and rejects.
8. **Move** each file, its `.ready` and its report into `outbox/<day>/<run>/`.
9. **Tell the server**: one data-load signal per pack, kind and business date, `ready`, through `LoadService` in the same JVM: the pack's
   connectors refresh (coalesced), the dates and views include the new data, verify, alerts, smoke and the notices run, and the Data loads
   page shows the load with its files. The VaR engine reloads a date whose inputs changed, as for any data-load signal.

## 5. Routing a file to a table

In this order: the `.ready` contents (section 3.5); a **row form** that names itself (*loader rows* `{domain, kind, id, date, doc,
columns}` and *lake rows* with `table`, DATA_GUIDE section 2): the parallel loader reads documents and has no reader for these forms, and
the inbox writes no second one, so such a file is **refused** into `failed/` with the reason and the tool that loads it (`load_rows.py`
for lake rows, the store loaders for loader rows); the **rules** in `config/dataload.yaml`: a file-name pattern to a pack and
kind, with the date taken from the pack's date field or from the name:

```yaml
dataload:
  rules:
    - match: "trades-endur-*.jsonl.gz"        # glob on the file name
      pack: trading
      kind: trade
      connector: trade-endur                  # the table that connector reads
      date-from: field                        # the pack's date field (ingest.trade.date), or: name  (YYYY-MM-DD in the name)
    - match: "scenario-pv-*.jsonl.gz"
      pack: market-risk
      kind: scenario-pv
  unmatched: fail                             # fail (the default): into failed/ with the reason; never guessed
```

An archive follows the loader's own rule: one dataset named after the archive, or one per top-level folder.

## 6. Running in the server, safely

The cycle is a thread of the server, as asked. The **load itself runs as a child Java process** of the server
(`java -cp <the server's jar> com.ash.drishti.lakeload.LakeLoad --config <generated>`) with its own heap (`drishti.dataload.loader-heap`,
default 4g), `nice`, and the server's temp folder: a large or malformed file can never take the server's heap, and the loader's exit code
says exactly what happened. The server waits for it with a time limit (`load-timeout`, default 2 hours), and a hung load is stopped and
its files go back to the inbox with the reason. (One way of loading, the loader that exists, in its own process; no second loader code
inside the server.)

## 7. Crash safety: every step can be repeated

| The server stops after | On the next start |
|---|---|
| claiming, before loading | the files in `inprocess/` with ledger `claimed` go back to `inbox/` |
| a Delta commit, before the ledger says `loaded` | the loader's run log and the table's history show the commit (the loader's manifest names the run): the ledger is completed, the file moved, the signal sent; nothing is loaded twice |
| `loaded`, before the move | the file is moved |
| the move, before the signal | the signal is sent (signals are idempotent per pack, kind, date and batch: `LoadStore` records each once) |

A date's commit replaces that date's rows of that table (the default `mode: replace`), so even a repeated load would leave the same data.

## 8. Failures

| Case | What happens |
|---|---|
| Parse rejects within the limit (`reject-max-percent`, default 0) | loaded (exit 2), rejects listed in the report and on the Data loads page |
| Rejects over the limit, or exit 1 | nothing committed for that file; it goes to `failed/` with the report; never retried by itself |
| Table locked (exit 3) | back to `inbox/`; the next cycle tries again; after three cycles a notice |
| Checksum or row count in `.ready` does not match | `failed/` before loading |
| Unknown file, link, unsupported extension, no rule | `failed/` with the reason |
| Disk below the server's free-space floor | the cycle is skipped and says so |

**Retry** from Admin moves a failed file and its `.ready` back to `inbox/`.

## 9. Configuration and the switch

`config/engines/dataload.yaml` (off by default, Admin → Engines): `enabled`, `owner`, `dir`, `interval`, `stable-seconds`, `max-files`,
`max-gb`, `loader-heap`, `load-timeout`, `reject-max-percent`, `reload-duplicates`, `keep-outbox-days` (default 30; older outbox and
failed folders are deleted, the ledger kept), and `config/dataload.yaml` for the routing rules; every key in CONFIGURATION.md.

## 10. What a person sees

**Admin → Data loads → Inbox** (planned; mock-ups first): files waiting (ready, not yet stable, without a marker), the last cycles with their
files, tables, dates, rows and time, failures with the reason and **Retry**, **Load now**, and the next cycle's time. The command line:
`drishti.py dataload status | run | retry <file>`. Every load is on the Data loads page as today, with *inbox:* and its files as its source.

**Mock-ups** of the Inbox tab (planned screen), each with a quiet and a busy scene (a long run, skipped cycles, Load now answered
*skipped*, `ready.all` waiting, one failed file with Retry), the routing rules read-only, the engine's switch and owner node, and the
folder links: [A](mockups/dataload-inbox/A.html), a status board with three columns that follow the folders (waiting `inbox/`, loading
`inprocess/<run>/`, last cycles `outbox/`), failures, duplicates and rules below, and a phone scene (**recommended**: the columns teach
the hand-shake and answer "stuck, next, loaded?" at a glance); [B](mockups/dataload-inbox/B.html), a timeline of cycles with the
current run's steps, and a side panel of waiting files, failures and the engine; [C](mockups/dataload-inbox/C.html), table-first: one
table of every file of the day wherever it is, then cycles, rules and settings (the cheapest to build, like the Loads tab).

**Chosen** (product owner, 2026-11-03): **A**, the status board; B and C are not built.

## 11. The module

`drishti-dataload`, plain Java (no Spring): `Inbox` (find and the hand-shake), `Claims` (the `inprocess/` folder, lock and heartbeat), `Ledger`,
`Routes` (section 5), `LoaderProcess` (the child `LakeLoad`), `Outbox` (moves and reports), `Recovery` (section 7), `Cycle` (one run of
section 4). The server keeps only the wiring: the schedule, the settings, the bridge to `LoadService`, busy protection and the Admin API.
Architecture rules: the module never writes a table itself (only `drishti-lakeload` does) and never names the server.

## 12. Tests

Unit: the hand-shake (marker, `ready.all`, stability, names, links), claims and the lock (two runners, a stale lock), the ledger
(duplicates), routing, every failure of section 8. Recovery: a fault injected after each step of section 7, then a restart, proving
nothing is lost or loaded twice. Integration: a temporary data folder and lake, files of each format dropped, a cycle run, the Delta
versions, the outbox, the signal received and the connector refreshed; a three-node test where only the owner loads. Windows: the moves
and the lock on NTFS (the Windows workflow).

## 13. Decisions (product owner, 2026-11-03: every recommendation accepted)

| # | Question | Decided |
|---|---|---|
| D1 | `ready.all`: every data file in the inbox, even without its own `.ready`? | **Yes**, with the stability check, and `ready.all` itself moved to the outbox after the cycle |
| D2 | The extra folders `inprocess/`, `failed/`, `ledger/` | **Yes**: needed for crash safety, bad files and duplicates |
| D3 | The load in a child process with its own heap, or inside the server's heap | **Child process**: a big or bad file can never take the server down |
| D4 | A file with no routing rule | **`failed/` with the reason**, never guessed |
| D5 | The same file dropped again (same SHA-256) | **Skipped, to the outbox with *already loaded***; a changed file with the same name is loaded (its dates replaced) |
| D6 | The `.ready` file's optional contents (pack, kind, date, mode, checksum, rows) | **Optional**, empty works |
| D7 | Which nodes run it | **The configured owner only**, plus the folder lock |
| D8 | `tar.tz` in the request | read as **`.tar.gz`/`.tgz`**; `.zip` and `.zst` come with the loader |

## 14. As built

| Part | Where |
|---|---|
| The module, plain Java | `drishti-dataload`: `Inbox`, `Claims`, `Ledger`, `Routes`, `ReadyNote` (the marker's keys), `LoaderProcess` (with the `LoadRunner` contract), `Outbox`, `Recovery`, `Cycle`, `DataInbox` (the schedule, Load now, Retry, the status), `DataLoadSettings`, `Signal` |
| Reused, not rewritten | `LakeLoad` (run as a child process from the server's own class path or exec jar: `-Dloader.main`; its exit codes 0/1/2/3, its summary and the load file it reads), `LakeLoad.reads` (the loader's own file names, `Archives`), `PackLayout` (through `source.pack`: the table, layout, key and date of the pack), the archive guards; `LoadService.announce` for the signal (the method `POST /api/v1/packs/{pack}/loads` calls), `AdmissionGate` (`Work.BULK_LOAD`) for the busy check, `TempFiles` for the loader's temp folder, `BasicEngine` and the engine catalogue for the switch |
| The server's wiring | `server.dataload.DataLoadConfiguration` (settings, packs, signal, busy check, the engine), `DataLoadController` (`GET /api/v1/admin/dataload`, `POST …/run`, `POST …/retry`; the two `POST`s in the `loads:write` scope) |
| Files | `config/engines/dataload.yaml` (off as shipped), `config/dataload.yaml` (no rules; the demo packs' rules commented) |
| Architecture rules | `ArchitectureRulesTest`: the module never depends on the server or Spring, and never on a table format, lake engine or storage (it writes no table: only the loader does) |
| The run's files | `inprocess/<run>/load-<n>.yaml` (the load file), `.route.json` (pack, kind, batch: what a restart reads), `.summary.json`, `.log`, `.rejects.jsonl`; `load-<n>-check.*` for a dry run made when a `.ready` names `rows`; `signals.jsonl` (signals not yet sent). Moved to `outbox/<day>/<run>/loads/` at the end of the run |
| Recovery | a claimed file whose load's summary says it committed is completed from the summary (never loaded again); otherwise it goes back. A crash inside the loader's own write, before its summary, sends the file back and it loads again: its dates are replaced, the same data |
| Cluster | each signal is passed on to every other live member by `loads.LoadForwarding`, the one path a data-load signal takes to the members (a person's announcement goes on as that person): the inbox's as this node, `node:<id>` with the service role, carried on behalf of the node and signed with the cluster key; the receiving member's `LoadController` takes a signed node signal as it takes an administrator's. One hop; a failed load is not passed on |
| Row forms | `RowForms` looks at the first line of a plain, gzip or zstd file: loader rows and lake rows are refused with the reason. An archive's members are not looked at: rows of these forms there are rejected by the load (no pack id field) and the file fails with the loader's reason |
| Batch | one loader run per table (pack, kind, connector, table, date field or date, key, lake, format); when a run of several files fails without a commit, each file is loaded again alone, so one bad file fails alone |

Still (planned): the Admin screen (mock-ups first); the three-node and Windows (NTFS) tests.
