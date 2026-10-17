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

All the data on this page is invented. The commodity-swap book used throughout comes from the synthetic generator
`tools/packgen/comswap_synth.py` (deals with legs, cash flows, resets and events, upper-case keys, blanks spelt four ways: the
*shape* of a booking-system export, none of its values).

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
| See what an export holds and which fields make a good id, date and classifier | the same page, step 2 | `drishti.py lake scan`, `drishti.py data profile` |
| Make a pack from schema files or JSON | Build → **New pack** (`/build/pack/new`) | `drishti.py pack make`, `drishti.py pack new` |
| Check a pack in CI | | `drishti.py pack check --junit` |
| Ship a pack to a server | Admin → Packs → Deploy | `drishti.py pack bundle`, `pack deploy`, `server packs deploy` |
| Tell the server that a batch landed | the Lakes page does it after a build | `drishti.py data landed`, or `lake build --signal` |
| Change where a connector reads | Admin → **Connectors** | `drishti.py connector apply` |
| Install or uninstall a pack, connector or BI file safely | Admin → **Artifacts** | `drishti.py server packs`, `connector delete` |
| Keep a lake small and fast | | `drishti.py lake maintain` (on a timer) |
| Load the demo data into Postgres, DuckDB, MongoDB, Redis, Aerospike, Iceberg, files or Delta | | `tools/load-*.sh`, `drishti.py data load` |
| Check a machine before a demo | Admin → **Health** | `drishti.py doctor`, `drishti.py server smoke` |

Use the page for a one-off file, a first look at new data, or when you want to see each choice before it is made. Use the command
line for anything that repeats, runs at night, or lives in a pipeline: it has exit codes, a lock, a log line and a JSON summary,
and it is the same engine the page runs.

## 2. Running the tools

Every tool is Python 3.10+ or bash, run from the repository root. Most need only PyYAML; the ones that write Delta or Parquet need
`deltalake` and `pyarrow`. With [uv](https://docs.astral.sh/uv/) nothing has to be installed:

```bash
uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py lake build ...
```

The Java tools (`sutra`, `pack check`, and Drishti's own lake writer) run from the server's exec jar, found as `--jar`, then
`DRISHTI_JAR`, then the newest `drishti-server/target/drishti-server-*-exec.jar` (build it with `./mvnw -q -DskipTests package`).

Exit codes follow one rule everywhere: `0` done, `1` problems found or refused, `2` usage (a bad option, a missing file). The lake
builder adds `2` for "written with rejects" and `3` for "another run holds the lock" (section 4.6). Commands that talk to a
server take `--server` (default `DRISHTI_SERVER`, then `http://localhost:18480`) and read the token from `--token-file` or
`DRISHTI_TOKEN`; the token is never printed. Shell completion: `source <(python3 tools/drishti.py completion bash)`.

## 3. `drishti.py`: the one command line

`tools/drishti.py` is one program with twelve groups. `python3 tools/drishti.py <group> --help` lists a group's commands and
`<group> <command> --help` every option with examples.

| Group | Commands | Section |
|---|---|---|
| `sutra` | `sutra shape`, `sutra design`, `sutra lint`, `sutra test`, `sutra preview`, `sutra gen` | [5.1](#51-sutra) |
| `pack` | `pack make`, `pack new`, `pack regenerate`, `pack diff`, `pack catalogue`, `pack i18n`, `pack check`, `pack about-check`, `pack bundle`, `pack verify`, `pack deploy`, `pack rollback`, `pack publish`, `pack keygen`, `pack install` | [5.2](#52-pack) |
| `data` | `data profile`, `data ingest`, `data load`, `data landed`, `data loads` | [5.3](#53-data) |
| `lake` | `lake build`, `lake scan`, `lake status`, `lake maintain` | [4](#4-lake-build-a-large-json-export-into-a-delta-lake) |
| `server` | `server health`, `server packs`, `server smoke` | [5.4](#54-server-and-doctor) |
| `connector` | `connector list`, `connector get`, `connector apply`, `connector delete`, `connector test`, `connector plugins`, `connector enable`, `connector disable`, `connector reset`, `connector history` | [5.5](#55-connector) |
| `design` | `design list`, `design create`, `design get`, `design save`, `design check`, `design propose`, `design approve`, `design reject`, `design proposals`, `design export`, `design import`, `design bind`, `design autodesign`, `design delete` | [5.6](#56-design) |
| `docs` | `docs shots` | [9](#9-developer-tools) |
| `doctor` | `drishti.py doctor` | [5.4](#54-server-and-doctor) |
| `bi` | `bi workspace`, `bi catalogue`, `bi expr`, `bi migrate`, `bi sql`, `bi model`, `bi mq`, `bi item`, `bi dataset` | [5.7](#57-bi) |
| `view` | `view get`, `view explain` | [5.8](#58-view-and-completion) |
| `completion` | `drishti.py completion bash` (also `zsh`, `powershell`) | [5.8](#58-view-and-completion) |

The group modules live beside it and are not run on their own: `tools/authoring.py` (`data profile`, `pack regenerate|diff|catalogue|i18n`),
`tools/dataprofile.py`, `tools/packmake.py`, `tools/packregen.py`, `tools/packdiff.py`, `tools/packcatalogue.py`, `tools/packi18n.py`,
`tools/packform.py` (the `form:` section a pack check reads), `tools/schemapack.py` (`--schema`), `tools/sutragen.py` (`sutra gen`),
`tools/ingest_jsonl.py` and `tools/ingest_watch.py` (`data ingest`), `tools/packbundle.py` (`pack bundle|verify|deploy|rollback`),
`tools/serverpacks.py`, `tools/serverconnectors.py`, `tools/smoke.py`, `tools/doctor.py`, `tools/viewcmd.py`, `tools/completion.py`,
`tools/bilang.py`, `tools/bimodel.py`, `tools/biitem.py`, and `tools/lake/cli.py` with `tools/lake/build.py`, `tools/lake/scan.py`,
`tools/lake/jsonstream.py`, `tools/lake/stage.py` and `tools/lake/writers.py`.

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
bounded by rows (`--batch-rows`, 50,000) and bytes (`--batch-mb`, 128). Section 12 has the numbers.

### 4.1 Look first: lake scan

`lake scan` reads the export as a stream and lists every field (dotted paths to depth 3; arrays are counted, not entered), how
often it is present, its types and most frequent values, then suggests an id, a business date and classifiers:

```
$ python3 tools/drishti.py lake scan book-2026-10-16.json --limit 500 | tail -n 8
PRECIOUS_METAL_FLAG                    500         2  text  (455), Y(45)
TT_GROUP                               500         1  text  (500)
TT_BOOK                                500         1  text  (500)
LEASE_PERPETUITY_FLAG                  500         1  text  (500)
SETTLEMENT_DATE                        500         1  text  (500)
LEGS                                   500         0  array
EVENTS                                 131         0  array
suggested --key TRADE_ID   --date START_DATE   --partition-by: PRODUCT_TYPE, PRODUCT_SUB_TYPE, PORTFOLIO
```

`(455)` is a blank value counted 455 times. The id is a field present in (nearly) every document with every value different;
the business date ranks names such as *business*, *cob*, *as of* and *snapshot* first; the classifiers are text fields rarely
blank with 2 to 40 values, *product* and *type* first. This export is a snapshot of the book (its dates are trade and start
dates, not the day of the extract), so the walk-through below uses `--as-of` for the day the file was taken.

### 4.2 The first load

```
$ uv run --with deltalake --with pyarrow --with pyyaml python tools/drishti.py lake build --from book-2026-10-16.json \
    --kind deal --key TRADE_ID --as-of 2026-10-16 --partition-by PRODUCT_TYPE --promote COUNTERPARTY,PORTFOLIO,QUANTITY \
    --lake lake --domain comswap
engine: deltalake (the Python deltalake package; no server jar with the lake writer was found)
business date  PRODUCT_TYPE           rows
2026-10-16     CallNot                  21
2026-10-16     Cash                    337
2026-10-16     ComFut                  402
2026-10-16     ComSwap                 892
2026-10-16     Commodity               348
documents read: 2000, rows written: 2000, rejected: 0
summary: lake/_drishti-lake/runs/20261017T082600-509848.json
2026-10-17T08:26:01-04:00 lake-build ok exit=0 run=20261017T082600-509848 domain=comswap kind=deal engine=deltalake mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=5 seconds=0.467
```

The last line is **the** log line of the run (also appended to `<lake>/_drishti-lake/runs.log`): one line per run, whatever
happened, for a scheduler's log and for `grep`. `--dry-run` reads and counts and writes nothing; `-q` prints only the log line;
`--json` prints the whole summary.

### 4.3 The next day, with rejects

The next extract arrives gzipped, as JSON Lines, with two bad lines at the end:

```
$ ... lake build --from book-2026-10-19.jsonl.gz --kind deal --key TRADE_ID --as-of 2026-10-19 --partition-by PRODUCT_TYPE \
    --promote COUNTERPARTY,PORTFOLIO,QUANTITY --lake lake --domain comswap
engine: deltalake (the Python deltalake package; no server jar with the lake writer was found)
business date  PRODUCT_TYPE           rows
2026-10-19     CallNot                  16
2026-10-19     Cash                    257
2026-10-19     ComFut                  321
2026-10-19     ComSwap                 672
2026-10-19     Commodity               234
documents read: 1502, rows written: 1500, rejected: 2
  rejected 1: no id (field 'TRADE_ID' is missing or empty)
  rejected 1: not JSON (Expecting value)
    e.g. book-2026-10-19.jsonl.gz:1501: no id (field 'TRADE_ID' is missing or empty)
    e.g. book-2026-10-19.jsonl.gz:1502: not JSON (Expecting value)
summary: lake/_drishti-lake/runs/20261017T082601-509920.json
2026-10-17T08:26:01-04:00 lake-build partial exit=2 run=20261017T082601-509920 domain=comswap kind=deal engine=deltalake mode=overwrite-dates rows=1500 rejected=2 dates=1 partitions=5 seconds=0.323
```

A reject never stops the run: it is counted per reason, the first 50 are kept with where they were (`file:line` for JSON Lines,
`file[n]` for an element of an array), and the run exits `2` so a scheduler can tell "loaded, look at the rejects" from "all
good". The reasons: not JSON, not a JSON object, no id, an id that is an object, no business date, a date that is not a date
(ISO dates and date-times, `yyyymmdd` as text or a number are dates). An id seen twice on one date is reported as a duplicate.

### 4.4 Re-running a day replaces that day only

```
$ ... lake build --from book-2026-10-16.json ... --lake lake --domain comswap -q
2026-10-17T08:26:02-04:00 lake-build ok exit=0 run=20261017T082601-510010 domain=comswap kind=deal engine=deltalake mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=5 seconds=0.4
$ find lake -maxdepth 4 -type d | sort
lake
lake/comswap
lake/comswap/deal
lake/comswap/deal/business_date=2026-10-16
lake/comswap/deal/business_date=2026-10-16/PRODUCT_TYPE=CallNot
lake/comswap/deal/business_date=2026-10-16/PRODUCT_TYPE=Cash
lake/comswap/deal/business_date=2026-10-16/PRODUCT_TYPE=ComFut
lake/comswap/deal/business_date=2026-10-16/PRODUCT_TYPE=Commodity
lake/comswap/deal/business_date=2026-10-16/PRODUCT_TYPE=ComSwap
lake/comswap/deal/business_date=2026-10-19
lake/comswap/deal/business_date=2026-10-19/PRODUCT_TYPE=CallNot
lake/comswap/deal/business_date=2026-10-19/PRODUCT_TYPE=Cash
lake/comswap/deal/business_date=2026-10-19/PRODUCT_TYPE=ComFut
lake/comswap/deal/business_date=2026-10-19/PRODUCT_TYPE=Commodity
lake/comswap/deal/business_date=2026-10-19/PRODUCT_TYPE=ComSwap
lake/comswap/deal/_delta_log
lake/_drishti-lake
lake/_drishti-lake/runs
```

| `--mode` | What a run does to the dates in its input | Re-run safe |
|---|---|---|
| `overwrite-dates` (default) | replaces every partition of each business date present; every other date is kept | yes |
| `overwrite-partitions` | replaces only the (date, classifier values) present: a file per product type per day replaces only its own product | yes |
| `append` | adds the rows | no: a re-run adds them again |

A table keeps the partitioning it was created with; a run that asks for another fails before reading anything:

```
$ ... lake build --from book-2026-10-16.json --kind deal --key TRADE_ID --as-of 2026-10-16 --partition-by PORTFOLIO --lake lake --domain comswap
lake build: lake/comswap/deal is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)
summary: lake/_drishti-lake/runs/20261017T082602-510178.json
2026-10-17T08:26:02-04:00 lake-build failed exit=1 run=20261017T082602-510178 domain=comswap kind=deal mode=overwrite-dates seconds=0.001 error="lake/comswap/deal is partitioned by business_date, PRODUCT_TYPE; this run asks for business_date, PORTFOLIO (give --partition-by PRODUCT_TYPE or write a new table)"
```

### 4.5 The two engines

`--engine auto` (the default) uses **Drishti's own Java writer** when it finds the server's exec jar with the writer in it, and the
Python `deltalake` package otherwise; the first line of the output says which ran and why. `--engine drishti` or
`--engine deltalake` insists on one.

| | Drishti's writer (`drishti`) | `deltalake` (delta-rs) |
|---|---|---|
| Runs as | `java -jar drishti-server-<v>-exec.jar lake write --manifest M` (started by the builder) | the builder's own Python |
| Needs | Java 21 and the server jar | `pip install deltalake pyarrow` (or `uv run --with ...`) |
| Commits | **one** per table per run: the replaced files are removed and the new ones added in the same version, so a reader sees all of the load or none of it | one per batch; the first batch of a replaced date replaces it |
| Where | local disk (Windows paths too, no `winutils.exe`) | local disk and object storage |
| Heap / memory | `DRISHTI_LAKE_HEAP` (default `1g`) | the batch (`--batch-mb`) plus Arrow's copy |

The same synthetic day through Drishti's writer (the jar's writer taken from a development class path, `DRISHTI_LAKE_CLASSPATH`,
as the tests do), then read back with deltalake: the two engines write the same table.

```
$ python3 tools/drishti.py lake build --from book-2026-10-16.json --kind deal --key TRADE_ID --as-of 2026-10-16 \
    --partition-by PRODUCT_TYPE --promote COUNTERPARTY,PORTFOLIO,QUANTITY --lake lake2 --domain comswap --engine drishti
engine: drishti (Drishti's Java writer in DRISHTI_LAKE_CLASSPATH)
business date  PRODUCT_TYPE           rows
2026-10-16     CallNot                  21
2026-10-16     Cash                    337
2026-10-16     ComFut                  402
2026-10-16     ComSwap                 892
2026-10-16     Commodity               348
documents read: 2000, rows written: 2000, rejected: 0
summary: lake2/_drishti-lake/runs/20261017T082247-499153.json
2026-10-17T08:22:49-04:00 lake-build ok exit=0 run=20261017T082247-499153 domain=comswap kind=deal engine=drishti mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=5 seconds=1.597
$ ls lake2/comswap/deal/_delta_log
00000000000000000000.crc
00000000000000000000.json
$ uv run --with deltalake --with pyarrow python -c "from deltalake import DeltaTable; t = DeltaTable('lake2/comswap/deal'); \
    print('version', t.version(), 'partitions', t.metadata().partition_columns, 'rows', t.to_pyarrow_table().num_rows); print(t.schema().to_arrow())"
version 0 partitions ['business_date', 'PRODUCT_TYPE'] rows 2000
arro3.core.Schema
------------
id: Utf8
doc: Utf8
business_date: Date32
PRODUCT_TYPE: Utf8
COUNTERPARTY: Utf8
PORTFOLIO: Utf8
QUANTITY: Float64
```

### 4.6 Exit codes, the lock, the log and the summary

| Exit | Meaning | What a scheduler should do |
|---|---|---|
| `0` | written, nothing rejected (and, with `--signal`, the server was told) | nothing |
| `2` | written, but some documents were rejected, or the data-load signal failed | alert someone to read the summary's `rejects` (or re-send the signal: `drishti.py data landed`) |
| `1` | failed: nothing was committed for the table (a bad option, an unreadable file, another partitioning, the engine failed) | alert; fix and re-run (safe) |
| `3` | another run holds the lake's lock | try again later; never kill the other run |

**The lock.** One run per lake at a time: the builder takes an exclusive lock on `<lake>/_drishti-lake/lock`. The operating system
releases it when the process ends, so a run that crashed or was killed never blocks the next one. The file names the holder:

```
lake build: the lake is locked by {"pid": 510142, "host": "oryxpro", "since": "2026-10-17T08:26:02-04:00"}
2026-10-17T08:26:02-04:00 lake-build locked exit=3 run=20261017T082602-510155 domain=comswap kind=deal mode=overwrite-dates seconds=0.0 error="the lake is locked by {\"pid\": 510142, \"host\": \"oryxpro\", \"since\": \"2026-10-17T08:26:02-04:00\"}"
```

**The log and the summary.** Every run appends its log line to `<lake>/_drishti-lake/runs.log` and writes its summary to
`<lake>/_drishti-lake/runs/<run>.json` and `<lake>/_drishti-lake/last-run.json` (and `--summary FILE`). Staged rows go to
`<lake>/_drishti-lake/staging/<run>/` while the run lasts and are removed after it (`--keep-staging` keeps them); a crashed run's are
removed by the next run, which holds the lock. A summary (the run of 4.3, two partitions shown):

```json
{
 "run": "20261017T082601-509920",
 "status": "partial",
 "mode": "overwrite-dates",
 "domain": "comswap",
 "kind": "deal",
 "key": "TRADE_ID",
 "partitionBy": ["PRODUCT_TYPE"],
 "promote": ["COUNTERPARTY", "PORTFOLIO", "QUANTITY"],
 "asOf": "2026-10-19",
 "table": "lake/comswap/deal",
 "rows": {"2026-10-19": 1500},
 "partitions": [
  {"date": "2026-10-19", "values": {"PRODUCT_TYPE": "CallNot"}, "rows": 16},
  {"date": "2026-10-19", "values": {"PRODUCT_TYPE": "Cash"}, "rows": 257}
 ],
 "documents": 1502, "accepted": 1500, "rejected": 2,
 "rejects": {
  "byReason": {"no id (field 'TRADE_ID' is missing or empty)": 1, "not JSON (Expecting value)": 1},
  "first": [{"where": "book-2026-10-19.jsonl.gz:1501", "reason": "no id (field 'TRADE_ID' is missing or empty)"},
            {"where": "book-2026-10-19.jsonl.gz:1502", "reason": "not JSON (Expecting value)"}]
 },
 "duplicates": {},
 "files": [{"file": "book-2026-10-19.jsonl.gz", "bytes": 805886}],
 "sampleId": "100001", "sampleDate": "2026-10-19",
 "timings": {"readSeconds": 0.18, "writeSeconds": 0.116, "totalSeconds": 0.323},
 "engine": "deltalake",
 "commit": {"commits": 5, "version": 9},
 "columns": {"partition": ["PRODUCT_TYPE"], "promoted": {"COUNTERPARTY": "string", "PORTFOLIO": "string", "QUANTITY": "double"}},
 "exitCode": 2,
 "peakMemoryMb": 211.5
}
```

`lake status` reads them back, and says whether a run holds the lock now:

```
$ python3 tools/drishti.py lake status --lake lake
lock: free
runs (3 logged, last 3):
  2026-10-17T08:26:01-04:00 lake-build ok exit=0 run=20261017T082600-509848 domain=comswap kind=deal engine=deltalake mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=5 seconds=0.467
  2026-10-17T08:26:01-04:00 lake-build partial exit=2 run=20261017T082601-509920 domain=comswap kind=deal engine=deltalake mode=overwrite-dates rows=1500 rejected=2 dates=1 partitions=5 seconds=0.323
  2026-10-17T08:26:02-04:00 lake-build ok exit=0 run=20261017T082601-510010 domain=comswap kind=deal engine=deltalake mode=overwrite-dates rows=2000 rejected=0 dates=1 partitions=5 seconds=0.4
last run: ok (exit 0), summary lake/_drishti-lake/last-run.json
```

### 4.7 A pack, a connector and the data-load signal

With `--pack DIR` the builder takes from the pack's `pack.yaml` what it declares: the domain and the promoted columns of its Delta
connector (`settings.domain`, `settings.layout.<kind>.columns`), and the key and date fields of its `ingest:` block (written by
`pack make` and `pack new`). Options on the command line win.

```yaml
# config/packs/synth-book/pack.yaml (the parts lake build reads)
pack: synth-book
connectors:
  book-store:
    plugin: delta
    kinds: [deal]
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: comswap
      layout:
        deal:
          columns: [COUNTERPARTY, PORTFOLIO, QUANTITY]
ingest:
  deal: {key: TRADE_ID, date: TRADE_DATE}
```

```bash
python3 tools/drishti.py lake build --from drop/book-2026-10-16.json --pack config/packs/synth-book --lake /srv/drishti/delta \
    --as-of 2026-10-16 --partition-by PRODUCT_TYPE --signal http://drishti.internal:18480 --token-file /etc/drishti/loads.token
```

The connector reads the lake once its file says so (Admin → Connectors, or `drishti.py connector apply book-store.yaml`):

```yaml
# config/connectors/book-store.yaml
plugin: delta
kinds: [deal]
settings:
  root: /srv/drishti/delta
  domain: comswap
  layout:
    deal:
      columns: [COUNTERPARTY, PORTFOLIO, QUANTITY]
```

`--signal URL` then tells **that** server (it is never a default) that each business date landed: `POST /api/v1/packs/{pack}/loads`
with the rows, the rejects and the run as the batch id, so the server refreshes, verifies, checks its expectations and alerts, and
tells people ([DATA_LOADS.md](DATA_LOADS.md)). The pack's name comes from `--pack`, or `--pack-name` without a pack folder. The
token needs the `loads:write` scope; it is read from `--token-file` or `DRISHTI_TOKEN` and never printed or written to the summary.
A signal that fails leaves the data written and makes the run exit `2`.

### 4.8 The same in the browser

Admin → Data loads → **Load a file into a lake** (section 10.2) runs this builder on the console's machine with the choices made on
the page, then binds the lake to the pack's connector and sends the signal as the administrator who started it.

### 4.9 Scheduling the lake build

A nightly load of the day's extract, at 21:30 on weekdays, after the export lands in `/srv/drop`:

```cron
# m  h  dom mon dow  command
30 21 *   *   1-5  cd /opt/drishti && python3 tools/drishti.py lake build --from /srv/drop/book-$(date +\%F).json.gz --pack config/packs/synth-book --lake /srv/drishti/delta --as-of $(date +\%F) --partition-by PRODUCT_TYPE --signal http://localhost:18480 --token-file /etc/drishti/loads.token -q >> /var/log/drishti/lake-build.log 2>&1
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
ExecStart=/bin/sh -c 'python3 tools/drishti.py lake build --from /srv/drop/book-$(date +%%F).json.gz --pack config/packs/synth-book --lake /srv/drishti/delta --as-of $(date +%%F) --partition-by PRODUCT_TYPE --signal http://localhost:18480 --token-file /etc/drishti/loads.token -q'
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
python3 tools/drishti.py lake status --lake /srv/drishti/delta
```

**Safe re-runs.** With `overwrite-dates` or `overwrite-partitions` a run can be repeated any number of times: the same input gives
the same table. Re-run a failed night by hand with the same command. A run that finds the lock held exits `3` at once; let the
scheduler try again (systemd: `Restart=on-failure` with `RestartSec=10min` on the service, or simply the next timer). A watch on
a drop folder is `drishti.py data ingest --watch` (section 5.3).

## 5. The other `drishti.py` groups

### 5.1 sutra

`sutra shape` infers the JSON Schema of samples; `sutra design` drafts a Sutra from samples (or `--schema`); `sutra lint` and
`sutra test` check a pack's Sutras (CI); `sutra preview` renders samples to HTML; `sutra gen` drafts one Sutra per group of JSON
Lines (for example one per `PRODUCT_TYPE`). The first five are the Java tool in the server jar; `sutra gen` is Python
(`tools/sutragen.py`) and lints its result with the Java tool unless `--no-lint`.

```bash
python3 tools/drishti.py sutra gen jsonl --key deal=TRADE_ID --match deal=PRODUCT_TYPE --out build/sutras
python3 tools/drishti.py sutra lint config/packs/synth-book --strict
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
python3 tools/drishti.py data profile jsonl/deal.jsonl            # first: what is there (5.3)
python3 tools/drishti.py pack make jsonl/deal.jsonl --kind deal --match PRODUCT_TYPE --key TRADE_ID --date TRADE_DATE --name synth-book --out build/synth-book
python3 tools/drishti.py pack check build/synth-book/pack --strict --junit build/reports
```

Real output: [CLI_GUIDE.md section 5](CLI_GUIDE.md); schema files to a pack: [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md).
**Scheduling**: `pack check` belongs in CI (exit `1` fails the build; `--junit DIR` for the report); `pack deploy` is idempotent
(the same bundle twice changes nothing) and keeps the replaced version for `pack rollback`.

### 5.3 data

`data profile` lists every field of JSON Lines with coverage, distinct values and examples, and prints the `pack make` line that
fits (a profile of 300 synthetic deals, first and last lines):

```
$ python3 tools/drishti.py data profile jsonl/deal.jsonl | head -n 8
data profile: 1 file(s), 300 document(s), 1 kind(s)

== deal: 300 document(s), 107 field(s)
  field                                           type            cover  distinct  examples
  SOURCE_SYSTEM                                   string           100%         1  SYNTH-ETRM
  SOURCE_UPDATE_TIME                              string           100%         1
  TRADE_DATE                                      string           100%       249  2025-07-06, 2026-06-22, 2025-05-19
  INPUT_DATE                                      string           100%         1
$ python3 tools/drishti.py data profile jsonl/deal.jsonl | tail -n 6
    COUNTERPARTY                 0.913  coverage 100%, 24 values over 300 documents (average 12.5 per value, evenness 0.98; largest group CPTY-EMBER = 23)
    TRADER_LAST_NAME             0.895  coverage 100%, 13 values over 300 documents (average 23.1 per value, evenness 0.87; largest group  = 108)
    PORTFOLIO                    0.842  coverage 100%, 7 values over 300 documents (average 42.9 per value, evenness 0.97; largest group PF-ENERGY-01 = 60)
    PRODUCT_TYPE                 0.648  coverage 100%, 5 values over 300 documents (average 60.0 per value, evenness 0.83; largest group ComSwap = 129); name suggests a category
  suggested:
    python3 tools/drishti.py pack make jsonl/deal.jsonl --kind deal --match PRODUCT_SUB_TYPE --key TRADE_ID --date TRADE_DATE --name deal-pack
```

`data ingest` writes JSON Lines into a Delta lake (the pack's layout, `business_date` partitions only) or the File connector's
folders (`--store files`); `--watch DIR` turns a drop folder into a feed. Use `lake build` instead for JSON arrays, gzip, large
files, classifier partitions or a lock and a summary; use `data ingest --store files` for the File connector. A dry run on the
synthetic JSON Lines, by start date:

```
$ python3 tools/drishti.py data ingest --from jsonl --domain comswap --key TRADE_ID --date START_DATE --store files --root ingest-files --dry-run | tail -n 5
deal                2026-10-01             17
deal                2026-11-01             10
deal                2026-12-01              5
deal                2027-01-01              1
total rows: 300
```

`data load --store delta|files|postgres|duckdb|mongodb|redis|aerospike|iceberg` runs the store's demo loader (section 6);
`data landed` announces a batch to a server and `data loads` lists what landed and what is due ([DATA_LOADS.md](DATA_LOADS.md)).
**Scheduling** `data ingest --watch DIR --once` from cron is a drop folder polled every few minutes (exit `1` when a file failed);
`data landed` is the last step of any ETL job that loads a store itself.

### 5.4 server and doctor

`server health` (exit `1` unless OK), `server packs list|load|unload|on|off|deploy|history|rollback|datasource` (Admin → Packs from
the terminal) and `server smoke` (opens sample views of every kind on two dates; exit `1` on any failure, the check after a deploy).
`doctor` checks this machine without changing anything (paths shortened):

```
$ python3 tools/drishti.py doctor
GREEN  java                    /usr/lib/jvm/java-21-openjdk-amd64/bin/java: Java 21.0 (Ubuntu)
RED    server jar              no drishti-server-*-exec.jar under drishti-server/target: build it (./mvnw -q -DskipTests package) or pass --jar / DRISHTI_JAR
                               fix: ./mvnw -q -DskipTests package   (or set DRISHTI_JAR)
GREEN  python                  /usr/bin/python3: Python 3.14.4
GREEN  python pyyaml           installed
YELLOW python deltalake        not installed (needed for data ingest --store delta)
                               fix: pip install deltalake   or run through uv: uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...
YELLOW python pyarrow          not installed (needed for data ingest --store delta)
                               fix: pip install pyarrow   or run through uv: uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py ...
GREEN  repo config/packs/      <repo>/config/packs
YELLOW console venv            <repo>/drishti-console/.venv
                               fix: cd drishti-console && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
GREEN  configured directories  none of DRISHTI_DELTA_ROOT, DRISHTI_FILES_ROOT, DRISHTI_PACKS_INSTALLED is set
GREEN  port 18480              in use by Drishti: status UP
GREEN  port 17480              in use by Drishti: answers 404 on /actuator/health, as the Drishti console does
doctor: 1 red, 3 yellow
```

**Scheduling** `server smoke --json` every few minutes is a cheap synthetic monitor (exit `1` pages someone); `server health`
in a load balancer's script. Real output: [CLI_GUIDE.md section 7](CLI_GUIDE.md). In the console: Admin → **Health**.

A scratch server on port 18956 with only the `finance` pack loaded and empty data folders (paths shortened; exit `1` because two
of its sources have no data yet), then the smoke test that passes anyway, and a load announced by hand:

```
$ python3 tools/drishti.py server health --server http://localhost:18956
status DEGRADED  version 2.2.0  java 21.0.12.1  uptime 17s
packs 1  sources 6 (degraded 0, down 2)  packs with problems 1  failed to start 0
  file                        DOWN      DOWN: no directory <data>/feeds
  finance-lake                DOWN      DOWN: cannot reach <data>/delta/finance (engine: native)
  book-totals                 UP        UP (nothing computed yet)
  demo                        UP        UP
  news                        UP        UP
  yfinance                    UP        UP
$ python3 tools/drishti.py server packs list --server http://localhost:18956 | head -n 4
pack                  version  loaded  on   added  kinds
finance               1.0.0    yes     yes         14
banking-core          1.0.0    no      no          0
climate-risk          1.0.0    no      no          0
$ python3 tools/drishti.py server smoke --server http://localhost:18956 --ids 1 --dates 1 | tail -n 5
fx-spot               EURUSD                    2026-10-16  2       0      9.1      OK
book                  COMM-NY-1                 2026-10-16  3       1      7.1      OK
book-pnl              COMM-NY-1                 2026-10-16  2       0      19.0     OK

13 views, p50 8.9 ms, max 57.1 ms
smoke: passed
$ python3 tools/drishti.py data landed --server http://localhost:18956 --pack finance --kind book --date 2026-10-16 --rows 12 --batch demo-1 --source lake-build
book for 2026-10-16 loaded: 12 rows; 0 alerts fired
  load mvcepvo0-1  status ready  verified verified  attempt 1
  record  ok      recorded
  refresh ok      caches dropped: demo, file, finance-lake
  verify  ok      3 book entities on 2026-10-16
  alerts  ok      0 alerts fired
  smoke   skipped not asked for (loads.smoke is 0)
  notices ok      1 person told
$ python3 tools/drishti.py data loads --server http://localhost:18956 --pack finance
received             kind              date        status        rows    rej  verified  alerts told  batch / source
2026-10-17 13:02:58  book              2026-10-16  ready           12         verified       0    1  demo-1 lake-build
```

### 5.5 connector

Admin → Connectors from the terminal: `connector list`, `connector get NAME [--yaml]`, `connector apply FILE` (create or change; the
version is read first and sent back as `If-Match`, so a concurrent edit is refused, not overwritten: GitOps-safe),
`connector delete`, `connector test NAME | --file F` (a throwaway instance tries the settings; exit `1` when it does not work),
`connector plugins`, `connector enable` / `connector disable`, `connector reset` (the pack's template back), `connector history`.

```bash
python3 tools/drishti.py connector apply config/connectors/book-store.yaml --server http://localhost:18480 --token-file ~/.drishti-token
python3 tools/drishti.py connector test book-store
```

Every change is audited and the old text kept ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)). The 10,000-deal lake of
section 12, bound to a connector on the scratch server and tried: the server reads every row `lake build` wrote.

```
$ cat book-store.yaml
plugin: delta
description: the synthetic commodity-swap book (lake build)
kinds: [deal]
settings:
  root: <scratch>/l10b
  domain: comswap
  layout:
    deal:
      columns: [COUNTERPARTY, PORTFOLIO, QUANTITY]
$ python3 tools/drishti.py connector apply book-store.yaml --server http://localhost:18956
applied book-store: running; changed (new connector), root, domain, layout.deal.columns; version ffc4169939987981
  note kinds: kind 'deal' is not owned by a loaded pack (loaded: agreement, book, book-pnl, clearing-account, contract-spec, counterparty, credit-limit, csa, and 6 more)
$ python3 tools/drishti.py connector test book-store --server http://localhost:18956
book-store (delta): reachable, health UP (engine: native), 22 ms
  note: kinds: kind 'deal' is not owned by a loaded pack (loaded: agreement, book, book-pnl, clearing-account, contract-spec, counterparty, credit-limit, csa, and 6 more)
  kind  business date  rows   note
  ----  -------------  -----  ----
  deal  2026-10-16     10000
$ python3 tools/drishti.py connector list --server http://localhost:18956
3 connector(s) in <scratch>/connectors   (watching: WATCHING)
  name          origin  plugin   kinds     state           used by  problems
  ------------  ------  -------  --------  --------------  -------  --------
  book-store    file    delta    deal      running
  book-totals   file    derived  book-pnl  running         finance
  finance-lake  file    delta              down, retrying  finance
```

The note goes once a pack that owns `deal` is loaded and routes it to `book-store`. More output:
[CLI_GUIDE.md section 7](CLI_GUIDE.md).

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
`bi mq` (a model query), `bi item new|save|tests` and `bi dataset refresh`. A write needs the `bi:write` scope.

```bash
python3 tools/drishti.py bi expr validate "sum(QUANTITY) / count()" --model synth-book
python3 tools/drishti.py bi migrate --expressions bi/ --check          # CI: exit 1 while a file has the old syntax
```

Real output: [CLI_GUIDE.md section 7](CLI_GUIDE.md) and [EXPRESSIONS.md](EXPRESSIONS.md). In the console the migration's state is
on Admin → **BI** (items that could not be converted, with why).

### 5.8 view and completion

`view get KIND/ID` prints a rendered view as text tables, as the token's user sees it (masks apply); `view explain KIND/ID` the
About answer. `completion bash|zsh|powershell` prints a completion script generated from the command tree:

```
$ python3 tools/drishti.py completion bash | head -n 3
# bash completion for drishti.py: generated by `drishti.py completion bash`
#   source <(drishti.py completion bash)         or save it as ~/.local/share/bash-completion/completions/drishti.py
_drishti_complete() {
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
| `tools/load-iceberg.sh` | Apache Iceberg tables (a folder or a REST catalog), sorted by id, external sort with `--spill-dir` | `--keep-days N` |

```bash
tools/load-duckdb.sh /tmp/demo.duckdb --trades 10000 --days 3
python3 tools/drishti.py data load --store files --trades 10000 --days 3 -- /tmp/demo-files
```

**Scheduling.** The loaders guard dates (a row dated after tomorrow is not loaded and the run fails naming it), replace each day
they reach in one step, and refuse to drop more than half a table (`--max-drop-share`, `--force-drop`), so a nightly cron entry
that re-runs is safe; the details are at the top of each script and in [DEMO_DATA.md](../connectors/DEMO_DATA.md). The connectors.
guides are listed in [CONNECTORS.md](../connectors/CONNECTORS.md).

## 7. Lake maintenance: `tools/lake/maintain.py`

`drishti.py lake maintain` (the same as `tools/lake/maintain.py`) keeps a lake bounded: **retention** (business dates older than
`keep-business-days`, never more than `max-drop-share` of a table without `--force-drop`), **compaction** of each day's small
files (or a re-layout into the pack's declared layout), a **checkpoint**, and **vacuum** of unreferenced files. Local lakes and S3.
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

```
$ uv run --with deltalake --with pyarrow --with pyyaml python tools/drishti.py lake maintain --config maint.yaml --once --dry-run
{"at": "2026-10-17T08:26:03-04:00", "event": "maintained", "dry_run": true, "table": "lake/comswap/deal", "before": {"files": 10, "mb": 4.44}, "retention": {"cutoff": "2024-10-21", "would_remove_files": 0}, "vacuum": {"files": 0, "dry_run": true}, "after": {"files": 10, "mb": 4.44}}
```

One JSON line per table and step, for logs and monitoring; one failing table is reported and the rest go on (exit `1`). The shipped
configuration is `deploy/lake-maintenance.yaml`.

**Scheduling.** Either the job's own daemon (`--daemon`: every day at `schedule.at` in `schedule.zone`, for a container) or a
timer that runs `--once` after the night's loads. It must not run while a `lake build` writes the same lake: schedule it after the
builds (02:30 here, after a 21:30 build), and on one machine only.

```cron
30 2 * * 2-6  cd /opt/drishti && uv run --with deltalake --with pyarrow --with pyyaml python tools/drishti.py lake maintain --config deploy/lake-maintenance.yaml --once >> /var/log/drishti/lake-maintain.log 2>&1
```

```ini
# /etc/systemd/system/drishti-lake-maintain.service   (+ a .timer with OnCalendar=Tue..Sat 02:30, Persistent=true)
[Service]
Type=oneshot
User=drishti
WorkingDirectory=/opt/drishti
ExecStart=/usr/bin/python3 tools/drishti.py lake maintain --config deploy/lake-maintenance.yaml --once
```

## 8. Generators: `tools/packgen`, `tools/samplegen`, `tools/realdata`

| Tool | What it makes |
|---|---|
| `tools/packgen/comswap_synth.py` | **The synthetic Endur-like commodity-swap book**: deals with 1 to 4 legs, monthly cash flows, resets, events, packages and novations; upper-case keys in a fixed order, digit-string codes that must stay text, blanks spelt as `""`, `null`, `0` and a missing key. `--mix` makes a whole book (ComSwap, ComFut, Cash, Commodity and a rare CallNot by `PRODUCT_TYPE`). Deterministic (`--seed`); at most 10,000 deals. Every value is invented. |
| `tools/packgen/note_synth.py` | a synthetic structured note of another shape (camelCase keys; note, coupon periods, observations, fixings; a call schedule and a basket of underlyings), to show the pack generator is generic. Every value is invented. |
| `tools/packgen/pack_from_jsonl.py` | the engine of `drishti.py pack new`: a complete pack from a folder of JSON Lines |
| `tools/packgen/banking/make_data.py`, `make_packs.py`, `make_sutras.py`, `make_docs.py` | the banking packs: their sample data and lake, manifests, Sutras and guides, from one taxonomy |
| `tools/packgen/climate/make.py`, `liquidity/make.py`, `oprisk/make.py`, `retail/make.py` | the climate-risk, liquidity-risk, operational-risk and retail-banking packs, built on the banking data |
| `tools/packgen/genomics/make.py` | the genomics pack (public reference facts, synthetic samples) |
| `tools/packgen/economics/make.py`, `politics/make.py` with `tools/realdata/realdata.py` | the economics and politics packs from real public data (World Bank, IMF; internal demos only) |
| `tools/samplegen/bulk_trades.py` | a large trade book for scale tests, N trades over D business days, as a Delta lake (`load-delta.sh --trades`) |

The synthetic book used on this page:

```
$ python3 tools/packgen/comswap_synth.py --deals 2000 --mix --out book-2026-10-16.json
wrote 2000 synthetic deals to book-2026-10-16.json
$ python3 tools/packgen/comswap_synth.py --deals 1500 --seed 8 --mix --jsonl --out book-2026-10-19.jsonl
wrote 1500 synthetic deals to book-2026-10-19.jsonl
```

2,000 deals are 28.5 MB of pretty-printed JSON (about 14 KB a deal); the lake of section 4 (two days, 3,500 deals) holds them in 4.4 MB of Parquet.

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
| `tools/bench/scale.sh`, `tools/bench/pinning.sh` | the scale benchmark per store and size, and the virtual-thread pinning comparison (Java 21 / 25); they start and stop their own servers by process id |
| `tools/windows/start-server.ps1`, `tools/windows/start-console.ps1`, `tools/windows/load-delta.ps1` | starting the server and console, and loading the demo lake, on Windows |
| `tools/smoke.py` | the engine of `drishti.py server smoke` |

```
$ python3 tools/license_headers.py
$ echo $?
0
```

## 10. The tools in the console

### 10.1 Build → New pack (`/build/pack/new`)

Bring schema files and JSON or JSON Lines data; the page profiles every file in the browser (arrays streamed), proposes kinds, ids,
business dates and one Sutra per type, previews them, and builds a pack bundle to download or deploy. The command-line twin is
`drishti.py pack make`. Guide: [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md).

### 10.2 Admin → Data loads → Lakes (`/admin/lakes`)

Data loads (`/admin/loads`) shows what each pack expects and what landed; its **Load a file into a lake** button opens the Lakes
page, the browser form of `lake build`. Administrators only.

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
the dependants along only when confirmed by name, and the uninstalled files kept. Guide: [ARTIFACTS.md](../admin/ARTIFACTS.md).

### 10.5 Admin → BI (`/admin/bi`) and the migration

BI settings and governance; after an upgrade it lists the items the expression and model migration could not convert, with why
(the terminal twin: `drishti.py bi migrate --expressions --dry-run`). Guide: [BI_GOVERNANCE.md](BI_GOVERNANCE.md).

### 10.6 Admin → Health (`/admin/health`) and Admin → Packs (`/admin/packs`)

Health is the server's and the console's state on one page (connectors not configured, late data, retries, temporary folders,
the backend watch); the terminal twins are `drishti.py server health`, `server smoke` and `doctor`. Packs loads, unloads, switches
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
