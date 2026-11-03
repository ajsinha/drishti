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
guide, each example then shown with its real result on the demo lake. Every function registered today, generated from the registries
and checked against this page's names, is [FUNCTIONS.md](../guides/FUNCTIONS.md) (Help → Functions).*

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
and models and rewritten by the migration tool. Rachana has the one names (`starts_with`, `ends_with`, `days_between`, `least`,
`greatest`, `matches_case`, `one_of`); its old spellings `startsWith`, `days`, `matchesCase`, `oneOf` and `min`/`max` with several
arguments are read with the results they always gave. Rachana's tier-1 functions, their rules and an example of each on the demo trade
are in [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#functions). Since 2.9.11.e Rachana writes lists (`['DESK-COMM', 'DESK-RATES']`), a strict `in` (an empty list holds nothing) beside
`one_of` (an empty choice holds everything, for controls), named arguments (`where = ...`) and the parent path `^`; checked mode
types an expression where the types are known (BI_FOUNDATION_2911E.md section 2).

## 1. Math and logic (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `abs(x)` | absolute value | `abs(trades.MTM)` | `ABS` / `ABS` | R | built (DEL, Rachana) |
| `round(x, [n])` | round half away from zero to n places | `round(trades.NOTIONAL / 1e6, n = 1)` | `ROUND` / `ROUND` | R | built (Rachana; DEL until part B) |
| `round_up(x, [n])`, `round_down(x, [n])` | away from or towards zero | `round_up(trades.QUANTITY / 100)` | `ROUNDUP`, `ROUNDDOWN` | R | built (Rachana) |
| `mround(x, m)` | to the nearest multiple of m | `mround(trades.PRICE, 0.25)` | `MROUND` | R | built (Rachana) |
| `ceiling(x, [m])`, `floor(x, [m])` | up or down to a multiple | `ceiling(size($.LEGS), 2)` | `CEILING.MATH`, `FLOOR.MATH` / `CEIL`, `FLOOR` | R | built (Rachana) |
| `mod(x, y)` | remainder | `mod(day($.TRADE_DATE), 7)` | `MOD` / `MOD` | R | built (Rachana) |
| `power(x, y)`, `sqrt(x)`, `exp(x)`, `ln(x)`, `log10(x)` | powers and logarithms | `sqrt(10) * measure('VAR')` (the 10-day lens is the VaR engine's own; this only shows the function) | `POWER`, `SQRT`, `EXP`, `LN`, `LOG10` | R | built (Rachana) |
| `sign(x)` | -1, 0 or 1 | `sign(trades.QUANTITY)` | `SIGN` / `SIGN` | R | built (Rachana) |
| `clamp(x, lo, hi)` | x held between lo and hi | `clamp(trades.LIMIT_USE, 0, 1)` | `MAX(lo, MIN(hi, x))` | R | built (Rachana) |
| `least(a, b, ...)`, `greatest(a, b, ...)` | smallest or largest of the arguments | `greatest(trades.MATURITY_DATE, asof())` | `MIN`, `MAX` of arguments / `MIN`, `MAX` | R | built (Rachana; old spelling `min`, `max` with several arguments) |
| `coalesce(a, b, ...)` | first not null | `coalesce($.STRATEGY, 'NONE')` | `IFNA` chains / `COALESCE` | R | built (DEL, Rachana) |
| `nonblank(a, b, ...)` | first not null and not blank | `nonblank($.TT_BOOK, $.PORTFOLIO)` | `IF(LEN(...)...)` | R | built (Rachana) |
| `if(c, a, b)` | a when c, else b | `if($.DIRECTION == 'SHORT', -1, 1)` | `IF` / `IFN`, `IFC` | R | built (Rachana) |
| `ifs(c1, a1, c2, a2, ..., [else])` | first true condition | `ifs($.QUANTITY > 1e6, 'large', $.QUANTITY > 1e5, 'medium', 'small')` | `IFS` / `SELECT` | R | built (Rachana) |
| `switch(x, v1, a1, v2, a2, ..., [else])` | value match | `switch($.LEG_TYPE, 'FIXED', 'F', 'FLOAT', 'V', '?')` | `SWITCH` / `SELECT` | R | built (Rachana) |
| `iferror(x, y)` | y when x fails (a division by zero, a bad date) | `iferror(trades.PNL / trades.NOTIONAL, 0)` | `IFERROR` | R | built (Rachana) |
| `let(name = expr, ..., result)` | names a part to reuse | `let(n = $.LEGS[0].NOTIONAL, q = $.QUANTITY, n * q)` | `LET` | R | built (Rachana) |

## 2. Text (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `upper(s)`, `lower(s)` | case; null in, null out | `upper($.COUNTERPARTY)` | `UPPER`, `LOWER` / `UPCASE`, `LOWCASE` | R | built (Rachana) |
| `len(s)` | characters; `len(null)` is null (`size` counts a list) | `len($.TRADE_ID)` | `LEN` / `LENGTH` | R | built (Rachana; DEL until part B) |
| `contains(s, part)`, `starts_with(s, p)`, `ends_with(s, p)` | ignore case | `starts_with($.PRODUCT_TYPE, 'Com')` | `SEARCH`, `LEFT(...)=` / `FIND`, `INDEX` | R | built (Rachana; old spelling `startsWith`) |
| `matches(s, regex)`, `matches_case(s, regex)` | regular expression | `matches($.INSTRUMENT, 'USD 20[0-9]{2}')` | `REGEXTEST` / `PRXMATCH` | R | built (Rachana; old spelling `matchesCase`) |
| `within(x, range)`, `one_of(x, list)` | in a range, in a list | `one_of($.DESK, ['DESK-COMM', 'DESK-RATES'])` | `OR(x=...)` / `IN` | R | built (Rachana; old spelling `oneOf`) |
| `concat(a, b, ...)` | joined | `concat($.DESK, ' / ', $.TT_BOOK)` | `CONCAT` / `CATS` | R | built (Rachana) |
| `left(s, n)`, `right(s, n)`, `mid(s, start, n)` | parts | `left($.TRADE_ID, 3)` | `LEFT`, `RIGHT`, `MID` / `SUBSTR` | R | built (Rachana) |
| `replace(s, old, new)` | substitution | `replace($.INSTRUMENT, ' ', '-')` | `SUBSTITUTE` / `TRANWRD` | R | built (Rachana) |
| `split(s, sep)` | a list of parts | `split($.TAGS, ',')` | `TEXTSPLIT` / `SCAN` | R | built (Rachana) |
| `trim(s)`, `pad(s, n, [char], [side])` | spaces, width | `pad($.LEG_ID, 3, '0')` | `TRIM`, `TEXT` / `STRIP`, `PUT` | R | built (Rachana) |
| `regex_extract(s, regex, [group])` | the matching part | `regex_extract($.INSTRUMENT, '20[0-9]{2}-[0-9]{2}')` | `REGEXEXTRACT` / `PRXCHANGE` | R | built (Rachana) |
| `fmt(x, format)` | a pack's display format | `fmt(trades.NOTIONAL, 'amount0')` | `TEXT` / `PUT` | R | built (Rachana) |
| `string_agg(x, sep, [distinct])` | the group's texts joined in text order (distinct: each once); at most 1,000 values, past that no figure, said in words | `string_agg($.LEGS[*].CURRENCY, ', ', true)` | `TEXTJOIN` / `CATX` over a group | E | built (engine) |

## 3. Dates, on the bank's calendars (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `date(text)`, `timestamp(text)`, `asof()` | constants and the query's date (`today()` is read as `asof()`, Q7) | `asof()` | `DATEVALUE`, `TODAY` / `MDY`, `TODAY` | R | built (Rachana) |
| `year(d)`, `quarter(d)`, `month(d)`, `week(d)`, `day(d)` | parts (`week` is the ISO week) | `quarter($.TRADE_DATE)` | `YEAR`, `MONTH`, `ISOWEEKNUM` / `YEAR`, `QTR` | R | built (Rachana) |
| `days_between(a, b)` | calendar days | `days_between($.TRADE_DATE, $.MATURITY_DATE)` | `DAYS` / `INTCK('day', ...)` | R | built (DEL, Rachana; old spelling `days`) |
| `business_days_between(a, b, [calendar])` | business days after a up to b (negative when b is before a) | `business_days_between($.TRADE_DATE, asof(), calendar = 'USNY')` | `NETWORKDAYS.INTL` / `INTCK('weekday', ...)` | R | built (Rachana) |
| `shift_date(d, n, [unit], [calendar])` | moved by business days (the default), days, weeks, months, quarters or years | `shift_date(asof(), -1)` | `WORKDAY.INTL`, `EDATE` / `INTNX` | R | built (Rachana) |
| `eomonth(d, [n])`, `edate(d, n)` | month end, months later | `eomonth($.TRADE_DATE)` | `EOMONTH`, `EDATE` / `INTNX('month', d, n, 'end')` | R | built (Rachana) |
| `yearfrac(a, b, basis)` | year fraction: `ACT/360`, `ACT/365F`, `30/360`, `30E/360`, `ACT/ACT` | `yearfrac($.START_DATE, $.MATURITY_DATE, 'ACT/360')` | `YEARFRAC` / `YRDIF` | R | built (Rachana) |
| `intck(interval, a, b, [calendar])` | how many interval boundaries are crossed | `intck('month', $.TRADE_DATE, asof())` | `DATEDIF` / `INTCK` | R | built (Rachana) |
| `is_business_day(d, [calendar])` | a business day? | `is_business_day($.MATURITY_DATE, 'USNY')` | `NETWORKDAYS(d, d)=1` | R | built (Rachana) |

## 4. Aggregates (many rows or list elements to one value)

Each runs in the engine. *Mergeable* means a cluster or a pivot total combines partial results exactly; *holistic* means it needs the
values (bounded by the engine's holistic limits, exact on request, or a sketch when the person accepts ≈).

**How the statistics are kept.** One implementation each in `drishti-bicompute` (`functions.Moments`, `functions.Comoments`,
`functions.Holistics`), registered into Rachana, so a pivot cell, a Rachana list (`skewness($.TRADES[*].PNL)`) and BI later run the
same code. `stderr`, `cv`, `skewness` and `kurtosis` keep the central moments to the fourth order (n, mean, M2, M3, M4), each batch's in two
passes and merged by Pébay's formulas; `corr`, `covar`, `slope`, `intercept` and `rsq` keep the co-moments (n, the two means, the two sums
of squares and the cross sum) merged by Chan's; `geomean` and `harmean` a compensated sum of ln x or 1/x; every one of them passes the
split-and-merge harness (2, 3 and 16 parts, five seeds, states serialised) within the configured 1e-12. `mode` and `string_agg` keep a
count per distinct value, whose merge is exact; `iqr`, `trimmean`, `kth_largest` and `kth_smallest` keep the values. On a table the SQL
engine computes the states where SQL can (`nmiss`, `range`, `stderr`, `cv`, `geomean`, `harmean`, the co-moments, `iqr`); the rest are fed
the cell's rows. `mode` of names and `string_agg` answer text.

| Function | Means | Example | Excel / SAS | Form | Status |
|---|---|---|---|---|---|
| `sum(x, [where])` | total | `sum($.LEGS[*].NOTIONAL)`, `sum(trades.NOTIONAL, where = trades.DESK == 'DESK-COMM')` | `SUM`, `SUMIFS` / PROC MEANS `SUM` | mergeable | built (all three) |
| `count(x \| alias, [where])` | values not null, or rows | `count($.EVENTS[?@.EVENT_TYPE == 'BOOKED'])` | `COUNT`, `COUNTIFS` / `N` | mergeable | built |
| `nmiss(x)` | nulls | `nmiss($.LEGS[*].DISCOUNT_INDEX)` | `COUNTBLANK` / `NMISS` | mergeable | built (engine) |
| `avg(x, [where])` | mean | `avg(trades.PRICE)` | `AVERAGE`, `AVERAGEIFS` / `MEAN` | mergeable (sum, count) | built |
| `wavg(x, w)` | weighted mean | `wavg(trades.PRICE, trades.QUANTITY)` | `SUMPRODUCT/SUM` / `MEAN` with `WEIGHT` | mergeable | built (engine) |
| `min(x)`, `max(x)` | extremes (numbers, dates, text) | `max($.EVENTS[*].EVENT_DATE)` | `MIN`, `MAX`, `MAXIFS` / `MIN`, `MAX` | mergeable | built |
| `range(x)` | max − min | `range(trades.PRICE)` | `MAX-MIN` / `RANGE` | mergeable | built (engine) |
| `distinct(x)` | how many different values (exact; ≈ by sketch when asked) | `distinct(trades.COUNTERPARTY)` | `COUNTA(UNIQUE())` / PROC SQL `COUNT(DISTINCT)` | holistic (sketch: HLL) | built |
| `median(x)`, `percentile(x, p)` | middle, p-quantile (continuous) | `percentile(trades.NOTIONAL, 0.95)` | `MEDIAN`, `PERCENTILE.INC` / `MEDIAN`, `P95` | holistic (sketch: KLL) | built |
| `mode(x)` | most frequent, a number or a name; ties to the smallest (SAS's rule; Excel takes the first met); none when no value repeats (Excel's #N/A) | `mode($.LEGS[*].CURRENCY)` | `MODE.SNGL` / `MODE` | holistic | built (engine) |
| `iqr(x)` | p75 − p25, linear interpolation (Excel's `QUARTILE.INC`; SAS's `QRANGE` uses its definition 5, so small samples can differ) | `iqr(trades.PRICE)` | `QUARTILE(3)-QUARTILE(1)` / `QRANGE` | holistic | built (engine) |
| `variance(x)`, `stddev(x)` (sample), `variance_pop(x)`, `stddev_pop(x)` (population) | spread | `stddev(trades.PNL)` | `VAR.S`, `STDEV.S` / `VAR`, `STD` | mergeable (moments) | built (engine) |
| `stderr(x)`, `cv(x)` | standard error s/√n, coefficient of variation s/mean (SAS's `CV` is 100 times it); none under two values | `cv(trades.PNL)` | `STDEV/SQRT(n)` / `STDERR`, `CV` | mergeable | built (engine) |
| `skewness(x)`, `kurtosis(x)` | shape: Excel's sample forms (kurtosis in excess of 3), SAS's with `VARDEF=DF`; none under three (four) values or without spread | `skewness(trades.PNL)` | `SKEW`, `KURT` / `SKEWNESS`, `KURTOSIS` | mergeable (moments to 4) | built (engine) |
| `geomean(x)`, `harmean(x)` | means for rates; positive values only (a value ≤ 0 leaves no figure, Excel's #NUM!) | `geomean(1 + trades.RETURN) - 1` | `GEOMEAN`, `HARMEAN` / `GEOMEAN`, `HARMEAN` | mergeable (log sum, reciprocal sum) | built (engine) |
| `trimmean(x, pct)` | mean without ⌊n·pct/2⌋ values at each end (Excel's rule) | `trimmean(trades.PRICE, 0.1)` | `TRIMMEAN` / PROC UNIVARIATE `TRIMMED=` | holistic | built (engine) |
| `corr(x, y)`, `covar(x, y)` | correlation, sample covariance, over the pairs where both are present | `corr(trades.PNL, trades.NOTIONAL)` | `CORREL`, `COVARIANCE.S` / PROC CORR | mergeable (sums of products) | built (engine) |
| `slope(y, x)`, `intercept(y, x)`, `rsq(y, x)` | least-squares line of y on x (y first, as Excel); none when x does not vary | `slope(trades.PNL, trades.QUANTITY)` | `SLOPE`, `INTERCEPT`, `RSQ` / PROC REG | mergeable | built (engine) |
| `ratio(num, den)` | num / den, null on 0 | `ratio(sum(trades.PNL), sum(trades.NOTIONAL))` | `IFERROR(a/b, "")` | of its parts | built (engine: a measure of measures, the one compiler) |
| `avg_by(agg, by = [fields])` | the aggregate per value of the fields, then their mean: iteration written out, never a context transition | `avg_by(sum(trades.PNL), by = [trades.TRADE_DATE])` | none (DAX `AVERAGEX`) | of its parts | built (engine: a context sub-plan, FilterContext) |
| `sum_once(x)`, `count_once(x)`, `avg_once(x)` | a coarser figure counted once per parent (a trade's in a per-leg view) | `sum_once($.QUANTITY)` beside `count($.LEGS[*])` | none | mergeable by parent id | built (engine) |
| `group(list, key, [agg])` | grouping inside one document | `group($.LEGS[*], @.CURRENCY, sum(@.NOTIONAL))` | `GROUPBY` / PROC MEANS `CLASS` | the engine's grouping | built (engine: Rachana reads the key and the inputs, the engine groups) |

## 5. Shares, ranks and windows (over the cells of an answer)

**On the pivot (built in the engine):** a value written around an aggregate, `pct_of_total(sum(NOTIONAL))`, `ntile(sum(NOTIONAL), 4)`,
`diff(sum(PNL), 1, TRADE_DATE:month)`. The aggregate is computed as every other value; the share, bucket or change is then computed from
the finished figures of the answer's cells, beside Top N and Others (`core.ShowAs`, "show values as"), so a total is never broken and no
row is read again. The header is the text as written; the raw figure stays beside the shown one. They are pivot values, not Rachana list
functions (a list has no rows and columns); Rachana reads a pivot's figure through `cell(...)`. `ntile`, `diff` and `pct_change` leave the
grand total and Others without a figure (an open member's total is that member, among its siblings); the order of `diff` and
`pct_change` is a dimension on the rows or the columns.

| Function | Means | Example | Excel / SAS | Status |
|---|---|---|---|---|
| `pct_of_total(agg)`, `pct_of_row(agg)`, `pct_of_column(agg)` | share of the grand, row or column total **visible** in the answer (Excel's "show values as") | `pct_of_total(sum(trades.NOTIONAL))` | PivotTable "% of grand total" / TABULATE `PCTSUM`, `ROWPCTSUM`, `COLPCTSUM` | built (engine) |
| `pct_of_parent(agg, [hierarchy])` | share of the parent level's **visible** total (the level above on the rows); not `parent_of`, which drops filters (Q6) | `pct_of_parent(sum(trades.NOTIONAL), 'MGMT')` | "% of parent row" / `ROWPCTSUM` | built (engine) |
| `share_of(agg, over = [fields] \| ALL)` | `agg / calc(agg, ignore = over)`: the share of everything you may see with those filters dropped (DAX's `ALL`), whatever the slicers say | `share_of(sum(trades.NOTIONAL), over = [trades.DESK])` | none | built (engine: a context sub-plan, FilterContext) |
| `parent_of(agg, hierarchy = 'name')` | `agg` over the same with the deepest level of the hierarchy in the answer ignored; null at the top | `parent_of(measure('MTM'), hierarchy = 'Desk and book')` | none | built (engine: a context sub-plan, FilterContext) |
| `rank(agg, desc = true, by = [f])`, `dense_rank(agg, desc = true, by = [f])`, `percent_rank(agg, desc = true, by = [f])` | position | `rank(sum(trades.NOTIONAL), by = [trades.DESK])` | `RANK.EQ`, `PERCENTRANK` / PROC RANK | built (engine: a window over the answer, the one compiler) |
| `ntile(agg, n)` | the bucket 1..n among the siblings, ascending, ties at their mean rank: SAS's ⌊rank·n/(count+1)⌋ plus one | `ntile(sum(trades.NOTIONAL), 10)` | `QUARTILE` buckets / PROC RANK `GROUPS=10` | built (engine) |
| `kth_largest(x, k)`, `kth_smallest(x, k)` | k-th value | `kth_largest(trades.NOTIONAL, 3)` | `LARGE`, `SMALL` / PROC UNIVARIATE extremes | built (engine, an aggregate: section 4) |
| `top_n(n, agg)`, `bottom_n(n, agg)` with Others | the n leading members, the rest as Others | `top_n(10, sum(trades.NOTIONAL))` | PivotTable Top 10 filter | built (the pivot view's `top`: Top N and Others) |
| `lag(agg, n, order, by = [f])`, `lead(agg, n, order, by = [f])` | n cells before or after | `lag(sum(trades.PNL), 1, trades.TRADE_DATE)` | `OFFSET` / `LAG` | built (engine: a window over the answer, the one compiler) |
| `diff(agg, [n], order)`, `pct_change(agg, [n], order)` | change from n members before along the order dimension (rows or columns, members in natural order); `pct_change` over the earlier figure, none where it is 0 | `pct_change(sum(trades.NOTIONAL), 1, month)` | `B2-B1` / `DIF` | built (engine) |
| `moving_avg(agg, n, order)`, `moving_sum(agg, n, order)`, `moving_min(agg, n, order)`, `moving_max(agg, n, order)` | rolling windows | `moving_avg(sum(trades.PNL), 20, trades.TRADE_DATE)` | `AVERAGE(OFFSET())` / PROC EXPAND | built (engine: a window over the answer, the one compiler) |
| `running(agg, date)` | running total (`cumulative` folds in) | `running(sum(trades.PNL), trades.TRADE_DATE)` | running total / `RETAIN` | built (engine: time intelligence, the one compiler) |
| `ytd(agg, date)`, `mtd(agg, date)`, `qtd(agg, date)`, `fiscal_ytd(agg, date)` | to date | `ytd(sum(trades.PNL), trades.TRADE_DATE)` | none / `INTNX` windows | built (engine: time intelligence, the one compiler) |
| `prior_period(agg, date)`, `same_period_last_year(agg, date)` | comparisons | `same_period_last_year(sum(trades.PNL), trades.TRADE_DATE)` | none | built (engine: time intelligence, the one compiler) |
| `opening(agg, date, period = 'month')`, `closing(agg, date, period = 'month')`, `earliest(agg, date)`, `latest(agg, date)`, `avg_over(agg, date)` | balances (`latest` and `earliest` were DEL's `last` and `first`, Q5; `first(list)` and `last(list)` stay Rachana's list ends) | `closing(sum(positions.BALANCE), positions.DATE, period = 'month')` | none / `FIRST.`, `LAST.` | built (engine: balances, the dates by context.Periods) |
| `calc(agg, where = c, ignore = [f] \| ALL, keep = [f], via = 'r')` | the aggregate in an edited filter context (BI_DESIGN 3.3.5, carried over word for word) | `calc(sum(trades.NOTIONAL), ignore = [trades.DESK])` | none (DAX `CALCULATE`) | built (engine: a context sub-plan, FilterContext) |
| `as_of(agg, date)` | the aggregate on another business date, worked out before the query runs | `as_of(sum(trades.NOTIONAL), shift_date(asof(), -1))` | none | built (engine: a context sub-plan, FilterContext) |

## 6. Lists (Excel's dynamic arrays, over `[*]`)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `size(list)` | how many elements | `size($.LEGS)` | `ROWS`, `COUNTA` / `DIM` | R | built (Rachana) |
| `first(list)`, `last(list)` | ends | `last($.EVENTS)` | `TAKE(x, 1)`, `TAKE(x, -1)` | R | built (Rachana) |
| `flatten(list)` | a list of lists as one | `flatten($.LEGS[*].CASH_FLOWS)` | `TOCOL` | R | built (Rachana) |
| `sort(list, [key], [desc])`, `unique(list)` | order, distinct | `unique($.LEGS[*].CURRENCY)` | `SORT`, `SORTBY`, `UNIQUE` | R | built (Rachana) |
| `take(list, n)`, `drop(list, n)` | the first or last n | `take(sort($.EVENTS, @.EVENT_DATE, true), 3)` | `TAKE`, `DROP` | R | built (Rachana) |
| `sequence(n, [start], [step])` | a list of numbers | `sequence(12)` | `SEQUENCE` / `DO` loop | R | built (Rachana) |
| `map(list, expr)` | each element transformed (`@` the element) | `map($.LEGS, @.NOTIONAL * $.QUANTITY)` | `MAP`, `LAMBDA` | R | built (Rachana) |
| `reduce(list, init, expr)` | folded (`@acc`, `@`) | `reduce($.EVENTS, '', concat(@acc, @.EVENT_TYPE))` | `REDUCE`, `LAMBDA` / `RETAIN` | R | built (Rachana; a sum with `+` or `-` on `@acc` is refused: use `sum`) |
| `link(id, kind, [label])` | a link to an entity | `link($.LEGS[0].DISCOUNT_INDEX, 'ir-curve')` | `HYPERLINK` | R | built (Rachana) |

## 7. Conversion, lookup and measures

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `fx(ccy, to, at = d)` | an FX rate from the installation's FX source (Q15); its Java and SQL forms come with the table layer (part B) | `fx('EUR', 'USD')` | none | R | T1 (Rachana parses and checks it; DEL until part B) |
| `convert(agg, to = 'USD', at = 'as-of')` | an aggregate of many currencies in one (the currency guard lifted) | `convert(sum(trades.NOTIONAL), to = 'USD')` | none | E | built (engine: the one compiler's plan; the rates with the table layer) |
| `related(alias.COLUMN)`, `related(table.COLUMN, on = key)` | a column of the row a reference points at (one many-to-one hop, Q29) | `related(counterparties.RATING)` | `XLOOKUP` / a `MERGE` | E | T1 (Rachana parses and checks it; resolved by the table layer's joins in part B; DEL until then) |
| `measure(name, [scope], [options])` | a model's measure in a model; a compute engine's measure for one scope, exactly from its engine (never summed) | `measure('VAR', 'DESK:DESK-COMM')` | none | M | built (engine, answered by the server's pivot engine for the person; model measures by the one compiler) |
| `cell(view, field, member, ...)` | a cell of a saved pivot view, as the view computes it for the person | `cell('owner/desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')` | `GETPIVOTDATA` | E | built (engine, answered by the server's pivot engine) |

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
