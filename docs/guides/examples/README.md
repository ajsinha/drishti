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
# Examples

Twelve small, working Sutras, each with a JSON document to run it against and a note on what it shows. Together they use all twenty-two panel kinds, nested pivot groups and tree rows. Each opens in the workbench as a copy (**Build → New → Examples**, or **File → Open** with its two files) and is listed under **Help → Examples**.

Each example is `<name>.sutra.yaml` (the layout), `<name>.json` (a self-contained document, a few dozen rows at most) and its section of this page, under [The examples](#the-examples) (what it shows, what to look for, which lines to copy; the workbench opens it as the example's notes). The documents are small teaching documents that use the production attribute names in UPPER_SNAKE (`$.TRADE_ID`, `$.POSITIONS`), as the shipped packs, their Sutras and every other guide do; [linked-sources](#linked-sources) reads the packs' own entities. A Sutra reads field names exactly as the document writes them.

| Example | Shows | Panel kinds |
|---|---|---|
| [all-panels-showcase](#all-panels-showcase) | Every panel kind on one screen | all twenty-two |
| [metric](#metric) | Big-number tiles: a toned figure, its change, unit and caption | `metric` |
| [map](#map) | A country on the world map and its own regions | `map`, `kv` |
| [pivot-row-groups](#pivot-row-groups) | Nested row groups with subtotals and expand | `pivot` |
| [tree-table](#tree-table) | Rows that hold child rows | `table`, `ladder` |
| [market-charts](#market-charts) | Prices, closes and a volatility surface | `candlestick`, `line`, `surface` |
| [risk-distribution](#risk-distribution) | VaR histogram, books scatter, limit gauge | `histogram`, `scatter`, `gauge` |
| [pnl-explain](#pnl-explain) | Where P&L came from, and sensitivities | `waterfall`, `hbar`, `kv` |
| [relationships](#relationships) | Group graph, netting-set tabs, linked entities | `graph`, `tabs`, `links` |
| [operations-status](#operations-status) | Status, lifecycle, coupon ladder, notes | `status`, `timeline`, `ladder`, `markdown`, `provenance` |
| [exposure-profile](#exposure-profile) | Exposure over tenor against a limit | `area` |
| [linked-sources](#linked-sources) | Panels that read other entities with `source` (needs the live demo packs) | `tabs`, `area`, `pivot` |

A test (`drishti-console/tests/test_examples.py`; the Java `SutraCliTest` also runs `sutra test` over this folder) checks every example parses, that together they cover all twenty-two kinds, and that they include a nested pivot and a tree table, so the set cannot rot.

Three folders are not Sutra examples: [schemas/](schemas/) holds the JSON Schemas and JSON Lines of [Schema to pack](../BUILD_GUIDE.md#8-a-pack-from-a-json-schema-build--new-pack) (`pack make --schema docs/guides/examples/schemas`), [comswap/](comswap/) two small synthetic exports of deep trading JSON, every value invented by `tools/packgen/comswap_synth.py` (`book-sample.jsonl`, five product types; `comswap-sample.jsonl`, swaps; one deal a line; [A deep trading JSON](../BUILD_GUIDE.md#9-a-deep-trading-json-from-one-file-to-a-deployed-pack)), and [pack/helpdesk/](pack/helpdesk/) is the help-desk pack of the [Pack developer guide](../BUILD_GUIDE.md). The teaching source plugin of the [Connector developer guide](../DEVELOPER_GUIDE.md#1013-a-worked-example-the-dayfolder-plugin) is a Maven module of its own, [examples/connector](../../../examples/connector/) (built and tested by the testkit contracts, not shipped).

## The examples

### All panels showcase

All twenty-two panel kinds on one screen, reading one self-contained bond trade.

**Panel kinds:** `kv`, `STATUS`, `waterfall`, `line`, `hbar`, `gauge`, `ladder`, `TIMELINE`, `table`, `tabs`, `graph`, `area`, `pivot` (nested `BY` list), `scatter`, `histogram`, `candlestick`, `surface`, `provenance`, `markdown`, `links`.

**Open it:** in the workbench, **Build → New → Examples** and pick `all-panels-showcase`: it opens as a copy (this Sutra, the JSON as samples, this note as notes) and a new scratch design opens on it by default. Or **File → Open** in the workbench with `all-panels-showcase.sutra.yaml` and `all-panels-showcase.json`.

**What to look for:** the number in each panel title is its place in the list; the kind is named after it. The `links` panel resolves ids it finds in the document against the live reference catalogue, so it shows entries only where your packs know them.

**Lines to copy:** each panel is a block of its own. Copy one under `panels:` and change its `rows`, `bind` and field names to your document's.

See [RACHANA_REFERENCE.md](../RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one) for what each kind means and when to use it, and [the reference](../RACHANA_REFERENCE.md) for every option.

### Metric

A desk's headline numbers as big-number tiles: one large formatted, toned figure each, with a small change, a unit and a caption.

**Panel kinds:** `metric`, `hbar`.

**Open it:** in the workbench, **Build → New → Examples** and pick `metric`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `metric.sutra.yaml` and `metric.json`.

**What to look for:** the MTM tile shows a signed figure toned by its sign, with the change since yesterday in its own tone beside it; the VaR tile's change is toned `neg` whichever way it moves (a rise in risk is bad); the limit-use tile has a caption built with `${...}`; the last tile is a plain count; the bars below say where the MTM comes from.

**Lines to copy:** `value` (an expression, required), `fmt` and `tone` for the figure, `delta` with `deltaFmt` and `deltaTone` for the change, `unit` and `caption` for the small texts.

### Map

A country on the map: the world with the country highlighted, and the same country's regions (states, provinces, counties) beside it.

**Panel kinds:** `map`, `kv`.

**Open it:** in the workbench, **Build → New → Examples** and pick `map`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `map.sutra.yaml` and `map.json`.

**What to look for:** the left map draws the world with Australia in the accent colour (`highlight: $.ISO3` takes an ISO alpha-3 or alpha-2 code, or the English name); the right map draws Australia's states and territories (`region: $.ISO3`, ISO 3166-2 codes such as `AU-NSW`); small territories are dots so they can still be clicked. Both are drawn from shapes shipped with the console: nothing is fetched from the internet.

**Lines to copy:** `highlight` and `region` (expressions), and, on a server that holds the entities, `link: { kind: jurisdiction, id: "JUR-{iso3}" }` so a click on a country opens its page, and `shade: { kind: economy, field: $.gdpUsdBn, label: "GDP (USD bn)" }` to colour the countries by a figure.

<a id="pivot-row-groups"></a>
### Pivot with row groups

A pivot whose `by` is a list: desks hold books, books hold product families, each level with a subtotal.

**Panel kinds:** `pivot` (three of them: a three-level `by`, a two-level one with `expand: all`, and a flat one with `heat`).

**Open it:** in the workbench, **Build → New → Examples** and pick `pivot-row-groups`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `pivot-row-groups.sutra.yaml` and `pivot-row-groups.json`.

**What to look for:** in the first pivot only the desks show, each with a subtotal and a triangle; click a triangle to open the books, then the families. The second opens every level at once. Heat shading shows on the flat pivot only: nested pivots are not shaded.

**Lines to copy:** `by: [desk, book, family]` for the nesting, `expand: 1` (or `2`, or `all`) for the levels open at first, `across: currency` for the columns.

### Tree table

Tables and ladders whose rows hold their own children: an organisation of units, each with sub-units, to any depth.

**Panel kinds:** `table` and `ladder`, both with `CHILDREN` and `expand`.

**Open it:** in the workbench, **Build → New → Examples** and pick `tree-table`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `tree-table.sutra.yaml` and `tree-table.json`.

**What to look for:** a triangle beside each unit that has children; it opens in place, indented. The table opens to the second level (`expand: 2`), the ladder opens everything (`expand: all`). The `Total` adds up only rows without children, so nothing counts twice. Type in the filter: parents of the rows found stay.

**Lines to copy:** `children: "@.CHILDREN"` (an expression per row, giving the rows nested under it) and `expand`.

### Market charts

Market data: daily bars, a line of closes and a volatility surface.

**Panel kinds:** `candlestick`, `line`, `surface`.

**Open it:** in the workbench, **Build → New → Examples** and pick `market-charts`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `market-charts.sutra.yaml` and `market-charts.json`.

**What to look for:** 30 bars with volume under them (scroll inside the chart to zoom); the surface opens as a 3D plot you can rotate (`view: 3d`; leave it out for a heatmap).

**Lines to copy:** `volume: volume` on a candlestick; `y: month` plus `columns:` (one per point across) on a surface.

### Risk distribution

How risk is spread: scenario P&L as a histogram with VaR lines, books as points, a limit as a dial.

**Panel kinds:** `histogram`, `scatter`, `gauge`.

**Open it:** in the workbench, **Build → New → Examples** and pick `risk-distribution`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `risk-distribution.sutra.yaml` and `risk-distribution.json`.

**What to look for:** dashed marker lines for VaR 99%, ES 97.5% and the mean on the histogram; one labelled point per book on the scatter; the gauge reading usage against the limit.

**Lines to copy:** `markers:` with `value: "-$.VAR99"` (an expression over the document), `x`, `y` and `label` on the scatter, `value` and `max` on the gauge.

<a id="pnl-explain"></a>
### P&L explain

Where a trade's P&L came from, and how sensitive it is.

**Panel kinds:** `waterfall`, `hbar`, `kv`.

**Open it:** in the workbench, **Build → New → Examples** and pick `pnl-explain`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `pnl-explain.sutra.yaml` and `pnl-explain.json`.

**What to look for:** the waterfall steps from the opening MTM through each effect to a closing bar; red and green bars on the DV01 chart (`tone: sign`); signed figures coloured in the key-value panel.

**Lines to copy:** `sum: Closing MTM` (the label of the final total bar) on the waterfall, `tone: sign` on bars and values.

### Relationships

How entities relate: a group and its agreements as a graph, netting sets as tabs, and the entities a document refers to.

**Panel kinds:** `graph`, `tabs`, `links`.

**Open it:** in the workbench, **Build → New → Examples** and pick `relationships`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `relationships.sutra.yaml` and `relationships.json`.

**What to look for:** the group at the top of the graph and the netting sets at the bottom; one tab per netting set, each a small `kv`. The `links` panel resolves ids in the document against the live reference catalogue, so it lists entries only where your packs know them.

**Lines to copy:** `NODES`, `EDGES`, `layout: tree` on the graph; `each`, `tabTitle` and `body` on tabs.

### Operations status

The back office on one screen: where a trade stands and what comes next.

**Panel kinds:** `STATUS`, `TIMELINE`, `ladder`, `markdown`, `provenance`.

**Open it:** in the workbench, **Build → New → Examples** and pick `operations-status`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `operations-status.sutra.yaml` and `operations-status.json`.

**What to look for:** coloured status lights (`tone: status`); the timeline in date order with the pending event in amber; the coupon ladder with the next payment highlighted and a total; the note and provenance in the right-hand column.

**Lines to copy:** `highlight: "#index == $.NEXT_INDEX"` on the ladder, `label` and `detail` on the timeline, `area: right` to put a panel in the side column.

### Exposure profile

Expected and potential future exposure over tenor, against a limit.

**Panel kinds:** `area`.

**Open it:** in the workbench, **Build → New → Examples** and pick `exposure-profile`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `exposure-profile.sutra.yaml` and `exposure-profile.json`.

**What to look for:** two filled series (EE and PFE 95) rising then falling over the tenors, and the limit as a line across them.

**Lines to copy:** `series:` (one entry per measure, each with `label`, `value` and `tone`) and `limit: $.LIMIT`.

### Linked sources

Panels that read other entities with `source: "link(id, kind)"`: the counterparty's netting sets, a netting set's MTM by asset (its exposure is computed by the exposure engine, not stored on the record) and a desk's pivot, all fetched live.

**This example is not self-contained.** Its JSON names the entities (`CP-SUMMIT`, `NS-SUMMIT-NY`, `DESK-FI`), and the panels read those from a running server with the demo packs (`counterparty-risk`, `market-risk`). Without them the panels say they are waiting for the entity.

**Panel kinds:** `tabs`, `area`, `pivot`, each with `source`.

**Open it:** in the workbench, **Build → New → Examples** and pick `linked-sources`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `linked-sources.sutra.yaml` and `linked-sources.json`.

**What to look for:** the panels' data does not come from the trade's own document; change `desk` in the JSON and the pivot follows.

**Lines to copy:** `source: "link($.desk, 'desk')"` (an expression giving the entity to read), then write `rows`, `each` or the other options as if the linked document were the page's own.
