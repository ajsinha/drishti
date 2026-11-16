<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
---
module: trading
title: Trading
kinds: [trade]
---
# Trading

The live trade blotter: a declaration (`blotters/trade.blotter.yaml`) the kernel's live result sets engine runs. The trades from the
durable store, ticking from the `trade` kind's `live:` binding, editable where the site's configuration and the person's rights allow it,
each change written back through the binding's write path (a version per trade, the outbox, four eyes where the site asks, audit).

## Data structures

| Kind | Bound as | Fields the blotter uses |
|---|---|---|
| `trade` | `read:` the durable store; `live:` a messaging connector (Kafka, RabbitMQ, ActiveMQ) | `TRADE_ID` (key), `TRADE_VERSION` (version), `TRADE_STATUS`, `PORTFOLIO`, `TRADER_ID`, `COUNTERPARTY`, `PRODUCT_TYPE`, `BUY_SELL_CODE`, `QUANTITY`, `PRICE`, `CURRENCY`, `TRADE_DATE`, `MATURITY_DATE`, `SOURCE_UPDATE_TIME` |

The guide and tutorial: [guide.md](guide.md).
