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

# Liquidity-adjusted VaR

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **i6.** Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*What would this scope lose on a bad day if it also had to be sold?* The cost of crossing the spread, and the risk of the days it takes.

## 2. Theory

- VaR assumes a position can be closed in a day at the mid price; neither holds for a large or illiquid position.
- Exogenous liquidity: the bid-ask spread and its variability. Endogenous: the position's size against the market's volume.
- The assumptions of the liquidation-period scaling (independent days, equal variance, an even sale), stated beside the figure.
- Nothing is priced: supplied P&L vectors are scaled and re-ranked, supplied spreads are multiplied by supplied market values.

## 3. The mathematics

```
Exit cost           LC    = Σ_i ½ · |MV_i| · (s_i + κ · σ_i)                 s the relative spread, σ its volatility, κ = 3 by default
Liquidation period  d_i   = max(1, |Q_i| / (π_i · ADV_i))  or the bank's LIQUIDATION_DAYS         π the participation rate, 0.2 by default
Scaling             f(d)  = sqrt( (1 + d)(1 + 2d) / (6d) )                   f(1) = 1, f(4) = 1.3693, f(10) = 1.9621
                    VaR_L = VaR of  t_L(s) = Σ_i f(d_i) · p_i(s)             re-ranked: diversification is kept
Liquidity-adjusted  LVaR  = VaR_L + LC                                       ES the same way
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

The three positions of [VaR decomposition](VAR_DECOMPOSITION.md#4-worked-examples) (VaR 5), with liquidation periods 1, 4 and 2.5 days
(`f` = 1, 1.369306, 1.183216): the scaled total's two worst are −13.318009 (scenario 3) and −8.140542 (scenario 2), so
**VaR_L = 8.140542**. Market values 1000, 500, 400; spreads 0.10%, 0.60%, 0.20%; spread volatilities 0.02%, 0.20%, 0.05%; κ = 3:
exit costs 0.8 + 3.0 + 0.7 = **4.5**. **LVaR = 12.640542**.

## 5. Data contracts

| Input (command) | Kind | Status | Grain | Sent by |
|---|---|---|---|---|
| Market liquidity of an instrument (bid-ask spread, volume, depth) (MLIQ) | `market-liquidity` | planned | One row per liquidity key and BUSINESS_DATE (a date without a delivery keeps the last, and the result says how old it is), keyed by LIQUIDITY_ID (MLIQ-<KEY_TYPE>-<LIQUIDITY_KEY>). | The bank's market-data system or a liquidity data vendor |

Every field: [the contract pages](../../contracts/packs/market-risk.md). One line of each:

`market-liquidity`:

```json
{"LIQUIDITY_ID":"MLIQ-SECURITY-XCH-ComFut-USD-03","BUSINESS_DATE":"2026-09-30","KEY_TYPE":"SECURITY","LIQUIDITY_KEY":"XCH-ComFut-USD-03","BID_ASK_SPREAD":0.0012,"SPREAD_VOLATILITY":0.0004,"SPREAD_WINDOW_DAYS":60,"AVERAGE_DAILY_VOLUME":42000,"VOLUME_UNIT":"contracts","MARKET_DEPTH":1500,"PARTICIPATION_RATE":0.2,"SOURCE":"demo-liquidity","AS_OF":"2026-09-30T21:00:00Z"}
```


## 6. Loading

- One complete file per business date, JSON Lines (`.jsonl`, `.jsonl.gz`, or a tar of them), then the load signal
  ([contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date)).
- The same answers from Delta, Iceberg and the file store.
- A position no row matches is listed under *no liquidity data* with its market value and the figure is marked incomplete; it is never given a zero spread or one day. A row older than the setting's limit is refused.

## 7. Reading results

`LVAR <scope>`; the Liquidity tab of the VaR page (VaR, VaR_L, the exit cost, LVaR, by child); the computed kind `lvar-result`; BI
`banking.market_risk_var_liquidity`; Explain opens each position's liquidity row, its `d` and `f`; the Liquidity tab of `PLAY VAR`.

## 8. Settings, refusals and error codes

`drishti.var.liquidity.spread-multiplier`, `participation-rate`, `max-age-days`, `enabled`.

## 9. Tutorial

Load a liquidity file for a desk, read its LVaR, find the position that takes longest to sell, change the participation rate in the playground.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
