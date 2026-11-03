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
we have". The design below is the recommendation the product owner accepted. Status: parts 1 to 3 and 5 **built** in 2.9.11.b (as
built: section 8); part 4 designed, built in a release of its own after 2.9.11.d, [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).*

**Read with:** [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) (the grammar), [PIVOT_ENGINE.md](PIVOT_ENGINE.md) (the engine,
section 6.7 for paths into documents), [BI_DESIGN.md](BI_DESIGN.md) (section 3.3, DEL).

## 1. What existed before 2.9.11.b, and why it must become one

Drishti computed with three overlapping languages and three implementations of the same aggregates
(parts 1 to 3 made the engine the only one; DEL remains, part 4):

| Where | What it does | Its aggregates |
|---|---|---|
| Rachana's expressions (`drishti-rachana`, package `rachana.el`: `Parser`, `ElCompiler`, `Functions`) | Sutra bindings, titles, filters: paths (`$.LEGS[0].INDEX.NAME`, `[*]`, `[?...]`), templates, links | its own `sum`, `min`, `max`, `group` over lists (since 2.9.11.b: the engine's, section 8) |
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
| `cell(view, field, member, ...)` | a cell of a saved pivot view, as the view computes it | `cell('ann/desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')` |
| a **pivot panel** in a Sutra | its groupings and value written as Rachana paths and an aggregate, answered by the engine | `kind: pivot`, `by: "@.BOOK"`, `across: "@.CURRENCY"`, `value: sum(@.MTM)` |

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
- The function catalogue is one generated page (Help → Functions, [FUNCTIONS.md](../guides/FUNCTIONS.md)), listing each function once
  with where it runs. (`FUNC` stays the command directory it already was.)

## 6. The order of work

| Part | What | Size | Release |
|---|---|---|---|
| 1 and 2 | Rachana's function table open to providers; the engine registers its aggregates; Rachana's own list aggregates removed; parity test on every shipped Sutra | S to M | 2.9.11.b |
| 3 | `measure`, `cell`, `group` on the engine, the Sutra pivot panel in paths, the catalogue page | M | 2.9.11.b |
| 5 | the guards | S | 2.9.11.b |
| 4 | DEL folded into Rachana, its parity proof, DEL deleted | L | a release of its own after 2.9.11.d, designed first |

## 7. The function catalogue: what to add, from Excel and SAS

*Product owner, 2026-11-03: "evaluate what other functions we should build into Drishti like sum ... what Excel and SAS provide ...
let us expand wherever you think ok".*

**The full list, function by function** (one name each, an example, the Excel and SAS equivalents, where it runs, its status):
[FUNCTION_CATALOGUE.md](FUNCTION_CATALOGUE.md).

**What exists (2026-11-03).** BI's DEL has 59 functions (`drishti-server/src/main/resources/bi/expr/functions.yaml`): scalar (18), aggregate
(9: `sum`, `avg`, `min`, `max`, `count`, `distinct`, `ratio`, `percentile`, `avg_by`), time (10: `ytd`, `mtd`, `qtd`, `prior_period`,
`same_period_last_year`, `fiscal_ytd` …), windows (10: `lag`, `lead`, `rank`, `dense_rank`, `percent_rank`, `moving_*`, `running`,
`cumulative`), balances (5: `opening`, `closing`, `last`, `first`, `avg_over`), filter (3: `calc`, `share_of`, `parent_of`), conversion (2:
`fx`, `convert`), lookup and measure. Rachana has about 23 (`sum`, `min`, `max`, `size`, `flatten`, `group`, `coalesce`, `fmt`, text
tests, `days`, `today` …). The pivot engine has about 14 aggregates (`sum`, `count`, `avg`, `min`, `max`, `wavg`, `median`,
`percentile`, `variance`, `stddev`, `count_distinct`, and the `_once` forms). Each family below lands **once**: scalars in Rachana,
aggregates and windows in the engine, as part 2 requires.

**What every new function must have** (the catalogue entry checks it): typed arguments and result, the null rule, a mergeable form or
the word *holistic* (so totals and the cluster stay right), currency and unit rules (a sum of two currencies is refused unless converted),
masks respected, a worked example that runs in the docs test, and its **Excel equivalent** where one exists, so the pivot's Excel export
writes a live formula and the docs map Excel and SAS names to Drishti's (one name in Drishti; the others are documentation, never aliases).

### 7.1 Tier 1: the everyday analyst's set (Excel's and SAS PROC MEANS/TABULATE/RANK core)

| Family | Add | Excel / SAS it answers | Where |
|---|---|---|---|
| Conditional aggregates | any aggregate with `where` (exists in DEL), and in paths `sum($.LEGS[?@.LEG_TYPE == 'FIXED'][*].NOTIONAL)` | `SUMIFS`, `COUNTIFS`, `AVERAGEIFS`, `MAXIFS`; SAS `WHERE=` | engine |
| Descriptive statistics | `nmiss` (nulls), `range`, `mode`, `stderr`, `cv`, `skewness`, `kurtosis`, `iqr`, `geomean`, `harmean`, `trimmean(x, pct)` | `MODE`, `SKEW`, `KURT`, `GEOMEAN`, `TRIMMEAN`; PROC MEANS `NMISS CV SKEWNESS KURTOSIS STDERR QRANGE` | engine (moments are mergeable) |
| Two-variable statistics | `corr`, `covar`, `slope`, `intercept`, `rsq` | `CORREL`, `COVARIANCE.S`, `SLOPE`, `INTERCEPT`, `RSQ`; PROC CORR | engine (mergeable sums of products) |
| Shares of totals | `pct_of_total`, `pct_of_row`, `pct_of_column`, `pct_of_parent` (DEL's `share_of` and `parent_of` move here) | Excel PivotTable "Show values as %"; PROC TABULATE `PCTSUM ROWPCTSUM COLPCTSUM` | engine |
| Ranks and tops | `rank`, `dense_rank`, `percent_rank` (from DEL), `ntile(n)` (deciles, quartiles), `kth_largest`, `kth_smallest`, `top_n`, `bottom_n` | `RANK`, `LARGE`, `SMALL`, `QUARTILE`; PROC RANK `GROUPS=` | engine |
| Windows and time | DEL's `lag`, `lead`, `moving_*`, `running`, `cumulative`, `ytd`, `mtd`, `qtd`, `prior_period`, `same_period_last_year`, `opening`, `closing` move to the engine; add `diff` and `pct_change` | SAS `LAG`, `DIF`, PROC EXPAND; Excel `OFFSET` patterns | engine |
| Text aggregate | `string_agg(x, sep, distinct?)` | `TEXTJOIN`; SAS `CATX` over a group | engine |
| Math | `round`, `round_up`, `round_down`, `mround`, `ceiling`, `floor`, `mod`, `power`, `sqrt`, `exp`, `ln`, `log10`, `sign`, `clamp(x, lo, hi)` | the Excel math set | Rachana |
| Logic | `if`, `ifs`, `switch`, `iferror`, `let(name = expr, ...)` | `IF`, `IFS`, `SWITCH`, `IFERROR`, `LET` | Rachana |
| Text | `concat`, `left`, `right`, `mid`, `replace`, `split`, `trim`, `pad`, `regex_extract` | `LEFT`, `MID`, `SUBSTITUTE`, `TEXTSPLIT`, `TRIM`, `REGEXEXTRACT` | Rachana |
| Lists (Excel's dynamic arrays) | `sort`, `unique`, `take`, `drop`, `sequence`, `map(list, expr)`, `reduce(list, init, expr)` over `@` | `SORT`, `UNIQUE`, `TAKE`, `SEQUENCE`, `MAP`, `REDUCE` | Rachana (no aggregation: `reduce` with `+` is refused in favour of `sum`) |
| Dates with the bank's calendars | `edate`, `eomonth`, `workday(d, n, calendar)`, `networkdays(a, b, calendar)`, `yearfrac(a, b, basis)` (ACT/360, ACT/365F, 30/360, ACT/ACT), `intck(interval, a, b)`, `intnx(interval, d, n)` | `EDATE`, `EOMONTH`, `WORKDAY.INTL`, `NETWORKDAYS.INTL`, `YEARFRAC`; SAS `INTCK`, `INTNX` | Rachana, on the calendars Drishti already has (`USNY` …) |

### 7.2 Tier 2: finance on flows that are given

| Add | Excel it answers | Note |
|---|---|---|
| `npv(rate, flows)`, `xnpv(rate, flows, dates)`, `irr`, `xirr`, `pv`, `fv`, `pmt`, `rate` | the Excel financial set | on cash flows a document or a table carries (`$.LEGS[*].CASH_FLOWS[*]`) |
| `discount_factor(curve, date)`, `forward_rate(curve, a, b)` | none | reads an `ir-curve` kind; interpolation stated |
| `accrued(coupon, last, next, basis)`, `macaulay_duration`, `modified_duration`, `convexity` on given flows and a yield | `ACCRINT`, `DURATION`, `MDURATION` | arithmetic on given flows only |
| `norm_dist`, `norm_inv`, `t_dist`, `chisq_dist`, `binom_dist` | `NORM.S.DIST`, `NORM.S.INV`, `T.DIST`, `CHISQ.DIST`, `BINOM.DIST` | statistics for tests a person builds (the VaR engine's backtesting keeps its own) |

**The boundary.** Pricing a trade from market data (a model, a curve build, a volatility surface) is QLRisk's job as the quant engine
(design only until its API is stable), and VaR, ES, exposure, FRTB-SA and SIMM come only from their own engines through `measure(...)`:
the catalogue never re-implements them, and a function that would (a `var(vector, p)` over a scenario vector) is not added.

### 7.3 Tier 3: SAS-style analysis (candidates)

Frequency tables with chi-square (PROC FREQ), regression with several variables (PROC REG), `forecast` and `trend` (Excel's linear forms),
winsorising and z-score outlier flags (PROC STDIZE, PROC UNIVARIATE), histogram bins as a dimension. Each a candidate for the pivot's P4
or later, decided one by one.

### 7.3a Matrix functions at native speed (with Java 25)

*Product owner, 2026-11-03: "after JDK 25 is activated ... very fast and native computation of some of these functions like large
matrix multiplication using BLAS and NumPy via FFM".*

| Add | Excel / SAS it answers | Drishti uses |
|---|---|---|
| `mmult`, `transpose`, `solve`, `inverse`, `det` | `MMULT`, `TRANSPOSE`, `MINVERSE`, `MDETERM`; SAS PROC IML | regression with several variables (tier 3), what-if batches |
| `cov_matrix`, `corr_matrix` over columns or scenario vectors | `COVARIANCE.S` across a range; PROC CORR | risk-factor correlation views |
| `eigen`, `pca(k)`, `cholesky` | PROC PRINCOMP, PROC IML | the parked statistical factor VaR; correlated simulation |

**How they run.** One implementation in the engine (`drishti-bicompute`), calling BLAS and LAPACK through the Foreign Function & Memory
API on Java 25 (in Java 21 that API is a preview), as [HIGH_PERFORMANCE_NATIVE.md](HIGH_PERFORMANCE_NATIVE.md) section 6 designs:
OpenBLAS only, CBLAS and LAPACKE downcalls, BLAS's own threads set to one with Drishti's pool over tiles, the parity rule of a relative
1e-12 with the selected scenario identical, a start-up canary that falls back to the Java kernel, and not on the Windows QA machine unless
the product owner allows a system library. Matrices small enough (an FRTB bucket's or SIMM's correlations) stay in plain Java, where a
native call costs more than it saves. Python custom functions (P2's sandboxed pool) already have NumPy, whose wheels bundle their own
OpenBLAS: that serves a person's own code and is not a second implementation of a catalogue function.

**What is true today.** No BLAS is bound in Java yet; the Java side uses FFM only to map files (`FileMaps`). The benchmark found that
today's VaR sums are bound by memory bandwidth, so BLAS pays only for many scopes in one pass (GEMM over K scopes); its go rule is 1.5
times the Java multi-scope kernel at K of 100 or more (BENCHMARK.md, HIGH_PERFORMANCE_NATIVE.md section 6.4). The development machine
has the reference BLAS and LAPACK, not OpenBLAS: the system package `libopenblas0` is needed for the Java binding.

### 7.4 When

| Tier | Size | Release |
|---|---|---|
| 1 | M to L | **2.9.11.e**, with DEL folded into Rachana: DEL's 59 functions move once, and the new ones are added in the same move, never twice |
| 2 | M | the release after, coordinated with the QLRisk boundary |
| 3 | per function | candidates |
| matrix functions (7.3a) | M | with the Java 25 activation (2.9.13 or later), after the BLAS benchmark's go |

## 8. As built (2.9.11.b: parts 1 to 3 and 5)

**Parts 1 and 2: the table open to providers.** `drishti-rachana` declares `rachana.el.FunctionProvider` (its functions with their
arguments, form and description, and the `Grouping` behind `group`); `Aggregates` finds the providers once by `ServiceLoader` when the
table is built and registers their functions into `Functions`, the one table. `drishti-bicompute` provides them
(`bicompute.rachana.EngineFunctions`, listed in `META-INF/services`): `sum`, `count`, `avg`, `min`, `max`, `median`, `percentile`,
`distinct`, `wavg`, `variance`, `stddev`, `variance_pop`, `stddev_pop`, `sum_once`, `count_once`, `avg_once` (`ListAggregates`: the
list's values made a `Batch` and run through the very `AggFunction` a pivot cell runs), the grouping (`ListGrouping`), `measure` and
`cell`. Rachana's own list aggregates are deleted (`ListFunctions.sum` and `group`'s sums, `min`/`max` over one list); `min` and `max`
stay Rachana's entries for several arguments (the old spelling of `least`/`greatest`) and hand the one-list form to the engine; `group`
is a Rachana form (it binds `@` for the key and the inputs) that hands the grouping to the engine. Without the engine on the class path an
aggregate compiles and is refused with the reason when evaluated (`DRS-2102`). The dependency is one way: `drishti-bicompute` depends on
`drishti-rachana` (without its Spring wiring), never the reverse.

**Parity.** `SutraAggregateParityTest` (drishti-bicompute) evaluates every call of `sum`, `min`, `max` and `group` written in every Sutra
and fragment of `config/packs/*/sutras` on every test case of its pack (`tests/<sutra>/cases.jsonl`), at the document's root and on every
element of every list in it, through the engine and through the old code kept as the test's record of "before"
(`LegacyListAggregates`, test code only, deleted after 2.9.11.b). On the shipped packs: 39 calls, 153,888 comparisons, 62,623 of them
with a figure, no figure different; 30 totals of fractional numbers differ in the last binary digit only (the engine's sum is
compensated, `0.110261` where the plain sum gave `0.11026100000000001`), never in a figure shown.

**Part 3.** `group(list, key, aggregate)` takes the quoted form every Sutra has (`group($.TRADES, 'PRODUCT_TYPE', 'NOTIONAL')`, results
unchanged) and expressions over `@` with any engine aggregate (`group($.LEGS, @.CURRENCY, percentile(@.NOTIONAL, 0.95))`).
`measure(name, scope?, options?)` and `cell(view, field, member, ...)` go through the engine's SPI `bicompute.spi.FigureSource`, which the
server implements (`server.pivot.RachanaFigures`, registered at start): `measure` asks the compute engine whose pivot provider offers the
name as its own measure (the VaR engine's `var`, `es`, `pv`, `trades`) for one cell, its scope's members, exactly as that engine computes
it; `cell` opens a saved pivot view the person may open and reads one cell. Both answer for the person of the request (their
entitlements and masks), are refused with the reason when the pivot engine is switched off or no server answers, and never sum a
figure. Model measures (BI's) through `measure(...)` arrive with part 4. The Sutra pivot panel (`kind: pivot`, computed by the engine
since P1) takes Rachana paths for `by` and `across` and an engine aggregate as its `value` (`value: sum(@.MTM)`,
`percentile(@.PNL, 0.99)`); the `desk` Sutra's MTM grid is written so (`Functions.aggregateCall` gives the engine the call, Rachana
the per-row input).

**Part 5.** `OneGrammarRulesTest` (drishti-it, ArchUnit): no class outside `drishti-bicompute` implements `AggFunction` or
`FunctionProvider`; Rachana never depends on the engine; only `drishti-rachana` parses expressions, with the listed exceptions until
2.9.11.e: DEL (`server.bi.expr`), BI's dataset expressions (`ExprParser`), model calculations (`CalculationParser`), the pivot's formulas
in DEL syntax (`bicompute.formula`) and the VaR scope selector (`ScopeExpr`). The console check
(`drishti-console/tests/test_console_does_not_aggregate.py`) finds every aggregation idiom in console Python and JavaScript code (strings
and comments left out: a measure's text sent to the engine is not a sum) and lets through plain counting, sizes and promise plumbing;
every other one is listed with its reason, layout arithmetic or the console's own data (usage, rows loaded, a pack draft's sample
documents, a person's own Python cell and the stand-in pricer it calls), and there is no third reason: **the browser computes nothing**.
A figure the console needs over rows it read (the trading landing's MtM, P&L, DV01, by book and trader and the largest counterparty's
share; the market landing's CDS median, its move, each move's sigma and the Explorer's group totals; the Home facts' `sum` and fund
`overlap`; a column filter's average) is asked of the pivot engine through `POST /api/v1/pivot/inline`
(`drishti-console/core/engine_figures.py`: the rows and the measures, `sum(MTM)`, `median(S)`, `abs(sum(LAST)) / stddev_pop(H)`,
`least(sum(A), sum(B))`) and shown as the engine answers; with the engine switched off the figure is left empty with the reason. The
pivot chart's diversification bar is the engine's gap of the charted column (`diversificationByColumn` on each open row, beside
`diversification` at the column total). `ConsoleFiguresTest` (drishti-bicompute) proves each on the console tests' fixtures. The
function catalogue page is generated from the registries
(`FunctionCataloguePage`, [FUNCTIONS.md](../guides/FUNCTIONS.md)) and checked against [FUNCTION_CATALOGUE.md](FUNCTION_CATALOGUE.md)'s
names (`FunctionCataloguePageTest`).
