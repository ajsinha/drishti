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
# Live results and blotters

One engine shows any set of records **live**: a snapshot read from the durable store, then every change merged in by key and version as it
arrives. Blotters, FORM search results, bare mnemonics (`POS`, `TRD`, `NEWS`), monitors, drill-through and previews all run on it; a SQL or
BI table is the static case of the same grid. A blotter is a **declaration**, a YAML file; a new blotter (trades, positions, exposure,
inventory) is a configuration job, not a project. Drishti is the analytics layer next to a bank's systems: a blotter sends no orders.

## Contents

1. [One engine, and what uses it](#1-one-engine-and-what-uses-it)
2. [The page](#2-the-page)
3. [Editing: configuration and rights](#3-editing-configuration-and-rights)
4. [Versions, conflicts, the outbox, four eyes and the audit](#4-versions-conflicts-the-outbox-four-eyes-and-the-audit)
5. [Row rules and per-person isolation](#5-row-rules-and-per-person-isolation)
6. [The declaration](#6-the-declaration)
7. [The engine: snapshot, deltas, scale, retention](#7-the-engine-snapshot-deltas-scale-retention)
8. [Writing back to the record's own source](#8-writing-back-to-the-records-own-source)
9. [The API](#9-the-api)
10. [Settings](#10-settings)
11. [Make a new blotter](#11-make-a-new-blotter)
12. [Retention in every store](#12-retention-in-every-store)
13. [Tutorial: feed, blotter, edit, approval, write-back, Pivot](#13-tutorial-a-kafka-feed-a-ticking-blotter-an-edit-with-four-eyes-written-back-pivot)
14. [Screenshots to add](#14-screenshots-to-add)
15. [The trading module: switch it on, bind the trades, edit a trade](#15-the-trading-module)
16. [Source systems: one module per trading system](#16-source-systems)

## 1. One engine, and what uses it

A **live result set** is the snapshot of the records that hold a condition, from the kind's durable store (Delta, Iceberg or files, through
the bindings), plus the live changes of the kind's `live:` binding (Kafka, RabbitMQ, ActiveMQ), merged **by key and version**, plus an optional
edit path and an optional retention. What uses it:

| User | What it is |
|---|---|
| A blotter | a declaration with columns, grouping, links and, optionally, edits |
| A FORM or the search page | the search's matches as a set: a blotter without `edit:` |
| A bare mnemonic (`POS <GO>`, `TRD`, `NEWS`) | the kind's own set; a default declaration is made from its contract |
| A monitor, drill-through, a preview | the same set, opened from another page |
| A SQL or BI table | the static grid: the same component without the live channel |

The trading module's trade blotter is the first declaration (section 15).

## 2. The page

The screen is the grid with the record's panel beside it: the Drishti Elements grid in its live mode, drawn from the API of section 9.

| Part | What it does |
|---|---|
| The grid | every record of the declaration's query, sorted, filtered and grouped; new rows ride in green, amended in amber, changed cells flash |
| The record's panel | the selected record: its fields, the reason, the four-eyes state, the version history, the links, and **Raw JSON** |
| Grouping | one field; the declaration's `subtotals:` are summed per group and stay current as records tick |
| The tray (**My changes**) | the person's changes waiting: awaiting an approver, sent to the source, applied, refused (with why) |
| **Pivot ›** | opens the Pivot on the same kind with the grid's filters, the grouping as rows and the numbers as sums |
| The live mark | the binding it ticks from, the messages per minute and the latency |

**Raw JSON** is the full stored record, including the fields the grid has no column for, as the person may see it (a masked field shows as
masked and is copied as the mask), with **Copy**, the version and sequence. A page of a kind fed by several source systems says when one is
switched off: `excludes ENDUR (switched off)`.

### The page, step by step


Open a blotter from its module's menu (the trading module's is **Trading › Trade blotter**, `/live/trade`); any declaration has its page at `/live/<id>`.

1. **The bar.** The title, the live mark (`LIVE · 30 rows`; `PAUSED`, `RECONNECTING` and `ENDED` say what happened), **Group by** (one field; the
   declaration's `subtotals:` are summed per group and stay current as trades tick), **Pause** (rows that arrive are not applied; Resume reads
   the set again), **Pivot ›** and **My changes (n)**.
2. **Ticking.** A change to a trade arrives over the browser's one live channel and the changed cells flash; a replayed or older version is
   ignored. A set larger than the grid holds (5,000 rows) shows `5,000 of 120,000` and the server sends changes of the rows in view only.
3. **A record's panel.** Click a row. *Record*: the fields. With the edit power they are a form with a **Reason** (required or optional as the
   declaration says); without it they are plain text, with the words of why ("Read-only for you: ..."), and there is no edit control at all.
   Submit sends the change on the version you opened; the answer is in words (*waiting for 1 approver (four eyes)*, *on its way to the source*).
   If the trade moved meanwhile the panel says **Changed since you opened it**, and the server refuses an edit made on the old version, naming
   who changed it; **Reload** opens it again as it is now. *History*: every change with who, when, why and where it stands. *Raw JSON*: the whole
   stored record, including the fields the grid has no column for, collapsible, with **Copy**, the version and sequence; a field masked for your
   role shows as masked and is copied as the mask. The links (Explain, VaR, Limits) are the declaration's `links:`.
4. **My changes.** The tray lists your changes still open; click a trade to open it.
5. **Pivot ›.** Opens the Pivot on the same kind with the grid's filters, the grouping as rows and the numbers as sums, live when the trades are.



## 3. Editing: configuration and rights

Never everyone. A blotter is editable for a person only when **both** allow it, checked on the server for every write (the screen only
reflects it):

| | Read-only when |
|---|---|
| The site's configuration | the declaration names no `edit:`; write-back is off (`edit.enabled`); the site switched this blotter's edits off (its id in `drishti.live-sets.edits-off`); the connector's command is missing, refused or its writes are off (Admin → Edits) |
| The person's rights | no **edit** power; the kind is not theirs to open; none of the form's fields is editable by their roles (`roles:`, `editable-by:`) |

A person who may not edit sees no edit controls (`editable: false` and `readOnly: <why>` in the declaration the API gives them). A crafted
request is refused with `403 DRS-9302` and the reason, and audited (`edit-refused`). Four eyes apply on top. Finer entitlements (per desk,
per field) come with the security release.

## 4. Versions, conflicts, the outbox, four eyes and the audit

- **Versions.** The declaration's `version:` field is the guard. An edit carries `basedOn`, the version the person saw. If the record moved since,
  the answer is `409 DRS-9303` naming the version, who changed it and when; **Reload** opens it again. The same guard runs again when the change is
  applied at the source, so a change that raced another is refused there too.
- **Outbox.** The change is recorded as pending in the same transaction that applies it, sent once per record in order, and resent after a failover with
  the **same change id**; the source treats a repeat as already applied.
- **Four eyes.** The site's policy (`config/edit/policy.yaml`, `source:`) asks for approvals for amendments sent to a source; nobody approves their own
  change; a form's `approval:` may ask for more, never fewer ([APPROVALS_GUIDE.md](../../../docs/guides/APPROVALS_GUIDE.md)).
- **Audit.** Every submit, approval, refusal (`edit-refused`), send and result is audited with who, when, why and the values.
- **Write-back** goes to the record's own source system: section 8.

## 5. Row rules and per-person isolation

A role may restrict **which records of a kind** its holders see, by the record's fields and the person's attributes, in the configured roles:

```yaml
drishti:
  security:
    roles:
      rates-trader:
        kinds: [trade, position]
        edit: true
        rows:
          trade: "ASSET_CLASS = 'RATES' and DESK in $user.desks"
      endur-support:
        kinds: [trade]
        edit: true
        rows:
          trade: "SOURCE_SYSTEM = 'ENDUR'"
        edit-systems: [ENDUR]           # write-back only to Endur's records
```

A person sees a record when any of their roles that opens the kind passes it; a role without a rule for the kind opens every record of it; an
administrator sees all. `edit-systems` limits write-back to the listed source systems. Row rules restrict record views (live sets, search,
FORM, Raw JSON); aggregates such as VaR keep their own scope model. The rule is part of the viewer's audience, so two people with different
rules never share a filled set. Full text: [ACCESS_MODEL.md](../../../docs/architecture/ACCESS_MODEL.md) section 10. The rest of the isolation (masks, the
node answering, the embedding app) is in section 7, "Who sees what".

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

## 7. The engine: snapshot, deltas, scale, retention

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

## 8. Writing back to the record's own source

Edits go through the one write-back (../../../docs/guides/APPROVALS_GUIDE.md): the lane, the approvals, the **outbox** (the change recorded as pending in the
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
mode ([ELEMENTS.md](../../../docs/architecture/ELEMENTS.md) section 5.5).

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

## 11. Make a new blotter

A new blotter is one file. An inventory blotter, complete:

```yaml
# modules/stores/blotters/stock.blotter.yaml: a warehouse's stock, one row per bin and item
blotter: stock
title: Stock on hand
kind: stock                                   # a kind with a data contract (contracts/stock.contract.yaml)
query: "stock where QTY_ON_HAND > 0"
key: BIN_ITEM_ID
version: ROW_VERSION
changed-by: UPDATED_BY
changed-at: UPDATED_AT
columns:
- { field: BIN_ITEM_ID, label: Bin/item, type: id, width: 140 }
- { field: WAREHOUSE, label: Warehouse }
- { field: SKU, label: SKU }
- { field: QTY_ON_HAND, label: On hand, type: amount, format: amount0 }
- { field: REORDER_LEVEL, label: Reorder at, type: number }
- { field: STATUS, label: Status, type: status }
- { field: UPDATED_AT, label: Updated, type: datetime }
group-by: [WAREHOUSE]
subtotals: [QTY_ON_HAND]
sort: { field: UPDATED_AT, descending: true }
links:
- { label: Item, href: "/items/{SKU}" }
retention: { ttl: 2d, on: UPDATED_AT, max-rows: 200000 }
tray: true
edit:
  edit: cells
  roles: [stock-controller, admin]
  fields:
  - { id: id, label: Bin/item, bind: BIN_ITEM_ID, readonly: true }
  - { id: qty, label: On hand, bind: QTY_ON_HAND, type: number, min: 0, required: true }
  - { id: level, label: Reorder at, bind: REORDER_LEVEL, type: number, min: 0 }
  save: { target: source, command: adjust-stock, approval: none, reason: required }
```

Then the module lists it (`blotters: {folder: blotters, list: [stock]}` in `module.yaml`), the site binds the kind (`read:` the store, `live:` the
messaging connector) and gives the connector the `adjust-stock` command under `writes:` (section 8). No code is written: the page is at
`/live/stock`, the API at `/api/v1/live/stock/rows`, and the Pivot, the tray, the Raw JSON and the audit come with the engine.

## 12. Retention in every store

`retention:` bounds the **live side** (section 7). The durable stores keep their own: the in-memory lake's tables carry
`retention: {ttl: 1d, on: SOURCE_UPDATE_TIME}` and drop older rows at compaction; Delta and Iceberg tables drop whole partitions older than the
period the lake's retention names, one partition at a time; the file store prunes its dated folders. The three behave alike
([DATA_LAKE_GUIDE.md](../../../docs/guides/DATA_LAKE_GUIDE.md)).

## 13. Tutorial: a Kafka feed, a ticking blotter, an edit with four eyes, written back, Pivot

The worked example is the trading module's trade blotter on the demo data. Each step says what you do in the UI, from a terminal (CLI)
and in the file (YAML). `$B` is the server's `/api/v1`, `$DRISHTI_TOKEN` a token of a trader.

**Step 1 · Bind the feed.** *YAML*, `config/bindings.yaml`: the kind reads its snapshot from the lake and its changes from Kafka.

```yaml
bindings:
  trade:
    read: [trade-endur, trade-murex]
    union: true
    live: trade-kafka
```

*UI*: Admin shows the kind with its live source. *CLI*: `drishti.py module list | grep trading` shows the module on.

**Step 2 · Watch it tick.** *UI*: open **Trading › Trade blotter**; the live mark reads `LIVE`. Publish an amended trade to the topic and its row
turns amber and its changed cells flash. *CLI*:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$B/live/trade/rows?limit=3" | jq '{total, live, rows: [.rows[].id]}'
echo '{"TRADE_ID":"MX-20004163","TRADE_VERSION":8,"PRICE":101.25}' | kcat -P -b $BROKER -t trades.v1 -k MX-20004163
```

*YAML*: nothing more: the declaration's `columns:` and `sort:` decide what you see.

**Step 3 · Edit with a reason.** *UI*: click the trade; in the panel change Price, type the reason "booked at the wrong level: ticket 4411" and
**Submit**. The answer says *waiting for 1 approver (four eyes)* and the change appears in **My changes (1)**. *CLI*:

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' $B/live/trade/edit -d '{
  "entity": "MX-20004163", "basedOn": 8, "reason": "booked at the wrong level: ticket 4411",
  "items": [{"field": "PRICE", "from": 101.25, "to": 101.5}]}'
```

*YAML*: `save: {target: source, command: amend-trade, reason: optional}` in the declaration and `source: one` in `config/edit/policy.yaml`.

**Step 4 · Approve (another person).** *UI*: a second person opens **Approvals**, reads the old and new values and the reason, and approves; the
requester cannot (`self-approval: false`). *CLI*: the approvals commands of [APPROVALS_GUIDE.md](../../../docs/guides/APPROVALS_GUIDE.md). *YAML*: `approver-roles:` in the policy.

**Step 5 · Written back.** *UI*: the tray's line moves from *awaiting* to *sent to the source* to *applied*; the row ticks to version 9 when the
source's record comes back through the feed, and **History** in the panel lists who, when, why. *CLI*: the same guide lists a change's state.
*YAML*: the connector's `writes: amend-trade:` block (section 8).

**Step 6 · Pivot ›.** *UI*: group the grid by **Book** (the subtotals tick), then press **Pivot ›**: the Pivot opens on the same kind with the
filters, the grouping as rows and the quantities as sums, live while the trades are. *CLI*: none; the Pivot is a console page.

Pictures for the steps are listed in [section 14](#14-screenshots-to-add).

## 14. Screenshots to add

Not yet captured (they need a server with the demo stream): `live-blotter-grid` (step 2), `live-record-panel` (step 3), `live-approvals`
(step 4), `live-tray-applied` (step 5), `live-pivot-from-blotter` (step 6), `live-raw-json` (section 2).

## 15. The trading module

The module ships the trade blotter (`/live/trade`) and its binding; the steps below are specific to trades.

### 15.1 Switch it on (tutorial)

```bash
# 1. the module is on (Admin -> Modules, or the distribution lists it)
drishti.py module list | grep trading
# trading  1.0.0  first-party  content  on

# 2. the trade kind is bound with a live source (section 15.2), then the blotter answers
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" $B/live | jq '.sets[].id'
# "trade"
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "$B/live/trade/rows?limit=3" | jq '{total, live, rows: [.rows[].id]}'
# {"total": 4120, "live": true, "rows": ["MX-20004324", "MX-20004261", "MX-20004205"]}
```

### 15.2 Bind the trades

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

### 15.3 Edit a trade (tutorial)

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

## 16. Source systems

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
section 8), `mappings/<system>-trade.yaml` (the native fields to the canonical ones), `dataload.yaml` (the inbox routing rules, in the
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
