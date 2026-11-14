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
# Trading: the live blotter

The trading module shows the bank's trades **live**: new and amended trades appear in the blotter within milliseconds of reaching the
bank's messaging (Kafka, RabbitMQ, ActiveMQ), and traders and middle office correct them in place, the change written back to the source
system with a version per trade, an outbox, four eyes where the site asks and a full audit. Drishti is the risk and analytics layer next
to the bank's trading systems: the blotter is **not** an order or execution system. It sends no orders to venues and opens no FIX
sessions; orders stay with the bank's order-management system.

The blotter is not trade-specific code. It is a **declaration** (`blotters/trade.blotter.yaml` in the module) that the kernel's one
**live result sets** engine runs; a risk, exposure or inventory blotter is another declaration, and a FORM search's result is the same
engine without edits.

## Contents

1. [What you see](#1-what-you-see)
2. [Switch it on (tutorial)](#2-switch-it-on-tutorial)
3. [Bind the trades](#3-bind-the-trades)
4. [Edit a trade (tutorial)](#4-edit-a-trade-tutorial)

The engine, the page, rights, the declaration format, write-back, the API and the settings are in
[Live results and blotters](../../../docs/guides/LIVE_RESULTS_GUIDE.md).

## 1. What you see

Open **Trading › Trade blotter** (`/live/trade`): the grid of every trade of the declaration's query, the record's panel (editable Book,
Price and Quantity; Status for middle office), the tray of your changes and **Pivot ›**. How the page works:
[the guide](../../../docs/guides/LIVE_RESULTS_GUIDE.md#2-the-page); the declaration is [`trade.blotter.yaml`](../blotters/trade.blotter.yaml).

## 2. Switch it on (tutorial)

```bash
# 1. the module is on (Admin -> Modules, or the distribution lists it)
drishti.py module list | grep trading
# trading  1.0.0  first-party  content  on

# 2. the trade kind is bound with a live source (section 3), then the blotter answers
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" $B/live | jq '.sets[].id'
# "trade"
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$B/live/trade/rows?limit=3" | jq '{total, live, rows: [.rows[].id]}'
# {"total": 4120, "live": true, "rows": ["MX-20004324", "MX-20004261", "MX-20004205"]}
```

## 3. Bind the trades

The blotter reads the `trade` kind through `config/bindings.yaml`, like every module:

```yaml
bindings:
  trade:
    read: [trade-endur, trade-murex]   # the durable store: the snapshot
    union: true
    live: trade-kafka                  # the messaging connector: the changes, merged in by key and version
```

The Kafka connector reads its topic as the latest record per trade (`kind.<topic>: trade`, `id-field.<topic>: TRADE_ID`); RabbitMQ and
ActiveMQ work the same way through their own connectors. Every change of the kind reaches the blotter as it is consumed.

To land the day's live trades in the **in-memory lake** as well (for BI and the engines that read tables), a memory table reads the Kafka
connector's state in one pass and keeps a day of it:

```yaml
# config/engines/memory-lake.yaml (excerpt; DATA_LAKE_GUIDE.md, the in-memory lake)
drishti.lake.memory.tables:
  - name: trades-live
    store: livelake                       # config/lakes/livelake.yaml, root: mem://livelake
    source: { connector: trade-kafka, kind: trade }
    mode: merge
    key: [TRADE_ID]
    columns: [PORTFOLIO, TRADER_ID, TRADE_STATUS]
    signal: true                          # refilled when the connector's last update moves
    retention: { ttl: 1d, on: SOURCE_UPDATE_TIME }   # rows older than a day are dropped by the compaction
```

## 4. Edit a trade (tutorial)

1. Select the trade; its panel opens with the fields you may change (Book, Price, Quantity; Status for middle office).
2. Change the book, give a reason, **Submit**. The change goes to the write-back with the version you saw.
3. Where the site asks for four eyes (`config/edit/policy.yaml`, `source: one`), it waits in **Approvals** for another person; otherwise it
   goes to its lane at once.
4. The outbox sends it to the source once; the source's new version comes back through the live binding and the row ticks.

From the command line, the same request:

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' $B/live/trade/edit -d '{
  "entity": "MX-20004163", "basedOn": 7, "reason": "booked in the wrong book: ticket 4411",
  "items": [{"field": "PORTFOLIO", "from": "BOOK-IRS-1", "to": "BOOK-IRS-2"}]}'
# {"changes": [{"id": "chg-01M5V8...", "kind": "trade", "entity": "MX-20004163", "target": "source", "state": "awaiting", "needed": 1}]}
```

**A stale edit is refused.** When the trade moved since you saw it, the answer is `409 DRS-9303`:
*trade MX-20004163 is at version 8, changed by R.DIAZ at 10:02:11; you edited version 7: open it again and edit from what is there*.
The same guard runs again when the change is applied at the source ([section 8](../../../docs/guides/LIVE_RESULTS_GUIDE.md#8-writing-back-to-the-records-own-source)), so a change that raced another one is refused there too.
