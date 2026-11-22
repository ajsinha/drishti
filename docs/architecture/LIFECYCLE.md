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
# Lifecycle: modules, packs and connectors

One lifecycle for every runtime part of Drishti. Modules, packs and connectors implement the kernel's one contract
(`com.ash.drishti.api.lifecycle.Lifecycle`). Engines follow the engine specification
([drishti-engine `spec/1/SPEC.md` section 7.1](https://github.com/drishti-platform/drishti-engine/blob/develop/spec/1/SPEC.md)),
which uses the same states and the same five transitions. This page says what each state means for each part, how the parts
compose, and how an administrator drives them.

## Contents

1. [The contract](#1-the-contract)
2. [States and transitions](#2-states-and-transitions)
3. [Packs](#3-packs)
4. [Connectors](#4-connectors)
5. [Modules and the dependency graph](#5-modules-and-the-dependency-graph)
6. [Engines](#6-engines)
7. [Health and stats](#7-health-and-stats)
8. [Admin and REST](#8-admin-and-rest)
9. [Audit, events and the cluster](#9-audit-events-and-the-cluster)
10. [Limits](#10-limits)

## 1. The contract

| Method | Means |
|---|---|
| `init` | Read and validate: files, settings, contracts, secrets resolved. No I/O to the outside, nothing registered, nothing served. |
| `start` | Register what the part offers and begin serving: kinds, mnemonics, Sutras, routes; pools and consumers opened. |
| `suspend` | Stop taking new requests. They answer `503 DRS-1037` in words. Requests in flight finish. Pulling stops. State, offsets, caches and pools are kept, idle. |
| `resume` | Serve again from the kept state. Cheap: nothing is reloaded or re-read, so answers are the ones given before the suspend. |
| `shutdown` | Unregister everything and release every resource. Idempotent. The instance is done: a later start is a new instance. |
| `health()` | `UP`, `DEGRADED`, `SUSPENDED` or `DOWN`, the reasons in words, and when it was checked. Cheap: never blocks, never does I/O. |
| `stats()` | Counters since start and over the last five minutes, latency p50/p95/p99, queue depth, in flight, last activity. A snapshot. |

One class implements the state table for every part: `LifecycleMachine`. It runs one transition at a time per part, keeps the request gate
and the drain, counts the stats (`StatsRecorder`), and raises one `LifecycleEvent` per transition, refused and failed ones included.

## 2. States and transitions

| State | Entered by | Allowed next |
|---|---|---|
| `NEW` | construction | init; shutdown |
| `INITIALISED` | init returned | start; shutdown |
| `RUNNING` | start or resume returned | suspend; shutdown |
| `SUSPENDED` | suspend returned | resume; shutdown |
| `FAILED` | init or start threw | shutdown |
| `SHUTDOWN` | shutdown returned | none; a further shutdown is harmless |

- A transition that the state does not allow is refused with `409 DRS-1036` and changes nothing. The words name the part, its state and
  what is allowed.
- A suspend closes the gate first, so new requests are refused from that moment. It then waits up to the drain (30 s, or
  `drishti.sources.connectors-drain` for a connector) for requests in flight, and only then runs the part's own step (stop pulling).
  A failing suspend reopens the gate. A failing resume leaves the part suspended. A shutdown always ends `SHUTDOWN`; a failure while
  releasing is recorded in its event.
- The state names are the engine specification's. Its section 7.1 is being rewritten to these five transitions. If the rewrite spells a
  state differently, `LifecycleState` is the one place to rename it.

## 3. Packs

| State | For a pack |
|---|---|
| `INITIALISED` | `pack.yaml`, its about, formats and semantics files read and checked together with the running packs (what a load checks). Nothing registered. |
| `RUNNING` | Its kinds, mnemonics, Sutras, routes, roles, connector templates and settings registered through the pack reloader, the same path a pack folder change takes. |
| `SUSPENDED` | Its kinds and mnemonics answer `503 DRS-1037` ("pack market-risk is suspended (by ops at ...)"). The connectors that only it uses stop pulling, so its live tables pause. Everything registered and every cache is kept. |
| `SHUTDOWN` | Unregistered: no kind, mnemonic, Sutra, route or template of it remains. The connectors only it used stop. An open view of its kinds says the pack was removed. |

- **The gate.** Every request for a kind passes `Entitlements.requireActive(kind)`: views, records, mnemonics, forms, calc, alerts, exports.
  That method asks the pack lifecycle (`PackLifecycles.requireServing`). There is no second gate.
- **Boot.** The packs the server loads at start get a `RUNNING` machine. So does a pack that the folder watcher, a deploy or Admin → Packs
  Load brings in. A pack unloaded by those paths is shut down.
- **Shutdown of a pack the site lists.** A pack named in `drishti.packs.enabled` is left out of every reload on this node until it is
  started again. The site's list wins at the next server start.
- **Resume serves the same answers.** Nothing is reloaded and the connectors' instances are the same objects. The packaged test proves this
  with VaR: VaR 167,372,692 and ES 210,131,972, the whole scenario P&L vector compared bit for bit before the suspend and after the resume.

## 4. Connectors

| State | For a connector |
|---|---|
| `INITIALISED` | A fresh instance of its plugin, its own settings merged (`[connector.<name>]`, named brokers and databases, lake files) and its secrets resolved. Not started: no I/O. |
| `RUNNING` | The plugin started (pools, consumers), registered by name and source name, routed. |
| `SUSPENDED` | A stand-in takes its place in routing at once. Every read, search, scan, new subscription and write answers `503 DRS-1037`. What describes it (manifest, health, freshness, caches, pool) still reaches the plugin. Reads in flight finish on the plugin. Then `SourcePlugin.suspend()` stops pulling. The instance, offsets, caches and pools are kept. |
| `SHUTDOWN` | Out of routing, drained for `connectors-drain`, closed. Its settings are kept, so a start brings it back as configured. |

- `SourcePlugin` gains `suspend()`, `resume()` and `backlog()`. All three are no-ops by default, because a source that only answers reads has
  nothing to stop. The Kafka plugin pauses its partitions on the consumer's own thread and resumes from the kept positions, so no message is
  lost or read twice (`KafkaSuspendResumeTest`). Its backlog is the client's `records-lag`.
- A changed connector file restarts the connector as a new instance (shutdown, then init and start), as before. A suspended connector whose
  file changes comes back running.

## 5. Modules and the dependency graph

### 5.1 One graph, from what is already declared

`DependencyGraph` is built from declarations that already exist. There is no new field.

| Edge | From | Declared by |
|---|---|---|
| `EXTENDS` | pack → parent pack | `extends:` (the older `requires:` list) |
| `USES` | pack → connector | `connectors:` and `routes:`; a template it declares `enabled: false` is optional |
| `USES` | pack → engine | `uses.engines` (engine specification section 10.1), `optional:` as declared |
| `CONTAINS` | module → pack, connector | the module's packs (resolution origin `module:<id>`), and the connectors only its packs name |

Nodes are `module:<id>`, `pack:<name>`, `connector:<name>` and `engine:<id>`.

### 5.2 What the kernel takes from it

- **Start order:** dependencies first. A module starts its connectors, then its packs.
- **Refused at start**, in one sentence, before anything runs:
  - a cycle, named: "the parts depend on each other in a cycle: pack:a -> pack:b -> pack:a";
  - a required dependency that does not exist: "pack:x needs connector:y, which does not exist here; add it, or mark the dependency
    optional".
- **Suspend cascades** to the packs that extend the part, children first, because they serve its kinds. A module's suspend takes its packs,
  then its connectors.
- **Suspend degrades** the parts that only `USES` the suspended part. They are not suspended. They stay `RUNNING`, serve everything that
  does not read it, and their health is `DEGRADED` naming it. What reads it answers `503 DRS-1037` naming the connector.
- **Shutdown** is refused while a running part depends on it, naming them, unless the cascade is asked for (`?cascade=true`). Then the
  dependants shut down first.
- **Health roll-up:** a required dependency that is `DOWN` or `SUSPENDED` makes the dependant `DEGRADED`, never `DOWN`, with a reason
  naming it ("needs connector:scenario-lake, which is suspended (...)"). Optional dependencies are not counted.

### 5.3 Modules

A module's transition is its parts' transitions in graph order (`ModuleLifecycles`). Shutdown is packs, then connectors, then the runtime's
hot unload of its code: routes off, context closed, loader dropped and leak-checked. A start after a shutdown loads the module again from
the candidate verified at boot. A part can be suspended alone; the module stays `RUNNING` and its health names the part.

## 6. Engines

Engines keep their own lifecycle in the engine specification, and their own paths. Composition:

- **Start.** A pack whose `uses.engines` names an engine that is not running still starts. This is the engine specification's section 10.3
  rule 4: no absence of an engine is a start failure. Its health is `DEGRADED`, naming the engine. The parts derived from the engine answer
  as the specification's section 10.3 says.
- **An engine suspends or stops.** The dependent packs are **degraded, not refused and not suspended**. Only what reads the engine is
  unavailable; the rest of the pack serves.
- **An engine shuts down.** The same as an engine suspend. The engine host's own dependency check, `DRS-1021`, still guards engines that
  need engines.

## 7. Health and stats

There is one health system. `/actuator/health` gains the `lifecycle` entry beside `engines`: every part that is not `UP`, with its status
and reasons. The entry itself is always `UP`, because a suspended or degraded part is an administrator's choice or a dependency's state,
not the server's fault. Admin → Health shows it with the rest.

How each part works out its health:

- **Connector:** the plugin's own health line, read as `SourceHealth` reads it for the health page.
- **Pack:** its state, its problems on disk, then the roll-up of its dependencies.
- **Module:** its state and its parts.

How each part works out its stats:

- **Connector:** the router's read counters (`SourceStats`, the one place reads are counted, now with a five-minute window and p95),
  refusals at its stand-in, the plugin's cache counters, its backlog, and its last pull.
- **Pack:** requests and refusals at its gate.
- **Module:** the sum of its parts.

`ComponentStats` fields: `since`, `total` and `window` (requests, errors, refusals, rows read, rows written, bytes, cache hits, cache
misses), `windowLength`, `latency` (samples, p50, p95, p99, max in ms), `queueDepth`, `inFlight`, `lastActivity`.

Prometheus (`/actuator/prometheus`) has one series per part, tagged `type` and `name`:

- `drishti_lifecycle_state`: 0 new, 1 initialized, 2 running, 3 suspended, 4 failed, 5 shut down;
- `drishti_lifecycle_health`: 0 up, 1 degraded, 2 suspended, 3 down;
- the counters `drishti_lifecycle_requests`, `_errors`, `_refusals`, `_rows_read`, `_rows_written`, `_bytes`, `_cache_hits`;
- the gauges `drishti_lifecycle_latency_p50|p95|p99`, `_queue_depth`, `_in_flight`.

## 8. Admin and REST

**Admin → Packs** shows `suspended` with who suspended it, and offers **Suspend** or **Resume**. **Admin → Connectors** shows
`suspended (state kept)` and offers the same. Module transitions are REST only. All of them are for administrators only.

The routes take the shape the i8 drop-in lane proposed, and we adopted it unchanged.

| Route | Does |
|---|---|
| `GET /api/v1/admin/lifecycle` | every module, pack and connector here: state, since, by, health, stats; plus each peer's parts that are not running |
| `GET /api/v1/admin/lifecycle/graph` | nodes, edges (edge type, optional), start order, cycle, missing dependencies |
| `GET /api/v1/admin/{modules,packs,connectors}/{name}/lifecycle` | one part's row |
| `POST /api/v1/admin/{modules,packs,connectors}/{name}/lifecycle/{init,start,suspend,resume,shutdown}[?cascade=true]` | one transition. The answer is the row, plus `along`: the packs a cascade took with it. |
| `GET /api/v1/admin/{modules,packs,connectors}/{name}/health` | `ComponentHealth` |
| `GET /api/v1/admin/{modules,packs,connectors}/{name}/stats` | `ComponentStats` |

Engines stay on the engine specification's paths: `/api/v1/engines/{id}/health`, `/stats` and `/output`. They also have
`/api/v1/admin/engines/{id}/...`.

Personal API tokens:

- A pack transition needs `packs:admin`.
- A connector transition is covered by `packs:admin`'s `POST /api/v1/admin/connectors/**`.
- A module transition is never allowed to a token.

## 9. Audit, events and the cluster

**Audit.** These transitions are recorded in the audit log:

- every transition a person asks for, as `pack-suspend`, `connector-resume`, `module-shutdown` and so on;
- every refusal (`…-refused`) and every failure (`…-failed`).

Each record carries `<type>/<name>` and `from -> to: words`. The server's own successful transitions are not recorded: a boot, or a
connector restarted by its file. Those changes have their own audit entries already.

**Events.** Every `LifecycleEvent` is published as a kernel application event.

**Cluster.** The state is per node. Each node's capability digest carries the service `lifecycle`, listing only the parts that are not
running (`pack:market-risk:suspended,connector:trades-kafka:suspended`), and a transition announces the digest at once. Peers see it within
a gossip round, in `GET /api/v1/admin/lifecycle` under `peers`. A request forwarded to the owner node of a suspended pack gets that node's
own `503 DRS-1037`, which names the pack. The forwarding node passes it on.

## 10. Limits

- Suspend and shutdown are in memory, per node. A restart starts what the site's configuration names.
- A live table pauses because its connectors stop pulling. It is not paused separately.
- Only the Kafka plugin implements `suspend`/`resume` so far. Other live plugins are gated by the stand-in, but they keep pulling until they
  implement them. The banking module's `VarPvStreamPlugin` is next.
- A module's API routes, such as `/api/v1/risk/var/*`, belong to the module, not to a pack. A pack's suspend does not close them; a module
  suspend or shutdown does.
