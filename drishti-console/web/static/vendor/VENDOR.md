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
keeps its licence file beside it. Drishti Elements' engines (ECharts, ECharts GL, CodeMirror, Apache Arrow JS, the map shapes) come inside its
vendored release tree, listed with their licences in its own `vendor/VENDOR.md`. Sizes are bytes as served (raw) and gzip -9.

| Library | Version | Licence | Where | In git | Loaded by |
|---|---|---|---|---|---|
| Bootstrap, Bootstrap Icons | 5.3.8 / see its folder | MIT | `bootstrap/`, `bootstrap-icons/` | yes | every page |
| **Drishti Elements** (an external component with its own repository and docs: github.com/ajsinha/drishti-elements, ajsinha.github.io/drishti-elements; grid and pivot, charts and maps, inputs, tree, graph, data client, designer kit and the gallery; with its engines: Apache ECharts and ECharts GL, CodeMirror, Apache Arrow JS, the Natural Earth map shapes and the world-regions table) | 3.3.0 (github.com/ajsinha/drishti-elements, tag v3.3.0, commit 3363c9f; the release tree built by `tools/elements/cli.py dist` in a fresh clone of that tag) | Proprietary, the same owner and `LICENSE` as Drishti (`LICENSE` beside it); its engines' licences in its `vendor/VENDOR.md` (Apache-2.0, BSD-3-Clause, MIT, public domain) | `drishti-elements/3.3.0/` | yes | every page |
| Cinzel 700 (wordmark), Playfair Display 500 (tagline) | Google Fonts, Latin subset (Cinzel v26) | SIL OFL 1.1 (`fonts/OFL-*.txt`) | `fonts/` | yes | every page (theme.css) |
| Pyodide (CPython for Calc) | 314.0.7 | MPL-2.0 | `pyodide/` | **no**: `python tools/drishti.py calc install` | Calc, on demand |


**Drishti Elements: updating the pin.** Drishti holds no source of Drishti Elements, only this release tree (docs/architecture/ELEMENTS_IN_DRISHTI.md).
Clone github.com/ajsinha/drishti-elements at the new tag into an empty folder outside this repository, check the tag's commit id, run
`python3 tools/elements/cli.py dist --out dist`, copy `dist/drishti-elements-<new>/` to `drishti-elements/<new>/` and delete the old folder (the
folder's name is the pin: `core/elements_pin.py`), set `drishti.elements.version` in the root `pom.xml` (drishti-rachana reads `data/world-regions.yaml`
and `data/formats.yaml` from the copy), change the pinned paths of Drishti's own libraries and pages (`git grep -l "vendor/drishti-elements/<old>/"`),
rebuild Drishti's three libraries (`python3 drishti-console/web/static/vendor/drishti-elements/<new>/tools/elements/build.py --root drishti-console/web/static
--manifest drishti-console/web/static/elements/drishti_elements.json --header config/license-header.txt`), the render folder (`tools/render_sheet.py`) and
the element sheets (`tools/elements_sheet.py`), and run the guards (`tests/test_elements_vendored.py`, `tests/test_elements_version.py`). Nobody edits
the copy: the guard compares every file with the SHA-384 its own `drishti-elements.json` lists.

## Removed in wave B (2.3.0)

FINOS Perspective 3.8.0, DuckDB-Wasm 1.33.1 and the Apache Arrow JS module that DuckDB-Wasm imported are no longer vendored: wave B made the servers compute
every number and the browser draw (docs/architecture/ARCHITECTURE.md §28 principle P3), so no BI page loads WebAssembly. A 2.1 report's `perspective-grid`,
`perspective-pivot` and `perspective-chart` visuals are drawn as Drishti's own grid of the server's answer; Analyse's Slice and SQL run on the console and the
server. Pyodide stays for Calc only.

## Removed in 2.9.11.g: Vega

Vega and Vega-Lite (with their embedding and expression-interpreter packages) are no longer vendored (the product owner's decision O1: one
drawing engine). A report's `vega-lite` visual and a Python visual's `output: vega-lite` are translated by drishti-chart's `fromVegaLite`
and drawn by drishti-chart; a feature it does not translate is refused in words with what to use instead.

## Perspective's chart plugin and the settings panel (wave C)

The d3fc chart plugin (already vendored) is loaded by a page that holds a `perspective-chart`; it writes two more `<style>` texts than a grid does (`CHART_STYLE_HASHES` in `core/perspective_csp.py`, the same two the phase 0 page lists), which only such a page is given. The settings panel writes no more styles but sets about a dozen `style` **attributes** through `setAttribute`, which the policy refuses (`style-src-attr`); the panel works. The Chromium test of the chart page asserts both: every `<style>` is on the list, and nothing but those attributes is refused.

## Licence policy

Only these licences are vendored (besides Drishti Elements, by the same owner as Drishti, under the same proprietary `LICENSE`): MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, SIL OFL 1.1, MPL-2.0 (unmodified), public domain or CC0, PSF.
Anything else (GPL, AGPL, LGPL, SSPL, CC-BY-NC, CC-BY-ND, the Commons Clause, BUSL, or an unknown licence) is refused by
`tests/test_vendor_licences.py`. Every folder here has a row above, its licence file beside it, and Apache-2.0's NOTICE where upstream
ships one (inside Drishti Elements: ECharts, Apache Arrow JS).
