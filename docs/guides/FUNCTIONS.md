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

*Generated from the registries (Rachana's function table and the pivot engine's functions registered into it) by `FunctionCataloguePage`; do not edit by hand. 80 functions, each under its one name. Each function's example, its Excel and SAS equivalents and its rules are in [FUNCTION_CATALOGUE.md](../architecture/FUNCTION_CATALOGUE.md); the grammar is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#functions).*

**Runs:** **R** Rachana (a scalar, one value from values); **E** the pivot engine (`drishti-bicompute`), the same implementation a pivot cell runs. Where the engine is not on the class path an **E** function is refused with the reason, never computed another way. **Form:** *mergeable* (a total combines partial results exactly), *holistic* (it needs the values), *measure* (a figure from its own engine, never summed).

## Aggregates: the pivot engine's (many values to one; a pivot cell runs the same function)

| Function | Arguments | Form | Means | Runs | Source |
|---|---|---|---|---|---|
| `avg` | list, [field] | mergeable | the mean, Σx / n from the list's own sum and count | E | the pivot engine (drishti-bicompute) |
| `avg_once` | list, field, parentField \| xs, parents | holistic | the mean over the parents, each once | E | the pivot engine (drishti-bicompute) |
| `cell` | view, [field, member] ... | measure | a cell of a saved pivot view, as the view computes it | E | the pivot engine (drishti-bicompute) |
| `count` | list, [field] | mergeable | the elements (or the field's values) that are not empty | E | the pivot engine (drishti-bicompute) |
| `count_once` | list, field, parentField \| xs, parents | holistic | the parents with a value, each once | E | the pivot engine (drishti-bicompute) |
| `distinct` | list, [field] | holistic | how many different values (exact) | E | the pivot engine (drishti-bicompute) |
| `group` | list, key, [aggregate], [by] | the engine's grouping | one row {label, value, count} per key, the aggregate over each group (sum of a quoted field, or any aggregate over @) | E | the pivot engine (drishti-bicompute) |
| `max` | list, [field] | mergeable | the largest number (several arguments: greatest) | E | the pivot engine (drishti-bicompute) |
| `measure` | name, [scope], [options] | measure | a compute engine's measure for one scope (VaR, ES …), exactly from its engine, never summed | E | the pivot engine (drishti-bicompute) |
| `median` | list, [field] | holistic | the exact middle value | E | the pivot engine (drishti-bicompute) |
| `min` | list, [field] | mergeable | the smallest number (several arguments: least) | E | the pivot engine (drishti-bicompute) |
| `percentile` | list, [field], p | holistic | the exact p-quantile (linear interpolation), p in 0..1 | E | the pivot engine (drishti-bicompute) |
| `stddev` | list, [field] | mergeable | the sample standard deviation | E | the pivot engine (drishti-bicompute) |
| `stddev_pop` | list, [field] | mergeable | the population standard deviation | E | the pivot engine (drishti-bicompute) |
| `sum` | list, [field] | mergeable | the total (compensated); 0 when there is no number | E | the pivot engine (drishti-bicompute) |
| `sum_once` | list, field, parentField \| xs, parents | holistic | Σx counting each parent once | E | the pivot engine (drishti-bicompute) |
| `variance` | list, [field] | mergeable | the sample variance (Welford) | E | the pivot engine (drishti-bicompute) |
| `variance_pop` | list, [field] | mergeable | the population variance | E | the pivot engine (drishti-bicompute) |
| `wavg` | list, xField, wField \| xs, ws | mergeable | Σwx / Σw, the weighted mean | E | the pivot engine (drishti-bicompute) |

## Math (scalars)

| Function | Runs | Source |
|---|---|---|
| `abs` | R | Rachana |
| `ceiling` | R | Rachana |
| `clamp` | R | Rachana |
| `exp` | R | Rachana |
| `floor` | R | Rachana |
| `greatest` | R | Rachana |
| `least` | R | Rachana |
| `ln` | R | Rachana |
| `log10` | R | Rachana |
| `mod` | R | Rachana |
| `mround` | R | Rachana |
| `power` | R | Rachana |
| `round_down` | R | Rachana |
| `round_up` | R | Rachana |
| `sign` | R | Rachana |
| `sqrt` | R | Rachana |

## Logic (scalars)

| Function | Runs | Source |
|---|---|---|
| `coalesce` | R | Rachana |
| `if` | R | Rachana |
| `iferror` | R | Rachana |
| `ifs` | R | Rachana |
| `let` | R | Rachana |
| `link` | R | Rachana |
| `nonblank` | R | Rachana |
| `one_of` | R | Rachana |
| `switch` | R | Rachana |
| `within` | R | Rachana |

## Text (scalars)

| Function | Runs | Source |
|---|---|---|
| `concat` | R | Rachana |
| `contains` | R | Rachana |
| `ends_with` | R | Rachana |
| `fmt` | R | Rachana |
| `left` | R | Rachana |
| `lower` | R | Rachana |
| `matches` | R | Rachana |
| `matches_case` | R | Rachana |
| `mid` | R | Rachana |
| `pad` | R | Rachana |
| `regex_extract` | R | Rachana |
| `replace` | R | Rachana |
| `right` | R | Rachana |
| `split` | R | Rachana |
| `starts_with` | R | Rachana |
| `trim` | R | Rachana |
| `upper` | R | Rachana |

## Dates, on the bank's calendars (scalars)

| Function | Runs | Source |
|---|---|---|
| `days_between` | R | Rachana |
| `edate` | R | Rachana |
| `eomonth` | R | Rachana |
| `intck` | R | Rachana |
| `is_business_day` | R | Rachana |
| `today` | R | Rachana |
| `yearfrac` | R | Rachana |

## Lists (Excel's dynamic arrays, over [*])

| Function | Runs | Source |
|---|---|---|
| `drop` | R | Rachana |
| `first` | R | Rachana |
| `flatten` | R | Rachana |
| `last` | R | Rachana |
| `map` | R | Rachana |
| `reduce` | R | Rachana |
| `sequence` | R | Rachana |
| `size` | R | Rachana |
| `sort` | R | Rachana |
| `take` | R | Rachana |
| `unique` | R | Rachana |

## Old spellings (read for existing Sutras, never offered)

| Old | Write instead |
|---|---|
| `days` | `days_between` |
| `matchesCase` | `matches_case` |
| `oneOf` | `one_of` |
| `startsWith` | `starts_with` |
