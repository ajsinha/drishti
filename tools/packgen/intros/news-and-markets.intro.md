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
sources:
- name: Hand-written reference data (topics and listed companies)
  licence: Proprietary (part of Drishti)
  internal-only: false
  used-for: news-topic, listed-company
- name: Google News RSS (news connector)
  licence: Headlines belong to their publishers; no redistribution
  internal-only: true
  used-for: news, read live when the news connector is on
- name: Yahoo Finance (yfinance connector)
  licence: Unofficial endpoint; terms restrict use and forbid redistribution
  internal-only: true
  used-for: quote, read live when the yfinance connector is on
- name: Captured snapshot of the same quotes and headlines (data/testdata)
  licence: As above; committed for the offline demo machine by the owner's choice
  internal-only: true
  used-for: the offline demo lake
fields:
  news-topic: {ALIASES: Other names the news connector searches for, ECONOMY: The topic's economy (world-economy pack)}
  listed-company: {NAME: Company name, ALIASES: Other names the news connector searches for, ECONOMY: Country economy (world-economy pack)}
---
## What it is

A sample pack built on two on-demand connectors: fifteen news topics (central banks, countries, companies, themes) and twenty
well-known listed companies, each company with a live quote and price history (Yahoo Finance) and every topic and company with its
headlines (Google News). Internal demonstration only; off until its connectors are enabled. Offline, the demo lake serves a captured
snapshot.

## Refreshing the data

The topics and companies are written by `config/packs/news-and-markets/tools/make_samples.py`; the offline snapshot by
`tools/publicdata/news_and_markets.py`.
