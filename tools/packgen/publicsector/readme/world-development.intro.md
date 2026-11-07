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
---
generator: tools/packgen/publicsector/make.py
refresh: python3 tools/feeds/publicsector/run.py run world-development
sources:
- name: World Bank World Development Indicators API (v2)
  licence: CC BY 4.0
  internal-only: false
  used-for: development-profile, development-series
---
## What it is

World Bank World Development Indicators by country and year: population, GDP per head and growth, inflation, unemployment, life expectancy, poverty, access to electricity, internet use and central government debt for every economy, from the World Bank's API (CC BY 4.0). A country's profile shows the latest of each indicator and the world map shaded by GDP per head; each series shows its history by year. Filled daily by the world-development feed into the worldlake lake.

## Refreshing the data

The **world-development** feed (`config/feeds/world-development.yaml`: its sources, licences, schedule `30 6 * * *` UTC and selection) pulls what is new, writes one JSONL.gz a kind with its `.ready` file and loads it into the `worldlake` lake: `python3 tools/feeds/publicsector/run.py run world-development --inbox` (the server's data inbox loads it) or `--load` (at once); `--dry-run` shows what it would ask. On Windows: `.\tools\windows\feeds-publicsector.ps1 run world-development`. It needs no key. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
