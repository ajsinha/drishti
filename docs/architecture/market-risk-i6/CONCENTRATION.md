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

# Concentration risk

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **i6.** Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*Is this scope's risk spread out or held in a few names?* By position, issuer, sector, country, currency, product; in VaR, in exposure and in the days it would take to sell.

## 2. Theory

- Concentration of risk (shares of VaR) against concentration of exposure (shares of market value): they differ, and why.
- The Herfindahl index and the effective number of parts.
- Hedges are listed apart: a negative component is not a share.
- Liquidity concentration: size against the market's volume.

## 3. The mathematics

```
VaR share              w_i  = max(c_i, 0) / Σ_j max(c_j, 0)           c the component VaR
Herfindahl index       HHI  = Σ_i w_i²          effective number of parts = 1 / HHI
Top-n share                 = the sum of the n largest w_i            n = 1, 5, 10
Diversification ratio       = Σ_i VaR(p_i) / VaR
Exposure share of a group   = |MV| of the group / the scope's gross |MV|       net and gross
Days to liquidate      d_i  = as in liquidity-adjusted VaR;  the share of gross market value with d_i above 5, 10, 20 days
Size against the market     = |Q_i| / ADV_i
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

The three positions of [VaR decomposition](VAR_DECOMPOSITION.md#4-worked-examples): components 1.00, 3.75, 0.25 of a VaR of 5 give
shares 0.20, 0.75, 0.05; **HHI = 0.04 + 0.5625 + 0.0025 = 0.605**; effective number **1.6529**; top-1 share 0.75; standalone VaRs
2 + 7 + 4 = 13, so the diversification ratio is **2.6**.

## 5. Data contracts

| Input (command) | Kind | Status | Grain | Sent by |
|---|---|---|---|---|
| Market liquidity of an instrument (bid-ask spread, volume, depth) (MLIQ) | `market-liquidity` | planned | One row per liquidity key and BUSINESS_DATE (a date without a delivery keeps the last, and the result says how old it is), keyed by LIQUIDITY_ID (MLIQ-<KEY_TYPE>-<LIQUIDITY_KEY>). | The bank's market-data system or a liquidity data vendor |
| Default risk inputs (the DRC, per position and obligor) (DRCI) | `default-risk` | planned | One row per position (or trade), obligor and BUSINESS_DATE, keyed by DEFAULT_RISK_ID (DRC-<POSITION_ID or TRADE_ID>-<OBLIGOR>-<BATCH_ID>). | The bank's risk system or issuer master (obligor, seniority, rating) with the positions' notionals and market values |

Every field: [the contract pages](../../contracts/packs/market-risk.md). One line of each:

`market-liquidity`:

```json
{"LIQUIDITY_ID":"MLIQ-SECURITY-XCH-ComFut-USD-03","BUSINESS_DATE":"2026-09-30","KEY_TYPE":"SECURITY","LIQUIDITY_KEY":"XCH-ComFut-USD-03","BID_ASK_SPREAD":0.0012,"SPREAD_VOLATILITY":0.0004,"SPREAD_WINDOW_DAYS":60,"AVERAGE_DAILY_VOLUME":42000,"VOLUME_UNIT":"contracts","MARKET_DEPTH":1500,"PARTICIPATION_RATE":0.2,"SOURCE":"demo-liquidity","AS_OF":"2026-09-30T21:00:00Z"}
```

`default-risk`:

```json
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000087-5493001KJTIIGC8Y1R12-B-DRC-2026-09-30-01","BUSINESS_DATE":"2026-09-30","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000087","BATCH_ID":"B-DRC-2026-09-30-01","OBLIGOR":"5493001KJTIIGC8Y1R12","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BBB","NOTIONAL":10000000,"MARKET_VALUE":9800000,"CURRENCY":"USD","MATURITY_DATE":"2028-09-30","JTD":5800000,"LGD":0.6,"INSTRUMENT_CLASS":"DEBT","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1"}
```


## 6. Loading

- One complete file per business date, JSON Lines (`.jsonl`, `.jsonl.gz`, or a tar of them), then the load signal
  ([contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date)).
- The same answers from Delta, Iceberg and the file store.
- Issuer groupings need the issuer of a position (`default-risk`'s `OBLIGOR`); without it the issuer view says *no issuer data*.

## 7. Reading results

`CONC <scope>`; the Concentration tab of the VaR page; the computed kind `concentration-result`; BI
`banking.market_risk_var_concentration`; limits on a measure with the one near and breach rule; Explain opens the parts behind a share.

## 8. Settings, refusals and error codes

`drishti.var.concentration.top`, `liquidity-thresholds`, `enabled`.

## 9. Tutorial

Find the desk whose VaR is 75% one position; compare its VaR share with its exposure share; set a limit on the top-5 share.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
