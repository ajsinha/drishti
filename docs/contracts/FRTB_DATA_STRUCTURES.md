<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# FRTB and SIMM: the structure of the data

*Contract version 2.0 · 2026-11-02 · **Status:** design, not built. The `position-sensitivity` and `risk-factor` contracts are written
(`status: planned`) and their demo data is generated; the default-risk and residual-risk inputs and the computed records are drafts ·
Companion to [FRTB.md](FRTB.md) and [SIMM.md](SIMM.md) · Method: [SENSITIVITIES_AND_FRTB_SA.md](../architecture/SENSITIVITIES_AND_FRTB_SA.md)*

This page is the reference an upstream team builds its feed from and an auditor checks a record against: every field of every input,
the factor naming standard, the bucket grids, the parameter files' structure, the computed records, a worked JSON Lines record of each
kind, and the mapping from the formats banks already produce. [FRTB.md](FRTB.md) says how the data is delivered, checked and used.

Where a YAML contract exists it is the machine-readable truth and its generated page lists the same fields
([packs/market-risk.md](packs/market-risk.md#sens-position-sensitivities-position-sensitivity)); this page adds the meaning, the units
and the rules that tie the fields together.

## Contents

- [1. How to read this page](#1-how-to-read-this-page)
- [2. The factor naming standard](#2-the-factor-naming-standard)
- [3. The bucket grids](#3-the-bucket-grids)
- [4. position-sensitivity (SENS): every field](#4-position-sensitivity-sens-every-field)
- [5. risk-factor (RFAC): every field](#5-risk-factor-rfac-every-field)
- [6. Default risk (DRC) inputs, draft](#6-default-risk-drc-inputs-draft)
- [7. Residual risk (RRAO) inputs, draft](#7-residual-risk-rrao-inputs-draft)
- [8. The parameter files](#8-the-parameter-files)
- [9. Computed records](#9-computed-records)
- [10. Worked JSON Lines examples](#10-worked-json-lines-examples)
- [11. Mapping from upstream formats](#11-mapping-from-upstream-formats)

## 1. How to read this page

- **Types** are those of [README.md section 5](README.md#5-types-names-and-encodings): `id`, `text`, `date` (YYYY-MM-DD), `timestamp`
  (ISO 8601, UTC), `integer`, `amount` (a signed decimal in a currency), `rate` (a decimal), `boolean`, `record list`, `text list`, `id list`.
- **Required** means the record is refused without it; **optional** may be absent or null; **conditional** names its condition.
- **Files** are JSON Lines: UTF-8, one record per line, gzip allowed (`.jsonl.gz`); field names in UPPER_SNAKE as written here.
  Unknown fields are ignored (an additive change, [README.md section 13](README.md#13-versioning-a-contract)).
- **Signs:** every sensitivity is the position's gain for the stated up-move. **Amounts** are in the currency named beside them.

## 2. The factor naming standard

One text name per risk factor, slash-separated, the same in every system Drishti reads. A name without its last part (the tenor) names
the curve, issuer, commodity curve or spot as a whole: the curvature factor.

| Pattern | Factor | Example |
|---|---|---|
| `IR/CURVE/<ccy>/<curve>/<tenor>` | a vertex of an interest-rate curve | `IR/CURVE/USD/SOFR/10Y` |
| `IR/CURVE/<ccy>/<curve>` | the curve as a whole (curvature, gamma) | `IR/CURVE/USD/SOFR` |
| `INF/<index>/<tenor>` | an inflation curve vertex (the currency is the dictionary's `QUALIFIER`) | `INF/USCPI/10Y` |
| `CR/SPREAD/<issuer>/<BOND or CDS>/<tenor>` | a credit spread vertex | `CR/SPREAD/ISS-FIN-IG-01/CDS/5Y` |
| `CR/SPREAD/<issuer or index>` | the issuer or index as a whole (curvature) | `CR/SPREAD/CDX.NA.HY` |
| `FX/SPOT/<ccy>` | the currency's value in the reporting currency | `FX/SPOT/EUR` |
| `EQ/SPOT/<underlying>` | an equity price or index level | `EQ/SPOT/SPX` |
| `EQ/REPO/<name>` | an equity repo rate | `EQ/REPO/SPX` |
| `CM/PRICE/<commodity>/<location>/<tenor>` | a commodity forward | `CM/PRICE/WTI/CUSHING/1Y` |
| `CM/PRICE/<commodity>/<location>` | the commodity curve as a whole (curvature) | `CM/PRICE/BRENT/ICE` |
| `VOL/IR/<ccy>/<curve>/<expiry>/<underlying tenor>` | a swaption or cap volatility | `VOL/IR/USD/SOFR/1Y/10Y` |
| `VOL/<CR, EQ, CM or FX>/<underlying>/<expiry>` | an implied volatility | `VOL/EQ/SPX/1Y` |

**Parts.** `<ccy>` ISO 4217; `<curve>` the bank's curve code (`SOFR`, `ESTR`, `SONIA`, `TONA`, `SARON`, `AONIA` …); `<issuer>` an LEI in
production (the demo uses synthetic ids such as `ISS-FIN-IG-01`); `<underlying>` an ISIN or index code; `<commodity>` and `<location>` the
bank's commodity and delivery-point codes. **Tenors** are codes: `0D` (commodity spot), `2W`, `1M`, `3M`, `6M`, `1Y`, `2Y`, `3Y`, `5Y`,
`10Y`, `15Y`, `20Y`, `30Y`. A code maps to years for the correlations (`3M` 0.25, `6M` 0.5, `2W` 14/365, `1M` 1/12).

**The bank's own names** are listed per factor in the dictionary's `SOURCE_NAMES` (the demo: `MX:IR:CURVE-USD-SOFR-10Y` for Murex,
`IR.CURVE.USD.SOFR.10Y` for Calypso, `10Y_SOFR_USD_CURVE_IR` for Endur). A source may deliver either the standard name or one of its own;
a name in no list is refused.

## 3. The bucket grids

A **grid** maps each factor to one bucket. The FRTB grids are those of MAR21; the SIMM grid is that of the SIMM version
([SIMM.md section 6](SIMM.md#6-the-computation)); a bank may add its own (for example a DV01 ladder 0–2Y, 2–5Y, 5–10Y, 10Y+ per currency
for sensitivity limits). The values below are the Basel figures in the parameter file
[`frtb-sa-sbm.yaml`](../architecture/capital-parameters/frtb-sa-sbm.yaml); the file, not this page, is what the engine reads.

### 3.1 GIRR (MAR21.39 to MAR21.50)

Each currency is a bucket. Delta risk weights by vertex:

| Vertex | 3M | 6M | 1Y | 2Y | 3Y | 5Y | 10Y | 15Y | 20Y | 30Y | inflation | cross-currency basis |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RW | 1.7 % | 1.7 % | 1.6 % | 1.3 % | 1.2 % | 1.1 % | 1.1 % | 1.1 % | 1.1 % | 1.1 % | 1.6 % | 1.6 % |

Divided by √2 for EUR, USD, GBP, AUD, JPY, SEK, CAD and the reporting currency when the bank chooses it (MAR21.44). Within a currency:
the published table of MAR21.46 (by default) or `max(exp(−3 % · |T_k − T_l| / min(T_k, T_l)), 40 %)`; × 99.9 % between two curves at
the same vertex; inflation to a curve 40 %; basis to anything 0 %. Across currencies γ = 50 %.

### 3.2 CSR non-securitisation (MAR21.51 to MAR21.57)

| Bucket | Credit quality | Sector | RW |
|---|---|---|---|
| 1 | IG | sovereigns including central banks, multilateral development banks | 0.5 % |
| 2 | IG | local governments, government-backed non-financials, education, public administration | 1.0 % |
| 3 | IG | financials including government-backed financials | 5.0 % |
| 4 | IG | basic materials, energy, industrials, agriculture, manufacturing, mining and quarrying | 3.0 % |
| 5 | IG | consumer goods and services, transportation and storage, administrative and support services | 3.0 % |
| 6 | IG | technology, telecommunications | 2.0 % |
| 7 | IG | health care, utilities, professional and technical activities | 1.5 % |
| 8 | IG | covered bonds | 2.5 % |
| 9 to 15 | HY and not rated | the sectors of 1 to 7 | 2.0 %, 4.0 %, 12.0 %, 7.0 %, 8.5 %, 5.5 %, 5.0 % |
| 16 | any | other sector | 12.0 % |
| 17 | IG | indices | 1.5 % |
| 18 | HY | indices | 5.0 % |

Vertices 6M, 1Y, 3Y, 5Y, 10Y. Within a bucket ρ = ρ_name × ρ_tenor × ρ_basis: 35 % between names (80 % in buckets 17 and 18), 65 %
between tenors, 99.9 % between the bond and the CDS curve; bucket 16 adds absolute values. Across buckets γ = γ_rating × γ_sector:
γ_rating 50 % between an IG and a HY bucket of 1 to 15; γ_sector from MAR21.57's table; 0 % with bucket 16.

### 3.3 Equity (MAR21.71 to MAR21.80)

| Bucket | Size | Economy | Sector | Spot RW | Repo RW | ρ within |
|---|---|---|---|---|---|---|
| 1 | large | emerging | consumer goods and services, transportation and storage, administrative and support services, health care, utilities | 55 % | 0.55 % | 15 % |
| 2 | large | emerging | telecommunications, industrials | 60 % | 0.60 % | 15 % |
| 3 | large | emerging | basic materials, energy, agriculture, manufacturing, mining and quarrying | 45 % | 0.45 % | 15 % |
| 4 | large | emerging | financials including government-backed financials, real estate, technology | 55 % | 0.55 % | 15 % |
| 5 to 8 | large | advanced | as 1 to 4 | 30 %, 35 %, 40 %, 50 % | 0.30 % to 0.50 % | 25 % |
| 9 | small | emerging | all sectors of 1 to 4 | 70 % | 0.70 % | 7.5 % |
| 10 | small | advanced | all sectors of 5 to 8 | 50 % | 0.50 % | 12.5 % |
| 11 | | | other sector | 70 % | 0.70 % | absolute sum |
| 12 | large | advanced | indices (not sector-specific) | 15 % | 0.15 % | 80 % |
| 13 | | | other indices | 25 % | 0.25 % | 80 % |

Spot to repo of one name 99.9 %. Across buckets 15 %; 0 % with bucket 11; 75 % between 12 and 13; 45 % between 12 or 13 and 1 to 10.

### 3.4 Commodity (MAR21.81 to MAR21.85)

| Bucket | Group | RW | ρ_commodity |
|---|---|---|---|
| 1 | energy: solid combustibles | 30 % | 55 % |
| 2 | energy: liquid combustibles | 35 % | 95 % |
| 3 | energy: electricity and carbon trading | 60 % | 40 % |
| 4 | freight | 80 % | 80 % |
| 5 | metals: non-precious | 40 % | 60 % |
| 6 | gaseous combustibles | 45 % | 65 % |
| 7 | precious metals, including gold | 20 % | 55 % |
| 8 | grains and oilseed | 35 % | 45 % |
| 9 | livestock and dairy | 25 % | 15 % |
| 10 | softs and other agriculturals | 35 % | 40 % |
| 11 | other commodity | 50 % | 15 % |

ρ = ρ_commodity × ρ_tenor (99 %) × ρ_basis (99.9 % between locations). Across buckets 20 %; 0 % with bucket 11.

### 3.5 FX (MAR21.86 to MAR21.89)

Each currency against the reporting currency is a bucket; RW 15 % (÷ √2 for the specified pairs and their first-order crosses, MAR21.88,
listed in the bank's copy of the file); γ = 60 %.

### 3.6 Vega risk weights (MAR21.92)

`min(55 % · sqrt(LH / 10), 100 %)` with the liquidity horizon of the class: GIRR 60 days (100 %), CSR 120 (100 %), commodity 120 (100 %),
FX 40 (100 %), equity buckets 1 to 8, 12 and 13: 20 (77.78 %), equity buckets 9 to 11: 60 (100 %).

## 4. position-sensitivity (SENS): every field

Contract: [`position-sensitivity.contract.yaml`](../../config/packs/market-risk/contracts/position-sensitivity.contract.yaml). The record:

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `SENSITIVITY_ID` | id | required | `SENS-<POSITION_ID or TRADE_ID>-<BATCH_ID>`, unique per business date | `SENS-POS-BOOK-RATES-1~MX-20000002-B-SENS-2026-09-30-01` |
| `GRAIN` | text | required | `POSITION` or `TRADE`; one grain per source and date | `POSITION` |
| `POSITION_ID` | id | conditional: required for `POSITION` | `POS-<TT_BOOK>~<key>` as the pack's position rules derive it; on a `TRADE` record the trade's position when known | `POS-BOOK-RATES-1~MX-20000002` |
| `TRADE_ID` | id | conditional: required for `TRADE` | the trade (links to `trade`) | `MX-20000002` |
| `TRADE_IDS` | id list | optional | the trades a position record covers | `["MX-20000002"]` |
| `SOURCE_SYSTEM` | text | required | the system that computed the record | `MUREX` |
| `BUSINESS_DATE` | date | required | the date the sensitivities are for; the partition | `2026-09-30` |
| `BATCH_ID` | id | required | the run; one full run per source and date | `B-SENS-2026-09-30-01` |
| `RUN_KIND` | text | required | `FULL` or `INTRADAY` | `FULL` |
| `COMPUTED_AT` | timestamp | required | the as-of of the record; the latest wins | `2026-09-30T06:10:00Z` |
| `RESTATEMENT` | integer | required | 0 first delivery; 1, 2 … each restatement | `0` |
| `STATUS` | text | required | `ok`, `proxied` (with `PROXY_REASON`), `failed` (`SENSITIVITIES` null) | `ok` |
| `PROXY_REASON` | text | conditional: on `proxied` | the missing curve or surface and its proxy | `volatility surface missing on the date: the underlying's index surface used` |
| `PRICER`, `MODEL_VERSION` | text | optional | the pricing library and model version | `demo-factor-model`, `demo-sens-1` |
| `LEGAL_ENTITY` | id | required | the booking legal entity | `LE-NY` |
| `DIVISION`, `DESK` | id | required | the management hierarchy | `DIV-MARKETS`, `DESK-RATES` |
| `TT_BOOK` | id | required | the trading book | `BOOK-RATES-1` |
| `LIMIT_LETTER` | id | optional | the limit letter of authority | `LL-0001` |
| `FRTB_DESK` | id | optional | the regulatory trading desk, when it differs from the management desk | `FD-USD-RATES` |
| `PRODUCT_GROUP`, `PRODUCT_TYPE` | text | required | the product; `PRODUCT_TYPE` decides the RRAO class and the SIMM product class mapping | `Rates`, `IRS_FIXFLOAT` |
| `TRADE_STATUS` | text | required | the trades' status; `CANCELLED` carries an empty list | `NEW` |
| `MATURITY_DATE` | date | required | the position's last maturity | `2031-04-15` |
| `CURRENCY` | text | required | the position's own currency | `EUR` |
| `SENSITIVITIES` | record list | required | the elements below; empty only when cancelled, null when failed | |

Each element of `SENSITIVITIES[]`:

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `RISK_FACTOR` | id | required | the factor's standard name or a source name in the dictionary (section 2) | `IR/CURVE/USD/SOFR/10Y` |
| `MEASURE` | text | required | `DV01`, `CS01`, `IE01`, `DELTA`, `VEGA`, `CURVATURE_UP`, `CURVATURE_DOWN`, `GAMMA`; `BETA` and `BASE_CORR` reserved for SIMM | `DV01` |
| `VALUE` | amount | required | the change in value for the measure's move, in `CURRENCY`, gain positive; finite | `-19565.62` |
| `CURRENCY` | text | required | the currency of `VALUE` | `USD` |
| `RISK_CLASS` | text | optional | `GIRR`, `CSR_NS`, `CSR_SEC_NCTP`, `CSR_SEC_CTP`, `EQ`, `COMM`, `FX`; the dictionary's wins | `GIRR` |
| `FRTB_BUCKET` | text | optional | the source's FRTB bucket; the dictionary's wins | `USD` |
| `TENOR` | text | optional | the vertex or the option expiry; null on a curvature or spot factor | `10Y` |
| `SIMM_BUCKET` | text | optional | the source's SIMM bucket; the dictionary's wins | `1` |
| `SHOCK_SIZE` | rate | conditional: on `CURVATURE_UP` and `CURVATURE_DOWN` | bp for rates and spreads; a fraction for spot and price | `170` |
| `IMPLIED_VOL` | rate | conditional: on `VEGA` | the implied volatility, a fraction | `0.28` |

**Units of `VALUE` by measure:** [FRTB.md section 3.2.2](FRTB.md#322-units-and-signs). **What each class sends:**
[FRTB.md section 3.2.4](FRTB.md#324-what-each-risk-class-must-deliver).

## 5. risk-factor (RFAC): every field

Contract: [`risk-factor.contract.yaml`](../../config/packs/market-risk/contracts/risk-factor.contract.yaml). One row per factor and
delivery date; a date without a delivery keeps the last; a row applies from `VALID_FROM` to `VALID_TO`.

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `RISK_FACTOR` | id | required | the standard name (section 2); the key | `IR/CURVE/USD/SOFR/10Y` |
| `FACTOR_TYPE` | text | required | `CURVE`, `INFLATION`, `SPREAD`, `SPOT`, `REPO`, `PRICE`, `VOL` | `CURVE` |
| `RISK_CLASS` | text | required | the FRTB class; a volatility's is its underlying's | `GIRR` |
| `QUALIFIER` | text | required | the currency (GIRR, FX), issuer or index (credit), underlying (equity, commodity) | `USD` |
| `CURVE` | text | optional | the rate curve, `BOND` or `CDS`, the commodity location, or the inflation index | `SOFR` |
| `UNDERLYING` | text | optional | the underlying of an equity, commodity, FX or volatility factor | `SPX` |
| `TENOR` | text | optional | the vertex or the option expiry; null for a spot or a whole curve | `10Y` |
| `UNDERLYING_TENOR` | text | optional | the underlying swap's tenor of a rates volatility | `10Y` |
| `FRTB_BUCKET` | text | required | the currency (GIRR, FX), 1 to 18 (CSR), 1 to 13 (equity), 1 to 11 (commodity) | `USD` |
| `SIMM_RISK_CLASS` | text | required | `InterestRate`, `CreditQ`, `CreditNonQ`, `Equity`, `Commodity`, `FX` | `InterestRate` |
| `SIMM_BUCKET` | text | required | the SIMM bucket; `Residual` when none applies | `1` |
| `SIMM_LABEL1` | text | optional | SIMM's tenor (`2w` … `30y`) or expiry; `Inf` for inflation | `10y` |
| `SIMM_LABEL2` | text | optional | the rate sub-curve (`OIS`, `Libor3m`, `Prime`) or the credit instrument | `OIS` |
| `SECTOR` | text | optional | the issuer's or name's sector, as the grids use it | `Financials including government-backed financials` |
| `CREDIT_QUALITY` | text | optional | `IG` or `HY` (credit) | `IG` |
| `MARKET_CAP`, `ECONOMY` | text | optional | `LARGE` or `SMALL`; `ADVANCED` or `EMERGING` (equity) | `LARGE`, `ADVANCED` |
| `LIQUIDITY_HORIZON` | integer | required | 10, 20, 40, 60 or 120 business days (MAR33.12, the internal models approach) | `10` |
| `MODELLABLE` | boolean | optional | passes the risk factor eligibility test (internal models) | `true` |
| `PROXY_OF` | id | optional | the factor whose data proxies this one | `VOL/EQ/MXEF/10Y` |
| `MARKET_DATA_REF` | text | optional | where the level comes from in the market data | `CRV-USD-SOFR#10Y` |
| `SOURCE_NAMES` | text list | required | the source systems' names; each maps to this factor only | `["MX:IR:CURVE-USD-SOFR-10Y"]` |
| `VALID_FROM`, `VALID_TO` | date | `VALID_FROM` required | the dates the row applies to | `2026-01-02` |
| `BUSINESS_DATE` | date | required | the delivery's date; the partition | `2026-09-30` |
| `SOURCE` | text | required | the system the row came from | `demo-factor-master` |

## 6. Default risk (DRC) inputs, draft

Not yet a YAML contract ([FRTB.md section 3.3](FRTB.md#33-default-risk-the-drc-inputs-optional)). Path
`<root>/risk/default-risk/`; one row per position (or trade), obligor and business date.

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `DEFAULT_RISK_ID` | id | required | `DRC-<POSITION_ID or TRADE_ID>-<OBLIGOR>-<BATCH_ID>` | `DRC-POS-BOOK-CREDIT-1~CLY-3000087-5493001KJTIIGC8Y1R12-B-DRC-2026-09-30-01` |
| `GRAIN`, `POSITION_ID`, `TRADE_ID` | text, id | as SENS | the position or trade | `POSITION` |
| `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND`, `COMPUTED_AT`, `RESTATEMENT`, `STATUS` | as SENS | required | the run (FRTB.md section 3.6 applies) | |
| `LEGAL_ENTITY`, `DESK`, `TT_BOOK`, `FRTB_DESK` | id | as SENS | placement | `LE-NY` |
| `OBLIGOR` | id | required | the issuer or reference entity, LEI preferred | `5493001KJTIIGC8Y1R12` |
| `DRC_BUCKET` | text | required | `CORPORATE`, `SOVEREIGN`, `LOCAL_GOVERNMENT_MUNICIPALITY` | `CORPORATE` |
| `SENIORITY` | text | required | `COVERED_BOND`, `SENIOR`, `NON_SENIOR`, `EQUITY` | `SENIOR` |
| `CREDIT_QUALITY` | text | required | `AAA`, `AA`, `A`, `BBB`, `BB`, `B`, `CCC`, `UNRATED`, `DEFAULTED` | `BBB` |
| `NOTIONAL` | amount | required | bond-equivalent notional; positive long (loses on default), negative short | `10000000` |
| `MARKET_VALUE` | amount | required | bond-equivalent market value, signed as the notional | `9800000` |
| `CURRENCY` | text | required | of the two amounts | `USD` |
| `MATURITY_DATE` | date | conditional: not for cash equity | the instrument's maturity | `2028-09-30` |
| `CASH_EQUITY_MATURITY` | rate | conditional: cash equity | `0.25` or `1` (years) | `1` |
| `RECOVERY_INDEPENDENT` | boolean | optional | the price does not depend on recovery: no LGD | `false` |
| `INSTRUMENT` | text | optional | what the row is (for the auditor) | `bond` |

**Signs, checked on load:** a bought CDS has a negative `NOTIONAL`; a sold put on a bond a positive one; `MARKET_VALUE` has the
notional's sign. A row whose `CREDIT_QUALITY` is `DEFAULTED` takes the 100 % weight whatever its seniority.

## 7. Residual risk (RRAO) inputs, draft

Not yet a YAML contract ([FRTB.md section 3.4](FRTB.md#34-residual-risk-the-rrao-inputs-optional)). By default the RRAO is derived from
`PRODUCT_TYPE` and the trade master's notional; this optional table, `<root>/risk/residual-risk/`, carries overrides and the flags no
master holds. One row per trade and business date.

| Field | Type | Presence | Meaning | Example |
|---|---|---|---|---|
| `RESIDUAL_RISK_ID` | id | required | `RRAO-<TRADE_ID>-<BATCH_ID>` | `RRAO-MX-20000311-B-RRAO-2026-09-30-01` |
| `TRADE_ID`, `POSITION_ID` | id | `TRADE_ID` required | the trade and its position | `MX-20000311` |
| `SOURCE_SYSTEM`, `BUSINESS_DATE`, `BATCH_ID`, `COMPUTED_AT` | as SENS | required | the run | |
| `RRAO_CLASS` | text | required | `EXOTIC` (MAR23.3), `OTHER` (MAR23.4, MAR23.5) or `NONE`: overrides the product-type mapping | `OTHER` |
| `RISK_TYPE` | text | optional | `longevity`, `weather`, `natural-disaster`, `future-realised-volatility`, `gap`, `correlation`, `behavioural` | `gap` |
| `GROSS_NOTIONAL` | amount | required | the gross notional, absolute | `50000000` |
| `CURRENCY` | text | required | of the notional | `USD` |
| `BACK_TO_BACK_OF` | id | optional | the trade it exactly matches with a third party: both excluded (MAR23.7) | `MX-20000312` |
| `LISTED_OR_CLEARABLE` | boolean | optional | listed or eligible for central clearing: excluded from the other-residual part only | `false` |
| `REASON` | text | optional | why the trade is in scope | `knock-out barrier` |

## 8. The parameter files

| File | Top-level keys | Per class |
|---|---|---|
| [`frtb-sa-sbm.yaml`](../architecture/capital-parameters/frtb-sa-sbm.yaml) | `regime`, `source`, `scenarios` (`medium`, `high`, `low` formulas) | `GIRR`, `CSR_NS`, `EQ`, `COMM`, `FX`, each with `delta` (`tenors`, `risk-weights`, `within-bucket`, `across-buckets`), `vega` (`risk-weight`, correlations) and `curvature` (`factor`, `shock`, `correlation`; FX `divisor`, `divisor-all-fx-instruments`) |
| [`frtb-sa-drc-rrao.yaml`](../architecture/capital-parameters/frtb-sa-drc-rrao.yaml) | `regime`, `sources` | `drc` (`lgd`, `maturity`, `buckets`, `risk-weights`, `netting`, `hedge-benefit-ratio`, `bucket-charge`, `total`); `rrao` (`exotic-underlying-weight`, `other-residual-risk-weight`, `exotic-underlyings`, `other-residual-risks`, `exclusions`, `product-types`) |
| [`simm.yaml`](../architecture/capital-parameters/simm.yaml) | `version`, `effective-from`, `source`, `holding-period-days`, `product-classes`, `risk-classes`, `risk-class-correlation`, `curvature`, `add-ons` | `InterestRate`, `CreditQ`, `CreditNonQ`, `Equity`, `Commodity`, `FX`: risk weights, correlations, concentration thresholds, HVR; every number `null` as shipped |

Every value names its paragraph in a comment. The bank's copy of each file is the one the engine reads; its SHA-256 identifies it in
every answer, recipe and capture.

## 9. Computed records

Drafts: the names are fixed with the engine, the content is the contract.

### 9.1 `frtb-sa-result`: one record per scope and business date

| Field | Type | Meaning |
|---|---|---|
| `SCOPE`, `CANONICAL`, `BUSINESS_DATE`, `FILER` | text, date | what was computed |
| `TOTAL`, `SBM`, `DRC`, `RRAO` | amount | in the reporting currency; `DRC` and `RRAO` null with `NOT_COMPUTED` when their inputs are missing |
| `SBM_LOW`, `SBM_MEDIUM`, `SBM_HIGH`, `BINDING_SCENARIO` | amount, text | the three scenario totals and the largest |
| `BY_CLASS[]` | record list | `RISK_CLASS`, `MEASURE` (`DELTA`, `VEGA`, `CURVATURE`), `SCENARIO`, `CHARGE`, `ALTERNATIVE_SPECIFICATION` (boolean), `ESTIMATED` (boolean: curvature from gamma) |
| `BUCKETS[]` | record list | `RISK_CLASS`, `MEASURE`, `SCENARIO`, `BUCKET`, `K`, `S`, `S_CAPPED`, `FACTORS`; curvature: `K_UP`, `K_DOWN`, `SIDE` |
| `FACTORS[]` | record list | `RISK_CLASS`, `MEASURE`, `BUCKET`, `RISK_FACTOR`, `S` (netted, regulatory unit), `RW`, `WS`, `GRADIENT`, `CONTRIBUTION`; curvature: `CVR_UP`, `CVR_DOWN` |
| `DRC_BUCKETS[]` | record list | `DRC_BUCKET`, `NET_LONG`, `NET_SHORT`, `WEIGHTED_LONG`, `WEIGHTED_SHORT`, `HBR`, `CHARGE` |
| `DRC_OBLIGORS[]` | record list | `OBLIGOR`, `DRC_BUCKET`, `CREDIT_QUALITY`, `JTD_LONG`, `JTD_SHORT` (scaled), `NET`, `RW` |
| `RRAO_LINES[]` | record list | `TRADE_ID`, `RRAO_CLASS`, `GROSS_NOTIONAL`, `WEIGHT`, `ADD_ON`, `EXCLUSION` |
| `SWITCHES`, `OVERRIDES[]`, `REGULATORY` | record, record list, boolean | the switches used; any override; false when either is not the default |
| `NOT_COMPUTED[]` | text list | the parts and classes not computed, with the reason |
| `PROVENANCE` | record | `INPUTS[]` (table, format, version, batches), `DICTIONARY_VERSION`, `PARAMETERS[]` (file, regime, version, SHA-256), `REFUSED` and `PROXIED` counts per source, `MISSING_POSITIONS`, `ENGINE_VERSION`, `COMPUTED_AT` |

### 9.2 Contributions

One row per part: `PART`, `STANDALONE_SBM`, `CONTRIBUTION`, `SHARE`, `INCREMENTAL` (the first 50 parts), `SCALED` (true when the
gradient was one-sided and the contributions were scaled to add up).

### 9.3 Playground recipe

| Field | Type | Meaning |
|---|---|---|
| `RECIPE_ID` | id | `RCP-FRTB-<yyyymmdd>-<n>` or `RCP-SIMM-…` |
| `KIND` | text | `FRTB-SA` or `SIMM` |
| `CREATED_BY`, `CREATED_AT` | text, timestamp | |
| `SCOPE`, `BUSINESS_DATE`, `FILER` or `NETTING_SET` | text, date | |
| `INPUTS[]` | record list | `TABLE`, `FORMAT` (`delta`, `iceberg`), `VERSION`, `BATCH_IDS[]` |
| `PARAMETERS[]` | record list | `FILE`, `REGIME` or `VERSION`, `SHA256` |
| `SWITCHES` | record | each switch with its value |
| `OVERRIDES[]` | record list | `PATH`, `VALUE`, `BY`, `REASON` |
| `ENGINE_VERSION` | text | |
| `RESULT_DIGEST` | text | SHA-256 of the figures, for the byte-equal replay |

### 9.4 Official capture

The record of section 9.1 for each filer and date, with `CAPTURE_ID`, `CAPTURE_VERSION` (a restatement adds a version) and
`CAPTURED_BY`; `REGULATORY` is always true (default switches only, no override).

## 10. Worked JSON Lines examples

The records of [FRTB.md section 15](FRTB.md#15-a-worked-example), as an upstream team would send them, then one record of each special
case. Each line is one record (wrapped here only by the code block's width).

**position-sensitivity: the worked example's three positions.** The USD rates position of cases 1 and 2:

```json
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-20000101-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000101","TRADE_IDS":["MX-20000101"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","FRTB_DESK":"FD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","TRADE_STATUS":"NEW","MATURITY_DATE":"2036-09-30","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","MEASURE":"DV01","VALUE":-1000.0,"CURRENCY":"USD","RISK_CLASS":"GIRR","FRTB_BUCKET":"USD","TENOR":"5Y","SIMM_BUCKET":"1"},{"RISK_FACTOR":"IR/CURVE/USD/SOFR/10Y","MEASURE":"DV01","VALUE":-500.0,"CURRENCY":"USD","RISK_CLASS":"GIRR","FRTB_BUCKET":"USD","TENOR":"10Y","SIMM_BUCKET":"1"}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-2~MX-20000102-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-2~MX-20000102","TRADE_IDS":["MX-20000102"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-2","FRTB_DESK":"FD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","TRADE_STATUS":"NEW","MATURITY_DATE":"2028-09-30","CURRENCY":"EUR","SENSITIVITIES":[{"RISK_FACTOR":"IR/CURVE/EUR/ESTR/2Y","MEASURE":"DV01","VALUE":800.0,"CURRENCY":"USD","RISK_CLASS":"GIRR","FRTB_BUCKET":"EUR","TENOR":"2Y","SIMM_BUCKET":"1"}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-EQ-1~MX-20000103-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-EQ-1~MX-20000103","TRADE_IDS":["MX-20000103"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-EQUITY","TT_BOOK":"BOOK-EQ-1","FRTB_DESK":"FD-EQUITY","PRODUCT_GROUP":"Equity","PRODUCT_TYPE":"EQ_INDEX_OPTION","TRADE_STATUS":"NEW","MATURITY_DATE":"2027-09-17","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"EQ/SPOT/SPX","MEASURE":"DELTA","VALUE":10000.0,"CURRENCY":"USD","RISK_CLASS":"EQ","FRTB_BUCKET":"12","TENOR":null,"SIMM_BUCKET":"11"},{"RISK_FACTOR":"EQ/SPOT/SPX","MEASURE":"CURVATURE_UP","VALUE":120000.0,"CURRENCY":"USD","RISK_CLASS":"EQ","FRTB_BUCKET":"12","TENOR":null,"SHOCK_SIZE":0.15},{"RISK_FACTOR":"EQ/SPOT/SPX","MEASURE":"CURVATURE_DOWN","VALUE":-180000.0,"CURRENCY":"USD","RISK_CLASS":"EQ","FRTB_BUCKET":"12","TENOR":null,"SHOCK_SIZE":0.15}]}
```

**position-sensitivity: special cases.** A vega element (an option with its implied volatility); a source that cannot revalue sending
`GAMMA` (case 10: the curvature is then estimated, `CVR± ≈ −½ · 200 · 15² = 22,500`); a trade-grain record from Endur; a failed record;
an intraday replacement of the first position (only its 10Y changed: the whole record is sent again):

```json
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-20000104-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000104","TRADE_IDS":["MX-20000104"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"SWAPTION","TRADE_STATUS":"NEW","MATURITY_DATE":"2037-09-30","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"VOL/IR/USD/SOFR/1Y/10Y","MEASURE":"VEGA","VALUE":40657.16,"CURRENCY":"USD","TENOR":"1Y","IMPLIED_VOL":0.28}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-EQ-2~CLY-4000201-B-SENS-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-EQ-2~CLY-4000201","TRADE_IDS":["CLY-4000201"],"SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-02","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-EQUITY","TT_BOOK":"BOOK-EQ-2","PRODUCT_GROUP":"Equity","PRODUCT_TYPE":"EQ_INDEX_OPTION","TRADE_STATUS":"NEW","MATURITY_DATE":"2027-09-17","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"EQ.SPOT.SPX","MEASURE":"DELTA","VALUE":10000.0,"CURRENCY":"USD"},{"RISK_FACTOR":"EQ.SPOT.SPX","MEASURE":"GAMMA","VALUE":-200.0,"CURRENCY":"USD"}]}
{"SENSITIVITY_ID":"SENS-1000019-B-SENS-2026-09-30-03","GRAIN":"TRADE","POSITION_ID":"POS-BOOK-COMM-1~1000019","TRADE_ID":"1000019","TRADE_IDS":["1000019"],"SOURCE_SYSTEM":"ENDUR","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-03","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:10:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-COMM","TT_BOOK":"BOOK-COMM-1","PRODUCT_GROUP":"Commodity","PRODUCT_TYPE":"CallNot","TRADE_STATUS":"AMENDED","MATURITY_DATE":"2028-02-29","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"CM/PRICE/BRENT/ICE/1Y","MEASURE":"DELTA","VALUE":-49726.79,"CURRENCY":"USD","TENOR":"1Y"},{"RISK_FACTOR":"CM/PRICE/BRENT/ICE","MEASURE":"CURVATURE_UP","VALUE":-2066410.39,"CURRENCY":"USD","SHOCK_SIZE":0.35},{"RISK_FACTOR":"CM/PRICE/BRENT/ICE","MEASURE":"CURVATURE_DOWN","VALUE":3888576.98,"CURRENCY":"USD","SHOCK_SIZE":0.35},{"RISK_FACTOR":"VOL/CM/BRENT/1Y","MEASURE":"VEGA","VALUE":-63654.63,"CURRENCY":"USD","TENOR":"1Y","IMPLIED_VOL":0.32}]}
{"SENSITIVITY_ID":"SENS-POS-BOOK-CREDIT-1~CLY-3000090-B-SENS-2026-09-30-02","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000090","TRADE_IDS":["CLY-3000090"],"SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-02","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"failed","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","PRODUCT_GROUP":"Credit","PRODUCT_TYPE":"CDS_SINGLE","TRADE_STATUS":"NEW","MATURITY_DATE":"2031-12-20","CURRENCY":"USD","SENSITIVITIES":null}
{"SENSITIVITY_ID":"SENS-POS-BOOK-RATES-1~MX-20000101-B-SENS-2026-09-30-11","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-RATES-1~MX-20000101","TRADE_IDS":["MX-20000101"],"SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-SENS-2026-09-30-11","RUN_KIND":"INTRADAY","COMPUTED_AT":"2026-09-30T14:20:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DIVISION":"DIV-MARKETS","DESK":"DESK-RATES","TT_BOOK":"BOOK-RATES-1","FRTB_DESK":"FD-RATES","PRODUCT_GROUP":"Rates","PRODUCT_TYPE":"IRS_FIXFLOAT","TRADE_STATUS":"AMENDED","MATURITY_DATE":"2036-09-30","CURRENCY":"USD","SENSITIVITIES":[{"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","MEASURE":"DV01","VALUE":-1000.0,"CURRENCY":"USD"},{"RISK_FACTOR":"IR/CURVE/USD/SOFR/10Y","MEASURE":"DV01","VALUE":-650.0,"CURRENCY":"USD"}]}
```

The Calypso record uses the source's own names (`EQ.SPOT.SPX`), resolved through `SOURCE_NAMES`. A restatement of the date would send
the whole Murex full run again with `RESTATEMENT` 1, a new `BATCH_ID` and a later `COMPUTED_AT`.

**risk-factor: the factors of the example.**

```json
{"RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","FACTOR_TYPE":"CURVE","RISK_CLASS":"GIRR","QUALIFIER":"USD","CURVE":"SOFR","TENOR":"5Y","FRTB_BUCKET":"USD","SIMM_RISK_CLASS":"InterestRate","SIMM_BUCKET":"1","SIMM_LABEL1":"5y","SIMM_LABEL2":"OIS","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"MARKET_DATA_REF":"CRV-USD-SOFR#5Y","SOURCE_NAMES":["MX:IR:CURVE-USD-SOFR-5Y","IR.CURVE.USD.SOFR.5Y","5Y_SOFR_USD_CURVE_IR"],"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30","SOURCE":"factor-master"}
{"RISK_FACTOR":"IR/CURVE/EUR/ESTR/2Y","FACTOR_TYPE":"CURVE","RISK_CLASS":"GIRR","QUALIFIER":"EUR","CURVE":"ESTR","TENOR":"2Y","FRTB_BUCKET":"EUR","SIMM_RISK_CLASS":"InterestRate","SIMM_BUCKET":"1","SIMM_LABEL1":"2y","SIMM_LABEL2":"OIS","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"MARKET_DATA_REF":"CRV-EUR-ESTR#2Y","SOURCE_NAMES":["MX:IR:CURVE-EUR-ESTR-2Y","IR.CURVE.EUR.ESTR.2Y","2Y_ESTR_EUR_CURVE_IR"],"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30","SOURCE":"factor-master"}
{"RISK_FACTOR":"EQ/SPOT/SPX","FACTOR_TYPE":"SPOT","RISK_CLASS":"EQ","QUALIFIER":"SPX","UNDERLYING":"SPX","FRTB_BUCKET":"12","SIMM_RISK_CLASS":"Equity","SIMM_BUCKET":"11","SECTOR":"Index","MARKET_CAP":"LARGE","ECONOMY":"ADVANCED","LIQUIDITY_HORIZON":10,"MODELLABLE":true,"MARKET_DATA_REF":"EQ-SPX","SOURCE_NAMES":["MX:EQ:SPOT-SPX","EQ.SPOT.SPX","SPX_SPOT_EQ"],"VALID_FROM":"2026-01-02","BUSINESS_DATE":"2026-09-30","SOURCE":"factor-master"}
```

**default-risk: case 4.** The long BBB bond and the bought CDS on obligor A, and obligor B's net short:

```json
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000101-LEI-OBLIGOR-A-B-DRC-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000101","SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-DRC-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","OBLIGOR":"LEI-OBLIGOR-A","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BBB","NOTIONAL":10000000,"MARKET_VALUE":9800000,"CURRENCY":"USD","MATURITY_DATE":"2028-09-30","INSTRUMENT":"bond"}
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000102-LEI-OBLIGOR-A-B-DRC-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000102","SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-DRC-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","OBLIGOR":"LEI-OBLIGOR-A","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BBB","NOTIONAL":-4000000,"MARKET_VALUE":-3950000,"CURRENCY":"USD","MATURITY_DATE":"2027-03-31","INSTRUMENT":"bought CDS"}
{"DEFAULT_RISK_ID":"DRC-POS-BOOK-CREDIT-1~CLY-3000103-LEI-OBLIGOR-B-B-DRC-2026-09-30-01","GRAIN":"POSITION","POSITION_ID":"POS-BOOK-CREDIT-1~CLY-3000103","SOURCE_SYSTEM":"CALYPSO","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-DRC-2026-09-30-01","RUN_KIND":"FULL","COMPUTED_AT":"2026-09-30T06:05:00Z","RESTATEMENT":0,"STATUS":"ok","LEGAL_ENTITY":"LE-NY","DESK":"DESK-CREDIT","TT_BOOK":"BOOK-CREDIT-1","OBLIGOR":"LEI-OBLIGOR-B","DRC_BUCKET":"CORPORATE","SENIORITY":"SENIOR","CREDIT_QUALITY":"BB","NOTIONAL":-2500000,"MARKET_VALUE":-2625000,"CURRENCY":"USD","MATURITY_DATE":"2029-09-30","INSTRUMENT":"bought CDS"}
```

Obligor B: JTD = 0.75 · −2,500,000 + (−2,625,000 + 2,500,000) = −2,000,000, maturity over a year so scaled × 1: the net short of case 4.

**residual-risk: case 5.**

```json
{"RESIDUAL_RISK_ID":"RRAO-MX-20000311-B-RRAO-2026-09-30-01","TRADE_ID":"MX-20000311","SOURCE_SYSTEM":"MUREX","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-RRAO-2026-09-30-01","COMPUTED_AT":"2026-09-30T06:10:00Z","RRAO_CLASS":"OTHER","RISK_TYPE":"gap","GROSS_NOTIONAL":50000000,"CURRENCY":"USD","LISTED_OR_CLEARABLE":false,"REASON":"FX knock-out barrier"}
{"RESIDUAL_RISK_ID":"RRAO-1000420-B-RRAO-2026-09-30-01","TRADE_ID":"1000420","SOURCE_SYSTEM":"ENDUR","BUSINESS_DATE":"2026-09-30","BATCH_ID":"B-RRAO-2026-09-30-01","COMPUTED_AT":"2026-09-30T06:10:00Z","RRAO_CLASS":"EXOTIC","RISK_TYPE":"weather","GROSS_NOTIONAL":10000000,"CURRENCY":"USD","REASON":"heating degree day swap"}
```

**capital-input: the bank's own DRC beside Drishti's.**

```json
{"CAPITAL_INPUT_ID":"CAPI-LE-NY-OSFI-CAR9-2026-09-30","BUSINESS_DATE":"2026-09-30","FILER":"LE-NY","REGIME":"OSFI-CAR9","DEFAULT_RISK_CHARGE":126000,"CURRENCY":"USD","APPROVED_BY":"head-of-regulatory-capital","SOURCE":"capital-engine","AS_OF":"2026-09-30T23:00:00Z"}
```

**frtb-sa-result: the example's answer**, with the default switches and, as in the worked example, without the index's own delta
charge (abbreviated: the factor and bucket tables carry one row each here):

```json
{"SCOPE":"LE:LE-NY","CANONICAL":"LE:LE-NY","BUSINESS_DATE":"2026-09-30","FILER":"LE-NY","TOTAL":416732.74,"SBM":140555.42,"DRC":126177.32,"RRAO":150000.0,"SBM_LOW":140555.42,"SBM_MEDIUM":131286.72,"SBM_HIGH":121079.64,"BINDING_SCENARIO":"low","BY_CLASS":[{"RISK_CLASS":"GIRR","MEASURE":"DELTA","SCENARIO":"low","CHARGE":110555.42,"ALTERNATIVE_SPECIFICATION":false,"ESTIMATED":false},{"RISK_CLASS":"EQ","MEASURE":"CURVATURE","SCENARIO":"low","CHARGE":30000.0,"ALTERNATIVE_SPECIFICATION":false,"ESTIMATED":false}],"BUCKETS":[{"RISK_CLASS":"GIRR","MEASURE":"DELTA","SCENARIO":"low","BUCKET":"USD","K":115106.47,"S":-116672.62,"S_CAPPED":null,"FACTORS":2}],"FACTORS":[{"RISK_CLASS":"GIRR","MEASURE":"DELTA","BUCKET":"USD","RISK_FACTOR":"IR/CURVE/USD/SOFR/5Y","S":-10000000.0,"RW":0.00777817,"WS":-77781.75}],"SWITCHES":{"curvature-source":"revaluation","girr-curvature-shock":"1.7%","fx-curvature-divisor":"options-not-referencing-reporting-currency","girr-correlation":"published-table","scenario":"binding"},"OVERRIDES":[],"REGULATORY":true,"NOT_COMPUTED":[],"PROVENANCE":{"INPUTS":[{"TABLE":"position-sensitivity","FORMAT":"delta","VERSION":412,"BATCH_IDS":["B-SENS-2026-09-30-01"]},{"TABLE":"default-risk","FORMAT":"delta","VERSION":37,"BATCH_IDS":["B-DRC-2026-09-30-01"]}],"DICTIONARY_VERSION":58,"PARAMETERS":[{"FILE":"frtb-sa-sbm.yaml","REGIME":"BCBS-MAR21","SHA256":"<sha-256 of the file>"}],"ENGINE_VERSION":"<engine version>"}}
```

**A recipe.**

```json
{"RECIPE_ID":"RCP-FRTB-20261002-1","KIND":"FRTB-SA","CREATED_BY":"validator.mr","CREATED_AT":"2026-10-02T15:04:00Z","SCOPE":"LE:LE-NY","BUSINESS_DATE":"2026-09-30","FILER":"LE-NY","INPUTS":[{"TABLE":"position-sensitivity","FORMAT":"delta","VERSION":412,"BATCH_IDS":["B-SENS-2026-09-30-01","B-SENS-2026-09-30-11"]},{"TABLE":"risk-factor","FORMAT":"delta","VERSION":58,"BATCH_IDS":[]}],"PARAMETERS":[{"FILE":"frtb-sa-sbm.yaml","REGIME":"BCBS-MAR21","SHA256":"<sha-256>"},{"FILE":"frtb-sa-drc-rrao.yaml","REGIME":"BCBS-MAR22-23","SHA256":"<sha-256>"}],"SWITCHES":{"curvature-source":"revaluation","girr-curvature-shock":"1.7%","fx-curvature-divisor":"options-not-referencing-reporting-currency","girr-correlation":"exact-formula","scenario":"binding"},"OVERRIDES":[],"ENGINE_VERSION":"<engine version>","RESULT_DIGEST":"<sha-256 of the figures>"}
```

## 11. Mapping from upstream formats

### 11.1 From ISDA CRIF rows

Many banks already produce the Common Risk Interchange Format for SIMM. A CRIF file carries delta and vega at SIMM's grid; it does not
carry FRTB curvature, the commodity tenors, or the record-level fields of SENS, so a CRIF feed alone covers SIMM and most FRTB delta and
vega, and FRTB curvature needs `CURVATURE_UP` and `CURVATURE_DOWN` (or `GAMMA`) from the same source. The loader groups the CRIF lines of
one trade into one `GRAIN` `TRADE` record:

| CRIF field | SENS field | Rule |
|---|---|---|
| `TradeID` | `TRADE_ID`, `SENSITIVITY_ID` | `GRAIN` `TRADE` |
| `PortfolioID` | not on SENS | the netting set comes from the netting-set master; kept for the SIMM reconciliation |
| `ProductClass` | not on SENS | checked against the product-class mapping of `PRODUCT_GROUP` ([SIMM.md](SIMM.md#62-product-classes)) |
| `RiskType`, `Qualifier`, `Label1`, `Label2` | `SENSITIVITIES[].RISK_FACTOR`, `MEASURE` | the table below; `Label1` upper-cased to a tenor code (`10y` → `10Y`); `Label2` through the bank's sub-curve map (`OIS` → the currency's curve) |
| `Bucket` | `SENSITIVITIES[].SIMM_BUCKET` | a difference from the dictionary is a warning |
| `Amount`, `AmountCurrency` | `VALUE`, `CURRENCY` | as is for delta (CRIF's sign is the same: gain for the up-move) |
| `AmountUSD` | none | checked against `Amount` at the date's FX |
| `CollectRegulations`, `PostRegulations` | not on SENS | the regimes come from the CSA ([SIMM.md](SIMM.md#4-reference-and-master-data-read)) |
| `EndDate` | `MATURITY_DATE` | the trade's |
| (not in CRIF) | `BUSINESS_DATE`, `BATCH_ID`, `RUN_KIND`, `COMPUTED_AT`, `SOURCE_SYSTEM` | from the file's load configuration |
| (not in CRIF) | placement, `PRODUCT_GROUP`, `PRODUCT_TYPE`, `TRADE_STATUS`, `CURRENCY` | from the trade master |

| CRIF `RiskType` | `RISK_FACTOR` | `MEASURE` |
|---|---|---|
| `Risk_IRCurve` | `IR/CURVE/<Qualifier>/<curve>/<Label1>` | `DV01` |
| `Risk_Inflation` | the currency's inflation factor (CRIF has no tenor: one element per currency) | `IE01` |
| `Risk_XCcyBasis` | no name in the standard yet ([FRTB.md section 16](FRTB.md#16-open-questions-and-decisions)) | `DV01` |
| `Risk_CreditQ` | `CR/SPREAD/<issuer>/<BOND or CDS>/<Label1>` | `CS01` |
| `Risk_CreditNonQ` | `CR/SPREAD/<tranche>/<BOND or CDS>/<Label1>`, class `CSR_SEC_NCTP` | `CS01` |
| `Risk_BaseCorr` | `CR/SPREAD/<index family>` | `BASE_CORR` |
| `Risk_Equity` | `EQ/SPOT/<Qualifier>` | `DELTA` |
| `Risk_Commodity` | `CM/PRICE/<Qualifier>/<location>` (CRIF has no tenor: SIMM only) | `DELTA` |
| `Risk_FX` | `FX/SPOT/<Qualifier>` | `DELTA` |
| `Risk_IRVol`, `Risk_InflationVol`, `Risk_CreditVol`, `Risk_CreditVolNonQ`, `Risk_EquityVol`, `Risk_CommodityVol`, `Risk_FXVol` | `VOL/<class>/<underlying>/<Label1>[/<underlying tenor>]` | `VEGA`, with `IMPLIED_VOL` |

**Vega from CRIF.** The CRIF specification of the SIMM version defines each volatility row's `Amount` per risk type; the loader turns it
into `VEGA` per volatility point (and needs the implied volatility for that) by a rule configured per risk type and confirmed with the
bank, never assumed (SIMM.md, open question 3).

### 11.2 From a front-office bump report

A typical risk report has one line per trade, risk type, curve or underlying, tenor and bump: `TRADE_ID`, `RISK_TYPE`, `CURVE_NAME`,
`TENOR`, `BUMP_SIZE`, `BUMP_TYPE` (absolute or relative), `PV_CHANGE`, `CCY`.

| Report column | SENS | Rule |
|---|---|---|
| `TRADE_ID` | `TRADE_ID` | `GRAIN` `TRADE`, or summed into the position by the source |
| `RISK_TYPE` + `CURVE_NAME` + `TENOR` | `RISK_FACTOR` | the source's name, listed in the dictionary's `SOURCE_NAMES` |
| `RISK_TYPE` | `MEASURE` | rate, inflation, spread → `DV01`, `IE01`, `CS01`; spot, price → `DELTA`; volatility → `VEGA` |
| `PV_CHANGE` with `BUMP_SIZE` | `VALUE` | delivered at the standard move (1 bp, 1 %, 1 volatility point); a report bumped by another size is re-run at the standard move by the source, not rescaled by Drishti (a rescaled bump mixes in convexity) |
| `CCY` | `CURRENCY` | |
| the curvature run | `CURVATURE_UP`, `CURVATURE_DOWN`, `SHOCK_SIZE` | two full revaluations per curvature factor with the regulatory shock |
| the tenor | `TENOR` | a non-vertex tenor allocated by the source to the two neighbouring vertices (linear in time) |

### 11.3 From another FRTB engine's sensitivity file

A file already in regulatory units (s_k per unit of the factor, by risk class, bucket, qualifier and tenor) converts back by the constants
of [FRTB.md section 3.2.3](FRTB.md#323-from-the-delivered-values-to-the-regulatory-sensitivities): `VALUE = s_k / 10,000` for rates and
spreads, `s_k / 100` for spots and prices, `s_k / (100 × IMPLIED_VOL)` for vega. Delivering the source's raw bumps instead is better: it
keeps one convention for every reader and lets the MR cube show the ladders in the units traders use.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
