---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: source-ici
title: Source ICI
domain: trading
summary: ICI cross-asset bookings, in the shared trades table (mocked data)
kinds: [trade]
---
# Source ICI

ICI is a generic cross-asset booking system. What it trades was not stated, so it is MOCKED as a generic cross-asset booking system (rates, FX, equity, credit and commodity tickets; UPPER_SNAKE names, sections HEADER, INSTRUMENT, PARTIES, DATES; YYYYMMDD dates).
The data is **mocked**: a synthetic look-alike written by `tools/samplegen/src_ici.py` (never real data, banks or counterparties), a few hundred trades over sixty business days in the system's own native style, with no positions and no scenario P&L, so the VaR does not move.

**ICI is mocked as a generic cross-asset booking system**: the owner did not say what it trades, so the mock books rates, FX, equity, credit and commodity tickets from one blotter.

This module is one source of the canonical `trade` kind: its rows carry `SOURCE_SYSTEM = ICI`. It requires the `trading` module and
can be switched on or off on its own (`config/modules/source-ici.yaml`, `enabled: false`).

## Data structures

| Kind | Where | Native key | Canonical fields it fills |
|---|---|---|---|
| `trade` | the shared table trading.trades_shared, split by where: | `TICKET_NO` | `TRADE_ID`, `TRADE_VERSION`, `TRADE_STATUS`, `PRODUCT_TYPE`, `BUY_SELL_CODE`, `QUANTITY`, `PRICE`, `CURRENCY`, `COUNTERPARTY`, `LEGAL_ENTITY`, `PORTFOLIO`, `TRADER_ID`, `TRADE_DATE`, `START_DATE`, `MATURITY_DATE`, `SOURCE_UPDATE_TIME` |

The guide: [guide.md](guide.md).
