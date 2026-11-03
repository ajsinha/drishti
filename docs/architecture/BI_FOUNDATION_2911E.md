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
# BI's foundation (2.9.11.e): DEL into Rachana, and virtual tables

*Status: **design; part B's foundation built** ([section 9](#9-as-built-part-b-foundation)); the switch not yet. **Decided by the product owner on 2026-11-03**: every recommendation of section 8 accepted, except Q1, decided the other way (Rachana's null rule, below). Earlier wording: a design draft for discussion, nothing decided beyond what the documents it reads already
record as decided (the release and its scope, product owner 2026-11-03; [VIRTUAL_TABLES.md](VIRTUAL_TABLES.md) V6;
[ONE_GRAMMAR.md](ONE_GRAMMAR.md) part 4). Every command, path, setting, key and error code below that does not exist today is
planned and says so on its line. Section 8 lists the questions, each with a recommendation. Nothing is coded until the discussion is
finished.*

**Read with:** [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (2.9.11.b rows PB, OG and SC; 2.9.11.e; "Virtual tables"),
[VIRTUAL_TABLES.md](VIRTUAL_TABLES.md), [ONE_GRAMMAR.md](ONE_GRAMMAR.md), [FUNCTION_CATALOGUE.md](FUNCTION_CATALOGUE.md),
[BI_DESIGN.md](BI_DESIGN.md) (section 3.3, DEL; section 15, the model layer; MQ), [PIVOT_ENGINE.md](PIVOT_ENGINE.md) (sections 6.7
and 12), [LAKE_CONFIG.md](LAKE_CONFIG.md), [ARROW_FLIGHT.md](ARROW_FLIGHT.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md)
("Rachana-EL expressions"), [BI_GUIDE.md](../guides/BI_GUIDE.md) (sections 23 and 30).

## Contents

1. [The goal in one page](#1-the-goal-in-one-page)
2. [DEL into Rachana](#2-del-into-rachana)
3. [Datasets and models into virtual tables and models](#3-datasets-and-models-into-virtual-tables-and-models)
4. [The one migration tool](#4-the-one-migration-tool)
5. [The one parity proof](#5-the-one-parity-proof)
6. [Arrow Flight, client notebooks, Help and docs](#6-arrow-flight-client-notebooks-help-and-docs)
7. [Risks, size, order of work, drills](#7-risks-size-order-of-work-drills)
8. [Decisions](#8-decisions-product-owner-2026-11-03-every-recommendation-accepted-q1-decided-as-rachanas-rule)
9. [As built (part B foundation)](#9-as-built-part-b-foundation)

## 1. The goal in one page

**Where BI ends.** After 2.9.11.e, BI (Rūpaka) owns only its own job: **reports, visuals, the designer, boards, publishing,
subscriptions and alerts' presentation**. Everything it computes or defines underneath belongs to the platform, once:

| Concern | Before 2.9.11.b | After 2.9.11.b (PB, OG) | After 2.9.11.e (this design) |
|---|---|---|---|
| Aggregation, totals, rollups | BI's SQL compiler and its own totals | the pivot engine (`drishti-bicompute`) | unchanged: the pivot engine |
| Expressions (measures, derived columns, filters, row rules) | DEL (`server.bi.expr`), with `ExprParser`, `CalculationParser`, `RowEval` behind it | DEL still, listed as an exception in `OneGrammarRulesTest` | **Rachana**, checked and lowered by one compiler; DEL deleted |
| Tables (datasets) | BI datasets (`config/bi/datasets`, stored items) | the same | **virtual tables** in a central layer (planned module `drishti-tables`, files `config/tables/`, planned) |
| Models (relationships, measures, hierarchies, model security) | BI models | the same, answered by the engine | **the layer's models**; MQ stays the query contract, answered by the layer |
| Cached, rollup and live layers | BI's `Layers`, `Rollups`, `LiveTables` | rollups of partial states (P3) | the layer's **materialisation** (moved, not copied) |
| Who reads tables | BI only | BI, the pivot (through BI's plan) | BI, the pivot, Arrow Flight (`table` and `model` forms), alerts; later the Data view, Sutras, the AI |

**Two large items, one migration, one proof.** (1) DEL folds into Rachana: Rachana gains the checked mode, the scopes and the
filter-context functions DEL has; DEL's parser, checker and lowering are deleted. (2) BI's datasets and models become virtual tables and
models of the central layer; BI reads them like any consumer. One tool rewrites every dataset as a virtual table **and** every
expression from DEL to Rachana in one pass; one parity proof (every BI suite and shipped report the same figures, every person the same
rows and masks) covers both, against fixtures recorded from today's code before anything moves.

**What does not change.** MQ (`mq: 1` and `mq: 2`) as the one way to ask a model; the four filter-context layers (security, query, group,
measure) and the rule that security is never in scope; many-to-one joins, per-fact aggregation and the fan-out refusal; the currency
guard; masks before aggregation; item governance (workspaces, review, four-eyes, versions, stable ids, semver contracts); DSQL (a query
language, not an expression language) and Python items (the sandboxed escape). A report's YAML changes only where it holds an
expression or a dataset name.

**Picture.**

```
  reports · visuals · designer · boards · publishing        ← BI (Rūpaka): presentation only
                         │ MQ
  ┌──────────────────────┴───────────────────────────────┐
  │ the table layer (planned drishti-tables)             │  catalogue · virtual tables · models · checks · materialisation
  │   expressions: Rachana (drishti-rachana, checked)    │  one parser, one checker, one lowering
  │   aggregation: the pivot engine (drishti-bicompute)  │  one function catalogue
  └──────────────────────┬───────────────────────────────┘
        kinds through connectors · lake tables · the in-memory lake · other tables
  consumers beside BI: the pivot · Arrow Flight (table, model) · alerts · later the Data view, Sutras, the AI
```

## 2. DEL into Rachana

### 2.1 Two languages today, and where they differ

DEL ([BI_DESIGN.md section 3.3](BI_DESIGN.md#33-del-the-drishti-expression-language-python-like)) is a typed, Python-shaped language
compiled to SQL; it **refuses** what is ill-typed. Rachana-EL is a total, untyped language evaluated in Java over documents; it
**never throws** and makes something of anything. The fold keeps one grammar (Rachana's) and gives it DEL's discipline where types are
known. The differences that matter, each settled below:

| Point | DEL | Rachana today | After the fold (recommendation) |
|---|---|---|---|
| Booleans | `and`, `or`, `not`; booleans only | `&&`, `\|\|`, `!`; truthiness of any value | Rachana's spelling; in **checked mode** booleans only (Q4) |
| Conditional | `a if c else b` | `c ? a : b` | `c ? a : b` (and `if(c, a, b)`, tier 1) |
| Null literal and test | `None`, `x is None` | `null`, `x == null` | `null`, `x == null` |
| `!=` against null | false (SQL's rule: a comparison with null is false) | `null != 'X'` is **true** (empty equals only empty) | **Rachana's rule everywhere** (Q1, decided); the migrator rewrites DEL comparisons to keep BI's figures |
| Membership | `x in [..]`, `not in`; empty list holds nothing | no `in`; `oneOf(x, list)` where an empty list holds **everything** | a strict `in` / `not in` operator; `one_of` stays a control function (Q3) |
| Lists | literals `[a, b]` | none | list literals (needed by `in`, `by=`, `ignore=`) |
| Keyword arguments | `where=`, `by=`, `ignore=`, `keep=`, `via=`, `over=`, `desc=`, `to=`, `at=`, `unit=`, `period=`, `hierarchy=` | none | named arguments `name = expr`, as FUNCTION_CATALOGUE already writes them |
| Fields | `alias.column[.datepart]`, `user.x`, `param.x`, `fn.x(...)` | `$`, `@`, `#index`, `ctl.x`, bare names | `$`, `@`, `^` (parent element, Q8), table aliases in a model, `user.x`, `param.x`; `fn.x` stays reserved |
| Chained comparison | `0 < x <= 10` | parses as `(0 < x) <= 10` | refused by the checker (a boolean compared with a number); the migrator writes `0 < x && x <= 10` |
| Power, floor division | `**`, `//` | none | `power(x, y)`, `floor(x / y)` |
| Types | integer, decimal, string, boolean, date, timestamp; no implicit casts | text that reads as a number is a number; `+` joins text | checked mode: DEL's types and rules; unchecked (Sutras over untyped documents): unchanged (Q2) |
| Text functions on null | `upper(None)` is None | `upper(empty)` is `''` | null in, null out (catalogue rule; Sutras show the same) |
| Masked values | `•••` in row scope; aggregates null; a filter, sort or `in` on one is refused (`DRS-9161`) | `•••` carries through; a condition on it is never true | checked mode keeps DEL's refusal (a filter on a hidden value must not be silently answered) |
| Limits | 2,000 characters (4,000 in block form), depth 64 | 10,000 characters, depth 200 | Rachana's limits |
| Comments | `#` to end of line in block scalars | none | none: the migrator moves a comment to a YAML comment on the line above |

**Checked mode** is Rachana's own type checker, the port of `DelChecker` into `drishti-rachana` (ONE_GRAMMAR section 2: Rachana owns
"the parser and type checker every caller uses"). It runs wherever the types are known (a virtual table's source contract, a model's
tables, a pivot over a typed source) and refuses with DEL's codes, kept: `DRS-9127` (type or scope), `DRS-9129` (not yet), `DRS-9152`
(unknown name), `DRS-9161` (masked field filtered). It is not a second language: the same text means the same thing in both modes;
checked mode only refuses more.

### 2.2 Scopes, in Rachana

DEL's four scopes ([BI_DESIGN.md 3.3.1](BI_DESIGN.md#331-scopes-row-rule-aggregate-context)) become Rachana's, decided by where the
expression is written:

| Scope | Written in | Names in scope | Aggregates |
|---|---|---|---|
| **row** | a virtual table's column `expr`, its `filter`, `keep:` | the source: `$` (the entity or source row), `@` (the grain's element), `^` (the element's parent); `param.x` | refused (`DRS-9127`: "an aggregate belongs in a measure") |
| **rule** | a virtual table's `rules[].where`, a model's `security.rows.<alias>[].where` | as row (a table) or the alias's columns (a model), and `user.x` | refused; a rule is **never removable** by any function |
| **aggregate** | a model's `measures.*.expr`, MQ `define` and `having`, a report's local measures | `alias.COLUMN` **inside** an aggregate, `measure('name')`, `param.x` | yes; a bare column outside an aggregate is refused with "wrap it: `sum(trades.MTM)`" |
| **context** | the first argument of `calc`, `share_of`, `parent_of`, `as_of` | as aggregate | yes, in an edited filter context (2.3) |

A **document path inside a measure is refused** (`sum(trades.LEGS[*].NOTIONAL)`): paths belong in a table's columns, so a figure is
defined once and every consumer reads the same column (Q21). `ctl.x` (Sutra controls), `link(...)` and `fmt(...)` are display concerns
and are refused in tables and models (a column or a measure has a `format`).

### 2.3 The filter-context algebra, carried over exactly

The semantics of [BI_DESIGN.md 3.3.5](BI_DESIGN.md#335-filter-context-calc-designed-now-built-in-b) move word for word into Rachana's
function catalogue entries; the implementation moves from `server.bi.engine.ContextFunctions` and `Contexts` into the engine
(`drishti-bicompute`), which already owns grouping sets and sub-plans. A measure is evaluated per group of the answer in four layers:

```
  1. SECURITY  the reader's row rules and masks per table          fixed: no function can touch it
  2. QUERY     MQ where, report, page and visual filters, slicers, drill, cross-filter
  3. GROUP     the current group's values of the answer's fields
  4. MEASURE   the measure's own where= conditions and calc() edits
```

| Rachana | Meaning | Compiles to (unchanged) |
|---|---|---|
| `calc(agg, where = cond)` | add conditions (AND) to layer 4 | `FILTER (WHERE …)` on the aggregate |
| `calc(agg, ignore = [fields])`, `calc(agg, ignore = ALL)` | drop layer 2 and 3 filters on those fields, or on all | a grouped sub-plan at the coarser grain, joined back on the remaining fields |
| `calc(agg, keep = [fields])` | drop every layer 2 and 3 filter except those on the fields | `ignore` with the complement |
| `calc(agg, via = 'r_5')` | follow an inactive relationship (the active one for the same pair off) | relationship substitution in the sub-plan |
| `share_of(agg, over = [fields] \| ALL)` | `agg / calc(agg, ignore = over)` | as `calc`, then a division (null on 0) |
| `parent_of(agg, hierarchy = 'name')` | `agg / calc(agg, ignore = [the deepest level of the hierarchy in the answer and the levels below])` | as `calc` |
| `as_of(agg, date)` | every scan bound to another business date, joined on the answer's fields | a second plan at that date; the date worked out before the query runs |

**The rules, unchanged:** (1) security is never in scope, so `ignore = ALL` means everything **you** may see; (2) closed and
compositional, nesting of `calc`, `share_of`, `parent_of` and `as_of` at most three deep; (3) **no context transition**: iteration is
explicit (`avg_by`); (4) every sub-plan keeps grain safety and the fan-out refusal; (5) the incremental class of a standing query is
derived per function; (6) calculation-group items are templates in the same syntax with `SELECTED` standing for the measure, expanded at
bind time; parameters bind as `param.name` and are part of the cache key; (7) `as_of`'s date may not depend on a row (`DRS-9166`) and a live
table has no other dates (`DRS-9157`). `ALL` stays a reserved word of checked mode.

**Not the same as the pivot's "% of".** `share_of` and `parent_of` drop filters (DAX's `ALL`): a book's `share_of(…, over = ALL)` is of
everything you may see, whatever the slicers say. The pivot's `pct_of_total`, `pct_of_row`, `pct_of_column` and `pct_of_parent`
(FUNCTION_CATALOGUE section 5, tier 1) are of the **visible** totals of the answer (Excel's "Show values as"). Both are useful and they
answer differently under a slicer, so they keep different names; FUNCTION_CATALOGUE's line that calls `pct_of_parent` "built (DEL
`parent_of`)" is corrected (Q6).

### 2.4 Every DEL construct, mapped

| DEL construct | Rachana form | Note |
|---|---|---|
| `a and b`, `a or b`, `not a` | `a && b`, `a \|\| b`, `!a` | booleans only in checked mode |
| `a if c else b` | `c ? a : b` | right-associative in both; nested chains stay nested |
| `0 < x <= 10` | `0 < x && x <= 10` | the migrator expands chains |
| `==`, `!=`, `<`, `<=`, `>`, `>=` | the same | null rule of Q1 |
| `x in [a, b]`, `x not in [a, b]` | `x in [a, b]`, `x not in [a, b]` | strict (Q3) |
| `x in user.desks` | `x in user.desks` | an empty attribute list holds nothing: no rows |
| `x is None`, `x is not None` | `x == null`, `x != null` | lowered to `IS NULL`, `IS NOT NULL` |
| `True`, `False`, `None` | `true`, `false`, `null` | |
| `+ - * / %` | the same | `/` and `%` by zero are null in both |
| `x ** y`, `x // y` | `power(x, y)`, `floor(x / y)` | `power`, `floor` arrive with the fold (tier 1 functions the migration needs) |
| `-x`, `+x` | `-x`, `x` | |
| `'text'`, `"text"` | `'text'` | the migrator re-quotes; Rachana's `\` escapes the next character |
| `12`, `1.5`, `1e6`, `1_000_000` | `12`, `1.5`, `1e6`, `1000000` | Rachana has no `_` in numbers |
| `[a, b]` | `[a, b]` | new in Rachana |
| `alias.column` (a model) | `alias.COLUMN` | aliases are names in a model's scope |
| bare `column` (a dataset's row scope) | `$.PATH` of that column (or `@.PATH`, `^.PATH`) | a table's expressions read the source; the migrator substitutes each column by its path (3.2) |
| `alias.column.year` (and `.quarter`, `.month`, `.week`, `.day`) in an expression | `year(alias.COLUMN)` … | in MQ field lists and hierarchies `trades.TRADE_DATE.month` stays a **field name** of the model |
| `user.attr` | `user.attr` | rule scope only |
| `param.name` | `param.name` | |
| `fn.name(...)` | `fn.name(...)` | reserved; a functions library is not built |
| `measure('name')`, `measure('m_id')` | `measure('name')` | inside a model a one-argument `measure` is the model's; an engine's measure takes its scope (`measure('VAR', 'DESK:DESK-COMM')`) |
| `SELECTED` (calc-group template) | `SELECTED` | |
| `name=value` keyword argument | `name = value` | |
| `ALL` | `ALL` | |
| `# comment` | a YAML comment above the key | |

### 2.5 All 59 functions of `bi/expr/functions.yaml`, mapped

**R** runs in Rachana (a scalar); **E** in the engine (an aggregate, window or context function; mergeable or holistic as its catalogue
entry says). "Same" means the name and arguments are unchanged apart from the keyword spelling of 2.4. Every row keeps DEL's edge cases
(nulls, empty, masked, currencies, types) as its catalogue entry's edge cases.

| # | DEL | Family | Rachana | Runs | Note |
|---|---|---|---|---|---|
| 1 | `abs(x)` | scalar | same | R | exists in Rachana |
| 2 | `round(x, n=0)` | scalar | `round(x, n)`, `n` defaults to 0 | R | half away from zero, as the database rounds |
| 3 | `coalesce(a, b, …)` | scalar | same | R | Rachana's 8-argument cap raised to DEL's unbounded |
| 4 | `upper(s)` | scalar | same | R | null in, null out |
| 5 | `lower(s)` | scalar | same | R | null in, null out |
| 6 | `len(s)` | scalar | same | R | `size` stays for lists; `len(null)` is null, `size(null)` is 0 |
| 7 | `contains(s, part)` | scalar | same | R | case-insensitive in both |
| 8 | `startswith(s, p)` | scalar | `starts_with(s, p)` | R | the catalogue's one name (Rachana's `startsWith` too) |
| 9 | `endswith(s, p)` | scalar | `ends_with(s, p)` | R | the catalogue's one name |
| 10 | `date(text)` | scalar | same | R | a typed date; text dates compare as dates in checked mode |
| 11 | `timestamp(text)` | scalar | same | R | |
| 12 | `year(d)` | scalar | same | R | |
| 13 | `quarter(d)` | scalar | same | R | |
| 14 | `month(d)` | scalar | same | R | |
| 15 | `week(d)` | scalar | same | R | ISO week |
| 16 | `day(d)` | scalar | same | R | |
| 17 | `days_between(a, b)` | scalar | same | R | Rachana's `days(a, b)` folds in (catalogue) |
| 18 | `business_days_between(a, b)` | scalar | `business_days_between(a, b, calendar = …)` | R | the model's calendar when none is named |
| 19 | `fx(ccy, to, at=None)` | conversion | `fx(ccy, to, at = d)` | R | reads the FX source of Q15; allowed in table columns too (Q15) |
| 20 | `convert(agg, to='USD', at='as-of')` | conversion | `convert(agg, to = 'USD', at = 'as-of')` | E | lifts the currency guard |
| 21 | `related(alias.column)` | lookup | `related(table.COLUMN, on = key)` in a table; `related(alias.COLUMN)` in a model | E | one many-to-one hop (Q29) |
| 22 | `sum(x, where=c)` | aggregate | `sum(x, where = c)` | E | engine built-in, compensated summation |
| 23 | `avg(x, where=c)` | aggregate | same | E | |
| 24 | `min(x, where=c)` | aggregate | same | E | one argument in aggregate scope; several arguments are `least` |
| 25 | `max(x, where=c)` | aggregate | same | E | several arguments are `greatest` |
| 26 | `count(alias \| x, where=c)` | aggregate | same | E | `count(trades)` counts the table's rows |
| 27 | `distinct(x, where=c)` | aggregate | same | E | exact; ≈ only when the model's author opts in (PIVOT_ENGINE 12.4) |
| 28 | `ratio(num, den)` | aggregate | same | E | null on 0 |
| 29 | `percentile(x, p, where=c)` | aggregate | same | E | continuous quantile; the engine's interpolation proved equal to DuckDB's `quantile_cont` |
| 30 | `avg_by(agg, by=[f])` | aggregate | `avg_by(agg, by = [f])` | E | missing from FUNCTION_CATALOGUE.md: added |
| 31 | `ytd(agg, date)` | time | same | E | |
| 32 | `mtd(agg, date)` | time | same | E | |
| 33 | `qtd(agg, date)` | time | same | E | |
| 34 | `prior_period(agg, date)` | time | same | E | |
| 35 | `same_period_last_year(agg, date)` | time | same | E | |
| 36 | `running(agg, date)` | time | same | E | |
| 37 | `fiscal_ytd(agg, date)` | time | same | E | the model's `calendars.fiscal-year-start` |
| 38 | `lag(agg, n, order, by=[f])` | window | `lag(agg, n, order, by = [f])` | E | |
| 39 | `lead(agg, n, order, by=[f])` | window | same, keywords | E | |
| 40 | `rank(agg, desc=True, by=[f])` | window | `rank(agg, desc = true, by = [f])` | E | |
| 41 | `dense_rank(…)` | window | same, keywords | E | |
| 42 | `percent_rank(…)` | window | same, keywords | E | |
| 43 | `moving_avg(agg, n, order, by=[f])` | window | same, keywords | E | |
| 44 | `moving_sum(…)` | window | same, keywords | E | |
| 45 | `moving_min(…)` | window | same, keywords | E | |
| 46 | `moving_max(…)` | window | same, keywords | E | |
| 47 | `cumulative(agg, date)` | window | `running(agg, date)` | E | folded into `running` (catalogue) |
| 48 | `last(agg, date)` | balances | `latest(agg, date)` | E | renamed: Rachana's `last(list)` is the last element (Q5) |
| 49 | `first(agg, date)` | balances | `earliest(agg, date)` | E | renamed, as 48 (Q5) |
| 50 | `opening(agg, date, period='month')` | balances | `opening(agg, date, period = 'month')` | E | |
| 51 | `closing(agg, date, period='month')` | balances | `closing(agg, date, period = 'month')` | E | |
| 52 | `avg_over(agg, date)` | balances | same | E | `avg_by(agg, by = [date])` |
| 53 | `calc(agg, where=, ignore=, keep=, via=)` | filter context | same, keywords | E | 2.3 |
| 54 | `share_of(agg, over=)` | filter context | same, keywords | E | not `pct_of_total` (2.3) |
| 55 | `parent_of(agg, hierarchy=)` | filter context | same, keywords | E | not `pct_of_parent` (2.3) |
| 56 | `as_of(agg, date_expr)` | time travel | same | E | |
| 57 | `shift_date(d, n, unit='business-day')` | time travel | `shift_date(d, n, unit = 'business-day', calendar = …)` | R | |
| 58 | `asof()` | time travel | same | R | Rachana's `today()` folds in (Q7) |
| 59 | `measure('name')` | measure | same | E | a model's measure in a model; an engine's with its scope |

Of the 59, **15 exist in Rachana or the engine already** (`abs`, `coalesce`, `upper`, `lower`, `contains`, `sum`, `avg`, `min`, `max`,
`count`, `distinct`, `percentile`, `measure` and, renamed, `starts_with` and `days_between`); the other 44 are added to the catalogue in
this release, each with its DEL entry's text, examples and edge cases. The tier 1 functions the migration itself needs (`power`, `floor`,
`if`, `switch`, `least`, `greatest`) come with them; the rest of tier 1 is Q23.

### 2.6 One compiler: Rachana's tree to the engine

DEL's path today: `DelParser` → `DelChecker` → `DelLowering` → the old `bi.dataset.Node` tree → `SqlEmitter` (scans) and `RowEval` (live
tables). After the fold there is one path, owned by the engine:

1. **Parse and check** in `drishti-rachana` (the one parser; checked mode with the table's or model's types and masks).
2. **Lower** in `drishti-bicompute`: a row expression becomes the engine's row expression (`RowExpr`, DuckDB SQL for scans); an aggregate
   becomes the engine's `AggFunction` call (a formula of built-ins is the engine's `FormulaFunction`, mergeable when its parts are); a
   context function becomes the engine's context sub-plan; a window, a window over the answer.
3. **Evaluate** a live table's rows with Rachana's own evaluator (the in-memory live table), in decimals where the column is decimal.
   **The Java evaluator and the SQL lowering must agree** on every value, nulls and masks included: a test runs every shipped expression
   and a generated set (nulls, empty lists, masked values, zero divisors, mixed currencies) through both and compares row by row.

**Deleted in the same release** (no dual machinery): `server.bi.expr` (`DelParser`, `DelChecker`, `DelLowering`, `DelPrinter`, `DelAst`,
`DelType`, `FunctionCatalog`, `FunctionDocs`, `Scope`, `Fields`, `Expressions` and the `/api/v1/bi/expr` endpoints, whose job the
Rachana validate and catalogue endpoints take), `bi/expr/functions.yaml`, `bi.dataset.ExprParser` and `Node`, `bi.layer.CalculationParser`
(its keys read by the layer's model parser), `bicompute.formula.FormulaParser` (the pivot's custom formulas in DEL syntax: they become
Rachana text, migrated by the same tool), `bi.engine.RowEval`, the compatibility reader of version-1 datasets, and the `bi migrate
--expressions` and `--models` modes. `OneGrammarRulesTest`'s list of exceptions is then empty except the VaR scope selector (Q20).

### 2.7 Worked examples from the shipped items

| Item (shipped) | DEL today | Rachana after |
|---|---|---|
| `trading-risk` measure *Open MTM* | `sum(trades.MTM, where=trades.TRADE_STATUS != 'MATURED')` | `sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED')` (a null status is not counted, as today: Q1) |
| `trading-risk` *MTM per 1m notional* | `ratio(sum(trades.MTM), sum(trades.NOTIONAL)) * 1000000` | unchanged |
| `trading-risk` *MTM ytd* | `ytd(sum(trades.MTM), trades.TRADE_DATE)` | unchanged |
| `trading-risk` *MTM rank* | `rank(sum(trades.MTM))` | unchanged |
| `trading-risk` *MTM 3-day average* | `moving_avg(sum(trades.MTM), 3, trades.TRADE_DATE)` | unchanged |
| `trading-risk` *P&L per exposure* | `measure('P&L 1d') / measure('Exposure (EE peak)')` | unchanged; each measure at its own grain, as today |
| `trades-basic` *Average MTM per trade* | `measure('MTM') / measure('Trades')` | unchanged |
| `market-risk-var` *Bank VaR* | `max(nodes.VAR, where=nodes.NODE_ID == 'MGMT~BANK')` | `max(nodes.VAR, where = nodes.NODE_ID == 'MGMT~BANK')` |
| `trading-cashflows` *Cashflow amount* | `sum(payments.AMOUNT * (1 / 148.2 if payments.CURRENCY == 'JPY' else 1.174 if payments.CURRENCY == 'EUR' else … else None))` | mechanically `sum(payments.AMOUNT * (payments.CURRENCY == 'JPY' ? 1 / 148.2 : payments.CURRENCY == 'EUR' ? 1.174 : … : null))`; the migration report suggests `switch(payments.CURRENCY, 'JPY', 1 / 148.2, 'EUR', 1.174, …, null)` for the author to accept, never applied silently |
| `trading-risk-trades` row rule | `DESK in user.desks` | table rule `$.DESK in user.desks` (strict `in`: a desk-head with no desks sees no trades, as today) |
| `trades-basic-trade` calculated column | `MTM / 1000000` | `$.MTM / 1000000` |
| `market-risk-var-latest` derived columns | `'1D'`, `None` | `'1D'`, `null` (with `type: string`) |
| `counterparty-exposure-official-netting-sets` filter | `HIERARCHY == 'NSET'` | `filter: "$.HIERARCHY == 'NSET'"` |
| BI_DESIGN 3.3.5 *MTM all desks* | `calc(measure('MTM'), ignore=[desks.name])` | `calc(measure('MTM'), ignore = [desks.NAME])` |
| *MTM % of total* | `share_of(measure('MTM'), over=ALL)` | `share_of(measure('MTM'), over = ALL)` |
| *MTM % of parent* | `parent_of(measure('MTM'), hierarchy='Desk and book')` | `parent_of(measure('MTM'), hierarchy = 'Desk and book')` |
| *MTM day on day* | `measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))` | unchanged |
| *closing balance* | `last(sum(accounts.balance), accounts.businessDate)` | `latest(sum(accounts.BALANCE), accounts.business_date)` (Q5, Q12) |

### 2.8 What is refused, and why

| Refused | Why |
|---|---|
| any function that edits layer 1 (security) or adds rows a rule removed | security is never in scope; nothing adds rights |
| context transition (an aggregate silently evaluated per row of another table) | explicit iteration only (`avg_by`), so a figure's meaning is visible in its text |
| an aggregate in row or rule scope; a bare column in aggregate scope | grain safety: a table's column is one value per row, a measure one value per group |
| a document path in a measure | defined once: add the path as a table column |
| a filter, sort, `in` or grouping on a masked field (`DRS-9161`) | a hidden value must not be inferred from what a filter keeps |
| `reduce` with `+` over a list | an aggregate is the engine's (`sum`), never re-implemented |
| `as_of` with a date that depends on a row (`DRS-9166`); `as_of` on a live table (`DRS-9157`) | a second plan is bound to one date before the query runs |
| nesting of context functions deeper than three | closed and readable; the plans stay bounded |
| `ctl.x`, `link`, `fmt` in tables and models | display concerns of a Sutra, not data |
| a function with no SQL lowering in a scan, or no Java form in a live table | one grammar, executed where it is defined; never evaluated a second way |
| VaR, ES, exposure, FRTB-SA, SIMM as aggregates over a vector or a table | they come only from their engines through `measure(...)` (ONE_GRAMMAR 7.2) |

## 3. Datasets and models into virtual tables and models

The target forms are [VIRTUAL_TABLES.md](VIRTUAL_TABLES.md) section 4's, with the refinements this deeper look suggests (each one a
question in section 8). Files: `config/tables/<domain>/<name>.table.yaml` and `config/tables/<domain>/<name>.model.yaml` (planned);
stored items keep their place in the item store (Q16).

### 3.1 A dataset, key by key

| Dataset key (`rachana: 2`, and `rachana: 1` tables) | Virtual table key (planned) | Note |
|---|---|---|
| `rachana: 2`, `expressions: 2` | `rachana: 3` (planned) | the language version: Rachana expressions; `expressions:` goes (Q25) |
| `dataset: trading-risk-trades` | `table: bi.trading_risk_trades` | naming rule of Q11; `was: trading-risk-trades` kept for one release (Q18) |
| `version`, `id`, `title`, `description`, `owner`, `sample`, `endorsement`, `tags`, `ai`, `layout` | the same | stable ids and semver unchanged |
| `source: { kind, pack, connector }` | `from: { kind, pack, connector }` | any kind any connector serves |
| `source: { kind, each: LEGS }` | `from: { kind }` + `grain: $.LEGS[*]` | |
| `source: { kind, each: "LEGS[].CASH_FLOWS" }` | `grain: $.LEGS[*].CASH_FLOWS[*]` | two lists, one inside the other |
| `source: { connector, query, params }` (SQL) | `from: { connector, query, params }` | the four bound dates only, as today |
| `source: { python, inputs, params }` | `from: { python, inputs, params }` | inputs are MQs asked as the reader, as today |
| `source: { model, query }` | `from: { model, query }` | a **summary table**: a model's answer (Q13) |
| `extends: base@^1` | `from: { table: bi.base }` | a table over a table; its columns, rules and masks are inherited, its own only add |
| `columns.X: { type }` (no path) | `X: { path: $.X, type }` (`@.X` under a grain) | the path a bare name meant |
| `columns.X: { path: INDEX.NAME }` under `each` | `X: { path: "@.INDEX.NAME" }` | |
| `columns.X: { path: "LEGS[0].NOTIONAL" }`, `LEGS.0.CURRENCY` | `X: { path: "$.LEGS[0].NOTIONAL" }`, `$.LEGS[0].CURRENCY` | dotted indexes and a leading `$.` read as today |
| `columns.X: { path: "^.LEG_ID" }` | `X: { path: "^.LEG_ID" }` | the parent element (Q8) |
| `columns.X: { expr }`, `derive.X: { expr }` | `X: { expr }` | row scope; sibling columns replaced by their paths |
| `columns.X: { role, meaning, references, unit, additive, aggregate, format, key, description }` | the same keys on the column | `key: true` also lists the column in `keys` |
| `columns.X: { python: … }` (a Python column) | `X: { python: … }` | unchanged |
| `where:` | `filter:` | row scope over the source |
| `grain: { says, keys, date }` | `says:`, `keys:`; the date is always `business_date` | |
| `grain: { one-row-per: K, keep: latest by V }` | `keys: [K]`, `keep: { latest: V }` | explicit de-duplication, never a silent distinct |
| `group: { by, columns, grain }` | a summary table `from: { model: <the base's implicit model>, query: { fields: by, measures } }` | one way to aggregate (Q13) |
| `time: { mode: snapshot }`, `mode: cached` | `materialize: on-load` | BI's columns layer, moved |
| `time: { mode: snapshot, storage: direct }`, `mode: direct` | `materialize: none` on a SQL source | the compiler pushes the query to the source |
| `time: { mode: live, live: { rows, age } }` | `materialize: { live: { rows, age } }` | the in-memory live table (Q14) |
| `live.history: { every, keep, by, measures }` | the model's `history:` | it samples measures, which are a model's (Q14) |
| `time: { as-of, keep }`, `refresh: { on-load, schedule }` | `as-of:`, `keep:`, `refresh:` | the same words |
| `security.rows[]` | `rules[]` | applied to **every** reader of the table (Q9); `user.x` |
| `security.masked`, `hidden-for` | `masked: true` on the column, `hidden-for:` | |
| `tests` (`unique`, `not-null`, `references-exist`, `agg`) | `tests` | `agg` tests ask the table's implicit model |
| `rollups` (a dataset's) | the implicit model's `rollups` | |
| `measures`, `hierarchies`, `relationships` (`rachana: 1` multi-table datasets) | a model | the `rachana: 1` reader is retired (Q19) |

Built-in columns: `id`, `business_date`, `index` (under a grain), `update_seq` and `updated_at` (live), never declared (Q12).

A migrated sample, `trading-cashflows-cashflows`:

```yaml
# config/tables/bi/trading_cashflows_cashflows.table.yaml  (planned)
rachana: 3                                   # planned
table: bi.trading_cashflows_cashflows
was: trading-cashflows-cashflows             # one release only (planned)
version: 1.0.0
title: "Trade legs and cashflows: cashflows"
from: { kind: trade }
grain: $.LEGS[*].CASH_FLOWS[*]               # one row per cash flow of every leg
columns:
  LEG_ID:       { path: "^.LEG_ID", type: integer }
  CURRENCY:     { path: "^.CURRENCY", type: string }   # the leg's currency: NOTIONAL is in it
  FLOW_TYPE:    { path: "@.FLOW_TYPE", type: string }
  PAYMENT_DATE: { path: "@.PAYMENT_DATE", type: date }
  NOTIONAL:     { path: "@.NOTIONAL", type: decimal, format: amount0 }
  RATE:         { path: "@.RATE", type: decimal }
materialize: on-load
```

and `trading-risk-trades` with its rule and mask:

```yaml
# config/tables/bi/trading_risk_trades.table.yaml  (planned)
rachana: 3                                   # planned
table: bi.trading_risk_trades
from: { kind: trade-valuation, connector: risk-store }
keys: [VALUATION_ID]
columns:
  VALUATION_ID: { path: $.VALUATION_ID, type: string, role: key }
  DESK:         { path: $.DESK, type: string }
  COUNTERPARTY: { path: $.COUNTERPARTY, type: string, masked: true }
  MTM:          { path: $.MTM, type: decimal, format: signed0 }
  TRADE_DATE:   { path: $.TRADE_DATE, type: date }
rules:
  - { role: desk-head, where: "$.DESK in user.desks" }
  - { role: [risk, analyst, admin], where: "true" }
materialize: on-load
```

### 3.2 Calculated columns

A calculated column is a row-scope Rachana expression over the **source** (`$`, `@`, `^`), never over another column: the migrator
replaces each column a DEL expression names by that column's path (`MTM / 1000000` becomes `$.MTM / 1000000`), so a column's meaning never
depends on the order columns are read and the compiler can push it to the fastest route. A column built from a masked path is masked.

### 3.3 A model, key by key

| Model key | Layer model key (planned) | Note |
|---|---|---|
| `model`, `version`, `id`, `title`, `description`, `owner`, `endorsement`, `tags`, `sample`, `lifecycle`, `layout`, `ai`, `golden`, `contract`, `perspectives` | the same | consumers bind by name and major as today (`trading-risk@1`) |
| `datasets: { trades: { dataset: trading-risk-trades, role: fact } }` | `tables: { trades: { table: bi.trading_risk_trades, role: fact } }` | **aliases kept** (Q10), so every measure, field and rule text keeps `trades.MTM` |
| `datasets.x.dataset: pack.trading.trade@^1` | `tables.x.table: trading.trade@^1` | the pack's generated table (3.6) |
| `relationships[]` (`id`, `from`, `to`, `cardinality`, `direction`, `active`, `name`, `suggested`) | the same | `from` and `to` are `alias.COLUMN`; validation `DRS-9122` to `DRS-9124` unchanged |
| `measures.X: { expr, format, description, currency, additive, id }` | the same, `expr` in Rachana | aggregate scope |
| `measures.X: { via: r_5 }` | `measures.X: { expr: "calc(…, via = 'r_5')" }` | one way to name an inactive relationship |
| `measures.X: { python, inputs, by }` | the same | a Python measure stays a custom function of the engine |
| `hierarchies` | the same | levels are model fields (`trades.TRADE_DATE.month` a field name) |
| `calc-groups`, `parameters`, `calendars`, `fx` | the same keys | items in Rachana with `SELECTED`; `fx` per Q15 |
| `security.rows.<alias>[]`, `masked`, `propagate`, `may-see-all` | the same | only tighter; the safeguard `DRS-9130` and `DRS-9131` unchanged |
| `tests`, `rollups` | the same | rollups are the engine's partial states (P3) |
| a dataset's live `history` | `history:` | 3.1 |

The model `trading-risk` after migration (an excerpt):

```yaml
# config/tables/bi/trading_risk.model.yaml  (planned)
rachana: 3                                   # planned
model: trading-risk
version: 1.0.0
tables:
  trades:         { table: bi.trading_risk_trades, role: fact }
  netting_sets:   { table: bi.trading_risk_netting_sets, role: fact }
  counterparties: { table: bi.trading_risk_counterparties, role: dimension }
  desks:          { table: bi.trading_risk_desks, role: dimension }
relationships:
  - { from: trades.DESK, to: desks.id }
  - { from: trades.NETTING_SET, to: netting_sets.id, active: false }
measures:
  MTM:      { expr: "sum(trades.MTM)", format: signed0 }
  Open MTM: { expr: "sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED')", format: signed0 }
  MTM rank: { expr: "rank(sum(trades.MTM))" }
security:
  may-see-all: { netting_sets: [desk-head], counterparties: [desk-head], desks: [desk-head] }
```

### 3.4 Materialisation (BI's cached, rollup and live layers)

| Today | Becomes | Code |
|---|---|---|
| `cached`: Parquet per business date in the BI store, rebuilt on the data-load signal | `materialize: on-load` | `Layers`, `TableBuilder`, `DatasetRefresher` moved into the layer; the files' layout keeps its shape under the layer's store (planned `drishti.tables.store-dir`) |
| `direct` | `materialize: none` over a SQL-capable source | the compiler's route |
| `live` (in-memory by key, retention, history) | `materialize: { live: … }`; history on the model | `LiveTables`, `LiveTable`, `LiveFeed`, `LiveHistory` moved |
| rollups (BI's and the pivot's P3) | the model's `rollups:` | one mechanism already after P3 |
| `meta.layer`, `meta.rollup` in answers | the same keys | consumers read them unchanged |

Materialised files stay a cache: rebuilt at will, never the truth, never written into a lake's own tables.

### 3.5 MQ: the contract, answered by the layer

MQ keeps its keys, versions and canonical form; what changes is who answers. `model:` resolves in the layer's catalogue (an implicit
model of a table is `table:<name>`, as `dataset:<id>` is today); `define` and `having` carry Rachana; `where[].op` is unchanged (it is not
an expression). The answer's `columns`, `rows`, `totals` and `meta` are unchanged. The plan's nodes (`SecureScan`, `Derive`, `Dedup`,
`SemiJoin`, `Aggregate`, `Meet`, `Context`, `Window`, `Totals`) move with the layer; the engine executes the aggregation nodes as since
2.9.11.b. DSQL compiles to MQ as today; its expression functions map to the catalogue's names.

### 3.6 Pack-generated datasets

BI derives a read-only dataset `pack.<pack>.<kind>` per kind at pack load ([BI_DESIGN.md section 15, part 7](BI_DESIGN.md#7-pack-generated-datasets-d3)). They are not
migrated: the layer generates the same tables as `<pack>.<kind>` (`trading.trade`), with the same columns, roles, meanings, references,
grain, time mode and entitlements, and a pack's optional `bi:` block in `pack.yaml` becomes a `tables:` block (planned) whose arrays
(`arrays: { trade: [LEGS] }`) generate grain tables (`trading.trade_legs`, VIRTUAL_TABLES section 3). Models naming `pack.trading.trade@^1`
are rewritten to `trading.trade@^1`; the parity proof covers them like any table.

## 4. The one migration tool

### 4.1 What it is

`drishti.py tables migrate` (planned), one command with one engine (Java, on the server and in the CLI's local mode as `bi migrate` is
today), replacing every `bi migrate` mode that touches datasets, models or expressions. Per item it does both moves in one pass: the
dataset or model to its layer form, every DEL expression in it to Rachana. It parses DEL with the old parser kept **inside the tool only**
(read, never evaluate), prints Rachana from the tree (a printer, never text substitution), re-checks the result with Rachana's checker and
asserts that the old lowering and the new compile to plans that give the same values on the generated set of 2.6.

| | |
|---|---|
| **Input** | `config/bi/datasets/*.dataset.yaml`, `config/bi/models/*.model.yaml` (both `rachana: 1` and `2`); reports (local measures, filters, the `dataset:` or `model:` binding), boards, alerts and subscriptions (their MQ and `define`), notebooks (MQ and DSQL cells), golden questions and tests; the server's **stored items** of those kinds in every workspace, **every version** in their history (Q17); pivot custom functions written as DEL formulas (P2); a pack's BI content and `bi:` block; `docs/tutorials/data` and the test resources |
| **Output** | `config/tables/<domain>/<name>.table.yaml` and `.model.yaml` (planned); rewritten reports and other consumers (only expressions and names change, comments and key order kept); stored items rewritten in place under their ids and version numbers; a report (JSON and text): every item, every expression before and after, every name mapped, every suggestion offered, every refusal |
| **Dry run** | `--dry-run` (planned) writes nothing and prints a unified diff per file and per stored item, then the counts (items, expressions, renamed names, refusals); `--check` (planned) exits 1 when anything would change (CI) |
| **Idempotent** | an item already in the new form (`rachana: 3`, or a `table:` key, both planned) is left as it is; running twice changes nothing (a test runs it twice on every shipped item and compares bytes) |
| **Audited** | stored items: one audit line per item (`tables-migrate`, planned), the text before kept in the item's history, the person or `system` named |
| **Bounded** | one item at a time, streamed; no data is read (only definitions), so it runs in seconds on the shipped set and in minutes on thousands of items |

### 4.2 What it refuses

| Refused (planned code `DRS-9195`) | What the person does |
|---|---|
| an expression DEL's checker rejects today (already broken) | fix it in the old form, or delete it; the report names it |
| a construct with no Rachana form (none is known; the list is kept in the tool) | the report names the item and expression |
| a new name that collides with an existing table or model | rename one; the report proposes a name |
| a stored item with a draft newer than another person's open edit session | the tool waits for the edit to be saved or offers to migrate the published version only |
| a pack-generated dataset | none: it is regenerated, not migrated (3.6) |
| an expression whose two plans disagree on the generated set | never written; the report shows the row that differs; a defect for the build, not for the person |

Suggestions (the `switch` form of a long conditional chain, `in` instead of a chain of `==`) are offered in the report and never applied
silently: the migrated text is the mechanical translation, so the parity proof proves exactly what was written.

### 4.3 Existing installations

1. **Stored items** are migrated at the server's first start on 2.9.11.e, automatically and audited (as the boards' migration does
   today), before BI answers its first query; a refusal leaves that item unreadable with the reason on its page, never half-migrated.
2. **Files** (`config/bi/datasets`, `config/bi/models` and an installation's own report files) are migrated by the administrator with
   `drishti.py tables migrate --dry-run`, then without it (planned), so the diff is reviewed before it is written. A server that finds a
   file in an old form at start refuses that file (planned code `DRS-9196`) with the command to run, and loads everything else.
3. **The old files' fate:** after migration they are **deleted** (the repository's in the same commit as the switch; an installation's by
   the tool, which moves them into the planned `<data>/migrated/2.9.11.e/` folder for one release before removing them). No release reads
   the old forms beside the new (Q18); the only alias is `was:`, one release, so a bookmark, a link or a saved URL that names
   `trading-risk-trades` still resolves with a warning.
4. **Pack-generated datasets** need nothing: they are regenerated at pack load (3.6).
5. **The tool's own life:** it keeps the old DEL reader inside it so a late upgrader can migrate; it is removed after 2.9.14 (planned), and
   an upgrade from before 2.9.11.e then goes through 2.9.14 first.

## 5. The one parity proof

**Rule:** before versus after, on the same data, every figure equal and every person's visible rows and masks equal. Recorded once from
the current code, before any change of the release, and committed as fixtures; the release passes only when every fixture matches on the
new code alone. It extends what 2.9.11.b's `BiParityRecorder` and `BiParityTest` on `wip/pivot-b` do (one grouped query per level,
recorded with a system property, compared at 1e-9 relative), from totals to every answer and every person.

### 5.1 What is recorded

| Set | Recorded per | From |
|---|---|---|
| **Shipped reports** (13 in `config/bi/reports`, and the tutorials' reports) | report × page × visual: the MQ the visual sends, its rows, its totals per level, `meta.masked` and `meta.noAccess` | the viewer's own path (`VisualMq` → MQ → answer) |
| **Shipped models** (10) | model × measure × (no field; each field of each dimension; each hierarchy level): the answer and its totals | generated MQs, bounded |
| **Every BI test suite** | the Java suites under `drishti-server/src/test/java/com/ash/drishti/server/bi` and the console's BI tests run unchanged on the new code; a guard checks the switch commit changes no expected value in them (only names and expression text) | the suites themselves |
| **Items' own tests** | every `tests:` (expected values, `unique`, `not-null`, `references-exist`) and every golden question | Check's runner |
| **Rows each person sees** | model × alias × person: the set of row keys of a `shape: rows` extract (hashed), the masked columns, and each measure's grand total | the persons below |
| **Expressions** | every expression of every item: its values on the generated set (2.6), old lowering against new | the migration tool's harness |
| **Live** | `live-trading` and `live-exposure` fed a recorded, deterministic tick sequence; answers at fixed checkpoints | the demo ticker's replay |
| **Pivot formulas** | every shipped and test custom function written in DEL syntax (P2), on its harness cases | the split-and-merge harness |

**Persons.** A desk-head with two desks; a desk-head with **no** desks (the strict `in`: no rows); risk; analyst; admin; a reader without
the raw power (masks); a role that no rule names (`DRS-9131`: no rows and the reason). **Dates.** Two business dates of the test lake, a
window (time intelligence), and the day before (for `as_of`). **Size.** The test lake and at most 10,000 trades locally.

### 5.2 Tolerance and known differences

Figures: 1e-9 relative for floating-point values; **exact** for counts, decimals, ranks, dates and texts; null equals only null. Rows:
equal sets of keys per person, exactly; masks: the same columns. A difference fails naming the item, visual or measure, the group, the
person and both values. **Known, intended differences:** none expected. Any found is listed in the release notes' draft with its reason
and needs the product owner's agreement before the switch; the last digit of a compensated sum (2.9.11.b's precedent) is within the
tolerance, not a difference.

### 5.3 How the fixtures are recorded

1. The first commit of the release branch adds the recorder (`BiFoundationRecorder`, planned) and its cases, beside
   `BiParityRecorder`, enabled only by a system property (`-Ddrishti.bi.foundation.record=true`), and runs it on the unchanged tree
   (develop after 2.9.11.d). Its fixtures go to `drishti-server/src/test/resources/bi/foundation-parity/` (planned), one JSON file per
   report and model, written with sorted keys so a diff is readable.
2. The fixtures are committed **before** any production code of the release changes; the commit that records them holds nothing else.
3. The comparison (`BiFoundationParityTest`, planned) runs on every later commit of the branch and in both drills.
4. In the switch commit the recorder is deleted (it can no longer run: the old code is gone); the fixtures and the comparison stay as the
   release's regression test until 2.9.14 (planned), as the VaR regression check against the previous release does.

## 6. Arrow Flight, client notebooks, Help and docs

### 6.1 Arrow Flight: the `table` and `model` forms (design only)

2.9.11.c builds the request `{"form": …, "request": …}` with the forms `kind`, `model` (MQ), `pivot`, `measure` and `lake`
(the Flight reference on `wip/flight`). This release adds `table` and moves `model` onto the layer:

```json
{"form": "table", "request": {"table": "trading.trade_legs", "columns": ["TRADE_ID", "LEG_ID", "CURRENCY", "NOTIONAL"],
                              "filter": "CURRENCY in ['JPY', 'EUR']", "date": "2026-09-30", "limit": 1000000}}
{"form": "model", "request": {"model": "trading-risk@1", "query": {"select": {"fields": ["desks.NAME"], "measures": ["MTM"]}}}}
```

| Key or point | `table` (planned) | `model` (moved) |
|---|---|---|
| Names | a virtual table (`<domain>.<name>`, or its `was:` name for one release) | a model by name and major, or `table:<name>` for a table's implicit model |
| Selection | `columns` (none: every column the person may see); `filter` in Rachana over the table's columns (checked mode; a masked column in it is refused); `date` or `window`; `limit` | the MQ (`select`, `where`, `having`, `time`, `shape: rows` for an extract) |
| Parts | one endpoint per materialised business date and node that holds it (`GetFlightInfo`), read in parallel; an unmaterialised table is one part on any member | one part (an answer), or the pivot engine's parts when the plan scatters |
| Schema | one field per column from the table's types (`integer` int64, `decimal` float64 as the 2.9.11.c reference maps decimals, `date` date32, `timestamp` timestamp, `boolean` bool, `string` utf8); metadata `drishti.table`, `drishti.grain`, `drishti.keys`, `drishti.masked`, `drishti.business_date` | the answer's columns; metadata `drishti.model`, `drishti.masked`, `drishti.provenance` |
| Security | the table's rules and the source kind's entitlements for the person; masked columns left out unless asked, then sent as nulls with `drishti.masked` | the model's and every table's rules; masks as the answer carries them |
| Streams | `stream: true` on a live table: upserts by key, the sequence is `update_seq`, resumable | snapshot only, as in 2.9.11.c |
| Scope | `data:read` or `data:read:<domain>` | `data:read` or `data:read:<the model's domain>` (Q28) |
| `ListFlights` | every table the token may read | every model the token may read |
| Audit | the Flight line and `tables.read` (planned) on the table | the Flight line and the model's query audit |

Flight SQL (when 2.9.11.c builds it) sees a catalog `tables` whose schemas are domains and whose tables are virtual tables, and a catalog
`models` whose schemas are models, with `MEASURE('name')` as in DSQL; both compile to the same plans. The PostgreSQL protocol is on hold.

### 6.2 The client examples' virtual-table notebooks

Two notebooks join the 2.9.11.d examples (folder as 2.9.11.d decides; `clients/python/notebooks/` is the planned default), each run as a
test on the demo lake: **Virtual tables** (`virtual-tables.ipynb`, planned): list the tables, read `trading.trade_legs` for a date into
pandas, filter in Rachana, show a masked column as an unprivileged token sees it, stream a live table; **Models**
(`model-queries.ipynb`, planned): MTM by desk from `trading-risk`, a `shape: rows` extract, `share_of` against a slicer, the same question
through Flight SQL when it exists. Read only, Flight by default; a cell that reads the lake directly is labelled for people who already
hold lake access.

### 6.3 Help and documentation

| Document | Change |
|---|---|
| A **tables guide** (planned `docs/guides/TABLES_GUIDE.md`, Help → Data → Virtual tables) and its **tutorial** (two docs per feature) | new: tables, grains, paths, columns, rules, materialisation, models, the checks and preview, the migration; every example run on the demo lake with its real output, UI, command line and YAML at every step |
| [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) | checked mode, scopes, list literals, named arguments, `in`, `^`, the null rule; the filter-context section with before and after tables |
| `docs/guides/FUNCTIONS.md` (generated, from 2.9.11.b) and [FUNCTION_CATALOGUE.md](FUNCTION_CATALOGUE.md) | the 44 functions added, `avg_by`, the renames (`latest`, `earliest`), the `share_of` and `pct_of_*` distinction |
| [BI_LANGUAGE_REFERENCE.md](../guides/BI_LANGUAGE_REFERENCE.md) | deleted; its cookbook of finance recipes moves into the tables guide, rewritten in Rachana (Q27) |
| [BI_GUIDE.md](../guides/BI_GUIDE.md) | sections 13 to 16, 23 and 30 rewritten: BI reads the layer's tables and models; the dataset editor and the Model view are the layer's editors, reached from BI and Admin |
| [BI_DESIGN.md](BI_DESIGN.md) | section 3.3 replaced by a pointer to Rachana; section 15's dataset and model specification moved into the tables guide's reference part (current design only) |
| [PIVOT_ENGINE.md](PIVOT_ENGINE.md), `docs/guides/PIVOT_GUIDE.md` | the "dataset column" route becomes "a virtual table's column"; Promote offers *add as a column of a virtual table* |
| [VIRTUAL_TABLES.md](VIRTUAL_TABLES.md), [ONE_GRAMMAR.md](ONE_GRAMMAR.md) | the decisions of section 8 written in; part 4 marked built |
| Flight guide and reference (2.9.11.c) | the `table` form, the `model` form on the layer, the catalogs of Flight SQL |
| `CLI_GUIDE.md`, `API_GUIDE.md`, `CONFIGURATION.md`, `TROUBLESHOOTING.md` | `tables` commands (planned), the layer's endpoints, settings and codes; the removed `bi expr` endpoints and `bi migrate` modes taken out |
| Tutorials 10, 11, 13, 14, 17, 18 and their screenshots | every expression in Rachana, every dataset a table; screenshots retaken |
| CHANGELOG and RELEASE_NOTES | the release's history (never in the guides) |

## 7. Risks, size, order of work, drills

### 7.1 Risks

| Risk | Mitigation |
|---|---|
| **A security meaning changes silently** (`in` on an empty list; `!=` against null; a dataset rule that no longer applies outside a model) | the strict `in` (Q3), one null rule (Q1), table rules for every reader (Q9); the persons of 5.1 include the empty-desks desk-head and the role no rule names |
| **The Java evaluator and the SQL lowering disagree** (nulls, decimals, text dates, masks) | one generated set run through both on every build (2.6); decimals in the evaluator where the column is decimal |
| **Sutras change** under the null rule and the null-in text functions | the 2.9.11.b Sutra scan (`SutraAggregateParityTest`'s pattern) extended to every expression of every Sutra on its test cases, before and after |
| **Fixtures recorded too late** (after code moved) are no proof | the recording commit is the first of the branch and holds nothing else (5.3) |
| **Renames ripple** (table names, `business_date`, `latest`/`earliest`) into reports, bookmarks, URLs, alerts, subscriptions, the AI's prompts and golden questions | one tool rewrites every consumer; `was:` for one release; the docs test and the console's browser tests find leftovers |
| **Performance regresses** when the layer replaces BI's own scans | the compiler keeps BI's routes (promoted column, materialised Parquet, connector scan, JSON over `doc`); a before and after timing on the shipped reports, at 10,000 trades locally, in the drill |
| **Depends on unmerged work** (2.9.11.b's `wip/pivot-b` and `wip/one-grammar`, 2.9.11.c's `wip/flight` and the lake configuration) | built only after 2.9.11.d is released; the design is re-read against what landed before the build starts |
| **Two large items in one release** (and 2.9.11's ten drills) | the order of 7.3, the pre-merge gate per branch, the pre-drill check, a freeze during drills; tier 1 functions moved out (Q23) |
| **History rewrite of stored items** (every version) | the text before kept per item in the audit; a dry run on a copy of a real store first |

### 7.2 Size

**XL as two L items**: (1) the DEL fold (Rachana's checked mode, syntax, 44 functions, the engine's lowering and context functions, the
evaluator, the Sutra scan) and (2) virtual tables with BI moved onto them (the layer module, its catalogue, parsers, compiler,
materialisation moved, checks and preview, the editors repointed, the migration tool, the parity proof, Flight's two forms, notebooks,
docs). Tier 1's other functions are not in this size (Q23).

### 7.3 Order of work

| Step | What | Branch (planned) | Weight |
|---|---|---|---|
| 0 | this design discussed; decisions written into VIRTUAL_TABLES.md, ONE_GRAMMAR.md and here | none | light |
| 1 | the parity recorder and fixtures, recorded on develop as it is | `wip/bi-foundation` (planned; first commit) | heavy, once |
| 2 | Rachana gains: syntax, checked mode (the checker ported), scopes, the null rule, the 44 functions; the Sutra scan | `wip/rachana-checked` (planned) | heavy |
| 3 | the layer module: catalogue, table and model parsers, compiler, materialisation moved, checks, preview, `tables` commands (planned) | `wip/tables-layer`, beside step 2 | heavy |
| 4 | the migration tool; dry runs on the shipped set and a copy of a real store | on step 3's branch | medium |
| 5 | the switch: BI reads the layer, editors repointed, DEL and the other parsers deleted, `OneGrammarRulesTest`'s exceptions emptied; parity green | `wip/bi-foundation` (planned) | heavy |
| 6 | Flight's `table` and `model` forms; the notebooks | `wip/flight-tables` (planned) | medium |
| 7 | docs, tutorials, Help, screenshots | `wip/bi-foundation-docs` (planned) | light |

Steps 2 and 3 run side by side (two heavy builders at most, one when the product owner's stack is up); steps 4 and 5 follow in order;
6 and 7 overlap 5. Each branch passes the drill's gate and its own tests before it is merged into develop.

### 7.4 Drills (two)

| Drill | When | What it proves |
|---|---|---|
| **Drill A (features)** | steps 1 to 5 merged, after a clean pre-drill check | the whole suite on the new path only; the parity proof green; the migration run on the shipped set from scratch; the Sutra scan; Windows QA (no native code is added) |
| **Drill B (release)** | Drill A's fixes, Flight's forms, the notebooks, docs and screenshots merged | the release as shipped: every suite, the docs test, the before and after timings, the client notebooks on the demo lake; then the release |

A code freeze holds during each drill; nothing else heavy runs beside it; the drill tool is not changed during the release's drills.

## 8. Decisions (product owner, 2026-11-03: every recommendation accepted; Q1 decided as Rachana's rule)

Each with a recommendation. Q30 collects what this deeper look suggests changing in VIRTUAL_TABLES.md V1 to V5.

| # | Question | Recommendation |
|---|---|---|
| Q1 | **One null rule.** DEL (and SQL) say a comparison with null is false; Rachana says `null != 'X'` is true. | **Decided: Rachana's rule** (`null != 'X'` is true, `null == 'X'` false) everywhere. The migrator rewrites each DEL comparison whose meaning would change (`a != b` becomes `a != b && a != null` where `a` may be null) so every BI figure stays identical under the parity proof (section 5); the editor and docs state the rule once. |
| Q2 | **Checked mode.** Rachana refuses ill-typed expressions only where types are known (tables, models, typed pivots); Sutras over untyped documents stay total | **Yes**: one grammar and one checker; checked mode only refuses more, never means something else |
| Q3 | **Strict `in`.** Add `in` and `not in` to Rachana (an empty list holds nothing), keeping `one_of` (an empty choice holds everything) for controls; row rules may use only `in` | **Yes**: otherwise a desk-head with no desks would see every trade |
| Q4 | **One spelling.** Rachana's `&&`, `\|\|`, `!`, `?:`, `true`, `false`, `null`; DEL's Python words are not kept as aliases | **Yes**: one spelling; the migrator writes it and the editor completes it |
| Q5 | **`first` and `last` clash.** DEL's balance functions `first(agg, date)`, `last(agg, date)` against Rachana's `first(list)`, `last(list)` | **Rename the balances** to `earliest(agg, date)` and `latest(agg, date)` |
| Q6 | **`share_of`, `parent_of` against `pct_of_*`.** The first drop filters (DAX's `ALL`), the second are of the visible totals (Excel) | **Keep both families with distinct names**; correct FUNCTION_CATALOGUE's line that equates `pct_of_parent` with `parent_of` |
| Q7 | **`today()` and `asof()`** mean the same date (a view's, a query's) | **Fold `today()` into `asof()`**; the Sutras are rewritten by the same tool and proved by the scan |
| Q8 | **The parent element.** A table of cash flows reads its leg's fields (`^.LEG_ID` in BI today); Rachana has `$` and `@` only | **Add `^`** (the element's parent; `^^` its parent) to Rachana's paths |
| Q9 | **Dataset row rules and masks** move onto the virtual table and apply to **every** reader (Flight, the pivot, BI), not only through models (VIRTUAL_TABLES section 7 says the model adds them) | **On the table**: otherwise a Flight read of a table would show a desk-head every desk |
| Q10 | **Aliases in models** (`tables: { trades: { table: … } }`) instead of full names in measures (`sum(trading.trade_legs.NOTIONAL_USD)`, VIRTUAL_TABLES section 4) | **Aliases**: every measure, field, rule and report keeps its text; three-part names are long and read as paths |
| Q11 | **Names of migrated datasets.** `trading-risk-trades` becomes `bi.trading_risk_trades` (domain `bi` for every migrated dataset, underscores for SQL clients); pack datasets `pack.trading.trade` become `trading.trade` | **Yes**: mechanical and predictable; an administrator may move a table to another domain later (a rename the tool does) |
| Q12 | **Built-in column names.** BI's `businessDate`, `updateSeq`, `updatedAt` or the lake's `business_date`, `update_seq`, `updated_at` | **The lake's**, one name across the lake, Flight and SQL clients; the tool rewrites every reference |
| Q13 | **Tables that are not flat** (V3): SQL queries, Python Transforms, a model's answer, `grain.keep`, `group:` | **Allowed as sources**; `keys` with `keep: { latest: … }` de-duplicates; `group:` becomes a summary table (`from: { model, query }`), the one way to aggregate into a table |
| Q14 | **Materialisation** (V4): VIRTUAL_TABLES section 6 has `none`, `on-load`, `rollup` | **Add `live`** (in-memory, `rows`, `age`); `direct` is `none` over a SQL source; **history moves to the model** (it samples measures) |
| Q15 | **FX in tables.** DEL allows `fx` only in measures (the rates are the model's); VIRTUAL_TABLES' example uses it in a column | **One FX source for the installation** (planned `drishti.tables.fx-kind`), a model may name another; tables may use `fx` at the row's business date |
| Q16 | **Where definitions live.** Files under `config/tables/` (planned); stored items stay in the item store (`drishti_bi_item`, no schema change) with kinds `table` and `model`; governance (workspaces, review, versions) shared by BI and the layer | **Yes** (the database rules: no migrations, two complete schema files) |
| Q17 | **Old versions in item history** (consumers pinned to `@1` read them) | **Rewrite every version** in place, the text before in the audit; otherwise the old parser would have to stay |
| Q18 | **The old files.** Read-with-warning for one release, or none | **None**: stored items migrate at first start, files by the command with a dry run; a server refuses an old file with the command named; only `was:` names stay one release |
| Q19 | **Version-1 datasets** (`rachana: 1`, `live-trading`, `live-exposure`) and their compatibility reader | **Migrated by the same tool and the reader deleted** |
| Q20 | **The other parsers** `OneGrammarRulesTest` lists until 2.9.11.e: DEL, `ExprParser`, `CalculationParser`, the pivot's DEL-syntax formulas, the VaR scope selector `ScopeExpr` | **The first four go now**; `ScopeExpr` moves in its own change with VaR's exact-equality regression check, after this release |
| Q21 | **Document paths in measures** (`sum(trades.LEGS[*].NOTIONAL)`) | **Refused**: a path is a table column, defined once |
| Q22 | **Consumers in this release** (V5 says everything) | **BI, the pivot, Flight (`table`, `model`), alerts**; the Data view, Sutras reading tables, Flight SQL's catalogs (if not built in 2.9.11.c) and the AI in the next release; the PostgreSQL protocol stays on hold |
| Q23 | **Tier 1 functions.** The plan puts the whole of tier 1 in 2.9.11.e | **Only the DEL fold's 44 and the six the migration needs** (`power`, `floor`, `if`, `switch`, `least`, `greatest`); the rest of tier 1 in the next release, keeping the release to two large items |
| Q24 | **How long the migration tool lives** (it carries the old DEL reader) | **Until 2.9.14** (planned), then deleted; later upgrades pass through 2.9.14 |
| Q25 | **Version marker** of the new files | **`rachana: 3`** (planned); the `expressions:` key goes |
| Q26 | **Parity tolerance**: 1e-9 relative for floats; exact for counts, decimals, ranks, dates, texts, rows and masks; no intended differences | **Yes**; any difference found needs your agreement before the switch |
| Q27 | **BI_LANGUAGE_REFERENCE.md** | **Deleted**: grammar into RACHANA_REFERENCE, functions into FUNCTIONS.md, the recipes into the tables guide |
| Q28 | **Flight scopes** for `table` and `model` | **`data:read` or `data:read:<domain>`** for both; models no longer need the BI power, since they are the layer's |
| Q29 | **`related`** in a table (no aliases there) | **`related(table.COLUMN, on = key)`**; in a model `related(alias.COLUMN)` along the active relationship |
| Q30 | **Changes to VIRTUAL_TABLES.md V1 to V5** | **V1** yes, the module named `drishti-tables` (planned), not depending on BI; **V2** yes, with the item store of Q16 and the history of Q17; **V3** amended by Q13 (summary tables are a model's answer) and Q21; **V4** amended by Q14; **V5** narrowed for this release by Q22, and section 7 changed by Q9 |

## 9. As built (part B foundation)

Part B's foundation (sections 3, 4 and 5) is built on `wip/bi-foundation-tables` beside part A (the language, `wip/bi-foundation`). BI does
not read the layer yet: that is the switch (section 7.3, step 5). The reference for every key, check and command is
[TABLES_GUIDE.md](../guides/TABLES_GUIDE.md).

| Part | Built | Where |
|---|---|---|
| The module | `drishti-tables`, plain Java, depending on `drishti-rachana` and `drishti-bicompute`; `drishti-server` depends on it; `ArchitectureRulesTest` holds both directions (the layer never reads BI or the server; nothing beneath it reads the layer) | `com.ash.drishti.tables` |
| Definitions | `TableDef`, `ColumnDef`, `ModelDef` with every key of sections 3.1 and 3.3; names (Q11) and references with a major; built-in columns (Q12); `was:` (Q18) | `tables.def` |
| Files | `.table.yaml` and `.model.yaml` read with every problem by key, and written in one canonical form (`rachana: 3`, Q25); an old form refused with the command (`DRS-9614`) | `tables.yaml` |
| Catalogue | files by domain folder and stored items through `ItemStore` (Q16; the server's implementation over `drishti_bi_item` comes with the switch); `was:` names; a table over a table with its base's columns, rules and keys; lineage, with `Lineage.Consumers` for BI, alerts and Flight to register | `tables.catalog` |
| Checks | every table (grain, paths and their roots, columns, types, keys, `keep`, materialisation including live, rules, tests, masks derived from paths), every model (aliases Q10, tables at their major, relationships and their types, measures in aggregate scope, no path in a measure Q21, `measure('…')`, hierarchies, security, a live `history:` Q14), the catalogue (duplicates, references, `related(…)` Q29, cycles); expressions through `ExpressionChecker`, the seam where part A's checked mode is chained (`andThen`); what only checked mode decides is `DRS-9612` (information) until then | `tables.check` |
| Preview | rows over JSONL samples: the grain expanded, `$`, `@`, `^`, `^^` walked, filters and calculated columns by Rachana (`RowEvaluator`, replaced by part A's evaluator); sample checks (keys unique, empty paths) | `tables.preview` |
| Materialisation | the API (`Materialization.OnLoad`, `Materialization.Live`, `LiveBounds`) and the store layout (`StoreLayout`: `<store>/<domain>/<name>/v<major>/<date>/part-*.parquet`, retention); `FileSwap` moved from BI (BI's `Layers`, `TableBuilder` and the runs store use it from the layer) | `tables.materialize` |
| Command line | `drishti.py tables` with `list`, `check`, `preview`, `lineage` and `migrate`, run as `java -jar drishti-server-*-exec.jar tables …` (no server, no Spring) | `tables.cli.TablesCli`, `tools/tablescli.py` |
| Migration | section 4's tool for files: every dataset and model key the shipped items use, `rachana: 1` datasets into tables and a model (Q19), the dry run's unified diff, `--check`, the report, the audit (`tables-migrate`), idempotent, refusals by name (`DRS-9195`); DEL into Rachana through `DelTranslator` (part A's translator plugs in; `TokenTranslator` stands in for the mechanical part and refuses `a if c else b`, `not`, `**`, `//` and comments by name), then the layer's rewrites on Rachana's tree: columns to paths (3.2), date parts, and **Q1's null rule** (`a != b` becomes `a != b && a != null` where `a` may be missing; `==` with both sides missable, `not in`, negations pushed inward; keys and literals never guarded; idempotent) | `tables.migrate` |
| Parity recorder | `BiFoundationRecorder` (enabled by `-Ddrishti.bi.foundation.record=true`), `BiFoundationCases`, `BiFoundationLive` and `BiFoundationParityTest` (skipped until the fixtures exist): every shipped model × the seven persons × three times × the grand total and each hierarchy level (rows and totals), each alias's rows per person (count, hash, masks), every model-bound report's visuals per person, the live replay; values compared exactly except floating point (1e-9 relative). Uses only BI's APIs before the switch and compiles against develop's server | `drishti-server` tests, `bi.mq` and `bi.engine` |

On the shipped `config/bi`, the dry run converts 31 of 34 items and refuses three by name: `trading-cashflows`' currency chains
(`a if c else b`: part A's translator) and the two `rachana: 1` live originals, whose converted forms ship beside them. `trading-risk`'s
*Open MTM* is the null rule's worked case.

**What remains for the switch.** Part A's translator (`DelTranslator`) and checked mode (`ExpressionChecker`), and Rachana's own tree and
printer in place of the layer's reader (`tables.expr`, listed in `OneGrammarRulesTest`'s exceptions); BI's `Layers`, `TableBuilder`,
`DatasetRefresher`, `Rollups`, `LiveTables`, `LiveTable`, `LiveFeed`, `LiveHistory` and `LiveAggregate` behind `Materialization` (they read
BI's `Dataset` and DEL's `Node` today, so they move when BI reads the layer's definitions); the compiler (MQ answered by the layer, section
3.5); the server's `ItemStore` over `drishti_bi_item` and the first-start migration of stored items, every version (Q17); reports, boards,
alerts, notebooks and golden questions in the migration; `group:` and Drishti SQL sources as summary tables; pack-generated tables (3.6);
the fixtures recorded on develop and committed, then the parity test green on the new path; the old files deleted; the server's refusal of
an old file (`DRS-9196`); the layer's settings (the store folder) and the editors.
