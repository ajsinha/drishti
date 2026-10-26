<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# SACCR: the standardised approach for counterparty credit risk, the data contract

*Version 1.0 · 2026-10-25 · Shared rules: [README.md](README.md) · Model contract: [VAR.md](VAR.md) · Netting sets, CSAs and
membership: [NSET.md](NSET.md) · Collateral: [COLL.md](COLL.md)*

**Status:** proposed. PLANNED for 2.10.0, not built. Every table, column, setting, kind, endpoint and code below is a proposal; names
may change before the release, and each change will be recorded in [section 16](#16-open-questions-and-the-change-table).

This is the contract of Drishti's **SA-CCR engine**: the exposure at default (EAD) of derivative netting sets under the Basel standardised
approach for counterparty credit risk (BCBS 279, consolidated as CRE52), computed when asked for any scope (a netting set, a counterparty,
a legal entity, the bank), with incremental EAD before dealing. It defines the shared input `saccr-trade`.

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to SA-CCR](#5-conventions-specific-to-sa-ccr)
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

**What it is.** EAD = α × (RC + PFE): the replacement cost of the netting set today (after collateral; for margined sets, at least the
threshold plus the minimum transfer amount less the independent collateral) plus a potential future exposure built from supervisory
add-ons per asset class and hedging set, scaled by a multiplier that credits over-collateralisation. RWA follows from the counterparty's
risk weight.

**Commands.** `SACCR NSET NS-ALDERSHOT-NY <GO>`, `SACCR CPTY CP-ALDERSHOT <GO>`, `SACCR LE LE-NY <GO>`; the API `/api/v1/saccr`.

**Retired in the same release:** the stored kind `sa-ccr` (`SACCR-*`, 76 records with `REPLACEMENT_COST`, `ADD_ON`, `MULTIPLIER`, `EAD`,
`RWA`, `ADD_ONS[]`, `HEDGING_SETS[]`). Nothing of it is kept: `ALPHA` becomes a setting.

| Part | Status |
|---|---|
| `saccr-trade`: one row per trade (or per risk driver of a complex trade) with its SA-CCR classification and terms | PLANNED |
| Supervisory duration, maturity factor, supervisory delta, adjusted and effective notional (computed by Drishti) | PLANNED |
| Hedging-set and asset-class aggregation, multiplier, RC margined and unmargined, EAD with the unmargined cap, RWA hook | PLANNED |
| Incremental EAD before dealing; standalone EAD per trade | PLANNED |
| Computed kind `saccr-result`, reactive connector `saccr-engine` (Python formulas over Java's additive aggregates) | PLANNED |

## 2. The measure in one page

**What it answers:** *what exposure do regulators say this netting set carries, and how much more will this trade add?* For regulatory
capital (the EAD of RWA and the leverage ratio's exposure measure), large-exposure reporting, credit limits on EAD, and the BA-CVA
capital charge ([CVA.md](CVA.md)).

**In words** (CRE52):
1. **Replacement cost** RC: unmargined max(V − C, 0); margined max(V − C, TH + MTA − NICA, 0), where V is the net MtM, C the haircut value
   of net collateral held, TH and MTA the counterparty's threshold and minimum transfer amount, and NICA the net independent collateral.
2. Each trade gets an **effective notional** D = δ × d × MF: supervisory delta δ (±1 for linear trades, a Black–Scholes delta at a
   supervisory volatility for options), adjusted notional d (for rates and credit, the notional times a supervisory duration), and maturity
   factor MF (unmargined: √(min(M, 1 year)/1 year); margined: 1.5 × √(MPOR/1 year)).
3. Trades are grouped by **asset class** (interest rate, FX, credit, equity, commodity) and **hedging set** (a currency, a currency pair,
   all credit, all equity, a commodity group). Within a hedging set, effective notionals offset fully (FX), partly across maturity
   buckets (rates) or through a single-factor formula across reference entities (credit, equity, commodity types).
4. Each hedging set's **add-on** is its supervisory factor × its effective notional; the asset-class add-ons are summed into AddOn_agg.
5. **PFE** = multiplier × AddOn_agg, the multiplier = min(1, 0.05 + 0.95 × exp((V − C) / (2 × 0.95 × AddOn_agg))).
6. **EAD** = 1.4 × (RC + PFE), capped for a margined set at its unmargined EAD.

## 3. Inputs from upstream engines

### 3.1 Where they live

```
${DRISHTI_DELTA_ROOT}/risk/
├── saccr-trade/          business_date=YYYY-MM-DD/   one row per trade and risk driver: SA-CCR classification and terms   (new)
├── netting-set/, netting-set-member/, csa/            NSET.md: margined or not, TH, MTA, MPOR, disputes, cleared
├── collateral-balance/, collateral-holding/           COLL.md: C and NICA (haircut values)
└── trade-valuation/                                   TVAL.md: V (MtM per trade)
```

### 3.2 Decision: an input table, with Drishti computing the formula parts

The SA-CCR **classification** of a trade (its primary risk driver, asset class, hedging set, reference entity, credit quality, commodity
type, whether it is a basis or volatility transaction, its option terms) is **delivered** as an input table, `saccr-trade`, and not
derived by Drishti from the three booking systems' raw trades. Reasons:
- the classification is a regulatory judgement each bank signs off and audits (CRE52 asks for the primary risk driver, and allows a
  complex trade to be split into several drivers); it belongs to the bank's capital or product-control process, not to a viewer;
- the trade masters differ by system (Endur, Calypso, Murex shapes, VAR.md section 4) and do not carry the needed fields (no reference
  entity, no credit quality, no supervisory option terms for many products);
- Drishti still computes **everything that is formula**: supervisory duration, maturity factor, supervisory delta, adjusted and effective
  notional, maturity buckets, every aggregation and EAD. So a what-if trade needs only its classification and terms (§6.8), and a
  change of supervisory parameters is a setting, not a reload.

For banks without such a feed (and for the demo), a configured rule set derives the rows from the trade master by `PRODUCT_TYPE`
(`drishti.ccr.saccr.derive: rules`, §6.1, open question 1); derived rows are marked `DERIVED_BY: rules` and listed in provenance.

### 3.3 `saccr-trade`: the classification and terms

**Grain:** one row per trade and risk driver per business date. **Key:** `SACCR_ROW_ID` = `<TRADE_ID>~<DRIVER>`; natural key
(`BUSINESS_DATE`, `TRADE_ID`, `DRIVER`). A plain trade has one row (`DRIVER` 0). **Scope:** OTC derivatives and exchange-traded
derivatives facing a CCP; **not** securities financing transactions (repo, securities lending, margin lending: CRE22 comprehensive
approach, out of this contract) and not cash products. **Row count:** live derivatives (test data about 7,000; a large bank about
1,000,000).

| Column | JSON | Delta | Req. | Engine | Unit | Meaning | Example |
|---|---|---|---|---|---|---|---|
| `SACCR_ROW_ID` | id | `id` STRING | required | – | | `<TRADE_ID>~<DRIVER>` | `MX-30000412~0` |
| `BUSINESS_DATE` | date | `business_date` DATE | required | R | | | `2026-09-30` |
| `TRADE_ID`, `SOURCE_SYSTEM` | id, text | STRING | required | R | | the trade | `MX-30000412`, `MUREX` |
| `DRIVER` | integer | DOUBLE | required | R | | 0 the primary risk driver; 1, 2 … further drivers of a complex trade | `0` |
| `NETTING_SET`, `COUNTERPARTY`, `LEGAL_ENTITY` | id | STRING | required | R | | denormalised from the membership (checked) | `NS-ALDERSHOT-NY`, `CP-ALDERSHOT`, `LE-NY` |
| `ASSET_CLASS` | text | STRING | required | R | | `IR`, `FX`, `CREDIT`, `EQUITY`, `COMMODITY` | `IR` |
| `HEDGING_SET` | text | STRING | required | R | | IR: the currency (`USD`); FX: the pair, alphabetical (`EURUSD`); credit: `CREDIT`; equity: `EQUITY`; commodity: `ENERGY`, `METALS`, `AGRICULTURAL`, `OTHER`; a basis or volatility transaction: its own (`USD-BASIS-SOFR-TERM`, `EQUITY-VOL`) | `USD` |
| `TRANSACTION_TYPE` | text | STRING | required | R | | `LINEAR`, `OPTION`, `CDO_TRANCHE`, `BASIS`, `VOLATILITY` | `LINEAR` |
| `REFERENCE` | text | STRING | optional | R | | credit and equity: the reference entity or index (an LEI, ticker or index id); commodity: the commodity type (`CRUDE_OIL`, `NATURAL_GAS`, `ELECTRICITY`, `GOLD`, …) | `549300H5KZ2QJHEAMB55` |
| `IS_INDEX` | boolean | STRING | optional | R | | credit and equity: an index (correlation 80%) or a single name (50%) | `false` |
| `CREDIT_QUALITY` | text | STRING | optional | R | | credit single name: `AAA`, `AA`, `A`, `BBB`, `BB`, `B`, `CCC`; credit index: `IG`, `SG` | `BBB` |
| `DIRECTION` | text | STRING | required | R | | `LONG` or `SHORT` in the primary risk factor (CRE52: a payer swap is long the rate; bought protection is short credit quality: `SHORT`) | `LONG` |
| `NOTIONAL_USD` | amount | DOUBLE | optional | R | USD | IR and credit: the trade notional; FX: the foreign leg (both non-domestic: the larger of the two legs converted); equity and commodity: units × price. Required unless `QUANTITY` and `UNDERLYING_PRICE` are given | `100000000` |
| `QUANTITY`, `UNDERLYING_PRICE` | amount | DOUBLE | optional | R | units, USD | equity and commodity: d = `QUANTITY` × `UNDERLYING_PRICE` | `50000`, `74.6` |
| `START_DATE` | date | STRING | optional | R | | S: when the referenced period starts (IR and credit; forward starting) | `2026-09-30` |
| `END_DATE` | date | STRING | optional | R | | E: when it ends (IR and credit); also the maturity bucket | `2030-09-30` |
| `MATURITY_DATE` | date | STRING | required | R | | M: the latest date the trade may still be active | `2030-09-30` |
| `OPTION_TYPE` | text | STRING | optional | R | | `CALL`, `PUT` (options) | `CALL` |
| `BOUGHT_SOLD` | text | STRING | optional | R | | `BOUGHT`, `SOLD` (options, tranches: protection bought or sold) | `BOUGHT` |
| `EXERCISE_DATE` | date | STRING | optional | R | | T: the latest contractual exercise date (options) | `2027-03-31` |
| `STRIKE`, `UNDERLYING_FORWARD` | amount, rate | DOUBLE | optional | R | | K and P for the supervisory delta (the same unit: a rate, a price) | `0.04`, `0.0385` |
| `SHIFT` | rate | DOUBLE | optional | R | | λ for options on rates that can be negative (CRE52.41) | `0.01` |
| `ATTACHMENT`, `DETACHMENT` | rate | DOUBLE | optional | R | | CDO tranches, 0 to 1 | `0.03`, `0.07` |
| `CLEARED` | boolean | STRING | optional | R | | the trade faces a qualifying CCP | `false` |
| `DERIVED_BY` | text | STRING | derived | – | | `upstream` or `rules` (§3.2) | `upstream` |
| `BATCH_ID` | id | STRING | required | – | | | `B-2026-09-30-01` |

**Not delivered (computed by Drishti):** supervisory duration, supervisory delta, maturity factor, adjusted notional, effective notional,
maturity bucket, supervisory factor, add-ons, RC, multiplier, EAD, RWA. A table that carries any of them fails the guard test.

### 3.4 Cadence and delivery

| Item | Contract |
|---|---|
| Cadence | once per business date with the trade valuations (the same `BATCH_ID` is recommended, not required) |
| Due | **to be agreed** (open question 2) |
| Complete when | every live member trade of a `DERIVATIVES` or `CLEARED` netting set has at least a `DRIVER` 0 row |
| Load signal | reloads the date in the SA-CCR engine automatically (product owner, 2026-10-25) |
| Retention | 7 years (per-trade inputs, product owner, 2026-10-25): about 1,000,000 × 300 bytes = 0.3 GB a date, 0.5 TB over 7 years |

## 4. Reference and master data read

| Kind | Read for |
|---|---|
| `netting-set`, `netting-set-member`, `csa` (NSET.md) | margined or not, `CLEARED`, `THRESHOLD_THEM`, `MTA_THEM`, `MPOR_DAYS`, `MARGIN_FREQUENCY_DAYS`, `DISPUTES_LAST_2Q`, `ILLIQUID_COLLATERAL`, member count (the 5,000-trade MPOR floor) |
| `trade-valuation` (`TVAL.md`) | V = Σ MTM of the set's derivative members |
| `collateral-holding` (COLL.md) | C (VM and IM held − posted, haircut values) and NICA |
| `counterparty` (core) | the counterparty's type and rating for the RWA hook |
| `trade` | rule-derived rows (§3.2), maturity cross-check |

## 5. Conventions specific to SA-CCR

| Convention | Rule |
|---|---|
| Time | years = business days / 250 for MF and MPOR (CRE52); S, E, M, T in years as ACT/365 from the business date for SD and δ |
| Floors | M ≥ 10 business days; S ≥ 0; E ≥ 10 business days (SD); MPOR floors §6.3 |
| Signs | δ and therefore D signed; RC, add-ons, PFE, EAD ≥ 0; V and C from the bank's side (C held positive) |
| Currency | USD |
| Rounding | API to whole USD; δ, MF, SD, multiplier unrounded |
| Excluded | SFTs and cash products: reported as `excluded` with a count, never as zero EAD silently |

## 6. The computation

### 6.1 Parameters (`drishti.ccr.saccr.*`, regulatory values as defaults)

| Setting | Default | Meaning |
|---|---|---|
| `alpha` | `1.4` | α (some jurisdictions allow 1.0 for certain end-user trades: per counterparty override `alpha-overrides`) |
| `multiplier-floor` | `0.05` | the floor of the multiplier |
| `ir-bucket-offset` | `true` | IR offset across maturity buckets (else Σ \|D_b\|) |
| `supervisory-factors` | the table of §6.5 | |
| `correlations` | credit single 0.5, credit index 0.8, equity single 0.5, equity index 0.8, commodity 0.4 | |
| `option-volatilities` | IR 0.5, FX 0.15, credit single 1.0, credit index 0.8, equity single 1.2, equity index 0.75, electricity 1.5, other commodity 0.7 | σ for δ |
| `basis-factor`, `volatility-factor` | `0.5`, `5` | SF multipliers for basis and volatility transactions |
| `mpor-floor-days` | `{bilateral: 10, cleared: 5, large-or-illiquid: 20}` | §6.3 |
| `large-netting-set-trades` | `5000` | |
| `cap-at-unmargined` | `true` | margined EAD ≤ unmargined EAD |
| `risk-weights` | by counterparty type and rating (a standardised table, CRE20; 100% when unknown) | the RWA hook |
| `derive` | `none` | `rules`: derive `saccr-trade` from the trade master (§3.2) |
| `regulation` | `BCBS` | `BCBS`; jurisdictional variants (US, EU CRR2, OSFI) as named overrides later (open question 3) |

### 6.2 Replacement cost

For netting set *n*: V = Σ MTM of its in-scope members (derivatives only; excluded trades are not in V); C = haircut value of collateral
held minus posted (VM and IM, COLL.md); NICA = IA and IM held − IA and IM posted that is not segregated (COLL.md section 6);
TH = `THRESHOLD_THEM`, MTA = `MTA_THEM` of the CSA (USD).

```
unmargined   RC = max(V − C, 0)
margined     RC = max(V − C, TH + MTA − NICA, 0)
```

### 6.3 Maturity factor and MPOR

```
unmargined   MF_i = sqrt( min(max(M_i, 10 bd), 250 bd) / 250 bd )
margined     MF   = 1.5 · sqrt( MPOR_n / 250 )                         one value for every trade of the set
MPOR_n       = max(floor_n, MPOR_DAYS) + F − 1                         F = MARGIN_FREQUENCY_DAYS (remargining every F days)
floor_n      = 5 cleared · 10 bilateral · 20 when the set has > 5,000 trades or ILLIQUID_COLLATERAL
             doubled when DISPUTES_LAST_2Q > 2
```

### 6.4 Supervisory duration, delta, adjusted and effective notional

```
SD_i  = ( exp(−0.05 · S_i) − exp(−0.05 · E_i) ) / 0.05                 IR and credit;  S_i ≥ 0, E_i ≥ 10 bd
d_i   = NOTIONAL_USD_i · SD_i                                          IR and credit
d_i   = NOTIONAL_USD_i                                                 FX (the foreign leg in USD)
d_i   = QUANTITY_i · UNDERLYING_PRICE_i  (or NOTIONAL_USD_i)            equity, commodity

δ_i   = +1 LONG, −1 SHORT                                              LINEAR, BASIS, VOLATILITY
δ_i   (OPTION, T_i in years, σ the supervisory option volatility, λ = SHIFT or 0):
        d1 = ( ln((P + λ)/(K + λ)) + 0.5 σ² T ) / ( σ sqrt(T) )
        bought call  +Φ(d1)     sold call  −Φ(d1)     bought put  −Φ(−d1)     sold put  +Φ(−d1)
δ_i   (CDO_TRANCHE, A attachment, D detachment):
        +15 / ((1 + 14 A)(1 + 14 D))  when protection is sold (LONG credit quality), −15 / (…) when bought

D_i   = δ_i · d_i · MF_i                                               effective notional of the trade (signed)
```

### 6.5 Supervisory factors

| Asset class | Sub-class | SF | Correlation ρ | Option σ |
|---|---|---|---|---|
| IR | | 0.50% | | 50% |
| FX | | 4.0% | | 15% |
| Credit, single name | AAA, AA | 0.38% | 50% | 100% |
| | A | 0.42% | 50% | 100% |
| | BBB | 0.54% | 50% | 100% |
| | BB | 1.06% | 50% | 100% |
| | B | 1.6% | 50% | 100% |
| | CCC | 6.0% | 50% | 100% |
| Credit, index | IG | 0.38% | 80% | 80% |
| | SG | 1.06% | 80% | 80% |
| Equity | single name | 32% | 50% | 120% |
| | index | 20% | 80% | 75% |
| Commodity | electricity | 40% | 40% | 150% |
| | oil, gas, metals, agricultural, other | 18% | 40% | 70% |

Basis transactions use SF × 0.5, volatility transactions SF × 5 (both in their own hedging sets). The values are CRE52's (checked against
the text when the release is built).

### 6.6 Aggregation within hedging sets and asset classes

```
IR, per hedging set h (a currency), buckets by E: b1 E < 1y, b2 1y ≤ E ≤ 5y, b3 E > 5y,  D_b = Σ_{i ∈ h, b} D_i
    EN_h      = sqrt( D1² + D2² + D3² + 1.4·D1·D2 + 1.4·D2·D3 + 0.6·D1·D3 )      (ir-bucket-offset: false → |D1| + |D2| + |D3|)
    AddOn_IR  = Σ_h SF_IR · EN_h

FX, per hedging set h (a pair):   AddOn_FX = Σ_h SF_FX · | Σ_{i ∈ h} D_i |

Credit, Equity, per reference entity or index k:   AddOn_k = SF_k · Σ_{i ∈ k} D_i            (signed)
    AddOn_class = sqrt( (Σ_k ρ_k · AddOn_k)² + Σ_k (1 − ρ_k²) · AddOn_k² )

Commodity, per hedging set h (ENERGY, METALS, AGRICULTURAL, OTHER), per commodity type k:   AddOn_k = SF_k · Σ_{i ∈ k} D_i
    AddOn_h   = sqrt( (ρ · Σ_k AddOn_k)² + (1 − ρ²) · Σ_k AddOn_k² ),  ρ = 0.4
    AddOn_COM = Σ_h AddOn_h

AddOn_agg = AddOn_IR + AddOn_FX + AddOn_CREDIT + AddOn_EQUITY + AddOn_COM
```

### 6.7 Multiplier, PFE, EAD, RWA

```
multiplier = min( 1, f + (1 − f) · exp( (V − C) / (2 · (1 − f) · AddOn_agg) ) ),  f = 0.05        (1 when AddOn_agg = 0)
PFE        = multiplier · AddOn_agg
EAD        = α · (RC + PFE)
margined   EAD = min( EAD_margined, EAD_unmargined )            the same set with unmargined RC and MF (cap-at-unmargined)
RWA        = EAD · RW(counterparty)                             hook: informational, the bank's capital engine owns RWA
```

For a scope of whole netting sets, EAD and RWA **add** over netting sets. A scope that cuts netting sets is computed per touched set on
the trades in the scope (`partial`, NSET.md section 6.1).

### 6.8 Incremental EAD before dealing

A candidate is one or more `saccr-trade` rows (the same columns, `TRADE_ID` of the candidate's choosing) with its netting set and an
estimated MtM (0 by default for an at-market trade). The engine adds them to the set's aggregates, recomputes V, multiplier, PFE, RC and
EAD, and answers `EAD_BEFORE`, `EAD_AFTER`, `INCREMENTAL_EAD` and the hedging sets that moved. **Standalone EAD** of a trade is its EAD as
its own unmargined set (V its MtM, C = 0). Because hedging-set aggregation is non-linear, per-trade figures do not add up to the set's
EAD; the API never presents them as a decomposition.

### 6.9 Where it runs

| Work | Where |
|---|---|
| Per trade: SD, δ, MF (both margined and unmargined), D; per set: the additive aggregates of §7.1 | Java at date load (`com.ash.drishti.compute.ccr.SaccrAggregates`, planned) |
| The formulas of §6.2–6.7 over the aggregates (bank-wide: about 1,000,000 aggregate rows), what-if, standalone EAD | Python engine `drishti_ccr.saccr:SaccrEngine` on the `risk` pool, Arrow in and out, split by netting set across workers (sums reduce) |
| Reference implementation for tests | `tools/packgen/ccr/ccrmath.py` (planned), parity-tested against both |

## 7. What Drishti builds

### 7.1 Additive aggregates per netting set (in memory)

For each netting set and each (asset class, hedging set, sub-key) the sum of D, twice (margined MF and unmargined MF), where the sub-key
is the IR maturity bucket, the credit or equity reference (with its SF and ρ), or the commodity type:

| Structure | Content | Size, large bank |
|---|---|---|
| aggregate rows | key (set, class, hedging set, sub-key), `D_MARGINED`, `D_UNMARGINED`, `SF`, `RHO`, trade count | about 1,000,000 rows × 64 bytes = 64 MB |
| set header | V, C, NICA, TH, MTA, MPOR, margined, cleared, counts, excluded | 100,000 × 96 bytes = 10 MB |
| per trade | D (both), SD, δ, MF for drill and what-if removal | 1,000,000 × 40 bytes = 40 MB |

Nothing goes to disk beyond the lake; the date rebuilds in seconds. Answers are cached as NSET's (§7.3 there).

### 7.2 What is additive

| Figure | Additive over |
|---|---|
| D within a (set, hedging set, sub-key) | trades |
| V, C, NICA | trades, netting sets |
| EN, add-ons, multiplier, PFE, RC | nothing: per netting set, at query time |
| EAD, RWA | netting sets |

## 8. Scopes

Scope grammar of VAR.md section 8 with NSET.md section 8's terms. EAD is defined per netting set: a counterparty's EAD is the sum over
its netting sets, a legal entity's the sum over the sets it faces. Default parts: a counterparty → netting sets; a netting set → asset
classes → hedging sets → sub-keys → trades (drill: each level shows its add-on; trades show D, δ, d, MF, SD).

## 9. What Drishti answers

### 9.1 The computed kind `saccr-result`

**Id:** the canonical scope (`NSET:NS-ALDERSHOT-NY`). The retired kind name `sa-ccr` is not reused.

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `SCOPE`, `CANONICAL`, `SCOPE_KIND`, `LABEL`, `BUSINESS_DATE` | | | as NSET |
| `NETTING_SETS`, `TRADES`, `EXCLUDED_TRADES` | integer | | excluded: SFTs, cash products, no `saccr-trade` row |
| `V`, `C`, `NICA` | amount | USD | |
| `RC` | amount | USD | |
| `ADD_ON` | amount | USD | AddOn_agg |
| `MULTIPLIER` | rate | | (for a single set; empty above it) |
| `PFE` | amount | USD | |
| `EAD`, `EAD_UNMARGINED`, `CAPPED` | amount, amount, boolean | USD | |
| `ALPHA`, `MPOR_DAYS`, `MARGINED` | | | (single set) |
| `RWA`, `RISK_WEIGHT` | amount, rate | USD | the hook |
| `ADD_ONS` | record list | | per asset class: `ASSET_CLASS`, `ADD_ON` |
| `HEDGING_SETS` | record list | | `ASSET_CLASS`, `HEDGING_SET`, `SUB_KEYS` (`KEY`, `D`, `SF`, `ADD_ON`), `EFFECTIVE_NOTIONAL`, `ADD_ON` |
| `BY_NETTING_SET` | record list | | above a set: `NETTING_SET`, `RC`, `PFE`, `MULTIPLIER`, `EAD`, `MARGINED` |
| `PROVENANCE` | record | | NSET's, plus `SUPERVISORY_TABLE` (the regulation and version), `DERIVED_ROWS` |

### 9.2 The API (`/api/v1/saccr`, planned)

| Method and path | Parameters | Answer |
|---|---|---|
| `GET /scope` | `scope`, `asOf` | §9.1 |
| `GET /scope/contributions` | `scope`, `by` (`netting-set`, `asset-class`, `hedging-set`, `trade`) | rows `{key, scope, label, ead | addOn | d, standaloneEad}` |
| `GET /trade/{tradeId}` | `asOf` | the trade's SD, δ, d, MF (both), D, bucket, its row of `saccr-trade`, its standalone EAD |
| `POST /what-if` | body `{nettingSet, candidates: [<saccr-trade row> + {mtm}], remove: [tradeId]}` | `{eadBefore, eadAfter, incrementalEad, rcBefore, rcAfter, pfeBefore, pfeAfter, hedgingSets: [{assetClass, hedgingSet, addOnBefore, addOnAfter}]}` |
| captures, status, reload | as NSET | |

### 9.3 CSV

`GET /scope/contributions?by=trade&format=csv`: `trade_id,netting_set,asset_class,hedging_set,sub_key,direction,notional_usd,sd,delta,mf,d,effective_notional`.

### 9.4 The reactive connector and the engine (proposed)

```yaml
# config/connectors/saccr-engine.yaml                           (planned, 2.10.0)
plugin: reactive
mode: reactive
description: SA-CCR exposure at default (CRE52) for any scope; incremental EAD before dealing
kinds:
  saccr-result:
    compute: saccr
    params:
      sandbox: { type: sandbox-ref }                     # optional: candidate trades for a what-if
    result: { required: [EAD, RC, PFE], types: { EAD: number, RC: number, PFE: number } }
reads:
  - { kind: saccr-trade, as: trades, all: true }
  - { kind: netting-set, as: sets, all: true }
  - { kind: csa, as: csas, all: true }
  - { kind: trade-valuation, as: valuations, all: true }
  - { kind: collateral-holding, as: holdings, all: true }
engine:
  type: python
  entry: drishti_ccr.saccr:SaccrEngine
  pool: risk
  version: "1.0"
  split: aggregates                                      # cut by netting set over the pool's workers
  chunk-rows: 50000
  reduce: { sum: [EAD, RWA, RC, PFE, NETTING_SETS, TRADES] }
triggers:
  - { on: data-version, kinds: [saccr-trade, netting-set-member, csa, trade-valuation, collateral-holding], affects: all }
coalesce: { quiet: 250ms, max-wait: 2s }
cache: { results: 2000, ttl: 1d }
limits: { timeout: 30s, wait: 50ms, max-inflight-per-user: 4 }
requires: { python: { version: "3.12", lock: requires/drishti-ccr.lock } }
```

```python
# compute/drishti-ccr/src/drishti_ccr/saccr.py                  (planned, 2.10.0)
class SaccrEngine(Engine):
    kinds = ("saccr-result",)

    def compute(self, req, inputs, progress):
        agg = inputs.arrow("aggregates")   # NETTING_SET, ASSET_CLASS, HEDGING_SET, SUB_KEY, SF float64, RHO float64,
                                           # D_MARGINED float64, D_UNMARGINED float64, TRADES int32   (from Java, additive)
        sets = inputs.arrow("sets")        # NETTING_SET, V, C, NICA, TH, MTA, MARGINED bool, MPOR_DAYS int32, RW float64
        cands = inputs.arrow("sandbox")    # optional: saccr-trade rows + MTM (the engine computes their D itself)
        ...                                # sections 6.2 to 6.7, vectorised with numpy per hedging set
        return Result(rows=[{"EAD": ..., "RC": ..., "PFE": ..., "ADD_ON": ...}],
                      tables={"by_netting_set": ..., "hedging_sets": ...})
```

### 9.5 Published official results (`saccr-official`, `saccr-official-cpty`)

The two tables of [NSET.md section 9.5](NSET.md#95-published-official-results-nset-official-nset-official-cpty), with the shared columns
and: `V`, `C`, `NICA`, `RC`, `ADD_ON`, `ADD_ON_IR`, `ADD_ON_FX`, `ADD_ON_CREDIT`, `ADD_ON_EQUITY`, `ADD_ON_COMMODITY`, `PFE`, `EAD`,
`EAD_UNMARGINED`, `RWA` (DOUBLE, USD); `MULTIPLIER`, `ALPHA` (DOUBLE; netting-set rows only); `MARGINED`, `CAPPED` (BOOLEAN; netting-set
rows); `NETTING_SETS`, `TRADES`, `EXCLUDED_TRADES` (BIGINT); `SUPERVISORY_TABLE` (STRING). Node and counterparty rows sum EAD, RWA, RC and
PFE over netting sets (EAD is additive over sets; the multiplier is not, so it is left empty above a set). This is the table a regulatory
reporting system reads for the EAD of the day.

## 10. Validation, errors and data quality

| Check | Rule | Outcome |
|---|---|---|
| Enums | `ASSET_CLASS`, `TRANSACTION_TYPE`, `DIRECTION`, `CREDIT_QUALITY`, `HEDGING_SET` (commodity groups) valid | row rejected |
| Hedging set consistent | IR: a currency; FX: a six-letter pair; credit: `CREDIT` or a basis/vol set; … | row rejected |
| Option terms | an `OPTION` has `OPTION_TYPE`, `BOUGHT_SOLD`, `EXERCISE_DATE`, `STRIKE`, `UNDERLYING_FORWARD` (P, K > −λ) | row rejected |
| Tranche terms | 0 ≤ A < D ≤ 1 | row rejected |
| Notional | `NOTIONAL_USD`, or `QUANTITY` and `UNDERLYING_PRICE` | row rejected |
| Dates | S ≤ E ≤ M (where given), all on or after the business date except S | row rejected |
| Coverage | every live derivative member has a `DRIVER` 0 row | missing counted `unclassified`; the set is `incomplete` (its EAD excludes them and says so) |
| Scope | a row for an SFT or cash product | row ignored, counted `excluded` |
| Membership | `NETTING_SET` equals the membership | membership wins, flagged |
| Margined terms | a margined set's CSA has TH, MTA, MPOR | treated as unmargined, flagged |

**Proposed DRS codes** (5310–5319): `DRS-5310` (400, bad scope or candidate row: the reason), `DRS-5311` (409, the date's
classification is incomplete and `strict=true`), `DRS-5312` (400, a what-if names a netting set the date does not have).

## 11. Entitlements and audit

As NSET.md section 11. Trade-level drill (δ, d, MF per trade) only at `trades` level; what-if sandboxes are private; `ccr-view`,
`ccr-whatif` audited.

## 12. Performance and sizing

| Operation | Target (p95, warm) |
|---|---|
| a netting set | 20 ms (Python worker, warm) |
| a counterparty, 50 sets | 30 ms |
| a legal entity, 25,000 sets | 300 ms (split over workers) |
| the bank, 100,000 sets, 1,000,000 aggregate rows | 2 s |
| pre-deal, one candidate | 50 ms |
| date load (1,000,000 rows: SD, δ, MF, aggregates) | 10 s after reading |

## 13. Test data and generators

| Item | Plan |
|---|---|
| Generator | `tools/packgen/ccr/saccr.py` (planned): a mapping table by `PRODUCT_TYPE` (the trading pack's product catalogue) to asset class, hedging set rule, transaction type and direction rule, used both for the test data and as the shipped example of `drishti.ccr.saccr.derive: rules` |
| Rows | every live derivative member (about 7,000 of the 10,000 trades); credit references from the issuers of the market-data pack with their ratings mapped to quality; options with strike and forward from the trade's `STRIKE_PRICE` and the market-data curves |
| Folder | `data/testdata/counterparty-risk/saccr-trade/cob-2026-09-30.jsonl.gz` |
| Tests | `test_ccr_data.py`: every derivative member classified, SFTs and cash excluded; `ccrmath.py` against the worked example of §15 and against the BCBS example netting sets (open question 4) |

## 14. Retiring the stored version

### 14.1 Id mapping and removals

`SACCR-<cp>-<le>` → `SACCR NSET:NS-<cp>-<le>` (the netting set of the same name; 76 ids). Removed: the `sa-ccr` kind (kind, route,
form, badge, id pattern `^SACCR-` now redirecting), `samples/sa-ccr.jsonl`, `data/testdata/counterparty-risk/sa-ccr/`, the Sutra
`exposure-and-capital/sa-ccr.v1.sutra.yaml` (replaced by the computed page), the Calc snippet `saccr_recompute.py` (rewritten to call
the engine), the SA-CCR part of `credit_results` in `tools/packgen/banking/data_results.py` and `SF`, `SACCR_CLASS`, `SF_BY_CLASS` there.
Guard test: `sa-ccr` must not return; no input or fundamental kind carries `EAD`, `ADD_ON`, `REPLACEMENT_COST`, `MULTIPLIER`.

### 14.2 Reconciliation: how the stored figures were made

`credit_results` (2026-10-25):

| Stored rule | CRE52 | Effect |
|---|---|---|
| effective notional = notional × min(1, max(years to maturity, 0.1)) × ±1 | δ × notional × SD × MF | no supervisory duration (rates and credit understated for long trades), no option delta, maturity factor replaced by a time fraction |
| hedging set = (asset class, **currency**) for every class | IR: currency; FX: pair; credit and equity: one set with per-entity correlation; commodity: four groups | equity, credit and commodity hedging sets by currency are wrong |
| add-on = Σ hedging sets \|EN\| × SF | IR bucket offsets; entity correlation formula; commodity type formula | no offsets inside a class |
| RC = max(V − C, 0) for every set | margined: max(V − C, TH + MTA − NICA, 0) | margined sets with a 10m threshold are understated by up to TH + MTA |
| multiplier formula | the same | consistent |
| SFTs (repo, lending) mapped to *Interest rate* | out of SA-CCR (CRE22) | the test data's `NS-ALDERSHOT-FRA` holds only SFTs: computed SA-CCR EAD is **0 with 43 excluded trades**, stored 19,999,181 |
| RWA = EAD × 20%, 50% or 100% by rating | the bank's risk weight (hook) | |

The pack sample `SACCR-ALDERSHOT-FRA` (2026-09-30: RC 0, add-on 791,562, multiplier 0.933, EAD 1,033,938) is internally consistent
with the stored rule (V − C = −339,550 + 229,552 = −109,998 gives the multiplier 0.933); in the test data the same id reads RC 1,679,379
while its netting set's `NET_MTM − COLLATERAL` is 2,611,248 (the daily walk moves the netting set, not the SA-CCR record). The release
notes will list stored and computed EAD for each set with the reason.

## 15. A worked example

**An unmargined IR netting set**, two hedging sets (USD, EUR), V = +1.5m (sum of MtMs: +2.0m, −1.0m, +0.5m), no collateral. Notionals in
USD; S = 0 for all.

| Trade | Hedging set | Direction | Notional | E = M (years) | SD = (1 − e^(−0.05E))/0.05 | d = N × SD | MF | D = δ d MF | Bucket |
|---|---|---|---|---|---|---|---|---|---|
| T1 payer swap | USD | LONG (+1) | 100m | 4 | 3.625385 | 362,538,494 | 1 | **+362,538,494** | 2 |
| T2 receiver swap | USD | SHORT (−1) | 50m | 8 | 6.593599 | 329,679,954 | 1 | **−329,679,954** | 3 |
| T3 payer swap | EUR | LONG (+1) | 80m | 0.5 | 0.493802 | 39,504,141 | √0.5 = 0.707107 | **+27,933,646** | 1 |

**USD:** D1 = 0, D2 = 362,538,494, D3 = −329,679,954;
EN = √(D2² + D3² + 1.4·D2·D3) = √(1.31434 + 1.08689 − 1.67331) × 10¹⁷ = √(7.27922 × 10¹⁶) = **269,801,201**; add-on = 0.5% × EN = **1,349,006**.
**EUR:** EN = 27,933,646; add-on = **139,668**.
**AddOn_IR = AddOn_agg = 1,488,674.**

| Figure | Computation | Value |
|---|---|---|
| RC (unmargined) | max(1.5m − 0, 0) | 1,500,000 |
| multiplier | V − C > 0 | 1 |
| PFE | 1 × 1,488,674 | 1,488,674 |
| **EAD** | 1.4 × (1,500,000 + 1,488,674) | **4,184,144** |
| offset benefit in USD | 362.5m + 329.7m = 692.2m without offset, 269.8m with | add-on 3,461,092 → 1,349,006 |

**If V were −1.5m** (the set out of the money, no collateral): RC = 0; multiplier = 0.05 + 0.95 × exp(−1,500,000 / (2 × 0.95 × 1,488,674))
= 0.05 + 0.95 × exp(−0.530316) = **0.608996**; PFE = 906,596; EAD = 1.4 × 906,596 = **1,269,235**.

**The same set margined** (daily VM, MPOR 10 days, TH = 0, MTA = 0.5m, NICA = 0, VM held C = 1.2m): MF = 1.5 × √(10/250) = **0.3** for
every trade; D: T1 108,761,548, T2 −98,903,986, T3 11,851,242; EN_USD = **80,940,360**, EN_EUR = 11,851,242; AddOn = 0.5% × 92,791,602 =
**463,958**. RC = max(1.5 − 1.2, 0 + 0.5 − 0, 0) = **0.5m**; multiplier = 1 (V − C = 0.3m > 0); **EAD = 1.4 × (500,000 + 463,958) =
1,349,541**. The unmargined EAD with the same collateral is 1.4 × (300,000 + 1,488,674) = 2,504,144, so the cap does not bind.

**Credit aggregation** (the correlation formula): entity A single name BBB, Σ D = +200m → AddOn_A = 0.54% × 200m = +1.08m (ρ 0.5); an IG
index, Σ D = −300m → AddOn_I = 0.38% × (−300m) = −1.14m (ρ 0.8).
AddOn_credit = √((0.5 × 1.08 − 0.8 × 1.14)² + (1 − 0.25) × 1.08² + (1 − 0.64) × 1.14²) = √(0.138384 + 0.8748 + 0.467856) = √1.48104 =
**1.21698m** (the hedge of the single name by the index offsets through the systematic term only).

## 16. Open questions and the change table

### 16.1 Open questions

| # | Question | Context |
|---|---|---|
| 1 | Is there an upstream SA-CCR classification feed (the regulatory capital engine), or does Drishti derive the rows by configured rules for production too? | §3.2 |
| 2 | Due time of `saccr-trade` | §3.4 |
| 3 | Which jurisdictions' variants are needed (US final rule, EU CRR2, OSFI CAR): alpha for commercial end users, the treatment of collateral haircuts, SFT exclusions | §6.1 |
| 4 | Reference test cases: the BCBS SA-CCR worked examples (the regulator's published netting sets) as the engine's acceptance tests | §13 |
| 5 | Risk weights for the RWA hook: from configuration (standardised, by type and rating), from an upstream capital engine per counterparty, or not shown? | §6.7 |
| 6 | Specific wrong-way risk trades (WWR.md) as separate netting sets in SA-CCR: by `SPECIFIC_WWR` on the membership row? | WWR.md |
| 7 | Cleared trades: the CCP exposure (trade exposure and default fund contribution, CRE54) is out of this contract; needed in 2.10.0? | |

### 16.2 The change table

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-25 | First version, PLANNED for 2.10.0; product owner's decisions of 2026-10-25 applied (load signal reloads the engine, inputs kept 7 years, published official results) |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
