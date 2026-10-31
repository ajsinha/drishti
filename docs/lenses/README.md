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
| **CIO** | the Chief Information Officer, the data office and the security team: is the risk data estate on time (feeds against their service levels, the 7am-ready forecast), trustworthy (data quality per dataset, a BCBS 239 scorecard with its evidence), healthy (nodes, shards, response times), affordable (capacity, cost later), secure and used; read-only, every row opens the admin page behind it; the evidence pack for a supervisor; mock-ups and a build checklist in the document | proposed, not built (layouts decided) | [CIO_LENS.md](CIO_LENS.md) |

The four built lenses are on by default (`drishti.lenses: risk,world,market,trading`); the Oversight lens is a proposal whose own
document says what must exist first (the VaR change explanation and the breach workflow). The two risk cubes are proposals too: two
lenses on one shared cube core, because the data grain and the order of aggregation differ (position × scenario for market risk; trade ×
path × time step, netted and collateralised per netting set first, for counterparty risk) while the pivot, provenance, explain and
scatter-gather machinery are the same; sensitivities and the QLRisk pricing bridge are shared ([MR_CUBE_LENS.md section 4.13](MR_CUBE_LENS.md#413-mr-versus-ccr-two-cubes-on-one-core)). The CRO lens
sits above them all: it owns no engine, only the risk appetite catalogue, the statuses, the brief and the CRO's decisions, and is built
after the cube proofs of concept ([CRO_LENS.md section 11](CRO_LENS.md#11-a-proof-of-concept-after-the-cube-pocs)). Its mock-ups, like the
cubes', are in [mockups/](mockups/cro/B.html) beside the documents. The CIO lens is its sibling for the platform and the risk data
behind the numbers: the CRO's "safe to quote" is the CIO lens's whole story, and BCBS 239 its strongest angle
([CIO_LENS.md section 9](CIO_LENS.md#9-bcbs-239-principle-by-principle); mock-ups in [mockups/cio/](mockups/cio/B.html)). Each document links its siblings and draws the
line between its lens and the others (Risk against Oversight in [RISK_LENS.md section 7.1](RISK_LENS.md), for example).

## Mock-ups

The product owner's mock-ups live in the repository under [`mockups/<lens>/`](mockups/), each `<name>.html` self-contained (system
fonts, everything inline, nothing from a CDN) with a `<name>.png` screenshot beside it (headless Chrome, 1600 px wide, the whole page).
Each lens document ends with **Mock-ups: the screens to build**: the screenshot, a link to the HTML, the variant chosen and a **build
checklist**, one row per panel (its exact contents, the data or API it reads, its interactions, the phase, and whether it is built,
partly built or not built, checked against the code). **That checklist is the source of truth at build time**: a panel is done when its
row says built, and what a mock-up showed but the build left out is listed under the checklist and is a row of the plan. A new mock-up is
copied into `mockups/<lens>/` with its screenshot (web fonts replaced by the system stack) and gets its checklist in the same change; a
lens with no mock-up yet (Oversight) says so, and its next step is mock-ups first, before any build. One mock-up is shared by the four
built lenses: their own subject menus in the bar ([`mockups/menus/`](mockups/menus/A.html), row LX1), with its checklist in each of
their documents (for example [RISK_LENS.md section 11.4](RISK_LENS.md#114-the-lens-menus-lx1-menusahtml)).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
