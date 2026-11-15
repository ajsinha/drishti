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

# A Polars engine for the lake loader (design, not built)

**Status:** design only, 2026-11-15. No product code exists for it. Requested by the product owner: keep Polars at hand as a lake
loader option, "a delta loader using polars ... should follow exact same behavior that our current loaders have".

**What this document is.** (1) The loader's behaviour written down as one contract every engine must meet, with where each rule
lives in code today; (2) how a Polars engine meets each rule, and where it cannot; (3) the proof that engines agree; (4) what to
expect for speed and how to measure it; (5) how the engine is chosen, packaged and run; (6) the build plan, the risks and the
questions for the owner.

**Where the facts live.** What a person who loads data needs is [DATA_LAKE_GUIDE.md](../guides/DATA_LAKE_GUIDE.md) (sections 2 to 5);
how the Java loader is built is [DATA_ARCHITECTURE.md section 10](DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-drishti-lakeload);
the inbox is [section 7](DATA_ARCHITECTURE.md#7-loading-the-data-inbox-and-the-load-rules-internal). This document links to them and
does not repeat their text. When the engine is built, section 2 here (the contract) moves into DATA_ARCHITECTURE.md section 7 as the
loader's specification and this file is deleted.

## 1. The answer in short

1. **Polars is an engine, never a second loader.** One front (`tools/lake/build.py`: the plan, the lake file, the names and dates,
   the lock, the run record) and one contract between the front and an engine: *a load configuration in, a summary and an exit
   code out*. The Java loader already sits behind exactly that (`tools/lake/javaload.py` writes `loader.json`, `LakeLoad` writes
   `loader-summary.json`). The Polars engine takes the same configuration document and writes the same summary.
2. **The contract is what the Java loader does today.** The pure-Python path (`stage.py` + `writers.py`) already differs from it in
   nine places (section 2.4). A Polars engine built to "what the Python path does" would copy those differences.
3. **The row rule stays one piece of Python.** Rejects, ids, flattening, vector markers, the document text and column values come
   from one function shared by every Python engine; Polars does the columnar half: the vectors, duplicates, the sort, the
   partitions and the Parquet and Delta write through delta-rs.
4. **A probe on 5,000 synthetic rows (section 9) changed the expectation.** Python's own JSON parser is not what makes the Python
   path slow (30,000 rows a second on one thread for the whole row rule on 250-number vectors); its staging design is (every row
   written again as JSON, read again, one thread, then handed to a JVM writer). Polars cannot write the document text the contract
   fixes (it prints `1e-05` as `0.00001`, and cannot tell a missing field from a null one), so the document stays with Python's
   encoder. The honest expectation: **3 to 6 times the present Python path, 0.4 to 0.8 times the Java loader** on vector tables.
5. **Delta on local disk only, in the first version.** Iceberg tables of the path-based layout cannot be committed by pyiceberg;
   S3 targets, the in-memory lake and the data inbox stay with the Java loader. Asked for anything else, the engine refuses before
   it reads a row and names `--engine java`.
6. **It is the better choice in two cases**: a machine with Python and no Java, and many small loads where the JVM's start is most
   of the time. Everywhere else the Java loader stays the primary path.

## 2. The loader contract

An **engine** loads the files of one table (one pack, kind and connector) into one table of a lake. The rules below are the
specification; an engine meets all of them or refuses the load before writing. *Java* is the code under
`lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/` unless a package is named; *Python* is `tools/lake/`.

### 2.1 Before a row is read (shared by every engine: the front)

| # | Rule | Java | Python |
|---|---|---|---|
| C1 | **Input forms.** JSON Lines (`.jsonl`, `.ndjson`, `.json`), plain, `.gz` (every member of a multi-member gzip), `.zst`, `.zip`; tars (`.tar`, `.tar.gz`, `.tgz`). The format is sniffed from the first bytes, not the name. Loader rows and lake rows are refused with the tool that loads them | `Inputs`, `SourceReader`, `GzipMembers`, `Archives`, `TarStream`; `dataload.RowForms` | `jsonstream.py`, `archives.py` |
| C2 | **Archive guards.** Size, member count and expansion ratio limits; path slips, links, nested archives and unknown tables refuse the archive, every problem listed. Fixture: `tools/lake/testdata/archives/expected.json` | `Archives.Limits`, `Archives.plan` | `archives.Limits`, `archives.plan` |
| C3 | **Names.** `<table>[.<date>][.part-<n>].<extension>`; the date is `YYYY-MM-DD` or `YYYYMMDD`, a real date, not in the future; a name with a bad date part is refused whatever else is given. Fixture: `tools/lake/testdata/file-dates/names.json` | `drishti-common` `FileDates` | `filedate.py` |
| C4 | **A tar is `<table>/<YYYY-MM-DD>/`.** Its dates are its folders' and cannot be overridden; tables load in name order; a table must be a pack's; a failure part-way says which tables were committed (the inbox undoes them: all or nothing) | `Archives`, `DatasetLoads`; `dataload.Cycle`, `LakeUndo.undoAll` | `build._run_tars`, `filedate.tar_layout`, `feedroute.py` |
| C5 | **The `.ready` file.** Its keys, an unknown key refused; `sha256` checked against the file; `rows` checked by a dry run of every table before anything is written (the inbox) | `dataload.ReadyNote`, `ReadyChecks`, `Cycle` | `feedroute.read_marker` (keys, `sha256`) |
| C6 | **The table and its layout come from the lake file** (`config/lakes/*.yaml`): location, format, `partition-by`, `promote`, `vectors`, `flatten`, `id`, `date`, `latest-by`, `load-mode`, `sort-by`, the file layout. One reader. A command line that disagrees is refused naming both; a kind the lake file has no entry for is refused | `drishti-lakeio` package `lakeconfig`; `LoadConfig` | `lakeconfig.py` (runs the Java reader), `build.lake_layout` |
| C7 | **The mode.** `--mode` or the `.ready` file's, else the table's `load-mode`, else replace. Iceberg: replace-date or append | `LoadConfig` | `build.plan`, `build.lake_layout` |
| C8 | **Locks.** Shared on the lake, exclusive on `_drishti-lake/locks/<domain>/<table>.lock` (an OS lock and a POSIX lock on the same byte, so Java and Python exclude each other); on Windows the whole lake; a table held by another run is exit 3 | `LakeLoad` (`--no-lock` when the front holds it) | `build.LakeLock` |

### 2.2 Rows (the row rule)

| # | Rule | Java | Python |
|---|---|---|---|
| C9 | **The business date of a file**, first match: `--as-of`; the `.ready` file's `business-date`; the name's date; the rows' own date field when every row carries the same one (source `rows`); the business today only for a kind without a date field or an append of rows without one. In a replace, a file with no date given whose rows carry none or several is refused, and a file named for one date whose rows all carry another is refused: nothing written. **Never today over the rows** | `Loader.checkRowDates`, `ParseWorker`, `RowParser` | `filedate.decide`, `stage.Stager.file` (see 2.4) |
| C10 | **A row whose own date differs** from its file's is loaded under the file's date and counted (`otherDateRows`, `dateNotes`, `fileDates`) | `LoadReport` | `stage.Stager.date_report` |
| C11 | **The id.** Missing, null or empty: rejected. An object or a list: rejected. A number: its exact text (`1000008`, never `1000008.0`), and the document carries it as text too | `RowParser` | `stage.Stager.add`, `stage.id_text` |
| C12 | **Constants.** The connector's `set:` values are stored in a row that lacks the field | `RowParser`, `LoadConfig.Source.set` | `stage.Stager.add` |
| C13 | **Flatten.** `NAME: PATH[0].FIELD` becomes a field of the document (never replacing one it has) and a promoted column | `RowParser` | `stage.get_indexed` |
| C14 | **Vectors.** A list of numbers becomes a `list<double>` column and leaves the document, which keeps `{"$vector": "<column>"}`; a null element stays null; anything else is a reject; a dotted path leaves the document as it is | `RowParser`, `RowPlan` | `stage.Stager.add` |
| C15 | **Partition values** are text: a boolean `true`/`false`, a number as Python prints it, an object or list as compact sorted JSON, missing and null as null; an empty value is null in the table | `RowParser.scalarText` | `stage.scalar_text` |
| C16 | **Promoted columns.** `double` when every value present is a number and the field is not an id (`ID`, `_ID`, `_REF`, `_KEY` and their plurals, and the key); `array<string>` when every value is a list of simple values (a number in its plain text, a null element left out); else `string` (an object or list as its JSON text). An existing table keeps its column types and its partitioning; another partitioning is refused; a column the table lacks is added (Iceberg) or left out and reported (Delta) | `RowPlan`, `TableTarget`, `DeltaTarget` | `stage.Stager.types`, `writers.check_layout`, `writers._cell` |
| C17 | **Every row carries** `_load_id` (`<run>.<n>`, one per data file, an archive's members sharing theirs) and `_source_file` | `Loader` (load ids) | `stage.Stager.file` |
| C18 | **The document text** is the document written again as Python's `json.dumps(ensure_ascii=False, separators=(",", ":"))` writes it, a float as Python's `repr`; key order kept, duplicate keys kept (Java) | `PyJson` | `json.dumps` in `stage.Stager.add` |
| C19 | **Rejects.** Each has a reason in fixed words and a place (`file:line`); all are counted by reason, the first 50 kept, all written to the rejects file. A load with rejects is exit 2. More than `rejects.max-pct` of the documents rejected, or every one: nothing written, exit 1. A row of another slice of a shared table is a reject | `RowParser`, `LoadReport`, `Loader` | `stage.Stats.reject` |
| C20 | **Duplicates.** Of one id's copies on one business date, the copy with the latest `latest-by` timestamp is kept (ISO-8601, no offset is UTC), else the last read in one order (files by path, then line); a copy with a timestamp beats one without. The others are dropped, counted per date and the first ten named. Exact at any size | `Latest`, `LoadReport` | `stage.Stager.resolve`, `stage.instant_micros` |
| C21 | **Child kinds** (`ingest: {child-of}`) are split off the parent's documents and loaded in the same run under the parent's date. Fixture: `lake/drishti-lakeload/src/test/resources/childkinds` | `ChildLoads` | `childload.py`, `tools/packgen/childkinds.py` |

### 2.3 The table, the commits and the record

| # | Rule | Java | Python |
|---|---|---|---|
| C22 | **Columns.** `id` and `doc` (text), `business_date` (date), the partition columns (text), the promoted columns, the vector columns; partitioned by `business_date` first, always, then at most four columns whose names can be folder names | `TableTarget`, `IcebergLayout` | `writers.Table`, `build.check_partition_names` |
| C23 | **Replace.** A load replaces each business date it holds and no other; an empty file touches nothing. **A shared table**: a connector whose `set:` constant is a partition column (Delta) or a promoted column (Iceberg) replaces only its own slice of the date (`SOURCE_SYSTEM` of `trading.trades_shared`); a shared table that cannot be split refuses a replace | `DeltaTarget` (`LakeWriter.removals` with `TableTarget.inSlice`), `IcebergTarget`, `LoadConfig.withSlice` | `writers.write_deltalake` (the date only; see 2.4) |
| C24 | **Append.** Adds rows; with a batch id each date's commit records it (Delta: a transaction `drishti-batch:<batch>:<date>`, version 1; Iceberg: the snapshot summary's `drishti.batch`) and a batch already committed for a date is skipped | `DeltaTarget`, `IcebergTarget` | not in the Python path |
| C25 | **One commit per business date**, the dates one after another, each visible when committed and whole; a failure leaves every date not yet committed as it was (exit 1) | `Loader`, `DeltaTarget`, `IcebergTarget` | see 2.4 |
| C26 | **Delta protocol.** Reader 1, writer 2, no table features; partition folders and `add.path` exactly as the protocol and Spark write them; a checkpoint every `delta.checkpointInterval` commits; a new table of a kept domain gets its retention properties and `delta.dataSkippingStatsColumns`. [DATA_LAKE_GUIDE.md section 14](../guides/DATA_LAKE_GUIDE.md#14-delta-protocol-compliance) | Delta Kernel through `LakeWriter` | delta-rs in `writers.write_deltalake` |
| C27 | **File layout.** Rows of a date in `sort-by` order (default `LEGAL_ENTITY`, `DESK`, `id`), every file a contiguous key range; `file-size-mb`, row groups, `compression`; vector columns BYTE_STREAM_SPLIT with zstd, no dictionary, no statistics; no statistics for `doc`; `shard-aligned` cuts; the load manifest | `Runs`, `LakeWriter`, `ParquetDataWriter` | `writers._chunks` (see 2.4) |
| C28 | **Memory.** `memory.budget-mb` bounds the load whatever the input's size; what does not fit is sorted and spilled under `memory.spill-dir`; the spill space is estimated and checked before reading; the summary reports the peak and the MB spilled | `Runs`, `Loader` | staged files (flat, no budget setting) |
| C29 | **The run record.** A run id `yyyyMMddTHHmmss.SSS-<pid>`; one line in `<lake>/_drishti-lake/runs.log`; `runs/<run>.json` and `last-run.json`; `--summary FILE`; the progress file; the table recorded as Drishti's to maintain. The summary's keys are one list (`javaload.MERGED` and `LoadReport`) | `LoadReport`, `LakeLoad` | `build._finish`, `ownership.py` |
| C30 | **Exit codes.** 0 loaded; 2 loaded with rejects (or the signal failed); 1 failed; 3 locked | `LakeLoad` | `build.py` |
| C31 | **Dry run and plan.** `--plan` says the table, date and mode of each file and reads nothing; `--dry-run` reads and counts everything and writes nothing | `report.dry-run` | `build._plan_only`, `build._go` |

**Rules of the inbox, not of an engine** (they stay in Java, `dataload`): routing by `config/dataload.yaml`, `hold`, new tables, a
file skipped as a duplicate only while the lake still holds its rows (`Ledger`, `Cycle`, `LakeUndo`), crash recovery, undo. They
rely on three things every engine must give: `_load_id` on every row, the summary's `loads`, and a dry run whose counts are exact.

### 2.4 Where the pure-Python path differs from the contract today

Read from the code on `wip/i4` (2026-11-15). These are what "the same behaviour as our current loaders" must not copy.

| Rule | The Python path (`stage.py`, `writers.py`) does |
|---|---|
| C9 | a file with no date given or in its name loads under the business today (`filedate.decide`); the rows' own date is only counted; none of the three refusals |
| C19 | no `rejects.max-pct`; no slice reject |
| C20 | duplicates are watched for the first 5,000,000 ids only (`DUPLICATE_WATCH`) |
| C23 | replaces the whole date of a shared table (the predicate is the date alone) |
| C24 | no batch id |
| C25 | `--engine deltalake`: one commit per date **and batch of rows**; `--engine drishti`: one commit for the whole table, every date together |
| C27 | rows are sorted by `id` only, and only within a part that fits one batch; `sort-by`, `shard-aligned`, vector encodings and the load manifest are not applied by `--engine deltalake` |
| C28 | no budget setting; memory is flat because every row goes through a staged file |
| C1 | `s3://` inputs are the Java loader's only |

It also has a mode the Java loader has not: `overwrite-partitions` (replace one partition of a date). `tools/lake/demo.py` used it
for shared tables before the slice rule; see question Q2.

## 3. The Polars engine

### 3.1 Shape

```
drishti.py lake build --engine polars            (the front: unchanged, shared by every engine)
  plan · lake file · names and dates · archives plan · .ready · LakeLock · run record
        │  load configuration (the document javaload.config builds)          ▲ summary (LoadReport's keys) + exit code
        ▼                                                                    │
  tools/lake/polarsload.py
  ┌── parse: N worker processes, one input split each ──┐   ┌── per business date, in the main process ──┐
  │ lines → the row rule (stage.row: one function)      │   │ Polars: duplicates (C20) → sort-by (C27)   │
  │ → Arrow chunks in the run's staging folder:         │ ─►│ → one RecordBatchReader → delta-rs         │
  │   id, doc, date, place, latest-by instant,          │   │   write_deltalake: ONE commit (C25),       │
  │   partition values, promoted cells, vectors         │   │   replace predicate with the slice (C23)   │
  └─────────────────────────────────────────────────────┘   └────────────────────────────────────────────┘
```

- **Splits** as the Java loader's: each file one split, a large plain file cut into byte ranges that own the lines starting in
  them; a compressed file is one split (one worker). Archive members are opened by the shared `archives.Archive`.
- **Workers write, they do not send.** Each worker reads its own split and writes Arrow IPC chunk files of a bounded size into the
  staging folder; nothing large crosses a process boundary. (In the probe, sending lines to four workers and the documents back
  gave only 1.6 times one thread.)
- **The main process** scans a date's chunks with Polars, resolves duplicates, sorts and hands delta-rs one stream per date.

### 3.2 What is shared and what is the engine's

| Shared (one definition, every engine or every Python engine) | The Polars engine's own |
|---|---|
| The front: C1 to C8, C29 to C31 (`build.py`, `feedroute.py`, `filedate.py`, `archives.py`, `lakeconfig.py`) | Splits and worker processes |
| The load configuration and the summary's keys (C29) | Arrow chunk files in staging |
| **The row rule** C9 to C19 as one pure function `stage.row(document, plan, file) → row or reject` (taken out of `Stager.add`, brought up to the contract first: section 7, step P1). The fixed words of every reject live there only | Duplicates, sort, partitions in Polars |
| The document encoder (C18): Python's `json` | The Delta write through delta-rs |
| The timestamp reader of `latest-by` (`stage.instant_micros`) | Memory accounting (3.5) |
| Child kinds (C21): `tools/packgen/childkinds.py` | |

A guard test holds the line: `polarsload.py` contains no reject wording and no date rule, and imports both.

### 3.3 Rule by rule

| Rules | Polars engine | How, or why not |
|---|---|---|
| C1 inputs | **meets**, except `s3://` | plain, `.gz` and `.zst` through Python's readers in the workers (`pl.scan_lines` read the probe's `.gz` itself; `.zst` and multi-member gzip are to be proven in P2). `--from s3://` is refused: the Java loader's |
| C2 to C8 | **meets** | the front's code, unchanged |
| C9 to C17, C19 | **meets** | the shared row rule; the file-level date checks (C9) run after the parse and before the first write, as `Loader.checkRowDates` |
| C18 document text | **meets, by not using Polars for it** | Polars' JSON writer prints `1e-05` as `0.00001`, gives a field that was missing as `null`, and has no option for either (probe). The document is written by Python's encoder in the workers |
| C14 vectors | **meets** | 1,250,000 doubles of the probe decoded by Polars were bit-equal to Python's; nulls inside a vector survive the Delta round trip. Polars refuses a whole chunk when one vector holds a text, so the worker's row rule decides the reject first |
| C20 duplicates | **meets** | exact, per date: sort by (has a timestamp, timestamp, place), keep the last of each id; counts and examples from the same frame |
| C21 child kinds | **meets** | the front builds them after the parent, as for the Python path today |
| C22 columns | **meets** | probe: `string`, `date`, `double`, `array<double>` in the Delta schema, as the Java loader writes |
| C23 replace and slice | **meets** (Delta) | probe: `mode="overwrite"` with `business_date = '…' AND SOURCE_SYSTEM = 'WSS'` made one commit that removed only the WSS files and left the other system's rows |
| C24 append and batch | **meets, to be proven** | probe: delta-rs wrote the `txn` action (`drishti-batch:b1:2026-11-12`, version 1) with the commit. Reading an existing batch back to skip a date is not yet exercised (P3) |
| C25 one commit a date | **meets** | one `write_deltalake` call per date, fed by a stream, so memory does not hold the date |
| C26 protocol | **meets, to be proven by interop** | probe: a table it creates is reader 1, writer 2, no features. Folders and `add.path` are delta-rs's own; `test_delta_interop.py` already holds delta-rs to the escaping list |
| C27 sort and file size | **meets** `sort-by`, `file-size-mb`, row-group rows, `compression`, no statistics for `doc` | Polars sorts; delta-rs cuts files by size |
| C27 vector encoding | **cannot today** | delta-rs 1.6.6 accepted a per-column BYTE_STREAM_SPLIT, no-dictionary, no-statistics setting for the vector column and did not apply it (the file had PLAIN and dictionary pages, Snappy, statistics; tried under three column names). Tables read the same and are larger. pyarrow's own Parquet writer does apply it, but committing files written outside delta-rs means Drishti's own code for folders and statistics, which the standard-libraries rule rules out |
| C27 `shard-aligned`, row-group MB | **cannot** | delta-rs decides where a file ends; files are still contiguous key ranges because the stream is sorted |
| C27 load manifest | **meets** | from the commit's add actions and their statistics |
| C28 memory | **partly** | section 3.5 |
| C29 to C31 | **meets** | the front's; the summary's `engine` is `polars-loader`, with `throughput` per stage and `memory` (budget, peak resident memory of the process tree, MB staged) |

### 3.4 What it does for each store

| Store | Polars engine | Why |
|---|---|---|
| **Delta, local disk** | yes | delta-rs is an official Delta library; rules C22 to C27 above |
| **Delta, `s3://`** | no, refused | delta-rs can write S3, but safe concurrent commits there need its own locking set-up, which Drishti has not tested; the Java loader commits with a conditional PUT |
| **Iceberg, path-based table** (`<lake>/<domain>/<table>`, Drishti's `PathCatalog`) | no, refused | Polars writes Iceberg through pyiceberg, and pyiceberg commits only through a catalog (REST, Glue, SQL, Hive); it reads a path-based table but cannot commit to one |
| **Iceberg, REST catalog** | not in the first version | pyiceberg can replace a date in one snapshot and carry snapshot properties (`drishti.batch`); not probed. It would be a second Iceberg committer beside `drishti-iceberg`, with its own field ids, sort order and metrics settings to hold equal |
| **Files store** | not a target of `lake build` or `LakeLoad` at all | the contract has no files target today (`data ingest --store files` is another tool). Storage parity for loading is a question about the contract, not about Polars (Q5) |
| **In-memory lake** (`mem://`) | no | lives in the server's JVM |

Iceberg with a JVM at hand is `--engine java`. Passing the Polars engine's sorted rows to Drishti's Iceberg loader in the jar (as
`icebergwrite.py` does for the Python path) would need the JVM the Polars engine exists to avoid, so it is not proposed.

### 3.5 Memory

Polars has no setting that caps its memory. The budget (C28) is kept by construction and reported, not enforced by an allocator:

- workers: `parallel.parsers` processes, each holding one chunk (`budget × 55 % / parsers`, as the Java sort buffers) before it is
  written to staging; staging is the spill, checked for free space before reading with the Java loader's estimate;
- per date: when the date's staged bytes fit the writers' share it is sorted in memory; when they do not, the sort runs on Polars'
  streaming engine over the chunk files. **Whether that sort stays inside a budget is not proven** (P4 measures it under a hard
  process limit). Until it is, a date larger than the share is refused before anything is written, naming `--engine java`;
- the summary reports the peak resident memory of the whole process tree, as BENCHMARK.md section 7 measures both paths.

## 4. Proof of parity

### 4.1 One fixture, every engine

`lake/drishti-lakeload/src/test/resources/loader-contract/cases.yaml`, in the manner of `pack-resolution/cases.yaml`: read by a
Java test (`LoaderContractTest`, `LakeLoad` in process) and a Python test (`tools/lake/test_loader_contract.py`, through
`drishti.py lake build`, once per engine: `java`, `polars`, and the Python path while it exists).

A case: `files` (name → lines, or a small generator: n rows, a vector length, duplicates), `ready`, `lake` (the lake file's entry),
`pack` (key, date, `set:`, shared tables), `run` (mode, `--as-of`, batch id, settings), `before` (earlier loads that set the table
up) and `expect`:

| Expected | Compared how |
|---|---|
| exit code, status, an error fragment | exact |
| rows per table, date and partition | exact counts, and the ids of each |
| every row: `id`, `doc` byte for byte, each column's value and type, each vector bit for bit, `_source_file` | one dump of the table, read by **one reader for every engine** (Delta Kernel: the test module's `TableDump`, `deltaread.py`), so a reader's own behaviour hides nothing |
| rejects: count by reason in the fixed words, the first places | exact |
| duplicates per date and the named examples | exact |
| `fileDates` (date and source: given, ready, name, folder, rows, today), `otherDateRows` | exact |
| commits: how many, one per date; what each removed and added by partition; the `txn` of an append | read from `_delta_log` |
| protocol and table properties of a new table | exact |
| file layout: each file a contiguous range in `sort-by` order | by the files' statistics; encodings only where the engine claims them (3.3) |

Then, beyond the expectations, **engine against engine**: for every case the dumps of all engines are equal.

| Group | Cases (about 60 in all) | Rules |
|---|---|---|
| names and dates | each line of the name table of DATA_LAKE_GUIDE.md 5.2; the three refusals of a replace; an append without dates; `date-from: name` | C3, C9, C10 |
| archives | zip, tar, tar.gz of several tables and dates; each guard | C1, C2, C4 |
| ids and rows | number ids, float ids, leading zeros, empty, object; `set:`; flatten; non-ASCII, escapes, `1e-05`, 20-digit integers, duplicate keys | C11 to C13, C18 |
| vectors | nulls inside, an empty list, a text inside, a dotted path, 1 and 509 values | C14 |
| columns | all numbers, mixed, an id-named field, lists of simple values, lists of objects, a column null on every row; an existing table of another type or partitioning | C15, C16, C22 |
| rejects | each reason; exactly at and above `max-pct`; every row rejected | C19, C30 |
| duplicates | timestamps, ties, none, across files and parts, with and without offsets | C20 |
| replace | a date reloaded; a date not in the input untouched; an empty file; six systems into `trades_shared`, one reloaded; a row of another slice; a table that cannot be split | C23 |
| append | two batches; the same batch again; a retry after one of two dates | C24 |
| commits | 1, 2 and 60 dates | C25 |
| child kinds | the existing `childkinds` fixture, run per engine | C21 |
| record | summary keys, the run log line, a locked table, dry run, plan | C8, C29 to C31 |

An engine that does not support a case's target (Iceberg, S3 for Polars) must **refuse it with exit 1 before writing**; the fixture
expects that refusal, so an engine can never quietly do something else.

### 4.2 Delta interop, both ways

`tools/lake/test_delta_interop.py` gains the Polars engine as a writer, over its list of partition values (spaces, `%`, `:`, `/`,
`=`, quotes, non-ASCII, empty, null):

1. Polars engine writes → Delta Kernel (the server's reader), delta-rs and `pyarrow.dataset` read: every row and partition value equal.
2. The Java loader creates a table and loads date 1 → the Polars engine replaces date 1's slice and appends date 2 → every reader
   reads all of it; the protocol is still reader 1, writer 2 with no features; the checkpoint each side wrote is read by the other.
3. The Polars engine creates → the Java loader replaces a date → all readers.
4. `lake undo` (Java, `LakeUndo`) of a Polars load restores the date; `lake maintain` compacts a table both engines wrote.
5. The Spark escaping oracle for partition folders, as for the other writers.

### 4.3 VaR is unaffected

The VaR goldens' inputs are loaded with the Polars engine into a scratch lake and the golden check is run against it: every figure
equal. The fixture already proves the vectors bit for bit; this proves nothing between the lake and VaR depends on the file layout
that differs (3.3). It runs once per release that touches the engine, not in every drill.

## 5. Performance

### 5.1 What is known

| Fact | Source |
|---|---|
| Python path, 200,000 positions × 509, two dates: 68.4 s (5,800 rows/s), peak 2.7 GB; Java loader: 7.2 s, peak 4.0 GB; 1,000,000: 20.0 s | [BENCHMARK.md section 7.1](../admin/BENCHMARK.md#7-the-loaders) |
| Java loader per thread, warm: decompress 124 MB/s, parse 30,000 rows/s, write 24,500 rows/s; the first load in a JVM 1.3 s | BENCHMARK.md section 7.2 |
| The whole row rule for a 250-number row in Python, one thread: 30,000 rows/s; Polars decoding the same vectors and six fields: 0.13 s for 5,020 rows on its thread pool; reading the 26 MB `.gz` into lines: 0.14 s | the probe (section 9): 5,000 rows, not a benchmark |

### 5.2 What to expect (estimates, to be replaced by section 5.3's numbers)

- **Parse**: about 15,000 to 20,000 rows/s per worker at 509 values (Python's parser and encoder); the Java loader's 30,000 per
  thread stays ahead, and one large `.gz` is one worker where Java gives it every parser.
- **Sort and write**: Polars and delta-rs (Rust) should match or beat the Java write stage per core.
- **End to end**, vector tables on Linux: 3 to 6 times the present Python path, 0.4 to 0.8 times the Java loader. Trade documents
  (no vectors, deep nesting): closer to the Python parser's speed times the workers; less gain.
- **Table size**: larger than the Java loader's on vector tables until the vector encoding is applied (3.3). The Python path's
  table was 831 MB against 571 MB.
- **Small loads**: no JVM start; a kind of a few thousand rows should load in well under the Java loader's 1.6 s.
- **Windows**: process start is slower and the lock is the whole lake; expect a smaller gain from workers.

### 5.3 The benchmark

Same inputs for both engines, never more than **200,000 positions × 250 values × 2 dates**, one run at a time, never during a drill.

| | |
|---|---|
| Data | `vargen.py` scenario PVs at 10,000, 50,000 and 200,000 positions × 250 values × 2 dates, 20 gzip files a date; `trades.py` 20,000 trades a day × 3 systems × 2 dates into separate tables and into `trades_shared` |
| Engines | `--engine java`, `--engine polars`; the Python path's 7.1 figures as the baseline |
| Settings | 4, 8 and 24 parsers; `memory.budget-mb` 1,024 and 4,096; a cold and a warm run each |
| Measured | wall time and rows/s per stage; peak resident memory of the process tree; MB spilled or staged; table bytes and file count; commits; then **the reader's side**: the VaR first open and one desk's page on each table (the layout differs, so the read may) |
| Machines | the Linux build machine (24 threads); the Windows QA laptop at 10,000 and 50,000 only |
| Recorded | [BENCHMARK.md section 7](../admin/BENCHMARK.md#7-the-loaders) (the one benchmark document) and `tools/bench/results/<release>-loaders.jsonl`; `var_bench.py load --engine polars` |

### 5.4 When Polars is the better choice

| Use the Polars engine when | Use the Java loader when |
|---|---|
| the machine has Python and **no Java 21 or no server jar** (an analyst's workstation, a notebook host) and the table's layout is given by a pack or the options | always for the data inbox, Iceberg, `s3://`, the in-memory lake |
| many **small loads** (reference kinds, limits, scenario sets), where the JVM's start is most of the time | loads above about 1 GB of JSON, or any date larger than the memory share |
| the benchmark shows it within 20 % of the Java loader for the table's shape at the same memory, **and** the reader's side is no slower | whenever table size on disk matters (vectors) |

One limit on the first row: `--lake NAME` is resolved by the one reader of lake files, which is Java (`lakeconfig.py` runs
`drishti-lakeio`). Without Java the engine works with `--lake DIR` and a pack, or with a load file whose layout was resolved on a
machine that has Java (a small addition to `--plan`: it prints the resolved load file). A Python reader of lake files would be a second reader and is not proposed.

## 6. Choosing, packaging and running it

### 6.1 The choice

- `drishti.py lake build --engine polars`. `--loader-set` takes the Java loader's keys where they mean the same
  (`memory.budget-mb`, `memory.spill-dir`, `parallel.parsers`, `rejects.max-pct`, `target.layout.sort-by`, `file-size-mb`); a key the
  engine cannot honour (`parallel.writers`, `shard-aligned`, `vector-compression`) is **refused by name**, never ignored.
- **`auto` never picks Polars** in the first version. A site default is one setting, `drishti.lake.build.engine` (`auto`, `java`,
  `polars`), listed in CONFIGURATION.md by its generator; the command line wins.
- **No silent fallback.** `--engine polars` without the packages: exit 1 with the install line. A target or input it does not
  support: exit 1 before a row is read, naming `--engine java`. An engine is never changed in the middle of a run.
- The data inbox does not take the engine: the server always has its own JVM, and one loader in the server is the rule.
- The summary and the run log say `engine=polars-loader` and why.

### 6.2 Packaging

- **Optional.** `drishti-console/requirements.txt` already carries `pyarrow` and `deltalake`; Polars goes into a second file,
  `drishti-console/requirements-polars.txt` (`polars`, pinned), with exact versions and hashes in a lock beside
  `config/compute/locks/python.lock`. `drishti.py doctor` reports it as optional (`lake build --engine polars`).
- **Not vendored in git.** The three wheels are tens of megabytes of binaries per platform and Python version. For a machine with
  no network, the release carries a wheel folder built with `pip download --only-binary=:all: --platform win_amd64
  --python-version 3.13 -r requirements-polars.txt -d wheels` and installed with `pip install --no-index --find-links wheels`.
- Versions probed: Polars 2.1.0, delta-rs 1.6.6, pyarrow 26.0.1, Python 3.13, Linux.

### 6.3 Windows without any `.exe`

**The wheels qualify, as far as could be checked from Linux.** Listing the installed files: `polars` 2.1.0 is pure Python and
depends on `polars-runtime-32`, which holds one native library; `deltalake` 1.6.6 holds one; `pyarrow` 26.0.1 holds 38. None holds
an `.exe` and none installs a command into `Scripts\`. On Windows the native libraries are `.pyd` and `.dll` files loaded by
`python.exe`, as the DLLs the server unpacks are loaded by `java.exe`. `pyarrow` and `deltalake` are already on the QA laptop for
the console, so the mechanism is proven there for two of the three.

Not checked, and what could still stop it: (a) the Windows wheels themselves were not listed; (b) a policy that blocks unsigned
DLLs loaded from a user's folder would block Polars as it would have blocked pyarrow; (c) the default Polars runtime needs a CPU
with AVX2 (`polars[rtcompat]` is the build for older ones); (d) the Microsoft C runtime must be present (it comes with Python).

To verify on the laptop, before any build work depends on it:

```powershell
# 1. no .exe inside the wheels (run in the folder they were downloaded to)
Get-ChildItem *.whl | ForEach-Object { python -m zipfile -l $_.FullName } | Select-String "\.exe"      # expect nothing
# 2. the libraries load
drishti-console\.venv\Scripts\python.exe -c "import polars, deltalake, pyarrow; print(polars.__version__, deltalake.__version__)"
# 3. a table is written and read back
drishti-console\.venv\Scripts\python.exe -c "import polars as pl; pl.DataFrame({'id':['a']}).write_delta('data/tmp/polars-check')"
drishti-console\.venv\Scripts\python.exe -c "import polars as pl; print(pl.read_delta('data/tmp/polars-check'))"
```

### 6.4 What changes in the documents when it is built

| Document | Change |
|---|---|
| [DATA_LAKE_GUIDE.md](../guides/DATA_LAKE_GUIDE.md) 3.2 | the `--engine` row gains `polars` and one paragraph: when to use it, what it refuses. Section 3's table and the runbooks of section 16 stay on the Java loader |
| DATA_LAKE_GUIDE.md 14.1 | the table of writers: the Polars engine through delta-rs |
| [CLI_REFERENCE.md](../guides/CLI_REFERENCE.md) 4.13 | the settings the engine takes and refuses |
| [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md) 7 and 10 | the contract (section 2 here) and the engine; this file deleted |
| DEPLOYMENT_AND_QA_GUIDE.md, OPERATIONS_GUIDE.md | the optional install and section 6.3's three checks |
| BENCHMARK.md 7 | section 5.3's results |
| CONFIGURATION.md | generated: `drishti.lake.build.engine` |
| CHANGELOG.md, the README's counts | as every release |

## 7. Build plan

Each step ends green on the fixture for what it covers; no step needs a lake build above 10,000 rows except P7.

| Step | What | Size | Gate |
|---|---|---|---|
| P0 | Section 6.3's three checks on the Windows QA laptop | an hour, by the owner or QA | stop here if a wheel does not load |
| P1 | **The contract fixture**: `loader-contract/cases.yaml`, the Java runner, the Python runner for `--engine java`; the comparators (one reader, the log reader). About 60 cases | large: 3 to 4 days | the Java loader passes every case; each case it fails is a finding for the owner, not a change to the case |
| P2 | **The row rule as one function** `stage.row`, brought up to the contract (C9, C19, C20: section 2.4), with a differential test against the Java loader on the fixture's documents and on generated ones | medium: 2 days | the Python path passes the row and date groups |
| P3 | **`polarsload.py`, one process**: configuration in, summary out; replace with the slice, append with the batch, one commit a date, new and existing tables; the refusals of 3.4 | large: 3 to 4 days | the Polars engine passes every case; engine against engine equal |
| P4 | **Workers, staging, the memory budget**; the streaming sort measured under a hard process limit; the spill check | medium: 2 to 3 days | a 10,000-position load under a 256 MB limit writes the same table; the decision on large dates (3.5) |
| P5 | **Interop** (4.2) and undo and maintain across engines | medium: 1 to 2 days | all readers, both directions |
| P6 | The setting, `doctor`, the requirements file and lock, the wheel folder, the guard test, the documents of 6.4 | small: 1 day | |
| P7 | **The benchmark** (5.3) on Linux and Windows; the VaR goldens on a Polars-loaded lake (4.3) | medium: 1 to 2 days, one run at a time | section 5.4's table filled with numbers |
| P8 | **Retire the old Python path** if Q1 says so: `Stager`'s staged files, `writers.write_deltalake`, `writers.write_drishti`, `--engine drishti` and `deltalake`, `overwrite-partitions` | medium: 2 days | `PythonPathEqualityTest` becomes the fixture's engine-against-engine check |

P1 and P2 are worth doing whether or not the engine is built: they turn "the same behaviour" into something a test decides, and
they close the differences of section 2.4 or retire the code that has them. **Recommendation: do P0 to P2 now, and decide on P3
onwards from what P1 shows.** About 13 to 18 builder-days for all of it.

### 7.1 Risks

| Risk | Effect | Answer |
|---|---|---|
| The fixture shows the Java loader itself differs from the guide somewhere | the contract needs a ruling | each is put to the owner; the fixture records the ruling |
| delta-rs and Delta Kernel disagree on a mixed table (checkpoints, statistics, a partition value) | a table one engine cannot append to after the other | 4.2 before any use; a table can be marked single-engine in the summary until it passes |
| Polars or delta-rs changes behaviour between versions (Polars went from 1.x to 2.x within a year) | silent drift | exact pins with hashes; the fixture runs on every bump; the engine checks the versions it was proven on and says so in the summary |
| Memory cannot be bounded for a large date | the engine is for small and medium loads only | measured in P4; refusal with a clear message, never an out-of-memory kill |
| The vector encoding stays unavailable in delta-rs | larger vector tables, slower VaR open | measured in P7 (the reader's side); raise it upstream; the Java loader for vector tables |
| A third engine to keep equal for ever | cost on every loader change | the shared row rule and the one fixture; and Q1: two engines, not three |
| Workers on Windows (process start, whole-lake lock) | little gain there | measured in P7; the engine still serves "no Java" |

## 8. Questions for the owner

| # | Question | Recommendation |
|---|---|---|
| Q1 | **Two engines or three?** With Polars added there would be the Java loader, the Polars engine and the old Python path (with its two writers). "One way per job" says the old Python path goes once Polars passes the fixture; Polars then stops being optional for loading without a JVM | retire it (step P8), one release after both pass |
| Q2 | `overwrite-partitions` exists only in the old Python path. Keep it in the contract (then the Java loader needs it), or retire it now that a shared table's slice is replaced by rule? | retire; move `lake demo` to the slice rule |
| Q3 | **Must the document text be byte-equal across engines** (C18)? It is what keeps Polars from parsing natively. "Equal as JSON" would allow a faster engine but two texts for one document | keep byte-equal |
| Q4 | Is a **Delta-only** engine acceptable under storage parity, with Iceberg, S3 and the in-memory lake refused in favour of `--engine java`? Or is Iceberg through pyiceberg (REST catalogs only) wanted, as a second Iceberg committer? | Delta only |
| Q5 | Storage parity for **loading into the files store**: `lake build` and `LakeLoad` have no files target. Should the contract gain one (for every engine), or does `data ingest --store files` remain its loader? | a separate decision; not part of this engine |
| Q6 | The vector columns' encoding cannot be set through delta-rs today. Accept larger vector tables from this engine, or allow it to write Parquet with pyarrow and commit the files itself (own code for folders and statistics, against the standard-libraries rule)? | accept; never own protocol code |
| Q7 | Should `auto` ever pick Polars (for example for loads under some size)? | no: explicit only, until section 5.3's numbers exist |
| Q8 | The offline wheel folder: part of the release zip, or built on site from a lock? | part of the release, per platform |
| Q9 | Do P0 to P2 (the Windows check, the contract fixture, the one row rule) go ahead now, ahead of the decision to build the engine? | yes |
| Q10 | Which release? It touches the loader, so it follows the release that settles the i4 loader fixes | after i4 |

## 9. The probe (2026-11-15)

A throwaway environment in the worktree, deleted afterwards: Python 3.13, Polars 2.1.0, delta-rs 1.6.6, pyarrow 26.0.1, Linux, 24
threads. 5,000 synthetic positions × 250 values plus 20 duplicates and four bad lines, 26 MB of JSON Lines, gzip. Not a benchmark.

| Looked at | Seen |
|---|---|
| Reading the `.gz` into lines (`pl.scan_lines`) | 0.14 s |
| Decoding six fields and the vector from each line (`str.json_decode` with a narrow schema) | 0.13 s for 5,020 rows; a number id came out as its exact text (`1000001`) |
| One bad line in the column | the whole decode fails: no per-row error. One text inside a vector: the same |
| A missing field and a null one | both null: not told apart |
| 1,250,000 doubles against Python's parser | all bit-equal |
| Polars' JSON writer against `json.dumps` | `1e-05` → `0.00001`; the rest of the sample equal |
| `str.json_path_match` on the vector | returns the array written again, not the line's own text; `str.replace` with a per-row pattern is not supported: the vector cannot be cut out of the line natively |
| The whole row rule in Python (parse, marker, encode), one thread | 30,000 rows/s; the same through four worker processes with the lines sent to them: 48,000 rows/s |
| delta-rs creating a table partitioned by `business_date`, `SOURCE_SYSTEM` | protocol reader 1, writer 2, no features; schema `string`, `date`, `double`, `array<double>` |
| A replace of one system's slice of the date, with a batch transaction and commit metadata | one commit: 1 add, 1 remove (the WSS file only), the `txn` action written; the other system's 2,505 rows read back identical, vectors with nulls included |
| Per-column Parquet settings for the vector through delta-rs | accepted, not applied to the vector; applied to `doc` (no statistics) |
| The same settings through pyarrow's own writer | applied: zstd, BYTE_STREAM_SPLIT, no statistics; Polars reads the file |
| `DeltaTable.create_write_transaction`, `sink_delta`, `sink_iceberg`, `merge_sorted` | present; not exercised |
| Native files in the installed packages | `polars-runtime-32` 1, `deltalake` 1, `pyarrow` 38; no `.exe`, no installed commands |

Not probed: Iceberg through pyiceberg, `.zst` and multi-member gzip through Polars, Polars' streaming sort under a memory limit,
delta-rs reading a batch transaction back, any Windows run, any timing above 5,000 rows.
