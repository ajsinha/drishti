---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: external-data
title: External data
domain: external-data
summary: Economies, forecasts, elections and governments; development indicators, central banks, national statistics, the OECD; US equities and funds; the World and Markets lenses.
packs: [world-economy, world-development, national-statistics, oecd, central-banks, funds, us-equities]
distributions: [drishti.finance, drishti.public-sector, drishti.all]
uses: [reference-data]
internal-only: the IMF figures of world-economy (five sample files, listed under `restricted:` in content.yaml, are not in the package); US equity prices stay off as shipped
---

# The external-data module

A content module (no code): the seven public-data packs with their kinds, contracts, Sutras and guides, their pages (one Sutra page per
function under `/m/external-data/`), the **World** and **Markets** lenses and their Help. Each pack is filled daily by its refresh script (Help -> External data). A pack is the switch: the module is the delivery and licensing unit.

| Part | Where in the package |
|---|---|
| The packs | `packs/world-economy`, `packs/world-development`, `packs/national-statistics`, `packs/oecd`, `packs/central-banks`, `packs/funds`, `packs/us-equities` |
| Functions | ECON, MACRO, FCST, LABR, FISC, ECG, REGE, JUR, PARTY, OFFC, ELEC, REGN, SOCI; WDI, WDIS; CBANK, CBSER; NSTAT; OECD, OECDS; USEQ, IDX, SIC, EDGAR, FILING, FACT, PXH; FUND, HELD, TRACK; and each pack's code |
| Pages | `console/plugin.yaml` (a Sutra page per function) |
| Lens | `lenses/world.yaml` (Drishti World), `lenses/markets.yaml` (Drishti Markets) |
| Help | `docs/help.yaml`: the packs' guides, the public-sector tutorial |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |

The IMF figures stay internal-only: the five IMF-derived sample files of world-economy (`catalog`, `economic-forecast`, `economy`, `fiscal-position`, `macro-indicator`) are named under `restricted:` in `content.yaml`, with their licence reason, and are left out of the package; the other samples (jurisdictions, regions, elections, parties, office holders, labour markets, social indicators) travel, so a read such as JUR-AUS answers on an installed server. US equity prices (`PXH`, read live) stay internal-only and off as shipped. The module needs only the kernel; `reference-data` (the LEI link of us-equities) is optional. The module carries its packs' connector templates (read-only; hosts and secrets are the site's placeholders); the site overrides them in `config/connectors/`.
