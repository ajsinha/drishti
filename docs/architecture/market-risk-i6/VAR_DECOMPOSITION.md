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

# VaR decomposition

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **Built** on the VaR pages (component, incremental and marginal VaR, component ES); **i6** adds what section 7 marks. Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*Which parts of this scope make its VaR, which hedge it, and what would the VaR be without one?* For risk managers and desk heads.

## 2. Theory

- VaR is not additive: the Euler allocation and why components add up.
- Component against incremental: a share of today's VaR, and the change if the part left.
- A hedge: a negative component or incremental VaR.
- Why a component read from one scenario is noisy, and the tail window that steadies it.

## 3. The mathematics

For a scope with total vector `t(s) = Σ_i p_i(s)`, `N` scenarios, `k = ⌈N(1 − α)⌉`, `r(1) … r(N)` the scenarios from the worst total:

```
VaR                 = −t(r(k))
Component VaR  c_i  = −mean of p_i(r(j)), j = k−h … k+h,  scaled by VaR / Σ_i c_i        h = 2 (drishti.var.component-window: 5)
Component ES   e_i  = −mean of p_i(r(j)), j = 1 … k_ES                                   adds up to ES exactly
Incremental VaR     = VaR(t) − VaR(t − p_i)
Marginal VaR        = c_i / size_i, per million of size
Standalone, share   = VaR(p_i);  c_i / VaR
Stability band (i6) = c_i at h = 0, 2 and 4
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

**Three positions, ten scenarios, α = 80% (k = 2), component window 3** (the design's golden G1):

| Scenario | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| P1 | −8 | 3 | 1 | 5 | −2 | 4 | 2 | −1 | 6 | 0 |
| P2 | 1 | −9 | −7 | 2 | 3 | −1 | 4 | 5 | −3 | 2 |
| P3 | 2 | 1 | −4 | −6 | 1 | 3 | −2 | 2 | 1 | −1 |
| Total | −5 | −5 | −10 | 1 | 2 | 6 | 4 | 6 | 4 | 1 |

VaR 5, ES 7.5. Tail scenarios 3, 1, 2: raw components 1.3333, 5, 0.3333, scaled by 0.75 to **1.00, 3.75, 0.25**. Component ES 3.5,
3.0, 1.0. Incremental VaR **−3, 2, −1**: P1 and P3 hedge at the margin. Standalone 2, 7, 4.

The built example on real figures: the rates desk by book in [VAR_GUIDE.md section 2.2](../../guides/VAR_GUIDE.md#22-on-the-test-data).

## 5. Data contracts

It reads no input of its own: the vectors are the [VaR engine's](VAR.md#5-data-contracts).

## 6. Loading

No input of its own: it reads the vectors the VaR engine loaded ([Value at risk, loading](VAR.md#6-loading-a-business-date-from-the-lake-to-memory)).

## 7. Reading results

| Door | What | State |
|---|---|---|
| The VaR page's contribution table; `drishti.py var parts <scope>` | standalone, component, share, incremental, component ES by child, book, position or trade | built |
| `var-decomposition`, a computed kind | one record per scope, grouping and date | i6 |
| Any promoted column as the grouping (`VAR <scope> PARTS BY <column>`) | limit letter, product, currency, risk type where mapped | i6 |
| Incremental VaR of every part in one pass | | i6 |
| Official rows at the levels a site lists | in `var-official` | i6 |
| BI | `banking.market_risk_var_decomposition` | i6 |

## 8. Settings, refusals and error codes

`drishti.var.component-window`, `component-scale-to-var`, `max-parts`; a grouping by a column that is not promoted is refused with the fix.

## 9. Tutorial

From a desk's VaR to the three positions that make it and the one that hedges it; the same in the playground's workbook.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
