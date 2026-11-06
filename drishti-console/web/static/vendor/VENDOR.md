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
# Vendored front-end libraries

Everything the console loads is served from this folder: no CDN, no external URL (`tests/test_assets_policy.py`). Each library
keeps its licence file beside it. Sizes are bytes as served (raw) and gzip -9.

| Library | Version | Licence | Where | In git | Loaded by |
|---|---|---|---|---|---|
| Bootstrap, Bootstrap Icons | 5.3.8 / see its folder | MIT | `bootstrap/`, `bootstrap-icons/` | yes | every page |
| Cinzel 700 (wordmark), Playfair Display 500 (tagline) | Google Fonts, Latin subset (Cinzel v26) | SIL OFL 1.1 (`fonts/OFL-*.txt`) | `fonts/` | yes | every page (theme.css) |
| Apache ECharts, ECharts GL (patched: `echarts-gl/PATCHED.md`) | 6.1.0 / see its folder | Apache-2.0 / BSD-3 | `echarts/`, `echarts-gl/` | yes | chart panels |
| **Natural Earth** country boundaries, 1:110m scale (the map visual: countries only, no tiles, no CDN) | 5.1 (nvkelso/natural-earth-vector, `ne_110m_admin_0_countries`) | Public domain (naturalearthdata.com/about/terms-of-use) | `echarts/maps/world-countries.json` (176,138 bytes: coordinates rounded to 2 decimals, properties reduced to `name`, `iso2`, `iso3`; 177 features) | yes | a report or board that holds a `map` visual; fetched on first use, `/embed/v1/world-countries.json` for the `<drishti-report>` element |
| **Natural Earth** admin-1 (states, provinces, counties), 1:10m scale, simplified (the map's regions: a click on a country opens its regions) | 5.1 (nvkelso/natural-earth-vector, `ne_10m_admin_1_states_provinces`) | Public domain (naturalearthdata.com/about/terms-of-use) | `echarts/maps/regions/<ISO3>.json` (251 files, about 7 MB in all, the largest under 200 KB; made by `tools/maps/build_regions.py`, see `regions/README.md`) and `echarts/maps/world-points.json` (the countries too small for the 1:110m shapes, one point each) | yes | a map that opens a country; each file is fetched the first time that country is opened; `/embed/v1/regions/<ISO3>.json` for the elements |
| CodeMirror 5 | see its folder | MIT | `codemirror/` | yes | Studio, Build |
| Pyodide (CPython for Calc) | 314.0.7 | MPL-2.0 | `pyodide/` | **no**: `tools/fetch-pyodide.sh` | Calc, on demand |
| **Vega 6.4.0, Vega-Lite 6.5.0, vega-embed 7.3.0, vega-interpreter 2.3.2** (wave C: the `vega-lite` visual and a Python visual's Vega-Lite output) | 6.4.0 / 6.5.0 / 7.3.0 / 2.3.2 | BSD-3-Clause (each licence kept: `LICENSE-vega`, `-vega-lite`, `-vega-embed`, `-vega-interpreter`) | `vega/6.4.0/` | yes, 0.85 MB | a report or board page that draws one, the first time (`bi-vega.js`); no CDN |
| **Apache Arrow JS 21.1.0** (2.9.7, "Arrow where it pays": the pages that read big tables as Arrow, docs/guides/DATA_FORMATS.md section 6) | 21.1.0 (npm `apache-arrow`, published 2025-10-07) | Apache-2.0 (`LICENSE`, `NOTICE` beside it; the licence text lists the code bundled into it) | `apache-arrow/21.1.0/Arrow.es2015.min.js` (the package's ES2015 UMD build, `window.Arrow`; its `# sourceMappingURL` comment removed) | yes, 178,919 bytes (47,797 gzip -9) | the VaR explorer (`terminal/var.html`), before `drishti-table.js`; no other page |

**Apache Arrow JS: updating and checking.** `npm i --ignore-scripts apache-arrow@<v>` in an empty folder outside the repository, copy `Arrow.es2015.min.js` without its last line (`//# sourceMappingURL=...`), `LICENSE.txt` as `LICENSE` and `NOTICE.txt` as `NOTICE` into `apache-arrow/<v>/`, and change the version in the `<script>` of each page that loads it. `grep -E 'https?://'` over the folder must find only the licence URL in the file's header comment (no source map, no worker, no remote fetch). The bundle holds one `new Function` (Arrow's builder compiling a null-value test, `createIsValidFunction`), reached only when JavaScript *builds* vectors with `nullValues`; reading a stream (`tableFromIPC`, all Drishti does) never reaches it, so the page's policy (no `unsafe-eval`) holds, which `tests/test_arrow_browser.py` asserts on the VaR explorer.

## Removed in wave B (2.3.0)

FINOS Perspective 3.8.0, DuckDB-Wasm 1.33.1 and the Apache Arrow JS module that DuckDB-Wasm imported are no longer vendored: wave B made the servers compute
every number and the browser draw (docs/architecture/BI_DESIGN.md principle P3), so no BI page loads WebAssembly. A 2.1 report's `perspective-grid`,
`perspective-pivot` and `perspective-chart` visuals are drawn as Drishti's own grid of the server's answer; Analyse's Slice and SQL run on the console and the
server. Pyodide stays for Calc only.

## Wave C files (exact sizes)

| File | Raw | gzip |
|---|---:|---:|
| `vega/6.4.0/vega.min.js` | 521,123 | 179,594 |
| `vega/6.4.0/vega-lite.min.js` (the `# sourceMappingURL` comment removed) | 257,913 | 81,763 |
| `vega/6.4.0/vega-embed.min.js` (likewise) | 60,256 | 20,759 |
| `vega/6.4.0/vega-interpreter.min.js` (the npm package's ES module bundled to a global with esbuild: `window.vegaInterpreter`) | 6,330 | 2,640 |
| **Vega total** | **845,622** | **284,756** |

## Vega-Lite and the content security policy (wave C)

Vega compiles expressions to JavaScript functions, which needs `unsafe-eval`. The page does not give it. vega-embed is called with `ast: true` (it builds the expression's syntax tree) and `expr: vegaInterpreter` (the vendored **vega-interpreter** walks the tree), so a chart with `datum.x < 0` runs without `eval`; the one `new Function` left in `vega.min.js` is the CSV parser of Vega's data loader, which a page never reaches (the loader refuses every URL and the data is `values`). vega-embed is also called with `defaultStyle: false` (it would write a `<style>`), `actions: false`, `tooltip: false` and a loader whose `load`, `http` and `sanitize` all reject. `bi-vega.js` is the only code that calls it. A Chromium test (`tests/test_bi_wave_c_browser.py`) draws a report's Vega-Lite visual and a Python visual's and asserts the page has no `unsafe-eval` and reports no violation.

**Updating:** `npm i vega@<v> vega-lite@<v> vega-embed@<v> vega-interpreter@<v>` in an empty folder, copy the four `build/*.min.js` (vega-interpreter has none: bundle `import { expressionInterpreter } from 'vega-interpreter'; window.vegaInterpreter = expressionInterpreter;` with `esbuild --bundle --minify --format=iife`), keep the licences, strip the `sourceMappingURL` comments, put them under `vega/<vega version>/`, change `BASE` in `static/js/bi-vega.js`, run the Chromium test.

## Perspective's chart plugin and the settings panel (wave C)

The d3fc chart plugin (already vendored) is loaded by a page that holds a `perspective-chart`; it writes two more `<style>` texts than a grid does (`CHART_STYLE_HASHES` in `core/perspective_csp.py`, the same two the phase 0 page lists), which only such a page is given. The settings panel writes no more styles but sets about a dozen `style` **attributes** through `setAttribute`, which the policy refuses (`style-src-attr`); the panel works. The Chromium test of the chart page asserts both: every `<style>` is on the list, and nothing but those attributes is refused.
