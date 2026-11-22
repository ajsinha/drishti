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

# Drishti Elements in Drishti

Drishti Elements is a separate project by the same owner: eight plain-JavaScript libraries (drishti-core, drishti-inputs, drishti-grid
with its pivot, drishti-chart with its maps, drishti-data, drishti-tree, drishti-graph, drishti-designer), their gallery and their docs,
in its own repository, [github.com/drishti-platform/drishti-elements](https://github.com/drishti-platform/drishti-elements), with its docs site at
[ajsinha.github.io/drishti-elements](https://ajsinha.github.io/drishti-elements/). Drishti uses it as an external, vendored component at a
pinned version. Everything about the libraries themselves (the design, the API, the contracts, theming, using them in an app, moving from
AG Grid) is in its docs, one home for each fact:
[DESIGN.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/DESIGN.md),
[API.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/API.md),
[CONTRACTS.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/CONTRACTS.md),
[THEMING.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/THEMING.md),
[USING.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/USING.md) and
[MOVING_FROM_AG_GRID.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/MOVING_FROM_AG_GRID.md).
This page says how Drishti holds and uses it.

## Contents

1. [The pin](#1-the-pin)
2. [How pages load it](#2-how-pages-load-it)
3. [The host script](#3-the-host-script)
4. [Drishti's own libraries](#4-drishtis-own-libraries)
5. [The two tables the server reads](#5-the-two-tables-the-server-reads)
6. [The render folder and the embed](#6-the-render-folder-and-the-embed)
7. [The gallery](#7-the-gallery)
8. [The guards](#8-the-guards)
9. [Moving the pin](#9-moving-the-pin)
10. [Drishti's screens on the libraries](#10-drishtis-screens-on-the-libraries)

## 1. The pin

The release tree of a tag of Drishti Elements (built by its `python3 tools/elements/cli.py dist` in a fresh clone of the tag, never from a
working folder) is copied, unchanged, to `drishti-console/web/static/vendor/drishti-elements/<version>/`. **The pin has one home: the name of
that single folder.** `drishti-console/core/elements_pin.py` reads it (`VERSION`, the tree's `ROOT` on disk and its `URL` under the
console) and maps a static path as pages name it (`elements/drishti-grid/drishti_grid.css`, `css/drishti_tokens.css`,
`vendor/echarts/echarts.min.js`) to the vendored file (`static_path`, `static_url`). The tree holds the eight libraries (their modules,
bundles, type declarations and sheets), drishti-core's sheets (`css/`), the themes, the engines with their licences (`vendor/`: Apache ECharts
and ECharts GL, CodeMirror, Apache Arrow JS, the Natural Earth map shapes), the gallery, the two shared tables (`data/`), the build tool
(`tools/elements/build.py`), `LICENSE`, `README.md`, `CHANGELOG.md` and `drishti-elements.json` (the version, the libraries and the
SHA-384 of every file). Its licence is the owner's proprietary licence, the same as Drishti's (`static/vendor/VENDOR.md`).

## 2. How pages load it

A template names the copy through one global, `ELEMENTS_DIR` (`/static/vendor/drishti-elements/<version>`, set in `core/app.py` from the
pin): `{{ BASE }}{{ ELEMENTS_DIR }}/elements/drishti-grid/drishti_grid.js`. No template spells the version. `base.html` loads drishti-core's
bundle first, and drishti-core knows the folder it came from: a console script that needs another library's file or an engine asks it
(`Drishti.elements.core.staticUrl('vendor/echarts/echarts.min.js')`); inside an embed (`/embed/v1/`, `/embed/v2/`) the scripts keep the
embed routes beside them, which serve the same vendored files (`routes/embed_routes.py`, `routes/embed_v2_routes.py`). The live hub's
shared worker learns the folder from the page (`drishti_channel.js` adds it to the worker's address).

A page loads each library after the libraries it requires, as `drishti-elements.json` lists them: `base.html` loads drishti-core,
drishti-data, drishti-inputs, then drishti-chart before drishti-grid (the grid's bullet cells are drishti-chart's), each once; a page that
draws charts adds only ECharts (`vendor/echarts/echarts.min.js`), which drishti-chart looks up when it draws. The client-side renderer imports
the libraries' modules from the same folder and loads their bundles and the map shapes from it too.

## 3. The host script

`static/js/drishti_elements-host.js`, loaded by `base.html` straight after drishti-core's bundle, is the one place Drishti's routes are
told to Elements: `configure({ endpoints: { live: '/api/live', changes: '/api/changes', pivotPage: '/pivot', capabilities:
'/api/v1/data/capabilities' } })` (the live-set client, the grid's pivot link and the data client read them; with none they say the
feature is off), and `register` for Drishti's own libraries (section 4), so a page may load them by name with the loader.

## 4. Drishti's own libraries

Three libraries stay in Drishti because they are clients of Drishti's server or belong to a module: **drishti-insight** (About and
Explain, search, approvals, workbook export), **drishti-renderer** (the ViewModel client: `/api/view`, sign-in, the DRS codes) and
**drishti-risk** (the banking module's kinds and presets). They live under `static/elements/drishti-{insight,renderer,risk}/`, listed in
`static/elements/drishti_elements.json` (no version of their own: the pin), and import the eight through the vendored copy's entry files
(`../../../vendor/drishti-elements/<version>/elements/drishti-core/src/drishti_index.js`). Their bundles are written by the vendored build
tool:

```bash
python3 drishti-console/web/static/vendor/drishti-elements/<version>/tools/elements/build.py \
  --root drishti-console/web/static --manifest drishti-console/web/static/elements/drishti_elements.json --header config/license-header.txt
```

Their Node tests are `drishti-console/tests/elements/*.test.mjs` (`test_elements_node.py`); a domain module's library (drishti-genomics)
is tested the same way.

## 5. The two tables the server reads

`world-regions.yaml` (the grouping of countries into sub-regions and continents) and `formats.yaml` (the named formats) have their home
in Drishti Elements (`data/`), because both projects read them. `compute/drishti-rachana/pom.xml` adds the vendored `data/` folder as a
resource folder (the root `pom.xml`'s property `drishti.elements.version` names it; a guard holds it to the pin), so `WorldRegions.java`
and `Formats.java` read the same names from the classpath and git holds no second copy.

## 6. The render folder and the embed

`static/render/<version>/` (the public entry of the client-side renderer, its sheet and its integrity record) is Drishti's and is named
after the pin; `tools/render_sheet.py` writes it. **The redirect policy is unchanged:** when the pin moves, a new folder is written, the
previous folder's paths answer `308` to the current one with `Deprecation: true` for one release, then are removed; `1.2.0` stays frozen
byte for byte (its engines are served from the vendored copy by the embed's vendor route). The element sheets of `<drishti-view>` and
`<drishti-report>` (`tools/elements_sheet.py`) read Elements' sheets through `elements_pin.static_path`.

## 7. The gallery

Build -> Learn -> Elements gallery (`/elements`) opens the gallery of the vendored release tree, offline, with
`?visuals=/api/elements/visuals`: the hosted modules' visual types (a module's own kinds registered in drishti-chart) get their pages too.

## 8. The guards

- `tests/test_elements_vendored.py`: exactly one folder under `vendor/drishti-elements/`, its `drishti-elements.json` and the Maven property
  say the same version, `LICENSE` is there; nobody edits the copy (every file matches the SHA-384 of its manifest, nothing added); Drishti
  keeps no second copy of what moved; the host script registers Drishti's libraries as their manifest says.
- `tests/test_elements_version.py`: Drishti's bundles carry the pin, the render folder is named after it, every module's `elements:` range
  admits it, and no path into the copy names another version.
- `tests/test_vendor_licences.py`: the console's vendored folders and the engines inside the copy are on the licence allow list; Drishti
  Elements itself is the one proprietary row (the same owner and `LICENSE`).
- `tests/test_elements_guards.py`: no console script keeps a second implementation of what the libraries do (charts, grids, number
  formats, live connections). The libraries' own guards are Drishti Elements'.

## 9. Moving the pin

`static/vendor/VENDOR.md`, "Drishti Elements: updating the pin", lists the steps: the release tree of the new tag into
`vendor/drishti-elements/<new>/` (the old folder deleted), `drishti.elements.version`, the pinned paths of Drishti's libraries and pages,
their bundles, the render folder, the element sheets, and the guards.

## 10. Drishti's screens on the libraries

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
8. **drishti-renderer**: drawn with libraries 1 to 5; `behaviours: 'console'` from libraries.
9. **drishti-risk**: the VaR explorer's and exposure explorer's charts, backtesting, the stress window, the scenario matrix, FRTB-SA,
   SIMM and the MR lens; moved into the banking plugin.
10. **The allow list empty**; the guard strict; the inventory regenerated into section 2.

### 10.1 The migration checklist

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

The embed (the first Elements embed) loads drishti-core and drishti-grid with the console's scripts, and its view sheet carries the grid's style, so
an embedded view or report draws the same grid.

### 10.2 The VaR and exposure family (step 9, 2.9.11.e): the migration checklist

Every table of the family is a `<drishti-grid>` made by drishti-risk's preset `riskGrid` (the `terminal` layout and density, the
product owner's default for VaR screens; the grid's own command line, wells, toolbar and function keys off inside a screen that has
its own; `clientAggregation: false`; VaR, ES, limits and exposures `additive: false`, never summed in the browser), every chart a
`<drishti-chart>` made by `riskChart` (drishti-risk's kinds where one fits), the figures in words by `riskMoney`, `riskSigned`,
`riskInt` and `riskPct` (one home; the texts the screens printed before, so no figure changed). What the libraries gained for it:
drishti-grid (a column's `cellClass` and `cellRenderer`, a row's `getRowClass` and `getRowData`, `fitRows`, `chrome: false`,
`pinnedTopRows` with `pinnedCellClicked`, a tree node's own row in its cell context), drishti-chart (the histogram of the server's
bins), drishti-risk (the P&L distribution from the server's bins with its fitted normal; the presets).

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

```bash
DRISHTI_FIGURES_OUT=data/figures/before drishti-console/.venv/bin/python -m pytest -q drishti-console/tests/test_var_family_figures_browser.py
DRISHTI_FIGURES_BASELINE=data/figures/before drishti-console/.venv/bin/python -m pytest -q drishti-console/tests/test_var_family_figures_browser.py
```

**Open.** The exposure scope tree is still hand-built (not tree data yet); the scenario matrix's date headings lose their hover note (in the column
description instead); the screens' scripts move into the banking plugin with the module platform (section 8).

### 10.3 Every designer, editor and authoring tool on the kit (the standing rule: no bespoke UI)

The survey of 2026-11-07 (grep for drags, inspectors, palettes, outlines, code and YAML editors, then each authoring screen read). The
old dataset wizard and model designer were deleted in 2.9.11.d; nothing of them survives (the Datasets page's "New table" opens the
table editor, `/build/tables?new=table`; "the model designer" in two notes means the model editor).

| Screen (files) | On the kit | Still its own (moving next) |
|---|---|---|
| Build workbench (`build/drishti_*.js`) | canvas (flow `LayoutCanvas`), drags (`DragHub`), YAML tab (`YamlPane`, schema assistant), about box (`CodeEditor`), the form and controls tabs' re-ordering (`sortableList`), the inspector (`Inspector`: one live `Form` from the Rachana schema), its expressions (the `Form`'s formula widget, completing from `WB.expr.candidates`), the palette (`Palette`); undo stays the server's operation log | the data-shape tree (`drishti_build-shape-tree.js` → `Outline`) |
| BI report designer (`drishti_bi-design*.js`) | canvas (grid `LayoutCanvas`), undo (`SnapshotHistory`), settle, YAML tab (`CodeEditor` with completion), the Phone and Tablet orders (their drag `sortableList`, their model a `LayoutModel` breakpoint); the Format pane and every settings pane beside it (conditional formatting, interactions, tooltip page, page, report, theme, tile: live `Form`s, 8.2); the expression editor (`drishti_bi-expr-editor.js`: a one-line Rachana `CodeEditor`, its completion hook from the fields, measures, functions and keywords); the Visualizations (`Palette`), the wells' Add a field (`Form`); the Fields pane (drishti-tree's `TreeOutline`: folders, a date's parts, the badges, Move to the model, one branch per added dataset with its remove) | |
| Dataset designer: the table editor, virtual tables (`drishti_editor-table.js`) | the shell (`DesignerShell`), the YAML (`YamlPane`), the name, rows and look-up dialog (`Form`), every formula field (the `Form`'s formula widget) and the statement (`CodeEditor`, through `drishti_editor-formula.js`), the source and look-up rows and the columns checklist (drishti-grid editing) | |
| Model designer: the semantic model editor (`drishti_editor-model.js`) | the shell, the YAML, the Overview's name, title and words and the masks (live `Form`s), the rows of the tables, relationships, measures (the ? card, "adds up?"), hierarchies, row rules, fields and the access matrix (drishti-grid editing; formulas in the grid's `code` cell editor) | |
| Python table and model editor (`drishti_editor-python.js`) | the shell, the YAML | the code beside its contract (→ `CodeEditor` Python), the templates |
| + New steps (`drishti_editor-new.js`) | the shell | the guided steps (→ `Form`) |
| Sutra screen designer: Customize, Grid, Outline, Describe (`drishti_designer*.js`, `templates/designer/`) | the drags (`trackPointer`, `DragHub`), the Grid's formula bar (`CodeEditor` with completion), the Outline mode's tree (`Outline`), the columns checklist (`Form`'s checkboxes) | the panel handles (→ `LayoutCanvas` flow), the Grid's cells (→ `LayoutCanvas` grid), the settings and forms panels (→ `Inspector`) |
| Form designer (the workbench's Form and Controls tabs) | the cards' order by drag (`sortableList`) | their fields (→ `Inspector`) |
| BI Studio shell and theme designer (`drishti_bi-studio.js`, `drishti_bi-theme-designer.js`) | the shell (`DesignerShell`: the header and the designer's actions, the Explorer as its objects, the open items as `docs`, Problems and Query as its `dock`, the three layouts, four eyes through `propose`), the theme's settings and series colours (`Form`), the theme YAML (`YamlPane` in the shell's YAML panel, Visual / YAML / Both) | |
| BI items, workspaces, transforms (`templates/bi/item.html`, `workspace.html`, `transform.html`) | | the YAML and Python text areas (→ `CodeEditor`) |
| The Notebook's cells (`drishti_bi-notebook*.js`) | | its cells' code (→ `CodeEditor` Python) |
| Calculator, Pivot computed fields (`terminal/_calc.html`, `terminal/pivot.html`) | | their code areas (→ `CodeEditor`) |
| Admin › Connectors, Drishti*net (`templates/admin/_connector_dialogs.html`, `net.html`) | | the YAML areas (→ `YamlPane`) |
| Reader layout mode, arranging a screen, workspaces (`drishti_layout.js`, `drishti_screen-grid.js`, `drishti_workspace.js`) | | the pointer moves (→ `LayoutCanvas`, `trackPointer`) |
| Forms, record forms, subscriptions (`drishti_form.js`, `drishti_record-forms.js`, `drishti_subscribe-when.js`), the table editor's fields, BI's Format pane, the Build inspector's structured options (`build/drishti_forms.js`) | `Form` (live, embedded, steps, named fields, hooks, custom widgets, time, date-and-time, money: 8.2, "The forms on Form"); the alert form (`drishti_alert-form.js`: the server's schema from `core/alert_form.py`, embedded in its POST form) | the record forms' cells (→ drishti-grid editing) |
| Packs, lake files, lenses, editions, engines, settings (`drishti_pack-new.js`, `drishti_admin-*.js`) | a lake file's fields (drishti-grid editing); every Admin form (sixth pass below): Users, Roles, Access, Audit, Configuration, Connectors and the connector editor, Artifacts, Packs, a pack's page, Lakes and Lakes › Maintain (its row picks are drishti-grid's row selection), Drishti*net, Capacity, Health, Engines, Edits, Compute, Inbox, Caches, Collaboration, Data loads, Embedding, the uninstall dialogs, the banking module's Scopes (`Form`s from `core/admin_forms.py`, one machinery) | `drishti_pack-new.js`; `test_admin_forms_guard.py` holds `templates/admin/` at no raw `<input>`, `<select>` or `<textarea>` (allow-list empty) |
| Approvals' change views (`drishti_approvals.js`, `build/_diff.html`) | | the diffs (→ drishti-inputs' `CodeDiff`) |

**Second pass (the allow-list from 19 to 3).** On drishti-core's `trackPointer` (now with pointer capture) or the kit's `DragHub`: the
Sutra designer's Customize drags (parts and the kit's cards) and Grid drags (a cell onto another), the Outline's drags, the BI
designer's field and visual-type drags and its wells, a reader's layout mode, screen arranging, a workspace's panes (captured across
its frames) and a slider's thumbs; every HTML5 drag of these is deleted. On drishti-inputs' code areas (`<textarea data-di-code>`,
`upgradeCodeArea`: the text area keeps the value for its form and its page's script): the connector and Drishti*net YAML, the theme
designer's YAML, a BI item's and a workspace's YAML, the transform's Python and YAML, the Pivot's function code; the calculator draws its
`CodeEditor` in place. **Still on the allow-list**: the Sutra designer's Grid formula bar (its own suggestion list → `CodeEditor`
completion) and the Outline mode's tree (→ the kit's `Outline`), the workbench's data-shape tree (→ drishti-tree's `TreeOutline`). The editors' own panes in the table
above (inspectors and forms) are not drags or code areas, so the guard does not see them; they move with the forms builder's `Form`.

**Third pass (the allow-list from 3 to 1).** The Grid's formula bar is a `CodeEditor` (Rachana, `height: 'auto'`; Enter keeps, Shift+Enter
a new line, Esc goes back; its suggestions are the editor's completion hook); the Outline mode draws its tree with the kit's `Outline`
(sections and the "Drop a panel here" rows as fixed nodes, the host's `data-ol-*` attributes on the rows; a move becomes the server's
step: move, un-nest, or nest after asking where it opens). The editors' formula fields are `CodeEditor`s through `<input data-di-code>`.
The workbench's data-shape tree is on drishti-tree's `TreeOutline` (8.8): **the allow-list is empty**. The kit's `Outline` and
drishti-tree's `TreeOutline` are now one component (wip/e-admin-1: `drishti_outline.js`, 286 lines, deleted; `drishti_tree-outline.js`
292 → 526 lines with the nested nodes, the selection, the moves and the drag; the kit re-exports it; never two side by side).

**Fourth pass (the panes, wip/e-panes).** The Build workbench's inspector is the kit's `Inspector`: one live `Form` whose schema is made
from the Rachana schema (sections Required, the kind's options, Placement and heading; lists as repeatable groups; a tabs panel's body
by its kind; the structured options through `build/drishti_forms.js`'s converter); its header (the kind, About, Remove panel, Bind
field) is the host's `head`. Its expression fields are the `Form`'s new **formula** widget (`<input data-di-code="rachana">`, a
one-line `CodeEditor` with the host's completion), the hand-made completion list deleted; the palette is the kit's `Palette` over the
page's `DragHub`. The table editor's own formula widget folded into the `Form`'s. A new rule in `test_designer_kit_guard.py`: no
console script draws a label and an input in a loop over a schema's properties or fields (an inspector or form pane made by hand); its
allow-list (2: a lake's classifier and promote checkboxes, a BI transform's parameters) only shrinks and ends at 0.

**The BI designer's own panes (2.9.11.e).** The panes the guard does not see moved too: every settings pane of the report designer and
the theme designer (and, in the second BI pass, the Filters pane's editor, bookmarks, links, drill-through, the Phone and Tablet rows) is a `Form` through one helper (`drishtiBi.formatPane.form`: fields, values, a handler per top-level field; each
control keeps its `data-*` hook, so tests and tutorials find it), and the ExpressionEditor is a one-line `CodeEditor` (the editor's
completion in place of its own list). Saved reports are unchanged: a field writes only when a person changes it, and what it writes is
what the old control wrote. The second BI pass moved the drill-through target (its fields a row of checkboxes, a field to add), the links between
datasets (repeatable rows), the bookmarks (each name a field; the new one's name and slicer choices a form), the Filters pane's editor (a
submitted `Form` whose fields follow the kind of filter through `when` and a dependent condition list, every control named as before;
Pick from the data a row of checkboxes), the Phone and Tablet rows' hide, wide and height, a report measure's name and formula, the
Copilot's question and a transform step's parameters (`drishti_bi-transform.js` off the form guard's allow-list). The Visualizations are drishti-designer's `Palette` (an entry's own `attrs`, data-* only, so `data-type` stays) and the wells' Add a
field a `Form` select. **The last pass**: the Fields pane is drishti-tree's `TreeOutline` (the one tree component; its field-list
hooks in 8.8: a field a button that adds it and drags through the page's `DragHub`, the badges as marks with their `data-*`
attributes, Move to the model a link mark, a date's parts and Hierarchies closed at the start, an added dataset a branch with its remove
action); its hand-made tree, search and CSS are deleted. The BI Studio shell is the kit's `DesignerShell` (the report and the theme
designer): the header with the designer's actions (each keeps its `data-*` hook: `data-save`, `data-publish`, `data-check`,
`data-undo`, `data-redo`, `data-preview`, `data-view-as`, `data-view-link`, `data-item-link`, `data-save-copy`, the AI's
`data-ai-draft` and `data-ai-suggest`), the status beside the name, the Explorer as the shell's objects, the open items above the
designer and Problems and Query in the dock under it; studio, side by side and terminal, remembered per person in this browser; the
terminal's `check`, `save`, `publish`; a workspace that requires review proposes through the shell's Propose for review (a note
for the reviewer). Deleted: `templates/bi/_studio.html`, the designers' own headers and the Studio's Explorer, tabs and dock CSS.
**Still their own: none** of the BI designer's panes.

**Fifth pass (the rows, wip/e-panes-2).** A document's rows are drishti-grid with cell editing through the editors' one helper
(`E.rowGrid`): the model editor's tables, relationships, measures, hierarchies, row rules, fields and access matrix, the table editor's
source and look-ups and its columns checklist, and Admin › Lakes' fields with their Classifier and Promote checkboxes. drishti-grid gained
the **`code` cell editor** (drishti-inputs' one-line `CodeEditor` in the column's `codeMode`, completing through its `complete` hook; the
completion keeps its own Enter, Tab and Escape) so a formula in a row is the same editor as everywhere else. With the BI pass's transform parameters on `Form`, the hand-made
form allow-list is empty (0) and capped at 0.

The alert form is a `Form` from the server's schema (`core/alert_form.py`), embedded in the page's POST form with every control named as
before (`routes/alert_routes.body_from` unchanged): the source, condition and trigger groups show through `when` (their `data-group` and
`data-when` hooks kept), the model's measures and the filters' fields arrive through `setOptions`, and the filters are an array of objects
the page writes into the hidden `model_query` (`drishti_alert-form.js` 218 → 191 lines, `alert_form.html` 163 → 69, `core/alert_form.py` new, 168: the hand-made groups,
the filter rows and the swapped column select deleted).

**Sixth pass (the Admin forms, part 2, wip/e-admin-2).** Every Admin template but the four of part 1 draws no control of its own. A
page puts `admin_form('<name>', ...)` where its fields go (a Jinja global, `core/admin_forms.py`, new, 271 lines): the builder makes the schema
on the server from the helpers of `core/form_kit.py` (new, 128: `text`, `area`, `select`, `radios`, `check`, `checks`, `date`, `file`,
`slider`, `group`, `host`) and answers the host, `<div data-di-form data-schema="{schema, value, options}">`, which drishti-inputs makes a
`Form` (`formOf`, `upgradeForms`; 8.2). Every field keeps the old control's name, so a page's GET or POST form submits what it always
did (Access, Audit, Users' and Scopes' searches, Configuration's search, each Edits switch with its reason), and its data attributes
(`hook`), so the scripts, tests and tutorials find it; what a page posts as JSON its script reads from the `Form` (`getField`), never
from the controls. **Checkboxes**: a ticked box submits `name=on`, an unticked one nothing, as before; a JSON body says `true` or
`false` for every box (a role's seven powers, a user's *Enabled* and *Must change*, Lakes' *Bind* and *Tell*, the connector's *on*, a
deploy's breaking-change box), never leaves one out. **Security pages**: Users and Roles keep every server check (the routes are
unchanged); the dialogs' `Form`s are per page, nothing per person is shared. The lists the server answers later (a plugin's names, a
pack's kinds, the lake targets, the file's fields) arrive through `setOptions`; a field that follows a choice uses `when` over a value
of the page's state (a user being edited hides the password; a lake that exists fixes its root, domain and kind as read-only). Two forms
are built in the page from a server's description, through the same `Form`: Capacity's inputs (from the planner's list: a fieldset per
group, a list of choices a select, a number read loosely) and the connector editor's settings (from the plugin's description: a group
per section, a secret masked, a boolean or a list of values a select with the default first, the settings without a field as rows;
the server's problems on their fields through `showErrors`). Templates, lines before → after: roles 78 → 66, users 76 → 64, net 335 →
320, lakes 92 → 76, capacity 130 → 125, artifacts 117 → 106, access 52 → 46, `_connector_dialogs` 71 → 65, health 55 → 46,
configuration 89 → 84, packs 167 → 164, pack_detail 192 → 190, connectors 86 → 79, `_uninstall_dialog` 53 → 53, caches, edits,
inbox, engines, audit and scopes one line shorter or the same; scripts: `drishti_admin-connectors.js` 633 → 630 (its hand-made fields,
extra rows and datalist deleted), `drishti_capacity-page.js` 464 → 470 (its hand-made inputs and their CSS deleted),
`drishti_roles.js` 172 → 166, `drishti_admin.js` 235 → 229; the rest within a few lines.

**Sixth pass, part 1, and the fold (wip/e-admin-1 into the train).** Collaboration, Data loads, Embedding and Lakes › Maintain were
converted in parallel on a machinery of their own (a second helper module, `formFromData`, the JSON in `data-di-form`); the fold leaves
one: `core/form_kit.py`'s helpers (`number` and `time` added, `text` and `check` as part 2 had them), one registry (`admin_form` in
`core/admin_forms.py`, the four pages' builders beside part 2's), one host (`data-di-form` + `data-schema`) and one entry (`formOf`,
`upgradeForms`). A page script that needs what JSON cannot carry calls `formOf(host, {onSubmit, check})`: on a Form `upgradeForms`
already drew, those options are added to it, so the order the scripts run in does not matter. The alert form's schema
(`core/alert_form.py`) uses the same helpers and host (`alert_form_host`, found by `formOf`); `core/form_schema.py` and `formFromData`
are deleted. Admin › Collaboration: the thread
search, Only hidden comments, Active holds only, the legal hold (the scope's own fields through `when`, the reason required), the
compliance export, the chain check and a comment's hide reason. Data loads: the history's filter embedded in its GET form, Announce a
load, and the expectations with each expected kind a repeatable row. Embedding: the filter, a new application (the JWK set shown for a
key through `when`, the scopes a row of checkboxes), the secret shown once, rotate's grace period. Lakes › Maintain: Select all and the
domain, the settings, a new schedule (the days a row of checkboxes, the time picker, the cron text); the tables are a drishti-grid with
row selection (a checkbox column, *Select all* in its head), never a checkbox per row of the page's own. The guard,
`test_admin_forms_guard.py`: no Admin template writes a raw `<input>`, `<select>` or `<textarea>` (a hidden input is not a field); its
allow-list is empty and its cap 0. The Build workbench's menus (Add panel, Bind
field, File, Ship, the command palette, a dropped field's kinds) are drishti-inputs' `Listbox` under a search ranked by `searchOptions`
(`build/drishti_menus.js` 132 → 119 lines; the hand-made listbox and its CSS deleted); `Listbox` gained `followActive` (the active option
is the selected one), and `searchOptions` ranks a label that is the word first.

