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
bank's own data: trades, positions, the scenario PVs that VaR is computed from, and the VaR limits of the hierarchies' nodes, into a lake of their own
(called `risklake` throughout), beside the demo lake and never mixed with it.

The hands-on walk-through of the same, every step in the UI, on the command line and in YAML, is
[tutorial 16, Part A, Load your own data into a new lake](../tutorials/16-load-your-own-data-into-a-new-lake.md#part-a--a-new-lake-of-your-own). Connecting a source that is
not a lake (a database, a file folder, Kafka) is the [data guide](DATA_GUIDE.md); telling Drishti that an outside job has loaded data is
its section 1. The in-memory lake (feeds that may never touch a disk) is [section 13](#13-the-in-memory-lake).

Every example below uses invented, Endur-style data (`TRADE_ID`, `PRODUCT_TYPE`, `DESK`, `BOOK`, `COUNTERPARTY_ID`); outputs marked
*captured* were produced on a scratch data folder with 2,000 trades, 300 positions of 509 scenarios and 26 VaR limits.

## Contents

1. [What a lake is](#1-what-a-lake-is)
2. [The lake file, every key](#2-the-lake-file-every-key)
3. [Loading, three ways](#3-loading-three-ways)
4. [The `.ready` file, every key](#4-the-ready-file-every-key)
5. [Routing, business dates, modes, repeats and undo](#5-routing-business-dates-modes-repeats-and-undo)
6. [Pointing Drishti at a lake](#6-pointing-drishti-at-a-lake) (6.2: [several source systems](#62-several-source-systems-shared-or-separate-tables))
7. [The demo lake's one-time move](#7-the-demo-lakes-folder)
8. [Checking a load, and troubleshooting](#8-checking-a-load-and-troubleshooting)
9. [Sizing: a day of 1M trades and 50K positions × 509 scenarios](#9-sizing-a-day-of-1m-trades-and-50k-positions--509-scenarios)
10. [Connecting to an existing lakehouse (Databricks, Unity Catalog)](#10-connecting-to-an-existing-lakehouse-databricks-unity-catalog)
11. [Archives: zip and tar](#11-archives-zip-and-tar)
12. [Other lakes, packs and older installations](#12-other-lakes-packs-and-older-installations)
13. [The in-memory lake](#13-the-in-memory-lake)
14. [Delta protocol compliance](#14-delta-protocol-compliance)
15. [Iceberg spec compliance](#15-iceberg-spec-compliance)
16. [Runbooks: production data into the lake](#16-runbooks-production-data-into-the-lake) (16.1: [scenario PVs for VaR](#161-production-position-level-scenario-pvs-for-var-explain-and-why-it-moved); 16.2: [trades](#162-production-trades-from-the-source-systems); 16.3: [netting sets](#163-production-netting-sets-with-their-agreements-and-csas))
17. [Daily loads: the business-day runbook](#17-daily-loads-the-business-day-runbook)

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
    ├── risk/var-limit/
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
answers, VaR equal to the cent ([tutorial 16, Part E](../tutorials/23-the-risk-lake-on-iceberg.md)). An Iceberg table of this layout is partitioned by
business date alone.

**In memory.** A lake whose root is `mem://<store>` lives in the server's memory under a hard cap, for feeds that may never be written
to disk: [section 13](#13-the-in-memory-lake).

**Where the files are read from.** `config/lakes/` (the system property `lake.lakes.dir` or `LAKE_LAKES_DIR` say otherwise). The
demo lake's file also ships inside the server, so a server started in another folder, a container or a cluster node knows it; a
`demo.yaml` in the folder wins. Example lake files for S3, Iceberg path tables, AWS S3 Tables, a REST catalog behind a private CA and an
in-memory store are in `config/lakes.examples/`, each beside its connectors in `config/connectors.examples/`.

**The lakes Drishti ships.** `demo` (the demo data, built from the committed test data in `data/testdata/`) and six lakes that ship as definitions only: `risklake` (the bank's own risk data; its lake file holds the tables the bank-data loaders write), `newslake` (news), `worldlake` (the World Bank, the IMF, the United Nations), `regulators` (the SEC, the Federal Reserve, OSFI and other central banks), `capitalmarket` (prices, curves, volatility surfaces, funds) and `referencelake` (core reference data: GLEIF legal entities, ownership, identifier mappings). Each has its lake file in `config/lakes/` (`risklake`'s holds the tables the bank-data loaders write, the public-sector lakes' the tables their packs read, the others none) and its folder `data/delta/<lake>/`, which set-up makes; what a build or a load writes there is never committed. Nothing shipped points at the six: every connector reads `demo` until you point it elsewhere (section 6); only the data feeds (`config/feeds/`, [WORLD_DATA_GUIDE.md](WORLD_DATA_GUIDE.md)) name them, as the lakes they load.

## 2. The lake file, every key

Why one file per lake, who reads it and how it is built: [DATA_ARCHITECTURE.md section 8](../architecture/DATA_ARCHITECTURE.md#8-the-lake-file-one-description-of-every-lake).

### 2.1 A worked example: `config/lakes/risklake.yaml`

Trades from Endur, positions and their scenario PVs, and the VaR limits of the hierarchy's nodes:

```yaml
lake: risklake                    # its name: the file's name (lower-case letters, digits, - and _)
format: delta                     # delta (the default) | iceberg
# root: not given: the lake's own folder, ${lake.data.dir:${drishti.data.dir}}/delta/risklake
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
  risk.var-limit:                 # the VaR limits of the hierarchy's nodes: BANK > DESK > BOOK
    promote: [LIMIT_ID, HIERARCHY, NODE_ID, LIMIT_VAR, CURRENCY]
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
    risk.var-limit                           risk/var-limit                           partitioned by business_date; 5 promoted
```

### 2.2 The lake's own keys

| Key | Means |
|---|---|
| `lake` | the lake's name; must equal the file's name |
| `format` | `delta` (default) or `iceberg` |
| `root` | where it is: a local folder, `s3://bucket/prefix`, or `mem://<store>`. Not given: `${lake.data.dir:${drishti.data.dir}}/delta/<lake>` (`…/iceberg/<lake>` for Iceberg) |
| `storage` | how to reach it: `region`, `endpoint`, `path-style`, `tls.*`, `proxy.*`. Credentials come from the platform (an instance or task role, a profile) or as `${ENV}` references; a secret written as a value is refused |
| `catalog` | Iceberg only: `none` (path tables, the default), `rest`, `glue`, `s3tables`, `hadoop`, with their settings (`uri`, `warehouse`, `token: ${…}`) |
| `defaults` | any table key, for every table that does not set it |
| `tables` | the tables, each `<domain>.<table>: {…}` (below) |
| `memory` | an in-memory lake only: `max-mb`, `chunk-kb`, `contract`, `owner`, `idle-trim-minutes` ([section 13](#13-the-in-memory-lake)) |
| `description` | free text, shown on Admin → Lakes |

Values may name environment variables or server settings: `${NAME}`, `${NAME:default}`, nested
(`${DRISHTI_DELTA_ROOT:${lake.data.dir:${drishti.data.dir}}/delta/demo}`). A variable that is not set and has no default refuses the file, naming
it. An unknown key, a format that is not delta or iceberg, a fifth partition column, a load mode that is not replace or append, a
secret written as a value, or a `lake:` that differs from the file's name: each refuses the file with the key named.

A site moves a lake by its name without editing its file: `[lake.<name>] root=` and `storage.<key>=` in the settings files win over the
file's `root:` and `storage:`, and a pack's demo-lake connectors read another lake with `[pack.<pack>] lake=<name>`; each lake moves alone
([CONFIGURATION.md, "Named lakes, databases and brokers"](../admin/CONFIGURATION.md#named-lakes-databases-and-brokers)).

A table's entry:

| Key | Means | Section |
|---|---|---|
| `kind` | the pack's kind, when the entry is not named after it (`trading.trade_endur` holds `kind: trade`) | |
| `path` | the table's folder under the root (default `<domain>/<table>`) | |
| `uri` | the table's own location, an absolute path or URI (`s3://fo-data/mx/trades_v7`): nothing is joined to it, so a bank's lake keeps its own layout (an absolute `path:` means the same) | 2.2.1 |
| `contract` | the schema the table is read by, a kind's contract (`trade@1`); empty: the kind's own | 2.2.1 |
| `format` | delta or iceberg, when it differs from the lake's | |
| `partition-by` | the partition columns after `business_date` (at most four; Delta only) | 2.3 |
| `promote`, `vectors`, `flatten` | the columns beside the document | 2.4 |
| `id`, `date`, `latest-by` | the id field, the rows' own date field, which copy of an id wins | 2.4 |
| `load-mode` | `replace` (the default) or `append` | 5.3 |
| `sort-by`, `file-rows`, `row-group-rows`, `file-size-mb`, `row-group-mb`, `compression`, `vector-compression` | the file layout | 2.5 |
| `retention`, `maintenance`, `time-travel` | how long dates and old versions are kept, rows dropped for their age, compaction, vacuum, checkpoints | 2.6, 2.7 |
| `properties` | Delta or Iceberg table properties, passed through as they are | 2.6 |

#### 2.2.1 Tables by symbolic name: where a name is

Code, packs, Sutras, engine inputs and contracts name a table only by its symbolic name (`trading.trade`, `risk.scenario-pv`); the lake
file of the installation says where that name is, so the demo lake, the owner's lake and a bank's external lake differ in configuration
only. A table with no `uri:` is at `<root>/<domain>/<table>`, the layout Drishti gives its own lakes; a table with one is exactly there:

```yaml
lake: bank
format: delta
root: s3://bank-lake/curated
tables:
  trading.trade_murex:
    kind: trade
    uri: s3://fo-data/mx/trades_v7     # any absolute location; its last two folders are the plugins' domain and table folder
    contract: trade@1
```

One resolver makes the location, `TableCatalogue` (Java) and `lakeconfig.locate` (the Python tools); a guard test fails a main source that
joins a root, a namespace and a table itself. **Admin → Lakes** shows each table's resolved location, format, contract and whether it was
configured (its own `uri:`; `${lake.root}/...` anchors it to the lake's root) or a fallback (the lake's layout puts it: `<root>/<domain>/<table>`).
Every table of the reference installation is configured; the production profile refuses a fallback, and `drishti.py lake entries` writes the
explicit entries for a lake that still has some. [Every table of the reference installation](../admin/TABLE_CATALOGUE.md), with what it holds,
who uses it and how a bank binds its own external lake, is generated from the lake files by `drishti.py docs generate`. A table's name never follows a module or pack: reorganising modules moves
no data.

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
| `promote` (a list) | `[TAGS, TRADE_IDS]` | a field whose every value is a **list of simple values** (texts, numbers, booleans) is written as a native **array column** (`array<string>` in Delta, `list<string>` in Iceberg): each value as text, a number in its plain text (`5`, never `5.0`), a null element left out. A list of objects is **not** promoted as a list: it stays in the document (read as a sub-table), its column keeps the JSON text. Filters offer *contains / any of / all of* on it, the pivot counts each value, and screens show it as chips. Delta keeps **no min/max statistics** for an array column (no file is skipped by it), and an array **cannot be a partition column**. The Pack designer and `pack new` promote lists of simple values themselves |
| `vectors` | `[SCENARIO_PV]` | list-of-numbers fields: the numbers are written as `list<double>` columns and the document keeps a reference, `{"$vector": "SCENARIO_PV"}`, naming the column. VaR reads the vector column directly ([STORAGE.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/STORAGE.md), the lake file) |
| `flatten` | `{LEGS[0].NOTIONAL: LEG1_NOTIONAL}` | a nested field written as a column of its own (and promoted) |
| `id` | `TRADE_ID` | the field that holds each row's id (default: the kind's key from its pack, its ingest key else the single `key:` field of the kind's data contract; else `id`) |
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

Drishti Lake owns the upkeep of every table, Delta, Iceberg and plain files alike: `drishti.py lake maintain` (and Admin → Lakes'
maintenance) runs Lake's `lake maintain` with the table's own settings over the maintenance file's (`<data>/lakes/lake-maintenance.yaml`),
and never below the domain's floor (risk and risk results keep seven years whatever is set). The options (retention by business dates with
the drop guard, compaction and relayout, checkpoint, vacuum and snapshot expiry, time travel, table properties), what each does and the
guards that refuse a run that would delete too much are in Lake's [LOADER.md, Maintenance](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/LOADER.md#maintenance-lake-maintain); the lake
file's keys that set them are in [STORAGE.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/STORAGE.md#the-lake-file). A maintenance key it does not know is said in the run's log,
never guessed.

### 2.7 Row retention: the same option in memory, Delta, Iceberg and files

A table can drop rows for their age, not only whole business dates. The option is one block, `retention:`, and it means the same in all four stores
(the in-memory lake of section 13, a Delta table, an Iceberg table and a files store); a table's scalar `retention: 7y` (section 2.6) is
unchanged, and a block may carry both (`dates: 7y`):

```yaml
tables:
  trading.trade:
    retention:
      ttl: 8h                # rows older than this are dropped (500ms, 30s, 15m, 8h, 1d)
      on: UPDATED_AT         # the field the age is read from: a promoted column or a document field (a time, a date, epoch seconds or milliseconds)
      max-rows: 100000       # then at most this many rows are kept, the newest by the same age
```

| Rule | Detail |
|---|---|
| The age | The field `on`. Without `on`: the column `arrived_at` (a memory table aged by arrival writes it), else the row's business date. A row whose age cannot be read is never dropped |
| `ttl`, `max-rows` | Rows older than `ttl` go; of the rest the newest `max-rows` stay, and rows tied at the cut all stay. Either alone is fine |
| Absent | No limit; nothing changes |
| Who applies it | One rule, one class (`RowRetention`), so the four stores leave the same rows. A parity test loads the same rows into all four with the same block and compares what is left, and reads the Delta result with delta-rs |
| When | On the store's own upkeep, never a second scheduler |

| Store | Where it runs | What it does |
|---|---|---|
| Memory | the compaction (section 13.5) | the rewrite leaves out the dropped rows |
| Delta | `drishti.py lake maintain` (a step before compaction and vacuum; Admin → Lakes → Maintain, its schedules) | each business date holding a dropped row is rewritten without it, one Delta commit per date (a date left empty has its files removed); the files the commit stopped using are removed by the table's vacuum (`maintenance.vacuum`). Any Delta reader sees the result, and delta-rs and Spark read it |
| Iceberg | the same run (`lake maintain --format iceberg`, with `--ttl --on --max-rows`) | each day holding a dropped row is replaced by one Iceberg commit (a day left empty: its partition is deleted); the old files go when snapshot expiry (`maintenance.expire-snapshots`) says |
| Files | the same run for a maintenance lake of `format: files` (`<root>/<domain>/<date>/<kind>.jsonl`) | each affected day file is rewritten through a temporary file moved into place, so a running server never reads half a day |

Where the block is written: a lake file's table entry (the table's own), or `row-retention:` in the maintenance file's lake or table settings
(`lakes[].tables.<domain>/<table>`), the table's own over the lake's. The risk and risk-results domains keep their floor (section 2.6): a ttl under
the kept years is raised to them and a `max-rows` is not applied. `drishti.py lake maintain --dry-run` counts the rows a run would drop per date.
A Delta or files run reads the table's rows to judge them, so its memory is about the table's size (an Iceberg run keeps only each row's age in memory and spills the rewrite); a table too large for that is better trimmed by `retention: 520d` (whole dates). Admin → Lakes shows a memory table's retention and the rows it dropped, and a durable table's page the rows it keeps. A memory table that is also
bound to a durable table keeps its own retention in each place: the memory table's ttl trims the memory only, and the durable table keeps what its
own block says (none by default).

## 3. Loading, three ways

**Who owns what.** Loading is done by **Drishti Lake**, one loader in Java and one in Python on the same configuration. The load's rules
(the business date of a file, replace and append, the guards `future-days`, `zone`, `max-drop-share` and `force-drop`, the newest of two
loads of one date winning by commit order, one winner when two loads replace the same date, the archive rule, the `.ready` hand-over and
the inbox, undo, delete and retention, `lake maintain`) are written once, in Lake's [LOADER.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/LOADER.md). Drishti adds what only it
knows: routing a file to a table and a lake by its packs, the Admin pages, the server's signal after a load, and the commands below.
The sections that follow show those with Drishti's commands and files.

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

Run these from the Drishti folder (the repository root, or the install folder of a release).

```bash
python3 tools/drishti.py server engines on dataload --reason "load risklake"
```

or `DRISHTI_DATALOAD_ENABLED=true`. In a cluster, set `owner: true` on one node only (`DRISHTI_DATALOAD_OWNER`); a folder lock keeps a
second runner out anyway.

**The folders**, all under `<data>/dataload` (`dir`), on one file system so a move is an atomic rename. A clone has them already, each with
only a `README.md` that the inbox ignores (never claimed, listed or moved; what a load writes there is never committed); the server makes any
that is missing, and `drishti.py setup` makes them, with the READMEs, in a data folder outside the clone:

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

**Loading a production batch.** Position-level scenario PVs for VaR, step by step with the files, the fields, the checks and the traps (a name without a date loads under today's date): [16.1](#161-production-position-level-scenario-pvs-for-var-explain-and-why-it-moved).

**One cycle**, every `interval` (10 minutes), on the owner node:

1. **Skipped** while an earlier run still loads (runs never overlap: *skipped: run 20261107T150000Z-drishti-main still loading (12
   min)*), when the server is short of memory, or when the disk is below the server's free-space floor.
2. **The whole ready batch** (every ready, stable file at that moment, oldest first) is moved at once into `inprocess/<run>/`; files that
   become ready during the run wait for the next cycle. `max-files` and `max-gb` (off by default) bound one run when set.
3. **Never twice**: a file whose SHA-256 the ledger shows as loaded, and whose rows the lake still holds, goes straight to the outbox,
   *already loaded in run …*; when a later load replaced (or an undo removed) its date, the same file loads again. A changed
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
engine: java-loader (the parallel lake loader (the default engine))
business date  PRODUCT_TYPE           rows
2026-11-06     CDS                     130
2026-11-06     CDS_INDEX               140
...
2026-11-06     WARRANT                 125
documents read: 2000, rows written: 2000, rejected: 0
  business date 2026-11-06 from the file name: trade_endur.2026-11-06.jsonl.gz
summary: data/delta/risklake/_drishti-lake/runs/20261107T080146.064-1786979.json
2026-11-07T08:01:53-05:00 lake-build ok exit=0 run=20261107T080146.064-1786979 domain=trading kind=trade engine=java-loader mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=15 seconds=7.012
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
java -Xmx2g -Dloader.main=com.ash.drishti.lakehost.DrishtiLakeCli -jar lib/drishti-server-<version>-exec.jar \
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
are [DATA_ARCHITECTURE.md section 10](../architecture/DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-lake-load).

### 3.4 Pushing data from your own programs

Your system writes the rows; Drishti loads them. The safe way, at any volume, is the **data inbox** (section 3.1): your program writes a
JSON Lines file (gzipped) into the inbox, and its `.ready` file **last**. Both programs below were run against a server with the inbox on
and their rows checked in the lake; they are checked in under [examples/lake-push/](examples/lake-push/) (`push_inbox.py`,
`PushInbox.java`), and a test keeps this page equal to them.

**The three rules every pusher follows:**

1. **Write, then rename.** The data file is written outside the inbox (here `<dataload>/staging/`, beside `inbox/` on the same file
   system) and renamed in when complete: one atomic rename, so the inbox never sees half a file.
2. **The `.ready` file last**, the same way, with `sha256:` and `rows:` of what you wrote: a file cut short is refused before anything is
   written. The inbox also waits until both are unchanged for `stable-seconds` (30).
3. **`mode: replace`** for a day you may send again: a re-run replaces that business date in one commit, never doubles it.

**Name the file `<table>.<YYYY-MM-DD>.jsonl.gz`** (the business date in the name), or give `table:` and `business-date:` in the `.ready`
file. The table is a pack's kind or, with `connector:`, one of its tables: for trades, whose kind `trade` has one table per source system,
name the connector (`trade-endur` writes `trading/trade_endur`), or name the file after the table (`trade_endur.2026-11-06.jsonl.gz`
resolves to the kind `trade` and the connector `trade-endur`). **No `key:` is needed**, also into a lake that does not exist yet: a table
created by its first load takes the pack's layout and its kind's key as its `id:` (the pack's ingest key, else the `key:` of the kind's data
contract, `TRADE_ID` for trades); `key:` in the `.ready` file wins when you give it.

#### (a) Python: one file, a tar, and `lake build`

The script uses Python's standard library only. One file:

```bash
python3 push_inbox.py --inbox <data>/dataload/inbox --table trade_endur --date 2026-11-06 --source export.jsonl.gz \
    --lake risklake --pack trading --kind trade --connector trade-endur
```

```text
pushed <data>/dataload/inbox/trade_endur.2026-11-06.jsonl.gz and trade_endur.2026-11-06.jsonl.gz.ready
```

```yaml
# the .ready file it wrote (captured)
pack: trading
kind: trade
connector: trade-endur
lake: risklake
mode: replace
sha256: ef759f8486980a5b311b5efdf90ea575be65655412b28e983963b61dfb4139c6
rows: 164
```

**A tar of several tables and dates**, all or nothing: `<folder>/<YYYY-MM-DD>/part-0001.jsonl.gz` inside. A folder is a **table**
(`trade_endur`, `trade_calypso`: the tables of the kind `trade`, one per source system) or a **kind** with one table (`var-limit`: [contract](../contracts/packs/market-risk.md#vlim-var-limit-of-a-hierarchy-node-var-limit), [example](../contracts/packs/market-risk.md#example-market-risk-var-limit),
written `var_limit` too), so one archive may mix the trades of several systems with the limits:

```bash
python3 push_inbox.py --inbox <data>/dataload/inbox --tar eod-2026-11-06 \
    --part trade_endur:2026-11-05:endur-1105.jsonl --part trade_endur:2026-11-06:endur-1106.jsonl \
    --part trade_calypso:2026-11-05:calypso-1105.jsonl --part trade_calypso:2026-11-06:calypso-1106.jsonl \
    --part var_limit:2026-11-05:limits-1105.jsonl --part var_limit:2026-11-06:limits-1106.jsonl \
    --folders trade_endur=trading,trade_calypso=trading,var_limit=market-risk --lake risklake
```

You should see the pair go through the inbox within a cycle; the outbox's `eod-2026-11-06.tar.loaded.json` says `"outcome" : "loaded"`
with one entry per table, and each table has a folder per date (`business_date=2026-11-05`, `business_date=2026-11-06`). A lake with no
entry for a table yet (or no file at all) gets the pack's entry for it, with the kind's key as its `id:` (`TRADE_ID`, `NODE_ID`): nothing to
prepare. `--folders` is optional when each folder names one pack's table or kind.

```python
"""Push rows into a Drishti lake through the data inbox (docs/guides/DATA_LAKE_GUIDE.md section 3.4). Python's standard library only.

One file:  push_inbox.py --inbox <data>/dataload/inbox --table trade_endur --date 2026-11-06 --source rows.jsonl.gz --lake risklake \
               --pack trading --kind trade --connector trade-endur
A tar:     push_inbox.py --inbox ... --tar eod-2026-11-06 --part trade_endur:2026-11-06:a.jsonl.gz --part var_limit:2026-11-06:b.jsonl \
               --folders trade_endur=trading,var_limit=market-risk --lake risklake

The data file is written in <dataload>/staging/ and renamed into the inbox when complete (one atomic rename); its .ready file,
with the SHA-256 and the row count, is written LAST, the same way. A re-run of a day is safe with --mode replace."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import tarfile
from pathlib import Path


def documents(source: Path):
    """Your rows, one JSON object each: here read from a JSON Lines export (.jsonl or .jsonl.gz); yield dicts from your program instead."""
    opener = gzip.open if source.name.endswith(".gz") else open
    with opener(source, "rt", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


def write_jsonl_gz(rows, out) -> int:
    """Rows as gzipped JSON Lines into a binary stream; the number written."""
    n = 0
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as gz:
        for row in rows:
            gz.write((json.dumps(row, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8"))
            n += 1
    return n


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def place(inbox: Path, name: str, write) -> Path:
    """Writes inbox/<name> atomically: first into <dataload>/staging/ (beside the inbox, the same file system), flushed, then renamed in."""
    staging = inbox.parent / "staging"
    staging.mkdir(parents=True, exist_ok=True)
    final, tmp = inbox / name, staging / name
    with open(tmp, "wb") as f:
        write(f)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, final)
    return final


def ready_text(keys: dict) -> str:
    return "".join(f"{k}: {json.dumps(v) if isinstance(v, dict) else v}\n" for k, v in keys.items() if v is not None)


def push_file(inbox: Path, table: str, day: str, rows, **keys) -> Path:
    """One table, one business date: inbox/<table>.<day>.jsonl.gz, then its .ready with sha256 and rows."""
    counted = {}
    data = place(inbox, f"{table}.{day}.jsonl.gz", lambda f: counted.setdefault("rows", write_jsonl_gz(rows, f)))
    note = {**keys, "sha256": sha256_of(data), "rows": counted["rows"]}
    place(inbox, data.name + ".ready", lambda f: f.write(ready_text(note).encode("utf-8")))
    return data


def push_tar(inbox: Path, name: str, parts: list[tuple[str, str, object]], **keys) -> Path:
    """Several tables and dates in one archive: <table>/<YYYY-MM-DD>/part-0001.jsonl.gz each; one .ready, all or nothing."""
    total = 0

    def write(f):
        nonlocal total
        with tarfile.open(fileobj=f, mode="w") as tar:
            for table, day, rows in parts:
                buf = io.BytesIO()
                total += write_jsonl_gz(rows, buf)
                info = tarfile.TarInfo(f"{table}/{day}/part-0001.jsonl.gz")
                info.size = buf.tell()
                buf.seek(0)
                tar.addfile(info, buf)

    data = place(inbox, f"{name}.tar", write)
    note = {**keys, "sha256": sha256_of(data), "rows": total}
    place(inbox, data.name + ".ready", lambda f: f.write(ready_text(note).encode("utf-8")))
    return data


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--inbox", required=True, type=Path, help="<data>/dataload/inbox of the inbox's owner node")
    ap.add_argument("--table"), ap.add_argument("--date"), ap.add_argument("--source", type=Path)
    ap.add_argument("--tar", help="an archive's name (without .tar)")
    ap.add_argument("--part", action="append", default=[], help="table:YYYY-MM-DD:source, for --tar")
    ap.add_argument("--folders", help="table=pack,... for --tar")
    ap.add_argument("--lake"), ap.add_argument("--pack"), ap.add_argument("--kind"), ap.add_argument("--connector")
    ap.add_argument("--key", help="the id field (default: the lake entry's id:, else the kind's key from its pack: TRADE_ID)")
    ap.add_argument("--mode", default="replace", choices=["replace", "append"])
    ap.add_argument("--hold", action="store_true", help="the inbox waits until hold is removed from the .ready file")
    a = ap.parse_args()
    a.inbox.mkdir(parents=True, exist_ok=True)
    common = {"lake": a.lake, "mode": a.mode, "hold": True if a.hold else None}
    if not a.tar:
        common["key"] = a.key
    if a.tar:
        parts = [(t, d, documents(Path(s))) for t, d, s in (p.split(":", 2) for p in a.part)]
        folders = dict(x.split("=", 1) for x in a.folders.split(",")) if a.folders else None
        out = push_tar(a.inbox, a.tar, parts, folders=folders, **common)
    else:
        out = push_file(a.inbox, a.table, a.date, documents(a.source), pack=a.pack, kind=a.kind, connector=a.connector, **common)
    print("pushed", out, "and", out.name + ".ready")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

**Without the inbox**, the same rows straight into a lake from the machine that holds it (the `.ready` file is optional here; command-line
options win over it):

```bash
python3 tools/drishti.py lake build --from trade_endur.2026-11-05.jsonl.gz --lake risklake \
    --pack modules/banking/packs/trading --connector trade-endur --key TRADE_ID --mode overwrite-dates
```

```text
… lake-build ok exit=0 … domain=trading kind=trade engine=java-loader mode=overwrite-dates rows=100 rejected=0 dates=1 partitions=4 seconds=2.573
```

#### (b) Java: the JDK alone

`Files`, `GZIPOutputStream` and `MessageDigest`; no other library. `push(...)` takes your own JSON lines; `main` reads them from a file:

```bash
javac PushInbox.java
java PushInbox <data>/dataload/inbox trade_endur 2026-11-04 rows.jsonl lake=risklake pack=trading kind=trade connector=trade-endur
```

```text
pushed <data>/dataload/inbox/trade_endur.2026-11-04.jsonl.gz and trade_endur.2026-11-04.jsonl.gz.ready (100 rows)
```

```java
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Push rows into a Drishti lake through the data inbox (docs/guides/DATA_LAKE_GUIDE.md section 3.4), with the JDK alone.
 *
 * <pre>
 * javac PushInbox.java
 * java PushInbox &lt;data&gt;/dataload/inbox trade_endur 2026-11-06 rows.jsonl lake=risklake pack=trading kind=trade connector=trade-endur
 * </pre>
 *
 * The rows (one JSON object a line) are written gzipped into &lt;dataload&gt;/staging/ and renamed into the inbox when complete; the
 * .ready file, with the given keys, mode replace, the SHA-256 and the row count, is written last the same way. Your program passes its
 * own JSON lines to {@link #push}.
 */
public final class PushInbox {

    private PushInbox() {
    }

    /** One table, one business date: inbox/&lt;table&gt;.&lt;day&gt;.jsonl.gz, then its .ready with {@code keys} (lake, pack, kind, ...). */
    public static Path push(Path inbox, String table, String day, Iterable<String> jsonLines, Map<String, String> keys) throws IOException {
        Path staging = inbox.resolveSibling("staging");                 // beside the inbox: the same file system, so the move is one rename
        Files.createDirectories(inbox);
        Files.createDirectories(staging);
        String name = table + "." + day + ".jsonl.gz";
        Path tmp = staging.resolve(name);
        long rows = 0;
        try (OutputStream out = Files.newOutputStream(tmp);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(out, 1 << 16), StandardCharsets.UTF_8))) {
            for (String line : jsonLines) {
                w.write(line);
                w.write('\n');
                rows++;
            }
        }
        Map<String, String> note = new LinkedHashMap<>(keys);
        note.putIfAbsent("mode", "replace");                              // a re-run of the day replaces it
        note.put("sha256", sha256(tmp));
        note.put("rows", Long.toString(rows));
        Path data = Files.move(tmp, inbox.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        StringBuilder ready = new StringBuilder();
        note.forEach((k, v) -> ready.append(k).append(": ").append(v).append('\n'));
        Path marker = staging.resolve(name + ".ready");
        Files.writeString(marker, ready);
        Files.move(marker, inbox.resolve(name + ".ready"), StandardCopyOption.ATOMIC_MOVE);   // last: the inbox takes the pair from now on
        return data;
    }

    static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: java PushInbox <inbox> <table> <YYYY-MM-DD> <rows.jsonl> [key=value ...]   (lake=, pack=, kind=, connector=, key=, mode=)");
            System.exit(2);
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (int i = 4; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            keys.put(kv[0], kv[1]);
        }
        List<String> lines;
        try (InputStream in = Files.newInputStream(Path.of(args[3]))) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
        Path data = push(Path.of(args[0]), args[1], args[2], lines, keys);
        System.out.println("pushed " + data + " and " + data.getFileName() + ".ready (" + lines.size() + " rows)");
    }
}
```

**The parallel loader instead of the inbox** (every core, bounded memory, a file of any size; section 3.3): `lake build … --engine java`
into a lake whose file has the table's entry (here the demo lake's `trading.trade_endur`; `--key` is passed to the loader, and an entry
without `id:` takes it, else the kind's key), or the loader alone from the server's jar with a load file:

```bash
python3 tools/drishti.py lake build --from trade_endur.2026-11-05.jsonl.gz --lake demo \
    --pack modules/banking/packs/trading --connector trade-endur --mode overwrite-dates --engine java
```

```text
… lake-build ok exit=0 … domain=trading kind=trade engine=java-loader mode=overwrite-dates rows=100 rejected=0 dates=1 partitions=4 seconds=2.129
```

```bash
java -Xmx4g -Dloader.main=com.ash.drishti.lakehost.DrishtiLakeCli -jar drishti-server-*-exec.jar --config load-trades.yaml
```

#### (c) Where the inbox is, and watching it load

| | |
|---|---|
| The inbox | `<data>/dataload/inbox/` on the inbox's **owner** node (`drishti.dataload.dir`, default `<data>/dataload`; `<data>` is `DRISHTI_DATA_DIR`). Turn it on: Admin → Engines → **Data inbox**, or `DRISHTI_DATALOAD_ENABLED=true` (section 3.1) |
| Which table | the `.ready` file's keys, else the first rule of `config/dataload.yaml`, else the name's table (section 5.1) |
| Which date | the `.ready` file's `business-date:`, else the name's date, else the rows' one date (section 5.2) |
| Which lake | the `.ready` file's `lake:`, else the rule's, else `drishti.dataload.lake` (`demo`) |
| Watch it | **Admin → Data loads → Inbox** (Waiting, Loading, Last cycles, Failures with **Retry**); `drishti.py dataload status`; `GET /api/v1/admin/dataload` |
| Load now | **Load now** on the Inbox screen; `drishti.py dataload run`; `POST /api/v1/admin/dataload/run` |
| Its report | `outbox/<day>/<run>/<file>.loaded.json` (tables, dates, Delta versions, rows); a refusal: `failed/<day>/<run>/<file>.failed.json` with the reason in words |
| Undo a load | `drishti.py dataload undo <load id>` (a dry run, then `--yes`), `POST /api/v1/admin/dataload/undo`; section 5.5 |

#### (d) At scale

- **One file per table and business date**, gzipped, up to a few GB each; or **one tar** per batch when several tables (the trades of
  several systems, the limits, the PVs) must land together (all or nothing). Many small files cost more than a few large ones: aim for 100 MB to 2 GB per file.
- **One `.ready` per data file**; or, when a whole batch is written, one `ready.all` in the inbox releases every file there at once.
- **Order:** a cycle takes every ready, stable file at that moment, oldest first, and loads the files of one table together; files that
  become ready during a run wait for the next cycle (`interval`, 10 minutes; **Load now** starts one at once). Send a table's dates in
  order when a later date depends on an earlier one.
- **Idempotent:** `mode: replace` (and the ledger, which skips a file whose SHA-256 was already loaded) make a re-run safe.
- **Throughput:** the loader runs as its own process (`loader-heap`, 4g) on every core; a cycle can be bounded with `max-files` and
  `max-gb`; keep the inbox and the lake on local or fast shared storage; exclude them from real-time antivirus scanning.
- **Several files for one date:** `mode: replace` removes every row of the date in the table, so a date made of several files must be loaded either **together in one tar** (one replace, all or nothing) or with
  **`mode: append`**, never one replace after another. The Upload page (section 3.5) warns, naming the systems and rows, and needs a tick.
- **Hold and release:** `hold: true` keeps a pair waiting (shown as *held*) until you remove the key, for example until a risk run is
  signed off.
- **Checks:** a wrong `sha256:` or `rows:`, an unknown key, a bad date or mode, a table that cannot be routed, or rejected rows over
  `reject-max-percent` (0) send the pair to `failed/` with the reason, and nothing is committed; **Retry** (or `drishti.py dataload
  retry`) puts it back after you fix it. Every row a load rejects is listed in the load's `*.rejects.jsonl` with its line and reason.

### 3.5 Upload a file and build its `.ready` (Admin → Data loads → Upload)

**Admin → Data loads → Upload** takes a data file, builds its `.ready` from a form, shows the exact text, checks both against the kind's contract and the
lake, and pushes them to the inbox: the same rules as a `.ready` written by hand (section 4), without writing one. The command line does the same through the same
endpoints. Administrators; a token needs the `loads:write` scope.

**1. The file.** Drop a file, or pick one already in `inbox/` with no marker (very large files: copy them to `inbox/` first, then pick). `.jsonl`, `.gz`, `.zip`,
`.tar`, `.tar.gz` and `.tgz` are taken, up to `drishti.dataload.upload.max-gb` (10). An upload goes to `<dataload>/staging/` on the owner node, streamed, never held in
memory. The SHA-256 and the row count are computed on the server over the whole file and written into the `.ready`. Dropping a file on the Inbox board opens it here, and **Build its .ready**
on a Waiting card with no marker opens that file here.

**2. The table.** The main field is **Table**: one searchable list of the real tables, each labelled `trade_endur · trade · trading` (table, kind, pack), filled from the file name when it can be
(`trade_endur.2026-11-06.jsonl.gz` picks `trade_endur`, and the page says so). The pack, kind and connector are shown beside it, read-only: they are what the table resolves to and what the
checks use. Only **Create a new table** (which needs `drishti.dataload.new-tables: true`) asks for a pack; a new table's kind is its own name. The `.ready` names `table:` and adds `pack:`, `kind:` and
`connector:` only when a routing rule or a table name in two packs would otherwise send the file elsewhere. **Lake** is its own field. The mode starts on **append**.

**3. A tar.** The table and business date are read from the archive's `<table>/<YYYY-MM-DD>/` folders by the loader's own reading of them, and shown read-only with each date and row count. Nothing overrides them, no `table:` or
`business-date:` is written, and a bad layout is refused before anything is written. `folders:` is written only where a folder needs its pack named.

**4. The checks.** A sample of the file's first rows (`upload.sample-rows`, 10,000; of each table of a tar) is checked, per table and date. Each result is a **pass**, a **warning** or a **refusal**, in words and with the fix:

| Check | Result |
|---|---|
| required fields of the kind's contract | refused, with the fields and how many rows |
| the key is present on every sampled row | refused when not; a repeat within the sample is a warning |
| a VaR input's records say what they are and their grain and id agree | refused (`DRS-5208`, `DRS-5209`), by the VaR intake's own rule |
| one source system, one form and one grain on the date (`DRS-5210`) | refused, by the same rule the VaR intake applies; **Use replace** is offered only when replace would really remove the clash |
| hierarchy level columns filled | warning with counts: such rows go under (Blank) |
| rows the table already holds for the date | see below |
| a file of that name already waiting | refused |

A refusal blocks Push; the checks run again on the server when you push.

**Replacing a date that already has rows.** `mode: replace` removes **every** row of that date in that table, whichever file or source system sent it: each trading system has its own table (`trading.trade_<system>`, section 16.2), so a system's file replaces that system's date only. (In a table several connectors share, which no shipped module uses, a connector's load replaces only its own `SOURCE_SYSTEM` slice.) A date can be several files, or several systems, so when the table already holds rows for the date the page says how many, per system,
and offers the two right ways: load the date's files **together in one tar** (one replace, all or nothing), or use **append**. **Push stays off until you tick** that this file really takes the place of those rows. On the command line this is `--yes-replace`.

**5. Push.** The data file is moved into `inbox/` first and its `.ready` last, each appearing whole or not at all, so the loader never sees a half-written pair; a file of the same name already waiting is refused and never overwritten. The push is audited: who, the file, its SHA-256, the `.ready` text. In a cluster
the request is answered by the node that runs the inbox; an upload reaching another node is passed on to it in signed parts.

```bash
export DRISHTI_TOKEN=...                                                # a token with loads:write; never on the command line
drishti.py dataload ready --file trade_endur.2026-11-06.jsonl.gz --table trade_endur --check   # prints the .ready and the checks
drishti.py dataload ready --file trade_endur.2026-11-06.jsonl.gz --table trade_endur --mode append --push
drishti.py dataload ready --inbox eod-2026-11-06.tar.gz --mode append --push                   # a file already in inbox/
```

```yaml
# trade_endur.2026-11-06.jsonl.gz.ready, as the page and the command line write it
lake: risklake
table: trade_endur
business-date: 2026-11-06
mode: append
key: TRADE_ID
sha256: 7d1f0c9e4ab2386ef15d70c2b94e6a1038dfc7725ea41b96c0d83f2a617be4d9
rows: 612480
```

```yaml
# config/engines/dataload.yaml
drishti:
  dataload:
    upload: { max-gb: 10, floor-gb: 5, sample-rows: 10000, keep-hours: 24 }
```

## 4. The `.ready` file, every key

The `.ready` file says that its data file is complete, and may say how to load it. It is YAML or JSON; **empty is fine**. The inbox reads
it, and so do `lake build` and the parallel loader for a file given on their command line (below their own options).

**Its name**: the data file's name in full plus `.ready` (`trade_endur.2026-11-06.jsonl.gz.ready`), or without the data extension
(`trade_endur.2026-11-06.ready`, `eod.tar.ready`). A short name that could release two data files (`trades.ready` beside `trades.jsonl`
and `trades.jsonl.gz`) releases neither, and the Inbox screen says why.

### 4.0 A complete `.ready` file, every key

Every key a `.ready` file can carry, each with what it does and its default. All are optional: a real file uses only those it needs (most
need none, or `lake:`, `mode:`, `sha256:` and `rows:`). The keys are one list shared by the inbox and the command-line loaders, owned by Drishti Lake
([LOADER.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/LOADER.md#the-inbox-hand-over-by-ready-files); Drishti adds `lake`; the lists are compared by a test); this example is parsed against it
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
key: TRADE_ID                # the id field. Default: the lake entry's id:, else the kind's key (the pack's ingest key, else the key: of
                             #   the kind's data contract), else a field named id. A table created by its first load takes it as its id:
new-table: false             # true: this file may create a table no pack has (with pack:, and drishti.dataload.new-tables: true). Default false
#
# --- A tar only ----------------------------------------------------------------------------------------------------------------------------------
folders: {trade_endur: trading, scenario-pv: market-risk, var_limit: market-risk}
                             # each top folder's pack (a tar holds <table or kind>/<YYYY-MM-DD>/ files). Default: the one pack with a
                             #   kind or table of that name (_ and - alike), else a pack of that name with one kind; an unresolved folder
                             #   refuses the whole tar. Section 4.4
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
| `key` | a field | the id field; default the lake entry's `id:`, else the kind's key from its pack (ingest key, else data contract), which a new table takes as its `id:` |
| `sha256`, `rows` | 64 hex characters; a whole number | checked by the inbox before anything is written; a mismatch sends the file to `failed/` |
| `new-table` | `true`/`false` | see section 5.6 |
| `folders` | `{<folder>: <pack>}` | a tar only; a folder is a table or a kind (section 4.4) |
| `hold` | `true`/`false` | the Inbox screen shows the file as *held* |

**An unknown key is refused** (the file goes to `failed/` naming the keys there are), so a typo never goes unnoticed; so is a bad date, a
mode that is not replace or append, or a checksum that is not 64 hexadecimal characters.

### 4.1 One plain JSONL file

```text
inbox/var_limit.2026-11-06.jsonl
inbox/var_limit.2026-11-06.ready          (empty)
```

The table is `var_limit` and the date 2026-11-06, both from the name. With a rule or a `.ready` saying `pack: market-risk`,
`kind: var-limit` and `lake: risklake` it goes to `risklake/risk/var-limit`.

### 4.2 A file whose name carries no date

```text
inbox/var_limits.jsonl
inbox/var_limits.ready
```

```yaml
lake: risklake
table: var-limit
business-date: 2026-11-06
mode: replace
sha256: 5e1e6523226a718f836f725d58650af2de53973add4a7733622e3fe5fb6df266
rows: 26
```

`lake build --plan` says where each value came from (*captured*): `var_limits.jsonl: table var-limit …, business date 2026-11-06
from the .ready file, mode overwrite-dates (var_limits.ready)`.

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

A tar (`.tar`, `.tgz`, `.tar.gz`, `.tar.zip`) holds `<folder>/<YYYY-MM-DD>/<files>.jsonl[.gz]`, nothing else (operating-system litter
such as `__MACOSX/` and `.DS_Store` is ignored). **Each top folder is a table or a kind**: a table name (`trade_endur`, `trade_calypso`,
`trade_murex`, the tables of the kind `trade`, one per source system) writes that table; a kind name (`scenario-pv` ([contract](../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-position-or-trade-one-pv-per-scenario-its-currency-scenario-pv), [example](../contracts/packs/market-risk.md#example-market-risk-scenario-pv)), `var-limit`)
writes the kind's one table. Names compare without case and with `_` and `-` alike (`var_limit` is the kind `var-limit`):

```text
eod-2026-11-06.tar.gz
├── trade_endur/2026-11-05/part-0001.jsonl.gz
├── trade_endur/2026-11-06/part-0001.jsonl.gz
├── trade_calypso/2026-11-06/part-0001.jsonl.gz
├── scenario-pv/2026-11-06/part-0001.jsonl.gz
├── scenario-pv/2026-11-06/part-0002.jsonl.gz
└── var_limit/2026-11-06/limits.jsonl
```

```yaml
# eod-2026-11-06.tar.gz.ready
lake: risklake
folders: {trade_endur: trading, trade_calypso: trading, scenario-pv: market-risk, var_limit: market-risk}
rows: 2050326
```

**A tar names its own tables and dates; nothing overrides them**: `business-date:` or `table:` in its `.ready` is refused. Each folder's
pack is `folders:`, else the one pack with a kind or table of that name, else a pack of that name with one kind; an unresolved folder
refuses the whole archive, listing the kinds and tables there are. A folder named after a kind with **several tables** (`trade`) is
refused naming them: name the table's folder (`trade_endur`). Each table's id is its lake entry's `id:`; a table the lake has no entry for
gets the pack's entry with the kind's key as `id:` (section 5.1), so a tar into a new lake needs nothing prepared. Every table of the archive shares
one load id, and **an archive loads completely or not at all**: if one table's load fails, the tables already committed are undone and the
archive goes to `failed/` saying what failed and what was rolled back. Make one from dated folders with
`tar -czf eod-2026-11-06.tar.gz trade_endur trade_calypso scenario-pv var_limit` run in the folder holding them.

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
    - match: "var_limit.*"
      pack: market-risk
      kind: var-limit
      lake: risklake
  unmatched: fail                             # the only choice: a file no rule matches goes to failed/
```

Every key of a rule: `match`, `pack`, `kind`, and optionally `connector`, `table`, `date-from` (`auto` or `name`), `date-field`, `key`,
`lake` (a lake's name; a folder is the older form), `format` (`delta` or `iceberg`), `date-pattern` and `date-format` (below).

**Which lake**: the `.ready` file's `lake:`, else the rule's `lake:`, else `drishti.dataload.lake` (`demo` as shipped). When the lake
file has no entry for the table (a kind's, or one of a kind's several tables: `trade_endur`), the pack's recommended entry is copied into it
first (said in the log), with the kind's key as its `id:` when the entry names none (the `.ready` file's `key:`, else the pack's ingest
key, else the single `key:` field of the kind's data contract); when the lake has no file at all, `config/lakes/<lake>.yaml` is created. From then on the lake file is the truth: edit it there.

A file of *lake rows* or *loader rows* (the row forms of [DATA_GUIDE.md section 2.2](DATA_GUIDE.md#22-the-row-forms)) is refused with the
tool that loads it (`tools/samplegen/load_rows.py`, or the store's own loader): the loader reads documents.

### 5.2 The business date of a file

**The business date comes from outside the data first.** It is the partition, so whoever sends the file decides it. First match wins:

1. the command line's `--as-of` (`source.as-of`);
2. the `.ready` file's `business-date:`;
3. the name's date: `<table>[.<date>][.part-<n>].<extension>`;
4. the rows' own date field (`BUSINESS_DATE`), when none of the above gives one: every row under the date it carries. In a replace the rows
   must carry one date: rows with none or with several refuse the file (*no business date …*, *its business date is ambiguous …*), nothing
   written, because a replace under a guessed date could take out a loaded date;
5. the business today (today in the server's zone, on a Saturday or Sunday the Friday before), only for a kind without a date field, or an
   append of rows without one.

A file named for a date whose rows all carry another date is refused in a replace (*its name says 2026-11-12, but none of its rows carries that
date*): a load never replaces a date its rows do not belong to.

A rule's `date-pattern` reads the date from a name that does not follow `<table>.<YYYY-MM-DD>.<ext>`: a regular expression with exactly one
capture group around the date, read as `YYYY-MM-DD` (or `YYYYMMDD` with `date-format: YYYYMMDD`). `swapone-2026-11-03.jsonl.gz` with
`date-pattern: '-(\d{4}-\d{2}-\d{2})\.'` is dated 2026-11-03 with no `.ready` file; a `.ready` file's `business-date:` still wins, and a
date that is not a calendar date is refused.

A rule's `date-from: name` (or `--date-from name`) refuses a name without a date. Every row of the file goes under the decided date; a
row whose own date field differs is loaded and counted (*3 rows of trade_endur.2026-11-06.jsonl.gz carried another date in
BUSINESS_DATE; loaded under 2026-11-06*), in the report, the ledger and on the Inbox screen. A tar's dates are its folders'.

**The name** is `<table>[.<date>].<extension>`:

| Name | Table | Date |
|---|---|---|
| `trade_endur.jsonl` | trade_endur | its rows' `BUSINESS_DATE` (one date; see above) |
| `trade_endur.2026-11-06.jsonl.gz` | trade_endur | 2026-11-06 |
| `scenario-pv.2026-11-06.part-0001.jsonl.gz` | scenario-pv | 2026-11-06 (one part of the day; the parts of a cycle are one load) |
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
the command line says `append`.** (`lake build` writes replace as `--mode overwrite-dates`.) A loaded file's report (`.loaded.json`, and the ledger's line) says the mode
the load ran: `route.mode` `replace` or `append` and `route.modeFrom` (*given (--mode or the .ready file)*, *the lake file's entry*, *the
inbox's default-mode*); an archive whose tables ran in different modes says `per table` (each table's `load.mode`).

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
would undo risk/var-limit 2026-11-06: 26 rows of load 20261107T080233.594-1791776.1 removed, 26 rows of the date before it restored
```

Without `--dry-run` it asks for the load id again (`--yes` for scripts). The old versions an undo restores must still be there:
`time-travel:` (section 2.6) keeps them.

### 5.6 New tables

A table no pack has is refused, listing the known tables, unless the `.ready` file says `new-table: true` with its `pack:` and the inbox
allows it (`new-tables: true`, off as shipped). The table is then written in the pack's lake and domain with the generic layout (`id`,
`doc`, `business_date`, `_load_id`, `_source_file`), its id field `key:` else `id`. The report says *new table X created; no pack serves it
yet*: a pack declares it as a kind when it should appear on screen.

### 5.7 Child kinds

A **child kind** is an array of records in a parent's documents (`LEGS` of `trade`) loaded as a kind of its own (`trade-leg`): one row per
element, in a table of its own beside the parent's (`<lake>/<domain>/trade-leg/`, or `<files root>/<domain>/<date>/trade-leg.jsonl`), under
the **parent's business date**, written in the same run as the parent. Its row is the element's fields with two in front: its id (`LEG_KEY`,
the parent's id `:` the element's natural key such as `LEG_ID`, else its index) and the link to the parent (`TRADE_ID`); its promoted columns
are the link and the element's fields. The parent's row is unchanged (it keeps the array). The pack declares the child kind in its `ingest:`
block, which the loader reads:

```yaml
ingest:
  trade: { key: TRADE_ID, date: BUSINESS_DATE }
  trade-leg: { key: LEG_KEY, child-of: { kind: trade, field: LEGS, link: TRADE_ID, natural: LEG_ID } }
```

Child tables work in **every store**, by every loader, from the same declaration:

| Loader | Delta | Iceberg | Files |
|---|---|---|---|
| `drishti.py data ingest --pack <pack> --from <files>` (also `pack new --load` and the Pack designer) | `--lake DIR` | `--store iceberg --lake DIR` | `--store files --root DIR` |
| `drishti.py lake build --pack <pack> --kind trade` (`--engine` any) | default | `--format iceberg` | |
| the parallel loader (section 3.3), with `source.pack` and `target.kind` in its configuration | `target.format: delta` | `target.format: iceberg` | |

The parent's table is loaded as ever; then each child kind of the pack's `ingest:` block is split off the same documents and loaded as a table of
its own, in the parent's format, with the pack's layout of the child kind (its promoted columns, sort and table name), **under the business
date the parent's file was loaded under** (`--as-of`, the `.ready` file's, the tar folder's, the file name's, else the business today). The
rules live in the pack's `ingest:` block alone and every loader reads them: the id is `parent id : LEG_ID` when every element has a unique
natural key (the `natural:` field), else `parent id : index`; the link field carries the parent's id; an element that is not a record, a
parent without an id and a parent without the array give no child row. `tools/packgen/childkinds.py` (Python) and
`com.ash.drishti.lakeload.ChildKinds` (the parallel loader) are checked against one fixture of documents and the rows they must give, so
they cannot drift; `tools/test_child_parity.py` loads the same nested JSON Lines as Delta, Iceberg and files and checks the child rows (count,
ids, links, promoted columns, date) and the answers through the BI relationship are the same. A child kind can still be delivered as files of
its own (`trade-leg.2026-11-03.jsonl`) where the parent has no array to split. `pack new` and the Pack designer plan child kinds and write this
block ([BUILD_GUIDE section 8.1.4](BUILD_GUIDE.md#814-child-tables-an-array-of-records-as-a-kind-of-its-own)).

### 5.8 Delete business dates

`drishti.py lake delete` removes whole business dates from **one table** of a lake: a date loaded by mistake, a date to be sent again from
scratch, or test data to clear. Name the table by its kind (`--kind scenario-pv`: the pack's table of that kind, where `lake build` writes
it) or by its path (`--table risk/scenario-pv`), and the dates by `--date` (repeatable, or comma separated) or a range `--from`/`--to`
(both ends included):

```bash
python3 tools/drishti.py lake delete --lake risklake --kind scenario-pv --date 2026-11-16 --dry-run
python3 tools/drishti.py lake delete --lake risklake --kind scenario-pv --date 2026-11-16 --server http://localhost:18480
```

```text
would delete risk/scenario-pv 2026-11-16: 15,000 rows
risk/scenario-pv would keep 59 business dates (newest 2026-11-13)
```

- **One writer, every store.** The lake's own writer removes the dates, the same machinery as row retention and undo (the lake loader's
  `LakeRetention --dates`): a Delta table gets one commit removing every file of the dates, an Iceberg table one partition delete, a files
  store loses the dates' day files (`<root>/<domain>/<date>/<kind>.jsonl`). A reader sees the whole date or none of it. The same rows go in
  all three stores (a parity test, and delta-rs reads the Delta result).
- **Checked first.** It first says what it would delete, rows per date, then asks for the table's name to confirm (`--yes` for scripts;
  without a terminal it is refused unless given). A date the table does not hold is refused in words, nothing deleted: *2026-11-14 is not
  in risk/scenario-pv: it holds 60 business dates (2026-08-19 .. 2026-11-16); nothing deleted*. `--dry-run` stops after the counts.
- **Locked and logged** like a build: it holds the table's lock (exit 3 when another run holds it) and writes a `lake-delete` line to
  `<lake>/_drishti-lake/runs.log` and the run's summary to `runs/<run>.json` (`lake status` lists it).
- **The server is told.** With `--server URL` (or `DRISHTI_SERVER`; `--token-file` or `DRISHTI_TOKEN` for the token, `loads:write`) it
  sends the data-load signal with status `deleted`, one per date (`POST /api/v1/packs/{pack}/loads`): the server drops the kind's
  connector caches and every engine drops the date at once (the VaR engine: [VAR_GUIDE.md section 15.8](VAR_GUIDE.md#158-cadence-delivery-and-the-load-signal));
  nothing is verified, alerted or captured, and the load history records it as *scenario-pv for 2026-11-16 deleted from the lake: 15,000
  rows*. `--no-signal` tells no server; a running server then notices within its version check (`drishti.var.version-check`, 30 s).
  `--pack NAME` names the pack when no pack has the kind.

**Bringing a date back.** Load it again (`lake build`, the data inbox or a feed): the load's signal makes it today's again. `lake undo`
cannot bring back a deleted date: it removes and restores the rows of one load by its id, and the deleted rows are no longer in the table.
Until the table's vacuum (Delta, `time-travel:` of section 2.6) or snapshot expiry (Iceberg), the removed files are still on disk and the
table can be read as it was before the delete (time travel), but the way back is a load.

Exit codes: 0 deleted (or a dry run), 1 refused or failed (nothing changed), 2 deleted but the signal failed, 3 another run holds the lock.

## 6. Pointing Drishti at a lake

A lake is read by the Delta and Iceberg connectors that name it. A connector is its **template** (the `connectors:` section of its
pack's `pack.yaml`, or a source module's `connectors/` file) with **your override** merged over it (`config/connectors/<name>.yaml`, only
the keys you change) and the secrets resolved when it starts (KERNEL_AND_MODULES.md section 18.5). The template travels with its module:
a module installed from its `.dmod` (the distribution installs banking, market data, capital markets and economy at the first start)
carries its packs' templates, and the server reads them from the installed module at every start. **The server writes no connector
file**: with no override the template is the connector. A connector says `lake:` and, where a kind has several tables, `lake-tables:`;
root, folders, partitions and promoted columns come from the lake file. To read your scenario PVs and VaR nodes from `risklake`, write
`config/connectors/risk-store.yaml` with only what differs from the template (Admin → Connectors writes the same):

```yaml
template: pack market-risk
settings:
  lake: risklake
  lake-tables: risk.scenario-pv,risk.var-limit
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

- **The packs' samples step aside.** Every pack ships sample documents (`modules/<module>/packs/<pack>/samples/`), served by the `demo` connector,
  which answers any kind and, on a live read, before the lakes. Once a kind's routed connector, or any Delta or Iceberg connector serving
  it, names a lake other than `demo`, the samples no longer answer for that kind: not by id (`TRD 1000001`, a demo ComSwap, is not
  found once `trade-endur` reads risklake), not in a search. A kind whose connectors all read `demo` keeps its samples, so a demo server
  is unchanged. The server's published results lakes (`var-official`, `ccr-official`) do not count: they hold no inputs.
- **A whole pack, one lake.** Point every Delta and Iceberg connector of a pack at the lake, not one of them: the pack's screens read
  all its connectors (`trade-endur`, `trade-calypso` and `trade-murex` are one trade store; VaR reads scenario PVs, P&L vectors,
  limits from `risk-store` and the market data from `market-store`). A connector file replaces the pack's
  template whole: keep its other keys (`union: true` on the trade connectors) when you change `lake:`.
- **The warning.** A pack whose connectors read more than one lake is *DEGRADED* in Admin → Health and `GET /api/v1/health`
  (`packs[].lakes`, each lake with its connectors, and `packs[].lakeWarning`): *trading's connectors read different lakes: demo
  (trade-calypso, trade-murex); risklake (trade-endur). Its screens mix them: point every connector of trading at one lake (lake: in each
  connector file) and restart*.

Nothing is cached across the switch: a read goes to the connectors as they stand after the restart. Keep the demo on another server (or
another data folder) to compare.

**Going back to the demo**: set the connector files back to `lake: demo` and their pack's `lake-tables:` (or delete the file: the
pack's template is written again at the next start), and restart. Nothing in either lake changes.

### 6.1 Data bindings

Which connectors serve a kind is the site's **binding**, one per kind in `config/bindings.yaml`, shared by every module that needs the kind
(changed from Admin → Data bindings with a second administrator's approval, and applied without a restart:
[ADMIN_GUIDE.md section 23](ADMIN_GUIDE.md#23-data-bindings)). A connector file says *how to reach* a store (`plugin`, `lake:`, settings,
`${secret:…}` references); the binding says *which kinds it serves and in what order*:

```yaml
format: 1
bindings:
  trade:
    read: [trade-endur, trade-calypso, trade-murex]   # connectors (config/connectors/<name>.yaml), in read order
    union: true                                        # one trade store across them
    live: [trade-endur-kafka, trade-calypso-kafka]     # the connectors that push changes (one name, or a list)
  scenario-pv:   { read: [risk-store] }
  var-limit:     { read: [risk-store] }
  var-official:  { write: { lake: risklake, table: banking.var-official } }
  pnl-vector:    { demo: true }                        # explicitly the demo lake's samples
```

| Key | Meaning |
|---|---|
| `read` | the connectors that serve the kind, in read order (the first that holds the date answers; a union is read as one) |
| `union` | the read connectors are one store together (one source system each, ids unique across them); two or more |
| `live` | the connectors that push changes of the kind: one name, or a list when several source systems stream it (merged into the live result set by key and version) |
| `write` | where a produced kind is written: `{lake, table}` |
| `demo` | `true`: the demo lake's samples serve it (only while demo is on); never with `read` |
| `note` | the administrator's note |

A binding never holds a host, a URL, a placeholder or a credential (refused); a computed kind (`position`, `system-basket`, `var-result`)
is never bound. A kind nobody bound still finds its data, in this order: the distribution's default (a pack's `routes:`), a lake whose file
holds the contract's `binding-hint.lake-table` (`AUTO`, *found by convention: confirm*), the demo lake (`DEMO`), else `UNBOUND`: an unbound
**required** need switches off exactly the functions that use it, with the reason. A contract names no connector: its `binding-hint`
gives the lake table, partitions and promoted columns a site usually binds ([contracts README](../contracts/README.md)).

A connector file names no kinds (a `kinds:` line from an earlier release is ignored): `bindings.yaml` is the one home of which connectors serve a kind.

### 6.2 Several source systems: shared or separate tables

Trading systems (Endur, Calypso, Murex, WSS, SwapOne, ICI) are **source modules**, each feeding the **one canonical `trade` kind**; the field
`SOURCE_SYSTEM` says which system a record came from. Each system's trades are in **its own table** (one table a trading system, always), and the site tunes
the read in the connector file; the same keys work for `delta`, `iceberg` and `files` connectors, and for `kafka` live connectors (each message
filtered and mapped):

```yaml
plugin: delta            # also iceberg, files; kafka for live
settings:
  lake: main
  lake-tables: trading.trade_endur    # the system's own table
  where: SOURCE_SYSTEM = 'ENDUR'       # optional: the rows that are this connector's (a table that holds other rows too)
  set: { SOURCE_SYSTEM: ENDUR }        # optional: constants for rows that lack the field
  map: mappings/endur-trade.yaml       # optional: native field -> canonical field
```

| Key | Meaning |
|---|---|
| `where` | A row predicate on the **stored** row: `=`, `<>`, `<`, `<=`, `>`, `>=`, `in ('A', 'B')`, `is null`, `is not null`, `and`, `or`, `not`, parentheses; text is quoted. The top-level comparisons of a promoted field with text (`=`, `in`) are pushed down to the store: a Delta or Iceberg connector does not open the date's files whose partition value or column statistics show none of the values and leaves out the other rows of the files it reads; a database (PostgreSQL, DuckDB) gets them in its query's `WHERE`. Other comparisons are not pushed down. The whole predicate is still tested on every row read, so the answer is the same. Health shows the files read and left out (`wherePushdown`). |
| `set` | Constants for the canonical row where it lacks the field (`SOURCE_SYSTEM` for a system's own table). |
| `map` | A map file, relative to the connector's module folder, else the connectors' folder (`config/connectors/`). |

The order is: `where` on the stored row, then `map`, then `set`. **Raw JSON** shows the stored record (the source's native fields, before
`map`); the grid, search, FORM and BI show the canonical fields. The connector's system is its `set.SOURCE_SYSTEM`, else the value its
`where` fixes (`SOURCE_SYSTEM = 'ENDUR'`).

**The map file** has two forms of entry, which may be mixed:

```yaml
format: 1
fields:
  TRAN_NUM: TRADE_ID                              # native: canonical
  NOTIONAL_AMT: { to: NOTIONAL, cast: number }    # native: {to, cast}
  DESK: { from: PORTFOLIO, values: { RNY: RATES-NY } }   # canonical: {from: native, cast, values: native value -> canonical value}
  ASSET_CLASS: { value: RATES }                   # canonical: {value: constant}, always that value
set: { SOURCE_SYSTEM: ENDUR }                     # constants where the row lacks them (the connector's set wins)
keep: true                                        # native fields the map does not name pass as they are (false: dropped)
```

Casts are `text`, `number`, `integer`, `boolean` and `date` (ISO, `yyyyMMdd` or `13-Nov-2026`); a value a cast cannot read stays as stored.

**One table a system.** One connector per table with `set: { SOURCE_SYSTEM: X }` (and a `map` when the
system's fields are its own). Several live connectors go in the binding's `live:` list; a message another system's connector would not serve
is dropped by the `where` of each.

**Stamping at load.** When the inbox, `drishti.py data ingest` or the Java loader writes a file through a connector (a rule's `connector:`),
the connector's `set:` constants are stored into every row that lacks the field, in Delta, Iceberg and files alike, so a shared table splits by
`where` afterwards. The site's own connector file (`config/connectors/<name>.yaml`) wins over the module's template. `map:` is applied at
read only: the stored row stays as the system sent it (Raw JSON shows it).

**Inbox rules of modules.** A source module's `dataload:` (a file of the same format as `config/dataload.yaml`) adds rules for the files of its
system. The inbox reads the site's rules first, then those of the modules that are on, at every cycle: a module switched off takes its rules
away. Admin → Data loads → Routing rules shows each rule's origin (`site` or the module).

**Refusals.** A `where` that is not a predicate, a map file that is missing, unreadable, maps two fields to one, or names an unknown cast,
refuses the connector at load with `DRS-5034`, shown on Admin → Connectors with the reason; the rest of the server runs. A connector's `where`
may not name `$user`: person attributes belong in a role's row rule ([KERNEL_AND_MODULES.md section 19.10](../architecture/KERNEL_AND_MODULES.md#1910-row-rules)).

**A source module switched off** (Admin → Modules, at runtime, no restart): its connectors leave every binding without an error (Admin says
*source ENDUR off*), its rows leave lists, blotters, search and BI (also from a shared table read by a connector without `where`), its live
feed stops, and the pages of the kinds it feeds say *excludes ENDUR (switched off)*. Switched on, all comes back. With every source off the
kind is empty, without errors. A module is a source module when it owns the connector (its manifest's `connectors`) or is named
`source-<system>`. The allowed values of `SOURCE_SYSTEM` are the systems of the installed source modules, never a fixed list. VaR is driven by
scenario PVs at position grain and is unchanged by a source switch.

**Write-back.** A blotter edit of a record goes to the write path of the system the record came from (its `SOURCE_SYSTEM`): the form's
connector when it is that system's, else the first of the system's connectors that carries the command; never another system's. A system
without a write path for the command refuses the change, with the reason.

## 7. The demo lake's folder

The demo lake is `<data>/delta/demo/`. A demo lake whose tables sit directly in `<data>/delta/<domain>/` is not read: delete those folders and build it again (`drishti.py lake build`); `lake build` says so when it finds them.

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
| *already loaded in run …* | the same SHA-256 was loaded before and the lake still holds its rows | nothing to do; `reload-duplicates: true` to force |
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
own readers inside the server (Delta Kernel and lake-iceberg): **no Databricks cluster, Spark or warehouse is needed to read**. The
tables need the shape of section 1 (`id`, `doc`, `business_date`, promoted columns); a Spark job that writes it is in
[DELTA_CONNECTOR.md section 5](../../plugins/drishti-plugin-delta/README.md#writing-the-lake). Each day sorted by `id` (on Databricks,
`OPTIMIZE … ZORDER BY (id)` or liquid clustering on `id`) reads fastest. Drishti's lakes follow the Delta protocol's path encoding and are
tested against delta-rs both ways; the table features each reader supports are in [section 14](#14-delta-protocol-compliance).

### 10.1 Built

**Delta by storage path.** The lake file's `root` is the folder above the domains: `s3://bucket/prefix` or a local folder. The reader
is Drishti Lake's (local disk and S3-compatible storage; `abfs://`, `gs://` and `hdfs://` are not read). Credentials: S3 from the AWS default
chain (a task or instance role) or `${ENV}` references; a private CA through `tls.*`. With Unity Catalog, point the root at an external
table's location and give the server read access to that storage.

```yaml
# config/lakes/bank-lakehouse.yaml
lake: bank-lakehouse
root: s3://bank-lakehouse/delta
storage: {region: eu-west-2}                 # no keys: the task role
tables:
  trading.trade_endur: {kind: trade, promote: [TRADE_ID, PRODUCT_TYPE, DESK, BOOK, COUNTERPARTY_ID], id: TRADE_ID}
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

> **Under construction.** On the roadmap; none of this works today.

| Planned | What it will do | Today |
|---|---|---|
| **Unity Catalog for Delta** | a table found by its name, `catalog.schema.table`; Unity Catalog's credential vending; Unity Catalog's grants as the permissions | read Delta by storage path (external tables), or the same table as Iceberg through Unity's REST endpoint (UniForm) |
| **Delta Sharing** | read tables a provider shares through the Delta Sharing protocol | not supported |
| **Plain tables without the `id`/`doc` shape** | read a table of ordinary columns directly, driven by per-table configuration | a small job writes a Drishti-shaped copy beside it ([DELTA_CONNECTOR.md section 5](../../plugins/drishti-plugin-delta/README.md#writing-the-lake)) |

## 11. Archives: zip and tar

Every loader reads JSON Lines inside an archive (`.zip`, `.tar.gz`, `.tgz`, `.tar`), in place, from a local folder or `s3://`. A **zip** is
one table and one date, its JSON Lines files loose; a **tar** holds `<table>/<YYYY-MM-DD>/` folders (section 4.4). There is one archive
rule for the Java and the Python loader, owned by Drishti Lake (`drishti_lake.archives` in Python): which members are read, the guards
(absolute or `..` names, links and devices, encrypted members, nested archives, member count, size and ratio caps) and the settings that
set the caps (`lake.archive.max-mb`, `max-ratio`, `max-members`) are in Lake's [LOADER.md, Archives](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/LOADER.md#rules-that-apply-to-every-load)
and [STORAGE.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/STORAGE.md#options-of-the-runtime). A load whose archive breaks a rule is refused whole, naming every problem, before a row is written.

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
`demo-iceberg.yaml` and connectors naming it ([tutorial 16, Part E](../tutorials/23-the-risk-lake-on-iceberg.md)).

**The local cluster** (`deploy/stack`): the stack's synthetic lakes are written through the demo lake's file with another root
(`lake build --lake demo --lake-root data/stack/lakes/<name>`); every node reads the same description.

**Admin → Lakes** lists every lake (format, root, in memory or not) and each table (kind, path, partitions, promoted columns, vectors, id,
load mode), read-only; a lake file the reader refuses is shown as the refusal. The API: `GET /api/v1/admin/lakes/files`
(`?check=true` adds the comparison with the tables on disk).

**Connectors by path to a lake file.** A connector that names its tables by path keeps working with no warning; write a lake file for such tables by hand (this section) and run `python3 tools/drishti.py lake check` to compare it with the tables on disk.

## 13. The in-memory lake

Some feeds may not, by contract, be saved on a disk. Drishti holds them in an **in-memory lake**: ordinary Delta tables (a `_delta_log`
and Parquet files) whose files live in the server's memory, off the Java heap, under a hard cap. A server job, the **replenish job**,
fills each table from its feed; every page, search, BI dataset and Export data read it exactly as they read a lake on a disk; a guard
refuses every way the data could reach a disk. Only a user's download leaves the server. How it is built:
[DATA_ARCHITECTURE.md section 9](../architecture/DATA_ARCHITECTURE.md#9-the-in-memory-lake-delta-tables-that-never-touch-a-disk); a
worked example: [tutorial 16, Part D](../tutorials/18-news-held-in-memory.md).

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
  `tools/drishti.py start server` computes it from the configuration (`tools/lake/memory_caps.py`); the Docker image and the ECS
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
| `tables[].retention` | none | the rows' age limit and cap, applied by the compaction (the one upkeep): `{ttl: 8h, on: UPDATED_AT, max-rows: 100000}`; the rule, and the same option for Delta, Iceberg and files, is section 2.7. Admin → Lakes shows each table's retention and the rows it dropped. A table that also has a durable copy keeps that copy's own retention (none by default): the TTL trims the memory only |
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
file, field or naming rule is involved. Drishti writes, reads and maintains Delta only through **Drishti Lake**, which owns the protocol
code (Delta Kernel), the partition-path rules, the table features, the maintenance in protocol terms and the interoperability tests
both ways; they are described in its [STORAGE.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/STORAGE.md) and its design,
[DRISHTI_LAKE_DESIGN.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/design/DRISHTI_LAKE_DESIGN.md). Drishti's side
(the lake files, the catalog, the one runtime of the process) is in
[DATA_ARCHITECTURE.md sections 8–10](../architecture/DATA_ARCHITECTURE.md#8-the-lake-file-one-description-of-every-lake).

## 15. Iceberg spec compliance

Drishti's Iceberg tables are standard Apache Iceberg tables, read and written to the [Iceberg table spec](https://iceberg.apache.org/spec/)
with no workaround: Spark, Trino, Flink, Snowflake, AWS Glue, pyiceberg and the Apache Iceberg Java library read what Drishti writes, and
Drishti reads what they write, or refuses a table by name; it never reads one wrongly. Drishti Lake owns the Iceberg code (on
`iceberg-core`): the loader, the read API, maintenance and the compliance tests; see its [STORAGE.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/STORAGE.md) and
[READ.md](https://github.com/drishti-platform/drishti-lake/blob/main/docs/guides/READ.md). The connectors are [ICEBERG_CONNECTOR.md](../../plugins/drishti-plugin-iceberg/README.md) and
[ICEBERG_EXTERNAL_CONNECTOR.md](../../plugins/drishti-plugin-iceberg-external/README.md).

## 16. Runbooks: production data into the lake

The kinds these runbooks (sections 16 and 17) load, each with its sample record and its data contract: `agreement` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-agreement), [contract](../contracts/packs/counterparty-risk.md#agr-master-agreement-agreement)), `clearing-account` ([sample](../contracts/packs/core.md#example-core-clearing-account), [contract](../contracts/packs/core.md#clr-clearing-account-clearing-account)), `collateral-balance` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-collateral-balance), [contract](../contracts/packs/counterparty-risk.md#coll-collateral-balance-collateral-balance)), `counterparty-group` ([sample](../contracts/packs/core.md#example-core-counterparty-group), [contract](../contracts/packs/core.md#grp-counterparty-group-counterparty-group)), `credit-limit` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-credit-limit), [contract](../contracts/packs/counterparty-risk.md#lim-credit-limit-credit-limit)), `csa` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-csa), [contract](../contracts/packs/counterparty-risk.md#csa-credit-support-annex-csa)), `desk-pnl` ([sample](../contracts/packs/market-risk.md#example-market-risk-desk-pnl), [contract](../contracts/packs/market-risk.md#dpnl-desk-pl-actual-and-hypothetical-daily-desk-pnl)), `exposure-path` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-exposure-path), [contract](../contracts/packs/counterparty-risk.md#xpath-exposure-path-a-trades-mtm-on-every-path-at-a-grid-date-exposure-path)), `fx-scenario` ([sample](../contracts/packs/market-risk.md#example-market-risk-fx-scenario), [contract](../contracts/packs/market-risk.md#fxs-scenario-fx-of-a-currency-one-rate-per-scenario-fx-scenario)), `fx-spot` ([sample](../contracts/packs/market-data.md#example-market-data-fx-spot), [contract](../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)), `legal-entity` ([sample](../contracts/packs/core.md#example-core-legal-entity), [contract](../contracts/packs/core.md#le-bank-legal-entity-legal-entity)), `netting-set` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-netting-set), [contract](../contracts/packs/counterparty-risk.md#nset-netting-set-netting-set)), `pnl-history` ([sample](../contracts/packs/market-risk.md#example-market-risk-pnl-history), [contract](../contracts/packs/market-risk.md#pnlh-long-history-pl-of-a-hierarchy-node-stress-window-selection-pnl-history)), `sa-ccr` ([sample](../contracts/packs/counterparty-risk.md#example-counterparty-risk-sa-ccr), [contract](../contracts/packs/counterparty-risk.md#saccr-sa-ccr-exposure-sa-ccr)), `scenario-set` ([sample](../contracts/packs/market-risk.md#example-market-risk-scenario-set), [contract](../contracts/packs/market-risk.md#sset-scenario-set-scenario-set)).

One runbook per kind of production feed, each tested end to end on a synthetic batch shaped like production. Each follows one shape:
prerequisites, the fields, the steps, the files and their names, chunking, two dates, the layout, verification, troubleshooting, a do-not list and a
checklist to print.

### 16.1 Production position-level scenario PVs, for VaR, Explain and Why it moved

**What this loads.** A pricing system's morning batch: one `scenario-pv` record per **position** (`GRAIN: POSITION`, `POSITION_ID` set, no
`TRADE_ID`), about 1.3 million positions a business date, `UPPER_SNAKE` field names, JSON Lines (many large files, plain or `.gz`). **The scenario PVs are always one table** (`risk.scenario-pv`) for every
pricing system, loaded as **one complete file or tar per business date**: a day's PV file must contain **all** pricing systems' positions,
and a partial file **replaces the whole date** (there is no per-system slice of scenario PVs; 17.4). With it the
VaR page, Explain and **Why it moved** are fast: a date opens from one memory-mapped hot file. The kind is `scenario-pv` (command `SPV`); its contract
is [`scenario-pv.contract.yaml`](../../modules/banking/packs/market-risk/contracts/scenario-pv.contract.yaml), published as
[SPV](../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-position-or-trade-one-pv-per-scenario-its-currency-scenario-pv). The engine's rules for
forms and grains are [VAR_GUIDE.md section 15.13](VAR_GUIDE.md#1513-inputs-any-form-any-grain); this section is the procedure.

**Every input a position-grain date needs.**

| Kind | Needed? | Why |
|---|---|---|
| `scenario-pv` | **yes**, the batch itself | the values |
| `scenario-set` | **yes**, one row a date | the scenario days: `SCENARIO_COUNT` must equal every vector's length; the engine uses the newest `scenario-window` of them |
| `fx-scenario` | **yes** when any `CURRENCY` is not USD (one row per currency and date, `RATES` as long as the set) | otherwise the rates are derived from `fx-spot` history, which covers every scenario day only on a bank's own FX history; a currency with neither rejects its rows (`DRS-5207`) |
| a position or hierarchy kind | **no** | a position record places itself: `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER` build both trees; nothing is derived from trades |
| `var-limit` | optional | the Limit column and limit use |
| `desk-pnl`, `pnl-history` | optional | backtesting, stressed VaR |
| trades (`trade_*`) | optional | trade pages only; position grain never joins them |

#### Prerequisites

| Check | Value | Where |
|---|---|---|
| Java | 21 or newer | `java -version` |
| Server heap | **8 to 10 GB** for 1.3M positions and two to three open dates | `python tools/drishti.py start server --heap 10g` (Windows: `py -3.13 tools\drishti.py start server --heap 10g`). The measured book is about 0.77 GB of heap at 1M positions ([VAR_GUIDE.md 27.1](VAR_GUIDE.md#271-bytes-per-date)), so about 1 GB at 1.3M, and a date kept open about 1.5 GB of `-Xmx`; `-Xmx6g` served 1M positions with two dates |
| Loader heap | `16g` | `DRISHTI_DATALOAD_LOADER_HEAP=16g` for the inbox (default `4g`; the loader's budget is half of it): a separate process, memory bounded, the rest spilled to disk; for the CLI, `DRISHTI_LAKE_LOADER_HEAP` |
| Reporting currencies | `DRISHTI_VAR_CURRENCIES=USD` **for the first load** | the default builds EUR, CAD, JPY and GBP books ahead, each about 77% of the USD book's disk; add them when you need them |
| Open dates | `DRISHTI_VAR_HOT_DATES=3` | the default 10 keeps up to ten dates open |
| Disk | see the table below; the guard `drishti.disk.min-free-percent` (10) and `min-free-gb` (20), both must hold, else a date is refused `503 DRS-5218` | `df -h` of the data folder |
| Scenario window | `drishti.module.banking.var.window` (default 250: the newest N of the set; `all` uses the whole set) | `config/engines/var.yaml` |
| Inbox | on, on one owner node | `drishti.py server engines on dataload` (the demo starts it on); in a cluster, the owner node's `<data>/dataload` |
| A token | scope `loads:write` (an administrator's will do) | My account, API tokens; `--token-file F` or `DRISHTI_TOKEN`; `DRISHTI_SERVER=http://host:18480` |

Put the settings in the server's env file and start it:

```bash
cat >> server.env <<'EOF'
DRISHTI_VAR_CURRENCIES=USD
DRISHTI_VAR_HOT_DATES=3
DRISHTI_DATALOAD_LOADER_HEAP=16g
EOF
python tools/drishti.py start server --env-file server.env --heap 10g --background      # Windows: py -3.13 tools\drishti.py ...
```

**Disk**, 1.3M positions, 250 values a vector, two dates, **estimated** from the measured 20,000-position run (below; 509 values roughly doubles the lake and
the hot files):

| Folder | One date | Two dates |
|---|---|---|
| your landing area | 1.3 GB gzip (4.3 GB as text) | 2.6 GB |
| `<data>/dataload/`: inbox, then `outbox/` (kept `keep-outbox-days`, 30) | 1.3 GB, once in each | 2.6 GB at once, 5.2 GB kept |
| the lake, `<lake>/risk/scenario-pv/` | 1.75 GB | 3.5 GB; **a replaced date keeps its old files** until vacuum (`time-travel: 30d`, `vacuum: 7d` in the lake file): budget a second copy of any date you load twice |
| VaR hot files, `<data>/var/hot/` | 2.7 GB (USD) | 5.4 GB; with the five default currencies about 11 GB a date |
| temp (a load's spill, `<data>/tmp`) | up to 10 GB | |
| **Plan on** | | **about 30 GB free** for two dates with USD only, 60 GB with the default currencies |

#### Fields: what each record must carry

The contract lists 41 fields, 28 required. The load rejects a row for the few the engine reads; the others are kept for audit and navigation. This
table is what happened when each was dropped from a test date:

| Field | Send it | If missing (tested) |
|---|---|---|
| `VALUE_TYPE` = `PV`, `GRAIN` = `POSITION`, `POSITION_ID`, `CURRENCY`, `SOURCE_SYSTEM` | always; `POSITION_ID` unique on the date | the row is rejected `DRS-5208` (*no GRAIN: every PV record says what it is …*) |
| `BUSINESS_DATE`, `SCENARIO_SET_ID` | always; the set's id the same on every row and in `scenario-set` | `DRS-5203` for the date |
| `SCENARIO_PV` | always: as many values as the set's `SCENARIO_COUNT`, oldest scenario first, none `null` or NaN | `DRS-5201` wrong length; `DRS-5206` a null: the row is not loaded, never zero-filled |
| `BASE_PV` | always (the same run's PV today; P&L = PV(s) × FX(s) − BASE_PV × FX(base)) | **every row rejected**, `DRS-5201 … no BASE_PV` |
| `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER` | always: the trees are built from them | the row sits under **(Blank)** and the answer warns `DRS-5211`: loaded, but not where you want it |
| `BATCH_ID` (one a date), `COMPUTED_AT`, `RUN_KIND` = `FULL`, `RESTATEMENT` (0, then 1, 2 …) | always | two `BATCH_ID`s on a date refuse the date `DRS-5203`; with none the date loads |
| `SCENARIO_PV_ID` (`SPV-<POSITION_ID>-<SCENARIO_SET_ID>`) | always: the contract's key, what `SPV` pages are named by | loaded without it, but the record page and key-based lookups rely on it |
| `VALUATION_DATE`, `BASE_VALUATION_DATE` | the business date | loaded when absent; `DRS-5201` when sent and different |
| `TRADE_STATUS`, `MATURITY_DATE`, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `SECURITY_ID`, `COUNTERPARTY`, `DIRECTION`, `QUANTITY`, `INSTRUMENT`, `PORTFOLIO`, `TAGS`, `STRATEGY`, `TRADE_CURRENCY` | as you have them: they feed scopes (`CPTY:`, `WHERE:`), baskets and the pages | loaded when all are absent |
| `TRADE_IDS` | optional: the position's trades, kept for navigation | loaded |
| `STATUS`, `PRICER`, `MODEL_VERSION` | audit | loaded when absent |
| `TRADE_ID` | **never** at position grain | a `TRADE`-grain row beside positions of one system refuses the date, `DRS-5210` |

A position under two nodes of a hierarchy is one record per node, its id `<position>@<level value>`
([VAR_GUIDE.md 15.13](VAR_GUIDE.md#1513-inputs-any-form-any-grain)). The pin `drishti.var.inputs` (`<SYSTEM>: {form: pv, grain: position}`) is
optional; a record that disagrees with it is refused `DRS-5209`.

**The layout the lake file gives it** (`config/lakes/demo.yaml`, `risk.scenario-pv`; the lake file is the truth, never a command line): partitioned by
business date, then `SCENARIO_SET_ID` and `GRAIN` (one folder a date at position grain), files of about 256 MB (`file-size-mb`), row groups of 16 MB
(`row-group-mb`), `zstd` for columns and vectors, `sort-by: [LEGAL_ENTITY, DIVISION, DESK, TT_BOOK, POSITION_ID, TRADE_ID]`: rows are ordered by book,
then by `POSITION_ID` (`TRADE_ID` is empty at position grain), so a lookup of one position reads one row group. VaR reads the whole date.

#### Step by step (Linux; each command was run as written)

**1. Name and cut the files.** One tar per business date, with one folder per kind and, inside it, the date's folder (section 4.4). The parts of a
date go in the same folder under any names (`part-0001.jsonl.gz`): **a tar sends one date in several files by the inbox**, and it loads
all three tables or none.

```text
eod-2026-11-12.tar
├── scenario-set/2026-11-12/scenario-set.jsonl.gz       one row
├── fx-scenario/2026-11-12/fx-scenario.jsonl.gz         one row a non-USD currency
└── scenario-pv/2026-11-12/part-0001.jsonl.gz           the positions, in as many parts as you like
    scenario-pv/2026-11-12/part-0002.jsonl.gz
```

Chunking: **200,000 to 400,000 positions a part** (about 200 to 400 MB of gzip at 250 values), each part gzipped: several parts are decompressed and
parsed in parallel, one part by one reader (a single 5 GB file loads on one core). The lake writes its own files of about 256 MB whatever you send. Do not
gzip the tar: its parts are already compressed.

```bash
cd /landing/2026-11-12                                    # holds scenario-set/ fx-scenario/ scenario-pv/, each with the date's folder
tar -cf /landing/eod-2026-11-12.tar scenario-set/2026-11-12 fx-scenario/2026-11-12 scenario-pv/2026-11-12     # Windows 10+: the same in PowerShell
sha256sum /landing/eod-2026-11-12.tar | cut -c1-64        # Windows: (Get-FileHash eod-2026-11-12.tar -Algorithm SHA256).Hash.ToLower()
```

**2. Drop the tar, and its `.ready` last** (section 3.1). The `.ready` is YAML, named after the tar in full. `sha256:` is checked before anything is
written (a wrong one goes to `failed/`: *the SHA-256 in its .ready file is not the file's*); `rows:` (optional) is the archive's documents, all its tables together: each table folder is counted
first without writing, and another count goes to `failed/` (*its .ready file says N rows, the archive holds M documents*), nothing written.
`lake: demo` is the lake holding your demo data.

```bash
INBOX=<data>/dataload/inbox                               # the owner node's data folder; Admin → Data loads → Inbox shows it
cp /landing/eod-2026-11-12.tar $INBOX/
cat > $INBOX/eod-2026-11-12.tar.ready <<EOF
lake: demo
sha256: $(sha256sum /landing/eod-2026-11-12.tar | cut -c1-64)
rows: $(( $(zcat /landing/2026-11-12/*/2026-11-12/*.gz | wc -l) ))
EOF
```

The inbox takes the pair once both are unchanged for 30 seconds (`stable-seconds`). Do not wait for the 10-minute cycle:

```bash
export DRISHTI_SERVER=http://host:18480
python3 tools/drishti.py dataload run --wait --token-file ~/.drishti-token   # "Load now", then each file's outcome; exit 0, 1 (failed), 2 (rejects, only above 0%)
python3 tools/drishti.py dataload status --token-file ~/.drishti-token       # waiting / failed / last cycles
```

`run --wait` ends with `exit 0: every file loaded, no row rejected`. **Exit 2 is loaded with rejected rows**: the line under the file says how many
of how many and why (*loaded with rejects: 3 of 20,000 rows rejected: DRS-5201 … (3)*), the same as a red *loaded · 3 rejected* on Admin → Data
loads and a notice in `status`: the date answers without those rows, so fix and resend before using it.

`status` ends with `done  1 loaded` when it worked (tested: 11 seconds for a 20,000-position date). The file, its marker and a report
`eod-….tar.loaded.json` are then in `outbox/<day>/<run>/`, with the loader's log under `loads/`; a failure is in `failed/<day>/<run>/` with
`.failed.json` in words, and `drishti.py dataload retry <file>` puts it back.

**3. The second date.** **Why it moved needs two dates**: it compares a date with the one before it (`var explain --from … --to …`, default the
previous one). Load the previous business day the same way, each date its own tar with its own `scenario-set` (the later set's days are the earlier's
rolled by one).

```bash
# repeat steps 1 and 2 with 2026-11-13
```

Sending the identical file again is skipped as *already loaded in run …* while the lake still holds its rows; when another load replaced its date
since (or `dataload undo` removed it), the same file loads again. `drishti.dataload.reload-duplicates: true` forces a reload of any file.

**4. The CLI alternative, tuned** (no inbox: a terminal, cron, or a machine that is not the owner node). **Always the Java loader** (`--engine java`):
readers, parsers and writers on every core, memory bounded and spilled to disk, **one Delta commit per business date and table** however many files
the folder holds. The Python writer (`--engine deltalake`) is never used for a production batch. One folder a table and date, the
date given by `--as-of` (never trust a name, see the troubleshooting), `--signal` tells the server; `scenario-set` and `fx-scenario` first (tiny), then
the positions as **one run over the date's folder**, not a run per part:

```bash
export DRISHTI_LAKE_LOADER_HEAP=24g                       # the loader's -Xmx (default 4g); the budget below must be under nine tenths of it
mkdir -p /fast/spill                                      # local NVMe, 15 to 30 GB free; removed when the load ends
for k in scenario-set fx-scenario; do
  python3 tools/drishti.py lake build --engine java --from /landing/2026-11-12/$k/2026-11-12 --as-of 2026-11-12 --lake demo --kind $k \
     --pack modules/banking/packs/market-risk --signal $DRISHTI_SERVER --token-file ~/.drishti-token
done
python3 tools/drishti.py lake build --engine java --from /landing/2026-11-12/scenario-pv/2026-11-12 --as-of 2026-11-12 --lake demo --kind scenario-pv \
   --pack modules/banking/packs/market-risk --signal $DRISHTI_SERVER --token-file ~/.drishti-token \
   --loader-set parallel.writers=16 --loader-set memory.budget-mb=20480 --loader-set memory.spill-dir=/fast/spill
```

`parallel.writers` (default 8, at most the cores) is the number of Parquet files written at once: 1.3M positions are about seven files of 256 MB, so 8 to 16
is enough; `parallel.readers` and `parallel.parsers` default to every core (give a number only to leave cores to the server). The budget covers the read
queue, the sort buffers and the writers; rows that do not fit are sorted and spilled to `memory.spill-dir`. The loader's last line is `lake-build ok exit=0 …
rows=… rejected=0`; the summary file it names has every stage's throughput. Exit 2 is loaded with rejects (read the summary). `--plan` and `--dry-run` show the
route and count without writing. **Tested** at 20,000 positions: 3.8 s wall, 24 cores busy (21 s of CPU), one commit, one file, loader peak heap 646 MB
(`parallel.writers=24`, `memory.budget-mb=6144`, `DRISHTI_LAKE_LOADER_HEAP=8g`).

**The inbox uses the same Java loader** (the ledger says `engine: java-loader`), one run a table: all files of the same connector or table that are ready in a
cycle are **one load and one commit per date** (tested: two parts of an Endur date, one commit); the three tables of a tar are three loads, each one commit a date. Files
for **different connectors** are separate loads into their own tables (one table a trading system, 16.2), each replacing only that system's date. So send a date as **one tar** (this section) and tune the inbox's loader with `DRISHTI_DATALOAD_LOADER_HEAP`; the inbox does not take
`--loader-set` options per drop: its settings are `drishti.dataload.*` and the lake file's layout.

**5. Verify** (the output below is from the tested run; `$T` is `--token-file ~/.drishti-token`).

```bash
python3 tools/drishti.py lake status --lake demo               # (reads the lake folder: no token) risk/scenario-pv: rows, files, size, dates, latest date
python3 tools/drishti.py var show BANK --date 2026-11-12 $T
```

```text
VAR BANK  ·  Drishti Bank  ·  node  ·  2026-11-12
  VaR 99%   1,355,248 USD   (the 3th worst of 250)
  ES 97.5%   1,327,210 USD   (mean of the 7 worst)
  20000 trades in 20000 positions
  pnl-vector and scenario-pv (20000 trades from scenario PVs) · batch B-2026-11-12-01 of 2026-11-12 · …
```

1. **The count is the file's.** `20000 trades in 20000 positions` equals the rows you sent (`zcat part-*.gz | wc -l`).
2. **Nothing was rejected.** A rejected row is skipped and the date still answers, so this check is not optional:

```bash
python3 tools/drishti.py var show BANK --date 2026-11-12 --json $T | python3 -c "import sys,json; p=json.load(sys.stdin)['provenance']; print('rejected',p['rejected'],p['scenarioWindow']['note']); [print(' ',x) for x in p.get('rejections',[])+p.get('warnings',[])]"
```

   Expect `rejected 0` and no lines under it. The window line says what was used (*newest 250 of 509 scenarios …*).
3. **The tree is yours.** `var tree --date … $T` and `var parts BANK --date … $T` list the divisions, desks and books from your columns; a **(Blank)** node
   means a level column was empty.
4. **One position.** `var show "POS:<POSITION_ID>" --date … $T`, and its record `python3 tools/drishti.py view get "scenario-pv/SPV-<POSITION_ID>-<SCENARIO_SET_ID>" --date … $T`
   (in the console: `SPV <id>`).
5. **Why it moved.** `python3 tools/drishti.py var explain BANK --from 2026-11-12 --to 2026-11-13 $T` prints the steps and `the steps add up: yes`
   (0.3 s tested); the same in the VaR page's Explain drawer, tab *Why it moved*.
6. **The first open of a date** builds its hot file (`<data>/var/hot/<date>/inputs-….vhot`). The load signal also starts the **official end-of-day capture** by
   itself (tested: `official VaR capture of 2026-11-12 … status=captured`), so **a load by mistake leaves an official record**: rehearse with
   `drishti.var.official.enabled: false`. A date reloaded after a signal is picked up in the background (within the 30 s version check): if the batch id
   shown is still the old run, ask again.

**6. After the load.** Compaction is not needed: the loader writes files of the target size (one file of 26.8 MB for 20,000 positions, about seven of
256 MB for 1.3M). Replaced dates leave their old files until vacuum; `drishti.py lake maintain --once --tables risk/scenario-pv` runs vacuum and
checkpoint per the lake file's `maintenance:` (it needs the `deltalake`, `pyarrow` and `pyyaml` Python libraries and a `<data>/lakes/lake-maintenance.yaml`
written by Admin → Data loads → Lakes → Maintain; **not run in this rehearsal**, [OPERATIONS_GUIDE.md section 16](OPERATIONS_GUIDE.md#16-lake-maintenance-and-scheduling)).
Undo a wrong load with `drishti.py dataload undo <load-id>` (a dry run; `--yes` undoes it; section 5.5).

#### Troubleshooting

| You see | Why | Do |
|---|---|---|
| `failed/`: *'part-x' is neither the date … nor an extension* | a part name other than `<table>.<date>.part-<n>.jsonl[.gz]` | name the parts `scenario-pv.2026-11-12.part-0001.jsonl.gz`, or put them in a tar (step 1) |
| `failed/`: *no business date: none given …, none in its name … and none of its rows carries BUSINESS_DATE* | a name without a date (`scenario-pv.jsonl`) and rows without `BUSINESS_DATE` | name the date (`scenario-pv.2026-11-12.jsonl.gz`), or `business-date:` in the `.ready` |
| `failed/`: *its business date is ambiguous …* | no date given or named, and the rows carry several dates (or some none) | one date a file, named |
| `failed/`: *its name says D, but none of its rows carries that date* | the name and `BUSINESS_DATE` disagree on every row | fix the name or the rows; a replace never puts another day's rows under D |
| `failed/`: *its .ready file says N rows, the archive holds M documents* | cut short, or a wrong `rows:` | resend, or correct the marker |
| `run --wait` exit 1, `failed/`: *its check failed before loading: 1 of 3 documents rejected (33.33%), more than reject-max-percent 0.0 (<reason> (1)): not loaded* | with the shipped `reject-max-percent: 0` **any rejected row fails the whole file**: nothing is committed (with `rows:` in the `.ready` the check run says it before loading) | fix the rows and resend the date |
| `run --wait` exit 2, *loaded with rejects: N of M rows rejected: …* | only when `reject-max-percent` is above 0 and the share rejected is within it: the good rows are committed (the reasons follow) | fix and resend the date; check 2 of step 5 |
| *already loaded in run …* | the same SHA-256 was loaded before and the lake still holds it | nothing to do; `reload-duplicates` to force |
| `failed/`: *the SHA-256 in its .ready file is not the file's* | cut short in transfer, or the wrong hash | resend, correct the marker, `dataload retry` |
| `422 DRS-5210` *ENDUR sent {PV/POSITION=167, PV/TRADE=1}* | a trade-grain row in a position-grain date | remove it: one form and one grain a date |
| `DRS-5203` *the vectors come from 2 runs (BATCH_ID …)* … *answered from <earlier date> instead* | two `BATCH_ID`s, or a `SCENARIO_SET_ID` that is not the set's | one batch a date; the page answers the earlier date meanwhile |
| rejected `DRS-5208` *no GRAIN* | a record without `VALUE_TYPE`, `GRAIN`, its id, `CURRENCY` or `SOURCE_SYSTEM` | add them (`drishti.py var convert`) |
| rejected `DRS-5206` *1 scenario failed to price: 17 (2025-12-12)* | a `null` or NaN in `SCENARIO_PV` | price it, or leave the position out: it is never zero-filled |
| rejected `DRS-5201` *the row has 249 scenario PVs; the scenario set has 250* | a vector of another length than `SCENARIO_COUNT` | fix the batch or the set |
| rejected `DRS-5201` *no BASE_PV* | no base PV | send it |
| rejected `DRS-5207` *its PVs are in CHF and the date has no CHF scenario FX* | no `fx-scenario` row for the currency | send it for the date |
| `DRS-5211`, a **(Blank)** node | a level column empty on some rows | fill it |
| `503 DRS-5204`, `DRS-5218` | the heap budget or the disk guard | free disk or raise the heap; open dates keep answering |
| `404` on `/api/v1/risk/var/scope` just after a start | the banking module is still installing | wait; `python tools/drishti.py status` |

#### Do not

- Do not send **trade** and **position** grain for one source system on one date, nor two forms (PV and P&L vectors); do not send a position and, alone, a trade it names.
- Do not name a file without its date (`scenario-pv.jsonl`) or with extra words in the name: a file with no date takes its rows' `BUSINESS_DATE`,
  and a replace refuses it when they carry none or several; name it `<table>.<date>[.part-<n>].jsonl.gz`.
- Do not put the `.ready` before the file is complete; do not edit a file in the inbox.
- Do not mix `BATCH_ID`s or scenario sets within a date; do not send `null` in a vector; do not restate a date under its old `BATCH_ID` (next `RESTATEMENT`, a new `BATCH_ID`, the whole date again: replace mode takes the whole date).
- Do not leave the five default reporting currencies on for the first load of 1.3M positions.
- **Never commit production data**: no file of the batch, no sample row with real values, no `.ready` with a real path. Rehearse on `tools/pvgen/bulk_positions.py`.

#### Printable checklist

```text
BEFORE   [ ] java 21   [ ] heap 8-10g   [ ] loader heap 16g   [ ] DRISHTI_VAR_CURRENCIES=USD   [ ] disk: 30 GB free (guard 20 GB and 10%)
         [ ] var.scenario-window 250 (or all)   [ ] inbox on   [ ] token (loads:write)   [ ] rehearsed with tools/pvgen/bulk_positions.py
FILES    [ ] every row: VALUE_TYPE PV, GRAIN POSITION, POSITION_ID, CURRENCY, SOURCE_SYSTEM, BUSINESS_DATE, SCENARIO_SET_ID, BASE_PV,
             SCENARIO_PV (= SCENARIO_COUNT values, no null), LEGAL_ENTITY, DIVISION, DESK, TT_BOOK, LIMIT_LETTER, BATCH_ID (one a date), COMPUTED_AT
         [ ] no TRADE_ID, no trade-grain row   [ ] scenario-set (1 row) and fx-scenario (1 row a non-USD currency) for the same date
         [ ] parts of 200-400K positions, gzip   [ ] one tar a date: scenario-set/<date>/ fx-scenario/<date>/ scenario-pv/<date>/   [ ] sha256 noted
LOAD     [ ] the earlier date first   [ ] cp tar to the inbox   [ ] .ready LAST (lake: demo, sha256, rows)   [ ] dataload run --wait: exit 0
         [ ] then the later date
VERIFY   [ ] var show BANK: positions = rows sent   [ ] rejected 0, no warnings, no (Blank)   [ ] var explain --from D-1 --to D: adds up
         [ ] SPV <position> opens   [ ] lake status shows both dates   [ ] disk still above the guard
IF WRONG [ ] dataload undo <load-id> (dry run, then --yes)   [ ] dated names only   [ ] dataload retry after fixing a failed file
```

#### What was measured (a rehearsal, not production)

20,000 positions × 250 values a date, two consecutive business dates (2026-11-12, then 2026-11-13 with 400 positions fewer), four gzip parts a date,
generated by `tools/pvgen/bulk_positions.py`; one 24-thread workstation, NVMe, Java 21, 4 GB server and loader heaps.

| Step | Measured |
|---|---|
| input a date | 66.8 MB as text (3,343 B a position), **20.3 MB gzip** (1,016 B a position) |
| inbox, a tar of three tables (claim to outbox) | **11 s**; `scenario-pv` 3.9 s of it (read 0.5 s, parse 0.5 s, write 1.7 s, commit 0.1 s), loader peak heap **646 MB** |
| `lake build --engine java`, tuned, one run over the four parts | **3.8 s** wall, 20,000 rows at 7,700 rows/s end to end (parse 52,000 rows/s, 24 threads; write 16,800 rows/s a file; commit 0.07 s), one commit |
| the lake | `business_date=…/SCENARIO_SET_ID=HS-250/GRAIN=POSITION/`, 1 file of **26.8 MB** a date (the Python writer 30.5 MB): 1,340 B a position |
| first open of a date, with the official capture | about 1.5 s; the USD hot file **42.1 MB** (2.1 KB a position), each extra currency 32.2 MB |
| `var show BANK`, `var explain` | 0.2 s, 0.3 s |
| server process after both dates | 2.0 GB resident |

**Extrapolated to 1.3M positions a date (an estimate: sizes scale with positions, times do not).** Text 4.3 GB, gzip about 1.3 GB (PVs with more
significant digits compress less: plan 1.5 to 2 times); the lake about 1.75 GB; the USD hot file about 2.7 GB plus the node aggregates (27,000 nodes
added 0.64 GB at 1M); a tuned Java loader run **about 1 to 2.5 minutes** (parse 52,000 rows/s over 24 threads: 25 s; seven files written in parallel at 17,000 rows/s each: 11 s; then the commit; the target is five minutes or less) ([BENCHMARK.md 7.1](../admin/BENCHMARK.md#71-the-200000-position-var-inputs-the-python-path-and-the-java-loader): 20 s
for 1M positions × 509 values); the first open of a date 25 to 60 s ([BENCHMARK.md 5.4](../admin/BENCHMARK.md#54-a-dates-first-open): 25 to 27 s at 1M), then
the official capture. With 509 values a vector the gzip, the lake and the hot file about double. The first real day's own numbers replace these.

### 16.2 Production trades from the source systems

**What this loads.** The trades of Endur, Calypso, Murex, WSS, SwapOne and ICI, as JSON Lines in each system's own field names (`UPPER_SNAKE`, as
Endur names them), into the one canonical `trade` kind (`TRD`). A source system is a **module** (`modules/source-<system>/`) that brings a
connector template, a field map (`mappings/<system>-trade.yaml`), a routing rule for the inbox (`dataload.yaml`) and its own on/off switch. The trade's
contract is [`trade.contract.yaml`](../../modules/banking/packs/trading/contracts/trade.contract.yaml), published as
[TRD](../contracts/packs/trading.md#trd-trade-trade). **Trades reach VaR only at trade grain, which is not used now**: VaR is fed by position-level scenario PVs
(16.1) and is unchanged by anything in this section, including switching a source off.

**The modules and where each lands** (tested with the synthetic files of `data/testdata/trades`, 13,400 trades over two dates):

| System | File name the module's rule takes | Native key | Field map | Table (as shipped) |
|---|---|---|---|---|
| Endur | `endur-<date>.jsonl.gz` | `TRADE_ID` | identity (Endur writes the canonical fields) | `trading.trade_endur` |
| Calypso | `calypso-<date>.jsonl.gz` | `TRADE_ID` | identity | `trading.trade_calypso` |
| Murex | `murex-<date>.jsonl.gz` | `TRADE_ID` | identity | `trading.trade_murex` |
| WSS | `wss-<date>.jsonl.gz` | `DEAL_REF` | `mappings/wss-trade.yaml` (`DEAL_STATE`, `DEAL_TYPE`, `FAMILY`, `DIR` …) | `trading.trade_wss` |
| SwapOne | `swapone-<date>.jsonl.gz` | `SWAP_ID` | `mappings/swapone-trade.yaml` (`LIFECYCLE`, `SWAP_FAMILY`, `PAY_LEG.NOTIONAL` …) | `trading.trade_swapone` |
| ICI | `ici-<date>.jsonl.gz` | `TICKET_NO` | `mappings/ici-trade.yaml` (`HEADER.STATE`, `INSTRUMENT.KIND`, `PARTIES.CPTY` …) | `trading.trade_ici` |

The rule's `date-pattern` reads the date from the name (`-(\d{4}-\d{2}-\d{2})\.`): the name must carry it. The files are stored **as the system sent them**; the
`map:` is applied when trades are **read** (Raw JSON on a trade page shows the stored native record), and `set: {SOURCE_SYSTEM: <SYS>}` stamps the system into
rows that lack the field. Every field of the contract is listed, with its type and whether it is required, in the contract; **the load checks no field of a trade**
(tested: WSS, SwapOne and ICI rows carry almost none of the canonical names natively and load whole; what the map does not fill shows empty on the page). The
fields to get right are the key (`TRADE_ID` or the system's native key), `SOURCE_SYSTEM`, the status, the product, the counterparty, the legal entity and the book.

#### Prerequisites

| Check | Value |
|---|---|
| Server heap and loader heap | as 16.1 (`DRISHTI_DATALOAD_LOADER_HEAP`); trades are far lighter than vectors |
| Disk | 1 million trades a day: the files 0.7 to 1.2 GB gzip (measured 0.65 to 1.2 KB a trade), the lake 1.1 to 2.5 KB a trade, an estimate from the measured 13,400; the inbox keeps a copy 30 days |
| Inbox | on, one owner node (as 16.1) |
| The source modules | on: `config/modules/source-<system>.yaml` has `enabled: true` (Admin → Modules) |
| A token | `loads:write` |

#### Where the trades go: one table a trading system, always

Every trading system has **its own table**: `trading.trade_endur`, `trade_calypso`, `trade_murex`, `trade_wss`, `trade_swapone` and
`trade_ici`, each read by its source module's connector (`lake-tables: trading.trade_<system>`, `set: {SOURCE_SYSTEM: <SYS>}`, no `where:`),
and the trade kind reads them as one union. A system's file replaces **that system's date in its own table and nothing else**, so one
system's late or corrected file never touches another's rows. Do not land several systems in one table.

(The loader can still split a table several connectors share by `SOURCE_SYSTEM`, each load replacing only its own slice, for a site that
already lands its systems together; no shipped module does.)

#### Step by step (Linux; each command was run as written)

**1. The files.** One file per system and date, named by the rule, gzipped. **Chunking:** a system's files that are ready in one inbox cycle are **one load and
one Delta commit a date** (tested: `endur-part1-2026-07-08.jsonl.gz` and `endur-part2-…` made one commit of 4,407 trades); parts of 250,000 to 500,000 trades each, any name matching the
rule's glob and carrying the date, `endur-part1-2026-07-08.jsonl.gz`. Files for different systems are different loads (one commit each).

**2. Drop the files and their markers.** `.ready` last; an empty marker is enough (the lake is the inbox's `lake:` setting, `demo`). Add `sha256:`
(`sha256sum`) when you want the file checked; `rows:` is checked as well (a tar: all its tables together).

```bash
INBOX=<data>/dataload/inbox
cp /landing/endur-part1-2026-07-08.jsonl.gz /landing/endur-part2-2026-07-08.jsonl.gz /landing/calypso-2026-07-08.jsonl.gz \
   /landing/murex-2026-07-08.jsonl.gz /landing/wss-2026-07-08.jsonl.gz /landing/swapone-2026-07-08.jsonl.gz /landing/ici-2026-07-08.jsonl.gz $INBOX/
for f in $INBOX/*-2026-07-08.jsonl.gz; do touch $f.ready; done                       # Windows: ni "$f.ready"
export DRISHTI_SERVER=http://host:18480
python3 tools/drishti.py dataload run --wait --token-file ~/.drishti-token            # after the 30 s of stability; exit 0, 1 (failed), 2 (rejects, only above 0%)
python3 tools/drishti.py dataload status --token-file ~/.drishti-token                # done  7 loaded
```

A tar works for every source module that is on: one folder per system named after its connector, `trade_endur/<date>/…`, `trade_calypso/<date>/…`,
`trade_murex/<date>/…`, `trade_wss/<date>/…`, `trade_swapone/<date>/…`, `trade_ici/<date>/…`, in one tar with one `.ready` (a tar loads whole or not at
all; each folder replaces that system's date in its own table).

**3. The second date**, the same with `2026-07-09`. **Which trades a date holds is your choice and it matters**: the trade pages, the blotter and the pivot read **one business
date's rows**, so a trade is seen on a date only when that date's file carries it (tested: a trade of the 2026-07-08 file is not found on 2026-07-09, whose file held only the
day's changes: *no source holds trade/1000001 … for 2026-07-09*). Send each date's full set of live trades, or knowingly only the day's changes.

**4. The CLI alternative** (`--engine java`, never the Python writer; one run over a system's files, one commit a date):

```bash
python3 tools/drishti.py lake build --engine java --from /landing/endur-2026-07-08 --as-of 2026-07-08 --lake demo --pack modules/banking/packs/trading \
   --kind trade --connector trade-endur --key TRADE_ID --signal $DRISHTI_SERVER --token-file ~/.drishti-token \
   --loader-set parallel.writers=16 --loader-set memory.budget-mb=20480 --loader-set memory.spill-dir=/fast/spill        # DRISHTI_LAKE_LOADER_HEAP=24g
```

**5. Verify** (the output is from the tested run):

```bash
python3 tools/drishti.py pivot run --source trade --date 2026-07-08 --rows SOURCE_SYSTEM --values "count()" $T
```

```text
SOURCE_SYSTEM    count()
CALYPSO            3,471
ENDUR              4,407
ICI                  230
MUREX              4,198
SWAPONE              240
WSS                  207
Grand total       12,753
```

1. **The counts are the files'** (`zcat endur-*.gz | wc -l`), per system; a system missing from the list is off, or loaded to a table no connector reads.
2. **A trade** of each system: `python3 tools/drishti.py view get trade/<TRADE_ID> --date 2026-07-08 $T` (about 0.2 s whatever the table: one row group), the same in the console as `TRD <id>`, the
   trade page's Raw JSON for the stored native record, the Trades menu's **blotter** (`/live/trade`) with a filter on `SOURCE_SYSTEM`.
3. **The map worked**: WSS `DEAL_STATE: Verified` shows as status `NEW`, SwapOne `SWAP_FAMILY: CDS` as product `CDS_SINGLE`, ICI `INSTRUMENT.SIDE` as `BUY_SELL_CODE`.
4. **A filter**: `--filter SOURCE_SYSTEM=WSS` (tested: 14 on 2026-07-09).

**Switching a source system on and off.** Admin → Modules, or `enabled: false` in `config/modules/source-<system>.yaml`: **no restart** (tested: ICI left the pivot within 15 seconds and
came back when switched on). Its trades leave the pages, the blotter, search and BI, its inbox rule goes with it (a site rule stays), and VaR is unchanged.

**A new or changed attribute.** The stored row keeps what the system sent, so a new field costs nothing at load: add it to the system's map (`modules/source-<system>/mappings/<system>-trade.yaml` or a
`map:` file beside the site's connector: `<canonical>: <native.path>`, with `values:`, `first-of:`, `scale:`, `as:`; section 6.2) and the page shows it; **not tested here**
whether a connector re-reads a changed map without a restart (restart, or Admin → Connectors). For a **new shape of JSONL** (a seventh system, or a file you cannot map by hand) use the Pack designer's **A source for an existing kind**
([BUILD_GUIDE.md 8.9](BUILD_GUIDE.md#89-a-source-for-an-existing-kind)): it proposes the map from the file, writes the module `source-<system>` with its rule, connector and map, and deploys it.

#### Troubleshooting

| You see | Why | Do |
|---|---|---|
| `failed/`: *no rule matches this name* | the name does not fit `<system>-*.jsonl.gz` | rename, or add a rule |
| `failed/`: *… is neither the date … nor an extension* | a name with a word between table and date, for a file with no rule | use the rule's pattern (`endur-part1-2026-07-08.jsonl.gz`) |
| the trades of the system are missing, the others are there | its module is off, or its connector reads a table that holds nothing | `enabled: true`; `pivot run` per system; Admin → Connectors |
| *the table of connector trade-wss is shared by …: a replace would take out the other connectors' rows* | a shared table not split by `SOURCE_SYSTEM` (a site's lake entry without `partition-by: [SOURCE_SYSTEM]`) | add it to the entry (a new table), or give the system its own table |
| rejected *SOURCE_SYSTEM is ICI, not this load's WSS* | another system's row in WSS's file | one system a file |
| `refused, nothing was loaded: trade_xyz: no pack, kind or table of that name` | a tar folder that is no connector or table of an enabled source module | the connector's name with `_` (`trade_wss`), and the module on |
| `404 no source holds trade/<id> … for <date>` | the id is not in that date's file | ask the date that has it; send full dates |
| a page shows empty fields for a mapped system | the native field is not in the map | add it (above) |
| `DRS-5034` on Admin → Connectors | a `where`, a missing or duplicate map entry | the reason names it |

#### Do not

- Do not mix two systems' trades in one file: each system's file replaces only that system's rows; do not name a part without the date.
- Do not drop a file without its `.ready`, or edit it in the inbox.
- Do not edit the stored data to fit the map: change the map.
- Do not expect trades to feed VaR: that is the scenario PVs (16.1).
- **Never commit production trades**, nor a sample row with real values; rehearse on `data/testdata/trades` (synthetic).

#### Printable checklist

```text
BEFORE   [ ] source modules on   [ ] one table a system: trading.trade_<system> (as shipped)
         [ ] inbox on, token   [ ] disk for the files twice (inbox, outbox)   [ ] loader heap as 16.1
FILES    [ ] <system>-<date>.jsonl.gz (parts: <system>-partN-<date>.jsonl.gz)   [ ] native key present   [ ] each date a full set (or deltas knowingly)
LOAD     [ ] cp files   [ ] touch .ready   [ ] dataload run --wait: exit 0
VERIFY   [ ] pivot count per SOURCE_SYSTEM = rows sent   [ ] view get trade/<id> --date D for one trade of each system   [ ] blotter filter SOURCE_SYSTEM
         [ ] mapped fields filled   [ ] switch one source off and on again
```

#### What was measured

13,400 trades of the six systems in two dates (12,753 and 647), gzip 9.4 MB, one 24-thread workstation, `-Xmx4g` loader. The seven files of the first date: **11 s** from claim to loaded
(one JVM a connector: 1 to 3 s each; Murex 4,198 trades of 64 MB as text in 2.3 s). Stored size a trade: Endur about 1.5 KB, Calypso 1.1 KB, Murex 2.5 KB (15 KB as text). The lake:
Endur partitioned by `PRODUCT_TYPE`, Calypso and Murex by `PRODUCT_GROUP`, the others by date alone. **Estimate for 1M trades a date**: 1 to 3 GB of lake, the load one to a few minutes
(the loader parsed 166 MB/s of text in 16.1's run; Murex-shaped trades are 15 KB of text each, 15 GB, about 2 minutes of parsing). The files of one system are one run: split a day of 1M into parts of 250,000 to 500,000.

### 16.3 Production netting sets, with their agreements and CSAs

**What this loads.** The counterparty-risk pack's `netting-set` (`NSET`), with the `agreement` (`AGR`) and `csa` (`CSA`) it points at, from the xVA engine and the legal
documents system: one JSON object per netting set and business date, its member trades inside it (`TRADES`). The contracts are
[`netting-set`](../../modules/banking/packs/counterparty-risk/contracts/netting-set.contract.yaml),
[`agreement`](../../modules/banking/packs/counterparty-risk/contracts/agreement.contract.yaml) and
[`csa`](../../modules/banking/packs/counterparty-risk/contracts/csa.contract.yaml), published with the pack ([contracts](../contracts/packs/counterparty-risk.md)).

**Related inputs, and how they link.**

| Kind | Needed | Link |
|---|---|---|
| `netting-set` | the batch | `AGREEMENT`, `CSA`, `COUNTERPARTY`, `LEGAL_ENTITY`, `CREDIT_LIMIT`, `CVA_RESULT`, `SACCR`, `SIMM`, `COLLATERAL_BALANCE` name the other kinds' ids |
| `agreement` | yes, one per master agreement | `CSAS` lists its CSAs; `COUNTERPARTY` |
| `csa` | yes, one per agreement and bank legal entity | `AGREEMENT`, `NETTING_SETS`, `COLLATERAL_BALANCES` |
| `counterparty`, `legal-entity` (core pack) | already in the lake: the page names them | `COUNTERPARTY`, `LEGAL_ENTITY` |
| `credit-limit`, `cva`, `sa-ccr`, `simm`, `collateral-balance` | optional: the sections of the netting-set page that read them show *Under construction* or empty without them | the ids above |
| `exposure-path` | optional: the exposure profile, EPE and PFE (*no exposure paths in any connector* otherwise, tested) | `NETTING_SET` |

**Fields.** The netting set's contract: 19 fields, 18 required. The ones the pages read: `NETTING_SET_ID` (the key, `NS-<counterparty>-<legal entity>`), `TRADE_COUNT`, `NET_MTM`, `COLLATERAL`, `LIMIT`, `CVA`,
`TRADES` (the member trades, one element each with its id, status, product, notional, maturity and MtM), `BY_ASSET`, `COUNTERPARTY`, `AGREEMENT`, `CSA`, `LEGAL_ENTITY`,
`NETTING_ENFORCEABLE` (false treats the trades gross). The load rejects nothing of them (tested path: the rehearsal's records carry all 19). `TRADE_IDS` is optional.

**Layout.** The lake file has `credit.netting-set: {id: NETTING_SET_ID}`: partitioned by business date only, the writer's default sort and row groups. One date of 5,000 sets is one small file;
the record page opened in 0.28 s (client included). For a production count of tens of thousands add the trades' layout (`sort-by: [id]`, `row-group-rows: 1000`) to the entry: **not tested here**, the
default layout was.

#### Step by step (Linux; each command was run as written)

**1. The files.** One tar a business date: `netting-set/<date>/`, `agreement/<date>/`, `csa/<date>/` (section 4.4: the folders are the pack's kinds), parts of 50,000 sets each at about 3.3 KB of text a
set (tested size; a set with a few hundred trades is larger).

```bash
python3 tools/pvgen/bulk_netting.py --out /rehearsal/ns --dates 2026-07-08,2026-07-09 --sets 5000          # a rehearsal batch: synthetic, never commit
tar -C /rehearsal/ns -cf /rehearsal/nset-2026-07-08.tar netting-set/2026-07-08 agreement/2026-07-08 csa/2026-07-08
```

**2. Drop it** with its `.ready` last, and run the cycle (as 16.1 step 2):

```bash
cp /rehearsal/nset-2026-07-08.tar $INBOX/
printf 'lake: demo\nsha256: %s\n' $(sha256sum /rehearsal/nset-2026-07-08.tar | cut -c1-64) > $INBOX/nset-2026-07-08.tar.ready
python3 tools/drishti.py dataload run --wait --token-file ~/.drishti-token
```

**3. The second date**, the same with 2026-07-09 (both tars in one cycle load as two runs). Each tar loads three tables, **one commit a date for each** (tested: 6 loads, 6 commits for two dates).
With the CLI use `--engine java` and one run per kind (16.1 step 4).

**4. Verify.**

```bash
python3 tools/drishti.py pivot run --source netting-set --date 2026-07-09 --rows LEGAL_ENTITY --values "count()" --values "sum(NET_MTM)" $T
python3 tools/drishti.py view get "netting-set/NS-SOLARIS-TKY-004320" --date 2026-07-09 $T          # the record page; console: NSET <id>
python3 tools/drishti.py view get "csa/CSA-ALDERSHOT-FRA-000000" --date 2026-07-09 $T                 # CSA, and agreement/<id> for AGR
python3 tools/drishti.py lake status --lake demo                                                       # credit/netting-set, legal/csa, legal/agreement: rows, dates
```

Tested: 5,000 netting sets, 5,000 CSAs and 2,764 agreements on each date; the pivot's grand total 5,000 on each date; the page shows *Trades 14*, net MtM, collateral and the **Member trades (14 of 14)** table; EPE
and PFE are empty until `exposure-path` rows exist. **Pass `--date`**: without it the page asks the newest date any source has (here 2026-11-13) and answers *no source holds netting-set/… (file for 2026-11-13)*.

#### Troubleshooting

| You see | Why | Do |
|---|---|---|
| `404 no source holds netting-set/<id> … for <date>` | the date asked has no such set | `--date` of a loaded date |
| a netting set's page says *Under construction* for CVA, empty EPE and PFE | `cva` / `exposure-path` not loaded | load them  |
| the CSA page does not list its sets | `NETTING_SETS` missing on the CSA | send it, or the agreement's `CSAS` |
| `refused … no pack, kind or table of that name` | a tar folder that is not a kind of the pack | `netting-set`, `agreement`, `csa` |
| *already loaded in run …* | same bytes sent twice, still in the lake | nothing to do; `reload-duplicates` to force |

#### Do not

- Do not send a netting set whose `CSA` or `AGREEMENT` is not in the same date's files: the link pages are empty.
- Do not name the tar's folders in other words, or put the date in the `.ready` of a tar.
- **Never commit production netting sets** (counterparty names, limits and MtM are confidential); rehearse on `tools/pvgen/bulk_netting.py`.

#### Printable checklist

```text
BEFORE   [ ] inbox on, token   [ ] disk (the tar twice)   [ ] counterparties and legal entities already loaded (core pack)
FILES    [ ] NETTING_SET_ID, AGREEMENT, CSA, COUNTERPARTY, LEGAL_ENTITY, TRADES on every set   [ ] every AGREEMENT and CSA the sets name is in the same date's files
         [ ] one tar a date: netting-set/<date>/ agreement/<date>/ csa/<date>/   [ ] sha256 in the .ready
LOAD     [ ] earlier date first   [ ] cp tar   [ ] .ready LAST   [ ] dataload run   [ ] done N loaded
VERIFY   [ ] pivot count = sets sent, per date   [ ] view get netting-set/<id> --date D   [ ] the CSA and agreement pages   [ ] lake status shows both dates
```

#### What was measured

5,000 netting sets (a 3.3 KB set, 16 MB gzip a date as text 3 times that), 5,000 CSAs and 2,764 agreements on each of two dates, generated in 9 s; the inbox loaded both tars in one cycle,
each table of each tar in 1.0 to 1.7 s (JVM start included): 44 MB of lake for 10,000 sets (4.4 KB a set), pivot of 5,000 sets 2.6 to 3.1 s the first time. **Estimate**: tens of thousands of sets a day are tens of MB of
tar and a load of seconds.

## 17. Daily loads: the business-day runbook

Section 16 loads **one kind**; this section is the **day**: every feed of every business day, from many systems, without a day ever touching the days
before it. It links to the per-kind procedures ([16.1 scenario PVs](#161-production-position-level-scenario-pvs-for-var-explain-and-why-it-moved),
[16.2 trades](#162-production-trades-from-the-source-systems), [16.3 netting sets](#163-production-netting-sets-with-their-agreements-and-csas)) and does not
repeat them. Everything below was **run on a server with the inbox on and three business days (2026-07-08, 09, 10), plus a fourth for the rejects**, 5,000 positions × 250
scenarios a day and the six trade systems (synthetic; the counts are in 17.3). What was **not** run is said where it comes up.

### 17.1 The cycle of a day

**What arrives, from whom, under what name.** Every file is `.jsonl.gz`, one **table and one business date** a file, its `.ready` marker last (section 4):

| Feed | Sent by | File name (the date is always in the name) | Table | One date's file must be |
|---|---|---|---|---|
| Books, desks, legal entities, counterparties (portfolio data) | the organisation, entity and counterparty masters | `book.<date>.jsonl.gz`, `desk.<date>…`, `legal-entity.<date>…`, `counterparty.<date>…` | `reference.<kind>` | **the whole day's list** (17.5) |
| Trades, one file per system | Endur, Calypso, Murex, WSS, SwapOne, ICI, each on its own schedule | `<system>-<date>.jsonl.gz` (the source module's rule, 16.2); parts `<system>-part1-<date>.jsonl.gz` | `trading.trade_<system>` (one table a trading system, always) | the system's full set of live trades for the date, or knowingly only its changes (16.2 step 3) |
| Scenario PVs, scenario set, FX of the scenarios | the pricing systems, as one batch | one tar `eod-<date>.tar`: `scenario-set/<date>/`, `fx-scenario/<date>/`, `scenario-pv/<date>/part-NNNN.jsonl.gz` (16.1 step 1) | `risk.*` | **the whole date, one `BATCH_ID`**, all pricing systems together (17.6) |
| Netting sets, agreements, CSAs | the xVA engine and the legal documents system | `nset-<date>.tar` (16.3) | `credit.*`, `legal.*` | the whole date; later |

A plain file may also be named `<table>.<date>.part-0001.jsonl.gz` (any number of parts, one load); a name with a word between table and date, or no date, is refused to
`failed/` (tested: *endur-latest.jsonl.gz carries none … rename it*). **The business date is the one in the name**, never "today".

**Where.** `<data>/dataload/inbox/` of the **owner node** (Admin → Data loads → Inbox shows the path). Copy the data file, then write its marker. For a file
that crosses a network, write it under a dot name or another folder and move it in, so the inbox never sees half a file; the marker last is the rule.

**The marker** names the file in full plus `.ready` (`endur-2026-07-09.jsonl.gz.ready`) and carries the checks. Tested, every day, every file:

```yaml
lake: demo                 # the lake the pack's connectors read (section 6)
sha256: 2b2a8bdede4047f53b379bd476645547a527a91fddf20764b77038beb25a3201    # sha256sum of the data file: a wrong one goes to failed/
rows: 5005                 # the documents in the file; a tar: every table together (zcat … | wc -l)
```

**The order inside a day.** What each feed needs was read from the code and the contracts and then tested:

1. **Portfolio data first.** It is *state per day* (17.5); trade and VaR pages read it for names and desks, but loading it does not depend on anything.
2. **Trades second.** They need nothing and nothing waits for them: **VaR does not read trades** at position grain (16.2), so trade lateness never delays VaR.
3. **The scenario-PV tar last.** The tar is the date's VaR input and its load **signals the VaR engine** (the date reloads within seconds) **and starts the official
   end-of-day capture** (16.1 step 5, item 6: tested there; switched off in this run, so not re-tested here). Loading it after the other feeds means the official
   record, Explain and the trade pages all describe the same completed day. The hierarchy (legal entity, division, desk, book, limit letter) comes **from the PV rows' own
   columns**, not from the book or desk files: a PV tar never waits for reference data (tested: the PV tars loaded after the reference files on days 1 and 2 and in the same cycle on day 3, with the same VaR tree).
4. **Netting sets and exposure** after the trades and PVs, in the tar of 16.3.

**The cut-off.** The inbox takes what is ready at each cycle (every 10 minutes; `dataload run` starts one at once) and a file must be unchanged for 30 seconds. There is no
cut-off in the product: **a file that arrives late is simply loaded in the next cycle, and the pages change when it is** (a trade pivot, for instance, then shows the system's
rows). So the *organisation* sets the time, and a **verification job** (17.8, `verify-day.sh`) runs at that time and **fails when a feed has no load for the date**. Decide per feed
what a miss means: a missing Murex file is a Murex gap in the trade pages only; a missing PV tar means VaR for the date is not there (17.5).

### 17.2 Loading a day, step by step (each command was run as written)

The three runs of the test, abbreviated (`$INBOX=<data>/dataload/inbox`, `$T` = `--token-file ~/.drishti-token`):

```bash
# 1  portfolio data of 2026-07-09
for k in book desk legal-entity counterparty; do
  cp /landing/$k.2026-07-09.jsonl.gz $INBOX/
  printf 'lake: demo\nsha256: %s\nrows: %s\n' $(sha256sum /landing/$k.2026-07-09.jsonl.gz | cut -c1-64) $(zcat /landing/$k.2026-07-09.jsonl.gz | wc -l) > $INBOX/$k.2026-07-09.jsonl.gz.ready
done
# 2  each trade system when it arrives (the same two lines per system); the cycle can run between them
cp /landing/endur-2026-07-09.jsonl.gz $INBOX/ && printf 'lake: demo\nsha256: …\nrows: …\n' > $INBOX/endur-2026-07-09.jsonl.gz.ready
# 3  the PV tar last
tar -C /landing/pv -cf /landing/eod-2026-07-09.tar scenario-set/2026-07-09 fx-scenario/2026-07-09 scenario-pv/2026-07-09
cp /landing/eod-2026-07-09.tar $INBOX/ && printf 'lake: demo\nsha256: %s\nrows: %s\n' $(sha256sum /landing/eod-2026-07-09.tar | cut -c1-64) $(tar -xOf /landing/eod-2026-07-09.tar | zcat -f | wc -l) > $INBOX/eod-2026-07-09.tar.ready
# 4  load, wait, read the exit code
python3 tools/drishti.py dataload run --wait $T          # 0 loaded · 2 loaded with rejects · 1 a file failed (17.7)
```

A cycle of the test's size took 45 to 50 seconds, 30 of them the stability wait (the six trade files, 13,400 trades: one cycle; the tar of 5,005 rows: one cycle, 11 s of loading).

### 17.3 Proof: loading day N never touches the days before it

Three business days loaded one after the other (reference first, trades staggered, the tar last), each feed in its own cycle or several together (the third day's eleven files were one cycle).
**After the third day, the Delta log of every table shows only `WRITE` versions that add rows for the new date; no version removes or rewrites an older date's file** (counts from the logs: adds minus removes per partition):

| Table | 2026-07-08 | 2026-07-09 | 2026-07-10 | Versions (each a day's add) |
|---|---|---|---|---|
| `reference.book` (30 books a day) | 30 | 30 | 30 | 3 |
| `reference.desk` · `legal-entity` · `counterparty` | 10 · 4 · 24 | 10 · 4 · 24 | 10 · 4 · 24 | 3 each |
| `trading.trade_endur` | 4,407 | 138 | 124 | 3 |
| `trading.trade_calypso` | 3,471 | 198 | 202 | 3 |
| `trading.trade_murex` | 4,198 | 269 | 258 | 3 |
| `trading.trade_wss` | 207 | 14 | 11 | 3 |
| `trading.trade_swapone` | 240 | 10 | 19 | 3 |
| `trading.trade_ici` | 230 | 18 | 11 | 3 |
| `risk.scenario-pv` (positions) | 5,000 | 4,900 | 4,900 | 3 |
| `risk.scenario-set` · `fx-scenario` | 1 · 4 | 1 · 4 | 1 · 4 | 3 each |

The first day's answers did not move when the next two arrived: **VaR BANK 2026-07-08 was 103,986 USD** after the first day, after the second and after the third (and after the
restarts and corrections below); 2026-07-09 is 113,266 and 2026-07-10 is 132,262, 5,000, 4,900 and 4,900 positions, `rejected 0`, and `var explain BANK --from 2026-07-09 --to 2026-07-10`
says *the steps add up: yes* (market +18,996). Check it yourself, with no server needed:

```bash
python3 tools/drishti.py lake status --lake demo                     # dates, rows and last build of every table (a lake's name, or its folder)
python3 - <<'EOF'                                                       # live rows per partition and the versions of one table, from the Delta log (a library is not needed)
import json, glob, collections, sys
t = sys.argv[1] if len(sys.argv) > 1 else "<data>/delta/demo/trading/trade_wss"
live, hist = {}, []
for f in sorted(glob.glob(t + "/_delta_log/*.json")):
    ops = collections.Counter(); op = "?"
    for line in open(f):
        a = json.loads(line)
        if "add" in a:
            x = a["add"]; n = json.loads(x["stats"])["numRecords"] if x.get("stats") else 0
            live[x["path"]] = (tuple(sorted(x["partitionValues"].items())), n); ops["add"] += n
        elif "remove" in a:
            r = live.pop(a["remove"]["path"], None); ops["remove"] += r[1] if r else 0
        elif "commitInfo" in a: op = a["commitInfo"].get("operation", "?")
    hist.append((len(hist), op, dict(ops)))
c = collections.Counter()
for pv, n in live.values(): c[pv] += n
for k in sorted(c): print(" ".join(f"{a}={b}" for a, b in k), c[k])
print(hist)
EOF
```

### 17.4 One table a trading system: each system's date is its own

**Early and late systems.** On 2026-07-09 Endur and Calypso arrived first, the pricing tar next, and Murex, WSS, SwapOne and ICI **later in another cycle**: the later cycle changed nothing of the earlier loads.
**A resend replaces only the sender's date in its own table**:

| Resend | Before | After | What the log shows |
|---|---|---|---|
| Murex 2026-07-09 (own table `trade_murex`) with 250 rows | 269 | **250** | one commit `remove 269 add 250`; Murex 07-08 (4,198) and 07-10 (258) unchanged; Endur, Calypso untouched |
| WSS 2026-07-09 (own table `trade_wss`) with 9 rows | 14 | **9** | `remove 14 add 9`; SwapOne's 10 and ICI's 18 of the same date, and WSS's other dates, unchanged |
| The same Murex file again | | not loaded | `duplicate: already loaded in run … (the same SHA-256)`; exit 0 |

Three rules from it. (1) **A system's file for a date is that system's whole date**: rows of the date that the new file lacks are removed (the 19 rows missing from the resent Murex file are gone). (2) **Send a
system's own rows only**: a row of another system in WSS's file is rejected (*SOURCE_SYSTEM is ICI, not this load's WSS*, 17.7). (3) **One table a trading system, always**: never land several systems in one table.
**The scenario PVs do not work this way, by design**: `risk.scenario-pv` is **always one table** for all pricing systems, loaded as **one complete file or tar per business date**: a day's PV file **must contain every pricing system's positions**, and **a partial file replaces the whole date** (17.6, tested: a tar with only Murex's 1,633 positions
left the date with 1,633 positions and VaR 82,176 instead of 113,266 until the full tar was loaded again). There is no per-system slice of scenario PVs.

### 17.5 Portfolio data: state per day, complete files, and a missing day

The core pack's `book`, `desk`, `legal-entity`, `counterparty`, `trader` (and `counterparty-group`, `issuer`, `ccp`, `clearing-account`, `calendar`) are **one row per entity per business date**
(`dated-by: business_date`, contract `grain: One row per book per business date`; `cadence: daily`). Tested on `book`, `desk`, `legal-entity`, `counterparty`:

- **Each day's file is a full snapshot of that day.** A file replaces its date: a 5-book file for 2026-07-09 left **5 books** on that date (30 → 5; 07-08 and 07-10 kept 30). A "delta" file is therefore a data loss for the day: **send every entity every day**, unchanged ones included.
  To change one entity, send the day's whole file again.
- **Slowly changing data is the same file each day**; history is the sequence of dated files. `drishti.py history` shows one entity over the days (changed cells marked).
- **A missing day is not refused and not filled in.** Asking for a date with no file (tested: 2026-07-13):
  - a record page (`view get book/BOOK-COMM-1 --date 2026-07-13`) answers **the newest earlier date, and says so** (`provenance.businessDate: 2026-07-10`); nothing is copied into the lake;
  - a pivot of the kind (`pivot run --source book --date 2026-07-13`) is refused: *DRS-4003 book holds no data on 2026-07-13: its dates run from 2026-07-08 to 2026-07-10*;
  - a trade page by id (`view get trade/1000001 --date 2026-07-13`) is `404 no source holds trade/1000001 … for 2026-07-13` (a trade is seen on a date only when that date's file carries it, 16.2);
  - a trade pivot or a trade list for a date with files from only some systems shows each other system's newest earlier date **and says so, per system**: on 2026-07-13, with only WSS loaded, the pivot shows Endur, Calypso and Murex from 2026-07-10 beside WSS, with the notice *Not every source has data for 2026-07-13: trade-endur: no load for 2026-07-13, showing 2026-07-10; …* (the pivot's and the records page's banner; `asOfNotices` in `POST /api/v1/pivot/query` and a search's live table, `/api/v1/rdp/searches`, one entry per source system: `connector`, `asked`, `answered`). A date with no data is never answered silently from an older one.
- **VaR with no PV tar for the date** answers **the newest earlier date, with a warning**: the page shows *⚠ scenario P&L for 2026-07-13 not loaded — showing 2026-07-10* under the header, whose business date is 2026-07-10; the API answers `businessDate: 2026-07-10` and `asOfNotice` (and the same words in `provenance.warnings`). The figures are 2026-07-10's own, unchanged. The official capture of 2026-07-13 is skipped with that reason, never published from the earlier date. `var explain --from 2026-07-10 --to 2026-07-13` is refused (*from must be before to*). Nothing is carried into the lake. The hierarchy
  of a VaR date is that date's PV rows, so a missing book file changes no VaR number; a missing PV tar makes the date answer as yesterday, said on the page. The verification job (17.8) checks it too.

### 17.6 Scenario PVs every day

16.1 is the procedure; for the daily cycle:

| Point | Rule (tested) |
|---|---|
| One tar a date, parts of 200,000 to 400,000 positions | the three folders of the date: `scenario-set/<date>/`, `fx-scenario/<date>/` (a row per non-USD currency), `scenario-pv/<date>/part-NNNN.jsonl.gz`; the tar is three loads, one commit each; 5,005 rows in 11 s |
| The scenario set and the window | each date's `scenario-set` row (`SCENARIO_COUNT` = the vectors' length). The engine uses the **newest `drishti.module.banking.var.window` scenarios of the date's own set** (default 250; `all`): the answer says *all 250 scenarios (2025-07-25 … 2026-07-09)*. The next day's set is the earlier one rolled by one scenario |
| Preload of the previous date | none to do: the two dates are two ordinary loads. **Why it moved needs both dates in the lake** (`var explain` compares D with the date before it); load D-1 the day before. The hot file of a date is built at its first open and reloaded by itself when a load replaces it (tested: the restated date answered the right figure within seconds) |
| Keep at least two dates | `DRISHTI_VAR_HOT_DATES` (default 10) is how many dates stay open in memory; **keep ≥ 2** (3 in the test) so today and yesterday both answer at once. The lake keeps every date until retention (17.8) |
| Checks after the load | `var show BANK --date D` (positions = rows sent, `rejected 0`, no warning), `var explain BANK --from D-1 --to D` (*the steps add up: yes*), `var tree --date D` (no **(Blank)** node), `var show "POS:<id>" --date D`: `verify-day.sh` does the first, 17.8 |
| Restating a date | send the whole date again, its own tar and `BATCH_ID`/`RESTATEMENT` (16.1 do-not list). Tested: re-sending 2026-07-09's tar after the Murex-only load put its 4,900 positions back and VaR 113,266 exactly; 07-08 and 07-10 stayed as they were (103,986 and 132,262). The old files stay on disk until vacuum |
| One system's PVs only | **do not**: the whole date is replaced, so the other systems' positions leave VaR (tested above) |

### 17.7 Corrections, late files, undo, and what not to do

| Case | How | Tested result |
|---|---|---|
| **A system re-sends a past date** (restated trades) | the same name, new content; replace (the default): that system's date only | 17.4: only that slice replaced |
| **The identical file again** (a retry, a double drop) | nothing to do | `duplicate`, *already loaded in run …*, exit 0; the lake is unchanged. A file whose rows a later load replaced loads again (re-sending 2026-07-09's tar after the Murex-only load) |
| **An extra file for a date, added to what is there** (a late trade, an intraday delta) | `mode: append` in the **file's own `.ready`**, and different `TRADE_ID`s | Endur 2026-07-09: 138 → **143**, one version `add 5`, the original 138 untouched; the new ids open (`view get trade/9100001 --date 2026-07-09`). Append never deduplicates across files: send only new or changed ids |
| **An extra file without `mode: append`** | **do not**: replace mode takes the system's whole date | tested: a 3-trade Calypso "late" file for 2026-07-10 left **3** trades where 202 were (`remove 202 add 3`) |
| **Undo a bad load** | `drishti.py dataload undo <load-id>` (a dry run; `--yes`): the id is in the file's `.loaded.json` (`loads`) | the 3-trade replace was undone: **202 rows restored**, version history `…, remove 202 add 3, remove 3 add 202`. The undo sends the same data-load signal a load sends, one per table and date undone (the answer's `signals`), so the pages and pivots read the table again at once |
| **A tar** with a wrong date or table folder | the whole tar is refused, nothing written | 16.1, 16.3 |
| **Never**: edit a file in the inbox, put the marker first, name a file without its date, send two systems in one trade file, send a partial portfolio or PV file for a date | | 17.4, 17.5, 17.6 |

**What a failure looks like (all tested; the exit code of `dataload run --wait` is 0, 2 or 1)**

| You see | Why | Exit | Do |
|---|---|---|---|
| `loaded 4,407 rows 0 rejected` | all fine | 0 | |
| `duplicate … already loaded in run …` | the same SHA-256, rows still in the lake | 0 | nothing |
| `failed: its .ready file says 999 rows, the file holds 9 documents: not loaded` | `rows:` wrong, or the file is cut short | 1 | resend, or correct the marker; `dataload retry <file>` |
| `failed: the rule endur-*.jsonl.gz takes the business date from the name … carries none … rename it, or give business-date:` | a name with no date | 1 | rename; or `business-date: YYYY-MM-DD` in the marker |
| `failed: 1 of 3 documents rejected (33.33%), more than rejects.max-pct 0.0: nothing was written` (no `rows:` in the marker) | **with the shipped `reject-max-percent: 0` a single rejected row fails the whole file**: nothing is committed | 1 | fix the row and resend; or set `drishti.dataload.reject-max-percent` to the share you accept |
| `failed: its check failed before loading: 1 of 3 documents rejected (33.33%), more than reject-max-percent 0.0 (<reason> (1)): not loaded` | the same rejected-row case when the marker carries `rows:` (the check run before the load says the real cause; a count that differs is added after it) | 1 | fix the row and resend |
| `loaded with rejects: 1 of 3 rows rejected: SOURCE_SYSTEM is ICI, not this load's WSS (1)` | `reject-max-percent` above the share (40 in the test): the good rows are committed, the bad ones are in `rejects` | **2** | fix the rows, resend the date; **do not use the date meanwhile** (the pivot showed WSS 2 of 3) |
| `refused, nothing was loaded: … no pack, kind or table of that name` | a tar folder that is no kind | 1 | 16.1, 16.2 |
| `503 DRS-5218` | the disk guard (10% free **and** 20 GB) | | free disk (17.8) |

Where the alerts show: **Admin → Data loads → Inbox** (Waiting, Loading, Last cycles with *notice:* lines, **Failures** with the reason and **Retry**), `drishti.py dataload status`, the file's
`.loaded.json`/`.failed.json` in `outbox/<day>/<run>/` and `failed/<day>/<run>/`, and `ledger/ledger-<yyyy-MM>.jsonl` (kept for ever).

### 17.8 Automation, verification, housekeeping

**The scheduled job** (tested on Linux: exit 0 on an empty inbox, 1 with the server unreachable; the lock refuses a second copy). The upstream jobs write files into the inbox as they finish; this job runs the cycle at the cut-off and again later:

```bash
#!/usr/bin/env bash
# daily-load.sh: run the data inbox now, wait for it, and turn its exit code into an alert. Scheduled; never run by hand twice at once.
set -u
export DRISHTI_SERVER="${DRISHTI_SERVER:-http://drishti-owner:18480}"       # the owner node's address
TOKEN_FILE="${DRISHTI_TOKEN_FILE:-/etc/drishti/loader.token}"                # a token with the scope loads:write, mode 600
DRISHTI_HOME="${DRISHTI_HOME:-/opt/drishti}"
LOG="${LOG_DIR:-/var/log/drishti}/daily-load-$(date +%F).log"
mkdir -p "$(dirname "$LOG")"
exec 9>"${LOCK_FILE:-/tmp/drishti-daily-load.lock}"; flock -n 9 || { echo "$(date -Is) another daily load is running" >> "$LOG"; exit 3; }

python3 "$DRISHTI_HOME/tools/drishti.py" dataload run --wait --wait-seconds 7200 --token-file "$TOKEN_FILE" >> "$LOG" 2>&1
rc=$?
case $rc in
  0) echo "$(date -Is) ok: every file loaded, no row rejected" >> "$LOG" ;;
  2) alert="WARNING: loaded with REJECTED ROWS" ;;
  *) alert="CRITICAL: a file failed, or the server was not reached" ;;
esac
if [ $rc -ne 0 ]; then
  echo "$(date -Is) $alert (exit $rc): see $LOG and Admin > Data loads > Inbox" >> "$LOG"
  # replace with your pager or mail: mail -s "Drishti daily load: $alert" risk-ops@bank.example < "$LOG"
fi
exit $rc
```

*Linux, cron* (`crontab -e` of the service account): the cut-off at 05:30 on weekdays, a catch-up at 07:30 (a file that arrived after 05:30 loads there; the verification below runs at 06:00 and 08:00):

```cron
30 5,7 * * 1-5  DRISHTI_SERVER=http://drishti-owner:18480 /opt/drishti/ops/daily-load.sh
0 6,8 * * 1-5   DRISHTI_SERVER=http://drishti-owner:18480 /opt/drishti/ops/verify-day.sh $(date +\%F) || mail -s "Drishti: the day is incomplete" risk-ops@bank.example
```

*Linux, systemd timer* (written from the documented syntax; not run here): `daily-load.service` (`Type=oneshot`, `ExecStart=/opt/drishti/ops/daily-load.sh`, `User=drishti`, `EnvironmentFile=/etc/drishti/loader.env`, `OnFailure=drishti-alert@%n.service`)
and `daily-load.timer` (`OnCalendar=Mon..Fri 05:30,07:30`, `Persistent=true`). A non-zero exit marks the unit failed, which is the alert.

*Windows, Task Scheduler* (**not run here**: the PowerShell below uses the same CLI and exit codes; Python and `tools\drishti.py` are the Windows tools, no `.exe` is needed):

```powershell
# C:\drishti\ops\daily-load.ps1
$env:DRISHTI_SERVER = "http://drishti-owner:18480"
$log = "C:\drishti\logs\daily-load-$(Get-Date -Format yyyy-MM-dd).log"
python C:\drishti\tools\drishti.py dataload run --wait --wait-seconds 7200 --token-file C:\drishti\loader.token *>> $log
$rc = $LASTEXITCODE
if ($rc -ne 0) { Send-MailMessage -To risk-ops@bank.example -From drishti@bank.example -Subject "Drishti daily load: exit $rc (2 = rejects, 1 = failed)" -Body (Get-Content $log -Tail 40 | Out-String) -SmtpServer smtp.bank.example }
exit $rc
# register: schtasks /Create /TN "Drishti daily load" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 05:30 /RU drishti /TR "powershell -NoProfile -File C:\drishti\ops\daily-load.ps1"
```

**Exit codes for the alert**: 0 all loaded; **2 loaded with rejected rows** (warning: the date answers without them: fix and resend); **1 a file failed or the server was not reached** (critical). **An empty cycle is also exit 0**
(*done (nothing ready)*): a feed that never arrived is **not** an error of the loader, which is why the verification exists.

**The daily verification** (`verify-day.sh DATE`, run on the owner node, tested: exit 0 for a complete day; for 2026-07-13 it reported ten missing feeds and the WSS rejects, exit 1):

```bash
#!/usr/bin/env bash
# verify-day.sh YYYY-MM-DD: exit 0 only when (1) every expected feed has a loaded line for THAT date in the inbox's ledger, with 0 rejects, and
# (2) VaR answers for that very date with nothing rejected. (A pivot or a VaR page asked for a date with no file answers from the newest earlier date
# of each connector and says so on the page (asOfNotices, asOfNotice); a job reads the ledger.)
set -u
D="${1:?usage: verify-day.sh YYYY-MM-DD}"
export DRISHTI_SERVER="${DRISHTI_SERVER:-http://drishti-owner:18480}"
TOKEN_FILE="${DRISHTI_TOKEN_FILE:-/etc/drishti/loader.token}"
DATA="${DRISHTI_DATA_DIR:-/var/lib/drishti}"                                  # the OWNER node's data folder (the inbox's)
FEEDS="${FEEDS:-book desk legal-entity counterparty endur calypso murex wss swapone ici eod}"   # the first word of each file name expected for the date
bad=0
python3 - "$D" "$DATA/dataload/ledger" $FEEDS <<'PY' || bad=1
import sys, json, glob, collections, re
d, led, feeds = sys.argv[1], sys.argv[2], sys.argv[3:]
rows, rej, seen = collections.defaultdict(int), collections.defaultdict(int), set()
for f in sorted(glob.glob(led + "/ledger-*.jsonl")):
    for line in open(f):
        r = json.loads(line)
        if r.get("outcome") != "loaded" or d not in (r.get("dates") or {}): continue
        feed = re.split(r"[.-]\d{4}-?\d{2}", r["file"])[0].split(".")[0]
        seen.add(feed); rows[feed] += r["dates"][d]["rows"]; rej[feed] += r.get("rejected", 0)
ok = True
for feed in feeds:
    if feed not in seen: print(f"MISSING  {feed}: no file loaded for {d}"); ok = False
    elif rej[feed]:      print(f"REJECTS  {feed}: {rej[feed]} rejected"); ok = False
    else:                print(f"ok       {feed:<14}{rows[feed]:>9} rows loaded for {d} (all loads summed)")
sys.exit(0 if ok else 1)
PY
js=$(python3 "${DRISHTI_HOME:-/opt/drishti}/tools/drishti.py" var show BANK --date "$D" --json --token-file "$TOKEN_FILE" 2>&1) || { echo "VaR failed: $js"; exit 1; }
echo "$js" | python3 -c "
import sys, json
d = json.load(sys.stdin); p = d['provenance']; got = d.get('businessDate')
bad = p.get('rejections', []) + p.get('warnings', [])
print('VaR for', got, '| rejected', p.get('rejected', 0), '| warnings', len(bad), '|', p.get('scenarioWindow', {}).get('note', ''))
for w in bad: print('  ', w)
sys.exit(0 if got == '$D' and p.get('rejected', 0) == 0 and not bad else 1)" || bad=1
exit $bad
```

By hand, the same, with the numbers each check should give (`$T` = `--token-file`):

| Check | Command | Expect |
|---|---|---|
| Rows per date per table | `drishti.py lake status --lake demo` (a lake's name, or its folder) | every table's latest date is today |
| Per system | the Delta-log script of 17.3 for each `trade_<system>`; or `pivot run --source trade --date D --rows SOURCE_SYSTEM --values "count()" $T` (a system with no load for D is shown from its earlier date with a notice per system: 17.5) | the file's rows (`zcat file \| wc -l`) |
| Rejects | `.loaded.json` of each file, `dataload status`, exit code | 0 |
| VaR | `var show BANK --date D --json $T` | the date in the header is D; `provenance.rejected` 0; no warnings; positions = rows sent |
| Explain | `var explain BANK --from D-1 --to D $T` | *the steps add up: yes* |
| One system | `pivot run --source trade --date D --rows SOURCE_SYSTEM --values "count()" --filter SOURCE_SYSTEM=MUREX $T`; `view get trade/<id> --date D $T` | the system's count; the trade opens |
| One position | `var show "POS:<position id>" --date D $T` | the position's own figure |
| Disk | `df -h <data>` | above the guard (below) |

**Housekeeping and disk.**

- **Retention of old dates.** Nothing deletes a date by itself: the shipped lake files keep every date. Set `retention: 260` (business dates; `7y`) per table, or in the maintenance file (`<data>/lakes/lake-maintenance.yaml`, Admin → Data loads → Lakes → Maintain); the risk domain keeps seven years whatever is set (section 2.6).
  The inbox's own copies go after `keep-outbox-days` (30); the ledger stays.
- **Compaction, vacuum.** The loader writes files of the target size, so compaction is rarely needed. A replaced or undone date leaves its old files until vacuum (`maintenance.vacuum: 7d`, `time-travel: 30d` in the lake file: budget a second copy of any date loaded twice; the hot file of a restated date likewise: 26 MB beside 16 MB in the test).
  `drishti.py lake maintain --once --tables risk/scenario-pv` runs it; **not run here: it needs the `deltalake`, `pyarrow` and `pyyaml` Python libraries, which this machine lacks** (OPERATIONS_GUIDE section 16).
- **Disk per business date at 1.3M positions** (16.1's estimate; this test's measured 5,000 × 250: 1.3 KB a position in the lake, 3.2 KB a position of hot file with its node aggregates): about **1.75 GB lake plus 2.7 to 4 GB hot files plus the gz and its inbox and outbox copies (1.3 GB each)** a day: **8 to 10 GB a day** with USD only,
  of which the lake and hot file stay. Trades: 1.1 to 2.5 KB a trade in the lake. **The guard**: a build that would leave less than 10% of the disk free (`drishti.disk.min-free-percent`) **or** 20 GB (`min-free-gb`) is refused `503 DRS-5218`: plan for the guard **above** the day's data, and put `<data>` on a volume with room for 30 days of outbox.
- **Monitoring.** The scheduled job's exit code and log; `verify-day.sh` at the cut-off; Admin → Health (a pack reading two lakes is DEGRADED, section 6); Admin → Data loads → Inbox *Failures*; `dataload status` in a dashboard.

### 17.9 One-page checklist (print it)

```text
DAILY LOAD      business date: __________   owner node: __________   operator: __________
BEFORE (once)   [ ] source modules on   [ ] connector files in config/connectors (risk-store, var-*, trade-*)   [ ] inbox on, one owner   [ ] token loads:write in a 600 file
                [ ] disk above the guard (10% and 20 GB) and 10 GB a day free   [ ] reject-max-percent decided (0 = any bad row fails the file)
FILES, per day  portfolio: book, desk, legal-entity, counterparty   <kind>.<date>.jsonl.gz   FULL list, every entity
                trades: endur, calypso, murex, wss, swapone, ici   <system>-<date>.jsonl.gz   one system a file, its own rows only
                PVs: eod-<date>.tar = scenario-set + fx-scenario + scenario-pv parts   ALL pricing systems, one BATCH_ID
                every file: .ready LAST with sha256 and rows
ORDER           1 portfolio   2 trades (any order, each system when it arrives)   3 the PV tar LAST (signals VaR and the official capture)   4 netting sets
LOAD            [ ] drop, marker last   [ ] dataload run --wait   exit 0 / 2 rejects (fix, resend) / 1 failed (read the reason, retry)
CORRECT         resend a system's whole date (replace) · extra rows: mode: append in ITS .ready · wrong load: dataload undo <id> --yes, then wait 1 minute
NEVER           a partial portfolio or PV file for a date · an extra file without mode: append · two systems in a trade file · a name without a date
VERIFY          [ ] verify-day.sh <date> exit 0   [ ] var show BANK --date D: header date = D, rejected 0, positions = rows sent
                [ ] var explain D-1 -> D adds up   [ ] trades per system = file rows   [ ] failures list empty   [ ] disk above the guard
MISSED A FEED?  the pages still answer from the newest earlier date: say it to the desks; load the feed when it arrives (next cycle); nothing else changes
SIGNED          ____________________   time: ________
```

### 17.10 What was measured

A server on a scratch data folder (Java 21, 3 GB heap, the inbox's loader 2 GB), the banking, trading and source modules, 5,000 positions × 250 scenarios a date in two gzip parts (`tools/pvgen/bulk_positions.py`), the six systems' synthetic trade files of `data/testdata/trades` (13,400 trades on the first date, 600 to 700 on the next two), the core pack's reference files of the same three dates; three business dates plus the cases of 17.4 to 17.7.
One cycle: 45 to 50 s (30 s of that the stability wait); the tar of three tables 11 s; every `VaR show` 0.2 to 1 s. **Not tested here**: cron and systemd timer runs, the Windows task, `lake maintain`, a cluster (the inbox runs on its owner node), the official capture (off), Iceberg, netting sets (16.3 has their runbook), production volumes (use 16.1 and 16.2's estimates for the first real day).
