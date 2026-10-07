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

# Live BI: Drishti as the live front end of a streaming BI producer

Status: design note, proposed (2026-10-06). Nothing in it is built beyond what section 2 lists as existing. Owner: the
server's source layer (`drishti-api`, `plugins/`), the live path ([LIVE.md](LIVE.md)) and the console's view pages.

**The question.** Can Drishti work as a real-time BI server: aggregations, pivots and rankings, kept current and pushed
to people as the data changes?

**The answer in one paragraph.** Drishti should not *compute* real-time BI; it should *serve* it. Keeping an aggregate
over a million rows current on every change needs incremental maintenance (apply each change as a delta, never
recompute), and that is a different engine: a continuous-SQL engine such as Pravaha, a stream processor, or a custom
BI server. What Drishti adds on top of such a producer is everything a BI server does not have: a dense, keyboard-driven
screen per result, live delivery to many browsers, links from a total down to the rows behind it, field masks per
reader, provenance, sharing, alerts and embedding in other applications. So the design is: **a producer computes and
streams keyed result rows; Drishti treats each row as an entity and streams it on.** The first version needs no code
at all (section 5.1); the rest of this note is what makes it good.

Contents

1. [Terms](#1-terms)
2. [What Drishti already does](#2-what-drishti-already-does)
3. [The shape of the design](#3-the-shape-of-the-design)
4. [The BI stream contract](#4-the-bi-stream-contract)
5. [Three ways in](#5-three-ways-in)
6. [Modelling results: rows, pivots and totals](#6-modelling-results-rows-pivots-and-totals)
7. [Screens: one result, and a live board of many](#7-screens-one-result-and-a-live-board-of-many)
8. [Drilling down from a total to its rows](#8-drilling-down-from-a-total-to-its-rows)
9. [Consistency: what "current" means across rows](#9-consistency-what-current-means-across-rows)
10. [Security](#10-security)
11. [Performance and limits](#11-performance-and-limits)
12. [Non-goals](#12-non-goals)
13. [Worked example: live desk exposure](#13-worked-example-live-desk-exposure)
14. [Build plan](#14-build-plan)
15. [Open decisions](#15-open-decisions)

## 1. Terms

| Term | Meaning here |
|---|---|
| **Producer** | The system that computes results: Pravaha, Flink, ksqlDB, Materialize, a custom BI server, a batch job that republishes. Drishti never runs inside it. |
| **Result row** | One keyed output row of a producer's query: a desk's exposure, a region's sales today, one cell row of a pivot. It has an identity that does not change while its values do. |
| **Result kind** | A Drishti kind whose entities are result rows (`desk-exposure`, `region-sales`). It lives in a pack like any other kind. |
| **Board** | A live list of many result rows of one kind, sorted and cut to a top N (proposed, section 7.2). |
| **Members** | The input records a result row summarises (the trades behind a desk's exposure). |
| **Generation** | The producer's own version of its output (a commit number, a checkpoint id, a watermark); it says which input a row reflects. |

## 2. What Drishti already does

Everything here exists in 1.18 and is reused, not rebuilt.

| Capability | What it gives BI | Where |
|---|---|---|
| **Per-entity live path** | `SourcePlugin.subscribe(ref, listener)` pushes each new generation of an entity; the TopicHub keeps one source subscription per entity, latest wins; ViewStream and PatchDiffer send only what changed over one channel per browser. Tick to frame p99 11.2 ms. | `drishti-api/.../SourcePlugin.java`; [LIVE.md](LIVE.md) |
| **Deletes** | A tombstone becomes `EntityDocument.deleted(...)`; open views say the entity was deleted, and a later message brings it back in order. | [LIVE.md, Deleted entities](LIVE.md#deleted-entities) |
| **Kafka, state mode** | A compacted topic keyed by entity *is* the entity store: the latest message per key is the document, served on read and pushed live, with memory and disk caches. Envelope messages carry `kind`, `id` and `document`, keyed `<kind>/<id>`. | [KAFKA_CONNECTOR.md](../connectors/KAFKA_CONNECTOR.md) §2–§4 |
| **Derived kinds** | The `derived` plugin groups one kind by an expression and sums, counts or averages fields, with a `members` list of ids that become links (`desk-pnl` from trades in `packs/trading/pack.yaml`). It **recomputes on a timer** (`refresh: 60s`), and caps members (`max-members: 250`). | `packs/trading/pack.yaml` `desk-totals` |
| **Monitors** | A saved list of 1 to 50 entities streamed as one connection; only strip patches are sent. | [LIVE.md, Monitors](LIVE.md#monitors-and-alerts) |
| **Alerts** | A Rachana-EL condition per rule, evaluated on every new document of the watched entities, firing on false → true. | [LIVE.md, Alerts](LIVE.md#monitors-and-alerts) |
| **Search, pivot, reports** | Questions over a kind (`TRD where mtm < -10m order by mtm`), a client-side pivot of the results, scheduled CSV reports. | USER_GUIDE |
| **Masks, provenance, sharing, embedding** | Every value that leaves is masked per reader; every view says where it came from; any view can be shared, discussed or embedded with `<drishti-view>`. | [CONTEXT_HELP.md](CONTEXT_HELP.md), [COLLABORATION.md](COLLABORATION.md), [ELEMENTS.md](ELEMENTS.md) |

So Drishti already has real-time *delivery* of keyed documents and *timed* aggregation. What it lacks for real-time BI
is *incremental* aggregation, and a live view of *many* result rows at once.

## 3. The shape of the design

```
  inputs              producer                       Drishti                                  people and apps
  ──────              ────────                       ───────                                  ───────────────
  trades,     ─►  continuous query / BI job  ─►  result kind in a pack            ─►  one result: a live view (Sutra)
  orders,         keeps each result row          (a source per section 5)             a board: top N rows, live
  readings        current; emits upserts         TopicHub → ViewStream → frames       drill down to members
                  and deletes, keyed                                                  alerts, shares, embeds
```

Three rules hold the design together:

1. **The producer owns the arithmetic.** Drishti never re-derives a total a producer sent; it displays, links, masks
   and streams it. (It may still compute its own small derived kinds for convenience, as today.)
2. **A result row is an entity.** It has a kind, a stable id, a document, links and a history, so every Drishti feature
   works on it without special cases.
3. **The wire carries state, not deltas.** Each message is the whole current row (an upsert) or a delete. A producer
   that thinks in weighted deltas (+1 / −1, as Pravaha and other incremental engines do) is turned into state by an
   adapter (section 5.2) before it reaches the TopicHub.

## 4. The BI stream contract

Any producer that meets this contract works with Drishti. It is written to be met by a compacted Kafka topic with no
Drishti code, and by the HTTP form in section 5.2.

### 4.1 Identity

- Every result row has a **kind** and an **id**. The id is built from the row's group-by key, deterministic and stable:
  `RATES` for a desk, `RATES|USD` for desk and currency, `EMEA|2026-10-06` for region and day. Use `|` between key
  parts, and keep ids distinct from other kinds' ids (the pack's `graph.id-patterns` map an id to its kind).
- The same row always has the same id; a row that leaves the result (a desk with no trades left) is **deleted**, not
  sent as zeros, unless zero is a meaningful answer the producer wants to show.
- A **grand total** is a row like any other, with a reserved id (`ALL`), so it is linkable and searchable.

### 4.2 Message

On Kafka, an envelope keyed `<kind>/<id>` on a compacted topic (the Kafka connector's envelope form):

```json
key:   desk-exposure/RATES
value: {
  "kind": "desk-exposure",
  "id": "RATES",
  "document": {
    "desk": "RATES",
    "tradeCount": 1842,
    "notional": 48210000000,
    "mtm": -12650044.17,
    "dv01": -381220.5,
    "topCounterparties": [ { "id": "CP-MERIDIAN-RE", "mtm": -4120000.0 }, { "id": "CP-ALDERSHOT", "mtm": -2210300.0 } ],
    "members": { "kind": "trade", "where": "$.desk == 'RATES'", "count": 1842 },
    "_bi": { "query": "desk_exposure", "generation": 918244, "asOf": "2026-10-06T14:03:11.204Z",
             "inputWatermark": "2026-10-06T14:03:10Z", "producer": "pravaha-1" }
  }
}
```

and a delete is the same key with a null value (a tombstone).

### 4.3 Fields Drishti reads by name

| Field | Required | Used for |
|---|---|---|
| `kind`, `id` | yes (envelope) | routing and identity |
| `document` | yes | the view; any shape, inference lays out what no Sutra covers |
| `document._bi.generation` | recommended | shown in the view's provenance ("generation 918244"); lets a board say rows are from different generations (section 9) |
| `document._bi.asOf` | recommended | freshness on the view and in Health (`stale-after`) instead of message arrival time |
| `document._bi.inputWatermark` | optional | "complete up to" in About this page: how far the producer has read its inputs |
| `document._bi.query`, `producer` | optional | About this page, layer 2 ("where the data came from") |
| `document.members` | optional | drill-down (section 8): either a list of ids, or `{kind, where, count}` |

Everything under `_bi` is metadata: it is never shown as a field, and it is excluded from inference's layout.

### 4.4 Ordering, snapshots and size

- **Per key, in order.** All messages of one row go to one partition (a fixed key does this); across rows there is no
  order (section 9).
- **Snapshot plus changes.** A new Drishti server reads the compacted topic from the beginning and has the current state;
  a producer never needs to "replay for Drishti".
- **Small rows.** A row should stay under 64 KB; a list inside a row (top counterparties, a histogram) under 500 items.
  Large detail belongs in the members, not in the row.
- **Rate.** Upserting a row many times a second is fine: the TopicHub keeps only the latest per entity and sends at
  most one frame per 50 ms per entity to each view. The producer may coalesce for its own sake; Drishti does not need it.

## 5. Three ways in

### 5.1 Today, no code: a compacted Kafka topic

A producer that can write to Kafka (Pravaha has Kafka sinks; Flink, ksqlDB and most BI jobs can too) writes the
envelope above to a compacted topic. The pack declares the kind and a Kafka connector in state mode:

```yaml
kinds:
- desk-exposure                             # a result kind, declared like any other
mnemonics:
  DEXP:
    kind: desk-exposure
    label: Desk exposure (live)
connectors:
  risk-results:
    plugin: kafka
    kinds:
    - desk-exposure
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: ${DRISHTI_RISK_RESULTS_TOPIC:risk.results}
      stale-after: 2m                       # amber in Health if the producer goes quiet
      disk-cache.enabled: true
```

`DEXP RATES <GO>` then opens a live view of the desk's exposure; it ticks as the producer upserts; a tombstone closes
it with "deleted". Alerts work at once (`$.mtm < -15000000`), as do sharing and embedding. What this does *not* give
yet: a board of many rows (section 7.2), drill-down by predicate (section 8), and `_bi` read as metadata (it shows as
an ordinary field until step 2 of the build plan).

### 5.2 A `bi` source plugin: HTTP stream, and deltas turned into state

For producers without Kafka, or that speak in weighted deltas, a new plugin `bi` subscribes to the producer over HTTP:

- **Endpoint.** `GET <producer>/results/{query}/changes?from=<generation>` as server-sent events or newline-delimited
  JSON; first a snapshot (every current row), then batches of changes, each batch with its generation.
- **Two message forms, chosen per query in the pack.** `upsert` (the row's whole state, as in 4.2) and `weighted`
  (`{"weight": +1|-1, "row": {...}}`, a Z-set delta: a −1 withdraws an old version of the row, a +1 adds the new one).
  For `weighted`, the plugin keeps the current row per key, applies each batch atomically, and emits the resulting
  upsert or delete. A batch that leaves a key with net weight 0 deletes it.
- **Resume.** It remembers the last generation it applied (in the disk cache) and reconnects with `from=`; if the
  producer can no longer serve that generation it answers with a fresh snapshot, and the plugin swaps state in one step.
- **Serving reads.** `fetch` answers from its state; `search` offers type-ahead by id and by any field the pack lists
  as `searchable`; `lastUpdate` is the last batch's `_bi.asOf`.

It implements the existing SPI only (`start`, `fetch`, `subscribe`, `search`, `lastUpdate`, `coverage`); no engine
change is needed for one-result views.

### 5.3 Pravaha, by name

Pravaha keeps the answer to registered SQL current and lets a client subscribe to a view and receive every commit as a
batch of weighted changes. That is the `weighted` form of 5.2 with Pravaha's commit as the generation, so the Pravaha
adapter is a thin client of the `bi` plugin's protocol rather than a separate plugin, or, with Pravaha's Kafka sink,
it is section 5.1 with nothing to build. Either way Pravaha registers the query (`CREATE CONTINUOUS QUERY
desk_exposure … KEYED BY (desk)`) and Drishti never parses SQL.

## 6. Modelling results: rows, pivots and totals

| Producer output | Model in Drishti | Why |
|---|---|---|
| A grouped aggregate (sum of MTM by desk) | One entity per group, id = group key | Each group is linkable, searchable, alertable and has its own live view |
| A two-key aggregate (desk × currency) | One entity per pair, id `RATES|USD`, plus links to the `desk` row and to `currency` | The board can pivot rows client-side; each cell is still openable |
| A pivot the producer computes whole (a small matrix) | **One** entity whose document is the matrix; a `pivot` or `table` panel shows it | Under ~500 cells it is one screen, one frame per change |
| A ranking (top 20 counterparties by exposure) | The ranking as one entity (`top-cpty/ALL`) with an ordered list, each item a link | Rank order is the producer's answer; Drishti does not re-sort it |
| A time series (sales per minute today) | One entity per series, the points in a list; a `line` or `area` panel | Each new minute is an upsert of the series with one more point |
| A grand total | A row with the reserved id `ALL` | Linkable like any other |

Rule of thumb: **if people want to open it, alert on it or share it, it is an entity**; if it is only ever seen inside
another result, it is a field of that result.

## 7. Screens: one result, and a live board of many

### 7.1 One result

A result row is an entity, so its screen is a Sutra like any other, or inference when there is none (a numeric strip,
lists as tables, `members` as a link). The four reference panels for BI already exist: `metric` and `kv` for figures,
`table` and `ladder` for breakdowns, `line`/`area`/`hbar`/`waterfall`/`histogram` for shapes, `pivot` for a small
matrix. Nothing new is needed.

### 7.2 A board: a live list of many rows (proposed)

Monitors stream up to 50 named entities and only their strips. BI needs more: *all* rows of a kind (or those matching
a predicate), sorted by a field, cut to the top N, with rows entering and leaving as values change. Proposal:

- **A board is saved like a monitor** but defined by a query, not a list: `{kind: desk-exposure, where: "$.mtm < 0",
  order: "$.mtm", limit: 100, columns: [desk, mtm, dv01, tradeCount]}`.
- **Server side.** A `BoardStream` subscribes to the kind as a whole (new SPI method `subscribeKind(kind, listener)`,
  default unsupported; the Kafka and `bi` plugins implement it because they already see every row), keeps the matching
  rows in a sorted structure, and sends `row` (upsert at a position), `drop` and `move` events, coalesced to at most one
  frame per 100 ms per board. Columns are masked per reader as everywhere.
- **Limits.** `limit` up to 500 rows on screen; the kind may have any number of rows (the board keeps only matching
  keys and their sort values in memory).
- **In the console.** A board page (`/b/<name>`) and a `board` panel kind for Sutras, so a desk's screen can embed a
  live board of its counterparties. Keys: arrows to move, Enter to open a row, `/` to filter, `p` to pivot the visible
  rows (the existing client-side pivot).
- **Embedding.** `<drishti-view kind="board" entity="<name>">` works through the same element and channel.

## 8. Drilling down from a total to its rows

A total is only trusted if people can see what it adds up. Two forms of `members`:

1. **A list of ids** (`"members": ["MX-20000001", …]`), as the `derived` plugin does today: links straight to the
   member entities. Fine up to a few hundred.
2. **A predicate** (`"members": {"kind": "trade", "where": "$.desk == 'RATES'", "count": 1842}`): Drishti opens a
   search of `trade` with that Rachana-EL condition, on the same business date. The predicate is the producer's
   statement of its group; Drishti validates it as a closed expression (no side effects) and refuses it with
   `DRS-2xxx` if it does not parse.

About this page then shows, for a result row: the query and producer (layer 2), the generation and the input watermark
("complete up to 14:03:10"), and the count of members against the count a search returns now (a mismatch is shown, not
hidden: the producer and the member source may be at different points; section 9).

## 9. Consistency: what "current" means across rows

- **Per row: exact.** Each row is the producer's latest state for its key, in order.
- **Across rows: eventually.** One producer commit that changes 40 desks arrives as 40 upserts; for a few milliseconds a
  board can show some desks at generation N and others at N−1. A board therefore shows its **generation range** in its
  footer (`generations 918243–918244`) and settles when a frame has a single generation.
- **Optional commit barrier.** A producer that can mark a commit's end (`{"commit": 918244}` on the `bi` stream, or a
  marker key on Kafka) lets a `BoardStream` hold a frame until the commit is complete, so a board never shows a half
  commit. Off by default: it trades a little latency for a consistent picture.
- **Against the members.** A total and a search of its members can disagree briefly; About this page says so with
  both counts and both freshness times.

## 10. Security

- **Kinds per role** decide who may open a result kind at all, as for any kind.
- **Field masks** apply to result fields exactly as to source fields (`drishti.security.redact`), in views, boards,
  alerts, shares, emails, bridges, snapshots and embedded views.
- **Aggregates can leak.** A total over a masked field is still information (the sum of salaries by team reveals a
  one-person team's salary). Rules: a result field computed from a masked input is declared masked in the pack
  (`masked-from: [salary]`), and a pack may set a minimum group size (`min-members: 5`) below which the row's figures
  show as `•••` for readers without `raw`.
- **Row-level access.** Readers who may see only some desks see only those rows on a board; the predicate is applied
  server side before sorting, so a top-N never reveals a row's existence by its rank.
- **Producer trust.** The producer is a source like any other: TLS to Kafka or to the `bi` endpoint, credentials from
  environment variables, `stale-after` in Health.

## 11. Performance and limits

| Concern | Expectation | Basis |
|---|---|---|
| One result view, tick to frame | as today: p99 ~11 ms | [PERFORMANCE.md](../admin/PERFORMANCE.md) |
| Update rate per row | unbounded at the producer; Drishti sends at most one frame per 50 ms per view | TopicHub latest-wins slot |
| Rows of a result kind | 100,000s on Kafka state mode with the disk cache | the Kafka connector's caches |
| Board size | up to 500 rows shown; one frame per 100 ms | proposed (section 7.2); to be measured in step 4 |
| Boards per server | bounded by `drishti.live.max-streams`, as monitors are | LIVE.md, Settings |

The numbers in the proposed rows are targets; step 4 of the build plan measures them on the 1M-trades-a-day data set and
replaces the targets with results in PERFORMANCE.md.

## 12. Non-goals

- **Incremental computation inside Drishti.** No continuous queries, windows, joins or watermarks. The `derived` plugin
  stays a convenience for small, timed totals.
- **SQL or a semantic layer.** No SQL endpoint, no measures-and-dimensions model, no ODBC/JDBC driver for BI tools.
- **Historical OLAP.** Slicing years of history stays with BI tools and the lake; Drishti's history is per entity and
  per business date.
- **Dashboard canvases.** A board or a Sutra is the unit; free-form dashboards of many unrelated tiles are not planned.

## 13. Worked example: live desk exposure

1. **The producer** keeps `desk_exposure` current, keyed by desk, and writes envelopes as in 4.2 to the compacted
   topic `risk.results` (with Pravaha: a continuous query and its Kafka sink; with another engine: its upsert sink).
2. **The pack** adds the kind, the mnemonic `DEXP` and the `risk-results` connector (5.1), and a Sutra:

   ```yaml
   rachana: 1
   sutra: desk-exposure
   version: 1
   match: { kind: desk-exposure, priority: 10 }
   title: { pill: "Desk exposure · live", id: $.desk }
   strip:
     - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
     - { label: DV01 (USD), bind: $.dv01, fmt: signed0 }
     - { label: Trades, bind: $.tradeCount, fmt: amount0 }
   panels:
     - { id: top, kind: table, title: Top counterparties, key: F2, rows: $.topCounterparties }
   ```

3. **A person** types `DEXP RATES <GO>`: the strip ticks with each producer commit; About this page says "from
   desk_exposure on pravaha-1, generation 918244, complete up to 14:03:10".
4. **An alert** `$.mtm < -15000000` on `desk-exposure/RATES` fires on the first commit that crosses it, to the bell,
   inbox and email, masked per recipient.
5. **With step 3 built**, F3 "Trades" opens the search `trade where $.desk == 'RATES'`; **with step 4**, the board
   `DEXP*` shows every desk, sorted by MTM, rows moving as commits arrive.

## 14. Build plan

| Step | What | Size | Done when |
|---|---|---|---|
| 1 | **Guide and example, no code**: a `docs/guides/LIVE_BI.md` cookbook for 5.1, an example pack `live-bi` with the kind, connector and Sutra, and a small producer script in `tools/` that writes envelopes to a local Kafka (dev compose) | S | `DEXP RATES` ticks from the script; a tombstone closes the view |
| 2 | **`_bi` as metadata**: provenance, freshness from `_bi.asOf`, generation and watermark in About this page, excluded from inference | S | About shows generation and watermark; `_bi` never appears as a field |
| 3 | **Members by predicate**: `members: {kind, where, count}` opens a search; counts compared in About | M | Clicking members opens the right search; mismatches are shown |
| 4 | **Boards**: `subscribeKind` in the SPI (Kafka and `bi` implement it), `BoardStream`, `/b/<name>`, the `board` panel kind, masks and row-level access, measured | L | A 500-row board of 100,000 rows, p99 frame time measured and in PERFORMANCE.md |
| 5 | **`bi` plugin**: HTTP stream, `upsert` and `weighted` forms, resume by generation, snapshot swap | M | A weighted stream with retractions yields the same rows as its upsert twin, in a test |
| 6 | **Commit barrier** (optional): hold board frames to whole commits | S | A board never shows two generations in one frame with the barrier on |
| 7 | **Aggregate safety**: `masked-from`, `min-members` | S | A one-member group's figures are masked for a viewer |

Steps 1 and 2 make section 5.1 a supported, documented path within days. Step 4 is the one that makes Drishti feel like
a live BI front end. Each step ships with tests (unit, console, browser at phone width), documentation and real
captures, as every feature does.

## 15. Open decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| 1 | Where a board's query lives | per user, like monitors; in a pack, like Sutras; both | **Both**: packs ship standard boards, users save their own |
| 2 | `subscribeKind` for sources that cannot see every row (JDBC, REST) | refuse; poll with `refresh:` | **Refuse** in step 4; a polling board is a later, separate choice |
| 3 | Commit barrier default | on; off | **Off**: lower latency; turn on per board where a consistent picture matters more |
| 4 | Pravaha integration form | Kafka sink (5.1); `bi` client (5.2); a dedicated plugin | **Kafka sink first** (no code), then the `bi` client; no dedicated plugin unless the protocol diverges |
| 5 | Drill-down predicate language | Rachana-EL only; also SQL `WHERE` | **Rachana-EL only**: closed, already validated, already used by search |
