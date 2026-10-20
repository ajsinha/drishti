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

Status: **being built for 2.5.0** (wave N); what is built, and what changed from the design, is in [section 19](#19-as-built). Planned for **2.5.0**, a release of its own built straight after 2.4.0 and then fully tested (wave N, decided by the
product owner on 2026-10-18; see
[BI_DESIGN.md §12.2](BI_DESIGN.md#122-the-three-waves)). Off by default when built. Names marked **(new)** do not exist
in the code today; every other class, file or setting named here does.

## Contents

1. [The ask](#1-the-ask) · 2. [The idea in one picture](#2-the-idea-in-one-picture) · 3. [Principles](#3-principles-and-non-goals) ·
4. [What the code already gives us](#4-what-the-code-already-gives-us) · 5. [Membership: gossip over HTTP](#5-membership-gossip-over-http) ·
6. [What a node offers](#6-what-a-node-offers-the-capability-digest) · 7. [Routing a request](#7-routing-a-request) ·
8. [Operations across nodes](#8-operations-across-nodes) · 9. [Identity and security](#9-identity-and-security) ·
10. [Shared and separate lakes](#10-shared-and-separate-lakes) · 11. [State that belongs to a user](#11-state-that-belongs-to-a-user) ·
12. [Failure](#12-failure) · 13. [Admin, management and observability](#13-admin-management-and-observability) · 14. [Configuration](#14-configuration) ·
15. [APIs](#15-apis) · 16. [Build plan](#16-build-plan) · 17. [Tests](#17-tests) · 17A. [Documentation](#17a-documentation-part-of-every-phase-not-after-it) · 18. [Risks and open questions](#18-risks-and-open-questions) · 19. [As built](#19-as-built)

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

A pack listed in `peer-only-packs` (`node.yaml`) is loaded and served to peers (view and data forwarding, search shares) but hidden from
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
   The view's linked entities are read so too, within routing.yaml's `link-budget` (1 s) when they are on a peer.

**Choosing among peers** that can answer: alive (not suspect) → holds the date asked (its `latestDate` and coverage) →
freshest data version → lowest recent latency → for a tie, a stable hash of the entity id (so the same entity goes to the
same peer and its caches stay warm). A request that fails on one peer is tried once on the next (reads only), within the
caller's time budget.

## 8. Operations across nodes

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

## 9. Identity and security

- **Cluster membership is authenticated.** Gossip, digest, fetch and forwarded calls need a cluster credential: an HMAC
  over method, path, timestamp and body with the cluster key (`secret` in `config/net/node.yaml`, from the environment), refused outside
  a 60 s window (replay), or mutual TLS with the shared `tls.*` settings. A node without the key cannot join or read.
- **The user is carried, not trusted blindly.** A forwarded call carries a short-lived on-behalf-of token **(new)**:
  the user, roles, embed app if any, the original node and request id, signed with the cluster key, valid 30 s. The peer
  checks it, and also checks the user exists and is enabled when `registered-users-only` is on (the nodes copy users, roles
  and API tokens to each other, section 19 *Identity across nodes*, or share one identity database, so that is true everywhere).
- **Masks twice.** The peer redacts as it would for that user; the asking node, which renders, applies its own masks again.
  The stricter configuration wins; an administrator sees on Admin → Net when two nodes' `redact` lists differ.
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

## 10. Shared and separate lakes

A Delta table is identified by its table id and location, a `file` connector's kind by its folder and the fingerprint of its
files. Two nodes whose digests name the same table identity read the
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
once. Replicating the rest (or moving it into the shared database) is phase 7. As built (section 19, N7), the user's documents, sessions and
identity (users, roles, API tokens) are all copied between nodes, so there is no home node to lose.

### 11.1 The console and several servers (new)

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
  dropped from the list; the configured `urls` are never dropped.
- **One active server, the rest standing by.** The console sends its requests to the first healthy server (its user's
  *home node*, section 11): the configured ones first, then the discovered ones, nearest first by measured latency. Each
  server in the list is health-checked every `check-every`.
- **Falling over.** A server that refuses connections, times out or answers 502/503 `fail-after` times in a row is set
  aside, and the console moves to the next healthy one. A **read** that failed on the way is retried once on the new
  server, so the person sees the page, not an error; a **write** (save a layout, a search, a pivot) is never retried
  automatically: the page says it did not save and offers to try again.
- **Nothing to sign in again.** Every node accepts the same tokens (one token secret or OIDC issuer, section 9), so the
  session carries over. Live streams (server-sent events) reconnect to the new server by themselves and resubscribe.
- **Coming back.** A set-aside server is checked on; once healthy for a minute it is used again for new sessions (not
  moved under a running session, so caches and streams stay put).
- **Visible to administrators.** Admin → Net and Admin → Health show which server the console is on, the list with each
  one's state, and the fail-overs over the last hour; each fail-over is a log line and an audit row.
- **Without Drishti*net** `backend.urls` still works as a plain list of identical servers (same packs, same lakes), but
  answers are only the same on each when they hold the same data; with Drishti*net any server answers for all.

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

## 13. Admin, management and observability

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

## 14. Configuration

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
| `node.yaml` | this node: `enabled` (false), `node-id` (empty: generated once, kept in `<data dir>/net/node-id`), `advertise-url`, `tags`, `peer-only-packs`, the cluster key as `secret: ${DRISHTI_NET_SECRET}` and `previous-secret: ${DRISHTI_NET_PREVIOUS_SECRET}` (environment references only, as connector passwords are), `tls.*` for mutual TLS | applied live; `enabled` false → true joins, true → false leaves cleanly |
| `peers/<name>.yaml` | one file per seed peer, the file name its logical name: `url`, optional `tags` it is expected to carry, `tls.*` overrides | a new file is contacted at once, a deleted one is no longer a seed (gossip may still know the node) |
| `gossip.yaml` | the protocol's timings: `interval` (1s), `fanout` (3), `suspect-after` (5s), `indirect-probes` (2), `dead-after` (30s), `forget-after` (24h), `persist-every` (10m) | applied on the next round |
| `routing.yaml` | `forward-timeout` (5s), `scatter-budget` (3s), `suggest-budget` (150ms), `max-hops` (1), peer choice weights | applied to the next request |
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
(`127.x.x.x`, `::1`) or a wildcard address (`0.0.0.0`, `::`) is refused by the schema (the server at load, Admin → Net → Files,
`drishti.py net check|set|add-peer`), with a message naming this machine's address to use. Without `advertise-url` the node advertises
this machine's primary IPv4 address with `server.port` (`HostAddress`: the interface of the default route, else the first interface that
is up and is not a loopback, a container bridge or a virtual link); a machine with no other address refuses `enabled: true` without one.
There is no escape hatch: nodes on one machine, the in-process tests (`NetNodes`), `net_stack.py`, the soak and `drishti-stack/` all use
the machine's real address (the tests skip, with that reason, on a machine that has none). The console's own `backend.urls` may still
name 127.0.0.1 (its choice, not an advertisement); what it discovers never does, and a member that moved address replaces its old one.

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
| N1 | Membership: gossip, probes, states, `peers.json`, the `config/net/` folder (watched, validated, `drishti.py net`); Admin → Net *Members* (add seed, remove, drain, leave, rejoin) and *Files* | 5 nodes started from 1 seed all list each other within 5 s; one killed is `dead` everywhere within 40 s; a restarted node rejoins from its file with no seeds |
| N2 | Capability digests and the capability table; Admin → Net *Capability map* | a pack switched on at one node shows in every node's table within 2 s |
| N3 | Security: cluster key or mTLS, key rollover, on-behalf-of token, masks twice, audit; Admin → Net *Access* and *Security* | a node without the key is refused; a rolling key change drops no request; a role limited to two nodes never reaches a third; a masked field stays masked when the data comes from a peer whose `redact` is looser |
| N4 | View forwarding and data forwarding (`PeerSource`), newest pack wins, peer-only packs; the console's `backend.urls` with health checks, discovery and fail-over (11.1); Admin → Net *Routing* (explain a request, traffic) | the console on B opens a trade only A holds, and a kind only A's pack renders, with no visible difference; with a newer pack on A, B's users see A's version; a peer-only pack is invisible to B's own users but served to peers; a peer down gives a clear error, not a wrong page; with the console configured with A alone, it learns B and C within a minute, and stopping A moves it to B within 10 s and the open page, a live stream and the next search carry on without signing in again |
| N5 | Scatter-gather search, type-ahead and grouped pages; replicas versus shards | a search on B across trades held on A and C equals the union, with duplicates removed and `failed` naming a stopped node |
| N6 | Live relay; BI queries routed to the model's node | a ticking entity held on A streams to a viewer on B; a BI report on B over A's model answers |
| N7 | User state across nodes | saved searches, layouts and pivots follow a user to another node after a fail-over |

All of N1–N7 are planned for 2.5.0, in this order; N1–N5 come first and are the minimum for the release.

## 17. Tests

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
- **A soak on real processes:** three servers on scratch ports, one killed and restarted under `tools/bench/users.py` load.

## 17A. Documentation (part of every phase, not after it)

Each phase ends with its docs written and checked against the running code (commands and outputs captured on a fresh
stack, pictures of the real pages), as the product owner asked:

| Document | What it gets |
|---|---|
| `docs/admin/NET.md` (new) | the operator's guide: set up a cluster of three, the `config/net/` files one by one, key rollover step by step, drain and maintenance, a node leaving, reading Admin → Net, sizing |
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

## 18. Risks and open questions

- **Decided (product owner, 2026-10-18):** (1) when nodes hold different versions of a pack, the **newest version is served**
  (view forwarding to a node that has it, even when this node could answer with its older version; equal versions with
  different content hashes are flagged on Admin → Net); (2) the cluster key **rotates without downtime** (two keys accepted
  during a rollover, section 9); (3) a node **may host a pack only for its peers** (`peer-only-packs`, section 6).
- **Latency of scatter-gather** grows with the slowest node; budgets and partial answers bound it.
- **Cache coherence:** each node caches what it fetched from peers; data versions in the digest invalidate them, a
  `knownAt` read bypasses them.
- **Decided (product owner, 2026-10-18):** (4) a search, type-ahead or forwarded request goes **only to nodes the
  user's role may reach** (section 9).

## 19. As built

Each phase as it was built, with anything that differs from the design above. The operator's guide is [NET.md](../admin/NET.md).

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
filter and merged on the asking node. The type-ahead asks every reachable node within `suggest-budget` and interleaves the answers. Measured on trades
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
  nodes hold is then counted on each; documented in NET.md).
- A partial page says so in its document (`partial`, `missing` naming the node), as a search does in its answer.

**The node that answered (built, after N7).** For an administrator, a view's *How this view was built* panel has a line **Node** naming the node
that built the view and how: `local`, `built here, data from <node>` (a document a peer read, N4), `forwarded from <node>` (the whole request
forwarded here, said by the node that rendered it) or `scatter-gather from <nodes>` with the nodes missing (a grouped page). `NetServedBy` adds
it to the view (`GET /api/v1/views/...`) and to the frames of its live stream; what the request asked of other nodes is noted on the request's
caller (`NetCaller.Served`) by the peer sources and the gathering of a grouped page. Everyone else's view is unchanged, so a user still cannot
tell which server answered; with Drishti*net off nothing is added. Tested on three real servers: forwarded, local, data and scatter-gather
lines for an administrator, none for a user. The finance pack's book P&L Sutra gained its *How this view was built* panel.

