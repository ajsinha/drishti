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
runs and its status. The design is [ONE_GRAMMAR.md](ARCHITECTURE.md#23-one-grammar-one-engine) (section 7); when the functions are built this becomes the user's
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

- **Null rule** (product owner, 2026-11-03): a missing value is not equal to anything that is present, so `null != 'X'` is **true** and
  `null == 'X'` is false; a function of a missing value is missing (`left($.STRATEGY, 2) != 'X'` is true when there is no strategy).
  Tests answer true or false, never empty: `is_blank` is the one true for an empty value; the text tests (`contains`, `starts_with` …)
  are false for one.
- **Real-number functions.** Logarithms, trigonometry, the distributions and discounting (`npv`, `irr`, `pmt` …) are powers and
  exponentials: computed in binary floating point, as Excel computes them, and held by the tests to Excel's documented figures. Rounding,
  whole-number work (`fact`, `gcd`, `base`), `accrued`, `dollarde` and `dollarfr` stay exact in decimal.
- **Lists.** An element that is not a number is *missing*. Finance never skips a missing flow (that would shift every later period): its
  figure is empty. MATLAB's running and moving forms leave the missing place empty and carry on past it (MATLAB's `'omitmissing'`); a
  statistic leaves it out. A masked element masks the result.
- **One rule for text in numbers.** Aggregates read numbers and leave text out (Excel's `AVERAGE`, `MIN`, `MAX`); Excel's `AVERAGEA`,
  `MINA`, `MAXA` (text as 0, TRUE as 1) are not added: one rule, and a text that is counted as 0 is a wrong figure waiting to happen.
  `count` counts the values present of any type (Excel's `COUNTA`; on a number field it is `COUNT`).
- **Refusals.** A value of the wrong type or outside a function's domain (a standard deviation of 0, a probability of 1.2, a rate with
  no root) is `DRS-2102` in words naming the function and the argument, which `iferror(...)` turns into a fallback; never a made-up
  figure. Non-deterministic functions (`RAND`, `RANDBETWEEN`, `NOW`) are not added: a figure must be the same each time it is asked for
  (`today()` is the view's business date).

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
| `round(x, [n])` | round half away from zero to n places, in decimal (`round(2.675, 2)` is 2.68; n may be negative) | `round(trades.NOTIONAL / 1e6, n = 1)` | `ROUND` / `ROUND` | R | built (Rachana; DEL until part B) |
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
| `round_sig(x, digits)` | to significant digits | `round_sig($.NOTIONAL, 3)` | `ROUND(x, d-1-INT(LOG10(ABS(x))))` / `ROUND` with a computed unit | R | built (Rachana) |
| `log(x, [base])` | logarithm, base 10 by default | `log(8, 2)` is 3 | `LOG` / `LOG`, `LOG2` | R | built (Rachana) |
| `fact(n)`, `combin(n, k)`, `permut(n, k)` | factorial, combinations, permutations, exact (n ≤ 1,000) | `combin(8, 2)` is 28 | `FACT`, `COMBIN`, `PERMUT` / `FACT`, `COMB`, `PERM` | R | built (Rachana) |
| `gcd(a, ...)`, `lcm(a, ...)` | greatest common divisor, least common multiple | `lcm(3, 6, 12)` | `GCD`, `LCM` / `GCD`, `LCM` | R | built (Rachana) |
| `even(x)`, `odd(x)` | up, away from zero, to an even or odd whole number | `even(size($.LEGS) + 1)` | `EVEN`, `ODD` | R | built (Rachana) |
| `pi()`, `sin(x)`, `cos(x)`, `tan(x)`, `asin(x)`, `acos(x)`, `atan(x)`, `atan2(x, y)`, `sinh(x)`, `cosh(x)`, `tanh(x)`, `radians(x)`, `degrees(x)` | trigonometry; `atan2` in Excel's order, x first | `degrees(atan2(1, 1))` is 45 | the same names / `SIN` … `ATAN2` (SAS's order y, x) | R | built (Rachana) |
| `base(n, radix, [length])`, `decimal(text, radix)` | a whole number in radix 2 to 36 and back | `base(255, 16)` is FF | `BASE`, `DECIMAL`, `DEC2HEX`, `HEX2DEC`, `DEC2BIN` … / `PUT(x, HEX.)`, `INPUT(s, HEX.)` | R | built (Rachana) |
| `is_number(x)`, `is_text(x)`, `is_date(x)`, `is_boolean(x)`, `is_blank(x)` | what a value is; `is_blank` true for null, missing and blank text; number-like text is text | `is_blank($.STRATEGY)` | `ISNUMBER`, `ISTEXT`, `ISLOGICAL`, `ISBLANK` / `MISSING`, `VTYPE` | R | built (Rachana) |
| `is_error(x)` | x raises a problem (a wrong type, a bad date, an argument out of domain); a division by zero is empty, tested by `is_blank` | `is_error(date($.TEXT_DATE))` | `ISERROR`, `ISERR`, `ISNA` | R | built (Rachana) |
| `type_of(x)` | `number`, `text`, `boolean`, `list`, `object`, `link` or `empty` | `type_of($.LEGS)` | `TYPE` / `VTYPE` | R | built (Rachana) |
| `choose(i, a1, a2, ...)` | the i-th value (1 the first), only that one evaluated | `choose(quarter($.TRADE_DATE), 'Q1', 'Q2', 'Q3', 'Q4')` | `CHOOSE` / `CHOOSE` | R | built (Rachana) |

## 2. Text (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `upper(s)`, `lower(s)` | case; null in, null out | `upper($.COUNTERPARTY)` | `UPPER`, `LOWER` / `UPCASE`, `LOWCASE` | R | built (Rachana) |
| `len(s)` | characters (code points, as the engine's SQL counts them); `len(null)` is null (`size` counts a list) | `len($.TRADE_ID)` | `LEN` / `LENGTH` | R | built (Rachana; DEL until part B) |
| `contains(s, part)`, `starts_with(s, p)`, `ends_with(s, p)` | ignore case | `starts_with($.PRODUCT_TYPE, 'Com')` | `SEARCH`, `LEFT(...)=` / `FIND`, `INDEX` | R | built (Rachana; old spelling `startsWith`) |
| `has(list, value)` | a list attribute holds the value; equality as `==` | `has(trades.TAGS, 'fx')` | `COUNTIF` over the list, `ISNUMBER(MATCH(...))` | R | built (Rachana) |
| `any_of(list, values)` | a list attribute holds at least one of the values; none chosen is false | `any_of(trades.TAGS, ['fx', 'rates'])` | `SUMPRODUCT(--ISNUMBER(MATCH(...)))>0` | R | built (Rachana) |
| `all_of(list, values)` | a list attribute holds every one of the values | `all_of(trades.TAGS, ['fx', 'hedge'])` | `SUMPRODUCT(--ISNUMBER(MATCH(...)))=n` | R | built (Rachana) |
| `matches(s, regex)`, `matches_case(s, regex)` | regular expression | `matches($.INSTRUMENT, 'USD 20[0-9]{2}')` | `REGEXTEST` / `PRXMATCH` | R | built (Rachana; old spelling `matchesCase`) |
| `within(x, range)`, `one_of(x, list)` | in a range, in a list | `one_of($.DESK, ['DESK-COMM', 'DESK-RATES'])` | `OR(x=...)` / `IN` | R | built (Rachana; old spelling `oneOf`) |
| `concat(a, b, ...)` | joined | `concat($.DESK, ' / ', $.TT_BOOK)` | `CONCAT` / `CATS` | R | built (Rachana) |
| `left(s, n)`, `right(s, n)`, `mid(s, start, n)` | parts | `left($.TRADE_ID, 3)` | `LEFT`, `RIGHT`, `MID` / `SUBSTR` | R | built (Rachana) |
| `replace(s, old, new)` | substitution | `replace($.INSTRUMENT, ' ', '-')` | `SUBSTITUTE` / `TRANWRD` | R | built (Rachana) |
| `split(s, sep)` | a list of parts | `split($.TAGS, ',')` | `TEXTSPLIT` / `SCAN` | R | built (Rachana) |
| `trim(s)`, `pad(s, n, [char], [side])` | spaces, width | `pad($.LEG_ID, 3, '0')` | `TRIM`, `TEXT` / `STRIP`, `PUT` | R | built (Rachana) |
| `regex_extract(s, regex, [group])` | the matching part | `regex_extract($.INSTRUMENT, '20[0-9]{2}-[0-9]{2}')` | `REGEXEXTRACT` / `PRXCHANGE` | R | built (Rachana) |
| `fmt(x, format)` | a pack's display format | `fmt(trades.NOTIONAL, 'amount0')` | `TEXT` / `PUT` | R | built (Rachana) |
| `text(x, format)` | a number or a date written with an Excel format code (`#,##0.00`, `0.0%`, `0.00E+00`, `#,##0;(#,##0);"-"`, `dd-mmm-yyyy`, `hh:mm`); `fmt` writes a pack's named format | `text($.PRICE, '0.00%')` | `TEXT`, `FIXED`, `DOLLAR` / `PUT` | R | built (Rachana) |
| `value(text, [decimal_sep], [group_sep])` | text read as a number: groups, a currency sign, a trailing %, accounting brackets | `value('(1,250.50)')` is −1250.5 | `VALUE`, `NUMBERVALUE` / `INPUT(s, COMMA.)` | R | built (Rachana) |
| `proper(s)`, `reverse(s)`, `clean(s)` | each word capitalised; reversed; control characters removed | `proper($.COUNTERPARTY_NAME)` | `PROPER`, `CLEAN` / `PROPCASE`, `REVERSE`, `COMPRESS(s, , 'c')` | R | built (Rachana) |
| `rept(s, n)` | s n times (at most 10,000 characters) | `rept('*', 3)` | `REPT` / `REPEAT` | R | built (Rachana) |
| `exact(a, b)` | equal as written, case counts; two empty values are equal | `exact($.BOOK, 'BOOK-RATES-3')` | `EXACT` / `=` | R | built (Rachana) |
| `char(n)`, `code(s)` | a Unicode character and back | `char(8364)` is € | `UNICHAR`, `UNICODE` (`CHAR`, `CODE` on a code page) / `BYTE`, `RANK` | R | built (Rachana) |
| `find(part, s, [start])`, `search(part, s, [start])` | 1-based position, 0 when not there (SAS's rule, so `find(...) > 0` is a test); `find` case counts, `search` ignores it, no wildcards (`matches` for patterns) | `search('swap', $.INSTRUMENT)` | `FIND`, `SEARCH` / `INDEX`, `FIND` | R | built (Rachana) |
| `text_before(s, delim, [n])`, `text_after(s, delim, [n])` | the text before or after the n-th delimiter (negative: from the end); empty when not there | `text_after($.TRADE_ID, '-')` | `TEXTBEFORE`, `TEXTAFTER` / `SCAN` | R | built (Rachana) |
| `regex_replace(s, regex, replacement)` | every match replaced, `$1` … a group | `regex_replace($.INSTRUMENT, '[0-9]', '#')` | `REGEXREPLACE` / `PRXCHANGE` | R | built (Rachana, the linear-time engine) |
| `string_agg(x, sep, [distinct])` | the group's texts joined in text order (distinct: each once); at most 1,000 values, past that no figure, said in words | `string_agg($.LEGS[*].CURRENCY, ', ', true)` | `TEXTJOIN` / `CATX` over a group | E | built (engine) |

## 3. Dates, on the bank's calendars (scalars)

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `timestamp(text)`, `asof()` | a timestamp constant and the query's date (`today()` is read as `asof()`, Q7) | `asof()` | `DATEVALUE`, `TODAY` / `MDY`, `TODAY` | R | built (Rachana) |
| `year(d)`, `quarter(d)`, `month(d)`, `week(d)`, `day(d)` | parts (`week` is the ISO week) | `quarter($.TRADE_DATE)` | `YEAR`, `MONTH`, `DAY`, `ISOWEEKNUM` / `YEAR`, `QTR`, `MONTH`, `WEEK(d, 'V')`, `DAY` | R | built (Rachana; DEL until part B) |
| `days_between(a, b)` | calendar days | `days_between($.TRADE_DATE, $.MATURITY_DATE)` | `DAYS` / `INTCK('day', ...)` | R | built (DEL, Rachana; old spelling `days`) |
| `business_days_between(a, b, [calendar])` | business days after a up to b (negative when b is before a) | `business_days_between($.TRADE_DATE, asof(), calendar = 'USNY')` | `NETWORKDAYS.INTL` / `INTCK('weekday', ...)` | R | built (Rachana) |
| `shift_date(d, n, [unit], [calendar])` | moved by `business-day` (the default, on the bank's calendars; `business_day`, as `intck` writes it, is the same), `day`, `week`, `month`, `quarter` or `year`; a calendar moves only business days | `shift_date(asof(), -1)` | `WORKDAY.INTL`, `EDATE` / `INTNX(..., 'same')` | R | built (Rachana; DEL until part B) |
| `eomonth(d, [n])`, `edate(d, n)` | month end, months later | `eomonth($.TRADE_DATE)` | `EOMONTH`, `EDATE` / `INTNX('month', d, n, 'end')` | R | built (Rachana) |
| `yearfrac(a, b, basis)` | year fraction: `ACT/360`, `ACT/365F`, `30/360`, `30E/360`, `ACT/ACT` | `yearfrac($.START_DATE, $.MATURITY_DATE, 'ACT/360')` | `YEARFRAC` / `YRDIF` | R | built (Rachana) |
| `intck(interval, a, b, [calendar])` | how many interval boundaries are crossed | `intck('month', $.TRADE_DATE, asof())` | `DATEDIF` / `INTCK` | R | built (Rachana) |
| `is_business_day(d, [calendar])` | a business day? | `is_business_day($.MATURITY_DATE, 'USNY')` | `NETWORKDAYS(d, d)=1` | R | built (Rachana) |
| `date(text)`, `date(y, m, d)` | a date from ISO text, or from its parts with Excel's carry-over (month 14 is February next year) | `date(year($.TRADE_DATE), 12, 31)` | `DATE`, `DATEVALUE` / `MDY`, `INPUT(s, YYMMDD10.)` | R | built (Rachana) |
| `hour(t)`, `minute(t)`, `second(t)` | of a timestamp's text or a time (`09:30`); 0 for a plain date | `hour($.EXECUTION_TIME)` | `HOUR`, `MINUTE`, `SECOND` / `HOUR`, `MINUTE`, `SECOND` | R | built (Rachana) |
| `weekday(d, [type])` | day of the week with Excel's return types (1: Sunday 1; 2: Monday 1; 3: Monday 0; 11 to 17) | `weekday($.TRADE_DATE, 2) > 5` | `WEEKDAY` / `WEEKDAY` (type 1) | R | built (Rachana) |
| `days360(a, b, [european])` | days on a 360-day year, US (NASD) or European | `days360($.START_DATE, $.MATURITY_DATE)` | `DAYS360` / `DATDIF(a, b, '30/360')` | R | built (Rachana) |
| `datedif(a, b, unit)` | complete years, months, days: `Y`, `M`, `D`, `MD`, `YM`, `YD` (`intck` counts boundaries crossed instead) | `datedif($.TRADE_DATE, today(), 'M')` | `DATEDIF` / `INTCK(..., 'continuous')` | R | built (Rachana) |

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
| `sum_by(agg, by = [fields])` | the aggregate per value of the fields, then their mean: iteration written out, never a context transition | `sum_by(sum(trades.PNL), by = [trades.TRADE_DATE])` | none (DAX `SUMX`) | of its parts | built (engine: a context sub-plan, FilterContext) |
| `min_by(agg, by = [fields])` | the aggregate per value of the fields, then their mean: iteration written out, never a context transition | `min_by(sum(trades.PNL), by = [trades.TRADE_DATE])` | none (DAX `MINX`) | of its parts | built (engine: a context sub-plan, FilterContext) |
| `max_by(agg, by = [fields])` | the aggregate per value of the fields, then their mean: iteration written out, never a context transition | `max_by(sum(trades.PNL), by = [trades.TRADE_DATE])` | none (DAX `MAXX`) | of its parts | built (engine: a context sub-plan, FilterContext) |
| `sum_once(x)`, `count_once(x)`, `avg_once(x)` | a coarser figure counted once per parent (a trade's in a per-leg view) | `sum_once($.QUANTITY)` beside `count($.LEGS[*])` | none | mergeable by parent id | built (engine) |
| `group(list, key, [agg])` | grouping inside one document | `group($.LEGS[*], @.CURRENCY, sum(@.NOTIONAL))` | `GROUPBY` / PROC MEANS `CLASS` | the engine's grouping | built (engine: Rachana reads the key and the inputs, the engine groups) |
| `sumproduct(a, b)` | Σ a·b over the rows where both are present; 0 for none | `sumproduct(trades.QUANTITY, trades.PRICE)` | `SUMPRODUCT` / PROC SQL `SUM(a*b)` | mergeable (a compensated sum) | built (engine) |
| `sumsq(x)` | Σ x² | `sumsq(trades.PNL)` | `SUMSQ` / `USS` | mergeable | built (engine) |
| `product(x)` | Π x, kept as a mantissa and a binary exponent, so a long product never overflows on the way; empty past a number's range | `product(map($.RETURNS, 1 + @)) - 1` | `PRODUCT` / none (`EXP(SUM(LOG))`) | mergeable | built (engine) |
| `devsq(x)` | Σ(x − mean)² | `devsq(trades.PNL)` | `DEVSQ` / `CSS` | mergeable (moments) | built (engine) |
| `skewness_pop(x)` | the population skewness | `skewness_pop(trades.PNL)` | `SKEW.P` / `SKEWNESS` with `VARDEF=N` | mergeable (moments to 3) | built (engine) |
| `covar_pop(x, y)` | the population covariance | `covar_pop(trades.PNL, trades.NOTIONAL)` | `COVARIANCE.P` / PROC CORR `COV VARDEF=N` | mergeable (co-moments) | built (engine) |
| `steyx(y, x)` | the standard error of y predicted from x (y first, as Excel); none under three pairs | `steyx(trades.PNL, trades.QUANTITY)` | `STEYX` / PROC REG Root MSE | mergeable (co-moments) | built (engine) |
| `avedev(x)` | the mean absolute deviation from the mean | `avedev(trades.PRICE)` | `AVEDEV` / none | holistic | built (engine) |
| `percentile_exc(x, p)` | the p-quantile at rank p·(n + 1); none outside 1..n (Excel's #NUM!); `QUARTILE.EXC(x, k)` is `percentile_exc(x, k/4)` | `percentile_exc(trades.PNL, 0.25)` | `PERCENTILE.EXC`, `QUARTILE.EXC` / `PCTLDEF=4` | holistic | built (engine) |
| `rank_avg(x, value, [ascending])` | the value's rank among the values, ties at their average, largest first unless ascending is 1; none when absent | `rank_avg($.TRADES[*].PNL, $.PNL)` | `RANK.AVG` / PROC RANK `TIES=MEAN` | holistic | built (engine; `rank` ranks a pivot cell among its siblings) |
| `percent_rank_of(x, value, [digits])` | the value's rank as a fraction 0..1, interpolated between values, cut to 3 significant digits | `percent_rank_of($.TRADES[*].PNL, 1000)` | `PERCENTRANK.INC` / PROC RANK `PERCENT` | holistic | built (engine; `percent_rank` ranks a pivot cell) |
| `mode_multi(x)` | every most frequent value, ascending; a list in Rachana, the values joined in a cell; at most 10,000 different values | `mode_multi($.LEGS[*].CURRENCY)` | `MODE.MULT` / PROC UNIVARIATE `MODES` | holistic | built (engine) |

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
| `sort(list, key1, desc1, key2, desc2, ...)` | by several keys, the later ordering what the earlier leave equal | `sort($.TRADES, @.DESK, false, @.NOTIONAL, true)` | `SORTBY` / MATLAB `sortrows`, SAS PROC SORT `BY` | R | built (Rachana) |
| `union(a, b)`, `intersect(a, b)`, `difference(a, b)`, `is_member(x, list)` | sets, each value once, in `sort`'s order, compared as `==` (`1` and `1.0` one value); `is_member` of a list is a list of true/false; an empty list holds nothing (`one_of` is a control's choices, where none chosen holds all) | `difference($.DESKS, $.CLOSED_DESKS)` | `UNIQUE(VSTACK())`, `XMATCH` / MATLAB `union`, `intersect`, `setdiff`, `ismember` | R | built (Rachana) |
| `fillmissing(list, method, [value])` | missing elements filled: `previous`, `next`, `nearest`, `linear` (the ends extended in a line), `constant` | `fillmissing($.FIXINGS[*].RATE, 'previous')` | none / MATLAB `fillmissing`, SAS PROC EXPAND, `RETAIN` | R | built (Rachana) |
| `interp1(xs, ys, xq, [method])` | the curve through the points at xq (a number or a list): `linear`, `previous`, `next`, `nearest`; empty outside the points (no spline: not added until it is needed and tested) | `interp1($.TENORS, $.RATES, 2.5)` | `FORECAST.LINEAR` between two points / MATLAB `interp1`, PROC EXPAND | R | built (Rachana) |
| `polyval(p, x)` | the polynomial with coefficients p (highest power first) at x or each of a list's | `polyval(polyfit($.TENORS, $.RATES, 2), 7)` | `SERIESSUM` / MATLAB `polyval` | R | built (Rachana) |
| `trapz([xs], ys)`, `cumtrapz([xs], ys)` | area under the points by trapezoids (MATLAB's order, x first); empty over a gap | `trapz($.TIMES, $.EXPOSURES)` | none / MATLAB `trapz`, `cumtrapz` | R | built (Rachana) |
| `cumsum(list)`, `cumprod(list)`, `cummax(list)`, `cummin(list)` | running forms over a list (the engine's sum, product, max, min); a missing place stays empty | `cumsum($.CASH_FLOWS[*].AMOUNT)` | `SCAN` / MATLAB `cumsum` …, SAS `RETAIN` | E | built (engine) |
| `movsum(list, k)`, `movmean(list, k)`, `movstd(list, k)`, `movmedian(list, k)` (or `before, after`) | moving windows centred (an even k: k/2 before, k/2 − 1 after), shrinking at the ends, as MATLAB | `movmean($.PNL_HISTORY, 20)` | `AVERAGE(OFFSET())` / MATLAB `movmean` …, PROC EXPAND | E | built (engine) |
| `maxk(list, k)`, `mink(list, k)`, `bounds(list)` | the k largest (largest first) or smallest; [min, max] | `maxk($.TRADES[*].NOTIONAL, 3)` | `LARGE` over `SEQUENCE(k)` / MATLAB `maxk`, `mink`, `bounds` | E | built (engine) |
| `zscore(list)`, `rescale(list, [lo], [hi])` | (x − mean) / s (sample); mapped onto lo..hi (0..1) | `zscore($.TRADES[*].PNL)` | `STANDARDIZE` per value / MATLAB `zscore`, `rescale`, `normalize`; PROC STDIZE | E | built (engine) |
| `isoutlier(list, [threshold])` | true more than threshold (3) scaled MADs (1.4826 · median absolute deviation) from the median, MATLAB's default rule | `isoutlier($.TRADES[*].PNL)` | none / MATLAB `isoutlier`, PROC UNIVARIATE robust | E | built (engine) |
| `polyfit(xs, ys, n)` | least-squares polynomial coefficients, highest power first, degree ≤ 6, by Householder QR on centred x; refused in words when the points do not determine it (fewer distinct x than n + 1, a reciprocal condition under 1e-12) | `polyfit($.TENORS, $.RATES, 2)` | `LINEST(y, x^{1,2})` / MATLAB `polyfit`, PROC REG | E | built (engine; larger fits are the matrix tier) |

## 7. Conversion, lookup and measures

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `fx(ccy, to, at = d)` | an FX rate from the installation's FX source (Q15); its Java and SQL forms come with the table layer (part B) | `fx('EUR', 'USD')` | none | R | T1 (Rachana parses and checks it; DEL until part B) |
| `convert(agg, to = 'USD', at = 'as-of')` | an aggregate of many currencies in one (the currency guard lifted) | `convert(sum(trades.NOTIONAL), to = 'USD')` | none | E | built (engine: the one compiler's plan; the rates with the table layer) |
| `related(alias.COLUMN)`, `related(table.COLUMN, on = key)` | a column of the row a reference points at (one many-to-one hop, Q29) | `related(counterparties.RATING)` | `XLOOKUP` / a `MERGE` | E | T1 (Rachana parses and checks it; resolved by the table layer's joins in part B; DEL until then) |
| `related_rows('alias')` | a row rule: the rows sharing a key with the rows of another table of the model the reader sees (BI_DESIGN 8.5, the owner's decision of 2026-11-07) | `related_rows('trades')` | none | E | a row rule, written by BI's engine (`EXISTS` over the other table with its rules; a table read alone lets no row through) |
| `measure(name, [scope], [options])` | a model's measure in a model; a compute engine's measure for one scope, exactly from its engine (never summed) | `measure('VAR', 'DESK:DESK-COMM')` | none | M | built (engine, answered by the server's pivot engine for the person; model measures by the one compiler) |
| `cell(view, field, member, ...)` | a cell of a saved pivot view, as the view computes it for the person | `cell('owner/desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')` | `GETPIVOTDATA` | E | built (engine, answered by the server's pivot engine) |

## 8. Finance on given flows (tier 2)

Arithmetic on cash flows and rates that the data already carries; pricing a trade from market data is QLRisk's, and VaR, ES, exposure,
FRTB-SA and SIMM come only from their engines through `measure(...)`.

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
Excel's signs (money paid out negative) and argument order. Every function here is in `FinanceFunctions` (Rachana), tested on the
examples of Excel's own pages. **Root finding** (`irr`, `xirr`, `rate`, `RootFinder`): Newton's method from the guess (0.1 by default),
at most 100 steps, to an absolute 1e-12 on the rate; if Newton fails, a scan of 1 + r from 0.0001 to 10,001 on 4,000 logarithmic points
for a change of sign, the bracket nearest the guess narrowed by bisection to 1e-12; no change of sign anywhere is refused in words ("no
rate from −99.99 % to 1,000,000 % makes the value 0"; flows of one sign: "the flows never change sign"). With several roots, the one Newton
reaches from the guess is the answer, as Excel's.

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `npv(rate, flows)`, `xnpv(rate, flows, dates)` | present value; npv's first flow one period away, xnpv's years as days / 365 from the first date | `xnpv(0.045, $.LEGS[0].CASH_FLOWS[*].AMOUNT, $.LEGS[0].CASH_FLOWS[*].PAY_DATE)` | `NPV`, `XNPV` / `FINANCE('XNPV')` | R | built (Rachana) |
| `irr(flows, [guess])`, `xirr(flows, dates, [guess])` | internal rate of return | `xirr($.LEGS[0].CASH_FLOWS[*].AMOUNT, $.LEGS[0].CASH_FLOWS[*].PAY_DATE)` | `IRR`, `XIRR` / `FINANCE('IRR')` | R | built (Rachana) |
| `mirr(flows, finance_rate, reinvest_rate)` | modified internal rate of return | `mirr($.FLOWS, 0.1, 0.12)` | `MIRR` / `FINANCE('MIRR')` | R | built (Rachana) |
| `pv(rate, nper, pmt, [fv], [type])`, `fv(rate, nper, pmt, [pv], [type])`, `pmt(rate, nper, pv, [fv], [type])`, `nper(rate, pmt, pv, [fv], [type])`, `rate(nper, pmt, pv, [fv], [type], [guess])` | annuity arithmetic | `pmt(0.05 / 12, 360, 1e6)` | `PV`, `FV`, `PMT`, `NPER`, `RATE` / `MORT`, `FINANCE` | R | built (Rachana) |
| `ipmt(rate, per, nper, pv, [fv], [type])`, `ppmt(...)`, `cumipmt(rate, nper, pv, start, end, type)`, `cumprinc(...)` | a payment's interest and principal, and their sums over periods | `cumipmt(0.09 / 12, 360, 125000, 13, 24, 0)` | `IPMT`, `PPMT`, `CUMIPMT`, `CUMPRINC` / `FINANCE` | R | built (Rachana) |
| `effect(nominal, n)`, `nominal(effect, n)`, `rri(n, pv, fv)`, `pduration(rate, pv, fv)`, `fvschedule(principal, rates)` | rate conversions, growth | `effect(0.0525, 4)` | `EFFECT`, `NOMINAL`, `RRI`, `PDURATION`, `FVSCHEDULE` / `FINANCE` | R | built (Rachana) |
| `accrued(rate, from, to, [basis], [par])` | rate · yearfrac(from, to, basis) · par, in decimal; basis `30/360` by default | `accrued(0.04, date('2026-06-15'), date('2026-12-15'), '30/360')` | `ACCRINTM` (`ACCRINT` with a schedule is QLRisk's) / none | R | built (Rachana) |
| `dollarde(price, fraction)`, `dollarfr(price, fraction)` | a price in fractions (1.02 in 16ths) as a decimal (1.125) and back, exact | `dollarde($.PRICE_32NDS, 32)` | `DOLLARDE`, `DOLLARFR` | R | built (Rachana) |
| `macaulay_duration(flows, when, yield, [frequency], [settle])`, `modified_duration(...)`, `convexity(...)` | on given flows and a given yield; `when` years, or dates counted from `settle` (days / 365); frequency 1 by default, 0 continuous | `modified_duration($.BOND_FLOWS, $.BOND_TIMES, 0.09, 2)` | `DURATION`, `MDURATION` (a bond's own flows) / `FINANCE('DURATION')` | R | built (Rachana) |
| `discount_factor(curve, date)`, `forward_rate(curve, a, b)` | from an `ir-curve` kind, interpolation stated | `discount_factor('USD-SOFR', $.MATURITY_DATE)` | none | R | T2, not built: Rachana has no clean reader of a kind (its context is the document); it needs a curve reader through the engine's `FigureSource`, designed with QLRisk's curve API |

## 9. Distributions and test statistics (scalars)

Every function here is in `Distributions` (Rachana) on the numerics of `SpecialFunctions`, each a published algorithm, cited in the code:
Lanczos's gamma (g = 7, nine coefficients, relative error about 1e-15); the incomplete gamma and beta functions by series and continued
fractions (modified Lentz, *Numerical Recipes* sections 6.2 and 6.4) to a relative 1e-16; the normal from erfc = Q(½, x²), so a tail keeps
its relative digits; its inverse by Acklam's approximation refined by one Halley step; other inverses by bisection on the CDF to a relative
1e-15. No library: commons-math3 is not on Rachana's class path, and none is added. `cumulative` is optional and true by default (Excel
requires it); degrees of freedom and counts are cut to whole numbers, as Excel's. The VaR engine's backtests keep their own statistics;
these serve the tests a person builds.

| Function | Means | Example | Excel / SAS | Runs | Status |
|---|---|---|---|---|---|
| `norm_dist(x, mean, sd, [cumulative])`, `norm_inv(p, mean, sd)`, `norm_s_dist(z, [cumulative])`, `norm_s_inv(p)` | the normal | `norm_s_inv(0.99)` | `NORM.DIST`, `NORM.INV`, `NORM.S.DIST`, `NORM.S.INV` / `CDF('NORMAL')`, `PROBNORM`, `PROBIT` | R | built (Rachana) |
| `lognorm_dist(x, mean, sd, [cumulative])`, `lognorm_inv(p, mean, sd)` | the log-normal | `lognorm_dist(4, 3.5, 1.2)` | `LOGNORM.DIST`, `LOGNORM.INV` / `CDF('LOGNORMAL')` | R | built (Rachana) |
| `t_dist(x, df, [cumulative])`, `t_inv(p, df)` | Student's t, the left tail (`T.DIST.2T` is `2 * (1 - t_dist(abs(x), df))`, `T.DIST.RT` is `1 - t_dist(x, df)`, `T.INV.2T(p)` is `t_inv(1 - p/2, df)`) | `t_inv(0.975, 59)` | `T.DIST`, `T.INV` / `PROBT`, `TINV` | R | built (Rachana) |
| `chisq_dist(x, df, [cumulative])`, `chisq_inv(p, df)` | chi-square, the left tail | `1 - chisq_dist(3.84, 1)` | `CHISQ.DIST`, `CHISQ.INV` (`.RT` as 1 −) / `PROBCHI`, `CINV` | R | built (Rachana) |
| `f_dist(x, df1, df2, [cumulative])`, `f_inv(p, df1, df2)` | F, the left tail | `f_inv(0.95, 6, 4)` | `F.DIST`, `F.INV` / `PROBF`, `FINV` | R | built (Rachana) |
| `binom_dist(k, n, p, [cumulative])`, `binom_inv(n, p, alpha)` | binomial; the smallest k reaching alpha | `binom_dist(3, 250, 0.01)` (exceptions in a backtest year) | `BINOM.DIST`, `BINOM.INV` / `PROBBNML`, `QUANTILE('BINOM')` | R | built (Rachana) |
| `poisson_dist(k, mean, [cumulative])`, `expon_dist(x, lambda, [cumulative])` | Poisson, exponential | `poisson_dist(2, 5)` | `POISSON.DIST`, `EXPON.DIST` / `POISSON`, `CDF('EXPONENTIAL')` | R | built (Rachana) |
| `gamma_dist(x, alpha, beta, [cumulative])`, `gamma_inv(p, alpha, beta)`, `gamma(x)`, `gamma_ln(x)` | gamma (beta the scale) and Γ | `gamma_ln(4)` | `GAMMA.DIST`, `GAMMA.INV`, `GAMMA`, `GAMMALN` / `PROBGAM`, `GAMINV`, `GAMMA`, `LGAMMA` | R | built (Rachana) |
| `beta_dist(x, a, b, [cumulative], [A], [B])`, `beta_inv(p, a, b, [A], [B])` | beta on A..B | `beta_dist(0.4, 8, 10)` | `BETA.DIST`, `BETA.INV` / `PROBBETA`, `BETAINV` | R | built (Rachana) |
| `weibull_dist(x, alpha, beta, [cumulative])`, `hypgeom_dist(k, n, K, N, [cumulative])`, `negbinom_dist(f, s, p, [cumulative])` | Weibull, hypergeometric, negative binomial | `weibull_dist(105, 20, 100)` | `WEIBULL.DIST`, `HYPGEOM.DIST`, `NEGBINOM.DIST` / `CDF('WEIBULL')`, `PROBHYPR`, `PROBNEGB` | R | built (Rachana) |
| `erf(x)`, `erfc(x)` | the error function and its complement | `erf(1)` | `ERF`, `ERFC` / `ERF`, `ERFC` | R | built (Rachana) |
| `standardize(x, mean, sd)`, `confidence_norm(alpha, sd, n)`, `confidence_t(alpha, sd, n)` | a z-score; the half-width of a confidence interval for a mean | `avg($.TRADES[*].PNL) + confidence_t(0.05, stddev($.TRADES[*].PNL), count($.TRADES[*].PNL))` | `STANDARDIZE`, `CONFIDENCE.NORM`, `CONFIDENCE.T` / PROC MEANS `CLM` | R | built (Rachana) |
| `fisher(x)`, `fisher_inv(y)` | Fisher's transformation of a correlation and back | `fisher_inv(fisher(corr(trades.PNL, trades.NOTIONAL)) + 1.96 / sqrt(count(trades.PNL) - 3))` | `FISHER`, `FISHERINV` / PROC CORR `FISHER` | R | built (Rachana) |

## 10. Matrices, at native speed (Java 25)

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

## 11. Candidates (tier 3)

Frequency tables with chi-square (PROC FREQ, Excel's `FREQUENCY`, `CHISQ.TEST`), two-sample tests over two columns (`T.TEST`, `F.TEST`,
`Z.TEST`: the distributions they rest on are built, section 9), regression with several variables and its statistics (PROC REG,
`LINEST`, `LOGEST`, on `solve`), `forecast` and `trend` (Excel's linear forms; `slope` and `intercept` give them today), exponential
smoothing (`FORECAST.ETS`), `winsorize(x, pct)` (PROC UNIVARIATE), histogram bins as a dimension. Each decided one by one.

## 12. Excel, SAS and MATLAB/Octave coverage

*Product owner, 2026-11-03: "let us add functions, whatever makes sense": an analyst coming from Excel, SAS or MATLAB should rarely miss a
function.* Every family of Excel's function list, SAS's DATA-step functions and PROC MEANS/UNIVARIATE/FREQ statistics, and MATLAB's
(Octave's) everyday data functions, marked: **covered** (by which Drishti function, under its one name: the other names are documentation,
never aliases), **added** (built in 2.9.11.e, sections 1 to 9), or **not** (why). Counts are of Excel functions, SAS functions and
statistics, and MATLAB functions as the families list them.

**The reasons a function is not added.** *Non-deterministic*: a figure must be the same each time it is asked for. *Spreadsheet-cell*:
it reads a sheet's cells, addresses or workbook (Drishti has fields, paths and saved views instead). *Pricing*: instrument conventions
and models are QLRisk's (design only until its API is stable). *Risk*: VaR, ES, exposure, FRTB-SA, SIMM come from their engines
through `measure(...)`, never a `var()` over a vector. *MX*: needs matrices, the Java 25 tier (section 10). *Locale or legacy*: a
code-page, a national script or a function Excel keeps for compatibility with a newer one beside it. *No use*: nothing on Drishti's data
calls for it; added on a person's request.

### 12.1 Excel

| Family | Covered (by) | Added | Not (why) |
|---|---|---|---|
| Math and trigonometry | `SUM` `SUMIF(S)` (`sum` with `where`, path filters), `ABS`, `ROUNDUP`, `ROUNDDOWN`, `TRUNC` (`round_down`), `INT` (`floor`), `QUOTIENT` (`round_down(a / b)`), `MROUND`, `CEILING(.MATH)`, `FLOOR(.MATH)`, `MOD`, `POWER`, `SQRT`, `EXP`, `LN`, `LOG10`, `SIGN`, `SEQUENCE`, `SQRTPI` (`sqrt(x * pi())`), `SERIESSUM` (`polyval`), `SUBTOTAL`, `AGGREGATE` (the pivot's totals, the aggregates) | `ROUND`, round to significant digits, `LOG`, `FACT`, `COMBIN`, `PERMUT`, `GCD`, `LCM`, `EVEN`, `ODD`, `PI`, `SIN` … `ATAN2`, `SINH` … `TANH`, `RADIANS`, `DEGREES`, `BASE`, `DECIMAL`, `SUMPRODUCT`, `SUMSQ`, `PRODUCT` | `RAND`, `RANDBETWEEN`, `RANDARRAY` (non-deterministic); `MMULT`, `MINVERSE`, `MDETERM`, `MUNIT` (MX); `FACTDOUBLE`, `COMBINA`, `PERMUTATIONA`, `MULTINOMIAL`, `ROMAN`, `ARABIC`, `SEC`, `CSC`, `COT` and their inverses and hyperbolics, `SUMX2MY2`, `SUMX2PY2`, `SUMXMY2` (no use; each one line of arithmetic over the added ones) |
| Statistical | `AVERAGE(IF(S))` (`avg`), `COUNT`, `COUNTA` (`count`), `COUNTBLANK` (`nmiss`), `COUNTIF(S)` (`count` with `where`), `MAX(IFS)`, `MIN(IFS)`, `MEDIAN`, `MODE.SNGL`, `PERCENTILE.INC`, `QUARTILE.INC` (`percentile`), `STDEV.S/.P`, `VAR.S/.P`, `SKEW`, `KURT`, `GEOMEAN`, `HARMEAN`, `TRIMMEAN`, `LARGE`, `SMALL`, `CORREL`, `PEARSON` (`corr`), `COVARIANCE.S`, `SLOPE`, `INTERCEPT`, `RSQ`, `RANK.EQ` (`rank`), `PERCENTRANK` of a cell (`percent_rank`), `FORECAST.LINEAR` (`intercept` + `slope`·x) | `SUMPRODUCT`, `DEVSQ`, `AVEDEV`, `SKEW.P`, `COVARIANCE.P`, `STEYX`, `PERCENTILE.EXC`, `QUARTILE.EXC` (`percentile_exc`), `RANK.AVG`, `PERCENTRANK.INC` of a value, `MODE.MULT`, `STANDARDIZE`, `CONFIDENCE.NORM`, `CONFIDENCE.T`, `FISHER`, `FISHERINV`, and the distributions: `NORM.*`, `LOGNORM.*`, `T.DIST`, `T.INV` (`.2T`, `.RT` by arithmetic), `CHISQ.DIST`, `CHISQ.INV`, `F.DIST`, `F.INV`, `BINOM.DIST`, `BINOM.INV`, `POISSON.DIST`, `EXPON.DIST`, `GAMMA.DIST`, `GAMMA.INV`, `GAMMA`, `GAMMALN`, `BETA.DIST`, `BETA.INV`, `WEIBULL.DIST`, `HYPGEOM.DIST`, `NEGBINOM.DIST` | `AVERAGEA`, `MAXA`, `MINA`, `STDEVA`, `VARA` … (one rule: text is not a number); `LINEST`, `LOGEST`, `TREND`, `GROWTH` (MX or tier 3); `FREQUENCY`, `T.TEST`, `F.TEST`, `Z.TEST`, `CHISQ.TEST`, `FORECAST.ETS*` (tier 3); `PERCENTRANK.EXC`, `PROB`, `GAUSS` (`norm_s_dist(z) - 0.5`), `PHI` (`norm_s_dist(x, false)`), `KURT.P`-style forms Excel lacks; the pre-2010 names (`NORMDIST`, `TDIST` …: legacy) |
| Text | `CONCAT`, `TEXTJOIN` (`concat`, `string_agg`), `LEFT`, `RIGHT`, `MID`, `SUBSTITUTE` (`replace`), `TEXTSPLIT` (`split`), `TRIM`, `UPPER`, `LOWER`, `REGEXTEST` (`matches`), `REGEXEXTRACT` | `LEN`, `PROPER`, `REPT`, `EXACT`, `UNICHAR` / `UNICODE` (`char`, `code`), `FIND`, `SEARCH`, `TEXTBEFORE`, `TEXTAFTER`, `REGEXREPLACE`, `CLEAN`, `VALUE`, `NUMBERVALUE` (`value`), `TEXT`, `FIXED`, `DOLLAR` (`text`) | `CHAR`, `CODE` on a code page, `ASC`, `DBCS`, `JIS`, `BAHTTEXT`, `PHONETIC` (locale); `T`, `N` (legacy); `REPLACE` by position (`left` + `mid`); `VALUETOTEXT`, `ARRAYTOTEXT` (cell display) |
| Date and time | `TODAY` (`today()`, the view's business date), `DATEVALUE`, `YEARFRAC`, `EDATE`, `EOMONTH`, `DAYS` (`days_between`), `NETWORKDAYS(.INTL)` (`intck('business_day')`, BI's `business_days_between`), `WORKDAY(.INTL)` (`shift_date`), `ISOWEEKNUM` (`week`) | `DATE`, `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND`, `WEEKDAY`, `DAYS360`, `DATEDIF`, `shift_date` in Rachana | `NOW` (non-deterministic); `TIME`, `TIMEVALUE` (Rachana has no time-of-day type: `hour`, `minute`, `second` read one); `WEEKNUM` system 1 (one week rule: ISO's) |
| Logical | `IF`, `IFS`, `SWITCH`, `IFERROR`, `IFNA` (`iferror`, `coalesce`), `LET`, `AND`, `OR`, `NOT`, `XOR` (`&&`, `\|\|`, `!`, `!=`), `TRUE`, `FALSE`, `MAP`, `REDUCE`, `SCAN` (`cumsum` …) | `CHOOSE` | `LAMBDA`, `BYROW`, `BYCOL`, `MAKEARRAY` (a named function is a custom function of the engine; MX for arrays) |
| Lookup and reference | `XLOOKUP`, `VLOOKUP`, `HLOOKUP`, `INDEX`, `MATCH`, `XMATCH`, `LOOKUP`, `FILTER` (path filters `[?@.KEY == x]`, `related(...)`), `SORT`, `SORTBY`, `UNIQUE`, `TAKE`, `DROP`, `TOCOL` (`flatten`), `VSTACK` (`flatten`, `union`), `ROWS`, `COLUMNS` (`size`), `HYPERLINK` (`link`), `GETPIVOTDATA` (`cell`), `GROUPBY`, `PIVOTBY` (`group`, the pivot) | sort by several keys | `OFFSET`, `INDIRECT`, `ADDRESS`, `AREAS`, `CELL`, `ROW`, `COLUMN`, `FORMULATEXT`, `RTD` (spreadsheet-cell); `TRANSPOSE`, `HSTACK`, `WRAPROWS`, `WRAPCOLS`, `EXPAND`, `CHOOSECOLS`, `CHOOSEROWS` (MX: two-dimensional arrays) |
| Financial | | `NPV`, `XNPV`, `IRR`, `XIRR`, `MIRR`, `PV`, `FV`, `PMT`, `IPMT`, `PPMT`, `NPER`, `RATE`, `CUMIPMT`, `CUMPRINC`, `EFFECT`, `NOMINAL`, `FVSCHEDULE`, `RRI`, `PDURATION`, `ACCRINTM` (`accrued`), `DOLLARDE`, `DOLLARFR`, `DURATION`, `MDURATION` (on given flows) | `PRICE`, `YIELD`, `ACCRINT`, `COUP*`, `ODD*`, `TBILL*`, `DISC`, `INTRATE`, `RECEIVED`, `PRICEDISC`, `PRICEMAT`, `YIELDDISC`, `YIELDMAT` (pricing: QLRisk's conventions and schedules); `SLN`, `SYD`, `DB`, `DDB`, `VDB`, `AMORLINC`, `AMORDEGRC` (accounting depreciation: no use); `ISPMT` (legacy); `STOCKHISTORY` (market data, a feed) |
| Information | `ISNONTEXT` (`!is_text`), `ISEVEN`, `ISODD` (`mod`), `NA` (an empty value) | `ISNUMBER`, `ISTEXT`, `ISLOGICAL` (`is_boolean`), `ISBLANK`, `ISERROR`, `ISERR`, `ISNA` (`is_error`), `TYPE` (`type_of`), is a date | `CELL`, `INFO`, `SHEET`, `SHEETS`, `ISREF`, `ISFORMULA`, `ERROR.TYPE` (spreadsheet-cell) |
| Engineering (light) | `CONVERT` of currencies (`fx`, `convert`) | `ERF`, `ERFC`, `BASE`/`DECIMAL` for `DEC2BIN`, `BIN2DEC`, `DEC2HEX`, `HEX2DEC`, `DEC2OCT`, `OCT2DEC` | `BITAND`, `BITOR`, `BITXOR`, `BITLSHIFT`, `BITRSHIFT`, `COMPLEX` and `IM*`, `BESSEL*`, `DELTA`, `GESTEP`, unit `CONVERT` (no use) |
| Cube, web, database | `CUBEVALUE` … (the pivot, `cell`), `DSUM`, `DAVERAGE` … (`sum` with `where`) | | `WEBSERVICE`, `FILTERXML`, `ENCODEURL`, `IMAGE`, `TRANSLATE`, `DETECTLANGUAGE`, `COPILOT` (outside a figure: a connector's or the AI layer's) |

### 12.2 SAS (DATA step, PROC MEANS, UNIVARIATE, FREQ, RANK)

| Family | Covered (by) | Added | Not (why) |
|---|---|---|---|
| PROC MEANS / SUMMARY statistics | `N` (`count`), `NMISS`, `MEAN`, `SUM`, `MIN`, `MAX`, `RANGE`, `STD`, `VAR`, `STDERR`, `CV` (`cv` · 100), `SKEWNESS`, `KURTOSIS`, `MEDIAN`, `P1` … `P99`, `Q1`, `Q3` (`percentile`), `QRANGE` (`iqr`), `MODE`, `SUMWGT` (`sum` of the weight), weighted `MEAN` (`wavg`), `CLM`, `LCLM`, `UCLM` (`avg` ∓ `confidence_t`), `T` (`avg / stderr`), `PROBT` (`t_dist`) | `USS` (`sumsq`), `CSS` (`devsq`), `VARDEF=N` forms (`variance_pop`, `stddev_pop`, `skewness_pop`, `covar_pop`) | `KURTOSIS` with `VARDEF=N` (no Excel form; on request) |
| PROC UNIVARIATE | moments, quantiles (`percentile`, `percentile_exc` for `PCTLDEF=4`), extremes (`kth_largest`, `maxk`), `TRIMMED=` (`trimmean`), robust scale (`isoutlier`'s MAD) | `MODES` (`mode_multi`), `MAD`-based outliers | `WINSORIZED=` (tier 3), normality tests and plots (tier 3; no plotting) |
| PROC FREQ | one-way counts (`group`, the pivot's `count`), percentages (`pct_of_total` …) | the distributions a test needs (`chisq_dist` …) | `CHISQ`, `FISHER` exact, `MEASURES`, `AGREE` (tier 3) |
| PROC RANK, PROC STDIZE, PROC CORR, PROC REG | `GROUPS=` (`ntile`), `TIES=` (`rank`, `dense_rank`), `PERCENT` (`percent_rank`), `CORR` (`corr`, `covar`), simple regression (`slope`, `intercept`, `rsq`) | `TIES=MEAN` of a value (`rank_avg`), `METHOD=STD` (`zscore`), `METHOD=RANGE` (`rescale`), Root MSE (`steyx`), small polynomial fits (`polyfit`) | several regressors, `COV` matrices, PROC PRINCOMP (MX) |
| DATA-step numeric | `ABS`, `ROUND`, `CEIL`, `FLOOR`, `INT`, `MOD`, `SQRT`, `EXP`, `LOG`, `LOG10`, `LOG2` (`log(x, 2)`), `SIGN`, `MIN`, `MAX` of arguments (`least`, `greatest`), `SUM(OF ...)`, `MEAN(OF ...)` over a list (`sum`, `avg`), `COALESCE`, `IFN`, `IFC` (`if`), `LAG`, `DIF` (`lag`, `diff`), `RETAIN` (`running`, `cumsum`) | `FACT`, `COMB`, `PERM`, `GCD`, `LCM`, trigonometry, `GAMMA`, `LGAMMA`, `ERF`, `ERFC`, `CDF`/`PDF`/`QUANTILE` of the families of section 9, `PROBNORM`, `PROBIT`, `PROBT`, `TINV`, `PROBCHI`, `CINV`, `PROBF`, `FINV`, `PROBBNML`, `POISSON`, `PROBGAM`, `GAMINV`, `PROBBETA`, `BETAINV`, `MORT`, `FINANCE('NPV' …)` | `RAND`, `RANUNI`, `RANNOR`, `STREAMINIT`, `DATETIME()`, `TIME()` (non-deterministic); `BLACKCLPRC`, `BLKSHPTPRC`, `GARKHCLPRC` (pricing: QLRisk) |
| DATA-step character | `UPCASE`, `LOWCASE`, `LENGTH`, `SUBSTR`, `SCAN` (`split`), `CAT`, `CATS`, `CATX` (`concat`, `string_agg`), `STRIP`, `TRIM`, `LEFT`, `TRANWRD` (`replace`), `PRXMATCH` (`matches`), `PRXCHANGE`, `INDEX`, `FIND`, `PUT`, `INPUT` | `PROPCASE` (`proper`), `REPEAT` (`rept`), `REVERSE`, `COMPRESS`, `COMPBL` (`regex_replace`), `INDEX`/`FIND` positions, `INPUT(s, COMMA.)` (`value`), `PUT(x, format)` (`text`) | `SOUNDEX`, `SPEDIS`, `COMPGED` (fuzzy matching: no use yet); `TRANSLATE` per character (`regex_replace` per class); `KSUBSTR` and the `K` functions (DBCS locale) |
| DATA-step date and time | `TODAY`, `MDY`, `YEAR`, `QTR`, `MONTH`, `DAY`, `INTCK`, `INTNX` (`edate`, `eomonth`, `shift_date`), `YRDIF` (`yearfrac`), `DATDIF` | `HOUR`, `MINUTE`, `SECOND`, `WEEKDAY`, `WEEK(d, 'V')` (`week`), `INTCK('continuous')` (`datedif`) | `DATETIME()`, `TIME()` (non-deterministic); `HOLIDAY` (the bank's calendars instead), `NWKDOM` (no use) |

### 12.3 MATLAB and Octave

| Family | Covered (by) | Added | Not (why) |
|---|---|---|---|
| Descriptive statistics | `sum`, `mean`, `median`, `mode`, `min`, `max`, `std`, `var`, `range`, `prctile`, `quantile` (`percentile`), `iqr`, `skewness`, `kurtosis`, `corrcoef` of two (`corr`), `cov` of two (`covar`), `nnz` (`count` with `where`), `numel` (`size`) | `bounds`, `maxk`, `mink`, `zscore`, `rescale`, `normalize` (`zscore`, `rescale`), `isoutlier`, `trapz`, `cumtrapz` | `histcounts` (tier 3), `corrcoef`/`cov` of many columns (MX) |
| Running and moving | `diff` (`diff`), cumulative windows of pivot cells (`running`) | `cumsum`, `cumprod`, `cummax`, `cummin`, `movsum`, `movmean`, `movstd`, `movmedian` (over a list) | `movmax`, `movmin`, `movvar`, `movprod`, `movmad` (on request: each one line on the engine's aggregates); `smoothdata` (tier 3) |
| Missing data | `ismissing` (`is_blank`), `rmmissing` (`$.LIST[?!is_blank(@)]`) | `fillmissing` (previous, next, nearest, linear, constant) | `fillmissing` by spline or a moving method (on request); `standardizeMissing` (no use) |
| Interpolation and fitting | `polyfit` degree 1 (`slope`, `intercept`) | `interp1` (linear, previous, next, nearest), `polyfit` (degree ≤ 6, QR), `polyval` | `interp1` spline, pchip, makima (not until needed and tested); `interp2`, `griddedInterpolant`, `spline`, `fit` (MX or QLRisk's curves); `fzero`, `fminsearch` (solvers: QLRisk) |
| Sets and sorting | `unique`, `sort`, `issorted` (`sort(...) == list`) | `union`, `intersect`, `setdiff` (`difference`), `ismember` (`is_member`), `sortrows` (`sort` by several keys) | `setxor` (`difference(union(a, b), intersect(a, b))`) |
| Elementary math | `abs`, `round` (`round`), `floor`, `ceil` (`ceiling`), `fix` (`round_down`), `mod`, `rem`, `sqrt`, `exp`, `log`, `log10`, `log2`, `power`, `sign`, `max`/`min` of two (`greatest`, `least`), `factorial` (`fact`), `nchoosek` (`combin`), `gcd`, `lcm`, `pi`, trigonometry, `dec2bin`, `bin2dec`, `dec2hex`, `hex2dec` (`base`, `decimal`) | `round(x, n, 'significant')` (`round_sig`) | `primes`, `isprime`, `factor` (no use) |
| Text and dates | `upper`, `lower`, `strtrim` (`trim`), `strsplit` (`split`), `strjoin` (`string_agg`), `strrep` (`replace`), `regexp` (`regex_extract`), `regexprep` (`regex_replace`), `contains`, `startsWith`, `endsWith`, `datestr` (`text`), `year`, `month`, `day`, `weekday`, `calmonths` (`intck`), `busdate` (`shift_date`) | | `datetime('now')`, `now`, `clock`, `rand`, `randn`, `randi` (non-deterministic) |
| Signal, matrices, toolboxes | | | `filter`, `conv`, `fft`, `ifft`, `xcorr` (signal processing: no use on risk data; `movmean` covers smoothing); `mtimes`, `inv`, `mldivide`, `det`, `eig`, `chol`, `svd`, `pca` (MX); plotting (`plot` …: the console draws); Financial Toolbox pricing (`blsprice`, `bndprice`, `zbtprice` …: QLRisk), `portvar`, `portopt` (risk engines, `measure(...)`) |

**Counts (2.9.11.e).** Drishti gained **154** registered functions, each under its one name: 127 in Rachana (9 of them DEL's scalars,
each one implementation shared with part A's fold: `round`, `len`, `date`, `year`, `quarter`, `month`, `week`, `day`, `shift_date`), 12 aggregates
and 15 list analytics in the engine; Rachana's `sort` takes several keys. Counting each Excel, SAS or MATLAB name once as these tables
list it (a family written with "…" counts once): **268** were already covered, **207** are answered by the added functions, **193** are not
added: no use yet (about 45, accounting depreciation and fuzzy matching among them), MX matrices (about 30), non-deterministic (18),
spreadsheet-cell (18), pricing for QLRisk (about 20), tier 3 candidates (about 17), locale or legacy (about 15), outside a figure (7),
one rule for text in numbers (5), signal processing (5), risk engines (2); a few are on request (`movmax` …).
