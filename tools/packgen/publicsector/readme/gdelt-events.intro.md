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
refresh: python3 tools/feeds/publicsector/run.py run gdelt-events
sources:
- name: GDELT 1.0 daily event files
  licence: 'GDELT terms of use: free and open for any use, commercial included, with a citation of the GDELT Project and a link to it'
  internal-only: false
  used-for: event-country
---
## What it is

What the world's news reported about each country yesterday, from the GDELT Project's daily event files: the number of events whose first actor is the country, how widely they were reported, their average tone and Goldstein scale, the share of conflict and of cooperation, the events by CAMEO category, thirty days of history and the most-mentioned stories, each a link to the original. The world map is shaded by tone. Free and open with a citation (GDELT terms). Filled daily by the gdelt-events feed into the newslake lake.

## Refreshing the data

The **gdelt-events** feed (`config/feeds/gdelt-events.yaml`: its sources, licences, schedule `30 8 * * *` UTC and selection) pulls what is new, writes one JSONL.gz a kind with its `.ready` file and loads it into the `newslake` lake: `python3 tools/feeds/publicsector/run.py run gdelt-events --inbox` (the server's data inbox loads it) or `--load` (at once); `--dry-run` shows what it would ask. On Windows: `.\tools\windows\feeds-publicsector.ps1 run gdelt-events`. It needs no key. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
