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
11. [Source systems](#11-source-systems)

## 1. What you see

The screen is the grid with the record's panel beside it: the chosen design, option B of the
[mock-ups](../../../docs/lenses/mockups/trading-blotter/README.md), drawn by the Drishti Elements grid in its live mode from the API of section 9:

| Part | What it does |
|---|---|
| The grid | every trade of the declaration's query, sorted, filtered, grouped (desk and book with live subtotals as an option); new rows ride in green, amended in amber, changed cells flash |
| The record's panel | the selected trade: its editable fields, the reason, the four-eyes state, the version history, the links to Explain, VaR and limits, and the **Raw JSON** of the whole record as stored |
| The tray | the person's changes waiting: awaiting an approver, sent to the source, applied, refused (with why) |
| The live mark | the binding it ticks from, the messages per minute and the latency |

### The page, step by step

Open **Trading › Trade blotter** (`/live/trade`; any declaration has its page at `/live/<id>`).

1. **The bar.** The title, the live mark (`LIVE · 30 rows`; `PAUSED`, `RECONNECTING` and `ENDED` say what happened), **Group by** (one field; the
   declaration's `subtotals:` are summed per group and stay current as trades tick), **Pause** (rows that arrive are not applied; Resume reads
   the set again), **Pivot ›** and **My changes (n)**.
2. **Ticking.** A change to a trade arrives over the browser's one live channel and the changed cells flash; a replayed or older version is
   ignored. A set larger than the grid holds (5,000 rows) shows `5,000 of 120,000` and the server sends changes of the rows in view only.
3. **A trade's panel.** Click a row. *Record*: the fields. With the edit power they are a form with a **Reason** (required or optional as the
   declaration says); without it they are plain text, with the words of why ("Read-only for you: ..."), and there is no edit control at all.
   Submit sends the change on the version you opened; the answer is in words (*waiting for 1 approver (four eyes)*, *on its way to the source*).
   If the trade moved meanwhile the panel says **Changed since you opened it**, and the server refuses an edit made on the old version, naming
   who changed it; **Reload** opens it again as it is now. *History*: every change with who, when, why and where it stands. *Raw JSON*: the whole
   stored record, including the fields the grid has no column for, collapsible, with **Copy**, the version and sequence; a field masked for your
   role shows as masked and is copied as the mask. The links (Explain, VaR, Limits) are the declaration's `links:`.
4. **My changes.** The tray lists your changes still open; click a trade to open it.
5. **Pivot ›.** Opens the Pivot on the same kind with the grid's filters, the grouping as rows and the numbers as sums, live when the trades are.

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
changed-at: SOURCE_UPDATE_TIME     # said when a stale edit is refused (changed-by: names who, when the kind's contract has such a field)
columns: [{field: TRADE_ID, label: Trade, type: id}, {field: PRICE, type: number, format: price4}, ...]   # type: text, number, amount, date, datetime, list, status, id
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

Every declaration is **checked at load**, beyond its own shape:

- each column's `format:` is a named format of `formats.yaml` (the bundled one and the packs': `price4` is the trading pack's);
- every field it names (columns, key, version, who and when, grouping, subtotals, sort, retention, the edit's binds) is in the kind's
  data contract (`contracts/<kind>.contract.yaml` of the pack that owns the kind; a kind without a contract is checked for formats only);
- every editable field has a **write path**: it binds a record field, and not one that names or stamps the record (the key, the version,
  who and when: mark those `readonly: true`).

A declaration with problems is set aside with every problem in words, under `DRS-9307`: `GET /api/v1/live` lists them for
administrators (Admin), and asking for the set answers `422 DRS-9307` with the reason; the others load.
The `edit:` form joins the Sutras' forms in the write-back under the name `live:<id>`, so its checks, roles and approval rules are the
write-back's own.

## 7. The live result sets engine

One engine behind every blotter and every FORM result (`com.ash.drishti.server.liveset`):

1. **Snapshot, paged from the store**: a kind a lake keeps as columns (Delta, Iceberg or files, through the bindings) is never read into
   memory. For each order (the key, else the chosen sort, ascending or descending), filter and audience the set keeps an *index*: the keys
   of the records that hold the query, in order, with the sort column's values, read in one pass of those columns (the documents once when
   a field is not a column). A page fetches only its own rows whole, by id; the total is the index's count. The index is read again when
   the store's version moves (a load); a few are kept, the latest asked for. Other kinds (no columns) are read whole in one pass, else
   from the search's matches.
2. **Live deltas**: the kind's `live:` binding, subscribed to **before** the snapshot is read; what arrives meanwhile is applied after it.
   Every change merges **by key and version**: a newer version replaces the row, an older or equal one is dropped (no duplicates, never back
   in time), a deletion or a row that no longer holds the query leaves. Skipped versions are counted (the record is whole, so the newest is
   still right).
   Paged from the store, the set keeps **only the live side** (the records changed since it opened, and the keys deleted or no longer
   holding the query); each page merges it with the store's order by key: a changed record shadows the store's (and takes its place by the
   sort), a removed one hides it, a new one joins; the total is the store's count with the same corrections, so it is exact.
3. **Who sees what** (session isolation: nothing of any person is kept in a shared set): every request makes a *reader* of the person
   (their **audience**: roles and embedding app, which also decide the Drishti*net nodes a search reaches, their masks, their row scope,
   whether they see which node answered). A declared set's rows are shared, and **every read is kept to the reader**: pages, totals, each
   viewer's stream (a record outside their scope is never sent; one that leaves it is a removal to them), the panel's Raw JSON and an edit's
   record. A **search's set** is filled for one audience and read only by it: another person's request for its id is answered as if it did
   not exist. Kind access (the role, the kind's pack switched on for them) is checked on every request. Changes are coalesced per record and
   flushed every `flush-ms` (50 ms).
4. **Scale**: pages of at most 5,000 rows (`offset`, `limit`, `sort`, `filter`: a text found in the shown columns' values as the reader
   sees them); a paged viewer is sent changes of the rows it shows and the new total only; a viewer more than `window-capacity` (5,000)
   records behind is sent one `resync` instead of a queue (back-pressure: a burst never queues without bound). 10,000 updates apply in well
   under a second and reach a viewer as one coalesced event. At a million records (a narrow kind) the first page takes about half a second
   (the index read once), a first sorted page under a second, every later page and a page after a live change a millisecond or two
   (`StorePagesTest`); memory holds the keys and the sort column, never the records.
5. **Retention**: `retention:` trims the live side every 30 s (older than `ttl` by its field, else by arrival; beyond `max-rows`, oldest
   first); the durable store keeps its own.
6. A set nobody has viewed for ten minutes is closed.
7. **A search is a set too**: a FORM's result and the search page's are the search's matches as a set (`GET /api/v1/live/query`): the
   search's rows, columns and order, then the kind's live changes merged in by key while the business date is the live one (static
   otherwise, and when the kind has no `live:` binding). One set per search, business date and **audience**: people who see differently
   (other roles, masks or row scope) get their own set, filled and kept as they see it, so a masked value never picks, orders or filters
   rows and a row outside a person's scope never reaches them, live changes included. Pressing Search again reads a static set afresh; a
   live one is current already.
8. **A bare mnemonic is the kind's set**: `POS <GO>` opens the kind's own set a page at a time (`GET /api/v1/live/kinds/POS/rows`). A
   module's declaration of the kind is used when there is one; otherwise a default is made from the kind's contract (its key fields as
   columns, else its promoted columns; the entity id as the key; read-only). No YAML per mnemonic is needed. A kind a lake keeps as columns
   is paged from it (every record counted: a bank's 1.3 million positions are all there); a kind that cannot be paged is read into memory
   up to `drishti.live-sets.default-max-rows` records, and the set says when it stopped there (`capped`).

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
| `GET` | `/api/v1/live` | the declarations the caller may open (each with `editable` and `readOnly` for them, and for an editable one its `fields`: `id`, `label`, `type`, `bind`, `required`, `readonly`, `pattern`, `maxLength`, `min`, `max`, `options`); for administrators those set aside (`problems`, each with `DRS-9307`) and the open sets (`paged`, `storeRows`) |
| `GET` | `/api/v1/live/{id}/rows` | a page: `offset`, `limit` (≤ 5,000), `sort`, `descending`, `filter`, `query`; `{set, live, total, seq, rows[{id, version, seq, doc}]}` |
| `GET` | `/api/v1/live/{id}/rows/{entity}` | one record whole (the panel's Raw JSON), masked for the caller |
| `GET` | `/api/v1/live/query` | a search's matches as a set (FORM, the search page): `q`, `offset`, `limit`, `sort`, `descending`, `filter`, `again`; the page plus `search` (what the search said) |
| `GET` | `/api/v1/live/kinds/{word}/rows` | a kind's own set by its mnemonic or name (`POS <GO>`); `capped` when it stopped at the default cap |
| `GET` | `/api/v1/live/{id}/stream` | server-sent events: `hello` `{stream, total, live}`, then `rows` `{upserts, removes, total, seq, countOnly}` or `resync` |
| `POST` | `/api/v1/live/{id}/stream/{stream}/window` | the page the stream shows: `{ids: [...]}`, or `{all: true}` |
| `POST` | `/api/v1/live/{id}/edit` | an edit: `{entity, basedOn, reason, items[{field, from, to}]}`; `403 DRS-9302` read-only (why), `409 DRS-9303` stale |

The console passes these on as the person: `GET /api/live`, `GET /api/live/{id}/rows[/{entity}]`, `POST /api/live/{id}/edit` and
`POST /api/live/{id}/stream/{stream}/window`; the stream is the console's channel (`/api/channel?s=live:{id}`, events `hello`, `rows`,
`resync`). The page is `/live/{id}`; the module's menu entry (`console/plugin.yaml`) links to `/live/trade`. The component is the grid's live
mode (ELEMENTS.md section 5.5).

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `drishti.live-sets.flush-ms` | `50` | how often viewers' windows are sent their changes |
| `drishti.live-sets.window-capacity` | `5000` | records waiting per viewer before it is asked to resync |
| `drishti.live-sets.dir` | `config/blotters` | the site's own declarations |
| `drishti.live-sets.modules-dir` | `modules` | where the modules' `blotters/` folders are found (a module's only while it is on) |
| `drishti.live-sets.edits-off` | (none) | the blotters whose edits the site switched off, by id (`trade,exposure`) |
| `drishti.live-sets.default-max-rows` | `250000` | the most records a kind's default set (a bare mnemonic) reads into memory when its store cannot page it |
| `drishti.live-sets.store-timeout-seconds` | `120` | how long a set paged from the lake may take to read the columns it orders by, and a page's records |

Every one is in [CONFIGURATION.md](../../../docs/admin/CONFIGURATION.md) (`drishti.live-sets`), generated from the server's `application.yaml`.

## 11. Source systems

The `trade` kind is one canonical kind (the trading pack's `trade` contract; every row carries `SOURCE_SYSTEM`). Each trading system that
feeds it is a **source module of its own**, `modules/source-<system>/`, which requires this module and switches on and off alone
(`config/modules/source-<system>.yaml`, `enabled: false`; off, the system's trades leave the kind and nothing else changes). Six ship:
`source-endur`, `source-calypso`, `source-murex` (their data is already in the canonical schema) and `source-wss` (treasury, FX and
money markets), `source-swapone` (OTC swaps) and `source-ici` (a generic cross-asset booking system; its asset class was not stated, so it
is mocked as one). The data of the last three is **mocked** (`tools/samplegen/src_*.py`; each in its own native field style; no
positions and no scenario P&L, so the VaR does not move).

A module's manifest names its parts with one set of keys: `connectors` (the folder of connector templates), `mappings` (kind to map file),
`dataload` (the inbox rules file) and `sections` (what it adds to the kind's page). These templates are the one home of the system's
connectors: the trading pack declares none of them. A file dropped in the inbox (`swapone-<date>.jsonl.gz` and its `.ready`) goes by the
module's rule to `trading.trades_shared`, its connector's `set:` stamps `SOURCE_SYSTEM = SWAPONE` into each stored row, and `TRD` lists
it; switch `source-swapone` off and it is gone, on and it is back.

A module ships: `connectors/` (read from Delta, Iceberg and files; a Kafka live stream whose `writes:` is the write-back of
section 8), `mappings/<system>-trade.yaml` (the native fields to the canonical ones), `dataload.yaml` (the inbox routing rules, in the
format of `config/dataload.yaml`; the inbox reads the site's rules first) and a short guide. A connector's source options:

| Option | Meaning |
|---|---|
| `lake-tables` | the table read: a table of the system's own, or one several systems share |
| `where` | a predicate that selects this system's rows of a shared table |
| `set` | constants stamped into the rows this connector reads and into the rows the inbox stores (`SOURCE_SYSTEM`) |
| `map` | the native-to-canonical field map (the module's `mappings/` file) |

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

```yaml
bindings:
  trade:
    read: [trade-endur, trade-calypso, trade-murex, trade-wss, trade-swapone, trade-ici]
    union: true
    live: trade-kafka
```

The inbox: `wss-<date>.jsonl.gz` (SwapOne `swapone-`, ICI `ici-`, Endur, Calypso and Murex likewise) goes to that system's table by the module's
rule; put the file's `.ready` marker in last (each module's guide has the example).
