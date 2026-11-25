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
# Tutorial 23 · The risk lake and the official results on Iceberg

> Commands: Linux and macOS `python3 tools/drishti.py X`; Windows `py -3.13 tools\drishti.py X` (same arguments).

Delta Lake is Drishti's default lake format; Apache Iceberg is a full alternative, chosen by configuration. In this
part you move the market-risk pack's VaR inputs from Delta to Iceberg, publish the official end-of-day VaR results to Iceberg,
check that VaR is the same to the cent, see each table's format in Admin → Lakes, point BI's reader of the official results at the
new format, and go back to Delta. Each step shows the UI, the command line and the YAML behind it; every output was captured on the
committed test data.

## Time to complete

About 20 minutes.

## Before you start

- [The VaR tutorial, Part A](../guides/VAR_GUIDE.md#part-a--historical-var-from-the-bank-to-a-trade) step 1 done once: you know the VaR test data and `VAR BANK` (112,041,646 USD on 2026-09-30).
- `build_demo_lakes.py` writes Iceberg with the loader in the server's jar, so the server is built first.
- The Python you run Drishti with (`python3`; Windows `py -3.13`), its libraries installed with the two pip commands (`setup` checks them): it has the Drishti Lake wheel (the loader's Python path) and `pyarrow`.
- Stop the server while you build the lake; the examples use the default data folder, `data/` (`data/delta/demo`, `data/iceberg`).

## What you will learn

- how `build_demo_lakes.py --store risk=iceberg` moves every table of the risk domain into Iceberg and switches `risk-store.yaml`;
- how to publish the official results to Iceberg (`drishti.var.official.format` and `.root`), and why a table keeps its format;
- that VaR, ES and the limit letters read from Iceberg equal Delta's;
- each table's **Format** in Admin → Lakes;
- how BI's `var-results` connector follows the official format, and how to put back a file `lake demo` wrote;
- how to go back to Delta.

## Step 1 · Build the VaR data into Iceberg

**From the command line.** The same `--only` list as the VaR tutorial, Part A, with `--store risk=iceberg`:

```bash
python3 tools/build_demo_lakes.py --as-of data --store risk=iceberg \
  --only market-risk/pnl-vector,market-risk/scenario-pv,market-risk/fx-scenario,market-risk/scenario-set,market-risk/var-limit
```

```text
1/3 the demo lakes: only market-risk/pnl-vector,…,market-risk/var-limit, every business day, ending data → data/delta/demo
  …
  risk/pnl-vector into Iceberg: data/delta/demo/risk/pnl-vector -> data/iceberg/risk/pnl-vector
    iceberg: 8,233 rows read in 1 s; 1 business days of 1 tables to write
  risk/pnl-vector: 8,233 rows in Iceberg, newest business date 2026-09-30 (data/iceberg/risk/pnl-vector)
  …
  risk/var-limit: 129 rows in Iceberg, newest business date 2026-09-30 (data/iceberg/risk/var-limit)
3/3 done in 14 s.
  Risk domain: iceberg data/iceberg/risk; risk-store: now reads iceberg, was delta (data/connectors/risk-store.yaml; …);
  exposure-store: now reads iceberg, was delta (…)
```

The tables are built as Delta first (`lake demo`), then each is copied into Iceberg by Drishti's own loader and the Delta copy removed:
one store per domain. Your own `--lake` folder: the Iceberg root is the folder beside it (`<lake>/../iceberg`; `DRISHTI_ICEBERG_ROOT`
moves it).

**The YAML.** The script writes the connector file `risk-store.yaml` from the market-risk pack's template, switched to Iceberg (the
text it replaced is kept in `data/connectors/history/`), and does the same for the counterparty-risk pack's `exposure-store.yaml`. The
file names no kinds: the pack's template still binds the risk kinds to `risk-store` by default, and `config/bindings.yaml` (Admin → Data
bindings) is where a site binds them elsewhere:

```yaml
# Written by tools/build_demo_lakes.py --store risk=iceberg: every risk table in Iceberg (data/iceberg/risk), …
plugin: iceberg
settings:
  root: ${DRISHTI_ICEBERG_ROOT:${lake.data.dir:${drishti.data.dir}}/iceberg}
  domain: risk
  layout:
    pnl-vector:
      columns: [VALUE_TYPE, GRAIN, TRADE_ID, …, TT_BOOK, LIMIT_LETTER, …]
      vectors: [PNL]
    …
```

**In the UI.** Nothing to do yet; step 4 shows the tables, and [the VaR tutorial, step 26](../guides/VAR_GUIDE.md#step-26--the-same-var-on-iceberg) reads VaR from them, the same to the cent.

**You should see** `data/iceberg/risk/<kind>/metadata` and `data` folders for the seven VaR kinds, `data/delta/demo/risk` empty, and
`plugin: iceberg` at the top of `data/connectors/risk-store.yaml`.

These are standard Apache Iceberg v2 tables: Spark, Trino, Snowflake or pyiceberg read them as they are. To try it with pyiceberg,
see [the data lake guide, section 15](../guides/DATA_LAKE_GUIDE.md#15-iceberg-spec-compliance).

**Further:** [ICEBERG_CONNECTOR.md](../../plugins/drishti-plugin-iceberg/README.md) (the connector, sections 18 and 19),
[../guides/DATA_GUIDE.md section 11](../guides/DATA_GUIDE.md#112-mixed-delta-and-iceberg-storage) (mixed storage). What Drishti reads and writes of the Iceberg spec:
[DATA_LAKE_GUIDE.md section 15](../guides/DATA_LAKE_GUIDE.md#15-iceberg-spec-compliance).

## Step 2 · Publish the official results to Iceberg

The server captures every node of every hierarchy officially when a date loads, and appends the figures to
`<root>/risk-results/var-official` for other systems ([VAR_GUIDE.md section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)).
Delta is the default; Iceberg is one setting. **A table keeps the format it was created in**, so give the Iceberg table a root of its
own; the Iceberg root of step 1 is the natural one.

**The YAML** (`application.local.yaml` in the server's working folder; [CONFIGURATION.md](../admin/CONFIGURATION.md)):

```yaml
drishti:
  var:
    official:
      format: iceberg                  # delta (the default) or iceberg; DRISHTI_VAR_OFFICIAL_FORMAT
      root: ./data/iceberg             # the table: data/iceberg/risk-results/var-official
  ccr:
    exposure:
      official:
        format: iceberg                # the same for the official exposure results (DRISHTI_EXPOSURE_OFFICIAL_FORMAT)
        root: ./data/iceberg
```

**From the command line**, the same by environment for the format (the root has no variable of its own):
`DRISHTI_VAR_OFFICIAL_FORMAT=iceberg`.

**In the UI.** None: settings are read at start.

**You should see**, if you change the format but not the root of a lake whose Delta table already exists, the capture still made and
the publish refused in words, in `drishti.py var official` and the log:

```text
"publishError": "iceberg table data/delta/risk-results/var-official: the table data/delta/risk-results/var-official is a Delta table
and the official format is iceberg: an official table keeps its format; give the new format another root (drishti.*.official.root)"
```

With the root of the YAML above it publishes (step 3). A date already captured is not published again by a restart; the new format
applies from the next capture.

**Further:** [VAR_GUIDE.md section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results),
[EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) (section 20), on S3: [CLUSTER_GUIDE.md › Drishti on AWS: ECS, EFS and S3, section 22](../guides/CLUSTER_GUIDE.md#1212-iceberg-on-aws-and-official-results-in-s3).

## Step 3 · Start the server and look at the connectors

Start the server and the console as in tutorial 1, Part A, with the samples off (`DRISHTI_DEMO_ENABLED=false`). For Admin → Lakes to list the
Iceberg tables too, give the console both roots: `DRISHTI_LAKE_ROOTS=data/delta/demo,data/iceberg`.

**In the UI.** **Admin → Connectors**, filter `market-risk`: `risk-store` on **iceberg**, `var-results` on **iceberg** (after step 5).

![Admin → Connectors filtered on market-risk: risk-store and var-results on iceberg](img/t29/02-connectors.jpg)

**From the command line:**

```text
$ drishti.py connector list
  name                origin  plugin         kinds                                                        state     used by
  exposure-store      file    iceberg        simulation-run,exposure-path                                 running   counterparty-risk
  risk-store          file    iceberg        stress-scenario,stress-result,frtb-sensitivity +9            running   market-risk
  trade-murex         file    delta          trade                                                        running   trading
  var-results         file    delta          var-official                                                 running   market-risk
  …
$ drishti.py var official
  … "nodes": 131 …, "published": true …
```

**The YAML** is step 1's and step 2's.

**You should see** `risk-store` on `iceberg`, the official capture of 2026-09-30 with 131 nodes **published**, and the table
`data/iceberg/risk-results/var-official` with its JSON Lines beside it (`data/iceberg/risk-results/jsonl/var-official/2026-09-30/var-official-v1.jsonl`).
`var-results` still says `delta`: step 5 is about it.

**Further:** [../guides/DATA_GUIDE.md section 9](../guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors) (Admin → Connectors).

## Step 4 · Each table's format in Admin → Lakes

**In the UI.** **Admin → Lakes**: the tables of both roots with a **Format** column.

![Admin → Lakes: MUREX's trades in Delta, the risk tables and the official results in Iceberg](img/t29/03-lakes.jpg)

**From the command line:** the page prints its twin under the table (*The same from a terminal or cron*: `drishti.py lake maintain
--config data/lakes/lake-maintenance.yaml --once`); add `--dry-run` to see what a run would change, changing nothing.

**The YAML** (`data/lakes/lake-maintenance.yaml`, what the page writes): a lake `format: auto` (the default) maintains each table in
its own format, Iceberg's by snapshot expiry and orphan-file removal under the 7-year floor of `risk` and `risk-results`
([OPERATIONS_GUIDE.md › Lake maintenance: Admin → Lakes → Maintain and `lake maintain`, section 8](../guides/OPERATIONS_GUIDE.md#163-iceberg-lakes)).

**You should see** `trading/trade_murex` as **Delta**, the seven risk tables and `risk-results/var-official` as **Iceberg**. The risk
tables are maintained here like the ones `drishti.py lake build --format iceberg` writes: the copy leaves the same build record
(`<iceberg root>/_drishti-lake/tables-built.json`, with the Delta table's pack and kind).

**Further:** lake maintenance and its schedule are in [OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md#16-lake-maintenance-and-scheduling).

## Step 5 · BI's reader of the official results follows the format

The market-risk pack's `var-results` connector (BI's `VOFF`) is `plugin: delta` in the pack; with `format: iceberg` the server reads
that template as `plugin: iceberg` over `${drishti.var.official.root}`. The server reads the template where it is and writes no file:
nothing to do. A site override of `var-results` (`config/connectors/var-results.yaml`) changes only the keys it names; if it names
`plugin`, that value wins: **Reset** in Admin → Connectors removes the override and the template is in force again.

**In the UI.** **Admin → Connectors → var-results** shows the layer of each setting (template or override) and `plugin: iceberg`.

![var-results: plugin iceberg](img/t29/04-var-results.jpg)

**From the command line:**

```text
$ drishti.py connector get var-results
connector var-results   origin file   plugin iceberg   running   …
  root                         ${drishti.var.official.root:${DRISHTI_DELTA_ROOT:${lake.data.dir:${drishti.data.dir}}/delta}}
  domain                       risk-results
  engine                       native
```

**The YAML** the server reads (the pack's template seen through the official format; `OfficialReaders`):

```yaml
plugin: iceberg
settings:
  root: ${drishti.var.official.root:${DRISHTI_DELTA_ROOT:${lake.data.dir:${drishti.data.dir}}/delta}}
  domain: risk-results
```

**You should see** `var-results` on `iceberg` and running, and the official records read back: `VOFF HIERARCHY == 'LL'` on
2026-09-30 finds 43 of the 131 rows.

**Further:** [../guides/DATA_GUIDE.md section 9, "Connector layers"](../guides/DATA_GUIDE.md#connector-layers) (templates, overrides,
files an earlier release left).

## Step 6 · Back to Delta

**From the command line** (server stopped):

```text
$ python3 tools/build_demo_lakes.py --as-of data --store risk=delta --only market-risk/pnl-vector,…,market-risk/var-limit
  the risk domain is in Delta now: its Iceberg tables are removed (data/iceberg/risk)
  Risk domain: delta data/delta/risk; risk-store: now reads delta, was iceberg (the pack's own connector; …); exposure-store: now reads delta, was iceberg (…)
```

**The YAML.** `risk-store.yaml` and `exposure-store.yaml` are removed (their text kept in the history), so the packs' own Delta
connectors serve again; remove the `official` block of step 2 to publish to Delta again (the Iceberg official table stays where it is:
a table keeps its format). A full build without `--store risk=…` also puts the risk domain back in Delta.

**In the UI.** Admin → Connectors: `risk-store` on `delta`.

**You should see** `data/iceberg/risk` gone, `data/iceberg/risk-results` kept, and `VAR BANK` still 112,041,646.

## Next steps

- [DATA_LAKE_GUIDE.md](../guides/DATA_LAKE_GUIDE.md): every key, a Delta lake in S3, an Iceberg lake over REST, an in-memory store.
- Your own lake on Iceberg, Databricks or a REST catalog: [Using Drishti with Databricks Delta Lake or Apache Iceberg](../guides/DATA_GUIDE.md#3-using-drishti-with-databricks-delta-lake-or-apache-iceberg).
- On AWS (S3, Glue, S3 Tables, the scripts in `deploy/aws/iceberg/`): [CLUSTER_GUIDE.md › Drishti on AWS: ECS, EFS and S3, section 22](../guides/CLUSTER_GUIDE.md#1212-iceberg-on-aws-and-official-results-in-s3).
- Exposure on the same lake: [the exposure tutorial](../guides/EXPOSURE_GUIDE.md) after a full `--store risk=iceberg` build.

## If something goes wrong

| You see | Do |
|---|---|
| *this Python lacks the Drishti Lake wheel* | run the script with the console's Python (Before you start) |
| *could not find the server jar* or the copy into Iceberg fails | build the server first (`./mvnw clean package -DskipTests`) |
| `VAR BANK` answers *no P&L vectors in any connector* | the server reads another Iceberg root: set `DRISHTI_ICEBERG_ROOT` to the folder beside your `--lake` |
| `publishError` *… is a Delta table and the official format is iceberg* | give the official results another root (step 30) |
| `var-results` stays `delta` | a site override names `plugin`: Admin → Connectors → var-results → **Reset** (step 33) |
| Admin → Lakes lists no Iceberg table | the console's `DRISHTI_LAKE_ROOTS` lacks `data/iceberg` (step 31) |
