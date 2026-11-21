<!--
  Drishti Elements

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY.

  This file is the proprietary property of Ashutosh Sinha.
  Copying, use, modification or distribution of this file, via any medium,
  is not permitted except with the express prior written permission of the
  copyright holder.

  See the LICENSE file in the root of this repository.
-->
# Vendored front-end libraries

Everything Drishti Elements loads is served from the release tree: no CDN, no external URL. Each library keeps its licence file beside it.

| Library | Version | Licence | Where | In git | Loaded by |
|---|---|---|---|---|---|
| Apache ECharts | 6.1.0 | Apache-2.0 (`LICENSE`, `NOTICE`; `LICENSE-d3` for the d3 code it bundles) | `echarts/` | yes | drishti-chart |
| **Natural Earth** country boundaries, 1:110m scale (the map visual: countries only, no tiles, no CDN) | 5.1 (nvkelso/natural-earth-vector, `ne_110m_admin_0_countries`) | Public domain (naturalearthdata.com/about/terms-of-use) | `echarts/maps/world-countries.json` (176,138 bytes: coordinates rounded to 2 decimals, properties reduced to `name`, `iso2`, `iso3`; 177 features) | yes | drishti-chart's `map` kind and its geo part (`drishti_chart-geohelper.js`: the map helper of drishti-views' map panels, loaded the first time a view has a map); fetched on first use, `/embed/v1/world-countries.json` for the `<drishti-report>` element |
| **Natural Earth** admin-1 (states, provinces, counties), 1:10m scale, simplified (the map's regions: a click on a country opens its regions) | 5.1 (nvkelso/natural-earth-vector, `ne_10m_admin_1_states_provinces`) | Public domain (naturalearthdata.com/about/terms-of-use) | `echarts/maps/regions/<ISO3>.json` (251 files, about 7 MB in all, the largest under 200 KB; made by `tools/maps/build_regions.py`, see `regions/README.md`) and `echarts/maps/world-points.json` (the countries too small for the 1:110m shapes, one point each) | yes | a map that opens a country, and a view's map panel of one country's regions (`mode: region`: the United States' states with Alaska and Hawaii as insets, Canada's provinces and territories, every other country's first-level regions) through the geo part; each file is fetched the first time that country is drawn; `/embed/v1/regions/<ISO3>.json` for the elements |
| **World regions** (the grouping of countries into sub-regions and continents: the map's world-regions level and a host's own server may read the same table) | Natural Earth 5.1 admin-0 map units, fields `REGION_UN` and `SUBREGION`, derived by `tools/maps/build_world_regions.py` into `data/world-regions.yaml` | Public domain (naturalearthdata.com/about/terms-of-use). **Not** the UN Statistics Division's M49 list: its terms of reuse are not plainly public domain, so the grouping is derived from Natural Earth's own fields | `compute/drishti-rachana/src/main/resources/world-regions.yaml` (the one home; the chart's `drishti_world-regions.js` is generated from it) | yes | the map, the server |
| ECharts GL (one local change: `echarts-gl/PATCHED.md`) | 2.1.0 | BSD-3-Clause (`LICENSE`) | `echarts-gl/` | yes | drishti-chart's 3D trace (loaded the first time one is drawn) |
| CodeMirror 5 | see its folder | MIT | `codemirror/` | yes | Studio, Build |
| **Apache Arrow JS 21.1.0** (read big tables as Arrow where a host serves them) | 21.1.0 (npm `apache-arrow`, published 2025-10-07) | Apache-2.0 (`LICENSE`, `NOTICE` beside it; the licence text lists the code bundled into it) | `apache-arrow/21.1.0/Arrow.es2015.min.js` (the package's ES2015 UMD build, `window.Arrow`; its `# sourceMappingURL` comment removed) | yes, 178,919 bytes (47,797 gzip -9) | drishti-data (`ARROW_VENDOR`), loaded only when a page reads Arrow |

**Apache Arrow JS: updating and checking.** `npm i --ignore-scripts apache-arrow@<v>` in an empty folder outside the repository, copy `Arrow.es2015.min.js` without its last line (`//# sourceMappingURL=...`), `LICENSE.txt` as `LICENSE` and `NOTICE.txt` as `NOTICE` into `apache-arrow/<v>/`, and change `ARROW_VENDOR` in `drishti-data`. `grep -E 'https?://'` over the folder must find only the licence URL in the file's header comment (no source map, no worker, no remote fetch).

## Algorithms and published constants used (no third-party code copied)

Elements' own sources were scanned (foreign copyright lines, "adapted from", pasted snippets, minified code): none found. Two places use a
published method, written independently: the **Equal Earth** projection's four polynomial coefficients (Šavrič, Patterson and Jenny, 2018,
*International Journal of Geographical Information Science*; d3-geo-projection, BSD-3, publishes the same constants) in
`elements/drishti-chart/src/drishti_chart-geo.js`, and the standard **CRC-32** (IEEE 802.3) table in the zip writer of
`elements/drishti-grid/src/drishti_export.js`. Constants and algorithms are not code; they are credited here so the notices stay complete.

## Licence policy

Only these licences are vendored: MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, SIL OFL 1.1, MPL-2.0 (unmodified), public domain or CC0, PSF.
Anything else (GPL, AGPL, LGPL, SSPL, CC-BY-NC, CC-BY-ND, the Commons Clause, BUSL, or an unknown licence) is refused by
`tests/test_vendor_licences.py`. Every folder here has a row above, its licence file beside it, and Apache-2.0's NOTICE where upstream
ships one (ECharts, Apache Arrow JS).
