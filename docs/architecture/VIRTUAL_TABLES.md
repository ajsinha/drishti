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
centralise it so BI and other consumers can use it." The product owner's first points: virtual tables sit on top of connectors, not just the lake, and
Arrow Flight serves them. Status: **decided** (product owner, 2026-11-04): section 10 records every decision; built in
2.9.11.e with the DEL fold.*

**The deeper design for 2.9.11.e** (DEL into Rachana, every dataset and model key mapped, the migration tool, the parity proof, Flight's
`table` and `model` forms, and questions Q1 to Q30, some of which refine V1 to V5 below): [BI_FOUNDATION_2911E.md](BI_FOUNDATION_2911E.md),
decided by the product owner on 2026-11-03 (Q30 amends V1 to V5 below: the module is `drishti-tables`; rules and masks are on the table;
`live` materialisation; summary tables). The layer's foundation is built ([section 9 there](BI_FOUNDATION_2911E.md#10-as-built-part-b-the-table-layer));
its reference is [TABLES_GUIDE.md](../guides/TABLES_GUIDE.md).

## 1. The idea

Drishti holds data in many shapes: entity documents in a lake (a trade with its `LEGS`, `CASH_FLOWS` and `EVENTS` in one JSON `doc`),
typed risk tables, connectors to other stores (PostgreSQL, REST, Kafka, the in-memory lake). People and programs want **tables**: rows and
typed columns, joined where they relate. Today only BI turns the first into the second, inside BI, with its datasets and models.

A **virtual table** is that turn made central: a named, typed table defined by configuration in Rachana, over any kind any connector
serves (a lake table, a database table, an in-memory table) or over other virtual tables. Every consumer reads virtual tables the same
way: BI, the pivot, Sutras, Arrow Flight and Flight SQL, the PostgreSQL protocol, Export data and its downloads, alerts, the AI's
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
| the pivot's document fields and its "dataset column" route (ARCHITECTURE.md section 23.6.7) | the pivot reads virtual tables and models as sources; its ad-hoc paths stay for exploring, and **Promote** offers *add as a column of a virtual table* |
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
aggregates are a model's measures, and the many-to-one and once-per-parent rules (ARCHITECTURE.md section 23.6.7) keep them from counting twice.

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

`drishti.py tables check` compiles every virtual table and model against its sources' contracts and a sample: unknown paths,
types that do not cast, keys that are not unique, a grain crossing two lists, a relationship whose key types differ. `drishti.py tables
preview trading.trade_legs --sample …` prints the first rows. Admin and BI list them with their sources, grains, columns,
relationships, measures, materialisation and who uses them (lineage).

## 9. Size and order

L: the module and its catalogue, BI's datasets, models and layers moved under it with a migration tool and the parity test, the pivot and
Flight reading it, the checks and preview, docs (a tables guide with real examples on the demo lake; the BI, pivot and Flight guides).

## 10. Decided (product owner, 2026-11-04)

| # | Decided |
|---|---|
| V1 | **A central layer of its own** (the module `drishti-tables`, definitions under `config/tables/<domain>/`), not inside BI and not in the lake file: its sources are any kind any connector serves |
| V2 | **BI's datasets become flat virtual tables and BI's models the layer's models**, migrated by one tool (`drishti.py tables migrate`, dry run and report) and proved by the BI parity test; BI keeps reports, visuals and boards |
| V3 | **A flat table's columns are scalar** Rachana paths or row expressions; aggregates exist only as a model's measures |
| V4 | **Materialisation is BI's cached and rollup layers moved, not copied**: one mechanism, the old code deleted at the switch |
| V5 | **Every consumer reads the layer**: BI, the pivot, Sutras, Arrow Flight, Flight SQL, the PostgreSQL protocol, Export data, alerts, the AI |
| V6 | **Built in 2.9.11.e together with the DEL fold**: one migration tool, one parity proof, two drills |
| V7 | **Many tables over one source.** Any number of virtual tables may be rooted in one connector and kind, each with its own columns, grain and filter (`trading.trade_summary` and `trading.trade_legs` both `from: { kind: trade }`). Within one request (a pivot view, a model query, a Flight read, a Sutra page), the reads of one source for one business date are shared: the source is read once and every table over it is computed from that one read |
| V8 | **New tables from data loads are offered, never made automatically.** A table a data load creates (DATA_ARCHITECTURE.md section 10.7) is offered a flat virtual table in one click, its columns suggested from a sample of the new rows (`drishti.py tables offer`); nothing is defined until someone accepts |
| V9 | **Who edits, who reads.** Administrators and BI authors (today's power to edit BI datasets) define tables and models. Reading is always as the source: the reader's row rules and masks on the source kind apply, then the table's own rules and masks; nothing adds rights. Definitions are audited and versioned like BI items |
| V10 | **Models across connectors.** A model may relate tables from different connectors (a lake kind and a PostgreSQL table); the engine joins them. The scan estimate warns when a side of such a join is larger than the threshold and unfiltered. **The threshold** (decided 2026-11-04): `drishti.tables.scan-warn-rows` for the installation (1,000,000), and a model may set its own with `scan-warn-rows:` in its `.model.yaml`, which wins for that model |
| V10a | **Tables use look-ups; models join** (product owner, 2026-11-06). A virtual table enriches its rows with look-ups (`related(…)`, many to one) and never multiplies them; combining tables row by row (one to many, several facts, across connectors) is a semantic model's relationships. The table editor shows look-ups as rows; joins live in the model editor. |
| V11 | **Dates as the source has them.** Every read names a business date (none: the source's latest). Known-at time travel where the source supports it. An undated source gives its current rows, dated today |
| V12 | **Names.** `<domain>.<name>`, unique across virtual tables and stored tables: a virtual table named like a stored table (a lake table, a kind) is refused when it is defined |
| V13 | **Sutras read tables, in 2.9.11.e** (decided 2026-11-04; Q22 had put it in the next release). A panel may name a virtual table as its source: `source: { table: trading.trade_legs, where: "<Rachana condition>", limit, as-of }`; the rows are `$src`, read for the page's viewer (the source as they may see it, then the table's rules, masks and hidden columns). The viewer travels with every call of the view pipeline as one `ViewerContext` (user, roles, attributes, raw power, masks, kinds), never kept in a global, a shared cache or a thread-local: multi-user isolation is a release blocker. A page built for nobody in particular (a snapshot for an audience, a tool) reads no table |
| V14 | **One SQL engine.** DuckDB is the one SQL engine, required, with no fallback; it moves behind `drishti-native`'s `SqlEngine` in 2.9.12. The layer adds no new place that calls DuckDB: it reads through the pivot engine's executor and BI's engine as they are |
| V15 | **One grammar, no dual machinery.** Rachana is the only expression language of the layer; whatever moves into it is moved, never copied |

The deeper design and the questions Q1 to Q30 it settled (2026-11-03) are in
[BI_FOUNDATION_2911E.md section 8](BI_FOUNDATION_2911E.md#8-decisions-product-owner-2026-11-03-every-recommendation-accepted-q1-decided-as-rachanas-rule).

## 11. Screens: decided (product owner, 2026-11-04)

The editors move from BI (the dataset editor, the Model view) to the layer, reachable from Admin and from BI. Not built yet: the backend
steps come first.

**Tables.** Of the mock-ups [A](mockups/virtual-tables/A.html), [B](mockups/virtual-tables/B.html) and
[C](mockups/virtual-tables/C.html), the product owner chose **B and C with a switch**:
[virtual-tables/BC.html](mockups/virtual-tables/BC.html).

**Models.** Of [model-A](mockups/virtual-tables/model-A.html), [model-B](mockups/virtual-tables/model-B.html) and
[model-C](mockups/virtual-tables/model-C.html), the product owner chose **B (the diagram with a live answer) and C (the
measures-first sheet) behind one switch**, with a YAML view: [virtual-tables/model-BC.html](mockups/virtual-tables/model-BC.html).
A and the single-option mock-ups stay for the record.

| Point | Decided (tables and models alike) |
|---|---|
| Visual views | tables: Visual (B: a sample document as a tree, the columns, the rows as a chosen role reads them), the default for everyday users; models: Diagram (B) and Measures (C) |
| YAML view | the definition as text, full screen, with Rachana completion and checks as you type; preview, checks, lineage, versions and the diff as tabs; for experts |
| One definition | one switch flips between the views on the same table or model; all edit the one draft, so an edit in one shows in the others |
| Live YAML panel | below every visual view, a panel shows the definition as YAML, updated at once by every visual edit; collapsible, its open or closed state remembered per person |
| Editing the YAML | anyone with the edit right (an administrator or a BI author) edits it in the panel or full screen; a valid edit updates the visual part at once |
| Invalid YAML | the error is shown at its line, the text kept as typed; the visual part keeps the last valid version until it is fixed, and the switch back to a visual view waits until then |
| Without the edit right | the YAML is read-only (panel and full screen) |
| Memory | each person's last view and the panel's state are remembered |
| New data | the offer for a table a data load created (V8) opens the table editor, prefilled |

The screenshots are taken in the next heavy run.
