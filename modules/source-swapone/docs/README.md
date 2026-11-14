---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: source-swapone
title: Source SwapOne
domain: trading
summary: SwapOne OTC swaps, in the shared trades table (mocked data)
kinds: [trade]
---
# Source SwapOne

SwapOne is an OTC swaps system (mocked here: a synthetic look-alike, snake_case, a swap as a pay leg and a receive leg, epoch-millisecond update times).
The data is **mocked**: a synthetic look-alike written by `tools/samplegen/src_swapone.py` (never real data, banks or counterparties), a few hundred trades over sixty business days in the system's own native style, with no positions and no scenario P&L, so the VaR does not move.

This module is one source of the canonical `trade` kind: its rows carry `SOURCE_SYSTEM = SWAPONE`. It requires the `trading` module and
can be switched on or off on its own (`config/modules/source-swapone.yaml`, `enabled: false`).

## Data structures

| Kind | Where | Native key | Canonical fields it fills |
|---|---|---|---|
| `trade` | the shared table trading.trades_shared, split by where: | `swap_id` | `TRADE_ID`, `TRADE_VERSION`, `TRADE_STATUS`, `PRODUCT_TYPE`, `BUY_SELL_CODE`, `QUANTITY`, `PRICE`, `CURRENCY`, `COUNTERPARTY`, `LEGAL_ENTITY`, `PORTFOLIO`, `TRADER_ID`, `TRADE_DATE`, `START_DATE`, `MATURITY_DATE`, `SOURCE_UPDATE_TIME` |

The guide: [guide.md](guide.md).
