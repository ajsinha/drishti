<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# ISDA SIMM initial margin: the data contract

*Contract version 2.0 · 2026-11-02 · **Status:** design, not built. The sensitivity inputs are the shared ones of [FRTB.md](FRTB.md)
(contracts `planned`, demo data generated); the engine, its screens and its playground are the design to build · Method:
[SENSITIVITIES_AND_FRTB_SA.md section 8](../architecture/SENSITIVITIES_AND_FRTB_SA.md#8-isda-simm) · Fields and worked records:
[FRTB_DATA_STRUCTURES.md](FRTB_DATA_STRUCTURES.md) · Shared rules: [README.md](README.md)*

This is the data contract of Drishti's **ISDA SIMM** engine: the Standard Initial Margin Model of the International Swaps and Derivatives
Association, computed per **netting set**, per **regime** and per the **SIMM version** the netting set's CSA names, from the same position
sensitivities FRTB-SA reads. It says what is delivered, how it is mapped to ISDA's Common Risk Interchange Format (CRIF), what Drishti
computes, and how a margin figure is reproduced and explained to a counterparty.

**The structure is ISDA's; the numbers are licensed.** Drishti implements SIMM's method as published in ISDA's SIMM methodology
(version 2.8+2512, effective 11 July 2026, at the time of writing; the structure has been stable across versions and the parameters change
with each calibration). The risk weights, correlations and thresholds are ISDA's and licensed: Drishti **ships every number empty**, and
a bank holding the licence loads its own file for each version it uses; the version is recorded on every figure and export (decision 11).

## Contents

1. [Summary](#1-summary)
2. [The measure in one page](#2-the-measure-in-one-page)
3. [Inputs from upstream engines](#3-inputs-from-upstream-engines)
4. [Reference and master data read](#4-reference-and-master-data-read)
5. [Conventions specific to SIMM](#5-conventions-specific-to-simm)
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

**What it is.** The initial margin the bank should **collect** from and **post** to a counterparty under a netting set, by ISDA SIMM,
with the breakdown by product class, risk class, measure (delta, vega, curvature, base correlation), bucket and risk factor, the
concentration factors applied, and the add-ons; the margin against the counterparty's call, to explain a dispute; and the same model
over any other scope as an analytic (a desk's SIMM, labelled *analytic, not a margin*).

**Commands (built in 2.9.11; [SIMM_GUIDE.md section 9](../guides/SIMM_GUIDE.md#9-api-command-line-and-settings)).**

| What | Command | API | CLI |
|---|---|---|---|
| SIMM of a netting set (the computed figure) | `SIMM <netting set>` | `GET /api/v1/simm?nettingSet=…&regime=…` | `drishti.py simm show` |
| The playground and its recipes | `PLAY SIMM <netting set>` | `/api/v1/simm/playground` (and `.xlsx`) | `drishti.py play simm` |
| The sensitivities and the dictionary | `SENS`, `RFAC` ([FRTB.md section 1](FRTB.md#1-summary)) | | |

| Part | Status | Where |
|---|---|---|
| Sensitivities: the shared `position-sensitivity` (SENS) and the dictionary `risk-factor` (RFAC) | contracts `planned`, demo data generated | [FRTB.md section 3.2](FRTB.md#32-the-shared-sensitivity-table-position-sensitivity-sens), section 3.2 here |
| The CSA's SIMM terms (version, regimes, calculation currency, add-ons) | read from the `csa` records when present (`SIMM_VERSION`, `IM_COLLECT_REGIMES`, `IM_POST_REGIMES`, `IM_CALCULATION_CURRENCY`, `IM_MODEL`, `IM_FIXED_ADDON`, `IM_PRODUCT_MULTIPLIERS`, `IM_ADDON_FACTORS`, `IM_DISPUTE_TOLERANCE`); not in its contract yet | section 4 |
| Notional add-on lines (`simm-addon`) and the counterparty's CRIF (`crif-external`) | drafts | sections 3.3, 3.4 |
| The parameter file per SIMM version, shipped empty | built: the structure `capital-parameters/simm.yaml`, read and checked by the engine (`DRS-5333`) | section 6.1, [SIMM_GUIDE.md section 4](../guides/SIMM_GUIDE.md#4-the-licensed-parameter-file) |
| The engine, the computed kind `simm-result` (the answer of `GET /api/v1/simm`), the playground | built (the contributions and incremental IM of section 9.2 and the CRIF export are not yet) | sections 6 to 9, [SIMM_GUIDE.md](../guides/SIMM_GUIDE.md) |

## 2. The measure in one page

**What it answers:** *how much initial margin should we collect and post on this netting set today, what drives it, and why does it
differ from the counterparty's number?*

1. The netting set's trades are taken from the netting-set master; their sensitivities are read from SENS and grouped by **product
   class** (RatesFX, Credit, Equity, Commodity: each trade belongs to one).
2. Within a product class, sensitivities to the same risk factor are **netted** (the only additive step).
3. Each net sensitivity is multiplied by its risk weight and by a **concentration factor** (≥ 1) that grows when the net exposure at its
   grain (a currency for rates, an issuer and seniority for credit, a factor for equity, commodity and FX) exceeds the bucket's
   threshold.
4. **Within a bucket** the weighted sensitivities are aggregated with the correlations ρ (scaled by a ratio of concentration factors);
   **across buckets** with γ, each bucket's sum always capped at its charge; residual buckets added outside the root. This gives the
   delta margin of each risk class; vega, curvature and base correlation likewise.
5. A risk class's margin is the sum of its delta, vega, curvature and base-correlation margins; within a product class the six risk
   classes aggregate with the correlations ψ; **the four product classes are added**, with no correlation between them.
6. The CSA's add-ons come on top. Collect and post are computed separately per regime.

## 3. Inputs from upstream engines

### 3.1 The tables

```
${DRISHTI_DELTA_ROOT}/risk/
├── position-sensitivity/   the shared sensitivities (FRTB.md section 3.2)                                     (planned)
├── risk-factor/            the dictionary: SIMM_RISK_CLASS, SIMM_BUCKET, SIMM_LABEL1, SIMM_LABEL2              (planned)
├── simm-addon/             one row per trade with a notional-based add-on                                      (draft, optional)
└── crif-external/          the counterparty's or a custodian's CRIF and IM, for the comparison                 (draft, optional)
```

No separate SIMM sensitivity table: SIMM reads the same records as FRTB-SA. **What SIMM reads from SENS:** the elements with measures
`DV01`, `IE01`, `CS01`, `DELTA`, `VEGA` (with `IMPLIED_VOL`) and `BASE_CORR`; not `CURVATURE_UP`, `CURVATURE_DOWN` or `GAMMA` (SIMM's
curvature is computed from the vegas). A bank whose systems already produce CRIF loads it into SENS by the mapping of
[FRTB_DATA_STRUCTURES.md section 11.1](FRTB_DATA_STRUCTURES.md#111-from-isda-crif-rows).

**Units, as delivered** ([design section 2.3](../architecture/SENSITIVITIES_AND_FRTB_SA.md#23-from-the-delivered-conventions-to-the-regulatory-sensitivities)):

```
SIMM delta                         DV01, IE01, CS01 per bp and DELTA per 1 %, as delivered (SIMM's own units)
SIMM vega, rates and credit        VR_k = Σ_j VEGA_kj · 100 · IMPLIED_VOL_kj        (σ the implied volatility)
SIMM vega, equity, FX, commodity   VR_k = HVR_c · Σ_j VEGA_kj · σ_kj,   σ_kj = RW_k · sqrt(365 / 14) / Φ⁻¹(99 %)
                                   (σ from the factor's SIMM delta risk weight, not the market's implied volatility)
```

`VEGA` is the change for one volatility point (σ + 1 with σ in per cent), the same quantity SIMM's methodology uses. Every element is
converted to the calculation currency at the date's FX before anything is added; the FX sensitivity to the calculation currency itself is
left out.

### 3.2 The CRIF mapping: Drishti columns and ISDA CRIF

Drishti maps its records to CRIF terms to compute, to export a netting set's CRIF for a counterparty or vendor, and to compare with the
counterparty's CRIF. The reverse mapping (CRIF to SENS, for loading) is in
[FRTB_DATA_STRUCTURES.md section 11.1](FRTB_DATA_STRUCTURES.md#111-from-isda-crif-rows).

| ISDA CRIF field | From | Rule |
|---|---|---|
| `TradeID` | SENS `TRADE_ID`, or each of `TRADE_IDS` | a position record covering several trades is exported per position with its trades listed |
| `PortfolioID` | the netting-set master | not on SENS |
| `ProductClass` | `PRODUCT_GROUP` (or `PRODUCT_TYPE`) through the product-class mapping | section 6.2 |
| `RiskType` | the dictionary's `SIMM_RISK_CLASS` and `FACTOR_TYPE`, and the element's `MEASURE` | table below |
| `Qualifier` | the dictionary's `QUALIFIER` | currency (rates, FX), issuer (credit; with its seniority, section 16), name (equity), commodity |
| `Bucket` | the dictionary's `SIMM_BUCKET` | `Residual` as text |
| `Label1` | the dictionary's `SIMM_LABEL1` | lower-case tenor (`2w`, `1m`, `3m`, `6m`, `1y` … `30y`) or expiry; `Inf` for inflation |
| `Label2` | the dictionary's `SIMM_LABEL2` | the rates sub-curve (`OIS`, `Libor3m`, `Prime` …); `Sec` for credit securitisations |
| `Amount`, `AmountCurrency` | `VALUE`, `CURRENCY` | delta as delivered; vega by the CRIF definition of the version (section 16) |
| `AmountUSD` | `VALUE` at the date's FX | |
| `CollectRegulations`, `PostRegulations` | the CSA | section 4 |
| `EndDate` | `MATURITY_DATE` | |

| `SIMM_RISK_CLASS` / `FACTOR_TYPE` | Delta `RiskType` | Vega `RiskType` |
|---|---|---|
| `InterestRate` / `CURVE` | `Risk_IRCurve` | `Risk_IRVol` |
| `InterestRate` / `INFLATION` | `Risk_Inflation` (the currency's inflation sensitivities added) | `Risk_InflationVol` |
| `InterestRate` / cross-currency basis | `Risk_XCcyBasis` (no factor name yet, [FRTB.md section 16](FRTB.md#16-open-questions-and-decisions)) | |
| `CreditQ` / `SPREAD` | `Risk_CreditQ`; `BASE_CORR` elements `Risk_BaseCorr` | `Risk_CreditVol` |
| `CreditNonQ` / `SPREAD` | `Risk_CreditNonQ` | `Risk_CreditVolNonQ` |
| `Equity` / `SPOT`, `REPO` | `Risk_Equity` (repo per the version's CRIF rule) | `Risk_EquityVol` |
| `Commodity` / `PRICE` | `Risk_Commodity` (the tenors of a commodity added: SIMM has no commodity tenor) | `Risk_CommodityVol` |
| `FX` / `SPOT` | `Risk_FX` | `Risk_FXVol` |

**Credit indices** are one qualifier (decision 5): `Risk_CreditQ` on the index, in the `Residual` bucket in the demo; look-through to the
constituents is a later option.

### 3.3 simm-addon (draft, optional)

The notional-based add-on lines a CSA agrees for products SIMM does not cover well. One row per trade and business date:

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `ADDON_ID` | id | required | `ADD-<TRADE_ID>-<BATCH_ID>` | `ADD-MX-20000311-B-ADD-2026-09-30-01` |
| `TRADE_ID`, `BUSINESS_DATE`, `BATCH_ID` | id, date | required | | `MX-20000311` |
| `PRODUCT` | text | required | the product the CSA names the factor for | `Variance swap` |
| `NOTIONAL` | amount | required | the trade's notional | `25000000` |
| `CURRENCY` | text | required | of the notional | `USD` |

The factor per product, the fixed amount and the product-class multipliers are terms of the CSA (section 4), not rows.

### 3.4 crif-external (draft, optional)

The counterparty's (or a custodian's or reconciliation service's) CRIF and IM per netting set, as received, for the comparison of
section 9.4: the CRIF fields of section 3.2, plus `NETTING_SET`, `SOURCE` (`COUNTERPARTY`, `CUSTODIAN`, `VENDOR`), `DIRECTION`
(`COLLECT`, `POST`, from the bank's view), `ROW_TYPE` (`HEADER` with `IM_TOTAL` and the IM per risk class, or `CRIF`). Never an input to
the margin: Drishti compares, it does not compute from it.

### 3.5 Delivery and completeness

As FRTB ([FRTB.md section 3.6](FRTB.md#36-delivery-cadence-batches-intraday-and-restatements)): one full run per source and date,
intraday records replacing a position's record whole, restatements by `RESTATEMENT` and `COMPUTED_AT`. **Complete when** every live,
uncleared trade of a netting set under SIMM has sensitivities; a netting set with missing trades shows the count and is labelled
*incomplete*; a trade under schedule IM is left out and counted.

## 4. Reference and master data read

| Master (kind) | Key | What for |
|---|---|---|
| `netting-set` (NSET) | `NETTING_SET`, `TRADE_IDS` | the trades of the margin's scope on the date |
| `csa` (CSA) | `CSA_ID` | the IM terms: **SIMM version**, **collect and post regimes**, **calculation currency**, add-on terms (fixed amount, notional factors per product, product-class multipliers ≥ 1), SIMM or schedule IM. These terms are not in the CSA contract today (section 16) |
| `counterparty` (CPTY) | `COUNTERPARTY` | the counterparty's netting sets |
| `trade` (TRD) | `TRADE_ID` | live status, the cleared flag (cleared trades are margined by the clearing house and left out), `PRODUCT_GROUP` |
| `risk-factor` (RFAC) | `RISK_FACTOR` | `SIMM_RISK_CLASS`, `SIMM_BUCKET`, `SIMM_LABEL1`, `SIMM_LABEL2`, `QUALIFIER` |
| The SIMM parameter file of the version | `version`, SHA-256 | section 6.1 |

## 5. Conventions specific to SIMM

| Convention | Rule |
|---|---|
| Sensitivity sign | the bank's gain for the up-move (as FRTB); **post** is computed on every sensitivity negated (the counterparty's view) |
| IM | reported positive; collect is what the bank should receive, post what it should give |
| Calculation currency | the CSA's (USD by default); the thresholds, in USD in the methodology, converted at the date's FX |
| Version | the CSA's; a netting set whose version has no installed file is refused, never computed with another |
| Rounding | float64 throughout; whole currency units on screen; the CSA's rounding only on the expected call |

## 6. The computation

[Design section 8](../architecture/SENSITIVITIES_AND_FRTB_SA.md#8-isda-simm), checked against the methodology in
[design section 18](../architecture/SENSITIVITIES_AND_FRTB_SA.md#18-checked-against-the-rules). Paragraph numbers are those of SIMM
2.8+2512.

### 6.1 Parameters: one licensed file per version

The structure is [`simm.yaml`](../architecture/capital-parameters/simm.yaml): `version`, `effective-from`, `source`,
`holding-period-days`, the product and risk classes, `risk-class-correlation` (ψ), and per risk class the tenors, buckets, delta risk
weights, correlations (ρ, the sub-curve φ for rates, γ), vega risk weights, concentration thresholds (delta and vega) and historical
volatility ratios (HVR); the curvature constants; the add-on keys. **Every number is `null` as shipped.** The bank puts each licensed
version in its own copy, one file per version. The engine refuses a version with a value it needs missing, keeps the file's SHA-256, and
records the version on every figure, export, recipe and capture.

### 6.2 Product classes

Each trade belongs to one product class (paragraph 6), from its `PRODUCT_GROUP` (or `PRODUCT_TYPE`) by the bank's mapping, a
configuration: for example Rates, Inflation, Money market, FX → `RatesFX`; Credit → `Credit`; Equity → `Equity`; Commodity → `Commodity`.
Every sensitivity of the trade, whatever its risk class, stays in the trade's product class (an equity swap's interest-rate delta is in
`Equity`). **The product classes are added without correlation** (paragraph 6; the parameter file carries no correlation across them).

### 6.3 Delta margin of a risk class

For a netting set, regime, product class and risk class, with s_k the netted sensitivity in SIMM's units and T_b the delta threshold of
the bucket:

```
Concentration (paragraphs 7(b), 8(b), section J), at the grain the rules set:
  InterestRate          CR_b = max( 1, sqrt( |Σ_{k,i} s_k,i| / T_b ) )   one per currency: every vertex and sub-curve, inflation in,
                                                                         cross-currency basis out (and not scaled)
  CreditQ, CreditNonQ   CR_k = max( 1, sqrt( |Σ_j s_j| / T_b ) )          one per issuer and seniority over all its tenors and currencies;
                                                                         base-correlation sensitivities take CR = 1
  Equity, Commodity, FX CR_k = max( 1, sqrt( |s_k| / T_b ) )              one per factor (the name, the commodity, the currency)
WS_k = RW_k · s_k · CR
K_b  = sqrt( Σ_k WS_k² + Σ_k Σ_{l≠k} ρ_kl · f_kl · WS_k · WS_l )        f_kl = min(CR_k, CR_l) / max(CR_k, CR_l); f = 1 for rates,
                                                                        whose ρ is the tenor correlation × φ between sub-curves
S_b  = max( min( Σ_k WS_k, K_b ), −K_b )                                  always capped (paragraphs 7(d), 8(d))
Delta = sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc · g_bc · S_b · S_c ) + K_residual
        g_bc = min(CR_b, CR_c) / max(CR_b, CR_c) for rates, 1 otherwise; the residual bucket (credit, equity) outside the root
```

### 6.4 Vega margin (paragraph 10)

The vega risk VR_k of section 3.1, netted per factor, then `VR_k = VRW · Σ_i VR_ik · VCR`, the vega concentration factor VCR at the same
grains as delta against the vega thresholds VT_b. Within a bucket f_kl from the VCRs (1 for rates); across buckets g_bc for rates,
1 otherwise; S_b capped; residual outside the root.

### 6.5 Curvature margin (paragraph 11)

```
CVR_k = Σ_i Σ_expiries SF(t) · σ · ∂V/∂σ          SF(t) = 0.5 · min(1, 14 / t_days)   (12 months = 365 days, pro rata); no concentration;
                                                  zero for equity bucket 12 (volatility indices)
K_b   = sqrt( Σ_k CVR_k² + Σ_k Σ_{l≠k} ρ_kl² · CVR_k · CVR_l )
θ     = min( Σ CVR / Σ |CVR|, 0 )        λ = ( Φ⁻¹(99.5 %)² − 1 ) · (1 + θ) − θ
Curvature_nonres = max( Σ CVR + λ · sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc² · S_b · S_c ), 0 )      S_b capped at ±K_b
Curvature_res    = max( Σ CVR_res + λ_res · K_res, 0 );   Curvature = the two added;   rates: × HVR_IR⁻²
```

### 6.6 Base correlation (paragraph 13)

`sqrt( Σ WS_k² + Σ_k Σ_{l≠k} ρ_kl · WS_k · WS_l )` over the index families of CreditQ, from `BASE_CORR` elements, no concentration.

### 6.7 Up to the total (paragraphs 5 and 6, sections K and L)

```
IM_r     = Delta_r + Vega_r + Curvature_r (+ BaseCorr_r)
SIMM_p   = sqrt( Σ_r IM_r² + Σ_r Σ_{s≠r} ψ_rs · IM_r · IM_s )                  ψ the 6 × 6 correlation between risk classes
SIMM     = SIMM_RatesFX + SIMM_Credit + SIMM_Equity + SIMM_Commodity          a plain sum
AddOnIM  = fixed amount + Σ_p factor_p · notional_p + Σ_p (MS_p − 1) · SIMM_p   each multiplier MS_p ≥ 1
IM       = SIMM + AddOnIM
```

### 6.8 Collect, post and regimes

| Figure | Rule |
|---|---|
| Collect | for each regime the CSA lists for collection: IM over the trades in its scope; the binding regime and its IM reported |
| Post | for each regime listed for posting: IM over those trades with every sensitivity negated |
| No regime | one computation over the netting set's SIMM trades, regime `NONE` |
| Symmetry | delta and vega give the same margin when negated; curvature does not (θ and the floor), so collect and post differ for option-heavy sets |

### 6.9 The method choices (the playground's Choices panel)

| Switch | Default | Alternatives |
|---|---|---|
| `simm-parameters` | the bank's licensed file of the CSA's version | another installed version beside it (for a recalibration date) |
| `concentration-thresholds` | the version's | typed overrides (*not the regulatory figure*) |
| `hvr` | the version's | typed overrides (*not the regulatory figure*) |
| `overrides` | none | any risk weight or correlation typed by a validator |

## 7. What Drishti builds

| Structure | Content | Size, 50,000 netting sets × 200 factors (estimate) |
|---|---|---|
| The sensitivity store and the dictionary | shared with FRTB ([FRTB.md section 7](FRTB.md#7-what-drishti-builds)); the dictionary's SIMM attributes as int codes | shared |
| Netting-set index | netting set → position (or trade) ordinals on the date, with the CSA's terms | 4 B a trade + 200 B a set: about 14 MB |
| Netting-set vectors | per netting set × product class: the sparse netted sensitivities in SIMM units, built at load in one pass | 50,000 × 200 × 12 B = about 120 MB |
| Concentration sums | per netting set: the sums at each grain (currency, issuer and seniority, factor) | inside the vectors |
| Parameters | per installed version: typed tables and the SHA-256 | small |

A position record that covers trades of two netting sets is split by trade; a source that sends by position must therefore send
`TRADE_IDS` and the position's trades must lie in one netting set, or send `GRAIN` `TRADE` (section 10).

## 8. Scopes

| Scope | Meaning |
|---|---|
| A netting set | **the margin**: its uncleared SIMM trades, its CSA's version and regimes |
| A counterparty | each of its netting sets computed alone and listed with their sum (SIMM does not net across netting sets) |
| Any other scope (a desk, a book, a basket) | an **analytic**: SIMM of those trades as one portfolio, the default version, no regimes; labelled *analytic, not a margin* |

## 9. What Drishti answers

### 9.1 The headline and the computed kind `simm-result`

One record per netting set (or scope), regime and business date:

| Field | Meaning |
|---|---|
| `SCOPE`, `NETTING_SET`, `COUNTERPARTY`, `CSA`, `BUSINESS_DATE`, `ANALYTIC` | what was computed; true when the scope is not a netting set |
| `SIMM_VERSION`, `PARAMETER_SHA256` | the licensed file used |
| `IM_COLLECT`, `IM_POST`, `COLLECT_REGIME`, `POST_REGIME` | the margins and the binding regimes |
| `SIMM_COLLECT`, `ADDON_COLLECT`, `SIMM_POST`, `ADDON_POST` | SIMM and the add-ons, each direction |
| `BY_PRODUCT_CLASS[]` | `PRODUCT_CLASS`, `COLLECT`, `POST` |
| `BY_RISK_CLASS[]` | `PRODUCT_CLASS`, `RISK_CLASS`, `DELTA`, `VEGA`, `CURVATURE`, `BASE_CORR`, `TOTAL` |
| `BUCKETS[]`, `FACTORS[]` | per bucket K_b, S_b (capped); per factor s_k, RW, CR (or VCR), WS, the gradient |
| `CALCULATION_CURRENCY`, `IM_COLLECT_CCY`, `IM_POST_CCY` | in the CSA's currency |
| `TRADES`, `TRADES_SCHEDULE_IM`, `TRADES_CLEARED`, `MISSING_TRADES` | counts |
| `SWITCHES`, `OVERRIDES[]`, `REGULATORY` | as FRTB-SA |
| `PROVENANCE` | the batches and table versions, the dictionary version, the parameter file's version and SHA-256, the CSA's terms used |

### 9.2 Contributions and incremental margin

The marginal IM of each trade, `Σ_k s_k(trade) · ∂IM/∂s_k` with the concentration factors held fixed, scaled to add up; and the
incremental IM of a candidate trade, `IM(set + trade) − IM(set)`, computed in a private sandbox and never stored.

### 9.3 The playground (`PLAY SIMM`)

One netting set, or a few side by side ([design section 12.2](../architecture/SENSITIVITIES_AND_FRTB_SA.md#122-simm-playground-play-simm)).
Tabs: Main (SIMM per product class and the total against the engine, with check cells), Config (the licensed parameters of the version
and the Choices of section 6.9), Data (the sensitivities), WS (with the concentration factors), Buckets, Classes (ψ), Curvature (SF(t),
θ, λ), Add-ons, Explain, Provenance. **The template ships with formulas and empty parameter cells**; the bank's export fills them from
its licensed file and is never a shared template. Every run is a recipe with the same structure as FRTB-SA's
([FRTB_DATA_STRUCTURES.md section 9.3](FRTB_DATA_STRUCTURES.md#93-playground-recipe)), the netting set and the SIMM version in place of the
filer and regime, replayable to the cent.

### 9.4 Margin against the counterparty's call

For a netting set with `crif-external` rows: the IM in total and by product class, risk class and bucket, side by side (Drishti, the
other side, the difference); and the CRIF lines that differ (a trade on one side only, a different bucket or qualifier, an amount beyond a
tolerance). It explains *why* the numbers differ, and never recomputes the counterparty's figure.

## 10. Validation, errors and data quality

The sensitivity checks of [FRTB.md section 10](FRTB.md#10-validation-errors-and-data-quality) hold. SIMM adds (range `DRS-533x`; also
`DRS-5336`, a rates or credit vega without `IMPLIED_VOL`, left out and counted):

| Check | Outcome | Words |
|---|---|---|
| The factor has a SIMM mapping in the dictionary | the element is left out of SIMM, counted | `DRS-5331` *<factor> has no SIMM bucket; left out of SIMM* |
| A trade in exactly one netting set on the date | in two: kept in both, flagged; in none: in no netting set | `DRS-5332` *trade <id> is in netting sets <a> and <b> on <date>* |
| The CSA's version is installed and complete | the netting set is not computed | `DRS-5333` *SIMM <version> is not installed (or has no <parameter>); <netting set> is not computed* |
| Regimes known to the version | unknown codes ignored and listed | `DRS-5334` *regime <code> is not recognised by SIMM <version>* |
| Cleared trades | left out and counted | *<n> cleared trades are margined by the clearing house* |
| A position record whose trades span netting sets | the record is left out of SIMM, counted | `DRS-5335` *<SENSITIVITY_ID> covers trades of several netting sets; send it by trade* |

## 11. Entitlements and audit

VaR's model with the gate kind `position-sensitivity`, and per counterparty: a role limited to some counterparties sees their netting
sets only. **Licence:** the parameter values appear only in the bank's own screens and exports, never in a shared template, a sample or
the repository; exports that contain them are marked confidential. **Audited:** every CRIF export (trade level), every playground export,
every recipe saved or replayed, every override, every incremental-IM request.

## 12. Performance and sizing

Estimates, to be measured.

| Shape | Netting sets | Store |
|---|---|---|
| 300,000 trades | about 10,000 netting sets, 200 factors each | the shared store plus about 25 MB of netting-set vectors |
| 2.3 M positions | about 50,000 netting sets | the shared store plus about 120 MB |

| Operation | Target, warm (estimate) |
|---|---|
| IM of one netting set, both directions | under 50 ms |
| Every netting set of a counterparty (20 sets) | under 500 ms |
| The daily run of 50,000 netting sets | a few minutes, in parallel |
| Incremental IM of a candidate trade | under 200 ms |

## 13. Test data and generators

The demo sensitivities ([FRTB.md section 13](FRTB.md#13-test-data-and-generators)) carry each factor's SIMM bucket and labels through the
dictionary; the netting sets and CSAs are those of the counterparty-risk pack. **No licensed number is in the repository:** the engine's
tests use toy parameter sets (cases 6 and 11 of the design and the example of section 15), which prove the formulas and never the
licensed values; a bank runs ISDA's own test portfolios in its environment with its licensed file. Until a licensed file is installed the
demo shows no computed SIMM figure.

## 14. Retiring the stored version

The stored kind `simm` (one record per counterparty, a total from mark-to-market and fixed risk-class shares) is not sensitivity-based.
When the engine is built, the computed figure replaces it **for the netting sets that have sensitivities**; the others stay on the stored
figure, labelled *stored, not computed*, until the bank's sensitivities cover them. No factor relates the two.

## 15. A worked example

**Toy parameters, not ISDA's.** Cases 6 and 11 of the design
([design section 13.1](../architecture/SENSITIVITIES_AND_FRTB_SA.md#131-hand-built-cases)) and one netting set end to end.

**Case 6.** One currency, WS = 50 · (−1,000) and 60 · 400 per bp, ρ = 0.8, CR = 1:
`K = sqrt(50,000² + 24,000² − 2 · 0.8 · 50,000 · 24,000) = 34,000`. Two risk classes of one product class, IM 50,000 and 30,000,
ψ = 0.3: `SIMM_p = sqrt(50,000² + 30,000² + 2 · 0.3 · 50,000 · 30,000) = 65,574.39`.

**Case 11: concentration per issuer.** One credit bucket, `T_b = 1,000` per bp, RW 100, ρ = 0.4 between issuers. Issuer X nets CS01
−4,000 over its tenors, so `CR_X = 2`; issuer Y +1,000, `CR_Y = 1`; `f_XY = 0.5`. `WS_X = −800,000`, `WS_Y = 100,000`,
`K = sqrt(800,000² + 100,000² − 2 · 0.4 · 0.5 · 800,000 · 100,000) = 786,129.76`. A bucket-level factor would give a different figure.

**One netting set, product class RatesFX.** Toy risk weights USD 5y 50, 10y 51, EUR 2y 54 (per bp), FX 8 (per 1 %); ρ(5y, 10y) = 0.94;
γ(USD, EUR) = 0.30; ψ(IR, FX) = 0.25; thresholds high enough that CR = 1:

| Factor (CRIF) | Net sensitivity | RW | WS |
|---|---|---|---|
| `Risk_IRCurve` USD `5y` `OIS` | +10,000 per bp | 50 | 500,000 |
| `Risk_IRCurve` USD `10y` `OIS` | −6,000 per bp | 51 | −306,000 |
| `Risk_IRCurve` EUR `2y` `OIS` | +4,000 per bp | 54 | 216,000 |
| `Risk_FX` EUR | +20,000 per 1 % | 8 | 160,000 |

| Step | Computation | Value |
|---|---|---|
| K_USD | sqrt(500,000² + 306,000² − 2 · 0.94 · 500,000 · 306,000) | 236,634.74 |
| S_USD, S_EUR | Σ WS capped at ±K: 194,000; 216,000 | |
| Delta_IR | sqrt(236,634.74² + 216,000² + 2 · 0.30 · 194,000 · 216,000) | 357,483.43 |
| Delta_FX | one factor | 160,000 |
| SIMM_RatesFX | sqrt(357,483.43² + 160,000² + 2 · 0.25 · 357,483.43 · 160,000) | **426,606.46** = collect = post (delta only) |

**With concentration.** A USD threshold of 3,000 per bp: `CR_USD = sqrt(4,000 / 3,000) = 1.154701` (one factor for the currency);
the USD WS become 577,350.27 and −353,338.36, K_USD = 273,242.26, S_USD = 224,011.90, `g(USD, EUR) = 1 / 1.154701 = 0.866025`,
Delta_IR = 382,700.58 and **SIMM = 450,195.27**: the concentration adds 23,588.81.

## 16. Open questions and decisions

**Decided** ([design section 16](../architecture/SENSITIVITIES_AND_FRTB_SA.md#16-decisions)): SIMM's numbers shipped empty, the bank's
licensed file by version recorded on every figure (11); credit indices one qualifier, look-through later (5); rates vega in lognormal
points by default, normal declared per source (3); the screens: the netting-set list with the counterparty comparison as a second tab (1).

**Open:**

| # | Question | Context |
|---|---|---|
| 1 | The CSA's SIMM terms (version, collect and post regimes, calculation currency, add-on terms): new fields of the `csa` contract, or a separate SIMM-terms table | section 4 |
| 2 | Credit concentration and the CRIF qualifier are per issuer **and seniority**; neither the dictionary nor the factor names carry seniority yet | section 6.3 |
| 3 | The CRIF definition of each volatility row's `Amount` for the installed version, and the rule that turns it into `VEGA` per volatility point | section 3.2 |
| 4 | Equity repo under SIMM, per the installed version's CRIF rule | section 3.2 |
| 5 | SIMM-only vertices (`2w`, `1m`): delivered as their own factors (no FRTB vertex) or not at all | [FRTB.md section 16](FRTB.md#16-open-questions-and-decisions) |
| 6 | Schedule IM for the netting sets that use it: here, or with the margin call ([MC.md](MC.md)) | section 3.5 |
| 7 | The comparison sources (`crif-external`): which counterparties, custodians or vendors, in which format | section 3.4 |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
