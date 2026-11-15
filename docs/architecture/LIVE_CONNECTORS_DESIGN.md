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
# Live connectors: a live companion for any data connector (design, decided; building in i5)

**Status: design for release i5, decided by the product owner (section 15); steps L1 to L10 are built in i5. Nothing in this
document is built unless a section says "today".** It answers the product owner's request: *"for all data connectors there can be a
live connector, generally speaking."*

One sentence: **every kind keeps its dated record in a lake; any kind may also have a live feed, declared the same way for every kind,
whose messages are checked against the kind's contract and merged over the lake's snapshot by key and order, and which the next lake
load absorbs, so nothing is ever counted twice.**

What this document does not repeat (one home per fact):

| Fact | Its home |
|---|---|
| The live result-set engine, blotter declarations, the page, edits, the API | [LIVE_RESULTS_GUIDE.md](../guides/LIVE_RESULTS_GUIDE.md) |
| How a tick reaches a browser (SSE, frames, deleted entities), live BI tables | [DATA_ARCHITECTURE.md section 1](DATA_ARCHITECTURE.md#1-live-updates) |
| Bindings (`read`, `union`, `live`, `write`), templates, overrides, resolution | [KERNEL_AND_MODULES.md sections 5 and 18](KERNEL_AND_MODULES.md#5-data-needs-and-connector-binding) |
| The Kafka, ActiveMQ and RabbitMQ transports and their settings | the plugin READMEs under `plugins/drishti-plugin-*` |
| Intraday VaR (what the engine does with an intraday batch) | [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes) |
| A stream connector without its broker (WAITING) | [OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md) (*Stream connectors without their broker*) |

## Contents

1. [Where we are today](#1-where-we-are-today)
2. [The general model](#2-the-general-model)
3. [A live message and the kind's contract](#3-a-live-message-and-the-kinds-contract)
4. [Order, duplicates and late events](#4-order-duplicates-and-late-events)
5. [Business dates: the live date and the lake's snapshots](#5-business-dates-the-live-date-and-the-lakes-snapshots)
6. [End of day: the next lake load absorbs the live state](#6-end-of-day-the-next-lake-load-absorbs-the-live-state)
7. [Where it plugs in](#7-where-it-plugs-in)
8. [Kind by kind](#8-kind-by-kind)
9. [Admin and what people see](#9-admin-and-what-people-see)
10. [Migration: one machinery](#10-migration-one-machinery)
11. [Tests](#11-tests)
12. [Performance targets](#12-performance-targets)
13. [Build plan](#13-build-plan)
14. [Risks](#14-risks)
15. [Decisions](#15-decisions)

## 1. Where we are today

Five pieces of live machinery exist. Each works; together they are not one way per job.

| # | Piece (today) | What it does | What is particular to it |
|---|---|---|---|
| 1 | `kafka` plugin (`KafkaSourcePlugin`) | latest message per key from topics; `state` mode (the topic is the store) or `ticks` mode (a change feed over a lake); `subscribe` per entity, `subscribeKind` for a live set | its own index and caches; no validation against the contract; order is the offset |
| 2 | `activemq`, `rabbitmq` plugins (shared `MessageStateSource`) | each message kept in a RocksDB state store, then acknowledged | a second state implementation; the store is the only copy |
| 3 | Six `trade-<system>-live` templates (`modules/source-*/connectors/`) | live trades per source system, and the blotter's write-back (`writes: amend-trade`) | trades only; the trade rules (key, version field) are repeated in each file and in `trade.blotter.yaml` |
| 4 | `var-pv-stream` plugin (`VarPvStreamPlugin`, banking) | reads the PV topic itself, collects a five-minute window, writes one intraday batch to the lake, raises the data-load signal | its own Kafka reader, window, rejections list, admin API, settings (`drishti.var.intraday.kafka.*`) |
| 5 | BI live tables (`mode: live`) and `materialize: {live: …}` | an in-memory table per dataset, fed per entity through `subscribe` | a third copy of live rows |

Shared and kept as they are: the live result-set engine (`liveset`: snapshot paged from the store, live side merged by key and version,
every read kept to the reader), the `live:` binding, the calm WAITING state (`BrokerWaiting`), the browser's one live channel.

What is missing for "any kind": one declaration of what a live message *means* for a kind; validation and a place for rejects; a rule
for business dates; a rule for the next lake load; one state; one admin surface.

## 2. The general model

### 2.1 Three parts, each with one home

| Part | What it says | Home | Owner |
|---|---|---|---|
| **The kind's live rules** | what a live message means: order field, deletes, partial updates, how it is applied, how the lake absorbs it | a `live:` block in the kind's contract (`contracts/<kind>.contract.yaml`) | the module that owns the kind |
| **The live companion** | transport only: plugin, broker, topics or queues, message shape, credentials | a connector file like any other (a module's template, a site's override) with `companion-of:` | the site (the template travels with the module, switched off) |
| **The binding** | which companions feed the kind at this site, and the switch | `live:` of the kind in `config/bindings.yaml` (it exists today) | the site's data administrator, four eyes |

The contract block names no broker, the connector file names no business rule, the binding names connectors only. A module still
starts with no externals: every companion template ships `enabled: false`, and a companion that is on without its broker is WAITING.

### 2.2 The companion

**Any data connector may have one live companion.** The companion is a connector on a messaging plugin (`kafka`, `activemq`,
`rabbitmq`; later others, section 2.5) that names the data connector it accompanies:

```yaml
# modules/market-data/connectors/market-store-live.yaml   (a template; off as shipped)
name: market-store-live
plugin: kafka
enabled: ${DRISHTI_LIVE_MARKET:false}
companion-of: market-store                 # the data connector whose rows this feed changes
kinds: [fx-spot, equity, ir-curve]         # a subset of the data connector's kinds; absent: all of them
settings:
  bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP}      # no default host: without it the companion is "not configured"
  topics: { each-kind: "${DRISHTI_LIVE_TOPIC_PREFIX:drishti.live}.{kind}" }   # one topic per kind, or topics: [one, two] with shape: envelope
  shape: document                          # document | envelope | jsonl (section 3.1)
```

Rules:

- The name is `<data connector>-live` by convention; `companion-of:` is what the server reads. A companion of a connector that is off
  or absent is not used (the existing DRS-1046 wording), so a source module switched off takes its live rows with it.
- A companion serves the kinds of its data connector and no others. In a union (trades from six systems) each system has its own
  companion, and its rows shadow only that system's rows.
- A companion holds nothing a kind's contract should say. The settings `id-field`, `version-field`, `kind` + key rules of today's
  templates move to the contract (section 10).
- Explicit paths: every file a companion names (a mapping, a truststore) is absolute or anchored to a named root (`${module.dir}`,
  `${drishti.data.dir}`); the path guard covers the new keys.
- A kind with **no lake** (a result a streaming producer publishes, [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#results-computed-by-a-streaming-producer))
  has a live connector without `companion-of:`. It is the same machinery with an empty snapshot.

### 2.3 The contract's `live:` block

```yaml
# contracts/trade.contract.yaml (addition)
live:
  allowed: true                  # false: the kind never takes a live feed (official results, anything signed off)
  order: [TRADE_VERSION, SOURCE_UPDATE_TIME]   # what makes one message newer than another, most significant first (section 4)
  changed-at: SOURCE_UPDATE_TIME # the event time: latency, late events, the cut-off of a lake load
  changed-by: UPDATED_BY
  as-of: BUSINESS_DATE           # the field that carries the business date; absent in a message: the live date (section 5)
  partial: whole                 # whole | merge (section 3.3)
  delete:                        # besides a tombstone, which every kind accepts
    when: null                   # a condition in the search grammar, for sources that cancel with a record (PVs: section 8.4)
  apply: overlay                 # overlay | land | both (section 2.4)
  conflate: none                 # none | a period (1s): keep the latest per key per period before applying (market data)
  absorb: by-order               # by-order | by-cutoff | reset (section 6)
  retention: { ttl: 2d, max-rows: 2000000 }    # the bound of the live state of this kind
  validate: strict               # strict | lenient | deferred (section 3.4)
```

The key is the contract's `key:` (it exists). Every field the block names is checked to be in the contract when the pack loads, the way
a blotter declaration is checked today. `binding-hint.live: optional` (today's hint) is replaced by `live.allowed`.

A kind without a `live:` block may still be fed (the owner's "any kind"): the defaults are `order:` arrival, `partial: whole`,
`apply: overlay`, `absorb: reset`, `validate: strict`. They are the safe ones: whole records, cleared at the next load.

### 2.4 Two ways to apply, chosen per kind

| | `overlay` | `land` |
|---|---|---|
| Where a message goes | the **live state**: the newest row per key, in memory (bounded), on the node | a **window** (the latest row per key for a few minutes), then one batch appended to the kind's lake table, then the data-load signal |
| Who sees it | every read of the live date, merged over the lake's row by key, within a frame | whatever reads the lake: engines by the signal, pages at their next read |
| Latency | milliseconds | the window (default 5 minutes; "apply now" for an administrator) |
| The record | the lake stays the record; the live state is absorbed by the next load | the lake is the record at once (rows marked `RUN_KIND: INTRADAY`, their own `BATCH_ID`) |
| For | rows people watch: trades, quotes, curves, netting sets, limits, reference data | heavy inputs of an engine: scenario PVs, P&L vectors, sensitivities |
| Today | the `kafka`, `activemq`, `rabbitmq` plugins and the live result-set engine | `var-pv-stream` |

`both` overlays at once and lands by window: for a kind fed by a queue that keeps no history, where a restart must not lose the day
(the lake replaces today's RocksDB state store as the durable copy). It is never a contract's default for an `overlay` kind: a site
asks for it in the kind's binding (`live: {apply: both}`), decision Q7.

Landing writes through the lake writer, so it is the same on Delta, Iceberg and the file store (JSONL in the dated folder). A landed
table is append-only within a date; its current row per key is the newest by the contract's `order:`; a full load of the date replaces
the date and its intraday batches. That is today's scenario-PV behaviour, stated once for every kind.

### 2.5 Transports

A transport plugin does four things: connect (or WAIT), receive, hand each message to the kernel's **live intake** with its transport
position (topic, partition, offset; or delivery tag), and acknowledge when told. Everything else (decode, map, validate, order,
apply, notify, health, rejects) is the kernel's, written once.

| Transport | Replay at start | Order it gives | Consumed by (section 7.8) | Status |
|---|---|---|---|---|
| Kafka | yes (from the beginning, no group) | per partition | every placed node | exists; becomes a thin transport |
| ActiveMQ / JMS | no (queue), durable topic | per destination | one node (singleton) | exists; becomes a thin transport |
| RabbitMQ | no (queue); yes (a stream) | per queue | one node (singleton) | exists; becomes a thin transport |
| Later: Solace, IBM MQ, Azure Event Hubs / AWS Kinesis, a WebSocket or SSE feed, a watched folder of JSONL | varies | varies | by `replayable:` of the plugin | not planned (decision Q9: no new transports); the transport SPI is the only work when one is wanted |

## 3. A live message and the kind's contract

### 3.1 Three shapes, one canonical record

Inside the server every message becomes one **live record**: `{kind, key, op, order, changedAt, asOf, source, doc}`.

| `shape:` | The message | Where the fields come from |
|---|---|---|
| `document` | the value is the kind's row (one JSON object: a JSONL line) | kind from the topic; key from the contract's `key:` fields; order, time and date from the fields the `live:` block names; a null value is a delete |
| `envelope` | `{"kind", "key", "op", "asOf", "doc"}`; many kinds on one topic | from the envelope; missing parts as for `document` |
| `jsonl` | the value is JSONL text: several rows, each as `document` | per line (today's PV topic) |

Payloads are JSON (JSONL is the data standard); Avro and JSON Schema through a schema registry are decoded to JSON by the transport as
today. The connector's existing `where` / `set` / `map` keys (a source system's own field names into the kind's) run before validation,
exactly as they do for a lake load: one mapping, two paths.

**The key.** The contract's `key:`; several fields are joined with `|`. A Kafka message key, when present, must equal it (compaction
and tombstones depend on it); a mismatch is a reject. A tombstone is identified by the message key alone.

### 3.2 Operations

| `op` | Meaning | Shape that carries it |
|---|---|---|
| `upsert` (default) | the whole row as it is now | any |
| `delete` | the row is gone | a null value (tombstone), envelope `"op": "delete"` or `"doc": null`, or a row that holds `live.delete.when` |
| `patch` | some fields changed (only when `partial: merge`) | envelope `"op": "patch"`, or a `document` on a kind whose block says `partial: merge` |
| `replace` | a whole set is replaced: every row of a scope, bracketed by begin and end | envelope only (section 3.5) |

A cancelled trade is normally an `upsert` with a status, not a delete: the record still exists, and a blotter's `query:` decides whether
it shows. Deletes are for rows that should no longer be found.

### 3.3 Partial updates

Default `partial: whole`: every message is the complete row ("state, not deltas", the rule the Kafka connector documents today).
Whole rows make order trivial: the newest wins and a missed message costs nothing.

`partial: merge` is for rows where a tick is small and the row is large (an FX spot with its history series, an equity with its
fundamentals):

- Fields present replace; fields absent stay; an explicit `null` clears; arrays and nested objects are replaced whole (JSON Merge Patch,
  RFC 7386). No arithmetic deltas (+1 / −1) are accepted: a producer turns them into state.
- The base is the live state's row, else the lake's row of the base date read once by key. No base: the patch is rejected
  (`patch-without-base`) unless it carries every required field, in which case it is an upsert.
- A patch must be newer than its base by `order:`. When the order is a version number, a gap (base 7, patch 9) marks the row
  **incomplete**: it is shown with its last complete state and a small mark, and is healed by the next whole row or, after
  `reorder-window` (2 s), by reading the base again and applying the patches held, in order.
- The merged row is validated, not the patch.

### 3.4 Validation and rejects

A live message is held to the same contract as a loaded file, by the same validator: required fields, types, enums, patterns, the key.

| `validate:` | When | For |
|---|---|---|
| `strict` (default, decision Q8) | on arrival; a failing message is rejected, the row keeps its last good state | everything, unless measured too slow |
| `lenient` | on arrival; missing optional structure and unknown fields pass, type faults are rejected | a source mid-migration |
| `deferred` | key and order on arrival; the body when the row is first read or landed | very large keyed topics (today's cheap path: a keyed message is indexed without parsing) |

A reject is never an error on anybody's page. It goes to three places:

1. **The reject log**: JSONL, one line per message with the reason, the transport position and the payload, at
   `${drishti.data.dir}/live/rejects/<companion>/<business date>.jsonl`, kept `drishti.live.rejects.keep-days` (7).
2. **The counters** of the feed (per reason) in Health and Admin, with the newest 20 in the Admin panel (the pattern of today's PV
   rejections list). Payloads are shown to data administrators only, masked like any record.
3. Optionally **a dead-letter destination** on the same broker (`rejects: {forward: <topic or queue>}`), for the source's own team.

| Reason | Example |
|---|---|
| `not-json`, `no-key`, `key-mismatch` | the message key says `MX-1`, the row says `MX-2` |
| `contract` | a required field is missing; `PRICE` is text |
| `unknown-kind` | an envelope names a kind the companion does not serve |
| `closed-date`, `future-date` | section 5 |
| `patch-without-base`, `replace-incomplete` | sections 3.3, 3.5 |
| `not-allowed` | the contract says `live.allowed: false` |

A feed whose rejects pass `rejects.max-ratio` (5% over five minutes) is DEGRADED (amber) and keeps running. Error codes for these are
allotted at step L1; the reasons above are their names.

### 3.5 Replacing a whole set

For reference data republished whole, or a book's positions restated by its source:

```json
{"kind":"counterparty","op":"replace-begin","batch":"R-2026-11-16-01","scope":"all"}
{"kind":"counterparty","key":"CP-MERIDIAN-RE","batch":"R-2026-11-16-01","doc":{…}}
{"kind":"counterparty","op":"replace-end","batch":"R-2026-11-16-01","rows":18422}
```

The rows are held aside and applied **at the end marker, all at once**: every row of the scope not in the batch becomes a live delete.
`scope` is `all` or a condition on promoted fields (`"PORTFOLIO = 'BOOK-COMM-3'"`). A batch whose end does not arrive within
`replace.timeout` (10 m), or whose count differs, is discarded whole and rejected as `replace-incomplete`; the state before it stands.

## 4. Order, duplicates and late events

Every transport delivers at least once and, across partitions or redeliveries, not always in order. The intake is therefore
**idempotent by key and order**, the rule the live result sets already keep.

**The order of a record** is a tuple, compared left to right: the business date, then the fields of `live.order` (a version number, an
event time), then the transport position as a tie-break. With no `order:` in the contract it is arrival.

| A record that is… | What happens | Counted as |
|---|---|---|
| newer than the row held | replaces it; subscribers are told | `applied` |
| equal (a redelivery, a replay at restart) | dropped | `duplicate` |
| older (out of order, late) | dropped: the row held is already newer and whole | `late` |
| older, on a `partial: merge` kind | held up to `reorder-window` for the gap to fill (section 3.3) | `reordered` or `late` |
| older than the lake's row of the same key | dropped: the load already has it or better | `absorbed` |
| a delete older than the row held | dropped | `late` |
| a delete, then an older upsert | dropped: the delete is remembered (a **tombstone** in the state) until it is absorbed or its retention ends | `late` |

Notes:

- **A version number is the only order that survives everything** (two systems' clocks, a replay, a failover). A kind ordered by event
  time alone is correct to the clock of its source; a kind ordered by arrival is correct only within one partition. The Admin panel
  says which order a feed has, in words.
- **Kafka**: order is per partition, so a producer keys by the contract's key. The intake does not depend on it when the contract has
  an order field.
- **Queues**: a message is acknowledged after it is applied (and, for `land` or `both`, after its window is written), so a crash
  redelivers and the duplicate rule drops what was already applied.
- **Counts are per feed and per kind** and are shown; `late` and `duplicate` are normal in small numbers and are not faults.

## 5. Business dates: the live date and the lake's snapshots

The lake holds one snapshot of a kind per business date. Live rows belong to **one** date at a time:

- **The live date** of a kind is the current business date of its calendar, rolling at `drishti.live.roll-at` in the calendar's zone
  (the business-date rules of ADR 012).
- **The base** is the lake's newest snapshot of the kind on or before the live date. In the morning it is usually yesterday's close;
  after the day's first load it is today's.
- **Live** (what `AsOf.LATEST` answers) **= the base, with the live state over it by key.** Every screen that shows it says so in the
  same words: `LIVE · base 13 Nov close + 412 changes · last 10:42:03`.
- **A picked business date never includes live rows.** A dated read is the lake's snapshot, as today. Yesterday as it was at 15:00 is
  not a question the live state answers; landed kinds keep their intraday batches in the lake, which is the place for that history.

A record's date is its `live.as-of` field, else the envelope's `asOf`, else the live date.

| The record's date | What happens |
|---|---|
| the live date | applied |
| earlier (a closed date) | `overlay`: rejected `closed-date`. `land`: appended to that date as a restatement only when the contract says `closed-dates: restate` and the date is not signed off; otherwise rejected. `closed-dates: restate` is allowed on `scenario-pv` and `pnl-vector` only (decision Q12), checked when the pack loads |
| the next business date, before the roll | held (bounded, `next-date.max-rows`) and applied at the roll |
| later than that | rejected `future-date` |

**At the roll** the live rows not yet absorbed stay, still shadowing the base, until a load absorbs them or their retention ends
(section 6); they are shown as *carried from 13 Nov*. Nothing is copied forward into the lake by Drishti: an `overlay` kind's history is
what its source delivers. **Intraday history is the publisher's job** (decision Q7): a source that wants the day's changes kept
publishes them to its own store or extract; Drishti lands an `overlay` kind (`apply: both`) only where a site's binding asks for it.

## 6. End of day: the next lake load absorbs the live state

**The invariant: a row exists once per key.** The live state never *adds* rows to the lake's; it *shadows* them by key, and a read
takes each key from exactly one side. That holds because the live key is the contract's key (a mismatch is a reject, section 3.1) and
because there is one read path (section 7.1). Totals, subtotals and every additive measure over a live read are therefore exact
before, during and after a load.

When a load of the kind arrives (the data-load signal, or the store's version moves), each row of the live state is **reconciled**
against the new snapshot, by the contract's `absorb:` rule:

| `absorb:` | The rule | For |
|---|---|---|
| `by-order` | per key: the lake's row is as new or newer by `order:` → the live row is dropped (**absorbed**); the lake's is older → the live row stays (**carried**: it arrived after the extract) | kinds with a version or event time that the lake keeps as a column |
| `by-cutoff` | the load states its cut-off (the extract time, in the load's `.ready` or manifest; else the commit time less `settle`, 5 m): live rows changed on or before it are dropped, later ones carried | kinds with an event time the lake does not keep |
| `reset` | the live state of the kind is cleared; the feed's position is remembered so a replay does not bring it back | kinds with no order; the safe default |

The cases that need a decision, and their defaults:

| Case | Default | Why |
|---|---|---|
| A live row whose key is **not in the load**, changed before the cut-off | dropped, reported as an **orphan** (decision Q3) | the source's own extract says it does not exist |
| The same, changed after the cut-off | carried | booked after the extract |
| A live **delete** whose key is in the load, older than the delete | still hidden (carried) | deleted after the extract |
| A live delete older than the load's row | dropped: the row shows again | the source brought it back |
| A row carried past its retention without being absorbed | removed, counted `expired-unabsorbed`, amber in Health | a feed and a load that never agree is a data problem to see, not to hide |

**Replay must not undo a reconcile.** A Kafka companion reads its topic from the beginning at every start. `by-order` needs nothing
more (the lake's row wins on order). For `by-cutoff` and `reset` the feed keeps a **checkpoint** per kind (the transport positions and
the cut-off absorbed up to) in the Drishti database, and the intake drops what lies before it.

**Every reconcile leaves a record**: kind, load, counts (absorbed, carried, orphans, deletes carried, lake rows new to the day), the
first 100 orphans as JSONL at `${drishti.data.dir}/live/reconcile/<kind>/<date>.jsonl`, an audit entry, and a line in the Admin
panel. An orphan count over `reconcile.orphan-alert` raises a platform alert.

**Landed kinds** need no reconcile: their intraday batches are in the lake, the newest row per key wins by `order:`, and a full load of
the date replaces the date.

## 7. Where it plugs in

```
 broker ──► transport plugin ──► LIVE INTAKE (kernel, one) ──► LIVE STATE per kind (overlay) ──► the one read path ──► everything
            connect / WAIT        decode · map · validate       newest row per key, tombstones     lake row ⊕ live row by key
            receive · ack         order · conflate              bounded, shared, unfiltered        (reader's rules applied at exit)
                                         │
                                         └──► LIVE LANDER (windows) ──► lake writer ──► Delta / Iceberg / files ──► data-load signal ──► engines
```

### 7.1 The source router and union reads

`SourceRouter` is the one read path, and stays it.

| Read | Today | Proposed |
|---|---|---|
| One entity, Live | live sources first, then the stores | the live state's row if it is newer than the lake's by `order:`, else the lake's; the provenance names both (`risk lake 13 Nov + trade-endur-live v9 10:42:03`) |
| One entity, a date | the stores | unchanged |
| A kind's rows, Live (live sets, BI, pivots, export, Flight) | the store's pages with the set's own live side | the store's pages merged with the **shared** live state by key (the `StorePages` merge, moved below the set so every consumer has it) |
| A union of connectors | each system's rows | each system's rows with **its own companion's** live rows; a system switched off drops both |
| `subscribe(entity)`, `subscribeKind(kind)` | from the plugin | from the live state's change stream (one implementation for every transport) |

A consumer never reads a broker, a companion or the live state directly, and never adds "lake rows + live rows" itself: a guard test
forbids the live state's API outside the router.

### 7.2 The live result-set engine

Unchanged in what it shows and in its API. Two things move below it:

- **The live side becomes shared.** Today each open set keeps the records changed *since it opened*. With the live state, a set is the
  store's index plus the keys of the live state that hold its query: a blotter opened at 11:00 shows the amendment of 09:00, two
  blotters over one kind hold the rows once, and a set that closes loses nothing.
- **The rules a declaration repeats come from the contract.** `key:`, `version:`, `changed-by:`, `changed-at:` of a blotter default to
  the contract's `key:` and `live:` block; a declaration states them only to differ. The trade blotter loses four lines.

`retention:` of a declaration keeps its meaning for the set's view; the live state's own bound is the contract's (section 7.6).

### 7.3 Pivots and BI: snapshot by default, live by choice

A pivot over 1.3 million positions should not recompute on every tick, and a signed-off report must not move. So:

Decided (Q4): snapshot by default, live by choice.

| Level | What it is | When |
|---|---|---|
| **Snapshot** (default) | the pivot or report reads the base; a chip says `snapshot 13 Nov close · 412 newer changes · Refresh` | every pivot and report unless it asks otherwise |
| **Live read** | the visual reads through the live read path and is asked again on a tick (today's BI `mode: live`: the tick carries no data, each reader's query applies their own rules) | a dataset table with `mode: live`, a blotter's **Pivot ›**; kinds under `drishti.pivot.live.max-rows` (250,000) |
| **Incremental** (later, step L9) | additive measures (sum, count) updated by the difference of each changed row, the way the VaR intraday layer adjusts its nodes | large kinds, additive measures only; never VaR, ES or any non-additive measure |

BI's own in-memory live table and `materialize: {live: …}` are replaced by the live read (section 10): one copy of live rows.

### 7.4 Record pages

A record page opened Live is the base's row with the live row over it, ticking as today. Additions: the provenance names base and
feed; a **Changes today** list (the versions the live state saw, when `live.keep-versions` > 1; default 1) beside History, which stays
the lake's; the deleted banner as today; an *incomplete* mark for a patched row with a gap. A page of a picked date is the lake's and
says `snapshot`.

### 7.5 Computed measures: one transport, the engines stay engines

Generalise the plumbing, keep the arithmetic where it is.

- **Generalised (kernel):** reading the broker, the window, the latest row per key, the write to the lake, the signal, the status,
  "apply now", rejections. That is the live lander; `var-pv-stream`'s own copy of it goes (section 10).
- **Kept (the engine):** what an intraday batch *means*. The VaR engine's `VarIntraday` (the difference of each changed trade added
  to its nodes, the morning run's FX, the label *intraday · 5 trades*) is unchanged and still hears the data-load signal.
- **One rule for every engine:** an engine consumes kinds, never brokers. It declares in its `config/engines/<engine>.yaml` what it
  does with live input: `live: off | landed | overlay`.

| Engine | `live:` | Meaning |
|---|---|---|
| VaR | `landed` (today's behaviour) | recomputes incrementally when scenario PVs or P&L vectors land |
| Derived positions | `overlay` (new, step L8) | a position's quantity follows its trades as they tick; its VaR follows the PVs when they land |
| Exposure (EE, PFE) | `off` | the official end-of-day result; live netting-set MTM and collateral are shown beside it, never mixed into it |
| FRTB SA, SIMM, sensitivities | `off` now; `landed` when a site feeds sensitivities intraday | the same lander, no new machinery |

Computed kinds (`position`, `var-result`, `exposure-result`) have no companion: they are live *by derivation* from their inputs. A
figure that includes live input always says so (*intraday*, *live*), and official figures never do.

**Live quotes never move VaR, exposure or P&L** (decision Q6). A live price, curve or surface changes what a record page, a blotter and
a market monitor show; valuations follow their own inputs (scenario PVs, P&L vectors, the morning run's market data), and no engine
reads market data from the live state. A guard test holds it: no engine's `live:` is `overlay` on a market-data kind.

### 7.6 Retention and TTL

| Layer | Bound | Default | When it is full |
|---|---|---|---|
| The live state of a kind | `live.retention` of the contract (`ttl`, `max-rows`), a site cap `drishti.live.state.max-mb` per kind and in total | 2 d, 2 M rows, 2 GB | absorbed rows first, then the oldest; an unabsorbed row removed is counted and amber. Documents past `cache-mb` are kept as a pointer and read back (Kafka: by offset; else the disk cache) |
| The landed batches | the lake's own retention, per date | the lake's | the three stores behave alike ([LIVE_RESULTS_GUIDE.md section 12](../guides/LIVE_RESULTS_GUIDE.md#12-retention-in-every-store)) |
| The reject log, the reconcile records | `keep-days` | 7, 30 | oldest files removed |
| A live set | its declaration's `retention:` | as today | as today |

### 7.7 Security

- The live state is **shared and unfiltered**, like a lake table. Nothing of any person is in it.
- **Every exit applies the reader**: kind access, row rules, masks, the embedding app, on pages, streams, record views, BI answers,
  pivots, exports and Flight, exactly as for lake rows. A live row that moves out of a person's scope is a removal to them; one that
  moves in is an insert. This is the live result sets' rule today, now below every consumer.
- Aggregates over live rows apply row rules before aggregating (the BI rule).
- Switching a feed, changing a binding, "apply now", pause and resume are administrator actions, four eyes for the switch and the
  binding, all audited. Reject payloads: data administrators only.
- A companion's credentials are `${secret:…}` references; consuming needs read-only broker rights; write-back (`writes:`) is a separate
  command with its own switch (Admin → Edits), as today. A module's template holds no host and no secret.
- A feed is input from outside: the validator bounds message size (`max-message-kb`, 1024), nesting depth and array length before
  parsing.

### 7.8 Cluster: which node consumes

| Feed | Who consumes | Fail-over |
|---|---|---|
| **Replayable** transport, `overlay` (Kafka, a RabbitMQ stream) | **every node where the kind is placed**, each on its own, with no consumer group (today's Kafka behaviour): every node builds the same state from the same log | none needed: a node that stops is not missed; one that starts catches up from the topic |
| **Not replayable** (a JMS or RabbitMQ queue), and **every `land` or `both` feed** | **one node**: a `feed` part of the kind's module, owned by the placement's singleton rule (i5: the lease of the cluster on standard libraries) | the next placed node takes the lease, reads the checkpoint from the Drishti database, and continues; the broker held what arrived meanwhile |

For a singleton `overlay` feed the owner **relays** the live state's changes to the other placed nodes over Drishti\*net (the relay
live views use today), each holding a replica, so every node answers Live reads and a take-over starts from a warm state. A landed
batch reaches every node the way any load does: the lake and the forwarded data-load signal.

Writing is idempotent across a take-over: a window's `BATCH_ID` is derived from its first and last transport positions, and the
`order:` guard drops rows the lake already holds (today's PV rule: *the restarted reader skips what it already wrote*).

A node that is not the owner shows the feed as STANDBY, which is healthy.

### 7.9 Monitoring and health

One state per feed, the same words everywhere (Health, Admin, the page's live mark):

| State | Meaning | Counts as |
|---|---|---|
| `OFF` | switched off | OK |
| `NOT CONFIGURED` | on, but a setting it cannot run without is missing | OK, idle |
| `WAITING` | on, the broker is not reachable: *live updates paused* (today's `BrokerWaiting`, unchanged) | OK |
| `CATCHING UP` | connected, reading what the broker holds | OK |
| `LIVE` | applying messages; includes a feed that is simply quiet | OK |
| `STALE` | no message for longer than `stale-after` during the kind's hours (`live.hours`, from its calendar) | amber |
| `DEGRADED` | rejects over the ratio, lag over `lag-alert`, the state at its cap, or unabsorbed rows expired | amber |
| `PAUSED` | an administrator paused it (messages wait at the broker, or are skipped: stated) | OK, shown |
| `STANDBY` | another node owns it | OK |
| `DOWN` | the broker answers but the feed fails (a refused log-in, TLS) | fault, retried |

Measures per feed and kind: messages a minute, applied, duplicate, late, rejected by reason, lag (messages behind; and event time to
applied, p50 and p99), rows and megabytes held, carried rows, the last message, the last reconcile, the last landed batch. They are in
`GET /api/v1/health`, on the Admin panel, and available to `config/alerts/platform-alerts.yaml`. `GET /api/v1/admin/live` lists
every feed; today's `GET /api/v1/risk/var/intraday/streams` becomes a view of it for one release's notice in the API reference.

## 8. Kind by kind

### 8.1 Trades (today's, unified)

| | |
|---|---|
| Contract `live:` | `order: [TRADE_VERSION, SOURCE_UPDATE_TIME]`, `changed-by: UPDATED_BY`, `partial: whole`, `apply: overlay`, `absorb: by-order` (the lake keeps `TRADE_VERSION`) |
| Companions | `trade-<system>-live`, one per source module, `companion-of: trade-<system>`; they keep `writes: amend-trade` |
| Delete | a tombstone; a cancel is an upsert with `TRADE_STATUS` |
| End of day | each system's extract absorbs its own live rows; a trade booked after the extract is carried to the next |

What changes for a site: nothing visible. What changes in files: section 10.

### 8.2 Positions

`position` is computed (derived by the VaR engine, never delivered), so it takes no companion. With the engine at `live: overlay` a
position's quantity and the trades that build it follow the trade feed; its VaR follows landed PVs. `POS <GO>` ticks through the
same live set as any kind. **Positions are derived only** (decision Q5): `position`'s contract says `live.allowed: false`, and no
binding may give it a lake of delivered positions or a companion.

### 8.3 Market data: the 20 lake tables of the market-data pack

One companion (`market-store-live`), one topic per kind. All `apply: overlay`; `both` only where a site's binding asks for intraday
history in the lake (decision Q7: that history is the publisher's job first).

| Group | Kinds (lake table `market.<kind>`) | Key | `order:` | `partial:` | `conflate:` | Notes |
|---|---|---|---|---|---|---|
| Spot and prices | `fx-spot`, `equity`, `equity-index`, `commodity`, `bond` | `PAIR`, `TICKER`, `INDEX_ID`, `COMMODITY_ID`, `ISIN` | a quote time (a field to add to the contracts) | `merge`: a tick carries the price fields; the history series and static fields stay from the base | 1 s | the highest rates; conflation keeps a screen readable and the state small |
| Fixings and index levels | `rate-fixing`, `inflation-index` | `INDEX_ID` | the fixing date and time | `merge` (one new point) | none | a few a day |
| Curves | `ir-curve`, `repo-curve`, `fx-forward-curve`, `credit-curve`, `dividend-curve`, `inflation-curve`, `commodity-curve` | `CURVE_ID` | a curve build time | `whole`: a curve is one row with its points; a point is never patched alone | 5 s | a curve is consistent only as a whole |
| Volatility | `fx-vol-surface`, `equity-vol-surface`, `commodity-vol-surface`, `cap-vol-surface`, `ir-vol-cube` | `SURFACE_ID`, `CUBE_ID` | a build time | `whole` | 30 s | large rows (up to megabytes): `max-message-kb` raised for these kinds |
| Correlation | `correlation-matrix` | `MATRIX_ID` | n/a | n/a | n/a | `live.allowed: false`: an end-of-day estimate |

`absorb: by-cutoff` for all of them (the close's snapshot replaces the day's ticks). The computed `correlation-result` and
`equity-beta` take no feed. A live quote changes what a record page and a market monitor show; it does **not** move VaR, exposure or
P&L (decision Q6, section 7.5).

### 8.4 Scenario PVs and P&L vectors (intraday)

| | |
|---|---|
| Contract `live:` | `order: [COMPUTED_AT]`, `apply: land`, `partial: whole`, `delete.when: "TRADE_STATUS = 'CANCELLED' and SCENARIO_PV is null"` (today's cancel line), `closed-dates: restate` (these two kinds only, decision Q12) |
| Companion | `risk-store-live` (replaces `pv-updates`), `shape: jsonl`, `kinds: [scenario-pv, pnl-vector]` |
| Window | 5 m, earlier at 500 keys, "apply now": the lander's settings, per kind |
| Rejects | another `SCENARIO_SET_ID`, a time before the morning run: the engine's checks stay the engine's and are reported through the same reject log |

Behaviour is today's, to the row.

### 8.5 Reference and portfolio data

`counterparty`, `legal-entity`, `book`, `desk`, `trader`, stored `portfolio`s, `var-limit`, `credit-limit`: a few changes a day, whole
rows, `apply: overlay`, `absorb: reset`, and `replace` for a source that republishes its whole set.

One rule is particular to them: **`effective:`**. A change to a book's desk alters hierarchies that dated aggregates were built on.

| `live.effective:` | Meaning |
|---|---|
| `at-once` | record pages, blotters and pick lists show the change at once; a limit change moves limit bars at once |
| `next-load` (the default for books, desks and anything a hierarchy is built from; decision Q10) | the change is shown on the record with *effective from the next load*; trees, pivots and VaR scopes keep the day's structure |

### 8.6 Netting sets and collateral

`netting-set`, `nset-mtm`, `cpty-mtm`, `collateral-balance`, `margin-call`, `credit-limit`: whole rows keyed by their ids,
`order:` the source's update time, `apply: overlay`, `absorb: by-cutoff`. Today's live exposure model
(`config/tables/banking/live_exposure_netting.table.yaml`) reads them through the live read instead of its own table. EE and PFE stay
the exposure engine's official result (section 7.5); the page already says so.

### 8.7 Other modules

The same three parts serve any module: genomics run status, public-sector feeds, news (today a replenish job into the in-memory lake:
a pull, not a push; it stays a feed job and may take a companion when a push source exists). Nothing in the model is financial.

## 9. Admin and what people see

Mock-ups first: the product owner picked **Admin B with A's grid under Health** (Q1) and **live mark 1 with 2's row dot** (Q2). All are static HTML made only of Drishti Elements patterns (the page
header, the grid, status pills, the switch, the drawer, the banner, KPI tiles, the grid's live mark); no new component is proposed.
They are in [mockups/live-connectors/](mockups/live-connectors/); add `#light` to an address for the light theme.

### 9.1 The Admin view: three options

| | Option | The idea | For | Against |
|---|---|---|---|---|
| **A** | [admin-A.html](mockups/live-connectors/admin-A.html): a **Live feeds** page | one grid, a row per kind and companion: state, rate, lag, rejects, carried rows, the switch; a review panel on the right | everything live in one place; the pattern of Admin → Engines | a new Admin page; the switch lives away from the binding it changes |
| **B** | [admin-B.html](mockups/live-connectors/admin-B.html): a **Live column in Data bindings** | Admin → Data bindings already lists every kind with read and write; add Live (state and switch) and a drawer with the feed's detail | one home: the binding is the fact being changed; no new page | the bindings grid gets wider; monitoring is a drawer away |
| **C** | [admin-C.html](mockups/live-connectors/admin-C.html): **each connector with its companion** | Admin → Connectors shows a data connector and its live companion as a pair, with the path broker → checks → live state → lake load drawn out | closest to the owner's sentence; best for explaining and for diagnosis | per connector, so a kind fed by six systems is six cards; busiest screen |

**Chosen (Q1): B to switch, plus A's grid read-only under Health as the monitor.** The switch is where the binding is (one way to
change it, four eyes as for any binding); Health gets the one table an operator watches. C's path drawing is reused as the drawer's
*How it flows* section.

In every option switching is: pick the kind → the panel shows the companion, the contract's rules in words, what is missing (broker
address, topic, credentials) → **Propose** → a second administrator approves → the feed starts, or WAITS calmly for its broker. The
CLI equal: `drishti.py live list | show <kind> | on <kind> | off <kind> | pause | resume | apply-now <kind> | rejects <kind>`.

### 9.2 What people see: three options for the live mark

| | Option | The idea |
|---|---|---|
| **1** | [indicators-A.html](mockups/live-connectors/indicators-A.html): **a pill in the page's bar** | today's live mark, with the base and the count in words: `LIVE · base 13 Nov close + 412 changes · 2 s ago`; amber `PAUSED`, grey `SNAPSHOT` |
| **2** | [indicators-B.html](mockups/live-connectors/indicators-B.html): **an as-of strip under the title** | a thin strip drawn as a line: base snapshot → changes since → now, with the feeds named; rows changed today carry a dot |
| **3** | [indicators-C.html](mockups/live-connectors/indicators-C.html): **a quiet dot, detail on click** | one coloured dot beside the title, terminal style; the popover holds everything |

Each mock-up shows the same four situations: a blotter that is live, the same with its broker away (*live updates paused*, never an
error), a record page, and a pivot that is a snapshot with newer changes waiting.

**Chosen (Q2): 1** for blotters and record pages (it is the mark people already know, and the words answer "as of when?" without a
click), with option 2's row dot for rows changed today, and the snapshot chip of every option on pivots and reports.

The words are fixed, one list for every screen: `LIVE`, `LIVE · catching up`, `PAUSED · waiting for the broker`, `PAUSED · by an
administrator`, `STALE · nothing since 10:42`, `SNAPSHOT 13 Nov`, `carried from 13 Nov`, `intraday · 5 trades · 11:00`.

## 10. Migration: one machinery

No two implementations side by side: each step below deletes what it replaces in the same change.

| Today | Becomes | Deleted |
|---|---|---|
| `KafkaSourcePlugin`'s index, caches, listeners, `documents()` snapshot | the kernel's live state and intake; the plugin keeps connect, read, WAIT, write-back | the plugin's own index and listener code |
| `MessageStateSource` and the RocksDB state store of `activemq` / `rabbitmq` | the same live state; durability by `apply: both` (the lake) or the state's disk cache | the state store, its settings (`state.*`), the second implementation |
| Kafka `mode: state` / `mode: ticks` | a companion (`companion-of:`: a feed over a lake, what `ticks` was) or a connector with no lake (what `state` was) | the `mode` setting (read for one release with a message saying what replaces it) |
| The six `trade-<system>-live.yaml` | the same files with `companion-of: trade-<system>`, without `kind`, `id-field`, `version-field`, `changed-by`, `changed-at` (the contract's) | the repeated keys; `DRISHTI_STREAM_<SYSTEM>` kept as the switch |
| `trade.blotter.yaml` `key`, `version`, `changed-by`, `changed-at` | defaults from the contract | four lines |
| `VarPvStreamPlugin`, `VarPvWindow`, the Kafka half of `VarPvStream`, `drishti.var.intraday.kafka.*`, the `pv-updates` connector, kind `pv-update` | `risk-store-live` (`plugin: kafka`, `shape: jsonl`) with `scenario-pv` and `pnl-vector` at `apply: land`; the lander's settings | the plugin, its window, its admin endpoints (answered from `/admin/live`), the pseudo-kind |
| `VarIntraday` (the engine's apply) | unchanged | nothing |
| BI `mode: live` in-memory tables; `materialize: {live: …}` | the live read path | BI's own copy of live rows; the tick and status events stay |
| Each live set's private live side | the shared live state | the per-set copies |

A site's files: a connector override written for `pv-updates` or with `mode:` is read for one release and listed in Health with the
line to change (the resolver's existing *not used / superseded* list); the server rewrites nothing (connectors are read-only to it).
`DRISHTI_VAR_PV_STREAM`, `DRISHTI_VAR_PV_TOPIC` and the window variables keep working as the defaults of the new template.

The proof that nothing changed: the existing tests of each piece (`VarPvStreamTest`, `VarPvStreamBrokerAbsentTest`,
`VarIntradayApiTest`, the Kafka plugin's tests, the live set tests, the trade blotter's browser test) pass against the new machinery
before the old is deleted, in the same step.

## 11. Tests

| Layer | What | How |
|---|---|---|
| The intake (unit) | every row of the tables in sections 3 to 6: shapes, ops, merge patch, replace, order, duplicates, late, dates, each absorb rule | plain JUnit on the intake with a fake transport: no broker, milliseconds each; the bulk of the tests |
| Transports (embedded) | connect, WAIT, receive, acknowledge, replay, reconnect | in-process brokers: Kafka's KRaft test kit, an embedded ActiveMQ broker, RabbitMQ through Testcontainers (skipped where Docker is absent, as today); `@TempDir`; no `.exe` |
| **Storage parity** | one scenario (feed 1,000 changes over a snapshot, land two windows, load the next date, reconcile) run on Delta, Iceberg and the file store, the read results compared row for row | one parameterised test; a new store that fails it is not a store |
| **Never double count** | property test: random interleavings of upserts, deletes, duplicates, late events, a load in the middle, a restart with replay; the invariant: the live read equals a reference model, each key once, totals equal | seeded, a few hundred cases |
| **Chaos** | the broker stops and returns (WAITING, then caught up, nothing lost on a replayable feed; the gap counted on a queue), the owner node stops mid-window (the next owner writes the batch once), a load during a burst, the state at its cap | embedded brokers; two server instances for the fail-over case |
| Security | two readers with different row rules and masks on one live set, a row moving across a rule, a pivot's totals per reader, the reject log's access | extends the live set isolation tests |
| Session isolation | nothing per person in the live state (the guard's scan) | the existing guard |
| UI | the live mark's words in each state, the page walk and layout checks on the Admin panel and one blotter, visual snapshots of both | the browser tests, per-worker fixtures, no fixed sleeps |
| Migration | the old tests against the new machinery (section 10) | before each deletion |
| Guards | no consumer touches the live state except the router; a contract's `live:` fields exist; companions name no kind rules; explicit paths; no broker default host in a template | static tests |

Local runs stay small (10,000 rows); the performance figures below are measured in the release's performance drill.

## 12. Performance targets

Targets to design to and to measure; none is a measured figure.

| What | Target |
|---|---|
| Intake, whole documents, validated (`strict`), one kind, one node | 20,000 messages a second sustained (2 KB rows) |
| Intake, keyed, `deferred` | 100,000 a second (today's unparsed path must not get slower) |
| Broker to the live state (applied) | p99 under 50 ms |
| Broker to a cell flashing in a browser | p99 under 250 ms (50 ms flush, one frame) |
| Market data with `conflate: 1s`, 50,000 instruments | one update per instrument per second at most reaches viewers; the state holds 50,000 rows |
| Live read of a page (5,000 rows) over a 1.3 million-row lake kind with 100,000 live rows | under 5 ms after the index is built (today's `StorePages` figure, kept) |
| Memory of the live state | under 500 bytes a row beyond the cached documents (today's Kafka index figure) |
| Catch-up at start, compacted topic, 1 million keys | under 60 s; pages answer from the lake meanwhile |
| Reconcile of 1 million live rows against a load | under 10 s, off the request threads; reads are never blocked (the swap is atomic per kind) |
| Landing a window of 500 scenario-PV rows | written and signalled within 5 s (today's order of magnitude) |
| A broker away | zero errors, zero WARN lines, one INFO line; reconnect found within 30 s |

## 13. Build plan

Sizes: S about a day, M two to three days, L about a week, for one builder. L1 to L5 are the general model with the migrations folded
in; L6 to L10 use it.

| Step | What | Size | Deletes | Docs with it |
|---|---|---|---|---|
| **L0** | The owner's answers (section 15); the two mock-up choices | done | | this document |
| **L1** | Contract `live:` block (parser, load checks, generated contract docs); `companion-of:` in the resolver; reject reasons and their codes | M | `binding-hint.live` | contracts README, KERNEL_AND_MODULES 5.2 and 18.5 |
| **L2** | The live intake and the live state (kernel): decode, map, validate, order, apply; the transport SPI; Kafka on it | L | the Kafka plugin's index, caches, `mode` | Kafka README, DATA_ARCHITECTURE *Streaming sources* |
| **L3** | The router's merged read; live sets on the shared state; blotter defaults from the contract; the six trade templates | L | per-set live side; repeated keys | LIVE_RESULTS_GUIDE sections 6 and 7 |
| **L4** | Business dates, the roll, reconcile at a load (three absorb rules), checkpoints, the reconcile record | L | | DATA_LAKE_GUIDE (loads), LIVE_RESULTS_GUIDE |
| **L5** | The live lander; scenario PVs and P&L vectors on it; parity on Delta, Iceberg and files | L | `VarPvStreamPlugin`, `VarPvWindow`, `pv-updates`, `drishti.var.intraday.kafka.*` | VAR_GUIDE 8.4, CONFIGURATION (generated) |
| **L6** | ActiveMQ and RabbitMQ on the transport SPI; `apply: both` | M | `MessageStateSource`, `state.*` | the two READMEs |
| **L7** | Admin (option B: the Live column and drawer in Data bindings; option A's grid read-only under Health), Health states and measures, alerts, the CLI, the live mark's words on every page | L | the PV stream's admin endpoints | ADMIN_GUIDE, OPERATIONS_GUIDE, CLI_REFERENCE, API_REFERENCE |
| **L8** | Kinds: market data (20 contracts' `live:` blocks, quote-time fields, the companion, conflation), netting sets and collateral, reference data with `effective:`, derived positions at `live: overlay`; demo publishers in `tools/samplegen` | L | the live exposure table's own copy | the packs' READMEs and guides, a tutorial (feed → blotter → load → reconcile) |
| **L9** | BI and pivot: the live read for `mode: live`, the snapshot chip, incremental additive measures | M (L with incremental) | BI's live table copy | BI guides |
| **L10** | Cluster: the singleton feed part on the i5 lease, the relay of a singleton's state, fail-over tests | M | | CLUSTER_GUIDE |

All ten steps are in i5 (decision Q11). Order and drills: L1 → L2 → L3 are one chain; L4 and L5 follow L3 and are independent of
each other; L6, L7, L8 follow; L9 next; **L10 last**, because it depends on i5's cluster work. About seven builder-weeks in all; one
feature drill after L8, one performance and hardening drill after L10.

## 14. Risks

| Risk | Mitigation |
|---|---|
| The refactor of L2 and L3 touches the hottest path of the live blotter | the old tests run against the new code before the old is deleted; the performance targets of today's paths are floors |
| Validation on arrival costs throughput | `deferred` keeps today's path; measured in L2 before the default is fixed |
| A source's extract and its feed disagree (orphans every night) | the reconcile record makes it visible on day one; the default (the lake wins) is the safe one; Q3 |
| A kind ordered only by arrival misorders across partitions | the Admin panel states the order in words; `absorb: reset` bounds the damage to a day; contracts gain an order field where a source has one |
| Memory: a quote feed without conflation | conflation is in the contract of every market kind; the state has a hard cap and says when it is reached |
| Fail-over of a queue feed loses the state | followers hold a replica; `apply: both` for kinds that must survive a full restart |
| A live reference change breaks a hierarchy mid-day | `effective: next-load` is the default for anything a hierarchy is built from |
| Scope: "any kind" invites feeds for official results | `live.allowed: false` on signed-off and computed kinds, checked at load |
| i5 is also the cluster and the console, and holds all ten steps | L1 to L9 do not depend on either; only L10 does, and it is last; a slip of the cluster work moves L10 alone |

## 15. Decisions

The product owner's answers (2026-11-15). Where an answer differs from the recommendation, the sections named were changed to match.

| # | Question | Decision | Sections |
|---|---|---|---|
| Q1 | Admin view (section 9.1) | **B** to switch (the Live column and drawer in Data bindings), with **A's grid** read-only under Health as the monitor | 9.1, 13 (L7) |
| Q2 | Live mark (section 9.2) | **1** (the pill in the page's bar), with **2's row dot** for rows changed today; the snapshot chip on pivots and reports | 9.2 |
| Q3 | A live row the next load does not contain, changed before the cut-off | **drop and report** (an orphan; the lake wins) | 6 |
| Q4 | Pivots and BI | **snapshot by default**, live by choice under the row cap; incremental additive measures in L9 | 7.3 |
| Q5 | Positions | **derived only**: `position` takes no lake of delivered positions and no companion | 8.2 |
| Q6 | Do live quotes move valuations? | **confirmed: never.** Live quotes never move VaR, exposure or P&L | 7.5, 8.3 |
| Q7 | Intraday history of `overlay` kinds | **the publisher's job.** Drishti lands an `overlay` kind (`apply: both`) only where a site's binding asks | 2.4, 5, 8.3 |
| Q8 | Validation default | **`strict`** on arrival for every kind; `deferred` the exception | 3.4 |
| Q9 | Transports after Kafka, ActiveMQ and RabbitMQ | **none**: no new transports; the SPI stays ready | 2.5 |
| Q10 | Reference data intraday | **`effective: next-load`** the default for books, desks and anything a hierarchy uses | 8.5 |
| Q11 | Scope of i5 | **all of L1 to L10 in i5**; L10 depends on i5's cluster work and is last | 13, 14 |
| Q12 | Closed dates | **scenario PVs and P&L vectors only** may restate a closed, not signed-off date | 5, 8.4 |
