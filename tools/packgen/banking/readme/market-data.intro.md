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
refresh: tools/publicdata/official_market_data.py
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
    FACTORS: The risk factors of the matrix
    PAIRS: Correlations by pair of factors
    WINDOW: Estimation window
    ESTIMATOR: Estimation method, e.g. Ledoit-Wolf shrinkage
---
## What it is

The market data the trading and risk packs price with: interest-rate, repo, FX-forward, credit, dividend, inflation and commodity
curves; FX, cap, swaption, equity and commodity volatility surfaces; FX spots, equities, equity indexes, commodities, bonds, rate
fixings, inflation indexes and a correlation matrix. Products select their curves and surfaces by the ids defined here.

## Data sources and licences

The committed samples are synthetic. Five feed connectors (off by default) read the same kinds live from official public sources;
`tools/publicdata/official_market_data.py` captures those sources to files in the data folder, never over the samples.
