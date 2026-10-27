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
# Reactive connectors: data that is computed when it is asked for, and again when the world changes

Status: **R1 to R4 built (2.8.0, unreleased); R5 and R6 planned.** Designed with the product owner (2026-10-24). What is built, and
where it differs from the design below, is in [section 21](#21-as-built-r1-to-r4); the reference for people who write and run reactive
connectors is [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md) and [tutorial 23](../tutorials/23-computed-panels.md).
Everything named below that section 21 does not list as built is **planned**. Related designs:
[PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) (the workers that run the computations, `requires:` and approval),
[VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) (the first reactive connector: a generic VaR engine), [WRITE_BACK.md](WRITE_BACK.md) (a saved change
can trigger a computation), [SUTRA_NEXT.md](SUTRA_NEXT.md) (screens that read many sources), [LIVE.md](LIVE.md) (how results reach
the screen), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) (the connector SPI as built).

## Contents

1. [The ask, in one paragraph](#1-the-ask-in-one-paragraph)
2. [Decisions this design rests on](#2-decisions-this-design-rests-on)
3. [Four kinds of connector](#3-four-kinds-of-connector)
4. [What a reactive connector is](#4-what-a-reactive-connector-is)
5. [The connector file](#5-the-connector-file)
6. [The request and its lifecycle](#6-the-request-and-its-lifecycle)
7. [Triggers: computing on events](#7-triggers-computing-on-events)
8. [Point-in-time stamping, ordering and idempotence](#8-point-in-time-stamping-ordering-and-idempotence)
9. [Isolation: two users never see each other's answers](#9-isolation-two-users-never-see-each-others-answers)
10. [Coalescing, incremental recompute and back-pressure](#10-coalescing-incremental-recompute-and-back-pressure)
11. [Caching](#11-caching)
12. [One screen, many connectors](#12-one-screen-many-connectors)
13. [Failure](#13-failure)
14. [Several servers (Drishti*net)](#14-several-servers-drishtinet)
15. [Observability and audit](#15-observability-and-audit)
16. [The SPI and the APIs](#16-the-spi-and-the-apis)
17. [Configuration reference](#17-configuration-reference)
18. [Build plan](#18-build-plan)
19. [Tests](#19-tests)
20. [Open questions for the product owner](#20-open-questions-for-the-product-owner)
21. [As built: R1 to R4](#21-as-built-r1-to-r4)
22. [How the VaR engine plugs in](#22-how-the-var-engine-plugs-in)

---

## 1. The ask, in one paragraph

Today a connector brings data that already exists somewhere: a lake, a database, a queue, a web service. The product owner wants
connectors that **compute** their answer: a VaR for any group of trades, a what-if price for a trade that is not booked, the effect of
an amendment on a desk's risk. The computation runs **when someone asks**, and **again when something changes** (a saved edit, a
message on Kafka, a schedule). Packs stay exactly as they are (kinds, routes, screens); the connector carries the execution and the
libraries it needs. A screen can combine as many connectors as it likes: stored data, live data and computed data side by side, all
for the same business date, and each panel says on its own if its source failed.

## 2. Decisions this design rests on

| # | Decision (product owner, 2026-10-24) | What it means here |
|---|---|---|
| 1 | **Packs stay as they are.** "Connector can be reactive, on demand, static, whatever it is." | No computation and no library is declared in a pack. A pack names kinds, routes each kind to a connector and draws screens. |
| 2 | **A connector may be static, live, on-demand or reactive.** | One `mode:` per connector file ([section 3](#3-four-kinds-of-connector)). |
| 3 | **A pack combines results from as many connectors as it wishes.** | Per-panel `source:` already exists ([SUTRA_NEXT.md section 6](SUTRA_NEXT.md#6-sources-over-several-packs)); this design adds parameters, one pinned as-of per screen, and per-panel failure ([section 12](#12-one-screen-many-connectors)). |
| 4 | **Reactive connectors compute on request and on events** (a write-back, a Kafka change, a schedule). | `triggers:` ([section 7](#7-triggers-computing-on-events)) with coalescing, incremental recompute, point-in-time stamps, ordering, idempotence and back-pressure. |
| 5 | **Two users' write-backs or requests never mix their responses.** | A correlation id on every request and event, per-user sandboxes, cache keys that include the viewer's rights, masks applied per recipient ([section 9](#9-isolation-two-users-never-see-each-others-answers)). |
| 6 | **The connector declares `requires:`** (Python, Java, plugins); an administrator approves before use. | The block, the locked environments and the approval are in [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md). |
| 7 | **Python runs out of process, Java stays on 21, no FFI, no embedded CPython.** | A reactive connector's code runs in a worker process, never inside the server ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)). |

## 3. Four kinds of connector

| Mode | What `fetch` means | Data lives | History (a picked business date) | Pushes changes | Examples today |
|---|---|---|---|---|---|
| **static** | read stored records | in the store, written by a loader | yes, from the store's dated partitions | only when a new load is published (the data version moves) | `delta`, `iceberg`, `jdbc`, `duckdb`, `cassandra`, `file` |
| **live** | read the latest value and subscribe to its changes | in a stream or a cache fed by one | through a lake behind it, if any | yes, every message | `kafka`, `rabbitmq`, `activemq`, `redis` in subscribe mode |
| **on-demand** | call a service; the id is the question | nowhere: the answer is fetched or worked out per call, then cached | only if the service can answer for a date | no | `news`, `yfinance`, `rest` |
| **reactive** (new) | **run a computation** parameterised by the request, over data read from other connectors | the result is computed, stamped and cached; nothing is stored unless the connector says so | yes: the computation is run on the inputs **as of** the picked date | **yes: the result is recomputed and pushed when a declared trigger fires** | planned (2.8.0): `var-engine` ([VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives)) |

- The first three exist today and do not change. Writing `mode:` in their connector files is optional (the plugin's manifest already
  says what it can do: `live`, `search`, `dated`); a planned (2.8.0) `mode` field in the manifest makes it visible in Admin →
  Connectors and in the connector type's README front matter (`capabilities:` gains `compute`).
- An **on-demand** connector is a reactive connector without triggers, without parameters beyond its id, and without workers: it stays
  the light pattern of [the developer guide](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand).
  A reactive connector is for computations that are heavy, parameterised, need their own libraries, or must react to events.

## 4. What a reactive connector is

A reactive connector is **a connector whose answer is a computation**. It has five parts:

| Part | Says | Section |
|---|---|---|
| **kinds** | the kinds it answers (`var-result`, `var-contribution`, `what-if` …), each with its parameters and the shape of its result | [5](#5-the-connector-file) |
| **inputs** (`reads:`) | which kinds of which connectors it reads, always **as the requesting user** | [6](#6-the-request-and-its-lifecycle) |
| **engine** | where the code runs: a Python worker, a Java worker, or (trusted, light code only) in the server's own process | [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) |
| **requires** | the libraries the engine needs, pinned, approved by an administrator | [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) |
| **triggers** | the events on which results are recomputed and pushed | [7](#7-triggers-computing-on-events) |

Everything that already consumes connectors works on it without change: a Sutra panel's `source:`, BI datasets, history queries,
entitlements and masks, Drishti\*net routing, Admin → Connectors and Health, the README standard. That is why the execution unit is a
connector and not a new concept.

```
 Sutra panel / BI / API ──▶ engine router (kind → connector, as today)
                                     │
                     ┌───────────────┼──────────────────────────────┐
                     ▼               ▼                              ▼
              static connector   live connector            reactive connector  (var-engine)
              (risk-store,       (trade-kafka)              │ 1. key + cache + single flight
               Delta lake)                                  │ 2. admission (interactive | event | batch)
                     ▲                                      │ 3. reads inputs AS THE USER through the router ──┐
                     └──────────────────────────────────────┼──────────────────────────────────────────────────┘
                                                            │ 4. Arrow batches ──▶ compute worker pool (Python / Java)
                                                            │ 5. partial results ──▶ live channel (progress)
                                                            │ 6. stamped result ──▶ cache ──▶ the requester only
                     triggers: write-back applied, ─────────┘ 7. on an event: affected scopes recomputed, pushed to
                     Kafka message, schedule, new load           each subscriber, re-masked for each
```

## 5. The connector file

A reactive connector is a connector file in `config/connectors/` like any other (the file name is its name). The plugin is the
generic planned (2.8.0) `reactive` plugin; what is specific to the computation is configuration plus the code the `engine:` names.

```yaml
# config/connectors/var-engine.yaml                           (planned, 2.8.0)
plugin: reactive
mode: reactive
enabled: true
description: Generic VaR, ES and what-if over precomputed 509-day P&L vectors
kinds:
  var-result:                          # one answer: VaR, ES and the headline numbers for one scope
    params:
      scope:      { type: scope, required: true }          # a hierarchy node, a saved basket, or a scope expression (VAR_GUIDE.md section 5)
      confidence: { type: decimal, default: 0.99, allowed: [0.95, 0.975, 0.99] }
      horizon:    { type: int, default: 1, allowed: [1, 10] }
      method:     { type: enum, default: historical, options: [historical, age-weighted, filtered] }
      whatif:     { type: sandbox-ref, required: false }   # a private what-if sandbox of the requester (section 9)
    result: { schema: schemas/var-result.v1.json }
  var-contribution: { params: { scope: { type: scope, required: true }, by: { type: enum, options: [child, position, risk-class] } } }
  var-path:         { params: { scope: { type: scope, required: true } } }     # the 509 scenario P&Ls
  var-history:      { params: { scope: { type: scope, required: true }, days: { type: int, default: 250, max: 750 } } }
  what-if:          { params: { sandbox: { type: sandbox-ref, required: true } } }
reads:                                  # the inputs, always read as the requesting user, through the normal routing
  - { kind: pnl-vector }                # served by the static connector risk-store (Delta)
  - { kind: scenario-set }
  - { kind: trade }                     # only for what-if: the trade to amend
  - { kind: pnl-hypothetical }          # backtesting
engine:
  type: python                          # python | java | in-process (trusted Java only)
  entry: drishti_var.connector:VarEngine
  pool: risk                            # a worker pool of config/compute/compute.yaml (PYTHON_COMPUTE.md)
requires:                               # PYTHON_COMPUTE.md section 4; nothing runs until an administrator approves it
  python:
    version: "3.12"
    lock: requires/var-engine.lock      # pinned wheels with sha256
  java: []
triggers:                               # section 7
  - on: write-back
    kinds: [trade]
    affects: "scopes_of(trade.TT_BOOK)"           # the book and every node above it
    recompute: [var-result, var-contribution]
  - on: data-version
    kinds: [pnl-vector]
    affects: all
    recompute: [var-result, var-contribution, var-history]
  - on: schedule
    cron: "0 */15 7-19 * * MON-FRI"
    zone: America/New_York
    affects: "subscribed"                         # only scopes someone is watching
    recompute: [var-result]
coalesce: { quiet: 250ms, max-wait: 2s }          # section 10
cache:   { results: 20000, bytes: 512MB, ttl: 8h, store: disk }  # section 11
limits:  { timeout: 30s, timeout-batch: 10m, max-inflight-per-user: 4, max-scope-positions: 5000000 }
history: { dated: true }                          # a picked business date computes on that date's inputs
```

- **The pack does not change** except for routes: `var-result: var-engine` beside `pnl-vector: risk-store`, as for any kind.
- **`reads:` is checked at load:** each kind must be routed to a connector; a reactive connector may read another reactive connector,
  but a cycle is refused at load with the cycle named.
- **`params:` is checked twice:** at load (types, defaults, allowed values) and per request (a value outside `allowed` is refused in
  words: *confidence 0.9 is not allowed for var-result; allowed: 0.95, 0.975, 0.99*).
- **`result:` names a JSON schema** in the connector's folder of supporting files; every result is validated against it before it is
  cached or sent. A result that does not match is an error of the connector, never shown as data.
- **Off until approved.** A reactive connector whose `requires:` is not approved loads as *waiting for approval*: Admin → Connectors
  says so, Health says `DOWN: requirements not approved`, and panels reading its kinds say *This computation is not approved yet*.

## 6. The request and its lifecycle

### 6.1 The request

The connector SPI's "the id is the question" is kept, but a reactive kind takes **typed parameters** too. A Sutra panel names them
with a planned (2.8.0) `params:` key on `source:`:

```yaml
panels:
  - use: kpi-tiles@1
    with: { id: headline, rows: $src }
    source:
      kind: var-result
      id: "'DESK:' + $.DESK_ID"                       # the scope, written as an id: readable, linkable, cacheable
      params: { confidence: ctl.confidence, horizon: 1, method: ctl.method }
      as-of: ctl.asof
```

Inside the server every read of a reactive kind becomes one **compute request**:

```json
{
  "requestId": "req-01JB4C2Q…",               // ULID, the correlation id: the answer goes back to this and nothing else
  "connector": "var-engine", "kind": "var-result",
  "id": "DESK:RATES-NY",
  "params": { "confidence": 0.99, "horizon": 1, "method": "historical" },
  "asOf": { "businessDate": "2026-10-23", "knownAt": null, "pin": "pin-7f3c…" },     // section 8
  "viewer": { "user": "asha", "roles": ["risk"], "viewSignature": "vs-91a2…" },      // section 9
  "origin": { "type": "panel", "screen": "desk/var-explorer", "panel": "headline" }, // or trigger, api, bi
  "class": "interactive"                     // interactive | event | batch (section 10)
}
```

### 6.2 The lifecycle

| Step | What happens | Where |
|---|---|---|
| 1. **Receive** | The router sends the read to the connector the kind is routed to, exactly as today. | server |
| 2. **Authorise** | The viewer's roles may open the kind; the viewer holds the planned (2.8.0) `compute` power for computed kinds; the scope is within the viewer's entitlements ([VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)). Refused in words, before any work. | server |
| 3. **Pin the as-of** | The request's as-of is resolved to concrete input versions (the Delta table version of `pnl-vector` for the date, the edit-log watermark), once, and recorded as a **pin** ([section 8](#8-point-in-time-stamping-ordering-and-idempotence)). | server |
| 4. **Key** | The canonical key: connector, kind, id, normalised params, pin, the viewer's **view signature** ([section 9](#9-isolation-two-users-never-see-each-others-answers)), engine version, environment lock hash. | server |
| 5. **Cache and single flight** | A cached result for the key is answered at once. A request for a key already being computed **joins** that computation (one computation, many waiters, each answered under its own request id). | server, then the key's owner node ([section 14](#14-several-servers-drishtinet)) |
| 6. **Admit** | The request takes a slot in its class's queue; a full queue answers *busy, try again* (interactive) or waits (event, batch) ([section 10](#10-coalescing-incremental-recompute-and-back-pressure)). | server |
| 7. **Read the inputs as the user** | Each `reads:` kind is read through the normal router **as the requesting viewer**: kinds, row entitlements and field masks applied before a byte reaches the computation. Bulk inputs travel as Arrow (the existing `scan` SPI). | server |
| 8. **Compute** | The request, the inputs and a scratch folder go to a worker of the connector's pool. The worker never fetches data itself and holds no credentials. | worker ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) |
| 9. **Progress** | A long computation sends partial results (*desks 7 of 12 done*, a first estimate) on the requester's live channel; the panel paints them, marked *computing 58%*. | worker → server → console |
| 10. **Stamp, validate, cache** | The result is validated against the kind's schema, stamped (pin, computedAt, engine and environment), and cached under its key. | server |
| 11. **Answer** | The result goes to the request id that asked, and to every request id that joined it, each masked for its own viewer. | server |
| 12. **Audit** | Who asked what, with which pin, how long it took, which result hash ([section 15](#15-observability-and-audit)). | server |

**Timeout.** An interactive request has a budget (`limits.timeout`, 30 s by default). If the panel's own wait (the view's link budget,
`drishti.graph.link-budget`) passes first, the panel is drawn *computing* and fills itself from the live channel when the result
lands; the computation is not abandoned because a page was drawn.

**Cancel.** A request is cancelled when every waiter has gone (the panel closed, the page left, a new selection replaced it). A
computation already running in a worker is stopped only if it is interactive and nobody else joined it.

## 7. Triggers: computing on events

A reactive connector recomputes **without being asked** when a declared trigger fires, and pushes the new result to the screens that
watch it.

| `on:` | Fires when | The event carries | Typical use |
|---|---|---|---|
| `request` (always on) | a panel, a BI query or an API call reads the kind | the request | every computed kind |
| `write-back` | a change is **applied** in the edit log ([WRITE_BACK.md section 18](WRITE_BACK.md#18-280-saving-through-connectors-triggers-and-viewers)) | change id, kind, entity, lane, seq, by, the fields changed | an amended trade recomputes the VaR of its book and every node above it |
| `live` | a live connector delivers a message for one of the listed kinds (Kafka, MQ, Redis) | kind, id, the source's offset or sequence | a trade booked in the source system recomputes its book's VaR intraday |
| `data-version` | a static connector's data version moves for a kind and date (a new lake load is published) | kind, business date, old and new version | the overnight vectors land: every cached result of that date is stale, the watched ones recompute |
| `schedule` | a cron expression, in a named zone | the tick time | refresh the watched scopes every 15 minutes in trading hours |

### 7.1 From an event to the scopes it affects

Each trigger says which results it affects with `affects:`:

- `all`: every cached result of the connector for the event's business date is marked stale; those somebody watches recompute.
- `subscribed`: only results some open screen is watching.
- an **expression** over the event (Rachana-EL, as panels use): `"scopes_of(trade.TT_BOOK)"` returns the keys the event touches. The
  function `scopes_of` is supplied by the connector (for VaR: the book and its ancestors in the hierarchy, plus every saved ad-hoc
  scope that contains the trade; [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)).

Only affected results that **somebody is watching** are recomputed at once; the others are only marked stale and recompute on their
next read. A trigger never computes for a screen nobody has open.

### 7.2 What a trigger may and may not do

- It recomputes **shared** results (a book's VaR as seen by the risk role) and pushes them; it never recomputes a user's private
  what-if sandbox, which changes only when its owner acts.
- It runs under the **connector's service identity** for reading inputs that every subscriber shares, and the result is then
  **re-masked per subscriber** before it is pushed ([section 9.3](#93-pushing-one-recomputed-result-to-many-viewers)). A subscriber
  whose rights produce a different view signature gets a result computed for that signature (one per distinct signature, not one per
  person).
- It is **idempotent**: an event already processed (same change id, same Kafka offset, same schedule tick) is ignored
  ([section 8](#8-point-in-time-stamping-ordering-and-idempotence)).

## 8. Point-in-time stamping, ordering and idempotence

### 8.1 The stamp on every result

```json
"stamp": {
  "businessDate": "2026-10-23",
  "knownAt": null,                                      // or an instant, for "as known at" reads (HISTORY_QUERIES.md)
  "pin": "pin-7f3c…",
  "inputs": {                                           // exactly what the computation read
    "risk-store/pnl-vector":   { "version": "delta:v41", "date": "2026-10-23" },
    "risk-store/scenario-set": { "version": "delta:v12" },
    "edit-log":                { "watermark": { "L17": 3412, "L42": 988 } },   // the last applied change per lane included
    "trade-kafka/trade":       { "offsets": { "p3": 182277 } }
  },
  "trigger": { "type": "write-back", "eventId": "chg-01JB3Z…" },               // absent for a plain request
  "computedAt": "2026-10-23T14:02:13.418Z",
  "engine": { "connector": "var-engine", "entry": "drishti_var.connector:VarEngine", "package": "drishti-var 0.1.0" },
  "environment": "env-sha256:7f3c9a…",                    // the locked environment (PYTHON_COMPUTE.md section 5)
  "resultHash": "sha256:…"
}
```

The stamp is what *How this view was built* shows for a computed panel, and what makes a number **reproducible**: the same pin, the
same environment and the same engine give the same result hash.

### 8.2 The pin

When a request (or a screen, [section 12.2](#122-one-as-of-for-the-whole-screen)) is resolved, the server records a **pin**: the
concrete version of every input for the business date at that moment. A pin is small (a few hundred bytes), kept for the request's
life and, for results that are cached or audited, with the result. Two panels of one screen that share a pin computed on the same
data, whatever loads arrive while they run.

### 8.3 Ordering

- Results for one key are **versioned by their watermark**: the input versions, edit-log seqs and offsets they include. A result is
  newer than another if every watermark is at least as high and one is higher.
- **A late result never overwrites a newer one.** If an event-driven recompute that started at watermark `L17:3410` finishes after one
  that started at `L17:3412`, the older is dropped (counted, never shown).
- Events of one **lane** of the edit log are processed in seq order (the lane already orders them,
  [WRITE_BACK.md section 7](WRITE_BACK.md#7-edit-queues-lanes-the-edit-log-and-the-transaction-handler)); events of one **Kafka
  partition** in offset order. Across lanes and partitions there is no order to keep: a result simply includes a set of
  watermarks.

### 8.4 Idempotence

Every event has an id (the change id, `connector/partition/offset`, `schedule/<tick>`, `data-version/<kind>/<date>/<version>`). The
connector keeps the ids of processed events per key for `triggers.dedupe-window` (default 24 h). An event seen again (a redelivered
Kafka message, a resent change after a fail-over) is ignored. Recomputing is itself idempotent: the same pin gives the same result.

## 9. Isolation: two users never see each other's answers

The product owner's question (2026-10-24): *if two users write back or ask at the same time, can their responses mix?* No. Five rules
make sure of it.

### 9.1 The rules

1. **Every request and every event has a correlation id** (`requestId`, the change id). A result is delivered only to the request ids
   that asked for it or joined it, over the requester's own live channel. There is no broadcast of a computed result to "everyone on
   the page".
2. **The cache key includes the viewer's view signature.** A view signature is a hash of what decides what the viewer may see: the
   roles' kinds, row entitlements (for VaR: the hierarchy nodes the viewer may see position by position or only in aggregate) and
   field masks. Two people with the same rights share a cached result (that is the point of the cache); two people with different
   rights never do.
3. **What-ifs are private sandboxes.** A what-if is a sandbox owned by one user (`sandbox:asha:01JB…`); only its owner can read, change
   or compute it; a request naming another user's sandbox is refused as *not found* (it does not even confirm the sandbox exists). A
   sandbox can be shared read-only as a frozen copy, never as a live link to the owner's changes.
4. **Masks are applied per recipient.** A result computed once for a signature is still passed through the recipient's field masks
   when it is sent (belt and braces: a signature mismatch can only hide, never reveal).
5. **No state survives between requests in a worker.** A worker process handles one request at a time and is reset (or forked fresh)
   between requests ([PYTHON_COMPUTE.md section 7](PYTHON_COMPUTE.md#7-the-worker-pool)); a Python global, a cached object or a temp
   file of one request is never seen by the next.

### 9.2 Two traders, two amendments, the same second

Asha (RATES-NY) amends MX-50000001 at 14:02:11.200; Ben (also RATES-NY) books CLY-30000002 at 14:02:11.350. Both changes apply in the
edit log (different lanes, `L17:3412` and `L42:988`).

| | Asha sees | Ben sees | Everybody watching RATES-NY sees |
|---|---|---|---|
| Their own change's state | *applied* on her row (her change id) | *applied* on his row | the updated records, with a "changed by" mark |
| **Impact of their own change** | *your change: incremental VaR +1.8m on RATES-NY*, computed against the state just before her event | *your change: incremental VaR −0.4m*, against the state just before his | nothing private |
| **Shared VaR** | one number including **both** changes, in event order, stamped `L17:3412, L42:988` | the same number, the same stamp | the same number, the same stamp |

- The **incremental impact** is attributed per event: the connector computes the vector of the scope before and after each event, in
  watermark order, and sends each submitter the difference for **their** event, under their change id. Ben never receives Asha's
  impact, and neither receives the other's private what-ifs.
- The **shared** number is one result for the scope and signature, coalesced over both events ([section 10](#10-coalescing-incremental-recompute-and-back-pressure)),
  stamped with both watermarks, so a reader can tell exactly which changes it includes.

### 9.3 Pushing one recomputed result to many viewers

```
 trigger (event) ─▶ affected keys ─▶ for each key: group the subscribers by view signature
                                      ├─ signature A (risk, all positions): compute once ─▶ mask per subscriber ─▶ push to A1, A2, A3
                                      └─ signature B (desk head, aggregate only): compute once ─▶ mask ─▶ push to B1
```

Subscribers are the open live channels that read the key (LIVE.md's one subscription per entity, kept per key and signature).

## 10. Coalescing, incremental recompute and back-pressure

### 10.1 Coalescing bursts

A burst of events for one key (twenty amendments to one book in a second) must not mean twenty computations.

- **Quiet window** (`coalesce.quiet`, default 250 ms): after an event, wait until no new event for the key arrives for this long.
- **Maximum wait** (`coalesce.max-wait`, default 2 s): never wait longer than this after the first event of a burst; compute with what
  has arrived. A steady stream therefore still yields a result every two seconds.
- **At most one computation per key at a time**; events that arrive during it cause **exactly one more** afterwards (the same rule
  LIVE.md uses for view rebuilds, [LIVE.md, guarantees](LIVE.md#guarantees-and-limits)).
- Per-event impacts ([section 9.2](#92-two-traders-two-amendments-the-same-second)) are still computed per event, because they are
  cheap (a difference of vectors), even when the shared result is coalesced.

### 10.2 Incremental recompute

A connector may declare that a kind is **incremental**: given the previous result and the events since, it can produce the new result
without starting again. For VaR this is natural: the P&L vector of a scope is a sum, so an amended trade changes the scope's vector by
(new vector − old vector), and only the scopes above the trade change ([VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)).
The connector's entry point receives `previous` (result plus stamp) and `events`; it may answer *full recompute needed* when the
events are too many or of a kind it cannot apply incrementally. Every Nth incremental result (`incremental.verify-every`, default 50)
is checked against a full recompute in the background; a difference beyond tolerance is a health warning and the full result wins.

### 10.3 Back-pressure and fairness

| Class | Who | Queue | When full |
|---|---|---|---|
| `interactive` | a person waiting on a panel or an API call | short, highest priority | *busy, try again in a moment*; the panel shows the last good result, marked stale |
| `event` | triggers | bounded per connector (`limits.event-queue`, default 10,000 keys); keys, not events, are queued, so a burst for one key is one entry | the oldest *unwatched* keys are dropped to *stale, recompute on read*; watched keys are never dropped |
| `batch` | schedules, BI runs, precomputation | lowest priority, a fixed share of workers (`pool.batch-share`, default 25%) | waits |

- **Per-user fairness:** at most `limits.max-inflight-per-user` requests of one user run at once; the rest queue behind other users'.
- **Admission by cost:** a request declares (or the connector estimates) its size (for VaR: the number of positions in the scope); one
  over `limits.max-scope-positions` is refused before any read, with the size in the message, or sent as a BI run job
  ([BI_RUNS.md](BI_RUNS.md)) if the caller allows.

## 11. Caching

| Tier | Holds | Key | Invalidated by |
|---|---|---|---|
| **in memory** (per node) | recent results | the key of [section 6.2](#62-the-lifecycle) step 4 | a trigger that affects the key; size and age limits |
| **on disk** (`store: disk`) | expensive results, under `<data dir>/compute/results/<connector>/` | the same | the same; a restart keeps it |
| **inputs** | Arrow batches of inputs read for a pin (the P&L vectors of a desk) | connector, kind, scope, pin, signature | a new data version of the input |

- **The pin is in the key**, so a cached result is never served for another state of the inputs; a new load or an applied edit yields
  a new pin and therefore a new key. Old keys age out.
- **The engine version and the environment hash are in the key**: upgrading the VaR package or a pinned wheel never serves a result
  computed by the previous code.
- **Single flight** (one computation per key, others join) holds within a node and, through the key's owner
  ([section 14](#14-several-servers-drishtinet)), across a cluster.
- **Never cached:** a result that failed validation, a partial result, a private sandbox's result beyond the owner's session
  (sandbox results live with the sandbox, not in the shared cache).
- `purgeCaches()` (Admin → Caches, as for every connector) drops all three tiers.

## 12. One screen, many connectors

A pack already combines connectors: each kind is routed to one connector, and each panel can name its own `source:`. This design
makes combining **computed** results first-class.

### 12.1 An example: the desk VaR screen

```yaml
# config/packs/market-risk/sutras/pages/desk-var.v1.sutra.yaml       (planned, 2.8.0)
match: { kind: desk }
title: "VaR · ${$.DESK_ID}"
consistency: snapshot                       # one pin for the whole screen (12.2)
controls:
  - { id: confidence, type: enum, options: [0.99, 0.975], default: 0.99 }
panels:
  - id: headline                            # reactive: var-engine
    use: kpi-tiles@1
    source: { kind: var-result, id: "'DESK:' + $.DESK_ID", params: { confidence: ctl.confidence } }
  - id: books                               # reactive: var-engine, contributions by child
    use: ranked-bars@1
    source: { kind: var-contribution, id: "'DESK:' + $.DESK_ID", params: { by: child } }
    emits: [ { name: select, on: bar-click, payload: { scope: "@.SCOPE_ID" } } ]
  - id: path                                # reactive: the 509 scenario P&Ls of the picked book
    use: series-bars@1
    source: { kind: var-path, id: "@books.select.scope" }
  - id: limits                              # static: core pack, a Delta table
    use: field-list@1
    source: { kind: desk, id: "$.DESK_ID" }
  - id: blotter                             # live: trades ticking from Kafka
    use: row-picker@1
    source: { kind: portfolio-trades, id: "$.DESK_ID" }
    refresh: 10
```

Five panels, three kinds of connector, one screen. Nothing in the pack names a library or a worker.

### 12.2 One as-of for the whole screen

- **`consistency: snapshot`** (planned, 2.8.0; the default for screens with a computed panel): when the screen is opened the server
  resolves **one pin** for the business date and every panel's source is read and computed against it. The strip says *as of
  2026-10-23 · pinned 14:02:13*. When newer data arrives (a trigger fires for a key the screen shows), panels are **not** silently
  mixed: the screen shows *Newer data: 3 changes since 14:02 · Update all*, and **Update all** re-pins and refreshes every panel
  together. A screen that is live ([SUTRA_NEXT.md section 9](SUTRA_NEXT.md#9-live-panels-refresh-control-and-freshness)) may set
  `consistency: snapshot, follow: true`: the screen re-pins by itself, at most once per `coalesce.max-wait`, and all panels move together.
- **`consistency: freshest`**: each panel shows its newest result as it lands; each panel's header says its own as-of. For monitoring
  screens where one late panel must not hold the others back.
- The view model carries each panel's pin; a panel computed on a different pin from the screen's (it could not be computed on the
  screen's pin in time) is marked *computed on newer data* in amber, never presented as consistent.

### 12.3 Cross-connector joins

Two ways, and the design keeps both:

1. **On the screen**, through events: a click on a book in a reactive panel opens static and live panels for the same book (as the
   book desk does today). No join engine is needed; each panel reads its own source.
2. **In the reactive connector**, through `reads:`: when the answer itself needs data from several connectors (VaR over vectors from
   the lake and intraday vectors computed from Kafka trades), the connector reads them all as the user, on one pin, and returns one
   result. The join is the connector's code and is tested with it.

### 12.4 Partial failure, per panel

Each panel has its own state, drawn in place, never as an error page:

| State | What the panel shows |
|---|---|
| `computing` | the last good result, dimmed, with *computing 58%* and the partial result if the connector sends one |
| `ready` | the result; the header's freshness from the result's stamp |
| `stale` | the last good result in amber: *stale · var-engine is recomputing after 3 changes* |
| `failed` | the connector named in words: *var-engine could not compute this: the worker ran out of memory (request req-01JB…)*; with the last good result if there is one |
| `refused` | why, in words: *not approved yet*, *your role cannot see positions of RATES-LDN*, *the scope has 6.2 million positions; the limit is 5 million* |
| `hidden` | *Hidden for you* (masks), as today |

The other panels are unaffected. *How this view was built* lists each panel's source, pin, state and request id.

## 13. Failure

| What fails | What happens | What the user sees |
|---|---|---|
| a worker crashes or is killed (memory, time) | the request fails; the worker is replaced; the same request is retried **once** on a fresh worker if it is interactive and the crash was not a limit | `failed` with the reason and request id; the last good result |
| the same request crashes workers twice | the key is **quarantined** for `limits.quarantine` (default 10 min): further requests are refused at once instead of crashing more workers | *this computation keeps failing; an administrator has been told* |
| an input connector is down | the request fails with that connector named; triggers from it stop | `failed`: *risk-store isn't answering* |
| the worker pool is exhausted | interactive requests queue up to their timeout; event keys are coalesced; batch waits | `computing`, then `failed: busy` |
| requirements are revoked by an administrator | in-flight requests finish; new ones are refused | `refused: not approved` |
| a result fails its schema | an error of the connector, logged with the request; never cached or shown | `failed: the computation returned an invalid result` |

A connector whose error rate passes `limits.circuit` (default 50% over one minute) opens its **circuit**: requests are answered from
the cache (marked stale) or refused, for 30 s, then one probe is let through. Health shows `DEGRADED: circuit open`.

## 14. Several servers (Drishti*net)

- **Who computes a key:** the key's **owner node**, chosen on the Drishti\*net ring by the key's hash among the nodes that serve the
  connector ([DRISHTI_NET.md](DRISHTI_NET.md)). Any node receiving a request forwards it to the owner (signed, on behalf of the
  user, as views are forwarded today), so single flight and the cache hold across the cluster.
- **Triggers** are received by every node (an applied edit is published on the cluster; a Kafka consumer group gives each partition to
  one node) and routed to the owner of each affected key.
- **Pushes** go from the owner to the nodes holding the subscribers' live channels (the existing live relay).
- **A node leaving:** its keys move to the next owner; in-flight computations there are lost and recomputed on the next read or
  trigger (results are reproducible from their pins, so nothing is wrong, only late). Private sandboxes are kept in the content store
  ([CONTENT_STORE.md](CONTENT_STORE.md)), so they survive a node.

## 15. Observability and audit

- **Admin → Compute** (planned, 2.8.0): per reactive connector, its pools and workers (busy, idle, memory, requests served, recycled),
  queues per class (depth, oldest wait), latency (p50, p95, p99 per kind), cache hit rate, coalescing ratio (events in, computations
  out), quarantined keys, circuit state, and its approved requirements.
- **Metrics** under `drishti.compute.*` on the server's metrics endpoint, labelled by connector and kind.
- **Tracing:** the request id is carried into the worker and into every log line and metric exemplar; Admin → Compute finds a
  request by id and shows its timeline (received, pinned, admitted, inputs read, computed, answered).
- **Audit:** every computed answer delivered to a person is an audit entry: who, which kind and id, params, pin, signature, result
  hash, duration, and the trigger if any. What-if sandboxes record every change and computation. Entries are signed like all audit
  entries and kept as the site's audit retention says.
- ***How this view was built*** on a computed panel shows the stamp of [section 8.1](#81-the-stamp-on-every-result) in words: *VaR
  computed by var-engine (drishti-var 0.1.0) at 14:02:13 over 18,412 P&L vectors of 2026-10-23 (lake version 41) and 3 changes
  applied since (L17:3412, L42:988)*.

## 16. The SPI and the APIs

### 16.1 Java SPI (planned, 2.8.0)

The generic `reactive` plugin implements `SourcePlugin` once; what differs per connector is behind a small interface, implemented by
the worker bridge (Python, Java worker) or by trusted in-process Java code:

```java
// drishti-api, com.ash.drishti.api.compute (planned)
public interface ComputeEngine extends AutoCloseable {
    ComputeManifest manifest();                                   // kinds, params, result schemas, incremental kinds
    ComputeResult compute(ComputeRequest request, ComputeInputs inputs, ProgressSink progress) throws ComputeException;
    default Optional<ComputeResult> incremental(ComputeRequest request, ComputeResult previous,
                                                List<ComputeEvent> events, ComputeInputs inputs) { return Optional.empty(); }
    default Set<String> affected(ComputeEvent event, String expression) { return Set.of(); }   // scopes_of(...)
}
```

`ComputeInputs` hands the engine Arrow batches already read as the user; the engine never sees a connector, a credential or the
router.

### 16.2 HTTP API (planned, 2.8.0)

Panels and BI read reactive kinds through the existing view and source paths; nothing new is needed for them. For programs:

| Method and path | Does |
|---|---|
| `POST /api/v1/compute/{connector}/{kind}` | submit a request (`id`, `params`, `asOf`); answers the result inline when ready within the budget, otherwise `202` with the request id |
| `GET /api/v1/compute/requests/{requestId}` | the state and, when done, the result (only to the user who asked) |
| `GET /api/v1/compute/requests/{requestId}/stream` | progress and the result as server-sent events |
| `DELETE /api/v1/compute/requests/{requestId}` | cancel |
| `GET /api/v1/admin/compute` | Admin → Compute's data (administrator) |

A token needs the planned (2.8.0) `compute` scope. The CLI gains `drishti.py compute run|status|cancel` (planned, 2.8.0).

## 17. Configuration reference

| Key (connector file) | Default | Meaning |
|---|---|---|
| `mode` | from the plugin | `static`, `live`, `on-demand`, `reactive` |
| `kinds.<kind>.params` | — | typed parameters: `type` (`scope`, `decimal`, `int`, `enum`, `date`, `sandbox-ref`, `text`), `default`, `allowed`, `required`, `max` |
| `kinds.<kind>.result.schema` | — | JSON schema each result must match |
| `kinds.<kind>.incremental` | `false` | the engine can update a previous result from events |
| `reads` | — | input kinds, read as the requester through the router |
| `engine.type`, `engine.entry`, `engine.pool` | — | where the code runs ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) |
| `requires` | — | libraries ([PYTHON_COMPUTE.md section 4](PYTHON_COMPUTE.md#4-requires-what-a-connector-needs-to-run)) |
| `triggers[]` | — | `on`, `kinds`, `affects`, `recompute`, `cron`, `zone` |
| `triggers.dedupe-window` | `24h` | how long processed event ids are remembered |
| `coalesce.quiet`, `coalesce.max-wait` | `250ms`, `2s` | burst handling |
| `incremental.verify-every` | `50` | a full recompute checks every Nth incremental result |
| `cache.results`, `cache.bytes`, `cache.ttl`, `cache.store` | `20000`, `512MB`, `8h`, `heap` | result cache |
| `limits.timeout`, `limits.timeout-batch` | `30s`, `10m` | budgets |
| `limits.max-inflight-per-user` | `4` | fairness |
| `limits.event-queue` | `10000` | event keys queued |
| `limits.quarantine`, `limits.circuit` | `10m`, `0.5` | failure handling |
| `history.dated` | `true` | a picked business date computes on that date's inputs |

The worker pools themselves are configured once per site, in the planned (2.8.0) `config/compute/compute.yaml`
([PYTHON_COMPUTE.md section 13](PYTHON_COMPUTE.md#13-configuration)).

## 18. Build plan

| Step | Delivers | Accepted when |
|---|---|---|
| R1 (**built**) | the `reactive` plugin and `ComputeEngine` SPI; typed `params:` on `source:` (lint, view model); the compute request, key, pin and stamp; cache and single flight on one node; an **in-process Java** test engine | a panel reads a computed kind with params; two panels asking the same key cause one computation; a new data version gives a new key |
| R2 (**built**) | the worker bridge to the Python pool ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) steps C1–C3); progress on the live channel; per-panel states | a Python engine computes a result for a panel, with *computing N%* shown, and a crash shows `failed` in that panel only |
| R3 (**built**; `write-back` from the edit log since W6/W7, 2026-10-25, and through the events API) | triggers: `write-back`, `live`, `data-version`, `schedule`; `affects:`; coalescing; ordering by watermark; dedupe | 200 events for one key in a second cause at most 2 computations; an older result never replaces a newer; a redelivered event is ignored |
| R4 (**built**, except per-event impacts: W6/V5) | isolation: view signatures in keys, per-recipient masks on push, per-event impacts, private sandboxes | the two-traders test of [section 19](#19-tests) passes |
| R5 (planned) | `consistency: snapshot` and `freshest`; *Newer data · Update all* | a screen of five panels on three connectors shows one pin; an applied edit offers *Update all* and re-pins all panels together |
| R6 (planned) | Drishti\*net: key owners, forwarding, cluster single flight, trigger routing; Admin → Compute; audit; circuit and quarantine | three nodes, one key requested on each at once: one computation; a node killed mid-computation: the next read recomputes on the new owner |

## 19. Tests

- **Isolation (the product owner's question):** two users with different entitlements request the same scope at the same instant; each
  receives only the result for their own signature, under their own request id; a third user with the first user's rights shares the
  first's cached result. Two users submit write-backs to one book within 100 ms; each receives only their own incremental impact;
  both see one shared result stamped with both watermarks. A request naming another user's sandbox is *not found*.
- **Ordering:** random event streams over many keys and lanes; results for a key are never replaced by one with a lower watermark.
- **Coalescing:** a burst of N events yields at most ⌈burst length / max-wait⌉ + 1 computations per key.
- **Idempotence:** a redelivered Kafka message and a resent change id do not recompute.
- **Incremental equals full:** for VaR, a random sequence of amendments applied incrementally equals a full recompute to 1e-9.
- **Failure:** a crashing engine is retried once and quarantined after the second crash; the circuit opens and closes.
- **Masks:** a field masked for a subscriber never appears in a pushed result.
- **Cluster:** the R6 acceptance tests, plus a network split (results stay correct; late, never mixed).
- **Windows:** everything except the native QuantLib-Java worker runs on the Windows QA machine
  ([PYTHON_COMPUTE.md section 12](PYTHON_COMPUTE.md#12-windows)).

## 20. Open questions for the product owner

1. **Default consistency.** Should every screen with a computed panel default to `consistency: snapshot` (one pin, *Update all*), or
   should `freshest` be the default and snapshot opt-in?
2. **Triggers under the service identity.** Is it acceptable that shared recomputes read inputs as the connector's service identity
   and then compute once per distinct view signature, or must every recompute run as a named user?
3. **Kafka-triggered intraday VaR in 2.8.0?** The `write-back` and `data-version` triggers are needed for 2.8.0; is the `live`
   (Kafka) trigger in 2.8.0 too, or 2.8.x?
4. **Where private sandboxes live.** The content store (survives nodes, per user) is proposed; is a time limit wanted (for example
   sandboxes expire after 30 days unless saved)?
5. **Limits.** Are 5 million positions per interactive request and 4 in-flight requests per user the right starting limits?
6. **In-process Java engines.** Allow trusted, light Java engines inside the server process (with their own classloader), or require
   every engine to run in a worker?

## 21. As built: R1 to R4

Built for 2.8.0 (unreleased), on the compute workers C1 to C3 ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)). The reference is
[REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md); the sample is the `compute-demo` pack (not loaded by default).

| Part | As built | Where |
|---|---|---|
| SPI | `ComputeEngine`, `EngineManifest`, `EngineRequest`, `EngineInputs` (documents by input name), `EngineResult` (rows, tables, meta), `EngineProgress`, `EngineException`, `ComputeEvent`, `Viewer`, `ComputeRef` (the id with its parameters). The names differ from section 16.1 (`Engine…` rather than `Compute…`) so they do not clash with drishti-compute's worker types | `drishti-api`, `com.ash.drishti.api.compute` |
| Plugin | `reactive` (built-in, `ReactiveSourcePlugin`), `ReactiveConnector` (the lifecycle), `ReactiveSpec`, `ParamSpec`, `ResultCache`, `Coalescer`, `SeenEvents`, `Watermark`, `Triggers`, `Sandboxes`, `RequestEvents`, `ReactiveHub` (the host the server installs) | `drishti-compute`, `com.ash.drishti.compute.reactive` |
| Connector file | the design's keys at the top level (`mode`, `kinds` with `params`, `reads`, `engine`, `triggers`, `coalesce`, `cache`, `limits`, `history`, `incremental`); refused on any other plugin. Added: `kinds.<k>.compute` (the engine's name for the kind), `engine.split`/`chunk-rows`/`reduce` (fan-out), `engine.version`, `limits.wait`, `limits.max-input-rows`, `limits.dedupe-window` (the design's `triggers.dedupe-window`), `reads[].ids` and `{inputs.<as>.<FIELD>}` (a read takes its ids from an earlier one) | `ConnectorFiles` |
| Templates | single braces: `{id}`, `{params.x}`, `{inputs.book.MEMBERS}`, `{payload.BOOK_ID}`, `{entity}`; `${NAME}` stays an environment variable | `Templates` |
| Result rules | `result: { required, types, max-rows }` instead of a JSON schema file (`result.schema`: planned) | `Results` |
| Parameters on a panel | `source: { kind, id, params: { name: expression } }`, only with `id`; lint `DRS-2012` (shape) and `DRS-2057` (not with `where`/`search`); evaluated as the viewer (a masked value: *not available to your role*) into the id `ID~name=value` | `WiringReader`, `ScreenWire` |
| Panel states | `ready`, `computing` (progress), `stale`, `failed`, `refused` in the view model (`PanelView.compute`) and drawn in place; the console follows `compute:<request>` on the live channel and fetches the panel again when it is done or updated | `ViewModel`, `panels.html`, `compute.js` |
| Lifecycle | authorise (the viewer's kinds), parameters, pin (the read kinds' data versions and the sandbox version), key (with the view signature: a hash of the roles and the embedding app, `Principal.securitySignature`), cache, single flight, admission (per-user in-flight; the pool's fair queue), inputs read and masked as the viewer, engine, progress, validate, stamp (`STAMP`: pin, inputs, watermark, trigger, engine, where it ran, signature, result hash), cache, audit (`compute.answer`, `compute.failed`) | `ReactiveConnector` |
| Engines | Python in a compute pool (Arrow in and out; fan-out over every worker of the pool and its peers with `split` and `reduce`); Java in the server (Drishti's own always; others with `compute.in-process-java: true`) | `PythonRunner`, `JavaRunner` |
| Triggers | `write-back` and any event through `POST /api/v1/compute/events` (administrators) and `ReactiveHub.publish` (the write-back module's hook: built 2026-10-25, every applied change after its commit, with the watermark `L<lane> -> seq`, replayed per lane from the edit log's cursor; [WRITE_BACK.md section 19](WRITE_BACK.md#19-as-built-280-2026-10-25)); `live`: the entities of its kinds a watched computation read are followed, a new generation recomputes the results that read them; `data-version`: the load signal and a check every 30 s; `schedule`: `cron` (Spring's six fields) or `every`. `affects`: `all`, `subscribed`, id templates, or an expression a Java engine answers. Coalescing, watermark ordering, dedupe as designed | `Triggers`, `Coalescer` |
| Isolation | request ids per read, events to the owner only, view signature in keys, inputs masked per viewer, a recomputed result read again by each watcher through their own view, private sandboxes (`sbx-…`, frozen copies), live views rebuilt on behalf of their viewer | `RequestEvents`, `ViewerExecutor` |
| HTTP and CLI | `POST /api/v1/compute/{connector}/{kind}`, `GET …/requests/{id}/stream`, `DELETE …/requests/{id}`, `POST …/events`, sandboxes, `GET /api/v1/admin/compute/reactive`; `drishti.py compute` `run`, `cancel`, `event`, `sandboxes`, `reactive` | `ReactiveController`, `tools/computecli.py` |

**Not built yet:** per-event impacts for each submitter (section 9.2, with W6 and V5); the result cache on disk (`cache.store: disk`);
`consistency:` and *Update all* (R5); key owners, forwarding, cluster single flight, quarantine and the circuit (R6); a JSON schema file
per kind; a `compute` power (any role that may open the kind may compute it); the designer's awareness of parameters (a source with
`params:` is kept as written).

**Measured** (a workstation, 2026-10-25; `ReactiveOverheadTest`, `PythonBridgeTest` and the compute-demo screen): a cache hit inside the
connector 4.7 µs at p50 (p99 9.6 µs); a miss with an engine that does nothing 47 µs (p99 183 µs); a miss through a Python worker (fork,
trivial engine) 2.3 ms; through the server, one computed panel from the cache 2.7 ms, the same as a plain panel (2.6 ms); the
compute-demo book screen, two computed sources and two 250-point charts, 5.0 ms. A panel never waits longer than `limits.wait`.

## 22. How the VaR engine plugs in

The VaR engine ([VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives), steps V0 to V3) becomes the `var-engine` reactive connector (R5/V4) without new
machinery:

1. **The file.** `config/connectors/var-engine.yaml` with `plugin: reactive`, the kinds of section 5 (`var-result`, `var-contribution`,
   `var-path`, `var-history`, `what-if`) and their `params:` (`scope` as `type: scope`, `confidence` as `decimal` with `allowed`,
   `horizon` as `int`, `method` as `enum`, `whatif` as `sandbox-ref`), and `result:` rules (`required: [VAR, ES]`).
2. **The engine, either of two ways.**
   - *Java, in the server* (the vector store is Java): a class implementing `ComputeEngine` in `com.ash.drishti…` (so it may run in
     process), `engine: { type: java, class: …VarComputeEngine }`, with **no `reads:`**: the VaR builder's in-memory vector store is its
     input, and it must read it **for `request.viewer()`** (the entitlement filter of VAR_GUIDE.md section 9) and **for
     `request.asOf()`**. `compute` aggregates the scope's vectors and answers `VAR`, `ES` and the path as a table; `incremental`
     applies a write-back's `delta` vector (`ComputeEvent.payload`); `affected(event, "scopes_of(trade.TT_BOOK)")` returns the book and
     its ancestors' scope ids from the hierarchy. The vector store's version for the date goes into the pin through `dataVersion` of
     the kind it serves (`pnl-vector`), so a new load is a new key.
   - *Python, in a pool* (`compute/drishti-var`): `engine: { type: python, entry: drishti_var.connector:VarEngine, pool: risk }` with
     `reads: [{ kind: pnl-vector, as: vectors, ids: "{inputs.scope.MEMBERS}" }]` after a read of the scope's members; vectors arrive as
     fixed-size Arrow vectors; `split: vectors` and `reduce: { tables: { path: [PNL] }, then: { VAR: … } }` spread a bank-wide scope over
     every worker. Its `requires:` locks numpy and the package and waits for approval.
3. **What-if.** A `sandbox-ref` parameter names the trader's private sandbox; its items (the hypothetical trades) reach the engine as the
   input `sandbox`; the key holds the owner, so it is never shared. Pricing those items is C4/C5 (QLRisk) or C6 (QuantLib Java).
4. **Triggers.** `{ on: write-back, kinds: [trade], affects: "scopes_of(trade.TT_BOOK)", recompute: [var-result, var-contribution] }`
   (W6 publishes `edit.applied` to `ReactiveHub.publish`), `{ on: data-version, kinds: [pnl-vector], affects: all }`, and a schedule.
5. **Screens.** The explorer's panels name the kinds with `source: { kind: var-result, id: "'DESK:' + $.DESK_ID", params: { confidence:
   ctl.confidence } }` and get the computing, stale, failed and refused states for free. The pack routes `var-result: var-engine` beside
   `pnl-vector: <its store>`.

