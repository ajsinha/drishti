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
5. [Who may edit](#5-who-may-edit)
6. [The declaration](#6-the-declaration)
7. [The live result sets engine](#7-the-live-result-sets-engine)
8. [Writing back: Kafka and JDBC](#8-writing-back-kafka-and-jdbc)
9. [The API](#9-the-api)
10. [Settings](#10-settings)

## 1. What you see

The screen is the grid with the record's panel beside it: the chosen design, option B of the
[mock-ups](../../../docs/lenses/mockups/trading-blotter/README.md), drawn by the Drishti Elements grid in its live mode from the API of section 9:

| Part | What it does |
|---|---|
| The grid | every trade of the declaration's query, sorted, filtered, grouped (desk and book with live subtotals as an option); new rows ride in green, amended in amber, changed cells flash |
| The record's panel | the selected trade: its editable fields, the reason, the four-eyes state, the version history, the links to Explain, VaR and limits, and the **Raw JSON** of the whole record as stored |
| The tray | the person's changes waiting: awaiting an approver, sent to the source, applied, refused (with why) |
| The live mark | the binding it ticks from, the messages per minute and the latency |

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
The same guard runs again when the change is applied at the source (section 8), so a change that raced another one is refused there too.

## 5. Who may edit

Never everyone. A blotter is editable for a person only when **both** allow it, checked on the server for every write (the screen only
reflects it):

| | Read-only when |
|---|---|
| The site's configuration | the declaration names no `edit:`; write-back is off (`edit.enabled`); the site switched this blotter's edits off (its id in `drishti.live-sets.edits-off`); the connector's command is missing, refused or its writes are off (Admin → Edits) |
| The person's rights | no **edit** power; the kind is not theirs to open; none of the form's fields is editable by their roles (`roles:`, `editable-by:`) |

A person who may not edit sees no edit controls (`editable: false` and `readOnly: <why>` in the declaration the API gives them). A crafted
request is refused with `403 DRS-9302` and the reason, and audited (`edit-refused`). Four eyes apply on top. Finer entitlements (per desk,
per field) come with the security release.

## 6. The declaration

`blotters/<id>.blotter.yaml` in a module, or `config/blotters/` for a site's own. The trading module's
[`trade.blotter.yaml`](../blotters/trade.blotter.yaml) in short:

```yaml
blotter: trade                     # its id
title: Trade blotter
kind: trade
query: "trade where TRADE_STATUS != 'CANCELLED'"   # the rows (the search grammar); absent: every record of the kind
key: TRADE_ID                      # the record's key (absent: the entity id)
version: TRADE_VERSION             # the version: changes merge by it, and an edit on an older one is refused
changed-by: UPDATED_BY             # said when a stale edit is refused
changed-at: SOURCE_UPDATE_TIME
columns: [{field: TRADE_ID, label: Trade, type: id}, {field: TAGS, type: list}, ...]   # type: text, number, amount, date, datetime, list, status, id
group-by: []                       # the grouping it opens with
subtotals: [QUANTITY]              # summed per group
sort: {field: SOURCE_UPDATE_TIME, descending: true}
links: [{label: VaR, href: "/var?scope=book:{PORTFOLIO}"}]   # paths of the console, {FIELD} filled from the row
retention: {ttl: 1d, on: SOURCE_UPDATE_TIME, max-rows: 100000}   # the live side's age limit (by a field, else arrival) and row cap
tray: true                         # the tray of the person's changes
edit:                              # a Rachana form with save: (RACHANA_REFERENCE.md, forms): fields, checks, roles, where it goes
  edit: cells
  roles: [trader, middle-office, admin]
  fields: [{id: book, bind: PORTFOLIO, required: true, pattern: "BOOK-[A-Z0-9-]+"}, ...]
  save: {target: source, command: amend-trade, approval: none, reason: optional}
```

A declaration with problems is set aside with every problem in words (`GET /api/v1/live` lists them for administrators); the others load.
The `edit:` form joins the Sutras' forms in the write-back under the name `live:<id>`, so its checks, roles and approval rules are the
write-back's own.

## 7. The live result sets engine

One engine behind every blotter and every FORM result (`com.ash.drishti.server.liveset`):

1. **Snapshot**: the declaration's query over the durable store through the bindings, in one pass where the store can (a lake, Kafka's
   topic state), else the search's matches.
2. **Live deltas**: the kind's `live:` binding, subscribed to **before** the snapshot is read; what arrives meanwhile is applied after it.
   Every change merges **by key and version**: a newer version replaces the row, an older or equal one is dropped (no duplicates, never back
   in time), a deletion or a row that no longer holds the query leaves. Skipped versions are counted (the record is whole, so the newest is
   still right).
3. **Viewers**: one set is shared by everyone viewing the same declaration and query, and holds nothing of any person; each viewer's window
   masks what it sends as that viewer may see it. Changes are coalesced per record and flushed every `flush-ms` (50 ms).
4. **Scale**: pages of at most 5,000 rows (`offset`, `limit`, `sort`, `filter`); a paged viewer is sent changes of the rows it shows and the
   new total only; a viewer more than `window-capacity` (5,000) records behind is sent one `resync` instead of a queue (back-pressure: a
   burst never queues without bound). 10,000 updates apply in well under a second and reach a viewer as one coalesced event.
5. **Retention**: `retention:` trims the live side every 30 s (older than `ttl` by its field, else by arrival; beyond `max-rows`, oldest
   first); the durable store keeps its own.
6. A set nobody has viewed for ten minutes is closed.

## 8. Writing back: Kafka and JDBC

Edits go through the one write-back (APPROVALS_GUIDE.md): the lane, the approvals, the **outbox** (the change recorded as pending in the
same transaction that applies it, sent once per record in order, resent after a failover with the **same change id**) and the audit. The
connector says how; besides JDBC, **Kafka** writes:

```yaml
# config/connectors/trade-kafka.yaml (excerpt)
writes:
  amend-trade:
    kind: trade
    kafka:
      topic: trades.amend.v1          # where the change goes
      shape: command                  # command: {changeId, kind, entity, field, value, from, basedOn, by, reason, at}, keyed by the change id
                                      # document: the whole amended record (next version, change id, who, when) to the record's topic
      version-field: TRADE_VERSION    # the guard: sent only while the record is at the version the person saw
      changed-by: UPDATED_BY
      changed-at: SOURCE_UPDATE_TIME
      change-id-field: DRISHTI_CHANGE_ID   # the idempotency clause: a record already carrying the change id is a resend
    confirm: reply                    # the broker's acknowledgement (acks=all, an idempotent producer)
```

| | Kafka | JDBC |
|---|---|---|
| The guard | the record's version as the live binding holds it, before sending | the statement's `WHERE VERSION = :basedOn` and `expect-rows` |
| Idempotency | the message key is the change id (a command), the record's change-id field (a document); every message carries the header `drishti-change-id` | `already-applied` |
| Confirmed by | the broker's acknowledgement | the statement's reply, or the next load |

A change of several fields sends one message per field (`<id>#1`, `#2`); a document's later fields build on the earlier ones. The Delta,
Iceberg and file connectors never write per change.

## 9. The API

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/v1/live` | the declarations the caller may open (each with `editable` and `readOnly` for them); for administrators those set aside and the open sets |
| `GET` | `/api/v1/live/{id}/rows` | a page: `offset`, `limit` (≤ 5,000), `sort`, `descending`, `filter`, `query`; `{set, live, total, seq, rows[{id, version, seq, doc}]}` |
| `GET` | `/api/v1/live/{id}/rows/{entity}` | one record whole (the panel's Raw JSON), masked for the caller |
| `GET` | `/api/v1/live/{id}/stream` | server-sent events: `hello` `{stream, total, live}`, then `rows` `{upserts, removes, total, seq, countOnly}` or `resync` |
| `POST` | `/api/v1/live/{id}/stream/{stream}/window` | the page the stream shows: `{ids: [...]}`, or `{all: true}` |
| `POST` | `/api/v1/live/{id}/edit` | an edit: `{entity, basedOn, reason, items[{field, from, to}]}`; `403 DRS-9302` read-only (why), `409 DRS-9303` stale |

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `drishti.live-sets.flush-ms` | `50` | how often viewers' windows are sent their changes |
| `drishti.live-sets.window-capacity` | `5000` | records waiting per viewer before it is asked to resync |
| `drishti.live-sets.dir` | `config/blotters` | the site's own declarations |
| `drishti.live-sets.modules-dir` | `modules` | where the modules' `blotters/` folders are found (a module's only while it is on) |
| `drishti.live-sets.edits-off` | (none) | the blotters whose edits the site switched off, by id (`trade,exposure`) |
