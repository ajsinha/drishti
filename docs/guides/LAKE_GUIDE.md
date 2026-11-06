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
# The lake guide: one file per lake

A lake is configuration of its own. One file, `config/lakes/<lake>.yaml`, says where a lake is and describes every table in it. Every
**writer** (the parallel loader, the data inbox, `lake build`, the demo lakes) creates and writes tables exactly as that file says, and
every **reader** (the Delta and Iceberg connectors, and through them BI, the pivot engine and Export data) finds its tables through it.
Nobody else holds a root, a table path, a partition column or a promoted column. The design and its decisions are in
[LAKE_CONFIG.md](../architecture/LAKE_CONFIG.md).

## 1. The file

```yaml
lake: main                                  # its name: the file's name; connectors and loads refer to it
format: delta                               # delta | iceberg; a table may say otherwise
root: s3://${DRISHTI_LAKE_BUCKET}/delta     # a local folder, s3://bucket/prefix, or mem://<store> for an in-memory store
storage:                                    # how to reach it: the platform's role, or ${ENV} references; never a secret written here
  region: ${AWS_REGION:us-east-1}
catalog: none                               # Iceberg: none (path tables) | rest | glue | s3tables, with its settings
defaults:                                   # every table unless it says otherwise
  sort-by: id
  file-rows: 250000
  row-group-rows: 1000
tables:
  trading.trade:                            # <domain>.<kind>
    partition-by: [PRODUCT_TYPE]            # after business_date, which is always first; at most four
    promote: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, COUNTERPARTY, PORTFOLIO, QUANTITY]
  risk.pnl-vector:
    promote: [VALUE_TYPE, GRAIN, TRADE_ID, POSITION_ID, CURRENCY, DESK]
    vectors: [PNL]
```

**Every table is partitioned by `business_date` first, always.** It is not a setting and cannot be turned off: `partition-by` names only
what comes after it (at most four columns). Naming `business_date` there is refused.

A table's entry may hold:

| Key | Means |
|---|---|
| `kind` | the kind, when the entry is not named after it: several tables of one kind (`trading.trade_endur`, `trading.trade_murex`, each `kind: trade`) |
| `path` | the table's folder under the root (default `<domain>/<entry name>`) |
| `format` | delta or iceberg, when it differs from the lake's |
| `partition-by` | the partition columns after `business_date` (Delta only: an Iceberg table of the layout is partitioned by business date alone) |
| `promote` | the promoted columns: real, typed columns beside the document |
| `vectors` | the list-of-numbers fields kept as `list<double>` columns |
| `flatten` | nested fields written as columns (`field.path: COLUMN`) |
| `id`, `date` | the id field and the business-date field of the kind's documents |
| `latest-by` | which of two rows of one id on one date wins (a timestamp field) |
| `load-mode` | `replace` (the default) or `append`: section 4 |
| `sort-by`, `file-rows`, `row-group-rows`, `file-size-mb`, `row-group-mb`, `compression`, `vector-compression` | the file layout |
| `retention`, `maintenance`, `time-travel` | how many dates are kept; compaction, vacuum and checkpoints; how long old versions stay readable |
| `properties` | Delta or Iceberg table properties, passed through as they are |

Values may name environment variables: `${NAME}`, `${NAME:default}`. A variable that is not set and has no default refuses the file,
naming it. An unknown key, a format that is not delta or iceberg, a fifth partition column, a load mode that is not replace or append, a
secret written as a value (`secret-key: abc`), or a `lake:` that differs from the file's name: each refuses the file with the key named.

The lake folder is `config/lakes/` (the system property `drishti.lakes.dir` or the variable `DRISHTI_LAKES_DIR` say otherwise). A server
may know several lakes: a shared lake, a Murex lake, an in-memory store. The demo lake's file also ships inside the server (a copy of
`config/lakes/demo.yaml`), so a server started in another working folder, a container or a cluster node knows it; a `demo.yaml` in the lake
folder wins. A reference may nest and may name a server setting: `${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}` is the
variable, else the data folder's `delta` (`drishti.data.dir` as a system property or as `DRISHTI_DATA_DIR`).

Example lake files for every example connector are in `config/lakes.examples/` (Delta in S3, Iceberg path tables, AWS S3 Tables, a REST
catalog behind a private CA, an in-memory store), each beside its connectors in `config/connectors.examples/`.

## 2. Who refers to it

| Who | How |
|---|---|
| Delta and Iceberg connectors | `lake: main` in the connector's settings, and its kinds. Root, table folders, storage, catalog, promoted columns and vectors come from the lake file; the connector file keeps only reading concerns (caches, refresh, warm dates, the source name). Where a kind has several tables, `lake-tables: trading.trade_endur` names the entries |
| The parallel loader (`LoadConfig`) | `target.lake: main` and `target.kind` (and `target.table` when a kind has several). Partitioning, promoted columns, vectors, id, date, latest-by, file layout and load mode come from the lake file. A key of the load file that says otherwise (`source.partition-by: [DESK]` against `[PRODUCT_TYPE]`) is refused with both named |
| `drishti.py lake build` | `--lake main` (a name). `--lake-root DIR` writes the same layout under another folder (a scratch or test lake). `--partition-by`, `--promote`, `--vector` and `--key` must agree with the lake file or the run is refused |
| The data inbox | a rule's `lake:` (or the inbox's `lake:`, `demo` as shipped) names a lake. A kind the lake file has no entry for gets its pack's recommended entry copied into the file first, said in the log; the table is then created exactly as the file says |
| The demo lakes | `config/lakes/demo.yaml` describes the demo lake like any other: `drishti.py lake demo` and `tools/build_demo_lakes.py` write through it (`--lake demo --lake-root <folder>`) |
| The in-memory lake | a lake whose root is `mem://<store>` (section 6) |
| Admin → Lakes | lists every lake and table from the files, read-only |

A writer never creates a table its lake file does not describe: a load for a kind with no entry is refused, naming the lake file and the
tables it has.

## 3. Examples

### 3.1 The demo lake (`config/lakes/demo.yaml`, shipped)

```yaml
lake: demo
format: delta
root: ${DRISHTI_DELTA_ROOT:./data/delta}
defaults: {load-mode: replace}
tables:
  trading.trade_endur:
    kind: trade
    partition-by: [PRODUCT_TYPE]
    promote: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, TRADE_STATUS, ...]
    id: TRADE_ID
    sort-by: [id]
    file-rows: 250000
    row-group-rows: 1000
  trading.trade_calypso: {kind: trade, partition-by: [PRODUCT_TYPE], ...}
  trading.trade_murex: {kind: trade, partition-by: [PRODUCT_TYPE], ...}
  risk.pnl-vector:
    promote: [VALUE_TYPE, GRAIN, TRADE_ID, POSITION_ID, ...]
    vectors: [PNL]
    id: VECTOR_ID
    date: BUSINESS_DATE
  ...
```

The trades' `PRODUCT_TYPE` partition, which `build_demo_lakes.py` once wrote on its command line, is here and nowhere else.

### 3.2 A Delta lake in S3

```yaml
lake: main
format: delta
root: s3://${DRISHTI_DELTA_S3_BUCKET:drishti-lake}/${DRISHTI_DELTA_S3_PREFIX:delta}
storage:
  region: ${AWS_REGION:us-east-1}            # credentials: the AWS default chain (the task role, a profile); never here
tables:
  trading.trade_endur: {kind: trade, partition-by: [PRODUCT_TYPE], promote: [TRADE_ID, PRODUCT_TYPE, COUNTERPARTY, PORTFOLIO]}
  risk.pnl-vector: {promote: [VALUE_TYPE, TRADE_ID, DESK], vectors: [PNL]}
```

and its connectors (`config/connectors/trade-endur.yaml`, `risk-store.yaml`):

```yaml
plugin: delta
kinds: [trade]
settings:
  lake: main
  lake-tables: trading.trade_endur
  source-name: trade-endur
---
plugin: delta
kinds: [pnl-vector]
settings:
  lake: main
  refresh-seconds: 30
```

An S3-compatible store adds `endpoint:` and `path-style: true` under `storage:`, keys as `${ENV}` references.

### 3.3 An Iceberg lake over a REST catalog

```yaml
lake: iceberg-main
format: iceberg
root: s3://acme-warehouse/iceberg
catalog:
  type: rest
  uri: https://catalog.acme.internal/api/catalog
  warehouse: acme
  token: ${ICEBERG_REST_TOKEN}              # a secret only as a reference
tables:
  market.ir-curve: {promote: [CURVE_ID, CURRENCY, TENOR]}
```

An Iceberg table of the layout is partitioned by business date alone: `partition-by` is refused here; keep the fields as `promote:` columns.

### 3.4 An in-memory store

```yaml
lake: feeds
root: mem://feeds
memory:
  max-mb: 2048                              # the hard cap; the launchers add it to the JVM's direct-memory limit
  contract: vendor-news-no-disk             # shown on every refusal and on Admin -> Lakes
tables:
  news.news: {promote: [TICKER, PUBLISHED_AT]}
```

## 4. Replace and append

**`replace`** (the default): a load replaces each business date it holds, in one commit per date, so repeating a load leaves the same
data. **`append`**: a load adds its rows to the dates it holds, and keeps the same safety: each load carries a **batch id** recorded in
the table's commit (Delta: a transaction id `drishti-batch:<batch>:<date>`; Iceberg: the snapshot summary `drishti.batch`). A batch
already committed for a date is never appended twice: a repeated file, or a crash and a retry, skips the dates done. Two rows of one id on
one date are resolved by `latest-by` when read.

```yaml
tables:
  risk.intraday-pv:
    load-mode: append
    latest-by: COMPUTED_AT
```

The loader takes the batch id from `target.batch-id` (an append without one is refused); the data inbox sends one for every load (the
files' names and sizes, so the same file dropped again is the same batch); `lake build --engine java --batch-id ID` passes one. The
rule is implemented once, in the parallel loader: `lake build`'s Python path refuses a table whose lake file says `append` and names
`--engine java --batch-id`. (`lake build --mode append` into a folder, the older form without a lake file, keeps its own append with no
batch record.)

## 5. Checks

At start, and with `drishti.py lake check`:

- every Delta or Iceberg connector names a known lake (a connector that names a lake and also sets `root`, `domain`, `table.<kind>` or
  `layout.<kind>.*` fails, naming both);
- every table of a lake file that is on a local disk matches its file: the format, the partition columns (`business_date` first), the
  promoted columns present. A difference is reported, never rewritten: rebuild the table or correct the file. A table in S3 or in memory
  is checked by its connector when it opens it.

```text
$ drishti.py lake check
  error demo/trading.trade_endur: partitioned by [business_date] on disk; the lake file says [business_date, PRODUCT_TYPE] (reported, never rewritten)
lake check: 1 lakes, 1 errors
```

`drishti.py lake show` lists every lake and table as the server reads them (`--json` for everything).

## 6. In-memory stores

A memory store is a lake whose root is `mem://<store>`, its cap and contract under `memory:` (`max-mb`, `chunk-kb`, `contract`, `owner`,
`idle-trim-minutes`). `config/engines/memory-lake.yaml` keeps the engine's switch, its replenish jobs and its compaction policy: the work
the engine does, not the description of the lake. A store still under `drishti.lake.memory.stores` there is read with one warning for
one more release; a store described in both places is refused, naming both. See the
[in-memory lake guide](IN_MEMORY_LAKE_GUIDE.md).

## 7. A pack's recommended entries

A pack ships recommended entries for its kinds: a `lake:` block in `pack.yaml` with the same shape as a lake file's `tables:`, and its
Delta and Iceberg templates name the lake and their entries (`lake: demo`, `lake-tables: trading.trade_endur`) with only reading
concerns beside them (`union`, `source-name`, `mode.<kind>`, `layout.<kind>.type-ahead`). Every shipped pack is in this form, and
`config/lakes/demo.yaml` holds their entries. When a writer first meets a kind its lake file lacks (the data inbox does this), the pack's
entry is copied into the lake file once and said so; from then on the lake file is the truth and an administrator edits it there. A pack
without a `lake:` block gives the layout of its Delta or Iceberg connector template.

The official results are lakes of their own: `config/lakes/var-official.yaml` and `ccr-official.yaml` take their format, root and storage
from the publishers' settings (`drishti.var.official.format`, `.root`, `.storage`; `drishti.ccr.exposure.official.*`), so BI's readers
(`var-results`, `exposure-results`, which name them) follow wherever and however the results are published; when the publisher writes
Iceberg the server makes the reader an Iceberg connector, nothing else.

A connector file written from an older template keeps working (the older form, one warning); a template update never adds `lake:` to it
(with `root` and `layout` beside it the connector would be refused): `drishti.py lake migrate-config` moves it.

## 8. Migrating an older installation

An installation whose connector files carry `root`, `domain`, `table.<kind>` or `layout.<kind>.*` keeps working: those connectors start
as before, with one warning naming the tool. To move:

1. **See what would change** (nothing is written):

   ```bash
   python3 tools/drishti.py lake migrate-config
   ```

   It prints the lake files it would write (one per distinct root, named after the root's last folder: `./data/delta` becomes lake
   `delta`) and each connector file rewritten to `lake: <name>` (and `lake-tables:` where a table is not named after its kind).
2. **Write them**: `python3 tools/drishti.py lake migrate-config --write`. A lake file that exists already is never overwritten.
3. **Check**: `python3 tools/drishti.py lake check` compares each lake file with the tables on disk.
4. **Restart** the server (or let it reload the connector files). The warning is gone.

Nothing under the data folder changes: tables keep their files and partitions. The old keys are read for one more release, then removed.

## 9. Maintenance from the lake file

`drishti.py lake maintain` (and Admin → Lakes' maintenance) acts on a table's own settings in its lake file, over the maintenance file's
(`<data>/lakes/lake-maintenance.yaml`) and under the domain's retention floor (risk and risk-results keep seven years whatever is set):

| Lake file | Maintenance does |
|---|---|
| `retention: 260` / `260d` / `7y` | keeps that many business dates (years: 261 a year); older dates removed |
| `maintenance.compaction: false` | no compaction (default: compacted, a laid-out table re-sorted by its layout) |
| `maintenance.target-file-mb` | the compaction's file size |
| `maintenance.vacuum` / `expire-snapshots` (`168h`, `7d`), `vacuum-hours`, `orphan-hours` | Delta vacuum or Iceberg snapshot expiry age; Iceberg orphan files |
| `maintenance.checkpoint` | a checkpoint each run (default true) |
| `maintenance.checkpoint-interval` | the writers set it as the Delta table's `delta.checkpointInterval`; maintenance checkpoints each run |
| `time-travel: 30d` | vacuum and expiry never go below it: old versions stay readable that long |

A maintenance key it does not know is said in the run's log, never guessed.

## 10. The data inbox's loader

The inbox runs the parallel loader as a child process. It is handed the settings the lake files refer to, as the server resolves them
(`DRISHTI_DELTA_ROOT` given only as a `--setting` included), and reads the same lake files through the same reader: it writes where the
server's connectors read.

## 11. An Iceberg demo store

`tools/build_demo_lakes.py --store calypso=iceberg` (or `risk=iceberg`) copies the tables into Iceberg and writes their connectors in the
lake form: `lake: demo-iceberg`, its lake file `demo-iceberg.yaml` beside the connector folder (`config/lakes/`), one entry per moved
table from the pack's recommended entry (the partition field kept as a promoted column: Iceberg tables of the layout are partitioned by
business date alone). Back to Delta, the pack's own connector (`lake: demo`) serves again.

## 12. The local cluster (drishti-stack)

The stack's synthetic lakes (`stack.yaml` `lakes:`) are laid out as the demo lake's file says: `tools/bench/trades.py` writes through it
(`lake build --lake demo --lake-root data/stack/lakes/<name>`), and a node with `lake:` reads the same file with its root,
`${DRISHTI_DELTA_ROOT}`, set to its synthetic lake. One description of the tables for every node; only the root differs.

## 13. Admin → Lakes

The page lists every lake (its format, root, whether it is held in memory) and each table: its kind, path, partition columns, promoted
columns, vectors, id and load mode, read-only from the files. A lake file the reader refuses is shown as the refusal, so the page always
opens. The API is `GET /api/v1/admin/lakes/files` (`?check=true` adds the comparison with the tables on disk).
