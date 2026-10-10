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
| Apache ECharts, ECharts GL (patched: `echarts-gl/PATCHED.md`) | 6.1.0 / see its folder | Apache-2.0 / BSD-3 | `echarts/`, `echarts-gl/` | yes | chart panels |
| CodeMirror 5 | see its folder | MIT | `codemirror/` | yes | Studio, Build |
| Pyodide (CPython for Calc) | 314.0.7 | MPL-2.0 | `pyodide/` | **no**: `tools/fetch-pyodide.sh` | Calc, on demand |
| **FINOS Perspective** viewer, datagrid, d3fc plugin, engine (Rūpaka phase 0) | 3.8.0 | Apache-2.0 (verified: package.json and LICENSE) | `perspective/3.8.0/` | yes, 3.9 MB | `/bi/poc` only, on demand |
| **DuckDB-Wasm** (`eh` build: API module, worker, wasm) (Rūpaka phase 0) | 1.33.1-dev57.0 | MIT (verified: package.json; the repository's licence text is written by the fetch script) | `duckdb-wasm/1.33.1-dev57.0/` | **no**: 36 MB, `tools/fetch-duckdb-wasm.sh` (pinned SHA-256) | `/bi/poc` only, on first use |
| Apache Arrow JS 17.0.0, one ES module bundled from the npm package with esbuild (DuckDB-Wasm imports it) | 17.0.0 | Apache-2.0 (LICENSE and NOTICE kept) | `apache-arrow/17.0.0/` | yes, 0.2 MB | with DuckDB-Wasm |

## Rūpaka phase 0 files (exact sizes)

| File | Raw | gzip |
|---|---:|---:|
| `perspective/3.8.0/perspective/cdn/perspective.js` (client) | 32,653 | 9,536 |
| `perspective/3.8.0/perspective/cdn/perspective-server.worker.js` | 5,786 | 2,169 |
| `perspective/3.8.0/perspective/wasm/perspective-server.wasm` | 2,277,909 | 2,247,185 |
| `perspective/3.8.0/perspective-viewer/cdn/perspective-viewer.js` | 56,792 | 13,281 |
| `perspective/3.8.0/perspective-viewer/wasm/perspective-viewer.wasm` | 920,705 | 911,980 |
| `perspective/3.8.0/perspective-viewer/css/pro.css` | 100,699 | 22,645 |
| `perspective/3.8.0/perspective-viewer/css/pro-dark.css` | 103,959 | 23,219 |
| `perspective/3.8.0/datagrid/cdn/perspective-viewer-datagrid.js` | 130,067 | 37,469 |
| `perspective/3.8.0/datagrid/css/*.css` (2 files) | 24,048 | 3,904 |
| `perspective/3.8.0/d3fc/cdn/perspective-viewer-d3fc.js` | 284,956 | 97,297 |
| `perspective/3.8.0/d3fc/css/perspective-viewer-d3fc.css` | 9,803 | 2,115 |
| **Perspective total** | **3,947,377** | |
| `duckdb-wasm/1.33.1-dev57.0/duckdb-eh.wasm` | 35,913,747 | 8,057,777 |
| `duckdb-wasm/1.33.1-dev57.0/duckdb-browser-eh.worker.js` | 773,223 | 188,134 |
| `duckdb-wasm/1.33.1-dev57.0/duckdb-browser.mjs` | 32,000 | 8,335 |
| `apache-arrow/17.0.0/apache-arrow.mjs` | 209,281 | 48,468 |
| **DuckDB-Wasm and Arrow total** | **36,928,251** | **8,302,714** |

Perspective and DuckDB-Wasm together are 40.9 MB raw: over the 40 MB the phase-0 brief set for committing, so DuckDB-Wasm's 36 MB
is installed by a script (as Pyodide is) and not committed. The "mvp" build (no WebAssembly exceptions) is 41 MB and the "coi"
(threads, needs SharedArrayBuffer) 35.6 MB; neither is vendored.
