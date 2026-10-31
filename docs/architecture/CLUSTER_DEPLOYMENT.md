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
# Cluster deployment: Drishti as a highly available, sharded estate

Status: **the deployment reference** (2026-10-31, written against `develop` at b8d00fce7). This is **the** document on how a clustered
Drishti is deployed: how many server processes of which role, how coordinators, shard owners and consoles are placed, how VaR and exposure
are sharded, and how the estate stays highly available, balanced and up through upgrades, loads and maintenance. Every statement marked
**Today** was read in the code or configuration and names the file or setting; every proposal is marked **PLANNED** with its plan row in
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#cluster-deployment-candidates-2026-10-31) (rows `CD1` to `CD12` were added there as
candidates by this document; `D1`, `PO-11`, `2b`, `2c` and the 2.9.8 rows existed). Sizes are **ESTIMATED** from the capacity model unless
marked measured; the footnotes say how.

Older documents that hold deployment text keep it until the consolidation pass (2.9.7 row 2e) folds it in here:
[AWS_DEPLOYMENT.md](AWS_DEPLOYMENT.md) (ECS, EFS, S3 as built), [OPERATIONS.md](../admin/OPERATIONS.md) (one machine, services, TLS,
monitoring), [VAR_SHARDING.md](VAR_SHARDING.md) (the shard machinery), [RESOURCES_AND_SCALE.md](RESOURCES_AND_SCALE.md) (memory, CPU,
disk), [DRISHTI_NET.md](DRISHTI_NET.md) and [NET.md](../admin/NET.md) (membership and routing). Where they disagree with this document on
deployment, this one decides.

## Contents

1. [Goals and non-goals](#1-goals-and-non-goals) · 2. [The estate in one picture](#2-the-estate-in-one-picture) ·
3. [Each role](#3-each-role) · 4. [Cluster mechanics: membership, placement and fail-safety](#4-cluster-mechanics-membership-placement-and-fail-safety) ·
5. [VaR sharding in depth (built)](#5-var-sharding-in-depth-built) · 6. [Exposure sharding in depth (PLANNED)](#6-exposure-sharding-in-depth-planned) ·
7. [Other aggregations](#7-other-aggregations) · 8. [High availability, role by role](#8-high-availability-role-by-role) ·
9. [Load balancing](#9-load-balancing) · 10. [Zero downtime](#10-zero-downtime) · 11. [The daily cycle across the cluster](#11-the-daily-cycle-across-the-cluster) ·
12. [Consoles and users](#12-consoles-and-users) · 13. [Security zones](#13-security-zones) · 14. [Operations](#14-operations) ·
15. [Two concrete targets](#15-two-concrete-targets) · 16. [Bill of materials](#16-bill-of-materials) ·
17. [Built against planned](#17-built-against-planned) · 18. [Decisions for the product owner](#18-decisions-for-the-product-owner) ·
19. [Glossary](#19-glossary)

---

## 1. Goals and non-goals

| # | Goal | What it means here | Met today? |
|---|---|---|---|
| G1 | **No single point of failure** in any role | at least two of everything that serves users; every VaR shard on two owners in two zones; the database with a standby; the lake on replicated storage | servers, shards, consoles: yes; database standby, singleton jobs: PLANNED (`CD5`, `CD8`) |
| G2 | **Load balancing** | users spread over consoles; consoles spread over servers; heavy work spread over shard replicas, not only their first owner | consoles and servers: yes; partials over replicas: PLANNED (`CD6`) |
| G3 | **Zero downtime** | upgrades, data loads, configuration changes and maintenance with no outage and no wrong number | loads and most configuration: yes; rolling upgrades: by hand today, automated PLANNED (`CD1` to `CD4`) |
| G4 | **Scale-out** | adding a server lowers per-server disk and page cache (VaR today) and heap (after `D1`) | VaR rows: yes; skeleton heap: `D1`; exposure: PLANNED (`CD10`) |
| G5 | **Workload isolation** | a whole-bank BI run or a cold load never slows a VaR node answer | by role separation (section 3); admission queue PLANNED (`CD6`) |
| G6 | **Security** | TLS everywhere, signed member calls, segmented networks, secrets outside files, audit on every member | signed calls, secrets by environment, audit: yes; TLS between members: available, not the default (`CD9`) |

**Non-goals.** A consensus protocol (Raft, ZooKeeper) for membership or shard ownership (section 4.10); a distributed answer cache (Redis,
Hazelcast) ([RESOURCES_AND_SCALE.md section 1](RESOURCES_AND_SCALE.md#1-purpose-principles-and-non-goals)); active-active across two
regions for writes (the second site is a recovery site, section 14.5); Kubernetes manifests (the same roles map onto it; not written);
quorum-based write safety under a network split (section 4.7 says exactly what is and is not guaranteed).

---

## 2. The estate in one picture

```
                               users (browsers, embedding hosts, API clients)
                                      │ HTTPS 443, SSO (OIDC) at the console or the edge
  EDGE      ┌─────────────────────────▼───────────────────────────────────┐
            │ load balancer pair (ALB, or F5/HAProxy x2 + VIP), TLS, WAF   │  health: console /readyz
            └──────────────┬───────────────────┬──────────────────────────┘
  CONSOLES   ┌─────────────▼──┐ ┌──────────────▼─┐ ┌────────────────┐   stateless but for live channels (PO-11)
             │ console 1      │ │ console 2      │ │ console 3 (N+1)│   4 worker processes each
             └───────┬────────┘ └───────┬────────┘ └───────┬────────┘
                     │ backend.urls + discovery (any server answers any scope)    HTTPS 18480
  APP     ┌──────────▼────────────────────▼────────────────────▼─────────────────────────────────────────────────┐
  (one    │ COORDINATORS (VaR coordinator-only, exposure coordinator, entity pages)    x2-3                       │
  Drishti │ VaR SHARD OWNERS (rows of S0..Sn, 2 replicas, 2 zones)                       x6 at 2.3M, x16 at 10M     │
  *net)   │ EXPOSURE SHARD OWNERS (PLANNED; today one exposure server)                   x4 at 2.3M, x8 at 10M      │
  gossip  │ BI AND REPORTING servers (Rūpaka, BI runs, scheduled reports)               x2-3                       │
  + signed│ DATA-SERVING servers (entity pages, search, type-ahead, data map, World, Market)  x2-3               │
  calls   │ PYTHON COMPUTE pools (exposure what-if, VaR engine pools) on owners or hosts of their own             │
          └─────┬──────────────────────┬───────────────────────────┬───────────────────────────┬───────────────┘
                │ Parquet reads         │ JDBC                      │ Kafka (PV topic)          │ content store
  DATA    ┌─────▼──────────────┐ ┌──────▼──────────────────┐ ┌──────▼─────────────┐ ┌──────────▼───────────────┐
          │ LAKE: S3 or shared │ │ Drishti database:       │ │ Kafka (bank's, or  │ │ content store: PostgreSQL│
          │ file system; Delta │ │ PostgreSQL primary +    │ │ MSK), 3 brokers    │ │ or S3 (or a shared mount)│
          │ or Iceberg         │ │ standby (identity, edits│ └────────────────────┘ └──────────────────────────┘
          └─────▲──────────────┘ │ sessions, run leases)   │
                │ one commit a date└────────────────────────┘     SECRETS: vault / Secrets Manager (token, net, session, embed keys)
  LOAD    ┌─────┴──────────────────────────┐
          │ LOADERS: drishti-lakeload jobs │──── data-load signal (POST /packs/{pack}/loads) to one member, forwarded to all
          │ (scheduler: active + re-run)   │
          └────────────────────────────────┘
```

Every Drishti server process is the same jar; its **role** is configuration (packs, edition, settings, tags). All servers are members of one
Drishti\*net and any of them answers any request: a request reaching the "wrong" server is forwarded (view or data forwarding,
[DRISHTI_NET.md section 7](DRISHTI_NET.md#7-routing-a-request)) or scattered to shard owners (section 5). Roles exist to size and isolate
work, not for correctness.

---

## 3. Each role

### 3.1 The roles at a glance

| Role | Runs (packs, edition, features) | Key settings | Count: pilot / 2.3M / 10M | Shares a host with | Must not share with |
|---|---|---|---|---|---|
| **Edge** | load balancer pair, TLS, optional OIDC pre-auth | stickiness on (until `PO-11`), health `GET /readyz` on the console | 2 / 2 / 2 | nothing | app servers |
| **Console** | `drishti-console`, 4 workers | `DRISHTI_CONSOLE_WORKERS=4`, `backend.urls`, `discover: true`, same `DRISHTI_SESSION_SECRET` and token secret | 2 / 3 / 4–6 (2–3 after `D6`) | a coordinator (pilot only) | shard owners, loaders |
| **VaR coordinator** | full packs or the `risk` edition; VaR sharding on, `coordinator-only: true` | `drishti.var.sharding.coordinator-only`, `drishti.var.threads` half the cores when BI also runs | 0 (all-in-one) / 2 / 3 | exposure coordinator | loaders |
| **VaR shard owner** | `market-risk` (and what it needs); `peer-only-packs: [market-risk]` optional | sharding block, `zone-tag: az`, `drishti.var.threads: 0` (every core), BI off | 3 / 6 / 16 | nothing heavy | BI, loaders, consoles |
| **Exposure server** (today) / **owner** (PLANNED `CD10`) | `counterparty-risk` | `drishti.ccr.exposure.*`, compute pool `exposure` | 1 / 1 → 4 / 8 | its compute pool | VaR owners (page cache) |
| **BI and reporting** | Rūpaka, BI runs, scheduled reports | `config/runs/runs.yaml` workers, `drishti.bi.query.*` | 0 / 2 / 3 | data-serving | VaR owners |
| **Data-serving** | entity pages, search, type-ahead, data map, World, Market packs | connector caches, `index.max-disk` | 0 / 2 / 3 | BI | VaR owners |
| **Loader** | `drishti-lakeload` jobs (batch), `lake maintain` | `memory.budget-mb`, `parallel.writers`, spill on NVMe | 1 / 1 + standby / 2 + standby | nothing during the load window | any server |
| **Python compute** | `config/compute/compute.yaml` pools (`risk`, `exposure`) | `workers`, `memory` per worker | on servers / on exposure owners / hosts of their own | exposure owners | VaR owners |
| **Database** | PostgreSQL (identity, sessions, edit log, run leases) | `DRISHTI_IDENTITY_DB_URL`; pools per `drishti.db.pool.size` | 1 (SQLite allowed) / 1 + standby / 1 + standby | nothing | app servers |
| **Lake** | S3 (Delta or Iceberg) or a shared file system | `DRISHTI_DELTA_ROOT` | managed | — | — |
| **Kafka** | the PV topic for intraday | `DRISHTI_KAFKA_BOOTSTRAP`, `DRISHTI_VAR_PV_STREAM` | optional / 3 brokers / 3 brokers | — | — |

Pilot means about 200,000 positions and 1,500 portfolios (the 2.9.7 benchmark size) and up to 50 active people: three all-in-one servers
(each owner and coordinator) and two consoles.

### 3.2 Sizing per role

The VaR rows below are the capacity model's ([RESOURCES_AND_SCALE.md section 9.5](RESOURCES_AND_SCALE.md#95-capacity-tables)) with 1,518
values a position (1-day, 10-day and stressed), two dates open, four listed currencies. **D1** is the mapped skeleton (2.9.7 row D1, not
built).

| Role | Scale | `-Xmx` | RAM | vCPU | Local disk | Note |
|---|---|---|---|---|---|---|
| All-in-one server | pilot | 8g | 32 GB | 8 | 200 GB NVMe | footnote 1 |
| VaR shard owner (of 6, replicas 2) | 2.3M before D1 | 36g | 128 GB (need 69) | 16 | ~650 GB NVMe (need 394) | footnote 2 |
| VaR shard owner (of 6) | 2.3M after D1 | 20g (16g aggregates mapped) | 96–128 GB (need 60) | 16 | ~650 GB NVMe | footnote 2 |
| VaR shard owner (of 16, replicas 2) | 10M before D1 | 118g | 256 GB (need 146) | 16–24 | ~1 TB NVMe (need 643) | impractical heap: D1 needed |
| VaR shard owner (of 16) | 10M after D1 | 53g (36g) | 192 GB (need 106) | 16–24 | ~1 TB NVMe | footnote 2 |
| VaR coordinator | 2.3M before D1 | **36g** | 64 GB | 8–16 | 50 GB | footnote 3: the decks' 16–20 GB is too small before D1 |
| VaR coordinator | 2.3M after D1 / 10M after D1 | 20g / 53g | 48 GB / 96 GB | 8–16 | 50 GB | footnote 3 |
| Exposure owner (PLANNED, of 4, replicas 2) | 100,000 sets, P = 2,000, G = 50 | 16g | 128 GB | 16 | 2 TB NVMe | footnote 4 |
| Exposure owner (PLANNED, of 8) | 250,000 sets, P = 5,000, G = 100 | 24g | 256 GB | 24 | 4 TB NVMe | footnote 4, ASSUMPTION on sets |
| BI and reporting | any | 16g | 64 GB | 16 | 500 GB SSD (`bi-store`, `bi-runs` 20 GB) | ASSUMPTION |
| Data-serving | any | 24g | 64 GB | 16 | 1 TB NVMe (id maps, index, documents) | ASSUMPTION |
| Loader | 2.3M in 10 minutes | 40g | 64 GB | 32 | 50 GB NVMe spill | measured at 10K, estimated at 2.3M ([PARALLEL_LOADER.md section 11](PARALLEL_LOADER.md#11-measured-and-the-23m-estimate)) |
| Console | 300–500 people | — | 8 GB | 4 | 20 GB | measured 69–70 pages/s a console |
| PostgreSQL | any | — | 32 GB | 8 | 500 GB SSD | ASSUMPTION |

**Footnote 1.** 200K is K = 2 against the 100,000-trade benchmark: hot file 16.3 GB × 2/23 = 1.4 GB a date, skeleton 0.17 GB a date, a load
3.8 GB; heap need (2 × 0.19 + 2 × 0.16 + 2 + 4) × 1.5 ≈ 10 GB at full width, so 8g suffices for 1-day only and 12g for 1,518 values.
**Footnote 2.** RESOURCES_AND_SCALE footnotes 1 and 4: an owner holds replicas ÷ owners of the page cache, a load and the disk (1/3 at 6,
1/8 at 16); its heap is **not** divided (the skeleton is copied to every member). RAM sizes are the need ÷ 0.61 (the decks' 39%
headroom) rounded up to a machine size. **Footnote 3.** A coordinator puts every open date together too: it holds the whole skeleton
(`ShardAssembly`, VAR_SHARDING section 5) but no rows, so its heap need is the owner's (21.2 GB at 2.3M before D1, `-Xmx36g`) and its page
cache is only what it reads of the mapped aggregates. The capacity model's coordinator row (`capacity.py`: "16–20 GB, 32 GB RAM",
marked ASSUMPTION) holds only after D1; the decks are to be corrected. **Footnote 4.** EXPOSURE_GUIDE section 26.2 as built: V, E and N
matrices float32, 800 KB a set at P = 2,000, G = 50 (G_primary 25): 80 GB a date for 100,000 sets; with replicas 2 over 4 owners, 40 GB
a date an owner, two dates open; NVMe for about 30 retained dates, older ones read from the lake (PO-3: the disk cap wins). At P = 5,000,
G = 100: 4 MB a set; 250,000 sets (ASSUMPTION for a 10M-position bank) = 1 TB a date, 250 GB an owner of 8.

### 3.3 JVM flags (Java 21 and 25)

**Today** (`deploy/docker/ecs/server-entrypoint.sh`): `-XX:+UseZGC`, `-XX:+ZGenerational` on 21 or `-XX:+UseCompactObjectHeaders` on 25,
`-XX:-UsePerfData`, `-XX:MaxDirectMemorySize=256m`, `-XX:TrimNativeHeapInterval=60000`; `-Djdk.nio.maxCachedBufferSize=262144` on every
launcher; `MALLOC_ARENA_MAX=2` (`drishti-stack/stack.py`, `deploy/docker/compose.yaml`). The stack runs G1 on Java 21
(`drishti-stack/stack.yaml`: `java`, `jvm: [-XX:G1PeriodicGCInterval=60000]`).

| Role | Java 21 | Java 25 |
|---|---|---|
| VaR owner, coordinator, exposure owner (heap ≥ 16 GB) | `-XX:+UseZGC -XX:+ZGenerational -XX:SoftMaxHeapSize=<0.8 × Xmx>` (the last flag PLANNED, RESOURCES 5.5) | `-XX:+UseZGC -XX:+UseCompactObjectHeaders` |
| BI, data-serving | the same (heap 16–24 GB) | the same |
| Small server, pilot (≤ 8 GB) | G1, `-XX:G1PeriodicGCInterval=60000` | G1, plus `-XX:+UseCompactObjectHeaders` |
| Loader | G1 or `-XX:+UseParallelGC` (throughput) | the same |
| Every role | `-XX:MaxDirectMemorySize=256m -Djdk.nio.maxCachedBufferSize=262144 -XX:TrimNativeHeapInterval=60000`, `MALLOC_ARENA_MAX=2`, heap dumps to a capped folder | the same |

### 3.4 Disk and cache budgets per role

**Today** (`drishti-server/src/main/resources/application.yaml`): VaR hot files `drishti.var.tier.max-disk-gb` (0: half the disk),
`disk-dates` 60, `keep-on-shutdown` (true in the server images, PO-10); exposure `drishti.ccr.exposure.max-disk-gb` (0: a quarter);
the free-space guard `min-free-percent` 10 and `min-free-gb` 20 (`DRS-5218`); the cache budget 0.5 of `-Xmx` (D3, built). Per role:

| Role | `var/hot` | `ccr/hot` | connector caches | `bi-store`, `bi-runs` |
|---|---|---|---|---|
| VaR owner | the disk's 60% (`max-disk-gb` set explicitly) | 0 (exposure off) | small (hierarchies, scenario sets) | off |
| Exposure owner | 0 | 60% | small | off |
| Coordinator | aggregates only (skeleton books) | 0 | small | optional |
| BI, data-serving | 0 | 0 | 50–70% (id maps 5 GB a connector, index 2 GB, documents 1 GB) | 50 GB, 20 GB |

### 3.5 How each role scales

| Role | Scales by | Limit that binds first |
|---|---|---|
| Console | adding processes behind the LB | CPU (about 40 pages a second a worker, measured) |
| VaR owner | adding owners: each holds replicas ÷ owners of the rows | the skeleton heap, copied to every member, until D1 |
| VaR coordinator | adding coordinators | the skeleton heap; CPU for many ad-hoc scopes |
| Exposure owner | adding owners (PLANNED) | page cache for the set matrices |
| BI | more BI servers; heavy runs go to the model's owner by rendezvous (`RunCluster`) | DuckDB memory and threads |
| Data-serving | more servers; same-table replicas are asked once ([DRISHTI_NET.md section 10](DRISHTI_NET.md#10-shared-and-separate-lakes)) | page cache for id maps and segments |
| Loader | more writer threads, then a second loader per kind | spill disk speed |

---

## 4. Cluster mechanics: membership, placement and fail-safety

This chapter is the design reference for how members find each other, how VaR shards are placed and moved without a coordinator, and
what happens when anything fails. Every fact is read in the code named.

### 4.1 Membership by gossip (SWIM over HTTP)

**The member table** (`drishti-net/.../member/Member.java`): each entry carries

| Field | Meaning |
|---|---|
| `nodeId` | stable, generated once and kept in `<data dir>/net/node-id` (`NodeIdentity`) |
| `url` | the advertised address: always a real IP or a name resolving to one; loopback, wildcard, link-local and container-bridge addresses in any spelling are refused at the intake (`MemberTable.merge`, `HostAddress.callableUrl`) |
| `gossipPort` | the member's own gossip connector (0: gossip goes to `url`) |
| `incarnation` | raised only by the member itself, to refute a suspicion or come back after leaving |
| `heartbeat` | raised by the member every round |
| `state` | `alive`, `suspect`, `dead`, `left` |
| `digestHash` | the hash of its capability digest; a change makes peers pull `GET /api/v1/net/digest` |
| `switchesHash` | the hash of its switches ledger (Admin → Commands) |
| `version`, `protocol` | the build, and the Drishti\*net protocol (`Member.PROTOCOL` = 1); a member with another protocol is listed but never routed to |
| `tags` | e.g. `az:eu-west-1a`, `region:emea`; the **zone** is the value of the tag named by `drishti.var.sharding.zone-tag` (`ShardPeers.zone`) |
| `since`, `drained` | when the state began (the setter's clock: display only); `drained` while it takes no forwarded work |

Locally, beside each entry, a member keeps `beatAt` (when the heartbeat last rose **here**), `heardAt`, a smoothed latency and `stateAt`
(`MemberTable.Known`). Failure detection uses only these local times, so clock skew between machines never makes a member suspect.

**A round, step by step** (`Gossiper.round`, every `interval`, on one platform thread `drishti-net-gossip`, `NetService.loop`):

1. raise its own heartbeat;
2. pick `fanout` random members that are **alive or suspect** (a suspect one hears of its suspicion this way and can refute it) and do a
   **push-pull** with each, in parallel on the 8 platform threads of `drishti-net-gossip-io-*` (`NetService`), each cut at
   `max(200 ms, 2 × interval)`: `POST /api/v1/net/gossip` with its whole table; the answer is the peer's whole table; both merge;
3. if it reaches no member, try its **join list** (seeds, then the members saved in `peers.json`, alive ones first), three at a time,
   until one exchange answers; otherwise **every 30th round** exchange with one seed or saved member that is not in its reachable table
   ("a seed outside the table heals a split", `Gossiper.round`);
4. **detect**: every alive member whose heartbeat has not risen here for `suspect-after` is probed directly (`GET /api/v1/net/ping` on its
   gossip port) and, failing that, through `indirect-probes` other alive members (`GET /api/v1/net/ping?target=`); still silent, it is
   marked `suspect` (`MemberTable.mark`, keeping its incarnation so it can refute);
5. a member `suspect` here for `dead-after` becomes `dead`; a `dead` or `left` one is forgotten after `forget-after`;
6. tell the listeners (save `peers.json` on any join or leave, pull new digests); save `peers.json` every `persist-every` anyway.

**The merge rule** (`Member.supersededBy`): the higher `incarnation` wins; at equal incarnation the worse state wins
(`left` > `dead` > `suspect` > `alive`, `MemberState`); at equal incarnation and state the higher `heartbeat` wins (it carries the newest
url, tags, digest hash and drain flag). A member's own entry is changed only by itself; **refutation**: news of itself that is worse than
`alive` at its incarnation, or carries a higher incarnation, makes it raise its incarnation above it and stay `alive`, spread by the next
round (`MemberTable.merge`). A member that is leaving refutes nothing.

**Leaving** (`Gossiper.leave(3)`, from `NetService.stopNode` on shutdown or `enabled: false`): its entry becomes `left` with a raised
heartbeat and is pushed to every reachable member three times; peers drop it at once instead of waiting for the detector. **Rejoining**
after a leave raises the incarnation (`Gossiper.rejoin`).

**Joining** (`NetService`, DRISHTI_NET section 5): a server joins only on Spring's `ApplicationReadyEvent` (`joinWhenReady`, `ready()`), when
its web server answers and its connectors have started; a restarted node rejoins from `peers.json` even when every seed is down. It is
*joining* until it has the digest of every alive peer, at most `join-grace` (30 s, `RoutingSettings`); a read it cannot place meanwhile
waits up to `join-wait` (2 s) and is otherwise `502 DRS-5052` "still joining", which the console retries once elsewhere.

**The gossip port** (`drishti-server/.../net/GossipConnector.java`): a second Tomcat connector serving only gossip, ping and digest, with
8 platform request threads, `maxConnections` 4096 and its own acceptor; `gossip-port: auto` (node.yaml, default) binds a free port and
advertises it, a number binds that port, `off` uses the server's port. So a server whose request threads are saturated by a whole-bank
query still answers gossip and probes, and is not wrongly suspected. With TLS on the server, the gossip port uses the same certificate and
client authentication.

**Settings** (`config/net/gossip.yaml`, applied on the next round; defaults in `drishti-net/.../config/GossipSettings.java`; routing in
`RoutingSettings.java`):

| Setting | Default | Meaning |
|---|---|---|
| `interval` | 1 s | one round |
| `fanout` | 3 | push-pull partners a round |
| `suspect-after` | 5 s | heartbeat silence before probing |
| `indirect-probes` | 2 | helpers asked to probe |
| `dead-after` | 30 s | suspect → dead |
| `forget-after` | 24 h | dead or left entries kept (no resurrection by a stale table) |
| `persist-every` | 10 min | `peers.json` saved (also on joins, leaves, shutdown) |
| routing `forward-timeout` | 5 s | a forwarded call |
| routing `join-grace`, `join-wait` | 30 s, 2 s | joining until the digests |
| node `gossip-port` | `auto` | the gossip connector |

**The stack today** (`drishti-stack/stack.yaml`): `interval: 1s`, `suspect-after: 3s`, `dead-after: 10s` (a faster failover for a lab).

### 4.2 Message sizes and convergence

A member entry is about 350 to 450 bytes of JSON (thirteen fields, two 64-character hashes) (ESTIMATED from the record's fields). Each
round a member sends and receives `fanout` whole tables:

| Members N | Table | Bytes a member a second (2 × 3 × table) | Rounds for news to reach all (push-pull, fanout 3) |
|---|---|---|---|
| 8 | ~3.5 KB | ~21 KB | 2 |
| 20 | ~9 KB | ~54 KB | 2–3 |
| 50 | ~22 KB | ~130 KB | 3–4 |
| 100 | ~45 KB | ~270 KB | 4–5 |

Convergence (ESTIMATED): with push-pull each informed member reaches 3 others a round and every member also pulls from 3, so the informed
share grows about fourfold a round at first; log₄ N rounds plus one or two for the last members: 2 rounds at 8 members, 4 to 5 at 100
(DRISHTI_NET section 5 states log₃(100) ≈ 4–5). **Consequence for detection:** a heartbeat seen second-hand is up to that many rounds old,
so at 100 members a healthy member's heartbeat can look 4 to 5 s old: just under `suspect-after`. Probes then answer and nothing is
marked, but the probe traffic rises: raise `suspect-after` with size (section 4.11).

### 4.3 Failure detection and its timing

With the defaults, a member that crashes at t = 0 (every heartbeat stops):

| t (s) | Event | Where in the code |
|---|---|---|
| 0 | last heartbeat | |
| 5–6 | peers see the heartbeat stale for `suspect-after`; direct ping fails (connection refused at once, or cut at 2 s on a black hole); 2 indirect pings fail | `Gossiper.detect`, `answers` |
| 5–8 | **suspect** on the detecting members, spread to all within 1–3 rounds; from now `ShardPeers.peers` omits it (only `alive` members), so it is no longer asked for partials or skeletons, and routing skips it | `ShardPeers.peers`, `Member.routable` |
| 35–38 | **dead** (suspect for `dead-after`) | `Gossiper.round` |
| 24 h | forgotten | `forget-after` |

A member that was only slow answers a probe or later gossips with a higher incarnation (refutation) and is `alive` again; nothing moved
in between unless it stayed out longer than `stable-seconds` (section 4.5).

### 4.4 Automated sharding: the shard map

`ShardMap` (`drishti-compute/.../shard/ShardMap.java`): per business date, the rows counted per value of the shard key (VaR:
`LEGAL_ENTITY`, then `DESK`), read as columns without the vectors; walked in key order, a shard closes when the next key would take it over
`target-shard-mb` (default 2048, `VarSharding.DEFAULT_TARGET_MB`; a row's size is 4 × scenarios bytes, the float32 hot row). **A key value
(a desk) is never split**: a desk larger than the target is a shard of its own. The ranges cover the whole key space (shard 0 also takes
everything before, the last everything after), so every row has exactly one shard. The map is a pure function of the counts: every member
reading the same data computes the same map. Its digest names the **cut**, not the data, so an intraday batch that moves no cut keeps the
map (VAR_SHARDING section 3).

**How many shards (ESTIMATED).** At 1-day width (509 scenarios, 2,036 B a row) 2.3M positions are 4.7 GB of rows: 3 shards at the
default target; at 1,518 values (6,072 B) 14 GB: 7 shards. With 6 owners and 2 replicas, 7 shards are 14 owner slots over 6 members:
2 or 3 each, a 50% imbalance, and each shard a 2 GB rebuild after a failure. **Recommendation:** `target-shard-mb: 512` at 2.3M (about 27
shards at 1,518 values, 54 slots, 9 an owner) and 1024 at 10M (about 60 shards, 120 slots over 16 owners); smaller shards balance better
and rebuild faster, at the price of more partial calls a whole-bank scope (one a shard, 4 KB to 12 KB each).

### 4.5 Rendezvous hashing, replicas and zones

`ShardOwners.owners` (`drishti-compute/.../shard/ShardOwners.java`):

```
weight(node, key) = first 60 bits of SHA-256( node + "|" + key )      key = the shard's range "LE-A/DESK-X..LE-B/DESK-Y"
ranked            = candidates sorted by weight descending, ties by node id
owners            = pinned member first (if among the candidates), then repeatedly:
                      the heaviest candidate not yet chosen whose zone is not used yet (zones carried),
                      else the heaviest candidate not yet chosen
                    until `replicas` owners (fewer when fewer members)
```

**The candidates** (`ShardMembership`, `drishti-server/.../shard/ShardMembership.java`): this server and every Drishti\*net member that is
`alive`, speaks the protocol, is not Drishti\*net-drained, has VaR sharding on, owns shards (not `coordinator-only`) and is not draining
(`ShardPeers.Peer.eligible`). **The view moves only once it has been the same for `stable-seconds`** (30): until it first has, the members
eligible now; a draining member leaves the view at once. The view is looked at again at most every 250 ms.

**Why rendezvous and not a ring.** A consistent-hashing ring needs virtual nodes (hundreds per member) to balance load and a shared ring
state; rendezvous needs neither: each shard independently ranks the members, which balances shards evenly in expectation with no tuning,
gives the top-k replica set directly (no walking the ring past a member's own virtual nodes), moves only the shards a joining member wins
or a leaving member held, makes the zone rule a simple "next heaviest in an unused zone", and is a pure function computed the same on every
member with no state to agree on. Its cost, O(members) hashes a shard, is microseconds for hundreds of shards. (AWS_DEPLOYMENT section 9
still says "ring position"; it means the same rendezvous weight.)

**Rebalancing math.** With N eligible members and replicas R, each of the R × S owner slots goes to a joining member with probability
1/(N + 1), so a joiner takes about R × S/(N + 1) slots and every other member gives up about 1/(N + 1) of its share; a leaver's slots go,
each, to the next heaviest member (its other replica is untouched). `ShardOwnersTest` checks a fourth member taking 20 to 30% (expected
25%) of first owners and of replica slots over 2,000 shards.

*Worked example, 6 → 7 owners at 2.3M, 1,518 values, `target-shard-mb: 512`* (ESTIMATED): S = 27 shards of about 0.52 GB, R = 2, 54
slots, 9 an owner. The seventh member takes about 54/7 ≈ 7.7 slots (each of the six gives up about 1.3); about 7.7 shards change one owner;
the seventh builds about 4 GB of shard books from the lake (it reads only the files of those key ranges, VAR_SHARDING section 5); each
owner's share falls from 9/54 to 7.7/54 of the rows (page cache 26.1 → 22.4 GB, NVMe need 394 → 338 GB). Nothing else moves.

**Zones.** With `zone-tag: az` and tags `az:<zone>` on every member, the second owner of every shard is in another zone whenever one
exists; a zone outage then leaves every shard with a ready replica. Members without the tag are chosen only when no zoned candidate in an
unused zone remains: tag all members or none.

**Pins** (`pins`): a shard pinned to a member is owned by it first, the same on every member. For tests and for keeping one large desk on a
bigger machine; not a placement tool for everyday use.

### 4.6 Re-placement, handover and drain

**What triggers a re-placement.** Only a change of the eligible view that lasts `stable-seconds`: a member suspect, dead or left, a new
member alive, a member's sharding state changed, a drain (at once), or a new cut (a reload with new counts). A flap shorter than
`stable-seconds` moves nothing (`ShardMembership.look`: the stable view is replaced only after the new one stayed the same that long).

**The handover protocol** (`ShardHandover.decide`, `VarShards` every `rebalance-seconds`, VAR_SHARDING section 7):

```
 new owner N                                    old owner O                                  every member
 ─────────                                      ─────────                                    ────────────
 view settled (stable-seconds): N owns S3
 builds S3 from the lake (its key range only)
 writes <hot>/<date>-shard-3/inputs-<d>.vhot
 announces "ready S3 @ data d" in its
   capability digest (var-shards service) ───► digest hash in gossip, pulled in ≤ 1 round ───► partial calls may now go to N
                                                keeps serving S3 (KEEP: successor not ready
                                                long enough)
                          ... grace-seconds (30) after EVERY owner announced S3 ready ...
                                                RELEASE: unmaps and deletes its S3 book
```

So **a shard in use always has a ready holder**: the release waits for the successor's readiness, never the other way round. An older data
version of a shard is released once the newer has been ready here for the grace period; a shard of a replaced cut once the grace has
passed. A date put together again is swapped in whole only when complete (`VarService.swap`); until then the loaded date answers.

**Drain.** `POST /api/v1/var/shards/drain {"on": true}` or `drishti.py var shards --drain` (administrators): the member leaves every view at
once, keeps answering and serving its shards until each is handed over, then `GET /api/v1/var/shards` reports `"safeToStop": true`. A
member started with `drishti.var.sharding.drain: true` takes no shard. The drain flag is in memory: a restart without the setting takes
shards again. Drishti\*net's own drain (`POST /api/v1/net/drain`, the member's `drained` flag) is separate: it stops forwarded work and makes
the member non-routable, which also takes it out of the shard view. **Today no stop hook drains**: SIGTERM runs Spring's graceful shutdown
(`server.shutdown: graceful`) and gossips `left`; shards are then rebuilt by their next owners while the replicas answer. **PLANNED
(`CD2`):** one drain that covers both and a stop hook that waits for `safeToStop`.

**Fargate scale-in.** ECS's `stopTimeout` is at most 120 s on Fargate (the task definition says 60, `deploy/aws/ecs/templates/node-task.json`),
less than a 2.3M shard rebuild plus `grace-seconds`; so the drain must run **before** the stop (the deploy runner, `CD3`), never from the
stop signal alone. On ECS with EC2 the agent's `ECS_CONTAINER_STOP_TIMEOUT` can be longer.

### 4.7 Readiness in the digest and the completeness check

Each member publishes under the digest service `var-shards` (`ShardPeers.State`): sharding on, owner, draining, per date the shard maps it
holds and per shard the first 16 characters of the data digest it is ready to serve, and (2.9.7) the dates it has open. A change rebuilds
the digest at once (`ShardPeers.announce`), raises its hash in gossip, and peers pull it within a round: readiness needs no protocol of
its own.

**Scatter-gather** (`ShardGather`, VAR_SHARDING section 6): a scope's rows are split by shard; each shard's part goes to **one** member in
this order: owners that announced it ready at the data version the date was put together from (first owner first), any other member that
announced it so (an old owner mid-handover), then owners not yet ready (they build from the lake). `POST /api/v1/var/partial`, signed, one
hop, with the caller's on-behalf-of token; the answer is labelled with shard, map digest, data digest and row count and refused if any
differs. **Exactly one answer per shard**, added in shard order; a shard nobody answers fails the whole scope with `503 DRS-5215` naming
it; a member at another data version answers `409 DRS-5216` and the next is asked. **Never a partial VaR.**

### 4.8 Fail-safety: every failure, its timing and what people see

Defaults throughout (gossip 1 s / 5 s / 30 s; `stable-seconds` 30, `grace-seconds` 30, `call-timeout-seconds` 30, console `check-every` 5
s, `fail-after` 2). *T_b* is the time to build a shard book from the lake (minutes at 2.3M, to be measured in the 2.9.7 benchmark).

| Failure | Detected after | Automatic recovery | What users see | Operator | Not guaranteed (and the row closing it) |
|---|---|---|---|---|---|
| **One VaR owner down** | 5–8 s (suspect) | from 5–8 s its replica answers its shards (it is no longer asked); at 35–40 s the view settles, new owners build the shards (T_b), the replica count is restored | node figures never stop; scopes needing its shards: at most one slow call (refused at once; on a black hole up to `call-timeout-seconds` 30 s before the replica) | replace or restart the machine | requests in its first 5–8 s on a black-holed host wait up to 30 s (`CD6` lowers the call timeout for partials) |
| **Both owners of a shard down** | 5–8 s | node aggregates keep answering on every member; at 35–40 s new owners build from the lake; answers again after T_b | scopes needing that shard: `503 DRS-5215` naming the shard for 35–40 s + T_b | restore one owner; zones prevent it (one per zone) | the outage window itself; zone-tagging is the defence |
| **A slow owner** | per call | after `call-timeout-seconds` the next member is asked | one slow answer | look at Admin → Net latency | (`CD6`: spread and hedge) |
| **A flapping node** | each flap 5–8 s | a flap shorter than `stable-seconds` moves no shard; while suspect it is not asked | nothing, or one retried call | find the cause (GC pauses, network) | a flap period near 30 s can move shards back and forth: raise `stable-seconds` |
| **Network partition** | 5–8 s each side | each side marks the other suspect then dead; each side's view settles (35–40 s) and its members build the missing shards from the lake; both sides answer correctly (same lake, same versions) | brief `DRS-5215` on scopes needing shards held only across the split; searches partial, naming missing nodes | heal the network | **no quorum**: both sides serve and both may run singleton jobs (official captures, PV stream) until `CD5`; a side that cannot reach the lake answers only from what it holds |
| **Partition heals** | ≤ 30 s (every 30th round a seed outside the table is contacted) | tables merge (higher incarnation wins, refutation), the view settles, extra shard copies released after `grace-seconds` | nothing | none; put a seed in every zone | heals only if some member's seeds or `peers.json` name a member on the other side |
| **A coordinator down** | console: 2 failures in a row (≤ 10 s) | the console sets it aside and moves to the next healthy server; reads in flight retried once; any server coordinates | a page may reload; Sutra Next selections on it reset (`DRS-5052` with words) | restart it | writes in flight are never retried automatically |
| **Seed nodes down** | — | running members keep gossiping; restarting members rejoin from `peers.json` | nothing | restore | a brand-new member with only seeds named cannot join until one is up: name 2–3 seeds in different zones |
| **A bad data version on one node** | per call | partials labelled with another data version are refused (`409 DRS-5216`); when every member is stale the date is put together again at the new version and swapped in | the scopes needing those rows answer 409 briefly; one data version throughout | announce the load (forwarded to every member) | — |
| **A stale shard book** | at the next request (version watch every 30 s, or the signal) | the owners build the newer version when asked; the old one goes after the grace | the old figures with a warning until swapped | `drishti.py var reload --date D` | without a signal and with the watch off (`version-check: 0`), unbounded |
| **Disk full** | before the write (`DiskBudget.reserve`) | least recently used hot files freed first; else the build is refused `503 DRS-5218` naming free and needed space; open dates keep answering | the new date refused with the reason | free disk or raise the cap; Health → Disk is red | no alert notice is sent yet (RESOURCES 14.15) |
| **A crash mid-handover** | 5–8 s | the old owner's book was not released (release only after every new owner is ready for the grace); if the new owner crashed, the old keeps serving; if the old crashed, the replica serves | as one owner down | none | — |
| **Clock skew** | at the first signed call | none: signed calls are refused outside a 60 s window (`ClusterKey.WINDOW_MS`); the skewed member cannot gossip, becomes suspect and dead | as one node down | NTP on every host; Admin → Net → Security lists refusals | detection itself uses local clocks only, so skew under 60 s is harmless |
| **Lake outage** | at the next read | open dates keep answering from hot files; version checks fail quietly; new loads and shard rebuilds fail | figures of open dates; new dates and rebuilds refused | restore the lake | a shard whose owners are both down cannot be rebuilt |
| **Database primary down** | at the next query | none today; reconnects once the standby is promoted (RDS Multi-AZ fails over in about a minute; on premises Patroni or an operator) | sign-in, user and session writes, edits, BI run leases fail until promoted; VaR and exposure reads with a valid token continue | promote the standby | behaviour of each read path during the switch to be tested (`CD8`) |

### 4.9 Timelines

**A node failure** (owner A of shards S2 and S5, replicas B and C; defaults; T_b = 2 min for illustration):

```
 t=0s      A crashes ─────────────── last heartbeat
 t=0-5s    calls to A for S2/S5: refused at once ──► next member (B for S2, C for S5) answers      [no gap for users]
 t=5-8s    A suspect on every member (1-3 rounds); ShardPeers omits A: B and C asked first
 t=35-38s  A dead; the eligible view (without A) has been stable 30 s ──► new owners: S2 → {B, D}, S5 → {C, E}
 t=37-40s  D starts building S2, E starts building S5 from the lake (rebalance every 2 s)
 t≈160s    D and E announce ready (digest, ≤1 round) ──► two ready owners again                   [replica count restored]
 t≈190s    grace passed: nothing to release (A is gone); steady state
 t=24h     A forgotten
 A restarts at t=10min: joins on ApplicationReadyEvent ─► joining until digests (≤30 s) ─► view stable 30 s ─► A owns S2, S5 again
           (rendezvous weight unchanged) ─► builds them (T_b) ─► ready ─► after 30 s grace D and E release them
```

**A rolling upgrade with drain** (one owner; the others stay up):

```
 drain  ─► POST /api/v1/net/drain (no forwarded work) + POST /api/v1/var/shards/drain {"on":true}
           its shards' next owners build them (T_b), announce ready ─► +30 s grace ─► "safeToStop": true
 LB     ─► (console role only) deregister from the target group, wait for connections to drain
 stop   ─► SIGTERM: graceful shutdown, gossip "left" ×3 ─► peers drop it at once
 swap   ─► new jar or image; configuration unchanged
 start  ─► Spring ready ─► joins ─► joining ≤ 30 s ─► view stable 30 s ─► takes its rendezvous shards ─► builds ─► ready
 ready  ─► /actuator/health/readiness UP (today: when Spring is ready; PLANNED CD1: only when its dates and shards are ready)
 next   ─► only then the next node; owners in one zone at a time never both owners of a shard
```

### 4.10 Why no consensus protocol

Ownership is a pure function of shared inputs (the shard map from the same lake, the membership gossip converges on in seconds). Two
members that briefly disagree cannot produce a wrong number: a partial is served only by a member that *holds* the shard at the data
version asked, so disagreement costs a refused call and a try on the next member. A Raft or ZooKeeper quorum would add a service to keep
alive and a leader to elect for no gain in **read** correctness. It would help **singleton writers** (one official capture, one PV
stream reader) under a partition; Drishti uses the database it already has for that, by lease (as BI runs do, `RunCluster` and
`config/runs/runs.yaml` `lease.*`), PLANNED for the other singletons in `CD5`.

### 4.11 Tuning

| Situation | Change | Trade-off |
|---|---|---|
| **Larger clusters** (30+ members) | `suspect-after` 8–10 s; `fanout` 4; `target-shard-mb` 512–1024 | slower detection; more gossip bytes (still under 1 MB/s at 100 members) |
| **WAN or multi-site** in one cluster (not recommended; section 14.5) | `interval` 2 s, `suspect-after` 15 s, `dead-after` 60 s, `stable-seconds` 120, `call-timeout-seconds` 10; zones = sites | minutes to fail over; cross-site partials pay the round trip |
| **Faster failover** (one data centre, low latency) | `suspect-after` 3 s, `dead-after` 10 s (the stack's), `stable-seconds` 10, `grace-seconds` 15 | a GC pause or a short network blip can move shards: only with generational ZGC and a quiet network |
| **Many owners, frequent maintenance** | `replicas: 3` | 50% more disk and page cache across the cluster |
| **Large shards slow to rebuild** | lower `target-shard-mb` | more partial calls a whole-bank scope |
| **Firewalls** | `gossip-port: 7946` (a fixed number) on every member | one more port to open |

---

## 5. VaR sharding in depth (built)

Built in 2.9.6, **off by default** (no `sharding:` block in `config/packs/market-risk/pack.yaml`, no `drishti.var.sharding.enabled`). The
full design is [VAR_SHARDING.md](VAR_SHARDING.md); this section is the deployment view.

### 5.1 What is sharded and what is copied

| Data | Placement | Size at 2.3M (1,518 values) |
|---|---|---|
| trade and position rows (`scenario-pv`, `pnl-vector`, both kinds together, a trade never split from its PVs) | **sharded** by `[LEGAL_ENTITY, DESK]`, a desk never split; `replicas` 2 | 16.3 GB a date hot; 5.4 GB an owner of 6 |
| **the skeleton** (ids, columns, positions, books, nodes and their aggregates) | **copied to every member**, owners and coordinators (`ShardSkeleton`, fetched from owners, never rebuilt by non-owners) | 2.23 GB a date on the heap (0.8 KB a position) until D1 |
| hierarchies, scenario sets, portfolios, FX scenarios | read whole by every member | MB |
| the intraday layer | every member, from the lake on its own signal | thousands of rows |
| answers, views | each member's own caches | bounded |

### 5.2 The partial and the combine

An owner sums the rows a scope selects within its shard into one float64 vector of the scenarios (the partial: 509 or 1,518 doubles, 4 to
12 KB), in a fixed block order (PO-7, built: bits independent of core count). The coordinator adds one partial a shard **in shard order**,
then takes VaR (the scenario at the configured rank), ES (the mean beyond it), and the components (each child's vector through the same
path; a position's vector one call to its shard's owner, cached for the date). **A node's figures are its aggregate**, summed bottom-up
from the shards' book aggregates exactly as one server does: equal to the bit; any other scope equals one server to the cent and, with the
fixed order, to the bit for the same set (`VarShardClusterTest`, `ShardAssemblyTest`).

### 5.3 Horizons, stress and confidence

| Measure | How it shards | State |
|---|---|---|
| 1-day VaR, ES, components | as above | built |
| 10-day √10 | 1-day result × √10 on the coordinator | PLANNED (2.9.7 row 2b) |
| 10-day sum | each 10-day scenario the sum of 10 consecutive 1-day P&Ls: linear, so the owner can sum rows then windows, or the coordinator windows the combined vector; both exact | PLANNED (2b) |
| stressed VaR | a second scenario set (`HS-STRESS-…`) of about 250 values: more columns of the same rows, sharded with them (the 1,518 width) | PLANNED (row 2c) |
| any confidence | the rank applied once, after the combine; never on partials | PLANNED (2b) |

### 5.4 Intraday, official records, currencies

- **Intraday** (VAR_SHARDING section 7): file batches and Kafka windows change no shard; each member applies the day's updates on the date it
  put together, a trade's morning row read back from the lake by id. Convergence is visible in `provenance.intraday.batches` on any answer.
- **Official records** are computed from the same vectors on the member that runs the capture; the capture is idempotent by its inputs
  (`VarOfficial`). **Today every member that receives a full-run signal runs it** (the signal is forwarded to all, PO-5): idempotent by
  inputs, but with no cluster-wide lock. **PLANNED (`CD5`):** one member by lease.
- **Reporting currencies**: their own shard books, built from the USD one, per shard (`<date>-<CCY>-shard-<i>/`).

### 5.5 Sizes per shard and D1

At 2.3M and 1,518 values, `target-shard-mb: 512`: about 27 shards of 0.52 GB of rows (the hot book with its sections about 0.6 GB);
partials 12 KB; a skeleton about 0.8 KB a position of the shard. **D1** (memory-map the skeleton, decided PO-1, not built) takes every
member's skeleton off the heap: owners and coordinators from `-Xmx36g` to about `-Xmx20g` at 2.3M, and makes 10M possible (`-Xmx118g` →
`-Xmx36g`–`53g`). Without D1, adding owners does not lower any member's heap.

### 5.6 Settings for a production cluster

```yaml
# config/packs/market-risk/pack.yaml (or drishti.var.sharding.* in application.yaml, which wins)
sharding:
  enabled: true
  key: [LEGAL_ENTITY, DESK]
  kinds: [scenario-pv, pnl-vector]
  target-shard-mb: 512          # 2.3M; 1024 at 10M (section 4.4)
  replicas: 2
```

```yaml
# application.yaml of an owner (environment variables work as for any Spring key)
drishti:
  var:
    threads: 0                  # every core
    tier: { mode: mapped, max-disk-gb: 600, keep-on-shutdown: true }
    sharding: { zone-tag: az, stable-seconds: 30, grace-seconds: 30, call-timeout-seconds: 30, rebalance-seconds: 2 }
# a coordinator: the same, plus  drishti.var.sharding.coordinator-only: true  and  drishti.var.threads: <half the cores>
```

Every member must set the same sharding block (a read of another cut is refused, never mixed). The settings are read at start: a change is
a rolling restart (section 10.4).

---

## 6. Exposure sharding in depth (PLANNED)

**Today** exposure runs on one server (`ExposureService`, EXPOSURE_GUIDE section 18.8: "Fan-out across nodes is PLANNED"). Its load holds
every set's value matrix in float64 on the heap until the date is built (80 GB at P = 2,000, G = 50 for 100,000 sets; refused over
`memory-budget-gb` with `DRS-5445`, EXPOSURE_GUIDE section 28), so a large bank does not fit one server. This section is the design,
**PLANNED as `CD10`**, on the measure-agnostic machinery VaR plugs into (VAR_SHARDING section 13; RESOURCES_AND_SCALE section 9.3).

### 6.1 The key, and what is sharded

- **Key:** `[COUNTERPARTY, NETTING_SET]`. **A netting set is never split** (netting and collateral apply per path inside a set), and a
  counterparty's sets sit together, so a counterparty's profile is one owner's. An optional group prefix (`[COUNTERPARTY_GROUP,
  COUNTERPARTY, NETTING_SET]`) keeps a group on one owner where it fits (decision PO-C3).
- **Sharded:** the trade simulation paths' products: per set the V, E and N matrices (the hot file's `VMAT`, `EMAT`, `NMAT`), and the
  load itself: each owner streams only its sets' `exposure-path` rows (files skipped by `NETTING_SET` statistics, the loader's sorted
  layout), **one set at a time** (EXPOSURE_GUIDE section 26.3's design), so the heap holds one set's float64 matrix, not the date's.
- **Copied to every member:** the set index (id, counterparty, legal entity, CSA terms, flags, about 200 B a set: 20 MB at 100,000 sets),
  the run's grid and record, netting-set membership, collateral balances, CSAs, credit limits, credit curves (for CVA).

### 6.2 The owner's work

For each of its sets: net paths `V_n(p,k) = Σ MTM_i(p,k)`; collateral per path with the CSA's thresholds, MTA, IA, rounding and the
**margin period of risk** (`C_n(p,k)` from `V_n(p, ℓ(k))`, the lagged model, EXPOSURE_GUIDE section 18.3); initial margin; positive and
negative exposure per path and time step `E_n = max(V − C − IM, 0)`, `N_n = min(V − C, 0)`; float32 rows date-major in its hot file. A
scope that cuts a set (a desk inside a counterparty) is recomputed by the set's owner from the scope's trades, as one server does today.

### 6.3 The partial and the coordinator

- **The partial** for a scope: the **summed positive-exposure path matrix** `Σ_{n ∈ scope ∩ shard} E_n` (and `N` when ENE is asked),
  float64, primary time steps × paths: 50 × 2,000 × 8 = 800 KB (2 MB at 50 × 5,000); plus, for CVA, the per-counterparty EE rows of the
  counterparties it holds (50 doubles each) or their CVA, since unilateral CVA is additive across counterparties.
- **The coordinator** adds the partials in shard order (float64, a fixed set order within each owner), then computes EE, ENE, EEE, EPE,
  EEPE, the PFE quantiles at any confidence, peaks, and CVA (`−LGD Σ EE·DF·ΔPD`, CVA.md section 6.3, itself PLANNED with the CVA wave).
  **Quantiles only after adding, never on partials**: a PFE is not additive (EXPOSURE_GUIDE section 18.4).
- **Contributions** need two rounds: the coordinator finds the paths ranked around the quantile at the peak date, then asks each owner for
  its parts' mean over those paths (the `contribution-window`); EE and EPE contributions are additive and come in the first round.

### 6.4 Sizes, replicas, handover, failure

| Item | P = 2,000, G = 50 (100,000 sets) | P = 5,000, G = 100 (250,000 sets, ASSUMPTION) |
|---|---|---|
| a set's matrices (V, E, N float32) | 800 KB | 4 MB |
| a date, whole bank | 80 GB | 1 TB |
| an owner's share (replicas 2) | 40 GB over 4 owners | 250 GB over 8 owners |
| a partial | 800 KB (E only) | 2 MB |
| a shard at `target-shard-mb: 2048` | about 2,600 sets | about 500 sets |

Replicas, zones, handover, drain and failure behave exactly as VaR's (sections 4.5 to 4.8): the same `ShardOwners`, `ShardHandover`,
`ShardMembership`, `ShardPeers` under the digest service of its own (proposed `exp-shards`). **Pre-deal what-ifs** (`method: paths`) go to
the candidate's counterparty's owner, whose Python `exposure` pool reads the touched sets' matrices locally; a candidate without a netting
set is its own set and may go to any owner.

### 6.5 Tests and the plug-in

- An `ExposureMeasure` (`ShardedMeasure<double[][]>`: name, digest service, key order, bytes per row = P × G × 4 × 3, the matrix partial,
  element-wise add) with its own store and assembly (proposed `ExposureShardBooks`, `ExposureShardAssembly`).
- **Bit-equality evidence** (as `VarShardClusterTest`): the exposure test data cut into shards with two owners each; for every scope kind
  (a set, a counterparty, a group across owners, a legal entity, a desk cutting sets, the bank) EE, PFE at 95% and 99%, EPE, EEPE, peaks and
  contributions equal a reference single server to the bit for nodes and to the cent otherwise, on Delta and Iceberg; while a member joins;
  with one owner down (the replica answers) and both (`503`, then rebuilt).

### 6.6 Migration from the single exposure server

1. **Behind a setting** (proposed `drishti.ccr.exposure.sharding.enabled`, off by default; a block in the counterparty-risk pack like VaR's).
2. **The set-at-a-time load first** (useful alone: lowers the single server's heap), compared against today's load on the test data.
3. **The evidence before switching it on at a bank:** the equality suite above green; official exposure captures of 20 dates identical
   between the reference server and the cluster; the 2.9.1 benchmark run on the cluster (load time per owner, p95 per target of EXPOSURE_GUIDE
   section 27.1); a drill with an owner killed mid-load.
4. **Cut-over:** run both for a week (the single server kept as the reference, read-only), compare the official captures daily, then retire it.

### 6.7 VaR against exposure sharding

| | VaR (built) | Exposure (PLANNED) |
|---|---|---|
| Key | `LEGAL_ENTITY`, `DESK` | `COUNTERPARTY`, `NETTING_SET` |
| Never split | a desk | a netting set |
| Row | a position: 509–1,518 float32 | a set: P × G float32, three matrices |
| Copied skeleton | 0.8 KB a position (heavy, D1) | ~200 B a set (light) |
| Partial | a vector of scenarios, 4–12 KB | a paths × steps matrix, 0.8–2 MB |
| Combine | element-wise add in shard order | the same |
| After the combine | VaR rank, ES, components | EE, EPE, EEPE, PFE quantiles, peaks, CVA |
| Non-additive part | the quantile (VaR) | quantiles, running max (EEE), peaks |
| Node answers | from aggregates, under 1 ms | from per-counterparty statistics or a sum of its sets |
| Load | per owner, its key ranges | per owner, its sets, one at a time |
| Intraday | file batches and Kafka windows | none (a new run reloads) |
| What-if | the basket's vector through the same path | to the counterparty's owner (Python pool) |

---

## 7. Other aggregations

| Aggregation | Where it runs today | How it scales | Plan |
|---|---|---|---|
| **Stress P&L** (`STR`) | stored desk × scenario results, a derived bank total (COMPUTED_ATTRIBUTES section 4.8) on the server holding the pack | small (desks × scenarios) | per-trade scenario P&L is VaR-shaped (a vector a trade): the same `ShardedMeasure` with key `[LEGAL_ENTITY, DESK]` once computed (computed-measures roadmap) |
| **Sensitivities and FRTB SBM** | stored per book | net sensitivities add per factor; charges never add (square roots of correlated sums) | trade-grain sensitivities sharded by desk; the charge on the coordinator from summed net sensitivities |
| **P&L explain, backtesting** | stored (`PNL`); backtesting 2.9.8 rows 3–4 | backtests read official records: one member, small | 2.9.8; the backtest over 250 official dates runs on the coordinator |
| **Computed attributes** (netting-set and counterparty MtM, utilisation) | on demand on the server answering the page | per entity, small | unchanged |
| **BI rollups and caches** | Rūpaka on DuckDB layers per server; a heavy run goes to the model's owner by rendezvous (`RunCluster`); leases in the identity database | add BI servers; runs spread by model | BI sharding by model partition is a later release (VAR_SHARDING section 14) |
| **Regulatory report runs** (2.9.8 framework, 2.9.9 packs) | not built | one run a period: one member, by lease | a BI run or a scheduled job on a BI server, singleton by lease (`CD5`) |
| **Scheduled reports, alerts** | each server's scheduler | — | singleton by lease (`CD5`) so a cluster sends one email, not one a member |

---

## 8. High availability, role by role

| Role | What fails | What users see | Recovers automatically | Operator does |
|---|---|---|---|---|
| Edge | one LB node | nothing (pair, VIP or ALB across zones) | yes | replace |
| Console | a console process or host | a page reload; live tabs reconnect; a stuck channel needs stickiness (`PO-11`) | LB health `GET /readyz` takes it out | restart |
| Coordinator | process or host | console fails over in ≤ 10 s; reads retried once | yes | restart |
| VaR owner | one | nothing for nodes; one slower call | replica answers; rebuild after 35–40 s + T_b | restart |
| VaR owner | both of a shard | `DRS-5215` on scopes needing it, 35–40 s + T_b | rebuild from the lake | restore; tag zones |
| Exposure server (today) | the one server | exposure unavailable until restart; hot files reopened (kept) | ECS restarts the task | restart; plan `CD10` |
| BI server | one | runs on it fail; new runs go elsewhere; unfinished runs adopted after the lease lapses (shared database) | yes with PostgreSQL | none |
| Data-serving | one | forwarded reads go to the other replica of the same table | yes | restart |
| Loader | a job | the date stays as it was (one commit a date: whole or nothing) | the scheduler re-runs; a re-run replaces the date (idempotent) | re-run if the scheduler did not |
| PV stream (Kafka) | the member reading it | updates wait | none: no consumer group, one reader (`var-pv-stream` reads every partition from the beginning); the restarted reader skips what it already wrote | run it on one member only; standby by lease PLANNED (`CD5`) |
| Kafka | a broker | nothing (replication factor 3) | yes | — |
| Database | primary | sign-in and writes fail until the standby is promoted | RDS Multi-AZ: yes; on premises with Patroni: yes | promote; guide `CD8` |
| Lake | S3 region or the file system | open dates answer; new dates not | S3: managed | restore |
| Content store | its backend | Designs unavailable | with PostgreSQL or S3 behind it | — |
| Secrets | the vault | nothing while running (read at start) | — | restore before restarts |

**Databases.** The identity database is SQLite by default (`drishti.identity.database-url`, `jdbc:sqlite:${drishti.data.dir}/identity/drishti.db`);
a cluster needs PostgreSQL (`DRISHTI_IDENTITY_DB_URL`) so users, sessions, edit lanes and run leases are shared and failover works
([WRITE_BACK.md](WRITE_BACK.md): "a cluster with neither ... no failover"). Size its `max_connections` for every member's pool
(OPERATIONS section 16). A streaming standby (synchronous in one site, asynchronous to the recovery site) is PLANNED as a guide and a tested
failover (`CD8`).

---

## 9. Load balancing

### 9.1 Health endpoints

**Today:**

| Endpoint | Process | Means | Gap |
|---|---|---|---|
| `GET /actuator/health/liveness` | server | the JVM answers | — |
| `GET /actuator/health/readiness` | server | Spring is ready (probes on: `management.endpoint.health.probes.enabled`); the ECS health check and the Docker `HEALTHCHECK` use it | **not** gated on open dates, owned shards or joined digests: a restarted owner is "ready" before its shards are |
| `GET /healthz` | console | the process answers (liveness) | — |
| `GET /readyz` | console | the console can reach a server (`503` otherwise) | — |
| `GET /api/v1/var/shards` | server | `safeToStop`, shards held and ready | a drain signal, not a probe |

**PLANNED (`CD1`):** the server's readiness DOWN while the node is joining, while the newest business date of an enabled engine is not open,
or while a shard it owns for an open date is not ready; `OUT_OF_SERVICE` while drained. A readiness group so that the ECS and LB checks can
use it without failing liveness.

### 9.2 Routing per role

- **Users → consoles:** the LB, round robin over healthy consoles (`/readyz`), sticky by cookie today.
- **Consoles → servers:** each console holds the whole cluster's list (`backend.urls`, `discover: true`, `servers.json`), sends a session to
  one healthy server (configured first, then nearest by latency), fails over after `fail-after` (2) errors; DRISHTI_NET section 11.1. Point
  consoles at the **coordinators** (`backend.urls`), not at owners.
- **Coordinators → owners:** never through the LB: signed member calls to advertised addresses.
- **Shard owners are never behind the LB.** Peer-only packs (`peer-only-packs: [market-risk]`) hide their kinds from users of that node.

### 9.3 Spreading heavy work

**Today** a shard's partial goes to its **first ready owner first**; the replica serves only when the first refuses or fails (VAR_SHARDING
section 6), so the first owners carry all the partial load. **PLANNED (`CD6`):** pick among the ready owners by a hash of the request id (or
least outstanding calls), keeping exactly one answer a shard; a shorter call timeout for partials with one hedged retry; an **admission
queue** for ad-hoc whole-bank scopes per coordinator (like BI's `max-concurrent`, `max-concurrent-per-user`), so ten people asking "the
bank without desk X" queue rather than saturate every owner's memory bandwidth (about 10 to 25 such scopes a second over 6 owners, the
capacity model's estimate).

### 9.4 Sticky sessions and the relay

**Today:** a console's live channel lives in the worker that opened it (`drishti-console/core/channel_relay.py` forwards within one host,
signed with `DRISHTI_CONSOLE_RELAY_KEY`); across hosts the LB must be sticky (IMPLEMENTATION_PLAN known gaps; AWS_DEPLOYMENT section 6.3:
"load balancer stickiness stays on"). **PLANNED (`PO-11`, decided, not built):** the channel id carries its host and the relay forwards across
hosts (no stickiness), then with the renderer the browser's stream passes through to the server's SSE (no console state at all).

---

## 10. Zero downtime

### 10.1 Rolling upgrades

One node at a time, in this order: **consoles**, then **BI and data-serving**, then **coordinators**, then **owners one zone at a time**.
For each node: drain (section 4.6; for a console: deregister from the LB and wait for connections to close) → wait `safeToStop` → stop
(graceful, `left` gossiped) → upgrade → start → wait readiness → next. **Today** this is by hand (`drishti.py var shards --drain`,
`POST /api/v1/net/drain`) and the ECS per-node services stop the old task before starting the new (`maximumPercent: 100`,
`minimumHealthyPercent: 0`, `deploy/aws/ecs/templates/node-service.json`), so the drain must precede the service update. **PLANNED:**
readiness that means ready (`CD1`), drain on stop (`CD2`), a rolling runner for VMs and ECS (`CD3`).

### 10.2 Version compatibility: N and N−1

**The rule (PLANNED as a tested rule, `CD4`):** a release must run in a cluster with the previous release for the length of a rolling upgrade:
every member-to-member format is read tolerantly in both directions, and a feature that needs every member on N is switched on only after
the last member is upgraded.

| Contract | Today | Needed |
|---|---|---|
| Gossip and the member table | `Member.PROTOCOL` = 1; unknown JSON fields ignored (`@JsonIgnoreProperties(ignoreUnknown = true)`); a missing `gossipPort` or `switchesHash` tolerated; a member of another protocol listed, never routed | a mixed-version cluster test |
| Capability digest, `var-shards` state | 2.9.7 added `open`; a member older than 2.9.7 has its held dates counted as open (`ShardPeers.State.openOrHeld`) | the same, tested |
| Partials and skeletons | labelled with map digest, data digest and rows; refused when they differ | the partial's format versioned; a cut or format change refused cleanly by N−1 |
| Hot files | format 3 stays readable (RESOURCES section 1, non-goals: only sections are added) | D1's format 4 read by N, format 3 still read |
| ViewModel | `"contract": "1.0"` (the renderer) | N−1 consoles against N servers |
| Data contracts | per kind, additive | unchanged |

**What the drill checks today** (`tools/drill.sh`): the full build and tests on Java 21 and on Java 25; **no** mixed-version cluster.
**Needed (`CD4`):** a drill stage that runs the previous release's server beside the new one (one JVM each) through join, a VaR scope
gathered across both, a drain and an upgrade.

### 10.3 Data loads without downtime

A new date is committed whole by the loader (one Delta commit or Iceberg `replacePartitions` a date, PARALLEL_LOADER section 6), then
announced (`POST /api/v1/packs/{pack}/loads`, forwarded once to every live member, PO-5 built); each member builds the date in the
background and swaps it in when complete (`VarService.swap`), the previous date answering meanwhile. A **restatement** is a re-load of the
date: a new data version, rebuilt and swapped the same way; without a signal the version watch notices within `version-check` (30 s,
`drishti.var.version-check`, `drishti.ccr.exposure.version-check`). Official records keep every result version.

### 10.4 Configuration changes

| Change | Applies | Source |
|---|---|---|
| packs, Sutras, connector files, BI files, AI and MCP files, admin switches | **live** on each member | OPERATIONS section 3, "What changes live" |
| `config/net/*.yaml` (gossip, routing, access, peers) | **live** (watched) | DRISHTI_NET section 14 |
| `application.yaml`, environment (ports, TLS, token secret, OIDC, database URL, `drishti.packs.enabled`, the edition, VaR and exposure settings, sharding) | **rolling restart** | OPERATIONS section 3 |
| the cluster key | rolling: `secret` new + `previous-secret` old, then remove the old | DRISHTI_NET section 9 |

The one configuration document (2.9.7 rows C1–C3) will carry a "live or restart" column per key.

### 10.5 Database and lake maintenance online

- **Lake:** vacuum, compaction and Iceberg snapshot expiry run beside readers (`lake maintain`, `deploy/lake-maintenance.yaml`); a reader on
  an older version keeps its files until the retention (keep it above the longest open date's age).
- **PostgreSQL:** minor upgrades by standby switchover; schema files are complete (no migrations): a schema change is a release note and
  `db copy` (planned, DATABASE.md).

---

## 11. The daily cycle across the cluster

```
 04:00-07:00  overnight runs land: scenario PVs, P&L vectors, exposure paths (EXPOSURE_GUIDE 15.4)
 07:00        loader: drishti-lakeload, one commit a date, the shard-aligned layout (files cut by LEGAL_ENTITY, DESK) + manifest
 07:10        signal: POST /packs/market-risk/loads {status: ready} to ONE member ──► forwarded to every live member (PO-5)
 07:10-07:20  each owner builds ITS shard books of the new date from the lake (file skipping by key range); announces ready
              every member puts the date together (its shards + the others' skeletons) and swaps it in; the old date answers meanwhile
              currency shard books built from the USD ones
 07:20        official end-of-day capture (VarOfficial) of the date: every member today, one by lease PLANNED (CD5)
 07:20-        intraday: file batches and Kafka windows (5 min) applied by every member on its own signal; no shard moves
 any time     the version watch (30 s) reloads a date whose lake version moved without a signal
 warm-up      hot files kept across restarts (keep-on-shutdown true in the images); a restarted member maps them again
```

Timing (ESTIMATED): the loader's 2.3M load 1 to 5 minutes (PARALLEL_LOADER section 11); shard books in parallel across owners, each about
1/6 of a single server's build.

---

## 12. Consoles and users

- **How many:** 300–500 active people (1,500–5,000 named) need 100 to 140 page loads a second: two consoles of 4 workers measured 69–70
  each; deploy **three** for N+1, four to six at 10M-scale user counts; with the client-side renderer (D6) about half.
- **The LB:** TLS, `/readyz`, cookie stickiness until `PO-11`.
- **Sign-in:** OIDC (`DRISHTI_OIDC_ENABLED`, issuer, client id) at the console, or at the LB; one `DRISHTI_TOKEN_SECRET` (or one issuer)
  for the cluster so every member accepts every token; one `DRISHTI_SESSION_SECRET` for every console.
- **Embedding:** the token exchange (ELEMENTS.md) and the optional sign-in window (`auth: 'popup'`, CLIENT_RENDERER section 10.1, PLANNED
  row 2a). The embed signing key (`DRISHTI_EMBED_KEY_FILE`) **must be the same file on every member**: an empty setting makes a key at start
  that "a second server cannot verify" (CONFIGURATION.md).
- **Lenses** (`drishti.lenses`) are per person and per session; editions (`drishti.edition`) are per member and must be equal on all
  (Admin → Net warns; EDITIONS section 6).

---

## 13. Security zones

```
  USERS ──443──► EDGE (LB, WAF, TLS) ──17480 (TLS optional)──► CONSOLES ──18480 TLS──► APP (servers)
                                                                                         │ 18480 + gossip port: member to member only
                                                                                         ├──► DATA: lake (S3 endpoint / NFS), PostgreSQL 5432,
                                                                                         │          Kafka 9092/9094, content store
  ADMIN HOST (bastion) ──► everything, audited          LOADERS ──► DATA only (no user ingress)
```

| Control | Today | Production rule |
|---|---|---|
| TLS at the edge | ALB with ACM (AWS_DEPLOYMENT section 10); nginx example (OPERATIONS section 8) | required |
| TLS console → server and member ↔ member | available (`server.ssl.*`, `https://` advertise URLs, mutual TLS `tls.*` in node.yaml; the gossip port follows the server's TLS) | required in a bank: PLANNED as the default template (`CD9`) |
| Member calls | HMAC over method, path, time, body with `DRISHTI_NET_SECRET`, 60 s window; on-behalf-of tokens 30 s | required; rotate with `previous-secret` |
| Segmentation | security groups: LB → consoles; members → members; members → data | three zones (edge, app, data); owners unreachable from users |
| Gossip port | `auto` (a random free port) | a fixed port for firewalls (`gossip-port: <n>`); OPERATIONS section 12a still says there is no separate gossip port (corrected in 2e) |
| Secrets | environment only (`${DRISHTI_NET_SECRET}` in node.yaml; Secrets Manager in ECS) | a vault; never on disk |
| The token secret | `DRISHTI_TOKEN_SECRET`: a master key (OPERATIONS section 9a-1) | the same on every member; rotated with a rolling restart |
| Audit | every member's audit log; forwarded requests record both nodes | ship to the bank's SIEM |

---

## 14. Operations

### 14.1 Start-up order

1. Database (primary, then standby), Kafka, the lake reachable.
2. Seeds (two or three members named in every node's `peers/`), then the other owners, then coordinators, BI, data-serving.
3. Consoles last (they wait for `/readyz`).
A cold start of the whole cluster: owners build the open dates' shard books (or map kept hot files); coordinators put dates together once
skeletons are available. Whole-cluster stop: in reverse.

### 14.2 Monitoring per role

| Role | Look at | Alert |
|---|---|---|
| every server | Admin → Health (net, disk, direct memory), Admin → Caches, `/actuator/prometheus` | heap after GC > 85% for 10 min; disk red; direct > 80% |
| owners | `GET /api/v1/var/shards` (`drishti.py var shards`): shards held and ready, refusals | any `DRS-5215` in 5 min; a shard with one ready owner for more than 10 min |
| cluster | Admin → Net, `drishti_net_members{state}`, `drishti_net_forwarded_total` | `suspect` > 0 for 5 min; `dead` > 0 |
| consoles | `/readyz`, CPU, pages a second | p95 page > 300 ms |
| loader | job exit code, the manifest | missed expectation (Data loads page) |
| database | replication lag, connections against `max_connections` | lag > 10 s; connections > 80% |

The Admin → Health "Shards" panel is an open item (VAR_SHARDING section 14).

### 14.3 Logs

Standard output to the platform (CloudWatch, journald), files under `data/logs` rotated (D9 in part); `net:` lines name every join, suspicion,
death and refused call; one WARN a change of state, never a stack trace a call (DRISHTI_NET section 12).

### 14.4 Backups

| What | How | RPO |
|---|---|---|
| Lake | S3 versioning + cross-region replication; or the file system's snapshots | minutes |
| Database | PostgreSQL base backup + WAL archive (or RDS automated backups) | seconds to minutes |
| Members' data folders | only configuration and small state matter (`config/`, `data/net/`, `data/identity` on SQLite): nightly (OPERATIONS section 10); hot files and caches are rebuilt from the lake | a day |
| Content store | with its backend | as the backend |
| Secrets | the vault's own | — |

### 14.5 Disaster recovery to a second site

One cluster per site; the second site is **warm standby**, not part of the first site's Drishti\*net (WAN gossip would only slow failover).

| Component | Replication | RPO | RTO |
|---|---|---|---|
| Lake | S3 CRR or storage replication | minutes (replication lag) | none (read in place) |
| PostgreSQL | asynchronous streaming replica | seconds | minutes (promote) |
| Configuration, packs, Sutras | the same artefacts deployed to both (OPERATIONALISING.md) | none | none |
| Servers | the second site's cluster at minimum size, or stopped (desired count 0 on ECS) | — | 10–30 min: start, join, build the newest dates from the lake |
| Consoles, LB | DNS (Route 53 or the bank's GSLB) | — | DNS TTL |

A runbook and a twice-yearly exercise are PLANNED (`CD11`).

---

## 15. Two concrete targets

### 15.1 On-premises VMs (2.3M positions, before D1)

| Node | Count | vCPU / RAM / disk | Key settings |
|---|---|---|---|
| `lb-1`, `lb-2` | 2 | 4 / 8 GB | HAProxy + keepalived VIP, TLS, health `GET /readyz` on 17480, cookie stickiness |
| `con-1..3` | 3 | 4 / 8 GB / 20 GB | `DRISHTI_CONSOLE_WORKERS=4`, `DRISHTI_BACKEND_URLS=https://coord-1:18480,https://coord-2:18480` |
| `coord-1..2` | 2 | 16 / 64 GB / 100 GB | `-Xmx36g`, `drishti.var.sharding.coordinator-only=true`, tags `az:dc1-a` / `az:dc1-b` |
| `var-1..6` | 6 | 16 / 128 GB / 800 GB NVMe | `-Xmx36g`, `drishti.var.threads=0`, tags split over two racks or halls |
| `exp-1` (then `exp-1..4` with `CD10`) | 1 → 4 | 16 / 128 GB / 2 TB NVMe | counterparty-risk; compute pool `exposure` |
| `bi-1..2` | 2 | 16 / 64 GB / 500 GB | Rūpaka, `config/runs/runs.yaml` workers 2–4 |
| `data-1..2` | 2 | 16 / 64 GB / 1 TB NVMe | entity pages, search, World, Market |
| `load-1` (+ standby) | 1 + 1 | 32 / 64 GB / 100 GB NVMe | `drishti-lakeload`, cron or the bank's scheduler |
| `pg-1`, `pg-2` | 2 | 8 / 32 GB / 500 GB SSD | PostgreSQL 16, streaming replication, Patroni |

```yaml
# config/net/node.yaml on var-3 (every member: its own advertise-url and tags)
enabled: true
advertise-url: https://10.20.1.33:18480
tags: [az:hall-b, role:var-owner]
gossip-port: 7946
secret: ${DRISHTI_NET_SECRET}
previous-secret: ${DRISHTI_NET_PREVIOUS_SECRET}
```

```ini
# /etc/systemd/system/drishti-server.service (excerpt; OPERATIONS section 7.2 has the full unit)
Environment=MALLOC_ARENA_MAX=2
ExecStart=/usr/bin/java -Xmx36g -XX:+UseZGC -XX:+ZGenerational -XX:MaxDirectMemorySize=256m \
  -Djdk.nio.maxCachedBufferSize=262144 -XX:TrimNativeHeapInterval=60000 -jar /opt/drishti/drishti-server.jar
ExecStop=/opt/drishti/bin/drain-and-wait.sh      # PLANNED (CD2): drain, wait for safeToStop, then exit
TimeoutStopSec=900
```

### 15.2 AWS

**Today** (`deploy/aws/ecs/`, AWS_DEPLOYMENT.md): one ECS service per node, each one task with the server and its console, EFS for every
written path, S3 for the lake, Cloud Map names for seeds, an ALB to the consoles (`/healthz`), Fargate or EC2 (`register.sh --cache nvme`
puts the **connector** caches on the instance's NVMe on EC2). **Gaps found:** VaR and exposure hot files default under the data folder, so on
ECS they land on EFS (`drishti.var.tier.dir`, `${drishti.data.dir}/var/hot`): at bank scale they belong on NVMe (`DRISHTI_VAR_HOT_DIR`) —
PLANNED `CD7`; the console in each node task means a node's restart takes its console too; consoles as a service of their own exist
(`deploy/aws/ecs/templates/console-service.json`, `maximumPercent` 200, `minimumHealthyPercent` 50).

The target for 2.3M:

| Piece | Choice |
|---|---|
| VaR owners | ECS on **EC2** (Fargate's 120 GB and 200 GB ephemeral limits are too small for an owner): `r7gd`/`r6id`-class 16 vCPU, 128 GB, local NVMe, one task a host, a service per node, tags `az:<zone>` from the task's zone |
| Coordinators, BI, data-serving | ECS on EC2 (`m7i`/`r7i`-class) or Fargate for ≤ 120 GB |
| Consoles | Fargate service `consoles`, 3 tasks, ALB target group, stickiness on |
| Lake | S3 (Delta with conditional writes, or Iceberg), gateway endpoint |
| Database | RDS PostgreSQL **Multi-AZ** |
| Kafka | MSK, 3 brokers over 3 zones |
| Hot files | the instance's NVMe at `/opt/drishti/nvme` (`DRISHTI_VAR_HOT_DIR=/opt/drishti/nvme/var/hot`), EBS gp3 with raised throughput as the fallback |
| Loader | ECS scheduled task (`deploy/aws/ecs/templates/schedule-target.json`, `lake-task.json`) on EC2 with NVMe for the spill |
| Secrets | Secrets Manager (`DRISHTI_TOKEN_SECRET`, `DRISHTI_NET_SECRET`, session, embed key) |

```json
// node-task.json additions for an owner (ready to adapt; the template's own keys stay)
{ "name": "server",
  "environment": [
    { "name": "DRISHTI_HEAP", "value": "36g" },
    { "name": "DRISHTI_VAR_HOT_DIR", "value": "/opt/drishti/nvme/var/hot" },
    { "name": "DRISHTI_VAR_MAX_DISK_GB", "value": "600" },
    { "name": "DRISHTI_VAR_KEEP_HOT_FILES", "value": "true" } ],
  "stopTimeout": 120 }
```

```bash
# a rolling step for one owner (today, by hand; CD3 makes it a script)
drishti.py var shards --drain                      # as an administrator, against that node
until curl -fsS -H "Authorization: Bearer $TOKEN" https://$NODE:18480/api/v1/var/shards | grep -q '"safeToStop":true'; do sleep 10; done
aws ecs update-service --cluster "$ECS_CLUSTER" --service "$NODE" --task-definition "drishti-$ENV-$NODE:$REV"
```

---

## 16. Bill of materials

| Role | Pilot (200K) | 2.3M, 15K portfolios, before D1 | 2.3M after D1 | 10M after D1 (before D1: not practical) |
|---|---|---|---|---|
| Load balancers | 2 × (4 vCPU, 8 GB) or ALB | 2 or ALB | 2 or ALB | 2 or ALB |
| Consoles | 2 × (4, 8 GB) | 3 × (4, 8 GB) | 3 × (4, 8 GB) | 4–6 × (4, 8 GB)¹ |
| Coordinators | in the servers | 2 × (16, 64 GB, `-Xmx36g`)² | 2 × (16, 48 GB, `-Xmx20g`) | 3 × (16, 96 GB, `-Xmx53g`) |
| VaR owners | 3 all-in-one × (8, 32 GB, 200 GB NVMe)³ | 6 × (16, 128 GB, 800 GB NVMe)⁴ | 6 × (16, 96–128 GB, 800 GB) | 16 × (24, 192 GB, 1 TB NVMe)⁴ |
| Exposure | on the all-in-one servers | 1 today; 4 × (16, 128 GB, 2 TB) with `CD10`⁵ | the same | 8 × (24, 256 GB, 4 TB)⁵ |
| BI and reporting | — | 2 × (16, 64 GB, 500 GB) | 2 | 3 |
| Data-serving | — | 2 × (16, 64 GB, 1 TB) | 2 | 3 |
| Loaders | 1 × (8, 32 GB) | 1 + standby × (32, 64 GB, 100 GB NVMe)⁶ | the same | 2 + standby |
| Python compute | on the servers | on exposure owners | the same | 4 × (16, 64 GB) |
| PostgreSQL | SQLite or 1 | 2 × (8, 32 GB, 500 GB) | 2 | 2 × (16, 64 GB) |
| Kafka | optional | 3 brokers (bank's or MSK) | 3 | 3 |
| **Servers (Drishti JVMs)** | **3** | **13 + loader** (2 + 6 + 1 + 2 + 2) → **16** with exposure sharding | **16** | **33** |

¹ Halved by the client-side renderer (RESOURCES section 6.1, estimated 2–3 × a worker). ² Footnote 3 of section 3.2: the whole skeleton on
the heap. ³ Section 3.2 footnote 1. ⁴ RESOURCES_AND_SCALE section 9.5 (capacity model: heap 21.2 GB need ÷ 0.61 → 36g; RAM need 69 GB
÷ 0.61 → 128 GB; NVMe 394 GB ÷ 0.61 → 650 GB, 800 GB provisioned; at 10M, 106 GB ÷ 0.61 → 192 GB, 643 GB → 1 TB). ⁵ Section 3.2 footnote 4.
⁶ PARALLEL_LOADER section 11 (`-Xmx40g`, `memory.budget-mb` 32768, 16 writers, about 1 to 2 minutes for 2.3M positions on 32 vCPU).
Request capacity (capacity model, ESTIMATED): 140 requests a second measured on 24 cores, so about 93 a 16-vCPU and 47 an 8-vCPU server:
6 owners + 2 coordinators about 650 a second at peak, about 330 sustained with half kept free.

---

## 17. Built against planned

| Item | State | Row |
|---|---|---|
| Drishti\*net membership (SWIM, gossip port, digests, signed calls, joining after ready) | **built** (2.5.0–2.6.7) | — |
| VaR sharding (shard map, rendezvous, replicas, zones, pins, handover, drain, scatter-gather) | **built** (2.9.6), off by default | — |
| Shard books released for age; signal forwarded to every member; version watch; disk guard; one cache budget; fixed reduction order | **built** (2.9.7) | A3, PO-5, O9, O5, D3, PO-7 |
| D1 the mapped skeleton | decided, **not built** | D1 |
| The console relay across hosts, then the stateless console | decided, **not built** | PO-11 |
| 10-day VaR; stressed VaR | **PLANNED** | 2b; 2c |
| Readiness gated on dates and shards | **PLANNED** | CD1 |
| Drain on stop (net + shards), stop hooks | **PLANNED** | CD2 |
| The rolling upgrade runner (VMs, ECS) | **PLANNED** | CD3 |
| N/N−1 rule and a mixed-version drill stage | **PLANNED** | CD4 |
| Singleton jobs by lease (official captures, PV stream, scheduled reports, maintenance) | **PLANNED** (BI runs already lease) | CD5 |
| Partials spread over replicas; hedged calls; admission queue for whole-bank scopes | **PLANNED** | CD6 |
| Hot files on local NVMe on ECS | **PLANNED** | CD7 |
| PostgreSQL standby guide and failover test | **PLANNED** | CD8 |
| TLS between members and a fixed gossip port by default | **PLANNED** | CD9 |
| Exposure sharding and the set-at-a-time load | **PLANNED** | CD10 |
| Second-site DR runbook and exercise | **PLANNED** | CD11 |
| A minority warning under a partition | **PLANNED** | CD12 |
| Admin → Health "Shards" panel | open item (mock-ups first) | VAR_SHARDING 14 |

---

## 18. Decisions for the product owner

| # | Decision | Recommendation |
|---|---|---|
| PO-C1 | The reference production topology at 2.3M: 2 coordinators, 6 owners, 2 BI, 2 data-serving, 3 consoles, PostgreSQL with a standby | yes; correct the decks' coordinator to `-Xmx36g` until D1 |
| PO-C2 | `target-shard-mb` default for banks: 512 (2.3M) and 1024 (10M) instead of 2048 | yes, in this document's examples now; the default after the 2.3M benchmark |
| PO-C3 | Exposure shard key: `[COUNTERPARTY, NETTING_SET]` or with a group prefix | `[COUNTERPARTY, NETTING_SET]`; the group prefix only if group scopes dominate |
| PO-C4 | Singletons by a lease in the Drishti database (no ZooKeeper) | yes (`CD5`) |
| PO-C5 | Partials spread over ready replicas instead of first owner first | yes (`CD6`) |
| PO-C6 | Readiness DOWN until the newest dates and owned shards are ready | yes (`CD1`) |
| PO-C7 | N/N−1 as a release rule with a drill stage | yes (`CD4`) |
| PO-C8 | DR: a warm standby cluster per site, never one cluster across sites | yes |
| PO-C9 | On AWS: owners on ECS with EC2 and local NVMe, consoles on Fargate | yes (`CD7`) |
| PO-C10 | Order of the candidates | `CD1`, `CD2`, `CD3` (zero-downtime upgrades), then `CD5`, `CD6`, `CD8`, then `CD10` with D1 |

---

## 19. Glossary

| Term | Plain words |
|---|---|
| **member, node** | one Drishti server process in the cluster |
| **gossip** | members telling a few others, every second, what they know of everyone; the news spreads to all in a few seconds |
| **SWIM** | a well-known way to run membership by gossip with probes to detect failures |
| **heartbeat, incarnation** | a counter a member raises every second to show it is alive; a counter it raises to say "I am alive, ignore that rumour" |
| **suspect, dead, left** | not heard from for a few seconds; not heard from for longer; stopped on purpose |
| **seed** | a member a new one contacts first to learn the rest |
| **shard** | a slice of the positions (a range of legal entities and desks) held and summed by its owners |
| **owner, replica** | a member holding a shard; the second one, ideally in another zone |
| **rendezvous hashing** | each shard scores every member and takes the top ones; everyone computes the same result without asking anyone |
| **coordinator** | the member that takes a question, asks each shard for its part, adds them and computes the figure |
| **partial** | one shard's sum for a question: a vector (VaR) or a matrix (exposure) |
| **skeleton** | everything about a VaR date but its numbers, copied to every member |
| **drain** | asking a member to hand its shards to others before it stops |
| **stable seconds, grace seconds** | how long the membership must stay still before shards move; how long a new holder must be ready before the old lets go |
| **readiness** | a health answer that says "send me work now" |
| **N/N−1** | a new release and the previous one working side by side during an upgrade |
| **RPO, RTO** | how much recent data a disaster may lose; how long until service is back |
| **zone** | a failure domain: a rack, a hall or a cloud availability zone |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
