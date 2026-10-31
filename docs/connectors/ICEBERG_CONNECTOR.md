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

**Delta is the default; Iceberg by configuration.** Every pack's connector files read Delta Lake tables. A deployment reads a domain from
Iceberg only when it says so: a connector file with `plugin: iceberg` (`config/connectors.examples/iceberg/`), `lake build --format
iceberg`, `build_demo_lakes.py --store calypso=iceberg` or `--store risk=iceberg`, or the scripts of `deploy/aws/iceberg/`. Since
2.9.5 the connector answers everything the Delta connector does on the same rows (vector columns, a date's documents in one pass,
table identity, inventory, `holdsNone`, the data-load signal) and keeps the same disk caches ([section 18](#18-parity-with-delta-vectors-documents-and-disk-caches-295)).

Reading Iceberg tables that Spark, Databricks (Unity Catalog), Trino or Snowflake already write: [Using Drishti with Databricks Delta Lake or Apache Iceberg](../guides/DATA_GUIDE.md#3-using-drishti-with-databricks-delta-lake-or-apache-iceberg).

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
17. [Engines: native and library](#17-engines-native-and-library)
18. [Parity with Delta: vectors, documents and disk caches (2.9.5)](#18-parity-with-delta-vectors-documents-and-disk-caches-295)
19. [AWS: Glue, S3 Tables and the IAM they need (2.9.5)](#19-aws-glue-s3-tables-and-the-iam-they-need-295)

Also: [Security: TLS and credentials](#security-tls-and-credentials) (before *As a connector file*).

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
| `id` (1) | string, required | the entity's id, `MX-20000001` |
| `doc` (2) | string | the whole JSON document, exactly what a view shows |
| `business_date` (3) | date, required | the identity partition |
| `TRADE_ID`, `SOURCE_SYSTEM`, `PRODUCT_TYPE`, `CURRENCY`, `COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`, … | string | promoted fields (the trading pack's `layout.trade.columns`) |
| `QUANTITY` | double | promoted field |
| `LEGS__CURRENCY`, … | string / double | a promoted nested path (`LEGS.CURRENCY`: a dot becomes `__`, as in Delta) |

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
id                  MX-20000001
business_date       2026-09-30
doc                 {"SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "PRODUCT_TYPE": "IRS_FIXFLOAT",
                     "QUANTITY": 242000000.0, "PORTFOLIO": "BOOK-RATES-3", "COUNTERPARTY": "CP-MERIDIAN-RE", "LEGS": [ … ], …}
TRADE_ID            MX-20000001
SOURCE_SYSTEM       MUREX
PRODUCT_TYPE        IRS_FIXFLOAT
QUANTITY            242000000.0
PORTFOLIO           BOOK-RATES-3
COUNTERPARTY        CP-MERIDIAN-RE
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
trade-endur:
  plugin: iceberg
  kinds: [trade]
  settings: { catalog: rest, uri: "https://<account>.snowflakecomputing.com/polaris/api/catalog", warehouse: risk_catalog,
              credential: "${POLARIS_CLIENT_ID}:${POLARIS_CLIENT_SECRET}", scope: "PRINCIPAL_ROLE:drishti_reader",
              domain: trading }

# AWS Glue's Iceberg REST endpoint (SigV4 signing)
trade-endur:
  plugin: iceberg
  settings: { catalog: rest, uri: "https://glue.us-east-1.amazonaws.com/iceberg", warehouse: "<account-id>",
              catalog.rest.sigv4-enabled: "true", catalog.rest.signing-name: glue, catalog.rest.signing-region: us-east-1,
              domain: trading }
```

**Path-based tables in S3 need a single writer.** A `hadoop` table commits by renaming a metadata file, which S3 does not
do atomically: two writers committing at once can lose a commit. One loader at a time is safe (the loader serialises its
own commits per table). For several writers, use a REST catalog: it commits atomically on its side.

The Drishti profile `iceberg` (`SPRING_PROFILES_ACTIVE=iceberg`, `drishti-server/src/main/resources/application-iceberg.yaml`; deprecated, replaced by the connector files in
`config/connectors.examples/iceberg/`, see [As a connector file](#as-a-connector-file)) switches the banking packs' store connectors (`reference-store`, `market-store`, `trade-endur`, `risk-store`,
`credit-store`, `collateral-store`) to this plugin with `catalog: ${DRISHTI_ICEBERG_CATALOG:hadoop}`,
`root: ${DRISHTI_ICEBERG_ROOT:${drishti.data.dir:./data}/iceberg}`, `uri: ${DRISHTI_ICEBERG_URI:}`, `warehouse: ${DRISHTI_ICEBERG_WAREHOUSE:}`
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

**Millions of rows: the parallel lake loader (2.9.6).** `drishti.py lake build --format iceberg --engine java`, or the loader on its own
(`java -Dloader.main=com.ash.drishti.lakeload.LakeLoad -jar drishti-server-*-exec.jar --config load.yaml` with `target.format: iceberg`),
writes the same layout as `IcebergLoader` (field ids 1 to 3, the identity partition on `business_date`, the layout's properties, vector
columns `list<double>`) from JSON Lines in a folder or S3, every stage multi-threaded and memory bounded, one overwrite per business
date, on a path-based table or through a REST catalog (`target.iceberg`). Sorted by `LEGAL_ENTITY`, `DESK`, id by default (the table
then says so in `drishti.layout.sort-by`, which relayout respects): [TOOLS.md section 4.13](../guides/TOOLS.md#413-the-parallel-lake-loader-296),
[PARALLEL_LOADER.md](../architecture/PARALLEL_LOADER.md).

**The demo lakes, a source system in Iceberg (2.6.7).** `tools/build_demo_lakes.py --store calypso=iceberg` (or `all=iceberg`)
puts a source system's trades in Iceberg beside the others in Delta: the system is built as Delta, then its rows (id, document, every
promoted column) are streamed into `IcebergLoader` with `--engine native` from the server's exec jar (`java -Dloader.main=com.ash.drishti.plugin.iceberg.IcebergLoader -jar drishti-server-*-exec.jar - <root> …`,
no Maven needed, so it runs on Windows) as the table `<data>/iceberg/trading/trade_<system>`, and the connector file
`trade-<system>.yaml` becomes `plugin: iceberg`, `engine: native`, `table: {trade: trade_<system>}`. The connector then reads the trades
exactly as the Delta connector does, including BI's column scan (`scan`), the data version the BI store and the history cache key by
(`dataVersion`: the snapshot id and its time; a day's files' fingerprint) and the held dates (`latestDate`, `earliestDate`,
`heldDates`). An equality test against all-Delta (`tools/lake/stores_equality.py`) gives the same trade pages, histories, grouped pages,
searches and BI answers: [DEMO_DATA.md](DEMO_DATA.md#mixed-delta-and-iceberg-storage).

The samples and the book go to the loader as **one input**, so each business day of a table is written once and holds both: a load
replaces the days it writes, so two loads of the same days (the samples, then a book) would leave only the book's trades on them.
The loader's heap is `DRISHTI_LOADER_HEAP` (default `4g`; Windows: `-Heap`), and its input file is written under the data folder
(`DRISHTI_DATA_DIR`) and removed after the load. A load that fails ends with one line and status 1, never a Java stack trace (both
scripts): `iceberg: the bucket 'warehouse' does not exist at http://localhost:9000 (said by the REST catalog at http://localhost:8181):
create it, or check --warehouse, --set s3.endpoint and the catalog's own warehouse setting […]`, `iceberg: cannot reach the REST
catalog at … (the connection was refused or closed before an answer (ClosedChannelException)): check --uri …`, or `iceberg: the load
failed: <why>`, where the reason is the first message in the failure's chain that says something, never `null`; `-Ddrishti.loader.trace=true` (in `JAVA_TOOL_OPTIONS`) prints the
stack trace as well.

Measured (10,000 trades x 3 days, two writer threads; before 2.6.6 the samples and the book were two loads):

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
(day.select(F.col("TRADE_ID").cast("string").alias("id"), F.to_json(F.struct("*")).alias("doc"), F.lit("2026-09-30").cast("date").alias("business_date"),
            "TRADE_ID", "SOURCE_SYSTEM", "PRODUCT_TYPE", "CURRENCY", "QUANTITY", "PORTFOLIO", "COUNTERPARTY", …)
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


**Several days.** With `index.days: 5` (and, for a lake you search often by past date, `index.storage: mmap`) the
connector also keeps the ids of the last five business days, so a type-ahead for a past date answers from that date's own
ids ([ARCHITECTURE.md section 9.1](../architecture/ARCHITECTURE.md#91-several-business-days)). A new snapshot of a table is the signal: the kept past days are rebuilt from the id column in the background, and a segment file survives a restart only while the snapshot id is unchanged.

A past day restated with no signal is caught by the check every `index.reload-every` (10 minutes, compared with the recorded watermark), and **Admin → Connectors → this connector → Index** reloads any day by hand ([OPERATIONS.md section 12.2](../admin/OPERATIONS.md#122-sizing-the-multi-day-type-ahead-index)).

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

### History queries

`TRD 1000015 30` ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time)): the connector lists an entity's dates itself (`days()`): the days of the snapshot's layout whose id map holds it, so only those days' row groups are read. The design's planned scan (pruning by the manifests' id bounds without an id map) is not built yet.

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
| `--remove-orphans --older-than-hours H` | (2.9.5) deletes the files under each table's location that no snapshot references and that are older than H hours (72, the least it takes: a load still committing has written files no snapshot names yet); with `--dry-run` only lists them |
| `--floor-days D` | (2.9.5) the retention floor: keep-days never drops a business date within D days of `--as-of`, and expiry never removes a snapshot younger than D days (`lake maintain` passes 2,557 for the `risk` and `risk-results` domains) |
| `--dry-run`, `--json` | (2.9.5) commit and delete nothing, say what keep-days and the orphan sweep would do; one JSON line a table (files, MB, dates and snapshots before and after, each step's result), which `lake maintain` reads |
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
Orphan files (left by a loader that crashed before committing) are removed by `IcebergMaintenance --remove-orphans` since
2.9.5 ([DATA_ARCHITECTURE.md › drishti-iceberg: Drishti's own Apache Iceberg engine, section 16](../architecture/DATA_ARCHITECTURE.md#16-vector-columns-and-orphan-files-295)); Admin → Lakes and
`lake maintain` run it for Iceberg tables ([OPERATIONS.md › Lake maintenance: Admin → Lakes → Maintain and `lake maintain`, section 8](../admin/OPERATIONS.md#8-iceberg-lakes)). Never apply
S3 lifecycle **expiry** rules to a table's files: they delete files the metadata still points to. Storage-class transitions by business
date are safe (`deploy/aws/iceberg/setup-lifecycle.sh`: `data/` only, never `metadata/`).

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

**The figures below were measured in 2.2**, when the generated trades were written in camel case (`mtm`, `notional`, `book`),
so the searches are shown as they were run; the trades now carry the source systems' UPPER_SNAKE attributes and no MTM (that is the
market-risk pack's trade valuation): today's equivalents read `TRD where QUANTITY > 500m and CURRENCY = 'USD'` and
`TRD PORTFOLIO=BOOK-RATES-3`. Not re-measured for 2.3.

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

- **On the `library` engine the Parquet footer is read on every single-entity read.** Iceberg's reader does not cache
  footers; with 1 MB row groups a 250,000-row file has 400–800 groups and a footer of 1–2 MB (estimate), the larger part
  of a first read. Larger row groups shrink it and make each read decode more. The `native` engine reads each footer
  once and keeps it ([section 17](#17-engines-native-and-library)).
- **Only promoted fields are fast**; others are searched in documents (20,000 at most, `partial`).
- **Effective-mode kinds** are not read as columns (each entity's latest row would have to be resolved across days);
  their type-ahead lists every day's ids.
- **Only Parquet data files** are read; a table with ORC or Avro data files fails on those files (the native engine
  refuses it by name, naming the file). Encrypted tables are not supported. Format version 3 (deletion vectors, new
  types) is refused by the native engine and not read by Iceberg 1.10.
- **Path-based tables on S3 need one writer at a time** ([section 4](#4-catalogs-path-based-tables-and-rest-catalogs)).
- **Time travel reaches back only as far as snapshots are kept.**
- **Orphan files are not removed** by the maintenance tool ([section 9](#9-retention-and-maintenance)).
- **The loader holds `--buffer-mb` of rows plus up to two buffers being spilled**: about three times the buffer at peak.
- **Live overlays are not in column searches**, as for Delta Lake.
- **The `library` engine needs Hadoop** (and on Windows `winutils.exe`); the `native` engine, drishti-iceberg, needs
  neither but reads local disk and S3 only ([section 17](#17-engines-native-and-library)) and is the default from 2.6.0
  (a root it cannot reach falls back to the library).

## 14. Diagnosing

`GET /api/v1/admin/health` shows each connector with its caches:

```json
{"name": "trade-endur", "health": "UP",
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
| `DEGRADED: cannot read trade: its metadata names files under <old folder> that are not there (…): the table was moved or copied after it was written … relocate: '<old> -> <new>'` (several tables moved together: `credit-limit, cva: their metadata names files …` and one `relocate`) | the table's folder was moved or copied after it was written: Iceberg's metadata names every file by its full path. Admin → Connectors shows the connector as **degraded** with this text; a read of the table answers `502 DRS-1003 … failed reading …: the table … names files that are not there …` (no folder in it) and logs one line, never a stack trace; the connector's **Test** is not ok, gives the same text, and each kind of the table reads `cannot read: the table … names files that are not there …` rather than *nothing found*. The same on Java 21 and Java 25 | write it again where it is now, or read it with `engine: native` and the `relocate` the message gives: copy it as it is, quotes and all (`relocate: '/data/ice1 -> /data/ice2'`). One pair covers every table moved together |
| `… (relocate <pairs> is set, but no old folder in it holds <folder>)` | the old folder in `relocate` is not the one the metadata names (for example a Windows pair `C:\Users\qa\ice1 -> D:\lake\ice2` on a lake written under `/data/ice1`); said for tables moved together too | use the pair the message gives |
| `relocate <pairs> sends its reads to <folder>, and the files are not there either` | the new folder in `relocate` is wrong | fix the new folder |
| the connector fails to start: `relocate: '<pair>' cannot be read: …` (one problem; the retry closes the half-started connector without another log line) | a pair without ` -> `, a side missing, or a relative folder | write `<old folder> -> <new folder>`, both absolute (`/data/ice1`, `C:\lake`, `\\fs\share\lake`) or URIs (`file:/data/ice1`, `s3://bucket/wh`) |
| `UP (relocate is read by engine: native only; engine: library ignores it)` | `relocate` with `engine: library` | set `engine: native` (the library engine reads files where the metadata names them) |

Iceberg logs `Table location loaded` once per table and `Refreshing table metadata` when a table changes; the
connector turns off the scan report Iceberg would log for every planning. A table that cannot be read is one problem in Health per
kind, and the log has one short line when it appears or changes (`<connector>: trade cannot be read: …`) and one when it is over
(`… is readable again`), never a stack trace at each refresh.

## Security: TLS and credentials

An Iceberg lake behind a **REST catalog** whose object store (MinIO, Ceph, an internal gateway) has a certificate from a private
CA is configured with the shared `tls.*` settings ([TLS.md](TLS.md)). They secure the **object store** that holds the data and
metadata files: Iceberg's S3FileIO is replaced by a subclass whose HTTP client has the connector's trust and client certificate.

| Setting | Meaning |
|---|---|
| `s3.endpoint: https://minio.example.com:9000` | TLS on for the object store. `tls.*` against a missing or `http://` endpoint is a start-up error (`tls.* is set but s3.endpoint is not https:// (...): tls.* secures the object store's endpoint`). |
| `tls.ca-file` / `tls.truststore` (+ `tls.truststore-password`) | Trust a private CA. |
| `tls.cert-file` + `tls.key-file` (+ `tls.key-password`), or `tls.keystore` (+ `tls.keystore-password`) | A client certificate. |
| `tls.verify-hostname: false` | **Not supported**: the AWS client always checks the host name (`tls.verify-hostname: false is not supported for Iceberg's S3 client`). |
| `s3.access-key`, `s3.secret-key` | As before. A catalog that vends credentials still overrides them. |

Two things `tls.*` does not cover, because the libraries underneath have no setting for them:

- **The REST catalog's own `https://` connection** uses the JVM's truststore. If the catalog is on a private CA too, import that CA into
  the truststore the server runs with (`-Djavax.net.ssl.trustStore=/etc/drishti/tls/truststore.p12 -Djavax.net.ssl.trustStorePassword=...`).
  The connector logs a warning at start when `tls.*` is set and the catalog URI is `https://`, to say so.
- **Path-based tables (`catalog: hadoop`) on the `library` engine** are read through Hadoop's S3A, which has no setting for a
  private CA. `tls.*` with that catalog is refused at start (`tls.* applies to a REST catalog's object store: ...`); import the CA
  into the JVM's truststore instead. The `native` engine reads them with the AWS SDK and the shared TLS settings, so `tls.*`
  works there for path-based tables too.

### A complete file

`config/connectors/iceberg-rest-private-ca.yaml`:

```yaml
plugin: iceberg
kinds: [trade]
description: Iceberg tables behind a REST catalog, object store over TLS
settings:
  catalog: rest
  uri: http://catalog.example.com:8181
  warehouse: s3://lake/warehouse
  namespace: risk
  s3.endpoint: https://minio.example.com:9000
  s3.region: us-east-1
  s3.access-key: ${LAKE_ACCESS_KEY}
  s3.secret-key: ${LAKE_SECRET_KEY}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
```

### Checking it

```
curl --cacert /etc/drishti/tls/ca.pem https://minio.example.com:9000/minio/health/live
curl http://catalog.example.com:8181/v1/config
```

Health reads `UP`, and `UP (TLS certificate CN=drishti (tls.cert-file) expires in 19 days (2026-10-30))` once a certificate has under
30 days left. The test of the file IO (`IcebergTlsTest`) reads an object's metadata from an HTTPS endpoint that requires a client
certificate, and shows the refusals (an untrusted server: `PKIX path building failed`; a missing client certificate).

### Common errors

| Message | Cause and fix |
|---|---|
| `tls.* is set but s3.endpoint is not https:// (...): tls.* secures the object store's endpoint` | `tls.*` with no endpoint or an `http://` one. |
| `tls.* applies to a REST catalog's object store: Hadoop's S3A, which reads path-based tables, has no setting for a private CA. ...` | `tls.*` with `catalog: hadoop`. |
| `tls.verify-hostname: false is not supported for Iceberg's S3 client ...` | A name mismatch cannot be switched off for this client; re-issue the certificate with the endpoint's name. |
| `tls.ca-file '/etc/drishti/nope.pem': file not found` | A wrong path. |
| a read fails with `PKIX path building failed` | The object store's certificate is not signed by a CA in `tls.ca-file`; or the **catalog's** certificate is private and not in the JVM truststore. |

## Outbound proxy

The settings and rules are shared by every HTTP-based connector: [TLS.md (proxy)](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers). A `proxy:` block in `settings:` (or `proxy: none`) overrides the site default `drishti.http.proxy.*`; the password is an `${ENV}` placeholder, never a literal.

For a **REST catalog**: the catalog's own HTTP client takes the proxy from `rest.client.proxy.hostname`, `.port`, `.username` and `.password` (set from the
connector's settings when the catalog's address is not on `no-proxy`; Iceberg's client has no no-proxy list), and the object store's S3 file IO gets the SDK's
proxy configuration (`https://` tunnelled, `tls.*` kept). For path tables read through **Hadoop**, `fs.s3a.proxy.*` is set. Health adds `via proxy host:port (source)`.
Tested in `IcebergProxyTest` (the S3 file IO through a fake proxy, plain and `CONNECT` with its own TLS, and the S3A properties with no-proxy and `proxy: none`).

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/trade-endur.yaml
plugin: iceberg
kinds: [trade]
description: Trading documents in an Iceberg catalog
settings:
  domain: trading
  catalog: ${DRISHTI_ICEBERG_CATALOG:hadoop}
  root: ${DRISHTI_ICEBERG_ROOT:${drishti.data.dir:./data}/iceberg}
  uri: ${DRISHTI_ICEBERG_URI:}
  warehouse: ${DRISHTI_ICEBERG_WAREHOUSE:}
  credential: ${DRISHTI_ICEBERG_CREDENTIAL:}  # an environment (or file:) reference; empty for a path-based lake
  layout:
    trade:
      columns: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, TRADE_STATUS, TRADE_VERSION, BUY_SELL_CODE, CURRENCY, QUANTITY, TRADE_DATE, MATURITY_DATE, COUNTERPARTY, LEGAL_ENTITY, PORTFOLIO, TRADER_ID]
```

Every `${…}` here has a default, so the file starts as it is on a path-based lake; a REST catalog sets `DRISHTI_ICEBERG_CATALOG`,
`DRISHTI_ICEBERG_URI` and the credential. The trading pack routes kind `trade` to `trade-endur`, so this file replaces that Delta connector
(the ready-made [`iceberg/trade-endur.yaml`](../../config/connectors.examples/iceberg/trade-endur.yaml) is the same file).
A file replaces the pack's template wholesale, so it repeats the promoted `layout` the pack declared ([section 5](#5-declaring-the-layout-in-a-pack)).

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). Ready-made files for this store are in [`config/connectors.examples/iceberg/`](../../config/connectors.examples/iceberg).

## 15. Settings

In the connector file's `settings:` (see [As a connector file](#as-a-connector-file)):

Every setting of `iceberg`, with its type, default and meaning, is in the plugin's generated README: [plugins/drishti-plugin-iceberg/README.md, Settings](../../plugins/drishti-plugin-iceberg/README.md#settings) (from the settings catalogue Admin → Connectors builds its form from). The shared `tls.*` settings are in [TLS.md](TLS.md).

Read beside them, and not in that table:

| Setting | Default | Meaning |
|---|---|---|
| `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`, `prefix`, `io-impl` | none | `rest`: passed to the catalog |

Loader options (`IcebergLoader FILE|- [root]`): `--file-rows` (250000), `--row-group-mb` (1), `--buffer-mb` (1024),
`--engine` (`native`, `library` or `auto`, as the connector's `engine`), `--threads` (half the cores, at most 8), `--spill-dir` (the system temp folder), `--keep-days`, `--as-of yyyy-MM-dd` (today in the business zone), `--future-days N` (1), `--zone Z` (`DRISHTI_BUSINESS_ZONE`, else `America/New_York`), `--max-drop-share F` (0.5), `--force-drop`, `--catalog`,
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

## 17. Engines: native and library

The connector reads (and its loader and maintenance write) through one of two engines, chosen by its `engine` setting
(or `DRISHTI_ICEBERG_ENGINE` for every connector; the loader and maintenance take `--engine`):

| `engine` | What reads and writes | Reaches | Use it for |
|---|---|---|---|
| `native` (default) | **drishti-iceberg** (module `drishti-iceberg`, [DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine)): Drishti's own Iceberg engine, no Hadoop | local disk (Windows drive letters, backslashes, UNC shares), `s3://`, `s3a://`; path-based tables, a metadata file named directly, REST catalogs | everything on local disk and S3; **Windows**, with no `winutils.exe` and no `HADOOP_HOME` |
| `library` | Apache Iceberg Java over Hadoop's file systems (the connector's code before 2.6.0) | the above, plus `abfs://`, `gs://`, `hdfs://` | tables in Azure, Google Cloud Storage or HDFS; a fallback |
| `auto` | `native` on Windows, `library` elsewhere (and `library` for a root the native engine cannot reach) | | keeping the library on Linux servers while Windows laptops use `native` |

Left to the default, a root the native engine cannot reach (`abfs://`, `gs://`, `hdfs://`) is read by the library; asked for
by name, `engine: native` with such a root refuses to start, saying to use `library`.

Packs, connector files, the layout ([section 3](#3-what-is-stored)), the caches and every read path of
[section 7](#7-how-the-connector-reads) are the same on both; a table written by one engine is read and maintained by
the other. `cacheStats` (Admin → Health) shows `engine`.

**What the native engine is.** It reads table metadata (keeping every field it does not know when it writes the next
version), manifest lists and manifests (Avro, versions 1 and 2), plans a snapshot with partition-summary and column-bound
pruning over every transform (identity, bucket, truncate, year, month, day, hour, void; several specs), reads Parquet by
**field id** (renamed columns, added columns as null, widened types, files without ids through
`schema.name-mapping.default`), applies position and equality deletes by the spec's sequence-number rules, and travels
in time through the snapshot log. It writes Parquet with field ids and Iceberg metrics through parquet-java with no
Hadoop `Configuration` and its own codecs, manifests and manifest lists, and commits safely: on local disk the next
`vN.metadata.json` is created as a hard link of a finished file (two writers: one wins, the other re-reads and retries),
on S3 with a conditional PUT (`If-None-Match: *`; a store that ignores it is found by a probe at the first commit and its
tables are single-writer), through a REST catalog with the commit's requirements (a 409 is retried). Its REST client is
the JDK's (config, OAuth2 client credentials, `loadTable` with the ETag, vended S3 credentials, SigV4 for Glue with the
AWS SDK's signer). A class-loader test runs the connector, the loader and maintenance with Hadoop's file systems,
`Shell` and `Configuration` refused.

**What it refuses, by name** (never reads wrongly): format version 3 tables, encrypted tables and files, ORC and Avro
data files (naming the file), types it does not know, S3 remote signing. The message says to use `engine: library`.

**The footer cache.** The native engine reads each Parquet file's footer once and keeps it (files never change), shared
with the native Delta engine (`drishti.parquet.footer-cache-groups`, 50,000 row groups): the larger part of a first
single-entity read ([section 13](#13-limits-and-trade-offs)) is gone after the first read of a file. It also keeps parsed
manifests (`drishti.iceberg.manifest-cache-entries`, 2,000,000 entries) and delete files
(`drishti.iceberg.delete-cache-rows`, 5,000,000 rows); a purge of the connector's caches empties all three.

**Speed.** The same 10,000 trades a day over three days (`tools/samplegen/bulk_trades.py --trades 10000 --days 3
--jsonl`, documents of about 18 KB), loaded by each engine's loader into its own lake and read by the connector on that
engine in one JVM (`IcebergEnginesBenchTest`, `-Ddrishti.iceberg.bench=<jsonl>`; 2026-10-20, a 24-core workstation, heap 6 GB):

| | native | library |
|---|---|---|
| load (30,000 rows, three days) | 22.7 s | 25.7 s |
| connector start (plan, type-ahead from the newest day's ids) | 637 ms | 1,848 ms |
| first trade (cold: id map, footer) | 212 ms | 288 ms |
| one trade, document cache emptied each time: median / p95 | 54 / 111 ms | 75 / 144 ms |
| a trade on a past date (a day not yet read) | 94 ms | 353 ms |

Both engines answered the same (ids, documents, the day's 15 columns, a past date). The day's columns had been loaded
in the background at start on both, so a search is answered from memory on either.

**Windows.** `tools\windows\load-iceberg.ps1` loads the sample documents (and `-Trades N -Days D`) with the native
engine; `tools\windows\start-server.ps1 -Iceberg` starts the server with the Iceberg connector files and
`DRISHTI_ICEBERG_ENGINE=native` ([GETTING_STARTED.md](../guides/GETTING_STARTED.md#drishti-on-windows)).

**Tables Drishti does not write.** The `iceberg-external` connector serves tables of Spark, Flink, Trino, Snowflake or
Glue in their own layout, through the native engine: [ICEBERG_EXTERNAL_CONNECTOR.md](ICEBERG_EXTERNAL_CONNECTOR.md).

## 18. Parity with Delta: vectors, documents and disk caches (2.9.5)

Delta stays the default lake format. What follows lets a deployment that chose Iceberg for a domain (the risk domain of the VaR engine,
a source system's trades) get the same answers as from Delta, on the same rows; `IcebergParityTest` holds the two connectors to it.

**Vector columns.** A pack's `layout.<kind>.vectors` (the market-risk pack: `PNL` of `pnl-vector`, `SCENARIO_PV` of `scenario-pv`) is
an optional `list<double>` column, written by `IcebergLoader` from a staged line whose column holds a list of numbers (`lake build
--format iceberg` stages it so, as for Delta): a missing element is written as null, never 0, and the document keeps the marker
`{"$vector": "PNL"}` instead of the numbers. A list holding anything but numbers is a reject: the row is not loaded and the load ends
with an error counting it (the other rows are loaded).

- `vectorColumns(kind)`: the kind's vector columns the table really has (none for an `effective` kind).
- `vectors(kind, column, texts, asOf, sink)`: every entity of the date streamed file by file, one buffer reused, with the text columns
  asked (each must be a promoted column, else `false` and nothing given, as Delta). A null element is NaN, a row without a vector has
  length -1, a number in a text column keeps its digits (`QUANTITY` 1500.0 reads `1500`). Both engines read it; the 509-value vectors
  come back bit for bit (`IcebergVectorsTest`, `native` and `library`).
- `fetch`: a document with a marker gets its vector put back (whole units stay whole, a missing element is `null`).
- Maintenance copies a vector column as it is: a relayout never takes the marker for the value, and a vector column's metrics (kept
  under its element's id) never make a day look drifted.

**A date's documents in one pass.** `documents(kind, asOf, sink)` reads the newest day on or before the date file by file (derived
pages); `false` for an `effective` kind, which is read entity by entity. Documents come as stored (a marker stays), as from Delta.

**Identity, inventory, `holdsNone`, the data-load signal.**

| Method | Answer |
|---|---|
| `tableIdentity(kind)` | `iceberg:<table-uuid>` from the metadata, else `location:` and the table's location (a local one with the host): the same on every server reading the table, and after the table is copied elsewhere and read through `relocate` |
| `inventory(kind, n)` | the newest `n` business dates and each date's ids, from the id maps (no document read) |
| `holdsNone(kind)` | true when there is no table for the kind, or one with no snapshot yet |
| `refreshVersions()` | each table's current snapshot read again at once (metadata only), so the next read sees the load; type-ahead is rebuilt in the background when one changed; no cached data is dropped |

**Disk caches.** As the Delta connector's, both on by default and both keyed by the date's *fingerprint* (its data and delete files), so
they are never stale and a commit of another date keeps them valid:

| Cache | Where | Setting |
|---|---|---|
| Id maps | `<data>/cache/<connector>/idmaps/<kind>/<date>@<fingerprint>.idmap`, memory-mapped (no heap per id); a newer file of the same date replaces the older, unmapped first (2.9.7); at start only each date's newest stays; the folder is kept under its cap, least recently mapped first | `id-map-disk.enabled`, `id-map-disk.dir`, `id-map-disk.max-gb` (5, 2.9.7), `id-map-disk.idle-release` (30m, 2.9.7), `id-map-disk.ttl` (off, at least 24h, 2.9.7) |
| Documents | `<data>/cache/<connector>/docs` (RocksDB, one per connector), a vector document with its vector put back | `doc-disk-cache.enabled`, `.dir`, `.max-gb` (1), `.ttl` (12h), `.reset-at` (02:00), `.zone` |

After a restart a date read before needs no data file for its id map or a document read before (`IcebergDiskCacheTest` removes the
data files of those dates and both still answer). A document cache that another process still holds is tried again every 30 s for
10 minutes while reads go on without it. A purge of the connector's caches (Admin → Caches) empties both. Admin → Caches shows `idMapsMappedFromDisk`,
`idMapsReadFromLake`, `docsOnDiskMB`, `docsOnDiskHits` and `docsOnDiskMisses`.

**The id-map folder's safeguards (2.9.7)** are the Delta connector's, from the same class (`IdMapFolder`,
[DELTA_CONNECTOR.md section 6.2](DELTA_CONNECTOR.md#62-the-id-map-of-a-day)): a map not read for `id-map-disk.idle-release` (30m, at
least 5m) is unmapped, its file kept, never while read nor for an open date; the cap `id-map-disk.max-gb` never evicts a map being
read, mapped and read within `idle-release`, written or mapped in the last five minutes, or of a date open in VaR or exposure; a date whose map alone is over the cap is
kept and warned about once per date and fingerprint (logged, and flagged on Admin → Caches); `id-map-disk.ttl` (off; at least `24h`,
refused at start below that) drops maps unused for that long, ±10% per file, never the table's latest and previous dates, today's, an
open date, a map being read, nor a mapped map when no engine says which dates it has open; a drop unmaps first, then deletes, a failed
delete is tried again at the next sweep. Each drop's reason (`expired (ttl)`, `evicted (max-gb)`, `replaced (new version)`) is on the
`connectors.id-maps` **Dates**; the counts on the connector's row (`idMapsExpired`, `idMapsEvicted`, `idMapsReplaced`). A position
committed again on the same date is read new right after a data-load signal, within `refresh-seconds` without one, and old only as of
the old snapshot (`IcebergDocStalenessTest`). Sizing: [OPERATIONS.md section 12.3](../admin/OPERATIONS.md#123-sizing-caches-and-disk-per-connector).

**VaR and exposure on an Iceberg risk lake.** With the risk domain in Iceberg (`build_demo_lakes.py --store risk=iceberg` switches the
market-risk pack's `risk-store.yaml` and the counterparty-risk pack's `exposure-store.yaml` together), the engines read P&L vectors,
scenario PVs, the hierarchy, limits and the exposure paths through this connector. The same day on Delta and on Iceberg, written from
the same lines and read by one connector switched from `plugin: delta` to `plugin: iceberg`, gives VaR, ES, component and incremental
VaR, the limit-letter nodes, intraday and the official records equal to the cent in USD and EUR (`VarFormatEqualityTest`), and EE, PFE,
EPE, profiles and contributions equal to the cent (`ExposureFormatEqualityTest`). Intraday batches are **Iceberg appends**: a file
batch appended to the table (one commit, nothing replaced) is applied live after its data-load signal, and the Kafka PV stream writes
each window to an Iceberg table as one append (`LakeAppends`). Run on the demo data: 14 risk tables (955,124 rows) moved in 579 s; the
server then answered `VAR BANK` (USD and EUR), a limit letter and `EXP BANK` from Iceberg.

**Official results in Iceberg.** `drishti.var.official.format: iceberg` and `drishti.ccr.exposure.official.format: iceberg` write the
official tables (`risk-results.var-official`, `exposure-official`, `exposure-official-cpty`) with Drishti's Iceberg engine
(`IcebergAppender`): append commits only (`drishti.append-only=true`), `history.expire.max-snapshot-age-ms` of 7 years, every metadata
file kept; under a folder or `s3://` (`….official.storage`, the `s3.*` settings). A reader is this connector over `domain:
risk-results`: BI's `var-results` and `exposure-results` are generated as `plugin: iceberg` over the official root by themselves when
the format is Iceberg (`OfficialReaders`; a file never edited follows a change of format). [VAR_GUIDE.md section
19](../guides/VAR_GUIDE.md#19-official-end-of-day-results).

## 19. AWS: Glue, S3 Tables and the IAM they need (2.9.5)

**AWS Glue.** The Glue Iceberg REST endpoint is `https://glue.<region>.amazonaws.com/iceberg`, the warehouse the account id (the Glue
catalog), the namespace the Glue database, requests signed with SigV4 under the name `glue` (credentials from the AWS default chain:
the task role on ECS):

```yaml
settings: { catalog: rest, uri: "https://glue.eu-west-2.amazonaws.com/iceberg", warehouse: "123456789012", domain: risk,
            catalog.rest.sigv4-enabled: "true", catalog.rest.signing-name: glue, catalog.rest.signing-region: eu-west-2,
            s3.region: eu-west-2, engine: native }
```

Create the Glue database with a `LocationUri` (`s3://<lake bucket>/iceberg/<domain>`): Glue's REST catalog places a new table under it
(`deploy/aws/iceberg/load-day.sh` does it with `ICEBERG_CATALOG=glue`). Name tables with letters, digits and underscores for Athena.
The IAM a reader needs: `glue:GetCatalog`, `glue:GetDatabase(s)`, `glue:GetTable(s)`, `s3:GetObject` and `s3:ListBucket` on the tables'
prefix, `kms:Decrypt`; a writer also `glue:CreateDatabase`, `glue:CreateTable`, `glue:UpdateTable` (an Iceberg commit through Glue is an
`UpdateTable`) and `s3:PutObject`; maintenance also `s3:DeleteObject`; and `lakeformation:GetDataAccess` where Lake Formation vends the
credentials. `deploy/aws/iceberg/iam/*.json` hold these policies ([CLUSTER_DEPLOYMENT.md › Drishti on AWS: ECS, EFS and S3, section 22](../admin/CLUSTER_DEPLOYMENT.md#22-iceberg-on-aws-295)).

**Proving it.** No AWS account is available to the build, so the Glue path is proven by the product owner with
`deploy/aws/iceberg/check-glue.sh`: it runs `GlueLiveTest` (skipped everywhere without `DRISHTI_GLUE_LIVE=1`), which creates a database
and a table of P&L vectors with `IcebergLoader`, reads them back through this connector (a document, and `vectors()` bit for bit) and
drops both. Its steps go to `plugins/drishti-plugin-iceberg/target/glue-live-report.txt`.

**S3 Tables.** The same test against S3 Tables' Iceberg REST endpoint (`https://s3tables.<region>.amazonaws.com/iceberg`, signing name
`s3tables`, the warehouse a table bucket ARN) is written and runs with `check-glue.sh --s3-tables <table bucket ARN>`. **Result in
2.9.5: not run** (no AWS account; the 2-hour time box was spent on the code only). It is deferred until the product owner's run;
`GlueLiveTest.s3TablesCreatesWritesReadsAndDrops` records the outcome.
