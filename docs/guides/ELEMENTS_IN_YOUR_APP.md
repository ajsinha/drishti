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

# Using Drishti Elements in your app

Drishti Elements are the console's JavaScript and CSS as libraries: drishti-core (formats, tokens, the API client, dialogs), -inputs,
-grid, -chart, -data, -renderer and, for the banking module, -risk ([ELEMENTS.md section 2](../architecture/ELEMENTS.md#2-the-eight-libraries)).
This guide is for a team that wants a grid, a chart or a picker in its **own** web application: how to install them, pick the libraries,
theme them, draw with no server, and connect to a Drishti server when it wants server pivots and Explain.

**Never from a public CDN.** Every form below is a file you keep inside your organisation: a release archive, tarballs for your own
npm registry, a WebJar for your own Maven repository, or the Drishti server you already run. Nothing is published anywhere public.

Contents: [1. Install](#1-install) · [2. Pick libraries](#2-pick-libraries) · [3. Theme](#3-theme) ·
[4. A grid and a chart with no server](#4-a-grid-and-a-chart-with-no-server) · [5. Connecting to a Drishti server](#5-connecting-to-a-drishti-server) ·
[6. Licensing](#6-licensing) · [7. Versions and integrity](#7-versions-and-integrity) · [8. More](#8-more) ·
[9. Moving an AG Grid Enterprise pivot app](#9-moving-an-ag-grid-enterprise-pivot-app)

## 1. Install

Every form holds the same bytes; choose the one your build already uses. The tools are `drishti.py elements …`
([CLI_REFERENCE.md section 5.18](CLI_REFERENCE.md#elements-drishti-elements-for-other-applications)); they run offline with Python only.

| Form | Make it | What you get | Size (Elements 1.0.0) |
|---|---|---|---|
| **Two files** for one page | `drishti.py elements pack --libs core,grid,chart` | `drishti-elements.js` and `drishti-elements.css` with exactly those libraries and what they require | core+grid+chart: 1.8 MB JS (555 KB gzip; 732 KB without ECharts), 50 KB CSS |
| **Release archive** | `drishti.py elements dist --only zip` | `drishti-elements-<version>.zip`: every library, the themes, a manifest with SRI hashes, the changelog lines, the licence notice | 3.3 MB (11.9 MB unpacked, maps included) |
| **npm tarballs** | `drishti.py elements dist --only npm` | `@drishti/elements-<name>` as `npm/drishti-elements-<name>-<version>.tgz` | core 90 KB, grid 283 KB, chart 2.5 MB (ECharts and the maps), inputs 217 KB, data 31 KB, renderer 92 KB |
| **WebJar** | `./mvnw -P elements -pl drishti-elements package` | `drishti-elements-<version>.jar` with the archive's files under `META-INF/resources/webjars/drishti-elements/<version>/` | 3.5 MB |
| **A Drishti server** | nothing to build | `/static/elements/…` on the console's origin, `/embed/v2/elements/<library>/…` for registered host applications ([CLIENT_RENDERER.md section 13](../architecture/CLIENT_RENDERER.md#13-the-elements-versioning-and-distribution)) | |

**The archive.** Unzip it into a folder your web server serves, for example `/vendor/drishti/`. Its layout is the console's: the loader
in drishti-core finds the other libraries, the style sheets and the engines from where drishti-core's bundle was loaded, so nothing has
to be configured:

```
drishti-elements-1.0.0/
  drishti-elements.json        every library, its version and files, every file's bytes and SHA-384 integrity hash
  README.md  CHANGELOG.md  LICENSE-NOTICE.txt
  elements/drishti_elements.json                 the release train
  elements/drishti-grid/src/*.js                 ES modules (tree-shakable)
  elements/drishti-grid/drishti_grid.js          the bundle: a classic script, registers Drishti.elements.grid
  elements/drishti-grid/drishti_grid.css         the component's sheet
  elements/drishti-grid/drishti-grid.d.ts        TypeScript declarations, generated from the typed JSDoc
  elements/drishti-grid/README.md  LICENSE-NOTICE.txt
  css/drishti_tokens.css  css/drishti_resize.css  the design tokens (every theme) and drishti-core's sheet
  themes/drishti-theme-<name>.css                one theme, every token on :root
  vendor/echarts/  vendor/codemirror/            the engines with their licences, the map shapes
```

**npm, through your internal registry.** Publish the tarballs once to Artifactory or Nexus (an npm-hosted repository), then install as
usual. Nothing in the tarballs names a registry; your `.npmrc` does:

```bash
# once, by whoever releases Drishti in your organisation (Artifactory: an npm local repository; Nexus: an npm (hosted) repository)
npm config set @drishti:registry https://artifactory.example.internal/artifactory/api/npm/npm-internal/
npm publish npm/drishti-elements-core-1.1.0.tgz     # core first: the others depend on it
npm publish npm/drishti-elements-inputs-0.3.0.tgz   # then inputs: the grid, the chart, the graph and the tree draw their choices with it
npm publish npm/drishti-elements-grid-0.4.0.tgz
npm publish npm/drishti-elements-chart-0.1.0.tgz
# in an application
echo "@drishti:registry=https://artifactory.example.internal/artifactory/api/npm/npm-internal/" >> .npmrc
npm install @drishti/elements-grid @drishti/elements-chart
```

For Nexus the registry address is `https://nexus.example.internal/repository/npm-internal/`; both need your registry's credentials in
`.npmrc` (`//host/path/:_authToken=…`). Without a registry, `npm install ./drishti-elements-core-1.1.0.tgz ./drishti-elements-inputs-0.3.0.tgz ./drishti-elements-grid-0.4.0.tgz`
works from the files. A package's `src/` holds the ES modules (`import` resolves the package itself), its root the bundle (`require`
gives the bundle's API under Node), `drishti-<name>.d.ts` the types, and `package.json` lists the libraries it depends on.

**The WebJar, through your Maven repository.** Deploy the jar to your internal repository (`mvn deploy:deploy-file
-DgroupId=com.ash.drishti -DartifactId=drishti-elements -Dversion=1.0.0 -Dpackaging=jar -Dfile=drishti-elements-1.0.0.jar
-Durl=<your repository> -DrepositoryId=<its id>`), then depend on it:

```xml
<dependency>
  <groupId>com.ash.drishti</groupId>
  <artifactId>drishti-elements</artifactId>
  <version>1.0.0</version>
</dependency>
```

Spring Boot serves it at `/webjars/drishti-elements/1.0.0/…` with no configuration; any servlet container that serves
`META-INF/resources` does the same.

## 2. Pick libraries

| You want | Libraries (`--libs`) | Needs a server |
|---|---|---|
| tables, a pivot, export | `grid` | no |
| charts and maps | `chart` | no |
| date pickers, multi-select, forms, the code editor | `inputs` | no |
| paged, server-side or live rows, Arrow | `data` (with `grid`) | the data's server, any |
| Drishti screens from a ViewModel | `renderer` | a Drishti server |
| the banking module's risk charts | `risk` | a Drishti server with the banking module, and its licence |

Every library brings drishti-core, and the pack tool adds what a library requires, deepest first:

```bash
python3 tools/drishti.py elements pack --libs grid,chart --theme dark --out web/vendor/drishti
# packed: drishti-core 1.1.0, drishti-grid 0.4.0, drishti-chart 0.1.0; theme terminal; engines vendor/echarts/echarts.min.js
```

The first comment of each packed file and `drishti-elements-pack.json` name every library and version inside. `--no-engines` leaves
ECharts out when the page loads its own first (`window.echarts`, or the chart's `echarts` option); `--maps` copies the map shapes
(7.7 MB) beside the files; drishti-inputs' code editor reads CodeMirror from `vendor/codemirror/` beside the packed files, which the
pack writes when inputs is packed.

With ES modules, import only what you use; a bundler drops the rest (each package marks only its entry, its bundle and its sheets as
having side effects):

```js
import { Grid } from '@drishti/elements-grid';           // or './vendor/drishti/elements/drishti-grid/src/drishti_index.js'
import '@drishti/elements-grid/drishti_grid.css';
import '@drishti/elements-core/drishti_tokens.css';
```

## 3. Theme

Every colour, size and density is a CSS custom property of drishti-core's token set (the list is in
[ELEMENTS.md section 4.1](../architecture/ELEMENTS.md#41-drishti-core)), read by every component. Two ways to choose a theme:

- **Every theme, switched at run time**: load `css/drishti_tokens.css` (or pack without `--theme`) and set `data-theme` on `<html>`
  (`terminal`, the default and dark; `light`; `blue`, `green`, `wallstreet`, `crimson`, `crimson-dark`, `terminal-green`,
  `terminal-crimson`, `terminal-blue`). Without the attribute, the default theme is used, and the light one when the reader's system
  prefers light. `Drishti.elements.core.setTheme('light')` (or `setTheme` imported from drishti-core) does the same from code.
- **One theme, fixed**: `themes/drishti-theme-<name>.css` or `pack --theme <name>` (`dark` and `light` name the default dark and
  light themes): every token on `:root`, no attribute needed.

**Your own theme** is an override after the sheet: set the tokens you change, on `:root` for the whole page or on any element for
one part of it. The components follow at once, charts included:

```css
/* after drishti-elements.css */
:root {
  --d-accent: #0a7d5a;          /* your brand colour: buttons, emphasis, the selected cell */
  --d-on-accent: #ffffff;       /* text on it */
  --d-font-sans: "Inter", system-ui, sans-serif;
}
.my-sidebar { --d-surface: #f4f6f8; }   /* one region only */
```

Keep text tokens readable on their grounds (4.5:1, WCAG AA). The density (`comfortable`, `compact`, `terminal`) is the components'
`density` option or `data-density` on an ancestor.

## 4. A grid and a chart with no server

A complete page with the two packed files, under a strict Content-Security-Policy (nothing inline: the components draw through the DOM
and the CSSOM):

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <link rel="stylesheet" href="vendor/drishti/drishti-elements.css">
  <script src="vendor/drishti/drishti-elements.js"></script>
  <script src="app.js" defer></script>
</head>
<body><div id="grid" style="height: 260px"></div><div id="chart"></div></body>
</html>
```

```js
// app.js
const E = window.Drishti.elements;
const rows = [{ desk: 'Rates', pnl: 1250000 }, { desk: 'Credit', pnl: -340000 }, { desk: 'FX', pnl: 560000 }];
new E.grid.Grid(document.getElementById('grid'), {
  columns: [{ field: 'desk', header: 'Desk' }, { field: 'pnl', header: 'P&L', type: 'number', format: 'amount0' }],
  rows, getRowId: (r) => r.desk,
});
new E.chart.Chart(document.getElementById('chart'),
  { kind: 'bar', title: 'P&L by desk', id: 'desk', data: { rows }, x: 'desk', y: { field: 'pnl', format: 'money' } });
```

With ES modules the same is `import { Grid } from '@drishti/elements-grid'` and `import { Chart } from '@drishti/elements-chart'`; as
web components, `<drishti-grid>` and `<drishti-chart>` with the options as a property. This page is the one the test
`tests/test_elements_pack_browser.py` opens. With no server the grid sorts, filters, groups and pivots in the browser, and the
chart's Explain shows the mark's own rows. The options of every component are in the generated API reference and live in the gallery.

## 5. Connecting to a Drishti server

A grid becomes a view onto Drishti's numbers through three hooks; the browser then computes no figure Drishti owns
([ELEMENTS.md section 5.3](../architecture/ELEMENTS.md#53-the-pivot)):

```js
import { ApiClient } from '@drishti/elements-core';
import { Grid } from '@drishti/elements-grid';

const api = new ApiClient({ base: 'https://drishti.example.internal', credentials: 'include' });
let last = null;
const grid = new Grid(el, {
  columns, layout: 'wells',
  clientAggregation: false,                                   // every total is the server's (VaR and ES are not sums)
  serverPivot: (req, signal) => api.post('/api/pivot/query', { view: req.view }, { signal }).then((a) => (last = a)),
  explain: (cell) => {                                        // cell: {measure, coords, members, value, text, view, row, col}
    const r = grid.model.rowAt(cell.row), c = grid.model.shownColumns()[cell.col];
    const key = r.entry.key + '|' + c.colKey;                // the answer's cell
    return api.get(`/api/pivot/provenance/${encodeURIComponent(last.provenance)}?cell=${encodeURIComponent(key)}`)
      .then((p) => `${cell.measure} = ${cell.text}: ${p.cell.members} rows, ${p.source}, version ${p.version}`);
  },
});
```

The Explain hook answers words, an element or an ExplainResult, and the grid shows it in its layout's place; the Pivot screen's own
hook (`static/js/drishti_pivot.js`, `explain`) is the complete example. The view is the pivot engine's ([PIVOT_GUIDE.md](PIVOT_GUIDE.md)); paged and live rows come from drishti-data
(`serverSideSource(url)`, `restSource(url)`, `arrowSource(url)`, `live({url})`). **Where the page runs**: the console's `/api/` answers
its own origin with the person's session, so an application calls it from behind the same origin (a path on the same host or a reverse
proxy). From another origin, register the application as a host application and use the embed API v2 (`/embed/v2/…`: the ViewModel,
About and panels as JSON, with CORS for registered origins and sign-in by pop-up), which drishti-renderer draws
([CLIENT_RENDERER.md section 10](../architecture/CLIENT_RENDERER.md)).

## 6. Licensing

Libraries 1 to 7 ship with the product under its licence (`LICENSE-NOTICE.txt` in every package names the libraries, their versions and
the third-party software inside: ECharts under Apache 2.0, CodeMirror under MIT, the Natural Earth map shapes in the public domain).
**drishti-risk is licensed with the banking module**, as every later domain library is with its module: the pack and dist tools leave it
out and refuse it unless `--include-domain banking` is given (the archive is then `drishti-elements-<version>+banking.zip`), and the
WebJar never holds it. Until the licence check of 2.9.11.i that switch is the gate; give the banking files only to customers licensed for
the module. Libraries 1 to 5 may be used in the product owner's own applications now and in a customer's applications under the
licensing work of 2.9.11.i ([ELEMENTS.md section 14, O3](../architecture/ELEMENTS.md#14-decisions-of-the-product-owner-2026-11-07));
never redistributed outside the licensed organisation.

## 7. Versions and integrity

Each library has its own semantic version and the release train (`elements/drishti_elements.json`, `"elements"`) names the set that
goes together; the archive, the WebJar and its folder carry the train's version, each npm package its library's. `drishti-elements.json`
(the archive and WebJar), `integrity.json` (each npm package) and `drishti-elements-pack.json` (a pack) hold every file's SHA-384
hash for subresource integrity:

```html
<script src="vendor/drishti/drishti-elements.js" integrity="sha384-…" crossorigin="anonymous"></script>
```

The same sources make the same bytes (fixed times, sorted entries), so a hash changes only with the code. What changed in each library
is `CHANGELOG.md` in the archive, the lines of the product's CHANGELOG whose lead names the library.

## 8. More

- The API reference of every library, generated from the typed JSDoc: [ELEMENTS_API.md](ELEMENTS_API.md) (and the `.d.ts` files).
- The gallery, every component live with its code: `/elements` on a Drishti console ([ELEMENTS.md section 8.4](../architecture/ELEMENTS.md#84-as-built-the-gallery)).
- Moving an AG Grid Enterprise pivot application: [section 9](#9-moving-an-ag-grid-enterprise-pivot-app).
- How the distribution is built: [ELEMENTS.md section 8.7](../architecture/ELEMENTS.md#87-as-built-the-distribution).

## 9. Moving an AG Grid Enterprise pivot app

An application written for AG Grid Enterprise's pivoting, row grouping, server-side row model or tree data moves to drishti-grid by
changing the call that makes its grid. drishti-grid's **AG Grid-style adapter** takes the application's grid options in AG Grid's option
shapes, gives it a drishti-grid, and answers the grid API calls and events the application already makes. The grid the person sees is
drishti-grid: its own look, layouts, densities, keyboard, Explain and non-additive totals.

**Drishti never uses AG Grid.** The adapter is a translation layer inside drishti-grid, written from AG Grid's public documentation of
its option and API shapes only: no AG Grid code, package, type files, CSS, class names or look. Your application stops loading AG Grid.
The adapter's API (`createGridFromAgOptions`, `AgGridApi`, the filter translation, the server helpers) is in
[ELEMENTS_API.md](ELEMENTS_API.md#drishti-grid); its design is [ELEMENTS.md section 6](../architecture/ELEMENTS.md#6-the-ag-grid-style-adapter).

### 9.1 Step by step

1. **Load drishti-grid instead of AG Grid.** Remove the AG Grid package, its script and its style sheets. Add Drishti's theme tokens,
   the grid's style sheet and the two bundles (no npm, no CDN: the files are served from Drishti's `static/elements/` or copied with
   your application):

   ```html
   <link rel="stylesheet" href="/static/css/drishti_tokens.css">
   <link rel="stylesheet" href="/static/elements/drishti-inputs/drishti_inputs.css">
   <link rel="stylesheet" href="/static/elements/drishti-grid/drishti_grid.css">
   <script src="/static/elements/drishti-core/drishti_core.js"></script>
   <script src="/static/elements/drishti-inputs/drishti_inputs.js"></script>   <!-- the grid's choices are drishti-inputs' Select -->
   <script src="/static/elements/drishti-grid/drishti_grid.js"></script>
   ```

   or, as ES modules: `import { createGridFromAgOptions } from '/static/elements/drishti-grid/src/drishti_index.js';`

2. **Change the one call that makes the grid.** Keep `gridOptions` as it is:

   ```js
   // before: const api = agGrid.createGrid(document.getElementById('grid'), gridOptions);
   const api = Drishti.elements.grid.createGridFromAgOptions(document.getElementById('grid'), gridOptions);
   ```

   `api` answers the calls your code makes (`api.setGridOption`, `api.applyTransaction`, `api.exportDataAsExcel` ...). drishti-grid's own
   objects are `api.grid` (the drawn grid) and `api.model` (its engine), for anything beyond AG Grid's shapes.

3. **Read the report.** Every option the adapter does not take in full is named once in the browser console with drishti-grid's way,
   and `api.reportUnsupported()` lists them (`{option, state, words}`; state `partial`, `unsupported`, `no-op` or `unknown`). Work
   through the list; nothing is ignored silently.

4. **Give the element a height.** drishti-grid scrolls its rows virtually inside its element (a million rows cost nothing), so the
   element needs a height, as AG Grid's did. `domLayout: 'autoHeight'` sizes it from the row count when the grid is drawn.

5. **Mark non-additive measures.** A measure whose total is not the sum of its rows (VaR, expected shortfall, a ratio, a distinct
   count) takes `aggFunc: 'drishti:server'` (or `'drishti:server:var(99, 1d)'` to name the server's measure). Its totals are never
   added up in the browser: with client-side rows give `drishti: { serverPivot }`, with the server-side row model your datasource
   answers them.

6. **Choose the look** with the `drishti` option, the one key the adapter adds: `drishti: { layout: 'panel' | 'wells' | 'terminal',
   density: 'comfortable' | 'compact' | 'terminal', title, explain, serverPivot, download, conditionalFormats }`. Any drishti-grid option
   may go there (ELEMENTS_API.md, `GridOptions` and `GridViewOptions`). A `sideBar` gives the `panel` layout, the nearest to AG Grid's
   tool panel.

### 9.2 Before and after

**A client-side pivot.** The options do not change; only the call does.

```js
const gridOptions = {
  columnDefs: [
    { field: 'desk', rowGroup: true, enableRowGroup: true },
    { field: 'book', rowGroup: true, rowGroupIndex: 1, enableRowGroup: true },
    { field: 'region', pivot: true, enablePivot: true },
    { field: 'mtm', headerName: 'MTM', aggFunc: 'sum', enableValue: true, valueFormatter: (p) => fmt(p.value) },
    { field: 'var', headerName: 'VaR 99% 1d', aggFunc: 'drishti:server', enableValue: true },
  ],
  rowData: trades,
  getRowId: (p) => p.data.id,
  pivotMode: true,
  groupDefaultExpanded: 1,
  grandTotalRow: 'bottom',
  pivotRowTotals: 'after',
  sideBar: true,
  onCellClicked: (e) => show(e.data, e.column.colId),
  drishti: { serverPivot: (body, signal, rows) => fetch('/api/pivot/query', { method: 'POST', body: JSON.stringify(body), signal }).then((r) => r.json()) },
};
// before: agGrid.createGrid(el, gridOptions)
const api = Drishti.elements.grid.createGridFromAgOptions(el, gridOptions);
```

**The server-side row model.** The datasource is the one your server already answers.

```js
const gridOptions = {
  rowModelType: 'serverSide',
  serverSideDatasource: {
    getRows: (params) => fetch('/trades/rows', { method: 'POST', body: JSON.stringify(params.request) })
      .then((r) => r.json())
      .then((a) => params.success({ rowData: a.rows, rowCount: a.lastRow, pivotResultFields: a.pivotFields }))
      .catch(() => params.fail()),
  },
  cacheBlockSize: 200,
  columnDefs: [{ field: 'desk', rowGroup: true }, { field: 'region', pivot: true }, { field: 'mtm', aggFunc: 'sum' }],
  pivotMode: true,
};
const api = Drishti.elements.grid.createGridFromAgOptions(el, gridOptions);   // was agGrid.createGrid(el, gridOptions)
api.refreshServerSide({ purge: true });                                          // as before
```

**Tree data.**

```js
const api = Drishti.elements.grid.createGridFromAgOptions(el, {
  treeData: true,
  getDataPath: (data) => data.orgPath,
  autoGroupColumnDef: { headerName: 'Organisation' },
  groupDefaultExpanded: -1,
  columnDefs: [{ field: 'headcount', aggFunc: 'sum' }],
  rowData: people,
});
```

### 9.3 What maps

The table is generated from the adapter's own mapping (`AG_MAPPING` in `drishti_grid-ag-map.js`), so it is what the code does. States:
**mapped** (same meaning), **partial** (mapped, with a difference the console names), **unsupported** (named in the console, with
drishti-grid's way), **no-op** (nothing to set: drishti-grid does it always).

<!-- ag-mapping: generated by tools/docs/elements_api.py from drishti_grid-ag-map.js; do not edit -->

| AG Grid Enterprise option (public API shape) | State | drishti-grid |
|---|---|---|
| `columnDefs`: `field`, `colId`, `headerName`, `headerTooltip`, `children` (column groups) | mapped | the column definition: `field`, `id`, `header`, `description`; a group's heading is `group` (nested groups joined with ›) |
| `defaultColDef`, `columnTypes`, colDef `type` (`numericColumn`, your own types), `cellDataType` | mapped | merged under each column; `numericColumn` or a number in the first row makes `type: 'number'` |
| `width`, `minWidth`, `maxWidth`, `resizable`, `hide`, `pinned`, `sortable`, `sort`, `sortIndex` | mapped | `width`, `minWidth`, `maxWidth`, `resizable`, `hidden`, `pinned`, `sortable`, the sort model |
| `flex` | partial | a width shared from the element's width when the grid is drawn (and by `sizeColumnsToFit`); it does not follow later resizes |
| `filter` (`true`, the text, number, date, set and multi filters), `filterModel` shapes | mapped | `filterable` and the shared filter model (text, number, date, set, blank); a filter with two conditions keeps the first and says so |
| `valueGetter`, `valueFormatter`, `comparator` (functions) | mapped | `valueGetter(row)`, `format(value, row)`, `comparator(a, b)`; string expressions are refused with a warning |
| `cellClass`, `cellClassRules`, `cellStyle` (functions or values) | partial | `cellClass` and `cellStyle` of the column: class names of letters and digits, colour, background, font, alignment only |
| `editable` | mapped | `editable` (a function becomes `true`, with a warning); drishti-grid's editors by type |
| `pivotMode: true` | mapped | the grid starts as a pivot (`pivot` wells) |
| colDef `rowGroup`, `rowGroupIndex`, `enableRowGroup` | mapped | `pivot.rows` (order) or grouped rows when not a pivot; the field allowed in Rows |
| colDef `pivot`, `pivotIndex`, `enablePivot` | mapped | `pivot.columns` (order); the field allowed in Columns |
| colDef `aggFunc` (`sum`, `min`, `max`, `count`, `avg`, `first`, `last`, a function, a name of `aggFuncs`), `enableValue`, `defaultAggFunc` | mapped | `pivot.values[].aggFunc`; a function or an `aggFuncs` name is registered (`registerAggFunc`); `'drishti:server'` (or `'drishti:server:var(99, 1d)'`) marks a non-additive measure asked of the server |
| `allowedAggFuncs` | partial | drishti-grid's aggregation picker offers its own functions (`VALUE_FUNCS`) |
| `aggFuncs` (custom functions) | mapped | `registerAggFunc(name, fn)`: the function is given the members' values of each cell and total |
| `groupDisplayType: 'singleColumn'`, `autoGroupColumnDef` (`headerName`, `width`, `minWidth`, `pinned`) | mapped | a group column the adapter adds (`system`), headed by `headerName` |
| `groupDisplayType: 'multipleColumns'` or `'groupRows'` | partial | drawn as one group column |
| `groupDefaultExpanded`, `pivotDefaultExpanded` | mapped | `groupDefaultExpanded`; the pivot's open levels (`expandToLevel`) |
| `grandTotalRow` (`top`, `bottom`; `pinnedTop`, `pinnedBottom` drawn unpinned), `groupIncludeTotalFooter` | mapped | the pivot's `grandTotalRow` (`top`, `bottom`, `none`) |
| `groupTotalRow`, `groupIncludeFooter` | mapped | the pivot's subtotals: `bottom` below the members, else on the group row (`subtotals: 'above'`) |
| `pivotRowTotals`, `pivotColumnGroupTotals` | mapped | `grandColumn`, `columnGroupTotals` (drawn after the members; `before` is drawn after, with a warning) |
| `suppressAggFuncInHeader` | mapped | the value's label is its column's heading alone |
| `sideBar` (columns and filters tool panels), `rowGroupPanelShow`, `pivotPanelShow` | mapped | `layout: 'panel'` (g1 B); the panels without a `sideBar` are the `wells` layout's ribbon |
| `rowModelType: 'serverSide'`, `serverSideDatasource.getRows(params)`, `cacheBlockSize` | mapped | drishti-grid's server-side row model (`ServerRowModel`) over the host's function; `blockSize` |
| `params.request`: `startRow`, `endRow`, `rowGroupCols`, `valueCols`, `pivotCols`, `pivotMode`, `groupKeys`, `filterModel`, `sortModel` | mapped | translated from the block request (columns as `{id, displayName, field, aggFunc}`, the filter model in AG's shape) |
| `params.success({ rowData, rowCount, pivotResultFields })`, `params.fail()`, `successCallback`, `failCallback` | mapped | the block answer; in pivot mode the rows and `pivotResultFields` become the pivot's answer (rows, result columns, cells) |
| `serverSidePivotResultFieldSeparator` | mapped | the separator of a pivot result field's keys (default `_`) |
| `refreshServerSide({ route, purge })`, `purge` | mapped | `ServerRowModel.refresh({keys, purge})`; a server pivot is asked again |
| `rowModelType: 'infinite'` with `datasource.getRows` | partial | the server-side row model without groups |
| `treeData`, `getDataPath` | mapped | `treeData`, `getDataPath` (client-side rows) |
| `treeData` with `rowModelType: 'serverSide'`, `isServerSideGroup`, `getServerSideGroupKey` (server-side tree data) | mapped | tree data on the server-side row model: each node's level asked when it opens, `groupKeys` its path |
| `rowData`, `getRowId`, `rowSelection` (`singleRow`, `multiRow`, `single`, `multiple`, `checkboxes`), `quickFilterText` | mapped | `rows`, `getRowId`, `rowSelection` and `checkboxes`, the quick filter |
| `rowHeight` | partial | the nearest density: up to 24 px terminal, up to 31 px compact, else comfortable |
| `domLayout: 'autoHeight'` | partial | the element's height follows the row count when drawn; give large data a fixed height |
| `animateRows`, `enableCellChangeFlash`, `cellSelection`, `enableRangeSelection`, `statusBar`, `suppressMenuHide` | no-op | nothing to set: drishti-grid flashes live changes, has cell ranges and its status bar always |
| Grid API: `setGridOption`, `updateGridOptions`, `getGridOption`, `applyTransaction(Async)`, `getSelectedRows`, `forEachNode`, `getDisplayedRowAtIndex` | mapped | the same calls on the adapter's handle, forwarded to the grid's API |
| Grid API: `exportDataAsCsv`, `exportDataAsExcel`, `getDataAsCsv`, `setFilterModel`, `getFilterModel`, `applyColumnState`, `getColumnState` | mapped | CSV and the standalone Excel writer, the filter model, the column state |
| Grid API: `sizeColumnsToFit`, `autoSizeAllColumns`, `expandAll`, `collapseAll`, `refreshCells`, `setRowGroupColumns`, `setPivotColumns`, `setValueColumns`, `getPivotResultColumns` | mapped | the grid's widths, autosize, open levels, redraw and wells |
| Events: `onGridReady`, `onFirstDataRendered`, `onCellClicked`, `onSelectionChanged`, `onSortChanged`, `onFilterChanged`, `onColumnPivotChanged` and the rest of AG_EVENTS | mapped | drishti-grid's typed events (GRID_EVENTS), with AG-shaped parameters |
| `processPivotResultColDef`, `processPivotResultColGroupDef` | unsupported | the pivot's columns are drawn by drishti-grid; format a measure with its column's `valueFormatter` |
| `getContextMenuItems`, `getMainMenuItems`, `cellRenderer`, `cellEditor`, `headerComponent`, overlays | unsupported | drishti-grid's own menus, cells and editors (by type) |
| `getRowStyle`, `getRowClass`, `rowClassRules`, `headerHeight`, `localeText`, `tooltipField`, `suppressRowClickSelection` | unsupported | the column's `cellStyle` and `cellClass`, the density, drishti-grid's words, `headerTooltip`, `rowSelection.enableClickSelection` |
| `pagination`, `paginationPageSize` | unsupported | virtual scrolling: every row is reachable without pages |
| `enableCharts` (integrated charts), `masterDetail`, `rowDragManaged`, `theme`, `rowModelType: 'viewport'` | unsupported | drishti-chart on a range; Drishti's theme tokens; the rest is not covered in version 1 |

Not covered in version 1: integrated charts (use drishti-chart on a range), master-detail rows, sparkline cell renderers, row dragging between grids, AG Grid's theming API and CSS classes (Drishti's theme applies), the viewport row model, pagination, custom cell renderers, editors and header components.

<!-- /ag-mapping -->

### 9.4 What differs

The adapter keeps AG Grid's meaning where it can. These are the differences a person or a test will notice:

| Area | AG Grid | drishti-grid through the adapter |
|---|---|---|
| Look | AG Grid's themes | Drishti's theme tokens, three layouts (`wells` by default, `panel` with a `sideBar`, `terminal`) and three densities; light and dark |
| The wells | the row group and pivot panels appear when asked (`rowGroupPanelShow`) | the `wells` layout always shows the four wells above the grid; `drishti: { layout: 'panel' }` puts them in a side panel |
| Non-additive totals | a custom aggregation sums or recomputes in the browser | `aggFunc: 'drishti:server'`: every total is asked of the server and marked ◆; a sum of a range of such cells is refused in the status bar |
| Custom aggregations | `aggFuncs` and `aggFunc` functions get `params.values` | the same, registered for the grid (`registerAggFunc`); every cell and total is made from its own members' values, never from other totals |
| Group order | the data's order unless sorted | the same for grouped rows; a pivot's members are in natural order (T2 before T10) until sorted |
| Pivot totals | no row or column group totals unless asked | the same: `pivotRowTotals` shows the grand total column, `pivotColumnGroupTotals` each open column group's total, both after the members |
| Group footers | `groupTotalRow` adds a footer row | in pivot mode `groupTotalRow: 'bottom'` gives a total row below the members; otherwise the group row carries the figures |
| Headings of values | `sum(MTM)` | `MTM` for a sum, `MTM (average)` for the others; `suppressAggFuncInHeader` keeps the column's heading alone |
| Column flex | follows the grid's width | shares the width once when drawn and on `sizeColumnsToFit()` |
| Row height | any pixel height | the nearest density (22, 28 or 36 px) |
| Filters | a filter component per column | drishti-grid's filter by the column's type (text, number, date, set, blank), one condition a column (or between); the model is translated both ways |
| Row nodes | the full RowNode | `{id, data, rowIndex, group, key, level, expanded, isSelected(), setSelected()}` |
| Events | AG Grid's event objects | the AG names with `type`, `api`, `context` and the fields applications read (`data`, `node`, `value`, `column`, `colDef`, `rowIndex`, `oldValue`, `newValue`, `expanded`) |
| Export | CSV and Excel with styles | CSV, and an Excel workbook with values, number formats and outline levels (`exportDataAsExcel`); inside Drishti the server's export adds formulas and provenance |

### 9.5 The server-side datasource

Your `serverSideDatasource.getRows(params)` is called as before. `params.request` carries `startRow`, `endRow`, `rowGroupCols`,
`valueCols` (each `{id, displayName, field, aggFunc}`, the `aggFunc` as your column definition named it, `'drishti:server'`
included), `pivotCols`, `pivotMode`, `groupKeys`, `filterModel` (AG Grid's shape: `{filterType: 'set', values}`, `{filterType:
'number', type: 'greaterThan', filter}` ...) and `sortModel`; `params.success({rowData, rowCount, pivotResultFields})` and
`params.fail()` answer it (the older `successCallback(rows, lastRow)` and `failCallback()` too). `params.api` is the adapter's handle and
`params.context` your `context`. A group level's rows carry the group column's value and their figures; `childCount` is shown beside
the member.

Rows come in blocks of `cacheBlockSize` (default 100) as they scroll into view; a sort or filter change aborts what is in flight. Open
groups ask their own level (`groupKeys`). `api.refreshServerSide({route, purge})` asks again (one level and those under it, or all).

**Pivot mode.** drishti-grid draws a pivot from one answer per open level, so in pivot mode the adapter asks your datasource for each
level whole (`startRow` 0, `endRow` 100,000): the top level, then each open member's level (`groupKeys`), as drishti-grid's pivot
opens them. Each answer's rows carry the pivot result fields; `pivotResultFields` names them, keys joined by
`serverSidePivotResultFieldSeparator` (default `_`) and the value column's id last (`EMEA_EUR_mtm`). The grand total row
(`grandTotalRow`) is one more request with no `rowGroupCols`; the row totals (`pivotRowTotals`) one more per level with no `pivotCols`.
Every figure, every total included, is your server's: nothing is added up in the browser, so a non-additive measure is right at every
level.

### 9.6 Options not covered

Every option below is named in the console when an application gives it, with drishti-grid's way, and listed by
`api.reportUnsupported()`. The table of [9.3](#93-what-maps) lists them by state; the ways in short:

- **Integrated charts** (`enableCharts`): select a range and use the context menu's Chart the range (drishti-chart).
- **Master-detail** (`masterDetail`): a second grid beside the first, or `drishti: { drillThrough }` for the rows behind a cell.
- **Cell renderers, editors, header components, overlays**: drishti-grid draws its cells (format with `valueFormatter`, colour with
  `cellClass`, `cellStyle` or conditional formats) and edits by type.
- **Pagination**: virtual scrolling reaches every row without pages.
- **Theming** (`theme`, AG Grid's CSS classes): Drishti's theme tokens and densities.
- **The viewport row model**: the server-side row model.
- **`processPivotResultColDef`**: format a measure with its column's `valueFormatter`.
- **Context and column menus of your own** (`getContextMenuItems`, `getMainMenuItems`): drishti-grid's menus.

An option the adapter does not know at all is reported as `unknown`. A string expression in `valueGetter`, `valueFormatter` or
`cellClassRules` is refused (give a function).

### 9.7 Checking a migration

- Open the page with the browser console showing; read every adapter line, then `api.reportUnsupported()`.
- Compare a pivot's figures with the old grid's on the same data: sums, counts, averages, mins, maxes and custom aggregations are
  computed the same way from the same members; a non-additive measure now comes from the server at every total.
- Your event handlers run with the same names; check the fields they read against [9.4](#94-what-differs).
- The gallery (Build → Learn → Elements, `/elements`) shows any grid's options in AG Grid's shapes on its AG Grid adapter tab, and the
  adapter's demo draws a pivot made from them.
