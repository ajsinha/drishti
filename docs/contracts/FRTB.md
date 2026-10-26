<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# FRTB standardised approach: the data contract

*Version 1.0 · 2026-10-25 · **Status:** proposed (PLANNED for 2.9.0) · Shared rules: [README.md](README.md) · Model contract:
[VAR.md](VAR.md) · The same sensitivities feed [SIMM.md](SIMM.md), [STR.md](STR.md) and [PNL.md](PNL.md)*

This is the planned data contract of Drishti's **FRTB standardised approach** (SA) engine: the sensitivities-based method (SBM) of the
Basel Committee's *Minimum capital requirements for market risk* (BCBS d457, January 2019, consolidated as chapter **MAR21** of the
Basel Framework), with the default risk charge (MAR22) and the residual risk add-on (MAR23) where their inputs are delivered. It is
computed when asked, for **any scope**, from one table of per-trade sensitivities that the upstream risk engine delivers. It replaces
the stored kind `frtb-sensitivity`. Everything here is a **proposal** for the 2.9.0 build; each change will be recorded in
[section 16](#16-open-questions-and-the-change-table).

This document also defines the **shared `sensitivity` table** (§3.2) and the **sensitivity store** in memory (§7): SIMM, STR and P&L
explain read the same rows and refer here.

**Sources read (2026-10-25):** `config/packs/market-risk/contracts/frtb-sensitivity.contract.yaml`, the pack's
`samples/frtb-sensitivity.jsonl` (30 books), `data/testdata/market-risk/frtb-sensitivity/` (snapshot of 2026-07-08, every later
file empty), `tools/packgen/banking/data_results.py` (`market_results`, `book_var`, `TR_CLASS`), `tools/packgen/banking/data_trades.py`
(`risk_numbers`, `sensitivities`), the Calc snippets `config/packs/market-risk/python/frtb_girr_delta.py` and `frtb_overview.py`.

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
16. [Open questions and the change table](#16-open-questions-and-the-change-table)

## 1. Summary

**What it is.** The FRTB SA capital charge of any set of trades: delta, vega and curvature for each of the seven risk classes (GIRR,
CSR non-securitisation, CSR securitisation non-CTP, CSR correlation trading portfolio, equity, commodity, FX) under the three
correlation scenarios, the binding scenario, the default risk charge and the residual risk add-on, with the breakdown by risk class,
bucket and risk factor, and each part's contribution. **Not** a regulatory submission engine: no jurisdiction's reporting templates,
no IMA eligibility tests (the P&L attribution test is out of scope, [PNL.md](PNL.md#16-open-questions-and-the-change-table)).

**Commands.** `FRTB <scope> <GO>` (`FRTB DESK RATES <GO>`, `FRTB BANK <GO>`, `FRTB BOOK BOOK-COMM-1 <GO>`); the API `/api/v1/frtb`;
the computed kinds `frtb-charge`, `frtb-bucket`, and the shared `sensitivity-net`.

| Part | Status | Where |
|---|---|---|
| The shared input table `sensitivity` (one row per trade and risk factor) | PLANNED | §3.2 |
| Optional inputs `default-risk` (DRC) and `residual-risk` (RRAO) | PLANNED, computed only when delivered | §3.3, §3.4 |
| The regulatory parameter tables, versioned, as configuration | PLANNED | §6.2 |
| The sensitivity store: factor dictionary, per-trade rows, per-node net sensitivities (Java) | PLANNED | §7 |
| The SBM, DRC and RRAO formulas in a Python engine on the compute pools | PLANNED | §6, §9.6 |
| Contributions by Euler allocation (the gradient handed back to Java) | PLANNED | §6.7 |
| Computed kinds, API, captures, CSV | PLANNED | §9 |
| Retirement of `frtb-sensitivity`: `FRTB-<book>` opens `FRTB BOOK:BOOK-<book>` | PLANNED | §14 |

## 2. The measure in one page

**What it answers:** *how much capital does this set of trades need under the standardised approach, which risk class and scenario
bind, and which trades, books or risk factors drive it?* For market-risk managers and capital teams (the charge by desk, its drivers),
desk heads (what a trade adds), and auditors (every number from its inputs).

**The method in words (MAR21).**

1. Each trade's sensitivities to the prescribed risk factors arrive from upstream: delta (first-order), vega (to implied volatility)
   and the curvature revaluations (the trade repriced with each factor shocked up and down).
2. For a scope, the sensitivities of each risk factor are **netted across its trades** (a plain sum: additive).
3. Each net sensitivity is multiplied by its **risk weight**: the weighted sensitivity WS.
4. Within a **bucket** (a currency for GIRR, a sector and rating class for credit and equity, a commodity group), the weighted
   sensitivities are aggregated with the prescribed correlations ρ into K_b, a square root of a quadratic form.
5. Across buckets of a risk class, the K_b and the bucket sums are aggregated with the correlations γ.
6. Steps 4 and 5 are repeated under three **correlation scenarios** (low, medium, high: ρ and γ scaled). For each scenario the charges
   of every risk class and of delta, vega and curvature are added; the **largest** of the three totals is the SBM charge.
7. The DRC (jump-to-default per obligor, netted, weighted by credit quality) and the RRAO (a percentage of the gross notional of
   exotic and other residual-risk trades) are added.

**Additive and not additive.** Everything up to step 3 is additive over trades and is kept per hierarchy node (net sensitivities per
risk factor). Steps 4 to 6 are not additive and run at query time. The DRC's per-obligor jump-to-default amounts are additive; its
netting and hedge-benefit ratio are not. The RRAO is fully additive.

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
├── sensitivity/      business_date=YYYY-MM-DD/part-*.parquet   one row per trade × risk factor × measure         (PLANNED, shared)
├── default-risk/     business_date=YYYY-MM-DD/                  one row per trade × obligor (DRC)                (PLANNED, optional)
└── residual-risk/    business_date=YYYY-MM-DD/                  one row per trade subject to the RRAO           (PLANNED, optional)
```

Served by `risk-store` (domain `risk`). Physical layout as VaR's ([VAR.md section 3.1](VAR.md#31-where-they-live)); every column the
engines read is promoted (**P**). Retention 7 years (product owner, 2026-10-25). The data-load signal of each table reloads the date
in the engine automatically (product owner, 2026-10-25).

### 3.2 `sensitivity`: one row per trade and risk factor (the shared table)

**Grain:** one row per trade, business date, measure and risk factor (risk class, factor type, qualifier, curve, tenor, underlying
tenor) and grid. **Key:** `SENSITIVITY_KEY` = `<TRADE_ID>~<MEASURE>~<RISK_CLASS>~<FACTOR_TYPE>~<QUALIFIER>~<CURVE>~<TENOR>~<UNDERLYING_TENOR>~<GRID>`
(empty parts kept as empty strings; unique per date). **Partitioning:** `business_date`; sorted by `TRADE_ID` then the key, so one
trade's rows are contiguous (the engine relies on it for streaming, §7.2). **Cadence:** daily after the close; **due:** to be agreed
(open question 1). **Readers:** FRTB (this document), SIMM ([SIMM.md](SIMM.md#3-inputs-from-upstream-engines)), STR's sensitivity
shocks ([STR.md](STR.md#63-sensitivity-based-stress-scenarios-defined-on-the-spot)), P&L explain ([PNL.md](PNL.md#6-the-computation)).

| Column | Type (Delta) | Req. | P | Unit, sign | Meaning | Example |
|---|---|---|---|---|---|---|
| `SENSITIVITY_KEY` | id (`id`) | required | P | | the lake key above | `MX-20000209~delta~GIRR~CURVE~USD~SOFR~5Y~~COMMON` |
| `BUSINESS_DATE` | date (`business_date`) | required | P | | | `2026-09-30` |
| `TRADE_ID` | id | required | P | | the trade | `MX-20000209` |
| `SOURCE_SYSTEM` | text | optional | P | | | `MUREX` |
| `MEASURE` | text (enum) | required | P | | `delta`, `vega`, `curvature-up`, `curvature-down`, `gamma`, `theta`, `base-corr` (§3.2.2) | `delta` |
| `RISK_CLASS` | text (enum) | required | P | | `GIRR`, `CSR_NS`, `CSR_SEC`, `CSR_CTP`, `EQ`, `COMM`, `FX` (FRTB's classes; SIMM maps them, [SIMM.md section 3.2](SIMM.md#32-the-crif-mapping-drishti-columns-and-isda-crif)) | `GIRR` |
| `FACTOR_TYPE` | text (enum) | required | P | | GIRR `CURVE`, `INFLATION`, `XCCY_BASIS`; CSR `SPREAD`; EQ `SPOT`, `REPO`; COMM `PRICE`; FX `SPOT` | `CURVE` |
| `QUALIFIER` | text | required | P | | GIRR, FX: the currency (ISO 4217; FX: the non-USD currency of the pair, the factor being its value in USD); CSR: the issuer (LEI preferred, else the bank's issuer id) or the tranche or index name; EQ: the issuer (ISIN of the stock, or the index); COMM: the commodity (the bank's commodity code) | `USD` |
| `CURVE` | text | optional | P | | GIRR: the curve (`OIS`, `SOFR`, `LIBOR3M`, `ESTR`, …; MAR21.8 *different curves*); CSR: `BOND` or `CDS` (the basis); COMM: the delivery location; empty for others | `SOFR` |
| `TENOR` | text | optional | P | | delta: the risk tenor (GIRR, CSR, COMM; `2W`, `1M`, `3M`, `6M`, `1Y`, `2Y`, `3Y`, `5Y`, `10Y`, `15Y`, `20Y`, `30Y`; `0D` spot for COMM); vega and curvature-free measures: the **option expiry** on the same codes; empty for EQ, FX delta | `5Y` |
| `UNDERLYING_TENOR` | text | optional | P | | GIRR and CSR vega: the residual maturity of the underlying (MAR21.11); empty otherwise | `10Y` |
| `GRID` | text (enum) | required | P | | `COMMON` (a vertex both FRTB and SIMM use), `FRTB` (FRTB only), `SIMM` (SIMM only: `2W`, `1M` IR deltas, SIMM's 12 vega expiries) | `COMMON` |
| `FRTB_BUCKET` | text | optional | P | | the MAR21 bucket (GIRR: the currency; CSR NS `1`–`18`; CSR CTP `1`–`16`; CSR sec `1`–`25`; EQ `1`–`13`; COMM `1`–`11`; FX: the currency); required on `GRID` `COMMON` or `FRTB` rows (or derivable, §4) | `USD` |
| `SIMM_BUCKET` | text | optional | P | | the ISDA SIMM bucket (different definitions: [SIMM.md](SIMM.md#32-the-crif-mapping-drishti-columns-and-isda-crif)); required on `COMMON` or `SIMM` rows | `1` |
| `AMOUNT_USD` | amount (DOUBLE) | required | P | §3.2.1 | the sensitivity in USD in the measure's unit | `-1250.0` |
| `AMOUNT` | amount (DOUBLE) | optional | | `AMOUNT_CURRENCY` | the same in the currency it was computed in (SIMM's CRIF `Amount`) | `-1250.0` |
| `AMOUNT_CURRENCY` | text (ISO 4217) | optional | P | | | `USD` |
| `SHOCK_SIZE` | rate (DOUBLE) | required on curvature rows | P | GIRR, CSR: bp; others: fraction | the shock the curvature revaluation applied (checked against MAR21.98–100, §10) | `170` |
| `IMPLIED_VOL` | rate (DOUBLE) | optional | | GIRR, CSR: bp normal vol; others: fraction (0.25 = 25%) | the option's implied volatility, for converting absolute vol shocks (STR) | `0.22` |
| `STRIKE` | rate (DOUBLE) | optional | | the underlying's units | for display and curvature checks; no engine uses it in 2.9.0 | `4.25` |
| `MATURITY_DATE` | date | optional | | | the trade's or the underlying's maturity (CSR, DRC cross-checks) | `2031-09-30` |
| `PRODUCT_CLASS` | text (enum) | optional | P | | SIMM product class: `RatesFX`, `Credit`, `Equity`, `Commodity` (derived from the trade's product group when empty, [SIMM.md](SIMM.md#62-product-classes)) | `RatesFX` |
| `COLLECT_REGULATIONS`, `POST_REGULATIONS` | text | optional | | | SIMM regimes, comma-separated (CRIF's fields) | `USPR,CFTC` |
| `STATUS` | text | required | P | | `ok`, `proxied` (a proxy factor), `failed` (then the trade has one row, `MEASURE` `delta`, `AMOUNT_USD` null) | `ok` |
| `MODEL_VERSION`, `COMPUTED_AT` | text, timestamp | optional | | | | `murex-3.1.48`, `2026-09-30T23:10:00Z` |
| `BATCH_ID` | id | required | P | | the run, one per source system per date | `B-SENS-2026-09-30-01` |
| `RESTATEMENT` | integer | optional | | | | `0` |

#### 3.2.1 Units and signs of `AMOUNT_USD`

Every amount is the **P&L of the trade, in USD, for the stated move**, gain positive (a long bond has a negative rate delta).

| Risk class, factor type | `delta` | `gamma` | `vega` | `curvature-up`, `curvature-down` | `theta` |
|---|---|---|---|---|---|
| GIRR `CURVE`, `INFLATION`, `XCCY_BASIS`; CSR `SPREAD`; EQ `REPO` | USD per **+1 bp** absolute (PV01, CS01) | USD per (1 bp)²: ½ × gamma × Δbp² is the second-order P&L | USD: σ·∂V/∂σ, the vega times the implied volatility (MAR21.25; the same quantity as SIMM's vega risk) | USD: V(x shocked by `SHOCK_SIZE`) − V(x), the full revaluation, for the whole curve or underlying of the factor (one row per factor, `TENOR` empty) | USD: the P&L of one day passing, nothing else moving |
| EQ `SPOT`, COMM `PRICE`, FX `SPOT` | USD per **+1%** relative move of the price or rate | USD per (1%)² | as above | as above | as above |
| `base-corr` (CSR CTP, SIMM `Risk_BaseCorr`) | USD per +1% absolute move of the base correlation | | | | |

**FRTB's own definitions follow by a constant.** MAR21.8–21.24 define s_k as the change for a 1-unit move (rates: (V(r + 0.0001) −
V(r)) / 0.0001; prices: (V(1.01 x) − V(x)) / 0.01). So s_k = `AMOUNT_USD` × 10,000 for bp rows and × 100 for % rows; with risk weights
stated in % the weighted sensitivity is WS_k = RW_k(%) × 100 × `AMOUNT_USD` (bp rows) or RW_k(%) × `AMOUNT_USD` (% rows). §6.1.

#### 3.2.2 Measures

| `MEASURE` | Used by | Notes |
|---|---|---|
| `delta` | FRTB, SIMM, STR, PNL | every class |
| `vega` | FRTB, SIMM, STR, PNL | per option expiry (and underlying tenor for GIRR, CSR) |
| `curvature-up`, `curvature-down` | FRTB | per factor (a curve or an underlying), not per tenor; SIMM's curvature is derived from vega ([SIMM.md section 6.4](SIMM.md#64-vega-and-curvature)) |
| `gamma` | STR, PNL | the diagonal second order per factor (per tenor for curves); cross-gammas are not delivered |
| `theta` | PNL | one row per trade (`RISK_CLASS` the trade's main class, `FACTOR_TYPE` `TIME`, `QUALIFIER` empty) |
| `base-corr` | SIMM (credit qualifying), FRTB CSR CTP (not in 2.9.0) | |

#### 3.2.3 Rows per trade (typical)

| Product | Delta rows | Vega rows | Curvature rows | Gamma, theta | Total |
|---|---|---|---|---|---|
| Interest-rate swap, one currency, two curves | 10–14 tenors × 2 curves | | | 1 theta | about 25 |
| Swaption | about 20 | 5 expiries × 5 underlying tenors = 25 (FRTB) + SIMM expiries 12 | 2 | 10 gamma, 1 theta | about 70 |
| FX forward | 1 FX, 2 × 10 GIRR | | | 1 | about 22 |
| Equity option | 1 spot, 1 repo | 5 (FRTB) + up to 12 (SIMM) | 2 | 2 | about 23 |
| Commodity swap | up to 11 tenors | | | 1 | about 12 |
| Bond, CDS | 5 CSR tenors + 10 GIRR | | | 1 | about 16 |

A large bank's average is about **20 rows a trade**: 1 M trades give about 20 M rows a date (about 300 MB compressed in Parquet,
estimate; 7 years about 525 GB).

### 3.3 `default-risk`: the DRC inputs (optional)

**Grain:** one row per trade, obligor and business date, for every trade with default risk (bonds, CDS, equities, credit derivatives).
**Key:** `<TRADE_ID>~<OBLIGOR>~<SENIORITY>`. Delivered only by banks that want the DRC; without it the answer says *DRC not computed:
no default-risk inputs* (never 0).

| Column | Type | Req. | Unit, sign | Meaning | Example |
|---|---|---|---|---|---|
| `TRADE_ID`, `BUSINESS_DATE` | id, date | required | | | `CLY-4000123`, `2026-09-30` |
| `OBLIGOR` | id | required | | the issuer or reference entity (LEI preferred) | `5493001KJTIIGC8Y1R12` |
| `DRC_CLASS` | text | required | | `NON_SEC`, `SEC_NON_CTP`, `CTP` | `NON_SEC` |
| `DRC_BUCKET` | text | required | | NON_SEC: `CORPORATE`, `SOVEREIGN`, `LOCAL_GOVT` (MAR22.21) | `CORPORATE` |
| `SENIORITY` | text | required | | `COVERED`, `SENIOR`, `NON_SENIOR`, `EQUITY`, `DEFAULTED` | `SENIOR` |
| `CREDIT_QUALITY` | text | required | | `AAA`, `AA`, `A`, `BBB`, `BB`, `B`, `CCC`, `UNRATED`, `DEFAULTED` | `BBB` |
| `NOTIONAL_USD` | amount | required | USD; **positive long** default exposure (loses on default), negative short | the bond-equivalent notional (MAR22.12) | `10000000.0` |
| `MARKET_VALUE_USD` | amount | required | USD | the market value of the exposure | `9500000.0` |
| `MATURITY_DATE` | date | required | | for the maturity scaling (MAR22.17) | `2029-06-15` |
| `LGD` | rate | optional | fraction | an override of MAR22.13's LGD (documented exceptions only) | `0.75` |
| `BATCH_ID`, `STATUS` | id, text | required | | | |

### 3.4 `residual-risk`: the RRAO inputs (optional)

| Column | Type | Req. | Meaning | Example |
|---|---|---|---|---|
| `TRADE_ID`, `BUSINESS_DATE` | id, date | required | | `MX-20000311`, `2026-09-30` |
| `RRAO_TYPE` | text | required | `EXOTIC` (MAR23.2, an exotic underlying: weather, longevity …) or `OTHER` (MAR23.3, gap, correlation or behavioural risk) | `OTHER` |
| `GROSS_NOTIONAL_USD` | amount | required | the trade's gross notional, USD, absolute | `25000000.0` |
| `REASON` | text | optional | why the trade is in scope | `path-dependent barrier` |
| `BATCH_ID` | id | required | | |

### 3.5 Delivery and completeness

| Item | Contract |
|---|---|
| Cadence | daily after the close; each table one `BATCH_ID` per source system and date |
| Due time | **to be agreed** (open question 1) |
| Complete when | every live trade of the date has at least one `sensitivity` row (`ok`, `proxied` or `failed`); a trade with no risk (a matured cash flow) sends one `delta` row of 0 on its main class |
| Consistency | `sensitivity` and `valuation` ([TVAL.md](TVAL.md#32-valuation-one-row-per-trade-per-business-date-planned)) of a date come from the same market data set (`MARKET_DATA_SET` on the batch, checked) |

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `trade` (TRD) | `TRADE_ID` | live status, book, product group (→ `PRODUCT_CLASS` when empty), legal entity, counterparty |
| `risk-hierarchy` (HIER) | `NODE_ID` | nodes and their books (the per-node aggregates) |
| `issuer` (ISS), `lei-entity` (LEI) | `QUALIFIER`, `OBLIGOR` | sector, rating, region and country to **derive** `FRTB_BUCKET` for CSR and EQ when the row has none (open question 3), and to validate it |
| `equity` (market-data pack) | ISIN | market capitalisation (large or small cap, EQ buckets) and economy (advanced or emerging) |
| `fx-rate` | pair, date | `AMOUNT` → `AMOUNT_USD` cross-check |
| FRTB parameter tables | version | §6.2 |

## 5. Conventions specific to FRTB

| Convention | Rule |
|---|---|
| Sensitivity sign | the trade's P&L for the stated up-move, gain positive (§3.2.1) |
| Charges | reported **positive** (capital); a contribution may be negative (a hedge) |
| Currency | USD (the bank's reporting currency is a setting, open question 4); no FX conversion inside the formulas |
| Tenors | text codes, mapped to years for the correlations: `2W` = 14/365, `1M` = 1/12, `3M` = 0.25, `6M` = 0.5, `1Y` = 1 … |
| Specified currencies | GIRR risk weights divided by √2 for EUR, USD, GBP, AUD, JPY, SEK, CAD and the reporting currency when `girr-specified-currency-reduction: true` (MAR21.44, the bank's choice) |
| Rounding | figures rounded to whole USD in answers; all arithmetic in float64 |

## 6. The computation

### 6.1 Weighted sensitivities (MAR21.4)

For a scope with trade set *S*, every risk factor *k* (one row of the factor dictionary, §7.1) and measure:

```
s_k   = Σ_{t∈S} AMOUNT_USD_{t,k} × u_k             u_k = 10,000 for bp rows, 100 for % rows; 1 for vega
WS_k  = RW_k × s_k                                  RW_k as a fraction (1.1% = 0.011)
```

### 6.2 Parameters: the regulatory tables

Shipped as versioned configuration, never in code, one file per regulation version and jurisdiction overlay (proposed:
`config/frtb/bcbs-d457.yaml`, overlays `eu-crr3.yaml`, `us-npr.yaml`, `osfi-car.yaml` as each is agreed; open question 2). Each value
carries its paragraph, and a test pins every value against the file. Excerpts (BCBS d457 / MAR21):

| Table | Values |
|---|---|
| GIRR delta RW (MAR21.42) | 0.25y 1.7%, 0.5y 1.7%, 1y 1.6%, 2y 1.3%, 3y 1.2%, 5y 1.1%, 10y 1.1%, 15y 1.1%, 20y 1.1%, 30y 1.1%; inflation and cross-currency basis 1.6% |
| GIRR ρ (MAR21.46–49) | same curve: max(e^{−θ\|T_k − T_l\| / min(T_k, T_l)}, 40%), θ = 3%; different curves: that × 99.9%; inflation vs a curve 40%; basis vs anything 0% |
| GIRR γ (MAR21.50) | 50% across currencies |
| FX delta RW (MAR21.87–88) | 15% (÷ √2 for the liquid pairs of MAR21.88 when chosen); γ 60% (MAR21.89) |
| Commodity delta RW (MAR21.82) | buckets 1–11: 30%, 35%, 60%, 80%, 40%, 45%, 20%, 35%, 25%, 35%, 50% |
| Commodity ρ (MAR21.83) | ρ_cty × ρ_tenor × ρ_basis; ρ_tenor 99%, ρ_basis 99.9% (different locations); ρ_cty by bucket 55%, 95%, 40%, 80%, 60%, 65%, 55%, 45%, 15%, 40%, 15%; γ 20% (bucket 11: 0%) |
| CSR NS, CSR sec, CSR CTP, EQ | the RW, ρ and γ tables of MAR21.51–21.81 (name, tenor and basis factors; rating and sector γ), in the same file |
| Vega RW (MAR21.92–93) | min(RW_σ × √(LH / 10), 100%), RW_σ = 55%; LH: GIRR 60, CSR 120, EQ large cap 20, EQ small cap and other 60, COMM 120, FX 40 days |
| Vega ρ (MAR21.94–96) | GIRR, CSR: min(ρ_option × ρ_underlying, 1), ρ = e^{−α\|T_k − T_l\| / min(T_k, T_l)}, α = 1%; others: min(ρ_delta × ρ_option, 1) |
| Curvature (MAR21.98–101) | shock = the class's delta RW (GIRR, CSR: the largest tenor RW as a parallel shift); FX curvature RW ÷ 1.5 when chosen (MAR21.98); correlations ρ² and γ² |
| Correlation scenarios (MAR21.6) | high: min(1.25 × c, 100%); medium: c; low: max(2c − 100%, 75% × c), for every ρ and γ |
| DRC RW (MAR22.24) | AAA 0.5%, AA 2%, A 3%, BBB 6%, BB 15%, B 30%, CCC 50%, unrated 15%, defaulted 100% |
| DRC LGD (MAR22.13) | equity and non-senior 100%, senior 75%, covered bonds 25% |
| RRAO (MAR23.8) | exotic 1.0%, other 0.1% of gross notional |

### 6.3 Delta and vega: intra-bucket, inter-bucket (MAR21.4)

For each risk class, measure (delta or vega) and correlation scenario *c*:

```
K_b  = sqrt( max( 0,  Σ_k WS_k² + Σ_k Σ_{l≠k} ρ^c_kl WS_k WS_l ) )                    every bucket b
S_b  = Σ_{k∈b} WS_k
M    = Σ_b K_b² + Σ_b Σ_{c≠b} γ^c_bc S_b S_c
if M < 0:  S_b = max( min(S_b, K_b), −K_b )   and recompute M                           (MAR21.4(5)(b), the alternative specification)
Charge = sqrt(M)
```

**Structured sums.** ρ factorises within a bucket (name × tenor × basis for CSR and COMM; a constant within an equity bucket), so
the quadratic form is computed without a dense n × n matrix: grouping the factors by (tenor, basis) gives
Σ_kl ρ_kl WS_k WS_l = Σ_{(t,b),(t',b')} ρ_tenor ρ_basis [ρ_name A_{t,b} A_{t',b'} + (1 − ρ_name) Σ_names W_{n,t,b} W_{n,t',b'}], O(n · m²)
with m ≤ 10 tenor-basis pairs. A CSR bucket of 2,000 issuers and 5 tenors is about 10⁵ operations, not 10⁸.

### 6.4 Curvature (MAR21.5)

For each factor *k* (a curve or an underlying) and trade set *S*, with δ_k = Σ_{tenors of k} s (the factor's delta, per unit):

```
CVR_k⁺ = −( Σ_t curvature-up_{t,k}   − RW^curv_k × δ_k )
CVR_k⁻ = −( Σ_t curvature-down_{t,k} + RW^curv_k × δ_k )
K_b^±  = sqrt( max( 0,  Σ_k max(CVR_k^±, 0)² + Σ_k Σ_{l≠k} ρ²_kl CVR_k^± CVR_l^± ψ(CVR_k^±, CVR_l^±) ) )      ψ = 0 when both < 0, else 1
K_b    = max(K_b⁺, K_b⁻)  (the side chosen; on a tie, the side with the larger Σ CVR)
S_b    = Σ_k CVR_k^{side}
Curv   = sqrt( max( 0,  Σ_b K_b² + Σ_b Σ_{c≠b} γ²_bc S_b S_c ψ(S_b, S_c) ) )
```

The curvature inputs (Σ_t of the revaluations) and δ_k are **additive** over trades, so CVR_k of a scope is computed from per-node
sums; the max(·, 0), ψ and the square roots are not.

### 6.5 The SBM total and the binding scenario (MAR21.6–7)

```
SBM^c = Σ_{risk classes} ( Delta^c + Vega^c + Curvature^c )      c ∈ {low, medium, high}
SBM   = max_c SBM^c                                               the binding scenario c* is reported
```

### 6.6 DRC and RRAO (MAR22, MAR23)

**DRC (non-securitisation; securitisation and CTP follow the same steps with their own tables, not in 2.9.0, open question 5).**

```
JTD_t      = max(LGD × N + (MV − N), 0)  for a long;  min(LGD × N + (MV − N), 0) for a short          (MAR22.11)
scaled     = JTD_t × min( max(M_years, 0.25), 1 )                                                       (MAR22.17)
net_o,s    = Σ_{t∈S} scaled_{t,o,s}    per obligor o and seniority s;  then offset long against short of the same obligor
             when the short's seniority is the same or lower                                            (MAR22.19)
HBR_b      = Σ net long / ( Σ net long + Σ |net short| )                                                 (MAR22.25)
DRC_b      = max( Σ RW × net long − HBR_b × Σ RW × |net short|, 0 )
DRC        = Σ_b DRC_b
```

The per-(obligor, seniority) sums of scaled long and of scaled short JTD are additive and kept per node; the offset, HBR and floor run
at query time. **RRAO** = Σ_{t∈S} (1.0% × exotic notional + 0.1% × other notional): fully additive.

**FRTB SA total** = SBM + DRC + RRAO (each reported apart; a missing DRC or RRAO input is *not computed*, not zero).

### 6.7 Contributions

The SBM is positively homogeneous of degree 1 in the weighted sensitivities (square roots of quadratic forms, maxima), so each part's
**Euler contribution** is exact where the charge is differentiable:

```
contribution(part) = Σ_k WS_k(part) × ∂SBM^{c*} / ∂WS_k        (the gradient at the scope, in the binding scenario)
Σ_parts contribution = SBM^{c*}                                  when the parts partition the scope
```

The Python engine returns the gradient g_k per factor; Java computes any number of parts' contributions as Σ_k WS_k(part) g_k from
their own net sensitivities (one sparse dot product a part). Floors (`max(0, …)`, a curvature side switch, the alternative
specification) make the gradient one-sided at a kink; the answer then says *contributions scaled to add up* and scales them, as
VaR's component VaR. Also reported: **standalone** (the part's own SBM) and **incremental** (SBM(scope) − SBM(scope − part), recomputed;
at most `max-incremental` parts).

### 6.8 Parameters (the `drishti.frtb` settings, proposed)

| Setting | Default | Meaning |
|---|---|---|
| `drishti.frtb.enabled` | `true` | the engine and its API |
| `drishti.frtb.regulation` | `bcbs-d457` | the parameter file (§6.2) |
| `drishti.frtb.overlay` | empty | a jurisdiction overlay |
| `drishti.frtb.girr-specified-currency-reduction` | `false` | MAR21.44 |
| `drishti.frtb.fx-curvature-scalar` | `1.0` | 1.5 to apply MAR21.98's option |
| `drishti.frtb.drc` | `auto` | `auto` (when `default-risk` has rows), `off` |
| `drishti.frtb.rrao` | `auto` | the same for `residual-risk` |
| `drishti.frtb.max-parts` | `5000` | parts of a contribution table |
| `drishti.frtb.max-incremental` | `50` | parts with an incremental figure |
| `drishti.frtb.hot-dates` | `2` | dates loaded |
| `drishti.frtb.access` | `{}` | per role and node, VaR's model |

## 7. What Drishti builds

### 7.1 The factor dictionary (shared)

Every distinct risk factor of the date: (`RISK_CLASS`, `FACTOR_TYPE`, `QUALIFIER`, `CURVE`, `TENOR`, `UNDERLYING_TENOR`, `MEASURE`,
`GRID`) → an int **factor ordinal**, with its attributes as int codes (`FRTB_BUCKET`, `SIMM_BUCKET`, `PRODUCT_CLASS`, tenor in years,
unit). One dictionary per date, shared by FRTB, SIMM, STR and P&L explain. A large bank: about 100,000–250,000 factors (GIRR 30
currencies × 3 curves × 12 tenors; CSR 5,000 issuers × 5 tenors × 2 bases; EQ 5,000 names × spot, repo and vega expiries; COMM 200 ×
11 tenors; FX 50; their vega grids): about 20 MB with the dictionaries.

### 7.2 The sensitivity store (shared)

| Structure | Content | Size, 1 M trades × 20 rows |
|---|---|---|
| Rows by trade | compressed sparse rows over the date's trade ordinals (VaR's order): `offsets[trade]` (int64), `factor[row]` (int32), `amount[row]` (float64) | 20 M × 12 B + 8 MB = **248 MB** |
| Node aggregates | per hierarchy node, a sparse vector of net sensitivities: sorted `factor[]` (int32) and `amount[]` (float64), summed bottom-up at load | about 12 B per (node, factor) present: 5,000 nodes, about 150 MB (books dominate) |
| Curvature aggregates | per node, per curvature factor: Σ up, Σ down (additive) | inside the node aggregates (their own measures) |
| DRC aggregates | per node, per (obligor, seniority): Σ scaled long, Σ scaled short, with the obligor's bucket and quality | about 40 B per (node, obligor) present |
| RRAO aggregates | per node: Σ exotic notional, Σ other notional | 16 B a node |
| Netting-set aggregates (SIMM) | per netting set, the same sparse vector | [SIMM.md section 7](SIMM.md#7-what-drishti-builds) |

**A scope's net sensitivities** use VaR's planner ([VAR.md section 7.1](VAR.md#71-as-built-one-business-date-in-memory-varbook)): node
aggregates for whole nodes, minus the rows of missing trades, plus the rows of extra trades, accumulated into a dense float64 array
indexed by factor ordinal (2 MB at 250,000 factors; one per thread), then compacted to the non-zero factors. The order of additions
is fixed (node order, then ordinal order), so the same scope gives the same bits.

**Memory tiering.** At 250 MB a date plus aggregates, no float32 copy is needed; history dates are opened lazily from the lake as
VaR's (§7.3 of VAR.md). Captures keep the scope's net sensitivities, so a capture is reproducible without the lake.

### 7.3 Load

At the date's first request or the load signal: stream the `sensitivity` rows (promoted columns only, no document parsed), map each
to its factor ordinal (creating it), place it under the trade's ordinal (rows of an unknown trade are kept under an *unmapped* ordinal
and counted), sum the node aggregates in one pass per book then up the tree. About 6 s for 20 M rows (estimate; measured in the build).

## 8. Scopes

Every scope of [VAR.md section 8](VAR.md#8-scopes) and its planned words, plus `INDEX:` and `TRDR:` of
[TRADING_GROUPINGS.md](TRADING_GROUPINGS.md#81-words-added-proposed). The net sensitivity of a factor over a scope is additive and
resolved exactly from aggregates and runs; the charge is not and is computed per scope. **Positions:** a position's sensitivities are
the sum of its trades' (positions derived as in [VAR.md section 3.7](VAR.md#37-positions-derived-by-drishti-as-built)).

## 9. What Drishti answers

### 9.1 The API (proposed)

Base `/api/v1/frtb`, JSON, as the caller; `scope`, `asOf` as VaR.

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf`, `scenario` (`binding` default, `low`, `medium`, `high`) | the headline (§9.2) |
| `GET /scope/classes` | `scope`, `asOf` | per risk class × measure × scenario: the charge; the binding scenario marked |
| `GET /scope/buckets` | `scope`, `asOf`, `riskClass`, `measure`, `scenario` | per bucket: K_b, S_b, factors, the bucket's share of the class charge |
| `GET /scope/factors` | `scope`, `asOf`, `riskClass`, `bucket`, `measure`, `page`, `size` | per factor: net s, RW, WS, gradient, contribution |
| `GET /scope/contributions` | `scope`, `asOf`, `by` (`child`, `book`, `position`, `trade`, `desk`) | per part: standalone, contribution (Euler), incremental (first `max-incremental`), pct |
| `GET /scope/drc`, `GET /scope/rrao` | `scope`, `asOf` | per bucket and obligor; per type |
| `GET /scope/sensitivities.csv` | `scope`, `asOf`, `riskClass` | the scope's net sensitivities (one line per factor) |
| `POST /what-if` | body `{scope, add: [sensitivity rows of a trade not booked], remove: [trade ids]}` | the headline with and without, and the change (a private sandbox, REACTIVE_CONNECTOR's `sandbox-ref`) |
| `POST /captures` … `GET /compare` | as VaR ([VAR.md section 9.6](VAR.md#96-captures)) | captures `FRCAP-<yyyymmdd>-<n>`, with the scope's net sensitivities |
| `GET /status`, `POST /reload` | | as VaR |

### 9.2 The headline

| Field | Type | Meaning |
|---|---|---|
| `scope`, `canonical`, `kind`, `label`, `node`, `businessDate` | string | as VaR |
| `total` | integer | SBM + DRC + RRAO (those computed) |
| `sbm`, `drc`, `rrao` | integer or null | null: not computed (no inputs) |
| `bindingScenario` | string | `low`, `medium` or `high` |
| `sbmByScenario` | object | `{low, medium, high}` |
| `byClass` | list | `{riskClass, delta, vega, curvature, total}` in the binding scenario |
| `sumStandalone`, `diversification` | integer | over the default parts |
| `provenance` | object | VaR's fields plus `sensitivities` (`sensitivity · batches … · data version …`), `regulation` (`bcbs-d457 · file sha …`), `factors` (count), `rowsRead`, `proxied`, `failed`, `missingTrades` (live trades without a row), `grid` (`COMMON+FRTB`) |

### 9.3 The computed kinds and their records

| Kind | Id | Record |
|---|---|---|
| `frtb-charge` | the canonical scope (parameters after `~`) | `SCOPE`, `CANONICAL`, `BUSINESS_DATE`, `TOTAL`, `SBM`, `DRC`, `RRAO`, `BINDING_SCENARIO`, `SBM_LOW`, `SBM_MEDIUM`, `SBM_HIGH`, `BY_CLASS[]` (`RISK_CLASS`, `DELTA`, `VEGA`, `CURVATURE`, `TOTAL`), `CHILDREN[]` (`KEY`, `SCOPE`, `LABEL`, `STANDALONE`, `CONTRIBUTION`, `PCT`), `PROVENANCE` |
| `frtb-bucket` | `<scope>~class=<RC>~measure=<m>` | `BUCKETS[]` (`BUCKET`, `K`, `S`, `FACTORS`, per scenario) |
| `sensitivity-net` | the canonical scope | the scope's net sensitivities: `FACTORS[]` (factor attributes, `AMOUNT_USD`), `TRADES`, `PROVENANCE`; read by FRTB, SIMM and STR's Python engines (§9.6) |

The retired kind name `frtb-sensitivity` is not reused (README section 14's guard test).

### 9.4 Captures and CSV

As VaR's captures, plus the scope's net sensitivities (every factor with its amount), the parameter file's name and hash, and the
gradient. CSV parts: `factors` (`risk_class,factor_type,qualifier,curve,tenor,underlying_tenor,measure,bucket,s,rw,ws,gradient,contribution`),
`classes`, `contributions`.

### 9.5 The reactive connectors (proposed)

```yaml
# config/connectors/sensitivity-net.yaml                   (proposed, 2.9.0: Java, shared by FRTB, SIMM, STR)
plugin: reactive
mode: reactive
description: The net sensitivities of any scope, per risk factor, from the sensitivity store
kinds:
  sensitivity-net:
    compute: sensitivity-net
    params:
      grid:  { type: enum, options: [FRTB, SIMM, ALL], default: FRTB }     # FRTB = COMMON + FRTB rows
      class: { type: text, default: "" }                                     # one risk class, or all
    result: { required: [SCOPE, BUSINESS_DATE, FACTORS] }
engine:
  type: java
  entry: com.ash.drishti.compute.measure.sens.SensitivityNetEngine          # proposed
  version: "1.0"
triggers:
  - { on: data-version, kinds: [sensitivity, trade, risk-hierarchy], affects: all }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 30s, wait: 50ms, max-inflight-per-user: 4 }
```

```yaml
# config/connectors/frtb-engine.yaml                       (proposed, 2.9.0)
plugin: reactive
mode: reactive
description: The FRTB standardised charge (SBM, DRC, RRAO) of any scope
kinds:
  frtb-charge:
    compute: frtb-charge
    params:
      scenario:   { type: enum, options: [binding, low, medium, high], default: binding }
      regulation: { type: text, default: bcbs-d457 }
      sandbox:    { type: sandbox-ref, required: false }
    result: { required: [SCOPE, BUSINESS_DATE, TOTAL, SBM, BINDING_SCENARIO], types: { TOTAL: number, SBM: number } }
  frtb-bucket:
    compute: frtb-bucket
    params:
      class:   { type: enum, options: [GIRR, CSR_NS, CSR_SEC, CSR_CTP, EQ, COMM, FX], required: true }
      measure: { type: enum, options: [delta, vega, curvature], default: delta }
reads:
  - { kind: sensitivity-net, as: net, id: "{id}~grid=FRTB" }
  - { kind: default-risk-net, as: drc, id: "{id}" }          # proposed Java kind: per (obligor, seniority) sums
  - { kind: residual-risk-net, as: rrao, id: "{id}" }        # proposed Java kind: two sums
engine:
  type: python
  entry: drishti_risk.frtb:FrtbEngine                        # proposed package compute/drishti-risk
  pool: risk
  version: "1.0"
triggers:
  - { on: data-version, kinds: [sensitivity, default-risk, residual-risk], affects: all }
  - { on: write-back, kinds: [trade], affects: subscribed }
coalesce: { quiet: 500ms, max-wait: 5s }
cache: { results: 1000, ttl: 1d }
limits: { timeout: 60s, wait: 100ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-risk.lock } }
```

### 9.6 The Python engine interface (proposed)

```python
# compute/drishti-risk/src/drishti_risk/frtb.py                  (proposed, 2.9.0)
from drishti_compute import Engine, Request, Inputs, Progress, Result

class FrtbEngine(Engine):
    kinds = ("frtb-charge", "frtb-bucket")

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        net = inputs.arrow("net")      # one row per factor: RISK_CLASS, FACTOR_TYPE, QUALIFIER, CURVE, TENOR, UNDERLYING_TENOR,
                                       # MEASURE, GRID, FRTB_BUCKET, TENOR_YEARS, UNDERLYING_YEARS, UNIT, AMOUNT_USD (net), FACTOR (ordinal)
        drc = inputs.arrow("drc")      # OBLIGOR, SENIORITY, DRC_CLASS, DRC_BUCKET, CREDIT_QUALITY, LONG_JTD, SHORT_JTD
        rrao = inputs.arrow("rrao")    # EXOTIC_NOTIONAL, OTHER_NOTIONAL
        params = load_parameters(req.params["regulation"])      # the versioned tables of §6.2
        ...
        return Result(rows=[headline], tables={"classes": ..., "buckets": ..., "factors": ...})   # factors carry WS and GRADIENT
```

| Input (Arrow) | Rows | Typical size |
|---|---|---|
| `net` | the scope's non-zero factors | desk: 2,000–20,000; bank: up to 250,000 (about 25 MB) |
| `drc` | (obligor, seniority) present | up to 20,000 |
| `rrao` | 1 | |

| Output | Content |
|---|---|
| `rows` | the headline of §9.3 |
| `tables.classes` | risk class × measure × scenario charges |
| `tables.buckets` | per bucket K_b, S_b per scenario; curvature side |
| `tables.factors` | per factor WS and the gradient ∂SBM/∂WS in the binding scenario (Java computes contributions from it, §6.7) |

Deterministic (no random numbers), pure (no files, no network). A what-if adds the sandbox's rows to `net` before the formula.

### 9.7 Official end-of-day captures and the published table

When a date's `sensitivity` (and, when delivered, `default-risk`, `residual-risk`) rows are complete, Drishti captures every node of
both hierarchies as the official record (VaR's mechanism, [VAR.md section 9.6](VAR.md#96-captures)) and publishes it to
`${DRISHTI_DELTA_ROOT}/risk-results/frtb-official/` ([README.md section 11](README.md#11-what-drishti-answers-computed-kinds-the-api-captures),
product owner 2026-10-25): one row per node per business date, the shared columns (`BUSINESS_DATE`, `HIERARCHY`, `NODE_ID`,
`NODE_NAME`, `PARENT_ID`, `LEVEL`, `CAPTURE_ID`, `RESULT_VERSION`, `INPUT_VERSIONS`, `COMPUTED_AT`, `CONTRACT_VERSION`) and FRTB's
figures:

| Column | Type | Meaning |
|---|---|---|
| `TOTAL` | DOUBLE | SBM + DRC + RRAO (those computed), USD |
| `SBM`, `SBM_LOW`, `SBM_MEDIUM`, `SBM_HIGH` | DOUBLE | §6.5 |
| `BINDING_SCENARIO` | STRING | `low`, `medium` or `high` |
| `DRC`, `RRAO` | DOUBLE | null when not computed (no inputs) |
| `GIRR_DELTA`, `GIRR_VEGA`, `GIRR_CURVATURE`, and the same three for `CSR_NS`, `CSR_SEC`, `CSR_CTP`, `EQ`, `COMM`, `FX` | DOUBLE | each risk class's charges in the binding scenario (21 columns) |
| `STANDALONE_SUM`, `DIVERSIFICATION` | DOUBLE | the sum of the children's SBM, and that sum minus `SBM` |
| `REGULATION` | STRING | the parameter file and overlay (`bcbs-d457`) with its hash |
| `TRADE_COUNT`, `FACTOR_COUNT`, `MISSING_TRADES` | BIGINT | members, non-zero factors, live trades without sensitivities |

No trade, position or factor rows are published; the capture keeps the node's net sensitivities for audit.

## 10. Validation, errors and data quality

README section 10 holds; the codes below are **proposed** (range `DRS-532x` for FRTB and the shared sensitivity checks).

| Check | Rule | Outcome |
|---|---|---|
| Enums | `MEASURE`, `RISK_CLASS`, `FACTOR_TYPE`, `GRID` in their lists; the pair (`RISK_CLASS`, `FACTOR_TYPE`) allowed | row rejected |
| Tenors | `TENOR`, `UNDERLYING_TENOR` on the grid of the row's `GRID` and class | row rejected (`DRS-5321` *tenor 7Y is not a vertex of GIRR*) |
| Bucket | `FRTB_BUCKET` present (or derivable) on `COMMON`/`FRTB` rows; consistent with the issuer master | row kept, flagged; the charge uses the delivered bucket; counted `bucketMismatch` |
| Unique | one row per `SENSITIVITY_KEY` per date | load fails |
| Finite | `AMOUNT_USD` finite (null only on `failed`) | row rejected |
| Curvature | both `curvature-up` and `curvature-down` per (trade, factor); `SHOCK_SIZE` within 1% of the regulatory shock | the factor's curvature excluded for that trade, counted `curvatureIncomplete` (`DRS-5322`) |
| Units | `AMOUNT_CURRENCY` with `AMOUNT` × FX ≈ `AMOUNT_USD` (0.1%) | flagged |
| Completeness | live trades of the date with no row | `ready` but *incomplete*; `missingTrades` per scope |
| One run | one `BATCH_ID` per source system and date; the same market data set as `valuation` | that system's date not loaded (`DRS-5323` *sensitivities and valuations of <date> are of different runs*) |
| Parameters | the parameter file parses and every table is complete | the engine does not start (`DRS-5324` at startup, in the admin page) |
| Requests | bad `scenario`, `riskClass`, `by` | `400 DRS-5001` |
| Forbidden | engine off, kind refused, aggregate-only probing | `403 DRS-5002` |
| Python pool | compute off, not approved, busy | `DRS-9202`, `DRS-9203` (existing reactive codes) |

## 11. Entitlements and audit

VaR's model ([VAR.md section 11](VAR.md#11-entitlements-and-audit)) with `drishti.frtb.access` and the gate kind `sensitivity`. An
`aggregate` role sees the headline, classes and buckets of its nodes, never a factor table filtered to one trade, trade contributions,
or a CSV of a scope below its nodes. **Audit:** `frtb-view` (every headline of more than 10,000 trades or of a node), `frtb-capture`,
`frtb-what-if`, `frtb-export`.

## 12. Performance and sizing

| Operation | Target (p95, warm, 1 M trades, 20 M rows) |
|---|---|
| headline of a desk node (aggregate → Python) | 300 ms |
| headline of the bank | 1 s |
| an ad-hoc scope of 100,000 trades | 1 s (Java 30 ms to sum 2 M rows; the rest Python) |
| contributions of 5,000 parts | 500 ms (sparse dot products in Java) |
| what-if of one trade on a desk | 400 ms |
| load of a date | 10 s |
| memory per date | about 0.5 GB (store and aggregates) |

**Planned benchmark:** `FrtbScaleBenchmark` with 1 M synthetic trades, 20 rows each, 5,000 nodes; reports the load, the planner, the
Python formula at bank and desk scope, and the contribution table.

## 13. Test data and generators

### 13.1 Planned test data

| Item | Plan |
|---|---|
| Folders | `data/testdata/market-risk/sensitivity/cob-<date>.jsonl.gz` (one file per date, split in parts of about 1.2 MB), `default-risk/`, `residual-risk/` |
| Dates | 2026-09-29 and 2026-09-30 (P&L explain needs T−1, [PNL.md section 13](PNL.md#13-test-data-and-generators)) |
| Trades | the live set of the VaR generator (about 9,572; ≤ 10,000 locally) |
| Generator | `tools/packgen/risk/sensitivities.py` (proposed), run by `tools/testdata/make_testdata.py`; deterministic (text-seeded `Random`) |
| **Consistency with VaR** | each trade's deltas are its exposures to the VaR factor model of `tools/packgen/var/factors.py`: the generator gives each instrument a factor key (risk class, qualifier, tenor) and a delta such that **delta × the factor's move = the linear part of the trade's VaR path**; options get vega, gamma and curvature from the same short-gamma term. Then FRTB, SIMM, STR (sensitivity-based) and P&L explain read the same economics as VaR |
| Buckets | FRTB and SIMM buckets from the issuer and commodity masters; a few deliberate mismatches in a fixture (not in the demo data) |
| Rows | about 10 per trade: about 100,000 rows a date, about 2 MB compressed |
| DRC, RRAO | DRC rows for the credit and fixed-income trades (issuer, seniority from the bond master, rating); RRAO rows for the structured desk's exotics |
| `--check` | as VaR's generator |

### 13.2 How the retired figures were made

`tools/packgen/banking/data_results.py`, `market_results`, per book *b* of the banking generator's trades:

| Field | Rule |
|---|---|
| `DELTA_CHARGE` | `book_var(b) × U(1.1, 1.6)`, with `book_var = 2.33 × sqrt(Σ_trades (|MTM| × 0.004 + Σ|risk numbers| × 0.6)²)`: no risk weights, no correlations, absolute values |
| `VEGA_CHARGE`, `CURVATURE_CHARGE` | delta × U(0.1, 0.4) and delta × U(0.05, 0.2) |
| `TOTAL` | delta + vega + curvature (a plain sum, not a scenario maximum) |
| `SENSITIVITIES[]` | per class the book holds × buckets `"1"`, `"2"`, `"3"` × `Delta`, `Vega`: `round(N(0, delta / 20))`, random draws unrelated to the charge or the trades |
| `BY_CLASS[]` | the total split **equally** between the classes the book holds |
| `RISK_CLASS` | from the trade's asset class (`TR_CLASS`: Rates, Inflation, Money market, Securities financing → GIRR; Fixed income, Credit → CSR non-securitisation; Equity and Structured → Equity …) |

The test data recomputes the same rules over the 15,000-trade universe whose cloned amounts are scaled by 0.2 to 5
(`tools/testdata/universe.py`), so `FRTB-COMM-1` reads `TOTAL` 16,165,538 in the sample and 738,984,139 in the test data.

### 13.3 What the current data shows (checked 2026-10-25)

- 30 records, one per book, dated only by the snapshot of 2026-07-08 (every later file is empty).
- GIRR buckets are `"1"`, `"2"`, `"3"`: a GIRR bucket is a **currency** (MAR21.39); the buckets cannot be mapped.
- The listed sensitivities cannot reproduce the charge (they are drawn after it), and `BY_CLASS` is an equal split.
- The banking generator's trades carry `sensitivities` rows (`data_trades.py`: the product's risk numbers spread over tenor buckets
  with weights 1, 2, 3 …); the production-shaped trade master (`trade` contract) has no sensitivity field, so the Calc snippet
  `frtb_girr_delta.py` that reads `SENSITIVITIES` from trade documents finds none on real-shaped trades.

## 14. Retiring the stored version

### 14.1 Id mapping

| Old | New |
|---|---|
| `FRTB-<DESK>-<n>` (`FRTB-COMM-1`, the book `BOOK-COMM-1`) | `FRTB BOOK:BOOK-COMM-1` (`/v/frtb-charge/BOOK:BOOK-COMM-1`) |
| `/v/frtb-sensitivity/<id>` | the same, by redirect |
| an id with no matching book | `FRTB BANK` with a notice |

### 14.2 What is removed in 2.9.0

| Item | Removed |
|---|---|
| Kind `frtb-sensitivity` | `config/packs/market-risk/pack.yaml` kind, route, layout, form, id pattern `^FRTB-`; mnemonic `FRTB` opens the computed page |
| Data | `config/packs/market-risk/samples/frtb-sensitivity.jsonl`, `data/testdata/market-risk/frtb-sensitivity/`, `testdata.yaml` entries |
| Sutra | `config/packs/market-risk/sutras/market-risk/frtb-sensitivity.v1.sutra.yaml` |
| Calc snippets | `config/packs/market-risk/python/frtb_girr_delta.py`, `frtb_overview.py` (and their sources under `tools/packgen/banking/snippets/`): replaced by the engine's own breakdowns |
| Generator | the FRTB part of `market_results` |
| Contract | `config/packs/market-risk/contracts/frtb-sensitivity.contract.yaml`; contracts for `sensitivity`, `default-risk`, `residual-risk` (class `input`) and the computed kinds replace it |
| About, README | the kind's glossary and README sections |
| Guard test | fails when `frtb-sensitivity` returns, or an input table carries `DELTA_CHARGE`, `VEGA_CHARGE`, `CURVATURE_CHARGE`, `SBM`, `DRC` |

### 14.3 Reconciliation

No factor relates the stored figures to the engine's (§13.2): the release notes list, per book on 2026-09-30, the stored `TOTAL` and
the engine's SBM, DRC and RRAO, and state the reasons (no risk weights or correlations in the stored rule, absolute values, an equal
class split, scaled clone amounts). The engine's numbers are validated instead against an **independent implementation**: a small
reference calculator in the test suite (`test_frtb_reference.py`, proposed) over hand-built portfolios, including the worked example
below, and against the bank's own FRTB numbers when a bank supplies a parallel run (open question 6).

## 15. A worked example

GIRR delta only, plus one curvature factor; `girr-specified-currency-reduction: false`. Three trades in two books; sensitivities in
USD per +1 bp (`AMOUNT_USD`), all on curve `SOFR` (USD) or `ESTR` (EUR):

| Trade | Book | Factor | `AMOUNT_USD` | RW | WS = RW(%) × 100 × amount |
|---|---|---|---|---|---|
| T1 (USD swap with a cap) | BOOK-A | GIRR USD `SOFR` 5Y | +10,000 | 1.1% | 110 × 10,000 = **1,100,000** |
| T2 (USD swap) | BOOK-B | GIRR USD `SOFR` 10Y | −6,000 | 1.1% | 110 × −6,000 = **−660,000** |
| T3 (EUR swap) | BOOK-B | GIRR EUR `ESTR` 2Y | +4,000 | 1.3% | 130 × 4,000 = **520,000** |

**Intra-bucket correlation** of USD 5Y and 10Y (same curve): ρ = max(e^{−0.03 × 5/5}, 0.40) = e^{−0.03} = 0.970446.

| Scenario | ρ | γ | K_USD | K_EUR | S_USD, S_EUR | Delta = sqrt(K_USD² + K_EUR² + 2γ S_USD S_EUR) |
|---|---|---|---|---|---|---|
| low | max(2 × 0.970446 − 1, 0.75 × 0.970446) = 0.940891 | max(2 × 0.5 − 1, 0.375) = 0.375 | sqrt(1.1M² + 0.66M² − 2 × 0.940891 × 1.1M × 0.66M) = **528,607.77** | 520,000 | 440,000; 520,000 | **849,368.10** |
| medium | 0.970446 | 0.5 | **486,326.11** | 520,000 | 440,000; 520,000 | **857,737.19** |
| high | min(1.25 × 0.970446, 1) = 1 | 0.625 | \|1.1M − 0.66M\| = **440,000** | 520,000 | 440,000; 520,000 | **866,025.40** |

(The sum under each square root is positive, so S_b is the plain sum; S_USD = 1,100,000 − 660,000 = 440,000.)

**Curvature.** T1 carries optionality (the cap): the curve shocked by the GIRR curvature shift (`SHOCK_SIZE` 170 bp, the largest GIRR
risk weight 1.7%) gives `curvature-up` +1,620,000 and `curvature-down` −1,760,000; the factor's delta is +10,000 USD per bp, so
RW^curv × δ = 170 bp × 10,000 = 1,700,000.

| Figure | Computation | Value |
|---|---|---|
| CVR⁺ | −(1,620,000 − 1,700,000) | 80,000 |
| CVR⁻ | −(−1,760,000 + 1,700,000) | 60,000 |
| K⁺, K⁻ (one factor) | max(CVR, 0) | 80,000; 60,000 |
| Curvature (up side chosen; one bucket) | | **80,000** in every scenario |

**SBM:** low 849,368.10 + 80,000 = 929,368.10; medium 937,737.19; high **946,025.40**. The **high** scenario binds: **SBM = 946,025**.

**Contributions (Euler, high scenario).** With u = S_USD = 440,000, e = 520,000, γ = 0.625, Delta = 866,025.40:
∂Delta/∂WS_USD = (u + γe) / Delta = 0.883346; ∂Delta/∂WS_EUR = (e + γu) / Delta = 0.917987.

| Part | Delta contribution | Curvature | Total | Standalone SBM |
|---|---|---|---|---|
| BOOK-A (T1) | 1,100,000 × 0.883346 = 971,680.50 | 80,000 | **1,051,680.50** | 1,100,000 + 80,000 = 1,180,000 |
| BOOK-B (T2, T3) | −660,000 × 0.883346 + 520,000 × 0.917987 = −105,655.10 | 0 | **−105,655.10** | max over scenarios of sqrt(0.66M² + 0.52M² − 2γ × 0.66M × 0.52M): low (γ 0.375) **669,776.08** |
| Sum | | | **946,025.40** = SBM | 1,849,776.08 (diversification 903,750.68) |

BOOK-B hedges the bank (its contribution is negative) although alone it needs 669,776 of capital. **What is kept per node:** BOOK-A
holds {USD 5Y: +10,000; USD curvature up +1,620,000, down −1,760,000, δ +10,000}; BOOK-B {USD 10Y: −6,000; EUR 2Y: +4,000}; the bank node
their sums. Every figure above is computed from those sums at query time.

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Due time of the sensitivity batch; may source systems deliver at different times (FRTB waits for all, or answers per system with a warning)? | §3.5 |
| 2 | Which jurisdiction overlays to ship first (EU CRR3, US, OSFI, PRA) and their effective dates; is the reporting currency USD for every legal entity? | §6.2 |
| 3 | `FRTB_BUCKET` and `SIMM_BUCKET`: always delivered upstream, or derived by Drishti from the issuer, equity and commodity masters (then those masters need sector, rating, market cap and economy)? | §3.2, §4 |
| 4 | Reporting currency other than USD for capital (per legal entity) | §5 |
| 5 | DRC for securitisations and the CTP, and CSR CTP in the SBM: in 2.9.0 or later? | §6.6 |
| 6 | Parallel run: will a bank's FRTB engine output (per desk) be supplied to validate against? | §14.3 |
| 7 | One sensitivity table with a `GRID` column, or two tables (FRTB grid, SIMM grid)? Upstream engines usually compute one grid and re-bucket | §3.2 |
| 8 | Gamma: diagonal per factor enough for STR and P&L explain, or are cross-gammas (rates × FX) needed? | §3.2.2 |
| 9 | The DRC and RRAO inputs: shipped in 2.9.0 (with test data) or reserved for a later release? | §3.3, §3.4 |
| 10 | The kind names (`frtb-charge`, `frtb-bucket`, `sensitivity-net`) and the capture prefix `FRCAP-` | §9 |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version (PLANNED for 2.9.0): the shared `sensitivity` table, DRC and RRAO inputs, the SBM per MAR21 with three correlation scenarios, the sensitivity store, the retirement of `frtb-sensitivity` |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
