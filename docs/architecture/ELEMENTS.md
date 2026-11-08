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

# Drishti Elements: the console's JavaScript as eight reusable libraries

**Status: being built** (release 2.9.11.e, product owner 2026-11-07; mock-ups g1 to g4 below, all decided; g5 and g6 decided and built (8.8), g7 decided and built (8.9), section 16; as built: 8.1 to 8.10). The internal design:
what each library is, what it takes over from today's code, the grid's pivot, the AG Grid-style adapter, the chart spec and the maps,
packaging and licensing, the documentation of every library, the gallery, the guards, the order in which Drishti's screens move, the
risks and the product owner's decisions. The renderer, the embed API and Elements 2.x (`<drishti-view>`) are designed in
[CLIENT_RENDERER.md](CLIENT_RENDERER.md) and are not repeated here.

## Contents

1. [Why: one implementation of each thing](#1-why-one-implementation-of-each-thing)
2. [The eight libraries](#2-the-eight-libraries)
3. [The rules every library keeps](#3-the-rules-every-library-keeps)
4. [Each library: purpose, API sketch, what it takes over](#4-each-library-purpose-api-sketch-what-it-takes-over)
5. [drishti-grid and its pivot](#5-drishti-grid-and-its-pivot)
6. [The AG Grid-style adapter](#6-the-ag-grid-style-adapter)
7. [drishti-chart: one spec, the kinds, the maps](#7-drishti-chart-one-spec-the-kinds-the-maps)
8. [Packaging, versions and licensing](#8-packaging-versions-and-licensing)
9. [The documentation of every library](#9-the-documentation-of-every-library)
10. [The gallery](#10-the-gallery)
11. [The guards](#11-the-guards)
12. [Moving Drishti's screens onto the libraries](#12-moving-drishtis-screens-onto-the-libraries)
13. [Risks](#13-risks)
14. [Decisions of the product owner (2026-11-07)](#14-decisions-of-the-product-owner-2026-11-07)
15. [Build checklist](#15-build-checklist)
16. [Proposed: drishti-graph, drishti-tree and the designer kit (mock-ups g5, g6, g7), for the owner's choice](#16-proposed-drishti-graph-drishti-tree-and-the-designer-kit-mock-ups-g5-g6-g7-for-the-owners-choice)

## 1. Why: one implementation of each thing

The console's browser code grew screen by screen. Measured on develop (2026-11-07):

| What | Today |
|---|---|
| Console scripts (`drishti-console/web/static/js/`, with `js/build/`) | 239 files, 58,864 lines |
| The renderer (`static/render/1.2.0/`, without its playground) | 19 files, 3,184 lines |
| Grid implementations | about 10: `drishti_tables.js` with `drishti_colfilter.js`, `drishti_tree-rows.js`, `drishti_drill.js`, `drishti_pivot-grid.js` (with the field zones of `drishti_pivot.js`), `drishti_bi-grids.js` with `drishti_bi-matrix.js`, `drishti_designer-grid.js`, the basket's sheet (`drishti_var-basket-sheet.js`), the playground's windowed grid (`drishti_var-playground.js`), the raw viewer's tree, the renderer's `m/tables.js` |
| Scripts that draw charts themselves | 22: 16 console scripts called ECharts or Vega directly (`drishti_view.js`, `drishti_charts.js`, `drishti_bi-visuals.js`, the BI Vega script, `drishti_table-chart.js`, `drishti_pivot.js`, `drishti_geomap.js` and nine more), the renderer's `m/charts.js` and `m/draw.js`, and four draw SVG by hand (`drishti_var-charts.js`, `drishti_exposure-charts.js`, `drishti_var-backtesting.js`, `drishti_stress-window.js`) |
| Number formatters | at least six (`drishti_bi-lib.js`, `drishti_pack-ui.js`, `drishti_var-charts.js`, `drishti_exposure-charts.js`, the renderer's `m/format.js`, the pivot grid) |

Each copy has its own keyboard, its own accessibility, its own bugs and its own look. **Drishti Elements ends that**: every screen is
built from eight libraries, the old code is deleted as each screen moves (no dual machinery), and a guard stops a second copy from
growing back (section 11). The same libraries are usable outside Drishti: the product owner's other applications use AG Grid Enterprise
with heavy pivoting, and libraries 1 to 5 must serve them without a Drishti server.

## 2. The eight libraries

| # | Library | What it is | Server | Takes over today (measured: files, lines) | Expected size |
|---|---|---|---|---|---|
| 1 | **drishti-core** | formats, theme tokens, API client, events, accessibility helpers, resize, keymap, dialogs and menus | not needed | 18 files (12 whole, 6 in part), about 2,160 lines | 1,300 deduplicated; about 1,900 with the new parts |
| 2 | **drishti-inputs** | pickers (date and range, multi-select, people), forms and their rules, the filter bar, the code editor, the formula input | not needed | 15 files (11 whole, 4 in part), about 2,830 lines | 1,800; about 3,300 |
| 3 | **drishti-grid** | the grid with the pivot UI, tree data, ranges, editing, export, the AG Grid-style adapter | not needed (server-side rows optional) | 16 files (9 whole, 7 in part), about 3,760 lines | 1,700; about 7,700 (the most new code) |
| 4 | **drishti-chart** | one engine-neutral chart spec over ECharts, the general kinds, the maps | not needed | 23 files (7 whole, 16 in part), about 3,980 lines | 2,000; about 3,500 |
| 5 | **drishti-data** | data sources (local, REST, Drishti's pivot and rows), Arrow tables, live updates over one connection | not needed (talks to any server) | 9 files (5 whole, 4 in part), about 1,570 lines | 1,100; about 1,700 |
| 6 | **drishti-insight** | Explain, search, approvals, workbook export | a Drishti server | 9 files (6 whole, 3 in part), about 1,380 lines | 1,000; about 1,300 |
| 7 | **drishti-renderer** | Drishti screens from a (streamed) ViewModel, `<drishti-view>`, the layout grid | a Drishti server | 21 files (14 whole, 7 in part), about 4,250 lines | 3,600; about 3,000 once it draws with libraries 1 to 5 |
| 8 | **drishti-risk** | the banking module's chart kinds and panels (P&L distribution, backtest, exposure profile, VaR panels) | a Drishti server with the banking module; **licensed** | 8 files (2 whole, 6 in part), about 1,060 lines | 600; about 1,100 |
| 9 | **drishti-designer** | the designer kit (added 2026-11-07, section 8.9): the layout canvas over a layout model, the palette, the inspector, the outline, the YAML pane, undo and redo, the designer shell | not needed | the three canvases, the inspectors and panes, the two YAML editors, the outlines and the editor shell of every designer: about 8,800 lines | about 3,900 (as built: 4,750 lines of modules with their JSDoc) |
| | **All eight** | | | **116 files touched, about 21,000 lines folded** (17,800 from the console's scripts, 3,200 from the renderer) | **about 13,100 deduplicated; about 23,500 with the commercial feature set** |

How the numbers were taken: every `drishti_*.js` and renderer module was read and assigned to a library, wholly or in part (a share
estimated from its sections); "expected" applies a deduplication factor per library (the grid's ten copies fold hardest) and then adds
the new capability sections 5 to 7 call for. **After the move** the console keeps about 190 scripts of screen logic (49 scripts move
whole, 58,864 lines become about 38,000) and every table, chart, picker and format comes from a library. The inventory is regenerated
by the build (`tools/elements/inventory.py`, planned, section 15) so these figures are checked, not trusted.

**Dependencies.** Each library depends only on **drishti-core** (and on its vendored engine: ECharts for drishti-chart, CodeMirror for
the code editor, Arrow JS for drishti-data). drishti-insight and drishti-renderer may use libraries 1 to 5; drishti-risk uses
drishti-chart and drishti-grid. Nothing depends on drishti-risk, and no library depends on the console.

```
drishti-core ◄── inputs, grid, chart, data            (1 to 5: no server needed)
     ▲               ▲      ▲      ▲
     └── insight, renderer ─┴──────┘                   (6, 7: a Drishti server)
                     ▲
                 drishti-risk                          (8: the banking module, licensed)
```

**Two more generic libraries** (2.9.11.e, the owner's picks of g5 and g6): **drishti-graph** (networks on a canvas) and **drishti-tree**
(hierarchies), each on drishti-core and drishti-grid (their tables are grids); as built: section 8.8.

**Domain libraries.** Each later domain adds its own library, licensed with its module and built like drishti-risk: drishti-genomics,
drishti-public-sector, drishti-aml. A domain library may use libraries 1 to 7 and never another domain's.

## 3. The rules every library keeps

### 3.1 Standing rule: no bespoke UI

**The product owner, 2026-11-07, standing.** No screen, designer, editor or authoring tool uses bespoke UI code. Everything a person
uses to create or change an artefact (Sutras, reports, boards, forms, tables, models, Python items, functions, alerts and rules, packs,
connectors, lake files, lenses, editions, settings, Notebook cells, workflows, approvals' change views) is assembled from Drishti
Elements. A need the libraries do not meet is met by **extending a library** (generic in the generic libraries, domain-specific in the
domain's library), never by code in the screen. `test_designer_kit_guard.py` holds it for what designers and editors are made of (a
drag or pointer layout, a code or YAML editor, a tree view, an undo history), with an allow-list of the files still being moved that
only shrinks and is capped; section 12.3 lists every authoring screen and its state.

### 3.2 What every library keeps

- **No framework required.** Each component is a **web component** (`<drishti-grid>`, `<drishti-chart>`, …) and a **plain JavaScript
  class** with the same options (`new Grid(el, options)`); React, Angular and Vue use them as they use any custom element (the samples of
  [CLIENT_RENDERER.md section 19](CLIENT_RENDERER.md#19-drishti-elements-developer-guide) extend to every library).
- **Plain ES modules, no build step**, readable as served; one entry per library plus its stylesheet, a `.d.ts` and an integrity record
  ([the renderer's shape](CLIENT_RENDERER.md#3-the-librarys-shape) and [versioning](CLIENT_RENDERER.md#13-the-elements-versioning-and-distribution) apply to all eight).
- **Vendored, never a CDN**; every file of ours carries the `drishti_` prefix inside the console and the library name outside it.
- **Files under 1,500 lines**; object-oriented, configuration-driven, no globals beyond one namespace (`window.Drishti.<Library>` for
  hosts without modules).
- **The theme is CSS custom properties** from drishti-core's tokens; light and dark, three **densities** (comfortable, compact,
  terminal: the product owner's decision on g1, 2026-11-07) shared by every component.
- **Accessibility is part of the component**: roles, names, keyboard, focus, announcements, contrast (WCAG 2.2 AA); axe runs on every
  gallery page.
- **The browser computes no figure Drishti owns.** Inside the console every total comes from the server
  (`test_console_does_not_aggregate.py`); the grid's client-side aggregation exists for standalone use and is off in the console (5.3).
- **One chrome**: the same toolbar, menus, status bar, Explain entry and export menu on the grid, the chart and the map (g1 to g3).

## 4. Each library: purpose, API sketch, what it takes over

### 4.1 drishti-core

Formats (numbers, money, percentages, dates, durations, "3 min ago"; the server's rounding), theme tokens and the theme switch, the API
client (fetch with the person's token, DRS problems with their fix, cancellation, retries), a typed event bus, accessibility helpers
(live region, focus trap, roving tab index), the one resize component, the keymap (one registry, conflicts refused, Help lists it), dialogs,
menus, toasts and popovers.

```js
import { format, theme, api, keys, dialog } from 'drishti-core';
format(-30205543.2, { style: 'compact' });          // "−30.21m"
theme.set({ mode: 'dark', density: 'terminal' });
keys.register('grid', { 'E': explainCell, 'Ctrl+C': copyRange });
const ok = await dialog.confirm({ title: 'Delete the basket?', danger: true });
```

#### Design tokens

drishti-core's tokens are one file, `static/css/drishti_tokens.css`, the only home of every colour and scale the console and the
libraries draw with. `tools/elements/build.py` generates the themes' table (`drishti_theme-table.js`) from it, and the embedded view,
report and renderer sheets (`tools/elements_sheet.py`, `tools/render_sheet.py`) write its fixed values into their rules.

| Family | Tokens | What they hold |
|---|---|---|
| Theme colours | `--d-bg` … `--d-bad`, `--d-fill`, `--d-grad-*`, `--d-nav-*` | one block per theme (`data-theme` on `<html>`: terminal, light, blue, green, wallstreet, crimson, crimson-dark, terminal-green, terminal-crimson, terminal-blue), the light fallback, and the print palette last; text colours read at 4.5:1 on every ground (`test_contrast.py`) |
| Semantic hues | `--d-market-violet`, `--d-trading-green`, `--d-world-teal`, `--d-cap-*` | a screen's own hue, with a dark and a light value per theme |
| Fixed colours | `--d-on-color`, `--d-paper`, `--d-on-band`, `--d-band-*`, `--d-scrim*`, `--d-swatch-*`, … | the same in every theme: white labels on coloured fills, the navigation band, scrims under dialogs, the theme menu's swatches |
| Elevation | `--d-shadow` (per theme), `--d-elev-*` | shadows by role: paper, raised, bar, pop, toast, panel, list, menu, sheet, dialog, drawer, mega menu |
| Type | `--d-fs-<px>`, `--d-fs-em-<percent>`, `--d-fs-display-<n>` | absolute steps named by their size in px at the default 16px and written in rem (so the console follows the reader's font size); relative steps; fluid display sizes |
| Spacing | `--d-sp-<px>` | padding, margin and gap steps up to 2rem; larger offsets are layout |
| Radius | `--d-radius` (cards and panels), `--d-radius-<px>`, `--d-radius-pill` | corner radii |
| Layers | `--d-z-bar` … `--d-z-confirm` | the stacking order of what floats over the page (a z-index under 100 orders things inside one component) |
| Breakpoints | `--d-bp-*` | the only widths an `@media` may use (a media query cannot read a custom property, so it repeats the number) |
| Densities | `--d-density-data-*`, `--d-density-control-*` | the base text of the three densities (comfortable, compact, terminal) for data (grid, chart) and controls (inputs) |

The steps are the sizes the screens use; two are folded into one only under the visual check (`tools/dev/css_visual_check.py`:
screenshots of a set of screens in every theme, before and after, compared pixel by pixel). `test_css_tokens.py` keeps the console's CSS
on the tokens: no colour literal outside this file, font sizes only from the type scale, `@media` widths only from the breakpoints, no
undefined token, and `!important` only on an allow-list that gives each one its reason (print, reduced motion, a position a script
writes inline, utilities) and only shrinks.

Takes over: `drishti_app.js`, `drishti_theme-init.js`, `drishti_resize.js`, `drishti_settled.js`, `drishti_pack-ui.js`,
`build/drishti_drag.js`, `build/drishti_menus.js`, the renderer's `m/dom.js`, `m/format.js`, `m/net.js`, `m/signin.js`, `m/assets.js`;
the formats and JSON calls of `drishti_bi-lib.js`, and the dialogs of `drishti_busy.js`, `drishti_scope-delete.js`,
`drishti_artifact-uninstall.js`, `drishti_server-watch.js`, `build/drishti_grid-keys.js`.

### 4.2 drishti-inputs

`<drishti-select>` (one value from a list: keyboard, type-ahead, search on a long list), `<drishti-multiselect>`, `<drishti-date>` (date,
range, relative ranges, business dates), `<drishti-people>`, `<drishti-form>` (fields
from a JSON schema, rules evaluated as people type, drafts), `<drishti-filter-bar>`, `<drishti-code>` (CodeMirror with YAML, Python,
SQL and Rachana modes, completion from a schema), `<drishti-formula>` (Rachana with completion and the red part of a formula that does
not read).

Takes over: `drishti_multiselect.js`, `drishti_people.js`, `drishti_controls.js`, `drishti_form-expr.js`, `drishti_editor-formula.js`,
`drishti_bi-expr-editor.js`, `build/drishti_expr.js` (the YAML editors went to drishti-designer's YamlPane over this library's CodeEditor, 8.9),
`build/drishti_forms.js`; the inputs of `drishti_form.js`, `drishti_bi-format-pane.js`, `drishti_subscribe-when.js`,
`drishti_discussion-compose.js`.

### 4.3 drishti-grid

Section 5. Takes over: `drishti_tables.js`, `drishti_colfilter.js`, `drishti_tree-rows.js`, `drishti_drill.js`, `drishti_pivot-grid.js`,
`drishti_bi-grids.js`, `drishti_bi-matrix.js`, the renderer's `m/tables.js` and `m/cells.js`; the grids inside `drishti_pivot.js`,
`drishti_designer-grid.js`, `drishti_var-basket-sheet.js`, `drishti_var-playground.js`, `drishti_raw-viewer.js`,
`drishti_editor-table.js` and the renderer's `m/behave.js`.

### 4.4 drishti-chart

Section 7. Takes over: `drishti_charts.js`, `drishti_geomap.js`, the BI Vega script (retired: Vega-Lite is translated), `drishti_bi-multiples.js`,
`drishti_table-chart-engine.js`, the renderer's `m/charts.js` and `m/draw.js`; the chart code of `drishti_bi-visuals.js`,
`drishti_bi-visuals-format.js`, `drishti_table-chart.js`, `drishti_table-chart-sources.js`, `drishti_view.js`, `drishti_history.js`,
`drishti_history-page.js`, `drishti_bi-python.js`, `drishti_bi-ai.js`, `drishti_calc.js`, `drishti_zoom.js`, the BI designer's canvas
and panes, `drishti_bi-extras.js`, `drishti_bi-notebook-cells.js`.

### 4.5 drishti-data

```js
import { source, arrow, live } from 'drishti-data';
const s = source.rest('/api/v1/rows/positions');      // or source.local(rows), source.drishtiPivot(server, view)
const t = await arrow.fetch('/api/v1/rows/positions'); // column-oriented, Arrow where the server can, JSON otherwise
live.subscribe('vm:pivot/42', patch => grid.apply(patch));   // one connection per browser
```

A **data source** answers windowed, grouped, pivoted, sorted and filtered requests; the grid's server-side row model and the chart ask
it the same way. Takes over: `drishti_channel.js`, `drishti_live-hub.js`, `drishti_live.js`, `drishti_table.js` (Arrow),
`drishti_pivot-engine.js`; the freshness of `drishti_refresh.js`, the live part of `drishti_bi-live.js`, the file streaming of
`drishti_pack-read.js` and `drishti_pack-profile.js`.

### 4.6 drishti-insight

`<drishti-explain>` (the one Explain: a cell, a mark, a figure, a change between two dates), `<drishti-search>` (the one search),
`<drishti-approvals>` (the queue and a review), `workbook.export()` (the server's workbook with formulas and a Provenance sheet). It
packages the components the one-Explain, one-search and one-Approvals work finished ([SHARED_SCREENS.md](SHARED_SCREENS.md)); it adds
no second implementation. Takes over: `drishti_about.js`, `drishti_about-hints.js`, `drishti_about-ask.js`, `drishti_command.js`,
`drishti_bi-export.js`, the renderer's `m/about.js`; the Why card of `drishti_pivot-functions.js`, the review panel of
`drishti_bi-govern.js`, the dialog of `drishti_share.js`.

### 4.7 drishti-renderer

The renderer of [CLIENT_RENDERER.md](CLIENT_RENDERER.md) becomes library 7 (renderer 2.0.0): it draws panels with drishti-grid and
drishti-chart instead of its own `m/tables.js` and `m/charts.js`, and `behaviours: 'console'` loads library components instead of console
scripts. Elements 2.x (`<drishti-view>`, `<drishti-panel>`, `<drishti-report>`) move with it unchanged; the name **Drishti Elements**
now means the family of eight. Takes over: the renderer's entry and `m/` modules, `drishti_screen-grid.js`, `drishti_layout.js`,
`drishti_layout-rules.js`, `drishti_sutra-wire.js`, `drishti_compute.js`, `drishti_bi-frame.js`; the screen parts of `drishti_view.js`,
`drishti_zoom.js`, `drishti_record-forms.js`, `drishti_phone.js`, `drishti_bi-report.js` (the one page renderer), `drishti_bi-phone.js`.

### 4.8 drishti-risk

Chart kinds `risk.pnl-distribution`, `risk.backtest` (with the traffic light), `risk.exposure-profile`, `risk.var-change` (the
waterfall of what moved), `risk.stress-window`; panels for the VaR and exposure explorers; grid presets (the MR cube's columns). Served
only with the banking module and only to people whose role or lens uses it. Takes over: `drishti_var-charts.js`,
`drishti_exposure-charts.js`; the charts of `drishti_var-backtesting.js` and `drishti_stress-window.js`; the drawing parts of
`drishti_scenario-matrix.js`, `drishti_frtb-sa.js`, `drishti_simm.js`, `drishti_mr-lens.js`.

## 5. drishti-grid and its pivot

### 5.1 Three layouts of one grid (g1, product owner 2026-11-07)

[g1](mockups/elements/g1-pivot-grid.html): the product owner liked all three options, "they show different capabilities", so
**drishti-grid ships all three as layouts of one component**, chosen per screen or application with `layout`. They share the grid,
the pivot model, the menus, the status bar and Explain; only the arrangement around the grid differs.

| `layout` | Mock-up | Arrangement | Best at | Default for |
|---|---|---|---|---|
| `'wells'` | A, ribbon wells | the field list (with search) and the four wells (Filters, Rows, Columns, Values) in one strip above the grid; Explain in a drawer on the right that follows the selection | the most room for the grid; quick re-pivoting by drag; Explain read beside many cells | every screen that is not VaR (BI, Pivot, tables) |
| `'panel'` | B, tool panel | a side panel with the grouped, searchable field list (checkboxes) and the wells in a 2×2 block, pivot mode, subtotal and grand-total switches, deferred update; Explain as a card at the cell | building and changing pivots all day; long field lists; people and applications coming from AG Grid's or Excel's pivot panel | the AG Grid adapter (`sideBar` maps to it) |
| `'terminal'` | C, terminal | a command line, one lane of compact wells, a dense grid, Explain in a split pane below with its steps, the worst scenarios and the trend side by side, function keys at the foot | the keyboard (F2 explain, F3 and F4 expand and collapse, F7 chart, F8 export); a figure, its scenarios and its trend at once; traders | **VaR screens** (the VaR explorer, the MR cube, the scenario matrix) |

**Density** (comfortable, compact, terminal) is a separate setting from drishti-core, shared by every component; the terminal layout
does not force the terminal density, and any layout takes any density.

### 5.2 Features (professional, not a clone)

| Area | What drishti-grid does |
|---|---|
| Scale | virtual rows and columns (millions of rows, thousands of columns), row height fixed per density; server-side rows in windows with prefetch and cancellation |
| Columns | pinning left and right, reorder by drag, resize, autosize, hide and show, column groups, header menus (sort, filter, hide, pin, autosize, aggregation, Explain) |
| Rows | sorting (multi-column), Excel-style column filters and a quick filter, value filters on measures, row selection with checkboxes, tree data, grouping |
| Pivot | section 5.3 |
| Cells | cell ranges (drag, Shift+click, Shift+arrows), the status bar's count, sum, average, min and max of a range (and a warning when a range mixes measures or sums a non-additive one), copy and paste as text, editing with validation, conditional formatting (colour scales, data bars, rules), Explain on any cell |
| Menus | a context menu (Explain, Why did it move, copy, copy with headers, export, chart the range, drill to the rows, group by, pin, comment) and the column menu; both extendable by the host |
| Export | CSV and Excel from the browser for standalone use (an Excel writer in the library, values and outline); inside Drishti the server's export (values, outline, formulas where right, Provenance sheet) |
| Keyboard and readers | arrows, Home and End, Page keys, Enter to expand or edit, E or F2 to explain, Ctrl+C, Shift+F10; `role="grid"` or `treegrid`, `aria-rowcount` and `aria-colcount` for virtual rows, `aria-level`, `aria-expanded`, `aria-sort`, announcements of sort and filter changes |
| Look | Drishti's own: the theme tokens, three densities, light and dark, the shared chrome; three layouts (5.1); no copied styling |

### 5.3 The pivot

**The model.** A pivot is a view: `rows` (dimensions, in order), `columns` (dimensions, in order), `values` (a measure with its
aggregation), `filters` (members of a dimension, and value filters such as "VaR 99% > 1m" or "top 5 by VaR"), `totals` (grand total
and subtotals, before or after), `expanded` (which row and column groups are open). It is the pivot engine's view
([PIVOT_ENGINE.md section 4](PIVOT_ENGINE.md#4-the-pivot-model)); the grid adds nothing to its meaning.

**What the grid draws.** Row groups with + and − (lazy: a group's children are asked when it opens), **pivot result columns** with
collapsible column groups (a collapsed group shows its own total), totals and subtotals in the server's order, a grand total row and
column (pinnable), the measure's name in the leaf header, ≈ beside an approximate figure and ◆ on a non-additive measure.

**Where totals come from.** Two row models:

- **Server-side** (Drishti, and any server that answers the request below): the grid sends the view and the window it needs; the
  server answers rows in display order with every subtotal and total already computed. This is the only model the console uses.
  **Non-additive measures (VaR, ES, ratios, distinct counts, percentiles) are computed by Drishti's server for each total**, so a
  total is never the sum of its rows; the hover note and Explain say so (g1).
- **Client-side** (standalone pages with their rows in memory): the grid aggregates with built-in functions (sum, count, average, min,
  max, first, last, distinct count) and functions the host registers. A measure marked `aggregate: 'server'` shows no client total
  unless the host gives a function that computes it from the group's rows (for example a percentile of summed vectors); otherwise the
  cell says that it needs the server. In the console the client-side model is refused by policy (section 11).

**The request** (server-side, one shape for rows, groups and pivots):

```json
{ "view": { "rows": ["desk", "book"], "columns": ["region", "currency"], "values": [{"measure": "mtm", "agg": "sum"}, {"measure": "var", "agg": "var99_1d"}],
            "filters": {"trader": {"not": ["L. Duval"]}}, "valueFilters": [{"measure": "var", "op": ">", "value": 400000, "level": "leaf"}],
            "totals": {"rows": "after", "columns": "after", "grand": true} },
  "groupKeys": ["Rates"], "window": {"start": 0, "end": 200}, "sort": [{"by": "var", "column": {"region": "EMEA"}, "dir": "desc"}],
  "expandedColumns": ["Americas", "EMEA"] }
```

The answer is the pivot engine's answer ([PIVOT_ENGINE.md 4.4](PIVOT_ENGINE.md#44-the-answer)): rows in display order, the
pivot result column keys, cells with values, texts and tones, totals, approximation marks, the row count. drishti-data's
`source.drishtiPivot()` speaks it; a host's own server may answer the same JSON, or the AG-style request (section 6).

**Large data.** Windows of rows, lazily opened groups, column windows when the pivot is wide, a progressive answer (≈ first, exact
when ready) from the engine, cancellation when the view changes; the browser holds only what is in view and a margin.

## 6. The AG Grid-style adapter

**Purpose.** An application written for AG Grid Enterprise's pivoting moves to drishti-grid by keeping its configuration:
`createGridFromAgOptions(el, gridOptions)` takes the **option shapes** of AG Grid's row grouping, pivoting, server-side row model and
tree data (O5) and builds a drishti-grid. **Drishti never uses AG Grid**: no AG Grid code, package, CSS or look anywhere. **Clean room:
written from AG Grid's public documentation only, never its source code, type files, styling, class names or examples.** The result
looks and behaves like drishti-grid. Every option not taken in full is reported once in the console (named, with drishti-grid's way) and
by `reportUnsupported()`; the adapter never fails silently.

**As built** (2.9.11.e, drishti-grid 0.4.0; the migration guide: [AG_GRID_MIGRATION_GUIDE.md](../guides/AG_GRID_MIGRATION_GUIDE.md)):

- **The mapping's one home is the code**: `AG_MAPPING` and `AG_NOT_COVERED` in `drishti_grid-ag-map.js`, each row an option group with
  its state (mapped, partial, unsupported, no-op) and its drishti-grid form. The gallery's AG Grid tab and the migration guide's option
  table are generated from it (`tools/docs/elements_api.py`, `--check`); a Node test keeps the run-time option lists (`AG_GRID_OPTIONS`,
  `AG_COLUMN_KEYS`, `AG_UNSUPPORTED`, `AG_NO_OP`, `AG_EVENTS`) in step with it.
- **The translation** (`drishti_grid-ag.js`, `translateAgOptions`): column definitions with `defaultColDef`, `columnTypes` and column
  groups become drishti-grid's columns (`valueGetter`, `valueFormatter`, `comparator`, `cellClass`, `cellClassRules` and `cellStyle` as
  functions; the type from `cellDataType`, `numericColumn` or the first row); `rowGroup`, `pivot` and `aggFunc` with their indexes become
  the wells; `aggFunc` functions and `aggFuncs` names are registered (`registerAggFunc`, forgotten with the grid), and
  `'drishti:server'` (or `'drishti:server:var(99, 1d)'`) marks a non-additive measure whose every total is the server's; the totals
  (`grandTotalRow`, `groupTotalRow`, `pivotRowTotals`, `pivotColumnGroupTotals`), the open levels (`groupDefaultExpanded`,
  `pivotDefaultExpanded`), `suppressAggFuncInHeader`, `rowSelection`, `sideBar` (the `panel` layout), `rowHeight` (the nearest density)
  and `domLayout: 'autoHeight'`. Rows that group get the adapter's group column, headed by `autoGroupColumnDef` (a `system` column: never
  in the field list). drishti-grid's own options go under the one added key `drishti`.
- **The server side** (`drishti_grid-ag-server.js`): `serverSideDatasource.getRows(params)` becomes the `ServerRowModel`'s datasource
  (the request with AG's column objects and filter model; `success`, `fail` and the older callbacks; abort); in pivot mode it is also
  the grid's `serverPivot`: each open level asked whole, the rows and `pivotResultFields` (split by
  `serverSidePivotResultFieldSeparator`) made into the pivot answer, the grand total and the row totals asked of the server too, so a
  non-additive measure is right at every level. `rowModelType: 'infinite'` maps onto the same model without groups.
- **The handle** (`drishti_grid-ag-api.js`, `AgGridApi`; its filter, column and pivot calls in `drishti_grid-ag-columns.js`, its events in `drishti_grid-ag-events.js`): `setGridOption`,
  `updateGridOptions`, `getGridOption`, `applyTransaction(Async)`, `getSelectedRows` and nodes, `forEachNode` and its kin,
  `getDisplayedRowAtIndex`, `getRowNode`, CSV and Excel (`exportDataAs…`, `getDataAs…`), `setFilterModel` and `getFilterModel` (translated
  both ways), `applyColumnState` and `getColumnState`, `sizeColumnsToFit` (with `flex`), `autoSizeAllColumns`, `expandAll`,
  `collapseAll`, `refreshCells`, the row group, pivot and value columns, `getPivotResultColumns`, `refreshServerSide`, focus, ranges and
  editing; AG-named events (`AG_EVENTS`: `gridReady`, `firstDataRendered`, `cellClicked`, `selectionChanged`, `sortChanged`,
  `filterChanged`, `columnPivotChanged`, `columnRowGroupChanged`, `rowGroupOpened` …) raised from drishti-grid's typed events with
  AG-shaped parameters. Without an element (Node, tests) the handle holds the model alone.
- **What drishti-grid gained for it**: `registerAggFunc` and `unregisterAggFunc`; a column's `cellClass`, `cellStyle` (safe properties
  only) and `system`; the pivot's `grandTotalRow` option (`top`, `bottom`, `none`: `placeGrandRow`); the view's `grandColumn` and
  `columnGroupTotals`.
- **Tests.** Node (`tests/elements/ag-adapter.test.mjs`): the option mapping, a pivot from AG options equal to drishti-grid's own pivot
  of the same data, custom and non-additive aggregations, totals and levels, the server-side request and a server pivot from
  `pivotResultFields`, tree data, the events, the API, the filter models both ways, the unsupported report; the gallery's AG shapes
  given to the adapter make the stage's pivot (`gallery.test.mjs`). Browser: the static harness `drishti_ag-harness.html`
  (`?mode=pivot|server|tree|server-tree`), a grid configured purely with AG-shaped options (`test_elements_grid_browser.py`).

**Not covered in version 1** (named in the console when used): integrated charts (use drishti-chart on a range), master-detail rows,
sparkline cell renderers, row dragging between grids, AG Grid's theming API and CSS classes (Drishti's theme applies), the viewport row
model, pagination, custom cell renderers, editors and header components. The migration guide lists each with its
Drishti way.

## 7. drishti-chart: one spec, the kinds, the maps

### 7.1 The spec

**ECharts stays the drawing engine**; drishti-chart is one **engine-neutral spec** above it, so a Sutra panel, a BI visual, the AI and
`new Chart(el, spec)` all say the same thing and nothing in a spec names ECharts ([g2](mockups/elements/g2-charts.html), option B shows
the spec beside the chart).

```json
{ "kind": "line", "title": "Daily P&L", "data": { "source": "var/backtest?scope=BANK&days=250" },
  "x": { "field": "date", "type": "time" }, "y": { "format": "money" },
  "series": [{ "field": "hypothetical" }, { "field": "var99", "negate": true, "style": "dashed", "tone": "bad" }],
  "marks": [{ "when": "hypothetical < -var99", "as": "point", "tone": "bad" }],
  "legend": "bottom", "zoom": { "window": [150, 250] }, "onClick": "explain", "export": ["png", "svg", "csv"], "a11y": { "table": true } }
```

Every chart gets, without asking: the theme and density, drishti-core's formats on axes and tooltips, a crosshair tooltip, legend
toggles, click → Explain (with drishti-insight), export (PNG at 2×, SVG, CSV of the data, the spec), a **data-table alternative** that
screen readers read and a button shows, and the same card chrome and status line as the grid.

### 7.2 The kinds

| General (drishti-chart) | Risk (drishti-risk, licensed) |
|---|---|
| line, area, bar (grouped, stacked, horizontal), scatter, bubble, heatmap (sequential and diverging), waterfall, candlestick, histogram, box plot, treemap, sankey, graph, gauge, sparkline, map | `risk.pnl-distribution` (scenario P&L with VaR and ES lines, the tail marked), `risk.backtest` (P&L against −VaR with exceptions and the traffic light: green 0 to 4, amber 5 to 9, red 10 and more, with the plus factor), `risk.exposure-profile` (EE, PFE, EEPE, the limit), `risk.var-change` (the waterfall of what moved), `risk.stress-window` |

A kind is a module that turns the spec into ECharts options; a domain library registers its kinds (`chart.kinds.register`) and they
appear in the gallery and in Sutras like the general ones.

**Vega-Lite.** A BI visual of `type: vega-lite` and a Python visual's Vega-Lite output are translated into the spec (`fromVegaLite`)
and drawn by drishti-chart; Vega itself is not in the product (decision O1, section 14: two engines is dual machinery). A Python visual
may return a drishti-chart spec directly (`output: drishti-chart`), the preferred form.

### 7.3 Maps

`kind: "map"`: the world, then **a country's first-level regions**: US states (and DC), **Canadian provinces and territories**, and the
first-level regions of every country that has a file, from the vendored Natural Earth data already in
`drishti-console/web/static/vendor/echarts/maps/` (`world-countries.json` at 1:110m, `world-points.json` for countries too small for that
scale, `regions/<ISO3>.json` for 252 countries at 1:10m simplified; public domain). A country's regions **load when first opened** and
stay cached for the session; nothing comes from a map service. [g3](mockups/elements/g3-maps.html) shows the world → USA → Canada drill
with quantile and gradient legends, the ranking, the inset and the table.

```js
new Chart(el, { kind: 'map', level: 'world', value: { field: 'ead', format: 'money' }, scale: { type: 'quantile', classes: 5 },
                drill: 'regions', onClick: 'explain' });
```

Codes are ISO 3166 (alpha-3 for countries, 3166-2 for regions, as the files hold them); a value keyed by a name is matched by
drishti-chart's place lookup (today `drishti_geomap.js`'s `find`). Projections: Equal Earth for the world, Albers for the USA (with
Alaska and Hawaii insets) and Canada, a conic fitted to the country otherwise.

## 8. Packaging, versions and licensing

**Where the files live.** Each library is a folder per version, `drishti-console/web/static/elements/<library>/<version>/` (entry,
modules, stylesheet, `.d.ts`, `integrity.json`), served as `/static/elements/…` to the console and `/embed/v2/elements/…` to other
origins with the rules of [CLIENT_RENDERER.md section 13](CLIENT_RENDERER.md#13-the-elements-versioning-and-distribution) (immutable
released folders, SRI, a partial version redirecting to the exact one). drishti-risk's folder lives in the banking module's plugin and
is served from `/static/banking/elements/risk/<version>/` behind the entitlement check.

**Versions.** Semantic versions **per library**; a released folder never changes; a breaking change only in a new major, announced one
release ahead. A **release train** file (`elements.json`) names the versions that go together for a Drishti release, so a host pins
one train or single libraries. Elements 2.x and the renderer's 1.x keep working one release beside renderer 2.0.0.

**Outside Drishti** (as built: 8.7). Never a public CDN or registry. An application takes two packed files
(`drishti.py elements pack`), the release archive `drishti-elements-<version>.zip`, the npm tarballs `@drishti/elements-<name>` from
an internal registry, or the WebJar from an internal Maven repository; the guide is
[ELEMENTS_IN_YOUR_APP.md](../guides/ELEMENTS_IN_YOUR_APP.md).

**Licensing.** Libraries 1 to 7 ship with the product. **drishti-risk is licensed with the banking module** and every later domain
library with its module: an unlicensed module's library is not served, its kinds are not registered, and its gallery pages are hidden.

**What the domain-module platform must deliver first** (the module split, designed on `origin/wip/e-boundaries` in
`docs/architecture/DOMAIN_MODULES.md`):

1. the console plugin loader with a plugin's own `static/` folder served only to the people whose role or lens uses it;
2. an **import map** per page (vendored, generated from the release train and the enabled modules) so pages and plugins import
   `drishti-grid` by name rather than by path;
3. the integrity record and the immutable-version check of the renderer generalised to any folder under `static/elements/` and a
   plugin's `elements/`;
4. the architecture guard extended to JavaScript: core code never imports a domain library, domain libraries never import each other;
5. the module licence check reachable from the console, so a library's folder is served or refused by it.

### 8.1 As built: the foundation, drishti-core and the grid's engine

- **Files.** `static/elements/drishti_elements.json` is the release train (each library's version, entry, requirements, style sheets).
  A library is ES modules under `<library>/src/` (they run as served: `import` them) and one bundled file, `<library>/drishti_<name>.js`,
  for hosts without modules and the console's classic pages: a classic script that registers `Drishti.elements.<name>` and is a CommonJS
  module under Node. The bundle is written by `tools/elements/build.py` (Python, offline, no npm; `--check` refuses a stale bundle).
  The per-version folders of this section come with the first released version.
- **Loading.** drishti-core carries the loader: `Drishti.elements.load('drishti-grid')` adds what a library requires, its style sheets
  and its bundle (`?v=<version>`), once each. `base.html` loads drishti-core on every page.
- **One home for each fact.** The named formats are generated from the server's `formats.yaml` and the themes from `drishti_tokens.css`
  into drishti-core; numbers are rounded half up on the decimal value with the true minus, as `FormatSpec` does.
- **Moved into drishti-core:** the resize handles (`drishti_resize.js`), the panel keys (`build/drishti_grid-keys.js`; `Drishti.resize`
  and `Drishti.GridKeys` keep their names), the alerts' and the workbench's toasts, the table editors' dialog, and the number helpers
  of twelve screens. `drishti_charts.js`, `drishti_view.js` and `drishti_pivot-engine.js` keep their own until drishti-renderer: the
  frozen renderer loads them into host pages that have no drishti-core.
- **The grid's engine speaks the server's pivot.** The view and the answer are the pivot engine's own (`PivotView`, `PivotAnswer`:
  `{measure, as}` values, `{in, notIn}` member filters, `expanded` paths, the row kinds grand, node, header, total, others); 5.3's
  request maps onto it, and value filters are applied by the grid. A column with `additive: false` (or `aggregate: 'server'`) is never
  summed in the browser: its cells wait for the server, which is asked for exactly those. `clientAggregation: false` is the console's
  policy. The layout (`wells`, `panel`, `terminal`) and the density are state the engine keeps and gives back; it draws nothing.
- **Events and live data** (`drishti_grid-live.js`, `drishti_live.js`). Typed events (`GRID_EVENTS`) on the bus and, on the web
  component's host, as DOM events `drishti:<kebab-name>`; transactions by row id, batched (`applyTransactionAsync`, a row's last update
  wins) and applied in place: a row whose sort or filter values changed is merged back where it belongs, ties keep their order; each
  changed cell's direction for its flash; a feed message is `{type: transaction | dataChanged | rows}` from any object with
  `subscribe` (the connection itself, SSE or a WebSocket, is drishti-data's `LiveClient`, 8.2); a
  server's data-changed notice invalidates blocks, a level, the blocks holding some rows, or everything. Group and local pivot totals
  change by difference (sums, counts, averages; the others from their members); non-additive ones are asked of the server again
  (`serverGroupTotals`, `serverPivot`), debounced.
- **Ready for the renderer.** Every module is a plain ES module with no console global: the renderer imports
  `drishti-core/src/drishti_index.js` and `drishti-grid/src/drishti_index.js` directly (a page touches `window` only through
  `install()`, which the bundle runs). One difference to settle when the renderer moves: its `m/format.js` rounds as Python does
  (half to even, for parity with the console's Python-drawn panels), drishti-core as the server's `FormatSpec` (half up); one rule wins.
- **The visual layer (drishti-grid 0.2.0).** `new Grid(element, options)` (`drishti_grid-view.js`) and `<drishti-grid>`
  (`drishti_grid-element.js`: options as a property, `layout` and `density` as attributes, events as `drishti:<name>` DOM events,
  `el.grid` the plain API; no shadow root, so the page's tokens and style sheet reach it) draw the engine's model. The modules, each
  under 530 lines: `drishti_grid-body.js` (one scroller with role grid or treegrid; heading rows stuck to its top with a pivot's member
  groups spanning their columns and collapsible; the rows the viewport gives, each one element with its pinned cells stuck left and
  right inside it, so a reader reads a row as one; the middle columns windowed), `drishti_grid-shell.js` (the three layouts' chrome:
  toolbar, ribbon, side panel, command line, function keys, status bar, the Explain drawer, card or split pane),
  `drishti_grid-wells-ui.js` (the field list and the wells: drag and drop, the chip menus, member filters, value filters, top N, the
  panel's switches and deferred update), `drishti_grid-actions.js` (the keys, the column, context and toolbar menus, copy, export,
  chart the range, the rows behind a cell), `drishti_grid-explain.js` (what the Explain hook answers, drawn by drishti-core in the layout's place),
  `drishti_grid-base.js` (the columns as drawn: panes, widths, a phone's subset), `drishti_grid-dom.js` (the menu,
  the hover note) and `drishti_grid-ui.js` (the decisions without a page, tested in Node: heading spans, conditional formats, the
  phone's columns, menu items, the command line, value filter conditions, the status figures, the flash). The look is
  `drishti_grid.css`, every colour a drishti-core token; the densities set the row heights the engine uses (36, 28, 22 px).
  **Nothing inline**: elements are made with the DOM and styled through the CSSOM, so a page with a strict CSP draws the grid.
  **Totals stay the server's**: a non-additive total is marked ◆ with a hover note, a range that holds one gets no sum (a warning
  instead), and its aggregation in the menus is the server's, disabled with the reason. **Live**: after a transaction, a figure drawn
  again that changed flashes up or down (the CSS animation is dropped under `prefers-reduced-motion`; a still edge marks the change);
  pending server figures and loading blocks show placeholders. **Phone** (under 640 px): the wells behind a Fields button, Explain as a
  sheet, the first column pinned; a pivot shows its row headings and one value column, the grand total of the measure picked in the
  toolbar's switcher. The **fixture harness** `static/elements/drishti-grid/drishti_harness.html` (synthetic trades, a stand-in server
  for diversified VaR and ES and for server-side rows, an Explain hook, a live tick; `?layout=…&density=…&theme=light&mode=pivot|grid|server&rows=N`,
  `?matrix=1` for all nine) opens with no server, from the file system too; the browser test of the component runs against it.
- **Reference and tests.** [ELEMENTS_API.md](../guides/ELEMENTS_API.md) is generated from the typed JSDoc by
  `tools/docs/elements_api.py` (`--check` refuses a public symbol without types); the unit tests run in Node (`tests/elements/`,
  `node --test`) from `tests/test_elements.py`, a quick file check of the gate; the engine at a million rows is in
  [BENCHMARK.md](../admin/BENCHMARK.md). The drawn grid is tested once, in one browser file against the fixture harness
  (`tests/test_elements_grid_browser.py`: every layout and density in both themes, drag between wells, every menu item, button and
  function key, ranges and the status bar, the keys and roles, the flash, server blocks, 100,000 virtual rows, a phone); screens that
  move onto it (section 12) test only their wiring.

### 8.2 As built: drishti-inputs, drishti-data and the grid's cell editing

- **One home for what all of them share** (moved into drishti-core): the sort and filter model and their tests of a row
  (`drishti_filters.js`, from the grid: `passes`, `rowFilter`, `compareRows`, `filterWords`, and `filterRows` for a chart or a source),
  `FILTER_CHANNEL` (the bus event a filter bar emits and grids and charts follow), the element maker and the placements (`h`,
  `placeAt`, `placeUnder`, `drishti_dom.js`), reading typed and pasted numbers in a locale (`parseNumberText`, `numberEditText`,
  `drishti_parse.js`: grouping, the true minus, brackets, k, m, bn, percentages; de-DE's marks), and `loadScript`, `loadStyle` and
  `staticUrl` for the vendored engines a library wraps.
- **drishti-inputs 0.1.0** (`static/elements/drishti-inputs/`; every component a class, `new X(element, options)`, and a web component,
  events on its own bus and as `drishti:<name>` DOM events, no shadow root, nothing inline):
  - **Business dates** (`drishti_calendar.js`, pure): `HolidayCalendar` from rules (a fixed day with its weekend roll, the n-th or
    last weekday of a month, the weekday before a day, Easter offsets) or from the server's dates, any weekend, unions (`US+UK`); the
    bundled US, UK, TARGET and CA rules are illustrative and a site's `defineCalendar` wins. T-1 and T-n, month-, quarter- and
    year-end, the previous month-end, business days between; twelve range presets (Today, T-1, WTD, MTD, QTD, YTD, the last 5 and 20
    business days, the previous month, quarter and year, the last 12 months); typed dates (`T-3`, `T+2`, `ME`, `QE`, `YE`, `PME`,
    ISO, `6 Nov 2026`, `Nov 6, 2026`).
  - **`DatePicker`, `<drishti-date>`**: a field that reads typed dates and a calendar (the WAI-ARIA date grid: arrows, Page Up and Down,
    Shift for a year, Home and End); weekends dimmed, holidays named, days that are not business days refused in words ("a holiday
    (Thanksgiving): the business day before is 25 Nov 2026"), min and max, the shortcuts business date, T-1 and month-end.
    **`DateRangePicker`, `<drishti-date-range>`**: the presets beside two months, the ends as typed fields, a range shaded as the pointer
    or the keys move, Apply and Cancel, a preset kept as its id so it moves with the business date (`setAsOf`), a longest range.
  - **`TreePicker`, `<drishti-tree-picker>`** over `TreeModel`: a selection kept compact (a node stands for everything under it; a branch
    never loaded can be checked whole), checked, mixed and unchecked (`aria-checked`), unchecking under a checked parent, children from
    the server the first time a node opens (a loading row, an error row with Retry), search of the loaded levels or through a server
    hook, the tree view's keys and type-to-jump, inline or in a panel, single or multiple.
  - **`MultiSelect`, `<drishti-multiselect>`**: chips (a "+N" past `maxChips`), a search field in the control, grouped options, Select
    all (the matching ones) and Clear, a limit, Backspace takes the last chip; thousands of options (the best 300 drawn, "N more").
  - **`Combobox`, `<drishti-combobox>`** over `TypeAhead`: a list or a server search, debounced, a minimum length, the request it
    replaces aborted, a late answer dropped, recent answers cached, the server's error in words, free text when allowed.
  - **`Select`, `<drishti-select>`** (`drishti_select.js`): one value from a list, the WAI-ARIA select-only combobox over the same
    `Listbox` and `Popover` as the combobox and the multi-select (groups, disabled options, the cap). On the control: Down, Up, Enter,
    Space or Alt+Down open; Home and End open on the first or last; a printable key types ahead. In the list: the arrows, Page Up and
    Down, Home and End; Enter, Space or Alt+Up pick; Escape and Tab close without picking. A long list (more than `searchMin`, 8, or
    `search: true`) has a search field on top that holds the focus. In markup its options may be `<option>` and `<optgroup>` children
    (`selected` marks the value), so a template swaps `<select>` for `<drishti-select>`; `name` makes a hidden input for a plain form;
    `setOptions(list, value)` refills it, `setDisabled`, `drishti:change` with `{value, option}` (a value set by code emits nothing).
  - **`Form`, `<drishti-form>`** from a JSON-schema-like description (`drishti_schema.js`, pure): string, number and integer (read
    loosely, shown formatted), boolean, date, date range, enum (radios up to three, else a select), arrays of an enum (the multi-select),
    arrays of objects (repeatable groups: add, remove, move), objects (fieldsets), a combobox, a tree, the code editor; conditions
    (`when`: equals, in, more than, true; all, any, not) show and hide fields as people type, and a hidden field is neither validated
    nor submitted; validation in words per field once touched and for all at submit, with a summary whose items lead to their fields;
    cross-field dates (`after`, `before`); the server's checks (`check` hook, problems by path). **Four eyes**: the submit lists what
    changed, asks why (required) and who approves, files the proposal through the hook and waits read-only with its number until
    `resolveProposal('approved' | 'rejected')`.
  - **`FilterBar`, `<drishti-filter-bar>`** over `FilterModelBar`: chips in words, + Filter with a field search, editors per type
    (members with search and select-all, from the server when given; a condition and a value read loosely; a date preset or two typed
    ends), a quick search, Clear all. It emits drishti-core's filter model on a bus under `FILTER_CHANNEL`; `grid.followFilters(bus)`
    follows it (its own columns only), and a chart filters its rows with `filterRows(rows, model)`.
  - **`CodeEditor`, `<drishti-code>`**: the vendored CodeMirror 5, loaded the first time an editor is made (a text area holds the text
    until then); the modes Rachana (functions, `[fields]`, `$paths`, `@names`, numbers with units, comments), Rachana YAML and the
    vendored Python, loaded when asked; completion from the function catalogue through a hook (as a name is typed, or Ctrl+Space; the
    arrows, Enter or Tab, Escape; a function's parentheses with the cursor inside); lint markers from a hook (a mark under the text, a
    sign in the gutter, the problems listed under the editor as links to their lines); read-only. **`CodeDiff`, `<drishti-code-diff>`**:
    a line diff (Myers), side by side or unified, highlighted by the same modes without CodeMirror.
- **drishti-data 0.1.0** (`static/elements/drishti-data/`):
  - **The data source**, one contract for a grid and a chart (`drishti_sources.js`): `getRows(request)` answers a window for the grid's
    own block request (rows, sort and filter models, quick filter, row groups and the level, values, an AbortSignal), so any source is
    drishti-grid's server-side `datasource` as it is; `memorySource` (filter, sort, groups with counts and sums, windows),
    `restSource` (the request POSTed, or offset, limit, sort and filter as query parameters), `serverSideSource` (Drishti's own),
    `arrowSource` (an Arrow table fetched once and served as row views, or Arrow pages), `liveSource` (any source with a feed);
    `readAll` reads every matching row a page at a time up to a limit (a chart). Each says what it can do (`capabilities`).
  - **The live client** (`LiveClient`): Server-Sent Events or a WebSocket, open with the first subscriber and closed with the last;
    reconnect after an exponential, capped, jittered back-off (at once when the browser comes back online); a heartbeat watchdog (no
    message, ping or `heartbeat` event for `heartbeatMs`: opened again; on a WebSocket the client pings); resume from the last event
    id (SSE: a `lastEventId` parameter on the new address; WebSocket: a `resume` message first); states and counts for a status line.
    It is itself a feed: `grid.connectLive(client)`.
  - **Backpressure** (`TransactionSink`, `pipeToGrid(feed, grid)`): at most `maxInFlight` batches in the grid's
    `applyTransactionAsync`; everything that arrives meanwhile is folded by row id (the last update wins; an add then a remove is
    nothing; an update then a remove is a remove); a `rows` message replaces everything pending, a `dataChanged` notice follows the
    rows before it. In Node, 5,000 ticks on two rows reach a grid in at most three batches with the last value of each row.
  - **The Arrow client** (`ArrowClient`, `fetchFrame`, `DataFrame`): the vendored Arrow JS, loaded when first needed; columns read without
    a copy where they can be (a 64-bit float column with no nulls is the Arrow buffer; a dictionary decoded once per word); the
    console's type rules (64-bit integers past 2^53 as exact text, decimals as exact text, dates ISO, timestamps ISO with Z when
    zoned); rows as views (each holds its row number; `toObject()` copies, the grid's edits copy only the row they change);
    `toGrid()` gives column definitions from the types. **Flight over HTTP** when the server's capabilities name a `do-get` address (the
    ticket POSTed, an Arrow stream back); else **Arrow over HTTP**; else JSON, read into the same frame. The Drishti server answers
    Arrow over HTTP today; its Flight service is gRPC ([ARROW_FLIGHT.md](ARROW_FLIGHT.md)), which browsers cannot speak, so the
    Flight path serves servers or gateways that offer it over HTTP.
  - The grid's own SSE and WebSocket feeds (`eventSourceFeed`, `webSocketFeed`) are gone: the connection has one home, `LiveClient`.
- **Cell editing in drishti-grid 0.3.0** (`drishti_grid-edit.js`, the model; `drishti_grid-editors.js`, the editors): a leaf's cell
  of a column with `editable` (true or a function of the row) may change; never a pivot's figure, a group, a server-side row or a
  computed column. Editors per type (`editor`, else by type): text; a number shown with every digit in the grid's `locale` and read
  back loosely; a date through drishti-inputs' business-date picker when that library is on the page (the column's `calendar` and
  `businessOnly`), else the browser's date field; a select of the column's `options`; a checkbox that turns at once. Started by Enter,
  F2 (Explain on a cell that cannot be edited), a double click or typing (the key replaces the value); Enter saves and moves down,
  Shift+Enter up, Tab right, Shift+Tab left, Escape cancels, leaving saves. Validation: `required`, `min`, `max`, `maxLength`, the
  options and the column's `validate(value, row)`; a problem keeps the editor open with the words under it (role alert). Every change
  is one **edit transaction** (`cellValueChanged` per cell, `editTransaction` once; rows updated by id, re-sorted and re-filtered in
  place, totals by difference) and is undone and redone as one (Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z; the context menu). **Paste from
  Excel**: tab-separated text (quoted cells with tabs, new lines and quotes) from the focus, one value filling a selected range, cells
  that cannot change skipped and values that do not read listed; one transaction. Delete empties a range's editable cells.
  **Write-back**: `editing.writeBack(tx)` hears each transaction and answers applied, refused (the old values come back, with the
  server's words) or proposed; with `editing.fourEyes` every transaction is a **proposal**: its cells are marked and cannot change
  again until `grid.resolveProposal(id, 'approved' | 'rejected')`. Events `cellEditStarted`, `cellEditStopped`, `editTransaction`,
  `editUndone`, `editRedone`, `proposalResolved`.
- **Harness and tests.** `static/elements/drishti-inputs/drishti_harness.html` draws every input, an editable grid following a filter
  bar (with `?fourEyes=1`), the form with four eyes, the code editors and diff, a grid fed by `LiveClient` through stand-in
  EventSource and WebSocket classes, and an Arrow grid; no server (the Arrow part reads a fixture the browser test serves). Node:
  `tests/elements/inputs.test.mjs`, `data.test.mjs`, `grid-edit.test.mjs`; one browser file, `tests/test_elements_inputs_browser.py`.
- **drishti-inputs 0.2.0, the console's pickers** (`drishti_native.js`, and options on two components): `upgradeDateInput(input)`
  turns a page's `<input type="date">` into the date picker in place (the input becomes a hidden input with its name, id and value;
  its `value` setter moves the picker, the picker's changes come back as `input` and `change` events; `hidden`, `disabled`, `min` and
  `max` are followed; a required or unreadable date stops its form's submit with the words beside it; `data-holidays`, `data-calendar`,
  `data-current` and `data-business` choose the calendar, the business date and business days only); `upgradeCheckboxes(group)` puts
  the multi-select over a group of checkboxes that stay in the form (ticked with a `change` each; a label with the `offClass` is not
  offered, so a dependent list follows); `upgradeFields(root)` does both for a page. `Combobox` takes a page's own `input` and a
  `suggest` mode (the text stays the page's; Enter and Escape with the list closed reach the page). `DateRangePicker` takes presets
  given whole (`{id, label, from, to}`: a server's own) and `openEnded`. Every field, option and button is 44 px high on a touch screen.
  The console loads drishti-inputs on every page (`base.html`); `drishti_app.js` upgrades the page's fields once.
- **Moved onto them** (2.9.11.e, wip/e-inputs-convert):

  | Was | Now | Deleted |
  |---|---|---|
  | `drishti_multiselect.js` (106 lines, every query form, the controls, BI's slicer) | `upgradeCheckboxes` (query forms, `data-multiselect`), `MultiSelect` (controls, BI's drop-down slicer) | the file is a 40-line loader of drishti-inputs for the frozen renderer 1.2.0; `.ms-*` and `.ctl-pop` CSS |
  | `drishti_controls.js`: the multi-select, the date, the date range | `MultiSelect`, `DatePicker`, `DateRangePicker` (the server's presets, open-ended) | the details/summary multi-select and the native date fields; the number range stays two number fields |
  | the as-of date (`drishti_app.js`, `terminal/_topbar.html`) | `DatePicker` keyed by the server's holidays, T-1 and month-end; non-business days refused in words | the native date handling and its validity messages |
  | raw date fields: `terminal/compare`, `form`, `search`; `bi/report`, `notebook`; `admin/caches`, `collab`, `lakes`, `access`, `loads`, `pack_detail`; `drishti_bi-design-panes.js`, `drishti_bi-statement.js`, `drishti_bi-grids.js` (range slicer), `drishti_explain.js`, `drishti_record-forms.js` (fields and cells) | `upgradeDateInput` (`upgradeFields` on every page) | the query form's date that turned into text: a separate relative field (`.qf-vrel`) |
  | `drishti_history-page.js` range presets | `DateRangePicker` whose presets open the server's ranges | the links stay only for a page without scripts |
  | `drishti_people.js` (four screens), `drishti_discussion-compose.js` (`@`, `{`) | `Combobox` (suggest mode, on the page's field); the comment box with the combobox's `TypeAhead`, `Listbox` and `Popover` | the hand-made listboxes, their `<ul>`s in five templates and scripts, `.shr-list` CSS |
  | `drishti_record-forms.js` entity field | `Combobox` over the page's field with the server's lookup | the hand-made list, its search ordering, `.fm-entity-list` CSS |

- **Still to move** (each a later branch; none is drawn twice):

  | Today (file, lines) | Moves onto | Note |
  |---|---|---|
  | `drishti_cm-yaml.js` (21–43) | the code editor's YAML mode | the same mode, now in drishti-inputs; two design pages load the old one |
  | `drishti_studio-yaml.js` (28–241), `drishti_studio-assist.js` (54–372) | `CodeEditor` lint and completion hooks | Studio's own YAML reading and hint box |
  | `drishti_bi-expr-editor.js` (33–375), `drishti_editor-formula.js` (27–100), `build/drishti_expr.js` (28–106) | `CodeEditor` completion, a `Combobox` | three completions of formulas and paths |
  | `drishti_bi-design.js` (602), `drishti_calc.js` (71), `build/drishti_yamltab.js` (29), `build/drishti_about.js` (39) | `CodeEditor` | four `CodeMirror.fromTextArea` |
  | `drishti_controls.js` (the filter model) | `FilterBar` | the controls' values are the server's (`ctl.*`, panels asked again); a filter bar emits a browser-side filter model, so it waits for a Sutra whose panels filter in the browser |
  | `drishti_form.js` (the query form, server-drawn GET), `build/drishti_forms.js` (29–184), `drishti_bi-format-pane.js` (41–171), `drishti_editor-table.js` (54–263), `drishti_subscribe-when.js` (28–76) | `Form` | `Form` draws its own `<form>` with Save and Reset and validates at submit; these are live property panes or parts of a server form. They need a **live mode** (no form element, a change per field), **custom widgets** (colour, rules, swatches, the format pane's `x-control`s), **per-field hooks** (`data-*` names the tutorials and tests use) and a **time** type (the subscription's "At") first |
  | `drishti_form-expr.js` (27–212) | stays | the browser's copy of the server's form expressions (`FormExpr.java`): a language, not a form; `Form`'s `when` conditions are a different, smaller one |
  | `drishti_record-forms.js` (the rest: beside, cells, the drawer's steps, drafts, review) | `Form` with four eyes, grid editing | the entity field and every date moved (above); the beside form, the cells and the stepped drawer need `Form` steps, money and datetime types with their adornments, and the server's drafts as the four-eyes hook |
  | `<input type="datetime-local">` (the top bar's *Known at*, compare, the record forms' datetime) | a date-and-time picker | drishti-inputs has no time yet |
  | `drishti_var-basket-sheet.js` (40–290) | grid editing | its cell editor (200) and paste (210–248) |
  | `drishti_live-hub.js` (40–316), `drishti_channel.js` (60–163), `drishti_live.js` (34–155), `drishti_monitor.js` (32–49), `drishti_bi-consume.js` (158–216), the renderer's `m/net.js` (55–105) and `m/elements.js` (85–256) | `LiveClient` | one connection, reconnect and resync; the channel's leader election stays a page concern |
  | `drishti_table.js` (39–242) | `ArrowClient`, `DataFrame` | the VaR explorer and the scenario matrix |
  | `drishti_pivot-engine.js` (81–125), `drishti_refresh.js` (42–191), `drishti_bi-live.js` (40–123) | data sources | fetch, polling and staleness |
  | `drishti_table-chart-engine.js` (342–388) | `HolidayCalendar` | business days for chart gaps (drishti-chart) |

### 8.3 As built: drishti-chart, its maps and drishti-risk

- **The spec** (`drishti_chart-spec.js`): `kind`, `title`, `subtitle`, `data` (rows inline as objects, `{values}`, a server's answer
  `{columns, rows}`, or `{source}` read with the page's fetch), `id` (the field that names a row: selection and live updates follow it),
  encodings `x`, `y`, `category`, `value`, `series` (a list of `{field, name, tone, style, negate, area, stack, axis}` or `{by: field}`, one
  series per member), `size`, `colour`; formats by name through drishti-core (`money`, `compact`, `percent`, `amount0`, `pct2` …);
  axes (`type` category, time, value or log; `title`, `order`, `domain`, `min`, `max`); `legend` (bottom, right, top, none);
  `annotations` (lines, bands, points and texts on either axis); `marks` (points where a condition holds: `"hypothetical < -var99"`);
  `colourWhen` (a mark's tone by a condition); `stack` (true or `percent`), `orient`, `sort`, `labels`, `zoom` (`{window: [150, 250]}`),
  `onClick` (explain, select, none), `export`, `live` (`{throttleMs, keep}`). Conditions are read by a small parser (comparisons,
  arithmetic, `&&`, `||`, `datum.x` as Vega-Lite writes it), never by `eval`. `checkSpec` names every problem with its path;
  `normalizeSpec` fills the defaults; `specText` is the spec as the studio shows it. A kind is registered with `registerKind(name,
  {build, label, family, cartesian})`; nothing in a spec names ECharts.
- **The kinds**: line, area, bar (grouped, stacked, stacked to 100%, horizontal, sorted), scatter and bubble (value or category axes,
  symbols), histogram (equal bins on a value axis, the tail beyond a value in its tone), waterfall (totals first and last or by a
  field), box plot (whiskers at 1.5 IQR, outliers), heatmap (sequential, or diverging around a middle), candlestick, pie and donut (small
  slices folded into Other), gauge (bands in tones), treemap (nested by levels), sankey (links summed), `layer` (several kinds over the
  same axes, categories the union of every layer's) and `map`. Each builds the ECharts option, the data table, the legend and one
  line a screen reader reads, without a page (`drishti_chart-kinds.js`, `drishti_chart-kinds2.js`); every data item carries the indexes
  and ids of the rows it was made of.
- **The card** (`new Chart(el, spec, options)`, `<drishti-chart>`; `drishti_chart-view.js`, `drishti_chart-parts.js`): the title, the
  kind, a status badge (the backtest's zone), the tools ▦ ⤓ ? ⛶ and the zoom's reset, the legend as buttons that hide a series, the
  status line (the source, the rows, the time to draw). The theme follows the page (a change of `data-theme` redraws), the three
  densities are the grid's. Tooltips are elements built with the DOM (a strict CSP holds), with a crosshair on axes. **Click any mark:**
  its rows are selected (ctrl or shift adds) and outlined, and the Explain hook (the grid's: a context of kind, series, field, value,
  category, rows and ids) answers in a side sheet drawn by drishti-core's `renderExplain`, as the grid draws it. Zoom: the wheel, a drag
  across the chart, and the overview window under it. The keyboard: arrows walk the first series' marks with an announcement, Enter
  or E explains, T opens the table, Escape closes. Export: PNG at 2×, SVG drawn off screen by the engine's server-side renderer, CSV of
  the data (drishti-core's writer), the spec. **Live**: `apply({add, update, remove})` by row id, `append`, `replace`, drawn once per
  throttle window; `live.keep` caps the rows kept. **Studio** (`mode: 'studio'`, `drishti_chart-studio.js`): the registered kinds in a
  rail grouped by family (general, maps, then each domain library's), the chart with its table under it, the spec beside it with options
  (legend, labels, stack, bars, log x, zoom, density) that change the chart and the spec together.
- **Linked selection** (`drishti_chart-link.js`): charts with `link: {channel}` and grids linked by `linkGrid(grid, {channel})` exchange
  row ids on drishti-core's bus (`drishti:selection`); a selection that came from the bus is never sent back. drishti-chart does not
  import drishti-grid: a grid is linked through its model's typed events and selection.
- **Maps** (`drishti_chart-geo.js`, `drishti_chart-map.js`, `drishti_map-view.js`). The shapes are projected before the engine sees them
  (an identity projection draws them): Equal Earth for the world (Antarctica left out; the countries too small for 1:110m as points),
  Albers for the United States (the lower 48 and DC; Alaska and Hawaii in their own Albers, scaled into framed insets under the
  south-west) and for Canada (Statistics Canada's parallels; ten provinces and three territories), and for every other country a conic
  fitted to its core extent (the rings near its area-weighted centre, so France fits its mainland and its overseas regions stay
  reachable by panning), or a plate carrée scaled at the middle latitude where a conic cannot fit. A country's `regions/<ISO3>.json` is
  read the first time it is opened and kept for the session; a failed read is asked again next time. Places match by ISO alpha-3,
  alpha-2, a region's ISO 3166-2 code with or without its country, or the English name; rows that match nothing are counted on the
  status line. The choropleth: quantile classes (each with its range and count, and the no-data count) or a continuous ramp; no data
  hatched; a legend class clicked keeps it bright; bubbles at places or at longitude and latitude; a metric picker; the wheel and drag,
  and + − ⟲. Double-click a country (or "Drill into regions ›") for its regions, the breadcrumb back. A page gives a level's rows with
  `placeData(iso3)`, or one set of rows holds every level. Modes (g3): `ranking` (the default: the legend, the top ten with bars, the
  selected place with its rank and share, Explain and the drill) and `table` (a floating legend, a world inset once drilled, the selected
  place in a floating card, a sortable table under the map).
- **Vega-Lite translated** (O1; `fromVegaLite(vl, {rows})` gives `{spec, unsupported, refused}`; `vegaLiteIn(host, vl, {rows})` draws
  it in a host or writes the refusal there; `chartIn(host, spec)` puts any spec in a host as a `<drishti-chart>`). The shipped Vega-Lite
  specifications are the report visual of `python-desk-risk` (a bar with a sort, a colour condition and a tooltip) and the Python sample
  `pnl-vega` (two layers: dots and median ticks over a nominal axis); both translate whole, and a test holds their data and encodings
  equal to what Vega drew (`tests/test_vega_translate.py`). Coverage:

  | Vega-Lite | Translated to |
  |---|---|
  | marks bar, line, area, point, circle, square, tick, rect, arc, boxplot | bar, line, area, scatter (or bubble with size), heatmap, pie or donut (innerRadius), boxplot |
  | rule at a constant (`datum`), or a few rows' values | annotation lines |
  | `layer` with data of its own or inherited | layers of one chart (drishti-chart's `layer`) |
  | encodings x, y, color, fill, stroke, size, theta; tooltip and opacity | axes, `series: {by}`, `colour`, bubble size, the pie's value; Drishti's tooltip |
  | types quantitative, nominal, ordinal, temporal | value, category (sorted as Vega-Lite sorts), time |
  | `aggregate` sum, mean, average, count, min, max, median; `bin` with maxbins | rows aggregated in the translation; the histogram kind |
  | `sort` -y, y, ascending, descending, an array; `stack` null, false, normalize | `sort`, the category order, `stack` (percent) |
  | `scale` log, zero, domain; `axis` title and format (d3: `,.0f`, `.2%`, `~s`) | log axes, min and max, titles, drishti-core's named formats |
  | color condition `{test, value}`, a fixed colour | `colourWhen` and tones (a red is bad, a green good, an orange accent, a grey muted) |
  | transforms filter (an expression or a field predicate) and calculate | applied to the rows in the translation |
  | `title` (text and subtitle); width, height, autosize, config, $schema | title and subtitle; the card sizes and themes the chart |

  Facets, repeat and concat, params and selections, other transforms, text marks, projections and geoshape are **refused, never drawn
  wrong**: the answer has no spec, the paths in `unsupported`, and `refused` a sentence naming each feature with what to use instead
  (`vegaLiteAlternative(path)`: small multiples through a split well, one visual per chart, the report's cross-filtering, the map kind,
  a calculated column or measure, a table or card, or a drishti-chart spec from Python). A `url` or a `loader` anywhere is refused too.
- **drishti-risk** (`static/elements/drishti-risk/`, the banking module's; the manifest marks it `module: banking`; it moves into the
  plugin's folder with the module platform of section 8): `risk.pnl-distribution` (the histogram of scenario P&L, the VaR and ES lines,
  the tail beyond VaR in the bad tone), `risk.backtest` (P&L against minus the day-before VaR, exceptions marked, the Basel traffic light
  with its plus factor on the card: green 0 to 4, amber 5 to 9, red 10 and more over 250 days), `risk.exposure-profile` (EE and PFE on a
  log tenor axis, EEPE over the first year, the limit, a breach on the card) and `risk.var-change` (a waterfall where an increase is bad).
  VaR, ES and EEPE are the server's (in the spec or the rows); only a page without a server computes them from the rows given, and the
  card says "computed here".
- **drishti-core** gained what the grid and the chart share: the element maker (`h`, `placeAt`), Explain's drawing (`renderExplain`,
  `sparkline`, under each component's class prefix), the CSV writer and `download`. The loader loads a library's vendored engine (the
  manifest's `scripts`: ECharts for drishti-chart) once, and not when the page has it.
- **Tests.** Node (`tests/elements/chart.test.mjs`, `maps.test.mjs`): the spec and its checks, the condition language, the formats and
  theme, every kind's option, table and legend, the risk figures, the translator on the two shipped specs and the subset, linked
  selection, the bundles, the projections, the loader and every one of the 252 region files. The drawn chart is tested once, in
  `tests/test_elements_chart_browser.py` against the fixture harness `static/elements/drishti-chart/drishti_harness.html` (synthetic
  data, no server; `?view=board|studio|map|link`, `&mapMode=table`, `&level=USA`, `&density=…`, `&theme=light`).

### 8.4 As built: the gallery

- **Where.** `static/gallery/drishti_gallery.html`, a static page: it needs no server and opens from the file system too (there the maps
  cannot read their shapes: a browser refuses `file://` reads). The console serves it at **`/elements`** (and `/elements/gallery`), the
  query kept; it is a Build page (the edition gate's `build` feature, `core/edition.py`) listed under Build → Learn. The page loads
  drishti-core; its boot script loads every other library of the manifest with drishti-core's loader, so a library added to
  `drishti_elements.json` is in the gallery with no change to the page. Nothing inline: it runs under a strict CSP.
- **An Elements app, two modes of one component** (O6): `Drishti.gallery.start(el, {mode})` makes a `Gallery`; `mode: 'playground'`
  (g4 A, the default) has three panes: the searchable list of libraries and components (version, the badge "no server needed", "needs a
  Drishti server" or "banking module licence", element or function), the live component with its code tabs and status line (bundle size
  and lines, the Node tests and browser file, the accessibility check, the time to draw), and the options with the event log;
  `mode: 'docs'` (g4 B) is a page per component with the tabs Examples (each a runnable card with its code and "Open in playground"),
  API, Accessibility and Changelog. The address keeps the state (`#mode=docs&c=pivot&tab=api&o={"layout":"terminal"}`).
- **One home per fact: the catalogue.** `tools/docs/elements_api.py` writes `static/gallery/drishti_catalogue.js` from the same parse as
  ELEMENTS_API.md (`tools/docs/elements_catalogue.py`; `--check` refuses a stale copy of either): per library its version, title,
  licence, bundle size, Node tests and browser file, each module's opening words, every public symbol, **every typedef with its
  properties** (name, type, default, optional, words: the options panel is made from them; a list of literals is a select, `boolean` a
  switch, `number` and `string` inputs, anything else "set in code"), the string lists the code exports frozen (`GRID_EVENTS`,
  `CHART_EVENTS`, `LAYOUTS`, `MAP_MODES` …: the event log listens to exactly these) and the library's lines of the product CHANGELOG;
  besides, the libraries section 2 plans that the manifest does not hold yet (listed as "Not built yet" with their badge) and the AG Grid
  adapter's mapping (its one home: `drishti_grid-ag-map.js`, section 6) with each row's state.
- **The demos** (`drishti_gallery-demos.js` for drishti-core and drishti-grid, `-demos-chart.js` for drishti-chart, the maps and
  drishti-risk, `-demos-inputs.js` for drishti-inputs and drishti-data): a demo names its library, the typedefs of its options, its event
  lists, its modules and its examples. An element demo's `build(values)` is **the object the gallery mounts and prints**: the web
  component, the JavaScript and the AG Grid shapes are written from it (shared rows and hooks by name), so a snippet is the example's
  source, not a copy. A demo is shown only when its library is in the manifest. On this build: core (formats, theme tokens with contrast,
  the event bus, dialogs, drawers and toasts, Explain, the API client against a stand-in, the accessibility helpers), grid (the grid in
  three layouts and three densities, the pivot with the stand-in server's VaR and ES, the nine layouts × densities at once, server-side
  rows at 100,000, live updates from a manual feed, editing through `setCellValue`, CSV and Excel, a pivot made by the AG Grid-style
  adapter from AG-shaped options), chart (every general kind and the
  studio, the map in both modes from the world to a country's regions, a grid and a chart linked, a live chart, Vega-Lite translated)
  and the four risk kinds; inputs and data (every input, the filter bar with a grid following it, data sources, the live client over a
  manual feed, frames) come in with their libraries. The sample data and the stand-in server are the fixture harnesses' synthetic
  figures (`drishti_gallery-data.js`).
- **The AG Grid tab** (every grid demo): live. It shows the AG Grid Enterprise-shaped options an application passes
  `createGridFromAgOptions` for the grid on the stage (`columnDefs` with `rowGroup`, `pivot`, `aggFunc`, `'drishti:server'` for a
  non-additive measure, `pivotMode`, the totals, `rowModelType: 'serverSide'`, `sideBar` for the panel layout, the hooks under
  `drishti`; `agShape` in `drishti_gallery-code.js`, real functions), and the adapter's mapping with each row's state, the version 1
  scope (O5) and what it leaves out. The **AG Grid adapter** demo mounts its pivot through the adapter from exactly those options (its
  controls: the wells, `grandTotalRow`, `groupTotalRow`, `sideBar`). The Sutra tab shows the panel (`table`, `pivot`, `line`, `hbar` …) where a panel kind
  draws the component, and says so where none does yet.
- **Accessibility.** No axe is vendored: every demo gets the gallery's keyboard and ARIA smoke check (`audit` in
  `drishti_gallery-code.js`): controls named, roles and ARIA attributes valid, every id an ARIA attribute names present, ids unique, no
  positive tab index, something reachable by Tab, a grid with rows. The status line shows it; the Accessibility tab adds the keys from the
  modules' own words (`drishti_keyboard.js`, `drishti_chart-view.js`), the grid's function keys and the roles found.
- **Tests.** Node (`tests/elements/gallery.test.mjs`): the catalogue against the manifest and the code (the frozen lists equal what the
  bundles export), the typedefs, the planned libraries, the AG mapping and the changelog; the option controls, the code writer and the AG
  shapes; every demo naming what the catalogue holds. The routes and the menu item (`tests/test_elements_gallery.py`). One browser file
  against the static page (`tests/test_elements_gallery_browser.py`, about 18 s): every component drawn with no page error and a clean
  smoke check, its code tabs and status line; options from the JSDoc changing the component and its code, the grid in three densities
  and both themes, the address restoring the state; the AG Grid tab, live, editing, export; the keys in the grid and the chart; every
  example of every documentation page, its API, accessibility and changelog tabs, "Open in playground".

### 8.5 As built: drishti-renderer 2.0.0

The client renderer is library 7 (CLIENT_RENDERER.md is its one home: [section 3](CLIENT_RENDERER.md#3-the-librarys-shape) the modules and
what each library draws, [section 6](CLIENT_RENDERER.md#6-formats-and-tones) the rounding, [section 13](CLIENT_RENDERER.md#13-the-elements-versioning-and-distribution)
versions and the migration from 1.x, [section 14.4](CLIENT_RENDERER.md#144-renderer-200-against-the-macros-2026-11-07) parity). What the
other libraries gained for it:

- **drishti-grid, the document table** (`drishti_doc-table.js`, `drishti_doc-behave.js`): the grid draws the server's table as it speaks
  the server's pivot. The ViewModel's plain tables, trees (`children`, `expand`), drills (`levels`), tables offering Pivot, the pivot kind
  (heat buckets, half up) and a chart's Data table are a semantic `<table>`, every row in the DOM, with the console's table classes,
  element for element renderer 1.2.0's markup (a Node test compares them with the frozen `tables.js`); what a cell shows and its tone
  come from the host's hooks. `enhanceDocTables` gives the console's behaviours in place: heading sort (ascending, descending, the
  server's order) by drishti-core's `textSortKey` (amounts with k, m, bn, tn, %, bp, dates, as the console's tables read them), one filter
  box, 25-row pages, tree rows whose filter keeps ancestors, drill levels with crumbs, Up, Esc and Backspace; `sort: false` leaves plain
  tables to a host's own tools. The console's server tables fold into it in step 3 of section 12.
- **drishti-grid, tree data** (`drishti_tree-data.js`; the checklist's item): `treeData: true` with `getDataPath(row)` builds the client
  rows into a tree of their paths; intermediate nodes are made, a row may itself be a node, nodes open by path or `groupDefaultExpanded`,
  siblings follow the sort, a filter keeps a match's ancestors; a node no row names carries its figures as a group does, the server's
  when they are non-additive or `clientAggregation` is off (`serverGroupColumns`, `setGroupServerValues` by path); the body draws a
  `treegrid` with `aria-level` and `aria-expanded`; the AG Grid-style adapter maps `treeData`, `getDataPath` and `autoGroupColumnDef` onto it (section 6).
  **Tree data on server-side rows** (`drishti_server-rows.js`): `treeData` with a `datasource`, `isServerSideGroup(row)` and
  `getServerSideGroupKey(row)`: the server answers the tree one level at a time; the top level is asked with `groupKeys: []` and
  `treeData: true`, a node's level when it opens, with `groupKeys` the node's path; a node is a row too (its own figures, the
  server's); a closed node reopens from its cached blocks; a sort or filter asks every open level again; `refresh({keys})` asks one
  node's level and those under it; `groupDefaultExpanded` and expand/collapse all open levels as they load. The adapter maps AG's
  `treeData` with `rowModelType: 'serverSide'`, `isServerSideGroup` and `getServerSideGroupKey` onto it (`?mode=server-tree`).
- **drishti-chart, the panel charts** (`drishti_chart-panels.js`, `drishti_chart-panel-draw.js`, moved from the renderer's `m/charts.js`
  and `m/draw.js`): `panelOption(kind, data, tokens, size, view)` builds the console's own options for line, area, waterfall, histogram,
  scatter, candlestick, graph, donut and the surface (heatmap or 3D), series for series the frozen 1.2.0 builders' (a Node test compares
  them); ticks on drishti-core's `formatCompact`. `PanelCharts(root, {engine, gl, geo, onLink, onPlaces})` draws a view's chart
  containers (sizes, theme redraw, clicks that name an entity, the surface's switch, the map from the console's shapes) and holds every
  engine call a panel chart makes. The console's `drishti_view.js` and `drishti_charts.js` move onto it in step 5.
- **drishti-data, the live client**: a `fetch` transport (Server-Sent Events read with fetch, so a bearer token travels; the headers a
  function of the last refusal), an address asked at every connect (a channel whose subscriptions are in its address), `eventField`
  (each message carries its SSE event name) and `parseSse`. The renderer's streams and Elements' one connection per page use it.
- **drishti-core**: `attrAllowed` and `safeUrl` (the rules for drawing untrusted data), `formatPythonFloat` (an older server's raw candle
  prices), `textSortKey` and `compareTextKeys`; one script loader (`loadScript`, also for a library's engine).
- **Harness and tests.** `static/elements/drishti-renderer/drishti_harness.html` draws the parity suite's ViewModels
  (`drishti_fixtures.json`, written by `tools/elements/renderer_fixtures.py`) one shot or streamed, in each behaviours mode and theme, with
  no server; `tests/test_elements_renderer_browser.py` (four tests, about 5 s) tests the renderer's wiring of the components, which are
  tested in their own harnesses and Node files (`doc-table.test.mjs`, `tree.test.mjs`, the panel section of `chart.test.mjs`, the fetch
  transport in `data.test.mjs`).
- **Open.** The controls, the column funnels with Chart any table, and Zoom in `behaviours: 'console'` are still the console's scripts
  (they move with their screens: drishti-inputs for the controls, drishti-grid's column menus for the funnels); the renderer does not use
  drishti-inputs yet. About takes its source from `options.insight.about` (drishti-insight's hook, 8.10). The libraries 2.0.0 imports are
  not per-version folders yet (its integrity record covers its own modules). The engine's model keeps under 530 lines a module: its pivot, export and kept state are `drishti_grid-model-base.js` (`GridModelBase`, which `GridModel` extends).

### 8.6 As built: the general screens moved (history, data, Admin, Help, landings, live)

The screens outside BI, the Pivot, VaR and exposure (those move on their own branches) now draw with the libraries:

| What | Was | Now |
|---|---|---|
| Every server table's sort, filter box, column funnels, pages, rows per page, keys (↑ ↓ PgUp PgDn Home End Enter K), a zoomed panel's fit, the state kept through a live repaint | `drishti_tables.js` (474 lines, on every page) | **drishti-grid's console tables** (`consoleTables`, `drishti_doc-console.js`); `drishti_tables.js` is their host (90 lines: the funnels, the keymap, Chart any table). Every page and the embed load drishti-grid |
| A view's panel charts: line, area, waterfall, histogram, scatter, candlestick, graph, donut, the surface (heatmap or 3D) and the map | `drishti_charts.js` (421) and the charts of `drishti_view.js` (176 of its lines) | **drishti-chart's `PanelCharts`** (the client renderer's), hosted by `drishti_charts.js` (95: where the charts are, their data, what an entity click does). `PanelCharts` gained `attr`, `relayout` (a waterfall's labels fitted again to a new width), `update` (a live point merged, through `drishti:chart-data`) and `state` (the page-settled signal asks it, not the engine) |
| The history page's chart (the crosshair follows the selected row) and a field's history in a view | ECharts called by `drishti_history-page.js` and `drishti_history.js` | **drishti-chart's `Chart`**: `pointAt(index)` and the `pointerMoved` event join it; a category or time axis shows no second label under the crosshair (QA1-15, now for every chart) |
| Calc: the editor and its charts | `CodeMirror.fromTextArea` and `echarts.init` in `drishti_calc.js` | **drishti-inputs' `CodeEditor`** (Python; `keys`, `indentUnit` and `refresh` join it; the error line is a lint mark) and **drishti-chart** |
| The browser's one live connection | the hub's own `EventSource`, retry and close (`drishti_live-hub.js`), and `drishti_server-watch.js` wrapping `window.EventSource` | **drishti-data's `LiveClient`** (the hub keeps the tabs, keys, replay and session checks; the SharedWorker loads drishti-core and drishti-data beside it; `LiveClient` takes the clock's timer functions); the server watch hears `drishti:live-error` from the channel |
| Numbers on the admin, data, landing, history, form and build screens | `toFixed`, `toLocaleString`, `Intl.NumberFormat` (35 files) | **drishti-core**: `formatNumber`, and the new `formatBytes` and `formatDuration` (one home for sizes and timings); a hand-drawn SVG's coordinates are geometry, rounded, not formatted |

**The guards** (section 11), `tests/test_elements_guards.py`, a quick file check of the gate: no console script calls ECharts
itself, sorts a table by its headings (`aria-sort` in a script), formats numbers on its own (`Intl.NumberFormat`, `toFixed`,
`toPrecision`, a number's `toLocaleString`; a date's is a date) or opens its own `EventSource` or `WebSocket`. The allow-list names
only files the BI and Pivot branch (`wip/e-migrate-bi`) and the VaR and exposure branch (`wip/e-migrate-var`) are moving, each with its
branch, and only shrinks: an entry whose file is gone or keeps the rule fails the check. **The generic libraries know no domain**
(the product owner, 2026-11-07): drishti-core, -grid, -chart, -inputs and -data hold no domain word in their code (VaR, ES, EEPE,
PFE, MTM, P&L, exposure, backtest, Basel, netting, counterparty, a `risk.` kind); a domain's logic lives in its library (risk:
drishti-risk) behind a generic hook. The chart studio's options for the risk kinds became such a hook (a kind declares `studio:
['log', 'zoom']` when it registers). Comments, the harness pages and the generated manifest's titles are documentation; a threshold
(`limit`) is a generic hook.

The multi-select, the as-of date, the raw date fields, the controls' pickers, the history range and the people pickers moved in
2.9.11.e (8.2, "Moved onto them"); the YAML editors moved onto the designer kit (8.9). **Still to move**: the forms (`drishti_form.js`,
`build/drishti_forms.js`, `drishti_bi-format-pane.js`, `drishti_editor-table.js`, `drishti_subscribe-when.js`, the rest of
`drishti_record-forms.js` → `Form`, which first needs custom widgets, per-field hooks and a time type; 8.2), the formula inputs
(`drishti_editor-formula.js`, `build/drishti_expr.js` → `CodeEditor` hooks), polling and staleness
(`drishti_refresh.js` → a data source), and inside drishti-grid the renderer's simpler document tables (`enhanceDocTables`' sort and
pager) fold into `consoleTables`.

### 8.7 As built: the distribution

Other applications take the libraries as files kept inside the organisation; nothing is published to a public registry or CDN (the
owner's decision of 2026-11-07). How to use them: [ELEMENTS_IN_YOUR_APP.md](../guides/ELEMENTS_IN_YOUR_APP.md). How they are made:

- **One code path, three forms** (`tools/elements/dist.py`, offline, Python only). A library's files are laid out as the console's
  static root: `elements/<library>/src/*.js` (the ES modules), its bundle, its own sheets from the manifest's `css`, the vendored
  engine of its `scripts` with that folder's LICENSE and NOTICE, the folders of its `assets` (a new manifest key, not read by the
  loader: `vendor/echarts/maps/` for drishti-chart, `vendor/codemirror/` for drishti-inputs), `drishti-<name>.d.ts`, a README and a
  licence notice; drishti-core adds `css/drishti_tokens.css` and `themes/drishti-theme-<name>.css`. The whole tree adds
  `elements/drishti_elements.json` (the train, without the libraries left out), `CHANGELOG.md` (each library's lines of the product
  CHANGELOG, `elements_catalogue.changelog`), `LICENSE-NOTICE.txt`, `README.md` and `drishti-elements.json`: the libraries, the themes,
  the libraries excluded and every file's bytes and SHA-384 integrity hash. Because the layout is the console's, drishti-core's loader
  works in it unchanged.
- **The zip** `drishti-elements-<train>.zip` is that tree under `drishti-elements-<train>/` (with a domain included:
  `drishti-elements-<train>+banking.zip`). **The npm tarballs** `@drishti/elements-<name>` (`npm/drishti-elements-<name>-<version>.tgz`,
  `npm pack`'s layout, written with `tarfile`) hold one library flattened: its folder and `css/` at the package root, `vendor/` and
  `themes/` kept; imports of another library rewritten to its package name (`../../drishti-core/src/drishti_index.js` →
  `@drishti/elements-core`, a JSDoc `import('…')` to `@drishti/elements-core/src/…`), the bundle's Node requirements likewise;
  `package.json` (`exports`: `import` the ES entry, `require` the bundle, `types`; `src/package.json` marks the modules as ES modules,
  the root stays CommonJS for the bundle; `sideEffects` only the entry, the bundle and the sheets; `dependencies` the required libraries
  at `^version`; no registry is named) and `integrity.json`. **The WebJar** is Maven's: the module `drishti-elements` (in the root pom's
  profile `elements`, versioned with the train, not the product) runs `tools/elements/cli.py webjar-tree` at `generate-resources`, which
  writes the tree under `META-INF/resources/webjars/drishti-elements/<train>/` and refuses a module version that is not the train's.
  All three are reproducible: fixed timestamps (npm's 1985-10-26 for the tarballs, 1980-01-01 in the zip), sorted entries, mode 644.
- **The declarations** (`tools/elements/dts.py`) are written from the same parse as the API reference (`tools/docs/elements_api.py`):
  every name the entry exports (functions with their parameters, classes with their documented methods, constants), every `@typedef`
  as an interface or type and every `@callback` as a function type; JSDoc types are read into TypeScript (unions, `?T`, `Object<K, V>`,
  records, `function(A): R`, literals); a type of another library is `import('<that library>').Name`; what cannot be read is `any`.
- **The themes** are read from `drishti_tokens.css` by its blocks: a block whose selector is only `:root` and `[data-theme]` is a theme
  (the one that also holds the bare `:root` is the default), the no-theme `prefers-color-scheme` fallback is dropped, every other block
  (the fixed colours, the scales, print) is kept. A theme sheet is the default's tokens overlaid by the theme's, on `:root`, then the
  shared blocks; `dark` and `light` name the default dark and the first light theme.
- **The pack** (`tools/elements/pack.py`, `drishti.py elements pack`): the named libraries and what they require, deepest first, each
  once; a library with a `module` refused unless that domain is included; one JavaScript file (the licence once, a comment naming every
  library and version, a prelude setting `Drishti.elements.base` to `<the file's folder>/elements/` so lazily read files come from
  beside it, the vendored engines unless `--no-engines`, then the bundles) and one sheet (the tokens, every theme or one, then each
  library's sheets), each library's own licence header removed; `drishti-elements-pack.json` records what is inside with SRI hashes.
- **The server** keeps serving the same files: `/static/elements/…` on its origin and `/embed/v2/elements/<library>/…` to registered
  host applications.
- **Tests.** `tests/test_elements_dist.py`: the zip, tarballs and WebJar hold what their manifests say with matching hashes, the same
  bytes in all three, reproducibly; the Maven profile builds the jar (offline; skipped without Java or the exec plugin in the local
  repository); dependency resolution and the banking exclusion; themes; every export declared. `tests/test_elements_pack_browser.py`
  (about 3 s): a static page with only the two packed files draws a grid and a chart under a strict CSP and asks for nothing else.

### 8.8 As built: drishti-graph and drishti-tree

The product owner's picks of section 16 (2026-11-07): **g5 option A** is drishti-graph's default and **g6 option B** drishti-tree's; the
other options are **modes of the same components**, presentation only, one engine underneath each. Both are generic (no domain word in
their code: the guard of section 11 covers them); the banking variants are presets in drishti-risk.

**drishti-graph** (`static/elements/drishti-graph/`, requires drishti-core and drishti-grid, whose grid draws its tables):

- **The model** (`drishti_graph-model.js`): nodes and edges by id, cyclic or acyclic, directed or not, with an adjacency index both
  ways; any other field of a node is an attribute (text) or a measure (number). Changes come as transactions (`addNodes`,
  `updateNodes`, `removeNodes`, the same for edges); an edge naming a node the graph lacks is refused in words; a node removed takes its
  edges. `find(words)` ranks an exact id, then a label that starts with the words, then any.
- **The algorithms** (`drishti_graph-algo.js`): the shortest path by hops (breadth first) or by a cost (Dijkstra; the view uses 1 /
  weight with `weightedPath`), the rings of hops round a node, connected components, and the layers of a directed graph with its cycles
  broken (edges back to a node still on the depth-first walk read reversed; longest path from the sources; a source pulled next to what
  it feeds; a `rank` given wins: the lineage's stages).
- **The layouts** (`drishti_graph-layout.js`, `drishti_graph-force.js`): **force** (Barnes-Hut on a quadtree in typed arrays, springs
  to a rest length, a pull to the centre, cooling; a sunflower start and a seeded generator, so the same graph and seed always give the
  same picture; a node new to a picture starts next to a placed neighbour, so a live change moves little), **layered** (the layers,
  each ordered by its neighbours' barycentres in six sweeps; top-down or left to right), **radial** (rings of hops round the busiest
  node or a root given), **circular** (by type, then label), **grouped** (a frame per group packed on shelves, the most connected first,
  its nodes in a grid: what the data map draws) and **preset** (the positions given). Pinned nodes keep their places in every layout.
  The force layout runs as a generator, about 24 ms of steps a frame, so the page stays responsive; the picture is shown as it settles
  (every 120 ms) and the move between two layouts is a 400 ms tween, neither when the page asks for reduced motion (`animate: false`
  too).
- **What is drawn** (`drishti_graph-scene.js`): types filtered out, nodes hidden, what is below a collapsed node (reached only through
  it), and **groups by an attribute** folded into one node each (a group opened is drawn as its members; edges between groups summed,
  with their count). A group's figure of a measure is the sum of its members' **only for a measure that adds up**; a measure marked
  `additive: false` is never summed (the inspector says "not summed" for a selection too). Then the encodings: size by a measure (area
  in proportion), colour by an attribute (the palette) or a measure (a ramp, diverging when the values cross zero), shape by type,
  edge width by weight; the legend says so. The palette, tones and ramps are drishti-core's `visualTheme`, which drishti-chart now uses
  too (moved from it, with linked selection's `SELECTION_EVENT`, `linkGrid` and `publishSelection`).
- **The canvas** (`drishti_graph-canvas.js`): the edges in a few batched paths (by width and state), arrows only where they can be
  seen, nodes outside the view skipped, labels only where they fit (always for the selected, the path, the node under the pointer and
  the keyboard's node), edge labels where there are at most 80 edges, group frames, status badges, pins; everything else dimmed while a
  selection, a path or a search highlight is shown. A grid of buckets finds the node under the pointer without a scan. The minimap is
  the same scene drawn small with the view's window.
- **The component** (`Graph`, `<drishti-graph>`; `drishti_graph-base.js`, `-view.js`, `-chrome.js`, `-pointer.js`, `-tables.js`): the
  wheel zooms at the pointer, a drag pans, + − ⤢ and the minimap; a click selects (Shift or Ctrl adds), Alt-drag or the ◌ tool draws a
  lasso, a node dragged is pinned, a double click expands a node or opens a group; **expand on demand** through the host's `expand`
  hook (lazy; without one, what was collapsed or hidden comes back), collapse, hide, pin and unpin; **the path** between two nodes typed
  in the toolbar (or "Path from here", or the first two selected), highlighted and fitted; **Explain** through the host's hook, drawn by
  drishti-core's `renderExplain` in the inspector; **linked selection** on drishti-core's bus (`link: {channel}`; a drishti-grid joins
  with `linkGrid`, a chart with its `link`); `highlight(ids)` for a page's search; **export** PNG (2×), SVG (vector, every node and
  edge), CSV (nodes, then edges) and JSON (the data); **live updates** by `applyTransaction`, drawn once per throttle window (200 ms);
  the state (`getState`/`setState`: mode, density, layout, encodings, filters, selection, pins). Events (`GRAPH_EVENTS`) go on the
  component's bus and as `drishti:<name>` DOM events on its host; its inner tables' events stay inside.
- **The three modes** (g5): `inspector` (A, the default: the rail with the types and their counts, group, size and colour, and the
  legend; the canvas; the inspector with the node's measures, attributes, degree, actions, Explain and neighbours, or Σ of a selection's
  additive measures; the selection as a drishti-grid under the canvas), `table` (B: the controls float on the canvas, a card at the
  node, the Nodes, Edges and Path tabs below as one drishti-grid with a filter, "selected only" and "Select these on the graph", linked
  both ways) and `terminal` (C: the `GRAPH>` command line with `help`, `find`, `select`, `expand`, `collapse`, `path`, `layout`,
  `group by`, `size`, `colour`, `filter`, `pin`, `unpin`, `hide`, `top <n> <measure>`, `explain`, `export`, `fit`, `clear`; Tab
  completes the command, then node ids, attributes, measures, layouts or types; ↑ ↓ history; F1 to F9 and Esc; the answers name nodes
  as links). Three densities and light and dark from the tokens, as every component.
- **Accessibility**: the canvas is a focusable `role="img"` whose words say what is drawn; the arrow keys move to the neighbour that
  lies that way (else the nearest node that way), each move announced with the node's type, neighbours and selection; Enter selects,
  E or F2 explains, X expands, C collapses, P pins, Delete hides, + − 0 zoom and fit, Esc clears. **▦ the adjacency table** is the text
  alternative (each drawn node with what it points to and what points to it, its edges), a drishti-grid.
- **The lineage preset** (`lineageOptions`, `lineageHealth`; `drishti_graph-lineage.js`): stages as columns of the layered layout left to
  right (sources → tables → models → reports by default; a host names its own), a shape and a tone per stage, status badges (ok, warn
  or stale, bad or failed, running), and what a failure reaches downstream.
- **The view's graph panel** (`drawGraphPanel`; `drishti_graph-panel.js`): a Sutra's `kind: graph` (nodes with group, link and focus;
  `layout: tree | force`) drawn as the table mode without its tables or minimap: groups as types, `tree` as the layered layout, the
  panel's own node outlined, a click on a node that names an entity opening it through the host. drishti-chart's `PanelCharts` hands the
  panel over through its `graph` hook (it does not import drishti-graph): the console loads the library when a view has a graph, the
  renderer imports it, the embed serves it (`elements-graph`); its sheet is part of `<drishti-view>`'s and the renderer's.

**Performance** (5,000 nodes, 20,000 random edges, one core; `tests/elements/graph.test.mjs` and
`tests/test_elements_graph_browser.py` print them):

| Step | Node 22 | Chromium (the harness's `?view=big`) |
|---|---|---|
| force layout (140 steps, Barnes-Hut θ 0.9) | 2.2 to 2.7 s | 4.7 s to settle on screen, the page responsive throughout (24 ms of steps a frame, a picture every 120 ms) |
| layered / radial / circular / grouped | 0.39 / 0.08 / 0.01 / 0.03 s | the same order |
| folding into 12 groups | 0.04 s | |
| a full frame (5,000 nodes, 19,995 edges) | | 11 to 17 ms |
| the component made (model, scene, chrome) | | 0.2 s |

Above about 50,000 nodes a WebGL drawing and a layout in a Web Worker would take over (section 16's note); neither is built: no
Drishti screen draws that many today.

**drishti-tree** (`static/elements/drishti-tree/`, requires drishti-core and drishti-grid):

- **The model** (`HierarchyModel`, `drishti_tree-model.js`): rows with a parent, a nested root, or paths; further parents in `also`
  (a node read from two parents, marked ⧉); expand and collapse; search that opens the matching branches and gives each match its
  breadcrumb; measures (`TreeMeasure`: field, label, format, `additive`, `rollup`, `childSum`, a `bar` in bands). A measure with
  `additive: false` (◆) is **never summed in the browser**: `requests()` lists the nodes that lack it and the host's `serverMeasures`
  hook answers (debounced); `childSum` shows the children's figures added up beside the node's, the gap being the diversification.
- **The four modes**, one engine: `grid` (g6 B, the default: the indented tree **on drishti-grid's tree data**, `treeData` and
  `getDataPath`, with the client's aggregation off, a column per measure and the bar; drishti-grid draws the ◆), `org` (A: top-down
  boxes in a tidy layout with figures and the bar; a lineage drawn as layered), `sunburst` and `icicle` (C: sized by an **additive**
  measure only, refused in words for a non-additive one; coloured by another). SVG drawings with a text alternative (a table) for the
  sunburst and the icicle; `role="tree"` with levels and expansion in the org chart.
- Explain on a click (the host's hook, `renderExplain`), linked selection on the bus, export (PNG, SVG, CSV, JSON), the keyboard
  (arrows, Home and End, + and −, Enter or E explains), three densities, light and dark; `TREE_EVENTS`, `<drishti-tree>`.
- **The edit mode** (`editable`): a node dragged onto a new parent (or "Move to" from the keyboard) is a proposed move; a move under
  its own descendant is refused; the moves collect into **one proposal** (`proposal` event, `drishti:proposal`) the host submits to the
  Approvals API for four eyes; nothing changes until the host calls `applyProposal` (or `discardProposal`).
- **A screen's own tree** (`drishti_tree-hooks.js`): `chrome: false` draws the view alone inside the screen's panels; `grid`
  (`TreeGridHooks`) gives the node's cell (`labelColumn`), more columns reading the node's own row (every grid row carries its node's
  fields), a row's class and data-* attributes, the widths given and every resize handed back to keep, and `fitRows`; a measure's
  `column` changes its column (header, width, format, look; the tree's waiting mark and bar still draw first). **Lazy children**: a row
  with `lazy` (the model's `lazyField`) has a waiting row under it (`lazyText`, or why they did not come) and `loadChildren` is asked
  the first time it is open; the answer joins the model as nodes (`addChildren`; a row in it may be lazy in turn), an answer for nodes
  read again since is dropped and asked again. `select(id, {quiet: true})` follows the screen's own selection: marked, nothing opened,
  no event.
- **The outline** (`TreeOutline`, `drishti_tree-outline.js`): a `Tree` in grid mode with `chrome: false` for a narrow panel of an
  authoring screen with its own filter box and buttons. `marks(node)` draws words after a node's label (`OutlineMark`: a tone, a badge,
  a title, mono, clipped), `rowClass(node)` a row's class; `filter(q)` keeps the matching nodes and the nodes above them (the rest
  leave the tree, their open state remembered; `outlineFilter`); the rows answer a tree's keys (`outlineKey` on `keyMove`: Home and
  End, Right and Left to open, close and go up, Enter or Space opens a branch or shows a leaf's detail); `detail(node, box)` fills the
  box under the tree (Enter or a click; Enter again or Escape closes it); `onKey(node, event)` is the host's keys first; `nodeAt(el)`
  names the node under a pointer for the host's drag.
- **The table tree** (`TreePanel`, `treeTables`, `readTableTree`, `isChain`; `drishti_tree-panel.js`): a server's table with rows
  nested under rows (`table.tbl-tree`, a Sutra table or ladder with `children:`) read from its DOM (each row's depth, the server's open
  state, every cell's text, tone class and content: links and badges kept as drawn; the footer's lines) and drawn in two modes:
  `grid` (drishti-grid's tree data, a column per heading, a figure's text ordered by drishti-core's `textSortKey`, never summed; the
  highlighted row a class) and `org` (the org chart's drawing over a `HierarchyModel` of the same rows, the first two other columns in
  each box; Enter or a double click follows the row's link). A tree that is one chain (`isChain`) opens as `org`, any other as `grid`;
  a switch changes it. The filter keeps a row whose cells hold every word and its ancestors (the grid's tree filter; the org chart
  marks the matches and opens their branches). The server's table stays in the page, hidden, for print and pages without scripts.
  `treeTables(scope, {memory, scope, live})` draws every tree table under a scope once and keeps each one's mode, open rows and filter
  by key in the host's `memory`, so a live patch that draws the panel again keeps them.

**drishti-risk's presets** (options only: a page loads drishti-graph or drishti-tree itself): `counterpartyStructureGraph(rows, links)`
(groups → legal entities → netting sets → CSAs, layered top-down by level, sized by EAD; EAD, MTM and collateral add up, PFE does not)
and `varHierarchyTree(rows)` (bank → divisions → desks → books with VaR, ES, the limit and its use as ◆ figures, positions and
notional additive; the grid mode in the terminal density and layout, as the VaR screens; the figures in `riskMoney`, a node without a
limit use saying why, `LIMIT_NOTE`, *no limit* by default; a screen picks its `fields` and changes a measure's label or column).

**What moved onto them, and what was deleted:**

| Was | Now | Deleted |
|---|---|---|
| the view's `kind: graph` panel (the counterparty hierarchy F3 HIER, the `relationships` and showcase examples), drawn with ECharts' graph series in drishti-chart | drishti-graph's view panel, in the console, the renderer and the embed; the same nodes and edges (the panel's data unchanged), the Entities list under it kept | drishti-chart's graph option and its layer placement (`drishti_chart-panels.js`) |
| the data map (`/datamap`, `DATAMAP`): a server-placed SVG of domain boxes, kind nodes and join lines | drishti-graph in the grouped layout (domains as frames), **Layered** the lineage DAG of the joins; selection, chips, Find (a highlight) and the layout in the address; the browser test checks the drawn kinds and joins equal the server's model | the template's SVG, its CSS, the server's layout (`_layout` and the coordinates in `core/datamap.py`) |
| Build › New pack's kinds-and-links graph (a hand-drawn SVG) | drishti-graph, circular, the field on each arrow | the SVG drawing and its CSS |
| the VaR explorer's scope tree (drishti-risk's `riskGrid` with drishti-grid's tree data, the rows, lazy parts and open state kept by the screen) | drishti-tree through `varHierarchyTree`, `chrome: false`: the same rows and server figures (VaR, ES, the limit use bar, Parametric and Gap), positions and trades through `loadChildren`, *+ basket* and the chip in the node's cell, the current scope a row class | the explorer's tree columns, its rows' paths, its own lazy loading and open-state bookkeeping, its limit bar (`ubar`, `.vx-ubar`) |
| the Build workbench's shape tree (a hand-built `role=tree` list: conflicts and rare fields, the fields with type, role, presence, examples, a filter, Expand and Collapse, Enter for a field's reason, `S` and `B`, drag a field onto the canvas) | drishti-tree's outline (`TreeOutline`): the same nodes and marks, the filter hiding what does not match, the detail with the workbench's buttons under the tree, `window.Drishti.ShapeTree` kept (`show`, `fieldAt`) | the list's DOM, keys and filter code and its CSS (`.bs-tree`, `.bs-row`, `.bs-twist`, `.bs-name`, `.bs-type`, `.bs-pres`, `.bs-ex`, `.bs-detail`) |

| a table or ladder with `children:` (`table.tbl-tree`): ▸/▾ buttons, the filter box keeping ancestors and the column funnels of the console (`drishti_tree-rows.js`), and the renderer's own copy (drishti-grid's `tree` and `treeVisibility`) | drishti-tree's table tree (`treeTables`): the tree grid, or the org chart for one chain, in the console, the renderer and the embed (`elements-tree`; its sheet in `<drishti-view>`'s and the renderer's); the same rows, texts, tones and links; the mode, open rows and filter kept across live patches | the console's hand-built toggles and filter (`treeTable`, its address state), drishti-grid's `tree` and `treeVisibility`, their CSS (`.tr-q`, `.tr-bar`, `.tr-count`) |
| the GLEIF entity's parents chain (`lei-entity` `PAR`): a flat table, direct parent first | the same panel as a tree from the ultimate parent down to the entity (Rachana `take`, `let` and the document; no engine change), opening as the org chart; the group tree (`lei-group` `TREE`) the tree grid with the org chart a click away | the flat chain's order |

| the Drishti Risk home's VaR hierarchy (`drishti_risk-landing.js`, `rl-tree`: indented rows to depth 2 with VaR and a limit pill) | drishti-tree through `varHierarchyTree`, `chrome: false`, in the panel: every node with the server's VaR and limit use (the bar in bands; *no limit* / *n/c*), open to the desks, a node's name the link to `/var?scope=…&hierarchy=…` | the rows, their depth classes and CSS |
| the exposure explorer's scope tree (`drishti_exposure-explorer.js`, `xx-tree`: hand-built rows with ▸/▾, the open state in `S.expanded`, a set's trades drawn under it) | drishti-tree, `chrome: false`: the bank, legal entities, counterparties (under each legal entity), netting sets and a set's trades through `loadChildren` (the 30 largest, the open trade wherever it ranks, *… n more in the Trades tab*); peak PFE `additive: false`, every figure the server's; a click opens the scope, the way to the current scope opens and its row is marked (`xx-current`, `data-scope`) | the rows, twisties, open-state bookkeeping and their CSS (`.xx-row` and the rest) |
| the raw document viewer and F9's drawer (`drishti_raw-viewer.js`: a hand-built `role=tree` of `.jt-n` rows, ▸ buttons, *Show all* buttons) | drishti-tree's tree grid (loaded on demand) with a **Key** and a **Value** column: the document's order, open two levels, the path opened, selected and scrolled to, an array over 50 cut with *Show all* as a row, search keeping the matches and their ancestors (open), masked values as sent, every colour a token (`.jt-key`, `.jt-num` …); `Drishti.Raw.render` returns a promise of the tree | the tree class, its toggles and its CSS (`.jt-n`, `.jt-l`, `.jt-c`, `.jt-t`) |

The GLEIF relationship (`lei-relationship`) is one child and one parent (a key-value panel and the two entities' cards): no chain to
draw. The Build home's lineage is a column of links: no graph to move. No hand-built tree is left on a screen.

**A tree's filter in the address.** Before the move, a table with children kept its column filters in the console's address
(`?f.<panel>.<column>=…`, USER_GUIDE.md › Filter a table like Excel), and a test said so. The table tree keeps that promise with its
filter box: `treeTables(scope, {address})` takes the host's page address (`read(panelId)`, `write(panelId, words)`); the console
writes `?tf.<panel id>=<words>`, so a reload or a shared link shows the same rows (not inside `<drishti-view>`: the host's address is
not touched).

**Touch.** drishti-grid draws rows that open and close (a tree, groups, a pivot) 44 px high (inside the row line) on a touch screen (`pointer: coarse`) or
a phone (`data-phone`), with the twisty 44 px wide: a target a finger can hit, for every tree on the grid. Other grids keep their
density's height.

**Tests.** Node: `tests/elements/graph.test.mjs` (13: the model, paths, layers, every layout and the timings, the scene, encodings,
hit testing, export, the terminal's commands and completion, the lineage, the panel options, the bundle) and
`tests/elements/hierarchy.test.mjs` (11). Browser, against the static harnesses (no server): `tests/test_elements_graph_browser.py`
(7, about 15 s on 3 workers: every mode in both themes and three densities; zoom, pan, fit, the minimap, click and Shift, the lasso,
drag and pin, the tooltip, the keys and the adjacency table; Explain, lazy expand, collapse, hide, the path, groups, encodings, types;
the table mode linked both ways and the terminal; export, live, every layout and reduced motion; a grid linked through the bus, the
lineage and a view panel; 5,000 nodes) and `tests/test_elements_tree_browser.py` (5). The screens test their wiring only:
`test_datamap_browser.py`, `test_pack_new_browser.py`, the renderer's and the embed's files.

### 8.9 As built: drishti-designer, the designer kit

The product owner (2026-11-07): "let us build the designer kit, duplicated code will make the system unmaintainable." Every designer and
editor is built on it; mock-up g7 (all three options taken: the docked studio the default, side by side and the terminal as layouts of
one shell) is its look. `static/elements/drishti-designer/` (version 0.1.0; requires drishti-core and drishti-inputs only; ES modules
under 530 lines each, typed JSDoc, the bundle by `tools/elements/build.py`, `Drishti.elements.designer`):

- **The engine, without a page** (Node-tested). `CommandStack` (undo and redo, steps grouped as one, merging by a key within 800 ms,
  saved and dirty) and `SnapshotHistory` (the same over before and after copies of one JSON document: a host that edits its model in
  place gets undo without inverse operations; the BI report designer's). `Selection` (one per designer page, never global; the primary
  is the last picked; a pick replaces, Shift or Ctrl adds or toggles; each change says its source so a pane ignores its own echo).
  `drishti_layout-rules.js`: the grid's geometry (snap, clamp, overlap, **settle** as a report page settles, align on six edges,
  distribute, bounds) and the 12-column panel rules (span, height or the content's, the sentence a screen reader hears; the old
  `drishti_layout-rules.js` of the console keeps its name for layout mode until it moves). `LayoutModel`: a **grid** layout ({x, y, w,
  h}, z-order, groups that move, copy and delete together, copy and paste with new ids, duplicate) or a **flow** layout (items in order
  inside named areas, a span and a height), and **breakpoints** (a phone's own order, hidden items and heights; arranging back removes
  the entry). Every change is one step on its stack, or, with `stack: null`, a "commit" event for a host that keeps its own undo (the
  Build workbench sends operations to the server; the BI designer keeps snapshots). `designerKeys`: the keymap (the "move" scheme of a
  report page and the "select" scheme of the workbench: arrows select, Alt+arrows move; the common keys: undo, redo, copy, cut, paste,
  duplicate, delete, select all, group, z-order, Enter, Escape), on drishti-core's `keymap`.
- **The YAML, both ways.** `drishti_yaml-read.js` is Sutra Studio's forgiving reader, moved here (it never throws; the cursor's place);
  `drishti_yaml-write.js` writes plain values as block YAML and says which lines each path spans (`yamlRanges`, `pathAtLine`), so the
  selected item is highlighted and a click in the YAML selects its item. `drishti_yaml-assist.js` is Studio's assistant over a JSON
  Schema, moved here and pure: completion of keys (an object's options by its kind), values, Rachana's functions with their signatures
  and the sample's field paths; the light check (unknown keys, missing required keys, options a kind does not take, values outside an
  enum, whole numbers, tabs); the help of the key under the cursor.
- **The components** (each a class and a web component, no shadow root, nothing inline). `LayoutCanvas` draws a grid model (its items
  placed on a CSS grid through the CSSOM; the host's `createItem`, `updateItem`, `removeItem`) or decorates a flow layout the host
  drew (`adopt`); a pointer drag by the item's handle moves it, snapped to the grid and shown live, a corner (grid) or the right and
  bottom edges (flow) resize it; Shift or Ctrl selects more; the keys; drops from the palette through a `DragHub` (one Pointer Events
  drag for every designer: Escape cancels, a click still clicks, the page scrolls at its edges) or HTML5 drops of the host's type;
  `onAction` lets a host do a key itself (true), leave it to the page (false), or let the canvas do it. `sortableList`: a list whose
  rows are re-ordered by dragging (the workbench's form and controls cards, the BI designer's device orders). `Palette` (categories, search, a
  disabled entry says why, drag or Enter). `Inspector` (drishti-inputs' `Form` from the schema the host gives for the selection, live:
  each valid edit is a "change"; several items show the fields they share, a field whose values differ marked mixed). `Outline` (the
  structure as a tree: the tree view's keys, a drag before, after or into another item, Alt+arrows; the host's `canDrop` says why not).
  `YamlPane` (drishti-inputs' `CodeEditor` in YAML mode: written again when the model changes, the cursor and scroll kept; read after
  a pause through the host's `fromYaml`, the server's or `readYaml`; a problem said at its line while the design keeps its last good
  version; lint and completion hooks). `DesignerShell` (EDITORS.md section 1): the header (where you are, the name, the status with
  versions, Validate, Preview, History, Impact, Save draft, Propose for review, + New), Visual / YAML / Both, the left rail's tabs
  (objects with search, palette, outline), the inspector pane, the preview with checks, a status bar, a dialog, and Propose for review
  (a note, the host's submit to the Approvals queue: four eyes). Its **layouts** (`layout`, remembered per person by the host): **studio**
  (g7 A, the default: rail | canvas with the YAML docked under it | inspector | preview), **side-by-side** (B: the outline over the
  palette, the canvas and the YAML side by side, the preview always on, the inspector a card by the selection) and **terminal** (C:
  dense and monospace, a `DESIGN>` command line on Ctrl+K, the outline as a property grid, F3 Validate, F4 YAML, F5 Preview, F7 History,
  F8 Impact, F9 Propose).
- **drishti-inputs extended for it** (no code in the screens): `CodeEditor.updateValue` (the cursor and the scroll kept), `highlight`
  (the selected item's lines), `cursor` and its event, `completeAfter` (a YAML key's "kind: ", a path's "$."), entries that say where
  their word starts, `lineWrapping`, `tabLeaves` (a small box Tab leaves), YAML's Enter keeping its indentation (`yamlIndentAfter`);
  `Form`'s `live` (no Save or Reset: an inspector).
- **Moved onto it, the old code deleted**: the Build workbench's canvas (`build/drishti_canvas.js`: a flow `LayoutCanvas`; the trash,
  grip and size badge are its decoration), its drags (`build/drishti_drag.js`: the kit's `DragHub` and `trackPointer`), its YAML tab
  (`build/drishti_yamltab.js`: `YamlPane` with the schema assistant) and its about box (`CodeEditor`); the BI report designer's canvas
  (`drishti_bi-design-canvas.js`: a grid `LayoutCanvas`, moves and resizes one step of the designer's undo), its undo
  (`SnapshotHistory`), its settle (the kit's) and its YAML tab (`CodeEditor` completing from the report's catalogue, in place of the
  suggestion strip); the table, model and Python editors' shell (`drishti_editors.js` on `DesignerShell`, its YAML on `YamlPane`: the
  hand-made gutter and highlight gone). Deleted whole: `drishti_studio-yaml.js`, `drishti_studio-assist.js`, `drishti_cm-yaml.js`
  (`window.Drishti.yaml` points to the kit's reader).
- **Harness and tests.** `drishti_harness.html` (a report designer built only from the kit, in any layout with `?layout=`; a flow
  canvas; a stand-in approvals queue); Node: `tests/elements/designer.test.mjs` (the stack, snapshots, selection, the grid rules, the
  model in grid and flow, commits, breakpoints, the keymap, YAML both ways and its ranges, the assistant, the inspector's shared
  schema, the outline's moves, the palette, the shell's words); the browser: `tests/test_elements_designer_browser.py` (every layout,
  pointer moves snapped to the grid, the corner, the keys, multi-select, align, group, undo, the inspector's conditional field, the
  outline, YAML both ways and a broken line, Propose for review, a flow canvas's drag and keys, a phone). The gallery has a designer in
  each layout and a flow canvas.

### 8.10 As built: drishti-insight 0.1.0

Library 6 (`static/elements/drishti-insight/`, in the manifest, so the distribution packs it and the gallery lists it) holds the
components that read a Drishti server's answers as the person, moved whole from the console's scripts (nothing kept beside them):

- **The one Explain** (`drishti_insight-explain.js`, was `static/js/drishti_explain.js`): the preview, the drawer with its four tabs,
  the full page at `/explain` (a pivot cell's view carried in the link as one JSON parameter, `body`), the keys E, ? and Esc, and
  `Drishti.Explain` (`open`, `preview`, `close`, `pageUrl`, `bind`, `ask`). The drawing of a figure's explanation stays drishti-core's.
- **The one search box** (`drishti_insight-search.js`, was `static/js/drishti_search-box.js`): `Drishti.searchBox` for the command
  line's type-ahead and Help's search, and `Drishti.find` (Alt+K).
- **The one Approvals queue** (`drishti_insight-approvals.js`, was `static/js/drishti_approvals.js`): it starts on the page that holds
  `[data-approvals]` and does nothing elsewhere.
- **The workbook export** (`drishti_insight-workbook.js`): `workbookLinks(answer)` and `exportWorkbook(request)`, a number's
  live-formula workbook found through the one Explain's Workbook part and downloaded; the server writes the workbook.

`base.html` loads the bundle once for every page (after the keymap); its `install` adds the search box always, Explain unless the tag
says `data-explain="off"` (a page for someone not signed in) and the Approvals queue on its page. Tests: `tests/elements/insight.test.mjs`
(the entry, the workbook export, the queue's start) and the browser files of the screens that use them (Explain, the keymap and the
search, Approvals).

## 9. The documentation of every library

The owner asked for documentation as rich as a commercial product's. Within the lean-docs rule (one home per fact; references are
generated, never written twice) every library has:

| Document | One per | Where | Written or generated |
|---|---|---|---|
| **Guide** (concepts: what it is, how its parts fit, decisions such as server totals) | library | `docs/guides/elements/<library>.md`, a Help page | written |
| **API reference** (every option, event, method, type and CSS custom property) | library | Help → Developer → Elements, and the gallery's API tab | **generated** from typed JSDoc by `tools/elements/api_ref.py`; `--check` fails the build when a public member has no description or a type is missing |
| **Tutorial** (one task end to end: a pivot over your own data, a chart with Explain, a world map with drill) | library | `docs/tutorials/`, numbered with the others | written, with screenshots from the gallery |
| **Live gallery** (runnable examples) | component | `/elements/gallery/<library>/<component>` | the examples are files the tests also run (section 10) |
| **Migration guide from AG Grid Enterprise (pivot)** | drishti-grid | [`docs/guides/AG_GRID_MIGRATION_GUIDE.md`](../guides/AG_GRID_MIGRATION_GUIDE.md) | written; its option table is **generated** from the adapter's mapping (`AG_MAPPING` in `drishti_grid-ag-map.js`, the one home) by `tools/docs/elements_api.py` |
| **Theming guide** | the family | `docs/guides/elements/theming.md` | written (planned); the list of tokens is generated from drishti-core's token file |
| **Accessibility guide** | the family | `docs/guides/elements/accessibility.md` | written (planned); each component's keyboard table is generated from its keymap registration |
| **Changelog** | library | `static/elements/<library>/CHANGELOG.md`, shown on the gallery's Changelog tab | written per release; the product `CHANGELOG.md` links it rather than repeating it |
| **Versioning policy** | the family | one section of the theming guide's sibling, `docs/guides/elements/versions.md` | written once (planned) |

Help lists one entry per library guide and per tutorial, grouped under Developer; the generated references are built at build time and
never committed (as the Javadoc and pdoc references). The documentation tests gain: every public member documented (`--check`), every
gallery example runs, every guide links its API reference and tutorial, no option table written by hand.

## 10. The gallery

[g4](mockups/elements/g4-gallery.html): option A, the three-pane playground (the list of libraries and components with their version and
a badge: no server needed, needs a Drishti server, banking module licence; the live component; code tabs for the web component, plain
JavaScript, a Sutra and, for the grid, the AG Grid adapter; options generated from the typed JSDoc; an event log), with option B's tabs
(Examples, API, Accessibility, Changelog) as the published page. Served by the console at `/elements` (as built: 8.4); libraries 1 to 5 run with
no server (static files and sample data); 6 to 8 against the console's own server. **Each gallery example is a test**: the browser suite
opens every example in both themes and three densities, runs axe, and checks the code snippet produces the same component (the snippet
is the example's source, not a copy).

## 11. The guards

| Guard | What it fails |
|---|---|
| **No second implementation** (`test_elements_no_duplicates.py`) | outside `static/elements/`: a call to `echarts.init`, `setOption`; a `<table>` built with sorting, filtering or paging code; a number formatter (`toFixed` with a unit suffix, `Intl.NumberFormat` outside drishti-core); a drag handle; a hand-made dialog. An allow list holds exactly the files not yet moved and **only shrinks** |
| **Dependencies** | a library importing anything but drishti-core, its vendored engine and (for 6 to 8) libraries 1 to 5; a domain library importing another domain's |
| **No server for 1 to 5** | a reference to `/api/`, `/embed/` or the console in libraries 1 to 5 (their Drishti sources live in drishti-data behind an explicit `server` option) |
| **The browser computes nothing in the console** | a console page creating a grid with the client-side aggregating model (extends `test_console_does_not_aggregate.py`) |
| **Immutable versions** | a changed file in a released version folder without a new version and integrity record |
| **Assets** | an outside URL (`test_assets_policy.py`), a missing `drishti_` prefix inside the console (`test_asset_names.py`), a file over 1,500 lines |
| **Licence** | drishti-risk served, or its kinds registered, without the banking module's licence |
| **Docs** | `api_ref.py --check`, a gallery example that does not run, a hand-written option table |
| **Size budget** | a library's gzip size over its budget (set from the first release, raised only on purpose) |
| **No bespoke UI in a designer or editor** (`test_designer_kit_guard.py`, the standing rule of 3.1) | a script or page with its own pointer drag or drag and drop, its own code or YAML editor (CodeMirror by hand, a code text area), and in an authoring screen its own tree view or undo history; an allow-list of the files still being moved (section 12.3) that only shrinks and is capped |
| **Design tokens** (`test_css_tokens.py`) | a colour literal in a console sheet outside `drishti_tokens.css`, a font size off the type scale, an `@media` width that is not a breakpoint, an undefined `--d-*`, an `!important` not on its allow-list ([4.1, Design tokens](#design-tokens)) |

## 12. Moving Drishti's screens onto the libraries

Each step is one branch with its gate; the screen moves, its old code is deleted in the same branch, the allow list of 11 shrinks, and
the screen's tests and screenshots are unchanged or deliberately updated.

1. **drishti-core**: formats, theme, API client, keymap, dialogs, resize; every script switches its formatter and its fetch.
2. **drishti-data**: one live connection, Arrow, the pivot source.
3. **drishti-grid, plain tables**: every server-rendered table (`drishti_tables.js` and `drishti_colfilter.js`), then tree rows and
   drills, then the renderer's tables.
4. **drishti-grid, the pivot**: the Pivot screen and the MR cube, the nested pivot panel, BI's matrix and tables, the basket's sheet, the
   playground's grid, the designer's grid.
5. **drishti-chart**: the renderer's charts, `drishti_view.js` and `drishti_charts.js`, BI's visuals, Chart any table, history charts,
   then the maps (`drishti_geomap.js`); Vega-Lite visuals translated to the spec and Vega deleted (O1).
6. **drishti-inputs**: controls, forms, pickers, the code and formula editors (Build, Studio, the editors, BI).
7. **drishti-insight**: About and Explain, search, approvals, workbook export.
8. **drishti-renderer 2.0.0**: drawn with libraries 1 to 5; `behaviours: 'console'` from libraries.
9. **drishti-risk**: the VaR explorer's and exposure explorer's charts, backtesting, the stress window, the scenario matrix, FRTB-SA,
   SIMM and the MR lens; moved into the banking plugin.
10. **The allow list empty**; the guard strict; the inventory regenerated into section 2.

### 12.1 The migration checklist

One line per screen or part: what it is drawn with now, what was deleted, and how its numbers were checked. A screen is moved when its
old code is gone and its tests run against the new DOM with the same assertions.

| Screen or part | Moved onto | Status | Deleted | Numbers checked by |
|---|---|---|---|---|
| Pivot screen (`drishti_pivot.js`, `terminal/pivot.html`) | drishti-grid, `layout: 'wells'`, `clientAggregation: false`, `serverPivot` (/api/pivot/query, progressive, compare), Explain = provenance | **moved** (2.9.11.e) | `drishti_pivot-grid.js` (the renderer 1.2.0 keeps the name as a loader of the grid), the field list and zones, their CSS | `test_pivot_screen_browser.py`, `test_pivot_switches_browser.py`, `test_pivot_functions_browser.py`, `test_pivot_expand_browser.py` (grand totals per column against the sample), `test_pivot_grid_fmt.py` (`measureText`), a one-off old-against-new parity on the VaR demo |
| MR cube (`MCUBE`) and CCR cube presets | the Pivot screen; the lens's sparklines and the switches' comments as `decorateCell`; live patches flashed by the grid | **moved** | the lens's and switches' writes into the old grid's cells | `test_mr_cube_browser.py` (every cell the VaR engine's scope VaR, a total never the sum of its rows) |
| Pivot functions' cards (why, what-if, functions) | drishti-inputs `DatePicker` for the two dates; the results stay plain tables (no sort, filter or paging) | **moved** | the two text date fields | `test_pivot_functions_browser.py` |
| Tree rows, the nested pivot (`drishti_tree-rows.js`) | drishti-grid pivot mode, `chrome: false`, the panel's own answer | **moved** | the call into the old grid | `test_tree_rows.py` (in Chromium), `test_render_behaviours_browser.py` |
| Tree rows, the table with children (`table.tbl-tree`) | drishti-tree's table tree (`treeTables`: the tree grid on drishti-grid's tree data, the org chart for one chain) | **moved** (2.9.11.e) | the console's toggles, filter and funnels on trees, drishti-grid's `tree` and `treeVisibility` | `test_tree_rows.py`, `tests/elements/tree-panel.test.mjs`, `test_colfilter_browser.py`, `test_render_library_browser.py`, `test_render_parity_browser.py` |
| BI table, perspective grid, a report's rows and as-table views (`drishti_bi-grids.js`) | drishti-grid, `chrome: false`, conditional formats and terminal links as `decorateCell`, data bars, the engine's totals under the grid | **moved** | the table's own `<table>` and sort code, `.bi-sorthead` | `test_bi_reports_browser.py`, `test_bi_new_visuals_browser.py` (the engine's totals), `test_bi_wave_b_browser.py`, `test_bi_live_browser.py` (masks), `test_embed_report_browser.py` |
| Notebook result tables (`drishti_bi-notebook.js`) | drishti-grid | **moved** | the `<table>` | `test_bi_notebook_browser.py`, `test_bi_wave_c_browser.py` |
| Chart any table, View as table (`drishti_table-chart.js`) | drishti-grid | **moved** | the `<table>` | `test_table_chart_browser.py` (every row in the CSV) |
| BI matrix (`drishti_bi-matrix.js`) | drishti-grid pivot mode | **waits**: values on rows and the tabular layout (one column per row level) in drishti-grid first | | |
| The Pivot's chart of a selection | drishti-chart (`kind: 'bar'`, the diversification and the total beside the parts) | **moved** | its ECharts calls | `test_pivot_screen_browser.py` (the engine's gap, never one added up) |
| BI's visuals, Chart any table's chart | drishti-chart | **waits** for the chart migration (step 5) | | |
| BI date and time controls (`bi/report.html`, `notebook.html`) | drishti-inputs `DatePicker`, `DateRangePicker` | **waits** (step 6) | | |
| The pivot engine's key texts and formats (`drishti_pivot-engine.js`) | drishti-core formats | **waits** for the renderer 2.0.0 (it loads the file into host pages) | | |

The embed (Elements 1.4.5) loads drishti-core and drishti-grid with the console's scripts, and its view sheet carries the grid's style, so
an embedded view or report draws the same grid.

### 12.2 The VaR and exposure family (step 9, 2.9.11.e): the migration checklist

Every table of the family is a `<drishti-grid>` made by drishti-risk's preset `riskGrid` (the `terminal` layout and density, the
product owner's default for VaR screens; the grid's own command line, wells, toolbar and function keys off inside a screen that has
its own; `clientAggregation: false`; VaR, ES, limits and exposures `additive: false`, never summed in the browser), every chart a
`<drishti-chart>` made by `riskChart` (drishti-risk's kinds where one fits), the figures in words by `riskMoney`, `riskSigned`,
`riskInt` and `riskPct` (one home; the texts the screens printed before, so no figure changed). What the libraries gained for it:
drishti-grid 0.4.0 (a column's `cellClass` and `cellRenderer`, a row's `getRowClass` and `getRowData`, `fitRows`, `chrome: false`,
`pinnedTopRows` with `pinnedCellClicked`, a tree node's own row in its cell context), drishti-chart 0.2.0 (the histogram of the server's
bins), drishti-risk 0.2.0 (the P&L distribution from the server's bins with its fitted normal; the presets).

| Screen | Before (file, lines) | After | Tables → grids | Charts | Old code and CSS deleted |
|---|---|---|---|---|---|
| [x] VaR explorer (`VAR <scope>`) | `drishti_var-explorer.js` 1,343, `drishti_var-charts.js` 208, `drishti_var.css` 217 | explorer 1,326, charts deleted, CSS 168 | the scope tree (tree data: nodes, a book's positions and a position's trades asked when opened; the column widths kept per mode), the path, the scenario's contributors, the history, the contributions (Euler totals the server's, under the grid), the captures, a capture reopened, a comparison, the currencies | `risk.pnl-distribution` (the server's bins, VaR and ES), the worst 25 and the P&L by scenario date (bars, the VaR scenario and the tail toned, −VaR line), the history (P&L, −VaR, −ES, exceptions) | the SVG charts and their tip, the hand-built tables and the tree's rows |
| [x] Parametric VaR (`PVAR`) | `drishti_var-parametric.js` 124, `drishti_var-parametric.css` 46 | 132, CSS 35 | the tree's Parametric and Gap columns (grid columns with the ?) | the fitted normal and its marker on the P&L distribution (the server's curve) | the tree cells, the SVG curve, tails and gap bracket (the gap is in its tile) |
| [x] Basket sheet | `drishti_var-basket-sheet.js` 292, sheet CSS 22 lines | 186, 7 | one grid with cell editing: typing, Enter, Tab, Delete, undo; the paste with headings stays the sheet's; each cell toned by the server's check | — | the sheet's own table, editor, keys and windowing |
| [x] Basket what-if | two tables in `drishti_var-basket.js` (831) | grids (807) | the what-if trades, before and after | — | `vb-table` |
| [x] VaR Playground | `drishti_var-playground.js` 425, `drishti_var-playground.css` 118 | 405, CSS 111 | the workbook grid (the six header rows pinned, the label column pinned, 500 × 509 windowed), the Parametric tab's figures, positions and scenarios | — | the canvas, its windowing, keys and scroll code |
| [x] Backtesting (`VAR <scope> BACKTEST`) | `drishti_var-backtesting.js` 235 | 181 | the zone counts, the bank and its desks (a desk redraws in place), the exceptions | `risk.backtest` with the server's zone, count and multiplier on the card (`spec.light`), both exception sets marked | the SVG chart |
| [x] Regulatory screens (`VRES`, reports, statistics) | `drishti_var-reports.js` 283, `drishti_var-regulatory.css` 96 | 281, CSS 77 | the pilot pack (Generate in a cell), the report as filed (a cell opens its lineage; totals marked), the checks | — | the hand-built tables |
| [x] Stress window | `drishti_stress-window.js` 281, CSS 69 | 225, CSS 52 | the candidates, by node, current against proposed, the proposals | an area of the windows' VaR with today's line, the candidates and the selected window; a click selects | the SVG chart |
| [x] Scenario matrix (`SMAT`) | `drishti_scenario-matrix.js` 869, CSS 130 | 669, CSS 91 | the matrix (a column per date, the heat, V and E, the tail outline), each date's VaR, ES and base PV under it | line, scatter, area or bar with −VaR, base PV, the tail and the VaR scenario marked | about 400 lines of SVG and the virtualised table |
| [x] MR lens and cube panes | `drishti_mr-lens.js` 384, CSS 99 | 392, CSS 86 | the components (the server's total row), the heat map, the ladder, the what-if | `risk.pnl-distribution` of the server's bins, a waterfall to the server's total | the string tables and SVG |
| [x] Risk landing | `drishti_risk-landing.js` 254, CSS 97 | 249, CSS 94 | VaR by desk, exposure by counterparty (the limit use in the cell) | — | the hand-built tables |
| [x] Exposure explorer and counterparty screens (`EXP`) | `drishti_exposure-explorer.js` 985, `drishti_exposure-charts.js` 126, `drishti_exposure.css` 142 | 967, charts deleted, CSS 109 | all nine tables (netting sets with the server's peak PFE as their total, a row drills to its scope) | `risk.exposure-profile` (EE, PFE, the server's EEPE, the limit at the page's confidence, the peak; the uncollateralised EE and a netting set's 5/25/75/95% paths as more series) | the SVG profile and its legend |
| [x] FRTB-SA | `drishti_frtb-sa.js` 250, CSS 52 | 269, CSS 44 | class by measure, scenarios, contributions, the bucket drill, compare, the playground's sensitivities (the formula bar follows the focus) | the capital stack as horizontal bars | the hand-built tables |
| [x] SIMM | `drishti_simm.js` 242 | 241 | the netting sets, against the counterparty, the breakdown, compare, the sensitivities | — | the hand-built tables |

drishti-risk also gained `textColumn` and `moneyColumn` (the exposure, FRTB and SIMM columns), `more` series on the exposure profile, and
the shared page partials `terminal/_risk_elements.html` and `_risk_elements_css.html` load the libraries in order.

**Before and after.** `tests/screen_figures.py` reads every table or grid of a screen as rows by heading, the tiles and the playground's
cells, the same way before and after the move; `tests/test_var_family_figures_browser.py` (run only with `DRISHTI_FIGURES_OUT` or
`DRISHTI_FIGURES_BASELINE`) captures sixteen screens and tabs on the market-risk demo data and compares them with a capture of the code
before the move.

**Pickers.** Every picker of the VaR family and the exposure screens is drishti-inputs' `Select` (`<drishti-select>`, section 8.2): the
VaR explorer's reporting currency (its "More…" group), scenarios, horizon, VaR and ES confidence and hierarchy (a hierarchy skipped on the
date disabled with why), the basket sheet's saved baskets (asked when the list opens, searchable), the playground's horizon, confidences,
currency and workbook size (a refused size disabled with its reason), the stress window's hierarchy and window, the period statistics'
measure (a plain form: the hidden input), and the exposure explorer's pre-deal form (the netting set, the candidate, the method, whose
figures). `terminal/_risk_elements.html` loads drishti-inputs. FRTB-SA's and SIMM's own choice selects (side, by, compare) and the
library's own two (a form's enum of more than three, the grid's select editor) are still native.

**Open.** The exposure scope tree is still hand-built (not tree data yet); the scenario matrix's date headings lose their hover note (in the column
description instead); the screens' scripts move into the banking plugin with the module platform (section 8).

### 12.3 Every designer, editor and authoring tool on the kit (the standing rule of 3.1)

The survey of 2026-11-07 (grep for drags, inspectors, palettes, outlines, code and YAML editors, then each authoring screen read). The
old dataset wizard and model designer were deleted in 2.9.11.d; nothing of them survives (the Datasets page's "New table" opens the
table editor, `/build/tables?new=table`; "the model designer" in two notes means the model editor).

| Screen (files) | On the kit | Still its own (moving next) |
|---|---|---|
| Build workbench (`build/drishti_*.js`) | canvas (flow `LayoutCanvas`), drags (`DragHub`), YAML tab (`YamlPane`, schema assistant), about box (`CodeEditor`), the form and controls tabs' re-ordering (`sortableList`); undo stays the server's operation log | the inspector (`build/drishti_inspector.js` → `Inspector` from the Rachana schema), the palette (`build/drishti_palette.js` → `Palette`), the data-shape tree (`drishti_build-shape-tree.js` → `Outline`), the expression input (`build/drishti_expr.js` → `CodeEditor`) |
| BI report designer (`drishti_bi-design*.js`) | canvas (grid `LayoutCanvas`), undo (`SnapshotHistory`), settle, YAML tab (`CodeEditor` with completion), the Phone and Tablet order's drag (`sortableList`) | the Fields and Visualizations panes (→ `Palette`), the Format pane (→ `Inspector`), the device orders' model (→ `LayoutModel` breakpoints), the expression editor (`drishti_bi-expr-editor.js` → `CodeEditor`) |
| Dataset designer: the table editor, virtual tables (`drishti_editor-table.js`) | the shell (`DesignerShell`), the YAML (`YamlPane`) | the source and look-up rows, the columns checklist and computed columns (→ `Form`, drishti-grid editing), the formula input (`drishti_editor-formula.js` → `CodeEditor`) |
| Model designer: the semantic model editor (`drishti_editor-model.js`) | the shell, the YAML | the tabs, measure cards and the access matrix (→ `Form`, drishti-grid), relationships |
| Python table and model editor (`drishti_editor-python.js`) | the shell, the YAML | the code beside its contract (→ `CodeEditor` Python), the templates |
| + New steps (`drishti_editor-new.js`) | the shell | the guided steps (→ `Form`) |
| Sutra screen designer: Customize, Grid, Outline, Describe (`drishti_designer*.js`, `templates/designer/`) | | the panel handles and drags (→ `LayoutCanvas` flow), the Grid's cells and formula bar (→ `LayoutCanvas` grid, `CodeEditor`), the outline (→ `Outline`), the settings and forms panels (→ `Inspector`) |
| Form designer (the workbench's Form and Controls tabs) | the cards' order by drag (`sortableList`) | their fields (→ `Inspector`) |
| BI Studio shell and theme designer (`drishti_bi-studio.js`, `drishti_bi-theme-designer.js`) | | the explorer, tabs and dock (→ `DesignerShell`), the theme YAML (→ `YamlPane`) |
| BI items, workspaces, transforms (`templates/bi/item.html`, `workspace.html`, `transform.html`) | | the YAML and Python text areas (→ `CodeEditor`) |
| The Notebook's cells (`drishti_bi-notebook*.js`) | | its cells' code (→ `CodeEditor` Python) |
| Calculator, Pivot computed fields (`terminal/_calc.html`, `terminal/pivot.html`) | | their code areas (→ `CodeEditor`) |
| Admin › Connectors, Drishti*net (`templates/admin/_connector_dialogs.html`, `net.html`) | | the YAML areas (→ `YamlPane`) |
| Reader layout mode, arranging a screen, workspaces (`drishti_layout.js`, `drishti_screen-grid.js`, `drishti_workspace.js`) | | the pointer moves (→ `LayoutCanvas`, `trackPointer`) |
| Forms, record forms, alerts, subscriptions (`drishti_form*.js`, `drishti_record-forms.js`, `drishti_alert-form.js`, `drishti_subscribe-when.js`) | | `Form` (wip/e-inputs-convert moves the forms) |
| Packs, lake files, lenses, editions, engines, settings (`drishti_pack-new.js`, `drishti_admin-*.js`) | | their forms (→ `Form` with four eyes) |
| Approvals' change views (`drishti_approvals.js`, `build/_diff.html`) | | the diffs (→ drishti-inputs' `CodeDiff`) |

**Second pass (the allow-list from 19 to 3).** On drishti-core's `trackPointer` (now with pointer capture) or the kit's `DragHub`: the
Sutra designer's Customize drags (parts and the kit's cards) and Grid drags (a cell onto another), the Outline's drags, the BI
designer's field and visual-type drags and its wells, a reader's layout mode, screen arranging, a workspace's panes (captured across
its frames) and a slider's thumbs; every HTML5 drag of these is deleted. On drishti-inputs' code areas (`<textarea data-di-code>`,
`upgradeCodeArea`: the text area keeps the value for its form and its page's script): the connector and Drishti*net YAML, the theme
designer's YAML, a BI item's and a workspace's YAML, the transform's Python and YAML, the Pivot's function code; the calculator draws its
`CodeEditor` in place. **Still on the allow-list**: the Sutra designer's Grid formula bar (its own suggestion list → `CodeEditor`
completion) and the Outline mode's tree (→ the kit's `Outline`); the workbench's data-shape tree is off the list, on drishti-tree's `TreeOutline` (8.8). The editors' own panes in the table
above (inspectors and forms) are not drags or code areas, so the guard does not see them; they move with the forms builder's `Form`.

## 13. Risks

| Risk | Mitigation |
|---|---|
| The grid is the largest new code and the most visible | built first behind the gallery with the Pivot screen as its first user; parity tests against the current pivot's answers and screenshots |
| Performance at millions of rows | virtual rows and columns from the first commit; a gallery page with 1,000,000 rows and a frame-time budget in the browser suite |
| An adapter that looks like a clone | clean-room rule (public documentation only), Drishti's look, our own names in code; reviewed before release |
| Moving every screen is long | ordered by reuse (tables first), one branch per step, the allow list as the progress bar; no screen keeps old code past its step |
| Two charting engines (Vega and ECharts) | Vega retired (O1); a visual outside the translatable subset is refused in words with the alternative |
| Licensing leaks (drishti-risk reachable without a licence) | served by the plugin behind the entitlement and licence check; a guard |
| Outside users pin old versions | immutable folders, a release train, a deprecation window of one release |

## 14. Decisions of the product owner (2026-11-07)

| # | Question | Decided |
|---|---|---|
| O1 | **Vega-Lite's future** | **Retire Vega**: Vega-Lite visuals are translated to the drishti-chart spec (the common subset: marks, encodings, facets) and Python visuals return a drishti-chart spec; visuals outside the subset are listed and redrawn once; Vega, its 845 KB and the expression interpreter are deleted |
| O2 | **The grid on a phone** | grids scroll across with the first column pinned; a pivot collapses to one value column with a measure switcher; cards (one per row, the first column as its title) only where a Sutra asks for them |
| O3 | Where libraries 1 to 5 may be used | inside the product owner's applications now; any customer application with the licensing work |
| O4 | The standalone Excel export | a small writer in drishti-grid (values, outline, formats); no new dependency |
| O5 | The AG adapter's scope in version 1 | pivot, row grouping, the server-side row model and tree data (section 6) |
| O6 | Mock-ups g2, g3, g4 | **both options of each are built as modes of one component, A the default**: the chart card `mode: 'card'` (g2 A) or `'studio'` (g2 B, the kind rail and the spec beside the chart); the map `mode: 'ranking'` (g3 A) or `'table'` (g3 B, the floating legend, the inset and the table below); the gallery `mode: 'playground'` (g4 A) or `'docs'` (g4 B, the Examples, API, Accessibility and Changelog tabs) |
| O7 | Names | "Drishti Elements" is the family; Elements 2.x is part of drishti-renderer |

g1 was decided separately: the grid's three layouts (section 5.1).

## 15. Build checklist

- [ ] The domain-module platform's five deliverables (section 8) in place
- [ ] `static/elements/` layout, integrity records, release train, import map
- [ ] drishti-core: formats with the server's rounding, tokens and densities, API client, events, keymap, dialogs, menus, resize; its gallery pages
- [x] drishti-data: sources (local, REST, Drishti pivot and rows), Arrow, live (8.2)
- [x] drishti-grid: virtual rows and columns, columns and menus, ranges and the status bar, filters, editing (8.2), export, conditional formats, accessibility
- [x] drishti-grid: tree data (a path per row, 8.5); the AG Grid-style adapter's tree data (O5) with the adapter
- [x] drishti-grid pivot: the three layouts `wells`, `panel` and `terminal` (VaR screens default to `terminal`, others to `wells`), aggregation pickers, value filters, collapsible pivot result columns, totals, server-side model with the request of 5.3, non-additive totals from the server
- [x] The AG Grid-style adapter with the table of section 6 as code, and its unknown-option report (section 6, as built); the migration guide
- [x] drishti-chart: the spec, the general kinds, the data-table alternative, export, Explain; the chart card's modes `card` (default) and `studio` (g2 A and B); maps with per-country loading and the modes `ranking` (default) and `table` (g3 A and B); drishti-risk's four kinds (8.3)
- [x] Vega retired (O1): Vega-Lite visuals translated to the spec and drawn by drishti-chart (8.3), unsupported features refused in words, Python visuals may return a spec (`output: drishti-chart`), the vendored Vega deleted; a guard (`tests/test_bi_wave_c.py`) keeps every reference to the library out
- [x] The phone behaviour of the grid and the pivot (O2); the standalone Excel writer (O4)
- [x] drishti-inputs: pickers, forms with four eyes, the filter bar, the code editor (8.2); the formula input and people picker as their screens move
- [x] drishti-inputs' single-choice `Select` (`<drishti-select>`, 8.2); the VaR family's and the exposure screens' native selects replaced (12.2: hierarchy, horizon, confidences, currency, the pre-deal form)
- [ ] The library's own native selects (a form's enum select, the grid's select editor) and FRTB-SA's and SIMM's choice selects on `Select`
- [x] drishti-renderer 2.0.0 on the libraries (8.5; the console's controls, funnels and Zoom still its scripts until their screens move)
- [x] drishti-insight 0.1.0 (8.10): the one Explain, the one search box, the Approvals queue and the workbook export, in the manifest, so
  packing and the gallery include it
- [ ] drishti-risk in the banking plugin, licensed
- [x] The distribution (8.7): per-library packages with `.d.ts`, themes, `drishti.py elements pack` with the banking exclusion, the
  zip with SRI hashes, npm tarballs for an internal registry, the WebJar (`-P elements`); the guide ELEMENTS_IN_YOUR_APP.md
- [ ] The licence check (2.9.11.i) replaces `--include-domain` as the gate on drishti-risk
- [x] The gallery in both modes, `playground` (default) and `docs` (g4 A and B), every component and example run in its browser file, the grid in both themes and three densities, a keyboard/ARIA smoke check on every demo (8.4)
- [ ] axe vendored for the gallery's check; every example in both themes and three densities
- [ ] Documentation per library (section 9): guide, generated API reference with `--check`, tutorial, gallery, the AG Grid migration guide, theming and accessibility guides, changelogs, versioning policy; Help entries
- [x] The guards of section 11, the allow list (8.6: `test_elements_guards.py`; the allow-list holds only the BI, Pivot, VaR and exposure screens still moving)
- [x] The general screens' tables, panel charts, history charts, Calc, the live connection and their numbers on the libraries (8.6)
- [x] The general screens' multi-select, the as-of date and every date field, the controls' pickers, the history range, the people pickers and the comment box's lists on drishti-inputs 0.2.0 (8.2)
- [ ] The forms on `Form` (needs its live mode, custom widgets, per-field hooks and a time type) and the Build and Studio editors (8.2, 8.6)
- [ ] Every screen moved (section 12) and the old code deleted; the allow list empty (the BI and Pivot family: 12.1)
- [ ] `tools/elements/inventory.py` (planned) regenerates section 2's figures; README and CHANGELOG updated; two drills

## 16. Proposed: drishti-graph, drishti-tree and the designer kit (mock-ups g5, g6, g7), for the owner's choice

**Decided (product owner, 2026-11-07):** g5 option A and g6 option B are the defaults, the other options modes of the same components;
both are built (section 8.8). g7: all three options taken, the docked studio the default; built (section 8.9).

Three new components, mock-ups first: each page has three options (A, B, C) in the chrome of g1 to g4 (the toolbar, menus, status
line, the three densities, light and dark). Synthetic data only. Nothing is built until the product owner picks; as with the grid,
the options may become modes of one component.

**g5, drishti-graph** ([g5-graph.html](mockups/elements/g5-graph.html)): a generic connected-graph component on a canvas (WebGL
above about 50,000 nodes, layouts in a Web Worker). Data sets: a counterparty network (groups, legal entities, netting sets, CSAs,
trades), a GLEIF-style ownership chain, an AML transaction network; each with a dense cluster (1,500 trades, 600 vehicles, an
800-account community). Shared by all options: force, hierarchy and radial layouts; zoom, pan, minimap; expand a node's neighbours,
collapse what is below; search; the shortest path between two nodes; group and collapse by an attribute; size and colour by a
measure with a legend; edge width by weight; pin; filter by type; a selection with Explain (EAD by SA-CCR, look-through ownership,
the AML score's rules); a table linked both ways; export PNG, SVG and the data.

| Option | Arrangement and interaction | Best for |
|---|---|---|
| A, canvas with a side inspector | filters, grouping, size, colour and the legend on the left; the inspector, Explain and the neighbours on the right; a table of the selection under the canvas | mouse-first exploration (recommended default) |
| B, canvas over a linked table | the controls float on the canvas, the inspector is a card at the node; below, a table of every drawn node, edge or the path, sortable and filterable, linked both ways | analysts who work from grids (counterparty reviews, AML cases) |
| C, terminal with a graph query line | a command line (`path`, `expand`, `group by`, `top 10 exposure`, `explain`, `filter`, `export`), an output pane, function keys, monospace | the keyboard, traders |

**g6, drishti-tree** ([g6-tree.html](mockups/elements/g6-tree.html)): a generic hierarchy component. Data sets: a VaR hierarchy (bank,
divisions, desks, books) with VaR, ES and limit use, a legal-entity structure, and a lineage DAG (sources, tables, models, reports;
a node with two parents is marked ⧉). Shared: expand and collapse, search that opens the matching branches, breadcrumbs, measures on
the nodes with VaR and ES marked ◆ and never summed (the children's sum shown beside, the gap is the diversification), Explain,
an edit mode where a node is dragged to a new parent and the moves become one proposal for four-eyes review, export PNG, SVG, CSV, JSON.

| Option | Shape | Best for |
|---|---|---|
| A, org chart | top-down boxes with figures and a limit-use bar; the lineage as a layered DAG left to right | structures people present |
| B, tree grid with measures | an indented tree in drishti-grid (`role="treegrid"`), a column per measure, the limit-use bar, keyboard | risk managers' daily view (recommended default) |
| C, sunburst or icicle | an additive measure (positions, notional, RWA, rows) as angle or width, colour by limit use; VaR is never an angle | where it is concentrated, at a glance |

**g7, the designer kit** ([g7-designer-kit.html](mockups/elements/g7-designer-kit.html)): one shell on which every designer is built
(Sutra screens, BI reports, forms, semantic models, tables, Python tables and models, the Build workbench's YAML), with the header
of [EDITORS.md](EDITORS.md) section 1. The page shows the same kit as the BI report designer and, by a switch, as the form designer:
only the palette, the schemas and the preview's widgets change. Shared: a layout canvas (drag, drop, resize, snap to the 12-column
grid, multi-select, align and distribute, undo and redo, keyboard moves); a searchable palette; an inspector generated from the
component's schema (required fields, validation as typed, fields shown only when they apply); the outline as a tree in g6's style,
dragged to re-order; the live YAML (selection and YAML highlight each other, values edited in place with type-ahead from the Rachana
catalogue, lint markers and a problems list); the header's versions, Validate, Preview, History, Impact and Propose for review (four
eyes, with the diff against the approved version); a preview drawn with the real Elements widgets (grid, chart, KPI, form fields).

| Option | Arrangement | Best for |
|---|---|---|
| A, studio with docked panes | palette and outline as tabs on the left, the canvas with the YAML docked below, the inspector and the preview on the right | most builders (recommended default) |
| B, code beside, live preview | outline over palette on the left, the canvas and the YAML side by side, the preview always on; the inspector as a card at the selection | people who read and write the YAML |
| C, terminal, keyboard first | a command line and a Ctrl+K palette, the outline as a property grid, a dense inspector, the YAML with its problems below, function keys | builders who keep to the keyboard |

After the choice: drishti-graph and drishti-tree join drishti-chart's family as generic components (domain variants in the domain
libraries), the GLEIF and counterparty screens and the data map move onto them; the designer kit becomes part of drishti-inputs and
every editor of EDITORS.md is rebuilt on it.
