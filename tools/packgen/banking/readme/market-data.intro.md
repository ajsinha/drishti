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
---
generator: tools/packgen/banking/make_docs.py
sources:
- name: Synthetic sample data (tools/packgen/banking)
  licence: Proprietary (part of Drishti); fictitious names and figures
  internal-only: false
  used-for: every document in samples/ and the demo lake
- name: Official public data (ECB, US Treasury, New York Fed, FRED) through the feed connectors
  licence: Public sources, attribution; read live, off by default
  internal-only: false
  used-for: rate-fixing, fx-spot and ir-curve when the feed connectors are on
refresh: drishti.py feed run market-data
fields:
  ir-curve:
    POINTS: The curve's pillars (tenor, date, rate, discount factor)
    FORWARDS: Forward rates read off the curve
    INTERPOLATION: Interpolation method, e.g. log-linear on discount factors
    DAY_COUNT: Day-count convention
    CALENDAR: Holiday calendar id
    BUILT_AT: When the curve was built
    SOURCES: Quote sources the curve was built from
  repo-curve: {POINTS: The curve's pillars}
  fx-spot:
    PAIR_NAME: The pair as BASE/QUOTE
    HISTORY: Daily history (date, mid)
    CONVENTIONS: Quoting conventions (base, quote, pip factor, spot lag, calendar, NDF)
  fx-forward-curve: {POINTS: Forward points by tenor}
  fx-vol-surface: {GRID: Volatilities by expiry and delta, MODEL: Smile model}
  ir-vol-cube: {GRID: Volatilities by expiry, tenor and strike}
  cap-vol-surface: {GRID: Volatilities by expiry and strike, MODEL: Volatility model}
  equity:
    HISTORY: Daily closes
    OHLC: Open, high, low and close bars
    IDENTIFIERS: Other identifiers (ISIN, exchange codes)
  equity-index: {CONSTITUENTS: Members with their weights, HISTORY: Daily levels}
  dividend-curve: {POINTS: Expected dividends by date}
  equity-vol-surface: {GRID: Volatilities by expiry and strike, MODEL: Volatility model}
  credit-curve: {POINTS: Spreads and default probabilities by tenor, CURRENCY: Currency of the curve}
  inflation-index: {CURRENCY: Currency, FIXINGS: Monthly index fixings}
  inflation-curve: {POINTS: Zero-coupon inflation rates by tenor, SEASONALITY: Monthly seasonal adjustments}
  commodity: {OHLC: Open, high, low and close bars, SPEC: Contract specification}
  commodity-curve: {POINTS: Futures prices by delivery month, UNIT: Price unit}
  commodity-vol-surface: {GRID: Volatilities by expiry and strike}
  rate-fixing: {CURRENCY: Currency, FIXINGS: Daily fixings (date, rate)}
  bond:
    ISIN_CODE: The ISIN without the BND- prefix
    TERMS: Issue terms
    COUPONS: Coupon schedule
    HISTORY: Daily price and yield
  correlation-matrix:
    FACTORS: The risk factors of the set
    EQUITY_IDS: The factors that are equities (their price history)
    FX_IDS: The factors that are FX pairs (their price history)
---
## What it is

The market data the trading and risk packs price with: interest-rate, repo, FX-forward, credit, dividend, inflation and commodity
curves; FX, cap, swaption, equity and commodity volatility surfaces; FX spots, equities, equity indexes, commodities, bonds, rate
fixings, inflation indexes and a correlation matrix. Products select their curves and surfaces by the ids defined here.

**Intraday.** The lake holds the close; the live companion `market-store-live` (`companion-of: market-store`, off until
`DRISHTI_MARKET_STREAM=true`, the broker named `default`) carries the day's changes over it from one Kafka topic per kind,
`drishti.market.<kind>`, for every kind but `correlation-matrix` (an end-of-day estimate). Each contract's `live:` block says how: a price
kind (`fx-spot`, `equity`, `equity-index`, `commodity`, `bond`) takes ticks of its changed fields ordered by `QUOTE_TIME`, at most one a
second reaching the screens; a fixing one new point by `FIXED_AT`; a curve or a surface only whole, by `BUILT_AT`, at most one every 5 or
30 seconds. The close's load replaces the day's ticks (`absorb: by-cutoff`). A live quote moves what a record page and a market monitor
show, never VaR, exposure or P&L, which stay on the close. `tools/samplegen/market_stream.py` publishes a demo feed
([LIVE_CONNECTORS_DESIGN.md section 8.3](../../../docs/architecture/LIVE_CONNECTORS_DESIGN.md#83-market-data-the-20-lake-tables-of-the-market-data-pack)).

## Data sources and licences

The committed samples are synthetic. Five feed connectors (off by default) read the same kinds live from official public sources;
`drishti.py feed run market-data` captures those sources to files in the data folder, never over the samples.
