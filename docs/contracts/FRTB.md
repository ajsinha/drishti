<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# FRTB standardised approach: the data contract

*Contract version 2.0 · **Status:** the inputs `position-sensitivity` and `risk-factor` are active and loaded with the demo data; the
engine (SBM, DRC, RRAO), the capital screen `FRSA`, the playground and its recipes are built; the `default-risk` and `residual-risk`
contracts are `planned`; the computed kind `frtb-sa-result`, the reactive connectors and the official captures are design · Method and
use: [FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md) · Every field, every bucket grid and the worked records: sections 17 to 27 ·
Shared rules: [README.md](README.md) · The same sensitivities feed [SIMM.md](SIMM.md), [STR.md](STR.md) and [PNL.md](PNL.md)*

This is the data contract of Drishti's **FRTB standardised approach** (FRTB-SA): the sensitivities-based method (SBM, Basel MAR21), the
default risk charge for non-securitisations (DRC, MAR22) and the residual risk add-on (RRAO, MAR23). It says what an upstream team
delivers, how Drishti checks it, what Drishti builds from it in memory, what it answers, and how an auditor follows every figure from the
delivered sensitivity to the total. The method as a user meets it (every formula with its paragraph, the hand-built test cases, the
screens and the playground) is in [FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md); this document states each formula once, briefly, so
that the contract can be read on its own, and holds every field of every record (sections 17 to 27).

**Who reads this.** The teams that run the pricing and risk systems (what to send, in which unit and sign, by when), the market-risk and
capital teams (what Drishti computes and how it is checked), and auditors and validators (how to reproduce a figure months later).

**This document also defines the shared sensitivity table** (section 3.2) and **the sensitivity store** in memory (section 7): SIMM,
sensitivity-based stress and P&L explain read the same records and refer here.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to FRTB](#5-conventions-specific-to-frtb)
6. [The computation](#6-the-computation)
7. [What Drishti builds](#7-what-drishti-builds)
8. [Scopes](#8-scopes)
9. [What Drishti answers](#9-what-drishti-answers)
10. [Validation, errors and data quality](#10-validation-errors-and-data-quality)
11. [Entitlements and audit](#11-entitlements-and-audit)
12. [Performance and sizing](#12-performance-and-sizing)
13. [Test data and generators](#13-test-data-and-generators)
14. [Retiring the stored version](#14-retiring-the-stored-version)
15. [A worked example](#15-a-worked-example)
16. [Open questions and decisions](#16-open-questions-and-decisions)
17. [How to read the field tables](#17-how-to-read-the-field-tables)
18. [The factor naming standard](#18-the-factor-naming-standard)
19. [The bucket grids](#19-the-bucket-grids)
20. [position-sensitivity (SENS): every field](#20-position-sensitivity-sens-every-field)
21. [risk-factor (RFAC): every field](#21-risk-factor-rfac-every-field)
22. [Default risk (DRC) inputs: every field](#22-default-risk-drc-inputs-every-field)
23. [Residual risk (RRAO) inputs: every field](#23-residual-risk-rrao-inputs-every-field)
24. [The parameter files' structure](#24-the-parameter-files-structure)
25. [Computed records: every field](#25-computed-records-every-field)
26. [Worked JSON Lines examples](#26-worked-json-lines-examples)
27. [Mapping from upstream formats](#27-mapping-from-upstream-formats)

## 1. Summary

**What it is.** The FRTB-SA capital of any set of positions on a business date: delta, vega and curvature for each risk class under the
three correlation scenarios, the binding scenario, the default risk charge and the residual risk add-on, with every intermediate figure
(weighted sensitivity, bucket charge, bucket sum, the curvature side chosen) and each part's contribution. Computed when asked, for any
scope, from trade-grain inputs; never stored as a figure except as an official capture. **The regime:** Basel MAR21 to MAR23 as the
reference, then OSFI CAR chapter 9, as for the regulatory VaR reports (decision 4).

**Not in scope:** the internal models approach and its eligibility tests (the P&L attribution test belongs with [PNL.md](PNL.md)); the
SBM and DRC of securitisations and the correlation trading portfolio (their inputs are accepted and counted, their charge is reported as
*not computed* until their parameters are added, section 16); a jurisdiction's reporting templates.

**Commands** (every parameter: [FRTB_SA_GUIDE.md section 9](../guides/FRTB_SA_GUIDE.md#9-api-command-line-and-settings)).

| What | Command | API | CLI |
|---|---|---|---|
| Sensitivities of a scope, by factor, bucket or grid | `SENS <scope>` | `GET /api/v1/risk/sensitivities` | `drishti.py sens show` |
| The factor dictionary and the mapping refusals | `RFAC <factor or name>` | `GET /api/v1/risk/risk-factors` | `drishti.py sens factors` |
| FRTB-SA of a scope: SBM by class and scenario, DRC, RRAO | `FRSA <scope>` | `GET /api/v1/risk/frtb/sa` | `drishti.py frtb sa` |
| Its steps: buckets, WS, K, S, the scenario | in `FRSA` | `GET /api/v1/risk/frtb/sa/explain` | `drishti.py frtb explain` |
| Contributions | in `FRSA` | `GET /api/v1/risk/frtb/sa/contributions` | `drishti.py frtb contributions` |
| The playground and its recipes | `PLAY FRTB <scope>`, `PLAY FRTB RUN <recipe>` | `/api/v1/risk/frtb/playground` (and `.xlsx`) | `drishti.py play frtb` |
| The official capture per filer and date | not built yet | | |

| Part | Status | Where |
|---|---|---|
| `position-sensitivity` (SENS): the shared input, one record per position or trade with a sparse list of sensitivities | contract `active`, demo data loaded | sections 3.2, 20 |
| `risk-factor` (RFAC): the factor dictionary, its FRTB and SIMM mapping and the bank's own names | contract `active`, demo data loaded | sections 4.1, 21 |
| `default-risk` (DRCI): obligor, seniority, rating, notional, market value, maturity | contract `planned`; no demo data | sections 3.3, 22 |
| `residual-risk` (RRAI): RRAO class overrides, gross notional, exclusion flags | contract `planned`; no demo data | sections 3.4, 23 |
| `capital-input` (CAPI): the bank's own charges per filer, read for comparison only | contract `planned` | section 3.5 |
| Parameter files: `frtb-sa-sbm.yaml`, `frtb-sa-drc-rrao.yaml` | in the market-risk pack, read and checked at start | sections 6.1, 24 |
| The engine, the API, the capital screen, the playground and its recipes | built | sections 6 to 9 |
| The computed kind `frtb-sa-result`, the reactive connectors, official captures | design | sections 9.2, 9.5, 9.6, 25 |
| Retirement of the stored kind `frtb-sensitivity` | with the computed kind | section 14 |

## 2. The measure in one page

**What it answers:** *how much capital does this set of positions need under the standardised approach, which risk class, bucket and
scenario bind, and which desks, positions or risk factors drive it?*

1. **Sensitivities arrive** from the bank's pricing and risk systems, in the same run as the scenario P&L that feeds VaR: per position
   (or trade), per risk factor and measure (delta, vega, the two curvature revaluations). Drishti does not reprice.
2. **Each factor name is checked** against the factor dictionary; the dictionary gives each factor its risk class and bucket.
3. **Each delivered value becomes the regulatory sensitivity** by a fixed constant (DV01 × 10,000, DELTA × 100, VEGA × 100 × implied
   volatility; section 3.2.3), converted to the reporting currency.
4. **Within the scope, sensitivities to the same factor are added** (MAR21.4(2)): this is the only additive step.
5. **Weighted sensitivity** WS = risk weight × sensitivity; **within a bucket** the WS are combined with the correlations ρ into K_b;
   **across buckets** with the correlations γ into the class's charge. Curvature takes the larger of an upward and a downward loss.
6. Steps 5 run under **three correlation scenarios** (low, medium, high). In each, every class's delta, vega and curvature are added;
   the **largest** of the three totals is the SBM.
7. **DRC:** jump-to-default per position, scaled by maturity, netted per obligor, weighted by credit quality, with a hedge benefit ratio
   per bucket. **RRAO:** 1.0 % or 0.1 % of the gross notional of instruments with exotic or other residual risks.
8. **Total FRTB-SA = SBM + DRC + RRAO.** Each part is shown on its own; a part whose inputs are missing is *not computed*, never zero.

**Additive and not additive.** Only the netting of step 4 (and the per-obligor JTD sums and the RRAO notionals) adds across positions.
Everything after the risk weight is not: a desk's charges add to more than the bank's, and the difference is shown as diversification.

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/                     the common risk lake, connector risk-store, domain risk
├── position-sensitivity/   business_date=YYYY-MM-DD/   one record per position or trade, batch; SENSITIVITIES[] sparse    (planned)
├── risk-factor/            business_date=YYYY-MM-DD/   one row per risk factor; slowly changing                          (planned)
├── default-risk/           business_date=YYYY-MM-DD/   one row per position or trade and obligor (DRC)                   (draft, optional)
├── residual-risk/          business_date=YYYY-MM-DD/   one row per trade in the RRAO's scope (overrides and flags)       (draft, optional)
└── capital-input/          business_date=YYYY-MM-DD/   one row per filer, regime and date                                (planned)
```

Delta or Iceberg tables, one per kind, as every risk input ([README.md section 2](README.md#2-the-common-risk-lake)); delivered as JSON
Lines (UTF-8, gzip, one record per line) and loaded into the lake, or written directly by a Delta or Iceberg writer that follows the
contract. The YAML contracts are the machine-readable truth for the two planned kinds:
[`position-sensitivity.contract.yaml`](../../config/packs/banking/market-risk/contracts/position-sensitivity.contract.yaml),
[`risk-factor.contract.yaml`](../../config/packs/banking/market-risk/contracts/risk-factor.contract.yaml) and
[`capital-input.contract.yaml`](../../config/packs/banking/market-risk/contracts/capital-input.contract.yaml); their generated pages are in
[packs/market-risk.md](packs/market-risk.md#sens-position-sensitivities-position-sensitivity) and a real demo record explained field by
field is in [samples/market-risk.md](samples/market-risk.md#position-sensitivities-position-sensitivity-sens) and
[samples/market-risk.md](samples/market-risk.md#risk-factor-dictionary-risk-factor-rfac).

### 3.2 The shared sensitivity table: position-sensitivity (SENS)

**Grain:** one record per position (`GRAIN` `POSITION`) or trade (`GRAIN` `TRADE`), business date and batch. **Key:** `SENSITIVITY_ID` =
`SENS-<POSITION_ID or TRADE_ID>-<BATCH_ID>`, unique per business date. **The sensitivities** are a sparse list in the record, one element
per risk factor and measure, never the same pair twice. **Partition:** `BUSINESS_DATE`. **Promoted columns** (read without parsing the
document): `SENSITIVITY_ID`, `GRAIN`, `POSITION_ID`, `TRADE_ID`, `SOURCE_SYSTEM`, `BATCH_ID`, `STATUS`, `DESK`, `TT_BOOK`, `PRODUCT_TYPE`,
`CURRENCY`. **Readers:** FRTB-SA (this document), SIMM ([SIMM.md](SIMM.md#3-inputs-from-upstream-engines)), the MR cube's ladders and
heat maps, sensitivity limits, sensitivity-based stress ([STR.md](STR.md)) and P&L explain ([PNL.md](PNL.md)). A bank delivers one
sensitivity batch, not one per consumer.

**Position or trade.** A position is the one Drishti derives by the market-risk pack's position rules (`POS-<TT_BOOK>~<key>`), so the
record joins the position's VaR vectors. A source that computes by trade sends `GRAIN` `TRADE`; Drishti adds its trades' elements into
the position (the trade's `POSITION_ID`, when the source knows it, or the position rules). One source system sends one grain on one date.

#### 3.2.1 The record

Every field, type and example: [section 20](#20-position-sensitivity-sens-every-field).
In groups:

| Group | Fields | Required | What it is for |
|---|---|---|---|
| Identity | `SENSITIVITY_ID`, `GRAIN`, `POSITION_ID` (required for `POSITION`), `TRADE_ID` (required for `TRADE`), `TRADE_IDS` | yes, as stated | the key; the join to the VaR vectors and the trade master |
| The run | `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND` (`FULL`, `INTRADAY`), `COMPUTED_AT`, `RESTATEMENT`, `STATUS` (`ok`, `proxied`, `failed`), `PROXY_REASON` | yes except `PROXY_REASON` | which run, when, and how good (section 3.6) |
| Audit | `PRICER`, `MODEL_VERSION` | no | the pricing library and model version |
| Placement | `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`, `FRTB_DESK` | yes except `LIMIT_LETTER`, `FRTB_DESK` | as on the P&L vectors; `FRTB_DESK` the regulatory trading desk when it differs from the management desk |
| Terms | `PRODUCT_GROUP`, `PRODUCT_TYPE`, `TRADE_STATUS`, `MATURITY_DATE`, `CURRENCY` | yes | the live rule, the RRAO's product classes, the DRC's maturity |
| `SENSITIVITIES[]` | `RISK_FACTOR`, `MEASURE`, `VALUE`, `CURRENCY` (required); `RISK_CLASS`, `FRTB_BUCKET`, `TENOR`, `SIMM_BUCKET`, `SHOCK_SIZE`, `IMPLIED_VOL` (optional) | as stated | one element per factor and measure |

`SENSITIVITIES` is empty only when `TRADE_STATUS` is `CANCELLED`, and null (never zeros) when `STATUS` is `failed`. `RISK_CLASS`,
`FRTB_BUCKET`, `TENOR` and `SIMM_BUCKET` on an element are the source's view: the dictionary is the authority, and a difference is a
warning (section 10).

#### 3.2.2 Units and signs

Every `VALUE` is **the change in the value of the position, in the element's `CURRENCY`, for the stated move, a gain positive**: a long
bond has a negative DV01, a bought payer swaption a positive vega.

| `MEASURE` | Factor names | `VALUE` is | Unit |
|---|---|---|---|
| `DV01` | `IR/CURVE/...` | V(r + 1 bp) − V(r) | per basis point of the rate |
| `IE01` | `INF/...` | V(i + 1 bp) − V(i) | per basis point of the inflation rate |
| `CS01` | `CR/SPREAD/...` | V(cs + 1 bp) − V(cs) | per basis point of the spread |
| `DELTA` | `FX/SPOT`, `EQ/SPOT`, `CM/PRICE` | V(1.01 · x) − V(x) | per 1 % relative move of the spot or price |
| `DELTA` | `EQ/REPO` | V(repo + 1 bp) − V(repo) | per basis point of the repo rate |
| `VEGA` | `VOL/...` | V(σ + 0.01) − V(σ) | per volatility point; lognormal (relative) volatility for every class by default (decision 3) |
| `CURVATURE_UP`, `CURVATURE_DOWN` | the curve, issuer, commodity or spot without a tenor | V(x shocked up) − V(x) and V(x shocked down) − V(x), the full revaluation, delta included | currency, with `SHOCK_SIZE` |
| `GAMMA` | the factor or the whole curve | the second derivative in the delta's unit: per (1 bp)² or per (1 %)² | only from a source that cannot revalue for curvature (decision 2) |
| `BASE_CORR`, `BETA` | reserved | SIMM's base-correlation and equity-beta inputs | not read by FRTB-SA |

**`SHOCK_SIZE`** on a curvature element: basis points for rates and spreads (GIRR 170, the 1.7 % weight of MAR21.99 undivided,
decision 8; CSR the bucket's risk weight, 500 for bucket 3), a fraction of the spot or price for equity, FX and commodity (the bucket's
risk weight: 0.15 for FX, 0.35 for commodity bucket 2). **`IMPLIED_VOL`** on a vega element: a fraction (0.28 = 28 %). A source whose rates
volatilities are normal (in basis points) declares it in its load configuration, and Drishti converts its vegas per source on load
(decision 3).

#### 3.2.3 From the delivered values to the regulatory sensitivities

MAR21.19 to MAR21.25 define each sensitivity per unit of its factor. Drishti converts each element by a constant, in its own currency,
and then into the reporting currency at the business date's FX, before anything is added
([FRTB_SA_GUIDE.md section 3](../guides/FRTB_SA_GUIDE.md#3-the-inputs-and-their-data-contract)):

```
GIRR, CSR delta; equity repo      s_k = VALUE × 10,000          (DV01, IE01, CS01; DELTA of an EQ/REPO factor)    MAR21.19 to MAR21.24
Equity spot, commodity, FX delta  s_k = VALUE × 100             (DELTA per 1 %)                                    MAR21.19 to MAR21.24
Vega, every class                 s_k = VALUE × 100 × IMPLIED_VOL                (∂V/∂σ · σ, the vega risk sensitivity)   MAR21.25
Curvature                         read as delivered (currency amounts), with the delta of section 6.4
```

#### 3.2.4 What each risk class must deliver

The patterns are the factor naming standard ([section 18](#18-the-factor-naming-standard)).
"Vertices" are the tenors MAR21.8 prescribes; a sensitivity at another tenor is allocated by the source to the two vertices around it
before delivery (linear in time), never by Drishti.

| Class | Delta | Vega | Curvature | Vertices |
|---|---|---|---|---|
| **GIRR** | `DV01` on `IR/CURVE/<ccy>/<curve>/<tenor>`; `IE01` on `INF/<index>/<tenor>` | `VEGA` with `IMPLIED_VOL` on `VOL/IR/<ccy>/<curve>/<expiry>/<underlying tenor>` | `CURVATURE_UP` and `CURVATURE_DOWN` with `SHOCK_SIZE` 170 on `IR/CURVE/<ccy>/<curve>`, every curve of the currency shifted together | delta 3M, 6M, 1Y, 2Y, 3Y, 5Y, 10Y, 15Y, 20Y, 30Y; vega expiry and underlying 6M, 1Y, 3Y, 5Y, 10Y |
| **CSR non-securitisation** | `CS01` on `CR/SPREAD/<issuer>/<BOND or CDS>/<tenor>` | `VEGA` on `VOL/CR/<issuer>/<expiry>` | on `CR/SPREAD/<issuer>`, the bond and CDS curves as one, `SHOCK_SIZE` the bucket's risk weight in bp | 6M, 1Y, 3Y, 5Y, 10Y |
| **Equity** | `DELTA` on `EQ/SPOT/<underlying>` (per 1 %) and `EQ/REPO/<name>` (per bp) | `VEGA` on `VOL/EQ/<underlying>/<expiry>` | on `EQ/SPOT/<underlying>`, `SHOCK_SIZE` the bucket's spot risk weight; none for repo | vega 6M, 1Y, 3Y, 5Y, 10Y |
| **Commodity** | `DELTA` on `CM/PRICE/<commodity>/<location>/<tenor>` | `VEGA` on `VOL/CM/<commodity>/<expiry>` | on `CM/PRICE/<commodity>/<location>`, the whole curve, `SHOCK_SIZE` the bucket's risk weight | delta 0 (spot), 3M, 6M, 1Y, 2Y, 3Y, 5Y, 10Y, 15Y, 20Y, 30Y |
| **FX** | `DELTA` on `FX/SPOT/<ccy>` (the currency's value in the reporting currency, per 1 %) | `VEGA` on `VOL/FX/<ccy>/<expiry>` | on `FX/SPOT/<ccy>`, `SHOCK_SIZE` 0.15 | vega 6M, 1Y, 3Y, 5Y, 10Y |

Every class: `CURRENCY` on every element; `SHOCK_SIZE` on every curvature element; `IMPLIED_VOL` on every vega element. A source that
cannot revalue sends `GAMMA` on the curvature factor's name instead of the two curvature elements: the gamma of the parallel shift of
every tenor of every curve of the factor; per-tenor gammas are accepted and summed as a second approximation (section 6.4). Inflation is
delivered per tenor (SIMM and the limits use the ladder); FRTB adds a currency's inflation sensitivities into one factor, as MAR21.8 treats
inflation as a flat curve.

#### 3.2.5 Rows per position (typical)

| Product | Delta elements | Vega | Curvature | Total |
|---|---|---|---|---|
| Interest-rate swap, one currency, two curves | 2 to 4 vertices × 2 curves | | | 4 to 8 |
| Swaption | 4 to 8 | 2 expiries × 2 underlying tenors | 2 | about 14 |
| FX forward | 1 FX, 2 to 4 GIRR | | | 3 to 5 |
| Equity option | spot, repo, 2 to 4 GIRR | 2 | 2 | about 10 |
| Bond, CDS | 2 CSR vertices + 2 to 4 GIRR | | | 4 to 6 |

The demo averages 3.2 elements a record (rate, credit and inflation ladders on the two vertices around maturity); a production book with
full ladders and both curves averages 15 to 25 (estimate; section 12 uses 20).

### 3.3 Default risk: the DRC inputs (optional)

Contract `default-risk.contract.yaml` (`status: planned`; the delivery is open question 2 of section 16). **Grain:** one row per position (or trade), obligor and business date, for every position with
default risk: bonds, CDS and other credit derivatives, equities and equity derivatives. **Key:** `DEFAULT_RISK_ID` =
`DRC-<POSITION_ID or TRADE_ID>-<OBLIGOR>-<BATCH_ID>`. Every field and an example:
[section 22](#22-default-risk-drc-inputs-every-field).

| Field | Meaning |
|---|---|
| `OBLIGOR` | the issuer or reference entity (LEI preferred), the netting unit of MAR22.19 |
| `DRC_BUCKET` | `CORPORATE`, `SOVEREIGN`, `LOCAL_GOVERNMENT_MUNICIPALITY` (MAR22.22) |
| `SENIORITY` | `COVERED_BOND`, `SENIOR`, `NON_SENIOR`, `EQUITY` (MAR22.12; netting by seniority, MAR22.19 to MAR22.21) |
| `CREDIT_QUALITY` | `AAA`, `AA`, `A`, `BBB`, `BB`, `B`, `CCC`, `UNRATED`, `DEFAULTED` (MAR22.24) |
| `NOTIONAL` | the bond-equivalent notional, **positive for a long credit exposure** (a loss on default), negative for a short (a bought CDS is short, a sold put on a bond long; MAR22.10, MAR22.13) |
| `MARKET_VALUE` | the bond-equivalent market value; `P&L = MARKET_VALUE − NOTIONAL` (MAR22.14) |
| `CURRENCY` | of the two amounts |
| `MATURITY_DATE` | the instrument's (of the derivative, not its underlying), for the maturity scaling (MAR22.15 to MAR22.18) |
| `CASH_EQUITY_MATURITY` | cash equity only: `0.25` or `1` years, the bank's choice (MAR22.16) |
| `RECOVERY_INDEPENDENT` | true when the price does not depend on recovery: no LGD applied (MAR22.12) |

Without default-risk rows, Drishti may **derive** them from the trade records and the issuer master (obligor and rating from `issuer`,
seniority from the bond's terms, notional and market value from the valuation); a derived DRC is labelled *derived from the trade and
issuer masters* on every screen. When neither exists, the answer says *DRC not computed: no default-risk inputs for <scope> on <date>*.

### 3.4 Residual risk: the RRAO inputs (optional)

**By default the RRAO needs no extra file:** each position's `PRODUCT_TYPE` (on SENS) is mapped by the parameter file's `product-types`
to `exotic` (1.0 %, MAR23.3) or `other` (0.1 %, MAR23.4 and MAR23.5), and a type absent from both lists is out of scope (MAR23.6 lists
the risks that do not bring an instrument in). The **gross notional** comes from the trade master. An optional `residual-risk`
table (contract `residual-risk.contract.yaml`, `status: planned`) carries what neither SENS nor the trade master holds today (section 16): one row per trade with `RRAO_CLASS` (`EXOTIC`, `OTHER`,
`NONE`, an override of the mapping), `GROSS_NOTIONAL` and `CURRENCY`, `BACK_TO_BACK_OF` (the trade it exactly matches with a third
party: both excluded, MAR23.7) and `LISTED_OR_CLEARABLE` (excluded from the other-residual part only; an exotic underlying stays in).
Every field: [section 23](#23-residual-risk-rrao-inputs-every-field).

### 3.5 capital-input (CAPI): the bank's own charges

One row per filer, regime and date ([contract](../../config/packs/banking/market-risk/contracts/capital-input.contract.yaml)). FRTB-SA reads
only `DEFAULT_RISK_CHARGE`, the bank's own DRC figure, and only to show it **beside** Drishti's computed DRC during a parallel run: it never
enters the total. The rest of the row (multiplier override, add-on, the US specific, incremental and comprehensive risk charges) belongs
to the regulatory VaR capital ([REGULATORY_VAR_REPORTING.md](../guides/REGULATORY_VAR_REPORTING.md)).

### 3.6 Delivery: cadence, batches, intraday and restatements

| Item | Contract |
|---|---|
| Cadence | daily: the **full run** after the close, in the same run as the scenario PVs (by 06:30 New York the next business day in the demo; the bank's time to be agreed) |
| Batches | one `FULL` run per source system and business date, its own `BATCH_ID`; any number of `INTRADAY` batches after it |
| Intraday | an intraday batch carries only the changed positions; its record **replaces the position's record whole** (never an element at a time); the record with the latest `COMPUTED_AT` wins |
| Restatement | the source sends the date again with `RESTATEMENT` 1, 2, … and a later `COMPUTED_AT`; the restated full run replaces every record of that source and date. The earlier versions stay readable through the lake's time travel |
| Proxied | `STATUS` `proxied` with `PROXY_REASON`: used, counted and shown beside every sum |
| Failed | `STATUS` `failed`, `SENSITIVITIES` null: the position counts as missing, never as zero |
| Complete when | every live position with VaR vectors has a record (`ok`, `proxied` or `failed`); the reverse (sensitivities without vectors) is also reported, by desk |
| Dictionary | `risk-factor` is delivered on change, before the first business date a new factor applies to; a date without a delivery keeps the last |

This differs from [README.md section 6](README.md#6-dates-as-of-and-restatements), where a date is replaced whole: sensitivities are
replaced per record, because intraday batches carry only what changed. **Which records a figure used** is always recorded: the batch ids
and the table versions are in every answer's provenance and in every playground recipe (section 9.7), so a figure is reproducible after
any later batch.

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `risk-factor` (RFAC) | `RISK_FACTOR`, `SOURCE_NAMES` | every factor's class, bucket, tenor and grid mapping; the bank's names (section 4.1) |
| `trade` (TRD) | `TRADE_ID` | live status, gross notional (RRAO), the DRC's derived inputs |
| `issuer` (ISS), `lei-entity` (LEI) | `OBLIGOR`, the issuer in a factor name | the obligor's rating and sector (DRC bucket and risk weight when derived) |
| the VaR hierarchies (built from the trades' columns) | `NODE_ID` | the scopes: desks, legal entities, limit letters; the FRTB desks |
| `pnl-vector` (positions) | `POSITION_ID` | the completeness check and the consistency check against VaR |
| FX rates of the date | currency pair | conversion of every element to the reporting currency |
| The parameter files | file name, version and hash | section 6.1 |

### 4.1 The factor dictionary (RFAC)

One row per risk factor, slowly changing (`VALID_FROM`, `VALID_TO`), keyed by `RISK_FACTOR`
([contract](../../config/packs/banking/market-risk/contracts/risk-factor.contract.yaml); every field in
[section 21](#21-risk-factor-rfac-every-field)). It holds the factor's parts (`FACTOR_TYPE`,
`QUALIFIER`, `CURVE`, `UNDERLYING`, `TENOR`, `UNDERLYING_TENOR`), its FRTB mapping (`RISK_CLASS`, `FRTB_BUCKET`, and for the bucket tests
`SECTOR`, `CREDIT_QUALITY`, `MARKET_CAP`, `ECONOMY`), its SIMM mapping (`SIMM_RISK_CLASS`, `SIMM_BUCKET`, `SIMM_LABEL1`, `SIMM_LABEL2`),
its liquidity horizon, and `SOURCE_NAMES`: the names each source system gives the factor.

- **A source name maps to exactly one factor.** A name listed under two factors refuses the dictionary of that date; a sensitivity
  delivered under a name in no list is refused and counted. A new factor is added to the dictionary, never guessed from its name.
- **The grids decide.** `FRTB_BUCKET` and `SIMM_BUCKET` are defaults; the bucket grids of the configuration (FRTB, SIMM and the bank's
  limit grids) decide, and a factor falls in exactly one bucket of each grid (an overlap or a gap refuses the grid at start).
- **The vega liquidity horizon** of FRTB-SA is the class's (MAR21.92: GIRR 60, CSR 120, commodity 120, FX 40, equity 20 or 60 by bucket)
  and comes from the parameter file; the dictionary's `LIQUIDITY_HORIZON` (MAR33.12) is for the internal models approach.

## 5. Conventions specific to FRTB

| Convention | Rule |
|---|---|
| Sensitivity sign | the position's gain for the stated up-move, gain positive (section 3.2.2) |
| Charges | reported **positive** (capital); a contribution may be negative (a hedge) |
| Reporting currency | the filer's (USD in the demo); every element converted at the business date's FX before netting; no FX inside the formulas |
| Tenors | text codes mapped to years for the correlations: `3M` 0.25, `6M` 0.5, `1Y` 1 … `30Y` 30; commodity spot `0D` = 0 |
| Specified currencies | GIRR risk weights ÷ √2 for EUR, USD, GBP, AUD, JPY, SEK, CAD and the reporting currency (MAR21.44), when the bank chooses it |
| Rounding | all arithmetic in float64; figures rounded to whole currency units only on screen; check cells compare to the cent |
| Determinism | the same inputs, parameters and switches give the same bits: sums in a fixed order (position ordinal, then factor ordinal; shards in shard order) |

## 6. The computation

### 6.1 Parameters: the files, their versions and their hash

| File | Holds | Source |
|---|---|---|
| [`frtb-sa-sbm.yaml`](../../config/packs/banking/market-risk/capital/frtb-sa-sbm.yaml) | SBM risk weights per class, bucket and vertex; ρ and γ; the scenario formulas; the curvature rules (factor, shock, correlation, FX divisor) | Basel MAR21 |
| [`frtb-sa-drc-rrao.yaml`](../../config/packs/banking/market-risk/capital/frtb-sa-drc-rrao.yaml) | LGDs, maturity floor and cap, DRC buckets and risk weights, netting rule, HBR; RRAO weights, exotic and other lists, exclusions, the product-type mapping | Basel MAR22, MAR23 |

**Rules** ([FRTB_SA_GUIDE.md section 12](../guides/FRTB_SA_GUIDE.md#12-parameters-as-configuration); their keys: [section 24](#24-the-parameter-files-structure)): one file per regime (Basel,
then a jurisdiction's variant); each names its regime, its effective date and the paragraph of every value. A filer is configured with
its regime. The engine loads every file at start and checks it (every value present and in range, every correlation matrix symmetric with
a unit diagonal and positive semi-definite in each scenario, every paragraph reference present); a file that fails is refused and the
engine does not answer from it. **A changed file is a new version, never an edit of the one in force.** The file's SHA-256 is in every
answer's provenance, in every recipe and in every official capture, which also names the version.

### 6.2 Weighted sensitivity

```
s_k  = Σ_{positions in scope} s_k,position             (after section 3.2.3; inflation of a currency added into one factor)
WS_k = RW_k · s_k                                       MAR21.4(3)
```

### 6.3 Delta and vega: within and across buckets

```
K_b = sqrt( max( 0, Σ_k WS_k² + Σ_k Σ_{l≠k} ρ_kl · WS_k · WS_l ) )                     MAR21.4(4)
other-sector buckets (CSR 16, equity 11): K_b = Σ_k |WS_k|                              MAR21.56, MAR21.79
S_b = Σ_k WS_k
Charge = sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc · S_b · S_c )                                MAR21.4(5)(a)
if the sum under the root is negative: S_b = max( min( Σ_k WS_k, K_b ), −K_b ) and again   MAR21.4(5)(b), the alternative specification
```

The risk weights, ρ and γ per class are in the parameter file and summarised in
[section 19](#19-the-bucket-grids). **Vega** uses the same steps with the vega risk weight
`min(55 % · sqrt(LH / 10), 100 %)` (MAR21.92) and the vega correlations (MAR21.93 for GIRR, MAR21.94 for the others: the option-maturity
correlation `exp(−1 % · |T_k − T_l| / min(T_k, T_l))` times the delta correlation of the underlyings, capped at 1).

**GIRR within a bucket** (decision 10): the tenor correlation is taken from the **published table** of MAR21.46 (rounded to 0.1 %) by
default; the exact formula `max(exp(−3 % · |T_k − T_l| / min(T_k, T_l)), 40 %)` behind it is a switch (section 9.7).

### 6.4 Curvature

For each curvature factor *k* (a currency for GIRR, an issuer for CSR, a commodity curve, an equity or FX spot), summed over the
positions *i* of the scope (MAR21.5(2)):

```
CVR_k+ = − Σ_i ( CURVATURE_UP_ik   − RW_k · s_ik )
CVR_k− = − Σ_i ( CURVATURE_DOWN_ik + RW_k · s_ik )
RW_k · s_ik:  GIRR, CSR   Σ_tenors DV01 (or CS01) × SHOCK_SIZE in bp
              commodity   Σ_tenors DELTA × 100 × SHOCK_SIZE
              equity, FX  DELTA × 100 × SHOCK_SIZE
K_b± = sqrt( max( 0, Σ_k max(CVR_k±, 0)² + Σ_k Σ_{l≠k} ρ_kl² · CVR_k± · CVR_l± · ψ(CVR_k±, CVR_l±) ) )    ψ = 0 when both < 0, else 1
K_b  = max( K_b+, K_b− )   the side: up when K_b+ > K_b−, down when K_b− > K_b+; on a tie up when Σ CVR+ > Σ CVR−, else down
Curvature = sqrt( max( 0, Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc² · S_b · S_c · ψ(S_b, S_c) ) ),   S_b = Σ_k CVR_k of the side chosen
```

The side is chosen per bucket **and per scenario**. The correlations are the delta ones **squared first, then the scenario applied**
(MAR21.100, MAR21.101), with only the dimensions curvature keeps (ρ_name for CSR, ρ_commodity for commodity). Other-sector buckets:
`K_b = max( Σ max(CVR+, 0), Σ max(CVR−, 0) )`. **FX ÷ 1.5** (MAR21.98, decisions 6 and 9): CVR+ and CVR− of FX options that do not
reference the reporting currency are divided by 1.5 by default; every FX instrument only with the supervisor's approval (a second
setting, off).

**Curvature estimated from gamma** (decisions 2 and 7). Where a source sends `GAMMA` instead of the two revaluations:
`CVR_k+ ≈ CVR_k− ≈ −½ · Γ_k · h_k²`, h the shock in the delta's unit (170 bp for GIRR, 15 for a 15 % spot shock). It is an **estimate**,
labelled so on every screen and export, and an **analysis figure until the supervisor accepts it for the filing** (MAR21.17 and its
FAQ); where both exist, the playground shows it beside the revalued figure.

### 6.5 The three scenarios and the SBM

```
medium  ρ           high  min(1.25 · ρ, 1)           low  max(2 · ρ − 1, 0.75 · ρ)          every ρ and γ, MAR21.6
SBM(s) = Σ_classes ( Delta_class(s) + Vega_class(s) + Curvature_class(s) )
SBM    = max( SBM(low), SBM(medium), SBM(high) )                                            MAR21.7; the binding scenario is named
```

The three are compared only after the classes are added, never class by class. A desk computed as a standalone portfolio compares its
scenarios at the desk (MAR21.7(2)(b)).

### 6.6 Default risk charge (MAR22, non-securitisations)

```
JTD_long  = max( LGD · NOTIONAL + (MARKET_VALUE − NOTIONAL), 0 )      JTD_short = min( same, 0 )          MAR22.9 to MAR22.14
LGD: senior 75 %, non-senior and equity 100 %, covered bond 25 %, none where RECOVERY_INDEPENDENT        MAR22.12
scaled    = JTD · min( max( M_years, 0.25 ), 1 )    cash equity: 0.25 or 1, the bank's choice                MAR22.15 to MAR22.18
net per obligor: a short offsets a long of the same obligor when the short's seniority is the same or lower   MAR22.19 to MAR22.21
HBR_b     = Σ net long / ( Σ net long + Σ |net short| )                                                      MAR22.23 (unweighted)
DRC_b     = max( Σ RW · net long − HBR_b · Σ RW · |net short|, 0 )                                           MAR22.25
DRC       = Σ_b DRC_b      no hedging across buckets                                                          MAR22.26
RW: AAA 0.5 %, AA 2 %, A 3 %, BBB 6 %, BB 15 %, B 30 %, CCC 50 %, unrated 15 %, defaulted 100 %              MAR22.24
```

### 6.7 Residual risk add-on (MAR23)

`RRAO = Σ gross notional × 1.0 %` (exotic underlying) `+ Σ gross notional × 0.1 %` (other residual risks), after the exclusions: a
back-to-back trade and its exact third-party match entirely; a listed or centrally clearable instrument from the other-residual part only.
Fully additive. **Total FRTB-SA = SBM + DRC + RRAO.**

### 6.8 Contributions

Within a scenario the SBM is homogeneous of degree one in the weighted sensitivities, so a part's contribution is
`Σ_k WS_k(part) · ∂SBM/∂WS_k` (the gradient at the scope, in the binding scenario), and the parts' contributions add up to the charge.
At a kink (a floor at zero, a curvature side switch, the alternative specification) the gradient is one-sided: the answer says
*contributions scaled to add up* and scales them, as VaR's component VaR. DRC contributions follow each obligor's share of its bucket's
charge; RRAO is additive.

### 6.9 The method choices (switches)

Every choice the method makes is a named switch with its decided default
([FRTB_SA_GUIDE.md section 8.1](../guides/FRTB_SA_GUIDE.md#81-critique-the-choices-panel)):

| Switch | Default (decided) | Alternatives | Rule |
|---|---|---|---|
| `curvature-source` | revaluation where delivered | gamma estimate; both side by side | MAR21.5, MAR21.17 |
| `girr-curvature-shock` | 1.7 % | 1.7 % / √2 where the √2 reduction is used | MAR21.99 |
| `fx-curvature-divisor` | ÷ 1.5 for FX options not referencing the reporting currency | off; every FX instrument (with approval) | MAR21.98 |
| `girr-correlation` | the published table (0.1 %) | the exact formula | MAR21.46 |
| `scenario` | the binding one | high, medium, low, each shown | MAR21.6 |
| `overrides` | none | any risk weight, correlation or shock typed by a validator | not a regulatory figure |

A figure computed with any non-default switch or any override carries *not the regulatory figure* and can never be captured officially.

## 7. What Drishti builds

### 7.1 The factor dictionary (shared)

The dictionary of the date in memory: every `RISK_FACTOR` → an int **factor ordinal**, with its attributes as int codes (class, FRTB
bucket, SIMM bucket and labels, tenor in years, underlying tenor, unit, curvature parent: the curve, issuer, commodity or spot a tenor
factor belongs to) and a hash map from every source name to its ordinal. Shared by FRTB-SA, SIMM, sensitivity-based stress and P&L
explain. About 100,000 to 250,000 factors in a large bank (30 currencies × 3 curves × 10 vertices; 5,000 issuers × 5 vertices × 2 curves;
5,000 equity names with repo and 5 vega expiries; 200 commodities × 11 vertices; their volatility grids): 20 to 50 MB with the names.

### 7.2 The sensitivity store (shared)

Built at load, indexed like the VaR vectors (position ordinal, then factor ordinal):

| Structure | Content | Size at 2.3 M positions × 20 elements (estimate) |
|---|---|---|
| Elements by position | compressed sparse rows over the date's position ordinals (VaR's order): `offsets[position]` int64; `factor[element]` int32 (the measure folded into the ordinal); `value[element]` float64, already in the reporting currency and converted per section 3.2.3 | 46 M × 12 B + 18 MB = **570 MB** |
| Record attributes | per position: source, batch, status, desk, book, legal entity, FRTB desk, product type, maturity (int codes) | about 40 B a position: 90 MB |
| Curvature pairs | per position and curvature factor: up, down and the delta term `RW · s`, so CVR± of any scope is a sum | inside the elements (three measures of the curvature factor) |
| DRC rows | per position and obligor: scaled JTD long and short, seniority, bucket, quality | about 40 B a row |
| RRAO rows | per position: class (exotic, other, none) and gross notional in the reporting currency | 16 B a position |
| Refusal counters | per source, refusal class and desk | small |

**A scope's netted sensitivities** are accumulated into a dense float64 array indexed by factor ordinal (2 MB at 250,000 factors; one per
thread), in position-ordinal order, then compacted to the non-zero factors and grouped by class and bucket. In a cluster, each owner sums
its shards and the coordinator adds the partial sums in shard order, then applies the formula once
([DATA_ARCHITECTURE.md section 13](../architecture/DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built)).

### 7.3 Load

At the date's first request or the lake's load signal: read the promoted columns and the `SENSITIVITIES` list, resolve each element's
name through the dictionary (refusals counted per source and class, section 10), convert, and place it under the position's ordinal;
apply intraday and restated records by `COMPUTED_AT`; build the record attributes and the DRC and RRAO rows. Load time at 2.3 M
positions: about 30 to 60 s from Parquet, two to four times that from JSON Lines (estimates, to be measured).

### 7.4 The parameters in memory

Each parameter file as typed tables (risk weight per class, bucket and vertex; the ρ structure of each bucket as its factors, never as a
dense matrix: name × tenor × basis for CSR and commodity, a constant per equity bucket; γ per bucket pair), the three scenario versions
precomputed, and the file's SHA-256.

## 8. Scopes

Every VaR scope ([VAR_GUIDE.md section 5](../guides/VAR_GUIDE.md#5-scopes-portfolios-and-baskets)): a hierarchy node (desk, legal entity,
limit letter, FRTB desk), a portfolio, a basket, an ad-hoc set of positions. Positions' sensitivities to the same factor are added
first; everything after the weighting is computed for the scope as a whole. **Non-additive:** the MR cube shows each cell's own charge
and the diversification row `Σ cells − total`, as for VaR. **Filers:** the official capture is per filer (a legal entity that files)
and date; each FRTB desk is also computed standalone for the desk-level view (MAR21.7(2)(b)).

## 9. What Drishti answers

### 9.1 The headline

| Field | Meaning |
|---|---|
| `total` | SBM + DRC + RRAO (those computed) |
| `sbm`, `drc`, `rrao` | each part; null when not computed, with the reason |
| `bindingScenario`, `sbmByScenario` | `low`, `medium` or `high`; the three totals |
| `byClass` | per risk class and measure: delta, vega, curvature in each scenario |
| `curvatureEstimated` | the classes whose curvature came from gamma (an analysis figure) |
| `switches`, `overrides`, `regulatory` | the switches used; any override; false when either is not the default |
| `provenance` | the batches and table versions of every input, the dictionary version, the parameter files with version and SHA-256, refusals and proxies counted, missing positions |

### 9.2 The computed kind `frtb-sa-result`

One record per scope and business date (class `computed`; design). Every field and a worked record:
[section 25](#25-computed-records-every-field). It holds the headline above, and the
explanation at every level an auditor walks:

| Level | Per | Figures |
|---|---|---|
| Class | risk class × measure × scenario | the charge; whether the alternative specification applied |
| Bucket | class × measure × scenario × bucket | K_b, S_b (and the capped S_b when used), the factor count; for curvature K_b+, K_b−, the side chosen |
| Factor | class × measure × bucket × factor | the netted s_k, RW_k, WS_k, the gradient ∂SBM/∂WS_k, the contribution; for curvature CVR+ and CVR− |
| DRC | bucket × obligor | gross JTD long and short, scaled, net, RW; HBR and the bucket charge |
| RRAO | class × position | gross notional, weight, add-on, the exclusion applied |

### 9.3 Contributions

`by` = child node, desk, book, position or trade: each part's standalone SBM, its Euler contribution in the binding scenario, its share,
and (for the first 50 parts) its incremental figure `SBM(scope) − SBM(scope − part)`.

### 9.4 CSV and the API

The API returns JSON as the caller; the CSV parts are `factors`
(`risk_class,measure,bucket,risk_factor,s,rw,ws,gradient,contribution`), `buckets`, `classes`, `contributions`, `drc` and `rrao`.

### 9.5 The reactive connectors (proposed)

The engine runs as VaR's do: a Java connector serves a scope's netted sensitivities (the sparse vector of section 7.2), and the FRTB-SA
formula reads it with the parameter files; a what-if adds a sandbox's elements to the vector before the formula. The connector names,
kinds and the split between Java and Python are fixed at build; the inputs, outputs and determinism rules of this document hold whichever
runs the formula.

### 9.6 Official captures

The official run captures FRTB-SA **per filer and business date** as VaR's official results are captured
([VAR_GUIDE.md section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results)): the figures of section 9.1 and the class, bucket and
factor tables, the batches and table versions of every input, the parameter file with its version and SHA-256, the dictionary version,
the switches and the engine version. **An official capture uses the default switches only:** an override never reaches an official
record, and a curvature estimated from gamma is captured only where the bank has recorded the supervisor's acceptance for that source.
A restatement after the capture makes a new capture version; the first stays.

### 9.7 The playground: recipes and the workbook

`PLAY FRTB <scope>` opens the scope in the VaR Playground's frame
([FRTB_SA_GUIDE.md section 8](../guides/FRTB_SA_GUIDE.md#8-the-frtb-sa-playground-play-frtb)): up to a configurable number of
factors (default 2,000; beyond it the export states its size and offers one class at a time).

**A recipe** makes every playground run reproducible ([FRTB_SA_GUIDE.md section 8.2](../guides/FRTB_SA_GUIDE.md#82-reproduce-recipes); every field: [section 25.3](#253-playground-recipe)):

| Part | Holds |
|---|---|
| `RECIPE_ID`, `CREATED_BY`, `CREATED_AT` | the id (`RCP-FRTB-…`), who saved it, when |
| `SCOPE`, `BUSINESS_DATE`, `FILER` | what was computed |
| `INPUTS[]` | per input table (`position-sensitivity`, `risk-factor`, `default-risk`, `residual-risk`, FX, the hierarchy): the table format (Delta or Iceberg), its version, the batch ids used |
| `PARAMETERS[]` | per parameter file: its name, regime, version and SHA-256 |
| `SWITCHES` | every switch of section 6.9 with its value |
| `OVERRIDES[]` | each override: the parameter's path, the value, who typed it and why |
| `ENGINE_VERSION` | the engine's version |
| `RESULT_DIGEST` | the SHA-256 of the figures, so a replay proves byte equality |

A recipe is saved with one click and on every export; `PLAY FRTB RUN <id>`, its link, or `drishti.py play frtb --recipe <id>` computes
it again **to the cent**, months later, because it reads the recorded versions, not the newest data.

**The workbook** (Excel, real formulas, check cells against the engine):

| Tab | What it shows | Typed or formula |
|---|---|---|
| Main | scope, date, parameter file and version; SBM per class × measure × scenario, the binding scenario, DRC, RRAO, the total; each beside the engine's figure with a check cell, for the default and for every alternative the export was made with | formulas; the engine's figures typed |
| Config | the parameters used, with their MAR paragraphs; every switch as a cell with a drop-down that the formula tabs read (`IF`, `CHOOSE`), so a validator flips a switch and sees the alternative recompute in Excel | typed |
| Data | the scope's elements as delivered (position, factor, measure, value, currency, batch) and the FX | values |
| WS | per factor: the netted sensitivity (`SUMIFS` over Data), the conversion, RW (`INDEX`/`MATCH` into Config), WS | formulas |
| Buckets | per class and bucket: the ρ of its factors, K_b with `SUMPRODUCT` and `MMULT`, S_b, the alternative specification | formulas |
| Classes | per class: γ, delta, vega and curvature per scenario | formulas |
| Curvature | per factor: up, down, the delta term, CVR+ and CVR−; per bucket K+, K−, the side in each scenario | formulas |
| DRC | obligors, JTD long and short, maturity scaling, netting, HBR, bucket charges | formulas on typed positions |
| RRAO | instruments, class, notional × weight | formulas |
| Explain | the method in plain words with the cells of each step | text |
| Provenance | the recipe: input versions and batches, refusals, the parameter file's SHA-256, switches, overrides, engine version, who exported it | values |

## 10. Validation, errors and data quality

[README.md section 10](README.md#10-validation-and-rejects) holds. The checks of the sensitivity load, with what is refused and the words
shown on the data-trust card and in the load report (codes proposed, range `DRS-532x`; `<…>` filled in):

| Check | Refused | Words |
|---|---|---|
| The factor name is in the dictionary, or a source name maps to exactly one factor | the element | `DRS-5321` *<source>: risk factor <name> is not in the factor dictionary; the element is refused* |
| A source name listed under two factors | the dictionary of the date | `DRS-5322` *source name <name> maps to <factor A> and <factor B>; the factor dictionary of <date> is refused* |
| `VALUE` finite | the element | `DRS-5323` *<SENSITIVITY_ID>: <factor> <measure> is not a finite number; the element is refused* |
| The element's `CURRENCY` has an FX rate on the date | the element | `DRS-5324` *no <ccy> rate on <date>; <n> elements of <source> are refused* |
| The same factor and measure twice in a record | the record | `DRS-5325` *<SENSITIVITY_ID> carries <factor> <measure> twice; the record is refused* |
| One full run per source and date | the second run | `DRS-5326` *<source> already delivered the full run of <date> (<batch>); <batch> is refused: send a restatement* |
| Required fields, enums, the key pattern | the record | planned: *<SENSITIVITY_ID>: <field> <is missing or has value v>; the record is refused* |
| One grain per source and date | the records of the second grain | planned: *<source> sent both POSITION and TRADE records for <date>; the TRADE records are refused* |
| A parameter file complete and in range | the file (the engine does not answer from it) | `DRS-5329` *<file>: <key> is <missing or v, outside min to max>; the parameter file is refused* |

**Warnings** (the data is used; the warning is counted and shown):

| Check | Words |
|---|---|
| A curvature element without `SHOCK_SIZE`, or a shock other than the bucket's risk weight | *<factor>: curvature shocked by <x>, the bucket's risk weight is <y>* |
| A delivered bucket or class that differs from the dictionary's | *<factor>: delivered bucket <a>, dictionary bucket <b>; the dictionary's is used* |
| Completeness by desk | *<desk>: <n> positions have VaR vectors and no sensitivities; <m> have sensitivities and no vectors* |
| Proxied records | *<n> records of <source> are proxied (<share> of the desk's sum)* |
| Curvature from gamma | *curvature estimated from gamma: an analysis figure until the supervisor accepts it* |
| Classes without parameters (securitisations, CTP) | *<class>: <n> sensitivities received; not computed* |
| Consistency with VaR (a sample of positions: the first-order P&L Σ s_k · Δx_k,j against the scenario P&L) | *<desk>: sensitivities and P&L vectors correlate at <r>* (shown; a low figure points at different runs or trades) |

**Answers that are not errors:** *DRC not computed: no default-risk inputs for <scope> on <date>*; *not the regulatory figure:
overrides <list>* on every figure an override touches. **Request errors:** a bad scope, scenario, class or `by` answers `400 DRS-5001`;
a refused scope or engine answers `403 DRS-5002`.

## 11. Entitlements and audit

VaR's model ([VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)) with the gate kind
`position-sensitivity`. A role limited to aggregates sees the headline, classes and buckets of its nodes, never a factor table filtered
to one position, position contributions, the Data tab of a playground export, or a CSV below its nodes. **Audited:** every `FRSA` view of
a node or of more than 10,000 positions, every export (CSV and workbook), every recipe saved and replayed, every override typed (who,
what, why), every official capture.

## 12. Performance and sizing

Estimates, to be measured by the engine's benchmark. Elements: 20 a record; storage: 16 B an element in gzip JSON Lines (the demo's
figure), 12 B in memory.

| Shape | Elements a date | Lake a date (gzip JSON Lines) | Memory a date (store + attributes) | Seven years in the lake |
|---|---|---|---|---|
| 300,000 trades (trade grain) | 6 M | about 100 MB | about 90 MB + 12 MB | about 180 GB |
| 2.3 M positions (the VaR benchmark's shape) | 46 M | about 750 MB | about 570 MB + 90 MB | about 1.3 TB |

| Operation | Target, warm, 2.3 M positions (estimate) |
|---|---|
| Netting a desk's sensitivities | under 100 ms (one pass over its elements) |
| The bank's SBM in three scenarios | about 2 s on one server (the netting dominates; about 1,000 factors a class, so the bucket sums are small) |
| Explain of one class | under 500 ms |
| Contributions of 5,000 parts | under 1 s (one sparse dot product a part) |
| Playground export of 2,000 factors | a few seconds |
| Load of a date | 30 to 60 s from Parquet |

## 13. Test data and generators

`tools/packgen/sensitivity/make_sensitivity_data.py` writes `data/testdata/market-risk/position-sensitivity/` and
`data/testdata/market-risk/risk-factor/` for 2026-09-30 from the committed VaR inputs: the 7,984 positions the pack's rules derive, one
record a position from Murex and Calypso and one a trade from Endur; **8,052 records and 25,821 elements** (7,730 `DELTA`, 7,150 `DV01`,
3,221 `VEGA`, 2,692 `CS01`, 2,296 each `CURVATURE_UP` and `CURVATURE_DOWN`, 436 `IE01`); 88 records `proxied`. The dictionary
(`factor_dictionary.py`) has **855 factors**: ten rate curves at ten vertices with their swaption volatilities, three inflation indices,
36 issuers and three credit indices on bond and CDS curves, nine currencies, twelve equity underlyings with repo and volatility, ten
commodities at six forward points, each with three source names. The loadings and one-sigma P&L of each position are the VaR demo's own
factor model, so a desk's sensitivities and its VaR tell the same story. Deterministic; checked by
`tools/packgen/sensitivity/test_sensitivity_data.py`. Both kinds are listed in `data/testdata/testdata.yaml` and `lake demo` loads them
into the risk store. No default-risk or residual-risk demo data exists.

## 14. Retiring the stored version

The stored kind `frtb-sensitivity` (one record per book with `DELTA_CHARGE`, `VEGA_CHARGE`, `CURVATURE_CHARGE`, `TOTAL`, per-bucket
`SENSITIVITIES[]` and an equal `BY_CLASS` split) holds figures made without risk weights or correlations; no factor relates them to
FRTB-SA. It stays active, unchanged, until the computed kind `frtb-sa-result` replaces it; then:

| Old | New |
|---|---|
| `FRTB-<desk>-<n>` (the book `BOOK-<desk>-<n>`) | `FRSA BOOK:BOOK-<desk>-<n>` |
| `/v/frtb-sensitivity/<id>` | the same, by redirect |
| The kind, its samples, test data, Sutra, Calc snippets and contract | removed; a guard test fails when `frtb-sensitivity` returns or an input table carries a charge (`DELTA_CHARGE`, `SBM`, `DRC`) |

The engine is validated against the hand-built cases of section 15 and against the bank's own figures when it supplies a parallel run.

## 15. A worked example

The hand-built cases 1 to 5 ([FRTB_SA_GUIDE.md section 10](../guides/FRTB_SA_GUIDE.md#10-the-hand-built-cases)), small
enough for a calculator. They are also the engine's unit tests, the playground's check cells and the numbers of the Explain tab.

**GIRR delta (cases 1 and 2).** USD is a specified currency, so `RW = 1.1 % / √2 = 0.777817 %` at 5Y and 10Y and `1.3 % / √2` at 2Y.

| Element delivered | s (× 10,000) | WS |
|---|---|---|
| `IR/CURVE/USD/SOFR/5Y` `DV01` −1,000 | −10,000,000 | −77,781.75 |
| `IR/CURVE/USD/SOFR/10Y` `DV01` −500 | −5,000,000 | −38,890.87 |
| `IR/CURVE/EUR/ESTR/2Y` `DV01` +800 | +8,000,000 | +73,539.11 |

ρ(5Y, 10Y) = max(exp(−0.03 · 5/5), 0.40) = 0.970446. K_USD is 115,129.89 (low), 115,903.82 (medium), 116,672.62 (high);
K_EUR = 73,539.11; γ = 50 % (low 37.5 %, high 62.5 %). **Δ_GIRR = 110,579.79 (low), 101,300.03 (medium), 91,079.64 (high):** the hedge
across currencies makes the low scenario bind.

**Curvature (case 3).** One equity index, bucket 12, RW 15 %: `DELTA` 10,000 (so RW · s = 0.15 × 10,000 × 100 = 150,000),
`CURVATURE_UP` 120,000, `CURVATURE_DOWN` −180,000 with `SHOCK_SIZE` 0.15. CVR+ = −(120,000 − 150,000) = 30,000; CVR− = −(−180,000 +
150,000) = 30,000; the tie goes down (Σ CVR+ is not greater than Σ CVR−). **Curvature = 30,000** in every scenario.
Each case isolates one step: the index's own delta charge (WS = 15 % × 1,000,000 = 150,000, a one-factor bucket) is real but is not
part of the cases' total below.

**SBM:** low 110,579.79 + 30,000 = **140,579.79** (binding); medium 131,300.03; high 121,079.64.

**DRC (case 4).** A long senior BBB bond, notional 10,000,000, market value 9,800,000, 2 years: JTD = 0.75 · 10,000,000 − 200,000 =
7,300,000, scaled × 1. A bought CDS on the same obligor, notional −4,000,000, P&L +50,000, 6 months: (0.75 · −4,000,000 + 50,000) · 0.5 =
−1,475,000. Net long 5,825,000. A second obligor (BB) net short −2,000,000. HBR = 5,825,000 / 7,825,000 = 0.744409.
**DRC = max(6 % · 5,825,000 − 0.744409 · 15 % · 2,000,000, 0) = 126,177.32.**

**RRAO (case 5).** An FX barrier option, gross notional 50,000,000, other residual risk: 50,000; a weather derivative, 10,000,000,
exotic: 100,000. **RRAO = 150,000.**

**Total FRTB-SA = 140,579.79 + 126,177.32 + 150,000 = 416,757.11.**

**The GIRR correlation switch.** The cases compute ρ(5Y, 10Y) by the exact formula (0.970446), the alternative of the `girr-correlation`
switch. With the default, the published MAR21.46 table's 97.0 % (low 94.0 %), K_USD is 115,106.47 (low), 115,892.19 (medium), 116,672.62
(high), Δ_GIRR 110,555.42, 101,286.72 and 91,079.64, the SBM 140,555.42 (low, binding) and the total **416,732.74**: the difference of
24.37 is the kind of figure the playground's compare view explains (section 6.9).

The input records of this example, as an upstream team would send them, are in
[section 26](#26-worked-json-lines-examples).

## 16. Open questions and decisions

**Decided** (product owner; the list with reasons: [FRTB_SA_GUIDE.md section 15](../guides/FRTB_SA_GUIDE.md#15-decisions-and-the-screens-chosen)):
curvature from gamma accepted and labelled an estimate, an analysis figure until the supervisor accepts it (2, 7); rates vega in
lognormal points by default, normal declared per source (3); Basel MAR21 to MAR23 first, then OSFI CAR chapter 9 (4); credit indices one
SIMM qualifier (5); FX curvature ÷ 1.5 on by default for options not referencing the reporting currency, every FX instrument only with
approval (6, 9); the GIRR curvature shock 1.7 % undivided (8); the published MAR21.46 table for GIRR correlations (10); SIMM parameters
shipped empty (11).

**Open:**

| # | Question | Context |
|---|---|---|
| 1 | The bank's due time for the full run and the intraday batches | section 3.6 |
| 2 | The default-risk and residual-risk inputs: delivered as the drafts of sections 3.3 and 3.4 (then each gets a YAML contract), or always derived from the trade and issuer masters | sections 3.3, 3.4 |
| 3 | The GIRR curvature factor is a **currency** (MAR21.8: every curve of the currency shifted together), while the naming standard names a curvature element per curve (`IR/CURVE/<ccy>/<curve>`); the same question for a commodity traded at several locations | section 3.2.4 |
| 4 | Cross-currency basis (GIRR, MAR21.8) has no name in the naming standard yet | section 3.2.4 |
| 5 | SIMM-only vertices (2W, 1M) and FRTB-only vertices: one dictionary row per factor with both mappings, or a factor at a SIMM-only vertex with no FRTB vertex | [SIMM.md](SIMM.md) |
| 6 | CSR securitisations and the correlation trading portfolio: parameters and DRC tables, now or later | section 1 |
| 7 | The reporting currency per filer, when not USD | section 5 |

## 17. How to read the field tables

Sections 18 to 27 are the reference an upstream team builds its feed from and an auditor checks a record against. Where a YAML contract
exists it is the machine-readable truth and its generated page lists the same fields
([packs/market-risk.md](packs/market-risk.md#sens-position-sensitivities-position-sensitivity)); these sections add the meaning, the units
and the rules that tie the fields together.

- **Types** are those of [README.md section 5](README.md#5-types-names-and-encodings): `id`, `text`, `date` (YYYY-MM-DD), `timestamp`
  (ISO 8601, UTC), `integer`, `amount` (a signed decimal in a currency), `rate` (a decimal), `boolean`, `record list`, `text list`, `id list`.
- **Required** means the record is refused without it; **optional** may be absent or null; **conditional** names its condition.
- **Files** are JSON Lines: UTF-8, one record per line, gzip allowed (`.jsonl.gz`); field names in UPPER_SNAKE as written here.
  Unknown fields are ignored (an additive change, [README.md section 13](README.md#13-versioning-a-contract)).
- **Signs:** every sensitivity is the position's gain for the stated up-move. **Amounts** are in the currency named beside them.

## 18. The factor naming standard

One text name per risk factor, slash-separated, the same in every system Drishti reads. A name without its last part (the tenor) names
the curve, issuer, commodity curve or spot as a whole: the curvature factor.

| Pattern | Factor | Example |
|---|---|---|
| `IR/CURVE/<ccy>/<curve>/<tenor>` | a vertex of an interest-rate curve | `IR/CURVE/USD/SOFR/10Y` |
| `IR/CURVE/<ccy>/<curve>` | the curve as a whole (curvature, gamma) | `IR/CURVE/USD/SOFR` |
| `INF/<index>/<tenor>` | an inflation curve vertex (the currency is the dictionary's `QUALIFIER`) | `INF/USCPI/10Y` |
| `CR/SPREAD/<issuer>/<BOND or CDS>/<tenor>` | a credit spread vertex | `CR/SPREAD/ISS-FIN-IG-01/CDS/5Y` |
| `CR/SPREAD/<issuer or index>` | the issuer or index as a whole (curvature) | `CR/SPREAD/CDX.NA.HY` |
| `FX/SPOT/<ccy>` | the currency's value in the reporting currency | `FX/SPOT/EUR` |
| `EQ/SPOT/<underlying>` | an equity price or index level | `EQ/SPOT/SPX` |
| `EQ/REPO/<name>` | an equity repo rate | `EQ/REPO/SPX` |
| `CM/PRICE/<commodity>/<location>/<tenor>` | a commodity forward | `CM/PRICE/WTI/CUSHING/1Y` |
| `CM/PRICE/<commodity>/<location>` | the commodity curve as a whole (curvature) | `CM/PRICE/BRENT/ICE` |
| `VOL/IR/<ccy>/<curve>/<expiry>/<underlying tenor>` | a swaption or cap volatility | `VOL/IR/USD/SOFR/1Y/10Y` |
| `VOL/<CR, EQ, CM or FX>/<underlying>/<expiry>` | an implied volatility | `VOL/EQ/SPX/1Y` |

**Parts.** `<ccy>` ISO 4217; `<curve>` the bank's curve code (`SOFR`, `ESTR`, `SONIA`, `TONA`, `SARON`, `AONIA` …); `<issuer>` an LEI in
production (the demo uses synthetic ids such as `ISS-FIN-IG-01`); `<underlying>` an ISIN or index code; `<commodity>` and `<location>` the
bank's commodity and delivery-point codes. **Tenors** are codes: `0D` (commodity spot), `2W`, `1M`, `3M`, `6M`, `1Y`, `2Y`, `3Y`, `5Y`,
`10Y`, `15Y`, `20Y`, `30Y`. A code maps to years for the correlations (`3M` 0.25, `6M` 0.5, `2W` 14/365, `1M` 1/12).

**The bank's own names** are listed per factor in the dictionary's `SOURCE_NAMES` (the demo: `MX:IR:CURVE-USD-SOFR-10Y` for Murex,
`IR.CURVE.USD.SOFR.10Y` for Calypso, `10Y_SOFR_USD_CURVE_IR` for Endur). A source may deliver either the standard name or one of its own;
a name in no list is refused.

## 19. The bucket grids

A **grid** maps each factor to one bucket. The FRTB grids are those of MAR21; the SIMM grid is that of the SIMM version
([SIMM.md section 6](SIMM.md#6-the-computation)); a bank may add its own (for example a DV01 ladder 0–2Y, 2–5Y, 5–10Y, 10Y+ per currency
for sensitivity limits). The values below are the Basel figures in the parameter file
[`frtb-sa-sbm.yaml`](../../config/packs/banking/market-risk/capital/frtb-sa-sbm.yaml); the file, not this page, is what the engine reads.

### 19.1 GIRR (MAR21.39 to MAR21.50)

Each currency is a bucket. Delta risk weights by vertex:

| Vertex | 3M | 6M | 1Y | 2Y | 3Y | 5Y | 10Y | 15Y | 20Y | 30Y | inflation | cross-currency basis |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RW | 1.7 % | 1.7 % | 1.6 % | 1.3 % | 1.2 % | 1.1 % | 1.1 % | 1.1 % | 1.1 % | 1.1 % | 1.6 % | 1.6 % |

Divided by √2 for EUR, USD, GBP, AUD, JPY, SEK, CAD and the reporting currency when the bank chooses it (MAR21.44). Within a currency:
the published table of MAR21.46 (by default) or `max(exp(−3 % · |T_k − T_l| / min(T_k, T_l)), 40 %)`; × 99.9 % between two curves at
the same vertex; inflation to a curve 40 %; basis to anything 0 %. Across currencies γ = 50 %.

### 19.2 CSR non-securitisation (MAR21.51 to MAR21.57)

| Bucket | Credit quality | Sector | RW |
|---|---|---|---|
| 1 | IG | sovereigns including central banks, multilateral development banks | 0.5 % |
| 2 | IG | local governments, government-backed non-financials, education, public administration | 1.0 % |
| 3 | IG | financials including government-backed financials | 5.0 % |
| 4 | IG | basic materials, energy, industrials, agriculture, manufacturing, mining and quarrying | 3.0 % |
| 5 | IG | consumer goods and services, transportation and storage, administrative and support services | 3.0 % |
| 6 | IG | technology, telecommunications | 2.0 % |
| 7 | IG | health care, utilities, professional and technical activities | 1.5 % |
| 8 | IG | covered bonds | 2.5 % |
| 9 to 15 | HY and not rated | the sectors of 1 to 7 | 2.0 %, 4.0 %, 12.0 %, 7.0 %, 8.5 %, 5.5 %, 5.0 % |
| 16 | any | other sector | 12.0 % |
| 17 | IG | indices | 1.5 % |
| 18 | HY | indices | 5.0 % |

Vertices 6M, 1Y, 3Y, 5Y, 10Y. Within a bucket ρ = ρ_name × ρ_tenor × ρ_basis: 35 % between names (80 % in buckets 17 and 18), 65 %
between tenors, 99.9 % between the bond and the CDS curve; bucket 16 adds absolute values. Across buckets γ = γ_rating × γ_sector:
γ_rating 50 % between an IG and a HY bucket of 1 to 15; γ_sector from MAR21.57's table; 0 % with bucket 16.

### 19.3 Equity (MAR21.71 to MAR21.80)

| Bucket | Size | Economy | Sector | Spot RW | Repo RW | ρ within |
|---|---|---|---|---|---|---|
| 1 | large | emerging | consumer goods and services, transportation and storage, administrative and support services, health care, utilities | 55 % | 0.55 % | 15 % |
| 2 | large | emerging | telecommunications, industrials | 60 % | 0.60 % | 15 % |
| 3 | large | emerging | basic materials, energy, agriculture, manufacturing, mining and quarrying | 45 % | 0.45 % | 15 % |
| 4 | large | emerging | financials including government-backed financials, real estate, technology | 55 % | 0.55 % | 15 % |
| 5 to 8 | large | advanced | as 1 to 4 | 30 %, 35 %, 40 %, 50 % | 0.30 % to 0.50 % | 25 % |
| 9 | small | emerging | all sectors of 1 to 4 | 70 % | 0.70 % | 7.5 % |
| 10 | small | advanced | all sectors of 5 to 8 | 50 % | 0.50 % | 12.5 % |
| 11 | | | other sector | 70 % | 0.70 % | absolute sum |
| 12 | large | advanced | indices (not sector-specific) | 15 % | 0.15 % | 80 % |
| 13 | | | other indices | 25 % | 0.25 % | 80 % |

Spot to repo of one name 99.9 %; of two names the spot ρ × 99.9 %. Across buckets 15 %; 0 % with bucket 11; 75 % between 12 and 13; 45 %
between 12 or 13 and 1 to 10.

### 19.4 Commodity (MAR21.81 to MAR21.85)

| Bucket | Group | RW | ρ_commodity |
|---|---|---|---|
| 1 | energy: solid combustibles | 30 % | 55 % |
| 2 | energy: liquid combustibles | 35 % | 95 % |
| 3 | energy: electricity and carbon trading | 60 % | 40 % |
| 4 | freight | 80 % | 80 % |
| 5 | metals: non-precious | 40 % | 60 % |
| 6 | gaseous combustibles | 45 % | 65 % |
| 7 | precious metals, including gold | 20 % | 55 % |
| 8 | grains and oilseed | 35 % | 45 % |
| 9 | livestock and dairy | 25 % | 15 % |
| 10 | softs and other agriculturals | 35 % | 40 % |
| 11 | other commodity | 50 % | 15 % |

ρ = ρ_commodity × ρ_tenor (99 %) × ρ_basis (99.9 % between locations). Across buckets 20 %; 0 % with bucket 11.

### 19.5 FX (MAR21.86 to MAR21.89)

Each currency against the reporting currency is a bucket; RW 15 % (÷ √2 for the specified pairs and their first-order crosses, MAR21.88,
listed in the bank's copy of the file); γ = 60 %.

### 19.6 Vega risk weights (MAR21.92)

`min(55 % · sqrt(LH / 10), 100 %)` with the liquidity horizon of the class: GIRR 60 days (100 %), CSR 120 (100 %), commodity 120 (100 %),
FX 40 (100 %), equity buckets 1 to 8, 12 and 13: 20 (77.78 %), equity buckets 9 to 11: 60 (100 %).

## 20. position-sensitivity (SENS): every field

Contract: [`position-sensitivity.contract.yaml`](../../config/packs/banking/market-risk/contracts/position-sensitivity.contract.yaml). The record:

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `SENSITIVITY_ID` | id | required | `SENS-<POSITION_ID or TRADE_ID>-<BATCH_ID>`, unique per business date | `SENS-POS-BOOK-RATES-1~MX-20000002-B-SENS-2026-09-30-01` |
| `GRAIN` | text | required | `POSITION` or `TRADE`; one grain per source and date | `POSITION` |
| `POSITION_ID` | id | conditional: required for `POSITION` | `POS-<TT_BOOK>~<key>` as the pack's position rules derive it; on a `TRADE` record the trade's position when known | `POS-BOOK-RATES-1~MX-20000002` |
| `TRADE_ID` | id | conditional: required for `TRADE` | the trade (links to `trade`) | `MX-20000002` |
| `TRADE_IDS` | id list | optional | the trades a position record covers | `["MX-20000002"]` |
| `SOURCE_SYSTEM` | text | required | the system that computed the record | `MUREX` |
| `BUSINESS_DATE` | date | required | the date the sensitivities are for; the partition | `2026-09-30` |
| `BATCH_ID` | id | required | the run; one full run per source and date | `B-SENS-2026-09-30-01` |
| `RUN_KIND` | text | required | `FULL` or `INTRADAY` | `FULL` |
| `COMPUTED_AT` | timestamp | required | the as-of of the record; the latest wins | `2026-09-30T06:10:00Z` |
| `RESTATEMENT` | integer | required | 0 first delivery; 1, 2 … each restatement | `0` |
| `STATUS` | text | required | `ok`, `proxied` (with `PROXY_REASON`), `failed` (`SENSITIVITIES` null) | `ok` |
| `PROXY_REASON` | text | conditional: on `proxied` | the missing curve or surface and its proxy | `volatility surface missing on the date: the underlying's index surface used` |
| `PRICER`, `MODEL_VERSION` | text | optional | the pricing library and model version | `demo-factor-model`, `demo-sens-1` |
| `LEGAL_ENTITY` | id | required | the booking legal entity | `LE-NY` |
| `DIVISION`, `DESK` | id | required | the management hierarchy | `DIV-MARKETS`, `DESK-RATES` |
| `TT_BOOK` | id | required | the trading book | `BOOK-RATES-1` |
| `LIMIT_LETTER` | id | optional | the limit letter of authority | `LL-0001` |
| `FRTB_DESK` | id | optional | the regulatory trading desk, when it differs from the management desk | `FD-USD-RATES` |
| `PRODUCT_GROUP`, `PRODUCT_TYPE` | text | required | the product; `PRODUCT_TYPE` decides the RRAO class and the SIMM product class mapping | `Rates`, `IRS_FIXFLOAT` |
| `TRADE_STATUS` | text | required | the trades' status; `CANCELLED` carries an empty list | `NEW` |
| `MATURITY_DATE` | date | required | the position's last maturity | `2031-04-15` |
| `CURRENCY` | text | required | the position's own currency | `EUR` |
| `SENSITIVITIES` | record list | required | the elements below; empty only when cancelled, null when failed | |

Each element of `SENSITIVITIES[]`:

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `RISK_FACTOR` | id | required | the factor's standard name or a source name in the dictionary ([section 18](#18-the-factor-naming-standard)) | `IR/CURVE/USD/SOFR/10Y` |
| `MEASURE` | text | required | `DV01`, `CS01`, `IE01`, `DELTA`, `VEGA`, `CURVATURE_UP`, `CURVATURE_DOWN`, `GAMMA`; `BETA` and `BASE_CORR` reserved for SIMM | `DV01` |
| `VALUE` | amount | required | the change in value for the measure's move, in `CURRENCY`, gain positive; finite | `-19565.62` |
| `CURRENCY` | text | required | the currency of `VALUE` | `USD` |
| `RISK_CLASS` | text | optional | `GIRR`, `CSR_NS`, `CSR_SEC_NCTP`, `CSR_SEC_CTP`, `EQ`, `COMM`, `FX`; the dictionary's wins | `GIRR` |
| `FRTB_BUCKET` | text | optional | the source's FRTB bucket; the dictionary's wins | `USD` |
| `TENOR` | text | optional | the vertex or the option expiry; null on a curvature or spot factor | `10Y` |
| `SIMM_BUCKET` | text | optional | the source's SIMM bucket; the dictionary's wins | `1` |
| `SHOCK_SIZE` | rate | conditional: on `CURVATURE_UP` and `CURVATURE_DOWN` | bp for rates and spreads; a fraction for spot and price | `170` |
| `IMPLIED_VOL` | rate | conditional: on `VEGA` | the implied volatility, a fraction | `0.28` |

Units of `VALUE` by measure: [section 3.2.2](#322-units-and-signs); what each class sends: [section 3.2.4](#324-what-each-risk-class-must-deliver).

## 21. risk-factor (RFAC): every field

Contract: [`risk-factor.contract.yaml`](../../config/packs/banking/market-risk/contracts/risk-factor.contract.yaml). One row per factor and
delivery date; a date without a delivery keeps the last; a row applies from `VALID_FROM` to `VALID_TO`.

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `RISK_FACTOR` | id | required | the standard name ([section 18](#18-the-factor-naming-standard)); the key | `IR/CURVE/USD/SOFR/10Y` |
| `FACTOR_TYPE` | text | required | `CURVE`, `INFLATION`, `SPREAD`, `SPOT`, `REPO`, `PRICE`, `VOL` | `CURVE` |
| `RISK_CLASS` | text | required | the FRTB class; a volatility's is its underlying's | `GIRR` |
| `QUALIFIER` | text | required | the currency (GIRR, FX), issuer or index (credit), underlying (equity, commodity) | `USD` |
| `CURVE` | text | optional | the rate curve, `BOND` or `CDS`, the commodity location, or the inflation index | `SOFR` |
| `UNDERLYING` | text | optional | the underlying of an equity, commodity, FX or volatility factor | `SPX` |
| `TENOR` | text | optional | the vertex or the option expiry; null for a spot or a whole curve | `10Y` |
| `UNDERLYING_TENOR` | text | optional | the underlying swap's tenor of a rates volatility | `10Y` |
| `FRTB_BUCKET` | text | required | the currency (GIRR, FX), 1 to 18 (CSR), 1 to 13 (equity), 1 to 11 (commodity) | `USD` |
| `SIMM_RISK_CLASS` | text | required | `InterestRate`, `CreditQ`, `CreditNonQ`, `Equity`, `Commodity`, `FX` | `InterestRate` |
| `SIMM_BUCKET` | text | required | the SIMM bucket; `Residual` when none applies | `1` |
| `SIMM_LABEL1` | text | optional | SIMM's tenor (`2w` … `30y`) or expiry; `Inf` for inflation | `10y` |
| `SIMM_LABEL2` | text | optional | the rate sub-curve (`OIS`, `Libor3m`, `Prime`) or the credit instrument | `OIS` |
| `SECTOR` | text | optional | the issuer's or name's sector, as the grids use it | `Financials including government-backed financials` |
| `CREDIT_QUALITY` | text | optional | `IG` or `HY` (credit) | `IG` |
| `MARKET_CAP`, `ECONOMY` | text | optional | `LARGE` or `SMALL`; `ADVANCED` or `EMERGING` (equity) | `LARGE`, `ADVANCED` |
| `LIQUIDITY_HORIZON` | integer | required | 10, 20, 40, 60 or 120 business days (MAR33.12, the internal models approach) | `10` |
| `MODELLABLE` | boolean | optional | passes the risk factor eligibility test (internal models) | `true` |
| `PROXY_OF` | id | optional | the factor whose data proxies this one | `VOL/EQ/MXEF/10Y` |
| `MARKET_DATA_REF` | text | optional | where the level comes from in the market data | `CRV-USD-SOFR#10Y` |
| `SOURCE_NAMES` | text list | required | the source systems' names; each maps to this factor only | `["MX:IR:CURVE-USD-SOFR-10Y"]` |
| `VALID_FROM`, `VALID_TO` | date | `VALID_FROM` required | the dates the row applies to | `2026-01-02` |
| `BUSINESS_DATE` | date | required | the delivery's date; the partition | `2026-09-30` |
| `SOURCE` | text | required | the system the row came from | `demo-factor-master` |

The demo dictionary's 855 factors: ten rate curves (USD SOFR, EUR €STR, GBP SONIA, JPY TONA, AUD, CAD, CHF, BRL, MXN, CNH) at ten
vertices with their swaption volatilities; three inflation indices; 36 issuers (six sectors, investment grade and high yield) and three
credit indices, bond and CDS curves at five vertices; nine currencies; twelve equity underlyings (four indices, eight names) with repo and
volatility; ten commodities at six forward points (`tools/packgen/sensitivity/factor_dictionary.py`).

## 22. Default risk (DRC) inputs: every field

Contract: [`default-risk.contract.yaml`](../../config/packs/banking/market-risk/contracts/default-risk.contract.yaml) (`status: planned`;
[section 3.3](#33-default-risk-the-drc-inputs-optional)). Path `<root>/risk/default-risk/`; one row per position (or trade), obligor and
business date.

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `DEFAULT_RISK_ID` | id | required | `DRC-<POSITION_ID or TRADE_ID>-<OBLIGOR>-<BATCH_ID>` | `DRC-POS-BOOK-CREDIT-1~CLY-3000087-5493001KJTIIGC8Y1R12-B-DRC-2026-09-30-01` |
| `GRAIN`, `POSITION_ID`, `TRADE_ID` | text, id | as SENS | the position or trade | `POSITION` |
| `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND`, `COMPUTED_AT`, `RESTATEMENT`, `STATUS` | as SENS | required | the run ([section 3.6](#36-delivery-cadence-batches-intraday-and-restatements) applies) | |
| `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, `FRTB_DESK` | id | as SENS | placement | `LE-NY` |
| `OBLIGOR` | id | required | the issuer or reference entity, LEI preferred | `5493001KJTIIGC8Y1R12` |
| `DRC_BUCKET` | text | required | `CORPORATE`, `SOVEREIGN`, `LOCAL_GOVERNMENT_MUNICIPALITY` | `CORPORATE` |
| `SENIORITY` | text | required | `COVERED_BOND`, `SENIOR`, `NON_SENIOR`, `EQUITY` | `SENIOR` |
| `CREDIT_QUALITY` | text | required | `AAA`, `AA`, `A`, `BBB`, `BB`, `B`, `CCC`, `UNRATED`, `DEFAULTED` | `BBB` |
| `NOTIONAL` | amount | required | bond-equivalent notional; positive long (loses on default), negative short | `10000000` |
| `MARKET_VALUE` | amount | required | bond-equivalent market value, signed as the notional | `9800000` |
| `CURRENCY` | text | required | of the two amounts | `USD` |
| `MATURITY_DATE` | date | conditional: not for cash equity | the instrument's maturity | `2028-09-30` |
| `CASH_EQUITY_MATURITY` | rate | conditional: cash equity | `0.25` or `1` (years) | `1` |
| `RECOVERY_INDEPENDENT` | boolean | optional | the price does not depend on recovery: no LGD | `false` |
| `INSTRUMENT` | text | optional | what the row is (for the auditor) | `bond` |

**Signs, checked on load:** a bought CDS has a negative `NOTIONAL`; a sold put on a bond a positive one; `MARKET_VALUE` has the
notional's sign. A row whose `CREDIT_QUALITY` is `DEFAULTED` takes the 100 % weight whatever its seniority.

## 23. Residual risk (RRAO) inputs: every field

Contract: [`residual-risk.contract.yaml`](../../config/packs/banking/market-risk/contracts/residual-risk.contract.yaml) (`status: planned`;
[section 3.4](#34-residual-risk-the-rrao-inputs-optional)). By default the RRAO is derived from `PRODUCT_TYPE` and the trade master's
notional; this optional table, `<root>/risk/residual-risk/`, carries overrides and the flags no master holds. One row per trade and
business date.

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `RESIDUAL_RISK_ID` | id | required | `RRAO-<TRADE_ID>-<BATCH_ID>` | `RRAO-MX-20000311-B-RRAO-2026-09-30-01` |
| `TRADE_ID`, `POSITION_ID` | id | `TRADE_ID` required | the trade and its position | `MX-20000311` |
| `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `COMPUTED_AT` | as SENS | required | the run | |
| `RRAO_CLASS` | text | required | `EXOTIC` (MAR23.3), `OTHER` (MAR23.4, MAR23.5) or `NONE`: overrides the product-type mapping | `OTHER` |
| `RISK_TYPE` | text | optional | `longevity`, `weather`, `natural-disaster`, `future-realised-volatility`, `gap`, `correlation`, `behavioural` | `gap` |
| `GROSS_NOTIONAL` | amount | required | the gross notional, absolute | `50000000` |
| `CURRENCY` | text | required | of the notional | `USD` |
| `BACK_TO_BACK_OF` | id | optional | the trade it exactly matches with a third party: both excluded (MAR23.7) | `MX-20000312` |
| `LISTED_OR_CLEARABLE` | boolean | optional | listed or eligible for central clearing: excluded from the other-residual part only | `false` |
| `REASON` | text | optional | why the trade is in scope | `knock-out barrier` |

## 24. The parameter files' structure

| File | Top-level keys | Per class |
|---|---|---|
| [`frtb-sa-sbm.yaml`](../../config/packs/banking/market-risk/capital/frtb-sa-sbm.yaml) | `regime`, `source`, `scenarios` (`medium`, `high`, `low` formulas) | `GIRR`, `CSR_NS`, `EQ`, `COMM`, `FX`, each with `delta` (`tenors`, `risk-weights`, `within-bucket`, `across-buckets`), `vega` (`risk-weight`, correlations) and `curvature` (`factor`, `shock`, `correlation`; FX `divisor`, `divisor-all-fx-instruments`) |
| [`frtb-sa-drc-rrao.yaml`](../../config/packs/banking/market-risk/capital/frtb-sa-drc-rrao.yaml) | `regime`, `sources` | `drc` (`lgd`, `maturity`, `buckets`, `risk-weights`, `netting`, `hedge-benefit-ratio`, `bucket-charge`, `total`); `rrao` (`exotic-underlying-weight`, `other-residual-risk-weight`, `exotic-underlyings`, `other-residual-risks`, `exclusions`, `product-types`) |
| [`simm.yaml`](../architecture/capital-parameters/simm.yaml) | `version`, `effective-from`, `source`, `holding-period-days`, `product-classes`, `risk-classes`, `risk-class-correlation`, `curvature`, `add-ons` | `InterestRate`, `CreditQ`, `CreditNonQ`, `Equity`, `Commodity`, `FX`: risk weights, correlations, concentration thresholds, HVR; every number `null` as shipped |

Every value names its paragraph in a comment. The bank's copy of each file is the one the engine reads; its SHA-256 identifies it in
every answer, recipe and capture (the rules: [section 6.1](#61-parameters-the-files-their-versions-and-their-hash)).

## 25. Computed records: every field

The names are fixed with the computed kind; the content is the contract.

### 25.1 `frtb-sa-result`: one record per scope and business date

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `BUSINESS_DATE`, `FILER` | text, date | what was computed |
| `TOTAL`, `SBM`, `DRC`, `RRAO` | amount | in the reporting currency; `DRC` and `RRAO` null with `NOT_COMPUTED` when their inputs are missing |
| `SBM_LOW`, `SBM_MEDIUM`, `SBM_HIGH`, `BINDING_SCENARIO` | amount, text | the three scenario totals and the largest |
| `BY_CLASS[]` | record list | `RISK_CLASS`, `MEASURE` (`DELTA`, `VEGA`, `CURVATURE`), `SCENARIO`, `CHARGE`, `ALTERNATIVE_SPECIFICATION` (boolean), `ESTIMATED` (boolean: curvature from gamma) |
| `BUCKETS[]` | record list | `RISK_CLASS`, `MEASURE`, `SCENARIO`, `BUCKET`, `K`, `S`, `S_CAPPED`, `FACTORS`; curvature: `K_UP`, `K_DOWN`, `SIDE` |
| `FACTORS[]` | record list | `RISK_CLASS`, `MEASURE`, `BUCKET`, `RISK_FACTOR`, `S` (netted, regulatory unit), `RW`, `WS`, `GRADIENT`, `CONTRIBUTION`; curvature: `CVR_UP`, `CVR_DOWN` |
| `DRC_BUCKETS[]` | record list | `DRC_BUCKET`, `NET_LONG`, `NET_SHORT`, `WEIGHTED_LONG`, `WEIGHTED_SHORT`, `HBR`, `CHARGE` |
| `DRC_OBLIGORS[]` | record list | `OBLIGOR`, `DRC_BUCKET`, `CREDIT_QUALITY`, `JTD_LONG`, `JTD_SHORT` (scaled), `NET`, `RW` |
| `RRAO_LINES[]` | record list | `TRADE_ID`, `RRAO_CLASS`, `GROSS_NOTIONAL`, `WEIGHT`, `ADD_ON`, `EXCLUSION` |
| `SWITCHES`, `OVERRIDES[]`, `REGULATORY` | record, record list, boolean | the switches used; any override; false when either is not the default |
| `NOT_COMPUTED[]` | text list | the parts and classes not computed, with the reason |
| `PROVENANCE` | record | `INPUTS[]` (table, format, version, batches), `DICTIONARY_VERSION`, `PARAMETERS[]` (file, regime, version, SHA-256), `REFUSED` and `PROXIED` counts per source, `MISSING_POSITIONS`, `ENGINE_VERSION`, `COMPUTED_AT` |

### 25.2 Contributions

One row per part: `PART`, `STANDALONE_SBM`, `CONTRIBUTION`, `SHARE`, `INCREMENTAL` (the first 50 parts), `SCALED` (true when the
gradient was one-sided and the contributions were scaled to add up).

### 25.3 Playground recipe

The parts of [section 9.7](#97-the-playground-recipes-and-the-workbook) as fields; the SIMM playground's recipes have the same structure
with the netting set and the SIMM version in place of the filer and regime.

| Field | Type | Meaning |
|---|---|---|
| `RECIPE_ID` | id | `RCP-FRTB-<yyyymmdd>-<n>` or `RCP-SIMM-…` |
| `KIND` | text | `FRTB-SA` or `SIMM` |
| `CREATED_BY`, `CREATED_AT` | text, timestamp | |
| `SCOPE`, `BUSINESS_DATE`, `FILER` or `NETTING_SET` | text, date | |
| `INPUTS[]` | record list | `TABLE`, `FORMAT` (`delta`, `iceberg`), `VERSION`, `BATCH_IDS[]` |
| `PARAMETERS[]` | record list | `FILE`, `REGIME` or `VERSION`, `SHA256` |
| `SWITCHES` | record | each switch with its value |
| `OVERRIDES[]` | record list | `PATH`, `VALUE`, `BY`, `REASON` |
| `ENGINE_VERSION` | text | |
| `RESULT_DIGEST` | text | SHA-256 of the figures, for the byte-equal replay |

### 25.4 Official capture

The record of section 25.1 for each filer and date, with `CAPTURE_ID`, `CAPTURE_VERSION` (a restatement adds a version) and
`CAPTURED_BY`; `REGULATORY` is always true (default switches only, no override).

## 26. Worked JSON Lines examples

The records of [section 15](#15-a-worked-example), as an upstream team would send them, then one record of each special case. Each line
is one record (wrapped here only by the code block's width).

**position-sensitivity: the worked example's three positions.** The USD rates position of cases 1 and 2:

```json
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-20000101-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000101","TRADE_IDS":["MX-20000101"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","FRTB_DESK":"FD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","TRADE_STATUS":"NEW","MATURITY_DATE":"2036-09-30","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","MEASURE":"DV01","VALUE":-1000.0,"CURRENCY":"USD","RISK_CLASS":"GIRR","FRTB_BUCKET":"USD","TENOR":"5Y","SIMM_BUCKET":"1"},{"RISK_FACTOR":"IR/CURVE/USD/SOFR/10Y","MEASURE":"DV01","VALUE":-500.0,"CURRENCY":"USD","RISK_CLASS":"GIRR","FRTB_BUCKET":"USD","TENOR":"10Y","SIMM_BUCKET":"1"}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-2~MX-20000102-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-2~MX-20000102","TRADE_IDS":["MX-20000102"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-2","FRTB_DESK":"FD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","TRADE_STATUS":"NEW","MATURITY_DATE":"2028-09-30","CURRENCY":"EUR","SENSITIVITIES":[{"RISK_FACTOR":"IR/CURVE/EUR/ESTR/2Y","MEASURE":"DV01","VALUE":800.0,"CURRENCY":"USD","RISK_CLASS":"GIRR","FRTB_BUCKET":"EUR","TENOR":"2Y","SIMM_BUCKET":"1"}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-EQ-1~MX-20000103-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-EQ-1~MX-20000103","TRADE_IDS":["MX-20000103"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-EQUITY","TT_BOOK":"BOOK-EQ-1","FRTB_DESK":"FD-EQUITY","PRODUCT_GROUP":"Equity","PRODUCT_TYPE":"EQ_INDEX_OPTION","TRADE_STATUS":"NEW","MATURITY_DATE":"2027-09-17","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"EQ/SPOT/SPX","MEASURE":"DELTA","VALUE":10000.0,"CURRENCY":"USD","RISK_CLASS":"EQ","FRTB_BUCKET":"12","TENOR":null,"SIMM_BUCKET":"11"},{"RISK_FACTOR":"EQ/SPOT/SPX","MEASURE":"CURVATURE_UP","VALUE":120000.0,"CURRENCY":"USD","RISK_CLASS":"EQ","FRTB_BUCKET":"12","TENOR":null,"SHOCK_SIZE":0.15},{"RISK_FACTOR":"EQ/SPOT/SPX","MEASURE":"CURVATURE_DOWN","VALUE":-180000.0,"CURRENCY":"USD","RISK_CLASS":"EQ","FRTB_BUCKET":"12","TENOR":null,"SHOCK_SIZE":0.15}]}
```

**position-sensitivity: special cases.** A vega element (an option with its implied volatility); a source that cannot revalue sending
`GAMMA` (case 10: the curvature is then estimated, `CVR± ≈ −½ · 200 · 15² = 22,500`); a trade-grain record from Endur; a failed record;
an intraday replacement of the first position (only its 10Y changed: the whole record is sent again):

```json
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-20000104-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000104","TRADE_IDS":["MX-20000104"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"SWAPTION","TRADE_STATUS":"NEW","MATURITY_DATE":"2037-09-30","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"VOL/IR/USD/SOFR/1Y/10Y","MEASURE":"VEGA","VALUE":40657.16,"CURRENCY":"USD","TENOR":"1Y","IMPLIED_VOL":0.28}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-EQ-2~CLY-4000201-B-SENS-2026-09-30-02","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-EQ-2~CLY-4000201","TRADE_IDS":["CLY-4000201"],"SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-02","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-EQUITY","TT_BOOK":"BOOK-EQ-2","PRODUCT_GROUP":"Equity","PRODUCT_TYPE":"EQ_INDEX_OPTION","TRADE_STATUS":"NEW","MATURITY_DATE":"2027-09-17","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"EQ.SPOT.SPX","MEASURE":"DELTA","VALUE":10000.0,"CURRENCY":"USD"},{"RISK_FACTOR":"EQ.SPOT.SPX","MEASURE":"GAMMA","VALUE":-200.0,"CURRENCY":"USD"}]}
{"SENSITIVITY_ID":"SENS-1000019-B-SENS-2026-09-30-03","GRAIN":"TRADE","POSITION_ID":"POS-BOOK-COMM-1~1000019","TRADE_ID":"1000019","TRADE_IDS":["1000019"],"SOURCE_SYSTEM":"ENDUR","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-03","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-COMM","TT_BOOK":"BOOK-COMM-1","PRODUCT_GROUP":"Commodity","PRODUCT_TYPE":"CallNot","TRADE_STATUS":"AMENDED","MATURITY_DATE":"2028-02-29","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"CM/PRICE/BRENT/ICE/1Y","MEASURE":"DELTA","VALUE":-49726.79,"CURRENCY":"USD","TENOR":"1Y"},{"RISK_FACTOR":"CM/PRICE/BRENT/ICE","MEASURE":"CURVATURE_UP","VALUE":-2066410.39,"CURRENCY":"USD","SHOCK_SIZE":0.35},{"RISK_FACTOR":"CM/PRICE/BRENT/ICE","MEASURE":"CURVATURE_DOWN","VALUE":3888576.98,"CURRENCY":"USD","SHOCK_SIZE":0.35},{"RISK_FACTOR":"VOL/CM/BRENT/1Y","MEASURE":"VEGA","VALUE":-63654.63,"CURRENCY":"USD","TENOR":"1Y","IMPLIED_VOL":0.32}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-CREDIT-1~CLY-3000090-B-SENS-2026-09-30-02","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000090","TRADE_IDS":["CLY-3000090"],"SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-02","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"failed","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","PRODUCT_GROUP":"Credit","PRODUCT_TYPE":"CDS_SINGLE","TRADE_STATUS":"NEW","MATURITY_DATE":"2031-12-20","CURRENCY":"USD","SENSITIVITIES":null}
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-20000101-B-SENS-2026-09-30-11","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000101","TRADE_IDS":["MX-20000101"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-11","RUN_KIND":"INTRADAY","COMPUTED_AT":"2026-09-30T14:20:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","FRTB_DESK":"FD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","TRADE_STATUS":"AMENDED","MATURITY_DATE":"2036-09-30","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","MEASURE":"DV01","VALUE":-1000.0,"CURRENCY":"USD"},{"RISK_FACTOR":"IR/CURVE/USD/SOFR/10Y","MEASURE":"DV01","VALUE":-650.0,"CURRENCY":"USD"}]}
```

The Calypso record uses the source's own names (`EQ.SPOT.SPX`), resolved through `SOURCE_NAMES`. A restatement of the date would send
the whole Murex full run again with `RESTATEMENT` 1, a new `BATCH_ID` and a later `COMPUTED_AT`.

**risk-factor: the factors of the example.**

```json
{"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","FACTOR_TYPE":"CURVE","RISK_CLASS":"GIRR","QUALIFIER":"USD","CURVE":"SOFR","TENOR":"5Y","FRTB_BUCKET":"USD","SIMM_RISK_CLASS":"InterestRate","SIMM_BUCKET":"1","SIMM_LABEL1":"5y","SIMM_LABEL2":"OIS","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"MARKET_DATA_REF":"CRV-USD-SOFR#5Y","SOURCE_NAMES":["MX:IR:CURVE-USD-SOFR-5Y","IR.CURVE.USD.SOFR.5Y","5Y_SOFR_USD_CURVE_IR"],"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30","SOURCE":"factor-master"}
{"RISK_FACTOR":"IR/CURVE/EUR/ESTR/2Y","FACTOR_TYPE":"CURVE","RISK_CLASS":"GIRR","QUALIFIER":"EUR","CURVE":"ESTR","TENOR":"2Y","FRTB_BUCKET":"EUR","SIMM_RISK_CLASS":"InterestRate","SIMM_BUCKET":"1","SIMM_LABEL1":"2y","SIMM_LABEL2":"OIS","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"MARKET_DATA_REF":"CRV-EUR-ESTR#2Y","SOURCE_NAMES":["MX:IR:CURVE-EUR-ESTR-2Y","IR.CURVE.EUR.ESTR.2Y","2Y_ESTR_EUR_CURVE_IR"],"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30","SOURCE":"factor-master"}
{"RISK_FACTOR":"EQ/SPOT/SPX","FACTOR_TYPE":"SPOT","RISK_CLASS":"EQ","QUALIFIER":"SPX","UNDERLYING":"SPX","FRTB_BUCKET":"12","SIMM_RISK_CLASS":"Equity","SIMM_BUCKET":"11","SECTOR":"Index","MARKET_CAP":"LARGE","ECONOMY":"ADVANCED","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"MARKET_DATA_REF":"EQ-SPX","SOURCE_NAMES":["MX:EQ:SPOT-SPX","EQ.SPOT.SPX","SPX_SPOT_EQ"],"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30","SOURCE":"factor-master"}
```

**default-risk: case 4.** The long BBB bond and the bought CDS on obligor A, and obligor B's net short:

```json
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000101-LEI-OBLIGOR-A-B-DRC-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000101","SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-DRC-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","OBLIGOR":"LEI-OBLIGOR-A","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BBB","NOTIONAL":10000000,"MARKET_VALUE":9800000,"CURRENCY":"USD","MATURITY_DATE":"2028-09-30","INSTRUMENT":"bond"}
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000102-LEI-OBLIGOR-A-B-DRC-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000102","SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-DRC-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","OBLIGOR":"LEI-OBLIGOR-A","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BBB","NOTIONAL":-4000000,"MARKET_VALUE":-3950000,"CURRENCY":"USD","MATURITY_DATE":"2027-03-31","INSTRUMENT":"bought CDS"}
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000103-LEI-OBLIGOR-B-B-DRC-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000103","SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-DRC-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","OBLIGOR":"LEI-OBLIGOR-B","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BB","NOTIONAL":-2500000,"MARKET_VALUE":-2625000,"CURRENCY":"USD","MATURITY_DATE":"2029-09-30","INSTRUMENT":"bought CDS"}
```

Obligor B: JTD = 0.75 · −2,500,000 + (−2,625,000 + 2,500,000) = −2,000,000, maturity over a year so scaled × 1: the net short of case 4.

**residual-risk: case 5.**

```json
{"RESIDUAL_RISK_ID":"RRAO-MX-20000311-B-RRAO-2026-09-30-01","TRADE_ID":"MX-20000311","SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-RRAO-2026-09-30-01","COMPUTED_AT":"2026-09-30T06:10:00Z","RRAO_CLASS":"OTHER","RISK_TYPE":"gap","GROSS_NOTIONAL":50000000,"CURRENCY":"USD","LISTED_OR_CLEARABLE":false,"REASON":"FX knock-out barrier"}
{"RESIDUAL_RISK_ID":"RRAO-1000420-B-RRAO-2026-09-30-01","TRADE_ID":"1000420","SOURCE_SYSTEM":"ENDUR","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-RRAO-2026-09-30-01","COMPUTED_AT":"2026-09-30T06:10:00Z","RRAO_CLASS":"EXOTIC","RISK_TYPE":"weather","GROSS_NOTIONAL":10000000,"CURRENCY":"USD","REASON":"heating degree day swap"}
```

**capital-input: the bank's own DRC beside Drishti's.**

```json
{"CAPITAL_INPUT_ID":"CAPI-LE-NY-OSFI-CAR9-2026-09-30","BUSINESS_DATE":"2026-09-30","FILER":"LE-NY","REGIME":"OSFI-CAR9","DEFAULT_RISK_CHARGE":126000,"CURRENCY":"USD","APPROVED_BY":"head-of-regulatory-capital","SOURCE":"capital-engine","AS_OF":"2026-09-30T23:00:00Z"}
```

**frtb-sa-result: the example's answer**, with the default switches and, as in the worked example, without the index's own delta
charge (abbreviated: the factor and bucket tables carry one row each here):

```json
{"SCOPE":"LE:LE-NY","CANONICAL":"LE:LE-NY","BUSINESS_DATE":"2026-09-30","FILER":"LE-NY","TOTAL":416732.74,"SBM":140555.42,"DRC":126177.32,"RRAO":150000.0,"SBM_LOW":140555.42,"SBM_MEDIUM":131286.72,"SBM_HIGH":121079.64,"BINDING_SCENARIO":"low","BY_CLASS":[{"RISK_CLASS":"GIRR","MEASURE":"DELTA","SCENARIO":"low","CHARGE":110555.42,"ALTERNATIVE_SPECIFICATION":false,"ESTIMATED":false},{"RISK_CLASS":"EQ","MEASURE":"CURVATURE","SCENARIO":"low","CHARGE":30000.0,"ALTERNATIVE_SPECIFICATION":false,"ESTIMATED":false}],"BUCKETS":[{"RISK_CLASS":"GIRR","MEASURE":"DELTA","SCENARIO":"low","BUCKET":"USD","K":115106.47,"S":-116672.62,"S_CAPPED":null,"FACTORS":2}],"FACTORS":[{"RISK_CLASS":"GIRR","MEASURE":"DELTA","BUCKET":"USD","RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","S":-10000000.0,"RW":0.00777817,"WS":-77781.75}],"SWITCHES":{"curvature-source":"revaluation","girr-curvature-shock":"1.7%","fx-curvature-divisor":"options-not-referencing-reporting-currency","girr-correlation":"published-table","scenario":"binding"},"OVERRIDES":[],"REGULATORY":true,"NOT_COMPUTED":[],"PROVENANCE":{"INPUTS":[{"TABLE":"position-sensitivity","FORMAT":"delta","VERSION":412,"BATCH_IDS":["B-SENS-2026-09-30-01"]},{"TABLE":"default-risk","FORMAT":"delta","VERSION":37,"BATCH_IDS":["B-DRC-2026-09-30-01"]}],"DICTIONARY_VERSION":58,"PARAMETERS":[{"FILE":"frtb-sa-sbm.yaml","REGIME":"BCBS-MAR21","SHA256":"<sha-256 of the file>"}],"ENGINE_VERSION":"<engine version>"}}
```

**A recipe.**

```json
{"RECIPE_ID":"RCP-FRTB-20261002-1","KIND":"FRTB-SA","CREATED_BY":"validator.mr","CREATED_AT":"2026-10-02T15:04:00Z","SCOPE":"LE:LE-NY","BUSINESS_DATE":"2026-09-30","FILER":"LE-NY","INPUTS":[{"TABLE":"position-sensitivity","FORMAT":"delta","VERSION":412,"BATCH_IDS":["B-SENS-2026-09-30-01","B-SENS-2026-09-30-11"]},{"TABLE":"risk-factor","FORMAT":"delta","VERSION":58,"BATCH_IDS":[]}],"PARAMETERS":[{"FILE":"frtb-sa-sbm.yaml","REGIME":"BCBS-MAR21","SHA256":"<sha-256>"},{"FILE":"frtb-sa-drc-rrao.yaml","REGIME":"BCBS-MAR22-23","SHA256":"<sha-256>"}],"SWITCHES":{"curvature-source":"revaluation","girr-curvature-shock":"1.7%","fx-curvature-divisor":"options-not-referencing-reporting-currency","girr-correlation":"exact-formula","scenario":"binding"},"OVERRIDES":[],"ENGINE_VERSION":"<engine version>","RESULT_DIGEST":"<sha-256 of the figures>"}
```

## 27. Mapping from upstream formats

### 27.1 From ISDA CRIF rows

Many banks already produce the Common Risk Interchange Format for SIMM. A CRIF file carries delta and vega at SIMM's grid; it does not
carry FRTB curvature, the commodity tenors, or the record-level fields of SENS, so a CRIF feed alone covers SIMM and most FRTB delta and
vega, and FRTB curvature needs `CURVATURE_UP` and `CURVATURE_DOWN` (or `GAMMA`) from the same source. The loader groups the CRIF lines of
one trade into one `GRAIN` `TRADE` record:

| CRIF field | SENS field | Rule |
|---|---|---|
| `TradeID` | `TRADE_ID`, `SENSITIVITY_ID` | `GRAIN` `TRADE` |
| `PortfolioID` | not on SENS | the netting set comes from the netting-set master; kept for the SIMM reconciliation |
| `ProductClass` | not on SENS | checked against the product-class mapping of `PRODUCT_GROUP` ([SIMM.md](SIMM.md#62-product-classes)) |
| `RiskType`, `Qualifier`, `Label1`, `Label2` | `SENSITIVITIES[].RISK_FACTOR`, `MEASURE` | the table below; `Label1` upper-cased to a tenor code (`10y` → `10Y`); `Label2` through the bank's sub-curve map (`OIS` → the currency's curve) |
| `Bucket` | `SENSITIVITIES[].SIMM_BUCKET` | a difference from the dictionary is a warning |
| `Amount`, `AmountCurrency` | `VALUE`, `CURRENCY` | as is for delta (CRIF's sign is the same: gain for the up-move) |
| `AmountUSD` | none | checked against `Amount` at the date's FX |
| `CollectRegulations`, `PostRegulations` | not on SENS | the regimes come from the CSA ([SIMM.md](SIMM.md#4-reference-and-master-data-read)) |
| `EndDate` | `MATURITY_DATE` | the trade's |
| (not in CRIF) | `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND`, `COMPUTED_AT`, `SOURCE_SYSTEM` | from the file's load configuration |
| (not in CRIF) | placement, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `TRADE_STATUS`, `CURRENCY` | from the trade master |

| CRIF `RiskType` | `RISK_FACTOR` | `MEASURE` |
|---|---|---|
| `Risk_IRCurve` | `IR/CURVE/<Qualifier>/<curve>/<Label1>` | `DV01` |
| `Risk_Inflation` | the currency's inflation factor (CRIF has no tenor: one element per currency) | `IE01` |
| `Risk_XCcyBasis` | no name in the standard yet ([section 16](#16-open-questions-and-decisions)) | `DV01` |
| `Risk_CreditQ` | `CR/SPREAD/<issuer>/<BOND or CDS>/<Label1>` | `CS01` |
| `Risk_CreditNonQ` | `CR/SPREAD/<tranche>/<BOND or CDS>/<Label1>`, class `CSR_SEC_NCTP` | `CS01` |
| `Risk_BaseCorr` | `CR/SPREAD/<index family>` | `BASE_CORR` |
| `Risk_Equity` | `EQ/SPOT/<Qualifier>` | `DELTA` |
| `Risk_Commodity` | `CM/PRICE/<Qualifier>/<location>` (CRIF has no tenor: SIMM only) | `DELTA` |
| `Risk_FX` | `FX/SPOT/<Qualifier>` | `DELTA` |
| `Risk_IRVol`, `Risk_InflationVol`, `Risk_CreditVol`, `Risk_CreditVolNonQ`, `Risk_EquityVol`, `Risk_CommodityVol`, `Risk_FXVol` | `VOL/<class>/<underlying>/<Label1>[/<underlying tenor>]` | `VEGA`, with `IMPLIED_VOL` |

**Vega from CRIF.** The CRIF specification of the SIMM version defines each volatility row's `Amount` per risk type; the loader turns it
into `VEGA` per volatility point (and needs the implied volatility for that) by a rule configured per risk type and confirmed with the
bank, never assumed ([SIMM.md](SIMM.md#16-open-questions-and-decisions), open question 3).

### 27.2 From a front-office bump report

A typical risk report has one line per trade, risk type, curve or underlying, tenor and bump: `TRADE_ID`, `RISK_TYPE`, `CURVE_NAME`,
`TENOR`, `BUMP_SIZE`, `BUMP_TYPE` (absolute or relative), `PV_CHANGE`, `CCY`.

| Report column | SENS | Rule |
|---|---|---|
| `TRADE_ID` | `TRADE_ID` | `GRAIN` `TRADE`, or summed into the position by the source |
| `RISK_TYPE` + `CURVE_NAME` + `TENOR` | `RISK_FACTOR` | the source's name, listed in the dictionary's `SOURCE_NAMES` |
| `RISK_TYPE` | `MEASURE` | rate, inflation, spread → `DV01`, `IE01`, `CS01`; spot, price → `DELTA`; volatility → `VEGA` |
| `PV_CHANGE` with `BUMP_SIZE` | `VALUE` | delivered at the standard move (1 bp, 1 %, 1 volatility point); a report bumped by another size is re-run at the standard move by the source, not rescaled by Drishti (a rescaled bump mixes in convexity) |
| `CCY` | `CURRENCY` | |
| the curvature run | `CURVATURE_UP`, `CURVATURE_DOWN`, `SHOCK_SIZE` | two full revaluations per curvature factor with the regulatory shock |
| the tenor | `TENOR` | a non-vertex tenor allocated by the source to the two neighbouring vertices (linear in time) |

### 27.3 From another FRTB engine's sensitivity file

A file already in regulatory units (s_k per unit of the factor, by risk class, bucket, qualifier and tenor) converts back by the constants
of [section 3.2.3](#323-from-the-delivered-values-to-the-regulatory-sensitivities): `VALUE = s_k / 10,000` for rates and spreads,
`s_k / 100` for spots and prices, `s_k / (100 × IMPLIED_VOL)` for vega. Delivering the source's raw bumps instead is better: it keeps one
convention for every reader and lets the MR cube show the ladders in the units traders use.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
