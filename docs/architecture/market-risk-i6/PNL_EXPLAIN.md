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

# P&L explain

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **i6** (the backtest it shows is built: [Value at risk, backtesting](VAR.md#11-backtesting)). Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*What did the desk make, what would yesterday's positions have made, what does the risk model say they made, and does the model still explain the desk?*

## 2. Theory

- The three P&Ls: actual (APL), hypothetical (HPL), risk-theoretical (RTPL), and what each leaves out.
- Unexplained P&L: what the risk model misses (missing factors, approximations, proxies).
- Attribution to risk factors from sensitivities and moves: a first- and second-order reading, and its residual.
- The P&L attribution test: why a rank correlation and a distance between distributions, and what the zones mean for capital.
- Backtesting asks whether VaR is large enough; the attribution test asks whether the model is the desk's.

## 3. The mathematics

```
HPL(S)  = Σ_{i∈S} HPL_i          RTPL(S) = Σ_{i∈S} RTPL_i          RTPL_i = REALISED_PV_i · FX_T − BASE_PV_i · FX_{T−1}
Unexplained(S) = HPL(S) − RTPL(S)
APL(S) − HPL(S) = new and amended trades + intraday + fees + reserves + other        (supplied lines; the rest "not attributed")

Attribution   E(S) = Σ_f δ_f·Δx_f + ½ Σ_f γ_f·Δx_f² + Σ_f ν_f·Δσ_f + θ          Residual(S) = HPL(S) − E(S)

Attribution test, per regulatory desk, the most recent 250 days of (HPL_t, RTPL_t):
  Spearman  ρ = the Pearson correlation of rank(HPL) and rank(RTPL), ties at their mean rank
  KS        D = max_x | F_HPL(x) − F_RTPL(x) |
  Zone      green: ρ > 0.80 and D < 0.09;   red: ρ < 0.70 or D > 0.12;   amber: otherwise
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

**Ten days** (the design's golden G2):

| Day | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| HPL | 12 | −5 | 3 | 8 | −11 | 0 | 6 | −2 | 9 | −7 |
| RTPL | 10 | −6 | 5 | 7 | −8 | 1 | 2 | −3 | 11 | −4 |
| Unexplained | 2 | 1 | −2 | 1 | −3 | −1 | 4 | 1 | −2 | −3 |
| Rank of HPL | 10 | 3 | 6 | 8 | 1 | 5 | 7 | 4 | 9 | 2 |
| Rank of RTPL | 9 | 2 | 7 | 8 | 1 | 5 | 6 | 4 | 10 | 3 |

Squared rank differences sum to 6: **ρ = 1 − 36/990 = 0.963636** (green). The distribution functions differ by at most one step:
**D = 0.1** (amber). The desk is **amber**.

**One position's attribution** (G11): DV01 −19,565.62 per basis point, a move of +3 bp: the delta line is **−58,696.86**; with HPL
−58,211.40 the residual is **485.46**.

## 5. Data contracts

| Input (command) | Kind | Status | Grain | Sent by |
|---|---|---|---|---|
| Hypothetical P&L of a position (daily) (HPL) | `hypothetical-pnl` | planned | One row per position (GRAIN POSITION) or trade (GRAIN TRADE) and BUSINESS_DATE (the day the market moved), keyed by HPL_ID (HPL-<POSITION_ID or TRADE_ID>-<BUSINESS_DATE>); one complete file per date. | The bank's front-office P&L system (the product control or P&L attribution batch) |
| Realised-move PV of a position (the risk model, daily) (RPV) | `realised-pv` | planned | One row per position (GRAIN POSITION) or trade (GRAIN TRADE) and BUSINESS_DATE (the day the market moved), keyed by REALISED_PV_ID (RPV-<POSITION_ID or TRADE_ID>-<BUSINESS_DATE>); one complete file per date. | The bank's risk engine (the run that produces the scenario PVs, one day later) |
| Desk P&L: actual and hypothetical (daily) (DPNL) | `desk-pnl` | active | One row per desk (and its legal entity) per BUSINESS_DATE, keyed by DESK_PNL_ID (DPNL-<DESK_ID>-<BUSINESS_DATE>). | Synthetic sample data (tools/packgen/var/desk_pnl.py); a bank's finance and front-office P&L systems |
| Market move of a risk factor (daily, with its series mapping) (MMOV) | `market-move` | planned | One row per risk factor and BUSINESS_DATE, keyed by MOVE_ID (MMOV-<RISK_FACTOR>-<BUSINESS_DATE>). | The bank's market-data or risk system (the closes its sensitivities were computed from) |

Every field: [the contract pages](../../contracts/packs/market-risk.md). One line of each:

`hypothetical-pnl`:

```json
{"HPL_ID":"HPL-POS-BOOK-RATES-1~MX-20000002-2026-09-30","BUSINESS_DATE":"2026-09-30","POSITION_DATE":"2026-09-29","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000002","SOURCE_SYSTEM":"MUREX","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","HPL":-58211.4,"CURRENCY":"USD","PV_START":1204500.0,"PV_END":1146288.6,"STATUS":"ok","PRICER":"fo-rates-3","BATCH_ID":"B-HPL-2026-09-30-01","COMPUTED_AT":"2026-10-01T05:40:00Z","RESTATEMENT":0}
```

`realised-pv`:

```json
{"REALISED_PV_ID":"RPV-POS-BOOK-RATES-1~MX-20000002-2026-09-30","VALUE_TYPE":"PV","BUSINESS_DATE":"2026-09-30","POSITION_DATE":"2026-09-29","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000002","SOURCE_SYSTEM":"MUREX","LEGAL_ENTITY":"LE-NY","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","CURRENCY":"EUR","BASE_PV":1120300.0,"REALISED_PV":1066150.0,"RISK_MODEL":"demo-factor-model/demo-2","STATUS":"ok","BATCH_ID":"B-RPV-2026-09-30-01","COMPUTED_AT":"2026-10-01T05:55:00Z"}
```

`desk-pnl`:

```json
{"DESK_PNL_ID":"DPNL-DESK-COMM-2026-09-30","BUSINESS_DATE":"2026-09-30","DESK_ID":"DESK-COMM","LEGAL_ENTITY":"LE-NY","ACTUAL_PNL":199665,"HYPOTHETICAL_PNL":-479876,"FEES_AND_COMMISSIONS":298323,"CURRENCY":"USD","SOURCE":"demo-finance","AS_OF":"2026-09-30T22:00:00Z"}
```

`market-move`:

```json
{"MOVE_ID":"MMOV-IR/CURVE/EUR/ESTR/5Y-2026-09-30","BUSINESS_DATE":"2026-09-30","PREVIOUS_DATE":"2026-09-29","RISK_FACTOR":"IR/CURVE/EUR/ESTR/5Y","RISK_CLASS":"IR","LEVEL_PREVIOUS":2.412,"LEVEL":2.442,"MOVE":3.0,"MOVE_UNIT":"BP","SERIES_REF":"curve:EUR-ESTR:5Y","QUALITY":"OBSERVED","STALE_DAYS":0,"SOURCE":"demo-market-data","BATCH_ID":"B-SENS-2026-09-30-01"}
```


## 6. Loading

- One complete file per business date, JSON Lines (`.jsonl`, `.jsonl.gz`, or a tar of them), then the load signal
  ([contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date)).
- The same answers from Delta, Iceberg and the file store.
- `POSITION_DATE` must be the business day before `BUSINESS_DATE` on both position inputs.
- A position with scenario PVs on the position date and no HPL or realised-move row is counted by desk; a desk under the
  completeness threshold is left out of the test for the day, in words.
- The position rows' sum is reconciled with the desk row; both are shown, the desk row is the figure of record for backtesting.
- Without `realised-pv` the RTPL is the newest scenario's, labelled approximate, never used for the test.

## 7. Reading results

`PNL <scope>`, `PLA <desk>`; the desk view (the day's waterfall, the HPL against RTPL scatter, the test's two statistics and zone
over time, the backtest beside it, the largest unexplained positions); the computed kinds `pnl-attribution` and `pla-result`; BI
`banking.market_risk_pnl_`; the official record per regulatory desk (`pnl-official`); Explain opens the positions behind any figure;
`PLAY PLA`.

## 8. Settings, refusals and error codes

`config/engines/pnl-explain.yaml`: the completeness threshold, the tolerance of the reconciliation, the test's window and minimum days; the thresholds in the regime's parameter file.

## 9. Tutorial

Load two days of HPL and realised-move PVs for a desk, read the unexplained P&L, open its largest position, run the test in the playground on the ten days above.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
