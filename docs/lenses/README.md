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
# Lenses: one product document per lens

An **edition** turns a whole instance into a focused product (`drishti.edition`); a **lens** offers the same focus to one person on a
full instance (`drishti.lenses`, *Open Drishti Risk* in Views and Home). How both are set up, switched and customised is
[EDITIONS.md](../admin/EDITIONS.md). The documents here are the **product view** of each lens: who uses it, the questions they ask by
the day, month and year, what the lens answers today (each statement cites the guide or file it was read from), the gaps, the
proposals and the data each proposal needs. Their proposals are candidates in the
[implementation plan](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started), not started.

| Lens | For | Status | Document |
|---|---|---|---|
| **Drishti Risk** | risk managers near the desks: VaR and exposure first, limits, the morning and the close | built (2.9.6), the first edition | [RISK_LENS.md](RISK_LENS.md) |
| **Drishti Trading** | traders and desk heads: my books, my P&L, my VaR and limits, whether a new trade fits | built (2.9.7) | [TRADING_LENS.md](TRADING_LENS.md) |
| **Drishti Market** | the market-data team: curves, surfaces and fixings, and their quality | built (2.9.7) | [MARKET_LENS.md](MARKET_LENS.md) |
| **Drishti World** | macro, research and context users: economies, markets, companies and funds from public data | built (2.9.7) | [WORLD_LENS.md](WORLD_LENS.md) |
| **Drishti Oversight** | market risk oversight, the second line (the CRO's function): why VaR moved, breaches and escalation, limits of every kind | proposed, not built | [OVERSIGHT_LENS.md](OVERSIGHT_LENS.md) |
| **MR Cube** | market risk managers, oversight, the regulatory capital team and traders: a pivot over any dimensions with correct non-additive VaR and ES totals, sensitivities and ladders, FRTB SA and IMA, explain, corrections, pre-deal pricing; the shared cube core (its section 4) and every data contract | proposed, not built | [MR_CUBE_LENS.md](MR_CUBE_LENS.md) |
| **CCR Cube** | credit officers, the CVA desk, collateral operations and regulatory capital: counterparty × tenor against limits with headroom, allocated exposure, the GLEIF group roll-up, CVA and SA-CVA, SA-CCR, IMM, SIMM, wrong-way risk, pre-deal on paths; on the MR Cube's core | proposed, not built | [CCR_CUBE_LENS.md](CCR_CUBE_LENS.md) |
| **CRO** | the Chief Risk Officer and their office: is the bank within its risk appetite (every board metric against its limit, floors as headroom), what changed overnight and why, the decisions waiting (breaches, temporary limit increases to approve, those expiring), the top 10, the world, data health and "safe to quote", Ask, time travel to the last board, the board pack; summarises and drills into the cubes, Oversight, Market and World; mock-ups and a build checklist in the document | proposed, not built (layouts decided) | [CRO_LENS.md](CRO_LENS.md) |

The four built lenses are on by default (`drishti.lenses: risk,world,market,trading`); the Oversight lens is a proposal whose own
document says what must exist first (the VaR change explanation and the breach workflow). The two risk cubes are proposals too: two
lenses on one shared cube core, because the data grain and the order of aggregation differ (position × scenario for market risk; trade ×
path × time step, netted and collateralised per netting set first, for counterparty risk) while the pivot, provenance, explain and
scatter-gather machinery are the same; sensitivities and the QLRisk pricing bridge are shared ([MR_CUBE_LENS.md section 4.13](MR_CUBE_LENS.md#413-mr-versus-ccr-two-cubes-on-one-core)). The CRO lens
sits above them all: it owns no engine, only the risk appetite catalogue, the statuses, the brief and the CRO's decisions, and is built
after the cube proofs of concept ([CRO_LENS.md section 11](CRO_LENS.md#11-a-proof-of-concept-after-the-cube-pocs)). Its mock-ups, like the
cubes', are in [mockups/](mockups/cro/B.html) beside the documents. Each document links its siblings and draws the
line between its lens and the others (Risk against Oversight in [RISK_LENS.md section 7.1](RISK_LENS.md), for example).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
