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
# The lake configuration: one place that says what the lake is

*Product owner, 2026-11-03: "it should not be in connector config: theoretically we can have a Delta lake anywhere. We should have one
global and separate config for the lake. Everyone else, including the Delta connector, should refer to it." Status: **built**
(section 9); the questions of section 8 decided as recommended by the product owner (2026-11-03).*

## 1. Where the lake is described today, and why that is wrong

| Today | Holds |
|---|---|
| each Delta/Iceberg **connector file** (`config/connectors/*.yaml`, a pack's connector template in `pack.yaml`) | the lake's `root`, `domain`, `table.<kind>`, storage options, and the table layout: `layout.<kind>.columns` (promoted), `sort-by`, `file-rows`, `row-group-rows`, vectors |
| `lake build` and the Java loader's load file | `--partition-by`, `--promote`, the target path, the layout again |
| `tools/build_demo_lakes.py` | `--partition-by PRODUCT_TYPE` hard-coded for trades |
| the data inbox | no layout: a table it creates is partitioned by business date only |
| the in-memory lake (`config/engines/memory-lake.yaml`) | its stores (`mem://…`), their caps |
| `drishti-stack/stack.yaml` | which synthetic lakes a stack builds |

The same table is described in up to four places that can disagree, and a reader (the connector) owns what only a writer should decide.

## 2. The rule

**A lake is configuration of its own.** One file per lake describes where it is and every table in it. **Writers** (lake build, the Java
loader, the data inbox, the demo lakes, the official-record publishers) create and write tables exactly as it says; **readers** (the
Delta and Iceberg connectors, BI, the pivot engine, Arrow Flight) find tables through it. Nobody else holds a root, a path, a partition
or a promoted column.

## 3. The file

`config/lakes/<lake>.yaml`, one per lake; a server may know several (a shared lake, a Murex lake, an in-memory store):

```yaml
lake: main                                  # its name; connectors and loaders refer to it
format: delta                               # delta | iceberg; a table may say otherwise
root: s3://${DRISHTI_LAKE_BUCKET}/delta     # or a local folder, or mem://feeds for the in-memory lake
storage:                                    # how to reach it; secrets only as ${ENV} or the platform's role, never in the file
  region: ${AWS_REGION:us-east-1}
catalog: none                               # iceberg: none (path tables) | rest | glue | s3tables, with its settings
defaults:                                   # every table unless it says otherwise
  sort-by: id
  file-rows: 250000
  row-group-rows: 1000
tables:
  trading.trade:                            # <domain>.<kind>
    path: trading/trade_endur               # default <domain>/<kind>
    partition-by: [PRODUCT_TYPE]            # after business_date, which is always first; at most four
    promote: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, COUNTERPARTY, PORTFOLIO, QUANTITY]
  risk.pnl-vector:
    path: risk/pnl-vector
    promote: [VALUE_TYPE, GRAIN, TRADE_ID, POSITION_ID, CURRENCY, SOURCE_SYSTEM, DESK, TT_BOOK]
    vectors: [PNL]
```

**Every table is partitioned by `business_date` first, always** (product owner, 2026-11-03: "business date will be for everyone, that is
given"): it is not a setting and cannot be turned off; `partition-by` names only what comes after it.

**Everything specific to a table lives here** (product owner, 2026-11-03), nowhere else. Beside the keys above, a table's entry may hold:

| Key | Means |
|---|---|
| `id`, `date` | the id field and the business-date field of the kind's documents (today a load's `--key` and `--date`) |
| `latest-by` | which of two rows of one id on one date wins (today the loader's `latest-by`) |
| `load-mode` | **`replace`** (the default): a load replaces each business date it holds, in one commit per date, so repeating a load leaves the same data; **`append`**: a load adds its rows to the dates it holds (product owner, 2026-11-03: "replace by default, but it can be append if so configured in the lake config"). Append keeps the same safety: each load carries a batch id recorded in the table's commit, and a batch already committed is never appended twice (a repeated file, a crash and retry); two rows of one id on one date are resolved by `latest-by` when read |
| `compression`, `vector-compression`, `file-size-mb`, `row-group-mb` | file layout |
| `retention` | how many business dates or years are kept; older dates removed by the maintenance job |
| `maintenance` | compaction (small files of a date merged), vacuum or expire-snapshots age, checkpoint interval, statistics columns |
| `time-travel` | how long old versions stay readable (*known at*) |
| `properties` | Delta table properties or Iceberg table properties passed through as they are |
| `memory` | for a `mem://` lake: the cap, the contract, pinning (today in the in-memory lake's engine file) |

**Where a table's first entry comes from.** A pack ships its recommended entries for its kinds (a `lake:` block in `pack.yaml`, replacing
the layout in its connector templates). When a pack is installed into a lake, or a writer first meets a kind the lake file lacks, the
pack's entry is copied into the lake file and said so; from then on the lake file is the truth, and an administrator edits it there.

## 4. Who reads it

| Consumer | Uses |
|---|---|
| Delta and Iceberg connectors | `lake: main` and their kinds; root, paths, format, storage, promoted columns and vectors come from the lake file. A connector file keeps only reading concerns (caches, refresh, which kinds, the source name) |
| `lake build`, the Java loader (`LoadConfig`) | `--lake main` (or `target.lake`); partitioning, promoted columns, vectors and file layout from the lake file. A `--partition-by` on the command line must agree with it, or the run is refused with both named |
| The data inbox | the routing rule names a lake and a kind; a new table is created exactly as the lake file says |
| **The demo lakes**, the stack | **the demo lake is a lake like any other** (product owner, 2026-11-03: "even the demo lake should refer to it"): `config/lakes/demo.yaml` describes it, and `drishti.py lake demo`, `build_demo_lakes.py` and the stack's synthetic lakes write through it; the hard-coded `PRODUCT_TYPE` and every other layout in those tools go |
| The in-memory lake | a lake whose root is `mem://<store>`, its cap and contract in the same file |
| Admin | **Admin → Lakes** (exists for memory stores) shows every lake and table from these files, read-only first |

## 5. Existing installations

Connector files that carry `root`, `domain`, `table.<kind>` or `layout.<kind>.*` are migrated by a tool (`drishti.py lake migrate-config`): it writes the lake file from them and rewrites each connector to `lake: <name>`. Until the tool has run, a connector with the
old keys still starts, with one warning naming the tool; the old keys are removed one release later. Nothing in `data/` changes: tables
keep their files and partitions, and the lake file is checked against each table's Delta log or Iceberg metadata at start (a partitioning
that differs from the table is reported, never rewritten).

## 6. Checks

At start and in `drishti.py lake check`: every connector names a known lake; every table in a lake file matches its table on disk
(format, partition columns, promoted columns present); every kind a pack serves from a lake has an entry. A writer refuses to create a
table that has no entry.

## 7. Size

M: the file and its reader (one small plain-Java module or package shared by readers and writers), the connectors and the loaders moved
onto it, the migration tool, the checks, docs (a lake guide; CONNECTOR docs and DATA_GUIDE updated; CONFIGURATION.md).

## 8. Decisions (product owner, 2026-11-03: every recommendation accepted)

| # | Question | Decided |
|---|---|---|
| L1 | One file per lake under `config/lakes/` | **Yes** |
| L2 | A pack ships recommended entries; the lake file is the truth after install | **Yes** |
| L3 | The in-memory lake's stores become lakes in the same form | **Yes**, so one description covers every lake |
| L4 | Old connector keys: migrated by a tool, read with a warning for one release, then removed | **Yes** (the one allowed configuration alias, with its removal planned) |
| L5 | Where it ships | **2.9.11.b**, or a release of its own after 2.9.11.b if .b is full (it touches every lake connector, so it gets its own drill attention) |

## 9. As built

| Part | Where | Decided while building |
|---|---|---|
| The reader | module `drishti-lakeconfig` (`Lakes`, `Lake`, `LakeTable`, `LakeCheck`, `LakeFiles`, `ConnectorLake`, `PackEntry`, `LakesCli`) | A module of its own, with Jackson as its only dependency: its users sit on both sides of the plugin boundary (the source registry, the loader run alone as a command, the data inbox, the in-memory lake, the server) and drishti-api must stay dependency-free while drishti-common brings Spring. `PackLayout` moved here as `PackEntry` (moved, not copied) |
| Python | `tools/lake/lakeconfig.py` runs `LakesCli` (`dump`, `table`, `check`, `migrate-config`, `add-entry`) and reads its JSON | One reader: the Java one. The one exception is `tools/lake/memory_caps.py`, which sizes the JVM before any JVM runs and reads two keys (`root`, `memory.max-mb`) |
| Several tables of one kind | an entry is `<domain>.<table>` with `kind:` (`trading.trade_endur`, `kind: trade`); connectors name entries with `lake-tables:`; the loader picks with `target.table` | The demo lake has three trade tables (ENDUR, CALYPSO, MUREX); a kind with several tables is never guessed |
| Readers | `SourceRegistry.ownSettings` expands `lake:` into the settings the plugins already read (`root`, `domain`, `table.<kind>`, `layout.<kind>.columns`, `layout.<kind>.vectors`, `s3.<key>`, `catalog`, `uri`, `warehouse`) | One point every connector's settings pass, whatever file or template they came from; the plugins keep one way of reading. A connector that names a lake and sets one of those keys is refused |
| Writers | `LoadConfig` (`target.lake` a name), `LoaderProcess` (the inbox), `lake build --lake NAME [--lake-root DIR]`, `lake demo --lake-name demo`, `build_demo_lakes.py` | `--lake-root` lets a scratch or test lake take a lake's layout; a `target.lake` that is a folder is the older form (one warning) |
| Append | `TableTarget.appended`, `DeltaTarget` (`withTransactionId(drishti-batch:<batch>:<date>, 1)`), `IcebergTarget` (`drishti.batch` in the snapshot summary) | The batch is per date, so a crash after some dates' commits retries only the rest |
| Memory stores | `MemoryLakeSettings.withLakeStores` | The engine file keeps the switch, the replenish jobs and the compaction policy: the engine's work, not the lake's description |
| Admin | `GET /api/v1/admin/lakes/files`, Admin → Lakes "The lakes" | Read-only first, as decided |
| Packs | every shipped pack's `lake:` block; its Delta templates `lake: demo` + `lake-tables`; `tools/packlake.py` (`lake_form` for the generators, `view` for the tools that read a template's layout); `PackEntry` | One transformation each way, so the generators and every reader of a template agree; the official-results readers stay in the older form (OfficialReaders points them at the publisher's store) |
| Shipped copy | `drishti-lakeconfig/src/main/resources/drishti/lakes/demo.yaml`, checked equal by `settings_catalogue.py` | A server in another working folder (a container, a stack node) knows the demo lake |
| References | nested `${A:${B:x}/delta}`; resolved with the server's settings (`Lakes.useProperties`, installed by `PackEnvironmentPostProcessor`), else a dotted name as a system property or its environment form | The demo root follows the data folder and a `--setting` or test property, as a connector file's references did |
| Python append | refused for a lake-file append table (`--engine java --batch-id`); `--mode append` into a folder unchanged | The batch rule exists once, in the loader |
| Stack | synthetic lakes laid out by the demo lake file, each node's root its synthetic folder | No second description of the same tables |
