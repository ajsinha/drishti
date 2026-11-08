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
refresh: python3 tools/drishti.py feed run official-news
sources:
- name: Federal Reserve Board press releases (RSS)
  licence: Public domain (US government work); cite the Board as the source
  internal-only: false
  used-for: release-source, press-release
- name: European Central Bank press releases (RSS)
  licence: 'ECB copyright: free use with the ECB cited as the source; inside a paid product, buyers told the information is free on ecb.europa.eu'
  internal-only: false
  used-for: release-source, press-release
- name: Bank of Canada press releases (RSS)
  licence: 'Bank of Canada terms of use: free to use, copy, distribute and transmit with attribution; inside a paid service, tell buyers it is free on bankofcanada.ca'
  internal-only: false
  used-for: release-source, press-release
- name: Bank of England news (RSS)
  licence: 'Bank of England copyright: download, display or print for personal or internal non-commercial use; other re-use needs its permission'
  internal-only: true
  used-for: release-source, press-release
- name: US Securities and Exchange Commission press releases (RSS)
  licence: Public domain (US government work)
  internal-only: false
  used-for: release-source, press-release
- name: Office of the Superintendent of Financial Institutions news (RSS)
  licence: 'Government of Canada terms (OSFI): non-commercial reproduction with the title, author and source URL; commercial redistribution needs written
    permission'
  internal-only: true
  used-for: release-source, press-release
- name: US Federal Register documents (API)
  licence: Public domain (US government works published by the Office of the Federal Register)
  internal-only: false
  used-for: release-source, press-release
- name: Canada Gazette, Part II (RSS)
  licence: 'Government of Canada terms: non-commercial reproduction permitted; commercial reproduction needs permission'
  internal-only: true
  used-for: release-source, press-release
- name: Canada Gazette, Part I (RSS)
  licence: 'Government of Canada terms: non-commercial reproduction permitted; commercial reproduction needs permission'
  internal-only: true
  used-for: release-source, press-release
---
## What it is

Press releases and notices from the publishers' own feeds: the Federal Reserve, the European Central Bank, the Bank of Canada, the Bank of England, the SEC, OSFI, the US Federal Register and the Canada Gazette. A publisher's page lists its latest releases newest first, each a link to the original, with its terms; a release's page gives its title, date, summary and link. Each publisher's terms are recorded: the Bank of England, OSFI and the Canada Gazette allow no commercial reuse, so their releases are internal only. Filled daily by the official-news feed into the newslake lake.

## Refreshing the data

The **official-news** feed (`config/feeds/official-news.yaml`: its sources and their licences in `options.sources`, its schedule, daily at 19:00 local time, and its selection in `options`) pulls what is new into `<data>/datafeeds/official-news/<date>/` and loads it into the `newslake` lake: `python3 tools/drishti.py feed run official-news` (the parallel loader), `--load inbox` (the server's data inbox loads it), `--dry-run` (what it would ask). The wrappers: `tools/feeds/feed.sh run official-news`, `.\tools\windows\feed.ps1 run official-news`; the schedule for cron, systemd or Task Scheduler: `python3 tools/drishti.py feed schedule`. It needs no key. The samples (a few documents for the tests) are written by `python3 tools/packgen/publicsector/make.py` from the feed's recorded fixtures, never from the network.
