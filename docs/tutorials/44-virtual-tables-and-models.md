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
# Tutorial 44 · Virtual tables and models: trade legs as a table, a model over it, BI's samples migrated

A virtual table turns documents into rows and typed columns, once, for every reader. In this tutorial you define the demo trades' legs as
a table, preview it over sample documents, add a column converted to US dollars and a desk's name looked up in another table, give a
desk-head only their desks, relate the table to a desk table in a model with measures, ask the model, and migrate BI's shipped datasets
and models with a dry run first. Each step shows the YAML, the command line and the screen. The guide is
[TABLES_GUIDE.md](../guides/TABLES_GUIDE.md).

Outputs marked *(filled in the heavy run)* are replaced by the real output on the demo lake, with the screenshots, when the release's
examples are run; the YAML and the commands are the real ones. This tutorial takes its number in the tutorials' index with its pictures.

## Time to complete

About 20 minutes.

## Before you start

- A checkout of the repository and the server's exec jar built (`./mvnw -q -DskipTests package`): the `tables` commands run from it
  without starting a server ([CLI_GUIDE.md](../guides/CLI_GUIDE.md)).
- A few trades as JSON Lines, one trade a line (the demo lake's trades, or `docs/tutorials/data`'s samples) in `trades.jsonl`, and the
  desks in `desks.jsonl`.
- Run every command from the repository's folder: the tables live under `config/tables`, one folder per domain.

## What you will learn

- how a table's grain, paths and columns turn a trade's legs into rows, and what `$`, `@` and `^` name;
- why a calculated column reads the source, never another column, and how `fx` and `related` read another source;
- how a table's rules and masks apply to every reader, and what a desk-head with no desks sees;
- how a model relates tables and defines measures in Rachana, and how the layer answers it;
- how `tables migrate` turns BI's datasets and models into tables and models, keeping every figure (the null rule).

## Step 1 · A table of the trades' legs

Write `config/tables/trading/trade_legs.table.yaml`:

```yaml
rachana: 3
table: trading.trade_legs
from: { kind: trade }
grain: $.LEGS[*]                          # one row per leg
keys: [TRADE_ID, LEG_ID]
columns:
  TRADE_ID: $.TRADE_ID                    # the trade's own field
  DESK:     { path: $.DESK, type: string }
  LEG_ID:   { path: "@.LEG_ID", type: integer }
  CURRENCY: { path: "@.CURRENCY", type: string }
  NOTIONAL: { path: "@.NOTIONAL", type: decimal, format: amount0 }
materialize: on-load
```

Check it:

```text
$ python3 tools/drishti.py tables check
(filled in the heavy run)
```

On the screen (planned with the switch: Admin → Tables): the table, its grain, its five columns and *on-load*.

You should see `0 error(s)`. Write `$.LEGS[0]` as the grain, or `@.LEG_ID` in a table without a grain, and the check names the key and the
reason (`DRS-9603`).

## Step 2 · Its rows over sample documents

```text
$ python3 tools/drishti.py tables preview trading.trade_legs --sample trades.jsonl --limit 5
(filled in the heavy run)
```

Add `--json` for the rows as JSON. Check the keys on the sample:

```text
$ python3 tools/drishti.py tables check --sample trading.trade_legs=trades.jsonl
(filled in the heavy run)
```

You should see one row per leg, the built-in columns `id`, `business_date` and `index` first, and no `DRS-9613` (the keys are unique on
each business date).

## Step 3 · A column in dollars and the desk's name

Add two calculated columns. A column's expression reads the source (`@.NOTIONAL`), never another column (`NOTIONAL`):

```yaml
  NOTIONAL_USD: { expr: "@.NOTIONAL * fx(@.CURRENCY, 'USD')", type: decimal }
  DESK_NAME:    { expr: "related(core.desks.NAME, on = $.DESK)", type: string }
```

`fx` reads the installation's one FX source at the row's business date; `related` reads the `NAME` of the `core.desks` row whose key is the
trade's desk. Write `config/tables/core/desks.table.yaml` with `NAME` and `REGION` columns over the kind `desk`, then preview again.

```text
$ python3 tools/drishti.py tables preview trading.trade_legs --sample trades.jsonl --limit 3
(filled in the heavy run)
```

You should see the notional in dollars beside each leg's own, and the desk's name. Write `NOTIONAL * 2` instead and the check refuses it
with the path to write (`$.NOTIONAL` is not the leg's: the leg's is `@.NOTIONAL`).

## Step 4 · Who sees which rows

Add rules and a mask to the table:

```yaml
rules:
  - { role: desk-head, where: "$.DESK in user.desks" }
  - { role: [risk, analyst], where: "true" }
```

and `masked: true` on a column you want hidden from readers without the raw power. The rules apply to every reader of the table: BI, the
pivot, Arrow Flight.

You should see, once BI reads the layer: a desk-head of two desks sees their two desks' legs; a desk-head with no desks sees none (`in` is
strict: an empty list holds nothing); a role no rule names sees none and is told so.

## Step 5 · A model over the legs and the desks

Write `config/tables/trading/legs.model.yaml`:

```yaml
rachana: 3
model: legs
tables:
  legs:  { table: trading.trade_legs, role: fact }
  desks: { table: core.desks, role: dimension }
relationships:
  - { from: legs.DESK, to: desks.id }
measures:
  Notional USD:   { expr: "sum(legs.NOTIONAL_USD)", format: amount0 }
  Legs:           { expr: "count(legs)" }
  Average leg:    { expr: "measure('Notional USD') / measure('Legs')", format: amount0 }
  Non-USD legs:   { expr: "count(legs, where = legs.CURRENCY != 'USD' && legs.CURRENCY != null)" }
hierarchies:
  Desk: [desks.REGION, desks.NAME]
```

```text
$ python3 tools/drishti.py tables check
$ python3 tools/drishti.py tables lineage trading.trade_legs
(filled in the heavy run)
```

You should see `0 error(s)`, and the lineage name the model `legs` (reading the table as `legs`). Asked by `desks.REGION`, the layer
compiles each measure with Rachana's one compiler and the pivot engine's aggregates: *(the figures by region, filled in the heavy run)*.

## Step 6 · BI's samples migrated, with a dry run first

```text
$ python3 tools/drishti.py tables migrate --dry-run --report migration.txt
(filled in the heavy run: the diff of every file under config/tables/bi, and the report)
```

You should see 34 items converted, none refused, and in the report `trading-risk`'s *Open MTM*:

```text
    measures.Open MTM.expr:
      - sum(trades.MTM, where=trades.TRADE_STATUS != 'MATURED')
      + sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED' && trades.TRADE_STATUS != null)
        (kept DEL's meaning for a missing value: ... (Q1))
```

The guard keeps BI's figure: in Rachana a missing status is not `'MATURED'`, so without it a trade with no status would be counted. Then
`--check` (exit 1 while something would change) and the write:

```text
$ python3 tools/drishti.py tables migrate --audit data/audit/tables-migrate.jsonl
$ python3 tools/drishti.py tables migrate --check
nothing to migrate
```

You should see the files under `config/tables/bi/`, one audit line per file, and the second run change nothing.

## Next steps

- Every key, check and code: [TABLES_GUIDE.md](../guides/TABLES_GUIDE.md).
- The language: [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) (paths, `in`, the null rule, checked mode).
- The design and what is built: [BI_FOUNDATION_2911E.md](../architecture/BI_FOUNDATION_2911E.md).
