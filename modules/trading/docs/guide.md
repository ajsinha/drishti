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
# The trading module

The module ships the trade blotter (`/live/trade`) and its binding; the steps below are specific to trades. The engine behind the blotter, the page,
editing, the declaration, the API and the settings are the platform's: [Live results and blotters](../../../docs/guides/LIVE_RESULTS_GUIDE.md).

## 1. Switch it on (tutorial)

```bash
# 1. the module is on (Admin -> Modules, or the distribution lists it)
drishti.py module list | grep trading
# trading  1.0.0  first-party  content  on

# 2. the trade kind is bound with a live source (section 2), then the blotter answers
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" $B/live | jq '.sets[].id'
# "trade"
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$B/live/trade/rows?limit=3" | jq '{total, live, rows: [.rows[].id]}'
# {"total": 4120, "live": true, "rows": ["MX-20004324", "MX-20004261", "MX-20004205"]}
```

## 2. Bind the trades

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

## 3. Edit a trade (tutorial)

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
The same guard runs again when the change is applied at the source ([live results, section 8](../../../docs/guides/LIVE_RESULTS_GUIDE.md#8-writing-back-to-the-records-own-source)), so a change that raced another one is refused there too.

## 4. Source systems

The `trade` kind is one canonical kind (the trading pack's `trade` contract; every row carries `SOURCE_SYSTEM`). Each trading system that
feeds it is a **source module of its own**, `modules/source-<system>/`, which requires this module and switches on and off alone
(`config/modules/source-<system>.yaml`, `enabled: false`; off, the system's trades leave the kind and nothing else changes). Six ship:
`source-endur`, `source-calypso`, `source-murex` (their data is already in the canonical schema) and `source-wss` (treasury, FX and
money markets), `source-swapone` (OTC swaps) and `source-ici` (a generic cross-asset booking system; its asset class was not stated, so it
is mocked as one). The data of the last three is **mocked** (`tools/samplegen/src_*.py`; each in its own native field style; no
positions and no scenario P&L, so the VaR does not move).

A module's manifest names its parts with one set of keys: `connectors` (the folder of connector templates), `mappings` (kind to map file),
`dataload` (the inbox rules file) and `sections` (what it adds to the kind's page). These templates are the one home of the system's
connectors: the trading pack declares none of them. A file dropped in the inbox (`swapone-2026-11-03.jsonl.gz`; no `.ready` is needed, the
rule's `date-pattern` reads the date from the name) goes by the module's rule to `trading.trades_shared`, its connector's `set:` stamps `SOURCE_SYSTEM = SWAPONE` into each stored row, and `TRD` lists
it; switch `source-swapone` off and it is gone, on and it is back.

A module ships: `connectors/` (read from Delta, Iceberg and files; a Kafka live stream whose `writes:` is the write-back of
the [live results guide](../../../docs/guides/LIVE_RESULTS_GUIDE.md#8-writing-back-to-the-records-own-source), section 8), `mappings/<system>-trade.yaml` (the native fields to the canonical ones), `dataload.yaml` (the inbox routing rules, in the
format of `config/dataload.yaml`; the inbox reads the site's rules first) and a short guide. A connector's source options:

| Option | Meaning |
|---|---|
| `lake-tables` | the table read: a table of the system's own, or one several systems share |
| `where` | a predicate that selects this system's rows of a shared table |
| `set` | constants stamped into the rows this connector reads and into the rows the inbox stores (`SOURCE_SYSTEM`) |
| `map` | the native-to-canonical field map (the module's `mappings/` file) |

**The map file** (`mappings/<system>-trade.yaml`, `format: 1`): under `map:` each line is a canonical field and where its value comes from.
Real feeds nest and rename, so one line can be:

| Form | Meaning |
|---|---|
| `TRADE_ID: SWAP_ID` | the native field `SWAP_ID` |
| `QUANTITY: PAY_LEG.NOTIONAL` | a dotted path into a nested native document |
| `from: [a, b]` or `first-of: [a, b]` | the first of the paths that is present and not blank |
| `scale: 0.01` | multiply the number (a rate in percent to a fraction) |
| `cast: text \| number \| integer \| boolean \| date` | the type |
| `as: yyyymmdd \| yyyymmddhhmmss \| epoch-millis \| epoch-seconds \| iso` | a date or time written another way (a date becomes `YYYY-MM-DD`, a time an ISO UTC instant) |
| `values: {LIVE: NEW}` | a code translated (a code not listed stays as read) |
| `value: X` | a constant |

```yaml
map:
  TRADE_ID: SWAP_ID
  QUANTITY: PAY_LEG.NOTIONAL
  PRICE: { first-of: [PAY_LEG.FIXED_RATE_PCT, REC_LEG.FIXED_RATE_PCT], scale: 0.01 }
  TRADE_DATE: { from: TRADE_DT, as: yyyymmdd }
```

The older form (`fields:`, native field to canonical field) still works. A map that cannot be used refuses the connector (`DRS-5034`, shown
on Admin -> Connectors). A field built from a nested path, alternatives, a scale or a reading is served from the documents, not from
columns. `drishti.py pack new --source-for KIND` proposes dotted paths for nested native fields.

**The date of a file in the inbox.** The module's rule names it from the file name: `date-from: name` with `date-pattern`, a regular
expression with one capture group around the date, `-(\d{4}-\d{2}-\d{2})\.` for `swapone-2026-11-03.jsonl.gz`. A date written
`20261103` takes `date-format: YYYYMMDD`. A `.ready` file's `business-date:` still wins; a name the pattern does not match is refused.

**Layout A: a table each** (Endur, Calypso and Murex in the demo): the connector reads its own table, no `where` is needed.

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
settings:
  lake: main
  lake-tables: trading.trade_endur
  set: { SOURCE_SYSTEM: ENDUR }
  map: mappings/endur-trade.yaml
```

**Layout B: one shared table, split by `where`** (WSS, SwapOne and ICI in the demo, `trading.trades_shared`, partitioned by `SOURCE_SYSTEM`):

```yaml
# config/connectors/trade-wss.yaml
plugin: delta
settings:
  lake: main
  lake-tables: trading.trades_shared
  where: SOURCE_SYSTEM = 'WSS'
  set: { SOURCE_SYSTEM: WSS }
  map: mappings/wss-trade.yaml
```

The kind reads them all as a union (`config/demo/bindings.yaml`, written by `drishti.py lake demo`, on by default; `--no-bindings` leaves it out):
