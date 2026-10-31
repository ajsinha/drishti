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
licence: Part of Drishti (drishti-compute); no client library. Python engines run in the compute workers (PYTHON_COMPUTE.md).
windows: Works the same on Windows; Python pools run in warm mode there (no fork). Java engines run in the server.
doc: docs/connectors/REACTIVE_CONNECTOR.md
---
# The reactive connector

Built-in plugin `reactive` (`drishti-compute/src/main/java/com/ash/drishti/compute/reactive/`, `ReactiveSourcePlugin` and
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
| `requires` | none | The libraries it needs; nothing runs until an administrator approves (PYTHON_COMPUTE.md) |
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

The compute-demo sample pack's Java connector (`config/packs/compute-demo/pack.yaml`, as written to `config/connectors/path-sum-java.yaml`):

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
  *Not computed: compute is off on this server*. Switch compute on (docs/admin/OPERATIONS.md).
- `DEGRADED: <name>: engine <class> would run inside the server; this server allows only Drishti's own Java engines there`: set
  `compute.in-process-java: true` in `config/compute/compute.yaml`, or run it as a Python engine.
- A panel says *Not computed: x for k is 11: it must be at least 0 and at most 10*: the parameter is refused before any work.
- A panel stays *computing*: the browser's live channel is closed (the Live pill); the panel fills on its next refresh. Admin →
  Compute → Reactive (`GET /api/v1/admin/compute/reactive`) shows each connector's running computations, cache and coalescing.
- `NOT_APPROVED`: its `requires:` waits for an administrator (Admin → Compute).

## See also

- [The reactive connector](../../REACTIVE_CONNECTOR.md): the file, the request lifecycle, triggers, sandboxes and the HTTP API, in full.
- [Reactive connectors, the design](../../../architecture/DATA_ARCHITECTURE.md#4-reactive-connectors) and [compute workers](../../../architecture/PYTHON_COMPUTE.md).
- [Operating compute](../../../admin/OPERATIONS.md#19-compute-workers) and [tutorial 23: computed panels](../../../tutorials/23-computed-panels.md).
