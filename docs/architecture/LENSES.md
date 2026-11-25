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
# Lenses: the product view of every lens

Status: analysis and product description

This is the internal product document of Drishti's lenses, for the people who plan and build them. A **lens** offers one person a focused
Drishti on a full instance (`drishti.lenses`, *Open Drishti Risk* in Views and Home); run as `drishti.edition`, the same lens turns a whole
instance into a focused product. How an administrator sets lenses up, switches, customises and white-labels them is
[ADMIN_GUIDE.md section 12 onwards](../guides/ADMIN_GUIDE.md#12-what-a-lens-is) and is not repeated here. This document holds, per lens, what it answers today (linking the guide that describes it), then the design of the lens mechanism itself
([section 10](#10-the-lens-mechanism-the-design)). Who uses each lens and their questions, the gaps and proposals with the data each
needs, the mock-ups with their build checklists and the designs of lenses not built yet are in
[docs/plans/LENSES_PROPOSALS.md](../plans/LENSES_PROPOSALS.md).

## Contents

- [1. Risk lens](#1-risk-lens)
- [2. Trading lens](#2-trading-lens)
- [3. Market lens](#3-market-lens)
- [4. World lens](#4-world-lens)
- [5. Oversight lens](#5-oversight-lens)
- [6. MR cube lens](#6-mr-cube-lens)
- [7. CCR cube lens](#7-ccr-cube-lens)
- [8. CRO lens](#8-cro-lens)
- [9. CIO lens](#9-cio-lens)
- [10. The lens mechanism: the design](#10-the-lens-mechanism-the-design)

| Lens | For | Status | Section |
|---|---|---|---|
| **Drishti Risk** | risk managers near the desks: VaR and exposure first, limits, the morning and the close | built, the first lens | [1](#1-risk-lens) |
| **Drishti Trading** | traders and desk heads: my books, my P&L, my VaR and limits, whether a new trade fits | built | [2](#2-trading-lens) |
| **Drishti Market** | the market-data team: curves, surfaces and fixings, and their quality | built | [3](#3-market-lens) |
| **Drishti World** | macro, research and context users: economies, markets, companies and funds from public data | built | [4](#4-world-lens) |
| **Drishti Oversight** | market risk oversight, the second line: why VaR moved, breaches and escalation, limits of every kind | proposed; its foundations partly built | [5](#5-oversight-lens) |
| **MR cube** | market risk, oversight, regulatory capital and traders: a pivot over any dimensions with correct non-additive VaR and ES totals | built as a Pivot preset (`MCUBE`); further proposals open | [6](#6-mr-cube-lens) |
| **CCR cube** | credit officers, the CVA desk, collateral operations, regulatory capital: counterparty × tenor against limits, allocated exposure, CVA, SA-CCR, SIMM | proposed, on the MR cube's core | [7](#7-ccr-cube-lens) |
| **CRO** | the Chief Risk Officer and their office: within risk appetite, what changed and why, the decisions waiting, the board pack | proposed (layouts decided) | [8](#8-cro-lens) |
| **CIO** | the Chief Information Officer, the data office and security: is the risk data estate on time, trustworthy (BCBS 239), healthy, affordable, secure | proposed (layouts decided) | [9](#9-cio-lens) |

The lenses of the modules that are on are offered by default (`drishti.lenses: default`: Risk, Trading, Market, World, and Research while genomics is on). The two risk cubes are two lenses on one shared
cube core, because the data grain and the order of aggregation differ (position × scenario for market risk; trade × path × time step,
netted and collateralised per netting set first, for counterparty risk) while the pivot, provenance, explain and scatter-gather machinery
are the same ([section 6.5](../plans/LENSES_PROPOSALS.md#65-the-shared-cube-core-mr-and-ccr)). The CRO lens sits above them all: it owns no engine, only the risk
appetite catalogue, the statuses, the brief and the CRO's decisions ([section 8](#8-cro-lens)). The CIO lens is its sibling for the
platform and the risk data behind the numbers: the CRO's "safe to quote" is the CIO lens's whole story, and BCBS 239 its strongest angle
([section 9](#9-cio-lens)). Shared material is written once and referenced (the lens menus, the oversight designs, the cube core and the cube data contracts are in
[LENSES_PROPOSALS.md](../plans/LENSES_PROPOSALS.md)).

## 1. Risk lens

**Built**, the first lens: "a one-stop shop to manage risk. VaR and Exposure first; liquidity, climate and more join as more lines
here" ([`config/lenses/banking/risk.yaml`](../../config/lenses/banking/risk.yaml)). What it shows and its three landings are in
[ADMIN_GUIDE.md section 16.1](../guides/ADMIN_GUIDE.md#161-drishti-risk); the engines behind it in [VAR_GUIDE.md](../guides/VAR_GUIDE.md) and
[EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md). Risk is the engines' lens (read, compute, what-if, capture); governance (approve,
escalate, sign off) is the Oversight lens ([section 5](#5-oversight-lens)).

### 1.2 What it answers today

| Question or need | Status | Where it is described |
|---|---|---|
| The bank's and each desk's VaR and ES, within limits? | **Built**: the Risk desk's tiles and VaR by desk with limit use; the Risk map's hierarchy and limit-use heat map; official `LIMIT` and `LIMIT_USE` | [ADMIN_GUIDE.md section 16.1](../guides/ADMIN_GUIDE.md#161-drishti-risk); [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| VaR and ES of any scope; component, incremental, marginal VaR; any window and confidence | **Built** | [VAR_GUIDE section 18](../guides/VAR_GUIDE.md#18-the-computation) |
| 10-day VaR (√10 and the 10-day sum), the confidence pickers, limit use only when comparable | **Built** | [VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen) |
| Stressed VaR and its 12-week average; the stress window review (`SWIN`) | **Built** in the engine; not yet on the landings (RL1) | [VAR_GUIDE sections 18.8](../guides/VAR_GUIDE.md#188-stressed-var), [18.9](../guides/VAR_GUIDE.md#189-choosing-the-stress-window) |
| Why did VaR move? | **Built** as the VaR figure's Explain (*Why it moved*, causes that add up exactly); not yet a card on the landings (RL4) | [VAR_GUIDE section 3.11](../guides/VAR_GUIDE.md#311-why-it-moved-the-var-change-explained) |
| Which scenario drives it and who loses on it; the scenario matrix (`SMAT`) | **Built** | [VAR_GUIDE sections 3.4](../guides/VAR_GUIDE.md#34-the-tabs), [3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat) |
| Several hierarchies (MGMT, LEGAL, limit letters `LL`, any configured) | **Built** | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-promoted-columns) |
| Captures, compare, history, backtest on Drishti's derived hypothetical P&L | **Built**; the bank's actual and hypothetical P&L not yet (RL3) | [VAR_GUIDE sections 6](../guides/VAR_GUIDE.md#6-captures-and-compare), [7](../guides/VAR_GUIDE.md#7-history-and-backtesting) |
| A large trade's effect; intraday VaR from files and Kafka; the pre-deal basket | **Built** | [VAR_GUIDE section 8](../guides/VAR_GUIDE.md#8-what-if-and-var-as-a-kind) |
| The VaR of every short EUR position, of a basket | **Built**: scope words, rule portfolios, `WHERE:`, baskets | [VAR_GUIDE section 3.5](../guides/VAR_GUIDE.md#35-construct-your-basket) |
| Every number reproducible; seven years | **Built**: official captures with inputs' versions | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [EXPOSURE_GUIDE section 20](../guides/EXPOSURE_GUIDE.md#20-official-end-of-day-results) |
| Sharded across a cluster, equal to the bit | **Built** | [VAR_GUIDE section 28](../guides/VAR_GUIDE.md#28-sharding-across-drishtinet) |
| EE, ENE, PFE, EPE, EEPE for any scope after netting and collateral; the pre-deal check | **Built** | [EXPOSURE_GUIDE sections 1](../guides/EXPOSURE_GUIDE.md#1-the-question-exposure-answers), [4](../guides/EXPOSURE_GUIDE.md#4-scopes), [7](../guides/EXPOSURE_GUIDE.md#7-pre-deal-what-if) |
| Counterparties near their credit limits | **Built as read**; the demo's stored limits read very low use (RL9) | [EXPOSURE_GUIDE section 28](../guides/EXPOSURE_GUIDE.md#28-open-items) |
| Is today's official run complete? | **Partly**: load checks, rejected rows, late loads; no completeness against trades booked | [VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks) |
| Tell me when a desk crosses its limit intraday | **Partly**: alerts on BI and view numbers after a load (RL12) | [USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers) |
| Entitlements per scope and kind, masks, audit per answer | **Built** | [VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit) |
| Stress results (`SCN`, `STR`, `SSPNL`) | **Hidden** in the lens by decision (RL7) | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived) |
| CVA, SA-CCR, SIMM | **Hidden** in the lens | [ADMIN_GUIDE.md section 16.1](../guides/ADMIN_GUIDE.md#161-drishti-risk) |
| Sensitivities by factor and bucket, sensitivity limits; VaR by risk type; liquidity, climate, concentration | **Not built** (RL5, RL8; RO4, RO8, RO10 in [section 5.3](../plans/LENSES_PROPOSALS.md#53-gaps-and-proposals); the MR cube in [section 6](#6-mr-cube-lens)) | — |

**The landings.** One template, [`risk_landing.html`](../../drishti-console/plugins/banking/templates/home/risk_landing.html), with
[`drishti_risk-landing.js`](../../drishti-console/plugins/banking/static/js/drishti_risk-landing.js); every figure is the engines' own
answer through `/api/var/...` and `/api/exposure/...` (`scope`, `scope/history`, `tree`, `intraday/streams`, `official`, `baskets`). Not
Sutras (a Sutra lays out one entity's view; these span the engines). **A: Risk desk** (`desk`, the default): tiles and two ranked cards;
**B: Command first** (`command`): a big command line, the lens's chips, a status strip, the person's baskets and monitors; **C: Risk map**
(`map`): the hierarchy with VaR and limit use per node, a limit-use heat map, a **Today** feed. Panel by panel in
[section 1.4.1](../plans/LENSES_PROPOSALS.md#141-the-risk-landings-risk-landinghtml).

## 2. Trading lens

**Built.** The first line of defence's lens: "for traders and desk heads: my books, my P&L, my VaR and limits, my counterparties, and
'does this new trade fit?'" ([`config/lenses/banking/trading.yaml`](../../config/lenses/banking/trading.yaml)). Three landings (Trader
desk, Desk head, Explorer) on a green accent; how to set it up and run it: [ADMIN_GUIDE.md section 16.2](../guides/ADMIN_GUIDE.md#162-drishti-trading).
The proposals below (TL1 to TL14) are candidates in the plan's
Lens product candidates.

### 2.2 What it answers today

| Need | Status | Where it is described |
|---|---|---|
| P&L today: MtM change of every trade valued on both the business date and the day before, by book, by trader, overnight movers | **Built** (MtM change only; the bank's struck P&L is not received; the pricing system's `PNL_1D` is not used) | [ADMIN_GUIDE.md, How the figures are made](../guides/ADMIN_GUIDE.md#162-drishti-trading) |
| My VaR, Desk VaR (any union of books), top positions and VaR by trader from the server's contributions; labelled intraday after updates | **Built** | [VAR_GUIDE section 5](../guides/VAR_GUIDE.md#5-scopes-system-baskets-and-baskets), [section 11.3](../guides/VAR_GUIDE.md#113-path-and-contributions), [section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes) |
| 10-day VaR and the confidence chosen | **Built** on the VaR page; on the landings: TL5 | [VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen) |
| The scenario matrix (`SMAT`, "is this tail scenario new?") | **Built**, on in the lens (`SMAT BOOK BOOK-RATES-1 20`) | [VAR_GUIDE section 3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat) |
| VaR limit use per book and desk; limit use by book over 5 official dates; breaches and near limit | **Built** (`var-limit`; the server's `limitStatus` with `drishti.risk.limit-near` 0.8) | [VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits) |
| P&L against VaR: hypothetical P&L against the VaR before it, 60 days, exceptions circled | **Partly built**: Drishti's derived hypothetical P&L only (TL3); left out without official history | [VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting) |
| DV01 of my books | **Partly built**: Σ live trades' `DV01`; no bucketed sensitivities (TL7) | |
| Trades today (new, amended, cancelled), today's blotter, trade pages and history | **Built** ("new" reads 0 on the demo: TL1) | [trading pack README](../../modules/banking/packs/trading/README.md), [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time) |
| Counterparties: `CTRD`, `NSET`, `NSMTM`, `CPMTM`; pre-deal PFE against the credit limit | **Built** | [EXPOSURE_GUIDE section 7](../guides/EXPOSURE_GUIDE.md#7-pre-deal-what-if) |
| The desk's largest counterparty | **Partly built**: by share of positive MtM, not exposure (TL2) | [EXPOSURE_GUIDE section 4](../guides/EXPOSURE_GUIDE.md#4-scopes) |
| Alerts on numbers and monitors | **Built**, each person sets their own (one-click watch: TL10) | [USER_GUIDE.md, Alerts on numbers](../guides/USER_GUIDE.md#alerts-on-numbers) |
| The bank's market data a trader reads (`CRV`, `FX`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS`, `FXF`, `IRV`, `CPV`, `FIX`, `REPO`) | **Built**, in one Markets menu | [section 2.2.3](#223-the-lens-is-configuration) |
| "My": user → trader or desk (lens file, console, a matching trader record, then View as); landing by trading role; never widening access (tested) | **Built**; from the directory: TL13; `lens.roles` not wired | [ADMIN_GUIDE.md, "My": who a person is, configured](../guides/ADMIN_GUIDE.md#162-drishti-trading) |
| P&L explain (`PNL`) | **Partly built**: the stored explain is shown, its waterfall *under construction* (a generator's steps disagree with the bars); the engine is TL4 | [the release notes](../../RELEASE_NOTES.md), [PNL.md](../plans/contracts/PNL.md) |
| Actual P&L, month-to-date and year-to-date, intraday P&L, stop-loss, sensitivity and notional limits, breach records | **Not built**: TL3, TL9, TL11, TL7, TL6 | section 2.3 |

What the data does not give is left out or labelled, never invented: [ADMIN_GUIDE.md, What the data does not give](../guides/ADMIN_GUIDE.md#162-drishti-trading).

#### 2.2.1 The landings

One template (`home/trading_landing.html`), figures worked out on the server by the trading module (`TradingLanding`, `/api/v1/trading/landing/*`) and passed on read-only by `trading_routes.py`
(`/api/home/trading/{me,valuations,blotter,risk,limits,pnl-var,traders}`, `POST /api/home/trading/check`; `404 DRS-4023` unless the
person's lens shows `TRD`), cached two minutes per person, date and scope. **A Trader desk** (the default): P&L today, My VaR, Limit use,
DV01, Trades today, Check a new trade, Today's blotter, Top positions, Quick commands. **B Desk head**: Desk P&L today, Desk VaR and limit
use, Breaches, Near limit, Largest counterparty, Limit use by book, By trader, P&L against VaR, Overnight movers. **C Explorer**: a big
command line, the `home.chips`, the commands grouped, Recently opened. Panel by panel in [section 2.4.1](../plans/LENSES_PROPOSALS.md#241-the-trading-landings-trading-landinghtml).

#### 2.2.2 The new-trade check

The engines price a **copy of a booked trade**: the check picks a live trade of the product in my books (the counterparty's own first,
then the chosen book's, then the largest notional) and scales it (factor = notional ÷ its notional, negative when the side differs).

- **VaR**: `POST /api/v1/risk/var/what-if` on `BOOK:<book>` → VaR and limit use before and after: **fits**, **near**, **breaches**; when
  the engine cannot answer (no vectors on the date) the half says why.
- **Counterparty**: `POST /api/v1/risk/exposure/what-if` on `CPTY:<cp>` (method `paths`, the copy into the counterparty's largest netting
  set) → peak PFE before and after against the credit limit; the `addon` proxy, labelled, when `paths` cannot run.

A scaled copy is exact for a **linear** product with the same terms; for an option, a different strike or tenor, or a new currency pair
it is an approximation the answer does not yet quantify (TL8). Set-up: [ADMIN_GUIDE.md, The new-trade check](../guides/ADMIN_GUIDE.md#162-drishti-trading).

#### 2.2.3 The lens is configuration

What a trader sees is the lens file [`trading.yaml`](../../config/lenses/banking/trading.yaml) over the menus file
[`sitemap.yaml`](../../drishti-console/config/sitemap.yaml); no code draws a trading menu or hides a trading command.

| Change | Status | Where |
|---|---|---|
| `PNL`, `DPNL`, `SENS` in the Risk menu; `FXF`, `IRV`, `CPV`, `FIX`, `REPO` unhidden | **Built** | `commands.allow`; the P&L explain waterfall stays *under construction* ([`config/under-construction.yaml`](../../config/under-construction.yaml), `pnl-explain-waterfall`) |
| One **Markets** menu with every market-data screen: rates (`CRV`, `REPO`, `FIX`), vols (`IRV`, `CPV`, `FXV`), FX (`FX`, `FXF`), credit (`CDS`, `BND`), equities and commodities (`EQ`, `EQX`, `CMD`, `CMDC`) | **Built** | `menus.markets`; `gather:` of the Curves, Volatility, FX and Credit menus |
| BI and Admin by command only (`BI <GO>`, `ADMIN <GO>`, `ADMIN <page>`, administrators only) | **Built** | `menus.top` names neither |
| Tests | **Built** | `test_lens_menus.py`, `test_lens_menus_browser.py`, `test_phone.py`, `test_phone_browser.py`, `EditionTradingServerTest` |

## 3. Market lens

**Built.** "The bank's own market data in one product: curves, fixings, FX, volatility surfaces and cubes, credit curves, equities,
inflation, commodities, bonds and correlations, the inputs that feed risk and valuations. No trades and no VaR"
([`config/lenses/banking/market.yaml`](../../config/lenses/banking/market.yaml)). Three landings (Market desk, Explorer, Quality) on a
violet accent; set-up and running: [ADMIN_GUIDE.md section 16.3](../guides/ADMIN_GUIDE.md#163-drishti-market); every kind and field: the
[market-data pack README](../../modules/market-data/packs/market-data/README.md). Proposals ML1 to ML11 are candidates in the plan's
Lens product candidates.

### 3.2 What it answers today

| Need | Status | Where |
|---|---|---|
| Rates (`CRV`, `REPO`, `IRV`, `CPV`, `FIX`, `INF`, `INFC`); FX (`FX`, `FXF`, `FXV`); credit and bonds (`CDS`, `BND`); equities and commodities (`EQ`, `EQX`, `DIV`, `EQV`, `CMD`, `CMDC`, `CMDV`); correlations computed from history (`CORR`, `CORRC`, `EQB`) | **Built** | `market.yaml` `commands.allow`; [market-data README](../../modules/market-data/packs/market-data/README.md) |
| Holiday calendars and issuers (`CAL`, `ISS`) | **Loaded, hidden** (the checks use calendars). **Decided:** show them in Market | [ADMIN_GUIDE.md section 16.3](../guides/ADMIN_GUIDE.md#163-drishti-market) |
| An item's history; query by form | **Built** (the pack's `history:` block) | [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time) |
| Biggest moves in σ of each item's own history; a curve against a week ago | **Built** (desk ranking only; per pillar and decomposed: ML5) | section 3.2.1 |
| Quality checks: stale, gaps, discount factors and forwards, vol surfaces | **Built** (jumps, two sources, grids, completeness: ML2, ML3, ML4, ML8) | section 3.2.2 |
| Late loads by expectation | **Built** for loads (per id: ML8) | [DATA_GUIDE.md section 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data) |
| Monitors and alerts on numbers | **Built** (`market.yaml` `features`) | [USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers) |
| Proxy status of each P&L vector (`ok`, `proxied`, `failed`) | **Partly built**: in the Risk packs, not in Market (ML9) | [VAR_GUIDE section 15.2](../guides/VAR_GUIDE.md#152-pnl-vector-one-pl-vector-per-trade) |
| Who uses each item; VaR and valuations at stake | **Not built**: trades carry `LEGS[].INDEX` (a benchmark name) and `DISCOUNT_INDEX`, not market-data ids (ML1) | [trading pack README](../../modules/banking/packs/trading/README.md) |
| Two sources and IPV; kept quality records; market-data BI | **Not built** (ML3, ML7, ML6); the lens keeps BI to `market-data-*` names so it never offers VaR or exposure | — |
| The market-data line of a data-trust card | **Proposed** as RO13 ([section 5.3](../plans/LENSES_PROPOSALS.md#53-gaps-and-proposals)), reusing the Quality checks | — |

#### 3.2.1 The landings

One template (`home/market_landing.html`); figures worked out on the server by the market-data module (`MarketLanding`, `/api/v1/market-data/landing/*`) from the pack's own answers (search, raw documents,
the business date, one field's history through `/api/v1/history/{kind}/{id}/series`), read as the person, cached two minutes, served by
`/api/home/market/desk`, `/moves` and `/quality` (`404 DRS-4023` unless the lens shows `CRV`).

- **A Market desk** (the default): tiles USD OIS 10Y (`TEN_Y`), USD 2s10s (`SLOPE_2S_10S`), EUR/USD 1M ATM vol (`ATM_1M`), CDS 5Y median
  (every credit curve's `SPREAD_5Y`), Data exceptions (opens C); a change only when the latest day and the day before both hold real data.
  **USD curves** (OIS today against five business days before when held). **Biggest moves** over 21 business days: each curve's `TEN_Y`,
  credit's `SPREAD_5Y`, FX's `MID`, commodity's `FRONT`, index's `LEVEL`, in σ when ten or more moves are held ("jump" at 3σ); fewer: the
  move against the day before; none: says so.
- **B Explorer**: command line and `home.chips`, counts by asset class, the commands grouped with their counts, Recently opened, Knowledge,
  Data map, Learn.
- **C Quality**: the checks worst first, tiles Critical, Warnings, Clean; the VaR at stake tile reads *Impact on VaR and valuations comes
  in a later release*; a Checks run card.

Panel by panel in [section 3.4.1](../plans/LENSES_PROPOSALS.md#341-the-market-landings-market-landinghtml).

#### 3.2.2 The quality checks

| Check | Rule | Status |
|---|---|---|
| Stale | `AS_OF` older than the business date by more than `quality.stale-days` (2 business days); other kinds by their latest load | **Built** |
| Gaps | a curve missing a tenor every other curve of its type has (types of three or more) | **Built** |
| Discount factors and forwards | a DF not falling with maturity or outside (0, 1]: critical; a negative forward: warning | **Built** |
| Vol surfaces | a vol at or below zero; ATM total variance falling with expiry (FX, equity) | **Built** |
| Jumps over n σ | — | **Not built** (ML2) |
| Two sources disagreeing | — | **Not built** (ML3) |
| Calendar and smile checks on commodity, cap and swaption grids | — | **Not built** (ML4) |
| Completeness against an expected list | — | **Not built** (ML8) |

On the committed demo every check is clean; the checks are proved on crafted bad data in `test_market_landing.py`.

## 4. World lens

**Built.** "The public-data packs in one product: economies and regions, news and quotes, US companies and filings, legal entities
(GLEIF), funds and ETFs" ([`config/lenses/economy/world.yaml`](../../config/lenses/economy/world.yaml)). It is **context**, not the bank's
book: none of its packs links into the bank's own data. Three landings (World desk, Explorer, World map) on a teal accent; set-up:
[ADMIN_GUIDE.md section 16.4](../guides/ADMIN_GUIDE.md#164-drishti-world). **Licensing first:** the IMF figures, Yahoo prices and Google News headlines
are **internal only**, shown inside the bank; any showing outside needs a decision first
([WORLD_DATA_GUIDE.md section 19.9](../guides/WORLD_DATA_GUIDE.md#199-licences-and-internal-only-data)). Proposals WL1 to WL12 are candidates in
the plan's Lens product candidates.

### 4.2 What it answers today

| Need | Status | Where |
|---|---|---|
| About 200 economies (output, growth, prices, jobs, debt, external balance); governments, parties, office holders, elections; social and governance indicators (`ECON`, `MACRO`, `FCST`, `LABR`, `FISC`, `SOCI`, `JUR`, `PARTY`, `OFFC`, `ELEC`) | **Built** (World Bank, IMF, CIA World Factbook, UNDP, WGI); elections' next date is free text (WL3) | [world-economy README](../../modules/economy/packs/world-economy/README.md) |
| IMF forecasts with their vintage (`FCST`, `VINTAGE`) | **Built**, internal only; revisions between vintages: WL6 | same |
| Country groups (World Bank regions, income groups, the World), GDP-weighted (`ECG`, `macro-groupings`) | **Built**; G7 and G20 are member lists in the landing's script (WL2) | same |
| Regional economies (`REGE`) | **Built**, illustrative ("not for reporting") | same |
| Fifteen news topics and twenty companies with headlines; live quotes (offline: a captured snapshot) | **Built**, Google News and Yahoo, internal only, quotes off until enabled; index levels and FX not in the snapshot (WL1) | [news-and-markets README](../../modules/economy/packs/news-and-markets/README.md) |
| S&P 500, Nasdaq-100, Dow 30 and the US, Canadian and UK banks filing with the SEC: filings, ten years of XBRL financials, LEI, SIC, index membership (`USEQ`, `FACT`, `FILING`, `EDGAR`) | **Built**; index weights, levels and GICS not shipped; prices live only (`PXH`, off by default, internal only) | [us-equities README](../../modules/capital-markets/packs/us-equities/README.md) |
| LEI records, groups (ultimate parent and up to 50 consolidated entities), relationships, mappings, late renewals (`LEI`, `LEIG`, `LEIR`, `LEIC`, `LEIM`); company and LEI linked both ways | **Built**, CC0; changes over time: WL6 | [gleif README](../../config/packs/core/gleif/README.md) |
| The 200 largest US funds and ETFs: holdings, derivatives and counterparties, flows, returns, N-CEN facts; who holds a company, what tracks an index (`FUND`, `HELD`, `TRACK`) | **Built**, public domain; newest N-PORT only (WL7); no NAV history | [funds README](../../modules/capital-markets/packs/funds/README.md) |
| BI: `world-economics-economies`, `region-economics-regions` and their reports | **Partly built** (companies, funds, entities: WL8) | `world.yaml` `bi` |
| One standalone refresh script per pack (`status`, `refresh --dry-run`, `refresh`) | **Built**; a freshness card and schedule: WL11 | [WORLD_DATA_GUIDE.md section 19.2](../guides/WORLD_DATA_GUIDE.md#192-the-feeds) |
| A lens beside the others on a full instance (`drishti.lenses: default`) | **Built** | [ADMIN_GUIDE.md section 14](../guides/ADMIN_GUIDE.md#14-offer-lenses-to-people) |
| A change feed ("since yesterday"); news relevance; a coverage list; hiding internal-only sources | **Not built** (WL4, WL5, WL9, WL10, WL12) | section 4.3 |

#### 4.2.1 The landings

One template (`home/world_landing.html`); every figure is the packs' own answer through one thin read-only route, `/api/home/search`
([`edition_routes.py`](../../drishti-console/routes/edition_routes.py): the server's search grammar, refused with `404 DRS-4023` for a
command the lens does not show), and `/api/raw` for entities; each panel paints when its answer arrives.

- **A World desk** (the default): World GDP growth (`ECG-WLD`); **Quotes up and down** and **Biggest mover** from the snapshot (in place of
  the S&P 500 level and USD/CAD: the snapshot holds twenty equity quotes, no index and no FX); New SEC filings (the feed's latest day);
  **Funds and ETFs** (in place of elections in the next 90 days: the next date is free text); Economies that moved (the twelve largest,
  this year's growth against last); Today's news (`home.topics`: rates, inflation, oil, Fed, ECB, AI); quick cards `ECON`, `USEQ`, `LEIG`,
  `FUND`.
- **B Explorer**: command line and `home.chips`, a counts strip (economies, US companies, legal entities, funds, filings, news topics),
  Recently opened, Knowledge, Data map, Learn.
- **C World map**: the world by region, income group, G7 or G20 and by growth, inflation or debt to GDP, each group GDP-weighted from its
  economies (the EU and the African Union are not economies of the pack; income groups stand in for emerging markets); US sectors (revenue
  by SIC division); Largest funds; What changed (each source's latest: the IMF vintage, the filings, the latest annual report, the
  snapshot, GLEIF on file; counts of changes need WL4).

Panel by panel in [section 4.4.1](../plans/LENSES_PROPOSALS.md#441-the-world-landings-world-landinghtml).

#### 4.2.2 Sources and licensing

| Source | Pack | Licence | Internal only | Used for | Cadence |
|---|---|---|---|---|---|
| World Bank WDI and WGI | world-economy | CC BY 4.0 | no | economies, indicators, labour, regions, governance | monthly to yearly |
| **IMF World Economic Outlook** | world-economy | reuse with attribution; commercial use needs the IMF's permission | **yes** | forecasts; fiscal debt and net lending | April, October |
| CIA World Factbook | world-economy | public domain | no | governments, office holders, elections, parties | irregular |
| UNDP HDI | world-economy | CC BY 3.0 IGO | no | social indicators | yearly |
| **Google News RSS** | news-and-markets | headlines belong to their publishers; no redistribution | **yes** | news | continuous |
| **Yahoo Finance** | news-and-markets, us-equities | unofficial endpoint; terms forbid redistribution | **yes** | quotes, the snapshot, price history | live; the snapshot by capture |
| SEC EDGAR, N-PORT, N-CEN, prospectus data | us-equities, funds | public domain; EDGAR fair access (the scripts honour its rate limits) | no | filings, XBRL facts, funds | daily; quarterly |
| Wikidata, Nasdaq Trader | us-equities, funds | CC0; public directory | no | company facts, tickers | on refresh |
| GLEIF | gleif, us-equities, funds | CC0 | no | LEIs, relationships, mappings | daily |
| ECB reference FX rates (WL1) | proposed for World | free reuse, source acknowledged | no | FX | daily |
| Index levels (WL1) | the Yahoo snapshot | the index owner's terms | **yes** | index levels | by capture |
| G7, G20 membership (WL2) | world-economy | public fact | no | groups | on change |

Every source at a glance: [WORLD_DATA_GUIDE.md section 19.11](../guides/WORLD_DATA_GUIDE.md#1911-every-external-source-at-a-glance). The committed
IMF forecasts and the Yahoo and Google snapshot are for internal demos; before any external or client use, rebuild without them or get
permission. Nothing World shows from Yahoo may be redistributed, screenshots in external decks included. World needs no bank data.

## 5. Oversight lens

**Status: proposed; no lens file yet.** Drishti Oversight is the lens of the **second line of defence** for market risk: the market risk
management function under the CRO, independent of the desks. It answers *are we within appetite, is this number right, and who has
approved what?* Decided: a separate lens (not a landing inside Drishti Risk), built after its two own features exist: the VaR change
explanation (RO1, **built**) and the breach workflow (RO2, proposed). How a lens is set up: [ADMIN_GUIDE.md](../guides/ADMIN_GUIDE.md#12-what-a-lens-is).

### 5.2 What it answers today

Everything below is built in the engines and lenses the Oversight lens would reuse; the guides are the reference.

| Need | Status | Where |
|---|---|---|
| VaR 99% and ES 97.5% of any scope; component, incremental, marginal VaR; any confidence and window per request | **Built** | [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts), [18.5](../guides/VAR_GUIDE.md#185-the-scenario-window-and-the-confidences) |
| 10-day VaR (√10 and overlapping sum); stressed VaR; the stress-window selection `SWIN` | **Built** | [VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen), [18.8](../guides/VAR_GUIDE.md#188-stressed-var), [18.9](../guides/VAR_GUIDE.md#189-choosing-the-stress-window) |
| Several hierarchies (management, legal entity, limit letters `LL`) | **Built** | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-promoted-columns) |
| VaR limits per node and system basket; `LIMIT_USE` on every answer and official record; near from `drishti.risk.limit-near` (0.8) | **Built** | [VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits), [3.2](../guides/VAR_GUIDE.md#32-the-screen), [19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| Why VaR moved (RO1) | **Built** | [VAR_GUIDE section 3.11](../guides/VAR_GUIDE.md#311-why-it-moved-the-var-change-explained); [5.3.1](../plans/LENSES_PROPOSALS.md#531-ro1-var-change-explanation) |
| Worst scenarios and who lost on them; the scenario matrix `SMAT` | **Built** | [VAR_GUIDE section 3.4](../guides/VAR_GUIDE.md#34-the-tabs), [3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat) |
| Captures and compare; official end-of-day records, append-only, seven years, reproducible | **Built** | [VAR_GUIDE section 6](../guides/VAR_GUIDE.md#6-captures-and-compare), [19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| Backtesting (actual and hypothetical P&L, zone, multiplier, Kupiec, Christoffersen) | **Built, needs the bank's data** (`desk-pnl`) | [REGULATORY_VAR_REPORTING section 4.6](../guides/REGULATORY_VAR_REPORTING.md#46-backtesting), [VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting) |
| Period statistics; VaR by risk type (by hierarchy) | **Built** (risk types need the bank's hierarchy) | [REGULATORY_VAR_REPORTING sections 4.4, 4.5](../guides/REGULATORY_VAR_REPORTING.md#44-period-statistics) |
| Report definitions, checks, frozen runs with lineage, maker-checker-approver sign-off | **Built** (the pilot pack) | [REGULATORY_VAR_REPORTING section 6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off) |
| Position sensitivities by risk factor (`SENS`), the factor dictionary; FRTB standardised approach | **Built, needs the bank's data** | [PIVOT_GUIDE section 27.9](../guides/bi/PIVOT_SCALE_AND_CUBES.md#58-position-sensitivity-the-sensitivities), [FRTB_SA_GUIDE section 3](../guides/FRTB_SA_GUIDE.md#3-the-inputs-and-their-data-contract) |
| DV01 ladder by currency and tenor bucket against configured two-sided bucket limits | **Built** (MR cube tab C) | [PIVOT_GUIDE section 29.6](../guides/bi/PIVOT_SCALE_AND_CUBES.md#76-tail-attribution-sensitivities-and-what-if) |
| Intraday VaR from files and Kafka; the pre-deal what-if | **Built** | [VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes), [8.5](../guides/VAR_GUIDE.md#85-the-pre-deal-what-if-under-the-baskets-sheet) |
| Data trust: load checks and rejections, late loads, proxied/failed vector status, market-data quality | **Partly** (each in its own place; completeness against the trades booked not checked) | [VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks), [DATA_GUIDE section 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data), the [Market lens](#3-market-lens) |
| Stress: stored definitions and results per desk; the bank's P&L per scenario and each desk's stress limit use (derived) | **Partly** (no stress engine for any scope) | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived), [STR.md](../plans/contracts/STR.md) |
| Concentration: VaR of any slice | **Partly**: scope words, rules and `WHERE:`; no concentration view | [VAR_GUIDE section 5.5](../guides/VAR_GUIDE.md#55-scope-words-by-a-trades-attribute) |
| Counterparty exposure and credit limits (read for concentration) | **Built** | [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) |
| Alerts on numbers; comment threads anchored to the data; write-back approval with four eyes | **Built** (a person sets each alert) | [USER_GUIDE, Alerts](../guides/USER_GUIDE.md#alerts), [ARCHITECTURE.md §31](../guides/USER_GUIDE.md#discussion), [APPROVALS_GUIDE section 10](../guides/USER_GUIDE.md#the-approvals-queue) |
| Certified BI reports; entitlements and audit per answer | **Built** | [REPORTS_GUIDE, certification](../guides/bi/GOVERNANCE_AND_AI.md#24-certification-and-endorsement), [VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit) |
| P&L explain by cause; PLA test; FRTB internal models | **Not built** (P&L explain stored per desk as read, `PNL`) | [PNL.md](../plans/contracts/PNL.md), [REGULATORY_VAR_REPORTING section 8.1](../guides/REGULATORY_VAR_REPORTING.md#81-coverage-report-by-report) |

## 6. MR cube lens

**Built, as a Pivot preset.** The MR cube is the Pivot's preset `mr` (`MCUBE <GO>`, `/pivot?preset=mr`): the pivot engine on the VaR
source with the MR lens around it (headline strip, time-travel slider, tabs B and C). It puts market risk on any rows and columns with
**correct non-additive totals**: every cell, subtotal and total is the VaR (or ES, SBM …) of its own set of trades, re-aggregated from their
scenario P&L vectors, never a sum of other cells. How to use it: [PIVOT_SCALE_AND_CUBES.md section 29](../guides/bi/PIVOT_SCALE_AND_CUBES.md#7-the-mr-cube-preset) and
[PIVOT_SCALE_AND_CUBES.md › Presets](../guides/bi/PIVOT_SCALE_AND_CUBES.md#6-presets-the-risk-cubes); the tutorial is
[tutorial 7, Part C](../guides/bi/PIVOT_SCALE_AND_CUBES.md#part-c--the-mr-cube-desks-by-risk-class-with-the-right-totals). This section keeps the product view: who it serves, what is still
open, the build checklist, and the **shared cube core** and **data contracts** that the CCR cube ([section 7](#7-ccr-cube-lens)) reuses.

### 6.2 What it answers today

| Need | Status | Where it is described |
|---|---|---|
| Any rows and columns over the VaR book (hierarchy levels, position and trade columns, the derived `RISK_CLASS`, members) | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.4](../guides/bi/PIVOT_SCALE_AND_CUBES.md#74-the-view-dimensions-options-cells-and-totals) |
| VaR, ES, PV, trades, component VaR and ES, incremental VaR, limit and limit use, stressed VaR, DV01, CS01, SBM per cell | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.2](../guides/bi/PIVOT_SCALE_AND_CUBES.md#72-the-measures) |
| Totals as the union's own figure, diversification beside each total | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.4](../guides/bi/PIVOT_SCALE_AND_CUBES.md#74-the-view-dimensions-options-cells-and-totals) |
| The landing (layout A): headline, lens controls, slider, pivot, rail | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.5](../guides/bi/PIVOT_SCALE_AND_CUBES.md#75-the-landing) |
| Tab B (components, waterfall, histogram, DV01 heat map); tab C (DV01 ladder, what-if side by side, explain) | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.6](../guides/bi/PIVOT_SCALE_AND_CUBES.md#76-tail-attribution-sensitivities-and-what-if) |
| Explain the move, time travel, provenance per cell | **Built** (factor overlay not built) | [PIVOT_SCALE_AND_CUBES.md section 29.7](../guides/bi/PIVOT_SCALE_AND_CUBES.md#77-explain-the-move-time-travel-and-provenance) |
| Saved and shared views, export (Excel with outline and formulas, CSV, Arrow, JSON Lines, YAML) | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.8](../guides/bi/PIVOT_SCALE_AND_CUBES.md#78-saved-views-sharing-and-export), [PIVOT.md section 10](../guides/bi/PIVOT.md#10-export) |
| The engine switch, cell budgets, refusals | **Built** | [PIVOT_SCALE_AND_CUBES.md sections 29.9 and 29.11](../guides/bi/PIVOT_SCALE_AND_CUBES.md#79-settings-and-switching-the-cube-off) |
| Sharded dates: one batched call per shard owner, equal to the bit | **Built** | [PIVOT_SCALE_AND_CUBES.md section 29.12](../guides/bi/PIVOT_SCALE_AND_CUBES.md#712-how-it-is-computed), [6.5.11](../plans/LENSES_PROPOSALS.md#6511-cluster-execution-sharded-cube-views-bit-equal) |
| What the guide lists as not built | open | [PIVOT_SCALE_AND_CUBES.md section 29.13](../guides/bi/PIVOT_SCALE_AND_CUBES.md#713-not-built-yet), and [6.3](../plans/LENSES_PROPOSALS.md#63-gaps-and-proposals) below |

#### 6.2.1 The lens: a Pivot preset, not a lens file

The MR cube is **a preset of the one Pivot** (one grid, several doors; [PIVOT_SCALE_AND_CUBES.md › Presets](../guides/bi/PIVOT_SCALE_AND_CUBES.md#6-presets-the-risk-cubes)),
not a lens file of its own with its own endpoints:

- **Doors:** `MCUBE <GO>` (with `<scope> BY rows,columns MEASURE … DATE … SORT desc`, or `@owner/name` for a saved view); **VaR → MR cube**;
  the VaR explorer's **MR cube ›** link (its scope and date); `/mcube`.
- **The grid** is the pivot engine's (`POST /api/v1/pivot/query`, source `var`); the **MR lens** adds `POST /api/v1/risk/mr/headline`,
  `/detail`, `/history`, `GET /versions`, `/sensitivities`, `POST /what-if` (`MrCubeExtras`, `MrSensitivities`, `VarPivotProvider` in
  `drishti-banking`; `drishti_mr-lens.js`, `templates/terminal/_mr_lens.html` and `_mr_panes.html` in the console's banking plugin).
- **The switch** is the compute engine `mr-cube` (`config/engines/mr-cube.yaml`; off: `DRS-1020`, the word, menu entry and help gone).
- **Lenses:** the cube is a screen in the Risk lens (and Trading on the person's desk scope); a separate `mrcube` lens file
  ([ADMIN_GUIDE.md section 14](../guides/ADMIN_GUIDE.md#14-offer-lenses-to-people)) is **not built** and not needed while the preset carries the doors.

The proof-of-concept decisions stand as the design: **Decided:** one shared core first, then MR, then CCR (P1); synthetic sensitivities
derived from the VaR factor model, consistent with the vectors (P2); time travel over dates and batches, not raw lake versions (P3); explain
in exact steps first, the full RO1 sequence and the overlay later (P4); the cube on in Risk, and on in Trading for `MCUBE` only (P6); a
cell budget refusing a view with its count, never truncating (P7, now the pivot engine's `max-holistic-cells`).

#### 6.2.2 The proof of concept, as built

| Row | What | Status |
|---|---|---|
| MCP1 | The core: view model, planner, totals as the union's figure, batching per shard owner, provenance, saved views, cell budget | **Built** as the pivot engine plus the VaR provider ([PIVOT.md section 18](../guides/bi/PIVOT.md#18-how-it-is-built)) |
| MCP2 | MR endpoints: query, cell, explain, versions, sensitivities | **Built** under `/api/v1/pivot` and `/api/v1/risk/mr` ([PIVOT_SCALE_AND_CUBES.md section 29.10](../guides/bi/PIVOT_SCALE_AND_CUBES.md#710-api-and-command-line)); explain is the one Explain |
| MCP3 | Synthetic data: sensitivities from the factor model, factor dictionary, limits, per-class vectors | **Partly built**: `position-sensitivity` and `risk-factor` demo data built; ladder limits are configuration; per-class vectors not built |
| MCP4 | Console and lens: landing A with tabs B and C, phone tiers, renderer kind, help | **Partly built**: landing and tabs built; phone tiers beyond the pivot's narrow layout and a `cube-view` renderer kind not built |
| MCP5 | Tests: totals never summed, explain adds up, shard equality | **Built**: `VarMrCubeShardTest`, `test_mcube.py`, `test_cube_presets.py`, `test_mr_cube_browser.py` |
| MCP6 | Docs and the demo script | **Built**: the guide and tutorial 12, Part C |

**Success criteria** (all hold as built): the grand total equals `VAR BANK` (112,041,646 on the demo) exactly; the bank's total differs
from the sum of the desks' VaR (367.9m) and equals the union's own VaR; every cell and total is equal on one server and on a sharded date.
The response targets (landing 300 ms, re-pivot 400 ms, a cell 60 ms, explain 1 s, ladder 200 ms, p95 warm) are to be measured at the
reference size ([6.3.10](../plans/LENSES_PROPOSALS.md#6310-performance-and-scale-targets)).

**The demo script** (ten minutes): open the cube on the bank (VaR 112.0m, the diversification row); click CREDIT × CR for the tail and the
provenance; drag `LEGAL_ENTITY` onto columns; drill CREDIT to books, positions, a trade; tab B, the waterfall adding to 112,041,646 with
SFT's hedge; the DV01 heat map; tab C, the ladder and an unwind in the what-if; the slider to yesterday and **Explain**; close on the
comparison of [6.3.11](../plans/LENSES_PROPOSALS.md#6311-the-fis-comparison).

## 7. CCR cube lens

**Under construction: the preset exists, its source does not yet.** The CCR cube is the Pivot's preset `ccr` (`CCUBE <GO>`,
**Counterparties → CCR cube**, `/pivot?preset=ccr`), landing on the **limit board** with the netting-set drill; its page says *Under
construction* until the CCR provider is built ([PIVOT_SCALE_AND_CUBES.md › Presets](../guides/bi/PIVOT_SCALE_AND_CUBES.md#6-presets-the-risk-cubes),
[UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md), item `ccr-cube-preset`). Its purpose: how much could each counterparty, group or
legal entity owe us at a future date after netting and collateral, against which limit at which tenor, at what cost (CVA), capital
(SA-CCR, IMM, CVA capital) and margin (SIMM), for any rows and columns. It runs on the **shared cube core** of
[section 6.5](../plans/LENSES_PROPOSALS.md#65-the-shared-cube-core-mr-and-ccr) and reuses the MR cube's contracts ([section 6.6](../plans/LENSES_PROPOSALS.md#66-data-and-contracts)); this
section holds only what is specific to counterparty risk. Meanwhile every counterparty's computed exposure is the Exposure explorer
([EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md), `EXP`).

### 7.2 What it answers today

| Need | Status | Where it is described |
|---|---|---|
| EE, ENE, EEE, PFE 95% and 99%, EPE, EEPE, CE, IMM EAD (informational) for any scope after netting and lagged collateral | **Built** | [EXPOSURE_GUIDE sections 2 and 18.4](../guides/EXPOSURE_GUIDE.md#184-statistics-of-a-scope) |
| Scopes `NSET:`, `CPTY:`, `GROUP:`, `LE:`, `DESK:` and `BOOK:` (partial), `TRD:`, `WHERE:`, `+ - &` | **Built** | [EXPOSURE_GUIDE section 4](../guides/EXPOSURE_GUIDE.md#4-scopes) |
| Collateral models `lagged`, `current`, `none`; MPOR from the run's look-back | **Built** | [EXPOSURE_GUIDE section 18.3](../guides/EXPOSURE_GUIDE.md#183-collateral-on-each-path-margined-netting-sets) |
| EE and EPE contributions per trade inside a set (Euler), PFE contributions, incremental PFE | **Built** | [EXPOSURE_GUIDE section 18.6](../guides/EXPOSURE_GUIDE.md#186-contributions-decomposition) |
| Pre-deal what-if (exact paths, add-on proxy) and the pre-deal check against the credit limit and its tenor buckets | **Built** | [EXPOSURE_GUIDE sections 7 and 18.9](../guides/EXPOSURE_GUIDE.md#189-the-pre-deal-check) |
| Official records, captures, compare | **Built** | [EXPOSURE_GUIDE section 20](../guides/EXPOSURE_GUIDE.md#20-official-end-of-day-results) |
| The explorer: tree, tiles, profile, path fan, collateral, trades, pre-deal; phone layout | **Built** | [EXPOSURE_GUIDE section 3](../guides/EXPOSURE_GUIDE.md#3-the-explorer-screen-by-screen) |
| SIMM computed per netting set and regime from the shared sensitivities | **Built** (CC8) | [SIMM_GUIDE.md](../guides/SIMM_GUIDE.md) |
| The CCR cube preset, its word and its door | **Built**, under construction | [PIVOT_SCALE_AND_CUBES.md section 28](../guides/bi/PIVOT_SCALE_AND_CUBES.md#6-presets-the-risk-cubes) |
| Open items of the exposure engine (large-bank load, stored limits sized low, several runs a date, sharding) | open | [EXPOSURE_GUIDE section 28](../guides/EXPOSURE_GUIDE.md#28-open-items) |

**On the demo** (2026-09-30): the bank's peak PFE 95% is **2,897,620,439** USD at 2W (EPE 947,202,991); CP-ALDERSHOT's peak PFE
401,456,820 against the sum of its four sets' peaks 534.4m (PFE is not additive).

#### 7.2.1 The preset and the proof of concept

As the MR cube ([6.2.1](#621-the-lens-a-pivot-preset-not-a-lens-file)), the CCR cube is a **Pivot preset**, not a lens file: the
proposed `ccrcube` lens file is not built; its doors are the preset's (`CCUBE`, **Counterparties → CCR cube**), its landing
`limit-board`, its help `ccr-cube`. The grid will be the pivot engine's answer over a **CCR provider** (state per netting set: the path
matrix netted and collateralised first; finish: EE, EPE, PFE, EEPE; [ARCHITECTURE.md section 26.4](ARCHITECTURE.md#264-the-providers)),
plus CCR lens endpoints for the board, the allocation, the group tree and the waterfall.

**Decided** (the proof-of-concept decisions, kept as the design): build on the MR cube's core (P1); regenerate the demo's credit limits by
tenor bucket on the computed PFE, because the stored ones read near zero (P2); a synthetic legal tree for the demo counterparties, kept
apart from the real GLEIF data in `data/testdata/counterparty-risk/legal-parent/` (P3); allocation computed at date load per owner and
cached for the date (P4); pre-deal in the first cut by the built add-on and copied paths only, pricing waiting for QLRisk (P5); one
exposure server first, the shard equality suite joining with CC14 (P6).

| Row | What | Status |
|---|---|---|
| CCP1 | CCR endpoints: query, limits, allocation at load, group tree, waterfall | not built |
| CCP2 | Synthetic data: credit limits by tenor bucket on the computed PFE (`tools/packgen/ccr/credit_limits.py`), the legal tree (`legal_tree.py`) | not built |
| CCP3 | Console: the landing (A) with tabs B and C, treemap, fan with limit line and headroom, waterfall, overlay | **Partly built**: the preset, word and door, *Under construction* page |
| CCP4 | Pre-deal overlay on the profile with limit and headroom | **Partly built**: the pre-deal check's numbers; the overlay chart not built |
| CCP5 | Tests: allocation adds up (property test), group sums per path, board buckets, browser | not built (`test_cube_presets.py` covers the preset) |
| CCP6 | Docs and the demo script | not built |

**Success criteria:** the desks' allocated EE equals the bank's EE at every tenor to the cent, and a desk alone in a set gets the set; the
bank's peak PFE equals `EXP BANK` (2,897,620,439) exactly; a group's PFE differs from the sum of its members' peaks. Targets (estimates,
p95 warm): landing 500 ms, re-pivot 400 ms, netting-set drill 300 ms, allocation by desk at load 2 s on the demo, pre-deal overlay 300 ms
(add-on) or 1 s (paths). **The demo script:** the treemap and the board with one counterparty over its 1–5Y limit; CP-ALDERSHOT's fan with
the limit step line and headroom narrowing at 2W; rows to `GROUP` over the legal tree; NS-ALDERSHOT-LDN's waterfall (111.1m posted at
risk over the MPOR, then the MPOR effect as its own bar); rows to `DESK` with allocated EE adding to the bank; a new trade by add-on, then
as a copy of a booked swap; `MCUBE` from the same command line.

## 8. CRO lens

**Status: proposed (design, not built).** The lens id `cro` has a registry entry in the banking module
(`modules/banking/java/banking-service/src/main/resources/META-INF/drishti/lenses.yaml`); there is no lens file, landing or pack yet. Purpose: the
executive, cross-risk-type view that answers *"Is the bank within its risk appetite, what changed overnight, and what do I need to act
on or tell the board?"* It summarises and owns no engine; every figure opens the lens that computed it (MCUBE, CCUBE, Oversight,
Market, World). Its rows are the plan's candidates CRO1 to CRO13
([the release notes](../../RELEASE_NOTES.md)).

The Chief Risk Officer owns the **risk appetite framework**: a board-approved list of appetite metrics (market, counterparty and
concentration, liquidity, capital, climate), each with a board limit, a warning level and an owner. Drishti already computes the two
hardest families from trade-grain data (VaR at any scope; PFE by counterparty and connected group against limits). The lens adds an
**appetite catalogue**, a **status per metric**, **what changed overnight with the reason**, the **CRO's decision queue**, **Ask**,
**time travel** to the last board date and a **board pack** from the same figures.

### 8.2 What it answers today

| Need | Status | Where |
|---|---|---|
| Bank and desk VaR, ES at any scope; 10-day by √10 and overlapping sum | **Built** | [VAR_GUIDE 1](../guides/VAR_GUIDE.md#1-the-question-var-answers), [18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen) |
| VaR limits and limit use in the official record (`LIMIT`, `LIMIT_USE`) | **Built** | [VAR_GUIDE 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits), [19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| Stressed VaR | **Built** | [VAR_GUIDE 18.8](../guides/VAR_GUIDE.md#188-stressed-var) |
| Worst stress loss | **Partly**: stored per desk and scenario, the bank's P&L derived; not computed from trades (RO9, [section 5.3](../plans/LENSES_PROPOSALS.md#53-gaps-and-proposals)) | [VAR_GUIDE 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived) |
| Peak PFE by counterparty, connected group, legal entity against the credit limit | **Built**; group and tenor limits are CC4 ([section 7.3](../plans/LENSES_PROPOSALS.md#73-gaps-and-proposals)) | [EXPOSURE_GUIDE 4](../guides/EXPOSURE_GUIDE.md#4-scopes), [20](../guides/EXPOSURE_GUIDE.md#20-official-end-of-day-results) |
| Top-10 concentration (share of bank EPE) | **Partly**: EPE per counterparty served; the share and its limit are not a measure | [EXPOSURE_GUIDE](../guides/EXPOSURE_GUIDE.md) |
| Within / near / breach against a limit | **Built**: one server rule (`drishti.risk.limit-near`, 0.8) on every limit the banking module holds; shown on the Risk and Trading landings | [ADMIN_GUIDE 16.1](../guides/ADMIN_GUIDE.md#161-drishti-risk) |
| Compare with an earlier capture; component VaR | **Built** | [VAR_GUIDE 6](../guides/VAR_GUIDE.md#6-captures-and-compare) |
| VaR change explanation by cause (RO1) | **Built** for VaR; exposure, liquidity and capital reasons not built | [VAR_GUIDE 3.11](../guides/VAR_GUIDE.md#311-why-it-moved-the-var-change-explained) |
| A breach as a record; one limit catalogue (RO2, RO3) | **Not built**, designed ([section 5.3](../plans/LENSES_PROPOSALS.md#53-gaps-and-proposals)) | — |
| Temporary limit increases with four eyes | **Not built**; the pattern exists in write-back approval | [APPROVALS_GUIDE 10](../guides/USER_GUIDE.md#the-approvals-queue) |
| Comments anchored to data; alerts on numbers | **Built**; alerts are set per person, not from a catalogue | [ARCHITECTURE](ARCHITECTURE.md#31-collaboration-write-back-and-approvals), [USER_GUIDE, alerts](../guides/USER_GUIDE.md#alerts-on-numbers) |
| LCR, NSFR, survival horizon; CET1, RWA | **Not built** (balance-sheet roadmap; stored SA-CCR and FRTB charges are partial RWA inputs only) | |
| Financed emissions | **Not built**; climate stays out of scope as a computed measure | — |
| News, market moves, macro signals | **Built** (World and Market lenses; IMF data internal only) | [ADMIN_GUIDE 16.4](../guides/ADMIN_GUIDE.md#164-drishti-world) |
| News relevance to the book; VaR at stake of a move | **Not built** (WL9, [section 4.3](../plans/LENSES_PROPOSALS.md#43-gaps-and-proposals); ML1, [section 3.3](../plans/LENSES_PROPOSALS.md#33-gaps-and-proposals)) | — |
| Data health: late loads by expectation | **Partly**: late loads built; the safe-to-quote rule not built | [DATA_GUIDE 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data) |
| Ask in words | **Partly**: *Ask about this page* and BI's *ask the data*; an answer across engines not built; AI off in every lens today | [AI 7](../guides/ADMIN_GUIDE.md#2717-ask-on-the-ai-layer) |
| Time travel | **Partly**: official records per date; "as at the last board" across every metric not built | [VAR_GUIDE 7](../guides/VAR_GUIDE.md#7-history-and-backtesting) |

The demo's connected group `GRP-NORTHBRIDGE` has one member and a stored group limit; nothing in the code names a risk appetite, an
LCR, an NSFR, a CET1 ratio, financed emissions or a limit request.

## 9. CIO lens

**Status: proposed (design, not built).** The lens id `cio` has a registry entry in the server
(`kernel/drishti-server/src/main/resources/META-INF/drishti/lenses.yaml`, no module); there is no lens file, landing or pack yet. Purpose: the
executive view of **the platform and the risk data behind the numbers**: *"Is the risk data estate healthy, trustworthy, on time,
secure, affordable, and are people using it?"* Its strongest angle is a live **BCBS 239** scorecard with its evidence. Admin pages are
for operators fixing things; the CIO lens is a **read-only executive summary with trends and evidence**, and every row opens the admin
page or record behind it. It pairs with the CRO lens: the CRO's safe-to-quote panel ([8.3.3](../plans/LENSES_PROPOSALS.md#833-safe-to-quote)) reads this lens's
records. Its rows are the plan's candidates CIO1 to CIO14
([the release notes](../../RELEASE_NOTES.md)).

Drishti already records most of the evidence a CIO and a supervisor need as a side effect of running: every load with its rows and
rejects, expectations met or missed, a contract per kind with validation, a data version and provenance on every answer, the
configuration in one generated document, the audit and access logs, a cluster with gossip and shards, disk and cache budgets. The lens
adds one executive page that turns them into trends and a verdict: feeds against service levels with a **7am-ready forecast**, data
quality per dataset with owners and history, the **BCBS 239 scorecard**, platform health and capacity (cost later), usage, security,
change and incidents, Ask, and an **evidence pack** for a supervisor or audit.

### 9.2 What it answers today

| Need | Status | Where |
|---|---|---|
| Load records with rows, rejected rows, batch, source, steps | **Built** | [DATA_GUIDE 9](../guides/DATA_GUIDE.md#9-the-data-loads-page) |
| Expectations per pack and kind with six states (`pending`, `on-time`, `landed-late`, `late`, `missing`, `failed`); Health turns `DEGRADED` | **Built** | [DATA_GUIDE 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data) |
| A history beyond the look-back window, a dependency chain, a ready-by forecast | **Not built**: states cover today and `lookback-days`; the load history keeps `keep` loads a pack | [CONFIGURATION, `drishti.loads`](../admin/CONFIGURATION.md#drishtiloads--data-loads-a-batch-landed) |
| A contract per kind with validation and rejects | **Built** | [contracts README 10](../contracts/README.md#10-validation-and-rejects) |
| Content checks (past maturity, unmapped factors, stale prices, no LEI match) | **Not built** as checks; the Market lens shows market-data quality | [section 3](#3-market-lens) |
| Data versions and provenance on every answer | **Built** | [RESOURCES_AND_SCALE 4.2](../guides/CLUSTER_GUIDE.md#84-data-loads-configuration-and-maintenance-without-downtime) |
| BI lineage and impact; official VaR and exposure records for any past date | **Built** | [REPORTS_GUIDE 32](../guides/bi/GOVERNANCE_AND_AI.md#2-publishing-and-governance-versions-review-certification-lineage-impact-environments), [VAR_GUIDE 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| The configuration in one generated document, in the UI with value and origin | **Built** | [CONFIGURATION](../admin/CONFIGURATION.md#every-setting) |
| Audit log (sign-ins, failures, locks, users, roles, tokens, packs, connectors, load settings, cache purges, secret rotations) | **Built** | [USER_MANAGEMENT, audit](../guides/ADMIN_GUIDE.md#26-read-the-audit-log) |
| Write-back audit and four-eyes approval | **Built** | [APPROVALS_GUIDE 10](../guides/USER_GUIDE.md#the-approvals-queue) |
| Access log of every answered read, 90 days | **Built** | [USER_MANAGEMENT, access log](../guides/ADMIN_GUIDE.md#8-who-looked-at-what-the-access-log), [OPERATIONS 9b](../guides/OPERATIONS_GUIDE.md#114-what-leaves-the-server-ask-the-access-log-and-ai) |
| Usage analytics by lens, screen and desk; an entitlement report as of a date | **Not built**; the access log does not record lens or screen | — |
| Roles and powers; commands turned off by an administrator | **Built** | [ADMIN_GUIDE 3](../guides/ADMIN_GUIDE.md#3-roles) |
| Secrets inventory with ages and a rotation policy | **Partly**: secrets from environment or files, embed secrets rotated and audited; no inventory | [OPERATIONS 9c](../guides/OPERATIONS_GUIDE.md#811-permissions-ownership-and-registry-keys) |
| Internal-only rule for public sources | **Built** as a rule and switch; a report of where each is shown not built | [EXTERNAL_DATA 9](../guides/WORLD_DATA_GUIDE.md#199-licences-and-internal-only-data) |
| Admin → Health; Prometheus, Grafana, suggested alerts; platform metrics and alerts | **Built** | [OPERATIONS 13.2](../guides/OPERATIONS_GUIDE.md#132-admin--health), [13.4](../guides/OPERATIONS_GUIDE.md#134-prometheus-grafana-and-the-suggested-alerts), [13.6](../guides/OPERATIONS_GUIDE.md#135-platform-metrics-and-alert-rules) |
| Drishti's own history of those metrics | **Not built** (CIO4) | [RESOURCES_AND_SCALE 8.3](../guides/CLUSTER_GUIDE.md#159-seeing-it-admin--caches-health--disk-metrics-and-alerts) |
| Cluster gossip, VaR sharding with replicas and zones; disk caps and the cache budget | **Built** | [CLUSTER_DEPLOYMENT 4.1](ARCHITECTURE.md#321-membership-by-gossip-swim-over-http), [RESOURCES_AND_SCALE 14](ARCHITECTURE.md#3212-bounded-resources-inside-one-server) |
| Readiness, drain, the rolling upgrade runner; the N and N−1 rule | **Built**; N and N−1 planned (CD4) | [CLUSTER_DEPLOYMENT 18](../guides/CLUSTER_GUIDE.md) |
| A history of releases and upgrades per node | **Partly**: each server reports its version; no history | [OPERATIONS 15](../guides/OPERATIONS_GUIDE.md#18-upgrades-and-rollback) |
| Incidents; cloud cost; a BCBS 239 scorecard | **Not built** | — |
| Ask | **Partly**: *Ask about this page* and BI's *ask the data*, off by default; across loads, checks, audit and incidents not built | [AI 7](../guides/ADMIN_GUIDE.md#2717-ask-on-the-ai-layer) |

## 10. The lens mechanism: the design

Drishti stays one product with one code base; a lens is a named bundle of settings. The site's configuration wins over the lens file key by
key, so a bank adjusts one item without forking. Switching is a configuration change (and, for the instance's lens, a restart), never a
code change. The administrator's view of the same mechanism (the two uses, the settings, the shipped lenses) is
[ADMIN_GUIDE.md section 12](../guides/ADMIN_GUIDE.md#12-what-a-lens-is).

### 10.1 What a lens changes

| Area | How |
|---|---|
| **Packs** | no list: `drishti.packs.enabled` defaults to the word `default`: the distribution's packs, else the kernel's (`kernel.yaml` `kernel.packs`) and every module's that is on, less a manifest's `packs.off-by-default` (the word may stand in a list: `default,us-equities`); `allow-only` refuses other packs at start, in Admin → Packs, Deploy archive, the registry and the folder watcher (`403 DRS-1017`) |
| **Commands** | a command allow-list, separate from kind access (`EditionCommands`): hidden from the command line, `FUNC`, the type-ahead, the packs' examples, the entity page and search (`404 DRS-4023`); the engines still read every kind. The reserved and console words switch on and off, with their pages |
| **Menus** | `drishti-console/config/sitemap.yaml`: every menu item and footer link with an id and `requires:` (`admin`, `bi`, `designer`, `edition`, `feature:<name>`, `word:<WORD>`, `admin-page:<id>`, `pack:<name>`, `cmd:<WORD>`, `route:<path>`, `module:<id>`, `engine:<id>`); the lens lists what it shows per menu; with more than six menus the labels fold to icons on narrow screens |
| **Features** | `config/distributions/kernel.yaml `features:`` maps each feature a lens names to the flags of each side (console `features.build`, `forms`, `datamap`, `workspaces`, `monitors`, `alerts`, `lakes`, `reports`, `collaboration`, ...; the engine switches `drishti.engines.<id>.enabled`) |
| **Home** | `home.sections` switches the product's sections off; the landings; the registry's cards and chips, each shown while its function is on |
| **Help and Knowledge** | sections and Knowledge pages follow the lens (Knowledge pages tagged by pack in `help.yaml`), pack guides follow its packs, unlisted slugs refused (`help.unlisted-slugs: refuse`) |
| **Admin** | tabs, menu items and routes follow `admin.pages`; a hidden one answers 404 |
| **BI** | reduced to the lens's datasets and models (`EditionBi`, applied in `BiService`): a report or board follows its dataset or model, in the catalogue and by id |
| **Branding** | the console takes the server's product and tagline from its about answer (the console's own `ui.product` still wins); `ui.logo`, `ui.favicon`, `ui.apple_touch_icon`; a generated `/manifest.webmanifest`; `{product}` in Help titles and summaries |
| **Static files** | a module's scripts and styles are served to a person in a lens only when that lens uses the module (the World lens gets no banking file) |

### 10.2 How each side reads it

- **Server.** `EditionEnvironmentPostProcessor` (`drishti-packs`, right after the configuration files are read, before the packs'
  post-processor) loads the file as one property source placed **just above the product's own `application.yaml` and below everything the
  site sets** (application.local.yaml, the packs overlay, environment variables, arguments). It holds the file's keys under
  `drishti.edition-spec.*` and the settings the lens presets: `drishti.packs.enabled`, `drishti.branding.product`/`tagline`, each feature's
  flags (through `features.yaml`), the raw `server:` block. A preset of a setting the product writes as `${VARIABLE:default}` keeps the
  variable, so `DRISHTI_PACKS` set still wins. `Editions.of(env)` gives the `Edition` record every server class asks; blank gives
  `Edition.NONE`, which answers "allowed" to every question. `Editions.file` finds `<lens>.yaml` in the folder or one of its module folders;
  a missing file or a name that is not a plain word stops the start with what to do. `Editions.lenses` reads each name of `drishti.lenses`
  from its file alone; `EditionContext` applies a person's lens per request (`X-Drishti-Lens`).
- **Registry.** `drishti-registry` reads every jar's `META-INF/drishti/lenses.yaml` and the site's `config/access/lenses.yaml`; a lens is
  offered while its modules are on, and `GET /api/v1/modules` tells each person the lenses open to them with their modules, functions,
  cards and chips, and the functions off.
- **Console.** The console learns the instance's lens, the lenses offered, and the product name and tagline from `/public/about`, at start
  and on every check of `core/backend_watch.py`; it reads the same file and puts its presets as a settings layer between `application.yaml`
  and the site's own; the site's `edition:` block overrides the file key by key. `core/edition.py` answers every "is this shown?" question
  (with `core/modules.py` for modules and functions, and `core/engines.py` for compute engines); `core/edition_gate.py` answers 404 for a
  hidden page; `routes/edition_routes.py` draws the landings and opens, switches and leaves a person's lens. Each landing's code is its
  module's console plugin (`drishti-console/plugins/banking/`: `trading_landing.py`, `market_landing.py`, `routes/trading_routes.py`;
  `drishti-console/plugins/economy/`), each with its `home/<lens>_landing.html`, `drishti_<lens>-landing.js` and `.css`.
- **Who sees a lens.** `lens.roles` in the console's configuration is read and is the hook for role-based access; grants and denials of
  lenses per role belong to the access model ([the access model](KERNEL_AND_MODULES.md#19-the-access-model-licence-module-lens-function-permission), [the domain modules part](KERNEL_AND_MODULES.md#21-domain-modules-the-boundary-between-the-core-and-banking) section 8).
- **A lens file extending another** (`extends: risk`, so shared command lists do not drift) is decided for the Oversight lens
  ([section 5.5](../plans/LENSES_PROPOSALS.md#55-the-oversight-lens-design)) and not built.

### 10.3 Decisions (product owner)

1. Lenses are lenses of modules: a lens is offered only while its modules are on, and a card or chip only while its function is.
2. Branding: Drishti's name and eye kept; the product name is the lens's title; white-labelling through site configuration.
3. BI is reduced to the lens's datasets and models.
4. Commands are hidden, never renamed.
5. Fully clustered: one value on the servers; the console learns it from the about answer.
6. Each shipped lens has three Home landings, drawn from the engines' and packs' own answers; a figure the data cannot give is left out or
   labelled, never invented.
7. With no instance lens Drishti is the full product of the modules that are on.
8. A person's lens hides and does not secure; who may open a lens is the access model's.
9. The default lenses offered are those of the modules that are on (`drishti.lenses: default`); a lens file may say `offered-by-default: false`.
10. The IMF and Yahoo data in World are shown inside the bank; outside it needs a decision.

### 10.4 Tests

| Test | What it proves |
|---|---|
| `EditionsTest` (drishti-packs) | the default list is the one file; the layer's place; presets; a site variable still wins; a missing file stops the start; lenses; packs by module |
| `EditionRiskServerTest`, `EditionWorldServerTest`, `EditionMarketServerTest`, `EditionTradingServerTest` | a server running each lens: About and branding, its packs, `DRS-1017` for another, `FUNC` and the type-ahead, `DRS-4023` typed and by page, the packs' examples, BI datasets and reports |
| `EditionNoneAndLensServerTest`, `EditionTwoLensesServerTest`, `SettingsTest` | no instance lens: the full product; a lens header focuses one request only; several lenses offered, set and read back through `/api/v1/me/settings`, switching, others unaffected; `tradingView` validated |
| `*RiskEditionTest` (seven classes), `EditionShardClusterTest` | the VaR and exposure suites again under `risk`; two Drishti*net members with `risk`: no Admin → Cluster warning, sharded VaR equal to one server's |
| `PublicSectorLensTest`, `EconomyModuleOffTest`, `BankingModuleOffTest`, `CapitalMarketsModuleOffTest` | lenses offered or not with their modules; functions, packs and cards gone with a module |
| `test_edition.py` (console) | the full product unchanged; every surface under each lens; the landings and the remembered choice; the role hooks; "my" scoping; the lens for one person and not another; no lens with an instance lens; logo, favicon, manifest |
| `test_market_landing.py`, `test_trading_landing.py` | Market's figures and quality checks; Trading's mapping, figures, scoping and the new-trade check |
| `test_lens_menus.py` | the subject menus each lens names, drawn from the menus file and gated by the lens's words |
| `test_edition_browser.py`, `test_edition_world_browser.py`, `test_edition_market_browser.py`, `test_edition_trading_browser.py`, `test_lens_menus_browser.py`, `test_public_sector_browser.py` | the landings and menus in a browser, at phone and desktop widths, in every theme |
| `test_modules_off.py` | a lens without a module gets none of its files, functions, cards or chips |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*

### Admin → Modules: three mock-ups (for the owner's choice, before I8b builds it)

Status: design, not built (mock-ups; the commands below are the planned ones).

Shared content (all three): every module with id, title, version, origin, content hash (short), on/off switch with reason, parts placed per node,
problems (stale package, refused verify, need not met, library duplicate), each node's fingerprint and whether the nodes agree, "Download .dmod" and the
install/upgrade commands (the install command, `verify`, `restart`; nothing deploys from the browser), and a "module not installed" list for saved work.
Admin-only. Built later from Drishti Elements only (grid, chips, cards, inputs, tabs); these files use the vendored 2.4.0 tokens and chip CSS.
Sample data: 10 modules, 3 nodes (drishti-main, b, c); capitalmarkets stale, source-swapone refused, market-data/economy warnings, trading differs on drishti-c.

| | Layout | Strong | Weak |
|---|---|---|---|
| A | One dense grid, rows expand in place; nodes and install below | Everything in one scan; fewest clicks; best for 10-30 modules; one grid component | Per-node placement only as text; the cluster story is at the bottom |
| B | Master-detail: list left, detail with tabs right | Room for depth per module (parts x nodes, problems, install) | Two panes; the overview of all modules is narrow; more clicks to compare; weakest on a phone |
| C | Cluster-first: counts, nodes x modules matrix (cell = content hash booted), then detail | A disagreeing node or stale module is visible at once; matches the fingerprint model (content hash is the identity) | A matrix gets wide with many nodes (scrolls sideways); less room for per-module text |

Files: `A.html`/`A.png`/`A-phone.png`, `B...`, `C...` (PNG 1440x900 and 390 wide).

**Recommendation: C.** After i7a the question an admin asks is "did every node boot the same modules, and is anything stale or refused?", and C answers it first, in one
table, while still carrying the per-module detail and the install commands. If a plain list is preferred, A is the cheapest to build (one grid).
Build checklist for I8b: the matrix and list are one grid; chips for origin/problem; no bespoke widget; the switch is the existing per-module setting.

### BI depth: five sets of mock-ups

**The request.** The screens for the BI-depth release: a decomposition tree that can break down VaR, a Playground for Drishti Elements,
linked brushing, the first new visuals, and modelling of many-to-many and both-ways relationships. The gaps they close are in
[BI_AND_POWER_BI.md](../guides/bi/README.md).

Each HTML file is self-contained (system fonts, no CDN). The caption bar switches option, state and theme; the address keeps the choice
(`#B-error-light`). The interactions work, in plain JavaScript written for these mock-ups: the figures are synthetic, but VaR is really
computed for each selection from generated scenario P&L (509 scenarios). The real screens are built from Drishti Elements, never from this code.

#### 1. Decomposition tree: [decomposition-tree.html](../lenses/mockups/bi-depth/decomposition-tree.html)

| A · Tree | A · Notional | B · Ladder | C · Waterfall |
|---|---|---|---|
| ![](../lenses/mockups/bi-depth/decomposition-tree-A-var.png) | ![](../lenses/mockups/bi-depth/decomposition-tree-A-sum.png) | ![](../lenses/mockups/bi-depth/decomposition-tree-B-var.png) | ![](../lenses/mockups/bi-depth/decomposition-tree-C-var.png) |

Light: ![](../lenses/mockups/bi-depth/decomposition-tree-A-var-light.png)

**Non-additive splits.** Every box is its own number, recomputed for that slice. A VaR split states *Σ standalone − diversification = parent*
beside the column; a plain sum says the children add exactly.

- **A · Tree.** Boxes left to right, one open at each level, the next split chosen by a list at the column head. *For:* the shape Power BI
  users know; the path is visible at once; diversification sits on each split. *Against:* wide after four levels; horizontal scrolling.
- **B · Ladder.** Indented lists of levels, down the page. *For:* the best fit on a narrow panel; every level has its share column. *Against:*
  reads as a report, the exploring feel is weaker.
- **C · Waterfall.** One split at a time: standalone bars, the green diversification step, the true figure. *For:* the clearest statement of
  the benefit, and it needs no explanation for a risk manager. *Against:* only one level is on screen.

**Recommendation: A**, with C as the view a node opens ("explain this split") in a later release. The diversification line in A is what no
other tool has.

**Decide:** whether to show Euler contribution (component VaR) beside standalone. It adds up exactly, but it needs the risk engine's vectors.

#### 2. Elements Playground: [elements-playground.html](../lenses/mockups/bi-depth/elements-playground.html)

| A · One column | A · Error | B · Workbench | B · Error |
|---|---|---|---|
| ![](../lenses/mockups/bi-depth/elements-playground-A-ok.png) | ![](../lenses/mockups/bi-depth/elements-playground-A-error.png) | ![](../lenses/mockups/bi-depth/elements-playground-B-ok.png) | ![](../lenses/mockups/bi-depth/elements-playground-B-error.png) |

Light: ![](../lenses/mockups/bi-depth/elements-playground-A-ok-light.png)

Both have the live visual, an editable data table, an options form, and formatted, editable HTML and JavaScript; every edit redraws after a
short pause; Reset restores the example. The frame is `sandbox="allow-scripts"` with no same-origin and a content policy that blocks the
network. **An error** names the message and the line, marks the line in the gutter, offers *Go to the line*, and keeps the last good drawing
greyed out above it. A new frame is built for each edit and swapped in only if it ran cleanly.

- **A · One column** (like a D3 reference page). *For:* reads as documentation, then an experiment; the page links well. *Against:* the code is
  below the fold on a laptop.
- **B · Workbench** (code left, visual right). *For:* code and result side by side while editing. *Against:* no room for the prose, weak as reference.

**Recommendation: A.** It is what was asked for, and it doubles as the element's reference page.

#### 3. Linked brushing: [linked-brushing.html](../lenses/mockups/bi-depth/linked-brushing.html)

| A · Filter | B · Highlight | C · Crossfilter | Cleared |
|---|---|---|---|
| ![](../lenses/mockups/bi-depth/linked-brushing-A-brushed.png) | ![](../lenses/mockups/bi-depth/linked-brushing-B-brushed.png) | ![](../lenses/mockups/bi-depth/linked-brushing-C-brushed.png) | ![](../lenses/mockups/bi-depth/linked-brushing-A-empty.png) |

Light: ![](../lenses/mockups/bi-depth/linked-brushing-A-brushed-light.png)

Drag across the time chart, the histogram or the scatter; click bars. Clearing: the chip's ✕, *Clear all*, Esc, or a click on empty chart area.
VaR in the tiles and the bars is recomputed for the selection, never re-added.

- **A · Filter.** One selection; the other charts filter to it. *For:* one engine query per brush, easy to read. *Against:* the context goes.
- **B · Highlight.** Same, with the unfiltered figures ghosted behind. *For:* keeps proportion. *Against:* two queries per chart, and costly for VaR.
- **C · Crossfilter.** Several brushes at once, each chart ignoring only its own. *For:* the analyst's tool. *Against:* more to explain, more queries.

**Recommendation: A** as the default for every chart, with C as a report setting.

#### 4. First new visuals: [new-visuals.html](../lenses/mockups/bi-depth/new-visuals.html)

| A · Sunburst | A · Icicle | A · Calendar | B · Board |
|---|---|---|---|
| ![](../lenses/mockups/bi-depth/new-visuals-A-sunburst.png) | ![](../lenses/mockups/bi-depth/new-visuals-A-icicle.png) | ![](../lenses/mockups/bi-depth/new-visuals-A-calendar.png) | ![](../lenses/mockups/bi-depth/new-visuals-B-sunburst.png) |

Light: ![](../lenses/mockups/bi-depth/new-visuals-A-sunburst-light.png)

Segment size is **notional**, because it adds up; **VaR use of limit is colour**, read per node, because VaR does not add. Click to zoom,
the centre or the path to go back. The calendar shows limit breaches or backtesting exceptions per day, with non-business days hollow.

- **A · One visual at a time,** with a detail panel. *For:* the size needed for labels; simple. *Against:* no comparison.
- **B · Board.** The three linked: the sunburst sets the scope of the icicle and the calendar. *For:* a daily risk view. *Against:* the sunburst labels are tight.

**Recommendation: A** for the elements themselves. B then needs no new element: it is a report that places three of them.

#### 5. Modelling: [modelling.html](../lenses/mockups/bi-depth/modelling.html)

| A · Bridge | A · Both ways | A · Refused | B · Bridge | B · Refused |
|---|---|---|---|---|
| ![](../lenses/mockups/bi-depth/modelling-A-bridge.png) | ![](../lenses/mockups/bi-depth/modelling-A-direction.png) | ![](../lenses/mockups/bi-depth/modelling-A-refused.png) | ![](../lenses/mockups/bi-depth/modelling-B-bridge.png) | ![](../lenses/mockups/bi-depth/modelling-B-refused.png) |

Light: ![](../lenses/mockups/bi-depth/modelling-A-bridge-light.png)

**The refusal** names the two paths in words and draws them in red; the model is not saved, and each fix is one button. The bridge form shows why group rows do not add to the total.

- **A · Canvas and inspector.** *For:* the paths are visible, which is the whole subject. *Against:* a new graph element; a model of forty tables gets messy.
- **B · Table first.** A grid of relationships, the same forms, a diagram below. *For:* scales; built from the grid and inputs that exist. *Against:* the paths are one step away.

**Recommendation: B**, drawing the diagram on the refusal and on request. The new graph element then has one small job.

#### Build checklist

| Item | Library | Data | Interaction | Status |
|---|---|---|---|---|
| Decomposition tree: columns, split list, links, diversification line | `drishti-tree` | pivot engine; VaR from the engine per node | open a node, change a split | built |
| Playground page per element; sandbox with error reporting; last good drawing; reset | `drishti-inputs` (code editor), `drishti-grid` (data) | element's example | edit redraws | built |
| Brush on line, histogram, scatter; click on bars; chips; Esc | `drishti-chart`, `drishti-core` (events) | cross-filter of the report, VaR recomputed | drag, clear | built |
| Sunburst, icicle | `drishti-tree` (its modes) | hierarchy from promoted columns | zoom, path | built (reports; Sutra panels later, with the Rachana track) |
| Calendar heatmap | `drishti-chart` | breaches or exceptions per business date | pick a day | built (reports; Sutra panels later) |
| Relationship grid and forms; bridge form; path diagram | `drishti-grid`, `drishti-inputs`, `drishti-chart` | the model file | edit, save | to build |
| Refusal with paths and one-click fixes | server model check; `drishti-core` | a new error code, one per refusal | apply a fix, undo | to build |

The language these screens rest on is designed in [ARCHITECTURE.md section 28.4](ARCHITECTURE.md#284-the-expression-language-del-and-its-checks).

### Pivot fields: three mock-ups

**The problem.** The MR cube pivot (`/pivot?preset=mr&text=POS:POS-BOOK-CREDIT-1~CLY-4001063`) lists about 45 field chips in
four rows. Every hierarchy level appears as its own field (`MGMT.DESK`, `LEGAL.BOOK`, `LL.LIMIT-LETTER`, …). So does every raw column
those levels are built from (`DIVISION`, `TT_BOOK`, `LIMIT_LETTER`, …). The measures sit among them as wide chips. Below that come the
four wells, a scan banner and a long title. The grid gets about a quarter of the screen, with truncated labels and a
`Σ-total VaR 0.00` line. A trader will not use it.

**What the mock-ups change.** They follow Power BI's idea of a field list: grouped, searchable and collapsible, with hierarchies as one
item and measures apart. They also follow the Bloomberg bar: dense but calm, keyboard first, and a default that answers at once.

Each mock-up is self-contained (system fonts, no CDN). Open it in a browser. The address picks the state: `#desk` or `#pos`, `open`,
`collapsed` (A only) and `light`. The caption bar at the top switches between them. All three use the same data. The first scope is
the owner's screenshot (`POS:POS-BOOK-CREDIT-1~CLY-4001063`, VaR 10.60 m). The second is `DESK:DESK-RATES`, a 17-row tree with seven
books, nine positions, a `(Blank)` book and the diversification line. The figures are made up for the illustration.

| Option | File | Closed | Open | Light, the screenshot's scope |
|---|---|---|---|---|
| A · Power BI panes | [A-powerbi-panes.html](../lenses/mockups/pivot-fields/A-powerbi-panes.html) | ![](../lenses/mockups/pivot-fields/A-closed-desk.png) | ![](../lenses/mockups/pivot-fields/A-open-desk.png) | ![](../lenses/mockups/pivot-fields/A-open-pos-light.png) |
| B · Trader view first | [B-trader-view.html](../lenses/mockups/pivot-fields/B-trader-view.html) | ![](../lenses/mockups/pivot-fields/B-closed-desk.png) | ![](../lenses/mockups/pivot-fields/B-open-desk.png) | ![](../lenses/mockups/pivot-fields/B-closed-pos-light.png) |
| C · Saved views + B | [C-saved-views.html](../lenses/mockups/pivot-fields/C-saved-views.html) | ![](../lenses/mockups/pivot-fields/C-closed-desk.png) | ![](../lenses/mockups/pivot-fields/C-open-desk.png) | ![](../lenses/mockups/pivot-fields/C-closed-pos-light.png) |

#### What is common to all three (the cleanups)

- **A hierarchy is one field.** `MGMT ▸ Division › Desk › Book` is a single item: drag it to get drill-down, or open it to choose
  levels. A level above the view's scope (Division under `DESK:DESK-RATES`) is shown greyed as *above the scope*. `Positions ▸ Position › Trade` is the
  members' drill.
- **Hierarchy-level columns are hidden.** Hierarchies are built from promoted columns, so `DIVISION`, `DESK`, `TT_BOOK`,
  `LEGAL_ENTITY` and `LIMIT_LETTER` would only duplicate them. They go under **More fields** with the rest of the long tail (14
  columns, collapsed). This is Power BI's "hide in report view", applied automatically.
- **Measures are grouped apart** with a Σ icon, in display folders: VaR (VaR, ES, Parametric, Stressed), Contribution (Comp VaR, Comp ES,
  Incr VaR), Limits (Limit, Limit use), Sensitivities (DV01, CS01, SBM capital) and Book (PV, Trades).
- **The grid:** full row labels, indented, with a tooltip giving the scope, and a row-header column wide enough for
  `POS-BOOK-RATES-3~CORP-BOND-ALLY-4.25-2031`. Every figure is in one format (`10.60 m`, USD m stated once in the footer). An empty
  cell is blank, never 0.00. There is no `Σ-total VaR 0.00` line. Instead, a **Diversification (Σ books − desk)** row appears only
  where it means something. `(Blank)` marks an empty level, in muted italics, with a **?** that explains it.
- **The header:** the title is shortened to `MR cube · DESK-RATES 2026-11-06 · 1d 99% · USD`. The headline figures (VaR, ES, Limit
  use, the change since the previous day, Trades) share that line, so no tiles are cut off. The scan banner moves into the **i** icon.
- **A keyboard line** sits under the grid, and every picker has its keys.

#### The three options

**A · Power BI panes.** The grid fills about 70% on the left. On the right are **Build** (the four wells as short vertical lists;
Filters split into *on this view* and *on every view of the MR cube*) and **Fields** (search with `/`, then Hierarchies, Trade &
position, Measures, More fields). Drill arrows sit on the grid's corner (↑ up, ↓ drill mode, ⇊ next level, ⤓ expand all). Both panes
collapse to a 28 px rail (`\`). The open screenshot shows LEGAL being dragged to Rows.
*For:* everything is visible and familiar to Power BI and Excel users. *Against:* even tidied, it is an authoring screen. A trader who
only reads the cube still sees about 40 names, and the grid loses about 30% of the width while the panes are open.

**B · Trader view first.** There is no field list on screen. One compact line holds
`Rows [⋔ MGMT ▾ Desk › Book ▲▼] [› Position › Trade] · Columns [Risk class] · Filters [Trade status = LIVE] [+] · Values` and then a
measure toggle strip. The hierarchy is one chip with a level stepper (`[` `]`). Each chip opens a picker, grouped like A's tree, with
level check-boxes and search. `R`, `C` and `F` open the pickers, and `M` jumps to the measures. **Customize ›** (`\`) opens A's panes.
I merged the brief's `Values [VaR ▾ ES ▾]` with the toggle row: the lit toggles *are* the Values well, in order, and ▾ on a lit toggle
sets its options. That saves a line. The grid gets about 85% of the screen at 1600×960, including the console bar.
*For:* calm and fast, and the screen is the answer. *Against:* a field is not visible until you open a picker, and new users must
learn the chips.

**C · Saved views + B.** B plus a **View** picker as the first control (`Shift+V`). It offers ready-made layouts for this cube (By desk,
By book, By limit letter, By risk class × desk, Top contributors, Trades in my scope; keys `1`–`6`), then *My views* and *Shared with
me*, and **Save view as…** (Personal or Shared, `Ctrl+S`). The closed screenshot shows *By book*, which adds Limit use with bars to
the total. The open screenshot shows the picker.
*For:* the trader's first click answers the question, and the desk head can share a morning view. *Against:* the ready-made list has
to be curated for each preset. A changed layout must show that it differs from its view (a dot, plus Save or Revert).

#### Recommendation

**Make B + C the default. They are one screen: C is B with the View picker in front. Put A behind Customize ›.** The one exception is
the BI designer, which is an authoring tool and should open with A's panes.

- Traders read the cube; they rarely build it. C's ready-made views plus B's line cover the daily questions with no field list at all.
  Power BI makes the same split: report readers never see the Fields pane, only authors in edit mode do.
- A is still needed: by power users, by anyone building a new view, and by the BI designer. Customize › opens it from B or C,
  and `\` closes it again. A's cleanups (one hierarchy item, hidden level columns, measure folders) are also what B's pickers show,
  so both read one catalogue.

#### What the choice means, since the pivot is a shared Elements component

The field list and wells are **drishti-grid**'s `WellsModel` (`drishti_wells.js`) drawn by `drishti_grid-wells-ui.js`. Its own header
already says *"the ribbon above the grid, or the side tool panel: the same model"*. So B's line is the ribbon, A is the side tool
panel, and C adds a view picker over both. Nothing is bespoke to the MR cube (see the no-bespoke-UI rule). The change applies to
every pivot: Views → Pivot, the MR cube, the CCR cube when it is built, the panel pivots (Pivot under a Sutra table), search-result
pivots and the BI visuals on the pivot engine. In practice:

1. **The catalogue grows metadata.** `GET /api/v1/pivot/catalogue` gains, for each field: its *group* (hierarchy, field, measure),
   *display folder*, *hidden*, and for a hierarchy its *levels*. A hierarchy's level columns are hidden automatically. A source or
   pack can set folders and hidden fields in YAML. Each source supplies this data (VaR, tabular/BI layers, panel), and the
   component draws it the same way for all of them.
2. **The default mode is chosen per host, not per screen.** A preset or Sutra `pivot:` block says `mode: views` (C), `compact` (B) or `panes`
   (A). Defaults: risk presets and panel pivots use C, the generic Views → Pivot uses B, and the BI designer uses A. A person's choice
   between closed and open panes is kept.
3. **Ready-made views are configuration.** The preset (`drishti-console/config/pivot-presets.yaml`) gets a `views:` list, and the
   existing saved and shared views ([PIVOT_GUIDE §11](../guides/bi/PIVOT.md#11-saved-and-shared-views-compare)) feed *My
   views* and *Shared with me*. There is no new store.
4. **The level stepper and drill arrows** belong to drishti-grid's pivot body (next to `1`–`9` and `Shift+→`), and the picker is
   drishti-inputs (combobox and tree). Grid formats (`10.60 m`, blanks, `(Blank)`) belong to drishti-core's number formats, so every
   pivot gets them.
5. **Existing keys stay as they are:** `R C V F` in A, `→ ← 1–9 Enter G X E`. In B and C, `R C F` open pickers instead of placing a
   selected field, because there is no field list to select from. `M` opens the measures. `[` `]` drill, `Shift+V` opens views and
   `\` opens Customize.
6. **Tests and docs:** the pivot browser tests (`test_pivot_screen_browser.py`, `test_pivot_expand_browser.py`) cover all three modes.
   PIVOT_GUIDE §2 (the screen) and §29, the pivot tutorial, and its screenshots are redone with the build.

#### Decision and build checklist

**Decided (owner, 2026-11-08): C + B is the default everywhere a pivot opens, A behind Customize ›; the BI designer keeps its panes.** The
build checklist, ticked as built, lives in the lens document:
[LENSES.md section 6.4.4](../plans/LENSES_PROPOSALS.md#644-pivot-fields-the-view-picker-the-compact-line-and-the-panes).

### Upload a file and build its `.ready`: three mock-ups

**The request.** An Admin page that takes a data file (and optionally a `.ready`), builds the `.ready` from a form so nobody edits it by
hand, shows the exact text, and pushes both to the data inbox.

Each mock-up is self-contained (system fonts, no CDN). Open it in a browser. The caption bar switches option, state (empty, uploading,
filled, refused, pushed), file (a `.jsonl.gz` or a tar) and theme; the address keeps the choice (`#refused-tar-light`). The form really
drives the `.ready` text and the checks: change the mode, pick a pack, press Copy. The example loads position-grain scenario PVs for
2026-11-06 from ENDUR into `risklake`. The figures are made up.

| Option | File | Filled | A refusal blocking Push | Tar, light |
|---|---|---|---|---|
| A · Step wizard | [A-step-wizard.html](../lenses/mockups/ready-upload/A-step-wizard.html) | ![](../lenses/mockups/ready-upload/A-filled.png) | ![](../lenses/mockups/ready-upload/A-refused.png) | ![](../lenses/mockups/ready-upload/A-tar-light.png) |
| B · One page, split view | [B-split-view.html](../lenses/mockups/ready-upload/B-split-view.html) | ![](../lenses/mockups/ready-upload/B-filled.png) | ![](../lenses/mockups/ready-upload/B-refused.png) | ![](../lenses/mockups/ready-upload/B-tar-light.png) |
| C · Inbox first | [C-inbox-first.html](../lenses/mockups/ready-upload/C-inbox-first.html) | ![](../lenses/mockups/ready-upload/C-filled.png) | ![](../lenses/mockups/ready-upload/C-refused.png) | ![](../lenses/mockups/ready-upload/C-tar-light.png) |

#### What all three show

- **The file:** upload (drag and drop, progress for a large file) or pick one already in the inbox folder, which is the advice for very
  large files. Accepted: `.jsonl`, `.gz`, `.zip`, `.tar`, `.tar.gz`. A `.ready` you already have can be uploaded to fill the form.
- **The form,** one field per key of [the `.ready` file](../guides/DATA_LAKE_GUIDE.md#4-the-ready-file-every-key), a **?** on each, and
  a note saying where each value came from: *from the file name*, *from the contract*, *computed*, *you set this*.
- **A tar** has no pack, kind, table or business date to choose: the date field is read-only and says why, and a Folders table maps each
  top folder to its pack.
- **The checks,** as pass, warning or refused, in plain words with the fix. A refusal disables Push; *Use replace* shows a fix applied in
  one click.
- **The exact `.ready` text,** read-only with syntax colouring, Copy and Download.
- **Push:** data first, `.ready` last, then a confirmation with the next cycle and the way to the Inbox board.

#### The three options

**A · Step wizard** (File → Details → Check → Review `.ready` → Push). Check is a gate: a refusal stops you at step 3.
*For:* the clearest for someone who does this rarely; one thing at a time. *Against:* the `.ready` appears only at step 4, so the link
between a field and its line is lost; four clicks for a daily job; fixing a refusal means going back.

**B · One page, split view.** The form on the left, the live `.ready` on the right, the checks underneath, Push held at the bottom of the
window. *For:* the request's point on one screen: change a field and see the line change; the refusal and its field are on the same
page; fast for a daily user, and easy to read top to bottom. *Against:* a long page, the checks sit below the form at 1600×960, and a
first-time user gets every field at once (the pre-filled values soften this).

**C · Inbox first.** A drop band on the existing Inbox tab; a drawer opens with the form, checks and preview, and after the push the file
is simply there in Waiting. *For:* no new tab, the shortest path to the result, and *Build its .ready* on a file waiting without a marker is
the natural way in for very large files. *Against:* a 660 px drawer stacks everything, so the form and the `.ready` are not side by side
and a refusal needs scrolling; the board behind is dimmed while you work.

#### Recommendation

**Build B as the screen (Data loads → Upload), and take from C its two ways in, opening B already filled in: dropping a file on the Inbox
board, and *Build its .ready* on a Waiting card with no marker. Leave A out unless wanted.**

- Seeing the `.ready` form as you type is what replaces hand-editing; only B keeps both in view.
- C's entry points cost little and fit how large files really arrive (copied to the folder first). Its drawer would be a second layout
  of the same form for no gain.
- **This departs from the general rule (A, B, C as views of one screen, A the default)**, as the data inbox itself did. If the rule should
  hold, all three are views over one form, one checks list and one preview, and I would still ask for B as the default rather than A.

#### Things the build must get right

These came out of reading the code and the guide; the mock-ups simplify them.

1. **DRS-5210 belongs to the VaR intake, not the loader.** The loader would take the file; the date is refused later, in `VarIntake`. The
   check must call that same rule, for the system and date in the file, or the page promises something the load does not do.
2. **`Use replace` is only sometimes the right fix.** Replace clears the date in *this table*. If the other grain arrived through another
   table (P&L vectors), replace does not remove it, so the server decides which fix to offer.
3. **Replace clears the date for every system in the table.** The mock-up's date holds only ENDUR rows. If it also holds another system's rows,
   the check must be a warning that names them and their count, because a single-system file would remove them.
4. **The checks read a sample.** Say so on the screen. SHA-256 and rows are the whole file: on a very large gz that is minutes of
   server work, so it is a job with progress, not a request.
5. **A file of that name already waiting** must not be overwritten: refuse with the name, as a check.
6. **The data inbox can be off**, and the upload then has nowhere to go: Push is disabled with the link to Admin → Engines, as the board does.

#### Build checklist

Built: **B**, with C's two ways in; A and C were not built.

| Area | Item | Status |
|---|---|---|
| Server | `POST /api/v1/admin/dataload/upload`: streams the raw body into `<dataload>/staging/`, never into memory; free-space floor; size limit in `config/engines/dataload.yaml` | built |
| Server | `POST /api/v1/admin/dataload/inspect`: name, tar layout, SHA-256 and rows as a job with progress; for an upload or a file in `inbox/` (names only, no paths, no links) | built |
| Server | `POST /api/v1/admin/dataload/check`: the form's values against a sample; returns the pass, warning and refused list in words with the fix | built |
| Server | `POST /api/v1/admin/dataload/push`: rename the data file into `inbox/`, then write the `.ready` the same way, last; refuses when a check refuses, re-checked on the server | built |
| Server | `GET /api/v1/admin/dataload/choices`: lakes, packs, kinds, connectors, each kind's key from its contract, files in `inbox/` with no marker | built |
| Reuse | `ReadyNote` for the keys and rules (today it reads a `Path`: add parsing from text); one writer beside it, tested by reading its output back; `READY_KEYS` in `tools/lake/feedroute.py` stays compared | built |
| Reuse | `Routes` for what a name routes to, the contract validator for the sample, `VarIntake`'s rule for DRS-5210 | built |
| Reuse | The streaming of `/api/lakes/upload` in `drishti-console/routes/lake_routes.py`, passed on to the server rather than kept in the console's temporary folder | built |
| Security | Administrator only; add the new endpoints to the `loads:write` list in `application.yaml` for API tokens; file names validated (no `..`, no leading dot, accepted extensions only) | built |
| Audit | One audit line per push: who, file, SHA-256, rows, the `.ready` text; a refusal overridden is impossible, so none to record | built |
| Cluster | Every call goes to the owner node (`owner: true`), which holds the inbox folder; another node forwards; the page names the owner node | built |
| CLI | `drishti.py dataload ready <file>` in `tools/dataloadcli.py`: same inspect and check, prints the `.ready`, `--push` to push; shares the endpoints | built |
| Console | Tab *Upload* in `_loads_tabs.html`; *Build its .ready* on a no-marker card in `_inbox_body.html`; dropping on the board opens Upload | built |
| Elements | No bespoke UI: `Form`, pickers and read-only `CodeEditor` from drishti-inputs. **A file-drop input with progress does not exist yet:** add it to drishti-inputs, with its gallery page | built |
| Docs | DATA_LAKE_GUIDE section 3.5 (the page), one tutorial with UI, CLI and YAML (screenshots deferred, owner), `CONFIGURATION.md` for the new settings, CHANGELOG | built |
| Tests | Writer and `ReadyNote` round trip for every key; each check's words; refusal blocks push on the server; data before `.ready` (crash between them leaves no marker); name collision; path traversal; tar with an unresolved folder; `loads:write` and non-admin refused; owner-node forwarding; one browser test per state, dark and light | built |

### The live blotter: mock-ups

Three options for the live, editable blotter (the trading module's trade blotter is the first declaration; the same screen serves every
blotter a module declares). Each is a self-contained HTML file with the theme tokens inlined, no CDN; `#light` shows the light theme.
PNGs at 1440 × 900, dark and light, from headless Chrome.

| Option | File | In one line |
|---|---|---|
| A | `blotter-A-terminal-grid.html` | the dense terminal grid: every editable cell edits in place; refusals as a toast |
| B | `blotter-B-grid-and-panel.html` | the grid plus the record's panel: editable fields, reason, four-eyes state, version history, links to Explain, VaR and limits |
| C | `blotter-C-grouped-stream.html` | grouped by desk and book with live subtotals, an event ticker and the tray of my changes (the outbox) |

**Recommended: B**, with C's *My changes* tray and grouping as grid options (grouping is a grid feature in every option): quick edits stay
in the cells, while a change that needs a reason or an approver is reviewed in the panel, where the version and who changed the record
last are in view before anything is sent.

### Include or exclude matured items on the VaR explorer: two mock-ups

**The request.** A control on the VaR explorer that adds matured items back into the figure, at the grain of the input: positions for
`GRAIN=POSITION`, trades for `GRAIN=TRADE`. Off by default.

Each mock-up is self-contained (system fonts, no CDN). Open it in a browser. The caption bar switches the control, the input grain and the
theme; the address keeps the choice (`#on-trade-light`). The figures are made up.

| Option | File | Off | On, positions | On, trades | On, light |
|---|---|---|---|---|---|
| A · Header switch | [A-header-switch.html](../lenses/mockups/var-matured/A-header-switch.html) | ![](../lenses/mockups/var-matured/A-off.png) | ![](../lenses/mockups/var-matured/A-on-position.png) | ![](../lenses/mockups/var-matured/A-on-trade.png) | ![](../lenses/mockups/var-matured/A-on-light.png) |
| B · Scope compare | [B-scope-compare.html](../lenses/mockups/var-matured/B-scope-compare.html) | ![](../lenses/mockups/var-matured/B-off.png) | ![](../lenses/mockups/var-matured/B-on-position.png) | ![](../lenses/mockups/var-matured/B-on-trade.png) | ![](../lenses/mockups/var-matured/B-on-light.png) |

#### What both show

- **The control names the grain:** "matured positions" or "matured trades", from the input. It is off whenever the page opens.
- **When on, the figure says so:** an INCLUDES MATURED tag in the header, the first words of the commentary, and each export (Excel title and
  Scope column, PDF banner, CSV `SCOPE` column).
- **What ignores it:** the official record, captures and sign-off, limit use and breaches. Limit use stays on the official figure in both.
- **A matured trade inside a position** is flagged only. A position's PVs arrive whole, so it stays in the figure either way.

#### The two options

**A · Header switch.** One switch beside Export; on, a band with *Back to official*.
*For:* least on the screen, one click, the page looks as it does today while off. *Against:* the size of the difference is visible only once on.

**B · Scope compare.** Official or With matured as two segments, the official figure, the other figure and the difference always shown, and a
list of the matured items. *For:* the official figure is never out of sight, and the list answers "what was added". *Against:* a permanent
card and a second VaR figure on the page, even for people who never use it, and the second figure must be computed on every open.

#### Recommendation

**A as the default,** with B's list of matured items opened from the band when the control is on. A keeps the explorer as it is for the people
who never need this and computes the second figure only on request; the band already states the official figure and the difference.
