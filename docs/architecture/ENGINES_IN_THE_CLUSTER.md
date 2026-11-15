# Compute engines: local or remote, one way to reach them

Design for release i5 (decided by the product owner on 2026-11-15). It explains how a compute engine such as VaR is built and
becomes part of a pack, how several engines run side by side, and how the cluster makes an engine local or remote without its
callers noticing. FRTB and SIMM as full engines on this pattern are release i6; i5 builds and proves the mechanism with the
engines that exist today.

Related documents: [KERNEL_AND_MODULES.md](KERNEL_AND_MODULES.md) (modules, packs, placement),
[../guides/CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md) (Drishti\*net), [LIVE_CONNECTORS_DESIGN.md](LIVE_CONNECTORS_DESIGN.md)
(live data; its last step uses the same engine lookup), [../guides/VAR_GUIDE.md](../guides/VAR_GUIDE.md). **The detailed design of
the cluster step** (the libraries, the lookup, placement per engine, the digest, promotion, security, tests, the build plan) is
[CLUSTER_I5_DESIGN.md](CLUSTER_I5_DESIGN.md).

## 1. The anatomy of one engine (VaR as the example)

| Layer | Where (VaR) | What it holds |
|---|---|---|
| 1. Mathematics | `modules/banking/banking-compute/.../compute/var/` | Pure Java, no framework, no I/O: inputs in, numbers out. Golden tests prove exact results. |
| 2. Engine service | `modules/banking/banking-service/.../banking/var/` | The server side: loading inputs into the hot store, the API (`/api/v1/risk/var`), the Explain answer, official end-of-day capture, the playground. |
| 3. Engine manifest | `META-INF/drishti/engines.yaml` (entry `id: var`) | What the engine owns and needs: its switch, `needs` / `uses`, API paths, command words, menus, help, tiles, BI tables, connectors, the kinds it produces and consumes. |
| 4. Engine settings | `config/engines/var.yaml` | Its parameters (confidence, ES confidence, 10-day method, stressed VaR, `scenario-window` default 250) and its on/off switch. One file per engine. |
| 5. Pack | `modules/banking/dmod/packs/market-risk/` | The data face: input contracts (`scenario-pv`, `pnl-vector`, `fx-scenario` …), the computed output kind (`var-result`, contract class `computed`), command words, and a *reactive* connector (`var-engine`, `plugin: reactive`) that computes the kind when it is read. |
| 6. Module | `banking` (`.dmod`) | Ships layers 1–5 together; switched on or off as a whole. |

The reactive connector is what makes a computed result look like data: `VRES DESK:RATES`, a pivot over `var-result` or a record
page reads the kind through the same read path as any stored kind, and the engine computes it when asked.

Switching an engine off removes everything its manifest owns at once (API, commands, menus, help, tiles, BI tables, connectors):
*off means gone everywhere*. An engine that `needs` another refuses to start without it, in words (`DRS-1022`); one that `uses`
another adds more when that one is present.

### Engines running side by side today

| Engine | needs | uses |
|---|---|---|
| var | — | — |
| exposure | — | — |
| sensitivities | — | — |
| frtb-sa | var, sensitivities | — |
| simm | sensitivities | exposure |
| mr-cube | var, pivot | sensitivities, frtb-sa |

### Adding a compute model (the recipe; FRTB and SIMM follow it in i6)

1. Mathematics in `<module>-compute/<engine>/`, with golden tests.
2. Engine service beans: loading, API, explainer, playground, official capture where it applies.
3. A manifest entry: id, switch, `needs` / `uses`, and everything the engine owns.
4. A settings file `config/engines/<engine>.yaml`.
5. In the pack: input contracts, the computed output kind, a reactive connector, command words.
6. Ship it in a module; it is switched on and off in Admin → Engines, on its own.

## 2. What the cluster knows today (Drishti\*net)

Each node gossips, over HTTP, its members and states and a capability digest: the packs and kinds it serves, the data it holds
(lake, dates), the compute engines running on it (`services`) and the VaR shards it owns. Requests use it:

| Situation | What happens |
|---|---|
| The kind's pack is not on this node | View forwarding: the whole request goes to a node that has it |
| The pack is here, the data is not | Data forwarding: the document is read from the node that holds it |
| A search or type-ahead | Scatter-gather: asked of every relevant node, the answers merged |
| An engine's API reaches a node that does not run it | Forwarded to the least loaded node that does; none: `404 DRS-1020` |

The site's `placement.yaml` places each module **part** on nodes (for banking: `api`, `compute`, `exposure`, `feeds`, `jobs`,
`console`); a request that reaches a node without the part is forwarded.

## 3. The i5 principle: an engine is local or remote, and its callers cannot tell

Every caller — a pack's reactive connector, a pivot, a command (`VAR BANK`), Explain, or another engine's `needs` — resolves an
engine **by name through one lookup**. Placement decides where the engine runs. On this node the call is local; on another node it
is forwarded and the answer comes back in the same shape. Moving VaR from node A to node B is a placement change, never a code
change. The goal: VaR on one node and FRTB on another, both used transparently by modules and packs, with data and results reused.

### What i5 adds

1. **Placement per engine**, not only per module part. Today banking's `compute` part holds VaR, FRTB-SA and SIMM together, so they
   always land on one node; i5 lets a site write, for example, `var: {nodes: [node-a]}` and `frtb-sa: {nodes: [node-b]}`.
2. **`needs` satisfied anywhere in the cluster.** Today an engine's `needs` is checked on the same server ("FRTB-SA needs var on this
   server, which is off", `DRS-1022`). In i5 a need is met by the engine on any node, reached through the same lookup.
3. **Modules and their parts in the gossip digest**, beside packs, kinds, data and engines, so every node knows which module part runs
   where.
4. **Cluster on standard libraries**: membership, failure detection and election move from hand-written gossip onto a proven standard
   library.
5. **Remote as dependable as local**:
   - health shows a remote engine's state with its node name, and dependents refuse cleanly instead of waiting without end;
   - timeouts and busy signals pass through unchanged (as the Explain "busy, retrying" state does today);
   - an engine placed on two nodes fails over to the other;
   - provenance and Explain name the node that computed a number.
6. **Reuse instead of recomputation**:
   - every node reads the same named lakes (from the settings ini, absolute paths), so inputs are loaded once and shared;
   - published results (official VaR, sensitivities) are read rather than recomputed;
   - hot caches stay with each engine, on its node.
7. **Scalability**: VaR already shards dates across nodes (`VarShards`); the same pattern extends to other heavy engines, and more
   nodes are added by placement alone.

### How i5 proves it

- VaR and a second existing engine (exposure or sensitivities) placed on different nodes, used from a third node by commands, pivots,
  Explain and a dependent engine, with VaR golden numbers exactly equal to a single-node run.
- A remote engine stopped and restarted: clean refusals while it is away, recovery without restarting its callers.
- The live connectors' last step (L10, see [LIVE_CONNECTORS_DESIGN.md](LIVE_CONNECTORS_DESIGN.md)) reaches engines through the same
  lookup.

The cluster step is designed in detail before it is coded: [CLUSTER_I5_DESIGN.md](CLUSTER_I5_DESIGN.md), with the proof scenario in
its section 12.4.

## 4. Questions to settle in the detailed design

Each is answered in [CLUSTER_I5_DESIGN.md](CLUSTER_I5_DESIGN.md): isolation in its section 7.4, the honest manifest by the contract
check of 5.1, the end-of-day order in 7.3, and engines in their own modules by the manifest additions of 5.1 and placement per
engine in 6.

- Resource isolation between engines on one node: separate memory budgets and thread pools, so one heavy run cannot starve another.
- A check that keeps each engine's manifest honest (the words, APIs and menus it claims exist, and nothing it owns is unlisted).
- End-of-day order: official captures of several engines run in `needs` order, wherever each engine is placed.
- Engines shipped in their own modules (for a client-specific engine), using the same manifest.
