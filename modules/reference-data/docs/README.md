---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: reference-data
title: Reference data
domain: reference-data
summary: The parties and organisation every domain builds on, and the GLEIF legal entities.
packs: [core, gleif]
distributions: [drishti.risk, drishti.finance, drishti.all]
required-by: [banking, trading-systems]
---

# The reference-data module

A content module (no code): the `core` pack (counterparties, groups, issuers, central counterparties, bank legal entities, books, desks, traders, holiday calendars and clearing accounts) and the `gleif` pack (the Legal Entity Identifier records), with their kinds, contracts, Sutras and guides. The kernel keeps no domain pack: the reference data every domain builds on is this module's, delivered and licensed as one unit; each pack is the switch (`gleif` is off by default).

| Part | Where in the package |
|---|---|
| The packs | `packs/core`, `packs/gleif` |
| Functions | CORE, CPTY, GRP, ISS, CCP, LE, BOOK, DESK, TRDR, CAL, CLR; GLEIF, LEI, LEIG, LEIR, LEIC, LEIM |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |

Drishti Risk, Drishti Finance and the module-less distributions that need parties list this module; Drishti Genomics does not.
