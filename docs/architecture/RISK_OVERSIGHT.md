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
the [implementation plan](IMPLEMENTATION_PLAN.md#risk-oversight-candidates-not-started), not started. Every "built" or "partly" statement
cites the document and section it was read from on 2026-10-31 (develop at b22ff3850); where the plan names a row, the row is cited.

**The request (product owner, 2026-10-31):** "please write detailed document"; "do you think a lens for risk oversight will be a good
idea?" The short answer is in [section 5.1](#51-the-answer-yes-a-separate-lens-built-after-two-features-exist): **yes, a separate lens**,
built after the two features that give it something of its own to show (the VaR change explanation and the breach workflow).

**Read with:** [VAR_GUIDE.md](../guides/VAR_GUIDE.md) (the VaR engine as built), [REGULATORY_VAR_REPORTING.md](REGULATORY_VAR_REPORTING.md)
(backtesting, the P&L feed and the report framework with sign-off, 2.9.8 and 2.9.9), [EDITIONS.md](EDITIONS.md) (how an edition or a lens
is defined), [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md) (compute, do not store), [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md),
and the contracts [STR.md](../contracts/STR.md) (stress and the proposed limit table), [FRTB.md](../contracts/FRTB.md) (sensitivities) and
[PNL.md](../contracts/PNL.md) (P&L explain).

## Contents

1. [Who market risk oversight is](#1-who-market-risk-oversight-is)
2. [Their questions: a day, a month and a year](#2-their-questions-a-day-a-month-and-a-year)
3. [What they need, area by area, and Drishti's status](#3-what-they-need-area-by-area-and-drishtis-status)
4. [Design sketches for the gaps](#4-design-sketches-for-the-gaps)
5. [The Drishti Oversight lens: recommendation and design](#5-the-drishti-oversight-lens-recommendation-and-design)
6. [A roadmap](#6-a-roadmap)
7. [Data the bank must supply](#7-data-the-bank-must-supply)
8. [Decisions for the product owner](#8-decisions-for-the-product-owner)
9. [Glossary](#9-glossary)

**Status words.** **Built**: on develop, documented as built. **Partly**: some of it is built; the cell says what is missing.
**Planned (row)**: a row of the implementation plan names it. **Not built**: neither built nor planned. **Branch**: built on a branch
that is not yet on develop.

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
([EDITIONS.md section 14](EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)). The second line asks *are we within appetite, is this
number right, and who has approved what?* The questions overlap; the defaults, the duties and the actions do not.

---

## 2. Their questions: a day, a month and a year

### 2.1 The morning meeting (07:30 to 09:00)

The overnight batch is in; the official end-of-day figures of yesterday are captured. The head of market risk runs a meeting with the
business risk managers.

| Question | Drishti today | Gap |
|---|---|---|
| *Are we within appetite?* Bank VaR and ES against the bank limit | **Built**: the Risk lens's desk landing tiles (VaR and ES of BANK, the backtest, limits in breach) ([EDITIONS.md section 7](EDITIONS.md#7-drishti-risks-home-three-landings)); the official record's `LIMIT` and `LIMIT_USE` per node ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) | Appetite measures other than VaR (stressed VaR is planned, 2.9.7 row 2c; stress loss is stored per desk, not computed) |
| *What breached?* | **Partly**: VaR limits only: the Risk map landing's "Today" feed and the limit-use heat map ([EDITIONS.md section 7](EDITIONS.md#7-drishti-risks-home-three-landings)); the Trading desk-head landing's Breaches and Near limit tiles ([EDITIONS.md section 14](EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)); stress limit use per desk (derived, [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived-297)) | Sensitivity, stop-loss, position and notional limits; a breach as a record with status and commentary |
| *Why did VaR move?* | **Partly**: captures compare with now (VaR change, members added and removed, components that moved most, [VAR_GUIDE section 6](../guides/VAR_GUIDE.md#6-captures-and-compare)); the scenario matrix shows a node's scenarios across dates (**branch**, `SMAT`, not on develop) | A decomposition that adds up exactly: positions, market, window roll, restatements, hierarchy changes ([section 3.5](#35-why-it-moved-var-change-explanation-day-over-day)) |
| *Which scenario drives it?* | **Built**: the P&L path tab, the 25 worst scenarios, the contributors on a scenario day ([VAR_GUIDE section 3.4](../guides/VAR_GUIDE.md#34-the-tabs)) | — |
| *Is the number trustworthy today?* | **Partly**: load checks and rejections in the provenance ([VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks-as-built)); late loads ([DATA_LOADS.md section 8](../guides/DATA_LOADS.md#8-expectations-and-late-data)); market data checks (the Market lens's Quality landing, [EDITIONS.md section 13](EDITIONS.md#13-drishti-market-built-2026-10-29-for-297)) | One trust card: completeness against the trades booked, proxies, failed rows, market data exceptions and their impact on VaR (2.9.9 row 1) |
| *Did yesterday's P&L exceed VaR?* | **Partly**: the official-mode backtest with the derived hypothetical P&L ([VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting)) | Actual and hypothetical P&L from the bank (2.9.8 rows 3 and 4) |

### 2.2 Intraday

| Question | Drishti today | Gap |
|---|---|---|
| *Has a big trade moved us close to a limit?* | **Built**: intraday updates from files and Kafka change today's VaR during the day ([VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)); the pre-deal what-if ([VAR_GUIDE section 8.5](../guides/VAR_GUIDE.md#85-the-pre-deal-what-if-in-the-basket-tray)) | An intraday limit check that notifies (alerts on numbers watch BI and view numbers, [USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers), not the VaR engine's intraday answer) |
| *Can I see what the desk sees?* | **Built**: the same explorer, entitlements as configured ([VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)) | — |
| *A desk asks for a temporary limit increase* | **Not built** | The request, its approval and its expiry ([section 4.2](#42-ro2-breaches-and-escalation)) |

### 2.3 Month-end

| Task | Drishti today | Gap |
|---|---|---|
| **Backtesting review**: exceptions in the month, their causes, the traffic light | **Partly**: the official backtest per node, Kupiec and Christoffersen ([VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting)) | Actual and hypothetical P&L, separate counts, the multiplier (2.9.8 rows 3 and 4); an exception commentary |
| **Limit utilisation review**: which limits are never used (too loose) and which always near full | **Partly**: the official records carry `LIMIT_USE` per node and date, read in BI ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) | Other limit types; a utilisation-statistics report (average, peak, days above the threshold) |
| **RNIV**: quantify and review the risks not in VaR | **Not built** | A register ([section 4.7](#47-ro7-the-rniv-register)) |
| **Stress testing**: the monthly stress run against stress limits | **Partly**: stored stress results per desk and scenario, the bank's P&L per scenario derived ([VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived-297)) | The stress engine for any scope ([STR.md](../contracts/STR.md), proposed) |
| **The committee pack** | **Partly**: the BI report *Market risk: VaR* over the official table ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) | The report framework with sign-off (2.9.8 row 6) and the board pack (2.9.9 row 3) |

### 2.4 Annual

| Task | Drishti today | Gap |
|---|---|---|
| **Limit framework renewal**: re-size every limit to the appetite and the use | **Partly**: utilisation history of VaR limits | A limit catalogue of every type with approvals and validity ([section 4.3](#43-ro3-one-limit-catalogue-and-its-utilisation)) |
| **Stress window review**: is the stressed-VaR window still the worst for today's portfolio? | **Planned** (2.9.8 row 8, the stress window selection tool) | — |
| **Model validation support**: evidence for the validators and the regulator | **Partly**: every official figure reproducible from its inputs, kept seven years ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)); VaR at another window or confidence per request ([VAR_GUIDE section 18.5](../guides/VAR_GUIDE.md#185-the-scenario-window-and-the-confidences-as-built-294)) | Backtests on bank P&L (2.9.8); a P&L attribution test (REGULATORY_VAR_REPORTING G12, later); an evidence pack frozen with sign-off |
| **Risk appetite recalibration** | **Partly**: history of every official figure | Period statistics (2.9.8 row 5) |

---

## 3. What they need, area by area, and Drishti's status

### 3.1 Risk measures

| Need | Status | Evidence or row |
|---|---|---|
| VaR 99% 1-day, historical simulation, any scope | **Built** | [VAR_GUIDE sections 1, 2, 18](../guides/VAR_GUIDE.md#2-the-measures-with-worked-numbers) |
| ES 97.5% 1-day, component ES adding up | **Built** | [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) |
| Component, incremental, marginal VaR; diversification | **Built** | [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) |
| Any confidence and window per request; the configured one official | **Built** | [VAR_GUIDE section 18.5](../guides/VAR_GUIDE.md#185-the-scenario-window-and-the-confidences-as-built-294) |
| Confidence chosen on screen (a picker, 99% default) | **Planned** (2.9.7 row 2b) | IMPLEMENTATION_PLAN 2.9.7 |
| 10-day VaR (√10 and the 10-day sum) | **Planned** (2.9.7 row 2b) | the guide says 10-day is not built ([VAR_GUIDE section 1](../guides/VAR_GUIDE.md#1-the-question-var-answers)) |
| Stressed VaR, 1-day and 10-day, official `SVAR`, 12-week average | **Planned** (2.9.7 row 2c) | |
| VaR by risk type (IR, credit spread, equity, FX, commodity) | **Not built**, designed | REGULATORY_VAR_REPORTING G6, 2.9.9 row 3 |
| FRTB standardised and internal models | **Not built**; FRTB SA contract proposed | [FRTB.md](../contracts/FRTB.md); REGULATORY_VAR_REPORTING phases 3a, 3b |
| Several hierarchies (management, legal entity, limit letters, any configured) | **Built** | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns-as-built-295) |
| Exposure (EPE, PFE) for counterparty limits | **Built** (credit risk's measure; oversight of market risk reads it for concentration) | [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md) |

### 3.2 Limits and appetite

| Need | Status | Evidence or row |
|---|---|---|
| VaR limits per node, with currency; limit use on every answer and official record | **Built** | `var-limit` ([VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits-as-built-295)); `LIMIT`, `LIMIT_USE` ([VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)) |
| VaR limits on stored portfolios | **Built** (2.9.7: hierarchy `PORTFOLIO` in `var-limit`) | [VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits-as-built-295) |
| Limit letters as a hierarchy | **Built** (`LL`: `LIMIT_LETTER > DESK`) | [VAR_GUIDE section 15.14](../guides/VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns-as-built-295) |
| Limit use only when comparable (horizon and confidence the limit's own) | **Planned** (2.9.7 row 2b) | |
| Sensitivity limits: DV01 by tenor and currency, CS01 by rating and tenor, vega by underlying and expiry, FX delta by currency | **Not built**. Per-trade `DV01` is stored on `trade-valuation`; FRTB sensitivities are stored per book and bucket; no engine aggregates them; the per-trade `sensitivity` table is proposed | [COMPUTED_ATTRIBUTES.md sections 4.1, 4.9](COMPUTED_ATTRIBUTES.md#41-trade-valuation-tval); [FRTB.md](../contracts/FRTB.md) |
| Stop-loss (cumulative P&L, month-to-date, year-to-date) | **Not built**; needs the bank's P&L (2.9.8 row 3) | |
| Position and notional limits | **Not built**; positions are derived with quantity and market value | [VAR_GUIDE section 4](../guides/VAR_GUIDE.md#4-positions-derived-never-delivered) |
| Stress limits | **Partly**: the stored stress result carries a positive `LIMIT` per desk (2.9.7); the derived per-scenario view shows each desk's limit use and breaches; the proposed `risk-limit` table | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived-297); [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental) |
| One catalogue of every limit (id, type, upper and lower, approver, validity) | **Not built**; proposed for stress in STR.md, needed by Volcker | [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental); REGULATORY_VAR_REPORTING G8 |
| Utilisation history | **Partly**: VaR only, from the official records; the Trading desk-head landing's five-day grid by book | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [EDITIONS.md section 14](EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297) |
| Near-breach thresholds | **Partly**, three different numbers: the explorer's bar amber at 75%, red at 90% ([VAR_GUIDE section 3.2](../guides/VAR_GUIDE.md#32-the-screen)); `trading.near-limit` 80% ([EDITIONS.md section 14](EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)); `WARNING_AT` 0.8 per limit, proposed ([STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental)) | One threshold per limit, from the catalogue |
| Credit limits on counterparties | **Built** as read (stored `credit-limit`, PFE against it); the LIM engine is parked | [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md); [LIM.md](../contracts/LIM.md) |

### 3.3 Breaches and escalation

| Need | Status | Evidence or row |
|---|---|---|
| Detect a VaR breach (end of day) | **Built**: `LIMIT_USE` above 1 on the official record; shown on the Risk and Trading landings | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [EDITIONS.md sections 7, 14](EDITIONS.md#7-drishti-risks-home-three-landings) |
| Detect intraday | **Partly**: intraday VaR is live; nothing checks it against the limit and tells anyone | [VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294) |
| Notify (inbox, email, chat) | **Partly**: alert rules on one entity and alerts on numbers, after a load, with hysteresis and a cooldown ([USER_GUIDE.md](../guides/USER_GUIDE.md#alerts)); a person sets each one up | A breach rule per limit, set by the framework, not by each person |
| Commentary: the desk's explanation | **Partly**: comment threads anchored to the data are built ([COLLABORATION.md](COLLABORATION.md)) | Anchored to a breach (limit, date), with a required structure (cause, action, expected duration) |
| Approval, temporary limit increases | **Not built**. The pattern exists: write-back approval with four eyes and no self-approval ([WRITE_BACK_GUIDE.md section 10](../guides/WRITE_BACK_GUIDE.md#10-approval)); the 2.9.8 maker-checker sign-off is designed ([REGULATORY_VAR_REPORTING section 8.2.6](REGULATORY_VAR_REPORTING.md#826-the-report-definition-framework)) | |
| The audit trail | **Partly**: the audit log records changes; captures and official records are immutable | Every breach state change as one record chain |

### 3.4 Trust in the numbers

| Need | Status | Evidence or row |
|---|---|---|
| Backtesting against actual and hypothetical P&L | **Partly** (derived hypothetical P&L only); **planned** with the bank's P&L in 2.9.8 rows 3 and 4 | [VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting); [REGULATORY_VAR_REPORTING section 8.2.4](REGULATORY_VAR_REPORTING.md#824-the-backtesting-engine-g4) |
| P&L attribution (explain) | **Not built**; contract proposed, parked | [PNL.md](../contracts/PNL.md) |
| P&L attribution test (FRTB PLA) | **Not built** | REGULATORY_VAR_REPORTING G12 |
| Market data quality | **Built** in the Market lens (stale, gaps, discount factors and forwards, vol surfaces); impact on VaR **planned** (2.9.9 row 1) | [EDITIONS.md section 13](EDITIONS.md#13-drishti-market-built-2026-10-29-for-297) |
| Data completeness | **Partly**: late loads by expectation ([DATA_LOADS.md section 8](../guides/DATA_LOADS.md#8-expectations-and-late-data)); rejected and failed rows counted ([VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks-as-built)); completeness against the trades booked **not checked** (the same section) | |
| Proxies | **Partly**: each P&L vector and scenario-PV row says `ok`, `proxied` or `failed` ([VAR_GUIDE section 15.2](../guides/VAR_GUIDE.md#152-pnl-vector-one-pl-vector-per-trade-as-built)) | A proxy report: the share of VaR from proxied trades, by desk, over time |
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
| A scope's scenarios across its last N dates (is this tail scenario new? did the window roll?) | **Branch**: the scenario matrix `SMAT` (VAR_GUIDE section 3.9 on the branch); not on develop; its screenshot is in 2.9.7 row 2f | |
| The stress matrix (every scenario of a set against a scope's children) | **Not built**, proposed | [STR.md section 6.5](../contracts/STR.md#65-the-matrix-and-limits) |
| The stressed window inside the date's set | **Partly**: the Python what-if's `stress-window` | [VAR_GUIDE section 8.2](../guides/VAR_GUIDE.md#82-var-whatif-the-python-engine) |

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
| Historical and hypothetical scenarios, defined | **Built** as stored definitions (`stress-scenario`), shocks without units | [COMPUTED_ATTRIBUTES.md section 4.7](COMPUTED_ATTRIBUTES.md#47-stress-scenario-scn) |
| Stress P&L per desk and scenario | **Built** as stored results; the bank's P&L per scenario derived (2.9.7) | [VAR_GUIDE section 21.4](../guides/VAR_GUIDE.md#214-the-banks-stress-pl-by-scenario-derived-297) |
| Stress P&L of any scope, full revaluation and sensitivity-based; user scenarios | **Not built**, proposed | [STR.md](../contracts/STR.md), [STR.md section 3.4](../contracts/STR.md#34-user-scenarios-drishti-owned) |
| Stressed VaR | **Planned** (2.9.7 row 2c) | |
| Reverse stress (which moves lose a given amount) | **Not built** | [section 4.9](#49-ro9-stress-testing-the-engine-limits-and-reverse-stress) |
| Firm-wide exercises (a scenario run frozen, reviewed, signed off) | **Not built**; the 2.9.8 framework fits | [REGULATORY_VAR_REPORTING section 8.2.6](REGULATORY_VAR_REPORTING.md#826-the-report-definition-framework) |

### 3.9 Governance and reporting

| Need | Status | Evidence or row |
|---|---|---|
| Official records of every node, append-only, result versions, seven years | **Built** | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| BI over the official table; exports, subscriptions, paginated reports | **Built** | [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); [REGULATORY_VAR_REPORTING section 6](REGULATORY_VAR_REPORTING.md#6-drishtis-coverage-today) |
| Certified reports (a steward vouches for a report) | **Built** | [BI_GOVERNANCE.md section 4](../guides/BI_GOVERNANCE.md#4-certification-and-endorsement) |
| A daily risk report | **Partly**: the sample report *Market risk: VaR* | A defined daily report: appetite, limits of every type, breaches with commentary, VaR change explained, backtest, data trust |
| Report definitions, checks, freeze, lineage, maker-checker sign-off | **Planned** (2.9.8 row 6) | [REGULATORY_VAR_REPORTING section 8.2.6](REGULATORY_VAR_REPORTING.md#826-the-report-definition-framework) |
| Board and risk-committee pack (PDF, house style) | **Planned** (2.9.9 row 3) | |
| Regulatory reports (BCAR, Pillar 3, FFIEC 102, Volcker) | **Planned** (2.9.9 row 3) | [REGULATORY_VAR_REPORTING section 7](REGULATORY_VAR_REPORTING.md#7-gaps-and-what-to-build) |
| Who saw and did what | **Built**: entitlements, audit entries per answer and capture | [VAR_GUIDE section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit) |

---

## 4. Design sketches for the gaps

Every design follows Drishti's rules ([COMPUTED_ATTRIBUTES.md section 1.2](COMPUTED_ATTRIBUTES.md#12-the-rule-for-deciding-which)):

- **Fundamental inputs** (limits, sensitivities, P&L, volumes, RNIV amounts a quant supplies) arrive under a data contract as JSON Lines into
  the lake, one kind each.
- **Measures are computed on demand** from trade or position-grain inputs and never stored, except as an immutable capture or an official
  end-of-day record.
- **Governance records** (a breach's commentary and approval, an RNIV entry, a sign-off) are **Drishti-owned**: kept in the content store
  with versions, never edited in place, every step an audit entry. They point at numbers (a capture id, a limit id, a date); they never
  copy a number as the truth.
- **BI is the report layer**; the 2.9.8 report framework (definition, checks, freeze, lineage, maker-checker sign-off) is reused for every
  report and every approval chain.

Sizes as in REGULATORY_VAR_REPORTING: **S** days, **M** one to three weeks, **L** more than three weeks, for one builder. The ids RO1 to
RO14 are this document's (risk oversight); the regulatory gaps keep their G ids.

| Id | Gap | Size | Depends on |
|---|---|---|---|
| RO1 | VaR change explanation | M | official captures and membership (built); two dates open in the engine (built) |
| RO2 | Breaches and escalation | M | RO3 for limits other than VaR; the 2.9.8 sign-off record |
| RO3 | One limit catalogue and its utilisation | M | the limit system's extract |
| RO4 | Sensitivity aggregation and sensitivity limits | L (M if the bank delivers bucketed sensitivities) | RO3; per-trade sensitivities |
| RO5 | Stop-loss and P&L limits | S | 2.9.8 row 3 (the P&L feed); RO3 |
| RO6 | Position and notional limits | S | RO3 |
| RO7 | The RNIV register | S to M | the 2.9.8 sign-off record |
| RO8 | Concentration | M | reference data on positions (issuer, sector, country) |
| RO9 | Stress testing: the engine, limits and reverse stress | L | STR.md's inputs; RO3 |
| RO10 | Liquidity | M to L | volume and bid-offer data |
| RO11 | The daily risk report and the committee pack | M | 2.9.8 row 6; RO1, RO2 |
| RO12 | The Oversight lens and its landings | M | mock-ups; RO1, RO2 |
| RO13 | One data-trust card | S to M | 2.9.9 row 1 for the market-data impact |
| RO14 | Model performance | S | 2.9.8 rows 3 to 5 |

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

**Order dependence, said.** The sequence is a setting (`drishti.var.explain.order`, proposed); the default is the table above
(restatement first, because it corrects yesterday; trades last, because the desk decides them). An optional **Shapley** variant averages
each cause's effect over every order of the five main causes (120 orders, each a handful of vector sums): order-free, exact in sum,
dearer, offered per request, never official.

**Data needed.** All held today: yesterday's official capture (with its vector and membership, [VAR_GUIDE section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results));
yesterday's and today's P&L vectors (both dates open in the engine, or yesterday's hot file reopened, [VAR_GUIDE section 26](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file));
the scenario dates of both sets; the trades' status and action (`TRADE_STATUS`, `TRADE_ACTION`) for the amendment split. Nothing new
from the bank.

**Cost.** Small, because only the **churn** is summed: S3 = S2 minus the vectors of the trades out; S7 = S6 plus the vectors of the trades in;
S4 and S5 are the same sums restricted to the common dates. A node needs its two totals (already in memory) and the sum of its changed
trades' vectors (hundreds to thousands, against millions held), then eight quantiles of 509 values. For all 131 official nodes it is well
under a second on the demo; at 2.3M positions it is bounded by the churn, not the book. The S2 step needs yesterday's trades summed by
today's hierarchy only when a book moved: the books that moved, not the bank.

**Where it shows.** `VAR <scope> EXPLAIN` (a proposed command form), a **Why it moved** tab on the VaR page (a waterfall, each bar opening
its contributors), the Appetite landing's card ([section 5.4](#54-three-landings-to-mock-up-later)), the daily risk report, and the API
`GET /api/v1/var/scope/explain?scope=&from=&to=` (proposed). `from` and `to` may be any two dates, not only consecutive ones (a month's
change for the committee), and `to` may be **now** (intraday since the morning's official figure).

**Never stored.** Computed when asked. The daily report's frozen run (2.9.8 framework) keeps the explanation it showed, with its inputs'
versions, as every frozen cell does.

**Tests.** Crafted vectors where each cause alone moves VaR by a known amount (one trade out, one in, one restated vector, one book moved,
a rolled window): each step equals it and the others are zero; the steps sum to the change to the cent on the demo for every node; the
Shapley variant sums to the same total; sharded equals single-server to the bit (the sums keep a fixed order); a date with no official
capture for yesterday says so, never a zero.

### 4.2 RO2: breaches and escalation

**Records (Drishti-owned, content store, versioned).** A **breach** is created by the engine, not by a person, when an official run finds
a limit's use above 1 (or, intraday, when a configured check does): key `BR-<limit id>-<date>`; it holds the limit id, the measure, the
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
([section 8](#8-decisions-for-the-product-owner)): (a) Drishti records the request and approval and the bank's limit system is updated
by its own process, Drishti reading the new limit the next day; (b) an approved temporary limit is a Drishti-owned **overlay** on the
catalogue, valid from and to, used for limit use until it expires and exported for the limit system. (a) is simpler and keeps one
source of truth; (b) answers the same day.

**Notification.** The existing alert machinery (inbox, bell, email, chat bridges, hysteresis and cooldown,
[USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers)) is reused, but the rules come **from the catalogue** (each limit's owners
and escalation chain), not from each person setting an alert. Near breaches (use at or above the limit's warning level) notify without
creating a breach.

**Commentary** reuses comment threads anchored to the data ([COLLABORATION.md](COLLABORATION.md)), anchored to the breach record, so the
discussion and the record stay together and fall under the same retention and legal hold.

**Approvals** reuse the **2.9.8 maker-checker sign-off record** ([REGULATORY_VAR_REPORTING section 8.2.6](REGULATORY_VAR_REPORTING.md#826-the-report-definition-framework)):
one record shape for "a person approved this version of this thing", for reports, breaches, temporary limits and RNIV entries.

**Data needed.** The limit catalogue (RO3) with owners and the escalation chain; the official records (built). **Size** M. **Tests:**
a crafted breach goes through every status, refuses self-approval, escalates after N days, closes; a restated date that removes the
breach marks it `withdrawn`, never deletes it; the audit trail lists every step; a person without the power sees the queue read-only.

### 4.3 RO3: one limit catalogue and its utilisation

**Input kind** `risk-limit` (fundamental), generalising [STR.md section 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental)
to every market-risk measure: `LIMIT_ID`, `MEASURE` (`VAR`, `SVAR`, `ES`, `STR`, `DV01`, `CS01`, `VEGA`, `FX_DELTA`, `NOTIONAL`,
`POSITION`, `STOP_LOSS`, …), `SCOPE` (a scope text: a node, a portfolio, a rule), `BUCKET` (tenor, currency, curve, rating, underlying,
expiry: the measure's own grid; empty for VaR), `HORIZON` and `CONFIDENCE` (VaR and ES), `LOWER`, `UPPER` (a sensitivity limit has
both), `WARNING_AT`, `HARD_OR_SOFT`, `OWNER`, `ESCALATION` (the chain), `APPROVED_BY`, `VALID_FROM`, `VALID_TO`, `LIMIT_LETTER`,
`CURRENCY`, `BUSINESS_DATE`. `var-limit` stays as it is and is read as the `VAR` rows (open question 4 of STR.md, decided by the product
owner, [section 8](#8-decisions-for-the-product-owner)).

**Utilisation** is computed, never stored: per limit and date, the measure of the limit's scope and bucket from its engine (VaR from
the VaR engine, sensitivities from RO4, stress from RO9, P&L from RO5), `use = measure ÷ limit` (for two-sided limits, the side
approached), the status `ok`, `warning`, `breach`. One endpoint answers every limit of a date; the official run records the use of each
limit beside the VaR record (the same append-only table pattern), which feeds the utilisation history, the statistics (average, peak,
days above warning) and the annual limit review.

**Size** M (the catalogue, the engine-independent utilisation, the official rows; each measure arrives with its engine). **Tests:**
crafted limits of each type, both sides, the warning level, validity dates; a limit naming no node is reported, never silently unused
(as `DRS-5212` does for `var-limit`).

### 4.4 RO4: sensitivity aggregation and sensitivity limits

**Input.** The per-trade `sensitivity` table proposed in [FRTB.md](../contracts/FRTB.md) (one row per trade and risk factor: delta,
vega, curvature inputs), shared by FRTB SA, P&L explain and stress by sensitivities. Today only per-trade `DV01` and book-level FRTB
buckets are stored ([COMPUTED_ATTRIBUTES.md section 4.9](COMPUTED_ATTRIBUTES.md#49-frtb-sensitivity-frtb)).

**Computation.** Sensitivities are **additive**: a scope's DV01 by tenor and currency is the sum of its trades' rows. That is a derived
measure, but per arbitrary scope with entitlements it belongs to an engine (rule 4: an arbitrary scope, per reader), the same Java
summing pattern as VaR (a factor dictionary, per-trade rows, per-node totals), then each limit's bucket is matched. Grids: DV01 by
currency × tenor bucket, CS01 by rating × tenor, vega by underlying × expiry, FX delta by currency, equity delta by index or name.

**Screens.** A **ladder** per scope (the grid with each bucket's use against its limit, coloured, every cell printing its value) and a
drill to the trades of a bucket.

**Size** L; M when the bank delivers sensitivities already bucketed to the limit grid. **Tests:** the sum by bucket equals the sum of
trades; a bucket limit breached by a crafted trade; netting long and short within a bucket; two-sided limits.

### 4.5 RO5 and RO6: stop-loss, position and notional limits

- **Stop-loss** (RO5): cumulative P&L of a scope over the month and the year against a loss limit, from the bank's daily P&L (`desk-pnl`,
  2.9.8 row 3; per trade when delivered, so any scope). Derived (a sum over dates) when at desk grain; computed when at trade grain for
  arbitrary scopes. **S** after 2.9.8.
- **Position and notional limits** (RO6): a position's quantity or market value, a scope's gross or net notional, from the derived
  positions ([VAR_GUIDE section 4](../guides/VAR_GUIDE.md#4-positions-derived-never-delivered)) and `trade-valuation.NOTIONAL` (its unit
  disputed, F10 in [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md#6-findings-duplicates-and-inconsistencies), which must be fixed first). **S**.

### 4.6 The data-trust card (RO13) and model performance (RO14)

- **RO13, one data-trust card** per date and scope: loads on time ([DATA_LOADS.md section 8](../guides/DATA_LOADS.md#8-expectations-and-late-data));
  rejected and failed rows; **completeness against the trades booked** (live trades without a vector, now not checked,
  [VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks-as-built)); the **share of VaR from proxied trades** (the
  scope's VaR with and without the `proxied` rows: an incremental VaR, exact); the market data exceptions (the Market lens's checks) and,
  from 2.9.9 row 1, their VaR at stake. Computed from what the loads and the engine already record. **S to M.**
- **RO14, model performance**: the 2.9.8 backtests (actual and hypothetical, 99% and 97.5%) per desk with exceptions annotated (a
  commentary record per exception, as for breaches), the traffic light and the multiplier over time, Kupiec and Christoffersen, the share
  of proxied VaR, and later the PLA test (G12). **S** after 2.9.8.

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
review with sign-off (the 2.9.8 record), a committee-pack section. **Size** S to M. **Tests:** an entry through its statuses with four
eyes; a computed amount frozen at approval and unchanged after a restatement; the overdue review alert.

### 4.8 RO8: concentration

**Views.** For a scope and date: market value, gross and net notional, and VaR (standalone and component in the scope) **by issuer,
sector, country, currency, tenor bucket**; the top N and their share of the total; a Herfindahl index per dimension; against
concentration limits when the catalogue has them (`MEASURE` `NOTIONAL` or `POSITION` with a scope rule).

**Computation.** The sums are additive over positions; the VaR columns come from the engine's contributions by a grouping
(`by=` a column, [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) already groups by desk, book,
position, trade). The missing piece is the dimension on every position: the issuer of the instrument (core's issuer kind), its sector
and country of risk, a tenor bucket from the maturity. **Data:** reference data from the bank's security master. **Size** M. **Tests:**
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
- **Firm-wide exercises**: a named scenario set run on a date, frozen with its inputs and signed off through the 2.9.8 framework, then
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

**The daily report** is a report definition on the 2.9.8 framework: the appetite measures and their limits; every limit at warning or in
breach with its status and commentary (RO2); VaR change explained for the bank and each business (RO1); the backtest and yesterday's
exception if any (RO14); the data-trust card (RO13); the top concentrations (RO8). Generated after the official run, checked (a missing
explanation for a breach blocks sign-off), frozen, signed off by the head of market risk, distributed through subscriptions.

**The committee pack** (monthly) and the **board pack** (quarterly, 2.9.9 row 3) add: utilisation statistics, breaches of the period, the
backtesting chart, the RNIV register's total and changes, stress results against appetite, the limit changes approved. **Size** M on the
framework. **Tests:** as 2.9.8's (every cell traced to its official capture; a failing check blocks sign-off; a frozen run unchanged after a
restatement).

---

## 5. The Drishti Oversight lens: recommendation and design

### 5.1 The answer: yes, a separate lens, built after two features exist

**Yes, a lens for risk oversight is a good idea, and it should be its own lens (a fifth edition file), not a landing inside Drishti
Risk.** It should be built **after** the VaR change explanation (RO1) and the breach workflow (RO2) exist; before that it would be Drishti
Risk with a different first page.

| | For a separate lens | Against (and the answer) |
|---|---|---|
| **Persona** | The second line is a different person from the Risk lens's user: independent challenge, governance, read-only on the trading data. Trading was split from Risk on the same ground ([EDITIONS.md section 14](EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)) | Risk already serves "risk managers" (VAR_GUIDE section 1). *Answer:* Risk is the engines' lens (VaR and exposure of any scope); Oversight is the governance lens over them |
| **Defaults** | Bank-wide first; **limits first** (every type), then breaches, then why it moved; every hierarchy, including the limit letters | A landing inside Risk could set these defaults. *Answer:* only the landing, not the menus, words, help or BI |
| **Commands** | Oversight needs stress (`STR`, `SCN`, `SSPNL`), P&L explain (`PNL`), FRTB, valuations (`TVAL`), which Risk hides ([EDITIONS.md section 4](EDITIONS.md#4-what-drishti-risk-shows)); and fewer operational ones (scenario inputs, collateral operations) | Adding them to Risk would blur Risk. *Answer:* a separate allow-list is one file |
| **Governance actions** | Breach commentary and approval, temporary limits, the RNIV register, sign-offs: actions Risk (Build, Studio and write-back off) has no place for | They could be features of the full product. *Answer:* they will be (any lens may show a breach); the Oversight lens is where they are the default page |
| **RBAC later** | One lens maps cleanly to roles: `lens.roles` starts the CRO's team in it, `landing-by-role` opens Appetite for the head and Breaches for a business risk manager ([EDITIONS.md sections 7, 8](EDITIONS.md#8-lenses)) | `lens.roles` is not wired yet. *Answer:* the lens works without it, as the other four do |
| **Cost** | One file, its landings and read-only routes, no engine change: the mechanism was built for this ([EDITIONS.md section 11](EDITIONS.md#11-later-editions)) | Five lenses crowd the Switch-to menu. *Answer:* `drishti.lenses` is per site; a bank offers the ones it uses |
| **Drift** | | Two files listing similar commands may drift. *Answer:* the edition test checks each lens's surfaces; a shared list could later be an `extends:` in the edition file (a small change, [section 8](#8-decisions-for-the-product-owner)) |
| **Read-only** | | A lens hides, it does not secure ([EDITIONS.md section 8](EDITIONS.md#8-lenses)). *Answer:* read-only comes from the oversight **role** (no write-back powers; governance powers only), not from the lens |

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
          the scenario matrix: SMAT (once on develop); the proposed oversight commands]
  words: { BI: on, FUNC: on, WHAT: on, DATAMAP: on, VAR: on, EXP: on, FORM: off, RUPAKA: off }
features: { bi: edition, var: on, exposure: on, reports: on, alerts: on, monitors: on, workspaces: on, layouts: on,
            collaboration: on, governance: on,            # governance: the new feature (breaches, RNIV, sign-offs)
            build: off, studio: off, designer: off, forms: off, calc: off, pyconsole: off, python: off, ai: off, embed: off }
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

Mock-ups first, the product owner picks, then the build (the house rule). The outlines:

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
| **Drishti Risk** | the VaR and exposure explorers unchanged; the desk landing's tiles; the map landing's hierarchy switch, limit-use heat map and "Today" feed ([EDITIONS.md section 7](EDITIONS.md#7-drishti-risks-home-three-landings)); the BI datasets over the official records |
| **Drishti Market** | the Quality checks (stale, gaps, discount factors and forwards, vol surfaces) as the market-data line of the data-trust card ([EDITIONS.md section 13](EDITIONS.md#13-drishti-market-built-2026-10-29-for-297)); the moves in σ of their own history |
| **Drishti Trading** | the limit use by book over N days from the official records, the P&L-against-VaR card, `near-limit`, the "my" mapping turned into **coverage** (which businesses a risk manager covers), and its rule that scoping never widens access ([EDITIONS.md section 14](EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)) |
| **The mechanism** | one edition file, `home.landings`, `home.template`, `home.landing-by-role`, `lens.roles` (when wired), `X-Drishti-Lens`, edition gates (`DRS-4023` for a hidden command) |

---

## 6. A roadmap

Ordered by value and dependency. Releases are named only where the current plan already has a place; the rest are candidates.

| Order | Rows | Why here | Release |
|---|---|---|---|
| 1 | **RO1** VaR change explanation | The most-asked question; needs nothing new from the bank; cheap | candidate for 2.9.9 |
| 2 | **RO2** breaches and escalation (VaR limits first) | Turns "what breached" into a record; reuses the 2.9.8 sign-off record | candidate for 2.9.9, after 2.9.8's sign-off |
| 3 | **RO13** data trust, **RO14** model performance | Mostly reads of what 2.9.8 and 2.9.9 row 1 produce | candidates with 2.9.9 |
| 4 | **RO11** the daily report and committee pack | On the 2.9.8 framework; the board pack is 2.9.9 row 3 | the board pack in 2.9.9; the daily report a candidate beside it |
| 5 | **RO3** the limit catalogue, then **RO4** sensitivity limits, **RO5** stop-loss, **RO6** position and notional | The catalogue first; sensitivities need the bank's per-trade data | candidates after 2.9.9 |
| 6 | **RO7** the RNIV register | Small; uses the sign-off record | candidate for 2.9.9 or after |
| 7 | **RO12** the Oversight lens, after mock-ups | Once RO1 and RO2 exist | candidate after RO1 and RO2 |
| 8 | **RO8** concentration | Needs reference data on positions | candidate |
| 9 | **RO9** the stress engine and limits, then reverse stress | The largest; STR.md's inputs | candidate |
| 10 | **RO10** liquidity | Needs volume and bid-offer data | candidate |

The rows are in the plan's [Risk oversight (candidates)](IMPLEMENTATION_PLAN.md#risk-oversight-candidates-not-started) section, marked not
started.

---

## 7. Data the bank must supply

| Data | Grain and cadence | Needed by | Note |
|---|---|---|---|
| Limits of every type, with owners, escalation chain, warning level, validity, approver, limit letter | Per limit, daily or on change, from the limit system | RO2, RO3 and every limit | VaR limits already arrive as `var-limit` |
| Sensitivities by risk factor (or by the limit grid's buckets) | Per trade (or per book and bucket) per date | RO4, RO9 (sensitivity stress), FRTB | FRTB.md's `sensitivity` table |
| Actual and hypothetical P&L | Per desk (better per trade) per date, from product control | RO5, RO14, backtesting | The same feed as 2.9.8 row 3 |
| Average daily volume and bid-offer | Per instrument (or product and tenor bucket) per date, from vendors or exchanges | RO10 | Licensing is the bank's |
| Security master attributes: issuer, sector, country of risk | Per instrument | RO8 | Often already in the trade or position feeds |
| RNIV entries and their quantification | Per entry, monthly | RO7 | Entered in Drishti or loaded from the bank's register |
| Stress scenarios with machine-readable shocks (with units) and per-trade scenario P&L | Per scenario; per trade per run | RO9 | STR.md section 3 |
| Trade lifecycle markers (new, amended, cancelled) | Per trade per date | RO1's amendment split | `TRADE_ACTION`, `TRADE_STATUS` already on trades |
| The coverage map (which risk manager covers which business) | Per person, on change | RO2, RO12 | Configuration or the directory's groups |

---

## 8. Decisions for the product owner

1. **The lens.** A separate Drishti Oversight lens (recommended), built after RO1 and RO2; or a fourth landing inside Drishti Risk now.
2. **First build.** RO1 (VaR change explanation) and RO2 (breaches, VaR limits first) as 2.9.9 candidates beside the board pack; or after
   2.9.9.
3. **The explanation's order.** The default sequence of section 4.1 (restatement, hierarchy, trades out, window roll, market, trades in);
   the Shapley variant on request only.
4. **Temporary limits.** (a) recorded in Drishti, applied by the bank's limit system; or (b) a Drishti-owned overlay used the same day.
5. **One limit table.** `risk-limit` for every measure with `var-limit` read as its VaR rows (STR.md open question 4); or `var-limit`
   kept separate.
6. **Counterparty risk in the lens.** Read exposure for concentration (recommended), or leave counterparty risk to a credit-risk lens.
7. **The governance feature.** A new feature flag and powers (`breach.*`, `limit.temporary`, `rniv.*`, `report.sign`) on the 2.9.8 sign-off
   record; or reuse write-back's approval policy.
8. **One near-limit threshold.** Unify the explorer's 75%/90%, Trading's 80% and the catalogue's `WARNING_AT` into the limit's own
   warning level with a site default.
9. **Shared command lists.** Allow an edition file to extend another (`extends: risk`) to stop lists drifting; or keep each file whole.
10. **Mock-ups.** Two or three variants of the Appetite, Breaches and Explorer or Risk map landings when the lens is scheduled.

---

## 9. Glossary

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
