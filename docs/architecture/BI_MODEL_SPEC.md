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
# Rūpaka model layer: design specification for wave W1 (and what W2 to W5 need from it)

*Status: agreed by the product owner on 2026-10-12 (all eight open questions of section 11 accepted with the recommended
answers, question 1 with the security safeguard of section 2.6). Wave W1's server package is built (the item kinds and their
checks, pack-generated datasets, implicit models, the model query language with its API and standing queries, the
compatibility reader; packages S1, S2, S3, S6 and the implicit models of S5); the connector-query, Python and model sources
(S4), the consumers on MQ (S5), the console (C1, C2), the converter (M1) and the guides (D1) follow.* This is the specification behind wave **W1
"Layers and the query language"** of [BI_MODEL_ARCHITECTURE.md](BI_MODEL_ARCHITECTURE.md) (decisions D1 to D6, the
experience rules of its section 3). It fixes the **formats** W1 introduces so that W2 to W5 extend them without a
second breaking change: every key that a later wave fills is already named here, marked with the wave that gives it
behaviour. The gap analysis it answers is [BI_PARITY_PLAN.md](BI_PARITY_PLAN.md); the current shapes it replaces are
[DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [BOARDS.md](../guides/BOARDS.md),
[ANALYSE.md](../guides/ANALYSE.md), [PYTHON_BI.md](../guides/PYTHON_BI.md), [BI_AI.md](../guides/BI_AI.md) and
[BI_GOVERNANCE.md](../guides/BI_GOVERNANCE.md).

Contents: [0](#0-reading-guide-and-terms) terms · [1](#1-the-dataset) the dataset · [2](#2-the-data-model) the data
model · [3](#3-implicit-models) implicit models · [4](#4-consumers-bind-to-one-model) consumers ·
[5](#5-the-model-query-language-v1) the model query language · [6](#6-worked-examples) ten worked examples ·
[7](#7-pack-generated-datasets-d3) pack-generated datasets · [8](#8-migration) migration · [9](#9-ux-flows) UX flows ·
[10](#10-w1-build-plan) W1 build plan · [11](#11-open-questions-for-the-product-owner) open questions

## 0. Reading guide and terms

| Term | Meaning in this spec |
|---|---|
| **dataset** | one governed **table** (D1): a source, a grain, columns with types and meanings, a time mode, refresh, security. Item kind `dataset`, file `config/bi/datasets/<id>.dataset.yaml` |
| **data model** (model) | datasets in a star, relationships, measures, hierarchies, model security, a contract. Item kind `model`, file `config/bi/models/<id>.model.yaml` |
| **implicit model** | the model Drishti makes for a consumer that names one dataset only (section 3) |
| **model query** (MQ) | the one request shape for data (D5), `mq: 1` (section 5) |
| **consumer** | report, board, notebook, alert, subscription, export, embed, feed, AI, MCP: each bound to exactly one model (D2) |
| **field** | `<alias>.<column>` or `<alias>.<column>.<part>` of a model (alias = the dataset's name inside the model) |
| **stable id** | an immutable id of a column, measure, hierarchy or dataset inside its item (`c_…`, `m_…`, `h_…`), written back into the YAML by the designers (R3 of BI_PARITY_PLAN) |
| **W1 … W5** | the waves of release 2.2.0; a key marked *(W3)* parses in W1, is validated in W1, and gets its behaviour in W3 |

**Language versions.** New items are `rachana: 2`. A `rachana: 1` dataset file is read in W1 by a **compatibility
reader** (section 8.4) and removed in W4 (no dual support after W4, as BI_PARITY_PLAN section 5.0 requires).

**Code ranges.** New problem codes are `DRS-9100` to `DRS-9199`, unused today: datasets 9100–9119, models 9120–9139,
consumers 9140–9149, the query language 9150–9179, standing queries 9180–9189, migration 9190–9199. Existing codes keep
their meaning (`DRS-9020` and `DRS-9021` are still what the engine answers for a refused or masked query; the MQ
validator answers the more precise 915x code first).

**Format conventions.** Every item keeps the catalogue keys of today (`title`, `description`, `owner`, `tags`,
`endorsement`, `audience`, `sample`) and the governance of today (drafts, four-eyes, publish, saved states, impact).

---

## 1. The dataset

A dataset is **one table**. It never joins (joins are relationships in a model) and never declares measures (measures
live in models; a dataset declares **measure defaults** per column so a model gets the right aggregation for free).

### 1.1 Top-level keys

| Key | Required | Meaning |
|---|---|---|
| `rachana: 2` | yes | language version |
| `dataset` | yes | name: letters, digits, `.`, `_`, `-`, up to 64 (generated datasets: `pack.<pack>.<kind>`, section 7) |
| `version` | yes | **semver** of the dataset's contract (`1.0.0`); the designer bumps it (section 2.8 rules apply) |
| `id` | written | stable id of the dataset (`ds_7f3a…`), written on first save, never changed |
| `source` | yes | exactly one of `pack`, `connector`, `python`, `model` (1.2) |
| `grain` | yes | what one row is, in words and keys (1.3) |
| `columns` | yes* | the columns (1.4). *Generated from the source for `pack`; declared for `python` (a contract) and `connector` queries |
| `time` | no | the time mode, as-of and history (1.5). Default: `live` where the source streams, else `snapshot` (D6) |
| `refresh` | no | when the dataset's layers are rebuilt (1.5) |
| `security` | no | `rows` (row rules) and `masked` (1.6) |
| `ai` | no | synonyms, units, examples, hints (1.7) *(behaviour W5)* |
| `tests` | no | expected values and data tests (1.8) |
| `extends` | no | another dataset this one narrows (1.9) |
| `rollups` | no | stored or live aggregates of this table (unchanged meaning from DATASETS.md section 7; `measures:` here are `{agg, column}` pairs, not names) |
| `layout` | no | designer-only (ignored by the language) |

Unknown top-level keys are a warning (`DRS-9100`), as today.

### 1.2 Sources

```yaml
# A. A pack kind, read through a connector (the common case; also what packs generate, section 7)
source:
  pack: trading                 # optional: which pack's definition of the kind (default: the pack that declares it)
  kind: trade
  connector: trading-store      # optional: a connector by logical name; empty = whichever serves the kind (as today)
  each: legs                    # optional: one row per element of this array (as DATASETS.md section 2)

# B. A connector query: a SQL text run in a SQL-capable connector (DuckDB, PostgreSQL), parameterised only by the as-of date
source:
  connector: risk-warehouse
  query: |
    SELECT desk_id, limit_type, limit_amount, currency, as_of
    FROM limits WHERE as_of = :asOf
  params: { asOf: as-of }       # the only bindable values: as-of, business-date, from, to. Never text from a user

# C. A Python Transform: a table out of model queries (replaces the "derived dataset")
source:
  python: pnl-vs-exposure       # a Python item, interface transform
  inputs:                       # each input is a MODEL QUERY (section 5), asked as the reader
    pnl:      { model: trading-risk@1, select: { fields: [counterparties.id, counterparties.name], measures: ["P&L 1d", Trades] } }
    exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id, counterparties.sector], measures: [Exposure (EE peak), Exposure (PFE peak)] } }
  params: { floor: 1.0 }
  where: server                 # always the console backend's sandboxed runner (no browser computation, section 12)

# D. Another model's output: one model query, its answer is the table (replaces simple "composed" sources)
source:
  model: trading-risk@1
  query: { select: { fields: [desks.id, trades.currency], measures: [MTM, Notional] } }
```

| Source | Grain from | Columns from | Time modes allowed | Security applied |
|---|---|---|---|---|
| `pack` | the kind (one entity per business date, or one array element) | the pack (section 7): promoted columns, query-form fields, vocabulary | `live`, `snapshot`, `history` | the kind's entitlements, the dataset's rules and masks |
| `connector` query | declared `grain.keys` (checked by a uniqueness test) | declared, or read from the query's result types on **Check** | `snapshot`, `history` | the connector's entitlement (the reader may open the connector's packs), the dataset's rules and masks |
| `python` | declared `grain.keys` | declared `columns:` (a contract; a missing column is `DRS-9075` as today) | `snapshot` (kept per reader and input version, as today) | each input query runs **as the reader**; the dataset's own rules on top |
| `model` | the query's `fields` | the query's fields and measures (measures arrive as columns with their additivity carried over) | `snapshot`, `live` (when the source model is live: a standing query, W3) | the inner query runs as the reader; the dataset's own rules on top |

A `connector` query is **text the author writes**, reviewed like any item; its only parameters are the four named
dates, bound, never interpolated. A connector without a SQL target refuses it (`DRS-9102`).

### 1.3 Grain and keys

```yaml
grain:
  says: One row per trade per business date      # shown in the designer, the catalogue and to AI
  keys: [tradeId]                                # columns that identify a row within one business date / one live state
  date: businessDate                             # the column that is the row's date (snapshot, history); built in for pack sources
```

`keys` are checked by a **grain test** that Check and publish run (a duplicate key over a sample date is `DRS-9104`,
a warning for `python` and `model` sources until W2, an error from W2). The grain is what lets a model refuse fan-out
(section 2.3) without guessing.

### 1.4 Columns

```yaml
columns:
  tradeId:  { type: string, role: key, id: c_01 }
  desk:     { type: string, role: dimension, references: desk, id: c_02 }       # references: a kind (from the pack's graph.fields): drives relationship suggestions
  currency: { type: string, role: dimension, meaning: currency-code, id: c_03 }
  notional: { type: decimal, role: amount, unit: { currency: currency }, additive: true, aggregate: sum, format: amount0, id: c_04 }
  mtm:      { type: decimal, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_05 }
  eePeak:   { type: decimal, role: amount, unit: { currency: USD }, additive: { over-time: last }, aggregate: sum, id: c_06 }   # semi-additive (W2/W7)
  utilisation: { type: decimal, role: ratio, additive: false, aggregate: max, format: pct, id: c_07 }
  tradeDate: { type: date, role: date, id: c_08 }
  mtmK:     { type: decimal, expr: "mtm / 1000", role: amount, unit: { currency: USD }, id: c_09 }               # calculated column, as today
  counterpartyName: { type: string, path: counterparty.name, role: attribute, of: counterpartyId, id: c_10 }
```

| Key | Meaning |
|---|---|
| `type` | `string integer decimal boolean date timestamp` (as today) |
| `path`, `expr` | as today (DATASETS.md section 2); `expr` is a row-level calculated column |
| `role` | `key`, `dimension` (group and filter by it), `attribute` (describes a key: `of:` names it; used for fan-out reasoning), `date`, `amount`, `quantity`, `ratio`, `text`. Drives the designer's defaults (a `dimension` goes on an axis, an `amount` into values) |
| `meaning` | a word from the pack vocabulary (`about.yaml`), copied from the pack for generated datasets (`term`, `means`, `unit`, `sign`); shown everywhere a column is named |
| `references` | the kind this column holds ids of (pack `graph.fields`); the source of relationship suggestions |
| `unit` | `{ currency: <column or ISO code> }` for money, `{ of: <text> }` otherwise. **The currency guard** (section 5.6) reads it |
| `additive` | `true` (sum anywhere), `{ over-time: last \| first \| avg \| none }` (semi-additive: sums across other dimensions, not across dates), `false` (never summed). Default by role: amounts and quantities `true`, ratios `false` |
| `aggregate` | the default aggregation the model's **default measure** uses (`sum avg min max count distinct`) |
| `format` | a format name of the pack |
| `id` | stable id, written by the designer / converter |

Built-in columns are kept: `id`, `businessDate` (snapshot and history), `index` (`each`), `updateSeq`, `updatedAt`
(live). They are not declared.

### 1.5 Time mode, as-of and refresh (D6)

```yaml
time:
  mode: snapshot            # live | snapshot | history
  as-of: business-date      # snapshot: which date a query without one reads: business-date (latest with rows, as today) | latest-load
  keep: 400d                # snapshot and history: how far back the columns layer is kept (default: drishti.bi.query.max-days)
  live:                     # mode live: the in-memory table, as DATASETS.md section 7.1 (rows, age)
    rows: 5000
    age: 2h
  history:                  # mode history (or a live dataset's sampled history, today's live.history)
    every: 10s              #   sampled from the live table every 10 s ...
    keep: 360               #   ... newest 360 samples
    by: [desk]
refresh: "business-days 06:30"    # the words of DATASETS.md section 7.2; after-load; or { schedule, on-load }
```

| Mode | The table is | A query may ask | Default when |
|---|---|---|---|
| `live` | the current state, kept by key, pushed by the connector (today's `mode: live`) | *now*; *the last hour* through its history | the pack's connector for the kind can push (`subscribe`) |
| `snapshot` | one partition per business date (today's `mode: cached`, and `direct` where the connector is SQL) | *as of a date*, a date range | otherwise |
| `history` | a time-indexed table of samples or of a temporal source (rows valid from, to) | *a window* (`last 1h`, `from … to`) | never by default |

`direct` storage stays a property of the snapshot dataset (`time: { mode: snapshot, storage: direct }`) and keeps
today's rule (every direct dataset of one query in one store). A model **may mix modes** *(W3)*: a live fact with
snapshot dimensions is read with the dimensions as of today's business date.

### 1.6 Security at the dataset

```yaml
security:
  rows:
    - { role: desk-head, where: "trades.desk in user.desks" }   # the table is called by the dataset's own name in its rules: here `trades`
    - { role: [risk, analyst, admin], where: "true" }
  masked: [counterpartyName]
  hidden-for: { roles: [external], columns: [trader] }          # (W2) object-level: the column does not exist for these readers
```

Rules and masks keep **today's semantics** (DATASETS.md section 6): OR of the reader's matching rules, no rule = no
rows, masks before aggregation, `•••` dimensions not grouped on, measures over a masked column null, filter or sort on
one `DRS-9021`. What changes is **where they bind**: a rule names only its own dataset's columns (`DRS-9106` otherwise),
so it can be applied to that table **before any join or aggregation**, wherever the dataset is used. Section 2.6 says how
a rule on a dimension reaches the facts.

### 1.7 `ai:` metadata *(behaviour W5)*

```yaml
ai:
  synonyms: { mtm: [mark to market, market value, MV], desk: [trading desk] }
  examples: ["MTM by desk", "trades in EUR this week"]
  hints: ["A trade's notional is in its own currency; convert before adding across currencies."]
  exclude: [internalNote]        # never offered to a model, even by name
```

### 1.8 Tests

Today's `tests:` keep their form (DATASETS.md section 8.1) with `measure:` replaced by an inline aggregate
(`{ agg: count }`, `{ agg: sum, column: mtm }`), plus **data tests** that need no model:

```yaml
tests:
  - { name: Unique trade per date, unique: [tradeId] }                        # the grain test (1.3)
  - { name: Desk is always set, not-null: [desk] }
  - { name: Desk exists, references-exist: { column: desk, dataset: pack.banking-core.desk } }   # (W2) referential test
  - { name: The book holds 750 trades, agg: count, expect: 750, date: 2026-10-09 }
```

### 1.9 `extends`: narrowing a dataset without copying it

```yaml
dataset: trades-eu
extends: pack.trading.trade@1      # all columns, grain, time, security of the base
where: "currency in ('EUR', 'GBP', 'CHF')"     # a row filter (a dataset filter, not a security rule)
columns:                           # add calculated columns, override title/format/meaning; cannot remove a key
  notionalM: { type: decimal, expr: "notional / 1000000", role: amount, unit: { currency: currency } }
security: { masked: [trader] }     # only adds (only tighter, as for a model)
```

`extends` is how an author customises a **pack-generated** dataset (which is read-only, section 7).

### 1.10 Full examples

The generated dataset of the trading pack's `trade` (what a person sees on its YAML tab; written by Drishti, not typed):

```yaml
rachana: 2
dataset: pack.trading.trade
version: 1.0.0                    # = the trading pack's version (section 7.3)
id: ds_pack_trading_trade
title: Trade
description: Trades in 125 products across rates, FX, credit, equity, commodities, inflation, fixed income, securities financing, money markets and structured products.
owner: pack:trading
endorsement: certified            # certified by the pack (section 7.4)
generated: { by: pack, pack: trading, packVersion: 1.0.0 }
source: { pack: trading, kind: trade }            # connector: whichever serves the kind (trading-store in the demo)
grain: { says: One row per trade per business date, keys: [tradeId], date: businessDate }
time: { mode: snapshot, as-of: business-date }    # the demo Delta connector does not push; with a pushing connector: live
refresh: { on-load: true }
columns:
  tradeId:          { type: string,  role: key, meaning: trade-id, id: c_tradeId }
  desk:             { type: string,  role: dimension, references: desk, id: c_desk }
  book:             { type: string,  role: dimension, references: book, id: c_book }
  nettingSet:       { type: string,  role: dimension, references: netting-set, id: c_nettingSet }
  counterpartyId:   { type: string,  path: counterparty.id, role: dimension, references: counterparty, id: c_counterpartyId }
  counterpartyName: { type: string,  path: counterparty.name, role: attribute, of: counterpartyId, id: c_counterpartyName }
  productType:      { type: string,  role: dimension, id: c_productType }
  productName:      { type: string,  role: attribute, of: productType, id: c_productName }
  direction:        { type: string,  role: dimension, meaning: direction, id: c_direction }
  assetClass:       { type: string,  role: dimension, id: c_assetClass }
  currency:         { type: string,  role: dimension, meaning: currency, id: c_currency }
  status:           { type: string,  role: dimension, id: c_status }
  notional:         { type: decimal, role: amount, meaning: notional, unit: { currency: currency }, additive: true, aggregate: sum, format: amount0, id: c_notional }
  mtm:              { type: decimal, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_mtm }
  pnl1d:            { type: decimal, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_pnl1d }
  dv01:             { type: decimal, path: risk.dv01, role: amount, unit: { currency: USD }, additive: true, aggregate: sum, format: signed0, id: c_dv01 }
  tradeDate:        { type: date, role: date, meaning: tradeDate, id: c_tradeDate }
  maturityDate:     { type: date, role: date, meaning: maturityDate, id: c_maturityDate }
security:
  masked: [counterpartyName]       # from drishti.security.redact and the pack's sensitive fields
ai:
  synonyms: { mtm: [mark to market], pnl1d: [daily P&L, one-day P&L] }
tests:
  - { name: One row per trade, unique: [tradeId] }
```

A user dataset over a connector query, and a Python dataset (the migrated `desk-pnl-vs-exposure-py`):

```yaml
rachana: 2
dataset: desk-limits
version: 1.0.0
title: Desk limits
owner: risk-control
source:
  connector: risk-warehouse
  query: "SELECT desk_id, limit_type, limit_amount, currency FROM limits WHERE as_of = :asOf"
  params: { asOf: as-of }
grain: { says: One row per desk and limit type per date, keys: [desk_id, limit_type] }
time: { mode: snapshot }
refresh: "business-days 06:30"
columns:
  desk_id:      { type: string, role: dimension, references: desk }
  limit_type:   { type: string, role: dimension }
  limit_amount: { type: decimal, role: amount, unit: { currency: currency }, additive: { over-time: last }, aggregate: sum, format: amount0 }
  currency:     { type: string, role: dimension, meaning: currency }
tests: [{ name: One limit per desk and type, unique: [desk_id, limit_type] }]
---
rachana: 2
dataset: pnl-vs-exposure-py
version: 1.0.0
title: Desk P&L vs counterparty exposure (Python)
source:
  python: pnl-vs-exposure
  inputs:
    pnl:      { model: trading-risk@1, select: { fields: [counterparties.id, counterparties.name], measures: ["P&L 1d", Trades] } }
    exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id, counterparties.sector], measures: [Exposure (EE peak), Exposure (PFE peak)] } }
  params: { floor: 1.0 }
grain: { says: One row per counterparty, keys: [counterparty] }
time: { mode: snapshot }
columns:
  counterparty:     { type: string,  role: key, references: counterparty }
  name:             { type: string,  role: attribute, of: counterparty }
  sector:           { type: string,  role: dimension }
  desk_pnl:         { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
  trades:           { type: integer, role: quantity, aggregate: sum }
  ee_peak:          { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
  pfe_peak:         { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
  pnl_per_exposure: { type: decimal, role: ratio, additive: false, aggregate: avg }
  exposure_rank:    { type: decimal, role: ratio, additive: false, aggregate: min }
```

(In the converted samples the Python transform's input renames, `as Desk P&L`, become column renames in the Transform
itself; the MQ `select` has no `as`, the answer's columns are the field and measure names.)

### 1.11 Dataset problems

| Code | Meaning |
|---|---|
| `DRS-9100` | structure: unknown key (warning), missing `source`, two sources, bad name, bad `version` (not semver) |
| `DRS-9101` | a column: unknown type, role, aggregate, additivity; `of:` naming no column; `references:` naming no kind |
| `DRS-9102` | a source the server cannot use: no such pack/kind/connector, a query on a connector without a SQL target, a parameter that is not one of the four dates |
| `DRS-9103` | a time mode the source cannot give (`live` on a connector that cannot push and has no resync; `live` on `each`; `history` with no history block) |
| `DRS-9104` | the grain: `keys` naming no column, or (test) a duplicate key |
| `DRS-9105` | `extends`: base not found, removes a key, or loosens security |
| `DRS-9106` | security: a rule naming another dataset's columns, a mask naming no column |
| `DRS-9107` | a generated dataset edited (it is read-only; use `extends`) |
| `DRS-9108` | a Python or model source: an input query that does not validate (the 915x code of the inner query is attached), or (when read) an answer without a declared column |
| `DRS-9109` | a model source that reaches its own model again (`model:a -> dataset:x -> model:a`): refused by Check, the bind and at run time |

---

## 2. The data model

A model puts datasets in a star and gives them measures. It never copies data: every dataset is read through its own
layers, with its own security, and the model adds relationships, measures, hierarchies and tighter security.

### 2.1 Top-level keys

| Key | Meaning |
|---|---|
| `rachana: 2`, `model`, `version` (semver), `id` | as for a dataset; `model` is the name (`trading-risk`) |
| `datasets` | the datasets it uses, each with an **alias** (the name fields use), a version range and a `role` (2.2) |
| `relationships` | 2.3 |
| `measures` | 2.4 |
| `calc-groups` | *(W6)* placeholder, parsed and kept, refused in W1 with `DRS-9129` if non-empty |
| `hierarchies` | as today (levels are model fields) |
| `parameters` | *(W6)* placeholder: what-if and field parameters, parsed, refused if non-empty in W1 (`DRS-9129`) |
| `calendars` | *(W7)* placeholder: the business calendar the model's dates use (default `drishti.business-date.calendar`) |
| `security` | model security, **only tighter** (2.6) |
| `contract` | what consumers may use, with stable ids and semver (2.8) |
| `ai` | model-level synonyms, descriptions, examples *(W5)* |
| `golden` | golden questions *(W5; parsed and run as tests from W1 when they carry `query:`)* (2.9) |
| `tests` | expected values over model measures (today's `tests:` form) |
| `rollups` | aggregate tables over one fact (today's rollups, per fact alias) |
| `lifecycle` | `draft`, `published`, `deprecated` (with `replaced-by:`), `retired` (2.8) |
| `layout` | where the designer draws each dataset |

### 2.2 Datasets and star roles

```yaml
datasets:
  trades:         { dataset: pack.trading.trade@^1, role: fact }
  netting_sets:   { dataset: pack.counterparty-risk.netting-set@^1, role: fact }
  counterparties: { dataset: pack.banking-core.counterparty@^1, role: dimension }
  desks:          { dataset: pack.banking-core.desk@^1, role: dimension }
  books:          { dataset: pack.banking-core.book@^1, role: dimension }
```

* The alias (`trades`) is what fields and expressions use (`trades.mtm`): letters, digits, underscore. The converter
  keeps today's table names as aliases, so `trades.desk` still means the same.
* `@^1` is a semver range (`@1.2.0` exact, `@^1` compatible with 1, `@~1.2`); without one, the latest published
  version is pinned on save (the designer writes the pin). A source dataset publishing a new **major** does not move the
  model until the model is edited (impact analysis lists it).
* `role`: `fact` (has measures, the many side), `dimension` (described, grouped by), `bridge` *(W2/R4: many-to-many
  resolution)*. The designer infers it (a dataset whose keys others reference is a dimension; one with amounts is a
  fact) and the author may change it. A dataset with no relationship to the others is an **island**: allowed with a
  warning (`DRS-9121`), it can still be used alone in a visual (Open question 3).

### 2.3 Relationships

```yaml
relationships:
  - { id: r_1, from: trades.desk,             to: desks.id,          cardinality: many-to-one }
  - { id: r_2, from: trades.counterpartyId,   to: counterparties.id, cardinality: many-to-one }
  - { id: r_3, from: netting_sets.counterparty, to: counterparties.id, cardinality: many-to-one }
  - { id: r_4, from: trades.nettingSet,       to: netting_sets.id,   cardinality: many-to-one, active: false }   # fact-to-fact: inactive by default
  - { id: r_5, from: trades.settleDate,       to: calendar.date,     cardinality: many-to-one, active: false, name: by settle date }   # (W7) role-playing
```

| Key | Meaning |
|---|---|
| `from`, `to` | the many side and the one side (as today); both fields of the model, same type |
| `cardinality` | `many-to-one` (default), `one-to-one`, `one-to-many` (stored reversed), `many-to-many` *(W2: through a `bridge`)* |
| `direction` | `single` (default: filters flow from the one side to the many side) or `both` *(W2/R4: semi-join)* |
| `active` | `true` (default) or `false`; an inactive relationship is used only by a measure that names it *(W7: `via:`)* |
| `suggested` | written by the designer for a relationship it proposed and the author accepted: `{ from: pack-reference \| values \| name, match: 0.98 }` (provenance) |

**Validation (W1).** Both fields exist and share a type (`DRS-9122`). The one side must be the dataset's **grain key**
(or a column the grain test proves unique): a relationship whose `to` is not unique is refused (`DRS-9123`, the
model-level fan-out refusal; *W2* adds the full refusal of any path that repeats rows). At most one **active** path
between any two datasets (`DRS-9124`, ambiguity: make one inactive). A fact-to-fact relationship is allowed but the
designer proposes it inactive and suggests the shared dimension instead.

### 2.4 Measures

```yaml
measures:
  MTM:            { id: m_mtm, expr: "sum(trades.mtm)", format: signed0, description: Mark-to-market in USD, signed. }
  Notional:       { id: m_notional, expr: "sum(trades.notional)", format: amount0, currency: trades.currency }
  Trades:         { id: m_trades, expr: "count(trades)" }
  Exposure (EE peak): { id: m_ee, expr: "sum(netting_sets.eePeak)", format: amount0, additive: { over-time: last } }
  MTM per 1m notional: { id: m_mtm1m, expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000", format: signed2 }
  P&L per exposure: { id: m_ratio, expr: "[P&L 1d] / [Exposure (EE peak)]", format: signed2 }    # across two facts: each at its grain (W2 rule, built today)
  Desk P&L volatility: { id: m_vol, python: desk-pnl-volatility, inputs: [trades.pnl1d], by: [trades.desk] }   # Python measure, as today
  MTM by settle date: { id: m_mtm_s, expr: "sum(trades.mtm)", via: r_5 }   # (W7) inactive relationship
```

* The **expression language is unchanged** (DATASETS.md section 4: aggregates, `[Measure]`, time intelligence,
  windows), with `table` meaning a dataset alias.
* `currency:` names the column (or ISO code) the measure's amounts are in when the column's `unit` is per row; the
  currency guard reads it (5.6). `additive:` defaults from the column (1.4); a measure of measures is non-additive
  unless it says so. These feed **true totals** (REPORTS.md section 3.2) instead of today's guess from the aggregate.
* **Default measures.** A model gets, without writing them, one measure per fact column whose `aggregate` is set
  (`Sum of notional`, `Count of trades`), marked `implicit: true`, hidden from the contract unless promoted. The
  designer writes them out when the author renames or formats one.

### 2.5 Calculation groups and parameters *(placeholders)*

```yaml
calc-groups:            # (W6) templates applied to any measure; refused if non-empty in W1
  Time:
    items: { YTD: "ytd(${measure}, trades.tradeDate)", Prior: "prior_period(${measure}, trades.tradeDate)" }
parameters:             # (W6) what-if and field parameters; refused if non-empty in W1
  shock: { kind: number, default: 0, min: -0.5, max: 0.5, step: 0.01 }
```

They are named now so the contract (2.8), the query language (`params:` in 5.2) and stable ids reserve their place.

### 2.6 Model security: only tighter

```yaml
security:
  rows:
    trades:                                          # per dataset alias: a rule names only that alias's columns
      - { role: desk-head, where: "trades.desk in user.desks" }
      - { role: [risk, analyst, admin], where: "true" }
  masked: [counterparties.rating]                    # adds masks; cannot unmask
  propagate: dimension-to-fact                       # the default and only W1 value
  may-see-all: { netting_sets: [desk-head] }         # the safeguard's explicit note (below): desk-heads see every netting set
```

* **Dataset rules first, always.** For each reader, every dataset in a query is filtered by **its own** rules and
  masked by its own masks before it is joined or aggregated (D: "security is applied per reader at the dataset").
* **Model rules are a second filter on the same dataset.** `rows.<alias>` has the dataset semantics (the reader's
  matching rules are ORed; a reader with no matching rule gets no rows of that alias) and the result is **ANDed** with
  the dataset's own rules, applied at the dataset before any join. AND can only remove rows, so a model is tighter by
  construction. A rule naming another alias's columns is `DRS-9125`, as is an `unmask` key or a mask list that tries
  to remove a dataset mask.
* **Propagation (dimension to fact).** A row rule on a **dimension** filters the facts that reference it along active
  many-to-one relationships (a fact row whose key points at a dimension row the reader may not see is not counted).
  A rule on a fact never filters a dimension or another fact. This replaces today's fail-closed refusal: today a
  desk-head's rule on `trades` made exposure by counterparty refused (DATASETS.md section 6); with security at the
  dataset, netting sets (which carry no rule) answer, and trades answer for the desk-head's desks only. This is a
  deliberate change of answer, listed in section 8.5.
* A row rule naming `user.*` attributes is evaluated on the server as today; the reader's rule set is part of the
  cache key as today.
* **The safeguard** (the product owner's condition for accepting security per dataset, Open question 1). A role is
  *restricted* in a model when a row rule of the model, or of one of its datasets, names it with a condition other than
  `true`. Every dataset of the model that **no row rule restricts at all** (neither its own nor the model's) is open to
  such a role, which then sees every one of its rows. **Check flags** each such dataset and role (`DRS-9130`, a warning,
  and the list `exposures` in the Check answer), and **the model cannot be published** (`409 DRS-9130`) until that dataset
  gets a rule (in the dataset, or under the model's `security.rows.<alias>`) **or** the model carries an explicit note
  `security.may-see-all: { <alias>: [<role>, ...] }`. The publish that relies on a note records it in the audit log
  (`bi-model-may-see-all`, naming the model version, the datasets and the roles). A dataset whose rules simply do not
  mention a role is not flagged: that role sees none of its rows (no matching rule, no rows). The sample of section 2.10
  therefore says `may-see-all` for `netting_sets`, `counterparties`, `desks` and `books` for `desk-head`.

### 2.7 Hierarchies, rollups, tests

Unchanged in form from DATASETS.md (sections 5, 7, 8.1); levels and `by:` use model fields; a rollup names its fact
(`table: trades`) and lives in the model (a rollup is an aggregate of the model's measures).

### 2.8 The contract: semver and stable ids

```yaml
version: 1.3.0
contract:
  exposes: all                 # all (default) | listed: only the fields and measures below may be used by consumers
  fields:   [desks.name, counterparties.sector, trades.currency, trades.tradeDate]
  measures: [MTM, Notional, Trades, Exposure (EE peak)]
  deprecated: { "MTM median": { since: 1.2.0, use: MTM } }
lifecycle: published
```

* Every column (dataset), measure, hierarchy and relationship has an **id**, written once; consumers **store ids**
  next to names (`{ field: desks.name, id: ds_desk/c_name }` in the saved YAML, written by the designer). A rename
  changes the name only; consumers keep working and show the new name (R3).
* **Breaking change** = a contract field or measure removed, its type changed, a measure's `expr` changed in meaning
  (as BI_GOVERNANCE.md section 6 "alters"), a relationship removed or made inactive, security loosened is impossible,
  a grain key changed. Publishing a breaking change needs a new **major** version (`DRS-9126` otherwise; W1 detects
  removed and renamed ids; W2 adds the full breaking-change detection and refusal). A minor adds; a patch changes
  descriptions, formats and `ai:`.
* Consumers bind to a **major** (`model: trading-risk@1`). A new major does not move them; impact lists them and the
  designer offers to move each (with a diff of what it uses).

### 2.9 `ai:` and golden questions *(W5)*

```yaml
ai:
  description: Trades, counterparties and netting-set exposure across three packs.
  synonyms: { "Exposure (EE peak)": [EE, expected exposure, exposure] }
golden:
  - question: "What is the MTM of the rates desk?"
    query: { select: { measures: [MTM] }, where: [{ field: desks.id, op: eq, value: DESK-RATES }] }
    expect: { value: 161766771, tolerance: 0.5% }
```

A golden question is a model query (5) plus an expected answer; from W1 it runs as a **test** (its query part); from W5
the AI must produce an equivalent query from the words.

### 2.10 Full example: `trading-risk` as a model

```yaml
rachana: 2
model: trading-risk
version: 1.0.0
id: md_trading_risk
title: Trading and counterparty risk
owner: risk-analytics
endorsement: promoted
sample: true
datasets:
  trades:         { dataset: pack.trading.trade@^1, role: fact }
  netting_sets:   { dataset: pack.counterparty-risk.netting-set@^1, role: fact }
  counterparties: { dataset: pack.banking-core.counterparty@^1, role: dimension }
  desks:          { dataset: pack.banking-core.desk@^1, role: dimension }
  books:          { dataset: pack.banking-core.book@^1, role: dimension }
relationships:
  - { from: trades.desk,               to: desks.id,          suggested: { from: pack-reference } }
  - { from: trades.book,               to: books.id,          suggested: { from: pack-reference } }
  - { from: trades.counterpartyId,     to: counterparties.id, suggested: { from: pack-reference } }
  - { from: netting_sets.counterparty, to: counterparties.id, suggested: { from: pack-reference } }
  - { from: trades.nettingSet,         to: netting_sets.id,   active: false }
measures:
  MTM:                 { expr: "sum(trades.mtm)", format: signed0 }
  Notional:            { expr: "sum(trades.notional)", format: amount0, currency: trades.currency }
  "P&L 1d":            { expr: "sum(trades.pnl1d)", format: signed0 }
  Trades:              { expr: "count(trades)" }
  Counterparties:      { expr: "distinct(trades.counterpartyId)" }
  MTM per 1m notional: { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000", format: signed2 }
  Worst trade MTM:     { expr: "min(trades.mtm)", format: signed0 }
  MTM median:          { expr: "percentile(trades.mtm, 0.5)", format: signed0 }
  MTM ytd:             { expr: "ytd(sum(trades.mtm), trades.tradeDate)", format: signed0 }
  MTM rank:            { expr: "rank(sum(trades.mtm))" }
  MTM 3-day average:   { expr: "moving_avg(sum(trades.mtm), 3, trades.tradeDate)", format: signed0 }
  Open MTM:            { expr: "sum(trades.mtm, trades.status == 'Live')", format: signed0 }
  Exposure (EE peak):  { expr: "sum(netting_sets.eePeak)",  format: amount0 }
  Exposure (PFE peak): { expr: "sum(netting_sets.pfePeak)", format: amount0 }
  Netting sets:        { expr: "count(netting_sets)" }
  P&L per exposure:    { expr: "[P&L 1d] / [Exposure (EE peak)]", format: signed2 }    # replaces the composed dataset's measure
hierarchies:
  Trade date:    [trades.tradeDate.year, trades.tradeDate.quarter, trades.tradeDate.month, trades.tradeDate]
  Desk and book: [desks.name, books.name]
  Counterparty:  [counterparties.sector, counterparties.name]
rollups:
  - { name: by_desk_currency, table: trades, by: [desk, currency, assetClass], measures: [MTM, Notional, "P&L 1d", Trades, MTM per 1m notional] }
security:
  rows:
    trades:
      - { role: desk-head, where: "trades.desk in user.desks" }
      - { role: [risk, analyst, admin], where: "true" }
  masked: [counterparties.name, counterparties.rating]     # trades.counterpartyName is masked by the pack dataset already
  may-see-all: { netting_sets: [desk-head], counterparties: [desk-head], desks: [desk-head], books: [desk-head] }   # the safeguard (2.6), audited on publish
tests:
  - { name: The book holds 750 trades, measure: Trades, expect: 750 }
```

The pack datasets are read-only (section 7), so the old file's row rule becomes a **model** rule on `trades` (only
tighter: ANDed with the pack dataset, which has none). An organisation that wants the same rule in every model over
trades writes it once in a dataset that `extends` the pack's (1.9) and uses that dataset instead.

### 2.11 Model problems

| Code | Meaning |
|---|---|
| `DRS-9120` | structure: bad name, version, alias; a dataset not found or not at the pinned version |
| `DRS-9121` | an island dataset (warning) |
| `DRS-9122` | a relationship naming no field, or fields of different types |
| `DRS-9123` | a relationship's one side is not unique (not the grain key and not proven unique): it would repeat rows |
| `DRS-9124` | two active paths between two datasets (ambiguous) |
| `DRS-9125` | model security looser than a dataset's |
| `DRS-9126` | a breaking change without a new major version |
| `DRS-9127` | a measure: today's `DRS-9011`/`9012`/`9013` conditions, reported with the model path |
| `DRS-9128` | a contract naming a field or measure the model lacks |
| `DRS-9129` | a W6/W7 placeholder (`calc-groups`, `parameters`, `via`) used before its wave |
| `DRS-9130` | the security safeguard (2.6): a dataset no row rule restricts, in a model where a role is restricted elsewhere, with no `may-see-all` note for that role (a warning in Check; the publish is refused) |

---

## 3. Implicit models

A consumer that names **one dataset** (`dataset: pack.trading.trade`) gets an **implicit model**. Rules:

1. **Made, not stored.** It is computed from the dataset when it is read: id `implicit:<dataset id>@<major>`, one
   dataset whose alias is the dataset's last name part with `-` turned into `_` (`pack.trading.trade` is aliased
   `trade`, `trades-basic` keeps its table alias `trade` through the converter), role `fact`.
2. **Measures:** the default measures of section 2.4 (one per column with an `aggregate`), plus `Count of <alias>`.
   Names are stable and documented: `Count of trade`, `Sum of notional`, `Average of utilisation`.
3. **Pack dimensions come along, one hop.** For a pack-generated dataset, every column with `references:` whose kind
   has a pack-generated dataset adds that dataset as a **dimension** with an active many-to-one relationship
   (`trade.desk → desk.id`). One hop only, never facts. This is what makes "MTM by desk name" work from a bare trade
   dataset without a model step (Open question 2).
4. **Security:** the datasets' own; nothing added.
5. **Visible and promotable.** Wherever a consumer shows its model (the designer's Fields pane header, the report's
   About drawer, lineage), an implicit model reads *Implicit model of Trade* with **Make it a model**: that writes the
   implicit model out as a draft `model` item (the YAML of section 2, with `implicit-from:` provenance) and rebinds the
   consumer to it in one undoable edit. A consumer's local measures (4.2) move into the new model on request.
6. **Contract:** an implicit model's version is its dataset's version; its contract is everything (`exposes: all`).
7. **Not allowed on an implicit model:** model security, relationships beyond rule 3, calculation groups. Wanting any of
   them is the moment to press *Make it a model*; the designer says so beside the control the author tried.

---

## 4. Consumers bind to one model

### 4.1 The binding key

Every consumer has exactly one `model:` (D2):

```yaml
model: trading-risk@1          # a model by name or catalogue id, with a major (the designer writes the pin)
# or
dataset: pack.trading.trade    # shorthand: the implicit model of this dataset (section 3)
```

| Consumer | Today binds to | Binds to (W1 for queries, W4 for the YAML) | Note |
|---|---|---|---|
| report | `dataset:` + per-visual `dataset:` + `links:` | `model:` (visual `dataset:` removed; `links:` only as 4.3) | every visual query is an MQ against the report's model |
| board | tiles name report visuals | `model:` on the board, checked: every tile's report is on that model (`DRS-9141`) | tiles from another owner's model are the 4.3 exception |
| notebook | `dataset:` + `extracts[].dataset` + fields | `model:`; each extract is an MQ with `shape: rows` (5.1) | Analyse slices are MQs too |
| alert | `source.type: visual \| dataset \| field \| tile` | `visual` (model via the report), `model` (`{ model, query }`, replaces `dataset`), `field`, `tile` | `query` is an MQ that must answer one number |
| subscription | report / page / visual + state | unchanged target; the model comes from the report | each recipient's render is MQs as that recipient |
| export, embed | report visual | unchanged; MQ underneath | |
| Excel / SQL feed *(W13)* | — | `model:` + MQ | |
| AI ask / explain, MCP *(W5)* | report's dataset, `QueryRequest` | `model:` + MQ only | 5.9 |
| Python dataset input | `derived.inputs[].dataset` + by/measures | `source.inputs.<name>` is an MQ | 1.2 C |

### 4.2 Local measures (allowed) and joins (never)

```yaml
model: trading-risk@1
measures:                                  # report-local measures: expressions over the MODEL's measures and fields
  Loss share: { expr: "ratio([Open MTM], [MTM])", format: pct }
  Big trades: { expr: "count(trades, trades.notional > 100000000)" }
```

* A local measure uses the model's measures and fields and the expression language; it **cannot** name a dataset the
  model lacks, declare a relationship, or change security (`DRS-9142`). It travels inside the MQ (`define:`, 5.1), so
  the engine handles it exactly like a model measure, as the reader.
* The designer marks local measures and offers *Move into the model* (a draft change of the model, reviewed like any
  model change) when the author owns the model or can propose to it.

### 4.3 The cross-owner exception: report links

A report may **link** a second model only when the two models have **different owners** (D2):

```yaml
model: trading-risk@1
also:
  - { model: treasury-liquidity@2 }            # validated: its owner differs from trading-risk's (DRS-9143 otherwise)
links:
  - fields: [counterparties.id, treasury-liquidity:counterparty.id]
pages:
  - id: main
    visuals:
      - { id: lcr, type: card, model: treasury-liquidity@2, wells: { values: [LCR] } }
```

Today's link semantics are kept (REPORTS.md section 16: a choice on a linked field reaches the other model's visuals;
no link, no reach). A visual still reads **one** model. When both models share an owner the check refuses the link and
the designer offers **Add these datasets to the model** instead.

### 4.4 Consumer problems

| Code | Meaning |
|---|---|
| `DRS-9140` | a consumer names no model, or two (`model:` and `dataset:`) |
| `DRS-9141` | a board tile, notebook extract or alert names a field or report on another model |
| `DRS-9142` | a local measure that names something outside the model, or declares a join |
| `DRS-9143` | a link between two models with the same owner (use one model) |
| `DRS-9144` | a consumer pinned to a model major that no longer exists (retired) |

---

## 5. The model query language v1

The **model query** (MQ) is the only request for data (D5). The UI's visuals, the API, Analyse, alerts, Python inputs,
AI and MCP all send it; the server validates it, compiles it to the engine and answers in one shape. It is **versioned**
(`mq: 1`) and changed only by a reviewed change to this section.

### 5.1 The object

YAML or JSON, the same keys:

```yaml
mq: 1
model: trading-risk@1               # name or catalogue id, @major or @exact; the implicit model: dataset:<id>
define:                             # optional local measures (4.2)
  Loss share: "ratio([Open MTM], [MTM])"
select:
  fields:   [counterparties.sector, trades.tradeDate.month]   # group by (names or stable ids)
  measures: [MTM, Exposure (EE peak), Loss share]
where:                              # row filters, ANDed
  - { field: trades.currency, op: in, values: [USD, EUR] }
  - { field: trades.tradeDate, op: relative, window: last-5-business-days }
  - { field: counterparties.id, op: top, n: 10, by: Exposure (EE peak), dir: desc }
having:                             # filters on measures, after aggregation
  - { measure: MTM, op: lt, value: 0 }
time:                               # one of
  as-of: 2026-10-09                 #   a business date (default: the model's as-of, 1.5)
  # window: { from: 2026-10-01, to: 2026-10-09 }     # a date range (snapshot), at most drishti.bi.query.max-days
  # window: { last: 1h }                             # history
  # live: true                                       # the current state of live datasets
sort:  [{ by: MTM, desc: true }]
limit: 100
totals: { levels: [[], [counterparties.sector]] }     # true totals per level (or: grand | subtotals)
drill:  { hierarchy: Counterparty, path: [Banking] }  # drill-down position: fields and eq filters follow
shape: grouped                      # grouped (default) | rows (no aggregation: an extract, "show the rows")
params: {}                          # (W6) what-if values; refused if non-empty in W1
options: { layer: auto, explain: false, format: json }   # auto | columns | rollup | direct; json | arrow
```

| Key | Rule |
|---|---|
| `mq` | `1` |
| `model` | resolved through the catalogue as the caller (they must be able to open it); `@1` picks the latest published 1.x |
| `define` | local measures; a name may not shadow a model measure |
| `select.fields` | model fields (`alias.column[.part]`) or stable ids (`ds_…/c_…`); a hierarchy level is a field |
| `select.measures` | model or `define` measures, by name or id; at least one field or measure |
| `where[].op` | today's `eq ne in not-in lt le gt ge between contains starts-with is-null not-null`, plus **`relative`** (`window:` a RelativeDates name: `last-N-days`, `last-N-business-days`, `this-month`, `previous-quarter`, …, computed on the server from the as-of date and the model's calendar) and **`top`** (`n`, `by` a measure, `dir`) |
| `having` | `{ measure, op, value \| values }`: today's "filter whose field is a measure", made explicit |
| `time` | exactly one of `as-of`, `window`, `live`; absent: the model's default (today: the latest business date with rows) |
| `totals` | `grand` (no fields), `subtotals` (every prefix of `fields`), or explicit `levels` (each a subset of `fields`). Only non-additive measures are computed per level; additive ones are summed by the client (REPORTS.md 3.2, now driven by declared additivity) |
| `drill` | a hierarchy of the model and the values clicked; the compiler replaces the hierarchy's field by the next level and adds `eq` filters |
| `shape: rows` | no measures; **one row per row** of the fields' **one** dataset (plus many-to-one attributes), duplicates kept (never `DISTINCT`: the sums of a rows extract equal the measures), with its security; cut at `bi.explore.max_rows`; used by Analyse extracts and *Show the rows* |
| `limit` | 1 to the server maximum; default 500 grouped, 10,000 rows |

### 5.2 The answer

```json
{ "mq": 1, "model": { "id": "md_trading_risk", "name": "trading-risk", "version": "1.0.0" },
  "columns": [ { "name": "counterparties.sector", "id": "ds_cpty/c_sector", "kind": "field", "type": "string", "masked": false },
               { "name": "MTM", "id": "m_mtm", "kind": "measure", "type": "decimal", "format": "signed0", "additive": true, "masked": false } ],
  "rows": [ ["Agriculture", 20378377.0] ],
  "totals": [ { "by": [], "rows": [[ "…" ]] } ],
  "meta": { "asOf": "2026-10-09", "dates": ["2026-10-09"], "layer": "columns", "rollup": null, "parts": 2,
            "tables": ["trades", "netting_sets", "counterparties"], "masked": [], "warnings": [],
            "cached": false, "millis": 41, "generation": "g-1873", "query": { "…": "the canonical MQ that ran" }, "sql": null } }
```

`meta.generation` is the data generation of every dataset read (the standing-query cursor, 5.8). Arrow keeps today's
`X-Bi-*` headers and adds `X-Bi-Model` and `X-Bi-Generation`.

### 5.3 Validation (before any data is read)

In order; the first failing step answers, with every problem of that step and its path:

1. **Shape** (`DRS-9150`): unknown key, wrong type, `mq` missing or not 1, two `time` forms.
2. **Model** (`DRS-9151`): not found, not readable by the caller, retired major, a draft the caller may not query.
3. **Names** (`DRS-9152`): a field, measure, hierarchy or id the model lacks; one outside the model's `contract`
   (`DRS-9153`, with the deprecation hint when it is deprecated).
4. **Local measures** (`DRS-9154`): parsed and checked as model measures (today's 9011 conditions).
5. **Filters** (`DRS-9155`): an operator not allowed for the type, a value of the wrong type (never cast into SQL), an
   unknown relative window, `top` without `n`/`by`, a `having` on a non-measure, a drill past the last level.
6. **Reachability** (`DRS-9156`): every field, filter and row rule must be reachable many-to-one from each measure's
   fact along active relationships (today's 9020 rule), so several facts meet only on shared dimensions.
7. **Time** (`DRS-9157`): a window over the limit, `live` with no live dataset, a history window on a model without a
   history, an as-of in the future.
8. **Correctness guards**: different currencies in one sum (`DRS-9158`, 5.6); a semi-additive measure across dates
   (`DRS-9159`: a date window, a measure `additive: { over-time: last }` and no date field: W1 answers the last date's
   value with a warning, W2 refuses unless asked for `last`); a path that repeats rows (`DRS-9160`, 5.5).
9. **Security** (`DRS-9161`; the HTTP answer keeps `403` and also names `DRS-9021`): a filter, sort or `top` on a field
   masked for the caller.
10. **Python** (`DRS-9162`): a Python measure in a grouped MQ (as today: a python visual runs it; R8 later).

A valid MQ can still be refused by the engine for limits (`DRS-9022` to `9025`) and source failures (`9026`, `9027`).
`POST /api/v1/bi/mq/validate` runs steps 1 to 9 without reading data (the designer and AI use it on every edit).

### 5.4 Compilation to the existing engine

W1 adds a **front end**, not a new engine. The MQ compiler flattens a model into today's in-memory `Dataset` record
(each model dataset becomes a `Table`, each relationship a `Relationship`, measures as they are, security merged per
table) and an MQ into one or more `QueryRequest`s, and calls today's `QueryService` and `SqlCompiler` where it can:

| Step | What happens | Existing code reused |
|---|---|---|
| 1 Resolve | the model at its pinned version; ids to names; `define` merged into the measure map | `Resolver`, `DatasetValidator` (expressions) |
| 2 Flatten | model → engine `Dataset`: a `Table` per dataset (source, `time.mode` → today's `mode`: snapshot → `cached` or `direct`, live → `live`, the history block → `Live.History`), columns, relationships, measures, hierarchies, rollups. Cached per model version | `dataset.Dataset` records |
| 3 Security | per reader: each table's rules = dataset rules **AND** model rules; masks = union; dimension rules propagated to facts as semi-joins (`fact.key IN (SELECT id FROM dim WHERE rule)`) | `SqlCompiler.plan`'s `Reader` (row rules, masks), changed so a rule binds to its own table: the old "join the rule's tables into every query" and its fail-closed refusal go |
| 4 Relative dates and top-N | `relative` windows from the as-of and the calendar; each `top` resolved by its own grouped request in the context of the other filters, becoming an `in` filter (`"\u0000none"` when empty) | `RelativeDates`, `VisualQueries.resolveTop` and its `TopValues` callback |
| 5 Drill | the hierarchy position becomes a field substitution and `eq` filters | `VisualQueries.drill` |
| 6 Per-grain plan | measures split into leaves per fact (`expandMixed`, `grains`, `factParts`); each fact part grouped at the query's fields | `SqlCompiler` grain splitting |
| 7 Meeting of facts | parts full-outer-joined on the shared fields (null equals null), then `having`, sort and limit | `SqlCompiler.compileMulti` |
| 8 Guards | fan-out (5.5), currency (5.6), semi-additive (5.3 step 8) | new, in the MQ compiler, before step 6 |
| 9 Layer | rollup, columns, live or direct, as today | `QueryService.route`, `Layers`, `TableRelations` |
| 10 Totals | one extra request per level for the non-additive measures | `Totals` (its regular-expression guess replaced by declared additivity) |
| 11 Answer | rows, totals, meta (adds `model`, `asOf`, `generation`, stable ids, the canonical MQ) | the result cache key gains the model version and the canonical MQ's hash |

`python` and `model` dataset sources run their inner MQs **as the reader** and stage the result as today's composed and
derived frames are staged (`QueryService.runFramed`, the Transform runner and its per-reader cache); the frame becomes a
table of the flattened dataset, so it can have relationships. The composed `from:` / `join:` machinery
(`dataset.Composition`) is retired in W4: a join is a relationship now.

### 5.5 Fan-out refusal

A row repeats when a query joins from a fact along a path whose far side is not unique. The model check refuses a
relationship whose `to` is not a grain key (`DRS-9123`), and the planner follows only many-to-one paths from a
measure's fact (today's rule). A field reachable only one-to-many from a measure's fact is `DRS-9160` with a fix (*"group
by a field both reach"*). `shape: rows` with fields of two facts is refused the same way. W2 extends the model check to
every path (bridges, `direction: both` as semi-joins).

### 5.6 The currency guard

A measure over a column with a **per-row currency** (`unit: { currency: <column> }`, or the measure's `currency:`) may
be summed only within one currency. The compiler adds `count(distinct <currency>)` per group to the same query and
flags (`DRS-9158`) any group that adds rows of several currencies, **unless** the currency column is a field of the
query, a `where` pins it to one value, or the measure is a conversion measure (`convert: { to: USD, rates: <dataset> }`,
W2). In W1 the flag is a **warning** in `meta.warnings` naming the groups (the designer shows it on the visual); in W2 it
refuses. Columns with a fixed currency (`unit: { currency: USD }`) are always addable. In the samples, `Notional`
(trade currency) is the measure the guard catches: *Notional by desk* warns, *Notional by desk and currency* does not.

### 5.7 Errors in the answer

```json
{ "status": 422, "code": "DRS-9156", "path": "select.fields[0]",
  "detail": "trades.tradeId cannot group Exposure (EE peak): netting_sets does not reach trades many-to-one. Group by a field both reach (counterparties.sector, counterparties.name) or ask MTM alone.",
  "fix": { "replace": "select.fields[0]", "with": ["counterparties.sector", "counterparties.name"] } }
```

Every refusal carries a **`fix`** where one exists (experience rule *feedback where you look*): the designer shows it
as a one-click change beside the control; the AI repair loop sends it back to the model once.

### 5.8 Standing queries

Every MQ may be **asked** (one answer) or **subscribed** (kept current):

```
POST   /api/v1/bi/mq/subscribe   { "query": { …MQ… }, "throttle": "1s", "deliver": "rows" | "diff" | "value" }
       → { "subscription": "sq_…", "answer": { … }, "cursor": "g-1873" }
GET    /api/v1/bi/mq/stream?ids=sq_a,sq_b          (SSE)
       event: answer  data: { "subscription": "sq_a", "cursor": "g-1874", "rows": [...] | "diff": { "upsert": [...], "delete": [...] }, "totals": … }
       event: status  data: { "subscription": "sq_a", "state": "live | stale | throttled | failed", "lag": "120ms" }
DELETE /api/v1/bi/mq/subscribe/sq_a
```

| Rule | Behaviour |
|---|---|
| Who | a subscription is evaluated **as its owner** every time; the stream carries that reader's answer only |
| When | it re-evaluates when a dataset it reads changes generation (a live batch, a load, a refresh) |
| Incremental, per measure *(W3)* | `sum`, `count` (and `avg` as a sum and a count): **by difference** from the changed rows; `min`, `max`, `distinct`: **recompute the touched groups**; a measure of measures: from its parts; percentile, windows, time intelligence, Python: **recompute on the throttled tick**. The rule of each measure is derived from its expression and shown in the model designer (*updates by difference / per group / on a tick*) |
| Throttle | `throttle` (default `drishti.bi.live.frame`, 250 ms; at least 100 ms): at most one answer per period; the latest state wins, nothing queues (today's rule) |
| Back-pressure | a client that does not read is skipped to the newest answer; over `drishti.bi.mq.max-subscriptions` is `DRS-9181`, over the per-user cap `DRS-9182`; a subscription whose evaluation overruns its throttle three times in a row moves to twice the throttle and says `throttled` |
| Same numbers | a subscribed answer equals the asked answer at the same cursor (W3's acceptance compares them after random update streams) |
| Time | `live` follows changes; `as-of` a past date does not change (subscribing to one is `DRS-9183`); `window: { last: 1h }` slides |

In W1 the **endpoints are built** and answer by full recompute on each generation change (today's "tick, then the
client asks again", moved to the server, which also removes one round trip per visual). The incremental rules are W3.
Alerts with `trigger: live` become standing queries with `deliver: value`; boards and live report visuals subscribe one
MQ per visual over one stream.

### 5.9 One language for every caller

| Caller | How it uses MQ |
|---|---|
| Report viewer and designer | the server turns a visual and the page state into an MQ (`VisualQueries` becomes `VisualMq`, keeping its order of authority); `POST /reports/{id}/query` keeps its body and returns the MQ it ran under `query` |
| Analyse | Slice and extracts send MQs (`shape: rows` for extracts); a notebook stores them; the console's `explore_routes.extract_for` posts an MQ instead of a dataset query |
| Alerts | `source.type: model` stores an MQ; `BiValueReader` runs it; `visual` sources run the visual's MQ |
| API | `POST /api/v1/bi/mq` (a full MQ), `POST /api/v1/bi/models/{id}/query` (an MQ without `model`), `POST /api/v1/bi/mq/validate`, `GET /api/v1/bi/models/{id}/describe` (fields, measures, hierarchies, types, formats, meanings, contract, `ai:`, as the caller: masked fields marked, hidden ones absent). `POST /datasets/{id}/query` with today's body is accepted until W4 and translated to an MQ on the implicit model |
| AI *(W5; W1 moves ask to MQ)* | the model is shown `describe` and writes an MQ; the server validates it (5.3), runs it as the user, and on a refusal sends back the code and the `fix` once; numbers always come from the engine |
| MCP *(W5)* | tools `describe_model(model)`, `query_model(model, mq)`, `explain_change(model, measure, from, to)`, all as the token's user |
| CLI | `drishti.py bi query <model> --mq file.yaml [--format csv]` |

---

## 6. Worked examples

All over the shipped samples as converted in section 8. The numbers quoted are real answers already documented in
DATASETS.md, BI_AI.md and REPORTS.md; the converted samples must give the same (10.3), except where 8.5 says otherwise.

**1. A card: trades in the book** (*trades-overview*, visual `trades`; the model `trades-basic`).

```yaml
{ mq: 1, model: trades-basic@1, select: { measures: [Trades] } }
```

One fact part, `count(*)` over `trade`; one row. Additive, so no totals request.

**2. MTM by desk with its rank, top 5** (DATASETS.md 4.3).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [trades.desk], measures: [MTM, MTM rank] }
sort: [{ by: MTM, desc: true }]
limit: 5
```

Answered from the rollup `by_desk_currency` (one fact, `desk` in its `by`, `MTM` a stored component, `rank` the one
window a rollup holds): `DESK-RATES 161,766,771 · 1`, `DESK-COMM 16,954,390 · 2`, `DESK-STRUCT 15,784,130 · 3` …

**3. Notional by trade date, the last 30 days** (a relative-date slicer).

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.tradeDate], measures: [Notional] }
where: [{ field: trade.tradeDate, op: relative, window: last-30-days }]
sort: [{ by: trade.tradeDate }]
time: { window: { from: 2026-09-09, to: 2026-10-08 } }
```

Step 4 turns the window into `trade.tradeDate >= 2026-09-09 and < 2026-10-09` (bound values). The currency guard warns
(`Notional` adds trade currencies); the designer offers *Split by currency* or *Filter one currency*.

**4. A matrix with true totals** (*trades-overview* page `products`: desk by currency).

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.desk, trade.currency], measures: [Trades, MTM, Average MTM per trade] }
totals: subtotals
```

`Trades` and `MTM` are additive (the client sums them); `Average MTM per trade` is a measure of measures, so the
server runs one request per level (`[]`, `[trade.desk]`) for it alone.

**5. Drill down a hierarchy** (*trades-overview* `by-desk`, a click on DESK-FX).

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.desk], measures: [MTM] }
drill: { hierarchy: Desk and book, path: [DESK-FX] }
```

Compiled as `fields: [trade.book]` with `trade.desk eq DESK-FX`.

**6. Two facts in one visual through a shared dimension** (DATASETS.md 3.1).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [counterparties.sector], measures: [MTM, Trades, Exposure (EE peak), Netting sets] }
sort: [{ by: counterparties.sector }]
limit: 4
```

Two fact parts (`trades`, `netting_sets`), each grouped by the sector through its own many-to-one path, full-outer-joined
on it: `Agriculture 20,378,377 · 35 · 59,019,457 · 4`, `Airlines 15,984,208 · 33 · 73,868,165 · 4`, `Asset management
70,776,928 · 153 · 289,738,661 · 15`, `Banking 23,279,594 · 94 · 240,271,956 · 12`. As the desk-head `hana` the trades
part is cut to her desks **before** grouping and the exposure part answers in full (today: refused; see 8.5).

**7. The ten largest counterparties, ranked by an unmasked key** (*counterparty-risk* `by-counterparty`).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [counterparties.name], measures: [Exposure (EE peak), Exposure (PFE peak)] }
where: [{ field: counterparties.id, op: top, n: 10, by: Exposure (EE peak), dir: desc }]
```

Step 4 asks `{fields: [counterparties.id], measures: [Exposure (EE peak)], sort desc, limit 10}` as the reader, then
filters `counterparties.id in (…)`. For `ann` (no raw power) the name reads `•••`; ranking by the name instead is
`DRS-9161` with `fix: { by: counterparties.id }`.

**8. P&L against exposure in one model** (replaces the composed `desk-pnl-vs-exposure` and its two-dataset report).

```yaml
mq: 1
model: trading-risk@1
select: { fields: [counterparties.id, counterparties.name], measures: ["P&L 1d", Trades, Exposure (EE peak), P&L per exposure] }
having: [{ measure: Trades, op: gt, value: 0 }]
```

`P&L per exposure` is `[P&L 1d] / [Exposure (EE peak)]`, each side at its own grain, then divided. As `rita`: 18
counterparties, P&L 2,671,113 and EE 1,447,544,943 in total (DATASETS.md 11.3). The `having` keeps the old left join's
meaning (only counterparties with trades the reader sees), so `hana` gets 17 counterparties, P&L 1,060,102 and EE
1,356,300,321, as today. Without it she would see exposure for all 18 (8.5).

**9. A live desk, subscribed** (*live-trading-desk* `by-desk`).

```yaml
mq: 1
model: live-trading@1
select: { fields: [trade.desk], measures: [Trades, Notional, MTM, Worst trade] }
time: { live: true }
```

Subscribed with `throttle: 250ms`. W1 recomputes it on each generation from the live rollup `byDeskBook` (layer `live`;
the 90 trades of `DESK-COMM` from kept groups, as documented). From W3 `Trades`, `Notional`, `MTM` move by difference and
`Worst trade` per touched group.

**10. The last hour, and a measure as of a past date** (*live-trading-desk* `last-hour`; *counterparty-risk* `mtm-ytd`).

```yaml
- { mq: 1, model: live-trading@1, select: { fields: [history.ts], measures: [MTM history, Losses history] }, time: { window: { last: 1h } }, sort: [{ by: history.ts, desc: true }], limit: 5 }
- { mq: 1, model: trading-risk@1, select: { measures: [MTM ytd] }, time: { as-of: 2026-09-30 } }
```

The first reads the live dataset's sampled history (`history.ts`, one column per sampled measure; DATASETS.md 7.1); a
reader with a row rule on `trade` is refused as today (`DRS-9156`: `history` does not reach `trade`). The second binds
`ytd` to 2026-01-01 … 2026-09-30 and reads the snapshot of 30 September.

**A refusal: fan-out.** `{ mq: 1, model: trading-risk@1, select: { fields: [trades.tradeId], measures: [Exposure (EE peak)] } }`
is `422 DRS-9156` with the message and `fix` of 5.7 (today: `DRS-9020`, no fix).

---

## 7. Pack-generated datasets (D3)

### 7.1 What a pack kind gives, automatically

For **every kind of every enabled pack** that a connector serves, the server keeps one generated dataset. Nothing is
written to `config/bi`: the datasets are derived when packs load (and reload), held in the catalogue as read-only
items, and regenerated when the pack, its connector layout or its vocabulary changes.

| Part of the dataset | Comes from (what exists today) |
|---|---|
| name, id | `pack.<pack>.<kind>`, `ds_pack_<pack>_<kind>` |
| title, description | the kind's mnemonic label (`mnemonics.<M>.label`) and the pack's description, else the kind's name in words |
| source | `{ pack, kind }`; the connector is the pack's route for the kind (`Pack.routes()`), else whichever serves it |
| columns | the connector's **promoted columns** for the kind (`layout.<kind>.columns`, `SourcePlugin.columnar`), else the paths of the pack's sample documents; types by sampling as `PackModels.typeOf` does today. Nested paths get camel-case names (`counterparty.id` → `counterpartyId`, the converter's and today's wizard's rule) |
| roles | `id`-typed and `references` columns are `dimension`; decimals are `amount`; dates `date`; the query-form field types (`amount`, `enum`, `id`, `date`) refine it where the pack has a query form |
| `references` | `graph.fields` of the pack and of every loaded pack (`ReferenceCatalog.kindOfField`): `desk → desk`, `counterparty.id → counterparty` |
| `meaning`, `unit`, sign | the pack's `about.yaml` vocabulary (`term`, `means`, `unit`, `sign`) for a column whose name or path is a vocabulary entry; `unit: trade currency` becomes `unit: { currency: currency }` when the kind has a `currency` column |
| `format` | `graph.impact.formats` and the pack's `formats.yaml` |
| grain | one row per entity id per business date (`keys: [id]`, plus a declared business key such as `tradeId` when its values equal the id on the sample) |
| time | `live` when the kind's connector can push (`subscribe`), else `snapshot`; `refresh: { on-load: true }` |
| security | the kind's entitlements (who may open the pack and kind); masks from `drishti.security.redact` and any field the pack marks sensitive |
| arrays | one extra dataset per array field the pack lists under a new optional `bi.arrays` key (`pack.trading.trade.legs`, `pack.trading.trade.schedule`), each with `each:` and a `references: trade` back to the parent |
| tests | the grain test |

A pack may refine the result with an **optional** `bi:` block in `pack.yaml` (no pack needs one):

```yaml
bi:
  datasets:
    trade:
      grain: { keys: [tradeId] }
      columns: { mtm: { unit: { currency: USD } }, notional: { additive: true } }
      exclude: [internalNote]
      time: snapshot                 # force a mode
  arrays: { trade: [legs, schedule] }
  skip: [desk-pnl]                   # kinds that should not get a dataset
```

### 7.2 Naming

* `pack.<pack>.<kind>` and `pack.<pack>.<kind>.<array>`; the pack's code is not used (names must read well in YAML).
* Aliases in models default to the kind (`trade`, `netting_set`, `counterparty`); the designer pluralises on request.
* A user dataset may not start with `pack.` (`DRS-9100`).

### 7.3 Versioning with the pack

* The dataset's `version` **is the pack's version** (`trading 1.0.0` → `pack.trading.trade@1.0.0`).
* On a pack upgrade the server compares the old and new generated datasets with the contract rules of 2.8: a removed or
  retyped column in a pack **minor** or **patch** is a pack defect; the pack loader warns on Health and in Admin → Packs,
  and impact analysis lists every model and consumer that uses the column, before the upgrade is activated (Admin →
  Packs shows *This upgrade breaks 2 models*).
* Models pin `@^<major>`; a pack major moves no model until it is edited.

### 7.4 Governance

* **Owner** `pack:<pack>`; **endorsement** `certified` (by the pack), revoked if the pack is unsigned or edited locally.
* **Read-only** in every UI (`DRS-9107` for an edit through the API); customise by `extends` (1.9).
* Lineage shows a generated dataset as the root under the pack and its connector; Admin → Packs lists the datasets of
  each pack and the models that use them.
* Disabling a pack or a kind hides its datasets; models that use them show *dataset unavailable* and their queries
  answer `DRS-9151` naming the pack.
* A pack's own BI content (`PackBi`: reports and datasets a pack ships) is imported as today; its datasets become
  models over the pack's generated datasets when the pack is rebuilt with the converter (8.3).

---

## 8. Migration

### 8.1 Concept map

| Today (2.1) | From W1/W4 | How |
|---|---|---|
| dataset YAML with `tables:` (the model of today) | a **model** + one **dataset** per table (pack-generated when the table is a plain pack kind, else a user dataset) | D1: "today's dataset YAML becomes a data model" |
| a table `source: { connector, kind }` with the columns of the pack | `pack.<pack>.<kind>` (generated); a table with extra calculated columns or a narrower column list → a user dataset `extends` it | 7 |
| a table over a connector with a column set the pack does not promote | a user dataset `source: { pack, kind, connector }` with its own columns | 1.2 A |
| `each:` tables | `pack.<pack>.<kind>.<array>` datasets (the pack lists the array) or user datasets with `each` | 7.1 |
| `mode: cached / direct / live` | `time.mode: snapshot` (`storage: direct`) / `live` | 1.5 |
| `live.history` | `time.history` on the live dataset | 1.5 |
| `measures`, `hierarchies`, `rollups`, `tests` | in the model, unchanged | 2 |
| `relationships` | in the model, with `suggested:` provenance when a pack reference matches | 2.3 |
| `security.rows` / `masked` | model `security.rows.<alias>` (tighter than the pack dataset); masks of the pack's redact list stay in the dataset, others go to the model | 2.6 |
| `refresh:` (dataset-wide or per table) | per dataset `refresh:`; a model has none (it reads its datasets' layers) | 1.5 |
| composed dataset (`from:` + `join:`) | the sources' datasets **in one model** with relationships to shared dimensions; the composed measures become model measures; a left join's "only rows of the left source" becomes a `having` in the consumers that need it | 6 example 8 |
| derived dataset (`derived:` + Python Transform) | a **Python dataset** (`source.python`, inputs are MQs) used by a model (implicit or explicit) | 1.2 C |
| report `dataset:` | `model:` | 4.1 |
| visual `dataset:` + `links:` | removed when both datasets end in one model; kept as 4.3 only across owners | 4.3 |
| board (reports' tiles) | `model:` on the board | 4.1 |
| notebook `dataset:` + extracts | `model:` + MQ extracts | 4.1 |
| alert `type: dataset` | `type: model` with an MQ | 4.1 |
| `POST /datasets/{id}/query` | `POST /models/{id}/query` (MQ); the old route translates until W4 | 5.9 |
| Python measure (`python:`, `inputs`, `by`) | unchanged, in the model | 2.4 |
| Python item `inputs: [datasets]` | `inputs: [<name>]` naming the Python dataset's MQ inputs | 1.2 C |

### 8.2 Every shipped sample

*As built (2.2 wave A): the samples are converted by the converter itself (8.3.1) and committed under `config/bi/models` and `config/bi/datasets`.
Where it differs from this plan: the datasets are user datasets `<old>-<table>` (not pack datasets), the derived sample is `desk-pnl-vs-exposure-py-result`
plus the model `desk-pnl-vs-exposure-py`, the composed sample's `Collateral` measure joins `trading-risk` too, the two live reports stay on their live
datasets until W3, the boards are unchanged (they name reports), and the notebook and the Transform's `inputs:` are manual steps for the console.*

| File (config/bi) | Becomes |
|---|---|
| `datasets/trades-basic` | model `trades-basic` over `pack.trading.trade` (alias `trade`); the calculated column `mtmInMillions` moves to a user dataset `trades-basic-trade` (`extends: pack.trading.trade@^1`); measures (with the Python measure), hierarchy as written |
| `datasets/trading-risk` | model `trading-risk` (2.10) over five pack datasets; the desk-head rule becomes a model rule on `trades`; masks `counterparties.name`, `counterparties.rating` in the model; `counterpartyName` masked by the pack dataset; rollup kept; `trades.nettingSet → netting_sets.id` kept but **inactive** (two facts meet on `counterparties`) |
| `datasets/trading-cashflows` | model `trading-cashflows` over `pack.trading.trade`, `pack.trading.trade.legs`, `pack.trading.trade.schedule` (aliases `trades`, `legs`, `cashflows`; the column `index_name` stays a renamed path in a user dataset because `index` is reserved); relationships as written |
| `datasets/live-trading` | dataset `live-trading-trade` (`source: { pack: trading, kind: trade }`, `time: { mode: live, live: { rows: 5000 }, history: { every: 10s, keep: 360, measures: [MTM, Losses, Trades] } }`, masks `trader`); model `live-trading` with the measures, hierarchy and the live rollup |
| `datasets/live-exposure` | dataset `live-exposure-netting` (live) + model `live-exposure` |
| `datasets/counterparty-exposure` | model `counterparty-exposure` over `pack.counterparty-risk.netting-set` and `pack.banking-core.counterparty` (kept as a separate model with another owner, so the cross-owner link of 4.3 has a sample) |
| `datasets/desk-pnl-vs-exposure` (composed) | **removed**; its measure `P&L per exposure` moves into `trading-risk` (2.10). Its question is example 8 |
| `datasets/desk-pnl-vs-exposure-py` (derived) | Python dataset `pnl-vs-exposure-py` (1.10) and its implicit model; the Transform's `inputs:` become the dataset's MQ inputs |
| `datasets/world-economics`, `region-economics` | models over `pack.economics.economy` and `pack.economics.region-economy` (aliases `economies`, `regions`); `Average growth` and the other averages are marked `additive: false` |
| `reports/trades-overview`, `cashflow-ladder`, `python-desk-risk`, `world-economics`, `region-economics`, `live-trading-desk`, `live-exposure`, `counterparty-risk` | `dataset:` → `model:` of the same name at `@1`; fields unchanged because aliases keep today's table names |
| `reports/pnl-vs-exposure` | `model: trading-risk@1`; `joined.x` fields → `counterparties.x`; `Desk P&L` → `P&L 1d` (title kept); the two visuals with `dataset: counterparty-exposure` read the same model; `links:` removed; every visual gets `having: Trades > 0` as a page filter on a measure, to keep the left-join meaning (example 8) |
| `boards/desk-wall` | one board over two models (`live-trading`, `live-exposure`): converted with a new model `live-desk` containing both live datasets as facts (an island pair, `DRS-9121` warning) and the two reports rebound to it (Open question 3) |
| `boards/python-wall` | `model: trades-basic@1` (its report's) |
| `notebooks/pnl-vs-exposure-pandas` | `model: trading-risk@1`; extracts `{ name, query: { shape: rows, select: { fields: [...] } } }`: the trades fields and the netting-set fields as two row extracts of one model |
| `python/pnl-vs-exposure` | `inputs: [pnl, exposure]` (the Python dataset's input names); the function's renames of `Desk P&L` follow the MQ's column names (`P&L 1d`) |
| `python/desk-pnl-volatility`, `pnl-histogram`, `pnl-vega` | unchanged |
| tutorials 10 to 18 and their data | rewritten in W4 against the converted samples (UI first, then CLI and YAML) |

### 8.3 The converter

`drishti.py bi migrate [--dir config/bi] [--out DIR] [--dry-run] [--items workspace|all]` and
`POST /api/v1/bi/migrate/preview` (bi-admin). Behaviour:

1. **Reads** every `rachana: 1` item (shipped files and stored items, published and draft), in dependency order
   (composed and derived after their sources).
2. **Plans** per item: which generated datasets match each table (same pack kind and every declared column is a
   generated column with the same path and type → use the pack dataset; otherwise write a user dataset, `extends` when
   the difference is only additions); the model; the consumers' rewrites. Names: the model keeps the old dataset's
   name (so `trading-risk` stays `trading-risk`), user datasets are `<old>-<table>`.
3. **Checks** every output with the validators of sections 1, 2 and 4 and runs the **equivalence probe** (8.6) when a
   server is reachable.
4. **Writes** a plan report (`migrate-report.md` in `--out`, and the same as JSON from the API): every item, what it
   became, every name change, every behaviour difference (8.5) and every manual step. `--dry-run` stops here.
5. **Applies**: shipped files are written to `--out` (default: in place, the old files removed; Git shows the diff);
   stored items get a new saved state with origin `migrate`, published items are republished as the next version by
   the bi-admin running it (audited `bi-migrated`), drafts stay drafts. Stable ids are generated and written.
6. **Idempotent**: a `rachana: 2` item is skipped; a second run changes nothing. Comments in old YAML files are kept
   where the key survives (the converter edits a YAML tree that keeps comments), and dropped with the key otherwise.
7. Refuses (`DRS-9190`) an item whose old form is invalid, and (`DRS-9191`) a composed join it cannot express as
   relationships (a join on non-key columns of both sides): the report lists it with the Python-dataset alternative.

#### 8.3.1 As built (2.2 wave A, task 6)

The converter is `com.ash.drishti.server.bi.migrate.ModelConverter` (pure; `DatasetSplitter`, `Folding`, `ReportRewrite`), reached three ways:

| Way | What it converts | Security of the restricted roles (below) |
|---|---|---|
| `drishti.py bi migrate --models DIR [--dry-run] [--out D] [--keep-old] [--report F]` (`POST /api/v1/bi/migrate/models/convert`, `bi`) | a BI folder's files; written in place (old dataset files removed unless `--keep-old`) or under `--out` | `NOTE` |
| `drishti.py bi migrate --models [--dry-run]` (`POST /api/v1/bi/migrate/models`, `bi-admin`) | the server's stored items | `CLOSED` |
| the start-up run (`drishti.bi.migrate.on-start`, after the expression migration) | the server's stored items, as `system` | `CLOSED` |

Decisions taken while building it, against the plan above:

* **Datasets are user datasets, never a pack dataset.** Every table becomes `<old>-<table>` (`_` written `-`) with the table's own `source`
  (`connector`, `kind`, `each`), columns (`path`, `type`, `format`, `description`, calculated `expr` with bare columns, `key: true` as `role: key`),
  time (`cached` is the default snapshot, `direct` is `storage: direct`, `live` with `live.rows/age` and `history`), refresh and masks. Step 2's "use
  the pack dataset when every column matches" would give a model every column of the pack dataset, including columns the old file never showed; the
  converter never widens what a model shows, so a site that wants the pack datasets rebinds by hand (`dataset: pack.trading.trade`).
* **Security moves to the datasets.** A rule naming one table is that dataset's rule (its columns bare, `desk in user.desks`); a `True` rule is given to
  every dataset, so a role the old file did not name still sees nothing; a rule naming two tables is refused (`DRS-9190`). A role restricted on one
  table and named nowhere else (the desk-head on `netting_sets`) asked a question 2.1 **refused**. `NOTE` (the shipped samples, section 8.2) gives it
  every row of the other datasets and writes `security.may-see-all` (the allow-listed change of 8.5); `CLOSED` (stored items, converted unattended)
  gives it `{ role: desk-head, where: "False" }` there, so it keeps seeing nothing, and the report says how to open it. Nothing is widened silently.
* **Two facts joined by a relationship** (`trades.nettingSet -> netting_sets.id`): kept **inactive**, so the facts meet on their shared dimensions.
* **Composed** datasets fold into a *host*: a source whose model already reads every table the other sources use (same connector, kind, `each`, and
  at least their columns with the same paths and types). The composed dataset's own measures (not the engine's generated frame measures) and any source
  measure the host lacks move into the host (renamed `<name> (<source>)` when the name means something else there); consumers bind to the host with the
  frame's names renamed (`joined.name` to `counterparties.name`, `Desk P&L` to `P&L 1d`); a visual that read another source of the composed dataset reads
  the host too, so the report's `links:` go. A `left` (or `inner`) join adds the report filter `{ field: <left count measure>, op: gt, value: 0 }`.
  No host: `DRS-9191`.
* **Derived** datasets become a Python dataset `<old>-result` (`source.python`, each input a model query `{ model, select }` on the converted input's
  model) and a model of the old name over it under the alias `result`, so its measures and `result.*` fields keep their meaning. The Transform now
  receives each column named by the last part of its field or measure name (`counterparties.id` is `id`, `P&L 1d`), not the `as` names: a manual step (the shipped `pnl-vs-exposure` accepts both).
* **Reports** are rewritten in their text (comments kept): `rachana: 2`, `dataset:` to `model: <name>@<major>`, renames in wells, filters, sorts,
  formats and bookmarks (never in titles or texts). A report on a dataset with a **live** table stays on the dataset (manual step) until a report on a
  model follows live data (standing queries, W3), and that dataset's file is kept. **Boards** name reports and are unchanged; **notebooks** and Python
  Transforms that name datasets are manual steps until the console reads them from models.
* **Stored items**: the dataset item **becomes** the model item (same id, so references by id hold; its 2.1 text is a saved state of its history); each
  new dataset is an item of the same workspace, owner and audience, published when the old dataset was; reports are rebound through the same rewrite as
  the expression migration (text before kept, a published report published again). A dataset whose draft is ahead of its published version, a name already
  taken, or a refused item is never changed and is listed (Admin, BI settings; `GET /bi/migrate/problems`, step `models`).
* **Idempotent**: a `rachana: 2` item is skipped; a converted folder converted again writes the same text.

Captured (`drishti.py bi migrate --models config/bi --dry-run`, the shipped samples; the report's table, abridged):

```
datasets/desk-pnl-vs-exposure.dataset.yaml: removed -> folded into the model trading-risk@1
datasets/counterparty-exposure.dataset.yaml: converted -> model counterparty-exposure@1 over [counterparty-exposure-netting-sets, counterparty-exposure-counterparties]
    writes datasets/counterparty-exposure-netting-sets.dataset.yaml
    writes datasets/counterparty-exposure-counterparties.dataset.yaml
    writes models/counterparty-exposure.model.yaml
...
reports/live-trading-desk.report.yaml: manual
    manual: the report reads the live dataset live-trading (now also the model live-trading@1): it stays on the dataset until a report on a model follows live data (standing queries, wave W3); then bind it with model: live-trading@1
reports/pnl-vs-exposure.report.yaml: converted -> trading-risk@1
...
26 file(s) read, 16 converted, 0 refused; would write 32 file(s)
```

**Equivalence (8.6) as built:** `ConvertedSamplesTest` converts the 2.1 samples (kept under `drishti-server/src/test/resources/bi/samples-2.1`) and
asks every converted model what the 2.1 dataset was asked (each measure alone and by each hierarchy's first level, sorted, as of a date, with a measure
filter and a field filter) as `rita`, `hana` (desk-head) and `ann` (no raw power: masked): row for row equal, with one allow-list entry (8.5: a
desk-head's question about another fact is answered as risk sees it instead of refused). The composed sample is compared with the left join of its two
sources' 2.1 answers, asked of the host with `Trades > 0`; the derived sample is a Python dataset, which only the console runs. The test also asserts that
the files under `config/bi` are exactly the converter's output.

### 8.4 The W1 compatibility reader

*Removed in 2.2 (wave A, task 6), with `dataset/Composition`, the composed and derived datasets' code in `FrameService` and the phase 0 proof of
concept: a `rachana: 1` dataset is no longer read as a model (`DRS-9151` names the converter), a `from:` or `derived:` dataset parses with `DRS-9190` (its text
is still read, for the converter), and `POST /datasets/{id}/query` answers `410` with the equivalent model query. A 2.1 dataset with a live table keeps
serving its live reports through the engine until W3. What follows is the design as it was.*

So that W1 can be accepted before W4 migrates the files, a `rachana: 1` dataset with `tables:` is **read as a model**
whose datasets are inline (one anonymous dataset per table, under the table's name) and a composed or derived
`rachana: 1` dataset as an implicit model of one dataset. Reports, alerts and Analyse then ask it through MQ. The reader
is deleted in W4 with the old shapes.

**Security in the compatibility reader is not split** (decided with the safeguard of 2.6): the old file's rules keep their
version-1 meaning (joined into every query, refusing what they cannot reach), and the query runs through the same engine
path as the old dataset query, so an old file answers **exactly** as before through either path (the equivalence test
asserts it for every shipped sample, as `rita`, `hana` and `ann`). Binding the rules per dataset changes answers (8.5) and
needs the safeguard's review of every open dataset, so it happens when the converter (M1/W4) writes the model, never
silently when an old file is read.

### 8.5 What breaks (on purpose)

| Change | Who notices | Mitigation |
|---|---|---|
| Security binds to the dataset: a row rule on one fact no longer refuses queries about another fact (desk-head and exposure) | a desk-head sees exposure for every counterparty where today the question was refused (`trading-risk`) or limited by a left join (composed sample) | the converter adds `having: Trades > 0` where the composed sample relied on the join; a model that must hide another fact adds its own rule on it |
| Composed datasets are gone; `joined.*` fields are renamed | reports, alerts, bookmarks over `desk-pnl-vs-exposure` | the converter rewrites them; stable ids prevent it next time |
| `POST /datasets/{id}/query` body | API scripts | translated until W4, then `410` with the MQ equivalent in the message |
| visual `dataset:` and same-owner `links:` | reports over two datasets | the converter merges into one model |
| dataset-level measures | nobody downstream (they move to the model of the same name) | |
| a user dataset name starting `pack.` | none shipped | rename |
| `rachana: 1` files after W4 | sites with their own YAML | the converter, run once |
| Totals of measures declared `additive: false` that the regular expression considered additive (and the reverse) | matrices | they become right; the equivalence test lists each changed cell |

### 8.6 Equivalence

For every sample report visual, every dataset test and the sample alerts and extracts, the old path (2.1
`QueryRequest` through the old dataset) and the new path (MQ through the converted model) are asked as `rita` (risk),
`hana` (desk-head), `ann` (analyst without raw) on the 10K-trade demo lake; rows are compared exactly for counts and
strings and within 1e-9 relative for decimals; the differences of 8.5 are an explicit allow-list in the test, each with
its reason.

---

## 9. UX flows

Per the experience rules (BI_MODEL_ARCHITECTURE.md section 3): no dead ends, no YAML typing, direct manipulation,
never lose work, feedback beside the control, right defaults, phone width and keyboard. Wireframes are text; `[ ]` is a
button, `( )` a choice, `▸` a next step offered.

### 9.1 Start: what do you want to see?

```
BI home  ─────────────────────────────────────────────────────────────
  Start from your data
  ┌ Trades (Trading pack)            certified · live        [Use] ┐
  │ Netting sets (Counterparty risk) certified · snapshot    [Use] │
  │ Counterparties (Banking core)    certified · snapshot    [Use] │
  └ … search all datasets ________                               ┘
  Or from a model:  Trading and counterparty risk · promoted   [Use]
  ▸ New dataset (connector query or Python)    ▸ New model
```

*Use* on a dataset opens a new report on its implicit model (9.4); on a model, the report designer on it. Pack datasets
are listed first because they exist without anyone building them (D3).

### 9.2 The dataset wizard (mostly automatic)

Only for what a pack does not already give: a connector query, a Python table, another model's output, or a narrowed
pack dataset.

```
New dataset ─ Step 1 of 3: Where does the table come from?
  ( ) A pack kind, narrowed or with extra columns   → choose: [Trades ▾]
  ( ) A query in a SQL connector                     → choose: [risk-warehouse ▾]   (only SQL connectors listed; others greyed with the reason)
  ( ) A Python Transform                             → choose an item [▾] or [Write one]  (opens the Python editor, returns here)
  ( ) The answer of a model                          → choose a model [▾], then pick fields and measures (the report field picker)
                                                                       [Next ▸]
Step 2 of 3: Check the table   (filled in for you: run on a sample as you, masks applied)
  Column        Type      Role         Meaning (from the pack)     Unit
  desk_id       string    dimension ▾  Desk ▾  (references desk)   —
  limit_amount  decimal   amount ▾     —                           [currency ▾]
  Grain: one row per  [desk_id ✕] [limit_type ✕]   ✔ unique on 2026-10-09 (412 rows)
  Time: (•) snapshot ( ) history      Refresh: [business days ▾] at [06:30]
  ⚠ limit_amount looks semi-additive (a balance): [Treat as a balance] [It adds over time]
                                                                       [◂ Back] [Next ▸]
Step 3 of 3: Save
  Name [desk-limits]  Title [Desk limits]  Workspace [Risk control ▾]
  Who may see which rows: [+ Add a row rule]   Masked columns: [+]
  ▸ The YAML (for review)          [Save draft]  [Save and publish]
  After saving: ▸ Use it in a new model   ▸ Add it to model "Trading and counterparty risk"   ▸ Start a report on it
```

Every step keeps its draft in the session (refresh, Back) and asks before leaving. The grain is checked as soon as keys
are chosen; a duplicate shows the offending key values beside the chips with *Add a key* or *Make it a Python dataset to
aggregate*.

### 9.3 The model designer (the Model view becomes the editor)

```
Model: Trading and counterparty risk  v1.0.0 draft   [Check] [Save] [Propose ▾]   ⤺ ⤻
┌ Datasets ──────┐ ┌ Canvas ───────────────────────────────────────────────┐ ┌ Inspector ──────────────┐
│ search ____    │ │   [desks]◂─────┐                                       │ │ Relationship            │
│ Trades     +   │ │   [books]◂───┐ │                                       │ │ trades.counterpartyId → │
│ Netting sets + │ │          [ trades (fact) ]──┐                          │ │ counterparties.id       │
│ Counterparties+│ │                             ▾                          │ │ many-to-one · active    │
│ Desks       +  │ │          [counterparties (dimension)]                  │ │ from the pack reference │
│ …              │ │                             ▴                          │ │ 98% of values match     │
│ [+ New dataset]│ │          [ netting sets (fact) ]─┘                     │ │ [Make inactive] [Remove]│
└────────────────┘ │  Suggested: trades.nettingSet → netting sets  (pack)   │ └─────────────────────────┘
                   │             [Add as inactive] [Dismiss]                │
                   └────────────────────────────────────────────────────────┘
 Tabs: Canvas · Measures · Hierarchies · Security · Tests · Contract · AI · YAML
```

* **Datasets**: drag one onto the canvas (or `+`); its role is inferred (fact or dimension) and editable on the box.
  Adding a dataset immediately proposes its relationships from **pack references** (`references:`), then from values
  (match rate) and names; each is one click (*Add*), and an ambiguous second path is proposed inactive with the reason.
* **Relationships**: drag a column onto another, as today; the inspector shows cardinality, direction (single in W1;
  *both* greyed: "arrives in W2"), active, the match rate and the provenance.
* **Measures tab**: a list with *New measure* → pick a column → the default aggregation, format and additivity come
  from the dataset (a balance column defaults to *last over time*); a formula box with field completion and the
  server's check beside it; *Preview* shows the measure by a suggested dimension, as you, in place. Each measure shows
  how it will update live (*by difference / per group / on a tick*) and the facts it reads.
* **Security tab**: the datasets' rules read-only (with *Open the dataset*), then *Add a rule for this model*: role,
  then a condition built from a field picker and `user.` attributes (no typing), and **Preview as** a person or role
  showing the rows and masks they get. The tab says "rules here can only remove rows".
* **Tests tab**: expected values and golden questions; *Add from a preview* turns any preview figure into a test.
* **Contract tab**: what consumers use (from impact), the version the next publish needs (*this removes MTM median:
  2.0.0*), deprecations.
* Check runs on every edit (debounced) and pins problems on the canvas box or the measure row they belong to, with
  the `fix` as a button. Undo and redo cover every action; the YAML tab is two-way, for experts.

### 9.4 A report from a dataset (implicit model) or from a model

```
New report ─ on: (•) Trades (implicit model)  ( ) a model [▾]
Designer: Fields pane header: "Implicit model of Trades  [Make it a model]"
  ▾ trade      desk · book · currency · tradeDate (▸ year, month) · Sum of notional · Sum of mtm · Count of trade
  ▾ desk       name · head                      (came along from the pack reference)
  ▸ Report measures [+]                         (local measures: expressions over the model)
Drag "Sum of mtm" onto the canvas → a card. Drag "desk.name" onto it → a bar of MTM by desk (right default).
⚠ beside a visual: "Notional adds several currencies"  [Split by currency] [Filter one currency]
Trying to add Netting sets: "This report reads one model. [Make it a model and add Netting sets]"  (never a dead end)
```

Every visual's query is an MQ the author can see (*Query* in the visual's menu: the MQ, the answer's meta, *Copy as
YAML*, *Open in Analyse*), which teaches the language without requiring it.

### 9.5 Promote, move and consume

* *Make it a model* (implicit → explicit), *Move into the model* (local measure → model), *Add these datasets to the
  model* (instead of a same-owner link): each is one undoable action that produces a draft for review.
* Alert me, Subscribe, Analyse this, Export: on any visual, carrying its MQ; nothing to choose about datasets.

---

## 10. W1 build plan

### 10.1 Scope (from BI_MODEL_ARCHITECTURE.md W1)

The dataset and model item types; pack-generated datasets; connector and Python datasets; implicit models; MQ v1
(this spec, validator, compiler, API); security at the dataset, model security only tighter. **Not in W1** (formats only):
several-fact correctness guarantees beyond today's (W2), currency guard refusal (W2), incremental standing queries (W3),
consumer YAML migration and the designers' full form (W4), AI on MQ beyond *ask* (W5).

### 10.2 Work packages (at most two builders at once)

| WP | Builder | Size | What | Depends on |
|---|---|---|---|---|
| **S1** Items and parsers | server | M | item kinds `dataset` (v2) and `model` (`BiService.KINDS`, `BiFolder` `models/`, catalogue, governance, bundles); parsers and validators for sections 1, 2 (codes 9100–9139); stable ids written on save; the compatibility reader (8.4) | — |
| **S2** Pack-generated datasets | server | M | generation from packs (7.1) reusing `PackModels` column discovery and `ReferenceCatalog`; vocabulary meanings; regeneration on pack reload; read-only; Admin → Packs listing | S1 |
| **S3** MQ v1 | server | L | MQ parser, validator (5.3) with `fix`, flattener, compiler front end (5.4) over `SqlCompiler`; per-dataset security and dimension propagation; top-N, relative, drill moved from `VisualQueries` into MQ; totals by additivity; currency and semi-additive warnings; `/mq`, `/models/{id}/query`, `/describe`, `/mq/validate`; old `/datasets/{id}/query` translated | S1 |
| **S4** Connector and Python datasets | server | M | `source.connector` query (SQL target, bound dates); `source.python` and `source.model` with MQ inputs via `runFramed` and the Transform runner | S3 |
| **S5** Consumers on MQ | server | M | `VisualMq` (reports, exports, subscriptions, embeds through it); alerts `type: model` and visual sources via MQ; AI *ask* emits MQ; implicit models (section 3) | S3 |
| **S6** Standing-query endpoints | server | S | `/mq/subscribe`, `/mq/stream` with full recompute on generation change, throttle, caps (5.8) | S3 |
| **C1** Console on MQ | console | M | designer previews, slicer values, Analyse Slice and extracts (`explore_routes.extract_for`) send MQ; *Query* panel per visual; implicit-model header and *Make it a model* | S3 (API stub first) |
| **C2** Dataset wizard v2 and model view as editor (first cut) | console | M | the three-step wizard (9.2) for connector, Python, model and `extends`; the Model view edits a model: datasets panel, pack-reference suggestions, roles, measures tab with defaults, security tab with *Preview as* | S1, S2 |
| **M1** Converter and equivalence harness | migration | M | `drishti.py bi migrate` (8.3) on a temporary copy, the plan report, the equivalence harness (8.6); no shipped file changed in W1 | S1, S3 |
| **D1** Docs and tutorials | docs | M | new guides `DATASETS.md` (v2) and `MODELS.md`, `MODEL_QUERY.md` (MQ reference), API and CLI; tutorial 10 gets a new first part "From a pack dataset to a report in five minutes" (UI, CLI, YAML); the rest of 10–18 move in W4 | each WP as it lands |

**Order with two builders.** Builder A (server): S1 → S3 → S5 → S6. Builder B: S2 → C2 → S4 → C1 → M1. D1 follows each
WP (written by the builder of the WP, reviewed in the experience wave). S3 publishes the MQ request/response types first
(day one of S3) so C1 can start against them.

### 10.3 Test strategy

* **Unit** (fast, every WP): parsers and validators with every code of 9100–9183, one test per row of the tables in
  sections 1.11, 2.11, 4.4, 5.3; the flattener (model → engine `Dataset`) compared to the old dataset objects of every
  sample; MQ canonicalisation (the cache key) stable under key order.
* **Security matrix**: for each sample model, `rita`, `hana`, `ann` and a reader with no role: every visual's MQ; masks
  before aggregation; dimension propagation; model rules only tighter (a property test that ANDing never adds a row).
* **Equivalence** (8.6): old vs new over every shipped sample report visual, dataset test, the sample alert sources and
  extracts, on the **10K-trade demo lake** (never larger locally), one heavy process at a time; the 8.5 allow-list is
  explicit.
* **Acceptance test (W1)**: one test that asks the **same MQ** through a report visual, an alert (`type: visual` and
  `type: model`) and an Analyse extract-and-slice, as three readers, and asserts identical, per-reader masked numbers.
* **Browser** (Playwright, at phone and desktop width, keyboard-only pass): implicit-model report from a pack dataset
  without typing; wizard happy path and each dead-end check; model designer add-dataset → suggested relationship →
  measure → preview; *Make it a model*.
* **No drill during the wave**: targeted tests between WPs; one drill at the end of release 2.2.0, per the drill rule.

### 10.4 Acceptance criteria

1. (W1, from BI_MODEL_ARCHITECTURE.md) **a report, an alert and an Analyse slice ask the same model through the same
   query language and get identical, per-reader masked answers** (10.3 acceptance test, three readers).
2. Every enabled pack kind with a serving connector has a generated dataset, visible in the catalogue, read-only,
   versioned with its pack.
3. A connector-query dataset and a Python dataset can be made in the wizard without typing YAML, used in a model and
   queried.
4. Every shipped sample answers through MQ (via the compatibility reader) with the equivalence harness green apart from
   the 8.5 allow-list.
5. Model security is provably only tighter (property test) and dataset rules apply before any join.
6. Every refusal of 5.3 is shown beside the control that caused it, with its `fix` where one exists (experience review).
7. The experience wave's power-user findings are fixed before the drill.

---

## 11. Open questions for the product owner

**Decided on 2026-10-12: all eight accepted with the recommended answer.** Question 1 is accepted **with the security
safeguard** of section 2.6: Check flags any dataset of a model that has no row rule while a role is restricted elsewhere
in the same model, and the model cannot be published until that dataset gets a rule or an explicit, audited
`may-see-all: [role]` note (`DRS-9130`).

| # | Question | Recommended answer |
|---|---|---|
| 1 | **Security binding.** With rules at the dataset, a desk-head sees **all** netting-set exposure (today refused). Accept that, or keep a "fact rule hides other facts" option? | Accept; it is D-consistent and explainable. Offer a model rule on the other fact when a site wants it, and have the converter keep the composed sample's meaning with `having`. **Accepted with the safeguard (2.6, `DRS-9130`)** |
| 2 | **Implicit models bring pack dimensions** (one hop by pack reference). Always, or only when asked? | Always, one hop, dimensions only: it is what makes the first report useful with no model step |
| 3 | **Boards over two models** (`desk-wall`). One model per board strictly, or one model per board **page**? | One model per board, with islands allowed in a model (warning); convert `desk-wall` to a `live-desk` model. Revisit if real boards need more |
| 4 | **Generated dataset ids** `pack.<pack>.<kind>`: is the pack name stable enough, or use the pack code (`TRDS`)? | The pack name: readable in YAML and MQ; a pack rename is a major anyway |
| 5 | **The currency guard in W1**: warning only (refusal in W2), or refuse from W1? | Warning in W1 (it touches many shipped visuals over `Notional`); refuse in W2 as planned |
| 6 | **Old query API**: keep `POST /datasets/{id}/query` translated until W4, or remove in W1? | Keep until W4 (the console, Python `drishti_bi` and scripts move in W4), then `410` with the MQ equivalent |
| 7 | **Converter on stored items**: republish published items automatically as the bi-admin, or leave them as drafts for owners to publish? | Republish automatically (audited, reversible by restoring the saved state): a half-migrated catalogue is a dead end for readers |
| 8 | **MQ in YAML and JSON**, both forever, and the name "model query" (`mq: 1`) for users? | Both (YAML in files and tests, JSON on the wire); users see "query" in the UI, "model query" in the docs |

## 12. Query execution tiers *(planner: W3; the source and Java tiers exist in W1)*

A model is a small database rooted in packs: its datasets are the tables, references and relationships are the keys and
joins, measures are its stored calculations, row rules and masks are its grants, and the **model query (MQ, section 5)**
is its query language. Every panel, card, table, chart, slicer, alert, tile, export and Analyse slice is one MQ. An MQ
names **fields and measures only**; the joins come from the model's relationships, so a report says *what*, the model
knows *how*.

**The servers do the work; the browser draws.** Product-owner decision (2026-10-12): computing in the browser risks
different answers on different devices (an iPhone's Safari is not a PC's Chrome), so every number is computed on the
servers, and the browser only renders the answer it is given.

| Tier | Runs | Work | Why there |
|---|---|---|---|
| **1. Source** (through the pack's connector) | DuckDB over a Delta or Parquet lake, SQL to PostgreSQL, a stream's own rollups | column choice, row filters (including the reader's row rules on that dataset), aggregation at the dataset's own grain when the source can do it | the data does not move; the source scans its own data fastest |
| **2. Java server** (the Drishti engine) | the Spring Boot server | the MQ planner; joins between datasets of different connectors; per-grain aggregation the source cannot do; the meeting of facts through shared dimensions; fan-out refusal; the currency guard; **security** (row rules and masks per dataset before any join); totals; sort, limit, top-N; **standing queries** kept current incrementally; caches and refresh layers | the only place that sees every dataset of a query and can enforce security |
| **3. Console backend** (FastAPI, Python) | the console server | Python datasets, Python measures and **Python visuals** in the sandboxed runner (masked input only, CPU/memory/time limits); rendering of exports (PDF, PNG, Excel, CSV); narrative text; shaping the answer for the page (formats, labels, chart specification) | Python and rendering run the same way for every reader, whatever their device |
| **4. Browser** | the reader's browser | **presentation only**: draw the chart or table it is given, hover tooltips, scroll; every interaction that changes numbers (a slicer, a sort, a drill, a cross-filter, a what-if value) sends a new MQ to the servers | no number is computed here, so every device shows the same answer |

**Rules.**

1. **Every number comes from tiers 1–3.** The browser never sums, filters, sorts, pivots or recalculates; the same MQ
   gives the same answer on any device. (Answers are cached per reader on the server, so interactions stay fast.)
2. **Security never leaves the servers;** the browser receives only what the reader may see, already masked.
3. **Python runs on the console backend** (sandboxed), never in a report's browser page. A design does not choose
   where code runs.
4. **Live:** a subscribed MQ stays on the Java server, which pushes the **changed rows of the answer** to the page; the
   browser replaces them on screen (it does not recompute).
5. **`options.explain: true`** returns the plan: which parts ran at which tier, the source queries, rows moved, time per
   part. The designer shows it under each visual.

**Future work, not scheduled: a Python console in the browser.** A person's own Pyodide console connected to a model
for just-in-time analytics is a possible later addition. Until then there is no browser computation at all: Analyse's
Python cells run on the console backend's sandbox like every other Python item.

## 13. Worked example, end to end: two packs, five datasets, one model, one report

The whole architecture in one example, using two shipped packs: **trading** (kind `trade`) and **counterparty-risk**
(kinds `netting-set`, `credit-limit`, `collateral-balance`). Field names are the packs' real ones.

### 13.1 The picture

```
 PACKS (kinds + connectors; the data stays here)
 ┌──────────────── trading ────────────────┐     ┌────────────────────── counterparty-risk ──────────────────────┐
 │ kind trade  (trading-store: Delta lake) │     │ netting-set, credit-limit (credit-store)                      │
 │                                         │     │ collateral-balance (collateral-store)                         │
 └───────────────────┬─────────────────────┘     └──────────────┬───────────────┬──────────────────┬─────────────┘
                     │                                          │               │                  │
 DATASETS (one per kind, automatic; plus one derived)           │               │                  │
   trades ◄─ pack.trading.trade                 netting-sets ◄──┘   limits ◄────┘    collateral ◄──┘
   (fact)                                       (fact)             (fact)            (fact)
                                         counterparties ◄─ derived from credit-limit: one row per counterparty
                                         (dimension, shared)
                     │
 DATA MODEL  counterparty-cockpit@1  (datasets + relationships + measures + security; versioned; owned by risk-analytics)
                     │
 CONSUMERS (each references the model; none owns it)
   report "Counterparty cockpit"   board "Risk wall"   alert "Headroom below 10%"   Analyse notebook   subscription
```

### 13.2 The five datasets

Three are generated by the packs and used as they are (section 7); you never write them. Their essentials:

| Dataset | From | Grain (one row per) | Columns used | Role |
|---|---|---|---|---|
| `pack.trading.trade` | trading / `trade` | trade per business date | `tradeId` key, `nettingSet` → netting-set, `counterpartyId` → counterparty, `desk`, `productType`, `currency`, `notional`, `mtm`, `pnl1d`, `tradeDate` | fact |
| `pack.counterparty-risk.netting-set` | counterparty-risk / `netting-set` | netting set | `nettingSetId` key, `counterparty`, `eePeak`, `pfePeak`, `netMtm`, `cva` | fact |
| `pack.counterparty-risk.collateral-balance` | counterparty-risk / `collateral-balance` | collateral balance | `balanceId` key, `nettingSet` → netting-set, `held`, `posted`, `net` | fact |

Two are written by a person (in the wizard, which writes this YAML):

```yaml
# 4. limits: the PFE credit limits only, with a derived headroom column
rachana: 2
dataset: pfe-limits
title: PFE credit limits
extends: pack.counterparty-risk.credit-limit@^1
where: measure = 'PFE'                                 # row filter: one measure of limit
columns: [limitId, counterparty, counterpartyName, limit, used, utilisation, status]
derive:
  headroom: { expr: "limit - used", type: decimal, meaning: amount, unit: { currency: USD }, additive: true, format: amount0 }
grain: { says: One row per PFE limit, keys: [limitId] }
```

```yaml
# 5. counterparties: a shared dimension derived from the limits, with explicit de-duplication
rachana: 2
dataset: counterparties
title: Counterparties
extends: pack.counterparty-risk.credit-limit@^1
columns: [counterparty, counterpartyName, reviewDue]
grain: { says: One row per counterparty, one-row-per: counterparty, keep: latest by reviewDue }   # never a silent distinct
columns-meta:
  counterparty:     { role: key, meaning: counterparty-id }
  counterpartyName: { role: attribute, of: counterparty }
security:
  masked: [counterpartyName]                           # names are sensitive: shown as ••• without the raw power
```

### 13.3 The model

```yaml
rachana: 2
model: counterparty-cockpit
version: 1.0.0
title: Counterparty cockpit
owner: risk-analytics
datasets:
  trades:         { dataset: pack.trading.trade@^1,                         role: fact }
  netting_sets:   { dataset: pack.counterparty-risk.netting-set@^1,         role: fact }
  collateral:     { dataset: pack.counterparty-risk.collateral-balance@^1,  role: fact }
  limits:         { dataset: pfe-limits@^1,                                 role: fact }
  counterparties: { dataset: counterparties@^1,                             role: dimension }
relationships:                                          # suggested from the packs' references; accepted with one click
  - { from: trades.counterpartyId,       to: counterparties.counterparty }   # many-to-one, crosses the two packs
  - { from: netting_sets.counterparty,   to: counterparties.counterparty }
  - { from: limits.counterparty,         to: counterparties.counterparty }
  - { from: trades.nettingSet,           to: netting_sets.nettingSetId }      # trades roll up to netting sets
  - { from: collateral.nettingSet,       to: netting_sets.nettingSetId }
measures:
  MTM:               { expr: "sum(trades.mtm)",           format: signed0 }
  "P&L 1d":          { expr: "sum(trades.pnl1d)",         format: signed0 }
  Trades:            { expr: "count(trades)" }
  EE peak:           { expr: "sum(netting_sets.eePeak)",  format: amount0 }
  PFE peak:          { expr: "sum(netting_sets.pfePeak)", format: amount0 }
  Collateral held:   { expr: "sum(collateral.held)",      format: amount0 }
  PFE limit:         { expr: "sum(limits.limit)",         format: amount0 }
  Headroom:          { expr: "sum(limits.headroom)",      format: amount0 }
  Limit used:        { expr: "ratio(sum(limits.used), sum(limits.limit))", format: pct1 }
  Collateral cover:  { expr: "[Collateral held] / [EE peak]", format: pct0 }   # two facts, each at its own grain
hierarchies:
  Counterparty: [counterparties.counterpartyName, netting_sets.nettingSetId]
security:
  rows:
    trades:
      - { role: desk-head, where: "trades.desk in user.desks" }
      - { role: [risk, analyst, admin], where: "true" }
  may-see-all: { netting_sets: [desk-head], collateral: [desk-head], limits: [desk-head], counterparties: [desk-head] }
                                                       # the safeguard (2.6): exposure is shared across desks by policy; audited
tests:
  - { name: Headroom is limit minus used, measure: Headroom, expect-equals: "[PFE limit] - sum(limits.used)" }
ai:
  synonyms: { Headroom: [room under the limit, spare limit], "P&L 1d": [daily P&L] }
```

What the model guarantees, without anyone writing a join:

* **MTM and PFE peak per counterparty in one visual** are each aggregated at their own grain (trades, netting sets),
  then meet through `counterparties`. Summing a joined trade-by-netting-set table instead would repeat each netting
  set's exposure once per trade (the trap of tutorial 18).
* **A filter on a shared dimension filters every fact;** a filter on one fact's own column filters **only that fact**.
  `trades.currency = USD` narrows MTM and P&L, not exposure or limits; the visual's footnote says so.
* **Security per dataset:** a desk-head's rule cuts `trades` to their desks before any join; exposure, collateral and
  limits are shown in full to them because the model says so explicitly (`may-see-all`, audited); `counterpartyName` is
  `•••` for readers without the raw power, in every report on this model.

### 13.4 The report (it references the model; it owns nothing but its layout and local measures)

```yaml
rachana: 2
report: counterparty-cockpit-report
title: Counterparty cockpit
model: counterparty-cockpit@1          # the one model it is rooted in
measures:                               # local, "this report only"; can be moved into the model
  Headroom after collateral: { expr: "[Headroom] + [Collateral held]", format: amount0 }
pages:
  - name: Overview
    visuals:
      - { id: total-mtm, type: card,  wells: { value: [MTM] } }
      - { id: total-headroom, type: card, wells: { value: [Headroom] } }
      - { id: by-cpty, type: bar,    wells: { axis: [counterparties.counterpartyName], values: [MTM, PFE peak] } }
      - { id: limits, type: table,   wells: { columns: [counterparties.counterpartyName, PFE limit, Limit used, Headroom, Headroom after collateral] },
          filters: [{ field: counterparties.counterparty, op: top, n: 10, by: Limit used, dir: desc }] }
      - { id: ccy, type: slicer,     wells: { field: [trades.currency] }, format: { mode: dropdown } }
      - { id: concentration, type: python, wells: { values: [counterparties.counterpartyName, PFE peak] } }   # runs on the console backend
```

Other consumers on the **same model** (none owns it; the model's *Used by* lists them all):

```yaml
alert: headroom-low
source: { type: model, model: counterparty-cockpit@1,
          query: { select: { measures: [Limit used] }, where: [{ field: counterparties.counterparty, op: eq, values: [CPTY-ALDERSHOT] }] },
          reduce: value }
rule: { above: 0.9 }                    # tell me when Aldershot uses more than 90% of its PFE limit
```

A board tile, a subscription of the report, and an Analyse notebook (`model: counterparty-cockpit@1`, `shape: rows`
extracts) are written the same way.

### 13.5 What happens when Rita (risk) and Hana (desk head, rates desk) open the report

**The bar `by-cpty`** sends this MQ (the designer shows it under the visual):

```yaml
mq: 1
model: counterparty-cockpit@1
select: { fields: [counterparties.counterpartyName], measures: [MTM, PFE peak] }
where:  [{ field: trades.currency, op: in, values: [USD] }]       # Hana picked USD in the slicer
time:   { as-of: 2026-10-09 }
```

The planner splits it:

| Step | Tier | What runs |
|---|---|---|
| 1 | source: trading-store (DuckDB over the Delta lake) | `select counterpartyId, sum(mtm) from trades where businessDate = '2026-10-09' and currency = 'USD' and desk in ('DESK-RATES') group by counterpartyId` — Hana's row rule is pushed down as part of the scan; for Rita the `desk in` clause is absent |
| 2 | source: credit-store | `sum(pfePeak) group by counterparty` over netting sets (no currency filter: a fact's own filter does not reach other facts) |
| 3 | source: credit-store | counterparties: one row per counterparty, latest by `reviewDue` |
| 4 | server | join the two aggregates to `counterparties` on the counterparty key (each already at counterparty grain: no fan-out possible); mask `counterpartyName` for Hana (no raw power) → `•••`; apply the visual's sort and limit |
| 5 | console backend | shape the answer for the bar chart (labels, formats, the chart specification) |
| 6 | browser | draw the bars and show tooltips; a click on a bar (cross-filter) or a new slicer value sends a new MQ |

**What each sees:** Rita gets every counterparty, names shown, MTM over all desks. Hana gets the same PFE peaks
(`may-see-all`), MTM only from the rates desk's USD trades, and `•••` instead of names. Both answers come from the same
model and the same MQ; only the reader differs.

**The table `limits`** runs entirely on credit-store plus the server (no trades are read); its local measure
`Headroom after collateral` is computed on the server after `Headroom` and `Collateral held` are aggregated at their
own grains. **The Python visual** runs on the console backend in the sandbox on the masked rows; for Hana it
receives `•••` names, never the real ones, and every device shows the same picture. **The alert** runs the same MQ path on the server as its owner, every check.

**Live:** if the trading pack's connector streams (time mode `live`), the bar's MQ is subscribed: the server keeps the
MTM aggregate current by applying each new trade as a difference to its counterparty's sum, and pushes only the changed
bars to the page, which redraws them. PFE peak, from a snapshot dataset, stays as of the business date until the next load.

### 13.6 What you did, and what you did not have to do

* You chose four pack datasets, wrote two small datasets in a wizard (a filter, a derived column, an explicit
  de-duplication), accepted suggested relationships, named ten measures, and built a report by dragging fields.
* You did **not** write a join, copy any data, write security per report, or decide where anything runs. The packs gave
  the keys, meanings and references; the model gave the joins and the measures; the servers computed every number;
  security followed the data from the pack to every consumer.
