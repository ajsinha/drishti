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
# FVA guide: funding cost and benefit per netting set

Drishti computes FVA, the funding valuation adjustment, the way it computes VaR and exposure: **from trade-grain inputs, when asked, for any
scope, never stored as a figure.** FVA reads the expected exposure (EE) and expected negative exposure (ENE) profile that the exposure
engine already computes for each netting set from the bank's simulated paths, after the CSA, and prices it with the bank's funding spread
and the discount curve. There is one calculation, in Java on the server, and one answer that every screen, the command line, the
playground and its workbook read. The tutorial is [tutorial 34](../tutorials/34-fva-funding-cost-and-benefit.md); the exposure engine is
[EXPOSURE_GUIDE.md](EXPOSURE_GUIDE.md); the design and its decisions are
[COUNTERPARTY_ENGINES.md section 5](../architecture/COUNTERPARTY_ENGINES.md#5-fva-funding-cost-and-benefit) and
[section 14](../architecture/COUNTERPARTY_ENGINES.md#14-decisions); the formulas beside CVA are in [CVA.md section 6.3](../contracts/CVA.md#63-cva-dva-fva).

## Contents

- [1. What FVA answers](#1-what-fva-answers)
- [2. The formulas, with worked numbers](#2-the-formulas-with-worked-numbers)
- [3. The inputs](#3-the-inputs)
- [4. The choices](#4-the-choices)
- [5. The screens](#5-the-screens)
- [6. The API](#6-the-api)
- [7. The command line](#7-the-command-line)
- [8. The FVA Playground (PLAY FVA)](#8-the-fva-playground-play-fva)
- [9. Settings and the engine switch](#9-settings-and-the-engine-switch)
- [10. Flags, refusals and error codes](#10-flags-refusals-and-error-codes)
- [11. The tests that prove it](#11-the-tests-that-prove-it)
- [12. What is not built yet](#12-what-is-not-built-yet)

## 1. What FVA answers

*What does it cost the bank to fund this netting set's trades over their life, net of the funding they provide?* When a trade is in the
bank's favour and the counterparty posts no collateral against it, the bank has in effect lent the counterparty that value and borrows the
money in the market at its own funding spread over the risk-free (OIS) rate. When the bank owes the counterparty, it holds that money and
saves the spread. FVA adds both up over the life of the trades: the **funding cost (FCA)** on the expected positive exposure and the
**funding benefit (FBA)** on the expected negative exposure, period by period, discounted to today.

FVA is part of the price and of the profit and loss (fair value, IFRS 13; Gregory, *The xVA Challenge*), not a capital figure. A trader
quoting a new uncollateralised swap needs its funding cost; a desk or the xVA team needs to know which agreements are expensive to fund and
which a CSA would fix. A set with a CSA has little FVA: collateral received can be reused to fund (decision 13).

## 2. The formulas, with worked numbers

Per primary date t_k of the exposure grid (k = 1 … K, t_0 today, Δt_k = t_k − t_(k−1)):

```
EE*(t_k)  = EE(t_k) · DF(t_k)                 ENE*(t_k) = ENE(t_k) · DF(t_k)
FCA       = − Σ_k EE*(t_k)    · s_B(t_k) · Δt_k   [· S_C(t_k) · S_B(t_k) with survival weighting]
FBA       = + Σ_k |ENE*(t_k)| · s_L(t_k) · Δt_k   [· S_C(t_k) · S_B(t_k)]
FVA       = FCA + FBA
```

EE and ENE are the exposure engine's, after collateral (`lagged` collateral by default), at every primary date of the run: the whole life,
not the regulatory first year. s_B is the borrowing (funding) spread and s_L the lending spread; with symmetric funding (the default)
s_L = s_B. Signs are the bank's side: FCA ≤ 0 (a cost), FBA ≥ 0 (a benefit). Units: USD.

**Case F1** (the Exposure Playground's case X1 profile: EE 0.8, 1.6, 1.1, 1.3 and ENE 0, 0, −0.4, −0.3 USD millions at 0.25, 0.5, 0.75 and
1 year; the period's end):

| Case | Spread | Discounting | FCA | FBA | FVA |
|---|---|---|---|---|---|
| (a) | flat 50 bp | none (DF = 1) | −0.005 × 4.8m × 0.25 = **−6,000.00** | +0.005 × 0.7m × 0.25 = **+875.00** | **−5,125.00** |
| (b) | flat 50 bp | DF = e^(−0.04 t) | terms −990.05, −1,960.40, −1,334.36, −1,561.28: **−5,846.09** | 485.22 + 360.30 = **845.52** | **−5,000.57** |
| (c) | 40 bp to 6 months, 60 bp from 9 months | DF = e^(−0.04 t) | **−5,835.13** | **1,014.62** | **−4,820.51** |
| (d) | flat 50 bp, survival on (hazards 2 % and 1 %) | DF = e^(−0.04 t) | **−5,733.68** | **824.07** | **−4,909.60** |

**The EPE reading.** With a flat spread and no discounting the funding cost is the spread times the life times the average exposure over
the life: FCA = −s_F · T_life · EPE_life, with EPE_life = Σ EE(t_k) Δt_k / T_life. In case F1 (a): Σ EE · Δt = 1.2m, T_life = 1, and
0.005 × 1.2m = 6,000. Every answer carries this reading beside FCA when the borrowing spread is flat; it explains FVA in one line and is
not a second result.

**Additivity.** Every figure is linear in EE and ENE, and expected exposures add across netting sets, so FVA adds over netting sets and
counterparties: a counterparty's FVA is the sum of its sets', the bank's the sum of all. A scope that cuts a netting set (a desk's share)
uses the exposure engine's partial profile, flagged `partial`.

**CVA.** CVA joins this engine later, on the same EE profile; until then every screen shows **CVA (planned)** beside FVA. The stored `cva`
kind's FVA field is not read.

## 3. The inputs

| Input | Where it comes from | Rule |
|---|---|---|
| EE and ENE per netting set and primary date | the exposure engine's answer for the scope, as the caller (EXPOSURE_GUIDE.md section 18): each set's matrices already held, averaged over paths | after the CSA (decision 13); never a second exposure computation |
| CE and its negative (t_0) | the exposure engine's current exposure and `min(MTM − C_today, 0)` | used only with the `mid` point |
| Times t_k | the run's grid (`GRID_DATES`, `GRID_ROLE` PRIMARY), years from the business date | Δt from today |
| Discount factor DF(t) | the `ir-curve` document `CRV-USD-OIS` of the date (`POINTS[].TENOR`, `DF`) | log-linear in DF between (0, 1) and the points, the last segment's rate beyond; DF = 1 when the run's `VALUE_BASIS` is `DISCOUNTED`; no curve: the flat `discount-rate`, flagged `FLAT_DISCOUNT` |
| Borrowing spread s_B(t) | `funding-spread`: a curve id read from the `ir-curve` kind (points `TENOR`, `SPREAD` in decimals), else a curve of the same id in `drishti.ccr.fva.curves`, else a flat number | linear in t between points, flat beyond; no curve anywhere: `fallback-spread`, flagged `FUNDING_FALLBACK` |
| Lending spread s_L(t) | `lending-spread`, as the borrowing one; empty: the same (symmetric) | as s_B |
| Survival S_C(t) · S_B(t) | the flat hazards `counterparty-hazard` and `own-hazard` | e^(−(h_C + h_B) t); only with `funding-survival` |
| The run's calibration | the exposure run's `CALIBRATION` | not `RISK_NEUTRAL`: refused, or computed and flagged (section 10) |

**The demo's funding curve.** The demo names `CRV-USD-FUNDING`, a synthetic, labelled curve of the bank's unsecured spread over OIS
(40 bp at 6 months, 45 bp at 1 year, 55 bp at 2, 70 bp at 5, 85 bp at 10), in `config/engines/fva.yaml` (decision 12). A bank names its own
curve id: a document of that id in its `ir-curve` feed (or any kind named by `curve-kind`) wins over the configured one, and every answer
says which source it used.

## 4. The choices

| Choice | Values | Default | Rule |
|---|---|---|---|
| `ee-point` (`eePoint`) | `end`, `mid` | `end` | `end`: EE*(t_k) stands for the period (t_(k−1), t_k]; `mid`: the average of both ends, EE*(t_0) = CE |
| `fba` | on, off | on | off: FCA only (asymmetric funding), FBA = 0 |
| `funding-survival` (`survival`) | on, off | off | on: each term × S_C(t_k) · S_B(t_k): funding stops at either default (decision 11) |
| `funding-spread` (`spread`) | a curve id or a fraction | `CRV-USD-FUNDING` | `0.006` is a flat 60 bp: what-if on the spread |

The defaults are settings (section 9); a request may change any of them (`?eePoint=mid&fba=off&survival=on&spread=0.006`), and the
playground's Choices and the workbook's Config cells do the same.

## 5. The screens

One FVA screen over one answer, drawn three ways (the decided mock-ups of [section 12](../architecture/COUNTERPARTY_ENGINES.md#12-mock-ups-the-screens-to-build),
[A](../lenses/mockups/fva/A.html), [B](../lenses/mockups/fva/B.html), [C](../lenses/mockups/fva/C.html)); a tab switches between them on the
same answer, and the browser computes nothing.

| View | Opens on | Shows |
|---|---|---|
| **A, the netting set** (the default) | `FVA NSET:NS-ALDERSHOT-FRA <GO>` | the FVA, FCA, FBA, CVA (planned) and EPE-over-the-life tiles; the term bars per future date (benefit above the line, cost below); the formula with the curves used; how it was computed; links to the playground and the exposure |
| **B, the netting sets by FVA** | `FVA BANK <GO>`, `FVA GROUP:…`, `FVA LE:…`, or `FVA` alone | every netting set of the scope with its counterparty, CSA, EPE over the life, FCA, FBA, FVA and CVA (planned), the costliest first, and the total; the selected set's bars below |
| **C, the counterparty** | `FVA CPTY:CP-ALDERSHOT <GO>` | the counterparty's FVA, the largest set and its share; each netting set's term bars side by side |

### 5.1 The netting-set page

The netting-set page (`NSET NS-ALDERSHOT-FRA`) shows FVA beside exposure: **FVA (engine)** in the strip, the term bars by future date, and
a Funding panel (status, FVA, FCA, FBA, CVA planned, the funding curve and a note naming `FVA NSET:… <GO>` and `PLAY FVA NSET:… <GO>`).
The figures are computed as the page opens, as the viewer, by the same engine (the field `FVA_ENGINE` the counterparty page carries
too); with FVA off on the server the page shows no FVA.

### 5.2 Who sees what

FVA is computed over the netting sets the exposure engine lets the caller see. A role that sees some sets only in aggregate gets the scope's
total without its sets (`SETS_AGGREGATE`), and the playground, which shows each set's profile, refuses it (403).

## 6. The API

All `GET`, read-only: a token with the default `read` scope may call them; nothing is stored and no write scope is needed.

| Endpoint | Answers |
|---|---|
| `GET /api/v1/fva/scope?scope=&eePoint=&fba=&survival=&spread=&run=&strict=&asOf=` | the one answer: `figures` (fca, fba, fva, sumEeDt, life, epeLife), `epeReading`, `terms[]` per primary date (date, tenor, t, dt, df, sb, sl, survival, ee, ene, eeStar, eneStar, eePoint, enePoint, fca, fba), `nettingSets[]` (each set's figures and its `fcaTerms`, `fbaTerms`), `counterparties[]` (sums), `largest`, `settings` (the choices and the curves' points), `cva` (planned), `flags`, `provenance`, `playground` (the command) |
| `GET /api/v1/fva/status` | the settings and the curves of the newest date (with their sources and flags) |
| `GET /api/v1/fva/playground?scope=…` | the answer, the Choices and the Explain steps; up to `playground-max-sets` netting sets |
| `GET /api/v1/fva/playground.xlsx?scope=…` | the workbook of live formulas (section 8) |
| `GET /api/v1/fva/playground/template` | the workbook of the hand-built case F1 (b) |

The scope is the exposure engine's grammar: `NSET:`, `CPTY:`, `GROUP:`, `LE:`, `DESK:`, `BOOK:`, a hierarchy's word, `BANK`.

## 7. The command line

```bash
drishti.py fva show NSET:NS-ALDERSHOT-FRA            # FCA, FBA, FVA, the EPE reading, the flags, the netting sets
drishti.py fva show CPTY:CP-ALDERSHOT --spread 0.006 --fba off
drishti.py fva sets BANK                             # the netting sets by FVA, the costliest first
drishti.py fva status                                # the settings and the funding curve of the newest date
drishti.py play fva NSET:NS-ALDERSHOT-FRA            # the terms by date
drishti.py play fva CPTY:CP-ALDERSHOT --xlsx aldershot-fva.xlsx
drishti.py play fva --example --xlsx fva-example.xlsx
```

Each prints the server's figures (`--json` for the answer itself); the command line computes nothing.

## 8. The FVA Playground (PLAY FVA)

`PLAY FVA NSET:<id> <GO>` (or `CPTY:<id>`, up to 20 netting sets side by side) opens the playground on the VaR Playground's frame: the
Choices (EE point, FBA, survival weighting, the funding spread), the tiles, the terms by date with the formula bar (select a cell: what it
is and its value), the explanation of every step, and **Export to Excel**.

The workbook holds the profile as data and every step after it as a formula of the common set (Excel, LibreOffice Calc and Google Sheets
alike; `LN` and `EXP` for the discount factors, `INDEX` and `MATCH` for the interpolation):

| Tab | What it holds |
|---|---|
| **Main** | the band (ALL CHECKS PASS), each netting set's FCA, FBA and FVA beside the engine's with a check, the scope's sum, the check FVA = FCA + FBA, the EPE reading, CVA (planned) |
| **Config** | the choices as drop-downs (`EePoint`, `FundingSurvival`, `IncludeFba`), the hazards, the borrowing spread's points, the lending spread's (formulas that follow the borrowing ones when funding is symmetric), the discount curve's points from the anchor (0, 1) |
| **Data** | per netting set: today's CE and its negative, then EE and ENE at each primary date |
| **Terms** | per date: t, Δt, DF (log-linear), s_B and s_L (linear), S; per set: EE*, ENE*, the FCA and FBA terms |
| **Explain**, **Provenance** | the words of every step and term ([explain.txt](../../drishti-server/src/main/resources/playground/fva/template/explain.txt)); the run, the curves and their sources, the flags |

A figure equals the engine's when `ABS(workbook − engine) < 0.005`. Flip a choice in Config and FVA recomputes; the engine checks then turn
red by design (the engine answered the original question) while FVA = FCA + FBA stays green. The defined names `FCA`, `FBA`, `FVA`,
`EePoint`, `FundingSurvival` and `IncludeFba` point at their cells.

## 9. Settings and the engine switch

FVA is its own compute engine, `fva`, with its own switch in `config/engines/fva.yaml` (`drishti.engines.fva.enabled`, on by default,
`DRISHTI_FVA_ENABLED`); it **needs the exposure engine** on the same server (Admin → Engines refuses FVA on without it, and exposure off
while FVA is on). Off, `/api/v1/fva` answers 404 DRS-1020, the words `FVA` and `PLAY FVA`, the screen, the playground, the menu items and
these Help pages disappear, and the netting-set page shows no FVA.

```yaml
drishti.ccr.fva:
  funding-spread: CRV-USD-FUNDING    # a curve id or a flat spread (0.0050)
  lending-spread: ""                 # empty: symmetric funding
  fallback-spread: 0.0050            # FUNDING_FALLBACK
  discount-curve: CRV-USD-OIS
  discount-rate: 0.0                 # FLAT_DISCOUNT
  curve-kind: ir-curve
  curves: [{ id: CRV-USD-FUNDING, label: "...", points: [{ tenor: 6M, spread: 0.0040 }, ...] }]
  ee-point: end
  funding-survival: false
  counterparty-hazard: 0.0
  own-hazard: 0.0
  fba: true
  require-risk-neutral: ${DRISHTI_FVA_REQUIRE_RISK_NEUTRAL:false}   # true in production
  playground-max-sets: 20
  playground-template: ""
```

Every key is in [CONFIGURATION.md](../admin/CONFIGURATION.md) and in Help → Configuration.

## 10. Flags, refusals and error codes

| Flag or code | When |
|---|---|
| `REAL_WORLD_RUN` | the exposure run is not calibrated `RISK_NEUTRAL` and `require-risk-neutral` is false: every figure computed and flagged (the demo) |
| **DRS-5447** (409) | the same with `require-risk-neutral: true` (production, decision 10): FVA is a price and needs a risk-neutral run |
| `FUNDING_FALLBACK` | the funding curve was found neither in the curve kind nor in the configuration: the flat `fallback-spread` priced it |
| `FLAT_DISCOUNT` | the discount curve was not found: the flat `discount-rate` discounted |
| `PATHS_DISCOUNTED` | the run's values are already discounted: DF = 1 |
| `SETS_AGGREGATE` | the caller sees some netting sets only in aggregate: the total without the sets |
| `partial`, `stale-run` and the exposure engine's flags | carried from the exposure answer |
| DRS-5440 (400) | a bad scope or choice (`eePoint` not end or mid, `fba` not on or off, a spread outside −5 % to 50 %), or a playground over 20 sets |
| DRS-1020 (404) | FVA or the exposure engine is off on this server |

## 11. The tests that prove it

- **FvaMathTest** (drishti-compute): case F1 (a) to (d) and the CVA.md cross-check (FCA −5,000, FBA 2,812.50, FVA −2,187.50) to the cent,
  the terms of (b) and (c) to the cent, the mid point, FCA only and a lending spread of its own, linear in the profile and additive over
  sets, the curves' interpolation and the tenors.
- **FvaApiTest** (drishti-server, the exposure lake fixture): NS-X by hand (FCA −4,750, FBA 3,000, FVA −1,750 at 50 bp, Δt 0.2, 0.2, 0.6),
  CP-1 as the sum of its sets (−3,250), the choices moving the figures as the formulas say, an aggregate viewer, the playground and its
  workbooks.
- **FvaPlaygroundWorkbookTest**: the workbook written without cached values and recalculated by headless LibreOffice equals the engine to
  the cent for case F1 (a) to (d), the mid point, FCA only, an asymmetric lending spread on an OIS-like curve and two netting sets side by
  side; the formula scanner finds no function outside the common set.
- **test_fvacli.py**, **test_fva.py**: the command line and the console pass the choices through and compute nothing.

## 12. What is not built yet

- The computed kind `fva-result` and its reactive connector (BI and the CCR cube's FVA measure read it), and official FVA captures per set
  and counterparty.
- Survival weighting from each counterparty's credit curve and the bank's own (`own-curve`); today flat hazards from the settings.
- The `paths` mode of the playground (EE and ENE as formulas over the Exposure Playground's paths).
- A funding curve in the market-data pack's generated test data; today the demo's curve is configured in `config/engines/fva.yaml`.
- CVA and DVA in the same engine; the CCR cube's FVA measure and the counterparty tiles of its tab C.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
