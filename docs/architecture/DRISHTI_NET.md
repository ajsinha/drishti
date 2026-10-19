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
# Drishti*net: many servers, one Drishti

Status: **design, not built.** Proposed for the **3.0** track (after wave E, alongside "scale on real servers"; see
[BI_DESIGN.md §12.2](BI_DESIGN.md#122-the-three-waves)). Off by default when built. Names marked **(new)** do not exist
in the code today; every other class, file or setting named here does.

## Contents

1. [The ask](#1-the-ask) · 2. [The idea in one picture](#2-the-idea-in-one-picture) · 3. [Principles](#3-principles-and-non-goals) ·
4. [What the code already gives us](#4-what-the-code-already-gives-us) · 5. [Membership: gossip over HTTP](#5-membership-gossip-over-http) ·
6. [What a node offers](#6-what-a-node-offers-the-capability-digest) · 7. [Routing a request](#7-routing-a-request) ·
8. [Operations across nodes](#8-operations-across-nodes) · 9. [Identity and security](#9-identity-and-security) ·
10. [Shared and separate lakes](#10-shared-and-separate-lakes) · 11. [State that belongs to a user](#11-state-that-belongs-to-a-user) ·
12. [Failure](#12-failure) · 13. [Admin and observability](#13-admin-and-observability) · 14. [Configuration](#14-configuration) ·
15. [APIs](#15-apis) · 16. [Build plan](#16-build-plan) · 17. [Tests](#17-tests) · 18. [Risks and open questions](#18-risks-and-open-questions)

## 1. The ask

The product owner, 2026-10-18:

1. Several Drishti servers run at once; they may host different packs, or overlapping ones.
2. They may share a Delta lake, or not.
3. Each server is optionally given the `host:port` of some peers.
4. The servers gossip over HTTP with a standard gossip protocol, so all of them soon know each other; joins and departures
   spread the same way. Each saves the peers it knows about every 10 minutes and uses that list to rejoin after a restart.
5. Each server tells its peers the packs and mnemonics it serves. A request is served locally when it can be; otherwise by a
   peer that can, whose answer the server passes back.
6. A UI connects to any member and never knows where the data came from.

## 2. The idea in one picture

```text
      console / Elements / API client  ──►  any node (say B)
                                              │
               ┌──────────── B's router ───────┴──────────────────────────────┐
               │ 1. local sources (as today)                                   │
               │ 2. kind's pack not here ──► forward the whole request ──► A   │  view forwarding
               │ 3. pack here, data not  ──► PeerSource fetch ─────────► C     │  data forwarding
               │ 4. search / type-ahead  ──► scatter to A, C; merge            │  scatter-gather
               └────────────────────────────────────────────────────────────────┘
   A ◄──gossip──► B ◄──gossip──► C        every node: members, states, capability digests;
   (packs: trading)  (core, risk)  (trading, market)   saved to <data dir>/net/peers.json
```

## 3. Principles and non-goals

**Principles**

- **The router is the seam.** A peer is one more source (and, for a pack this node lacks, one more view server). Nothing
  above the router (Rachana, search, BI, the console) learns about the cluster.
- **Local first, unless a peer has a newer pack.** A node never asks a peer for what it can answer itself with the newest
  version of the kind's pack in the cluster; a node with no peers behaves exactly as today.
- **The answering node applies its own rules, and the asking node applies its own again.** Masks, kinds per role and pack
  visibility are enforced where the data is read *and* where it is shown: the stricter wins.
- **One hop.** A forwarded request is never forwarded again (no loops, bounded latency).
- **Partial, never wrong.** A peer that fails or is slow makes an answer *partial* and named (the existing `failed` list
  of a search), never silently smaller.
- **Small gossip.** Gossip carries membership and digest *hashes*; the full digest is pulled only when its hash changes.

**Non-goals (first release)**

- No consensus, no leader, no replicated writes of data: lakes are written by the lake tools as today.
- No cross-node transactions; user state stays on a home node (section 11) until a later phase.
- Not a load balancer for one node's own traffic (put one in front if wanted; any node answers).

## 4. What the code already gives us

| Need | Already there |
|---|---|
| A place to plug a peer in | `SourceRouter.candidates(kind)` orders every registered `SourcePlugin` whose manifest serves the kind; `ConnectorInstance` wraps a plugin per connector |
| Knowing whether a source holds a date | `SourcePlugin.coverage(kind, asOf)` (`HELD` / `NOT_HELD` / `UNKNOWN`), `latestDate(kind)`, `dataVersion(kind, date)` |
| Calling another HTTP service well | the REST connector (`RestSourcePlugin`): timeouts, the shared `tls.*` settings incl. mutual TLS, the site proxy |
| Retry and back-off per failing source | `RetryBoard` (`connector '…' failed (first time) … next try …`) |
| Partial answers that say why | search `partial` + `failed: [{source, reason}]` |
| Signed short-lived identity | `TokenVerifier.mint(user, roles, seconds)`; one `DRISHTI_TOKEN_SECRET` (or OIDC) for every node |
| A shared user store | the identity database on PostgreSQL (`DRISHTI_IDENTITY_DB_URL`) instead of the per-node SQLite default |
| Live streams | `StreamController` (server-sent events) and the subscriptions behind it |
| Health and activity | `HealthController` (with `RequestActivity` and `PageTimes`), Admin → Health |
| Files under the data folder | `drishti.data.dir`: the cluster's files go in `<data dir>/net/` |

## 5. Membership: gossip over HTTP

**Protocol.** SWIM-style membership (Das, Gupta, Motivala 2002, as used by Consul's memberlist and Cassandra-like systems),
carried over HTTP instead of UDP:

- Every `gossip-interval` (1 s) a node picks `fanout` (3) random alive members and does a **push-pull** exchange:
  `POST /api/v1/net/gossip` **(new)** with its member table; the answer is the peer's table. Both merge.
- Each member entry: `nodeId` (stable, generated once and saved), `url` (the advertised address), `incarnation` (raised only
  by the node itself, to refute a suspicion), `heartbeat` (raised every interval), `state` (`alive`, `suspect`, `dead`,
  `left`), `digestHash`, `version`, `since`.
- **Merge rule:** the higher incarnation wins; at equal incarnation the "worse" state wins (`left` > `dead` > `suspect` >
  `alive`), except that a node's own entry is only ever changed by itself.
- **Failure detection:** a member whose heartbeat has not risen for `suspect-after` (5 s) is probed directly
  (`GET /api/v1/net/ping` **(new)**) and indirectly through `indirect-probes` (2) other members; still silent, it becomes
  `suspect`; after `dead-after` (30 s) as suspect it becomes `dead`. A suspected node that hears of it raises its
  incarnation and gossips `alive`.
- **Leaving:** a node stopping cleanly gossips `left` for a few rounds (in the shutdown hook) so peers drop it at once.
- **Joining:** a new node contacts its configured `seeds`, and failing those the peers saved in `peers.json`; one
  successful exchange gives it the whole table. With no seeds and no file it is a cluster of one.
- **Forgetting:** `dead` and `left` entries are kept `forget-after` (24 h) so a stale table cannot resurrect them, then
  dropped.

With fanout 3 and 1 s rounds, news reaches 100 nodes in about log₃(100) ≈ 4–5 rounds: seconds.

**Persistence.** Every `persist-every` (10 min), on any join or leave, and at shutdown, the node writes
`<data dir>/net/peers.json` (atomically: a temp file and a rename, as `last-run.json` is written by the lake tools): its
own `nodeId` and the members with their last-known state and digest. At start it reads the file and contacts the members
it lists (alive ones first) together with the seeds.

## 6. What a node offers: the capability digest

Each node publishes a digest **(new)**, fetched by peers with `GET /api/v1/net/digest` **(new)** when the `digestHash` in
gossip changes:

| Field | Meaning |
|---|---|
| `nodeId`, `url`, `version`, `protocol` | who, where, which build; peers refuse a protocol they do not speak |
| `packs` | name, code, version and content hash of every pack switched on |
| `kinds` | per kind: its mnemonic, its pack, whether a Sutra renders it here, and its sources |
| `sources` per kind | connector name, type, **table identity** (a Delta table's id and location; section 10), `latestDate`, `dataVersion` of the latest date, whether columnar |
| `load` | requests a second over the last minute (from `RequestActivity`) and p95 latency, for choosing between peers |
| `calendar` | the business calendar (`DRISHTI_CALENDAR`): the as-of of a forwarded request must mean the same date |

A pack listed in `peer-only-packs` is loaded and served to peers (view and data forwarding, search shares) but hidden from
this node's own users: not in its catalog, forms home, type-ahead or command line, and a direct request for its kinds from
a client (not a peer) is answered as if the pack were not here, i.e. forwarded or refused. This lets a node be a pure data or
rendering server behind others. The digest marks such packs `peerOnly: true`.

A node recomputes its digest when packs reload, a connector changes or a load makes a new data version, and raises
`digestHash`; peers pull it within one gossip round. From all digests every node builds a **capability table**
**(new)**: kind → the nodes that render it and the nodes that hold its data, with their dates.

## 7. Routing a request

Every API request goes through the same three questions, in order. A forwarded request carries
`X-Drishti-Net-Hop: 1` **(new)**, and a node receiving it answers only from itself.

1. **Can I answer it here?** The kind's pack is on, it is the newest version of that pack in the cluster, and a local source
   holds the entity on the date (`coverage` `HELD`, or the fetch finds it): answer as today. This is the only path when the
   cluster is off.
2. **Is the kind's pack missing here, or older here than on a peer?** (`TRD` asked of a node with only `core`, or of a node
   with `trading` 2.3.0 while a peer has 2.4.0.) **View forwarding:** the whole request
   (path, query, `X-Drishti-As-Of`, `X-Drishti-Known-At`, the identity of section 9) goes to a node that renders the kind,
   and its answer is passed back unchanged, so the Sutra, help and links are the owner's. The console cannot tell.
3. **Is the pack here but not the data?** (Both nodes have `trading`; only C holds CALYPSO's trades, or holds the date
   asked.) **Data forwarding:** a `PeerSource` **(new)** `SourcePlugin`, one per peer, registered after the local
   sources, fetches the document (`GET /api/v1/net/fetch/{kind}/{id}` **(new)**, the raw entity document as the caller may
   see it) and this node renders it with its own Sutra, so the screen looks the same whichever node holds the data.

**Choosing among peers** that can answer: alive (not suspect) → holds the date asked (its `latestDate` and coverage) →
freshest data version → lowest recent latency → for a tie, a stable hash of the entity id (so the same entity goes to the
same peer and its caches stay warm). A request that fails on one peer is tried once on the next (reads only), within the
caller's time budget.

## 8. Operations across nodes

| Operation | How it runs | Merge |
|---|---|---|
| Entity view, history, explain, panel records | one owner (section 7) | none |
| Search (`/api/v1/search`, query by form) | scatter to every node that holds data of the kind and is not a replica of one already asked; each runs it on its own data | rows merged by `(kind, id)` (the node whose coverage is `HELD` wins a duplicate), re-sorted by `order by`, cut to `limit`; `scanned` and `matched` summed; a failed or late node in `failed` |
| Type-ahead and suggestions | scatter with a short budget (150 ms) | merged, ranked as today, de-duplicated |
| Grouped pages (counterparty trades, netting sets over several systems) | scatter per contributing kind or source | concatenated in the page's order |
| Live stream of an entity | this node subscribes once to the owner's stream and re-emits to its viewers | none; one upstream subscription per entity per node |
| BI queries | run on the node that holds the model's data; a model spanning nodes is phase 6 | answer passed back |
| Forms (the form itself) | the node that renders the kind | none; the cache of the form (per data version) works across nodes because the digest carries the data version |
| Writes (layouts, saved searches, pivots, alerts) | the home node (section 11) | none |

The search response grows one optional field, `nodes: [{nodeId, scanned, matched, elapsedMs}]`, shown to administrators only;
a UI that ignores it sees one result, as asked.

## 9. Identity and security

- **Cluster membership is authenticated.** Gossip, digest, fetch and forwarded calls need a cluster credential: an HMAC
  over method, path, timestamp and body with the cluster key (`drishti.net.secret`, from the environment), refused outside
  a 60 s window (replay), or mutual TLS with the shared `tls.*` settings. A node without the key cannot join or read.
- **The user is carried, not trusted blindly.** A forwarded call carries a short-lived on-behalf-of token **(new)**:
  the user, roles, embed app if any, the original node and request id, signed with the cluster key, valid 30 s. The peer
  checks it, and also checks the user exists and is enabled when `registered-users-only` is on (a shared identity database
  makes that true everywhere).
- **Masks twice.** The peer redacts as it would for that user; the asking node, which renders, applies its own masks again.
  The stricter configuration wins; an administrator sees on Admin → Net when two nodes' `redact` lists differ.
- **Kinds per role and pack visibility** are checked on both nodes; a kind a role may not open on the asking node is
  refused there before any call is made.
- **Audit.** The audit row of a forwarded request records both nodes and the request id; Admin → Net lists forwarded
  traffic.
- **Key rotation without downtime.** `drishti.net.secret` signs; `drishti.net.previous-secret` is also accepted while set.
  To rotate: give every node the new key as `secret` and the old as `previous-secret` (rolling restarts, the cluster keeps
  working), then remove `previous-secret`. On-behalf-of tokens live 30 s, so none outlives the rollover.
- **Same login everywhere.** One `DRISHTI_TOKEN_SECRET` (or one OIDC issuer) for the cluster, so a token minted by any
  node is valid on every node.

## 10. Shared and separate lakes

A Delta table is identified by its table id and location. Two nodes whose digests name the same table identity read the
**same data**: they are *replicas* for that kind, and a search asks only one of them. Different tables of the same kind
(ENDUR's trades on A, CALYPSO's on C) are *shards*: a search asks all. A shared lake therefore needs no forwarding at all
for nodes that both read it; separate lakes are joined by data forwarding and scatter-gather. Each node keeps its own
caches (id maps, document cache, grouped pages); the lake tools' per-table locks already let several writers load
different tables side by side.

## 11. State that belongs to a user

Saved searches, pivots, layouts, workspaces, alerts and API tokens live today in each server's data folder or identity
database. Phase 1: the node a console is connected to is the user's **home node**; their state is read and written there,
and a forwarded view applies the user's layout because the asking node renders it (data forwarding) or passes the layout id
(view forwarding, phase 3). With the identity database on PostgreSQL, users, roles and tokens are shared by every node at
once. Replicating the rest (or moving it into the shared database) is phase 7.

The console keeps one `backend.url`; it may list several nodes (`backend.urls`, **new**) and fails over to the next when
one stops, which the cluster makes safe because any node answers.

## 12. Failure

| Event | What happens |
|---|---|
| A peer stops cleanly | it gossips `left`; peers drop it from the capability table within a round; requests go elsewhere or to a later source |
| A peer crashes | suspected after 5 s, dead after 30 s more; meanwhile `RetryBoard` backs off calls to it; answers name it in `failed` |
| A network split | each side carries on with what it can reach; searches are partial and say which nodes are missing; when the split heals, gossip merges (higher incarnation wins) |
| A slow peer | its share of a scatter is cut at the caller's budget and named; single-owner reads try the next peer once |
| Two nodes disagree on a date (different calendars) | a peer whose `calendar` differs is used only for live reads; Admin → Net warns |
| A node restarts | reads `peers.json`, rejoins in one round, republishes its digest |
| Mixed versions | the `protocol` number gates gossip; an older node is listed but not routed to for features it lacks |

## 13. Admin and observability

- **Admin → Net (new):** members with state, incarnation, version, last heard, latency, packs and kinds; the capability
  table (kind → nodes, dates, replicas or shards); forwarded requests a minute; differences in masks or calendars.
- **Admin → Health:** a `net` section (members alive and suspect, forwarded requests and failures over 10 minutes).
- **Metrics:** `drishti_net_members{state}`, `drishti_net_forwarded_total{peer,kind,outcome}`, `drishti_net_gossip_rounds_total`,
  peer latency histograms.
- **A view's provenance** (*How this view was built*) names the node that held the data, for administrators.

## 14. Configuration

```yaml
drishti:
  net:
    enabled: ${DRISHTI_NET_ENABLED:false}
    node-id: ${DRISHTI_NET_NODE_ID:}            # empty: generated once, kept in <data dir>/net/node-id
    advertise-url: ${DRISHTI_NET_URL:}          # how peers reach this node, e.g. https://drishti-b:8480
    seeds: ${DRISHTI_NET_SEEDS:}                # host:port,host:port (optional)
    secret: ${DRISHTI_NET_SECRET:}              # the cluster key (required when enabled, 32+ bytes)
    previous-secret: ${DRISHTI_NET_PREVIOUS_SECRET:}   # also accepted during a key rollover (section 9)
    peer-only-packs: ${DRISHTI_NET_PEER_ONLY_PACKS:}   # packs this node serves to peers only (section 6)
    gossip-interval: 1s
    fanout: 3
    suspect-after: 5s
    indirect-probes: 2
    dead-after: 30s
    forget-after: 24h
    persist-every: 10m                          # <data dir>/net/peers.json
    forward-timeout: 5s                         # within the caller's own budget
    scatter-budget: 3s                          # a search's share per node
    suggest-budget: 150ms
    max-hops: 1
```

Start-up refuses `enabled: true` without a secret and an advertise URL (as the server refuses a missing token secret with
security on).

## 15. APIs

All new, all under `/api/v1/net`, cluster credential required:

| Endpoint | Purpose |
|---|---|
| `POST /gossip` | push-pull of member tables |
| `GET /ping` | direct and indirect probes (`?target=` for an indirect one) |
| `GET /digest` | this node's capability digest |
| `GET /fetch/{kind}/{id}` | one entity document as the on-behalf-of user may see it (`X-Drishti-As-Of`, `X-Drishti-Known-At`) |
| `GET /search` | this node's share of a scattered search (no further scatter) |
| `GET /members` | the member table and capability table (also for Admin → Net, admin token) |

View forwarding reuses the ordinary `/api/v1/**` endpoints with the hop header and the on-behalf-of token.

## 16. Build plan

| Phase | What | Accepted when |
|---|---|---|
| N1 | Membership: gossip, probes, states, `peers.json`, Admin → Net members | 5 nodes started from 1 seed all list each other within 5 s; one killed is `dead` everywhere within 40 s; a restarted node rejoins from its file with no seeds |
| N2 | Capability digests and the capability table | a pack switched on at one node shows in every node's table within 2 s |
| N3 | Security: cluster key or mTLS, key rollover, on-behalf-of token, masks twice, audit | a node without the key is refused; a rolling key change drops no request; a masked field stays masked when the data comes from a peer whose `redact` is looser |
| N4 | View forwarding and data forwarding (`PeerSource`), newest pack wins, peer-only packs | the console on B opens a trade only A holds, and a kind only A's pack renders, with no visible difference; with a newer pack on A, B's users see A's version; a peer-only pack is invisible to B's own users but served to peers; a peer down gives a clear error, not a wrong page |
| N5 | Scatter-gather search, type-ahead and grouped pages; replicas versus shards | a search on B across trades held on A and C equals the union, with duplicates removed and `failed` naming a stopped node |
| N6 | Live relay; BI queries routed to the model's node | a ticking entity held on A streams to a viewer on B; a BI report on B over A's model answers |
| N7 | User state across nodes; console `backend.urls` failover | saved searches follow a user to another node; the console survives a node stop |

N1–N5 are the first release; N6–N7 may follow.

## 17. Tests

- **In-process clusters:** several Spring Boot contexts in one JVM on random ports (`SpringBootTest` with a web
  environment), with a fake clock for the failure detector; joins, leaves, crashes, a split and its healing.
- **Routing:** each path of section 7 with stand-in peers; the hop limit (a forwarded request never forwards); masks
  twice; a peer that answers slowly or wrongly.
- **Scatter-gather:** merges against a single-node answer over the same data split three ways.
- **Persistence:** `peers.json` written atomically, read at start, a corrupt file ignored with a warning.
- **A soak on real processes:** three servers on scratch ports, one killed and restarted under `tools/bench/users.py` load.

## 18. Risks and open questions

- **Decided (product owner, 2026-10-18):** (1) when nodes hold different versions of a pack, the **newest version is served**
  (view forwarding to a node that has it, even when this node could answer with its older version; equal versions with
  different content hashes are flagged on Admin → Net); (2) the cluster key **rotates without downtime** (two keys accepted
  during a rollover, section 9); (3) a node **may host a pack only for its peers** (`peer-only-packs`, section 6).
- **Latency of scatter-gather** grows with the slowest node; budgets and partial answers bound it.
- **Cache coherence:** each node caches what it fetched from peers; data versions in the digest invalidate them, a
  `knownAt` read bypasses them.
- **Open:** should search scatter be limited to nodes a role may reach?
