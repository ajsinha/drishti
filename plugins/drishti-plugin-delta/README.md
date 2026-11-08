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
---
connector: delta
category: plugin
summary: Reads dated kinds from a Delta Lake on local disk or S3, one table per kind partitioned by business day, without ever loading a day whole.
capabilities:
- fetch
- search
- history
- reverse-lookup
- bulk-scan
settings:
- lake
- lake-tables
- root
- domain
- kinds
- mode.*
- layout.*
- table.*
- hadoop.*
- engine
- date-column
- id-column
- doc-column
- warm-dates
- refresh-seconds
- lookback-days
- max-load-rows
- max-concurrent-reads
- max-queued-reads
- cache-mb
- doc-cache-mb
- doc-disk-cache.enabled
- doc-disk-cache.dir
- doc-disk-cache.max-gb
- doc-disk-cache.ttl
- doc-disk-cache.reset-at
- doc-disk-cache.zone
- id-map-disk.enabled
- id-map-disk.dir
- id-map-disk.max-gb
- id-map-disk.ttl
- id-map-disk.idle-release
- columns-cache-mb
- id-map-mb
- lz4-via-native
- lz4-decoder
- s3.endpoint
- s3.region
- s3.path-style
- s3.access-key
- s3.secret-key
- index.days
- index.max-memory
- index.warm
- index.any-recent-day
- index.storage
- index.max-disk
- index.reload-every
- index.block-cache
- index.kinds.*
- tls.*
- proxy.*
auth: Local files need none; S3 takes s3.access-key and s3.secret-key (${ENV} or file:) or the AWS credential chain; a private CA or client certificate through tls.* (native engine only); an outbound proxy through proxy.*.
licence: Drishti's own native Delta engine (drishti-deltalake) with Delta Kernel, Apache Hadoop client and AWS SDK for Java v2 (all Apache-2.0), bundled.
windows: Works on Windows with engine native (the default), local folders and S3 alike; engine hadoop needs winutils.exe, so it is not for the Windows QA machine. Loader tools\windows\load-delta.ps1.
doc: plugins/drishti-plugin-delta/README.md
---
# The delta connector

Plugin `delta` (`plugins/drishti-plugin-delta`): a million trades a day kept for seven years (about 1,760 business days, 1.76 billion
trade-days, 2.8 TB), with type-ahead in tens of milliseconds, a trade opening in well under a second and searches over a whole day from
its promoted columns. It never loads a day: each question reads only the files, row groups and columns it needs. Databricks or Spark
tables, with or without Unity Catalog:
[DATA_GUIDE.md](../../docs/guides/DATA_GUIDE.md#3-using-drishti-with-databricks-delta-lake-or-apache-iceberg).

## What it reads

A lake root (`./data/delta` or `s3://bucket/prefix`) holds a folder per data domain (`domain`), and under it one Delta table per kind
(or per `table.<kind>` when several connectors serve one kind, such as the trading pack's `trade_endur`, `trade_calypso` and
`trade_murex`, joined by `union: true`). The tables are listed at start and at every re-index, so a table added while the server
runs is found without a restart. Two engines: `native` (the default; `java.nio` and the AWS SDK, no Hadoop) and `hadoop` (also
`abfs://` and `gs://`). It only reads: whatever writes the lake must follow the layout below.

**The principle: never load a day.** A trade document is 6–7 KB of JSON; a day of a million is about 7 GB of text (1.7 GB of Parquet).
Every question needs very little of it:

| Question | What it really needs |
|---|---|
| open trade `MX-20000001` | one document |
| type-ahead `TRD MX-200` | the day's ids, sorted |
| `TRD where QUANTITY > 1bn order by QUANTITY desc` | the id and `QUANTITY` of every trade |
| `TRD PORTFOLIO=BOOK-RATES-3` (pick list), a page per portfolio (derived) | `PORTFOLIO` and the fields shown or summed |
| linked entities (reverse lookups) | the link fields (`COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`) |

So each day is stored so that each of these can be read on its own: the fields searches use are **promoted** to their own columns
beside the whole document, rows are **sorted by id** with each file holding its own id range, files are cut into **small row groups**,
and the connector keeps small bounded structures in memory (a day's id map, a day's column set, recent documents), never a day of
documents. The pack declares the layout ([Declaring the layout in a pack](#declaring-the-layout-in-a-pack)), the writers follow it
([Writing the lake](#writing-the-lake)), and the connector uses it when it is there and still works, slowly, when it is not.

**The lake file.** A lake's root, storage, table folders, partitions, promoted columns and vectors are described once in
`config/lakes/<lake>.yaml` and the connector names it with `lake:` (and `lake-tables:` where a kind has several tables)
([DATA_LAKE_GUIDE.md](../../docs/guides/DATA_LAKE_GUIDE.md)). Giving `root`, `domain`, `table.<kind>` and `layout.<kind>.*` in the
connector file instead is the form for tables no lake file describes (a vendor's table on S3); `drishti.py lake migrate-config` moves
such connectors into a lake file ([DATA_LAKE_GUIDE.md section 12](../../docs/guides/DATA_LAKE_GUIDE.md#12-other-lakes-packs-and-older-installations)).
A Delta connector never writes per edit: write-back keeps corrections as overlays and the loader folds approved ones into the next build
([WRITE_BACK.md](../../docs/guides/WRITE_BACK.md#52-who-performs-the-write)).

## Settings

<!-- settings:begin (generated by tools/connector_readmes.py from drishti-engine/src/main/resources/drishti/plugin-settings.yaml; edit the catalogue, not this table) -->
| Setting | Type | Default | Meaning |
|---|---|---|---|
| `lake` | string |  | The lake this connector reads (config/lakes/<lake>.yaml, docs/guides/DATA_LAKE_GUIDE.md): root, storage, catalog, table folders, promoted columns and vectors come from its file; with lake set, root, domain, table.<kind> and layout.<kind>.* are refused here. Not set: the older form (those keys in this file, one warning) |
| `lake-tables` | list |  | With lake: the lake file's entries this connector reads (trading.trade_endur), where a kind has several tables; not set: the one table of each kind |
| `root` | path | `<data dir>/delta` | Lake root (folder, or s3://bucket/prefix); default: the delta folder of the data directory |
| `domain` | string |  | The data-domain folder under the root; may not contain `..` or start with `/` |
| `kinds` | list |  | Kinds to serve (comma list); not set: every table found under the domain |
| `mode.*` | string |  | Per-kind read mode |
| `layout.*` | string |  | Per-kind layout: layout.<kind>.columns (promoted document paths), layout.<kind>.type-ahead (promoted columns whose values the type-ahead also matches: a name finds its id) |
| `table.*` | string |  | Per-kind table folder under the domain when it is not the kind's name (table.trade: trade_endur) |
| `hadoop.*` | string |  | Any Hadoop configuration property (hadoop.fs.s3a.connection.maximum) |
| `engine` | string | `native` | Reader engine: native (Delta Kernel without Hadoop; Windows), hadoop (also abfs://, gs://) or auto (native on Windows); not set: DRISHTI_DELTA_ENGINE, else native |
| `date-column` | string | `business_date` | Business date column |
| `id-column` | string | `id` | Entity id column |
| `doc-column` | string | `doc` | Document column |
| `warm-dates` | int | `3` | After start, in the background, each table's newest N dates get their id map and one document read, so the first read of a recent past date is not the slow one (cold about 5x a warm read: 40-70 ms against 10 ms on the samples, more on a large day); the warm-up reads take their own read slots, so a page never waits behind them; `0` turns it off |
| `refresh-seconds` | int | `10` | How often each table's latest version is checked; the type-ahead index is rebuilt every six checks |
| `lookback-days` | int | `10` | How far back (days) a snapshot read looks for the newest day on or before the date asked; effective tables have no limit |
| `max-load-rows` | int | `200000` | The largest day loaded whole for reverse lookups when a table has no promoted columns |
| `max-concurrent-reads` | int | `16` | Single-document reads at once (each decodes one row group of documents) |
| `max-queued-reads` | int | `4096` | Document reads that may wait for a slot; more are refused at once, and a read whose caller has gone leaves the queue |
| `cache-mb` | int |  | Memory for whole days of small tables, in MB; not set: the shared cache of every Delta connector, 5 % of the heap; set: a cache of this connector's own |
| `doc-cache-mb` | int |  | Memory for recently read documents, in MB; not set: the shared cache, 5 % of the heap; set: a cache of this connector's own |
| `doc-disk-cache.enabled` | bool | `true` | Documents already read also kept on disk (RocksDB, one per connector): answered without decompressing Parquet pages, also after a restart |
| `doc-disk-cache.dir` | path | `<data dir>/cache/<connector>/docs` | Documents read before, in a RocksDB on disk, kept across restarts (plugins/drishti-plugin-delta/README.md › Opening one entity) |
| `doc-disk-cache.max-gb` | number | `1` | Its size cap; the oldest files go first |
| `doc-disk-cache.ttl` | duration | `12h` | Entries written longer ago go (compacted hourly) |
| `doc-disk-cache.reset-at` | string | `02:00` | Daily clearing time |
| `doc-disk-cache.zone` | string | `America/New_York` | Zone of the daily clearing |
| `id-map-disk.enabled` | bool | `true` | Each table date id map written once per table version and memory-mapped: no heap per id, kept across restarts |
| `id-map-disk.dir` | path | `<data dir>/cache/<connector>/idmaps` | Id maps written once per table version and date, read through a memory map (plugins/drishti-plugin-delta/README.md › The id map of a day) |
| `id-map-disk.max-gb` | number | `5` | The most disk the id map files take; the least recently used beyond it are unmapped, then deleted, never a map in use (mapped, read or written in the last five minutes, a date open in VaR or exposure); a map alone over it is kept and warned about (OPERATIONS.md section 12.3) |
| `id-map-disk.ttl` | duration |  | Off when empty or 0; at least 24h. Drops a map not used (mapped or read) for that long, give or take 10%; never the latest and previous dates, today, a date open in VaR or exposure (OPERATIONS.md section 12.3) |
| `id-map-disk.idle-release` | duration | `30m` | A map not read for that long is unmapped (the file stays; the next read maps it again), never while a read is inside it nor for a date open in VaR or exposure: the cap then trims its file when the folder is over max-gb. At least 5m (OPERATIONS.md section 12.3) |
| `columns-cache-mb` | int |  | Memory for days' column sets, in MB; not set: the shared cache, 20 % of the heap; set: a cache of this connector's own |
| `id-map-mb` | int |  | Memory for days' id maps, in MB; not set: the shared cache, 20 % of the heap; set: a cache of this connector's own |
| `lz4-via-native` | boolean | `true` | hadoop engine only: read LZ4 files through the native engine's decoder |
| `lz4-decoder` | one of safe, fast | `safe` | native engine: the LZ4 page decoder, safe (pure Java) or fast (JNI when available) |
| `s3.endpoint` | string |  | An S3-compatible store (MinIO, Ceph); also sets path-style access and TLS by scheme |
| `s3.region` | string |  | S3 region |
| `s3.path-style` | boolean | `true` | Path-style S3 addressing; not set: the native engine uses it only with s3.endpoint, the hadoop engine always |
| `s3.access-key` | string |  | S3 access key: ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `s3.secret-key` | string |  | S3 secret key: ${ENV_VAR} or file:/path (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `index.days` | int | `1` | Business days of ids kept for type-ahead (1 = the newest day only) |
| `index.max-memory` | string | `600MB` | Most memory the kept days may use; older days drop early above it (600MB, 2GB; 0 = no cap) |
| `index.warm` | one of background, lazy | `background` | Build past days in the background after start, or on the first search for them |
| `index.any-recent-day` | boolean | `false` | A bare id on the newest day also suggests ids seen only on earlier kept days (marked last seen) |
| `index.storage` | one of heap, mmap | `heap` | heap: closed days stay in memory until max-memory, then move to memory-mapped files; mmap: closed days go to files at once |
| `index.max-disk` | string | `2GB` | Most disk the connector's index segment files may use (under drishti.index.dir); the oldest days drop above it; 0 = no cap |
| `index.reload-every` | string | `10m` | How often each past day is checked against its source and rebuilt when the source changed (a source with no version is rebuilt every cycle); 0 = never; the newest day has its own faster refresh |
| `index.block-cache` | int | `16` | Decoded 128-id blocks each on-disk day keeps in memory (a lookup reads one or two blocks from the file); 0 = none |
| `index.kinds.*` | string |  | Per-kind override of any index setting (index.kinds.<kind>.days) |

Every connector also takes `source-name`, `mode.*`, `domain`, `stale-after`, `union` (docs/guides/DATA_GUIDE.md section 9). It takes the shared `tls.*` settings (docs/guides/DATA_GUIDE.md section 10). It takes the shared `proxy.*` settings for an outbound HTTP proxy (CONNECTOR_DEVELOPER_GUIDE.md).
<!-- settings:end -->

Every key with its tier and whether it applies live: [CONFIGURATION.md, `delta`](../../docs/admin/CONFIGURATION.md#delta-tls-proxy); sizing the
caches and disk per connector: [OPERATIONS.md section 12.3](../../docs/admin/OPERATIONS.md#123-sizing-caches-and-disk-per-connector). Read
beside the table, by the writers rather than the connector: `layout.<kind>.sort-by` (`id`), `layout.<kind>.file-rows` (250,000) and
`layout.<kind>.row-group-rows` ([Row-group size](#row-group-size)). In a connector file the keys may be written nested
(`layout: {trade: {columns: [...]}}`) or flat (`layout.trade.columns`).

## Data structure

```
<root>/                                    DRISHTI_DELTA_ROOT, e.g. ./data/delta/demo or s3://risk-lake/lake
└── trading/                               the data domain (`domain`)
    ├── trade_endur/                       one Delta table per source system and kind (`table.trade`)
    │   ├── _delta_log/                    the transaction log: one JSON file per commit, plus checkpoints
    │   └── business_date=2026-09-30/      a partition per business day
    │       └── PRODUCT_TYPE=IRS_FIXFLOAT/part-00000-<uuid>.c000.snappy.parquet   sorted by id, its own id range
    ├── trade_calypso/ …                   the union's other members (`union: true`)
    └── trade_murex/ …
```

| Column | Type | Content |
|---|---|---|
| `id` | string | the entity id (`MX-20000001`); `id-column` renames it |
| `doc` | string | the whole JSON document, exactly what a view shows; `doc-column` renames it |
| `business_date` | date | the partition column; `date-column` renames it |
| `PRODUCT_TYPE` | string | the trade tables' second partition column (`--partition-by` of `lake build`), also promoted |
| promoted fields (`TRADE_ID`, `PORTFOLIO`, `COUNTERPARTY`, …) | string or double | copies of `layout.<kind>.columns`; `LEGS.CURRENCY` is the column `LEGS__CURRENCY` |

- **Id rule:** `id`, unique per business day; rows sorted by id within a day (`layout.<kind>.sort-by: id`). Delta records each file's
  minimum and maximum `id` in its log and Parquet each row group's in the footer, so a reader looking for one id skips everything but one
  row group.
- **The document stays whole; promoted columns are copies.** A view needs every field, and only the few fields questions filter, sort,
  group and link by are copied out. A writer fills both from the same document. Numbers are written as `float64`, everything else as text;
  once a table exists its column types stay.
- **Shape:** small row groups ([Row-group size](#row-group-size)), files of `file-rows` (250,000 rows, about 430 MB: four files a day,
  seven years about 7,000 files). Writers set `delta.dataSkippingStatsColumns` to `id`, `business_date` and the promoted columns: by
  default Delta would put two copies of a whole document per file into the log; with it, three days of a million trades log 56 KB.
- **Modes:** `mode.<kind>: snapshot` (every entity every day) or `effective` (a row when an entity changes, for reference data).
- **Nested data** stays inside `doc` (for `trade`: `LEGS[]`, `EVENTS[]`).

## Examples

With a lake file (`config/lakes/demo.yaml` holds the root, table folder, promoted columns and vectors):

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings:
  lake: demo
  lake-tables: trading.trade_endur    # the entry, where a kind has several tables (ENDUR, CALYPSO, MUREX)
  union: "true"
  refresh-seconds: 10
```

The pack's template, with the layout on the connector (`config/packs/banking/trading/pack.yaml`; `trade-calypso` and `trade-murex` differ
only in table and name; the server writes it to `config/connectors/trade-endur.yaml` at the first start):

```yaml
connectors:
  trade-endur:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}     # one switch for every pack's lake
    kinds: [trade]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}   # one variable moves every pack's lake
      domain: trading
      table: { trade: trade_endur }           # <root>/trading/trade_endur
      union: true                             # one source system of several: the union serves the kind
      source-name: trade-endur
      layout:
        trade:
          columns: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, TRADE_STATUS, TRADE_VERSION,
                    BUY_SELL_CODE, CURRENCY, QUANTITY, TRADE_DATE, MATURITY_DATE, COUNTERPARTY, LEGAL_ENTITY, PORTFOLIO, TRADER_ID]
          sort-by: id
          file-rows: 250000
          row-group-rows: 1000
routes:
  trade: trade-endur                          # a single read asks trade-endur first, then the other union members
```

Reference data that changes rarely is `effective` (`config/packs/core/core/pack.yaml`):

```yaml
  reference-store:
    plugin: delta
    kinds: [counterparty, counterparty-group, issuer, ccp, legal-entity, book, desk, trader, calendar, clearing-account]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}
      domain: reference
      mode.counterparty: effective            # one mode.<kind> line per kind
```

A connector without `kinds` serves every table it finds in its domain. On S3 or an S3-compatible store, a site file of the same name
replaces the pack's template wholesale, so it repeats `plugin`, `kinds` and `domain`:

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings:
  domain: trading
  table.trade: trade_endur
  union: "true"
  root: s3://risk-lake/banking               # the table is s3://risk-lake/banking/trading/trade_endur
  s3.region: us-east-1
  # credentials: the AWS chain (environment, profile, instance role), or ${ENV} / file: references:
  # s3.access-key: ${LAKE_ACCESS_KEY}
  # s3.secret-key: ${LAKE_SECRET_KEY}
  # an S3-compatible store: s3.endpoint: https://minio.bank.example   (path-style; TLS when https)
  # any Hadoop setting (engine: hadoop): hadoop.fs.s3a.connection.maximum: "200"
```

`DRISHTI_DELTA_ROOT=s3://risk-lake/banking` moves every pack's lake at once. Ready-made files: `config/connectors.examples/other/customer-lake.yaml`
(S3-compatible, keys as references), `other/delta-private-ca.yaml` (TLS), `config/connectors.examples/delta-s3/` (AWS). Build a demo lake
with `drishti.py lake demo`, `tools/load-delta.sh` (`--trades 50000` for a generated book) or `tools\windows\load-delta.ps1`.

## Troubleshooting

**Admin → Health** shows `UP`, `DOWN: cannot reach <root>/<domain>` or `DOWN: no Delta tables under <root>/<domain>`. Nothing long-lived
can be lost: each read goes to storage afresh and recovers as soon as the storage does. `GET /api/v1/admin/health` lists the caches:
`"cache": {"engine": "native", "partitions": 0, "tables": 1, "idMaps": 2, "columnSets": 1, "documents": 3, "timeTravel": 0}`
(`partitions`, whole days loaded, stays 0 for a laid-out table). **Admin → Caches → Purge** (`drishti.py server caches purge trade-endur`)
drops all of a connector's caches (its share of the heap caches, the document cache on disk, the id map files, the parsed footers); it is
safe at any time, and the right step after a lake was restored or rewritten in place. A search answered from columns says
`"scanned": <every entity of the day>, "partial": false`.

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: cannot reach <root>/<domain>` | folder or bucket not reachable: credentials, region or endpoint for S3 | `aws s3 ls s3://<bucket>/<path>/` with the same credentials |
| `DOWN: no Delta tables under <root>/<domain>`; views say *No data available* | the lake was not built, or `DRISHTI_DELTA_ROOT`/`domain` point elsewhere | build it, or fix the root; tables built while the server runs are found at the next re-index |
| `UP (engine: native; not laid out as the pack declares: trade (12 of 16 columns); searches read documents)` | the writer did not write the promoted columns | `tools/lake/maintain.py relayout --root … --domain …` |
| searches say `partial: true` | a field the query reads is not promoted, or the table is not laid out | add it to `layout.<kind>.columns` and relayout |
| searches say `partial: true` with `failed: [{"source": "trade-endur", …}]` | a date or the table's ids could not be read; type-ahead keeps the ids listed before | the reason is in the answer and in Health |
| `DEGRADED: cannot read trade 2026-09-30: …`; views of that date fail with `DRS-1003 … failed reading` | pages in a codec the engine does not decompress (BROTLI, LZO), a page that does not decode, a truncated or missing file, or a log missing a commit | the reason says which ([Parquet codecs](#parquet-codecs)); restore the file or rewrite the date. `UP` again once a read of the date succeeds or the table gets a new version |
| opening a trade takes seconds | large row groups, or the day's id map is being read | rewrite with small row groups (`relayout --force`); the second open is fast |
| `DRS-1004 timed out` on a single read | the read deadline (`drishti.sources.fetch-timeout`, 10 s) passed: large row groups or distant storage | smaller row groups; check `p99Ms` in Health |
| the first search of the morning is slow | the newest day changed and its columns are loading | it loads in the background after each re-index |
| an old picked date falls back to another store | the date is more than `lookback-days` past the newest partition before it | load that date, or raise `lookback-days` |
| *known at* shows the latest data | the lake was copied without file times (Delta resolves instants against `_delta_log` modification times) | copy with `cp -p` / `rsync -t`; on S3 times are upload times |
| the log grows by megabytes a day | statistics include the document | set `delta.dataSkippingStatsColumns` (maintenance sets it) |
| `domain escapes the Delta root` under `failedToStart` | `domain` contains `..` or starts with `/` | a plain folder name |

## Declaring the layout in a pack

The layout belongs to the kind, so the pack that owns the kind declares it on the Delta connectors that store it (or, with a lake file,
in that file's table entry: [DATA_LAKE_GUIDE.md section 2.4](../../docs/guides/DATA_LAKE_GUIDE.md#24-columns-promote-vectors-flatten-id-date-latest-by)).
The trading pack's template is in [Examples](#examples); the pack loader flattens the nested keys into the connector's flat settings.

| Key | Meaning |
|---|---|
| `layout.<kind>.columns` | document paths to promote, as written in the document (`QUANTITY`, `LEGS.CURRENCY`); the column is the path with `.` as `__` |
| `layout.<kind>.type-ahead` | promoted paths whose values the type-ahead also matches, read from the day's columns: `USEQ Ford` finds `F`, a legal name finds its LEI (us-equities' us-company: `[NAME, LEI, LEI_LEGAL_NAME]`) |
| `table.<kind>` | the kind's table folder when it is not the kind's name (`trade_endur`), so several connectors serve one kind side by side |
| `layout.<kind>.sort-by` | `id` (the default and right for entity tables): rows sorted by id within each business day |
| `layout.<kind>.file-rows` | rows per file (250,000), each file a contiguous id range |
| `layout.<kind>.row-group-rows` | rows per row group, for the Python writers only (default 10,000; the trading pack 1,000) |

### Row-group size

This is the one statement of the row-group size; other documents link here. Reading one document decodes one row group of the `doc`
column, so small groups make single reads cheap; column scans barely notice.

| Writer | Row group | Set by |
|---|---|---|
| `drishti.py lake build`, `lake demo`, `build_demo_lakes.py` and the parallel loader into **Delta** (the Java writer) | **256 KB**, pages of 64 KB | `DRISHTI_LAKE_ROW_GROUP_KB`, `DRISHTI_LAKE_PAGE_KB`; the AWS deployment sets 1 MB ([CLUSTER_DEPLOYMENT.md section 7.1](../../docs/architecture/CLUSTER_DEPLOYMENT.md#71-the-numbers)) |
| the parallel loader into **Iceberg** | 1 MB, unless the layout sets `rowGroupBytes` | the loader's configuration ([DATA_LAKE_GUIDE.md section 3.3](../../docs/guides/DATA_LAKE_GUIDE.md#33-the-parallel-loader)) |
| the Python writers on `tools/samplegen/layout.py` (`ingest_jsonl.py`, `samplegen/lake.py`, the banking `make_data.py`, `maintain.py relayout`) | `layout.<kind>.row-group-rows` **rows** | the pack's layout |

`row-group-rows` therefore does not change what `lake build` writes; the byte size does. 256 KB holds a few dozen to a few hundred trade
documents, so one open decodes a small group either way.

### Choosing the columns

Promote every field a question over the whole book reads; a field that is not promoted makes that question read documents (slow,
`partial`). Numbers cost 8 bytes a row before compression, repeated text almost nothing. Small kinds (curves, counterparties, books) need
no layout.

| Used by | Fields (trading pack) |
|---|---|
| pick-list columns (`columns:` in `pack.yaml`) | `SOURCE_SYSTEM`, `PRODUCT_TYPE`, `BUY_SELL_CODE`, `CURRENCY`, `QUANTITY`, `MATURITY_DATE`, `PORTFOLIO`, `COUNTERPARTY` |
| searches and the search form | `TRADE_ID`, `PRODUCT_SUB_TYPE`, `TRADE_STATUS`, `LEGAL_ENTITY`, `TRADER_ID`, `TRADE_DATE`, … |
| derived kinds and reverse lookups | `COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`, `TRADER_ID` |

## Writing the lake

Drishti only reads the lake; whatever writes it follows the layout. The repository's writers do: `drishti.py lake build` and the parallel
loader ([DATA_LAKE_GUIDE.md section 3](../../docs/guides/DATA_LAKE_GUIDE.md#3-loading-three-ways),
[TOOLS.md section 4.13](../../docs/guides/TOOLS.md#413-the-parallel-lake-loader)), `tools/samplegen/load_rows.py` for JSON Lines rows
(`{"domain", "kind", "table", "id", "date", "doc"}`, [DATA_GUIDE.md](../../docs/guides/DATA_GUIDE.md#2-data-formats-json-lines-is-the-standard)),
`tools/samplegen/bulk_trades.py --trades N --days D` for scale tests (one JSONL file a day, then `lake build`; memory stays flat), and
`maintain.py relayout`, which rewrites an existing table into its layout one day at a time.

**Your own loader** (Spark, Databricks) writes the same thing. One business day in Spark:

```python
from pyspark.sql import functions as F
day = (spark.read.json(source)
       .withColumn("doc", F.to_json(F.struct("*")))
       .select(F.col("TRADE_ID").cast("string").alias("id"), "doc", F.lit("2026-09-30").cast("date").alias("business_date"),
               "PRODUCT_TYPE", "TRADE_ID", "SOURCE_SYSTEM", "CURRENCY", "QUANTITY", "COUNTERPARTY", "LEGAL_ENTITY", "PORTFOLIO"))  # … every promoted field
(day.repartitionByRange(4, "id").sortWithinPartitions("id")      # four files, each its own id range, sorted
    .write.format("delta").mode("overwrite")
    .option("replaceWhere", "business_date = '2026-09-30'")      # replaces that date only; history stays
    .option("parquet.block.size", 8 * 1024 * 1024)                # small row groups
    .partitionBy("business_date", "PRODUCT_TYPE")
    .save("s3a://risk-lake/trading/trade_endur"))
```

and once, on the table:

```sql
ALTER TABLE delta.`s3a://risk-lake/trading/trade_endur`
  SET TBLPROPERTIES ('delta.dataSkippingStatsColumns' = 'id,business_date,PRODUCT_TYPE,TRADE_ID,…');   -- every promoted column
```

What matters, whatever the tool: **the promoted columns hold exactly the document's values**, **each day is sorted by id and split into
files of disjoint id ranges**, **row groups are small**, **statistics leave out `doc`**. On Databricks, `OPTIMIZE … ZORDER BY (id)` or
liquid clustering on `id` keeps the order. A replaced date stays readable through *known at* until it is vacuumed.

## How the connector reads

Through Delta Kernel, without Spark and (native engine) without Hadoop.

- **The file list.** Every `refresh-seconds` (10) the connector asks for the table's latest version and its files by business date,
  replaying the log from the last checkpoint: seven years (7,200 files) list in 84 ms. It also notes files carrying deletion vectors.

### The id map of a day

For each business day asked about, the connector builds an **id map**: every id, sorted, with the index of its file, read from the `id`
column only (a million ids: about 75 MB, under a second). Maps are kept by memory (`id-map-mb`). With `id-map-disk.enabled` (the default)
each table date's map is also written once per table version under `id-map-disk.dir` and read through a memory map: no heap per id, and
after a restart a date's first read needs no scan (3 ms instead of 729 ms on 362,804 ids).

The folder looks after itself: a map leaving the cache is unmapped once its reads finish; a new table version's maps replace those older
than the previous version (which stays, so a request started on it finishes); half-written files go at start; and the folder is kept
under `id-map-disk.max-gb` (5), least recently mapped first. A map is never evicted while it is being read, was mapped or read within
`id-map-disk.idle-release` (30m) or written in the last five minutes, or belongs to a date open in VaR or exposure; a map alone over the
cap is kept and warned about once. `id-map-disk.idle-release` unmaps an unread map (the file stays). `id-map-disk.ttl` (off; at least
`24h`) drops maps unused that long, never the latest and previous dates, today or an open date. Admin → Caches shows each drop's reason
(`expired (ttl)`, `evicted (max-gb)`, `replaced (new version)`) and the counts `idMapsExpired`, `idMapsEvicted`, `idMapsReplaced`,
`idMapDiskMB`. Sizing: [OPERATIONS.md section 12.3](../../docs/admin/OPERATIONS.md#123-sizing-caches-and-disk-per-connector). Rows
deleted through a deletion vector are not in the map.

### Opening one entity

`TRD MX-20000017` on 2026-09-30: the file list gives the day's files (for a past date, the newest day on or before it within
`lookback-days`); the id map gives the file (a binary search); the file is read with `id = 'MX-20000017'`, so Parquet decodes only the
one row group whose id range can hold it, and only `id` and `doc` (the page index, `-Ddrishti.parquet.page-index`, narrows it to pages).
Each file's footer is parsed once and kept (`-Ddrishti.parquet.footer-cache-mb`, 64). The document goes to the heap cache
(`doc-cache-mb`) and the **document cache on disk** (`doc-disk-cache.*`, a RocksDB per connector): a document read once is answered
without decompressing Parquet, also after a restart. Its entries are keyed by table version, so never stale; it is bounded by size
(`max-gb`, 1), age (`ttl`, 12h) and a daily clearing (`reset-at`, 02:00). If its folder is locked (the previous process still stopping)
the connector reads without it and retries every 30 s for 10 minutes.

At most `max-concurrent-reads` (16) single reads run at once (each decodes a row group of documents) and at most `max-queued-reads`
(4096) wait; more are refused at once (*the server is busy, try again*), and a read whose caller has timed out leaves the queue.

### Type-ahead

The newest day's ids live in Drishti's search index, rebuilt from the newest id map every six `refresh-seconds`, no document read: ids
are sorted per kind, so a prefix is a binary search and a walk; substrings scan up to the limit. 29 ms over HTTP with a million ids. When
a kind's newest day cannot be read at a rebuild, it keeps the ids listed before and searches say partial with the reason.

**Several days.** With `index.days: 5` (and `index.storage: mmap` for a lake searched often by past date) the last five business days'
ids are kept, so a type-ahead on a past date answers from that date's ids
([ARCHITECTURE.md section 9.1](../../docs/architecture/ARCHITECTURE.md#91-several-business-days)). A new commit rebuilds them in the
background; a restated past day is caught every `index.reload-every` (10m), and **Admin → Connectors → Index** reloads a day by hand
([OPERATIONS.md section 12.2](../../docs/admin/OPERATIONS.md#122-sizing-the-multi-day-type-ahead-index)).

### The column set of a day and reverse lookups

A day's first search reads its **column set**: the id and every promoted column (repeated text shared; a million rows of 19 columns:
about 230 MB, kept by `columns-cache-mb`). The newest day's is loaded in the background after each re-index; another day's first search
waits for it (seconds), later ones take a few hundred milliseconds.

*Linked entities* and impact ("which trades reference `NS-SUMMIT-NY`?") are answered from the day's promoted text columns, no document
read. A table without promoted columns indexes identifier-like values (capitals and digits with a dash, up to 64 characters), loading a
day whole only when it has at most `max-load-rows` (200,000) rows; otherwise it reports no referrers.

## Searches, pick lists, derived kinds and impact over columns

These run in Drishti's engine: a source says which fields it keeps as columns for a kind and returns a day's values column by column
(Delta and Aerospike do; any source can). The router takes the first source in read order that holds the day, and falls back to
documents when none can answer.

- **Searches and pick lists** (`TRD where currency = 'USD' and notional > 500m order by notional desc limit 20`, `TRD book=BOOK-RATES-3`)
  are answered from columns when every field read (condition, ordering, result and pick-list columns) is promoted: the condition runs
  over every row with the same Rachana semantics as on documents, the matches are sorted, and the answer is exact over the whole day
  (`partial: false`). Role redaction applies as on documents: a field the role sees masked is masked in the columns too, so it can neither
  match nor show. Otherwise the search reads documents, up to `drishti.search.max-scan` (20,000), and says `partial`.
- **Derived kinds** ([PACK_DEVELOPER_GUIDE.md](../../docs/guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds))
  group and aggregate over the column arrays when every expression is a promoted path; only listed members (`max-members`) get a small
  document. Otherwise the day is streamed in one pass, file by file, keeping only the values needed; with `store: disk` (the trading pack's
  `trade-pages`) members go to a sorted store under `<data dir>/derived/<connector>` so pages are complete at any size. Results are kept
  until the sources' data version changes (checked every `version-check`, 10 s), then rebuilt in the background while the old pages serve.
- **Impact (F8)** finds the referrers over columns, sums the measure over all of them and follows promoted roll-up fields; it lists 200
  entities per group with the count of the rest, totals covering every one.

## Seven years of history

Nothing in the read paths grows with history: a read concerns one day (its id map, its column set, one row group), the file list comes
from the last checkpoint, and the caches hold days by memory and drop the least recently used. A user moving to 2019 waits once for that
day's id map (about a second) and, for a search, its column set; then the day is as fast as today. What grows is the lake (about 1.6 GB a
business day) and its log, which maintenance checkpoints.

**History queries** (`TRD 1000015 30`, [DATA_ARCHITECTURE.md](../../docs/architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time))
list the entity's dates from the partitions in memory whose id map holds it, and read only those, eight at once; for an effective table
the dates are its change dates; `knownAt` reads each day as the table stood then (time travel). History reads documents even when every
field asked is promoted.

## Memory

| Held in memory | Per million trades | Bounded by | Default |
|---|---|---|---|
| id map of a day | about 75 MB of heap; none when memory-mapped (the default) | `id-map-mb` | 20 % of the heap, shared |
| column set of a day (19 columns) | about 230 MB | `columns-cache-mb` | 20 % of the heap, shared |
| documents read recently | 7 KB each | `doc-cache-mb` | 5 % of the heap, shared |
| documents read before, on disk | — | `doc-disk-cache.max-gb` | 1 GB per connector |
| type-ahead index (newest day) | about 150 MB | `index.*` | the newest day |
| whole days of small tables | — | `cache-mb` | 5 % of the heap, shared |

Delta connectors that leave these four unset share one cache of each, sized from the heap (half of it in all, however many connectors
run); a connector that sets one keeps its own. Admin → Caches shows each shared cache against its cap. A million trades a day used
2–3.5 GB of heap; give the server at least 8 GB. Measured figures and sizing:
[BENCHMARK.md section 8.1](../../docs/admin/BENCHMARK.md#81-a-million-trades-a-day-on-delta) and
[8.4](../../docs/admin/BENCHMARK.md#84-sizing-for-millions-of-trades-a-day); the stores compared:
[section 9](../../docs/admin/BENCHMARK.md#9-the-stores-compared-10000-to-50000-trades-a-day).

## Keeping the lake in shape

`drishti.py lake maintain` (or Admin → Lakes) keeps each table bounded and laid out from its own settings: retention in business days,
compaction, checkpoints, vacuum and time travel
([DATA_LAKE_GUIDE.md section 2.6](../../docs/guides/DATA_LAKE_GUIDE.md#26-retention-maintenance-time-travel-properties)); run it nightly.
For a laid-out table, compaction does not run Delta's plain merge (which would break the id ranges): it rewrites, sorted, only the days
that drifted (an intraday file whose ids overlap, a file over `file-rows`, a missing promoted column) and keeps file statistics on the
id, the date and the promoted columns. `relayout` does the same on demand for a table written another way:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py relayout --root data/delta/demo --domain trading
```

**On S3** nothing maintains a Delta table by itself: run the maintenance against the `s3://` root, or let Databricks `OPTIMIZE`. Never use
S3 lifecycle **expiry** on a Delta table (it deletes files the log still points to); storage-class transitions are safe.

## Limits and trade-offs

- **The first search on a day other than the newest waits for that day's columns** (seconds at a million trades); then it is kept while
  memory allows.
- **Only promoted fields are fast.** Any other field reads documents, at most 20,000, and says `partial`.
- **Deletion vectors switch columns off for a table:** single reads and type-ahead stay correct, searches and reverse lookups read
  documents. Compaction rewrites the files without them.
- **Effective-mode tables are not read as columns** (each entity's latest row would have to be resolved across days); they are small.
- **Live overlays are not in column searches:** with a live source serving the kind ahead of the lake, a view shows the live document
  while a column search reads the lake's day.

## Engines: native and Hadoop

Delta Kernel does no I/O itself; an *engine* lists the log, reads commits and Parquet and evaluates expressions. `engine` (or
`DRISHTI_DELTA_ENGINE` for every connector) picks one:

| `engine` | What reads the lake | Reads | Use it for |
|---|---|---|---|
| `native` (default) | `drishti-deltalake`: `java.nio` locally, the AWS SDK v2 for S3, its own Parquet handler | local paths, `file:` URIs (Windows drive letters, UNC), `s3://`, `s3a://` | everything, and Windows (no `winutils.exe`) |
| `hadoop` | Kernel's default engine over Hadoop's `FileSystem` | the above (S3 through S3A), plus `abfs://`, `gs://`, HDFS | lakes in Azure, Google Cloud Storage or HDFS |
| `auto` | `native` on Windows, `hadoop` elsewhere | | a configuration shared by Windows and Linux |

The native engine reads each footer once, prunes row groups by the predicate's statistics, decodes only the asked columns and loads no
Hadoop class. Both engines read checkpoints, time travel, deletion vectors and partition values the same, and every Delta test runs on
both; reads are equal within noise, the native one starts a table's first read faster. On S3 the native engine uses one pooled client
per connector, `ListObjectsV2` and ranged GETs (a small read fetches at least `s3.read-block-kb`, 1 MiB, and serves the next small reads
from it). A native connector with an `abfs://` or `gs://` root refuses to start, naming `hadoop`. Iceberg has its own engines
([ICEBERG_CONNECTOR.md section 17](../drishti-plugin-iceberg/README.md#engines-and-caches)).

### Parquet codecs

| Codec | `native` | `hadoop` |
|---|---|---|
| UNCOMPRESSED, SNAPPY, GZIP, ZSTD, LZ4_RAW | read | read |
| LZ4 (the deprecated codec: Hadoop-framed pages or a raw block) | read | read, through the native decoder |
| BROTLI, LZO | refused, naming table, file and codec | refused |

For LZ4 the native decoder accepts Hadoop's framing only when the lengths add up exactly to the page, else decodes a single raw block, as
Arrow does; `lz4-decoder: fast` uses JNI where it loads. Hadoop's own LZ4 codec fails on raw blocks, so the `hadoop` engine reads each
file's footer once and routes LZ4 files on local disk or S3 to the native decoder (`lz4-via-native: false` turns it off); on `abfs://`,
`gs://` and HDFS rewrite them. A date that cannot be read (BROTLI, a corrupt page, a truncated file) fails its reads with
`DRS-1003 … cannot be read: … rewrite the date with Snappy or ZSTD`, its searches say `partial`, and Health shows `DEGRADED`. Rewrite a
date with `maintain.py relayout --root data/delta/demo --domain trading --kind trade --dates 2026-09-30 --force`.

## Delta Lake on AWS S3

The same connector with `root: s3://<bucket>/<prefix>`, each table at `s3://<bucket>/<prefix>/<domain>/<table>` exactly as on disk;
layout, id maps, caches, VaR and exposure are unchanged.

```yaml
plugin: delta
kinds: [pnl-vector, scenario-pv, risk-hierarchy, var-limit]
settings:
  root: s3://${DRISHTI_DELTA_S3_BUCKET:drishti-lake}/${DRISHTI_DELTA_S3_PREFIX:delta}
  domain: risk
  engine: native
  s3.region: ${AWS_REGION:us-east-1}
  layout.pnl-vector.vectors: PNL
```

- **Credentials and IAM.** No key in the file: without `s3.access-key` the AWS default chain applies (on ECS the task role). A reader
  needs `s3:ListBucket` on the prefix and `s3:GetObject` on `<bucket>/<prefix>/*`; a loader also `s3:PutObject`, `s3:DeleteObject` and
  `s3:AbortMultipartUpload`; with SSE-KMS, `kms:Decrypt` (and `kms:GenerateDataKey` to write).
- **Writing.** `drishti.py lake build` with an `s3://` lake and `lake publish`, or the parallel loader with an `s3://` target
  ([TOOLS.md section 4.11](../../docs/guides/TOOLS.md#411-lake-publish-a-built-day-into-a-delta-table-in-s3)); commits rely on S3's
  conditional PUTs (`deploy/aws/delta-lake/check-conditional-writes.sh` checks a bucket).
- **The AWS side.** `deploy/aws/delta-s3/delta-s3.sh` (and `.ps1`) creates a private encrypted bucket, prefix and IAM policy, checks them
  by reading one table end to end, and removes them ([its README](../../deploy/aws/delta-s3/README.md),
  [TOOLS.md section 4.14](../../docs/guides/TOOLS.md#414-the-aws-connectors-tool-scripts-s3-tables-and-delta-lake-on-s3)). A production lake
  past 200 TB (KMS, VPC endpoint, tiers, loads, maintenance, alarms):
  [CLUSTER_DEPLOYMENT.md](../../docs/architecture/CLUSTER_DEPLOYMENT.md#appendix-c-a-high-performance-delta-lake-on-aws-past-200-tb).
- **Tested** against S3Mock with the shipped template's settings, and with `s3a://` on both engines; not yet run against a live AWS account.

## Security: TLS and credentials

A store with a certificate from a private CA (MinIO, Ceph, an internal gateway) takes the shared `tls.*` settings
([DATA_GUIDE.md section 10](../../docs/guides/DATA_GUIDE.md#101-the-settings)), read by the **native engine** only:

| Setting | Meaning |
|---|---|
| `s3.endpoint: https://minio.example.com:9000` | TLS on; `tls.*` with no or an `http://` endpoint is refused at start |
| `tls.ca-file` / `tls.truststore` | trust a private CA (`tls.trust-jvm-default: true` keeps the public ones) |
| `tls.cert-file` + `tls.key-file`, or `tls.keystore` | a client certificate, for a gateway that asks for one |
| `tls.verify-hostname: false` | the chain is verified, the name is not (a warning at every start) |
| `s3.access-key`, `s3.secret-key` | `${ENV}` or `file:` references, or the AWS credential chain |

With `engine: hadoop` (S3A has no per-connector CA) `tls.*` is refused at start; import the CA into the JVM truststore instead. Health
notes a certificate with under 30 days left: `UP (engine: native; TLS certificate CN=drishti (tls.cert-file) expires in 19 days …)`.
Check a store with `openssl s_client -connect minio.example.com:9000 -CAfile ca.pem -verify_return_error </dev/null`.

| Message | Cause |
|---|---|
| `tls.* is set but s3.endpoint is not https://` (or `tls.enabled is true but …`) | `tls.*` with no endpoint or an `http://` one |
| `tls.* is read by the native engine only: …` | `engine: hadoop` with `tls.*` |
| `tls.ca-file '…': file not found` | a wrong path |
| `PKIX path building failed … unable to find valid certification path` in the table's problem text | the store's certificate is not signed by a CA in `tls.ca-file` |

### Outbound proxy

The `proxy.*` settings are shared by every HTTP-based connector
([DATA_GUIDE.md section 10, proxy](../../docs/guides/DATA_GUIDE.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)); `proxy: none` overrides
the site default. The native engine uses the AWS SDK's proxy configuration; the Hadoop engine gets `fs.s3a.proxy.*` (no no-proxy list:
set only when the endpoint is not on it). `https://` endpoints are tunnelled with `CONNECT` and keep `tls.*`. Health adds
`via proxy host:port (source)`.

## Checklist for production

1. Declare `layout.<kind>.columns` for every kind with more than a few hundred thousand entities a day: the fields of its pick lists,
   searches, derived kinds, impact measures and links.
2. Make your loader write the layout and set `delta.dataSkippingStatsColumns`; Admin → Health shows no `not laid out`.
3. Run lake maintenance nightly (retention, compaction, checkpoints).
4. Give the server at least 8 GB of heap per million entities a day.
5. Measure on your data: `GET /api/v1/search?q=…` reports `elapsedMs`, `scanned` and `partial`; a view reports its `timings`.

## See also

- [Several connectors for one kind (a union)](../../docs/guides/DATA_GUIDE.md#83-several-connectors-for-one-kind-a-union), [Connector files](../../docs/guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors) and [TLS](../../docs/guides/DATA_GUIDE.md#10-tls-and-the-outbound-proxy).
- [The data lake and data load guide](../../docs/guides/DATA_LAKE_GUIDE.md): lake files, loading, `.ready` files, maintenance.
- [BENCHMARK.md, Trades at scale](../../docs/admin/BENCHMARK.md#8-trades-at-scale) and [OPERATIONS.md, Memory and caches](../../docs/admin/OPERATIONS.md#12-memory-and-caches).
- [The connector developer's guide](../../docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md) (the README standard).
