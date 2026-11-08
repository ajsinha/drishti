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
# FRTB-SA guide: position sensitivities and the standardised approach to market-risk capital

Drishti computes the FRTB standardised approach (the Basel Framework's MAR21 to MAR23) the way it computes VaR: **from trade-grain inputs,
when asked, for any scope, never stored as a figure.** The input is one table of position sensitivities; a factor dictionary says what
each factor is and where it falls in the regulator's grid; the parameters are configuration files that name their paragraphs. Every figure
opens in a playground that shows the same calculation as spreadsheet formulas, with every method choice a switch you can flip, compare
and reproduce. The tutorial is [tutorial 30](../tutorials/30-frtb-sa-from-the-cases-to-a-desk.md); the data contract, every field of
every record and the worked records are [FRTB.md](../contracts/FRTB.md).

## Contents

- [1. What FRTB-SA answers](#1-what-frtb-sa-answers)
- [2. The figures at a glance](#2-the-figures-at-a-glance)
- [3. The inputs and their data contract](#3-the-inputs-and-their-data-contract)
- [4. The sensitivities-based method](#4-the-sensitivities-based-method)
- [5. The default risk charge](#5-the-default-risk-charge)
- [6. The residual risk add-on](#6-the-residual-risk-add-on)
- [7. Scopes and the capital screen (FRSA)](#7-scopes-and-the-capital-screen-frsa)
- [8. The FRTB-SA Playground (PLAY FRTB)](#8-the-frtb-sa-playground-play-frtb)
- [9. API, command line and settings](#9-api-command-line-and-settings)
- [10. The hand-built cases](#10-the-hand-built-cases)
- [11. Refusals and error codes](#11-refusals-and-error-codes)
- [12. Parameters as configuration](#12-parameters-as-configuration)
- [13. FRTB-SA data: every dataset the engine reads](#13-frtb-sa-data-every-dataset-the-engine-reads)
- [14. Where each number comes from](#14-where-each-number-comes-from)
- [15. Decisions and the screens chosen](#15-decisions-and-the-screens-chosen)
- [16. Tests and the rules they were checked against](#16-tests-and-the-rules-they-were-checked-against)

## 1. What FRTB-SA answers

*How much capital must the bank hold for the market risk of this trading book, by the regulator's standard formula?* FRTB-SA starts from
the book's sensitivities (how much each position gains or loses when one market factor moves a small standard step), multiplies each by a
risk weight (how far the factor could move in a stress), and adds the weighted figures up with the regulator's correlations so hedges
offset only as far as the rules allow. On top come the default risk charge (an issuer defaulting outright) and the residual risk add-on
(risks no sensitivity captures). **Total FRTB-SA = SBM + DRC + RRAO.**

Every bank that trades computes it: it is the capital of desks without an approved internal model, the fallback when a model loses its
approval, and the reference supervisors compare models with. The regime is Basel MAR21 to MAR23 first; OSFI CAR chapter 9 follows as its
own parameter file.

## 2. The figures at a glance

| Figure | Meaning | Rule |
|---|---|---|
| `s_k` | the regulatory sensitivity of factor k, netted over the scope's positions | MAR21.19 to MAR21.25 |
| `WS_k` | the weighted sensitivity, `RW_k × s_k` | MAR21.4(3) |
| `K_b` | the bucket's charge, `√max(0, Σ_k Σ_l ρ_kl WS_k WS_l)` | MAR21.4(4) |
| `S_b` | the bucket's net, `Σ WS_k` (capped at ±K_b under the alternative specification) | MAR21.4(5) |
| Δ, vega, curvature | a class's charge across buckets, `√(Σ K_b² + Σ γ_bc S_b S_c)` | MAR21.4(5), MAR21.5 |
| SBM(s) | the classes added in scenario s ∈ {low, medium, high} | MAR21.6, MAR21.7 |
| SBM | the largest of the three, the **binding** scenario named on every screen | MAR21.7 |
| DRC | the default risk charge for non-securitisations | MAR22 |
| RRAO | the residual risk add-on | MAR23 |

All money is in the reporting currency (USD unless asked); every sensitivity is converted at the date's FX before anything is added.

## 3. The inputs and their data contract

The full data contract (every field, its unit, the checks, the record layouts and the samples) is [FRTB.md](../contracts/FRTB.md); the
YAML contracts are in `config/packs/banking/market-risk/contracts/`. In short:

| Kind (command) | What | Grain | Required |
|---|---|---|---|
| `position-sensitivity` (SENS) | each position's sensitivities: DV01, CS01, IE01 per bp; DELTA per 1% (equity repo per bp); VEGA per vol point with `IMPLIED_VOL`; CURVATURE_UP and CURVATURE_DOWN with `SHOCK_SIZE`; or GAMMA for a source that cannot revalue | one record per position (or trade), date and batch, a sparse `SENSITIVITIES[]` list | yes |
| `risk-factor` (RFAC) | the factor dictionary: the standard name, class, type, qualifier, curve, tenor, FRTB and SIMM buckets, seniority, liquidity horizon, the source systems' names | one row per factor and date | yes |
| `default-risk` (DRCI, planned) | obligor, DRC bucket, seniority, credit quality, bond-equivalent notional and market value, maturity, cash-equity maturity, recovery independence | one row per position (or trade), obligor and date | for the DRC |
| `residual-risk` (RRAI, planned) | RRAO class (exotic, other, none), gross notional, back-to-back and listed-or-clearable flags | one row per trade and date | optional |

**The naming standard.** One slash-separated name per factor, the same in every system: `IR/CURVE/USD/SOFR/10Y` (a curve point),
`IR/CURVE/USD` (the currency: GIRR's curvature factor), `IR/BASIS/EUR`, `INF/USCPI` and `INF/USCPI/10Y`, `CR/SPREAD/<issuer>/<BOND or
CDS>/<tenor>` and `CR/SPREAD/<issuer>` (CSR curvature), `FX/SPOT/EUR`, `EQ/SPOT/SPX`, `EQ/REPO/<name>`, `CM/PRICE/WTI/CUSHING/1Y` and
`CM/PRICE/WTI` (commodity curvature), `VOL/<class>/<underlying>/<expiry>[/<underlying tenor>]`. A source's own names are mapped by the
dictionary's `SOURCE_NAMES`; a name that maps to nothing is refused and counted, never guessed.

**From the delivered conventions to the regulatory sensitivity** (MAR21.19 to MAR21.25):

```
rates, spreads, inflation, equity repo   s = DV01 (or CS01, IE01, repo DELTA) × 10,000
spots and prices                         s = DELTA × 100
vega                                     s = VEGA × 100 × IMPLIED_VOL       (∂V/∂σ · σ)
normal rates vol (VOL_CONVENTION NORMAL) s = VEGA per bp × IMPLIED_VOL in bp
```

Rates vegas are lognormal points by default; a source sending normal volatility declares `VOL_CONVENTION: NORMAL` on its records and is
converted on load.

**Without a DRC input** the answer says *DRC not computed: no default-risk inputs*, never 0. **Without an RRAO input** the engine derives
the RRAO from each position's `PRODUCT_TYPE` (the parameter file's mapping) and its notional from the VaR book; without either, *RRAO not
computed*.

## 4. The sensitivities-based method

### 4.1 Delta

Sensitivities to the same factor are netted across the scope first, then weighted (`WS = RW × s`), aggregated within each bucket and then
across the buckets of the class:

| Class | Buckets and factors | Risk weight | Within a bucket (ρ) | Across buckets (γ) |
|---|---|---|---|---|
| GIRR | each currency; curve × vertex (0.25Y to 30Y), inflation, cross-currency basis | 1.7% to 1.1% by vertex, ÷ √2 for the specified currencies and the reporting currency; inflation and basis 1.6% | the published table of MAR21.46 (switch: the exact formula), × 99.9% between curves; inflation 40%; basis 0% | 50% |
| CSR | 18 buckets by sector and quality; issuer × bond/CDS × vertex | 0.5% to 12% by bucket | name 35% (80% in the index buckets 17 and 18) × tenor 65% × basis 99.9%; bucket 16 adds absolute values | rating 50% between IG and HY (buckets 1 to 15) × MAR21.57's sector table; 0 with bucket 16 |
| Equity | 13 buckets; spot and repo per name | spot 15% to 70%, repo 0.15% to 0.70% | 15%, 25%, 7.5%, 12.5% by bucket group, 80% in the index buckets; spot to repo 99.9%; bucket 11 adds absolute values | 15%; 0 with 11; 75% between 12 and 13; 45% index to name |
| Commodity | 11 buckets; commodity × location × vertex | 20% to 80% | commodity (15% to 95%) × tenor 99% × location 99.9% | 20%; 0 with bucket 11 |
| FX | each currency | 15% (÷ √2 for the pairs a bank lists) | one factor a bucket | 60% |

### 4.2 Vega

Vega factors are implied volatilities by underlying and option maturity (and, for GIRR, the underlying's maturity). The risk weight is
`min(55% × √(LH / 10), 100%)`: 100% for GIRR, CSR, commodity and FX, 77.78% for equity buckets 1 to 8, 12 and 13, 100% for 9 to 11.
Within a bucket `ρ = min(ρ_option-maturity × ρ_underlying-maturity, 1)` for GIRR and `min(ρ_delta × ρ_option-maturity, 1)` for the
others, with `ρ_maturity = exp(−1% × |T_k − T_l| / min(T_k, T_l))`; across buckets the delta γ.

### 4.3 Curvature

Curvature factors are coarser than delta's: per **currency** for GIRR (every curve shifted together; no inflation or basis), per issuer for
CSR, per commodity, per equity spot, per currency for FX. The shock is the curvature risk weight: 1.7% for GIRR (switch: 1.7%/√2), the
bucket's risk weight for CSR and commodity (a parallel shift), the delta risk weight for equity and FX (a relative shift). From each
position's two revaluations (`CURVATURE_UP`, `CURVATURE_DOWN`, delta included):

```
CVR+ = −(UP − RW · s)        CVR− = −(DOWN + RW · s)          s: the position's delta to the factor's curve or spot
K_b± = √max(0, Σ max(CVR±, 0)² + Σ_{k≠l} ρ²_kl · CVR_k± · CVR_l± · ψ)     ψ = 0 when both are negative
K_b  = max(K_b+, K_b−); the tie goes up when Σ CVR+ > Σ CVR−, else down
Curvature = √max(0, Σ K_b² + Σ γ²_bc · S_b · S_c · ψ(S_b, S_c))          S_b: Σ CVR of the side chosen
```

The correlations are the delta ones **squared** (name only for CSR, commodity only for commodity), and the scenarios are applied **after**
squaring. The side is chosen per bucket and per scenario. FX options that do not reference the reporting currency have their CVR divided
by 1.5 (switch: off, or every FX instrument with the supervisor's approval); an option references the reporting currency unless its record
carries FX curvature on two currencies.

**Curvature from gamma.** A source that cannot revalue sends `GAMMA` (per bp² for a curve, per (1%)² for a spot); Drishti estimates
`CVR± ≈ −½ · Γ · h²` with `h` the shock in the delta's unit. It is an **estimate, an analysis figure until the supervisor accepts it**
(MAR21.17): every screen and export that shows one says so, and the playground shows it beside the revalued figure when both exist.

### 4.4 The three correlation scenarios

Every ρ and γ (squared first for curvature) is used three times: medium (as prescribed), high `min(1.25 ρ, 1)` and low `max(2ρ − 1,
0.75ρ)`. Each scenario repeats the whole aggregation; the classes are added per scenario and the **largest total binds**. High correlations
raise the charge of a long book, low correlations a hedged one.

### 4.5 The alternative specification

When the radicand across buckets is negative (hedges across buckets larger than the buckets), each `S_b` is capped at `±K_b` and the root
taken again (MAR21.4(5)(b)). The screens mark the class and scenario where it applied.

### 4.6 Contributions

Within a scenario the SBM is homogeneous of degree one in the weighted sensitivities and the curvature risks, so each position's
contribution is `Σ_k WS_ik · ∂SBM/∂WS_k` (and the same on its CVRs); the contributions add up to the reported SBM exactly. DRC
contributions follow each obligor's share of its bucket's charge, pro rata over its positions; the RRAO is additive. The figures are not
additive across scopes: a desk's charge is computed from its own netted sensitivities.

### 4.7 Curvature from gamma: the expansion and its limits

Where a source sends `GAMMA` instead of the two revaluations, the revaluations are replaced by their second-order Taylor expansion in the
shock h of the curvature factor:

```
V(x ± h) − V(x) ≈ ± Δ · h + ½ · Γ · h²            so      CVR+ ≈ CVR− ≈ − ½ · Γ · h²
h = RW_curvature · 10,000 bp for a curve, RW_curvature · 100 (per cent) for a spot; Γ in the delivered unit (per bp² or per (1%)²)
Γ = Σ_t Σ_u Γ_tu  for a curve: the gamma of the parallel shift, every pair of tenors of every curve of the factor
```

It is an approximation: it drops the third and higher orders, which matter at these shock sizes (a 170 bp parallel move, 15% to 70% on
an equity) and for barriers and digitals; it makes the up and down terms equal, so the side choice carries no information; and when the
source delivers only the diagonal gammas per tenor, `Σ_t Γ_tt` stands for the parallel gamma, a second approximation. MAR21.17 and its
first FAQ allow an alternative formulation only where the bank shows its supervisor the results are very close to the prescribed one
(hand-built case 10 shows the difference: 22,500 estimated against 30,000 revalued).

## 5. The default risk charge

1. **Gross jump-to-default**: `JTD = LGD × notional + (market value − notional)`, kept positive for a long and negative for a short; LGD
   75% senior, 100% non-senior and equity, 25% covered bonds, 100% for a recovery-independent instrument (MAR22.9 to MAR22.14).
2. **Maturity scaling**: × `min(max(M, 0.25), 1)`; cash equity 0.25 or 1 as the row (or the parameter file) says (MAR22.15 to MAR22.18).
3. **Netting per obligor by seniority**: a short offsets a long of the same or higher seniority; the seniorities are taken from the most
   senior down, the longs carried, the shorts left over counted (MAR22.19 to MAR22.21).
4. **Per bucket** (corporates, sovereigns, local governments and municipalities): `HBR = Σ net long / (Σ net long + Σ |net short|)`,
   `DRC_b = max(Σ RW × net long − HBR × Σ RW × |net short|, 0)`, RW by credit quality (AAA 0.5% to CCC 50%, unrated 15%, defaulted 100%).
5. **Total**: the buckets added, no hedging across them.

## 6. The residual risk add-on

`RRAO = Σ gross notional × 1.0%` (an exotic underlying: weather, longevity, natural disasters, future realised volatility) `+ Σ gross
notional × 0.1%` (gap, correlation or behavioural risk: barriers, digitals, baskets, TARFs, autocallables). A back-to-back trade is
excluded entirely; a listed or centrally clearable one only from the other-residual part.

## 7. Scopes and the capital screen (FRSA)

**Any scope.** FRTB-SA takes the VaR scopes (a hierarchy node, a desk, a book, a portfolio, a basket, positions or trades, with `+` and
`−`), resolved as VaR resolves them, as you: your roles and masks apply, and a role that sees a scope only in aggregate is refused. `EXAMPLE`
runs the hand-built cases of section 10 with no data.

**`FRSA <scope> <GO>`** opens the capital screen:

- **Capital stack** (the default tab): one bar each for delta, vega and curvature of the reported scenario, DRC, RRAO and the total, then
  the class × measure grid with each class's **Total** (its three charges added, the server's figure) and SBM; select a cell to drill into its buckets (K_b and S_b per scenario, the curvature side) and their factors.
- **Scenarios**: every class and measure in the low, medium and high scenarios, the binding scenario outlined, the alternative
  specification marked where it applied.
- **Contributions**: by desk, book, legal entity, product type or position; the sum is shown beside the total.

The header names the date, the reporting currency and the binding scenario; *not the regulatory figure* appears whenever a switch differs
from its default or a parameter is overridden.

## 8. The FRTB-SA Playground (PLAY FRTB)

**`PLAY FRTB <scope> <GO>`** opens the playground on the VaR Playground's frame: the grid of weighted sensitivities and curvature risks,
bucket by bucket, with K_b per scenario; the formula bar shows the selected row's formula; the explanation beside it opens the step that
produced it.

### 8.1 Critique: the Choices panel

Every method choice is a switch with its decided default, its alternatives, its rule and why the default was chosen:

| Switch | Default | Alternatives | Rule |
|---|---|---|---|
| `curvature-source` | `revaluation` (the up and down PVs where delivered) | `gamma` (the estimate wherever a gamma is delivered) | MAR21.5, MAR21.17 |
| `girr-curvature-shock` | `undivided` (1.7%) | `divided` (1.7%/√2 for the specified currencies) | MAR21.99 |
| `fx-curvature-divisor` | `non-reporting-options` | `off`, `all` | MAR21.98 |
| `girr-correlation` | `table` (MAR21.46's published table) | `exact` (the formula behind it) | MAR21.46 |
| `scenario` | `binding` | `low`, `medium`, `high` | MAR21.6, MAR21.7 |
| overrides | none | any parameter, `path=value` (`GIRR.delta.across-buckets=0.6`) | not a regulatory figure |

**Compare with** flips one switch and shows both runs side by side: the totals, the binding scenario of each, and per class and measure the
difference with the bucket and the **step where it arises** (the weighted sensitivity, the curvature risk, the correlations within a
bucket with the pair of factors behind it, the sum across buckets, or the scenario), in words: *"Girr-correlation: table → exact raises the
total by 24.37 (0.0%) … GIRR delta rises by 24.37, most of it in bucket USD, between USD CURVE SOFR 5Y and USD CURVE SOFR 10Y (ρ 0.970000
to 0.970446); the difference arises in the correlations within the bucket."*

### 8.2 Reproduce: recipes

**Save recipe** keeps the run: the scope and date, the knowledge time the inputs were read at, the digest of the engine's input, the
parameter files themselves (their bytes and SHA-256), every switch and override, the engine version and the figures. It gets an id
(`FR-…`); **`PLAY FRTB RUN <id> <GO>`** (or `drishti.py play frtb --recipe <id>`) reads the inputs as known then, runs the recorded
parameters and choices, and compares: *reproduced to the cent* when the input digest and every figure are equal, otherwise the
differences named. Every export saves a recipe; its id is on the workbook's Main and Provenance tabs.

### 8.3 The workbook of live formulas

**Export to Excel** downloads the workbook for exactly what is on screen (up to `drishti.frtb.max-factors` factors):

| Tab | What |
|---|---|
| Main | the charges per class × measure × scenario, SBM, the binding scenario, the reported SBM, DRC, RRAO and the total, each beside the engine's figure with a check; the switches as exported and as they stand; every one-switch alternative with the engine's total and a check |
| Config | the switches as drop-down cells; every parameter used with its paragraph; MAR21.46's table and MAR21.57's sector table |
| Data | the scope's elements in the reporting currency, their factor keys and the regulatory `s` (a formula) |
| WS | per factor: the netted `s` (`SUMIFS` over Data), RW (`INDEX/MATCH` into Config), WS |
| Buckets | per bucket: the ρ matrix as formulas on the tenors, names and curves; the scenario terms by `SUMPRODUCT`; K_b and S_b |
| Classes | per class: γ, the plain and capped terms per scenario, the alternative specification, the charge |
| Curvature | per position and factor: UP, DOWN, GAMMA, the delta part, the shock, CVR±; per bucket K±, the side, K_b, S_b; per class the charge |
| DRC, RRAO | the positions, the netting chain by seniority, HBR and the bucket charges; the instruments and their weights |
| Explain, Provenance | the method in plain words; the inputs, batches, refusals, parameter hash and the recipe |

Flip a switch in Config and the workbook recomputes the alternative **without Drishti**; Main's alternatives block then checks it against
the engine's own figure for that alternative. The formulas use only functions Excel, LibreOffice and Google Sheets share. A test
recalculates the workbook in headless LibreOffice and compares it with the engine to the cent, with the defaults and with each switch
flipped. **Hand-built cases workbook** (`/api/frtb/template.xlsx`) is the same workbook on the cases of section 10.

**The Explain tab's words** come from `drishti-banking/src/main/resources/playground/frtb-sa/template/explain.txt` (a line `## <title>`,
then its text; `#` lines are comments), read by the playground frame's `PlaygroundTemplate.explain()`: in plain words what FRTB-SA
answers, why a bank must hold it, the tabs, signs and units, the regime and its parameters; 18 steps from the delivered sensitivity to the
total and the contributions, each worked with the hand-built cases (cases 1 to 5 with the published table: GIRR delta 110,555.42, the
index's own delta charge 150,000 and its curvature 30,000 give SBM 290,555.42 on the low scenario and a total of 566,732.73); and every
term of every tab with its MAR paragraph. The SIMM playground's `playground/simm/template/explain.txt` does the same with the toy
parameter set, so no licensed number appears.

## 9. API, command line and settings

| What | API | Command | CLI |
|---|---|---|---|
| Sensitivities of a scope by factor, bucket, class, tenor, measure or attribute | `GET /api/v1/risk/sensitivities?scope=…&by=BUCKET` | `SENS <id>` (a record) | `drishti.py sens show <scope> --by BUCKET` |
| The factor dictionary and the load's refusals | `GET /api/v1/risk/risk-factors?q=…` | `RFAC <factor>` | `drishti.py sens factors --q SOFR` |
| FRTB-SA of a scope | `GET /api/v1/risk/frtb/sa?scope=…` | `FRSA <scope>` | `drishti.py frtb sa <scope>` |
| Its steps | `GET /api/v1/risk/frtb/sa/explain?scope=…&class=GIRR` | in FRSA | `drishti.py frtb explain <scope>` |
| Contributions | `GET /api/v1/risk/frtb/sa/contributions?scope=…&by=DESK` | in FRSA | `drishti.py frtb contributions <scope>` |
| The playground, a comparison | `GET /api/v1/risk/frtb/playground?scope=…&compare=girr-correlation=exact` | `PLAY FRTB <scope>` | `drishti.py play frtb <scope> --compare …` |
| The workbook | `GET /api/v1/risk/frtb/playground.xlsx?scope=…` | Export to Excel | `drishti.py play frtb <scope> --xlsx f.xlsx` |
| The hand-built cases' workbook | `GET /api/v1/risk/frtb/playground/template` | Hand-built cases workbook | `drishti.py play frtb EXAMPLE --xlsx f.xlsx` |
| Save a recipe; replay it | `POST /api/v1/risk/frtb/playground/recipes?scope=…`; `GET …/recipes/{id}` | Save recipe; `PLAY FRTB RUN <id>` | `--save`; `--recipe <id>` |

Every FRTB endpoint takes the switches (`curvatureSource`, `girrCurvatureShock`, `fxCurvatureDivisor`, `girrCorrelation`, `scenario`),
`override=path=value` (repeatable), `ccy` and `asOf`. Every answer carries its provenance: the date, the knowledge time, the batches, the
dictionary's version, the parameter files' regimes, versions and SHA-256, the choices, the overrides, what was refused and why.

**Settings** (`drishti.frtb.*` in the engine's own file `config/engines/frtb-sa.yaml`, all in [CONFIGURATION.md](../admin/CONFIGURATION.md)): the switch `drishti.engines.frtb-sa.enabled` (the old name `drishti.frtb.enabled` still works; off: `404 DRS-1020`; it needs VaR and the sensitivities on the same server, [OPERATIONS.md section 21](../admin/OPERATIONS.md#21-compute-engines-on-or-off)), `parameters-dir` (else the
market-risk pack's `capital/`), `sensitivity-kind`, `factor-kind` (now `drishti.sensitivities.*` in `config/engines/sensitivities.yaml`, read under the old names too), `default-risk-kind`, `residual-risk-kind`, `rrao-from-positions`,
`max-factors` (2,000), `recipes-dir` (under the data folder), `cache-minutes`, `playground-template`.

## 10. The hand-built cases

Small enough to check with a calculator; each is a unit test of the engine to the cent, and together they are the `EXAMPLE` scope and
the hand-built cases' workbook (whose recalculation by LibreOffice equals the engine).

| Case | Inputs | Result |
|---|---|---|
| 1 | USD DV01 5Y −1,000 and 10Y −500 (RW 1.1%/√2: WS −77,781.75 and −38,890.87) | published table (ρ 0.970): K = 115,106.47 low, 115,892.19 medium, 116,672.62 high; exact formula (ρ 0.970446): 115,129.89, 115,903.82, 116,672.62 |
| 2 | case 1 + EUR DV01 2Y +800 (WS 73,539.11), γ 50% | table: Δ_GIRR 110,555.42 low (binding), 101,286.72, 91,079.64; exact: 110,579.79, 101,300.03, 91,079.64 |
| 3 | an index in equity bucket 12, DELTA 10,000 per 1%, UP 120,000, DOWN −180,000 | CVR+ = CVR− = 30,000, the tie goes down, curvature 30,000 in every scenario; the index's own delta charge is 0.15 × 1,000,000 = 150,000 |
| 4 | a BBB senior bond (10,000,000, MV 9,800,000, 2Y) and a bought CDS (−4,000,000, P&L +50,000, 6M) on one obligor; a BB obligor net short −2,000,000 | net long 5,825,000, HBR 0.744409, DRC 126,177.32 |
| 5 | an FX barrier (50,000,000) and a weather derivative (10,000,000) | RRAO 50,000 + 100,000 = 150,000 |
| 1 to 5 | together (`EXAMPLE`) | SBM low 290,555.42 (GIRR delta 110,555.42 + the index's delta 150,000 + its curvature 30,000), binding; total 566,732.73. Without the index's own delta charge the sum is 140,555.42 and 416,732.73; with the exact formula 140,579.79 and 416,757.11 |
| 7 | two GIRR buckets whose nets oppose more than their charges (curve, basis and inflation each WS ±100,000) | the alternative specification in the medium and high scenarios: K = S_capped = 100,000·√3.8, charge 194,935.89 (medium) |
| 9 | two names in equity bucket 5 (ρ 25%, ρ² 6.25%): A CVR+ 40,000 / CVR− −10,000, B −25,000 / 30,000 | K+ 38,810.44 low, 38,405.73 medium, 37,996.71 high (ρ² scaled after squaring); the upward side, S_b 15,000 |
| 10 | case 3's index sent with GAMMA −200 per (1%)², h = 15 | CVR± = −½ × (−200) × 225 = 22,500, an estimate, beside the revalued 30,000 |

Cases 6 and 11 prove SIMM's aggregation with toy parameters (K 34,000 and SIMM_p 65,574.39; the per-issuer concentration K 786,129.76).

## 11. Refusals and error codes

| Code | What | Refused |
|---|---|---|
| `DRS-5321` | a risk factor not in the dictionary (by its name or a source name) | the element |
| `DRS-5322` | a source name listed under two factors | the dictionary |
| `DRS-5323` | a value that is not a finite number | the element |
| `DRS-5324` | no FX rate for the element's currency on the date | the element |
| `DRS-5325` | the same factor and measure twice in a record | the record |
| `DRS-5326` | a second full run of a source on the date | the run |
| `DRS-5329` | a parameter file missing, incomplete or out of range | the file: the engine answers every request with the reason |

The method's own refusals (a tenor that is not a vertex, a vega without its implied volatility, a measure the class does not have) are
counted and named in each answer's provenance. A curvature whose `SHOCK_SIZE` differs from the parameter file's shock is a warning: the
revaluations are used as delivered.

## 12. Parameters as configuration

The parameters are two files of the market-risk pack, `config/packs/banking/market-risk/capital/frtb-sa-sbm.yaml` (MAR21: risk weights, the
published GIRR table, correlations, the scenario multipliers, curvature shocks) and `frtb-sa-drc-rrao.yaml` (MAR22 and MAR23: LGDs,
seniority order, maturity scaling, DRC risk weights, RRAO weights and the product-type mapping). Each names its `regime`, `version` and
`effective-from` and the paragraph of every value. The engine reads them at start and checks every value and range (a symmetric unit-
diagonal table, a cash-equity maturity of 0.25 or 1, every seniority with its LGD); a bad file refuses the engine with `DRS-5329`, never the
server. A changed file is a new version: every answer and every recipe carries the files' SHA-256, and a recipe keeps the files themselves
so it reproduces after the parameters change.

## 13. FRTB-SA data: every dataset the engine reads

Every dataset the FRTB-SA engine reads, what it is for, whether it is required, where its sample lives, and one real record from the shipped sample
data (`data/testdata/`), formatted, with long arrays shortened as `/* … N more … */`. The field-by-field rules of each kind are its
**data contract** ([docs/contracts/packs/](../contracts/packs/README.md)); how files are named, dated and loaded is
[DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo).

<!-- The block below is generated by tools/docs/guide_data.py from tools/docs/guide_data.yaml: edit the spec, not the block. -->
<!-- guide-data:begin frtb-sa -->
### 13.1 At a glance

| Dataset (kind) | What it is for | Required? | Sample files (`data/testdata/…`) | Guide |
|---|---|---|---|---|
| [`position-sensitivity`](#132-position-sensitivity-the-sensitivities) | each position's sensitivities, a sparse list per record | **yes** | `market-risk/position-sensitivity/position-sensitivity.*.jsonl.gz` | [3](#3-the-inputs-and-their-data-contract) |
| [`risk-factor`](#133-risk-factor-the-factor-dictionary) | each factor's class, type, buckets, tenor and the source systems' names | **yes** | `market-risk/risk-factor/risk-factor.*.jsonl.gz` | [3](#3-the-inputs-and-their-data-contract) |
| [`default-risk`](#134-default-risk-the-drc-inputs) | obligor, seniority, credit quality and notional per position | for the default risk charge | no sample in the demo data | [5](#5-the-default-risk-charge) |
| [`residual-risk`](#135-residual-risk-the-rrao-inputs) | the residual risk add-on's class and notional per trade | optional | no sample in the demo data | [6](#6-the-residual-risk-add-on) |

### 13.2 `position-sensitivity`: the sensitivities

One record per position (or trade), date and batch with a sparse `SENSITIVITIES[]` list: DV01, CS01, IE01 per bp; DELTA per 1%; VEGA with
`IMPLIED_VOL`; curvature up and down with `SHOCK_SIZE`. Each element names its factor by the standard name the factor dictionary
defines.

- **Data contract:** [`position-sensitivity`](../contracts/packs/market-risk.md#sens-position-sensitivities-position-sensitivity) (SENS), key `SENSITIVITY_ID`; 28 fields, 19 required.
- **Sample:** `data/testdata/market-risk/position-sensitivity/position-sensitivity.2026-09-30.jsonl.gz`, the first record.

```jsonc
{
  "SENSITIVITY_ID": "SENS-1000003-B-SENS-2026-09-30-01",
  "GRAIN": "TRADE",
  "POSITION_ID": "POS-BOOK-COMM-1~1000003",
  "TRADE_ID": "1000003",
  "TRADE_IDS": [
    "1000003"
  ],
  "SOURCE_SYSTEM": "ENDUR",
  "BUSINESS_DATE": "2026-09-30",
  "BATCH_ID": "B-SENS-2026-09-30-01",
  "RUN_KIND": "FULL",
  "COMPUTED_AT": "2026-09-30T06:10:00Z",
  "RESTATEMENT": 0,
  "STATUS": "ok",
  "PRICER": "demo-factor-model",
  "MODEL_VERSION": "demo-sens-1",
  "LEGAL_ENTITY": "LE-NY",
  "DIVISION": "DIV-MARKETS",
  "DESK": "DESK-COMM",
  "TT_BOOK": "BOOK-COMM-1",
  "LIMIT_LETTER": "LL-0001",
  "FRTB_DESK": "FD-COMM",
  "PRODUCT_GROUP": "Commodity",
  "PRODUCT_TYPE": "ComSwap",
  "TRADE_STATUS": "AMENDED",
  "MATURITY_DATE": "2027-02-28",
  "CURRENCY": "USD",
  "SENSITIVITIES": [
    {
      "RISK_FACTOR": "CM/PRICE/NG/HENRY_HUB/3M",
      "MEASURE": "DELTA",
      "VALUE": -73736.91,
      "CURRENCY": "USD",
      "RISK_CLASS": "COMM",
      "FRTB_BUCKET": "6",
      "TENOR": "3M",
      "SIMM_BUCKET": "6"
    },
    {
      "RISK_FACTOR": "CM/PRICE/NG/HENRY_HUB/6M",
      "MEASURE": "DELTA",
      "VALUE": -139167.48,
      "CURRENCY": "USD",
      "RISK_CLASS": "COMM",
      "FRTB_BUCKET": "6",
      "TENOR": "6M",
      "SIMM_BUCKET": "6"
    },
    /* … 1 more … */
  ]
}
```

### 13.3 `risk-factor`: the factor dictionary

One row per factor and date: the standard name, risk class, factor type, qualifier, curve and tenor, the FRTB and SIMM buckets,
seniority and liquidity horizon, and `SOURCE_NAMES` mapping each system's own name to it.

- **Data contract:** [`risk-factor`](../contracts/packs/market-risk.md#rfac-risk-factor-dictionary-risk-factor) (RFAC), key `RISK_FACTOR`; 27 fields, 12 required.
- **Sample:** `data/testdata/market-risk/risk-factor/risk-factor.2026-09-30.jsonl.gz`, the first record.

```jsonc
{
  "RISK_FACTOR": "CM/PRICE/BRENT",
  "FACTOR_TYPE": "PRICE",
  "RISK_CLASS": "COMM",
  "QUALIFIER": "BRENT",
  "CURVE": null,
  "UNDERLYING": "BRENT",
  "TENOR": null,
  "UNDERLYING_TENOR": null,
  "FRTB_BUCKET": "2",
  "SIMM_RISK_CLASS": "Commodity",
  "SIMM_BUCKET": "2",
  "SIMM_LABEL1": null,
  "SIMM_LABEL2": null,
  "LIQUIDITY_HORIZON": 20,
  "MODELLABLE": true,
  "MARKET_DATA_REF": "CMD-BRENT",
  "SOURCE_NAMES": [
    "MX:CM:PRICE-BRENT",
    "CM.PRICE.BRENT",
    "BRENT_PRICE_CM"
  ],
  "VALID_FROM": "2026-01-02",
  "BUSINESS_DATE": "2026-09-30",
  "SOURCE": "demo-factor-master"
}
```

### 13.4 `default-risk`: the DRC inputs

The default risk charge's inputs per position and obligor. Without them the answer says *DRC not computed: no default-risk inputs*,
never 0.

- **Data contract:** [`default-risk`](../contracts/packs/market-risk.md#drci-default-risk-inputs-the-drc-per-position-and-obligor-default-risk) (DRCI), key `DEFAULT_RISK_ID`; 15 fields, 10 required.
- **Sample:** no sample in the demo data.

### 13.5 `residual-risk`: the RRAO inputs

The residual risk add-on's class (exotic, other, none) and gross notional per trade. Without them the engine derives the class from
each position's `PRODUCT_TYPE` and its notional from the VaR book.

- **Data contract:** [`residual-risk`](../contracts/packs/market-risk.md#rrai-residual-risk-inputs-the-rrao-per-trade-residual-risk) (RRAI), key `RESIDUAL_RISK_ID`; 12 fields, 7 required.
- **Sample:** no sample in the demo data.
<!-- guide-data:end -->

## 14. Where each number comes from

| Figure | Inputs | Computed by | Shown with |
|---|---|---|---|
| A sensitivity in a ladder or heat map | the `position-sensitivity` elements of the cell's positions, the date's FX | a sum per factor or bucket | the batch, the source system, the proxied share |
| `s_k`, `WS_k` | the elements; the dictionary's bucket; the parameter file's RW | [section 3](#3-the-inputs-and-their-data-contract), [section 4.1](#41-delta) | the parameter file, its version and paragraph |
| `K_b`, `S_b`, delta, vega, curvature | the scope's WS; the scenario's correlations | [section 4](#4-the-sensitivities-based-method) | the scenario, whether the alternative specification applied, the curvature side chosen per bucket |
| SBM | the class charges of each scenario | [section 4.4](#44-the-three-correlation-scenarios) | the three scenarios, the binding one marked |
| DRC | the `default-risk` inputs or the trade and issuer masters | [section 5](#5-the-default-risk-charge) | the obligors netted, each bucket's HBR |
| RRAO | the trades' gross notional and product types; the RRAO mapping | [section 6](#6-the-residual-risk-add-on) | the instruments in each class |

Every answer carries a provenance record: the data versions of each input table, the batches, the parameter files and their SHA-256,
refusals and proxies counted. A scope's netted sensitivities are a sparse sum, so in a cluster each owner sums its shards' positions per
factor and the coordinator adds the partial sums in shard order before the formula runs once: the bank's SBM on a sharded date equals one
server's, bit for bit. The same position sensitivities and factor dictionary feed SIMM ([SIMM_GUIDE.md](SIMM_GUIDE.md)), the MR cube's
ladders and heat maps, sensitivity limits, sensitivity-based stress and P&L explain: a bank delivers one sensitivity batch, not one per
consumer.

## 15. Decisions and the screens chosen

| # | Decision |
|---|---|
| 1 | **The screens:** the FRTB-SA capital screen is the capital stack with the three scenarios as a second tab; SIMM is the netting-set list with margin against the counterparty as a second tab; the factor dictionary is the searchable table with the load's quality as a second tab |
| 2 | **Curvature from gamma** is accepted from a source that cannot revalue, computed by the second-order expansion ([section 4.7](#47-curvature-from-gamma-the-expansion-and-its-limits)) and labelled an estimate on every screen and export |
| 3 | **Rates vega convention:** lognormal volatility points by default; a source sending normal volatility in basis points declares it and is converted on load |
| 4 | **The regime:** Basel MAR21 to MAR23 as the reference, then OSFI CAR chapter 9 |
| 5 | **SIMM indices:** a credit index is one qualifier; look-through to the constituents later |
| 6 | **FX curvature ÷ 1.5** (MAR21.98): on by default for FX options that do not reference the reporting currency; extending it to every FX instrument is a second setting, off, for a bank with the supervisor's approval |
| 7 | A curvature **estimated from gamma is an analysis figure** until the supervisor accepts it for the filing (MAR21.17 and its FAQ); the playground shows it beside the revalued figure whenever both exist |
| 8 | **The GIRR curvature shock** where the bank uses the √2 reduction: the undivided 1.7% until a regulator says otherwise; 1.7%/√2 is a switch |
| 9 | FX ÷ 1.5 only where the rules allow it by default (as 6) |
| 10 | **GIRR correlations:** MAR21.46's published table (rounded to 0.1%) by default, so the bank's figures match what its regulator expects; the exact formula is a switch |
| 11 | **SIMM numbers** ship empty; the bank loads its licensed ISDA file, whose version is recorded on every figure and export |

The screen options, three each, are self-contained mock-ups (dark by default, `#light` for the light theme):

| Screen | A | B | C |
|---|---|---|---|
| FRTB-SA capital | [scenarios first](../lenses/mockups/frtb-sa/A.html): the three scenarios side by side by risk class (the Scenarios tab) | [the capital stack](../lenses/mockups/frtb-sa/B.html): the waterfall from delta to the total, the class × measure grid, the bucket drill (chosen) | [desk by class pivot](../lenses/mockups/frtb-sa/C.html): the MR cube's pivot with the SBM measure |
| SIMM | [netting-set list](../lenses/mockups/simm/A.html) (chosen) | [one netting set](../lenses/mockups/simm/B.html): the product-class tree and the concentration factors | [margin against the counterparty](../lenses/mockups/simm/C.html) (the second tab) |
| Factor dictionary | [the dictionary](../lenses/mockups/risk-factors/A.html) (chosen) | [the load's quality](../lenses/mockups/risk-factors/B.html): coverage by desk, refusals by reason, unmapped names (the second tab) | [the factor's page](../lenses/mockups/risk-factors/C.html): its mapping, its exposure by desk, its move against VaR |

**Proposed, not built:** the consistency check of sensitivities against VaR (for a sample of positions, the first-order P&L
Σ_k s_k · Δx_k,j of each scenario j against the position's scenario P&L, the correlation per desk on the data-trust card: it catches
sensitivities and vectors from different runs or trades); the bank's own limit grids (a DV01 ladder 0–2Y, 2–5Y, 5–10Y, 10Y+ per currency
that sensitivity limits name); the official FRTB-SA captures per filer and date ([FRTB.md section 9.6](../contracts/FRTB.md#96-official-captures)).

## 16. Tests and the rules they were checked against

Each hand-built case of [section 10](#10-the-hand-built-cases) is an engine unit test to the cent (`FrtbHandBuiltCasesTest`), a
check-cell test of the workbook recalculated by LibreOffice (`FrtbPlaygroundWorkbookTest`, with the defaults and each switch flipped) and
the `EXAMPLE` scope. Besides them:

| Area | What the tests prove |
|---|---|
| Parameter files | every value present and in range; every bucket's correlations a valid matrix (symmetric, unit diagonal, positive semi-definite in each scenario); the paragraph references present; a missing SIMM parameter refuses the version |
| Grids | every dictionary factor in exactly one bucket of each grid; a source name under two factors refuses the dictionary |
| Loader | each refusal class of [section 11](#11-refusals-and-error-codes) with its code and count; a proxied record counted; an intraday record replacing a record whole |
| Scopes | a desk's charge equals its positions' charge as a basket; the bank's equals the re-aggregated figure, not the sum of desks; contributions add up to the charge to the cent |
| Cluster | the bank's SBM on a sharded date equals one server's, bit for bit |
| Demo data | `tools/packgen/sensitivity/test_sensitivity_data.py`: names on the standard, every element's factor in the dictionary, one record per derived position, a hand-built swap's ladder and one-sigma P&L equal to the VaR batch's, the committed files equal to the generator's |
| Playground | every check cell passes after recalculation; the formula scanner finds no function outside the common set; a saved recipe replayed gives byte-equal figures; an override never reaches an official record |

The method (sections 4 to 6, the hand-built cases and the parameter files) was checked paragraph by paragraph against the Basel
Framework's MAR21, MAR22 and MAR23 (the version effective 1 January 2023, [bis.org](https://www.bis.org/basel_framework/chapter/MAR/21.htm))
and ISDA's SIMM methodology 2.8+2512 ([isda.org](https://www.isda.org/2026/06/12/isda-publishes-isda-simm-methodology-version-2-8-2512/)),
and every hand-built case was recomputed. The points that a reader most often gets wrong are stated where they apply: one CVR+ and one
CVR− per factor with the side chosen per bucket and per scenario (MAR21.5); curvature across buckets floored at zero, never capped at ±K_b
(MAR21.5(4)); curvature correlations squared first, then the scenario applied (MAR21.100, MAR21.101); the other-sector buckets summed in
absolute value (MAR21.56, MAR21.79); listed or clearable instruments excluded from the other-residual part only (MAR23.7).
