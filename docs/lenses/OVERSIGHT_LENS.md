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
# Market risk oversight: what the second line needs from Drishti, and a Drishti Oversight lens

**Status: design, not built.** An analysis and a proposal; nothing in sections 4 to 6 is built. The rows it proposes are candidates in
the [implementation plan](../architecture/IMPLEMENTATION_PLAN.md#risk-oversight-candidates-not-started), not started. Every "built" or "partly" statement
cites the document and section it was read from on 2026-10-31; where the plan names a row, the row is cited.

**The request (product owner, 2026-10-31):** "please write detailed document"; "do you think a lens for risk oversight will be a good
idea?" The short answer is in [section 5.1](#51-the-answer-yes-a-separate-lens-built-after-two-features-exist): **yes, a separate lens**,
built after the two features that give it something of its own to show (the VaR change explanation and the breach workflow).

**Read with:** [VAR_GUIDE.md](../guides/VAR_GUIDE.md) (the VaR engine as built), [REGULATORY_VAR_REPORTING.md](../guides/REGULATORY_VAR_REPORTING.md)
(backtesting, the P&L feed and the report framework with sign-off; the report packs), [EDITIONS.md](../admin/EDITIONS.md#13-the-design) (how an edition or a lens
is defined), [COMPUTED_ATTRIBUTES.md](../architecture/COMPUTED_ATTRIBUTES.md) (compute, do not store), [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md),
and the contracts [STR.md](../contracts/STR.md) (stress and the proposed limit table), [FRTB.md](../contracts/FRTB.md) (sensitivities) and
[PNL.md](../contracts/PNL.md) (P&L explain). The proposed risk cubes, which adopt section 7's `position-sensitivity` contract and
would serve this lens as its pivot screen: see the design in [MR_CUBE_LENS.md](MR_CUBE_LENS.md) and [CCR_CUBE_LENS.md](CCR_CUBE_LENS.md).

## Contents

1. [Who market risk oversight is](#1-who-market-risk-oversight-is)
2. [Their questions: a day, a month and a year](#2-their-questions-a-day-a-month-and-a-year)
3. [What they need, area by area, and Drishti's status](#3-what-they-need-area-by-area-and-drishtis-status)
4. [Design sketches for the gaps](#4-design-sketches-for-the-gaps)
5. [The Drishti Oversight lens: recommendation and design](#5-the-drishti-oversight-lens-recommendation-and-design)
6. [A roadmap](#6-a-roadmap)
7. [Sensitivities data](#7-sensitivities-data)
8. [Data the bank must supply](#8-data-the-bank-must-supply)
9. [Decisions for the product owner](#9-decisions-for-the-product-owner)
10. [Glossary](#10-glossary)
11. [Mock-ups: the screens to build](#11-mock-ups-the-screens-to-build)

**Status words.** **Built**: on develop, documented as built. **Partly**: some of it is built; the cell says what is missing.
**Planned (row)**: a row of the implementation plan names it. **Not built**: neither built nor planned. **Branch**: built on a branch
that is not yet on develop (none now: the scenario matrix, a branch when this was written, is built).

---

## 1. Who market risk oversight is

The **second line of defence** for market risk: the market risk management function, reporting to the **chief risk officer (CRO)**,
independent of the trading desks (the first line) and audited by internal audit (the third line). In a large bank it is a team of risk
managers, each covering a business (rates, credit, FX, equities, commodities), with a central team for the limit framework,
methodology, reporting and the regulators.

| Accountable for | What it means day to day | What they need from a system |
|---|---|---|
| **Risk appetite** | The board approves an appetite statement (for example: bank-wide VaR, stressed VaR and stress loss below set amounts); the CRO cascades it | The bank's measures against the appetite, every day, with the trend |
| **Limits** | A limit framework cascades the appetite: board → CRO → business heads → desks → books, through **limit letters** (the delegation instruments); VaR limits, sensitivity limits, stress limits, stop-loss, position and notional limits | Every limit of every type, its use, history and near breaches; who approved it and until when |
| **Independent challenge** | They do not take the first line's word: they recompute, decompose and question large or unexplained moves, new trades and concentrations | Drill-down from the bank to a trade; "why did it move?"; the ability to ask any set of trades |
| **Model and data trust** | VaR is a model; they own the evidence that it works (backtesting, P&L attribution), that its inputs are complete and right, and they register what it misses (risks not in VaR) | Backtests, data completeness and quality, proxies, a register of risks not in VaR |
| **Escalation** | A breach must be detected, notified, explained by the desk, approved or remedied (temporary increase, reduction), and recorded | A breach queue with commentary, approval and an audit trail |
| **Committee and board reporting** | A daily risk report; a monthly market risk committee pack; a quarterly board risk committee pack | Reproducible, signed-off reports from the numbers of record |
| **Regulators** | Supervisors (OSFI in Canada; the Federal Reserve, OCC and FDIC in the US) examine the framework, the backtests, the limit breaches and the Volcker metrics | Official records, retained, reproducible, with lineage |

**Different from the first line.** A trader or desk head asks *what does this trade do to my VaR?*; that is Drishti Trading
([EDITIONS.md section 14](../admin/EDITIONS.md#14-drishti-trading)). The second line asks *are we within appetite, is this
number right, and who has approved what?* The questions overlap; the defaults, the duties and the actions do not.

---

## 2. Their questions: a day, a month and a year

### 2.1 The morning meeting (07:30 to 09:00)

The overnight batch is in; the official end-of-day figures of yesterday are captured. The head of market risk runs a meeting with the
business risk managers.

| Question | Drishti today | Gap |
|---|---|---|
| *Are we within appetite?* Bank VaR and ES against the bank limit | **Built**: the Risk lens's desk landing tiles (VaR and ES of BANK, the backtest, limits in breach) ([EDITIONS.md section 7](../admin/EDITIONS.md#7-drishti-risks-home-three-landings)); the official record's `LIMIT` and `LIMIT_USE` per node ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) | Appetite measures other than VaR (stress loss is stored per desk, not computed) |
| *What breached?* | **Partly**: VaR limits only: the Risk map landing's "Today" feed and the limit-use heat map ([EDITIONS.md section 7](../admin/EDITIONS.md#7-drishti-risks-home-three-landings)); the Trading desk-head landing's Breaches and Near limit tiles ([EDITIONS.md section 14](../admin/EDITIONS.md#14-drishti-trading)); stress limit use per desk (derived, [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived)) | Sensitivity, stop-loss, position and notional limits; a breach as a record with status and commentary |
| *Why did VaR move?* | **Partly**: captures compare with now (VaR change, members added and removed, components that moved most, [VAR_GUIDE section 6](../guides/VAR_GUIDE.md#6-captures-and-compare)); the scenario matrix shows a node's scenarios across dates (**built**, `SMAT`, [VAR_GUIDE section 3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat)) | A decomposition that adds up exactly: positions, market, window roll, restatements, hierarchy changes ([section 3.5](#35-why-it-moved-var-change-explanation-day-over-day)) |
| *Which scenario drives it?* | **Built**: the P&L path tab, the 25 worst scenarios, the contributors on a scenario day ([VAR_GUIDE section 3.4](../guides/VAR_GUIDE.md#34-the-tabs)) | — |
| *Is the number trustworthy today?* | **Partly**: load checks and rejections in the provenance ([VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks)); late loads ([DATA_GUIDE.md › Telling Drishti new data has landed, section 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data)); market data checks (the Market lens's Quality landing, [EDITIONS.md section 13](../admin/EDITIONS.md#13-drishti-market)) | One trust card: completeness against the trades booked, proxies, failed rows, market data exceptions and their impact on VaR (planned) |
| *Did yesterday's P&L exceed VaR?* | **Partly**: the official-mode backtest with the derived hypothetical P&L ([VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting)) | Actual and hypothetical P&L from the bank (planned) |

### 2.2 Intraday

| Question | Drishti today | Gap |
|---|---|---|
| *Has a big trade moved us close to a limit?* | **Built**: intraday updates from files and Kafka change today's VaR during the day ([VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)); the pre-deal what-if ([VAR_GUIDE section 8.5](../guides/VAR_GUIDE.md#85-the-pre-deal-what-if-under-the-baskets-sheet)) | An intraday limit check that notifies (alerts on numbers watch BI and view numbers, [USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers), not the VaR engine's intraday answer) |
| *Can I see what the desk sees?* | **Built**: the same explorer, entitlements as configured ([VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)) | — |
| *A desk asks for a temporary limit increase* | **Not built** | The request, its approval and its expiry ([section 4.2](#42-ro2-breaches-and-escalation)) |

### 2.3 Month-end

| Task | Drishti today | Gap |
|---|---|---|
| **Backtesting review**: exceptions in the month, their causes, the traffic light | **Partly**: the official backtest per node, Kupiec and Christoffersen ([VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting)) | Actual and hypothetical P&L, separate counts, the multiplier (planned); an exception commentary |
| **Limit utilisation review**: which limits are never used (too loose) and which always near full | **Partly**: the official records carry `LIMIT_USE` per node and date, read in BI ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) | Other limit types; a utilisation-statistics report (average, peak, days above the threshold) |
| **RNIV**: quantify and review the risks not in VaR | **Not built** | A register ([section 4.7](#47-ro7-the-rniv-register)) |
| **Stress testing**: the monthly stress run against stress limits | **Partly**: stored stress results per desk and scenario, the bank's P&L per scenario derived ([VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived)) | The stress engine for any scope ([STR.md](../contracts/STR.md), proposed) |
| **The committee pack** | **Partly**: the BI report *Market risk: VaR* over the official table ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) | The report framework with sign-off (planned) and the board pack (planned) |

### 2.4 Annual

| Task | Drishti today | Gap |
|---|---|---|
| **Limit framework renewal**: re-size every limit to the appetite and the use | **Partly**: utilisation history of VaR limits | A limit catalogue of every type with approvals and validity ([section 4.3](#43-ro3-one-limit-catalogue-and-its-utilisation)) |
| **Stress window review**: is the stressed-VaR window still the worst for today's portfolio? | **Built**: the stress window selection, `SWIN` ([VAR_GUIDE section 18.9](../guides/VAR_GUIDE.md#189-choosing-the-stress-window)): every 250-day window of the long history, the alert, freeze and propose with maker-checker | The approval inside the report sign-off framework (REGULATORY_VAR_REPORTING section 8.1) |
| **Model validation support**: evidence for the validators and the regulator | **Partly**: every official figure reproducible from its inputs, kept seven years ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)); VaR at another window or confidence per request ([VAR_GUIDE section 18.5](../guides/VAR_GUIDE.md#185-the-scenario-window-and-the-confidences)) | Backtests on bank P&L; a P&L attribution test (REGULATORY_VAR_REPORTING G12, later); an evidence pack frozen with sign-off |
| **Risk appetite recalibration** | **Partly**: history of every official figure | Period statistics (planned) |

---

## 3. What they need, area by area, and Drishti's status

### 3.1 Risk measures

| Need | Status | Evidence or row |
|---|---|---|
| VaR 99% 1-day, historical simulation, any scope | **Built** | [VAR_GUIDE sections 1, 2, 18](../guides/VAR_GUIDE.md#2-the-measures-with-worked-numbers) |
| ES 97.5% 1-day, component ES adding up | **Built** | [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) |
| Component, incremental, marginal VaR; diversification | **Built** | [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) |
| Any confidence and window per request; the configured one official | **Built** | [VAR_GUIDE section 18.5](../guides/VAR_GUIDE.md#185-the-scenario-window-and-the-confidences) |
| Confidence chosen on screen (a picker, 99% default) | **Built** | [VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen) |
| 10-day VaR (√10 and the 10-day sum) | **Built** | [VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen) |
| Stressed VaR, 1-day and 10-day, official `SVAR` | **Built** | [VAR_GUIDE section 18.8](../guides/VAR_GUIDE.md#188-stressed-var) |
| VaR by risk type (IR, credit spread, equity, FX, commodity) | **Not built**, designed | REGULATORY_VAR_REPORTING G6, planned with the regulatory reports |
| FRTB standardised and internal models | **Not built**; FRTB SA contract proposed | [FRTB.md](../contracts/FRTB.md); REGULATORY_VAR_REPORTING G9 to G14 |
| Several hierarchies (management, legal entity, limit letters, any configured) | **Built** | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns) |
| Exposure (EPE, PFE) for counterparty limits | **Built** (credit risk's measure; oversight of market risk reads it for concentration) | [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) |

### 3.2 Limits and appetite

| Need | Status | Evidence or row |
|---|---|---|
| VaR limits per node, with currency; limit use on every answer and official record | **Built** | `var-limit` ([VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits)); `LIMIT`, `LIMIT_USE` ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) |
| VaR limits on stored portfolios | **Built** (hierarchy `PORTFOLIO` in `var-limit`) | [VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits) |
| Limit letters as a hierarchy | **Built** (`LL`: `LIMIT_LETTER > DESK`) | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns) |
| Limit use only when comparable (horizon and confidence the limit's own) | **Planned** | |
| Sensitivity limits: DV01 by tenor and currency, CS01 by rating and tenor, vega by underlying and expiry, FX delta by currency | **Not built**. Per-trade `DV01` is stored on `trade-valuation`; FRTB sensitivities are stored per book and bucket; no engine aggregates them; position sensitivities are proposed ([section 7](#7-sensitivities-data)) | [COMPUTED_ATTRIBUTES.md sections 4.1, 4.9](../architecture/COMPUTED_ATTRIBUTES.md#41-trade-valuation-tval); [FRTB.md](../contracts/FRTB.md) |
| Stop-loss (cumulative P&L, month-to-date, year-to-date) | **Not built**; needs the bank's P&L (planned) | |
| Position and notional limits | **Not built**; positions are derived with quantity and market value | [VAR_GUIDE section 4](../guides/VAR_GUIDE.md#4-positions-derived-never-delivered) |
| Stress limits | **Partly**: the stored stress result carries a positive `LIMIT` per desk; the derived per-scenario view shows each desk's limit use and breaches; the proposed `risk-limit` table | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived); [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental) |
| One catalogue of every limit (id, type, upper and lower, approver, validity) | **Not built**; proposed for stress in STR.md, needed by Volcker | [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental); REGULATORY_VAR_REPORTING G8 |
| Utilisation history | **Partly**: VaR only, from the official records; the Trading desk-head landing's five-day grid by book | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [EDITIONS.md section 14](../admin/EDITIONS.md#14-drishti-trading) |
| Near-breach thresholds | **Partly**, three different numbers: the explorer's bar amber at 75%, red at 90% ([VAR_GUIDE section 3.2](../guides/VAR_GUIDE.md#32-the-screen)); `trading.near-limit` 80% ([EDITIONS.md section 14](../admin/EDITIONS.md#14-drishti-trading)); `WARNING_AT` 0.8 per limit, proposed ([STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental)) | One threshold per limit, from the catalogue |
| Credit limits on counterparties | **Built** as read (stored `credit-limit`, PFE against it); the LIM engine is parked | [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md); [LIM.md](../contracts/LIM.md) |

### 3.3 Breaches and escalation

| Need | Status | Evidence or row |
|---|---|---|
| Detect a VaR breach (end of day) | **Built**: `LIMIT_USE` above 1 on the official record; shown on the Risk and Trading landings | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [EDITIONS.md sections 7, 14](../admin/EDITIONS.md#7-drishti-risks-home-three-landings) |
| Detect intraday | **Partly**: intraday VaR is live; nothing checks it against the limit and tells anyone | [VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes) |
| Notify (inbox, email, chat) | **Partly**: alert rules on one entity and alerts on numbers, after a load, with hysteresis and a cooldown ([USER_GUIDE.md](../guides/USER_GUIDE.md#alerts)); a person sets each one up | A breach rule per limit, set by the framework, not by each person |
| Commentary: the desk's explanation | **Partly**: comment threads anchored to the data are built ([COLLABORATION.md](../architecture/COLLABORATION.md)) | Anchored to a breach (limit, date), with a required structure (cause, action, expected duration) |
| Approval, temporary limit increases | **Not built**. The pattern exists: write-back approval with four eyes and no self-approval ([WRITE_BACK.md section 10](../guides/WRITE_BACK.md#10-approval)); the planned maker-checker sign-off is designed ([REGULATORY_VAR_REPORTING section 6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off)) | |
| The audit trail | **Partly**: the audit log records changes; captures and official records are immutable | Every breach state change as one record chain |

### 3.4 Trust in the numbers

| Need | Status | Evidence or row |
|---|---|---|
| Backtesting against actual and hypothetical P&L | **Partly** (derived hypothetical P&L only); **planned** with the bank's P&L feed and the backtesting engine | [VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting); [REGULATORY_VAR_REPORTING section 8.2.4](../guides/REGULATORY_VAR_REPORTING.md#46-backtesting) |
| P&L attribution (explain) | **Not built**; contract proposed, parked | [PNL.md](../contracts/PNL.md) |
| P&L attribution test (FRTB PLA) | **Not built** | REGULATORY_VAR_REPORTING G12 |
| Market data quality | **Built** in the Market lens (stale, gaps, discount factors and forwards, vol surfaces); impact on VaR **planned** | [EDITIONS.md section 13](../admin/EDITIONS.md#13-drishti-market) |
| Data completeness | **Partly**: late loads by expectation ([DATA_GUIDE.md › Telling Drishti new data has landed, section 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data)); rejected and failed rows counted ([VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks)); completeness against the trades booked **not checked** (the same section) | |
| Proxies | **Partly**: each P&L vector and scenario-PV row says `ok`, `proxied` or `failed` ([VAR_GUIDE section 15.2](../guides/VAR_GUIDE.md#152-pnl-vector-one-pl-vector-per-trade)) | A proxy report: the share of VaR from proxied trades, by desk, over time |
| RNIV register | **Not built** | [section 4.7](#47-ro7-the-rniv-register) |
| Model performance over time | **Partly**: exceptions, the light, Kupiec and Christoffersen per node | ES backtests, PLA, a dashboard |
| Reproducibility | **Built**: official captures with inputs' versions, batch ids, position rules, membership, result versions; seven years | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |

### 3.5 Why it moved: VaR change explanation, day over day

The most-asked question of the morning meeting, and the one where Drishti's design (trade-grain vectors, two dates in the engine,
immutable official captures with membership) gives an exact answer cheaply.

**Today: partly.** Compare with now gives the VaR change, members added and removed and the components that moved most
([VAR_GUIDE section 6](../guides/VAR_GUIDE.md#6-captures-and-compare)); the component VaRs add up to the scope's VaR every day
([VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts)), so the change in each child's component explains
the change by child exactly. What is missing is the change **by cause**: how much from trades booked or ended, from the market, from the
scenario window rolling one day, from yesterday's data being restated, from the hierarchy moving a book. The method, its data and its cost
are in [section 4.1](#41-ro1-var-change-explanation).

### 3.6 Scenario drivers

| Need | Status | Evidence or row |
|---|---|---|
| The worst scenarios, the VaR scenario, who lost what on it | **Built** | [VAR_GUIDE section 3.4](../guides/VAR_GUIDE.md#34-the-tabs) |
| A scope's scenarios across its last N dates (is this tail scenario new? did the window roll?) | **Built**: the scenario matrix `SMAT` ([VAR_GUIDE section 3.9](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat)) | |
| The stress matrix (every scenario of a set against a scope's children) | **Not built**, proposed | [STR.md section 6.5](../contracts/STR.md#65-the-matrix-and-limits) |
| The stressed window inside the date's set | **Partly**: the what-if's stressed VaR (the worst run of 250 scenario days) | [VAR_GUIDE section 8.2](../guides/VAR_GUIDE.md#82-the-what-if-the-java-engine) |

### 3.7 Concentration and liquidity

| Need | Status | Evidence or row |
|---|---|---|
| VaR of any slice (currency, product, direction, any trade column) | **Built**: scope words by a trade's attribute, rule portfolios, `WHERE:` | [VAR_GUIDE sections 5.4, 5.5](../guides/VAR_GUIDE.md#55-scope-words-by-a-trades-attribute) |
| Concentration tables: market value, notional and VaR by issuer, sector, country, currency, tenor bucket, with the top N and the share of the total | **Not built** as a view; positions carry market value, trades their attributes; issuer, sector and country need reference data on every position | [section 4.8](#48-ro8-concentration) |
| Counterparty concentration | **Built** for exposure (PFE by counterparty against its credit limit) | [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) |
| Liquidity: days to liquidate, bid-offer cost, share of market volume | **Not built**; no volume or bid-offer data in the packs | [section 4.10](#410-ro10-liquidity) |

### 3.8 Stress testing

| Need | Status | Evidence or row |
|---|---|---|
| Historical and hypothetical scenarios, defined | **Built** as stored definitions (`stress-scenario`), shocks without units | [COMPUTED_ATTRIBUTES.md section 4.7](../architecture/COMPUTED_ATTRIBUTES.md#47-stress-scenario-scn) |
| Stress P&L per desk and scenario | **Built** as stored results; the bank's P&L per scenario derived | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived) |
| Stress P&L of any scope, full revaluation and sensitivity-based; user scenarios | **Not built**, proposed | [STR.md](../contracts/STR.md), [STR.md section 3.4](../contracts/STR.md#34-user-scenarios-drishti-owned) |
| Stressed VaR | **Planned** | |
| Reverse stress (which moves lose a given amount) | **Not built** | [section 4.9](#49-ro9-stress-testing-the-engine-limits-and-reverse-stress) |
| Firm-wide exercises (a scenario run frozen, reviewed, signed off) | **Not built**; the planned report framework fits | [REGULATORY_VAR_REPORTING section 6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off) |

### 3.9 Governance and reporting

| Need | Status | Evidence or row |
|---|---|---|
| Official records of every node, append-only, result versions, seven years | **Built** | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| BI over the official table; exports, subscriptions, paginated reports | **Built** | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [REGULATORY_VAR_REPORTING section 1](../guides/REGULATORY_VAR_REPORTING.md#1-what-drishti-produces-today) |
| Certified reports (a steward vouches for a report) | **Built** | [BI_GUIDE.md › Publishing and governance: versions, review, certification, lineage, impact, environments, section 4](../guides/BI_GUIDE.md#4-certification-and-endorsement) |
| A daily risk report | **Partly**: the sample report *Market risk: VaR* | A defined daily report: appetite, limits of every type, breaches with commentary, VaR change explained, backtest, data trust |
| Report definitions, checks, freeze, lineage, maker-checker sign-off | **Planned** | [REGULATORY_VAR_REPORTING section 6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off) |
| Board and risk-committee pack (PDF, house style) | **Planned** | |
| Regulatory reports (BCAR, Pillar 3, FFIEC 102, Volcker) | **Proposed** | [REGULATORY_VAR_REPORTING section 8](../guides/REGULATORY_VAR_REPORTING.md#8-coverage-and-roadmap) |
| Who saw and did what | **Built**: entitlements, audit entries per answer and capture | [VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit) |

---

## 4. Design sketches for the gaps

Every design follows Drishti's rules ([COMPUTED_ATTRIBUTES.md section 1.2](../architecture/COMPUTED_ATTRIBUTES.md#12-the-rule-for-deciding-which)):

- **Fundamental inputs** (limits, sensitivities, P&L, volumes, RNIV amounts a quant supplies) arrive under a data contract as JSON Lines into
  the lake, one kind each.
- **Measures are computed on demand** from trade or position-grain inputs and never stored, except as an immutable capture or an official
  end-of-day record.
- **Governance records** (a breach's commentary and approval, an RNIV entry, a sign-off) are **Drishti-owned**: kept in the content store
  with versions, never edited in place, every step an audit entry. They point at numbers (a capture id, a limit id, a date); they never
  copy a number as the truth.
- **BI is the report layer**; the planned report framework (definition, checks, freeze, lineage, maker-checker sign-off) is reused for every
  report and every approval chain.

Sizes: **S** days, **M** one to three weeks, **L** more than three weeks, for one builder. The ids RO1 to
RO15 are this document's (risk oversight); the regulatory gaps keep their G ids.

| Id | Gap | Size | Depends on |
|---|---|---|---|
| RO1 | VaR change explanation | M | official captures and membership (built); two dates open in the engine (built) |
| RO2 | Breaches and escalation | M | RO3 for limits other than VaR; the planned sign-off record |
| RO3 | One limit catalogue and its utilisation | M | the limit system's extract |
| RO15 | Position sensitivities: data contract, loader, ladders ([section 7](#7-sensitivities-data)) | M | the bank's pricing system sending sensitivities per position |
| RO4 | Sensitivity aggregation and sensitivity limits | M once RO15 is in (L without it) | RO15; RO3 |
| RO5 | Stop-loss and P&L limits | S | the planned P&L feed; RO3 |
| RO6 | Position and notional limits | S | RO3 |
| RO7 | The RNIV register | S to M | the planned sign-off record |
| RO8 | Concentration | M | RO15 (issuer, sector, curve and tenor from the factors); reference data on positions for market value by issuer, sector, country |
| RO9 | Stress testing: the engine, limits and reverse stress | L | STR.md's inputs; RO3 |
| RO10 | Liquidity | M to L | volume and bid-offer data |
| RO11 | The daily risk report and the committee pack | M | the planned report framework; RO1, RO2 |
| RO12 | The Oversight lens and its landings | M | mock-ups; RO1, RO2 |
| RO13 | One data-trust card | S to M | the planned market-data impact for the market-data impact |
| RO14 | Model performance | S | the planned P&L feed, backtesting and period statistics |

### 4.1 RO1: VaR change explanation

**The question.** VaR moved from V(t−1) to V(t) for a node. Split the change into causes that **add up exactly** to it, and show who
drove each cause.

**Why it is not trivial.** VaR is a quantile, not a sum: the effect of new trades depends on which scenarios are in the tail, which the
market move also changes. Any split into causes depends on the order in which the causes are applied. The method below makes that
explicit: a **fixed, configured sequence of states**, each a full VaR computation; each step's effect is the difference between two
consecutive states, so the steps add up exactly to the total by construction (a telescoping sum), and the order is stated on the
screen.

**The states**, for a node N (and, the same way, for any scope the engine answers):

| State | Trades | Vectors | Scenario dates | Hierarchy, rules |
|---|---|---|---|---|
| S0 | yesterday's members of N (official membership record) | yesterday's, as captured (the official capture keeps the vector) | yesterday's set | yesterday's |
| S1 | the same | yesterday's, **latest result version** (a restated date) | yesterday's | yesterday's |
| S2 | yesterday's trades of the books that are in N **today** | yesterday's, latest | yesterday's | **today's** (a book moved between desks, a new limit letter, changed position rules) |
| S3 | S2's trades **still live today** (drops matured, cancelled, closed) | yesterday's | yesterday's | today's |
| S4 | the same | yesterday's | the dates common to both sets (yesterday's set less its oldest day) | today's |
| S5 | the same | **today's** | the common dates | today's |
| S6 | the same | today's | today's set (the newest day added) | today's |
| S7 | **today's members** of N (new trades in; amendments are trades in S3 to S6 whose terms changed, separated as below) | today's | today's | today's |

| Step | Effect | Reads as |
|---|---|---|
| S1 − S0 | **Restatement** | yesterday's data was corrected after the official run |
| S2 − S1 | **Hierarchy and rules** | the node's membership changed with no trade changing |
| S3 − S2 | **Trades out** | trades that matured, were cancelled or closed |
| S4 − S3 | **Oldest scenario dropped** | the window rolled: one scenario day left |
| S5 − S4 | **Market** | today's revaluation of the same trades on the same scenario days: market levels, time decay, sensitivities |
| S6 − S5 | **Newest scenario added** | the window rolled: yesterday's move entered the set |
| S7 − S6 | **Trades in** | new trades; with **amendments** split out as their own step when the trade feed marks them (the amended trades moved from S3 to S7's side: their old vector out, their new in) |
| Σ | V(t) − V(t−1) | exactly |

The two window steps add to one **window roll** line when the bank prefers four lines. Where a date's scenario set is replaced rather
than rolled (a new set id, a window change), S4 to S6 become one **scenario set change** step. The limit's own change is **not** a VaR step;
the limit-use line beside it shows `limit(t) − limit(t−1)` from `var-limit` by business date.

**Who drove each step.** Each step names its top contributors: for trades out and in, the incremental VaR of each trade or position (the
scope's VaR with and without it, exact); for the market step, the change in each child's component VaR between S4 and S5 (components add
up to VaR in each state, [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts)); for the window steps,
the scenario day that left or entered and whether it was in the tail; for every state, the VaR scenario's date, so *"the VaR scenario
changed from 2025-12-24 to 2026-01-02"* is said in words. A second, order-free view is offered beside it: the change of each child's
component VaR, which also adds up exactly.

**Order dependence, said.** The sequence is a setting (`drishti.var.explain.order`, built); the default is the table above
(restatement first, because it corrects yesterday; trades last, because the desk decides them). An optional **Shapley** variant averages
each cause's effect over every order of the causes present (at most six: restatement, hierarchy and rules, trades out, window roll,
market, trades in with amendments, 720 orders; on a usual day four, 24 orders; each order a handful of vector sums): order-free, exact
in sum, dearer, offered per request, never official. **Planned** (not in 2.9.10).

**Data needed.** All held today: yesterday's official capture (with its vector and membership, [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results));
yesterday's and today's P&L vectors (both dates open in the engine, or yesterday's hot file reopened, [VAR_GUIDE section 26](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file));
the scenario dates of both sets; the trades' status and action (`TRADE_STATUS`, `TRADE_ACTION`) for the amendment split. Nothing new
from the bank.

**Cost.** Small, because only the **churn** is summed: S3 = S2 minus the vectors of the trades out; S7 = S6 plus the vectors of the trades in;
S4 and S5 are the same sums restricted to the common dates. A node needs its two totals (already in memory) and the sum of its changed
trades' vectors (hundreds to thousands, against millions held), then eight quantiles of 509 values. For all 131 official nodes it is well
under a second on the demo; at 2.3M positions it is bounded by the churn, not the book. The S2 step needs yesterday's trades summed by
today's hierarchy only when a book moved: the books that moved, not the bank.

**Where it shows.** **Built (2.9.10):** `VAR EXPLAIN <scope>` (and `VAR <scope> EXPLAIN`), the **Why it moved** tab on the VaR page
(a waterfall, each step opening its contributors), `drishti.py var explain` and the API `GET /api/v1/var/explain?scope=&from=&to=`
([VAR_GUIDE.md section 3.11](../guides/VAR_GUIDE.md#311-why-it-moved-the-var-change-explained)). Later: the Appetite landing's card
([section 5.4](#54-three-landings-to-mock-up-later)) and the daily risk report. `from` and `to` may be any two dates, not only consecutive ones (a month's
change for the committee), and `to` may be **now** (intraday since the morning's official figure).

**An explanatory overlay from sensitivities (approximate, labelled so).** Beside the exact market step (S5 − S4), the market move can be
read by risk factor: Σ sensitivity(t−1) × the factor's move from t−1 to t, by curve and tenor, issuer, currency pair, surface (the
position sensitivities of [section 7](#7-sensitivities-data) and the factor moves of the proposed `market-move` kind,
[PNL.md section 3.2](../contracts/PNL.md#32-market-move-the-move-of-each-risk-factor)). It says *which* factors drove the market step
(for example "USD SOFR 10Y +12 bp: −3.1m"); it is a first-order approximation of the P&L, not of the VaR change, and is drawn as an overlay
with its residual, never as one of the exact steps.

**Never stored.** Computed when asked. The daily report's frozen run (the planned report framework) keeps the explanation it showed, with its inputs'
versions, as every frozen cell does.

**Tests.** Crafted vectors where each cause alone moves VaR by a known amount (one trade out, one in, one restated vector, one book moved,
a rolled window): each step equals it and the others are zero; the steps sum to the change to the cent on the demo for every node; the
Shapley variant sums to the same total; sharded equals single-server to the bit (the sums keep a fixed order); a date with no official
capture for yesterday says so, never a zero.

#### 4.1.1 RO1: the build design

**What it shows.** A **Why it moved** tab on the VaR page of any scope (a node, a portfolio, a basket, an ad-hoc scope), and the same
answer wherever a lens shows a VaR change (the Risk desk card RL4, the CRO's *What changed*, the breach page of RO2, the board pack):
the waterfall of the steps from the *from* VaR to the *to* VaR, each bar opening its contributors; the states underneath (trades,
vectors, scenario days, hierarchy, VaR and the VaR scenario's date of each); the order-free view by child; the Shapley variant on
request; the risk-factor overlay when position sensitivities are loaded. Mock-ups: [section 11.1](#111-ro1-var-change-explanation).

**One screen, three views.** The tab has a view switch, **Waterfall · Matrix · Sentences**, over one answer of the same API:

| View | Mock-up | What it draws |
|---|---|---|
| **Waterfall** (the default) | A | the waterfall of the steps, the selected step's contributors beside it, the states under it, the order-free view by child |
| **Matrix** | B | the bridge of every node: the hierarchy down, the steps across, VaR before and after, the change and limit use; a cell opens its contributors and a generated sentence |
| **Sentences** | C | the answer in sentences, with the Shapley variant beside the sequence when asked |

The chosen view is kept in the address (`view=waterfall|matrix|sentences`, so a copied link opens the same view) and remembered in the
person's settings as the Home landings are ([EDITIONS.md section 4](../admin/EDITIONS.md#4-choose-the-home-landing)); a link
without `view=` opens the remembered one, else Waterfall. The pickers, the dates and the scope are shared by the three views, and a
switch redraws from the answer already fetched (no second request). The phone draws the Sentences view's paragraph and the steps as
bars whatever view is chosen.

**How it is built: an engine tab drawn with the standard panel kinds.** *Why it moved* is a tab of the VaR page's workbench beside P&L
path, VaR / ES history and Contributions ([VAR_GUIDE section 3.4](../guides/VAR_GUIDE.md#34-the-tabs)), answered by the VaR engine.
Unlike those tabs, it is not drawn by the VaR page's own charts: the console turns the answer into a ViewModel of the existing panel kinds
([RACHANA_REFERENCE, panel kinds](../guides/RACHANA_REFERENCE.md#panel-kinds)) and paints it with the one renderer of every screen
([CLIENT_RENDERER.md](../architecture/CLIENT_RENDERER.md)), so it looks and behaves like the rest of the product (themes, the Data table under
each chart, CSV, phone width). No new panel kind is needed:

| Part | Panel kind |
|---|---|
| The steps (Waterfall) | `waterfall` (`sum` the *to* VaR, `colors: theme`: a rise in VaR is not a gain) |
| The tiles | `metric` |
| A step's contributors, the states, the order-free view, trades out and in, the window | `table` (with `total` columns where the rows add up) |
| Every node (Matrix) | `pivot` (node by step, `heat`) with the hierarchy as its rows; a cell click fills a `table` and a `markdown` sentence |
| The answer (Sentences) | `markdown` (a template over the answer's facts) |
| Sequence against Shapley | `table` (cause, sequence, Shapley, difference; both columns total to the change) |

**How each figure is computed.** For a scope with members P, vectors v_p over the scenario days D and the configured confidence, VaR is
V(P, D) = −(the k-th smallest of Σ_{p∈P} v_p(d) over d ∈ D), k from the confidence and |D| exactly as the engine's VaR
([VAR_GUIDE section 18.1](../guides/VAR_GUIDE.md#181-var)).

| Figure | Formula | Notes |
|---|---|---|
| A state S_i | V(P_i, D_i) with the members, vectors, days and rules of the table in section 4.1 | S3 = S2 − Σ_{out} v_p(t−1); S7 = S6 + Σ_{in} v_p(t); amendments: S7 = S7′ − Σ_{amended} v_p(t−1) + Σ_{amended} v_p(t) |
| A step | step_i = V(S_i) − V(S_{i−1}) | Σ_i step_i = V(t) − V(t−1) by construction (telescoping); printed exactly, never rounded to fit |
| Window steps | S4 restricts S3 to D(t−1) ∩ D(t); S6 extends S5 to D(t) | dropping a day can only lower VaR or leave it (removing a tail day promotes the next rank); adding a day can only raise it or leave it; each day's rank in its state is printed |
| Market contributors | ΔC_c = C_c(S5) − C_c(S4) for each child c | components add up to VaR in every state ([VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts)), so Σ_c ΔC_c = the market step exactly |
| Trades out and in | I_p = V(S) − V(S without p), in S2 for trades out and in S7 for trades in | incremental VaR does not add up: the top N are listed and a last line, *the others and the interaction*, closes the step |
| VaR scenario of a state | the day d whose sum is the k-th smallest | said in words when it changes between states |
| Order-free view | ΔC_c = C_c(t) − C_c(t−1) | adds up to V(t) − V(t−1); children are today's; a child that left or arrived shows its whole component |
| Shapley | φ_j = (1/n!) Σ over the orders of the n causes present of [V(before j, with j) − V(before j)] | adds up to the change; n ≤ 6; never official |
| Limit-use line | use(t) − use(t−1) = [V(t) − V(t−1)] / L(t−1) + V(t) · [1/L(t) − 1/L(t−1)] | the first term the VaR change, the second the limit's own change (`var-limit` by business date); shown only when the limit's horizon and confidence match the explanation's |

**Horizon and confidence.** The explanation follows the VaR page's pickers ([VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen)):
at a confidence, every state uses that k; at 10-day √10 every state and step is × √10 (the shape is the 1-day one); at the 10-day sum
the scenario days are the overlapping 10-day windows, so the window steps drop and add windows rather than days. The official
explanation (the one reports freeze) is at the official lens, 1-day 99%.

**Any two dates, and now.** `from` and `to` may be months apart (the committee's month, the board's quarter): the same states, with the
window steps covering every day that left and entered (one line when many), and trades out and in the net churn between the two
dates. `to = now` uses the latest intraday batch's vectors for S5 to S7 (the batches of [VAR_GUIDE section 15](../guides/VAR_GUIDE.md#15-inputs-from-upstream-engines),
`RUN_KIND` intraday) against the morning's official capture.

**Data used (existing contracts).** `var-official` (the official capture with its vector and membership), `pnl-vector` and
`scenario-pv` (both dates' vectors, with `TRADE_STATUS` for the amendment split and `RESTATEMENT` for S1), `scenario-set` (the two
dates' scenario days), `risk-hierarchy` and `portfolio` (membership by date), `var-limit` (the limit-use line), `position` (the
position grain). Nothing new is asked of the bank. The risk-factor overlay needs the planned `position-sensitivity` kind
([section 7.3](#73-a-proposed-data-contract-position-sensitivity)) and is drawn only when it is loaded.

**Settings (built in 2.9.10 but `shapley`, planned; [VAR_GUIDE.md section 12](../guides/VAR_GUIDE.md#12-settings)).**

| Key | Default | Meaning |
|---|---|---|
| `drishti.var.explain.order` | `restatement, hierarchy, trades-out, window, market, trades-in` | the sequence; a bank may move trades first |
| `drishti.var.explain.window-lines` | `two` | `two` (oldest day dropped, newest day added) or `one` (window roll) |
| `drishti.var.explain.split-amendments` | `true` | amendments as their own step when the feed marks them |
| `drishti.var.explain.top` | `10` | contributors listed per step before *the others and the interaction* |
| `drishti.var.explain.shapley` | (planned) | `on-request` or `off`, with the Shapley variant |
| `drishti.var.explain.drivers-max` | `2000` | trades per step whose incremental VaR is computed |
| `drishti.var.explain.cache-mb` | `32` | the kept answers' memory, weighed in bytes |
| `drishti.var.explain.matrix-max` | `60` | child nodes the Matrix view explains |
| `drishti.var.explain.cache-minutes` | `10` | answers cached like the VaR page's, never stored |

**Command, CLI and API (built in 2.9.10 as below; Shapley, the computed kind and the AI tool are planned).**

- `VAR EXPLAIN <scope> [from] [to]` or `VAR <scope> EXPLAIN [from] [to]` on the command line: `VAR EXPLAIN BANK`,
  `VAR DESK DESK-CREDIT EXPLAIN 2026-06-30` (FUNC lists it; the edition word `VAR` covers it).
- `drishti.py var explain <scope> [--from] [--to] [--confidence] [--matrix] [--xlsx FILE] [--json]`, the waterfall as aligned lines.
- `GET /api/v1/var/explain?scope=&from=&to=&confidence=` (`method=shapley` answers 400: planned), with `/explain/matrix` and
  `/explain.xlsx` (the working as a workbook of formulas): `{states[], steps[{name, effect,
  contributors[], rest}], orderFree[], limit{}, provenance}`; the same entitlements as the scope's VaR (a person who cannot see a
  book sees its contribution folded into *the others*).
- The same answer as a computed kind, `var-explain` (id the scope, `params: { from, to, method }`), served by a reactive connector over
  the VaR engine, so any Sutra panel can draw it (RO2's *why it moved since detection*, a lens card) with `source:`
  ([REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md#reading-a-computed-kind)).
- An AI tool over the same API, so *"why is Rates VaR up?"* answers with these figures (the CRO lens's Ask).

**Phone.** The answer (one paragraph) is **must**; the steps are **glance**, the waterfall's compact form as horizontal bars; each step's
contributors are **more**; the states table, the bridge of every node and the Shapley comparison are **desk**
([MOBILE_DISPLAY.md section 3](../architecture/MOBILE_DISPLAY.md#3-phone-tiers-must-glance-more-desk)).

**Open questions for the product owner.**

1. **Mock-up choice:** A (the waterfall with contributors, recommended), B (the bridge of every node) or C (the answer in sentences
   with Shapley beside the sequence).

   **Decided (product owner, 2026-11-01):** one screen; A is the default view, B and C are views on the same screen (the view switch
   above).
2. **Window lines:** two lines by default (recommended: a dropped tail day and an added one read differently), or one.
3. **Amendments:** their own step (recommended where the feed marks them), or folded into trades in.
4. **How far back:** any two dates (recommended, both captures must exist), or capped (for example 13 months) to bound the work.

### 4.2 RO2: breaches and escalation

**Records (Drishti-owned, content store, versioned).** A **breach** is created by the engine, not by a person, when an official run finds
a limit's use above 1 (or, intraday, when a configured check does): id `BR-<year>-<sequence>` (the `limit-breach` contract,
[CRO_LENS section 7.10](CRO_LENS.md#710-limit-breach-brch-a-breach-as-a-record-new-drishti-owned)), one record per limit and episode
([section 4.2.1](#421-ro2-the-build-design)); it holds the limit id, the measure, the
scope, the capture or answer id that showed it, the use at detection, and a **status**: `open` → `explained` → `approved` (or
`remediated`) → `closed`, with `escalated` at any point. Each change of status is a new version with who, when and a comment. The number
is never copied as the truth: the breach points at the official capture, which holds it.

| Action | Who | What it records |
|---|---|---|
| Explain | the desk (first line) | cause, action, expected duration; required fields, then free text |
| Review | the business risk manager | accept, ask for more, escalate |
| Approve a temporary limit increase | the limit owner named in the catalogue (RO3) | new amount, valid until; four eyes; nobody approves their own request |
| Escalate | automatically after a configured number of days, or by hand | to the next level of the limit's chain (desk → business → CRO) |
| Close | the risk manager, once use is below the limit or the temporary limit holds | — |

**Temporary limits.** Limits are fundamental (the bank's limit system owns them). Two choices for the product owner
([section 9](#9-decisions-for-the-product-owner)): (a) Drishti records the request and approval and the bank's limit system is updated
by its own process, Drishti reading the new limit the next day; (b) an approved temporary limit is a Drishti-owned **overlay** on the
catalogue, valid from and to, used for limit use until it expires and exported for the limit system. (a) is simpler and keeps one
source of truth; (b) answers the same day.

**Notification.** The existing alert machinery (inbox, bell, email, chat bridges, hysteresis and cooldown,
[USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers)) is reused, but the rules come **from the catalogue** (each limit's owners
and escalation chain), not from each person setting an alert. Near breaches (use at or above the limit's warning level) notify without
creating a breach.

**Commentary** reuses comment threads anchored to the data ([COLLABORATION.md](../architecture/COLLABORATION.md)), anchored to the breach record, so the
discussion and the record stay together and fall under the same retention and legal hold.

**Approvals** reuse the planned **maker-checker sign-off record** ([REGULATORY_VAR_REPORTING section 6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off)):
one record shape for "a person approved this version of this thing", for reports, breaches, temporary limits and RNIV entries.

**Data needed.** The limit catalogue (RO3) with owners and the escalation chain; the official records (built). **Size** M. **Tests:**
a crafted breach goes through every status, refuses self-approval, escalates after N days, closes; a restated date that removes the
breach marks it `withdrawn`, never deletes it; the audit trail lists every step; a person without the power sees the queue read-only.

#### 4.2.1 RO2: the build design

**What it shows.** A **Breaches and escalation** screen (the queue of every open breach and near limit, worst first, *my coverage* by
default; the opened breach beside it with its escalation chain, its use over 20 business days, why it moved since detection (RO1), the
desk's explanation and actions, a temporary increase awaiting decision, and the thread), the closed and withdrawn breaches of the week,
and the same records on the CRO's decision queue and in the board pack. VaR limits first (`var-limit`), every measure once `risk-limit`
exists (decision 5). Mock-ups: [section 11.2](#112-ro2-breaches-and-escalation).

**One screen, three views.** A view switch, **Queue · Board · Timeline**, over the same records:

| View | Mock-up | What it draws |
|---|---|---|
| **Queue** (the default) | A | the queue worst first with deadlines, and the opened breach beside it with everything needed to act |
| **Board** | B | the breaches as cards in their status columns, the opened one in a drawer; near limits in a strip |
| **Timeline** | C | every VaR limit by use, the breaches' statuses over 20 business days, the escalation clock |

The chosen view is kept in the address (`view=queue|board|timeline`) and remembered in the person's settings; the filters (coverage,
status, period, limit type, business, hierarchy) are shared by the three, and so is the opened breach. The phone draws its own tiers
(below) whatever view is chosen.

**How it is built: Sutras over Drishti-owned records, actions through write-back.** RO2 is configuration on the generic screen path
([ARCHITECTURE.md section 7a](../architecture/ARCHITECTURE.md#7a-how-a-screen-is-made-generic-not-bespoke)), not a bespoke page, so a bank
tailors the screens, the forms and the rules in YAML:

- **The records** are three Drishti-owned kinds of the market-risk pack: `limit-breach` (BRCH, created and versioned by the breach
  service after the official run), `breach-action` (BACT) and `limit-request` (LREQ), written by people through write-back.
- **The views are Sutras.** The screen is a page kind, `breach-queue`, whose id is the coverage (`MINE`, `ALL` or a node such as
  `MGMT~DESK-RATES`): its document lists the coverage's breach records, the near limits without a record and the week's closed and withdrawn
  breaches, read from the records and the official run as the trading pack's derived pages are. Each view is one Sutra of that kind
  (`breach-queue`, `breach-board`, `breach-timeline`); the view switch opens the page with the chosen one, as `?sutra=` does
  ([RACHANA_REFERENCE, events, sources and children](../guides/RACHANA_REFERENCE.md#events-sources-and-children-sutra-next)). A breach
  opened from any view is a Sutra Next child (`place: detail-right` in the Queue, a drawer in the Board) reading the record with
  `source: { kind: limit-breach, id: "@queue.open.id" }`; `BRCH <id>` opens the same record on its own page with the same panels, shared as a
  pack fragment (`breach-detail@1`).
- **The actions are Sutra Next forms with write-back** ([WRITE_BACK.md section 4a](../guides/WRITE_BACK.md#4a-submitting-a-change-write-back)).
  Each action is a form with `new: drawer` whose `save:` writes a new record to the `owned` target, so the edit log keeps who, when and
  the reason, and the site's approval policy applies (decision 7):

| Action | Form | `save:` | Approval |
|---|---|---|---|
| Explain | cause (`enum`), action, expected by (`date`), comment (`textarea`) | `{ kind: breach-action, target: owned, reason: required }` | none |
| Review | accept or ask for more (`enum`), comment | the same | none |
| Escalate | to whom (the next level of the chain, read-only), comment | the same | none |
| Request a temporary increase | amount (`money` in the limit's currency), valid until (`date`, `min: today`), reason; a hint: the use at the requested amount | `{ kind: limit-request, target: owned, approval: one, reason: required }` | four eyes: the approver the chain names, never the requester (`self-approval: false`) |
| Close | closing comment; shown only while use is at or below the limit (`when:`), refused again by the server above it | `{ kind: breach-action, target: owned, reason: required }` | none |

When an action is applied, the breach service reads the `edit.applied` event of the new record
  ([WRITE_BACK.md section 18.3](../guides/WRITE_BACK.md#183-the-applied-event-and-triggers)) and writes the breach's next version (status,
  `ESCALATED`, who, when), which the live channel pushes to every viewer. The export of approved increases for the bank's limit system is
  the API below, or a `source` write when the bank's limit system has a connector with `writes:`.
- **The thread** is the record's Discussion ([COLLABORATION.md](../architecture/COLLABORATION.md)), anchored to the breach: no panel to build.

**Panel kinds and fragments.** Every panel is an existing kind or Drishti UI kit fragment:

| Part | Kind or fragment |
|---|---|
| Tiles (Q2) | `metric` (the kit's `key-figure@1`); the filters are the screen's controls |
| Queue, closed and withdrawn (Q3, Q4) | `row-picker@1` (a `table` that emits `open`); use as a toned percentage |
| Header, the chain (Q5) | `status`; `event-timeline@1` (`timeline`) for the chain and the record's versions |
| Use over 20 business days (Q6) | `area` with `limit` (the limit) |
| Why it moved since detection (Q7) | `waterfall` reading RO1's answer as a computed kind (`source: { kind: var-explain, id, params: { from, to } }`, a reactive connector over the VaR engine, [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md#reading-a-computed-kind)) |
| Explanation, the increase (Q8, Q9) | `kv` with the forms above |
| Board (Q11) | `tabs` with `layout: columns`, one column per status, each a compact `table` |
| Limits and timeline (Q12) | `hbar` (limits by use, toned), `timeline` (statuses over 20 days), `ladder` (the escalation clock, the next deadline lit) |

Three small additions are needed, none of them a new panel kind: **reference lines on `line` and `area`** (`markers:`, as `histogram`
has: the near level beside the limit on Q6); **a new record's field defaulting from the record the page shows** (a form `default:` reads
the form's own fields today; an action's record needs the breach id, `default: "$.BREACH_ID"`); and **the pending request's decision in
place** (Approve, Approve with conditions, Reject on Q9: the same decision as Review → Changes, with the same four-eyes checks, drawn on
the record's screen; until it exists, Q9 links to Review → Changes). The new pack fragment is `breach-detail@1` (the opened breach's
panels, used by the Queue, the Board's drawer and the record's own page).

**How each figure is computed.**

| Figure | Rule |
|---|---|
| Use | use = V_L / L, where L is the limit amount and V_L the scope's VaR **at the limit's own lens**: its `HORIZON` (1D or 10D, the 10-day figure by the configured ten-day method) and `CONFIDENCE` (`var-limit`; `risk-limit` adds `MEASURE`, so a stressed VaR limit is compared with stressed VaR). The official run computes V_L for every limit whatever lens a screen shows |
| Breach | use > 1 on an official run (a strict inequality; exactly at the limit is within) |
| Near | use ≥ w, w the limit's own warning level (`WARNING_AT`), else the site default 0.8 (decision 8); a near limit notifies, it creates no record |
| Comparable on a screen | the screen's horizon and confidence equal the limit's; otherwise the use cell reads *limit is for 1-day 99%: not comparable* ([VAR_GUIDE section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen)) and the screen never shows a breach the official run did not record |
| Episode | one record from the first official date with use > 1 while later official dates stay above; `USE_LATEST` follows each run; a new breach within `reopen-within-days` of the last close reopens the same record (no flapping) |
| Back within | the first official date with use ≤ 1 marks *back within since*; the record stays open until a risk manager closes it (or `auto-close-after-days` business days pass, when the site sets it) |
| Age | business days since detection on the bank's calendar (`calendar`) |
| Restatement | a restated date is re-evaluated; an episode with no date above 1 after restatement becomes `WITHDRAWN`, kept with the reason |
| Missing figure | no official capture for a date: *not evaluated*, never a close, never a new breach |
| Base use at expiry | for a temporary increase, V_L / the base limit on the latest date, shown before it lapses |

**States and who is told.** The states are those of section 4.2 (`OPEN`, `EXPLAINED`, `APPROVED`, `REMEDIATED`, `CLOSED`, `WITHDRAWN`, with
`ESCALATED` as a flag); each change is a version with who, when and a comment. The chain comes from the limit (`OWNER`,
`ESCALATION_CHAIN` of `risk-limit`); a `var-limit` row, which carries neither, takes the site's default chain for VaR limits by the
node's level (desk head, business risk manager, head of market risk, CRO).

| Event | Who is told | When |
|---|---|---|
| Breach detected | the limit's owner (first line) and the business risk manager | after the official run |
| Explanation due | the owner | `explain-within-days` before the deadline, and on it |
| Explained | the business risk manager | at once |
| Escalated (by the clock or by hand) | the next level of the chain, with the record and its thread | after `escalate-after-days` at each level, or on the action |
| Temporary increase requested | the approver the chain names (never the requester) | at once |
| Increase approved, conditioned or rejected | the requester, the risk manager and the limit's owner | at once |
| Approved increase not applied | the risk manager | when the next `var-limit` does not carry it |
| Back within, closed, withdrawn | everyone who acted on the record | at once |
| Near (no record) | the owner | once on crossing, again only after falling below and crossing again (hysteresis) |

Notifications go through the existing alert machinery (inbox, bell, email, chat bridges, cooldown) with rules from the catalogue, not set
by each person ([USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers)). Intraday (RL12): each intraday batch recomputes the use of
the changed nodes and notifies on crossing, with hysteresis (re-armed below `intraday-rearm`); it creates a record only when the site sets
`intraday-records`.

**Temporary limits (decision 4: recorded here, applied by the bank's limit system).** A `limit-request` (TLI) is entered through
write-back with four eyes ([WRITE_BACK.md section 10](../guides/WRITE_BACK.md#10-approval), decision 7); once approved it is exported
(the API below, a CSV or JSON Lines file) for the bank's limit system; Drishti checks that the next business date's `var-limit` carries
the approved amount and marks the request *not applied* if it does not. An increase always ends (`VALID_TO`); expiring requests show the
base use at expiry.

**Data used.** `var-limit` (with `HORIZON` and `CONFIDENCE`), `var-official`, `calendar`; the proposed Drishti-owned kinds `limit-breach`
(BRCH), `breach-action` (BACT) and `limit-request` (LREQ) of [CRO_LENS section 7](CRO_LENS.md#710-limit-breach-brch-a-breach-as-a-record-new-drishti-owned);
`risk-limit` once it exists ([MR_CUBE_LENS section 7.7](MR_CUBE_LENS.md#77-d6-risk-limit-new-one-limit-table-for-every-measure)); comment
threads ([COLLABORATION.md](../architecture/COLLABORATION.md)). Nothing new is asked of the bank for VaR limits; owners and chains come
with `risk-limit`.

**Settings (proposed).**

| Key | Default | Meaning |
|---|---|---|
| `drishti.breach.enabled` | `false` | breach records from the official run |
| `drishti.limits.warning-at` | `0.8` | the site's near level when a limit has no `WARNING_AT` (one threshold, decision 8; the Trading landing's `near-limit` and the explorer's bands read it) |
| `drishti.breach.explain-within-days` | `1` | business days for the first line's explanation |
| `drishti.breach.review-within-days` | `2` | business days for the risk manager's review |
| `drishti.breach.escalate-after-days` | `[3, 10]` | business days open at which the breach moves up each level of the chain |
| `drishti.breach.reopen-within-days` | `5` | a new breach this soon after a close reopens the record |
| `drishti.breach.auto-close-after-days` | `0` | `0`: a person closes; otherwise business days back within before an automatic close |
| `drishti.breach.default-chain.var` | `[desk-head, business-risk, head-market-risk, cro]` | the chain for limits that carry none |
| `drishti.breach.intraday-records` | `false` | intraday crossings notify only |
| `drishti.breach.intraday-rearm` | `0.97` | an intraday alert re-arms below this use |

**Command, CLI and API (proposed; the words are the proposed kinds' mnemonics).**

- `BRCH` lists the queue (my coverage), `BRCH <id>` opens a breach; `LREQ`, `LREQ <id>` the temporary increases.
- `drishti.py breach list [--status --scope --mine]`, `breach show <id>`, `breach explain <id> --cause --action --by`, `breach review <id>
  --accept|--more`, `breach escalate <id>`, `breach close <id> --comment`; `drishti.py breach request <limit> --amount --to --reason`,
  `breach decide <request> --approve|--reject [--amount --conditions]`.
- `GET /api/v1/breaches?status=&scope=&mine=`, `GET /api/v1/breaches/{id}` (with its versions and actions), `GET
  /api/v1/limit-requests/export?date=` (approved requests for the limit system); the screens read the records as any kind
  (`GET /api/v1/views/breach-queue/MINE`, `GET /api/v1/views/limit-breach/{id}`). Every action is a write-back change request
  (`POST /api/v1/changes`, `drishti.py change propose`; the `breach` subcommands above submit the same requests): each needs the edit
  power and its form's roles; the approver is never the requester.

**Phone.** Open breaches are **must** (status and breaches, MOBILE_DISPLAY section 3); the decision waiting for this person is
**glance** with full-width Approve and Reject, as on the CRO phone; the use chart, why it moved and the thread are **more**; the limits
table and the timeline are **desk**.

**Open questions for the product owner.**

1. **Mock-up choice:** A (the queue with the opened breach beside it, recommended), B (a board of the statuses) or C (limits first with
   a timeline; a candidate for the Oversight lens's Explorer landing).

   **Decided (product owner, 2026-11-01):** one screen; A is the default view, B and C are views on the same screen (the view switch
   above), built as Sutras with write-back for the actions.
2. **Closing:** a person closes (recommended: a closing comment is the evidence), or automatically after N business days back within.
3. **Escalation clock:** escalate after 3 and 10 business days open (recommended defaults), or after missed deadlines only.
4. **Intraday:** notify only (recommended), or create records from intraday crossings.
5. **Default chain for `var-limit` rows** until `risk-limit` carries owners: by the node's level (recommended), or one chain for all.

### 4.3 RO3: one limit catalogue and its utilisation

**Input kind** `risk-limit` (fundamental), generalising [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental)
to every market-risk measure: `LIMIT_ID`, `MEASURE` (`VAR`, `SVAR`, `ES`, `STR`, `DV01`, `CS01`, `VEGA`, `FX_DELTA`, `NOTIONAL`,
`POSITION`, `STOP_LOSS`, …), `SCOPE` (a scope text: a node, a portfolio, a rule), `BUCKET` (tenor, currency, curve, rating, underlying,
expiry: the measure's own grid; empty for VaR), `HORIZON` and `CONFIDENCE` (VaR and ES), `LOWER`, `UPPER` (a sensitivity limit has
both), `WARNING_AT`, `HARD_OR_SOFT`, `OWNER`, `ESCALATION` (the chain), `APPROVED_BY`, `VALID_FROM`, `VALID_TO`, `LIMIT_LETTER`,
`CURRENCY`, `BUSINESS_DATE`. `var-limit` stays as it is and is read as the `VAR` rows (open question 4 of STR.md, decided by the product
owner, [section 9](#9-decisions-for-the-product-owner)).

**Utilisation** is computed, never stored: per limit and date, the measure of the limit's scope and bucket from its engine (VaR from
the VaR engine, sensitivities from RO4, stress from RO9, P&L from RO5), `use = measure ÷ limit` (for two-sided limits, the side
approached), the status `ok`, `warning`, `breach`. One endpoint answers every limit of a date; the official run records the use of each
limit beside the VaR record (the same append-only table pattern), which feeds the utilisation history, the statistics (average, peak,
days above warning) and the annual limit review.

**Size** M (the catalogue, the engine-independent utilisation, the official rows; each measure arrives with its engine). **Tests:**
crafted limits of each type, both sides, the warning level, validity dates; a limit naming no node is reported, never silently unused
(as `DRS-5212` does for `var-limit`).

### 4.4 RO4: sensitivity aggregation and sensitivity limits

**Input.** Position sensitivities at risk-factor granularity, the proposed `position-sensitivity` kind of
[section 7](#7-sensitivities-data) (RO15). Today only per-trade `DV01` and book-level FRTB buckets are stored
([COMPUTED_ATTRIBUTES.md section 4.9](../architecture/COMPUTED_ATTRIBUTES.md#49-frtb-sensitivity-frtb), [section 7.1](#71-what-the-lake-holds-today)),
which is not enough for a limit.

**Computation.** Sensitivities are **additive**: a scope's DV01 by tenor and currency is the sum of its trades' rows. That is a derived
measure, but per arbitrary scope with entitlements it belongs to an engine (rule 4: an arbitrary scope, per reader), the same Java
summing pattern as VaR (a factor dictionary, per-trade rows, per-node totals), then each limit's bucket is matched. Grids: DV01 by
currency × tenor bucket, CS01 by rating × tenor, vega by underlying × expiry, FX delta by currency, equity delta by index or name.

**Screens.** A **ladder** per scope (the grid with each bucket's use against its limit, coloured, every cell printing its value) and a
drill to the trades of a bucket.

**Size** M once RO15 is in (the contract, loader and ladder skeleton are RO15's); L without it. **Tests:** the sum by bucket equals the sum of
trades; a bucket limit breached by a crafted trade; netting long and short within a bucket; two-sided limits.

### 4.5 RO5 and RO6: stop-loss, position and notional limits

- **Stop-loss** (RO5): cumulative P&L of a scope over the month and the year against a loss limit, from the bank's daily P&L (`desk-pnl`,
  the planned P&L feed; per trade when delivered, so any scope). Derived (a sum over dates) when at desk grain; computed when at trade grain for
  arbitrary scopes. **S** after the P&L feed.
- **Position and notional limits** (RO6): a position's quantity or market value, a scope's gross or net notional, from the derived
  positions ([VAR_GUIDE section 4](../guides/VAR_GUIDE.md#4-positions-derived-never-delivered)) and `trade-valuation.NOTIONAL` (its unit
  disputed, F10 in [COMPUTED_ATTRIBUTES.md](../architecture/COMPUTED_ATTRIBUTES.md#6-findings-duplicates-and-inconsistencies), which must be fixed first). **S**.

### 4.6 The data-trust card (RO13) and model performance (RO14)

- **RO13, one data-trust card** per date and scope: loads on time ([DATA_GUIDE.md › Telling Drishti new data has landed, section 8](../guides/DATA_GUIDE.md#8-expectations-and-late-data));
  rejected and failed rows; **completeness against the trades booked** (live trades without a vector, now not checked,
  [VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks)); the **share of VaR from proxied trades** (the
  scope's VaR with and without the `proxied` rows: an incremental VaR, exact); the market data exceptions (the Market lens's checks) and,
  from the planned market-data impact, their VaR at stake. Computed from what the loads and the engine already record. **S to M.**
- **RO14, model performance**: the planned backtests (actual and hypothetical, 99% and 97.5%) per desk with exceptions annotated (a
  commentary record per exception, as for breaches), the traffic light and the multiplier over time, Kupiec and Christoffersen, the share
  of proxied VaR, and later the PLA test (G12). **S** after the backtesting engine.

### 4.7 RO7: the RNIV register

**What it is.** A list, kept by market risk, of the risks the VaR model does not capture (a basis not modelled, a proxied curve, a
missing risk factor, a liquidity gap, a correlation assumed), each quantified and reviewed, whose total is held as capital or a reserve.

**Record (Drishti-owned, versioned).** `RNIV_ID`, title, description, risk type, the desks or scope affected (a scope text, so it opens in
the VaR page), the method of quantification, the **amount** and its as-of date, the evidence (a link to a capture, a stress result, a
document), owner, status (`proposed`, `approved`, `retired`), next review date, approvals.

**The amount.** Supplied by the quant who quantified it (fundamental: Drishti cannot work it out), **or**, where the method is one
Drishti can run (a stress P&L of a scope under a scenario; a VaR of a scope on a set of proxy vectors), **computed** on demand and frozen
at approval by the sign-off record (the same rule as a report cell). A register entry never stores a number Drishti could recompute
without saying where it came from.

**Screens and reports.** The register (filter by desk, status, due review), the total by desk and bank against the VaR, the monthly
review with sign-off (the planned sign-off record), a committee-pack section. **Size** S to M. **Tests:** an entry through its statuses with four
eyes; a computed amount frozen at approval and unchanged after a restatement; the overdue review alert.

### 4.8 RO8: concentration

**Views.** For a scope and date: market value, gross and net notional, and VaR (standalone and component in the scope) **by issuer,
sector, country, currency, tenor bucket**; the top N and their share of the total; a Herfindahl index per dimension; against
concentration limits when the catalogue has them (`MEASURE` `NOTIONAL` or `POSITION` with a scope rule).

**Computation.** The sums are additive over positions; the VaR columns come from the engine's contributions by a grouping
(`by=` a column, [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) already groups by desk, book,
position, trade). The missing pieces are the dimension on every position (the issuer of the instrument, core's issuer kind; its sector and country of
risk; a tenor bucket from the maturity) and, for concentration of **risk** rather than of value, the position sensitivities of
[section 7](#7-sensitivities-data): CS01 by issuer and sector, DV01 by curve and tenor, equity delta by issuer, FX delta by currency.
**Data:** reference data from the bank's security master; RO15. **Size** M. **Tests:**
shares add to 100%; component VaR by dimension adds up to the scope's VaR; a position without the attribute is counted as *unknown*,
never dropped.

### 4.9 RO9: stress testing, the engine, limits and reverse stress

- **The engine** is [STR.md](../contracts/STR.md) as proposed: full revaluation from per-trade scenario P&L, sensitivity-based for any
  scenario, user scenarios, the stress matrix, stress limits from the catalogue (RO3). **L.**
- **Stress limits and appetite**: the bank's worst stress loss against the appetite; per desk against its limit; breaches into RO2.
- **Reverse stress** (later, **M** after the engine): with sensitivities, the smallest shock (in the scenario's own volatility units)
  that loses a target amount has a closed form for the linear part (along the direction of the sensitivities weighted by the factors'
  covariance), refined with the second-order terms; with the historical scenario vectors, the scenarios and their scaled multiples that
  reach the target, ranked by plausibility. Always shown with its assumptions; never official.
- **Firm-wide exercises**: a named scenario set run on a date, frozen with its inputs and signed off through the planned report framework, then
  compared year on year.

### 4.10 RO10: liquidity

**Measures.** Per position: **days to liquidate** = |quantity| ÷ (participation rate × average daily volume), the participation rate a
setting (10% to 25% is usual); **bid-offer cost** = ½ × spread × |quantity| (or × |market value| with a spread in percent);
**share of market volume**. Per scope: the market value or VaR that can be liquidated within 1, 5, 10, 20 days (a liquidity ladder),
and the VaR scaled by each position's liquidity horizon as a check against the 10-day VaR.

**Data the bank supplies.** Average daily volume per instrument (exchange or vendor), bid-offer per instrument or per product and
tenor bucket (a grid is enough for OTC), the instrument link on every position. The us-equities pack's prices are internal-only and
off by default, so its data is not a source for this. **Size** M to L. **Tests:** crafted positions with known volume and spread; a
position without volume is *unknown*, never zero days.

### 4.11 RO11: the daily risk report and the committee pack

**The daily report** is a report definition on the planned report framework: the appetite measures and their limits; every limit at warning or in
breach with its status and commentary (RO2); VaR change explained for the bank and each business (RO1); the backtest and yesterday's
exception if any (RO14); the data-trust card (RO13); the top concentrations (RO8). Generated after the official run, checked (a missing
explanation for a breach blocks sign-off), frozen, signed off by the head of market risk, distributed through subscriptions.

**The committee pack** (monthly) and the **board pack** (quarterly, the planned board pack) add: utilisation statistics, breaches of the period, the
backtesting chart, the RNIV register's total and changes, stress results against appetite, the limit changes approved. **Size** M on the
framework. **Tests:** as the report framework's (every cell traced to its official capture; a failing check blocks sign-off; a frozen run unchanged after a
restatement).

#### 4.11.1 The board pack: the build design

**What it shows.** A PDF for the board risk committee (quarterly) and, with a shorter page list, the executive risk committee (monthly),
in the bank's house style. The default layout (mock-up A, `layout: pages`) is six A4 portrait pages, one subject a page:

| Page | Contents |
|---|---|
| 1. The answer | the meeting, the period and the data date; the answer in a paragraph (the CRO lens's Brief as the first page, CRO11); six headline tiles (VaR against the board limit, stressed VaR with its 12-week average, 10-day VaR, ES, the backtesting zone, breaches in the period); for decision and for noting; contents |
| 2. VaR over the period | the daily official VaR with the limit and the warning level; period end, average, high and low (with dates) of VaR and stressed VaR; VaR by risk type with diversification |
| 3. What changed since the last board | RO1 from the last board's data date to this one: the waterfall, the order-free view by desk, one sentence of cause |
| 4. Limits and breaches | each VaR limit's use at period end, highest use, days at or above the warning level and days in breach; the breaches of the period (RO2) with status and commentary |
| 5. Backtesting | the 250-day chart against actual P&amp;L, the zone, both counts, the multiplier, the exceptions and the desks that drove them |
| 6. Sign-off and lineage | maker, checker and approver with times; the checks; every figure's official records, data versions, the definition and the house style revisions, the run |

Stress results against appetite and the RNIV register join as pages when RO9 and RO7 exist. Mock-ups: [section 11.3](#113-the-board-pack).

**One definition, three layouts.** The board pack is not a screen: it is a report definition, and the mock-ups are three **layouts** of
the same pages and figures, chosen by the definition's `layout:` key. The run, its checks, its sign-off and its lineage are the same
whatever the layout; only the PDF changes.

| `layout:` | Mock-up | The PDF |
|---|---|---|
| `pages` (the default) | A | A4 or Letter portrait, the answer first, one subject a page, every chart's figures printed beside it |
| `landscape` | B | landscape pages like a board deck: one message a page, large tiles, three takeaways; the commentary in an appendix |
| `register` | C | a dense committee register: every measure against the last board, the breach register, the temporary increases; tables only |

A bank that wants two layouts (the board's pages and the committee's register) keeps two definitions over the same pages list.

**How each figure is computed.** Nothing is recomputed: every cell reads the stored official runs, as the report framework requires
([REGULATORY_VAR_REPORTING section 8.2.6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off)).

| Figure | Source and rule |
|---|---|
| Period end, average, high, low | period statistics (G5) over the official records of the period: average = mean of the daily official VaR; high and low with their dates; stressed VaR's 12-week average as the capital rule uses it |
| VaR by risk type | the definition's rows (filters on `RISK_TYPE`), diversification = total − Σ rows; high and low not computed for diversification (*n/m*) |
| Change since the last board | RO1 with `from` = the last meeting's `DATA_AS_OF` (`governance-calendar`) and `to` = this pack's data date; steps add up exactly |
| Limit use statistics | per limit over the period's official dates: use at period end, max use, the count of dates with use ≥ the warning level, the count with use > 1; at each limit's own horizon and confidence |
| Breaches of the period | `limit-breach` records whose episode overlaps the period: counts by status at the data date, highest use, the latest commentary from `breach-action` |
| Backtesting | the backtesting engine (G4) at the data date: actual and hypothetical counts over 250 days, the zone and the multiplier from the configured table |

**Checks before sign-off** (from the definition; a failed check blocks sign-off, never generation): every figure from an official
record; period end equals the VaR page for the data date; the change's steps add up to VaR(to) − VaR(from); every open breach is
explained or inside its explanation deadline; the backtesting counts equal the backtesting report's; no official record missing in the
period; a movement over the definition's threshold has a note.

**A definition in the report framework.** The pack is one file of the report-definition framework
([REGULATORY_VAR_REPORTING section 8.2.6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off)): the same
directory (`config/reports/`), the same validation at start (a wrong field names the file and the field), the same generator reading the
stored official records, the same declarative checks, frozen runs with each cell's lineage, maker-checker-approver sign-off (one person a
step, send back to the maker) and files released after the last approval. It extends the framework in one place: a third `type`,
`pack`, beside `var-table` and `backtesting-chart`, whose `pages:` list names the bands of each page, with a `layout:` and a `style:`
(illustrative):

```yaml
# config/reports/board-pack.yaml (illustrative)
report: board-pack
title: Market risk report to the board risk committee
type: pack                                  # pages of bands; var-table and backtesting-chart as before
period: fiscal-quarter                      # the data date and "since" come from governance-calendar's meetings
measure: var
layout: pages                               # pages (A, the default) | landscape (B) | register (C)
style: bank-default                         # config/reports/styles/<style>.yaml
pages:
  - { page: answer,              bands: [brief, headline-tiles, for-decision, contents] }
  - { page: var-over-period,     bands: [var-series, period-statistics, var-by-risk-type] }
  - { page: what-changed,        bands: [explain-waterfall, explain-by-desk, explain-sentence] }
  - { page: limits-and-breaches, bands: [limit-use-statistics, breaches-of-period] }
  - { page: backtesting,         bands: [backtest-chart, backtest-zone, backtest-exceptions] }
  - { page: sign-off,            bands: [signatures, checks, lineage] }
checks:
  - total-equals-official
  - no-missing-records
  - explain-adds-up                         # new: the change's steps add up to VaR(to) - VaR(from)
  - breaches-explained                      # new: every open breach explained or inside its deadline
  - same-backtest-as                        # new: the counts equal the backtesting report's
  - { check: movement-needs-note, threshold: 0.10 }
outputs: [pdf, xlsx]
sign-off: [maker, checker, approver]
step-titles: { approver: Chief Risk Officer }
```

The bands are the framework's cells grouped for print: tiles, a paragraph with facts (pre-filled, edited by the maker as the narrative
is), a table, a chart. The register layout draws the same bands as tables only. The house style is configuration of its own, one file
per style, named by the definition (illustrative):

```yaml
# config/reports/styles/bank-default.yaml (illustrative)
style: bank-default
paper: { size: A4, orientation: portrait, margins-mm: [18, 16, 16, 16] }   # Letter for a US bank
logo: { file: logo.png, height-mm: 10, position: left }                   # the bank's file, beside the style
colours: { heading: "#0f3d6e", rule: "#0f3d6e", breach: "#b42323", near: "#8a6100" }
font: Helvetica                                                           # the standard PDF fonts; nothing downloaded
header: "{title} · {confidentiality}"
footer: "Data as at {data-date} · run v{run} · from the official records · page {page} of {pages}"
confidentiality: "Confidential · board use only"
sign-off: { roles: [maker, checker, approver], show: [name, role, time] }
```

**Rendering.** The server's own PDF writer of BI statements (page-exact, standard fonts, no library and nothing downloaded,
[BI_GUIDE section 24](../guides/BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader)) is extended with two bands: a
**chart band** (lines, bars and the waterfall drawn as PDF vector paths, every value also printed in a table on the same page) and an
**image band** for the logo (PNG or JPEG). The same pages render as the on-screen preview, so the preview and the file agree.

**Generation and distribution.** On request, or scheduled a configured number of business days before each meeting of
`governance-calendar`; the run is frozen (a later restatement makes a new version that needs a new sign-off); maker, checker and the
approver the definition names sign through the framework's record; the signed PDF goes to a subscription list (no bursting: every
reader gets the same pack) and is kept with the run. The CRO phone's *Board pack PDF* button opens the latest signed run.

**Data used.** `var-official` (with period statistics over it), `var-limit`, `limit-breach`, `breach-action` and `limit-request`
(proposed, RO2), the planned `desk-pnl` and `var-backtest` (backtesting), the proposed `governance-calendar` (meetings and their data
dates, [CRO_LENS section 7.13](CRO_LENS.md#713-governance-calendar-gcal-board-and-committee-dates-new-fundamental)). The calendar is the
one input the pack adds; it is a small fundamental kind the bank or the risk office maintains.

**Settings (proposed).** `drishti.reports.board-pack.lead-days` (business days before a meeting to generate, default 5),
`drishti.reports.board-pack.style` (the default style, `bank-default`), `drishti.reports.pdf.charts` (`vector`), and the definition's
own `pages:`, `layout:` and `style:`.

**Command, CLI and API (the framework's).** `VAR REPORT board-pack 2026-Q3` opens the run on the framework's review and sign-off
screen; `drishti.py var report run board-pack 2026-Q3` generates it; `GET /api/v1/var/reports/board-pack/runs/2026-Q3/file?format=pdf`
downloads a signed run (before the last approval, a draft titled *DRAFT, not signed off*).

**Phone.** Generating, reviewing and signing are **desk**; the signed PDF opens in the phone's own viewer from the CRO phone's button,
so the pack has no phone layout of its own.

**Open questions for the product owner.**

1. **Mock-up choice:** A (the answer first, one subject a page, recommended), B (landscape pages like a board deck) or C (a dense
   committee register).

   **Decided (product owner, 2026-11-01):** A is the default PDF layout; B and C are layout choices of the report definition
   (`layout: landscape`, `layout: register`), not screens.
2. **Paper:** A4 portrait by default (recommended for a Canadian bank; Letter for a US one, set in the style).
3. **Fonts:** the standard PDF fonts only (recommended: nothing to download or license), or the bank's own font embedded (a font file
   the bank supplies, larger files).
4. **The monthly committee pack:** the same definition with fewer pages (recommended), or a separate one.
5. **PDF/A** for the archive: needed by the bank's records policy, or plain PDF.

---

## 5. The Drishti Oversight lens: recommendation and design

### 5.1 The answer: yes, a separate lens, built after two features exist

**Yes, a lens for risk oversight is a good idea, and it should be its own lens (a fifth edition file), not a landing inside Drishti
Risk.** It should be built **after** the VaR change explanation (RO1) and the breach workflow (RO2) exist; before that it would be Drishti
Risk with a different first page.

| | For a separate lens | Against (and the answer) |
|---|---|---|
| **Persona** | The second line is a different person from the Risk lens's user: independent challenge, governance, read-only on the trading data. Trading was split from Risk on the same ground ([EDITIONS.md section 14](../admin/EDITIONS.md#14-drishti-trading)) | Risk already serves "risk managers" (VAR_GUIDE section 1). *Answer:* Risk is the engines' lens (VaR and exposure of any scope); Oversight is the governance lens over them |
| **Defaults** | Bank-wide first; **limits first** (every type), then breaches, then why it moved; every hierarchy, including the limit letters | A landing inside Risk could set these defaults. *Answer:* only the landing, not the menus, words, help or BI |
| **Commands** | Oversight needs stress (`STR`, `SCN`, `SSPNL`), P&L explain (`PNL`), FRTB, valuations (`TVAL`), which Risk hides ([EDITIONS.md section 4](../admin/EDITIONS.md#4-what-drishti-risk-shows)); and fewer operational ones (scenario inputs, collateral operations) | Adding them to Risk would blur Risk. *Answer:* a separate allow-list is one file |
| **Governance actions** | Breach commentary and approval, temporary limits, the RNIV register, sign-offs: actions Risk (Build, Studio and write-back off) has no place for | They could be features of the full product. *Answer:* they will be (any lens may show a breach); the Oversight lens is where they are the default page |
| **RBAC later** | One lens maps cleanly to roles: `lens.roles` starts the CRO's team in it, `landing-by-role` opens Appetite for the head and Breaches for a business risk manager ([EDITIONS.md sections 7, 8](../admin/EDITIONS.md#8-lenses)) | `lens.roles` is not wired yet. *Answer:* the lens works without it, as the other four do |
| **Cost** | One file, its landings and read-only routes, no engine change: the mechanism was built for this ([EDITIONS.md section 11](../admin/EDITIONS.md#11-later-editions)) | Five lenses crowd the Switch-to menu. *Answer:* `drishti.lenses` is per site; a bank offers the ones it uses |
| **Drift** | | Two files listing similar commands may drift. *Answer:* the edition test checks each lens's surfaces; a shared list could later be an `extends:` in the edition file (a small change, [section 9](#9-decisions-for-the-product-owner)) |
| **Read-only** | | A lens hides, it does not secure ([EDITIONS.md section 8](../admin/EDITIONS.md#8-lenses)). *Answer:* read-only comes from the oversight **role** (no write-back powers; governance powers only), not from the lens |

**Why not a landing inside Drishti Risk.** A landing changes one page; the second line's difference is also in the menus (Breaches,
Limits, RNIV, Reports in front), the commands (stress, P&L explain), the help (governance guides), the BI datasets (limit utilisation,
breaches, backtests) and the role. An edition file carries all of that; a landing does not.

**Why not now.** Today the lens's three landings could show VaR against VaR limits, the Risk map's breaches and the Market quality
checks: all already in other lenses. With RO1 and RO2 it has its own two reasons to exist (*why did it move* and *what are we doing about
the breach*), and RO3 makes its limits page more than VaR.

### 5.2 The edition file, in outline

A proposed `oversight.yaml` beside the other four in the editions folder (illustrative; names to be confirmed at the build):

```yaml
edition: oversight
title: Drishti Oversight
packs:
  enabled: [core, market-data, trading, market-risk, counterparty-risk]   # counterparty-risk read for concentration (decision 6)
  allow-only: true
commands:
  allow: [VAR's and Risk's read commands: VRES, VOFF, VLIM, HIER, PORT, POS, TVAL, PNLV,
          stress: STR, SCN, SSPNL, P&L explain: PNL, FRTB,
          reference: CPTY, GRP, LE, BOOK, DESK, TRDR, ISS, trades: TRD, CTRD, PTRD, LTRD,
          exposure read: EXP, XOFC, LIM, market data: CRV, FX, FXV, EQ, CMD, CDS, BND,
          the scenario matrix: SMAT; the proposed oversight commands]
  words: { BI: on, FUNC: on, WHAT: on, DATAMAP: on, VAR: on, EXP: on, FORM: off, RUPAKA: off }
features: { bi: edition, var: on, exposure: on, reports: on, alerts: on, monitors: on, workspaces: on, layouts: on,
            collaboration: on, governance: on,            # governance: the new feature (breaches, RNIV, sign-offs)
            build: off, designer: off, forms: off, calc: off, python: off, ai: off, embed: off }
bi:
  datasets: [market-risk-var-latest, market-risk-var-official, and the proposed: limit utilisation, breaches, backtests, RNIV]
menus:
  views: [terminal, search, functions, datamap, var, oversight-breaches, oversight-limits, oversight-rniv, reports, monitors, alerts]
  placeholder: "VAR BANK, VAR BANK EXPLAIN, STR BANK, VOFF … then <GO>"
home:
  landing: appetite
  landings: [appetite, breaches, explorer]
  landing-by-role: {}                  # e.g. {cro: appetite, market-risk-manager: breaches} once roles are wired
help: { categories: [knowledge, start, use, data, operate], knowledge: [market-risk, counterparty-risk, core, trading, market-data] }
admin: { pages: [as Drishti Risk] }
branding: { product: Drishti Oversight, tagline: "Appetite, limits and breaches: independent oversight of market risk" }
oversight:
  coverage: {}                         # person → businesses they cover (as trading.people), never widening access
  near-limit: 0.8                      # used only where a limit has no WARNING_AT of its own
```

**Read-only by default; governance actions on.** Build, Studio, the designer, forms and write-back are off. The only actions are the
governance records of section 4 (explain, review, approve, escalate, close a breach; propose, approve, retire an RNIV entry; sign off a
report), each behind its own power (proposed: `breach.explain`, `breach.approve`, `limit.temporary`, `rniv.edit`, `rniv.approve`,
`report.sign`), four eyes where the policy says so, no self-approval. A desk user who explains a breach does so from wherever they are
(the Trading lens shows *their* open breaches); the Oversight lens is where the second line reviews and approves.

### 5.3 Menus

| Menu | Items |
|---|---|
| Views | Terminal, Search, Functions, Data map, VaR, **Breaches**, **Limits** (every limit, its use and history), **RNIV register**, **Stress** (once RO9), Reports, Monitors, Alerts |
| BI | the edition's datasets and reports: VaR official, limit utilisation, breaches, backtests, RNIV, the daily report and the committee pack |
| Help | Knowledge (market risk, counterparty risk, trades, entities, market data), Start, Use, Data, Operate; this document's user-facing successor |
| Admin | as Drishti Risk |

### 5.4 Three landings to mock up later

Mock-ups first, the product owner picks, then the build (the house rule); none exists yet (section 11). The outlines:

**A. "Appetite"** (the default; the head of market risk, the morning meeting):

- **Tiles:** bank VaR and ES against the bank limit (and stressed VaR once 2c); the worst stress loss against appetite (once RO9); limits in
  breach and near breach, of every type; open breaches past their deadline; the backtest light and exceptions in 250 days; the data-trust
  state (green, amber, red).
- **Why VaR moved** (RO1): the bank's waterfall (restatement, hierarchy, trades out, window roll, market, trades in), each bar opening its
  contributors; a switch to any business.
- **Limits heat map:** businesses down, limit types across (VaR, stressed VaR, DV01, CS01, vega, FX delta, stress, stop-loss), each cell its
  worst use, printed; from the Risk map landing's heat map, extended to every type.
- **Backtesting:** the bank's P&L against VaR over 250 days with exceptions marked (the Trading desk-head card, bank-wide).
- **Data trust:** loads on time, completeness, proxied share, market data exceptions and their VaR at stake.

**B. "Breaches and escalations"** (the business risk managers; the workflow queue):

- The queue: every open breach and near breach, worst first, with limit, scope, use, days open, status, owner, the desk's explanation (or
  *awaiting explanation*), the deadline; filters by business, limit type, status; *my coverage* by default.
- A breach opened: the limit and its history, the use over 20 days, why it moved (RO1) for that scope, the comment thread, the actions
  (review, approve a temporary increase, escalate, close) with the sign-off trail.
- Below: temporary limits expiring this week; RNIV entries due for review; reports awaiting sign-off.

**C. "Explorer" or "Risk map"** (independent challenge):

- The bank's hierarchy with VaR, ES and the worst limit use per node, a switch between the hierarchies (management, legal, limit letters),
  as the Risk map landing; plus concentration (RO8) by the dimension chosen, the stress matrix for a chosen scenario set (RO9), and a big
  command line with the edition's chips. Which of *Explorer* and *Risk map* is the third is for the mock-ups.

### 5.5 What it reuses

| From | Reused |
|---|---|
| **Drishti Risk** | the VaR and exposure explorers unchanged; the desk landing's tiles; the map landing's hierarchy switch, limit-use heat map and "Today" feed ([EDITIONS.md section 7](../admin/EDITIONS.md#7-drishti-risks-home-three-landings)); the BI datasets over the official records |
| **Drishti Market** | the Quality checks (stale, gaps, discount factors and forwards, vol surfaces) as the market-data line of the data-trust card ([EDITIONS.md section 13](../admin/EDITIONS.md#13-drishti-market)); the moves in σ of their own history |
| **Drishti Trading** | the limit use by book over N days from the official records, the P&L-against-VaR card, `near-limit`, the "my" mapping turned into **coverage** (which businesses a risk manager covers), and its rule that scoping never widens access ([EDITIONS.md section 14](../admin/EDITIONS.md#14-drishti-trading)) |
| **The mechanism** | one edition file, `home.landings`, `home.template`, `home.landing-by-role`, `lens.roles` (when wired), `X-Drishti-Lens`, edition gates (`DRS-4023` for a hidden command) |

---

## 6. A roadmap

Ordered by value and dependency (product owner, 2026-10-31); each row is **proposed** until its work starts, and a row without a status is unscheduled.

| Order | Rows | Why here | Status |
|---|---|---|---|
| 1 | **RO1** VaR change explanation | The most-asked question; needs nothing new from the bank; cheap | **built** (2.9.10, with memory, scale and mobile; decided, 2026-10-31) |
| 2 | **RO2** breaches and escalation (VaR limits first) | Turns "what breached" into a record; reuses the planned sign-off record | proposed (oversight, 2.9.14), on the sign-off record of 2.9.8 (decided, 2026-10-31) |
| 3 | **RO13** data trust, **RO14** model performance | Mostly reads of what the planned backtesting and market-data impact produce | RO13 proposed (regulatory reporting, 2.9.15, with the first CIO rows, the data-trust feeds); RO14 unscheduled |
| 4 | **RO11** the daily report and committee pack | On the planned report framework; the board pack is the planned board pack | the board pack proposed (oversight, 2.9.14); the daily report and the committee pack proposed (regulatory reporting, 2.9.15) |
| 5 | **RO15** position sensitivities (contract, loader, ladders), **RO3** the limit catalogue, then **RO4** sensitivity limits, **RO5** stop-loss, **RO6** position and notional | RO15 and the catalogue first: RO4 and RO8 need the sensitivities | RO15 proposed (first in sensitivities and capital, 2.9.11, with the factor dictionary); RO3 proposed (regulatory reporting, 2.9.15: the CRO decision queue needs it); RO4 proposed (sensitivities and capital, 2.9.11); RO5, RO6 unscheduled |
| 6 | **RO7** the RNIV register | Small; uses the sign-off record | unscheduled |
| 7 | **RO12** the Oversight lens: **mock-ups first, before any build** (none exists yet: section 11) | Once RO1 and RO2 exist | unscheduled (after RO1 and RO2; a separate lens, decided) |
| 8 | **RO8** concentration | Needs RO15 and reference data on positions | unscheduled (after RO15) |
| 9 | **RO9** the stress engine and limits, then reverse stress | The largest; STR.md's inputs | unscheduled |
| 10 | **RO10** liquidity | Needs volume and bid-offer data | proposed (liquidity, capital and climate) |

The rows are in the plan's [Risk oversight (candidates)](../architecture/IMPLEMENTATION_PLAN.md#risk-oversight-candidates-not-started) section, marked not
started.

---

## 7. Sensitivities data

Sensitivity limits (RO4), risk ladders, concentration of risk (RO8) and the factor-level reading of a VaR change (RO1) all need one
input Drishti does not hold today: **sensitivities per position (or trade), at risk-factor granularity**. This section says what the lake
holds, what is needed, a proposed data contract, its volume, and what is computed from it. The roadmap row is **RO15**
([section 6](#6-a-roadmap)), the prerequisite of RO4 and RO8.

### 7.1 What the lake holds today

| Kind | What it is | Why it is not enough |
|---|---|---|
| `frtb-sensitivity` (`FRTB`) | **One record per book** (`RESULT_ID` `FRTB-<desk>-<n>`, `BOOK`) with stored FRTB charges (`DELTA_CHARGE`, `VEGA_CHARGE`, `CURVATURE_CHARGE`, `TOTAL`, `BY_CLASS[]`) and a short list `SENSITIVITIES[]` of `RISK_CLASS`, `BUCKET` (`1` to `3`), `MEASURE` (`Delta`, `Vega`) and `VALUE` in USD, at most six elements a book; **demo data only: 30 records** in `data/testdata/market-risk/frtb-sensitivity/`, a snapshot then daily changes ([the sample page](../contracts/samples/market-risk/frtb-sensitivity.md); `config/packs/market-risk/contracts/frtb-sensitivity.contract.yaml`; the mnemonic `FRTB` in `config/packs/market-risk/pack.yaml`) | Book grain, not position or trade: no drill, no arbitrary scope, no intraday. Regulatory buckets only (a bucket is a sector or a rating group, not a curve and tenor); no risk factor, curve, tenor or issuer: no DV01 ladder, no CS01 by issuer. The charges are stored computed figures that FRTB.md retires ([COMPUTED_ATTRIBUTES.md section 4.9](../architecture/COMPUTED_ATTRIBUTES.md#49-frtb-sensitivity-frtb), finding F18) |
| `trade-valuation.DV01` (`TVAL`) | One number per trade and date, total DV01 in USD ([COMPUTED_ATTRIBUTES.md section 4.1](../architecture/COMPUTED_ATTRIBUTES.md#41-trade-valuation-tval); `config/packs/market-risk/contracts/trade-valuation.contract.yaml`) | No curve, no tenor: a parallel shift only; no other measure |
| The proposed `sensitivity` table of FRTB.md | One row per trade and risk factor, with the factor's parts (class, type, qualifier, curve, tenor, underlying tenor), FRTB and SIMM buckets ([FRTB.md section 3.2](../contracts/FRTB.md#32-the-shared-sensitivity-table-position-sensitivity-sens)) | Proposed, not built; a row per factor (tens of rows per trade), no position grain, no intraday rule. Its **factor fields** are the right ones and are reused below |

### 7.2 What oversight needs

Sensitivities **per position (or trade), at risk-factor granularity**, from the bank's pricing and risk system: the **same system and run
that produce the scenario PVs** ([VAR_GUIDE section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency)),
so the two agree on the trades, the market and the model.

| Risk type | Sensitivity | Granularity |
|---|---|---|
| Interest rates | DV01 (PV01) | curve (OIS, projection, government …) × tenor bucket × currency |
| Credit | CS01 | issuer or name × tenor (plus rating and sector, from reference data) |
| FX | delta | currency pair, in the reporting currency |
| Equity | delta (plus beta) | issuer or index |
| Commodity | delta | underlying × delivery tenor |
| Volatility | vega | surface × expiry (× strike or moneyness where limits need it) |
| Non-linear | gamma, curvature | where tracked |
| Inflation | IE01 | index × tenor |

### 7.3 A proposed data contract: `position-sensitivity`

**Recommendation: a new kind, `position-sensitivity`, not an extension of `frtb-sensitivity`.** Reasons: `frtb-sensitivity` is book
grain with stored charges, and FRTB.md retires it (its section 14); its key (`RESULT_ID` per book) cannot carry positions or intraday
batches; widening it would mix a computed regulatory result with a fundamental input, which the rules forbid
([COMPUTED_ATTRIBUTES.md section 1.2](../architecture/COMPUTED_ATTRIBUTES.md#12-the-rule-for-deciding-which)). The new kind takes FRTB.md's factor fields
and **becomes the per-trade `sensitivity` input FRTB.md proposes**, in a record-per-position layout, so FRTB SA, SIMM, P&L explain, stress
by sensitivities and oversight read one table. Decision 9 ([section 9](#9-decisions-for-the-product-owner)).

**Grain and ids, as the VaR inputs.** One record per position (or trade) per business date and batch, the same grain rules as
`scenario-pv`: `GRAIN` (`TRADE` or `POSITION`), `TRADE_ID` or `POSITION_ID` (a position its source sends whole names its trades in
`TRADE_IDS`), `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND` (`FULL` or `INTRADAY`), `COMPUTED_AT`, `STATUS` (`ok`, `proxied`,
`failed`), `MODEL_VERSION`, and the placement columns the VaR inputs carry (`TT_BOOK`, `DESK`, `LEGAL_ENTITY`, `LIMIT_LETTER`,
`PRODUCT_TYPE`, `TRADE_STATUS`, `MATURITY_DATE`). Key: `SENS-<trade or position>-<BATCH_ID>`. JSON Lines, gzip, into the lake by the
same loaders (`lake build`, the parallel loader), **Delta or Iceberg**, partitioned by `business_date`, sorted by book as the vectors are.

**The sensitivities: a sparse list**, only the non-zero factors:

| Field of `SENSITIVITIES[]` | Meaning | Example |
|---|---|---|
| `RISK_FACTOR` | the factor's name in the naming standard below | `IR/CURVE/USD/SOFR/10Y` |
| `RISK_CLASS`, `FACTOR_TYPE`, `QUALIFIER`, `CURVE`, `TENOR`, `UNDERLYING_TENOR`, `STRIKE` | the parts of the name (FRTB.md's fields; `STRIKE` a strike or moneyness for vega where limits need it); derived from `RISK_FACTOR` when absent | `IR`, `CURVE`, `USD`, `SOFR`, `10Y` |
| `MEASURE` | `DV01`, `CS01`, `IE01`, `DELTA`, `BETA`, `VEGA`, `GAMMA`, `CURVATURE_UP`, `CURVATURE_DOWN` | `DV01` |
| `VALUE` | the P&L in `CURRENCY` for the measure's standard move: +1 bp (DV01, CS01, IE01), +1% of the spot (delta), +1 vol point (vega); gamma per 1% squared; beta a ratio | `-1250.5` |
| `CURRENCY` | the currency of `VALUE` (converted to the reporting currency at today's rate when summed) | `USD` |
| `BUCKET` | optional: a bucket the source already assigns; Drishti's mapping below decides otherwise | `USD` |

```json
{"SENSITIVITY_ID": "SENS-POS-BOOK-RATES-1~MX-30000951-B-SENS-2026-09-30-01", "GRAIN": "POSITION",
 "POSITION_ID": "POS-BOOK-RATES-1~MX-30000951", "SOURCE_SYSTEM": "MUREX", "BUSINESS_DATE": "2026-09-30",
 "BATCH_ID": "B-SENS-2026-09-30-01", "RUN_KIND": "FULL", "STATUS": "ok", "TT_BOOK": "BOOK-RATES-1", "DESK": "DESK-RATES",
 "SENSITIVITIES": [
   {"RISK_FACTOR": "IR/CURVE/USD/SOFR/5Y",   "MEASURE": "DV01", "VALUE": -820.0,  "CURRENCY": "USD"},
   {"RISK_FACTOR": "IR/CURVE/USD/SOFR/10Y",  "MEASURE": "DV01", "VALUE": -1250.5, "CURRENCY": "USD"},
   {"RISK_FACTOR": "VOL/IR/USD/SOFR/1Y/10Y", "MEASURE": "VEGA", "VALUE": 310.0,   "CURRENCY": "USD"}]}
```

(Illustrative; the parts fields are left out for brevity.)

**The risk-factor naming standard** (decision 10). One text name per factor, slash-separated parts, so a name can be read, sorted and
split without a lookup:

| Class | Name | Example |
|---|---|---|
| Rates | `IR/CURVE/<ccy>/<curve>/<tenor>` | `IR/CURVE/EUR/ESTR/2Y` |
| Inflation | `INF/<index>/<tenor>` | `INF/USCPI/10Y` |
| Credit | `CR/SPREAD/<issuer id, LEI preferred>/<BOND or CDS>/<tenor>` | `CR/SPREAD/LEI:5493001KJTIIGC8Y1R12/CDS/5Y` |
| FX | `FX/SPOT/<ccy>` (the currency's value in the reporting currency) | `FX/SPOT/JPY` |
| Equity | `EQ/SPOT/<ISIN or index>` | `EQ/SPOT/SPX` |
| Commodity | `CM/PRICE/<underlying>/<location>/<delivery tenor>` | `CM/PRICE/WTI/CUSHING/3M` |
| Volatility | `VOL/<class>/<qualifier>/<curve or underlying>/<expiry>[/<underlying tenor>][/<strike or moneyness>]` | `VOL/EQ/SPX/SPX/6M/ATM` |

A bank that names factors otherwise sends its names with a **factor mapping** (its name → the standard name), loaded as configuration;
an unmapped name is refused, never guessed.

**Buckets, configurable** (decision 11). A bucket file in the market-risk pack (proposed name `sensitivity-buckets.yaml`) maps factors to
the grids limits use: the **FRTB buckets** (from FRTB.md's parameter tables) and the **bank's own limit grids** (for example rates tenors
0–2Y, 2–5Y, 5–10Y, 10Y+; credit by rating and sector; vega by expiry band). Each limit in the catalogue (RO3) names its grid; a factor
falls in exactly one bucket of each grid, checked at start.

**Intraday,** as scenario PVs ([VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)):
an `INTRADAY` batch carries only the changed positions; a record **replaces** that position's record whole (never merged factor by
factor); a cancel is `TRADE_STATUS` `CANCELLED` with no list; files appended to the lake, or the Kafka stream.

**Refusals and data-quality checks** at the load (each counted, the first reasons listed, as the VaR load does,
[VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks)):

| Check | Outcome |
|---|---|
| A factor name that does not parse, or is not mapped | the element rejected, counted per source |
| `CURRENCY` missing, or without FX on the date | the element rejected |
| `VALUE` null, NaN or infinite | the element rejected (a failed factor is never zero) |
| The same factor and measure twice in one record | the record rejected |
| One full run per date and source, `GRAIN` with its id | the record or the date refused, as for scenario PVs |
| A position with scenario PVs but **no sensitivities** (or the reverse) | a completeness warning by desk on the data-trust card (RO13), never an error |
| `STATUS` `proxied` | kept and counted; the proxied share shown beside every sum |
| A factor outside every bucket of a grid a limit uses | a warning naming the factor and the grid |

### 7.4 Volume

Sparse: a vanilla swap has 10 to 20 non-zero curve points, a bond its curve points and one issuer's spreads, an option adds vega points;
**10 to 50 non-zero factors a position** is the working range.

| At 2.3M positions | 10 factors a position | 50 factors a position |
|---|---|---|
| Elements a day | 23 million | 115 million |
| JSON a day (about 110 bytes an element, 400 a record's header) | about 3.5 GB (0.4 GB gzipped) | about 13.6 GB (1.5 GB gzipped) |
| Lake a day (Parquet: factor names dictionary-encoded, one double a value; about 10 to 15 bytes an element) | about 0.3 GB | about 1.7 GB |
| Lake over 7 years (about 1,764 business days, one full run a day) | about 0.5 TB | about 3 TB |
| Load with the parallel Java loader (32 vCPU, local NVMe) | about 1 minute | about 2 to 3 minutes |

These are **estimates, not measurements**¹. For comparison, the scenario PVs of the same positions are 20 GB or more of JSON a day
([PARALLEL_LOADER.md section 11](../architecture/PARALLEL_LOADER.md#11-measured-and-the-23m-estimate)).

¹ Element and record sizes from the example above; Parquet sizes from dictionary encoding of a few hundred thousand distinct factor names
plus a double per element; the load time scaled by bytes from the parallel loader's 2.3M scenario-PV estimate (about 92 µs of CPU per
5.4 KB position, itself extrapolated from 10K). To be measured with RO15 on the 10K test data and on the product owner's server.

### 7.5 What is computed from it (on demand, never stored)

| Computation | How | For |
|---|---|---|
| **Sums** by desk, book, any scope, and by bucket of any grid | additive: a scope's sensitivity to a factor is the sum of its positions'; the Java summing pattern of VaR (a factor dictionary, per-position sparse rows, per-node totals), as the person asking | every row below |
| **Utilisation against sensitivity limits** | each limit's scope and bucket summed, against its two sides | RO4 |
| **Ladders** | a scope's grid (DV01 by currency × tenor, CS01 by rating × tenor, vega by expiry …), each cell against its limit, drilling to the positions | RO4, the Limits page |
| **Concentration of risk** | CS01 by issuer and sector, DV01 by curve, delta by issuer and currency: top N, shares, Herfindahl | RO8 |
| **The "why" overlay of the VaR change** | Σ sensitivity(t−1) × factor move(t−1 → t) by factor, beside the exact steps, labelled approximate, with its residual | RO1 ([section 4.1](#41-ro1-var-change-explanation)) |
| **Hedge suggestions** (later) | for a breached bucket, the notional of a standard hedge instrument that brings it back, and that hedge's VaR effect through the what-if | the what-if, after RO4 |
| **FRTB standardised (SBM)** | weighted sensitivities, buckets and correlations from the same table | regulatory step 6 ([REGULATORY_VAR_REPORTING section 8.2](../guides/REGULATORY_VAR_REPORTING.md#82-the-order-to-build-in)) |
| **Stress by sensitivities** | Σ sensitivity × shock for any scenario | RO9, [STR.md](../contracts/STR.md) |

**RO15, the roadmap row: "Position sensitivities (data contract, loader, ladders)".** The same row as MC1 of the risk cubes, whose
data chapter extends this contract with FRTB and SIMM buckets, curvature inputs and the factor dictionary ([MR_CUBE_LENS.md section 7.9](MR_CUBE_LENS.md#79-d8-position-sensitivity-sens-new)). The contract and its generated documents; synthetic
demo data from the test-data generators (consistent with the demo's scenario PVs); the loader and its checks; the factor naming standard
and the bucket file; the summing engine and a first ladder (DV01 by currency and tenor). Size **M**. The prerequisite of RO4 and RO8.
**Tests:** sums by bucket equal the sum of positions; a position under two hierarchies sums once per node; an intraday batch replaces a
position whole; each refusal; the proxied share; overlapping buckets in a grid refused at start.

---

## 8. Data the bank must supply

| Data | Grain and cadence | Needed by | Note |
|---|---|---|---|
| Limits of every type, with owners, escalation chain, warning level, validity, approver, limit letter | Per limit, daily or on change, from the limit system | RO2, RO3 and every limit | VaR limits already arrive as `var-limit` |
| Sensitivities by risk factor: DV01 by curve and tenor, CS01 by issuer and tenor, FX delta, equity delta and beta, commodity delta, vega, gamma, IE01 ([section 7.2](#72-what-oversight-needs)) | Per position (or trade) per date, intraday changes like scenario PVs, from the pricing system that produces the scenario PVs | RO15, RO4, RO8, RO1's overlay, RO9 (sensitivity stress), FRTB SA | the proposed `position-sensitivity` kind ([section 7.3](#73-a-proposed-data-contract-position-sensitivity)); a risk-factor naming standard and the bank's limit buckets |
| Actual and hypothetical P&L | Per desk (better per trade) per date, from product control | RO5, RO14, backtesting | The same feed as the planned P&L feed |
| Average daily volume and bid-offer | Per instrument (or product and tenor bucket) per date, from vendors or exchanges | RO10 | Licensing is the bank's |
| Security master attributes: issuer, sector, country of risk | Per instrument | RO8 | Often already in the trade or position feeds |
| RNIV entries and their quantification | Per entry, monthly | RO7 | Entered in Drishti or loaded from the bank's register |
| Stress scenarios with machine-readable shocks (with units) and per-trade scenario P&L | Per scenario; per trade per run | RO9 | STR.md section 3 |
| Trade lifecycle markers (new, amended, cancelled) | Per trade per date | RO1's amendment split | `TRADE_ACTION`, `TRADE_STATUS` already on trades |
| The coverage map (which risk manager covers which business) | Per person, on change | RO2, RO12 | Configuration or the directory's groups |

---

## 9. Decisions for the product owner

**Decided (product owner, 2026-10-31):** every recommendation accepted ("I am aligned with your recommendations"); each choice is under its question.

1. **The lens.** A separate Drishti Oversight lens (recommended), built after RO1 and RO2; or a fourth landing inside Drishti Risk now.

   **Decided (product owner, 2026-10-31):** a separate Drishti Oversight lens, built after RO1 and RO2.
2. **First build.** RO1 (VaR change explanation) and RO2 (breaches, VaR limits first) as the next candidates beside the board pack; or later.

   **Decided (product owner, 2026-10-31):** RO1 and RO2 in the oversight and mobile phase, beside the board pack; placed by the themed plan (2026-11-01): RO1 built in 2.9.10, RO2 with the board pack in the oversight phase (2.9.14).
3. **The explanation's order.** The default sequence of section 4.1 (restatement, hierarchy, trades out, window roll, market, trades in);
   the Shapley variant on request only.

   **Decided (product owner, 2026-10-31):** the default sequence of section 4.1; the Shapley variant on request only.
4. **Temporary limits.** (a) recorded in Drishti, applied by the bank's limit system; or (b) a Drishti-owned overlay used the same day.

   **Decided (product owner, 2026-10-31):** (a): recorded in Drishti, applied by the bank's limit system.
5. **One limit table.** `risk-limit` for every measure with `var-limit` read as its VaR rows (STR.md open question 4); or `var-limit`
   kept separate.

   **Decided (product owner, 2026-10-31):** one `risk-limit` table for every measure, `var-limit` read as its VaR rows.
6. **Counterparty risk in the lens.** Read exposure for concentration (recommended), or leave counterparty risk to a credit-risk lens.

   **Decided (product owner, 2026-10-31):** counterparty exposure read-only in the lens (for concentration).
7. **The governance feature.** A new feature flag and powers (`breach.*`, `limit.temporary`, `rniv.*`, `report.sign`) on the planned sign-off
   record; or reuse write-back's approval policy.

   **Decided (product owner, 2026-10-31):** reuse write-back's approval policy.
8. **One near-limit threshold.** Unify the explorer's 75%/90%, Trading's 80% and the catalogue's `WARNING_AT` into the limit's own
   warning level with a site default.

   **Decided (product owner, 2026-10-31):** one near-limit threshold: the limit's own warning level, site default 80%, configurable.
9. **Sensitivities contract** ([section 7.3](#73-a-proposed-data-contract-position-sensitivity)). A new `position-sensitivity` kind
   (recommended), which also becomes FRTB.md's proposed per-trade `sensitivity` input and lets `frtb-sensitivity` retire; or extend
   `frtb-sensitivity`.

   **Decided (product owner, 2026-10-31):** a new `position-sensitivity` kind (it becomes FRTB.md's per-trade `sensitivity` input; `frtb-sensitivity` retires).
10. **The risk-factor naming standard.** Drishti's (FRTB.md's factor fields, recommended) with a mapping from the bank's names; or the
    bank's names as delivered, mapped at load.

   **Decided (product owner, 2026-10-31):** Drishti's factor naming standard with a mapping from the bank's names.
11. **Whose buckets.** The bank's own limit buckets as configuration beside the FRTB buckets (recommended: both, each limit naming its
    grid); or FRTB buckets only.

   **Decided (product owner, 2026-10-31):** both: the FRTB buckets and the bank's own limit grids, each limit naming its grid.
12. **Shared command lists.** Allow an edition file to extend another (`extends: risk`) to stop lists drifting; or keep each file whole.

   **Decided (product owner, 2026-10-31):** yes: an edition file may extend another (`extends:`).
13. **Mock-ups.** Two or three variants of the Appetite, Breaches and Explorer or Risk map landings when the lens is scheduled.

   **Decided (product owner, 2026-10-31):** two or three variants of each landing when the lens is scheduled, before any build.
14. **The RO1, RO2 and board pack mock-ups** ([section 11](#11-mock-ups-the-screens-to-build)). Pick one option each, or keep the others.

   **Decided (product owner, 2026-11-01):** one screen per item: A is the default view, B and C are views on the same screen (a view
   switch, kept in the address and remembered per person, one API). RO1 is a tab of the VaR page drawn with the standard panel kinds;
   RO2 is Sutras with write-back for its actions; the board pack is a report definition whose `layout:` is A by default, B or C by choice.

---

## 10. Glossary

| Term | In plain words |
|---|---|
| **First, second, third line** | The desks that take risk; the risk function that oversees them independently; internal audit that checks both |
| **CRO** | Chief risk officer: heads the second line |
| **Risk appetite** | How much risk the board is willing to take, written as limits on a few bank-wide measures |
| **Limit letter** | The document that delegates a limit from one level to the next (CRO to a business head to a desk) |
| **Utilisation, limit use** | The measure divided by its limit; above 100% is a breach |
| **Near breach** | Use above the limit's warning level (often 80%) but not above 100% |
| **Temporary limit increase** | A limit raised for a set period, approved, after or before a breach |
| **Stop-loss** | A limit on cumulative losses over a month or year |
| **DV01, CS01, vega, FX delta** | The P&L for a 1 basis point move in rates, a 1 basis point move in credit spreads, a 1 point move in volatility, a 1% move in an exchange rate |
| **Risk factor** | One market variable a position is sensitive to: a curve point (USD SOFR 10Y), an issuer's spread at a tenor, an exchange rate, a vol surface point |
| **IE01** | The P&L for a 1 basis point move in an inflation rate |
| **Bucket** | One cell of a limit grid (the 5-year USD rate; BBB credit spreads at 2 to 5 years) |
| **Backtesting** | Checking how often the day's actual loss exceeded the VaR forecast for it; about 2.5 times in 250 days at 99% |
| **Actual, hypothetical P&L** | The day's real P&L (cleaned of fees and so on); the P&L of yesterday's positions at today's prices |
| **P&L attribution** | Explaining a day's P&L by its causes (market moves by factor, new trades, time) |
| **RNIV** | Risks not in VaR: risks the model misses, listed, quantified and held as capital or reserves |
| **Proxy** | A stand-in price or history used for an instrument without its own |
| **Window roll** | Each day the oldest historical scenario leaves the VaR window and yesterday's move enters |
| **Restatement** | Yesterday's inputs corrected after the official run; a new result version |
| **Waterfall** | A chart of a change split into steps that add up to it |
| **Shapley** | A way to split a change among causes by averaging over every order of applying them |
| **Concentration** | Too much of the risk in one issuer, sector, country, currency or tenor |
| **Days to liquidate** | How many days it would take to sell a position without moving the market, at a share of the daily volume |
| **Reverse stress** | Starting from a loss that would hurt, finding the market moves that would cause it |
| **Lens** | One person's focused view of Drishti, opened by a link, hiding what they do not need; it does not secure |
| **Maker-checker** | One person prepares, another approves; nobody approves their own work |

---

## 11. Mock-ups: the screens to build

**The lens's three landings have no mock-up yet.** Section 5.4 outlines them (Appetite; Breaches and escalations; Explorer or Risk map)
in words only; they are mocked up when the lens (RO12) is scheduled, two or three of each, and copied into `docs/lenses/mockups/oversight/`
as in [RISK_LENS.md section 11](RISK_LENS.md#11-mock-ups-the-screens-to-build) ([README.md](README.md#mock-ups)).

**The three features built before the lens have their mock-ups**: RO1's screen, RO2's screen and the board pack, three options each, a
phone variant where the screen is on a phone tier, under [`docs/architecture/mockups/`](../architecture/mockups/ro1-var-change/A.html):
self-contained HTML pages (system fonts, everything inline, nothing from a CDN), dark by default and light with `#light` at the end of the
address. **Decided (product owner, 2026-11-01): one screen per item.** Option A is the default view; B and C are alternative views of the
same data on that screen, behind a view switch, the chosen view kept in the address and remembered per person, every view on the same API.
For the board pack, A is the default PDF layout and B and C are layout choices of its report definition. Screenshots are taken when each
view is built. Figures are the demo's on 2026-09-30 where the guides quote them; the
rest are illustrative and each footer says which. **The checklists below are the source of truth at build time**: a panel is done when its
row says built.

| Screen | Options | Phone | Views |
|---|---|---|---|
| RO1 VaR change explanation | [A](../architecture/mockups/ro1-var-change/A.html) the waterfall with each step's contributors · [B](../architecture/mockups/ro1-var-change/B.html) the bridge of every node · [C](../architecture/mockups/ro1-var-change/C.html) the answer in sentences, Shapley beside the sequence | [phone](../architecture/mockups/ro1-var-change/phone.html) | A is the default view; B and C are views on the same screen (**Waterfall · Matrix · Sentences**) |
| RO2 breaches and escalation | [A](../architecture/mockups/ro2-breaches/A.html) the queue with the opened breach beside it · [B](../architecture/mockups/ro2-breaches/B.html) a board of the statuses · [C](../architecture/mockups/ro2-breaches/C.html) limits first with a timeline | [phone](../architecture/mockups/ro2-breaches/phone.html) | A is the default view; B and C are views on the same screen (**Queue · Board · Timeline**) |
| The board pack | [A](../architecture/mockups/board-pack/A.html) the answer first, one subject a page · [B](../architecture/mockups/board-pack/B.html) landscape pages like a board deck · [C](../architecture/mockups/board-pack/C.html) a dense committee register | desk tier: none | A is the default PDF layout; B and C are layout choices of the report definition (`layout: pages · landscape · register`) |

### 11.1 RO1: VaR change explanation

[Open option A](../architecture/mockups/ro1-var-change/A.html). **A is the default view; B and C are views on the same screen.** The
question is *why did this number move*, and a waterfall answers it in one look: the start, each cause to scale and the end, with the
selected step's contributors beside it and the states under it for the reader who checks, so it opens first. B's bridge of every node is
the second line's sweep across the hierarchy (the **Matrix** view); C's sentences are the phone's and the brief's form, with Shapley
beside the sequence (the **Sentences** view). One tab, one answer, three views. Design in [section 4.1.1](#411-ro1-the-build-design).

| # | Panel | Contents on the mock-up | Data, API or contract | Interaction | Status |
|---|---|---|---|---|---|
| E1 | Header and pickers | *VaR · why it moved*, `VAR EXPLAIN BANK`; From (yesterday, month-end, last board, a date), To (official, now), Sequence or Shapley, horizon, confidence, hierarchy; Export xlsx, Copy link | `GET /api/v1/var/explain` | change a picker: every panel redraws | built: From, To, Download the working (.xlsx), the view switch; the order is the setting; Shapley planned; 1-day at the configured confidence (`confidence` on the API) |
| E2 | Tiles | VaR 2026-09-29, VaR 2026-09-30 (112,041,646), the change (−0.60m, *the steps add up exactly*), limit use 75.1% → 74.7%, the VaR scenario's date before and after | the explanation's states and limit line | — | built |
| E3 | Tab | *Why it moved* beside the VaR page's tabs, drawn with the standard panel kinds | the VaR page | — | built |
| E3a | View switch | **Waterfall · Matrix · Sentences** above the panels; Waterfall the default | `view=` in the address; the person's settings | switch: redraw from the same answer | built |
| E4 | Waterfall | VaR 09-29, restatement, hierarchy and rules, trades out, oldest day dropped, market, newest day added, trades in, amendments, VaR 09-30; up orange, down blue; the order and window setting said under it | the steps | click a bar: its contributors (E6 to E8) | built (a step opens its drivers from the Steps table beside the chart) |
| E5 | States | S0 to S7 (and S7′ for amendments): what changed, trades, vectors, scenario days, hierarchy, VaR, VaR scenario, step effect; Σ = the change | the states | — | built |
| E6 | Market contributors | each desk's component VaR change S4 → S5, share; Σ = the step | component VaR in both states | click a desk: its books, then positions | built (each child or book; the drill to positions is the page's own) |
| E7 | Trades out | count by reason (matured, cancelled, closed); top 5 by incremental VaR; *the others and the interaction*; Σ = the step | `pnl-vector` / `scenario-pv` status, incremental VaR | click a trade: its page in Risk | built (each trade's reason: no vector on the later date, or cancelled) |
| E8 | Window | the day dropped and the day added with their ranks and effects | the scenario sets | — | built |
| E9 | Order-free view | each desk's component VaR today less yesterday; Σ = the change | component VaR on both dates | — | built |
| E10 | Risk-factor overlay | the note that it needs position sensitivities; when loaded, Σ sensitivity × move by factor with its residual | `position-sensitivity` (proposed) | — | not built: needs `position-sensitivity` (planned); the screen says so |
| E11 | Matrix view (option B) | the hierarchy down, the steps across, VaR before and after, the change and limit use; a selected cell's contributors and a generated sentence | the explanation per node | sort; click a cell | built (a table, the hierarchy down, the steps across; a row opens its sentence and its steps) |
| E12 | Sentences view (option C) | the answer in sentences; sequence and Shapley side by side by cause, the difference; both sum to the change | the same answer; `method=shapley` | the Sequence / Shapley switch | built (the Shapley column says planned) |
| E13 | Phone | the answer (must), the steps as bars (glance), contributors (more), *Open on desktop* (desk) | the same API, compact | tap a step | built |

### 11.2 RO2: breaches and escalation

[Open option A](../architecture/mockups/ro2-breaches/A.html). **A is the default view; B and C are views on the same screen.** The
business risk manager's job is a queue worked one item at a time, and A keeps both on one screen: the queue worst first with deadlines,
and the opened breach with everything needed to act (chain, use, why it moved, explanation, the increase to decide, the thread), so it
opens first. B's board (the **Board** view) shows the workflow's shape; C's limits first with a timeline (the **Timeline** view) is
independent challenge. Each view is a Sutra of the same page kind; the actions are forms with write-back. Design in
[section 4.2.1](#421-ro2-the-build-design).

| # | Panel | Contents on the mock-up | Data, API or contract | Interaction | Status |
|---|---|---|---|---|---|
| Q1 | Header and filters | *Breaches and escalation*, `BRCH`; my coverage or all; open, near, this week, period; limit type, business, hierarchy; *each limit is compared at its own horizon and confidence* | `GET /api/v1/breaches` | filter | not built |
| Q1a | View switch | **Queue · Board · Timeline**; Queue the default | `view=` in the address, a Sutra per view of `breach-queue`; the person's settings | switch: the same filters and opened breach | not built |
| Q2 | Tiles | open (3, 1 escalated), awaiting explanation, awaiting review, temporary increases, near (≥ 80%), closed or withdrawn this week | the same | click: filter the queue | not built |
| Q3 | Queue | id, limit and scope (with the limit id), use bar, VaR and limit, business days, status, with whom, explanation, next deadline; then near limits without a record (BOOK-RATES-2 93.6%, DESK-STRUCT 90.8%, LE-NY 80.1%) | `limit-breach`, `var-limit`, `var-official` | click: open the breach | not built |
| Q4 | Closed and withdrawn | this week's, with the reason (a withdrawn breach is kept) | `limit-breach` versions | open | not built |
| Q5 | Breach header and chain | id, status, limit, lens, the chain with the step now; use, VaR and limit; detection date and use; owner; age | `limit-breach`, the chain | — | not built |
| Q6 | Use over 20 business days | the use line with the limit and the near level, the detection date marked | `var-official` history | hover a day | not built |
| Q7 | Why it moved since detection | RO1 for the scope from the detection date to today | RO1's API | open the full explanation | not built |
| Q8 | Explanation and actions | cause, action, expected by, progress; Explain, Review (accept, ask for more), Escalate now, Close (off while above the limit) | `breach-action`, `POST /api/v1/breaches/{id}/actions` | each action opens its form with a required comment | not built |
| Q9 | Temporary increase | TLI amounts, dates, requester, recommendation, approver, use if it lapsed; a comment; Approve, Approve with conditions…, Reject; the *not applied* rule | `limit-request`, write-back approval | approve or reject (four eyes) | not built |
| Q10 | Thread | comments anchored to the breach | comment threads | reply | not built |
| Q11 | Board view (option B) | status columns with cards and a drawer | the same | open a card | not built |
| Q12 | Timeline view (option C) | every VaR limit by use; breaches' statuses over 20 days; the escalation clock | the same | click a bar | not built |
| Q13 | Phone | open breaches (must), the decision waiting with full-width buttons (glance), near limits (glance), chart, why and thread (more), *Open on desktop* (desk) | the same API, compact | approve, reject | not built |

### 11.3 The board pack

[Open option A](../architecture/mockups/board-pack/A.html). **A is the default PDF layout; B and C are layout choices of the report
definition**, not screens. A board member reads the first page and perhaps one more: A puts the answer, the headline figures and what the
committee must decide on page 1, then one subject a page with its figures printed beside every chart, and ends with the sign-off and
lineage a regulator or auditor asks for. B (`layout: landscape`) presents like a board deck with commentary in an appendix; C
(`layout: register`) is a complete committee register in tables. The run is reviewed and signed on the report framework's screen. Design in
[section 4.11.1](#4111-the-board-pack-the-build-design).

| # | Panel | Contents on the mock-up | Data, API or contract | Interaction | Status |
|---|---|---|---|---|---|
| P1 | Run header | `VAR REPORT board-pack 2026-Q3`, the run and its freeze time, the style, the page count, the signatures; meeting, data date, since, style; *Open review and sign-off*, *Download PDF* | the report framework's run | download (after the last approval) | not built |
| P2 | Page header and footer | the bank's logo, the title, the confidentiality line; the data date, run, *from the official records*, page n of N | the house style | — | not built |
| P3 | Page 1, the answer | meeting and period; the answer; six tiles; for decision and for noting; contents | the definition's facts | — | not built |
| P4 | Page 2, VaR over the period | the daily VaR against the limit and 80%; period statistics of VaR and stressed VaR; VaR by risk type | `var-official`, period statistics | — | not built |
| P5 | Page 3, what changed | RO1 from the last board's data date; the order-free view by desk; one sentence | RO1, `governance-calendar` | — | not built |
| P6 | Page 4, limits and breaches | each VaR limit's use, highest, days near, days in breach; the period's breaches with commentary | `var-limit`, `limit-breach`, `breach-action` | — | not built |
| P7 | Page 5, backtesting | the 250-day chart; zone, counts, multiplier; the exceptions with drivers | the backtesting engine | — | not built |
| P8 | Page 6, sign-off and lineage | maker, checker, approver; the checks; official records, data versions, definition and style revisions, the run | the framework's sign-off and lineage | — | not built |
| P8a | Layout choice | `layout: pages` (the default), `landscape`, `register` in the definition; the same run, checks and sign-off | the report definition | — | not built |
| P9 | `layout: landscape` (option B) | one message a page, big tiles, three takeaways | the same | — | not built |
| P10 | `layout: register` (option C) | every measure against the last board; the breach register; temporary increases | the same | — | not built |
