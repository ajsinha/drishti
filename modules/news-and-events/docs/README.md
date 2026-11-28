---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: news-and-events
title: News and events
domain: news-and-events
summary: Official press releases, news and quotes by topic, and GDELT world events.
packs: [news-and-markets, official-news, gdelt-events]
distributions: [drishti.finance, drishti.public-sector, drishti.all]
uses: [market-data]
---

# The news-and-events module

A content module (no code): the three news packs with their kinds, contracts, Sutras and guides, their pages (one Sutra page per
function under `/m/news-and-events/`) and its Help. Each pack is filled daily by its refresh script (Help -> External data). It needs only the kernel; `market-data` is optional (quotes beside the news).

| Part | Where in the package |
|---|---|
| The packs | `packs/news-and-markets`, `packs/official-news`, `packs/gdelt-events` |
| Functions | NWMK, TOPIC, NEWS, CO, QUOTE; PRESS, PREL; GDELT; and each pack's code |
| Pages | `console/plugin.yaml` (a Sutra page per function) |
| Help | `docs/help.yaml`: the packs' guides |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |
