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
# Clusters, AWS and scale: deploying Drishti across servers

How Drishti runs as more than one server, and where: the **mechanics of a cluster** (section 1, Drishti\*net: membership by
gossip, the capability digest, routing, shared and separate lakes, failure; built in 2.5.0, off by default), **Drishti on AWS**
(section 2: ECS, EFS and S3, the path map, the images, task and service definitions; scripts in `deploy/`), **the Delta lake in S3 past
200 TB** (section 3: setup step by step, layout, loading, maintenance, caches, sizing), the **scale strategy** for one server (section 4:
millions of trades a day in a bounded heap; built and measured) and the **super-scale design** (section 5: 500 GB of JSON a day for
seven years on one shared lake; design, not built).

The operator's day-to-day guide to a cluster (the files of `config/net/`, Admin → Net, drain and maintenance, a cluster of three on one
machine) is [OPERATIONS.md section 18](OPERATIONS.md#18-drishtinet-running-several-servers-as-one); memory, CPU and disk are
[RESOURCES_AND_SCALE.md](../architecture/RESOURCES_AND_SCALE.md); VaR across servers is
[VAR_SHARDING.md](../architecture/VAR_SHARDING.md). Each part keeps its own section numbers (section 4's "section 12" is
*12. Phases and status* inside section 4).

| Part | Status |
|---|---|
| 1. Drishti\*net | built, released in 2.5.0; transport reworked in 2.6.7; off by default |
| 2. Drishti on AWS | scripts and templates in `deploy/aws/ecs/` and `deploy/docker/ecs/` |
| 3. The Delta lake in S3 | scripts in `deploy/aws/`, tested with `--dry-run`; not yet run against a real AWS account |
| 4. Scale strategy | phases 0 to 5 built and measured |
| 5. Super scale | design, not built; release not decided |

## Contents

1. [Drishti*net: many servers, one Drishti](#1-drishtinet-many-servers-one-drishti)
    - [1. The ask](#1-the-ask)
    - [2. The idea in one picture](#2-the-idea-in-one-picture)
    - [3. Principles and non-goals](#3-principles-and-non-goals)
    - [4. What the code already gives us](#4-what-the-code-already-gives-us)
    - [5. Membership: gossip over HTTP](#5-membership-gossip-over-http)
    - [6. What a node offers: the capability digest](#6-what-a-node-offers-the-capability-digest)
    - [7. Routing a request](#7-routing-a-request)
    - [8. Operations across nodes](#8-operations-across-nodes)
    - [9. Identity and security](#9-identity-and-security)
    - [10. Shared and separate lakes](#10-shared-and-separate-lakes)
    - [11. State that belongs to a user](#11-state-that-belongs-to-a-user)
    - [12. Failure](#12-failure)
    - [13. Admin, management and observability](#13-admin-management-and-observability)
    - [14. Configuration](#14-configuration)
    - [15. APIs](#15-apis)
    - [16. Build plan](#16-build-plan)
    - [17. Tests](#17-tests)
    - [17A. Documentation (part of every phase, not after it)](#17a-documentation-part-of-every-phase-not-after-it)
    - [18. Risks and open questions](#18-risks-and-open-questions)
    - [19. As built](#19-as-built)
2. [Drishti on AWS: ECS, EFS and S3](#2-drishti-on-aws-ecs-efs-and-s3)
    - [1. The ask and the rule](#1-the-ask-and-the-rule)
    - [2. The picture](#2-the-picture)
    - [3. The path map](#3-the-path-map)
    - [4. EFS](#4-efs)
    - [5. The server container](#5-the-server-container)
    - [6. The console container](#6-the-console-container)
    - [7. Starting a node](#7-starting-a-node)
    - [8. S3](#8-s3)
    - [9. Drishti*net on ECS](#9-drishtinet-on-ecs)
    - [10. Network](#10-network)
    - [11. Identity, roles and secrets](#11-identity-roles-and-secrets)
    - [12. Task definition](#12-task-definition)
    - [13. Service definition](#13-service-definition)
    - [14. Operations](#14-operations)
    - [15. Observability](#15-observability)
    - [16. Disaster recovery](#16-disaster-recovery)
    - [17. Performance: what EFS costs](#17-performance-what-efs-costs)
    - [18. Cost](#18-cost)
    - [19. Tests and acceptance](#19-tests-and-acceptance)
    - [20. Build plan](#20-build-plan)
    - [21. Open questions](#21-open-questions)
    - [22. Iceberg on AWS (2.9.5)](#22-iceberg-on-aws-295)
3. [A high-performance Delta lake on AWS, past 200 TB](#3-a-high-performance-delta-lake-on-aws-past-200-tb)
    - [1. The picture](#1-the-picture)
    - [2. Why S3 and not EFS for the lake](#2-why-s3-and-not-efs-for-the-lake)
    - [3. Built and planned](#3-built-and-planned)
    - [4. First-time setup, step by step](#4-first-time-setup-step-by-step)
    - [5. The layout: buckets, prefixes, tables and partitions](#5-the-layout-buckets-prefixes-tables-and-partitions)
    - [6. Keys: the id, promoted columns and statistics](#6-keys-the-id-promoted-columns-and-statistics)
    - [7. Files: sizes, row groups and sorting](#7-files-sizes-row-groups-and-sorting)
    - [8. Loading: the daily load, backfill and restatement](#8-loading-the-daily-load-backfill-and-restatement)
    - [9. Maintenance: checkpoints, retention, vacuum and storage tiers](#9-maintenance-checkpoints-retention-vacuum-and-storage-tiers)
    - [10. Caches: EFS or local NVMe](#10-caches-efs-or-local-nvme)
    - [11. Staying fast past 200 TB](#11-staying-fast-past-200-tb)
    - [12. Sizing and cost](#12-sizing-and-cost)
    - [13. Runbooks](#13-runbooks)
    - [14. The scripts](#14-the-scripts)
    - [15. Limits and what comes next](#15-limits-and-what-comes-next)
4. [Scale strategy: millions of trades, fast, in a bounded heap](#4-scale-strategy-millions-of-trades-fast-in-a-bounded-heap)
    - [1. Goal](#1-goal)
    - [2. Ground rules](#2-ground-rules)
    - [3. How Drishti reads today (2.3.0 plus the phase 1 fixes)](#3-how-drishti-reads-today-230-plus-the-phase-1-fixes)
    - [4. Measurements (2026-10-18)](#4-measurements-2026-10-18)
    - [5. Memory: what lives in the heap](#5-memory-what-lives-in-the-heap)
    - [6. Storage and layout](#6-storage-and-layout)
    - [7. Read paths and targets](#7-read-paths-and-targets)
    - [8. Caches: three tiers, all disposable](#8-caches-three-tiers-all-disposable)
    - [9. Freshness](#9-freshness)
    - [10. Grouped pages: on disk (built 2026-10-18)](#10-grouped-pages-on-disk-built-2026-10-18)
    - [11. Concurrency and time limits](#11-concurrency-and-time-limits)
    - [12. Phases and status](#12-phases-and-status)
    - [13. Settings reference](#13-settings-reference)
    - [14. Operations](#14-operations-1)
    - [15. Risks](#15-risks)
    - [16. Decisions](#16-decisions)
    - [17. Considered and rejected](#17-considered-and-rejected)
5. [Super scale: 500 GB of JSON a day for seven years (design, not built)](#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built)
    - [1. The ask](#1-the-ask-1)
    - [2. The size of it](#2-the-size-of-it)
    - [3. What breaks if nothing changes](#3-what-breaks-if-nothing-changes)
    - [4. Principles](#4-principles)
    - [5. The architecture](#5-the-architecture)
    - [6. The lake in S3](#6-the-lake-in-s3)
    - [7. Loading 500 GB a day](#7-loading-500-gb-a-day)
    - [8. Products: computed when loading, not when asked](#8-products-computed-when-loading-not-when-asked)
    - [9. Reading one entity](#9-reading-one-entity)
    - [10. Many servers on one lake: slices and owners](#10-many-servers-on-one-lake-slices-and-owners)
    - [11. Searches, lists, pages and BI](#11-searches-lists-pages-and-bi)
    - [12. Type-ahead](#12-type-ahead)
    - [13. Memory and caches on a server](#13-memory-and-caches-on-a-server)
    - [14. Freshness and consistency](#14-freshness-and-consistency)
    - [15. Seven years: storage tiers and retention](#15-seven-years-storage-tiers-and-retention)
    - [16. Consoles](#16-consoles)
    - [17. Failures](#17-failures)
    - [18. Security at scale](#18-security-at-scale)
    - [19. Sizing and cost](#19-sizing-and-cost)
    - [20. Observability](#20-observability)
    - [21. Settings (proposed)](#21-settings-proposed)
    - [22. Build plan](#22-build-plan)
    - [23. Tests](#23-tests)
    - [24. Risks and open questions](#24-risks-and-open-questions)

## 1. Drishti*net: many servers, one Drishti

Status: **built and released in 2.5.0** (2026-10-19; wave N, decided by the product owner on 2026-10-18, see
[BI_DESIGN.md §12.2](../architecture/BI_DESIGN.md#122-the-three-waves)); the transport between nodes was reworked in 2.6.7 ([below](#the-transport-between-nodes-as-built-267)) and the
content store followed (2.7.0, [11.2](#112-content-every-node-reaches-the-content-store-270)). What is built, and what changed from the design, is in [section 19](#19-as-built). Off by default. Names marked **(new)** do not exist
in the code today; every other class, file or setting named here does.

### 1. The ask

The product owner, 2026-10-18:

1. Several Drishti servers run at once; they may host different packs, or overlapping ones.
2. They may share a Delta lake, or not.
3. Each server is optionally given the `host:port` of some peers.
4. The servers gossip over HTTP with a standard gossip protocol, so all of them soon know each other; joins and departures
   spread the same way. Each saves the peers it knows about every 10 minutes and uses that list to rejoin after a restart.
5. Each server tells its peers the packs and mnemonics it serves. A request is served locally when it can be; otherwise by a
   peer that can, whose answer the server passes back.
6. A UI connects to any member and never knows where the data came from.

### 2. The idea in one picture

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

### 3. Principles and non-goals

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

### 4. What the code already gives us

| Need | Already there |
|---|---|
| A place to plug a peer in | `SourceRouter.candidates(kind)` orders every registered `SourcePlugin` whose manifest serves the kind; `ConnectorInstance` wraps a plugin per connector |
| Knowing whether a source holds a date | `SourcePlugin.coverage(kind, asOf)` (`HELD` / `NOT_HELD` / `UNKNOWN`), `earliestDate(kind)`, `latestDate(kind)`, `dataVersion(kind, date)` |
| Calling another HTTP service well | the REST connector (`RestSourcePlugin`): timeouts, the shared `tls.*` settings incl. mutual TLS, the site proxy |
| Retry and back-off per failing source | `RetryBoard` (`connector '…' failed (first time) … next try …`) |
| Partial answers that say why | search `partial` + `failed: [{source, reason}]` |
| Signed short-lived identity | `TokenVerifier.mint(user, roles, seconds)`; one `DRISHTI_TOKEN_SECRET` (or OIDC) for every node |
| A shared user store | the identity database on PostgreSQL (`DRISHTI_IDENTITY_DB_URL`) instead of the per-node SQLite default |
| Live streams | `StreamController` (server-sent events) and the subscriptions behind it |
| Health and activity | `HealthController` (with `RequestActivity` and `PageTimes`), Admin → Health |
| Files under the data folder | `drishti.data.dir`: the cluster's files go in `<data dir>/net/` |

### 5. Membership: gossip over HTTP

**Protocol.** SWIM-style membership (Das, Gupta, Motivala 2002, as used by Consul's memberlist and Cassandra-like systems),
carried over HTTP instead of UDP:

- Every `gossip-interval` (1 s) a node picks `fanout` (3) random alive members and does a **push-pull** exchange:
  `POST /api/v1/net/gossip` **(new)** with its member table; the answer is the peer's table. Both merge.
- Each member entry: `nodeId` (stable, generated once and saved), `url` (the advertised address: always a real one, never
  `localhost`, `127.x.x.x`, `::1`, `0.0.0.0` or `::`; an entry that names one is never stored nor sent on, and no saved member with one is
  tried on a rejoin), `incarnation` (raised only
  by the node itself, to refute a suspicion), `heartbeat` (raised every interval), `state` (`alive`, `suspect`, `dead`,
  `left`), `digestHash`, `version`, `since`.
- **Merge rule:** the higher incarnation wins; at equal incarnation the "worse" state wins (`left` > `dead` > `suspect` >
  `alive`), except that a node's own entry is only ever changed by itself.
- **Failure detection:** a member whose heartbeat has not risen for `suspect-after` (5 s) is probed directly
  (`GET /api/v1/net/ping` **(new)**) and indirectly through `indirect-probes` (2) other members; still silent, it becomes
  `suspect`; after `dead-after` (30 s) as suspect it becomes `dead`. A suspected node that hears of it raises its
  incarnation and gossips `alive`.
- **Off the busy path (2.6.7):** gossip, probes and digest pulls go to a member's own **gossip port** (`gossipPort` in its entry; node.yaml
  `gossip-port`, `auto` by default), a second connector with eight platform threads that serves nothing else, and are answered there (and
  on the server's port) by the first filter, from memory; the sender uses a client and threads of its own. A server whose request
  handling is saturated is never wrongly suspected ([as built](#the-transport-between-nodes-as-built-267)).
- **Leaving:** a node stopping cleanly gossips `left` for a few rounds (in the shutdown hook) so peers drop it at once.
- **Joining:** a new node contacts its configured `seeds`, and failing those the peers saved in `peers.json`; one
  successful exchange gives it the whole table. With no seeds and no file it is a cluster of one. A server joins (gossips itself
  `alive`) only once it is ready, on Spring's `ApplicationReadyEvent`: its web server answers and its connectors have started
  (after the 2.6.5 soak, SOAK-03). Until then its peers keep it as they last saw it (dead, after a restart) and answer `DRS-5052` naming it; before,
  a restarting node joined 12 to 19 s before its port opened and peers got `ConnectException`. The log says `net: Drishti*net is on;
  this node joins once the server is ready`, then `joined Drishti*net` right after `Tomcat started`.
- **Joining until the first digests (after the 2.6.6 soak 3, SOAK3-01):** a node that joined with seeds or saved members is *joining*
  until it has the digest of every alive peer that gossips one (`DigestService.joining()`), at most routing.yaml's `join-grace` (30 s)
  after it joined; it is joining too while node.yaml has it on and the server has not joined yet (its port may already answer). Then it
  does not know which peers hold a kind, so a read that none of its sources holds waits up to `join-wait` (2 s, never more than half the
  `forward-timeout`) for the digests and is answered from the peer; still joining, it answers `502 DRS-5052` "this node is still joining
  Drishti*net and does not know yet which nodes hold <kind>; try again in a moment", never `404 DRS-1001`. A history's days nothing here
  holds are so too (all of them: `DRS-5052`; some: the answer is partial naming the node joining), and a view of a kind whose pack is not
  here waits the same way before `ViewForwardFilter` routes it. The console tries such a read once on another server and does not count
  it against the node (section 11.1). A single server, and a node with no seeds and no saved members, are never joining. The log says
  `net: node … has joined: it has its peers' digests (2), 270 ms after joining`; Admin → Health's Drishti*net section says `state:
  joining` until then (`member` after). In soak 3, a node 0.1 to 0.7 s after its `joined Drishti*net` line answered three reads
  `404 DRS-1001` for trades only its peers held; `NetRejoinMissTest` asked a restarted node 408 times from its first open port: 81 were
  404 before, none after (every answer 200, held until the digests were in, about 0.3 s).
- **Forgetting:** `dead` and `left` entries are kept `forget-after` (24 h) so a stale table cannot resurrect them, then
  dropped.

With fanout 3 and 1 s rounds, news reaches 100 nodes in about log₃(100) ≈ 4–5 rounds: seconds.

**Persistence.** Every `persist-every` (10 min), on any join or leave, and at shutdown, the node writes
`<data dir>/net/peers.json` (atomically: a temp file and a rename, as `last-run.json` is written by the lake tools): its
own `nodeId` and the members with their last-known state and digest. At start it reads the file and contacts the members
it lists (alive ones first) together with the seeds.

### 6. What a node offers: the capability digest

Each node publishes a digest **(new)**, fetched by peers with `GET /api/v1/net/digest` **(new)** when the `digestHash` in
gossip changes:

| Field | Meaning |
|---|---|
| `nodeId`, `url`, `version`, `protocol` | who, where, which build; peers refuse a protocol they do not speak |
| `packs` | name, code, version and content hash of every pack switched on |
| `kinds` | per kind: its mnemonic, its pack, whether a Sutra renders it here, and its sources |
| `sources` per kind | connector name, type, **table identity** (a Delta table's id and location; section 10), `earliestDate` and `latestDate` (the oldest and newest business dates it holds; a node that goes down counts only for the dates between them, section 7), `dataVersion` of the latest date, whether columnar |
| `load` | requests a second over the last minute (from `RequestActivity`) and p95 latency, for choosing between peers |
| `calendar` | the business calendar (`DRISHTI_CALENDAR`): the as-of of a forwarded request must mean the same date |

A pack listed in `peer-only-packs` (`node.yaml`) is loaded and served to peers (view and data forwarding, search shares) but hidden from
this node's own users: not in its catalog, forms home, type-ahead or command line, and a direct request for its kinds from
a client (not a peer) is answered as if the pack were not here, i.e. forwarded or refused. This lets a node be a pure data or
rendering server behind others. The digest marks such packs `peerOnly: true`.

A node recomputes its digest when packs reload, a connector changes or a load makes a new data version, and raises
`digestHash`; peers pull it within one gossip round. From all digests every node builds a **capability table**
**(new)**: kind → the nodes that render it and the nodes that hold its data, with their dates.

### 7. Routing a request

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
   The view's linked entities are read so too, within routing.yaml's `link-budget` (1 s) when they are on a peer.

**Choosing among peers** that can answer: alive (not suspect) → holds the date asked (its `latestDate` and coverage) →
freshest data version → lowest recent latency → for a tie, a stable hash of the entity id (so the same entity goes to the
same peer and its caches stay warm). A request that fails on one peer is tried once on the next (reads only), within the
caller's time budget. A peer that refuses the connection (it just died, or is restarting) passes the read to the next holder.

**A node that is down counts only for what it may hold** (after the 2.6.5 soak, SOAK-01). An answer that is short because a holder of the kind is
down, suspect, drained or refusing connections names it (`DRS-5052`), but only for the business dates its last digest said its sources
of the kind hold (`earliestDate` to `latestDate`, section 6). When the live nodes and local sources answered every date the down node
could have, the answer is complete: a calendar window that reaches back before every lake's first day is not partial because a node is
down.

### 8. Operations across nodes

| Operation | How it runs | Merge |
|---|---|---|
| Entity view, history, explain, panel records | one owner (section 7) | none |
| Search (`/api/v1/search`, query by form) | scatter to every node that holds data of the kind and is not a replica of one already asked; each runs it on its own data | rows merged by `(kind, id)` (the node whose coverage is `HELD` wins a duplicate), re-sorted by `order by`, cut to `limit`; `scanned` and `matched` summed, an entity two nodes matched counted once; a failed or late node in `failed` |
| Type-ahead and suggestions | scatter with a short budget (150 ms) | merged, ranked as today, de-duplicated |
| Grouped pages (counterparty trades, netting sets over several systems) | scatter per contributing kind or source | concatenated in the page's order |
| Live stream of an entity | this node subscribes once to the owner's stream and re-emits to its viewers | none; one upstream subscription per entity per node |
| BI queries | run on the node that holds the model's data; a model spanning nodes is phase 6 | answer passed back |
| Forms (the form itself) | the node that renders the kind | none; the cache of the form (per data version) works across nodes because the digest carries the data version |
| Writes (layouts, saved searches, pivots, alerts) | the home node (section 11) | none |

The search response grows one optional field, `nodes: [{nodeId, scanned, matched, elapsedMs}]`, shown to administrators only;
a UI that ignores it sees one result, as asked.

### 9. Identity and security

- **Cluster membership is authenticated.** Gossip, digest, fetch and forwarded calls need a cluster credential: an HMAC
  over method, path, timestamp and body with the cluster key (`secret` in `config/net/node.yaml`, from the environment), refused outside
  a 60 s window (replay), or mutual TLS with the shared `tls.*` settings. A node without the key cannot join or read.
- **The user is carried, not trusted blindly.** A forwarded call carries a short-lived on-behalf-of token **(new)**:
  the user, roles, embed app if any, the original node and request id, signed with the cluster key, valid 30 s. The peer
  checks it, and also checks the user exists and is enabled when `registered-users-only` is on (the nodes copy users, roles
  and API tokens to each other, section 19 *Identity across nodes*, or share one identity database, so that is true everywhere).
- **Masks twice.** The peer redacts as it would for that user; the asking node, which renders, applies its own masks again.
  The stricter configuration wins; an administrator sees on Admin → Net when two nodes' `redact` lists differ. What a peer sent
  masked for one user is never kept for another: a cache of it is keyed by the caller's security signature (their sorted roles and
  embedding app, `Principal.securitySignature()`): the live relay's topic partition, the history cache's days read through a peer
  (DATA_ARCHITECTURE.md › History queries: an entity over time, section 11), and, through `SourceRouter.partition()`, the map panels' listing and documents and a view's shape
  cache (after soak 2's SOAK2-01).
- **Nodes per role.** A role may name the nodes it may reach (`config/net/access.yaml`, **new**: node ids or tags such as
  `region:emea`; empty means every node). The capability table is filtered by the caller's roles before any
  routing: a scattered search, type-ahead or forwarded request never reaches a node outside them, and the peer refuses
  an on-behalf-of token whose roles do not reach it. A kind held only on unreachable nodes is answered as not held.
- **Kinds per role and pack visibility** are checked on both nodes; a kind a role may not open on the asking node is
  refused there before any call is made.
- **Audit.** The audit row of a forwarded request records both nodes and the request id; Admin → Net lists forwarded
  traffic.
- **Key rotation without downtime.** `secret` (in `node.yaml`) signs; `previous-secret` is also accepted while set.
  To rotate: give every node the new key as `secret` and the old as `previous-secret` (rolling restarts, the cluster keeps
  working), then remove `previous-secret`. On-behalf-of tokens live 30 s, so none outlives the rollover.
- **Same login everywhere.** One `DRISHTI_TOKEN_SECRET` (or one OIDC issuer) for the cluster, so a token minted by any
  node is valid on every node.

### 10. Shared and separate lakes

A Delta table is identified by its table id and location, a `file` connector's kind by its folder and the fingerprint of its
files. Two nodes whose digests name the same table identity read the
**same data**: they are *replicas* for that kind, and a search asks only one of them. Different tables of the same kind
(ENDUR's trades on A, CALYPSO's on C) are *shards*: a search asks all. A shared lake therefore needs no forwarding at all
for nodes that both read it; separate lakes are joined by data forwarding and scatter-gather. Each node keeps its own
caches (id maps, document cache, grouped pages); the lake tools' per-table locks already let several writers load
different tables side by side.

### 11. State that belongs to a user

Saved searches, pivots, layouts, workspaces, alerts and API tokens live today in each server's data folder or identity
database. Phase 1: the node a console is connected to is the user's **home node**; their state is read and written there,
and a forwarded view applies the user's layout because the asking node renders it (data forwarding) or passes the layout id
(view forwarding, phase 3). With the identity database on PostgreSQL, users, roles and tokens are shared by every node at
once. Replicating the rest (or moving it into the shared database) is phase 7. As built (section 19, N7), the user's documents, sessions and
identity (users, roles, API tokens) are all copied between nodes, so there is no home node to lose.

#### 11.1 The console and several servers (new)

Once Drishti*net is on, the console is given **several servers** instead of one, so it always has one to fall back to:

```yaml
# drishti-console/config/application.yaml
backend:
  urls: ${DRISHTI_BACKEND_URLS:}       # e.g. https://drishti-a:8480 (one is enough; empty: backend.url, as today)
  discover: true                       # pull the cluster's member list from the server it is connected to and keep it
  discover-every: 60s                  # how often the list is pulled again
  check-every: 5s                      # how often each listed server's health is looked at
  fail-after: 2                        # failed requests in a row before a server is set aside
```

- **The console keeps the whole cluster's list.** Every `discover-every` (60 s) it pulls the member list from the server it is
  connected to (`GET /api/v1/net/members`: alive members with their advertised URLs, tags and packs) and keeps the whole
  list itself, so one configured server is enough: it learns every other member, and new members and departures within a
  minute. The list is saved to `<console data dir>/net/servers.json` (atomically, and at shutdown), so a console restarted
  while its configured server is down still knows where else to go. A member the cluster reports `dead` or `left` is
  dropped from the list; the configured `urls` are never dropped. A discovered URL that no other machine can call is never
  kept, by the server's own rule for `advertise-url`: localhost in any spelling, a loopback, wildcard or link-local address in
  any notation (`0`, `127.0.1.1`, `2130706433`, `0x7f000001`), a container bridge, or a host name that resolves to one of them.
- **One active server, the rest standing by.** The console sends its requests to the first healthy server (its user's
  *home node*, section 11): the configured ones first, then the discovered ones, nearest first by measured latency. Each
  server in the list is health-checked every `check-every`.
- **Falling over.** A server that refuses connections, times out or answers 502/503 `fail-after` times in a row is set
  aside, and the console moves to the next healthy one. A 502/503 that is the server's own answer about **another** node is not
  counted: `DRS-5050` to `DRS-5059` (a peer that holds the data is down, refuses the connection or failed), and from a server of 2.6.5 or
  older a `DRS-1003` whose detail names a peer (`net:<node> failed reading …`). A peer's time-out is `504 DRS-1014`, never counted.
  A `502 DRS-5052` "this node is still joining Drishti*net" (a node just started or rejoined, section 5; SOAK3-01) is not counted either,
  and a read that gets it is tried once on another server, which knows the peers' digests (`joining_problem()` in `core/backend.py`).
  In the 2.6.5 soak the console set healthy servers aside 43 times on histories a dying peer could not answer (SOAK-02). A **read** that failed on the way is retried once on the new
  server, so the person sees the page, not an error; a **write** (save a layout, a search, a pivot) is never retried
  automatically: the page says it did not save and offers to try again.
- **One server per screen (2.7.0).** A Sutra Next screen's selections (SUTRA_NEXT.md section 7) are tokens kept in the memory
  of the node that issued them, and each token names that node (`tok_<node>~…`). The console sends a click or a panel's children
  that carry tokens to that node while it is usable, whatever server the session is on now (`ServerPool.for_tokens`); the first
  click of a screen goes to the session's server. A request that reaches another node anyway (the console has not learnt the node's
  address yet, or a proxy chose) is forwarded whole to the issuing node for the caller, as a run's calls are (`SelectionForwardFilter`,
  one hop). When the issuing node is gone the answer is `502 DRS-5052` *your selections were reset because server X went away*
  (not counted against the server that says it), and the page says so in words above the panels, forgets the selections, closes
  what they opened and unmarks the picked rows: the reader picks again, on a node that is up.
- **Nothing to sign in again.** Every node accepts the same tokens (one token secret or OIDC issuer, section 9), so the
  session carries over. Live streams (server-sent events) reconnect to the new server by themselves and resubscribe.
- **Coming back.** A set-aside server is checked on; once healthy for a minute it is used again for new sessions (not
  moved under a running session, so caches and streams stay put).
- **Visible to administrators.** Admin → Net and Admin → Health show which server the console is on, the list with each
  one's state, and the fail-overs over the last hour; each fail-over is a log line and an audit row.
- **Without Drishti*net** `backend.urls` still works as a plain list of identical servers (same packs, same lakes), but
  answers are only the same on each when they hold the same data; with Drishti*net any server answers for all.

#### 11.2 Content every node reaches: the content store (2.7.0)

Build workbench Designs are kept in the **content store** ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches)), so a console that fails over to another
member still finds them (before, the other member answered `404 DRS-5006 no design`). **Every member must use the same
`config/store/store.yaml`.** The default filesystem store on a cluster needs a **shared mount** (NFS, EFS, SMB) at the same path on every
member; a local `data/fstore` per member gives each member its own Designs again. PostgreSQL or S3 need no shared disk. A member that
finds another member recorded with a different store root logs `content store roots differ`. Two members saving the same Design at
once give one success and one `409 DRS-5057`. The rest of the per-node content moves next (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 8).

### 12. Failure

| Event | What happens |
|---|---|
| A peer stops cleanly | it gossips `left`; peers drop it from the capability table within a round; requests go elsewhere or to a later source |
| A peer crashes | suspected after 5 s, dead after 30 s more; meanwhile `RetryBoard` backs off calls to it; answers name it in `failed`; a call that meets its closed port is `DRS-5052` naming it and goes on to the next holder. Each server logs these refusals as one WARN line per change of state (`drishti-c (dead)`), then a count once a minute, never a stack trace (after the 2.6.5 soak, SOAK-06; before, about 11 MB of log a minute per server) |
| A network split | each side carries on with what it can reach; searches are partial and say which nodes are missing; when the split heals, gossip merges (higher incarnation wins) |
| A slow peer | its share of a scatter is cut at the caller's budget and named; single-owner reads try the next peer once |
| Two nodes disagree on a date (different calendars) | a peer whose `calendar` differs is used only for live reads; Admin → Net warns |
| A node restarts | reads `peers.json`; once its server is ready (web server up, connectors started) it rejoins in one round and republishes its digest |
| Mixed versions | the `protocol` number gates gossip; an older node is listed but not routed to for features it lacks |

### 13. Admin, management and observability

Asked by the product owner (2026-10-18): the cluster is managed in the console, not only through settings. **Admin → Net**
(new; administrators) has five tabs:

- **Members:** every node with state (`alive`, `suspect`, `dead`, `left`), incarnation, version and protocol, tags, last
  heard, latency, load, packs (peer-only marked) and kinds; open a node for its digest. Actions: **add a seed** (host:port,
  tried at once, saved), **remove** a dead or left node from the table, **drain** this node (it stops taking forwarded work and
  tells its peers, for maintenance) and **undrain**, **leave** (gossip `left`, then stop serving peers), **rejoin**.
- **Capability map:** kind → the nodes that render it and the nodes that hold its data, with pack versions (the newest
  marked: it is the one served), table identity (replicas or shards), latest dates; warnings for equal pack versions with
  different contents, different calendars and different mask lists.
- **Routing:** *Explain a request*: type a kind and id (or a search) and a role, and see where it would go and why (local,
  view forwarding, data forwarding, scatter list, nodes the role may not reach); the forwarded traffic a minute per peer and
  outcome (an activity chart like Admin → Health's), the slowest peers, recent failures.
- **Access:** nodes per role (pick node ids or tags), this node's tags, its peer-only packs, all editable here (written to
  `access.yaml` and `node.yaml`, section 14), audited, applied without a restart and gossiped in the digest.
- **Files:** the folder's files (`node.yaml`, `peers/*.yaml`, `gossip.yaml`, `routing.yaml`, `access.yaml`) with the
  connector editor's validation, diff, history and audit; a refused file shows its error here.
- **Security:** the cluster key's state (set, the previous key accepted until removed, the last time each peer signed with
  the old key, so an administrator knows when the rollover is complete), mutual TLS certificates and their expiry, and
  refused join attempts (address, reason).

Also: **Admin → Health** gets a `net` section (members alive and suspect, forwarded requests and failures over 10 minutes);
**metrics** `drishti_net_members{state}`, `drishti_net_forwarded_total{peer,kind,outcome}`, `drishti_net_gossip_rounds_total`
and peer latency histograms; a view's provenance (*How this view was built*) names the node that held the data, for
administrators. Every management action is in the audit log with the node it was made on. The secret itself is never shown
or set in the UI (it comes from the environment), only its state.

APIs behind the page (new, admin token): `GET /api/v1/net/members`, `POST /api/v1/net/seeds`, `DELETE /api/v1/net/members/{id}`,
`POST /api/v1/net/drain|undrain|leave|rejoin`, `GET /api/v1/net/explain?kind=&id=&q=&role=`, `GET/PUT /api/v1/net/access`,
`GET /api/v1/net/security`.

### 14. Configuration

Managed **the way connectors are** ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)), not in `application.yaml`
(decided by the product owner, 2026-10-18). `application.yaml` holds only where the files are and how they are watched:

```yaml
drishti:
  net:
    dir: ${DRISHTI_NET_DIR:./config/net}     # created on first write; ready-made files in config/net.examples/
    watch: auto                              # auto (file events, polling as a fallback) | poll | off
    poll: 5s
```

The folder (new):

| File | What it holds | A change |
|---|---|---|
| `node.yaml` | this node: `enabled` (false), `node-id` (empty: generated once, kept in `<data dir>/net/node-id`), `advertise-url`, `tags`, `peer-only-packs`, the cluster key as `secret: ${DRISHTI_NET_SECRET}` and `previous-secret: ${DRISHTI_NET_PREVIOUS_SECRET}` (environment references only, as connector passwords are), `tls.*` for mutual TLS, `gossip-port` (`auto`, a port, or `off`; 2.6.7) | applied live; `enabled` false → true joins, true → false leaves cleanly |
| `peers/<name>.yaml` | one file per seed peer, the file name its logical name: `url`, optional `tags` it is expected to carry, `tls.*` overrides | a new file is contacted at once, a deleted one is no longer a seed (gossip may still know the node) |
| `gossip.yaml` | the protocol's timings: `interval` (1s), `fanout` (3), `suspect-after` (5s), `indirect-probes` (2), `dead-after` (30s), `forget-after` (24h), `persist-every` (10m) | applied on the next round |
| `routing.yaml` | `forward-timeout` (5s), `scatter-budget` (3s), `suggest-budget` (150ms), `max-hops` (1), peer choice weights, `peer-protocol` (`http2`; 2.6.7) | applied to the next request |
| `access.yaml` | nodes per role: `roles: { <role>: { nodes: [<node id or tag>, …] } }` (empty: every node) | applied to the next request; gossiped in the digest |

```yaml
# config/net/node.yaml
enabled: true
advertise-url: https://drishti-b.internal:8480
tags: [region:emea, tier:risk]
peer-only-packs: [market-risk]
secret: ${DRISHTI_NET_SECRET}
```

```yaml
# config/net/peers/drishti-a.yaml
url: https://drishti-a.internal:8480
tags: [region:emea]
```

As with connector files: the folder is watched; every file is checked against its schema before use and a bad file is
refused with the reason (the previous settings stay in force, Admin → Net shows the error, the log names the file and line);
a value written as `${ENV}` is read from the environment and never written back in clear; **Admin → Net writes these
files** (the same editor, validation, diff and audit as Admin → Connectors; a file can also be edited by hand or shipped
by deployment tools); `drishti.py net` (new) lists, checks and edits them (`net list`, `net check`, `net add-peer <name>
<url>`, `net set node.tags region:emea`), like `drishti.py connector`. What gossip learns at run time (the member table)
is state, not configuration: it is saved to `<data dir>/net/peers.json` (section 5), never into this folder.

Start-up and every reload refuse `enabled: true` without a secret, as the server refuses a missing token secret with security
on. **Members advertise real addresses only** (product owner, 2.6.0): an `advertise-url` or a seed's `url` that is `localhost`, a loopback
(`127.x.x.x`, `::1`), a wildcard (`0.0.0.0`, `::`) or a link-local address, in any spelling (`0`, `2130706433`, `0x7f000001`, `localhost.`,
`ip6-localhost`), an address of this machine's container or VM bridges, or a host name that resolves here to one of those
(`HostAddress.why`; tools/hostaddr.py the same) is refused by the schema and dropped at the gossip intake (the server at load, Admin → Net → Files,
`drishti.py net check|set|add-peer`), with a message naming this machine's address to use. Without `advertise-url` the node advertises
this machine's primary IPv4 address with `server.port` (`HostAddress`: the interface of the default route, else the first interface that
is up and is not a loopback, a container bridge or a virtual link); a machine with no other address refuses `enabled: true` without one.
There is no escape hatch: nodes on one machine, the in-process tests (`NetNodes`), `net_stack.py`, the soak and `drishti-stack/` all use
the machine's real address (the tests skip, with that reason, on a machine that has none). The console's own `backend.urls` may still
name 127.0.0.1 (its choice, not an advertisement); what it discovers never does, and a member that moved address replaces its old one.

### 15. APIs

All new, all under `/api/v1/net`, cluster credential required:

| Endpoint | Purpose |
|---|---|
| `POST /gossip` | push-pull of member tables (2.6.7: on the gossip port too, answered by the first filter) |
| `GET /ping` | direct and indirect probes (`?target=` for an indirect one) |
| `GET /digest` | this node's capability digest |
| `GET /fetch/{kind}/{id}` | one entity document as the on-behalf-of user may see it (`X-Drishti-As-Of`, `X-Drishti-Known-At`) |
| `GET /search` | this node's share of a scattered search (no further scatter) |
| `GET /members` | the member table and capability table (also for Admin → Net, admin token) |

View forwarding reuses the ordinary `/api/v1/**` endpoints with the hop header and the on-behalf-of token.

### 16. Build plan

| Phase | What | Accepted when |
|---|---|---|
| N1 | Membership: gossip, probes, states, `peers.json`, the `config/net/` folder (watched, validated, `drishti.py net`); Admin → Net *Members* (add seed, remove, drain, leave, rejoin) and *Files* | 5 nodes started from 1 seed all list each other within 5 s; one killed is `dead` everywhere within 40 s; a restarted node rejoins from its file with no seeds |
| N2 | Capability digests and the capability table; Admin → Net *Capability map* | a pack switched on at one node shows in every node's table within 2 s |
| N3 | Security: cluster key or mTLS, key rollover, on-behalf-of token, masks twice, audit; Admin → Net *Access* and *Security* | a node without the key is refused; a rolling key change drops no request; a role limited to two nodes never reaches a third; a masked field stays masked when the data comes from a peer whose `redact` is looser |
| N4 | View forwarding and data forwarding (`PeerSource`), newest pack wins, peer-only packs; the console's `backend.urls` with health checks, discovery and fail-over (11.1); Admin → Net *Routing* (explain a request, traffic) | the console on B opens a trade only A holds, and a kind only A's pack renders, with no visible difference; with a newer pack on A, B's users see A's version; a peer-only pack is invisible to B's own users but served to peers; a peer down gives a clear error, not a wrong page; with the console configured with A alone, it learns B and C within a minute, and stopping A moves it to B within 10 s and the open page, a live stream and the next search carry on without signing in again |
| N5 | Scatter-gather search, type-ahead and grouped pages; replicas versus shards | a search on B across trades held on A and C equals the union, with duplicates removed and `failed` naming a stopped node |
| N6 | Live relay; BI queries routed to the model's node | a ticking entity held on A streams to a viewer on B; a BI report on B over A's model answers |
| N7 | User state across nodes | saved searches, layouts and pivots follow a user to another node after a fail-over |

N1–N7 were planned for 2.5.0, in this order (N1–N5 the minimum for the release); section 19 says what was built.

<a id="planned-267-the-transport-between-nodes"></a>

#### The transport between nodes (as built, 2.6.7)

Decided by the product owner on 2026-10-20, built in 2.6.7 beside BI runs ([BI_RUNS.md](../architecture/BI_RUNS.md)) and chart any table
([TABLE_CHARTS.md](../architecture/TABLE_CHARTS.md)). The transport stays **HTTP**, signed as in N3, through `PeerHttp`, which now has two lanes: a
**control lane** for gossip, probes and digests and a **data lane** for everything else.

**1. Gossip off the busy path.** Before 2.6.7 every node-to-node call shared one JDK `HttpClient` per scheme and TLS settings: gossip,
probes, digest pulls, state sync and data forwarding alike; the round loop and the exchanges ran on virtual threads; and a peer's gossip was
served on the server's port by a request thread, behind the filter chain, like any read. A server whose request handling was saturated could
then miss `suspect-after` and be marked *suspect* while healthy.

- **Receiver: a gossip port of its own (`GossipConnector`), and a priority path (`GossipPath`).** The receiver options were a dedicated
  connector, a dedicated Tomcat executor for `/api/v1/net/gossip`, or a priority path past admission. Tomcat binds an executor to a
  connector, not to a path, so a per-path executor is not possible without reading the request first; and a priority path alone (a filter
  that answers first) still needs a request thread, which is exactly what is exhausted: in `NetGossipBusyTest` with `gossip-port: off`
  (the priority path on, gossip on the server's port) every node suspected both of its peers. With virtual threads (the default) the same
  happens when CPU-bound work holds the carriers, and both share the connection limit. So the node opens **a second Tomcat connector** as
  it joins: its own acceptor, its own connection limit (4,096), its own pool of **eight platform threads**, the server's TLS
  (`server.ssl.*`, client authentication included) when it has any, and nothing served on it but `POST /api/v1/net/gossip`,
  `GET /api/v1/net/ping` and `GET /api/v1/net/digest` (anything else: `404`). node.yaml's `gossip-port` is `auto` (a free port, the
  default), a number (a firewall that names it) or `off`. The node advertises it in its member entry (`gossipPort`, a new field: an older
  node ignores it and its peers call it on its server port). On both ports the three endpoints are answered by **the first filter**
  (`GossipPath`, highest precedence): the cluster key is checked and the answer written from memory; request activity, the path and size
  guards, the token filter, view forwarding, the dispatcher and every admission gate are never reached. The controller mappings stay as
  the API of record.
- **Sender: the control lane.** Gossip exchanges, direct and indirect probes and digest pulls go through a client of their own (HTTP/1.1,
  its own connections, an executor of **two platform threads**, a connect timeout of half the gossip timeout, at most 2 s), to the member's
  gossip port. The round runs on **one platform thread** (`drishti-net-gossip`) and its exchanges and probes on a pool of **eight
  platform threads** (`drishti-net-gossip-io-*`): never the virtual-thread scheduler, the common pool or a request thread. The data lane's
  timeouts (`forward-timeout`, the budgets) never touch suspicion. A gossip port that refuses while the server's port answers (a firewall)
  is skipped for 5 minutes, with one warning; a node that is down fails on both and is never so marked.
- **Metrics:** `drishti_net_gossip_rounds_total` and the new `drishti_net_gossip_exchange_seconds` (count and sum of the exchanges that
  answered); Admin → Health's Drishti*net section shows `gossipPort` and `peerProtocol`.
- **Tested** (`NetGossipBusyTest`, three servers in one JVM, `suspect-after: 3s`, `dead-after: 4s`): each server runs six platform request
  threads and a hook holding `/api/v1/test/slow` for 2 s; forty such requests per node are kept in flight for 12 s, from outside and, for
  half of A's and C's, as B's forwarded reads over its data lane. The server's port answered nothing (a health read timed out after 1 s).
  With the gossip port: **no suspicion anywhere**, and every heartbeat rose by at least six as each node saw it. With `gossip-port: off`, the
  same load: **every node suspected both of its peers** (the failure removed). A node that then died (gossip stopped, both ports closed,
  no word to anyone) was **suspect everywhere after 3.0 s and dead after 7.0 s**, as configured.

**2. HTTP/2 for peer data.** The data lane (views and their sub-paths forwarded whole, raw documents and `PeerSource` fetches, history,
searches and their scatter, state sync, the live relay's streams) speaks **HTTP/2** when routing.yaml's `peer-protocol` is `http2`
(the default; `http1` as before 2.6.7, switched live): many requests multiplexed over **one connection per peer**, with header compression
for the signed headers.

- **Negotiation.** The JDK client does not do `h2c` with prior knowledge, only by the upgrade, and only on a request without a body. The
  servers enable HTTP/2 with `server.http2.enabled: true` (application.yaml, `DRISHTI_HTTP2`), which gives Tomcat's `Http2Protocol` as an
  upgrade protocol: `h2c` by the upgrade on plain HTTP, ALPN on TLS. `PeerHttp` primes each peer: the first call (and the first after
  30 s of quiet; Tomcat keeps an idle HTTP/2 connection 60 s here) is a signed `GET /api/v1/net/ping` made alone while the calls behind it
  wait, so the upgrade happens once. Without priming, a burst of first calls opened a connection each, every one upgraded: 20 to 40
  connections in the benchmark below instead of one.
- **Limits.** Tomcat allows 100 streams open and 20 executing per HTTP/2 connection by default; with one connection per peer that would
  queue the 21st forwarded read. Both are raised to 1,000 (`NetConfiguration.peerHttp2Limits`): the server's own admission bounds the work.
- **Fallback.** A peer that does not speak HTTP/2 (a node of before 2.6.7 during a rolling upgrade, or `server.http2.enabled: false`)
  ignores the upgrade and answers in HTTP/1.1 on the same call; the client then keeps HTTP/1.1 connections to it.
- **Tested** (`NetHttp2Test`): two nodes negotiate HTTP/2 (`HttpResponse.version()` is `HTTP_2` on every call, a user's forwarded view
  answers); a node with `server.http2.enabled: false` is answered in HTTP/1.1; a node with `peer-protocol: http1` sends HTTP/1.1 and a
  user's view through it is forwarded as before; every node's gossip port is advertised, known to its peers, answers a signed probe and
  refuses everything else. The Net* suite passes on HTTP/2.
- **Benchmark** (`NetPeerProtocolBench`, run on demand with `-Ddrishti.net.bench=true`): two servers in one JVM, 64 users at once asking B
  for trade views that A holds (each view forwarded to A), 4,000 requests per run, the two protocols alternated twice; *connections* is the
  most A held from B at once (sampled every 20 ms):

  | Run | Protocol | p50 | p99 | Requests/s | Connections B → A |
  |---|---|---|---|---|---|
  | 1 (cold) | HTTP/1.1 | 144 ms | 1,037 ms | 331 | 312 |
  | 1 (cold) | HTTP/2 | 112 ms | 277 ms | 535 | **1** |
  | 2 (warm) | HTTP/1.1 | 76 ms | 370 ms | 753 | 227 |
  | 2 (warm) | HTTP/2 | 68 ms | **117 ms** | 939 | **1** |

  One connection instead of hundreds, and a p99 a third of HTTP/1.1's; the median is about the same (the work is the view, not the wire).
- **Not in 2.6.7:** the soak comparison (17.1 run once per protocol) is for the 2.6.7 QA soak; mutual TLS between nodes is configured as
  before and was not re-measured here. **Arrow streaming over HTTP/2** (split queries returning Arrow between owners) comes with
  [section 5](#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built) **S6**.

### 17. Tests

- **In-process clusters:** several Spring Boot contexts in one JVM on random ports (`SpringBootTest` with a web
  environment), with a fake clock for the failure detector; joins, leaves, crashes, a split and its healing.
- **Routing:** each path of section 7 with stand-in peers; the hop limit (a forwarded request never forwards); masks
  twice; a peer that answers slowly or wrongly.
- **Scatter-gather:** merges against a single-node answer over the same data split three ways.
- **Persistence:** `peers.json` written atomically, read at start, a corrupt file ignored with a warning.
- **Browser tests** for the five tabs of Admin → Net (in the style of the console's `*_browser.py` suites), including a drain
  that moves traffic to another node and an *Explain* that matches the route actually taken.
- **Console fail-over:** the member list pulled, kept, saved and reloaded after a console restart with its configured server
  down; a stand-in server that stops, hangs or answers 503; reads retried once, writes never; the
  stream reconnects; a recovered server is used again only for new sessions.
- **A soak on real processes:** three servers on scratch ports, one killed and restarted under `tools/bench/users.py` load
  (`tools/bench/net_soak.py`; results in [OPERATIONS.md › Drishti*net: running several servers as one, section 13](OPERATIONS.md#18-drishtinet-running-several-servers-as-one)).

#### 17.1 The soak with history queries and long runs (2.6.5)

The soak also exercises history queries ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time), section 12 for Drishti\*net):

- **Only what the lakes hold** (SOAK-10). The servers load only packs whose tables the soak's lakes hold: **a** and **c** trading and
  counterparty-risk, **b** trading, the reference trading and counterparty-risk (trading brings core and market-data; no market-risk).
  Before a server starts, the soak writes a connector file into the node's connector folder for every Delta connector of those packs whose
  tables its lake lacks (`<lake>/<domain>/<table>/_delta_log`): the pack's template with `enabled: "false"`, the documented way
  ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) section 3). On **c**'s MUREX lake that is `trade-endur`, `trade-calypso`,
  `market-store`, `legal-store` and `collateral-store`. So no server retries a store it cannot read, and none warns `deprecated` about
  `--drishti.sources.connectors.<name>.enabled=false`, which the soak no longer passes.

- **The load.** `--history-share` (0.15 by default in the soak) of the users' requests ask a history through the console's
  `/v/{kind}/{id}/history` page, which asks the server's `GET /api/v1/history/{kind}/{id}`: a trade of any system four times in five
  (MUREX trades are held only by **c**, so from **a** and **b** they are peer-only), else a counterparty, or an equity named with
  `--equities` (the soak's lakes hold none unless a lake given to it does); with `days=30`, `days=60`, `range=3M`, `diff=true` or, one
  request in ten, `knownAt` of the moment it is asked. `history` is its own line of the latency and error table.
- **The check.** The checked set adds history requests of `--history-sample` (40) trades of every system (the MUREX ones peer-only
  from **a** and **b**), half as many counterparties and the `--equities`: about 140 distinct entities, each asked twice, as the
  administrator and as a **viewer**, with two different ranges, so that each of ten ranges (`days` 5, 30, 60, `range` 1M and 3M,
  `diff=true` over 10 and 30 days, an explicit `from`/`to` with and without `diff`, `knownAt` an instant taken after the lakes were
  built) is asked in both roles. Since soak 3 (SOAK2-01) each entity's two ranges are each asked in **both** roles, and whenever the
  checker picks one of them it asks the twin (the same entity and range in the other role) of the same node at once, so the administrator
  and the viewer meet back to back in both orders, well within the history cache's life; `soak.json` `maskPairs` counts the pairs per
  order (`adminFirst`, `viewerFirst`), how many were peer-held on the node asked, and the wrong first and second answers. The soak's servers mask fields its synthetic data really has (`--drishti.security.redact=PRICE,
  TRADER_ID,NAME`; the default list names fields such as `trader` that these trades do not hold, DRS-6011): a viewer's trade history
  must show `PRICE` (it moves when a trade is amended) and a counterparty's `NAME` masked, as a masked column, the mask on every row
  and never a value in a diff; otherwise the answer is wrong (SOAK-09: the 2026-10-20 soak checked 35 distinct histories, none of
  whose viewer trade histories had a masked column). The verdict's `masksExercised` fails unless the reference's viewer histories
  mask those fields in every one (`maskCoverage` in `soak.json`). Each is asked of the reference server holding all the data first,
  then of a random running node all along, and compared by `tools/bench/soak_check.py`: the masked columns, the window's ends and baseline,
  and every row (date, data date, carried, deleted, values, part counts, the `changed` flags and, with `diff=true`, the changes). Timings,
  cache state, generations and sources are the node's own and not compared. Any difference is a **wrong answer** and fails the soak; so
  does a masked field whose values differ between rows or that is named as changed.
- **Every wrong answer is kept** (SOAK-08), in `soak.json` under `wrong`: `count`; `byKind`, the count per kind of request and per rule
  that failed (`partial yet nothing missing`, `partial with DRS-1003`, `complete answer differs from the reference`, `DRS-1013 no data`,
  `status 500` …, also printed in `soak-report.txt`); `all`, every one (up to 20,000) with its time, node, request and rule; and
  `samples`, the first 200 also with the status, the body's `partial` and `problems` whole (never cut), the rest of the body cut at 600
  characters, and a compact diff against the reference (up to 20 paths with both values, `rows.length` when the row counts differ).
- **While a node is down** (the shard kill and the seed kill) a history answer is complete, or a partial one whose `problems` are only
  `DRS-5052` naming the node that is down (or `DRS-1014`, a peer that did not answer in time) and whose rows are rows of the reference, or
  a refusal `502 DRS-5052` naming it, or `504 DRS-1014`. A short answer not marked partial, a partial one with another code or naming a
  node that is up, one marked partial with nothing missing, and `DRS-1013` (*no data*) at any time are wrong. A node restarted less than
  `--kill-grace` seconds ago still counts as down, since its peers may not have seen it come back.
- **Long runs.** Every `--sample-every` seconds (60) the report gets each process's resident memory, open files and threads (from
  `/proc`), and for each server its heap used, its live data after the last collection and its live threads (`/actuator/metrics`).
  The console is sampled as its whole process tree, the uvicorn parent and every worker (SOAK-07: the parent alone is one thread and
  51 MB whatever the load): resident memory, threads and open files summed, `processes`, and each worker's own figures under `workers`.
  The report (`<work>/soak.json`; the table and verdict also in `<work>/soak-report.txt`) ends with a **verdict**, which passes only when:
  no wrong answers; errors (partial, time-out, unavailable answers of the checker, failed pages, the load's failures per 10 s) only inside
  the **kill windows** (from a kill until the node has rejoined, plus `--kill-grace`); p95 and p99 per action (failing only past
  `--max-p99` when it is given); and memory not growing after warm-up: the least-squares slope of each process's samples taken
  `--warmup` seconds after it last started (live data after collection when the server reports it, else heap used, else resident memory)
  below `--max-growth` MB an hour. The slope that decides is the **steady** one (`slopeMbPerHour`): only the samples outside every kill
  window and the `--settle` seconds (600) after it, fitted with a level of its own per steady span, because a node that takes over a dead
  one's sessions steps up and stays there, which is not a leak (the 2026-10-20 soak, SOAK-04: b +166 MB/h over the whole run, flat in
  every steady span). The whole-run slope is reported beside it (`wholeRunSlopeMbPerHour`), with the spans used (`steadySpans`). The exit
  code is 0 only when the verdict passes.

A two-hour run, the shard killed at 20 minutes and the seed at 60 (each back five minutes later), with ten business days of data so a
history has rows to compare:

```bash
python3 tools/bench/net_soak.py --work ~/drishti-scale-test/net-2h --users 20 --seconds 7200 --days 10 \
    --kill-shard-at 1200 --restart-shard-at 1500 --kill-seed-at 3600 --restart-seed-at 3900 \
    --history-share 0.15 --sample-every 60 --warmup 600 --max-growth 64
```

To soak with the production settings of [PERFORMANCE.md](PERFORMANCE.md) add `--server-env MALLOC_ARENA_MAX=2
--jvm-arg=-XX:MaxDirectMemorySize=256m --jvm-arg=-XX:TrimNativeHeapInterval=60000`: `--server-env KEY=VAL` and `--jvm-arg` (after `-Xmx`)
are repeatable, reach every server, the reference too, and are recorded in `soak.json` under `serverLaunch`.

Give a new `--work` when `--dates`, `--days` or `--trades` change: the lakes are built once per folder and the soak refuses a folder
holding other dates. The checker's logic is tested without servers by `tools/bench/test_net_soak.py`.

### 17A. Documentation (part of every phase, not after it)

Each phase ends with its docs written and checked against the running code (commands and outputs captured on a fresh
stack, pictures of the real pages), as the product owner asked:

| Document | What it gets |
|---|---|
| `docs/admin/OPERATIONS.md` (new) | the operator's guide: set up a cluster of three, the `config/net/` files one by one, key rollover step by step, drain and maintenance, a node leaving, reading Admin → Net, sizing |
| `docs/tutorials/` (new tutorial) | a cluster of three on one machine, from the first seed to a console that falls over: every step with the UI, the CLI (`drishti.py net`) and the YAML |
| `docs/admin/CONFIGURATION.md` | `drishti.net.dir`, `watch`, `poll`; the console's `backend.urls`, `discover`, `discover-every`, `check-every`, `fail-after`; every file's settings |
| `docs/admin/OPERATIONS.md` | environment variables, ports, firewalls, health and metrics, `peers.json` and `servers.json`, backups |
| `docs/guides/API_GUIDE.md` | the `/api/v1/net/*` endpoints, the hop and on-behalf-of headers, `nodes` in a search answer |
| `docs/guides/CLI_GUIDE.md` | `drishti.py net list | check | add-peer | set` |
| `docs/guides/TROUBLESHOOTING.md` | a node that will not join, a refused key, a suspect that flaps, a partial search naming a node, a console that keeps failing over |
| `docs/guides/USER_GUIDE.md` | what a user sees: nothing changes, partial answers name a server, provenance for administrators |
| `docs/architecture/ARCHITECTURE.md` | the cluster in the component picture and the request path |
| `docs/qa/` (the Windows QA guide) | the cluster steps a tester walks through |
| `README.md`, Help → About, `CHANGELOG.md` | the feature and its measured results (fail-over time, soak numbers) |
| this design | each phase marked *as built*, with anything that changed from the design |

### 18. Risks and open questions

- **Decided (product owner, 2026-10-18):** (1) when nodes hold different versions of a pack, the **newest version is served**
  (view forwarding to a node that has it, even when this node could answer with its older version; equal versions with
  different content hashes are flagged on Admin → Net); (2) the cluster key **rotates without downtime** (two keys accepted
  during a rollover, section 9); (3) a node **may host a pack only for its peers** (`peer-only-packs`, section 6).
- **Latency of scatter-gather** grows with the slowest node; budgets and partial answers bound it.
- **Cache coherence:** each node caches what it fetched from peers; data versions in the digest invalidate them, a
  `knownAt` read bypasses them.
- **Decided (product owner, 2026-10-18):** (4) a search, type-ahead or forwarded request goes **only to nodes the
  user's role may reach** (section 9).

#### BI runs on the cluster (2.6.7)

A BI run ([BI_RUNS.md section 13](../architecture/BI_RUNS.md#13-the-cluster)) is named after its member (`r-<node id>-…`). A heavy run of a model goes to
the model's owner (rendezvous hashing over the members alive, `RunCluster`), sent for the caller like a forwarded view (signed, one hop,
on-behalf-of: the owner computes it with the caller's rights). A run's result, cancel and other actions go to the node that holds it
(`RunForwardFilter`); the person's run list is scattered to the members alive and merged, a node that does not answer listed in
`nodes.failed`; run events are relayed. With one identity database for the cluster, a member that dies leaves its unfinished runs to the
model's new owner once their lease lapsed (`config/runs/runs.yaml` `lease.renew`, `lease.ttl`): one conditional update decides which
node adopts each. With a database per node, a dead member's runs are "unknown" until it returns. Members are always called at the
address they advertise, never a loopback one. Tested by `RunClusterTest`.

### 19. As built

Each phase as it was built, with anything that differs from the design above. The operator's guide is [OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one).

**VaR sharding over the members (2.9.6, off by default).** The VaR engine can spread each date's rows over the members
([VAR_SHARDING.md](../architecture/VAR_SHARDING.md)): shard maps by legal entity and desk, owners by rendezvous hashing over the members that may own shards
(as `RunCluster` chooses a model's owner, with replicas, zones and a stable view), each owner building its shards from the lake. It uses
Drishti*net as it is: membership and liveness from the member table; **readiness in the capability digest**, a new optional field
`services` (name to compact text; the VaR engine publishes `var-shards`: on, owner, draining, per date the shard map and the shards ready
with their data digest), so a change raises the digest's hash, gossip carries it and peers pull it within a round; the coordinator's calls
(`POST /api/v1/var/partial`, `GET /api/v1/var/shards/skeleton`) are signed one-hop calls, a partial with the caller's on-behalf-of token so
the owner applies the caller's rights too (masks and entitlements on both sides), the endpoints refusing an unsigned call (`401
DRS-5051`). `drishti-net` itself gained only the `services` field (no Spring, no engine). The machinery is measure-agnostic
(`com.ash.drishti.compute.shard`, `com.ash.drishti.server.shard`); VaR is its first measure.

**N1, membership (built).** The core is a module of its own, `drishti-net` (no Spring, no engine: an ArchUnit rule keeps it so): `MemberTable`
(the merge rule of section 5), `Gossiper` (rounds, push-pull, direct and indirect probes, suspicion, death, forgetting, leave, rejoin, the join
list of seeds and saved members), `PeersFile`, `NodeIdentity`, `NetFolder` and `NetSchema` (the folder of section 14 with line numbers,
history and `If-Match`), `ClusterKey`, `OnBehalfOf`, `PeerHttp`. In the server, `NetService` runs the node (a virtual thread per node, a round
every `interval`), `NetCallFilter` checks the cluster key before the token filter, `NetController` and `NetAdminController` serve section 15's
membership endpoints and Admin → Net's Members and Files tabs; `drishti.py net` lists, checks and edits the files. Changes from the design:

- The signature covers the calling node's id too (`METHOD\npath?query\ntime\nnode\nsha256(body)`), sent in `X-Drishti-Net-Node`, so a
  signature cannot be replayed as another node within the window.
- The on-behalf-of token names the node it is for (`aud`); a token sent to another node is refused.
- **Key rotation takes three rolling steps, not two:** a node with only the old key cannot verify a call signed with the new one, so every node
  first accepts the new key (`secret` the old, `previous-secret` the new), then signs with it (`secret` the new, `previous-secret` the old), then
  drops the old. `previous-secret` therefore means *also accepted*, whichever key it holds.
- The management endpoints of section 13 are under `/api/v1/net` as designed; the files are `GET/PUT/DELETE /api/v1/net/file?id=<file>` and
  `POST /api/v1/net/file/check`, `GET /api/v1/net/files`, `GET /api/v1/net/file/revision`.
- Error codes `DRS-5050` (off) to `DRS-5056`.
- Measured: five nodes from one seed list each other within 5 rounds and a killed one is dead everywhere within 40 s (fake clock,
  `MembershipTest`); three real servers in one JVM list each other 52 ms after the third has started (`NetMembershipTest`).

**N2, capability digests (built).** `Digest` and `CapabilityTable` in `drishti-net`; `DigestService` in the server rebuilds this node's digest every
round from cheap reads, announces a new hash at once (a gossip round out of turn), compares the peers' hashes every 250 ms and pulls a changed
digest (`GET /api/v1/net/digest`), and swaps the capability table whole. Admin → Net's *Capability map* tab and `GET /api/v1/net/capabilities`.
Changes from the design:

- The table identity is a new, optional method of the source SPI, `SourcePlugin.tableIdentity(kind)`. The Delta connector gives the table's id
  from its first commit (`delta:<id>`), else its location, a local path qualified by the machine's name (the same path on another machine is
  another table). The `file` connector gives its folder and a SHA-256 fingerprint of the kind's files (path, size, time; `file:<folder>#<hex>`,
  taken at each rescan). Any other source says nothing and is a shard of its own node: always asked, never wrongly skipped.
- `SourcePlugin.holdsNone(kind)`: a source that surely holds nothing of the kind (Delta with no table for it, a folder with no file of it) is left
  out of the digest's sources, so it makes no node a holder (before, an empty `file` connector serving every kind made every node a shard).
- `load` is the average latency of the last full minute (`RequestActivity` keeps averages and maxima per minute, not percentiles).
- The digest's hash leaves out the load and the build time, so it changes only when what the node serves changes; a held digest is refreshed
  once a minute for the load.
- A digest also carries the node's masked fields, nodes per role and BI report ids, for the warnings and for routing BI (N6).
- Measured: a pack switched on at one of three servers is in the other two's tables 1.0 s later with the default 1 s round.

**N3, security (built).** The cluster key and the on-behalf-of token of N1, checked by `NetCallFilter`; `NetSecurityController` serves Admin →
Net's *Access* (`GET/PUT /api/v1/net/access`, written to access.yaml and node.yaml, applied live, gossiped in the digest) and *Security*
(`GET /api/v1/net/security`: the key's state, the previous key's last use per peer, mutual TLS and its certificates' expiry, refused calls). A
forwarded request is recorded in the receiving node's access log as `net:from <node> request <id>`. Changes from the design:

- **Mutual TLS comes on top of the cluster key, not in its place**: the HMAC is always required (it binds the call to its body, path, time and
  node, which a TLS session does not), and mutual TLS is the transport: `server.ssl.*` inbound, node.yaml's `tls.*` outbound.
- The key's rotation is the three steps of N1's note, measured: 13,542 signed calls among three servers through the three steps, none refused.
- The receiving node refuses an on-behalf-of token whose roles do not reach it with `403 DRS-5054`; a drained node refuses forwarded work with
  `503 DRS-5054`, so the caller tries the next peer.
- Masks twice is enforced where data crosses nodes (N4's fetch, the scattered search of N5): the reading node masks for the token's user, the
  showing node masks again.

**N4, routing (built).** `NetRouting` answers the three questions of section 7 from the capability table cut to the caller's roles and to the
member table's newest word; `ViewForwardFilter` (after the token filter) forwards a whole request (views and their sub-paths, raw documents, history,
searches, forms) to a node that renders the kind, signed, with the hop header and an on-behalf-of token, and streams the answer back unchanged;
`PeerSources` is the source router's new `Fallback`: after the local sources, the peers that hold the kind are asked through `PeerSource`
(`GET /api/v1/net/fetch/{kind}/{id}`), and the asking node renders and masks again. Peer-only packs are hidden through a predicate on `PackAccess`.
`ForwardLog` keeps the traffic and the meters; `NetHealth` the Health line and member meters; Admin → Net → *Routing* (explain, traffic). The
console's `backend.urls` (`core/cluster.py`): one server per session, health every `check_every`, discovery every `discover_every`,
`servers.json`, failover with a read retried once and a write never, recovery for new sessions after a minute. Changes from the design:

- A `PeerSource` is not registered with the source registry, so nothing that lists or scans every source (BI, caches, Health) sees a peer: the router
  asks the peers only after its local sources, through its fallback, while the cluster runs.
- Which node holds an entity cannot always be told from the digests (a store that serves every kind may hold nothing of it), so a forwarded read
  that a node answers `404` goes to the next renderer (at most three). Renderers whose sources say they hold data (a newest date, a data version, a
  live stream) are tried first.
- A kind whose pack is not on the asking node is not refused there by role: the node that renders it knows the pack's roles and decides.
- A digest of a member that left or died is kept (never routed to) until the member is forgotten, so an answer can name the node that holds the
  data and is down (`502 DRS-5052`).
- A console live stream that opened and then broke (a restart, an idle or proxy timeout) is not counted toward `fail_after`; it starts a health
  check, which counts as usual when it fails. A session moved because its server restarted (streams broke, then requests failed) returns to that
  server once it is usable again (healthy for a minute); other moved sessions stay where they are.
- The console's settings are `backend.urls`, `discover`, `discover_every`, `check_every`, `fail_after` (the console's own style, with underscores) and
  `backend.data_dir`; a failover is reported to the server it moved to (`POST /api/v1/net/console-event`, audited `net-console-failover`).
- `routing.yaml` also has `user-state` (N7). The administrators' line in *How this view was built* naming the node is built since the
  follow-up below (*The node that answered*).
- A view's linked entities have two budgets (after the soak, where links held on a peer showed *pending*): `drishti.graph.link-budget` (40 ms)
  for this node's own sources, and routing.yaml's `link-budget` (1 s from the read's start) once the read has gone on to the peers
  (`SourceRouter.Fallback.budget`, asked once per kind of a `fetchAll`; a read that has not reached the peers by the local budget is still cut
  there). The digests alone could say only "held on C" without the entity's badge or a panel's data, so the peers are asked, all links side by side.

**N5, scatter-gather (built).** `SearchScatter` completes a search answered here with the shares of the peers `CapabilityTable.scatterTargets`
names (shards, never a replica of a node already asked; the fewest nodes that cover every table, fastest first), asked in parallel through
`GET /api/v1/net/search` within `scatter-budget`, and merges them (rows by kind and id, this node's first; re-sorted by the order-by value or the id;
cut to the limit; sums; `failed`, `partial`, and `nodes` for administrators). A search of a kind whose pack is not here is scattered by the forwarding
filter and merged on the asking node. The type-ahead asks every reachable node within `suggest-budget` and interleaves the answers. A map panel's listing of a kind (which countries have a page, which entities shade it) asks the same scatter targets for the caller (`GET /api/v1/net/list/{kind}`, each node its own sources) and interleaves them with this node's, so a node that reads the kind only from a peer draws the same map (QA4-02); the documents then come through the peers as a view's links do, keyed per caller signature. Measured on trades
split three ways with one shared: every node answers the same union, the shared trade once, ordered and cut exactly as the sorted union. Changes from
the design:

- A node that holds data of the kind but is down (suspect, dead, left) is named in `failed` as `not asked: it cannot be reached now`, so a search with
  a node down is partial rather than silently smaller.
- A duplicate keeps the first answer (this node's, then the peers' in the order asked), not necessarily the one whose coverage is `HELD`.
- `matched` and `scanned` count an entity two nodes matched once: each share lists its matches' ids (`matchedIds`, up to 20,000; above that the
  overlap is the rows both returned), removed from the answer the client gets.
- Grouped pages are gathered across nodes since the follow-up below (*Grouped pages across nodes*).

**N6, live relay and BI (built).** `PeerSource.subscribe` opens one stream from the peer that holds the entity
(`GET /api/v1/net/subscribe/{kind}/{id}`: the peer's own topic, masked for the token's user) and feeds this node's topic; the router asks its fallback
for a live peer when no local source holds or pushes the entity. BI: `ViewForwardFilter` routes `/api/v1/bi/reports/{id}/**` with
`NetRouting.report`: answered here when the report's datasets' kinds (`BiReportService.kinds`) are held here, else forwarded to a node that lists the
report in its digest (its shipped reports) and holds them. Changes from the design:

- **The topic hub is partitioned by the subscriber's roles while the cluster runs** (one upstream per entity and role set, not per entity): an upstream
  from a peer is masked there for the user it was opened for, and sharing it with viewers of other roles could show one of them what the peer would
  mask for them. Viewers with the same roles still share one upstream.
- A relayed stream checks its on-behalf-of token when it opens; it is opened again (with a fresh token) when it breaks.
- A BI report goes to the node that holds every kind its datasets read; a model spanning nodes is not joined across them.

**N7, user state (built).** `ReplicatedPreferences` (the per-user store, decorated by a bean post-processor) tells `StateSync` of every write and
delete in the namespaces `settings` (saved searches among them), `layouts`, `pivots` and `workspaces`; `StateSync` sends them to every routable peer
(`POST /api/v1/net/state`, batches of 200, a quarter of a second, up to 10,000 waiting per peer), applies a peer's changes to the store underneath (the
newest write wins), and on a member's join queues every such document it holds as a catch-up (applied only where absent). Changes from the design:

- Replication is built in this release (the design put it in phase 7 after a first release with home nodes only); `user-state: off` keeps state
  where it was written, for a cluster with one shared identity database.
- Alerts, monitors, scheduled reports and measure alerts are not copied: they act, and each node would act on its copy.
- The last-writer-wins clock is each node's wall clock (the nodes are within 60 s of each other, since the cluster key refuses more); the times of
  writes are kept in memory, so a catch-up after a restart fills only what is absent and never overwrites.
- A user deleted on one node is deleted on the others (with their tokens, settings and sessions), by the identity copy below.
- A change is queued for every member that is not gone (`alive` or `suspect`, drained ones included, speaking the protocol) and sent once
  the member is alive. A member suspected for a moment and heard again never joins again, so it gets no catch-up: before 2.9.6 the changes
  were queued for routable peers only, and one made while a peer was suspected (a busy server's pause) never reached it. The drill saw a
  node keep the seeded administrator enabled after another node disabled it (`NetLastAdminTest`, now reproducing it by marking the peer
  suspect at the moment of the change). A member that goes dead drops its queue and gets the catch-up when it comes back.

**Identity across nodes (built, after N7).** A user known to one node used to be refused by the others (`registered-users-only`), so a console
that fell over from the node where the user was created signed them out. `IdentitySync`, a `StateSync.Part`, copies the identity over the same
signed batches, in the namespaces `identity-users` (the `User` record with its password hash; `JpaUserStore` tells it of every write and delete),
`identity-roles` (the roles of `RoleStore`, written on the peer with their own time and author by `RoleStore.adopt`) and `identity-tokens` (the
`ApiTokenStore.Stored` record: the secret's SHA-256, never the secret; a revocation is never undone). A change applied from a peer is written under
a thread-local flag, so the stores' listeners do not send it back. Rules:

- One user (same creation time): the newer change wins (a catch-up: the record with the later times: updated, signed in, password changed).
- Two users of one name made apart (different creation times): the older stands, so a new node's seeded administrator (made at its first start)
  never replaces the cluster's administrator and its default password.
- A deletion at T is a tombstone (`<data dir>/net/identity-deleted.json`, kept 90 days, sent first in a catch-up): every record of that name
  created at or before T is refused everywhere (a node that had not heard of it may send one, even with a later sign-in), a user created after T
  is a new user. Applying a user's deletion forgets their pack cache, settings, tokens, sessions, designs, alerts and inbox, as a deletion on the
  node itself does.
- Applied changes are audited with `net:<origin node>` as the actor; a sign-in time alone is copied but not audited.
- The last-enabled-administrator rule is checked on the node where a change is made, and **again where it is applied** (2.6.0): two
  administrators on two nodes may each disable, demote or delete one of the last two administrators at the same moment, each allowed where it
  is made. A peer's user change that would leave the node with no enabled administrator is repaired as it is applied (one user change at a
  time, under a lock): the disabled administrator enabled most recently (the newest `updatedAt`, then the name) is enabled again, so every
  node that has seen both changes picks the same one and their repairs (changes made there, sent to every peer) agree; when no disabled
  administrator is left (a demotion), the one the change took away keeps the role; a deletion of the last one is refused and the user kept
  as a new user (created now, after the tombstone, so no node refuses them). Each repair is audited (`last-admin-kept`, actor
  `net:<this node>`) and listed on Admin → Health under Drishti*net (`NetHealth.summary().warnings`). The cluster never ends with no
  enabled administrator.

`NetIdentityTest` (three servers in one JVM, security on): a user and a role made on A, the user signed in through the console's calls on A, A
stopped: the console's session check and the user's token and API token are accepted on B; a user disabled and another deleted on B while A was
down are disabled and deleted on A when it comes back, and A's stale copies bring neither back on B; a node joining later gets the users, the role
and the deletion, and the cluster's administrator stands over its seeded one. `NetLastAdminTest` (three servers, their copying held while two
of them change at once): A disables ada while B disables bob, the last two; when the changes cross, every node keeps bob (disabled last)
enabled, the repair is audited and on Admin → Health; two deletions at once keep an administrator on every node, the same on all.

**Grouped pages across nodes (built, after N7).** A page of a derived kind is gathered with the scatter-gather of a search: the router's
fallback (`SourceRouter.Fallback.gather`) hands the local page, or none, to `GroupGather`, which asks the search's targets for the kind the page
groups (`SearchScatter.targets`: the nodes the caller's roles reach, no replica of a node already asked) for their share
(`GET /api/v1/net/group/{kind}/{id}`, `DerivedSourcePlugin.share`: the node's page and, when it lists every member, each member's values by field
name, masked there by name), and merges the shares with this node's own (`GroupShares`). Measured on three real servers in one JVM with a book's
trades split three ways and one trade on two nodes: every aggregate (count, sum, min, max, avg, distinct, first), the members and the rows equal
what one server holding every trade builds; a field C masks is masked on B, a field B masks is masked there; a role limited to two nodes gathers
from those two; with C stopped the page is partial and names C. Changes from the design:

- Not "concatenated in the page's order": a page carries aggregates, so the shares are merged as one node would aggregate; exact (members united
  by id) when every node lists all its members of the group (up to `max-members`), combined from each node's totals otherwise (a member two
  nodes hold is then counted on each; documented in OPERATIONS.md).
- A partial page says so in its document (`partial`, `missing` naming the node), as a search does in its answer.

**The node that answered (built, after N7).** For an administrator, a view's *How this view was built* panel has a line **Node** naming the node
that built the view and how: `local`, `built here, data from <node>` (a document a peer read, N4), `forwarded from <node>` (the whole request
forwarded here, said by the node that rendered it) or `scatter-gather from <nodes>` with the nodes missing (a grouped page). `NetServedBy` adds
it to the view (`GET /api/v1/views/...`) and to the frames of its live stream; what the request asked of other nodes is noted on the request's
caller (`NetCaller.Served`) by the peer sources and the gathering of a grouped page. Everyone else's view is unchanged, so a user still cannot
tell which server answered; with Drishti*net off nothing is added. Tested on three real servers: forwarded, local, data and scatter-gather
lines for an administrator, none for a user. The finance pack's book P&L Sutra gained its *How this view was built* panel.

## 2. Drishti on AWS: ECS, EFS and S3

Status: **scripts in `deploy/`.** The images and entry points of sections 5 and 6 are files in
[`deploy/docker/ecs/`](../../deploy/docker/ecs); the EFS access points, Cloud Map, task and service definitions of
sections 4, 9, 12 and 13 are scripts and templates in [`deploy/aws/ecs/`](../../deploy/aws/ecs); the lake in S3 (buckets,
roles, loading, maintenance, tiers, alarms) is [section 3](#3-a-high-performance-delta-lake-on-aws-past-200-tb) and
[`deploy/aws/delta-lake/`](../../deploy/aws/delta-lake). Every script has `--help` and `--dry-run` and is tested that way;
none has yet run against a real AWS account (step A3 below). The server's two small changes of section 5.3 are not built.

Related: [section 5](#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built) (500 GB a day for seven years on this deployment),
[section 1](#1-drishtinet-many-servers-one-drishti) and [OPERATIONS.md](OPERATIONS.md#18-drishtinet-running-several-servers-as-one) (several servers), [OPERATIONS.md](OPERATIONS.md)
(running one server), [OPERATIONALISING.md section 13](../guides/OPERATIONALISING.md#13-docker-and-kubernetes) (Docker
and Kubernetes today, `deploy/docker/server.Dockerfile` and `deploy/docker/console.Dockerfile`),
[section 3](#3-a-high-performance-delta-lake-on-aws-past-200-tb) (the lake in S3, past 200 TB: layout, loading, maintenance, tiers, alarms).

### 1. The ask and the rule

The product owner (2026-10-20): Drishti runs in AWS on **ECS**, with **S3** and **EFS**; the server and the console are
**separate containers**. The rule:

> **Whatever path a container uses must have a mapped path on EFS, identical, so that everything is durable.**

The design turns the rule into something that cannot be broken by accident:

| Mechanism | What it guarantees |
|---|---|
| Every path a container writes is an **EFS access point mounted at the same path** (section 3) | a task that stops, restarts, moves to another Availability Zone or is replaced by a new image comes back to exactly the files it left |
| The container's **root file system is read-only** (`readonlyRootFilesystem: true`) | a write anywhere else fails at once, naming the path, instead of being silently lost when the task stops |
| `/tmp`, `HOME`, the JVM's temp folder, crash files and heap dumps are **pointed at EFS** (section 5) | the usual "hidden" writers write durably too |
| The **entry point refuses to start** if a writable path is not mounted, not writable, or not on NFS (section 7) | a task definition with a missing or wrong mount never runs |
| The **image** holds only what never changes while it runs: the server jar, the console's code, the packs Drishti ships, the documents for the help centre | an image is a version, kept in Amazon ECR; it is not data |
| **S3 holds the lakes**, read directly by the connectors | the business data needs no file system at all |

### 2. The picture

```
                                   users (browsers)
                                          │ HTTPS 443
                                          ▼
        ┌──────────────────── Application Load Balancer (public or internal subnets) ────────────────────┐
        │  ACM certificate · listener 443 → target group "consoles" (port 17480, health /healthz)         │
        └───────────────┬───────────────────────────────┬───────────────────────────────┬─────────────────┘
                        │                               │                               │
   ┌─ ECS service drishti-a (1 task) ─┐  ┌─ ECS service drishti-b ─────────┐  ┌─ ECS service drishti-c ─────────┐
   │ task, awsvpc, 10.0.1.23          │  │ task, 10.0.2.41                 │  │ task, 10.0.3.17                 │
   │ ┌──────────┐    ┌─────────────┐  │  │ ┌──────────┐   ┌─────────────┐  │  │ ┌──────────┐   ┌─────────────┐  │
   │ │ console  │───►│   server    │  │  │ │ console  │──►│   server    │  │  │ │ console  │──►│   server    │  │
   │ │  :17480  │    │   :18480    │◄─┼──┼─┼──────────┼──►│   :18480    │◄─┼──┼─┼──────────┼──►│   :18480    │  │
   │ └────┬─────┘    └──────┬──────┘  │  │ └────┬─────┘   └──────┬──────┘  │  │ └────┬─────┘   └──────┬──────┘  │
   │      └──── same EFS paths ───┘   │  │      └── same EFS paths ──┘     │  │      └── same EFS paths ──┘     │
   └──────────────┬───────────────────┘  └──────────────┬──────────────────┘  └──────────────┬──────────────────┘
                  │        Drishti*net gossip and forwarding between servers, task addresses, port 18480       │
                  ▼                                     ▼                                    ▼
   ┌──────────────────────────────── Amazon EFS (Regional, encrypted, Elastic throughput) ───────────────────────┐
   │ /drishti/prod/drishti-a/opt/drishti/{data,config,sutras}   /drishti/prod/drishti-b/…   /drishti/prod/drishti-c/… │
   └──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                  │  task role, gateway VPC endpoint
                  ▼
   ┌── Amazon S3 ── lakes (Delta Lake, Iceberg, files) ──┐   ┌── Secrets Manager ── token, session, cluster, admin ──┐
   └──────────────────────────────────────────────────────┘   └───────────────────────────────────────────────────────┘
   ┌── AWS Cloud Map ── drishti-a.drishti.local … ──┐   ┌── CloudWatch ── logs, metrics, alarms ──┐   ┌── AWS Backup ── EFS ──┐
```

**One ECS service per Drishti node.** Each service runs exactly one task. The task has **two containers, the server and
the console**, sharing the task's network address and mounting the same EFS folders at the same paths. A node has a
fixed name (`drishti-a`), its own EFS folders and its own place on the Drishti*net ring, so it must be a service of its
own: an ECS service with several tasks gives them no stable identity.

**Why the console sits beside its server** (not in a service of its own): the console reads the same packs, lakes
settings and folders as its server, through the same paths, exactly as on one machine. At the scale of
[section 5](#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built) consoles become a service of their own (section 21).

### 3. The path map

#### 3.1 The rule, made concrete

Every writable path is the **same in the container and on EFS**. On EFS each node's paths sit under the node's prefix
`/drishti/<environment>/<node>`: several environments and nodes share one file system, and each node must have its own
files (a RocksDB store or a DuckDB file is opened by one process at a time). Below the prefix the path is identical:

```
  container of node drishti-a (prod)                EFS file system fs-…
  ──────────────────────────────────                ───────────────────────────────────────────────
  /opt/drishti/data          ◄── access point ──►   /drishti/prod/drishti-a/opt/drishti/data
  /opt/drishti/config        ◄── access point ──►   /drishti/prod/drishti-a/opt/drishti/config
  /opt/drishti/sutras        ◄── access point ──►   /drishti/prod/drishti-a/opt/drishti/sutras

  /opt/drishti/app.jar, /opt/drishti/shipped/packs, /opt/drishti/console, /opt/drishti/docs, /opt/drishti/bin
                             ── image (ECR), read-only; never written
```

#### 3.2 Every folder, and who writes it

| Container path | Written by | Content |
|---|---|---|
| `/opt/drishti/data/identity` | server | users, roles, API tokens, tombstones (replicated between nodes by IdentitySync) |
| `/opt/drishti/data/governance` | server | certification, lineage, approvals |
| `/opt/drishti/data/bi-store` | server | BI columns and rollups (Parquet, DuckDB) |
| `/opt/drishti/data/reports` | server | report files, subscriptions' deliveries |
| `/opt/drishti/data/collab` | server | collaboration store and exports |
| `/opt/drishti/data/loads` | server | load history per pack, expectation overrides |
| `/opt/drishti/data/packs/installed` | server | packs deployed to this node |
| `/opt/drishti/data/uninstalled` | server | uninstalled artifacts (moved, never deleted) |
| `/opt/drishti/data/derived` | server | grouped pages (RocksDB sorted stores) |
| `/opt/drishti/data/cache` | server | document cache (RocksDB), warm column files, type-ahead segments |
| `/opt/drishti/data/designs` | server | Sutra designs |
| `/opt/drishti/data/dev-sutras` | server | bound development Sutras, one folder per user |
| `/opt/drishti/data/net` | server | Drishti*net state |
| `/opt/drishti/data/lakes` | console | lake maintenance settings and history |
| `/opt/drishti/data/console` | console | what the console keeps between runs (`net/servers.json`) |
| `/opt/drishti/data/tmp/server`, `/opt/drishti/data/tmp/console` | both | temp folders (the JVM's `java.io.tmpdir`, native libraries unpacked by DuckDB and RocksDB, Python's `TMPDIR`) |
| `/opt/drishti/data/home/server`, `/opt/drishti/data/home/console` | both | `HOME` (DuckDB extensions, font caches and any library that writes under home) |
| `/opt/drishti/data/logs` | server | log files, `hs_err_pid*.log`, heap dumps |
| `/opt/drishti/config/connectors` | server (Admin → Connectors) | connector files |
| `/opt/drishti/config/net` | server, entry point | `node.yaml`, `gossip.yaml`, `routing.yaml`, `peers/` |
| `/opt/drishti/config/ai`, `/opt/drishti/config/mcp` | both | AI providers, MCP servers |
| `/opt/drishti/config/bi` | server | Rupaka files: datasets, reports, boards, notebooks |
| `/opt/drishti/sutras` | server (going live) | site Sutras |

A folder the code adds later is under one of the three roots by construction (every Drishti path setting defaults to
`${drishti.data.dir}` or `./config`), so the map does not need to grow; the read-only-root test (section 19) catches
any exception.

#### 3.3 The variables that pin every path

Set in both images, so nothing depends on a working directory:

| Variable | Value | Image |
|---|---|---|
| `DRISHTI_DATA_DIR` | `/opt/drishti/data` | both |
| `DRISHTI_CONNECTORS_DIR` | `/opt/drishti/config/connectors` | server |
| `DRISHTI_NET_DIR` | `/opt/drishti/config/net` | server |
| `DRISHTI_AI_DIR`, `DRISHTI_MCP_DIR` | `/opt/drishti/config/ai`, `/opt/drishti/config/mcp` | both |
| `DRISHTI_BI_DIR` | `/opt/drishti/config/bi` | server |
| `DRISHTI_SUTRAS` | `/opt/drishti/sutras` | server |
| `DRISHTI_PACKS_DIR` | `/opt/drishti/shipped/packs` (read-only, from the image) | both |
| `DRISHTI_CONSOLE_DATA_DIR` | `/opt/drishti/data/console` | console |
| `DRISHTI_CONSOLE_TMP_DIR` | `/opt/drishti/data/tmp/console` | console |
| `TMPDIR` | `/opt/drishti/data/tmp/server` or `/opt/drishti/data/tmp/console` | both |
| `HOME` | `/opt/drishti/data/home/server` or `/opt/drishti/data/home/console` | both |

**Shipped packs move out of `config`.** Today's images copy the packs to `/opt/drishti/config/packs`; an EFS mount on
`/opt/drishti/config` would hide them. The ECS images copy them to `/opt/drishti/shipped/packs` and point
`DRISHTI_PACKS_DIR` there. Installed packs (Admin → Packs, `pack deploy`) go to `data/packs/installed` on EFS and are
searched first, as today.

### 4. EFS

#### 4.1 The file system

| Setting | Value | Why |
|---|---|---|
| Type | **Regional** (Multi-AZ) | a task restarted in another Availability Zone finds its files |
| Performance mode | General Purpose | lowest latency per operation |
| Throughput mode | **Elastic** | throughput follows the load (start-up warm-ups, BI builds) with no provisioning |
| Encryption at rest | on, with a customer-managed KMS key `alias/drishti-efs` | bank policy |
| Encryption in transit | TLS on every mount (`transitEncryption: ENABLED`) | |
| Lifecycle (Infrequent Access) | **off** | caches are read rarely but must be fast when they are |
| Mount targets | one per private subnet (one per Availability Zone), security group `drishti-efs` | section 10 |
| File system policy | deny any access without TLS; allow mounts only through the access points, only to the task role | section 11 |
| Backups | AWS Backup plan `drishti-efs-daily` | section 14.5 |
| Replication | EFS replication to the recovery Region | section 16 |

#### 4.2 Access points

`deploy/aws/ecs/setup-efs.sh` creates them (and one `data` access point each for the lake's `loader` and
`maintenance` tasks).

One access point per node per path (three per node). Each roots its path and enforces the user the containers run as:

| Access point (name tag) | Root directory | POSIX user | Root directory creation |
|---|---|---|---|
| `drishti-prod-drishti-a-data` | `/drishti/prod/drishti-a/opt/drishti/data` | uid 10001, gid 10001 | owner 10001:10001, permissions 0750 |
| `drishti-prod-drishti-a-config` | `/drishti/prod/drishti-a/opt/drishti/config` | uid 10001, gid 10001 | owner 10001:10001, permissions 0750 |
| `drishti-prod-drishti-a-sutras` | `/drishti/prod/drishti-a/opt/drishti/sutras` | uid 10001, gid 10001 | owner 10001:10001, permissions 0750 |
| … the same three for `drishti-b`, `drishti-c`, … | | | |

Both images run as uid 10001 (`drishti`), so the server and the console of a node read and write the same files, and
the access point enforces that identity whatever the image says.

#### 4.3 The tree on EFS

```
/drishti/
├── prod/
│   ├── drishti-a/opt/drishti/
│   │   ├── data/      identity/  governance/  bi-store/  reports/  collab/  loads/  packs/installed/  derived/
│   │   │              cache/  designs/  dev-sutras/  net/  lakes/  console/  tmp/{server,console}/  home/  logs/
│   │   ├── config/    connectors/  net/{node.yaml,gossip.yaml,routing.yaml,peers/}  ai/  mcp/  bi/
│   │   └── sutras/
│   ├── drishti-b/opt/drishti/…
│   └── drishti-c/opt/drishti/…
└── uat/
    └── drishti-a/opt/drishti/…
```

#### 4.4 One node, one writer

Two tasks on one node's folders would open the same RocksDB stores and DuckDB files. Three guards:

1. The service allows at most one running task (`maximumPercent: 100`, `minimumHealthyPercent: 0`, section 13): ECS
   stops the old task before it starts the new one.
2. Drishti's own lock checks: `SortedStore.inUse` takes the lock of a RocksDB store's `LOCK` file (EFS supports NFSv4.1
   byte-range locks), and a second process refuses a store in use.
3. The entry point writes `data/net/task.lock` with the task's ARN and refuses to start while another task's file is
   fresh (younger than two gossip `dead-after` periods): a hand-started duplicate stops at once.

### 5. The server container

#### 5.1 Dockerfile and entry point

[`deploy/docker/ecs/server.Dockerfile`](../../deploy/docker/ecs/server.Dockerfile) and
[`deploy/docker/ecs/server-entrypoint.sh`](../../deploy/docker/ecs/server-entrypoint.sh). Build from the repository root
after `./mvnw -q package -DskipTests`:

```bash
docker build -f deploy/docker/ecs/server.Dockerfile -t drishti-server-ecs:2.9.2 .     # Java 25: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
```

The image: the server jar, the shipped packs in `/opt/drishti/shipped/packs` (outside the EFS paths, so a mount never hides
them), the entry point, mount points owned by uid 10001, and the variables of section 3.3. Before Java starts, the entry
point:

1. checks that `/opt/drishti/data`, `/config` and `/sutras` are writable NFS mounts (exit 78 otherwise;
   `DRISHTI_REQUIRE_EFS=false` only for a local check of the image);
2. refuses to start while another task's `data/net/task.lock` is fresh (one task per node);
3. finds the task's real IPv4 address (never `127.*`, `0.0.0.0` or `localhost`);
4. on the node's first start only, writes `config/net/node.yaml` (`advertise-url: http://${DRISHTI_NODE_IP}:18480`,
   `secret: ${DRISHTI_NET_SECRET}`, both resolved by the server at every start) and a peer file per seed of
   `DRISHTI_NET_SEEDS`;
5. starts Java with ZGC, `-XX:-UsePerfData`, the temp folder, home, crash files and heap dumps on EFS
   (`-XX:+UseCompactObjectHeaders` on Java 25).

The task's ARN comes from the ECS task metadata endpoint (`ECS_CONTAINER_METADATA_URI_V4`, set by ECS in every
container); outside ECS the container's host name stands in.

#### 5.3 What the server needs from the code (small changes when built)

| Change | Why |
|---|---|
| Touch `data/net/task.lock` every gossip interval | the entry point's duplicate check (section 4.4) |
| Accept a peer `url` whose host is a DNS name that resolves to a real address | seeds by Cloud Map names (section 9); refused today only if it resolves to loopback or the wildcard |
| Nothing else | every path is already a setting; the real-address rule of 2.6.0 is already in the server |

#### 5.4 Sizing, health and logs

| | Value |
|---|---|
| CPU, memory | 4 vCPU, 12 GB (8 GB heap; RocksDB, DuckDB, Arrow and ZGC off the heap) |
| Health check | `/actuator/health/readiness` on the task's own address, every 15 s, 4 retries, 180 s start period (caches warm in the background; readiness says when the node answers) |
| Logs | standard output to CloudWatch Logs (`awslogs`, group `/drishti/<env>`, stream prefix the node id); files the server writes stay in `data/logs` on EFS |
| Stop | ECS sends SIGTERM; Spring Boot's graceful shutdown finishes requests in flight (`stopTimeout: 60`) |

### 6. The console container

#### 6.1 Dockerfile and entry point

[`deploy/docker/ecs/console.Dockerfile`](../../deploy/docker/ecs/console.Dockerfile) and
[`deploy/docker/ecs/console-entrypoint.sh`](../../deploy/docker/ecs/console-entrypoint.sh):

```bash
docker build -f deploy/docker/ecs/console.Dockerfile -t drishti-console-ecs:2.9.2 .
```

Calc's Python runtime (Pyodide) and DuckDB-Wasm are fetched in a first stage (pinned, SHA-256 checked), so a running
console needs no internet; the Python code is compiled at build time, so the read-only root never needs `__pycache__`.
The entry point checks the three mounts as the server's does, finds the task's real address and, unless
`DRISHTI_BACKEND_URL` is set (a console service of its own names the seeds' Cloud Map names), reaches the server of its
own task at `http://<task IP>:18480`.

The console **listens** on every interface of its task (`0.0.0.0`, so the load balancer reaches it); it never
**advertises** an address: the real-address rule of Drishti*net concerns what members advertise.

#### 6.3 Sizing, sessions and health

| | Value |
|---|---|
| CPU, memory | 2 vCPU, 4 GB; `DRISHTI_CONSOLE_WORKERS=4` (one process per core serves about 40 pages a second, *measured*) |
| Starts after | the server of its task is healthy (`dependsOn … HEALTHY`) |
| Other servers | discovered from its server (`DRISHTI_BACKEND_DISCOVER`, on by default); a session falls over to another server when its own does not answer |
| Sessions | signed cookies (`DRISHTI_SESSION_SECRET`, the same in every task), so any console accepts any session; load balancer stickiness stays on so a user's live pages keep to one task |
| Health check | `/healthz`, by the load balancer's target group and the task definition |
| Logs | standard output to CloudWatch Logs |

### 7. Starting a node

```
 ECS                         server container                                    console container
  │ start task                 │                                                   │
  ├─ mount EFS access points ──┤ (data, config, sutras at /opt/drishti/…)          │
  ├─ inject secrets ───────────┤                                                   │
  │                            ├─ check the three mounts: writable, NFS  ──fail──► exit 78, task stops, ECS retries
  │                            ├─ task.lock: another task alive?          ──yes──► exit 78
  │                            ├─ real IPv4 of the task (never 127.*, 0.0.0.0)
  │                            ├─ first start? write config/net/node.yaml + peers/<seed>.yaml
  │                            ├─ java -Xmx8g … -jar app.jar
  │                            │     ├─ gossip: join through the seeds, advertise http://<task IP>:18480
  │                            │     ├─ IdentitySync and StateSync catch up from peers
  │                            │     └─ caches warm in the background
  │                            ├─ readiness UP ─────────────────────────────────►  │ (dependsOn HEALTHY)
  │                            │                                                   ├─ same mount checks, real IPv4
  │                            │                                                   ├─ backend = http://<task IP>:18480
  │                            │                                                   ├─ python run_drishti_web.py
  ├─ register in Cloud Map (drishti-a.drishti.local → task IP)                    │
  └─ target group: console healthy (/healthz) ◄────────────────────────────────────┘ users reach it
```

### 8. S3

#### 8.1 Buckets

The buckets (`<org>-drishti-<env>-lake`, `-landing` and `-logs`), their protections, retention and lifecycle are described once, in
[section 3, its section 5.1](#51-buckets), as `deploy/aws/delta-lake/setup-buckets.sh` creates them. A site that also
keeps pack bundles and release artifacts in S3 adds its own `<org>-drishti-<env>-artifacts` bucket (versioning on, SSE-KMS); no script
creates it.

#### 8.2 How Drishti reads S3

The prefixes and tables under the lake bucket: [section 3, its section 5.2](#52-prefixes-and-tables).

- The Delta Lake native engine, Iceberg and the S3 connector take `s3://` roots in their connector files
  (`/opt/drishti/config/connectors/*.yaml`, on EFS).
- Credentials come from the **task role** through the AWS default chain: no access keys in files, variables or EFS.
- A **gateway VPC endpoint for S3** keeps the traffic inside AWS (no NAT gateway charges); the lake bucket's policy
  allows only that endpoint and the Drishti roles.

```yaml
# /opt/drishti/config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings:
  root: s3://acme-drishti-prod-lake/lake        # DRISHTI_DELTA_ROOT: s3://LAKE_BUCKET/LAKE_PREFIX
  domain: trading
  union: true
  table: {trade: trade_endur}                  # read from s3://acme-drishti-prod-lake/lake/trading/trade_endur
  engine: native
  s3.region: eu-west-2
```

#### 8.3 Bucket policy (lake)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "OnlyThroughTheVpcEndpoint", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::acme-drishti-prod-lake", "arn:aws:s3:::acme-drishti-prod-lake/*"],
      "Condition": { "StringNotEquals": { "aws:sourceVpce": "vpce-…" } } },
    { "Sid": "TlsOnly", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::acme-drishti-prod-lake", "arn:aws:s3:::acme-drishti-prod-lake/*"],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } } }
  ]
}
```

#### 8.4 What is not in S3

Anything the server or the console writes while running: by the rule it is on EFS. Loading the lakes (writing to S3) is
done by loader tasks with their own role ([section 3, its section 8](#8-loading-the-daily-load-backfill-and-restatement), built;
[section 5, its section 7](#7-loading-500-gb-a-day), the planned fleet); the
servers' role reads only.

### 9. Drishti*net on ECS

`deploy/aws/ecs/setup-cloud-map.sh` creates the namespace and a service per node; `register.sh` sets each server's
`DRISHTI_NET_SEEDS` from `DRISHTI_NET_SEED_NODES`.

```
   Cloud Map namespace drishti.local (private DNS)
     drishti-a.drishti.local  A  10.0.1.23     ◄── registered by ECS when drishti-a's task is healthy
     drishti-b.drishti.local  A  10.0.2.41
     drishti-c.drishti.local  A  10.0.3.17

   drishti-c starts (first time):
     DRISHTI_NET_SEEDS = http://drishti-a.drishti.local:18480,http://drishti-b.drishti.local:18480
       └─► entry point writes config/net/peers/drishti-a.yaml and drishti-b.yaml (url: http://drishti-a.drishti.local:18480)
     server resolves drishti-a.drishti.local → 10.0.1.23 (a real address; loopback would be refused)
       └─► gossips with drishti-a, advertising http://10.0.3.17:18480 (its own task address)
     every member now lists drishti-c at 10.0.3.17; the console of any node discovers it
```

- **Real addresses only.** With `awsvpc` networking each task has its own address in the VPC. The server advertises
  `http://<that address>:18480`; `node.yaml` says `advertise-url: http://${DRISHTI_NODE_IP}:18480`, resolved at every
  start, so a task that comes back with a new address advertises the new one. Never loopback, never `0.0.0.0`.
- **Seeds by name.** Peer files name seeds by their Cloud Map names, which resolve to task addresses; what members
  advertise stays an IP.
- **The cluster secret** (`DRISHTI_NET_SECRET`) comes from Secrets Manager; `node.yaml` names it, so it never lands on
  EFS.
- **Users, roles and API tokens** replicate between nodes as today (IdentitySync); each node also keeps its copy on its
  own EFS folders, so a whole-cluster restart loses nothing.
- **A node's identity is its service**: node id `drishti-a`, its access points, its Cloud Map name. The ring position
  follows the node id, so a task that restarts keeps its share.

### 10. Network

```
 VPC 10.0.0.0/16 (three Availability Zones)
 ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
 │ public or internal subnets:   ALB (sg drishti-alb)                                             │
 │ private subnets (one per AZ): ECS tasks (sg drishti-node) · EFS mount targets (sg drishti-efs) │
 │ endpoints: S3 (gateway) · Secrets Manager, ECR (api, dkr), CloudWatch Logs, STS (interface)    │
 │ no internet route from the private subnets (a NAT gateway only if a connector needs the internet,│
 │ such as news or market data; otherwise none)                                                    │
 └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Security group | Inbound | Outbound |
|---|---|---|
| `drishti-alb` | 443 from the users' networks | 17480 to `drishti-node` |
| `drishti-node` | 17480 from `drishti-alb`; 18480 from `drishti-node` (gossip, forwarding, consoles of other nodes) | 18480 to `drishti-node`; 2049 to `drishti-efs`; 443 to the endpoints |
| `drishti-efs` | 2049 from `drishti-node` | none |

- Only the load balancer is reachable from users; servers are never exposed.
- **TLS** at the load balancer with an ACM certificate; between the load balancer and the consoles, and between
  servers, traffic stays in the VPC on private subnets. A bank that requires TLS inside the VPC as well runs the
  servers with their own certificate (Drishti's TLS settings) and `https://` advertise URLs.
- **Sign-in**: the console's own sign-in, or OIDC as today (the load balancer can also authenticate with OIDC before
  the console).

### 11. Identity, roles and secrets

#### 11.1 Task role (what the containers may do)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "ReadTheLake", "Effect": "Allow", "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::acme-drishti-prod-lake", "arn:aws:s3:::acme-drishti-prod-lake/*"] },
    { "Sid": "DecryptTheLake", "Effect": "Allow", "Action": ["kms:Decrypt"],
      "Resource": "arn:aws:kms:…:key/<drishti-lake key>" },
    { "Sid": "MountThisNodesFolders", "Effect": "Allow",
      "Action": ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"],
      "Resource": "arn:aws:elasticfilesystem:…:file-system/fs-…",
      "Condition": { "StringEquals": { "elasticfilesystem:AccessPointArn": [
        "arn:aws:elasticfilesystem:…:access-point/fsap-…data",
        "arn:aws:elasticfilesystem:…:access-point/fsap-…config",
        "arn:aws:elasticfilesystem:…:access-point/fsap-…sutras" ] } } }
  ]
}
```

One task role per node (`drishti-prod-drishti-a-task`) limits each node to its own access points. The scripts create
one shared role, `drishti-<env>-server-task` (`deploy/aws/delta-lake/setup-iam.sh`), allowed any access point of the file
system; a role per node is a tightening left to the platform team's template.

#### 11.2 Execution role (what ECS does for the task)

Pull the images from ECR, write the log streams, and read the node's secrets:
`ecr:GetAuthorizationToken`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`, `logs:CreateLogStream`,
`logs:PutLogEvents`, `secretsmanager:GetSecretValue` on `drishti/prod/*`, `kms:Decrypt` on the secrets' key.

#### 11.3 Secrets

| Secret (Secrets Manager) | Variable | Container |
|---|---|---|
| `drishti/prod/token` | `DRISHTI_TOKEN_SECRET` (at least 32 characters; a master key, OPERATIONS.md section 9) | server, console |
| `drishti/prod/session` | `DRISHTI_SESSION_SECRET` (at least 32 characters) | console |
| `drishti/prod/net` | `DRISHTI_NET_SECRET` (the cluster key) | server |
| `drishti/prod/admin` (`username`, `password`) | `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` (the first administrator, first start only) | server |

These are the variables `drishti-stack/secrets.env.example` lists for the local cluster. Nothing secret is in an image,
a task definition's plain environment, or EFS. Rotation: a new secret version and a rolling restart (section 14.1);
rotating the token secret signs everyone out, as on one machine.

### 12. Task definition

For node `drishti-a` (the other nodes differ only in the node id and the three access point ids). The template is
[`deploy/aws/ecs/templates/node-task.json`](../../deploy/aws/ecs/templates/node-task.json); `deploy/aws/ecs/register.sh`
fills it from `deploy/aws/env` and the access points of `setup-efs.sh`, and registers it. It also sets
`DRISHTI_DELTA_ROOT=s3://<lake bucket>/lake`, so the shipped Delta connectors read the lake in S3. As rendered:

```json
{
  "family": "drishti-prod-drishti-a",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE", "EC2"],
  "cpu": "6144", "memory": "16384",
  "runtimePlatform": { "operatingSystemFamily": "LINUX", "cpuArchitecture": "X86_64" },
  "taskRoleArn": "arn:aws:iam::<account>:role/drishti-prod-drishti-a-task",
  "executionRoleArn": "arn:aws:iam::<account>:role/drishti-prod-execution",
  "volumes": [
    { "name": "data",   "efsVolumeConfiguration": { "fileSystemId": "fs-…", "transitEncryption": "ENABLED",
        "authorizationConfig": { "accessPointId": "fsap-…data",   "iam": "ENABLED" } } },
    { "name": "config", "efsVolumeConfiguration": { "fileSystemId": "fs-…", "transitEncryption": "ENABLED",
        "authorizationConfig": { "accessPointId": "fsap-…config", "iam": "ENABLED" } } },
    { "name": "sutras", "efsVolumeConfiguration": { "fileSystemId": "fs-…", "transitEncryption": "ENABLED",
        "authorizationConfig": { "accessPointId": "fsap-…sutras", "iam": "ENABLED" } } }
  ],
  "containerDefinitions": [
    { "name": "server", "image": "<account>.dkr.ecr.<region>.amazonaws.com/drishti-server-ecs:<version>",
      "cpu": 4096, "memory": 12288, "essential": true, "readonlyRootFilesystem": true, "stopTimeout": 60,
      "portMappings": [{ "containerPort": 18480, "protocol": "tcp" }],
      "mountPoints": [
        { "sourceVolume": "data",   "containerPath": "/opt/drishti/data" },
        { "sourceVolume": "config", "containerPath": "/opt/drishti/config" },
        { "sourceVolume": "sutras", "containerPath": "/opt/drishti/sutras" } ],
      "environment": [
        { "name": "DRISHTI_NODE_ID", "value": "drishti-a" },
        { "name": "DRISHTI_NET_SEEDS", "value": "http://drishti-a.drishti.local:18480,http://drishti-b.drishti.local:18480" },
        { "name": "DRISHTI_SECURITY_ENABLED", "value": "true" },
        { "name": "DRISHTI_SEED_ADMIN", "value": "true" },
        { "name": "DRISHTI_PACKS", "value": "core,market-data,trading,counterparty-risk,market-risk" } ],
      "secrets": [
        { "name": "DRISHTI_TOKEN_SECRET",  "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/token" },
        { "name": "DRISHTI_NET_SECRET",    "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/net" },
        { "name": "DRISHTI_SEED_USERNAME", "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/admin:username::" },
        { "name": "DRISHTI_SEED_PASSWORD", "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/admin:password::" } ],
      "healthCheck": { "command": ["CMD-SHELL", "curl -fsS --max-time 3 http://$(hostname -I | awk '{print $1}'):18480/actuator/health/readiness"],
                       "interval": 15, "timeout": 5, "retries": 4, "startPeriod": 180 },
      "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/drishti/prod", "awslogs-region": "<region>",
                                                                 "awslogs-stream-prefix": "drishti-a" } } },
    { "name": "console", "image": "<account>.dkr.ecr.<region>.amazonaws.com/drishti-console-ecs:<version>",
      "cpu": 2048, "memory": 4096, "essential": true, "readonlyRootFilesystem": true, "stopTimeout": 30,
      "dependsOn": [{ "containerName": "server", "condition": "HEALTHY" }],
      "portMappings": [{ "containerPort": 17480, "protocol": "tcp" }],
      "mountPoints": [
        { "sourceVolume": "data",   "containerPath": "/opt/drishti/data" },
        { "sourceVolume": "config", "containerPath": "/opt/drishti/config" },
        { "sourceVolume": "sutras", "containerPath": "/opt/drishti/sutras" } ],
      "environment": [
        { "name": "DRISHTI_AUTH_ENABLED", "value": "true" },
        { "name": "DRISHTI_CONSOLE__PACKS__ENABLED", "value": "core,market-data,trading,counterparty-risk,market-risk" } ],
      "secrets": [
        { "name": "DRISHTI_TOKEN_SECRET",   "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/token" },
        { "name": "DRISHTI_SESSION_SECRET", "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/session" } ],
      "healthCheck": { "command": ["CMD-SHELL", "python -c \"import urllib.request;urllib.request.urlopen('http://localhost:17480/healthz')\""],
                       "interval": 15, "timeout": 5, "retries": 4, "startPeriod": 60 },
      "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/drishti/prod", "awslogs-region": "<region>",
                                                                 "awslogs-stream-prefix": "drishti-a" } } }
  ]
}
```

The server's health check asks readiness on the task's own address, so the console (`dependsOn … HEALTHY`) starts
only when its server answers.

### 13. Service definition

Template [`deploy/aws/ecs/templates/node-service.json`](../../deploy/aws/ecs/templates/node-service.json), created or
updated by `register.sh`; a console service of its own is `console-task.json` and `console-service.json`
(`register.sh --consoles N`). As rendered for `drishti-a`:

```json
{
  "serviceName": "drishti-a",
  "cluster": "drishti-prod",
  "taskDefinition": "drishti-prod-drishti-a",
  "desiredCount": 1,
  "launchType": "FARGATE",
  "platformVersion": "1.4.0",
  "deploymentConfiguration": { "maximumPercent": 100, "minimumHealthyPercent": 0,
                               "deploymentCircuitBreaker": { "enable": true, "rollback": true } },
  "networkConfiguration": { "awsvpcConfiguration": { "subnets": ["subnet-a", "subnet-b", "subnet-c"],
                                                     "securityGroups": ["sg-drishti-node"], "assignPublicIp": "DISABLED" } },
  "loadBalancers": [{ "targetGroupArn": "arn:aws:elasticloadbalancing:…:targetgroup/drishti-consoles/…",
                      "containerName": "console", "containerPort": 17480 }],
  "serviceRegistries": [{ "registryArn": "arn:aws:servicediscovery:…:service/srv-drishti-a" }],
  "healthCheckGracePeriodSeconds": 240,
  "enableExecuteCommand": false,
  "propagateTags": "SERVICE"
}
```

- `maximumPercent: 100` and `minimumHealthyPercent: 0`: a new deployment stops the node's task before starting the
  new one: never two tasks on one node's folders.
- The deployment circuit breaker rolls back a version that cannot become healthy.
- `enableExecuteCommand: false`: no shell into production tasks; diagnosis is through logs, metrics and Admin.

### 14. Operations

#### 14.1 A new version (rolling, one node at a time)

```
  for each node (drishti-a, then b, then c), waiting for each to be healthy before the next:
    1. register a task definition revision with the new image tags
    2. update the node's service → ECS stops the old task (SIGTERM, graceful) → starts the new task
    3. the new task mounts the same EFS folders, rejoins Drishti*net with its (possibly new) address
    4. healthy: the load balancer sends it users again
  the other nodes answer meanwhile (the console falls over to another server; reads forward to a peer)
```

Schema and cache migrations happen at start, as on one machine; a version that needs all nodes on it at once is
marked in RELEASE_NOTES and deployed by stopping every service first.

#### 14.2 Everyday events

| Event | What happens |
|---|---|
| **Task or host failure** | ECS starts a new task, possibly in another zone, on the same folders; the node rejoins with its new address; the console of another node keeps its users |
| **Adding a node** | three access points, a task role, a task definition and a service for `drishti-d`; its first start writes its Drishti*net files and joins through the seeds; Admin → Net shows it |
| **Removing a node** | scale its service to 0, then delete it; Admin → Net → Members, "forget"; its EFS folders are kept until the backup retention passes, then deleted by hand |
| **A pack** | `drishti.py pack deploy … --to /opt/drishti/data/packs/installed` from an administration host that mounts the node's data access point, or Admin → Packs upload; the server loads it by itself; for every node, repeat per node (or deploy once and let Drishti*net's newest-version rule serve it, section 1, its section 9) |
| **Connector files, Sutras** | edited in the console (written to EFS) or copied onto EFS from an administration host |
| **Secrets rotation** | new secret version, then a rolling restart (14.1) |
| **Certificates** | ACM renews the load balancer's certificate by itself |

#### 14.3 The administration host

A small EC2 instance (or an AWS Systems Manager session on one) in a private subnet that mounts the EFS file system
with an administrator's access point rooted at `/drishti`, for pack deployment, inspecting files and restores. It is
the only place where people touch EFS directly.

#### 14.4 Upgrading Drishti*net membership settings

`config/net/*.yaml` are files on EFS: edited on the administration host or in Admin → Net; the server watches them
(`DRISHTI_NET_WATCH`), so no restart is needed.

#### 14.5 Back-up and restore

| | |
|---|---|
| Back-up | AWS Backup plan `drishti-efs-daily`: daily at 01:00, kept 35 days (a site setting), monthly kept 13 months; vault locked against deletion |
| Restore one node | stop its service (desired count 0); restore `/drishti/prod/<node>` from the recovery point to the same path (item-level restore); start the service |
| Restore everything | stop every service; restore the file system's `/drishti/prod`; start the seeds first, then the rest |
| What is lost | changes since the recovery point; users, roles and tokens are also on the other nodes (IdentitySync) and come back from them |

### 15. Observability

| Signal | Where |
|---|---|
| Logs | CloudWatch Logs `/drishti/<env>`, one stream per node and container; log files on EFS in `data/logs` |
| Metrics | the server's Prometheus endpoint (`/actuator/prometheus`, `DRISHTI_METRICS_TOKEN`) scraped by Amazon Managed Service for Prometheus or the CloudWatch agent; the Grafana dashboard in `deploy/grafana` |
| ECS | CPU and memory per container (Container Insights) |
| EFS | `PercentIOLimit`, `ClientConnections`, `MeteredIOBytes`, latency (CloudWatch) |
| Drishti | Admin → Health, Admin → Net (members, the donut, forwarding), Admin → Caches, per node |

Alarms: a node's service with no running task for 5 minutes; a console target unhealthy; server heap above 90 % for 10
minutes; EFS `PercentIOLimit` above 80 %; Drishti*net member `dead` for longer than 5 minutes; the backup job failed.

### 16. Disaster recovery

| | Primary Region | Recovery Region |
|---|---|---|
| Lakes | S3 bucket | S3 Cross-Region Replication of the lake bucket |
| Node files | EFS | EFS replication (read-only replica, made writable on fail-over) |
| Images | ECR | ECR replication |
| Secrets | Secrets Manager | replicated secrets |
| Running | the services | the same task and service definitions, desired count 0 |

Fail-over: make the EFS replica writable, set the recovery services' desired count to 1, point the load balancer's DNS
(Route 53) at the recovery Region. Recovery point: the replication lag (minutes); recovery time: the time to start the
nodes and warm their caches (tens of minutes). Exercised twice a year.

### 17. Performance: what EFS costs

<!-- 2.9.7 docs consolidation, phase 2: the scale MEASUREMENTS of this section move to RESOURCES_AND_SCALE.md (with PERFORMANCE and SCALE_BENCHMARK); the design stays here. -->

EFS is a network file system: an operation takes about a millisecond or more, where a local NVMe disk takes tens of
microseconds. For files read and written whole (users, settings, reports, designs) this does not matter. It does for
the **caches** that the rule also puts on EFS:

| Cache on EFS | Effect | What softens it |
|---|---|---|
| RocksDB derived pages and document cache | slower first reads of a page; slower builds (many small writes) | Drishti's memory caches in front of every disk cache; RocksDB's block cache |
| DuckDB files (BI store) | slower first queries after a start | DuckDB's buffer pool |
| Warm column files and type-ahead segments (memory-mapped) | the first touch of a page of the file reads it over NFS; then the operating system's page cache serves it | page cache: give the task memory beyond the heap |
| Temp folders, native libraries | a slower start (a few seconds) | none needed |

Step A4 measures, on EFS and on local disk: node start to ready, first trade view, a form search, a derived-page build
and a BI query; the numbers go here. If they show slow first reads that matter, the product owner decides whether
the caches (which Drishti can always rebuild from the lake) may use the task's local disk instead (section 21).

### 18. Cost

Order of magnitude for three nodes in one Region, list prices, on-demand (to be priced for the bank's Region and
discounts):

| Item | Size | Monthly |
|---|---|---|
| ECS on Fargate | 3 tasks × (6 vCPU, 16 GB), always on | about USD 650–750 |
| EFS | 300 GB Standard, Elastic throughput | about USD 100 plus throughput charges |
| Load balancer | one ALB | about USD 25 plus capacity units |
| S3 | the lake (depends on data; section 5, its section 19 for 500 GB a day) | |
| Other | Secrets Manager, Cloud Map, CloudWatch, AWS Backup, VPC endpoints | about USD 100–200 |

### 19. Tests and acceptance

| Test | Pass when |
|---|---|
| **Read-only root, every flow**: both images run with a read-only root and plain folders mounted at the three paths (`DRISHTI_REQUIRE_EFS=false`, the only use of that switch), and the browser suite's smoke set runs against them | no write outside the three paths (a write elsewhere fails the test, naming the path) |
| **Entry points**: a missing mount, a read-only mount, a mount that is not NFS, a loopback address, a second task | each refuses to start with its message and exit code 78 |
| **Durability** (development account): stop a node's task | users, tokens, connector files, Sutras, designs, reports, installed packs and cluster membership are as before |
| **Zone move**: force the task into another Availability Zone | the same |
| **Restore** from AWS Backup to a new path, then swapped in | the node comes back as of the recovery point |
| **Drishti*net**: three nodes find each other through Cloud Map names | each advertises its task address; a restart with a new address is seen by every member within one `dead-after` |
| **Rolling upgrade** | users of the node being upgraded fall over to another console without signing in again |
| **Performance** (section 17) | measured and recorded |

### 20. Build plan

| Step | Content | Done when |
|---|---|---|
| A1 | The images and entry points of sections 5–6 as files (**done**: `deploy/docker/ecs/`); `task.lock` touched by the server; DNS seed names accepted | the read-only-root and entry-point tests pass on a build machine with Docker |
| A2 | One template (CloudFormation or Terraform): VPC pieces, EFS and its access points, roles, secrets, task and service definitions, load balancer, Cloud Map, endpoints, backup plan, alarms (**scripted** with the AWS CLI in `deploy/aws/`: access points, Cloud Map, roles, task and service definitions, schedules, lake buckets, alarms; the VPC, EFS, load balancer, endpoints and secrets remain the platform team's) | a new environment comes up from the template alone |
| A3 | A development environment: the durability, zone, restore, Drishti*net and rolling-upgrade tests | all pass |
| A4 | EFS measurements (section 17) | recorded here |
| A5 | Documentation: OPERATIONS (an AWS chapter), OPERATIONALISING section 13, a tutorial "Drishti on AWS" (UI and CLI), help cards | reviewed |

### 21. Open questions

- **Fargate or EC2** capacity: Fargate is simpler; EC2 allows larger tasks and cheaper steady capacity. The design
  works on both.
- **CloudFormation or Terraform** for step A2: whichever the bank's platform team uses.
- **Caches on EFS**: the rule puts them there; step A4's numbers decide whether to keep it so.
- **Consoles**: one per node (this design), or a console service scaled on its own (recommended at the scale of
  section 5).
- **TLS inside the VPC**: required by the bank or not.

### 22. Iceberg on AWS (2.9.5)

Delta Lake stays the default ([section 3](#3-a-high-performance-delta-lake-on-aws-past-200-tb), `deploy/aws/delta-lake/`). A deployment that keeps some tables in
Apache Iceberg (the risk domain, a source system's trades) lists them in `ICEBERG_TABLES_FILE` and runs the scripts of
[`deploy/aws/iceberg/`](../../deploy/aws/iceberg). They share `deploy/aws/env` and `lib/common.sh` with the Delta scripts; every one has
`--help` and `--dry-run` and is tested that way (`tools/test_deploy_aws.py`); none has yet run against a real AWS account.

| Script | What it does |
|---|---|
| `setup-iam.sh` | three policies (`iceberg-read`, `-load`, `-maintain`, from `iam/*.json`) on the server, loader and maintenance task roles: S3 on `ICEBERG_PREFIX`, KMS, and for Glue `glue:GetDatabase/GetTable(s)`, `CreateDatabase`, `CreateTable`, `UpdateTable`, `lakeformation:GetDataAccess` |
| `check-conditional-writes.sh` | proves the bucket refuses a second create of one key (`If-None-Match: *`): a path-based table's commit is that create |
| `load-day.sh --date D` | per table, under its EFS lock: the day's dumps from the landing bucket, then `lake build --format iceberg --lake s3://LAKE_BUCKET/ICEBERG_PREFIX` (Drishti's loader writes the data files and commits one overwrite of the day: no publish step); metrics `LoadSucceeded`, `LoadSeconds`, `PublishedBusinessDate`. With `ICEBERG_CATALOG=glue` the Glue database of the domain is created first with its S3 location |
| `backfill.sh --from D1 --to D2` | `load-day.sh` for each business date, oldest first; stops at the first failure and says how to resume |
| `maintain.sh` | `lake maintain` over `lake-maintenance.s3.yaml` (`format: iceberg`): retention with the 7-year floor of `risk` and `risk-results`, relayout, snapshot expiry (`ICEBERG_EXPIRE_HOURS`), orphan files older than `ICEBERG_ORPHAN_HOURS` (72 at least); then `setup-lifecycle.sh` |
| `setup-lifecycle.sh` | storage tiers of `data/business_date=YYYY-MM` by business date (Standard-IA, then Glacier Instant Retrieval), in the bucket's one lifecycle configuration beside the Delta rules (each script keeps the other's rules); never `metadata/`, never an expiry of a current object |
| `setup-alarms.sh` | per table: load failed, not loaded for three days, load slow, more than `ALARM_ICEBERG_SNAPSHOTS` (500) snapshots |
| `report.sh` | per table from its current metadata file: version, snapshots, records, data files, business dates and the newest; sends `IcebergSnapshots` and `NewestBusinessDate` |
| `check-glue.sh` | the Glue proof below |

Settings in `deploy/aws/env` (from `env.example`): `ICEBERG_PREFIX` (`iceberg`), `ICEBERG_CATALOG` (`hadoop`: path-based tables in S3;
`glue`: the AWS Glue Iceberg REST catalog, the domain being the Glue database), `GLUE_CATALOG_ID` (the account), `ICEBERG_TABLES_FILE`,
`ICEBERG_KEEP_BUSINESS_DAYS`, `ICEBERG_EXPIRE_HOURS`, `ICEBERG_ORPHAN_HOURS`.

**Path-based tables or Glue.** A path-based table in S3 commits by a conditional create of its next metadata file, so two writers of one
table are safe when the bucket honours `If-None-Match` (`check-conditional-writes.sh`); the scripts also hold one EFS lock per table. With
Glue the catalog commits atomically and other engines (Athena, Spark) see the same tables. The server reads either through its
connector file (`plugin: iceberg`; [ICEBERG_CONNECTOR.md section 19](../connectors/ICEBERG_CONNECTOR.md#19-aws-glue-s3-tables-and-the-iam-they-need-295)).

**How the product owner proves Glue (no AWS account is available to the build).**

1. In a sandbox account: `cp deploy/aws/env.example deploy/aws/env`, fill in `AWS_REGION`, `AWS_ACCOUNT_ID`, `LAKE_BUCKET` (an existing
   bucket) and `ICEBERG_PREFIX`.
2. Credentials in the default chain (`aws configure --profile sandbox` and `AWS_PROFILE=sandbox`), allowed what `iam/loader-policy.json`
   allows plus `glue:DeleteTable`, `glue:DeleteDatabase` and `s3:DeleteObject` under `<ICEBERG_PREFIX>/_drishti-glue-check/`.
3. `deploy/aws/iceberg/check-glue.sh --dry-run` shows what it will run; then `deploy/aws/iceberg/check-glue.sh`. It runs `GlueLiveTest`
   (Maven, Java 21): a Glue database `drishti_check_<time>` at `s3://<bucket>/<prefix>/_drishti-glue-check/…`, a table of 25 P&L
   vectors written by Drishti's loader, read back through the connector (a document, and `vectors()` bit for bit), then dropped
   (`--keep` keeps them). Exit 0 means Glue works end to end; the steps are printed from `target/glue-live-report.txt`.
4. S3 Tables: create a table bucket, then `check-glue.sh --s3-tables arn:aws:s3tables:<region>:<account>:bucket/<name>`. **Not run in
   2.9.5** (no account; the time box went to the code); the result of the product owner's run is recorded in the CHANGELOG.

**Official results to S3 (2.9.5).** The official VaR and exposure tables (`risk-results/var-official`, `exposure-official`,
`exposure-official-cpty`) and their JSON Lines can be written straight to the bucket, in Delta (the default) or Iceberg, by the server
that captures them; before 2.9.5 an `s3://` root ended in `publishError`. In the server's settings:

```yaml
drishti:
  var:
    official:
      root: s3://LAKE_BUCKET/risk          # tables at s3://LAKE_BUCKET/risk/risk-results/<table>
      format: delta                        # or iceberg
      storage: { s3.region: us-east-1 }    # no keys: the task role through the default credential chain
  ccr:
    exposure:
      official:
        root: s3://LAKE_BUCKET/risk
        format: delta
        storage: { s3.region: us-east-1 }
```

The task role needs `s3:GetObject`, `s3:PutObject` and `s3:ListBucket` on that prefix (no delete: the tables are append-only). A Delta
commit file is an exclusive create (`If-None-Match: *`, proved by `check-conditional-writes.sh`), so two servers publishing at once both
land, the second on the next version; an Iceberg table commits by the conditional create of its next metadata file. The lifecycle rules
(`setup-lifecycle.sh`) tier the data files and never touch `_delta_log/` or `metadata/`; nothing expires inside the 7 years. Tested on
S3Mock in process (`OfficialS3Test`); not yet run against a real bucket.

## 3. A high-performance Delta lake on AWS, past 200 TB

Status: **scripts in [`deploy/aws/`](../../deploy/aws), images in [`deploy/docker/ecs/`](../../deploy/docker/ecs).** Every
script has `--help` and `--dry-run` and is tested that way (`tools/test_deploy_aws.py`); none has yet been run against a real
AWS account, so the first run in a development account is part of the work (section 15). Where this guide relies on something
that is designed but not built, it says **planned** and names the step of [section 5](#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built) (S1 to S8) that
builds it.

Related: [section 2](#2-drishti-on-aws-ecs-efs-and-s3) (ECS, EFS and the identical-path rule), [section 5](#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built)
(500 GB a day for seven years), [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) (how Drishti reads a Delta table),
[OPERATIONS.md](OPERATIONS.md#20-lake-maintenance-admin--lakes--maintain-and-lake-maintain) (`lake maintain`), [TOOLS.md](../guides/TOOLS.md) (`lake build`,
`lake publish`), [section 1](#1-drishtinet-many-servers-one-drishti) (several servers). Delta is the default; tables a deployment keeps in Apache Iceberg
instead have their own scripts, `deploy/aws/iceberg/` ([section 2, its section 22](#22-iceberg-on-aws-295)); the two
lifecycle scripts keep each other's rules in the bucket's one configuration.

> **The lake lives in Amazon S3: every table's Parquet data files AND its `_delta_log`.** Nothing of the lake is on EFS.
> EFS holds only what a node must find again after a restart: its configuration, users and tokens, Drishti\*net state, and
> the caches that should be warm after a restart (or local NVMe for those caches, section 10). The loader's scratch folder on
> EFS holds one day of one table for the minutes of a load, and is removed after it.

### 1. The picture

```
  SOURCES (ENDUR, CALYPSO, MUREX, reference)        dumps: JSON / JSON Lines / .gz
          │
          ▼
  ┌── S3: <org>-drishti-<env>-landing ──────────────┐     kept LANDING_KEEP_DAYS (30), then expired
  │  2026-10-20/endur/*.jsonl.gz   …/calypso/  …/murex/ │
  └──────────────┬──────────────────────────────────┘
                 │ EventBridge Scheduler, 22:30 New York, Monday to Friday
                 ▼
  ┌── ECS task "loader" (image drishti-loader-ecs) ─────────────────────────────────────────────────────┐
  │ load-day.sh, per table, under the table's lock (EFS):                                                │
  │  1 aws s3 sync   landing → /opt/drishti/data/lake-build/<run>/in/<table>/      (EFS scratch)         │
  │  2 lake build    → a scratch Delta table: promoted columns, sorted by id, Parquet row groups 1 MB    │
  │  3 aws s3 sync   scratch data files → s3://…-lake/lake/<domain>/<table>/business_date=…/             │
  │  4 lake publish  ONE Delta commit on S3 (conditional PUT): the day's old files out, new ones in      │
  │  5 metrics       Drishti/Lake: LoadSucceeded, LoadSeconds, PublishedBusinessDate                     │
  └──────────────┬──────────────────────────────────────────────────────────────────────────────────────┘
                 ▼
  ┌── S3: <org>-drishti-<env>-lake (THE LAKE) ──────────────────────────────────────────────────────────┐
  │  lake/trading/trade_endur/_delta_log/   00000000000000000412.json … checkpoints, _last_checkpoint    │
  │  lake/trading/trade_endur/business_date=2026-10-20/PRODUCT_TYPE=IRS_FIXFLOAT/part-…​.snappy.parquet │
  │  versioning · SSE-KMS · only through the VPC endpoint · tiers by business date:                      │
  │      Standard (0–90 days) → Standard-IA (to 2 years) → Glacier Instant Retrieval (beyond)            │
  └──────────────┬──────────────────────────────────────────────────────▲───────────────────────────────┘
                 │ ranged GETs (task role, gateway endpoint)             │ checkpoint, retention, vacuum
                 ▼                                                       │ (ECS task "maintenance", nightly)
  ┌── ECS services drishti-a, drishti-b, drishti-c (one task each: server + console) ──────────────────┐
  │ server: Delta connector, native engine, root s3://…-lake/lake (DRISHTI_DELTA_ROOT)                  │
  │   heap: file lists, id maps, column sets, documents, Parquet footers                                │
  │   disk: id maps and documents (RocksDB) under /opt/drishti/data/cache (EFS) or /opt/drishti/nvme    │
  │ Drishti*net: gossip, forwarding and scatter-gather between task IPs; seeds by Cloud Map names       │
  └──────────────┬─────────────────────────────────────────────────────────────────────────────────────┘
                 ▼
  ┌── EFS (Regional, encrypted, Elastic throughput) ───────────────────────────────────────────────────┐
  │ /drishti/<env>/<node>/opt/drishti/{data,config,sutras}   one access point per node per path          │
  │ /drishti/<env>/loader/opt/drishti/data                   scratch lakes (minutes), table locks         │
  │ NO lake data, NO _delta_log                                                                          │
  └─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Why S3 and not EFS for the lake

| | Amazon S3 | Amazon EFS |
|---|---|---|
| **Cost of 200 TB** (list prices, one Region) | about USD 1,400 a month tiered by business date (section 12); USD 4,600 all in Standard | USD 60,000 a month in EFS Standard; USD 3,200 in Infrequent Access plus a charge on every byte read, with tens of milliseconds added to each read |
| **Request rate** | at least 3,500 writes and 5,500 reads a second **per prefix**, and every `business_date=…/PRODUCT_TYPE=…/` folder is a prefix: the rate grows with the folders | one file system's limits (Elastic throughput: GB/s across all clients, IOPS per file system); every node, loader and maintenance job shares them |
| **Latency of a single read** | 10–30 ms to the first byte, in-Region; Drishti reads one row group (section 11.1) | about 1 ms for hot data in Standard; tens of ms in Infrequent Access |
| **Durability** | eleven nines, three or more Availability Zones, versioning (section 5.1), Cross-Region Replication | Regional: three zones; backup by AWS Backup |
| **Tiers** | Standard → Standard-IA → Glacier Instant Retrieval, still read in milliseconds; set per prefix and date by lifecycle rules (section 9.4) | Standard → Infrequent Access → Archive by last access only |
| **Other readers** | Athena, Trino, Spark, EMR and Glue read the same Delta tables in S3 (questions over all of history: [section 5, its section 11.4](#114-questions-over-all-88-billion-rows)) | only something that mounts the file system |
| **Writers** | conditional writes make concurrent commits safe (section 8.2) | a hard link on a POSIX file system |

At 200 TB and beyond, EFS costs ten to forty times more and shares one throughput budget between every reader and writer.
S3 scales its request rate by prefix and its cost by tier. Drishti's reads are built for object storage: they read one day's
files and one row group of one file at a time, by byte ranges (section 11.1). So the lake is in S3, and EFS keeps the small,
hot, per-node state.

### 3. Built and planned

| | Status | Where |
|---|---|---|
| Reading Delta tables in S3 (native engine, no Hadoop): file lists from the last checkpoint, id maps, one row group per read, footers cached, documents cached on disk | **built** | `drishti-deltalake`, `drishti-lakeio`, `plugins/drishti-plugin-delta`; [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) |
| `lake build`: a JSON export into a Delta table on local disk or EFS, the pack's promoted columns, partitions, sorted by id per partition that fits a batch | **built** | `tools/lake/build.py`, Drishti's Java writer in the server jar |
| `lake publish`: a table `lake build` wrote, committed into a table in S3 as one commit (S3 conditional writes) | **built** (this release) | `tools/lake/publish.py`, `tools/lake/test_publish.py` |
| `lake maintain` on an `s3://` root: retention, checkpoint and log cleanup, vacuum | **built** | `tools/lake/maintain.py` (with `--force-foreign`, section 9) |
| Buckets, IAM, conditional-write check, lifecycle tiers, alarms, report, load, backfill, maintenance, EFS access points, Cloud Map, task and service definitions, schedules | **scripts** (`--dry-run` tested; first real run pending) | [`deploy/aws/`](../../deploy/aws) |
| The ECS images (server, console, loader) and their entry points | **files** (not yet built in CI) | [`deploy/docker/ecs/`](../../deploy/docker/ecs) |
| Drishti\*net shards by table: nodes that read different tables are asked together; nodes that read the same table are replicas, and an entity goes to the same replica by a hash of its id | **built** | [section 1, its sections 7 and 10](#10-shared-and-separate-lakes) |
| Range maps from the log's per-file id statistics, Parquet Bloom filters on `id` | **planned (SUPER_SCALE step S1)** | |
| Tables by year (`root: …/{year}`), closing a year | **planned (S2)** | |
| A loader fleet writing slices of one table at once, a day register, publication by fingerprint | **planned (S3)** | |
| Products (link indexes, group totals, rollups, id lists) written with the data | **planned (S4)** | |
| Each server owning a share of (table, day) slices on the ring; split searches and aggregates | **planned (S5, S6)** | |
| Warm tier of memory-mapped column files of owned days | **planned (S7)** | |
| Benchmark at scale in AWS | **planned (S8)** | |

### 4. First-time setup, step by step

Run the steps from a workstation or an administration host with the AWS CLI v2, the repository, Java 21 and the console's
virtual environment (`drishti-console/.venv`). Every script takes its settings from `deploy/aws/env`; every script prints its
AWS calls first with `--dry-run`. Run each with `--dry-run`, read what it would do, then run it without.

#### Step 0: prerequisites

Before the scripts: a VPC with private subnets in three Availability Zones, a **gateway VPC endpoint for S3** on their route
tables, interface endpoints for ECR, CloudWatch Logs, Secrets Manager and STS, an EFS file system (Regional, encrypted,
Elastic throughput, a mount target per subnet), a KMS key for the lake (its key policy allows the roles of step 3), an ECS
cluster, an Application Load Balancer with a target group for the consoles on port 17480, an SNS topic for alarms, and the four
secrets of [section 2, its section 11.3](#113-secrets). These are the bank's platform team's templates
(CloudFormation or Terraform); the scripts take their ids.

#### Step 1: the settings

```bash
cp deploy/aws/env.example deploy/aws/env        # git-ignored; holds account ids and names, never a secret
$EDITOR deploy/aws/env                          # replace every <placeholder>
cp deploy/aws/delta-lake/tables.example deploy/aws/delta-lake/tables.conf   # optional: your tables; set LAKE_TABLES_FILE
```

**You should see:** nothing yet. A script run with a placeholder left says which one:
`setup-buckets.sh: LAKE_KMS_KEY_ARN is still a placeholder (<arn:aws:kms:…>) in deploy/aws/env`.

The tables file has one line per table: its folder, kind, pack, the pack's connector, the landing prefix (`{date}` is the
business date), the business-date field (`-` for the day being loaded) and the partition column:

```
trading/trade_endur      trade   config/packs/trading   trade-endur     {date}/endur/           -      PRODUCT_TYPE
```

#### Step 2: the buckets

```bash
deploy/aws/delta-lake/setup-buckets.sh --dry-run
deploy/aws/delta-lake/setup-buckets.sh
```

**You should see** each call as it runs, ending in
`setup-buckets.sh: done: acme-drishti-prod-lake, acme-drishti-prod-landing, acme-drishti-prod-logs`:

```
+ aws s3api create-bucket --bucket acme-drishti-prod-lake --object-ownership BucketOwnerEnforced --create-bucket-configuration LocationConstraint=eu-west-2
+ aws s3api put-public-access-block --bucket acme-drishti-prod-lake --public-access-block-configuration BlockPublicAcls=true,…
+ aws s3api put-bucket-versioning --bucket acme-drishti-prod-lake --versioning-configuration Status=Enabled
+ aws s3api put-bucket-encryption --bucket acme-drishti-prod-lake --server-side-encryption-configuration file://…/enc-….json
+ aws s3api put-bucket-policy --bucket acme-drishti-prod-lake --policy file://…/policy-….json
…
```

Run again, it says `acme-drishti-prod-lake exists: kept, settings applied again` and puts the same settings.

#### Step 3: who may do what

```bash
deploy/aws/delta-lake/setup-iam.sh --dry-run
deploy/aws/delta-lake/setup-iam.sh
```

| Role | Policy (`deploy/aws/delta-lake/iam/`) | May |
|---|---|---|
| `drishti-<env>-server-task` | `server-policy.json` | read the lake prefix (GET, LIST), decrypt; mount EFS through access points |
| `drishti-<env>-loader-task` | `loader-policy.json` | read the landing bucket; write data files and commits; **never delete** (an explicit deny) |
| `drishti-<env>-maintenance-task` | `maintenance-policy.json` | checkpoints, retention and vacuum: the only role that deletes; the lifecycle rules |
| `drishti-<env>-execution` | AWS's `AmazonECSTaskExecutionRolePolicy` and `drishti/<env>/*` secrets | ECS pulls images, writes logs, injects secrets |
| `drishti-<env>-scheduler` | inline | EventBridge Scheduler runs the lake tasks |

**You should see** `setup-iam.sh: done: three policies, five roles (…)`. A policy that exists gets a new default version.

#### Step 4: prove that commits are safe

```bash
deploy/aws/delta-lake/check-conditional-writes.sh
```

**You should see** `check-conditional-writes.sh: ok: the second create was refused (412 PreconditionFailed): concurrent commits are safe`.
Section 8.2 explains why this matters. Any other answer: stop and read section 15.

#### Step 5: storage tiers by business date

```bash
deploy/aws/delta-lake/setup-lifecycle.sh --print | head -20     # the rules, no AWS call
deploy/aws/delta-lake/setup-lifecycle.sh
```

**You should see** `setup-lifecycle.sh: 129 rules for acme-drishti-prod-lake` (three tables from 2019: one rule per table per
month still moving, one per table per year already in Glacier IR, three base rules). Section 9.4 explains the rules.

#### Step 6: EFS access points, one per node per path

```bash
deploy/aws/ecs/setup-efs.sh --dry-run
deploy/aws/ecs/setup-efs.sh
```

**You should see** one access point id per line (`fsap-0a1b…`) and `setup-efs.sh: 14 access points on fs-…` for three nodes:
`data`, `config` and `sutras` for each node and for `consoles`, and `data` for `loader` and `maintenance`. Each roots the
node's identical path, `/drishti/<env>/<node>/opt/drishti/<path>`, and runs as uid/gid 10001. Running it again returns the same
ids (the access point's name is its client token).

#### Step 7: Cloud Map for Drishti\*net seeds

```bash
deploy/aws/ecs/setup-cloud-map.sh        # once for the namespace (it takes a minute) …
deploy/aws/ecs/setup-cloud-map.sh        # … and again for one service per node
```

**You should see** `namespace requested: it takes a minute; run this script again to create the services`, then one
`create-service` per node. ECS registers each node's task under `drishti-a.drishti.local` with the task's own address; the
servers advertise `http://<task IP>:18480`, never loopback.

#### Step 8: the images

```bash
./mvnw -q package -DskipTests
docker build -f deploy/docker/ecs/server.Dockerfile  -t "$ECR_REGISTRY/drishti-server-ecs:2.9.2" .
docker build -f deploy/docker/ecs/console.Dockerfile -t "$ECR_REGISTRY/drishti-console-ecs:2.9.2" .
docker build -f deploy/docker/ecs/loader.Dockerfile  -t "$ECR_REGISTRY/drishti-loader-ecs:2.9.2" .   # carries deploy/aws/env
docker push …                                         # each of the three, after aws ecr get-login-password
```

The loader image copies `deploy/aws/` with the `env` you filled in (it holds no secret), so its tasks read the same settings
(`DRISHTI_AWS_ENV=/opt/drishti/app/deploy/aws/env`). Rebuild it when the settings change.

#### Step 9: task definitions, services and schedules

```bash
deploy/aws/ecs/register.sh --dry-run
deploy/aws/ecs/register.sh                          # --nodes --lake-jobs
deploy/aws/ecs/register.sh --consoles 4             # optional: a console service of its own (section 5, its section 16)
```

**You should see** a task definition per node (`drishti-prod-drishti-a`, …), a service per node, the `loader`, `maintenance`
and `report` task definitions and three schedules (`drishti-prod-load-day`, `-maintain`, `-report`), then
`register.sh: done`. The node task sets `DRISHTI_DELTA_ROOT=s3://<lake bucket>/lake`, so every shipped Delta connector
(`root: ${DRISHTI_DELTA_ROOT:…}`) reads the lake in S3 with no edit.

#### Step 10: the first load

```bash
deploy/aws/delta-lake/load-day.sh --dry-run --date 2026-10-20 --tables trading/trade_endur
deploy/aws/delta-lake/load-day.sh --date 2026-10-20 --tables trading/trade_endur
```

**You should see** the five steps of section 8.1, then
`lake publish: ok exit=0 published <n> file(s), <bytes> bytes, dates 2026-10-20 to s3://…/lake/trading/trade_endur version=0 (new table)`
and `load-day.sh: business date 2026-10-20: exit 0`. On ECS the same runs as the `loader` task
(`aws ecs run-task --task-definition drishti-prod-loader --overrides '{"containerOverrides":[{"name":"loader","command":["deploy/aws/delta-lake/load-day.sh","--date","2026-10-20"]}]}' …`).

In the console: `TRD <id>` on 2026-10-20 opens the trade; **Admin → Health** shows `trade-endur` `UP (engine: native)`.

#### Step 11: alarms and the report

```bash
deploy/aws/delta-lake/setup-alarms.sh
deploy/aws/delta-lake/report.sh
```

**You should see** `setup-alarms.sh: 17 alarms put` (four per table, two for the bucket and EFS, one per node), and the
report of section 11.3.

### 5. The layout: buckets, prefixes, tables and partitions

#### 5.1 Buckets

| Bucket | Holds | Settings (setup-buckets.sh) |
|---|---|---|
| `<org>-drishti-<env>-lake` | every Delta table: data files and `_delta_log` | versioning **on**; SSE-KMS with S3 Bucket Keys (one KMS call per object set, not per object); Block Public Access; objects owned by the bucket owner; TLS only; data reachable only through the VPC endpoint (and the account's `drishti-<env>-admin*` roles); access logs; request metrics on `lake/`; noncurrent versions expire after 14 days; incomplete multipart uploads abort after 2 days |
| `<org>-drishti-<env>-landing` | the sources' dumps | the same protections; dumps expire after 30 days |
| `<org>-drishti-<env>-logs` | S3 access logs | SSE-S3; expire after 400 days |

**Versioning on, with a short noncurrent window.** Delta keeps its own history (time travel back to the vacuum window), so
versions are not for history. They are a safety net: a file deleted by mistake (a wrong vacuum, a script run against the
wrong prefix) stays recoverable for `NONCURRENT_KEEP_DAYS` (14). The lifecycle expires noncurrent versions after that, so
storage does not double. ([section 2, its section 8.1](#81-buckets) is updated to match.)

#### 5.2 Prefixes and tables

```
s3://acme-drishti-prod-lake/
└── lake/                                         LAKE_PREFIX: DRISHTI_DELTA_ROOT = s3://acme-drishti-prod-lake/lake
    ├── trading/                                  the domain (the connector's domain)
    │   ├── trade_endur/                          one table per source system and kind (table.trade of connector trade-endur)
    │   │   ├── _delta_log/                       commits, a checkpoint every 10 commits, _last_checkpoint   (S3 Standard, always)
    │   │   ├── business_date=2026-10-19/
    │   │   └── business_date=2026-10-20/
    │   │       ├── PRODUCT_TYPE=FX_FORWARD/part-00000-<uuid>.c000.snappy.parquet
    │   │       └── PRODUCT_TYPE=IRS_FIXFLOAT/part-00000-<uuid>.c000.snappy.parquet …
    │   ├── trade_calypso/ …
    │   └── trade_murex/ …
    ├── counterparty/ …                           reference data: small tables, often mode: effective
    └── market/ …
```

| Choice | Why |
|---|---|
| **One table per source system and kind** (`trade_endur`, `trade_calypso`, `trade_murex` as a union, `union: true`) | each source loads, restates and fails on its own; each table's log stays a third the size; the three connectors of the trading pack already read them so |
| **The domain as the first folder** | matches the connector's `domain`; IAM and lifecycle rules can name a domain |
| **Tables by year** (`trade_endur/2026/`) | **planned (SUPER_SCALE step S2)**: until then one table holds every year, and its checkpoint grows with its live files (section 11.4) |

#### 5.3 Partition keys

| Key | Why |
|---|---|
| `business_date` (always first) | every interactive read names a date, so a read opens one day's folder only; a load and a restatement replace one day; retention removes whole days; the lifecycle tiers by the folder's date |
| `PRODUCT_TYPE` for trades (a second key, `--partition-by`) | spreads a day over several S3 prefixes (request rate), keeps each partition small enough for `lake build` to sort it in one batch (section 7.3), and lets a source restate one product type (`--mode overwrite-partitions`) |
| nothing finer | each partition column multiplies the folders and small files of a day; `lake build` allows four, two is the right number for trades and one for everything else |

**Request-rate-friendly prefixes.** S3 scales each prefix to at least 3,500 writes and 5,500 reads a second and splits busy
prefixes by itself. A day's reads are spread over its product-type folders, and over three tables for trades. Drishti's
writers name the folders (Delta paths cannot be hashed), so the spread comes from the partition keys. A `503 Slow Down` is
retried by the native engine's AWS client with back-off and counted in Health; the alarm `drishti-<env>-lake-s3-5xx` fires on
a sustained rate.

### 6. Keys: the id, promoted columns and statistics

Every row is one document ([DELTA_CONNECTOR.md section 3](../connectors/DELTA_CONNECTOR.md#3-what-is-on-disk)):

| Column | Type | Content |
|---|---|---|
| `id` | string | the entity's id (`--key`, or the pack's `ingest.<kind>.key`: `TRADE_ID`) |
| `doc` | string | the whole JSON document as the source sent it |
| `business_date` | date | partition |
| partition columns | string | `PRODUCT_TYPE` |
| promoted columns | string or double | copies of the fields searches, lists, links and BI read: the pack's `layout.<kind>.columns`, plus `ingest.<kind>.flatten` (`NOTIONAL`, `INDEX_NAME`) |

| Need | What it reads | So the table needs |
|---|---|---|
| **One entity** (`TRD MX-20000017`) | the day's id map (the `id` column of that day's files), then one row group of one file | rows sorted by `id` per file; small row groups (section 7) |
| **History** (`TRD MX-20000017 30`) | the `business_date=` partitions of the snapshot, then one single read per day, eight at once ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time)) | `business_date` as the partition; nothing else |
| **Type-ahead** | the newest day's ids (and `index.days` past days), from the id map, into the in-memory index | `id`; nothing else |
| **Search, pick lists, derived kinds, impact, links** | the day's column set: `id` and every promoted column, never `doc` | every field a search filters, sorts or shows **promoted**; a search on a field that is not promoted reads documents up to `drishti.search.max-scan` and says `partial` |
| **File skipping** | per-file min and max in the log, per-row-group min and max in the footer | `delta.dataSkippingStatsColumns` = `id`, `business_date` and the promoted columns, never `doc` (it would put whole documents in the log). `lake build` sets it; `lake publish` carries it to the S3 table |

Choosing what to promote is a pack decision ([DELTA_CONNECTOR.md section 4](../connectors/DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack)).
Adding a promoted column later changes the table's columns: `lake publish` refuses a day whose columns differ from the
table's (`the columns differ from the target's (new [...], missing [...], other type [...])`); see runbook 13.4.

### 7. Files: sizes, row groups and sorting

#### 7.1 The numbers

| Setting | Value | Set by | Why |
|---|---|---|---|
| Rows per file | `layout.<kind>.file-rows` (250,000) | the pack | few files per day, each large enough for S3 (hundreds of MB) |
| Row group | **1 MB** on AWS (`LAKE_ROW_GROUP_KB=1024` → `DRISHTI_LAKE_ROW_GROUP_KB`; every writer's sizes: [DELTA_CONNECTOR.md, Row-group size](../connectors/DELTA_CONNECTOR.md#row-group-size)) | `deploy/aws/env` | a single read fetches and decodes one row group: on S3 each GET costs 10–30 ms to the first byte, so 1 MB balances bytes moved against GETs; the page index narrows it further (`DRISHTI_LAKE_PAGE_KB`, 64) |
| Compression | Snappy (Drishti's Java writer and delta-rs) | the writer | the native engine also reads ZSTD, GZIP and LZ4_RAW |
| Statistics | `id`, `business_date`, promoted columns | `lake build` | file and row-group skipping without documents in the log |
| Checkpoint | every 10 commits (`delta.checkpointInterval`, set by `lake publish` on a new table) and after every maintenance run | `lake publish`, `lake maintain` | a reader replays the last checkpoint plus at most a few commits |
| Log retention | 30 days (`delta.logRetentionDuration`) | `lake publish` | commit files older than that (and before a checkpoint) are removed by `lake maintain` |
| Removed files kept | 7 days (`delta.deletedFileRetentionDuration`; `LAKE_VACUUM_HOURS` 168) | `lake publish`, `maintain.sh` | a reader in flight and *known at* reach back a week |

#### 7.2 Sorted by id

A file holds a contiguous id range and each row group a smaller one, so a read of one id skips every other row group by its
statistics. `lake build` sorts **each partition (a date and product type) that fits in one batch** (`--batch-rows`,
`LAKE_BATCH_ROWS`, 250,000); the Java writer writes a larger partition in the dumps' order. `load-day.sh` prints a warning
for each such partition. Three remedies, in order of preference:

1. **Ask the source to export sorted by id.** The dump's order is then the file's order: sorted at any size, no memory cost.
2. **Partition finer** (`PRODUCT_TYPE`, or a book or desk column) so each partition fits a batch.
3. **Raise `LAKE_BATCH_ROWS`** with the loader's memory (about rows × average document size × 3: 250,000 trades of 10 KB need
   about 8 GB; the loader task has 30 GB).

A Delta table written elsewhere (Spark, Databricks) sorts with `repartitionByRange(…, "id").sortWithinPartitions("id")`
([DELTA_CONNECTOR.md section 5](../connectors/DELTA_CONNECTOR.md#5-writing-the-lake)). Range maps that use the log's per-file
id ranges instead of id maps, and Bloom filters on `id`, are **planned (SUPER_SCALE step S1)**.

#### 7.3 One commit per day per table

`lake build` writes the day into a **fresh scratch table** on EFS, so every file there is live. `aws s3 sync` copies the data
files (new UUID names: nothing in S3 is overwritten), and `lake publish` commits them in **one** commit that removes the
day's previous files. A reader sees the old day or the new day, never a mixture, and the previous version stays readable
(time travel, *known at*) until vacuum.

### 8. Loading: the daily load, backfill and restatement

#### 8.1 The daily load (`load-day.sh`)

```
 EventBridge Scheduler (LOAD_SCHEDULE, LOAD_SCHEDULE_ZONE)
   └─► ECS RunTask drishti-<env>-loader ─► loader-entrypoint.sh (EFS mounted? writable?) ─► load-day.sh
          for each table of LAKE_TABLES_FILE:
            flock  /opt/drishti/data/lake-locks/<domain>--<table>.lock     (another load, backfill or maintenance: exit 3)
            aws s3 sync   s3://landing/<date>/<source>/  →  scratch/in/<table>/
            drishti.py lake build   --from scratch/in/<table> -r --kind trade --pack config/packs/trading --connector trade-endur
                                    --lake scratch/lake --domain trading --table trade_endur --mode overwrite-dates
                                    --batch-rows 250000 --as-of <date> --partition-by PRODUCT_TYPE --no-stamp --summary …
            aws s3 sync   scratch/lake/trading/trade_endur/  →  s3://lake/lake/trading/trade_endur/   --exclude "_delta_log/*"
            drishti.py lake publish --from scratch/lake/trading/trade_endur --to s3://…/lake/trading/trade_endur
                                    --dates <date> --mode overwrite-dates --storage-option AWS_REGION=<region> [--signal …]
            CloudWatch Drishti/Lake: LoadSucceeded, PublishedBusinessDate, LoadSeconds
          scratch removed (always)
```

The date defaults to today in `LOAD_SCHEDULE_ZONE`, the zone the schedule runs in (an evening load in New York is already the
next day in UTC). Exit codes: 0 every table published, 2 published with rejected documents (the summary says which), 1 a
table failed (its S3 table is unchanged), 3 a table was locked.

**The data-load signal.** With `DRISHTI_SIGNAL_URL` (a node's Cloud Map name) and `DRISHTI_TOKEN_SECRET_ID` (an API token
with `loads:write` in Secrets Manager, read into a 0600 file for the run), `lake publish` sends
`POST /api/v1/packs/{pack}/loads` per date: the server refreshes, verifies and alerts as for any load
([DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)). Without it, servers see the new table version at their next refresh
(`refresh-seconds`, 10).

#### 8.2 Why concurrent commits are safe

A Delta commit is the creation of `_delta_log/<version>.json`; two writers must never both create the same version. What the
code does, read from it:

| Writer | How it commits on S3 |
|---|---|
| `lake publish` and `lake maintain` (the Python `deltalake` package, 1.6.6 in the venv and the loader image) | a **conditional PUT** of the commit file (put-if-absent, `If-None-Match: *`): S3 refuses the second writer with 412, which re-reads the log and retries (`--commit-retries`, 20). deltalake 1.x no longer has the DynamoDB lock (its message: *delta-rs no longer supports S3DynamoDBLogStores*), so there is **no lock table**. `AWS_S3_ALLOW_UNSAFE_RENAME=true` is refused by `lake publish` |
| Drishti's Java writer (`lake build`) | local disk and EFS only (a hard link of a finished commit file); never S3 |
| Drishti's native Iceberg writer (`drishti-lakeio`) | the same conditional PUT, with a start-up probe |

`check-conditional-writes.sh` is that probe for the lake bucket. On top of it, the scripts take a lock per table on EFS
(`flock`, NFSv4 byte-range locks, released if a task dies), so a load, a backfill and maintenance of one table never overlap
in the first place. Loads of different tables run side by side.

#### 8.3 Backfill (`backfill.sh`)

```bash
deploy/aws/delta-lake/backfill.sh --from 2026-01-02 --to 2026-10-16 --skip-missing           # weekdays; dates without dumps skipped
deploy/aws/delta-lake/backfill.sh --from 2025-01-02 --to 2025-12-31 --from-dir /opt/drishti/data/history   # an export on EFS
```

Oldest date first, one `load-day.sh` per date. It stops at the first date that fails and prints the command that resumes from
there; re-running a date replaces it, so a backfill can always be run again. For a large backfill, run several loader tasks
side by side **on different tables** (the per-table lock keeps two on one table apart), each with `--tables`.

#### 8.4 Restatement

| A source re-sent | Run |
|---|---|
| some whole days | `backfill.sh --dates 2026-10-14,2026-10-15` |
| one product type of a day | `load-day.sh --date 2026-10-15 --tables trading/trade_endur --mode overwrite-partitions` (only the partitions in the dumps are replaced) |

Readers switch to the restated day at the commit; *known at* before the commit still shows the day as it was, for
`LAKE_VACUUM_HOURS`. Data files of an old day that is restated move to its storage tier at the next lifecycle run (the rules
are by date, not by object age), so a restatement of 2024 lands in Glacier IR, not in Standard.

### 9. Maintenance: checkpoints, retention, vacuum and storage tiers

#### 9.1 What `maintain.sh` runs

Nightly (`MAINTAIN_SCHEDULE`, Tuesday to Saturday 02:30), as the `maintenance` task, per table under the table's lock:

```bash
drishti.py lake maintain --config <lake-maintenance.s3.yaml rendered> --once --force-foreign --tables trading/trade_endur
```

then `setup-lifecycle.sh` (the tier rules of the coming months). The settings ([`deploy/aws/delta-lake/lake-maintenance.s3.yaml`](../../deploy/aws/delta-lake/lake-maintenance.s3.yaml)):

| Key | Value | What `lake maintain` does ([OPERATIONS.md](OPERATIONS.md#20-lake-maintenance-admin--lakes--maintain-and-lake-maintain)) |
|---|---|---|
| `keep-business-days` | `LAKE_KEEP_BUSINESS_DAYS` (1800, about seven years) | removes the business dates older than that, counted back from today: a delete by partition, one commit; refuses to remove more than `max-drop-share` (0.5) of a table |
| `checkpoint` | true | writes a checkpoint and removes commit files past `delta.logRetentionDuration` |
| `vacuum-hours` | `LAKE_VACUUM_HOURS` (168) | deletes files no version uses, older than a week; S3 keeps them 14 more days as noncurrent versions |
| `compact` | **false** | see 9.3 |

`--force-foreign`: the ownership rule of `lake maintain` maintains only tables `lake build` built in that location, and treats
every `s3://` table as foreign. The tables in S3 are Drishti's (`lake publish` wrote them from `lake build`'s output), so the
script overrides the rule; `lake maintain` prints its warning and its history marks the runs *forced*.

#### 9.2 Retention and the tiers together

```
 business date age:   0 ───────── 90 days ─────────────── 2 years ────────────────── LAKE_KEEP_BUSINESS_DAYS ──► removed
 storage class:       Standard    │ Standard-IA             │ Glacier Instant Retrieval │ by lake maintain (a Delta delete),
 (setup-lifecycle.sh)             │ (TIER_IA_AFTER_DAYS)    │ (TIER_GLACIER_IR_AFTER_DAYS)│ then vacuum, then noncurrent expiry
 read latency:        ms          │ ms                      │ ms                         │
```

Never Glacier Flexible Retrieval or Deep Archive: their objects need a restore of hours before a read, and Drishti reads any
day in milliseconds. Never an S3 expiry of current lake objects: removing a file a table version still names breaks the table
([DELTA_CONNECTOR.md section 10](../connectors/DELTA_CONNECTOR.md#10-keeping-the-lake-in-shape)); only `lake maintain`
removes table files.

#### 9.3 Compaction

Each day arrives laid out (files of `file-rows`, sorted, one commit), so the S3 tables need no compaction. A plain Delta
compaction would also merge files out of id order: `lake maintain` re-sorts (`relayout`) only tables whose folder is the
kind's name (`trading/trade`); for the trading pack's per-source tables (`trade_endur`) it finds no layout and would run a
plain compaction. So `compact` is off for S3. Restate a day that needs re-laying (runbook 13.4) instead.

#### 9.4 The lifecycle rules (`setup-lifecycle.sh`)

S3 lifecycle rules match prefixes and move objects on a **date** or at an **age**. Age is the time since the object was
written, which is wrong for a restated or backfilled day. So the script writes, per table, one rule per business month with
**dates**:

```json
{"ID": "tier-trading-trade_endur-2026-03", "Status": "Enabled",
 "Filter": {"And": {"Prefix": "lake/trading/trade_endur/business_date=2026-03", "ObjectSizeGreaterThan": 131072}},
 "Transitions": [{"Date": "2026-06-29T00:00:00Z", "StorageClass": "STANDARD_IA"},
                 {"Date": "2028-03-30T00:00:00Z", "StorageClass": "GLACIER_IR"}]}
```

A month's files move when the month's last day is 90 days old, and to Glacier IR at two years; a whole year already past two
years is one rule. Rules exist three months ahead, and `maintain.sh` rewrites them nightly. `_delta_log/` never matches, so
commits and checkpoints stay in Standard. Objects under 128 KB stay where they are (moving them costs more than it saves).
S3 allows 1,000 rules; the script refuses more (about 30 tables over seven years).

Costs to know: Standard-IA keeps an object at least 30 days, Glacier IR 90 days; a restatement of a tiered day pays the rest
of that minimum on the files it replaces. Reads from Standard-IA cost about USD 0.01 a GB and from Glacier IR about USD 0.03.

### 10. Caches: EFS or local NVMe

Drishti keeps small, bounded structures per table and day ([DELTA_CONNECTOR.md section 9](../connectors/DELTA_CONNECTOR.md#9-memory)):

| Cache | Where | Bounded by | After a restart |
|---|---|---|---|
| file list (snapshot) of each table | heap | the table's live files | re-read from the checkpoint |
| Parquet footers | heap (`-Ddrishti.parquet.footer-cache-groups`, 50,000 row groups) | row groups | re-read on first use (one ranged GET) |
| id maps of days | heap (`id-map-mb`, 20 % of heap, shared) and disk (`id-map-disk.dir`) | memory; one file per table version and date | **memory-mapped from disk: no scan** |
| column sets of days | heap (`columns-cache-mb`) | memory | re-read (the newest day in the background) |
| documents | heap (`doc-cache-mb`) and RocksDB on disk (`doc-disk-cache.dir`, `max-gb` 1, TTL 12 h, cleared daily 02:00) | size, age | **warm from disk** |
| type-ahead | heap (newest day; `index.days` more, `index.storage: mmap`) | days | rebuilt from id maps |

The disk caches default to `<data dir>/cache/<connector>/…`, which on ECS is `/opt/drishti/data/cache/…` on **EFS**.

| | EFS (default) | Local NVMe (instance store) |
|---|---|---|
| Latency | about 1 ms an operation (NFS); the page cache serves repeated reads of memory-mapped id maps | tens of microseconds |
| Throughput mode | **Elastic**: pays per GB read and written, follows the load; Provisioned only for a steady high rate | the disk's own |
| After a task moves host or zone | **warm**: same folders | **empty**: rebuilt as users read (correct, slower first reads) |
| Launch type | Fargate or EC2 | **EC2 only** (instances with instance store: `m6id`, `r6id`, `i4i`) |
| Cost | EFS Standard about USD 0.30 a GB-month plus Elastic throughput charges | included in the instance |
| The identical-path rule | satisfied | not: the cache path is on the host. The caches are rebuildable from the lake, so they may break the rule only with the product owner's decision ([section 2, its section 21](#21-open-questions)) |

To use NVMe: `register.sh --nodes --cache nvme` (EC2 launch type; the host's `/mnt/nvme/drishti/<node>` mounted at
`/opt/drishti/nvme`), then point the connectors' disk caches there:

```yaml
# /opt/drishti/config/connectors/trade-endur.yaml (on EFS), settings:
  id-map-disk.dir: /opt/drishti/nvme/cache/trade-endur/idmaps
  doc-disk-cache.dir: /opt/drishti/nvme/cache/trade-endur/docs
  doc-disk-cache.max-gb: "50"
```

Recommendation: start on EFS (warm after any restart, no host affinity); move the document cache to NVMe if Admin → Caches
shows cold reads dominate and EFS latency shows in `p99Ms` (step A4 of section 2 measures it).

### 11. Staying fast past 200 TB

#### 11.1 What never scans the whole lake

| Request | Reads | Grows with history? |
|---|---|---|
| Open one entity on a date | the table's snapshot (in memory, refreshed from the last checkpoint every 10 s), the day's id map (memory or disk), **one row group of one file** (one ranged GET, about 1 MB) | no |
| History of one entity over N days | the snapshot's `business_date=` partitions, then one single read per day held, eight at once | with N, not with the lake |
| Type-ahead | the newest day's ids in memory | no |
| Search, pick list, derived kind, impact on a date | that day's column set (`id` and promoted columns, never documents), files and row groups skipped by statistics | no |
| Load, restatement | one day's files and one commit | no |
| Retention | a delete by partition (the log only), one commit | no |
| Vacuum (weekly is enough) | a listing of the table's prefix | with the table's file count: about 1,000 objects per LIST call |
| Questions over all of history | not interactive: Athena, Trino or Spark on the same tables | — |

#### 11.2 Spreading the work over Drishti\*net

What is **built** today ([section 1, its section 10](#10-shared-and-separate-lakes)):

- Nodes whose connectors read **different tables** of a kind are **shards**: a search asks every one, a read goes to the
  node that holds the table. Give each node (or each pair) its own tables: `drishti-a` and `drishti-b` read `trade_endur`,
  `drishti-c` and `drishti-d` read `trade_calypso` and `trade_murex`. Each node then caches only its tables.
- Nodes that read **the same table** are **replicas**: a search asks one; among replicas a forwarded read goes to the same
  node for the same entity (a stable hash of its id), so that node's caches stay warm for it.
- Every node can read every table from S3, so a failed node costs speed, never answers.

**Planned (SUPER_SCALE steps S5 and S6):** ownership of (table, day, group) slices on the ring, so N servers on one table hold
N times one server's working set, and searches split over the owners.

#### 11.3 The numbers to watch

`report.sh` (scheduled daily, `REPORT_SCHEDULE`) prints them and sends them to CloudWatch. Its form, with illustrative
numbers for a lake of about 200 TB:

```
bucket acme-drishti-prod-lake (CloudWatch daily storage metrics):
  StandardStorage                         10412.3 GB
  StandardIAStorage                       46120.8 GB
  GlacierInstantRetrievalStorage         143998.1 GB
  objects                          672310

table                        version  checkpoint  behind   dates       newest   files GB(newest)    <32MB
trading/trade_endur             4512        4510       2    1764   2026-10-20     128      41.37        0
```

| Number | Healthy | If not |
|---|---|---|
| `behind` (commits since the checkpoint) | under 10 | a checkpoint failed: run `maintain.sh --tables …`; alarm `log-long` above 50 |
| files of the newest day, and `<32MB` | about rows ÷ `file-rows` per partition; small files 0 | many small files: the source sends many tiny dumps, or partitions are too fine (section 5.3) |
| live files per table | under about 100,000 | the checkpoint, and every refresh of the snapshot, grow with it: split the table (by source, or by year when step S2 lands) |
| S3 5xx | rare | `503 Slow Down`: reads concentrated on one prefix; spread with partitions; the alarm `lake-s3-5xx` |
| Admin → Health per connector: `p50Ms`, `p99Ms`, cold reads | a single read p95 under 300 ms on the newest days, under 1 s on any day | footers and id maps not warm (heap too small: Admin → Caches), or EFS latency (section 10) |
| Admin → Caches: the one memory budget | under its cap | raise the heap (8 GB per million entities a day), or shard tables over more nodes (11.2) |
| `LoadSeconds` | well inside the window | the dumps are large: more tables loaded in parallel (one loader task per table), or larger loader tasks |

#### 11.4 The CloudWatch alarms (`setup-alarms.sh`)

| Alarm | Fires when |
|---|---|
| `drishti-<env>-<table>-load-failed` | a load of the table failed (nothing of it was published) |
| `drishti-<env>-<table>-not-loaded` | no successful load for three days running (a weekend is two) |
| `drishti-<env>-<table>-load-slow` | a load took longer than `ALARM_PUBLISH_DEADLINE_HOURS` |
| `drishti-<env>-<table>-log-long` | readers replay more than `ALARM_COMMITS_SINCE_CHECKPOINT` commits |
| `drishti-<env>-lake-s3-5xx` | more than `ALARM_S3_5XX_PER_5MIN` server errors in 5 minutes, twice in 15 |
| `drishti-<env>-efs-io-limit` | EFS `PercentIOLimit` above 80 % |
| `drishti-<env>-<node>-down` | a node's service has no running task for 5 minutes |

All notify `ALARM_SNS_TOPIC_ARN`; the section 2 alarms (heap, Drishti\*net member dead, backup failed) come with
the platform's Prometheus or CloudWatch agent.

### 12. Sizing and cost

#### 12.1 What 200 TB is

Estimates (to be replaced by the measurements of SUPER_SCALE step S8), for 200 TB of Parquet kept seven years:

| | Value |
|---|---|
| Per business day (252 a year) | about 113 GB of Parquet; about 100 million trade documents at about 1.1 KB each compressed (Snappy) |
| Files per day | about 350 (330 MB each, 250,000 rows) over all tables |
| Live files after seven years | about 620,000 over all tables; with three trade tables, about 200,000 each: above the 100,000 of 11.3, so tables by year (S2) or more tables matter before the seventh year |
| Commits | one per table per day, plus maintenance: about 1,500 a year per table |

#### 12.2 The deployment

| Part | Size | Count |
|---|---|---|
| Server tasks | 4 vCPU and 12 GB for the server (8 GB heap) in a 6 vCPU, 16 GB task with its console; larger nodes (16 vCPU, 64 GB) at SUPER_SCALE volumes | 3 to 6, sharded by table (11.2) |
| Loader task | 4 vCPU, 30 GB, 21 GB ephemeral | one per table at load time, minutes to an hour a day |
| Maintenance and report tasks | the same image and size | nightly, minutes |
| EFS | each node's state and caches: 50–300 GB; the loader's scratch: one day of one table at a time (about the day's dumps plus its Parquet) | one file system |

#### 12.3 Monthly cost (list prices, one Region, estimates)

| Item | Basis | About |
|---|---|---|
| S3 storage, 200 TB tiered | 10 TB Standard (USD 23/TB), 46 TB Standard-IA (12.5), 144 TB Glacier IR (4) | USD 1,400 |
| S3 storage if all were Standard | 200 TB × 23 | USD 4,600 |
| S3 requests | 250 reads a second, two GETs each, USD 0.40 per million; loads and lists | USD 500–700 |
| Retrievals from IA and Glacier IR | month-ends, audits, history | tens to a few hundred USD |
| Versioning overhead | 14 days of replaced and vacuumed files | a few percent of storage |
| Server nodes | 3 tasks of 6 vCPU, 16 GB on Fargate, always on | USD 650–750 |
| Loader, maintenance | about an hour a day | USD 50–150 |
| EFS | 1 TB Standard, Elastic throughput | USD 300 plus throughput charges |
| CloudWatch, Secrets Manager, Cloud Map, endpoints, load balancer | | USD 200–400 |

For comparison, the same 200 TB on EFS: USD 60,000 a month in Standard, about USD 3,200 in Infrequent Access plus USD 0.03
per GB read (section 2).

### 13. Runbooks

#### 13.1 The daily load

| Check | How |
|---|---|
| It ran | CloudWatch Logs `/drishti/<env>`, stream prefix `loader`; `LoadSucceeded` 1 per table |
| It published | `report.sh --tables <table>`: `newest` is today; in the console, `TRD <id>` on today's date |
| Exit 2 (rejects) | the build summary in the log names the reasons; fix the source and restate the day (13.3) |
| Exit 1 | the log's line `lake build failed` or `lake publish failed` says why; the S3 table is unchanged; fix and run `load-day.sh --date <d> --tables <t>` |
| Exit 3 | maintenance or a backfill held the table's lock; the next run loads it, or run it by hand after |

#### 13.2 A backfill

1. `backfill.sh --dry-run --from … --to … --tables <t>`: check the dates and commands.
2. Run it as a loader task per table (`aws ecs run-task … --overrides` with the command `deploy/aws/delta-lake/backfill.sh …`).
3. It stops at a failing date with the resume command; fix and resume.
4. `setup-lifecycle.sh` (or the next `maintain.sh`) moves the backfilled years to their tiers.

#### 13.3 A restatement

1. The source re-sends the dumps under the landing prefix of the day.
2. `backfill.sh --dates <d>` (whole day) or `load-day.sh --date <d> --tables <t> --mode overwrite-partitions`.
3. Readers switch at the commit; the data-load signal (if set) refreshes servers at once.

#### 13.4 A slow table

| Symptom | Cause | Fix |
|---|---|---|
| first read of a day slow, then fast | id map not yet on disk; footers cold | normal; `warm-dates` (3) warms the newest days at start |
| every read of a day slow | the day is not sorted by id (load warning *written in the dumps' order*), or row groups are large | restate the day after fixing the order (7.2) |
| searches slow or `partial` | a field the search uses is not promoted | promote it in the pack; the table's columns change, so publish refuses the next day: write the new layout into a new table folder (`trade_endur_v2`) with `backfill.sh` (its tables file line pointing there), then switch the connector's `table.trade` |
| the snapshot refresh slow (Health) | many live files or a long log | `report.sh`: `behind` and file counts; run `maintain.sh`; split the table |
| `503 Slow Down` | one prefix overloaded | partition by `PRODUCT_TYPE` (or finer for that table); S3 scales a prefix within minutes |

#### 13.5 Disaster recovery

| | How |
|---|---|
| A table damaged (a wrong commit) | Delta time travel: the previous version is readable until vacuum; restate the affected days from the landing bucket (30 days) or from versions |
| Files deleted by mistake | S3 versioning: restore the noncurrent versions within `NONCURRENT_KEEP_DAYS` (`aws s3api list-object-versions`, `copy-object` of the version) |
| A Region lost | S3 Cross-Region Replication of the lake bucket, with Replication Time Control (most objects within 15 minutes), EFS replication and ECR replication ([section 2, its section 16](#16-disaster-recovery)). Replication copies objects one by one, so a commit can arrive before a data file it names: a read of that day in the recovery Region fails (Health `DEGRADED … a truncated or missing Parquet file`) until the file arrives, and the other days are unaffected. Fail over after the replication backlog (`ReplicationLatency`, `OperationsPendingReplication`) is empty |
| Node state lost | AWS Backup of EFS; caches rebuild from the lake by themselves |

### 14. The scripts

```
deploy/
├── docker/                       compose.yaml, compose.data.yaml, server.Dockerfile, console.Dockerfile (local and Docker hosts)
│   └── ecs/                      the ECS images: server.Dockerfile, console.Dockerfile, loader.Dockerfile and their entry points
├── aws/
│   ├── env.example               every setting; copy to deploy/aws/env (git-ignored)
│   ├── lib/common.sh             --help, --dry-run, --env, the printed AWS calls, templates
│   ├── delta-lake/
│   │   ├── setup-buckets.sh      step 2        ├── load-day.sh        8.1
│   │   ├── setup-iam.sh          step 3        ├── backfill.sh        8.3, 8.4
│   │   ├── check-conditional-writes.sh  step 4 ├── maintain.sh        9
│   │   ├── setup-lifecycle.sh    step 5, 9.4   ├── report.sh          11.3
│   │   ├── setup-alarms.sh       step 11       ├── lake-maintenance.s3.yaml
│   │   ├── tables.example        the tables    └── iam/*.json         the policies and trusts
│   └── ecs/
│       ├── setup-efs.sh          step 6        ├── register.sh        step 9
│       ├── setup-cloud-map.sh    step 7        └── templates/*.json   node, console, lake tasks; services; schedule target
├── grafana/                      the Grafana dashboard
└── lake-maintenance.yaml         lake maintain's example for a local lake
```

Every script: `bash` with `set -euo pipefail`, the AWS CLI v2 and nothing else (Python only through `tools/drishti.py`), `--help`,
`--dry-run` (each changing call printed as `+ aws …`, each read as `? aws …`, none made), settings from one file, safe to run
again. `tools/test_deploy_aws.py` runs every script with `--help` and `--dry-run` against a fake settings file with an `aws`
that fails if called, renders every template to JSON, and checks the lifecycle rules.

### 15. Limits and what comes next

- **Not yet run in AWS.** The first real run, in a development account, follows sections 4 and 13 and records what you
  should see here. section 2 steps A1 (images built and the read-only-root test) and A4 (EFS measurements) go with it.
- **A bucket without conditional writes** (`check-conditional-writes.sh` exit 1: an S3-compatible store, a proxy that drops
  the header): only one writer per table at a time. The per-table locks of the scripts already give that, as long as nothing
  else writes the tables.
- **`lake build` sorts a partition only when it fits a batch** (7.2).
- **One table holds every year** until tables by year (S2); watch live files per table (11.3).
- **Maintenance cannot re-sort per-source tables** (9.3): `compact` stays off; restate instead.
- **Ownership of slices, range maps, Bloom filters, products, the loader fleet** are planned (section 3).

## 4. Scale strategy: millions of trades, fast, in a bounded heap

**Status:** agreed direction, 2026-10-18. Phases 0 to 5 are built and measured (section 12); open items are listed there. Numbers marked
*measured* come from runs on 2026-10-18 (section 4); numbers marked *estimate* are to be replaced by phase 0.

Contents: 1 Goal · 2 Ground rules · 3 How Drishti reads today · 4 Measurements · 5 Memory: what lives in the heap ·
6 Storage and layout · 7 Read paths · 8 Caches · 9 Freshness · 10 Grouped pages: ids only · 11 Concurrency and
time limits · 12 Phases and status · 13 Settings reference · 14 Operations · 15 Risks · 16 Decisions · 17 Rejected

### 1. Goal

| | |
|---|---|
| Volume | ENDUR, CALYPSO and MUREX, each **300,000–600,000 trades a day**, each a **full daily dump** (any field may change daily) |
| Reference data | 10,000 counterparties, 30,000 netting sets and the like, every day |
| History | **at least one year** (about 250 business days), more later |
| Heap | **at most 8 GB** for the server, whatever the volume |
| Truth | the Delta lake, always. Everything else is a cache that can be thrown away and rebuilt from it. |
| Feel | a trade opens, a form search answers and a counterparty or netting-set page shows in under a second, on any date of the year |

Size of one year, a full partition per day (*estimate*, 1–2 KB per trade compressed): 3 systems × 500K × 250 =
**375 million rows**, about 0.4–0.8 TB of Parquet. Ordinary for Delta when every read names its date.

### 2. Ground rules

1. **Delta is the only truth.** Nothing beside it is a mirror of it. Anything kept beside it (RocksDB, memory-mapped
   files, the heap) must be rebuildable from Delta at any moment, keyed by the Delta table version it came from, and
   capped in size.
2. **Name the date, read one day.** Every read carries a business date and touches that day's partition only. A year
   of history costs disk, not speed.
3. **The heap holds ids and numbers, never documents in bulk.** A full JSON document is decoded for the one entity on
   the screen, and dropped. Anything that grows with the number of trades must hold, per trade, an id and a few
   numbers at most, and must be capped (section 5).
4. **Read columns for everything that is not one entity's page.** Searches, filters, lists, totals and BI read promoted
   columns.
5. **Compute when loading or in one pass, never one entity at a time in bulk.**
6. **Interactive work never waits behind background work.**
7. **Freshness follows the table version**, never a timer or a TTL.
8. **Never the system temp folder.** Temporary files go under `<data dir>/tmp` (done, section 12); the system temp
   folder can be a small in-memory file system.
9. **Every limit is a setting, every limit that bites says so** in the log and in Admin → Health, and a build that
   fails (out of memory included) fails cleanly; it never leaves readers waiting.

### 3. How Drishti reads today (2.3.0 plus the phase 1 fixes)

**One trade, given a business date and an id** (the trade page):

1. the date selects one partition folder (`business_date=…/PRODUCT_TYPE=…`); other days are not touched;
2. the connector's **id map** for that table and date (every id → its file, held in memory, built on the first read of
   the date) points at one file;
3. that file is sorted by id; the Parquet reader skips to the pages that can hold the id and decodes them; the trade is
   picked out. *Measured*: p50 about 0.1 s, p95 about 0.35 s on 300K trades, through the console.

**A form search over one day** (Forms → Trading): the promoted columns of that day (the id map's sibling, also held in
memory once loaded) are filtered in memory; no document is read.

**Grouped pages** (counterparty, portfolio, legal-entity, index trades: the `derived` connector `trade-pages`): built
from the trades of the date in **one pass** over each source's files, kept in memory per date, rebuilt when the data's
version changes (section 10 explains why this must become ids only).

**BI**: the BI store (Parquet columns and rollups on disk under `<data dir>/bi-store`, queried with DuckDB), each
partition rebuilt when the data version it was read from changes.

### 4. Measurements (2026-10-18)

<!-- 2.9.7 docs consolidation, phase 2: the scale MEASUREMENTS of this section move to RESOURCES_AND_SCALE.md (with PERFORMANCE and SCALE_BENCHMARK); the design stays here. -->

#### 4.1 The Windows demo machine, 300K real ENDUR trades, before the fixes

| Seen | Cause |
|---|---|
| Thousands of `FilterCompat` log lines a second after start | the grouped pages read up to 50,000 trades **one at a time**, each a Parquet read; the library logged every one (now at WARN) |
| `DRS-1004 timed out reading trade/CLY-3000020` | that rebuild held the 16 read slots; a trade page waited past the 2 s limit |
| Grouped pages incomplete | `max-scan` 50,000 of 300K+ |

#### 4.2 This machine, synthetic ENDUR-shaped trades (9.7 KB of JSON each)

| Test | Result |
|---|---|
| `lake build`, 303,780 trades, one day | **42 s**; files of 3K–60K trades by product type |
| Old writer layout | one Parquet row group per file (up to 60K trades); then 1 MB row groups, now 256 KB ([Row-group size](../connectors/DELTA_CONNECTOR.md#row-group-size)) |
| Single trade open, 300K, 4 GB heap (30 random ids, console) | p50 **0.09–0.14 s**, p95 **0.29–0.35 s**, 0 failures, also **during** a grouped-page rebuild |
| Trading form, 300K | 0.45 s |
| Grouped pages, 15K trades, one at a time (before) | **16.6 s** |
| Grouped pages, 15K trades, one pass (after) | **1.9 s**, the same pages |
| Grouped pages, 300K, full documents kept (before) | **out of memory** at 4 GB; readers then waited forever |
| Grouped pages, 300K, compact members, 8 GB | **out of memory** at about 250,000 trades |
| Grouped pages, 300K read, 100,000 kept (`max-stream`), 4 GB | **11.9 s**, heap **668 MB** after collection |
| A load (a restated date) while serving | grouped page updated within **about 20 s**, no restart, no signal |
| Trade documents: what makes them large | `LEGS` 7.7 KB (of which cash flows 2.6 KB), `EVENTS` 1.7 KB, everything else under 0.5 KB |

#### 4.3 The extreme case (2026-10-18)

ENDUR, CALYPSO and MUREX, 7 business days (2026-10-07 … 16), 313K–492K trades a day each: **8.2 million trades**
(ENDUR 2.82M, CALYPSO 2.66M, MUREX 2.71M; 1.0–1.3 million a day across the three); 10,000 counterparties and 30,000
netting sets a day. Synthetic documents of the real shapes (ENDUR 9.7 KB, CALYPSO 6.3 KB, MUREX about 14 KB of JSON).
Lake: 12.5 GB of Parquet. Server `-Xmx8g`, grouped pages `store: disk`. Times through the console (page rendered).

| What | Result |
|---|---|
| Load one system's day (`lake build`) | ENDUR 47–69 s, CALYPSO 50–69 s, MUREX 90–161 s (300K–500K trades) |
| Server start | 9 s; id maps of the three newest dates loaded in the background (≈0.3–0.5 s per table and date) |
| Grouped pages, latest date (1,163,754 trades, three systems) | built in **45 s** in the background, **complete**: 10,000 counterparty pages, 30 portfolios, 3 legal entities, 57 indices; 631 MB on disk |
| Heap | **0.85 GB** after the build, **1.16 GB** after the probes (several dates' id maps and columns), of 8 GB; no out of memory, no `DRS-1004` |
| Open one trade, latest date (25 random ids per system) | p50 **15–26 ms**, p95 **19–46 ms**, 0 failures |
| Open one trade, a past date (2026-10-08) | first read of the date **0.37 s** (its id map); then p50 **19–31 ms**, p95 **21–41 ms** |
| Counterparty page (reference), netting-set page | **13 ms**, **71–91 ms** |
| Counterparty trades page (`CTRD`, ~116 trades) | **0.15 s** |
| Portfolio trades page (`PTRD`, 20,000 listed trades, a 7.4 MB page) | **3.2 s**: the page lists 20,000 rows; paging the list is the fix (section 10) |
| Form search, one counterparty over 1.16M trades | **2.1–2.3 s** (target 1 s: section 7) |
| Purge and rebuild (`drishti.py server caches purge trade-pages`) | new pages built in 46 s from the lake while the old ones served; the old folder deleted a minute later |

#### 4.4 Concurrency on the extreme lake (2026-10-18)

Many users at once, straight against the server API (`-Xmx8g`, 24 cores): each user loops for 60 s over a mix of 80 %
single trades (random system, random date of the seven), 10 % counterparty-trades pages, 5 % netting sets, 5 % searches
(one counterparty's trades over 1.16M). Three rounds; the fixes between them came out of profiling the round before.

| Round | Users | Requests/s | Trade p50 / p95 | CTRD p50 / p95 | Search p50 | Failed |
|---|---|---|---|---|---|---|
| 1 (as built) | 10 / 50 / 100 | 76 / 110 / 83 | 0.07 / 0.21 · 0.29 / 0.55 · 0.76 / 1.28 s | 0.23 / 0.56 · 1.4 / 2.3 · 3.7 / 5.9 s | 0.43 · 0.40 · 0.46 s | 0 |
| 2 (+ pack access cached) | 10 / 50 / 100 | 65 / 66 / 66 | 0.06 / 0.54 · 0.07 / 3.0 · 0.16 / 5.0 s | 0.10 / 0.14 · 0.10 / 0.20 · 0.12 / 1.6 s | 0.59 · 0.65 · 0.69 s | 913 trades at 100 |
| 3 (+ Parquet footers cached) | 10 / 50 / 100 | **133 / 141 / 122** | **0.03 / 0.08** · 0.18 / 1.0 · 0.53 / 2.4 s | **0.10 / 0.16** · 0.26 / 1.0 · 0.52 / 2.3 s | 0.57 · 0.79 · 0.98 s | **0** |

What profiling found and what was fixed:

1. **Every entity of every page read the user and the user's pack preferences from the identity database** (SQLite,
   one access at a time) to decide whether the kind may be opened: the main bottleneck of round 1. Fixed: each user's
   effective packs cached for 5 s (`PackAccess`), cleared at once by the user's own choice, an administrator's change to
   the user, a pack loaded, unloaded, switched on or off, and the sample-pack setting.
2. **Every single read parsed its Parquet file's footer again**; with 1 MB row groups a file of 60,000 trades has about
   500 groups and a large footer: the bottleneck of round 2. Fixed: footers cached per immutable file (`ParquetBatches`,
   at most 50,000 row groups' worth; a purge of the connector clears them).
3. What remains is the data work itself: decompressing the pages that hold the trade (Snappy), then building the view.
   About 140 requests a second on this machine; latency grows with users beyond that (queueing, no failures). Next steps
   in section 12.

The same probes before the fixes of the day: grouped pages out of memory above about 250,000 trades at 8 GB, or partial
with a cap; with them, complete at 1.16 million a day in 1.2 GB of heap.

### 5. Memory: what lives in the heap

Each holder, what it grows with, and how it is bounded. The rule (section 2.3): per trade at most an id and a few
numbers, and a cap.

| Holder | Grows with | Per unit (*estimate*) | Bounded by | Status |
|---|---|---|---|---|
| Id map (table × date) | trades of a date | ~60 B per id | `id-map-mb` (1 GB per connector), least used dates dropped | fine; move cold dates to files (phase 2) |
| Columns of a date (promoted fields) | trades × columns | ~8–40 B per value | `columns-cache-mb` (1 GB per connector) | fine; cold dates to files (phase 2) |
| Recently read documents | documents read | ~10–50 KB each | `doc-cache-mb` (256 MB per connector) | fine; to RocksDB on disk (phase 2) |
| Type-ahead index | ids of the kept days | ~40 B per id | `index.max-memory` (600 MB), then files | fine |
| Loaded days for reverse links (`parts`) | trades of a day, **documents** | whole day | `cache-mb` 512 MB and `max-load-rows` 200,000 | used only for tables without promoted link columns; keep it so |
| **Grouped pages** (`trade-pages`) | with `store: disk`: **groups** (totals only); members and rows on disk | ~1 KB per group | the number of groups | **done** (section 10); `store: heap` keeps trades × row and is capped by `max-stream` |
| BI store reads | rows of a query | — | DuckDB, spilling to `<data dir>/tmp` | fine |

**The sum of the caps must fit the heap.** Today each Delta connector may cache up to about 2.8 GB, and there are a
dozen connectors: the caps add up to far more than any heap. They do not all fill at once, but nothing stops them.
Phase 1 gives the server **one memory budget** (a share of `-Xmx`) that every connector's caches draw from, so the
total cannot pass it. Until then: the defaults, `-Xmx8g`, and Admin → Caches to watch them.

**Out of memory, today:** with `store: disk` (the trading pack's default) the grouped pages no longer grow with trades.
With `store: heap` they do, capped by `max-stream`. A build that runs out now fails cleanly: it is logged
(`derived pages … ran out of memory: lower its max-stream setting or raise -Xmx`), Admin → Health shows the connector
DOWN with that reason, its pages answer "unavailable", and the rest of the server keeps working.

### 6. Storage and layout

- **A full partition per business date** (`mode: snapshot`). Storing only changed trades was considered and rejected:
  the dumps change too many fields every day (section 17).
- **Partitions**: `business_date` + `PRODUCT_TYPE` for trades; `business_date` for reference data.
- **Files**: sorted by id; at most `file-rows` (250,000) rows; small Parquet row groups (done; was one group per
  file), sized in bytes by the lake writer: 256 KB by default, 1 MB on AWS ([DELTA_CONNECTOR.md, Row-group size](../connectors/DELTA_CONNECTOR.md#row-group-size)).
  Takes effect for data loaded after the change; Admin → Lakes → Maintain (compaction) rewrites older files.
- **Promoted columns** cover every field a search, form, list, grouped page or BI table reads, including values
  inside arrays flattened at load time (for example the first leg's notional as `NOTIONAL`), so none of them needs a
  document.
- **Bloom filters on the id column** (phase 2): a cold date's single read finds its row group without an id map.
- **Maintenance**: Admin → Lakes schedules compaction, checkpoints and vacuum after loads (2.3.0).
- **Retention**: days kept on fast disk, and what happens to older ones, is a lake setting (decision 16.3).

### 7. Read paths and targets

| What | How | Target p95 | Today (*measured*) |
|---|---|---|---|
| Open one trade, any date | id map → one row group → one document | < 300 ms | 0.29–0.35 s at 300K |
| Form search, one day | promoted columns of that day | < 1 s over 1.5M | 0.45 s page at 300K |
| Counterparty, portfolio, legal-entity, index pages | ids and totals in memory; the trade list from columns (section 10) | < 500 ms | — |
| Netting set, counterparty (reference) | one document each | < 300 ms | — |
| Across dates (a trade's history, time series) | DuckDB over the Delta files of the dates asked for | < 2 s for a year of one trade | — |
| BI | BI store, rollups | < 3 s over a quarter | — |
| Load one day, one system (500K) | `lake build` | < 2 min | 42 s for 300K |
| First read of a date | id map and columns of that date | < 5 s for 500K | — |

### 8. Caches: three tiers, all disposable

| Tier | Holds | Where | Bounded by |
|---|---|---|---|
| **Hot** | id maps and columns of today and the dates being looked at; recent documents | heap | the one memory budget (section 5) |
| **Warm** | id maps and columns of other dates | **memory-mapped sorted files**, one per table version and date (as the type-ahead index already does) | `max-disk` per connector |
| **Documents** | full documents already read | **RocksDB** (`drishti-diskcache`, used by the Kafka connector), per connector, compressed | size cap, TTL, version purge, daily reset |

#### 8.1 The RocksDB document cache

- **Key**: `connector | table version (commit time) | business date | id`; value: the document as Delta holds it. The
  in-memory document cache uses this key already (`DeltaSourcePlugin.doc`).
- **Filled only by reads.** Never a bulk copy; never a mirror of Delta.
- **Limits, all automatic:**

  | Limit | Removes | Setting (proposed) |
  |---|---|---|
  | Version in the key | documents of a superseded table version; deleted by range when the version changes | — |
  | **TTL** | anything written more than the TTL ago (RocksDB FIFO TTL: whole files, dropped by compaction) | `disk-cache.ttl: 12h` |
  | **Size cap** | the oldest files (FIFO compaction, as today) | `disk-cache.max-gb: 5` |
  | **Daily reset** | everything, at a set hour | `disk-cache.reset-at: 02:00` |

- **No write-ahead log**: after a crash, or when the folder is deleted, it refills from Delta.
- Admin → Caches shows its size, cap, TTL and hit rate, and can purge it.

### 9. Freshness

The TTL limits disk, never freshness. A load is a new Delta commit, so a new table version, so new keys; readers look
up the current version only.

| | How far behind the data the screen can be |
|---|---|
| Single entities, searches, linked entities | about **10 s** after the commit (`refresh-seconds`), at most |
| With the load signal (Admin → Lakes, `--signal`, `lake demo`) | at once: the signal drops the caches |
| Grouped pages | about **10–20 s** (done: they follow the data's version, asked every `version-check`, 10 s) |
| BI reports and boards | the next query: each stored partition records the data version it was built from |
| The RocksDB TTL (`doc-disk-cache.ttl`, 12h) | no effect on freshness |
| A page already open in the browser | until reloaded (the page offers a refresh when a newer generation exists) |

### 10. Grouped pages: on disk (built 2026-10-18)

**Why they are in the heap today.** The `derived` connector was built for books of a few thousand trades. It builds
each group's page in advance (the totals and a row per member trade, including the trade's `EVENTS` for the payments
ladder) and keeps every page in memory, once per grouped kind (counterparty, portfolio, legal entity, index: four
copies of every trade's row). Memory is trades × 4 × row: several GB at 300K, whatever the heap.

**Built (2026-10-18), `store: disk`, the trading pack's default:** one pass over each source's day; each member's key
`kind | group | trade id` and its row (once per set of identical columns, shared by the four kinds) go to a sorted
store on disk (`SortedStore`, RocksDB, `<data dir>/derived/<connector>/<build>`); the heap keeps each **group's totals**
(count, sums, extremes, distinct values, the first member by id), never the members. A page is assembled when asked for:
its totals, then its members' ids and rows read from the store in id order (up to `max-members`). One folder per
computation, deleted when a newer one replaces it; the folder is emptied at start. Measured in section 4.3: complete at
1.16 million trades a day, 45 s, 631 MB on disk.

Still to do: the payments ladder totals computed in the pass (today a page sums its rows' `EVENTS`, which works because
the rows are on disk); paging the row list (a 20,000-trade portfolio is a 7 MB page); the trade list could later come
from promoted columns instead of stored rows.

**The earlier plan, ids only (kept for reference):**

| Kept in memory, per group | Read when the page opens |
|---|---|
| the member trade **ids** (sorted, shared between the four kinds: one id list per trade, not four) | the trade list: the members' **promoted columns** for just those ids (the columns of the date are already in memory for searches) |
| the **totals**: count, notional, MTM, distinct currencies and product types, last maturity | — |
| the **payments ladder** sums (next 7, 30, 90 days) and their event counts, computed per trade in the one pass from `EVENTS`, then summed per group | the events of one trade, when it is opened |

Per trade: one id reference per kind and a few numbers. 1.5M trades a day: about 50–100 MB, not GB.

**What changes:** the `derived` connector (members kept as ids; totals and ladders computed in the pass; the row list
served from columns); the four grouped-page Sutras read `$.PAY_7D`-style totals instead of summing
`$.TRADES[*].EVENTS`; the promoted columns of the trade table include every column the row list shows. Paging the row
list (a counterparty with 20,000 trades shows them a page at a time) comes with it.

### 11. Concurrency and time limits

- **Read slots**: single-entity reads take one of `max-concurrent-reads` (16) per connector. Background work now reads
  in one pass on its own thread and takes none (done for grouped pages). Phase 1 gives the rest of the background work
  (warm-up, BI refresh) its own slots.
- **Single read time limit**: `drishti.sources.fetch-timeout` **5 s** (done; was 2 s): the first read of a date waits
  for that date's id map.
- **Search and columns budget**: 20 s (`drishti.search.budget`, `columns-budget`).

### 12. Phases and status

<!-- 2.9.7 docs consolidation, phase 2: the scale MEASUREMENTS of this section move to RESOURCES_AND_SCALE.md (with PERFORMANCE and SCALE_BENCHMARK); the design stays here. -->

| Phase | Item | Status |
|---|---|---|
| **1. Quick fixes** | grouped pages read in one pass per source, not one trade at a time | **done** (2026-10-18) |
| | grouped pages keep computed values per trade, not documents | **done** |
| | grouped pages rebuild on a data-version change, not a timer | **done** |
| | an out-of-memory build fails cleanly (no reader waits forever) | **done** |
| | `max-stream` bounds the grouped pages (default 100,000) | **done** |
| | 1 MB Parquet row groups in the lake writer | **done** (new loads; compaction rewrites old files) |
| | single-read time limit 5 s | **done** |
| | quiet startup log (library INFO at WARN; one line per large table and date; progress every 100,000) | **done** |
| | temp files under `<data dir>/tmp` | **done** |
| | **grouped pages on disk** (section 10): complete at any number of trades, heap per group only | **done** |
| | Admin → Caches and `drishti.py server caches [purge NAME]`: grouped pages and caches dropped and rebuilt from the lake | **done** |
| | pack access cached per user (no identity database read per entity): 2–4× throughput under load | **done** |
| | Parquet footers cached per file (no footer parse per single read) | **done** |
| | grouped pages list 1,000 trades, the payments ladder over every member computed in the pass (`today()` set), 20,000-trade page 3.2 s/7.4 MB → 0.4 s/486 KB | **done** |
| | form pick lists cached per data version (server form 1.3 s → 0.2 s); long pick lists (over 300 values) found as you type instead of one checkbox each (form page 1.3 MB → 98 KB) | **done** |
| | one memory budget: Delta connectors without their own cache sizes share caches sized from the heap (day loads 5 %, id maps 20 %, columns 20 %, documents 5 %: half the heap in all) | **done** |
| | recent users cached 5 s in the identity store (each request read its user from SQLite); start-up warm-up on its own read slots | **done** |
| | console worker processes (`server.workers`, default 4): one Python process tops at ~40 pages/s | **done** |
| | **phase 1 integration test** (extreme lake, 8 GB): 16 functional checks pass; 50 users: server 146 req/s, console 69 req/s, 0 failures; heap 1.8 GB after collection | **passed** (2026-10-18) |
| | in-list paging (next 1,000 rows of a grouped page); console search under load (3 backend calls and a render per search: ~4 s at 50 users) | open |
| | the RocksDB document cache (section 8.1): **on by default**, one per connector (`<data dir>/cache/<connector>/docs`), 1 GB cap, 12 h TTL, hourly compaction, daily clearing, kept across restarts, skipped with a WARN when it cannot open; −17 % CPU per read for trades read before | **done** (2026-10-18) |
| | row groups of 256 KB and 64 KB pages in the lake writer: +13 % reads a second, −20 % CPU per first read (362,804 trades) | **done** |
| | one Parquet version (1.18.0) for every library (root `pom.xml`); the Parquet page index for single reads **on by default** (`drishti.parquet.page-index`): neutral on 256 KB row groups (812 vs 812 reads/s, same trades), it narrows reads of files with larger groups | **done** |
| | separate read slots for all background work | next |
| **2. Storage and caches** | id maps on disk, memory-mapped (`id-map-disk`): after a restart 3 ms instead of 729 ms per table date, first read of a past date 0.03–0.06 s instead of 0.24–0.34 s; 44 MB per trade connector | **done** (2026-10-18) |
| | grouped pages kept across restarts (`pages.json` beside the pages): served at once, no rebuild while the data is unchanged; 50 users straight after a restart 151 req/s, 0 failures (before: 395 timeouts) | **done** |
| | **phase 2 integration test** (extreme lake, 8 GB): two starts; heap 1.3 GB after collection | **passed** (2026-10-18) |
| | bloom filters on ids: not added (files sorted by id: statistics, the id map and the page index already find a trade); memory-mapped column sets: open (columns stay in the shared heap budget) | decided / open |
| **3. Computed at load** | flattened fields: the pack's `ingest.<kind>.flatten` (trading: `NOTIONAL: LEGS[0].NOTIONAL`, `INDEX_NAME: LEGS[0].INDEX.NAME`) adds each as a plain field of the document (never replacing one it has) and a promoted column, so searches, Sutras, grouped pages and BI read it without the document; `ingest.trade.key: TRADE_ID` makes `--key` optional. A table that exists keeps its columns: they appear in tables (re)built from scratch; a search over the three systems is a column read once every system's table has them | **done** (2026-10-18) |
| | grouped pages built at load: superseded by the pages on disk (phase 2), built in one pass when the data's version changes | done |
| **4. History** | a field's series over up to 260 business days and the diff between two dates (`/api/v1/history/{kind}/{id}/series|diff`, existing, any kind): with id maps memory-mapped (phase 2) a year costs disk pages, not heap; measured 0.02–0.12 s for 30 days, diffs 6–14 ms. DuckDB over Delta not needed for this. History queries (`GET /api/v1/history/{kind}/{id}`, [DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time)), `HistoryBench` at 10K trades × 30 business days on Delta, 200 trades, at the service: 30 days cold **13.2 ms** median (p95 19.1), warm **0.83 ms** (p95 1.6), cached answer **0.29 ms** | **done** (measured 2026-10-18; history queries 2026-10-20) |
| | BI monthly rollups: not added; rollups are per business date and a year reads about 250 small rollup files; revisit if a year-long BI question exceeds 3 s in the longevity tests | decided |
| **5. Operations** | Admin → Caches shows the shared memory budget (each shared cache's size against its cap, the heap); a sizing guide from the measurements ([SCALE_BENCHMARK.md section 8](SCALE_BENCHMARK.md)) | **done** (2026-10-18) |
| | Admin → Health → Pages: per kind (any pack) the views, p50, p95 and p99 over ten minutes, slowest first | **done** |
| | Admin → Health → Activity: requests a minute and the average response time, last 30 minutes, as a moving bar chart | **done** |
| | retention: `keep-business-days` per table in Admin → Lakes (since 2.3.0) | done |
| **0. Measure** | `tools/bench/trades.py` (synthetic trades of the real shapes, N a day per system for D days, counterparties and netting sets, loaded day by day) and `tools/bench/users.py` (simultaneous users, p50/p95/p99 per request kind), a small version in the tool tests ([SCALE_BENCHMARK.md section 9](SCALE_BENCHMARK.md)) | **done** (2026-10-18) |

### 13. Settings reference

| Setting | Where | Default | What |
|---|---|---|---|
| `refresh-seconds` | Delta connector | 10 | how often the table's log is checked for a new version |
| `lookback-days` | Delta connector | 10 | how far back a snapshot read looks for the newest partition on or before the date |
| `id-map-mb` | Delta connector | not set: shared, 20 % of the heap | id maps kept in the heap |
| `columns-cache-mb` | Delta connector | not set: shared, 20 % of the heap | columns of dates kept in the heap |
| `doc-cache-mb` | Delta connector | not set: shared, 5 % of the heap | documents kept in the heap |
| `cache-mb`, `max-load-rows` | Delta connector | not set: shared, 5 % of the heap; 200000 | whole days loaded for reverse links (tables without promoted link columns) |
| `id-map-disk.enabled`, `id-map-disk.dir` | Delta connector | true, `<data dir>/cache/<connector>/idmaps` | id maps memory-mapped from disk, one file per table version and date |
| `doc-disk-cache.enabled`, `.dir`, `.max-gb`, `.ttl`, `.reset-at`, `.zone` | Delta connector | true, `<data dir>/cache/<connector>/docs`, 1, 12h, 02:00, America/New_York | the RocksDB document cache (section 8.1) |
| `drishti.parquet.page-index`, `drishti.parquet.footer-cache-groups` | server (system properties) | true, 50000 | the page index for single reads; parsed footers kept, by row groups |
| `max-concurrent-reads` | Delta connector | 16 | single-entity reads at once |
| `warm-dates` | Delta connector | 3 | newest dates whose id maps load at start |
| `index.days`, `index.max-memory`, `index.storage`, `index.max-disk` | Delta connector | 1, 600MB, heap, 2GB | the type-ahead index |
| `store` | `derived` connector | `heap` (`disk` in `trade-pages`) | where the grouped pages live |
| `store-dir` | `derived` connector | `<data dir>/derived/<connector>` | the pages' only folder |
| `retire-after` | `derived` connector | 60s | replaced pages on disk kept this long for reads still running |
| `max-stream` | `derived` connector | **100000** | `store: heap` only: most members kept from a one-pass read; a WARN and DEGRADED say when it cuts |
| `version-check` | `derived` connector | **10s** | how often the data's version is asked; a new version rebuilds |
| `refresh` | `derived` connector | 30s (trading pack: 60s) | rebuild age, used only when a source cannot say its version |
| `max-scan` | `derived` connector | 50000 | members read one at a time, for sources that cannot read a day in one pass |
| `<kind>.max-members` | `derived` connector | 1000 | members listed on a page; the count and totals cover every member |
| `drishti.sources.fetch-timeout` | server | **5s** | longest single read (`DRS-1004`) |
| `DRISHTI_LAKE_ROW_GROUP_KB`, `DRISHTI_LAKE_PAGE_KB` | `lake build` (Java writer) | **256**, **64** | Parquet row group and page size ([DELTA_CONNECTOR.md, Row-group size](../connectors/DELTA_CONNECTOR.md#row-group-size)) |
| `ingest.<kind>.flatten`, `ingest.<kind>.key` | pack (read by `lake build`) | none | flattened fields written as plain fields and promoted columns; the id field, so `--key` is optional |
| `drishti.tmp-dir` / `DRISHTI_TMP_DIR` | server | **`<data dir>/tmp/server`** | temporary files (the console's: `<data dir>/tmp/console`) |
| `server.workers` / `DRISHTI_CONSOLE_WORKERS` | console | **4** | worker processes sharing the console's port |
| `-Xmx` | server JVM | 4g in the demo configuration | at most 8 GB (section 1) |

### 14. Operations

- **Hardware**: SSD, not a network share; 16–32 GB RAM for an 8 GB heap plus the operating system's file cache
  (*estimate*). On Windows, the data folder excluded from antivirus real-time scanning.
- **Order on a business day**: load the day (`lake build`, one system at a time or in parallel by system), maintenance
  (Admin → Lakes), then let the server pick the version up (10 s) or send the load signal.
- **Watch**: Admin → Health (connector p50/p99 and errors; DOWN with a reason), Admin → Caches (entries and sizes),
  the log lines `… loaded, N ids in F files (T ms)` and `derived pages … built in … s`, and any `WARN … has more than
  … documents`.
- **Disk**: the lake (about 1–3 GB a day for 3 × 500K, *estimate*), `<data dir>/tmp` (DuckDB spill; capped by the
  query limits), the BI store, the type-ahead segment files (`index.max-disk`), `<data dir>/cache/<connector>` (id maps,
  about 44 MB per trade connector for 7 dates; the document cache, at most `doc-disk-cache.max-gb`), `<data dir>/derived/<connector>`
  (grouped pages, about 0.55 KB a trade per cached date).

### 15. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Grouped pages run out of memory | their pages unavailable | done: pages on disk (`store: disk`); with `store: heap`, a clean failure and `max-stream` |
| The sum of cache caps passes the heap | long garbage collection, timeouts, out of memory | next: one memory budget; meanwhile defaults, `-Xmx8g`, Admin → Caches |
| First read of a date on a 500K table | a few seconds on that read | 5 s limit; `warm-dates`; phase 2 warm tier on disk keeps it after restart |
| A reload of a day while people read it | readers see the old version until the commit, then the new | by design (one commit per load) |
| Large documents (cash flows, events) | slower single reads | 1 MB row groups; promoted columns for everything but the trade page |
| The system temp folder is small (in memory) | the operating system runs out of memory | done: `<data dir>/tmp` |
| Antivirus scanning on Windows | every file read slower | exclude the data folder |

### 16. Decisions

1. **Hardware** for the year-scale server (SSD, RAM).
2. **Where the next steps go** in the delivery waves: grouped pages ids-only, the memory budget and separate read slots
   straight after the 2026-10-19 demo; phases 2–4 as a scale wave before or beside wave C.
3. **Retention**: days on fast disk, and what happens to older ones.
4. **Cache defaults**: TTL 12 h, 5 GB per trade connector for RocksDB (section 8.1).

### 17. Considered and rejected

| Idea | Why not |
|---|---|
| Store only the trades that changed (content hash against the previous day) | the dumps change too many fields every day; a full partition per day is fast when every read names its date |
| A RocksDB copy of the lake | two truths to keep in step; the cache must stay small and disposable |
| LMDB for the caches | fixed map size, no self-trimming, a new native dependency; RocksDB is shipped and trims itself |
| More heap | memory would grow with trades and days; the 8 GB limit is a goal, and holders must be bounded instead |
| Grouped pages that keep whole documents | out of memory at 300K (*measured*) |

## 5. Super scale: 500 GB of JSON a day for seven years (design, not built)

Status: **design, not built.** Release not decided (product owner, 2026-10-20). Builds on
[section 4](#4-scale-strategy-millions-of-trades-fast-in-a-bounded-heap) (one server, millions of trades a day: built and measured),
[section 1](#1-drishtinet-many-servers-one-drishti) (many servers, one Drishti: built in 2.5.0) and [section 2](#2-drishti-on-aws-ecs-efs-and-s3)
(ECS, EFS, S3: scripts in `deploy/`). What can be set up today for a lake in S3 past 200 TB, with the scripts, is
[section 3](#3-a-high-performance-delta-lake-on-aws-past-200-tb); it marks each piece of this design it relies on as planned. Every number is an **estimate** unless marked *measured*; step S8 replaces the estimates with
measurements.

**Super scale is the architecture at every size** (product owner, 2026-10-20): one code path that scales down (a laptop: one
node owns every slice) and up (many nodes), with external engines (Athena, Trino, Spark) as optional plug-ins. How every BI
query uses it (tiers chosen from metadata, products and rollups first, heavy work as bounded background runs split over slice
owners): see [BI_RUNS.md](../architecture/BI_RUNS.md) (release 2.6.7).

### 1. The ask

The product owner (2026-10-20): Drishti must serve **500 GB of JSON a day, kept for seven years**. The store is
**Delta Lake**, one **common lake in S3**, read by **several servers and several consoles** joined by **Drishti*net**.
Every user, on any console, sees one Drishti; any server can answer for any data.

Targets (section 4, its section 1, at fifty times its volume):

| | Target |
|---|---|
| Open one entity, today's business day | p95 under 300 ms |
| Open one entity, any of the 1,764 business days of seven years | p95 under 1 s (a cold day years back) |
| Form search over one day (50 million rows) | p95 under 2 s |
| Counterparty, portfolio, legal-entity and netting-set pages | p95 under 1 s, members paged |
| BI over a day / a month / seven years | seconds / seconds (rollups) / seconds (rollups) |
| The day's 500 GB loaded | within a 2-hour window after the last dump arrives; the day appears whole or not at all |
| Heap per server | **8 GB** (scale out, not up) |
| Truth | the Delta lake in S3; everything else is a product of the load or a cache, rebuildable from it |
| Users | 500 at once, each opening a page every few seconds (about 250 requests a second, peaks of 500) |

### 2. The size of it

500 GB of JSON documents a day. Trade documents measure 6.5–14 KB each (*measured*, section 4, its section 4.3),
about 10 KB on average; JSON compresses about 8 times in Parquet with ZSTD (*measured*: 0.7 KB per 6.5 KB trade,
ICEBERG_CONNECTOR.md section 3).

| | A day | A year (252 business days) | Seven years (1,764 days) |
|---|---|---|---|
| JSON from the sources | 500 GB | 126 TB | 882 TB |
| Documents (rows) | **50 million** | 12.6 billion | **88 billion** |
| Parquet: documents and promoted columns | about 65 GB | about 16 TB | **about 115 TB** |
| Products (section 8: link indexes, groups, rollups, id lists) | about 4 GB | about 1 TB | about 7 TB |
| Parquet files (about 250,000 rows each) | about 200 | about 50,000 | about 350,000 |
| Delta commits (one per slice, section 7) | about 150 | about 38,000 | about 265,000 |

Assumed mix (to be replaced by the real sources): three source systems; trades the large majority of documents;
reference data (counterparties, netting sets, books) a few hundred thousand documents a day; market data and risk
results sized like today's packs.

### 3. What breaks if nothing changes

| Built today (section 4) | Fine for | At 50 million a day |
|---|---|---|
| An **id map** per table and day: every id → its file, about 60 B per id, in the heap | 1–2 million a day | 3 GB per day: a server holds one or two days of 1,764 |
| A day's **promoted columns** in the heap: about 230 MB per million rows for 19 fields | 1–2 million a day | 11.5 GB per day: more than the heap |
| **Grouped pages** built by each server in one pass over the day: 45 s for 1.16 million (*measured*) | one server | about 30 minutes per server per day, repeated on every server |
| **One Delta table** per source and kind for all history | months | 350,000 files in one log: every snapshot lists them all |
| **One loader** per source system: about 50 MB of JSON a second (*measured*) | 3 GB a day | about 3 hours for 500 GB, and parallel loaders on one table need a safe commit |
| Servers on one lake are **replicas**: each caches whatever it is asked | a few servers | every server repeats the same work; adding servers adds no cache |
| **Type-ahead** ids of the newest days in memory, about 40 B per id | millions | 2 GB per day per server |

Each line has its answer: sections 9, 11, 8, 6, 7, 10 and 12.

### 4. Principles

1. **The lake is the truth; everything else is derived from it.** Products (section 8) are tables in the lake written
   by the loaders; caches are on the servers. Both are rebuildable from the documents.
2. **Compute when loading, not when asked.** Anything every server would compute for every user (grouped pages, link
   lists, totals, id lists) is computed once, by the slice that has the rows sorted in hand.
3. **Find by ranges, not by maps.** Files of a day hold sorted, disjoint id ranges; the Delta log already records each
   file's lowest and highest id. Nothing holds one entry per entity.
4. **Each server owns a share.** On one lake, the Drishti*net ring gives each slice of the data an owner; the cluster's
   memory adds up instead of repeating.
5. **Name the date, read one day** (section 4, its section 2): every interactive read touches one business day,
   except the history of one entity and rollups.
6. **The heap holds ids and numbers, never documents in bulk**, and is 8 GB whatever the volume.
7. **A day appears whole or not at all**, and a reload replaces it whole.
8. **Every limit is a setting, and every limit that bites says so** in Admin → Health.

### 5. The architecture

#### 5.1 The whole

```
  SOURCE SYSTEMS                     LOADING (batch window)                          THE LAKE (S3, one bucket)
  ┌────────────┐  dumps (JSON)   ┌─────────────────────────────┐   Parquet + commits ┌──────────────────────────────┐
  │ ENDUR      │───────────────► │ planner: the day's slices   │ ──────────────────► │ data/   trades, reference,   │
  │ CALYPSO    │  s3://…/landing │ loader tasks ×32 (ECS)      │                     │         market, risk …       │
  │ MUREX      │                 │   parse → promote → sort →  │ ──────────────────► │ products/ links, groups,     │
  │ reference  │                 │   write → products → commit │                     │         rollups, ids         │
  └────────────┘                 │ publisher: merge, register  │ ──────────────────► │ register/ days published     │
                                 └─────────────────────────────┘                     └──────────────┬───────────────┘
                                                                                                    │ ranged GETs
                                                                                                    │ (task roles, VPC endpoint)
  SERVING                                                                                           │
  ┌──────────────┐   HTTPS   ┌───────────────────┐                ┌─────────────────────────────────┴──────────────┐
  │ users        │ ────────► │ load balancer     │ ─────────────► │ consoles ×4 (a service; any console, any user) │
  └──────────────┘           └───────────────────┘                └───────┬──────────────┬──────────────┬──────────┘
                                                                          ▼              ▼              ▼
                                       ┌───────────── Drishti*net: gossip, ring, forwarding, scatter-gather ────────┐
                                       │  server A    server B    server C    server D    server E    server F       │
                                       │  each owns ~1/6 of the slices; caches only them; any server answers any read│
                                       └──────────────────────────────────────────────────────────────────────────────┘
                                                   │ caches and state per server: EFS (section 2)
```

#### 5.2 One request's path

```
 user opens TRD MX-20000017 on 2026-09-30
   console ──► any server (say B)
                B: slice of (trades/murex/2026, 2026-09-30, group 7) → owners on the ring: E (first), A (second)
                B ──forward──► E                                     (one hop; masked again on B for the user)
                               E: range map → file 0042 → footer (cached) → row group 3 → one ranged GET, 1 MB
                               E: document → its Sutra's data → back to B
                B ──► console ──► page
```

#### 5.3 Components and what is new

| Component | Today | New for this scale |
|---|---|---|
| Lake layout | one table per source and kind | tables per year; Bloom filters on `id`; disjoint id ranges guaranteed (section 6) |
| Loaders | `tools/load-delta.sh`, one process per source | a planned fleet of slice loaders, safe concurrent commits, a publisher and a day register (section 7) |
| Products | none (servers compute) | link indexes, group totals, rollups, id lists written with the data (section 8) |
| Single reads | id map | range map (section 9) |
| Drishti*net on one lake | replicas; any one answers | slices owned on the ring; forwarding to owners; split scans (section 10) |
| Searches and BI | a day's columns in the heap | split by owner; link indexes; rollups (section 11) |
| Type-ahead | ids in memory | the day's id list as a product, memory-mapped (section 12) |
| Caches | heap first | owned slices only; warm tier memory-mapped (section 13) |

### 6. The lake in S3

#### 6.1 Layout

```
s3://<org>-drishti-prod-lake/
├── landing/                                   the sources' dumps (JSON lines, gzip), kept 30 days
│   └── 2026-09-30/endur/trades-0001.jsonl.gz …
├── data/                                      the documents: one Delta table per source, kind and YEAR
│   ├── trades/endur/2026/
│   │   ├── _delta_log/                        commits, checkpoints (every 10 commits), _last_checkpoint
│   │   ├── business_date=2026-09-30/
│   │   │   ├── PRODUCT_TYPE=IRS_FIXFLOAT/
│   │   │   │   ├── part-00000-….zstd.parquet  ids ENDUR-0000001 … ENDUR-0250000   (sorted, disjoint)
│   │   │   │   └── part-00001-….zstd.parquet  ids ENDUR-0250001 … ENDUR-0498112
│   │   │   └── PRODUCT_TYPE=FX_FORWARD/ …
│   │   └── business_date=2026-10-01/ …
│   ├── trades/endur/2025/ …                   closed: compacted, checkpointed, vacuumed, read-only
│   ├── trades/calypso/2026/ …
│   └── counterparty/ref/2026/ …
├── products/                                  written by the loaders (section 8), same year split
│   ├── links/trade/COUNTERPARTY/2026/         (value, id, business_date, listed columns), sorted by value
│   ├── groups/counterparty-trades/2026/       one row per group per day: totals
│   ├── rollups/trading/2026/                  per day × grain: sums, counts, sketches
│   └── ids/trade/2026/                        the day's sorted id list (type-ahead)
└── register/                                  the day register (section 7.5): a small Delta table
```

#### 6.2 The table of documents

| Column | Type | Content |
|---|---|---|
| `id` | string | the entity's id; sorted within each file; files of a partition hold disjoint ranges |
| `doc` | string | the whole JSON document, exactly as the source sent it |
| `business_date` | date | partition |
| `PRODUCT_TYPE` (trades) | string | partition |
| promoted fields (`COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`, `CURRENCY`, `NOTIONAL`, `MTM`, …) | string / double | copies of the fields searches, forms, lists, links and BI use (the pack's `layout.<kind>.columns`) |
| `_slice` | string | the slice that wrote the row (section 7.1): a reload of a slice replaces exactly its rows |

| Property | Value | Why |
|---|---|---|
| File size | about 250,000 rows, about 330 MB | few files (about 200 a day), each one read only by ranged GETs |
| Row groups | 1 MB (*measured* balance on local disk; to be re-measured on S3, step S8) | one read decodes about 1 MB |
| Compression | ZSTD | about 8 times on documents |
| Statistics | min/max for `id` and every promoted column, in the Delta log and the Parquet footers | file and row-group skipping |
| Bloom filter | on `id` (Parquet split-block filter, about 1 % false positives) | a read for an id a file does not hold reads no data (section 9) |
| Checkpoints | every 10 commits; v2 checkpoints with sidecars when Delta Kernel supports them for reading | a snapshot reads the checkpoint plus a few commits |

#### 6.3 Tables by year

A year's table holds about 50,000 files and 38,000 commits; seven years in one table would hold 350,000 files, all of
which every snapshot of the table must list. The connector takes a pattern instead of a root:

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings:
  root: s3://acme-drishti-prod-lake/data/trades/endur/{year}     # {year} of the business date asked
  years: 2020-                                                    # the years that exist (or: listed from S3)
  engine: native
```

- A read names a business date, so it opens exactly one year's table.
- `inventory` and `latestDate` read the day register (section 7.5), not seven logs.
- **Closing a year** (first business day of January, by the maintenance job): compact the year's small files, write a
  final checkpoint, vacuum, then the table is read-only. Its log never changes again, so a server reads its snapshot
  once and keeps it.

#### 6.4 S3 request rates

S3 serves at least 3,500 writes and 5,500 reads a second **per prefix**, and the partitions are prefixes
(`…/business_date=…/PRODUCT_TYPE=…/`). A busy day is spread over its product-type prefixes; at 500 reads a second
(section 1) the load is far below the limits. A `503 Slow Down` is retried with jittered back-off by the native
engine's AWS client and counted in Admin → Health.

### 7. Loading 500 GB a day

#### 7.1 Slices

A **slice** is one source system × one kind × one product type (for trades) × one business day: the unit a loader
task writes and the unit a reload replaces. About 150 slices a day; the largest about 30–40 GB of JSON.

#### 7.2 The flow

```
 dumps arrive in s3://…/landing/2026-09-30/
      │  S3 event, or a schedule (Amazon EventBridge)
      ▼
 PLANNER (one small task)
   reads the day's manifest of dumps (or lists landing/), groups them into slices,
   writes the day's plan to the register: state PLANNED, slices, expected rows
      │
      ▼
 LOADER TASKS (an ECS task per slice, up to 32 at once; AWS Step Functions "Map" over the slices)
   ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
   │ read  : the slice's dump objects from landing/ (streamed, gzip)                               │
   │ parse : each line a document; LoadGuard: date checks, required fields                         │
   │ promote: the pack's promoted fields (layout.<kind>.columns), nested paths flattened            │
   │ sort  : by id, external sort, runs spilled to the task's EFS work folder (the AWS rule)        │
   │ write : Parquet files of 250,000 rows, 1 MB row groups, ZSTD, Bloom filter on id;             │
   │         disjoint id ranges, uploaded by S3 multipart                                          │
   │ products (same pass): link-index rows, group partials, rollup partials, the slice's id list   │
   │ commit: one Delta commit per table touched (replaceWhere the slice), safe concurrent (7.3)    │
   │ report: rows, files, bytes, id range, fingerprint → register (slice LOADED)                   │
   └───────────────────────────────────────────────────────────────────────────────────────────────┘
      │  every slice LOADED
      ▼
 PUBLISHER (one task)
   merges group and rollup partials into the day's rows; merges the slices' id lists;
   checks rows against the plan; writes the day PUBLISHED with its fingerprint
      │
      ▼
 servers see the published day at their next register poll (section 14) and warm their owned slices
```

#### 7.3 Safe concurrent commits

Many slices commit to the same table (one year of one source) at once. A Delta commit is the creation of the next
`_delta_log/<version>.json`; two writers must never both create the same version.

| Mechanism | How | Use |
|---|---|---|
| **S3 conditional writes** (`If-None-Match: *` on the commit file) | S3 refuses the second create of a version; the loser re-reads the log, checks its commit still applies (its slice is its own partition: it always does), and retries at the next version | the default; supported by S3 since 2024 and by delta-rs |
| **DynamoDB lock** (delta-rs's `dynamodb` locking provider) | a lock row per table serialises commits | S3-compatible stores without conditional writes |

```
 slice A                         _delta_log/                       slice B
   ├─ write data files             …                                 ├─ write data files
   ├─ create 000…0412.json  ──►  000…0412.json (A)                   │
   │                                                                 ├─ create 000…0412.json ──► refused (exists)
   │                                                                 ├─ read 0412, check: B's partition untouched
   │                                                                 └─ create 000…0413.json ──► 000…0413.json (B)
```

Retries are bounded (`commit-retries`, 20, with jittered back-off); a slice that cannot commit fails and is re-run.

#### 7.4 Throughput

| Step | Rate | For 500 GB with 32 tasks |
|---|---|---|
| Parse and promote | about 50 MB of JSON a second per task (*measured* single-loader rate, 3 GB in about 60 s) | about 5 minutes |
| External sort (spill and merge) | bounded by EFS throughput (Elastic: several GB a second across the file system) | 1 TB written and read: about 10–20 minutes |
| Write Parquet and upload | about 65 GB over S3 multipart | a few minutes |
| Commits | about 150, each under a second | seconds |
| Publisher | merges of thousands of group rows | a minute |
| **Total** | | **about 30–45 minutes** after the last dump arrives |

The window is bounded by when the dumps arrive, not by Drishti. More tasks shorten it; the planner caps them
(`max-tasks`) so S3 and EFS stay within limits.

#### 7.5 The day register

A small Delta table, one row per (kind, business day) and one per slice:

| Column | Content |
|---|---|
| `kind`, `business_date`, `slice` (empty for the day row) | the key |
| `state` | `PLANNED` → `LOADING` → `LOADED` → `PUBLISHED`; `RELOADING`; `FAILED` |
| `rows`, `files`, `bytes`, `id_min`, `id_max` | counts and range |
| `fingerprint` | a hash of the slices' file lists: the data version of the day |
| `tables` | the tables and versions the day's commits produced |
| `published_at`, `published_by` | when and which run |

```
           plan                 first slice starts         every slice LOADED, checks pass
 (none) ─────────► PLANNED ───────────────────► LOADING ──────────────────────────────► PUBLISHED
                                                  │ a slice fails after retries                │ reload asked
                                                  ▼                                            ▼
                                                FAILED ──── re-run the failed slices ───►  RELOADING ──► PUBLISHED (new fingerprint)
```

**Servers read a day only when it is PUBLISHED**, and read the fingerprint recorded there. During a reload the old
files stay readable (Delta keeps them until vacuum), and the servers keep serving the old fingerprint's files until the
new one is published: no half day, ever.

#### 7.6 Reloads and corrections

- **One slice** (a source re-sent its swaps): re-run that slice's task; its commit replaces its rows (`replaceWhere`
  on `_slice`); the publisher re-merges the day's products and publishes a new fingerprint.
- **A whole day**: the planner plans it again; same flow.
- **Late slices** (a source's dump arrives after the day was published): the day goes to `RELOADING` and is
  republished with the slice; the console shows the day's publication time.
- **LoadGuard** as today: dates checked, rows after tomorrow refused, a drop of more than half a table refused without
  `--force-drop`.

### 8. Products: computed when loading, not when asked

Each slice writes its products in the same pass that writes its documents; the publisher merges what must be merged
across slices. Servers read products like any table.

#### 8.1 Link indexes

For each promoted **link field** of a kind (a field that names another entity: counterparty, netting set, portfolio,
legal entity, index), a table sorted by the field's value:

| Column | Content |
|---|---|
| `value` | the linked entity's id (`CP-MERIDIAN-RE`) |
| `id` | the entity that links to it (`MX-20000017`) |
| `business_date` | partition |
| listed columns | the columns a list of such entities shows (product type, notional, MTM, maturity, currency): a covering index, so a list needs no document |

```
 products/links/trade/COUNTERPARTY/2026/business_date=2026-09-30/
   part-00000  values CP-AARDVARK … CP-FALCON     (sorted by value, then id)
   part-00001  values CP-FALCON-2 … CP-MERIDIAN
   part-00002  values CP-MERIDIAN-RE … CP-ZEPHYR
                       │
   "trades of CP-MERIDIAN-RE on 2026-09-30" = range map on value → part-00002 → row groups of that value
```

Each slice writes its rows sorted; the publisher does not rewrite them: a link table's day has one file set per slice,
and a lookup reads the matching range of each (about 150 small range reads, in parallel) — or, with
`links.merge: true`, the publisher merges them into a few sorted files (a few minutes, recommended for the most used
fields).

#### 8.2 Group totals

For each grouped kind (counterparty trades, portfolio trades, legal-entity trades, index trades; the `derived`
connector's pages today), one row per group per day:

| Column | Content |
|---|---|
| `group` | the group's id |
| `business_date` | partition |
| `members` | count |
| sums, extremes | notional, MTM, … (the page's totals) |
| distinct values | currencies, product types (small sets) |
| payments ladder | sums and counts of events in the next 7, 30, 90 days |
| `first_member` | the first member by id (the page's default selection) |

Slices write partials (per slice, per group); the publisher adds them up (sums add, extremes compare, distinct sets
unite, ladders add). A counterparty page reads its one row, and its member list from the link index, a page at a time.

#### 8.3 Rollups

Per model, per day, at the model's common grains (desk, portfolio, counterparty, product type, currency, and their
pairs the model declares): sums, counts, min and max, and **HyperLogLog sketches** for distinct counts (sketches add
across slices and days). BI over a range longer than `rollup-after-days` (5) reads rollups.

#### 8.4 Id lists

Per kind and day, the sorted list of ids (the slices' lists merged), written as a compact file: the type-ahead's source
(section 12).

#### 8.5 Product sizes

| Product | Per row | A day |
|---|---|---|
| Link indexes, five fields with listed columns | about 15 B compressed (sorted values compress well) | about 3.5 GB |
| Group totals | about 200 B | well under 100 MB |
| Rollups | about 100 B | well under 100 MB |
| Id lists | about 8 B (prefix-compressed) | about 400 MB |
| **Total** | | **about 4 GB** |

Products carry the day's fingerprint; a reload replaces them with the data.

### 9. Reading one entity

#### 9.1 The range map

Per (table, business day): the day's files with each file's `id` min and max, read from the Delta log's add actions
(`stats`), sorted by min. About 200 entries a day over all tables (about 60 for one source's trades), 100 B each.
A server keeps the range maps of the slices it owns (section 10): for seven years of all trades, about 35 MB in total,
about 6 MB per server. Range maps never change for a published day; a new fingerprint replaces the day's map.

#### 9.2 The read

```
  1. date → year → table                      (no I/O)
  2. register: the day is PUBLISHED            (memory, refreshed by the poll)
  3. range map: binary search on id → file    (memory)
  4. footer of the file: row groups' id ranges (cache; else one ranged GET of the footer, about 1–2 MB)
  5. Bloom filter of the row group: "maybe"    (in the footer's index; "no" ends the read: not on this day)
  6. one ranged GET of the row group           (about 1 MB, 30–80 ms in-Region, first byte 10–30 ms)
  7. decode the columns asked (id, doc), cut the document; document cache
```

| Step | Cold | Warm |
|---|---|---|
| Range map, register | memory | memory |
| Footer | 30–60 ms (one GET) | memory (footer cache, *measured* to double single-read throughput) |
| Row group | 30–80 ms (one GET) | page cache / document cache |
| Decode | 5–15 ms | — |
| View (Sutra, links, masks) | 10–30 ms | 10–30 ms |
| **Total** | **80–190 ms** | **15–40 ms** |

The id map of section 4 remains an option (`id-map: true`) for small tables on local disk, where it saves the
footer read.

#### 9.3 Effective kinds

For a kind stored only when it changes (`mode: effective`, reference data), "the entity as of date D" is its latest
row on or before D. Each day's id list (section 8.4) says whether a day holds the id; the server walks back day by
day through the id lists (memory-mapped, binary searches, microseconds each) and reads the first day that has it.
Reference kinds are small (hundreds of thousands a day), so their id lists for all days owned fit easily.

### 10. Many servers on one lake: slices and owners

#### 10.1 Slices of serving

For serving, a **serving slice** is (table, business day, group), where the group is one of `groups` (12) parts of
the day: a file's group is its index in the day's range map, modulo 12. A day of one source's trades (about 60
files) has 12 serving slices of about 5 files each.

#### 10.2 The ring

Drishti*net's ring (the donut of Admin → Net → Members) places each server at several points (virtual nodes, 64 each)
by the hash of its node id. A slice's owners are the first servers clockwise from `hash(table, day, group)`:

```
                         hash(trades/murex/2026, 2026-09-30, g7)
                                      ▼
                    A ●───────● C           owners of this slice: E (first), A (second, a hot day)
                  ●             ●
               F ●               ● B         newest `hot-days` (5) days: two owners
                 ●               ●           older days: one owner
                  ●             ●
                    D ●───────● E ◄─ first owner clockwise
```

| Days | Owners | Why |
|---|---|---|
| The newest `hot-days` (5) business days | 2 | most reads; a server's restart leaves a warm second owner |
| Older days | 1 | rare reads; any server can still read them (section 10.4) |

With N servers each owns about 1/N of the slices (2/N for hot days), so the cluster's caches add up: six servers hold
six times one server's working set.

#### 10.3 Routing

```
 request for (kind, id, date) arrives at server X
   slice ← (table of the date, date, group of the file holding id)    — X's register and range map (all servers keep
                                                                        the range maps of hot days; older days: section 10.5)
   owners ← ring(slice)
   X ∈ owners?   yes → read locally
                 no  → forward to owners[0]          (data forwarding, section 1 N4: masked there and here)
                        no answer within forward-timeout → owners[1] (if any) → X itself from S3
```

#### 10.4 Anyone can read anything

Ownership decides **who caches**, not who may read: every server has the lake's address and role. When owners are
down or slow, the asking server reads the slice itself from S3 (cold, correct). So a server's failure costs speed for
a moment, never answers.

#### 10.5 Range maps of old days

A server forwarding a read on an old day needs the file's group, which needs the day's range map. Old days' range maps
are small and immutable: every server keeps them for the days its users touch, loaded on demand (one Delta log read
per closed year, then the day's add actions from the year's final checkpoint, kept as a memory-mapped file). Or the
asking server forwards on `hash(table, day)` to a "day coordinator" that knows the groups: chosen in step S5 by
measurement.

#### 10.6 Joining, leaving and rebalancing

| Event | What happens |
|---|---|
| A server joins | it takes about 1/N of the slices (the ring's virtual nodes spread them evenly); it warms them from S3 on first use, or prefetches its hot slices' range maps, footers and id lists at once (`prefetch-hot: true`) |
| A server leaves or dies | gossip marks it dead (`dead-after`, 10 s); its hot slices' second owners already have them warm; its older slices go to the next server clockwise, cold for their first reads |
| A server is slow | forwarding times out to the second owner; Admin → Net shows the slow peer |
| Hot spot (month-end: everyone reads one day) | the day's 12 groups × 2 owners already spread it over the cluster; `hot-owners` (2) can be raised for the newest day |

#### 10.7 What changes in Drishti*net

| Today (2.5.0) | New |
|---|---|
| Two nodes with the same table identity are replicas: a search asks one | a table can be **owned in slices** (`ownership: sliced` on the connector): reads forward to the owner, searches go to each slice's owner |
| Capability digests name tables and latest dates | they also name the ring's version, so every node computes the same owners |
| Admin → Net → Members donut shows members | it also shows each member's share of slices and its forwarding |

### 11. Searches, lists, pages and BI

#### 11.1 A form search over one day

```
  search: trades where COUNTERPARTY = CP-MERIDIAN-RE and MTM < -1m on 2026-09-30
    │
    ├─ a link index exists for COUNTERPARTY → use it (11.2): one range, no scan
    │
    └─ otherwise: split by slices
         asking server ──► owners of the day's 12 groups (each group to its first owner)
             each owner: its files' promoted columns (warm tier, section 13), skipping files and row groups
                         whose min/max cannot match; filter; first `limit` rows by the order asked
         asking server ◄── partial results ── merge (rows by kind and id; order; cut to limit; totals added)
         partial and failed groups named (section 1 N5), as today
```

| Search | Expected cost |
|---|---|
| By a link field (counterparty, netting set, portfolio, legal entity, index) | under 300 ms (one range of the link index) |
| By promoted fields, the day split over six servers (about 8 million rows each) | 1–2 s cold columns, 200–500 ms warm |
| By a field that is not promoted | documents scanned up to `max-doc-scan` (20,000), answer marked `partial`; promote the field (a pack change and a reload) to make it fast |

#### 11.2 Lists and pages

| Page | Reads |
|---|---|
| Counterparty (reference) | one document |
| Counterparty trades (`CTRD`), portfolio trades (`PTRD`), legal-entity and index trades | the group's totals row (section 8.2) plus the link index range for the members, `page-size` (100) rows a page, with listed columns: no document |
| Netting set | one document, plus the netting set's trades from the link index |
| One trade's history over seven years | the trade's day list (id lists, section 8.4: which days hold it) then one read per day, 64 at a time | 
| Impact (F8: what depends on this) | link indexes of the entity's id across the kinds that link to it |

The 20,000-trade portfolio page (7.4 MB, 3.2 s, *measured*, section 4, its section 4.3) becomes its totals and its
first 100 members: under 1 s.

#### 11.3 BI

```
 BI query (model "trading", measure sum(MTM) by desk, 2026-09-01 … 2026-09-30)
   range longer than rollup-after-days (5)?
     yes → rollups/trading/2026, the 21 business days × grain desk → DuckDB on the asking server   (seconds)
     no  → split by slices: each owner aggregates its files' columns (DuckDB over Parquet columns),
           returns partial aggregates (sum, count, min, max; sketches for distinct counts)
           → the asking server merges                                                         (seconds)
```

- Measures that do not add (medians, percentiles, ratios of sums) are computed from what does add (sums and counts
  for ratios; t-digest sketches for percentiles, in the rollups when the model declares them).
- Row-level security and masks apply on each owner for the asking user, as every scattered request does today.
- A grain the rollups do not have falls back to the split path for ranges up to `max-split-days` (31); beyond that the
  answer says so and suggests adding the grain to the model's rollups.

#### 11.4 Questions over all 88 billion rows

Not interactive in Drishti. The same Delta tables are readable by **Amazon Athena**, **Trino** or **Spark**; Drishti's
SQL target can hand such a question to one of them and show the result when it is ready (minutes). Drishti's own
budget stays seconds.

### 12. Type-ahead

- Source: the day's **id list** product (section 8.4): sorted, prefix-compressed, about 400 MB for 50 million ids.
- Every server memory-maps the newest day's id lists (type-ahead must be local: its budget is 150 ms, too short for
  forwarding): about 400 MB of page cache per server, off the heap.
- Prefix search: binary search on the mapped file, then the next `limit` ids: microseconds.
- Past dates: the type-ahead index's past days (`index.days`) map those days' id lists the same way.

### 13. Memory and caches on a server

#### 13.1 The memory of a server (64 GB task)

```
 ┌──────────────────────────────────────────── 64 GB task ────────────────────────────────────────────┐
 │ JVM heap 8 GB                                                                                       │
 │   range maps (owned days) ~10 MB · register ~1 MB · footers of owned files ≤ 1.5 GB                 │
 │   hot columns of owned hot slices ≤ 3 GB · document cache ≤ 1 GB · requests, views ~2 GB            │
 │ off-heap ~8 GB                                                                                      │
 │   RocksDB block cache (document disk cache) 2 GB · DuckDB buffer pool 4 GB · Arrow, ZGC ~2 GB      │
 │ page cache ~44 GB (the operating system's; serves the memory-mapped files)                          │
 │   warm columns of owned days (memory-mapped) · id lists of the newest days · RocksDB files         │
 └─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

#### 13.2 The tiers

| Tier | Holds (owned slices only) | Where | Bound |
|---|---|---|---|
| Hot | range maps; footers; columns of owned hot slices; recent documents | heap | the one memory budget (section 4, its section 5) |
| Warm | columns of owned days as sorted column files, memory-mapped | data folder (EFS, per section 2) and the page cache | `warm-max-gb` per server (200) |
| Documents | documents already read | RocksDB document cache (section 4, its section 8.1) | size cap, TTL, version purge |
| Lake | everything | S3 | — |

A day's promoted columns are about 4–6 GB in the warm format for 50 million rows; a server owning 1/6 of a day (2/6
for hot days) holds about 1–2 GB of it per day, so its five hot days take about 8 GB of page cache, and older days come
in on demand.

#### 13.3 Warming

When a day is published, each server prefetches, for its owned hot slices: range maps (immediately), footers (in the
background, bounded), id lists (immediately, every server), and columns of the newest day (in the background, after
the footers). Users never wait for warming: a read of a slice not yet warm reads cold, correctly.

### 14. Freshness and consistency

- **The register drives everything.** Servers poll it every `register-poll` (10 s; one small Delta table read). A new
  PUBLISHED fingerprint for a day is the only signal that the day changed.
- **Caches are keyed by fingerprint**, not by table version: a commit of another day (the next slice, the next day)
  leaves every other day's caches in place.
- **Atomic switch.** When a day's fingerprint changes, a server serves the old one until its owned slices' new range
  maps are loaded, then switches; the old caches are dropped a minute later. A user sees the old day or the new day,
  never a mixture.
- **Across servers**, two servers may switch a few seconds apart; a page shows the fingerprint's publication time, so a
  user comparing two windows can tell.
- **Live overlays** (Kafka-fed intraday updates) stay as today: an overlay on top of the published day, per entity.

### 15. Seven years: storage tiers and retention

#### 15.1 Tiers

```
 age:  0 ──────────── 90 days ─────────────────────────── 2 years ───────────────────────────── 7 years ──► dropped
       │  S3 Standard  │  S3 Standard-IA / Intelligent-Tiering │  S3 Glacier Instant Retrieval       │
       │  read all day │  month-ends, audits, history          │  rare reads, still milliseconds     │
```

| Age | Storage class | Read latency | Storage, list price | Retrieval |
|---|---|---|---|---|
| Up to 90 days | S3 Standard | milliseconds | about USD 23 per TB-month | free |
| 90 days to 2 years | Standard-IA or Intelligent-Tiering | milliseconds | about USD 12.5 per TB-month | about USD 10 per TB read (IA) |
| 2 to 7 years | Glacier Instant Retrieval | milliseconds | about USD 4 per TB-month | about USD 30 per TB read |

**Never** Glacier Flexible Retrieval or Deep Archive for tables Drishti reads: their objects need a restore of hours
before a read.

#### 15.2 Rules

- Lifecycle transitions apply by **prefix and age** to data files only (`business_date=` partitions); the `_delta_log`
  folders, the register and the products' newest year stay in Standard.
- **No S3 expiry on any table's files.** Removing files under a live table corrupts it
  ([ICEBERG_CONNECTOR.md section 9](../connectors/ICEBERG_CONNECTOR.md#9-retention-and-maintenance) explains why).
  Retention past seven years drops the whole year's table (the year's folder), by the retention job, after the
  register marks the year expired.
- The landing folder expires its dumps after 30 days (they are not a table).
- **Vacuum** of open tables: weekly, keeping 7 days of removed files (so a reload's old files outlive any reader).

#### 15.3 Maintenance jobs

| Job | When | What |
|---|---|---|
| Compact small files | nightly, for days whose slices were reloaded | rewrite a day's files that fell below 50,000 rows |
| Checkpoint | every 10 commits (by the writer) | |
| Vacuum | weekly | open tables, 7-day retention |
| Close a year | first business day of January | compact, final checkpoint, vacuum; read-only |
| Retention | monthly | drop years past retention; the register marks them |

### 16. Consoles

At this scale the consoles are an **ECS service of their own** (the open question of section 2, its section 21,
answered here), scaled by users:

```
 load balancer ──► console service (4 tasks, auto-scaled on CPU and requests)
                     each: DRISHTI_BACKEND_URLS = two or three servers' Cloud Map names;
                           discovers the rest; keeps a session on one server while it answers
                     sessions: signed cookies; any console takes any user
                     state: its own EFS folders (data/console per task; nothing shared)
```

About 40 pages a second per console process (*measured*), 4 processes per task: four tasks serve about 600 pages a
second, above the target's peaks.

### 17. Failures

| Failure | Effect | Recovery |
|---|---|---|
| A server dies | its hot slices served by their second owners (warm); its older slices by the next server (cold for a moment); its users' consoles fall over | ECS restarts it (section 2); it takes its share back |
| A server is slow | forwards time out to the second owner; scattered searches name it in `failed` past `scatter-budget` | Admin → Net shows it; ECS health checks restart a stuck server |
| S3 throttles (`503 Slow Down`) | retried with back-off; reads slower | counted in Health; spread prefixes (section 6.4) |
| S3 Region trouble | reads fail | disaster recovery (section 2, its section 16; the lake bucket replicated) |
| A loader task dies | its slice stays `LOADING`; the day is not published | Step Functions retries the task (idempotent: `replaceWhere` on the slice) |
| A slice keeps failing | the day stays unpublished; the previous day is served; an alarm fires | fix the dump or the code; re-run the slice; or publish the day without it, marked partial, by an administrator's decision |
| A commit conflict storm | slices retry their commits | bounded retries; then the slice fails and is re-run |
| The register is unreadable | servers keep their last known register | alarm; a read-only copy of the register is in every server's data folder |
| A bad day published (wrong data) | users see it | republish the day from corrected dumps; or roll the day back: the register's previous fingerprint and Delta's time travel to the previous version of the day's slices |

### 18. Security at scale

- **Masks and roles twice**: every forwarded read and every scattered search is masked on the owner for the token's
  user and again on the asking server (section 1, its section 9), as today.
- **Row-level access** (a desk head sees only their desk) applies on each owner, before partial aggregates leave it,
  so a merged total never contains rows the user may not see.
- **Links and products obey the same rules**: a link index row is a row of its kind, filtered by the user's row-level
  access; group totals are computed per row-level partition where the model declares one (or computed on the fly from
  the link index for users with restricted rows).
- **S3**: SSE-KMS, bucket policies limited to the VPC endpoint and the Drishti roles; loader roles write, server roles
  read; access logs kept.
- **Lineage**: the register records which run published which day, from which dumps: governance shows it per day.

### 19. Sizing and cost

#### 19.1 The deployment

| Part | Size | Count |
|---|---|---|
| Servers | 16 vCPU, 64 GB (8 GB heap) | 6 (each owns about 1/6; hot days on two) |
| Consoles | 2 vCPU, 4 GB | 4, auto-scaled |
| Loader tasks | 4 vCPU, 16 GB | up to 32, during the batch window only |
| Planner, publisher, maintenance | 2 vCPU, 8 GB | on schedule |
| S3 | about 120 TB after seven years (documents and products) | growing about 70 GB a business day |
| EFS | servers' caches and state: about 200–300 GB a server; loader spill: up to 1 TB during the window | |

Throughput: about 130 single reads a second on 24 cores (*measured*, section 4, its section 4.4), so about 90 on a
16-vCPU server; six servers about 500 a second: the target's peak (section 1). More users: more servers (each added
server takes 1/N of the slices).

#### 19.2 Monthly cost, list prices (estimates, one Region, on demand)

| Item | After 1 year | After 7 years |
|---|---|---|
| S3 storage (tiered as section 15) | about USD 250 | about USD 900 |
| S3 requests (reads, loads, lists) | about USD 100–200 | about USD 100–200 |
| S3 retrievals from IA and Glacier IR (history and month-end reads) | tens of USD | about USD 100 |
| Servers (6 × 16 vCPU, 64 GB, always on) | about USD 3,500–4,500 | the same |
| Consoles (4 × 2 vCPU, 4 GB) | about USD 300 | the same |
| Loader fleet (32 tasks × about 1 hour × 21 days) | about USD 150 | the same |
| EFS (about 2 TB, Elastic throughput) | about USD 600–900 | the same |
| Load balancer, endpoints, logs, backups | about USD 300 | the same |
| **Total** | **about USD 5,500–7,000** | **about USD 6,500–8,000** |

Compute dominates; storage stays small even at seven years thanks to tiering. Savings Plans or EC2 capacity lower the
compute part by a third or more.

### 20. Observability

| Signal | Where |
|---|---|
| Per day: plan, slices' states, rows, publication time and fingerprint | Admin → Lakes → Days (new), from the register |
| Per server: owned slices, warm share, forwarded in and out, cold reads | Admin → Net → Members (the donut, with shares) |
| Per slice: reads, cold reads, p50/p95 | Admin → Caches, by table and day |
| S3: GETs, bytes, `503`s, latency | Admin → Health; CloudWatch S3 request metrics |
| Loading: tasks, throughput, retries, commit conflicts | Step Functions and the planner's metrics; Admin → Lakes → Days |
| Alarms | day not published by its deadline; a slice failed twice; a server dead for 5 minutes; cold-read share above 50 % for 10 minutes; S3 `503`s above 1 % |

### 21. Settings (proposed)

| Setting | Default | Meaning |
|---|---|---|
| connector `root` with `{year}` | — | tables by year (section 6.3) (planned) |
| connector `years` | listed | the years that exist (planned) |
| connector `ownership` | `replica` | `sliced`: owned in serving slices on the ring (section 10) (planned) |
| `drishti.net.slices.groups` | 12 | serving slices per table and day (planned) |
| `drishti.net.slices.hot-days` | 5 | newest days with two owners (planned) |
| `drishti.net.slices.hot-owners` | 2 | owners of a hot slice (planned) |
| `drishti.net.slices.virtual-nodes` | 64 | points per server on the ring (planned) |
| `drishti.net.slices.prefetch-hot` | true | warm owned hot slices when a day is published (planned) |
| `drishti.register.uri` | — | the day register's table (planned) |
| `drishti.register.poll` | 10s | how often the register is read (planned) |
| `drishti.sources.id-map` | false at this scale | the id map of section 4, for small tables (planned) |
| `drishti.cache.warm-max-gb` | 200 | warm tier per server (planned) |
| `drishti.bi.rollup-after-days` | 5 | BI ranges longer than this read rollups (planned) |
| `drishti.bi.max-split-days` | 31 | longest range answered by a split scan (planned) |
| loader `max-tasks` | 32 | loader tasks at once (planned) |
| loader `commit-retries` | 20 | commit retries per slice (planned) |
| loader `links.merge` | false | merge a day's link index files at publish (planned) |

### 22. Build plan

Each step is useful on its own and measured before the next.

| Step | Content | Done when |
|---|---|---|
| S1 | **Range maps and Bloom filters**: reads find their file from Delta's per-file id statistics; loaders write Bloom filters on `id`; the id map optional | one entity on a 50-million-row day with no id map, within target (correctness locally at 10,000 trades; speed in the environment of S8) |
| S2 | **Tables by year**: the `{year}` root pattern, closing a year | a date in any year read; a year's log stays small |
| S3 | **The loader fleet**: planner, slice loader, safe commits (conditional writes; DynamoDB), publisher, register, reloads | 500 GB loaded by 32 tasks within the window; a killed task re-run; no half day ever read |
| S4 | **Products**: link indexes, group totals, rollups, id lists; the `derived` connector and BI read them | the CTRD, counterparty and portfolio pages and BI over a year answer from products |
| S5 | **Slices and owners**: serving slices on the ring, forwarding to owners, failover, Admin → Net shares | three servers, each warming only its share; one stopped and the reads continue |
| S6 | **Split searches and aggregates** across owners, merged | a day's search over six servers equals the single-server answer, row for row |
| S7 | **Warm tier**: memory-mapped column files of owned days; the one memory budget | a server with 8 GB of heap serves its share of 1,764 days |
| S8 | **Benchmark in AWS**: a generator of synthetic documents of the real shapes, 1/10 scale (5 million a day for 30 days), then full scale for 10 days; every estimate here replaced by a measurement | the targets of section 1 met, or the gap stated with its fix |

The local machine stays at its limit of 10,000 trades; every step is proven for correctness locally and for scale in
the AWS environment of step S8.

### 23. Tests

| Test | Proves |
|---|---|
| Range map reads equal id map reads, every entity of a fixture lake | S1 correct |
| A Bloom filter miss reads no data (counted GETs) | S1 efficient |
| Concurrent slice commits on one table (MinIO with conditional writes; the DynamoDB lock against DynamoDB Local) | no lost commit; every slice's rows present once |
| A reader polling during a reload never sees two fingerprints in one answer | a day appears whole |
| Products equal the same numbers computed from the documents | products correct |
| Ownership: every slice has the expected owners for a given ring; one server removed moves only its slices | the ring |
| Split search and split aggregate equal the single-server answer | S6 |
| Masks and row-level access on split aggregates: a restricted user's totals equal the totals of the rows they may see | security |
| Failure drills (S8): a server killed mid-load of users; S3 throttling injected; a loader killed | section 17 |

### 24. Risks and open questions

- **Searches without a link index** over 50 million rows cost seconds even split six ways; the fields users search
  most should get link indexes (a pack setting): which fields, decided with the users.
- **History across years** reads one cold row group per day: fast for one entity, not for thousands at once (that is
  Athena's job, section 11.4).
- **Delta Kernel's support for v2 checkpoints** decides whether a year's snapshot stays cheap without tables by year;
  tables by year make it safe either way.
- **EFS for loader spill** (the AWS rule): about 1 TB written and read per day; Elastic throughput handles it, at a
  cost (section 19.2); whether the loaders' scratch may use task-local storage instead is the product owner's decision.
- **Who writes the products**: Drishti's loaders (this design), or the bank's own pipeline (Spark, Glue) writing
  Drishti's layout. The layout is documented either way.
- **Open for the product owner**: the release; the source systems' real document sizes and counts (to replace the
  assumed mix of section 2); the link-index fields; the console service versus one console per server.
