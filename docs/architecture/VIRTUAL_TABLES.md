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
# Virtual tables: one central table layer for every consumer

*Product owner, 2026-11-03: "virtual tables: logically defined tables which get data from a table in Delta. The actual table may be
complex JSON, but a configuration applies Rachana grammar to change the data layout so it starts looking like a traditional table, and
using Arrow we should be able to serve it." Then: "BI models and datasets are actually virtual tables. I want to generalise the concept and
centralise it so BI and other consumers can use it." Status: **designed, not built**; the questions of section 10 decided as recommended by the
product owner (2026-11-03: "virtual table is quite a generalised concept and sits on top of connectors, not just the lake"; "the main
thing is that Arrow Flight can serve data from a virtual table too"). (planned)*

## 1. The idea

Drishti holds data in many shapes: entity documents in a lake (a trade with its `LEGS`, `CASH_FLOWS` and `EVENTS` in one JSON `doc`),
typed risk tables, connectors to other stores (PostgreSQL, REST, Kafka, the in-memory lake). People and programs want **tables**: rows and
typed columns, joined where they relate. Today only BI turns the first into the second, inside BI, with its datasets and models.

A **virtual table** is that turn made central: a named, typed table defined by configuration in Rachana, over any kind any connector
serves (a lake table, a database table, an in-memory table) or over other virtual tables. Every consumer reads virtual tables the same
way: BI, the pivot, Sutras, Arrow Flight and Flight SQL, the PostgreSQL protocol, the Data view and its downloads, alerts, the AI's
questions. BI keeps what is BI's (reports, visuals, the designer, boards); the tables move under it.

## 2. Two forms, one layer

| Form | Is | Today's equivalent |
|---|---|---|
| **A flat virtual table** | one kind (or a lake table, or several of one kind) read as rows and typed columns: a grain (`$.LEGS[*]`), a filter, columns that are Rachana paths or scalar expressions | a BI **dataset** table (`columns:` with `path:`, `each:`, calculated columns) |
| **A model** | virtual tables related to each other (keys, relationships, cardinality, direction) with **measures** (aggregates over them, defined once) | a BI **model** (relationships, measures, the model query MQ) |

Both live in one layer, a module of their own, with one catalogue, one compiler and one access rule. A model's measures are written in
Rachana and computed by the pivot engine (ONE_GRAMMAR.md: the one grammar and the one engine); DEL is folded in 2.9.11.e.

## 3. What moves, so nothing is defined twice

| Today | Becomes |
|---|---|
| `config/bi/datasets/*.dataset.yaml` (tables with `path:`, `each:`, calculated columns) | **flat virtual tables** in the central catalogue; BI reads them |
| `config/bi/models/*.model.yaml` (relationships, measures, MQ) | **models** in the central catalogue; BI's reports and visuals name them as today; MQ stays the query contract, answered by the layer |
| BI's `cached` / `rollup` / `live` layers | the layer's **materialisation** of a virtual table or model (section 6), the same code moved |
| the pivot's document fields and its "dataset column" route (PIVOT_ENGINE 6.7) | the pivot reads virtual tables and models as sources; its ad-hoc paths stay for exploring, and **Promote** offers *add as a column of a virtual table* |
| pack-generated datasets (BI_GUIDE section 23) | packs ship recommended virtual tables and models (the trading pack: `trade_legs`, `trade_cash_flows`, `trade_events`), copied into the catalogue once |

BI's screens that edit datasets and models (the dataset editor, the Model view) become the layer's editors, reachable from BI and from
Admin; their files move with a migration tool and BI's parity test (the same figures before and after) proves nothing changed.

## 4. The definition

`config/tables/<domain>/<name>.table.yaml` and `.model.yaml` (planned), one file each, so a table can be reviewed and versioned on its own:

```yaml
table: trading.trade_legs                   # <domain>.<name>
from: { kind: trade }                       # any kind (whichever connector serves it), or { lake: demo, table: trading.trade_endur }, or { table: trading.trades }
grain: $.LEGS[*]                            # one row per leg; omitted: one row per entity
filter: "$.TRADE_STATUS != 'CANCELLED'"     # a Rachana condition on the entity ($) and the row's element (@)
columns:
  TRADE_ID:      $.TRADE_ID
  LEG_ID:        "@.LEG_ID"
  LEG_TYPE:      "@.LEG_TYPE"
  CURRENCY:      "@.CURRENCY"
  NOTIONAL:      { path: "@.NOTIONAL", type: decimal }
  FLOAT_INDEX:   "@.INDEX.NAME"
  NOTIONAL_USD:  { expr: "@.NOTIONAL * fx(@.CURRENCY, 'USD')", type: decimal }
keys: [TRADE_ID, LEG_ID]
materialize: none                           # none | on-load | rollup (section 6)
```

```yaml
model: trading.positions-and-legs
tables: [trading.trades, trading.trade_legs, core.counterparties]
relationships:
  - { from: trading.trade_legs.TRADE_ID, to: trading.trades.TRADE_ID, cardinality: many-to-one }
  - { from: trading.trades.COUNTERPARTY, to: core.counterparties.id, cardinality: many-to-one }
measures:
  Notional:        sum(trading.trade_legs.NOTIONAL_USD)
  Trades:          count(trading.trades)
  Average notional: ratio(sum(trading.trade_legs.NOTIONAL_USD), count(trading.trades))
```

Every virtual table has `business_date` (from its source, always) and its keys. A flat table's columns are scalar (never aggregates);
aggregates are a model's measures, and the many-to-one and once-per-parent rules (PIVOT_ENGINE 6.7) keep them from counting twice.

## 5. How it is read

One compiler, the pivot engine's: each column goes to the fastest route that holds it (a promoted column of a lake table, the
materialised Parquet, a connector's column scan, else DuckDB's JSON over `doc`); a model's query compiles to the engine's grouping sets as
MQ does today. The answer is Arrow batches, so **Arrow Flight serves a virtual table or a model like a stored table** (the product owner's main point:
Flight's ticket names it, `{"table": "trading.trade_legs", "date": …}`, and the cluster read, limits, streams and audit work as for any
read), and so do the pivot, BI and the SQL doors (Flight SQL and the PostgreSQL protocol see virtual tables and models as tables in schemas
named by domain).

## 6. Materialisation

| `materialize` | Means | Code |
|---|---|---|
| `none` (default) | read on demand; the scan estimate shows the cost first | the compiler |
| `on-load` | Parquet per business date, rebuilt when the source's data-load signal arrives | BI's cached layer, moved |
| `rollup` (models) | partial states kept per date and refreshed on load, chosen automatically for queries they answer | BI's rollups and the pivot's (P3), one mechanism |

Materialised files are a cache: rebuilt at will, never the truth, never written into a lake's own tables.

## 7. Who sees what

A virtual table is read **as its source**: the reader's row rules on the source kind apply to every row, and a column built from a masked
path is masked. A model adds BI's row rules per dataset (they move with it). Nothing adds rights. Definitions are configuration: an
administrator or a dataset author with the power BI grants today, reviewed and audited, versioned with the item versions BI already has.

## 8. Checks and tools

`drishti.py tables check` (planned) compiles every virtual table and model against its sources' contracts and a sample: unknown paths,
types that do not cast, keys that are not unique, a grain crossing two lists, a relationship whose key types differ. `drishti.py tables (planned)
preview trading.trade_legs --date …` (planned) prints the first rows. Admin and BI list them with their sources, grains, columns,
relationships, measures, materialisation and who uses them (lineage).

## 9. Size and order

L: the module and its catalogue, BI's datasets, models and layers moved under it with a migration tool and the parity test, the pivot and
Flight reading it, the checks and preview, docs (a tables guide with real examples on the demo lake; the BI, pivot and Flight guides).

## 10. Decisions (product owner, 2026-11-03: every recommendation accepted)

| # | Question | Decided |
|---|---|---|
| V1 | A central layer of its own (a module, `config/tables/` (planned)), not inside BI and not in the lake file | **Yes**: its sources are any kind, not only the lake |
| V2 | BI's datasets become flat virtual tables and BI's models become the layer's models; BI keeps reports, visuals and boards | **Yes**: one definition, migrated by a tool, proved by the BI parity test |
| V3 | Flat tables' columns are scalar Rachana paths or expressions; aggregates are model measures | **Yes** |
| V4 | Materialisation reuses BI's cached and rollup layers (moved, not copied) | **Yes** |
| V5 | Everything reads it: BI, pivot, Sutras, Flight, Flight SQL, the PostgreSQL protocol, the Data view, alerts, the AI | **Yes** |
| V6 | Release | **Its own release right after 2.9.11.c** (the lake configuration and Flight land first; Flight then gains virtual tables and models as a ticket form). It is large and moves BI's core, so it gets two drills of its own, after the DEL fold is designed so measures are written in Rachana from the start |
