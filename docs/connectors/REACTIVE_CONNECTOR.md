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
# The reactive connector: data computed when it is asked for

Built in 2.8.0 (steps R1 to R4 of [REACTIVE_CONNECTORS.md](../architecture/REACTIVE_CONNECTORS.md)). A reactive connector is a
connector file of plugin `reactive` whose answer is a computation: a sum of P&L paths, a VaR, a what-if. It is read like any connector
(a pack routes kinds to it; a Sutra panel names them in `source:`), and it computes **when it is asked**, **as the person asking**, and
**again when a trigger fires**. The short reference is its [README](types/reactive/README.md); the design and what is still planned are
in the architecture document.

## Contents

1. [When to use one](#when-to-use-one)
2. [The connector file](#the-connector-file)
3. [Reading a computed kind](#reading-a-computed-kind)
4. [What happens on a read](#what-happens-on-a-read)
5. [Engines](#engines)
6. [Triggers](#triggers)
7. [Private sandboxes](#private-sandboxes)
8. [The HTTP API](#the-http-api)
9. [Isolation](#isolation)
10. [Performance](#performance)
11. [Troubleshooting](#troubleshooting)

## When to use one

| You need | Use |
|---|---|
| stored records (a lake, a database) | a static connector (`delta`, `jdbc`, ...) |
| a value fetched from a service per id, light, no parameters | an on-demand connector ([developer guide](CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand)) |
| a computation with typed parameters, over inputs read from other kinds, in Python or Java, possibly heavy, possibly fanned out over many workers, recomputed on events | **a reactive connector** |

## The connector file

```yaml
# config/connectors/path-sum-python.yaml
plugin: reactive
mode: reactive
description: The sum of a book's P&L paths
kinds:
  path-sum:                                   # the kind the pack routes here
    compute: sum-vectors                      # what the engine calls it (the kind's own name when absent)
    params:
      delay-ms: { type: int, default: 0, min: 0, max: 500 }
    result: { required: [SUM, WORST], types: { SUM: number, WORST: number } }
reads:                                        # read as the viewer, in this order
  - { kind: path-book, as: book, id: "{id}" }
  - { kind: path-strip, as: values, ids: "{inputs.book.MEMBERS}", limit: 5000 }
engine:
  type: python
  entry: drishti_compute.samples.paths:PathSumEngine
  pool: sample                                # a pool of config/compute/compute.yaml
  version: "1.0"
  split: values                               # cut over every worker of the pool, on this node and its Drishti*net peers
  chunk-rows: 20
  reduce: { sum: [ROWS, SUM], tables: { total: [PNL] }, then: { WORST: "min:total.PNL", BEST: "max:total.PNL" } }
triggers:
  - { on: data-version, kinds: [path-strip], affects: all }
  - { on: write-back, kinds: [path-strip], affects: "{payload.BOOK_ID}" }
coalesce: { quiet: 250ms, max-wait: 2s }
cache: { results: 1000, ttl: 1h }
limits: { timeout: 30s, wait: 25ms, max-inflight-per-user: 4 }
# requires: { python: { version: "3.12", lock: requires/path-sum.lock } }   # nothing runs until an administrator approves
```

- **Parameters** are typed (`int`, `decimal`, `text`, `scope`, `bool`, `enum` with `options`, `date`, `sandbox-ref`) with `default`,
  `allowed`, `required`, `min` and `max`. They are checked when the file loads and on every read: a value outside them is refused in
  words (*delay-ms for path-sum is 900: it must be at least 0 and at most 500*), and an unknown name too (*path-sum has no parameter
  'n'*).
- **Templates** in `reads:` and `affects:` use single braces: `{id}`, `{params.book}`, `{inputs.book.MEMBERS}`, `{payload.BOOK_ID}`,
  `{entity}`. `${NAME}` stays what it is everywhere in a connector file: an environment variable, resolved when the file is read. A
  template that is exactly one variable holding a list (or comma-separated text) gives one id per element.
- **The result** is checked before it is cached or shown: `required` fields of a one-row result, their `types`, `max-rows`. A result
  that breaks them is *the computation returned an invalid result*, an error of the connector, never data.
- **In a pack** the same keys go under `connectors: <name>:` and are written to the file at first start; `routes:` sends the kinds
  (`path-sum: path-sum-python`).

## Reading a computed kind

A Sutra panel names the kind and its parameters ([SUTRA_NEXT.md section 6](../architecture/SUTRA_NEXT.md#6-sources-over-several-packs)):

```yaml
panels:
  - id: py-sum
    kind: metric
    source: { kind: path-sum, id: "$.BOOK_ID", params: { delay-ms: ctl.slow } }   # each value an expression; 1 or true are literals
    value: "$src.SUM"
```

The server evaluates the parameters as the viewer (a parameter that follows a value masked for them shows *not available to your
role*) and reads the kind with the id `BOOK-RATES~delay-ms=100`. The same id works anywhere an id does: `/v/path-sum/BOOK-RATES~delay-ms=100`,
`PSUM BOOK-RATES~delay-ms=100 <GO>`.

The panel is **never blank**:

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
4. **Key**: connector, kind, id and parameters, date, the viewer's **view signature** (a hash of their roles and embedding app: what
   decides what they see), the pin, the engine's identity and version.
5. **Cache and single flight**: a cached result is answered at once; a read of a key already being computed joins it (one computation,
   each reader answered under their own request id).
6. **Admit**: an interactive read over `limits.max-inflight-per-user` of the person's computations is refused as *busy*; the compute
   pool's own fair queue orders the rest by class and person (PYTHON_COMPUTE.md 7.7).
7. **Read the inputs as the viewer** through the normal routing (kinds refused, field masks applied); more than `limits.max-input-rows`
   is refused before anything runs.
8. **Compute** in the engine; **progress** goes to the request's events.
9. **Validate, stamp, cache, answer, audit** (`compute.answer` with the request, parameters, pin, signature, time and result hash).

The read waits `limits.wait` for the result; past it, it answers `computing` with the request id, and the console listens to that
request on the browser's live channel (`compute:<request id>`) and fetches the panel again when it is `done`. A computation is stopped
when every reader has gone (the page closed) and it is interactive.

## Engines

**Python** (`engine.type: python`): an `Engine` of the worker SDK (PYTHON_COMPUTE.md section 8) in a compute pool. Inputs arrive as
Arrow tables (`inputs.table("values")`): each top-level field a column, a list of numbers of one length a fixed-size vector (a P&L
path). Its `Result(rows=…, tables=…)` comes back as `RESULTS` and `TABLES`. With `split`, the input is cut into chunks spread over every
worker of the pool and of the other Drishti*net nodes that serve it, and `reduce` adds the chunks' answers up; the stamp's `ran` says
how many chunks, workers and nodes. The number of workers is configuration only (`compute.pools.<p>.workers.max`, per node).

**Java** (`engine.type: java`): a class implementing `com.ash.drishti.api.compute.ComputeEngine` (drishti-api) run in the server on
its own virtual thread with the request's budget:

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
with its text in the log only. Heavy or untrusted Java belongs in a Java worker (planned, C6).

## Triggers

| `on:` | Fires when | Built how |
|---|---|---|
| `write-back` | a change is applied (`edit.applied`) | `POST /api/v1/compute/events` (administrators, service tokens) and `ReactiveHub.publish` in Java, which the write-back module calls when it lands (W6) |
| `live` | a live connector delivers a message | each entity of the trigger's kinds that a watched computation read is followed (router subscribe); a new generation recomputes the results that read it |
| `data-version` | a kind's data version moves (a load is published) | the load signal (the connector depends on its `reads:` kinds) and a check every 30 s |
| `schedule` | `cron` (six fields, in `zone`) or `every` | a timer per trigger |

`affects:` is `all` (every result of the date), `subscribed` (the results somebody watches; the default), id templates over the event
(`"{payload.BOOK_ID}"`, `"{entity}"`), or an expression a Java engine evaluates (`affected(event, expression)`). Affected results are
marked stale at once; those **somebody watches** (an open panel listening to its request) are recomputed, **coalesced** (after
`coalesce.quiet` of no new event, never later than `coalesce.max-wait` after the first; one computation per key at a time, exactly one
more for what arrived meanwhile), once per view signature, and pushed (`updated`); the others recompute on their next read. An event
id seen again within `limits.dedupe-window` is ignored. A result carries the watermark of the events it includes (`L17: 3412`); a late
result with a lower watermark never replaces a newer one. A kind with `incremental: true` is updated from the previous result and the
events when the engine can (every `incremental.verify-every`th time it is computed in full instead).

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
| `POST /api/v1/compute/events` | `{id, type, kind, entity, businessDate, watermark, by, paths, payload}`: an event for every reactive connector's triggers (administrators) |
| `GET`, `POST /api/v1/compute/sandboxes`; `PUT`, `DELETE /api/v1/compute/sandboxes/{id}`; `POST …/{id}/copy` | the caller's sandboxes |
| `GET /api/v1/admin/compute/reactive` | each reactive connector: kinds and parameters, engine, cache, coalescing, computations, latency |

Not found is `DRS-9206`; a refusal `DRS-9202`.

## Isolation

Two people's answers never mix: every read has its own request id and its events go only to it, opened by its owner; the key holds the
view signature, so different rights never share a cached answer; inputs are read and masked as the viewer before the engine sees them
(a recomputed result is read again by each watcher through their own view, masked for them); sandboxes are private; a Python worker
handles one request at a time in a fresh process (fork) or a reset one (warm). A live view rebuilds on behalf of its own viewer.

## Performance

Measured on a workstation (2026-10-25): a cache hit costs 4.7 µs (p50; p99 9.6 µs) inside the connector; a miss with an engine that does
nothing 47 µs (p99 183 µs); a miss through a Python worker (fork mode, a trivial engine) 2.3 ms. Through the server, one computed panel
answered from the cache takes 2.7 ms (p50), the same as a plain panel (2.6 ms); the compute-demo book screen with two computed sources and
two 250-point charts 5.0 ms. A panel never waits more than `limits.wait` for a computation: the page is drawn and the result fills it.
Tests: `ReactiveOverheadTest` and `PythonBridgeTest` (drishti-compute).

## Troubleshooting

See the [README](types/reactive/README.md#troubleshooting). In the server's log, each failed computation is logged with its request id;
`compute.answer` and `compute.failed` audit entries name the connector, kind and id.
