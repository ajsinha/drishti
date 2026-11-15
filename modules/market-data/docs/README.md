---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: market-data
title: Market data
domain: market-data
summary: The parties and organisation (counterparties, books, desks, traders, calendars), then curves, volatility surfaces, FX, equities, credit, inflation, commodities, fixings and bonds, shared by Drishti Risk and Drishti Finance.
packs: [core, market-data]
distributions: [drishti.risk, drishti.finance, drishti.all]
required-by: [banking]
---

# The market-data module

A content module (no code): the `core` pack (counterparties, groups, issuers, CCPs, legal entities, books, desks, traders, calendars, clearing accounts) and the `market-data` pack with their kinds, contracts, Sutras and guides, its pages (one Sutra page per
function under `/m/market-data/`), the Drishti Market lens and its Help. Drishti Risk and Drishti Finance both list it; the `banking`
module requires it, because the VaR, sensitivity and exposure engines read its curves and surfaces.

| Part | Where in the package |
|---|---|
| The packs: kinds, contracts, Sutras, samples, Python items | `packs/core`, `packs/market-data` |
| Functions | `MKT` and CRV, REPO, FX, FXF, FXV, IRV, CPV, EQ, EQX, DIV, EQV, CDS, INF, INFC, CMD, CMDC, CMDV, FIX, BND, CORR, CORRC, EQB; `CORE` and the party words CPTY, GRP, ISS, CCP, LE, BOOK, DESK, TRDR, CAL, CLR |
| Pages | `console/plugin.yaml` (a Sutra page per function) |
| Lens | `lenses/market.yaml` (Drishti Market) |
| Help | `docs/help.yaml`: the pack's guide and catalogue, the analytics tutorial |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |

The module carries its pack's connector templates (read-only; hosts and secrets are the site's placeholders): an installed module serves its kinds at every start; the site overrides a template in `config/connectors/` and binds kinds in `config/bindings.yaml` (Admin -> Data bindings).
