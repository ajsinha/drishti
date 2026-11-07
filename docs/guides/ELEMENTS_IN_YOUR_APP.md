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
[6. Licensing](#6-licensing) · [7. Versions and integrity](#7-versions-and-integrity) · [8. More](#8-more)

## 1. Install

Every form holds the same bytes; choose the one your build already uses. The tools are `drishti.py elements …`
([TOOLS.md section 5.18](TOOLS.md#518-elements)); they run offline with Python only.

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
npm publish npm/drishti-elements-grid-0.4.0.tgz
npm publish npm/drishti-elements-chart-0.1.0.tgz
# in an application
echo "@drishti:registry=https://artifactory.example.internal/artifactory/api/npm/npm-internal/" >> .npmrc
npm install @drishti/elements-grid @drishti/elements-chart
```

For Nexus the registry address is `https://nexus.example.internal/repository/npm-internal/`; both need your registry's credentials in
`.npmrc` (`//host/path/:_authToken=…`). Without a registry, `npm install ./drishti-elements-core-1.1.0.tgz ./drishti-elements-grid-0.4.0.tgz`
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
- Moving an AG Grid Enterprise pivot application: [AG_GRID_MIGRATION_GUIDE.md](AG_GRID_MIGRATION_GUIDE.md).
- How the distribution is built: [ELEMENTS.md section 8.7](../architecture/ELEMENTS.md#87-as-built-the-distribution).
