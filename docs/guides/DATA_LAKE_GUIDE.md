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
[tutorial 16, Part A, Load your own data into a new lake](../tutorials/16-load-your-own-data-into-a-new-lake.md#part-a--a-new-lake-of-your-own). Connecting a source that is
not a lake (a database, a file folder, Kafka) is the [data guide](DATA_GUIDE.md); telling Drishti that an outside job has loaded data is
its section 1. The in-memory lake (feeds that may never touch a disk) is [section 13](#13-the-in-memory-lake).

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
13. [The in-memory lake](#13-the-in-memory-lake)
14. [Delta protocol compliance](#14-delta-protocol-compliance)
15. [Iceberg spec compliance](#15-iceberg-spec-compliance)

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
answers, VaR equal to the cent ([tutorial 16, Part E](../tutorials/16-load-your-own-data-into-a-new-lake.md#part-e--the-risk-lake-and-the-official-results-on-iceberg)). An Iceberg table of this layout is partitioned by
business date alone.

**In memory.** A lake whose root is `mem://<store>` lives in the server's memory under a hard cap, for feeds that may never be written
to disk: [section 13](#13-the-in-memory-lake).

**Where the files are read from.** `config/lakes/` (the system property `drishti.lakes.dir` or `DRISHTI_LAKES_DIR` say otherwise). The
demo lake's file also ships inside the server, so a server started in another folder, a container or a cluster node knows it; a
`demo.yaml` in the folder wins. Example lake files for S3, Iceberg path tables, AWS S3 Tables, a REST catalog behind a private CA and an
in-memory store are in `config/lakes.examples/`, each beside its connectors in `config/connectors.examples/`.

**The lakes Drishti ships.** `demo` (the demo data, built from the committed test data in `data/testdata/`) and six lakes that ship empty, as definitions only: `risklake` (the bank's own risk data), `newslake` (news), `worldlake` (the World Bank, the IMF, the United Nations), `regulators` (the SEC, the Federal Reserve, OSFI and other central banks), `capitalmarket` (prices, curves, volatility surfaces, funds) and `referencelake` (core reference data: GLEIF legal entities, ownership, identifier mappings). Each has its lake file in `config/lakes/` with no tables and its folder `data/delta/<lake>/` holding only a README.md; what a build or a load writes there is never committed. Nothing shipped points at the six: every connector reads `demo` until you point it elsewhere (section 6); only the data feeds (`config/feeds/`, [EXTERNAL_DATA.md](EXTERNAL_DATA.md)) name them, as the lakes they load.

## 2. The lake file, every key

Why one file per lake, who reads it and how it is built: [DATA_ARCHITECTURE.md section 8](../architecture/DATA_ARCHITECTURE.md#8-the-lake-file-one-description-of-every-lake).

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

The shipped demo lake (`config/lakes/demo.yaml`) lays out its scenario PVs with `partition-by: [SCENARIO_SET_ID, GRAIN]`,
`sort-by: [LEGAL_ENTITY, DIVISION, DESK, TT_BOOK, TRADE_ID]`, zstd, 256 MB files, 16 MB row groups, 30 days of time travel and
maintenance (compaction, vacuum after 7 days, checkpoints), and its Calypso and Murex trades by `PRODUCT_GROUP`. A demo lake built
before this layout keeps its old partitions: a load into such a table is refused, naming both layouts, until the lake is rebuilt with
`python3 tools/drishti.py lake demo` (VaR answers are the same on either layout).

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
| `memory` | an in-memory lake only: `max-mb`, `chunk-kb`, `contract`, `owner`, `idle-trim-minutes` ([section 13](#13-the-in-memory-lake)) |
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
The demo lake partitions Endur's trades by `PRODUCT_TYPE` (a few products), Calypso's and Murex's by `PRODUCT_GROUP` (each books 50+
product types: a folder per type would be the small-file shape above), and the scenario PVs by `SCENARIO_SET_ID, GRAIN` (a VaR run reads
one scenario set, trades and positions apart), each sorted so a legal entity's, desk's and book's rows sit together; for 1M trades a
day, section 9 recommends no trade partition at all.

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

The useful options (the full list: `drishti.py lake build --help`, [CLI_REFERENCE.md](CLI_REFERENCE.md)):

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
file, with S3 and Iceberg examples, is [CLI_REFERENCE.md section 4.13](CLI_REFERENCE.md#713-the-parallel-lake-loader); the design and the memory model
are [DATA_ARCHITECTURE.md section 10](../architecture/DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-drishti-lakeload).

## 4. The `.ready` file, every key

The `.ready` file says that its data file is complete, and may say how to load it. It is YAML or JSON; **empty is fine**. The inbox reads
it, and so do `lake build` and the parallel loader for a file given on their command line (below their own options).

**Its name**: the data file's name in full plus `.ready` (`trade_endur.2026-11-06.jsonl.gz.ready`), or without the data extension
(`trade_endur.2026-11-06.ready`, `eod.tar.ready`). A short name that could release two data files (`trades.ready` beside `trades.jsonl`
and `trades.jsonl.gz`) releases neither, and the Inbox screen says why.

### 4.0 A complete `.ready` file, every key

Every key a `.ready` file can carry, each with what it does and its default. All are optional: a real file uses only those it needs (most
need none, or `lake:`, `mode:`, `sha256:` and `rows:`). The keys are one list shared by the inbox and the command-line loaders
(`ReadyNote.KEYS` in drishti-dataload, `READY_KEYS` in `tools/lake/feedroute.py`, compared by a test); this example is parsed against it
by `drishti-console/tests/test_docs_ready_example.py`, so a renamed key fails the docs.

```yaml
# trade_endur.2026-11-06.jsonl.gz.ready   (YAML; JSON works too; an EMPTY file is fine and only says "the data file beside me is complete")
#
# --- Where the rows go -----------------------------------------------------------------------------------------------------------------------
lake: risklake               # the lake (its file config/lakes/<lake>.yaml; created at <data>/delta/<lake> when it has none yet).
                             #   Default: the routing rule's lake:, else drishti.dataload.lake (demo as shipped). Section 5.1
pack: trading                # the pack whose kind and recommended layout (promoted columns, id, partitions) the table takes.
                             #   Default: the rule's pack:, else the pack that has a kind or table named like the file
kind: trade                  # the pack's kind, when the pack has several. Default: the rule's kind:, else the one the file name names
connector: trade-endur       # the pack's connector whose table is written (here trading/trade_endur), when several serve the kind.
                             #   Default: the rule's connector:, else the first that serves the kind
table: trade_endur           # the table, instead of the name's. Default: the file name's table (trade_endur.2026-11-06...). Refused for a tar
#
# --- Which business date -----------------------------------------------------------------------------------------------------------------------
business-date: 2026-11-06    # every row's business date (YYYY-MM-DD), the partition. Default: the date in the file name, else the
                             #   business today (New York calendar). Refused for a tar: a tar's folders name their dates. Section 5.2
#
# --- How it loads ------------------------------------------------------------------------------------------------------------------------------
mode: replace                # replace: the file's business date(s) replaced in one commit, so a re-run is safe; append: rows added.
                             #   Default: the lake entry's load-mode (replace in the shipped lake files), else drishti.dataload.default-mode (append)
key: TRADE_ID                # the id field. Default: the lake entry's id:, else the pack's key, else a field named id. A new table needs one
new-table: false             # true: this file may create a table no pack has (with pack:, and drishti.dataload.new-tables: true). Default false
#
# --- A tar only ----------------------------------------------------------------------------------------------------------------------------------
folders: {trade_endur: trading, scenario_pv: market-risk}
                             # each top folder's pack (a tar holds <table>/<YYYY-MM-DD>/ files). Default: the pack that already has a table of
                             #   that name, else a pack of that name with one kind; an unresolved folder refuses the whole tar. Section 4.4
#
# --- Checks before anything is written (the inbox: a mismatch sends the pair to failed/, nothing committed) -------------------------------
sha256: 2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae
                             # the data file's SHA-256, 64 hexadecimal characters (sha256sum, Get-FileHash). Default: not checked
rows: 1048576                # the documents the file holds (a tar: all its files together). Default: not checked
#
# --- Hold it ---------------------------------------------------------------------------------------------------------------------------------------
hold: false                  # true: the pair waits in the inbox, shown as held, until this key is removed or set to false. Default false
```

The example names every key at once to show them: a real tar's `.ready` refuses `table:` and `business-date:`, and `folders:` is meant
for a tar only. The table below says the same per key.

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
- `POST /api/v1/admin/dataload/undo` (`{"loadId": …}` a dry run; `"confirm"`: the same id, the undo; `"lake"`: a lake to undo in);
- `python3 tools/drishti.py dataload undo <load-id>` (a dry run; `--yes` undoes it; `--lake NAME`);

These three undo in **the lake the load wrote into**: the server finds the load's `loaded` line in the ledger (its `loads`, or its
`loaderRun` for lines written before the ids were recorded) and takes the route's `lake`. The answer names it (*would undo in risklake: 1
table date*). A load the inbox did not make, such as a `lake build`, has no ledger line: it is looked for in the inbox's own lake
(`lake:` in `config/engines/dataload.yaml`), or give `lake`.

- `drishti.py lake undo` works on a lake folder directly, without the server (*captured*):

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

**Demo and real never mix.** Each lake has its own folder and its own file, and a connector reads one lake. Three rules keep a server
from showing demo data beside yours:

- **The packs' samples step aside.** Every pack ships sample documents (`config/packs/<domain>/<pack>/samples/`), served by the `demo` connector,
  which answers any kind and, on a live read, before the lakes. Once a kind's routed connector, or any Delta or Iceberg connector serving
  it, names a lake other than `demo`, the samples no longer answer for that kind: not by id (`TRD 1000001`, a demo ComSwap, is not
  found once `trade-endur` reads risklake), not in a search. A kind whose connectors all read `demo` keeps its samples, so a demo server
  is unchanged. The server's published results lakes (`var-official`, `ccr-official`) do not count: they hold no inputs.
- **A whole pack, one lake.** Point every Delta and Iceberg connector of a pack at the lake, not one of them: the pack's screens read
  all its connectors (`trade-endur`, `trade-calypso` and `trade-murex` are one trade store; VaR reads scenario PVs, P&L vectors,
  hierarchies, portfolios and limits from `risk-store` and the market data from `market-store`). A connector file replaces the pack's
  template whole: keep its other keys (`union: true` on the trade connectors) when you change `lake:`.
- **The warning.** A pack whose connectors read more than one lake is *DEGRADED* in Admin → Health and `GET /api/v1/health`
  (`packs[].lakes`, each lake with its connectors, and `packs[].lakeWarning`): *trading's connectors read different lakes: demo
  (trade-calypso, trade-murex); risklake (trade-endur). Its screens mix them: point every connector of trading at one lake (lake: in each
  connector file) and restart*.

Nothing is cached across the switch: a read goes to the connectors as they stand after the restart. Keep the demo on another server (or
another data folder) to compare.

**Going back to the demo**: set the connector files back to `lake: demo` and their pack's `lake-tables:` (or delete the file: the
pack's template is written again at the next start), and restart. Nothing in either lake changes.

## 7. The demo lake's one-time move

The demo lake is `<data>/delta/demo/`. A demo lake built in the older layout, its tables directly in `<data>/delta/<domain>/`, is
**moved once, safely, at the server's first start** (`drishti.lakes.relocate-on-start`, on), or by hand with
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
`<name>.yaml.before-lake-<time>.bak`. A file nobody edited since it was generated keeps counting as unedited (its template stamp is
written again over the new text), so it still follows its pack's template at upgrades; an edited file stays edited. A site that sets
`DRISHTI_DELTA_ROOT` keeps that root: nothing moves.

**Roll back by hand**: stop the server, set `drishti.lakes.relocate-on-start: false`, rename the folders under `<data>/delta/demo/` back to
`<data>/delta/`, delete `relocated.json`, and put the `.bak` connector files back. Every check, refusal and message:
[OPERATIONS_GUIDE.md, The lakes' folders](OPERATIONS_GUIDE.md#71-the-lakes-folders).

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
[DELTA_CONNECTOR.md section 5](../../plugins/drishti-plugin-delta/README.md#writing-the-lake). Each day sorted by `id` (on Databricks,
`OPTIMIZE … ZORDER BY (id)` or liquid clustering on `id`) reads fastest. Drishti's lakes follow the Delta protocol's path encoding and are
tested against delta-rs both ways; the table features each reader supports are in [section 14](#14-delta-protocol-compliance).

### 10.1 Built

**Delta by storage path.** The lake file's `root` is the folder above the domains: `s3://bucket/prefix` (the default `native` engine),
or `abfs://…` (Azure) and `gs://…` (Google Cloud) with the connector setting `engine: hadoop`
([DELTA_CONNECTOR.md section 16](../../plugins/drishti-plugin-delta/README.md#engines-native-and-hadoop)). Credentials: S3 from the AWS default
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
([ICEBERG_CONNECTOR.md section 4](../../plugins/drishti-plugin-iceberg/README.md#catalogs-and-storage)):

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
([ICEBERG_CONNECTOR.md section 19](../../plugins/drishti-plugin-iceberg/README.md#aws-glue)). Iceberg format
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
words. Every setting: [DELTA_CONNECTOR.md section 14](../../plugins/drishti-plugin-delta/README.md#settings),
[ICEBERG_CONNECTOR.md section 15](../../plugins/drishti-plugin-iceberg/README.md#settings).

### 10.2 Planned (not built)

> **Under construction.** On the owner's roadmap (2026-11-07) for the platform release 2.9.11.f; none of this works today.

| Planned | What it will do | Today |
|---|---|---|
| **Unity Catalog for Delta** | a table found by its name, `catalog.schema.table`; Unity Catalog's credential vending; Unity Catalog's grants as the permissions | read Delta by storage path (external tables), or the same table as Iceberg through Unity's REST endpoint (UniForm) |
| **Delta Sharing** | read tables a provider shares through the Delta Sharing protocol | not supported |
| **Plain tables without the `id`/`doc` shape** | read a table of ordinary columns directly, driven by per-table configuration | a small job writes a Drishti-shaped copy beside it ([DELTA_CONNECTOR.md section 5](../../plugins/drishti-plugin-delta/README.md#writing-the-lake)) |

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
run with nothing loaded ([CLI_REFERENCE.md section 4.13](CLI_REFERENCE.md#713-the-parallel-lake-loader)).

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

**The official results** are lakes of their own: `config/lakes/banking/var-official.yaml` and `ccr-official.yaml` take their format, root and
storage from the publishers' settings (`drishti.var.official.*`, `drishti.ccr.exposure.official.*`), so BI's readers follow wherever the
results are published.

**An Iceberg copy of a demo store**: `tools/build_demo_lakes.py --store risk=iceberg` copies the tables into Iceberg with a lake file
`demo-iceberg.yaml` and connectors naming it ([tutorial 16, Part E](../tutorials/16-load-your-own-data-into-a-new-lake.md#part-e--the-risk-lake-and-the-official-results-on-iceberg)).

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

## 13. The in-memory lake

Some feeds may not, by contract, be saved on a disk. Drishti holds them in an **in-memory lake**: ordinary Delta tables (a `_delta_log`
and Parquet files) whose files live in the server's memory, off the Java heap, under a hard cap. A server job, the **replenish job**,
fills each table from its feed; every page, search, BI dataset and Export data read it exactly as they read a lake on a disk; a guard
refuses every way the data could reach a disk. Only a user's download leaves the server. How it is built:
[DATA_ARCHITECTURE.md section 9](../architecture/DATA_ARCHITECTURE.md#9-the-in-memory-lake-delta-tables-that-never-touch-a-disk); a
worked example: [tutorial 16, Part D](../tutorials/16-load-your-own-data-into-a-new-lake.md#part-d--news-held-in-memory-only).

### 13.1 How it works

```
 feed (URL, file, connector) ──► replenish job ──► lake writer ──► mem://<store>/<table>   (Delta: _delta_log + Parquet, zstd)
                                     (server)                              │ off-heap chunks, hard cap
 pages, search, BI, Export data ◄── Delta connector (root: mem://<store>) ◄──┘
```

- A **memory store** (`mem://<store>`) is one pool of memory with its own cap: files are lists of fixed-size chunks taken from the pool
  and given back the moment a file is deleted, so the cap is exact.
- A **memory table** is a Delta table at `mem://<store>/<folder>`; a run of its job is one Delta commit, so a reader sees the whole run
  or none of it, and a read in progress finishes on the version it started on.
- A **restart empties** the stores, by design. The jobs refill them at start, in warm-up order; meanwhile the connector's health says
  *held in memory only and is empty now* rather than a blank.

### 13.2 Setting up a memory-only feed

Three files. The store is a lake like any other: its own lake file, its root `mem://<store>`, its cap and contract under `memory:`:

```yaml
# config/lakes/news.yaml (config/lakes.examples/memory/news.yaml)
lake: news
root: mem://news
memory:
  max-mb: 256                 # the hard cap
  chunk-kb: 64                # small tables: every file holds at least one chunk
  contract: VX-2026-07        # shown on every refusal and on Admin -> Lakes
tables:                       # the tables sit at mem://news/<kind>
  news.news-topic: {path: news-topic}
  news.listed-company: {path: listed-company}
  news.news: {path: news}
  news.quote: {path: quote}
```

The connector reads it like any lake:

```yaml
# config/connectors/news-store.yaml (config/connectors.examples/memory/news-store.yaml)
plugin: delta
kinds: [news-topic, listed-company, news, quote]
settings:
  lake: news
  lake-tables: news.news-topic,news.listed-company,news.news,news.quote
```

The tables' replenish jobs live in the in-memory lake's engine file `config/engines/memory-lake.yaml`, with its switch
`drishti.engines.memory-lake.enabled` (off as shipped: set it to `true` or turn it on in Admin → Engines, then restart, since the stores'
caps size the direct memory at start; [OPERATIONS_GUIDE.md section 21](ADMIN_GUIDE.md#21-compute-engines-on-or-off)), or in
`application.local.yaml` or the environment:

```yaml
# config/examples/in-memory-lake.yaml
drishti:
  lake:
    memory:
      tables:
        - name: news
          store: news
          source: { url: "https://feeds.example.com/news.jsonl", headers: { Authorization: "Bearer ${VENDOR_TOKEN}" } }
          key: [NEWS_ID]
          every: 15m
          warm-up: 1
          pin: true
```

Restart the server with the shipped start scripts (they raise the JVM's direct-memory limit by the stores' caps, 13.7), then check it:

```bash
python3 tools/drishti.py lake memory status
python3 tools/drishti.py lake memory jobs --table news
```

UI: **Admin → Lakes** lists the store (cap, use, contract) and each table (state, bytes, rows, version, last and next refill). Switching
back: remove the files and restart; the pack's own connector reads the disk lake again.

### 13.3 Replenish jobs: sources, modes, when they run

| Source (`source:`) | What the job reads |
|---|---|
| `url` (and `headers`) | an HTTP(S) feed answering JSON Lines or one JSON array; headers name secrets as `${ENV}` |
| `file` | a JSON Lines file, plain or `.jsonl.gz` |
| `connector` and `kind` | every document of a kind from any connector that can list them (Kafka's topic state, JDBC, files, the vendor feeds) |
| `connector`, `kind` and `ids` or `ids-from` | a connector that computes a document when asked (the news connector), entity by entity: `ids: [NEWS-Apple]`, or `ids-from: listed-company.NEWS_ID` (that field of every row of another memory table) |

A connector whose own lake is in memory is never a source.

**Rows.** Each document becomes a row: `id` from `key` (fields joined with `|`), `doc` the document, `business_date` from
`business-date` (`today`, a date, or `field:AS_OF`: the first ten characters of a field), and the `columns` promoted beside it, sorted
by `sort-by` (the key when empty) before they are written.

| Mode | A run | Use for |
|---|---|---|
| `replace` (default) | is the whole table: one commit removes every earlier file and adds the new | snapshots: prices, reference lists |
| `append` | adds its rows | ticks, events |
| `merge` | replaces rows by key on the dates the run touches | a feed of changes |

**When.** `every: 15m` (or `30s`, `1h`), or `cron: "0 0/15 * * * *"`; on the source's signal (`signal: true`, the default: a file's time
or size, a connector's last update, checked every 10 s); at start in `warm-up` order; 30 s after an eviction; and on demand
(`drishti.py lake memory refill <table>`, audited). One run of a table at a time; `job-threads` tables at once. Every run is recorded in
memory (when, why, `ok` / `failed` / `refused`, rows, bytes, the version, the time taken), without any value of the data.

### 13.4 The hard cap and the eviction ladder

`max-mb` is a hard limit. When a write needs room the store frees memory in this order and stops as soon as there is enough:

1. **Old versions** past `keep-versions` (2 by default: the current and the one a reader may still be on).
2. **Whole tables, least recently read first**: never a pinned table (`pin: true`), never the table being written. A table goes whole;
   it becomes *evicted, refill queued*, and its pages say so.
3. **Refuse the write.** The commit fails, the table stays whole at its previous version; the run records *refused: the store is full*;
   Health turns amber.

### 13.5 Automated compaction

After every commit and every `every-seconds`, each store's tables are scored by the memory a rewrite would save, and the best is
rewritten as one commit: more small files (below `small-file-mb`) than dates are merged per date; replaced rows past `dead-rows` are
dropped (merge mode); after `resort-after` appends the table is rewritten sorted by `sort-by`. A rewrite starts only when `headroom-mb`
(at most a quarter of the store) plus the table's size is free under the cap, so compaction never causes an eviction; `idle-only: true`
keeps it to quiet periods. Admin → Lakes shows the memory saved today and in total.

### 13.6 Never on disk: what is refused and what is allowed

A connector whose root is `mem://` is **memory-only**, and so is what is read from it. One check sits in front of every place Drishti
writes:

| Where Drishti writes | Memory-only data |
|---|---|
| The connector's disk caches: documents (RocksDB), id-map files, index segments | **off** for that connector, whatever its settings |
| A scheduled report (`/me/reports`) | **refused** (`DRS-5219`) when saved and when run |
| A share's picture, its e-mail, a note copying the page's values | **refused** (`DRS-5219`); a share as a link is allowed |
| A BI run's stored result | not stored: the definition is kept, the rows are recomputed when opened |
| DuckDB's temporary spill | **off** on a server with memory stores (a query past `memory-limit` fails instead of spilling) |
| Ask about this page (an outside AI provider) | **refused** (`DRS-5219`) |
| **A user's download** (Export data) | **allowed**: streamed from memory with no temporary file, audited (the audit line keeps the selection's shape, not its values) |
| Another node of the cluster | answers over Drishti\*net only (13.8) |
| Logs and audit | ids and counts only |

A refusal says why, in words: *"This report uses news data held in memory only (contract VX-2026-07); it cannot be delivered by a
scheduled report. Only a download by you may leave the server."*

### 13.7 Memory, the JVM and the host

- **Direct memory.** The stores live in direct buffers. The JVM's limit must be 256 MB (the server's own I/O) plus the stores' caps.
  `tools/windows/start-server.ps1` computes it from the configuration (`tools/lake/memory_caps.py`); the Docker image and the ECS
  entrypoint take `DRISHTI_LAKE_MEMORY_MB` (the caps added up); a systemd unit or a run configuration adds
  `-XX:MaxDirectMemorySize=<256 + caps>m` after the existing flag (the later one wins). Admin → Health turns **red** when the running
  limit is below it.
- **Memory is given back.** A deleted or evicted file's chunks return to the pool at once; free chunks are released to the operating
  system after `idle-trim-minutes` of quiet, and at once when a cap is lowered.
- **Host checks** (Admin → Health, *In-memory lake*): no swap (or encrypted swap), no heap dump on out-of-memory unless to a RAM disk, no
  JFR recording at launch, the temporary folder on a RAM disk. Each is amber when not met.
- **Sizing.** Memory per table is close to its Parquet size with zstd, plus at most one partly filled chunk per file. Measured on the demo
  tables ([CLUSTER_GUIDE.md](../admin/BENCHMARK.md#216-bounded-resources-of-one-server)): the in-memory files are 4 to
  42 times smaller than the decoded JSON and 1.6 to 3.9 times smaller than the disk lake's Snappy files. Small tables want small chunks
  (`chunk-kb: 64`).

### 13.8 A cluster: the owner node binds the pack

A memory store is owned by one node, which fills it and serves the pack whose connectors read it; the others reach the pack through
Drishti\*net as they reach any pack a peer holds, and keep nothing of it.

```yaml
# config/net/node.yaml on the owner
memory-stores: [news]
```

On the other nodes a store not named in their `memory-stores` is not loaded, its tables show as *held by its owner node*, the kinds are
not offered in their digest, and a request for them is forwarded to the owner. Without `memory-stores`, each store's `owner:` setting
(true by default) decides.

### 13.9 What an administrator sees

| Where | What |
|---|---|
| Admin → Lakes (`GET /api/v1/admin/lakes/memory`) | each store: cap, used, allocated, contract, refusals; each table: state in words, bytes in memory, the files' bytes, the decoded bytes and their ratio, rows, version, last and next refill, pinned, evictions, refusals, compactions, memory saved |
| `drishti.py lake memory status` / `jobs` / `refill` | the same from the terminal; `--json` for scripts |
| Admin → Health, *In-memory lake* | amber: a store over 85% of its cap, a refused write, a host check; red: a pinned table empty after warm-up, the direct-memory limit below the caps |
| Admin → Health, *Busy protection* | a gauge per memory store (its use against its cap) |
| Admin → Caches, memory budget | the stores as the off-heap pool `memory-lake` |

### 13.10 Every setting

The store's own keys (`max-mb`, `chunk-kb`, `contract`, `owner`, `idle-trim-minutes`) are in its lake file's `memory:` block (section
2.2). The jobs and compaction are under `drishti.lake.memory` (environment `DRISHTI_LAKE_MEMORY_…`; command line
`--drishti.lake.memory.…`); the full table is in [CONFIGURATION.md](../admin/CONFIGURATION.md).

| Setting | Default | Meaning |
|---|---|---|
| `max-mb` | 0 | the hard cap; 0 holds nothing |
| `chunk-kb` | 1024 | the chunk size (64 for stores of small tables) |
| `contract` | | shown on every refusal and on Admin → Lakes |
| `owner` | true | this node owns the store (node.yaml `memory-stores` wins when set) |
| `idle-trim-minutes` | 10 | free chunks go back to the operating system after this much quiet |
| `tables[].name`, `.store`, `.folder` | | the table, its store, its folder (the name) |
| `tables[].source` | | `url` (+ `headers`), `file`, `connector` + `kind` (+ `ids` or `ids-from`) |
| `tables[].mode` | replace | `replace`, `append`, `merge` |
| `tables[].key`, `.sort-by` | | the id's fields; the sort (the key) |
| `tables[].business-date` | today | `today`, a date, `field:<NAME>` |
| `tables[].columns` | | fields promoted beside the document |
| `tables[].every`, `.cron`, `.signal`, `.warm-up` | , , true, | when it runs |
| `tables[].pin`, `.keep-versions`, `.codec` | false, 2, zstd | never evicted; versions kept; `zstd`, `lz4`, `snappy` |
| `history-keep`, `job-threads` | 50, 2 | runs kept per table; jobs at once |
| `compaction.*` | enabled, 30 s idle, 512 MB headroom, 8 MB small files, 0.2 dead rows, 20 appends, 60 s | 13.5 |

**A memory table's layout** is the Delta connector's: `id` (string, the key), `doc` (string, the document as JSON), `business_date`
(date, the partition), one string column per `columns` entry; Parquet with zstd level 3 (`codec: snappy` gives byte-equal files to the
disk lake's), row groups of 256 KB, a checkpoint every 10 commits. The pack's data contracts describe the documents; holding them in
memory changes nothing in them. The Iceberg engine reads and writes through the same store (`warehouse: mem://<store>/…`); the replenish
jobs write Delta.

### 13.11 Troubleshooting the in-memory lake

| What you see | Why, and what to do |
|---|---|
| `UP: mem://news is held in memory only and is empty now` | the warm-up has not reached it, or its job failed: `drishti.py lake memory jobs --table <t>` gives the reason |
| a run `refused: the store is full` | raise `max-mb` (and restart, so the launcher raises the direct-memory limit), unpin a table, or lower `keep-versions` |
| Health red, *direct-memory limit* | the server was started without the caps in `-XX:MaxDirectMemorySize`: use the shipped scripts or add the flag |
| `DRS-5219` on a report, a share, an Ask | as the contract asks; download the rows from Export data instead |
| a table *evicted, a refill is queued* | the store needed room for another table; pin the tables that must stay |
| a run `failed: … connector cannot list its documents` | that connector computes documents when asked: give `ids` or `ids-from`, or read its feed by `url` |

## 14. Delta protocol compliance

Drishti's Delta tables are plain [Delta Lake protocol](https://github.com/delta-io/delta/blob/master/PROTOCOL.md) tables: Spark,
Databricks, delta-rs and any Delta Kernel reader open them as they are, and Drishti reads the tables those engines write. No Drishti-only
file, field or naming rule is involved, and it is tested both ways against delta-rs. The internals (the lake file, the in-memory lake,
the loader) are in [DATA_ARCHITECTURE.md sections 8–10](../architecture/DATA_ARCHITECTURE.md#8-the-lake-file-one-description-of-every-lake).

### 14.1 The four readers and writers

| Path | Library | Writes | Reads |
|---|---|---|---|
| Java writer (`lake build --engine drishti`) and the parallel loader (`--engine java`, the data inbox's large loads) | Delta Kernel 4.4.0 with Drishti's file access | protocol `{"minReaderVersion":1,"minWriterVersion":2}`, no table features; a classic checkpoint every `delta.checkpointInterval` commits (10) | |
| Java reader (the server's Delta connector) | Delta Kernel 4.4.0 on Drishti's native engine (no Hadoop) | | the features in 14.3's Java column |
| Python writer (`lake build --engine deltalake`) | delta-rs 1.6.6 | the protocol delta-rs writes | |
| Python reader (`tools/lake/deltaread.py`: `lake maintain`'s relayout, the Delta-to-Iceberg copy) | delta-rs 1.6.6, or Delta Kernel through the server's exec jar when delta-rs would not read the table completely | | the features in 14.3's Python column |

### 14.2 Partition paths: values, folders, `add.path`

A partition value goes to three places, each by the protocol's rule:

- **the folder** is the value Hive-escaped, as Spark writes it: `"` `#` `%` `'` `*` `/` `:` `=` `?` `\` `{` `[` `]` `^`, DEL and the
  control characters become `%XX`; a space, a dot and non-ASCII letters stay as they are; null is `__HIVE_DEFAULT_PARTITION__`;
- **`add.path`** in the log is that relative path as a URI, encoded once (`%` becomes `%25`, a space `%20`); every reader decodes it
  once and finds the file;
- **`partitionValues`** hold the raw value; an empty value is written as null, as Spark writes it.

From a table the Java writer wrote (captured):

| Value | Folder on disk | In `add.path` | `partitionValues` |
|---|---|---|---|
| `Money market` | `PRODUCT_GROUP=Money market` | `PRODUCT_GROUP=Money%20market` | `"Money market"` |
| `x/y` | `PRODUCT_GROUP=x%2Fy` | `PRODUCT_GROUP=x%252Fy` | `"x/y"` |
| `100%` | `PRODUCT_GROUP=100%25` | `PRODUCT_GROUP=100%2525` | `"100%"` |
| the text `%2F` | `PRODUCT_GROUP=%252F` | `PRODUCT_GROUP=%25252F` | `"%2F"` (never decoded to `/`) |
| `a:b` | `PRODUCT_GROUP=a%3Ab` | `PRODUCT_GROUP=a%253Ab` | `"a:b"` |
| `k=v` | `PRODUCT_GROUP=k%3Dv` | `PRODUCT_GROUP=k%253Dv` | `"k=v"` |
| `.` | `PRODUCT_GROUP=.` | `PRODUCT_GROUP=.` | `"."` |
| `..` | `PRODUCT_GROUP=..` | `PRODUCT_GROUP=..` | `".."` |
| `Zürich` | `PRODUCT_GROUP=Zürich` | `PRODUCT_GROUP=Zürich` | `"Zürich"` |
| `東京` | `PRODUCT_GROUP=東京` | `PRODUCT_GROUP=東京` | `"東京"` |
| empty | `PRODUCT_GROUP=__HIVE_DEFAULT_PARTITION__` | the same | `null` |
| null | `PRODUCT_GROUP=__HIVE_DEFAULT_PARTITION__` | the same | `null` |

A value never adds a folder level or leaves the table: `/` and `\` are escaped, and `..` is part of the name `PRODUCT_GROUP=..`, not a
folder of its own. One `add` action of that table, as written (statistics shortened):

```json
{"add":{"path":"business_date=2026-11-06/PRODUCT_GROUP=Money%20market/c8daa754-2833-4db9-b0b1-1397f1b207d5-000.parquet","partitionValues":{"PRODUCT_GROUP":"Money market","business_date":"2026-11-06"},"size":1718,"modificationTime":1794142500377,"dataChange":true,"stats":"{\"numRecords\":1,\"minValues\":{\"id\":\"0\"},\"maxValues\":{\"id\":\"0\"},\"nullCount\":{\"id\":0}}"}}
```

**The Python writer** (delta-rs) spells folders its own way: it percent-encodes a space and non-ASCII letters too
(`PRODUCT_GROUP=Money%20market`, `PRODUCT_GROUP=Z%C3%BCrich`). That is within the protocol (the folder is whatever `add.path` decodes
to, and every reader, Drishti's included, reads it), but the names differ from Spark's convention; use the Java writer when the folder
names themselves must be Spark's.

**A partition column's name** is never escaped (by Kernel or by Spark), so `lake build` refuses one a folder would change, or that Spark
refuses without column mapping (a space, `,` `;` `{` `}` `(` `)` `=` `/` `\` `%` `:` `#` `?` `*` quotes `[` `]` `^`, control
characters): *partition column 'PRODUCT GROUP' cannot name a Delta partition folder*. A dotted field path is a plain name
(`counterparty.id` is the column `counterparty__id`).

### 14.3 Table features

Each feature below was put on a table with delta-rs 1.6.6, appended to and read back by both readers. Delta Kernel 4.4.0's own list
(`TableFeatures`) declares read support for every feature it knows, including the ones delta-rs cannot create.

**The Python reader** is `tools/lake/deltaread.py`: delta-rs reads a table its pyarrow reader reads completely; any other table is read
by Delta Kernel, the server's reader, through the server's exec jar (`com.ash.drishti.deltalake.read.DeltaRowsCli`), so Python reads
exactly what the server reads. delta-rs alone is not enough: version 1.6.6 (the latest) refuses deletion vectors and v2 checkpoints,
and reads a column-mapped table of reader version 2 (the protocol Databricks gives column mapping) as all nulls.

| Feature | Java reader (server) | Python reader (`deltaread`) | delta-rs appends (the feature's flag only) |
|---|---|---|---|
| `appendOnly`, `invariants`, `checkConstraints`, `generatedColumns`, `changeDataFeed` | reads | reads (delta-rs) | yes |
| `timestampNtz`, `variantType`, `variantType-preview` | reads | reads (delta-rs) | yes |
| `deletionVectors` | reads, the deleted rows left out (tested with real deletion-vector files) | reads (Kernel), the same rows | yes |
| `v2Checkpoint` | reads a table whose files only its single-file checkpoint names (tested); with sidecar files: **not verified** | reads (Kernel), the same | yes |
| `columnMapping`, mode `name` or `id` | reads: every path (documents, columns, type-ahead, vectors, partition values, statistics) uses the physical names (tested) | reads (Kernel) | yes |
| `domainMetadata`, `identityColumns`, `rowTracking`, `icebergCompatV1` (UniForm) | reads | reads (delta-rs) | **refused** |
| `typeWidening`, `inCommitTimestamp`, `vacuumProtocolCheck`, `clustering`, `collations`, `allowColumnDefaults`, `icebergCompatV2`/`V3`, `timestampNanos` | **not verified**: Kernel declares read support; delta-rs cannot create them | through Kernel when delta-rs refuses them; **not verified** | not tested |

The server reads through Kernel's scan plan (the files, their partition values, deletion vectors), and its fast paths then read the
Parquet columns of those files directly, by their physical names (`delta.columnMapping.physicalName`, which Databricks writes in both
modes). A v2 checkpoint with sidecar files is left to Kernel's declared support: neither delta-rs nor Kernel 4.4.0 writes sidecars, so
there is no local fixture. **Writing into an existing external table** (one Databricks writes) that uses deletion vectors, column
mapping or another of these features is **not verified**: Drishti's writers write and append to their own plain tables (protocol 1/2,
no features). A table that needs a feature no reader knows is refused, never half-read:

- Java reader (and the Python reader through it): `UnsupportedTableFeatureException: Unsupported Delta table feature: table requires
  feature "madeUpFeature" which is unsupported by this version of Delta Kernel.`
- Python writer: `Unsupported table features required: [RowTracking]`; `lake maintain` compacts and vacuums with delta-rs, so on
  such a table it stops with that message and changes nothing.

### 14.4 Maintenance in protocol terms

`drishti.py lake maintain` (section 2.6) does each step as an ordinary commit any engine reads: compaction is delta-rs
`optimize.compact` (OPTIMIZE: the small files removed and the rewritten ones added, `dataChange: false`); a checkpoint is a classic
Parquet checkpoint with `_last_checkpoint`; old log files go after `delta.logRetentionDuration`; vacuum deletes only files no version
within `time-travel` needs. A table Databricks also maintains follows the same rules.

### 14.5 Check it yourself

```bash
python3 tools/drishti.py lake check
uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/lake/test_delta_interop.py -q
```

Reading a Drishti table with delta-rs, as Spark or Databricks would:

```python
from deltalake import DeltaTable
t = DeltaTable("data/delta/risklake/trading/trade_endur")
print(t.protocol(), t.to_pyarrow_table().num_rows)
```

A table with deletion vectors, column mapping or a v2 checkpoint (one Databricks wrote, say) is read completely from Python with
`deltaread` (it needs the server's exec jar and Java 21):

```python
import sys; sys.path.insert(0, "tools/lake")
import deltaread
print(deltaread.read("data/delta/risklake/trading/trade_endur", date="2026-11-06").num_rows)
```

Section 10 is the other way round: Drishti reading the tables a lakehouse writes.

## 15. Iceberg spec compliance

Drishti's Iceberg tables are standard Apache Iceberg tables, and Drishti reads other engines' Iceberg tables to the
[Iceberg table spec](https://iceberg.apache.org/spec/) with no workaround: Spark, Trino, Flink, Snowflake, AWS Glue, pyiceberg and the Apache
Iceberg Java library read what Drishti writes, and Drishti reads what they write, or refuses a table by name. It never reads one wrongly.
The design is in [DATA_ARCHITECTURE.md › drishti-iceberg](../architecture/DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine);
the connectors are [ICEBERG_CONNECTOR.md](../../plugins/drishti-plugin-iceberg/README.md) and
[ICEBERG_EXTERNAL_CONNECTOR.md](../../plugins/drishti-plugin-iceberg-external/README.md).

### 15.1 Who reads and writes

| Engine | Where | Reads | Writes |
|---|---|---|---|
| **drishti-iceberg** (`engine: native`, the default) | inside the server, the loader (`IcebergLoader`, `lake build --format iceberg`, the data inbox) and maintenance (`IcebergMaintenance`); the `iceberg-external` connector | format v1 and v2 | v1 and v2 tables in Drishti's layout (section 1): partitioned by `business_date` |
| **Apache Iceberg Java 1.10.1** (`engine: library`) | the `iceberg` connector, loader and maintenance when chosen | everything the library reads | everything the library writes |
| **Python** (`tools/lake`) | `lake build --format iceberg` streams rows to the loader above; `lake maintain` and Admin → Lakes read the metadata JSON for the figures, then run `IcebergMaintenance` | | no Iceberg writer of its own |

### 15.2 File paths and partition values

Per the spec, a data file's path in a manifest is opaque: a reader opens it **as written**, never percent-decoding it, and takes partition
values from the manifest, never from the folder name. Each writer names its partition folders its own way. The Apache library and pyiceberg
URL-encode the value, so the folder name on disk holds the `%XX` literally. Captured with pyiceberg 0.12.0, an identity partition on `g`
with the table at `file:///…/wh/ns/t`:

| Value of `g` | Data file path in the manifest (after `…/ns/t/data/`) |
|---|---|
| `a b` | `g=a+b/00000-0-….parquet` |
| `a/b` | `g=a%2Fb/…` |
| `100%` | `g=100%25/…` |
| `a%2Fb` (the text itself) | `g=a%252Fb/…` |
| `a:b`, `a=b`, `a\b`, `a+b` | `g=a%3Ab/…`, `g=a%3Db/…`, `g=a%5Cb/…`, `g=a%2Bb/…` |
| `.` and `..` | `g=./…` and `g=../…` (folder names that start with `g=`, never a path step) |
| `x` U+0001 `y`, `é漢` | `g=x%01y/…`, `g=%C3%A9%E6%BC%A2/…` |
| empty text | `g=/…` |
| null | `g=null/…` (the text `null` gets the same folder; the manifest tells them apart) |

Drishti opens each of these as written: `g=a%2Fb` is that folder, not `g=a/b`. The same holds through `relocate`, whose two sides are also
compared as written. The pairs in `relocate` are plain folders or `file:` paths spelled as the metadata spells them (`/data/100%25` names
the folder `100%25`). Drishti's own writers make `business_date=YYYY-MM-DD` folders and plain `file:` locations (`file:/srv/my lake/…`),
the form the library uses. The library engine's lake is the folder its `root` names, spaces, `%` and unicode included.

### 15.3 The feature table

| Area | Drishti reads | Drishti writes | Otherwise |
|---|---|---|---|
| Format versions | 1, 2 | 1, 2 (the loader makes 2) | 3: refused by name |
| Partition transforms | identity, bucket[N], truncate[W], year, month, day, hour, void; pruned by each; the partition values the library computes, value for value | identity on `business_date` | an unknown transform is read, never pruned; a table another engine **re-partitioned** (any field besides `business_date`) is refused for writing |
| Partition evolution | every spec, void fields, a field whose source column was dropped | | |
| Schema evolution by field id | add, drop, rename, reorder, int→long, float→double, decimal widening; nested structs, lists, maps; `schema.name-mapping.default` for files without ids | adds optional columns | |
| Sort orders | read | `sort_order_id` set when the files are sorted by `id` | |
| Snapshots, time travel | by snapshot id and by time (*known at*, the snapshot log) | one snapshot per commit | |
| Refs | the `main` branch | moves `main`, keeping its retention settings | other branches and tags are not read by name; expiry keeps every branch's and tag's snapshot |
| Position deletes (v2) | applied by the spec's sequence-number rule (`referenced_data_file` and `file_path` bounds honoured) | never writes delete files (rewrites copy on write) | |
| Equality deletes (v2) | applied by partition and sequence number, unpartitioned ones globally, null equal to null | | |
| Deletion vectors (v3) | | | refused (format version 3) |
| Row lineage, default values, `variant`, `geometry`, `geography`, `timestamp_ns`, `unknown`, multi-argument transforms (v3) | | | refused by name |
| Data files | Parquet | Parquet with field ids and column metrics | ORC and Avro data or delete files: refused, naming the file |
| Manifests and manifest lists | Avro v1 and v2 from any writer, inherited snapshot ids and sequence numbers | Avro v1 and v2 | |
| Statistics (Puffin) | not used | not written; expiry drops the entries of expired snapshots | |
| Metadata tables (`snapshots`, `files`, …) | | | not offered as tables; Admin → Lakes and Health show snapshots and files |
| Encryption | | | refused (`encryption-keys`, a file's `key_metadata`) |
| Catalogs | path-based (`version-hint.text` and `vN.metadata.json`, or a catalog's `NNNNN-uuid.metadata.json`), a metadata file named directly, REST (Polaris, Snowflake Open Catalog, AWS Glue's REST endpoint, Unity Catalog, Nessie, Lakekeeper, Gravitino), AWS S3 Tables | path-based, REST | Hive metastore, JDBC/SQL catalog, the Glue SDK: through a REST catalog in front, or `metadata-location.<table>` |
| Storage | local disk (Windows too), `s3://`, `s3a://`, `s3n://`, `mem://` | the same | `abfs://`, `gs://`, `hdfs://`: `engine: library` |
| Maintenance | | expire snapshots (keeping every branch and tag), rewrite a day's files, rewrite manifests, remove orphans, drop old days ([ICEBERG_CONNECTOR.md](../../plugins/drishti-plugin-iceberg/README.md#retention-and-maintenance)) | |

### 15.4 What a refusal says

A table or file the native engine does not read stops the read with this message (`[…]` names the table or file where it was met):

```text
drishti-iceberg does not read format version 3 tables; use the connector's engine: library for this table [<table>]
drishti-iceberg does not read ORC data files; use the connector's engine: library for this table [<the .orc file>]
drishti-iceberg does not read the Iceberg type 'variant' (format version 3); use the connector's engine: library for this table
drishti-iceberg does not read column defaults (format version 3) on NOTIONAL; use the connector's engine: library for this table
drishti-iceberg does not read encrypted data files; use the connector's engine: library for this table [<the file>]
```

A load into a table another engine re-partitioned stops before any file is written (the parallel loader's wording; the Iceberg loader's says the same):

```text
<table> is not in the layout: its partition spec 1 has the field 'id_bucket_8' (bucket[8]) beside the identity partition on
business_date; this writer writes only tables partitioned by business_date alone (write this table with engine: library, or restore its spec)
```

### 15.5 Check it yourself

`IcebergSpecInteropTest` (plugins/drishti-plugin-iceberg) runs both directions against the Apache Iceberg Java library: the library
writes a table partitioned by identity, bucket, truncate, year, month, day, hour and truncate of a decimal, with 26 partition values
(space, `/`, `%`, `%2F`, `%20`, `:`, `=`, `.`, `..`, `\`, control characters, a tab, a newline, quotes, unicode, an emoji, `+`, `?#`, `*`,
`~`, 200 characters, empty, `__HIVE_DEFAULT_PARTITION__`, the text `null`, null), then position and equality deletes. Drishti reads
every row the library reads, computes each file's partition values as the library did, and prunes to exactly the matching rows.
Drishti expires a library table's snapshots, and the library still reads its tag and its branch. `PyicebergReadsNativeTest` and
`NativeReadInteropTest` do the same with pyiceberg.

```bash
./mvnw -pl plugins/drishti-plugin-iceberg -am test -Dtest='IcebergSpecInteropTest,PyicebergReadsNativeTest,NativeReadInteropTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Read a Drishti table from Python with pyiceberg alone:

```python
from pyiceberg.table import StaticTable
t = StaticTable.from_metadata("file:///srv/drishti/data/iceberg/risk/pnl-vector/metadata/v12.metadata.json")
print(t.scan(row_filter="business_date = '2026-09-30'").to_arrow().num_rows)
```
