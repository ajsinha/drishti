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
# Regulatory reporting that needs VaR or ES: Canada and the US

**Status: analysis, not started.** Nothing here is built. It is a candidate in the
[implementation plan](IMPLEMENTATION_PLAN.md#298-regulatory-var-reporting-phase-1-product-owner-2026-10-30-not-started). Every regulatory statement
links its source; every "Drishti today" statement cites the document, setting or class it was read from on 2026-10-30 (develop at
895e8612d). Where a source could not be opened or a fact could not be confirmed, the text says so.

**The request (product owner, 2026-10-30):** a detailed analysis of the regulatory reports in Canada and the US that use VaR or expected
shortfall (ES), what a bank must build and report, and what Drishti covers and should build.

**Read with:** [VAR_GUIDE.md](../guides/VAR_GUIDE.md) (the VaR engine as built), [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md) (compute,
do not store), [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md), and the proposed contracts [FRTB.md](../contracts/FRTB.md) (standardised
approach) and [PNL.md](../contracts/PNL.md) (P&L explain).

**Not legal advice.** Which reports apply to which entity, and how a figure is defined for a filing, is for the bank's regulatory
reporting and legal teams to decide. This document maps the reports to computations so that Drishti can produce the numbers.

## Contents

1. [Purpose and scope](#1-purpose-and-scope)
2. [The regimes in one page](#2-the-regimes-in-one-page)
3. [Canada, report by report](#3-canada-report-by-report)
4. [The US, report by report](#4-the-us-report-by-report)
5. [The building blocks across all reports](#5-the-building-blocks-across-all-reports)
6. [Drishti's coverage today](#6-drishtis-coverage-today)
7. [Gaps and what to build](#7-gaps-and-what-to-build)
8. [A proposed roadmap](#8-a-proposed-roadmap)
   - 8.1 [The report-definition framework](#81-the-report-definition-framework-298)
   - 8.2 [Phase 1 design](#82-phase-1-design-298)
9. [Data the bank must supply](#9-data-the-bank-must-supply)
10. [Risks and open questions for the product owner](#10-risks-and-open-questions-for-the-product-owner)
11. [Glossary](#11-glossary)
12. [Sources](#12-sources)

---

## 1. Purpose and scope

**In scope:** the market-risk reports, returns and disclosures of a Canadian bank and of US banking organisations (including the US
intermediate holding company of a foreign bank) that need a VaR or ES figure, or the inputs and tests that surround one: backtesting,
P&L attribution, stressed calibration, period statistics, desk and risk-type breakdowns, limits and their use.

**Out of scope:** credit-risk capital (the standardised and IRB approaches), operational risk, liquidity (LCR, NSFR) and interest-rate
risk in the banking book. Two counterparty items are touched briefly because they sit next to market risk in the same returns:

- **CVA risk capital** has its own chapter in Canada (CAR chapter 8) and its own BCAR schedule (80.010) and Pillar 3 templates (CVA1 to
  CVA4) ([BCAR 2026](https://www.osfi-bsif.gc.ca/en/data-forms/reporting-returns/filing-financial-returns/financial-reporting-instructions/2026-basel-capital-adequacy-reporting-bcar);
  [Pillar 3 workbook](https://www.osfi-bsif.gc.ca/sites/default/files/documents/pillar3-pilier3-dsib-bisi-2025-en.xlsx)). The US
  re-proposal of March 2026 adds a CVA capital requirement for firms with significant trading activity and material derivatives
  ([fact sheet](https://www.federalreserve.gov/newsevents/pressreleases/files/fact-sheet-20260319.pdf)). Neither uses VaR from a P&L
  vector: the standardised CVA is a sensitivities formula.
- **SA-CCR** is an exposure measure (EAD) for counterparty credit risk, not a market-risk figure.

**A correction to the starting brief.** Drishti's exposure engine computes EE, ENE, EEE, PFE, EPE, EEPE and an informational IMM EAD
([EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md), the measures table); **CVA and SA-CCR are not built as engines**: CVA and limits are
"parked; still built on the stored profile's figures" (EXPOSURE_GUIDE section on what is still open), and SA-CCR is "PLANNED for 2.10.0,
not built" ([SACCR.md](../contracts/SACCR.md)). The exposure what-if uses SA-CCR's supervisory factor only as a proxy add-on.

## 2. The regimes in one page

### 2.1 Two generations of market-risk capital

| | **Basel 2.5** (VaR and stressed VaR) | **FRTB** (Basel *Minimum capital requirements for market risk*, MAR) |
|---|---|---|
| Model measure | VaR, 99%, 10-day; stressed VaR, same model on a 12-month stress period | ES, 97.5%, base horizon 10 days, scaled to liquidity horizons of 10, 20, 40, 60 or 120 days; calibrated to the most severe 12-month stress period through a reduced set of risk factors |
| Capital formula | max(latest, multiplier × 60-day average) for VaR, and max(latest, multiplier × 12-week average) for stressed VaR; multiplier 3 to 4 from backtesting | IMCC (½ unconstrained ES + ½ sum of risk-class ES) and SES for non-modellable risk factors: max(latest, m_c × 60-day average) with m_c 1.5 plus a backtesting add-on; plus DRC (99.9%, one year); plus a surcharge for PLA-amber desks |
| Where models are approved | the whole firm | each trading desk, which must pass desk-level backtesting and the P&L attribution test |
| Standardised fallback | specific-risk add-ons and charges | the sensitivities-based method (SBM) + DRC + residual risk add-on, which every bank computes, modelled or not |
| Role of VaR | the capital measure | backtesting only: 1-day VaR at 99% (bank-wide and desk) and 97.5% (desk) |

Sources: Basel 2.5 as the US rule states it, [12 CFR 217.204 to 217.206](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-217/subpart-F);
FRTB as OSFI adopts it, [CAR 2026 chapter 9](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-2026-chapter-9-market-risk)
(sections 9.5 and 9.6, with the Basel MAR paragraph numbers).

### 2.2 Who uses which, today (2026-10-30)

| Jurisdiction | Regime in force | Status | Source |
|---|---|---|---|
| Canada | **FRTB**, CAR chapter 9 | In force since Q1 of each bank's fiscal 2024: **1 November 2023** for October year-ends (the D-SIBs), 1 January 2024 for December year-ends. The current text is CAR 2026 (effective November 2025 / January 2026); CAR 2027 was published final on 10 September 2026 and takes effect November 2026 / January 2027 | [OSFI letter, November 2021](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/update-basel-iii-implementation-ahead-final-rules-release-january-2022); [CAR 2026 ch. 9](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-2026-chapter-9-market-risk); [CAR 2027](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-guideline-2027) |
| Canada | **Standardised approach in practice** | Every institution must compute and report the SA; the IMA needs OSFI's explicit approval. Risk.net reported at go-live that no OSFI-supervised bank was using internal models (secondary source, paywalled; we did not find an OSFI statement of who is approved today) | [BCAR 90.010](https://www.osfi-bsif.gc.ca/en/data-forms/reporting-returns/filing-financial-returns/financial-reporting-instructions/2026-basel-capital-adequacy-reporting-bcar); [Risk.net](https://www.risk.net/regulation/7959017/no-canadian-banks-using-internal-models-as-frtb-kicks-in) |
| US | **Basel 2.5**, 12 CFR part 217 subpart F (and the OCC's and FDIC's parallel parts) | In force; applies at $1 billion or 10% of total assets in trading assets plus trading liabilities | [12 CFR 217.201](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-217/subpart-F) |
| US | **FRTB-style re-proposal** ("Basel III endgame") | **Proposed, not final.** Issued 19 March 2026, published 27 March 2026 (91 FR 14952), comments closed 18 June 2026. It would replace VaR with ES and liquidity horizons, add a standardised approach, and apply market risk to Category I and II holding companies and to firms with more than $5 billion of trading activity or 10% of total assets. No final rule was found in the Federal Register as of 2026-10-30; the proposal asks commenters how long the gap to an effective date should be | [Fed press release](https://www.federalreserve.gov/newsevents/pressreleases/bcreg20260319a.htm); [fact sheet](https://www.federalreserve.gov/newsevents/pressreleases/files/fact-sheet-20260319.pdf); [Federal Register](https://www.federalregister.gov/documents/2026/03/27/2026-05959/regulatory-capital-rule-category-i-and-ii-banking-organizations-banking-organizations-with); [OCC bulletin 2026-9](https://www.occ.gov/news-issuances/bulletins/2026/bulletin-2026-9.html) |

**How the US re-proposal differs from OSFI's FRTB** (all from the
[Federal Register text](https://www.federalregister.gov/documents/2026/03/27/2026-05959/regulatory-capital-rule-category-i-and-ii-banking-organizations-banking-organizations-with),
so subject to change in a final rule):

- the **Spearman** P&L attribution metric is removed; only the **Kolmogorov-Smirnov** metric remains;
- a **three-year transition** with no automatic consequences from PLA results;
- the capital multiplier m_c ranges from **1.5 to 2** with the entity-wide backtesting exceptions;
- backtesting of the VaR-based measure (1-day, 99%) against **both actual and hypothetical** P&L, entity-wide; desk-level backtesting
  makes a desk model-ineligible above **12 exceptions at 99% or 30 at 97.5%**, actual and hypothetical counted separately;
- a single default-risk calculation for all desks, and a cap of the modelled requirement at the standardised one in some cases;
- revisions to FFIEC 101 and FFIEC 102, FR Y-9C and FR Y-14 are to come in separate notices.

### 2.3 Timeline

| Date | Event | Source |
|---|---|---|
| January 2014 / 2015 | US market risk rule in its current form; FFIEC 102 reporting from 31 March 2015 | [FR 2015-03265](https://www.govinfo.gov/content/pkg/FR-2015-02-18/html/2015-03265.htm) |
| January 2020 / January 2021 | Volcker amendments effective / compliance date: metrics cut to five, quarterly | [OCC bulletin 2019-56](https://www.occ.gov/news-issuances/bulletins/2019/bulletin-2019-56.html); [SEC release 2019-207](https://www.sec.gov/newsroom/press-releases/2019-207) |
| 1 November 2023 | FRTB and the revised CVA framework in force in Canada (Q1 fiscal 2024, October year-ends) | [OSFI letter, November 2021](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/update-basel-iii-implementation-ahead-final-rules-release-january-2022) |
| Q4 2024 | OSFI's Pillar 3 market-risk templates (MRA, MR1, MRB, MR2) effective for D-SIBs | [Pillar 3 workbook, index](https://www.osfi-bsif.gc.ca/sites/default/files/documents/pillar3-pilier3-dsib-bisi-2025-en.xlsx) |
| 19 / 27 March 2026 | US Basel III re-proposal issued / published; comments to 18 June 2026 | [Fed](https://www.federalreserve.gov/newsevents/pressreleases/bcreg20260319a.htm) |
| 10 September 2026 | OSFI publishes CAR 2027 (final), effective November 2026 / January 2027 | [CAR 2027](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-guideline-2027) |
| Not set | US final rule and its effective date | — |

## 3. Canada, report by report

The regulator is the Office of the Superintendent of Financial Institutions (OSFI). The capital rules are the Capital Adequacy
Requirements (CAR) guideline; market risk is chapter 9, which applies to internationally active institutions and to every bank OSFI
designates as a D-SIB ([CAR 2026 ch. 9, scope](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-2026-chapter-9-market-risk)).
The six D-SIBs are listed on [OSFI's systemically important banks page](https://www.osfi-bsif.gc.ca/en/supervision/financial-institutions/banks/systemically-important-banks).

| Report | Frequency, who files | What it contains | VaR or ES figures it needs | Source |
|---|---|---|---|---|
| **BCAR schedule 90.010, Market Risk** | Quarterly, within 30 days of the fiscal quarter end (January, April, July, October for October year-ends); every deposit-taking institution that qualifies for the market risk framework | Sections A (internal model requirements), B (desks under the SA: B(i) sensitivities-based method, C(ii) DRC, C(iii) RRAO), C (standardised capital requirements), D (hedges of permitted valuation-adjustment carve-outs), E (total minimum capital charge), F (valuation adjustments for less liquid positions), G (capital charge for a switch between books) | **Section A only, for approved desks:** total ES at 97.5% with full diversification, at the reporting date and the 60-day average; ES per risk class (general interest rate, credit spread, equity, FX, commodity), both; IMCC as the equal-weight average of the two; SES for each NMRF at the reporting date and the 12-week average, in three categories (idiosyncratic credit spread, idiosyncratic equity, other) aggregated with ρ = 0.6; multipliers 1.5 (ES) and 1 (SES) unless OSFI sets more. **No VaR figure.** Every institution must also report the SA | [BCAR 2026, schedule 90.010](https://www.osfi-bsif.gc.ca/en/data-forms/reporting-returns/filing-financial-returns/financial-reporting-instructions/2026-basel-capital-adequacy-reporting-bcar) |
| **BCAR 10.020 / 10.030** | Quarterly, same return | Summary of RWAs and EAD; capital floor RWAs (the IMA for market risk is not permitted in the floor calculation) | Market-risk RWA = capital × 12.5 (from 90.010) | [BCAR 2026](https://www.osfi-bsif.gc.ca/en/data-forms/reporting-returns/filing-financial-returns/financial-reporting-instructions/2026-basel-capital-adequacy-reporting-bcar); [CAR 2027 ch. 1](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-guideline-2027) |
| **IMA tests behind an approval** (CAR 9.6.3) | Daily calculation, results to OSFI as part of model approval and ongoing review; only for a bank seeking or holding IMA approval | Bank-wide backtesting; desk backtesting; the PLA test; the risk factor eligibility test (RFET) | **Bank-wide:** 1-day VaR at 99% against actual (APL) and hypothetical (HPL) P&L over the prior 12 months; exceptions counted separately, the larger used; green 0–4, amber 5–9, red 10+, with a multiplier table from 1.50 (green) to 2.00 (red). **Desk:** 1-day VaR at 97.5% and 99% on 12 months of equally weighted data; more than 12 exceptions at 99% or 30 at 97.5% sends the desk to the SA. **PLA:** RTPL against HPL over 250 days, Spearman > 0.80 and KS < 0.09 green, Spearman < 0.70 or KS > 0.12 red. **RFET:** 24 real-price observations a year with no 90-day period of fewer than 4, or 100 in 12 months | [CAR 2026 ch. 9, 9.6.3 and 9.6.2](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-2026-chapter-9-market-risk) |
| **Pillar 3: MRA, MR1, MRB, MR2** (D-SIBs) | MR1 and MR2 quarterly (fixed format); MRA and MRB annual (flexible); effective Q4 2024 | MRA: market-risk management qualitative; MR1: SA components; MRB: IMA qualitative (including the VaR's scope and characteristics); MR2: IMA components | **MR2 (IMA banks only):** unconstrained ES, ES per regulatory risk class, constrained ES, IMCC, SES, DRC, the amber-desk surcharge, the SA comparison: each as the most recent figure and the 60-day (12-week for SES and DRC) average, high and low, for the current and previous quarter, **plus the number of backtesting exceptions against VaR at 99.0%**. A bank on the SA discloses MR1 only | [OSFI Pillar 3 guideline for D-SIBs (2025)](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/pillar-3-disclosure-guideline-domestic-systemically-important-banks-sibs-2025); [templates workbook](https://www.osfi-bsif.gc.ca/sites/default/files/documents/pillar3-pilier3-dsib-bisi-2025-en.xlsx) |
| **Annual report MD&A, market risk** | Annual (and quarterly reports to shareholders); every listed bank | Trading market-risk measures; in the large banks these tables are part of the audited statements under IFRS 7 (shaded text and marked tables) | **Trading VaR, 1-day, 99%, by risk type** (equity, FX, commodity, interest rate, credit specific), the **diversification** line and the total, each **year-end, average, high and low**, this year and last; daily trading revenue against VaR. A FY2025 example: [RBC Annual Report 2025, Table 48](https://www.sec.gov/Archives/edgar/data/1000275/000119312525305927/d95203dex2.htm). **No stressed VaR** in that table (checked in two D-SIBs' FY2025 reports); not an OSFI rule, so each bank chooses | [RBC Annual Report 2025 (40-F exhibit)](https://www.sec.gov/Archives/edgar/data/1000275/000119312525305927/d95203dex2.htm) |
| **ICAAP (E-19) and stress testing (E-18)** | Annual ICAAP; stress testing as the bank's programme requires | Internal capital adequacy and enterprise stress testing | **No prescribed VaR or ES.** Market risk enters as the bank chooses (VaR, stress P&L, scenario losses) | [E-19](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/internal-capital-adequacy-assessment-process-icaap-deposit-taking-institutions-guideline-2010); [E-18](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/stress-testing-guideline-2009) |

**Corrections to the starting brief (Canada).**

1. *"IMA capital on 97.5% ES"* is the rule but not the practice: every bank computes the SA, and the IMA needs OSFI approval, which no bank
   held at go-live per the secondary report above. A Canadian bank's **capital figure today is most likely the SA**, a
   sensitivities-based number, not ES. VaR's regulatory role in Canada is the IMA backtests and the MR2 exception count, both only for
   IMA banks.
2. *"BCAR market-risk schedules"*: there is **one** schedule, **90.010 Market Risk**, with sections A to G. It carries ES and SES (IMA
   banks), never VaR.
3. *"Pillar 3 market-risk tables"*: MRA, MR1, MRB, MR2. Only MR2 has an ES and a VaR item, and only for IMA banks.
4. *"Annual report MD&A trading VaR and stressed VaR by risk type"*: VaR by risk type, yes; **stressed VaR is no longer shown** in the
   FY2025 reports we checked (it was a Basel 2.5 capital measure). The table is driven by IFRS 7 and the banks' own disclosure
   practice, not by an OSFI template.
5. *"ICAAP and E-18"*: correct that they exist; neither prescribes a VaR or ES figure.

## 4. The US, report by report

The regulators are the Federal Reserve (holding companies, state member banks, intermediate holding companies), the OCC (national
banks) and the FDIC (state non-member banks); the three have parallel rules (12 CFR part 217 for the Board, parts 3 and 324 for the
OCC and FDIC). Section references below are to the Board's text.

| Report | Frequency, who files | What it contains | VaR or ES figures it needs | Source |
|---|---|---|---|---|
| **Market risk capital rule**, 12 CFR 217 subpart F | Daily calculation; capital quarterly; applies at $1 billion or 10% of total assets in trading assets plus liabilities | VaR-based, stressed VaR-based, specific risk, incremental and comprehensive risk, de minimis | **VaR:** daily, one-tail 99%, 10-business-day holding period (computed directly or converted from a shorter horizon, justified to the Board), at least one year of history, data updated at least monthly; capital = max(previous day, 60-day average × multiplier). **Stressed VaR:** same model, a continuous 12-month period of significant stress, **at least weekly**, no less than VaR; capital = max(latest, 12-week average × multiplier). **Backtesting:** each of the last 250 business days' trading loss (excluding fees, commissions, reserves, net interest income and intraday trading) against the 1-day 99% VaR, counted quarterly; multiplier 3.00 for 4 or fewer exceptions, 3.40, 3.50, 3.65, 3.75, 3.85 for 5 to 9, 4.00 for 10 or more. Risk categories: interest rate, credit spread, equity, FX, commodity | [§217.201, 204, 205, 206](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-217/subpart-F) |
| **FFIEC 102**, Market Risk Regulatory Report | Quarterly as of the last day of March, June, September, December, due with the Call Report or FR Y-9C; every institution subject to the rule (the 2025 notice counts 16 OCC, 41 Board and 1 FDIC respondents, IHCs included). **Public** | Sections, as the 2015 notice describes them: (1) VaR-based measures; (2) stressed VaR-based measures; (3) specific risk add-ons; (4) incremental risk; (5) comprehensive risk; (6) de minimis positions; and a Memoranda section of 22 items (the first twelve on diversification across the main risk drivers). Extended **without revision** in 2025 | **VaR: the previous day's figure and the 60-day average; stressed VaR likewise** (latest and 12-week average), the multiplier; memoranda on risk drivers. The 2015 notice describes the sections; we could not open the current form on ffiec.gov (it refused automated access), so line numbers are not quoted here | [Fed reporting-forms page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FFIEC_102); [FR 2015-03265](https://www.govinfo.gov/content/pkg/FR-2015-02-18/html/2015-03265.htm); [FR 2025-07631](https://www.govinfo.gov/content/pkg/FR-2025-05-02/html/2025-07631.htm); [FFIEC background page](https://www.ffiec.gov/resources/reporting-forms/ffiec102-background) |
| **FR Y-9C, Schedule HC-R** | Quarterly; BHCs, SLHCs, **IHCs** and SHCs with $3 billion or more | Regulatory capital and RWAs | **Part II item 27, "Standardized market-risk weighted assets"** (only for holding companies covered by the market risk rule) and Memorandum item 4 (the part attributable to specific risk). An RWA, not a VaR: it is the capital of subpart F × 12.5 | [FR Y-9C page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FR_Y-9C) (current form, read 2026-10-30) |
| **Market risk disclosures**, §217.212 | Quarterly, public; qualitative annually | For each material portfolio of covered positions | **High, low and mean VaR over the period and VaR at period end; the same for stressed VaR**; IRC and CRM the same way; **separate measures for interest rate, credit spread, equity, FX and commodity** used in the VaR; a **comparison of VaR estimates with actual gains or losses**, with an analysis of important outliers. Exempt: a consolidated subsidiary of a BHC or depository institution that discloses, or **of a non-US banking organisation subject to comparable disclosure at home** | [§217.212](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-217/subpart-F) |
| **Volcker Rule metrics**, 12 CFR 248 appendix A | **Quarterly, within 30 days** of quarter end; every banking entity with **significant trading assets and liabilities** ($20 billion or more, four-quarter average; for a foreign banking organisation, its **combined US operations**) | Per trading desk engaged in covered trading activity: desk information (name, activity types, strategy, the trading-day calendar), identifying information schedules, an optional narrative | **Five metrics, each per desk:** (1) **Internal limits and usage**, daily, each limit's size (upper and lower) and usage, including at a minimum a VaR limit; (2) **VaR**, daily, **99%, one day**; (3) **Comprehensive P&L attribution**, daily: existing positions against new positions, existing attributed to risk factors and other factors (carry, reserves, cash flows, corrections), new positions including fees and commissions; (4) **Positions**; (5) **Transaction volumes**. **Stressed VaR and risk-factor sensitivities were eliminated in 2019** | [12 CFR 248 appendix A](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-248); [§248.20(d)](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-248); [SEC release 2019-207](https://www.sec.gov/newsroom/press-releases/2019-207) |
| **FR Y-14Q, Trading schedule** | Quarterly (the trading schedule's as-of date can differ); BHCs, **IHCs** and covered SLHCs with $100 billion or more | Part of the global market shock exercise of supervisory stress testing | **Not a VaR report.** The Board's page describes the schedule as supporting the global market shock; we did not open the form, so we do not say which sensitivities or positions it lists | [FR Y-14Q page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FR_Y-14Q) |
| **SEC Item 305 (10-K Item 7A)** | Annual, US registrants filing a 10-K | Quantitative and qualitative disclosures about market risk, trading and non-trading separately, per risk category | **VaR is one of three alternatives** (tabular, sensitivity, VaR); with VaR, the average, high and low (or the distribution) for the period, or the actual changes. A Canadian bank files a **40-F**, which carries its Canadian annual report (section 3); Item 305 applies to a US subsidiary only if it files a 10-K itself | [17 CFR 229.305](https://www.ecfr.gov/current/title-17/chapter-II/part-229/subpart-229.300/section-229.305) |

**Corrections to the starting brief (US).**

1. *"99% 10-day VaR + stressed VaR × a multiplier of 3–4"*: correct. Add: stressed VaR's average is over **12 weeks**, not 60 days;
   it must be computed **at least weekly**; backtesting is at **1 day**, against trading losses that exclude fees, commissions, reserves,
   net interest income and intraday trading.
2. *"FRTB not yet adopted; Basel III endgame pending"*: correct, with dates: re-proposed 19 March 2026, comments closed 18 June 2026,
   **no final rule as of 2026-10-30**. The re-proposal drops Spearman from the PLA test and sets m_c between 1.5 and 2.
3. *"FFIEC 102"*: six sections plus memoranda; VaR and stressed VaR as **latest and average**; public.
4. *"FR Y-9C HC-R"*: **Part II item 27**, a market-risk RWA, not a VaR.
5. *"Volcker metrics (VaR and stressed VaR per desk, limits and usage, P&L attribution)"*: **stressed VaR is no longer a Volcker metric**
   (eliminated in 2019, with risk-factor sensitivities and inventory aging). The five today: limits and usage, VaR (1-day 99%),
   comprehensive P&L attribution, positions, transaction volumes; quarterly; $20 billion threshold on combined US operations for a
   foreign bank.
6. *"FR Y-14Q trading schedules"*: one **Trading** schedule, for the global market shock; not VaR.
7. *"SEC 10-K Item 7A"*: applies to 10-K filers; the Canadian parent files a 40-F.
8. *"§217.212 disclosures"*: high, low, mean and period-end VaR and stressed VaR, by risk category, and backtesting; with an exemption
   for subsidiaries of foreign banks that disclose comparably at home (section 4.1).

### 4.1 The US intermediate holding company of a foreign bank

A foreign banking organisation with $100 billion or more of combined US assets and $50 billion or more of US non-branch assets must
hold its US subsidiaries under a US intermediate holding company (IHC), and the IHC **must comply with 12 CFR part 217 in the same
manner as a bank holding company** ([12 CFR 252.153](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-252)). For a
Canadian bank with a US IHC:

| Report | Applies to the IHC? | Note |
|---|---|---|
| Market risk rule (subpart F) and **FFIEC 102** | **If** the IHC's trading assets plus liabilities are $1 billion or more, or 10% of its total assets | The 2025 FFIEC 102 notice lists IHCs among the Board's respondents |
| **FR Y-9C** HC-R item 27 | Yes (IHCs of $3 billion or more file the Y-9C); item 27 only if covered by the market risk rule | |
| **§217.212** public disclosures | **Open question** | §217.212(a) exempts a consolidated subsidiary of a non-US banking organisation subject to comparable public disclosure at home (OSFI's Pillar 3), while §252.153 applies part 217 "in the same manner as a bank holding company". Counsel to decide |
| **Volcker** metrics | **If** the combined US operations of the parent hold $20 billion or more of trading assets and liabilities | Measured on combined US operations, branches included, not the IHC alone |
| **FR Y-14Q** | Yes at $100 billion or more; the Trading schedule only for firms in the global market shock | |
| CCAR / DFAST, capital plans | Per the IHC's category | Stress testing, not VaR |
| Under the re-proposal (if adopted) | Category I and II holding companies, and firms with more than $5 billion of trading activity or 10% of assets | The IHC's category and trading size decide it |

The parent's own Canadian reports (section 3) are consolidated and include the US operations.

## 5. The building blocks across all reports

✔ needed, ○ needed only by a bank on internal models (IMA) or as an input to a needed figure, blank not needed.

| Building block | BCAR 90.010 | Pillar 3 MR2 | Canada IMA tests | Canada MD&A | US subpart F | FFIEC 102 | §217.212 | Volcker | US re-proposal |
|---|---|---|---|---|---|---|---|---|---|
| VaR 99% 1-day | | ○ (exceptions) | ○ | ✔ | ✔ (backtest) | | | ✔ per desk | ✔ (backtest) |
| VaR 99% 10-day | | | | | ✔ | ✔ | ✔ | | |
| Stressed VaR (12-month stress, weekly) | | | | | ✔ | ✔ | ✔ | | |
| ES 97.5% with liquidity horizons | ○ | ○ | | | | | | | ✔ (modelled) |
| Stressed ES calibration (reduced set, ES_R,S × ES_F,C / ES_R,C) | ○ | ○ | | | | | | | ✔ (modelled) |
| Backtesting, bank level, actual and hypothetical P&L | | ○ | ○ | (revenue vs VaR) | ✔ (actual) | | ✔ | | ✔ both |
| Backtesting, desk level, 99% and 97.5% | | | ○ | | | | | | ✔ |
| Exception counts, zones, multiplier | ○ (multiplier) | ○ | ○ | | ✔ | ✔ | ✔ | | ✔ |
| P&L attribution test (Spearman, KS) | | ○ (surcharge) | ○ | | | | | | ✔ (KS only) |
| Risk-factor eligibility, NMRF and SES | ○ | ○ | ○ | | | | | | ✔ (modelled) |
| DRC (99.9%, one year) | ○ (and SA DRC ✔) | ○ | | | (IRC instead) | (IRC) | (IRC) | | ✔ |
| Breakdown by desk | ○ | | ○ | | | | | ✔ | ✔ |
| Breakdown by legal entity | ✔ (consolidated) | ✔ | | ✔ | ✔ (by filer) | ✔ | ✔ | ✔ (combined US) | ✔ |
| Breakdown by risk type, with diversification | ○ (ES per class) | ○ | | ✔ | ✔ (categories) | ✔ (memoranda) | ✔ | | ✔ |
| High, low, average, period end | | ○ | | ✔ | | | ✔ | | |
| 60-day average (12-week for stressed and SES) | ○ | ○ | | | ✔ | ✔ | | | ✔ |
| Limits and usage | | | | | | | | ✔ | |
| Sensitivities (SBM) | ✔ (SA, everyone) | (MR1) | | | | | | (removed 2019) | ✔ (standardised) |
| Comprehensive P&L attribution | | | | | | | | ✔ | |
| Positions, transaction volumes | | | | | | | | ✔ | |
| Retention and reproducibility | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |

**Reading the matrix.** For a Canadian bank on the SA today, the VaR-based reporting is the **MD&A table** and the bank's internal
risk management; the capital returns need the **SA** (sensitivities). The **US** side needs the full Basel 2.5 set (10-day VaR, stressed
VaR, averages, multiplier, backtesting, high-low-mean) wherever the market risk rule applies, and **1-day 99% VaR per desk with limits
and P&L attribution** for Volcker. FRTB IMA blocks matter only for a bank that seeks model approval in either country.

## 6. Drishti's coverage today

Built: no pill; **partly** and **not built** say so. Evidence is the document section, setting or class that shows it.

| Building block | Drishti today | Evidence |
|---|---|---|
| VaR 99% 1-day, any scope | **Built.** Historical simulation over the date's scenario set (509 in the test data; `scenario-window` cuts to the newest N, 250 the regulatory minimum); 99% by default (`drishti.var.confidence`), any confidence per request; the bank, a desk, a book, a position, a trade, any hierarchy node, stored portfolios, ad-hoc trade sets | [VAR_GUIDE §18.1, §18.5, §5](../guides/VAR_GUIDE.md#181-var) |
| VaR 10-day | **Not built.** "no √10 scaling anywhere (10-day VaR is not built; the screens say *1 day*)"; `HORIZON_DAYS` is 1 | [VAR_GUIDE §17](../guides/VAR_GUIDE.md#17-conventions-specific-to-var), §15.3 |
| Stressed VaR | **Not built** as a regulatory figure: a stressed set `SVAR-250` is designed, not built (open question 6). **Partly:** the Python what-if reports a *stressed window* (the largest VaR over every run of consecutive scenario days inside the date's set), which cannot reach a 2007-09 or 2020 period | [VAR_GUIDE §8.2, §18.3, §31](../guides/VAR_GUIDE.md#82-var-whatif-the-python-engine) |
| ES 97.5% | **Built** at 1 day: mean of the 13 worst of 509, any scope, component ES adding to the scope's ES | [VAR_GUIDE §18.2, §18.3](../guides/VAR_GUIDE.md#182-es) |
| ES with liquidity horizons; stressed ES calibration | **Not built** ("FRTB's liquidity horizons or stressed calibration" are named as not built) | [VAR_GUIDE §1](../guides/VAR_GUIDE.md#1-the-question-var-answers) |
| Backtesting, bank and node level | **Partly built.** Official mode: each official date's VaR against the next day's hypothetical P&L, per node of every hierarchy (bank, division, desk, book, legal entity, limit letters); exceptions over up to 250 days (`EXCEPTIONS_250`), the Basel light (0–4 green, 5–9 amber, 10+ red), Kupiec and Christoffersen (`Backtest.of`, `drishti-compute/.../var/Backtest.java`). The hypothetical P&L is **derived** (`HYPO_PNL`: the node's P&L under the newest scenario day); a bank's own hypothetical P&L table is planned. **Not built:** actual P&L, separate actual and hypothetical counts, the 97.5% desk test, ES backtests | [VAR_GUIDE §7, §18.4, §19](../guides/VAR_GUIDE.md#7-history-and-backtesting) |
| Multiplier from exceptions | **Not built** (the light is computed; the 3.00–4.00 table and OSFI's 1.50–2.00 add-on table are not) | VAR_GUIDE §18.4 |
| P&L attribution test | **Not built.** No RTPL or HPL series, no Spearman or KS | — |
| RFET, NMRF, SES | **Not built.** The market-data pack holds curves, surfaces and fixings, not real-price observations per risk factor | [FRTB.md](../contracts/FRTB.md) (SA only) |
| DRC | **Not built** (neither the IMA DRC nor the SA DRC; the SA's DRC is in FRTB.md's proposal, MAR22) | [FRTB.md §1](../contracts/FRTB.md#1-summary) |
| FRTB standardised approach (SBM + DRC + RRAO) | **Not built**, contract proposed, parked; per-trade sensitivities are stored today as the fundamental kind `frtb-sensitivity` | [FRTB.md](../contracts/FRTB.md); `config/packs/market-risk/contracts/frtb-sensitivity.contract.yaml` |
| Breakdown by desk and legal entity, diversification | **Built.** MGMT and LEGAL hierarchies and any configured one (limit letters); `LE:<id>` scope; each official node carries `STANDALONE_SUM` and `DIVERSIFICATION`; contributions (component, incremental, marginal) | [VAR_GUIDE §5.5, §15.14, §18.3, §19](../guides/VAR_GUIDE.md#55-scope-words-by-a-trades-attribute) |
| Breakdown by risk type | **Not built.** The per-risk-class split (`PNL_BY_RISK_CLASS`, IR, FX, CR, EQ, CO vectors adding to `PNL`) "was designed and is not in 2.8.0". A product-group rule portfolio is a stand-in, not a risk-class VaR | [VAR_GUIDE §15.2](../guides/VAR_GUIDE.md#152-pnl-vector-one-pl-vector-per-trade-as-built) |
| High, low, average, period end; 60-day and 12-week averages | **Partly.** Every official figure is published per node and date (`var-official`, append-only, result versions) and read by BI (the sample report *Market risk: VaR* shows the bank and a desk over time). No defined period statistics or averages yet | [VAR_GUIDE §19](../guides/VAR_GUIDE.md#19-official-end-of-day-results); `config/bi/reports/market-risk-var.report.yaml` |
| Limits and usage | **Built** for VaR limits: `var-limit` per node (2.9.7), `LIMIT` and `LIMIT_USE` in every official record; limit letters as a hierarchy. **Not built:** other limit types per desk (sensitivity, notional, stress), upper and lower limits, a limit catalogue with identifiers as Volcker needs | [VAR_GUIDE §15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits-as-built-295); [STR.md §3.5](../contracts/STR.md) (proposed `risk-limit`) |
| Sensitivities | **Partly:** stored per trade (`frtb-sensitivity`, `trade-valuation.DV01`); no engine aggregates them | FRTB.md, TVAL.md |
| Comprehensive P&L attribution (Volcker) | **Not built.** P&L explain is a proposed contract (actual = ΔMtM + cash; explained by sensitivities; new, amended, ended trades; fees; unexplained); a stored `pnl-explain` kind exists | [PNL.md §1](../contracts/PNL.md#1-summary) |
| Positions | **Built** as VaR positions derived from trades by configurable rules | [VAR_GUIDE §4](../guides/VAR_GUIDE.md#4-positions-derived-never-delivered) |
| Transaction volumes | **Not built** (trades and counterparties are held; no volume measure by counterparty type) | — |
| Reporting currency | **Built**: five currencies built ahead (USD, EUR, CAD, JPY, GBP); official records in `drishti.var.official.currencies` (default USD and EUR: **add CAD** for Canadian reports) | [VAR_GUIDE §15.10, §15.11, §12](../guides/VAR_GUIDE.md#1510-reporting-currency-as-built-294) |
| Retention and reproducibility | **Built.** Official captures of every node, each with its inputs' versions and batch ids, the position rules' digest, the membership record, result versions on restatement; append-only Delta or Iceberg table kept 7 years; recomputable from the same vectors and engine version | [VAR_GUIDE §19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) |
| The report layer | **Built:** BI datasets and reports over the published table, exports to PDF, PNG, Excel and CSV, subscriptions, paginated reports; the published table itself for downstream systems | [BI_GUIDE.md](../guides/BI_GUIDE.md), [BI_GUIDE.md](../guides/BI_GUIDE.md#33-distribution-scheduled-reports-export-subscriptions-alerts-and-embedding), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave) |
| Intraday, sharding | **Built** (intraday updates from files and Kafka; VaR sharded across servers). Not required by any report; useful for the daily limit metric | [VAR_GUIDE §8.4, §28](../guides/VAR_GUIDE.md#28-sharding-across-drishtinet-as-built-296) |

**One definition to settle.** Drishti's derived `HYPO_PNL` is the P&L of the date's members under the newest scenario day: the risk
model revaluing yesterday's move. Regulatory **HPL** is the front-office revaluation of yesterday's end-of-day positions at today's
market (and **APL** adds new trades, fees and reserves per the rule); Drishti's derived figure is closer to a **risk-theoretical P&L**
(RTPL). For any regulatory backtest the bank must supply HPL and APL (section 9); the derived figure stays useful as RTPL for a PLA test.

## 7. Gaps and what to build

Each design follows Drishti's rules: computed on demand from trade-grain inputs, never stored except as an official capture and its
published rows; official runs reproducible as of a date; BI reports, paginated reports and extracts as the report layer; a data
contract per new input kind. Sizes: **S** days, **M** one to three weeks, **L** more than three weeks, for one builder.

| # | Gap | Design sketch | Size | Depends on |
|---|---|---|---|---|
| G1 | **10-day VaR** | Two ways, both configurable per scenario set: (a) a 10-day scenario set delivered by the upstream engine (`HORIZON_DAYS: 10`, overlapping 10-day moves), loaded as a second set on the date; (b) √10 × the 1-day figure, labelled *scaled*, allowed by §217.205(b)(1) if justified. Official records gain `VAR_10D` and its method | S (scaling), M (second set) | Several scenario sets per date (VAR_GUIDE open question 6); the bank's choice of method |
| G2 | **Stressed VaR** | A stressed set (`SVAR-250`): the upstream engine revalues today's trades over the bank's chosen 12-month stress window; the engine computes VaR on it like any set; computed at least weekly (US) and published with its window dates. The what-if *stressed window* stays an analysis tool | M | G1's several sets; upstream P&L vectors over the stress window; the window chosen and justified by the bank |
| G3 | **Actual and hypothetical P&L feed** | A new fundamental kind `daily-pnl` (per trade or per desk and date: `APL`, `HPL`, fees, commissions, reserves, net interest, intraday, with the definitions as columns so each rule's exclusions can be applied); a data contract like `pnl-vector`'s; positions and nodes sum it | M | Product control's daily P&L; desk mapping |
| G4 | **Backtesting engine for regulation** | Extend `Backtest` to run per node, per confidence (99% and 97.5%), per P&L type (APL, HPL) with exceptions counted separately and the larger taken (OSFI) or both reported (US re-proposal); zone and multiplier tables as pack configuration (US 3.00–4.00; OSFI plus factor 1.50–2.00; the re-proposal's 1.5–2); unavailable P&L or VaR counted as an exception (OSFI) | M | G3 |
| G5 | **Period statistics** | A derived computation over the published official rows: per node and period (quarter, fiscal year with the bank's year end), high, low, mean, period end; rolling 60-business-day average of VaR (and of IMCC later), 12-week average of stressed VaR; the capital figure max(latest, multiplier × average). Computed when asked from official records, never stored beyond the official capture | S | Official history (built); G1, G2 for the US figures |
| G6 | **VaR by risk type** | The designed `PNL_BY_RISK_CLASS` vectors (IR, CS, EQ, FX, CO) delivered per trade beside `PNL`, adding up to it; the engine computes each class's VaR and the diversification line (total − Σ class) exactly like nodes today | M | The upstream engine revaluing by risk class |
| G7 | **Report packs** | Per report, a BI dataset over the published tables, a paginated report in the report's layout, and an extract (Excel, CSV) with the report's own identifiers where public: MD&A VaR table; Pillar 3 MR1 and MR2; FFIEC 102 VaR and stressed VaR sections; §217.212 quantitative table; Volcker metric files per the agencies' technical specifications. Drishti produces the figures and the files; the bank files them | M per report family | G1–G6 for content; the forms' current line identifiers from the bank's reporting team |
| G8 | **Volcker metrics** | Per desk and trading day: limits and usage for every limit type (a `risk-limit` catalogue with ids, upper and lower, as STR.md §3.5 proposes); VaR from the engine; comprehensive P&L attribution from the P&L explain engine (PNL.md) split into existing and new positions; positions from the derived positions; transaction volumes by counterparty type from trades | L | G3, PNL.md, a limit catalogue, desk metadata (trading calendar, covered-activity types) |
| G9 | **FRTB standardised approach** | Build the proposed [FRTB.md](../contracts/FRTB.md): SBM from the `sensitivity` table (delta, vega, curvature, three correlation scenarios), SA DRC, RRAO; any scope; official capture; BCAR 90.010 sections B and C, Pillar 3 MR1 | L | Per-trade sensitivities and curvature shocks from the upstream engine; issuer data for DRC |
| G10 | **ES with liquidity horizons** | The upstream engine delivers, per trade, the 10-day P&L vector with all risk factors shocked and four more with only the factors whose liquidity horizon is at least 20, 40, 60, 120 days; Drishti computes ES_T per vector at 97.5% and the cascade √(Σ (ES_T(P, j) √((LH_j − LH_{j−1})/T))²); unconstrained and per risk class; IMCC | L | Liquidity-horizon mapping of risk factors; five vectors per trade (sizing: five times today's memory per date) |
| G11 | **Stressed ES calibration** | ES_R,S on the reduced set over the stress window, scaled by ES_F,C / ES_R,C floored at 1; the reduced set's 75% explanation test (OSFI) or out-of-sample R² (US re-proposal) | L | G10; vectors on the reduced set over the stress window |
| G12 | **PLA test** | Per desk: RTPL (the risk model's P&L for the day, which Drishti's derived `HYPO_PNL` approximates) against HPL (G3) over 250 days; Spearman and KS; zones; Spearman switchable off for the US re-proposal | M | G3; desk definitions |
| G13 | **RFET, NMRF and SES** | A new kind of real-price observations (trade prints, committed quotes, vendor data) mapped to risk factors; the counts (24 a year with no 90-day gap of fewer than 4, or 100 in 12 months) monthly; SES per NMRF from its stress scenario; aggregation with ρ = 0.6 in the three categories | L | Observation data and a risk-factor taxonomy (often licensed or pooled) |
| G14 | **IMA DRC** | A two-factor default simulation at 99.9% over one year with constant positions; issuer PD, LGD, seniority | L | Issuer-level default data |
| G15 | **CAD official records** | Add CAD to `drishti.var.official.currencies` for a Canadian bank | S (configuration) | — |

## 8. A proposed roadmap

Ordered by value and dependency. **Phase 1 is release 2.9.8** (product owner, 2026-10-30), with the report-definition framework of
section 8.1 and one pilot pack; phase 2 follows in 2.9.9; phases 3a and 3b after that.

| Phase | When | Builds | Unlocks |
|---|---|---|---|
| **1. The measures** | 2.9.8 | G1 10-day VaR, G2 stressed VaR, G3 the actual and hypothetical P&L feed, G4 the regulatory backtesting engine, G5 period statistics, G15 CAD records | The figures behind US subpart F, FFIEC 102 and §217.212; the bank-wide and desk backtests both countries use; the MD&A's year-end, average, high and low |
| **2. The report packs** | 2.9.9 | G6 VaR by risk type; G7 the packs: Canada MD&A table and Pillar 3 MR1/MR2 shells, US FFIEC 102 and §217.212 tables; G8 Volcker metrics | Canada MD&A; FFIEC 102; §217.212; Volcker's five metrics |
| **3a. FRTB standardised** | after that | G9 SBM, SA DRC, RRAO | BCAR 90.010 sections B and C; Pillar 3 MR1; the US re-proposal's standardised approach if adopted |
| **3b. FRTB internal models** | after that, only for a bank seeking approval | G10 ES with liquidity horizons, G11 stressed calibration, G12 PLA, G13 RFET and SES, G14 DRC | BCAR 90.010 section A; Pillar 3 MR2; OSFI IMA approval tests; the US re-proposal's models-based approach |

**Why 3a before 3b.** In Canada every bank reports the SA, and an IMA bank must also compute it (CAR 9.5, BCAR 90.010); the US
re-proposal makes a standardised approach the fallback for every firm. The SA serves every client; the IMA serves only banks that apply
for it.

### 8.1 The report-definition framework (2.9.8)

Reports are produced **automatically, with human sign-off**: Drishti computes, fills, checks, freezes and traces every figure; people
approve, write the narrative and submit. Each report is configuration, as packs and editions are, so a new report is mostly a file.

```yaml
# config/reports/annual-report-var.yaml (illustrative)
report: annual-report-var
title: Trading VaR by risk type (annual report)
period: { kind: year, ends: 10-31 }            # the bank's fiscal year
measure: { name: VAR, confidence: 0.99, horizon: 1d, method: official }
scope: BANK
rows:                                          # one row per risk type, then diversification and total
  - { label: Interest rate,  filter: { RISK_TYPE: IR } }
  - { label: Foreign exchange, filter: { RISK_TYPE: FX } }
  - { label: Equity,         filter: { RISK_TYPE: EQ } }
  - { label: Commodity,      filter: { RISK_TYPE: CMD } }
  - { label: Credit spread,  filter: { RISK_TYPE: CS } }
  - { label: Diversification, compute: total - sum(rows) }
  - { label: Total,          filter: {} }
columns: [period-end, average, high, low]      # period statistics over the official runs (G5)
checks:
  - total equals the official VAR BANK of each date
  - same total as pillar3-mr2 and ffiec-102 for the same date
  - movement over 10% against last year needs a note
outputs: [xlsx, csv, bi-report]
sign-off: [maker, checker, CRO]
```

**The steps:**
1. On the period end, the generator reads the stored official runs (never recomputing a filed number).
2. It fills every cell from computed measures.
3. It runs the checks.
4. It freezes the result with each figure's lineage (official run, data versions, code version).
5. It opens a review in BI.
6. It records the maker-checker sign-off.
7. It hands the bank's reporting team the file for submission.

The narrative sections are pre-filled with the facts (exceptions, their dates, the desks that drove them) and owned by a person.

### 8.2 Phase 1 design (2.9.8)

The design follows Drishti's rules: **computed on demand from trade-grain inputs, never stored** (COMPUTED_ATTRIBUTES.md); the
official end-of-day captures are the **only** stored figures and are append-only; every part is **configuration**; every figure carries
its lineage. Names below are proposals; the build may refine them, and this section is updated "as built".

#### 8.2.1 The parts and how they fit

```
 pnl-vector (1-day, 509)  ─┐
 pnl-vector-10d (509)     ─┼─► VaR engine (as built, more vector sets) ─► official capture (var-official, more measures)
 pnl-vector-stressed      ─┘                                                   │
                                                                               ├─► period statistics (G5)  ──┐
 desk-pnl (actual, hypothetical) ─► backtesting engine (G4) ───────────────────┘                             │
                                                                                                             ▼
                                         config/reports/<report>.yaml ─► report generator ─► checks ─► freeze + lineage
                                                                                                             │
                                                                         review (BI report) ─► sign-off ─► file for submission
```

#### 8.2.2 10-day and stressed VaR (G1, G2)

| Part | Design |
|---|---|
| Inputs | Two more vector sets per position beside `pnl-vector`: **10-day** (overlapping 10-day returns over the same window, 509 values) and **stressed** (the configured stress window, 250 values; its 10-day form 250). A source may send them, or Drishti derives the 10-day set from the 1-day scenario P&Ls where the bank allows it. A setting chooses **√10 scaling** instead, which needs no new input |
| Configuration | the market-risk pack's `var:` block gains `horizons: [1d, 10d]`, `ten-day: overlapping \| sqrt10`, `stressed: { window: 2008-07-01..2009-06-30, scenarios: 250, refresh: weekly }`; the stress window is the bank's choice and recorded with every run |
| Engine | the same hot file, one section per vector set (the format gains sections, as `CTOT` and `CELL` were added); sums, quantiles and ES unchanged; a scope asks for `horizon` and `stressed` |
| Commands | `VAR <scope> 10D`, `VAR <scope> SVAR`, `VAR <scope> SVAR 10D`; the VaR page shows the four figures side by side |
| Official capture | `var-official` rows gain `VAR_10D`, `SVAR_1D`, `SVAR_10D` (and ES for each); old rows read as null; the capture records the stress window and the 10-day method |
| Capacity | the 1,518 values a position already sized in the capacity model and both VaR decks |

#### 8.2.3 The P&L feed (G3)

| Part | Design |
|---|---|
| Kind `desk-pnl` | one record per desk (and per legal entity) per business date: `ACTUAL_PNL` (finance's clean P&L: fees, commissions, reserves, net interest income and intraday trading excluded, as the US rule defines), `HYPOTHETICAL_PNL` (yesterday's positions revalued on today's market by front office), `CURRENCY`, `SOURCE`, `AS_OF`; a data contract, JSON Lines into the lake like every kind |
| Drishti's own | today's `HYPO_PNL` (the risk model's P&L for the newest scenario day) is kept and **renamed risk-theoretical P&L** (`RTPL`), which is what the FRTB PLA test needs later |
| Checks | a desk in the hierarchy without P&L on a date is a data-quality warning; desk P&L summed to the bank against finance's bank total |

#### 8.2.4 The backtesting engine (G4)

| Part | Design |
|---|---|
| Kind `var-backtest` (computed, `VBT`) | per scope (bank, desk, legal entity) and date: VaR 99% (and 97.5%) 1-day from the **official** capture of the day before, actual and hypothetical P&L of the day, `EXCEPTION_ACTUAL`, `EXCEPTION_HYPO`, the counts over the last 250 business days, the **zone** (green 0–4, amber 5–9, red 10+) and the **multiplier** (US 3.00 to 4.00 by the rule's table; Canada's IMA add-on as configured) |
| Never stored | derived from the official captures and `desk-pnl` on request; the official capture itself is the record |
| Pages | a backtesting chart (P&L bars against the VaR line, exceptions marked) for the bank and every desk; a table of exceptions with their dates and the desks that drove them |
| Configuration | `backtesting: { confidences: [0.99, 0.975], window: 250, zones: {green: 4, amber: 9}, multipliers: us-217 }` |

#### 8.2.5 Period statistics (G5)

Computed measures over the official captures: `period-end`, `average`, `high`, `low` for a quarter or a fiscal year; the
**60-day average** and the **12-week stressed-VaR average** the capital formulas use. Command `VAR <scope> STATS <period>` (for example
`VAR BANK STATS FY2026`); in BI, a measure on the official dataset. A missing official capture inside the period is named, never skipped
silently.

#### 8.2.6 The report-definition framework

| Part | Design |
|---|---|
| Report definition | `config/reports/<report>.yaml` (section 8.1): period, measure, scope, rows (filters or computed rows), columns (statistics), checks, outputs, sign-off chain; validated at start like every config (a wrong field names the file and line) |
| Generator | for a report and a period: reads the official captures and computed measures, fills every cell, never recomputes a filed figure; command `RPT <report> <period>` and, proposed, a `report run` subcommand of the CLI; scheduled on the period end |
| Checks | declarative, from the definition: equalities (a total equals the official figure), cross-report equalities (the same VaR in two reports), sums, movements over a threshold that need a note, missing data; a failed check blocks sign-off, never the generation |
| Freeze and lineage | a generated report is a **frozen run**: its cells, each cell's lineage (official capture id, data versions, code version, definition version) and the check results, in the content store; re-running a period makes a new version, never overwrites |
| Review | the run opens as a BI report; every cell drills to its lineage and on to the VaR page of that date |
| Sign-off | maker, checker, then the approver named in the definition; each step a record (who, when, the run version, a comment); a changed run needs a new sign-off |
| Outputs | xlsx and csv for the bank's reporting team, a PDF for the file; XBRL or a regulator's format later, per report |
| Narrative | pre-filled facts (exceptions, dates, desks, movements); the text is a person's, edited in the review |

#### 8.2.7 The pilot pack

`annual-report-var` (section 8.1's example: 1-day 99% VaR by risk type with diversification; year-end, average, high, low) and
`var-backtesting-chart` (the bank's 250-day chart). They need G1 only for the 10-day line the US adds later; the pilot runs on 1-day VaR,
the P&L feed, backtesting and period statistics. **Done** when: the pilot is generated for the demo's last fiscal year, every check
passes, every cell drills to its official capture, sign-off is recorded, and the figures reconcile to the VaR page.

#### 8.2.8 Tests and documentation

- **Tests:** the four VaR figures against an independent recomputation on the test data; backtesting zones and multipliers on crafted P&L
  (0, 4, 5, 9, 10 exceptions); period statistics against a hand-computed year; a report definition with a failing check (sign-off
  blocked); a frozen run unchanged after a data restatement; lineage for every cell; the pilot end to end in the browser.
- **Docs:** VAR_GUIDE (10-day, stressed, backtesting, statistics); a new REPORTS guide (definitions, checks, sign-off); data contracts for
  `desk-pnl` and `var-backtest`; a tutorial (generate and sign off the pilot); help entries; this section updated "as built".

#### 8.2.9 Decisions for the product owner before the build

1. The stress window for the demo data (a 12-month period inside the scenario history) and its refresh frequency.
2. ~~10-day method~~ **Decided (product owner, 2026-10-30):** √10 and the 10-day sum of 1-day P&Ls, both selectable on screen, built in 2.9.7; full 10-day revaluation later as a second vector set. Stressed VaR is left out for now.
3. The demo's `desk-pnl`: generated alongside the test data (actual = hypothetical + noise and fees), with real bank feeds later.
4. The fiscal year end of the demo (October 31, as Canadian banks, or December 31).
5. Outputs in 2.9.8: xlsx, csv and PDF, with XBRL left for a later release.

## 9. Data the bank must supply

| Data | Grain and cadence | Needed by | Note |
|---|---|---|---|
| Actual P&L (APL) and hypothetical P&L (HPL), with fees, commissions, reserves, net interest and intraday separated | Per desk (better per trade) per business day, from product control | G3, G4, G12; every backtest | US backtesting excludes fees, commissions, reserves, net interest income and intraday trading; OSFI uses both APL and HPL |
| 10-day P&L vectors or the bank's approval for scaling | Per trade per date | G1 | |
| P&L vectors over the stress window | Per trade per date (weekly at least for US stressed VaR) | G2, G11 | The bank chooses and justifies the window; OSFI's must include 2007-09 for ES |
| P&L vectors by risk class | Per trade per date | G6 | IR, CS, EQ, FX, CO |
| Trading-desk definitions | Per desk: regulatory desk id, model-eligible flag (Canada IMA), Volcker desk id, covered-activity types, trading-day calendar | G4, G8, G12 | OSFI approves desk definitions as part of model approval |
| Limit catalogue | Per desk: every limit with id, type, upper and lower bound | G8 | Volcker reports every limit, not only VaR |
| Sensitivities and curvature shocks | Per trade per date, FRTB risk factors and buckets | G9 | Today's `frtb-sensitivity` kind is the starting point |
| Liquidity-horizon mapping and liquidity-horizon vectors | Per risk factor; five vectors per trade per date | G10 | |
| Real-price observations | Per risk factor, daily | G13 | Often from vendors or data pools; licensing |
| Issuer default data | Per issuer: PD, LGD, seniority | G9 (SA DRC), G14 | |
| Supervisory parameters | Multiplier or plus factor currently imposed; any add-ons | G4, G5 | Set by the supervisor, not computed |
| Fiscal calendar and reporting currency | Year end (October for the D-SIBs), CAD | G5, G7, G15 | |

## 10. Risks and open questions for the product owner

1. **Which client, which regime.** A Canadian bank on the SA needs the MD&A VaR and, for its US IHC, the Basel 2.5 set and perhaps
   Volcker. Is the first target the MD&A and the US set (phases 1 and 2), or the SA (phase 3a)?
2. **The US rule is moving.** The re-proposal is not final. Building phase 1 to the current rule is safe (it is in force); FRTB-style
   pieces should keep the Basel parameters as configuration (Spearman on or off, the multiplier table, the thresholds) so a final US
   rule is a settings change.
3. **Drishti is not a filing tool.** It computes and lays out the figures; submissions go through the bank's own channels and
   controls. Should the report packs aim at the forms' line identifiers (which needs the current forms from the bank), or at
   management versions of the tables?
4. **The definition of hypothetical P&L** (section 6): the derived `HYPO_PNL` should be renamed or documented as a risk-theoretical
   P&L once a bank's HPL arrives, so no regulatory backtest runs on it by mistake.
5. **Data volume of liquidity horizons.** Five vectors per trade per date multiplies the hot file and the memory per date by five;
   sharding (built) is the answer, but the sizing must be redone before phase 3b.
6. **Real-price observations** are often licensed; the RFET may need a data-pool partner.
7. **§217.212 for an IHC**: whether the exemption for subsidiaries of foreign banks applies is for counsel (section 4.1).
8. **Model governance.** A regulatory VaR is a model under the bank's model risk policy (OSFI E-23, SR 11-7 in the US); Drishti's
   reproducibility helps validation but the bank owns the approval. Neither guideline was checked for this document.
9. **Two figures not confirmed:** the current FFIEC 102 line numbers (ffiec.gov refused automated access) and the content of the FR
   Y-14Q Trading schedule; both should be read from the forms before phase 2.

## 11. Glossary

| Term | Meaning |
|---|---|
| APL, HPL, RTPL | Actual P&L (the day's P&L including new trades, as the rule defines it); hypothetical P&L (yesterday's end-of-day positions revalued at today's market by front-office models); risk-theoretical P&L (the same, by the risk model) |
| BCAR | OSFI's Basel Capital Adequacy Reporting return; schedule 90.010 is market risk |
| CAR | OSFI's Capital Adequacy Requirements guideline; chapter 9 is market risk, chapter 8 CVA |
| D-SIB | Domestic systemically important bank (six in Canada) |
| DRC, IRC | Default risk charge (FRTB); incremental risk charge (Basel 2.5) |
| ES | Expected shortfall: the average loss beyond the quantile (97.5% under FRTB) |
| FFIEC 102 | The US Market Risk Regulatory Report |
| FRTB, MAR | The Fundamental Review of the Trading Book; the Basel Framework's market-risk chapters |
| IHC | US intermediate holding company of a foreign banking organisation (12 CFR 252.153) |
| IMA, SA | Internal models approach; standardised approach |
| IMCC | Internally modelled capital charge: ½ unconstrained ES + ½ Σ risk-class ES |
| Liquidity horizon | The time assumed to exit or hedge a risk factor under stress: 10, 20, 40, 60 or 120 days |
| NMRF, SES, RFET | Non-modellable risk factor; its stressed expected shortfall; the risk factor eligibility test |
| PLA test | P&L attribution test: RTPL against HPL by Spearman correlation and the Kolmogorov-Smirnov statistic |
| SBM, RRAO | Sensitivities-based method; residual risk add-on (FRTB SA) |
| Stressed VaR | VaR on a 12-month period of significant financial stress (Basel 2.5) |
| Traffic light | Backtesting zones from exceptions in 250 days at 99%: 0–4 green, 5–9 amber, 10+ red |
| Volcker metrics | The quantitative measurements of 12 CFR 248 appendix A |

## 12. Sources

Opened and read on 2026-10-30 unless noted.

**Canada**
- OSFI, [CAR 2026 chapter 9, Market Risk](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-2026-chapter-9-market-risk) (effective November 2025 / January 2026): scope, 9.5, 9.6 (backtesting, PLA, RFET, ES, SES, DRC, aggregation).
- OSFI, [CAR 2027 guideline](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/capital-adequacy-requirements-car-guideline-2027) (final, published 10 September 2026, effective November 2026 / January 2027). Its chapter 9 changes were **not** compared with CAR 2026 for this document.
- OSFI, [Update on Basel III implementation (November 2021)](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/update-basel-iii-implementation-ahead-final-rules-release-january-2022): market risk and CVA in Q1 2024.
- OSFI, [2026 BCAR instructions](https://www.osfi-bsif.gc.ca/en/data-forms/reporting-returns/filing-financial-returns/financial-reporting-instructions/2026-basel-capital-adequacy-reporting-bcar): schedule 90.010, frequency, filing deadline.
- OSFI, [Pillar 3 Disclosure Guideline for D-SIBs (2025)](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/pillar-3-disclosure-guideline-domestic-systemically-important-banks-sibs-2025) and its [templates workbook](https://www.osfi-bsif.gc.ca/sites/default/files/documents/pillar3-pilier3-dsib-bisi-2025-en.xlsx): MRA, MR1, MRB, MR2 and their frequency and effective date.
- OSFI, [Systemically important banks](https://www.osfi-bsif.gc.ca/en/supervision/financial-institutions/banks/systemically-important-banks); [E-18 Stress Testing](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/stress-testing-guideline-2009); [E-19 ICAAP](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/internal-capital-adequacy-assessment-process-icaap-deposit-taking-institutions-guideline-2010) (found by search; not read in full).
- Royal Bank of Canada, [Annual Report 2025, 40-F exhibit](https://www.sec.gov/Archives/edgar/data/1000275/000119312525305927/d95203dex2.htm): Table 48, IFRS 7 marking.
- Risk.net, [No Canadian banks using internal models as FRTB kicks in](https://www.risk.net/regulation/7959017/no-canadian-banks-using-internal-models-as-frtb-kicks-in) (secondary, paywalled; seen through a search summary only, **not confirmed**).

**US**
- eCFR, [12 CFR part 217 subpart F](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-217/subpart-F) (read through the eCFR API, as of 2026-10-28): §§217.201, 204, 205, 206, 212.
- eCFR, [12 CFR part 248](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-248): §248.2 (significant trading assets and liabilities), §248.20(d), appendix A.
- eCFR, [12 CFR 252.153](https://www.ecfr.gov/current/title-12/chapter-II/subchapter-A/part-252) (IHC requirement); [17 CFR 229.305](https://www.ecfr.gov/current/title-17/chapter-II/part-229/subpart-229.300/section-229.305) (Item 305).
- Federal Reserve, [FFIEC 102 page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FFIEC_102); Federal Register [2015-03265](https://www.govinfo.gov/content/pkg/FR-2015-02-18/html/2015-03265.htm) and [2025-07631](https://www.govinfo.gov/content/pkg/FR-2025-05-02/html/2025-07631.htm). The [FFIEC page](https://www.ffiec.gov/resources/reporting-forms/ffiec102-background) **could not be opened** (HTTP 403 to automated access).
- Federal Reserve, [FR Y-9C page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FR_Y-9C) and its current form (HC-R item 27, memorandum item 4); [FR Y-14Q page](https://www.federalreserve.gov/apps/reportingforms/Report/Index/FR_Y-14Q) (the form itself not opened).
- Federal Reserve, [press release of 19 March 2026](https://www.federalreserve.gov/newsevents/pressreleases/bcreg20260319a.htm), [fact sheet](https://www.federalreserve.gov/newsevents/pressreleases/files/fact-sheet-20260319.pdf), [Board memo](https://www.federalreserve.gov/aboutthefed/boardmeetings/files/board-memo-basel-gsib-standardized-approach-20260319.pdf); Federal Register [2026-05959, 91 FR 14952](https://www.federalregister.gov/documents/2026/03/27/2026-05959/regulatory-capital-rule-category-i-and-ii-banking-organizations-banking-organizations-with) (full text read); [OCC bulletin 2026-9](https://www.occ.gov/news-issuances/bulletins/2026/bulletin-2026-9.html). A **final rule was searched for and not found** (Federal Register API: `effective_on` null).
- Volcker 2019 amendments: [OCC bulletin 2019-56](https://www.occ.gov/news-issuances/bulletins/2019/bulletin-2019-56.html), [SEC release 2019-207](https://www.sec.gov/newsroom/press-releases/2019-207), [Federal Register 2019-22695](https://www.federalregister.gov/documents/2019/11/14/2019-22695/prohibitions-and-restrictions-on-proprietary-trading-and-certain-interests-in-and-relationships-with). No later proposal to change appendix A was found.

**Drishti** (develop at 895e8612d): [VAR_GUIDE.md](../guides/VAR_GUIDE.md), [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md),
[COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), [FRTB.md](../contracts/FRTB.md),
[PNL.md](../contracts/PNL.md), [SACCR.md](../contracts/SACCR.md), [STR.md](../contracts/STR.md), `drishti-compute/src/main/java/com/ash/drishti/compute/var/Backtest.java`,
`config/packs/market-risk/contracts/`, `config/bi/reports/market-risk-var.report.yaml`.
