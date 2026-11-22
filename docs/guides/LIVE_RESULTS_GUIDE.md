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
inventory) is a configuration job, not a project. Drishti is the analytics layer next to a bank's systems: a blotter sends no orders. The engine is part of the platform, so FORM search, mnemonics
and blotters work in every distribution; the trade blotter and the source systems are the [trading module's](../../modules/trading/docs/guide.md).

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

## 1. One engine, and what uses it

A **live table** (Rachana's live tables, `rachana-live`, served on the Data Protocol at `/api/v1/rdp`) is the snapshot of a kind's records
from its durable store (Delta, Iceberg or files, through the bindings), plus the live changes of the kind's `live:` binding (Kafka,
RabbitMQ, ActiveMQ), merged **by key and version**, plus an optional edit path and an optional retention. Drishti's adapter
(`com.ash.drishti.server.live`) gives the engine its snapshots, streams, rights and write-back. What uses it:

| User | What it is |
|---|---|
| A blotter | a declaration (`<name>.live.yaml`) with columns, grouping, links and, optionally, edits |
| A FORM or the search page | the search compiled to a query on a table of the kind: a filter tree, an order and a limit; each row keeps the search's title (`_title`, shown as **Title** when it says more than the id) |
| A bare mnemonic (`POS <GO>`, `TRD`, `NEWS`) | the kind's own table; a default declaration is made from its contract; the page carries the as-of notices a search's answer carries (the sources answering from an earlier date) |
| A monitor, drill-through, a preview | the same table, opened from another page |
| A SQL or BI table | the static grid: the same component without the live channel |

The trading module's trade blotter is the first declaration ([the trading module](../../modules/trading/docs/guide.md)).

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
   ignored. A table larger than the grid holds (5,000 rows) shows `5,000 of 120,000`; a change of a row it does not hold moves the count and **N new**.
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
| The site's configuration | the declaration names no `edit:`; write-back is off (`edit.enabled`); the site switched this blotter's edits off (its name in `drishti.live-sets.edits-off`); the connector's command is missing, refused or its writes are off (Admin → Edits) |
| The person's rights | no **edit** power; the kind is not theirs to open; none of the form's fields is editable by their roles (`roles:`, `editable-by:`) |

**The edit power for trades** ships with the trading pack as the role **`trade-editor`** (`kinds: [trade]`, `edit: true`). The plain
`trader` role reads trades and never writes; give `trade-editor` beside it to the people who amend trades. The trade blotter's form
names it in `roles:`, so a trade-editor may change the book, price and quantity (the status stays with `middle-office` and `admin` by
`editable-by:`). A pack's role may carry `edit: true` like `approve:` or `calc:`; a site may still define its own roles in
`drishti.security.roles`.

A person who may not edit sees no edit controls (no `write` and `readOnly: <why>` in the schema the API gives them). A crafted
request is refused with `403 rdp.write.forbidden` and the reason, and audited (`write-refused`). Four eyes apply on top. Finer entitlements (per desk,
per field) come with the security release.

## 4. Versions, conflicts, the outbox, four eyes and the audit

- **Versions.** The declaration's `version:` field is the guard. An edit carries `basedOn`, the version the person saw. If the record moved since,
  the answer is `409 rdp.write.conflict` naming the version, who changed it and when; **Reload** opens it again. The same guard runs again when the change is
  applied at the source, so a change that raced another is refused there too.
- **Outbox.** The change is recorded as pending in the same transaction that applies it, sent once per record in order, and resent after a failover with
  the **same change id**; the source treats a repeat as already applied.
- **Four eyes.** The site's policy (`config/edit/policy.yaml`, `source:`) asks for approvals for amendments sent to a source; nobody approves their own
  change; a form's `approval:` may ask for more, never fewer ([APPROVALS_GUIDE.md](APPROVALS_GUIDE.md)).
- **One change id, end to end.** A write's `id` (the client's, 1 to 64 letters, digits, `-` or `_`) is the change's id in the write-back too: the
  approvals page, `GET /api/v1/changes/{id}`, the edit log and the source's message carry it. The same id again is answered with that change's
  answer (its state now: `held`, `queued`, `applied`, ...), never made twice, on any node and after a restart; from another person it is
  refused (`the change id ... is another's`).
- **Audit.** Every submit, approval, refusal (`edit-refused`), send and result is audited with who, when, why and the values. The person who made
  the change is the caller of every record: the table's (`live-write-submitted`, `live-write-held`, ... with the change id and its columns) and the
  write-back's (`edit-submitted` with the same id); a write the server cannot tie to a signed-in request is refused.
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
rules never share a filled set. Full text: [ACCESS_MODEL.md](../architecture/ACCESS_MODEL.md) section 10. The rest of the isolation (masks, the
node answering, the embedding app) is in section 7, "Who sees what".

## 6. The declaration

`blotters/<name>.live.yaml` in a module, or `config/blotters/` for a site's own: a Rachana live-table declaration
([LIVE_TABLES.md](https://github.com/drishti-platform/drishti-rachana/blob/main/docs/guides/LIVE_TABLES.md) section 2) with Drishti's profile
keys (`kind`, `links`, the edit's `roles`, `save` and `editable-by`). The trading module's
[`trade.live.yaml`](../../modules/trading/blotters/trade.live.yaml) in short:

```yaml
rachana: 1
live-table: trade                  # its name: /api/v1/rdp/tables/trade, the page /live/trade
title: Trade blotter
kind: trade                        # the kind it holds (its access, masks and row rules are the kind's)
snapshot: trade                    # the kind's durable records, through the bindings
stream: trade                      # the kind's live: binding (absent: a static table)
where: "@.TRADE_STATUS != 'CANCELLED'"   # the rows it holds (Rachana-EL over the row); absent: every record of the kind
key: TRADE_ID                      # the row's key: an edit names the row by it, so the edit form has no key field
version: TRADE_VERSION             # changes merge by it, and an edit on an older one is refused
changed-by: UPDATED_BY             # said when a stale edit is refused, with changed-at
changed-at: SOURCE_UPDATE_TIME
columns: [{name: TRADE_ID, label: Trade}, {name: PRICE, type: number, fmt: price4}, ...]   # type: string, integer, number, decimal, date, timestamp, list
subtotals: [QUANTITY]              # summed per group
sort: {col: SOURCE_UPDATE_TIME, dir: desc}
links: [{label: VaR, href: "/var?scope=book:{PORTFOLIO}"}]   # paths of the console, {FIELD} filled from the row
retention: {ttl: 1d, on: SOURCE_UPDATE_TIME, max-rows: 100000}   # the live side's age limit and row cap
tray: true                         # the person's pending changes, served as trade.changes
edit:                              # a Rachana form with save: (RACHANA_REFERENCE.md, forms): fields, checks, roles, where it goes
  edit: cells
  roles: [trade-editor, trader, middle-office, admin]
  fields: [{id: book, bind: PORTFOLIO, required: true, pattern: "BOOK-[A-Z0-9-]+"}, ...]
  save: {target: source, command: amend-trade, approval: none, reason: optional}
```

Every declaration is **checked at load**: Rachana checks its shape, its formats (each `fmt` a named format of `formats.yaml`, the bundled
one and the packs') and its edit form; Drishti adds that every column is in the kind's data contract (`contracts/<kind>.contract.yaml`
of the pack that owns the kind; a kind without a contract is checked for formats only), that a link is a path of this console, and that
an editable table's form says where its changes go (`save:`). A declaration with problems is set aside with every problem in words, listed
in Admin, and never served; the others load. The `edit:` form joins the Sutras' forms in the write-back under the name `live:<name>`, so
its checks, roles and approval rules are the write-back's own.

## 7. The engine: snapshot, deltas, scale, retention

One engine behind every blotter and every FORM result: Rachana's live tables, with Drishti's adapter (`com.ash.drishti.server.live`):

1. **Snapshot, paged from the store**: a kind a lake keeps as columns (Delta, Iceberg or files, through the bindings) is never read into
   memory (the source's tier `columns`). For each order, filter and audience the table keeps an *index*: the keys of the rows that hold the
   condition, in order, with the sort column's values, read in one projection of the columns it lists. A page fetches only its own rows
   whole, by id; the total is the index's count. The index is read again when the store's version moves (a load). Other kinds are read
   whole in one pass (tier `whole`), up to `drishti.live-sets.default-max-rows` rows (the table says `capped`). An order by a field the
   lake does not keep as a column (the trade blotter's `SOURCE_UPDATE_TIME`) keeps the lake's records in key order **after** the live rows
   that carry the field: in a newest-first order the new and changed trades come first. Promote the field to a lake column to order the
   lake's own records by it too.
2. **Live deltas**: the kind's `live:` binding, subscribed to **before** the snapshot is read; what arrives meanwhile is applied after it.
   Every change merges **by key and version**: a newer version replaces the row, an older or equal one is dropped, a deletion or a row that
   no longer holds the table's `where` leaves. A binding whose broker is absent is `paused` with its reason, and the table keeps serving its
   snapshot.
3. **Who sees what** (session isolation: nothing of any person is kept in a shared table): every request is read through the person's
   **view** (the authoriser: their kind access, their row rule as a test of each row, their masks; their audience is everything that
   decides what they see). A table's rows are shared, and **every read is kept to the reader**: pages, totals, each stream (a row outside
   their scope is never sent; one that leaves it is a removal to them), one record whole and an edit's row. A filter or an order on a
   column masked for the reader is refused; a masked value never leaves the server. Changes are coalesced per row and flushed every
   `flush-ms` (50 ms).
4. **Scale**: pages of at most 5,000 rows (`offset`, `limit`, `sort`, `filter`: a filter tree on the columns as the reader sees them).
   The console's grid follows a table on the browser's one live channel: every patch of the table reaches it, merged by key and version;
   a row it does not hold (it shows a page of a larger table) moves the count and a **N new** button that reads the newest page again.
   This is by design: a paged grid never jumps under the reader's eyes (a row inserted at the top would move the row they are reading);
   they read the newest page when they choose. A
   windowed stream of the protocol (`POST /changes` with `window`; the reader names the keys it shows and `top` when its page starts at the
   first row of a newest-first order) is sent only the changes of those rows, new and changed rows on top, and the count of the others. A
   reader more than `window-capacity` (5,000) rows behind is sent one `gap` instead of a queue (back-pressure: a burst never queues without
   bound). 10,000 updates apply in well under a second and reach a viewer coalesced. At 100,000 records (a narrow kind) the first page takes
   about half a second (the index read once), a first sorted page under a second, every later page and a page after a live change a
   millisecond or two (`LiveTablesAdapterTest`); memory holds the keys and the sort column, never the records.
5. **Retention**: `retention:` trims the live side every 30 s (older than `ttl` by its field, else by arrival; beyond `max-rows`, oldest
   first); the durable store keeps its own.
6. A table nobody has read for ten minutes is closed (its declaration stays; it is read again when asked).
7. **A search is a query on a table**: a FORM's result and the search page's (`GET /api/v1/rdp/searches?q=`) are the search compiled
   once (`SearchFilter`: the search grammar's condition, its field names spelt as the kind spells them, text equality ignoring case,
   amounts expanded) into a **filter tree** (PROTOCOL 7, through Rachana's `FilterTree`), an order and a limit, read from a table of the kind
   that holds the columns the search needs (declared on first use, `q-<hash>`, shared by everyone who searches over those columns on that
   day). The answer is the table, the query, the first page and the search's summary; the next pages and the changes are the protocol's
   (`POST /tables/{t}/query`, `/changes` with the same filter). The rows, their count and their order are the search's (`FormResultsParityTest`).
   A condition outside the tree's subset (`matches`, `size`, a link) stays in the table itself (its `where:`); a condition on a field masked
   for the person matches no row, as the search does. An id pattern (`TRD MX-2*`) is a condition on the key column.
8. **As of a business date**: on the live business date the table follows the kind's changes; on any other day it is a static snapshot
   of that day (the lake's time travel: the bindings read the kind as of the date, the table has no stream) and says `live: false`.
9. **Across Drishti*net**: when other members hold records of the kind for the person (the cluster routing's scatter targets), the search's
   table spans them: this node's rows, then each member's share of the search (asked as the person, one hop, within the scatter budget),
   merged by key, the higher row version winning a key two members hold; each member's changes arrive over `GET /api/v1/net/changes/{kind}`
   (its own records, masked there for the person). Such a table is read whole and is the person's audience's own, so isolation holds
   across the members.
10. **A bare mnemonic is the kind's table**: `POS <GO>` opens the kind's own table a page at a time (`GET /api/v1/rdp/kinds/POS` names
   it). A module's declaration of the kind is used when there is one; otherwise a default is made from the kind's contract (its key fields
   as columns, else its promoted columns; the record's id as the key; read-only). No YAML per mnemonic is needed.

## 8. Writing back to the record's own source

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

The Data Protocol ([PROTOCOL.md](https://github.com/drishti-platform/drishti-rachana/blob/main/spec/1/data-protocol/PROTOCOL.md)) at `/api/v1/rdp`,
as the signed-in person:

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/v1/rdp/tables` | the tables the caller may read |
| `GET` | `/api/v1/rdp/tables/{name}` | the schema for this reader: columns (masked ones marked), key, `rowVersion`, `view`, `live` (`up` or `paused` with why), `write` (the columns they may change, with their rules) or `readOnly` (why not), the profile (`kind`, `links`), `tray`, `excludes` (the trading systems switched off that feed the kind) |
| `POST` | `/api/v1/rdp/tables/{name}/query` | a page: `{columns?, filter?, sort?, limit?, offset?, group?}`; `{columns, values, total, version}` |
| `POST` | `/api/v1/rdp/tables/{name}/write` | a change: `{id, op: update, key, basedOn, set, from?, reason?}`; `{change, state, message}` (`held` while it waits for four eyes); `403 rdp.write.forbidden` read-only (why), `409 rdp.write.conflict` stale (who changed it), `422 rdp.write.invalid` a field rule |
| `GET`, `POST` | `/api/v1/rdp/changes` | server-sent events of a table (`table`, `after`, `filter`, `window`): `msg` frames with a patch (`reset`, `remove`, `update`, `add`, `total`, `top`), `gap` when the reader must read again |
| `POST` | `/api/v1/rdp/changes/{sub}/window` | the rows a windowed stream sends: `{keys, top}`; only its own reader |
| `GET` | `/api/v1/rdp/searches?q=` | a search as a query on a table (FORM, the search page), on the person's business date: `{table, query, live, page, search, asOfNotices}` |
| `GET` | `/api/v1/rdp/kinds/{word}` | the table of a kind by its mnemonic or name (`POS <GO>`): `{table, asOfNotices}` |

A read the source cannot answer is `503 rdp.unavailable`; its `message` ends with what the source said and `source` holds those words, made
safe (one line of at most 300 characters, a file path cut to its file name, a credential in a URL or a `password=`/`token:` value as `***`).

The console passes these on as the person (`/api/rdp/...`); the changes are the console's channel (`/api/channel?s=live:{table}`, a FORM's
result `live:{table}~{filter}`, events `msg` and `gap`). The page is `/live/{name}`; the module's menu entry (`console/plugin.yaml`) links
to `/live/trade`. The component is the grid's live mode over the Data Protocol
([ELEMENTS.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/DESIGN.md) section 5.5), mounted by `js/drishti_records.js`.

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `drishti.live-sets.flush-ms` | `50` | how often viewers' windows are sent their changes |
| `drishti.live-sets.window-capacity` | `5000` | records waiting per viewer before it is asked to resync |
| `drishti.live-sets.dir` | `config/blotters` | the site's own declarations (`*.live.yaml`) |
| (none) | | the modules' `blotters/` folders are read from each module's folder as the module boot gave it (a module's only while it is on) |
| `drishti.live-sets.edits-off` | (none) | the blotters whose edits the site switched off, by id (`trade,exposure`) |
| `drishti.live-sets.default-max-rows` | `250000` | the most records a kind's default set (a bare mnemonic) reads into memory when its store cannot page it |
| `drishti.live-sets.store-timeout-seconds` | `120` | how long a set paged from the lake may take to read the columns it orders by, and a page's records |

Every one is in [CONFIGURATION.md](../admin/CONFIGURATION.md) (`drishti.live-sets`), generated from the server's `application.yaml`.

## 11. Make a new blotter

A new blotter is one file. An inventory blotter, complete:

```yaml
# modules/stores/blotters/stock.live.yaml: a warehouse's stock, one row per bin and item
rachana: 1
live-table: stock
title: Stock on hand
kind: stock                                   # a kind with a data contract (contracts/stock.contract.yaml)
snapshot: stock
stream: stock
where: "@.QTY_ON_HAND > 0"
key: BIN_ITEM_ID
version: ROW_VERSION
changed-by: UPDATED_BY
changed-at: UPDATED_AT
columns:
- { name: BIN_ITEM_ID, label: Bin/item, width: 140 }
- { name: WAREHOUSE, label: Warehouse }
- { name: SKU, label: SKU }
- { name: QTY_ON_HAND, label: On hand, type: number, fmt: amount0 }
- { name: REORDER_LEVEL, label: Reorder at, type: number }
- { name: STATUS, label: Status }
- { name: UPDATED_AT, label: Updated, type: timestamp }
group-by: [WAREHOUSE]
subtotals: [QTY_ON_HAND]
sort: { col: UPDATED_AT, dir: desc }
links:
- { label: Item, href: "/items/{SKU}" }
retention: { ttl: 2d, on: UPDATED_AT, max-rows: 200000 }
tray: true
edit:
  edit: cells
  roles: [stock-controller, admin]
  fields:
  - { id: qty, label: On hand, bind: QTY_ON_HAND, type: number, min: 0, required: true }
  - { id: level, label: Reorder at, bind: REORDER_LEVEL, type: number, min: 0 }
  save: { target: source, command: adjust-stock, approval: none, reason: required }
```

Then the module lists it (`blotters: {folder: blotters, list: [stock]}` in `module.yaml`), the site binds the kind (`read:` the store, `live:` the
messaging connector) and gives the connector the `adjust-stock` command under `writes:` (section 8). No code is written: the page is at
`/live/stock`, the table at `/api/v1/rdp/tables/stock`, and the Pivot, the tray, the Raw JSON and the audit come with the engine.

## 12. Retention in every store

`retention:` bounds the **live side** (section 7). The durable stores keep their own: the in-memory lake's tables carry
`retention: {ttl: 1d, on: SOURCE_UPDATE_TIME}` and drop older rows at compaction; Delta and Iceberg tables drop whole partitions older than the
period the lake's retention names, one partition at a time; the file store prunes its dated folders. The three behave alike
([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md)).

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
requester cannot (`self-approval: false`). *CLI*: the approvals commands of [APPROVALS_GUIDE.md](APPROVALS_GUIDE.md). *YAML*: `approver-roles:` in the policy.

**Step 5 · Written back.** *UI*: the tray's line moves from *awaiting* to *sent to the source* to *applied*; the row ticks to version 9 when the
source's record comes back through the feed, and **History** in the panel lists who, when, why. *CLI*: the same guide lists a change's state.
*YAML*: the connector's `writes: amend-trade:` block (section 8).

**Step 6 · Pivot ›.** *UI*: group the grid by **Book** (the subtotals tick), then press **Pivot ›**: the Pivot opens on the same kind with the
filters, the grouping as rows and the quantities as sums, live while the trades are. *CLI*: none; the Pivot is a console page.

Pictures for the steps are listed in [section 14](#14-screenshots-to-add).

## 14. Screenshots to add

Not yet captured (they need a server with the demo stream): `live-blotter-grid` (step 2), `live-record-panel` (step 3), `live-approvals`
(step 4), `live-tray-applied` (step 5), `live-pivot-from-blotter` (step 6), `live-raw-json` (section 2).
