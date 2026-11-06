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
# Functions

*Generated from the registries (Rachana's function table and the pivot engine's functions registered into it) by `FunctionCataloguePage`; do not edit by hand. 285 functions, each under its one name. Each function's example, its Excel and SAS equivalents and its rules are in [FUNCTION_CATALOGUE.md](../architecture/FUNCTION_CATALOGUE.md); the grammar is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#functions).*

**Runs:** **R** Rachana (a scalar, one value from values); **E** the pivot engine (`drishti-bicompute`), the same implementation a pivot cell runs. Where the engine is not on the class path an **E** function is refused with the reason, never computed another way. **Form:** *mergeable* (a total combines partial results exactly), *holistic* (it needs the values), *measure* (a figure from its own engine, never summed).

## Aggregates: the pivot engine's (many values to one; a pivot cell runs the same function)

| Function | Arguments | Form | Means | Runs | Source |
|---|---|---|---|---|---|
| `avedev` | list, [field] | holistic | the mean absolute deviation from the mean (Excel's AVEDEV) | E | the pivot engine (drishti-bicompute) |
| `avg` | list, [field] | mergeable | the mean, Σx / n from the list's own sum and count | E | the pivot engine (drishti-bicompute) |
| `avg_once` | list, field, parentField \| xs, parents | holistic | the mean over the parents, each once | E | the pivot engine (drishti-bicompute) |
| `cell` | view, [field, member] ... | measure | a cell of a saved pivot view, as the view computes it | E | the pivot engine (drishti-bicompute) |
| `corr` | list, xField, yField \| xs, ys | mergeable | Pearson's correlation over the pairs where both are numbers | E | the pivot engine (drishti-bicompute) |
| `count` | list, [field] | mergeable | the elements (or the field's values) that are not empty | E | the pivot engine (drishti-bicompute) |
| `count_once` | list, field, parentField \| xs, parents | holistic | the parents with a value, each once | E | the pivot engine (drishti-bicompute) |
| `covar` | list, xField, yField \| xs, ys | mergeable | the sample covariance (Excel's COVARIANCE.S) | E | the pivot engine (drishti-bicompute) |
| `covar_pop` | list, aField, bField \| as, bs | mergeable | the population covariance (Excel's COVARIANCE.P) | E | the pivot engine (drishti-bicompute) |
| `cv` | list, [field] | mergeable | the coefficient of variation, s / mean; empty under two numbers or a mean of 0 | E | the pivot engine (drishti-bicompute) |
| `devsq` | list, [field] | mergeable | Σ(x − mean)² (Excel's DEVSQ, SAS's CSS) | E | the pivot engine (drishti-bicompute) |
| `distinct` | list, [field] | holistic | how many different values (exact) | E | the pivot engine (drishti-bicompute) |
| `geomean` | list, [field] | mergeable | the geometric mean; empty when a number is 0 or less (Excel's #NUM!) | E | the pivot engine (drishti-bicompute) |
| `group` | list, key, [aggregate], [by] | the engine's grouping | one row {label, value, count} per key, the aggregate over each group (sum of a quoted field, or any aggregate over @) | E | the pivot engine (drishti-bicompute) |
| `harmean` | list, [field] | mergeable | the harmonic mean; empty when a number is 0 or less (Excel's #NUM!) | E | the pivot engine (drishti-bicompute) |
| `intercept` | list, yField, xField \| ys, xs | mergeable | the least-squares intercept of y on x (Excel's INTERCEPT) | E | the pivot engine (drishti-bicompute) |
| `iqr` | list, [field] | holistic | p75 − p25 (Excel's QUARTILE.INC) | E | the pivot engine (drishti-bicompute) |
| `kth_largest` | list, [field], k | holistic | the k-th largest number (Excel's LARGE); empty when fewer than k | E | the pivot engine (drishti-bicompute) |
| `kth_smallest` | list, [field], k | holistic | the k-th smallest number (Excel's SMALL); empty when fewer than k | E | the pivot engine (drishti-bicompute) |
| `kurtosis` | list, [field] | mergeable | the sample excess kurtosis (Excel's KURT); empty under four numbers or no spread | E | the pivot engine (drishti-bicompute) |
| `max` | list, [field] | mergeable | the largest number (several arguments: greatest) | E | the pivot engine (drishti-bicompute) |
| `measure` | name, [scope], [options] | measure | a compute engine's measure for one scope (VaR, ES …), exactly from its engine, never summed | E | the pivot engine (drishti-bicompute) |
| `median` | list, [field] | holistic | the exact middle value | E | the pivot engine (drishti-bicompute) |
| `min` | list, [field] | mergeable | the smallest number (several arguments: least) | E | the pivot engine (drishti-bicompute) |
| `mode` | list, [field] | holistic | the most frequent value, a number or a name; ties to the smallest; empty when no value repeats | E | the pivot engine (drishti-bicompute) |
| `mode_multi` | list, [field] | holistic | every most frequent value, ascending, as a list (Excel's MODE.MULT) | E | the pivot engine (drishti-bicompute) |
| `nmiss` | list, [field] | mergeable | how many values are missing (null or absent); 0 for an empty list | E | the pivot engine (drishti-bicompute) |
| `percent_rank_of` | list, [field], value, [digits] | holistic | the value's rank as a fraction 0..1, cut to 3 significant digits (Excel's PERCENTRANK.INC) | E | the pivot engine (drishti-bicompute) |
| `percentile` | list, [field], p | holistic | the exact p-quantile (linear interpolation), p in 0..1 | E | the pivot engine (drishti-bicompute) |
| `percentile_exc` | list, [field], p | holistic | the p-quantile at rank p·(n + 1) (Excel's PERCENTILE.EXC; QUARTILE.EXC at k/4); empty outside 1..n | E | the pivot engine (drishti-bicompute) |
| `product` | list, [field] | mergeable | Π x (Excel's PRODUCT); empty for no number | E | the pivot engine (drishti-bicompute) |
| `range` | list, [field] | mergeable | max − min; empty when there is no number | E | the pivot engine (drishti-bicompute) |
| `rank_avg` | list, [field], value, [ascending] | holistic | the value's rank among the list's, ties averaged, largest first unless ascending is 1 (Excel's RANK.AVG) | E | the pivot engine (drishti-bicompute) |
| `rsq` | list, yField, xField \| ys, xs | mergeable | r², the square of the correlation (Excel's RSQ) | E | the pivot engine (drishti-bicompute) |
| `skewness` | list, [field] | mergeable | the sample skewness (Excel's SKEW); empty under three numbers or no spread | E | the pivot engine (drishti-bicompute) |
| `skewness_pop` | list, [field] | mergeable | the population skewness (Excel's SKEW.P); empty without spread | E | the pivot engine (drishti-bicompute) |
| `slope` | list, yField, xField \| ys, xs | mergeable | the least-squares slope of y on x (Excel's SLOPE, y first) | E | the pivot engine (drishti-bicompute) |
| `stddev` | list, [field] | mergeable | the sample standard deviation | E | the pivot engine (drishti-bicompute) |
| `stddev_pop` | list, [field] | mergeable | the population standard deviation | E | the pivot engine (drishti-bicompute) |
| `stderr` | list, [field] | mergeable | the standard error of the mean, s / √n; empty under two numbers | E | the pivot engine (drishti-bicompute) |
| `steyx` | list, yField, xField \| ys, xs | mergeable | the standard error of y predicted from x (Excel's STEYX); empty under three pairs | E | the pivot engine (drishti-bicompute) |
| `string_agg` | list, [field], sep, [distinct] | holistic | the values joined by sep in text order (distinct true: each once); empty past 1,000 values | E | the pivot engine (drishti-bicompute) |
| `sum` | list, [field] | mergeable | the total (compensated); 0 when there is no number | E | the pivot engine (drishti-bicompute) |
| `sum_once` | list, field, parentField \| xs, parents | holistic | Σx counting each parent once | E | the pivot engine (drishti-bicompute) |
| `sumproduct` | list, aField, bField \| as, bs | mergeable | Σ a·b over the pairs both present (Excel's SUMPRODUCT) | E | the pivot engine (drishti-bicompute) |
| `sumsq` | list, [field] | mergeable | Σ x² (Excel's SUMSQ, SAS's USS) | E | the pivot engine (drishti-bicompute) |
| `trimmean` | list, [field], pct | holistic | the mean without ⌊n·pct/2⌋ values at each end (Excel's TRIMMEAN) | E | the pivot engine (drishti-bicompute) |
| `variance` | list, [field] | mergeable | the sample variance (Welford) | E | the pivot engine (drishti-bicompute) |
| `variance_pop` | list, [field] | mergeable | the population variance | E | the pivot engine (drishti-bicompute) |
| `wavg` | list, xField, wField \| xs, ws | mergeable | Σwx / Σw, the weighted mean | E | the pivot engine (drishti-bicompute) |

## Measures: the pivot engine's functions of a measure (filter context, time, windows, balances, currency)

| Function | Arguments | Form | Means | Runs | Source |
|---|---|---|---|---|---|
| `as_of` | agg, date_expr | measure | The aggregate with every scan bound to another business date (the same fields, filters and security, that day's data), joined on the answer's fields: today against yesterday in one visual. The date is worked out before the query runs: date('...'), asof(), or shift_date(asof(), n, unit=). | E | the pivot engine (drishti-bicompute) |
| `avg_by` | agg, by = … | measure | A two-level aggregate: agg per value of by (within each group of the answer), then the average of those values (the explicit iteration DEL has instead of DAX context transition). | E | the pivot engine (drishti-bicompute) |
| `avg_over` | agg, date | measure | The average over the dates of each group of the aggregate per date (an average daily balance): avg_by(agg, by=[date]). | E | the pivot engine (drishti-bicompute) |
| `calc` | agg, where = …, ignore = …, keep = …, via = … | measure | The aggregate in an edited filter context: add conditions (where=, a row condition ANDed to the aggregate's), drop the query's and the group's filters on some fields (ignore=[...]) or on all (ignore=ALL), drop every filter except those on some fields (keep=[...]), or follow an inactive relationship (via='r_5', the active one for the same pair switched off). Security is never in scope: ignore=ALL means everything you may see. | E | the pivot engine (drishti-bicompute) |
| `closing` | agg, date, period = … | measure | The aggregate over the rows dated on the last business day of each period (on the model's calendar: a month ending on a holiday or a weekend closes on the business day before), up to the as-of date (the as-of period closes on the as-of date); per group, so a monthly answer shows each month's close. | E | the pivot engine (drishti-bicompute) |
| `convert` | agg, to = …, at = … | measure | An aggregate of amounts in many currencies, converted to one: each amount inside is multiplied by the rate of its own currency (its column's unit: { currency: ... }) at the as-of date (at='as-of'), at each row's trade date (at='trade-date'; the business date when the row has none) or at another date column of the row (at='settleDate'). | E | the pivot engine (drishti-bicompute) |
| `dense_rank` | agg, desc = …, by = … | measure | Like rank, without gaps after ties. | E | the pivot engine (drishti-bicompute) |
| `earliest` | agg, date | measure | The aggregate over the rows of each group dated on the group's earliest date. | E | the pivot engine (drishti-bicompute) |
| `fiscal_ytd` | agg, date | measure | The aggregate from the start of the fiscal year that holds the as-of date to the as-of date; the fiscal year starts on the first of the model's calendars.fiscal-year-start month (January when the model names none). | E | the pivot engine (drishti-bicompute) |
| `fx` |  |  |  | E | none on the class path |
| `lag` | agg, n, order, by = … | measure | The aggregate n rows before, along order (a field of the answer), restarting for each value of by (default: every other field of the answer). | E | the pivot engine (drishti-bicompute) |
| `latest` | agg, date | measure | The aggregate over the rows of each group dated on the group's latest date (a balance's semi-additive total: the latest day's figure, never the sum of the days). | E | the pivot engine (drishti-bicompute) |
| `lead` | agg, n, order, by = … | measure | The aggregate n rows after, along order. | E | the pivot engine (drishti-bicompute) |
| `moving_avg` | agg, n, order, by = … | measure | The average of the aggregate over the row and the n - 1 rows before it along order. | E | the pivot engine (drishti-bicompute) |
| `moving_max` | agg, n, order, by = … | measure | The largest value of the aggregate over the row and the n - 1 rows before it. | E | the pivot engine (drishti-bicompute) |
| `moving_min` | agg, n, order, by = … | measure | The smallest value of the aggregate over the row and the n - 1 rows before it. | E | the pivot engine (drishti-bicompute) |
| `moving_sum` | agg, n, order, by = … | measure | The sum of the aggregate over the row and the n - 1 rows before it. | E | the pivot engine (drishti-bicompute) |
| `mtd` | agg, date | measure | The aggregate over the rows dated from the start of the as-of month to the as-of date. | E | the pivot engine (drishti-bicompute) |
| `opening` | agg, date, period = … | measure | The balance at the close of the business day before the period starts (period: day, week, month, quarter, year): along the answer's date field it is the closing of the row before; without one, the aggregate on the business day before the as-of date's period (read at that business date when date is the snapshot's businessDate). | E | the pivot engine (drishti-bicompute) |
| `parent_of` | agg, hierarchy = … | measure | The aggregate as a share of its parent level in a hierarchy of the model: the deepest level of the hierarchy in the answer is ignored (with the levels below it), so each row is divided by its parent's total. | E | the pivot engine (drishti-bicompute) |
| `percent_rank` | agg, desc = …, by = … | measure | The relative rank from 0 to 1. | E | the pivot engine (drishti-bicompute) |
| `prior_period` | agg, date | measure | The aggregate over the period just before the query's dates, of the same number of business days. | E | the pivot engine (drishti-bicompute) |
| `qtd` | agg, date | measure | The aggregate over the rows dated from the start of the as-of quarter to the as-of date. | E | the pivot engine (drishti-bicompute) |
| `rank` | agg, desc = …, by = … | measure | The position of each row of the answer by the aggregate, 1 for the highest (desc=False: 1 for the lowest); ties share a rank and leave gaps. | E | the pivot engine (drishti-bicompute) |
| `ratio` | num, den | measure | num / den as a decimal, None when den is 0. The same as num / den in DEL, kept for readability. | E | the pivot engine (drishti-bicompute) |
| `related` |  |  |  | E | none on the class path |
| `running` | agg, date | measure | A running total of a sum, count, min or max along the date field of the answer (the date must be a field of the visual). | E | the pivot engine (drishti-bicompute) |
| `same_period_last_year` | agg, date | measure | The aggregate over the query's dates moved back one year. | E | the pivot engine (drishti-bicompute) |
| `share_of` | agg, over = … | measure | The aggregate divided by itself with the fields of over ignored (the same as agg / calc(agg, ignore=over)): over=ALL is the share of the grand total you may see, over=[books.NAME] a book's share within the rest of its group. | E | the pivot engine (drishti-bicompute) |
| `ytd` | agg, date | measure | The aggregate over the rows whose date falls between the start of the as-of date's year and the as-of date (the end of the query's dates), on the pack's business-day calendar. | E | the pivot engine (drishti-bicompute) |

## Math (scalars)

| Function | Runs | Source |
|---|---|---|
| `abs` | R | Rachana |
| `acos` | R | Rachana |
| `asin` | R | Rachana |
| `atan` | R | Rachana |
| `atan2` | R | Rachana |
| `base` | R | Rachana |
| `ceiling` | R | Rachana |
| `clamp` | R | Rachana |
| `combin` | R | Rachana |
| `cos` | R | Rachana |
| `cosh` | R | Rachana |
| `decimal` | R | Rachana |
| `degrees` | R | Rachana |
| `even` | R | Rachana |
| `exp` | R | Rachana |
| `fact` | R | Rachana |
| `floor` | R | Rachana |
| `gcd` | R | Rachana |
| `greatest` | R | Rachana |
| `lcm` | R | Rachana |
| `least` | R | Rachana |
| `ln` | R | Rachana |
| `log` | R | Rachana |
| `log10` | R | Rachana |
| `mod` | R | Rachana |
| `mround` | R | Rachana |
| `odd` | R | Rachana |
| `permut` | R | Rachana |
| `pi` | R | Rachana |
| `power` | R | Rachana |
| `radians` | R | Rachana |
| `round` | R | Rachana |
| `round_down` | R | Rachana |
| `round_sig` | R | Rachana |
| `round_up` | R | Rachana |
| `sign` | R | Rachana |
| `sin` | R | Rachana |
| `sinh` | R | Rachana |
| `sqrt` | R | Rachana |
| `tan` | R | Rachana |
| `tanh` | R | Rachana |

## Logic (scalars)

| Function | Runs | Source |
|---|---|---|
| `choose` | R | Rachana |
| `coalesce` | R | Rachana |
| `if` | R | Rachana |
| `iferror` | R | Rachana |
| `ifs` | R | Rachana |
| `is_blank` | R | Rachana |
| `is_boolean` | R | Rachana |
| `is_date` | R | Rachana |
| `is_error` | R | Rachana |
| `is_number` | R | Rachana |
| `is_text` | R | Rachana |
| `let` | R | Rachana |
| `link` | R | Rachana |
| `nonblank` | R | Rachana |
| `one_of` | R | Rachana |
| `switch` | R | Rachana |
| `type_of` | R | Rachana |
| `within` | R | Rachana |

## Text (scalars)

| Function | Runs | Source |
|---|---|---|
| `char` | R | Rachana |
| `clean` | R | Rachana |
| `code` | R | Rachana |
| `concat` | R | Rachana |
| `contains` | R | Rachana |
| `ends_with` | R | Rachana |
| `exact` | R | Rachana |
| `find` | R | Rachana |
| `fmt` | R | Rachana |
| `left` | R | Rachana |
| `len` | R | Rachana |
| `lower` | R | Rachana |
| `matches` | R | Rachana |
| `matches_case` | R | Rachana |
| `mid` | R | Rachana |
| `pad` | R | Rachana |
| `proper` | R | Rachana |
| `regex_extract` | R | Rachana |
| `regex_replace` | R | Rachana |
| `replace` | R | Rachana |
| `rept` | R | Rachana |
| `reverse` | R | Rachana |
| `right` | R | Rachana |
| `search` | R | Rachana |
| `split` | R | Rachana |
| `starts_with` | R | Rachana |
| `text` | R | Rachana |
| `text_after` | R | Rachana |
| `text_before` | R | Rachana |
| `trim` | R | Rachana |
| `upper` | R | Rachana |
| `value` | R | Rachana |

## Dates, on the bank's calendars (scalars)

| Function | Runs | Source |
|---|---|---|
| `asof` | R | Rachana |
| `business_days_between` | R | Rachana |
| `date` | R | Rachana |
| `datedif` | R | Rachana |
| `day` | R | Rachana |
| `days360` | R | Rachana |
| `days_between` | R | Rachana |
| `edate` | R | Rachana |
| `eomonth` | R | Rachana |
| `hour` | R | Rachana |
| `intck` | R | Rachana |
| `is_business_day` | R | Rachana |
| `minute` | R | Rachana |
| `month` | R | Rachana |
| `quarter` | R | Rachana |
| `second` | R | Rachana |
| `shift_date` | R | Rachana |
| `timestamp` | R | Rachana |
| `week` | R | Rachana |
| `weekday` | R | Rachana |
| `year` | R | Rachana |
| `yearfrac` | R | Rachana |

## Lists (Excel's dynamic arrays and MATLAB's list functions, over [*])

| Function | Runs | Source |
|---|---|---|
| `bounds` | E | the pivot engine (drishti-bicompute) |
| `cummax` | E | the pivot engine (drishti-bicompute) |
| `cummin` | E | the pivot engine (drishti-bicompute) |
| `cumprod` | E | the pivot engine (drishti-bicompute) |
| `cumsum` | E | the pivot engine (drishti-bicompute) |
| `cumtrapz` | R | Rachana |
| `difference` | R | Rachana |
| `drop` | R | Rachana |
| `fillmissing` | R | Rachana |
| `first` | R | Rachana |
| `flatten` | R | Rachana |
| `interp1` | R | Rachana |
| `intersect` | R | Rachana |
| `is_member` | R | Rachana |
| `isoutlier` | E | the pivot engine (drishti-bicompute) |
| `last` | R | Rachana |
| `map` | R | Rachana |
| `maxk` | E | the pivot engine (drishti-bicompute) |
| `mink` | E | the pivot engine (drishti-bicompute) |
| `movmean` | E | the pivot engine (drishti-bicompute) |
| `movmedian` | E | the pivot engine (drishti-bicompute) |
| `movstd` | E | the pivot engine (drishti-bicompute) |
| `movsum` | E | the pivot engine (drishti-bicompute) |
| `polyfit` | E | the pivot engine (drishti-bicompute) |
| `polyval` | R | Rachana |
| `reduce` | R | Rachana |
| `rescale` | E | the pivot engine (drishti-bicompute) |
| `sequence` | R | Rachana |
| `size` | R | Rachana |
| `sort` | R | Rachana |
| `take` | R | Rachana |
| `trapz` | R | Rachana |
| `union` | R | Rachana |
| `unique` | R | Rachana |
| `zscore` | E | the pivot engine (drishti-bicompute) |

## Distributions and test statistics (scalars)

| Function | Runs | Source |
|---|---|---|
| `beta_dist` | R | Rachana |
| `beta_inv` | R | Rachana |
| `binom_dist` | R | Rachana |
| `binom_inv` | R | Rachana |
| `chisq_dist` | R | Rachana |
| `chisq_inv` | R | Rachana |
| `confidence_norm` | R | Rachana |
| `confidence_t` | R | Rachana |
| `erf` | R | Rachana |
| `erfc` | R | Rachana |
| `expon_dist` | R | Rachana |
| `f_dist` | R | Rachana |
| `f_inv` | R | Rachana |
| `fisher` | R | Rachana |
| `fisher_inv` | R | Rachana |
| `gamma` | R | Rachana |
| `gamma_dist` | R | Rachana |
| `gamma_inv` | R | Rachana |
| `gamma_ln` | R | Rachana |
| `hypgeom_dist` | R | Rachana |
| `lognorm_dist` | R | Rachana |
| `lognorm_inv` | R | Rachana |
| `negbinom_dist` | R | Rachana |
| `norm_dist` | R | Rachana |
| `norm_inv` | R | Rachana |
| `norm_s_dist` | R | Rachana |
| `norm_s_inv` | R | Rachana |
| `poisson_dist` | R | Rachana |
| `standardize` | R | Rachana |
| `t_dist` | R | Rachana |
| `t_inv` | R | Rachana |
| `weibull_dist` | R | Rachana |

## Finance on given flows (scalars)

| Function | Runs | Source |
|---|---|---|
| `accrued` | R | Rachana |
| `convexity` | R | Rachana |
| `cumipmt` | R | Rachana |
| `cumprinc` | R | Rachana |
| `dollarde` | R | Rachana |
| `dollarfr` | R | Rachana |
| `effect` | R | Rachana |
| `fv` | R | Rachana |
| `fvschedule` | R | Rachana |
| `ipmt` | R | Rachana |
| `irr` | R | Rachana |
| `macaulay_duration` | R | Rachana |
| `mirr` | R | Rachana |
| `modified_duration` | R | Rachana |
| `nominal` | R | Rachana |
| `nper` | R | Rachana |
| `npv` | R | Rachana |
| `pduration` | R | Rachana |
| `pmt` | R | Rachana |
| `ppmt` | R | Rachana |
| `pv` | R | Rachana |
| `rate` | R | Rachana |
| `rri` | R | Rachana |
| `xirr` | R | Rachana |
| `xnpv` | R | Rachana |

## Shares, ranks and windows: the pivot engine's, over an answer's cells

A pivot value written around an aggregate: the aggregate is computed as any other, then shown from the finished figures of the answer's cells (a total is never broken; no rows are read again). Pivot values only: a list in Rachana has no rows and columns.

| Function | Arguments | Means | Example | Excel | SAS | Runs | Source |
|---|---|---|---|---|---|---|---|
| `pct_of_total` | agg | the cell's share of the grand total; empty where the total is 0 or empty | `pct_of_total(sum(NOTIONAL))` | PivotTable: % of Grand Total | PROC TABULATE PCTSUM | E | the pivot engine (drishti-bicompute) |
| `pct_of_row` | agg | the cell's share of its row's total; empty where that total is 0 or empty | `pct_of_row(sum(NOTIONAL))` | PivotTable: % of Row Total | PROC TABULATE ROWPCTSUM | E | the pivot engine (drishti-bicompute) |
| `pct_of_column` | agg | the cell's share of its column's total; empty where that total is 0 or empty | `pct_of_column(sum(NOTIONAL))` | PivotTable: % of Column Total | PROC TABULATE COLPCTSUM | E | the pivot engine (drishti-bicompute) |
| `pct_of_parent` | agg | the cell's share of its parent on the rows (the level above); the grand total's is 1 | `pct_of_parent(sum(NOTIONAL))` | PivotTable: % of Parent Row Total | PROC TABULATE PCTSUM<parent> | E | the pivot engine (drishti-bicompute) |
| `ntile` | agg, n | the bucket 1..n of the cell among its siblings (the rows under the same parent, in the same column), ascending, ties at their mean rank: SAS's GROUPS=n plus one; empty for the grand total, Others and an empty figure | `ntile(sum(NOTIONAL), 4)` | none (QUARTILE as bounds) | PROC RANK GROUPS=n TIES=MEAN (0-based) | E | the pivot engine (drishti-bicompute) |
| `diff` | agg, [n], order | the cell's figure less the figure n members before along the order dimension (rows or columns, members in their natural order); empty for the first n and where either figure is empty | `diff(sum(PNL), 1, TRADE_DATE:month)` | =B3-B2 | DIF<n> | E | the pivot engine (drishti-bicompute) |
| `pct_change` | agg, [n], order | (figure − figure n members before) / figure n members before along the order dimension; empty for the first n, where either figure is empty and where the earlier is 0 | `pct_change(sum(NOTIONAL), 1, TRADE_DATE:month)` | =B3/B2-1 | DIF<n>(x)/LAG<n>(x) | E | the pivot engine (drishti-bicompute) |

## Old spellings (read for existing Sutras, never offered)

| Old | Write instead |
|---|---|
| `days` | `days_between` |
| `matchesCase` | `matches_case` |
| `oneOf` | `one_of` |
| `startsWith` | `starts_with` |
| `today` | `asof` |
