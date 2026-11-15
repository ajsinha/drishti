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

# Stress testing

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **i6.** Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*What would this set of trades lose if this scenario happened, which parts lose most, are we within the limit, and what would it take to lose a given amount?*

## 2. Theory

- Stress against VaR: a named event, not a quantile; no probability is claimed.
- Historical, hypothetical and regulatory scenarios; shocks applied at once.
- Full revaluation (the bank's values, exact sums) against a sensitivity-based estimate (always marked).
- Reverse stress: from a loss back to the scenarios that cause it, and how plausible they are.

## 3. The mathematics

```
With values      P&L(S, c) = Σ_{i∈S} ( PV_i(c) · FX(c) − BASE_PV_i · FX_0 )                    exact
Estimate         P&L(S, c) ≈ Σ_f δ_f·x_f + ½ Σ_f γ_f·x_f² + Σ_f ν_f·v_f                        x the scenario's shock of factor f; marked
Limit use                  = loss / the scenario's limit
Reverse, supplied scenarios: those with loss ≥ L; for the others the multiple λ_c = L / loss_c   (a linear estimate)
Reverse, most likely move:   x* = −L · Σδ / (δᵀΣδ),  at Mahalanobis distance L / sqrt(δᵀΣδ)      Σ the covariance of the factors' moves
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

**A sum** (G10): base values 100 and 50, scenario values 80 and 65: P&L −20 and +15, the scope **−5**.

**Reverse** (G10): `δ = (3, 4)`, covariance `[[4, 1], [1, 1]]`, target loss 10: `Σδ = (16, 7)`, `δᵀΣδ = 76`,
`x* = (−2.105263, −0.921053)`; check `3 × −2.105263 + 4 × −0.921053 = −10`; distance `10 / sqrt(76) = 1.147079` standard deviations.

## 5. Data contracts

| Input (command) | Kind | Status | Grain | Sent by |
|---|---|---|---|---|
| Stress scenario definition (historical, hypothetical, regulatory) (SDEF) | `stress-definition` | planned | One row per scenario and validity start, keyed by SCENARIO_ID and VALID_FROM. | The bank's stress-testing team (the stress programme's scenario library) |
| Scenario PVs of a position or trade (one PV per scenario, its currency) (SPV) | `scenario-pv` | active | One row per position (GRAIN POSITION) or trade (GRAIN TRADE), business date and scenario set (BUSINESS_DATE), keyed by SCENARIO_PV_ID; a position or trade is in this kind or in pnl-vector, never both on one date. | Synthetic sample data (tools/packgen/var/make_var_data.py, scenario_fx.py) |
| Scenario set (SSET) | `scenario-set` | active | One row per scenario set per BUSINESS_DATE (the normal set, and the stress set when the batch sends one), keyed by SCENARIO_SET_ID. | Synthetic sample data (tools/packgen/banking) |

Every field: [the contract pages](../../contracts/packs/market-risk.md). One line of each:

`stress-definition`:

```json
{"SCENARIO_ID":"SCN-GFC-2008","NAME":"Global financial crisis, September to November 2008","SCENARIO_TYPE":"HISTORICAL","PROGRAMME":"Board stress programme 2026","DESCRIPTION":"Credit spreads widen sharply, equities fall, rates rally, the dollar strengthens","SEVERITY":"SEVERE","WINDOW_START":"2008-09-12","WINDOW_END":"2008-11-20","HORIZON_DAYS":49,"SHOCKS":[{"SELECTOR":"CR/SPREAD/*/*/*","SHOCK_TYPE":"BP","VALUE":250},{"SELECTOR":"EQ/SPOT/*","SHOCK_TYPE":"PCT","VALUE":-34},{"SELECTOR":"IR/CURVE/USD/*/*","SHOCK_TYPE":"BP","VALUE":-120,"TENOR_FROM":"2Y","TENOR_TO":"10Y"}],"FULL_REVALUATION":true,"OWNER":"Market risk stress testing","APPROVED_BY":"Market risk committee","APPROVED_ON":"2026-01-15","STATUS":"ACTIVE","VALID_FROM":"2026-01-15","SOURCE":"demo-stress-library"}
```

`scenario-pv`:

```json
{"VALUE_TYPE":"PV","GRAIN":"TRADE","TRADE_ID":"1000054","CURRENCY":"EUR","SOURCE_SYSTEM":"ENDUR","SCENARIO_PV_ID":"SPV-1000054-HS-509","SECURITY_ID":null,"INSTRUMENT":"ENGY-SWAP EUR 2026-09","BUSINESS_DATE":"2026-09-30","SCENARIO_SET_ID":"HS-509","TRADE_VERSIONS":"v2","TRADE_STATUS":"VALIDATED","MATURITY_DATE":"2026-09-30","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-COMM","TT_BOOK":"BOOK-COMM-1","LIMIT_LETTER":"LL-0001","PORTFOLIO":"BOOK-COMM-1","STRATEGY":"","PRODUCT_GROUP":"Commodity","PRODUCT_TYPE":"ComSwap","COUNTERPARTY":"CP-NORTHPLAINS","DIRECTION":"SHORT","QUANTITY":-18000.0,"TAGS":"","BASE_PV":14997.44,"VALUATION_DATE":"2026-09-30","BASE_VALUATION_DATE":"2026-09-30","SCENARIO_PV":[139808.35,-108734.24,-56739.35],"STATUS":"ok","PRICER":"demo-factor-model","MODEL_VERSION":"demo-2","COMPUTED_AT":"2026-09-30T06:00:00Z","BATCH_ID":"B-2026-09-30-01","RESTATEMENT":0,"RUN_KIND":"FULL"}
```

The example line of `scenario-pv` above is a row of the VaR set; a stress-test row is the same row with the stress set's id and one value per listed scenario.

## 6. Loading

- One complete file per business date, JSON Lines (`.jsonl`, `.jsonl.gz`, or a tar of them), then the load signal
  ([contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date)).
- The same answers from Delta, Iceberg and the file store.
- The scenario values ride in `scenario-pv` under a scenario set of `SET_TYPE STRESS_TEST` whose `SCENARIO_IDS` list the scenarios in
  the vectors' order (scenario-set 1.2, planned): one format from the pricing batch for VaR, stressed VaR and stress tests.
- A scenario listed in the set and not defined, or defined with values and absent from the set, is named on the result.

## 7. Reading results

`STR <scope> [scenario]`, `SCN <id>`, `RSTR <scope> <loss>`; the stress page (the scenario × child matrix as a heat map, the worst
scenario and child, limit use, the scenario's shocks and narrative, contributors, the reverse-stress panel); the computed kinds
`stress-pnl`, `stress-matrix`, `reverse-stress`; BI `banking.market_risk_stress_`; official per node and scenario
(`stress-official`); `PLAY STRESS`.

## 8. Settings, refusals and error codes

`config/engines/stress.yaml`: the covariance window and its minimum observations, the estimate's switch, the factors a reverse stress considers.

## 9. Tutorial

Load a stress set of five scenarios, read the matrix for a desk, open the worst cell to its positions, build a scenario on the screen, ask what loses 10 million.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
