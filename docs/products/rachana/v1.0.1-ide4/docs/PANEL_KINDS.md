<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary. All rights reserved. -->
# Panel kinds

Every box of a Rachana screen is a **panel**, and every panel is one of the kinds below. A Sutra names the kind and says where the data is. This page says, for each kind, what it is for, when to use it and when not, and what every option does; the grammar and the exact option names are the specification ([SPEC.md 2.3](../spec/1/SPEC.md#23-panels)), and a wrong option or value is a problem with a code from [SPEC.md 6](../spec/1/SPEC.md#6-problems-the-error-model) (`RCH-2021` an unknown kind, `RCH-2022` a missing option, `RCH-2023` an option the kind does not take, `RCH-2029` a bad value). How to write a whole Sutra is [WRITING_SUTRAS.md](WRITING_SUTRAS.md).

Core has twenty kinds (`kv`, `table`, `ladder`, `tabs`, `line`, `area`, `hbar`, `metric`, `gauge`, `status`, `markdown`, `surface`, `waterfall`, `histogram`, `scatter`, `candlestick`, `graph`, `timeline`, `pivot`, `map`). The `links` and `provenance` kinds belong to a profile; the dataset kinds `grid`, `tree` and `chart` of language level 1.1 and the kinds `treemap`, `bullet`, `live` and `form` of level 1.2 are in [SPEC.md 2.3](../spec/1/SPEC.md#23-panels).

## Choosing a kind

| Your data looks like | Use | Rather than |
|---|---|---|
| one object with named fields | `kv` | `table` with one row |
| operational states (approved, shipped, settled) | `status` | `kv`: `status` colours each value by meaning |
| the one number a screen is about | `metric` | a strip figure when you want its change, unit and caption too |
| a list of similar rows | `table` | `tabs` when there are more than four |
| a list where one row matters most (the next delivery) | `ladder` | `table`: a ladder highlights rows and never hides any |
| two to four similar objects (regions, tranches) | `tabs` | `table`: tabs show each object's own fields |
| objects that each hold lists of their own (a region's products, each with monthly figures) | `tabs` with `panels:` | one flat table: the legs' tables differ and the nesting is lost |
| points along an axis (months, dates) | `line` | `hbar` when the order of the axis matters |
| several bands over one axis against a limit | `area` | several `line` panels |
| a few labelled amounts | `hbar` | `waterfall` when the amounts add up to something |
| one number against a maximum | `gauge` | `metric` when there is no maximum |
| a grid of numbers over two axes (a correlation matrix) | `surface` | `pivot` when the grid must be aggregated from rows |
| a start value, signed contributions and an end value | `waterfall` | `hbar`, which does not show the running total |
| many numbers whose spread matters | `histogram` | `line`, which shows order, not distribution |
| rows with two measures to compare | `scatter` | two `hbar` panels |
| prices with open, high, low and close by date | `candlestick` | `line` of the close, which hides the day's range |
| dated events | `timeline` | `ladder`, which needs columns and does not sort |
| rows to total by one field across another | `pivot` | `table` with totals, which aggregates one dimension only |
| a note for the reader | `markdown` | a title that says too much |

## Expressions, field names and plain values

Which option values are expressions, which are field names and which are plain text matters: only expressions and templates are compiled when the
Sutra is checked (SPEC 3 lists the places by key).

| Kind | Expressions (Rachana-EL) | Field names (of each row) | Plain values |
|---|---|---|---|
| `kv` | `rows` | | |
| `table` | `rows`, `moreLabel`, `children`, `crumb`, `root`, each level's `rows`, `crumb` and column `bind`; `limit` when it reads a control | | `limit` (integer), `totalLabel`, `search` (`false` hides the filter), `expand`, `empty`, each level's `title` |
| `tabs` | `each`, `tabTitle` | | `layout` |
| `line` | `rows`, `mark` | `x`, `y` | `unit`, `fmt`, `step` (true or false) |
| `area` | `rows`, `limit` | `x`, each series' `value` | `series[].label`, `series[].tone`, `limitLabel`, `unit` |
| `hbar` | `rows` | `label`, `value` | `fmt`, `tone`, `shape` (`bars` or `donut`) |
| `ladder` | `rows`, `highlight`, `children`, `crumb`, `root`, each level's `rows`, `crumb` and column `bind` | | `totalLabel`, `search` (`false` hides the filter), `expand`, `empty`, each level's `title` |
| `markdown` | | | `text` is a template |
| `status` | each field's `bind` | | each field's `label`, `fmt`, `tone` |
| `gauge` | `value`, `max` | | `label`, `fmt` |
| `surface` | `rows`; `y` when it starts with `$` or `@` | `y` otherwise | `fmt`, `unit`, `view` |
| `waterfall` | `rows` | `label`, `value`, `total` | `sum`, `fmt`, `unit`, `colors` (`gain-loss` or `theme`) |
| `histogram` | `rows`, each marker's `value` | `value` | `bins` (1–200), `markers[].label`, `markers[].tone`, `fmt`, `unit` |
| `scatter` | `rows` | `x`, `y`, `size`, `label`, `group` | `fmt`, `xFmt`, `xLabel`, `yLabel` |
| `candlestick` | `rows` | `x`, `open`, `high`, `low`, `close`, `volume` | `fmt`, `unit` |
| `graph` | `nodes`, `edges` | `label`, `group` (of each node) | `layout` (`tree` or `force`) |
| `timeline` | `rows` | `date`, `label`, `detail`, `status` | `tone` |
| `pivot` | `rows` | `by` (one field or a list), `across`, `value` | `agg` (`sum`, `count`, `avg`, `min`, `max`), `heat`, `totals` (`true`/`false`), `expand` (whole number or `all`), `fmt`, `tone` |
| `metric` | `value`, `delta`, `caption` (a template) | | `label`, `fmt`, `tone`, `deltaFmt`, `deltaTone`, `unit` |
| `map` | `highlight`, `region`, `shade.field`, `shade.by` | | `link` (`kind`, `id` template), `shade.kind`, `shade.fmt`, `shade.label`, `fmt`, `unit` |

**Field names.** On `line`, `area` and `hbar` a field name is matched exactly as the document writes it: `x: month`, `label: region`,
`value: revenue`; `Revenue` does not find `revenue`, and an `@` expression there finds nothing (no error, no points). The defaults (`tenor`,
`value`, `label`) suit rows that `group()` makes, whose fields are `label`, `value` and `count`. For the seven kinds from `waterfall` on (and
`timeline`), a field name may also be a dotted path (`customer.name`) or an expression over the row when it starts with `@` or `$`
(`y: "@.revenue / 1000"`).

Values outside the allowed set (`agg: median`, `layout: circle`, `colors: rainbow`, `bins: 0`, `heat: "yes"` (an unquoted `yes` is YAML for
`true`), a marker without `value`) are `RCH-2029`. So are values of the wrong type: `rows`, `each`, `nodes`, `edges` and `source` must be
expressions written as text (`rows: 5` is not), `markdown` `text` text, table `limit` a whole number from 1 (or an expression reading a control),
table and ladder `search` true or false, `kv` and `status` `fields` and `area` `series` lists.

Accepted but currently without effect (they parse, and do nothing yet): `fields` on `kv` (use `columns`), `link` on `table` (use `link: true` on
a column), `footer` on `line`.

When a panel's data does not fit (its `rows` is not a list), the panel is empty (or shows its `empty:` text) and the rest of the view is unaffected.

## Row groups and totals

### Row groups: pivot `by` lists and tree tables

Two opt-in features let rows hold rows; both open one level at a time, and `expand` sets how many levels show at first.

**Row groups of a pivot.** `by` takes one field, or a **list** (at most six) for nested groups:

```yaml
  - id: nested
    kind: pivot
    title: Revenue by region, product and month
    rows: $.sales
    by: [region, product]         # two levels: the region, then its products
    across: month
    value: revenue
    fmt: compact
    tone: sign
    expand: 1                     # only the regions, closed; 2 opens one level; all opens every level
```

You get one row per region with its subtotal, each opening to its products, across the months. Group rows aggregate the underlying
rows (an `avg` subtotal is the average of all its rows). A number, an empty list or a list of non-names as `by` is `RCH-2029`.

**A tree table.** When each row *carries its own children*, say which with `children`, an expression over the row:

```yaml
  - id: lines
    kind: table
    title: Order lines, each opening to its shipments
    rows: $.order.LINES
    children: "@.SHIPMENTS"       # the rows nested under this one, to any depth the host allows
    expand: 1
    columns:
      - { label: Line / shipment, bind: "nonblank(@.LINE_ID, @.SHIP_ID)" }
      - { label: Date, bind: "@.DATE", fmt: date }
      - { label: Quantity, bind: "@.QTY", fmt: amount0 }
```

Child rows are another kind of row, so a column reads whichever field is there with `nonblank`. `total: true` sums only the rows
**without** children. `ladder` takes the same two keys. When each level is a different thing with its own columns, use `levels` (one level at
a time with a breadcrumb: a row opens the rows under it in place of the level, each level with its own `rows`, `title`, `crumb` and `columns`).

A pivot **aggregates** rows by fields you name; a tree table or `levels` **shows** a hierarchy the document already has.

### Totals, limits and "more"

| Option | Kinds | Effect |
|---|---|---|
| column `total: true` | `table`, `ladder` | adds a total row summing that column over all rows (hidden ones included; with `where`, the rows it keeps; in a tree, the rows without children) |
| `totalLabel` | `table`, `ladder` | label of the total row (default `Total`), placed before the first totalled column |
| `limit` | `table` | rows shown; a whole number from 1 (`limit: "6"`, quoted, is `RCH-2029`) or an expression reading a control |
| `moreLabel` | `table` | expression for the line under the table when rows are hidden; default `<N> more` |

## The kinds

Each section gives the use, the options and their meaning. `Required` is as in the SPEC table; a default is what the kind does when the option is left out.

### kv

Labelled values, one per field: the terms of an order, an identifier block, any object with named fields. Each value is formatted (`fmt`) and toned (`tone`).

- **Use it when:** One object with named fields.
- **Not when:** A list of similar objects: that is a `table`. Operational states that should be coloured by meaning: `status`.
- **Key options:** `columns` (each `{label, bind, fmt, tone, link}`), `rows` to read another object, or `fields` (the same list as `status`). Every option: the table under [`kv`](#kv) below.

A grid of label/value pairs, four across in the main column and two across on the right.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `columns` | | | The fields: each `bind` is evaluated against the document, or against `rows` when given. |
| `rows` | | | Expression for one object; inside the columns `@` is that object. |
| `fields` | | | Accepted, no effect (see above); a list when written (`RCH-2029` otherwise). |


### table

A list of similar rows. A total row adds the `total: true` columns over **all** rows (including those beyond `limit`) and a *N more* line counts the rest. With `children` the rows nest (a tree table).

- **Use it when:** Rows you want to read, sort, filter and page: orders, versions, an org tree.
- **Not when:** Fewer than five rows where one matters most: `ladder`. Two to four objects with their own fields: `tabs`. Totals by one field across another: `pivot`.
- **Key options:** `rows` (required), `columns` (inferred when absent), `limit`, `moreLabel`, `totalLabel`, `search`, `children`, `expand`, `pivot`. Every option: the table under [`table`](#table) below.

One row per element of `rows`.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving a list. Anything else shows *No data available*. |
| `columns` | | inferred | One per column, each `{label, bind, fmt, tone, total, link}`. |
| `limit` | | all rows | A whole number, 1 or more: rows shown; or an expression reading a control (`limit: ctl.top`). The rest are counted in the "more" line. `limit: many`, `limit: -5` or `limit: "6"` (quoted) is `RCH-2029`. |
| `moreLabel` | | `"<N> more"` | Expression for the text of the "more" line, evaluated against the document (not a row). |
| `totalLabel` | | `Total` | Text of the total row's label cell. |
| `link` | | | Accepted, no effect (see above). |
| `highlight` | | none | Expression evaluated per row (`@` is the row, `#index` its position): a row for which it is true is drawn highlighted, as in a ladder. |
| `children` | | none | Expression evaluated per row (`@` is the row) giving the rows nested under it, for example `children: "@.SHIPMENTS"`. A row that has children opens and closes in place, indented, to any depth the host allows. Columns evaluate against each child with `@`. `total: true` columns sum the rows that have no children only, so nothing counts twice. A number, a list or a boolean is `RCH-2029`. |
| `expand` | | `1` | Levels shown open at first: `1` shows only the top level, `2` opens those to show their children, `all` opens every level. A whole number from 1 or `all` (`RCH-2029` otherwise). |
| `levels` | | none | A drill-down: the rows each row opens to, level by level, each level with its own `rows` (read with `@` the row above), `title`, `crumb` and `columns`; 1 to 6 levels. Not with `children` or `pivot` (`RCH-2029`). |
| `crumb` | | `'#' + (#index + 1)` | With `levels`: an expression naming a first-level row in the breadcrumb (`"'Shipment ' + @.SHIP_ID"`). |
| `root` | | the panel title | With `levels`: an expression, read against the document, for the first step of the breadcrumb (`"'Order ' + $.ORDER_ID"`). |
| `empty` | | `No data available` | The words shown when there are no rows (the list is missing, null or empty): `empty: No events booked`. |
| `search` | | `true` | `false` hides the filter box in the panel's heading, for a table too small or too fixed to need one. Table and ladder panels take it; on any other kind it is a problem (`RCH-2023`, *option 'search' applies only to panels that show a table*). `true` or `false` only (`search: maybe` is `RCH-2029`). |

Totals: when at least one column says `total: true`, a total row is added. It sums that column's numbers over **all** rows, including
those hidden by `limit` (values that are not numbers count as 0), formats the sum with the column's `fmt` and `tone`, and puts
`totalLabel` in the column just before the first totalled column (or the first column when the first totalled column is the first).

### tabs

One tab for each element of a list, whatever the count: the fields of a body panel (`kind: kv`) evaluated per element, and, with `panels:`, whole panels (a kv, a table, a tree table) read against that element. `layout: columns` puts them side by side instead.

- **Use it when:** Regions, tranches, accounts: a few objects with the same fields; or objects that each carry a list (a region's orders) you want to read inside the object's own tab.
- **Not when:** Dozens of elements: `table`. The `body` and the `panels` take the size of their tabs panel (`span` there is `RCH-2030`).
- **Key options:** `each` (required), `tabTitle` (an expression per element), `layout` (`tabs` or `columns`), the `body`, and `panels` (what each tab holds). Every option: the table under [`tabs`](#tabs) below.

One sub-panel per element of `each`, as tabs or side by side.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `each` | yes | | Expression giving a list. Inside the body, `@` is one element and `#index` its position. |
| `tabTitle` | | `'#' + (#index + 1)` | Expression for each tab's title. |
| `layout` | | `tabs` | `tabs` (one visible at a time) or `columns` (all side by side, for two or three legs). |
| `body` | | | A nested panel: `kind` is required (use `kv`), `id` is optional (`body`); only its `columns` are used. |
| `panels` | | | A list of panels shown inside **each** tab, under the body's fields . |

The body is validated like any panel, so a body of `kind: table` would need `rows` (`RCH-2022`); write it as `kind: kv`. A body without
columns gets the fields of the **first** element, inferred.

### line

Points along an axis: a curve by month, revenue by date. With `source` (a profile's linked-entity read) the points come from another document.

- **Use it when:** Order along an axis matters.
- **Not when:** Several bands against a limit: `area`. Labelled amounts: `hbar`.
- **Key options:** `rows`, `x`, `y`, `source`, `mark`, `fmt`, `unit`, `footer`, `step` (`true`: a value holds until the next point, a notional schedule). For `line`, `area` and `hbar` a field of each row is a bare name.

A line chart: one point per row.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | | `points` | Expression giving the list of points. |
| `x` | | `tenor` | Field of each row for the x axis (shown as text), written exactly as the document writes it. |
| `y` | | `value` | Field of each row for the y value (a number). |
| `mark` | | | Expression (against the main document) giving one x value to highlight; its value is written under the chart: `5Y point 4.03%`. |
| `fmt` | | | Format of the mark's value. |
| `unit` | | | Unit for the axis and the mark text; `%` is appended without a space, anything else after a space. |
| `step` | | `false` | `true` draws the line as steps: a value holds until the next point (a leg's notional schedule, an amortising principal). |
| `values`, `labels` | | | A vector instead of `rows`, `x` and `y`: `values` an expression giving a list of numbers (the y values), `labels` an expression giving a list of texts for the x axis (the position 0, 1, 2 … when left out): `values: $.RATES`, `labels: $src.SCENARIO_DATES`. |
| `footer` | | | Accepted, no effect. |


### area

Several bands over one axis against a limit: a forecast and its range under a ceiling, which is drawn dashed.

- **Use it when:** Profiles over time against a ceiling.
- **Not when:** One curve: `line`.
- **Key options:** `rows` (required), `x`, `series` (a list of `{label, value, tone}`), `limit` (an expression), `limitLabel`, `unit`. Every option: the table under [`area`](#area) below.

An area chart with one or more series over a common x axis, and an optional dashed limit line (exposure profiles).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list of points. |
| `x` | | `tenor` | Field of each row for the x axis. |
| `series` | | none | A list of `{label, value, tone}`: `value` names a field of each row; `tone` colours the series (`link`, `accent`, `pos`, `neg`). Without `series` the chart is empty. Anything but a list (`series: 5`) is `RCH-2029`. |
| `limit` | | | Expression for a number drawn as a dashed horizontal line. |
| `limitLabel` | | `Limit` | Legend text for the limit line. |
| `unit` | | | Unit for the axis. |

A legend is shown when there are two or more series or a limit.

### hbar

Horizontal bars for a few labelled amounts, scaled to the largest absolute value; without a `tone`, negative bars are tinted as losses.

- **Use it when:** revenue by product, units by region.
- **Not when:** Amounts that add up to a total: `waterfall`. Order along an axis: `line`.
- **Key options:** `rows` (required), `label`, `value`, `fmt`, `tone`, `shape` (`bars`, or `donut` for shares of a whole: the total in the middle, the rows with amount and share listed under it). Every option: the table under [`hbar`](#hbar) below.

Horizontal bars, one per row, scaled to the largest absolute value; or, with `shape: donut`, a ring of shares.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. `group(list, 'key', 'value', 'by'?)` makes such a list from any rows. |
| `label` | | `label` | Field of each row for the bar's label, written exactly as the document writes it. |
| `value` | | `value` | Field of each row for the bar's number. Rows whose value is not a finite number are skipped. |
| `fmt` | | plain | Format of the number written beside each bar. |
| `shape` | | `bars` | `donut` draws the same rows as a ring of shares of their total (the total, formatted with `fmt`, in the middle; the rows, their amounts and shares listed under it). Use it for a share of a whole: notional by product type. Negative values count by their size. |
| `tone` | | by sign | Tone of each bar; without it, negative bars are `neg` and the others `pos`. |

### ladder

A table that shows every row and lights the one that matters: a delivery schedule with the next date highlighted. `highlight` is an expression per row (`@`, `#index`).

- **Use it when:** A schedule or sequence in which a position in it is the point.
- **Not when:** A long list to filter and page: `table`. It takes no `limit` or `moreLabel` (`RCH-2023`): a ladder never hides a row.
- **Key options:** `rows` (required), `columns`, `highlight`, `totalLabel`, `search`, `children`, `expand`, `pivot`. Every option: the table under [`ladder`](#ladder) below.

A table whose rows can be highlighted: settlement ladders, lifecycle events, the next payment.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list. |
| `columns` | | inferred | As for `table`. |
| `highlight` | | none | Expression evaluated per row (`@`, `#index` available); rows where it is truthy are highlighted. |
| `totalLabel` | | `Total` | As for `table`; columns with `total: true` are summed. |
| `search` | | `true` | As for `table`. |
| `children` | | none | As for `table`: the rows nested under each row, a tree. |
| `expand` | | `1` | As for `table`. |
| `levels`, `crumb`, `root`, `empty` | | | As for `table`. |

A ladder does not accept `limit` or `moreLabel` (`RCH-2023`): it always shows every row.

### status

Statuses and flags, each coloured by what it says. Drawn like `kv`; the colour always accompanies the text.

- **Use it when:** Order states, approvals and other operational states.
- **Not when:** Plain facts: `kv`. A state over time: `timeline`.
- **Key options:** `fields`, a list of `{label, bind, fmt, tone}`; with `tone: status` text such as *fail*, *reject*, *dispute* or *breach* is bad, *pending*, *unmatched* or *warn* is warn, anything else ok. Every option: the table under [`status`](#status) below.

Operational fields (confirmation, clearing, settlement, booking), drawn like a kv grid.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `fields` | | none | A list of `{label, bind, fmt?, tone?}`. Each `bind` is an expression against the document. |

Always give each field a `label` (a field without one is labelled `null`). `tone: status` colours by meaning: text containing *fail*,
*reject*, *dispute* or *breach* is `bad`; *pending*, *unmatched* or *warn* is `warn`; any other non-empty text is `ok`.

### markdown

A note for the reader. Text may hold `${...}` templates over the document, so it can say things about this entity.

- **Use it when:** Say what a screen is for, or what to do next.
- **Not when:** Data: the other kinds format it for you.
- **Key options:** `text` (required). Every option: the table under [`markdown`](#markdown) below.

Static text for the reader.

| Option | Required | Meaning |
|---|---|---|
| `text` | yes | A template: `${expression}` parts are evaluated against the document. |

The text is shown as plain text: Markdown syntax (`**bold**`, lists) is **not** rendered inside the panel. The `markdown` panel is for the
people who *read* the view; notes for the people who *maintain* the Sutra belong in its top-level `notes` (or a panel's `description`),
which are never shown in the view.

### gauge

One number against a maximum, as a filled bar with its percentage.

- **Use it when:** Use of a limit or a target.
- **Not when:** A figure with no maximum: `metric`.
- **Key options:** `value` (required, an expression), `max` (an expression, default 1), `fmt` (applied to value divided by max, default `pct0`), `label`. Every option: the table below.

One value against a maximum, as a filled bar and a text.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `value` | yes | | Expression for the value. |
| `max` | | `1` | Expression for the maximum. |
| `fmt` | | `pct0` | Format applied to **value ÷ max** (not to the value). |
| `label` | | | Carried in the view data (`Gauge.label`); a painter may not draw it. Put the meaning in the title. |

The bar's width is value ÷ max. When the value is not a number, the text is `—`.

### surface

A grid of values over two axes already in the document: a correlation matrix, a response by two settings. A heatmap, or a 3D view where the painter offers one.

- **Use it when:** A matrix you want to see as a surface.
- **Not when:** Rows to be aggregated into a grid: `pivot`.
- **Key options:** `rows`, `y` (both required), `columns` (one per x point), `fmt`, `unit`, `view` (`heatmap` or `3d`). Every option: the table under [`surface`](#surface) below.

A grid of values over two axes (volatility surfaces, correlation matrices): each row of `rows` is one y point, each column one x point.
Drawn as a heatmap, with a button that turns it into a rotatable 3D surface.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the list of y points. |
| `y` | yes | | The y label of each row: a field name (`EXPIRY`), or an expression when it starts with `@` or `$`. |
| `columns` | | | One per x point: `label` is the x label, `bind` the cell value (non-numbers leave a gap). |
| `fmt` | | | Format of cell values. |
| `unit` | | | Unit shown with values. |
| `view` | | `heatmap` | `heatmap` or `3d` (which view opens first). |

### waterfall

Ordered signed steps as floating bars: opening level, moves, closing level (a bridge from last month to this month). Rises and falls differ by colour **and** by the signed text on each bar; totals are neutral.

- **Use it when:** Amounts that add up to something.
- **Not when:** Independent amounts: `hbar`.
- **Key options:** `rows` (required), `label`, `value`, `total`, `sum` (a closing total bar), `fmt`, `unit`, `colors` (`gain-loss` or `theme`). Every option: the table under [`waterfall`](#waterfall) below.

Ordered steps as floating bars: each step runs from the running total before it to the one after it; a total step (and the closing bar
`sum` adds) is drawn from zero. Rises take the `ok` tone (green), falls `bad` (red), totals `muted` (neutral); `colors: theme` uses
`pos` and `neg` instead (blue and orange in most themes, colour-blind friendly).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the steps, in order. |
| `label` | | `label` | Field with the step's name. |
| `value` | | `value` | Field with the signed amount (for a total, the level). Rows without a number are left out. |
| `total` | | `total` | Field that marks a total step; the running sum restarts at its value. |
| `sum` | | | Label of a closing total bar at the running sum. |
| `fmt`, `unit` | | | Format of the amounts (default: rounded to six significant digits); unit of the axis. |
| `colors` | | `gain-loss` | `gain-loss` (rises green, falls red) or `theme` (the theme's positive and negative colours); anything else is `RCH-2029`. |

### histogram

The distribution of many numbers, binned from the smallest to the largest, with optional marker lines (a mean, a percentile) read from the document.

- **Use it when:** Response times, residuals: spread matters, order does not.
- **Not when:** Order matters: `line`. Two measures per row: `scatter`.
- **Key options:** `rows` (required), `value`, `bins` (1 to 200), `markers` (`{label, value, tone}`), `fmt`, `unit`. Every option: the table under [`histogram`](#histogram) below.

The distribution of a list of numbers in equal-width bins from the smallest to the largest, with marker lines.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving a list of numbers, or of rows. |
| `value` | | | With rows, the field (or `@` expression) holding the number. Non-numbers are counted as dropped. |
| `bins` | | √count, 5 to 40 | Number of bins, 1 to 200. |
| `markers` | | | List of `{ label, value, tone }`; `value` is an expression over the document, `tone` defaults to `accent`. A marker whose value is missing is left out. |
| `fmt`, `unit` | | | Format of bin edges and markers (default: six significant digits); unit of the axis. |

### scatter

Two measures per row as points (price against volume), optionally sized and grouped (coloured).

- **Use it when:** Compare two measures across many entities.
- **Not when:** One measure by name: `hbar`.
- **Key options:** `rows`, `x`, `y` (required), `size`, `label`, `group`, `fmt`, `xFmt`, `xLabel`, `yLabel`. Every option: the table under [`scatter`](#scatter) below.

One point per row with two numbers; rows without both are left out.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the rows. |
| `x`, `y` | yes | | Fields (or `@` expressions) of the two measures. |
| `size` | | | Field that sizes the point (by its absolute value, area-proportional). |
| `label` | | | Field that labels the point. |
| `group` | | | Field whose value colours the point; one legend entry per group, in first-seen order. |
| `fmt`, `xFmt` | | `xFmt` = `fmt` | Formats of y and x in tooltips and the data table (default: six significant digits). |
| `xLabel`, `yLabel` | | the field names | Axis titles. |

### candlestick

Open, high, low and close by date, with optional volume bars.

- **Use it when:** Price history where each day's range matters.
- **Not when:** Only the close: `line`.
- **Key options:** `rows` (required), `x`, `open`, `high`, `low`, `close`, `volume`, `fmt`, `unit`. Every option: the table under [`candlestick`](#candlestick) below.

Open, high, low and close by date, oldest first, with volume bars under the prices when the rows carry volume.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the bars. |
| `x` | | `date` | Field with the bar's date. |
| `open`, `high`, `low`, `close` | | those names | Price fields; a bar without all four is left out. The defaults also read `OPEN`, `HIGH`, `LOW`, `CLOSE`. |
| `volume` | | | Volume field; no volume bars without it. |
| `fmt`, `unit` | | | Price format (default: six significant digits) and unit. |

### graph

Entities and the relations between them (an organisation chart, a dependency tree).

- **Use it when:** Structure: who owns whom, which set holds which item.
- **Not when:** A plain list of references: `links`.
- **Key options:** `nodes` (required), `edges`, `label`, `group`, `layout` (`tree` or `force`). Every option: the table under [`graph`](#graph) below.

Nodes and the edges between them. Each node needs an `id`. Nodes and edges read `id`, `kind`, `from`, `to` and `label` in lower case or UPPER_SNAKE.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `nodes` | yes | | Expression giving the nodes (`id`, optional `kind`, and the fields below). |
| `edges` | | | Expression giving the edges: `from`, `to` (node ids), optional `label`. Edges to unknown nodes are left out. |
| `label` | | `label` | Node field shown under the node. |
| `group` | | `type` | Node field that colours it (and the legend). |
| `layout` | | `tree` | `tree` (levels from the nodes nothing points to) or `force`. |

### timeline

Dated events in order, each with a status tone and a short description, sorted by date.

- **Use it when:** A lifecycle: ordered, shipped, delivered.
- **Not when:** A schedule with a current row: `ladder`.
- **Key options:** `rows` (required), `date`, `label`, `detail`, `status`, `tone`. Every option: the table under [`timeline`](#timeline) below.

Dated events, sorted by date (ISO dates and timestamps sort as written), drawn as a vertical list.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the events. |
| `date` | | `date` | Field with the date or timestamp. |
| `label` | | `event` | Field (or `@` expression) with the event's name. |
| `detail` | | `description` | Field (or `@` expression) with a short note. |
| `status` | | `status` | Field with the status shown beside the event. |
| `tone` | | `status` | Tone applied to the status (see [SPEC 2.6](../spec/1/SPEC.md#26-formats-and-tones)). |

### pivot

A two-dimensional aggregate of the rows: one row group per `by` field value (a list gives nested groups with subtotals), one column per `across` value, totals, and an optional heat scale. The `pivot:` option of a `table` (a host's pivot screen) is a profile feature, not this kind.

- **Use it when:** Totals by one field across another: revenue by region and month.
- **Not when:** Rows you want to read one by one: `table`.
- **Key options:** `rows`, `by`, `across` (all required), `value`, `agg` (`sum`, `count`, `avg`, `min`, `max`), `fmt`, `tone`, `heat`, `totals`, `expand`. Every option: the table under [`pivot`](#pivot) below.

The rows aggregated by one field (down the side) across another (along the top), with row and column totals. Totals are aggregates of
the underlying rows (an `avg` total is the average of all its rows, not of the cells).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `rows` | yes | | Expression giving the rows. |
| `by` | yes | | Field whose values are the row keys (first-seen order), or a **list of fields** (`by: [BOOK, FAMILY]`, at most 6) for nested groups: each group row shows the subtotal of the rows in it and a ▸/▾ toggle opens it. A field may be written as a Rachana path or expression over the row (`"@.BOOK"`, `"upper(@.DESK)"`; quote it in YAML); a plain path is headed by its field (`BOOK`). A string keeps working. A number, an empty list or a list of non-names is `RCH-2029`. |
| `across` | yes | | Field (or a Rachana path over the row, `"@.CURRENCY"`) whose values are the column keys (first-seen order). |
| `value` | | | Field aggregated; without it the pivot counts rows. Rows whose value is not a number are left out. |
| `agg` | | `sum` | `sum`, `count`, `avg`, `min` or `max` (with `value` a field). |
| `fmt`, `tone` | | | Format (default: six significant digits) and tone of every cell (counts are shown as whole numbers). |
| `heat` | | `false` | Shade each cell by its value, from the smallest to the largest. |
| `totals` | | `true` | `false` hides the total column and row (and the subtotal rows of nested groups). |
| `expand` | | `1` | With a list `by`: levels shown open at first (`1`: only the outermost groups, closed; `2`: those opened one level; `all`: every level). A whole number from 1 or `all`. |

### metric

One big headline figure, a KPI tile: the value formatted and toned, with an optional small change beside it, a unit and a caption under it.

- **Use it when:** The one number a screen is about: revenue, units, target attainment. Several tiles with `span: 3` make a row of headline figures.
- **Not when:** One number against a maximum: `gauge`. Many labelled values: `kv`. A figure whose history matters: `line`.
- **Key options:** `value` (required), `label`, `fmt`, `tone`, `delta` (an expression: the change), `deltaFmt`, `deltaTone` (default `sign`), `unit`, `caption` (may hold `${...}`). Every option: the table under [`metric`](#metric) below.

A big-number KPI tile: one large, formatted and toned figure, with an optional change beside it, a unit and a caption. Use several in a
row (`span: 3` or `span: 4` each) for headline numbers; for one number against a maximum use [`gauge`](#gauge).

| Option | Required | Default | Meaning |
|---|---|---|---|
| `value` | yes | | Expression for the figure. A missing value shows a dash, never an error; a masked one shows the mask. |
| `label` | | | Plain text shown with the figure (what it is). |
| `fmt`, `tone` | | | Format and tone of the figure (see [SPEC 2.6](../spec/1/SPEC.md#26-formats-and-tones)). |
| `delta` | | | Expression for the change. Without it, or when it is empty, no change is drawn. |
| `deltaFmt` | | the format of `value` | How the change is formatted. |
| `deltaTone` | | `sign` | Tone of the change: `sign` (positive and negative colours), `status`, or a fixed tone such as `neg`, `warn` or `accent`. |
| `unit` | | | Short text shown small beside the figure (`USD`, `bp`). |
| `caption` | | | Small text under the figure; a template, so it may hold `${...}` parts. |

`value`, `delta` and every other option are one value written as text, never a list or a mapping (`RCH-2029`); `source` works as for
any panel.

### map

The world with a country highlighted, or, with `region`, one country's regions.

- **Use it when:** A record belongs to a country and the reader should see where: an office, a market.
- **Not when:** Points at latitude and longitude, or a map of streets: not drawn (there are no map tiles).
- **Key options:** `highlight` and `region` (expressions); `link` and `shade` read the entities of a kind the host serves (a profile's forms); `fmt`, `unit`.

| Option | Required | Default | Meaning |
|---|---|---|---|
| `highlight` | | | Expression for the country to highlight: an ISO 3166-1 alpha-3 or alpha-2 code (`AUS`, `AU`) or the English name. In `region` mode, an ISO 3166-2 region code (`AU-NSW`). A masked value highlights nothing. |
| `region` | | | Expression for a country (same forms as `highlight`). The map then shows that country's regions, with ISO 3166-2 codes, instead of the world. A document with no such country makes the panel empty. |
| `link` | | | `{ kind, id }` with a template holding `{iso3}`, `{iso2}` or (regions) `{code}`: a click on a country opens that entity. A country with no such entity is not clickable. |
| `shade` | | | `{ kind, field, by, fmt, label }`: colour each country by `field` of the entity of `kind` that `by` names, with a legend. A masked field shades nothing. |
| `fmt`, `unit` | | | Format of the shade's figures when `shade` has none, and a unit shown in the legend and tooltip. |

`highlight`, `region`, `shade.field` and `shade.by` are expressions compiled when the Sutra loads (`RCH-2101`); a `link` or `shade` that is
not a mapping of the shape above, a link template with no code, and a `highlight` that is a list are `RCH-2029`.

## How a panel behaves

These hold for every kind; SPEC 4 ("Panel states") is the normative text.

- **Empty is not an error.** Real documents are incomplete. A panel whose data is missing or of the wrong shape is flagged `empty`; a list that is not a list, a row without the field asked for, and a value that is not a number are left out quietly.
- **A broken panel stays one panel.** When binding a panel fails, that panel carries an `error`; the other panels and the view are unaffected. A bad expression is caught when the Sutra is checked (`RCH-2101`).
- **Masked.** A value the viewer may not see is masked, never toned or linked, and a masked number is never added up, charted or used for a total.
- **Every kind takes** the keys of [WRITING_SUTRAS.md](WRITING_SUTRAS.md) and SPEC 2.3 (`id`, `kind`, `title`, `description`, `key`, `code`, `area`, `order`, `section`, `span`, `height`, `collapsible`, `phone`, `refresh`, `when`, `where`, `on-select`, `source`, `emits`, `waits`, `children`, `columns`, `form`, `infer`); `where` is for the kinds with `rows` except `kv`, `on-select` for `table`, `ladder` and `hbar`.
