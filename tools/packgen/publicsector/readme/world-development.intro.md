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
refresh: python3 tools/drishti.py feed run world-development
sources:
- name: World Bank World Development Indicators API (v2)
  licence: CC BY 4.0
  internal-only: false
  used-for: development-profile, development-series
---
## What it is

World Bank World Development Indicators by country and year: population, GDP per head and growth, inflation, unemployment, life expectancy, poverty, access to electricity, internet use and central government debt for every economy, from the World Bank's API (CC BY 4.0). A country's profile shows the latest of each indicator and the world map shaded by GDP per head; each series shows its history by year. Filled daily by the world-development feed into the worldlake lake.

The feed is the one fetcher of the World Bank's WDI: besides the ten headline indicators it pulls, as series only, every `kind: wdi` code of `tools/realdata/sources.yaml` back to that file's first year, and the world, its seven regions and four income groups (`options.series-from`, `options.aggregates`). The world-economy feed reads them from this feed's latest output and never calls the WDI API; with no such output it fails, saying to run this feed first. An aggregate's series has no `WDI_PROFILE` (aggregates have no profile page).

## Refreshing the data

The **world-development** feed (`config/feeds/world-development.yaml`: its sources and their licences in `options.sources`, its schedule, daily at 06:30 local time, and its selection in `options`) pulls what is new into `<data>/datafeeds/world-development/<date>/` and loads it into the `worldlake` lake: `python3 tools/drishti.py feed run world-development` (the parallel loader), `--load inbox` (the server's data inbox loads it), `--dry-run` (what it would ask). The wrappers: `tools/feeds/feed.sh run world-development`, `.\tools\windows\feed.ps1 run world-development`; the schedule for cron, systemd or Task Scheduler: `python3 tools/drishti.py feed schedule`. It needs no key. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
