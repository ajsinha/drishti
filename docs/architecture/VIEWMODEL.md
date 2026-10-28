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
# The ViewModel: the contract of every entity screen

When you open `TRD MX-20000001`, the server does not send HTML and it does not send the trade's raw record. It sends a
**ViewModel**: the screen, already laid out and bound, as JSON. The console paints it; a script reads it; a golden test
compares it; a live stream patches it. This document defines that JSON: its structure, the rules every value follows,
and how to use it. A client-side renderer (planned for 2.9.7), a distributable JS/CSS library and GenUI will depend on it,
so it is written as a contract.

The shape is checked by a machine-readable [JSON Schema](#9-the-json-schema), served at `GET /api/v1/viewmodel/schema`
and generated from the Java records, and a test validates real views and live frames against it.

Every example below is real output, abbreviated (lists cut to two items, long text cut). Most come from the
[all-panels showcase](../guides/examples/all-panels-showcase.md), the bond trade `DEMO-BOND-1` that holds one panel of
every kind; some come from the demo swap `IRS-48213`.

## Contents

1. [What it is, and the principles](#1-what-it-is-and-the-principles)
2. [The top level](#2-the-top-level)
3. [The common types](#3-the-common-types)
4. [Panels](#4-panels)
5. [Live: the stream and its patches](#5-live-the-stream-and-its-patches)
6. [Entitlements and masks in a view](#6-entitlements-and-masks-in-a-view)
7. [How to use it](#7-how-to-use-it)
8. [Compatibility and versioning](#8-compatibility-and-versioning)
9. [The JSON Schema](#9-the-json-schema)

The code: `drishti-engine/src/main/java/com/ash/drishti/engine/view/` (`ViewModel.java`, `PanelData.java`,
`Emptiness.java`, `MaskCount.java`, `ScreenGrid.java`, `ViewModelSchema.java`), the binders that fill `data`
(`engine/bind/Binder.java`, `ChartBinder.java`, `PivotBinder.java`, `MapBinder.java`), and the live code
(`engine/live/PatchDiffer.java`, `Patch.java`, `Frame.java`, `ViewStream.java`; `server/api/StreamController.java`,
`FrameMailbox.java`). The decision: [ADR-004](adr/004-server-returns-a-view-model.md).

---

## 1. What it is, and the principles

The ViewModel is the **bound layout**: the output of the [view pipeline](ARCHITECTURE.md#4-the-view-pipeline) after a
Sutra (or inference) has decided what goes where and the binder has evaluated every expression against the entity's
document. The pipeline builds it in one pass; the rest of the system only reads it.

| Principle | What it means for a client |
|---|---|
| **Data, no logic** | There is nothing to evaluate: no expressions, no Sutra, no formats. A client walks the JSON and draws it. |
| **Formatted text plus a tone** | Every value a person reads arrives as `text` already formatted by the server (`−412,580`, `3.8500%`, `2026-10-02`) with a `tone` (`neg`, `pos`, ...). A client never formats a number, so every client shows `−1,403,091` the same way. The exceptions are the numbers a chart needs to draw: series values, bar lengths, bin edges, coordinates, gauge levels. Each of those travels **beside** its formatted text where a person reads it (a bar has `value` and `text`; a point has `x` and `xText`). |
| **Masks applied on the server** | Values a caller may not see arrive as `•••`, already. A client cannot unmask anything and needs no rule to hide anything. `provenance.masked` says how many were hidden. |
| **Per viewer** | A ViewModel is built for one caller: their masks, the kinds they may open, the business date they asked for, the control values in their query. Two people may receive different JSON for the same entity. |
| **One model, every consumer** | The same JSON is the REST answer, the first event of a live stream, what the console's macros paint, what the Studio preview shows, what golden tests assert, and what live patches replace piece by piece. |
| **Failures stay local** | A panel that cannot bind carries `error` and no `data`; a strip value that fails reads `—`. The view still arrives. |

What it is not: it is not the entity's data (that is `GET /api/v1/entities/{kind}/{id}/raw`), and it is not HTML (the console
and the embed API turn it into HTML; see [ADR-021](adr/021-embedding-is-html-over-the-wire.md)).

---

## 2. The top level

`GET /api/v1/views/trade/DEMO-BOND-1` (the showcase, panels and timings abbreviated):

```json
{"ref": {"kind": "trade", "id": "DEMO-BOND-1"},
 "mnemonic": "TRD",
 "title": {"pill": "Showcase · US Treasury 4.25% 2038", "id": "DEMO-BOND-1", "with": null},
 "strip": [{"label": "Notional", "text": "USD 25,000,000", "path": "$.CURRENCY"},
           {"label": "MTM (USD)", "text": "+1,209,000", "tone": "pos", "emphasis": true, "path": "$.MTM"}],
 "panels": [{"id": "terms", "kind": "kv", "title": "1 · kv: bond terms", "area": "main", "inferred": false, "empty": false, "span": 8,
             "data": {"fields": [{"label": "Coupon", "text": "4.2500%", "path": "$.TERMS.COUPON"}]}}],
 "keys": [],
 "provenance": {"layout": "Sutra all-panels-showcase v1 + inference", "fingerprint": "b24b…2d27", "source": "test", "generation": 1,
                "fetchedAt": "2026-10-28T17:13:12.366528408Z", "live": true, "businessDate": null,
                "updatedAt": "2026-10-28T17:13:08.913778814Z", "staleAfter": null, "stale": false,
                "sutra": "all-panels-showcase", "masked": 0, "maskedPanels": []},
 "timings": {"fetch": 0.0, "layout": 7.81, "links": 1.46, "bind": 21.66, "total": 30.94}}
```

Every field (record `ViewModel`). *Always* means the server always sends it; *optional* fields are left out when they
have no value (the record is written with "leave out nulls").

| Field | Type | Sent | Meaning |
|---|---|---|---|
| `ref` | `{kind, id}` | always | The entity this view shows. |
| `mnemonic` | string | optional | The command-line code of the kind (`TRD`, `NSET`); left out for a kind without one. |
| `title` | [Title](#title) | always | The title line: pill, identifier, related entity. |
| `strip` | [Cell](#cell)`[]` | always | The header figures, in order. Live `strip` patches address them by index. |
| `panels` | [Panel](#4-panels)`[]` | always | The panels, in layout order. Each says its column (`area`) and, with a `layout`, its place. |
| `keys` | [Key](#keys-and-actions)`[]` | always (may be empty) | The function keys. |
| `provenance` | [Provenance](#provenance) | always | How the view was built and how fresh it is. |
| `timings` | `{stage: ms}` | always | Milliseconds per stage: `fetch`, `layout`, `links`, `bind`, `total`. Diagnostic; never draw anything from it. |
| `controls` | Control`[]` | optional (left out when empty) | The Sutra's controls (dropdowns, toggles, date ranges, ...) with their options and current values. See [Controls and selects](#controls-and-selects). |
| `selects` | Select`[]` | optional (left out when empty) | The panels whose click sets a control. |
| `layout` | object | optional | The Sutra's screen grid (`areas`, `columns`, `split`, `tabs`, `sections`, `sticky`, `arrange`, `phone`, `tablet`) and each top-level panel's place. Left out when the Sutra has none: the panels then flow in two columns, `main` and `right`. See [Area and grid](#area-and-grid). |

---

## 3. The common types

### Cell

One formatted value (record `ViewModel.Cell`). Used by the strip, `kv`, `status` and `provenance` fields, table cells,
tab fields, pivot cells and the metric figure. Every field is optional: a cell is written with "leave out defaults", so
`false`, `null` and missing are the same.

| Field | Type | Meaning |
|---|---|---|
| `label` | string | The label (a strip figure, a kv field, a table cell's column). |
| `text` | string | The value, formatted. Missing or `—` when the document has no value; `•••` when masked. |
| `tone` | [Tone](#tones) | How to colour it. |
| `link` | [Link](#link) | The entity the value opens. |
| `emphasis` | boolean | Draw it highlighted (the MTM figure). |
| `path` | string | The document path the value came from (`$.MTM`, `$.TERMS.COUPON`). Diagnostic and for "where does this come from"; live patches address strip cells by index, not by path. |

```json
{"label": "MTM (USD)", "text": "+1,209,000", "tone": "pos", "emphasis": true, "path": "$.MTM"}
```

An empty cell is `{}`: the blank cells of a table's total row are written so.

### Link

A navigable reference to another entity (record `ViewModel.LinkView`): `{kind, id, mnemonic}`. `mnemonic` is the target
kind's command code, missing when it has none. A client opens it as the view of `kind`/`id` (the console links to
`/v/{kind}/{id}`).

```json
{"kind": "counterparty", "id": "CP-NORTHBRIDGE", "mnemonic": "CPTY"}
```

A link the caller may not follow is **removed** before the view is sent (see [section 6](#6-entitlements-and-masks-in-a-view)), so
any link present is one the caller may open.

### Tones

A tone is a word, not a colour; the client maps it to its theme (the console: `t-<tone>` classes on tokens in
`tokens.css`) and always pairs colour with a sign or glyph. The values the server sends:

| Tone | Meaning | Comes from |
|---|---|---|
| `pos`, `neg` | a positive or negative figure | `tone: sign` in a Sutra, hbar bars, theme-coloured waterfalls |
| `ok`, `warn`, `bad` | a good, pending or failed status | `tone: status`, the timeline, gain-loss waterfalls |
| `link`, `accent` | a neutral series or highlight | `tone: link`/`accent`, the first series of a line |
| `muted` | a total, a neutral bar | waterfall totals |
| `inactive` | a value whose command an administrator turned off | entitlements (a table cell whose link was removed) |

The schema lists exactly these for `Cell.tone` and the tones of bars, waterfall steps, histogram markers, timeline events and
candles; a client that meets another word should draw the value untoned. A **series'** tone (`line`, `area`) is whatever the
Sutra author wrote, usually one of these, and is free text in the schema.

### Title

`{pill, id, with}` (record `TitleView`). `pill` is the kind's caption (`Trade · Interest rate swap`), `id` the identifier
shown, `with` an optional related entity as a [Cell](#cell) (`{"text": "Northbridge Capital LLP", "link": {...}}`); `with` is
`null` when there is none.

### Keys and actions

`{key, label, action, panel?, link?}` (record `KeyView`). `key` is `F2` to `F12`; `action` says what pressing it does:

| `action` | Field | What the client does |
|---|---|---|
| `panel` | `panel` | Scroll to and focus the panel with that id. |
| `link` | `link` | Open the linked entity. |
| `raw` | | Show the entity's raw document (`GET /api/v1/entities/{kind}/{id}/raw`). |
| `impact` | | Open Impact for the entity (`/impact/{kind}/{id}` in the console). |
| `denied` | | Nothing: the key's target is a kind the caller may not open. Its label ends `(no access)` or `(not active)`. |

```json
[{"key": "F2", "label": "Legs", "action": "panel", "panel": "legs"},
 {"key": "F7", "label": "Netting set", "action": "link", "link": {"kind": "netting-set", "id": "NS-NORTH-01", "mnemonic": "NSET"}},
 {"key": "F8", "label": "Impact", "action": "impact"},
 {"key": "F9", "label": "Raw JSON", "action": "raw"}]
```

### Provenance

How the view was built (record `ViewModel.Provenance`). Every field is always sent except `partial` and `missing`.

| Field | Type | Meaning |
|---|---|---|
| `layout` | string | What laid it out: `Sutra irs-vanilla v3 + inference`, or an inference-only label. |
| `fingerprint` | string | A short fingerprint of the document's shape (`4e09…e011`); the layout is cached per Sutra version and fingerprint. |
| `source` | string | The connector that answered (`aero-risk`, `trade-endur`). |
| `generation` | integer | The source's version of the entity. It moves when the data changes; live frames carry it too. |
| `fetchedAt` | ISO-8601 instant | When the document was fetched. |
| `live` | boolean | Whether the source pushes updates for this view. `false` for a past business date and for static sources: a stream then sends one `view` and closes. |
| `businessDate` | `YYYY-MM-DD` or null | The business date the source read; `null` for sources that are not dated. |
| `updatedAt` | ISO-8601 instant or null | When the source last received new data; `null` when it cannot tell. |
| `staleAfter` | ISO-8601 duration or null | The source's freshness threshold. |
| `stale` | boolean | Older than `staleAfter`. The console shows *stale*. |
| `sutra` | string or null | The Sutra's name; `null` when inference alone laid the view out. |
| `masked` | integer | How many values in this view the caller's field masks replaced with `•••` (strip, title and every panel's data); `0` when none. |
| `maskedPanels` | string`[]` | The ids of the panels that hold masked values. |
| `partial` | boolean, optional | Sent only when `true`: a page gathered across Drishti\*net with a node down. |
| `missing` | string`[]`, optional | Sent only when not empty: what did not answer, one line per node. |

The `provenance` **panel** kind shows the same facts as label/value lines; a client that wants them as data reads this object.

### Timings

`{"fetch": 3.98, "layout": 2.74, "links": 1.28, "bind": 1.73, "total": 9.74}` (milliseconds, `IRS-48213`). Diagnostic only.

### `empty`, `error` and `denied`

Three panel fields say why a panel shows no data. Draw them in this order:

| Field | Set when | `data` | What to draw |
|---|---|---|---|
| `error` | Binding this panel failed (its data does not fit its Sutra; an expression failed). The text is the reason, often with a code. | missing | The panel's frame and the error, inline. The rest of the view is unaffected. |
| `denied` | The panel reads another entity (`source:`) the caller may not open: `no access to curve`; `not active (turned off by an administrator)` when the command is off; `hidden for you` and `not available to your role` for a value it follows that is masked. | missing | "No access" in words. Never a blank panel. |
| `empty` | The document lacks what the panel asks for (`Emptiness.of`: no rows, no finite series value, every field `—`, ...). Also `true` with `error` and `denied`. | present, but nothing worth drawing | *No data available* (a `links` panel: *No linked entities*; a table's own words when its Sutra says `empty:`, in `data.emptyLabel`). |

`empty` is always sent (`true` or `false`); `error` and `denied` only when set. A panel with `empty: true` may still carry
`data` (a table's columns, a kv's labels); a renderer may use it, but should show the empty state.

### Area and grid

Without a `layout`, every panel has `area`: `main` (the wide left column) or `right` (the narrow right one), and the panels
flow in `panels` order. Two optional sizes refine this, for either mode:

| Field | Meaning |
|---|---|
| `span` | Width in columns of a 12-column grid (1 to 12); missing = the whole column. |
| `height` | Height in grid rows; missing = as tall as its content. |

With a Sutra `layout:` (SUTRA_NEXT.md 13), the top-level `layout` object carries the grid and `layout.place` says where each
top-level panel goes: `{panelId: {area, order?, section?, collapsible?}}`. `area` there is a name from `layout.areas`
(a panel's `main` or `right` is mapped to the first or last named area). Other keys: `columns` (column widths), `split`
(`{between: [a, b], resizable, remember}`), `tabs`, `sections` (`[{id, title, collapsed}]`), `sticky`, `arrange` (whether the
reader may rearrange), `phone`, `tablet`. A real example from `ScreenGridTest`:

```json
{"areas": ["head head", "body side"], "columns": [8, 4], "arrange": false,
 "place": {"a": {"area": "head"}, "b": {"area": "head"}, "c": {"area": "side", "order": 3, "collapsible": true}}}
```

### Controls and selects

`controls` lists the Sutra's controls (record `ControlView`): `{id, kind, label, placement, panel?, parent?, options: [{value,
label}], value, values?, required, remember, min?, max?, step?, range, reads: [panel ids], truncated}`. `kind` is `dropdown`,
`multi-select`, `cascading`, `toggle`, `segmented`, `date`, `date-range`, `number-range`, `top-n` or `search`; `placement`
is `strip`, `header` (in the header of `panel`) or `side`. A client sends a changed value back as the query parameter
`ctl.<id>=<value>` on the view (or stream) URL, and only the panels in `reads` change. A control never offers a masked
value. `selects` lists `{panel, by, filter}`: a click on a row (or bar) of `panel` sets control `by`; a row carries the
value it sets in `pick`.

### Other panel fields

These belong to Sutra Next features; a basic renderer may ignore them and still draw a correct screen.

| Field | Record | Meaning |
|---|---|---|
| `wire` | `Wire` | Events a click fires (`emits`), `waits` (set while the panel waits on an event: draw that text instead of data), child panel groups (`children`), the `source` kind it reads, `parent` (a child panel's parent). |
| `refresh` | `RefreshView` | `{every, userCanPause}`: fetch the panel again every `every` seconds. |
| `form` | object | The panel's edit form for write-back (fields the viewer may edit, rules, lookups). |
| `provenance` | `PanelProvenance` | Freshness of a panel with its own `source:`: `{source, kind, fetchedAt, updatedAt, staleAfter, stale}`. |
| `compute` | `PanelCompute` | A computed panel's state: `{state: ready\|computing\|stale\|failed\|refused, requestId, connector, message, progress, hasResult, stamp}`. Draw the state in words, over the last result when `hasResult`. |
| `inferred`, `explanation` | | Whether inference built or completed the panel, and why (shown as a tag). |
| `code` | | A short header tag (`REFS`). |
| `key` | | The function key that jumps to it (`F3`). |

---

## 4. Panels

### The common fields

Record `ViewModel.PanelView`. Written with "leave out nulls": `inferred` and `empty` are always sent, the rest when set.

| Field | Type | Sent | Meaning |
|---|---|---|---|
| `id` | string | always | Unique in the view; live `panel` patches replace the panel with this id. |
| `kind` | string | always | One of the 22 kinds below. Decides the shape of `data`. |
| `title` | string | usually | The resolved title. |
| `code`, `key` | string | optional | Header tag; function key. |
| `area` | `main` or `right` | always in practice | The column, when there is no `layout` (see [Area and grid](#area-and-grid)). |
| `span`, `height` | integer | optional | Grid width (of 12) and rows. |
| `inferred` | boolean | always | Built or completed by inference. |
| `explanation` | string | optional | Why inference chose it. |
| `data` | object | when bound | The kind's content, below. Missing with `error` or `denied`. |
| `error`, `denied`, `empty` | | see [above](#empty-error-and-denied) | |
| `wire`, `refresh`, `form`, `provenance`, `compute` | | optional | See [Other panel fields](#other-panel-fields). |

```json
{"id": "refs", "kind": "links", "title": "20 · links: linked entities", "code": "REFS", "area": "right",
 "inferred": false, "empty": true, "height": 10, "data": {"links": []}}
```

### The 22 kinds

Verified against `PanelKind` (`drishti-rachana/.../model/PanelKind.java`) and the binders: the grammar has **twenty-two**
kinds. (Older documents say twenty; `metric` and `map` came later.) Several kinds share one `data` record:

| `data` record | Kinds |
|---|---|
| `Fields` | `kv`, `status`, `provenance` |
| `Table` | `table`, `ladder` |
| `Tabs` | `tabs` |
| `Chart` | `line`, `area` |
| `Bars` | `hbar` |
| `Links` | `links` |
| `Text` | `markdown` |
| `Gauge` | `gauge` |
| `Surface` | `surface` |
| `Waterfall` | `waterfall` |
| `Histogram` | `histogram` |
| `Scatter` | `scatter` |
| `Candles` | `candlestick` |
| `Graph` | `graph` |
| `Timeline` | `timeline` |
| `Pivot` | `pivot` |
| `Metric` | `metric` |
| `GeoMap` | `map` |

What each kind is for, its Sutra options and how it behaves are in [PANELS.md](../guides/PANELS.md); below is only its JSON.
In the tables, *always* means the field is always written (it may be `null` where the type says so).

#### `kv`

`{fields: Cell[]}`: label/value pairs in a grid (the console: four columns in `main`, two in `right`).

```json
{"fields": [{"label": "Coupon", "text": "4.2500%", "path": "$.TERMS.COUPON"},
            {"label": "Frequency", "text": "Semi-annual", "path": "$.TERMS.COUPON_FREQUENCY"}]}
```

#### `table`

| Field | Type | Sent | Meaning |
|---|---|---|---|
| `columns` | string`[]` | always | Header labels. |
| `numeric` | boolean`[]` | always | Per column: right-align it. |
| `rows` | Row`[]` | always | The rows. |
| `total` | Row | optional | The total row (its blank cells are `{}`). |
| `more` | string | optional | `"10 more trades"`: rows left out beyond the Sutra's `limit`. |
| `search` | boolean | always | Offer a filter box. |
| `pivot` | object | optional | The Pivot tab the Sutra opts into: its fields and opening arrangement; rows come from `GET /api/v1/views/{kind}/{id}/panels/{panel}/records`. |
| `expand` | integer | optional | Tree levels open at first (a table with `children`). |
| `levels` | `[{title, columns, numeric}]` | optional | Drill levels: a row opens into the next level's rows. |
| `root` | string | optional | The first breadcrumb step of a drill table. |
| `emptyLabel` | string | optional | The words to show instead of *No data available*. |

A **Row** is `{cells: Cell[], highlight, path, children?, pick?, crumb?, kidsTotal?, ev?}`: `cells` one per column,
`highlight` draw it highlighted, `path` its document path (`null` in a tree's nested rows and the total), `children` nested
rows (a tree), `pick` the value a click sets in a control, `crumb` and `kidsTotal` for drill tables, `ev` the row's key for a
row-click event. Each cell's `label` repeats its column's.

```json
{"columns": ["#", "Event"], "numeric": [false, false],
 "rows": [{"cells": [{"label": "#", "text": "1"}, {"label": "Event", "text": "Booked"}], "highlight": false, "path": "$.LIFECYCLE.EVENTS[0]"},
          {"cells": [{"label": "#", "text": "2"}, {"label": "Event", "text": "Amended"}], "highlight": false, "path": "$.LIFECYCLE.EVENTS[1]"}],
 "search": true}
```

A tree (`children`), from the showcase's `tree` panel:

```json
{"columns": ["Name", "Trades"], "numeric": [false, false],
 "rows": [{"cells": [{"label": "Name", "text": "DESK-FI · Fixed income"}, {"label": "Trades", "text": "5"}], "highlight": false, "path": "$.BOOK_TREE[0]",
           "children": [{"cells": [{"label": "Name", "text": "BOOK-FI-1 · Govies"}, {"label": "Trades", "text": "3"}], "highlight": false, "path": null,
                         "children": [...]}]}],
 "total": {"cells": [{}, {"text": "Total"}], "highlight": false, "path": null}, "search": true, "expand": 1}
```

#### `ladder`

The same record as `table`; a ladder is a dated schedule, and `highlight` marks the current row (the next payment, today's
settlement).

```json
{"columns": ["Pay date", "Rate"], "numeric": [false, true],
 "rows": [{"cells": [{"label": "Pay date", "text": "2025-10-31"}, {"label": "Rate", "text": "4.2500%"}], "highlight": false, "path": "$.SCHEDULE[0]"}],
 "total": {"cells": [{}, {"text": "Total"}], "highlight": false, "path": null}, "search": true}
```

#### `tabs`

`{layout: "tabs" | "columns", tabs: [{title, fields: Cell[], panels?: Panel[]}]}`: one tab per element of a list
(`columns` puts them side by side). A tab's `panels` are whole panels bound against that element, with the same shape as
top-level panels (they are not in `layout.place`).

```json
{"layout": "tabs",
 "tabs": [{"title": "Leg 1 · Pay fixed 3.8500%", "fields": [{"label": "Currency", "text": "USD"}, {"label": "Notional", "text": "50,000,000"}]},
          {"title": "Leg 2 · Receive SOFR compounded", "fields": [{"label": "Currency", "text": "USD"}, {"label": "Notional", "text": "50,000,000"}]}]}
```

#### `line`

| Field | Type | Sent | Meaning |
|---|---|---|---|
| `x` | string`[]` | optional | Axis labels, already formatted. |
| `series` | `[{label, values: (number\|null)[], tone}]` | optional | One or more series, `values` aligned with `x`; `null` is a gap. |
| `mark`, `markText` | string | optional | The `x` label to highlight, and its caption (`5Y point 3.60%`). |
| `limit`, `limitLabel` | number, string | optional | A horizontal limit line. |
| `source` | [Link](#link) | optional | The entity the points came from (a curve); its changes tick this view. |
| `unit` | string | optional | Axis unit. |
| `step` | boolean | optional | Draw as steps (a value holds until the next point). |

```json
{"x": ["1M", "3M"], "series": [{"label": "USD-SOFR curve", "values": [3.93, 3.88], "tone": "link"}],
 "mark": "5Y", "markText": "5Y point 3.60%", "source": {"kind": "curve", "id": "USD-SOFR", "mnemonic": "CRV"}, "unit": "%"}
```

#### `area`

The same record as `line`, drawn filled; usually several series and a `limit`.

```json
{"x": ["1M", "3M"],
 "series": [{"label": "EE", "values": [800000.0, 1400000.0], "tone": "link"}, {"label": "PFE 95", "values": [1900000.0, 3100000.0], "tone": "accent"}],
 "limit": 6500000.0}
```

#### `hbar`

`{bars: [{label, value, text, tone, ev?}], shape?, total?}`: bars scaled to the largest absolute `value`; `text` is what to
print. `shape: "donut"` draws the same bars as a ring of shares, with `total` (formatted) in the middle.

```json
{"bars": [{"label": "3M", "value": -120.0, "text": "−120", "tone": "neg"}, {"label": "1Y", "value": -1450.0, "text": "−1,450", "tone": "neg"}]}
```

#### `links`

`{links: [{label, text, link, badge, status}]}`: the linked entities. `status` is `resolved`, `pending` (not fetched within
the link budget; a later rebuild fills it), `missing` (no source holds it), `denied` (`badge` is `no access`, `link` removed)
or `inactive` (`badge` is `not active`). `badge` is a short summary from the target (`EE 4.1m`), possibly empty.

```json
{"links": [{"label": "Book", "text": "RATES-NY-3", "link": {"kind": "book", "id": "RATES-NY-3", "mnemonic": "BOOK"}, "badge": "", "status": "resolved"},
           {"label": "Netting set", "text": "NS-NORTH-01", "link": {"kind": "netting-set", "id": "NS-NORTH-01", "mnemonic": "NSET"}, "badge": "EE 4.1m", "status": "resolved"}]}
```

#### `status`

The same record as `kv`; the values are statuses toned `ok`, `warn` or `bad`.

```json
{"fields": [{"label": "Confirmation", "text": "Confirmed", "tone": "ok", "path": "$.CONFIRMATION.STATUS"},
            {"label": "Clearing", "text": "Cleared", "tone": "ok", "path": "$.CLEARING.STATUS"}]}
```

#### `provenance`

The same record as `kv`: *How this view was built* as lines (Layout, Fingerprint, Source, Fetched, ...). On Drishti\*net an
administrator also sees the node that answered. The structured facts are in the top-level [`provenance`](#provenance).

```json
{"fields": [{"label": "Layout", "text": "Sutra irs-vanilla v3 + inference"}, {"label": "Fingerprint", "text": "4e09…e011"}]}
```

#### `markdown`

`{text}`: the Sutra's text with its `${...}` parts filled in. Treat it as text (the console prints it as a paragraph,
escaped); do not render it as HTML.

```json
{"text": "A buy USD bond. Every panel reads the document in all-panels-showcase.json."}
```

#### `gauge`

`{value, max, text, label}`, every field always written: a level `value` out of `max`, and `text` to print (formatted from
`value / max`). `value` is `null` when the document lacks it or it is masked (`text` is then `—` or `•••`).

```json
{"value": 89000.0, "max": 250000.0, "text": "36%", "label": null}
```

#### `surface`

`{x, y, z, min?, max?, fmt?, unit?, view?}`: `z[row][column]` over `x` (the columns' labels) and `y` (one label per row),
`null` where the document has no number; `min` and `max` for the colour scale; `view` is `heatmap` (default) or `3d`. The
cell values are numbers; `fmt` names the format the client uses for their labels.

```json
{"x": ["90%", "ATM"], "y": ["X6", "Z6"], "z": [[31.2, 28.52], [30.48, 28.02]], "min": 25.17, "max": 31.2, "view": "3d"}
```

#### `waterfall`

`{steps: [{label, value, from, to, text, tone, total}], unit?}`: floating bars from `from` to `to` (the running total), in
order; `total: true` draws a full bar from zero. `tone`: `ok`/`bad` for rises and falls (`colors: gain-loss`, the default) or
`pos`/`neg` (`colors: theme`), `muted` for totals.

```json
{"steps": [{"label": "Opening MTM", "value": 1250000.0, "from": 0.0, "to": 1250000.0, "text": "+1,250,000", "tone": "ok", "total": false},
           {"label": "Carry", "value": 18400.0, "from": 1250000.0, "to": 1268400.0, "text": "+18,400", "tone": "ok", "total": false}]}
```

#### `histogram`

`{bins: [{from, to, count, label}], markers: [{label, value, text, tone}], count, dropped, fmt?, unit?}`: equal-width bins
computed on the server (`[from, to)`, the last closed), `label` the range formatted; `markers` are vertical lines (VaR,
ES, mean); `count` numbers binned and `dropped` left out (not numbers, or beyond the maximum).

```json
{"bins": [{"from": -1165796.0, "to": -1030570.53, "count": 5, "label": "−1.2m to −1.0m"},
          {"from": -1030570.53, "to": -895345.06, "count": 4, "label": "−1.0m to −895.3k"}],
 "markers": [{"label": "VaR 99%", "value": -1097165.0, "text": "−1.1m", "tone": "neg"}], "count": 300, "dropped": 0, "fmt": "compact"}
```

#### `scatter`

`{points: [{x, y, size?, xText, yText, label?, group?, link?}], groups, xLabel, yLabel, sized, more}`: one point per row;
`groups` are the distinct groups in first-seen order (a colour each); `sized` says whether points carry `size`; `more` points
left out beyond the maximum.

```json
{"points": [{"x": 1486851.0, "y": 37.0, "xText": "1.5m", "yText": "37", "label": "RATES-A"},
            {"x": -4871775.0, "y": 43.0, "xText": "−4.9m", "yText": "43", "label": "RATES-B"}],
 "groups": [], "xLabel": "MTM (USD)", "yLabel": "Trades", "sized": false, "more": 0}
```

#### `candlestick`

`{candles: [{x, open, high, low, close, volume?}], volume, last?, change?, tone?, fmt?, unit?}`: oldest first; `volume`
says whether candles carry volume; `last` (the last close) and `change` (over the last bar) are formatted, `tone` colours
the change.

```json
{"candles": [{"x": "2026-09-01", "open": 74.0, "high": 74.06, "low": 72.97, "close": 73.2, "volume": 93206.0},
             {"x": "2026-09-02", "open": 73.2, "high": 74.1, "low": 72.15, "close": 73.61, "volume": 159955.0}],
 "volume": true, "last": "70.45", "change": "+0.01 (+0.01%)", "tone": "pos", "fmt": "price2"}
```

#### `graph`

`{nodes: [{id, label, group?, link?, focus}], edges: [{from, to, label?}], layout, more}`: `layout` is `tree` (layered from
the roots) or `force`; `focus` marks the node that is this view's entity; an edge to a node not listed is left out; `more`
nodes left out beyond the maximum.

```json
{"nodes": [{"id": "GRP-NORTHBRIDGE", "label": "Northbridge Holdings", "group": "group", "focus": false},
           {"id": "CP-NORTHBRIDGE", "label": "Northbridge Capital LLP", "group": "counterparty",
            "link": {"kind": "counterparty", "id": "CP-NORTHBRIDGE", "mnemonic": "CPTY"}, "focus": false}],
 "edges": [{"from": "GRP-NORTHBRIDGE", "to": "CP-NORTHBRIDGE", "label": "owns"}], "layout": "tree", "more": 0}
```

#### `timeline`

`{events: [{date, label, detail?, status?, tone?}], more}`: dated events, oldest first; `date` as the document writes it;
`tone` (`ok`, `warn`, `bad`) colours the status.

```json
{"events": [{"date": "2026-08-31", "label": "Booked", "detail": "Booked by the rates desk from the broker ticket.", "status": "Done", "tone": "ok"},
            {"date": "2026-08-31", "label": "Confirmed", "detail": "Matched with the counterparty.", "status": "Done", "tone": "ok"}], "more": 0}
```

#### `pivot`

| Field | Type | Sent | Meaning |
|---|---|---|---|
| `by` | string | optional | The row field (the corner heading; `BOOK › FAMILY` when nested). |
| `columns` | string`[]` | optional | Column keys, first-seen order. |
| `rows` | PivotRow`[]` | optional | One per row key. |
| `totals` | Cell`[]` | optional | Column totals, then the grand total; missing when totals are off. |
| `agg` | string | optional | The aggregation (`sum`, `count`, ...). |
| `heat` | boolean | always | Colour cells by value between `min` and `max`. |
| `min`, `max` | number | optional | The heat scale. |
| `more` | integer | always | Row keys left out beyond the maximum. |
| `levels`, `expand`, `across` | | optional | A nested pivot: the row fields outermost first, levels open at first, the column field. |

A **PivotRow** is `{label, cells: Cell[], values: (number|null)[], total?, path?, group?}`: `cells` formatted and toned,
`values` the raw aggregates (for the heat scale and export); in a nested pivot `path` is the keys from the outermost level
down and `group: true` marks a subtotal row.

```json
{"by": "BOOK › FAMILY", "columns": ["USD", "EUR"],
 "rows": [{"label": "RATES-A", "cells": [{"text": "685.8k", "tone": "pos"}, {"text": "801.1k", "tone": "pos"}], "values": [685763.0, 801088.0],
           "total": {"text": "1.5m", "tone": "pos"}, "path": ["RATES-A"], "group": true},
          {"label": "bond", "cells": [{"text": "274.9k", "tone": "pos"}, {"text": "−41.3k", "tone": "neg"}], "values": [274901.0, -41321.0],
           "total": {"text": "233.6k", "tone": "pos"}, "path": ["RATES-A", "bond"], "group": false}],
 "totals": [{"text": "−7.5m", "tone": "neg"}, {"text": "2.0m", "tone": "pos"}], "agg": "sum", "heat": false,
 "min": -2028840.0, "max": 2448996.0, "more": 0, "levels": ["BOOK", "FAMILY"], "expand": 1, "across": "CURRENCY"}
```

#### `metric`

`{value: Cell, delta: Cell|null, unit, caption}`, every field always written: one large figure (`value`, its `label` the
panel's label) with an optional change beside it, a short unit and a caption.

```json
{"value": {"label": "Mark to market", "text": "+1,209,000", "tone": "pos", "emphasis": true, "path": "$.MTM"},
 "delta": {"text": "−41,000", "tone": "neg", "path": "$.PNL1D"}, "unit": "USD", "caption": "change over the last day"}
```

#### `map`

| Field | Type | Meaning |
|---|---|---|
| `mode` | `world` or `region` | The world, or one country's regions. |
| `highlight` | string | The country to highlight, as the document names it (an ISO code or a name). |
| `region` | string | For `region`: the country whose regions are drawn. |
| `link` | `{kind, template, codes?, note?}` | What a click opens: `template` is an id template (`JUR-{iso3}`), `codes` the codes for which an entity exists (others are not clickable), `note` why nothing is clickable (`no access to economy`). |
| `shade` | `{label, unit?, items: [{code, value, text}], note?}` | Per-country figures that colour the map; `note` why it is not shaded (masked, no access, not read in time). |
| `unit` | string | |

All optional. Shapes come from vendored Natural Earth files; nothing is fetched from outside.

```json
{"mode": "world", "highlight": "USA"}
```

---

## 5. Live: the stream and its patches

### The stream

`GET /api/v1/views/{kind}/{id}/stream` (Server-Sent Events, `text/event-stream`) takes the same path, as-of and `ctl.*`
parameters as the view. It sends:

| Event | SSE `id:` | Data |
|---|---|---|
| `view` | `0` | The full ViewModel, exactly as `GET /api/v1/views/{kind}/{id}`. Always first, on every connect and reconnect. |
| `frame` | the frame's `seq` | A **Frame**: `{seq, generation, patches: Patch[], latencyMs, p99Ms}`. |
| comment `:hb` | none | A heartbeat every `drishti.live.heartbeat` (15 s) when nothing else was sent. |

`seq` grows by one per frame on this stream (1, 2, 3, ...); `generation` is the entity's source generation after the frame;
`latencyMs` is source tick to frame for this frame and `p99Ms` the server's rolling p99 (diagnostic). When the view is not
live (`provenance.live` false: a past business date, a static source) the stream sends the `view` event and closes.

### The patches

A frame's `patches` (record `Patch`, from `PatchDiffer` and `ViewStream`):

| `op` | Fields | Apply it by |
|---|---|---|
| `strip` | `index`, `cell` | Replacing strip cell number `index` (0-based) with `cell`. |
| `panel` | `panel` | Replacing the panel whose `id` is `panel.id` with `panel`, a whole [panel](#4-panels). Sent when its `data`, `error` or `title` changed. |
| `provenance` | `provenance` | Replacing the view's `provenance`. Sent when the generation or the layout label moved. |
| `deleted` | `at` (ISO-8601 instant) | Saying the entity was deleted at `at`: keep what is shown, grey it, apply no later patches. |
| `restored` | none | Repainting the whole view (fetch it again): a deleted entity came back; the patches after it bring the view up to date. |

These five are all the ops the code has. A real frame (the showcase after its MTM moved; panels abbreviated):

```json
{"seq": 1, "generation": 2,
 "patches": [{"op": "strip", "index": 3, "cell": {"label": "MTM (USD)", "text": "−1,300,000", "tone": "neg", "emphasis": true, "path": "$.MTM"}},
             {"op": "panel", "panel": {"id": "built", "kind": "provenance", "…": "…"}},
             {"op": "panel", "panel": {"id": "headline", "kind": "metric", "…": "…"}},
             {"op": "provenance", "provenance": {"layout": "Sutra all-panels-showcase v1 + inference", "source": "test", "generation": 2, "live": true, "…": "…"}}],
 "latencyMs": 0.4, "p99Ms": 1.9}
```

### Applying them, in order

- Apply the patches of a frame **in list order**, frames in `seq` order (SSE delivers them in order on one connection).
- Patches carry values exactly as the first view did: the same masks (every rebuild is made as the caller may see it), the
  same control values (the ones in the stream's URL).
- A slow client may receive fewer, fuller frames: the server's one-slot mailbox merges pending frames, newest value per strip
  cell, per panel and per op. A missing `seq` is therefore not an error.
- The view's **structure** never changes through patches: no panel is added, removed or reordered, and the strip keeps its
  length. A Sutra edited while the view is open re-sends every changed panel as `panel` patches. If a client needs a new
  structure (another Sutra, other controls), it opens the view again.
- `deleted` freezes the view; ignore everything until `restored`, then fetch the view again.

### Reconnecting

Nothing is replayed. A client that reconnects receives a fresh `view` event and must repaint from it (the browser's
`EventSource` reconnects by itself; the console reloads the page on a second `view`). The SSE `id` is not used to resume.

The console does not open one stream per page: each browser holds one channel to the console, which relays the server's
streams and adds a rendered `html` field to `panel` patches of non-chart panels ([LIVE.md](LIVE.md#console-the-browsers-channel-get-apichannels)).
That channel is the console's own; API clients use the server stream above.

---

## 6. Entitlements and masks in a view

Everything here is done on the server before the JSON leaves; a client only draws what arrives.

| Where | What the caller sees | Set by |
|---|---|---|
| A masked field (`drishti.security.redact`, callers without `raw`) | `"text": "•••"` wherever the value or anything computed from it appears: strip, title, every panel, totals, a gauge (`value: null`, `text: "•••"`), a pivot cell | `Entitlements.redactor` masks the document before binding |
| The count of masks | `provenance.masked` (how many values) and `provenance.maskedPanels` (which panels), so a page can say "some values are hidden" | `MaskCount.of` over title, strip and every panel's data |
| A `links` item to a kind the caller may not open | `status: "denied"`, `badge: "no access"`, no `link` (`inactive` / `not active` when the command is turned off) | `Entitlements.restrict` |
| A table cell linking to such a kind | the text stays, the `link` is removed (tone `inactive` when the command is off) | `Entitlements.restrict` |
| A function key to such a kind | label ends ` (no access)`, `action: "denied"`, no `link` | `Entitlements.restrict` |
| The title's related entity | `title.with` keeps its text and loses its link | `Entitlements.restrict` |
| A panel whose `source:` is such a kind | `denied: "no access to <kind>"`, `empty: true`, no `data` | `Binder` (`SourceDeniedException`) |
| A control | never offers a masked value as an option | `ControlView` |
| A map's link or shade from such a kind | `link.note` / `shade.note` say why, no codes or items | `MapSupport` |

A masked view, real (`MTM`, `NOTIONAL` and five other fields masked for the caller):

```json
{"strip": [{"label": "Notional", "text": "•••", "path": "$.CURRENCY"},
           {"label": "MTM (USD)", "text": "•••", "emphasis": true, "path": "$.MTM"}],
 "provenance": {"…": "…", "masked": 66, "maskedPanels": ["dv01use", "tree", "grid", "headline"]}}
```

and its gauge: `{"value": null, "max": 250000.0, "text": "•••", "label": null}`. People learn that a linked entity or a value
exists without seeing it. A view of a kind the caller may not open at all is refused (`403 DRS-5002`), not sent masked.

---

## 7. How to use it

### Fetch

```bash
B=http://localhost:18480/api/v1
curl -s $B/views/trade/MX-20000001 | jq -c '{mnemonic, title: .title.id, panels: [.panels[] | {id, kind}]}'
```

- **Who you are.** With security on, `Authorization: Bearer <token>` (a signed JWT or a personal API token; see
  [API_GUIDE, authentication](../guides/API_GUIDE.md#who-you-are-authentication)); with security off, `X-Drishti-User: <name>`
  or nobody (`anonymous`). The answer is masked for that caller.
- **Which day.** `X-Drishti-As-Of: 2026-09-29` or `?asOf=2026-09-29` (a snapshot: `provenance.live` is `false`), and
  `X-Drishti-Known-At` / `?knownAt=` for time travel ([API_GUIDE, business date](../guides/API_GUIDE.md#which-day-the-business-date)).
- **Which layout.** `?sutra=<name>` (another Sutra of the kind) or `?design=<id>` (one of your Designs).
- **Controls.** `?ctl.<id>=<value>`.
- **Errors** are Problem Details with a `code`: `404 DRS-1001` (no such entity), `403 DRS-5002` (may not open the kind),
  `502 DRS-1003`, `504 DRS-1004` ([error code table](../guides/API_GUIDE.md#error-code-table)).

### Stream

```bash
curl -s -N $B/views/trade/MX-20000001/stream
```

The same headers and parameters. See [section 5](#5-live-the-stream-and-its-patches) and [LIVE.md](LIVE.md).

### Preview an unsaved Sutra

`POST /api/v1/studio/preview` with `{yaml, kind, id}` (a real entity) or `{yaml, kind, id, document}` (pasted JSON) answers
a ViewModel built from that Sutra, masked for the caller; `422 DRS-2001`/`DRS-2002` with `problems` when the Sutra is bad.
This is how Sutra Studio and the Build workbench show a draft, and how a renderer can be tested against any Sutra without
saving it.

### A minimal render loop

```text
view = GET /api/v1/views/{kind}/{id}              # or the first "view" event of the stream
paint(view):
    draw title (pill, id, with as a link when with.link)
    for i, cell in view.strip: draw cell (label, text, tone, emphasis, link)
    grid = view.layout or two columns (main, right)
    for panel in view.panels:
        place panel by layout.place[panel.id] or panel.area; size by span and height
        frame = header(panel.title, panel.code, panel.key)
        if panel.wire.waits:   draw text panel.wire.waits
        elif panel.error:      draw error panel.error
        elif panel.denied:     draw "no access" in words
        elif panel.empty:      draw data.emptyLabel or "No data available"
        else:                  RENDERERS.get(panel.kind, fallback)(panel.data)
    draw keys (by action)

stream = SSE /api/v1/views/{kind}/{id}/stream
on "view":  if not first: paint(event.data)       # a reconnect: repaint from scratch
on "frame": for p in frame.patches (in order):
    strip      -> replace strip[p.index] with p.cell
    panel      -> replace the panel with id p.panel.id, re-render it
    provenance -> replace view.provenance
    deleted    -> freeze and grey the view, say when; ignore patches until restored
    restored   -> fetch the view again and repaint
```

`fallback` draws an unknown kind as its title and *This panel kind is not supported by this client* (see
[section 8](#8-compatibility-and-versioning)).

### How the console maps a kind to a renderer

The console is the reference renderer. `drishti-console/web/templates/_macros/panels.html` has one macro per kind (first
paint, server side, Jinja); `pbody(p)` dispatches on `p.kind`, after the `waits`, `empty` and `denied` checks above. Charts
are drawn in the browser by `static/js/charts.js` (ECharts) from the same `data`, embedded in the page.

| Kind | Macro (panels.html) | Drawn by |
|---|---|---|
| `kv`, `status` | `fields(p.data.fields)` | HTML |
| `provenance` | inline `<dl class="prov">` (links the Sutra and the connector) | HTML |
| `table`, `ladder` | `pivotable(p)`, or `drill(p)` when `data.levels` | HTML |
| `tabs` | `tabs(p)` (inner panels through `panel()`) | HTML |
| `line`, `area` | `chart(p)` | charts.js |
| `hbar` | `bars(p.data)`, or `donut(p)` when `shape` is `donut` | HTML / charts.js |
| `surface` | `surface(p)` | charts.js (ECharts GL for `3d`) |
| `links` | `links(p.data)` | HTML |
| `markdown` | `<p>{{ p.data.text }}</p>` | HTML |
| `waterfall`, `histogram`, `scatter`, `candlestick`, `graph` | one macro each | charts.js |
| `timeline` | `timeline(p.data)` | HTML |
| `map` | `geomap(p)` | charts.js |
| `pivot` | `pivot(p.data)` | HTML |
| `metric` | inline | HTML |
| `gauge` | inline (`value / max` as a bar) | HTML |

Live: `static/js/live.js` applies `strip` patches in place (text and tone, then a flash), hands `line` and `area` panels new
data (`drishti.updateChart`), swaps every other panel for the console-rendered `html` the channel adds, and handles
`provenance`, `deleted` and `restored` as in section 5.

Elements (`<drishti-view>`) and the embed API receive HTML rendered by the same macros, not the ViewModel
([ELEMENTS.md](ELEMENTS.md), [ADR-021](adr/021-embedding-is-html-over-the-wire.md)).

---

## 8. Compatibility and versioning

### What exists today

- **No version marker.** A ViewModel carries no schema or contract version; the server's version is at `GET /api/v1/about`.
  The JSON Schema served at `GET /api/v1/viewmodel/schema` is the shape of the server you are talking to.
- **Additive changes only, in practice.** Fields have been added over releases (`controls`, `layout`, `wire`, `form`,
  `compute`, `provenance.masked`, ...) and none renamed or removed. Every addition is optional, so an older client keeps working.
- **Clients must ignore unknown fields.** The console does (Jinja reads only what it names). Do not validate a live server's
  answer with a schema from an older server and `additionalProperties: false`; validate against the schema the same server
  serves.
- **A new panel kind needs a fallback.** `metric` and `map` were added after the first twenty. A client must draw an unknown
  `kind` as a titled panel that says it is not supported, never fail the view.
- **New tones and new words.** A client that meets a tone, `action`, `status` or `op` it does not know should draw the value
  plainly (no tone), skip the key, show the status text as is, and ignore the patch, respectively.

### Proposed (2.9.7)

Not built. For the client-side renderer and the distributable JS/CSS library, which turn this document into a published
contract:

1. **A contract version on every ViewModel and frame**: a top-level `"contract": "1.0"` (major.minor). The minor moves for
   additive changes (a field, a kind, a tone); the major for anything that breaks a client (a rename, a removal, a changed
   meaning). The schema's `$id` would carry the same version (`.../viewmodel-1.0.json`).
2. **The renderer library declares the majors it understands** and refuses (with a clear message) a major it does not know,
   instead of drawing a wrong screen.
3. **A kind registry in the schema**: each kind with the version it appeared in, so a renderer can say "needs 1.3".
4. **Deprecation before removal**: a field to be removed stays for one minor with `"deprecated": true` in the schema.
5. **The 2.9.7 renderer revisits ADR-021's decision** ("no second renderer"): it needs its own ADR, and golden tests that
   compare its output with the console's macros for every kind in the showcase.

---

## 9. The JSON Schema

### Served

`GET /api/v1/viewmodel/schema` (read only, `application/schema+json`, any signed-in caller) answers the JSON Schema
(draft 2020-12) of the ViewModel. Its root is `{"$ref": "#/$defs/ViewModel"}`; `$defs` holds every record: `ViewModel`,
`Cell`, `LinkView`, `PanelView`, `Provenance`, the 18 panel `data` records, their parts, `Tone`, and `Frame` and `Patch` for
the live stream.

```bash
curl -s $B/viewmodel/schema | jq -c '{title, ref: ."$ref", defs: (."$defs" | keys | length)}'
curl -s $B/viewmodel/schema | jq -c '."$defs".PanelView.allOf[0]'
```

```json
{"title":"Drishti ViewModel","ref":"#/$defs/ViewModel","defs":55}
{"if":{"required":["kind"],"properties":{"kind":{"const":"kv"}}},"then":{"properties":{"data":{"anyOf":[{"$ref":"#/$defs/Fields"},{"type":"null"}]}}}}
```

### How it is produced

`ViewModelSchema.build()` (`drishti-engine/.../view/ViewModelSchema.java`) **generates** it from the Java records by
reflection, the way `RachanaSchema` builds the Sutra schema served at `GET /api/v1/rachana/schema`:

- Every component of every record becomes a property; a field added to a record is in the schema at once.
- Each record's Jackson inclusion decides **required** and **nullable**: a component always written is required (and may be
  `null` if it is a reference); one left out when null, empty or default is optional and never `null`. That is why
  `PanelView.empty` is required and `PanelView.error` is not.
- Numbers may be `null`: the server writes NaN and the infinities as `null` (`JsonConfiguration`).
- Every record is closed (`additionalProperties: false`) **for this server**: see [section 8](#what-exists-today) before
  using that against another version.
- Hand-written on top, because types cannot say them: the `data` record of each kind (`DATA_BY_KIND`, an `if`/`then` per
  kind on `PanelView`), the closed word lists (`Patch.op`, `KeyView.action`, `LinkItem.status`, `PanelCompute.state`,
  `Tabs.layout`, `Graph.layout`, `Surface.view`, `GeoMap.mode`), the tones, `PanelView.kind` (from `PanelKind`), and the fields
  the server always sends although the record allows them to be missing (`ViewModel.ref`, `title`, `strip`, `panels`, `keys`,
  `provenance`, `timings`; `PanelView.id`, `kind`; `Patch.op`).

### How it is kept true

`ViewModelSchemaTest` (`drishti-server`) validates real ViewModels, serialised by the server's own `ObjectMapper`, against the
served schema:

- the all-panels showcase (all 22 kinds; the test fails if a kind the schema names is missing from it);
- the reference views `IRS-48213`, `FXS-20931`, `CFT-77120` and `NS-NORTH-01`;
- the chart kinds on a broken document (`ChartPanelsTest`'s), and every kind with nothing to show (`empty: true`);
- a masked view (`provenance.masked` > 0) and a panel denied by its `source`;
- live frames: `strip`, `panel` and `provenance` patches from `PatchDiffer`, and `deleted` and `restored`;
- the schema itself: a `data` record for every `PanelKind` and every `PanelData` record used; and a negative case (an unknown
  field, an unknown kind, a gauge carrying a table's data, rows that are not a list must all fail).

No JSON Schema library is in the build, so the test carries a small validator of exactly the keywords the schema uses
(`$ref`, `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`, `anyOf`, `allOf`, `if`/`then`);
it fails on any keyword it does not know, so the schema cannot outgrow it unnoticed. Any standard draft 2020-12 validator
accepts the same schema.
