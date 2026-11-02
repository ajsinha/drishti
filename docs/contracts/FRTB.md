<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# FRTB standardised approach: the data contract

*Contract version 2.0 · 2026-11-02 · **Status:** design, not built. The input contracts are written (`status: planned`) and the demo
inputs are generated; the engine, its screens and its playground are the design to build · Method:
[SENSITIVITIES_AND_FRTB_SA.md](../architecture/SENSITIVITIES_AND_FRTB_SA.md) · Every field, every bucket grid and the worked records:
[FRTB_DATA_STRUCTURES.md](FRTB_DATA_STRUCTURES.md) · Shared rules: [README.md](README.md) · The same sensitivities feed
[SIMM.md](SIMM.md), [STR.md](STR.md) and [PNL.md](PNL.md)*

This is the data contract of Drishti's **FRTB standardised approach** (FRTB-SA): the sensitivities-based method (SBM, Basel MAR21), the
default risk charge for non-securitisations (DRC, MAR22) and the residual risk add-on (RRAO, MAR23). It says what an upstream team
delivers, how Drishti checks it, what Drishti builds from it in memory, what it answers, and how an auditor follows every figure from the
delivered sensitivity to the total. The method itself (every formula with its paragraph, the hand-built test cases, the decisions and
the check against the rules) is in [SENSITIVITIES_AND_FRTB_SA.md](../architecture/SENSITIVITIES_AND_FRTB_SA.md); this document repeats
each formula once, briefly, so that the contract can be read on its own.

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

## 1. Summary

**What it is.** The FRTB-SA capital of any set of positions on a business date: delta, vega and curvature for each risk class under the
three correlation scenarios, the binding scenario, the default risk charge and the residual risk add-on, with every intermediate figure
(weighted sensitivity, bucket charge, bucket sum, the curvature side chosen) and each part's contribution. Computed when asked, for any
scope, from trade-grain inputs; never stored as a figure except as an official capture. **The regime:** Basel MAR21 to MAR23 as the
reference, then OSFI CAR chapter 9, as for the regulatory VaR reports (decision 4).

**Not in scope:** the internal models approach and its eligibility tests (the P&L attribution test belongs with [PNL.md](PNL.md)); the
SBM and DRC of securitisations and the correlation trading portfolio (their inputs are accepted and counted, their charge is reported as
*not computed* until their parameters are added, section 16); a jurisdiction's reporting templates.

**Commands (planned; the names follow the VaR API and are fixed at build).**

| What | Command | API | CLI |
|---|---|---|---|
| Sensitivities of a scope, by factor, bucket or grid | `SENS <scope>` | `GET /api/v1/sensitivities` | `drishti.py sens show` |
| The factor dictionary and the mapping refusals | `RFAC <factor or name>` | `GET /api/v1/risk-factors` | `drishti.py sens factors` |
| FRTB-SA of a scope: SBM by class and scenario, DRC, RRAO | `FRSA <scope>` | `GET /api/v1/frtb/sa` | `drishti.py frtb sa` |
| Its steps: buckets, WS, K, S, the scenario | in `FRSA` | `GET /api/v1/frtb/sa/explain` | `drishti.py frtb explain` |
| Contributions | in `FRSA` | `GET /api/v1/frtb/sa/contributions` | `drishti.py frtb contributions` |
| The playground and its recipes | `PLAY FRTB <scope>`, `PLAY FRTB RUN <recipe>` | `/api/v1/frtb/playground` (and `.xlsx`) | `drishti.py play frtb` |
| The official capture per filer and date | | | `drishti.py frtb capture` |

| Part | Status | Where |
|---|---|---|
| `position-sensitivity` (SENS): the shared input, one record per position or trade with a sparse list of sensitivities | contract `planned`, demo data generated | section 3.2 |
| `risk-factor` (RFAC): the factor dictionary, its FRTB and SIMM mapping and the bank's own names | contract `planned`, demo data generated | section 4.1 |
| Default-risk inputs (obligor, seniority, rating, notional, market value, maturity) | draft in this document, no YAML contract yet | section 3.3 |
| Residual-risk inputs (product type, gross notional, exclusion flags) | draft in this document, no YAML contract yet | section 3.4 |
| `capital-input` (CAPI): the bank's own charges per filer, read for comparison only | contract `planned` | section 3.5 |
| Parameter files: `frtb-sa-sbm.yaml`, `frtb-sa-drc-rrao.yaml` | drafts | section 6.1 |
| The engine, the computed kind `frtb-sa-result`, the API, the playground, official captures | design | sections 6 to 9 |
| Retirement of the stored kind `frtb-sensitivity` | when the engine is built | section 14 |

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
[`position-sensitivity.contract.yaml`](../../config/packs/market-risk/contracts/position-sensitivity.contract.yaml),
[`risk-factor.contract.yaml`](../../config/packs/market-risk/contracts/risk-factor.contract.yaml) and
[`capital-input.contract.yaml`](../../config/packs/market-risk/contracts/capital-input.contract.yaml); their generated pages are in
[packs/market-risk.md](packs/market-risk.md#sens-position-sensitivities-position-sensitivity) and a real demo record explained field by
field is in [samples/market-risk/position-sensitivity.md](samples/market-risk/position-sensitivity.md) and
[samples/market-risk/risk-factor.md](samples/market-risk/risk-factor.md).

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

Every field, type and example: [FRTB_DATA_STRUCTURES.md section 4](FRTB_DATA_STRUCTURES.md#4-position-sensitivity-sens-every-field).
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
([design section 2.3](../architecture/SENSITIVITIES_AND_FRTB_SA.md#23-from-the-delivered-conventions-to-the-regulatory-sensitivities)):

```
GIRR, CSR delta; equity repo      s_k = VALUE × 10,000          (DV01, IE01, CS01; DELTA of an EQ/REPO factor)    MAR21.19 to MAR21.24
Equity spot, commodity, FX delta  s_k = VALUE × 100             (DELTA per 1 %)                                    MAR21.19 to MAR21.24
Vega, every class                 s_k = VALUE × 100 × IMPLIED_VOL                (∂V/∂σ · σ, the vega risk sensitivity)   MAR21.25
Curvature                         read as delivered (currency amounts), with the delta of section 6.4
```

#### 3.2.4 What each risk class must deliver

The patterns are the factor naming standard ([FRTB_DATA_STRUCTURES.md section 2](FRTB_DATA_STRUCTURES.md#2-the-factor-naming-standard)).
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

Draft: no YAML contract yet (section 16). **Grain:** one row per position (or trade), obligor and business date, for every position with
default risk: bonds, CDS and other credit derivatives, equities and equity derivatives. **Key:** `DEFAULT_RISK_ID` =
`DRC-<POSITION_ID or TRADE_ID>-<OBLIGOR>-<BATCH_ID>`. Every field and an example:
[FRTB_DATA_STRUCTURES.md section 6](FRTB_DATA_STRUCTURES.md#6-default-risk-drc-inputs-draft).

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
the risks that do not bring an instrument in). The **gross notional** comes from the trade master. Draft: an optional `residual-risk`
table carries what neither SENS nor the trade master holds today (section 16): one row per trade with `RRAO_CLASS` (`EXOTIC`, `OTHER`,
`NONE`, an override of the mapping), `GROSS_NOTIONAL` and `CURRENCY`, `BACK_TO_BACK_OF` (the trade it exactly matches with a third
party: both excluded, MAR23.7) and `LISTED_OR_CLEARABLE` (excluded from the other-residual part only; an exotic underlying stays in).
Every field: [FRTB_DATA_STRUCTURES.md section 7](FRTB_DATA_STRUCTURES.md#7-residual-risk-rrao-inputs-draft).

### 3.5 capital-input (CAPI): the bank's own charges

One row per filer, regime and date ([contract](../../config/packs/market-risk/contracts/capital-input.contract.yaml)). FRTB-SA reads
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
| `risk-hierarchy` (HIER) | `NODE_ID` | the scopes: desks, legal entities, limit letters; the FRTB desks |
| `pnl-vector` (positions) | `POSITION_ID` | the completeness check and the consistency check against VaR |
| FX rates of the date | currency pair | conversion of every element to the reporting currency |
| The parameter files | file name, version and hash | section 6.1 |

### 4.1 The factor dictionary (RFAC)

One row per risk factor, slowly changing (`VALID_FROM`, `VALID_TO`), keyed by `RISK_FACTOR`
([contract](../../config/packs/market-risk/contracts/risk-factor.contract.yaml); every field in
[FRTB_DATA_STRUCTURES.md section 5](FRTB_DATA_STRUCTURES.md#5-risk-factor-rfac-every-field)). It holds the factor's parts (`FACTOR_TYPE`,
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
| [`frtb-sa-sbm.yaml`](../architecture/capital-parameters/frtb-sa-sbm.yaml) | SBM risk weights per class, bucket and vertex; ρ and γ; the scenario formulas; the curvature rules (factor, shock, correlation, FX divisor) | Basel MAR21 |
| [`frtb-sa-drc-rrao.yaml`](../architecture/capital-parameters/frtb-sa-drc-rrao.yaml) | LGDs, maturity floor and cap, DRC buckets and risk weights, netting rule, HBR; RRAO weights, exotic and other lists, exclusions, the product-type mapping | Basel MAR22, MAR23 |

**Rules** ([design section 14](../architecture/SENSITIVITIES_AND_FRTB_SA.md#14-parameters-as-configuration)): one file per regime (Basel,
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
[FRTB_DATA_STRUCTURES.md section 3](FRTB_DATA_STRUCTURES.md#3-the-bucket-grids). **Vega** uses the same steps with the vega risk weight
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
([design section 12.1a](../architecture/SENSITIVITIES_AND_FRTB_SA.md#121a-critique-and-reproduce-the-method-choices-as-switches)):

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

Built at load, indexed like the VaR vectors (design section 2.4):

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
([VAR_SHARDING.md](../architecture/VAR_SHARDING.md)).

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

One record per scope and business date (class `computed`; its contract is written when the engine is built). Every field and a worked
record: [FRTB_DATA_STRUCTURES.md section 9](FRTB_DATA_STRUCTURES.md#9-computed-records). It holds the headline above, and the
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
([design section 12.1](../architecture/SENSITIVITIES_AND_FRTB_SA.md#121-frtb-sa-playground-play-frtb)): up to a configurable number of
factors (default 2,000; beyond it the export states its size and offers one class at a time).

**A recipe** makes every playground run reproducible ([design section 12.1a](../architecture/SENSITIVITIES_AND_FRTB_SA.md#121a-critique-and-reproduce-the-method-choices-as-switches)):

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
| Required fields, enums, the key pattern | the record | `DRS-5327` *<SENSITIVITY_ID>: <field> <is missing or has value v>; the record is refused* |
| One grain per source and date | the records of the second grain | `DRS-5328` *<source> sent both POSITION and TRADE records for <date>; the TRADE records are refused* |
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
`tools/packgen/sensitivity/test_sensitivity_data.py`. Until the kinds have a command they are not in `testdata.yaml` and `lake demo`
does not load them. No default-risk or residual-risk demo data exists yet.

## 14. Retiring the stored version

The stored kind `frtb-sensitivity` (one record per book with `DELTA_CHARGE`, `VEGA_CHARGE`, `CURVATURE_CHARGE`, `TOTAL`, per-bucket
`SENSITIVITIES[]` and an equal `BY_CLASS` split) was generated without risk weights or correlations; no factor relates its figures to
FRTB-SA. It stays active, unchanged, until the engine is built; then:

| Old | New |
|---|---|
| `FRTB-<desk>-<n>` (the book `BOOK-<desk>-<n>`) | `FRSA BOOK:BOOK-<desk>-<n>` |
| `/v/frtb-sensitivity/<id>` | the same, by redirect |
| The kind, its samples, test data, Sutra, Calc snippets and contract | removed; a guard test fails when `frtb-sensitivity` returns or an input table carries a charge (`DELTA_CHARGE`, `SBM`, `DRC`) |

The engine is validated against the hand-built cases of section 15 and against the bank's own figures when it supplies a parallel run.

## 15. A worked example

The design's hand-built cases 1 to 5 ([design section 13.1](../architecture/SENSITIVITIES_AND_FRTB_SA.md#131-hand-built-cases)), small
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
[FRTB_DATA_STRUCTURES.md section 10](FRTB_DATA_STRUCTURES.md#10-worked-json-lines-examples).

## 16. Open questions and decisions

**Decided** (product owner, 2026-11-01 and 2026-11-02; [design section 16](../architecture/SENSITIVITIES_AND_FRTB_SA.md#16-decisions)):
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

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
