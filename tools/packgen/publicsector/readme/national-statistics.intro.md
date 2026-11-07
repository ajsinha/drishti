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
refresh: python3 tools/feeds/publicsector/run.py run national-statistics
sources:
- name: Statistics Canada Web Data Service (WDS)
  licence: Statistics Canada Open Licence
  internal-only: false
  used-for: statistics-series
---
## What it is

Statistics Canada's headline statistics by vector from its Web Data Service (Statistics Canada Open Licence): the consumer price index, unemployment, monthly GDP, population, housing starts and the overnight money market rate. Each series shows its latest value, the change on the period and on the year, and its history. Filled daily by the national-statistics feed into the worldlake lake; more vectors are a line each in the feed's YAML.

## Refreshing the data

The **national-statistics** feed (`config/feeds/national-statistics.yaml`: its sources, licences, schedule `0 14 * * 1-5` UTC and selection) pulls what is new, writes one JSONL.gz a kind with its `.ready` file and loads it into the `worldlake` lake: `python3 tools/feeds/publicsector/run.py run national-statistics --inbox` (the server's data inbox loads it) or `--load` (at once); `--dry-run` shows what it would ask. On Windows: `.\tools\windows\feeds-publicsector.ps1 run national-statistics`. It needs no key. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
