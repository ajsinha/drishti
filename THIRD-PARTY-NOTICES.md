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
# Third-party notices

Drishti is proprietary. It bundles or builds with these third-party components under their own licences:

| Component | Licence |
|---|---|
| Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`) | Apache-2.0 |
| Spring Boot, Spring Framework | Apache-2.0 |
| Jackson | Apache-2.0 |
| Jakarta Mail API and Eclipse Angus Mail (SMTP client, via `spring-boot-starter-mail`) | EPL-2.0 / GPL-2.0 with Classpath Exception / EDL-1.0 |
| GreenMail (tests only: an in-process SMTP server) | Apache-2.0 |
| Caffeine | Apache-2.0 |
| JCTools | Apache-2.0 |
| HdrHistogram | BSD-2-Clause / CC0 |
| Bootstrap, Bootstrap Icons (console, vendored) | MIT |
| Apache ECharts (console, vendored) | Apache-2.0 |
| Natural Earth 110m country boundaries (console, vendored for the map visual; simplified) | Public domain |
| Natural Earth 10m admin-1 states and provinces (console, vendored per country for the maps' regions; simplified, `tools/maps/build_regions.py`) | Public domain |
| FastAPI, Starlette, Jinja2, Uvicorn, httpx, PyYAML (console) | MIT / BSD |
| CodeMirror 5 and its Python mode (console, vendored) | MIT |
| Vega, Vega-Lite, vega-embed, vega-interpreter (console; the `vega-lite` visual and Python's Vega-Lite output; `drishti-console/web/static/vendor/vega/`) | BSD-3-Clause |
| Graphic Walker 0.5.3 and the 190 packages built into it (console, Calc's `drishti_bi.walk`; `drishti-console/web/static/vendor/graphic-walker/`, texts in its `LICENSES.txt`) | Apache-2.0 (Graphic Walker); MIT, ISC, BSD-2-Clause, BSD-3-Clause, Apache-2.0, 0BSD, zlib, Unlicense and SGI-B-2.0 (the packages: react, react-dom, vega, d3-*, lodash-es, mobx, styled-components, tess2 and the rest). `react-leaflet` (Hippocratic-2.1) is **not** included |
| Apache Arrow JS (console, vendored bundle) and `arrow-format` with flatbuffers-java (server, Arrow IPC messages) | Apache-2.0 |
| Pyodide (console, Calc; installed by `tools/fetch-pyodide.sh`, not in the repository) | MPL-2.0 |
| CPython, numpy, pandas, scipy, statsmodels, matplotlib and their dependencies (inside Pyodide) | PSF-2.0, BSD-3-Clause, BSD-3-Clause, BSD-3-Clause, BSD-3-Clause, PSF-based (matplotlib); see each package |
| Delta Kernel, Apache Parquet (parquet-java), snappy-java, AWS SDK for Java 2.x and Apache HttpClient 5 (the Delta connector and its native engine, `drishti-deltalake`) | Apache-2.0 |
| zstd-jni (Parquet pages compressed with ZSTD) | BSD-2-Clause |
| Apache Avro (decodes Schema Registry messages in the Kafka connector, `drishti-plugin-kafka`), with Apache Commons Compress | Apache-2.0 |

## Data bundled in the `economics` and `politics-society` packs

These packs carry published public data (normalised by `tools/realdata`, refreshed with `python3 tools/realdata/realdata.py all`). Every document shows its source,
dataset, retrieval date and licence in its *Data source and licence* panel. Details and the refresh procedure: [docs/guides/REAL_DATA.md](docs/guides/REAL_DATA.md).

| Data | Licence and attribution |
|---|---|
| World Bank, World Development Indicators and Worldwide Governance Indicators (API source 2 and 3) | CC BY 4.0. "The World Bank: World Development Indicators / Worldwide Governance Indicators (CC BY 4.0)." |
| IMF, World Economic Outlook (DataMapper API), April 2026 edition | IMF Copyright and Terms of Use (https://www.imf.org/en/About/copyright-and-terms): reuse with attribution, changes stated; commercial reuse needs the IMF's permission. "Source: International Monetary Fund, World Economic Outlook database." |
| UNDP, Human Development Report 2025, Human Development Index time series | CC BY 3.0 IGO. "UNDP, Human Development Report 2025 data (CC BY 3.0 IGO)." |
| CIA, The World Factbook, through the factbook.json mirror (github.com/factbook/factbook.json) | Public domain (U.S. Government work); the mirror is CC0 1.0. "Central Intelligence Agency, The World Factbook (public domain)." |
