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
# Data lake and data load guide

How Drishti keeps data in a lake and how your files get into it, with real, runnable examples. It is written for the person who loads a
bank's own data: trades, positions, the scenario PVs that VaR is computed from, and the VaR hierarchy's nodes, into a lake of their own
(called `risklake` throughout), beside the demo lake and never mixed with it.

The hands-on walk-through of the same, every step in the UI, on the command line and in YAML, is
[tutorial 45, Load your own data into a new lake](../tutorials/45-load-your-own-data-into-a-new-lake.md). Connecting a source that is
not a lake (a database, a file folder, Kafka) is the [data guide](DATA_GUIDE.md); telling Drishti that an outside job has loaded data is
its section 1. The in-memory lake (feeds that may never touch a disk) has [its own guide](IN_MEMORY_LAKE_GUIDE.md).

Every example below uses invented, Endur-style data (`TRADE_ID`, `PRODUCT_TYPE`, `DESK`, `BOOK`, `COUNTERPARTY_ID`); outputs marked
*captured* were produced on a scratch data folder with 2,000 trades, 300 positions of 509 scenarios and 26 hierarchy nodes.

## Contents

1. [What a lake is](#1-what-a-lake-is)
2. [The lake file, every key](#2-the-lake-file-every-key)
3. [Loading, three ways](#3-loading-three-ways)
4. [The `.ready` file, every key](#4-the-ready-file-every-key)
5. [Routing, business dates, modes, repeats and undo](#5-routing-business-dates-modes-repeats-and-undo)
6. [Pointing Drishti at a lake](#6-pointing-drishti-at-a-lake)
7. [The demo lake's one-time move](#7-the-demo-lakes-one-time-move)
8. [Checking a load, and troubleshooting](#8-checking-a-load-and-troubleshooting)
9. [Sizing: a day of 1M trades and 50K positions × 509 scenarios](#9-sizing-a-day-of-1m-trades-and-50k-positions--509-scenarios)
10. [Connecting to an existing lakehouse (Databricks, Unity Catalog)](#10-connecting-to-an-existing-lakehouse-databricks-unity-catalog)
11. [Archives: zip and tar](#11-archives-zip-and-tar)
12. [Other lakes, packs and older installations](#12-other-lakes-packs-and-older-installations)

## 1. What a lake is

A **lake** is a set of tables in one place, described by one file: `config/lakes/<lake>.yaml`. Every **writer** (the data inbox,
`drishti.py lake build`, the parallel loader, the demo builder) creates and writes tables exactly as that file says, and every
**reader** (the Delta and Iceberg connectors, and through them every screen, BI, the pivot engine, VaR and Export data) finds its tables
through it. Nobody else holds a root, a table path, a partition column or a promoted column.

**Each lake has its own folder**, `<data>/delta/<lake>/` (Iceberg: `<data>/iceberg/<lake>/`), and under it one folder per domain and
table:

```text
data/delta/
├── demo/                               the demo lake: config/lakes/demo.yaml
│   ├── trading/trade_endur/
│   ├── risk/scenario-pv/
│   └── ...                             16 domains, built by drishti.py lake demo
└── risklake/                           your data: config/lakes/risklake.yaml
    ├── trading/trade_endur/
    │   ├── _delta_log/                 the table's commits
    │   └── business_date=2026-11-06/
    │       ├── PRODUCT_TYPE=IRS/part-00000-....parquet
    │       └── ...
    ├── risk/scenario-pv/
    ├── risk/risk-hierarchy/
    └── _drishti-lake/                  the lake's own state: runs/ (one summary per load), locks/, staging/
data/delta/risk-results/                the official VaR and exposure results the server publishes (their own lake files)
```

`<data>` is the data folder (`drishti.data.dir`, `DRISHTI_DATA_DIR`; `./data` by default). Two lakes never share a folder, so the demo
and your data cannot mix (section 6).

**A table** holds one row per entity per business date:

| Column | Holds |
|---|---|
| `id` | the entity's id (`TRADE_ID` `7000001`), a string |
| `doc` | the whole record as JSON text: what a page shows |
| `business_date` | the business date, always the first partition |
| promoted columns | the fields people filter and search by (`DESK`, `PRODUCT_TYPE` …), with exactly the document's values |
| vectors | list-of-numbers fields (`SCENARIO_PV`, 509 numbers) as `list<double>` columns, out of the document |
| `_load_id`, `_source_file` | which load wrote the row, and from which file: every load can be traced and undone |

**Delta or Iceberg.** A lake is Delta unless its file says `format: iceberg`; one table may differ. On the same rows both give the same
answers, VaR equal to the cent ([tutorial 29](../tutorials/29-var-on-iceberg.md)). An Iceberg table of this layout is partitioned by
business date alone.

**In memory.** A lake whose root is `mem://<store>` lives in the server's memory under a hard cap, for feeds that may never be written
to disk: [IN_MEMORY_LAKE_GUIDE.md](IN_MEMORY_LAKE_GUIDE.md).

**Where the files are read from.** `config/lakes/` (the system property `drishti.lakes.dir` or `DRISHTI_LAKES_DIR` say otherwise). The
demo lake's file also ships inside the server, so a server started in another folder, a container or a cluster node knows it; a
`demo.yaml` in the folder wins. Example lake files for S3, Iceberg path tables, AWS S3 Tables, a REST catalog behind a private CA and an
in-memory store are in `config/lakes.examples/`, each beside its connectors in `config/connectors.examples/`.

## 2. The lake file, every key

### 2.1 A worked example: `config/lakes/risklake.yaml`

Trades from Endur, positions and their scenario PVs, and the VaR hierarchy's nodes:

```yaml
lake: risklake                    # its name: the file's name (lower-case letters, digits, - and _)
format: delta                     # delta (the default) | iceberg
# root: not given: the lake's own folder, ${drishti.data.dir:./data}/delta/risklake
defaults:                         # every table, unless its entry says otherwise
  load-mode: replace              # a load replaces each business date it holds (section 5.3)
  compression: zstd
tables:
  trading.trade_endur:            # <domain>.<table>; the table's folder is trading/trade_endur
    kind: trade                   # the pack's kind it holds (several tables of one kind: trade_endur, trade_murex)
    partition-by: [PRODUCT_TYPE]  # after business_date; at most four; keep it small (section 2.3)
    promote: [TRADE_ID, TRADE_VERSION, SOURCE_SYSTEM, TRADE_STATUS, PRODUCT_TYPE, PRODUCT_GROUP, DESK, BOOK, PORTFOLIO, COUNTERPARTY_ID,
      LEGAL_ENTITY, TRADER_ID, BUY_SELL_CODE, CURRENCY, QUANTITY, NOTIONAL, TRADE_DATE, MATURITY_DATE]
    id: TRADE_ID
    latest-by: UPDATED_AT         # two copies of one trade on one date: the later UPDATED_AT wins
    sort-by: [DESK, BOOK, id]     # each date's rows in this order: a desk's trades sit in few files
    file-rows: 250000
    row-group-rows: 1000
    retention: 7y
    maintenance: {target-file-mb: 256, vacuum: 7d}
    time-travel: 30d
  risk.scenario-pv:               # scenario PVs of trades (GRAIN TRADE) and of positions (GRAIN POSITION)
    promote: [VALUE_TYPE, GRAIN, TRADE_ID, POSITION_ID, CURRENCY, SOURCE_SYSTEM, SECURITY_ID, LEGAL_ENTITY, DIVISION, DESK, TT_BOOK,
      LIMIT_LETTER, PORTFOLIO, PRODUCT_GROUP, PRODUCT_TYPE, DIRECTION, QUANTITY, BASE_PV, VALUATION_DATE, SCENARIO_SET_ID, BATCH_ID, RUN_KIND,
      COMPUTED_AT, STATUS]
    vectors: [SCENARIO_PV]        # 509 numbers a row, a list<double> column
    id: SCENARIO_PV_ID
    date: BUSINESS_DATE           # the rows' own date field: only counted when it differs (section 5.2)
    latest-by: COMPUTED_AT
    sort-by: [DESK, TT_BOOK, id]
    file-size-mb: 256
    row-group-mb: 64
    vector-compression: zstd
    retention: 7y
  risk.risk-hierarchy:            # the VaR hierarchy's nodes: BANK > DESK > BOOK
    promote: [NODE_ID, HIERARCHY, LEVEL, PARENT_ID, NAME]
    id: NODE_ID
    date: BUSINESS_DATE
```

Positions are scenario-PV rows of `GRAIN: POSITION` (a `POSITION_ID`, placed by its book and naming its trades in `TRADE_IDS`); a
position the VaR engine derives from trades needs no table of its own. The shape of every VaR input, field by field, is the
[VaR guide's data contract](VAR_GUIDE.md).

Check the file before you load: `drishti.py lake show` lists it as the server reads it (*captured*):

```text
  risklake: delta at data/delta/risklake, 3 tables
    trading.trade_endur                      trading/trade_endur                      partitioned by business_date, PRODUCT_TYPE; 18 promoted
    risk.scenario-pv                         risk/scenario-pv                         partitioned by business_date; 24 promoted, vectors SCENARIO_PV
    risk.risk-hierarchy                      risk/risk-hierarchy                      partitioned by business_date; 5 promoted
```

### 2.2 The lake's own keys

| Key | Means |
|---|---|
| `lake` | the lake's name; must equal the file's name |
| `format` | `delta` (default) or `iceberg` |
| `root` | where it is: a local folder, `s3://bucket/prefix`, or `mem://<store>`. Not given: `${drishti.data.dir:./data}/delta/<lake>` (`…/iceberg/<lake>` for Iceberg) |
| `storage` | how to reach it: `region`, `endpoint`, `path-style`, `tls.*`, `proxy.*`. Credentials come from the platform (an instance or task role, a profile) or as `${ENV}` references; a secret written as a value is refused |
| `catalog` | Iceberg only: `none` (path tables, the default), `rest`, `glue`, `s3tables`, `hadoop`, with their settings (`uri`, `warehouse`, `token: ${…}`) |
| `defaults` | any table key, for every table that does not set it |
| `tables` | the tables, each `<domain>.<table>: {…}` (below) |
| `memory` | an in-memory lake only: `max-mb`, `chunk-kb`, `contract`, `owner`, `idle-trim-minutes` ([IN_MEMORY_LAKE_GUIDE.md](IN_MEMORY_LAKE_GUIDE.md)) |
| `description` | free text, shown on Admin → Lakes |

Values may name environment variables or server settings: `${NAME}`, `${NAME:default}`, nested
(`${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}`). A variable that is not set and has no default refuses the file, naming
it. An unknown key, a format that is not delta or iceberg, a fifth partition column, a load mode that is not replace or append, a
secret written as a value, or a `lake:` that differs from the file's name: each refuses the file with the key named.

A table's entry:

| Key | Means | Section |
|---|---|---|
| `kind` | the pack's kind, when the entry is not named after it (`trading.trade_endur` holds `kind: trade`) | |
| `path` | the table's folder under the root (default `<domain>/<table>`) | |
| `format` | delta or iceberg, when it differs from the lake's | |
| `partition-by` | the partition columns after `business_date` (at most four; Delta only) | 2.3 |
| `promote`, `vectors`, `flatten` | the columns beside the document | 2.4 |
| `id`, `date`, `latest-by` | the id field, the rows' own date field, which copy of an id wins | 2.4 |
| `load-mode` | `replace` (the default) or `append` | 5.3 |
| `sort-by`, `file-rows`, `row-group-rows`, `file-size-mb`, `row-group-mb`, `compression`, `vector-compression` | the file layout | 2.5 |
| `retention`, `maintenance`, `time-travel` | how long dates and old versions are kept, compaction, vacuum, checkpoints | 2.6 |
| `properties` | Delta or Iceberg table properties, passed through as they are | 2.6 |

### 2.3 Partitions: business date first, then as few as possible

**Every table is partitioned by `business_date` first, always.** It is not a setting and cannot be turned off; `partition-by` names
only what comes after it, at most four columns, and naming `business_date` there is refused. An Iceberg table of this layout is
partitioned by business date alone (keep the other fields as `promote:` columns).

**Why few partition columns.** Each partition column multiplies the folders: one folder per combination of values that occurs on a
date, and at least one file per folder. Folders and files are what a lake pays for: every file is an entry in the table's log that
every reader replays, an S3 request to open, a Parquet footer to read, and a file compaction and vacuum must visit. Small files also
compress worse. The arithmetic for a trade table with 20 desks, 400 books, 40 product types and 12 currencies, kept seven years
(1,827 business dates), where only the combinations that actually occur make a folder:

| `partition-by` (after `business_date`) | Folders a day | Folders in 7 years | Average file at 0.6 GB a day |
|---|---:|---:|---:|
| *(none)* | 1 | 1,827 | 256 MB (3 files a day) |
| `[PRODUCT_GROUP]` (6) | 6 | 10,962 | 100 MB |
| `[PRODUCT_TYPE]` (40) | 40 | 73,080 | 15 MB |
| `[DESK, PRODUCT_TYPE]` (≈300 occur) | 300 | 548,100 | 2 MB |
| `[DESK, BOOK, PRODUCT_TYPE]` (≈2,400) | 2,400 | 4,384,800 | 250 KB |
| `[DESK, BOOK, PRODUCT_TYPE, CURRENCY]` (≈9,600) | 9,600 | 17,539,200 | 62 KB |

The last row is seventeen million folders, each with a 62 KB file: a query for one day opens 9,600 files, and the table's log carries
an entry for every one of them. The first row answers the same questions from 3 files a day.

**What to use instead**:

- **`sort-by`**: each date's rows are written in this order, so a desk's or a book's rows sit in one or two files, and the readers skip
  the others from the files' statistics (each file's lowest and highest value of every promoted column). Sorting gives most of what a
  partition would, with no extra folders.
- **`promote`**: a promoted column is a real, typed column; filters on it read only that column, not the documents.
- **Row groups**: small row groups (`row-group-rows`, `row-group-mb`) let a reader skip inside a file too.
- **Compaction**: maintenance rewrites small files into large, sorted ones (section 2.6).

**Rules of thumb**: a partition (one date and one value) should hold **at least about 1 GB**; partition only by a **low-cardinality**
column (a handful of values) that almost every query names; when a day holds less than about 1 GB, use **no extra partition at all**.
The demo lake partitions its trades by `PRODUCT_TYPE` to show the feature; for 1M trades a day, section 9 recommends none.

A partition column is fixed when the table is first written: changing `partition-by` later means rebuilding the table (load its dates
again into a fresh table), and `drishti.py lake check` reports a table whose partitions differ from its file (section 8).

### 2.4 Columns: promote, vectors, flatten, id, date, latest-by

| Key | Example | What it does |
|---|---|---|
| `promote` | `[TRADE_ID, DESK, BOOK, PRODUCT_TYPE, COUNTERPARTY_ID]` | these fields of the document are also written as typed columns: what filters, sorting, the pivot engine and BI read without opening documents. Promote the fields people filter by; twenty or thirty is normal |
| `vectors` | `[SCENARIO_PV]` | list-of-numbers fields written as `list<double>` columns and kept out of the document (it keeps a marker). VaR reads the vector column directly |
| `flatten` | `{LEGS[0].NOTIONAL: LEG1_NOTIONAL}` | a nested field written as a column of its own (and promoted) |
| `id` | `TRADE_ID` | the field that holds each row's id (default: the pack's key, else `id`) |
| `date` | `BUSINESS_DATE` | the rows' own business-date field. It **never** decides a row's partition (section 5.2): rows whose own date differs from the file's are loaded under the file's date and counted |
| `latest-by` | `UPDATED_AT`, `COMPUTED_AT` | when one id appears twice on one date, the copy with the latest of this timestamp is kept (default `COMPUTED_AT`; empty: the last read). The others are dropped and counted, never a failure |

### 2.5 The file layout

| Key | Default | Means |
|---|---|---|
| `sort-by` | the pack's, else `[LEGAL_ENTITY, DESK, id]` | the order of each date's rows (`id` is the row id) |
| `file-size-mb` | 256 | Parquet files of about this size (the parallel loader) |
| `file-rows` | | at most this many rows a file (`lake build`'s writer and compaction) |
| `row-group-mb` | the format's (Delta 256 KB, Iceberg 1 MB) | Parquet row groups of about this size |
| `row-group-rows` | | at most this many rows a row group (`lake build`'s writer) |
| `compression` | `snappy` (Delta), `zstd` (Iceberg) | the codec of the columns |
| `vector-compression` | `zstd` | the codec of the vector columns (with byte-stream-split encoding, which packs doubles far better) |

**Rules of thumb**:

- **Files of about 128–512 MB** (256 is the default). Many small files cost more than a few large ones (section 2.3); a file much larger
  than 1 GB makes a single reader's work too coarse.
- **Small row groups for lookups by id** (`row-group-rows: 1000`): opening one trade reads one small row group, not 100 MB. Tables read
  mostly in bulk (scenario PVs, read whole by VaR) do better with larger row groups (`row-group-mb: 64`).
- **Sort by what people narrow by**, then `id`: `[DESK, BOOK, id]` for trades, `[DESK, TT_BOOK, id]` for scenario PVs.
- **`zstd`** compresses documents about a third better than `snappy` for a little more CPU; vectors are `zstd` already.

A load file or a command line that gives a layout different from the lake file's is refused, naming both: the lake file decides.

### 2.6 Retention, maintenance, time travel, properties

`drishti.py lake maintain` (and Admin → Lakes' maintenance) acts on each table's own settings, over the maintenance file's
(`<data>/lakes/lake-maintenance.yaml`) and never below the domain's floor (risk and risk results keep seven years whatever is set):

| Key | Example | Does |
|---|---|---|
| `retention` | `260`, `260d`, `7y` | keeps that many business dates (a year is 261); older dates are removed |
| `maintenance.compaction` | `false` | no compaction (default: compacted, the table re-sorted by its layout) |
| `maintenance.target-file-mb` | `256` | the size compaction writes |
| `maintenance.vacuum` / `expire-snapshots` | `168h`, `7d` | Delta vacuum, Iceberg snapshot expiry: files no version needs are deleted after this age |
| `maintenance.vacuum-hours`, `orphan-hours` | `168` | the same in hours; Iceberg orphan files |
| `maintenance.checkpoint` | `true` | a Delta checkpoint each run (the default) |
| `maintenance.checkpoint-interval` | `10` | written as the table's `delta.checkpointInterval` |
| `time-travel` | `30d` | vacuum and expiry never go below it: old versions stay readable that long (a page's *known at*, an undo of a replace) |
| `properties` | `{delta.logRetentionDuration: "interval 60 days"}` | table properties, passed through when the table is created |

A maintenance key it does not know is said in the run's log, never guessed.

## 3. Loading, three ways

| | The data inbox (3.1) | `drishti.py lake build` (3.2) | The parallel loader (3.3) |
|---|---|---|---|
| Who starts it | the server, every 10 minutes, for files dropped in a folder | you, from a terminal or a scheduler | you or a scheduler, Java only |
| Needs | the server running with the inbox on | the console's Python and the server's jar | Java 21 and the server's jar |
| Tells the server | always | with `--signal` | no (announce with `drishti.py data landed`) |
| Best for | daily files from an upstream job | one-off loads and back-fills | very large loads, S3 to S3, machines without Python |

All three follow the same rules for the table, the business date and the mode (section 5), honour the `.ready` file (section 4), take
the layout from the lake file, and stamp every row with `_load_id`.

### 3.1 The data inbox

Drop a file and a `.ready` marker in a folder; the server loads it into the lake by itself, moves it to an outbox with a report, and
refreshes everything that reads the table.

**Turn it on.** The inbox is an engine, off as shipped: Admin → Engines → **Data inbox**, or

```bash
python3 tools/drishti.py server engines on dataload --reason "load risklake"
```

or `DRISHTI_DATALOAD_ENABLED=true`. In a cluster, set `owner: true` on one node only (`DRISHTI_DATALOAD_OWNER`); a folder lock keeps a
second runner out anyway.

**The folders**, all under `<data>/dataload` (`dir`), on one file system so a move is an atomic rename:

| Folder | Holds | Written by |
|---|---|---|
| `inbox/` | your files, their `.ready` markers, `ready.all` | you or the upstream job |
| `inprocess/<run>/` | the files a run has claimed, while they load (a run id is its start time and node: `20261107T150000Z-drishti-main`) | Drishti |
| `outbox/<day>/<run>/` | each loaded file with its `.ready` and its report `<file>.loaded.json`; the loader's own files under `loads/` | Drishti |
| `failed/<day>/<run>/` | a file that could not be loaded, its `.ready` and `<file>.failed.json` with the reason in words | Drishti |
| `ledger/` | one line per step of every file (`ledger-<yyyy-MM>.jsonl`), kept for ever | Drishti |

**The hand-shake.**

1. Copy the data file into `inbox/`.
2. **When it is complete**, put its `.ready` file beside it. A file without its marker is never touched.
3. Or put one **`ready.all`** in the inbox: every data file there is ready for the next cycle, with or without its own marker;
   `ready.all` then moves to that run's outbox, so it applies once.

A file and its marker must also be unchanged for `stable-seconds` (30) before they are taken: a second guard against a writer that sets
the marker early. Names starting with a dot are never looked at; a link, a folder or a name the loader does not read goes to `failed/`
once marked ready.

```bash
cp /landing/trade_endur.2026-11-06.jsonl.gz data/dataload/inbox/
touch data/dataload/inbox/trade_endur.2026-11-06.ready          # or trade_endur.2026-11-06.jsonl.gz.ready
```

**One cycle**, every `interval` (10 minutes), on the owner node:

1. **Skipped** while an earlier run still loads (runs never overlap: *skipped: run 20261107T150000Z-drishti-main still loading (12
   min)*), when the server is short of memory, or when the disk is below the server's free-space floor.
2. **The whole ready batch** (every ready, stable file at that moment, oldest first) is moved at once into `inprocess/<run>/`; files that
   become ready during the run wait for the next cycle. `max-files` and `max-gb` (off by default) bound one run when set.
3. **Never twice**: a file whose SHA-256 the ledger shows as loaded goes straight to the outbox, *already loaded in run …*. A changed
   file with the same name is loaded.
4. **Loaded**: the files of one table go to one run of the parallel loader, one table after another, as a separate Java process with
   its own heap (`loader-heap`, 4g): a large or broken file never takes the server's memory. Each business date is one commit.
5. **Moved** with the marker and the report into `outbox/<day>/<run>/`.
6. **Told**: one data-load signal per pack, kind and business date: the pack's connectors refresh, the checks, alerts and notices run,
   the VaR engine reloads a date whose inputs changed, and the load is on Admin → Data loads with *inbox:* and its files. In a cluster
   the signal goes on to every live member.

**Load now** (Admin → Data loads → Inbox, `drishti.py dataload run`, `POST /api/v1/admin/dataload/run`) starts a cycle at once, or
answers *skipped* while one loads.

**The ledger** records each file's every step: claimed, loaded (with tables, dates, Delta versions, rows and rejects), duplicate,
ignored (empty), failed, rolled back, undone. **A server stopped in the middle** finishes the run at its next start: files only claimed
go back to the inbox, a load that had committed is completed from its summary (never loaded again), files not yet moved are moved,
signals not yet sent are sent.

**Settings** (`config/engines/dataload.yaml`, `drishti.dataload.*`; every key in
[CONFIGURATION.md](../admin/CONFIGURATION.md)):

| Key | Default | Means |
|---|---|---|
| `owner` | `true` | this node runs the cycles (one node in a cluster) |
| `dir` | `<data>/dataload` | the inbox's folder |
| `interval` | `10m` | how often a cycle runs |
| `stable-seconds` | `30` | a file is taken once unchanged this long |
| `max-files`, `max-gb` | `0` (no limit) | bound one run |
| `loader-heap` | `4g` | the loader process's heap |
| `load-timeout` | `2h` | a longer load is stopped and its files go back to the inbox |
| `reject-max-percent` | `0` | rows a load may reject and still commit |
| `reload-duplicates` | `false` | `true` loads a file again even when its SHA-256 was loaded before |
| `keep-outbox-days` | `30` | outbox and failed day folders older than this are deleted; the ledger stays |
| `lake` | `demo` | the lake files go to unless a rule or the `.ready` file names another (section 5.1) |
| `rules-file` | `./config/dataload.yaml` | the routing rules (section 5.1) |
| `nice` | `true` | the loader runs at a lower priority |
| `default-mode` | `append` | a file's mode when neither its `.ready` file nor its table's lake entry says one (section 5.3) |
| `new-tables` | `false` | whether a file may create a table no pack has |

**The Inbox screen**, Admin → Data loads → Inbox (`/admin/loads/inbox`), is the inbox's status board: its columns follow a file's way
through the folders. **Waiting** (`inbox/`) shows each file's state (*ready*, *ready via ready.all*, *not yet stable*, *no marker*,
*held*, *refused*) with the rule that will route it and the business date it will load under; **Loading** (`inprocess/<run>/`) shows the
run table by table with each date's version, rows and rejects; **Last cycles** (`outbox/`) shows today's cycles; then **Failures** with
the reason and **Retry**, **Duplicates today**, the **routing rules** in effect and the **settings**. It redraws itself every 5 seconds
while a run loads and every 30 seconds when quiet. `drishti.py dataload status` prints the same.

### 3.2 `drishti.py lake build`

Loads files into a table of a lake from the terminal. `--lake risklake` names the lake file, which gives the location and the layout;
`--table` picks the entry where a kind has several. The plan first, then the load (*captured*):

```bash
python3 tools/drishti.py lake build --from drop/trade_endur.2026-11-06.jsonl.gz --lake risklake --table trade_endur --kind trade --plan
```

```text
  trade_endur.2026-11-06.jsonl.gz: table trade_endur of pack None, business date 2026-11-06 from the file name, mode overwrite-dates
```

```bash
python3 tools/drishti.py lake build --from drop/trade_endur.2026-11-06.jsonl.gz --lake risklake --table trade_endur --kind trade
```

```text
engine: drishti (Drishti's Java writer in server.jar)
business date  PRODUCT_TYPE           rows
2026-11-06     CDS                     130
2026-11-06     CDS_INDEX               140
...
2026-11-06     WARRANT                 125
documents read: 2000, rows written: 2000, rejected: 0
  business date 2026-11-06 from the file name: trade_endur.2026-11-06.jsonl.gz
summary: data/delta/risklake/_drishti-lake/runs/20261107T080146.064-1786979.json
2026-11-07T08:01:53-05:00 lake-build ok exit=0 run=20261107T080146.064-1786979 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=15 seconds=7.012
```

The useful options (the full list: `drishti.py lake build --help`, [TOOLS.md](TOOLS.md)):

| Option | Means |
|---|---|
| `--from PATH` | a file, a folder or an archive; repeatable; `-r` for subfolders |
| `--lake NAME` | the lake file; `--lake-root DIR` writes its layout under another folder (a scratch copy) |
| `--table`, `--kind`, `--pack` | which entry; the pack's key, date and recommended layout when the lake file has no entry |
| `--as-of YYYY-MM-DD`, `--date-from name` | the business date (section 5.2) |
| `--mode overwrite-dates` / `append` | `overwrite-dates` is replace (section 5.3); `--batch-id ID` with `--engine java` for an append table |
| `--engine java` | the parallel loader (3.3) instead of the default writer; `--loader-set KEY=VALUE` for its settings |
| `--plan`, `--dry-run` | say what would happen; read and count without writing |
| `--signal URL` | tell the server afterwards (needs a token with `loads:write`) |

Exit codes: `0` loaded, `2` loaded with rejects (or the signal failed), `1` failed (nothing committed), `3` another run holds the lock.
A `--partition-by` that disagrees with the lake file is refused, naming both (*captured*):

```text
lake-build failed exit=1 ... error="--partition-by ['DESK'] differs from ['PRODUCT_TYPE'], the --partition-by of table trading.trade_endur in lake risklake (config/lakes/risklake.yaml): the lake file decides; drop --partition-by or correct the lake file"
```

### 3.3 The parallel loader

The Java loader the inbox runs, on its own: readers, parsers and writers on every core, memory bounded by `memory.budget-mb` whatever the
input's size (rows that do not fit are sorted and spilled to disk), local or `s3://`, one commit per business date. A load file
(`load-scenario-pv.yaml`):

```yaml
source:
  files: drop/scenario_pv.2026-11-06.jsonl.gz     # a file, a folder, a glob or s3://...; the date from the name
target:
  lake: risklake                                  # location, layout, id, date, latest-by and mode from the lake file
  kind: scenario-pv
parallel: {writers: 4}
memory: {budget-mb: 1024}
rejects: {max-pct: 0}
report: {summary: runs/scenario-pv.summary.json}
```

```bash
java -Xmx2g -Dloader.main=com.ash.drishti.lakeload.LakeLoad -jar drishti-server/target/drishti-server-<version>-exec.jar \
    --config load-scenario-pv.yaml
```

```text
loader: delta table data/delta/risklake/risk/scenario-pv
  2026-11-06  280 rows  (version 0, 1 file(s))
documents read: 300, rows written: 300, rejected: 0
  20 id(s) appear more than once on 2026-11-06
  business date 2026-11-06 from the name of scenario_pv.2026-11-06.jsonl.gz
  ...
  memory: budget 1,024 MB, peak heap used 334 MB of 2,048 MB, spilled 0 MB
```

(*Captured*: the sample file repeated 20 position ids, so `latest-by: COMPUTED_AT` kept one copy of each and the date holds 280 rows.)
On Windows, the same command in PowerShell with the jar's path; nothing else is needed. Command-line options over the file: `--as-of`,
`--table`, `--pack`, `--kind`, `--mode append|replace`, `--key`, `--date-from`, `--plan`, `--set key.path=value`. Every key of a load
file, with S3 and Iceberg examples, is [TOOLS.md section 4.13](TOOLS.md#413-the-parallel-lake-loader); the design and the memory model
are [PARALLEL_LOADER.md](../architecture/PARALLEL_LOADER.md).

## 4. The `.ready` file, every key

The `.ready` file says that its data file is complete, and may say how to load it. It is YAML or JSON; **empty is fine**. The inbox reads
it, and so do `lake build` and the parallel loader for a file given on their command line (below their own options).

**Its name**: the data file's name in full plus `.ready` (`trade_endur.2026-11-06.jsonl.gz.ready`), or without the data extension
(`trade_endur.2026-11-06.ready`, `eod.tar.ready`). A short name that could release two data files (`trades.ready` beside `trades.jsonl`
and `trades.jsonl.gz`) releases neither, and the Inbox screen says why.

**A full, annotated example** (every key; a real file uses only those it needs):

```yaml
# trade_endur.2026-11-06.jsonl.gz.ready
lake: risklake                  # the lake the file goes to, over the rule's and the inbox's (section 5.1); a lake with no file yet is created
pack: trading                   # the pack whose kind and recommended layout the table uses
kind: trade                     # the pack's kind, when the pack has several
connector: trade-endur          # the pack's connector whose table is written (here trading/trade_endur), when several serve the kind
table: trade_endur              # the table, over the name's (refused for a tar)
business-date: 2026-11-06       # every row's business date, over the name's (YYYY-MM-DD; refused for a tar)
mode: replace                   # replace (the date replaced) or append (rows added); section 5.3
key: TRADE_ID                   # the id field (default: the lake entry's or the pack's)
sha256: 3f0c...                 # 64 hex characters: the file's checksum, checked before anything is written (inbox)
rows: 1048576                   # the documents the file holds (a tar: all its files), checked before anything is written (inbox)
new-table: false                # true: this file may create a table no pack has (with pack:, and new-tables: true in the inbox)
hold: false                     # true: the file waits in the inbox until this key is removed or set to false
# folders: {trades: trading, scenario_pv: market-risk}   a tar only: each table folder's pack
```

| Key | Value | Notes |
|---|---|---|
| `lake` | a lake's name (`[a-z0-9][a-z0-9_-]*`) | the data inbox's key: over the rule's `lake:` and `drishti.dataload.lake`. A lake with no file yet gets `config/lakes/<lake>.yaml` written, rooted at `<data>/delta/<lake>`, with the routed pack's recommended entries. `lake build` reads it too; its own `--lake` (`target.lake` for the parallel loader) wins over the `.ready` file, as every command-line option does |
| `pack`, `kind`, `connector` | names | where the table's kind and layout come from |
| `table` | a table name | over the name's; refused for a tar |
| `business-date` | `YYYY-MM-DD` | over the name's date; refused for a tar |
| `mode` | `replace` or `append` | over the lake entry's `load-mode` and the inbox's default |
| `key` | a field | the id field; a new table needs one (else a field named `id`) |
| `sha256`, `rows` | 64 hex characters; a whole number | checked by the inbox before anything is written; a mismatch sends the file to `failed/` |
| `new-table` | `true`/`false` | see section 5.6 |
| `folders` | `{<folder>: <pack>}` | a tar only (section 4.4) |
| `hold` | `true`/`false` | the Inbox screen shows the file as *held* |

**An unknown key is refused** (the file goes to `failed/` naming the keys there are), so a typo never goes unnoticed; so is a bad date, a
mode that is not replace or append, or a checksum that is not 64 hexadecimal characters.

### 4.1 One plain JSONL file

```text
inbox/risk_hierarchy.2026-11-06.jsonl
inbox/risk_hierarchy.2026-11-06.ready          (empty)
```

The table is `risk_hierarchy` and the date 2026-11-06, both from the name. With a rule or a `.ready` saying `pack: market-risk`,
`kind: risk-hierarchy` and `lake: risklake` it goes to `risklake/risk/risk-hierarchy`.

### 4.2 A file whose name carries no date

```text
inbox/var_nodes.jsonl
inbox/var_nodes.ready
```

```yaml
lake: risklake
table: risk-hierarchy
business-date: 2026-11-06
mode: replace
sha256: a8ef6dcae92b7c15871a93aa455e5e7bda0379f19c64d78b9c5eebcccb58c133
rows: 26
```

`lake build --plan` says where each value came from (*captured*): `var_nodes.jsonl: table risk-hierarchy …, business date 2026-11-06
from the .ready file, mode overwrite-dates (var_nodes.ready)`.

### 4.3 A gz of scenario PVs, held until the risk run is signed off

```yaml
# scenario_pv.2026-11-06.jsonl.gz.ready
lake: risklake
pack: market-risk
kind: scenario-pv
hold: true
```

The Inbox screen shows it as *held*; delete the `hold:` line (or set it to `false`) and the next cycle takes it.

### 4.4 A tar with several tables and dates

A tar (`.tar`, `.tgz`, `.tar.gz`, `.tar.zip`) holds `<table>/<YYYY-MM-DD>/<files>.jsonl[.gz]`, nothing else (operating-system litter
such as `__MACOSX/` and `.DS_Store` is ignored):

```text
eod-2026-11-06.tar.gz
├── trade_endur/2026-11-06/part-0001.jsonl.gz
├── trade_endur/2026-11-06/part-0002.jsonl.gz
├── scenario_pv/2026-11-05/restated.jsonl.gz
├── scenario_pv/2026-11-06/positions.jsonl.gz
└── risk_hierarchy/2026-11-06/nodes.jsonl
```

```yaml
# eod-2026-11-06.tar.ready
lake: risklake
folders: {trade_endur: trading, scenario_pv: market-risk, risk_hierarchy: market-risk}
rows: 2050326
```

**A tar names its own tables and dates; nothing overrides them**: `business-date:` or `table:` in its `.ready` is refused. Each folder's
pack is `folders:`, else the pack that already has a table of that name, else a pack of that name with one kind; an unresolved folder
refuses the whole archive. Every table of the archive shares one load id, and **an archive loads completely or not at all**: if one
table's load fails, the tables already committed are undone and the archive goes to `failed/` saying what failed and what was rolled
back. Make one from dated files with `tar -czf eod-2026-11-06.tar.gz trade_endur scenario_pv risk_hierarchy` run in the folder holding
the three table folders.

### 4.5 Checks: sha256 and rows

```bash
sha256sum trade_endur.2026-11-06.jsonl.gz        # Windows: Get-FileHash trade_endur.2026-11-06.jsonl.gz -Algorithm SHA256
zcat trade_endur.2026-11-06.jsonl.gz | wc -l     # the documents (one a line)
```

Write both into the `.ready` file **after** the data file is complete. A file cut short in transfer then fails before anything is
written: *its .ready file says 1048576 rows, the file holds 1048000*. `lake build` and the parallel loader do not check these two keys;
the inbox does.

## 5. Routing, business dates, modes, repeats and undo

### 5.1 Which table and which lake: the routing rules

A file goes where its `.ready` file says; else where the **first rule** of `config/dataload.yaml` whose `match` fits its name says; else
to the table its name carries (the pack with a kind of that name, else a pack of that name with one kind). A table no pack has, or that
two packs have, goes to `failed/` with the candidates: **never guessed**. The rules are read again every cycle (no restart).

```yaml
# config/dataload.yaml
dataload:
  rules:
    - match: "trade_endur.*.jsonl.gz"         # a glob on the file name: * any characters, ? one, [abc], {a,b}
      pack: trading
      kind: trade
      connector: trade-endur                  # the table that connector reads: trading/trade_endur
      lake: risklake
      date-from: name                         # the name must carry the date
    - match: "scenario_pv.*.jsonl.gz"
      pack: market-risk
      kind: scenario-pv
      lake: risklake
      date-field: BUSINESS_DATE               # the rows' own date: only counted when it differs
    - match: "risk_hierarchy.*"
      pack: market-risk
      kind: risk-hierarchy
      lake: risklake
  unmatched: fail                             # the only choice: a file no rule matches goes to failed/
```

Every key of a rule: `match`, `pack`, `kind`, and optionally `connector`, `table`, `date-from` (`auto` or `name`), `date-field`, `key`,
`lake` (a lake's name; a folder is the older form), `format` (`delta` or `iceberg`).

**Which lake**: the `.ready` file's `lake:`, else the rule's `lake:`, else `drishti.dataload.lake` (`demo` as shipped). When the lake
file has no entry for the kind, the pack's recommended entry is copied into it first (said in the log); when the lake has no file at all,
`config/lakes/<lake>.yaml` is created. From then on the lake file is the truth: edit it there.

A file of *lake rows* or *loader rows* (the row forms of [DATA_GUIDE.md section 2.2](DATA_GUIDE.md#2-the-row-forms)) is refused with the
tool that loads it (`tools/samplegen/load_rows.py`, or the store's own loader): the loader reads documents.

### 5.2 The business date of a file

**The business date comes from outside the data, never from a row.** It is the partition, so whoever sends the file decides it. First
match wins:

1. the command line's `--as-of` (`source.as-of`);
2. the `.ready` file's `business-date:`;
3. the name's date: `<table>[.<date>].<extension>`;
4. the business today: today in the server's zone, and on a Saturday or Sunday the Friday before.

A rule's `date-from: name` (or `--date-from name`) refuses a name without a date. Every row of the file goes under the decided date; a
row whose own date field differs is loaded and counted (*3 rows of trade_endur.2026-11-06.jsonl.gz carried another date in
BUSINESS_DATE; loaded under 2026-11-06*), in the report, the ledger and on the Inbox screen. A tar's dates are its folders'.

**The name** is `<table>[.<date>].<extension>`:

| Name | Table | Date |
|---|---|---|
| `trade_endur.jsonl` | trade_endur | the business today |
| `trade_endur.2026-11-06.jsonl.gz` | trade_endur | 2026-11-06 |
| `trade_endur.20261106.jsonl.zip` | trade_endur | 2026-11-06 |
| `eod-2026-11-06.tar.gz` | from its folders | from its folders |
| `trade_endur.final.jsonl` | refused: *"final" is neither a date (YYYY-MM-DD) nor an extension* | |
| `trade_endur.2026-13-06.jsonl` | refused: not a date | |

The table part is letters, digits, `_` and `-` (never a `.`), stored lower-case; the date is `YYYY-MM-DD` or `YYYYMMDD`, a real date,
not in the future. Extensions: `.jsonl`, `.ndjson`, `.json`, each plain, `.gz`, `.zst` or `.zip`, and the tars. **A plain file is one
table and one date**; a tar is the one container for several.

### 5.3 Modes: replace and append

| Mode | A load | Use it for |
|---|---|---|
| **replace** | replaces each business date it holds, in one commit per date: loading the same file again leaves the same data | end-of-day files: a date's full set of trades, positions, PVs |
| **append** | adds its rows to the dates it holds; readers take each id's row from the newest load | intraday deltas, several files a day |

The mode of a load, first match wins: `--mode` or the `.ready` file's `mode:`; the table's `load-mode` in its lake file (`replace` when
not set); the inbox's `default-mode`; `append`. **So a table in a lake file loads in replace mode unless its entry, the `.ready` file or
the command line says `append`.** (`lake build` writes replace as `--mode overwrite-dates`.)

An append table keeps the same safety as replace: each load carries a **batch id** recorded in the date's commit (Delta: a transaction
id `drishti-batch:<batch>:<date>`; Iceberg: the snapshot summary's `drishti.batch`), and a batch already committed for a date is never
appended twice, so a retry after a crash skips the dates done. The inbox gives every file's batch; the parallel loader takes
`target.batch-id`; `lake build --engine java --batch-id ID` passes one.

```yaml
tables:
  risk.intraday-pv:
    load-mode: append
    latest-by: COMPUTED_AT
```

### 5.4 Repeats and corrections

- **The same file again** (the same SHA-256) is not loaded twice by the inbox: it goes to the outbox marked *already loaded in run …*
  (`reload-duplicates: true` changes that).
- **A corrected file for a date** (a new SHA-256) in replace mode replaces the date: drop it with the same name. That is the way to
  correct a day.
- **A correction to a few trades** in an append table: drop just those trades; readers take each id's newest load.
- **An archive that fails part-way** is rolled back whole (section 4.4).

### 5.5 Undo a load

Every row carries the id of the load that wrote it (`_load_id`, `<run>.<n>`; the summary's `loads`). Undo removes exactly that load's
rows, in one commit per table and date, and a replace is undone by restoring the date's previous version:

- Admin → Data loads → Inbox → **Undo a load**: Check, then type the load id again;
- `POST /api/v1/admin/dataload/undo`;
- the command line (*captured*):

```bash
python3 tools/drishti.py lake undo 20261107T080233.594-1791776.1 --lake data/delta/risklake --dry-run
```

```text
would undo risk/risk-hierarchy 2026-11-06: 26 rows of load 20261107T080233.594-1791776.1 removed, 26 rows of the date before it restored
```

Without `--dry-run` it asks for the load id again (`--yes` for scripts). The old versions an undo restores must still be there:
`time-travel:` (section 2.6) keeps them.

### 5.6 New tables

A table no pack has is refused, listing the known tables, unless the `.ready` file says `new-table: true` with its `pack:` and the inbox
allows it (`new-tables: true`, off as shipped). The table is then written in the pack's lake and domain with the generic layout (`id`,
`doc`, `business_date`, `_load_id`, `_source_file`), its id field `key:` else `id`. The report says *new table X created; no pack serves it
yet*: a pack declares it as a kind when it should appear on screen.

## 6. Pointing Drishti at a lake

A lake is read by the Delta and Iceberg connectors that name it. A connector file (`config/connectors/<name>.yaml`, written from the
pack's template at the first start and yours to edit) says `lake:` and, where a kind has several tables, `lake-tables:`; root, folders,
partitions and promoted columns come from the lake file. To read your scenario PVs and VaR nodes from `risklake`, edit
`config/connectors/risk-store.yaml`:

```yaml
plugin: delta
enabled: true
kinds: [scenario-pv, risk-hierarchy]               # only what risklake holds
settings:
  lake: risklake
  lake-tables: risk.scenario-pv,risk.risk-hierarchy
  refresh-seconds: 30
```

and the trades (`config/connectors/trade-endur.yaml`): `lake: risklake`, `lake-tables: trading.trade_endur`. A connector may instead
name its tables **by path** (`root:` a folder or `s3://…`, with `domain`, `table.<kind>`, `layout.<kind>.*`), for tables no lake file
describes, such as a vendor's Delta table; both forms are permanent, and where a connector gives both, its own path wins over the lake
file's (Admin → Connectors says so on the field). Every table a connector
names must be in the lake file (*connector risk-store: lake risklake has no table risk.pnl-vector (…)*), and one connector reads one
domain: split it otherwise. Then **restart the server** (or let it reload the connector files) and check with `drishti.py lake check`
(section 8).

**The warning.** A load into a lake that no connector reads is loaded, and says so on the Inbox screen, in the file's report and in the
cycle's notices: *loaded into risklake, but no connector reads it: point market-risk's connectors at it (lake: risklake) and restart*.
The rows are in the lake; nothing shows them until a connector names it.

**Demo and real never mix.** Each lake has its own folder and its own file, and a connector reads one lake. A server reads either your
data or the demo for a kind, never both: the moment `risk-store` says `lake: risklake`, VaR, BI and every page read your scenario PVs
only. Keep the demo on another server (or another data folder) to compare.

**Going back to the demo**: set the connector files back to `lake: demo` and their pack's `lake-tables:` (or delete the file: the
pack's template is written again at the next start), and restart. Nothing in either lake changes.

## 7. The demo lake's one-time move

Until 2.9.11.d the demo lake's tables sat directly in `<data>/delta/<domain>/`; now the demo lake is `<data>/delta/demo/`. A demo lake
built in the older layout is **moved once, safely, at the server's first start** (`drishti.lakes.relocate-on-start`, on), or by hand with
the server stopped:

```bash
python3 tools/drishti.py lake relocate demo --dry-run     # the folders it would move and every table measured; nothing moves
python3 tools/drishti.py lake relocate demo               # exit 0 moved (or nothing to move), 1 refused or moved back
```

**Safe**: only the demo lake's own folders move, each by a rename on the same file system (no file copied or rewritten); every Delta table
is measured before and after (version, data files, rows, every named file present) and **any difference or error moves everything
back**; it refuses, moving nothing, when `<data>/delta/demo` exists, when a folder belongs to no lake file, or while a load holds the lock;
it **never deletes** a file and records the move in `<data>/delta/demo/_drishti-lake/relocated.json`. Connector files the server once
generated with the old default root follow the move: they are rewritten to `lake: demo`, each kept beside it as
`<name>.yaml.before-lake-<time>.bak`. A site that sets `DRISHTI_DELTA_ROOT` keeps that root: nothing moves.

**Roll back by hand**: stop the server, set `drishti.lakes.relocate-on-start: false`, rename the folders under `<data>/delta/demo/` back to
`<data>/delta/`, delete `relocated.json`, and put the `.bak` connector files back. Every check, refusal and message:
[OPERATIONS.md, The lakes' folders](../admin/OPERATIONS.md#the-lakes-folders).

## 8. Checking a load, and troubleshooting

**Where to look**:

| Where | Shows |
|---|---|
| Admin → Data loads | every load with its source (*inbox:* and its files), pack, kind, dates, rows, and what refreshed |
| Admin → Data loads → Inbox | waiting, loading, last cycles, failures with Retry, duplicates, rules, settings |
| `drishti.py dataload status` (`--json`) | the same from the command line: waiting files and why, failures with reasons, the last cycles, the lock, the next cycle |
| `outbox/<day>/<run>/<file>.loaded.json` | the route, the table, each date's version and rows, the loader's summary |
| `failed/<day>/<run>/<file>.failed.json` | the reason in words, and the loader's report |
| `outbox/<day>/<run>/loads/load-<n>.log`, `.summary.json` | the loader's own output |
| `ledger/ledger-<yyyy-MM>.jsonl` | every step of every file |
| `<lake>/_drishti-lake/runs/<run>.json` | the summary of a `lake build` or loader run |
| Admin → Lakes, `drishti.py lake show` | every lake and table as the server reads them |
| `drishti.py lake check` | connectors name known lakes, and each table on disk matches its lake file |

`drishti.py lake check` reports a difference and never rewrites anything (*captured*, after `partition-by` was changed in the file):

```text
  error risklake/trading.trade_endur: partitioned by [business_date, PRODUCT_TYPE] on disk; the lake file says [business_date, PRODUCT_GROUP] (reported, never rewritten: rebuild the table or correct the lake file)
lake check: 4 lakes, 1 errors
```

**Retry** puts a failed file and its marker back in the inbox (an empty marker when it had none): the Retry button, or
`drishti.py dataload retry <file>`. Fix the file or the rules first.

**Troubleshooting**:

| You see | Why | Do |
|---|---|---|
| a file stays in *Waiting*, *no marker* | no `.ready` beside it | add `<file>.ready` (or `ready.all`) |
| *not yet stable* | it changed in the last `stable-seconds` | wait; the writer is still copying |
| *held* | its `.ready` says `hold: true` | remove the key |
| two files, neither taken | a short marker matches both (`trades.ready` beside `trades.jsonl` and `trades.jsonl.gz`) | name the marker after the file in full |
| `failed/`: *no rule matches this name* | no rule, no `.ready` routing, no pack with that table | add a rule or `pack:`/`kind:` in the `.ready` |
| `failed/`: *the .ready file's key X is not one of …* | a typo in the `.ready` | correct it, Retry |
| `failed/`: *its .ready file says N rows, the file holds M* | the file was cut short, or `rows:` is wrong | resend, Retry |
| `failed/`: *"final" is neither a date (YYYY-MM-DD) nor an extension* | a name with a word in the date's place | rename to `<table>.<YYYY-MM-DD>.jsonl.gz` |
| `failed/`: *a tar archive names its own tables and dates* | `business-date:` or `table:` in a tar's `.ready` | remove them; fix the tar's folders |
| `failed/`: a table *in two packs* | the table name is ambiguous | `pack:` in the `.ready`, or a rule |
| *already loaded in run …* | the same SHA-256 was loaded before | nothing to do; `reload-duplicates: true` to force |
| *skipped: run … still loading* | the previous run is not finished | wait; runs never overlap |
| the table locked (exit 3) | another load or build holds it | it is tried again next cycle |
| *loaded into risklake, but no connector reads it* | no connector names the lake | section 6 |
| loaded, but the page shows the demo's rows | the connector still says `lake: demo` | section 6; restart |
| `lake check`: *partitioned by … on disk; the lake file says …* | the file was changed after the table was written | put the file back, or rebuild the table |
| a load refused: *--partition-by … differs* | a command line contradicts the lake file | drop the option; the lake file decides |
| fewer rows than documents | `latest-by` kept one copy of each repeated id | expected; the summary counts `duplicates` |

## 9. Sizing: a day of 1M trades and 50K positions × 509 scenarios

**Assumptions** (measure your own with one day loaded; Admin → Lakes and `drishti.py lake status --lake data/delta/risklake` give each
table's size):

- an Endur trade document of about 3 KB as JSON, about 0.6 KB a row once stored (Parquet, `zstd`): **1M trades ≈ 0.6 GB a day**;
- a scenario-PV row with 509 doubles (4 KB raw): measured at **2.7 KB a row** stored (the 300-position sample above, 759,480 bytes for
  280 rows): **50K positions ≈ 135 MB a day**; at trade grain, 1M trades × 509 ≈ 2.7 GB a day;
- 20 desks, 400 books, 40 product types, 12 currencies; 261 business dates a year, kept seven years (1,827 dates).

**Layout A: partitioned finely** (trades `partition-by: [DESK, BOOK, PRODUCT_TYPE, CURRENCY]`, scenario PVs `[DESK, TT_BOOK]`):

| | Folders a day | Files a day | Average file | Files in a year | Files in 7 years |
|---|---:|---:|---:|---:|---:|
| trades | ≈9,600 | ≈9,600 | 62 KB | 2,505,600 | 17,539,200 |
| scenario PVs (positions) | 400 | 400 | 340 KB | 104,400 | 730,800 |
| **the day** | **≈10,000** | **≈10,000** | | **2,610,000** | **18,270,000** |

**Layout B: business date only, sorted** (trades `sort-by: [DESK, BOOK, id]`, scenario PVs `sort-by: [DESK, TT_BOOK, id]`,
`file-size-mb: 256`), the recommended one:

| | Folders a day | Files a day | Average file | Files in a year | Files in 7 years |
|---|---:|---:|---:|---:|---:|
| trades | 1 | 3 | 200 MB | 783 | 5,481 |
| scenario PVs (positions) | 1 | 1 | 135 MB | 261 | 1,827 |
| **the day** | **2** | **4** | | **1,044** | **7,308** |

Layout B holds the same data in 2,500 times fewer files. A desk's VaR reads one or two scenario-PV files (sorted by desk) instead of
twenty folders; opening one trade reads one 1,000-row row group. Trade-grain scenario PVs (2.7 GB a day) in layout B are about 11 files of
256 MB a day, still one folder; that is the size at which a low-cardinality partition such as `[LEGAL_ENTITY]` (three values, ≈0.9 GB
each) starts to earn its folders.

**Loading time and memory**: the parallel loader is bounded by `memory.budget-mb` whatever the input (it spills sorted runs to
`memory.spill-dir`); the inbox gives it `loader-heap` (4g). Its measured throughput and the 2.3M-position benchmark are in
[BENCHMARK.md](../admin/BENCHMARK.md).

## 10. Connecting to an existing lakehouse (Databricks, Unity Catalog)

When the bank's tables already sit in a lakehouse that Databricks, Spark, Trino or Snowflake writes, Drishti reads them in place, with its
own readers inside the server (Delta Kernel and drishti-iceberg): **no Databricks cluster, Spark or warehouse is needed to read**. The
tables need the shape of section 1 (`id`, `doc`, `business_date`, promoted columns); a Spark job that writes it is in
[DELTA_CONNECTOR.md section 5](../connectors/DELTA_CONNECTOR.md#5-writing-the-lake). Each day sorted by `id` (on Databricks,
`OPTIMIZE … ZORDER BY (id)` or liquid clustering on `id`) reads fastest.

### 10.1 Built

**Delta by storage path.** The lake file's `root` is the folder above the domains: `s3://bucket/prefix` (the default `native` engine),
or `abfs://…` (Azure) and `gs://…` (Google Cloud) with the connector setting `engine: hadoop`
([DELTA_CONNECTOR.md section 16](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop)). Credentials: S3 from the AWS default
chain (a task or instance role) or `${ENV}` references; Azure and Google Cloud through `hadoop.<key>` settings; a private CA through
`tls.*`. With Unity Catalog, point the root at an external table's location and give the server read access to that storage.

```yaml
# config/lakes/bank-lakehouse.yaml
lake: bank-lakehouse
root: s3://bank-lakehouse/delta
storage: {region: eu-west-2}                 # no keys: the task role
tables:
  trading.trade_endur: {kind: trade, promote: [TRADE_ID, PRODUCT_TYPE, DESK, BOOK, COUNTERPARTY_ID], id: TRADE_ID}
```

```yaml
# config/connectors/trade-endur.yaml, Delta on Azure (ADLS Gen2)
plugin: delta
kinds: [trade]
settings:
  root: abfs://lake@riskstorage.dfs.core.windows.net/delta
  engine: hadoop                             # abfs:// needs the hadoop engine
  domain: trading
  hadoop.fs.azure.account.key.riskstorage.dfs.core.windows.net: ${AZURE_STORAGE_KEY}
  layout.trade.columns: TRADE_ID,PRODUCT_TYPE,DESK,BOOK,COUNTERPARTY_ID
```

**Iceberg through Unity Catalog's REST endpoint.** A Delta table with UniForm (Iceberg reads enabled) is read as Iceberg through Unity
Catalog's Iceberg REST catalog, with **vended credentials**: the catalog hands the server each table's own temporary credentials
(`vended-credentials: true`, the default), so the server needs no storage keys of its own. Apache Polaris, Snowflake Open Catalog, AWS
Glue's Iceberg REST endpoint, Nessie, Lakekeeper and Gravitino are read the same way
([ICEBERG_CONNECTOR.md section 4](../connectors/ICEBERG_CONNECTOR.md#4-catalogs-path-based-tables-and-rest-catalogs)):

```yaml
# config/lakes/uc-risk.yaml
lake: uc-risk
format: iceberg
catalog:
  type: rest
  uri: https://<workspace>.cloud.databricks.com/api/2.1/unity-catalog/iceberg-rest
  warehouse: risk_catalog                    # the Unity catalog
  token: ${DATABRICKS_TOKEN}                 # a secret only as a reference
tables:
  trading.trade_endur: {kind: trade, promote: [TRADE_ID, PRODUCT_TYPE, DESK, BOOK]}   # trading: the schema (the REST namespace)
```

AWS Glue and S3 Tables through their REST endpoints with SigV4 are built and **not yet run on AWS**
([ICEBERG_CONNECTOR.md section 19](../connectors/ICEBERG_CONNECTOR.md#19-aws-glue-s3-tables-and-the-iam-they-need)). Iceberg format
version 3 is refused by name: write format version 2.

**Delta features.** Delta Kernel 4.4 checks a table's protocol before reading; a reader feature it does not support is **refused by
name** (the table and the feature named), never read wrongly.

| Feature | Read | Note |
|---|---|---|
| Deletion vectors (`deletionVectors`) | yes | rows deleted in place are left out; until `OPTIMIZE` rewrites the files, the table's searches go through the documents (slower) instead of the promoted columns |
| Liquid clustering (`clustering`), `domainMetadata`, row tracking, in-commit timestamps | yes | writer features: nothing changes for a reader |
| Checkpoints (classic and v2), time travel (*known at*), `vacuumProtocolCheck` | yes | |
| UniForm (`icebergCompatV2`) | yes | a writer feature; the same table is also readable as Iceberg (above) |
| Column mapping, `timestampNtz`, type widening, variant | accepted by Delta Kernel 4.4 | not exercised by Drishti's tests: try your table with `drishti.py connector test` |
| A reader feature Delta Kernel does not know | refused by name | the message names the table and the feature |
| Parquet codecs BROTLI, LZO | refused, naming the table, the file and the codec | rewrite as Snappy, ZSTD or LZ4_RAW |

Check a connector against the real store before it serves anyone: `drishti.py connector test <file>` tries it and says what failed, in
words. Every setting: [DELTA_CONNECTOR.md section 14](../connectors/DELTA_CONNECTOR.md#14-settings),
[ICEBERG_CONNECTOR.md section 15](../connectors/ICEBERG_CONNECTOR.md#15-settings).

### 10.2 Planned (not built)

> **Under construction.** On the owner's roadmap (2026-11-07) for the platform release 2.9.11.f; none of this works today.

| Planned | What it will do | Today |
|---|---|---|
| **Unity Catalog for Delta** | a table found by its name, `catalog.schema.table`; Unity Catalog's credential vending; Unity Catalog's grants as the permissions | read Delta by storage path (external tables), or the same table as Iceberg through Unity's REST endpoint (UniForm) |
| **Delta Sharing** | read tables a provider shares through the Delta Sharing protocol | not supported |
| **Plain tables without the `id`/`doc` shape** | read a table of ordinary columns directly, driven by per-table configuration | a small job writes a Drishti-shaped copy beside it ([DELTA_CONNECTOR.md section 5](../connectors/DELTA_CONNECTOR.md#5-writing-the-lake)) |

## 11. Archives: zip and tar

Every loader reads JSON Lines inside an archive the way it reads the files themselves (`.zip`, `.tar.gz`, `.tgz`, `.tar`), in place,
from a local folder or `s3://`. A **zip** is one table and one date, its JSON Lines files loose; a **tar** holds `<table>/<YYYY-MM-DD>/`
folders (section 4.4). The Python loaders and the Java loader apply the same rules and guards and run the same test cases.

**Members**: `*.jsonl`, `*.ndjson`, `*.json`, plain or `.gz`/`.zst`, read in name order. Any other file (`README.txt`) is skipped with a
note; hidden files and `__MACOSX/` are skipped; a leading `./` is ignored; `\` in a zip written on Windows reads as `/`.

**Guards**, checked before a row is written:

| Guard | Refused |
|---|---|
| Zip-slip | a member name that is absolute or has a `..` part |
| Links and devices | a symbolic or hard link, a device or a FIFO member |
| Encrypted members | a password-protected zip member |
| Nested archives | an archive inside the archive: unpack it first |
| Entries | more than `max-members` (100,000) |
| Zip bombs | declared or expanded sizes over `max-mb` (102,400 MB) or `max-ratio` (200) times the archive's own size; reading stops the moment one is passed |

The caps come from `DRISHTI_ARCHIVE_MAX_MB`, `DRISHTI_ARCHIVE_MAX_RATIO` and `DRISHTI_ARCHIVE_MAX_MEMBERS`; the parallel loader also
takes `source.archive.*` in its load file. **Datasets** (the parallel loader with `datasets:`, and `drishti.py convert`): an archive with
no folders is one dataset named after it; folders one level deep are one dataset each; loose files beside folders, or files deeper than
one folder, are refused. With several datasets every one is read and checked before the first commit, so one that would fail stops the
run with nothing loaded ([TOOLS.md section 4.13](TOOLS.md#413-the-parallel-lake-loader)).

```bash
# a day's trades as one archive, into the table its folder names
python3 tools/drishti.py lake build --from /landing/2026-11-06/trades.tar.gz --lake risklake --table trade_endur --kind trade
# what is inside, as JSON Lines
python3 tools/drishti.py convert bundle.zip
```

## 12. Other lakes, packs and older installations

**A Delta lake in S3**, read by every node of a cluster:

```yaml
lake: main
root: s3://${DRISHTI_DELTA_S3_BUCKET:drishti-lake}/${DRISHTI_DELTA_S3_PREFIX:delta}
storage: {region: "${AWS_REGION:us-east-1}"}   # credentials: the AWS default chain; never here
tables:
  trading.trade_endur: {kind: trade, promote: [TRADE_ID, PRODUCT_TYPE, DESK, BOOK, COUNTERPARTY_ID], id: TRADE_ID}
  risk.scenario-pv: {promote: [VALUE_TYPE, GRAIN, TRADE_ID, POSITION_ID, DESK, TT_BOOK], vectors: [SCENARIO_PV]}
```

An S3-compatible store adds `endpoint:` and `path-style: true` under `storage:`.

**A pack's recommended entries.** A pack ships recommended entries for its kinds (a `lake:` block in `pack.yaml`, the shape of a lake
file's `tables:`), and its connector templates name the lake and their entries. When a writer first meets a kind its lake file lacks,
the pack's entry is copied in once and said so; from then on the lake file is the truth.

**The official results** are lakes of their own: `config/lakes/var-official.yaml` and `ccr-official.yaml` take their format, root and
storage from the publishers' settings (`drishti.var.official.*`, `drishti.ccr.exposure.official.*`), so BI's readers follow wherever the
results are published.

**An Iceberg copy of a demo store**: `tools/build_demo_lakes.py --store risk=iceberg` copies the tables into Iceberg with a lake file
`demo-iceberg.yaml` and connectors naming it ([tutorial 29](../tutorials/29-var-on-iceberg.md)).

**The local cluster** (`drishti-stack`): the stack's synthetic lakes are written through the demo lake's file with another root
(`lake build --lake demo --lake-root data/stack/lakes/<name>`); every node reads the same description.

**Admin → Lakes** lists every lake (format, root, in memory or not) and each table (kind, path, partitions, promoted columns, vectors, id,
load mode), read-only; a lake file the reader refuses is shown as the refusal. The API: `GET /api/v1/admin/lakes/files`
(`?check=true` adds the comparison with the tables on disk).

**Connectors by path to a lake file.** A connector that names its tables by path keeps working with no warning. To give such tables a
lake file of their own:

1. `python3 tools/drishti.py lake migrate-config` prints the lake files it would write (one per distinct root, named after the root's
   last folder) and each connector rewritten to `lake: <name>`; nothing is written.
2. `python3 tools/drishti.py lake migrate-config --write` writes them (an existing lake file is never overwritten).
3. `python3 tools/drishti.py lake check` compares each lake file with its tables on disk.
4. Restart the server. Nothing under the data folder changes.
