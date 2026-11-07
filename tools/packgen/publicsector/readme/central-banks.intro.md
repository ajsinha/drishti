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
refresh: python3 tools/feeds/publicsector/run.py run central-banks
sources:
- name: FRED API (Federal Reserve Bank of St. Louis)
  licence: 'FRED API terms of use; each series keeps its owner''s terms. The default series are US government works (Federal Reserve Board, US Treasury,
    BLS): public domain'
  internal-only: false
  used-for: central-bank, rate-series
- name: Bank of Canada Valet API
  licence: 'Bank of Canada terms of use: free to use, copy, distribute and transmit with attribution; inside a paid service, tell buyers the data is free
    on bankofcanada.ca'
  internal-only: false
  used-for: central-bank, rate-series
---
## What it is

Policy rates and the market series around them: the Federal Reserve's target range and effective rate, Treasury yields, the curve's slope, US inflation, unemployment and the Fed's balance sheet (FRED, Federal Reserve Bank of St. Louis), and the Bank of Canada's target rate, CORRA, Government of Canada yields and USD/CAD (Bank of Canada Valet). A bank's page shows its policy rate, its last move and its series; each series shows two years of history. Filled daily by the central-banks feed into the worldlake lake; FRED needs a free key in DRISHTI_FRED_API_KEY.

## Refreshing the data

The **central-banks** feed (`config/feeds/central-banks.yaml`: its sources, licences, schedule `15 22 * * 1-5` UTC and selection) pulls what is new, writes one JSONL.gz a kind with its `.ready` file and loads it into the `worldlake` lake: `python3 tools/feeds/publicsector/run.py run central-banks --inbox` (the server's data inbox loads it) or `--load` (at once); `--dry-run` shows what it would ask. On Windows: `.\tools\windows\feeds-publicsector.ps1 run central-banks`. It needs `DRISHTI_FRED_API_KEY` in the environment (never in a file); without it the feed exits 3. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
