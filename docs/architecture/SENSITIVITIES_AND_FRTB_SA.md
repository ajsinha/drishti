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
# Sensitivities and market-risk capital: position sensitivities, the factor dictionary, FRTB-SA and SIMM

Status: built for position sensitivities (SENS), the factor dictionary (RFAC) and FRTB-SA (SBM, DRC, RRAO) with the capital screen
(FRSA), the FRTB-SA Playground (PLAY FRTB) and its workbook ([FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md)); design, not built, for SIMM
and its playground (sections 8 and 12.2), the sensitivity-VaR consistency check (section 2.4), the bank's limit grids (section 3.3) and the
official FRTB-SA captures. Companion to [MR_CUBE_LENS.md](../lenses/MR_CUBE_LENS.md) (sections 7.9, 7.10 and 8.3),
[FRTB.md](../contracts/FRTB.md) and [SIMM.md](../contracts/SIMM.md) (the earlier contract designs this document narrows), and the
[VaR Playground](VAR_PLAYGROUND.md) (the playground frame every metric here reuses).

Drishti computes market-risk capital the way it computes VaR: **from trade-grain inputs, when asked, for any scope, never stored as a
figure.** The input is one table of position sensitivities; a dictionary of risk factors says what each factor is and where it falls in
each regulatory grid; the parameters are configuration files with their sources. From these three, Drishti computes the FRTB standardised
approach (the sensitivities-based method, the default risk charge and the residual risk add-on) and ISDA SIMM initial margin, and every
figure can be opened in a playground that shows the same calculation as spreadsheet formulas.

## Contents

- [1. Scope and principles](#1-scope-and-principles)
- [2. Position sensitivities: the input](#2-position-sensitivities-the-input)
- [3. The factor dictionary and the naming standard](#3-the-factor-dictionary-and-the-naming-standard)
- [4. The aggregation every method shares](#4-the-aggregation-every-method-shares)
- [5. FRTB-SA: the sensitivities-based method](#5-frtb-sa-the-sensitivities-based-method)
- [6. FRTB-SA: the default risk charge](#6-frtb-sa-the-default-risk-charge)
- [7. FRTB-SA: the residual risk add-on](#7-frtb-sa-the-residual-risk-add-on)
- [8. ISDA SIMM](#8-isda-simm)
- [9. Scopes, totals and contributions](#9-scopes-totals-and-contributions)
- [10. Where each number comes from](#10-where-each-number-comes-from)
- [11. API, command line and screens](#11-api-command-line-and-screens)
- [12. The playgrounds](#12-the-playgrounds)
- [13. The tests that prove it](#13-the-tests-that-prove-it)
- [14. Parameters as configuration](#14-parameters-as-configuration)
- [15. Mock-ups: the screens to build](#15-mock-ups-the-screens-to-build)
- [16. Decisions](#16-decisions)
- [17. The playgrounds' explanations](#17-the-playgrounds-explanations)
- [18. Checked against the rules](#18-checked-against-the-rules)

## 1. Scope and principles

| Part | What it is | Regulatory reference |
|---|---|---|
| Position sensitivities | the input: delta, vega and curvature of each position to standard risk factors | MAR21.8 to MAR21.26 (definitions) |
| The factor dictionary | every factor with its FRTB and SIMM mapping, and the bank's own names for it | MAR21.39 to MAR21.89 (buckets) |
| FRTB-SA SBM | delta, vega and curvature charges per risk class, three correlation scenarios | MAR21 |
| FRTB-SA DRC | the default risk charge, non-securitisations | MAR22 |
| FRTB-SA RRAO | the residual risk add-on | MAR23 |
| SIMM | initial margin per netting set and regime, from the same sensitivities | ISDA SIMM methodology (structure) |

**Principles.**

1. **One input table.** FRTB-SA, SIMM, the MR cube's ladders and heat maps, sensitivity limits, P&L explain and sensitivity-based stress
   read the same `position-sensitivity` records. A bank delivers one sensitivity batch, not one per consumer.
2. **Computed, never stored.** A charge is computed for the scope asked (a desk, a legal entity, a portfolio, an ad-hoc set of positions)
   and kept only as an official capture, as VaR is. The stored `frtb-sensitivity` charges retire when the engine is built.
3. **Parameters are configuration, with their source.** Risk weights and correlations live in versioned files that name the regulatory
   paragraph they come from (section 14). The engine checks them at start and refuses a file with a missing or out-of-range value.
4. **Every figure explains itself.** Each charge shows its steps (weighted sensitivities, bucket charges, the scenario chosen) and opens in
   its playground (section 12), where every derived cell is a spreadsheet formula and a check cell matches the engine to the cent.
5. **Nothing is guessed.** A sensitivity whose factor is not in the dictionary is refused and counted; a missing input is shown as
   missing, never as zero.

## 2. Position sensitivities: the input

The contract is [`config/packs/market-risk/contracts/position-sensitivity.contract.yaml`](../../config/packs/market-risk/contracts/position-sensitivity.contract.yaml)
(generated reference: [the market-risk contracts](../contracts/packs/market-risk.md), [a sample record explained](../contracts/samples/market-risk/position-sensitivity.md)).

### 2.1 The record

One record per position (or trade), business date and batch, with the sensitivities as a sparse list:

| Group | Fields | Notes |
|---|---|---|
| Identity | `SENSITIVITY_ID`, `GRAIN`, `POSITION_ID` or `TRADE_ID`, `TRADE_IDS` | the position as the pack's position rules derive it, so it joins the VaR vectors |
| The run | `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND`, `COMPUTED_AT` (the as-of), `RESTATEMENT`, `STATUS`, `PROXY_REASON` | one full run per date and source; intraday batches replace a record whole |
| Placement | `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`, `FRTB_DESK` | as on the P&L vectors |
| Terms | `PRODUCT_GROUP`, `PRODUCT_TYPE`, `TRADE_STATUS`, `MATURITY_DATE`, `CURRENCY` | the live rule, the RRAO's product classes, the DRC's maturity |
| `SENSITIVITIES[]` | `RISK_FACTOR`, `MEASURE`, `VALUE`, `CURRENCY`, `RISK_CLASS`, `FRTB_BUCKET`, `TENOR`, `SIMM_BUCKET`, `SHOCK_SIZE`, `IMPLIED_VOL` | one element per factor and measure |

The measures and their units:

| `MEASURE` | Factor types | `VALUE` is | Unit |
|---|---|---|---|
| `DV01` | `IR/CURVE/...` | V(r + 1 bp) − V(r) | per basis point |
| `IE01` | `INF/...` | V(i + 1 bp) − V(i) | per basis point |
| `CS01` | `CR/SPREAD/...` | V(cs + 1 bp) − V(cs) | per basis point |
| `DELTA` | `FX/SPOT`, `EQ/SPOT`, `EQ/REPO`, `CM/PRICE` | V(1.01·x) − V(x) (for repo: + 1 bp) | per 1 % relative move |
| `VEGA` | `VOL/...` | V(σ + 0.01) − V(σ) | per volatility point |
| `CURVATURE_UP`, `CURVATURE_DOWN` | the curve, issuer or underlying without a tenor | V(x + shock) − V(x) and V(x − shock) − V(x), delta included | currency, with `SHOCK_SIZE` |

### 2.2 Where the numbers come from

The sensitivities are computed by the bank's own pricing and risk systems, in the same run as the scenario PVs that feed VaR: Drishti does
not reprice. A front-office system usually bumps each curve point and spot by the standard move; curvature requires two full revaluations
per curvature factor with the regulatory shock (the shock of the factor's bucket, section 5.3). A source that cannot revalue for
curvature sends `GAMMA` instead and Drishti labels the curvature it derives as an estimate (decided, section 16; the formula and its
limits are in section 5.3).

### 2.3 From the delivered conventions to the regulatory sensitivities

MAR21.19 to MAR21.25 define each sensitivity per unit of the factor: for rates and spreads (and equity repo)
`s_k = (V(r_k + 0.0001) − V(r_k)) / 0.0001`, for spots and prices `s_k = (V(1.01·x_k) − V(x_k)) / 0.01`, and for vega (MAR21.25) the vega
risk sensitivity `s_k = vega_k · σ_k`, the vega times the implied volatility. From the delivered values:

```
FRTB delta, rates and spreads   s_k = DV01_k · 10,000         (also IE01, CS01, and DELTA of an equity repo, which is per bp)
FRTB delta, spot and price      s_k = DELTA_k · 100
FRTB vega                       s_k = VEGA_k · 100 · IMPLIED_VOL_k          (∂V/∂σ · σ)
SIMM delta                      DV01, CS01 per bp and DELTA per 1 % as delivered (SIMM's own units, its section C.2)
SIMM vega, rates and credit     Σ_j VEGA_kj · 100 · IMPLIED_VOL_kj          (σ the implied volatility, its paragraph 10(a), (c))
SIMM vega, equity, FX, commodity HVR_c · Σ_j VEGA_kj · σ_kj,  σ_kj = RW_k · sqrt(365 / 14) / Φ⁻¹(99 %)
                                (σ from the factor's SIMM delta risk weight, not the market's implied volatility; paragraph 10(b), (c))
```

For SIMM, `VEGA` is the change for one volatility point (σ + 1 with σ in per cent, SIMM's section C.3), the same quantity as the
delivered `VEGA`.

Each element is converted to the reporting currency at the date's FX before anything is summed.

### 2.4 Load and checks

The loader reads the records from the common risk lake (`<root>/risk/position-sensitivity/`), checks them, and builds one sparse column
per measure in memory, indexed like the VaR vectors (position ordinal, then factor ordinal). Refusals are counted per source and shown on
the data-trust card:

| Check | On failure |
|---|---|
| the factor name is in the dictionary, or a source name maps to exactly one factor | the element is refused (`DRS` code per refusal class) |
| `VALUE` finite; `CURRENCY` with FX on the date | the element is refused |
| the same factor and measure twice in a record | the record is refused |
| one full run per source and date | the second run is refused |
| curvature carries `SHOCK_SIZE`, equal to the bucket's risk weight | a warning |
| a position with VaR vectors and no sensitivities, or the reverse | a completeness warning by desk |
| the element's bucket differs from the dictionary's | a warning; the dictionary wins |

**Consistency with VaR (proposed).** For a sample of positions, the first-order P&L Σ_k s_k · Δx_k,j of each scenario j is compared with
the position's scenario P&L; the correlation per desk is shown on the data-trust card. It catches sensitivities and vectors from different
runs or different trades.

### 2.5 The demo data

`tools/packgen/sensitivity/make_sensitivity_data.py` writes `data/testdata/market-risk/position-sensitivity/` and
`data/testdata/market-risk/risk-factor/` from the committed VaR inputs of the business date: the 7,984 positions the pack's rules derive, one
record a position from Murex and Calypso and one a trade from Endur (a source that sends by trade; a position's sensitivities shared among
its trades by quantity), 8,052 records and 25,821 elements (rate, credit and inflation ladders split onto the two vertices around maturity, spot and price
deltas, vegas at the option's expiry, curvature on options). The factor loadings and the one-sigma P&L of each position are the VaR demo's
own factor model, so a desk's sensitivities and its VaR tell the same story; about one record in a hundred is `proxied`. The files are
deterministic from the inputs and checked by `tools/packgen/sensitivity/test_sensitivity_data.py`. `lake demo` loads them into risk-store with the
other risk kinds.

## 3. The factor dictionary and the naming standard

The contract is [`config/packs/market-risk/contracts/risk-factor.contract.yaml`](../../config/packs/market-risk/contracts/risk-factor.contract.yaml)
([a sample record explained](../contracts/samples/market-risk/risk-factor.md)).

### 3.1 The naming standard

One text name per factor, slash-separated, the same in every system Drishti reads:

| Pattern | Factor | Example |
|---|---|---|
| `IR/CURVE/<ccy>/<curve>/<tenor>` | a point of an interest-rate curve | `IR/CURVE/USD/SOFR/10Y` |
| `IR/CURVE/<ccy>/<curve>` | the curve as a whole (curvature) | `IR/CURVE/USD/SOFR` |
| `INF/<index>/<tenor>` | an inflation curve point | `INF/USCPI/10Y` |
| `CR/SPREAD/<issuer>/<BOND or CDS>/<tenor>` | a credit spread point | `CR/SPREAD/ISS-FIN-IG-01/CDS/5Y` |
| `CR/SPREAD/<issuer>` | the issuer (curvature) | `CR/SPREAD/ISS-FIN-IG-01` |
| `FX/SPOT/<ccy>` | the currency against the reporting currency | `FX/SPOT/EUR` |
| `EQ/SPOT/<underlying>`, `EQ/REPO/<name>` | an equity price, a repo rate | `EQ/SPOT/SPX` |
| `CM/PRICE/<underlying>/<location>/<tenor>` | a commodity forward | `CM/PRICE/WTI/CUSHING/1Y` |
| `VOL/<class>/<underlying>/<expiry>[/<underlying tenor>]` | an implied volatility | `VOL/IR/USD/SOFR/1Y/10Y` |

A real bank uses an LEI for the issuer and an ISIN or index code for the equity; the demo uses synthetic ids.

### 3.2 The mapping from the bank's names

Each dictionary row lists `SOURCE_NAMES`, the names the source systems give the factor (`MX:IR:CURVE-USD-SOFR-10Y`,
`IR.CURVE.USD.SOFR.10Y`, `10Y_SOFR_USD_CURVE_IR` in the demo). A name maps to exactly one factor (checked when the dictionary loads; a name
in two rows refuses the dictionary). A sensitivity delivered under a name in no list is refused and counted; a new factor is added to
the dictionary, never guessed from its name.

### 3.3 The grids: FRTB, SIMM and the bank's own

A **grid** maps factors to buckets by rules on the parts. Three kinds, kept side by side:

- **FRTB grids** (MAR21): GIRR and FX by currency, CSR by sector and credit quality (18 buckets), equity by size, economy and sector (13),
  commodity by commodity group (11).
- **The SIMM grid** of each SIMM version: interest rates by the currency's volatility group, credit by sector and quality, equity, commodity
  and FX by their groups, with Label1 (tenor) and Label2 (sub-curve).
- **The bank's limit grids:** for example a DV01 ladder 0–2Y, 2–5Y, 5–10Y, 10Y+ per currency, which sensitivity limits name.

The dictionary's `FRTB_BUCKET` and `SIMM_BUCKET` are defaults; the grid configuration decides, and a factor falls in exactly one bucket of
each grid (checked at start; an overlap or a gap refuses the grid).

### 3.4 The demo dictionary

855 factors: ten rate curves (USD SOFR, EUR €STR, GBP SONIA, JPY TONA, AUD, CAD, CHF, BRL, MXN, CNH) at ten vertices with their swaption
volatilities; three inflation indices; 36 issuers (six sectors, investment grade and high yield) and three credit indices, bond and CDS
curves at five vertices; nine currencies; twelve equity underlyings (four indices, eight names) with repo and volatility; ten commodities
at six forward points. Written by `tools/packgen/sensitivity/factor_dictionary.py`.

## 4. The aggregation every method shares

FRTB-SA and SIMM aggregate in the same three steps; they differ in their parameters and a few rules, named under each step.

**Step 1: weighted sensitivity.** Sensitivities to the same factor are netted first (summed across the positions of the scope,
MAR21.4(2)); then, for factor *k* with risk weight `RW_k`:

```
WS_k = RW_k · s_k                          FRTB (MAR21.4(3))
WS_k = RW_k · s_k · CR                     SIMM: CR ≥ 1, the concentration factor (section 8): one per currency for interest
                                           rates, one per issuer and seniority for credit, one per factor for equity,
                                           commodity and FX
```

**Step 2: within a bucket b.**

```
K_b = sqrt( max( 0, Σ_k WS_k² + Σ_k Σ_{l≠k} ρ_kl · WS_k · WS_l ) )                 FRTB (MAR21.4(4))
K_b = sqrt( Σ_k WS_k² + Σ_k Σ_{l≠k} ρ_kl · f_kl · WS_k · WS_l ),  f_kl = min(CR_k, CR_l) / max(CR_k, CR_l)
                                                                                    SIMM, except interest rates (f = 1)
```

FRTB's "other sector" buckets (CSR non-securitisation bucket 16, equity bucket 11) use no correlation: `K_b = Σ_k |WS_k|` (MAR21.56,
MAR21.79).

**Step 3: across buckets.**

```
Δ = sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc · S_b · S_c )      with S_b = Σ_k WS_k              FRTB (MAR21.4(5)(a))
```

If the sum under the root is negative (hedges across buckets larger than the buckets), the **alternative specification** (MAR21.4(5)(b))
caps each bucket's net at its charge: `S_b = max( min( Σ_k WS_k, K_b ), −K_b )`, and the root is taken again.

SIMM differs here in three ways: it **always** uses the capped `S_b = max( min( Σ_k WS_k, K_b ), −K_b )`; for interest rates the
cross term carries `g_bc = min(CR_b, CR_c) / max(CR_b, CR_c)`; and the residual bucket of credit and equity is added outside the root,
`Δ = sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc · S_b · S_c ) + K_residual` (SIMM paragraphs 7(d), 8(d)).

**The three correlation scenarios (MAR21.6)** apply to every ρ and γ of the SBM (FRTB only; SIMM has one set of correlations):

```
medium  ρ                                    (the prescribed correlations)
high    min( 1.25 · ρ, 1 )
low     max( 2 · ρ − 1, 0.75 · ρ )
```

Curvature's correlations are the delta correlations squared (MAR21.100, MAR21.101), and the scenarios are applied **to the squared
value**: high `min(1.25 · ρ², 1)`, low `max(2 · ρ² − 1, 0.75 · ρ²)`. Squaring first and scaling second is the rule's order; the reverse
gives a different figure (hand-built case 9).

## 5. FRTB-SA: the sensitivities-based method

### 5.1 Delta, per risk class

The method is section 4's for every class; what differs is the factor, the bucket, the risk weight and the correlation. Parameter tables are
in the market-risk pack's [`capital/frtb-sa-sbm.yaml`](../../config/packs/market-risk/capital/frtb-sa-sbm.yaml), each value with its paragraph.

| Class | Factors and bucket | Risk weight | Within a bucket (ρ) | Across buckets (γ) |
|---|---|---|---|---|
| **GIRR** (MAR21.39–50) | each currency a bucket; curve × vertex (0.25Y to 30Y), inflation, cross-currency basis | by vertex, 1.7 % to 1.1 %; inflation and basis 1.6 %; ÷ √2 for the specified currencies and the reporting currency | `max(exp(−θ·|T_k − T_l| / min(T_k, T_l)), 40 %)`, θ = 3 %; × 99.9 % between curves; inflation 40 %; basis 0 % | 50 % |
| **CSR non-securitisation** (MAR21.51–57) | 18 buckets by sector and credit quality; issuer × curve (bond, CDS) × vertex (0.5Y to 10Y) | by bucket, 0.5 % to 12 % | ρ_name (35 % between names) × ρ_tenor (65 % between tenors) × ρ_basis (99.9 % bond to CDS); in the index buckets 17 and 18, ρ_name 80 % (MAR21.55); bucket 16 (other sector) adds absolute values, no correlation (MAR21.56) | γ_rating (50 % between IG and HY, buckets 1 to 15 only) × γ_sector (MAR21.57's table; 0 % with bucket 16) |
| **Equity** (MAR21.71–80) | 13 buckets by size, economy and sector; spot and repo per name | spot 15 % to 70 % by bucket; repo 0.15 % to 0.70 % | 15 %, 25 %, 7.5 %, 12.5 % by bucket group; 80 % in the index buckets (MAR21.78); spot to repo of one name 99.9 %, of two names the spot ρ × 99.9 %; bucket 11 (other sector) adds absolute values, no correlation (MAR21.79) | 15 %; 0 % with bucket 11; 75 % between the index buckets; 45 % index to name |
| **Commodity** (MAR21.81–85) | 11 buckets by commodity group; commodity × location × vertex | 20 % to 80 % by bucket | ρ_commodity (by bucket, 15 % to 95 %) × ρ_tenor (99 %) × ρ_basis (99.9 % between locations) | 20 %; 0 % with bucket 11 |
| **FX** (MAR21.86–89) | each currency a bucket | 15 %; ÷ √2 for the specified pairs and their first-order crosses (MAR21.88) | one factor a bucket | 60 % |

**Delta charge of the class** `Δ_class(scenario)` is section 4 with these parameters in the scenario's correlations.

### 5.2 Vega

Vega factors are implied volatilities by underlying and option maturity (0.5, 1, 3, 5 and 10 years; for GIRR also the underlying's
residual maturity, MAR21.8). The risk weight is `min(55 % · sqrt(LH / 10), 100 %)` by the liquidity horizon of MAR21.92's Table 13:
100 % for GIRR (LH 60), CSR (120), commodity (120) and FX (40), 77.78 % for equity large-cap and index buckets 1 to 8, 12 and 13 (LH 20),
100 % for equity buckets 9 to 11 (LH 60). Within a bucket,

```
GIRR (MAR21.93)    ρ_kl = min( ρ_option-maturity · ρ_underlying-maturity , 1 )
others (MAR21.94)  ρ_kl = min( ρ_delta · ρ_option-maturity , 1 )
ρ_option-maturity = exp( −α · |T_k − T_l| / min(T_k, T_l) ),  α = 1 %   (the same form on the underlying maturities for GIRR)
```

with ρ_delta the delta correlation of the two underlyings, on the dimensions vega shares with delta (for CSR the name, for commodity the
commodity; MAR21.94's FAQ). Across buckets, the delta γ (MAR21.95); the other-sector buckets add absolute values as for delta.

### 5.3 Curvature

Curvature captures the loss a delta does not. **The curvature factors** (MAR21.8 to MAR21.14) are coarser than the delta factors: for
GIRR one per currency, every curve of the currency shifted together (no curvature for inflation and cross-currency basis); for CSR one
per issuer, the bond and CDS curves taken as one; for commodity one per commodity, the whole curve; for equity each spot (none for
repo); for FX each currency against the reporting currency. **The shock** is the curvature risk weight: for FX and equity a relative
shift equal to the delta risk weight (MAR21.98); for GIRR, CSR and commodity a parallel shift of every tenor by the highest delta risk
weight of the bucket (MAR21.99; for GIRR the 0.25-year weight).

From the two revaluations, an **up and a down term per factor** (MAR21.5(2)), summed over the instruments i:

```
CVR_k+ = − Σ_i ( V_i(x_k shocked up)   − V_i(x_k) − RW_k · s_ik )
CVR_k− = − Σ_i ( V_i(x_k shocked down) − V_i(x_k) + RW_k · s_ik )
```

In the delivered values, `V(x ± RW) − V(x)` are `CURVATURE_UP` and `CURVATURE_DOWN`, and `RW · s_ik` is the delta times the shock: for
GIRR, CSR and commodity the sum of the deltas over every tenor of the curve (Σ DV01 × `SHOCK_SIZE` in bp); for a spot,
`DELTA × 100 × SHOCK_SIZE`. A positive CVR is a loss beyond delta.

**Within a bucket** (MAR21.5(3)), each side is aggregated on its own and the larger is kept:

```
K_b+ = sqrt( max( 0, Σ_k max(CVR_k+, 0)² + Σ_k Σ_{l≠k} ρ_kl² · CVR_k+ · CVR_l+ · ψ(CVR_k+, CVR_l+) ) )
K_b− = the same with CVR−
K_b  = max( K_b+, K_b− )
ψ(x, y) = 0 when x < 0 and y < 0, else 1
```

K_b+ > K_b− selects the **upward** side for the bucket, K_b− > K_b+ the downward; when they are equal, the upward side is selected if
`Σ_k CVR_k+ > Σ_k CVR_k−`, otherwise the downward. The choice is made per bucket and per correlation scenario, so a bucket may take the
upward side in one scenario and the downward in another. The other-sector buckets (CSR 16, equity 11) use no correlation:
`K_b = max( Σ_k max(CVR_k+, 0), Σ_k max(CVR_k−, 0) )` (MAR21.56, MAR21.79).

**Across buckets** (MAR21.5(4)):

```
Curvature = sqrt( max( 0, Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc² · S_b · S_c · ψ(S_b, S_c) ) )
S_b = Σ_k CVR_k+ when the bucket selected the upward side, Σ_k CVR_k− otherwise
```

The floor at zero replaces the alternative specification: curvature never caps S_b at ±K_b (that cap is SIMM's, section 8). The
correlations are the delta ones squared, with only the dimensions curvature keeps (for CSR ρ_name alone, for commodity ρ_commodity alone;
MAR21.100), and the scenarios of section 4 are applied after squaring.

**FX curvature ÷ 1.5** (MAR21.98): the net curvature charges CVR+ and CVR− of FX options that do not reference the reporting (or base)
currency may be divided by 1.5; applying it to every FX instrument needs supervisory approval and curvature computed for every currency
including the reporting currency shocked against the others. A setting of the parameter file (section 16, decision 6).

**Curvature estimated from gamma.** Where a source sends `GAMMA` instead of the two revaluations (decision 2), the revaluations are
replaced by their second-order Taylor expansion in the shock h of the curvature factor:

```
V(x ± h) − V(x) ≈ ± Δ · h + ½ · Γ · h²            so      CVR_k+ ≈ CVR_k− ≈ − ½ · Γ_k · h_k²
h_k = RW_curvature · 10,000 bp for a curve, RW_curvature · 100 (per cent) for a spot; Γ in the delivered unit (per bp² or per (1 %)²)
Γ_k = Σ_t Σ_u Γ_tu  for a curve: the gamma of the parallel shift, every pair of tenors of every curve of the factor
```

It is an **approximation**, labelled an estimate on every screen and export: it drops the third and higher orders, which matter at
these shock sizes (a 170 bp parallel move, 15 % to 70 % on an equity) and for barriers and digitals; it makes the up and down terms
equal, so the upward/downward choice carries no information; and when the source delivers only the diagonal gammas per tenor (no
cross-tenor terms), `Σ_t Γ_tt` stands for the parallel gamma, a second approximation. MAR21.17 and its first FAQ allow an alternative
formulation only where the bank shows its supervisor the results are very close to the prescribed one; an estimate is therefore an
analysis figure until the supervisor accepts it for the filing (hand-built case 10).

### 5.4 The three scenarios and the SBM total

For each scenario s ∈ {low, medium, high}, the charges of every class and measure are added; the SBM capital is the **largest** of the
three (MAR21.6–7):

```
SBM(s) = Σ_classes ( Δ_class(s) + Vega_class(s) + Curvature_class(s) )
SBM    = max( SBM(low), SBM(medium), SBM(high) )         the binding scenario named on every screen
```

Each scenario repeats the whole aggregation (the curvature side choice included) with its own correlations; the three are compared only
after the classes are added (MAR21.7(1)), never class by class. High correlations raise the charge of a long book; low correlations
raise the charge of a hedged one (the hand-built case 2 in section 13 binds on the low scenario). For a desk computed as a standalone
portfolio, the three scenarios are compared at the desk (MAR21.7(2)(b)).

## 6. FRTB-SA: the default risk charge

Non-securitisations (MAR22), from per-obligor jump-to-default amounts. The inputs are the `default-risk` input of
[FRTB.md section 3.3](../contracts/FRTB.md#33-default-risk-the-drc-inputs-optional) (obligor, seniority, notional, market value, credit
quality), or, where the bank sends none, derived from the trade records and the issuer master. Parameters:
[`capital/frtb-sa-drc-rrao.yaml`](../../config/packs/market-risk/capital/frtb-sa-drc-rrao.yaml).

1. **Gross jump-to-default per position** (MAR22.9 to MAR22.14): `JTD_long = max( LGD · notional + P&L, 0 )`,
   `JTD_short = min( LGD · notional + P&L, 0 )`, the notional positive for a long and negative for a short credit exposure (long means
   a loss on default, whatever the instrument: a bought CDS is short, a sold put on a bond long; MAR22.10, MAR22.13), `P&L = market
   value − notional` (bond-equivalent for derivatives, MAR22.14's table), LGD 75 % senior, 100 % non-senior and equity, 25 % covered
   bonds, and no LGD where the price does not depend on the recovery (MAR22.12).
2. **Maturity scaling of the gross JTD** (MAR22.15 to MAR22.18): multiply by `min( max( M, 0.25 ), 1 )` years, M the maturity of the
   instrument (of the derivative, not its underlying); cash equity takes 0.25 or 1, chosen by the bank, nothing between.
3. **Net per obligor** (MAR22.19 to MAR22.21): the scaled longs and shorts of the same obligor offset when the short is of the same or
   lower seniority than the long; the result is a net long or a net short per obligor.
4. **Per bucket** (corporates, sovereigns, local governments and municipalities; MAR22.22), with the **hedge benefit ratio** HBR
   (MAR22.23), unweighted, and the bucket's charge (MAR22.25):

```
HBR   = Σ netJTD_long / ( Σ netJTD_long + Σ |netJTD_short| )
DRC_b = max( Σ_i RW_i · netJTD_long,i − HBR · Σ_i RW_i · |netJTD_short,i| , 0 )
```

with RW by credit quality (MAR22.24: AAA 0.5 %, AA 2 %, A 3 %, BBB 6 %, BB 15 %, B 30 %, CCC 50 %, unrated 15 %, defaulted 100 %);
sovereign exposures may take 0 % at national discretion (MAR22.7), a setting of the regime's parameter file.

5. **Total:** `DRC = Σ_b DRC_b`, no hedging across buckets (MAR22.26).

## 7. FRTB-SA: the residual risk add-on

`RRAO = Σ gross notional × 1.0 %` for instruments with an exotic underlying (MAR23.3: longevity, weather, natural disasters, future
realised volatility) `+ Σ gross notional × 0.1 %` for instruments bearing other residual risks (MAR23.4 and MAR23.5: gap risk such as
barriers, Asians and digitals; correlation risk such as baskets, best-of, spread, Bermudan and quanto options; behavioural risk) (MAR23.8).
Which product types bear which is configuration (the parameter file's `product-types`, mapped from `PRODUCT_TYPE`); the risks that by
themselves do not bring an instrument in (cheapest-to-deliver, smile, plain multi-underlying vanillas, dividend risk) are listed in
MAR23.6. Exclusions (MAR23.7), flagged on the trade: a back-to-back transaction and its exact third-party match are excluded entirely;
an instrument listed or eligible for central clearing is excluded from the other-residual-risk part only, and stays in when its
underlying is exotic.

**Total FRTB-SA = SBM + DRC + RRAO.**

## 8. ISDA SIMM

Initial margin per netting set, per regime and per the SIMM version the netting set's CSA names. Drishti implements SIMM's structure; the
**parameters are ISDA's and licensed**: a bank holding the licence loads each version it uses into its copy of
[`capital-parameters/simm.yaml`](capital-parameters/simm.yaml) (shipped with every number empty; the engine refuses a version with a
value it needs missing). The scope is the netting set's trades (from the netting-set and CSA records), the regime the CSA's (collect and
post computed separately).

The paragraph numbers below are those of ISDA's SIMM methodology (version 2.8+2512, effective 11 July 2026, at the time of writing); the
structure has been stable across versions, the parameters change with each calibration.

1. **Delta per risk class** (InterestRate, CreditQ, CreditNonQ, Equity, Commodity, FX; paragraphs 7 and 8): section 4 with SIMM's risk
   weights and correlations, and the **concentration factor**, one threshold per bucket (or currency group, or FX category) but applied
   at a different grain per class:

```
InterestRate   CR_b = max( 1, sqrt( |Σ_{k,i} s_k,i| / T_b ) )     one per currency: every vertex and sub-curve of the currency,
                                                                  inflation included, cross-currency basis excluded (and not scaled)
Credit         CR_k = max( 1, sqrt( |Σ_j s_j| / T_b ) )            one per issuer and seniority: all its tenors and payment
                                                                  currencies; base-correlation sensitivities take CR = 1
Equity, Commodity, FX
               CR_k = max( 1, sqrt( |s_k| / T_b ) )                 one per factor (the name, the commodity, the currency)
```

   with `T_b` the delta threshold of the factor's bucket in USD (per bp for rates and credit, per 1 % for the others), converted to the
   calculation currency. Within a bucket the cross terms carry `f_kl = min(CR_k, CR_l) / max(CR_k, CR_l)` (credit, equity, commodity,
   FX); across currencies for interest rates `g_bc = min(CR_b, CR_c) / max(CR_b, CR_c)`; the sub-curve correlation φ multiplies the
   tenor correlation within a currency. `S_b` is always capped at `±K_b`, and the residual bucket (credit and equity) is added outside
   the root (section 4, step 3). FX sensitivity to the calculation currency itself is left out.
2. **Vega** (paragraph 10): the vega risk of section 2.3 (σ the implied volatility for rates and credit; σ from the delta risk weight
   and the historical volatility ratio HVR for equity, FX and commodity), netted per factor, then `VR_k = VRW · Σ_i VR_ik · VCR`, with the
   vega concentration factor `VCR` at the same grains as delta (per currency, per issuer curve, per factor) against the vega thresholds
   `VT_b`. Within a bucket `f_kl` from the VCRs (1 for interest rates); across buckets `g_bc` for interest rates, 1 for the others;
   `S_b` capped; residual outside the root.
3. **Curvature** (paragraph 11), from the same vegas: `CVR_k = Σ_i Σ_expiries SF(t) · σ · ∂V/∂σ` with
   `SF(t) = 0.5 · min(1, 14 / t_days)` (12 months = 365 days, pro rata), netted across expiries only through these weights; no
   concentration factor; zero for equity bucket 12 (volatility indices). Within a bucket `K_b = sqrt( Σ CVR_k² + Σ_k Σ_{l≠k} ρ_kl² ·
   CVR_k · CVR_l )`; then, over the non-residual buckets,
   `θ = min( Σ CVR / Σ |CVR|, 0 )`, `λ = (Φ⁻¹(99.5 %)² − 1)(1 + θ) − θ`,
   `Curvature_nonres = max( Σ CVR + λ · sqrt( Σ_b K_b² + Σ_b Σ_{c≠b} γ_bc² · S_b · S_c ), 0 )` with `S_b` capped at `±K_b`;
   the residual bucket the same on its own (`max( Σ CVR_res + λ_res · K_res, 0 )`), the two added. For interest rates the curvature
   margin is multiplied by `HVR_IR⁻²`.
4. **Base correlation** (CreditQ, paragraph 13): `sqrt( Σ WS_k² + Σ_k Σ_{l≠k} ρ_kl · WS_k · WS_l )` over the index families, from
   `BASE_CORR` sensitivities, no concentration.
5. **Per risk class:** `IM_r = Delta_r + Vega_r + Curvature_r (+ BaseCorr_r)` (paragraph 5).
6. **Per product class** (RatesFX, Credit, Equity, Commodity; paragraph 6): each trade belongs to one product class and its risks in
   every risk class stay within it; `SIMM_p = sqrt( Σ_r IM_r² + Σ_r Σ_{s≠r} ψ_rs · IM_r · IM_s )`, ψ the 6 × 6 correlation between
   risk classes (section K).
7. **Total:** `SIMM = SIMM_RatesFX + SIMM_Credit + SIMM_Equity + SIMM_Commodity`, a plain sum: SIMM has **no correlation between
   product classes**. The CSA's add-ons (section L) come on top:
   `AddOnIM = fixed amount + Σ_p factor_p · notional_p + Σ_p (MS_p − 1) · SIMM_p`, each multiplier MS_p ≥ 1.

The figure replaces the stored `simm` command's value for the netting sets that have sensitivities; the netting sets without stay on
the stored figure, labelled as such, until the bank's sensitivities cover them.

## 9. Scopes, totals and contributions

**Any scope.** The engine accepts the VaR scopes (a hierarchy node, a portfolio, a basket, a set of positions); SIMM also a netting set.
Within a scope, positions' sensitivities to the same factor are summed first (they are additive); everything after the weighting is not.

**Non-additive across scopes.** The square roots and the maximum make a desk's charges add to more than the bank's. The MR cube shows each
cell's own charge (re-computed from the cell's netted sensitivities) and the diversification row `Σ cells − total`, as it does for VaR.

**Contributions (Euler).** The SBM charge is homogeneous of degree one in the weighted sensitivities within a scenario, so a position's
contribution is `Σ_k WS_k(position) · ∂SBM/∂WS_k`, adding up exactly to the charge of the binding scenario; the gradient is computed once
per scope. DRC contributions follow each obligor's share of its bucket's charge; RRAO is additive.

**Cluster.** A scope's netted sensitivities are a sparse sum: each owner sums its shards' positions per factor and the coordinator adds
the partial sums in shard order, then applies the formula once (the same equality rule as the VaR sums, [VAR_SHARDING.md](VAR_SHARDING.md)).

**Performance.** About 1,000 factors per class in a large bank's scope, so a class's within-bucket double sum is small; the cost is the
netting (one pass over the scope's elements, parallel by shard) and the three scenarios share it. Target: the bank's SBM in three scenarios
in about 2 s on one warm server at 2.3 million positions (an estimate from the MR cube's response-time table, to be measured).

## 10. Where each number comes from

| Figure | Inputs | Computed by | Shown with |
|---|---|---|---|
| A sensitivity in a ladder or heat map | `position-sensitivity` elements of the cell's positions, FX of the date | sum per factor or bucket | the batch, the source system, the proxied share |
| `s_k`, `WS_k` | the elements; the dictionary's bucket; the parameter file's RW | section 2.3, section 4 step 1 | the parameter file, version and paragraph |
| `K_b`, `S_b`, Δ, vega, curvature | the WS of the scope; correlations of the scenario | sections 4 and 5 | the scenario, whether the alternative specification applied, the curvature side (up or down) chosen per bucket |
| SBM | the class charges of each scenario | section 5.4 | the three scenarios, the binding one marked |
| DRC | `default-risk` input or derived; issuer master | section 6 | the obligors netted, HBR of each bucket |
| RRAO | trades' gross notional and product types; the RRAO mapping | section 7 | the instruments in each class |
| SIMM | the netting set's sensitivities; the CSA's version and regime; the licensed parameters | section 8 | the version, the concentration factors, the add-ons |

Every answer carries a provenance record (data versions of each input table, the batch, the parameter files and their checksum, refusals
and proxies counted), as the VaR answers do.

## 11. API, command line and screens

Built for sensitivities and FRTB-SA (the commands `FRSA <scope>`, `PLAY FRTB <scope>`, `PLAY FRTB RUN <id>`, and `drishti.py sens`,
`frtb`, `play frtb`; [FRTB_SA_GUIDE.md section 9](../guides/FRTB_SA_GUIDE.md#9-api-command-line-and-settings)); SIMM and the official
captures are design.

| What | API | Command (terminal) | CLI |
|---|---|---|---|
| Sensitivities of a scope, by factor, bucket or grid | `GET /api/v1/sensitivities?scope=…&by=RISK_FACTOR,TENOR&grid=…` | `SENS <scope>` | `drishti.py sens show` |
| The factor dictionary and the mapping refusals | `GET /api/v1/risk-factors?q=…` | `RFAC <factor or name>` | `drishti.py sens factors` |
| FRTB-SA of a scope (SBM by class and scenario, DRC, RRAO) | `GET /api/v1/frtb/sa?scope=…` | `FRSA <scope>` | `drishti.py frtb sa` |
| Its steps (buckets, WS, K, S, scenario) | `GET /api/v1/frtb/sa/explain?scope=…&class=…` | in `FRSA` | `drishti.py frtb explain` |
| Contributions | `GET /api/v1/frtb/sa/contributions?scope=…&by=DESK` | in `FRSA` | `drishti.py frtb contributions` |
| SIMM of a netting set or scope (planned) | `GET /api/v1/simm?nettingSet=…&regime=…` | `SIMM <netting set>` (the computed figure) | `drishti.py simm show` (planned) |
| The playgrounds | `/api/v1/frtb/playground`, `/api/v1/simm/playground` (and `.xlsx`, `/estimate`, `/template`) | `PLAY FRTB <scope>`, `PLAY FRTB RUN <id>`; `PLAY SIMM <netting set>` (planned) | `drishti.py play frtb`; `drishti.py play simm` (planned) |
| Official captures (planned) | the official run captures FRTB-SA per filer and date, as VaR | — | `drishti.py frtb capture` (planned) |

**Computed kinds:** `frtb-sa-result` (the result of a scope and date) and `simm-result` (per netting set and regime), each with a contract
of class `computed`; the stored `frtb-sensitivity` and `simm` kinds retire when these are built.

**Screens:** the FRTB-SA capital screen, the SIMM screen and the factor dictionary screen (mock-ups in section 15); the MR cube's SBM
measure and its sensitivity ladders and heat maps (decided mock-ups, [MR_CUBE_LENS.md section 6.7](../lenses/MR_CUBE_LENS.md#67-mock-ups-the-screens-to-build)).

## 12. The playgrounds

Every metric ships with its playground, in the same release ([VAR_PLAYGROUND.md section 13](VAR_PLAYGROUND.md#13-the-playground-family-and-the-rule)).
The screen is the VaR Playground's frame as chosen (the grid with the tall explanation panel beside it, the formula bar, the export to
Excel from a version-controlled template with real formulas and check cells); each playground adds its model, its workbook generator and
its template.

### 12.1 FRTB-SA playground (`PLAY FRTB`)

Scope: one desk or a set of positions, up to a configurable number of factors (default 2,000); beyond it the export states its size and
offers a class at a time.

| Tab | What it shows | Typed or formula |
|---|---|---|
| Main | the scope, the date, the parameter file and its version; the SBM per class × measure × scenario, the binding scenario, DRC, RRAO and the total; each beside the engine's figure with a check | formulas; the engine's figures typed |
| Config | the parameters actually used: risk weights per bucket and vertex, ρ and γ, the scenario formulas, with their MAR paragraphs | typed (from the parameter file) |
| Data | the scope's sensitivity elements as delivered (position, factor, measure, value, currency) and the FX | values only |
| WS | per factor: the netted sensitivity (`SUMIFS` over Data), the conversion of section 2.3, RW (`INDEX/MATCH` into Config), WS | formulas only |
| Buckets | per class and bucket: the ρ matrix of its factors (formulas on the tenors and names), K_b with `SUMPRODUCT` and `MMULT`, S_b, the alternative specification when it applies | formulas only |
| Classes | per class: the γ matrix, Δ, vega, curvature per scenario; the scenario columns built with `MIN` and `MAX` on ρ | formulas only |
| Curvature | per curvature factor: up, down, the delta part, CVR+ and CVR−; per bucket K+, K−, the side chosen in each scenario, ψ | formulas only |
| DRC | obligors, JTD long and short, maturity scaling, netting, HBR and the bucket charges | formulas on the typed positions |
| RRAO | the instruments, their class, notional × weight | formulas |
| Explain | the method in plain words with the cells of each step | text with references |
| Provenance | data versions, batches, refusals, the parameter file's checksum, who exported it | values |

The explanation panel walks the selected cell: for a K_b cell it lists the WS of the bucket, the ρ used in the scenario, the sum of
squares and cross terms with their values, and the result.

### 12.1a Critique and reproduce: the method choices as switches

**Requested by the product owner (2026-11-02):** the playground must show that the engine can be **critiqued** (every choice the method
makes is visible, its alternative computable, the difference explained) and that its numbers can be **reproduced at will**.

**Every method choice is a named switch**, on the playground's *Choices* panel and on the workbook's Config tab, with the decided default
(section 16), the alternative, the rule paragraph and one sentence on why the default was chosen:

| Switch | Default (decided) | Alternative(s) | Rule |
|---|---|---|---|
| `curvature-source` | revaluation (up and down PVs) where delivered | gamma estimate (`−½·Γ·h²`); **both side by side** | MAR21.5, MAR21.17 |
| `girr-curvature-shock` | 1.7% | 1.7%/√2 where the √2 reduction is used | MAR21.99 |
| `fx-curvature-divisor` | ÷ 1.5 for FX options not referencing the reporting currency | off; every FX instrument (with approval) | MAR21.98 |
| `girr-correlation` | the published table (0.1%) | the exact formula | MAR21.46 |
| `scenario` | the binding one of the three | high, medium, low, each shown | MAR21.6 |
| `simm-parameters` | the bank's licensed file, by version | another version loaded beside it | SIMM |
| `overrides` | none | any risk weight, correlation or shock typed by a validator | (not a regulatory figure) |

- **Compare.** Any switch can be flipped and the playground shows **both runs side by side** with the difference per risk class, bucket
  and factor, and the **step where it arises** (the WS, K_b, the scenario, the across-bucket sum), in words as the VaR change is explained:
  *"Using the exact correlation formula instead of the published table raises GIRR delta by 1,204 (0.4%), all of it in USD, bucket 1,
  between the 5-year and 10-year vertices."* Overrides are drawn in a different colour and every figure they touch carries *not the
  regulatory figure*.
- **The same in Excel.** The workbook's Config tab holds each switch as a cell with a drop-down; the formula tabs read it (`IF` and
  `CHOOSE` on the switch), so a validator who flips a switch in Excel sees the alternative recompute **live in the workbook**, without
  Drishti. The Main tab shows the engine's figure for the default and for every alternative the export was made with, each with its check
  cell.
- **Reproduce at will.** Every playground run is a **recipe**: the scope and date, the input versions (each Delta or Iceberg table's
  version and the batch ids), the parameter file and its hash, every switch and override, and the engine version. A recipe is saved with
  one click (and on every export: the workbook's Provenance tab), has an id, and `PLAY FRTB RUN <id>` (or the recipe's link) computes it
  again **to the cent**, months later, even after new data arrived, because it reads the recorded versions. `drishti.py play frtb
  --recipe <id>` does the same from the command line.
- **The proof, in tests:** the workbook with the default switches equals the engine; the workbook with each switch flipped equals the
  engine run with that switch; a saved recipe replayed gives byte-equal figures; an override never reaches an official record.

The SIMM playground has the same Choices panel for its own choices (the parameter version, the concentration thresholds, the HVR), and so
will every later playground: critique and reproduce are part of the playground frame, not of one method.

### 12.2 SIMM playground (`PLAY SIMM`)

One netting set (or up to a configurable number of them side by side). Tabs: Main (SIMM per product class and total, against the engine),
Config (the licensed parameters of the version: written into the bank's own export only, never into a shared template), Data (the
sensitivities), WS (with the concentration factors), Buckets, Classes (ψ across risk classes), Curvature (SF(t), θ, λ), Add-ons, Explain,
Provenance. The template ships with formulas and empty parameter cells; the export fills them from the bank's licensed file.

### 12.3 Sensitivities

Sensitivities are delivered, not computed, so they have no playground of their own; their sums appear on the WS tabs above and in the MR
cube's drill-through to positions.

## 13. The tests that prove it

### 13.1 Hand-built cases

Each case is small enough to check with a calculator; each becomes an engine unit test, a playground check-cell test (the workbook
recalculated by LibreOffice equals the engine to the cent) and a row of the drill.

**Case 1: GIRR delta, one bucket, the three scenarios.** USD (a specified currency), DV01 5Y −1,000 and 10Y −500.
`s = −10,000,000` and `−5,000,000`; `RW = 1.1 % / √2 = 0.777817 %`; `WS = −77,781.75` and `−38,890.87`;
By default (decision 10) ρ is MAR21.46's published table, **0.970**; the exact formula `max(exp(−0.03 · 5 / 5), 0.40) = 0.970446` is
the `girr-correlation` switch.

| Scenario | ρ (table) | K_USD (table, the default) | ρ (exact) | K_USD (exact) |
|---|---|---|---|---|
| low | max(2 · 0.970 − 1, 0.75 · 0.970) = 0.940 | 115,106.47 | 0.940891 | 115,129.89 |
| medium | 0.970 | 115,892.19 | 0.970446 | 115,903.82 |
| high | min(1.25 · 0.970, 1) = 1 | 116,672.62 | 1 | 116,672.62 (= \|WS₁\| + \|WS₂\|) |

**Case 2: two buckets, a hedge, the low scenario binds.** Case 1 plus EUR DV01 2Y +800: `WS = 1.3 % / √2 · 8,000,000 = 73,539.11`,
`K_EUR = 73,539.11`, `S_USD = −116,672.62`, γ = 50 % (low 37.5 %, high 62.5 %).

| Scenario | γ | Δ_GIRR (table, the default) | Δ_GIRR (exact) |
|---|---|---|---|
| low | 0.375 | 110,555.42 (binding) | 110,579.79 (binding) |
| medium | 0.50 | 101,286.72 | 101,300.03 |
| high | 0.625 | 91,079.64 | 91,079.64 |

**Case 3: curvature.** One equity index (bucket 12, RW 15 %), `DELTA` 10,000 per 1 %, so `RW · s = 150,000`; `CURVATURE_UP = 120,000`,
`CURVATURE_DOWN = −180,000`. `CVR+ = −(120,000 − 150,000) = 30,000`, `CVR− = −(−180,000 + 150,000) = 30,000`; one factor, so
`K+ = K− = 30,000` (the tie goes to the downward side, as `Σ CVR+` is not greater than `Σ CVR−`), and the bucket and the class charge
are 30,000 in every scenario.

**Case 4: DRC netting and WtS.** A long senior BBB bond, notional 10,000,000, market value 9,800,000, 2 years: JTD 7,300,000 (×1).
A bought CDS on the same obligor, notional −4,000,000, P&L +50,000, 6 months: JTD `(0.75 · −4,000,000 + 50,000) · 0.5 = −1,475,000`.
Net long 5,825,000. A second obligor (BB) net short −2,000,000. `WtS = 5,825,000 / 7,825,000 = 0.744409`;
`DRC = max(6 % · 5,825,000 − 0.744409 · 15 % · 2,000,000, 0) = 126,177.32`.

**Case 5: RRAO.** An FX barrier option, gross notional 50,000,000 (other residual risk, 0.1 %): 50,000; a weather derivative, 10,000,000
(exotic, 1.0 %): 100,000. RRAO 150,000.

**Case 6: SIMM aggregation with a toy parameter set** (not ISDA's: the test proves the formulas, never the licensed numbers). One currency,
WS = 50 · (−1,000) and 60 · 400 per bp, ρ = 0.8, CR = 1: `K = sqrt(50,000² + 24,000² − 2 · 0.8 · 50,000 · 24,000) = 34,000`. Two risk
classes of one product class, IM 50,000 and 30,000, ψ = 0.3: `SIMM_p = 65,574.39`.

**Case 7: the alternative specification.** Two buckets whose nets oppose more than their charges (the radicand negative): the engine
switches to the capped S_b, and the test asserts both the switch and the figure.

**Case 8: the regulatory examples.** Where the supervisor publishes worked examples (the Basel QIS templates, a regulator's test pack),
each is added as a case with its source, the inputs typed from the publication.

**Case 9: curvature, the two sides and the order of squaring.** Two names in equity bucket 5 (ρ 25 %, so the curvature ρ² = 6.25 %).
Name A: `CVR+ = 40,000`, `CVR− = −10,000`; name B: `CVR+ = −25,000`, `CVR− = 30,000`. The scenarios apply to ρ² (low 4.6875 %,
high 7.8125 %). `K+ = sqrt(40,000² + 2 · ρ² · 40,000 · (−25,000))` (B's negative CVR+ adds no square, ψ = 1 as only one is negative),
`K− = sqrt(30,000² + 2 · ρ² · (−10,000) · 30,000)`:

| Scenario | ρ² in the scenario | K+ | K− | K_b (upward side, S_b = 15,000) |
|---|---|---|---|---|
| low | 0.046875 | 38,810.44 | 29,527.53 | 38,810.44 (the largest) |
| medium | 0.0625 | 38,405.73 | 29,368.35 | 38,405.73 |
| high | 0.078125 | 37,996.71 | 29,208.30 | 37,996.71 |

Scaling ρ before squaring would give 39,111.22 (low) and 37,479.16 (high); the test asserts the figures above.

**Case 10: curvature estimated from gamma.** The index of case 3 sent as `DELTA` 10,000 per 1 % and `GAMMA` −200 per (1 %)², shock
h = 15 (per cent). Estimated `V(x ± h) − V(x) = ±150,000 − ½ · 200 · 225`, i.e. 127,500 and −172,500, so `CVR+ = CVR− = 22,500`
(= −½ · Γ · h²), labelled an estimate. Against the revalued 30,000 of case 3 it shows what the expansion leaves out.

**Case 11: SIMM concentration per issuer** (toy parameters again). One credit bucket, threshold `T_b = 1,000` per bp, RW 100, ρ = 0.4
between the issuers. Issuer X nets CS01 −4,000 over its tenors, so `CR_X = sqrt(4) = 2`; issuer Y +1,000, `CR_Y = 1`; `f_XY = 0.5`.
`WS_X = 100 · (−4,000) · 2 = −800,000`, `WS_Y = 100,000`, `K = sqrt(800,000² + 100,000² − 2 · 0.4 · 0.5 · 800,000 · 100,000) =
786,129.76`. A bucket-level factor (|−3,000| against 1,000 for both) would give a different figure; the test asserts this one.

### 13.2 Engine and data tests

- **Parameter files:** every value present and in range, every bucket's correlations a valid matrix (symmetric, unit diagonal, positive
  semi-definite in each scenario), the file's paragraph references present; a missing SIMM parameter refuses the version.
- **Grids:** every dictionary factor in exactly one bucket of each grid; a source name in two rows refuses the dictionary.
- **Loader:** each refusal class of section 2.4 with its code and count; a proxied record counted; intraday replacing a record whole.
- **Scopes:** a desk's charge equals the charge of its positions as a basket; the bank's equals the re-aggregated figure, not the sum of
  desks; contributions add up to the charge to the cent.
- **Cluster equality:** the bank's SBM on a sharded date equals one server's, bit for bit (the netted sums added in shard order).
- **Demo data:** `tools/packgen/sensitivity/test_sensitivity_data.py` (built with the data): names on the standard, every element's factor
  in the dictionary, one record per derived position, the hand-built swap's ladder and its one-sigma P&L equal to the VaR batch's, the
  committed files equal to the generator's.
- **Playgrounds:** the workbook recalculated headless by LibreOffice: every check cell passes; the formula scanner finds no function
  outside the common set.

## 14. Parameters as configuration

| File (draft here; the market-risk pack's configuration at build) | Holds | Source |
|---|---|---|
| [`capital/frtb-sa-sbm.yaml`](../../config/packs/market-risk/capital/frtb-sa-sbm.yaml) | the SBM risk weights, correlations, scenario formulas and curvature rules per class | Basel MAR21 |
| [`capital/frtb-sa-drc-rrao.yaml`](../../config/packs/market-risk/capital/frtb-sa-drc-rrao.yaml) | LGDs, maturity scaling, DRC risk weights, RRAO weights and the product-type mapping | Basel MAR22, MAR23 |
| [`capital-parameters/simm.yaml`](capital-parameters/simm.yaml) | the SIMM structure with every licensed number empty | ISDA SIMM methodology (licensed, per version) |

**Rules.** One file per regime (Basel, a jurisdiction's variant) and per SIMM version; each names its regime or version, its effective
date and the paragraph of each value. A filer is configured with its regime; a netting set's CSA names its SIMM version. The engine
loads every file at start, checks it (section 13.2) and keeps a checksum in every answer's provenance. A changed file is a new version,
never an edit of the one in force: an official capture always names the parameter version it used.

## 15. Mock-ups: the screens to build

Decided already: the MR cube's landing A with tabs B and C ([MR_CUBE_LENS.md section 6.7](../lenses/MR_CUBE_LENS.md#67-mock-ups-the-screens-to-build));
the playgrounds use the VaR Playground's chosen frame ([VAR_PLAYGROUND.md section 16](VAR_PLAYGROUND.md#16-mock-ups-the-screens-to-build)).
Chosen (product owner, 2026-11-01): **FRTB-SA = B** (the capital stack) with A's three scenarios as a second tab; **SIMM = A** (the
netting-set list) with C (margin against the counterparty) as a second tab; **risk factors = A** (the dictionary) with B (the load's
quality) as a second tab. The options, three each, self-contained HTML with the dark theme by default (`#light` for the light):

| Screen | A | B | C |
|---|---|---|---|
| FRTB-SA capital | [scenarios first](../lenses/mockups/frtb-sa/A.html): the three scenarios side by side by risk class, the binding one marked, then DRC and RRAO | [the capital stack](../lenses/mockups/frtb-sa/B.html): one waterfall from delta to the total with the class × measure grid below and the bucket drill on the right | [desk by class pivot](../lenses/mockups/frtb-sa/C.html): the MR cube's pivot with the SBM measure, the explain panel walking a cell's buckets |
| SIMM | [netting-set list](../lenses/mockups/simm/A.html): every netting set with IM collect and post, the selected one's breakdown beside | [one netting set](../lenses/mockups/simm/B.html): the product-class tree with the risk-class bars and the concentration factors | [margin against the counterparty](../lenses/mockups/simm/C.html): Drishti's SIMM beside the counterparty's call, the differences by risk class to explain a dispute |
| Factor dictionary and sensitivity quality | [the dictionary](../lenses/mockups/risk-factors/A.html): a searchable table of factors with their FRTB and SIMM mapping and source names | [the load's quality](../lenses/mockups/risk-factors/B.html): coverage by desk, refusals by reason, unmapped source names to add | [the factor's page](../lenses/mockups/risk-factors/C.html): one factor with its mapping, its exposure by desk, its move against VaR |

The numbers on the mock-ups come from the demo sensitivities through a sketch of the engine (synthetic, illustrative; the CSR sector
correlation and the DRC inputs are placeholders until the engine is built).

## 16. Decisions

Decided by the product owner on 2026-11-01:

1. **The screens:** as section 15 (FRTB-SA B with A's scenarios as a tab; SIMM A with C as a tab; risk factors A with B as a tab).
2. **Curvature from gamma:** accepted from a source that cannot revalue, computed from `GAMMA` by the second-order expansion of
   section 5.3 and labelled as an estimate on every screen and export that shows it.
3. **Rates vega convention:** lognormal volatility points by default; a source sending normal volatility in basis points declares it
   and is converted per source on load.
4. **The regime first:** Basel MAR21–23 as the reference, then OSFI CAR chapter 9, as for the regulatory VaR reports.
5. **SIMM indices:** credit indices are one qualifier now; look-through to the constituents later.
6. **The FX curvature division by 1.5 (MAR21.98):** on by default for the FX options that do not reference the reporting currency, a
   setting to turn it off; extending it to every FX instrument is a second setting, off by default, for a bank with the supervisor's
   approval.

Decided by the product owner on 2026-11-02, after the check against the rules ([section 18](#18-checked-against-the-rules)):

7. **Curvature from gamma** (refining decision 2): an estimate is an **analysis figure** until the supervisor accepts it for the filing
   (MAR21.17 and its FAQ); every screen and export that shows one says so, and the playground shows it beside the revalued figure
   whenever both exist ([section 12.1a](#121a-critique-and-reproduce-the-method-choices-as-switches)).
8. **The GIRR curvature shock where the bank uses the √2 reduction:** the undivided **1.7%** until a regulator says otherwise; 1.7%/√2 is a
   switch.
9. **FX ÷ 1.5:** as decision 6 (only where the rules allow it by default; every FX instrument only with the supervisor's approval).
10. **GIRR correlations:** the **published table** of MAR21.46 (rounded to 0.1%) by default, so the bank's figures match what its
    regulator expects; the exact formula behind it is a switch.
11. **SIMM numbers:** shipped **empty**; the bank loads its licensed ISDA file, whose version is recorded on every figure and export.

## 17. The playgrounds' explanations

The words of each playground's Explain tab are written ahead of the engines, in the VaR Playground's format (a line `## <title>`, then
its text; `#` lines are comments), so the same `PlaygroundTemplate.explain()` reads them:

| File | Covers |
|---|---|
| [`playground/frtb-sa/template/explain.txt`](../../drishti-server/src/main/resources/playground/frtb-sa/template/explain.txt) | in plain words: what FRTB-SA answers, why a bank must hold it, the tabs, signs and units, the regime and its parameters; 18 steps from the delivered sensitivity to the total and the contributions, each with a worked number from the hand-built cases of section 13.1 (cases 1 to 5 together, with the default published table: GIRR delta 110,555.42, the index's own delta charge 150,000 and its curvature
30,000 give SBM 290,555.42 on the low scenario and a total of 566,732.73; without the index's delta 140,555.42 and 416,732.73; with the
exact formula 140,579.79 and 416,757.11); every term of Main, Config, Data, WS, Buckets, Classes, Curvature, DRC, RRAO and Provenance with its MAR paragraph |
| [`playground/simm/template/explain.txt`](../../drishti-server/src/main/resources/playground/simm/template/explain.txt) | in plain words: what SIMM answers, why initial margin is exchanged, the tabs, signs and units, the licensed parameters; 16 steps from the netting set's sensitivities to collect, post and the counterparty's call, each worked with the toy parameter set of case 6 (no licensed number appears); every term of Main, Config, Data, WS, Buckets, Classes, Curvature, Add-ons and Provenance |

The decisions of section 16 are in the words: curvature from gamma is labelled an estimate, rates volatilities are lognormal unless a
source declares normal, the regime is Basel MAR21 to MAR23 then OSFI CAR chapter 9, a credit index is one SIMM qualifier, and the FX
curvature division by 1.5 is on by default. The cell references (`{Name}`, `{Name:v}`) are added when each generator names its Main
cells.

## 18. Checked against the rules

Sections 4 to 8, 13.1, 14 and 16 were checked paragraph by paragraph against the Basel Framework's MAR21, MAR22 and MAR23 (version
effective 1 January 2023, [bis.org](https://www.bis.org/basel_framework/chapter/MAR/21.htm)) and ISDA's SIMM methodology version
2.8+2512 ([isda.org](https://www.isda.org/2026/06/12/isda-publishes-isda-simm-methodology-version-2-8-2512/)). Every hand-built case
of section 13.1 was recomputed.

| Point | Rule | Result |
|---|---|---|
| Delta aggregation, the floor in K_b, the alternative specification | MAR21.4 | as designed; paragraph references corrected (WS is MAR21.4(3)) |
| Sensitivity definitions | MAR21.19 to MAR21.25 | as designed; references corrected, equity repo per bp stated |
| Curvature: one CVR+ and one CVR− per factor, K_b = max(K_b+, K_b−), the tie rule, the side chosen per bucket and per scenario | MAR21.5(2), (3) | changed: a single `CVR = −min(…)` replaced by the two sides |
| Curvature across buckets: ψ, S_b from the chosen side, `sqrt(max(0, …))` with no cap on S_b | MAR21.5(4) | changed: the floor at zero replaces the ±K_b cap |
| Curvature factors and shocks: per currency for GIRR (no inflation or basis), per issuer for CSR, per commodity; relative shift for FX and equity, highest bucket weight in parallel for GIRR, CSR and commodity | MAR21.8 to MAR21.14, MAR21.98, MAR21.99 | changed: factors stated; commodity added to the parallel shift; references corrected |
| Curvature correlations squared, with fewer dimensions for CSR and commodity, scenarios applied after squaring | MAR21.100, MAR21.101 | changed: the order was the reverse; case 9 added |
| Other-sector buckets (CSR 16, equity 11): absolute sums for delta and vega, the max-of-sides sum for curvature | MAR21.56, MAR21.79 | added |
| Curvature from gamma | MAR21.5, MAR21.17 and its first FAQ | changed: the second-order form `CVR± ≈ −½ · Γ · h²` with Γ the parallel-shift gamma, stated as an approximation; case 10 added |
| FX curvature ÷ 1.5 | MAR21.98 | changed: reference (was MAR21.99) and scope (options not referencing the reporting currency) |
| Three correlation scenarios and the SBM as the largest scenario total, summed across classes first | MAR21.6, MAR21.7 | as designed; desk-level comparison added |
| Delta risk weights and correlations, every class | MAR21.39 to MAR21.89 | as designed; added the CSR index buckets' ρ_name 80 %, γ_rating only within buckets 1 to 15, the spot-repo ρ between names, FX first-order crosses |
| Vega risk weights by liquidity horizon; vega correlations | MAR21.92 to MAR21.95 | as designed; equity split stated (77.78 % for buckets 1 to 8, 12, 13; 100 % for 9 to 11), GIRR and non-GIRR forms separated |
| DRC: gross JTD, LGD, maturity scaling, netting by seniority, buckets, hedge benefit ratio, risk weights, sum across buckets | MAR22.9 to MAR22.26 | as designed; paragraph references corrected; the ratio named HBR as in MAR22.23 |
| RRAO weights 1.0 % and 0.1 %; scope and exclusions | MAR23.3 to MAR23.8 | weights as designed; changed: listed or centrally clearable instruments are excluded only from the other-residual part, back-to-back trades entirely |
| SIMM concentration: per currency (rates), per issuer and seniority (credit), per factor (equity, commodity, FX), thresholds by bucket | SIMM paragraphs 7(b), 8(b), 10(d), section J | changed: the factor was applied per bucket; f_kl and g_bc added; case 11 added |
| SIMM S_b always capped at ±K_b; residual buckets outside the root | SIMM paragraphs 7(d), 8(d), 10(f), 11(d) | added |
| SIMM vega and curvature: σ from the risk weight and HVR for equity, FX, commodity; SF(t); θ, λ; HVR_IR⁻² on rates curvature | SIMM paragraphs 10, 11 | changed |
| SIMM across risk classes and product classes | SIMM paragraph 6, section K | as designed for ψ within a product class; corrected the parameter file, which carried a correlation across product classes: SIMM adds the four product classes |
| SIMM add-ons | SIMM section L | stated: fixed amount, notional factors, product-class multipliers ≥ 1 |
| Hand-built cases 1 to 6 | the formulas above | recomputed: every figure as written |
