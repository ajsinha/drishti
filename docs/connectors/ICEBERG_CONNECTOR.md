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
# The Apache Iceberg connector at scale: a million trades a day, for years

This document explains how Drishti serves entities from **Apache Iceberg** tables, the open table format behind
Snowflake's Iceberg tables, AWS Glue and Athena, Dremio, Trino, Starburst, Apache Polaris and Snowflake Open Catalog, and
how the connector is built to hold a book of **1,000,000 trades every business day, kept for seven years**. It covers the
table layout, the catalogs (path-based tables and REST catalogs), the loader, every read path, delete files, time
travel, retention and maintenance, sizing, memory, what was measured, and where the limits are.

Read it if you run Drishti over Iceberg tables, load Iceberg tables for Drishti, or change the connector. The design is
the Delta Lake connector's ([DELTA_CONNECTOR.md](DELTA_CONNECTOR.md)) applied to Iceberg: the engine side (columns,
searches, derived kinds, impact) is shared and explained there in
[section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns). The other stores with the same
design are described in [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md) and [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md);
the JSON-lines files the loader reads are described in [FILE_CONNECTOR.md](FILE_CONNECTOR.md) and
[DEMO_DATA.md](DEMO_DATA.md). Every setting is in [CONFIGURATION.md](../admin/CONFIGURATION.md).

**About the numbers.** The measurements in this document were taken on 2026-10-01 with **10,000 trades a day over three
business days** (plus the banking samples), on a shared workstation, with the Drishti server at `-Xmx2g`; the scale
benchmark added 25,000 and 50,000 ([section 12](#12-measured-results)). Figures for a million trades a day are
**estimates**, marked as such, scaled from the 10,000-trade measurements and from the file and row-group sizes the
loader produces, or **extrapolations** from the benchmark, also marked.

## Contents

1. [The problem](#1-the-problem)
2. [The principle: never load a day](#2-the-principle-never-load-a-day)
3. [What is stored](#3-what-is-stored)
4. [Catalogs: path-based tables and REST catalogs](#4-catalogs-path-based-tables-and-rest-catalogs)
5. [Declaring the layout in a pack](#5-declaring-the-layout-in-a-pack)
6. [Loading](#6-loading)
7. [How the connector reads](#7-how-the-connector-reads)
8. [Delete files and time travel](#8-delete-files-and-time-travel)
9. [Retention and maintenance](#9-retention-and-maintenance)
10. [Seven years: sizing, and Iceberg with other stores](#10-seven-years-sizing-and-iceberg-with-other-stores)
11. [Memory](#11-memory)
12. [Measured results](#12-measured-results)
13. [Limits and trade-offs](#13-limits-and-trade-offs)
14. [Diagnosing](#14-diagnosing)
15. [Settings](#15-settings)
16. [Checklist for production](#16-checklist-for-production)

---

## 1. The problem

A trade document in the trading pack is about 6–7 KB of JSON. A bank books a million of them and writes the whole book
again every business day with that day's valuations (a *snapshot* kind). Over seven years:

| Quantity | Value |
|---|---|
| Trades per business day | 1,000,000 |
| Business days in seven years | about 1,760 |
| Trade-days (rows) | about 1.76 billion |
| Document text per day | about 6.5 GB (JSON) |
| On disk per day (Parquet, zstd, this layout) | about 0.7 GB (estimate, from the measured 0.7 KB per trade) |
| On disk for seven years | about 1.3 TB (estimate) |

Many banks already keep such data in Iceberg: Snowflake writes Iceberg tables, Glue catalogs them for Athena, Trino and
Spark write them on S3. Reading a table "the simple way" (scan a day, parse every document) does not survive this size:
a day is gigabytes of JSON, so opening one trade, building type-ahead or answering a search would each read the whole
day, as the first Delta connector did ([DELTA_CONNECTOR.md, section 1](DELTA_CONNECTOR.md#1-the-problem)).

## 2. The principle: never load a day

Every question a user asks needs very little of a day: one document; the ids, sorted; or a few narrow fields of every
trade. The connector therefore keeps three bounded structures in memory, each built from only what it needs:

| Structure | Built from | Used by |
|---|---|---|
| **id map** of a day | the `id` column alone | single reads (which file holds the id), type-ahead |
| **column set** of a day | `id` and the promoted columns | searches, pick lists, derived kinds, impact, reverse lookups |
| **document cache** | one row group of `id` and `doc` per read | views, raw documents |

It never holds a whole day of documents for a table with promoted columns (`partitions` stays 0 in the cache counters).

## 3. What is stored

```
<root>/                                          DRISHTI_ICEBERG_ROOT, e.g. ./data/iceberg or s3a://risk-lake/iceberg
└── trading/                                     the data domain (the connector's `domain`; a REST catalog's namespace)
    └── trade/                                   one Iceberg table per kind
        ├── metadata/
        │   ├── v12.metadata.json                schema, partition spec, sort order, properties, snapshots
        │   ├── version-hint.text                the current version (path-based tables only)
        │   ├── snap-…-….avro                    a manifest list per snapshot
        │   └── …-m0.avro                        manifests: each data file with its partition and column bounds
        └── data/
            ├── business_date=2026-09-28/
            ├── business_date=2026-09-29/
            └── business_date=2026-09-30/
                ├── 2026-09-30-00000-<uuid>.parquet   ids BBG-60000001 … CLY-4083458   (250,000 rows)
                ├── 2026-09-30-00001-<uuid>.parquet   ids CLY-4083459 … IMG-500374
                ├── 2026-09-30-00002-<uuid>.parquet   ids IMG-500375 … MX-30083832
                └── 2026-09-30-00003-<uuid>.parquet   ids MX-30083833 … WSS-2166540
```

The table (`IcebergLayout`):

| Column (field id) | Type | Content |
|---|---|---|
| `id` (1) | string, required | the entity's id, `MX-20000017` |
| `doc` (2) | string | the whole JSON document, exactly what a view shows |
| `business_date` (3) | date, required | the identity partition |
| `tradeId`, `productType`, `book`, `desk`, `currency`, `nettingSet`, `sourceSystem`, … | string | promoted fields |
| `notional`, `mtm`, `pnl1d` | double | promoted fields |
| `counterparty__id`, `counterparty__name`, `risk__dv01` | string / double | promoted nested fields (`counterparty.id`: a dot becomes `__`, as in Delta) |

| Property | Value | Why |
|---|---|---|
| partition spec | `identity(business_date)` | a day is a partition: planning a day opens only its manifests, and a day is replaced or dropped as a unit |
| sort order | `id ASC` | each day is sorted by id; engines that honour the sort order (Spark, Trino) keep it |
| `format-version` | `2` | position and equality deletes ([section 8](#8-delete-files-and-time-travel)) |
| `write.parquet.row-group-size-bytes` | `1048576` | small row groups: one read decodes about 3 MB of documents ([below](#why-each-part-is-shaped-this-way)) |
| `write.parquet.row-group-check-min-record-count` | `10` | the writer checks the size early, so small groups really are small |
| `write.parquet.compression-codec` | `zstd` | about 0.7 KB per 6.5 KB trade |
| `write.parquet.dict-size-bytes` | `262144` | `doc` gives up dictionary encoding at once; ids and promoted text keep it |
| `write.metadata.metrics.column.doc` | `none` | no bounds of whole JSON documents in the manifests |
| `write.metadata.metrics.column.id` | `full` | exact id bounds per file (file pruning, drift checks) |
| `write.metadata.delete-after-commit.enabled`, `write.metadata.previous-versions-max` | `true`, `50` | old `vN.metadata.json` files are removed |
| `commit.retry.num-retries` | `10` | concurrent day commits retry instead of failing |
| `drishti.layout.file-rows` | `250000` | rows per file; maintenance re-cuts drifted days to it |

One row, as the loader writes it (the document shortened):

```
id                  MX-20000017
business_date       2026-09-30
doc                 {"tradeId": "MX-20000017", "sourceSystem": "Murex", "productType": "IRS_FIXFLOAT",
                     "notional": 242000000.0, "mtm": 1875863, "book": "BOOK-RATES-3", "counterparty": {…}, "legs": [ … ], …}
tradeId             MX-20000017
productType         IRS_FIXFLOAT
notional            242000000.0
mtm                 1875863.0
book                BOOK-RATES-3
counterparty__id    CP-MERIDIAN-RE
risk__dv01          -155245.0
```

### Why each part is shaped this way

- **The document stays whole**; promoted columns are copies of the fields questions filter, sort, group and link by.
- **Sorted by id, a file per id range.** The manifests record each file's minimum and maximum `id`; each Parquet footer
  records each row group's. A reader looking for one id skips every row group whose range cannot hold it.
- **Small row groups.** Iceberg cuts row groups by size, not by rows, and measures the size compressed. With zstd a
  trade compresses about nine times, so 1 MB row groups hold about 300–700 trades (3 MB of documents to decode for one
  read; measured: 476 trades a group at 10,000 trades a day). The Delta layout's 1,000-row groups need about 2 MB here.
  Fewer, larger groups make the footer smaller (each group adds about 2 KB to it, read on every single-entity read) and
  each read decode more; 1 MB is the measured balance on local disk.
- **Files of 250,000 rows** (about 175 MB, estimate): four files a day, about 7,000 files in seven years.
- **No metrics for `doc`.** Iceberg keeps per-file bounds for every column by default (truncated to 16 characters):
  harmless for `doc` in size, but useless; it is switched off so the manifests carry only what planning uses.

## 4. Catalogs: path-based tables and REST catalogs

The connector reaches the tables through one of two catalogs (`IcebergLake`):

| `catalog` | Tables | Use it for |
|---|---|---|
| `hadoop` (default) | path-based tables at `<root>/<domain>/<kind>` (`HadoopTables`); `root` is a folder or `s3a://…`, `abfs://…`, `gs://…`, `hdfs://…` | a lake Drishti's loader writes; local disk; a single writer |
| `rest` | the tables of namespace `<domain>` (or `namespace`) of an Iceberg REST catalog at `uri` | Apache Polaris, Snowflake Open Catalog, AWS Glue's Iceberg REST endpoint, Unity Catalog, Nessie, Lakekeeper, Gravitino |

A REST catalog takes `uri`, `warehouse`, and `credential` (OAuth2 client id and secret, `id:secret`) or `token`; `scope`,
`oauth2-server-uri`, `prefix` and `io-impl` are passed on, and `catalog.<key>` passes any other catalog property. Files
in S3 are read with Iceberg's `S3FileIO` (or the file IO the catalog names); a catalog that vends credentials supplies
them per table. `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region` and `s3.path-style` configure both S3FileIO
and Hadoop's S3A, for S3-compatible stores and local tests; without them the AWS default chain is used (environment,
profile, instance role).

```yaml
# Polaris / Snowflake Open Catalog
trading-store:
  plugin: iceberg
  kinds: [trade]
  settings: { catalog: rest, uri: "https://<account>.snowflakecomputing.com/polaris/api/catalog", warehouse: risk_catalog,
              credential: "${POLARIS_CLIENT_ID}:${POLARIS_CLIENT_SECRET}", scope: "PRINCIPAL_ROLE:drishti_reader",
              domain: trading }

# AWS Glue's Iceberg REST endpoint (SigV4 signing)
trading-store:
  plugin: iceberg
  settings: { catalog: rest, uri: "https://glue.us-east-1.amazonaws.com/iceberg", warehouse: "<account-id>",
              catalog.rest.sigv4-enabled: "true", catalog.rest.signing-name: glue, catalog.rest.signing-region: us-east-1,
              domain: trading }
```

**Path-based tables in S3 need a single writer.** A `hadoop` table commits by renaming a metadata file, which S3 does not
do atomically: two writers committing at once can lose a commit. One loader at a time is safe (the loader serialises its
own commits per table). For several writers, use a REST catalog: it commits atomically on its side.

The Drishti profile `iceberg` (`SPRING_PROFILES_ACTIVE=iceberg`, `drishti-server/src/main/resources/application-iceberg.yaml`)
switches the banking packs' store connectors (`reference-store`, `market-store`, `trading-store`, `risk-store`,
`credit-store`, `collateral-store`) to this plugin with `catalog: ${DRISHTI_ICEBERG_CATALOG:hadoop}`,
`root: ${DRISHTI_ICEBERG_ROOT:./data/iceberg}`, `uri: ${DRISHTI_ICEBERG_URI:}`, `warehouse: ${DRISHTI_ICEBERG_WAREHOUSE:}`
and `credential: ${DRISHTI_ICEBERG_CREDENTIAL:}`; the packs keep deciding kinds, routes, modes and promoted columns.

## 5. Declaring the layout in a pack

Exactly as for Delta Lake: the pack declares the fields to promote on the connector that stores the kind, and the
pack loader flattens them into `layout.<kind>.columns` ([DELTA_CONNECTOR.md, section 4](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack)
explains how to choose them). The trading pack declares 19 fields for `trade`; the `iceberg` profile keeps that
declaration. `file-rows` applies (the loader's `--file-rows`); `row-group-rows` does not, Iceberg sizes row groups in
bytes (`--row-group-mb`); `sort-by` is always `id`.

A field declared but missing from the table makes Health say `not laid out as the pack declares` ([section 14](#14-diagnosing));
searches on it then read documents. `IcebergMaintenance --columns kind:path,…` adds such columns and fills them from the
documents ([section 9](#9-retention-and-maintenance)).

## 6. Loading

`tools/load-iceberg.sh` loads the banking packs' samples and, optionally, a generated book, through `IcebergLoader` (in
the plugin):

```bash
tools/load-iceberg.sh ./data/iceberg                                   # the 1,791 sample documents x 10 days
tools/load-iceberg.sh ./data/iceberg --trades 10000 --days 3           # and a book of 10,000 trades, streamed
tools/load-iceberg.sh ./data/iceberg --keep-days 1800                  # keep seven years of business days
tools/load-iceberg.sh --catalog rest --uri http://polaris:8181/api/catalog --warehouse risk --credential id:secret
tools/load-iceberg.sh s3a://risk-lake/iceberg --set s3.region=us-east-1 --spill-dir /data/tmp
```

Measured (10,000 trades x 3 days, two writer threads):

```
iceberg: loaded 17,910 rows into 46 tables in 8 s
iceberg: 30,000 rows read in 4 s; 3 business days of 1 tables to write
iceberg: loaded 30,000 rows into 1 tables in 5 s
```

How the loader works:

- **Input**: JSON lines `{"domain", "kind", "id", "date", "doc", "columns": {path: value}}`, from a file or standard input
  (`-`): `make_data.py --jsonl` writes the samples, `bulk_trades.py --jsonl -` streams a generated book straight into it.
- **An external sort per day.** The generator's stream interleaves days and ids (it is not sorted: workers emit slices
  of trade numbers as they finish). Each business day of each kind gets a sorter (`SortedRuns`). At most `--buffer-mb`
  (1024) of rows are held; beyond it the largest day's buffer is sorted and written to a run file in `--spill-dir`, on a
  background thread (two at most). At the end each day's runs are merged back in id order. When an id appears twice in
  a day, the row read last wins. Put the spill directory on a disk: run files total about the size of the JSON loaded.
- **Types**: a promoted path whose values are all numbers becomes a `double` column, anything else `string`; a table's
  column types stay as they are once it exists, and new promoted paths are added as columns.
- **Writing**: each day is written, merged, into files of `--file-rows` (250,000) rows with row groups of
  `--row-group-mb` (1) compressed; up to `--threads` days at once (default half the cores, at most eight).
- **Committing**: a day with at least 100,000 rows is committed as soon as it is written, as one overwrite of that
  partition (`OverwriteFiles` with `business_date = d`). The smaller days of a table are committed together in one
  overwrite of exactly those days, so 46 small tables of 10 days each take 46 commits, not 460. Commits to one table are
  serialised. Loading a day again replaces it: the load is idempotent, and a reader sees the old day or the new one.
- **`--keep-days N`** then removes the business days older than each table's newest N on or before `--as-of` (default:
  today in the business zone; a day after it is neither counted nor removed) and expires snapshots older than a week
  ([section 9](#9-retention-and-maintenance)). Removing more than `--max-drop-share` (0.5) of a table's rows fails,
  removing nothing, unless `--force-drop`.
- **Dates are guarded**: a row dated after tomorrow in the business zone is not loaded; the first few are named on
  standard error and the load ends with an error once the other rows are in.

**Estimate for a million trades a day.** A trial before the test limits wrote a day of 1,000,000 trades in about 40 s on
one writer thread (4 files, 0.70–0.75 GB); the load of three such days was bound by the generator (about 64 s per million
trade-days on 24 cores), not by the writer. Treat these as estimates.

**Your own writer** (Spark, Flink, Snowflake, Trino) writes the same table. In Spark, for one business day:

```python
(day.select(F.col("tradeId").alias("id"), F.to_json(F.struct("*")).alias("doc"), F.lit("2026-09-30").cast("date").alias("business_date"),
            "tradeId", "productType", "notional", "mtm", "book", F.col("counterparty.id").alias("counterparty__id"), …)
    .repartitionByRange(4, "id").sortWithinPartitions("id")
    .writeTo("risk.trading.trade").option("write.parquet.row-group-size-bytes", 1048576).overwritePartitions())
```

with the table created with `PARTITIONED BY (business_date)`, `ALTER TABLE … WRITE ORDERED BY id`, and the properties of
[section 3](#3-what-is-stored). Snowflake-managed Iceberg tables write their own file sizes and sort order; a
`CLUSTER BY (id)` keeps rows near their ids, and `IcebergMaintenance --relayout` can rewrite days the connector reads
slowly (on tables Drishti may write, through a REST catalog).

## 7. How the connector reads

The connector (`plugins/drishti-plugin-iceberg`) reads with Iceberg's Java library and its own Parquet reader
(`iceberg-parquet`, `iceberg-data` generic records), without Spark. Every read projects only its columns and passes
its filter to the Parquet reader, which skips row groups by their statistics.

### 7.1 The snapshot and its files

Every `refresh-seconds` (10) each table's metadata is re-read (`Table.refresh()`: for a path-based table one small
`version-hint.text` file and, when it changed, the new metadata JSON; for a REST catalog one `loadTable` call). When the
current snapshot changed, its data files are planned once (`planFiles`, the manifests read in parallel on virtual
threads) and grouped by business date into a **layout**, kept per snapshot (`layout-cache`, 64 snapshots). Planning
reads only manifests listed by the snapshot; with daily commits Iceberg merges small manifests itself, and maintenance
regroups them by month.

Every business day of a layout carries a **fingerprint** of its data and delete files. Id maps, column sets and cached
documents are keyed by it, not by the snapshot: committing a new day (a new snapshot) leaves every other day's caches in
place. (The Delta connector keys them by table version, so a commit there reloads the days it did not touch.)

### 7.2 The id map of a day

The `id` column of each of the day's files, read on its own virtual thread; files of a laid-out day hold disjoint id
ranges, so their ids, concatenated in order of each file's first id, are already sorted (a day that is not laid out is
sorted after reading). Measured at 10,000 trades: part of a 26 ms first view. Estimate at a million: about 75 MB and
well under a second. Kept by memory (`id-map-mb`, 1024).

### 7.3 Opening one entity

1. The layout gives the day: for a `snapshot` kind, the newest on or before the date asked within `lookback-days`;
   for an `effective` kind, each earlier day in turn until the entity is found.
2. The day's id map gives the file (a binary search).
3. That file is read with projection `(id, doc)` and filter `id = 'MX-20000017'`: Parquet reads the footer and decodes
   only the row group whose id range holds it. Delete files are applied ([section 8](#8-delete-files-and-time-travel)).
4. The document is kept in the document cache (`doc-cache-mb`, 256). At most `max-concurrent-reads` (16) such reads run
   at once.

| | Measured, 10,000 trades a day |
|---|---|
| a trade's document, first read | 12–14 ms over HTTP |
| a trade's full view, first / again | 24–38 ms / 11 ms |
| a trade on a past business day (its id map read first) | 39 ms |
| 1,000 different trades, 8 at once | 396 reads/s, p50 15 ms, p99 79 ms |

Estimate at a million trades a day: a first read takes about 20 ms in the reader (footer about 1–2 MB, one row group of
about 3 MB of documents), 40–50 ms over HTTP; the footer is the larger part, and it is read again for every read.

### 7.4 Type-ahead

The newest day's ids (for an `effective` kind, every day's, deduplicated) in Drishti's sorted `HitIndex`, rebuilt only
when a snapshot changes; no document is read. Measured: `TRD CLY-4000` in 4–13 ms with 10,000 ids.

### 7.5 The column set of a day

`id` and every promoted column of the day's files, each file on its own virtual thread, repeated text shared. Kept by
memory (`columns-cache-mb`, 1024); the newest day's is loaded in the background after each snapshot change. Measured: a
search on another business day, first 44 ms (its column set read), then 8 ms. Estimate at a million: about 230 MB per
day for 19 columns and one to two seconds to read.

### 7.6 Reverse lookups

From the day's promoted text columns: every row whose value equals the target. Without promoted columns, a day of at
most `max-load-rows` (200,000) rows is read whole (ids and the identifiers each document mentions, not the documents),
kept by `cache-mb`; a larger day reports no referrers. For an `effective` kind each entity is judged by its latest row on
or before the date. `reverse-index: false` turns reverse lookups off.

### 7.7 Searches, pick lists, derived kinds, impact

As for Delta Lake ([DELTA_CONNECTOR.md, section 7](DELTA_CONNECTOR.md#7-searches-pick-lists-derived-kinds-and-impact-over-columns)):
the engine asks `columnar(kind)` and `columns(kind, paths, asOf)`. `columns` answers "not held" (empty) for a business day
the table does not have, so the next store for the kind is asked.

## 8. Delete files and time travel

**Delete files are applied by every read**, not only by single reads. Spark, Trino, Flink and Snowflake delete or update
rows of an Iceberg v2 table without rewriting its files by writing *position deletes* (file and row position) or
*equality deletes* (rows whose `id` equals a value). Each planned file carries the delete files that apply to it, and
the reader runs through Iceberg's `GenericDeleteFilter`, so id maps, single reads, column sets and whole-day reads all
leave deleted rows out. (The Delta connector, meeting deletion vectors, switches column reads off for the table; here
they stay on.) Equality deletes are loaded into memory per file read, so a table with many of them reads more slowly
until maintenance folds them into the data (`--relayout`); `cacheStats` counts `daysWithDeletes`. Deletion vectors of
format version 3 are not covered by the version of Iceberg used (1.10.1, chosen because it reads Parquet 1.16 like Delta
Kernel, so the server carries one Parquet).

**Time travel**: a read with *known at* (`X-Drishti-Known-At`) uses the snapshot current at that instant (the table's
snapshot log), its schema and its files; a time after the latest commit reads the current snapshot, a time before the
first commit, or before the oldest snapshot kept, reads nothing ("nothing was known then"). Snapshot expiry
([section 9](#9-retention-and-maintenance)) therefore bounds how far back *known at* reaches. Measured: a trade loaded
after the chosen instant answers 404, a sample trade known then answers from the earlier snapshot (17 ms).

## 9. Retention and maintenance

`IcebergMaintenance` (in the plugin) keeps tables bounded and in the layout. Each step is one Iceberg commit:

```bash
java -cp <plugin classpath> com.ash.drishti.plugin.iceberg.IcebergMaintenance ./data/iceberg --domain trading,market \
     --keep-days 1800 --relayout --rewrite-manifests --expire-hours 168
```

| Option | What it does |
|---|---|
| `--keep-days N` | deletes the business days older than the table's newest N on or before `--as-of` (today): a metadata-only delete of whole partitions; more than `--max-drop-share` (0.5) of a table's rows needs `--force-drop` |
| `--relayout` | rewrites, sorted into files of the table's `file-rows`, only days that drifted: files whose id ranges overlap (an append out of order), a file over `file-rows`, more files than needed, delete files, a promoted column missing from a file; one `RewriteFiles` commit per day, validated against the snapshot it read |
| `--force` | with `--relayout`: every day |
| `--columns kind:path,path` | adds promoted columns (types inferred from the newest documents) and rewrites every day, filling them from the documents |
| `--rewrite-manifests` | regroups manifests one per month of business dates |
| `--expire-hours H` | expires snapshots older than H hours (default 168), always keeping the current one, and deletes the files only they referenced; `-1` skips it |
| `--kinds k1,k2`, `--spill-dir`, `--buffer-mb`, `--catalog …`, `--set key=value` | limits the tables; bounds a rewrite's sort; the catalog |

A rewrite reads the day through the same reader (deletes applied), sorts it with the loader's external sort, writes the
files and replaces the day's data and delete files in one commit. Measured on the 10,000-trade table: checking every day
for drift and finding none, 0.7 s; removing 5 days, rewriting the remaining 5 (`--force`), regrouping manifests and
expiring snapshots, 2.9 s.

**Seven years of commits.** A table of 1,800 daily commits (small rows, to test the history itself) took 93 ms per
commit to write; with a manifest per commit (Iceberg merging them as it goes) the connector started in 160 ms (planning
plus the newest ids), read a 2020 trade in 22 ms and an old day's columns in 13 ms; after `--rewrite-manifests` and
snapshot expiry: 33 ms, 5 ms and 3 ms. Expire snapshots regularly: every snapshot stays in the metadata JSON that each
commit rewrites.

**What managed platforms do instead.** AWS Glue's table optimizers (compaction, snapshot retention, orphan file removal)
and Athena's `OPTIMIZE … REWRITE DATA` and `VACUUM`; Snowflake's automatic maintenance of Snowflake-managed Iceberg
tables; Spark's `rewrite_data_files` (with `sort_order => 'id'`, so days stay sorted), `rewrite_manifests`,
`expire_snapshots` and `remove_orphan_files` procedures; Trino's `ALTER TABLE … EXECUTE optimize` and
`expire_snapshots`. Retention of old business days is a plain `DELETE FROM … WHERE business_date < …` in any of them.
Orphan files (left by a loader that crashed before committing) are not removed by `IcebergMaintenance`: use one of those
procedures, or delete files older than a day that no snapshot references. Never apply S3 lifecycle rules to a table's
files: they delete files the metadata still points to.

## 10. Seven years: sizing, and Iceberg with other stores

Estimates for a million trades a day:

| History | Rows | Data (zstd) | Files (4 a day) |
|---|---|---|---|
| 90 business days | 90 million | about 65 GB | 360 |
| 1 year (250 days) | 250 million | about 180 GB | 1,000 |
| 7 years (1,760 days) | 1.76 billion | about 1.3 TB | 7,040 |

Nothing in the read paths grows with history: a read concerns one day's files; planning reads the current snapshot's
manifests (a few dozen after monthly regrouping); the caches hold days by memory. An Iceberg table is the natural
long-history store, and it combines with a fast recent store exactly as Delta Lake does with Aerospike
([AEROSPIKE_CONNECTOR.md, section 7](AEROSPIKE_CONNECTOR.md#7-seven-years-sizing-and-aerospike-with-delta-lake)):
configure Aerospike (or PostgreSQL) first for recent days and an Iceberg connector for the same kind behind it; a read or
a search on a day the first store does not hold falls through to Iceberg.

## 11. Memory

| Held in memory | Per million trades (estimate) | Bounded by | Default |
|---|---|---|---|
| id map of a day | about 75 MB | `id-map-mb` | 1024 MB |
| column set of a day (19 columns) | about 230 MB | `columns-cache-mb` | 1024 MB |
| documents read recently | 7 KB each | `doc-cache-mb` | 256 MB |
| type-ahead ids (newest day) | about 150 MB | — | — |
| planned snapshots | a few KB per file | `layout-cache` | 64 snapshots |
| ids and references of small days (reverse lookups without columns) | — | `cache-mb` | 512 MB |

Measured server heap in use with 10,000 trades a day: 126 MB after start, 437 MB after the measurements (heap limit
2 GB). Give the server at least 8 GB of heap per million entities a day (estimate, as for Delta Lake).

## 12. Measured results

On 2026-10-01, a shared developer workstation (24 cores), the banking samples plus **10,000 trades a day over three
business days** in path-based tables on local disk, a Drishti server with the `iceberg` profile and `-Xmx2g`, times over
HTTP from the same machine:

| Question | Time |
|---|---|
| load the samples (17,910 rows, 46 tables) / load 30,000 trade-days (streamed, sorted, spilled) | 8 s / 5 s |
| server start (plans every table, reads the newest ids) | 6.3 s |
| type-ahead `TRD CLY-4000`, first / again | 13 ms / 4 ms |
| open a trade (view), first / again | 24–38 ms / 11 ms |
| a trade's document, first read / a past business day | 12–14 ms / 39 ms |
| `TRD where mtm < -50m order by mtm` | 29 ms first, 7 ms again (21 matches; `scanned` 10,000, `partial: false`) |
| `TRD where currency = 'USD' and notional > 500m order by notional desc limit 20` | 12–14 ms (113 matches) |
| `TRD book=BOOK-RATES-3` | 7–9 ms (689 matches) |
| pick list `TRD END-1100` | 8 ms |
| desk P&L `DESK-RATES` / another desk | 16 ms / 14 ms |
| impact of netting set `NS-SUMMIT-NY`, first / again | 130 ms / 35 ms |
| a search on another business day, first / again | 49 ms / 8 ms |
| *known at* before the trades were loaded | 404 in 34 ms (the earlier snapshot) |
| 1,000 different trades, 8 at once | 396/s, p50 15 ms, p99 79 ms |
| maintenance: drift check / keep 5 days + rewrite 5 + manifests + expiry | 0.7 s / 2.9 s |
| server heap in use | 126–437 MB |

**A million trades a day: not measured; extrapolated.** The straight lines through the scale benchmark's points (the
table below) put the newest day's three searches at 75–170 ms, a trade's document at about 80 ms today and 180 ms on a
past day, impact of `NS-SUMMIT-NY` at about 140 ms, the first search on another business day at about 5.5 s (its column
set read) and then about 60 ms, and three days at about 2.5 GB. These are **extrapolations from 10,000 to 50,000 trades
a day, not measurements**. Heap of 2–3.5 GB, as on Delta Lake, remains an estimate.

**The scaling curve, measured with the scale benchmark (2026-10-01).** `tools/bench/scale.sh`
([SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md)) loaded 10,000, 25,000 and 50,000 trades a day over three business
days (plus the banking samples) and asked the same questions over HTTP each time: a 24-thread laptop shared with other
work, a Drishti server with `-Xmx2g`, path-based tables on local disk. Medians of 25 requests; every search exact
(`partial: false`, `scanned` equal to the trades a day). The last column carries a straight-line fit (R² beside it) to a
million trades a day: **an extrapolation from the measured points, not a measurement**; "flat" means the measure does
not grow with the book. This store has not been measured at a million trades a day; the last column is the best figure
there is, and only an order of magnitude.

| | 10,000 | 25,000 | 50,000 | R² | 1,000,000 (**extrapolated**) |
|---|---|---|---|---|---|
| type-ahead `TRD CLY-400` (ms) | 8.4 | 8.7 | 7.3 | 0.47 | flat, about 8.1 ms |
| open a trade (view), first time (ms) | 5.7 | 5.3 | 4.5 | 0.11 | flat, about 5.0 ms |
| a trade's document, today (ms) | 11.3 | 10.1 | 14.1 | 0.62 | 83.2 ms |
| a trade's document, a past day (ms) | 10.9 | 11.5 | 17.1 | 0.93 | 183 ms |
| `TRD where mtm < -50m order by mtm` (ms) | 2.7 | 4.3 | 5.8 | 0.97 | 73.5 ms |
| `TRD where currency = 'USD' and notional > 500m …` (ms) | 2.7 | 5.0 | 9.6 | 0.99 | 168 ms |
| `TRD book=BOOK-RATES-3` (ms) | 3.0 | 4.5 | 6.6 | 0.97 | 101 ms |
| pick list `TRD END-1100` (ms) | 1.0 | 2.3 | 2.0 | 0.58 | 29.4 ms |
| desk P&L `DESK-RATES` (ms) | 3.1 | 3.6 | 4.0 | 0.08 | flat, about 4.4 ms |
| impact of `NS-SUMMIT-NY` (ms) | 19.3 | 21.6 | 24.3 | 0.99 | 136 ms |
| a search on another day, first (ms) | 33.4 | 123 | 264 | 0.99 | 5,451 ms |
| a search on another day, again (ms) | 2.6 | 5.4 | 5.4 | 0.62 | 59.2 ms |
| load (the whole script) (s) | 18.2 | 22.4 | 26.4 | 0.97 | 232 s |
| store size (MB) | 34.8 | 71.5 | 133 | 1.00 | 2,462 MB |
| server live heap after a full GC (MB) | 98.2 | 110 | 127 | 0.99 | 763 MB |

Run-to-run variance, requests per second with 8 clients, server start and the other stores side by side:
[SCALE_BENCHMARK.md › Results](../admin/SCALE_BENCHMARK.md#4-results).

## 13. Limits and trade-offs

- **The Parquet footer is read on every single-entity read.** Iceberg's reader does not cache footers; with 1 MB row
  groups a 250,000-row file has 400–800 groups and a footer of 1–2 MB (estimate), the larger part of a first read.
  Larger row groups shrink it and make each read decode more.
- **Only promoted fields are fast**; others are searched in documents (20,000 at most, `partial`).
- **Effective-mode kinds** are not read as columns (each entity's latest row would have to be resolved across days);
  their type-ahead lists every day's ids.
- **Only Parquet data files** are read; a table with ORC or Avro data files fails on those files. Encrypted tables are
  not supported. Format-version-3 deletion vectors are not read by Iceberg 1.10.
- **Path-based tables on S3 need one writer at a time** ([section 4](#4-catalogs-path-based-tables-and-rest-catalogs)).
- **Time travel reaches back only as far as snapshots are kept.**
- **Orphan files are not removed** by the maintenance tool ([section 9](#9-retention-and-maintenance)).
- **The loader holds `--buffer-mb` of rows plus up to two buffers being spilled**: about three times the buffer at peak.
- **Live overlays are not in column searches**, as for Delta Lake.

## 14. Diagnosing

`GET /api/v1/admin/health` shows each connector with its caches:

```json
{"name": "trading-store", "health": "UP",
 "cache": {"tables": 1, "snapshots": 1, "idMaps": 2, "columnSets": 2, "documents": 2, "partitions": 0,
           "daysWithDeletes": 0, "ids": 10000}}
```

`partitions` (small days read whole for reverse lookups) stays at 0 for a laid-out table; `daysWithDeletes` counts the
current snapshot's days with delete files.

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: cannot reach …` | the root folder, bucket or catalog namespace is not reachable | check `root` / `uri`, credentials, `s3.*` |
| `DOWN: no Iceberg tables under …` | nothing loaded in that domain, or the wrong `domain` / `namespace` | load it; check the domain |
| `UP (not laid out as the pack declares: trade (12 of 19 columns) …)` | the writer did not write all promoted columns | `IcebergMaintenance --columns trade:path,…` or reload |
| searches say `partial: true` | a field the query reads is not promoted | add it to `layout.<kind>.columns`, then `--columns` |
| single reads slow, `daysWithDeletes` > 0 | many equality deletes | `IcebergMaintenance --relayout` |
| single reads slow on a table written by another engine | large row groups or unsorted days | `--relayout --force` (or the engine's sorted compaction) |
| *known at* finds nothing | the snapshots of that time were expired | keep more history (`--expire-hours`) |
| new data not visible | the refresh has not run yet | wait `refresh-seconds`; check the catalog's current snapshot |
| loader: disk full in the temp folder | run files of the external sort | `--spill-dir` on a large disk |

Iceberg logs `Table location loaded` once per table and `Refreshing table metadata` when a table changes; the
connector turns off the scan report Iceberg would log for every planning.

## 15. Settings

On an Iceberg connector (`drishti.sources.connectors.<name>.settings`, or the connector's `settings:` in a pack):

| Setting | Default | Meaning |
|---|---|---|
| `catalog` | `hadoop` | `hadoop` (path-based tables) or `rest` |
| `root` | none (`root` or `uri` is required: without either the plugin stays idle; the `iceberg` profile sets `./data/iceberg`) | `hadoop`: the lake, a folder or `s3a://…` (`abfs://`, `gs://`, `hdfs://`) |
| `domain` | empty | the data domain: the folder under `root`, or the REST namespace |
| `namespace` | the domain | `rest`: the namespace (dots separate levels) |
| `uri` | none | `rest`: the catalog's URI |
| `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`, `prefix`, `io-impl` | none | `rest`: passed to the catalog |
| `catalog.<key>` | — | `rest`: any other catalog property (`catalog.rest.sigv4-enabled` …) |
| `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`, `s3.path-style` | — | object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) |
| `hadoop.<key>` | — | passed to Hadoop as `<key>` |
| `kinds` | every table found (new ones too) | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | promoted paths ([section 5](#5-declaring-the-layout-in-a-pack)) |
| `refresh-seconds` | `10` | how often each table's current snapshot is checked |
| `layout-cache` | `64` | planned snapshots kept |
| `id-map-mb` | `1024` | memory for days' id maps |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `doc-cache-mb` | `256` | memory for recently read documents |
| `cache-mb` | `512` | memory for small days read whole for reverse lookups |
| `max-concurrent-reads` | `16` | single-document reads at once |
| `max-load-rows` | `200000` | the largest day read whole for reverse lookups when a table has no promoted columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | the connector's name | the name shown in provenance and Health |
| `stale-after` | none | warn when no new data arrived for this long (engine setting) |

Loader options (`IcebergLoader FILE|- [root]`): `--file-rows` (250000), `--row-group-mb` (1), `--buffer-mb` (1024),
`--threads` (half the cores, at most 8), `--spill-dir` (the system temp folder), `--keep-days`, `--as-of yyyy-MM-dd` (today in the business zone), `--future-days N` (1), `--zone Z` (`DRISHTI_BUSINESS_ZONE`, else `America/New_York`), `--max-drop-share F` (0.5), `--force-drop`, `--catalog`,
`--uri`, `--warehouse`, `--credential`, `--token`, `--set key=value`.

## 16. Checklist for production

1. Choose the catalog: a REST catalog (Polaris, Snowflake Open Catalog, Glue) when other engines also write the tables;
   path-based tables when only Drishti's loader writes them.
2. Declare `layout.<kind>.columns` for every large kind and write the layout ([section 3](#3-what-is-stored)), or let
   `IcebergMaintenance --relayout` bring other writers' days into it.
3. Load with the spill directory on a disk; check Admin → Health: `UP`, no `not laid out`.
4. Run `IcebergMaintenance` nightly: `--keep-days`, `--relayout`, `--rewrite-manifests`, `--expire-hours` (or the
   platform's own maintenance, [section 9](#9-retention-and-maintenance)).
5. Give the server at least 8 GB of heap per million entities a day (estimate).
6. Measure on your data: a search reports `elapsedMs`, `scanned` and `partial`; a view reports its timings.
