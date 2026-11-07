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
refresh: python3 tools/feeds/publicsector/run.py run oecd
sources:
- name: OECD Data Explorer SDMX API
  licence: 'OECD terms and conditions: data may be extracted, copied and redistributed, also commercially, with the source acknowledged (CC BY 4.0 for OECD
    data since July 2024)'
  internal-only: false
  used-for: oecd-country, oecd-series
---
## What it is

The OECD members' business cycle, jobs, prices and growth from the OECD Data Explorer's SDMX API: the composite leading indicator, the harmonised unemployment rate, consumer price inflation and real GDP growth. A country's page shows the latest of each measure and the members on a map shaded by inflation; each series shows its monthly or quarterly history. Filled daily by the oecd feed into the worldlake lake.

## Refreshing the data

The **oecd** feed (`config/feeds/oecd.yaml`: its sources, licences, schedule `0 12 * * *` UTC and selection) pulls what is new, writes one JSONL.gz a kind with its `.ready` file and loads it into the `worldlake` lake: `python3 tools/feeds/publicsector/run.py run oecd --inbox` (the server's data inbox loads it) or `--load` (at once); `--dry-run` shows what it would ask. On Windows: `.\tools\windows\feeds-publicsector.ps1 run oecd`. It needs no key. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
