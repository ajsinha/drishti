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
# Composed and derived datasets (2.1)

> **Archived: replaced by models with several fact tables ([DATASETS.md section 3.1](../guides/DATASETS.md#31-several-unrelated-fact-tables-in-one-query)) and Python datasets ([PYTHON_BI.md section 13](../guides/PYTHON_BI.md#13-transforms-a-table-in-a-table-out)).** Composed and derived datasets were a 2.1 feature, removed in 2.2: a `from:` or `derived:` dataset now parses with `DRS-9190` and `drishti.py bi migrate --models` converts it. This is history: sections 11.1 to 11.4 of DATASETS.md and the matching TROUBLESHOOTING entries as they were. What the samples became is [DATASETS.md section 11.5](../guides/DATASETS.md#115-the-samples-as-converted-in-22).

## The feature

In 2.1 a dataset did not have to be read from a connector. It could be **built from other datasets**, asked a question each *as the person reading*, and joined: a **composed** dataset joined them in YAML (11.1), a **derived** dataset handed the answers to a Python Transform that returned the table (11.2). Either way the result was an ordinary dataset with one table and measures of its own. The design is [RUPAKA.md section 20.10](../architecture/RUPAKA.md); the sample, built both ways, was *Desk P&L vs counterparty exposure*; what it became is 11.5.

Why not one dataset with both facts? Because the facts live in different models with different rules: in `trading-risk` a desk-head's row rule is on trades, so exposure by counterparty (netting sets, which do not reach trades) is refused for them (3). A composed dataset keeps each source's own rules and joins what each lets the reader see.

### 11.1 Composed datasets: `from:` and `join:`

```yaml
rachana: 1
dataset: desk-pnl-vs-exposure
from:                                   # a question put to another dataset, as the reader, for each alias
  pnl:
    dataset: trading-risk               # a catalogue id, a file name or the dataset's name
    by: ["counterparties.id as counterparty", "counterparties.NAME as name"]     # grouping columns; "as" names the column of the joined table
    measures: ["P&L 1d as Desk P&L", Trades]                                     # measures of that dataset ("as" renames)
    key: [counterparty]                 # optional: the columns that identify one row (the rest are attributes of them)
    filters: [{ field: trades.TRADE_STATUS, op: in, values: [NEW, AMENDED, VALIDATED] }]                  # optional: conditions of a report filter's form
  exposure:
    dataset: counterparty-exposure
    by: ["counterparties.id as counterparty", "counterparties.SECTOR as sector"]
    measures: [Exposure (EE peak), Exposure (PFE peak)]
    key: [counterparty]
join:
  - { left: pnl, right: exposure, on: [counterparty], type: left }               # inner (default), left or full
measures:                               # the dataset's own measures, over the joined columns
  P&L per exposure: "[Desk P&L] / [Exposure (EE peak)]"
```

| Key | Meaning |
|---|---|
| `from.<alias>.dataset` | the source: a catalogue id (`file:dataset:trading-risk`), a shipped file's name or the dataset's own name. It may itself be composed or derived |
| `by` | the columns the answer is grouped by (`table.column`, `table.column.part`), each optionally `... as name`; the name is the column of the joined table (default: the last part of the field) |
| `fields` | instead of `by` and `measures`: a list of columns, one row each (no grouping); used when a Transform or a join needs the rows themselves |
| `measures` | measures of the source, each optionally `... as name`; each becomes a column of the joined table and a measure of this dataset (`sum` of the column, so it adds up over the joined rows) |
| `key` | optional: the frame names of the columns that identify one row of the answer. The fan-out check (below) otherwise takes every grouping column together |
| `filters` | extra conditions on the source, `{ field, op, values }` as in a report filter |
| `join[].left`, `right` | aliases; the joins are written in order and each starts from a source already joined |
| `join[].on` | the columns to join on: `name` (the same on both sides) or `"left = right"` when the names differ. Join keys must be columns, not measures, and of one type |
| `join[].type` | `inner`, `left` (every row of the left source) or `full`. A key named the same on both sides appears once in the joined table (the coalesce of both) |
| `measures` (own) | expressions over the joined table's measures: `measure('Desk P&L') / measure('Exposure (EE peak)')` is the ratio of the two sums, at whatever grain the visual groups by |

**What the engine does.** For each query of the dataset it asks every source, as the reader, the question `by` + `measures` + `filters` states (with the query's business dates), stages the answers in the engine and joins them; the dataset's measures, a visual's fields, filters and sorts then run over the joined table, which is called `joined`. A source answer is cut at `drishti.bi.transform.compose-source-max-rows` (`DRS-9022`); the sources run one after another and each counts as a query for the reader's own limit. Real compiled output (the sample, by sector; each `"bi_frame_n_<alias>"` is a source's answer, staged):

```sql
SELECT "joined"."sector" AS "joined.sector", (sum("joined"."Desk_P_L")) AS "Desk P&L", (sum("joined"."Exposure_EE_peak")) AS "Exposure (EE peak)",
       ((CAST((sum("joined"."Desk_P_L")) AS DOUBLE) / NULLIF((sum("joined"."Exposure_EE_peak")), 0))) AS "P&L per exposure"
FROM (SELECT CAST(COALESCE("pnl"."counterparty", "exposure"."counterparty") AS VARCHAR) AS "counterparty", CAST("pnl"."name" AS VARCHAR) AS "name",
             CAST("pnl"."Desk P&L" AS DOUBLE) AS "Desk_P_L", CAST("pnl"."Trades" AS DOUBLE) AS "Trades", CAST("exposure"."sector" AS VARCHAR) AS "sector",
             CAST("exposure"."Exposure (EE peak)" AS DOUBLE) AS "Exposure_EE_peak", CAST("exposure"."Exposure (PFE peak)" AS DOUBLE) AS "Exposure_PFE_peak"
      FROM "bi_frame_3_pnl" AS "pnl" LEFT JOIN "bi_frame_4_exposure" AS "exposure" ON "pnl"."counterparty" = "exposure"."counterparty") AS "joined"
GROUP BY "joined"."sector" ORDER BY "joined.sector" ASC LIMIT 3
```

The joined table's columns have the names of the frame with anything that is not a letter, digit or underscore replaced (`Desk P&L` is the column `Desk_P_L`; the measure keeps its name). **Every source measure is a `sum` over the joined rows**: at the grain the sources were grouped to it is the source's number, at a coarser grain it adds the rows up. That is right for additive measures (a P&L, an exposure, a count) and wrong for a ratio or an average: leave those out and write the ratio as a measure of the composed dataset over the additive ones (`P&L per exposure` above), which is computed from the sums.

**Checks** (the dataset page, `POST /datasets/validate`, and publish). Real output of the check on mistakes:

| Mistake | Problem |
|---|---|
| a source dataset that does not exist | `error DRS-9014 from.other.dataset: no dataset 'no-such-dataset' (a catalogue id, a file name such as trades-basic, or the dataset's name)` |
| a measure the source does not have | `error DRS-9014 from.pnl.measures[0]: dataset 'trading-risk' has no measure 'P&L tomorrow' (measures: MTM, Notional, P&L 1d, ...)` |
| join keys of different types | `error DRS-9014 join[0].on: the key 'day' of 'pnl' is date but 'day' of 'exposure' is string: both sides of a join are of one type` |
| a source nobody joins, a join of a source twice, a join naming a column the frame lacks, a key that is a measure | `error DRS-9014` naming the path |
| a column of the same name in two sources that is not a join key | `error DRS-9014 from.exposure: the column 'day' is in 'pnl' and in 'exposure' and is not a join key: rename one with as` |
| a join that repeats rows | `error DRS-9015 join[0]: many-to-many: neither 'pnl' nor 'exposure' has one row per counterparty, so every row matches several and the measures of both repeat; ...` |
| a dataset that reads itself, through any depth | `error DRS-9016` with the loop, on every dataset in it |
| a source that has errors of its own | `error DRS-9014 ... source dataset 'x' has errors, so nothing can be built from it: <its first error>` |
| `tables:`, `relationships:` or `rollups:` beside `from:` | `error DRS-9010` (a composed dataset has one table, built at query time) |

**The fan-out check** (`DRS-9015`) matters because a join that matches several rows on one side repeats the other side's measures, and a sum over them overstates. A source with one row per join key (its grouping columns, or its `key`, are all in the join's `on`) is *unique*: unique on the right is the ordinary lookup, unique on both is one-to-one, and neither is refused. Unique on the left only (one-to-many) repeats the left's measures; unique on neither is many-to-many. **Both are errors**: Check and publish refuse the dataset, so a total that is several times too high cannot be published by accident. The fix is to aggregate the many side by the key (add the missing columns to the join, or group a source by the key alone), to swap the ends, or to declare the `key:` when the other grouping columns are attributes of it (the sample does: a counterparty's name and sector are attributes of its id).

When the repetition is what you mean, say so on the join and accept it:

```yaml
join:
  - { left: pnl, right: exposure, on: [sector], type: inner, allow-repeat: true }
```

`allow-repeat: true` means *you accept repeated measures*: Check then shows `DRS-9015` as a **warning** (the dataset saves and publishes), and the Model view marks the join `repeats rows (accepted)`. The rule is for YAML joins only; a Python Transform is not checked this way.

### 11.2 Derived datasets: a Python Transform

```yaml
rachana: 1
dataset: desk-pnl-vs-exposure-py
derived:
  transform: pnl-vs-exposure            # a Python item with interface: transform (catalogue id, file name or title)
  inputs:                               # the frames the Transform receives, in this order; each is a question as above
    pnl:      { dataset: trading-risk, by: ["counterparties.id as counterparty", "counterparties.NAME as name"], measures: ["P&L 1d as Desk P&L", Trades] }
    exposure: { dataset: counterparty-exposure, by: ["counterparties.id as counterparty", "counterparties.SECTOR as sector"], measures: [Exposure (EE peak), Exposure (PFE peak)] }
  params: { floor: 1.0 }                # overrides the item's own params
columns:                                # optional: what the Transform returns, typed (a contract); left out, the columns of the last good result are kept
  counterparty: string
  desk_pnl: decimal
  ee_peak: decimal
  pnl_per_exposure: decimal
measures:
  P&L per exposure: { expr: "ratio(sum(result.desk_pnl), sum(result.ee_peak))" }
```

The dataset has one table, `result`, whose rows are the frame the Transform returns. Its measures are YAML over it as in any dataset (`sum(result.desk_pnl)`); a column of the frame is a field (`result.counterparty`). The Python side is in [PYTHON_BI.md section 13](../guides/PYTHON_BI.md#13-transforms-a-table-in-a-table-out).

* **The inputs are masked frames.** Each input is asked as the reader (11.1) and handed to the Transform as a pandas DataFrame in the order written, so the Transform only ever sees what the reader may see, and a column masked for them arrives null. The columns of an input are named as in 11.1 (`as`, else the last part of the field); a measure's column carries its (renamed) name.
* **The columns.** Declared (`columns:`, name to type: `string integer decimal boolean date timestamp`): a contract; if the frame lacks a declared column the run is refused with `DRS-9075`, and a declared column is cast to its type. Not declared: the dataset has no columns until the Transform has run once; the dataset page then says so (`DRS-9017`, a warning) and **Run the Transform now** (`POST /datasets/{id}/transform/run`, authors) runs it, types the columns from the frame (`int` is `integer`, `float` or decimal is `decimal`, `bool` is `boolean`, dates and timestamps likewise, everything else `string`) and keeps them; reports can then use the fields. Column names must be letters, digits and underscore.
* **Cached per reader and version of the inputs.** The frame is kept (a Parquet file in the BI store) for the reader it was made for, until `drishti.bi.transform.cache-ttl` (30 minutes) passes or an input changes: a source dataset republished, a source's layers rebuilt by a data load, the Transform item or the dataset's `params` changed, another business date asked. A second reader has other rows and masks, so the Transform runs again for them. The `engine` counters (`GET /api/v1/bi/engine`) show `transforms: {runs, cacheHits, failures, kept}`. Real output of the run on the sample, twice (the second answers from what was kept):

```
POST /api/v1/bi/datasets/file:dataset:desk-pnl-vs-exposure-py/transform/run     (as an author who may read every row)
{"dataset": "file:dataset:desk-pnl-vs-exposure-py", "rows": 18, "cached": false, "millis": 218,
 "columns": [{"name": "counterparty", "type": "string"}, {"name": "name", "type": "string"}, {"name": "sector", "type": "string"}, {"name": "desk_pnl", "type": "decimal"},
             {"name": "trades", "type": "integer"}, {"name": "ee_peak", "type": "decimal"}, {"name": "pfe_peak", "type": "decimal"}, {"name": "pnl_per_exposure", "type": "decimal"}, {"name": "exposure_rank", "type": "decimal"}]}
(again)   {"cached": true, "millis": 0, "rows": 18}
```

* **Limits.** `drishti.bi.transform.*`: rows in (`max-input-rows`), rows and bytes out (`max-rows`, `max-bytes`), time (`timeout`, and the item's own `timeout`), runs at once (`max-concurrent`), the wait for a place (`queue-wait`). Over a limit is `DRS-9072`; no runner is `DRS-9074`; an exception in the code is `DRS-9071`; the sandbox's refusals are `DRS-9073`; a frame that lacks a declared column, or inputs that do not match the item's `inputs`, is `DRS-9075`. The code runs in the same sandbox as every Python item (no network, a scratch folder, CPU, memory and output limits: [PYTHON_BI.md section 7](../guides/PYTHON_BI.md#7-the-sandbox-exactly)); the server starts the console's runner for it (`drishti.bi.transform.python`, `runner`).
* **Refresh.** A derived dataset has no layers to rebuild. A schedule or `after-load` in `refresh:` (7.2) follows its sources' loads and drops what was kept for it, so the next reader's Transform runs on fresh inputs; without a schedule the version of the inputs does the same on the first query after a load.

### 11.3 Who sees what

* **Each source is asked as the reader**, with *its own* row rules, masks and entitlements, inside its own query, **before** the join. A desk-head's rule on trades cuts the trades first; an analyst's masked name arrives masked. Nothing a source hides reaches the joined table or the Transform. Real output of the sample for three readers (rita holds `risk`, hana is a desk-head of one desk, ann is an analyst without the raw power):

```
rita  18 counterparties   Desk P&L 2,671,113   Exposure (EE peak) 1,447,544,943   first row: Aldershot Pension Trust  946,326  98,882,895
hana  17 counterparties   Desk P&L 1,060,102   Exposure (EE peak) 1,356,300,321   first row: Aldershot Pension Trust  611,721  98,882,895   (her desk's trades only; exposure has no rule)
ann   18 counterparties   Desk P&L 2,671,113   Exposure (EE peak) 1,447,544,943   first row: •••  (joined.name is masked: the answer says masked: [joined.name])
```

* **What a source masked is masked here.** A column or measure a source masks for the reader is masked in the joined table (a dimension reads `•••` and is not grouped on, a measure and every measure of this dataset made from it is null, and the answer's `masked` lists them); a filter or a sort on one is `403 DRS-9021`.
* **A join on a masked key is refused.** A masked column holds one constant, so joining on it would match every row to every row. `403 DRS-9021` says so and names the source. Real refusal (a dataset that joins on the counterparty *name*, for an analyst):

```
DRS-9021 the join key 'name' (counterparties.NAME of source 'pnl' in 'join-on-name') is masked for you: a join on a masked column would match every row to every row, so it is refused
```

  The sample therefore joins on the counterparty's **id** (`counterparties.id`), which is not masked, and shows the name as a masked attribute. Choose the key that way.
* **The composed dataset's own `security:`** (row rules over the joined columns, `masked:`) is applied on top, as for any dataset; it is rarely needed, because the sources already did it.
* **Entitlements.** A source over a kind the reader may not open has no rows for them (a warning, as in 6); the composed dataset's page never asks a connector itself.
* **Result cache.** The result of a query is cached per reader as any result is, keyed also by the version of every source (their text, their layers' generation, theirs in turn), so a reloaded source never serves a stale composed answer.

### 11.4 Lineage, impact and the Model view

* **Impact analysis.** A change to a source dataset is set against the composed and derived datasets built from it as it is against reports: removing `Exposure (EE peak)` from `counterparty-exposure` makes publishing it a breaking change (`DRS-9040`) that names `desk-pnl-vs-exposure` ("measure Exposure (EE peak) (source 'exposure' of desk-pnl-vs-exposure)"), published and in drafts. A report names its datasets for the same purpose (REPORTS.md section 16). Lineage (`GET /bi/items/{id}/lineage`) shows a composed dataset's sources as its upstream, with what it takes of each, and shows it below each source.
* **The Model view** (`/bi/m/<dataset>`) draws a composed dataset as its sources (each with the columns it is grouped by and its measures, the join keys marked), the joins as labelled lines between the key columns, and the joined table they feed; a derived dataset adds a box for the Transform. The relationship form and proposals are for tables and are hidden. The dataset page lists the sources under **Built from other datasets**, with **Run the Transform now** for a derived one.

## What was not built

**Was not built for the 2.1 composed and derived datasets (removed in 2.2):** joins other than on equal columns (no range or expression joins) and other than `inner`, `left` and `full`; a filter of a visual **pushed down into the sources** (a filter on the joined table is applied after the join, the sources are asked for their whole answer, within `compose-source-max-rows`); rollups and relationships on a composed dataset (it has one table); a Transform result **shared between readers** (it is kept per reader); a Transform that is **run in the browser** (server only); a derived dataset's `refresh:` that **re-runs the Transform for every reader in advance** (it drops what was kept; the next reader runs it); **Source** Python items (a table from a Python-only source, [RUPAKA.md section 13.1](../architecture/RUPAKA.md)).

## Troubleshooting in 2.1


* **`DRS-9014` or `DRS-9016` on the dataset page, and every query `DRS-9010`.** A composed dataset is checked against the datasets it is built from. `no dataset 'x'`: a source is named by catalogue id, file name or the dataset's own name; `source dataset 'x' has errors`: fix that one first; `the key 'k' of 'a' is date but 'k' of 'b' is string`: both sides of a join are of one type; `'k' is not a column of 'a'`: a join is made on the names the frame gives (`trade.desk as d` is `d`). `DRS-9016` names the loop.
* **`DRS-9021` "the join key 'counterparty' ... is masked for you".** A source masks the column you join on (the server's redact list or the source's `masked:`). Join on an unmasked key (an id), or ask for the raw power. The refusal is the safe answer: joined on a mask, every row would meet every row.
* **A composed measure is far too high.** `DRS-9015` on the dataset: a join repeats rows, and Check refuses it. Declare `key:` on each source, group the many side by the join key alone, swap the ends, or accept the repetition with `allow-repeat: true` on the join.
* **A visual of a composed dataset shows less than the other datasets say.** Each source is asked as you, before the join: a desk-head's row rule cuts the trades first, and an inner join then drops the counterparties they have none with. A `left` join keeps the left source's rows.
* **`DRS-9022` "source 'x' answers more than ... rows".** A source returned over `drishti.bi.transform.compose-source-max-rows`: group it by less in `from`, or filter it.
* **A derived dataset says "the columns of the result are not known yet" and no field resolves.** It declares no `columns:` and has never run: press **Run the Transform now** on its page (or `POST /datasets/{id}/transform/run`); the columns of that result are kept, and the page checks again.
