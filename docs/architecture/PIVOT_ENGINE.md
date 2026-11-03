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
# The pivot engine: one generalised, very large-scale pivot for all of Drishti

**Status: phase P1 partly built (2.9.11.a, unreleased); the rest design, not built.** Built, and described for users in
[PIVOT_GUIDE.md](../guides/PIVOT_GUIDE.md): the module `drishti-bicompute` (the core with declarative cell sets, the provider and function
SPIs, the mergeable and exact holistic built-ins, the GROUPING SETS compiler and the DuckDB tabular provider over the Data view's tables, the
ordered merge, the partial-state cache, the split-and-merge harness, xlsx and CSV export), the VaR provider on the new SPI, the API, the
CLI and the screen of mock-up A. Everything the build checklist ([section 24](#24-build-checklist)) leaves unticked is not built. The plan's
rows are in [IMPLEMENTATION_PLAN.md, 2.9.11.a](IMPLEMENTATION_PLAN.md#2911a-the-pivot-engine-then-the-mr-cube).

**What the product owner asked for (2026-11-02, in their words):** "build capability of a super scalable very generalized pivot
capability. Think of MS Excel but more capable. Built in aggregation functions but people can also supply custom aggregation functions.
And it should operate on extremely large volume of data." Then: the MR cube should use it; custom functions may be in Python; "pause MR
cube then and build this capability first, then come back to MR cube". And: "we need to do better than Excel and better than anything out
there" ([section 10](#10-beyond-anything-out-there)).

**Read with:** [MR_CUBE_LENS.md section 4](../lenses/MR_CUBE_LENS.md#4-the-shared-cube-core-mr-and-ccr) (the shared cube core this
engine grows from), [CCR_CUBE_LENS.md](../lenses/CCR_CUBE_LENS.md) (the second cube on it), [BI_GUIDE.md](../guides/BI_GUIDE.md) (the BI
query engine on DuckDB, rollups, true totals, run cards, Python measures), [BI_LANGUAGE_REFERENCE.md](../guides/BI_LANGUAGE_REFERENCE.md)
(DEL, the expression language), [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) (the compute worker pools), [VAR_SHARDING.md](VAR_SHARDING.md)
(scatter and gather of partial sums across Drishti\*net), [COMPUTE_ENGINES.md](COMPUTE_ENGINES.md) (one file and one switch per engine),
[IN_MEMORY_LAKE.md](IN_MEMORY_LAKE.md) (data that may never touch a disk), [RESOURCES_AND_SCALE.md](RESOURCES_AND_SCALE.md) (memory
budgets and busy protection) and [CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md).

## Contents

- [1. The idea in one page](#1-the-idea-in-one-page)
- [2. What Drishti has today](#2-what-drishti-has-today)
- [3. One engine, many measure providers](#3-one-engine-many-measure-providers)
- [4. The pivot model](#4-the-pivot-model)
- [5. Three kinds of measure, and how each total is made](#5-three-kinds-of-measure-and-how-each-total-is-made)
- [6. The providers](#6-the-providers)
- [7. From the MR cube's core to the pivot engine: what is kept, what changes](#7-from-the-mr-cubes-core-to-the-pivot-engine-what-is-kept-what-changes)
- [8. Custom aggregation functions](#8-custom-aggregation-functions)
- [9. Scale: extremely large data](#9-scale-extremely-large-data)
- [10. Beyond anything out there](#10-beyond-anything-out-there)
- [11. Expand and collapse: nested levels, better than Excel](#11-expand-and-collapse-nested-levels-better-than-excel)
- [12. BI on the pivot engine](#12-bi-on-the-pivot-engine)
- [13. The module: drishti-bicompute](#13-the-module-drishti-bicompute)
- [14. The screens and pivot charts](#14-the-screens-and-pivot-charts)
- [15. Export](#15-export)
- [16. The engine switch and its configuration](#16-the-engine-switch-and-its-configuration)
- [17. The API](#17-the-api)
- [18. The command line](#18-the-command-line)
- [19. Who sees what, and audit](#19-who-sees-what-and-audit)
- [20. Tests that prove it](#20-tests-that-prove-it)
- [21. The benchmark plan](#21-the-benchmark-plan)
- [22. Phases and sizes](#22-phases-and-sizes)
- [23. Decisions](#23-decisions)
- [24. Build checklist](#24-build-checklist)
- [25. Mock-ups: the screens to build](#25-mock-ups-the-screens-to-build)
- [26. Sources](#26-sources)

## 1. The idea in one page

A **pivot** is a question of the form "these measures, broken down by these dimensions on rows and columns, under these filters, as of this
date". Excel answers it for the rows that fit in a sheet, with sums that are right and totals that are wrong whenever a measure does not
add up. Drishti answers it for any amount of data, for every measure it has (a sum of notional, a distinct count of counterparties, a
99% VaR, an FRTB charge, a function a quant wrote this morning), and every total is right.

1. **One pivot engine for all of Drishti.** The shared cube core of the MR and CCR cubes ([MR_CUBE_LENS.md section 4](../lenses/MR_CUBE_LENS.md#4-the-shared-cube-core-mr-and-ccr))
   grown up: it owns the view, the cells, the totals, the budget, the cache, provenance, saved views, explain, export and the cluster
   merge. It never computes a figure itself: **measure providers** behind one interface do.
2. **Providers.** A **tabular** provider (DuckDB scans of Delta, Iceberg and Parquet, the BI engine Drishti already runs) for any table
   of any pack; the **VaR vector** provider (per-cell statistics from summed scenario vectors, in Java); **FRTB-SA** and **SIMM** formula
   providers; later the **CCR cube's** (EE, PFE, EAD). The MR cube and the CCR cube become **sets of providers and their landings** on
   this engine, not engines of their own.
3. **Three kinds of measure**, and the kind decides how a total is made: **mergeable** (sum, count, minimum, maximum, average as sum and
   count, variance, weighted average: totals merged from the cells' small states, exactly); **sketch** (distinct count, percentiles,
   top N: bounded states that merge with a stated error, shown "≈", exact on request); **holistic** (VaR, ES, exact median: each cell and
   each total recomputed from its own members, bounded by the cell budget, busy protection and the cell cache).
4. **Custom aggregation functions** with one four-step contract, **start → add (a columnar batch) → merge → finish**, written as a
   formula of built-ins (DEL), a DuckDB SQL macro, Python (vectorised over Arrow batches in a sandboxed worker pool on each node), or a
   Java plug-in. A function is tested by a harness that fails it when its split-and-merge answer differs from its one-pass answer; it has a
   **playground**, versions, an author, an audit trail and an administrator's approval before anyone else may use it. Official risk
   measures stay Java engines; custom functions are user analytics, labelled with their author and version.
5. **Scale.** Each node runs its own DuckDB executor over the files it is given; Drishti\*net scatters the plan and gathers **partial
   states per cell**, merged in a fixed order (as VaR shards do today). Column pruning and Parquet statistics skip what is not needed;
   tables are laid out by the dimensions people pivot by; **rollups** are refreshed on load and chosen automatically; sketches keep
   distinct counts and percentiles cheap; answers are **progressive** (≈ first, then exact); every large question gets a **scan estimate**
   before it runs ("≈180 GB, ≈2 min on 3 nodes") with cheaper options. DuckDB's limits are stated honestly
   ([section 9.10](#910-duckdbs-limits-stated-honestly)); a distributed SQL backend is a future provider, not part of this design's build.
6. **Excel's pivot, all of it, and more:** drag fields to rows, columns, values and filters; calculated fields; show values as % of
   total, % of parent, running total, difference from, rank; date and number grouping; slicers; subtotals; conditional formats;
   drill-through to the rows; and beyond Excel: no row limit, correct non-additive totals, cell provenance, explain any difference, time
   travel, what-if, live cells, entitlements and masks applied before aggregation, saved and shared views, the cluster. Export to xlsx
   **with a native PivotTable**, csv, Arrow and Power Query.
7. **An engine with its own switch:** `pivot`, in `config/engines/pivot.yaml` ([COMPUTE_ENGINES.md](COMPUTE_ENGINES.md)), off means it
   does not exist on that server.
8. **Expand and collapse better than Excel** ([section 11](#11-expand-and-collapse-nested-levels-better-than-excel)): nested rows and
   columns computed lazily per opened node, "expand by" a different dimension per member, ragged hierarchies, a correct Others, down to
   the trades inline; and **pick-and-choose charts** linked both ways to the pivot ([section 14.3](#143-pivot-charts-pick-and-choose)).
9. **BI runs on it** ([section 12](#12-bi-on-the-pivot-engine)): BI's matrix, totals, rollups and the semantic model's measures move onto
   the engine behind a parity gate, so a measure defined once works in BI, the pivot screen, the cubes and every export.
10. **One library**, the Maven module `drishti-bicompute` ([section 13](#13-the-module-drishti-bicompute)): plain Java, no Spring; every
    component that aggregates calls it, and none re-implements aggregation.
11. **Export to Excel and CSV**, complete ([section 15](#15-export)): the pivot as shown with its outline, the underlying rows, chart
    data or the definition; formulas only where right, a Provenance sheet, Excel's limits handled in words, big exports streamed.

## 2. What Drishti has today

Read from the guides and the code on 2026-11-02. The engine reuses every row of this table; none of it is rebuilt.

| What | Where | Built | What the pivot engine takes from it |
|---|---|---|---|
| BI query engine: datasets, DEL compiled to parameterised SQL, row rules and masks, embedded DuckDB (`AnalyticEngine`, `DuckDbEngine`) | [BI_GUIDE.md section 30](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) | yes | the tabular provider's compiler, security and connection handling |
| Layers and rollups: a rollup stores the **components** of its measures (sums, counts, minimums, maximums; `avg` as sum and count) per date and group, and a query is routed to the smallest layer that covers it | [BI_GUIDE.md, modes, layers and rollups](../guides/BI_GUIDE.md#7-modes-layers-and-rollups) | yes | aggregate awareness and the rollup store, extended to sketches |
| True totals: a total of a measure that does not add up is one more query per level, never the sum of the figures shown | [BI_GUIDE.md, true totals](../guides/BI_GUIDE.md#32-true-totals) | yes | the rule, generalised to every provider |
| Runs and run cards: a range estimate from Delta statistics, "why it is large", confirm above a minute or 500 million rows, run in background | [BI_GUIDE.md](../guides/BI_GUIDE.md) (runs) | yes | the scan estimate and the background job |
| The `matrix` visual (rows × columns, subtotals at every level) and Analyse's **Slice** (`POST /bi/explore/pivot`) | [BI_GUIDE.md](../guides/BI_GUIDE.md) | yes | replaced by the pivot engine's answer (they keep their YAML; [section 6.2](#62-the-tabular-provider-duckdb)) |
| The Pivot tab on a table or ladder (Rachana `pivot`) | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#pivot-pivot-on-a-table-or-ladder), [USER_GUIDE.md](../guides/USER_GUIDE.md#pivot-slice-a-table-your-way) | yes | becomes a pivot view over the panel's rows |
| Python measures in BI: `compute(table, params)` per group, sandboxed (isolated process, empty environment, no network, scratch folder only, CPU, memory, output limits, package allow-list) | [BI_GUIDE.md section 34](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python), [its sandbox](../guides/BI_GUIDE.md#7-the-sandbox-exactly) | yes | the sandbox's guards, unchanged; a Python measure is a holistic custom function |
| Compute worker pools (`drishti-compute`): fork-server per worker on Linux, warm workers on Windows, Arrow IPC scratch transport, limits, fan-out over nodes | [PYTHON_COMPUTE.md section 7](PYTHON_COMPUTE.md#7-the-worker-pool) | yes (C1 to C3) | the per-node pool that runs Python custom functions |
| VaR sharding: partial vectors per shard added in shard order, bit-equal to the whole (`ShardedMeasure`, `ShardGather.Combine`) | [VAR_SHARDING.md](VAR_SHARDING.md) | yes | the cluster merge, lifted into the core for every provider |
| Busy protection: one admission gate refusing new heavy work politely | [RESOURCES_AND_SCALE.md section 5.7](RESOURCES_AND_SCALE.md#57-busy-protection-refuse-politely-before-the-heap-runs-out), [OPERATIONS.md section 12.5](../admin/OPERATIONS.md#125-busy-protection-refuse-politely-never-fall-over) | yes (unreleased) | every pivot passes the gate |
| The in-memory lake: tables on `mem://`, DuckDB's spill switched off for them, the never-on-disk guard at every sink | [IN_MEMORY_LAKE.md](IN_MEMORY_LAKE.md) | yes (unreleased) | the rule that a memory-only table is never spilled or written for user code |
| Why VaR moved (RO1): exact steps that add up to the change | [OVERSIGHT_LENS.md section 4.1](../lenses/OVERSIGHT_LENS.md#41-ro1-var-change-explanation) | yes | the method behind "explain the difference" ([section 10](#10-beyond-anything-out-there), item 1) |
| The shared cube core and the MR source, on the paused branch `wip/mr-cube` | the branch (not merged) | built there, paused | the code this engine grows from ([section 7](#7-from-the-mr-cubes-core-to-the-pivot-engine-what-is-kept-what-changes)) |

## 3. One engine, many measure providers

```
 console grid · ViewModel · drishti.py pivot · REST · Arrow · xlsx            (people and programs ask; nothing computes in the browser)
                       │  a pivot view (dimensions, measures, filters, date, lens)
                       ▼
 ┌──────────────────────────── the pivot engine (the core) ────────────────────────────┐
 │ view → plan: cell keys (cells, row and column totals, grand total), per measure its kind │
 │ budget · busy gate · cache of partial states · estimate · progressive answer           │
 │ cluster: scatter the plan, gather partial states per cell key, merge in a fixed order    │
 │ finish → figures · show-as · provenance ids · explain · references · export              │
 └───────┬───────────────┬────────────────┬──────────────────┬───────────────┬───────────┘
         │ evaluate(cell keys, functions) → partial states per cell key (one interface)       │
   ┌─────▼─────┐  ┌──────▼──────┐  ┌──────▼──────┐  ┌───────▼───────┐  ┌────▼──────────┐
   │ tabular   │  │ VaR vector  │  │ FRTB-SA,    │  │ CCR (later):  │  │ external SQL  │
   │ DuckDB    │  │ (Java, in   │  │ SIMM        │  │ EE, PFE, EAD  │  │ backend       │
   │ Delta,    │  │ memory)     │  │ (formula)   │  │ (path matrix) │  │ (future)      │
   │ Iceberg,  │  └─────────────┘  └─────────────┘  └───────────────┘  └───────────────┘
   │ Parquet   │        custom functions: DEL formula · SQL macro · Python (worker pool) · Java plug-in
   └───────────┘
```

**Terms.** A **view** is what the person asked. A **cell key** is a cell's coordinates (dimension → value, or "all" for a total) plus
the view's filters; it names a cell without listing its members. A **function** is an aggregation with the four steps of
[section 8.1](#81-the-contract-start-add-merge-finish). A **partial state** is what `add` and `merge` produce for one cell key on one
node. A **provider** turns cell keys and functions into partial states, over the data it owns. The **core** merges, finishes, shows.

**Why one engine.** The MR cube, the CCR cube, BI's matrix, the Pivot tab and Analyse's Slice each draw a pivot today or will; five
pivots would mean five sets of totals, five budgets, five exports and five ways of being wrong. One engine means one rule for a total,
one provenance, one Excel export, one test suite, one cluster merge.

## 4. The pivot model

### 4.1 The view

```yaml
# a pivot view (what a saved view stores; the API takes the same as JSON)
pivot: 1
source: trading.trades                 # a provider's source: a BI dataset table, a lake table, or a cube (mr, ccr)
date: 2026-10-30                       # a business date, a range, or two for a comparison (section 10.2)
as-of: { version: latest }             # or { version: 1842 } (Delta) / { snapshot: 7731... } (Iceberg) / { at: 2026-10-30T18:00Z }
rows: [LEGAL_ENTITY, DESK]
columns: [{ field: TRADE_DATE, group: month }]
values:
  - { measure: sum(NOTIONAL_USD), as: Notional }
  - { measure: count_distinct(COUNTERPARTY_ID), exact: false }    # a sketch: shown ≈ with its error
  - { measure: var(99, 1d), provider: var }                        # holistic: each cell its own VaR
  - { measure: fn:asha/trimmed_mean@3(PNL_1D, 0.05) }              # a custom function, author and version named
  - { measure: sum(NOTIONAL_USD), show-as: { percent-of: parent-row } }
filters: { PRODUCT_GROUP: [RATES, FX], TRADE_STATUS: { not: [MATURED] } }
slicers: [CURRENCY]
totals: { rows: true, columns: true, subtotals: all }
currency: { report: USD, fx: { source: market-data, at: close } }
sort: { by: Notional, desc: true }
top: { field: DESK, n: 10, others: true }                          # never silent: "top 10 of 41, the other 31 in Others"
```

### 4.2 Dimensions: typed and ordered

A provider publishes a **catalogue** of its dimensions. Each has a type and an order, so the grid sorts, groups and labels the same way
everywhere:

| Type | Examples | Grouping offered | Order |
|---|---|---|---|
| string | `DESK`, `CURRENCY`, `COUNTERPARTY_ID` | manual groups ("G10", "EM"), top N with Others | a configured order, else by value |
| date and time | `TRADE_DATE`, `BUSINESS_DATE`, `MATURITY_DATE` | day, week, month, quarter, year, the bank's calendar and fiscal year, buckets from today | by time |
| number | `NOTIONAL_USD`, `TENOR_YEARS`, `RATING_SCORE` | buckets by width, by edges (`[0, 1, 2, 5, 10, 30]`), quantile buckets (a sketch) | by value |
| hierarchy level | MGMT (bank → division → desk → book), LEGAL, limit letters, risk-factor trees | expand and collapse per level, ragged hierarchies | the hierarchy's order |
| scenario day | VaR's 509 days (the VaR provider only) | by year, by stress window | by date |

A dimension that is masked for the person cannot be used as rows or columns, and says why ([section 19](#19-who-sees-what-and-audit)).

### 4.3 Cell keys, not member lists

A view of R rows and C columns has R × C cells, R row totals, C column totals and one grand total, plus every subtotal level that is
open. The core turns them into **cell keys**: `{DESK: RATES-NY, month: 2026-10}`, `{DESK: RATES-NY, month: *}`, `{DESK: *, month: *}`.
A provider decides how to evaluate them:

- the **tabular** provider compiles the whole set into **one** `GROUP BY GROUPING SETS (...)` query (cells, each total level and the
  grand total in one scan; [section 6.2](#62-the-tabular-provider-duckdb));
- the **VaR** provider maps each key to the set of its members (an `OrdinalSet` of positions, exactly as the MR cube does today) and sums
  their vectors;
- the **formula** providers map each key to the sensitivities of its members, summed to the formula's grain.

The **axis** (which values appear on rows and columns) is asked first, cheaply (distinct values under the filters, from the catalogue's
statistics or a small query), so the cell count is known **before** any measure runs, and a view over the budget is refused with the
count, never truncated.

### 4.4 The answer

```json
{ "view": "...", "cells": [ { "key": "c:7f3a…", "coords": {"DESK": "RATES-NY", "month": "2026-10"},
    "values": { "Notional": 1.284e9, "Counterparties": {"v": 412, "approx": true, "error": "±1.6% (95%)"},
                "VaR 99 1d": 4.82e6, "trimmed_mean": 1031.7 },
    "notes": {} } ],
  "totals": [ … the same, kind: row | column | total, per level ],
  "diversification": { "VaR 99 1d": { "rows": [...], "columns": [...] } },
  "provenance": "pv:2026-10-30:9b21…", "exactness": "partial: Counterparties ≈", "layer": "rollup by_desk_month",
  "progress": { "final": true }, "took_ms": 840 }
```

Every value is either a number, or a number with `approx: true` and its error, or `null` with the reason in `notes` (masked, refused,
a shard down, a currency that cannot be converted). Nothing is blank without a reason.

## 5. Three kinds of measure, and how each total is made

### 5.1 The rule

**A cell or a total is made from its members, never from other cells' figures.** What differs by kind is how cheaply:

| Kind | State | Merge | A total is | Marked |
|---|---|---|---|---|
| **Mergeable** | small and fixed (a sum; a sum and a count; Welford's count, mean and M2) | exact (associative and commutative) | the merge of its cells' states: no second scan | exact |
| **Sketch** | bounded (an HLL register array, a KLL or t-digest summary, a Space-Saving list) | approximate, within a stated error | the merge of its cells' sketches | ≈ with the error; **exact on request** |
| **Holistic** | unbounded (every value, or a vector per member) | none that is small, or merge is exact only on inputs | recomputed from its own members | exact (or refused over the budget) |

VaR and ES are **holistic statistics over mergeable inputs**: a scope's state is its summed scenario P&L vector (509 values, 4 KB), which
merges exactly by element-wise addition, and the statistic (a rank, a tail mean) is taken per cell. The MR cube computes every cell and
total from its own members' vectors in a fixed order, so each equals the VaR engine's answer for that scope to the bit; the engine keeps
that ([section 6.3](#63-the-var-vector-provider)). An exact median has no small state at all: it needs the values.

### 5.2 The built-in functions

| Function | Kind | State | Finish | In Excel's export |
|---|---|---|---|---|
| `sum(x)` | mergeable | Σx (Kahan-compensated float64, or decimal for money columns declared decimal) | Σx | SUM; in a native PivotTable a data field |
| `count(x)`, `count()` | mergeable | n | n | COUNT; a data field |
| `min(x)`, `max(x)` | mergeable | the extreme | it | MIN, MAX; data fields |
| `avg(x)` | mergeable | (Σx, n) | Σx / n | a calculated field `sum_x / count_x` (right at every level) |
| `wavg(x, w)` | mergeable | (Σwx, Σw) | Σwx / Σw | a calculated field |
| `var(x)`, `stddev(x)` (sample and population) | mergeable | (n, mean, M2), merged by Chan's formula | M2 / (n − 1) | values only |
| `ratio(sum(a), sum(b))` and any DEL of mergeable parts | mergeable | each part's state | the formula | a calculated field when every part is a sum |
| `first(x by t)`, `last(x by t)` | mergeable | (t, x) of the extreme t | x | values only |
| `count_distinct(x)` | sketch (HLL), exact on request | 2^p registers (p = 12: 4,096 bytes) | the HLL estimate | values only |
| `percentile(x, q)`, `median(x)` | sketch (KLL, k = 200), exact on request | a KLL summary | the rank query | values only |
| `top(x, n by m)` | sketch (Space-Saving, capacity 10 × n) | counters | the n largest, each with its error bound | values only |
| `median(x, exact)`, `percentile(x, q, exact)`, `mode(x)` | holistic | the values | exact | values only |
| `var(c, h)`, `es(c, h)`, stressed VaR, component VaR | holistic (VaR provider) | the scenario vector | rank or tail mean | values only, with the vector sheet when asked |
| `sbm()`, `drc()`, `simm()` | holistic (formula providers) | sensitivities at the formula's grain | the regulatory formula | values only |

Error statements, measured and printed with every ≈ value: HyperLogLog's relative standard error is about 1.04 / √m for m registers
(Flajolet and others, 2007), so 1.6% at m = 4,096 and 0.8% at m = 16,384 (p = 14); a KLL sketch with k = 200 has a normalised rank
error of about 1.65% (Apache DataSketches); Space-Saving's counts are over-estimates by at most the smallest counter (Metwally and others,
2005). The configured precision is a setting ([section 16](#16-the-engine-switch-and-its-configuration)), and the stated error is the
one the test suite measures on the site's data, not only the formula.

### 5.3 "Exact on request" without giving up scale

- **Distinct count.** Exact needs the set of values. Where the table is partitioned or clustered by the counted column (counterparty on
  the CCR tables, for example) per-node exact counts add up and the engine says so; otherwise each node sends its distinct values per
  cell up to a budget (default 5 million values a view), and above it the engine refuses exact with the size and keeps ≈.
- **Percentile and median.** Exact by **iterative selection**: the KLL sketch bounds the answer to a narrow value window; one more pass
  counts the values below the window (a mergeable count) and collects the few values inside it; the exact rank is found among them. Two
  passes on most data, a third when the window holds too many values; every pass is mergeable, so it scales across nodes. The answer
  says how many passes it took.
- **VaR and ES** are always exact (they are computed from vectors, never sketched).

### 5.4 Correctness Excel does not have

- **Non-additive totals are right**: by the rule above, a total is never the sum or average of the figures shown.
- **Currencies are explicit**: a money measure has a currency. A sum across currencies is converted at a named FX source and time
  (`currency.fx`), shown in the cell's provenance, or refused as "mixed currencies" with the list; never added raw.
- **Units are respected**: a percentage, a rate or a count of days is not summed; the catalogue marks a column's unit and the editor
  offers only the functions that make sense for it (a sum of a percentage is offered as an average, with a word why).
- **Masks before aggregation**: row rules and masks are applied in the compiled query before grouping; a masked column cannot be a
  dimension, and an aggregate-only role may set a **minimum number of members per cell** (default 1; a site may set 5) so a cell cannot
  reveal one trade.
- **No silent truncation**: the cell budget refuses with the count; top N always shows "of how many" and an Others row; a cell a shard
  could not answer says so (never a partial figure).
- **Exact or ≈, always shown**: every value carries its exactness; the column header shows the mix.
- **Floating point is ordered**: merges happen in a fixed order (shard order, then cell-key order), so the same question on the same
  data gives the same digits on every run and every node count.

## 6. The providers

### 6.1 The interface

```java
/** A measure provider: turns cell keys and functions into partial states over the data it owns (one interface for all). */
public interface PivotProvider {
    String id();                                         // tabular, var, frtb-sa, simm, ccr
    Catalogue catalogue(Principal p, SourceRef source);  // dimensions (typed, ordered), measures, functions it can evaluate natively
    Capabilities capabilities();                         // grouping sets, sketches, scope text, time travel, live, what-if, partials
    Axis axis(Principal p, PivotView view, String dimension, Budget b);   // the values a dimension takes under the filters
    Estimate estimate(Principal p, AggregationPlan plan);                 // bytes, rows, files, seconds per node (section 9.6)
    Partials evaluate(Principal p, AggregationPlan plan, Shard shard);    // partial states per cell key, for this node's part
    Rows drill(Principal p, CellKey key, Page page);                      // the member rows of a cell (drill-through)
    default Optional<String> scopeOf(CellKey key) { return Optional.empty(); }   // the cell as the provider's own scope text
    DataVersion version(SourceRef source, AsOf asOf);                     // the lake versions read; part of every cache key
}
```

An **aggregation plan** is the cell keys, the functions (built-in or custom, each with its kind and its state type), the filters, the
date, the as-of version and the lens. **Partials** are, per cell key and function, a serialised state (`PartialState`: a few bytes for a
sum, 4 KB for an HLL or a VaR vector) with the members counted and any note. The core merges partials across shards and finishes them.
A provider may also answer **final** figures for functions only it can finish (the regulatory formulas); the core then treats the cell as
holistic for that measure.

### 6.2 The tabular provider (DuckDB)

**What it reads:** any table a pack declares, through three routes, chosen per table:

| Route | When | How |
|---|---|---|
| the BI layers | a BI dataset table (the `cached`, `rollup`, `live` layers of [BI_GUIDE.md](../guides/BI_GUIDE.md#7-modes-layers-and-rollups)) | the same DuckDB connection and Parquet files BI reads; nothing copied |
| direct lake scan | large Delta or Iceberg tables (hundreds of GB and more), which must not be copied into the BI columns layer | DuckDB's `delta` and `iceberg` extensions over the lake's files (local disk, EFS, S3), with the table's version pinned |
| memory-only tables | tables on `mem://` | the server's Arrow reads, registered in DuckDB as Arrow views, DuckDB's temporary directory off (as BI does today) |

**How a view compiles.** One SQL statement per view, parameterised (values bound, never spliced), with the row rules and masks first:

```sql
SELECT DESK, date_trunc('month', TRADE_DATE) AS month,
       GROUPING(DESK, month)                    AS g,          -- which grouping set a row belongs to
       sum(NOTIONAL_USD)                        AS s0,         -- mergeable state of sum
       sum(PNL_1D), count(PNL_1D)               AS s1a, s1b,   -- avg as (sum, count)
       hll_registers(hash(COUNTERPARTY_ID), 12) AS s2          -- a sketch state (section 9.5)
FROM   lake_scan('trading.trades', version => ?) t
WHERE  <row rules> AND PRODUCT_GROUP IN (?, ?) AND TRADE_STATUS <> ?
GROUP BY GROUPING SETS ((DESK, month), (DESK), (month), ())
```

The provider returns **states**, not figures, so a node's answer can be merged with another node's, cached, and finished by the core.
Functions DuckDB has natively (`sum`, `count`, `min`, `max`, `avg`, `weighted_avg`, `var_samp`, `approx_count_distinct`,
`approx_quantile`, `quantile_cont`, `approx_top_k`) are used on a single node when the answer is final there; their **mergeable state
forms** (sums and counts, Welford parts, sketch states) are used whenever partials must be merged across nodes or cached. A
`direct` table (a PostgreSQL or DuckDB connector) gets the same SQL pushed to its source when every function is native to it.

**What it replaces.** BI's `matrix` visual, Analyse's Slice and the Pivot tab keep their YAML and their screens, and ask the pivot engine
instead of their own code paths (phase PB, [section 12](#12-bi-on-the-pivot-engine) and [section 22](#22-phases-and-sizes)); their totals already follow the true-totals rule, so their
figures do not change, which a test proves on every shipped sample report.

### 6.3 The VaR vector provider

The paused MR cube's `VarCubeSource` becomes this provider, unchanged in its arithmetic:

- **Members**: the positions (or trades) of the business date as the person sees them; a cell key maps to an `OrdinalSet` by the
  dimension partitions it already builds (desk, book, limit letter, any hierarchy level, any column of the vectors).
- **State**: the summed scenario P&L vector of the cell's members (and the stressed vector when asked), summed in the engine's fixed order;
  on a sharded date one call per owner for all of a view's cells (`VarBook.vectors`, `ShardedVectors.sumSets`, built on the branch).
- **Finish**: VaR, ES, stressed VaR at the lens's confidence and horizon; component VaR as a cell's share of the grand total's tail
  scenario; diversification shown beside each total (Σ cells − total).
- **Totals**: each total is its own set's vector, summed from members, so it equals `GET /api/v1/var/scope` for that scope to the bit
  (the branch's tests already prove it). Merging cells' vectors would be faster but equal only to the cent; it is not used for official
  figures (decision 3, [section 23](#23-decisions)).
- **Scope text**: a cell's scope in VaR's grammar (`DESK:RATES-NY & CCY:USD`) so any cell can be asked of the VaR engine itself.

### 6.4 The FRTB-SA and SIMM providers

State: the members' net weighted sensitivities summed per risk factor to the formula's grain (a sparse map factor → sum, added in shard
order); finish: the regulatory formula (bucket K_b, the three correlation scenarios, the charge; SIMM per CSA version). Each cell and
total runs the formula on its own members' sums. They come with the 2.9.11 FRTB-SA and SIMM engines
([SENSITIVITIES_AND_FRTB_SA.md](SENSITIVITIES_AND_FRTB_SA.md)) and need those engines on.

### 6.5 The CCR provider (later)

State per netting set: the path matrix of MtMs (paths × time steps), netted and collateralised **per netting set first** (non-linear), then
summed across netting sets per cell; finish: EE, EPE, PFE quantiles, EEPE; EAD from SA-CCR's formula provider. A netting set is never
split across shards. Built with the CCR cube ([CCR_CUBE_LENS.md](../lenses/CCR_CUBE_LENS.md)), in 2.9.12.

### 6.6 An external distributed backend (future, not built)

When one table is far beyond what a few Drishti nodes scan in minutes (tens of terabytes queried interactively by many people), a
**distributed SQL engine** (Trino, StarRocks or ClickHouse) can be a provider: the tabular provider's compiler emits its dialect, its
answers come back as mergeable states, and the core is unchanged. Not in this design's build; [section 9.10](#910-duckdbs-limits-stated-honestly)
says when it is needed, and decision 8 asks whether to keep it parked.

## 7. From the MR cube's core to the pivot engine: what is kept, what changes

The paused branch `wip/mr-cube` (at `17a4af0ea`; compiles; its fifteen server tests pass) holds the shared cube core in
`drishti-server/src/main/java/com/ash/drishti/server/cube/` (`CubeView` with the `MCUBE` text grammar, `CubeMeasure` with
`Kind {ADDITIVE, VECTOR, FORMULA}` and `needs`, `CubeDimension`, the `CubeSource<A>` and `Universe<A>` SPI on `OrdinalSet` members,
`CubeEngine` evaluating one batch of R × C + R + C + 1 sets with totals, diversification, the cell budget, busy protection and a
byte-weighed cache `cube.cells`, `CubeViews` for saved and shared views, `CubeExport`, `CubeController` at `/api/v1/cube`), the VaR
source (`VarCubeSource`, `VarCubeExtras`, `VarCubeController`) and the console's `cube-core.js`. The pivot engine is that code moved to
`com.ash.drishti.server.pivot` and changed as follows; the migration is the first step of phase P1.

| # | Today on the branch | In the pivot engine | Why |
|---|---|---|---|
| 1 | cells are materialised `OrdinalSet`s of members | cells are **declarative cell keys** (coordinates and filters); the tabular provider compiles them to one `GROUP BY GROUPING SETS`; the VaR provider maps keys to `OrdinalSet`s inside itself | a billion-row table cannot list its members per cell |
| 2 | SPI `Universe.aggregates(List<OrdinalSet>)` | SPI `evaluate(AggregationPlan)` returning partials per cell key; `aggregates(sets)` remains as one strategy, the VaR provider's | one interface for set-based and query-based providers |
| 3 | `CubeMeasure.Kind {ADDITIVE, VECTOR, FORMULA}` | measures are **functions** with start, add, merge, finish and a kind **mergeable, sketch, holistic**; totals merged from cell states when mergeable, recomputed only when holistic. Mapping: ADDITIVE → mergeable; VECTOR → holistic over a mergeable vector state; FORMULA → holistic | custom functions need the same contract as built-ins |
| 4 | shard partials inside the VaR source | **partial aggregation in the core**: every node returns partial states per cell key, the coordinator merges in a fixed order; VaR's vector sum is one such partial; `ShardGather.Combine` lifted from `drishti-server/.../shard` into the core | every provider scales out the same way |
| 5 | `CubeDimension` is a name and a label | **typed, ordered dimensions** (string, date, number buckets, hierarchy levels) from the provider's catalogue | grouping, sorting and Excel's date and number grouping |
| 6 | cache of aggregates keyed by the source's cache key and the set | cache of **partial states keyed by (provider data version, cell key, function)** | a changed filter or a new column reuses every cell still valid |
| 7 | `scopeOf` always expected | the cell's scope text is **optional**, a provider capability (VaR: its scope grammar; DuckDB: the `WHERE` clause shown in the provenance) | not every provider has a scope grammar |
| 8 | export writes `SUM` formulas for additive measures | export by **function kind**: Excel formulas only where a measure merges by plain sum (or is a ratio of sums); values otherwise; a native PivotTable for the mergeable ones ([section 15](#15-export)) | a formula that re-adds a VaR would be wrong |

**Kept as they are:** saved and shared views (`CubeViews`, now `PivotViews`: a shared view opens with the reader's entitlements, never
a copy of numbers), provenance ids per answer, the cell budget and the busy-protection check, the diversification view, the console's
grid (`cube-core.js` becomes `pivot-core.js`, the MR cube's landings keep their look), the `MCUBE` command word and its text grammar (it
becomes the MR cube's preset of the `PIVOT` grammar), the MR source's arithmetic and every test of the branch, which must pass
unchanged against the engine (the MR cube's acceptance test of phase P1).

**API compatibility:** `/api/v1/cube/{cube}` stays as the MR and CCR cubes' address, answered by the pivot engine with the provider set
of that cube; nothing calling it changes. The paused branch's own guide text (MR cube guide, API section) is rewritten on the engine
when the MR cube resumes.

## 8. Custom aggregation functions

### 8.1 The contract: start, add, merge, finish

Every function, built-in or custom, in any language, is four steps:

| Step | Called | Receives | Returns | Rule |
|---|---|---|---|---|
| `start(params)` | once per cell key per worker | the function's parameters (a trim fraction, a confidence) | an empty state | pure; no data |
| `add(state, batch)` | once per **columnar batch** (an Arrow record batch, 64K rows by default) of the cell's rows | the declared input columns only, masked as the reader sees them | the state, updated | vectorised over the batch; never row by row in Python |
| `merge(a, b)` | when two partial states of the same cell meet (two batches, two threads, two nodes, a cached cell and a new one) | two states | one state | associative and commutative, within the declared tolerance |
| `finish(state)` | once per cell, on the coordinator | the merged state | a number (or a small record: value, lower, upper, note) | pure |

A state is **serialisable** (Arrow or bytes, declared size bound); it crosses processes and nodes and lives in the cache. A function
declares its **kind**: mergeable (a fixed-size state and an exact merge), sketch (a bounded state and a merge with an error the author
states), or holistic (no `merge`: the engine gives it all the rows of a cell in one call, so it is size-bounded; [section 8.4](#84-holistic-functions-and-their-bounds)).

```python
# A mergeable custom function: the weighted mean of returns (state: two sums), vectorised over each Arrow batch.
from drishti_pivot import function, Mergeable
import pyarrow.compute as pc

@function(name="wavg_return", kind=Mergeable, inputs={"r": "float64", "w": "float64"}, state={"swr": "float64", "sw": "float64"})
class WeightedReturn:
    def start(self, params):          return {"swr": 0.0, "sw": 0.0}
    def add(self, s, batch):
        r, w = batch["r"], batch["w"]
        swr = pc.sum(pc.multiply(r, w)).as_py() or 0.0      # None for an all-null batch
        sw = pc.sum(w).as_py() or 0.0
        return {"swr": s["swr"] + swr, "sw": s["sw"] + sw}
    def merge(self, a, b):            return {"swr": a["swr"] + b["swr"], "sw": a["sw"] + b["sw"]}
    def finish(self, s):              return None if s["sw"] == 0 else s["swr"] / s["sw"]
```

### 8.2 Four languages, one contract

| Language | For | Where it runs | Kind it can be | Who may write it |
|---|---|---|---|---|
| **Formula of built-ins (DEL)** | ratios and combinations: `sum(pnl) / sum(abs(pnl))`, `share_of(...)`, a conditional sum | compiled into the provider's plan: SQL on DuckDB, Java on the VaR provider | the kind of its parts (a ratio of sums is mergeable) | any analyst with the authoring power |
| **SQL aggregate macro (DuckDB)** | anything expressible as SQL aggregates: `CREATE MACRO wret(r, w) AS sum(r * w) / sum(w)`; DuckDB treats a scalar macro that references an aggregate as an aggregate | inside DuckDB, on every node | mergeable when it is a formula of mergeable aggregates (the engine checks it by rewriting the macro into states), else holistic | authors; reviewed |
| **Python** | statistics no formula gives (a fitted slope, a robust estimator, a custom risk score) | a sandboxed **per-node worker pool** (`pivot-fn`, [section 8.5](#85-python-the-sandbox-and-the-worker-pool)), vectorised over Arrow batches | mergeable, sketch or holistic, as declared and proven by the harness | authors; approved before shared |
| **Java plug-in (SPI)** | production-grade functions a site maintains, fast and in process | in the server, loaded from an approved jar (sha256-pinned, as compute's Java libraries) | any | the site's developers; approved by an administrator |

The DEL route is the first one offered: most custom measures are formulas, and a formula is fast, safe and exportable. A function is
named `fn:<owner>/<name>@<version>` and used in a view like a built-in.

**Why not DuckDB's own extension points for user code.** DuckDB's C API has aggregate functions with init, update, combine and finalize
callbacks (the same four steps), but its Python API documents scalar functions only, and running user code inside the server's DuckDB
would put it in the server's process. Python therefore runs in the worker pool, fed Arrow batches by the provider; Java plug-ins run in
the server (reviewed code); SQL macros run in DuckDB (they are SQL, not code).

### 8.3 The harness: split-and-merge must equal one pass

A function is not saved, shared or used in production until its **harness** passes. The harness runs it on sample data (the author's
sample rows, then a seeded draw from the real table as the author sees it) in several ways and compares:

1. **one pass**: `start`, one `add` with all the rows, `finish`;
2. **batches**: the rows in batches of 1, 7, 1,000 and 65,536;
3. **splits and merges**: the rows split into 2, 3 and 16 random parts, each added separately and merged in random orders and tree
   shapes (left-deep, balanced), five seeds;
4. **empty and null**: an empty part, a part of nulls, a single row, all equal values, infinities and NaN in the inputs;
5. **state round trip**: every state serialised and read back before merging (as the cluster does).

It fails a **mergeable** function whose answers differ beyond the declared tolerance (default 1e-12 relative, or exact for integers and
decimals); a **sketch** function whose error exceeds its declared bound on the sample's exact answer; any function that is
non-deterministic for the same input order, too slow (over the cost limit per million rows), or whose state outgrows its declared bound.
The report shows each run, the largest difference and the run that produced it. A holistic function runs only the one-pass and empty
checks, and its size bound is shown.

### 8.4 Holistic functions and their bounds

A function without `merge` receives **all the rows of a cell in one call**, so it is bounded: a cell over `functions.holistic-max-rows`
(default 5 million rows) or `holistic-max-mb` (default 512 MB) is refused for that cell with the size, never truncated, and the editor
suggests a sketch or a mergeable form. Holistic functions never run on cluster partials: the rows of a cell are gathered to one node
(within the budget), and a view whose holistic cells would gather more than the budget is refused before any data moves.

### 8.5 Python: the sandbox and the worker pool

Python custom functions **reuse the sandbox BI's Python measures run in today** (the same guards, the same tests,
[BI_GUIDE.md, the sandbox](../guides/BI_GUIDE.md#7-the-sandbox-exactly)), hosted in the **compute worker pools** of the server's own node
([PYTHON_COMPUTE.md section 7](PYTHON_COMPUTE.md#7-the-worker-pool)) so the rows never leave the node that scanned them:

| Guard | How (unchanged from BI and compute unless said) |
|---|---|
| process | a worker per request (fork-server on Linux, warm and reset on Windows), `python -I`, an empty environment |
| no network | a network namespace with no interfaces where the machine allows it, and the audit hook refusing every socket |
| no files | no reads outside the Python installation; no writes at all for pivot functions (they receive batches and return states; no scratch writes) |
| no processes | the audit hook refuses `subprocess`, `os.system`, `exec*`, `fork`, `ctypes` |
| packages | the pool's allow-list (numpy, pyarrow, scipy, pandas, polars by default) and safe standard-library modules; `exec`, `eval`, `compile` not given |
| CPU, wall time, memory | per call and per view: `functions.python.timeout-seconds` (default 30 per batch, 300 per view), `memory-mb` (default 1,024) |
| inputs | only the declared input columns of the cell's rows, already filtered, ruled and masked for the reader |
| output | a state within its declared size; validated by the server before it is merged or cached |

**Transport.** Batches go to the worker as Arrow IPC over the worker's pipe or a shared-memory segment (`memfd` on Linux), not the
scratch-folder files compute uses for its engines, so nothing is written for a function call. **Memory-only stores**: user code is
**off by default per store** (`mem://` stores set `user-code: allow` to permit it), and when on, the data reaches the worker in memory only
(pipe or shared memory, never a file, never spilled); the never-on-disk guard checks it ([IN_MEMORY_LAKE.md](IN_MEMORY_LAKE.md)).

**Throughput.** Per node, the pool's worker count (configuration only) times the per-worker rate; a vectorised `add` over 64K-row batches
runs at numpy speed (tens to hundreds of millions of values a second per worker for simple arithmetic), so Python functions scale with
nodes and workers. A function that loops row by row is reported by the playground's cost line and slowed by its own design, not the
server's.

### 8.6 The playground for custom functions

**Pivot → Functions → New** opens the playground ([section 25](#25-mock-ups-the-screens-to-build), scene 2 of each mock-up):

- **Code** (DEL, SQL macro or Python), with the contract's four steps outlined and the declared kind, inputs, state and tolerance;
- **Sample rows**: pasted, typed, or drawn from a table the author may see (seeded, masked), shown as a grid;
- **States**: the partial state after each part and after each merge, the final value, side by side with the one-pass value;
- **The check**: the harness of [section 8.3](#83-the-harness-split-and-merge-must-equal-one-pass), run on every edit, green or red with
  the first difference;
- **Cost**: measured rows a second, memory per state, state bytes, and the projection on a real table ("≈3.2 s per billion rows on 3
  nodes × 8 workers");
- **Use it**: try the function in a pivot of the sample before saving; Save (private), Submit for approval (to share).

### 8.7 Versions, audit, approval and labels

- Every save is a new version `@n`; a view names the version it uses, so a function's change never changes a saved view's figures until the
  view's owner moves it to the new version (the view shows "a newer version exists").
- A function is **private** to its author until an administrator (or a delegated approver, never the author) **approves** it for a group
  or everyone, seeing the code, the harness report, the cost and the packages; approval is audited (who, when, version, reason), and
  revoking it makes views that use it say "function withdrawn" in the cell.
- Every figure from a custom function is **labelled** with the function's name, author and version in the column header and the
  provenance; the regulatory and risk engines' measures (VaR, ES, FRTB-SA, SIMM, SA-CCR, exposure) remain **Java engines**, and a custom
  function can never take an official measure's name or appear in an official report run or sign-off.
- The audit log keeps every run of a custom function in a view (who, which version, the data versions, the rows read, the time).

## 9. Scale: extremely large data

### 9.1 How a large pivot runs

1. **Plan.** The view becomes an aggregation plan: the axes first (distinct values under the filters, cheap), so the cell count is known;
   then the cell keys, the functions and their kinds.
2. **Estimate** ([section 9.7](#97-scan-estimates-before-anything-runs)). Bytes, files and seconds, from the lake's metadata, before any
   data is read. Over the confirm level, the person chooses (run, narrow, use a rollup, background).
3. **Route.** Aggregate awareness picks the smallest layer that answers every function of the plan exactly (a rollup, the BI columns
   layer, the lake); a plan partly answerable from a rollup is split.
4. **Scatter.** The files (or shards) to read are assigned to nodes; each node's executor compiles its part to one DuckDB query (or one
   vector batch) and returns **partial states per cell key**.
5. **Gather and merge.** The coordinator merges partials **in node order, then cell-key order**, so the digits are the same on every run.
6. **Finish, show, cache.** States are finished into figures; states (not figures) are cached by (provider data version, cell key,
   function), so the next view that shares cells reads them from the cache.

### 9.2 One DuckDB executor per node

Each node runs **one pivot executor**: a bounded pool of DuckDB connections (default one per four cores, `duckdb.connections`), each with
its `threads` and `memory_limit`, reading the files it is given. A node never answers more than its share; a slow node's files are
re-assigned (hedged) to an idle node holding the same data after `cluster.hedge-after-seconds`, and the first answer wins (both are equal
by construction). On a single server the executor is the whole engine.

### 9.3 Scatter and gather across Drishti\*net

The VaR engine's sharding ([VAR_SHARDING.md](VAR_SHARDING.md)) already adds partial vectors from shard owners in shard order, bit-equal to
the whole. The pivot engine lifts that into the core for every provider:

| Data placement | How files or shards are assigned | Example |
|---|---|---|
| shared storage (S3, EFS, a lake on a shared path) | any node may read any file; files are assigned by consistent hashing of the file path over the nodes that run the `pivot` engine, so each node's DuckDB and disk cache stay warm for the same files | a 3 TB trades table on S3 |
| node-owned data (VaR shards, a pack bound to a node, memory-only tables) | the owner computes its partials; nothing moves but partial states | VaR by `LEGAL_ENTITY` shards; a `mem://` table on its owner |
| a small table | one node, no scatter | reference data |

One request per node carries **all** of a view's cell keys (as the cube already batches per owner). A node that does not answer within
the deadline makes its cells say so (never a partial figure); the person may retry those cells alone.

### 9.4 Read less: pruning, skipping and layout

- **Column pruning**: only the columns of the plan (dimensions, measures' inputs, filters, row-rule columns) are read; Parquet is
  columnar, so a pivot of three columns of a sixty-column table reads a few percent of it.
- **Partition pruning and statistics skipping**: Delta keeps per-file minimum and maximum values in its log (for the first 32 columns by
  default), Iceberg in its manifests, Parquet per row group in its footer; filters on dates, desks or currencies skip whole files and
  row groups before reading them. DuckDB pushes filters into its Parquet reader and skips row groups by these statistics.
- **Layout by the dimensions people pivot by**: tables are partitioned by business date and **clustered** (Z-order on Delta, sort orders on
  Iceberg; liquid clustering where the Delta writer supports it) by the two or three dimensions most used as filters (desk, legal entity,
  product group), so statistics skipping works. The engine records which dimensions views filter by and the **layout advice** (phase P4)
  proposes a clustering with its expected saving, for an administrator to apply in Admin → Lakes.
- **The disk cache** of the lake readers keeps hot files local on each node.

### 9.5 Sketches

Distinct counts and percentiles are expensive exactly and cheap approximately. States the engine keeps:

| Sketch | State | How a node makes it | Merge |
|---|---|---|---|
| HyperLogLog (distinct count) | 2^p registers (p = 12 by default) | in SQL: `hash(x)` (64 bits); the first p bits pick the register, the rank of the first 1-bit in the rest is its value; `max(rank)` per (cell, register) | register-wise maximum: exact for the sketch |
| KLL (percentiles) | a summary of about 3k values (k = 200 by default) | in Java from the values DuckDB streams per cell, or (decision 6) DuckDB's `approx_quantile` per node turned into weighted points | KLL merge (Karnin, Lang, Liberty, 2016) |
| Space-Saving (top N) | 10 × n counters | DuckDB's `approx_top_k` (Filtered Space-Saving) per node with counts | counter merge with error bounds |

The stated error is printed with every ≈ value; on request the exact answer runs ([section 5.3](#53-exact-on-request-without-giving-up-scale)).
Rollups may hold sketch states, so a distinct count by desk and month comes from the rollup.

### 9.6 Rollups refreshed on load, chosen automatically

BI's rollups ([BI_GUIDE.md](../guides/BI_GUIDE.md#7-modes-layers-and-rollups)) store **components** of measures per date and group. The
pivot engine generalises them: a rollup stores **partial states** of any mergeable or sketch function (sums, counts, Welford parts, HLL
registers, KLL summaries) at its grain. Because the states merge:

- **Incremental refresh on load**: a load that appends files adds only the new files' states to the rollup (merge), instead of rebuilding
  it; a restatement or delete (a new Delta version that removes files) recomputes only the partitions it touched.
- **Aggregate awareness**: a plan is answered from the smallest rollup whose grain covers its dimensions, filters and row-rule columns and
  that holds every function's state; a coarser view merges the rollup's rows. The answer says which rollup answered.
- **Advice**: the engine counts the views that scanned the lake for want of a rollup ("asked 37 times this week, about 4 min each") and
  proposes one, as BI already does.
- Holistic functions are never rollup material; a rollup never holds VaR (the VaR engine's own per-node sums are its rollup).

### 9.7 Scan estimates before anything runs

```
 This pivot reads ≈180 GB (41,220 of 96,400 files after the date and desk filters; 6 of 58 columns), ≈2 min on 3 nodes.
 No rollup covers DESK × COUNTERPARTY × month.
 [ Run (≈2 min) ]  [ Narrow: last 3 months (≈18 s) ]  [ Use rollup by DESK × month, drop COUNTERPARTY (instant) ]  [ Run in background ]  [ Not now ]
```

Bytes come from the lake's metadata after pruning and skipping (file sizes and the column chunks' sizes in the Parquet footers); seconds
from the bytes divided by each node's **measured** scan rate (kept from recent scans, per storage kind) plus a per-file cost; always a
range, with how it was made, as BI's run cards do today. The confirm level is BI's (a minute or 500 million rows, settings). An unknown
size asks first.

### 9.8 Progressive answers: ≈ first, then exact

A view estimated over `progressive.after-ms` (default 2 s) answers twice: first an **approximate** answer within about a second,
from honest sources only (sketch states in a rollup that covers the view, or a **sample** of files chosen at random within each
partition, each mergeable figure scaled up with its 95% interval; never a coarser figure split by guessed proportions), marked ≈ with
its interval; then the exact answer replaces it cell by cell as nodes finish, with a progress line. Holistic measures (VaR) are
never sampled: they show "computing" until exact. The person may stop at the approximate answer.

### 9.9 Budgets, busy protection and the cache

- **Cell budget**: views over `max-cells` (default 20,000 visible cells, totals included) are refused with the count and the dimension
  that makes them large; holistic cells have their own budget (`max-holistic-cells`, default 5,000), as the MR cube today.
- **Busy protection**: every view passes the admission gate ([OPERATIONS.md section 12.5](../admin/OPERATIONS.md#125-busy-protection-refuse-politely-never-fall-over));
  views over `busy-cells` or estimated over a minute ask it first; a refusal is the gate's polite message with its retry time.
- **Partial-state cache**: in the one memory budget, weighed in bytes (`cache-mb`); keyed by provider data version, cell key and function;
  a new data version invalidates only the cells of the tables it changed.
- **Per-person fairness**: at most `max-running-per-person` large views at once (default 2); background runs queue as BI runs do.

### 9.10 DuckDB's limits, stated honestly

| Limit | What it means here | What the design does |
|---|---|---|
| **One process, one machine** | DuckDB itself does not distribute a query | Drishti scatters and gathers partial states ([section 9.3](#93-scatter-and-gather-across-drishtinet)); DuckDB is the per-node executor |
| **High-cardinality GROUP BY** | DuckDB spills grouping, joins, sorts and windows to its temporary directory when memory runs out, but some complex aggregate states cannot yet be offloaded, and a query with several blocking operators may still run out of memory (DuckDB's tuning guide) | the cell budget bounds the groups; states are kept small (mergeable kinds); holistic functions are size-bounded; the per-connection `memory_limit` is set, and an out-of-memory becomes a refusal in words |
| **Spill forbidden for memory-only data** | a `mem://` table may never touch a disk, so DuckDB's temporary directory is off for it | such views must fit in memory: over it they are refused, never spilled ([IN_MEMORY_LAKE.md](IN_MEMORY_LAKE.md)) |
| **Object-store bandwidth** | a scan of S3 is bounded by the node's network (a 10 Gbit/s link moves at most about 1.2 GB/s) | pruning and skipping first; the disk cache; more nodes for more bandwidth; the estimate uses the measured rate |
| **Concurrency** | DuckDB is not designed to run many small queries at once quickly (its tuning guide) | a bounded connection pool per node, queuing, the partial-state cache, rollups for dashboards, coalescing of identical plans |
| **Extensions** | the `delta` and `iceberg` extensions are separate files DuckDB normally downloads | shipped with the distribution and loaded from it (no download at run time, auto-install off); their versions pinned with DuckDB's |

**When this is not enough** (decision 8): interactive views over tens of terabytes by many people at once, where three to ten Drishti
nodes cannot answer in minutes. Then a distributed SQL engine (Trino, StarRocks, ClickHouse) becomes a provider
([section 6.6](#66-an-external-distributed-backend-future-not-built)). The benchmark of [section 21](#21-the-benchmark-plan) decides
when, on measured numbers.

## 10. Beyond anything out there

The product owner's bar (2026-11-02): "we need to do better than Excel and better than anything out there". Each item says how it
works, its phase ([section 22](#22-phases-and-sizes)) and its size. The most differentiating (1, 2, 3, 4, 9, 12, 13) are in the first
phases.

| # | Capability | How it works | Phase | Size |
|---|---|---|---|---|
| 1 | **Explain the difference between any two cells, or one cell over time** ("why is EMEA higher than last month") | for mergeable measures the difference is split **exactly** into contributions by member (the children of the cell along any dimension the person picks, each child's difference adding up to the total) and by cause where the provider knows causes (new members, members gone, value changes; for a price × quantity measure, the price and quantity effects in a fixed order with the cross term shown); for VaR, RO1's exact steps (restatement, hierarchy, trades out, window roll, market, trades in) per cell ([OVERSIGHT_LENS.md section 4.1](../lenses/OVERSIGHT_LENS.md#41-ro1-var-change-explanation)); shown as a waterfall beside the cell; steps always add up to the difference, and the residual (if any) is named | P2 | L |
| 2 | **Time travel and diff** | any view as of any business date and any **data version** (a Delta version or an Iceberg snapshot, `as-of`); **compare** two dates or versions side by side or as a diff grid with changed cells highlighted and new and gone members marked; each side's provenance names its versions | P1 | M |
| 3 | **What-if inside the pivot** | an **overlay**: exclude members (a book), scale or override an input column (a price, a weight, a notional), add hypothetical rows; the tabular provider joins the overlay into the plan, the VaR provider uses its what-if arithmetic (`VarWhatIfMath`); only affected cells recompute; the original stays beside the what-if with the difference; an overlay is private, saved with the view, never written to the lake | P2 | M to L |
| 4 | **Cell provenance and lineage** | every cell's popover: the data versions read, files or shards and nodes, rows counted, filters and row rules applied (in words), functions with their versions, the layer (rollup or scan), exact or ≈ with the error, time taken; one click to the rows (drill-through, paged, as the person may see them) | P1 | M |
| 5 | **Live pivots** | on streaming tables (BI's live tables, the Kafka PV stream, intraday files) the engine keeps **mergeable states per cell** and applies each batch as a merge (BI's `LiveAggregate` does this for rollups today); holistic cells recompute at most once per batch, coalesced; cells tick with a flash and a freshness age; a cell more than one batch behind says so | P3 | M |
| 6 | **Ask in words** | "VaR by desk and currency for last quarter, top 10" → the AI layer (when on) **proposes** a view definition; the person sees the definition (rows, columns, values, filters) before it runs, edits it, and nothing is hidden; the AI never computes a figure | P4 | S to M |
| 7 | **Automatic insights** | on request or as a toggle: outliers (robust z-score against the cell's peers), sudden changes (against the cell's own history), concentration (a member's share above a threshold), flagged in the grid with the reason in words; computed by the engine, never guessed | P4 | M |
| 8 | **Robust references to cells** | a stable name for a cell: `pivot://asha/desk-risk@4/Notional[DESK=RATES-NY, month=2026-10]` (view, version, measure, coordinates); survives re-sorting, new columns and a new date (it names coordinates, not positions); usable in DEL formulas (`pivot_ref(...)`), alerts, report cards and the Excel export (named ranges); a reference whose coordinate is gone says "not in the view", never 0 | P1 (names), P4 (formulas, Excel) | S, then M |
| 9 | **Correctness Excel lacks** | non-additive totals right, currencies converted explicitly, units respected, masks before aggregation, no silent truncation, exact or ≈ always shown ([section 5.4](#54-correctness-excel-does-not-have)) | P1 | M (in the core) |
| 10 | **Collaboration** | shared live views (the reader's entitlements), comments anchored to a cell's coordinates (survive re-sorts and new dates; [COLLABORATION.md](COLLABORATION.md)), **view versions** with a diff of definitions and restore | P2 | S to M |
| 11 | **Terminal-grade experience** | keyboard first (`PIVOT <GO>`, every action a key, the Bloomberg-bar standard), instant first answers on big data through progressive answers and rollups | P1 (keys), P3 (progressive) | S, then in P3 |
| 12 | **Expand and collapse, better than Excel** | nested rows and columns, lazy evaluation per expanded node, "expand by" a different dimension per member, ragged hierarchies, correct Others, inline drill to the rows ([section 11](#11-expand-and-collapse-nested-levels-better-than-excel)) | P1 (core), P2 (expand by, Others for holistic) | M, then M |
| 13 | **Pick-and-choose charts** | select cells, a row, a column, a range or a level and chart it; the chart is suggested from the selection's shape, linked both ways to the pivot, and never stacks a non-additive measure ([section 14.3](#143-pivot-charts-pick-and-choose)) | P1 (pick, suggest, link), P4 (Excel charts bound to the PivotTable) | M, then S |

## 11. Expand and collapse: nested levels, better than Excel

Product owner (2026-11-02): "In Excel pivot can also have expandable rows. We should have that capability too. Build something better
than Excel."

| # | What | How |
|---|---|---|
| 1 | **Rows and columns as nested levels** | every member of a row or column level has **+** and **−**; expand or collapse one, all at a level, or **expand to level N**; Excel's three layouts (**compact**: one indented column; **outline**: a column per level; **tabular**: a column per level with every label repeated) and **subtotals above or below** each group, per view |
| 2 | **Lazy evaluation** | only expanded nodes are computed: opening a node is a new plan of that node's children only (one `GROUPING SETS` query for the tabular provider, one vector batch for VaR), so a ten-level hierarchy over billions of rows opens as fast as one level; children already computed come from the partial-state cache; the **expansion state** (which nodes are open, by coordinates) is part of the saved view and of a link |
| 3 | **Expand by…, per member** | on any member choose a **different** dimension to break it down by, inline: EMEA by desk, APAC by currency, in the same grid; Excel's *Show Details* asks for one field once for the whole field; here each member keeps its own breakdown, and the totals stay right because each node is its own cell key |
| 4 | **Ragged and parent-child hierarchies** | hierarchies of uneven depth (VaR's MGMT and limit-letter trees, GLEIF ownership chains) and parent-child tables (a parent column), as well as hierarchies **built from columns** (division → desk → book); a member with no children has no +; a cycle in a parent-child table is refused at load with the members named |
| 5 | **Top N per level with a correct Others** | each level may show its top N by a measure with one **Others** row; Others' figure is computed from **its own members** (for VaR, the VaR of the members not shown, from their vectors), never total minus the top N, and says how many members it holds |
| 6 | **Sort within each level** | each level sorts independently (by label, by a measure, by a configured order); a sort never moves a child out of its parent |
| 7 | **Holistic measures right at every level** | a node's VaR is from its members' vectors, never the sum of its children; **diversification** (Σ children − node) shown on request as its own row or column |
| 8 | **Down to the rows, and back up** | the last level expands into the **member rows** (positions, trades) inline, virtualised (only the rows on screen are drawn; millions scroll), paged from the server as the person may see them; from a trade, **Show in hierarchy** opens the path from the top to it |
| 9 | **Keyboard** | `→` expand, `←` collapse, `Shift+→` expand all below, `Shift+←` collapse all below, `1` to `9` expand the whole axis to that level, `E` expand by…, `Enter` drill to rows; the Bloomberg-bar standard: every action has a key, shown in the help line |
| 10 | **Differences and time travel per node** | in compare mode each node shows its change and **Why** opens the explanation by its children ([section 10](#10-beyond-anything-out-there), item 1) |
| 11 | **Export keeps the outline** | the xlsx uses Excel's native row and column **outline levels** (grouping with + and −, summary rows above or below as on screen) and the native PivotTable keeps the same fields nested ([section 15](#15-export)) |

Phase P1 builds 1, 2, 4, 6, 7, 8 and 9 (size M); P2 builds 3, 5 (for holistic measures), 10 and 11 (size M).

## 12. BI on the pivot engine

Product owner (2026-11-02): the pivot engine is also BI's; there is no shadow mode and no old path kept: BI moves onto the engine where it
makes sense and the aggregation code it replaces is removed. BI's own design stays: datasets and data models (D1), one model per report
(D2), pack-generated datasets (D3), **MQ as the one way to ask a model for data** (D5), time modes (D6)
([BI_DESIGN.md section 14](BI_DESIGN.md#14-the-model-architecture-decisions-d1-to-d6)), its compiler of a model's rows (row rules,
masks, joins, layers, dates) and its security. What changes is **who aggregates**: one engine, one set of aggregation functions, the same
correctness. Drishti's BI is Power BI-like, not a replica: where a Power BI semantic would cost scale or reuse, the engine's design is
chosen and said here.

### 12.1 What is on the engine

| BI | On the pivot engine | Unchanged |
|---|---|---|
| a **matrix**'s totals and subtotals (every total row and column), a **table**'s total row (`format.totals`), a model query's `totals` (grand, subtotals, explicit levels) | every level in **one** `GROUP BY GROUPING SETS` statement of the engine's function states, over the model's rows as the reader may see them; each level from its own rows | the visual's YAML, wells and format; MQ's `totals` request and answer |
| the measures' additivity (`additive` in MQ's columns, the matrix's adding up) | the engine's own answer for the measure's function (`plainSum`: sums and counts added and subtracted) | an author's explicit `additive:` |
| the console adding up a matrix's additive totals | nothing: every total is the engine's | the matrix as drawn |
| a model's measures (DEL) | each measure that is an expression of aggregates of one fact's rows is a **pivot function** ([section 12.2](#122-a-measure-defined-once)) | the measure's name, text and the model's YAML |

What stays the model's own query: a visual's grouped cells (BI's compiler already computes them in one statement, with joins across facts,
filter contexts, time intelligence and windows), rollups and live aggregates (their state arithmetic moves with phase P3's rollups of
partial states), standing queries' incremental maintenance, and Analyse's Slice over an extract (to be a pivot view over the model with
P3).

### 12.2 A measure defined once

A model's measure is **a function**: `sum(trades.MTM)`, `avg(...)`, `min`, `max`, `count(trades)` (rows), `distinct(...)` (an exact
distinct count), `percentile(x, q)`, `ratio(a, b)`, `where=` conditions, arithmetic, row functions (`abs`, `round`, ...) and other measures
by `measure('Name')` translate to the engine's formula of built-ins, with its kind and states. A measure that reads beyond the rows of its
group is not a pivot function and has no total: time intelligence (`ytd`, `prior_period`, ...), running and analytic functions (a running
sum or a rank has no total that means anything), filter contexts (`calc`, `share_of`, ...), balances and Python measures. The answer says
so in its warnings ("no total for YTD: ...").

### 12.3 How a total is computed

BI's compiler writes the model's **detail** query as the reader (row rules, masks, joins along many-to-one paths, the layers and dates of
the question) and **does not run it**: it becomes the FROM item of the engine's `GROUPING SETS` statement (`QueryService.Outer`). The
statement runs in BI's engine, where the model's layers are, so no row leaves it and a billion-row model is totalled by one scan; the
engine reads each set's states (`fromSql`) and finishes them. A masked field is never grouped (that level is not made); a masked measure's
total is empty. A dataset read directly from its source (`mode: direct`) gets no engine totals (they would read every row back).

### 12.4 What changed for readers

- Totals of averages, ratios, distinct counts and percentiles: unchanged (they were already true totals, now made in one statement instead
  of one query per level).
- Totals of sums and counts: computed by the engine instead of added up in the browser (the same figures; a count is now a number with a
  decimal point in the JSON).
- A matrix without `format.subtotals` gets its grand total and row totals from the engine too.
- A total of a time-intelligence, running or context measure: none (a dash), with the reason in the answer's warnings.

### 12.5 When

Built in 2.9.11.b with phase P2 ([section 22](#22-phases-and-sizes)). Rollups of partial states and the Slice follow in P3.

## 13. The module: drishti-bicompute

Product owner (2026-11-02): "a holistic bi-compute maven module which can be used by any and all components of drishti".

### 13.1 What it is

`drishti-bicompute` is a new Maven module: a **plain Java 21 library, no Spring**, holding everything that aggregates. Its dependencies:
`drishti-api` and `drishti-common` and DuckDB (`duckdb_jdbc`, already in the build), plus, by decision 2, `arrow-format` (already used by
`drishti-compute`, for Arrow batches to Python workers) and the sketch library if one is chosen. **Rule: no other module re-implements
aggregation**; a module that needs a sum, a total, a sketch or a custom function calls this one. A build test (an architecture rule)
fails any new class outside it that implements the function SPI's steps or emits `GROUP BY` for a pivot.

### 13.2 Package layout and API surface

| Package | Holds | Main types |
|---|---|---|
| `com.ash.drishti.bicompute.core` | the pivot model, plans, the merge and finish | `PivotView`, `CellKey`, `Axis`, `AggregationPlan`, `PivotCore`, `PivotAnswer`, `Expansion` |
| `com.ash.drishti.bicompute.spi` | the provider SPI and the function SPI | `PivotProvider`, `Catalogue`, `Capabilities`, `Partials`, `AggFunction<S>` (start, add, merge, finish), `Kind {MERGEABLE, SKETCH, HOLISTIC}`, `StateCodec<S>` |
| `com.ash.drishti.bicompute.functions` | the built-ins | `Sum`, `Count`, `MinMax`, `Avg`, `WeightedAvg`, `Welford`, `FirstLast`, `ExactQuantile` |
| `com.ash.drishti.bicompute.sketch` | sketches and their codecs | `Hll`, `Kll`, `SpaceSaving` |
| `com.ash.drishti.bicompute.sql` | the grouping-sets compiler and the DuckDB provider | `GroupingSetsCompiler`, `DuckDbExecutor`, `TabularProvider`, `LakeScans` |
| `com.ash.drishti.bicompute.formula` | DEL: parser, checker, lowering, function catalogue (moved from BI) | `DelParser`, `DelChecker`, `DelLowering`, `FunctionCatalog`, `FormulaFunction`, `SqlMacroFunction` |
| `com.ash.drishti.bicompute.python` | the client side of the Python worker protocol | `PythonFunctionClient`, `ArrowBatchWriter`, `WorkerRequest` |
| `com.ash.drishti.bicompute.harness` | the split-and-merge test harness | `FunctionHarness`, `HarnessReport` |
| `com.ash.drishti.bicompute.cluster` | partial states over the wire, fixed-order merge | `PartialCodec`, `OrderedMerge` (from `ShardGather.Combine`) |
| `com.ash.drishti.bicompute.cache` | cache keys and weighing | `StateKey`, `Weigher` |
| `com.ash.drishti.bicompute.export` | xlsx (values, outline, native PivotTable, charts), csv, Arrow | `XlsxPivotWriter`, `PivotCacheWriter`, `CsvWriter` |

### 13.3 What moves into it

| From | What | Note |
|---|---|---|
| `wip/mr-cube`: `server/cube/` | `CubeEngine`'s pivot (axes, totals, diversification, budget check), `CubeView` and its text grammar, `CubeMeasure`, `CubeDimension`, `CubeExport` | changed as [section 7](#7-from-the-mr-cubes-core-to-the-pivot-engine-what-is-kept-what-changes) says; `CubeController`, `CubeViews` (storage), `CubeConfiguration` and the busy-gate wiring stay in the server |
| `drishti-server`: `server/bi/expr/` | DEL (`DelParser`, `DelChecker`, `DelAst`, `DelLowering`, `DelPrinter`, `DelType`, `FunctionCatalog`, `FunctionDocs`) | Spring-free today; the `web` part stays in the server |
| `drishti-server`: `server/bi/engine/` | `AnalyticEngine`, `DuckDbEngine`, `Dialect`, the aggregate and totals parts of `SqlCompiler` and `SqlEmitter`, `ArrowIpcWriter`, `LiveAggregate`'s state arithmetic | the security rewriter, layers, live feeds and BI's plan stay in the server and call the module |
| `drishti-server`: `server/shard/` | `ShardGather.Combine` (the ordered combine) | `ShardedMeasure` stays with VaR and implements the module's partial codec |

### 13.4 Who uses it

| User | How |
|---|---|
| `drishti-server` | wires providers, REST, entitlements, busy protection, the memory budget for the cache, the cluster transport; hosts the VaR, FRTB-SA, SIMM and (later) CCR providers, which implement the module's SPI (the vector arithmetic stays where it is) |
| BI | matrix visuals, totals, rollups and the semantic model's measures ([section 12](#12-bi-on-the-pivot-engine)) |
| the MR and CCR cubes | providers and landings on the core |
| the Data view, playgrounds, reports, the CLI | pivot views, function tests, exports |

## 14. The screens and pivot charts

### 14.1 The pivot screen

`PIVOT <GO>` (or **Views → Pivot**, or **Pivot** on any table, BI model or cube) opens the screen chosen from the mock-ups
([section 25](#25-mock-ups-the-screens-to-build)). Everything Excel's PivotTable does:

| Excel | Here |
|---|---|
| field list; drag to Rows, Columns, Values, Filters | the same four zones, plus the keyboard (`R`, `C`, `V`, `F` on a field) and the command line |
| summarise values by sum, count, average, max, min, product, count numbers, stdev, var | every built-in of [section 5.2](#52-the-built-in-functions) and every approved custom function |
| show values as % of grand total, column, row, parent row, parent column, parent total, difference from, % difference from, running total, % running total, rank smallest to largest and largest to smallest, index | the same list, each computed by the engine from figures (show-as transforms finished figures, so it never breaks a total) |
| calculated fields and items | calculated fields in DEL (over measures' states where mergeable, so they are right at every level, unlike Excel's calculated fields over sums); calculated items as named member groups |
| group dates (days, months, quarters, years) and numbers (by width) | typed grouping ([section 4.2](#42-dimensions-typed-and-ordered)), including fiscal calendars and edges |
| slicers and timelines | slicers and a date timeline, shared across pivots and charts on the page |
| subtotals, grand totals, layouts | [section 11](#11-expand-and-collapse-nested-levels-better-than-excel) |
| conditional formats | colour scales, data bars, icon sets and rules, on figures; a ≈ figure keeps its marker |
| double-click a value to see its rows | drill-through inline or in a side panel, paged, as the person may see them |
| refresh | live refresh, time travel, compare |

### 14.2 Phone tiers

As the cube ([MOBILE_DISPLAY.md](MOBILE_DISPLAY.md)): the top-left of the grid and the headline are **must**; charts **glance**; expand,
drill, explain and provenance **more**; the field list and the function playground **desk**.

### 14.3 Pivot charts: pick and choose

Product owner (2026-11-02): "And pick and choose charting as well."

1. **Pick and chart**: select cells, a row, a column, a range or a level, press `G` (or **Chart**): bar, stacked bar, line, area,
   **waterfall** (for explain and differences), heat map, **treemap and sunburst** (hierarchies), scatter, **histogram and distribution**
   (for vector measures: a cell's scenario P&L path), **sparklines in cells**, combo with a second axis.
2. **Suggested from the selection's shape**, overridable: time on an axis → line; parts of a whole (an additive measure over the children of
   one node) → stacked bar or treemap; one measure across members → bar; a change → waterfall; two measures over members → scatter; a
   vector measure in one cell → histogram with the VaR and ES lines.
3. **Linked both ways**: expand and collapse, filters, slicers, time travel and what-if update the chart live; clicking a bar or a
   treemap tile drills or filters the pivot.
4. **Correct**: a **non-additive measure is never stacked** as if its parts added up: the VaR of desks does not stack to the bank's VaR,
   so the chart shows the parts beside the total with **diversification as its own segment**, or refuses stacking in words; ≈ values are
   drawn hatched with their error bars; axes carry units and currency; mixed currencies are refused unless converted.
5. **Several charts** from one pivot pinned beside it, saved in the view, shared with it, **embedded in BI reports and Sutra pages**
   (they are BI visuals over the pivot's answer, drawn with the vendored ECharts and Vega-Lite libraries, nothing from a CDN), exported to
   Excel as **native charts bound to the PivotTable** (P4) and to PNG and SVG.
6. **Keyboard**: select with `Shift` and the arrows, `G` charts, `Shift+G` cycles the suggestions, `P` pins.

Phase P1 builds 1 to 4 and 6 with PNG and SVG export (size M); P4 binds charts to the native PivotTable (size S).

## 15. Export

Product owner (2026-11-02): "And data export to Excel and/or CSV." The **Export** menu (`X`, top right of the pivot screen; the same from
the API and `drishti.py pivot export`) asks two things: **what** and **how**.

### 15.1 What is exported, chosen by the person

| What | Contents |
|---|---|
| **The pivot as shown** | the visible cells with the outline (expanded levels, subtotals above or below as on screen), the show-as figures, the formats; collapsed nodes exported collapsed (Excel's outline keeps them openable) |
| **The underlying rows** | the drill-through of the whole view or of one cell: the member rows (trades, positions) with the columns the person picks, as they may see them |
| **The chart data** | the series behind a pinned chart, one column per series |
| **The view definition** | the view's YAML (section 4.1): to share, version or rebuild it elsewhere; no figures |

### 15.2 Excel (xlsx)

- **Values with formats**: one sheet for the pivot, numbers as numbers with the screen's number formats, dates as dates, the outline as
  Excel's native row and column **grouping levels** (summary rows above or below as on screen), conditional formats as Excel conditional
  formats where Excel has the same rule.
- **Formulas only where they are right**: a total of a measure that merges by **plain sum** is written as `SUM` of its cells (or a ratio
  of two such sums); everything else (VaR, a distinct count, a percentile, a custom function) is a **value** with a note in its cell's
  comment, "how this was computed" (the function, its version, exact or ≈ with the error).
- **A Provenance sheet**: the data versions read (Delta versions, Iceberg snapshots), the functions with their versions and authors, the
  filters, the row rules and masks applied (in words, never the hidden values), ≈ against exact per measure, who exported and when.
- **A native PivotTable** (phase P4): a real PivotTable with its **pivot cache** (the view's leaf cells as records, pre-aggregated by the
  engine, kept on a Data sheet so Excel's Refresh works offline) and a **pivot table definition** with the view's rows, columns and
  filters, written from the SpreadsheetML parts for PivotTables (`pivotCacheDefinition`, `pivotCacheRecords`, `pivotTableDefinition`;
  ECMA-376 Part 1, section 18.10). Only measures Excel re-aggregates correctly from records are data fields: sums, counts (as sums of a
  count field), minimums, maximums, and averages as a **calculated field** `sum_x / count_x`. Sketch, holistic and custom measures go to a
  Values sheet beside it, never into the PivotTable, with a note why. **Native charts** bound to the PivotTable come with it (P4).
- **Excel's limits, handled in words**: a worksheet holds 1,048,576 rows and 16,384 columns. Rows beyond it are **split over sheets**
  (`Rows 1`, `Rows 2`, ...) up to `export.xlsx-max-rows` (default 5 million); beyond that, or when a native PivotTable's records would not
  fit, the menu says so with the count and offers CSV, Parquet or Arrow instead, or a coarser grain. Never a silently cut file.

### 15.3 CSV

UTF-8 (with an optional byte-order mark for Excel on Windows), a chosen delimiter (comma, semicolon, tab), ISO dates and timestamps,
numbers unformatted (full precision, `.` as decimal point), a header row, one file per sheet (pivot, rows, provenance) in a **zip** when
there are several, and **gzip** for big files. The pivot as shown exports one row per cell (coordinates, measure, value, exact or ≈, error)
or the grid as shown, as the person picks.

### 15.4 Big exports

- **A size estimate first** (rows, columns, bytes, time), as for a query ([section 9.7](#97-scan-estimates-before-anything-runs)).
- **Streamed from the server**: rows go from DuckDB (or the provider) straight into the file being sent, in batches; no temporary file on
  the server for **memory-only data** (the never-on-disk guard), and none needed for the rest. A streamed answer is bounded by the
  server's async request timeout (`DRISHTI_ASYNC_TIMEOUT`, 30 minutes as shipped) and passes busy protection.
- **Large ones as a background job**: above the inline size (default 1 million rows or 200 MB), the export becomes a run with its card
  and a link when ready (as BI runs); the file is kept for `export.keep-hours` (default 24) under the data folder, unless the source is
  memory-only, when the job streams to the person's open download only and is refused as a stored file, in words.

### 15.5 Analysts: Arrow, Parquet and Power Query

**Arrow** (`Accept: application/vnd.apache.arrow.stream`) and **Parquet** files of the cells or the rows; **Power Query**: a REST endpoint
per saved view returning the cells, a personal token in the header, and a generated Power Query (M) text to paste into Excel or Power BI.

### 15.6 Who may export, and audit

Masks and entitlements are applied **before** export (the file holds what the screen showed this person, no more); an export of rows
needs the same right as drill-through; datasets marked internal only follow the Data view's rule. **Every export is audited**: who, the
view, what (pivot, rows, chart, definition), format, rows and columns, size, data versions; **never the values**.

### 15.7 Phases

P1: xlsx values with outline, formulas where right and the Provenance sheet; CSV; rows; chart data; the view definition; Arrow, Parquet,
Power Query; streaming, estimate, background job, audit (size M). P4: the native PivotTable and charts bound to it; references as named
ranges (size M).

```
drishti.py pivot export asha/desk-risk --what pivot|rows|chart|definition [--cell "DESK=EMEA"] --format xlsx|xlsx-pivot|csv|parquet|arrow [--delimiter ";"] [--gzip] [--wait] -o out.xlsx
POST /api/v1/pivot/export   { "view": "asha/desk-risk", "what": "rows", "cell": "c:7f3a…", "format": "csv", "csv": { "delimiter": ";", "gzip": true } }
```

## 16. The engine switch and its configuration

The `pivot` engine joins the catalogue of [COMPUTE_ENGINES.md](COMPUTE_ENGINES.md#2-the-engines): id `pivot`, file `pivot.yaml`,
needs nothing; `mr-cube` and `ccr-cube` **need** it; BI **uses** it once phase PB is done (until then BI does not depend on it). Off, the
engine does not exist on that server: `PIVOT`, the menu, Help pages, the CLI's `pivot` commands and the function playground disappear, and
the cubes cannot be on.

```yaml
# config/engines/pivot.yaml: the pivot engine (docs/architecture/PIVOT_ENGINE.md). Read at start, imported by application.yaml.
drishti:
  engines:
    pivot:
      enabled: ${DRISHTI_PIVOT_ENABLED:true}      # live: on and off without a restart
      changed: { by: "", at: "", reason: "" }
  pivot:
    max-cells: 20000                 # visible cells a view may have, totals included; over it: refused with the count
    max-holistic-cells: 5000         # cells of holistic measures (VaR, exact median, holistic custom functions)
    busy-cells: 2000                 # a view of more cells asks busy protection first
    cache-mb: 512                    # partial-state cache, weighed in bytes, in the one memory budget
    max-running-per-person: 2
    duckdb:
      connections: 0                 # 0: one per four cores
      threads: 0                     # 0: DuckDB's default
      memory-limit-mb: 4096          # per connection
      temp-directory: ${drishti.data.dir:./data}/pivot/spill   # never used for memory-only tables
      extensions-dir: ./lib/duckdb-extensions                  # shipped delta and iceberg extensions; nothing downloaded
    estimate:
      confirm-above-seconds: 60
      confirm-above-rows: 500000000
    progressive: { enabled: true, after-ms: 2000, sample-fraction: 0.02 }
    sketches: { hll-precision: 12, kll-k: 200, top-capacity-factor: 10, exact-distinct-budget: 5000000 }
    rollups: { incremental: true, advise-after-scans: 10 }
    cluster: { enabled: true, hedge-after-seconds: 20, deadline-seconds: 300 }
    aggregate-only: { min-members-per-cell: 1 }
    functions:
      formula: true                  # DEL formulas
      sql-macros: true
      require-approval: true         # sharing a function needs an approver who is not its author
      holistic-max-rows: 5000000
      holistic-max-mb: 512
      harness: { tolerance: 1.0e-12, seeds: 5, max-ns-per-row: 2000 }
      approver-role: pivot-approver  # members of this role approve; administrators always may
      python: { enabled: false, interpreter: "", workers: 2, timeout-seconds: 30, memory-mb: 1024, batch-rows: 65536 }
      java: { enabled: false, dir: ./plugins/pivot-functions }
```

Python custom functions never read memory-only data (decision 4: the source says `userCode: false` and the view is refused in words);
a per-store allowance is a later step. Every key is in [CONFIGURATION.md](../admin/CONFIGURATION.md) when built.

## 17. The API

| Call | Does |
|---|---|
| `POST /api/v1/pivot/query` | a view (JSON of [section 4.1](#41-the-view)) → the answer; `Accept: application/vnd.apache.arrow.stream` for Arrow; `?progressive=true` streams the ≈ answer then the exact one (server-sent events) |
| `POST /api/v1/pivot/estimate` | the scan estimate and the options, nothing read |
| `POST /api/v1/pivot/expand` | a node's children (lazy expansion: the view, the node's coordinates, the dimension to expand by) |
| `GET /api/v1/pivot/cells/{ref}` | one cell by reference ([section 10](#10-beyond-anything-out-there), item 8): value, exactness, provenance id |
| `GET /api/v1/pivot/cells/{ref}/rows` | drill-through, paged |
| `GET /api/v1/pivot/provenance/{id}` | the provenance of an answer or a cell |
| `POST /api/v1/pivot/explain` | the difference between two cells or two dates, as steps that add up |
| `POST /api/v1/pivot/compare` | two dates or versions, cell by cell |
| `GET, POST, PUT, DELETE /api/v1/pivot/views[/{owner}/{name}]` | saved views, versions, share, restore |
| `GET /api/v1/pivot/catalogue?source=` | dimensions, measures and functions a source offers this person |
| `GET, POST /api/v1/pivot/functions[/{owner}/{name}[@{v}]]` | custom functions: create, new version, list |
| `POST /api/v1/pivot/functions/test` | run the harness on sample rows: states, one-pass versus split-and-merge, cost |
| `POST /api/v1/pivot/functions/{owner}/{name}@{v}/approve` | approval (an approver, never the author), audited; `.../revoke` |
| `POST /api/v1/pivot/export` | an export: what (pivot, rows, chart, definition), format, options ([section 15](#15-export)) |
| `/api/v1/cube/{cube}` | the MR and CCR cubes' address, kept; answered by the pivot engine |

Errors are in words with a `DRS-` code assigned when built and listed in API_GUIDE's error code table.

## 18. The command line

```
drishti.py pivot query  --source trading.trades --rows DESK --cols month:TRADE_DATE --values "sum(NOTIONAL_USD)" --date 2026-10-30 [--csv|--arrow out.arrow]
drishti.py pivot estimate --view asha/desk-risk
drishti.py pivot views list | show asha/desk-risk | export asha/desk-risk --xlsx out.xlsx [--native-pivot]
drishti.py pivot explain --view asha/desk-risk --cell "DESK=EMEA" --from 2026-09-30 --to 2026-10-30
drishti.py pivot functions list | test fn.py --sample rows.csv | submit asha/trimmed_mean | approve asha/trimmed_mean@3 --reason "..."
```

The same words as the API; `--wait` for background runs; a refused view prints the same sentence as the screen.

## 19. Who sees what, and audit

- **Entitlements and masks before aggregation**: the provider applies the person's row rules and masks in the plan before grouping
  (BI's security rewriter for models; the VaR engine's scope entitlements for VaR). A masked column cannot be a dimension; a cell whose
  members the person may see only in aggregate refuses drill-through with the reason; `aggregate-only.min-members-per-cell` hides cells
  with fewer members.
- **A shared view** opens with the reader's entitlements; it never carries the author's numbers.
- **Custom functions** receive only declared columns of rows the reader may see; they cannot widen what a person sees.
- **Audit**: every view run (who, source, data versions, rows read, functions with versions, time), every export, every function save,
  test, approval and revocation.
- **Memory-only data**: never spilled, never exported to a file unless the store allows downloads (audited), user code off by default.

## 20. Tests that prove it

| Test | Proves |
|---|---|
| `GroupingSetsCompilerTest` | one query for cells and every total level; parameters bound, never spliced; row rules before grouping |
| `MergeOrderTest` | the same digits for the same question on 1, 2 and 3 nodes and any arrival order |
| `TrueTotalsTest` | every non-additive total equals the measure on the union of its members (avg, ratio, distinct, percentile, VaR) |
| `SketchErrorTest` | HLL, KLL and Space-Saving within their stated errors on seeded data of 10K rows (the local test size); exact on request equals exact |
| `ExactQuantileTest` | iterative selection equals a sort on the same data, across splits |
| `FunctionHarnessTest` | the harness fails a function whose merge is wrong, non-deterministic, too slow or too large, and passes the built-ins |
| `PythonFunctionSandboxTest` | no network, no files, no processes, the time and memory limits, only declared columns; nothing written for memory-only data |
| `VarProviderParityTest` | the paused branch's tests unchanged: every cell and total equal to `/api/v1/var/scope` to the bit, sharded and whole |
| `LazyExpandTest` | expanding a node computes only its children; the expansion state round-trips through a saved view and a link |
| `OthersTest` | Others equals the measure over its own members (VaR from vectors), not total minus top N |
| `CurrencyAndUnitsTest` | mixed currencies refused unless converted; percentages not summed |
| `MaskBeforeAggregationTest` | a masked column cannot be a dimension; aggregate-only minimum members honoured |
| `CellReferenceTest` | references survive re-sort, new columns and a new date; a gone coordinate says so |
| `ExplainAddsUpTest` | the steps of every explanation add up to the difference |
| `ChartStackingTest` | a non-additive measure is never stacked; diversification drawn as its own segment |
| `XlsxPivotTableTest` | the native PivotTable opens with the view's layout and its totals equal the engine's; non-mergeable measures are not data fields |
| `TrueTotalsTest`, `MqEngineTest`, `BiReportApiTest` (phase PB) | every BI total and subtotal from the engine's one statement equals the brute-force figure over the rows of its group |
| `FormulaFunctionTest`, `CustomFunctionsTest`, `test_pivot_fn.py` | formulas equal in Java and SQL; the harness gate, versions, approval, withdrawal, official runs; Python's four steps and sandbox |
| `PivotOffTest` | the engine switched off: 404 in words, the words gone everywhere, the cubes refused |
| browser test | the screen of the chosen mock-up: drag, expand, chart, provenance, playground; dark and light; phone width |

## 21. The benchmark plan

Results go to [BENCHMARK.md](../admin/BENCHMARK.md) when the runs are made (one benchmark document). Not run on a developer machine
(small local tests only); on the benchmark machines, one heavy job at a time.

| Run | Data | Nodes | Cache | Rollups | Views |
|---|---|---|---|---|---|
| B1 | 100 GB of synthetic trades (Parquet in Delta, about 1 billion rows, 40 columns, 5 years of business dates) | 1, then 3 | cold, then warm | without, then with `DESK × month × CURRENCY` | V1 sum by desk × month; V2 + distinct counterparties (≈ and exact); V3 + p99 of P&L (≈ and exact); V4 a 6-level expansion; V5 a Python mergeable function |
| B2 | 1 TB (about 10 billion rows) | 1, then 3 | cold, warm | without, with | V1 to V5 |
| B3 | 3 TB (about 30 billion rows), on S3 and on local disk | 3 | cold, warm | without, with | V1 to V5, and 20 people at once on V1 and V4 |

Measured: time to the first (≈) and the exact answer, bytes read against the estimate, the estimate's error, peak memory per node,
spill bytes, the cache's hit rate, the merge's share of the time, and the cost of each function per billion rows. The synthetic trades
come from the seeded generators (JSON Lines to Delta, as every pack's data), clustered by date and desk.

## 22. Phases and sizes

| Phase | What | Size | Proposed release |
|---|---|---|---|
| **P1: the core, the tabular provider, the built-ins and the screen** | the module `drishti-bicompute` with the core migrated from `wip/mr-cube` (section 7) and DEL moved from BI; cell keys and grouping sets; mergeable and exact holistic built-ins; the tabular provider (BI layers, lake scans, memory-only); the VaR provider moved; the partial-state cache, budgets, busy protection, the cluster merge; correctness (item 9); provenance (4); time travel and compare (2); cell reference names (8); expand and collapse (section 11, P1 part); the pivot screen of the chosen mock-up with show-as, grouping, slicers, conditional formats, drill-through; pick-and-choose charts (14.3, P1 part); exports xlsx values with outline, csv, Arrow, Power Query, PNG, SVG; the engine switch; API and CLI | XL (two large parts: the core and provider; the screen) | 2.9.11.a |
| **MR cube on the engine** | the paused branch resumed: its VaR source as a provider, its landings A, B and C on the pivot screen's grid, its tests unchanged; FRTB-SA and SIMM providers when those engines ship | L | 2.9.11.a |
| **P2: custom functions, explain, what-if** | the four languages, the harness, the playground, versions, approval, audit; Python in the per-node pool (shipped off); explain the difference (1); what-if (3); view versions and cell comments (10); expand by and holistic Others (section 11, P2 part) | L | 2.9.11.b |
| **PB: BI on the pivot engine** | section 12: BI's totals and subtotals in one engine statement over the model's rows, measures as functions, additivity from the engine; the old per-level queries and the console's adding up removed | L | 2.9.11.b |
| **P3: scale** | sketches (HLL, KLL, Space-Saving) and exact on request; rollups of partial states refreshed incrementally; aggregate awareness; progressive answers; scan estimates with options; scatter and gather of tabular partials across nodes with hedging; live pivots (5); the benchmark B1 to B3 | L | 2.9.11.c |
| **P4: Excel and advice** | the native PivotTable export and charts bound to it; references in formulas and Excel (8); Python functions on by default where approved; layout advice; ask in words (6); automatic insights (7) | M to L | unscheduled candidate |

## 23. Decisions

**All 14 decided by the product owner on 2026-11-02, as recommended; mock-up A picked.**

| # | Question | Decided (as recommended) |
|---|---|---|
| 1 | Placement: three releases, 2.9.11.a (P1 + MR cube), 2.9.11.b (P2 + BI), 2.9.11.c (P3), each with at most two large items | yes, as proposed; the MR cube right after P1, because it needs only the core and the VaR provider |
| 2 | The module's dependencies beyond `drishti-api`, `drishti-common` and DuckDB | allow `arrow-format` (already in the build) and Apache DataSketches (Apache 2.0, pure Java) for KLL; HLL written in the module (it is small, and computed in SQL) |
| 3 | VaR totals: recompute from members (bit-equal to the VaR engine) or merge cells' vectors (equal to the cent, faster) | recompute from members, as the MR cube does today |
| 4 | Python custom functions on memory-only stores | off by default per store; a store's owner may allow it; data passed in memory only |
| 5 | Sharing a custom function | an approver who is not the author, per group or everyone; audited; revocable |
| 6 | Percentile partials across nodes: KLL built in Java from streamed values, or per-node `approx_quantile` points merged | KLL in Java (a stated, tested error); revisit if the benchmark shows the stream costs too much |
| 7 | Progressive answers on by default for views estimated over 2 s | yes; sampled figures always marked ≈ with their interval; never for VaR |
| 8 | A distributed SQL backend (Trino, StarRocks, ClickHouse) | parked; revisit only if B3 shows common views over 5 min on 3 nodes |
| 9 | BI on the engine | in 2.9.11.b, a clean migration where it makes sense: no shadow mode, no old path kept (product owner, 2026-11-02: no users depend on the old one); BI's test suites pass, expected values changed only where the engine is more correct |
| 10 | Where the screen lives | `PIVOT <GO>`, Views → Pivot, and **Pivot** on every table, model and cube |
| 11 | The mock-up | A ([section 25](#25-mock-ups-the-screens-to-build)) |
| 12 | Aggregate-only roles: minimum members per cell | 1 as shipped (no change for anyone); a site may raise it |
| 13 | The native PivotTable carries only measures Excel re-aggregates correctly | yes; the rest on a Values sheet with a note |
| 14 | Custom functions in official runs | never; official measures stay Java engines |

## 24. Build checklist

- [x] `drishti-bicompute` module (no Spring; drishti-api, drishti-common, DuckDB); the architecture rule "no other module re-implements aggregation" is a build test (AggregationOwnerTest)
- [ ] core moved from `wip/mr-cube` with the eight changes of section 7 (built: changes 1 to 8 in the module; the branch's own tests run when the MR cube resumes on the engine)
- [ ] DEL moved from `server/bi/expr`; BI calls the module
- [x] function SPI, kinds, codecs; built-ins of section 5.2 except `first`/`last` and the sketches (P3); exact quantile from the values (selection with P3)
- [ ] grouping-sets compiler; DuckDB executor; tabular provider (built: the Data view's tables staged in memory, direct Delta and Iceberg lake reads of local Parquet files with pruning and skipping, a panel's rows; not yet: BI layers, object stores)
- [ ] VaR provider (built: `VarPivotProvider`, each cell the engine's scope; `VarCubeSource`'s batching, hierarchy levels and measures with the MR cube); FRTB-SA and SIMM providers with their engines
- [ ] partial-state cache, cell budgets, busy protection, per-person limits (built: all but the per-person limit)
- [ ] cluster: partial codec, ordered merge, file assignment, hedging, a node down said in the cell (built: state codecs and the ordered merge of local shards)
- [ ] correctness: currencies, units, masks, no truncation, exact or ≈ (built: masks before aggregation, no truncation, exact or holistic marked, currencies (explicit conversion, mixed refused per cell), units (rates and percentages never summed, units on headers))
- [x] provenance per answer and cell; time travel (by business date; data versions pinned with P3); compare; cell references
- [x] expand and collapse (section 11, P1 part and expand by), lazy, saved in views and links
- [ ] the screen (chosen mock-up), show-as, grouping, slicers, conditional formats, drill-through, keyboard, phone tiers (built: mock-up A, show-as in the API, grouping, slicers, conditional formats (colour scale, data bars), drill-through, keyboard; not yet: phone tiers)
- [ ] pivot charts (section 14.3), linked both ways, stacking rule (built: chart of a selection, the stacking rule, a click opens the member; not yet: pinned charts, PNG and SVG export)
- [ ] exports: xlsx values with outline, csv, Arrow, Power Query, PNG, SVG; later the native PivotTable and bound charts (built: xlsx with outline, formulas and Provenance, csv, Arrow, rows, definition)
- [x] custom functions: four languages, harness, playground, versions, approval, audit, labels; Python pool `pivot-fn` (the compute SDK's guard and Arrow codec)
- [ ] explain the difference; what-if overlays; view versions; cell comments (built: explain, what-if and view versions with a diff of definitions and restore; not yet: cell comments, what-if on VaR through the engine)
- [ ] BI on the engine (built: totals and subtotals of matrices, tables and model queries, measures as functions, additivity; not yet: models as pivot sources on the pivot screen, the Slice, rollups of states)
- [ ] sketches, rollups of states, incremental refresh, aggregate awareness, progressive answers, scan estimates, live pivots
- [ ] `config/engines/pivot.yaml`, the catalogue entry (`mr-cube` and `ccr-cube` need `pivot`), CONFIGURATION.md (built: `config/engines/pivot.yaml`, the catalogue entry, `mr-cube` needs `pivot`; `ccr-cube` when it is built)
- [x] API, `drishti.py pivot`, error codes in API_GUIDE
- [ ] tests of section 20; the benchmark of section 21 into BENCHMARK.md
- [ ] docs: a pivot guide and a tutorial (UI, CLI and YAML at every step), Help entries, a custom-function guide, the MR cube guide on the engine (built: the guide, tutorial 33, Help entries)

## 25. Mock-ups: the screens to build

Made 2026-11-02 for the product owner to choose; self-contained HTML (system fonts, everything inline, nothing from a CDN), dark by default
and light with `#light`, phone-safe. Each shows two scenes: **the pivot screen** (field list, drop zones, grid with ≈ and exact markers, a
holistic VaR column beside mergeable sums, nested expandable rows, a provenance popover, a scan estimate, a chart from a selection) and
**the playground for a custom aggregation function** (Python code, sample rows, one pass against split and merge, cost).

| Option | Idea | Screenshot |
|---|---|---|
| [A: Excel-familiar, field list on the right](mockups/pivot/A.html) (**recommended**) | the PivotTable people know: field list and four zones on the right, the grid with outline rows, the provenance popover on a VaR cell, the scan estimate above, a pinned chart beside it; the playground as three panes: code, sample rows, the check | [A.png](mockups/pivot/A.png) |
| [B: terminal first](mockups/pivot/B.html) | the command line writes the view (`PIVOT rows DESK cols month values ...`), zones as chips under it, explain and what-if in a right rail; the playground as a notebook of the four steps | [B.png](mockups/pivot/B.png) |
| [C: compare and insight canvas](mockups/pivot/C.html) | two dates side by side with changed cells highlighted and insights flagged; the playground as a four-step wizard | [C.png](mockups/pivot/C.png) |

**Why A:** nothing new to learn for anyone who has used Excel; everything beyond Excel (≈ and exact, VaR beside sums, provenance,
estimates, expand by, charts) sits where Excel's equivalents would be; B's command line and C's compare mode are both reachable from A (the
command line is always there; compare is a toggle).

## 26. Sources

- DuckDB, aggregate functions: `approx_count_distinct` (HyperLogLog), `approx_quantile` (T-Digest), `reservoir_quantile`,
  `approx_top_k` (Filtered Space-Saving), `weighted_avg`, `quantile_cont`, `median`, `mad`. https://duckdb.org/docs/stable/sql/functions/aggregates
- DuckDB, tuning workloads: larger-than-memory processing by spilling for grouping, joins, sorting and windowing; some aggregate states
  cannot yet be offloaded; not designed for many small concurrent queries; thread settings. https://duckdb.org/docs/stable/guides/performance/how_to_tune_workloads
- DuckDB, `CREATE MACRO`: a scalar macro that references an aggregate behaves as an aggregate. https://duckdb.org/docs/stable/sql/statements/create_macro
- DuckDB, Python functions: scalar UDFs, Arrow batches of up to 2,048 rows. https://duckdb.org/docs/stable/clients/python/function
- DuckDB C API aggregate functions (init, update, combine, finalize callbacks): `duckdb_create_aggregate_function`,
  `duckdb_aggregate_function_set_functions`. https://duckdb.org/docs/stable/clients/c/api
- DuckDB community extension `datasketches` (Apache DataSketches inside DuckDB; community maintained). https://duckdb.org/community_extensions/extensions/datasketches
- P. Flajolet, É. Fusy, O. Gandouet, F. Meunier, "HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm", AofA 2007
  (relative standard error about 1.04 / √m).
- Z. Karnin, K. Lang, E. Liberty, "Optimal quantile approximation in streams", FOCS 2016 (KLL).
- Apache DataSketches, KLL sketch: k = 200 gives a normalised rank error of about 1.65%; sketches merge. https://datasketches.apache.org/docs/KLL/KLLSketch.html
- T. Dunning, O. Ertl, "Computing extremely accurate quantiles using t-digests", 2019 (arXiv:1902.04023).
- A. Metwally, D. Agrawal, A. El Abbadi, "Efficient computation of frequent and top-k elements in data streams", ICDT 2005 (Space-Saving).
- T. F. Chan, G. H. Golub, R. J. LeVeque, "Algorithms for computing the sample variance", 1983 (merging variance states).
- ECMA-376, Office Open XML, Part 1, section 18.10 (PivotTables: pivot cache definition, records, pivot table definition).
- Microsoft, Excel specifications and limits (1,048,576 rows by 16,384 columns per worksheet). https://support.microsoft.com/en-us/office/excel-specifications-and-limits-1672b34d-7043-467e-8e27-269d656771c3
- Delta Lake, data skipping (per-file statistics, `delta.dataSkippingNumIndexedCols`, 32 by default). https://docs.delta.io/latest/optimizations-oss.html
