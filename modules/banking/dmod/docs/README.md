---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: banking
title: Banking and risk
domain: banking
summary: VaR and ES (historical, parametric, regulatory), exposure and CCR, FRTB-SA, ISDA SIMM, sensitivities, the MR cube and the official risk results.
packs: [trading, market-risk, counterparty-risk]
distributions: [drishti.risk, drishti.all]
uses: [market-data]
origin: first-party
---

# The banking module

The first module with code: the risk engines (VaR, exposure, sensitivities, FRTB-SA, SIMM, the MR cube) and their REST API under
`/api/v1/risk/`, the `trading`, `market-risk` and `counterparty-risk` packs, the Risk and Trading lenses, a page per function under
`/m/banking/`, and its Help. It requires the `market-data` module (the engines read its curves and surfaces).

| Part | Where in the package |
|---|---|
| Code | `java/drishti-banking.jar` (the service: engines, API, official results) and `java/drishti-compute-banking.jar` (the maths) |
| The packs | `packs/trading`, `packs/market-risk`, `packs/counterparty-risk` |
| Pages | `console/plugin.yaml` (a page per function) |
| Lenses | `lenses/risk.yaml`, `lenses/trading.yaml` |
| Help | `docs/help.yaml`: the packs' guides and the VaR, exposure, FRTB-SA and SIMM documents (each its guide and tutorial in one) |
| Data it needs | `data/needs.yaml`: every kind, bound by the site to its own connectors |

**First-party.** The manifest says `origin: first-party`; the kernel believes it only when the module's id and the hash of its
`META-INF/DMOD.MF` are in the `first-party.yaml` shipped with the same build. Banking still reaches kernel internals: its manifest carries
the allowance (`kernel-internals:`, the packages of `modules/banking/kernel-internals.txt`, which only shrinks) and the one kernel build it
was built for (`kernel-build:`, stamped by the build). On any other build the kernel refuses it: "built for kernel X, this is Y: install the
matching module". The product's one build ships the kernel and banking together.
