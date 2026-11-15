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

# Default risk charge

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **i6** (the standardised default risk charge is built, in FRTB-SA). Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*How much could the trading book lose over a year from issuers defaulting, at 99.9%?* By simulation of joint defaults from supplied jump-to-default amounts, LGDs and PDs.

## 2. Theory

- Jump-to-default: the loss if an issuer defaulted now; long and short, netting within an issuer.
- Why defaults are correlated, and the two types of systematic factor.
- The threshold model: an issuer defaults when its asset return falls below the quantile of its PD.
- Monte Carlo: a quantile from simulated losses, its standard error, and why the figure is reproducible.
- What is supplied (JTD, LGD, PD, loadings) and what is simulated (which issuers default together).

## 3. The mathematics

```
Net jump-to-default      J_n = Σ_{i∈S, issuer n} JTD_i
Asset return, path m     X_n = β_G,n·Z_m + β_S,n·Y_m,s(n) + sqrt(1 − β_G,n² − β_S,n²)·ε_m,n          Z, Y_s, ε independent standard normals
Default                  X_n < Φ⁻¹( max(PD_n, 0.0003) )
Loss                     L_m = Σ_n 1{default}·J_n
DRC(S)                   = the ⌈0.999·M⌉-th smallest of L_1 … L_M, floored at 0
Capital figure           = max( the latest weekly DRC , the mean of the last 12 )
Conditional PD           P(default | Z, Y) = Φ( (Φ⁻¹(PD) − β_G·Z − β_S·Y) / sqrt(1 − β_G² − β_S²) )
Pair correlation         β_G,a·β_G,b + β_S,a·β_S,b (same sector factor);  β_G,a·β_G,b (different)
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

- **Two independent issuers, exact** (G9): `J = 100, PD = 2%` and `J = 60, PD = 5%`: the loss is 0 with probability 0.931, 60 with 0.049,
  100 with 0.019, 160 with 0.001; `P(L ≤ 100) = 0.999`, so **DRC = 100** (at 99.95% it would be 160).
- **A conditional default probability**: `PD = 2%`, `β_G = 0.5`, `β_S = 0.3`, `Z = −2`, `Y = −1`:
  `Φ( (−2.053749 + 1.0 + 0.3) / 0.812404 ) =` **0.176755**.
- **A large equal pool**: `PD = 1%`, `β_G² = 0.2`: the 99.9% loss fraction tends to
  `Φ( (Φ⁻¹(0.01) + sqrt(0.2)·Φ⁻¹(0.999)) / sqrt(0.8) ) =` **0.145525**.
- **A hedge**: a long and a short of one issuer of equal JTD contribute nothing on any path.

## 5. Data contracts

| Input (command) | Kind | Status | Grain | Sent by |
|---|---|---|---|---|
| Default risk inputs (the DRC, per position and obligor) (DRCI) | `default-risk` | planned | One row per position (or trade), obligor and BUSINESS_DATE, keyed by DEFAULT_RISK_ID (DRC-<POSITION_ID or TRADE_ID>-<OBLIGOR>-<BATCH_ID>). | The bank's risk system or issuer master (obligor, seniority, rating) with the positions' notionals and market values |
| Issuer default parameters (PD and the two systematic factor loadings) (IDEF) | `issuer-default` | planned | One row per issuer and BUSINESS_DATE (a slowly changing table; a date without a delivery keeps the last), keyed by ISSUER. | The bank's credit risk or default-risk model team (the issuer master with model parameters) |

Every field: [the contract pages](../../contracts/packs/market-risk.md). One line of each:

`default-risk`:

```json
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000087-5493001KJTIIGC8Y1R12-B-DRC-2026-09-30-01","BUSINESS_DATE":"2026-09-30","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000087","BATCH_ID":"B-DRC-2026-09-30-01","OBLIGOR":"5493001KJTIIGC8Y1R12","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BBB","NOTIONAL":10000000,"MARKET_VALUE":9800000,"CURRENCY":"USD","MATURITY_DATE":"2028-09-30","JTD":5800000,"LGD":0.6,"INSTRUMENT_CLASS":"DEBT","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1"}
```

`issuer-default`:

```json
{"ISSUER":"5493001KJTIIGC8Y1R12","BUSINESS_DATE":"2026-09-30","ISSUER_NAME":"Acme Corporation","PD":0.0125,"PD_SOURCE":"INTERNAL","RATING":"BB","SECTOR_FACTOR":"SF-INDUSTRIALS-NA","BETA_GLOBAL":0.5,"BETA_SECTOR":0.3,"CORRELATION_BASIS":"EQUITY","CALIBRATION_START":"2007-01-02","CALIBRATION_END":"2026-06-30","SECTOR":"Industrials","REGION":"North America","DEFAULTED":false,"SOURCE":"demo-credit-model","AS_OF":"2026-09-30T20:00:00Z"}
```


## 6. Loading

- One complete file per business date, JSON Lines (`.jsonl`, `.jsonl.gz`, or a tar of them), then the load signal
  ([contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date)).
- The same answers from Delta, Iceberg and the file store.
- An obligor with no issuer parameters, or a row with neither `JTD` nor `LGD`, is listed and the charge marked incomplete; no default PD, loading or LGD is ever assumed. A PD below the floor is raised and counted.

## 7. Reading results

`DRC <scope>`; the results page (the figure with its standard error, seed and paths; the loss distribution; contributions by issuer,
sector, rating and desk; expected loss and the largest single-issuer loss beside it); the computed kind `drc-result`; BI
`banking.market_risk_drc_`; the weekly official record (`drc-official`); Explain opens the tail's paths; `PLAY DRC` with exact
enumeration for small cases.

## 8. Settings, refusals and error codes

`config/engines/drc.yaml`: paths, the seed, the convergence tolerance, the weekly day, the equity horizon switch; the PD floor and the confidence in `capital/drc-ima.yaml`.

## 9. Tutorial

Load ten issuers, read the charge and its standard error, find the issuer that drives the tail, check the two-issuer case by hand in the playground.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
