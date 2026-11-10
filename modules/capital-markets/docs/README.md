---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: capital-markets
title: Capital markets
domain: capitalmarkets
summary: US equities (SEC EDGAR filings and facts, indices, sectors), the largest US funds and ETFs with their holdings, and the Markets lens.
packs: [us-equities, funds]
distributions: [drishti.finance, drishti.all]
uses: [market-data]
internal-only: US equity prices (PXH) stay off as shipped
---

# The capital-markets module

A content module (no code): the `us-equities` and `funds` packs with their kinds, contracts, Sutras and guides, their pages (one Sutra
page per function under `/m/capital-markets/`), the **Markets lens** of Drishti Finance and its Help. With the `market-data` module beside
it (Drishti Finance lists both) the Markets lens also shows curves, FX, quotes and surfaces; it never shows a risk number.

| Part | Where in the package |
|---|---|
| The packs | `packs/us-equities`, `packs/funds` |
| Functions | `USEQS`, USEQ, IDX, SIC, EDGAR, FILING, FACT, PXH; `FUNDS`, FUND, HELD, TRACK |
| Pages | `console/plugin.yaml` (a Sutra page per function) |
| Lens | `lenses/markets.yaml` (Drishti Markets; Home's Markets cards) |
| Help | `docs/help.yaml`: the packs' guides, the world-data tutorial |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |

US equity prices (`PXH`, read live) stay internal-only and off as shipped. The module carries no connector.
