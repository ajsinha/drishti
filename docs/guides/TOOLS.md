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
# Tools: every command, script and page that builds, loads and looks after Drishti

This is the one map of Drishti's tools: the `drishti.py` command line (every group and command), the store loaders and generators
under `tools/`, the lake builder and its maintenance job, the developer scripts, and the console pages that do the same work in a
browser. For each tool it says what it is for, when to use the terminal and when the page, shows a worked example with real output
on **synthetic** data, and, for the tools that run unattended, how to schedule them (cron and systemd, exit codes, logs, locking,
safe re-runs).

The depth lives elsewhere and is linked from each section: every option of every command with its output is in
[CLI_GUIDE.md](CLI_GUIDE.md); turning schema files and JSON into a pack is [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md); running a site
(deploys, monitoring, capacity, runbooks) is [OPERATIONS.md](../admin/OPERATIONS.md); announcing loads is [DATA_LOADS.md](DATA_LOADS.md).

All the data on this page is invented, and every example runs on a fresh clone as it is: the files are the committed test data
under `data/testdata/` (the ENDUR trade book `data/testdata/trades/endur/`, a day-one snapshot and one file of changes per later day, JSON
Lines gzipped; trades with legs, cash flows, resets and events in the UPPER_SNAKE attributes of the trading pack, blanks spelt four ways:
the *shape* of a booking-system export, none of its values), the interpreter is the console's, `drishti-console/.venv/bin/python`
(the five-step quick start of [data/testdata/README.md](../../data/testdata/README.md) installs it), and the commands run from the
repository root. Scratch output (`lake`, `lake2`, `build/...`) goes into the clone; delete it afterwards.

## Contents

1. [Which tool for which job](#1-which-tool-for-which-job)
2. [Running the tools](#2-running-the-tools)
3. [`drishti.py`: the one command line](#3-drishtipy-the-one-command-line)
4. [lake build: a large JSON export into a Delta lake](#4-lake-build-a-large-json-export-into-a-delta-lake)
5. [The other `drishti.py` groups](#5-the-other-drishtipy-groups)
6. [Store loaders: `tools/load-*.sh`](#6-store-loaders-toolsload-sh)
7. [Lake maintenance: `tools/lake/maintain.py`](#7-lake-maintenance-toolslakemaintainpy)
8. [Generators: `tools/packgen`, `tools/samplegen`, `tools/realdata`](#8-generators-toolspackgen-toolssamplegen-toolsrealdata)
9. [Developer tools](#9-developer-tools)
10. [The tools in the console](#10-the-tools-in-the-console)
11. [Scheduling reference](#11-scheduling-reference)
12. [Sizing a large load (300,000 documents and more)](#12-sizing-a-large-load-300000-documents-and-more)

## 1. Which tool for which job

| You want to | In a browser | In a terminal or a scheduler |
|---|---|---|
| Turn a large JSON export (an array or JSON Lines, gzipped or not) into a Delta lake a pack reads | Admin → Data loads → **Load a file into a lake** (`/admin/lakes`) | `drishti.py lake build` |
| Load millions of JSON Lines rows (scenario PVs) into Delta or Iceberg, from a folder or S3, on every core | | `drishti.py lake build --engine java`, or the loader on its own (section 4.13) |
| See what an export holds and which fields make a good id, date and classifier | the same page, step 2 | `drishti.py lake scan`, `drishti.py data profile` |
| Make a pack from schema files or JSON | Build → **New pack** (`/build/pack/new`) | `drishti.py pack make`, `drishti.py pack new` |
| Check a pack in CI | | `drishti.py pack check --junit` |
| Ship a pack to a server | Admin → Packs → Deploy | `drishti.py pack bundle`, `pack deploy`, `server packs deploy` |
| Tell the server that a batch landed | the Lakes page does it after a build | `drishti.py data landed`, or `lake build --signal` |
| Change where a connector reads | Admin → **Connectors** | `drishti.py connector apply` |
| Install or uninstall a pack, connector or BI file safely | Admin → **Artifacts** | `drishti.py server packs`, `connector delete` |
| Keep a lake small and fast | | `drishti.py lake maintain` (on a timer) |
| Load the demo data into Postgres, DuckDB, MongoDB, Redis, Aerospike, Cassandra, Iceberg, files or Delta | | `tools/load-*.sh`, `drishti.py data load` |
| Check a machine before a demo | Admin → **Health** | `drishti.py doctor`, `drishti.py server smoke` |
| See or check the shape of a command's data (its data contract), or draft the contract of a new kind | | `drishti.py contract list`, `contract show`, `contract check`, `contract docs`, `contract bootstrap` |

Use the page for a one-off file, a first look at new data, or when you want to see each choice before it is made. Use the command
line for anything that repeats, runs at night, or lives in a pipeline: it has exit codes, a lock, a log line and a JSON summary,
and it is the same engine the page runs.

## 2. Running the tools

Every tool is Python 3.10+ or bash, run from the repository root. Most need only PyYAML; the ones that write Delta or Parquet need
`deltalake` and `pyarrow`. The console's environment has all three (`drishti-console/requirements.txt`), so the examples run with its
interpreter:

```bash
drishti-console/.venv/bin/python tools/drishti.py lake build ...
```

Without that environment, [uv](https://docs.astral.sh/uv/) runs a tool with nothing installed:
`uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py lake build ...`. (On a machine whose `python3 -m venv`
lacks `ensurepip`, `uv venv --python 3.13 drishti-console/.venv` makes the environment.)

The Java tools (`sutra`, `pack check`, and Drishti's own lake writer) run from the server's exec jar, found as `--jar`, then
`DRISHTI_JAR`, then the newest `drishti-server/target/drishti-server-*-exec.jar` (build it with `./mvnw -q -DskipTests package`).

Exit codes follow one rule everywhere: `0` done, `1` problems found or refused, `2` usage (a bad option, a missing file). The lake
builder adds `2` for "written with rejects" and `3` for "another run holds the table's or the lake's lock" (section 4.6). Commands that talk to a
server take `--server` (default `DRISHTI_SERVER`, then `http://localhost:18480`) and read the token from `--token-file` or
`DRISHTI_TOKEN`; the token is never printed. Shell completion: `source <(drishti-console/.venv/bin/python tools/drishti.py completion bash)`.

## 3. `drishti.py`: the one command line

`tools/drishti.py` is one program with twelve groups. `drishti-console/.venv/bin/python tools/drishti.py <group> --help` lists a group's commands and
`<group> <command> --help` every option with examples.

| Group | Commands | Section |
|---|---|---|
| `sutra` | `sutra shape`, `sutra design`, `sutra lint`, `sutra test`, `sutra preview`, `sutra gen` | [5.1](#51-sutra) |
| `pack` | `pack make`, `pack new`, `pack regenerate`, `pack diff`, `pack catalogue`, `pack i18n`, `pack check`, `pack about-check`, `pack bundle`, `pack verify`, `pack deploy`, `pack rollback`, `pack publish`, `pack keygen`, `pack install` | [5.2](#52-pack) |
| `data` | `data profile`, `data ingest`, `data load`, `data landed`, `data loads` | [5.3](#53-data) |
| `lake` | `lake build`, `lake publish`, `lake scan`, `lake status`, `lake demo`, `lake maintain` | [4](#4-lake-build-a-large-json-export-into-a-delta-lake) |
| `server` | `server health`, `server caches`, `server packs`, `server commands`, `server smoke` | [5.4](#54-server-and-doctor) |
| `connector` | `connector list`, `connector get`, `connector apply`, `connector delete`, `connector test`, `connector plugins`, `connector enable`, `connector disable`, `connector reset`, `connector history` | [5.5](#55-connector) |
| `net` | `net list`, `net check`, `net add-peer`, `net set`, `net members` | [5.9](#59-net) |
| `design` | `design list`, `design create`, `design get`, `design save`, `design check`, `design propose`, `design approve`, `design reject`, `design proposals`, `design export`, `design import`, `design bind`, `design autodesign`, `design delete` | [5.6](#56-design) |
| `docs` | `docs shots` | [9](#9-developer-tools) |
| `doctor` | `drishti.py doctor` | [5.4](#54-server-and-doctor) |
| `bi` | `bi workspace`, `bi catalogue`, `bi expr`, `bi migrate`, `bi sql`, `bi model`, `bi mq`, `bi item`, `bi dataset`, `bi transform`, `bi statement` | [5.7](#57-bi) |
| `view` | `view get`, `view explain` | [5.8](#58-view-and-completion) |
| `history` | `drishti.py history KIND ID` (`--days`, `--range`, `--from`/`--to`, `--diff`, `--changes-only`, `--csv`, `--json`): one entity over business days, as the console's history page shows it | [CLI_GUIDE.md](CLI_GUIDE.md) |
| `contract` | `contract list`, `contract show`, `contract check`, `contract docs`, `contract bootstrap` | [5.11](#511-contract) |
| `var` | `var show`, `var parts`, `var path`, `var history`, `var tree`, `var status`, `var shards`, `var capture`, `var captures`, `var compare`, `var basket`, `var baskets`, `var official`, `var reload`, `var intraday`, `var convert`, `var whatif` | [5.12](#512-var) |
| `exposure` | `exposure show`, `exposure parts`, `exposure tree`, `exposure profile`, `exposure distribution`, `exposure status`, `exposure capture`, `exposure captures`, `exposure compare`, `exposure official`, `exposure retention`, `exposure reload`, `exposure whatif` | [5.13](#513-exposure) |
| `completion` | `drishti.py completion bash` (also `zsh`, `powershell`) | [5.8](#58-view-and-completion) |

The group modules live beside it and are not run on their own: `tools/authoring.py` (`data profile`, `pack regenerate|diff|catalogue|i18n`),
`tools/dataprofile.py`, `tools/packmake.py`, `tools/packregen.py`, `tools/packdiff.py`, `tools/packcatalogue.py`, `tools/packi18n.py`,
`tools/packform.py` (the `form:` section a pack check reads), `tools/schemapack.py` (`--schema`), `tools/sutragen.py` (`sutra gen`),
`tools/ingest_jsonl.py` and `tools/ingest_watch.py` (`data ingest`), `tools/packbundle.py` (`pack bundle|verify|deploy|rollback`),
`tools/serverpacks.py`, `tools/serverconnectors.py`, `tools/servernet.py` (`net`), `tools/hostaddr.py` (this machine's real address for Drishti*net members), `tools/build_demo_lakes.py` (every demo lake start to finish: `lake demo` over all sixty days, then your own ENDUR files one per date, `--store calypso=iceberg` for a source system in Iceberg, `--dry-run` to look first; the Windows QA guide's step 3.0), `tools/smoke.py`, `tools/doctor.py`, `tools/viewcmd.py`, `tools/completion.py`,
`tools/bilang.py`, `tools/bimodel.py`, `tools/biitem.py`, `tools/bistatement.py` (`bi statement preview|file`), `tools/historycmd.py` (`history`), `tools/varcli.py` (`var`), `tools/expcli.py` (`exposure`), and `tools/lake/cli.py` with `tools/lake/build.py`, `tools/lake/scan.py`,
`tools/lake/jsonstream.py`, `tools/lake/stage.py`, `tools/lake/writers.py`, `tools/lake/icebergwrite.py` (`lake build --format iceberg`, §4.4b), `tools/lake/demo.py` (`lake demo`), `tools/lake/stores.py` (a source system's trades copied into Iceberg, its connector file switched; `build_demo_lakes.py --store`) and `tools/lake/laketables.py` (the tables `lake status` lists). `tools/connector_upgrade_fixture.py` is not a `drishti.py` group: it writes the connector files an older release's server generated, the fixture of the connector upgrade drill (`ConnectorUpgradeTest`, [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) "Upgrades").

## 4. lake build: a large JSON export into a Delta lake

`drishti.py lake build` reads an export of any size (a JSON array, pretty-printed or on one line, JSON Lines, either gzipped, one
file or a folder of them) **as a stream**, and writes it as a Delta table the Delta connector reads:

* one row per document: `id` (`--key`, a dotted path), `doc` (the **whole** document as JSON: nested arrays such as `LEGS[]` and
  `LEGS[].CASH_FLOWS[]` stay inside it, as the Sutras expect), `business_date` (`--date FIELD`, or `--as-of DATE` for a snapshot);
* partitioned by `business_date` and then any number of **classifier** columns (`--partition-by PRODUCT_TYPE[,DESK...]`, up to
  four), as Hive folders `business_date=2026-10-16/PRODUCT_TYPE=ComSwap/`;
* **promoted** columns (`--promote COUNTERPARTY,PORTFOLIO,QUANTITY`): real columns a reader filters on without parsing the
  document (DOUBLE when every value is a number, else STRING);
* at `<lake>/<domain>/<kind>/`, the folder a Delta connector with `root: <lake>` and `domain: <domain>` reads for that kind.

Memory stays flat: documents are decoded one at a time, staged on disk per business date and partition, and written in batches
bounded by rows (`--batch-rows`, 50,000) and bytes (`--batch-mb`, 128). The staged rows, and the Java writer's own temporary
files, go in a folder beside the lake (never the system temp folder), removed when the run ends. Section 12 has the numbers.

### 4.1 Look first: lake scan

`lake scan` reads the export as a stream and lists every field (dotted paths to depth 3; arrays are counted, not entered), how
often it is present, its types and most frequent values, then suggests an id, a business date and classifiers:

```
$ drishti-console/.venv/bin/python tools/drishti.py lake scan data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz --limit 500 | tail -n 8
PRECIOUS_METAL_FLAG                    500         2  text  (456), Y(44)
TT_GROUP                               500         1  text  (500)
TT_BOOK                                500         1  text  (500)
LEASE_PERPETUITY_FLAG                  500         3  text  (477), N(12), Y(11)
SETTLEMENT_DATE                        500         1  text  (500)
LEGS                                   500         0  array
EVENTS                                 500         0  array
suggested --key TRADE_ID   --date START_DATE   --partition-by: PRODUCT_TYPE, PRODUCT_SUB_TYPE, PORTFOLIO
```

`(456)` is a blank value counted 456 times. The id is a field present in (nearly) every document with every value different;
the business date ranks names such as *business*, *cob*, *as of* and *snapshot* first; the classifiers are text fields rarely
blank with 2 to 40 values, *product* and *type* first. This export is a snapshot of the book (its dates are trade and start
dates, not the day of the extract), so the walk-through below uses `--as-of` for the day the file was taken, 2026-09-22.

### 4.2 The first load

```
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz \
    --kind trade --key TRADE_ID --as-of 2026-09-22 --partition-by PRODUCT_TYPE --promote COUNTERPARTY,PORTFOLIO,QUANTITY \
    --lake lake --domain trading
engine: drishti (Drishti's Java writer in drishti-server-2.9.5-exec.jar)
business date  PRODUCT_TYPE           rows
2026-09-22     CallNot                  50
2026-09-22     Cash                    723
2026-09-22     ComFut                  883
2026-09-22     ComSwap                1994
2026-09-22     Commodity               757
documents read: 4407, rows written: 4407, rejected: 0
summary: lake/_drishti-lake/runs/20261017T174352-1339862.json
2026-10-17T17:43:55-04:00 lake-build ok exit=0 run=20261017T174352-1339862 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.614
```

The last line is **the** log line of the run (also appended to `<lake>/_drishti-lake/runs.log`): one line per run, whatever
happened, for a scheduler's log and for `grep`. `--dry-run` reads and counts and writes nothing; `-q` prints only the log line;
`--json` prints the whole summary. With the server jar built (a fresh clone after step 2 of the quick start) the builder picks
Drishti's own writer; without it, `deltalake` (section 4.5).

The run also leaves `<data>/bi-store/lake-rebuilt.json` (it was given no `--signal`): a server started afterwards clears the BI columns and
rollups it had read from a lake, so it never answers from the data before this build; `--no-stamp` leaves none (section 4.7).

### 4.3 The next day, with rejects

The next file of the book holds the day's changes (159 trades). Two bad lines added at its end show what a reject looks like:

```
$ (zcat data/testdata/trades/endur/changes-2026-09-23.jsonl.gz; echo '{"PRODUCT_TYPE": "Cash"}'; echo 'not json') | gzip > day2.jsonl.gz
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from day2.jsonl.gz --kind trade --key TRADE_ID --as-of 2026-09-23 --partition-by PRODUCT_TYPE \
    --promote COUNTERPARTY,PORTFOLIO,QUANTITY --lake lake --domain trading
engine: drishti (Drishti's Java writer in drishti-server-2.9.5-exec.jar)
business date  PRODUCT_TYPE           rows
2026-09-23     Cash                     27
2026-09-23     ComFut                   29
2026-09-23     ComSwap                  75
2026-09-23     Commodity                28
documents read: 161, rows written: 159, rejected: 2
  rejected 1: no id (field 'TRADE_ID' is missing or empty)
  rejected 1: not JSON (Expecting value)
    e.g. day2.jsonl.gz:160: no id (field 'TRADE_ID' is missing or empty)
    e.g. day2.jsonl.gz:161: not JSON (Expecting value)
summary: lake/_drishti-lake/runs/20261017T174355-1339986.json
2026-10-17T17:43:57-04:00 lake-build partial exit=2 run=20261017T174355-1339986 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=159 rejected=2 dates=1 partitions=4 seconds=2.201
```

A reject never stops the run: it is counted per reason, the first 50 are kept with where they were (`file:line` for JSON Lines,
`file[n]` for an element of an array), and the run exits `2` so a scheduler can tell "loaded, look at the rejects" from "all
good". The reasons: not JSON, not a JSON object, no id, an id that is an object, no business date, a date that is not a date
(ISO dates and date-times, `yyyymmdd` as text or a number are dates). An id seen twice on one date is reported as a duplicate.
(The table holds what each file holds: the changes file is a day's changes, so the 2026-09-23 date has 159 trades. `lake demo`
applies each day's changes to the previous day by key and writes the whole book every day: section 4.9.)

### 4.4 Re-running a day replaces that day only

```
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz ... --lake lake --domain trading -q
2026-10-17T17:44:01-04:00 lake-build ok exit=0 run=20261017T174358-1340108 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.14
$ find lake -maxdepth 4 -type d | sort
lake
lake/_drishti-lake
lake/_drishti-lake/runs
lake/trading
lake/trading/trade
lake/trading/trade/business_date=2026-09-22
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=CallNot
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=Cash
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=ComFut
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=Commodity
lake/trading/trade/business_date=2026-09-22/PRODUCT_TYPE=ComSwap
lake/trading/trade/business_date=2026-09-23
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=Cash
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=ComFut
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=Commodity
lake/trading/trade/business_date=2026-09-23/PRODUCT_TYPE=ComSwap
lake/trading/trade/_delta_log
```

| `--mode` | What a run does to the dates in its input | Re-run safe |
|---|---|---|
| `overwrite-dates` (default) | replaces every partition of each business date present; every other date is kept | yes |
| `overwrite-partitions` | replaces only the (date, classifier values) present: a file per product type per day replaces only its own product | yes |
| `append` | adds the rows | no: a re-run adds them again |

A table keeps the partitioning it was created with; a run that asks for another fails before reading anything:

```
$ ... lake build --from data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz --kind trade --key TRADE_ID --as-of 2026-09-22 --partition-by PORTFOLIO --lake lake --domain trading
lake build: lake/trading/trade is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)
summary: lake/_drishti-lake/runs/20261017T174401-1340283.json
2026-10-17T17:44:01-04:00 lake-build failed exit=1 run=20261017T174401-1340283 domain=trading kind=trade mode=overwrite-dates seconds=0.0 error="lake/trading/trade is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)"
```

### 4.4b Iceberg instead of Delta: `--format iceberg` (2.9.5)

Delta is the default format of `lake build` and of every pack. `--format iceberg` writes the same staged rows into an Apache Iceberg
table `<lake>/<domain>/<table>` with Drishti's Iceberg loader (in the server's exec jar; `DRISHTI_ICEBERG_CLASSPATH` in development):
the documents, the promoted columns and the vector columns (`--vector PNL`: `list<double>`) exactly as for Delta. Only
`overwrite-dates` (each business date of the input replaces that date in one Iceberg commit); `--partition-by` columns are kept as
columns, the table being partitioned by business date alone. `--lake s3://bucket/prefix` writes to S3 (staged in a local scratch
folder, no run record in the lake), and `--iceberg-set KEY=VALUE` (repeatable) passes any loader setting (`s3.region`, or a REST catalog
such as AWS Glue: `catalog=rest`, `uri=…`, `warehouse=…`, `catalog.rest.signing-name=glue`). The build record makes the table Drishti's
to maintain ([LAKE_MAINTENANCE.md section 8](../admin/LAKE_MAINTENANCE.md#8-iceberg-lakes)).

```bash
python3 tools/drishti.py lake build --from data/testdata/market-risk/pnl-vector/ --pack config/packs/market-risk --connector risk-store \
    --kind pnl-vector --lake data/iceberg --format iceberg
```

### 4.5 The two engines

`--engine auto` (the default) uses **Drishti's own Java writer** when it finds the server's exec jar with the writer in it, and the
Python `deltalake` package otherwise; the first line of the output says which ran and why. `--engine drishti` or
`--engine deltalake` insists on one. A third, `--engine java` (2.9.6), runs the whole build, reading included, in the parallel lake
loader, for Delta and Iceberg alike, only when you ask for it: the Python path stays the default (section 4.13).

| | Drishti's writer (`drishti`) | `deltalake` (delta-rs) |
|---|---|---|
| Runs as | `java -jar drishti-server-<v>-exec.jar lake write --manifest M` (started by the builder) | the builder's own Python |
| Needs | Java 21 and the server jar | `deltalake` and `pyarrow` (in the console's environment) |
| Commits | **one** per table per run: the replaced files are removed and the new ones added in the same version, so a reader sees all of the load or none of it | one per batch; the first batch of a replaced date replaces it |
| Where | local disk (Windows paths too, no `winutils.exe`) | local disk and object storage |
| Heap / memory | `DRISHTI_LAKE_HEAP` (default `1g`) | the batch (`--batch-mb`) plus Arrow's copy |
| Row groups and pages | `DRISHTI_LAKE_ROW_GROUP_KB` (default `256`; `DRISHTI_LAKE_ROW_GROUP_MB` also read) and `DRISHTI_LAKE_PAGE_KB` (default `64`): a single entity's read decodes its row group, so small groups make first reads cheap (256 KB: 13 % more reads a second than 1 MB on 362,804 trades, 2026-10-18) | delta-rs defaults |
| Flattened fields | the pack's `ingest.<kind>.flatten` (`NAME: LEGS[0].FIELD`): each a plain field of the document (an existing field is kept) and a promoted column. An existing table keeps its columns: delete it and load again to add them | the same (staged before either engine writes) |

The same day through `deltalake`, into a second lake; then the table Drishti's writer made (one commit per run: versions 0 to 2 for
the three runs above), read back with deltalake: the two engines write the same table.

```
$ drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz --kind trade --key TRADE_ID \
    --as-of 2026-09-22 --partition-by PRODUCT_TYPE --promote COUNTERPARTY,PORTFOLIO,QUANTITY --lake lake2 --domain trading --engine deltalake
engine: deltalake (the Python deltalake package)
business date  PRODUCT_TYPE           rows
2026-09-22     CallNot                  50
2026-09-22     Cash                    723
2026-09-22     ComFut                  883
2026-09-22     ComSwap                1994
2026-09-22     Commodity               757
documents read: 4407, rows written: 4407, rejected: 0
summary: lake2/_drishti-lake/runs/20261017T174401-1340285.json
2026-10-17T17:44:02-04:00 lake-build ok exit=0 run=20261017T174401-1340285 domain=trading kind=trade engine=deltalake mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=0.828
$ ls lake/trading/trade/_delta_log lake2/trading/trade/_delta_log
lake/trading/trade/_delta_log:
00000000000000000000.crc  00000000000000000000.json  00000000000000000001.crc  00000000000000000001.json  00000000000000000002.crc  00000000000000000002.json

lake2/trading/trade/_delta_log:
00000000000000000000.json  00000000000000000001.json  00000000000000000002.json  00000000000000000003.json  00000000000000000004.json
$ drishti-console/.venv/bin/python -c "from deltalake import DeltaTable; t = DeltaTable('lake/trading/trade'); d = t.to_pyarrow_dataset(); \
    print('version', t.version(), 'partitions', t.metadata().partition_columns, 'rows', d.count_rows()); print(d.schema)"
version 2 partitions ['business_date', 'PRODUCT_TYPE'] rows 5169
id: string
doc: string
business_date: date32[day]
PRODUCT_TYPE: string
COUNTERPARTY: string
PORTFOLIO: string
QUANTITY: double
```

(4,566 rows: the 4,407 of 2026-09-22 and the 159 of 2026-09-23. Read a table with `to_pyarrow_dataset()` as above: with deltalake 1.6
on Python 3.13, a script that called `to_pyarrow_table()` printed its answer but did not exit.)

### 4.6 Exit codes, the lock, the log and the summary

| Exit | Meaning | What a scheduler should do |
|---|---|---|
| `0` | written, nothing rejected (and, with `--signal`, the server was told) | nothing |
| `2` | written, but some documents were rejected, or the data-load signal failed | alert someone to read the summary's `rejects` (or re-send the signal: `drishti.py data landed`) |
| `1` | failed: nothing was committed for the table (a bad option, an unreadable file, another partitioning, the engine failed) | alert; fix and re-run (safe) |
| `3` | another load of the same table is running (or `lake maintain` holds the lake) | try again later; never kill the other run |

**The lock.** One load per **table** at a time; loads of different tables of one lake run side by side (ENDUR's and CALYPSO's
trades as they land, under one root). A load takes a shared lock on `<lake>/_drishti-lake/lock` and an exclusive one on
`<lake>/_drishti-lake/locks/<domain>/<table>.lock`; `lake maintain` takes the lake's lock exclusively, so it waits for every load
and no load starts while it runs. The operating system releases a lock when the process ends, so a run that crashed or was killed
never blocks the next one; the table's lock file names the holder. On Windows, whose file locks have no shared mode, a load takes
the whole lake as before (loads there run one after another). A second load of the same table started 0.5 s after the first
(captured 2026-10-18):

```
2026-10-18T14:52:08-04:00 lake-build locked exit=3 run=20261018T145208-2322240 domain=trading kind=trade mode=overwrite-dates seconds=0.026 error="table trading/trade_endur is locked by {\"pid\": 2322204, \"host\": \"oryxpro\", \"since\": \"2026-10-18T14:52:08-04:00\"}"
```

and ENDUR and CALYPSO loaded side by side into one lake, finishing a second apart:

```
2026-10-18T14:52:00-04:00 lake-build ok exit=0 run=20261018T145157-2322020 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.31
2026-10-18T14:52:01-04:00 lake-build ok exit=0 run=20261018T145158-2322023 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4936 rejected=0 dates=1 partitions=51 seconds=3.624
```

**The log and the summary.** Every run appends its log line to `<lake>/_drishti-lake/runs.log` and writes its summary to
`<lake>/_drishti-lake/runs/<run>.json` and `<lake>/_drishti-lake/last-run.json` (and `--summary FILE`). Staged rows go to
`<lake>/_drishti-lake/staging/<run>/` while the run lasts and are removed after it (`--keep-staging` keeps them); a crashed run's are
removed by the next run, which holds the lock. A summary (the run of 4.3, two of its four partitions shown):

```json
{
 "run": "20261017T174355-1339986",
 "status": "partial",
 "mode": "overwrite-dates",
 "pack": null,
 "domain": "trading",
 "kind": "trade",
 "key": "TRADE_ID",
 "promote": ["COUNTERPARTY", "PORTFOLIO", "QUANTITY"],
 "partitionBy": ["PRODUCT_TYPE"],
 "asOf": "2026-09-23",
 "table": "lake/trading/trade",
 "rows": {"2026-09-23": 159},
 "partitions": [
  {"date": "2026-09-23", "values": {"PRODUCT_TYPE": "Cash"}, "rows": 27},
  {"date": "2026-09-23", "values": {"PRODUCT_TYPE": "ComFut"}, "rows": 29}
 ],
 "documents": 161, "accepted": 159, "rejected": 2,
 "rejects": {
  "byReason": {"no id (field 'TRADE_ID' is missing or empty)": 1, "not JSON (Expecting value)": 1},
  "first": [{"where": "day2.jsonl.gz:160", "reason": "no id (field 'TRADE_ID' is missing or empty)"},
            {"where": "day2.jsonl.gz:161", "reason": "not JSON (Expecting value)"}]
 },
 "duplicates": {},
 "files": [{"file": "day2.jsonl.gz", "bytes": 148726}],
 "sampleId": "1000029", "sampleDate": "2026-09-23",
 "timings": {"readSeconds": 0.035, "writeSeconds": 2.159, "totalSeconds": 2.201},
 "engine": "drishti",
 "engineWhy": "Drishti's Java writer in drishti-server-2.9.5-exec.jar",
 "batchRows": 10565, "averageDocumentBytes": 12703,
 "commit": {"commits": 1, "version": 1, "files": 4},
 "columns": {"partition": ["PRODUCT_TYPE"], "promoted": {"COUNTERPARTY": "string", "PORTFOLIO": "string", "QUANTITY": "double"}},
 "stamp": "<data>/bi-store/lake-rebuilt.json",
 "exitCode": 2,
 "peakMemoryMb": 35.6
}
```

`lake status` reads them back, lists the lake's tables and says whether a run holds the lock now:

```
$ drishti-console/.venv/bin/python tools/drishti.py lake status --lake lake
tables (1):
  table          rows   files  size    partitions        dates  latest date  last build
  trading/trade  5,169  9      7.5 MB  9 (PRODUCT_TYPE)  2      2026-09-23   2026-10-17 21:54:10Z
lock: free
runs (5 logged, last 5):
  2026-10-17T17:43:55-04:00 lake-build ok exit=0 run=20261017T174352-1339862 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.614
  2026-10-17T17:43:57-04:00 lake-build partial exit=2 run=20261017T174355-1339986 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=159 rejected=2 dates=1 partitions=4 seconds=2.201
  2026-10-17T17:44:01-04:00 lake-build ok exit=0 run=20261017T174358-1340108 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=3.14
  2026-10-17T17:44:01-04:00 lake-build failed exit=1 run=20261017T174401-1340283 domain=trading kind=trade mode=overwrite-dates seconds=0.0 error="lake/trading/trade is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)"
  2026-10-17T17:54:10-04:00 lake-build ok exit=0 run=20261017T175408-1353448 domain=trading kind=trade engine=drishti mode=overwrite-dates rows=4407 rejected=0 dates=1 partitions=5 seconds=2.575
last run: ok (exit 0), summary lake/_drishti-lake/last-run.json
```

### 4.7 A pack, a connector and the data-load signal

With `--pack DIR` the builder takes from the pack's `pack.yaml` what it declares: the domain and the promoted columns of its Delta
connector (`settings.domain`, `settings.layout.<kind>.columns`), and the key, date and flattened fields of its `ingest:` block
(written by `pack make` and `pack new`; the trading pack has `ingest.trade.key: TRADE_ID`, so `--key` may be left out, and
`ingest.trade.flatten` adds `NOTIONAL` and `INDEX_NAME` from the first leg, [4.5](#45-the-two-engines)). Options on the command line win.

```yaml
# config/packs/trading/pack.yaml (the parts lake build reads; one Delta connector per source system)
pack: trading
connectors:
  trade-endur:
    plugin: delta
    kinds: [trade]
    settings:
      root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"
      domain: trading
      table.trade: trade_endur
      layout.trade.columns: "TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,...,COUNTERPARTY,LEGAL_ENTITY,PORTFOLIO,TRADER_ID"
      union: "true"
  trade-calypso: ...
  trade-murex: ...
```

The pack has three Delta connectors for `trade`, so the run names one with `--connector` (it picks the table, `trade_endur`, and the
sixteen promoted columns); captured as a dry run:

```bash
drishti-console/.venv/bin/python tools/drishti.py lake build --from data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz --pack config/packs/trading \
    --kind trade --connector trade-endur --key TRADE_ID --as-of 2026-09-22 --partition-by PRODUCT_TYPE --lake lake3 --dry-run --json
# "pack": "trading", "domain": "trading", "kind": "trade", "table": "lake3/trading/trade_endur", "promote": ["TRADE_ID", "SOURCE_SYSTEM",
# "PRODUCT_TYPE", ... "TRADER_ID"] (16), "documents": 4407, "status": "dry-run", "exitCode": 0
```

In production the same run names the real lake and tells the server (the ETL's last step):

```bash
drishti-console/.venv/bin/python tools/drishti.py lake build --from /srv/drop/endur-2026-10-16.json.gz --pack config/packs/trading --kind trade --connector trade-endur \
    --key TRADE_ID --as-of 2026-10-16 --partition-by PRODUCT_TYPE --lake /srv/drishti/delta \
    --signal http://drishti.internal:18480 --token-file /etc/drishti/loads.token
```

The connector reads the lake once its file says so (Admin → Connectors, or `drishti.py connector apply trade-endur.yaml`):

```yaml
# config/connectors/trade-endur.yaml (written from the pack's template at the first start; yours to edit)
plugin: delta
kinds: [trade]
settings:
  root: /srv/drishti/delta
  domain: trading
  table.trade: trade_endur
  union: "true"
```

`--signal URL` then tells **that** server (it is never a default) that each business date landed: `POST /api/v1/packs/{pack}/loads`
with the rows, the rejects and the run as the batch id, so the server refreshes, verifies, checks its expectations and alerts, and
tells people ([DATA_LOADS.md](DATA_LOADS.md)). The pack's name comes from `--pack`, or `--pack-name` without a pack folder. The
token needs the `loads:write` scope; it is read from `--token-file` or `DRISHTI_TOKEN` and never printed or written to the summary.
A signal that fails leaves the data written and makes the run exit `2`. Without `--signal`, or when it fails, the run leaves
`<bi-store>/lake-rebuilt.json` (the BI store: `DRISHTI_BI_STORE`, else `<data>/bi-store`), so the server's next start clears the BI columns
and rollups it read from the lakes; a running server also notices a rebuilt date by itself (its Delta files changed) and reads it again.
`--no-stamp` leaves no stamp.

### 4.8 The same in the browser

Admin → Data loads → **Load a file into a lake** (section 10.2) runs this builder on the console's machine with the choices made on
the page, then binds the lake to the pack's connector and sends the signal as the administrator who started it.

### 4.9 The demo lakes: lake demo

`drishti.py lake demo` (run configuration **Build demo lakes**) is `lake build` run for every dataset and business day of the
committed test data, `data/testdata`: the seven packs' tables under `data/delta` in about 15 minutes (943 s measured, about 3.4 GB), the sixty days moved to end on today's
business date (`--as-of data` keeps the files' own dates), the connector files checked, the next steps printed. `--only PREFIX` builds
some datasets, `--lake DIR` another folder (the connector files then point there), `--with-real FILE[,FILE]` adds real extracts to the
local lake only. The sample feed the `file` connector serves (`feeds/`) and the staging go to the server's data folder: `--data-dir DIR`, else
`DRISHTI_DATA_DIR`, else the folder holding `--lake` when that is a `delta/` folder (`--lake /srv/data/delta`: `/srv/data`), else `data/`, so a
server on another data folder finds its feeds (`make_data.py --data-dir DIR` does the same). It then tells the server the lakes changed: the data-load signal for every table and date to `--server` (default
`DRISHTI_SERVER`, then `http://localhost:18480`; a token with `loads:write` from `--token-file` or `DRISHTI_TOKEN`), or, with no server
there, the stamp of section 4.7; `--no-signal` does neither, and `--signal-once` tells each table once, for its newest date, instead of
each table and date (what `tools/build_demo_lakes.py --server URL` uses; a running server reads the older dates again by itself). The
summary says how many signals the server took, how many it **refused** (an HTTP error; the token hint only for 401 and 403) and how
many it **did not answer** within 30 s (busy, not a refusal), and how long the signals took beside the build (`built in 193 s, the
server told in 12 s`). `tools/build_demo_lakes.py` tells no server unless `--server` names one (QA3A-05). Its closing line says what came of the signals: *was told once per table it reads* only when every signal was taken; *was not told: nothing answered there* (with the stamp left for its next start), *was not told: it refused the signals* (a token with `loads:write`), *did not answer the signals in time* (busy) or *was told of only some tables* otherwise, and *would be told* in a dry run (QA4-06). Everything about the data and the five-step quick start is in [data/testdata/README.md](../../data/testdata/README.md).

**The exposure run (2.9.0).** `--exposure demo` (the default) does not load the committed exposure run of eight netting sets: it
generates the run `XR-2026-09-30-01` for every netting set of the counterparties that fit in 9,500 trades, smallest first and always
whole (23 of the 24 counterparties, 74 netting sets, 8,022 live trades on the data's date, 200 paths, about a minute and 100 MB in the staging folder,
nothing downloaded, never committed) with `tools/packgen/ccr/make_ccr_data.py --demo` from the committed netting sets and CSAs. Each
trade's paths are seeded by its id, so the eight committed sets keep exactly their committed figures; CP-SUMMIT and CP-NORTHPLAINS stay
without paths (the 10,000-trade local limit). `--exposure committed` loads the committed run alone. See
[EXPOSURE_GUIDE.md section 23](EXPOSURE_GUIDE.md#23-test-data-and-generators).

**Each source system in Delta or Iceberg (2.6.7).** `tools/build_demo_lakes.py --store <system>=delta|iceberg` (repeatable; `all=`
first, then each system over it) chooses where each source system's trades are stored:

```bash
drishti-console/.venv/bin/python tools/build_demo_lakes.py --store calypso=iceberg                     # CALYPSO in Iceberg, ENDUR and MUREX in Delta
drishti-console/.venv/bin/python tools/build_demo_lakes.py --store all=iceberg                         # every system in Iceberg
drishti-console/.venv/bin/python tools/build_demo_lakes.py --only trades/calypso --store calypso=iceberg --as-of 2026-10-16
drishti-console/.venv/bin/python tools/build_demo_lakes.py --source-dir ~/calypso --system calypso --files-only --store calypso=iceberg
drishti-console/.venv/bin/python tools/build_demo_lakes.py --store all=delta                           # every system back in Delta
```

A system in Iceberg is built as Delta first (`lake demo`, or `lake build` of your files into `<data>/build/iceberg-stage`), so its
documents and promoted columns are exactly a Delta build's, then copied by `tools/lake/stores.py` with Drishti's own Iceberg loader
(`IcebergLoader`, `engine: native`, from the server's exec jar: build it first; `DRISHTI_LOADER_HEAP`, default 2g) into
`<data>/iceberg/trading/trade_<system>` (beside `--lake`'s folder; `DRISHTI_ICEBERG_ROOT` moves it). Its Delta table is removed, and its
connector file `trade-<system>.yaml` is written with `plugin: iceberg`, `engine: native` and `table: {trade: trade_<system>}` (back to the
pack's Delta template when the system returns to Delta; the replaced text is kept in `<data>/connectors/history/`), so one connector
serves each system, never two. A full build without `--store` puts every system in Delta, as before; a partial one (`--only`,
`--files-only`) keeps each system it does not name in the store it has, and `--only` with a `--store` that moves another system adds that
system's `trades/<system>` to the rebuild. `--files-only` cannot move a system it does not load (it says to use `--only trades/<system>`
first). The loader's own progress lines (`iceberg: … rows read`, `written in … s`) and the 30-second heartbeat show while it copies; the
closing lines list the store and table of every system and what was done to each connector file. A running server restarts a switched
connector by itself (live reload); with `--server` it is also told once (trading, `trade`, the newest date). The equality test,
`tools/lake/stores_equality.py --work <empty folder> --port <port>`, builds the same synthetic days all-Delta and mixed and compares
trade pages, `TRD <id> 10`, counterparty-trades pages, searches and a BI query through a running server
([DEMO_DATA.md](../connectors/DEMO_DATA.md#mixed-delta-and-iceberg-storage)).

`drishti.py testdata capture-market` refreshes the committed market snapshot that `lake demo` builds for offline demos
(`data/testdata/news-and-markets/{quote,news}`): it starts a scratch server (or uses `--server`) and fetches the twenty companies'
quotes with a year of daily history (`--range`) and the companies' and topics' headlines through Drishti's own yfinance and news
connectors, politely (`--delay`); `--only quote|news` takes one of the two, `--dry-run` says what it would fetch. It needs internet;
the snapshot it writes is real data from Yahoo Finance and Google News, for internal demos only.

### 4.10 Scheduling the lake build

A nightly load of the day's extract, at 21:30 on weekdays, after the export lands in `/srv/drop`:

```cron
# m  h  dom mon dow  command
30 21 *   *   1-5  cd /opt/drishti && drishti-console/.venv/bin/python tools/drishti.py lake build --from /srv/drop/endur-$(date +\%F).json.gz --pack config/packs/trading --kind trade --connector trade-endur --key TRADE_ID --lake /srv/drishti/delta --as-of $(date +\%F) --partition-by PRODUCT_TYPE --signal http://localhost:18480 --token-file /etc/drishti/loads.token -q >> /var/log/drishti/lake-build.log 2>&1
```

The same as a systemd timer (a missed run is caught up after a reboot, and `systemctl status` shows the exit code):

```ini
# /etc/systemd/system/drishti-lake-build.service
[Unit]
Description=Drishti lake build (the day's book)
After=network-online.target

[Service]
Type=oneshot
User=drishti
WorkingDirectory=/opt/drishti
ExecStart=/bin/sh -c 'drishti-console/.venv/bin/python tools/drishti.py lake build --from /srv/drop/endur-$(date +%%F).json.gz --pack config/packs/trading --kind trade --connector trade-endur --key TRADE_ID --lake /srv/drishti/delta --as-of $(date +%%F) --partition-by PRODUCT_TYPE --signal http://localhost:18480 --token-file /etc/drishti/loads.token -q'
# 2 (written with rejects) is not a failure of the unit; alert on it from the summary instead
SuccessExitStatus=2
Environment=DRISHTI_LAKE_HEAP=2g

# /etc/systemd/system/drishti-lake-build.timer
[Unit]
Description=Drishti lake build, weekdays at 21:30

[Timer]
OnCalendar=Mon..Fri 21:30
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl enable --now drishti-lake-build.timer
journalctl -u drishti-lake-build.service -n 20     # the run's log line
drishti-console/.venv/bin/python tools/drishti.py lake status --lake /srv/drishti/delta
```

**Safe re-runs.** With `overwrite-dates` or `overwrite-partitions` a run can be repeated any number of times: the same input gives
the same table. Re-run a failed night by hand with the same command. A run that finds the lock held exits `3` at once; let the
scheduler try again (systemd: `Restart=on-failure` with `RestartSec=10min` on the service, or simply the next timer). A watch on
a drop folder is `drishti.py data ingest --watch` (section 5.3).

### 4.11 lake publish: a built day into a Delta table in S3

`lake build` writes tables on local disk or a network file system (EFS) only: its lock, its staging and Drishti's Java writer are
local. A lake in Amazon S3 is loaded in two steps: `lake build` into a fresh scratch lake, then the scratch table's data files are
copied to the S3 table's folder (`aws s3 sync`, without `_delta_log/`) and `lake publish` commits them:

```bash
drishti-console/.venv/bin/python tools/drishti.py lake publish --from /scratch/run-1/trading/trade_endur \
    --to s3://acme-drishti-prod-lake/lake/trading/trade_endur --dates 2026-10-20 --storage-option AWS_REGION=eu-west-2
lake publish: ok exit=0 published <n> file(s), <bytes> bytes, dates 2026-10-20 to s3://acme-drishti-prod-lake/lake/trading/trade_endur version=<v>
```

It reads the scratch table's log (paths, sizes, partition values, row counts, the id and promoted-column statistics), checks that
every file is in the target with its size, and makes **one** commit: with `--mode overwrite-dates` (the default) the dates' old
files are removed and the new ones added, so a reader sees the old day or the new day; `overwrite-partitions` replaces only the
partitions carried; `append` adds. A new table is created with the scratch table's schema, partitioning and statistics columns, a
checkpoint every `--checkpoint-interval` (10) commits, and `--log-retention-days` (30) and `--deleted-file-retention-days` (7).
Commits use S3 conditional writes (deltalake 1.x; no DynamoDB lock) and retry a lost race `--commit-retries` (20) times;
`AWS_S3_ALLOW_UNSAFE_RENAME=true` is refused. A day whose columns differ from the table's is refused, naming the columns.
`--dry-run` checks everything and commits nothing; `--list-files` prints the data files the publish needs; `--signal` sends the
data-load signal as `lake build` does. Exit: 0 ok, 1 failure (nothing committed), 2 committed but the signal failed, 3 nothing to
publish for the dates asked.

`deploy/aws/delta-lake/load-day.sh` runs the three steps per table under a per-table lock; the whole AWS setup, sizing past 200 TB
and the runbooks are [AWS_DELTA_LAKE.md](../architecture/AWS_DELTA_LAKE.md). Tested by `tools/lake/test_publish.py` (a local
folder stands in for S3) and on S3 simulators by `tools/lake/test_s3_simulators.py` (§4.12). With `--storage-option
AWS_ENDPOINT_URL=…` (MinIO, an S3-compatible store) the file check goes to that endpoint with those keys, as the commit does.

### 4.12 The S3 tests: S3Mock, moto and MinIO

Three S3 simulators, none needing Docker. Each suite prints which conditional writes its simulator covered
(`[S3 conditional writes] …: create-only covered, update-if-match covered, delete-if-match covered, racing writers …`) and skips,
with the reason, what the simulator does not honour.

| Simulator | What it tests | Runs on |
|---|---|---|
| Adobe S3Mock 4.12.4, in the test JVM (Maven test dependency) | the content store's S3 contract; the Iceberg native engine's table on S3 | Linux and Windows |
| moto 5.2.3 (`moto[server]`, pure Python, started with the venv's own python) | Delta on S3 through deltalake's storage options; `lake publish` on S3 (new table, a day restated, a missing file refused, three publishes at once) | Linux and Windows |
| the MinIO server binary | all of the above, the most faithful | Linux only: skipped on Windows, offline, or when no binary can be had |

```bash
# Java: the content store and the Iceberg table on S3Mock (and MinIO when it can run)
./mvnw -q -pl drishti-identity,plugins/drishti-plugin-iceberg -am test \
  -Dtest='S3ContentStoreTest,S3MockContentStoreTest,MinioContentStoreTest,NativeOnS3SimulatorTest' -Dsurefire.failIfNoSpecifiedTests=false
# Python: moto (once: drishti-console/.venv/bin/python -m pip install "moto[server]==5.2.3", or uv pip install --python …)
drishti-console/.venv/bin/python -m unittest -v tools/lake/test_s3_simulators.py
```

On Windows: `mvnw.cmd` and `drishti-console\.venv\Scripts\python.exe` instead (see the QA guide). MinIO: give the tests a binary
with `DRISHTI_MINIO_BIN=/path/to/minio` or put one in `tools/cache/minio/` (git-ignored); MinIO no longer serves its community
binaries, so the default download answers `410 Gone` and the MinIO tests skip saying so. The drill runs all three (the Java ones in its
verify, the Python one with uv). Results found so far: S3Mock honours create-only, update-if-match and delete-if-match but lets two
racing creates both win; moto honours all three and its racing creates have one winner.

### 4.13 The parallel lake loader (2.9.6)

For a load too large for the Python path (the bank's scenario PVs: 2.3 million positions of 510 scenarios, 20+ GB of JSON Lines), the
**parallel lake loader** (module `drishti-lakeload`, in the server's exec jar) does the whole build in Java: readers, parsers and writers
on every core, memory bounded by `memory.budget-mb` whatever the input's size (rows that do not fit are sorted and spilled to disk), Delta
or Iceberg, from a folder or `s3://`, **one commit per business date**. It writes exactly the tables `lake build` writes (the same
documents byte for byte, columns and vectors; the connectors and the VaR loader read them unchanged). The design, the memory model and
the 2.3M sizing: [PARALLEL_LOADER.md](../architecture/PARALLEL_LOADER.md). `tools/lake/javaload.py` is `lake build`'s side of it.

**Through lake build** (the lock, the run log, the summary, the signal as always):

```bash
python3 tools/drishti.py lake build --engine java --from /data/pv/2026-10-28/ --pack config/packs/market-risk --kind scenario-pv \
    --key SCENARIO_PV_ID --date BUSINESS_DATE --lake /var/lib/drishti/delta \
    --loader-set memory.budget-mb=16384 --loader-set memory.spill-dir=/mnt/nvme/spill
```

**The Python path is the default; the loader runs only when you ask for it** with `--engine java` (the same command line otherwise).
`--engine auto` (the default) never picks the loader on its own; with `--java-above-mb N` it does, for at least N MB of JSON Lines in mode
`overwrite-dates` (smaller loads, JSON arrays and the other modes go the Python way). `--loader-set KEY=VALUE` (repeatable)
sets any key below; `DRISHTI_LAKE_LOADER_HEAP` is the JVM's heap (default `4g`); `--from s3://…` and a Delta `--lake s3://…` need
`--engine java`.

**On its own** (a scheduler, a container, a machine without the console's Python):

```bash
java -Xmx40g -Djava.io.tmpdir=/mnt/nvme/tmp -Dloader.main=com.ash.drishti.lakeload.LakeLoad \
    -jar drishti-server/target/drishti-server-<version>-exec.jar --config /etc/drishti/load-pv.yaml
# development: java -cp <the module's class path> com.ash.drishti.lakeload.LakeLoad --config load.yaml
```

```powershell
# Windows (PowerShell; Java only, no .exe)
java -Xmx8g "-Djava.io.tmpdir=C:\drishti\data\tmp\server" "-Dloader.main=com.ash.drishti.lakeload.LakeLoad" `
    -jar C:\drishti\drishti-server\target\drishti-server-<version>-exec.jar --config C:\drishti\load-pv.yaml
```

Options: `--config FILE`, `--set key.path=value` (repeatable; overrides the file), `--summary FILE`, `--progress FILE`, `--dry-run`
(read and count only), `--no-lock`, `--no-run-log`, `--json` (the summary on standard output), `-q`. Exit codes as section 4.6: `0`,
`2` (rejects), `1` (failed: no date that was not committed changed), `3` (locked). A local lake gets the run log and summary under
`<lake>/_drishti-lake` and the lock of section 4.6.

**Every key** (YAML or JSON; `${ENV}` and `${ENV:-default}` are expanded; an unknown key is refused with the list of the section's keys):

| Key | Default | Meaning |
|---|---|---|
| `source.files` | (required) | a file, a folder (its `*.jsonl`, `*.ndjson`, `*.json`, `*.gz`, `*.zst`), or a glob (`/data/pv/*.jsonl.gz`, `C:\pv\cob-*.jsonl`, `**` for any depth; on S3 in the last segment: `s3://bucket/pv/2026-10-28/*.jsonl.gz`); a list for several |
| `source.recursive` | `false` | a folder's subfolders too |
| `source.storage-options` | none | S3: `region`, `endpoint`, `path-style`, `read-block-kb`, `tls.*`, `proxy.*`. Credentials only from the AWS default chain (environment, profile, instance or task role); a key here is refused |
| `source.format` | `jsonl` | JSON Lines (the data standard); `.gz` and `.zst` decompressed as streams, multi-member gzip included |
| `source.pack`, `source.connector` | none | a pack folder (as `--pack`): the connector's domain and table, `layout.<kind>.columns`, `vectors`, `flatten`, `ingest.<kind>.key` and `date`; the keys below win |
| `source.id` | `id` (or the pack's) | the id field (`--key`), a dotted path |
| `source.date` / `source.as-of` | (one of them) | the business-date field (ISO date or date-time, yyyymmdd) / one date for every row |
| `source.promote` | the pack's | fields kept as columns (`layout.<kind>.columns`) |
| `source.vectors` (`vector`) | the pack's | list-of-numbers fields as `list<double>` columns, the document keeping a `{"$vector": …}` marker (VAR_GUIDE.md section 29.3) |
| `source.flatten` | the pack's | `NAME: LEGS[0].NOTIONAL`: fields added to the document and promoted |
| `source.partition-by` | none | Delta partition folders after `business_date` (Iceberg: keep them as `promote`) |
| `target.format` | `delta` | `delta` or `iceberg` (Iceberg through the native engine: no Hadoop) |
| `target.lake`, `target.domain`, `target.kind`, `target.table` | (lake, domain, kind) | the table `<lake>/<domain>/<table or kind>`; `lake` a folder or `s3://bucket/prefix` |
| `target.path` | none | the table's location instead of the four above |
| `target.storage-options` | `source.storage-options` | the target's S3 options |
| `target.mode` | `replace-date` | each business date of the input replaced in one commit (`overwrite-dates` is the same word) |
| `target.properties` | none | Delta table properties for a new table (`delta.logRetentionDuration`, …) |
| `target.iceberg` | none | Iceberg catalog settings as the connector's: `catalog: rest`, `uri`, `warehouse`, `token: ${ICEBERG_TOKEN}` (a secret only as `${VAR}`) |
| `target.layout.sort-by` | the pack's `layout.<kind>.sort-by`, else `[LEGAL_ENTITY, DESK, id]` | the order of each date's rows (those of the fields the rows promote; `id` the row id); `[id]` is lake build's order |
| `target.layout.file-size-mb` | `256` | Parquet files of about this size |
| `target.layout.shard-aligned` | `true` | also a new file where `LEGAL_ENTITY`/`DESK` (the sort fields before the id) change, once a file holds a quarter of its size |
| `target.layout.row-group-mb` | the format's | Parquet row groups (Delta: `DRISHTI_LAKE_ROW_GROUP_KB`, 256 KB; Iceberg: the table's, 1 MB) |
| `target.layout.compression` | the format's | `snappy` (Delta), `zstd` (Iceberg) |
| `target.layout.vector-compression` | `zstd` | the vector column's codec |
| `target.layout.byte-stream-split` | `true` | the vector column BYTE_STREAM_SPLIT encoded (ZSTD packs it far better) |
| `parallel.readers` | the cores | files (or ranges) read at once; never more files open than this |
| `parallel.parsers` | the cores | parser threads |
| `parallel.writers` | 8 (at most the cores) | writer threads (key ranges written at once) |
| `parallel.split-mb` | `256` | a plain file larger than this is read as byte ranges in parallel (ranged GETs on S3) |
| `parallel.queue-mb` | 15 % of the budget | lines read but not parsed yet (back-pressure) |
| `memory.budget-mb` | half of `-Xmx` | everything the load holds: the queue, the sort buffers, the writers |
| `memory.sort-buffer-mb` | 55 % of the budget | the parsers' rows before they are sorted and spilled |
| `memory.spill-dir` | `java.io.tmpdir` | where sorted runs spill (local disk, EBS, Fargate ephemeral storage); removed however the load ends |
| `memory.check-spill-space` | `true` | stop before reading when the spill folder has less free space than the estimate |
| `rejects.file` | none | every reject as a JSON line: `where` (`file:line`), `reason`, `line` |
| `rejects.max-pct` | `100` | more of the documents rejected than this: nothing is written, exit 1 |
| `rejects.keep` | `50` | rejects kept in the summary |
| `report.summary` | `<lake>/_drishti-lake/runs/<run>.json` | the JSON summary (lake build's keys, plus `throughput` per stage and `memory`) |
| `report.manifest` | beside the summary | the load manifest: per date and file, rows and the first and last sort key (PARALLEL_LOADER.md section 7) |
| `report.progress` | none | a JSON file rewritten twice a second (phase, bytes, documents, rejects, dates) |
| `report.run-log`, `report.dry-run`, `lock` | `true`, `false`, `true` | as `--no-run-log`, `--dry-run`, `--no-lock` |

**Local folder to S3, Delta** (the scenario PVs of the day, the pack's layout):

```yaml
source:
  files: /data/pv/2026-10-28/*.jsonl.gz
  pack: config/packs/market-risk
  id: SCENARIO_PV_ID
  date: BUSINESS_DATE
target:
  format: delta
  lake: s3://bank-lake/delta
  kind: scenario-pv                       # the table s3://bank-lake/delta/risk/scenario-pv (domain from the pack)
  storage-options: {region: us-east-1}
  layout: {sort-by: [LEGAL_ENTITY, DESK, id], file-size-mb: 256, shard-aligned: true}
parallel: {writers: 16}
memory: {budget-mb: 32768, spill-dir: /mnt/nvme/spill}
rejects: {file: /var/log/drishti/rejects-2026-10-28.jsonl, max-pct: 1}
```

**S3 to S3, Iceberg** (MinIO here; on AWS leave out `endpoint` and `path-style`):

```yaml
source:
  files: s3://landing/pv/2026-10-28/*.jsonl.zst
  storage-options: {region: us-east-1, endpoint: '${MINIO_ENDPOINT}', path-style: true}
  id: POSITION_ID
  date: BUSINESS_DATE
  promote: [LEGAL_ENTITY, DESK, TT_BOOK, CCY]
  vectors: [PV]
target:
  format: iceberg
  lake: s3://bank-lake/iceberg
  domain: risk
  kind: scenario-pv
memory: {budget-mb: 8192, spill-dir: /scratch}
```

**A REST catalog** (AWS Glue's Iceberg endpoint): add `target.iceberg: {catalog: rest, uri: 'https://glue.us-east-1.amazonaws.com/iceberg',
warehouse: '<account id>', catalog.rest.signing-name: glue}` (the domain is the namespace).

**On Windows** (`C:\drishti\load-pv.yaml`; paths in single quotes keep their backslashes):

```yaml
source:
  files: 'C:\drishti-private\pv\cob-2026-10-28.*.jsonl.gz'
  pack: 'C:\drishti\config\packs\market-risk'
  id: SCENARIO_PV_ID
  date: BUSINESS_DATE
target:
  format: delta
  lake: 'C:\drishti\data\delta'
  kind: scenario-pv
memory: {budget-mb: 4096, spill-dir: 'C:\drishti\data\tmp\spill'}
```

What it prints: the market-risk pack's scenario PVs of `data/testdata` (three gzip files, 1,339 positions), the first config above
pointed at them and at a scratch lake, `-Xmx2g`, `budget-mb: 512`, captured 2026-10-28 (a cold JVM: a load this small is mostly start-up;
warm rates are in PARALLEL_LOADER.md section 11). `-q` leaves only the last line.

```
loader: delta table /scratch/demo/lake/risk/scenario-pv
  2026-09-30  1,339 rows  (version 0, 1 files)
documents read: 1,339, rows written: 1,339, rejected: 0
  read         3 threads       0.0 s    1,585.3 MB/s in
  decompress   3 threads       0.0 s    141.6 MB/s in
  parse       24 threads       0.2 s  5,666 rows/s  31.4 MB/s in
  sortSpill   24 threads       0.0 s
  write        8 threads       0.4 s  3,005 rows/s  6.6 MB/s out
  commit       1 threads       0.1 s  16,038 rows/s
  memory: budget 512 MB, peak heap used 150 MB of 2,048 MB, spilled 0 MB
summary: /scratch/demo/lake/_drishti-lake/runs/20261028T085451-1591296.json
load manifest: /scratch/demo/lake/_drishti-lake/runs/20261028T085451-1591296.manifest.json
2026-10-28T08:54:52-04:00 lake-build ok exit=0 run=20261028T085451-1591296 domain=risk kind=scenario-pv engine=java-loader mode=overwrite-dates rows=1339 rejected=0 dates=1 partitions=1 seconds=1.42
```

(The scratch folder's path shortened to `/scratch`; the line now says `1 file(s)`.)

The tests: `./mvnw -o -q -pl drishti-lakeload test` (equality with the Python path on both formats, through the connectors; one commit a
date; a stopped load; S3 on S3Mock; gz, multi-member gz, many gz, zstd, byte ranges, 1 vs many threads, a tiny budget: identical tables;
the 10K throughput) and `python3 -m pytest tools/lake/test_javaload.py`.

## 5. The other `drishti.py` groups

### 5.1 sutra

`sutra shape` infers the JSON Schema of samples; `sutra design` drafts a Sutra from samples (or `--schema`); `sutra lint` and
`sutra test` check a pack's Sutras (CI); `sutra preview` renders samples to HTML; `sutra gen` drafts one Sutra per group of JSON
Lines (for example one per `PRODUCT_TYPE`). The first five are the Java tool in the server jar; `sutra gen` is Python
(`tools/sutragen.py`) and lints its result with the Java tool unless `--no-lint`.

```bash
mkdir -p build/tools-walk && zcat data/testdata/trades/endur/snapshot-2026-07-08.jsonl.gz | head -n 300 > build/tools-walk/trade.jsonl
drishti-console/.venv/bin/python tools/drishti.py sutra gen build/tools-walk --key trade=TRADE_ID --match trade=PRODUCT_TYPE --out build/tools-walk/sutras
drishti-console/.venv/bin/python tools/drishti.py sutra lint config/packs/trading --strict
```

Captured (the end of `sutra gen`: one Sutra per product type, each linted):

```
trade           PRODUCT_TYPE=ComSwap                            145       5  trade-comswap
trade           PRODUCT_TYPE=Commodity                           38       5  trade-commodity
ok      build/tools-walk/sutras/trade/trade-callnot.v1.sutra.yaml
ok      build/tools-walk/sutras/trade/trade-cash.v1.sutra.yaml
ok      build/tools-walk/sutras/trade/trade-comfut.v1.sutra.yaml
ok      build/tools-walk/sutras/trade/trade-commodity.v1.sutra.yaml
ok      build/tools-walk/sutras/trade/trade-comswap.v1.sutra.yaml
```

Real output, every option: [CLI_GUIDE.md section 4](CLI_GUIDE.md#4-sutra-shape-design-gen-lint-test-preview). The page that does
the same is the Screen Designer and Studio ([SCREEN_DESIGNER.md](SCREEN_DESIGNER.md)).

### 5.2 pack

| Command | What it does | In the console |
|---|---|---|
| `pack make` | JSON Lines (or `--schema`) in, ONE deployable folder out: the pack, Delta data, a bundle, server config, run scripts | Build → New pack |
| `pack new` | a complete pack from JSON Lines (kinds, Sutras, samples, tests, About text, a lake or files store; `--load` loads it) | Build → New pack |
| `pack regenerate` | new data, new Sutras, none of your edits lost | |
| `pack diff` | what changed between two versions of a pack, and what breaks | |
| `pack catalogue` | a readable catalogue (Markdown or HTML) for business review | |
| `pack i18n` | `pack i18n export` / `pack i18n import`: translate the About text through a spreadsheet | |
| `pack check` | `sutra lint` + `sutra test` + help coverage, JUnit for CI | |
| `pack about-check` | fields the Sutras show that the About text does not explain | |
| `pack bundle` / `pack verify` / `pack deploy` / `pack rollback` | a versioned, checksummed archive; verify offline; copy into a packs folder atomically; put the previous one back | Admin → Packs → Deploy |
| `pack publish` / `pack keygen` / `pack install` | sign into a registry (`tools/packreg/packreg.py`); make a key; install from the registry on a server | Admin → Packs |

```bash
drishti-console/.venv/bin/python tools/drishti.py data profile build/tools-walk/trade.jsonl      # first: what is there (5.3)
drishti-console/.venv/bin/python tools/drishti.py pack make build/tools-walk/trade.jsonl --kind trade --match PRODUCT_TYPE --key TRADE_ID --date TRADE_DATE \
    --name synth-book --out build/tools-walk/synth-book
drishti-console/.venv/bin/python tools/drishti.py pack check build/tools-walk/synth-book/pack/synth-book --junit build/tools-walk/reports
```

`pack make` writes one deployable folder (`MANIFEST.json`, `README.txt`, `bundle/`, `config/`, `data/` with a 300-row Delta store,
`run-server.sh`, `run-server.ps1`) with the pack in `pack/synth-book/`; it ends by linting and testing it (captured: `sutra lint: exit 0`,
`sutra test: exit 0`, each drafted Sutra with its help coverage, for instance `trade-comswap: help coverage 18/39 (46%)`). `pack check
--strict` on the drafted pack exits `1`: the default Sutra shows fields its About text does not explain yet (`DRS-2047`, a warning that
`--strict` makes an error). Write the About text (`pack about-check`), then make CI strict.

Real output: [CLI_GUIDE.md section 5](CLI_GUIDE.md); schema files to a pack: [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md).
**Scheduling**: `pack check` belongs in CI (exit `1` fails the build; `--junit DIR` for the report); `pack deploy` is idempotent
(the same bundle twice changes nothing) and keeps the replaced version for `pack rollback`.

### 5.3 data

`data profile` lists every field of JSON Lines with coverage, distinct values and examples, and prints the `pack make` line that
fits (a profile of the first 300 ENDUR trades of the test data, `build/tools-walk/trade.jsonl` of 5.1; the file is named after the kind):

```
$ drishti-console/.venv/bin/python tools/drishti.py data profile build/tools-walk/trade.jsonl | head -n 8
data profile: 1 file(s), 300 document(s), 1 kind(s)

== trade: 300 document(s), 107 field(s)
  field                                           type            cover  distinct  examples
  SOURCE_SYSTEM                                   string           100%         1  ENDUR
  SOURCE_UPDATE_TIME                              string           100%         1
  TRADE_DATE                                      string           100%       250  2025-06-20, 2025-01-21, 2026-07-10
  INPUT_DATE                                      string           100%         1
$ drishti-console/.venv/bin/python tools/drishti.py data profile build/tools-walk/trade.jsonl | tail -n 6
    COUNTERPARTY_UEN             0.860  coverage 100%, 22 values over 300 documents (average 13.6 per value, evenness 0.86; largest group UEN0100888 = 83)
    COUNTERPARTY_COUNTRY_OF_RISK 0.827  coverage 100%, 11 values over 300 documents (average 27.3 per value, evenness 0.72; largest group US = 111)
    PRODUCT_TYPE                 0.642  coverage 100%, 5 values over 300 documents (average 60.0 per value, evenness 0.82; largest group ComSwap = 145); name suggests a category
    TRADE_STATUS                 0.613  coverage 100%, 5 values over 300 documents (average 60.0 per value, evenness 0.74; largest group MATURED = 150); name suggests a category
  suggested:
    python3 tools/drishti.py pack make build/tools-walk/trade.jsonl --kind trade --match PRODUCT_SUB_TYPE --key TRADE_ID --date TRADE_DATE --name trade-pack
```

`data profile` reads plain JSON Lines; give it a gzipped file and it stops with a Python traceback (`UnicodeDecodeError`), so unpack
first as above (`lake scan` reads `.gz` directly).

`data ingest` writes JSON Lines into a Delta lake (the pack's layout, `business_date` partitions only) or the File connector's
folders (`--store files`); `--watch DIR` turns a drop folder into a feed. Use `lake build` instead for JSON arrays, gzip, large
files, classifier partitions or a lock and a summary; use `data ingest --store files` for the File connector. A dry run on the
300 trades, by start date:

```
$ drishti-console/.venv/bin/python tools/drishti.py data ingest --from build/tools-walk --domain trading --key TRADE_ID --date START_DATE --store files --root build/tools-walk/files --dry-run | tail -n 5
trade               2026-09-01             18
trade               2026-10-01             13
trade               2026-11-01              7
trade               2026-12-01              3
total rows: 300
```

`data load --store delta|files|postgres|duckdb|mongodb|redis|aerospike|cassandra|iceberg` runs the store's demo loader (section 6);
`data landed` announces a batch to a server and `data loads` lists what landed and what is due ([DATA_LOADS.md](DATA_LOADS.md)).
**Scheduling** `data ingest --watch DIR --once` from cron is a drop folder polled every few minutes (exit `1` when a file failed);
`data landed` is the last step of any ETL job that loads a store itself.

### 5.4 server and doctor

`server health` (exit `1` unless OK), `server caches` (what the engine and each connector hold; `server caches purge NAME|all`
drops a cache, and grouped pages kept on disk are rebuilt from the lake at once: Admin → Caches from the terminal),
`server packs list|load|unload|on|off|deploy|history|rollback|datasource` (Admin → Packs from the terminal), `server commands list|off|on|history`
(the administrator's switches of Functions, `FUNC`, from the terminal: commands, or with `--pack` packs, on or off for everyone with `--reason`; an administrator, and as the
admin endpoints are never for a personal token, it runs where sign-in is off (`--user`); [COMMANDS.md](../admin/COMMANDS.md))
and `server smoke` (opens sample views of every kind on two dates; exit `1` on any failure, the check after a deploy).
`doctor` checks this machine without changing anything (paths shortened):

```
$ drishti-console/.venv/bin/python tools/drishti.py doctor --server http://127.0.0.1:19590 --token-file ~/.drishti-token
GREEN  java                    /usr/lib/jvm/java-21-openjdk-amd64/bin/java: Java 21.0 (Ubuntu)
GREEN  server jar              <repo>/drishti-server/target/drishti-server-2.9.5-exec.jar (version 2.5.0)
GREEN  python                  <repo>/drishti-console/.venv/bin/python: Python 3.13.15
GREEN  python pyyaml           installed
GREEN  python deltalake        installed
GREEN  python pyarrow          installed
GREEN  repo config/packs/      <repo>/config/packs
GREEN  console venv            <repo>/drishti-console/.venv
GREEN  configured directories  none of DRISHTI_DELTA_ROOT, DRISHTI_FILES_ROOT, DRISHTI_PACKS_INSTALLED is set
GREEN  port 18480              in use by Drishti: status UP
GREEN  port 17480              in use by Drishti: answers 404 on /actuator/health, as the Drishti console does
YELLOW server                  http://127.0.0.1:19590: version 2.9.2, java 21.0.12.1, status DEGRADED
GREEN  token                   accepted as a signed-in session (not a personal drk_ token): its power is the user's roles
doctor: 0 red, 1 yellow
```

(A fresh clone after the quick start, with its server on port 19590; the usual ports 18480 and 17480 are checked as well. On a machine
without the jar or the console's environment the same lines are RED or YELLOW with the `fix:` to run.)

**Scheduling** `server smoke --json` every few minutes is a cheap synthetic monitor (exit `1` pages someone); `server health`
in a load balancer's script. Real output: [CLI_GUIDE.md section 7](CLI_GUIDE.md). In the console: Admin → **Health**.

The demo-lakes server of the quick start (the seven packs, and a pack a tutorial added; paths shortened), its packs, the smoke test of
the trading pack, a load announced by hand and the list of loads:

```
$ drishti-console/.venv/bin/python tools/drishti.py server health --server http://127.0.0.1:19590
status DEGRADED  version 2.5.0  java 21.0.12.1  uptime 59s
packs 8  sources 16 (degraded 0, down 1)  packs with problems 1  failed to start 0
  desk-quotes-store           DOWN      DOWN: cannot reach <data>/delta/desk-quotes (engine: native)
  ...
$ drishti-console/.venv/bin/python tools/drishti.py server packs list --server http://127.0.0.1:19590 | head -n 9
pack                  version  loaded  on   added  kinds
core                  1.0.0    yes     yes         10
market-data           1.0.0    yes     yes         20
trading               1.0.0    yes     yes         5
counterparty-risk     1.0.0    yes     yes         10
market-risk           1.0.0    yes     yes         6
world-economy         1.0.0    yes     yes         13
news-and-markets      1.0.0    yes     yes         4
desk-quotes           1.0.0    yes     yes  yes    1
$ drishti-console/.venv/bin/python tools/drishti.py server smoke --server http://127.0.0.1:19590 --pack trading --ids 1 --dates 1
OK   health                      status UP
OK   pack trading                loaded, enabled, v1.0.0, 5 kinds, no Sutra problems

kind                  id                        date        panels  empty  ms       result
trade                 1000001                   2026-10-16  10      0      17.4     OK
counterparty-trades   CP-ALDERSHOT              2026-10-16  10      0      154.0    OK
portfolio-trades      BOOK-COMM-1               2026-10-16  10      0      912.3    OK
legal-entity-trades   LE-FRA                    2026-10-16  10      2      285.4    OK
index-trades          AONIA                     2026-10-16  10      0      69.3     OK

5 views, p50 285.4 ms, max 1916.2 ms
smoke: passed
$ drishti-console/.venv/bin/python tools/drishti.py data landed --server http://127.0.0.1:19590 --pack core --kind desk --date 2026-10-16 --rows 10 --batch tools-guide-1 --source lake-build
desk for 2026-10-16 reloaded: 10 rows; 0 alerts fired
  load mvcy47uo-1  status ready  verified verified  attempt 1  (a reload)
  record  ok      recorded as a reload of an earlier ready load
  refresh ok      caches dropped: file, reference-store
  verify  ok      10 desk entities on 2026-10-16
  alerts  ok      0 alerts fired
  smoke   skipped not asked for (loads.smoke is 0)
  notices ok      1 person told
$ drishti-console/.venv/bin/python tools/drishti.py data loads --server http://127.0.0.1:19590 --pack core | head -n 4
received             kind              date        status        rows    rej  verified  alerts told  batch / source
2026-10-17 22:06:00  desk              2026-10-16  ready           10         verified       0    1  tools-guide-1 lake-build
2026-10-17 21:32:29  desk              2026-10-16  ready                      verified       0    1  lake-demo-20261017T173227 lake demo
2026-10-17 21:32:29  desk              2026-10-15  ready                      verified       0    1  lake-demo-20261017T173227 lake demo
```

The load is a *reload*: `lake demo` had announced every table and date already (the last two lines, one per business date it wrote).

### 5.5 connector

Admin → Connectors from the terminal: `connector list`, `connector get NAME [--yaml]`, `connector apply FILE` (create or change; the
version is read first and sent back as `If-Match`, so a concurrent edit is refused, not overwritten: GitOps-safe),
`connector delete`, `connector test NAME | --file F` (a throwaway instance tries the settings; exit `1` when it does not work),
`connector plugins`, `connector enable` / `connector disable`, `connector reset` (the pack's template back), `connector history`.

```bash
drishti-console/.venv/bin/python tools/drishti.py connector apply config/connectors/trade-endur.yaml --server http://localhost:18480 --token-file ~/.drishti-token
drishti-console/.venv/bin/python tools/drishti.py connector test trade-endur
```

Every change is audited and the old text kept ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)). The lake of section 4, tried
as a connector file before it is applied (`--file`: a throwaway instance on the server, nothing saved), then the server's connectors:

```
$ cat tools-trade.yaml
plugin: delta
description: the ENDUR trades of the TOOLS guide (lake build)
kinds: [trade]
settings:
  root: <repo>/lake
  domain: trading
$ drishti-console/.venv/bin/python tools/drishti.py connector test --file tools-trade.yaml --server http://127.0.0.1:19590
tools-trade (delta): reachable, health UP (engine: native), 38 ms
  kind   business date  rows  note
  -----  -------------  ----  ----
  trade  2026-09-23     159
  trade  2026-09-22     4407
$ drishti-console/.venv/bin/python tools/drishti.py connector list --server http://127.0.0.1:19590 | head -n 12
23 connector(s) in <repo>/config/connectors   (watching: WATCHING)
  name               origin  plugin    kinds                                                        state           used by            problems
  -----------------  ------  --------  -----------------------------------------------------------  --------------  -----------------  --------
  civic-store        file    delta     jurisdiction,party,office-holder +3                          running         world-economy
  collateral-store   file    delta     collateral-balance,margin-call,simm                          running         counterparty-risk
  credit-store       file    delta     netting-set,credit-limit,cva +1                            running         counterparty-risk
  desk-quotes        file    file      desk-quote                                                   running
  desk-quotes-store  file    delta     desk-quote                                                   down, retrying  desk-quotes
  ecb-estr-feed      file    feed      rate-fixing                                                  disabled        market-data
  ecb-fx-feed        file    feed      fx-spot                                                      disabled        market-data
  fred-feed          file    feed      rate-fixing                                                  disabled        market-data
  legal-store        file    delta     agreement,csa                                                running         counterparty-risk
```

Applying that file would add a fourth connector serving `trade` beside the three of the trading pack; to read the lake in place of the
demo lake, edit `trade-endur.yaml`'s `root` instead (Admin → Connectors, or `connector get trade-endur --yaml`, edit, `connector apply`).
More output: [CLI_GUIDE.md section 7](CLI_GUIDE.md).

### 5.6 design

The Screen Designer over REST: `design create` from samples or a Sutra file, `design save`, `design check` (exit `1` on
problems), `design propose`, `design approve` / `design reject` (an approver), `design proposals`, `design export` /
`design import` (a pack fragment), `design bind` (a file in your development directory), `design autodesign`, `design get`,
`design list`, `design delete`. The GitOps recipe (create, propose, approve from a pipeline) is in
[CLI_GUIDE.md section 8](CLI_GUIDE.md); the page is Build → My designs ([SCREEN_DESIGNER.md](SCREEN_DESIGNER.md)).

### 5.7 bi

Rupaka (business intelligence) as the token's user: `bi workspace list|get`, `bi catalogue list|get|publish`, `bi expr
functions|validate|convert` (the expression language), `bi migrate --expressions|--models` (the converter of old expressions and of
`rachana: 1` datasets to models; `--check` for CI), `bi sql` (Drishti SQL over a model), `bi model list|describe|check|consumers`,
`bi mq` (a model query), `bi item new|save|tests`, `bi dataset refresh` and `bi transform input|snippets|snippet` (Python data prep: a
Transform's inputs as you see them, masked columns and a profile of each column, and the snippets the Transform editor offers; the commands are in
`tools/bitransform.py`, loaded by `drishti.py`; [PYTHON_BI.md section 17](PYTHON_BI.md#17-data-prep-the-transform-editor-240)). A write needs the
`bi:write` scope.

**BI runs (2.6.7, `tools/biruns.py`)**: `bi runs list|get|submit|estimate|wait|result|cancel|rerun|export|share|unshare|promote|keep|schedule|schedules|storage|suggestions`,
and `--async [--wait 2s] [--yes]` on `bi mq` and `bi sql`, which send `Prefer: respond-async` and print the run's id when the answer is a `202`.
`bi runs wait` exits 0 done, 3 failed, 4 cancelled and 5 when its `--timeout` passes with the run still going; `bi runs submit` without
`--yes` exits 2 on a large question and prints its estimate; `bi runs storage` is the bi-admin's view of the result store and the queue.
`bi runs share RUN --to WHO` prints each recipient's mode (sees the same, re-masked, recomputed as them; a refusal on stderr) and `unshare
RUN WHO...` removes recipients; `bi runs schedule RUN "weekly mon 08:00"` and `schedules [list|edit ID WHEN|disable|enable|delete ID]`
use the subscription grammar; `bi runs export RUN --format csv|xlsx --out FILE`; `bi runs suggestions [--model M] [accept|dismiss ID]`
is the bi-admin's list of rollup suggestions. The guide with output: [BI_GUIDE.md section 28](BI_GUIDE.md#on-the-command-line).

```bash
drishti-console/.venv/bin/python tools/drishti.py bi runs submit --mq year.mq.json --yes --wait 0
drishti-console/.venv/bin/python tools/drishti.py bi runs wait r-java-2-01JA... --timeout 30m && drishti-console/.venv/bin/python tools/drishti.py bi runs result r-java-2-01JA... --out pnl.csv
```

```bash
drishti-console/.venv/bin/python tools/drishti.py bi expr validate "sum(trades.NOTIONAL) / count(trades)" --model trading-risk
drishti-console/.venv/bin/python tools/drishti.py bi migrate --expressions config/bi --check          # CI: exit 1 while a file has the old syntax
```

Captured, with a lower-case name the second time (the model's columns are the packs' UPPER_SNAKE names):

```
valid  (del2, aggregate scope, type decimal)
INVALID  (del2, aggregate scope, type any)
  error   DRS-9152  at 4-19: no field 'trades.notional'; did you mean trades.NOTIONAL?
```

Real output: [CLI_GUIDE.md section 7](CLI_GUIDE.md) and [EXPRESSIONS.md](EXPRESSIONS.md). In the console the migration's state is
on Admin → **BI** (items that could not be converted, with why).

### 5.8 view and completion

`view get KIND/ID` prints a rendered view as text tables, as the token's user sees it (masks apply); `view explain KIND/ID` the
About answer. `completion bash|zsh|powershell` prints a completion script generated from the command tree:

```
$ drishti-console/.venv/bin/python tools/drishti.py completion bash | head -n 3
# bash completion for drishti.py: generated by `drishti.py completion bash`
#   source <(drishti.py completion bash)         or save it as ~/.local/share/bash-completion/completions/drishti.py
_drishti_complete() {
```

### 5.9 net

Drishti\*net's `config/net/` folder from the terminal (2.5.0; [NET.md](../admin/NET.md)): `net list` (the files, in force, refused or absent, and what
they say), `net check [FILE ...]` (each file checked as the server does; exit `1` when one would be refused), `net add-peer NAME URL [--tags ...]`
(a seed in `peers/NAME.yaml`), `net set KEY VALUE` (one setting, such as `node.enabled true` or `gossip.interval 2s`; an empty value removes it) and
`net members` (the member table of a running server). The folder is `--dir`, else `DRISHTI_NET_DIR`, else `./config/net`; a write is atomic and keeps
the previous text under `<data dir>/net/history`, and the cluster key is only ever written as a reference (`secret: ${DRISHTI_NET_SECRET}`).

```bash
drishti-console/.venv/bin/python tools/drishti.py net check
drishti-console/.venv/bin/python tools/drishti.py net members --server http://localhost:18480 --token-file ~/.drishti-token
```

### 5.10 compute

Reactive connectors from the terminal (2.8.0; `tools/computecli.py`, [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md#the-http-api)), as
the caller's token:
- `compute run CONNECTOR KIND ID [--param NAME=VALUE ...] [--wait MS] [--date YYYY-MM-DD]` asks for a computed kind. It prints the answer, or
  `computing` with the request id when the result is not ready within `--wait` (default 5000 ms).
- `compute cancel REQUEST` cancels one of the caller's requests.
- `compute event TYPE ID [--kind] [--entity] [--date] [--payload NAME=VALUE ...] [--watermark LANE=SEQ ...]` sends an event to the
  connectors' triggers (`write-back`, `live`, `data-version`, `schedule`). Administrators only; an id already processed is ignored.
- `compute sandboxes` lists the caller's what-if sandboxes.
- `compute reactive` shows Admin's view of the reactive connectors: kinds, engine, cache, computations and latency.

Exit codes are `0`, `1` (refused, failed, or the server said no) and `2` (usage).

```bash
drishti-console/.venv/bin/python tools/drishti.py compute run path-sum-python path-sum BOOK-RATES --wait 10000 --json
drishti-console/.venv/bin/python tools/drishti.py compute event write-back chg-77 --kind path-strip --entity STRIP-FX-001 --payload BOOK_ID=BOOK-FX
```


### 5.10a change and edits

Write-back from the terminal (2.8.0; `tools/changecli.py`, [WRITE_BACK.md](../architecture/WRITE_BACK.md),
[SUTRA_FORMS_GUIDE.md section 4a](SUTRA_FORMS_GUIDE.md#4a-submitting-a-change-280-write-back)), as the caller's token (it needs the `edit` scope):
- `change propose --kind KIND --id ID --set FIELD=VALUE [...] [--from FIELD=VALUE ...] [--reason TEXT] [--sutra S --panel P] [--date YYYY-MM-DD] [--override]`
  proposes a change to a record's fields: the same request as a form's Submit. A field must be editable by a Sutra's form with `save:`;
  `--from` is the value you saw (a field that moved since is conflicted, never written over).
- `change submit DRAFT [--reason TEXT]` submits one of your form drafts (`d-...`): one change per record.
- `change list [--state awaiting|queued|applied|refused|conflicted|rejected|withdrawn] [--kind] [--entity] [--mine]` lists changes, newest first.
- `change show CHANGE` shows one change (exit `1` when it was refused, conflicted or rejected).
- `change approve CHANGE [--note TEXT]`, `change reject CHANGE --reason TEXT`, `change withdraw CHANGE`: decide (an approver, never your own
  change) or withdraw your own while it awaits approval.
- `change history KIND ID` lists a record's changes from the edit log and its version.
- `edits status` shows Admin → Edits: where the edit log lives and why, the lanes, warnings, unconfirmed source commands and each
  connector's `writes:` (administrators).

Exit codes are `0`, `1` (refused, conflicted, or the server said no) and `2` (usage).

```bash
drishti-console/.venv/bin/python tools/drishti.py change propose --kind trade --id MX-20000012 --set NOTIONAL=28001250 --from NOTIONAL=28000000 --reason "booking typo"
drishti-console/.venv/bin/python tools/drishti.py change list --state awaiting
drishti-console/.venv/bin/python tools/drishti.py change approve chg-01M5V6GM7NCKSR506RQ86M7YVV --note "ticket 4411"
drishti-console/.venv/bin/python tools/drishti.py edits status
```

### 5.11 contract

The data contract of every command (`tools/contracts.py`, also run on its own as `python3 tools/contracts.py ...`;
[docs/contracts/README.md section 16](../contracts/README.md#16-contracts-of-every-command)). Each mnemonic's contract is
`config/packs/<pack>/contracts/<kind>.contract.yaml`, checked against `tools/contracts.schema.json`. No server is needed.
- `contract list` prints every command with its kind, pack, class, status and the number of points still to review.
- `contract show NSET` prints one contract (by command or kind; `--json` as JSON).
- `contract check` fails when a command has no contract, a contract is invalid or no longer matches its pack (identity, id pattern,
  links, the retirements in `tools/contracts.policy.yaml`) and its samples and test data (fields added or gone, a required field empty,
  a value outside an enum, a changed type). `--no-data` skips the data and takes a second.
- `contract docs` writes `docs/contracts/packs/<pack>.md` and the index `docs/contracts/packs/README.md`; `--check` fails when they are stale.
- `contract bootstrap KIND` drafts a contract from the pack, its samples and test data, its `config/about.yaml` glossary and its Sutras,
  and prints it; `--write` saves it (`--force` replaces one that exists), `--all` drafts every command without one. What the draft infers
  rather than reads is listed under `review` for a person to confirm.

Exit codes are `0`, `1` (a problem found, or the contract exists) and `2` (usage). `--packs DIR` and `--testdata DIR` point at other folders.

```bash
python3 tools/drishti.py contract show NSET
python3 tools/drishti.py contract check
python3 tools/drishti.py contract bootstrap my-kind --write && python3 tools/drishti.py contract docs
```


**The command catalog.** `python3 tools/command_catalog.py` writes [COMMAND_CATALOG.md](COMMAND_CATALOG.md): every command of the command
line with what it does and an example, from the packs, their contracts and `tools/commands.builtin.yaml` (the command line's own words and
forms). `--check` fails when it is stale; a console test runs the same check.

### 5.12 var

The VaR engine from the terminal (2.8.0; `tools/varcli.py`; [VAR_GUIDE.md](VAR_GUIDE.md), the API `/api/v1/var`), as the token's user sees
it (their nodes, their masks). A scope is the scope grammar's text: quote it in the shell (`'BANK - DESK:CREDIT'`). `--date YYYY-MM-DD`
picks the business date (default the newest with vectors); `--json` prints the server's answer.
- `var show SCOPE` prints the headline: VaR and ES with their ranks, the limit and its use, the parts and diversification, the backtest,
  the trades counted and hidden, the vectors' batch and where the vector came from (`node aggregate (float64)`, `hot file …`).
- `var parts SCOPE [--by child|node|position|trade|desk|book] [--scenario DATE] [--top N]` prints the contributions: standalone,
  component (and its share of the VaR), incremental VaR and component ES per part.
- `var path SCOPE [--page N] [--size N]` prints the scenario days worst first, and the part that lost most on each.
- `var history SCOPE` prints the history and backtest (the official record for a node, else the replay of today's vector) with the
  traffic light, Kupiec and Christoffersen.
- `var tree [--hierarchy MGMT|LEGAL]` prints every node with its VaR, ES and limit use, indented.
- `--ccy EUR` (2.9.4; `show`, `parts`, `path`, `history`, `tree`, `capture`, `official`, `whatif`) asks every figure in a reporting
  currency; the headline says the currency and where its FX came from ([VAR_GUIDE.md section 15.10](VAR_GUIDE.md#1510-reporting-currency-as-built-294)).
- `var status` prints the engine's settings, its tiered memory (hot files on disk, dates open, how this JVM maps) and the dates loaded.
- `var shards [--date D] [--json]` (2.9.6): VaR sharding across Drishti*net: the settings, the members, the shards held here, and the
  date's shard map with each shard's range, owners and the members ready for it ([VAR_SHARDING.md](../architecture/VAR_SHARDING.md));
  `--drain` / `--undrain` (administrators) hand this member's shards over before it stops, or take shards again.
- `var capture SCOPE [--note TEXT]`, `var captures [--scope SCOPE]`, `var compare A [B|now]`: captures (writing needs `risk:write`).
- `var basket NAME SCOPE [--title] [--description] [--share USER|role:NAME ...]`, `var baskets`: saved baskets.
- `var official [--date] [--capture]`: a date's official end-of-day record; `--capture` takes it now (administrators).
- `var reload`: drops the loaded dates (administrators).
- `var intraday [--date D]`: each loaded date's intraday layer (2.9.4: updates, trades, batches, what was ignored or refused); `var intraday --load FILE.jsonl [--date D] [--lake DIR] [--no-signal] [--dry-run]` appends an intraday batch to the lake (`lake build --mode append`) and raises its data-load signal ([VAR_GUIDE.md section 8.4](VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)); a file mixing scenario PVs and P&L vectors is routed by each record's `VALUE_TYPE`.
- `var convert FILE.jsonl [--set FIELD=VALUE ...] [--infer] [--out F]`: 2.9.4, makes an older PV or vector file say what each record is (`VALUE_TYPE`, `GRAIN`, `CURRENCY`, `SOURCE_SYSTEM`, leading each line), e.g. `--set VALUE_TYPE=PNL --set GRAIN=TRADE --set CURRENCY=USD --set SOURCE_SYSTEM=MUREX`; `--infer` takes `VALUE_TYPE` from the payload and `GRAIN` from the id; exit 1 when a record still lacks one ([VAR_GUIDE.md section 15.13](VAR_GUIDE.md#1513-inputs-any-form-any-grain-as-built-294)).
- `var whatif SCOPE [--trades FILE.json] [--stress-window DAYS]`: the scope's VaR before and after hypothetical trades (a JSON list of
  `{LABEL, PNL: [one number per scenario day], FACTOR}`, put in a private sandbox) and its stressed window, by the Python VaR engine
  (the `var-whatif` connector; the compute workers must be on).

Exit codes are `0`, `1` (the server said no, or a what-if refused or failed) and `2` (usage).

```bash
drishti-console/.venv/bin/python tools/drishti.py var show 'DESK:RATES'
drishti-console/.venv/bin/python tools/drishti.py var parts 'BANK - DESK:CREDIT' --by desk --date 2026-09-30
drishti-console/.venv/bin/python tools/drishti.py var whatif 'DESK:RATES' --trades new-swap.json --stress-window 250
```

### 5.13 exposure

The exposure engine from the terminal (2.9.0; `tools/expcli.py`; [EXPOSURE_GUIDE.md](EXPOSURE_GUIDE.md), the API `/api/v1/exposure`),
as the token's user sees it (their counterparties, groups and legal entities; trades, aggregate or none). A scope is the scope grammar's
text (`BANK`, `LE:LE-NY`, `GROUP:GRP-ALDERSHOT`, `CPTY:CP-ALDERSHOT`, `NSET:NS-ALDERSHOT-LDN`, `TRD:<id>`, `DESK:`, `BOOK:`,
`WHERE:<rule>`, with `+ - &`): quote it in the shell. The scoped subcommands take `--date YYYY-MM-DD` (default the newest date with
paths), `--q Q` (the PFE confidence, default 0.95) and `--collateral lagged|current|none` (default `lagged`); every subcommand takes
`--json` (the server's answer).
- `exposure show SCOPE` prints the figures (peak PFE and its date, EPE, EEPE, CE, EAD under the internal model, informational), the
  stored credit limit's use where one applies, and the profile (EE, ENE, EEE, PFE, PFE 99%, EE without collateral per primary date).
- `exposure parts SCOPE [--by child|netting-set|counterparty|legal-entity|trade|asset] [--figure PFE|EE|EPE] [--at TENOR|DATE]
  [--top N]` prints the contributions (to the peak PFE they add up; EE at a date; EPE) with the incremental PFE of each part.
- `exposure tree [--date] [--q]` prints the bank, legal entities, counterparties and netting sets with peak PFE, EPE and limit use.
- `exposure profile SCOPE [--csv]` prints the profile at every primary date (`--csv`: `tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll`).
- `exposure distribution SCOPE [--at TENOR|DATE]` prints the exposure distribution at a primary date (the peak by default) and the
  path fan at every date.
- `exposure status` prints the engine's settings, its hot files and the dates loaded.
- `exposure capture SCOPE [--note TEXT]`, `exposure captures [--scope SCOPE]`, `exposure compare A [B|now]`: captures, immutable
  (writing needs `risk:write`).
- `exposure official [--date] [--capture]`: a date's official end-of-day record; `--capture` takes it now (administrators).
- `exposure retention`: what the retention keeps of each date held, and the dates whose trade paths are beyond their window.
- `exposure reload`: drops the loaded dates (administrators); the next request maps the hot file again or reads the lake.
- `exposure whatif SCOPE [--like TRADE] [--factor F] [--set NETTING_SET] [--candidates FILE.json] [--method paths|addon]`: the
  scope's exposure before and after candidate trades. `--like` copies a booked trade's paths on the run (`--factor -1` is its unwind),
  `--set` the netting set it joins (default a set of its own); `--candidates` is a JSON list of `{tradeId, nettingSet, paths:
  {copyOf, factor} or {runId, mtm}, assetClass, notional, maturityDate, mtm}`. `paths` (the default) is exact, by the Python exposure
  engine (`exposure-whatif`; the compute workers must be on, `DRISHTI_COMPUTE_ENABLED=true`, else `DRS-5446`); `addon` is the
  conservative proxy computed by the server.

Exit codes are `0`, `1` (the server said no) and `2` (usage).

```bash
drishti-console/.venv/bin/python tools/drishti.py exposure show CPTY:CP-ALDERSHOT
drishti-console/.venv/bin/python tools/drishti.py exposure parts NSET:NS-ALDERSHOT-NY --by trade --figure EE --top 10
drishti-console/.venv/bin/python tools/drishti.py exposure whatif CPTY:CP-ALDERSHOT --like CLY-4003028 --factor -1 --set NS-ALDERSHOT-FRA
```

## 6. Store loaders: `tools/load-*.sh`

Each loader writes the banking packs' demo data (1,791 sample documents x 10 business days, and with `--trades N --days D` a
generated trade book streamed in) into one store, **in the layout that store's connector reads at scale**. `drishti.py data load
--store S` runs them with the same options. Keep local runs small (`--trades 10000`).

| Script | Store and layout | Retention |
|---|---|---|
| `tools/load-delta.sh` | Delta Lake under `data/delta`, the pack's layout (`tools/windows/load-delta.ps1` on Windows) | `drishti.py lake maintain` |
| `tools/load-files.sh` | the File connector's JSON Lines, `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl`, each file replaced whole | delete old day folders |
| `tools/load-postgres.sh` | PostgreSQL, `<domain>.entities` partitioned by month, promoted columns | `--keep-months N` |
| `tools/load-duckdb.sh` | one DuckDB file, written beside and renamed over the old one | `--keep-days N` |
| `tools/load-mongodb.sh` | MongoDB, a collection per domain, zstd, the `day_ids` index | `--keep-days N` or `--ttl-days N` |
| `tools/load-redis.sh` | Redis, a compressed document per entity per day, each day replaced atomically | `--ttl-days N` |
| `tools/load-aerospike.sh` | Aerospike, a set per domain, promoted fields as bins | `--ttl-days N` |
| `tools/load-cassandra.sh` | Apache Cassandra or ScyllaDB, a keyspace per domain, a partition per day per bucket, each day under a new generation (appears whole); `--create` makes the tables (`tools/windows/load-cassandra.ps1` on Windows) | `--ttl-days N` or `--keep-days N` |
| `tools/load-iceberg.sh` | Apache Iceberg tables (a folder or a REST catalog), sorted by id, external sort with `--spill-dir`; `--engine native` writes with drishti-iceberg, no Hadoop (`tools/windows/load-iceberg.ps1` on Windows, native by default) | `--keep-days N` |

```bash
tools/load-duckdb.sh /tmp/demo.duckdb --trades 10000 --days 3
drishti-console/.venv/bin/python tools/drishti.py data load --store files --trades 1000 --days 1 -- /tmp/demo-files
```

**Scheduling.** The loaders guard dates (a row dated after tomorrow is not loaded and the run fails naming it), replace each day
they reach in one step, and refuse to drop more than half a table (`--max-drop-share`, `--force-drop`), so a nightly cron entry
that re-runs is safe; the details are at the top of each script and in [DEMO_DATA.md](../connectors/DEMO_DATA.md). The connectors.
guides are listed in [CONNECTORS.md](../connectors/CONNECTORS.md).

## 7. Lake maintenance: `tools/lake/maintain.py`

> From the browser, with schedules, settings per table and a history: **Admin → Lakes → Maintain**, [LAKE_MAINTENANCE.md](../admin/LAKE_MAINTENANCE.md).
> Its parts beside `maintain.py`: `tools/lake/maintconfig.py` (the maintenance YAML and the run history), `tools/lake/maintschedule.py` (the one
> scheduler, used by `--daemon` and by the console) and `tools/lake/ownership.py` (which tables Drishti's lake tool built: the only ones it maintains).

`drishti.py lake maintain` (the same as `tools/lake/maintain.py`) keeps a lake bounded: **retention** (business dates older than
`keep-business-days`, never more than `max-drop-share` of a table without `--force-drop`), **compaction** of each day's small
files (or a re-layout into the pack's declared layout), a **checkpoint**, and **vacuum** of unreferenced files. Local lakes and S3. An Iceberg
table (`format: iceberg`, or found under a local root) gets retention, relayout, snapshot expiry and orphan-file removal instead
(`tools/lake/maintain_iceberg.py`, [LAKE_MAINTENANCE.md section 8](../admin/LAKE_MAINTENANCE.md#8-iceberg-lakes)).

**Who maintains a table.** Whoever writes a table maintains it.

* A table **another system publishes** (for instance Endur's own export job writing a Delta table) is maintained by that system's
  owner. Drishti only reads it: never point `lake maintain` at it, never compact or vacuum it.
* A table **Drishti's lake tool builds** (`lake build`, `lake demo`) is Drishti's. Schedule `lake maintain` in the same job, right
  after the build, on one machine: a compaction that races an append makes one of the two commits retry.
* `lake demo` already writes a checkpoint for each table it builds, so the demo lakes need nothing more.

A dry run on the lake of section 4:

```yaml
# maint.yaml
schedule: {at: "02:30", zone: America/New_York}
lakes:
  - root: ./lake
    domains: ["*"]
    keep-business-days: 520
    compact: true
    checkpoint: true
    vacuum-hours: 168
```

The domains `risk` and `risk-results` keep 7 years whatever the file says (the VaR contract, [VAR_GUIDE.md section 23.1](VAR_GUIDE.md#231-open-questions-of-the-contract)):
a lower `vacuum-hours` or `keep-business-days` for one of their tables is raised to 7 years and the run says so (`retention-floor`);
a lake's `retain-years: {risk: 7}` changes it per domain (`0`: no floor). `lake build` creates their tables with 7 years of Delta log
and removed files (`delta.logRetentionDuration`, `delta.deletedFileRetentionDuration`), so their time travel reaches back that far.

```
$ drishti-console/.venv/bin/python tools/drishti.py lake maintain --config maint.yaml --once --dry-run
{"at": "2026-10-17T18:07:13-04:00", "event": "maintained", "dry_run": true, "table": "lake/trading/trade", "before": {"files": 9, "mb": 7.48}, "retention": {"cutoff": "2024-10-21", "would_remove_files": 0}, "stats-columns-set": false, "compact": {"relaid-dates": ["2026-09-22", "2026-09-23"], "files": 0, "rows": 0}, "vacuum": {"files": 0, "dry_run": true}, "after": {"files": 9, "mb": 7.48}}
```

One JSON line per table and step, for logs and monitoring; one failing table is reported and the rest go on (exit `1`). The shipped
configuration is `deploy/lake-maintenance.yaml`.

**Scheduling.** Either the job's own daemon (`--daemon`: every day at `schedule.at` in `schedule.zone`, for a container) or a
timer that runs `--once` after the night's loads. It must not run while a `lake build` writes the same lake: schedule it after the
builds (02:30 here, after a 21:30 build), and on one machine only.

```cron
30 2 * * 2-6  cd /opt/drishti && drishti-console/.venv/bin/python tools/drishti.py lake maintain --config deploy/lake-maintenance.yaml --once >> /var/log/drishti/lake-maintain.log 2>&1
```

```ini
# /etc/systemd/system/drishti-lake-maintain.service   (+ a .timer with OnCalendar=Tue..Sat 02:30, Persistent=true)
[Service]
Type=oneshot
User=drishti
WorkingDirectory=/opt/drishti
ExecStart=/opt/drishti/drishti-console/.venv/bin/python tools/drishti.py lake maintain --config deploy/lake-maintenance.yaml --once
```

## 8. Generators: `tools/packgen`, `tools/samplegen`, `tools/realdata`

| Tool | What it makes |
|---|---|
| `tools/packgen/comswap_synth.py` | **The synthetic Endur-like commodity-swap book**: deals with 1 to 4 legs, monthly cash flows, resets, events, packages and novations; upper-case keys in a fixed order, digit-string codes that must stay text, blanks spelt as `""`, `null`, `0` and a missing key. `--mix` makes a whole book (ComSwap, ComFut, Cash, Commodity and a rare CallNot by `PRODUCT_TYPE`). Deterministic (`--seed`); at most 10,000 deals. Every value is invented. |
| `tools/packgen/note_synth.py` | a synthetic structured note of another shape (camelCase keys; note, coupon periods, observations, fixings; a call schedule and a basket of underlyings), to show the pack generator is generic. Every value is invented. |
| `tools/packgen/pack_from_jsonl.py` | the engine of `drishti.py pack new`: a complete pack from a folder of JSON Lines |
| `tools/contracts.py` | the data contract of every command (`drishti.py contract`, [5.11](#511-contract)): drafts one from a pack and its data, checks them all, writes `docs/contracts/packs`; the schema `tools/contracts.schema.json` and the product owner's decisions `tools/contracts.policy.yaml` beside it |
| `tools/knowledge.py` | the Knowledge pages' glossaries (`docs/knowledge/`): writes each page's terms and the home's A to Z from `docs/knowledge/glossary.yaml`; `--check` fails when a page differs or the glossary is invalid (`tools/test_knowledge_pages.py`) |
| `tools/packgen/readme.py` | a pack's `README.md` (the README standard, [PACK_DEVELOPER_GUIDE.md Part IV](PACK_DEVELOPER_GUIDE.md#the-readme-standard)) for a pack without a generator of its own; `--all --check` fails on any pack whose README is missing, stale or breaks the standard |
| `tools/packgen/common/guide_data.py` | every pack guide's *Data contracts* and *Data retrieval* sections (from the contracts and `data_retrieval.yaml`; the pack generators call it); run alone it writes compute-demo's guide and the generated block of the news-and-markets guide; `--check` fails when they are stale ([PACK_DEVELOPER_GUIDE.md Part IV](PACK_DEVELOPER_GUIDE.md#the-readme-standard)) |
| `tools/connector_readmes.py` | the settings tables of every connector README (from `plugin-settings.yaml`) and the file tables of the template folders' READMEs; `--check` fails when one is stale ([CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#the-readme-standard)) |
| `tools/packgen/banking/make_data.py`, `make_packs.py`, `make_sutras.py`, `make_docs.py` | the banking packs: their sample data and lake, manifests, Sutras and guides, from one taxonomy |
| `tools/packgen/climate/make.py`, `liquidity/make.py`, `oprisk/make.py`, `retail/make.py` | the climate-risk, liquidity-risk, operational-risk and retail-banking packs, built on the banking data |
| `tools/packgen/genomics/make.py` | the genomics pack (public reference facts, synthetic samples) |
| `tools/packgen/world/make.py` (the halves `economics/make.py`, `politics/make.py`) with `tools/realdata/realdata.py` | the world-economy pack from real public data (World Bank, IMF; internal demos only) |
| `tools/packgen/trades/make_trades.py` | **The trading pack's trades of three source systems** in the common schema (upper-snake, `LEGS` → `CASH_FLOWS` → `RESETS`, `EVENTS`): `--trades N` per system (ENDUR, CALYPSO, MUREX), `--days D` business days with about 6% amended each day, one `<out>/<system>/trades-<date>.jsonl.gz` a day; with `--lake <root>` each goes through `lake build --connector trade-<system>` into that system's table (`trade_endur`, `trade_calypso`, `trade_murex`). Deterministic; 10,000 trades or fewer on a laptop. `drishti-console/.venv/bin/python tools/packgen/trades/make_trades.py --trades 50 --days 2 --out data/build/trades` prints `ENDUR: 50 trades x 2 days -> data/build/trades/endur` and the same for CALYPSO and MUREX. |
| `tools/packgen/var/make_var_data.py` | **The VaR engine's inputs** ([VAR_GUIDE.md Part II](VAR_GUIDE.md#14-the-contract-at-a-glance)) from the committed trades and desks, as an upstream risk batch delivers them: one 509-scenario P&L vector per live trade (every one: statuses NEW, VALIDATED, AMENDED, not matured; 9,572 trades) with the terms the server derives positions from (a synthetic `SECURITY_ID` shared by several trades for securities and listed products, by the market-risk pack's `positions:` rules), the scenario set, the MGMT and LEGAL hierarchies with limits, and 28 portfolio definitions (a desk's, a legal entity's, rules and baskets, overlapping), written to `data/testdata/market-risk/<kind>/` with their `testdata.yaml` entries, and the pack's samples and Sutra tests. No position, VaR or node history is written: the server derives and computes them. `--budget N` values at most N trades per book (default 0: every live trade), `--out` writes elsewhere, and `--check` fails when the committed files differ. Since 2.9.4 the form is a choice per system: `--pv-systems ENDUR` (the default: Endur's trades as scenario PVs in their own currency, with the scenario FX), `--pnl-systems` (as P&L vectors), `--all-vectors` (every trade a P&L vector, exactly 2.9.3's files: the bank 112,035,906 / 160,132,107). `--position-systems MUREX` sends those systems per position (`GRAIN: POSITION`, naming their trades in `TRADE_IDS`); `--inputs-config` prints the `drishti.var.inputs` pins matching the data. Deterministic; `tools/packgen/var/test_var_data.py` checks the data. |
| `tools/pvgen/morning.py`, `intraday.py`, `publish_kafka.py`, `check.py` (each with a `.sh` beside it and a PowerShell form: `pvgen-morning.ps1`, `pvgen-intraday.ps1`, `pvgen-publish-kafka.ps1`, `pvgen-check.ps1` in `tools/windows`) | **A stand-in pricing system** (2.9.4, [VAR_GUIDE.md section 15.12](VAR_GUIDE.md#1512-producing-pl-vectors-and-scenario-pvs-as-built-294)): JSONL only, every line one row of its kind's contract. Every record leads with what it is (`VALUE_TYPE`, `GRAIN`, its id, `CURRENCY`, `SOURCE_SYSTEM`); `--grain position` (or `--position-systems SYS`) sends per position instead of per trade. `morning.py` writes a full day's `scenario-pv`, `pnl-vector` and `fx-scenario` (per-system forms as above, `--date`, `--lake`, `--server`); `intraday.py` N small batches of amended, new and cancelled trades (`--batches 20 --trades-per-batch 15`, `--seed`, `--first` for a second run, `--lake`, `--server`, `--every 30s`); `publish_kafka.py` the same to the PV topic, each message JSONL text of `--per-message` records, a cancel a cancel line (`--rate`, `--seconds`; kafka-python); `check.py` recomputes the bank's and the desks' VaR and ES from the files and compares them with the server. Each has `--help` and `--dry-run`; `tools/pvgen/test_pvgen.py` checks them. `drishti-console/.venv/bin/python tools/pvgen/morning.py --dry-run` prints `morning run of 2026-09-30: 1,339 trades as scenario PVs (ENDUR), 8,233 as P&L vectors, scenario FX for 6 currencies; computed at 2026-09-30T06:00:00Z`. |
| `tools/packgen/ccr/make_ccr_data.py` | **The exposure engine's inputs** ([EXPOSURE_GUIDE.md](EXPOSURE_GUIDE.md)) from the committed netting sets, as an upstream exposure simulator delivers them: a simulation run (`simulation-run`: 200 paths, 13 primary dates 1W .. 10Y and their 10-business-day look-back dates) and every live member trade's MtM on every path at every grid date up to its maturity (`exposure-path`) for eight netting sets, written to `data/testdata/counterparty-risk/<kind>/` with their `testdata.yaml` entries, the pack's samples and `drishti-compute`'s parity reference (`ccrmath.py`'s figures). No exposure figure is written. `--check` fails when the committed files differ, `--full --out DIR` writes every netting set that fits in 10,000 trades with 500 paths (local benchmarks), `--demo --out DIR` writes the demo lake's run (whole counterparties within 9,500 trades, 200 paths: 74 netting sets, 8,022 live trades on the data's date; what `lake demo --exposure demo` loads), `--reconcile I/N --stored DIR` compares the retired stored profiles with the computed ones. Deterministic; `tools/packgen/ccr/test_ccr_data.py` checks the data. |
| `tools/samplegen/bulk_trades.py` | a large trade book for scale tests, N trades over D business days: one JSONL file a day under `data/build/bulk/<system>/`, then each through `lake build` into the system's trade table (`load-delta.sh --trades`; `--no-lake` writes the JSONL only, `--jsonl FILE\|-` loader rows for the other stores) |
| `tools/demonames.py` | the bank's own names in the demo data (`config/demo/names.yaml`): the mapping `lake demo --names` and `build_demo_lakes.py --names` apply as they load, and the pack generators as they write samples ([DEMO_DATA.md](../connectors/DEMO_DATA.md#your-banks-names)) |
| `tools/convertcmd.py` (`drishti.py convert FILE`) | CSV (deprecated input), a JSON array or a folder of per-entity `.json` files into JSON Lines ([CLI_GUIDE.md](CLI_GUIDE.md#convert-csv-a-json-array-or-a-folder-of-json-files-into-json-lines)) |
| `tools/samplegen/lake.py`, `tools/samplegen/load_rows.py` | one pack's sample history as dated JSONL loader rows (`<name>-history.jsonl`, `<name>-restated.jsonl`), and the loader that puts such rows into Delta ([DATA_FORMATS.md](DATA_FORMATS.md)) |

The examples on this page read the committed test data (`data/testdata`, written by `tools/testdata/make_testdata.py`). It writes the
same bytes on every Python version and machine: amounts that are sums (a netting set's `NET_MTM`, a counterparty's) are added with
`math.fsum` to the cent, never the built-in `sum()`, whose float result differs at the last bit between Python 3.13 and 3.14
(`tools/testdata/test_deterministic.py`; give `DRISHTI_OTHER_PYTHON=<another python>` to compare two versions), so
`make_testdata.py --check` passes wherever it runs. For a book
of another size, or a JSON array as a booking system exports it (`--array`; without it, JSON Lines), the generators write one:

```
$ drishti-console/.venv/bin/python tools/packgen/comswap_synth.py --deals 2000 --mix --array --out build/tools-walk/book-2026-10-16.json
wrote 2000 synthetic deals to build/tools-walk/book-2026-10-16.json
```

2,000 deals are 27 MB of pretty-printed JSON (about 14 KB a deal); the lake of section 4 holds the 5,169 rows of two days in 7.5 MB
of Parquet.

## 9. Developer tools

| Tool | What it is for |
|---|---|
| `tools/drill.sh` | "Drill it": licence check, the Java verify (21 and 25), the console and tool tests in parallel, then push develop, merge into main, push main; nothing is pushed unless all pass. `--docs` for a docs-only change, `--no-push` to verify only. Once per release wave. |
| `tools/license_headers.py` | checks (exit `1` naming the files) or, with `--fix`, inserts the copyright header in every source file |
| `tools/elements_sheet.py` | builds the `<drishti-view>` and report stylesheets from the console's CSS (run it when `bi.css` or the report CSS changes) |
| `tools/elements_package.py` | builds the npm package of Drishti Elements |
| `tools/make_icons.py` | the console's favicon and app icons from the logo mark |
| `tools/fetch-pyodide.sh` | installs the pinned, checksummed Pyodide runtime Calc serves from its own origin (no CDN) |
| `tools/docs/screenshots.py` | regenerates the guides' screenshots (Playwright, scratch ports); `drishti.py docs shots` runs it |
| `tools/rachana/md_to_yaml.py` | converts Markdown Sutras (before 1.11) into YAML Sutras |
| `tools/maps/build_regions.py` | builds the vendored region maps from Natural Earth |
| `tools/packreg/packreg.py` | the signed pack registry behind `pack publish`, `pack keygen` and `pack install` |
| `tools/bench/trades.py` | synthetic trades of the real ENDUR, CALYPSO and MUREX shapes at scale (`--min`/`--max` a day per system, `--days`, `--counterparties`, with netting sets), trades repeat across days and change like a full daily dump (about 5 % amended a day, a few cancelled), loaded day by day through `lake build`; `--parallel` loads a day's tables side by side, `--order random` loads the days in a shuffled order, `--skip`/`--start` resume a run; writes only under `--lake` and `--tmp`; `--dry-run` says the days and counts ([SCALE_BENCHMARK.md section 9](../admin/SCALE_BENCHMARK.md)) |
| `tools/bench/users.py` | simultaneous users against a server's API (`--server`, `--token-file`) or a console's pages (`--console`, `--user`, `--password`) for `--seconds`: a mix of single trades, counterparty pages, netting sets and searches, and with `--history-share` that share of histories (30 and 60 days, 3M, DIFF, now and then *known at*; `--equities` adds equities); one JSON line with requests a second, p50/p95/p99 and failures per kind of request, and the failures per 10 s (`failedBuckets`) |
| `tools/bench/net_soak.py` (with `tools/bench/soak_check.py`) | the Drishti\*net soak on real processes: three servers (each with only the packs and connectors its lake holds, the others off by connector file), a console, `users.py` load, a shard and the seed killed and restarted, every answer (views, searches, about 140 distinct histories with ten ranges as administrator and as viewer, whose `PRICE` or `NAME` must read masked on every row and in every diff) checked against one server holding all the data; samples memory, heap, open files and threads every minute (the console with all its worker processes, summed and one by one) and ends with a verdict (no wrong answers: each one kept with its time, request and the rule that failed, the first 200 with `partial`, `problems` and a compact diff; errors only in kill windows, p95/p99 per action, memory flat after warm-up: the slope in the steady spans outside the kill windows and `--settle` seconds after each, the whole-run slope reported beside it), exit `0` only when it passes ([DRISHTI_NET.md section 17.1](../architecture/DRISHTI_NET.md#171-the-soak-with-history-queries-and-long-runs-265)) |
| `tools/bench/scale.sh`, `tools/bench/pinning.sh` | the scale benchmark per store and size, and the virtual-thread pinning comparison (Java 21 / 25); they start and stop their own servers by process id |
| `tools/windows/start-server.ps1`, `tools/windows/start-console.ps1`, `tools/windows/load-delta.ps1` | starting the server and console, and loading the demo lake, on Windows |
| `tools/windows/publicdata-funds.ps1`, `publicdata-gleif.ps1`, `publicdata-market-data.ps1`, `publicdata-news-and-markets.ps1`, `publicdata-us-equities.ps1`, `publicdata-world-economy.ps1` | the public-data refreshes of those packs on Windows: each runs `tools/publicdata/<pack>.py` with the console's Python (`status`, or `refresh` with the same options) |
| `tools/smoke.py` | the engine of `drishti.py server smoke` |
| `tools/javacmd.py` | `java_cmd()`: the one helper every Python launcher of a JVM uses, so each java it starts gets `-Djava.io.tmpdir=` (native libraries unpack in Drishti's own temp folder, not `/tmp`); a guard test fails on a tool that starts java without it ([CONFIGURATION.md](../admin/CONFIGURATION.md)) |

```
$ python3 tools/license_headers.py
$ echo $?
0
```

A two-hour soak, the shard killed at 20 minutes and the seed at 60 (on a workstation with no other heavy job running; nothing is written
outside `--work`):

```bash
python3 tools/bench/net_soak.py --work ~/drishti-scale-test/net-2h --users 20 --seconds 7200 --days 10 \
    --kill-shard-at 1200 --restart-shard-at 1500 --kill-seed-at 3600 --restart-seed-at 3900 \
    --history-share 0.15 --sample-every 60 --warmup 600 --max-growth 64
```

It prints the latency and error table (one line per action, `history` its own) and the verdict, and keeps them in
`<work>/soak-report.txt` beside the full `<work>/soak.json` (the per-minute samples are its `resources`).

## 10. The tools in the console

### 10.1 Build → New pack (`/build/pack/new`)

Bring schema files and JSON or JSON Lines data; the page profiles every file in the browser (arrays streamed), proposes kinds, ids,
business dates and one Sutra per type, previews them, and builds a pack bundle to download or deploy. The command-line twin is
`drishti.py pack make`. Guide: [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md).

### 10.2 Admin → Data loads → Lakes (`/admin/lakes`)

Data loads (`/admin/loads`) shows what each pack expects and what landed; its **Load a file into a lake** button opens the Lakes
page, the browser form of `lake build`. Administrators only.

The lake roots are `lakes.roots` (`DRISHTI_LAKE_ROOTS`); unset, `<data dir>/delta`, the server's own root. Every table under them is
listed with its rows, files, size, partitions and dates; **click a table's name** for its own page (`/admin/lakes/table`), read
from its Delta log alone (no data file is opened, so it opens at once on a table of millions of rows):

| Section | What it shows |
|---|---|
| Summary | rows, business dates (first to latest), files and size, table version and last commit, partition columns |
| Read by | the Delta connectors whose domain and kind's table are this table, with their state (each links to Admin → Connectors) |
| Business dates | newest first: rows (with a bar against the largest date), files, size, and the partitions with their rows |
| Columns | each column, its type and its role: the id, the whole document (`doc`), a partition, a promoted field |
| Commits | the newest 30: version, time, operation, its parameters (mode, predicate) and metrics (files and rows added), writer |
| Loads | the table's runs of `lake build` under this root: outcome, mode, dates, rows, rejected, seconds, engine |
| Maintenance | retention, target file size, checkpoints, vacuum (Lakes → Maintain changes them), or why it is not maintained here |
| Table | table id, created, protocol, table properties |

1. **The file.** Choose a `.json` (array), `.jsonl`, `.ndjson` or `.gz` file. It is streamed to the console's temporary folder as
   it uploads (a progress bar; never held in memory; up to `lakes.max_upload_mb`, 4,096 MB).
2. **Id, business date and classifiers.** The console scans the file as a stream (the first `lakes.scan_docs`, 20,000, documents)
   and lists every field with how often it is present, how many values it has and the most frequent ones. The suggested id, date
   and first classifier are selected; *None: one date for the whole file* is the `--as-of` of a snapshot. Tick **Classifier** for
   the partition columns and **Promote** for the real columns.
3. **Where it goes.** A new lake (a root from `lakes.roots`, a domain, a kind) or an existing table (its partitioning is then
   kept and shown); what to do when a date is already there (the three modes of 4.4); and the pack whose Delta connector should
   read it. **Bind** writes the connector's file through the connector API (validated, written atomically, audited, the old text
   kept in its history, the same as Admin → Connectors): the kind added, `root` and `domain` set, the promoted columns as the
   kind's layout. **Tell the server** announces each business date (Data loads, expectations, alerts and notices).
4. **Build.** The build runs as its own process on the console's machine, the same `tools/lake/build.py` with the same lock, run
   log and summary; the page shows reading and writing progress, then the rows per date and classifier, the rejects by reason,
   the connector bound, each date announced, and **Open deal 100001**, a link to one record of the load.

The process never holds a token: binding and announcing are done by the console as the administrator, after the build. The
recent runs of every root are listed at the bottom. Settings: `lakes.*` in the console's `application.yaml`
([CONFIGURATION.md](../admin/CONFIGURATION.md)).

### 10.3 Admin → Connectors (`/admin/connectors`)

Every connector, where it is defined, its state and who uses it; create, edit (form or YAML), test, switch off, reset to the pack's
template, restore an earlier version. The terminal twin is `drishti.py connector`. Guide: [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).

### 10.4 Admin → Artifacts (`/admin/artifacts`)

Install and uninstall packs, connectors and BI files safely: what depends on what, the impact before a change, a cascade that takes
the dependants along only when confirmed by name, and the uninstalled files kept. Guide: [OPERATIONALISING.md section 18](OPERATIONALISING.md#18-admin--artifacts-install-and-uninstall-safely).

### 10.5 Admin → BI (`/admin/bi`) and the migration

BI settings and governance; after an upgrade it lists the items the expression and model migration could not convert, with why
(the terminal twin: `drishti.py bi migrate --expressions --dry-run`). Guide: [BI_GOVERNANCE.md](BI_GOVERNANCE.md).

### 10.6 Admin → Health (`/admin/health`) and Admin → Packs (`/admin/packs`)

Health is the server's and the console's state on one page (connectors not configured, late data, retries, temporary folders,
the backend watch, **Pages**: per kind of any pack the views and p50/p95/p99 over the last ten minutes, slowest first, and an
**Activity** chart of requests a minute and the average response time over the last 30 minutes); the terminal twins are `drishti.py server health`, `server smoke` and `doctor`. Packs loads, unloads, switches
on and off and deploys a pack archive with its connectors (the twins: `drishti.py server packs`, `pack deploy`).

## 11. Scheduling reference

| Tool | Exit codes | Lock | Log | Re-run safe |
|---|---|---|---|---|
| `drishti.py lake build` | 0 ok, 2 rejects or signal failed, 1 failed, 3 locked | `<lake>/_drishti-lake/lock` (released when the process ends) | one line per run to stdout and `<lake>/_drishti-lake/runs.log`; JSON summary per run | yes with `overwrite-dates` / `overwrite-partitions` |
| `drishti.py lake maintain` | 0 ok, 1 a table failed | none: schedule it after the builds, on one machine | one JSON line per table and step | yes |
| `drishti.py data ingest` (`--watch --once`) | 0 ok, 1 a file failed or had bad lines | a state file per lake or files root | stdout | yes with `overwrite-dates` |
| `drishti.py data landed` | 0 ok, 1 not verified or refused | none needed (the same pack, kind, date and batch twice is recorded once) | stdout or `--json` | yes |
| `drishti.py server smoke` / `server health` | 0 ok, 1 a check failed | none | stdout or `--json` | yes |
| `drishti.py pack check` | 0 passed, 1 failed, 2 usage | none | stdout, JUnit with `--junit` | yes |
| `tools/load-*.sh` | 0 ok, non-zero failed | none: one loader per store at a time | stdout | yes (each day replaced in one step) |
| `tools/drill.sh` | 0 all passed and pushed | none: one drill at a time | `data/drill/*.log` | yes |

Rules that hold for all of them: run as a service user that owns the lake or data folder; pass the server explicitly
(`--server`, `--signal`); keep tokens in a file readable only by that user (`--token-file`), never on the command line; send
standard output to a log file or the journal; alert on a non-zero exit, and on `2` read the summary before re-running.

## 12. Sizing a large load (300,000 documents and more)

Measured on the synthetic book (14 KB a deal as pretty-printed JSON, 8.7 KB compact), one laptop, local SSD, `/usr/bin/time -v`
around the whole command (peak memory is the largest process: the builder, or the Java writer it starts):

| Documents | Input | Engine | Wall time | Peak memory | Lake |
|---|---|---|---|---|---|
| 2,000 | 28.5 MB JSON array | deltalake | 0.6 s | 257 MB | 2.6 MB a day |
| 2,000 | 28.5 MB JSON array | Drishti's writer | 1.7 s | 260 MB | 2.6 MB a day |
| 10,000 | 140 MB JSON array | deltalake | 1.3 s | 626 MB | 13 MB a day |
| 10,000 | 140 MB JSON array | Drishti's writer (`auto`, the server's exec jar) | 2.9 s (0.8 s reading, 2.0 s writing, about 1 s of it the JVM starting) | 591 MB (the builder itself: 114 MB) | 13 MB a day |

What grows with the input, and what does not:

* **Memory does not grow with the file.** Reading holds one document at a time (the builder stayed at 114 MB for 140 MB of input);
  writing holds one batch: `--batch-mb` (128 MB of compact documents, 15,368 of these deals) or `--batch-rows` (50,000), whichever
  is smaller, plus its Parquet encoding: about 600 MB for the default batch. That is the ceiling for any number of documents;
  `--batch-mb 32` brings it to about 250 MB on a small machine, at the cost of more, smaller files.
* **Disk.** The staged rows take the compact size of the documents (about 60 % of a pretty-printed export) on the lake's disk while
  the run lasts; the lake itself about 10 % of the pretty-printed input (Snappy Parquet). For 300,000 such deals: a 4.2 GB export,
  2.6 GB staged during the run, about 400 MB of lake per business date.
* **Time** grows linearly with the documents (reading about 180 MB a second of pretty-printed JSON here; writing about 5,000
  deals a second): about 40 s with deltalake and a minute with Drishti's writer for 300,000 deals, a few minutes for a million.
  The per-run summary's `timings` shows where the time went; `batchRows` and `averageDocumentBytes` what the batches were.
* **Partitions.** Each business date times each classifier value is a folder with at least one file; keep classifiers to a few
  dozen values (`lake scan` shows the counts) and compact with `lake maintain`.
* **The console's page** streams the upload to its temporary folder (`DRISHTI_CONSOLE_TMP_DIR`: room for the file) and runs the
  same builder; `lakes.jobs` (2) bounds the builds at once, so plan memory for two batches.
* **Millions of documents** (2.9.6): the parallel lake loader (section 4.13, `--engine java`, picked by `auto` from 1 GB of JSON Lines)
  parses and writes on every core with memory set by `memory.budget-mb`; 10,000 positions of 510 scenarios (54 MB of JSON) load in about
  0.2 s warm on 24 cores, and 2.3 million are estimated at a few minutes on a 16 to 32 vCPU server (an estimate, with its basis and the
  spill-disk and heap sizing: [PARALLEL_LOADER.md section 11](../architecture/PARALLEL_LOADER.md#11-measured-and-the-23m-estimate)).

**Also in `tools/`:** `lake/s3sim.py` starts the S3 simulators (moto, MinIO) for the S3 tests (section 4.12).
