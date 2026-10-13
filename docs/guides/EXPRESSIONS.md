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
# Expressions: the Drishti expression language (DEL)

DEL is how you write **measures**, **derived columns**, **filters** and **row rules** in Drishti BI. It reads like Python: every DEL expression is a
valid Python expression (`ast.parse(text, mode="eval")` accepts it), but only a small, safe part of Python is allowed, and nothing is ever run by a
Python interpreter. The Java server parses the text, checks it, and compiles it into the query plan (SQL with bound values), **after** your row rules
and masks are applied. DEL v2 arrived in release 2.2.0 (wave A) and replaces the older Rachana-EL-flavoured syntax (`&&`, `?:`, `[Measure]`); a
converter rewrites old expressions for you ([section 10](#10-the-old-syntax-and-the-converter)).

```python
sum(trades.mtm, where=trades.status == 'Live')                    # MTM of the live trades
measure('P&L 1d') / measure('Exposure (EE peak)')                 # one measure over another
notional * fx(currency, 'USD')                                    # a derived column (fx arrives in wave B)
trades.desk in user.desks                                         # a row rule: each desk head sees their desks
trades.mtm if trades.mtm > 0 else 0                               # a conditional
```

Contents: [1](#1-where-you-write-del-scopes) scopes · [2](#2-the-grammar) grammar · [3](#3-what-is-refused) what is refused ·
[4](#4-values-and-edge-cases) values and edge cases · [5](#5-measures-referring-to-measures) measure references ·
[6](#6-functions) functions (generated from the catalogue) · [7](#7-problems-and-their-fixes) problems and fixes ·
[8](#8-checking-an-expression-the-api-and-the-cli) the API and the CLI · [9](#9-how-del-differs-from-python-and-from-dax) DEL vs Python and DAX ·
[10](#10-the-old-syntax-and-the-converter) the old syntax and the converter · [11](#11-recipes) recipes

Related: [DATASETS.md](DATASETS.md) (datasets and their security), [BI_DESIGN.md §3.3](../architecture/BI_DESIGN.md#33-del-the-drishti-expression-language-python-like)
(the design), [API_GUIDE.md](API_GUIDE.md#expressions-apiv1biexpr) (the endpoints), [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibiexpr--the-expression-language)
(`drishti.bi.expr.*`).

---

## 1. Where you write DEL (scopes)

Where an expression is written decides what it may name. The server checks the **scope** of every expression; the expression editor and
`POST /api/v1/bi/expr/validate` take it as `scope`.

| Scope | Where | May name | Aggregates? | Result |
|---|---|---|---|---|
| **row** | a dataset's `derive.*.expr` and `columns.*.expr`, its `where:`, MQ and SQL row filters | the columns of its own dataset (`notional`, or `trades.notional`); `related(desk.name)` one hop | no — an aggregate belongs in a measure | one value per row |
| **rule** | `security.rows[].where` | the dataset's own columns and `user.<attribute>` (`user.desks`, `user.name`) | no | `True` or `False` per row; no function can remove it |
| **aggregate** | `measures.*.expr`, MQ `define`, report local measures | `alias.column` **inside an aggregate**, `measure('name')` | yes — a bare column outside an aggregate is refused: *wrap it: `sum(trades.mtm)`* | one value per group of the answer |
| **context** *(wave B)* | the first argument of `calc`, `share_of`, `parent_of`, `as_of` | as aggregate | yes | one value per group, in an edited filter context |

**Row context** is "this row of this dataset": `notional * 2` is worked out for each trade. **Aggregate context** is "the rows of this group,
after security and filters": `sum(trades.mtm)` in the bar for desk RATES is the sum over the RATES trades *you* may see.

## 2. The grammar

DEL is this subset of Python's expression grammar (precedence from loosest to tightest, as in Python):

```
expr        := ifexpr
ifexpr      := orexpr ["if" orexpr "else" expr]                 # a if cond else b
orexpr      := andexpr ("or" andexpr)*
andexpr     := notexpr ("and" notexpr)*
notexpr     := "not" notexpr | comparison
comparison  := arith (compop arith)*                           # chained: 0 < x <= 10
compop      := "==" | "!=" | "<" | "<=" | ">" | ">=" | "in" | "not in" | "is" | "is not"   # is / is not only with None
arith       := term (("+" | "-") term)*
term        := factor (("*" | "/" | "//" | "%") factor)*
factor      := ("-" | "+") factor | power
power       := primary ["**" factor]
primary     := atom | call | field
field       := NAME ("." NAME)*                                 # trades.mtm, trades.tradeDate.month, user.desks
call        := FUNCNAME "(" [arg ("," arg)* [","]] ")"          # catalogue functions only; never a method call
arg         := expr | NAME "=" expr                             # keyword arguments: where= by= desc= ...
atom        := NUMBER | STRING | True | False | None | ALL | list | "(" expr ")"
list        := "[" [expr ("," expr)* [","]] "]"                 # for in / not in, and field lists (by=[...])
```

| Element | Written | Notes |
|---|---|---|
| numbers | `12`, `1.5`, `1e6`, `1_000_000` | a number with `.` or an exponent is a decimal |
| text | `'Live'`, `"it's"` | escapes `\\` `\'` `\"` `\n` `\t`; adjacent texts join (`'a' 'b'`); no f-strings, bytes, raw or triple-quoted strings |
| booleans, null | `True`, `False`, `None` | capitalised, as in Python |
| dates | `date('2026-10-09')`, `timestamp('2026-10-09T09:30')` | DEL has no date literal; a column compared with an ISO text (`trades.tradeDate >= '2026-10-01'`) is also read as a date |
| fields | `trades.mtm`, `trades.tradeDate.year` (`.quarter`, `.month`, `.week`, `.day`) | in row and rule scope a bare `mtm` is a column of the dataset in context |
| the reader | `user.desks`, `user.name`, `user.roles` | row rules only |
| lists | `['USD', 'EUR']` | only on the right of `in` / `not in`, or as `by=[...]` |
| comments | `# to the end of the line` | in YAML, write a multi-line expression as a block: `expr: \|` |
| bounds | 2,000 characters (4,000 when written over several lines), nesting depth 64 | split a long expression into measures that use each other |

Operators: `+ - * / // % **`, comparisons, `in`, `not in`, `is None`, `is not None`, `and`, `or`, `not`, `a if c else b`. Keyword arguments
follow positional ones; `where=`, `by=`, `desc=` are keyword-only (write the name).

## 3. What is refused

Everything else Python has is refused with `DRS-9127`, naming the construct and what to write instead:

| You wrote | The server says | Write instead |
|---|---|---|
| `x := 1`, `x = 1` | assignment is not DEL | `==` for equality; a measure for a reusable value |
| `lambda x: x` | lambda is not DEL | a catalogue function, or a Python item |
| `[x for x in t]`, `sum(x for x in t)` | comprehensions and generators are not DEL | `sum(x, where=...)`, `count(alias, where=...)` |
| `trades.mtm[0]` | subscripts and slices are not DEL | name the column: `alias.column` |
| `trades.mtm.sum()` | method calls are not DEL | `sum(trades.mtm)` |
| `f(*args)`, `f(**kw)` | star arguments are not DEL | write each argument |
| `f'{x}'`, `b'x'`, `'''x'''` | prefixed or triple-quoted strings are not DEL | a plain `'text'` |
| `{'a': 1}`, `(1, 2)` | dict, set and tuple literals are not DEL | a list for `in`: `[1, 2]` |
| `import`, `await`, `yield`, `def` ... | not DEL | — |
| `a && b`, `!a`, `c ? a : b`, `true`, `null` | the old syntax | `and`, `not`, `a if c else b`, `True`, `None` (run the converter) |

## 4. Values and edge cases

Every function follows these rules; the reference in [section 6](#6-functions) adds what is particular to each.

| Case | Rule |
|---|---|
| `None` | arithmetic with `None` gives `None`; a comparison with `None` is never `True` — write `x is None` / `x is not None` (`x == None` is refused); aggregates skip `None`; `count(x)` counts the values that are not `None` |
| division | `/` is decimal division and gives `None` on a zero divisor (never an error, never infinity); `//` (floor division) and `%` likewise |
| no rows | `sum`, `avg`, `min`, `max` of no rows are `None`; `count` and `distinct` are `0`. Show `0` with `coalesce(sum(x), 0)` |
| masked values | a masked column reads as the text `•••` in row scope; an aggregate over it is `None` and the answer marks it masked; a filter, sort or `in` on it is refused (`DRS-9161`) |
| currencies | summing an amount whose currency is a column, across several currencies, is the currency guard (`DRS-9158`): group or filter by the currency |
| types | integer with decimal gives decimal; `date - date` gives integer days; text with number is refused (no implicit casts); the items of `in [...]` must have the field's type |
| booleans | `and`, `or`, `not` and `a if c else b` take conditions only: no truthiness of numbers or texts (`if trades.mtm` is refused; write `trades.mtm != 0`) |
| `%` with negatives | follows SQL (the sign of the dividend), not Python (the sign of the divisor) |

## 5. Measures referring to measures

A measure names another with **`measure('name')`** — the name in quotes, so any name works (`measure('P&L 1d')`, `measure('Exposure (EE peak)')`),
or the measure's stable id (`measure('m_mtm')`), which survives a rename. The designer shows the reference as a chip and writes `measure('…')` into
the YAML. Measures that refer to each other in a circle are refused (`DRS-9127`). A measure reference belongs in aggregate scope, outside
aggregates: `sum(measure('MTM'))` is refused (aggregate columns; combine measures outside aggregates).

```yaml
measures:
  MTM:          { id: m_mtm, expr: "sum(trades.mtm)" }
  Trades:       { id: m_trades, expr: "count(trades)" }
  MTM per trade: { expr: "measure('m_mtm') / measure('Trades')" }      # by id and by name
```

## 6. Functions

The reference below is generated from the server's catalogue (`drishti-server/src/main/resources/bi/expr/functions.yaml`), the same list the
server checks against, `GET /api/v1/bi/expr/functions` serves to the expression editor and AI uses; a test fails when they disagree. A function
marked with a later wave is refused with `DRS-9129` until that wave.

<!-- functions:begin (generated from drishti-server/src/main/resources/bi/expr/functions.yaml; do not edit by hand) -->

| Family | Functions |
|---|---|
| scalar | [`abs`](#fn-abs), [`round`](#fn-round), [`coalesce`](#fn-coalesce), [`upper`](#fn-upper), [`lower`](#fn-lower), [`len`](#fn-len), [`contains`](#fn-contains), [`startswith`](#fn-startswith), [`endswith`](#fn-endswith), [`date`](#fn-date), [`timestamp`](#fn-timestamp), [`year`](#fn-year), [`quarter`](#fn-quarter), [`month`](#fn-month), [`week`](#fn-week), [`day`](#fn-day), [`days_between`](#fn-days-between), [`business_days_between`](#fn-business-days-between) |
| conversion | [`fx`](#fn-fx) *(B)*, [`convert`](#fn-convert) *(B)* |
| lookup | [`related`](#fn-related) |
| aggregate | [`sum`](#fn-sum), [`avg`](#fn-avg), [`min`](#fn-min), [`max`](#fn-max), [`count`](#fn-count), [`distinct`](#fn-distinct), [`ratio`](#fn-ratio), [`percentile`](#fn-percentile), [`avg_by`](#fn-avg-by) *(B)* |
| time intelligence | [`ytd`](#fn-ytd), [`mtd`](#fn-mtd), [`qtd`](#fn-qtd), [`prior_period`](#fn-prior-period), [`same_period_last_year`](#fn-same-period-last-year), [`running`](#fn-running), [`fiscal_ytd`](#fn-fiscal-ytd) *(B)* |
| windows | [`lag`](#fn-lag), [`lead`](#fn-lead), [`rank`](#fn-rank), [`dense_rank`](#fn-dense-rank), [`percent_rank`](#fn-percent-rank), [`moving_avg`](#fn-moving-avg), [`moving_sum`](#fn-moving-sum), [`moving_min`](#fn-moving-min), [`moving_max`](#fn-moving-max), [`cumulative`](#fn-cumulative) |
| balances | [`last`](#fn-last) *(B)*, [`first`](#fn-first) *(B)*, [`opening`](#fn-opening) *(B)*, [`closing`](#fn-closing) *(B)*, [`avg_over`](#fn-avg-over) *(B)* |
| filter context | [`calc`](#fn-calc) *(B)*, [`share_of`](#fn-share-of) *(B)*, [`parent_of`](#fn-parent-of) *(B)* |
| time travel | [`as_of`](#fn-as-of) *(B)*, [`shift_date`](#fn-shift-date) *(B)*, [`asof`](#fn-asof) *(B)* |
| measure reference | [`measure`](#fn-measure) |

### Scalar

<a id="fn-abs"></a>
#### `abs(x)`

*Scopes: row, rule, aggregate · returns: the type of `x` · since W1*

The absolute value of a number.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | number | required |  |

| Example | What it says |
|---|---|
| `abs(trades.mtm)` | the size of a trade's MTM, whatever its sign (row scope) |
| `sum(abs(trades.mtm))` | gross MTM: the sum of the absolute values |

* **None:** abs(None) is None.
* **Types:** Numbers only; abs of a text or a date is DRS-9127.

<a id="fn-round"></a>
#### `round(x, n=0)`

*Scopes: row, rule, aggregate · returns: the type of `x` · since W1*

Rounds a number to n decimal places (half away from zero, as the database rounds).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | number | required |  |
| `n` | integer-literal | `0` |  |

| Example | What it says |
|---|---|
| `round(trades.notional / 1000000, 1)` | notional in millions with one decimal |
| `round(measure('MTM'))` | a measure rounded to a whole number |

* **None:** round(None, 2) is None.
* **Types:** n is a whole number written in the expression (not a field); a negative n rounds to tens, hundreds, ...

<a id="fn-coalesce"></a>
#### `coalesce(a, b, ...)`

*Scopes: row, rule, aggregate · returns: the type of `values` · since W1*

The first of its arguments that is not None.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `values` | any | — | one or more |

| Example | What it says |
|---|---|
| `coalesce(trades.book, 'UNBOOKED')` | the book, or UNBOOKED when it is missing |
| `coalesce(measure('MTM'), 0)` | a measure that shows 0 instead of an empty cell |

* **None:** None only when every argument is None.
* **Types:** Every argument must have the same type (a number with a text is DRS-9127).

<a id="fn-upper"></a>
#### `upper(s)`

*Scopes: row, rule, aggregate · returns: string · since W1*

The text in capital letters.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `s` | string | required |  |

| Example | What it says |
|---|---|
| `upper(trades.currency)` | usd becomes USD |
| `upper(counterparties.name) == 'ACME BANK'` | a comparison that ignores case |

* **None:** upper(None) is None.
* **Masked values:** A masked column reads as the text ••• and stays •••.

<a id="fn-lower"></a>
#### `lower(s)`

*Scopes: row, rule, aggregate · returns: string · since W1*

The text in small letters.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `s` | string | required |  |

| Example | What it says |
|---|---|
| `lower(trades.status)` | LIVE becomes live |
| `lower(trades.desk) in ['rates', 'credit']` | a list test that ignores case |

* **None:** lower(None) is None.

<a id="fn-len"></a>
#### `len(s)`

*Scopes: row, rule, aggregate · returns: integer · since A · old syntax: `length`*

The number of characters of a text.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `s` | string | required |  |

| Example | What it says |
|---|---|
| `len(trades.tradeId)` | how long the id is |
| `len(trades.comment) > 0` | a comment was written |

* **None:** len(None) is None (not 0).
* **Types:** Text only; len of a list is not DEL.

<a id="fn-contains"></a>
#### `contains(s, part)`

*Scopes: row, rule, aggregate · returns: boolean · since W1*

True when the text contains the part, ignoring case.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `s` | string | required |  |
| `part` | string | required |  |

| Example | What it says |
|---|---|
| `contains(trades.product, 'swap')` | every kind of swap |
| `sum(trades.mtm, where=contains(trades.book, 'HEDGE'))` | MTM of the hedge books |

* **None:** contains(None, 'x') is None, which a filter treats as not matching.
* **Masked values:** Filtering on a masked column is DRS-9161.

<a id="fn-startswith"></a>
#### `startswith(s, prefix)`

*Scopes: row, rule, aggregate · returns: boolean · since A · old syntax: `startsWith`*

True when the text starts with the prefix, ignoring case.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `s` | string | required |  |
| `prefix` | string | required |  |

| Example | What it says |
|---|---|
| `startswith(trades.book, 'RATES-')` | the rates books |
| `count(trades, where=startswith(trades.tradeId, 'BBG'))` | how many Bloomberg-booked trades |

* **None:** startswith(None, 'x') is None.

<a id="fn-endswith"></a>
#### `endswith(s, suffix)`

*Scopes: row, rule, aggregate · returns: boolean · since A*

True when the text ends with the suffix, ignoring case.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `s` | string | required |  |
| `suffix` | string | required |  |

| Example | What it says |
|---|---|
| `endswith(trades.book, '-LDN')` | the London books |
| `not endswith(trades.product, 'option')` | everything but options |

* **None:** endswith(None, 'x') is None.

<a id="fn-date"></a>
#### `date(text)`

*Scopes: row, rule, aggregate · returns: date · since A*

A date written in ISO form (YYYY-MM-DD). DEL has no date literal; this is it.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `text` | string-literal | required |  |

| Example | What it says |
|---|---|
| `trades.tradeDate >= date('2026-10-01')` | trades from October 2026 |
| `sum(trades.mtm, where=trades.maturityDate < date('2027-01-01'))` | MTM of what matures this year |

* **Types:** The text must be an ISO date; date('10/01/2026') is DRS-9127. Comparing a date with a text is allowed only for an ISO text.

<a id="fn-timestamp"></a>
#### `timestamp(text)`

*Scopes: row, rule, aggregate · returns: timestamp · since A*

A timestamp written in ISO form (YYYY-MM-DDTHH:MM[:SS]).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `text` | string-literal | required |  |

| Example | What it says |
|---|---|
| `trades.updatedAt >= timestamp('2026-10-09T09:00')` | changed since nine o'clock |
| `count(trades, where=trades.updatedAt < timestamp('2026-10-09T00:00:00'))` | rows not touched today |

* **Types:** The text must be an ISO timestamp; anything else is DRS-9127.

<a id="fn-year"></a>
#### `year(d)`

*Scopes: row, rule, aggregate · returns: integer · since W1*

The year of a date or timestamp. The same as the field part trades.tradeDate.year.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `d` | date | required |  |

| Example | What it says |
|---|---|
| `year(trades.tradeDate) == 2026` | trades of 2026 |
| `year(trades.maturityDate) - year(trades.tradeDate)` | a rough tenor in years |

* **None:** year(None) is None.
* **Types:** Dates and timestamps only.

<a id="fn-quarter"></a>
#### `quarter(d)`

*Scopes: row, rule, aggregate · returns: integer · since W1*

The quarter (1 to 4) of a date or timestamp.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `d` | date | required |  |

| Example | What it says |
|---|---|
| `quarter(trades.tradeDate)` | 1 for January to March |
| `count(trades, where=quarter(trades.tradeDate) == 4)` | trades done in the fourth quarter |

* **None:** quarter(None) is None.

<a id="fn-month"></a>
#### `month(d)`

*Scopes: row, rule, aggregate · returns: integer · since W1*

The month (1 to 12) of a date or timestamp.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `d` | date | required |  |

| Example | What it says |
|---|---|
| `month(trades.tradeDate)` | 10 for October |
| `month(trades.maturityDate) in [3, 6, 9, 12]` | quarter-end maturities |

* **None:** month(None) is None.

<a id="fn-week"></a>
#### `week(d)`

*Scopes: row, rule, aggregate · returns: integer · since A*

The ISO week number (1 to 53) of a date or timestamp.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `d` | date | required |  |

| Example | What it says |
|---|---|
| `week(trades.tradeDate)` | the ISO week a trade was done in |
| `count(trades, where=week(trades.tradeDate) == 41)` | trades of week 41 |

* **None:** week(None) is None.
* **Types:** ISO weeks: the first days of January can belong to week 52 or 53 of the year before.

<a id="fn-day"></a>
#### `day(d)`

*Scopes: row, rule, aggregate · returns: integer · since W1*

The day of the month (1 to 31) of a date or timestamp.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `d` | date | required |  |

| Example | What it says |
|---|---|
| `day(trades.tradeDate)` | 9 for 2026-10-09 |
| `day(trades.maturityDate) == 15` | mid-month maturities |

* **None:** day(None) is None.

<a id="fn-days-between"></a>
#### `days_between(a, b)`

*Scopes: row, rule, aggregate · returns: integer · since A*

Calendar days from a to b (negative when b is before a). The same as b - a for two dates.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `a` | date | required |  |
| `b` | date | required |  |

| Example | What it says |
|---|---|
| `days_between(trades.tradeDate, trades.maturityDate)` | a trade's tenor in days |
| `avg(days_between(trades.tradeDate, trades.maturityDate))` | the average tenor |

* **None:** None when either date is None.
* **Types:** Timestamps are taken at their date.

<a id="fn-business-days-between"></a>
#### `business_days_between(a, b)`

*Scopes: row, rule, aggregate · returns: integer · since A*

Business days (Monday to Friday) from a, excluded, to b, included; negative when b is before a. Holidays of the model's calendar are counted from wave B (model calendars).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `a` | date | required |  |
| `b` | date | required |  |

| Example | What it says |
|---|---|
| `business_days_between(trades.tradeDate, trades.settlementDate)` | the settlement lag in business days |
| `count(trades, where=business_days_between(trades.tradeDate, trades.settlementDate) > 2)` | trades settling later than T+2 |

* **None:** None when either date is None.
* **Types:** Weekends only until model calendars arrive (B); a holiday is counted as a business day.

### Conversion

<a id="fn-fx"></a>
#### `fx(ccy, to, at=None)`

*Scopes: row · returns: decimal · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The rate that converts one unit of ccy into the currency to, at the row's date (or at=). Needs the model's FX dataset: built with model calendars and rates in wave B.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `ccy` | string | required |  |
| `to` | string-literal | required |  |
| `at` | date | `None` | keyword only: `at=...` |

| Example | What it says |
|---|---|
| `trades.notional * fx(trades.currency, 'USD')` | notional in US dollars |
| `trades.mtm * fx(trades.currency, 'EUR', at=trades.tradeDate)` | MTM in euros at the trade date |

* **None:** None when the currency or the rate for that date is missing; the answer warns.
* **Currencies:** A converted amount has one currency, so summing it lifts the currency guard (DRS-9158).

<a id="fn-convert"></a>
#### `convert(agg, to='USD', at='as-of')`

*Scopes: aggregate · returns: decimal · since B* — **arrives in wave B**: refused with `DRS-9129` until then

An aggregate of amounts in many currencies, converted to one, at the as-of date or at each row's trade date.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `to` | string-literal | `'USD'` | keyword only: `to=...` |
| `at` | string-literal | `'as-of'` | keyword only: `at=...` |

| Example | What it says |
|---|---|
| `convert(sum(trades.notional), to='USD')` | notional in USD at the as-of rate |
| `convert(sum(trades.mtm), to='EUR', at='trade-date')` | each trade converted at its own trade date |

* **Currencies:** Lifts the currency guard: the result has one currency.

### Lookup

<a id="fn-related"></a>
#### `related(alias.column)`

*Scopes: row · returns: the type of `field` · since A*

A column of the one row a many-to-one pack reference points at: one hop, in a dataset's derive (for example the desk's name on a trade).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `field` | field | required |  |

| Example | What it says |
|---|---|
| `related(desk.name)` | the desk's name next to each trade |
| `related(counterparty.sector)` | the counterparty's sector as a column of the trade dataset |

* **None:** None when the reference is empty or points at nothing.
* **Masked values:** A masked column of the referenced kind arrives masked.
* **Types:** Exactly one hop; related(related(...)) is DRS-9127.

### Aggregate

<a id="fn-sum"></a>
#### `sum(x, where=None)`

*Scopes: aggregate · returns: the type of `x` · since W1*

The sum of x over the rows of the group (after security and filters), only the rows where the condition holds when where= is given.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | number | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `sum(trades.mtm)` | MTM |
| `sum(trades.mtm, where=trades.status == 'Live')` | MTM of the live trades only |
| `sum(trades.notional * trades.rate) / sum(trades.notional)` | a notional-weighted average rate |

* **None:** None values are skipped.
* **No rows:** None over no rows (not 0); wrap it in coalesce(..., 0) to show 0.
* **Masked values:** None over a masked column, and the answer marks the column masked.
* **Currencies:** Summing an amount whose currency is a column, across several currencies, is the currency guard DRS-9158: group or filter by the currency.

<a id="fn-avg"></a>
#### `avg(x, where=None)`

*Scopes: aggregate · returns: decimal · since W1*

The arithmetic mean of x over the rows of the group.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | number | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `avg(trades.notional)` | the average trade size |
| `avg(economies.growth, where=economies.region == 'Asia')` | average growth of the Asian economies |

* **None:** None values are skipped (they are not zeros).
* **No rows:** None over no rows.
* **Masked values:** None over a masked column.

<a id="fn-min"></a>
#### `min(x, where=None)`

*Scopes: aggregate · returns: the type of `x` · since W1*

The smallest value of x in the group (numbers, dates or text).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | any | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `min(trades.mtm)` | the worst trade MTM |
| `min(trades.maturityDate, where=trades.status == 'Live')` | the next maturity |

* **None:** None values are skipped.
* **No rows:** None over no rows.

<a id="fn-max"></a>
#### `max(x, where=None)`

*Scopes: aggregate · returns: the type of `x` · since W1*

The largest value of x in the group (numbers, dates or text).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | any | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `max(netting_sets.utilisation)` | the highest limit utilisation |
| `max(trades.updatedAt)` | when the group last changed |

* **None:** None values are skipped.
* **No rows:** None over no rows.

<a id="fn-count"></a>
#### `count(alias | x, where=None)`

*Scopes: aggregate · returns: integer · since W1*

count(trades) counts the rows of a dataset alias; count(x) counts the rows where x is not None.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | table-or-any | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `count(trades)` | how many trades |
| `count(trades, where=trades.mtm < 0)` | how many trades lose money |
| `count(trades.book)` | trades with a book |

* **None:** count(x) skips None; count(alias) counts every row.
* **No rows:** 0 over no rows (never None).

<a id="fn-distinct"></a>
#### `distinct(x, where=None)`

*Scopes: aggregate · returns: integer · since W1*

How many different values of x the group has.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | any | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `distinct(trades.counterpartyId)` | how many counterparties |
| `distinct(trades.book, where=trades.status == 'Live')` | books with live trades |

* **None:** None is not a value.
* **No rows:** 0 over no rows.
* **Masked values:** None over a masked column (the values cannot be told apart).

<a id="fn-ratio"></a>
#### `ratio(num, den)`

*Scopes: aggregate · returns: decimal · since W1*

num / den as a decimal, None when den is 0. The same as num / den in DEL, kept for readability.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `num` | aggregate | required |  |
| `den` | aggregate | required |  |

| Example | What it says |
|---|---|
| `ratio(sum(trades.mtm), sum(trades.notional))` | MTM per unit of notional |
| `ratio(sum(regions.gdpUsdBn), sum(regions.population)) * 1000000000` | GDP per head in USD |

* **None:** None when either side is None.
* **No rows:** None over no rows.

<a id="fn-percentile"></a>
#### `percentile(x, p, where=None)`

*Scopes: aggregate · returns: decimal · since W1*

The continuous p-quantile of x in the group (p between 0 and 1): 0.5 is the median, 0.99 the 99th percentile.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `x` | number | required |  |
| `p` | number-literal | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |

| Example | What it says |
|---|---|
| `percentile(trades.mtm, 0.5)` | the median trade MTM |
| `-percentile(scenarios.pnl, 0.01)` | a 99% VaR-style loss from scenario P&L |

* **None:** None values are skipped.
* **No rows:** None over no rows.
* **Types:** p is a number written in the expression, 0 to 1.

<a id="fn-avg-by"></a>
#### `avg_by(agg, by=[fields])`

*Scopes: aggregate · returns: decimal · since B* — **arrives in wave B**: refused with `DRS-9129` until then

A two-level aggregate: agg per value of by, then the average of those (the explicit iteration DEL has instead of DAX context transition).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `by` | fields | required | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `avg_by(sum(trades.mtm), by=[trades.tradeDate])` | the average daily MTM |
| `avg_by(count(trades), by=[trades.book])` | trades per book, on average |

* **No rows:** None when no group of by has rows.

### Time intelligence

<a id="fn-ytd"></a>
#### `ytd(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate over the rows whose date falls between the start of the as-of date's year and the as-of date (the end of the query's dates), on the pack's business-day calendar.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `ytd(sum(trades.mtm), trades.tradeDate)` | MTM of the trades dated this year so far |
| `ytd(count(trades), trades.tradeDate)` | trades done this year |

* **No rows:** None (a count: 0) when no row is in the window.
* **Types:** date is a date or timestamp field of the aggregate's dataset.

<a id="fn-mtd"></a>
#### `mtd(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate over the rows dated from the start of the as-of month to the as-of date.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `mtd(sum(trades.pnl1d), trades.tradeDate)` | month-to-date P&L |
| `mtd(count(trades), trades.tradeDate)` | trades this month |

* **No rows:** None (a count: 0) when no row is in the window.

<a id="fn-qtd"></a>
#### `qtd(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate over the rows dated from the start of the as-of quarter to the as-of date.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `qtd(sum(trades.pnl1d), trades.tradeDate)` | quarter-to-date P&L |
| `qtd(sum(trades.notional), trades.tradeDate)` | notional traded this quarter |

* **No rows:** None (a count: 0) when no row is in the window.

<a id="fn-prior-period"></a>
#### `prior_period(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate over the period just before the query's dates, of the same number of business days.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `prior_period(sum(trades.pnl1d), trades.tradeDate)` | P&L of the period before |
| `sum(trades.pnl1d) - prior_period(sum(trades.pnl1d), trades.tradeDate)` | change on the prior period |

* **No rows:** None (a count: 0) when the prior period has no rows.

<a id="fn-same-period-last-year"></a>
#### `same_period_last_year(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate over the query's dates moved back one year.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `same_period_last_year(sum(trades.notional), trades.tradeDate)` | notional a year ago |
| `ratio(sum(trades.notional), same_period_last_year(sum(trades.notional), trades.tradeDate)) - 1` | growth on last year |

* **No rows:** None (a count: 0) when last year's window has no rows.

<a id="fn-running"></a>
#### `running(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

A running total of a sum, count, min or max along the date field of the answer (the date must be a field of the visual).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `running(sum(trades.notional), trades.tradeDate)` | cumulative notional by day |
| `running(count(trades), trades.tradeDate)` | trades so far |

* **Masked values:** Refused along a masked date (DRS-9021).
* **Types:** Only sum, count, min, max; the date field must be in the answer (DRS-9020 otherwise).

<a id="fn-fiscal-ytd"></a>
#### `fiscal_ytd(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate from the start of the fiscal year of the model's calendar to the as-of date.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `fiscal_ytd(sum(trades.pnl1d), trades.tradeDate)` | fiscal year-to-date P&L |
| `fiscal_ytd(count(trades), trades.tradeDate)` | trades this fiscal year |

* **No rows:** None (a count: 0) when no row is in the window.

### Windows

<a id="fn-lag"></a>
#### `lag(agg, n, order, by=[fields])`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate n rows before, along order (a field of the answer), restarting for each value of by (default: every other field of the answer).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `n` | integer-literal | required |  |
| `order` | field | required |  |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `lag(sum(trades.mtm), 1, trades.tradeDate)` | yesterday's MTM on each row |
| `sum(trades.mtm) - lag(sum(trades.mtm), 1, trades.tradeDate, by=[trades.desk])` | day-on-day change per desk |

* **None:** None on the first n rows of each partition.
* **Masked values:** A masked field is never a partition; ordering along a masked field is DRS-9021.

<a id="fn-lead"></a>
#### `lead(agg, n, order, by=[fields])`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The aggregate n rows after, along order.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `n` | integer-literal | required |  |
| `order` | field | required |  |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `lead(sum(trades.notional), 1, trades.maturityDate)` | the next maturity bucket's notional |
| `lead(count(trades), 2, trades.tradeDate, by=[trades.book])` | two days ahead, per book |

* **None:** None on the last n rows of each partition.

<a id="fn-rank"></a>
#### `rank(agg, desc=True, by=[fields])`

*Scopes: aggregate · returns: integer · since W1*

The position of each row of the answer by the aggregate, 1 for the highest (desc=False: 1 for the lowest); ties share a rank and leave gaps.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `desc` | boolean-literal | `True` | keyword only: `desc=...` |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `rank(sum(trades.mtm))` | MTM rank, best first |
| `rank(sum(netting_sets.eePeak), by=[counterparties.sector])` | exposure rank within each sector |

* **None:** None values rank last.

<a id="fn-dense-rank"></a>
#### `dense_rank(agg, desc=True, by=[fields])`

*Scopes: aggregate · returns: integer · since W1*

Like rank, without gaps after ties.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `desc` | boolean-literal | `True` | keyword only: `desc=...` |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `dense_rank(sum(trades.notional))` | 1, 2, 2, 3 for tied notionals |
| `dense_rank(count(trades), desc=False)` | the quietest first |

* **None:** None values rank last.

<a id="fn-percent-rank"></a>
#### `percent_rank(agg, desc=True, by=[fields])`

*Scopes: aggregate · returns: decimal · since W1*

The relative rank from 0 to 1.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `desc` | boolean-literal | `True` | keyword only: `desc=...` |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `percent_rank(sum(trades.mtm))` | 0 for the best desk, 1 for the worst |
| `percent_rank(sum(trades.notional), desc=False)` | a percentile position by size |

* **No rows:** 0 for a single row.

<a id="fn-moving-avg"></a>
#### `moving_avg(agg, n, order, by=[fields])`

*Scopes: aggregate · returns: decimal · since W1*

The average of the aggregate over the row and the n - 1 rows before it along order.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `n` | integer-literal | required |  |
| `order` | field | required |  |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `moving_avg(sum(trades.mtm), 3, trades.tradeDate)` | a three-day moving average of MTM |
| `moving_avg(sum(trades.pnl1d), 20, trades.tradeDate, by=[trades.desk])` | a 20-day average P&L per desk |

* **None:** The first rows average over what is there.

<a id="fn-moving-sum"></a>
#### `moving_sum(agg, n, order, by=[fields])`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The sum of the aggregate over the row and the n - 1 rows before it.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `n` | integer-literal | required |  |
| `order` | field | required |  |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `moving_sum(sum(trades.pnl1d), 5, trades.tradeDate)` | a rolling five-day P&L |
| `moving_sum(count(trades), 7, trades.tradeDate)` | trades over the last seven rows |

* **None:** None values are skipped.

<a id="fn-moving-min"></a>
#### `moving_min(agg, n, order, by=[fields])`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The smallest value of the aggregate over the row and the n - 1 rows before it.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `n` | integer-literal | required |  |
| `order` | field | required |  |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `moving_min(sum(trades.mtm), 10, trades.tradeDate)` | the worst MTM of the last ten days |
| `moving_min(min(trades.mtm), 3, trades.tradeDate)` | the worst trade over three days |

* **None:** None values are skipped.

<a id="fn-moving-max"></a>
#### `moving_max(agg, n, order, by=[fields])`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The largest value of the aggregate over the row and the n - 1 rows before it.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `n` | integer-literal | required |  |
| `order` | field | required |  |
| `by` | fields | `[]` | keyword only: `by=...` |

| Example | What it says |
|---|---|
| `moving_max(max(netting_sets.utilisation), 5, netting_sets.businessDate)` | peak utilisation over five days |
| `moving_max(sum(trades.mtm), 20, trades.tradeDate)` | the 20-day high of MTM |

* **None:** None values are skipped.

<a id="fn-cumulative"></a>
#### `cumulative(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since W1*

The same as running(agg, date).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `cumulative(sum(trades.notional), trades.tradeDate)` | notional so far |
| `cumulative(count(trades), trades.tradeDate)` | trades so far |

* **Types:** As running.

### Balances

<a id="fn-last"></a>
#### `last(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate on the last date of each group that has rows (a balance's semi-additive total).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `last(sum(accounts.balance), accounts.businessDate)` | the closing balance of the period |
| `last(sum(collateral.value), collateral.businessDate)` | collateral held at the end |

* **No rows:** None when the group has no dated row.

<a id="fn-first"></a>
#### `first(agg, date)`

*Scopes: aggregate · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate on the first date of each group that has rows.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `first(sum(accounts.balance), accounts.businessDate)` | the balance at the start of the period |
| `last(sum(accounts.balance), accounts.businessDate) - first(sum(accounts.balance), accounts.businessDate)` | the change over the period |

* **No rows:** None when the group has no dated row.

<a id="fn-opening"></a>
#### `opening(agg, date, period='month')`

*Scopes: aggregate · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The balance at the close of the business day before the period starts.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |
| `period` | string-literal | `'month'` | keyword only: `period=...` |

| Example | What it says |
|---|---|
| `opening(sum(accounts.balance), accounts.businessDate)` | the opening balance of the month |
| `opening(sum(accounts.balance), accounts.businessDate, period='quarter')` | the opening balance of the quarter |

* **No rows:** None when the day before has no rows.

<a id="fn-closing"></a>
#### `closing(agg, date, period='month')`

*Scopes: aggregate · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The balance on the last business day of the period (holidays from the model's calendar).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |
| `period` | string-literal | `'month'` | keyword only: `period=...` |

| Example | What it says |
|---|---|
| `closing(sum(accounts.balance), accounts.businessDate)` | the month-end balance |
| `closing(sum(accounts.balance), accounts.businessDate, period='year')` | the year-end balance |

* **No rows:** None when the period's last business day has no rows.

<a id="fn-avg-over"></a>
#### `avg_over(agg, date)`

*Scopes: aggregate · returns: decimal · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The average over the dates of the group of the aggregate per date (an average daily balance).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date` | date-field | required |  |

| Example | What it says |
|---|---|
| `avg_over(sum(accounts.balance), accounts.businessDate)` | the average daily balance |
| `avg_over(sum(collateral.value), collateral.businessDate)` | average collateral held |

* **No rows:** None when the group has no dated row.

### Filter context

<a id="fn-calc"></a>
#### `calc(agg, where=..., ignore=[...] | ALL, keep=[...], via='rel_id')`

*Scopes: aggregate, context · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate in an edited filter context: add conditions (where=), drop query and group filters on fields (ignore=, or ALL), keep only some (keep=), or follow an inactive relationship (via=). Security is never in scope.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `where` | boolean | `None` | keyword only: `where=...` |
| `ignore` | fields | `[]` | keyword only: `ignore=...` |
| `keep` | fields | `[]` | keyword only: `keep=...` |
| `via` | string-literal | `None` | keyword only: `via=...` |

| Example | What it says |
|---|---|
| `calc(measure('MTM'), ignore=[desks.name])` | MTM ignoring the desk slicer |
| `calc(measure('MTM'), via='r_5')` | MTM along the settlement-date relationship |

* **Masked values:** ignore=ALL means all the rows you may see, never more.

<a id="fn-share-of"></a>
#### `share_of(agg, over=[...] | ALL)`

*Scopes: aggregate, context · returns: decimal · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate divided by itself over a coarser group (over=ALL is the grand total you may see).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `over` | fields | required | keyword only: `over=...` |

| Example | What it says |
|---|---|
| `share_of(measure('MTM'), over=ALL)` | MTM as a share of the total you may see |
| `share_of(measure('MTM'), over=[books.name])` | a book's share within its desk |

* **None:** None when the coarser total is 0 or None.

<a id="fn-parent-of"></a>
#### `parent_of(agg, hierarchy='name')`

*Scopes: aggregate, context · returns: decimal · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate as a share of its parent level in a hierarchy of the model.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `hierarchy` | string-literal | required | keyword only: `hierarchy=...` |

| Example | What it says |
|---|---|
| `parent_of(measure('MTM'), hierarchy='Desk and book')` | a book's share of its desk |
| `parent_of(measure('Notional'), hierarchy='Region')` | a country's share of its region |

* **None:** None at the top level.

### Time travel

<a id="fn-as-of"></a>
#### `as_of(agg, date_expr)`

*Scopes: aggregate, context · returns: the type of `agg` · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The aggregate with every scan bound to another business date.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `agg` | aggregate | required |  |
| `date_expr` | date | required |  |

| Example | What it says |
|---|---|
| `measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))` | MTM day on day |
| `as_of(measure('Exposure'), date('2026-09-30'))` | exposure at the quarter end |

* **No rows:** None when that date has no data.

<a id="fn-shift-date"></a>
#### `shift_date(d, n, unit='business-day')`

*Scopes: row, aggregate, context · returns: date · since B* — **arrives in wave B**: refused with `DRS-9129` until then

A date moved by n units (business days by default, on the model's calendar).

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `d` | date | required |  |
| `n` | integer-literal | required |  |
| `unit` | string-literal | `'business-day'` | keyword only: `unit=...` |

| Example | What it says |
|---|---|
| `shift_date(asof(), -1)` | the previous business day |
| `shift_date(asof(), -1, unit='month')` | a month ago |

* **None:** shift_date(None, n) is None.

<a id="fn-asof"></a>
#### `asof()`

*Scopes: row, aggregate, context · returns: date · since B* — **arrives in wave B**: refused with `DRS-9129` until then

The query's as-of date.

| Example | What it says |
|---|---|
| `days_between(trades.tradeDate, asof())` | the age of a trade |
| `count(trades, where=trades.maturityDate <= shift_date(asof(), 30, unit='day'))` | trades maturing within 30 days |

* **Types:** Always a date.

### Measure reference

<a id="fn-measure"></a>
#### `measure('name')`

*Scopes: aggregate, context · returns: number · since A · old syntax: `[name]`*

Another measure of the model, by its name or its stable id (measure('m_mtm')); a rename keeps the binding by id.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `name` | string-literal | required |  |

| Example | What it says |
|---|---|
| `measure('P&L 1d') / measure('Exposure (EE peak)')` | P&L per unit of exposure |
| `measure('MTM') - measure('Open MTM')` | MTM of the matured trades |

* **None:** As the measure it names.
* **Types:** Measures that refer to each other in a circle are DRS-9127.

<!-- functions:end -->

## 7. Problems and their fixes

Every problem carries a code, a message, a **fix** when there is a mechanical one, and the position (`at.start`, `at.end`: character offsets).
These are the codes an expression can raise:

| Code | When | Example | Fix |
|---|---|---|---|
| `DRS-9127` | the language, a scope or a type | `trades.mtm * 2` as a measure: *the column trades.mtm is outside an aggregate* | `wrap it: sum(trades.mtm)` |
| `DRS-9127` | a positional argument where a keyword is required | `sum(trades.mtm, trades.status == 'Live')`: *sum() takes at most 1 argument before its keywords* | `name it: where=...` |
| `DRS-9127` | a comparison with `None` | `trades.book == None` | `write x is None` |
| `DRS-9127` | an aggregate in a row expression or rule | `sum(trades.mtm)` in `derive`: *an aggregate belongs in a measure* | make it a measure of the model |
| `DRS-9127` | the old syntax or an old function name | `startsWith(trades.book, 'R')`: *'startsWith' is the old name* | `write startswith(...)` |
| `DRS-9127` | a construct Python has and DEL does not ([section 3](#3-what-is-refused)) | `trades.mtm.sum()`: *method calls are not DEL* | `call the function on it: sum(trades.mtm)` |
| `DRS-9129` | a function or value of a later wave | `calc(measure('MTM'), ignore=ALL)`: *calc() arrives in wave B (filter context)* | — |
| `DRS-9152` | a field or measure that does not exist for you (a hidden column is "not found", on purpose) | `sum(trades.mtx)`: *no field 'trades.mtx'; did you mean trades.mtm?* | the suggested name |
| `DRS-9161` | a filter, sort or `in` on a field masked for you | `count(trades, where=trades.mtm < 0)` as a reader without raw | filter on a field that is not masked |
| `DRS-9100` | the old expression syntax: refused (the default `drishti.bi.expr.legacy: refuse`); a warning only while a site sets `legacy: warn` | `[MTM] / [Trades]` | `write: measure('MTM') / measure('Trades')` |
| `DRS-9190` | the converter could not keep the meaning of an old expression (it then leaves it as it was) | — | rewrite it by hand |
| `DRS-9158` | (at query time) a sum across currencies | `sum(trades.notional)` by desk, several currencies | group or filter by the currency |

Captured from the server (2.2.0), checking `trades.mtm * 2` as a measure:

```json
{"code": "DRS-9127", "level": "error",
 "message": "the column trades.mtm is outside an aggregate: a measure has one value per group, not per row",
 "fix": "wrap it: sum(trades.mtm)", "at": {"start": 0, "end": 10}}
```

and `[MTM] / [Trades]` once the old syntax is refused:

```json
{"code": "DRS-9100", "level": "error",
 "message": "old expression syntax (refused since wave A): run the converter, drishti.py bi migrate --expressions",
 "fix": "write: measure('MTM') / measure('Trades')", "at": {"start": 0, "end": 16}}
```

## 8. Checking an expression: the API and the CLI

`POST /api/v1/bi/expr/validate` checks one expression in its scope against a model **as you see it** (masked fields marked, hidden ones absent)
and, for a measure, also compiles it the way publishing a model would — without reading data. It always answers `200`; `valid` and `problems` say
what is wrong ([API_GUIDE.md](API_GUIDE.md#expressions-apiv1biexpr)).

```bash
drishti.py bi expr validate "sum(trades.notional, where=trades.desk == 'DESK-A') / measure('m_trades')" --model lang-model@1
# valid  (del2, aggregate scope, type decimal)
drishti.py bi expr validate "trades.notional * 2" --model lang-model@1
# INVALID  (del2, aggregate scope, type number)
#   error   DRS-9127  at 0-15: the column trades.notional is outside an aggregate: a measure has one value per group, not per row
#       fix: wrap it: sum(trades.notional)
drishti.py bi expr functions --scope row          # the functions you may use in a derived column
drishti.py bi expr convert "[MTM] / [Trades]"     # measure('MTM') / measure('Trades')
```

## 9. How DEL differs from Python and from DAX

| | Python | DEL | DAX |
|---|---|---|---|
| Execution | interpreted | compiled into the plan (SQL); never run as code | engine-evaluated |
| Loops, assignment, side effects | yes | none | none (`VAR` only) |
| `sum(x)` | over an iterable | over the group's rows, after security | `SUM(col)` |
| Conditional aggregate | — | `sum(x, where=cond)` | `CALCULATE(SUM(x), cond)` |
| Null | `None`, arithmetic raises | `None` propagates, never raises | `BLANK()` with coercions |
| Division by zero | `ZeroDivisionError` | `None` | `DIVIDE` or an error |
| Truthiness | any value | booleans only | coercions |
| `==` with `None` | works | refused: write `is None` | — |
| `%` with negatives | sign of the divisor | sign of the dividend (SQL) | `MOD`: sign of the divisor |
| Context override | — | `calc(…)` (wave B); security outside it | `CALCULATE`, with context transition |

**DAX → DEL** (what a Power BI author writes instead):

| DAX | DEL |
|---|---|
| `SUM(Trades[MTM])` | `sum(trades.mtm)` |
| `CALCULATE(SUM(Trades[MTM]), Trades[Status] = "Live")` | `sum(trades.mtm, where=trades.status == 'Live')` |
| `COUNTROWS(Trades)` | `count(trades)` |
| `DISTINCTCOUNT(Trades[Counterparty])` | `distinct(trades.counterpartyId)` |
| `DIVIDE([MTM], [Notional])` | `measure('MTM') / measure('Notional')` (or `ratio(...)`: `None` on 0) |
| `IF([MTM] > 0, [MTM], 0)` | `measure('MTM') if measure('MTM') > 0 else 0` |
| `TOTALYTD(SUM(Trades[PnL]), Trades[TradeDate])` | `ytd(sum(trades.pnl1d), trades.tradeDate)` |
| `CALCULATE([MTM], SAMEPERIODLASTYEAR(Dates[Date]))` | `same_period_last_year(sum(trades.mtm), trades.tradeDate)` |
| `RANKX(ALL(Desks), [MTM])` | `rank(measure('MTM'))` (over the answer's rows) |
| `PERCENTILE.INC(Trades[MTM], 0.5)` | `percentile(trades.mtm, 0.5)` |
| `RELATED(Desks[Name])` (calculated column) | `related(desk.name)` in a dataset's `derive` |
| `CALCULATE([MTM], ALL(Desks[Name]))` | `calc(measure('MTM'), ignore=[desks.name])` *(wave B)* |
| `DIVIDE([MTM], CALCULATE([MTM], ALL(Trades)))` | `share_of(measure('MTM'), over=ALL)` *(wave B)* — of what *you* may see |
| `CALCULATE([MTM], USERELATIONSHIP(...))` | `calc(measure('MTM'), via='r_5')` *(wave B)* |
| `CALCULATE([MTM], ALLEXCEPT(Desks, Desks[Region]))` | `calc(measure('MTM'), keep=[desks.region])` *(wave B)* |

There is no context transition: iteration is explicit (`avg_by`, wave B), so a measure's number never depends on hidden row context.

## 10. The old syntax and the converter

Until 2.2.0, expressions used a Rachana-EL-flavoured syntax. From the end of wave A it is refused (`drishti.bi.expr.legacy: refuse`, the default),
with the DEL v2 text as the fix; a site still converting its own items may set `legacy: warn` to read it with a `DRS-9100` warning meanwhile. An item
that declares `expressions: 2` refuses it whatever the setting. A text that is already DEL v2 but wrong (say `rank(trades.mtm)`) gets its DEL v2 problem,
not `DRS-9100`. A rule written as a YAML boolean (`where: true`) is read as `True`.

| Old syntax | DEL v2 |
|---|---|
| `sum(trades.mtm, trades.status == 'Live')` | `sum(trades.mtm, where=trades.status == 'Live')` |
| `[P&L 1d] / [Exposure (EE peak)]` | `measure('P&L 1d') / measure('Exposure (EE peak)')` |
| `c ? a : b`, `if(c, a, b)` | `a if c else b` |
| `&&`, `\|\|`, `!` | `and`, `or`, `not` |
| `a = b` (equality) | `a == b` |
| `x in ('A', 'B')` | `x in ['A', 'B']` |
| `true`, `false`, `null`; `x == null` | `True`, `False`, `None`; `x is None` |
| `trades.desk in user.desks` | unchanged |
| `startsWith(s, p)`, `length(s)` | `startswith(s, p)`, `len(s)` |
| `rank(sum(x), 'asc')`, `lag(agg, 1, d, trades.desk)` | `rank(sum(x), desc=False)`, `lag(agg, 1, d, by=[trades.desk])` |

**How it converts.** The old parser reads the expression into a tree; a printer writes the tree as DEL v2 (never a text substitution); the DEL v2
parser reads it back, and the converter asserts that both compile to the **same tree, hence the same SQL**. Every shipped item is converted this
way and a test compares the SQL and parameters of every shipped measure before and after.

```bash
drishti.py bi migrate --expressions config/bi                   # what would change, file by file
drishti.py bi migrate --expressions config/bi --write           # rewrite in place: comments, key order and layout kept; sets expressions: 2
drishti.py bi migrate --expressions config/bi --check           # CI: exit 1 while a file still has the old syntax
```

Captured (two files of the 2.1 BI folder):

```
config/bi/datasets/trades-basic.dataset.yaml: would change (1 of 5 expressions)
    measures.Average MTM per trade.expr: [MTM] / [Trades]
      -> measure('MTM') / measure('Trades')
config/bi/datasets/trading-risk.dataset.yaml: would change (4 of 19 expressions)
    measures.Open MTM.expr: sum(trades.mtm, trades.status == 'Live')
      -> sum(trades.mtm, where=trades.status == 'Live')
    security.rows[1].where: true
      -> True
    ...
```

**Stored items convert themselves.** Items saved in workspaces before the old syntax was refused are converted by the server **at start**
(`drishti.bi.migrate.on-start`, [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibimigrate--migrations-of-stored-bi-items)): the draft and the
published text of every item, with the same converter. The text before becomes a saved state of the item's *History* (so *Restore* brings it back), a
published text that changes is published again as the next version (the old version stays in the list), a draft stays a draft, and each change is an
event on the item and an audit record (`bi-expressions-migrated`). An item with an expression the converter cannot convert is **not changed at all**: it
keeps its text, is listed in Admin, BI settings and on its page, and Check shows the expression with its problem. A bi-admin sees what would change, or
runs it again after fixing an item, from the CLI:

```bash
drishti.py bi migrate --expressions --dry-run     # the server's stored items: what would change (nothing is written)
drishti.py bi migrate --expressions               # convert them (bi-admin); exit 1 while an item cannot be converted
```

Captured (a store holding three items saved by 2.1, one of them with a typing slip):

```
dataset 'it-published' (it-published, published): would convert
    draft measures.Live.expr: sum(trades.mtm, trades.status == 'Live')
      -> sum(trades.mtm, where=trades.status == 'Live')
    draft measures.Per.expr: [MTM] / [Live]
      -> measure('MTM') / measure('Live')
    draft security.rows[0].where: true
      -> True
    published measures.Live.expr: sum(trades.mtm, trades.status == 'Live')
      -> sum(trades.mtm, where=trades.status == 'Live')
    ...
dataset 'it-broken' (it-broken, draft): problem
    measures.Per.expr: NOT CONVERTED: DRS-9127 expected ')' to close the call of sum() but the expression ends
4 stored item(s) scanned, 2 would be converted, 1 cannot be converted (they keep their text; Admin, BI settings lists them)
```

After the run, the item's *History* reads (newest first; captured): `4 expression(s) converted to DEL v2` (origin `migrate`, the draft), `... the
published text, version 2`, `the published text of version 1 before the migration`, then the person's own saved states; its events: `migrate` by `system`,
"4 expression(s) converted to DEL v2; the text before is saved state 2; published as version 2".

## 11. Recipes

<!-- recipes: the cookbook of finance and risk recipes (each run as a test on the demo lake) -->
