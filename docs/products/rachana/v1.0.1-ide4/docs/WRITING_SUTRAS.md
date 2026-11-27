<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary. All rights reserved. -->
# Writing Sutras

Everything about writing the screen itself: the language, the libraries of fragments you assemble screens from, and Studio, the visual editor.
The normative text of every rule is the specification ([SPEC.md](../spec/1/SPEC.md)); how to run, serve and embed what you wrote is [IN_YOUR_APP.md](IN_YOUR_APP.md).

## The Rachana language (level 1.2)

A **Sutra** is one screen written as a short YAML file (`*.sutra.yaml`). It says what the screen shows and where each value comes
from; it holds no code and no pixels. This guide walks through the language at level 1.2. The normative text is the
specification, [SPEC.md](../spec/1/SPEC.md); each section below names the part of it that is the one home of the details.

| One home | For |
|---|---|
| [SPEC.md](../spec/1/SPEC.md) | every key, rule and problem code |
| [functions.yaml](../spec/1/functions.yaml) | every Core function: signature, types, null and error behaviour, examples |
| [formats.yaml](../spec/1/formats.yaml) | the format names (`amount0`, `pct2`, `date`, ...) |
| [rachana.lang.json](../spec/1/rachana.lang.json), [sutra.schema.json](../spec/1/sutra.schema.json) | the generated language definition and JSON Schema (editors and AI tools read these) |
| [rachana-el.ebnf](../spec/1/rachana-el.ebnf) | the expression grammar |
| [TRANSLATION.md](../spec/1/SPEC.md#translation-sutra--data--viewmodel-rachana-1), [VIEWMODEL.md](../spec/1/SPEC.md#the-viewmodel-contract-1x) | what a Sutra compiles to |

### 1. A Sutra

```yaml
rachana: 1.2                       # the language level this file is written at
sutra: sales-overview              # its id
version: 1
title: { id: "'Sales overview'" }
data:
  sales: { table: sales, key: [region, product, month] }
panels:
  - { id: revenue, kind: metric, label: Revenue, value: "sum($.sales, 'revenue')", fmt: amount0, span: 3 }
  - { id: by-region, kind: hbar, title: Revenue by region, rows: "group($.sales, 'region', 'revenue')",
      label: label, value: value, fmt: amount0, span: 9 }
```

The file is from [examples/static-sales](../examples/static-sales/overview.sutra.yaml), trimmed. Every Sutra has the same parts:
the `rachana:` level, an id, a `title`, optional `data:` entries, optional `controls`, and `panels` laid out on a 12-column grid
(`span`). SPEC 2.1 and 2.2 list every top-level key.

#### The language level

The `rachana:` key is the one place a file's level is written: `rachana: 1` (or `1.0`) is level 1.0, `rachana: 1.2` is 1.2. Every
tool (lint, build, preview, Studio, `<rachana-view>`, the server) reads each file at the level it declares, never above the tool's own:
a level above the tool's is `RCH-2008`, an unknown minor `RCH-2009`. No command takes a level flag. `rachana new` writes the newest
level; a Sutra is read at the level it declares and nothing rewrites it; Rachana has no migrator. Within major 1 a later level only adds
(SPEC 7.2), so a 1.0 file reads the same on a 1.2 host.

### 2. Panels

A panel is one tile of the screen: `{id, kind, ...options, span}`. The kinds are `metric`, `gauge`, `kv`, `status`, `markdown`, `table`,
`pivot`, `ladder`, `hbar`, `line`, `area`, `scatter`, `histogram`, `waterfall`, `candlestick`, `timeline`, `surface`, `graph`, `map`
and `tabs` (the generated list with the options each takes: `panelKinds` and `panelOptions` in
[rachana.lang.json](../spec/1/rachana.lang.json)). A `table` takes `rows` and `columns` (`{label, bind, fmt, total}`); a chart takes
`rows` and the fields it plots. SPEC 2.3 has the rules; [PANEL_KINDS.md](PANEL_KINDS.md) says for each kind when to use it and what each option does; TRANSLATION.md shows what each kind becomes in the ViewModel.

Values are written in four ways, fixed per key (SPEC 3): an **expression** (`value: "sum($.sales, 'revenue')"`), a **template**
(`caption: "${size($.sales)} sales rows"`), a **name** (`fmt: amount0`, `tone: sign`) or plain **text** (`label: Revenue`).

### 3. Data

`data:` names the inputs a screen reads, each read in expressions as `$.<name>` (SPEC 2.4):

| Entry | Meaning |
|---|---|
| `{table: sales, key: [...]}` | a table the host serves over the [Data Protocol](../spec/1/PROTOCOL.md) (in static mode a file under `data/`) |
| `{url: ...}` | a file or endpoint read whole (JSON, CSV, NDJSON, Arrow), at an address the host allows |
| `{rows: [...]}` | rows written in the file |
| `{record: {...}}` | one record written in the file |

Common keys: `key`, `fields`, `where` (pushed to the host as a filter tree), `limit`, `refresh`, `live: true` (follow the table's
changes) and `mode: server` (paged from the host, for large and [live](IN_YOUR_APP.md#live-tables-editing-and-approvals) tables). A table the host does not have fails the panels that read it with `RCH-2070`; it is never read as
no rows.

### 4. Expressions (Rachana-EL)

Rachana-EL is a small, side-effect-free expression language (SPEC 4; grammar in `rachana-el.ebnf`):

- **Paths and filters**: `$.sales`, `$.sales[?@.month == '2026-06-01']`, `@.revenue` (the current row), `^` (its parent),
  `#index` (its place).
- **Operators**: arithmetic, comparison, `and`/`or`/`not`, `?:`, `??`, `in`, the pipe `a |> f(b)`.
- **Numbers** (SPEC 4.16): integers and decimals are exact (decimal128, 34 digits, half to even); `avg` over decimals is the exact
  sum divided; a double among the operands gives a double.
- **Functions**: 200+ Core functions in logic, math, text, dates, lists, aggregates, statistics and finance on given flows
  (SPEC 5, the catalogue in `functions.yaml`). Lambdas: `map($.rows, lambda r: r.qty * r.price)`.
- **Controls** are read as `ctl.<id>`; another panel's event as `@<panel>.<event>.<field>`.
- **Checked mode** (SPEC 4.14) types every expression at compile time, so a misspelt field or a wrong argument is a problem in
  `rachana lint`, not a blank tile.

### 5. Declared names: `let` and `functions`

```yaml
let:
  total: "sum($.sales, 'revenue')"
functions:
  share: { params: { part: number, whole: number }, is: "whole == 0 ? 0 : part / whole", doc: Part of a whole }
panels:
  - { id: north, kind: metric, label: North share, value: "share(sum($.sales[?@.region == 'North'], 'revenue'), total)", fmt: pct0 }
```

A `let` is a value computed once per bind; a function body reads only its parameters and other functions. Both exist at compile
time only (SPEC 2.7). A library may publish functions, called as `<library>.<name>(...)`.

### 6. State, events and effects

A screen may hold **state** (`state: {desk: {type: text}}`, read as `state.desk`) and change it with **effects** (SPEC 2.8):

```yaml
state:
  region: { type: text }
panels:
  - id: by-region
    kind: hbar
    rows: "group($.sales, 'region', 'revenue')"
    label: label
    value: value
    on:
      bar-click: [ { set: { state: region, to: "@.label" } } ]
  - { id: detail, kind: table, rows: "$.sales[?@.region == state.region]", columns: [ { label: Product, bind: "@.product" } ] }
```

Triggers are `row-click`, `bar-click` and `point-click`; effects are `set`, `toggle`, `clear`, `notify` and `host` (a host action
handed to the page, never run by Rachana), each with an optional `when:`. Panels also take header `actions:` (buttons with
`confirm` and `requires`), controls take `on: {change: [...]}`, and a `kind: form` panel runs its `submit` effects only after its own
checks pass.

### 7. Fragments, and fragments as functions

A **fragment** is a reusable group of panels, used in a Sutra's `panels` as `{use: <library>/<name>@<major>, with: {...}}`. A
**typed fragment** (level 1.2) declares each parameter with a type, and behaves like a function: it reads only its arguments, never
the caller's screen (`RCH-2117`), and returns events and outputs. The fragment is written once with its own local names; each use
binds the caller's data and column names to them at compile time.

The fragment `top-rows` of a small library `shop` (`rachana_libs/shop/fragments/top-rows.v1.fragment.yaml`; the whole library is
built in the tutorial [Build and use a library](TUTORIAL.md#part-2-build-and-use-a-library)):

```yaml
rachana: 1.2
fragment: top-rows
version: 1
doc:
  summary: The largest rows of a table by one column.
  category: tables
params:
  rows:  { type: table, required: true, doc: The rows, columns: { name: { type: text, doc: "The row's name" }, value: { type: number, doc: The figure } } }
  limit: { type: integer, default: 5, min: 1, max: 50, doc: How many rows }
  fmt:   { type: format, default: amount0, doc: How the figure is written }
emits:
  - { name: pick, payload: { name: text }, doc: A row was chosen }
panels:
  - id: list
    kind: table
    rows: "take(sort(arg.rows, @.value, true), arg.limit)"
    columns: [ { label: Name, bind: "@.name" }, { label: Value, bind: "@.value", fmt: "%{fmt}" } ]
    emits: [ { name: pick, on: row-click, payload: { name: "@.name" } } ]
```

Used from a Sutra whose table names its columns differently:

```yaml
rachana: 1.2
sutra: best-products
version: 1
libraries: [shop@1]
title: { id: "'Best products'" }
data:
  sales: { table: sales, key: [region, product, month] }
panels:
  - use: shop/top-rows@1
    as: best
    with:
      rows: { from: $.sales, columns: { name: product, value: revenue } }   # local column: caller column
      limit: 8
  - { id: picked, kind: markdown, text: "Picked: ${@best.pick.name}" }
```

What the compiler does at the use (SPEC 2.5, "Typed fragments" and "Fragments are functions"):

- **Parameters** are values (`text`, `number`, `integer`, `decimal`, `boolean`, `date`, `datetime`, `money`, `list<T>`,
  `record{...}`, `fn(T) -> U` written as a lambda), **tables** (`type: table` with `columns:` or a published `contract:`) and
  **names** (`format`, `tone`, `id`, `enum`, ...) placed as `%{name}`. Each is `required`, has a `default`, or is `optional`.
- **Column mapping**: `rows: $.t` binds a table whose columns already have the local names; `rows: {from: $.t, columns: {...}}`
  renames local columns to the caller's in every `bind`, field-name option, `@.<column>` and column parameter that reads the table.
  A column the mapping cannot reach is `RCH-2121`, never a silent read; a missing required column is `RCH-2115`, a wrong type
  `RCH-2116`.
- **Scope**: `as: best` prefixes every id the fragment brings (`best-list`). Events come back as `@best.pick.name`; `on:` at the
  use turns them into state writes or control settings.
- **Private state and outputs**: a fragment's own `state:` is private to each use; `outputs:` names the parts the screen may read
  as `state.<as>.<name>`. Nested uses are scoped twice (`a-x-<id>`), so two uses never collide.
- **Slots**: `slots: {name: {accepts: panels, max}}` lets the caller place its own panels inside the fragment.

Fragments come from [libraries](WRITING_SUTRAS.md#libraries-and-rachana-lib); a host may also resolve unqualified `name@N` fragments of its own. A text-form fragment (untyped, `%{param}` as
text) is still read; a file is wholly one form or the other.

### 8. Problems

Every mistake is a **problem** with a code (`RCH-xxxx`), a message, a severity and a source location (SPEC 6), all reported at once.
`rachana lint` prints them as text, JSON or SARIF; Studio shows them as you type. Run-time states (no rows, a failed table) are shown
on the panel and are not problems (SPEC 6.4).

### 9. Layout: placing panels

Without `layout:` panels flow: `area: main` panels (the default) down the wide column, `area: right` panels down the narrow one, `span`
(1 to 12) sizing each on a 12-column grid and `height` (1 to 24 grid rows) giving a fixed height. Sizes are grid units, never pixels.
`layout:` places panels instead (SPEC 2.2; every layout problem is `RCH-2059`).

```yaml
layout:
  areas:                                   # a picture of the screen: one string a row, one area name a cell, . an empty cell
    - "head head"
    - "table detail"
  columns: [7, 5]                          # grid columns of each picture column, summing to 12 (default: equal)
  split: { between: [table, detail], resizable: true, remember: user }   # a divider the reader drags; kept per reader
  tabs: [detail]                           # the panels of an area shown as tabs (two or more)
  sticky: head                             # an area (or a panel) that stays at the top while the page scrolls
  user-can-arrange: true                   # readers may drag panels to another place or area and resize them, for themselves
  phone: [head, detail, table]             # the stacking order on a phone: areas or panel ids
  tablet: { areas: ["head head", "table table", "detail detail"] }      # optional: the picture at tablet width
panels:
  - { id: revenue, kind: metric, label: Revenue, value: "sum($.sales, 'revenue')", fmt: amount0, area: head, span: 4 }
  - { id: table, kind: table, rows: $.sales, area: table, columns: [ { label: Region, bind: "@.region" }, { label: Revenue, bind: "@.revenue", fmt: amount0 } ] }
  - { id: office, kind: kv, area: detail, rows: "first($.regions)", columns: [ { label: Office, bind: "@.office" } ] }
  - { id: staff, kind: kv, area: detail, collapsible: true, rows: "first($.regions)", columns: [ { label: Staff, bind: "@.staff" } ] }
```

A screen without areas can keep the flow and add **sections** that fold open and shut (each reader's choice is kept):

```yaml
layout:
  sections:
    - { id: headline, title: Headline }
    - { id: detail, title: Detail, collapsed: true }
panels:
  - { id: revenue, kind: metric, label: Revenue, value: "sum($.sales, 'revenue')", fmt: amount0, section: headline, span: 6 }
  - { id: units, kind: metric, label: Units, value: "sum($.sales, 'units')", fmt: amount0, section: detail, span: 6 }
  - { id: table, kind: table, rows: $.sales, columns: [ { label: Region, bind: "@.region" } ] }    # no section: above the sections
```

| Key | Meaning | Checked (`RCH-2059`) |
|---|---|---|
| `areas` | The picture. Every area is a rectangle. | every row has the same number of cells (at most 12); no area is an L-shape or cut in two |
| `columns` | `12` (the flow grid), or one width a picture column | as many widths as columns, summing to 12 |
| `split` | `{ between: [left, right], resizable: true, remember: user \| none }` | two different areas, side by side, left then right, in some row |
| `tabs` | Areas whose panels show as tabs, one tab a panel | the area exists and holds two or more panels |
| `sections` | `{ id, title, collapsed }` in order; panels name theirs with `section:` | ids unique; not together with `areas` (a screen with areas uses `tabs` or `collapsible: true`) |
| `sticky` | An area or panel id | it exists |
| `user-can-arrange` | `true` or `false` | |
| `phone` | Areas or panel ids, in stacking order | each exists |
| `tablet` | `{ areas, columns }` | the same areas as `areas` |

A panel names its place with `area:` (an area of the picture, or `main` and `right`, which on a screen with areas mean its first and its
last), `order:` (0 to 999, lower first within the area or flow), `section:` and `collapsible: true`. Height stays `height`;
`rows` is always the rows a table reads. On a phone every panel is full width in the phone order (else the picture read left to right,
top to bottom); below the tablet width, without a tablet picture, the areas stack. What a reader changes (the split, what is folded,
panel moves and widths) is the reader's alone: the host keeps it per reader and Sutra, never in the Sutra file. A screen with `layout:`
does not offer a free layout mode: the grid replaces it.

#### Phone tiers

The `phone:` key of a panel (and the screen's `phone:` block) says what a phone gets (SPEC 2.2, 2.3):

| Tier | On a phone |
|---|---|
| `must` | in full, at the top in the phone order |
| `glance` | a compact form, with a way to show all |
| `more` | a row under a "more on this page" heading, loaded when tapped |
| `desk` | named under "on desktop" and never loaded on a phone: wide pivot grids, multi-chart dashboards |

```yaml
phone:                                     # the screen's block (optional)
  order: [revenue, table]                  # the must and glance panels in phone order
  default: more                            # the tier of panels the Sutra gives none
  summary: "Revenue ${fmt(sum($.sales, 'revenue'), 'amount0')}"   # one line at the top of the phone page
panels:
  - { id: revenue, kind: metric, label: Revenue, value: "sum($.sales, 'revenue')", phone: must }
  - { id: table, kind: table, rows: $.sales, phone: { tier: glance, rows: 5, columns: [Region, Revenue], sort: -Revenue } }
  - { id: notes, kind: markdown, text: Notes, phone: desk }
```

`phone:` on a panel is a tier or `{ tier, ...compact options }`: `rows` (1 to 50) on `table`, `ladder`, `hbar`, `timeline`, `map` and
`waterfall`; `columns` and `sort` (a column label; `-` first for largest first) on `table` and `ladder`; `series` on `line`, `area` and
`candlestick` (drawn as a sparkline); `figures` on `kv` and `status`; `tab` on `tabs`. When nothing is set, `kv`, `status`, `metric` and
`gauge` are `must`; tables, charts, bars, timelines, maps and tabs `glance`; `markdown`, `surface` and `graph` `more`; `pivot` `desk`. A page
always has a `must` panel: with none, its first panel is. Masks and roles are unchanged: a tier never shows what the viewer may not see.

### 10. Controls: letting the reader choose

A Sutra may declare up to 12 **controls**: dropdowns, date ranges, a slider, a search box. The reader sets them on the screen; panels
read the values as `ctl.<id>` in expressions, so one Sutra shows one region or one quarter of the same table. Controls only choose:
they never write data (the keys and codes `RCH-2034` to `RCH-2039` are in SPEC 2.5).

```yaml
controls:
  - { id: region, kind: dropdown, label: Region, options: "$.regions[*].name", default: first, placement: header, panel: detail }
  - { id: product, kind: multi-select, label: Product, options: "$.sales[*].product", default: all, placement: header, panel: detail }
  - { id: minUnits, kind: slider, label: Units at least, min: 0, max: "max($.sales[*].units)", step: 10, placement: header, panel: detail }
panels:
  - id: detail
    kind: table
    title: "Sales${ctl.region ? ' · ' + ctl.region : ''}"
    rows: $.sales
    where: "@.region == ctl.region && oneOf(@.product, ctl.product) && (ctl.minUnits == '' || @.units >= ctl.minUnits)"
    columns:
      - { label: Product, bind: "@.product" }
      - { label: Units, bind: "@.units", fmt: amount0 }
```

**The keys of a control**

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | The name read as `ctl.<id>`: a letter, then letters, digits and underscores, at most 32; unique. `ctl` itself is reserved |
| `kind` | yes | `dropdown`, `multi-select`, `cascading`, `toggle`, `segmented`, `date`, `date-range`, `number-range`, `slider`, `top-n` or `search` |
| `label` | | The text beside the control (the id when absent) |
| `options` | for `dropdown`, `multi-select`, `cascading`, `toggle`, `segmented`, `top-n` | A list of values (`[Pay, Receive]`, or `{ value, label }` items), **or** an expression over the data (`"$.regions[*].name"`; a list of records offers their `id`). Kinds that take no options refuse them |
| `default` | | The value to start from: a value, `first`, `all` (a multi-select), or for dates a relative token (below). It must fit the kind. A `top-n`, `toggle` and `segmented` control always has a value: its first option when no default is given |
| `remember` | | `user` keeps the reader's last choice; `none` (the default) does not |
| `required` | | `true`: the reader cannot clear it (a dropdown has no *(all)* entry) |
| `placement` | | `strip` (default: under the title, with the strip), `header` (in a panel's header; needs `panel: <panel id>`) or `side` (a bar at the side of the page) |
| `parent` | for `cascading` | The control whose value the options depend on; it must be declared **before** this one |
| `min`, `max` | for `slider` | A `number-range`'s or `slider`'s bounds: a number or an expression over the data (`"max($.sales[*].units)"`). A slider without both is a problem; bounds that cannot be read (a masked field, a bad expression) leave the slider without a scale |
| `step` | | A `slider`'s step: a number above zero or an expression (default `1`). Values snap to `min + n x step`; the top of the scale is always reachable |
| `range` | | `true`: a `slider` with two handles whose value is `from..to`. Only for sliders; a `default` must match |

An unknown key is `RCH-2011`.

**The kinds, and what `ctl.<id>` holds**

| Kind | Drawn as | `ctl.<id>` |
|---|---|---|
| `dropdown` | a select with an *(all)* entry (unless `required`) | the chosen value as text; empty when none |
| `multi-select` | a button over a checklist with *All* and *None* | a **list** of the chosen values; empty when none, which `oneOf` treats as everything |
| `cascading` | a select whose options follow its `parent` | as a dropdown; its `options` read `ctl.<parent>`: `"$.cities[?(@.region == ctl.region)][*].name"` |
| `toggle` | a switch for two options, buttons for more | the chosen option |
| `segmented` | joined buttons | the chosen option |
| `date` | a date input | an ISO date as text, or empty |
| `date-range` | two date inputs and a presets list | `from..to` as text (either end may be empty), empty when none |
| `number-range` | two number inputs | `min..max` as text |
| `slider` | a track with one handle, or two with `range: true` | one handle: a **number**, so `@.units >= ctl.minUnits` compares as numbers (empty when the scale has no bounds); two handles: `from..to` as text, for `within(@.units, ctl.band)` |
| `top-n` | a select of the options | a whole number; a table's `limit: ctl.top` shows that many rows |
| `search` | a search box (applied when the reader pauses) | the text |

The slider's value is held to `min`..`max`, snapped to the step and, for two handles, ordered; a value that is not a number falls back to
the `default`, then to `min`, so the screen always carries a value the slider can show.

**Relative dates.** A `date` or `date-range` default may be a token read on the host's business date: `today`, `yesterday`,
`previous-business-day`, `this-week`, `this-month`, `last-month`, `next-month`, `this-quarter`, `this-year`, `ytd`, `mtd`, `next-90d`,
`last-30d` (calendar days; the first day counted is the date itself) and `next-5bd`, `last-3bd` (business days on the host's calendar).
A `date-range` always **holds the resolved range** (`next-90d` becomes `2026-10-16..2027-01-13` on 2026-10-16), so a link reproduces what was seen.

**Reading a control.** `ctl.<id>` is an expression like any other, wherever a panel reads data: its `rows`, `where`, `limit`, `title`
(as `${ctl.id}`), a column or strip `bind`, a control's own `options`, `min` and `max`. A reference to a control the Sutra does not
declare is `RCH-2036`. Three panel options exist for controls:

| Option | On | Meaning |
|---|---|---|
| `where` | every kind with `rows` except `kv` | A condition on each row, with `@` the row and `#index` its place in the *data*: only rows for which it is true are shown, drawn and added up (`total: true` sums what is shown). A row whose condition fails or is masked is left out. Not text: `RCH-2029` |
| `limit` | `table` | Besides a whole number, an expression: `limit: ctl.top`. Reads as no limit when it is empty |
| `on-select` | `table`, `ladder`, `hbar` | `{ by: <control id>, field: <row field>, filter: [panel ids] }`: a click on a row, or on a bar, sets the control `by` to the row's `field` (default: the control's id) or to the bar's label; clicking the same one again clears it. `by` must be a control and `filter` must list panels of the Sutra (`RCH-2038`) |

Two functions read the values: `within(@.date, ctl.when)` and `oneOf(@.region, ctl.regions)`. For a dropdown with an *(all)* entry write
`(ctl.region == '' || @.region == ctl.region)`. A click on a bar can set a control, so one chart filters a table:

```yaml
controls:
  - { id: region, kind: dropdown, label: Region, options: "$.regions[*].name", placement: header, panel: sales }
panels:
  - { id: by-region, kind: hbar, rows: "group($.sales, 'region', 'revenue')", label: label, value: value,
      on-select: { by: region, field: label, filter: [sales] } }
  - { id: sales, kind: table, rows: $.sales, where: "ctl.region == '' || @.region == ctl.region" }
```

The control's value lives where the host keeps view state (the host reads and writes `ctl.<id>` in the page address, so a link
reproduces the screen; `remember: user` keeps the last choice per reader). Options are read from the data **as the viewer may see it**: a
masked value is never an option, and a `where` or a search over a masked field never matches.

### 11. Forms: letting people edit records

A panel's `form:` lets people edit the records the panel shows, or start new ones, and keeps what they type as **drafts**; a draft that
passes its own checks raises a `submit` action for the host (SPEC 2.5; a form's shape problems are `RCH-2058`). `form.save` (where a
submitted draft goes: an overlay, the record itself, a connector command, with approvals) is the `drishti` profile's, not Core's.

| Key | Required | Type | Meaning |
|---|---|---|---|
| `edit` | one of `edit`, `new` | `beside` \| `cells` | `beside`: a click on a row opens a form beside the table; `cells`: people type into the table's cells |
| `new` | one of `edit`, `new` | `drawer` | **New** (its text: `new-label`) opens a guided drawer over the dimmed page |
| `key` | with `edit` | field id | the field that names a record (the drafts and "Edit <key>" use it) |
| `title` | | text | the form's heading |
| `new-label` | | text | the New button's text (default New) |
| `roles` | | list of roles | who may edit every field without its own `editable-by` |
| `steps` | | list of `{id, label}` | the drawer's steps, in order; a field names its `step` (the first step when it names none) |
| `fields` | yes | list (at most 60) | the fields, below |
| `rules` | | list of `{rule, message, severity, fields}` | `rule` must hold; `message` says what to do, in words; `severity: error` (default) refuses the draft, `warn` is said and kept; `fields` names where the message shows |
| `hints` | | list of `{label, value, of, fmt, step}` | worked out as people type: "label: value of of (n %)" |

A field:

| Key | Type | Meaning |
|---|---|---|
| `id` | lower_snake (required) | read by rules and hints |
| `label` | text | the field's words (the id when absent) |
| `type` | `text` (default), `number`, `date`, `enum`, `bool`, `textarea`, `money`, `datetime`; `lookup` and `entity` read their choices from a host source | a number or money takes `40,000,000`, `40m`, `250k`, `1.5bn`; `datetime` is `2026-10-23T14:05` |
| `bind` | a record field (`region`) | what it edits, and in `cells` the column whose `bind` names it (the id when absent) |
| `required`, `min`, `max`, `pattern`, `max-length` | | `min`/`max` a number (number, money), a date or `today` (date), a date and time or `today` (datetime); `pattern` a regular expression the whole text matches |
| `currency` | a code (`EUR`) or a field id | `money` only (required): the currency, fixed, or the one another field of the form holds (`currency: currency`) |
| `tz` | a time zone (`Europe/London`) | `datetime` only: the zone it is entered in and shown with (UTC when absent) |
| `default` | a value, or `=<expression>` | what a new record starts with; `=revenue * 0.1` is worked out from the other fields as people type, until they type over it |
| `options` | list | `enum`: values or `{value, label}` |
| `editable-by` | list of roles | only these roles may change it; anyone else sees it locked |
| `readonly`, `reason` | | shown, never changed once the record exists ("read-only: <reason>"); the New drawer may set it |
| `step`, `placeholder`, `fmt`, `new` | | the drawer step, a hint in the empty input, the display format, `new: false` to leave it out of New |

**A form beside the table**: a click on a row opens its fields next to it. Locked fields say why; a field masked for the viewer is never
shown as an input nor sent. Messages are in words, under the field: an error stops the draft, a warning is said and kept with it.

```yaml
panels:
  - id: targets
    kind: table
    rows: $.targets
    columns:
      - { label: Region, bind: "@.region" }
      - { label: Month, bind: "@.month", fmt: date }
      - { label: Target, bind: "@.target", fmt: amount0 }
    form:
      title: Edit target
      key: region
      edit: beside
      fields:
        - { id: region, label: Region, bind: region, readonly: true, reason: "set by the sales plan", new: false }
        - { id: target, label: Target, bind: target, type: money, currency: EUR, min: 0, required: true, fmt: amount0 }
        - { id: reviewed, label: Reviewed on, bind: reviewed, type: date, min: today }
        - { id: comment, label: Comment, bind: comment, type: textarea, max-length: 500, placeholder: Reason for the change }
      rules:
        - { rule: "target <= 1000000", severity: warn, fields: [target], message: "Above one million: a second reader checks it." }
        - { rule: "not (target > 1000000 and empty(comment))", fields: [comment], message: "Say why the target is above one million." }
```

**Editing in the cells**: `edit: cells` makes the table's cells the inputs; a field edits the column whose `bind` names the same record field.
Enter (or a click) opens a cell and keeps the value in the draft, Esc undoes it, Tab moves to the next editable cell. A changed cell is
shown with its old value; a bad one carries the reason under its row.

```yaml
    form:
      key: region
      edit: cells
      fields:
        - { id: region, bind: region, readonly: true, reason: "the regions are fixed" }
        - { id: target, bind: target, type: number, min: 0, required: true }
      rules:
        - { rule: "target >= 0", fields: [target], message: "A target is not negative." }
```

**New records in a guided drawer**: `new: drawer` with `steps` asks a few fields at a time; **Next** checks the step's fields first, a done step is
ticked, the last step shows what was entered and every message, and **Save as draft** keeps it from any step.

```yaml
    form:
      new: drawer
      new-label: New target
      steps: [ { id: who, label: Who }, { id: what, label: What } ]
      fields:
        - { id: region, type: enum, step: who, options: [North, South, East], required: true }
        - { id: target, type: number, step: what, required: true, min: 0 }
      hints:
        - { label: "Share of the plan", value: "target", of: "1000000", fmt: amount0, step: what }
```

A field of type `money` takes an amount and its currency; `datetime` a date and a time in its `tz`; a `default` starting with `=` is worked
out from the other fields as people type and by the host when a new record leaves the field empty.

**The rule language** is a small subset of Rachana-EL, the same wherever a form is checked: the fields by id, numbers (`30_000_000`,
`1e6`), `'text'`, `true`, `false`, `null`, `today`; `+ - * /`; `== != < <= > >=` (numbers as numbers, anything else as text, so ISO
dates compare); `and or not`; brackets; `empty(x)`, `len(x)`, `abs(x)`, `min(a, b)`, `max(a, b)`. An empty value makes a sum or a
comparison empty, and a rule that comes out empty holds: `required` says what is missing, not every rule. Mistakes are `RCH-2058`:
a rule or hint reading a name the form does not have, a money field without its `currency`, a `currency` naming no field, a `tz` that
is not a time zone, a `default` reading a field the form does not have.

### 12. Recipes

Each recipe is a panel (or two) for the `panels:` of a Sutra over the [static-sales example](../examples/static-sales) data: `sales`
(`region, product, month, units, revenue`), `targets` (`region, month, target`) and `regions` (`code, name, office, hub, staff`), each a
`data:` entry read as `$.sales`, `$.targets`, `$.regions`.

**A table with a filter, totals and "more".**

```yaml
  - id: top-sales
    kind: table
    title: "Sales over 100 units (${size($.sales[?@.units > 100])})"
    key: F6
    rows: "$.sales[?@.units > 100]"
    limit: 5
    moreLabel: "(size($.sales[?@.units > 100]) - 5) + ' more rows'"
    totalLabel: Total
    columns:
      - { label: Month, bind: "@.month", fmt: date }
      - { label: Region, bind: "@.region" }
      - { label: Units, bind: "@.units", fmt: amount0, total: true }
      - { label: Revenue, bind: "@.revenue", fmt: amount0, total: true }
```

The title counts the filtered rows; five rows show; the total row adds **all** the filtered rows (not only the five shown); the line
under the table reads `<n> more rows`; the key bar offers `F6 Sales over 100 units` (the label is the title cut at the first ` · ` or ` (`).

**Tabs per element.**

```yaml
  - id: by-region
    kind: tabs
    title: Regions
    key: F3
    each: $.regions
    tabTitle: "@.code + ' · ' + @.name"
    body:
      kind: kv
      columns:
        - { label: Office, bind: "@.office" }
        - { label: Hub, bind: "nonblank(@.hub, 'none')" }
        - { label: Staff, bind: "@.staff", fmt: amount0 }
```

One tab for each region, `@` the region inside the tab. A `panels:` list in place of `body:` shows whole panels inside each tab.

**A ladder with the next row lit.**

```yaml
  - id: months
    kind: ladder
    title: Months, the latest lit
    rows: "$.targets[?@.region == 'North']"
    highlight: "#index == size($.targets[?@.region == 'North']) - 1"
    columns:
      - { label: Month, bind: "@.month", fmt: date }
      - { label: Target, bind: "@.target", fmt: amount0, total: true }
```

`highlight` is read per row (`@` the row, `#index` its position); a ladder never hides a row and takes no `limit`.

**A strip that reads well.**

```yaml
strip:
  - { label: Revenue, bind: "'EUR ' + fmt(sum($.sales, 'revenue'), 'amount0')", emphasis: true }
  - { label: Units, bind: "sum($.sales, 'units')", fmt: amount0 }
  - { label: Against target, bind: "sum($.sales, 'revenue') - sum($.targets, 'target')", fmt: signed0, tone: sign }
  - { label: Regions, bind: "size($.regions)" }
```

Lead with what the reader checks first; at most eight figures (`RCH-2026`); `tone: sign` colours by the sign, `emphasis: true` highlights.

**A donut of shares.**

```yaml
  - id: shares
    kind: hbar
    title: Revenue by product
    area: right
    shape: donut
    rows: "group($.sales, 'product', 'revenue')"
    fmt: compact
```

The ring shows the total in the middle and one slice for each product; `group(list, key, value)` makes the `{label, value, count}` rows an `hbar`
reads.

**Panels for one sub type: `when:`.** A panel with `when:` is shown only where the expression is true and is left out otherwise, with
no empty box; use it to give the cases of one screen their own panels:

```yaml
  - { id: north-note, kind: markdown, title: North, text: "The north region reports weekly.", when: "ctl.region == 'North'" }
  - { id: south-note, kind: markdown, title: South, text: "The south region reports monthly.", when: "ctl.region == 'South'" }
```

**A link in a cell.** `link(id, kind?, label?)` makes a value navigable where the host knows the kind (the host decides what a link
opens); `link: true` on a column does the same for the column's identifiers.

### 13. Rachana-EL, step by step

Every `bind`, `rows`, `where` and `highlight` is a Rachana-EL expression (SPEC 4; the functions are the catalogue in SPEC 5). This
section builds them up on one document, shown here as JSON; the same text works as a `record:` entry of `data:` (read as `$.order`; below,
`$` is that record).

```json
{ "ORDER_ID": "ORD-1001", "STATUS": "SHIPPED", "CUSTOMER": "CU-ACME", "CURRENCY": "EUR", "PACKAGE_ID": "",
  "LINES": [
    { "LINE_ID": 1, "KIND": "STANDARD", "SKU": "A-100", "QTY": 10, "PRICE": 4.5, "WAREHOUSE": { "NAME": "North" },
      "SHIPMENTS": [ { "SHIP_ID": "S-1", "DATE": "2026-03-02", "QTY": 6 }, { "SHIP_ID": "S-2", "DATE": "2026-03-09", "QTY": 4 } ] },
    { "LINE_ID": 2, "KIND": "BACKORDER", "SKU": "B-220", "QTY": 5, "PRICE": 240.1, "WAREHOUSE": { "NAME": "" },
      "SHIPMENTS": [ { "SHIP_ID": "S-3", "DATE": "2026-03-11", "QTY": 3 }, { "SHIP_ID": "S-4", "DATE": "", "QTY": 2 } ] } ],
  "EVENTS": [ { "TYPE": "PLACED", "LINE_ID": "", "DATE": "2026-02-27", "AMOUNT": "" },
              { "TYPE": "INVOICED", "LINE_ID": 1, "DATE": "2026-03-02", "AMOUNT": 45.0 },
              { "TYPE": "INVOICED", "LINE_ID": 2, "DATE": "2026-03-11", "AMOUNT": 720.3 },
              { "TYPE": "REFUNDED", "LINE_ID": 1, "DATE": "2026-03-20", "AMOUNT": -9.0 },
              { "TYPE": "CLOSED", "LINE_ID": "", "DATE": "2026-03-31", "AMOUNT": "" } ] }
```

**Paths.**

| You write | You get | Why |
|---|---|---|
| `$.ORDER_ID` | `ORD-1001` | a field of the document |
| `$.LINES[0].KIND` | `STANDARD` | `[0]` is the first element |
| `$.LINES[-1].WAREHOUSE.NAME` | *(empty text)* | `[-1]` is the last; `.` walks into objects |
| `$.LINES[0].SHIPMENTS[1].DATE` | `2026-03-09` | array, object, array, field: as deep as the data goes |
| `$['ORDER_ID']` | `ORD-1001` | brackets: for a field whose name has a hyphen (`$['day-count']`; `$.day-count` would subtract) |
| `$.ORDR_ID` | *(null)* | a misspelt field is null, never an error: check spelling in the preview |

**Rows: `@` and `#index`.** Inside `rows:`, `each:` or a filter, `@` is the current element and `#index` its position (from 0): `bind: "@.PRICE"` is
each row's own price; `highlight: "#index == 0"` lights the first row; `tabTitle: "'Line ' + @.LINE_ID"` reads `Line 1`, `Line 2`.

**Filters.** `[?condition]` keeps the elements for which the condition holds:

| Expression | Result |
|---|---|
| `size($.EVENTS)` | `5` |
| `size($.EVENTS[?@.TYPE == 'INVOICED'])` | `2` |
| `size($.EVENTS[?!nonblank(@.LINE_ID)])` | `2`: the events that belong to no line (placed, closed) |
| `size($.EVENTS[?nonblank(@.AMOUNT) && @.DATE > '2026-03-05'])` | `2`: money events after 5 March |
| `first($.LINES[?@.KIND == 'BACKORDER']).SKU` | `B-220`: the backorder line, wherever it sits (`$.LINES[?…][0].SKU` is the same) |
| `$.LINES[?@.KIND == 'BACKORDER'].SKU` | *(null)*: a filter gives a list; take an element first (`first(...)`, `[0]`) |
| `$.LINES[*].LINE_ID` | the list `[1, 2]`: **projection**, a field of every element (what a control's `options` read) |
| `size(flatten($.LINES[*].SHIPMENTS))` | `4`: every shipment of every line as one list (`size(flatten($.LINES[1].SHIPMENTS))` is `2`) |
| `size(flatten($.LINES[*].SHIPMENTS)[?!nonblank(@.DATE)])` | `1`: shipments with no date yet |

Extracts often write a missing value as empty text (`"AMOUNT": ""`), so test for a value with `nonblank(...)`, which skips `""` and spaces as
well as null, not with `coalesce`, which keeps empty text.

**Functions.**

| Before | After | Shows |
|---|---|---|
| `bind: $.LINES[0].PRICE, fmt: amount0` | `bind: "$.CURRENCY + ' ' + fmt(first($.LINES).PRICE, 'amount0')"` | `EUR 5` |
| `bind: "$.LINES[0].QTY + $.LINES[1].QTY"` | `bind: "sum($.LINES, 'QTY')"` | `15` |
| `bind: "coalesce($.PACKAGE_ID, 'none')"` (empty text is kept) | `bind: "nonblank($.PACKAGE_ID, 'none')"` | `none` |
| `where: "$.STATUS == 'shipped'"` (never true: case matters) | `where: "lower($.STATUS) == 'shipped'"` | `true` for `SHIPPED` |
| `where: "$.LINES[0].KIND == 'stand'"` | `where: "contains($.LINES[0].KIND, 'stand')"` | `true`: `contains` ignores case |
| revenue by month the data does not carry | `rows: "group($.EVENTS[?nonblank(@.AMOUNT)], 'DATE', 'AMOUNT', 'month')"` | one row `2026-03`, value `756.3` |
| days between two dates | `bind: "days('2026-03-02', '2026-03-31')"` | `29` |

There is no list literal (`['EUR', 'USD']` does not compile).

**Formats and tones.** `fmt:` decides how a value reads; `tone:` its colour:

| Value | `fmt` | `tone` | Shows |
|---|---|---|---|
| `242000000` | `compact` | | `242.0m` |
| `-155245` | `signed0` | `sign` | `−155,245`, negative colour (a true minus sign) |
| `0.040829` | `pct4` | | `4.0829%` |
| `0.040829` | `price4` | | `0.0408` (a price per unit: no scaling) |
| `2025-07-25` | `dmy` | | `25 Jul 2025` |
| `SHIPPED` / `Pending` | | `status` | `ok` colour / `warn` colour |

`tone: status` reads the words: *fail*, *reject*, *dispute*, *breach* are bad; *pending*, *unmatched*, *warn* a warning; anything else fine.
It suits workflow statuses, not a rating (`CCC` would show as fine).

**Conditions** combine with `&&`, `||` and `!`, and choose with `? :` (`bind: "$.STATUS == 'SHIPPED' ? 'Done' : 'Open'"` gives `Done`). Three things
to know: `<`, `>`, `<=`, `>=` compare numbers as numbers and texts as text, so ISO dates order correctly, and anything against empty
is false; `||` gives `true` or `false`, not a value (`$.STRATEGY || 'n/a'` gives `true`: use `nonblank`); `+` adds only when both sides are
numbers, otherwise it joins text (`'1' + 1` is `11`), and arithmetic on a missing value is empty (`$.QTTY * 2`): wrap it,
`nonblank($.QTTY, 0) * 2` is `0`.

### 14. A complete example, annotated

The Sutra below shows one region of the [static-sales example](../examples/static-sales) data (`sales`, `targets` and `regions`). It uses
most of the grammar: `data:`, a control, the title, the strip, panels of seven kinds, expressions, templates and function keys. Every
line is commented.

How to read the notation:

- `$` is the document: here the object of the `data:` entries, so `$.sales` is the sales table.
- `@` is the current row inside `rows`, `each` or a column: `@.units` is the row's `units`.
- A value in `"double quotes"` is a Rachana-EL expression (operators, functions). A bare path such as `$.sales` needs no quotes.
- `${...}` inside text (titles, pills) embeds an expression in a template.
- `#` starts a YAML comment, which the engine ignores.

```yaml
rachana: 1.2
# ---- Identity -----------------------------------------------------------------------------------------
sutra: sales-annotated           # the name: lower-case kebab, 2-64 characters
version: 1                       # name@version is unique; old versions stay loadable
description: One region of the sales example, every line explained.

# ---- Data: the tables the screen reads, each a field of $ ---------------------------------------------------
data:
  sales:   { table: sales, key: [region, product, month] }      # data/sales.csv: region, product, month, units, revenue
  targets: { table: targets, key: [region, month] }              # data/targets.csv: region, month, target
  regions: { table: regions }                                    # data/regions.csv: a lookup (code, name, office, hub, staff)

# ---- Controls: what the reader sets; panels read it as ctl.<id> ---------------------------------------------
controls:
  - { id: region, kind: dropdown, label: Region, options: "$.regions[*].name", default: first }

# ---- The title line:  [pill]  ID  with  <text> ------------------------------------------------------------------
title:
  pill: "Sales · ${ctl.region}"                  # a template: text with ${expression} parts
  id: "'Region: ' + ctl.region"                  # an expression: the big identifier
  with: "first($.regions[?@.name == ctl.region]).office"

# ---- The strip: up to 8 headline figures under the title ----------------------------------------------------
strip:
  - { label: Revenue, bind: "sum($.sales[?@.region == ctl.region], 'revenue')", fmt: amount0 }
  - { label: Units, bind: "sum($.sales[?@.region == ctl.region], 'units')", fmt: amount0 }
  - { label: Target, bind: "sum($.targets[?@.region == ctl.region], 'target')", fmt: amount0 }
  - { label: Against target, bind: "sum($.sales[?@.region == ctl.region], 'revenue') - sum($.targets[?@.region == ctl.region], 'target')", fmt: signed0, tone: sign, emphasis: true }
  - { label: Side, bind: "sum($.sales[?@.region == ctl.region], 'revenue') >= sum($.targets[?@.region == ctl.region], 'target') ? 'Ahead' : 'Behind'" }   # condition ? then : else

# ---- Panels: the body of the view, in order. area: right puts one in the side column ---------------------------
panels:

  # kv: a label/value grid. rows names the one object to read; inside the columns @ is that object.
  - id: office
    kind: kv
    title: Office
    key: F2                                      # F2 jumps here (keys are unique across the Sutra)
    code: OFC                                    # a short tag at the right of the panel header
    description: Where the region sits.          # plain text for authors, never evaluated
    rows: "first($.regions[?@.name == ctl.region])"
    columns:
      - { label: Office, bind: "@.office" }
      - { label: Hub, bind: "nonblank(@.hub, 'none')" }   # nonblank: the first value that is not empty text
      - { label: Staff, bind: "@.staff", fmt: amount0 }

  # table: one row per element of rows, with a total row and a "more" line.
  - id: detail
    kind: table
    title: "Sales rows · ${ctl.region}"           # templates work in titles too
    key: F3
    rows: "$.sales[?@.region == ctl.region]"      # a filter: [?condition] keeps matching elements
    limit: 6                                      # show six rows, then "N more"
    moreLabel: "(size($.sales[?@.region == ctl.region]) - 6) + ' more rows'"
    totalLabel: Total
    columns:
      - { label: Month, bind: "@.month", fmt: date }
      - { label: Product, bind: "@.product" }
      - { label: Units, bind: "@.units", fmt: amount0, total: true }        # total: true adds it into the total row
      - { label: Revenue, bind: "@.revenue", fmt: amount0, total: true }

  # ladder: a list with one highlighted row; highlight is evaluated per row (#index is its position).
  - id: months
    kind: ladder
    title: Targets by month, the latest lit
    rows: "$.targets[?@.region == ctl.region]"
    highlight: "#index == size($.targets[?@.region == ctl.region]) - 1"
    columns:
      - { label: Month, bind: "@.month", fmt: date }
      - { label: Target, bind: "@.target", fmt: amount0 }

  # status: states coloured by meaning (a word like "Behind" is a warning).
  - id: standing
    kind: status
    title: Standing
    fields:
      - { label: Against target, bind: "sum($.sales[?@.region == ctl.region], 'revenue') >= sum($.targets[?@.region == ctl.region], 'target') ? 'Ahead' : 'Pending review'", tone: status }

  # markdown: a note for the people who read this view; ${...} parts are evaluated.
  - id: notes
    kind: markdown
    title: Reading this view
    text: |
      Amounts are in euro. ${size($.sales[?@.region == ctl.region])} rows belong to ${ctl.region}.

  # ---- The side column -------------------------------------------------------------------------------------

  # line: x and y name fields of each row; group() sums a field by another and gives {label, value, count} rows.
  - id: trend
    kind: line
    title: Monthly revenue
    area: right
    rows: "group($.sales[?@.region == ctl.region], 'month', 'revenue')"
    x: label
    y: value
    fmt: amount0

  # hbar: horizontal bars, one per row; on-select makes a click on a bar set a control.
  - id: by-product
    kind: hbar
    title: Revenue by product
    area: right
    rows: "group($.sales[?@.region == ctl.region], 'product', 'revenue')"
    label: label
    value: value
    fmt: compact

  # gauge: one value against a maximum (a target, a budget). The text is value / max in fmt (pct0 by default).
  - id: attainment
    kind: gauge
    title: Revenue against target
    area: right
    value: "sum($.sales[?@.region == ctl.region], 'revenue')"
    max: "sum($.targets[?@.region == ctl.region], 'target')"

# ---- Function keys beyond the panel keys ------------------------------------------------------------------
keys:
  F10: months                                    # any other text: the id of a panel to scroll to

# ---- Notes for authors and reviewers (plain text; the engine ignores them) ------------------------------------
notes: |
  Written to show the grammar, not for a desk.
  description (top) and notes are prose for people; a panel's description says what that panel shows.
```

What each part does when the view is built:

| Part | What happens |
|---|---|
| `data` | Each entry is a table the host serves (here a file under `data/`); `$.sales` is its rows. A table the host does not have is `RCH-2070`, never read as no rows. |
| `controls` | The reader sets `region`; every expression reads it as `ctl.region`, so the whole screen follows it. |
| `title`, `strip` | Evaluated once: each expression gives a value, which `fmt` formats and `tone` colours. |
| `rows`, `each` | Evaluated to a list; the panel's columns are evaluated once per element with `@` set to it. |
| `key`, `keys` | Become the function-key bar; a panel key scrolls to the panel; a `keys:` entry naming a panel id does the same. |
| Missing data | A path that is absent is null, and an extract's empty text is nothing to `nonblank`: the cell shows a dash or nothing, and a panel with no data says so. |

You should see, with `North` chosen:

| Where | Shows |
|---|---|
| Title | the pill `Sales · North`, the id `Region: North` and `Lakeside` after it |
| Strip | `Revenue 278,590`, `Units 1,830`, `Target 289,200`, `Against target −10,610` (negative colour, highlighted), `Side Behind` |
| Office | `Office Lakeside`, `Hub Harbor Point`, `Staff 14` |
| Sales rows | six of the 24 rows, a `Total` row over all 24 rows, and `18 more rows` under the table |
| Targets by month | the six months, the last one lit |
| Standing | `Against target Pending review` in the warning colour |
| Monthly revenue | a line over the six months |
| Revenue by product | one bar a product |
| Revenue against target | a bar at 96% (278,590 / 289,200) |
| Function keys | `F2 Office`, `F3 Sales rows`, `F10 months` |

### 15. Common mistakes, and a checklist

All the problems of a file are reported at once (`rachana lint`: `<file>:<line> <code> <message>`). Every message below is what
the checker printed for the mistake; the codes are in SPEC 6.2.

| You wrote | You see | Fix |
|---|---|---|
| `bind: @.id` (unquoted) | `RCH-2001 YAML syntax: ... found character '@' that cannot start any token` | quote it: `bind: "@.id"` |
| `highlight: #index == 0` | no error, and no row is highlighted (`#` starts a YAML comment) | `highlight: "#index == 0"` |
| `version: "2"` | `RCH-2020 version must be a positive integer up to 2147483647, not '2'` | `version: 2` |
| `sutra: Sales_Mine` | `RCH-2020 name 'Sales_Mine' must be lower-case kebab, 2-64 characters` | `sutra: sales-mine` |
| `pannels:` | `RCH-2011 unknown key 'pannels' in top level` | `panels:` |
| `panels:` written twice | `RCH-2033 duplicate key 'panels' (first written at line 4): YAML would keep only the last, so write each key once` | one of each key |
| `kind: bar` | `RCH-2021 unknown panel kind 'bar'; expected one of kv, table, tabs, line, ...` | `kind: hbar` |
| a scatter without `y`, a metric without `value` | `RCH-2022 'scatter' panel 'a' needs option 'y'` | add it |
| `rows:` on a `graph` | `RCH-2023 option 'rows' is not valid for 'graph' panels` | a graph takes `nodes` and `edges` |
| `limit: 5` on a `ladder` | `RCH-2023 option 'limit' is not valid for 'ladder' panels` | use a `table`, or drop `limit` |
| `body:` on a `kv` | `RCH-2023 only 'tabs' panels take a 'body'` | make it `kind: tabs` with `each:` |
| a column with `fmat:` | `RCH-2011 unknown key 'fmat' in column` | `fmt:` |
| `agg: median` on a `pivot` | `RCH-2029 option 'agg' of 'pivot' panels must be one of sum, count, avg, min, max, not 'median'` | a value the message lists |
| `limit: "6"` (quoted) | `RCH-2029 option 'limit' of 'table' panels must be a whole number of rows, 1 or more ... not '6'` | `limit: 6` |
| `rows: 5` | `RCH-2029 option 'rows' of 'hbar' panels is an expression written as text (such as $.cashflows), not '5'` | `rows: "$.sales"` |
| two panels with one id | `RCH-2024 duplicate panel id 'a'` | rename one |
| `key: F3` on two panels | `RCH-2025 function key F3 is used twice` | one key per action |
| `key: F13` | `RCH-2025 'F13' is not a function key (F1-F12)` | `F2` to `F12` |
| nine strip items | `RCH-2026 the strip holds at most 8 figures, found 9` | move some into a `kv` panel |
| `area: left` | `RCH-2027 area 'left' is not 'main' or 'right': name areas in layout.areas first` | `area: right` |
| `span: 13` | `RCH-2030 span must be a whole number from 1 to 12 (columns of the 12-column grid), not '13'` | `span: 12` (or leave it out) |
| no `rachana:` line | `RCH-2009 missing 'rachana: 1' (the Rachana language version) at the top` | add `rachana: 1.2` |
| `rachana: 2` | `RCH-2009 'rachana: 2' is not a language version this server reads (it reads 1)` | `rachana: 1.2` |
| `notes:` followed by a list | `RCH-2012 'notes' is plain text` | `notes: \|` and indented text |
| `rows: "$.legs[0"` | `RCH-2101 expression '$.legs[0': expected ']' but the expression ends at 8` | close the bracket |
| `value: "size($.t, 1)"` | `RCH-2101 expression 'size($.t, 1)': size takes 1 argument(s), got 2 at 0` | `size($.t)` |
| `bind: "$.net_mtm"` for a field `NET_MTM` | no error; the cell is empty | names are exact; the preview catches these |
| `label: id` on an `hbar` over rows with `ID` | no error; no bars | `label: ID`: field names are exact on `line`, `area` and `hbar` |
| `where: "$.STATUS == 'shipped'"` | no error; never true (`SHIPPED` is not `shipped`) | `lower($.STATUS) == 'shipped'`, or the case the data uses |
| `fmt: compact` on a `gauge` | the gauge reads `0.5` | leave `fmt` out (the gauge shows value / max as `48%`) |
| a `status` field without `label` | the label reads `null` | give every status field a `label` |
| a waterfall whose level rows have no `total` flag | the opening amount is drawn as a move from zero | mark the level rows `total: true` |
| a title such as `Revenue by region and month` with a key | the key bar reads *F3 Revenue by region and month* | put the subject first and cut with ` (`: `Revenue (by region and month)` |
| a bare `ctl.set` with no such control | `RCH-2036` when the Sutra is checked | declare the control (section 10) |
| a form rule naming a field the form lacks | `RCH-2058` naming the field | fix the id (section 11) |

Before you ship a Sutra:

- The strip leads with what the reader checks first and has at most eight figures.
- Every panel a reader reaches often has a `key` (`F2` to `F12`), and the subject comes first in its title.
- Money has `fmt: signed0` or `amount0`, and `tone: sign` where the sign matters; rates `pct4`, prices `price4`.
- Missing values from an extract are tested with `nonblank`, not `coalesce`.
- Every strip item and column has a `label` that says what it is in the reader's words.
- Heavy panels have a phone tier (section 9).
- `description` says what the layout is for; `notes` say why it is what it is.
- It previews against a thin table and a rich one, and `rachana lint` reports no problem.

## Libraries and `rachana lib`

A **library** is a versioned, signed folder of [typed fragments](WRITING_SUTRAS.md#7-fragments-and-fragments-as-functions) (and optionally
functions) that any Sutra can use. Screens become assembly: a Sutra names the library, uses its fragments and maps its own data onto
them. The normative rules are SPEC 2.5 "Libraries" ([SPEC.md](../spec/1/SPEC.md)); the libraries shipped with Rachana and
every fragment's parameters are in their generated pages: [core](../libraries/core/README.md) and
[finance](../libraries/finance/README.md) (index: [README.md](../README.md#rachana-fragment-libraries)).

### 1. Using a library in a Sutra

```yaml
rachana: 1.2
sutra: orders
version: 1
libraries: [core@1]                 # each library and the major the Sutra is written against
title: { id: "'Orders'" }
data:
  orders: { rows: [ { ORDER_ID: A1, AMT_USD: 120 }, { ORDER_ID: A2, AMT_USD: 80 } ] }
panels:
  - use: core/data-table@1          # <library>/<fragment>@<major>; the major is required
    with:
      rows: $.orders
      columns: [ { label: Order, bind: "@.ORDER_ID" }, { label: Amount, bind: "@.AMT_USD", fmt: amount0, total: true } ]
```

`use: core@1/data-table@1` (the long form) works without a `libraries:` key. A library function is called as
`<library>.<name>(...)`. The compile result lists every library it was built from with its exact version and SHA-384.

### 2. Where libraries are found

A host finds libraries **on disk only, never on the network**, in this order of sources, all of which are read:

| Source | Used by |
|---|---|
| the libraries bundled with Rachana (`core`, `finance`) | every tool |
| `<project>/rachana_libs/` (library folders or `.rlib` files) | `lint`, `build`, `studio`, the server (`project.dir`) |
| `--libs <path>[,<path>]` | `rachana lint`, `build`, `studio`; `python -m drishti_rachana lint --libs` |
| `RACHANA_LIBS=<path>[:<path>]` | the same commands |
| `libraries:` in `rachana-server.yaml` | the [server](IN_YOUR_APP.md#the-rachana-server) |

Each path is a library folder, an `.rlib`, or a folder of them. A library name found twice with different contents is refused with
a problem (rename or remove one), never silently shadowed. A project's `rachana.lock` pins each library's exact version and the
SHA-384 of its canonical archive; a library not in the lock, or of another major, is `RCH-2111`; a hash or signature that does not
match is `RCH-2113`.

### 3. What a library is

```text
mylib/
  mylib.rachana-lib.yaml       the manifest: rachana, library, version (semver), title, summary, publisher, licence,
                               scope (public | private), profiles, depends, needs, categories; exports (generated)
  fragments/<name>.v<N>.fragment.yaml    one typed fragment per file and major
  functions/<file>.yaml        optional library functions
  contracts/<name>.json        optional published table contracts (Data Protocol schema form)
  examples/<name>.sutra.yaml   one example Sutra per fragment
  goldens/                     the ViewModel snapshot of each example
  MANIFEST.sha384              the SHA-384 of every file (generated)
  SIGNATURE                    optional Ed25519 signatures of MANIFEST.sha384
  README.md                    the library's page (generated)
```

Inside a library every use is `<library>/<fragment>@N` of itself or of a library it `depends` on. A typed fragment of a **public**
library reads only its parameters (`RCH-2117` for `$`, `ctl.`, `state.`, `source:`, `data:`, `url:`). Codes: `RCH-2110` not found,
`RCH-2112` invalid library.

The `.rlib` archive is a ZIP written deterministically (sorted entries, stored, a fixed time), so a folder and its archive have one
identity and one hash.

### 4. `rachana lib`

One implementation, the Java `LibraryTool`, reached from every launcher: `node js/packages/rachana-cli/bin/rachana.js lib ...` (with
`RACHANA_JAR` or `RACHANA_CLASSPATH` set; see [CLI and SDKs](IN_YOUR_APP.md#the-java-launcher)) or
`java -cp <jars> com.ash.rachana.cli.Main lib ...`.

| Command | What it does |
|---|---|
| `lib build DIR` | regenerates the manifest's `exports` and `MANIFEST.sha384` from the files |
| `lib keygen` | prints a new Ed25519 key pair (public key, private seed) |
| `lib sign DIR PUBLIC-KEY PRIVATE-SEED` | adds a signature line over `MANIFEST.sha384` to `SIGNATURE` |
| `lib pack DIR [--out FOLDER] [--require-signed] [--publisher KEY]...` | writes `<name>-<version>.rlib` |
| `lib verify DIR-OR-RLIB [--require-signed] [--publisher KEY]...` | checks every rule a host checks when it loads the library |
| `lib lock DIR-OR-RLIB... [--lock rachana.lock]` | adds or updates each library's entry in the lock file |
| `lib gate DIR [--strict]` | the quality gates: LG1 library valid, LG2 frozen fragments unchanged, LG3 definition of done (docs, examples), LG4 level replay, LG5 interface append-only, LG8 budgets; exit 1 when one fails (`--strict` also fails on the LG3 notes, such as a missing second example) |
| `lib freeze DIR` | records the released baseline (`FROZEN.sha384`, `released/`) that LG2 and LG5 check later versions against; run `lib build` after |
| `lib contracts DIR-OR-RLIB [--json]` | the tables a host must serve to use the library |
| `lib check-host DIR-OR-RLIB --base URL [--table CONTRACT=TABLE]... [--json]` | checks each contract against a running host's table schemas, column by column |

Exit status: 0 done, 1 the library does not verify (or a host column breaks a fragment), 2 usage.

### 5. Building the bundled libraries

```bash
cd java && ./mvnw clean package -DskipTests && cd ..
python3 tools/libraries/build.py              # docs, build, test against goldens, gates, sign (development key), pack, verify
python3 tools/libraries/build.py --update-goldens core   # after an intended change of a fragment's output
```

`build.py` writes each library's `README.md` from its fragment files, so the library pages are never edited by hand. `--gallery`
also builds a gallery site of every fragment with Rachana itself. The same check runs in the Maven build (`LibrariesCheckTest`).

### 6. Your own library

Write the fragments and examples, then `rachana lib build`, `rachana lib gate`, sign and pack. Put the folder or the `.rlib` where your
hosts read libraries (section 2); nothing in Rachana is modified or rebuilt. A step-by-step run is in the tutorial
[Build and use a library](TUTORIAL.md#part-2-build-and-use-a-library). In Studio, every fragment of every library found appears in the palette with a
parameter form ([Studio](WRITING_SUTRAS.md#rachana-studio)).

## Rachana Studio

Studio is the visual editor of a folder of Sutras: a palette of library fragments, the live preview, the Sutra source checked as you
type, a parameter form that inserts a fragment, and the unsaved changes as a diff. It edits the same YAML files every other tool
reads; it adds nothing of its own to them.

### 1. Open it

```bash
node js/packages/rachana-cli/bin/rachana.js studio examples/static-sales --port 4500
node js/packages/rachana-cli/bin/rachana.js studio examples/server-demo/project/sutras --data examples/server-demo/project/data --port 4501
```

`rachana studio [folder] [--libs <path>,...] [--data <dir>] [--port <n>]`. It listens on `127.0.0.1` only and prints its address and
the libraries it found (`studio http://127.0.0.1:4500/  (core 1.0.0, finance 1.0.0; Ctrl+C to stop)`). It needs the runtime bundles
(`python3 tools/js/build_js.py`). In the IDE: the launchers **Rachana Studio** and **Rachana Studio (server-demo)**.

| Option | Meaning |
|---|---|
| `folder` | the folder of `*.sutra.yaml` files (default: the current folder) |
| `--data <dir>` | the tables the preview reads (`<name>.csv`, `.json`, ...); default: the folder's `data/` |
| `--libs <path>,...` | more libraries besides the bundled ones, `<folder>/rachana_libs` and `RACHANA_LIBS` ([Libraries](WRITING_SUTRAS.md#2-where-libraries-are-found)) |
| `--port <n>` | the port (default: any free port) |

### 2. The screen

| Area | What it does |
|---|---|
| **Sutra** (top) | opens one of the folder's Sutras |
| **Fragments** (left) | every fragment of every library found, by category, with a search box |
| **Live preview** (middle) | the real `<rachana-view>` painting the Sutra over the folder's data, redrawn as you edit |
| **Sutra source** (right) | the YAML in a code editor; the compiler checks it at the level its `rachana:` key declares, problems marked on their lines |
| **Fragment properties** (right) | the chosen fragment's parameters as a form: typed fields, the tables of the folder's data files, and a column mapping that proposes your columns for the fragment's; **Insert into Sutra** adds the `use:` block |
| **Changes** (right) | the unsaved changes as a diff |
| **Save** | writes the Sutra back into the folder (Ctrl+S); nothing else is ever written |

Save writes only a `*.sutra.yaml` path inside the opened folder. The assistant bar ("Describe a screen...") is reserved for AI
authoring and does not generate yet.

### 3. A first edit

The tutorial [Your first dashboard](TUTORIAL.md#part-1-your-first-dashboard) (step 5) inserts a library fragment with Studio and shows the YAML
it writes; [Build and use a library](TUTORIAL.md#part-2-build-and-use-a-library) shows your own library in the palette.
