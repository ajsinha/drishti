---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: economy
title: Economy and public sector
domain: economy
summary: Economies, forecasts, elections and governments; development indicators, central banks, national statistics, the OECD, official press releases, news and quotes, GDELT world events; the World lens.
packs: [world-economy, news-and-markets, world-development, central-banks, national-statistics, oecd, official-news, gdelt-events]
distributions: [drishti.finance, drishti.all]
internal-only: the IMF figures of world-economy (no samples in the package)
---

# The economy module

A content module (no code): the eight public-data packs with their kinds, contracts, Sutras and guides, their pages (one Sutra page per
function under `/m/economy/`), the **World lens** and its Help. Each pack is filled daily by its refresh script (Help -> External data).

| Part | Where in the package |
|---|---|
| The packs | `packs/world-economy`, `packs/news-and-markets`, `packs/world-development`, `packs/central-banks`, `packs/national-statistics`, `packs/oecd`, `packs/official-news`, `packs/gdelt-events` |
| Functions | ECON, MACRO, FCST, LABR, FISC, ECG, REGE, JUR, PARTY, OFFC, ELEC, REGN, SOCI; TOPIC, NEWS, CO, QUOTE; WDI, WDIS; CBANK, CBSER; NSTAT; OECD, OECDS; PRESS, PREL; GDELT; and each pack's code |
| Pages | `console/plugin.yaml` (a Sutra page per function) |
| Lens | `lenses/world.yaml` (Drishti World) |
| Help | `docs/help.yaml`: the packs' guides, the public-sector tutorial |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |

The IMF figures stay internal-only: the world-economy pack's samples are left out of the package. The World landing (Home's World desk)
is still drawn by the console's economy plugin until its two tables move onto the Elements grid (conformance C2). The module carries no
connector.
