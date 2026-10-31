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
# The parallel lake loader (drishti-lakeload)

Status: **built in 2.9.6** (the top item of the release). Module `drishti-lakeload`, main class `com.ash.drishti.lakeload.LakeLoad`, in
the server's exec jar. How to run it and every configuration key: [TOOLS.md section 4.13](../guides/TOOLS.md#413-the-parallel-lake-loader-296).
Related: [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine) (the Iceberg engine it writes with), [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md)
and [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md) (the readers of its tables), [VAR_GUIDE.md section 29](../guides/VAR_GUIDE.md)
(the P&L vector in the lake).

## Contents

1. [The ask](#1-the-ask) · 2. [Where it lives](#2-where-it-lives) · 3. [The pipeline](#3-the-pipeline) · 4. [Memory](#4-memory) ·
5. [The tables it writes](#5-the-tables-it-writes) · 6. [Commits](#6-commits-one-per-business-date) · 7. [Shard-aligned layout and the load manifest](#7-shard-aligned-layout-and-the-load-manifest) ·
8. [Files and S3](#8-files-and-s3) · 9. [Failure and clean-up](#9-failure-and-clean-up) · 10. [Integration with lake build](#10-integration-with-lake-build) ·
11. [Measured, and the 2.3M estimate](#11-measured-and-the-23m-estimate) · 12. [Tests](#12-tests) · 13. [Decisions](#13-decisions) · 14. [Open](#14-open)

## 1. The ask

The full scenario-PV load of the bank: **2.3 million positions, about 510 scenarios each (about 1.2 billion doubles, 20+ GB of JSON
Lines), in at most 10 minutes** on a real server, into Delta or Iceberg (one setting), from a folder or S3, with memory bounded by
configuration whatever the input's size. Before 2.9.6 `drishti.py lake build` parsed and staged every document in Python (one thread,
under the GIL: `tools/lake/stage.py`, `jsonstream.py`) and then a Java writer wrote the staged rows (`LakeWriter`, or the Iceberg loader
over a pipe). The Python staging was the bottleneck.

## 2. Where it lives

A small module, `drishti-lakeload`, between the writers it reuses and the server that ships it:

| Needs | For | Why not elsewhere |
|---|---|---|
| `drishti-lakeio` | local and S3 reads (`Storage`, `RangeReader`: ranged GETs), S3 settings, the Parquet page codecs | shared by both lake engines already |
| `drishti-iceberg` | the Hadoop-free Parquet writer (`ParquetDataWriter`, extended in 2.9.6: `double[]` vectors, UTF-8 text, per-column encodings), catalogs, the Iceberg commit (`Operations.replacePartitions`) | the native Iceberg engine; the Delta files are written by the same writer |
| `drishti-deltalake` | Delta Kernel's transaction and the engine `LakeWriter` commits with (`LakeWriter.engineFor`, `LakeWriter.removals`, made public in 2.9.6) | Kernel stays the only Delta committer |
| Jackson (core, YAML), zstd-jni | streaming JSON, the configuration, `.zst` input | already on the server's class path |

Not in `drishti-deltalake` (it would then need the Iceberg engine) nor in the Iceberg plugin (it carries Apache Iceberg Java and
Hadoop's S3 file system; the loader needs neither). No new third-party dependency. `drishti-server` depends on the module, so the exec
jar runs it: `java -Dloader.main=com.ash.drishti.lakeload.LakeLoad -jar drishti-server-<v>-exec.jar --config load.yaml`.

## 3. The pipeline

```
  input files (folder / glob / s3://)                                                 the table (Delta / Iceberg)
        │ Inputs: each file one split; a plain file > split-mb cut into byte ranges              ▲
        ▼                                                                                        │ ONE commit per date
  ┌───────────── read ─────────────┐   bounded queue   ┌──────── parse ────────┐          ┌──── commit ────┐
  │ readers threads, one split each │ ─ chunks of whole ─► parsers threads       │          │ this thread    │
  │ ranged reads / GETs             │   lines (queue-mb) │ Jackson streaming      │          │ Kernel txn or  │
  │ .gz: GzipMembers (Inflater)     │   back-pressure    │ doc re-written as      │          │ Iceberg        │
  │ .zst: ZstdInputStream           │                    │ lake build writes it   │          │ overwrite      │
  └─────────────────────────────────┘                    │ vector → double[]      │          └────────────────┘
                                                         │ buffer per date; sort; │                ▲
                                                         │ spill sorted runs      │                │ files of the date
                                                         └──────────┬─────────────┘          ┌──── write ─────┐
                                                                    │ runs (memory or files) │ writers threads │
                                                                    └───────────────────────►│ one key range   │
                                                                      key-range bounds from  │ each: k-way     │
                                                                      the runs' key samples  │ merge → Parquet │
                                                                                             └─────────────────┘
```

**Read.** `Inputs` resolves `source.files` (a file, a folder, a glob in the last segments; on S3 a prefix or a glob in the last
segment) and sniffs each file's first bytes (gzip `1f 8b`, zstd `28 b5 2f fd`, else plain). A plain file larger than `parallel.split-mb`
(256 MB) is cut into byte ranges; a range owns the lines that **start** in it (it reads from one byte before its start, drops the
partial line there, and reads past its end to finish its last line). A compressed file is one split: **decompressed as a stream** by the
one reader that has it, with no temporary copy and no step that joins the inputs first; `GzipMembers` reads every member of a
multi-member gzip (the JDK's `GZIPInputStream` decides whether another member follows from `available()`, which a network stream may
answer with 0). The readers (`parallel.readers`, default the cores) take the splits from a queue, largest first, so at most that many
files are open at once and a big `.gz` starts at once. Each reader cuts its stream into chunks of whole lines on one shared queue.

**Parse.** `parallel.parsers` threads (default the cores) take chunks, whichever split they came from, so one large `.gz` is parsed
by every parser. `RowParser` makes one streaming pass per line (Jackson's parser over the bytes, no tree, Jackson's fast double
parser): the document is written again exactly as `lake build` keeps it (`PyJson`: Python's `json.dumps(ensure_ascii=False,
separators=(",", ":"))`, a float as Python's `repr`), the wanted fields are caught on the way (a trie of the id, date, promoted,
partition, vector and flattened paths), a top-level vector field's numbers go straight into a `double[]` (a null element is NaN, written
as null) and the document keeps `{"$vector": "<column>"}` in its place. The rules and the reject reasons are `stage.py`'s. A row
becomes one record in a reusable byte buffer (`RowPlan`: sort key, id, document, typed promoted values, partition values, vectors), never
an object per value.

**Sort.** Each parser buffers its records per business date in 8 MB arenas (`Runs.Buffer`); when its share of the sort buffer is
full it sorts each date's records by key (a merge sort over references, an 8-byte key prefix deciding most comparisons) and writes
them as a **sorted run** file in the spill folder (binary: the records as they are, length-prefixed; doubles raw). After the input, what
is left in memory becomes in-memory runs. Each run keeps a key sample every 1 MB.

**Write.** Per date: if the date spilled more runs than the writers can read at once within their memory share, runs are merged
into fewer, larger runs first (`Runs.reduce`: the passes of an external merge sort). The date is then cut into **key ranges** of about
equal bytes from the runs' key samples (`Runs.bounds`: about `2 × writers` ranges, or fewer for a small date); each range is one task
for `parallel.writers` threads: a k-way merge of every run within the range, rows written as Parquet into files of about
`target.layout.file-size-mb`, a new file also when the Delta partition values change and, with `shard-aligned`, at a group boundary
(section 7). Many small inputs are combined, one huge input is split: files follow the sort order, never the inputs. A file is uploaded
as it is written (S3 multipart) and finished when cut.

**Commit.** Only the main thread commits, after every file of the date is written (section 6).

Every stage is metered (`Meter`: bytes, rows, busy time summed over its threads, wall time); the summary's `throughput` has read,
decompress (its own stage, MB in and out), parse, sortSpill, mergePasses (when needed), write and commit.

## 4. Memory

One setting bounds the load whatever its input: **`memory.budget-mb`** (default half of `-Xmx`), shared as

| Share | Default | Holds |
|---|---|---|
| the queue | 15 % (`parallel.queue-mb`) | half for chunks waiting to be parsed (the semaphore that blocks the readers: back-pressure), a quarter for the chunks readers are filling, a quarter for their inflate buffers |
| the sort buffers | 55 % (`memory.sort-buffer-mb`) | the parsers' unsorted records, each parser `budget / parsers`; past it the parser sorts and spills |
| the writers | 30 % (the rest) | half for reading runs while merging (each writer reads each run of its range through `share / (writers × runs)`, 8 KB to 1 MB; more runs than fit at 64 KB each are first merged into fewer), half for the Parquet row groups being filled |

An explicit `parallel.queue-mb` or `memory.sort-buffer-mb` must leave the writers at least a tenth of the budget. The budget is
for the load's data; the JVM's own (classes, Kernel, the S3 client, the per-row duplicate arrays: about 36 bytes a row, freed before the write) comes on top,
so `-Xmx` should be the budget plus 1 to 2 GB. The summary's `memory` reports the shares, the **peak heap used** (sampled every 20 ms,
garbage included), the **peak heap after GC** (what the load really held) and the MB spilled. Measured: the 10,000-position load below
ran in a JVM of `-Xmx192m` with a 32 MB budget (peak after GC 78 MB, 33 MB spilled) and wrote the same table as with 7.8 GB.

**Spill space.** Before reading, the loader estimates the spill (the input's uncompressed size, compressed files at 6 to 1 for gzip and
8 to 1 for zstd, less the sort buffers, times 1.1) and stops with a clear message when `memory.spill-dir` has less free space
(`memory.check-spill-space: false` to try anyway). The spill folder is a fresh `drishti-lakeload-<pid>-<n>` under `memory.spill-dir`
(default `java.io.tmpdir`, which `lake build` points into the run's staging folder): local disk, EBS, or a Fargate task's ephemeral
storage (up to 200 GB).

## 5. The tables it writes

Exactly the layout the existing readers expect; nothing of the connectors or the VaR loader changed.

| | Delta | Iceberg |
|---|---|---|
| Columns | `id`, `doc`, `business_date` (partition), the partition columns (partition), the promoted columns (`double` when every value is a number and the field is not an id, else `string`; null on every row: `double`, as lake build), the vector columns `array<double>` (nullable elements) | `IcebergLayout`: field ids 1 `id` (required), 2 `doc`, 3 `business_date` (required, identity partition), then the partition fields as `string` columns, the promoted columns (null on every row: `string`, as the Iceberg loader), the vector columns `list<double>` (optional elements) |
| Text of a number in a text column | Python's (`json.dumps`: `42`, `1.5`, `1e-05`) | the Iceberg loader's (`42` for a whole number) |
| An existing table | keeps its schema and partitioning: a load column it lacks is left out (reported in `columns.leftOut`), a column the load lacks is null; another partitioning is refused | its columns kept; the missing ones added (one schema commit), as `NativeLayout.open` |
| New table | created by the first date's commit, with `target.properties` (lake build passes the risk lake's 7-year retention) | created with the layout's properties (zstd, 1 MB row groups, metrics: `doc` none, `id` full), sort order by id |
| Parquet | the writer's row groups and pages as `LakeWriter` (`DRISHTI_LAKE_ROW_GROUP_KB` 256, `DRISHTI_LAKE_PAGE_KB` 64), Snappy; statistics exact (min, max, nulls) for every scalar column, none for `doc` | the table's properties |
| The vector column | BYTE_STREAM_SPLIT (a double's 8 bytes as 8 streams), ZSTD, no dictionary, no statistics; `target.layout.byte-stream-split`, `vector-compression` | the same |

Every Parquet reader in the product reads BYTE_STREAM_SPLIT (it is a standard Parquet encoding: parquet-java, Kernel, Arrow, DuckDB);
the equality tests read the tables back through the Delta and Iceberg connectors, bulk vectors and fetched documents.

## 6. Commits: one per business date

**Why one per date.** A reader (the connectors, the VaR loader, the cluster's shards) must see a date whole or not at all, and a re-run
must replace the date, never add to it. One commit per date does both; N committers would race on the table's log and could leave a
date half-replaced. The commit is also cheap: it lists files, never rows.

- **Delta.** Per date one Kernel transaction (`CREATE_TABLE` for a new table, else `WRITE`), the files written into the folders Kernel
  names (`Transaction.getWriteContext`), then one `commit` with the add actions (`Transaction.generateAppendActions` from each file's
  path, size and statistics) and the remove actions of every earlier file of the date (`LakeWriter.removals`): one version replacing the
  date, as `LakeWriter` does. Kernel's post-commit hooks (checkpoints) run as for `lake build`. On S3 the log entry is an exclusive create
  (`StorageWriteFileIO`, conditional PUT), so two writers of one version cannot both win.
- **Iceberg.** Per date one `Operations.replacePartitions`: the date's live files out, the new ones in, one snapshot (`overwrite`,
  summary `drishti.business-dates`), retried on a concurrent commit as the native engine does.

Dates are written one after another, each with every writer thread; a load of 60 dates is 60 commits, each date visible as soon as it
is committed. `lake build`'s Python path made one Delta commit for a whole run; the loader's per-date commits are the brief's (and what
the Iceberg loader always did).

## 7. Shard-aligned layout and the load manifest

`target.layout.sort-by` orders each date's rows: by default the pack's `layout.<kind>.sort-by` if it names one, else **`LEGAL_ENTITY`, `DESK`, `id`** (those of them the rows promote; the id
alone when neither is promoted). Every file is a contiguous key range, so its Parquet statistics (and the Delta `stats`, the Iceberg
manifest bounds) of `LEGAL_ENTITY` and `DESK` let a reader skip every file outside its legal entities and desks. With
`shard-aligned: true` (default) a file is also cut where the group (the sort fields before the id) changes once it holds a quarter of
`file-size-mb`: a large desk gets files of its own, small desks share one. Range bounds between writers fall on any key, so a desk may
span the last file of one range and the first of the next.

The **load manifest** (`<summary>.manifest.json`, or `report.manifest`) lists, per committed date, the commit's version and every file
in key order: `path`, `rows`, `bytes`, `partition` (Delta), and `first` and `last` (the partition columns, the sort fields and `id` by
name). `groupBy` names the shard group (`["LEGAL_ENTITY", "DESK"]`). A shard owner may read only the files whose `first..last` range
meets its groups; the files' own statistics say the same for a reader without the manifest.

```json
{"table": "s3://bank-lake/risk/scenario-pv", "format": "iceberg", "sortBy": ["LEGAL_ENTITY", "DESK", "id"], "groupBy": ["LEGAL_ENTITY", "DESK"],
 "dates": {"2026-10-28": {"version": 6012998402214350812, "files": [
   {"path": "s3://…/data/business_date=2026-10-28/2026-10-28-r000-00000-….parquet", "rows": 31840, "bytes": 268170210,
    "first": {"LEGAL_ENTITY": "LE-LDN", "DESK": "DESK-COMM", "id": "P-1000017"}, "last": {"LEGAL_ENTITY": "LE-LDN", "DESK": "DESK-COMM", "id": "P-1799862"}}]}}}
```

An Iceberg table sorted by more than the id gets the property `drishti.layout.sort-by`; `lake maintain`'s relayout (which re-sorts
days by id) leaves such a table in its order (`IcebergMaintenance.shardSorted`). Its files carry no sort order id (Iceberg's 0,
unsorted), since the table's order is by id. A Delta table's compaction (`lake maintain`) merges only small files (under
`target-file-mb`); a pack layout that names `sort-by` for the kind should name the same columns (`sort-by: LEGAL_ENTITY,DESK,id`).

## 8. Files and S3

Local paths (Windows paths too: `C:\data\pv\*.jsonl.gz`) and `file:` URIs read through `LocalStorage`; `s3://` (and `s3a://`) through
`S3Storage`: an object's ranges with ranged GETs (a plain file's splits read in parallel), a compressed object as a stream of 64 KB to
8 MB reads. Writing: the Parquet files through the same `Storage` (`S3Output`: multipart upload while the file is written), the Delta
log through Kernel's `StorageWriteFileIO`, Iceberg metadata through drishti-iceberg's commit. S3 settings come from
`source.storage-options` / `target.storage-options` (`region`, `endpoint`, `path-style`, `read-block-kb`, `tls.*`, `proxy.*`) and
**credentials only from the AWS default chain** (environment, profile, instance or task role): a key in the file is refused. A REST
catalog's secret (`target.iceberg.token`, `credential`) is given as `${AN_ENVIRONMENT_VARIABLE}`. No Hadoop anywhere, so it runs on
Windows (Java only; zstd-jni and snappy-java are JNI libraries inside their jars, no `.exe`).

## 9. Failure and clean-up

- A failure before a date's commit leaves that date (and every later one) as it was: the files already written for it are not in any
  version or snapshot. They are orphans: Delta's `VACUUM` (`lake maintain`'s vacuum step) and Iceberg's orphan-file removal
  (`lake maintain` on an Iceberg lake: `remove-orphans`, `IcebergMaintenance --remove-orphans --older-than-hours 72`) remove them after their retention. Dates committed before the
  failure stay committed (each was whole).
- Too many rejects (`rejects.max-pct`) stop the load after the read, before anything is written.
- The spill folder is removed on success, on failure, and when the JVM is stopped (a shutdown hook: Ctrl-C, SIGTERM, a stopped task).
- A reader or parser that fails stops the others at once (the queue never leaves a thread blocked); exit code 1 with the reason in one
  line (`-Ddrishti.loader.trace=true` prints the stack).
- The lock: a local lake gets `lake build`'s lock (shared on `<lake>/_drishti-lake/lock`, exclusive on
  `locks/<domain>/<table>.lock`; the whole lake on Windows). `lake build` now takes a POSIX lock (`lockf`) beside its `flock` on the
  same files, so a direct loader run and a Python build or `lake maintain` exclude each other on Linux too (exit 3 for the later one).

## 10. Integration with lake build

`drishti.py lake build --engine java` runs the whole build in the loader (`tools/lake/javaload.py`): the build's options (`--pack`,
`--key`, `--date`/`--as-of`, `--promote`, `--vector`, `--partition-by`, the pack's flatten, `--format`, `--iceberg-set`) become the
loader's configuration (a JSON file in the run's staging folder); `--loader-set KEY=VALUE` adds any key. The build holds the lock and
writes the run log, the summary (the loader's keys merged in, plus `throughput` and `memory`) and the ownership record as before; the
data-load signal and the BI-store stamp work unchanged. The Python path is the default (product owner, 2026-10-28):
the loader runs only when asked, with `--engine java`. `--engine auto` (the default) picks it on its own only when `--java-above-mb N` is
given: every input JSON Lines, at least N MB, `--mode overwrite-dates`, and a jar (or `DRISHTI_LAKE_CLASSPATH`) with the loader;
everything else (the demo lakes, small loads, JSON arrays, other modes) goes exactly the way it went before. Java is started through
`tools/javacmd.py` (`-Djava.io.tmpdir` into the staging folder); the heap is `DRISHTI_LAKE_LOADER_HEAP` (default `4g`). `--from s3://`
and a Delta `--lake s3://` work with `--engine java` only.

## 11. Measured, and the 2.3M estimate

**Measured** (2026-10-28, the developer workstation: 24 cores, the machine's limit of 10,000 positions; `ThroughputTest`): 10,000
positions × 510 scenarios, 53.7 MB of JSON Lines in eight gzip files (22.3 MB), into Delta on local disk. The first load in a JVM is
cold (1.3 s, 7,700 rows/s); a warm one (what a long load runs at after its first seconds): **0.19 to 0.29 s, 34,000 to 52,000 rows/s,
180 to 280 MB/s of JSON** end to end. Per thread, warm:

| Stage | Per thread | Note |
|---|---|---|
| decompress (gzip, JDK Inflater) | 124 MB/s compressed in, about 300 MB/s out | one reader per `.gz` |
| parse (JSON → record, document re-written, 510 doubles) | 30,000 rows/s (about 165 MB/s of JSON) | Jackson's fast double parser |
| write (merge, Parquet, Snappy + BYTE_STREAM_SPLIT/ZSTD) | 24,500 rows/s | |
| commit | 8 ms a date | |

Iceberg warm: 0.25 to 0.27 s (37,000 to 40,000 rows/s). With a 24 MB budget the same load spilled 37 MB and wrote the identical table.

**The 2.3M estimate (not measured: the machine stays at 10K; to be measured on the product owner's server, release item 3).** Per
position, warm CPU: decompress 18 µs + parse 33 µs + write 41 µs ≈ **92 µs** for a 5.4 KB position; the brief's 20+ GB for 2.3M is
about 8.7 KB a position, so scaled ≈ **150 µs**, about **345 CPU-seconds** for the load. Assuming the stages scale with threads (they
share nothing but the queue and the runs) at half efficiency (memory bandwidth, GC, the read and write phases not overlapping):

| Server | Memory | Spill | Estimate |
|---|---|---|---|
| 32 vCPU, 64 GB, local NVMe (2 GB/s), S3 in and out | `-Xmx40g`, `memory.budget-mb: 32768` (sort buffer 18 GB) | about 0 to 2 GB (the records, about 17 GB, nearly fit) | CPU about 22 s, S3 read 5 GB of gzip and write about 9 GB of Parquet at 0.5 to 1 GB/s: 15 to 30 s; **about 1 to 2 minutes** |
| ECS Fargate, 16 vCPU, 120 GB | `-Xmx96g`, `memory.budget-mb: 73728` | none | CPU about 45 s; S3 as above; **about 2 minutes** |
| 16 vCPU, 32 GB, EBS gp3 at 125 MB/s for the spill | `-Xmx24g`, `memory.budget-mb: 16384` (sort buffer 9 GB) | about 8 to 9 GB written and read again: about 2.5 minutes of disk at 125 MB/s | **about 4 to 5 minutes** |

So the target of 10 minutes holds with room on each, the spill disk's speed being what matters most when the data does not fit: give it
fast disk (NVMe, gp3 with raised throughput, or Fargate ephemeral storage) or memory. **Spill disk**: at most the records' size, about
17 to 20 GB for 2.3M positions (about 2 GB on top while merge passes rewrite runs); 50 GB is ample. **Heap**: the budget plus 2 GB. The
recommended start: `-Xmx` 40 GB, `memory.budget-mb` 32768, `parallel.writers` 16, `target.layout.file-size-mb` 256, spill on NVMe.

## 12. Tests

`./mvnw -o -q -pl drishti-lakeload test` (one test JVM; the S3 test about 40 s):

| Test | What |
|---|---|
| `PythonPathEqualityTest` | `drishti.py lake build` run both ways (the Python path: `--engine drishti`, and `--format iceberg`; the loader: `--engine java`) on synthetic positions (escapes, non-ASCII, number ids, nulls in vectors, a reject) and on the market-risk pack's scenario PVs (`--pack`): equal tables (every row, document, column, vector), equal rejects, rows, sample; the Delta and Iceberg connectors read both alike (bulk vectors, a fetched document) |
| `LakeLoadTest` | one commit per date and a re-run replacing only its date (Delta log versions, Iceberg snapshots); a load stopped after its first files leaving the table as it was; rejects counted, named (`file:line`), written and `max-pct`; identical tables from one file, byte ranges, one `.gz`, a multi-member `.gz`, six `.gz` files, `.zst`, 1 thread vs many, a 1 MB budget (spills) |
| `S3LoadTest` | S3Mock in the JVM: a glob of `.gz` objects and a plain object read as ranged GETs, written to `s3://` as Delta and as Iceberg, equal to the local load; keys in the file refused |
| `ThroughputTest` | the measurements above, BYTE_STREAM_SPLIT and ZSTD on the vector column, the load manifest's key order, a 24 MB budget and a `-Xmx192m` JVM giving the identical table |
| `CodecsTest` | Python's float repr and string escapes, multi-member gzip and a corrupt CRC, globs, sort keys, the pack's layout, the configuration's checks |
| `tools/lake/test_javaload.py` | the build's options as the loader's configuration, secrets kept out of the file, when `auto` picks the loader |
| `ShardSortedLayoutTest` (Iceberg plugin) | relayout leaves a sort-by table in its order |

## 13. Decisions

Made by the builder where the brief left a choice; each the conservative one.

1. **Module**: `drishti-lakeload` (section 2), shipped in the server's exec jar; no new third-party dependency.
2. **Opt-in**: `--engine java`, and `auto` only from 1024 MB of JSON Lines in `overwrite-dates` mode; the demo and small loads unchanged.
3. **Sort buckets**: the key-range buckets are cut **after** the read, from the key samples of every sorted run (the whole
   distribution, no second pass over the input and no guess from a first sample); each bucket is merged from the sorted runs (an external
   merge sort, with extra merge passes only when the runs are too many for the writers' memory) and written independently, in parallel.
4. **Default sort**: a pack's `layout.<kind>.sort-by` when it names one (the trading pack: `id`), else `LEGAL_ENTITY, DESK, id` (the brief's), falling back to the id alone; `sort-by: [id]` gives lake build's order.
5. **Mode**: `replace-date` only (lake build's `overwrite-dates`); `overwrite-partitions` and `append` stay the Python path's.
6. **One commit per date**, not one per run (section 6).
7. **Types and text** follow each format's existing writer: Delta as lake build (an all-null promoted column `double`; a number in a
   text column as Python writes it), Iceberg as the Iceberg loader (all-null `string`; a whole number `42`). Where the Python Iceberg path
   rejected a row (a promoted field holding a list) or dropped a value (an object), the loader keeps the JSON text; the Python Iceberg path
   makes no column for a field null on every row, the loader makes it.
8. **Format**: JSON Lines only (the data standard); a JSON array is the Python path's (auto never sends one to the loader).
9. **Duplicates** in a JSON object keep both keys in the document (Python keeps the last value).
   **Duplicate records** (one id twice on one business date; product owner, 2026-10-29) follow one rule on both paths, so both write
   the same table: the copy with the latest `COMPUTED_AT` (`source.latest-by`; ISO-8601 text, no offset is UTC) is kept, else (none,
   or a tie) the last one read in one deterministic order (files sorted by path, then line order; a copy with a timestamp beats one
   without); the others are dropped, counted per date (`duplicates`) and named (up to ten `duplicateExamples`: `kept` and `dropped`
   as `file:line`); never a failure. How: each row record ends with its place in the input (split, chunk, line: 8 bytes); after the
   read the ids seen twice (64-bit hashes sorted, exact, no cap; a second 64-bit hash tells two ids with one first hash apart) are
   resolved from per-row arrays (the hashes, the timestamp, the place, the partition: about 36 bytes a row, freed before the write),
   the losers leave the row and partition counts, and the write stage skips them by their place. The Python path stages every row with
   its place, then re-reads only the dates with duplicates (`stage.Stager.resolve`). Before 2.9.6 both copies were written by both
   paths, in an order that differed between them (`PythonPathEqualityTest` failed when an uncompressed copy of a test file sat beside its
   `.gz`); the test now builds that very case, with and without `COMPUTED_AT`, on Delta and Iceberg.
10. **Rejects' place**: `file:line` counted in the file, as lake build, also for byte ranges (the ranges' line counts are summed).
11. **Vectors**: a NaN element is stored as null (as Drishti's Delta writer); JSON has no NaN except Python's extension.
12. **Lock interop**: `lake build` also takes a POSIX lock (section 9) rather than the loader learning `flock`, which Java cannot take.
13. **Partition values of an object** are its compact JSON (Python sorted the keys); an object as a partition value is not a real case.

## 14. Open

- The 2.3M measurement on a real server (release item 3); the estimate above is extrapolated from 10K.
- A zstd input is one split; a seekable-zstd file could be split into ranges like a plain one.
- S3 reads of a compressed object are sequential per object (one GET stream); a read-ahead would overlap the network and the inflate.
- The read and write phases do not overlap within a date (the sort needs the whole date); dates of a backfill could overlap.
