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
# Regulatory VaR reporting: Canada and the US

The one document on the market-risk reports, returns and disclosures of a Canadian bank and of a US banking organisation that need a
VaR or expected shortfall (ES) figure, or the tests around one: what each regime asks, every report item by item, the exact definition
and formula of each figure, the inputs and their data contracts, a worked example on the demo data where Drishti computes it, the
controls (report definitions, checks, frozen runs, sign-off), the data the bank supplies, and what Drishti produces today and next.

**Status: part built, part designed.** Every report, item, calculation and input below carries one of three marks:

| Mark | Meaning |
|---|---|
| **Built** | Drishti computes it today, on the demo data and on a bank's |
| **Built — needs the bank's data** | the computation is built; the figure a bank files needs an input the bank supplies (the demo runs on generated data of the same contract) |
| **Not built (designed)** | the design and, where it needs one, the data contract are here; nothing computes it yet |

**Read with:** [VAR_GUIDE.md](VAR_GUIDE.md) (the VaR engine: positions, scopes, official records, settings, API, CLI),
[the market-risk contracts](../contracts/packs/market-risk.md) (every input's contract, the planned ones at the end), and the proposed
contracts [FRTB.md](../contracts/FRTB.md) (the standardised approach) and [PNL.md](../contracts/PNL.md) (P&L explain).

**Not legal advice.** Which reports apply to which entity, and how a figure is defined for a filing, is for the bank's regulatory
reporting and legal teams to decide. Drishti computes the figures, lays them out, checks and freezes them; the bank files.

## Contents

1. [What Drishti produces today](#1-what-drishti-produces-today)
2. [The regimes](#2-the-regimes)
3. [The reports, item by item](#3-the-reports-item-by-item)
4. [The calculations](#4-the-calculations)
5. [Hypothetical P&L derived from base PVs](#5-hypothetical-pl-derived-from-base-pvs)
6. [Controls: definitions, checks, frozen runs and sign-off](#6-controls-definitions-checks-frozen-runs-and-sign-off)
7. [Data the bank supplies](#7-data-the-bank-supplies)
8. [Coverage and roadmap](#8-coverage-and-roadmap)
9. [Questions for the bank](#9-questions-for-the-bank)
10. [Glossary](#10-glossary)
11. [Sources](#11-sources)

---

## 1. What Drishti produces today

| Report or figure | Who needs it | Drishti | Section |
|---|---|---|---|
| Trading VaR by risk type, period end, average, high, low (annual report) | every listed Canadian bank; §217.212 in the US | **Built** (the pilot `annual-report-var`); rows by risk type **Built — needs the bank's data** (a risk-type hierarchy or the planned risk-type mapping) | [3.2](#32-annual-report-trading-var-by-risk-type-canada-mda) |
| Backtesting: exceptions, zone, multiplier, chart | US subpart F, OSFI internal models, Pillar 3 MR2, §217.212 | **Built — needs the bank's data** (`desk-pnl`); the pilot `var-backtesting-chart` | [3.3](#33-backtesting-chart-and-exceptions-both-countries) |
| VaR and stressed VaR, 1-day and 10-day, latest, 60-day and 12-week averages | US subpart F, FFIEC 102 | **Built** | [3.7](#37-us-market-risk-capital-217204), [3.8](#38-ffiec-102-market-risk-regulatory-report-us) |
| Market risk capital (VaR term + stressed VaR term + add-ons) and its RWA | US subpart F, FFIEC 102, FR Y-9C | **Not built (designed)**: needs the planned capital inputs | [4.7](#47-capital) |
| FFIEC 102 and §217.212 report definitions | US filers | **Not built (designed)**: every figure but the add-ons is built | [3.8](#38-ffiec-102-market-risk-regulatory-report-us), [3.9](#39-217212-market-risk-disclosures-us) |
| Volcker: VaR per desk, VaR limits and usage | US, significant trading | **Built**; the other three metrics **Not built (designed)** | [3.11](#311-volcker-metrics-us) |
| FRTB: ES with liquidity horizons, SES, DRC, PLA test, standardised approach | OSFI CAR chapter 9; the US re-proposal | **Not built (designed)** | [2.5](#25-frtb-in-outline), [3.4](#34-osfi-internal-model-tests-car-chapter-9) to [3.6](#36-bcar-schedule-90010-market-risk-canada) |
| Controls: definitions, checks, frozen runs with lineage, maker-checker-approver, files | every report | **Built** | [6](#6-controls-definitions-checks-frozen-runs-and-sign-off) |

**What every report gets from Drishti:** the figures computed from trade-grain inputs for any node, legal entity or desk; the official
end-of-day record of every node with its inputs' versions, membership and result versions, kept 7 years and recomputable
([VAR_GUIDE section 19](VAR_GUIDE.md#19-official-end-of-day-results)); the published official table for downstream systems and BI; and
reporting currencies (a Canadian report needs CAD in `drishti.var.official.currencies`, whose default is USD only).

## 2. The regimes

### 2.1 Two generations of market-risk capital

| | **Basel 2.5** (VaR and stressed VaR) | **FRTB** (Basel *Minimum capital requirements for market risk*, MAR) |
|---|---|---|
| Model measure | VaR 99%, 10-day; stressed VaR, the same model on a 12-month stress period | ES 97.5%, base horizon 10 days, scaled to liquidity horizons of 10, 20, 40, 60, 120 days; calibrated to the most severe 12-month stress period through a reduced set of risk factors |
| Capital | max(latest, multiplier × 60-day average) for VaR plus max(latest, multiplier × 12-week average) for stressed VaR; multiplier 3 to 4 from backtesting; plus specific risk, IRC, CRM | max(IMCC + SES of the day before, m_c × IMCC average + SES average), m_c 1.5 to 2 from backtesting; plus DRC (99.9%, one year); plus a surcharge for PLA-amber desks; the standardised approach for desks that are not eligible |
| Approval | the whole firm | each trading desk, which must pass desk backtesting and the P&L attribution test |
| VaR's role | the capital measure | backtesting only: 1-day VaR at 99% (bank and desk) and 97.5% (desk) |
| Where in force | the US (12 CFR 217 subpart F) | Canada (OSFI CAR chapter 9); proposed in the US |

### 2.2 Canada: OSFI

The regulator is the Office of the Superintendent of Financial Institutions (OSFI); the capital rules are the Capital Adequacy
Requirements (CAR) guideline. **Market risk is CAR chapter 9, FRTB**, in force from the first quarter of each bank's fiscal 2024 (1
November 2023 for October year-ends, which includes the six D-SIBs). It applies to internationally active institutions and to every bank
OSFI designates as a D-SIB.

| Piece | What it asks | VaR or ES in it |
|---|---|---|
| **Standardised approach** (CAR 9.5) | every institution computes and reports it: sensitivities-based method (delta, vega, curvature), standardised DRC, residual risk add-on | none: sensitivities |
| **Internal models approach** (CAR 9.6) | only with OSFI's explicit approval, desk by desk; a desk must pass backtesting and the P&L attribution test; risk factors must pass the risk factor eligibility test (RFET) | ES 97.5% with liquidity horizons, SES for non-modellable risk factors, IMA DRC; **1-day VaR at 99% and 97.5%** for backtesting |
| **Bank-wide backtesting** (CAR 9.6.3) | 1-day 99% VaR against actual and hypothetical P&L over the last 12 months (250 days); exceptions counted for each, the larger used; green 0–4, amber 5–9, red 10+; the multiplier m_c from 1.50 to 2.00; a day without P&L or VaR counts as an exception | VaR |
| **Desk backtesting** | 1-day VaR at 97.5% and 99% on 12 months; more than 12 exceptions at 99% or 30 at 97.5% sends the desk to the standardised approach | VaR |
| **P&L attribution test** | risk-theoretical P&L against hypothetical P&L over 250 days: Spearman correlation and the Kolmogorov-Smirnov statistic | none (P&L series) |
| **BCAR schedule 90.010 Market Risk** | the quarterly capital return's market-risk schedule, sections A to G | ES and SES (section A, approved desks only); never VaR |
| **Pillar 3 MRA, MR1, MRB, MR2** (D-SIBs) | MR1 (standardised) and MR2 (internal models) quarterly; MRA and MRB annual, qualitative | MR2: ES, SES, DRC and the number of backtesting exceptions against 99% VaR |
| **Annual report MD&A** | every listed bank's market-risk tables, part of the audited statements under IFRS 7 in the large banks | **trading VaR, 1-day 99%, by risk type with diversification; year-end, average, high, low** |
| **ICAAP (E-19), stress testing (E-18)** | the bank's internal capital assessment | none prescribed |

**In practice** every Canadian bank's market-risk capital is the standardised approach; a secondary report at go-live said no bank used
internal models, and no OSFI statement of who is approved today was found. VaR's regulatory role in Canada is therefore the **annual
report's VaR table** for every listed bank, and the internal-model backtests and MR2 exception count for a bank that holds or seeks
approval. The table in the annual report is driven by IFRS 7 and the bank's own practice, not by an OSFI template, and recent reports show
no stressed VaR.

### 2.3 The US: 12 CFR 217 subpart F and the reports around it

The regulators are the Federal Reserve (holding companies, state member banks, intermediate holding companies), the OCC (national banks)
and the FDIC (state non-member banks), with parallel rules (12 CFR part 217 for the Board, parts 3 and 324). The market risk rule applies
where trading assets plus trading liabilities reach **$1 billion or 10% of total assets**.

| Section | Subject | What it asks of the figures |
|---|---|---|
| §217.201 | Purpose, applicability, reservation of authority | the threshold above; the Board may require a different measure or more capital |
| §217.202 | Definitions | covered position, trading loss, VaR-based measure, stressed VaR-based measure and the others |
| §217.203 | Requirements for application of the rule | identification of covered positions, trading and hedging strategies, management, control, independent validation, stress testing, documentation |
| §217.204 | Measure for market risk; **backtesting** (§217.204(b)) | measure = VaR-based capital + stressed VaR-based capital + specific risk add-ons + incremental risk + comprehensive risk + de minimis charges; RWA = measure × 12.5; each quarter, each of the last 250 business days' trading loss (excluding fees, commissions, reserves, net interest income and intraday trading) against the 1-day 99% VaR of the day before; the multiplier from **Table 1 to §217.204** |
| §217.205 | VaR-based measure | daily, one-tail 99%, **10-business-day** holding period (computed directly or converted from a shorter horizon, justified to the Board), at least one year of history, data updated at least monthly; all covered positions; the risk categories interest rate, credit spread, equity, FX, commodity |
| §217.206 | Stressed VaR-based measure | the same model on a continuous 12-month period of significant stress, **at least weekly**, no less than the VaR-based measure's quantitative standards |
| §217.207 to §217.211 | Specific risk, incremental risk, comprehensive risk, the standardised measurement method for specific risk, the securitisation formula (section headings to confirm against the current text) | charges that are not VaR: inputs to Drishti ([7.2](#72-planned-inputs)) |
| §217.212 | Market risk disclosures | quarterly, public: high, low, mean and period-end VaR and stressed VaR; risk-category measures; VaR against actual results |

| Report | Who and when | VaR or ES in it |
|---|---|---|
| **FFIEC 102** Market Risk Regulatory Report | quarterly as of the last day of March, June, September, December, due with the Call Report or FR Y-9C; every institution subject to the rule (holding companies, banks, intermediate holding companies); **public** | VaR and stressed VaR, latest and average, the multiplier, the capital terms, the add-ons, memoranda on risk drivers ([3.8](#38-ffiec-102-market-risk-regulatory-report-us)) |
| **FR Y-9C Schedule HC-R** | quarterly; holding companies of $3 billion or more | Part II item 27, *Standardized market-risk weighted assets*: the subpart F measure × 12.5, not a VaR |
| **§217.212 disclosures** | quarterly, public; qualitative annually; a consolidated subsidiary of a bank or of a non-US banking organisation that discloses comparably at home may be exempt | [3.9](#39-217212-market-risk-disclosures-us) |
| **Volcker metrics**, 12 CFR 248 appendix A | quarterly, within 30 days of the quarter end; a banking entity with significant trading assets and liabilities ($20 billion or more, four-quarter average; a foreign bank's combined US operations) | VaR 1-day 99% per trading desk, daily; limits and usage ([3.11](#311-volcker-metrics-us)) |
| **FR Y-14Q Trading schedule** | quarterly; holding companies and IHCs of $100 billion or more in the global market shock | not a VaR report (the global market shock) |
| **SEC Item 305 (10-K Item 7A)** | annual, 10-K filers | VaR is one of three allowed forms (average, high and low); a Canadian parent files a 40-F carrying its annual report |

**The US re-proposal** (the Basel III endgame, issued 19 March 2026, published 27 March 2026 at 91 FR 14952, comments closed 18 June
2026; **not final**, no effective date) would replace the VaR-based capital with ES and liquidity horizons, add a standardised approach,
and apply market risk to Category I and II holding companies and to firms with more than $5 billion of trading activity or 10% of total
assets. Against OSFI's FRTB it drops the Spearman metric of the P&L attribution test, sets m_c between 1.5 and 2, backtests the 1-day 99%
VaR against both actual and hypothetical P&L, sends a desk to the standardised approach above 12 exceptions at 99% or 30 at 97.5%
(actual and hypothetical counted separately), and has a three-year transition without automatic consequences from the P&L attribution
test. FFIEC 101, FFIEC 102, FR Y-9C and FR Y-14 revisions are to come in separate notices. Drishti keeps every such parameter in
configuration, so a final rule is a settings change.

### 2.4 The US intermediate holding company of a foreign bank

A foreign banking organisation with $100 billion or more of combined US assets and $50 billion or more of US non-branch assets holds its US
subsidiaries under an intermediate holding company (IHC), which complies with 12 CFR part 217 in the same manner as a bank holding company
(12 CFR 252.153). For a Canadian bank's US IHC:

| Report | Applies? |
|---|---|
| Subpart F and FFIEC 102 | if the IHC's trading assets plus liabilities are $1 billion or more, or 10% of its total assets |
| FR Y-9C HC-R item 27 | yes ($3 billion or more), item 27 only if covered by subpart F |
| §217.212 | **for counsel**: the exemption for a subsidiary of a non-US banking organisation that discloses comparably at home (OSFI's Pillar 3) against §252.153's "same manner as a bank holding company" |
| Volcker | if the parent's combined US operations hold $20 billion or more of trading assets and liabilities (branches included) |
| FR Y-14Q | at $100 billion or more; the Trading schedule only in the global market shock |

The parent's Canadian reports are consolidated and include the US operations.

### 2.5 FRTB in outline

| Element | Definition | Drishti |
|---|---|---|
| **ES** | ES_T = mean of the losses beyond the 97.5% quantile at base horizon T = 10 days; with liquidity horizons, ES = √( ES_T(P)² + Σ_{j≥2} ( ES_T(P, j) · √((LH_j − LH_{j−1}) / T) )² ), where ES_T(P, j) shocks only the risk factors with a liquidity horizon of at least LH_j | ES 97.5% at 1 day of any scope: **Built**; liquidity horizons: **Not built (designed)**, needs five P&L vectors per trade |
| **Stressed calibration** | ES = ES_R,S × ES_F,C / ES_R,C: the reduced set of risk factors over the stress period, scaled by the full-to-reduced ratio today (floored at 1) | **Not built (designed)** |
| **IMCC** | ρ × ES(all risk classes) + (1 − ρ) × Σ ES(each risk class), ρ = 0.5 | **Not built (designed)** |
| **SES** | a stress scenario per non-modellable risk factor; aggregated in three categories (idiosyncratic credit spread, idiosyncratic equity, the rest, ρ = 0.6) | **Not built (designed)** |
| **DRC** | default losses at 99.9% over one year with constant positions, two-factor simulation | **Not built (designed)**; an input placeholder in the planned capital inputs |
| **Desk backtesting** | 1-day VaR at 99% and 97.5% on 12 months; more than 12 or 30 exceptions: standardised approach | **Built — needs the bank's data** (per desk, both confidences) |
| **PLA test** | RTPL against HPL over 250 days, Spearman and KS, zones ([4.8](#48-the-pl-attribution-test-frtb)) | **Not built (designed)** |
| **RFET** | 24 real-price observations a year with no 90-day period of fewer than 4, or 100 in 12 months | **Not built (designed)**: needs real-price observations per risk factor |

### 2.6 Timeline

| Date | Event |
|---|---|
| 31 March 2015 | FFIEC 102 first reported, under the US market risk rule in its current form |
| January 2021 | Volcker metrics cut to five, quarterly (compliance date of the 2019 amendments) |
| 1 November 2023 | FRTB and the revised CVA framework in force in Canada (October year-ends) |
| Q4 2024 | OSFI's Pillar 3 market-risk templates (MRA, MR1, MRB, MR2) effective for D-SIBs |
| 19 / 27 March 2026 | US re-proposal issued / published; comments to 18 June 2026 |
| 10 September 2026 | OSFI publishes CAR 2027 (final), effective November 2026 / January 2027 |
| Not set | the US final rule and its effective date |

## 3. The reports, item by item

### 3.1 How to read this section

Each report has a facts line (who files, frequency, deadline, the Drishti definition, the output) and a table, one row per item: the
item, its **identifier** on the form (only where the form or an authoritative source gives it; otherwise *to confirm against the
current form*), the exact definition and formula (the symbols are in [section 4](#4-the-calculations)), the inputs with their data
contract, the demo value where Drishti computes one, and the status. Demo values are the bank (`MGMT~BANK`) on the demo data, USD, the
fiscal year FY2026 (2025-11-01 to 2026-10-31, run to the newest official record, 2026-09-30, as a preview).

**Notation used in the tables:** VaR_q,h(d) the official VaR of a node at confidence q and horizon h on date d; SVaR the stressed VaR;
avg60 the mean of the last 60 official records; avg12w the mean of the stressed VaR over the last 12 weeks; k the multiplier;
APL, HPL, RTPL the actual, hypothetical and risk-theoretical P&L ([4.6](#46-backtesting)).

### 3.2 Annual report: trading VaR by risk type (Canada, MD&A)

**Who:** every listed Canadian bank (and a US bank's §217.212 table, [3.9](#39-217212-market-risk-disclosures-us)). **Frequency:**
annual, with quarterly updates to shareholders; fiscal year end October 31 for the D-SIBs. **Deadline:** with the annual report.
**Drishti:** `config/reports/annual-report-var.yaml` (type `var-table`); `VAR REPORT annual-report-var FY2026 <GO>`. **Output:** xlsx,
csv, PDF; the table in thousands. **Status: Built**; rows by risk type **Built — needs the bank's data**.

| Item | Identifier | Definition and formula | Inputs (contract) | Demo | Status |
|---|---|---|---|---|---|
| Rows by risk type: interest rate, credit spread, equity, FX, commodity | the bank's own table (no regulatory template) | VaR_99,1d of each risk type's node: the VaR of the trades in that risk type, computed together | official records ([VOFF](../contracts/packs/market-risk.md#voff-var-official-results-published-var-official)); the risk type of each trade: a risk-type hierarchy ([HIER](../contracts/packs/market-risk.md#hier-risk-hierarchy-node-risk-hierarchy)) or the planned risk-type mapping ([RTYP](../contracts/packs/market-risk.md#rtyp-var-risk-type-mapping-risk-type-map)) | each demo desk trades one asset class, so the pilot's rows are the ten desks (IR, INF, FI, MM, SFT, FX, EQ, STR, CMD, CS) | **Built — needs the bank's data** |
| Diversification | the bank's own | total − Σ rows, for period end and average; **n/m** for high and low (they fall on different days) | the same | from the run | **Built** |
| Total trading VaR | the bank's own | VaR_99,1d of the bank's node | official records | period end **112,041,646** | **Built** |
| Year-end (period end) | — | the figure of the last official record in the period | official records | 112,041,646 (2026-09-30) | **Built** |
| Average | — | mean of every official record in the period | official records | **151,812,526** (228 records) | **Built** |
| High, low | — | the largest and smallest record, with their dates | official records | high **167,372,692** (2026-01-13), low **87,874,268** (2025-12-10) | **Built** |
| Prior year-end, movement | — | the same over the prior fiscal year; movement = (this − prior) / prior, a note needed over 10% | official records of the prior year | the demo has no prior year | **Built** |
| Daily trading revenue against VaR (chart) | the bank's own | each day's APL against −VaR_99,1d of the day before | desk P&L ([DPNL](../contracts/packs/market-risk.md#dpnl-desk-pl-actual-and-hypothetical-daily-desk-pnl)) | [3.3](#33-backtesting-chart-and-exceptions-both-countries) | **Built — needs the bank's data** |

### 3.3 Backtesting chart and exceptions (both countries)

**Who:** a US filer under subpart F (quarterly count); an OSFI internal-model bank (bank-wide and desk); every bank for its own model
governance. **Frequency:** daily figures, counted quarterly over the last 250 business days. **Drishti:**
`config/reports/var-backtesting-chart.yaml` (type `backtesting-chart`); `VAR BANK BACKTEST <GO>`. **Output:** xlsx, csv, PDF (chart, zone,
exceptions). **Status: Built — needs the bank's data.**

| Item | Identifier | Definition and formula | Inputs (contract) | Demo | Status |
|---|---|---|---|---|---|
| Exceptions at 99%, actual P&L | §217.204(b); CAR 9.6.3 | count of days i in the last 250 with APL_i < −VaR_99,1d(i−1) | official records; `desk-pnl` `ACTUAL_PNL` | **3** (2025-12-29, 2026-01-07, 2026-01-13) | **Built — needs the bank's data** |
| Exceptions at 99%, hypothetical P&L | CAR 9.6.3; the US re-proposal | the same with HPL_i | `desk-pnl` `HYPOTHETICAL_PNL` | **3** | **Built — needs the bank's data** |
| Exceptions at 97.5% | desk backtesting | the same against VaR_97.5,1d (the record's `varAt`) | the same | 8 (actual), 10 (hypothetical); expected 6.25 | **Built — needs the bank's data** |
| Exceptions against RTPL | model governance | the same with Drishti's own risk-theoretical P&L | official records (`hypoPnl`) | on the screen | **Built** |
| Zone | Basel traffic light | green 0–4, amber 5–9, red 10+ exceptions at 99% | the count above | **green** | **Built — needs the bank's data** |
| Multiplier | Table 1 to §217.204; CAR 9.6.3 | the table of [4.6](#46-backtesting) by the count | configuration | **3.00** | **Built — needs the bank's data** |
| Desks | CAR 9.6.3 desk test | each desk's counts, zone and multiplier | `desk-pnl` by desk | inflation and money-market desks **6** each at 99%: amber, 3.50 | **Built — needs the bank's data** |
| Kupiec, Christoffersen | model governance | LR_POF and LR_IND with p-values ([VAR_GUIDE 18.4](VAR_GUIDE.md#184-history-and-backtest)) | APL | on the screen | **Built** |

### 3.4 OSFI internal-model tests (CAR chapter 9)

**Who:** a bank holding or seeking IMA approval. **Frequency:** daily calculation, results to OSFI with the approval and its ongoing
review. **Output:** evidence pack (no regulatory form). **Status:** see each row.

| Item | Identifier | Definition and formula | Inputs (contract) | Status |
|---|---|---|---|---|
| Bank-wide backtesting, APL and HPL | CAR 9.6.3 | [3.3](#33-backtesting-chart-and-exceptions-both-countries) with `count: higher` and the OSFI multiplier table ([4.6](#46-backtesting)) | `desk-pnl` | **Built — needs the bank's data**; a day without P&L or VaR counting as an exception: **Not built (designed)** (today such a day is named *untested*) |
| Desk backtesting 99% and 97.5% | CAR 9.6.3 | per regulatory desk; more than 12 (99%) or 30 (97.5%) exceptions: not eligible | `desk-pnl`; planned regulatory desks ([RDSK](../contracts/packs/market-risk.md#rdsk-regulatory-trading-desk-definitions-regulatory-desk)) | **Built — needs the bank's data** (by management desk); regulatory desks **Not built (designed)** |
| PLA test | CAR 9.6.3 | Spearman and KS of RTPL against HPL over 250 days ([4.8](#48-the-pl-attribution-test-frtb)) | official records (RTPL); `desk-pnl` (HPL) | **Not built (designed)** |
| RFET | CAR 9.6.2 | observation counts per risk factor | real-price observations (no contract yet) | **Not built (designed)** |

### 3.5 Pillar 3 MR2 and MR1 (Canada)

**Who:** D-SIBs. **Frequency:** MR1, MR2 quarterly (fixed format); MRA, MRB annual (qualitative). **Deadline:** with the bank's
quarterly reporting (date to confirm against OSFI's Pillar 3 guideline). **Drishti definition:** not yet. **Status: Not built
(designed)**, but for the exception count.

| Item | Identifier | Definition | Inputs | Status |
|---|---|---|---|---|
| Unconstrained ES, ES per regulatory risk class, constrained ES, IMCC | MR2 rows (to confirm against the current template) | [2.5](#25-frtb-in-outline); each the most recent figure and the 60-day average, high and low, current and previous quarter | liquidity-horizon P&L vectors (no contract yet) | **Not built (designed)** |
| SES; DRC; the PLA-amber surcharge; the standardised comparison | MR2 rows (to confirm) | most recent and 12-week average, high, low | as above; DRC from the planned capital inputs ([CAPI](../contracts/packs/market-risk.md#capi-market-risk-capital-inputs-per-filer-and-date-capital-input)) | **Not built (designed)** |
| Number of backtesting exceptions against VaR at 99% | MR2 row (to confirm) | the count of [3.3](#33-backtesting-chart-and-exceptions-both-countries) | `desk-pnl` | **Built — needs the bank's data** |
| MR1: delta, vega, curvature by risk class, DRC, RRAO | MR1 rows (to confirm) | the standardised approach ([FRTB.md](../contracts/FRTB.md)) | `frtb-sensitivity` and curvature shocks | **Not built (designed)** |

### 3.6 BCAR schedule 90.010 Market Risk (Canada)

**Who:** every deposit-taking institution in the market risk framework. **Frequency:** quarterly. **Deadline:** within 30 days of the
fiscal quarter end. **Status: Not built (designed).** Section A is the only one with ES and SES (approved desks); no section has VaR.

| Section | Contents | Inputs | Status |
|---|---|---|---|
| A, internal model requirements | ES 97.5% with full diversification and per risk class, reporting date and 60-day average; IMCC; SES per NMRF (reporting date, 12-week average) in three categories with ρ = 0.6; multipliers 1.5 (ES) and 1 (SES) unless OSFI sets more | liquidity-horizon vectors; NMRF scenarios; planned capital inputs (multiplier override) | **Not built (designed)** |
| B, C (standardised): sensitivities-based method, DRC, RRAO | every institution | `frtb-sensitivity`, curvature shocks, issuer default data | **Not built (designed)** ([FRTB.md](../contracts/FRTB.md)) |
| D to G | hedges of valuation-adjustment carve-outs; total charge; valuation adjustments for less liquid positions; switch between books | the bank's own figures | out of Drishti's scope |
| 10.020 / 10.030 | market-risk RWA = capital × 12.5 into the RWA summary (the IMA is not allowed in the capital floor) | the total of 90.010 | **Not built (designed)** |

### 3.7 US market risk capital (§217.204)

**Who:** a US filer under subpart F. **Frequency:** VaR daily, stressed VaR at least weekly, capital computed and reported quarterly.
**Drishti:** the figures through `VAR BANK STATS FY2026 <GO>` and `VAR BANK BACKTEST <GO>`; the capital total is designed
([4.7](#47-capital)). **Status:** each row.

| Item | Rule | Definition and formula | Inputs (contract) | Demo | Status |
|---|---|---|---|---|---|
| VaR-based measure, previous day | §217.205 | VaR_99,10d(t−1): √10 × VaR_99,1d or the sum of overlapping 10-day windows ([4.2](#42-var-es-and-the-10-day-horizon)) | official records; P&L vectors or scenario PVs | √10: **354,306,794**; sum: 274,544,283 (2026-09-30) | **Built** |
| VaR 60-day average | §217.204(a) | avg60 of VaR_99,10d | official records | 1-day avg60 **166,450,508**; × √10 = 526,362,723 | **Built** (the 1-day average; its 10-day form by √10 is arithmetic on it) |
| Multiplier | Table 1 to §217.204 | 3.00 at 0–4 exceptions … 4.00 at 10+ | backtest; planned capital inputs (override) | **3.00** | **Built — needs the bank's data** |
| VaR-based capital | §217.204(a) | max(VaR(t−1), k × avg60) | the above | 1,579,088,169 ([4.7](#47-capital)) | **Not built (designed)** |
| Stressed VaR, latest | §217.206 | SVaR_99,10d on the stress set | the stress scenario set ([SSET](../contracts/packs/market-risk.md#sset-scenario-set-scenario-set)) | √10: **675,582,023** (1-day 213,637,794) | **Built** |
| Stressed VaR 12-week average | §217.204(a) | avg12w of the official stressed VaR | official records | `VAR BANK STATS FY2026` | **Built** |
| Stressed VaR-based capital | §217.204(a) | max(SVaR latest, k × avg12w) | the above | [4.7](#47-capital) | **Not built (designed)** |
| Specific risk add-ons, IRC, CRM, de minimis | §217.207–209 | the bank's own engines | planned capital inputs ([CAPI](../contracts/packs/market-risk.md#capi-market-risk-capital-inputs-per-filer-and-date-capital-input)) | — | **Not built (designed)** |
| Measure for market risk; RWA | §217.204(a) | sum of the terms; RWA = × 12.5 | the above | [4.7](#47-capital) | **Not built (designed)** |

### 3.8 FFIEC 102 Market Risk Regulatory Report (US)

**Who:** every institution subject to subpart F (the 2025 notice counts 16 OCC, 41 Board and 1 FDIC respondents, IHCs included).
**Frequency:** quarterly, as of the last day of March, June, September and December. **Deadline:** with the Call Report or the FR Y-9C
(dates to confirm against the form's instructions). **Public.** **Drishti definition:** not yet (a `var-table` per section).
**Output:** xlsx, csv, PDF; the electronic format later. **Status: Not built (designed)** as a report; the figures are as in
[3.7](#37-us-market-risk-capital-217204). The current form's line numbers could not be read (ffiec.gov refused automated access), so
every identifier below is *to confirm against the current form*.

| Section | Items | Definition | Status |
|---|---|---|---|
| 1. VaR-based measures | previous day's VaR; 60-day average; multiplier; VaR-based capital requirement | [3.7](#37-us-market-risk-capital-217204) | figures **Built**; capital **Not built (designed)** |
| 2. Stressed VaR-based measures | most recent stressed VaR; 12-week average; multiplier; stressed VaR-based capital requirement | [3.7](#37-us-market-risk-capital-217204) | figures **Built**; capital **Not built (designed)** |
| 3. Specific risk add-ons | by method and position type | planned capital inputs | **Not built (designed)** |
| 4. Incremental risk | the measure and its average | planned capital inputs | **Not built (designed)** |
| 5. Comprehensive risk | the measure, its average, the surcharge | planned capital inputs | **Not built (designed)** |
| 6. De minimis positions | the charge | planned capital inputs | **Not built (designed)** |
| Memoranda (22 items; the first twelve on diversification across the main risk drivers) | e.g. VaR by risk driver | per risk type ([4.5](#45-var-by-risk-type-and-diversification)) | **Built — needs the bank's data** (risk types) |

### 3.9 §217.212 market risk disclosures (US)

**Who:** a US filer under subpart F, unless exempt ([2.4](#24-the-us-intermediate-holding-company-of-a-foreign-bank)).
**Frequency:** quantitative quarterly; qualitative annually. **Deadline:** with the quarter's public disclosures (to confirm).
**Drishti definition:** `annual-report-var` with `period: fiscal-quarter` and `measure: var`, a second for `svar`. **Status:** each row.

| Item (paragraph references to confirm against the current text) | Definition | Inputs | Demo (FY2026) | Status |
|---|---|---|---|---|
| High, low, mean VaR over the period and VaR at period end, per material portfolio | period statistics of VaR ([4.4](#44-period-statistics)) | official records | high 167,372,692, low 87,874,268, mean 151,812,526, period end 112,041,646 (1-day) | **Built** |
| The same for stressed VaR | period statistics of `svar` | official records | `VAR BANK STATS FY2026` with measure `svar` | **Built** |
| The same for IRC and CRM | the bank's figures | planned capital inputs | — | **Not built (designed)** |
| Separate measures for interest rate, credit spread, equity, FX, commodity | VaR by risk type ([4.5](#45-var-by-risk-type-and-diversification)) | risk-type hierarchy or planned mapping | — | **Built — needs the bank's data** |
| Comparison of VaR estimates with actual gains or losses, with an analysis of important outliers | the backtest: APL against −VaR of the day before, exceptions, the desks that drove them | `desk-pnl` | 3 exceptions | **Built — needs the bank's data** |

### 3.10 FR Y-9C Schedule HC-R (US)

**Who:** holding companies of $3 billion or more covered by subpart F. **Frequency:** quarterly. **Item:** Part II **item 27**,
*Standardized market-risk weighted assets* = the §217.204 measure × 12.5; **Memorandum item 4**, the part attributable to specific risk.
**Status: Not built (designed)** (needs the capital of [4.7](#47-capital)).

### 3.11 Volcker metrics (US)

**Who:** a banking entity with significant trading assets and liabilities ($20 billion or more; a foreign bank's combined US
operations). **Frequency:** each metric daily per trading desk, reported quarterly. **Deadline:** within 30 days of the quarter end.
**Output:** the agencies' electronic format (per their technical specifications). **Status:** each row. Stressed VaR, risk-factor
sensitivities and inventory aging are not among the five metrics.

| Metric | Definition (identifiers per the technical specifications, to confirm) | Inputs (contract) | Status |
|---|---|---|---|
| 1. Internal limits and usage | every limit of the desk (VaR at least), its upper and lower size and its daily usage | VaR limits ([VLIM](../contracts/packs/market-risk.md#vlim-var-limit-of-a-hierarchy-node-var-limit)); other limit types: a limit catalogue (proposed `risk-limit`, [STR.md 3.5](../contracts/STR.md#35-risk-limit-limits-by-measure-and-scope-fundamental)) | VaR limits **Built**; the catalogue **Not built (designed)** |
| 2. VaR | daily 1-day 99% VaR per trading desk | official records | **Built** (per management desk); per regulatory desk **Not built (designed)** |
| 3. Comprehensive P&L attribution | daily: P&L of existing positions attributed to risk factors and other factors (carry, reserves, cash flows, corrections), P&L of new positions including fees and commissions, the unexplained | desk P&L; P&L explain ([PNL.md](../contracts/PNL.md)); planned trade cash flows ([TCF](../contracts/packs/market-risk.md#tcf-trade-cash-flows-daily-trade-cash-flow)) | **Not built (designed)** |
| 4. Positions | daily value of the desk's securities and derivatives positions, long and short | derived positions ([VAR_GUIDE 15.6](VAR_GUIDE.md#156-positions-derived-by-drishti)); planned Volcker trade attributes ([VTRD](../contracts/packs/market-risk.md#vtrd-volcker-trade-attributes-volcker-trade)) for the product split | **Not built (designed)** |
| 5. Transaction volumes | value and number of transactions with customers, non-customers, and desks of the same or another banking entity | planned Volcker trade attributes (`COUNTERPARTY_CLASS`, `TRANSACTION_VALUE`) | **Not built (designed)** |
| Desk information | desk name, covered activities, strategy, trading-day calendar | planned regulatory desks | **Not built (designed)** |

### 3.12 Reports that need no VaR

FR Y-14Q's Trading schedule (the global market shock), SEC Item 305 (VaR one of three forms; a Canadian parent files a 40-F), OSFI's
ICAAP (E-19) and stress testing (E-18) prescribe no VaR or ES; CVA capital (CAR chapter 8, BCAR 80.010, Pillar 3 CVA1 to CVA4) and SA-CCR
are counterparty measures, not VaR. They are out of this document's scope.

## 4. The calculations

### 4.1 The official records every report reads

Every regulatory figure is read from the **official end-of-day records**, never recomputed for a report: one record per node of every
hierarchy per business date, with its result version, inputs' versions and membership, append-only, kept 7 years
([VAR_GUIDE section 19](VAR_GUIDE.md#19-official-end-of-day-results)). A record carries `var` (1-day at `drishti.var.confidence`), `es`,
`varAt` (the VaR at each other backtesting confidence), `svar` and `ses` (on a date with a stress set), `hypoPnl` (the risk-theoretical
P&L), the limit and its use, the standalone sum and diversification. A restated date gets a new result version; a report reads each
date's latest version at the time it is run and freezes what it read ([6.3](#63-runs-freezing-and-lineage)).

### 4.2 VaR, ES and the 10-day horizon

Historical simulation over the date's scenario set (N scenarios: 509 on the demo; `drishti.var.scenario-window` may cut it to the
newest N, 250 the regulatory minimum). For a scope S with P&L vector P_S(s) = Σ_{trades in S} P&L_trade(s):

```
VaR_q,1d  = −P_S(k)            k = ⌈N × (1 − q)⌉, P_S(k) the k-th worst scenario P&L          (99%, 509: the 6th worst)
ES_q,1d   = −mean of the k_ES worst, k_ES = ⌈N × (1 − q_ES)⌉                                  (97.5%, 509: the 13 worst)
VaR_q,10d = √10 × VaR_q,1d                                                       (scaled; §217.205 allows it if justified)
          or −(k-th worst of the N − 9 overlapping sums Σ_{j=0..9} P_S(s − j))   (the 10-day sum of 1-day scenario P&Ls)
```

**Demo, the bank, 2026-09-30:** VaR_99,1d **112,041,646** (6th worst of 509); ES_97.5 160,143,809 (mean of 13); VaR_99,10d by √10
**354,306,794**, by the sum 274,544,283 (5th worst of 500 windows). Full 10-day revaluation (a second vector set from the pricing system)
is **Not built (designed)**. The official record keeps the 1-day figure; the 10-day figures are computed on request. Details:
[VAR_GUIDE 18.1 to 18.6](VAR_GUIDE.md#181-var).

### 4.3 Stressed VaR

The same simulation on **today's positions** under the moves of a **fixed 12-month stress period**, delivered by the pricing system as a
second scenario set of the date (`SET_TYPE: STRESS`). k = ⌈250 × 0.01⌉ = 3 at 99% over 250 stress days; SES the mean of the 7 worst at
97.5%. **Demo** (`HS-STRESS-2008`, 2008-07-01 to 2009-06-30, synthetic): SVaR_99,1d **213,637,794**, √10 **675,582,023**, the 10-day
sum 293,280,315; SES 222,810,303. Every official record of a date with a stress set carries `SVAR`, `SES` and `STRESS_SET_ID`. Choosing
the window over a long history is **Not built (designed)** ([VAR_GUIDE 18.9](VAR_GUIDE.md#189-choosing-the-stress-window-design-not-built),
the screen in [6.7](#67-screens)). Details: [VAR_GUIDE 18.8](VAR_GUIDE.md#188-stressed-var).

### 4.4 Period statistics

A node's statistics of a measure m (`var`, `es`, `svar`, `ses`) over a period, from its official records (each date's latest result
version), computed on request, never stored beyond the records (`VarPeriodStats`):

```
R            = the node's official records on the dates from the period's start to its end
period end   = m of the last record in R
average      = (1/|R|) Σ_{r∈R} m(r)
high, low    = max and min of m over R, each with its date, result version and capture
avg60        = the mean of the node's last 60 official records up to the period end          (drishti.regulatory.average-days)
avg12w       = the mean of the official stressed VaR in the 12 weeks to the period end        (drishti.var.stress.average-weeks)
```

**Periods.** `FY2026` is the fiscal year ending in 2026 on `drishti.regulatory.fiscal-year-end` (October 31 by default: 2025-11-01 to
2026-10-31); `FY2026Q1` its first quarter; `2026-01-01..2026-03-31` any dates. Before the period ends the answer is a **preview**.
**Missing records are named, never skipped:** a weekday inside the period without an official record is listed (`missing`) unless the
desk P&L has no row of it either (a holiday); a period that starts before the first record says so.

**Demo:** `drishti.py var stats BANK --period FY2026` answers **228** official records, period end **112,041,646** (2026-09-30), average
**151,812,526**, high **167,372,692** (2026-01-13), low **87,874,268** (2025-12-10); avg60 **166,450,508**. The same on a page:
`VAR BANK STATS FY2026 <GO>`; the API: `GET /api/v1/var/statistics?scope=BANK&period=FY2026&measure=var` (`periodEnd`, `average`,
`high`, `low`, `records`, `missing`, `average60`, `stressedAverage`, `preview`).

**A demo server's synthetic history.** The demo lake holds one business date of vectors, so it has one official record. With
`drishti.regulatory.synthetic-history: true` (demo servers only; never on a bank's server) an administrator seeds the records before it
(`drishti.py var official --synthetic-history 259`): for each scenario day whose 250 days before it lie inside the scenario set, every node
gets the VaR and ES of today's members over those 250 days (and at each backtesting confidence), its risk-theoretical P&L the scenario
P&L of the day before, its stressed VaR scaled by the day's VaR against today's. Every seeded record says `synthetic: true` and is never
published; every answer that reads one counts them and the screens say so. The demo figures in this document are on that history.

### 4.5 VaR by risk type and diversification

Two routes give a risk type's VaR; both compute it like any node (historical simulation over the sum of its trades' vectors):

| Route | How | Exactness | Status |
|---|---|---|---|
| **A risk-type hierarchy** | a configured hierarchy whose nodes are the risk types, from the trades' columns ([VAR_GUIDE 15.14](VAR_GUIDE.md#1514-hierarchies-from-the-rows-or-from-the-trades-columns)) or from rows | each trade wholly in one risk type | **Built — needs the bank's data** (the columns or the rows) |
| **The planned risk-type mapping** | node, product type or trade → IR, CS, EQ, FX, CMD; the most specific wins ([RTYP](../contracts/packs/market-risk.md#rtyp-var-risk-type-mapping-risk-type-map)) | each trade wholly in one risk type | **Not built (designed)** |
| **Per-risk-class P&L vectors** | the pricing system sends, per trade, one vector per risk class with only that class's factors moved, adding up to the trade's P&L | exact for a trade with several risk types | **Not built (designed)** |

```
VaR_rt          = VaR_99,1d of the trades (or the risk-class vectors) of risk type rt
diversification = VaR_total − Σ_rt VaR_rt           (negative: the total is less than the sum)
```

High and low of diversification are **n/m**: the high of each row falls on a different day from the total's.

### 4.6 Backtesting

**The three P&Ls.**

| P&L | Definition | Source in Drishti |
|---|---|---|
| **APL**, actual | the day's P&L from finance, **clean**: fees, commissions, reserves, net interest income and intraday trading left out (§217.204(b)); OSFI's actual P&L likewise excludes fees and commissions and valuation adjustments with their own treatment | `desk-pnl` `ACTUAL_PNL` (a feed: [section 5](#5-hypothetical-pl-derived-from-base-pvs) explains why it stays one) |
| **HPL**, hypothetical | the end of the day before's positions revalued at the day's market by front-office models, nothing traded | `desk-pnl` `HYPOTHETICAL_PNL`; or derived from base PVs (**Not built (designed)**, [section 5](#5-hypothetical-pl-derived-from-base-pvs)) |
| **RTPL**, risk-theoretical | the same positions revalued by the risk model: the node's P&L under the newest scenario day | computed: the official record's `hypoPnl` |

**The test** (`VarBacktesting`), for a node and its official dates d_0 < d_1 < …:

```
day i is tested when the record of d_(i−1) has the node and the desk P&L has rows of the node's desks on d_i
VaR_q(i)    = the record of d_(i−1): var at the official confidence, varAt[q] at another
APL_i       = Σ ACTUAL_PNL of the node's desks on d_i;   HPL_i = Σ HYPOTHETICAL_PNL;   RTPL_i = the record of d_i: hypoPnl
exception   = P&L_i < −VaR_q(i), for APL, HPL and RTPL, counted over the last 250 tested days
zone        = green 0–4, amber 5–9, red 10+ at the first confidence     (drishti.regulatory.backtesting.green, .amber)
multiplier  = the configured table by the count                         (drishti.regulatory.backtesting.multipliers)
count read  = actual (the US rule), hypothetical, or higher (OSFI)      (drishti.regulatory.backtesting.count)
```

Fewer than 250 tested days answer *short* (not yet a regulatory zone). A node below desk level answers *not applicable* (the desk P&L is
kept by desk). The answer names every untested day and why, the exceptions with the excess and **the desks that drove them** (the largest
actual losses among the node's desks), every desk's own counts, zone and multiplier, and Kupiec's and Christoffersen's tests on the APL.
The window is `drishti.regulatory.backtesting.window` (250) and the confidences `drishti.regulatory.backtesting.confidences` (99%,
97.5%); the 97.5% VaR of the day before is the record's `varAt`.

**Multiplier tables.**

| Exceptions in 250 days (99%) | 0–4 | 5 | 6 | 7 | 8 | 9 | 10+ |
|---|---|---|---|---|---|---|---|
| Zone | green | amber | amber | amber | amber | amber | red |
| **US**, k (Table 1 to §217.204) | 3.00 | 3.40 | 3.50 | 3.65 | 3.75 | 3.85 | 4.00 |
| **Canada**, m_c = 1.50 + plus factor (Basel MAR33.9; to confirm against CAR chapter 9's table) | 1.50 | 1.70 | 1.76 | 1.83 | 1.88 | 1.92 | 2.00 |

The US table ships as `drishti.regulatory.backtesting.multipliers`; a Canadian bank sets the second row and `count: higher`. A
multiplier the supervisor imposes above the table's is an input (planned capital inputs, `MULTIPLIER_OVERRIDE`): the capital uses the
larger.

**Demo** (`drishti.py var backtest BANK`, `VAR BANK BACKTEST <GO>`, `GET /api/v1/var/backtesting?scope=BANK`): the bank over the 250
business days 2025-10-01 to 2026-09-30: **3** exceptions against APL and **3** against HPL at 99% (2025-12-29, 2026-01-07, 2026-01-13);
8 and 10 at 97.5% (expected 6.25); **green**, multiplier **3.00**. The inflation and money-market desks have 6 each at 99%: **amber**, 3.50.

**Its playground.** The backtest is a tab of the playground frame ([VAR_PLAYGROUND.md](../architecture/VAR_PLAYGROUND.md)): the node's
250 tested days as data, each exception a formula (`=IF(P&L < −VaR, 1, 0)`), the counts as `COUNTIF`, the zone and the multiplier a
lookup into the configured table, and a check cell against the engine's answer.

**Not built (designed):** ES backtests (Acerbi–Székely); OSFI's rule that a day without P&L or VaR counts as an exception.

### 4.7 Capital

**US (§217.204(a)), Basel 2.5.** With k from the backtest (or the supervisor's override, whichever is larger):

```
VaR term   = max( VaR_99,10d(t−1),  k × avg60(VaR_99,10d) )
SVaR term  = max( SVaR_99,10d(latest),  k × avg12w(SVaR_99,10d) )
measure    = VaR term + SVaR term + specific risk add-ons + IRC + CRM + de minimis charges (+ any supervisory add-on)
RWA        = 12.5 × measure                                                      (FR Y-9C HC-R Part II item 27)
```

**Worked on the demo** (2026-09-30, 10-day by √10; arithmetic on the built figures, the capital itself **Not built (designed)**):

| Term | Computation | Value |
|---|---|---:|
| VaR(t−1), 10-day | 112,041,646 × √10 | 354,306,794 |
| avg60, 10-day | 166,450,508 × √10 | 526,362,723 |
| VaR term | max(354,306,794; 3.00 × 526,362,723) | **1,579,088,169** |
| SVaR latest, 10-day | 213,637,794 × √10 | 675,582,023 |
| SVaR term | max(SVaR latest; 3.00 × avg12w): with avg12w read from `VAR BANK STATS FY2026` (`stressedAverage`) × √10 | 3.00 × that average |

With no add-ons, and **for illustration only** an avg12w equal to the latest stressed VaR, the SVaR term would be 3.00 × 675,582,023 =
2,026,746,070, the measure 3,605,834,239 and the RWA 45,072,927,988.

**Canada (CAR chapter 9), FRTB internal models, in outline:**

```
C_A    = max( IMCC(t−1) + SES(t−1),  m_c × IMCC_avg60 + SES_avg12w )          m_c = 1.50 + plus factor (table above)
IMA    = C_A + DRC
total  = IMA for eligible desks + standardised capital of the others + the PLA-amber surcharge
RWA    = 12.5 × total                                                         (BCAR 10.020)
```

**Status:** the components VaR, SVaR, averages and multiplier **Built**; the capital totals **Not built (designed)**, needing the
planned capital inputs ([CAPI](../contracts/packs/market-risk.md#capi-market-risk-capital-inputs-per-filer-and-date-capital-input)) and, for
Canada, the FRTB pieces of [2.5](#25-frtb-in-outline).

### 4.8 The P&L attribution test (FRTB)

Per desk over 250 days, HPL against RTPL:

```
Spearman ρ = the Pearson correlation of the ranks of HPL_i and RTPL_i
KS         = sup_x | F_HPL(x) − F_RTPL(x) |            the empirical distribution functions of the two series
green  when ρ > 0.80 and KS < 0.09;   red when ρ < 0.70 or KS > 0.12;   amber otherwise
```

An amber desk stays on internal models with a capital surcharge; a red desk goes to the standardised approach. The US re-proposal keeps
KS only. **Status: Not built (designed)**: RTPL is computed today (`hypoPnl`), HPL is the bank's (`desk-pnl`), regulatory desks are
planned.

## 5. Hypothetical P&L derived from base PVs

**Status: Not built (designed).** The bank's front office sends HPL today through `desk-pnl`. Where it does not, Drishti can derive it
from the base PVs it already holds (each trade's `BASE_PV` in scenario PVs, or a P&L vector's `PV`) and the planned trade cash flows:

```
HPL(t) = Σ_{i ∈ P(t−1)} [ PV_i(t) × FX_ci(t) − PV_i(t−1) × FX_ci(t−1) ] + Σ_{i ∈ P(t−1)} Σ_k CF_i,k(t) × FX_k(t)

P(t−1)     the trades (or positions) held at the end of t−1: the membership of the official record of t−1
PV_i(d)    the trade's base PV on d, in its currency c_i (full value, accrued interest included)
FX_c(d)    USD per unit of c on d: the scenario FX RATE of the date
CF_i,k(t)  the trade's cash flows of t in currency k (planned trade cash flows, TCF)
```

A node's HPL is the sum over its trades; the desk and bank figures follow the hierarchy, like VaR.

| Limit | What happens | Handling |
|---|---|---|
| **New trades** | not in P(t−1), so excluded | correct for HPL; their P&L belongs to APL |
| **Matured or closed trades** | PV_i(t) = 0; without the final cash flow the day shows a loss of the whole PV | the final payment comes as a `SETTLEMENT` or `PRINCIPAL` flow; a trade gone from the batch without a flow is named, never read as zero |
| **Coupons, dividends, premiums** | the PV drops by the flow on its payment date | added back through the cash flows; PVs must be full (dirty) values |
| **Amended trades** | PV_i(t) values the amended trade, not the position of t−1 | the day is named for the trade; scaling by the quantity ratio is offered only where the bank allows it |
| **Model or batch changes** | PV(t) and PV(t−1) from different model versions mix a model change into the P&L | the run's `MODEL_VERSION` compared; a change is named |
| **PVs from the risk engine** | the result is risk-theoretical rather than front-office | the HPL is only as front-office as its PVs: the report says which source |

**Actual P&L stays a feed.** APL holds what no revaluation of yesterday's positions gives: intraday trading, the P&L of new and amended
trades, fees and commissions, reserves and valuation adjustments, net interest income, and finance's corrections. It comes from finance
(`desk-pnl` `ACTUAL_PNL`, reconciled to the ledger through the planned finance P&L). APL is **required** for every backtesting-based report
(the US count under §217.204(b), OSFI's bank-wide test, Pillar 3 MR2's exceptions, §217.212's comparison with actual results) and
**optional** for the reports that read VaR statistics only (the annual report's VaR table, FFIEC 102's VaR lines, §217.212's high, low
and mean).

## 6. Controls: definitions, checks, frozen runs and sign-off

Reports are produced **automatically, with human sign-off**: Drishti reads the official records, fills every cell, runs the checks,
freezes the result with each figure's lineage and records the sign-off; people write the narrative, approve and submit. **Status: Built**
(the pilot pack); scheduling a run on the period end and a role check of the approver are **Not built (designed)**.

### 6.1 Report definitions

One YAML file per report in `drishti.regulatory.reports-dir` (`config/reports/`), read when asked; a wrong field is refused naming the
file and the field. The shipped pilot:

```yaml
# config/reports/annual-report-var.yaml
report: annual-report-var                     # the file's name
title: Trading VaR by risk type (annual report)
type: var-table                               # rows of official nodes; or backtesting-chart (with node:)
period: fiscal-year                           # or fiscal-quarter
measure: var                                  # the record's figure: var, es, svar, ses
units: thousands                              # how the files show it (the run keeps whole figures)
rows:
  - { label: Interest rate, code: IR, node: MGMT~DESK-RATES }
  # … one row per node …
  - { label: Diversification, compute: diversification }     # the total less the rows (n/m for high and low)
  - { label: Total trading VaR, node: MGMT~BANK, total: true }
columns: [period-end, average, high, low, prior-period-end, movement]
checks:
  - total-equals-official
  - rows-sum-to-total
  - no-missing-records
  - { check: same-total-as, report: var-backtesting-chart }
  - { check: movement-needs-note, threshold: 0.10 }
  - backtesting-exceptions
outputs: [xlsx, csv, pdf]
sign-off: [maker, checker, approver]          # in order, maker first, one person a step
step-titles: { approver: CRO }
```

| Key | Values |
|---|---|
| `type` | `var-table` (rows and statistics columns) or `backtesting-chart` (one `node:`, its 250 days, exceptions, zone, multiplier) |
| `period` | `fiscal-year`, `fiscal-quarter` |
| `measure` | `var`, `es`, `svar`, `ses` |
| `rows` | `node:` an official node; `compute: diversification`; `total: true` on the total row |
| `columns` | `period-end`, `average`, `high`, `low`, `prior-period-end`, `movement` |
| `outputs` | `xlsx`, `csv`, `pdf` |
| `sign-off`, `step-titles` | the steps in order and their titles on the screen and the files |

### 6.2 Checks

| Check | Passes when | On failure |
|---|---|---|
| `total-equals-official` | the total's period end equals the official record's figure of that date | blocks sign-off |
| `rows-sum-to-total` | rows plus diversification equal the total (period end, average) | blocks sign-off |
| `no-missing-records` | no official record missing in the period ([4.4](#44-period-statistics)) | blocks sign-off |
| `same-total-as` | the same total as another report of the same period | blocks sign-off |
| `movement-needs-note` | a movement over the threshold against the prior period has the maker's note | needs the note |
| `backtesting-exceptions` | the backtest's zone is green; amber or red needs a note | needs the note |
| `window-complete` | the backtest covers its whole window | blocks sign-off |

**Designed, Not built:** the reconciliation of the desks' actual P&L to finance's clean actual P&L per legal entity and date (planned
finance P&L, [FPNL](../contracts/packs/market-risk.md#fpnl-finance-trading-pl-by-legal-entity-daily-finance-pnl)), within the bank's
tolerance; every trade reached by the risk-type mapping.

### 6.3 Runs, freezing and lineage

A run (`POST /api/v1/var/reports/{report}/runs?period=FY2026`, `drishti.py var report run annual-report-var FY2026`, or *Generate*)
reads the period statistics of every row's node and of the prior period, fills every cell, computes diversification, runs the checks,
pre-fills the narrative's facts (the average, the period end, the largest movement, the exceptions and their dates) and **freezes** the
result in the content store (`var/reports/<report>/<period>/run-v<n>.json`) with the definition's file and digest and the code version.
Each cell keeps its **lineage**: the node, the statistic, the official record (`var/official/<date>.json`) with its result version and
capture, the data versions it was computed from, the period. A failed check blocks sign-off, never generation.

### 6.4 Sign-off

Steps in the definition's order (`POST …/runs/{period}/signoff`, `drishti.py var report sign`): **one person signs one step** of a run
(four eyes); a failed check blocks every approval; a check that needs a note needs the maker's comment; the narrative is the maker's,
edited before the maker signs; the checker or the approver may **send the run back** to the maker with a comment, which clears the steps.
The record (`signoff-v<n>.json`) keeps every action: who, when, the comment.

### 6.5 Restatements

A restated input date is captured again as the next **result version** of its official record; the earlier versions stay. A frozen run is
never changed by it: generating the period again makes the next run version, which reads the newest versions and needs a new sign-off.
The lineage of each run says which result version every cell read, so a filed figure can be traced and explained after a restatement.
An input restated by its source is sent again with a later `AS_OF` (the newest row wins), which triggers the new capture.

### 6.6 Files and submission

`GET …/runs/{period}/file?format=xlsx|csv|pdf` (`drishti.py var report file`): released after the last approval; before it, a draft on
request, titled *DRAFT, not signed off*. The xlsx and the PDF carry the table (or the chart and the zone), the checks, the narrative and
the sign-off. Submission stays with the bank's reporting team; a regulator's electronic format (XBRL, the Volcker specifications) is
**Not built (designed)**, per report.

### 6.7 Screens

| Screen | Command | Mock-up (chosen) | Status |
|---|---|---|---|
| Backtesting: chart of 250 days (APL bars, HPL line, −VaR 99% and 97.5%, exceptions), zone card with both confidences and all three P&Ls, the bank and desks table, exceptions with the desks that drove them, export | `VAR <scope> BACKTEST` | [A](../architecture/mockups/backtesting/A.html) ([B](../architecture/mockups/backtesting/B.html), [C](../architecture/mockups/backtesting/C.html)) | **Built**; on a phone the zone card only |
| Review and sign-off: the pilot pack, period statistics, the report as filed (a click shows a cell's lineage), narrative, sign-off chain, files, checks with evidence | `VAR REPORT <report> <period>`, `VAR REPORTS` | [A](../architecture/mockups/report-signoff/A.html) ([B](../architecture/mockups/report-signoff/B.html), [C](../architecture/mockups/report-signoff/C.html)) | **Built** |
| Period statistics | `VAR <scope> STATS <period>` | within the review screen | **Built** |
| Stress window selection | — | [A](../architecture/mockups/stress-window/A.html) ([B](../architecture/mockups/stress-window/B.html), [C](../architecture/mockups/stress-window/C.html)) | **Not built (designed)** |

**The stress window screen's build checklist** (option A; the mock-up's figures are illustrative):

| # | Panel | Contents | Data | Status |
|---|---|---|---|---|
| W1 | Header and controls | the positions date, the history (2007 to today), the window length; scope chips; hierarchy; export; **Freeze and propose for sign-off** | node-level long-history P&L series | **Not built (designed)** |
| W2 | Alert | the worst window exceeds the current one by more than a threshold | a threshold setting | **Not built (designed)** |
| W3 | History chart | stressed VaR of today's portfolio for every window start (weekly); the worst and current windows marked; today's VaR as reference | the rolling 250-day VaR | **Not built (designed)** |
| W4 | Top candidates | five non-overlapping windows with their stressed VaR and change against the current | the same | **Not built (designed)** |
| W5 | By desk | each node's VaR in the current window, its own worst window and the gap | the same per node | **Not built (designed)** |
| W6 | Current against proposed | window, stressed VaR 1-day and 10-day, the worst day of each, who approved the current one | the frozen analysis and its sign-off ([6.4](#64-sign-off)) | **Not built (designed)** |

## 7. Data the bank supplies

### 7.1 Inputs that exist

| Input | Contract | Grain and cadence | Used by | Status |
|---|---|---|---|---|
| P&L vectors | [PNLV](../contracts/packs/market-risk.md#pnlv-pl-vector-509-scenarios-pnl-vector) | per trade or position, date, scenario set; daily | every VaR, ES and stressed figure | **Built** |
| Scenario PVs (the alternative form, with the base PV) | [SPV](../contracts/packs/market-risk.md#spv-scenario-pvs-of-a-trade-509-scenarios-its-currency-scenario-pv) | per trade, date, scenario set; daily | the same; the base PVs of [section 5](#5-hypothetical-pl-derived-from-base-pvs) | **Built** |
| Scenario FX | [FXS](../contracts/packs/market-risk.md#fxs-scenario-fx-of-a-currency-509-scenarios-fx-scenario) | per currency, date; daily | scenario PVs to USD; the FX of [section 5](#5-hypothetical-pl-derived-from-base-pvs) | **Built** |
| Scenario sets (normal and stress) | [SSET](../contracts/packs/market-risk.md#sset-scenario-set-scenario-set) | per set, date; daily (stress at least weekly in the US) | VaR, stressed VaR | **Built** |
| Risk hierarchies (management, legal, limit letters, a risk-type hierarchy) | [HIER](../contracts/packs/market-risk.md#hier-risk-hierarchy-node-risk-hierarchy) | per node, date | every node figure; risk types by hierarchy | **Built** |
| VaR limits | [VLIM](../contracts/packs/market-risk.md#vlim-var-limit-of-a-hierarchy-node-var-limit) | per node, date | limit use; Volcker metric 1 (VaR limits) | **Built** |
| Desk P&L, actual and hypothetical | [DPNL](../contracts/packs/market-risk.md#dpnl-desk-pl-actual-and-hypothetical-daily-desk-pnl) | per desk (and legal entity), date; daily | every backtest; §217.212's comparison; MR2's exceptions | **Built — needs the bank's data** (generated demo P&L) |
| Official records (Drishti's own output, read by every report) | [VOFF](../contracts/packs/market-risk.md#voff-var-official-results-published-var-official) | per node, date, result version | every report | **Built** |

### 7.2 Planned inputs

Contracts written before the data exists (`status: planned`: no command, no test data and no engine reads them yet;
[the contracts README, section 16](../contracts/README.md#16-contracts-of-every-command)). Each lists every field on
[the market-risk contracts page](../contracts/packs/market-risk.md#planned-inputs).

| Proposed command | Input | Grain and cadence | Key fields | Unlocks |
|---|---|---|---|---|
| [TCF](../contracts/packs/market-risk.md#tcf-trade-cash-flows-daily-trade-cash-flow) | trade cash flows | per trade, date, flow type, currency; daily with the PVs | `TRADE_ID`, `FLOW_TYPE` (coupon, principal, premium, dividend, settlement, other), `PAYMENT_DATE`, `AMOUNT`, `CURRENCY` | HPL derived from base PVs ([section 5](#5-hypothetical-pl-derived-from-base-pvs)); the cash-flow line of Volcker's P&L attribution |
| [RTYP](../contracts/packs/market-risk.md#rtyp-var-risk-type-mapping-risk-type-map) | VaR risk-type mapping | per mapped node, product type or trade; on change | `SCOPE_TYPE`, `SCOPE_ID`, `HIERARCHY`, `RISK_TYPE` (IR, CS, EQ, FX, CMD), `VALID_FROM`, `VALID_TO` | VaR by risk type: annual report rows, §217.212, FFIEC 102 memoranda |
| [FPNL](../contracts/packs/market-risk.md#fpnl-finance-trading-pl-by-legal-entity-daily-finance-pnl) | finance trading P&L by legal entity | per entity, date; daily | `TOTAL_TRADING_PNL`, `FEES_AND_COMMISSIONS`, `RESERVES`, `NET_INTEREST_INCOME`, `INTRADAY_PNL`, `OTHER_EXCLUDED`, `CLEAN_ACTUAL_PNL` | the reconciliation check before a backtest is filed |
| [CAPI](../contracts/packs/market-risk.md#capi-market-risk-capital-inputs-per-filer-and-date-capital-input) | capital inputs | per filer, regime, date | `MULTIPLIER_OVERRIDE`, `ADD_ON`, `SPECIFIC_RISK_CHARGE`, `INCREMENTAL_RISK_CHARGE`, `COMPREHENSIVE_RISK_CHARGE`, `DE_MINIMIS_CHARGE`, `DEFAULT_RISK_CHARGE` | the capital totals and RWA: subpart F, FFIEC 102, FR Y-9C item 27 |
| [VTRD](../contracts/packs/market-risk.md#vtrd-volcker-trade-attributes-volcker-trade) | Volcker trade attributes | per trade, execution or amendment date; daily | `TRADING_DESK_ID`, `COVERED_ACTIVITY`, `COUNTERPARTY_CLASS`, `CUSTOMER_FACING`, `TRADE_DATE`, `TRANSACTION_VALUE`, `PRODUCT_CLASS` | Volcker positions and transaction volumes |
| [RDSK](../contracts/packs/market-risk.md#rdsk-regulatory-trading-desk-definitions-regulatory-desk) | regulatory desk definitions | per desk; on change | `REGULATORY_DESK_ID`, `LEGAL_ENTITY`, `MODEL_ELIGIBLE`, `DESK_BACKTESTED`, `VOLCKER_DESK_ID`, `COVERED_ACTIVITIES`, `TRADING_CALENDAR` | desk backtesting and PLA by regulatory desk; Volcker desk information |

**Why the cash flows are a separate input, not a field of the scenario PVs.** A flow is not a scenario value: it has its own grain (one
trade may pay several flows in several currencies on one day), it often comes from a settlement system rather than the pricing batch,
and P&L vectors carry no base PV row to add it to. A separate input leaves every existing field of the scenario PVs and P&L vectors
unchanged and serves both forms.

### 7.3 Other data the remaining reports need

No contract is written for these yet: each waits for its report's design to be confirmed with a bank.

| Data | Grain | Needed by |
|---|---|---|
| 10-day P&L vectors (full revaluation), or the bank's approval of scaling | per trade, date | US 10-day VaR if scaling is not accepted |
| Per-risk-class P&L vectors | per trade, date, risk class | exact VaR by risk type |
| A limit catalogue (every limit with id, type, upper and lower) | per desk | Volcker metric 1 (the proposed `risk-limit` of STR.md) |
| P&L explain (risk factors, new trades, fees, unexplained) | per desk, date | Volcker metric 3 ([PNL.md](../contracts/PNL.md)) |
| Sensitivities and curvature shocks | per trade, date, FRTB risk factor | the standardised approach (today's `frtb-sensitivity` kind is the start) |
| Liquidity-horizon mapping and five vectors per trade | per risk factor; per trade, date | ES with liquidity horizons |
| Real-price observations | per risk factor, daily | RFET, NMRF, SES |
| Issuer default data (PD, LGD, seniority) | per issuer | DRC (standardised and IMA) |
| Long-history P&L series per node | per node, day, back to 2007 | the stress window tool |

## 8. Coverage and roadmap

### 8.1 Coverage, report by report

| Report | Status | What unlocks the rest |
|---|---|---|
| Annual report VaR table (Canada) | **Built**; rows by risk type need the bank's risk types | a risk-type hierarchy now, or the risk-type mapping (G6) |
| Backtesting chart and exceptions | **Built — needs the bank's data** | the bank's desk P&L through `desk-pnl` |
| OSFI internal-model tests | backtesting **Built — needs the bank's data**; PLA, RFET **Not built (designed)** | regulatory desks; real-price observations |
| Pillar 3 MR2 | exception count **Built — needs the bank's data**; ES items **Not built (designed)** | FRTB internal models (G10 to G14) |
| Pillar 3 MR1, BCAR 90.010 B and C | **Not built (designed)** | the FRTB standardised approach (G9) |
| BCAR 90.010 A | **Not built (designed)** | FRTB internal models |
| US subpart F figures | **Built** | — |
| US capital, FFIEC 102, FR Y-9C item 27 | **Not built (designed)** | capital inputs; the FFIEC 102 definitions (G7) |
| §217.212 | VaR and stressed VaR statistics **Built**; risk types and the comparison need the bank's data; IRC, CRM **Not built (designed)** | risk types; desk P&L; capital inputs |
| Volcker | VaR and VaR limits **Built**; three metrics **Not built (designed)** | Volcker trade attributes, regulatory desks, a limit catalogue, P&L explain (G8) |

The gap identifiers G1 to G15, used by the other design documents, are kept:

| Gap | Subject | Status |
|---|---|---|
| G1 | 10-day VaR | √10 and the overlapping sum **Built**; full 10-day revaluation **Not built (designed)** |
| G2 | Stressed VaR | **Built**; the window selection tool **Not built (designed)** |
| G3 | Actual and hypothetical P&L feed (`desk-pnl`) | **Built — needs the bank's data**; HPL from base PVs **Not built (designed)** |
| G4 | Regulatory backtesting | **Built — needs the bank's data** |
| G5 | Period statistics | **Built** |
| G6 | VaR by risk type | by hierarchy **Built — needs the bank's data**; mapping and risk-class vectors **Not built (designed)** |
| G7 | Report packs per form (FFIEC 102, §217.212, Pillar 3, Volcker files) | the framework and the pilot **Built**; the forms **Not built (designed)** |
| G8 | Volcker metrics | VaR and VaR limits **Built**; the rest **Not built (designed)** |
| G9 | FRTB standardised approach | **Not built (designed)** |
| G10 | ES with liquidity horizons | **Not built (designed)** |
| G11 | Stressed ES calibration | **Not built (designed)** |
| G12 | PLA test | **Not built (designed)** |
| G13 | RFET, NMRF and SES | **Not built (designed)** |
| G14 | IMA DRC | **Not built (designed)** |
| G15 | CAD official records | configuration: add CAD to `drishti.var.official.currencies` |

### 8.2 The order to build in

Ordered by value to a bank and by dependency; each step names the data it waits for.

1. **The bank's data through the built contracts**: desk P&L, a risk-type hierarchy, CAD records. Every built report then runs on the
   bank's figures with no code.
2. **US capital and its forms**: the capital inputs, the capital totals ([4.7](#47-capital)), the FFIEC 102 and §217.212 definitions,
   FR Y-9C item 27.
3. **Reconciliation and risk types**: the finance P&L check, the risk-type mapping (or risk-class vectors), the regulatory desks.
4. **Hypothetical P&L from base PVs** with the trade cash flows, for banks whose front office does not send HPL.
5. **Volcker**: the trade attributes, a limit catalogue, P&L explain, the electronic files.
6. **FRTB standardised approach** (BCAR 90.010 B and C, Pillar 3 MR1): every Canadian bank computes it, and the US re-proposal makes it
   the fallback.
7. **FRTB internal models** (ES with liquidity horizons, stressed calibration, PLA, RFET and SES, DRC; BCAR 90.010 A, MR2), only for a
   bank seeking approval.

The standardised approach comes before internal models because every Canadian bank reports it, and an IMA bank must compute it too.

## 9. Questions for the bank

1. **Which regime first:** the annual report and a US IHC's Basel 2.5 set (steps 1 to 3), or the standardised approach (step 6)?
2. **The forms' current identifiers:** FFIEC 102 line numbers, Pillar 3 row numbers, §217.212 paragraph references and the Volcker
   technical specifications, from the bank's regulatory reporting team before each definition is built.
3. **10-day method:** √10, the overlapping sum, or full revaluation, as justified to the supervisor.
4. **Hypothetical P&L:** front office's own, or derived from base PVs (section 5), and the cash-flow convention per product.
5. **§217.212 for an IHC:** whether the exemption for subsidiaries of foreign banks applies (counsel).
6. **Model governance:** a regulatory VaR is a model under the bank's model risk policy (OSFI E-23, SR 11-7); Drishti's
   reproducibility supports validation, the bank owns the approval.
7. **Real-price observations** for the RFET are often licensed; a data-pool partner may be needed.

## 10. Glossary

| Term | Meaning |
|---|---|
| APL, HPL, RTPL | Actual P&L (finance, clean as the rule defines it); hypothetical P&L (yesterday's positions at today's market by front office); risk-theoretical P&L (the same by the risk model) |
| BCAR | OSFI's Basel Capital Adequacy Reporting return; schedule 90.010 is market risk |
| CAR | OSFI's Capital Adequacy Requirements guideline; chapter 9 is market risk |
| D-SIB | Domestic systemically important bank (six in Canada) |
| DRC, IRC, CRM | Default risk charge (FRTB); incremental risk charge and comprehensive risk measure (Basel 2.5) |
| ES | Expected shortfall: the average loss beyond the quantile (97.5% under FRTB) |
| FFIEC 102 | The US Market Risk Regulatory Report |
| FRTB, MAR | The Fundamental Review of the Trading Book; the Basel Framework's market-risk chapters |
| IHC | US intermediate holding company of a foreign banking organisation |
| IMA, SA | Internal models approach; standardised approach |
| IMCC | Internally modelled capital charge: ½ ES of all classes + ½ Σ ES per class |
| m_c, k | The capital multiplier: FRTB 1.5 to 2; US Basel 2.5 3 to 4 |
| NMRF, SES, RFET | Non-modellable risk factor; its stressed expected shortfall; the risk factor eligibility test |
| PLA test | P&L attribution test: RTPL against HPL by Spearman correlation and the Kolmogorov-Smirnov statistic |
| Stressed VaR | VaR of today's positions on a fixed 12-month stress period |
| Traffic light | Backtesting zones from exceptions in 250 days at 99%: 0–4 green, 5–9 amber, 10+ red |
| Volcker metrics | The quantitative measurements of 12 CFR 248 appendix A |

## 11. Sources

Opened and read on 2026-10-30 unless noted.

**Canada**
- OSFI, [CAR 2026 chapter 9, Market Risk](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-2026-chapter-9-market-risk): scope, 9.5, 9.6 (backtesting, PLA, RFET, ES, SES, DRC, aggregation).
- OSFI, [CAR 2027 guideline](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-guideline-2027) (final 10 September 2026; its chapter 9 not compared with CAR 2026 here).
- OSFI, [Update on Basel III implementation (November 2021)](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/update-basel-iii-implementation-ahead-final-rules-release-january-2022).
- OSFI, [2026 BCAR instructions](https://www.osfi-bsif.gc.ca/en/data-forms/reporting-returns/filing-financial-returns/financial-reporting-instructions/2026-basel-capital-adequacy-reporting-bcar): schedule 90.010, frequency, deadline.
- OSFI, [Pillar 3 Disclosure Guideline for D-SIBs (2025)](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/pillar-3-disclosure-guideline-domestic-systemically-important-banks-sibs-2025) and its [templates workbook](https://www.osfi-bsif.gc.ca/sites/default/files/documents/pillar3-pilier3-dsib-bisi-2025-en.xlsx).
- OSFI, [Systemically important banks](https://www.osfi-bsif.gc.ca/en/supervision/financial-institutions/banks/systemically-important-banks); [E-18](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/stress-testing-guideline-2009); [E-19](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/internal-capital-adequacy-assessment-process-icaap-deposit-taking-institutions-guideline-2010) (found by search; not read in full).
- Royal Bank of Canada, [Annual Report 2025, 40-F exhibit](https://www.sec.gov/Archives/edgar/data/1000275/000119312525305927/d95203dex2.htm): the trading VaR table, IFRS 7 marking.
- Risk.net, [No Canadian banks using internal models as FRTB kicks in](https://www.risk.net/regulation/7959017/no-canadian-banks-using-internal-models-as-frtb-kicks-in) (secondary, paywalled; a search summary only, **not confirmed**).
- Basel Committee, the Basel Framework MAR33 (the backtesting plus factors of the Canadian multiplier row, from knowledge of the framework; to confirm against CAR chapter 9).

**US**
- eCFR, [12 CFR part 217 subpart F](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-217/subpart-F) (as of 2026-10-28): §§217.201 to 212.
- eCFR, [12 CFR part 248](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-248): §248.2, §248.20(d), appendix A.
- eCFR, [12 CFR 252.153](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-252); [17 CFR 229.305](https://www.ecfr.gov/current/title-17/chapter-II/part-229/subpart-229.300/section-229.305).
- Federal Reserve, [FFIEC 102 page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FFIEC_102); Federal Register [2015-03265](https://www.govinfo.gov/content/pkg/FR-2015-02-18/html/2015-03265.htm) and [2025-07631](https://www.govinfo.gov/content/pkg/FR-2025-05-02/html/2025-07631.htm). The [FFIEC page](https://www.ffiec.gov/resources/reporting-forms/ffiec102-background) **could not be opened** (HTTP 403).
- Federal Reserve, [FR Y-9C page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FR_Y-9C) and its current form (HC-R item 27, memorandum item 4); [FR Y-14Q page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FR_Y-14Q) (the form not opened).
- Federal Reserve, [press release of 19 March 2026](https://www.federalreserve.gov/newsevents/pressreleases/bcreg20260319a.htm), [fact sheet](https://www.federalreserve.gov/newsevents/pressreleases/files/fact-sheet-20260319.pdf); Federal Register [2026-05959, 91 FR 14952](https://www.federalregister.gov/documents/2026/03/27/2026-05959/regulatory-capital-rule-category-i-and-ii-banking-organizations-banking-organizations-with); [OCC bulletin 2026-9](https://www.occ.gov/news-issuances/bulletins/2026/bulletin-2026-9.html). No final rule found.
- Volcker 2019 amendments: [OCC bulletin 2019-56](https://www.occ.gov/news-issuances/bulletins/2019/bulletin-2019-56.html), [SEC release 2019-207](https://www.sec.gov/newsroom/press-releases/2019-207), [Federal Register 2019-22695](https://www.federalregister.gov/documents/2019/11/14/2019-22695/prohibitions-and-restrictions-on-proprietary-trading-and-certain-interests-in-and-relationships-with).

**Drishti:** [VAR_GUIDE.md](VAR_GUIDE.md), [the market-risk contracts](../contracts/packs/market-risk.md), `config/reports/`,
[FRTB.md](../contracts/FRTB.md), [PNL.md](../contracts/PNL.md), [STR.md](../contracts/STR.md).
