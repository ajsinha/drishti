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
# The function catalogue: every Drishti function, once

*Product owner, 2026-11-03: "let us implement these functions, wherever possible using native and BLAS if it makes sense, then we
should have a document with examples". This is the catalogue to review **before** the functions are built: every function Drishti has
or will have, under its **one** name, with what it means, an example on the demo data's names, its Excel and SAS equivalents, where it
runs and its status. The design is [ONE_GRAMMAR.md](ONE_GRAMMAR.md) (section 7); when the functions are built this becomes the user's
guide (Help → Functions, `FUNC`), each example then shown with its real result on the demo lake.*

**How to read the tables.**

| Column | Means |
|---|---|
| **Function** | its one name and arguments in Rachana (`[...]` optional) |
| **Example** | written on the demo data: trades (`$.LEGS[*].NOTIONAL`, `$.PRODUCT_TYPE`, `$.DESK`), BI model fields (`trades.NOTIONAL`), measures (`measure('MTM')`) |
| **Excel / SAS** | the nearest equivalent; documentation only, never a second name |
| **Runs** | **R** Rachana (a scalar, one value from values); **E** the engine (an aggregate or window, many rows to one value, mergeable or *holistic*); **N** the engine on native BLAS/LAPACK through FFM on Java 25, with the plain Java kernel as fallback; **M** a measure's own engine |
| **Status** | **built** (today, in DEL, Rachana or the engine: where, in brackets), **T1**, **T2**, **T3** (the tiers of ONE_GRAMMAR section 7), **MX** (matrix, with Java 25) |

**Rules every function keeps.** A null in gives a null out unless the function says otherwise (`coalesce`, `count`, `nmiss`). Numbers
are decimals where the data is (money is never turned into binary floating point by a function). An aggregate of amounts in two
currencies is refused unless converted (`convert`). A masked value makes the result masked. Every aggregate declares its mergeable form
or that it is holistic, so pivot totals and the cluster stay right.

**One name where two exist today.** Rachana and DEL overlap; the catalogue keeps one: `starts_with` (DEL `startswith`, Rachana
`startsWith`), `ends_with`, `days_between` (Rachana `days`), `min`/`max` as aggregates over a list or a group and `least`/`greatest`
for several arguments (Rachana's `min(a, b, ...)`), `cumulative` folded into `running`. The old spellings are read for existing Sutras
and models and rewritten by the migration tool.

## 1. Math and logic (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `abs(x)` | absolute value | `abs(trades.MTM)` | `ABS` / `ABS` | R | built (DEL, Rachana) |
| `round(x, [n])` | round half away from zero to n places | `round(trades.NOTIONAL / 1e6, 1)` | `ROUND` / `ROUND` | R | built (DEL) |
| `round_up(x, [n])`, `round_down(x, [n])` | away from or towards zero | `round_up(trades.QUANTITY / 100)` | `ROUNDUP`, `ROUNDDOWN` | R | T1 |
| `mround(x, m)` | to the nearest multiple of m | `mround(trades.PRICE, 0.25)` | `MROUND` | R | T1 |
| `ceiling(x, [m])`, `floor(x, [m])` | up or down to a multiple | `ceiling(size($.LEGS), 2)` | `CEILING.MATH`, `FLOOR.MATH` / `CEIL`, `FLOOR` | R | T1 |
| `mod(x, y)` | remainder | `mod(day($.TRADE_DATE), 7)` | `MOD` / `MOD` | R | T1 |
| `power(x, y)`, `sqrt(x)`, `exp(x)`, `ln(x)`, `log10(x)` | powers and logarithms | `sqrt(10) * measure('VAR')` (the 10-day lens is the VaR engine's own; this only shows the function) | `POWER`, `SQRT`, `EXP`, `LN`, `LOG10` | R | T1 |
| `sign(x)` | -1, 0 or 1 | `sign(trades.QUANTITY)` | `SIGN` / `SIGN` | R | T1 |
| `clamp(x, lo, hi)` | x held between lo and hi | `clamp(trades.LIMIT_USE, 0, 1)` | `MAX(lo, MIN(hi, x))` | R | T1 |
| `least(a, b, ...)`, `greatest(a, b, ...)` | smallest or largest of the arguments | `greatest(trades.MATURITY_DATE, asof())` | `MIN`, `MAX` of arguments / `MIN`, `MAX` | R | built (Rachana `min`, `max`) |
| `coalesce(a, b, ...)` | first not null | `coalesce($.STRATEGY, 'NONE')` | `IFNA` chains / `COALESCE` | R | built (DEL, Rachana) |
| `nonblank(a, b, ...)` | first not null and not blank | `nonblank($.TT_BOOK, $.PORTFOLIO)` | `IF(LEN(...)...)` | R | built (Rachana) |
| `if(c, a, b)` | a when c, else b | `if($.DIRECTION == 'SHORT', -1, 1)` | `IF` / `IFN`, `IFC` | R | T1 (the conditional expression exists in both languages) |
| `ifs(c1, a1, c2, a2, ..., [else])` | first true condition | `ifs($.QUANTITY > 1e6, 'large', $.QUANTITY > 1e5, 'medium', 'small')` | `IFS` / `SELECT` | R | T1 |
| `switch(x, v1, a1, v2, a2, ..., [else])` | value match | `switch($.LEG_TYPE, 'FIXED', 'F', 'FLOAT', 'V', '?')` | `SWITCH` / `SELECT` | R | T1 |
| `iferror(x, y)` | y when x fails (a division by zero, a bad date) | `iferror(trades.PNL / trades.NOTIONAL, 0)` | `IFERROR` | R | T1 |
| `let(name = expr, ..., result)` | names a part to reuse | `let(n = $.LEGS[0].NOTIONAL, q = $.QUANTITY, n * q)` | `LET` | R | T1 |

## 2. Text (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `upper(s)`, `lower(s)` | case | `upper($.COUNTERPARTY)` | `UPPER`, `LOWER` / `UPCASE`, `LOWCASE` | R | built |
| `len(s)` | characters | `len($.TRADE_ID)` | `LEN` / `LENGTH` | R | built (DEL) |
| `contains(s, part)`, `starts_with(s, p)`, `ends_with(s, p)` | ignore case | `starts_with($.PRODUCT_TYPE, 'Com')` | `SEARCH`, `LEFT(...)=` / `FIND`, `INDEX` | R | built |
| `matches(s, regex)`, `matches_case(s, regex)` | regular expression | `matches($.INSTRUMENT, 'USD 20[0-9]{2}')` | `REGEXTEST` / `PRXMATCH` | R | built (Rachana) |
| `within(x, range)`, `one_of(x, list)` | in a range, in a list | `one_of($.DESK, ['DESK-COMM', 'DESK-RATES'])` | `OR(x=...)` / `IN` | R | built (Rachana) |
| `concat(a, b, ...)` | joined | `concat($.DESK, ' / ', $.TT_BOOK)` | `CONCAT` / `CATS` | R | T1 |
| `left(s, n)`, `right(s, n)`, `mid(s, start, n)` | parts | `left($.TRADE_ID, 3)` | `LEFT`, `RIGHT`, `MID` / `SUBSTR` | R | T1 |
| `replace(s, old, new)` | substitution | `replace($.INSTRUMENT, ' ', '-')` | `SUBSTITUTE` / `TRANWRD` | R | T1 |
| `split(s, sep)` | a list of parts | `split($.TAGS, ',')` | `TEXTSPLIT` / `SCAN` | R | T1 |
| `trim(s)`, `pad(s, n, [char], [side])` | spaces, width | `pad($.LEG_ID, 3, '0')` | `TRIM`, `TEXT` / `STRIP`, `PUT` | R | T1 |
| `regex_extract(s, regex, [group])` | the matching part | `regex_extract($.INSTRUMENT, '20[0-9]{2}-[0-9]{2}')` | `REGEXEXTRACT` / `PRXCHANGE` | R | T1 |
| `fmt(x, format)` | a pack's display format | `fmt(trades.NOTIONAL, 'amount0')` | `TEXT` / `PUT` | R | built (Rachana) |
| `string_agg(x, sep, [distinct])` | the group's texts joined | `string_agg($.LEGS[*].CURRENCY, ', ', true)` | `TEXTJOIN` / `CATX` over a group | E | T1 |

## 3. Dates, on the bank's calendars (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `date(text)`, `timestamp(text)`, `today()`, `asof()` | constants and the query's date | `asof()` | `DATEVALUE`, `TODAY` / `MDY`, `TODAY` | R | built |
| `year(d)`, `quarter(d)`, `month(d)`, `week(d)`, `day(d)` | parts | `quarter($.TRADE_DATE)` | `YEAR`, `MONTH`, `WEEKNUM` / `YEAR`, `QTR` | R | built (DEL) |
| `days_between(a, b)` | calendar days | `days_between($.TRADE_DATE, $.MATURITY_DATE)` | `DAYS` / `INTCK('day', ...)` | R | built (DEL; Rachana `days`) |
| `business_days_between(a, b, [calendar])` | business days | `business_days_between($.TRADE_DATE, asof(), 'USNY')` | `NETWORKDAYS.INTL` / `INTCK('weekday', ...)` | R | built (DEL) |
| `shift_date(d, n, [unit], [calendar])` | moved by business days, weeks, months | `shift_date(asof(), -1)` | `WORKDAY.INTL`, `EDATE` / `INTNX` | R | built (DEL) |
| `eomonth(d, [n])`, `edate(d, n)` | month end, months later | `eomonth($.TRADE_DATE)` | `EOMONTH`, `EDATE` / `INTNX('month', d, n, 'end')` | R | T1 |
| `yearfrac(a, b, basis)` | year fraction: `ACT/360`, `ACT/365F`, `30/360`, `30E/360`, `ACT/ACT` | `yearfrac($.START_DATE, $.MATURITY_DATE, 'ACT/360')` | `YEARFRAC` / `YRDIF` | R | T1 |
| `intck(interval, a, b, [calendar])` | how many interval boundaries are crossed | `intck('month', $.TRADE_DATE, asof())` | `DATEDIF` / `INTCK` | R | T1 |
| `is_business_day(d, [calendar])` | a business day? | `is_business_day($.MATURITY_DATE, 'USNY')` | `NETWORKDAYS(d, d)=1` | R | T1 |

## 4. Aggregates (many rows or list elements to one value)

Each runs in the engine. *Mergeable* means a cluster or a pivot total combines partial results exactly; *holistic* means it needs the
values (bounded by the engine's holistic limits, exact on request, or a sketch when the person accepts ≈).

| Function | Means | Example | Excel / SAS | Form | Status |
|---|---|---|---|---|---|
| `sum(x, [where])` | total | `sum($.LEGS[*].NOTIONAL)`, `sum(trades.NOTIONAL, where = trades.DESK == 'DESK-COMM')` | `SUM`, `SUMIFS` / PROC MEANS `SUM` | mergeable | built (all three) |
| `count(x \| alias, [where])` | values not null, or rows | `count($.EVENTS[?@.EVENT_TYPE == 'BOOKED'])` | `COUNT`, `COUNTIFS` / `N` | mergeable | built |
| `nmiss(x)` | nulls | `nmiss($.LEGS[*].DISCOUNT_INDEX)` | `COUNTBLANK` / `NMISS` | mergeable | T1 |
| `avg(x, [where])` | mean | `avg(trades.PRICE)` | `AVERAGE`, `AVERAGEIFS` / `MEAN` | mergeable (sum, count) | built |
| `wavg(x, w)` | weighted mean | `wavg(trades.PRICE, trades.QUANTITY)` | `SUMPRODUCT/SUM` / `MEAN` with `WEIGHT` | mergeable | built (engine) |
| `min(x)`, `max(x)` | extremes (numbers, dates, text) | `max($.EVENTS[*].EVENT_DATE)` | `MIN`, `MAX`, `MAXIFS` / `MIN`, `MAX` | mergeable | built |
| `range(x)` | max − min | `range(trades.PRICE)` | `MAX-MIN` / `RANGE` | mergeable | T1 |
| `distinct(x)` | how many different values (exact; ≈ by sketch when asked) | `distinct(trades.COUNTERPARTY)` | `COUNTA(UNIQUE())` / PROC SQL `COUNT(DISTINCT)` | holistic (sketch: HLL) | built |
| `median(x)`, `percentile(x, p)` | middle, p-quantile (continuous) | `percentile(trades.NOTIONAL, 0.95)` | `MEDIAN`, `PERCENTILE.INC` / `MEDIAN`, `P95` | holistic (sketch: KLL) | built |
| `mode(x)` | most frequent | `mode($.LEGS[*].CURRENCY)` | `MODE.SNGL` / `MODE` | holistic | T1 |
| `iqr(x)` | p75 − p25 | `iqr(trades.PRICE)` | `QUARTILE(3)-QUARTILE(1)` / `QRANGE` | holistic | T1 |
| `variance(x)`, `stddev(x)` (sample; `_pop` for population) | spread | `stddev(trades.PNL)` | `VAR.S`, `STDEV.S` / `VAR`, `STD` | mergeable (moments) | built (engine) |
| `stderr(x)`, `cv(x)` | standard error, coefficient of variation | `cv(trades.PNL)` | `STDEV/SQRT(n)` / `STDERR`, `CV` | mergeable | T1 |
| `skewness(x)`, `kurtosis(x)` | shape | `skewness(trades.PNL)` | `SKEW`, `KURT` / `SKEWNESS`, `KURTOSIS` | mergeable (moments to 4) | T1 |
| `geomean(x)`, `harmean(x)` | means for rates | `geomean(1 + trades.RETURN) - 1` | `GEOMEAN`, `HARMEAN` / `GEOMEAN`, `HARMEAN` | mergeable (log sum, reciprocal sum) | T1 |
| `trimmean(x, pct)` | mean without the tails | `trimmean(trades.PRICE, 0.1)` | `TRIMMEAN` / PROC UNIVARIATE `TRIMMED=` | holistic | T1 |
| `corr(x, y)`, `covar(x, y)` | correlation, covariance | `corr(trades.PNL, trades.NOTIONAL)` | `CORREL`, `COVARIANCE.S` / PROC CORR | mergeable (sums of products) | T1 |
| `slope(y, x)`, `intercept(y, x)`, `rsq(y, x)` | least-squares line | `slope(trades.PNL, trades.QUANTITY)` | `SLOPE`, `INTERCEPT`, `RSQ` / PROC REG | mergeable | T1 |
| `ratio(num, den)` | num / den, null on 0 | `ratio(sum(trades.PNL), sum(trades.NOTIONAL))` | `IFERROR(a/b, "")` | of its parts | built (DEL) |
| `sum_once(x)`, `count_once(x)`, `avg_once(x)` | a coarser figure counted once per parent (a trade's in a per-leg view) | `sum_once($.QUANTITY)` beside `count($.LEGS[*])` | none | mergeable by parent id | built (engine) |
| `group(list, key, [agg])` | grouping inside one document | `group($.LEGS[*], @.CURRENCY, sum(@.NOTIONAL))` | `GROUPBY` / PROC MEANS `CLASS` | the engine's grouping | built (Rachana, moves to E) |

## 5. Shares, ranks and windows (over the cells of an answer)

| Function | Means | Example | Excel / SAS | Status |
|---|---|---|---|---|
| `pct_of_total(agg)`, `pct_of_row(agg)`, `pct_of_column(agg)` | share of the grand, row or column total | `pct_of_total(sum(trades.NOTIONAL))` | PivotTable "% of grand total" / TABULATE `PCTSUM` | T1 (DEL `share_of` covers part) |
| `pct_of_parent(agg, [hierarchy])` | share of the parent level | `pct_of_parent(sum(trades.NOTIONAL), 'MGMT')` | "% of parent row" / `ROWPCTSUM` | built (DEL `parent_of`) |
| `share_of(agg, over)` | share with chosen fields ignored | `share_of(sum(trades.NOTIONAL), over = [trades.DESK])` | none | built (DEL) |
| `rank(agg, [desc], [by])`, `dense_rank`, `percent_rank` | position | `rank(sum(trades.NOTIONAL), by = [trades.DESK])` | `RANK.EQ`, `PERCENTRANK` / PROC RANK | built (DEL) |
| `ntile(agg, n)` | deciles, quartiles … | `ntile(sum(trades.NOTIONAL), 10)` | `QUARTILE` buckets / PROC RANK `GROUPS=10` | T1 |
| `kth_largest(x, k)`, `kth_smallest(x, k)` | k-th value | `kth_largest(trades.NOTIONAL, 3)` | `LARGE`, `SMALL` / PROC UNIVARIATE extremes | T1 |
| `top_n(n, agg)`, `bottom_n(n, agg)` with Others | the n leading members, the rest as Others | `top_n(10, sum(trades.NOTIONAL))` | PivotTable Top 10 filter | built (engine, Top N and Others) |
| `lag(agg, n, order)`, `lead(agg, n, order)` | n cells before or after | `lag(sum(trades.PNL), 1, trades.TRADE_DATE.month)` | `OFFSET` / `LAG` | built (DEL) |
| `diff(agg, [n], order)`, `pct_change(agg, [n], order)` | change from n cells before | `pct_change(sum(trades.NOTIONAL), 1, month)` | `B2-B1` / `DIF` | T1 |
| `moving_avg`, `moving_sum`, `moving_min`, `moving_max` (agg, n, order) | rolling windows | `moving_avg(sum(trades.PNL), 20, day)` | `AVERAGE(OFFSET())` / PROC EXPAND | built (DEL) |
| `running(agg, date)` | running total | `running(sum(trades.PNL), trades.TRADE_DATE)` | running total / `RETAIN` | built (DEL; `cumulative` folds in) |
| `ytd`, `mtd`, `qtd`, `fiscal_ytd` (agg, date) | to date | `ytd(sum(trades.PNL), trades.TRADE_DATE)` | none / `INTNX` windows | built (DEL) |
| `prior_period`, `same_period_last_year` (agg, date) | comparisons | `same_period_last_year(sum(trades.PNL), trades.TRADE_DATE)` | none | built (DEL) |
| `opening`, `closing`, `first`, `last`, `avg_over` (agg, date, [period]) | balances | `closing(sum(positions.BALANCE), positions.DATE, 'month')` | none / `FIRST.`, `LAST.` | built (DEL) |
| `calc(agg, where, ignore, keep, via)` | the aggregate in an edited filter context | `calc(sum(trades.NOTIONAL), ignore = [trades.DESK])` | none (DAX `CALCULATE`) | built (DEL) |
| `as_of(agg, date)` | the aggregate on another business date | `as_of(sum(trades.NOTIONAL), shift_date(asof(), -1))` | none | built (DEL) |

## 6. Lists (Excel's dynamic arrays, over `[*]`)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `size(list)` | how many elements | `size($.LEGS)` | `ROWS`, `COUNTA` / `DIM` | R | built (Rachana) |
| `first(list)`, `last(list)` | ends | `last($.EVENTS)` | `TAKE(x, 1)`, `TAKE(x, -1)` | R | built (Rachana) |
| `flatten(list)` | a list of lists as one | `flatten($.LEGS[*].CASH_FLOWS)` | `TOCOL` | R | built (Rachana) |
| `sort(list, [key], [desc])`, `unique(list)` | order, distinct | `unique($.LEGS[*].CURRENCY)` | `SORT`, `SORTBY`, `UNIQUE` | R | T1 |
| `take(list, n)`, `drop(list, n)` | the first or last n | `take(sort($.EVENTS, @.EVENT_DATE, true), 3)` | `TAKE`, `DROP` | R | T1 |
| `sequence(n, [start], [step])` | a list of numbers | `sequence(12)` | `SEQUENCE` / `DO` loop | R | T1 |
| `map(list, expr)` | each element transformed (`@` the element) | `map($.LEGS, @.NOTIONAL * $.QUANTITY)` | `MAP`, `LAMBDA` | R | T1 |
| `reduce(list, init, expr)` | folded (`@acc`, `@`) | `reduce($.EVENTS, '', concat(@acc, @.EVENT_TYPE))` | `REDUCE`, `LAMBDA` / `RETAIN` | R | T1 (a plain sum with `reduce` is refused: use `sum`) |
| `link(id, kind, [label])` | a link to an entity | `link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')` | `HYPERLINK` | R | built (Rachana) |

## 7. Conversion, lookup and measures

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `fx(ccy, to, [at])` | an FX rate | `fx('EUR', 'USD')` | none | R | built (DEL) |
| `convert(agg, [to], [at])` | an aggregate of many currencies in one | `convert(sum(trades.NOTIONAL), 'USD')` | none | E | built (DEL) |
| `related(alias.column)` | a column of the row a reference points at | `related(counterparties.RATING)` | `XLOOKUP` / a `MERGE` | E | built (DEL) |
| `measure(name, [scope], [options])` | a model measure, or a computed measure from its own engine, exactly | `measure('VAR', 'DESK:DESK-COMM')` | none | M | built for model measures (DEL); for engine measures planned (ONE_GRAMMAR part 3) |
| `cell(view, coordinates)` | a cell of a saved pivot, linked back | `cell('desk-pnl', DESK: 'DESK-COMM', month: '2026-09')` | `GETPIVOTDATA` | E | planned (ONE_GRAMMAR part 3) |

## 8. Finance on given flows (tier 2)

Arithmetic on cash flows and rates that the data already carries; pricing a trade from market data is QLRisk's, and VaR, ES, exposure,
FRTB-SA and SIMM come only from their engines through `measure(...)`.

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `npv(rate, flows)`, `xnpv(rate, flows, dates)` | present value | `xnpv(0.045, $.LEGS[0].CASH_FLOWS[*].AMOUNT, $.LEGS[0].CASH_FLOWS[*].PAY_DATE)` | `NPV`, `XNPV` / `FINANCE('XNPV')` | R | T2 |
| `irr(flows)`, `xirr(flows, dates)` | internal rate of return | `xirr(...)` | `IRR`, `XIRR` | R | T2 |
| `pv`, `fv`, `pmt`, `rate` | annuity arithmetic | `pmt(0.05 / 12, 360, 1e6)` | `PV`, `FV`, `PMT`, `RATE` / `MORT` | R | T2 |
| `discount_factor(curve, date)`, `forward_rate(curve, a, b)` | from an `ir-curve` kind, interpolation stated | `discount_factor('USD-SOFR', $.MATURITY_DATE)` | none | R | T2 |
| `accrued(coupon, last, next, basis)` | accrued interest | `accrued(0.04, date('2026-06-15'), date('2026-12-15'), '30/360')` | `ACCRINT` | R | T2 |
| `macaulay_duration`, `modified_duration`, `convexity` (flows, dates, yield) | on given flows | `modified_duration(...)` | `DURATION`, `MDURATION` | R | T2 |
| `norm_dist`, `norm_inv`, `t_dist`, `chisq_dist`, `binom_dist` | distributions | `norm_inv(0.99)` | `NORM.S.INV`, `T.DIST` / `PROBNORM`, `QUANTILE` | R | T2 |

## 9. Matrices, at native speed (Java 25)

The engine calls BLAS and LAPACK (OpenBLAS) through the Foreign Function & Memory API, in tiles, with BLAS's threads at one and
Drishti's pool over the tiles; a start-up canary checks the parity rule (a relative 1e-12, the selected scenario identical) and falls back
to the Java kernel, shown in Admin → Health → Native. Small matrices stay in Java. Not on a machine where no BLAS library is allowed
(the Windows QA machine), which runs the Java kernel.

| Function | Means | Example | Excel / SAS | Native call | Status |
|---|---|---|---|---|---|
| `mmult(a, b)`, `transpose(a)` | product, transpose | `mmult(weights, returns)` | `MMULT`, `TRANSPOSE` / IML `*`, `T()` | `dgemm` | MX |
| `solve(a, b)`, `inverse(a)`, `det(a)` | linear systems | `solve(xtx, xty)` (the normal equations of a regression) | `MINVERSE`, `MDETERM` / IML `SOLVE`, `INV`, `DET` | `dgesv`, `dgetrf`/`dgetri` | MX |
| `cov_matrix(columns)`, `corr_matrix(columns)` | covariance, correlation of many series | `corr_matrix(pnl_by_desk)` | `COVARIANCE.S` grid / PROC CORR `COV` | `dsyrk` | MX |
| `eigen(a)`, `pca(columns, k)` | eigenvalues, principal components | `pca(scenario_pnl_by_desk, 3)` | none / PROC PRINCOMP | `dsyevd` | MX |
| `cholesky(a)` | factor for correlated simulation | `cholesky(corr_matrix(factors))` | none / IML `ROOT` | `dpotrf` | MX |
| many VaR scopes in one pass (inside the VaR engine, not a catalogue function) | scope vectors for a board pack | the VaR engine, through `measure(...)` | none | `dgemm` over float32 tiles widened to float64 | MX, if the benchmark's go rule is met |

## 10. Candidates (tier 3)

Frequency tables with chi-square (PROC FREQ), regression with several variables and its statistics (PROC REG, on `solve`), `forecast`
and `trend` (Excel's linear forms), `winsorize(x, pct)` and `zscore(x)` (PROC STDIZE, PROC UNIVARIATE), histogram bins as a dimension.
Each decided one by one.
