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
---
connector: reactive
category: built-in
summary: Computes kinds when they are asked for (and again when the world changes) with a Python engine in a compute pool or a Java engine in the server, over inputs read as the viewer; typed parameters, cache and single flight, progress, triggers, private sandboxes.
capabilities:
- fetch
- history
settings:
- 'reactive.spec'
- 'source-name'
auth: None of its own; it reads its inputs through the server's routing as the requesting viewer, and its workers hold no credentials.
licence: Part of Drishti (drishti-compute); no client library. Python engines run in the compute workers (NOTEBOOK_GUIDE.md).
windows: Works the same on Windows; Python pools run in warm mode there (no fork). Java engines run in the server.
doc: docs/connectors/types/reactive/README.md
---
# The reactive connector

Built-in plugin `reactive` (`compute/drishti-compute/src/main/java/com/ash/drishti/compute/reactive/`, `ReactiveSourcePlugin` and
`ReactiveConnector`): a connector whose answer is a computation (DATA_ARCHITECTURE.md). Its file names the kinds it computes with
their typed parameters, the inputs it reads, the engine, the triggers, coalescing, the cache and the limits. A pack routes the computed
kinds to it like any kind; a Sutra panel reads them with `source: { kind, id, params }`.

## What it reads

Nothing by itself: the kinds its `reads:` name, through the normal routing, **as the person asking** (their roles' kinds, field masks
applied), pinned to one business date and to the inputs' data versions. Those documents go to the engine (as Arrow tables for a Python
engine, as documents for a Java one); the engine never reads data, holds no credentials and has no network. A later input may take its
ids from an earlier one (`{inputs.book.MEMBERS}`). A private what-if sandbox named by a `sandbox-ref` parameter is the input `sandbox`.

## Settings

Not in the settings catalogue: the computation is written as top-level keys of the connector file (below), which the server hands to
the plugin as the one setting `reactive.spec` (the block as JSON). Admin → Connectors' form keeps them as written.

| Key (connector file) | Default | Meaning |
|---|---|---|
| `mode` | `reactive` | The connector's mode; a reactive connector is `reactive` |
| `kinds.<kind>.params.<name>` | — | A typed parameter: `type` (`int`, `decimal`, `text`, `scope`, `bool`, `enum` with `options`, `date`, `sandbox-ref`), `default`, `allowed`, `required`, `min`, `max` |
| `kinds.<kind>.compute` | the kind | What the engine calls the computation (one engine may compute several kinds) |
| `kinds.<kind>.result` | — | `required` (fields a one-row result must have), `types` (field: number, text, bool, list, object), `max-rows` |
| `kinds.<kind>.incremental` | `false` | The engine can update a previous result from events |
| `reads[]` | none | `{ kind, as, id \| ids \| all, limit }`: `id: "{id}"` (the default), `ids: "{params.members}"` or `"{inputs.<as>.<FIELD>}"`, `all: true` |
| `engine.type` | `python` | `python` (a compute pool) or `java` (in the server's process) |
| `engine.entry` / `engine.class` | — | `module:Class` of a Python engine; the class of a Java `ComputeEngine` (Drishti's own, or any with `compute.in-process-java: true`) |
| `engine.pool` | — | The compute pool (`config/compute/compute.yaml`) of a Python engine |
| `engine.split`, `engine.chunk-rows` | none, the site's | Cut that input over every worker of the pool, on this node and its Drishti*net peers |
| `engine.reduce` | none | `sum: [fields]`, `tables: { <t>: [fields] }`, `then: { FIELD: "min:<t>.<col>" }`: how the chunks' answers become one |
| `engine.version` | `1` | Part of every key and stamp: a new version never serves an old answer |
| `triggers[]` | none | `{ on: write-back \| live \| data-version \| schedule, kinds, affects, recompute, cron, zone, every, when }` |
| `coalesce.quiet`, `coalesce.max-wait` | `250ms`, `2s` | Burst handling of triggers |
| `cache.results`, `cache.ttl` | `20000`, `8h` | Results kept in memory |
| `limits.timeout`, `limits.timeout-batch` | `30s`, `10m` | Budgets of a person's request and of a trigger's |
| `limits.wait` | `150ms` | How long a read waits for the result before answering *computing* (keep it under `drishti.graph.link-budget`) |
| `limits.max-inflight-per-user`, `limits.event-queue`, `limits.max-input-rows`, `limits.dedupe-window` | `4`, `10000`, `1000000`, `24h` | Fairness and bounds |
| `history.dated` | `true` | A picked business date computes on that date's inputs |
| `requires` | none | The libraries it needs; nothing runs until an administrator approves (NOTEBOOK_GUIDE.md) |
| `source-name` (setting) | the connector's name | The name in provenance and Health |

## Data structure

The document of a computed kind, for `path-sum-java` of `BOOK-FX` (the values are illustrative):

```json
{"ID": "BOOK-FX", "ROWS": 40, "WIDTH": 250, "SUM": -72869.07, "WORST": -37535.83, "BEST": 29214.4,
 "RESULTS": [{"ROWS": 40, "WIDTH": 250, "SUM": -72869.07, "WORST": -37535.83, "BEST": 29214.4}],
 "TABLES": {"total": [{"DAY": 1, "PNL": 1520.4}, {"DAY": 2, "PNL": -811.2}]},
 "STAMP": {"connector": "path-sum-java", "kind": "path-sum-java", "params": {"delay-ms": "0"}, "businessDate": "2026-10-23",
           "pin": "pin-4c3b79fd25579fdf", "inputs": {"path-book": "delta:v12", "path-strip": "delta:v41"}, "watermark": {"L3": 12},
           "trigger": {"type": "write-back", "eventId": "chg-77"}, "requestId": "req-…", "computedAt": "2026-10-25T04:19:52.9Z",
           "engine": "java:vector-sum@1.0", "ran": {"chunks": 2, "workers": 2, "nodes": {"local": 2}}, "signature": "vs-dfa14378a966c484",
           "resultHash": "sha256:6efecc13…"},
 "COMPUTE": {"state": "ready", "requestId": "req-…", "connector": "path-sum-java", "hasResult": true}}
```

- **Id rule:** the question's id and its parameters in one text, sorted by name: `BOOK-FX~delay-ms=50` (`~`, `=`, `|` and `%` in a value
  are percent-encoded). A panel's `params:` are written this way by the server; a link may be written by hand.
- **Fields:** a one-row result's fields at the top level; every row under `RESULTS`; tables under `TABLES`; the stamp under `STAMP`;
  the read's state under `COMPUTE` (`ready`, `computing`, `stale`, `failed`, `refused`, with the message in words), never an empty read.
- **Keys:** connector, kind, id with parameters, date, the viewer's view signature (a hash of their roles and embedding app), the pin
  (the inputs' data versions), the engine's identity. Two people with the same rights share an answer; with different rights, never.

## Examples

The compute-demo sample pack's Java connector (`config/packs/core/compute-demo/pack.yaml`, as written to `config/connectors/path-sum-java.yaml`):

```yaml
plugin: reactive
mode: reactive
kinds:
  path-sum-java:
    compute: sum-vectors
    params:
      delay-ms: { type: int, default: 0, min: 0, max: 500 }
    incremental: true
    result: { required: [SUM, WORST], types: { SUM: number, WORST: number } }
reads:
  - { kind: path-book, as: book, id: "{id}" }
  - { kind: path-strip, as: values, ids: "{inputs.book.MEMBERS}", limit: 5000 }
engine: { type: java, class: com.ash.drishti.compute.reactive.samples.VectorSumEngine, version: "1.0" }
triggers:
  - { on: data-version, kinds: [path-strip], affects: all }
  - { on: write-back, kinds: [path-strip], affects: "{payload.BOOK_ID}" }
  - { on: schedule, every: 10m, affects: subscribed }
coalesce: { quiet: 250ms, max-wait: 2s }
limits: { timeout: 30s, wait: 25ms }
```

Its Python twin `path-sum-python` says `engine: { type: python, entry: drishti_compute.samples.paths:PathSumEngine, pool: sample, split:
values, chunk-rows: 20, reduce: { sum: [ROWS, SUM], tables: { total: [PNL] }, then: { WORST: "min:total.PNL", BEST: "max:total.PNL" } } }`.

## Troubleshooting

- Under *failed to start*: `<name>.yaml: kinds names the kinds it computes`, `engine.entry is module:Class for a python engine`,
  `engine.pool names the compute pool`, `kinds.<k>.params.<p>.type is one of …`, `triggers[i].recompute names <k>, which this connector
  does not compute`; the file's own key named in each.
- `IDLE: the reactive plugin runs only as a connector file that declares a computation`: the plugin found no connector file using it.
- `DEGRADED: compute is off on this server (compute.enabled)` or `compute pool <p>: <why>`: a Python engine cannot run; panels say
  *Not computed: compute is off on this server*. Switch compute on (docs/guides/OPERATIONS_GUIDE.md).
- `DEGRADED: <name>: engine <class> would run inside the server; this server allows only Drishti's own Java engines there`: set
  `compute.in-process-java: true` in `config/compute/compute.yaml`, or run it as a Python engine.
- A panel says *Not computed: x for k is 11: it must be at least 0 and at most 10*: the parameter is refused before any work.
- A panel stays *computing*: the browser's live channel is closed (the Live pill); the panel fills on its next refresh. Admin →
  Compute → Reactive (`GET /api/v1/admin/compute/reactive`) shows each connector's running computations, cache and coalescing.
- `NOT_APPROVED`: its `requires:` waits for an administrator (Admin → Compute).
- In the server's log each failed computation is logged with its request id; the `compute.answer` and `compute.failed` audit entries
  name the connector, kind and id.

## See also

- Below: [when to use one](#when-to-use-one), [writing the computation](#writing-the-computation), [reading a computed kind](#reading-a-computed-kind), [what happens on a read](#what-happens-on-a-read), [engines](#engines), [triggers](#triggers), [private sandboxes](#private-sandboxes), [the HTTP API](#the-http-api), [isolation](#isolation), [performance](#performance).
- [Reactive connectors, the design](../../../architecture/DATA_ARCHITECTURE.md#4-reactive-connectors) and [compute workers](../../../guides/NOTEBOOK_GUIDE.md).
- [Operating compute](../../../guides/OPERATIONS_GUIDE.md#15-compute-workers) and [tutorial 19, Part C: computed panels](../../../tutorials/19-build-a-pack-and-a-screen.md#part-c--computed-panels-a-reactive-connector).

## When to use one

| You need | Use |
|---|---|
| stored records (a lake, a database) | a static connector (`delta`, `jdbc`, ...) |
| a value fetched from a service per id, light, no parameters | an on-demand connector ([developer guide](../../../guides/DEVELOPER_GUIDE.md#1011-connectors-that-compute-data-on-demand)) |
| a computation with typed parameters, over inputs read from other kinds, in Python or Java, possibly heavy, possibly fanned out over many workers, recomputed on events | **a reactive connector** |

## Writing the computation

- **Parameters** are checked when the file loads and on every read: a value outside them is refused in words (*delay-ms for path-sum
  is 900: it must be at least 0 and at most 500*), and an unknown name too (*path-sum has no parameter 'n'*).
- **Templates** in `reads:` and `affects:` use single braces: `{id}`, `{params.book}`, `{inputs.book.MEMBERS}`, `{payload.BOOK_ID}`,
  `{entity}`. `${NAME}` stays what it is everywhere in a connector file: an environment variable, resolved when the file is read. A
  template that is exactly one variable holding a list (or comma-separated text) gives one id per element.
- **The result** is checked before it is cached or shown: `required` fields of a one-row result, their `types`, `max-rows`. A result
  that breaks them is *the computation returned an invalid result*, an error of the connector, never data.
- **In a pack** the same keys go under `connectors: <name>:` and are written to the file at first start; `routes:` sends the kinds
  (`path-sum: path-sum-python`).

## Reading a computed kind

A Sutra panel names the kind and its parameters ([RACHANA_REFERENCE.md, section 6](../../../guides/RACHANA_REFERENCE.md#6-sources-over-several-packs)):

```yaml
panels:
  - id: py-sum
    kind: metric
    source: { kind: path-sum, id: "$.BOOK_ID", params: { delay-ms: ctl.slow } }   # each value an expression; 1 or true are literals
    value: "$src.SUM"
```

The server evaluates the parameters as the viewer (a parameter that follows a value masked for them shows *not available to your
role*) and reads the kind with the id `BOOK-RATES~delay-ms=100`. The same id works anywhere an id does: `/v/path-sum/BOOK-RATES~delay-ms=100`,
`PSUM BOOK-RATES~delay-ms=100 <GO>`. The panel is **never blank**:

| State | The panel shows |
|---|---|
| `ready` | the result; *How this view was built* and the stamp say how it was computed |
| `computing` | *Computing 58% · path-sum-python*, a progress bar moved by the live channel, the last result dimmed under it if there is one; filled in place when the result lands |
| `stale` | the last result, in amber: *Stale · path-sum-python is recomputing after write-back of STRIP-FX-001* |
| `failed` | *path-sum-python could not compute this: <why> (request req-…)*, the last result dimmed if there is one |
| `refused` | *Not computed: <why>* (a parameter refused, compute off, not approved, the viewer's roles) |

Other panels of the screen are not affected by one computed panel's state.

## What happens on a read

1. **Authorise**: the viewer's roles may open the kind, or the read is refused.
2. **Check the parameters** (types, bounds, allowed values, defaults filled).
3. **Pin**: the data version of every kind `reads:` names, for the business date (and the sandbox's version), make the pin.
4. **Key**: connector, kind, id and parameters, date, the viewer's **view signature**, the pin, the engine's identity and version.
5. **Cache and single flight**: a cached result is answered at once; a read of a key already being computed joins it (one computation,
   each reader answered under their own request id).
6. **Admit**: an interactive read over `limits.max-inflight-per-user` of the person's computations is refused as *busy*; the compute
   pool's own fair queue orders the rest by class and person ([NOTEBOOK_GUIDE.md](../../../guides/NOTEBOOK_GUIDE.md)).
7. **Read the inputs as the viewer** through the normal routing (kinds refused, field masks applied); more than `limits.max-input-rows`
   is refused before anything runs.
8. **Compute** in the engine; **progress** goes to the request's events.
9. **Validate, stamp, cache, answer, audit** (`compute.answer` with the request, parameters, pin, signature, time and result hash).

The read waits `limits.wait` for the result; past it, it answers `computing` with the request id, and the console listens to that request
on the browser's live channel (`compute:<request id>`) and fetches the panel again when it is `done`. An interactive computation is
stopped when every reader has gone (the page closed).

## Engines

**Python** (`engine.type: python`): an `Engine` of the worker SDK ([NOTEBOOK_GUIDE.md](../../../guides/NOTEBOOK_GUIDE.md)) in a
compute pool. Inputs arrive as Arrow tables (`inputs.table("values")`): each top-level field a column, a list of numbers of one length a
fixed-size vector (a P&L path). Its `Result(rows=…, tables=…)` comes back as `RESULTS` and `TABLES`. With `split`, the input is cut into
chunks spread over every worker of the pool and of the other Drishti*net nodes that serve it, and `reduce` adds the chunks' answers up;
the stamp's `ran` says how many chunks, workers and nodes. The number of workers is configuration only (`compute.pools.<p>.workers.max`,
per node).

**Java** (`engine.type: java`): a class implementing `com.ash.drishti.api.compute.ComputeEngine` (drishti-api), run in the server on its
own virtual thread with the request's budget:

```java
public final class VectorSumEngine implements ComputeEngine {
    public EngineManifest manifest() { return new EngineManifest("vector-sum", "1.0", List.of("sum-vectors"), Set.of("sum-vectors")); }
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        List<DataNode> rows = inputs.documents("values");          // read and masked as req.viewer()
        ...
        progress.fraction(done, rows.size());
        return new EngineResult(List.of(row), Map.of("total", table), Map.of());
    }
    public Optional<EngineResult> incremental(EngineRequest req, EngineResult previous, List<ComputeEvent> events, EngineInputs in) { ... }
    public Set<String> affected(ComputeEvent event, String expression) { ... }   // for affects: <expression>
}
```

Drishti's own engines (`com.ash.drishti.*`) always may run in the server; any other only with `compute.in-process-java: true` in
`config/compute/compute.yaml`. Throw `EngineException("words")` to refuse in words; any other exception is *the computation failed*
with its text in the log only. A Java worker process for heavy or untrusted Java is planned.

## Triggers

| `on:` | Fires when | How |
|---|---|---|
| `write-back` | a change is applied (`edit.applied`) | write-back calls `ReactiveHub.publish` after every applied change (the change id as the event id, the fields changed, the watermark `L<lane> -> seq`; `when: applied`, or `confirmed` / `refused` for a source target); also `POST /api/v1/compute/events` |
| `live` | a live connector delivers a message | each entity of the trigger's kinds that a watched computation read is followed; a new generation recomputes the results that read it |
| `data-version` | a kind's data version moves (a load is published) | the load signal (the connector depends on its `reads:` kinds) and a check every 30 s |
| `schedule` | `cron` (six fields, in `zone`) or `every` | a timer per trigger |

`affects:` is `all` (every result of the date), `subscribed` (the results somebody watches; the default), id templates over the event
(`"{payload.BOOK_ID}"`, `"{entity}"`), or an expression a Java engine evaluates (`affected(event, expression)`). Affected results are
marked stale at once; those **somebody watches** are recomputed, **coalesced** (after `coalesce.quiet` of no new event, never later than
`coalesce.max-wait` after the first; one computation per key at a time, exactly one more for what arrived meanwhile), once per view
signature, and pushed (`updated`); the others recompute on their next read. An event id seen again within `limits.dedupe-window` is
ignored. A result carries the watermark of the events it includes (`L17: 3412`); a late result with a lower watermark never replaces a
newer one. A kind with `incremental: true` is updated from the previous result and the events when the engine can (every
`incremental.verify-every`th time it is computed in full instead).

## Private sandboxes

A what-if belongs to one person: `POST /api/v1/compute/sandboxes {connector, title, items}` makes one (`sbx-…`); a parameter of type
`sandbox-ref` names it, and its items reach the engine as the input `sandbox`. Only its owner can read, change or compute it; another
person naming it is told *there is no sandbox sbx-…*. `POST /api/v1/compute/sandboxes/{id}/copy {to}` gives someone a frozen copy, never
a live link. A trigger never recomputes a sandbox. They are kept under `<data dir>/compute/sandboxes/`.

## The HTTP API

| Method and path | Does |
|---|---|
| `POST /api/v1/compute/{connector}/{kind}` | `{id, params, asOf, wait}`: the answer (`state`, `requestId`, `result`, `message`, `progress`) |
| `GET /api/v1/compute/requests/{id}/stream` | the request's events as server-sent events, to its owner only: `hello`, `progress`, `state`, `done`, `failed`, `updated`; a heartbeat every 15 s |
| `DELETE /api/v1/compute/requests/{id}` | cancels the caller's request (the computation stops if nobody else waits on it) |
| `POST /api/v1/compute/events` | `{id, type, kind, entity, businessDate, watermark, by, paths, payload}`: an event for every reactive connector's triggers (administrators, service tokens) |
| `GET`, `POST /api/v1/compute/sandboxes`; `PUT`, `DELETE /api/v1/compute/sandboxes/{id}`; `POST …/{id}/copy` | the caller's sandboxes |
| `GET /api/v1/admin/compute/reactive` | each reactive connector: kinds and parameters, engine, cache, coalescing, computations, latency |

Not found is `DRS-9206`; a refusal `DRS-9202`.

## Isolation

Two people's answers never mix: every read has its own request id and its events go only to it, opened by its owner; the key holds the
view signature, so different rights never share a cached answer; inputs are read and masked as the viewer before the engine sees them
(a recomputed result is read again by each watcher through their own view, masked for them); sandboxes are private; a Python worker
handles one request at a time in a fresh process (fork) or a reset one (warm). A live view rebuilds on behalf of its own viewer.

## Performance

Measured on a workstation: a cache hit costs 4.7 µs (p50; p99 9.6 µs) inside the connector; a miss with an engine that does nothing
47 µs (p99 183 µs); a miss through a Python worker (fork mode, a trivial engine) 2.3 ms. Through the server, one computed panel answered
from the cache takes 2.7 ms (p50), the same as a plain panel (2.6 ms); the compute-demo book screen with two computed sources and two
250-point charts 5.0 ms. A panel never waits more than `limits.wait` for a computation: the page is drawn and the result fills it. Tests:
`ReactiveOverheadTest` and `PythonBridgeTest` (drishti-compute).
