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

# Market risk in i6: the three main new screens (proposed)

Mock-ups for the product owner, for release i6. The design is
[MARKET_RISK_I6_DESIGN.md](../../MARKET_RISK_I6_DESIGN.md) (sections 6, 7, 9 and 18); the question is Q15 there.

**Status: proposed, nothing built.** Static HTML, dark by default (`#light` for the light theme), illustrative figures, everything
inline. The built pages use Drishti Elements only (the grid, chips, the chart library, the Explain drawer); a part a library lacks
(the waterfall, the heat-map cell style) is added to the library, never written for one screen.

| Screen | File | What it shows |
|---|---|---|
| **P&L explain, the desk view** | [pnl-explain-desk.html](pnl-explain-desk.html) | the day's APL, HPL, RTPL and unexplained P&L; the waterfall from the factor attribution to the actual P&L; HPL against RTPL over 250 days; every regulatory desk with its attribution test and backtest; the largest unexplained positions; what was not delivered, said in words |
| **FRTB-IMA, desk zones and capital** | [frtb-ima-capital.html](frtb-ima-capital.html) | the capital and its terms; each desk's approval, backtest, attribution zone and treatment; the capital formula worked with the day's numbers; one desk's expected shortfall by risk class and liquidity-horizon step; non-modellable factors with the eligibility test |
| **Stress results** | [stress-results.html](stress-results.html) | the scenario × desk matrix as a heat map with limit use; a scenario's shocks and largest losses; an estimate marked as one; reverse stress from delivered scenarios and as the most likely move |

**One option per screen.** The owner's rule is two or three mock-ups to choose from. Here each screen has a first option, so the
first reaction can say where a choice is wanted; alternatives are then drawn for those screens only (question Q15). Likely
alternatives: for the desk view, the tests first and the day second; for capital, the formula as the page's spine with the desks
in a drawer; for stress, one scenario at a time with a tree of the hierarchy.

The figures on the capital page are the worked example G7 of the design (capital 441.4), so the page can be checked against the
formula by hand.

Screenshots are not included (no browser was run for this design-only step); they are taken when the owner has chosen.

## What each screen is careful to say

- A figure whose input was not delivered reads *not delivered*, with the load's state; it is never a zero or yesterday's figure.
- A figure estimated from sensitivities carries an *estimate* chip, in the matrix and in exports.
- A term that comes from another engine names the engine and whether its figure is official.
- Desk rows are never added up where the measure is not additive (expected shortfall, standardised charges); the page says so.

## Build checklist (when an option is chosen)

- [ ] The option chosen per screen and recorded in the design's section 21 (Q15).
- [ ] Each page from Drishti Elements only; the waterfall, the scatter with a ringed point and the heat-map cell style added to the libraries where missing.
- [ ] Every number opens the Explain drawer; every desk, position, scenario and factor is a link.
- [ ] The notices of missing, late, incomplete and estimated inputs wired to the engines' answers, not composed in the page.
- [ ] Light and dark themes, the page walk and layout checks, a visual snapshot of each page.
- [ ] The phone display: the desk table and the matrix scroll sideways inside their cards.
- [ ] The chosen mock-ups copied beside each engine's page of the market-risk section, with screenshots.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
