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

# Market risk

> **Skeleton of the documentation section release i6 ships** (design:
> [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md), section 17). It lives under `architecture/` until its engines are built;
> each page then moves to `docs/guides/market-risk/` with its Help entry. Pages carry their headings and key formulas; the full
> prose is written with each engine. **Built** marks what exists in the product today; **i6** marks what the release adds.

Market risk in Drishti is one section of pages, as business intelligence is: this page is the map, and each engine or measure has
a page of its own with the same parts in the same order, so any two can be read side by side.

**Drishti never prices.** Every present value, scenario value, sensitivity and P&L comes from the bank's systems. Drishti loads
them once, checks them, and computes every risk figure on the server when asked, for any scope, as the person looking: sums,
ranks, expected shortfall, rank tests, and simulation of defaults. A figure whose input is missing says so and names it.

## The pages

| Page | What it covers | State |
|---|---|---|
| [Value at risk](VAR.md) | The VaR engine: inputs, how a date loads, VaR, ES, the 10-day figures, stressed VaR and the stress window, parametric VaR, hierarchies, currencies, limits, Explain and *Why it moved*, backtesting, official results, intraday, shards, performance | **Built** (the full text today: [VAR_GUIDE.md](../../guides/VAR_GUIDE.md), [REGULATORY_VAR_REPORTING.md](../../guides/REGULATORY_VAR_REPORTING.md)) |
| [VaR decomposition](VAR_DECOMPOSITION.md) | Component, incremental and marginal VaR, component ES; as data, for any grouping, for every part | **Built** on the VaR pages; **i6**: the kind, groupings, all parts in one pass, the stability band |
| [Liquidity-adjusted VaR](LIQUIDITY_ADJUSTED_VAR.md) | The exit cost and the liquidation-period scaling | **i6** |
| [Concentration risk](CONCENTRATION.md) | Shares of VaR and of exposure, the Herfindahl index, days to liquidate | **i6** |
| [P&L explain](PNL_EXPLAIN.md) | Hypothetical against risk-theoretical P&L, the unexplained P&L, attribution to risk factors, the P&L attribution test, the desk view | **i6** (backtesting itself is built: the VaR page) |
| [Stress testing](STRESS_TESTING.md) | Historical, hypothetical, regulatory and reverse stress | **i6** |
| FRTB-SA | The sensitivities-based method, the default risk charge, the residual add-on | **Built** ([FRTB_SA_GUIDE.md](../../guides/FRTB_SA_GUIDE.md)); **i6** completes it (the kind, official results, desk sets) |
| [FRTB-IMA](FRTB_IMA.md) | Expected shortfall by liquidity horizon, the eligibility test, the stress add-on, desk zones, capital | **i6** |
| [Default risk charge](DRC.md) | The two-factor default simulation | **i6** |
| SIMM | ISDA SIMM initial margin per netting set | **Built** ([SIMM_GUIDE.md](../../guides/SIMM_GUIDE.md)); **i6** completes it (contributions, CRIF, official results) |
| Regulatory reporting | Backtesting, period statistics, report runs and sign-off | **Built** ([REGULATORY_VAR_REPORTING.md](../../guides/REGULATORY_VAR_REPORTING.md)) |

## The parts of every page

1. **What it answers**: the question, for whom, in plain words.
2. **Theory**: the idea and its assumptions, and what the figure does not say.
3. **The mathematics**: every formula, with its symbols, signs and units.
4. **Worked examples**: small numbers a reader checks by hand. Each is a test of the engine and the default case of its playground.
5. **Data contracts**: each input, who sends it, its key and grain, and one JSON line; every field is on the
   [contract pages](../../contracts/packs/market-risk.md).
6. **Loading**: the file, the load signal, what is checked, what a refusal looks like.
7. **Reading results**: the screen, the command, the API, the computed kind, BI, Explain, the playground, the official record.
8. **Settings, refusals and error codes.**
9. **Tutorial**: the page's own, each step on the screen, on the command line and as YAML.

## How the engines fit

```
 the bank's systems                         Drishti                                           readers
 ──────────────────                         ───────                                           ───────
 scenario PVs, P&L vectors ───────────────► var ──────────┬──► VaR, ES, stressed VaR, decomposition, liquidity, concentration
 desk P&L, VaR limits ────────────────────►               │
 hypothetical P&L, realised-move PVs ─────► pnl-explain ◄─┤──► unexplained P&L, attribution, the attribution test
 stress scenarios and their PVs ──────────► stress        │──► the stress matrix, reverse stress
 sensitivities, the factor dictionary ────► sensitivities ┼──► frtb-sa ──► the standardised charge
 market moves ────────────────────────────►               └──► simm ─────► initial margin
 jump-to-default, issuer parameters ──────► drc ─────────────────────────► the default risk charge
 partial-shock PVs, observations, ────────► frtb-ima (needs pnl-explain, frtb-sa, drc) ──► internal models capital
   stress P&L of non-modellable factors
```

Each engine is switched on or off on its own (Admin → Engines) and placed on its own node or beside others; a page, a pivot or
another engine reaches it by name and cannot tell where it runs. An engine that needs another says so in words when the other is
off or away.

## Shared ideas, written once

| Idea | Where |
|---|---|
| Scopes: `BANK`, `DESK:`, `BOOK:`, `POS:`, `TRD:`, `BASKET:`, `WHERE:` and their algebra | [Value at risk, scopes](VAR.md#7-hierarchies-scopes-and-positions) |
| Signs: P&L positive for a gain; VaR, ES and losses reported positive | [contracts/README.md section 7](../../contracts/README.md#7-currencies-units-and-signs) |
| Business dates, restatements, *as known at* | [contracts/README.md section 6](../../contracts/README.md#6-dates-as-of-and-restatements) |
| Delivering a date and the load signal | [contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date) |
| Official end-of-day results and the published tables | [contracts/README.md section 11](../../contracts/README.md#11-what-drishti-answers-computed-kinds-the-api-captures) |
| An estimate is always marked | each page's *Reading results* |

## Where to start

| You want to | Read |
|---|---|
| See the bank's VaR and walk down to a trade | [Value at risk](VAR.md), then its tutorial |
| Know what your systems must send | each page's *Data contracts*; all of them: [the contract pages](../../contracts/packs/market-risk.md) |
| Understand why VaR moved | [Value at risk, Explain](VAR.md#10-explain-and-why-it-moved) |
| Find what drives the VaR | [VaR decomposition](VAR_DECOMPOSITION.md), [Concentration risk](CONCENTRATION.md) |
| Check whether a desk's risk model explains its P&L | [P&L explain](PNL_EXPLAIN.md) |
| Compute capital | FRTB-SA, [FRTB-IMA](FRTB_IMA.md), [Default risk charge](DRC.md) |
| Try a formula with your own numbers | each page's playground |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
