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
# SA-CCR guide: exposure at default by the standardised approach

Drishti computes SA-CCR the way it computes VaR, FRTB-SA and SIMM: **from trade-grain inputs, when asked, for any scope of whole netting
sets, never stored as a figure.** The input is one classification row per derivative (`saccr-trade`): its asset class, hedging set,
direction, notional, dates and, for an option, its strike and forward. Everything that is formula (supervisory duration, delta, maturity
factor, effective notional, the add-ons, replacement cost, the multiplier, PFE and EAD) is computed by the server's Java engine, from a
parameter file that names the Basel paragraph of every value. Every figure opens in a playground that shows the same calculation as
spreadsheet formulas, checked against the engine to the cent. The tutorial is
[tutorial 34](../tutorials/34-saccr-from-the-cases-to-a-counterparty.md); the data contract is [SACCR.md](../contracts/SACCR.md); the
design behind it is [COUNTERPARTY_ENGINES.md section 3](../architecture/COUNTERPARTY_ENGINES.md#3-sa-ccr-end-to-end).

## Contents

- [1. What SA-CCR answers](#1-what-sa-ccr-answers)
- [2. The figures at a glance](#2-the-figures-at-a-glance)
- [3. The inputs and their data contract](#3-the-inputs-and-their-data-contract)
- [4. The parameter file](#4-the-parameter-file)
- [5. The method, step by step](#5-the-method-step-by-step)
- [6. Scopes, the what-if and a trade alone](#6-scopes-the-what-if-and-a-trade-alone)
- [7. The SA-CCR page: views A, B and C](#7-the-sa-ccr-page-views-a-b-and-c)
- [8. The SA-CCR Playground (PLAY SACCR)](#8-the-sa-ccr-playground-play-saccr)
- [9. API, command line and settings](#9-api-command-line-and-settings)
- [10. The hand-built cases](#10-the-hand-built-cases)
- [11. Refusals and error codes](#11-refusals-and-error-codes)
- [12. What is not built yet](#12-what-is-not-built-yet)

## 1. What SA-CCR answers

"If this counterparty defaulted today, how much could the bank lose on its derivatives with it, by the regulator's standard formula?"
The answer is the **exposure at default (EAD)** of each netting set: the trades the bank can net against each other in a default because
they sit under one master agreement. EAD is the sum of two parts, multiplied by alpha = 1.4:

- **Replacement cost (RC):** what the counterparty owes the bank today, net of the collateral the bank holds; for a margined set, at
  least the exposure the bank can run before a margin call (the threshold plus the minimum transfer amount, less the independent
  collateral).
- **Potential future exposure (PFE):** how much more it could come to owe before the trades can be closed out: the regulator's
  add-ons, sized from each trade's notional, maturity and type, with hedges offsetting only as far as the rules allow, scaled down by the
  multiplier when the set is out of the money or over-collateralised.

EAD feeds the capital requirement (EAD × the counterparty's risk weight; Drishti shows RWA as an informational hook, the bank's capital
engine owns it), the leverage ratio, large exposures and the basic CVA charge. **EAD adds over netting sets**: a counterparty's EAD is
the sum of its sets', a legal entity's the sum of the sets it faces, the bank's the sum of all. Nothing below a set adds (square roots,
absolute values, the multiplier), so a class's or a trade's figure is never shown as a share of the set's EAD.

## 2. The figures at a glance

| Figure | Formula | Paragraph |
|---|---|---|
| V | Σ MTM of the set's classified derivatives (SFTs and cash products excluded) | CRE52.3 |
| C, NICA | collateral held net, after haircut, NICA included; the net independent collateral amount | CRE52.15 to CRE52.17 |
| RC unmargined | max(V − C, 0) | CRE52.10 |
| RC margined | max(V − C, TH + MTA − NICA, 0) | CRE52.18 |
| SD | (exp(−0.05 S) − exp(−0.05 E)) / 0.05, IR and credit | CRE52.34 |
| d | notional × SD (IR, credit); the foreign leg in USD (FX); units × price (equity, commodity) | CRE52.34 to CRE52.37 |
| δ | ±1; options ±Φ(±d1) with the supervisory σ; tranches ±15 / ((1 + 14 A)(1 + 14 D)) | CRE52.38 to CRE52.41 |
| MF unmargined | √(min(M, 1 year) / 1 year), M floored at 10 business days | CRE52.48 |
| MF margined | 1.5 √(MPOR / 250), MPOR = max(floor, MPOR_DAYS) + F − 1 | CRE52.50 to CRE52.52 |
| D | δ × d × MF | CRE52.24 |
| Add-ons | IR per currency over three maturity buckets; FX per pair; credit and equity per name or index through a common factor; commodity per hedging set over commodity types | CRE52.54 to CRE52.71 |
| multiplier | min(1, 0.05 + 0.95 exp((V − C) / (2 × 0.95 × AddOn_agg))) | CRE52.23 |
| PFE | multiplier × AddOn_agg | CRE52.20 |
| EAD | 1.4 × (RC + PFE); a margined set capped at its unmargined EAD | CRE52.1 |

On the demo data of 2026-09-30: 76 netting sets, 38 with derivatives; the bank's EAD is **22,247,406,196 USD**. CP-ALDERSHOT's is
1,271,935,765 (NS-ALDERSHOT-NY 948,761,509 and NS-ALDERSHOT-LDN 323,174,256; its FRA and TKY sets hold only securities financing and
have no SA-CCR exposure).

## 3. The inputs and their data contract

| Kind | Read for | Contract |
|---|---|---|
| `saccr-trade` | one row per trade and risk driver: `ASSET_CLASS`, `HEDGING_SET`, `TRANSACTION_TYPE` (`LINEAR`, `OPTION`, `CDO_TRANCHE`, `BASIS`, `VOLATILITY`), `REFERENCE`, `IS_INDEX`, `CREDIT_QUALITY`, `DIRECTION`, `NOTIONAL_USD` (or `QUANTITY` × `UNDERLYING_PRICE`), `START_DATE`, `END_DATE`, `MATURITY_DATE`, `EXERCISE_DATE`, `OPTION_TYPE`, `BOUGHT_SOLD`, `STRIKE`, `UNDERLYING_FORWARD`, `SHIFT`, `ATTACHMENT`, `DETACHMENT`, `DERIVED_BY`; optional `SPECIFIC_WWR` | [SACCR.md section 3.3](../contracts/SACCR.md#33-saccr-trade-the-classification-and-terms) |
| `trade-valuation` | each live member of a netting set (status `NEW`, `VALIDATED` or `AMENDED`, not past maturity) and its `MTM` | [TVAL.md](../contracts/TVAL.md) |
| `netting-set` | counterparty, legal entity, `CSA`, `COLLATERAL_BALANCE`, `NETTING_ENFORCEABLE`, `CLEARED` | [NSET.md](../contracts/NSET.md) |
| `csa` | `MARGIN_TYPE` (VM: margined), `THRESHOLD_THEM`, `MTA_THEM`, `MPOR_DAYS`, `MARGIN_FREQUENCY_DAYS`, `DISPUTES_LAST_2Q`, `ILLIQUID_COLLATERAL`, `INDEPENDENT_AMOUNT` | NSET.md |
| `collateral-balance` | C: `NET` (after the CSA's haircuts) plus NICA; `C_REG` and `NICA_REG` (supervisory haircuts, decision 1) when COLL delivers them | [COLL.md](../contracts/COLL.md) |

**Classification is delivered, formulas are Drishti's.** A row that carries a computed figure (an add-on, an EAD, a supervisory delta…)
is refused. A live member of a set without a `DRIVER` 0 row is counted:

- **excluded** when it is not a derivative under CRE52: securities financing (CRE22 covers it), money market, fixed income and the funded
  or cash products listed in `drishti.ccr.saccr.excluded-products`;
- **unclassified** otherwise: the set is marked `incomplete`, its EAD leaves the trade out and says so on every view; asked with
  `strict=true` the answer is refused (`DRS-5411`).

A set whose `NETTING_ENFORCEABLE` is false is not a set: each trade is its own unmargined set (`NS-STANDALONE~<trade>`). A row flagged
`SPECIFIC_WWR` is taken out into its own set with no netting benefit (decision 7). A VM CSA without TH, MTA or MPOR is computed unmargined
and flagged `MARGIN_TERMS_MISSING`. A set with no derivative has nothing to measure (`no derivatives`): its collateral is held against its
other trades.

**The demo's rows** are derived by rules from the trade valuations, as a bank without its own feed would do it
(`tools/packgen/ccr/make_saccr_data.py`, seeded, every row `DERIVED_BY: rules`): 6,527 rows on 2026-09-30 (IR 1,807, FX 1,202, credit
628, equity 992, commodity 1,898; 2,094 options, 89 tranches, 113 basis and 191 volatility rows), a second driver for the FX leg of a
cross-currency swap and the sold floor of a collar, and one derivative of NS-AURORA-LDN deliberately left without a row so the demo shows
an incomplete set. The option terms a booking system does not carry (the forward against the strike, a swaption's exercise date) are drawn
from a generator seeded by the trade id.

## 4. The parameter file

The Basel values ship with the engine as `saccr/saccr-bcbs.yaml` (in drishti-compute's jar); every value names its CRE52 paragraph, and
the engine reads each by its key. A different regulation is its own file (OSFI CAR chapter 4 next, decided 2026-11-02; the US rule and EU
CRR2 when a client asks), named by `drishti.ccr.saccr.parameters-file`. A file with a missing or out-of-range value is refused at start
with every problem listed (`DRS-5413`). Every answer names the file and its SHA-256.

| Key | Value | Paragraph |
|---|---|---|
| `alpha` | 1.4 | CRE52.1 |
| `cap-at-unmargined` | true | CRE52.1 |
| `multiplier-floor` | 0.05 | CRE52.23 |
| `supervisory-duration-rate` | 0.05 | CRE52.34 |
| `ir-buckets.lower-years`, `upper-years` | 1, 5 | CRE52.56 |
| `ir-bucket-weights.b1b2`, `b2b3`, `b1b3` | 1.4, 1.4, 0.6 | CRE52.57 |
| `basis-factor`, `volatility-factor` | 0.5, 5 | CRE52.28, CRE52.29 |
| `cdo-delta.numerator`, `slope` | 15, 14 | CRE52.40 |
| `time.mf-days-per-year`, `sd-days-per-year`, `min-maturity-days` | 250, 365, 10 | CRE52.31 |
| `mpor-floors-days.bilateral`, `cleared`, `large-or-illiquid`, `large-netting-set-trades` | 10, 5, 20, 5,000 | CRE52.50 |
| `dispute-doubling.disputes-over`, `factor` | 2, 2 | CRE52.51 |
| `margined-mf-scale` | 1.5 | CRE52.52 |
| `IR.sf`, `FX.sf` | 0.5 %, 4 % | CRE52.72 |
| `CREDIT.single.sf.<AAA … CCC>` | 0.38, 0.38, 0.42, 0.54, 1.06, 1.6, 6.0 % (ρ 50 %, σ 100 %) | CRE52.72 |
| `CREDIT.index.sf.<IG, SG>` | 0.38, 1.06 % (ρ 80 %, σ 80 %) | CRE52.72 |
| `EQUITY.single`, `EQUITY.index` | SF 32 %, 20 %; ρ 50 %, 80 %; σ 120 %, 75 % | CRE52.72 |
| `COMMODITY.ELECTRICITY`, `COMMODITY.other` | SF 40 %, 18 %; ρ 40 %; σ 150 %, 70 % | CRE52.72 |

`GET /api/v1/saccr/parameters` lists every key with its value and paragraph.

## 5. The method, step by step

Each step with a worked number from the hand-built cases ([section 10](#10-the-hand-built-cases)); the playground's Explain tab has the
same words.

1. **Times.** S, E, M and T in years ACT/365 from the business date; M and E floored at 10 business days (10/250 of a year), S at 0.
   Case S7, a one-year option on a five-year swap: T = 1, S = 1, E = M = 6.
2. **Supervisory duration** (IR, credit). Case S1, a five-year swap: SD = (1 − e^−0.25) / 0.05 = 4.423984.
3. **Adjusted notional.** Case S1: d = 100,000,000 × 4.423984 = 442,398,434. Case S3: a forward to buy EUR 50m at 1.10 has d = 55,000,000.
4. **Supervisory delta.** Linear trades ±1 (a payer swap and sold protection are long; a receiver swap and bought protection short).
   Options: d1 = (ln((P + λ)/(K + λ)) + ½σ²T)/(σ√T); bought call +Φ(d1), sold call −Φ(d1), bought put −Φ(−d1), sold put +Φ(−d1).
   Case S4, a sold index call, P 5,000, K 5,200, T 0.5, σ 75 %: d1 = 0.191210, δ = −0.575819. Case S7 with the shift λ = 1 %: δ = 0.476754.
5. **Maturity factor.** Unmargined √(min(M, 1)); margined 1.5 √(MPOR/250) for every trade of the set. Case S5, daily margin and a
   10-day MPOR: MF = 0.3; three disputes double the floor to 20 days: MF = 0.424264.
6. **Effective notional** D = δ × d × MF, kept on both bases (the cap needs the unmargined one).
7. **IR**: per currency, buckets by E (< 1, 1 to 5 inclusive, > 5 years), EN = √(D1² + D2² + D3² + 1.4 D1D2 + 1.4 D2D3 + 0.6 D1D3),
   add-on 0.5 % × EN. Case S2: EN = 270,359,047, add-on 1,351,795 (3,709,438 with the offsets switched off).
8. **FX**: per pair, 4 % × |Σ D|. Case S3: EURUSD 1,025,256 plus JPYUSD 1,200,000.
9. **Credit, equity**: per name or index AddOn_k = SF_k × Σ D; class add-on √((Σ ρ_k AddOn_k)² + Σ (1 − ρ_k²) AddOn_k²). Case S6: an IG index
   hedge against a BBB name, 1,542,388.
10. **Commodity**: per hedging set (energy, metals, agricultural, other) √((0.4 Σ AddOn_k)² + 0.84 Σ AddOn_k²) over commodity types.
11. **Basis and volatility** transactions form their own hedging sets with SF × 0.5 and × 5.
12. **AddOn_agg** = the five classes added. **RC**, the **multiplier**, **PFE**, **EAD** as in section 2. Case S2, V − C = −2,000,000:
    exponent −0.778692, multiplier 0.486056, PFE 657,048, EAD 919,867.
13. **The cap.** Case S5: margined 1.4 × (4,500,000 + 590,417) = 7,126,583, unmargined 1.4 × (2,000,000 + 1,118,746) = 4,366,244; the
    cap binds, EAD 4,366,244, `CAPPED` true.

## 6. Scopes, the what-if and a trade alone

| Scope | Meaning |
|---|---|
| `NSET:<id>` or the bare id | one netting set |
| `CPTY:<id>` | every set of a counterparty, EAD summed |
| `LE:<id>` | every set the bank's legal entity faces |
| `BANK` | every set of the date |
| `EXAMPLE`, `EXAMPLE:<case>` | the hand-built cases, each its own set (no data read) |

A scope that would cut a set (a desk) is refused (`DRS-5410`): SA-CCR adds over whole netting sets only.

**What-if** (`POST /api/v1/saccr/what-if`): `{nettingSet, candidates: [saccr-trade row + MTM], remove: [tradeId]}`. The set is recomputed
with the candidates added (V moved by their MTM) and the removed trades taken out: EAD, RC, PFE and the multiplier before and after, the
incremental EAD and each hedging set's add-on before and after. A candidate row is checked as a delivered row is (`DRS-5410`). Nothing is
stored.

**A trade alone** (`GET /api/v1/saccr/trade/{tradeId}`): its terms (S, E, M, T, SD, d, d1, δ, both MFs, both Ds, bucket, SF) and its
**standalone EAD**: the trade as its own unmargined set, V its MTM, no collateral. It is never a share of its set's EAD.

## 7. The SA-CCR page: views A, B and C

`SACCR <scope> <GO>` (the Engines menu, SA-CCR) opens one page with three views of **one answer of the server**
(`GET /api/v1/saccr/scope`, every figure computed there, the waterfall's steps included; the browser computes nothing). The decided
mock-ups are in [docs/lenses/mockups/saccr/](../lenses/mockups/saccr/).

| View | What it shows | Opens by default for |
|---|---|---|
| **A · EAD waterfall** (the default) | the tiles (RC, aggregate add-on, multiplier, PFE, EAD); the waterfall from RC through each asset class's add-on after the multiplier to RC + PFE and × 1.4 to EAD; the add-ons by asset class and hedging set with their sub-keys; the formula with the set's own figures | one netting set |
| **B · Netting sets** | every set of the scope (V, C, RC, add-on, multiplier, PFE, EAD; filters margined, unmargined, incomplete) with the selected set's breakdown beside | the bank, a legal entity |
| **C · Sets side by side** | a counterparty's sets each with its own small waterfall, the counterparty's EAD (the sum) on top | a counterparty |

A retired stored id still works: `SACCR SACCR-ALDERSHOT-NY` opens `NSET:NS-ALDERSHOT-NY`. The provenance under each view names the date,
the inputs read, the parameter file and its SHA-256, and the choices.

## 8. The SA-CCR Playground (PLAY SACCR)

`PLAY SACCR <netting set> <GO>` (or `PLAY SACCR EXAMPLE`) opens the playground on the VaR Playground's frame: the grid of every
classification row with its terms (a cell shows its formula in the formula bar), the explanation beside it, the two switches (the IR
bucket offsets and the cap) and **Export to Excel**. The workbook (`GET /api/v1/saccr/playground.xlsx`) holds the same calculation in
live formulas:

| Tab | What it holds | Typed or formula |
|---|---|---|
| **Main** | the band (ALL CHECKS PASS), then per netting set its header (V a formula over Data, C, NICA, TH, MTA, the CSA terms), the MPOR floor, MPOR and MF margined (formulas), and the results margined and unmargined (RC, the five class add-ons, AddOn_agg, the exponent, the multiplier, PFE, EAD, EAD after the cap, CAPPED), each beside the engine's figure with a check; the scope's EAD (a plain sum over the sets) | header typed; results formulas |
| **Config** | the switches (`ir-bucket-offset`, `cap`) as drop-downs; every parameter of the file with its paragraph | typed |
| **Data** | the classification rows as the engine read them (times in years from the business date) with each trade's MTM, the delivered dates beside | values |
| **Trades** | per row: S, E, M, T, SD, d, SF, ρ, σ, d1, δ (`NORMSDIST` for options), both MFs, both Ds, the IR bucket and the sub-key | formulas only |
| **Hedging sets** | per sub-key Σ D (`SUMIFS` over Trades) and its add-on; per hedging set the IR bucket formula, FX \|Σ D\|, the credit and equity correlation formula, the commodity formula | formulas only |
| **Explain** | the method in plain words with worked numbers, every term | text |
| **Provenance** | the date, the inputs, the parameter file and its SHA-256, refused rows, excluded and unclassified members, rows derived by rules, who exported it and when | values |

Change an input in Data or a value in Config and every figure recalculates: the engine checks turn red (the engine answered the original
question) and the add-up checks stay green. A test recalculates the workbook with headless LibreOffice and checks every result against the
engine to the cent. The playground holds up to 20 sets of a counterparty side by side and 5,000 rows
(`drishti.ccr.saccr.max-sets`, `max-trades`); the browser grid shows up to 500 rows. `GET /api/v1/saccr/playground/template` is the
workbook of the hand-built cases.

## 9. API, command line and settings

| Method and path | Answer |
|---|---|
| `GET /api/v1/saccr/scope?scope=&nettingSet=&irBucketOffset=&cap=&strict=&asOf=` | the page's answer: totals (EAD, RC, PFE, sets, rows, excluded, unclassified, RWA hook), every set's summary with its waterfall, the selected set in full (header, both bases, classes, hedging sets, the formula lines), provenance |
| `GET /api/v1/saccr/scope/contributions?scope=&by=netting-set\|asset-class\|hedging-set\|trade` | rows of the scope by part; by trade each row's D and standalone EAD |
| `GET /api/v1/saccr/trade/{tradeId}` | a trade's terms per driver and its standalone EAD |
| `POST /api/v1/saccr/what-if` | the set before and after candidate rows (token scope: read, stores nothing) |
| `GET /api/v1/saccr/parameters` | every parameter with its paragraph, the file and its SHA-256 |
| `GET /api/v1/saccr/playground`, `/playground.xlsx`, `/playground/template` | the playground's grid and explanation; the workbook; the workbook of the cases |

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:18480/api/v1/saccr/scope?scope=CPTY:CP-ALDERSHOT" | jq '.totals.EAD'
# 1271935765.29…
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X POST http://localhost:18480/api/v1/saccr/what-if \
  -d '{"nettingSet":"NS-ALDERSHOT-NY","candidates":[{"ASSET_CLASS":"IR","HEDGING_SET":"USD","TRANSACTION_TYPE":"LINEAR","DIRECTION":"SHORT","NOTIONAL_USD":100000000,"START_DATE":"2026-09-30","END_DATE":"2031-09-30","MATURITY_DATE":"2031-09-30","MTM":0}]}' | jq '.incrementalEad'
```

From the command line (`tools/saccrcli.py`, [TOOLS.md section 5.15a](TOOLS.md#515a-saccr-and-play-saccr)): `drishti.py saccr show SCOPE`,
`saccr contributions SCOPE --by …`, `saccr trade TRADE-ID`, `saccr what-if NETTING-SET --candidates FILE.json`, `saccr parameters`,
`play saccr SCOPE --xlsx FILE`.

The settings are `drishti.ccr.saccr.*` in [config/engines/sa-ccr.yaml](../../config/engines/sa-ccr.yaml) (every key in
[CONFIGURATION.md](../admin/CONFIGURATION.md)); the engine's switch is `drishti.engines.sa-ccr.enabled` (Admin → Engines; off, every
SA-CCR path answers `404 DRS-1020` and the word SACCR and its menu are gone).

## 10. The hand-built cases

Each case is small enough to check with a calculator; the engine's unit test (`SaccrHandBuiltCasesTest`) checks every published EAD and
recomputes each case's arithmetic independently to the cent; `SaccrDemoParityTest` and `SaccrServiceTest` check every demo netting set
against the reference implementation (`tools/packgen/ccr/saccrmath.py`) to the cent; the workbook test recalculates the cases in
LibreOffice.

| Case | What it shows | EAD |
|---|---|---|
| S1 | one USD payer swap, 100m, 5 years, V +1.2m | 4,776,789 (V −1.2m: 2,366,066) |
| S2 | three USD swaps across the three buckets, V −2m | 919,867 |
| S3 | FX forwards in two pairs | 4,235,358 |
| S4 | a sold equity index call and a single-name swap | 11,511,662 |
| S5 | a margined set where TH + MTA − NICA and the cap bind | 4,366,244 (`CAPPED`) |
| S6 | an IG index hedge against a BBB name | 2,859,343 |
| S7 | a bought payer swaption with a shifted delta | 2,664,402 |
| SACCR.md §15 | three IR swaps in USD and EUR; V ±1.5m; margined | 4,184,144; 1,269,235; 1,349,541 |

**The Basel Committee's examples** (BCBS 279, March 2014, Annex 4, examples 1 to 4: an interest-rate set, a credit set, a commodity set
and the three together; published EAD 569, 381, 5,406 and 6,342 thousand) are in the engine as `SaccrExamples.bcbs279()` and give those
figures, but their inputs were typed from memory, not from the published text: they stay **unverified** and their test is skipped until
each input is checked against the publication.

## 11. Refusals and error codes

| Code | Status | When |
|---|---|---|
| `DRS-5410` | 400 | a bad scope or parameter (a scope that cuts a set, an unknown hedging set, `by`), or a row the method refuses: an invalid enum, incomplete option or tranche terms, P + λ or K + λ not positive, dates not S ≤ E ≤ M, a missing notional; a refused delivered row is counted and listed, never charged |
| `DRS-5411` | 409 | `strict=true` and a set of the scope has live derivatives without a classification row |
| `DRS-5412` | 400 | a netting set, counterparty or legal entity the date does not have |
| `DRS-5413` | 502 | the parameter file is refused (every problem listed) |
| `DRS-1002` | 404 | no connector serves `saccr-trade` or `netting-set` |
| `DRS-1020` | 404 | the SA-CCR engine is switched off on this server |

## 12. What is not built yet

- The official captures (`saccr-official`, `saccr-official-cpty`, SACCR.md section 9.5) and the `saccr-result` kind in BI; the CCR cube's
  EAD measure reads this engine when the cube is built.
- OSFI CAR chapter 4's parameter file (next), then the US and EU variants when a client asks.
- Per-counterparty alpha overrides and risk weights for the RWA hook (one configured risk weight today).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
