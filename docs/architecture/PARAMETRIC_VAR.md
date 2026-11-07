<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Parametric VaR and ES: the normal cross-check from the scenario P&L vectors

**Status: built in 2.9.11.c** (as built: [VAR_GUIDE.md sections 3.12 and 18.15](../guides/VAR_GUIDE.md#1815-parametric-var-and-es-a-cross-check);
tutorial 43). **Decided at the product review of 2026-11-04 (the cut-down design, as built):** the parametric figures are a
**Parametric toggle** and **columns** on the existing VaR explorer (the three tiles, the tree's *Parametric* and *Gap* columns), the
**normal curve** over the P&L path's histogram, and a **?** on every parametric figure; the VaR Playground gains a **Parametric tab**.
`PVAR` is only a command **alias** that opens the explorer with the toggle on: no screen, view or menu item of its own; there is no
`PLAY PVAR` command and no new menu entry. Kept: the `parametric` block on the existing VaR endpoints, `pvar()` in the pivot and the MR
cube, the command-line flags; the workbook's named cells and checks live on its own **Parametric** tab with its own band (Main is
unchanged). Options B and C of section 14 are not built. The product owner decided the measure on 2026-11-03: parametric
(variance–covariance) VaR and ES computed from the scenario P&L vectors Drishti already holds, shown beside historical VaR with a
**?** tooltip that says how it was calculated, and the normal curve drawn over the VaR screens' P&L histogram. Every name below
(settings, fields, words) is a proposal until the build; the mock-ups to choose from are in [section 14](#14-mock-ups-and-the-build-checklist).

Related: [VAR_GUIDE.md section 18](../guides/VAR_GUIDE.md#18-the-computation) (historical VaR, ES, the decomposition and the lens this
design reuses), [section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen) (horizons),
[section 11](../guides/VAR_GUIDE.md#11-the-api) and [API_GUIDE.md](../guides/API_GUIDE.md#var-engine-apiv1var) (the VaR API it extends),
[VAR_PLAYGROUND.md](VAR_PLAYGROUND.md) (the playground the Parametric tab is part of), [HIGH_PERFORMANCE_NATIVE.md](HIGH_PERFORMANCE_NATIVE.md)
(the `LinearAlgebra` seam the covariance matrix uses, branch `wip/blas`), [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md) (computed, never stored).

## Contents

1. [What it is, and what it is not](#1-what-it-is-and-what-it-is-not)
2. [The method](#2-the-method)
3. [The decomposition: Euler components, marginal, incremental, correlations](#3-the-decomposition-euler-components-marginal-incremental-correlations)
4. [A worked example](#4-a-worked-example)
5. [Inputs and settings](#5-inputs-and-settings)
6. [The API: the existing VaR endpoints, extended](#6-the-api-the-existing-var-endpoints-extended)
7. [The computed contract](#7-the-computed-contract)
8. [The P&L histogram with the normal curve](#8-the-pl-histogram-with-the-normal-curve)
9. [The ? tooltip: what it says](#9-the--tooltip-what-it-says)
10. [The playground's Parametric tab](#10-the-playgrounds-parametric-tab)
11. [Commands, the command line and the screens](#11-commands-the-command-line-and-the-screens)
12. [Where it runs, cost and precision](#12-where-it-runs-cost-and-precision)
13. [Documentation plan](#13-documentation-plan)
14. [Mock-ups and the build checklist](#14-mock-ups-and-the-build-checklist)
15. [Test plan](#15-test-plan)
16. [Open questions, each with a recommendation](#16-open-questions-each-with-a-recommendation)

---

## 1. What it is, and what it is not

**What it is.** For any VaR scope (a hierarchy node of MGMT, LEGAL or LL, a portfolio, a basket, an ad-hoc trade set), take the same
summed scenario P&L vector historical VaR ranks, fit a normal distribution to it (its mean μ and standard deviation σ) and read VaR and
ES from the normal instead of from the ranked scenarios. Shown beside historical VaR, the difference between the two (the **fat-tail
gap**) says how far the scope's real tail is from a normal one with the same spread.

**What it is for.**

- A **cross-check** of the official historical figure: a large gap points to a few extreme scenario days carrying the VaR (fat tails,
  skew, a jump), a small gap to a book whose P&L is close to normal.
- **Smooth decomposition.** Euler components from covariances (section 3) are stable from day to day, where historical components
  depend on the handful of scenarios around the VaR rank.
- **Correlations between desks** (and any children): the desk × desk matrix behind the diversification.
- A teaching device: the tooltip and the playground's Parametric tab show the textbook formula with the scope's own numbers.

**What it is not.** It is **not the official VaR**: limits, limit use, backtesting, official records and regulatory reports stay on
historical VaR. Every place it appears says *cross-check, not the official VaR*. It is **not factor-level parametric VaR** (sensitivities ×
a factor covariance matrix, usable before a deal): that is out of scope and listed as later work (open question 9). It needs **no new
input**: no sensitivities, no factor covariance, nothing new in the lake.

## 2. The method

### 2.1 The vector, the weights, μ and σ

With `v` the scope's scenario P&L vector (length N, scenario order oldest first, the reporting currency, the lens's horizon:
section 2.4), and weights `w_j` (Σ w_j = 1):

| Weighting (`drishti.var.parametric.weighting`) | Weights | μ | σ² |
|---|---|---|---|
| `equal` (the default) | `w_j = 1/N` | `μ = (1/N) Σ_j v_j` | `σ² = (1/(N−1)) Σ_j (v_j − μ)²`: the sample variance (Excel's `STDEV.S`²) |
| `ewma` | `w_j = (1−λ)·λ^(N−j) / (1−λ^N)`, j = 1 … N, the newest scenario (j = N) weighted most | `μ = Σ_j w_j v_j` | `σ² = Σ_j w_j (v_j − μ)²` (RiskMetrics' form, no small-sample correction) |

λ is `drishti.var.parametric.lambda` (0.94 when `ewma` is chosen). Both are computed in two passes (μ first, then the centred sum) in
float64, in scenario order: the same bits on any number of threads or shards, because the scope's vector is already the same bits
(VAR_GUIDE section 18.6, *sums for any scope come first*).

### 2.2 VaR and ES

With c the VaR confidence, c_ES the ES confidence, Φ the standard normal distribution and φ its density:

```
z      = Φ⁻¹(c)                              2.3263 at 99%, 1.6449 at 95%, 1.9600 at 97.5%, 2.5758 at 99.5%
VaR_c  = z·σ − μ                             positive for a loss, as historical VaR (VAR_GUIDE 18.1)
ES_c   = σ·φ(z_ES)/(1 − c_ES) − μ            z_ES = Φ⁻¹(c_ES); at 97.5%: φ(1.9600)/0.025 = 2.3378
```

**Signs.** `v` is P&L (a gain positive), so a loss is the left tail; the normal's c-quantile loss is `−(μ − z·σ) = z·σ − μ`. A scope
that made money on average (μ > 0) has a lower VaR; one that lost on average (μ < 0) a higher one. VaR is not floored at zero (a scope
whose μ exceeds z·σ shows a negative VaR, as historical VaR can), and the answer says so in words.

**σ = 0** (a constant vector: no trades, or a scope whose positions offset exactly): VaR = ES = −μ, every component is −μ_i, the tooltip
says *no spread: the normal is a point*. An empty scope has no figure (*No trades*), as historical.

**The shape.** The answer also carries the sample **skewness** `(1/N) Σ (v−μ)³ / ((1/N) Σ (v−μ)²)^{3/2}` and **excess kurtosis**
`(1/N) Σ (v−μ)⁴ / ((1/N) Σ (v−μ)²)² − 3` (equal weights; with `ewma`, weighted the same way). They are not used in the VaR; they explain
the gap in the tooltip (a normal has 0 and 0).

### 2.3 The fat-tail gap

```
gap      = VaR_historical − VaR_parametric          (same scope, date, currency, lens, confidence)
gap %    = gap / VaR_parametric
ES gap   = ES_historical − ES_parametric
```

Positive: the scenario tail is heavier than the normal predicts, so the normal understates the loss (the usual case for trading books).
Negative: the worst scenario days were milder than a normal with that σ. The gap is about one order statistic (the 6th worst of 509)
against a quantile of a fitted curve, so one extreme day moves it: the tooltip says so.

### 2.4 Horizon, confidences, window, set and currency: the lens rule

**The horizon follows the VaR lens (VAR_GUIDE section 18.6), never a rule of its own** (`drishti.var.parametric.horizon-rule: lens`,
recommended and the default):

| Lens | The vector parametric reads | Effect |
|---|---|---|
| 1-day | the 1-day vector | as above |
| 10-day `sqrt10` | the 1-day vector × √10 (as historical) | σ and μ both × √10, so VaR and ES are the 1-day figures × √10 exactly |
| 10-day `sum` | the N − 9 overlapping 10-day window sums (500 of 509) | μ and σ of the windows; the windows overlap, so σ is estimated from about 50 independent periods (the tooltip says so) |

The textbook square-root-of-time rule scales μ by h and σ by √h (`μ·10`, `σ·√10`); the `sqrt10` lens scales μ by √10 because it
scales the whole vector. The difference is `(10 − √10)·μ`, small next to z·σ√10 for a trading book (1.4 million, 0.4%, on the worked example of section 4), and the
alternative is a setting (`horizon-rule: square-root-of-time`, open question 2). The console's calculator `parametric_var` (USER_GUIDE,
`drishti_quant.py`) is the textbook form and stays so.

**Confidences** are the lens's: VaR's from `drishti.var.parametric.confidences` (blank: `drishti.var.confidences`), ES's from
`es-confidences` (blank: `drishti.var.es-confidences`). **Window** (`scenario-window`, `window=` override), **stressed set**
(`set=stressed`: μ and σ of the stress set's 250 scenarios) and **reporting currency** (`ccy`) apply unchanged: parametric reads the
very vector historical reads.

## 3. The decomposition: Euler components, marginal, incremental, correlations

A scope's parts are historical VaR's parts (VAR_GUIDE section 18.3: child nodes, positions, trades, basket terms, `by=` groups).
For part i with vector `p_i` (Σ_i p_i = v when the parts partition the scope), the weighted covariance with the same weights and
normalisation as σ² (section 2.1):

```
cov(p_i, v)      = (1/(N−1)) Σ_j (p_ij − μ_i)(v_j − μ)          (equal)   or   Σ_j w_j (p_ij − μ_i)(v_j − μ)   (ewma)
ρ_i              = cov(p_i, v) / (σ_i · σ)                       the part's correlation with the scope
β_i              = cov(p_i, v) / σ²                              the part's share of the scope's variance (Σ β_i = 1)

component VaR_i  = z · cov(p_i, v)/σ − μ_i      = z·ρ_i·σ_i − μ_i
component ES_i   = (φ(z_ES)/(1−c_ES)) · cov(p_i, v)/σ − μ_i
standalone VaR_i = z·σ_i − μ_i                                   (the part's own parametric VaR)
incremental VaR_i= VaR(v) − VaR(v − p_i)                         (μ and σ of v − p_i recomputed)
marginal VaR_i   = component VaR_i / |s_i| × 1,000,000            per million of market value or PV (s_i), as historical; null without a size
diversification  = Σ standalone VaR_i − VaR(v)
```

**They add up exactly.** Σ_i cov(p_i, v) = cov(v, v) = σ² and Σ_i μ_i = μ, so Σ_i component VaR_i = z·σ − μ = VaR, and the same for
ES. The **−μ_i term is required**: z·cov/σ alone adds up to z·σ, not to the VaR, whenever μ ≠ 0. Parts that do not partition the scope
(a selection) do not add up, as historical.

For **nodes and groups** (no size), the screens show the marginal as *per +1% of the part*: the scope's VaR change, to first order, for
1% more of that part (`0.01 × component VaR_i`), the Euler identity's meaning.

**Correlation and covariance matrix.** For the parts of a scope (`matrix=true`, at most `drishti.var.parametric.matrix-max-parts`, 60),
with X the centred N × k matrix of the parts' vectors (`X_ji = √w_j·(p_ij − μ_i)`, equal weights with `1/(N−1)`), the covariance is
`C = XᵀX`, one `dgemm` through the `LinearAlgebra` seam (`com.ash.drishti.compute.linalg`, branch `wip/blas`: OpenBLAS when present,
pure Java otherwise, the canary and per-call fallback included); the correlation `R_il = C_il / √(C_ii·C_ll)`. `σ² = Σ_i Σ_l C_il` is
returned as a check. The **components** themselves are not read off the matrix: each is one `ddot` of its centred vector with the
scope's (index order, the Java path), so the figures the tree and the contribution table show never depend on which BLAS answered.

## 4. A worked example

The mock-ups' synthetic bank (509 scenario days, five desks, a fat-tailed sample; the numbers are made up, consistent with each other):

| Figure | Value |
|---|---:|
| N | 509 |
| μ | −211,407 |
| σ (sample, N − 1) | 54,240,760 |
| z (99%) | 2.326348 |
| z·σ | 126,182,877 |
| **parametric VaR 99%** = z·σ − μ | **126,394,284** |
| φ(1.96)/0.025 | 2.337803 |
| **parametric ES 97.5%** = 54,240,760 × 2.337803 + 211,407 | **127,015,607** |
| historical VaR 99% (6th worst of 509) | 158,495,340 |
| historical ES 97.5% (mean of the 13 worst) | 166,821,647 |
| **fat-tail gap** | **+32,101,056 (+25.4%)** |
| skew, excess kurtosis | −0.73, +3.20 |
| scenario days beyond the parametric VaR | 9 (the normal expects 5.09) |

The desks' Euler components (`z·ρ_i·σ_i − μ_i`):

| Desk | σ_i | μ_i | ρ_i | Component VaR | Standalone VaR | Historical VaR |
|---|---:|---:|---:|---:|---:|---:|
| Rates | 30,464,843 | 156,350 | 0.8254 | 58,341,667 | 70,715,472 | 84,051,284 |
| Credit | 13,729,034 | −630,809 | 0.7489 | 24,550,841 | 32,569,318 | 38,206,986 |
| FX | 12,361,674 | 192,864 | 0.1354 | 3,699,951 | 28,564,691 | 32,992,760 |
| Equity derivatives | 18,967,232 | −133,035 | 0.7347 | 32,550,423 | 44,257,414 | 55,876,274 |
| Commodities | 8,808,345 | 203,223 | 0.3638 | 7,251,401 | 20,288,052 | 24,216,953 |
| **Σ** | | **−211,407** | | **126,394,284** = the bank | 196,394,947 | |

Rates in full: cov(Rates, bank) = 1.3639·10¹⁵ USD², σ_bank = 54,240,760, so z·cov/σ = 2.326348 × 1.3639·10¹⁵ / 54,240,760 =
58,498,017; minus μ_Rates 156,350 gives **58,341,667**, 46.2% of the bank. Diversification: 196,394,947 − 126,394,284 = 70,000,663.

## 5. Inputs and settings

**Inputs:** none new. The scope's vector (from `pnl-vector` or `scenario-pv` with the scenario FX, VAR_GUIDE section 15) and the
hierarchy, exactly as historical VaR reads them.

**Settings** (`config/engines/var.yaml`, under `drishti.var`; also in CONFIGURATION.md and Help → Configuration):

| Setting | Default | Meaning |
|---|---|---|
| `drishti.var.parametric.enabled` | `${DRISHTI_VAR_PARAMETRIC_ENABLED:true}` | the measure: off, no answer carries a `parametric` block, the screens show no parametric column, curve or toggle, the playground's Parametric tab answers `DRS-4023` |
| `drishti.var.parametric.weighting` | `equal` | `equal` or `ewma` (section 2.1); anything else refused at start |
| `drishti.var.parametric.lambda` | `0.94` | EWMA's decay, refused outside (0.5, 1); read only with `ewma` |
| `drishti.var.parametric.confidences` | blank | VaR confidences offered; blank: `drishti.var.confidences` |
| `drishti.var.parametric.es-confidences` | blank | ES confidences offered; blank: `drishti.var.es-confidences` |
| `drishti.var.parametric.horizon-rule` | `lens` | `lens` (section 2.4) or `square-root-of-time` (μ·h, σ·√h from the 1-day vector; the `sum` lens is then not offered for parametric) |
| `drishti.var.parametric.curve-points` | `121` | points of the normal curve the path answer carries (21 to 1,001) |
| `drishti.var.parametric.matrix-max-parts` | `60` | most parts of a correlation matrix; more: the largest by \|component VaR\|, `truncated: true` |
| `drishti.var.parametric.show-curve` | `true` | the curve's toggle starts on (each person's choice is then remembered in `varView`) |

No per-request override of weighting or λ in 2.9.11.c (open question 4); `GET /api/v1/risk/var/status` lists the effective block as `parametric`.

## 6. The API: the existing VaR endpoints, extended

**No new endpoint, no new mode parameter.** The `method` parameter is already taken (the 10-day method, `sqrt10` or `sum`), so
`method=parametric` would collide; a new `model=` value would make every client choose. Instead, while the measure is enabled, the
answers that carry historical figures carry a **`parametric` block beside them**, computed from the same vector in the same request.
Historical fields (`var`, `es`, `limitUse`, …) are unchanged, so no client breaks and no figure is ever ambiguous. `parametric=false`
on any of these requests leaves the block out (a lean client); `matrix=true` on contributions adds the correlation matrix.

| Endpoint | What it gains |
|---|---|
| `GET /scope`, `/scopes/{scope}` | `parametric` (below); `provenance.parametric`: *normal, equal weights, z 2.3263, σ with N − 1, 1-day lens · cross-check, not official* |
| `GET /tree` | per node `parametric: {var, es, gap, gapPct}` (σ and μ only on the headline, keeps the tree light) |
| `GET /scope/path` | `parametric` with `curve` and `expected` for the histogram (section 8) |
| `GET /scope/contributions` | per row `pComponentVar`, `pComponentEs`, `pStandaloneVar`, `pIncrementalVar`, `pMarginalPer1m`, `pPct`, `rho`, `sigma`, `mu`; totals `pSumComponent`, `pSumStandalone`, `pDiversification`; with `matrix=true`: `matrix {keys, labels, correlation[k][k], covariance[k][k], truncated, sigmaCheck, engine}`; Arrow: the same as columns |
| `POST /captures`, `GET /captures/{id}` | the capture keeps its `parametric` block (a capture's figures as computed); `/compare` shows both |
| `GET /status` | `parametric {enabled, weighting, lambda, confidences, esConfidences, horizonRule, curvePoints}` |
| `GET /var/playground…` | the Parametric tab's data (section 10): `measure=parametric` on the existing playground endpoints, not new ones |

The **`parametric` block** of a headline:

| Field | Type | Meaning |
|---|---|---|
| `var`, `es` | integer | parametric VaR at `confidence`, ES at `esConfidence`, the answer's currency, positive for a loss |
| `mu`, `sigma` | number | μ and σ of the lens's vector (section 2) |
| `n` | integer | scenarios (or 10-day windows) used |
| `z`, `zEs`, `esFactor` | number | Φ⁻¹(c), Φ⁻¹(c_ES), φ(z_ES)/(1 − c_ES) |
| `zSigma` | number | z·σ (so the tooltip prints the formula without the browser multiplying) |
| `weighting`, `lambda` | string, number or null | `equal`, or `ewma` with λ |
| `horizonRule` | string | `lens` or `square-root-of-time` |
| `skew`, `excessKurtosis` | number | section 2.2 |
| `gap`, `gapPct`, `esGap` | integer, number, integer | section 2.3; against this answer's `var` and `es` |
| `beyond` | integer | scenarios whose P&L is below −`var`; `expectedBeyond` = n·(1 − c) |
| `sumStandalone`, `diversification` | integer or null | over the default parts, as historical |
| `official` | boolean | always `false` |
| `describe` | list of strings | the tooltip's lines, built in the server in the person's language (section 9) |
| `notes` | list of strings | *σ = 0: the normal is a point*, *10-day sum: 500 overlapping windows, about 50 independent periods*, *ewma: the histogram is unweighted, the curve weighted* |

**The computed kind.** `POST /api/v1/compute/var-engine/var-result` (`VRES`) carries the same figures as fields (section 7) and takes
`"params": {"parametric": false}` to leave them out.

**Errors.** Nothing new: the measure off is `parametric` absent and `parametric=true` answering `403 DRS-5002` in words (*parametric VaR
is switched off on this server*); `matrix=true` on a scope without parts is `400` (as `by=node`). Entitlements are historical VaR's: the
same scope, the same trades, the same masks (an aggregate-only viewer gets node-level parametric figures, never positions or trades).

## 7. The computed contract

Parametric VaR is a computed figure of the existing kind, not a new kind: **`var-result` (VRES) version 1.3** gains optional fields
(present when the measure is enabled and not asked away), documented in `config/packs/market-risk/contracts/var-result.contract.yaml`
and generated into [docs/contracts/packs/market-risk.md](../contracts/packs/market-risk.md#vres-var-of-a-scope-computed-var-result).

| Field | Type | Presence | Meaning |
|---|---|---|---|
| `PARAMETRIC_VAR` | amount (`CURRENCY`) | optional | z·σ − μ at `CONFIDENCE`, positive for a loss |
| `PARAMETRIC_ES` | amount | optional | σ·φ(z_ES)/(1 − c_ES) − μ at `ES_CONFIDENCE` |
| `PARAMETRIC_MU`, `PARAMETRIC_SIGMA` | amount | optional | μ and σ of the lens's vector |
| `PARAMETRIC_Z` | number | optional | Φ⁻¹(`CONFIDENCE`) |
| `PARAMETRIC_N` | integer | optional | scenarios or windows used |
| `PARAMETRIC_WEIGHTING`, `PARAMETRIC_LAMBDA` | text, number | optional | `equal` or `ewma`; λ |
| `PARAMETRIC_HORIZON_RULE` | text | optional | `lens` or `square-root-of-time` |
| `PARAMETRIC_SKEW`, `PARAMETRIC_EXCESS_KURTOSIS` | number | optional | the shape (section 2.2) |
| `FAT_TAIL_GAP`, `FAT_TAIL_GAP_PCT`, `FAT_TAIL_ES_GAP` | amount, rate, amount | optional | section 2.3 |
| `PARAMETRIC_OFFICIAL` | boolean | optional | always false |

The contract's prose states: *a cross-check computed from the same vector; never an official figure, never a limit's measure*. No input
contract changes. Official records (`var-official`) do not gain these fields (open question 6).

## 8. The P&L histogram with the normal curve

The P&L path's histogram (VAR_GUIDE section 3.4: `histogram {edges, counts}`, `drishti.var.histogram-bins` equal bins from the smallest to
the largest scenario P&L) gains an overlay. **The server sends every point; the browser computes nothing** (the server-computes-risk
rule): the chart library draws a supplied polyline, so points are sent rather than μ and σ for the browser to evaluate.

`GET /scope/path` → `parametric`:

| Field | Meaning |
|---|---|
| `mu`, `sigma`, `n`, `var`, `es`, `z`, `zSigma`, `gap`, `gapPct` | as the headline's block |
| `curve {x: number[P], y: number[P]}` | P = `curve-points` (121) points evenly from `edges[0]` to `edges[bins]`; `y = n × binWidth × φ((x − μ)/σ)/σ`: the expected days per bin, so the curve sits on the bars' scale |
| `expected: number[bins]` | days the normal expects in each bin, `n·(Φ((b − μ)/σ) − Φ((a − μ)/σ))` for bin [a, b) (sent with the curve; option B's tail table was not built) |
| `markers {historical, parametric}` | the x of each VaR line: `−var` (historical) and `μ − z·σ` (parametric) |
| `binWidth` | `edges[1] − edges[0]` |

**What the chart shows** (every mock-up):

| Element | Drawing |
|---|---|
| bars | days per bin, as today; bins wholly beyond historical VaR in the loss colour |
| the curve | a smooth line through `curve`, the parametric colour (blue), 2 px |
| historical VaR marker | solid vertical line in the loss colour at `markers.historical`, labelled *historical 158.5m* |
| parametric VaR marker | dashed vertical line in the parametric colour at `markers.parametric`, labelled *parametric 126.4m* |
| tails | the area left of each marker lightly shaded in its colour (two overlapping light tints) |
| fat-tail gap | a bracket between the two markers above the plot, labelled *fat-tail gap 32.1m (+25%)* in the warning colour |
| legend | bars, beyond-VaR bars, the fitted normal, each marker with its value, the two tails, and the **Normal curve** toggle |
| hover on the curve | *Fitted normal N(μ, σ²) · n = 509 · μ · σ · at P&L x: y days expected per bin · = n × bin width × φ((x − μ)/σ)/σ · VaR 99% = z·σ − μ* |

**The toggle.** *Normal curve* (key **N**), on by default wherever the measure is enabled (`show-curve`), remembered per person in
`varView` and in the link (`&nc=0`); off hides the curve, the parametric marker, its tail and the gap bracket, and leaves the
historical chart as today. With `ewma` the bars stay unweighted counts and the curve is the weighted fit: the legend says so.

## 9. The ? tooltip: what it says

A **?** sits on every parametric figure: the tree's column, the tiles, the contribution table's components, the chart's curve. It opens on
click (and on hover on a desktop), stays open until closed, and is a bottom sheet on a phone. **Every number in it comes from the
answer** (`parametric` block, `describe` lines): the console places text, never computes.

| Line | Content (the worked example's numbers) | From |
|---|---|---|
| Title | *Parametric VaR 99% · 1 day · Rates (DESK-RATES)* | lens, scope label |
| Status | *A cross-check, not the official VaR. The official figure is historical: 84,051,284.* | `official`, headline `var` |
| Method | *normal (variance–covariance) on this scope's own scenario P&L vector: the same 509 values historical VaR ranks* | fixed text |
| Window | *509 scenario days, the date's set HS-509, ending 2026-09-29* (stressed: the stress set and its period) | `n`, `provenance.scenarioSet` |
| Weighting | *equal (1/n each); EWMA off*, or *EWMA, λ = 0.94: the newest day weighs 6.0%, half the weight in the newest 11 days* | `weighting`, `lambda` |
| Confidence | *99% → z = 2.3263* | `confidence`, `z` |
| Horizon | *1 day*; *10 days: σ and μ × √10*; *10 days: 500 overlapping windows (about 50 independent periods)* | lens, `horizonRule`, `notes` |
| n, μ, σ | *509 · 156,350 · 30,464,843 (sample, n − 1)* | `n`, `mu`, `sigma` |
| Formula | *VaR = z·σ − μ = 2.3263 × 30,464,843 − (156,350) = 70,871,822 − 156,350 = 70,715,472* | `z`, `sigma`, `zSigma`, `mu`, `var` |
| ES (on ES figures) | *ES = σ·φ(z₉₇.₅)/(1 − 0.975) − μ = 30,464,843 × 2.3378 − (156,350) = …* | `esFactor`, `es` |
| Component (on a component) | *z·cov(Pᵢ, P)/σ_P − μᵢ = z·ρ·σᵢ − μᵢ; cov = 1.3639·10¹⁵ USD², σ_P = 54,240,760, ρ = 0.8254 → 58,341,667 (46.2% of the bank); the five components add up to the bank's 126,394,284 exactly* | the row's `rho`, `sigma`, `mu`, `pComponentVar` |
| Historical beside it | *Historical VaR (6th worst of 509): 84,051,284* | headline `var`, `k` |
| The gap | *Fat-tail gap = historical − parametric = +13,335,812 (+18.9%). Positive: the scope's real tail is heavier than a normal with the same σ (skew −1.00, excess kurtosis +5.78), so the normal understates the loss. Negative: the worst days were milder than a normal predicts.* | `gap`, `gapPct`, `skew`, `excessKurtosis` |
| Foot | *Computed in the server (VaR engine), never in the browser · provenance: the vectors' batch · Worked in the playground (its Parametric tab) · Guide* | provenance |

The mock-ups show it in full: on a tree cell (A), on the bank's tile (B), on a component (C) and as the phone's sheet.

## 10. The playground's Parametric tab

The rule (every metric ships with its playground, same release) applies. The VaR Playground has a **Parametric tab** beside its grid
(*VaR*): the same page and link with `measure=parametric`, on the same frame, template loader, `SeriesLayout`, check cells and
LibreOffice test (VAR_PLAYGROUND.md section 13); no second playground machinery and no command of its own. The explorer's **?** popovers
and its *Worked in the playground* link open it on the scope shown.

**The tab.** In place of the grid: the figures (n, weighting, μ, σ, z, the ES factor, parametric VaR = z·σ − μ, parametric ES, Σ
components), the steps, each position's μᵢ, σᵢ, cov(Pᵢ, P), ρᵢ and components, and each scenario's `w`, `v − μ` and `(v − μ)²`: the
explanation's steps: the vector → μ → σ → z → VaR, ES → the selected position's
covariance → its component → the check that the components add up.

**The workbook** (the VaR Playground's tabs; Main, Config, Data, FX, PnL, Explain and Provenance unchanged, plus one tab):

| Tab | Holds |
|---|---|
| Main | the parametric inputs as named cells (`PConfidence`, `PEsConfidence`, `PWeighting`, `PLambda`) and results (`PMu`, `PSigma`, `PZ`, `PVaR`, `PES`) beside the engine's figures with check cells, historical VaR and the gap beside them |
| **Parametric** | one row per scenario: the weight, the portfolio P&L (a reference to PnL), its deviation and square; one column per position: its deviation and its product with the portfolio's; under them μᵢ, cov(pᵢ, v), ρᵢ, the component VaR and ES, the standalone VaR; the sums and the checks |
| Explain | the parametric steps in words and cells, as for historical |

**The formulas, cell by cell** (Data typed, everything after it a formula; the common function set of `XlsxFormulas`):

| Cell | Formula |
|---|---|
| weight `w_j` | equal: `=1/N`; ewma: `=(1-PLambda)*PLambda^(N-j)/(1-PLambda^N)` |
| `PMu` | `=SUMPRODUCT(W, PF)` |
| `PSigma` | equal: `=SQRT(SUMPRODUCT((PF-PMu)^2)/(N-1))`; ewma: `=SQRT(SUMPRODUCT(W,(PF-PMu)^2))` |
| `PZ` | `=NORM.S.INV(PConfidence)` |
| `PEsFactor` | `=EXP(-(NORM.S.INV(PEsConfidence)^2)/2)/SQRT(2*PI())/(1-PEsConfidence)` (the brackets matter: a spreadsheet squares the negated value without them) |
| `PVaR`, `PES` | `=PZ*PSigma-PMu`; `=PSigma*PEsFactor-PMu` |
| μᵢ | `=SUMPRODUCT(W, Pᵢ)` |
| cov(pᵢ, v) | equal: `=SUMPRODUCT((Pᵢ-μᵢ)*(PF-PMu))/(N-1)`; ewma: `=SUMPRODUCT(W,(Pᵢ-μᵢ)*(PF-PMu))` |
| component VaR, ES | `=PZ*covᵢ/PSigma-μᵢ`; `=PEsFactor*covᵢ/PSigma-μᵢ` |
| standalone VaR | `=PZ*σᵢ-μᵢ` with σᵢ as `PSigma` on the position's column |
| ρᵢ | `=covᵢ/(σᵢ*PSigma)` |
| 10-day | the lens's vector: √10 × the 1-day results (`sqrt10`), or μ and σ of the PnL tab's 10-day sum column (`sum`) |

`NORM.S.INV` and `PI` join the common set (Excel 2010+, LibreOffice 4.2+, Google Sheets all have them; open question 7). A **Rest of
scope** column (more than 500 positions) takes part as one more position, so the portfolio is still the scope's.

**Check cells:** `PVaR`, `PES`, `PMu`, `PSigma` equal the engine to the cent (`ABS(sheet − engine) < 0.005`; μ and σ to 10⁻⁶);
Σ component VaR = `PVaR` and Σ component ES = `PES` to the cent; Σ weights = 1. The *ALL CHECKS PASS* band covers them.

**API:** the existing playground endpoints with `measure=parametric` (`/var/playground?measure=parametric`, `.xlsx`, `/estimate`,
`/template`); CLI `drishti.py var playground BANK --measure parametric`.

## 11. Commands, the command line and the screens

**Words** (Rachana's VaR words, extended, no new dialect): `VAR BANK PARAM` (the explorer with *Historical + parametric*),
`VAR DESK RATES PARAM 10D SUM 95`, `VAR BANK PARAM BY DESK` (contributions with the components and the matrix);
VRES: `VRES BANK~parametric=true`. The type-ahead offers `PARAM` once a scope is typed.

**The explorer.** A **Parametric** toggle on the VaR explorer (the link's `&pm=1`; the command `PVAR <scope> [lens]` is an alias that
opens the explorer with it on, as `VAR <scope> [lens]` would read the scope and lens). With it on: the three tiles (parametric VaR,
parametric ES, fat-tail gap), the tree's *Parametric* and *Gap* columns, the normal curve on the P&L path's histogram (key **N**), a
**?** on every figure, and *Worked in the playground* (the playground's Parametric tab). Off: the explorer is historical only and its
answers are asked without the block (`parametric=false`). No views, tabs, screens or menu items of its own.

**Command line:** `drishti.py var show BANK` prints the parametric line under the historical one while enabled (`--no-parametric` to
leave it out); `drishti.py var parts BANK --matrix` prints the components and the correlation matrix; `--json` carries the
block.

## 12. Where it runs, cost and precision

**In the server's VaR engine** (`drishti-compute`, beside `VarCalc`/`VarMath`: a `ParametricVar` class of pure functions on `double[]`,
called by the same request path that ranks the vector); no compute workers, nothing in the browser. Sharded dates (VAR_SHARDING.md) need
nothing new: parametric reads the scope's assembled 1-day vector; the matrix's parts are assembled vectors as the contribution table's.

**Cost.** μ and σ: 2N operations on a vector already in hand (1,018 for 509), noise next to the sort historical VaR already does. The
tree: one more pass per node. Contributions: one `ddot` per part (N each), the same order as the component VaR it sits beside. The
matrix: one `dgemm` of N × k, k ≤ 60: 509 × 60 × 60 ≈ 1.8 million multiply-adds, under a millisecond. The curve: P density
evaluations. Nothing is stored or cached beyond the answer cache historical answers already use (the same key plus the measure's
settings).

**Precision.** Two-pass, float64, scenario order: the same bits on 1 or 64 threads and on any shard layout, because the input vector
is (VAR_GUIDE section 18.6). Σ components equals the VaR to about 10⁻⁶ USD (float64 rounding of the covariance sum), well inside the cent
the tests demand. Φ⁻¹ by Acklam's rational approximation refined by one Halley step (relative error under 10⁻¹⁵); φ and Φ by the
standard series and `erfc` (the backtest's Numerical Recipes `erfc` is too coarse for 10⁻¹⁵ and is not reused).

## 13. Documentation plan

The two-docs rule (one complete guide incl. the contract + one tutorial; two help entries) and docs-with-every-change:

| Document | What changes |
|---|---|
| VAR_GUIDE.md | **18.15 Parametric VaR and ES (a cross-check)**: sections 2–3 of this design as built; **3.12 Parametric beside historical** (the screen, the curve, the tooltip, pictures); 11 (the fields), 12 (the settings), 10 (CLI), Part II (VRES 1.3 fields) |
| docs/tutorials/43-parametric-var-beside-historical.md | **new tutorial**: the bank's gap, a desk's tooltip read line by line, the curve and the toggle, the toggle, the playground's Parametric tab with the components adding up, and its workbook's checks; every step UI + CLI + YAML, with pictures |
| Help | two entries: *Parametric VaR* (the guide section) and *Tutorial: parametric VaR beside historical* |
| API_GUIDE.md, CLI_GUIDE.md | the `parametric` block, `parametric=false`, `matrix=true`, `measure=parametric`; `--no-parametric`, `--matrix`, `--measure` |
| CONFIGURATION.md (generated, checked) | the `drishti.var.parametric.*` settings |
| var-result.contract.yaml → docs/contracts/packs/market-risk.md | version 1.3 fields (generated) |
| VAR_PLAYGROUND.md | the playground family row *Parametric VaR*, the Parametric tab and formulas |
| USER_GUIDE.md | the explorer's Parametric toggle; a line under `parametric_var` that the server's figure follows the lens (section 2.4) |
| Knowledge, CHANGELOG, RELEASE_NOTES, the VaR deck | an entry; the change; a slide *historical and parametric side by side* |
| this document | *as built* at the end of the release |

## 14. Mock-ups and the build checklist

Mock-ups (self-contained HTML, dark by default, `#light` for the light theme; synthetic numbers of section 4):

| File | Option |
|---|---|
| [mockups/parametric-var/A.html](../lenses/mockups/parametric-var/A.html) | **A (recommended default): beside historical on the existing explorer.** Tree columns *Parametric* and *Gap*, the three tiles, the curve on the P&L path with both markers and the gap bracket, the curve's hover card, the **?** open on the Rates desk's parametric cell |
| [mockups/parametric-var/B.html](../lenses/mockups/parametric-var/B.html) | **B: a dedicated Parametric tab.** The large histogram with the curve, the left tail bin by bin (seen against expected), the fat-tail gap per desk with skew and kurtosis, a small histogram and curve per desk, the **?** open on the bank |
| [mockups/parametric-var/C.html](../lenses/mockups/parametric-var/C.html) | **C: Euler components and correlations.** The contribution table with z·cov/σ, −μ, component, share, marginal per +1%, standalone and historical component; the build-up bar; the desk correlation heat map; the curve; the **?** open on the Rates component |
| [mockups/parametric-var/phone.html](../lenses/mockups/parametric-var/phone.html) | **Phone (390 px):** tiles, the chart with the curve, the desks list, the tooltip as a bottom sheet |

**Decided (product review, 2026-11-04):** A's toggle, tiles, tree columns, curve and **?** on the existing explorer, with the
components and the worked figures in the VaR Playground's Parametric tab; B (a dedicated tab) and C (components and the heat map on the
explorer) are not built. The mock-ups stay as the record of the options.

**Build checklist** (2.9.11.c, after the product owner picks):

1. `ParametricVar` in `drishti-compute` (μ, σ, z, VaR, ES, shape, curve, expected, components, incremental, matrix through `LinearAlgebra`); unit tests (section 15).
2. `VarProperties.parametric` and `config/engines/var.yaml`; status.
3. The `parametric` block on `/scope`, `/tree`, `/scope/path`, `/scope/contributions` (+ `matrix=true`, Arrow columns), captures, VRES 1.3; `describe` lines.
4. The console: the Parametric toggle, tree columns, tiles, the curve and markers and the N toggle on the P&L path's chart, the **?** popover and phone sheet.
5. The playground's Parametric tab: the workbook's Parametric tab, Main's cells, the explanation steps, `NORM.S.INV`/`PI` in the common set, `measure=parametric`.
6. Words (`PARAM`; `PVAR` an alias of the explorer with the toggle on), CLI flags.
7. Tests (section 15) green in the pre-merge gate; the documents of section 13; screenshots from the demo.

## 15. Test plan

| Test | Proves |
|---|---|
| `ParametricVarTest` | μ, σ, VaR, ES against a hand-computed 10-value vector and against a 509-value vector computed independently with BigDecimal (to 10⁻⁹ relative); the sign (μ > 0 lowers VaR); σ = 0; z at 95/97.5/99/99.5% against tabulated values to 10⁻¹²; ES factor; EWMA weights summing to 1, the newest the largest; skew and kurtosis of a known sample |
| `ParametricEulerTest` | Σ component VaR = VaR and Σ component ES = ES **to the cent** on: five desks, 1,000 positions, an ad-hoc basket, `by=attribute:TRADER_ID`, μ ≠ 0, EWMA; incremental against a recomputation; a selection that does not partition does not claim to add up |
| `ParametricLensTest` | `sqrt10` = 1-day × √10 exactly; `sum` = μ, σ of the 500 windows; `square-root-of-time` = z·σ·√10 − 10·μ; stressed set; `ccy=EUR`; `window=250` |
| `ParametricDeterminismTest` | the same bits of μ, σ and every component on 1, 2, 8 and 24 threads and on six shards (as `ShardAssemblyTest`) |
| `ParametricMatrixTest` | the correlation matrix through `JavaBlas` and `NativeBlas` (where present) equal to a plain loop to 10⁻¹²; diagonal 1; symmetric; Σ C = σ²; `matrix-max-parts` truncation |
| `ParametricCurveTest` | the curve's area: Σ over bins of `expected` = n·(Φ(max) − Φ(min)); `curve.y` at x = μ equals n·bw/(σ√(2π)); the markers' x |
| `VarParametricApiTest` | the blocks on every endpoint; `parametric=false`; switched off: absent, and `403` when asked; entitlements (aggregate-only viewer); historical fields byte-identical with and without the block |
| `PvarPlaygroundWorkbookTest` (drill, LibreOffice) | the workbook written without cached values, recalculated headless: `PMu`, `PSigma`, `PVaR`, `PES` and every component equal to the engine **to the cent**, Σ components = PVaR, at 1, 50 and 500 positions with Rest of scope, equal and EWMA, USD and EUR; the formula scan |
| Browser (Playwright, the pre-merge gate) | the Parametric toggle on and off, the tree columns, the **?** shows the server's numbers (no number formed in the page), the curve toggles and is remembered, the playground's Parametric tab, the phone sheet at 390 px without sideways scroll |
| Demo figures | `VarTestDataPrecisionTest` gains the bank's parametric VaR, ES, μ, σ and gap on 2026-09-30 for the guide's worked table |

## 16. Open questions, each with a recommendation

1. **Which screen option?** *Decided (product review, 2026-11-04):* A, cut down: a toggle and columns on the existing explorer, the
   curve and the **?**; the components and the worked figures in the playground's Parametric tab; B and C not built.
2. **Horizon rule.** The lens (μ and σ × √10 under `sqrt10`; the windows under `sum`) or the textbook square-root-of-time (μ·10, σ·√10)?
   *Recommendation:* the lens, so historical and parametric always read the same vector and the gap compares like with like; offer
   `square-root-of-time` as a setting and say which in the tooltip.
3. **Include μ, or take μ = 0?** Many desks set μ = 0 for VaR (a mean estimated from two years is noisy and flatters a profitable book).
   *Recommendation:* include μ as decided (it keeps the Euler identity and the playground exact); show μ's share in the tooltip; add
   `mean: zero` later only if risk asks.
4. **Per-request weighting and λ** (`weighting=ewma&lambda=0.97` as a comparison, like `window=`)? *Recommendation:* not in 2.9.11.c;
   settings only. Add later as an `override` like `window`, never captured as official.
5. **EWMA default λ.** *Recommendation:* 0.94 (RiskMetrics daily); note that over 509 days 0.94 puts 95% of the weight in the newest 49
   days, so the curve can differ sharply from the bars; the legend says so.
6. **Store parametric in the official end-of-day record?** It would allow a trend of the gap. *Recommendation:* no (it is not official,
   and a capture keeps it on demand); revisit with the history tab (question 8).
7. **Spreadsheet functions.** `NORM.S.INV` and `PI` are not in `XlsxFormulas.ALLOWED`. *Recommendation:* add both (present in Excel
   2010+, LibreOffice 4.2+, Google Sheets); the fallback, a typed z with a check cell, hides the step the playground exists to show.
8. **Parametric history and backtest** (the exceptions against parametric VaR over 250 days, a Kupiec test of the normal)?
   *Recommendation:* the next release; it is the strongest evidence of the fat-tail gap and reuses `Backtest.of` unchanged.
9. **Factor-level parametric VaR** (sensitivities × factor covariance, usable pre-deal). *Recommendation:* out of scope as decided;
   design it with the parked delta-gamma-vega inputs (VAR_GUIDE 18.12), which supply the same sensitivities and factor moves.
10. **A `PVAR` mnemonic?** *Decided (product review, 2026-11-04):* `PVAR` is only an alias that opens the explorer with the Parametric
    toggle on; no screen or menu item of its own, and no `PLAY PVAR`.
11. **Cornish–Fisher (skew- and kurtosis-adjusted) VaR** as a third figure? *Recommendation:* not now; the tooltip already shows skew and
    kurtosis, and a third number beside two would blur the cross-check.
