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

# FRTB-IMA

> **Skeleton** of a page of the [market-risk section](README.md): the headings and the key formulas; the prose is
> written when the engine is built. **i6.** Design: [MARKET_RISK_I6_DESIGN.md](../MARKET_RISK_I6_DESIGN.md).

## 1. What it answers

*What is the market-risk capital of the desks approved for internal models: which desks qualify today, their expected shortfall with liquidity horizons, the add-on for factors that cannot be modelled, and the total with the standardised fallback?*

## 2. Theory

- From VaR to expected shortfall at 97.5%; liquidity horizons and why longer-horizon factors are shocked again.
- The stress calibration through a reduced factor set.
- Modellable and non-modellable risk factors: the eligibility test and the stress scenario add-on.
- Desk eligibility: backtesting and the P&L attribution test; green, amber and red.
- What the bank decides and prices (the reduced set, the stress period, the scenarios) and what Drishti computes and checks.

## 3. The mathematics

```
Eligibility test, per factor, 12 months, at most one observation a day:
  modellable ⇔ (days ≥ 24 and no 90-day period with fewer than 4)  or  days ≥ 100

Expected shortfall, scope S, calibration q ∈ {FC, RC, RS}, risk-class set C, steps LH = 10, 20, 40, 60, 120:
  t_j(s) = Σ_{i∈S} pnl_i(q, C, j, s)          ES_j = −mean of the ⌈N(1 − 0.975)⌉ worst of t_j
  ES(q, C) = sqrt( ES_1² + Σ_{j=2..5} ( ES_j · sqrt( (LH_j − LH_{j−1}) / 10 ) )² )
  ES(C)    = ES(RS, C) · max( ES(FC, C) / ES(RC, C), 1 )
  IMCC     = ρ · ES(ALL) + (1 − ρ) · Σ_{c ∈ IR, CS, EQ, FX, CM} ES(c)                ρ = 0.5

Stress add-on:  SES_k = max( −Σ_i up_i,k , −Σ_i down_i,k , 0 )
  SES = sqrt(Σ_{idio credit} SES_i²) + sqrt(Σ_{idio equity} SES_j²) + sqrt( (ρ'·Σ_{other} SES_k)² + (1 − ρ'²)·Σ_{other} SES_k² )     ρ' = 0.6

Capital:
  C_A       = max( IMCC_{t−1} + SES_{t−1} ,  m_c · mean60(IMCC) + mean60(SES) )
  IMA_GA    = C_A + DRC
  Surcharge = k · max(0, SA_GA − IMA_GA),   k = 0.5 · Σ_{i∈A} SA_i / Σ_{i∈G∪A} SA_i
  Capital   = min( IMA_GA + Surcharge + C_U , SA_all ) + max( 0 , IMA_GA − SA_GA )
```

## 4. Worked examples

Each example is a test of the engine and the default case of its playground.

- **The cascade** (G4): step figures 100, 60, 40, 20, 10: `100² + 60²·1 + 40²·2 + 20²·2 + 10²·6 = 18,200`, **ES = 134.907376**.
- **The calibration and the class sum** (G5): `150 × max(120/100, 1) = 180`; classes 110, 60, 40, 30, 20:
  **IMCC = 0.5 × 180 + 0.5 × 260 = 220**.
- **The add-on** (G6): idiosyncratic credit 3 and 4, others 6 and 8: `5 + sqrt(8.4² + 0.64 × 100) = 5 + 11.6 =` **16.6**.
- **Capital** (G7): `C_A = max(205 + 16.6, 1.5 × 190 + 15) = 300`; `IMA_GA = 340`; `k = 0.5 × 120/420`; surcharge 11.428571;
  `min(340 + 11.428571 + 90, 560) + 0 =` **441.428571**.
- **The eligibility test** (G8): four factors on a calendar, one each side of each rule.

## 5. Data contracts

| Input (command) | Kind | Status | Grain | Sent by |
|---|---|---|---|---|
| Partial-shock scenario PVs of a position (expected shortfall by liquidity horizon) (ESPV) | `es-scenario-pv` | planned | One row per position (GRAIN POSITION) or trade (GRAIN TRADE), calibration, risk-class set, liquidity-horizon step and BUSINESS_DATE, keyed by ES_PV_ID (ESPV-<POSITION_ID or TRADE_ID>-<CALIBRATION>-<RISK_CLASS_SET>-<LH_STEP>); one complete file per date. | The bank's risk engine (the FRTB internal models run) |
| Real-price observations of a risk factor (for the eligibility test) (RFOB) | `risk-factor-observation` | planned | One row per risk factor, observation type and BUSINESS_DATE on which at least one real price was observed, keyed by OBSERVATION_ID (RFOB-<RISK_FACTOR>-<OBSERVATION_TYPE>-<BUSINESS_DATE>). | The bank's trade repository and its real-price data vendors (pooled observation services) |
| Stress-scenario P&L of a position for a non-modellable risk factor (NMRF) | `nmrf-stress` | planned | One row per position (or trade), non-modellable risk factor and BUSINESS_DATE, keyed by NMRF_STRESS_ID (NMRF-<POSITION_ID or TRADE_ID>-<RISK_FACTOR>). | The bank's risk engine (the non-modellable risk factor run of the FRTB internal models batch) |
| Regulatory trading desk definitions (RDSK) | `regulatory-desk` | planned | One row per desk and validity start, keyed by REG_DESK_ID (RDSK-<DESK_ID>-<VALID_FROM>). | The bank's market risk and Volcker compliance teams (the desk inventory approved with the models) |

Every field: [the contract pages](../../contracts/packs/market-risk.md). One line of each:

`es-scenario-pv`:

```json
{"ES_PV_ID":"ESPV-POS-BOOK-RATES-1~MX-20000002-RS-ALL-20","VALUE_TYPE":"PV","BUSINESS_DATE":"2026-09-30","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000002","SOURCE_SYSTEM":"MUREX","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","CALIBRATION":"RS","RISK_CLASS_SET":"ALL","LH_STEP":20,"RISK_CLASSES":["IR"],"LH_STEPS_SENT":[10,20,40],"RISK_CLASS_SETS_SENT":["ALL"],"SCENARIO_SET_ID":"ES-STRESS-250","CURRENCY":"USD","BASE_PV":1204500.0,"SCENARIO_PV":[1131220.5,1259870.0,1190044.25],"STATUS":"ok","PRICER":"demo-factor-model","MODEL_VERSION":"demo-2","BATCH_ID":"B-ES-2026-09-30-01","COMPUTED_AT":"2026-09-30T07:10:00Z"}
```

`risk-factor-observation`:

```json
{"OBSERVATION_ID":"RFOB-CR/SPREAD/ACME-CORP/SNR/5Y-TRANSACTION-2026-09-30","BUSINESS_DATE":"2026-09-30","RISK_FACTOR":"CR/SPREAD/ACME-CORP/SNR/5Y","RISK_CLASS":"CR","OBSERVATION_TYPE":"TRANSACTION","OBSERVATION_COUNT":3,"BUCKETING":"OWN","EVIDENCE_REF":"CLY-3000087","SOURCE":"demo-trade-repository","AS_OF":"2026-10-01T04:00:00Z"}
```

`nmrf-stress`:

```json
{"NMRF_STRESS_ID":"NMRF-POS-BOOK-CREDIT-1~CLY-3000087-CR/SPREAD/ACME-CORP/SNR/5Y","BUSINESS_DATE":"2026-09-30","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000087","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","RISK_FACTOR":"CR/SPREAD/ACME-CORP/SNR/5Y","NMRF_TYPE":"IDIOSYNCRATIC_CREDIT","LIQUIDITY_HORIZON":60,"METHOD":"STEPWISE","STRESS_PNL_UP":-412000.0,"STRESS_PNL_DOWN":388500.0,"CURRENCY":"USD","STRESS_PERIOD_START":"2008-06-02","STRESS_PERIOD_END":"2009-06-01","STATUS":"ok","BATCH_ID":"B-ES-2026-09-30-01","COMPUTED_AT":"2026-09-30T07:25:00Z"}
```

`regulatory-desk`:

```json
{"REG_DESK_ID":"RDSK-DESK-RATES-2025-11-01","DESK_ID":"DESK-RATES","REGULATORY_DESK_ID":"CA-TD-0007","LEGAL_ENTITY":"LE-NY","MODEL_ELIGIBLE":false,"DESK_BACKTESTED":true,"VOLCKER_DESK_ID":"VD-RATES-NY","COVERED_ACTIVITIES":["MARKET_MAKING"],"TRADING_CALENDAR":"US-NY","VALID_FROM":"2025-11-01","SOURCE":"desk-inventory","AS_OF":"2025-10-28T18:00:00Z"}
```


## 6. Loading

- One complete file per business date, JSON Lines (`.jsonl`, `.jsonl.gz`, or a tar of them), then the load signal
  ([contracts/README.md section 9](../../contracts/README.md#9-delivery-writing-a-business-date)).
- The same answers from Delta, Iceberg and the file store.
- The partial-shock rows are streamed and summed as they pass; a step or class set a position did not declare and did not send
  refuses the position; nothing absent is read as zero.
- Size: 21 to 23 GB a date expected at 1.3 million positions (the design, section 13.2); desk grain is accepted as a first delivery.

## 7. Reading results

`FRIMA <scope>`, `RFET <factor or class>`; the capital page (desk zones, each desk's ES by class and step, the add-on, the capital
formula with its terms, the standardised comparison); the computed kinds `frtb-ima-result` and `rfet-result`; BI
`banking.market_risk_frtb_ima_`; official per regulatory desk and for the bank (`frtb-ima-official`); `PLAY FRIMA`.

## 8. Settings, refusals and error codes

`config/engines/frtb-ima.yaml`; thresholds, horizons, correlations and multipliers in `capital/frtb-ima.yaml`, one per regime. It needs `pnl-explain`, `frtb-sa` and `drc`, and says which is off or away.

## 9. Tutorial

From a desk's partial-shock file to its ES by step, then the bank's capital with one amber desk; the same in the playground's workbook.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
