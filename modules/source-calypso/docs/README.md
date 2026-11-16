---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: source-calypso
title: Source Calypso
domain: trading
summary: Calypso trades (rates, credit, structured), one table of their own
kinds: [trade]
---
# Source Calypso

Calypso is the capital markets trading and risk system. Its trades arrive in the common UPPER_SNAKE trade schema, so the mapping is the identity.
This module is one source of the canonical `trade` kind: its rows carry `SOURCE_SYSTEM = CALYPSO`. It requires the `trading` module and
can be switched on or off on its own (`config/modules/source-calypso.yaml`, `enabled: false`).

## Data structures

| Kind | Where | Native key | Canonical fields it fills |
|---|---|---|---|
| `trade` | its own table trading.trade_calypso | `TRADE_ID` | `TRADE_ID`, `TRADE_VERSION`, `TRADE_STATUS`, `PRODUCT_TYPE`, `BUY_SELL_CODE`, `QUANTITY`, `PRICE`, `CURRENCY`, `COUNTERPARTY`, `LEGAL_ENTITY`, `PORTFOLIO`, `TRADER_ID`, `TRADE_DATE`, `START_DATE`, `MATURITY_DATE`, `SOURCE_UPDATE_TIME` |

The guide: [guide.md](guide.md).
