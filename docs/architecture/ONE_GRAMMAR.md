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
# One grammar, one engine: Rachana for every expression, the pivot engine for every aggregation

*Product owner, 2026-11-03: "I want all BI related functions including pivot to concentrate in one module and Rachana and Sutra use it
and other code can use it too"; "I want to ensure we are not coding the same functionality in more than one place and that we reuse what
we have". The design below is the recommendation the product owner accepted. Status: **designed, not built**; the build is planned in
2.9.11.b (parts 1 to 3 and 5) and in a release of its own after 2.9.11.d (part 4), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).*

**Read with:** [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) (the grammar), [PIVOT_ENGINE.md](PIVOT_ENGINE.md) (the engine,
section 6.7 for paths into documents), [BI_DESIGN.md](BI_DESIGN.md) (section 3.3, DEL).

## 1. What exists today, and why it must become one

Drishti computes with three overlapping languages and three implementations of the same aggregates:

| Where | What it does | Its aggregates |
|---|---|---|
| Rachana's expressions (`drishti-rachana`, package `rachana.el`: `Parser`, `ElCompiler`, `Functions`) | Sutra bindings, titles, filters: paths (`$.LEGS[0].INDEX.NAME`, `[*]`, `[?...]`), templates, links | its own `sum`, `min`, `max`, `size`, `flatten`, `group` over lists |
| DEL, BI's expression language (`drishti-server`, package `bi.expr`: `DelParser`, `DelChecker`, `DelLowering`, `FunctionCatalog`) | BI measures, derived columns, filters, row rules, in a Python-like syntax | BI's aggregates, lowered to DuckDB SQL |
| The pivot engine (`drishti-bicompute`, package `functions`: `Builtins`, `OncePerParent`, `spi.AggFunction`) | every pivot, its totals, the once-per-parent forms, sketches, custom functions (DEL, SQL, Python, Java) | the full catalogue, each with start, add, merge and finish |

A sum over a trade's legs can therefore be computed three ways, with three treatments of nulls, decimals, currencies, units and masks,
and written in two syntaxes. That is the dual machinery the platform rules forbid, and "one grammar" in name only.

## 2. The target

| Layer | Module | Owns | Never does |
|---|---|---|---|
| **The grammar** | `drishti-rachana` | the one expression language: paths, operators, filters, templates, links, and the scalar functions (`fmt`, `upper`, `days`, `coalesce`, `size` …); the parser and type checker every caller uses | implement an aggregate |
| **The aggregation engine** | `drishti-bicompute` | every aggregate function (built-in, once-per-parent, sketch, custom), grouping, pivots, totals, the providers; the one function catalogue with each function's documentation | parse a language of its own |
| **Measures with their own engines** | the compute engines (VaR, exposure, FRTB-SA, SIMM) | their figures, exactly; reached through their pivot providers and through `measure(...)` | be summed by anyone |
| **Callers** | Sutras, BI, the pivot screen, alerts, playgrounds, the API, the command line | they write Rachana and ask the engine | carry their own parser or aggregate |

**How Rachana reaches the engine without a cycle.** `drishti-rachana` keeps a function table (`rachana.el.Functions`) that is open to
providers found once at start (`ServiceLoader`, the same pattern as the lake's storage providers for `mem://`). `drishti-bicompute`
registers every aggregate into it. Rachana has no dependency on the engine; the engine depends on Rachana for paths and expressions.
Where the engine is not on the class path, an aggregate in an expression is refused with the reason, never computed another way.

## 3. What Rachana gains

Every function below has one implementation, in the engine, and one documentation entry, generated from the engine's catalogue.

| Function | Means | Example |
|---|---|---|
| `sum`, `count`, `avg`, `min`, `max`, `median`, `percentile`, `distinctCount` … over a list | the engine's built-ins on the list's values | `sum($.LEGS[*].NOTIONAL)`, `count($.EVENTS[?@.EVENT_TYPE == 'BOOKED'])` |
| `group(list, key, aggregate)` | grouping inside one document, the engine's grouping | `group($.LEGS[*], @.CURRENCY, sum(@.NOTIONAL))` |
| `measure(name, scope, options?)` | a computed measure from its own engine, exactly (never summed) | `measure('VAR', 'DESK:DESK-COMM')` |
| `cell(view, coordinates)` | a cell of a saved pivot view, linked back to it | `cell('desk-pnl', DESK: 'DESK-COMM', month: '2026-09')` |
| a **pivot panel** in a Sutra | rows, columns and values written as Rachana paths, answered by the engine | `pivot: { rows: [$.DESK], values: [sum($.LEGS[*].NOTIONAL)] }` |

Today's Rachana `sum`, `min`, `max` over lists and `group` keep their names and results; their implementation moves to the engine, and a
test proves every shipped Sutra draws the same figures before and after.

## 4. DEL folds into Rachana

BI's measures, derived columns, filters and row rules are written in Rachana expressions; DEL's explicit filter-context rules (BI_DESIGN
section 3.3.5) are carried over as Rachana functions, so no wrong-number semantics is lost. The move is a migration like BI's onto the
engine: every BI model, report, test suite and row rule is translated by a tool (`ExpressionMigrator` exists for DEL's own versions),
its figures and the rows each person sees are compared with those recorded from the current code, and then DEL's parser, checker and
lowering are deleted in the same release. Read-only SQL over models (DSQL) and Python custom functions stay: SQL is a query language, not
a second expression syntax, and Python is the open-ended escape, sandboxed. Size L, designed in detail before it is built.

## 5. Guards, so it stays one

- An architecture rule (`drishti-it`): no class outside `drishti-bicompute` implements an aggregate over values (the rule names the
  engine's SPI as the only way), and no module other than `drishti-rachana` parses an expression.
- A console check: no console Python or JavaScript sums, averages or groups risk or BI figures (the server computes; the console
  displays), listed exceptions only for layout arithmetic.
- The function catalogue is one generated page (Help → Functions, `FUNC`), listing each function once with where it can be used.

## 6. The order of work

| Part | What | Size | Release |
|---|---|---|---|
| 1 and 2 | Rachana's function table open to providers; the engine registers its aggregates; Rachana's own list aggregates removed; parity test on every shipped Sutra | S to M | 2.9.11.b |
| 3 | `measure`, `cell`, `group` on the engine, the Sutra pivot panel in paths, the catalogue page | M | 2.9.11.b |
| 5 | the guards | S | 2.9.11.b |
| 4 | DEL folded into Rachana, its parity proof, DEL deleted | L | a release of its own after 2.9.11.d, designed first |
