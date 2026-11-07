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
# IFRS 9 expected credit loss: staging, PD, LGD, EAD, scenarios and the provision explained

Status: design, not built. Requested by the product owner on 2026-11-02 ("put IFRS 9 expected credit loss on the roadmap; design
first"); proposed for release 2.9.20, placement to confirm ([IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#2920-ifrs-9-expected-credit-loss-proposed)).
It follows the engine pattern of [FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md): the method with its formulas, the data
contracts, the API, command line and screens, the playground with its critique-and-reproduce switches, hand-built cases, parameters as
configuration and the decisions.

Drishti computes the IFRS 9 impairment allowance the way it computes VaR: **from facility-grain inputs, when asked, for any scope, never
stored as a figure** except as an official month-end capture. The bank delivers its facilities, their staging attributes, its PD term
structures (or migration matrices), its LGD parameters, its collateral and its macro scenarios; Drishti stages every facility, computes
12-month or lifetime ECL under each scenario, weights the scenarios, applies the approved overlays, aggregates to any scope and explains
every move of the provision between two dates in exact steps. Every number is computed in Java on the server; the console only displays.

## Contents

- [1. Scope and principles](#1-scope-and-principles)
- [2. The method](#2-the-method)
- [3. Staging](#3-staging)
- [4. PD term structures](#4-pd-term-structures)
- [5. LGD](#5-lgd)
- [6. EAD and the cash-flow profile](#6-ead-and-the-cash-flow-profile)
- [7. Forward-looking scenarios](#7-forward-looking-scenarios)
- [8. Management overlays](#8-management-overlays)
- [9. Purchased or originated credit-impaired assets](#9-purchased-or-originated-credit-impaired-assets)
- [10. Scopes and aggregation](#10-scopes-and-aggregation)
- [11. Why the provision moved](#11-why-the-provision-moved)
- [12. Inputs: the data contracts](#12-inputs-the-data-contracts)
- [13. Outputs](#13-outputs)
- [14. API, command line and screens](#14-api-command-line-and-screens)
- [15. The playground and its workbook](#15-the-playground-and-its-workbook)
- [16. Hand-built cases](#16-hand-built-cases)
- [17. Parameters as configuration](#17-parameters-as-configuration)
- [18. Sizing](#18-sizing)
- [19. Regulatory interaction: OSFI and Basel](#19-regulatory-interaction-osfi-and-basel)
- [20. Decisions](#20-decisions)
- [21. Build checklist](#21-build-checklist)
- [22. Sources](#22-sources)

## 1. Scope and principles

| Part | What it is | IFRS 9 reference |
|---|---|---|
| Scope of impairment | financial assets at amortised cost and at fair value through OCI (debt), loan commitments and financial guarantees not at fair value through profit or loss, lease and contract assets | 5.5.1, 2.1(e), 2.1(g) |
| 12-month ECL | the losses from defaults possible within 12 months of the reporting date, for a facility whose credit risk has not increased significantly since initial recognition (stage 1) | 5.5.5, Appendix A |
| Lifetime ECL | the losses from every default possible over the expected life, for a facility with a significant increase in credit risk (stage 2) or credit-impaired (stage 3) | 5.5.3 |
| Measurement | unbiased and probability-weighted, discounted, from reasonable and supportable information including forecasts | 5.5.17 to 5.5.20 |
| POCI | purchased or originated credit-impaired: lifetime changes since initial recognition, at the credit-adjusted EIR | 5.5.13, 5.5.14 |
| Disclosure | the reconciliation of the allowance by stage and the drivers of its change | IFRS 7.35H, 35I |

**Principles.**

1. **Computed, never stored.** An allowance is computed for the scope asked (a portfolio, a segment, a legal entity, a branch, one
   facility, an ad-hoc set) on a business date, and kept only as an official capture (month end, quarter end, year end), as VaR is.
2. **One method, every switch visible.** Each choice the method makes (the SICR thresholds, the discounting convention, the weighting
   of scenarios, overlays on or off) is a named switch with a decided default, shown on the screen and in the workbook
   ([section 15.2](#152-critique-and-reproduce-the-method-choices-as-switches)).
3. **Parameters are configuration with their source.** Thresholds, backstops, cure periods, scenario weights and CCFs live in
   `config/engines/ifrs9.yaml` and its parameter files, each value naming the bank policy or paragraph it comes from (section 17). The
   bank owns every parameter; Drishti ships documented defaults, never the bank's numbers.
4. **The bank's models are inputs.** Drishti does not estimate PD, LGD or macro models from history in this release: it consumes the
   bank's PD curves or migration matrices, its LGD parameters and its scenario paths, and applies them exactly. Model development stays
   with the bank's credit-risk modelling team (decision 4).
5. **Every figure explains itself.** A facility's ECL shows its stage and the rule that set it, each period's marginal PD, LGD, EAD and
   discount factor, and each scenario's ECL; a scope's provision change explains itself in exact steps (section 11).
6. **Nothing is guessed.** A facility without a PD curve for its segment and rating, or with a missing origination PD, is refused and
   counted; a missing input is shown as missing, never as zero.
7. **Switchable.** The engine is off as shipped (`enabled: false` in its engine file) and on in the demo; when off, its commands answer
   "not active" everywhere, as every switched-off command does.

## 2. The method

For facility *i*, scenario *s* and periods *t = 1 … T* of length Δ (a month by default):

```
ECL_i,s = Σ_{t=1..T_i}  mPD_i,s(t) · LGD_i,s(t) · EAD_i(t) · DF_i(t)

mPD_i,s(t) = S_i,s(t−1) − S_i,s(t)          the marginal (unconditional) probability of default in period t
S_i,s(t)   = 1 − CPD_i,s(t)                  survival to the end of period t
DF_i(t)    = (1 + EIR_i)^(−τ(t))             τ(t) in years to the assumed default point of period t (end of period by default)

T_i = the 12-month horizon (stage 1), min(12 months, remaining life)
    = the expected life (stages 2 and 3, POCI): the contractual maturity, or the behavioural life of a revolving facility (5.5.20)

ECL_i  = Σ_s w_s · ECL_i,s                   the probability-weighted ECL (weights sum to 1)
ECL_i* = ECL_i + overlay_i                   after the approved management overlays (section 8)
```

**Stage 3** facilities are in default: `mPD(1) = 1` and the ECL is `EAD · LGD_default`, where `LGD_default` is the bank's
expected loss given default for the defaulted state (already discounted to the reporting date from the expected recoveries), unless the
bank delivers expected recovery cash flows, in which case `ECL = gross carrying amount − Σ PV(recoveries at EIR)`.

**Interest revenue** (5.4.1): on the gross carrying amount for stages 1 and 2, on the amortised cost (gross less the allowance) for stage 3,
and at the credit-adjusted EIR for POCI. Drishti reports the interest base by stage; the bank's ledger books it.

**Default** follows the bank's definition, consistent with its credit risk management, with the rebuttable presumption at 90 days past due
(B5.5.37); the setting `default.dpd-presumption` is 90 and a rebuttal is a facility flag delivered with its reason.

## 3. Staging

A facility's stage on the business date is set by the first rule that applies, in this order (every rule a switch with a setting):

| # | Rule | Default | Source |
|---|---|---|---|
| 1 | **POCI** stays POCI for life | flag `POCI` on the facility | 5.5.13 |
| 2 | **Credit-impaired** → stage 3: in default (90 DPD, unlikely to pay, bankruptcy, distressed restructuring), or the bank's credit-impaired flag | `stage3.dpd: 90` | Appendix A, B5.5.37 |
| 3 | **30 DPD backstop** → stage 2 | `sicr.dpd-backstop: 30`; a rebuttal only with a flag and a reason | 5.5.11 |
| 4 | **Watchlist** → stage 2 | the bank's watchlist flag (`WATCHLIST = Y`) | B5.5.17 (qualitative indicators) |
| 5 | **Forborne performing** → stage 2 | `FORBORNE = Y` while the probation runs | B5.5.17(k), bank policy |
| 6 | **Low credit risk exemption** → stage 1 | off by default; when on, a facility rated at or better than `sicr.low-credit-risk.max-rating` (investment grade, internal BBB− or better) stays in stage 1 unless rule 3 applies | 5.5.10, B5.5.22 to B5.5.24 |
| 7 | **PD deterioration** → stage 2: the lifetime PD over the remaining life now, against the lifetime PD for the same remaining life expected at origination | relative: `PD_now / PD_orig ≥ 2.0` **and** absolute: `PD_now − PD_orig ≥ 0.5 %` (both, by default) | 5.5.9, B5.5.13 |
| 8 | otherwise stage 1 | | |

**The comparison PD.** IFRS 9 compares the risk of default over the expected life (5.5.9). The engine compares the cumulative PD over
the remaining life from today's curve with the cumulative PD over the same remaining period that the origination curve forecast for this
point (`PD_orig = 1 − S_orig(age + remaining) / S_orig(age)`). A bank that stages on 12-month PD as a proxy (B5.5.13 allows it when
reasonable) sets `sicr.pd-measure: twelve-month`.

**Per scenario or on the weighted PD.** By default staging uses the probability-weighted lifetime PD (one stage per facility); the
switch `staging-basis: per-scenario` stages per scenario and weights the scenario ECLs, the alternative some banks use. Both are shown
side by side in the playground.

**Cure (a bank policy, not an IFRS 9 rule).** A facility leaving a stage-2 or stage-3 trigger moves back only after a probation:
`cure.stage2-to-1: 3` months meeting every stage-1 condition, `cure.stage3-to-2: 12` months for a restructured facility, 3 months
otherwise. The engine keeps each facility's staging history (the stage each business date and the rule that set it) so the probation is
counted from data, and the history is an output (`ecl-stage-history`).

**Every stage carries its reason**: the rule number, the measured values (DPD 31, ratio 2.5, absolute +4.5 %) and the thresholds in force,
so the transfer reports of IFRS 7.35H can be read row by row.

## 4. PD term structures

Two delivery forms, per segment (the bank's PD model segment, for example `RETAIL-MORTGAGE-INSURED`) and rating grade, per scenario:

| Form | Input | How the engine gets `CPD(t)` |
|---|---|---|
| **PD curve** | `pd-curve`: cumulative PD at tenors (1, 2, 3 … years, or months), per segment × rating × scenario × business date | log-linear interpolation of survival `S(t)` between the delivered tenors (switch: linear in CPD); flat hazard after the last tenor |
| **Migration matrix** | `rating-migration-matrix`: a one-period transition matrix per segment × scenario × period (a point-in-time matrix for each forecast year, then a long-run matrix) | `CPD_r(t) = (M_1 · M_2 · … · M_t)[r, D]`, the default column of the product of the period matrices from the facility's current rating *r* |

The matrices are checked on load: rows sum to 1 within 1e-9, entries in [0, 1], the default state absorbing. A forecast for the first
three years (the reasonable and supportable period, `scenarios.forecast-years: 3`) reverts to the long-run matrix or curve over
`scenarios.reversion-years: 2` (linear in hazard), as banks commonly do; both are settings.

Monthly periods from annual inputs: the hazard of a year is spread evenly over its months (`S(month m of year y) = S(y−1) · (S(y)/S(y−1))^(m/12)`).

## 5. LGD

**Unsecured:** the bank's LGD per segment, seniority and scenario (`lgd-parameter`), optionally by time since default.

**Secured** (per facility, from its collateral):

```
C_eff  = Σ_k  min(value_k, allocated_k) · (1 − h_k)      collateral after haircut (h: price decline in the scenario, costs, time to sell)
covered    = min(EAD(t), C_eff)
uncovered  = EAD(t) − covered
LGD(t)     = (covered · LGD_secured + uncovered · LGD_unsecured) / EAD(t)
```

`h_k` is the haircut of the collateral type in the scenario (a property price index decline in the downside, for example); `LGD_secured`
covers the costs and time to realise (a floor, 10 % by default for residential property). Collateral shared by several facilities is
allocated in the bank's order (`allocated_k`); without one, pro rata to EAD. A guarantee is recognised as collateral of type `GUARANTEE`
when it is integral to the contract (B5.5.55), with the guarantor's PD available for a substitution switch (off by default).

## 6. EAD and the cash-flow profile

| Product | `EAD(t)` | Settings |
|---|---|---|
| Amortising loan (annuity, linear) | the scheduled balance at the start of period *t* from the facility's terms (principal, rate, payment frequency, remaining term), plus accrued interest (switch `ead.include-accrued`, on) | prepayment: a constant annual rate per segment (`ead.prepayment-rate`, 0 by default) |
| Bullet | the balance until maturity | |
| Revolving (cards, overdrafts, lines) | `drawn + CCF · (limit − drawn)` | `ead.ccf` per product, scenario-specific if delivered; the behavioural life `ead.revolving-life-months` per product (5.5.20, B5.5.40) |
| Undrawn commitment, financial guarantee | `CCF · undrawn` (the expected drawdown, B5.5.31), or the guaranteed amount | the commitment's ECL is shown as a provision, not deducted from an asset, unless presented together with a drawn balance (IFRS 7.B8E) |
| Bank-delivered schedule | `ead-schedule` per facility (for structured loans) | overrides the derived profile |

The engine derives schedules from terms; it does not store them. The playground lists them period by period.

## 7. Forward-looking scenarios

The bank delivers its scenarios (`macro-scenario`: the scenario names, weights and the macro paths it used, such as unemployment, GDP,
house prices and the policy rate, quarterly, for the forecast years), and its PD, LGD and CCF inputs **per scenario**. Drishti does not
run the macro-to-PD models in this release (decision 4); it shows the macro paths beside the figures they produced and keeps the weights
as data with an approval (they change the provision directly).

Default weights in the demo: base 60 %, upside 15 %, downside 25 % (illustrative; each bank sets its own, often with a severe downside).

**Weight the ECLs, not the inputs.** ECL is non-linear in its inputs (staging, PD × LGD, collateral floors), so the engine computes each
scenario's ECL and weights the results (5.5.17(a) and B5.5.42). The switch `scenario-weighting: inputs` (weight PD, LGD and CCF first, one
ECL) is offered for critique; the playground shows the difference (case 6).

## 8. Management overlays

An overlay (post-model adjustment) is a Drishti-owned record, `ecl-overlay`, entered through a write-back form and **approved with four
eyes** ([WRITE_BACK.md section 10](../guides/WRITE_BACK.md#10-approval); `owned` target, `approvals: two`), never typed into a figure:

| Field | Meaning |
|---|---|
| `OVERLAY_ID`, `SCOPE` | the scope it applies to (a segment, a portfolio, a filter such as `INDUSTRY = 'OIL-GAS'`) |
| `KIND` | `AMOUNT` (a fixed amount, allocated to facilities pro rata to their ECL), `PERCENT` (a share of the modelled ECL), `STAGE_OVERRIDE` (move a filtered set to stage 2 collectively, B5.5.1 to B5.5.6) |
| `VALUE`, `CURRENCY`, `STAGE_EFFECT` | the amount or percent; whether the overlay lands in the facility's stage or a stated stage |
| `REASON`, `EVIDENCE` | the risk the models miss (a new tariff, a flood region), with a link to the evidence |
| `EFFECTIVE_FROM`, `EFFECTIVE_TO`, `REVIEW_BY` | a dated life; an expired overlay stops applying and is listed for review |
| `PROPOSED_BY`, `APPROVED_BY`, `APPROVED_AT` | from the write-back approval |

Overlays appear as their own step in every explanation and their own column in every table; the switch `overlays: off` shows the modelled
figure alone. A collective stage-2 overlay changes the stage of the facilities it names, so it also changes their horizon to lifetime.

## 9. Purchased or originated credit-impaired assets

A POCI facility carries `CREDIT_ADJUSTED_EIR` and `LIFETIME_ECL_AT_RECOGNITION` (the losses already priced into the purchase). Its
allowance is the **cumulative change** in lifetime ECL since initial recognition (5.5.13, 5.5.14):

```
allowance_POCI = lifetime ECL now (at the credit-adjusted EIR) − lifetime ECL at recognition
```

A negative result is an impairment gain (5.5.14); it is reported as a negative allowance on the POCI line, never netted against other
facilities' allowances.

## 10. Scopes and aggregation

**Any scope.** A scope is a filter over facilities (portfolio, segment, product, legal entity, branch, region, stage, rating, industry,
any column of the facility record) or an ad-hoc set of facility ids. Unlike VaR, **ECL is additive**: a scope's ECL is the sum of its
facilities' ECL (each facility staged and computed on its own), so any cut adds up to the total exactly, and contributions need no Euler
step. Collective staging overlays and amount overlays are allocated to facilities before the sum, so they add up too.

**Currencies.** Each facility is computed in its own currency and converted at the business date's closing rate into the reporting
currency of the request (default the legal entity's functional currency), as VaR converts.

**The standard cuts:** by stage (1, 2, 3, POCI), by segment and rating, by days past due bucket, by product, by geography and industry;
the coverage ratio (allowance ÷ gross carrying amount) for each; the stage-2 share; and the IFRS 7.35M table of gross carrying amount by
credit risk grade and stage.

**Cluster.** Facilities are sharded by facility id; each owner computes its shard's facility ECLs and partial sums per requested cut; the
coordinator adds the partials in shard order (the same equality rule as the VaR sums, [CLUSTER_DEPLOYMENT.md section 5](CLUSTER_DEPLOYMENT.md#5-var-sharding-in-depth-built)).

## 11. Why the provision moved

The change of a scope's allowance from date *t−1* to *t* is explained in exact steps, in the manner of the VaR change explained
([VAR_GUIDE.md section 3.11](../guides/VAR_GUIDE.md#311-why-it-moved-the-var-change-explained)): states S0 to S10, each replacing one
group of inputs from *t−1* with its value at *t*, so the steps telescope exactly to `ECL(t) − ECL(t−1)`.

| Step | State after the step | Replaces | Reported as |
|---|---|---|---|
| S0 | the allowance at *t−1* | | opening balance |
| S1 | facilities derecognised (repaid, sold) removed | the facility set (outgoing) | derecognised |
| S2 | write-offs removed | write-offs | written off (IFRS 7.35I(c)) |
| S3 | new facilities added, with *t* inputs | the facility set (incoming) | new originated or purchased |
| S4 | every facility re-staged with *t* staging attributes and *t−1* parameters | stage | transfers 1→2, 2→1, 2→3, 3→2, 1→3 (the horizon change only) |
| S5 | *t* EAD (repayments, drawdowns, amortisation, ageing one period) | EAD and the remaining life | exposure changes |
| S6 | *t* PD curves or matrices and ratings | PD | credit quality changes |
| S7 | *t* LGD and collateral values | LGD | collateral and LGD changes |
| S8 | *t* macro scenarios and weights | scenario paths and weights | forward-looking information |
| S9 | *t* FX rates | FX | FX translation |
| S10 | *t* overlays | overlays | management overlays |

`S10 = ECL(t)` exactly. The order is the setting `drishti.ecl.explain.order` (the order above by default); a step's size depends on
the order when inputs interact (an FX change after a PD change is valued at the new PD), which the screen states, and the playground
shows the alternative order (case 11). Model changes (a new PD model version) are a step of their own when the bank flags them
(`MODEL_VERSION` changed), split out of S6.

Every step is also given **per facility** (the sum of the facility's step values is its change), so a step can be drilled to the
facilities that drive it, and **in sentences**: *"Stage transfers added 19,785.34, all from one facility, L1, moved to stage 2 when its
lifetime PD rose 2.5 times since origination."* The IFRS 7.35H reconciliation (opening, transfers, new, derecognised, write-offs,
remeasurement, FX, closing, by stage) is a regrouping of the same steps.

## 12. Inputs: the data contracts

The contracts follow the [contract rules](../contracts/README.md) (types, dates and as-of, restatements, delivery, rejects, entitlements)
and are written as YAML files of a new pack `credit-risk` when it is built, each with its generated reference page. All data is JSON Lines
(`data/testdata/credit-risk/<kind>/*.jsonl.gz` for the demo, loaded into Delta or Iceberg, read by the lake connector). Keys are
UPPER_SNAKE. A loan book may be a memory-only source ([DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk)) when a bank's contract forbids storing it.

### 12.1 `credit-facility` (one record per facility and business date)

| Field | Type | Req | Meaning | Example |
|---|---|---|---|---|
| `FACILITY_ID` | string | Y | the bank's facility id (key with `BUSINESS_DATE`) | `F-0001234` |
| `BUSINESS_DATE` | date | Y | the as-of date | `2026-10-31` |
| `SOURCE_SYSTEM`, `BATCH_ID` | string | Y | provenance | `LOANIQ`, `B-20261031-01` |
| `CUSTOMER_ID`, `LEGAL_ENTITY`, `BRANCH`, `PORTFOLIO` | string | Y | placement | `C-88812`, `LE-CA`, `BR-0107`, `RETAIL-MTG` |
| `PRODUCT_TYPE` | enum | Y | `MORTGAGE`, `TERM_LOAN`, `CARD`, `OVERDRAFT`, `LINE`, `COMMITMENT`, `GUARANTEE`, `BOND_AC` | `MORTGAGE` |
| `SEGMENT` | string | Y | the PD and LGD model segment | `RETAIL-MORTGAGE-UNINSURED` |
| `CURRENCY` | ISO 4217 | Y | facility currency | `CAD` |
| `ORIGINATION_DATE`, `MATURITY_DATE` | date | Y | life | `2022-03-15`, `2047-03-15` |
| `LIMIT`, `DRAWN`, `ACCRUED_INTEREST` | decimal | Y | amounts in `CURRENCY` | `420000.00`, `388512.40`, `1032.11` |
| `AMORTISATION`, `PAYMENT_FREQUENCY`, `INTEREST_RATE` | enum, enum, decimal | Y for loans | `ANNUITY`, `LINEAR`, `BULLET`, `REVOLVING`; `MONTHLY`…; the contract rate | `ANNUITY`, `MONTHLY`, `0.0489` |
| `EIR` | decimal | Y | effective interest rate, annual | `0.0495` |
| `CCF` | decimal | N | the facility's own CCF, overriding the product's | `0.75` |
| `RATING_ORIGINATION`, `PD_LIFETIME_ORIGINATION` | string, decimal | Y | the grade and the lifetime PD at recognition (staging rule 7) | `R4`, `0.0300` |
| `POCI`, `CREDIT_ADJUSTED_EIR`, `LIFETIME_ECL_AT_RECOGNITION` | bool, decimal, decimal | Y if POCI | section 9 | `false` |
| `INDUSTRY`, `COUNTRY_OF_RISK`, `REGION` | string | N | cuts and overlays | `RESIDENTIAL`, `CA`, `ON` |

```json
{"FACILITY_ID":"F-0001234","BUSINESS_DATE":"2026-10-31","SOURCE_SYSTEM":"LOANIQ","BATCH_ID":"B-20261031-01","CUSTOMER_ID":"C-88812","LEGAL_ENTITY":"LE-CA","BRANCH":"BR-0107","PORTFOLIO":"RETAIL-MTG","PRODUCT_TYPE":"MORTGAGE","SEGMENT":"RETAIL-MORTGAGE-UNINSURED","CURRENCY":"CAD","ORIGINATION_DATE":"2022-03-15","MATURITY_DATE":"2047-03-15","LIMIT":420000.00,"DRAWN":388512.40,"ACCRUED_INTEREST":1032.11,"AMORTISATION":"ANNUITY","PAYMENT_FREQUENCY":"MONTHLY","INTEREST_RATE":0.0489,"EIR":0.0495,"RATING_ORIGINATION":"R4","PD_LIFETIME_ORIGINATION":0.0300,"POCI":false,"INDUSTRY":"RESIDENTIAL","COUNTRY_OF_RISK":"CA","REGION":"ON"}
```

### 12.2 `staging-attribute` (per facility and business date; may be a part of the facility record)

| Field | Type | Req | Meaning | Example |
|---|---|---|---|---|
| `FACILITY_ID`, `BUSINESS_DATE` | | Y | key | |
| `RATING_CURRENT` | string | Y | current grade | `R6` |
| `DAYS_PAST_DUE` | int | Y | | `31` |
| `WATCHLIST`, `FORBORNE`, `DEFAULT_FLAG`, `UNLIKELY_TO_PAY` | bool | Y | the qualitative triggers | `false`, `false`, `false`, `false` |
| `DPD_REBUTTAL`, `DPD_REBUTTAL_REASON` | bool, string | N | a rebutted 30- or 90-day presumption, with its reason | `false` |
| `WRITE_OFF_AMOUNT` | decimal | N | written off on this date | `0` |

```json
{"FACILITY_ID":"F-0001234","BUSINESS_DATE":"2026-10-31","RATING_CURRENT":"R6","DAYS_PAST_DUE":31,"WATCHLIST":false,"FORBORNE":false,"DEFAULT_FLAG":false,"UNLIKELY_TO_PAY":false,"DPD_REBUTTAL":false,"WRITE_OFF_AMOUNT":0}
```

### 12.3 `pd-curve` and `rating-migration-matrix`

| Field (`pd-curve`) | Type | Meaning | Example |
|---|---|---|---|
| `CURVE_ID`, `BUSINESS_DATE`, `MODEL_VERSION` | | key and the model version (a change is its own explain step) | `PD-RMU-R6-DOWN`, `2026-10-31`, `PD-RM-3.2` |
| `SEGMENT`, `RATING`, `SCENARIO` | string | what the curve is for | `RETAIL-MORTGAGE-UNINSURED`, `R6`, `DOWNSIDE` |
| `POINTS[]` | array of `{TENOR_MONTHS, CPD}` | cumulative PD at each tenor, non-decreasing, < 1 | `[{"TENOR_MONTHS":12,"CPD":0.041}, …]` |

```json
{"CURVE_ID":"PD-RMU-R6-DOWN","BUSINESS_DATE":"2026-10-31","MODEL_VERSION":"PD-RM-3.2","SEGMENT":"RETAIL-MORTGAGE-UNINSURED","RATING":"R6","SCENARIO":"DOWNSIDE","POINTS":[{"TENOR_MONTHS":12,"CPD":0.041},{"TENOR_MONTHS":24,"CPD":0.079},{"TENOR_MONTHS":36,"CPD":0.112},{"TENOR_MONTHS":60,"CPD":0.163}]}
```

`rating-migration-matrix`: `MATRIX_ID`, `BUSINESS_DATE`, `MODEL_VERSION`, `SEGMENT`, `SCENARIO`, `PERIOD_INDEX` (1, 2, 3 for the
forecast years, 0 for the long run), `PERIOD_MONTHS`, `STATES[]` (the grades in order, the default state last), `ROWS[][]` (the
probabilities).

```json
{"MATRIX_ID":"MM-CORP-BASE-1","BUSINESS_DATE":"2026-10-31","MODEL_VERSION":"MM-C-1.4","SEGMENT":"CORPORATE","SCENARIO":"BASE","PERIOD_INDEX":1,"PERIOD_MONTHS":12,"STATES":["A","B","D"],"ROWS":[[0.90,0.08,0.02],[0.10,0.80,0.10],[0.0,0.0,1.0]]}
```

### 12.4 `lgd-parameter`, `loan-collateral`, `ead-schedule`

| Kind | Fields | Example |
|---|---|---|
| `lgd-parameter` | `SEGMENT`, `SENIORITY`, `SCENARIO`, `BUSINESS_DATE`, `MODEL_VERSION`, `LGD_UNSECURED`, `LGD_SECURED_FLOOR`, `LGD_DEFAULTED`, `HAIRCUTS{COLLATERAL_TYPE: h}`, `CCF{PRODUCT_TYPE: ccf}` | `{"SEGMENT":"RETAIL-MORTGAGE-UNINSURED","SENIORITY":"SENIOR_SECURED","SCENARIO":"DOWNSIDE","BUSINESS_DATE":"2026-10-31","MODEL_VERSION":"LGD-RM-2.0","LGD_UNSECURED":0.45,"LGD_SECURED_FLOOR":0.10,"LGD_DEFAULTED":0.22,"HAIRCUTS":{"RESIDENTIAL_PROPERTY":0.30},"CCF":{"LINE":0.50}}` |
| `loan-collateral` | `COLLATERAL_ID`, `BUSINESS_DATE`, `TYPE`, `VALUE`, `CURRENCY`, `VALUATION_DATE`, `FACILITY_ALLOCATIONS[{FACILITY_ID, AMOUNT, RANK}]` | `{"COLLATERAL_ID":"COL-55120","BUSINESS_DATE":"2026-10-31","TYPE":"RESIDENTIAL_PROPERTY","VALUE":800000.00,"CURRENCY":"CAD","VALUATION_DATE":"2026-06-30","FACILITY_ALLOCATIONS":[{"FACILITY_ID":"F-0001234","AMOUNT":800000.00,"RANK":1}]}` |
| `ead-schedule` | `FACILITY_ID`, `BUSINESS_DATE`, `POINTS[{PERIOD_END, EAD}]` | for structured facilities only |

### 12.5 `macro-scenario`

| Field | Meaning | Example |
|---|---|---|
| `SCENARIO`, `BUSINESS_DATE`, `SCENARIO_SET_VERSION` | key; the set the PD and LGD inputs were produced with | `DOWNSIDE`, `2026-10-31`, `MS-2026Q4` |
| `WEIGHT` | probability weight; weights of a set sum to 1 | `0.25` |
| `NARRATIVE` | the story, for the screen | `Recession from 2027Q1, unemployment peaks at 9.1 %` |
| `PATHS[{VARIABLE, REGION, POINTS[{PERIOD, VALUE}]}]` | the macro paths, quarterly | `{"VARIABLE":"UNEMPLOYMENT_RATE","REGION":"CA","POINTS":[{"PERIOD":"2027Q1","VALUE":0.072}]}` |
| `APPROVED_BY`, `APPROVED_AT` | the bank's scenario governance | `CRO-COMMITTEE`, `2026-10-28` |

```json
{"SCENARIO":"DOWNSIDE","BUSINESS_DATE":"2026-10-31","SCENARIO_SET_VERSION":"MS-2026Q4","WEIGHT":0.25,"NARRATIVE":"Recession from 2027Q1, unemployment peaks at 9.1 %","PATHS":[{"VARIABLE":"UNEMPLOYMENT_RATE","REGION":"CA","POINTS":[{"PERIOD":"2027Q1","VALUE":0.072},{"PERIOD":"2027Q2","VALUE":0.083}]}],"APPROVED_BY":"CRO-COMMITTEE","APPROVED_AT":"2026-10-28"}
```

**Load checks** (each refusal counted with its code on the load's quality screen): a facility without a PD curve or matrix for its
segment, rating and every scenario; a curve not monotone; a matrix row not summing to 1; weights not summing to 1; an EIR missing or
negative; a collateral allocation over the collateral's value; a stage-3 facility without `LGD_DEFAULTED`.

## 13. Outputs

| Computed kind | Grain | Fields |
|---|---|---|
| `ecl-result` | facility × business date (computed when asked; captured at period end) | stage, stage rule and measured values, horizon, ECL per scenario, weighted ECL, overlay, final ECL, gross carrying amount, coverage, EAD at t=1, 12-month and lifetime PD (weighted), LGD (weighted), currency and the reporting-currency amounts, the input versions |
| `ecl-summary` | scope × cut × business date | gross carrying amount, ECL by stage, coverage, counts, the stage-2 share |
| `ecl-movement` | scope × two dates | the steps of section 11 per stage, per facility on drill |
| `ecl-stage-history` | facility × business date | the stage and the rule (feeds the cure probation and the transfer tables) |
| `ecl-overlay` | Drishti-owned | section 8 |

Every answer carries a provenance record (the input tables' versions and batches, the parameter files and their checksum, the switches,
refusals counted), as the VaR answers do. Official captures (`drishti.py ecl capture`) freeze the month-end figure with its recipe, for the
ledger posting and the IFRS 7 notes; the ledger posting itself stays with the bank's general ledger.

## 14. API, command line and screens

| What | API | Command (terminal) | CLI |
|---|---|---|---|
| The allowance of a scope, by any cut | `GET /api/v1/ecl?scope=…&date=…&by=STAGE,SEGMENT` | `ECL <scope>` | `drishti.py ecl show` |
| One facility, period by period | `GET /api/v1/ecl/facility/{id}?date=…` | `ECL F-0001234` | `drishti.py ecl facility` |
| Staging: transfers and reasons | `GET /api/v1/ecl/staging?scope=…&from=…&to=…` | `ECL STAGE <scope>` | `drishti.py ecl staging` |
| Why it moved | `GET /api/v1/ecl/explain?scope=…&from=…&to=…` | `ECL EXPLAIN <scope>` | `drishti.py ecl explain` |
| What-if (a switch, a weight, a threshold) | `POST /api/v1/ecl/what-if` | in the playground | `drishti.py ecl what-if` |
| The playground and its workbook | `/api/v1/ecl/playground` (and `.xlsx`, `/template`) | `PLAY ECL <scope>`, `PLAY ECL RUN <id>` | `drishti.py play ecl` |
| Official capture | `POST /api/v1/ecl/capture` | | `drishti.py ecl capture` |
| Overlays (forms, approvals) | write-back | `ECL OVERLAYS` | `drishti.py ecl overlays` |

**Screens (mock-ups first, three options each, before the build; decision 7):**

| Screen | Option A | Option B | Option C |
|---|---|---|---|
| The provision | the stage stack: gross carrying amount and ECL by stage with coverage, the cuts below | the segment heat map: segment × stage, coloured by coverage, the drill on the right | the trend: month-end ECL by stage over 13 months with the latest movement |
| Why it moved | the waterfall S0 to S10 with the per-facility drill | the IFRS 7.35H matrix: steps × stages | sentences, largest first |
| Staging monitor | the transfer matrix (from stage × to stage) with the rules that caused each transfer | facilities near a trigger (ratio within 10 % of the threshold, 15 to 29 DPD) | cure probations under way, with their end dates |
| One facility | the period table (mPD, LGD, EAD, DF, term) per scenario | the staging timeline with each rule fired | the collateral and LGD build-up |

## 15. The playground and its workbook

Every calculation ships with its playground and an Excel workbook of live formulas equal to the engine to the cent
([VAR_GUIDE.md section 18.16](../guides/VAR_GUIDE.md#1816-the-var-playgrounds-workbook-formula-by-formula)). `PLAY ECL` opens the VaR Playground's frame
(the grid, the tall explanation panel, the formula bar, the export) on up to a configurable number of facilities (default 50) or one
segment summarised.

### 15.1 The tabs

| Tab | What it shows | Typed or formula |
|---|---|---|
| Main | the scope, the date, ECL by stage and scenario, the weighted ECL, overlays and the total, each beside the engine's figure with a check cell | formulas; the engine's figures typed |
| Config | every switch (a drop-down cell) and parameter: SICR thresholds, backstop, cure months, weights, discount convention, CCFs, haircuts | typed |
| Data | the facilities, staging attributes, PD points or matrices, LGDs, collateral, FX, as delivered | values only |
| Staging | per facility: each rule's test (`IF` on Config), the first that fires, the stage | formulas only |
| PD | per facility and scenario: survival by period from the curve (interpolation formulas) or the matrix product (`MMULT` per period), marginal PD | formulas only |
| EAD | the schedule per period (`PMT`-based annuity, CCF on the undrawn) | formulas only |
| LGD | collateral after haircut, covered and uncovered, the blended LGD per period | formulas only |
| ECL | per facility, scenario and period: `mPD · LGD · EAD · DF`; the horizon cut (`IF(period ≤ horizon, …, 0)`); the sum; the weighting | formulas only |
| Explain | the method in plain words with the cells of each step | text with references |
| Movement | (when two dates are exported) S0 to S10 as formulas over the two dates' Data | formulas |
| Provenance | the recipe: input versions, parameter checksum, switches, engine version, who exported it | values |

### 15.2 Critique and reproduce: the method choices as switches

As [FRTB_SA_GUIDE.md section 8.1](../guides/FRTB_SA_GUIDE.md#81-critique-the-choices-panel):
every choice is a named switch on the *Choices* panel and on the Config tab, the alternative computed side by side with the difference
explained per facility and step.

| Switch | Default | Alternative(s) |
|---|---|---|
| `sicr-relative-threshold` | 2.0× | any value (1.5×, 3.0×), shown with the facilities that change stage |
| `sicr-absolute-threshold` | 0.5 % | any value; `off` (relative only) |
| `sicr-pd-measure` | lifetime over the remaining life | twelve-month |
| `dpd-backstop` | 30 days | rebuttals honoured or ignored |
| `low-credit-risk-exemption` | off | on, with the grade |
| `staging-basis` | weighted PD | per scenario |
| `scenario-weights` | the delivered set | any weights summing to 1; one scenario at 100 % |
| `scenario-weighting` | weight the ECLs | weight the inputs |
| `discount-point` | end of period | mid-period |
| `pd-interpolation` | log-linear in survival | linear in CPD |
| `overlays` | on | off |
| `cure-months` | 3 (stage 2→1) | 0, 6, 12 |
| `explain-order` | section 11's order | any permutation of S4 to S10 |

Overrides of a parameter are drawn in a different colour and every figure they touch carries *not the booked figure*. A run is a
**recipe** (the scope, both dates, the input versions, the parameter file hash, the switches, the engine version) with an id;
`PLAY ECL RUN <id>` recomputes it to the cent months later. Tests prove: the default workbook equals the engine; each flipped switch equals
the engine run with it; a replayed recipe gives byte-equal figures; an override never reaches a capture.

## 16. Hand-built cases

Annual periods, default at the end of the period, EIR 5 %, so `DF(t) = 1.05^−t`. Figures rounded to the cent for display; the engine and
the workbook carry full precision, and the check cells compare unrounded values (a sum of rounded terms may differ by a cent).

**Case 1: stage 1, 12-month ECL.** Bullet, EAD 1,000,000, PD₁₂ 2 %, LGD 40 %:
`ECL = 0.02 · 0.40 · 1,000,000 / 1.05 = 7,619.05`.

**Case 2: the PD term structure from a migration matrix.** Three states A, B, D (default), one-year matrix
`A: [0.90, 0.08, 0.02]`, `B: [0.10, 0.80, 0.10]`, `D: [0, 0, 1]`. From A: `M²[A] = [0.818, 0.136, 0.046]`
(`0.90·0.02 + 0.08·0.10 + 0.02·1 = 0.046`), `M³[A] = [0.7498, 0.17424, 0.07596]`. Cumulative PD 0.02, 0.046, 0.07596; marginal PD
0.02, 0.026, 0.02996.

**Case 2a: the same loan in stage 2, lifetime over three years.** Terms `400,000 · mPD(t) / 1.05^t`: 7,619.05, 9,433.11, 10,352.23;
**lifetime ECL 27,404.38**. Moving to stage 2 raises the allowance by 19,785.34.

**Case 3: amortising.** 900,000 repaid 300,000 a year: EAD 900,000, 600,000, 300,000; the same marginal PDs and LGD: 6,857.14, 5,659.86,
3,105.67; lifetime 15,622.68; 12-month 6,857.14.

**Case 4: undrawn and CCF.** Limit 500,000, drawn 200,000, CCF 50 %: `EAD = 200,000 + 0.5 · 300,000 = 350,000`; PD₁₂ 1 %, LGD 60 %:
`350,000 · 0.006 / 1.05 = 2,000.00`.

**Case 5: secured LGD and stage 3.** EAD 700,000; property 800,000 with a 30 % haircut: `C_eff = 560,000`; covered 560,000 at 10 %,
uncovered 140,000 at 45 %: `LGD = (56,000 + 63,000) / 700,000 = 17.0 %`. In stage 3 (PD 1): `ECL = 700,000 · 0.17 = 119,000.00`.

**Case 6: scenarios, and why the ECLs are weighted.** EAD 1,000,000, one year. Base (60 %) PD 2 %, LGD 40 %; upside (15 %) 1.5 %, 35 %;
downside (25 %) 4 %, 50 %. Scenario ECLs 7,619.05, 5,000.00, 19,047.62; **weighted 10,083.33**. Weighting the inputs first (PD 2.425 %,
LGD 41.75 %) gives 9,642.26: 441.07 less, the non-linearity the default avoids.

**Case 7: SICR on the threshold.** Lifetime PD at origination for the remaining life 3.0 %, now 7.5 %: ratio 2.5 ≥ 2.0 and increase
4.5 % ≥ 0.5 %: **stage 2** (case 2a's 27,404.38). With `sicr-relative-threshold` 3.0 the facility stays in **stage 1** (case 1's
7,619.05); the playground shows both and the 19,785.34 between them.

**Case 8: the backstop, the exemption and the cure.** DPD 31 with ratio 1.2 → stage 2 by rule 3, the reason "DPD 31 ≥ 30". The same
facility rated investment grade with the exemption on → still stage 2 (the backstop is checked first). DPD back to 0 on 31 July:
stage 2 until the probation ends on 31 October (three month ends meeting stage-1 conditions), stage 1 from then.

**Case 9: POCI.** Bought with lifetime ECL at recognition 25,000; lifetime ECL now 20,000 at the credit-adjusted EIR: allowance
`20,000 − 25,000 = −5,000`, an impairment gain reported on the POCI line.

**Case 10: overlay.** A `PERCENT` overlay of 10 % on segment `CORPORATE-OIL-GAS` whose modelled ECL is 1,250,000: +125,000, allocated pro
rata to the facilities' ECL; `overlays: off` shows 1,250,000.

**Case 11: why the provision moved.** Three facilities at *t−1*, CAD reporting, EIR 5 %, one-year horizon unless stated:

| Facility | *t−1* | *t* |
|---|---|---|
| L1 (CAD) | stage 1, case 1: 7,619.05 | stage 2 (SICR), case 2a: 27,404.38 |
| L2 (CAD) | stage 1, EAD 500,000, PD 1 %, LGD 60 %: 2,857.14 | repaid |
| L3 (CAD) | — | new, EAD 300,000, PD 1.5 %, LGD 45 %: 1,928.57 |
| L4 (USD) | EAD 400,000, PD 3 %, LGD 50 %: USD 5,714.29 × 1.35 = 7,714.29 | PD 3.5 %: USD 6,666.67 × 1.40 = 9,333.33 |
| Overlay | — | +2,000.00 |

| Step | Value | Running total |
|---|---|---|
| S0 opening | | 18,190.48 |
| S1 derecognised (L2) | −2,857.14 | 15,333.33 |
| S3 new (L3) | +1,928.57 | 17,261.90 |
| S4 transfer 1→2 (L1) | +19,785.34 | 37,047.24 |
| S6 PD (L4, at the old FX 1.35) | +1,285.71 | 38,332.96 |
| S9 FX (L4, at the new PD) | +333.33 | 38,666.29 |
| S10 overlay | +2,000.00 | **40,666.29** = ECL(*t*) |

Steps S2, S5, S7 and S8 are zero here (no write-off; for readability the case assumes no repayment and no ageing between the dates, and no LGD or scenario change) and are shown as zero on the screen.

With FX before PD the two steps are 285.71 and 1,333.33: the same total, a different split; the screen states the order in force.

**Case 12: the regulator's and the auditors' examples.** Where the IFRS Foundation's illustrative examples (IFRS 9 IE29 to IE39 on
staging, IE40 to IE52 on measurement) give numbers, each is added as a case with its source.

## 17. Parameters as configuration

`config/engines/ifrs9.yaml` (the engine's own file, as every engine has; planned):

```yaml
# config/engines/ifrs9.yaml (planned)
enabled: false                    # off as shipped; the demo turns it on
period: month                     # month | year
default:
  dpd-presumption: 90
stage3:
  dpd: 90
sicr:
  pd-measure: lifetime            # lifetime | twelve-month
  relative-threshold: 2.0
  absolute-threshold: 0.005
  dpd-backstop: 30
  low-credit-risk: { enabled: false, max-rating: R3 }
cure: { stage2-to-1: 3, stage3-to-2: 12, stage3-to-2-not-restructured: 3 }
staging-basis: weighted           # weighted | per-scenario
scenarios: { forecast-years: 3, reversion-years: 2, weighting: ecl }   # ecl | inputs
discount-point: end               # end | mid
pd-interpolation: log-survival    # log-survival | linear
ead: { include-accrued: true, prepayment-rate: {}, revolving-life-months: { CARD: 36, OVERDRAFT: 24, LINE: 36 } }
overlays: { enabled: true, approvals: two }
explain: { order: [derecognised, write-off, new, stage, ead, pd, lgd, scenario, fx, overlay] }
limits: { playground-facilities: 50, what-if-facilities: 100000 }
```

The segment-level parameters that are the bank's (CCFs, haircuts, LGD floors) come as data (`lgd-parameter`), so a change is a new data
version with its approval, not a configuration edit. A changed engine file is a new version; every capture names the version and checksum
it used. Settings follow the `drishti.ecl.*` names in CONFIGURATION.md when built.

## 18. Sizing

| Book | Facilities | Periods (lifetime) | Scenarios | Terms per run | Memory (facility columns) | Estimated time, one warm server, 16 cores |
|---|---|---|---|---|---|---|
| Demo | 10,000 | up to 360 months | 3 | ~2 million | ~4 MB | well under a second |
| Mid-size bank | 2 million | average 60 months (stage 2 and 3 share small) | 3 | ~0.4 billion (most facilities stage 1: 12 terms) | ~0.5 GB | a few seconds |
| Large bank retail | 15 million | average 60 | 3 | ~3 billion | ~3.5 GB | tens of seconds; a cluster shards it |

Estimates, to be measured in the benchmark (BENCHMARK.md) once built. The cost drivers: survival curves are computed once per segment ×
rating × scenario (thousands, not millions) and shared; schedules are derived per facility on the fly in primitive arrays (no objects per
period); staging is one pass. The movement explain recomputes only the facilities whose input group changed at each step (a typical month:
under 10 % of facilities for PD, all for ageing), so it costs about two to four full runs, not eleven.

## 19. Regulatory interaction: OSFI and Basel

- **IFRS 9 is the accounting standard**; Canadian banks apply it under OSFI's guideline *IFRS 9 Financial Instruments and Disclosures*,
  which sets OSFI's expectations for impairment (the Basel Committee's ECL guidance applied, a limited use of the 30-days-past-due
  rebuttal and of the low-credit-risk exemption, forward-looking information incorporated, sound credit risk practices and governance).
  The engine's defaults follow it: the exemption off, rebuttals only with a reason, scenarios required.
- **The Basel Committee's** *Guidance on credit risk and accounting for expected credit losses* (December 2015) sets the supervisory
  principles for ECL governance, validation and the use of experienced credit judgement; overlays with approvals and evidence (section 8)
  and the recipe (section 15.2) are how Drishti supports them.
- **Capital interaction.** Under the standardised approach, stage 1 and 2 allowances are general allowances eligible for Tier 2 capital up
  to 1.25 % of credit risk-weighted assets, and stage 3 allowances (with partial write-offs) are specific allowances (Basel CAP10.18; OSFI
  CAR chapters 2 and 4). Under the IRB approach the total eligible allowances are compared with the regulatory expected loss: a shortfall
  is deducted from CET1, an excess added to Tier 2 up to 0.6 % of credit RWA (Basel CRE35). Regulatory EL uses through-the-cycle one-year
  PD and downturn LGD, not IFRS 9's point-in-time, forward-looking lifetime figures, so **the engine keeps the two apart**: the IFRS 9
  allowance by stage is an output that a capital engine may read, never a substitute for IRB parameters.
- US banks follow CECL (ASC 326: lifetime losses for every asset, no staging); the same engine runs CECL with `staging: off, horizon:
  lifetime` as a later option (decision 6).

## 20. Decisions

**All decided by the product owner on 2026-11-02, as recommended.**

| # | Question | Decided (as recommended) |
|---|---|---|
| 1 | Release placement | 2.9.20 as proposed, after the executive release; nothing earlier moves |
| 2 | Period granularity | **monthly** by default (banks' retail books amortise monthly), annual as a setting; the hand-built cases use annual for readability |
| 3 | Staging basis | **one stage per facility on the weighted lifetime PD**; per-scenario staging as a switch |
| 4 | Models | **consume the bank's PD, LGD, CCF and scenario outputs**; no macro-to-PD model fitting in Drishti in this release (a later candidate with its own playground) |
| 5 | SICR defaults shipped | relative 2.0× **and** absolute 0.5 %, the 30 DPD backstop, the exemption off, cure 3 and 12 months; every value labelled a default for the bank to replace |
| 6 | CECL | later, as a mode of the same engine (no staging, lifetime for all), after a US bank asks |
| 7 | Screens | mock-ups first: three options each for the provision, why it moved, the staging monitor and one facility; recommended defaults A, A, A, A with B and C as views on the same screen |
| 8 | Demo data | a synthetic loan book of 10,000 facilities (mortgages, cards, term loans, lines; three scenarios; planted transfers, a POCI, overlays), clearly labelled synthetic; the example pack's stored `retail-portfolio` ECL becomes a computed figure |
| 9 | Ledger | Drishti captures and explains; **the posting to the general ledger stays with the bank** (an export file of the capture by account) |
| 10 | Overlays | Drishti-owned records with **four-eyes approval** and an expiry date; never an edit of a figure |

## 21. Build checklist

| # | Item | Size |
|---|---|---|
| E1 | The `credit-risk` pack: the contracts of section 12 as YAML with their generated pages; the seeded generator of the demo loan book (JSON Lines → Delta), the README | M |
| E2 | The engine core in Java: periods, survival from curves and matrices, EAD schedules, LGD with collateral, discounting, scenarios, weighting, POCI; `config/engines/ifrs9.yaml` with its checks | L |
| E3 | Staging with reasons, the staging history and the cure probation | M |
| E4 | Overlays: the Drishti-owned kind, the write-back form with four-eyes approval, allocation | S |
| E5 | Scopes, cuts, FX and the cluster partial sums; the computed kinds; the API and the CLI; captures | M |
| E6 | Why it moved: S0 to S10 per facility and scope, the order setting, sentences, the IFRS 7.35H table | L |
| E7 | The playground and its workbook (formulas, switches, recipes), with the check-cell test under LibreOffice | M |
| E8 | The screens after the mock-ups are chosen (provision, why it moved, staging monitor, facility) | M |
| E9 | The hand-built cases as unit tests, playground tests and drill rows; the guide and the tutorial (two docs and two help entries) | S |

Two large items (E2, E6), as the release rules allow.

## 22. Sources

- IFRS Foundation, IFRS 9 *Financial Instruments*, section 5.5 and Appendix B5.5; IFRS 7 *Financial Instruments: Disclosures*, 35A to 35N:
  [ifrs.org](https://www.ifrs.org/issued-standards/list-of-standards/ifrs-9-financial-instruments/)
- OSFI, *IFRS 9 Financial Instruments and Disclosures* (guideline):
  [osfi-bsif.gc.ca](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library/ifrs-9-financial-instruments-disclosures)
- OSFI, *Capital Adequacy Requirements*, chapter 4 (credit risk, standardised approach, the treatment of allowances):
  [osfi-bsif.gc.ca](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library)
- Basel Committee, *Guidance on credit risk and accounting for expected credit losses* (December 2015):
  [bis.org](https://www.bis.org/bcbs/publ/d350.htm)
- Basel Committee, *Regulatory treatment of accounting provisions: interim approach and transitional arrangements* (March 2017):
  [bis.org](https://www.bis.org/bcbs/publ/d401.htm)
- Basel Framework CAP10 (definition of capital, general provisions in Tier 2) and CRE35 (IRB treatment of expected losses and provisions):
  [bis.org CAP10](https://www.bis.org/basel_framework/chapter/CAP/10.htm), [bis.org CRE35](https://www.bis.org/basel_framework/chapter/CRE/35.htm)
