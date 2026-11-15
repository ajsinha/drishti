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

# The cluster in release i5: the detailed design

**Status: design, nothing built.** This is the detailed design the owner asked for before any i5 cluster code: the cluster on standard
libraries, engines local or remote behind one lookup, modules and parts in the digest, module promotion between clusters, security
between nodes, what Admin and the console show, the tests and the build plan. Coding starts when i4 has shipped and the owner has
answered the questions of [section 13](#13-open-questions-for-the-owner).

It does not repeat its neighbours; each fact has one home:

| For | Read |
|---|---|
| The principles (an engine is local or remote, its callers cannot tell) and an engine's anatomy | [ENGINES_IN_THE_CLUSTER.md](ENGINES_IN_THE_CLUSTER.md) |
| Drishti\*net as it is built today (membership, the digest, routing, the console, placement, deploy) | [CLUSTER_GUIDE.md section 1](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet) |
| Modules, parts, resource classes, placement, deploy, upgrade, unload | [KERNEL_AND_MODULES.md sections 4.11, 7 and 12](KERNEL_AND_MODULES.md#411-modules-in-a-cluster) |
| Live feeds; their last step (L10) uses this design's leases and engine lookup | [LIVE_CONNECTORS_DESIGN.md](LIVE_CONNECTORS_DESIGN.md#78-cluster-which-node-consumes) |
| The decisions already taken (election by mode, the witness, idempotent singletons, promotion as one mechanism) | [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), the i5 rows |

When i5 is built, the operator's facts of this document move into CLUSTER_GUIDE.md and the generated CONFIGURATION.md, and this
document keeps only the design reasoning.

## Contents

1. [What changes, on one page](#1-what-changes-on-one-page)
2. [The cluster on standard libraries](#2-the-cluster-on-standard-libraries)
3. [The thin wrapper](#3-the-thin-wrapper)
4. [Migration: what moves, what is deleted, the settings](#4-migration-what-moves-what-is-deleted-the-settings)
5. [Engines local or remote: the one lookup](#5-engines-local-or-remote-the-one-lookup)
6. [Placement per engine](#6-placement-per-engine)
7. [Data reuse, VaR shards, end of day, isolation](#7-data-reuse-var-shards-end-of-day-isolation)
8. [Modules and parts in the digest](#8-modules-and-parts-in-the-digest)
9. [Module promotion between clusters](#9-module-promotion-between-clusters)
10. [Security between nodes](#10-security-between-nodes)
11. [What people see: Admin, the console, measures](#11-what-people-see-admin-the-console-measures)
12. [Tests](#12-tests)
13. [Open questions for the owner](#13-open-questions-for-the-owner)
14. [Build plan](#14-build-plan)
15. [Risks](#15-risks)

## 1. What changes, on one page

| Job | Today | In i5 | Deleted |
|---|---|---|---|
| Who is in the cluster, who is up | Drishti's own SWIM-style gossip over HTTP (`Gossiper`, `MemberTable`), a crashed node *dead* after about 40 s | **JGroups** behind `Membership`: agreed views, a crashed node out of the view in 1–2 s | the gossip code, the gossip port, `gossip.yaml`, `peers/`, `peers.json` |
| Who owns a one-runner part; what the cluster's configuration is | `FirstLiveElection` (first live placed node by name); placement and switches travel in the digest, *the highest version wins* | **Apache Ratis** (three or more voters), a **database lease** or the two-node fallback, behind `Agreement` (leases with a term, a small versioned register), chosen by `auto` as already decided | `FirstLiveElection`, the version-in-digest adoption of placement and switches |
| What each node offers | the digest: packs, kinds, load, calendar, `services` (engines as a string, VaR shards) | the digest gains **modules, parts and engines as structured parts**; its hash and the node's vitals travel on the membership channel; the full digest is still pulled over HTTP | the `engines` string in `services` |
| Reaching an engine | the public API is forwarded to the least loaded node (`EngineGateFilter`, `EngineCluster`); a dependent engine holds the other's Java bean, so both sit on one node | **one `EngineLookup` by name** for every caller; local = the bean, remote = a generated stub over the signed peer channel; deadlines, busy pass-through, fail-over, provenance | `EngineCluster.runs` and its own node choice; the direct `VarService` injections in other engines |
| An engine's `needs` | checked on the same server (`DRS-1022`) | met **anywhere in the cluster**; `DRS-1022` only when the need is off or placed nowhere; a need that is away refuses calls cleanly and recovers by itself | the *on this server* rule (kept for in-process facilities such as the pivot) |
| Where an engine runs | where its module part is placed and its per-node switch is on | **placement per engine** inside its part; the switch becomes site-wide | per-node engine switch files differing by node |
| A module from UAT to production | copy the `.dmod`, deploy again | **promotion**: the same bytes and a promotion record go to the next cluster, whose own plan and four eyes decide | — |

Request traffic stays where it is: HTTP/2 between nodes on the server port, signed and on behalf of the person; bulk data on Arrow
Flight. Only membership and agreement move onto libraries. Nothing is added for a single server: no library loaded, no port, no folder.

## 2. The cluster on standard libraries

### 2.1 Two jobs, deliberately separate

| Job | Must it work without a majority? | Property |
|---|---|---|
| **Membership and failure detection**: who is here, who is reachable, what each offers | **Yes.** A node cut off from most of the cluster must still know which peers it reaches, serve reads and reach engines on its side | available under partition; views may differ between partitions and merge afterwards |
| **Agreement**: who owns a one-runner part, which placement and switches are in force, which module version is active | **No.** Two owners or two "current" placements are worse than a pause | one answer or none; needs a majority (or one referee) |

One library that does both well without an outside service does not exist in an acceptable licence (2.2), so the design takes the
proven pattern of a membership layer beside a consensus layer (Consul's Serf and Raft are the well-known example), each from the
library that is strongest at it, each behind one small Drishti interface.

### 2.2 The candidates

Facts as known in November 2026; the versions and the cells marked *(spike)* are checked in step C0 before anything depends on them.

| Library | Licence | Does | Maturity | No outside service | Java 21, Windows and Linux | TLS and authentication | Footprint | Verdict |
|---|---|---|---|---|---|---|---|---|
| **JGroups** 5.5.8.Final (taken, 2026-10-15) | Apache 2.0 | membership (agreed views), failure detection (heartbeat, socket and verification protocols), discovery from a static list, merge after a partition, reliable group messages | 20+ years; under WildFly, Infinispan, Keycloak clustering | yes (`TCPPING`: a list of addresses) | pure Java; Java 17 bytecode (runs on 21) | TLS with client certificates on the TCP transport (the `TLS` component of `TCP`, given Drishti's own `SSLContext`), `AUTH` with Drishti's join token, `SYM_ENCRYPT` without certificates | one jar of 2.5 MB, no dependencies | **chosen for membership** |
| **Apache Ratis** 3.3.1 (taken, 2026-09-08) | Apache 2.0 | Raft: leader election, a replicated log and state machine, membership change, snapshots, non-voting listeners | Apache top-level project; the consensus of Apache Ozone, IoTDB and Alluxio's journal | yes (embedded) | Java 8 bytecode; `ratis-thirdparty-misc` 1.1.0 carries Netty's native transports and OpenSSL (`.so`, `.dll`), switched off by the provider so nothing native loads *(spike: Windows file handling of the log folder; confirm no native library is unpacked)* | mutual TLS on its gRPC transport (`GrpcTlsConfig`); **no shared-key authentication**, so its port needs TLS | 21 MB (18 MB of it the shaded gRPC, Netty, protobuf and Guava) | **chosen for agreement** (the owner's first choice of 2026-11-10 stands) |
| jgroups-raft 1.1.x | Apache 2.0 | Raft as JGroups protocols (election, log, state machine) | same maintainers as JGroups; small user base; its log store changed format in 1.1 (2025) | yes | pure Java | inherits the JGroups stack's TLS, `AUTH` and encryption | about 0.3 MB on top of JGroups | **the fallback** if the Ratis spike fails; one stack and one security configuration, but much less field use on the job where a defect costs most |
| Hazelcast 5.x (community) | Apache 2.0 for the community edition | membership, data structures, CP subsystem (Raft) | very mature | yes | yes | TLS is an Enterprise feature | about 12 MB | **out**: since 5.5 the CP subsystem (locks, leader election with a majority) is Enterprise-only, and so is TLS between members |
| Atomix 3.x | Apache 2.0 | membership, Raft, primitives | **unmaintained** since about 2020 | yes | old | — | — | **out**: no maintainer |
| ScaleCube Cluster 2.x | Apache 2.0 | SWIM membership, failure detection, gossip with metadata | small project, last tagged release 2023 *(spike not planned)* | yes | Reactor Netty | basic | small plus Reactor | **out**: thin maintenance; no agreed views; no election |
| Apache Pekko Cluster 1.x | Apache 2.0 | membership (gossip, phi-accrual detector), split-brain resolver, cluster singleton, leases, CRDT data | mature (Akka's lineage) | yes | yes; brings the Scala runtime | TLS on its remoting | about 20 MB and an actor model the code base does not use | **not now**: it would mean adopting actors for two small jobs; stays the option the owner named for clusters past about 50 nodes |
| Apache Helix; Curator; etcd clients | Apache 2.0 | cluster management, leases, election | mature | **no**: ZooKeeper or etcd must run beside Drishti | yes | yes | — | **out**: an outside service (externals are optional) |
| SOFAJRaft; MicroRaft | Apache 2.0 | Raft | JRaft mature in its own ecosystem, RocksDB (native) for its log; MicroRaft small, transport and storage to be written by the user | yes | native library; or own code | limited; own code | — | **out**: a native store, or too much of our own code under a "library" |
| A database table as the referee | — (PostgreSQL, already there) | leases and a versioned register by conditional `UPDATE` on the database's clock | as mature as the database | **needs the shared database** a production cluster already has (CLUSTER_GUIDE 1.1) | yes | the database's own | no new jar | **the `database` mode**, as decided on 2026-11-10; libraries that wrap it (ShedLock, Spring Integration's lock registry) give no term to fence with, so the wrapper holds the two statements itself |

**The recommendation: JGroups for membership, Apache Ratis for agreement.**

1. Each is the most proven open-source Java library for its job, both Apache 2.0, both embedded, both pure Java.
2. JGroups gives **agreed, ordered views**. Every member of a partition sees the same member list in the same order, with a view
   id. Deterministic rules that today wait for the gossip to settle (the VaR shard ranking's `stable-seconds`, the first-live owner)
   get an exact trigger.
3. Failure detection improves at once: a crashed process is noticed when its socket closes (1–2 s) instead of after `suspect-after`
   + `dead-after` (about 40 s); a hung or cut-off node within the heartbeat time-out (10 s by default).
4. Ratis keeps the owner's decision of 2026-11-10 unchanged: **election by mode**, the witness, the database lease for two nodes,
   idempotent singletons in every mode.
5. The price is known and bounded: a second library and a third port in `ratis` mode, and **Ratis needs mutual TLS** on its port
   because it has no shared-key authentication. Section 10 turns that into one rule and one helper command.

**Why not one library.** jgroups-raft would give one stack, one security configuration and a smaller footprint. It is the fallback,
not the first choice, because the consensus log is the one place where field use matters more than tidiness. The wrapper (section 3)
makes the swap a provider, not a redesign. A third option, election from the JGroups view plus a majority rule with no Raft at all
(what application servers do for their singletons), is the leanest but gives no term to fence with and no agreed register for the
placement; it is question Q2.

### 2.3 Agreement by mode (the decision of 2026-11-10, made exact)

`auto` chooses, in this order; Admin → Cluster → Agreement shows the mode and why it was chosen.

| Mode | When `auto` picks it | Leases and register live in | Survives |
|---|---|---|---|
| `local` | one node (Drishti\*net off) | this process; the register is the files on disk as today | — (nothing to agree) |
| `database` | a shared PostgreSQL identity database is configured (`[db.identity]`) | two tables in that database; the database's clock decides expiry | any node loss while the database is up (the database's own high availability is the site's: CLUSTER_GUIDE 6.4) |
| `ratis` | no shared database; three or more voters (a **witness** counts) **and** mutual TLS configured | a Raft group of the voters; log and snapshots under `<data dir>/net/agreement/` | the loss of a minority of voters |
| `first-live` | two voters, no database, no witness | no agreement: the owner is the first live placed node of the membership view; the register is each node's file, the highest version adopted | a clean stop; under a partition both sides may run a singleton, which is safe only because singleton work is idempotent; Health is **amber** with the three ways out |

- The owner's order for two nodes is kept: database lease, else witness (then `ratis` with three voters), else `first-live` with the
  Health warning. `auto` never picks `ratis` without TLS; it says so and falls to the next line.
- **`database` before `ratis`** in `auto` (question Q3): where the database exists it needs no majority, no extra port and no log
  folder, and it is already the referee of BI run leases and the inbox.
- **In every mode singleton work stays idempotent** (lake commits with optimistic concurrency, feeds and captures deduplicated by
  batch id). A lease carries a **term**; a holder checks `stillHeld(lease, term)` before a side effect that is not idempotent by
  itself, and the lake commit of an official result records the term, so a late writer of an older term is refused.
- **Voters** are named, three or five (`[net.agreement] voters=`). Other nodes use the agreement as clients; consoles never vote.
  A **witness** is the same jar started with `role: witness`: membership and agreement only, no lake, no modules, no API but
  health; it runs wherever a small JVM fits (the console's machine).

## 3. The thin wrapper

The libraries are reached only through four interfaces in `net/drishti-net`. A guard test (ArchUnit) holds it: `org.jgroups` is
imported only under `net.member.jgroups`, `org.apache.ratis` only in the Maven module `net/drishti-net-ratis`, which ships in
`lib/` and is loaded only when the mode is `ratis`.

```java
/** Who is here. Available without a majority. */
public interface Membership extends AutoCloseable {
    NodeIdentity self();
    View view();                                    // id, the members in the agreed order, each with name, addresses, roles, state
    void join();                                    // called once the server is ready (section 4.3)
    void leave();                                   // a clean stop: peers drop the node at once
    void onChange(Consumer<ViewChange> listener);   // joined, left, suspected, merged
    void publish(String topic, byte[] small);       // vitals: digest hash, load, drain flag (at most 4 KB)
    void onMessage(String topic, BiConsumer<String, byte[]> listener);
}

/** One answer or none. */
public interface Agreement extends AutoCloseable {
    Mode mode();                                    // LOCAL, DATABASE, RATIS, FIRST_LIVE
    AgreementHealth health();                       // has a majority or a referee, leader, term, voters, why this mode
    Leases leases();
    Register register();
}

public interface Leases {
    void candidates(String lease, List<String> nodesInOrder);   // from the placement; the holder is always one of them
    Optional<Lease> holder(String lease);                       // Lease(node, term, expiresAt)
    boolean stillHeld(String lease, long term);                 // the fencing check
    void onChange(Consumer<String> listener);
}

public interface Register {
    Optional<Entry> get(String key);                            // Entry(key, value, version, by, at)
    boolean compareAndSet(String key, long expected, byte[] value, String by);
    void onChange(String prefix, Consumer<Entry> listener);
}
```

| Provider | What the wrapper writes itself | About |
|---|---|---|
| `JGroupsMembership` | the stack built in code from the settings (no XML file to ship), the mapping of a view to `View`, the cluster key as a join token, the real-address checks of today (`HostAddress`) on every address | 400 lines |
| `RatisAgreement` | one state machine: a versioned key-value map and leases whose expiry is itself a log entry proposed by the leader (so every replica agrees on it); snapshots of that map; voters' configuration | 500 lines |
| `DatabaseAgreement` | two tables in **both** schema files (`cluster_lease`: name, holder, term, expires_at; `cluster_register`: key, value, version, by, at); acquire and renew are one conditional `UPDATE` each on the database's `now()` | 200 lines |
| `FirstLiveAgreement` | the first live candidate of the view; the register as a file, the highest version adopted from the membership channel | 120 lines |
| `LocalAgreement` | in-process; also what unit tests use | 80 lines |
| `SimMembership` (tests) | an in-memory membership with scripted joins, losses and partitions, replacing `SimCluster` | 150 lines |

A **contract test suite** runs the same cases against every provider (acquire, renew, expire, fence, compare-and-set, listeners,
loss of the referee), so a provider cannot drift.

**One lease mechanism.** `SingletonElection` becomes a caller of `Leases` and keeps its shape; the BI run leases
(`config/runs/runs.yaml`, `lease.*`) and the inbox lease move onto `Leases` in the same step, so the product has one way to hold a
lease (to confirm at build, when that code is read line by line).

### 3.1 The JGroups stack

Built in code, bottom to top; every timing has a setting (section 4.4).

| Protocol | For | Replaces today's |
|---|---|---|
| `TCP` with TLS | the transport on `cluster-port`; `bind` and `external` addresses from `advertise-url`'s host | the gossip port |
| `TCPPING` | discovery from `seeds` plus the members remembered in `<data dir>/net/members.json` | `peers/*.yaml`, `peers.json` |
| `MERGE3` | two partitions that see each other again become one view | (did not exist: gossip converged by itself) |
| `FD_SOCK2`, `FD_ALL3`, `VERIFY_SUSPECT2` | a closed socket is noticed at once (`FD_SOCK2` listens on `cluster-port` + 100: open it between the nodes too); a heartbeat every `heartbeat` (2 s), suspect after `suspect-after` (10 s), verified in `verify` (1.5 s) | `interval`, `suspect-after`, `indirect-probes`, `dead-after` |
| `NAKACK2`, `UNICAST3`, `STABLE` | reliable, ordered vitals messages | per-round table swaps |
| `AUTH` | the join token (an HMAC challenge with the cluster key; a node of another cluster or without the key never enters the view) | the HMAC on gossip calls |
| `GMS` | views | the member table |
| symmetric encryption | only when no certificates are configured: the channel is encrypted with a key derived from the cluster key | — |

The gossip states map as follows: `alive` → in the view; `suspect` → suspected, shown in Admin, still in the view until verified;
`dead` and `left` → out of the view (remembered for `forget-after`, 24 h, for display and for discovery); `joining` and `draining`
stay Drishti states carried in the vitals.

## 4. Migration: what moves, what is deleted, the settings

No dual machinery: the old protocol and its files are removed in the same release. A server that finds an old file refuses it in
words that name the new key; it never reads both.

### 4.1 Piece by piece

| Today | In i5 |
|---|---|
| `Gossiper`, `MemberTable`, `GossipTransport`, `MemberState`, `NetClock`, `PeersFile` (`net.member`) | `Membership` and `JGroupsMembership`; `NodeIdentity` and `Member` stay as value types |
| `GossipSettings`, `SeedPeer` (`net.config`); `gossip.yaml`, `peers/<name>.yaml` | `[net.membership]` settings; `seeds` is one list; a seed's expected tags are dropped (the digest already shows each node's tags, and Admin flags a difference) |
| `GossipConnector`, `GossipPath` (server): the gossip port and its reserved threads; `POST /api/v1/net/gossip`, the probe calls | gone; JGroups has its own port and threads, off the busy path by construction |
| `<data dir>/net/peers.json` | `<data dir>/net/members.json` (the last view and the members seen in 24 h; written atomically on a view change and at shutdown) |
| `node.yaml`: `gossip-port` | `cluster-port` (a number; `auto` is gone, because seeds must be dialled) and `agreement-port` |
| `node.yaml`: `enabled`, `node-id`, `advertise-url`, `tags`, `peer-only-packs` | unchanged |
| `node.yaml`: `secret`, `previous-secret`, `tls.*` | unchanged in meaning; may come from `[net.cluster]` and `[net.tls]` (4.4); the key also authenticates the join |
| `routing.yaml`, `access.yaml` | unchanged |
| The digest (`DigestService`, `Digest`, `CapabilityTable`, `Versions`) | stays; pulled over signed HTTP as today; its **hash and the vitals** (load, drain, joining, engine states, open dates) are published on the membership channel on change and every `vitals-every` (10 s), instead of riding the gossip rounds |
| `PeerHttp`, `ClusterKey`, `OnBehalfOf`, `NetHeaders`; view and data forwarding, scatter-gather, the live relay, user-state copying (`StateSync`) | **unchanged**: request traffic is not part of this move |
| `FirstLiveElection` | deleted; `SingletonElection` on `Leases` |
| Placement adopted from the digest's version; `SwitchSync` (commands, packs and engine switches carried in gossip) | the **register**: `placement`, `switches/…`, `modules/active/…`, `net/key-epoch`; each node applies a change when the register tells it and writes its local file as it does today (the file stays the node's readable copy and what a node starts from before it reaches the others) |
| `EngineCluster` (the `engines` service string, `runs`, `about`) | the digest's structured `engines` part (section 8) and `EngineLookup` (section 5) |
| Tests `MembershipTest`, `SimCluster`, the gossip-port tests | replaced by the provider contract suite, `SimMembership` and the multi-node suite (section 12) |
| The protocol number (`1`) | `2`; a node of an older release cannot join (it speaks no JGroups). A cluster moves to i5 by a short full stop, not a rolling upgrade: question Q9 |

About 1,300 lines of `net/drishti-net` and 500 of the server go; about 1,500 come back as providers and the contract suite.

### 4.2 The secrets

- **`DRISHTI_NET_SECRET`** keeps its three jobs and gains one: it signs every HTTP call between nodes, signs on-behalf-of tokens,
  and is now also the join token of the membership channel. It may be given as today (`secret: ${DRISHTI_NET_SECRET}`) or as a file
  (`[net.cluster] secret-file=/etc/drishti/net/secret`, an absolute path readable only by the service user). A key in clear in a
  settings file is still refused.
- **Rotation** keeps its three rolling steps (CLUSTER_GUIDE 1.6). The step the cluster is in is written to the register
  (`net/key-epoch`), so Admin shows *step 2 of 3: 4 of 5 nodes sign with the new key* and step 3 is refused while any node still
  signs with the old one. The membership channel accepts both keys during steps 1 and 2.

### 4.3 Readiness gates

| Gate | Rule | Kept or new |
|---|---|---|
| Join | `Membership.join()` is called only when the web server answers and the connectors have started | kept |
| Joining | in the view but without its peers' digests: reads wait `join-wait`, then `502 DRS-5052` | kept |
| Agreement | changes that need one answer (a placement apply, a module activation, a switch, taking a lease) need `health().decides()`; without it they are refused with `503 DRS-5060` *the cluster cannot decide right now (2 of 5 voters reachable)*. **Reads, forwarding and engine calls never wait for it** | new |
| Singletons | a part whose lease cannot be held or renewed stops its work within the lease time (15 s) and shows *unavailable*, never doubled | new in its exactness |
| Engines | a node is routed to for an engine only when its vitals say `running` (not `starting`, `stopping`) and it is not draining | kept, on the vitals |
| Shards | readiness is `DOWN` until the VaR shards the node owns are built | kept |
| Leave and drain | a drained node publishes `draining`, hands its leases over, then leaves; peers drop it with the next view | kept, faster |

### 4.4 The settings

Every key lives in the settings file (named sections, the one `${…}` resolver, absolute paths), appears in the generated
CONFIGURATION.md and in Admin → Configuration. The YAML files of `config/net/` keep working as the per-node home and may name
these keys as placeholders, as they can today.

```ini
[net.cluster]
name=risk-prod                      ; a node of another name never joins
environment=prod                    ; dev | uat | prod | any word; used by promotion (section 9)
secret-file=/etc/drishti/net/secret
seeds=10.20.1.31:7946,10.20.1.32:7946,10.20.1.33:7946

[net.node]
id=drishti-a                        ; the node's name (node.yaml's node-id)
cluster-port=7946
agreement-port=7947
role=server                         ; server | witness

[net.membership]
heartbeat=2s
suspect-after=10s
verify=1500ms
join-timeout=5s
merge-every=10s
vitals-every=10s
forget-after=24h

[net.agreement]
mode=auto                           ; auto | local | database | ratis | first-live
voters=drishti-a,drishti-b,drishti-c
dir=${drishti.data.dir}/net/agreement
lease-ttl=15s
lease-renew=5s
election-timeout=2s
snapshot-every=10000

[net.tls]                           ; one certificate for the server port, the cluster port and the agreement port
ca-file=/etc/drishti/tls/cluster-ca.pem
cert-file=/etc/drishti/tls/${net.node.id}.pem
key-file=/etc/drishti/tls/${net.node.id}.key
client-auth=need
reload=on                           ; a replaced file is picked up without a restart

[net.engines]
call-timeout=30s                    ; the default deadline of a remote engine call; an engine's own value wins
connect-timeout=2s
uses-timeout=5s
failover=on
breaker.failures=5
breaker.open-for=15s
```

Old keys are refused with their replacement: `gossip-port` → `[net.node] cluster-port`; `gossip.yaml` → `[net.membership]`;
`peers/` → `[net.cluster] seeds`. `drishti.py net check` prints the new text for a folder of the old form; nothing is rewritten by
the server.

## 5. Engines local or remote: the one lookup

### 5.1 The contract an engine offers to other code

Today FRTB-SA and SIMM hold `VarService` itself; that is why they must share a node. What they really use is small (which
positions belong to a scope on a date). In i5 **an engine offers a contract**: a Java interface in its module's API package, with
plain records in and out. Other engines, connectors and kernel services use the contract, never the service class. A conformance
check forbids importing another engine's service package.

```yaml
# META-INF/drishti/engines.yaml (additions per engine)
- id: var
  reach: cluster                       # cluster (default) | node: an in-process facility such as pivot, met on the same server only
  contract:
    interface: com.ash.drishti.banking.api.VarContract
    version: 1                         # the major version; callers and the engine must match
  locality: [date]                     # the argument that keeps a caller on the node that already holds it
  resources: { memory-mb: 4096, threads: 0, max-in-flight: 32, queue: 64 }
  timeouts: { call: 30s }
  official: { capture: true, publishes: [var-official] }
```

Contract rules (checked by a new conformance check, C16):

- Parameters and results are records of primitives, strings, dates, enums, lists and maps of those. No vectors by the thousand: bulk
  is read from the lake or streamed on Arrow Flight (7.1).
- A leading `Principal` parameter is the person: it crosses as the on-behalf-of token, and the engine applies that person's rights
  on its own node.
- Methods are `@Idempotent` (the default: a question) or `@Mutating` (an official capture, a load): a mutating call carries an
  idempotency key and is never retried by the kernel.
- A `DrishtiException` crosses unchanged: code, status, words, the engine and the node. A caller cannot tell a local refusal from a
  remote one, except by the node named in it.

### 5.2 The lookup

```java
public interface EngineLookup {                       // kernel API; the only way to reach an engine from other code
    <T> EngineRef<T> engine(String id, Class<T> contract);
    EngineStatus status(String id);                   // for Health, Admin and "what do I show"
}

public interface EngineRef<T> {
    T get();                                          // the contract: the local bean, or a stub; never null
    boolean available();                              // for `uses`: add the extra only when true
    EngineStatus status();                            // RUNNING here | RUNNING on <nodes> | UNAVAILABLE (why) | OFF | NOT_PLACED | NOT_INSTALLED
    Provenance last();                                // where this thread's last call was computed (5.5)
}
```

Every caller named in the principles goes through it:

| Caller | Today | In i5 |
|---|---|---|
| Another engine's `needs` or `uses` | the service bean injected | `EngineRef` of the contract |
| A pack's reactive connector (`var-engine`) | the engine's bean on the same node | `EngineRef`; the kind is readable on any node where its pack is on |
| The public API (`/api/v1/risk/var`) | `EngineGateFilter` forwards to the least loaded node | the same filter, the node chosen by the lookup's routing (5.3): one policy |
| Commands, pivots, Explain, BI | through the API or the connector | unchanged; they inherit the lookup |
| The live lander's signal to an engine (L10) | the engine on this node | `EngineRef`: the engine wherever it is placed |

**Local** is a direct Java call on the engine's bean through `EngineRegistry.enter` (so a drain still counts it). **Remote** is a
stub the kernel generates from the interface (a dynamic proxy): one signed HTTP/2 call to
`POST /api/v1/net/engines/{engine}/contract/{version}/{method}` on the chosen node, JSON by Jackson, handled there by one kernel
endpoint for all engines that enters the registry and calls the bean. No engine writes transport code.

**Exact numbers.** A `double` crosses as JSON and comes back bit-equal (the shortest text that parses to the same value); `NaN` and
infinities cross as words. The contract suite proves it with the edge values and the VaR goldens.

### 5.3 Which node

For a call to engine *E* with locality key *k* (for example the date):

1. *E* runs **here** → local. Always preferred.
2. Else the candidates are the nodes where *E* is placed, whose vitals say `running`, not draining, that the caller's roles reach
   (`access.yaml`), whose contract major matches, and whose circuit is closed.
3. Among them: a node whose vitals list *k* among its **open dates** first; then a rendezvous hash of (*E*, *k*) so the same
   question keeps going to the same node and its caches stay warm; a node that answered *busy* is skipped for that call.
4. No candidate → refuse at once (5.4). The lookup never waits for a node to appear.

### 5.4 As dependable as local: every failure and what the caller sees

| Situation | What happens | The caller sees | Proposed code |
|---|---|---|---|
| The need is switched off at the site, or placed on no node | the dependent engine is `blocked` at start, as today | *FRTB-SA needs VaR, which is off at this site* (or *is placed on no node*) | `DRS-1022` (words change; *on this server* stays only for `reach: node`) |
| The need is placed, but no node running it is reachable now | the dependent engine **runs**; its state shows `waiting for VaR (drishti-a: unreachable since 12:01)`; the call is refused in milliseconds | `503` *VaR is not reachable right now (placed on drishti-a, which left at 12:01); FRTB-SA will work again when it is back* | `DRS-1035` (new: `ENGINE_NEED_UNAVAILABLE`) |
| The remote engine is busy (its in-flight limit or queue is full) | passed through unchanged, with its retry-after; another placed node is tried first when there is one | exactly the local busy signal; Explain's *busy, retrying* works as today | the engine's own busy code |
| No answer within the deadline | the call is cancelled here and, through the closed stream, on the remote node | `504` *VaR on drishti-a did not answer within 30 s* | `DRS-5053` (existing) |
| The node dies or is cut off before answering an idempotent call | **fail-over**: the next candidate, once per candidate, inside the same deadline | nothing, or the time-out above if none is left; the provenance names the node that answered | — |
| The same, for a mutating call | never retried; the idempotency key makes a retry by the caller safe | `502` naming the node and *not known whether it was applied; ask again with the same key* | `DRS-5052` (existing) |
| A node fails repeatedly | its circuit opens for `breaker.open-for`; calls skip it; one trial call closes it again | fail-over or `DRS-1035` | — |
| The contract major differs (a rolling upgrade) | only nodes of the caller's major are candidates | fail-over to a matching node, else `DRS-1035` with *version 2 of VaR's contract is on drishti-a, this node speaks 1* | `DRS-1035` |
| A `uses` engine is away | never blocks; the extra is left out within `uses-timeout` | the answer says what was left out (*exposure not included: not reachable*) | — |

**No hangs, by construction.** Every remote call has a deadline (`timeouts.call`, else `[net.engines] call-timeout`); a call made
inside a request inherits what is left of that request's deadline, carried in a header, so a chain C → B → A cannot outlive C's
caller. Calls run on virtual threads; a closed or timed-out call closes its HTTP/2 stream, and the remote endpoint interrupts the
computation.

**Recovery without restarts.** A dependent engine never caches *unavailable*: each call asks the lookup, which reads the live view
and vitals. When the remote engine is back, the next call succeeds; the state line clears with the next vitals.

**Circuit, bulkhead and time limit** come from Resilience4j (Apache 2.0) behind the lookup, not from own code *(spike: confirm it is
not already a dependency under another name)*.

### 5.5 Provenance names the computing node

```java
public record Provenance(String engine, String engineVersion, String node, String nodeName, Instant computedAt,
                         List<InputVersion> inputs, String settingsChecksum, boolean official, String via) {}
```

- Attached to every contract answer by the kernel endpoint; `via` is `local`, `remote from <node>` or `failed over from <node>`.
- The public API returns it in the header `X-Drishti-Engine-Node` (existing) and in the answer's provenance block; Explain's *How
  this number was computed* shows *Computed by VaR 3.0.1 on drishti-a at 12:01:03 from scenario-pv risk@812; asked from drishti-c*.
- A computed kind's document carries it; an official record stores the node and the lease term.
- Goldens compare numbers and documents with the provenance's node fields set aside, and assert those separately.

### 5.6 What changes in `EngineRegistry`

| Today | In i5 |
|---|---|
| `State`: running, off, starting, stopping, blocked, not installed | adds **`not placed`** (installed, switched on, placed on other nodes) and the sub-state `waiting` shown with `running` |
| `startAll` and `attach` block an engine whose needs are not `RUNNING` in this registry | a need of `reach: cluster` is satisfied when it is switched on and placed somewhere; only a `reach: node` need must run here |
| `block()` words: *needs X on this server, which is off* | *needs X, which is off at this site* / *placed on no node*; *on this server* only for `reach: node` |
| `set` refuses to turn off an engine others need (`DRS-1021`) | the same check **cluster-wide**: refused while any running engine on any node needs it, naming them |
| The switch is this node's (`config/engines/<id>.yaml`) | the switch is the site's, held in the register and written to each node's file; **where** it runs is placement (section 6) |
| `enter` counts requests for the drain | unchanged; remote contract calls enter too |

Start order within a node stays topological over local needs. **Across nodes there is no start order**: an engine never waits for
a remote need to start (two nodes starting together would otherwise wait for each other); it starts, shows `waiting`, and answers
when the need is reachable.

## 6. Placement per engine

### 6.1 Syntax

`config/placement.yaml` goes to `format: 2`. A part's target still places the part; an optional `engines:` block places single
engines of that part elsewhere.

```yaml
format: 2
version: 12
modules:
  banking:
    parts:
      api:     { roles: [web] }
      compute: { nodes: [drishti-b], max-nodes: 1 }          # the home of every engine of the part not named below
      feeds:   { nodes: [drishti-main, drishti-b] }
    engines:
      var:           { nodes: [drishti-a, drishti-d], max-nodes: 2 }   # two nodes: fail-over and, with sharding on, two owners
      exposure:      { nodes: [drishti-b] }
      sensitivities: { nodes: [drishti-b] }
      # frtb-sa, simm, mr-cube are not named: they run where `compute` is placed (drishti-b)
```

### 6.2 Rules

| Rule | Detail |
|---|---|
| **Default** | an engine not named runs on its part's nodes: a site that places only parts sees no change. A `format: 1` file is read as it is (it has no `engines:`); the first applied change writes `format: 2` |
| **Code follows engines** | the part's code is staged on the union of the part's nodes and its engines' nodes; on each node only the engines placed there start. A node that holds the code but none of the engines running shows them as `not placed`, which is healthy |
| **Guards per engine** | `max-nodes`, `allow-all` and the memory headroom check apply to each placed engine with its own `resources.memory-mb` (the part's `memory-mb` stays the default for engines that declare none) |
| **Needs** | a plan is refused (`DRS-1030`) when it leaves a running engine's `needs` placed nowhere; a `reach: node` need must be placed on every node of its dependent |
| **Same inputs** | every node an engine is placed on must resolve the engine's input kinds to the **same table identities** (the digest already carries them); otherwise two nodes would give two answers, and the plan is refused naming the kind and the two tables |
| **Sharding** | with VaR sharding on, the nodes of `var` are the shard owners; fewer nodes than `replicas` is refused |
| **Kernel engines** | `pivot`, `flight`, `dataload`, `memory-lake`, `openblas` are facilities of a node (`reach: node`), not placed; they stay per-node switches |
| **Change** | plan, four eyes, apply, as for parts today; the apply is a compare-and-set on the register's `placement` key, so two applies cannot both win; `DRS-1031` when the cluster changed since the plan |
| **Moving an engine** | the new node starts the engine and reports `running`; only then does the old node stop taking calls, drain and release. With one node at a time allowed (`max-nodes: 1`) the order is the reverse and the gap is shown in the plan |
| **Single server** | no placement file needed; everything is placed here, as today |

### 6.3 One switch, one placement

The i4 rule (distribution = what exists, module switch = on at this site, engine switch = this compute is on) stays, with the
overlap removed: the **engine switch is site-wide** (on or off for the cluster), and **which nodes** is placement only. Turning an
engine off on one node is a placement change. Admin → Engines keeps one switch per engine and links to the placement for *where*.

## 7. Data reuse, VaR shards, end of day, isolation

### 7.1 Reuse instead of recomputation

| What | Rule |
|---|---|
| **Inputs** | every node reads the same **named lakes** (`[lake.<name>] root=`, absolute, the same on every node). The digest carries each lake's resolved root and each table's identity; Admin flags a lake whose root differs between nodes, and 6.2 refuses an engine placement across differing inputs |
| **Published results** | an engine that publishes official results (`official.publishes`) is **read**, not asked: a dependent that needs the official end-of-day figure reads the published kind through the router like any data. The engine is asked only for what is not published (an ad-hoc scope, a what-if, an intraday figure) |
| **Hot caches** | stay with the engine, on its node. Routing by open dates and by the rendezvous hash (5.3) keeps repeated questions on the node that holds the date; nothing is copied between nodes |
| **Bulk between engines** | when one engine needs another's bulk intermediate (vectors), it is read from the lake where it exists, else streamed on Arrow Flight from the engine's node; never as JSON. i5 builds the seam only if the proof scenario needs it |
| **No caller-side result cache** | not in i5: it would be a second place for a number to go stale |

### 7.2 VaR shards

Sharding stays inside the VaR engine: the lookup routes a VaR question to a node where `var` is placed; that node gathers the
partials from the shard owners with the existing `ShardPeers`. What changes:

- The owners are the nodes of `var` in the placement (6.2); `coordinator-only` stays a VaR setting.
- The shard ranking is computed on the **membership view** and re-run on a view change; `stable-seconds` remains as the wait before
  rows are moved, counted from the last view change, which is now an exact event.
- The shard state leaves the `services` string and becomes part of the structured `engines` digest (section 8).
- Extending shards to another engine uses the same kernel seam (`server/shard`); no second engine is sharded in i5.

### 7.3 End of day in `needs` order, wherever the engines are

- An engine that takes official captures says so (`official.capture: true`) and offers `capture(date, runId)` in its contract
  (`@Mutating`, idempotent by engine, date and input versions).
- One **end-of-day run** per business date is driven by the module's `jobs` part, whose owner holds the lease `banking.jobs`. It
  walks the engines in topological order of `needs`: call `capture` through the lookup (the engine is wherever it is placed), wait
  until the published kind's commit is visible in the lake, then go to the engines that need it.
- The run's state (`eod/<date>`: per engine `pending`, `running on <node>`, `published <table version>`, `failed (why)`,
  `skipped: needs var`) is kept in the register, so a new lease holder continues where the last one stopped and repeats nothing
  that is published. Today's rule (*every member that receives a full-run signal runs the capture*) is replaced by this one run.
- A failed or absent need marks its dependents `skipped` with the reason; nothing is captured from stale inputs. Each step has a
  deadline (`[engine.<id>] capture-timeout`).

### 7.4 Isolation between engines on one node

Engines of one node share one JVM, so isolation is **accounting and admission**, stated honestly: it stops one engine from taking
another's share by the kernel's rules, not by the operating system. Hard isolation is what placement per engine is for: put the
two engines on two nodes.

| Resource | Mechanism |
|---|---|
| **Memory** | each engine gets an `EngineResources` at start with a **budget** (`resources.memory-mb`, overridable in `[engine.<id>] memory-mb`). What the engine holds per open date is reserved against its own budget; over budget it refuses with its own capacity message (VaR and exposure already do; the two existing settings fold into this one). The sum of the placed engines' budgets must fit the node's engine budget at activation (the headroom check of 6.2) |
| **Threads** | each engine computes on its own bounded pool (`drishti-<engine>-…`, `resources.threads`, 0 = the cores it may use), handed out by the kernel; the common pool is forbidden to engines (a guard test); the sum of the pools is checked against the cores |
| **Requests** | a bulkhead per engine (`max-in-flight`, `queue`): beyond it the engine answers *busy* at once, locally and remotely alike |
| **Disk** | hot files and spill per engine under `<data dir>/<engine>/`, each a folder of the existing disk budget |
| **Measures** | budget, held, threads, in-flight and queued per engine and node, in Admin and as metrics (section 11) |

## 8. Modules and parts in the digest

The digest (format 2) gains two structured parts and loses the engines string. The `modules` part completes what
KERNEL_AND_MODULES 4.11.5 and 4.11.6 describe; that section stays the home of what the matrix means.

```json
{
  "modules": [
    { "id": "banking", "version": "3.0.1", "fingerprint": "sha256:9f2c…", "state": "active",
      "parts": { "api": "active", "compute": "active", "feeds": "standby" },
      "contracts": { "var": 1, "exposure": 1 } }
  ],
  "engines": [
    { "id": "var", "module": "banking", "state": "running", "since": "2026-11-15T07:58:02Z",
      "needs": { }, "budgetMb": 4096, "shards": { "date": "2026-11-13", "owned": 9, "ready": 9 } },
    { "id": "frtb-sa", "module": "banking", "state": "running", "waiting": "var: drishti-a unreachable since 12:01",
      "needs": { "var": "drishti-a", "sensitivities": "here" }, "budgetMb": 1024 }
  ],
  "placement": 12, "kernelApi": "1.4", "protocol": 2
}
```

| In the full digest (pulled over HTTP when its hash changes) | In the vitals (on the membership channel, on change and every 10 s) |
|---|---|
| modules, parts, fingerprints, contract versions; engines with needs and budgets; packs, kinds, sources, calendar, tags, access (as today); the lakes' resolved roots | the digest hash; load; `joining`, `draining`; per engine: state, in-flight, busy, memory held, **open dates**; per one-runner part: owner or standby |

- A part's state is `active`, `standby` (a one-runner part placed here, the lease held elsewhere), `staged`, `refused (why)`.
- Drift (red, amber) is computed from the digest against the register's placement, as designed in 4.11.6.
- The vitals are small and frequent so that routing reacts in seconds; the digest is large and changes rarely.

## 9. Module promotion between clusters

**What it means** (IMPLEMENTATION_PLAN, the row *Module promotion between clusters*, owner 2026-11-10): one mechanism, both ways.
The deploy that ships a `.dmod` to the placed nodes of one cluster (upload once → plan → four-eyes apply → two-phase activation)
also targets **another cluster**: dev → UAT → production. The receiving cluster's own plan and its own two administrators decide.
A signed `.dmod` (release j) proves it is the module that passed UAT, and Admin shows which version runs in which environment.
It is the module form of what OPERATIONS_GUIDE 8.5 already says for packs: promote the same bundle, never rebuild.

### 9.1 What travels, and what never does

| Travels | Stays with each cluster |
|---|---|
| the `.dmod`, byte for byte (its SHA-256 is its identity; the same version with other bytes is refused, as today) | **placement** (each cluster has its own nodes) |
| the **promotion record**: module, version, SHA-256, the source cluster's name and environment, who proposed and who approved there, the conformance result (C1–C16), since when it is active there and on how many nodes, the health and the module's own checks at the moment of promotion; signed (from j: with the cluster's certificate; until j: an HMAC with a promotion key the two clusters share) | **bindings**, connectors, lakes, secrets, site settings |
| | approvals: an approval in UAT is evidence in production, never an approval there |

### 9.2 Three ways to move it, one flow on arrival

| Way | For | How |
|---|---|---|
| **Push** | the source can call the target (dev → UAT) | `drishti.py module promote banking@3.0.1 --to uat`, or Admin → Modules → Promote; the target is a named section of the settings file |
| **Pull** | the target may call the source but not the other way (production pulls from UAT) | `drishti.py module promote banking@3.0.1 --from uat`, run against production |
| **Bundle** | no network between them | `… promote --export banking-3.0.1.dpromo` (the `.dmod` and the signed record in one file), carried across, `… promote --import` |

```ini
[promotion.target.uat]
url=https://uat-entry.bank.internal:18480
token-file=/etc/drishti/promotion/uat.token
ca-file=/etc/drishti/tls/uat-ca.pem

[promotion.source.uat]              ; on production: who may promote to us, and what we require of it
environment=uat
key-file=/etc/drishti/promotion/uat.key
require-active-hours=24
require-health=green
```

On arrival every way does the same: the package lands in the target's module store, the record is verified, and a **promotion** in
state `RECEIVED` appears in Admin → Modules → Promotions and in the Approvals queue. From there it is the existing deployment
(KERNEL_AND_MODULES 7.6): plan against the target's own placement → proposed → approved by a second administrator of the target →
applied. There is no second deploy machinery: `POST /api/v1/admin/modules/promotions` stores and verifies, then creates an ordinary
plan.

### 9.3 Versions

- A version is immutable and identified by its hash. Promotion never re-packs.
- **Order**: a target accepts a version only from a source it trusts, and only when the record meets its `require-*` rules; a version
  that skipped an environment is refused in words (*banking 3.0.1 has not been active in uat*). An administrator may record a waiver
  with a reason (four eyes, audited); the environments view marks it.
- A first-party module that declares `kernel-build` needs that kernel build on the target; the plan says so first (*install kernel
  2.9.11.i5 build 4127 before banking 3.0.1*).

### 9.4 Compatibility checks at the target (the plan refuses with the reason; proposed `DRS-1036`)

| Check | Against |
|---|---|
| Kernel API range, and the exact kernel build for a first-party module | the target's kernel on **every** placed node |
| Other modules' ranges (`requires:`) | the versions active in the target |
| Distribution and licence | the target's |
| Data needs | every kind the new version needs has a binding in the target, and the bound contract's version is in range |
| Settings | every required setting of the new version has a value in the target (the plan lists new keys and their defaults) |
| Placement | every part and engine the target's placement names still exists in the new version; new parts and engines are listed as *not placed*; memory headroom per node with the new `resources` |
| Engine contracts | for each engine contract whose **major** changed: every engine in the target that needs it is in the same promotion or already speaks the new major; otherwise refused, because the two would not find each other during or after the roll |
| State and rollback | the version pair is readable both ways, or the module says `rollback: false` for the pair and the plan shows *no rollback after activation* before anyone approves |
| Saved work | reports, alerts and views that name something the new version drops are counted and listed |

### 9.5 Roll-out across the target's nodes

1. **Stage everywhere** it is placed (copy, verify the hash and signature on each node, V1–V9 locally). Any refusal ends the
   promotion with nothing changed.
2. **Canary**: activate on one placed node. Sessions stay pinned to their version (4.11.8); the lookup sends a caller to an engine
   of its own contract major.
3. **Bake** for `bake-minutes` (15 by default): the node's health, the module's own `verify:` checks (for banking: the VaR golden
   through the API) and the error rate against the old version's.
4. **Roll** the remaining placed nodes one by one, each drained, switched and checked.
5. **Done**: the register's `modules/active/banking` moves to the new version; the previous one stays staged for rollback until
   pruned; a signed **receipt** goes back to the source (pushed, pulled or exported), so the source knows *3.0.1 active in prod
   since …* without polling.

### 9.6 Rollback

| When | What |
|---|---|
| A stage or a check fails | nothing was activated; staged files removed |
| The canary fails its bake | **automatic**: the canary goes back to the previous version (the approval of the promotion includes this consent); the promotion ends `rolled back (why)` |
| A failure mid-roll | the switched nodes go back in reverse order; the cluster is never left mixed outside the upgrade window |
| After completion | a rollback is a plan of its own (four eyes), refused in words where the pair is `rollback: false`; official results already published by the newer version stay, each naming the version that computed it |

### 9.7 Which version is where

Admin → Modules → **Environments**: a module × environment matrix (dev, UAT, production) with version, hash, since when, promoted
by whom, and the state of a promotion in flight. Its sources are the promotion records and receipts each cluster holds, and, where
the network allows, `GET /api/v1/admin/modules/environment` of the neighbours named in the settings. A cluster that cannot be
reached shows its last receipt and its age.

## 10. Security between nodes

| Channel | Port | Protected by |
|---|---|---|
| Requests between nodes (forwarding, engine contracts, digests) | the server port | TLS; the cluster key's signature on every call; the on-behalf-of token for the person (unchanged) |
| Membership | `cluster-port` | mutual TLS when certificates are configured, else encryption from the cluster key; the cluster key as join token in both cases |
| Agreement (`ratis`) | `agreement-port` | **mutual TLS, required**: `ratis` is not offered without certificates |
| Agreement (`database`) | the database's | the database's TLS and account |
| Bulk | the Flight port | as today |

- **One certificate per node, one section** (`[net.tls]`) for all three ports. The certificate names the node (a subject
  alternative name holding the cluster name and the node id); a peer whose certificate names another cluster, or a node id other
  than the one it claims, is refused and listed in Admin → Cluster → Security.
- **A helper for sites without a PKI**: `drishti.py net certs init|issue|renew` makes a private cluster CA and node certificates
  with the JDK's `keytool` (Java and Python only; nothing to install; the same on Windows and Linux). A bank's own PKI is the
  normal case; the helper serves development, QA and small sites.
- **Rotating certificates**: a replaced file is picked up without a restart (`reload=on`): the server port and the membership
  channel take the new context for new connections; the agreement port restarts its transport on one voter at a time. Admin shows
  each certificate's expiry and warns 30 days before.
- **Rotating the cluster key**: section 4.2.
- **Joining** needs all of: the cluster name, the key, a certificate of the cluster's CA (when TLS is on), protocol 2, a kernel API
  range the cluster runs (strict mode), and the same distribution.
- **Engine contract calls** are peer calls: signed, on behalf of the person, accepted only from members of the current view, and
  checked again on the receiving node (the person's roles, the nodes their roles reach). A contract endpoint is never reachable
  with a user token alone.
- **Promotion** between clusters uses its own credentials (9.2), never the cluster key.
- **Audit**: view changes, lease changes with their term, register writes (who, what, the version), refused joins, contract calls
  that failed over, each in the audit log of the node that saw it.

## 11. What people see: Admin, the console, measures

### 11.1 Admin → Cluster

| Tab | Shows | New in i5 |
|---|---|---|
| Members | the view (its id and order), each member's state, role (server, witness, console), ports, certificate expiry | view id, witness, the agreement role |
| **Engines** | **which engine runs where**: engine × node, with state, `needs` and where each is met, budgets and use, in-flight and busy, fail-overs of the last hour; *Move*, *Add a node*, *Remove* open a placement plan | the whole tab; three mock-ups: [mockups/cluster-i5/](mockups/cluster-i5/README.md) |
| Modules | the node × module and part matrix, drift (KERNEL_AND_MODULES 4.11.6) | parts' `standby`, engines linked |
| Agreement | the mode and why, voters, leader, term, whether it can decide; every lease with holder, term and expiry; the register's keys with version, who and when | the whole tab |
| Security | signatures refused, certificates, the key-rotation step | the rotation step, join refusals |
| Environments | section 9.7 | the whole tab (under Modules) |

The page is built only from Drishti Elements (the grid, chips, the drawer, the plan dialog); no bespoke code.

### 11.2 The console and what a user sees

- The console stays an **HTTP client** of the cluster. It needs no cluster or agreement port, which matters where consoles sit in
  another security zone. As a placement node (KERNEL_AND_MODULES 10.5) it is recorded in the register by its entry node, with a
  heartbeat; it never votes.
- `about` lists each engine as `{id, title, here, cluster, nodes, state}`. Menus, tiles and command words follow `cluster`: an
  engine that is placed but not reachable stays visible and says *unavailable right now*, as a part does today; only an engine that
  is off or not placed disappears.
- Every figure's Explain and *How this view was built* name the computing node (5.5). A page built from a failed-over call says
  so for administrators only.
- The live channel carries `engine.available`, `engine.unavailable`, `engine.moved`, so an open page changes its notice without a
  reload.

### 11.3 Measures and logs

`drishti_cluster_view_changes_total`, `drishti_cluster_members{state}`, `drishti_agreement_can_decide`, `drishti_agreement_term`,
`drishti_lease_holder{lease,node}`, `drishti_engine_calls_total{engine,from,to,outcome}` (outcome: local, remote, failover, busy,
timeout, unavailable), `drishti_engine_call_seconds{engine,to}`, `drishti_engine_memory_bytes{engine,kind=budget|held}`,
`drishti_engine_in_flight{engine}`, `drishti_promotion_total{module,outcome}`. One log line per view change, lease change and
fail-over, with the request id that the access logs of both nodes already share.

## 12. Tests

### 12.1 Layers

| Layer | What | Runs |
|---|---|---|
| Provider contract suite | the same cases against every `Agreement` provider and both `Membership` providers (section 3) | every build; seconds |
| Unit with `SimMembership` and `LocalAgreement` | routing, the lookup's choices, fail-over, deadlines, placement validation, end-of-day order, promotion checks | every build |
| Multi-node in one JVM | three to five servers started by the existing `NetNodes` harness, real JGroups and Ratis | the affected-tests gate and the drill |
| Separate processes | a node killed without warning; a cold restart from `members.json`; a witness | the drill only |
| Browser | Admin → Cluster → Engines, the unavailable notices, Explain's node line | the drill |

### 12.2 Several nodes on one machine

- **Ports**: each node needs three (server, cluster, agreement). A `PortBlock` gives each test class a block from a base per test
  worker, so classes never collide with each other, with the release drill or with another builder on the machine.
- **Addresses**: the machine's real address, never loopback, as the product requires.
- **Roots**: every node has its own absolute root under the test's temporary folder
  (`<root>/<node>/{config,data,lakes,net/agreement,logs}`) set through the settings file; the shared lake is one absolute folder
  named in every node's `[lake.<name>]`. Nothing is written outside; folders are removed after the run.
- **Size**: small data only (10,000 trades at most), small heaps, one multi-node class at a time, a memory budget per class.
- **Windows and Linux**: the same suite; nothing native is started (no `.exe`), a process is killed with `Process.destroyForcibly`.

### 12.3 Chaos

| Fault | Injected by | Must hold |
|---|---|---|
| Node loss (clean stop; kill) | close the node; kill the process | out of the view within 3 s (kill) or at once (stop); idempotent calls fail over; one-runner parts move within the lease time and never run twice; no request waits past its deadline |
| Partition (2 \| 1; 2 \| 2 \| 1) | JGroups' `DISCARD` protocol in the test stack and a faulty decorator of `PeerHttp` that drops calls between chosen nodes | each side serves what it reaches; only the majority side changes placement or holds leases; the minority's singletons stop; after healing one view, one placement, no doubled official record |
| Slow node | a delay in the decorator; a busy fake engine | callers time out at their deadline and fail over; the circuit opens and closes; *busy* passes through unchanged |
| Loss of the referee | stop the Ratis leader; stop a majority; stop the database | a new leader within seconds; with no majority changes are refused with `DRS-5060` while reads and engine calls continue; the same for the database |
| Flapping | a node joining and leaving every few seconds | no shard storm (rows move only after `stable-seconds`); no lease ping-pong |
| Clock skew | a node's clock moved by minutes | leases unaffected (terms and the referee's clock); signed calls refused past 60 s, as today |
| Restart of everything | stop all, start in any order | the cluster forms from `members.json` and the seeds; the register and the leases recover; no engine waits for another to start |

### 12.4 The proof scenario

Four servers on one machine, one shared lake, the demo data at test size:

| Node | Holds |
|---|---|
| A | `var` |
| B | `exposure`, `sensitivities` |
| C | banking's `api` part, `frtb-sa`, `simm`, `mr-cube`, the pivot; **no** VaR, exposure or sensitivities |
| D (step 6 only) | `var` as well |

1. **From C, everything works**: `VAR BANK`, `VRES DESK:RATES` (the reactive connector), a pivot over `var-result`, Explain of a
   VaR figure, FRTB-SA (needs VaR on A and sensitivities on B), SIMM (uses exposure on B), the MR cube.
2. **Goldens exact**: every number and document equals the single-server run bit for bit, the provenance's node fields set aside;
   the VaR goldens pass unchanged; the provenance names A or B as placed.
3. **Stop A**: VaR questions from C are refused with `DRS-1035` in under a second; FRTB-SA shows `waiting for VaR`; exposure and
   SIMM without VaR keep working; no thread waits.
4. **Start A**: the next question succeeds; nothing on C was restarted.
5. **Slow and busy**: A delayed past the deadline gives `DRS-5053` on time; A busy gives the same *busy* a local engine gives.
6. **Add D to `var`** by a placement plan; stop A in the middle of a run of questions: every idempotent question is answered (by D),
   the provenance says *failed over from A*.
7. **End of day**: the run captures `var` on A, then `sensitivities` on B, then `frtb-sa` on C, in that order; the lease holder is
   killed after the first capture, and the new holder continues without repeating it.
8. **Move** `exposure` from B to C by a placement plan while questions run: none fails.

## 13. Open questions for the owner

| # | Question | Recommendation |
|---|---|---|
| Q1 | **The libraries**: JGroups for membership and Apache Ratis for agreement, with jgroups-raft as the fallback if the Ratis spike fails? | **Yes.** The most proven library for each job; Ratis was already the first choice |
| Q2 | Or the leanest form: JGroups only, the owner chosen from the view under a majority rule, no Raft and no register? | **No.** It gives no term to fence with and leaves placement and switches on *highest version wins*; keep it in mind only if both Raft spikes fail |
| Q3 | In `auto`, the **database lease before Ratis** wherever a shared PostgreSQL is configured? | **Yes.** No majority, no extra port, no log folder; Ratis is then the mode of clusters without a shared database |
| Q4 | **Ratis only with mutual TLS** (it has no key-based authentication), with `drishti.py net certs` for sites without a PKI? | **Yes.** A consensus port without authentication is not acceptable; the helper keeps development easy |
| Q5 | The 2026-11-10 decision said *HTTP stays for gossip*. Membership now moves to JGroups on its own port (the gossip port already exists today, so the number of ports for membership is unchanged). Confirm? | **Confirm.** It is what *membership and failure detection on a standard library* means; requests stay on HTTP |
| Q6 | **The engine switch becomes site-wide**; where an engine runs is placement only (6.3)? | **Yes.** One way per job; a per-node switch beside a per-engine placement would be two answers to one question |
| Q7 | Engines talk to each other only through a **contract interface** with plain records (5.1); FRTB-SA and SIMM stop holding `VarService`? | **Yes.** It is the condition for local-or-remote; the contracts needed in i5 are small |
| Q8 | The **second engine of the proof**: sensitivities and exposure both on node B (12.4)? | **Both.** Sensitivities proves `needs` across nodes (FRTB-SA, SIMM), exposure proves `uses` |
| Q9 | Moving a running cluster to i5 needs a **short full stop** (protocol 1 → 2), not a rolling upgrade. Acceptable? | **Yes.** A bridge between the old gossip and JGroups would be dual machinery for one evening's benefit |
| Q10 | **Fixed cluster ports** (7946, 7947 by default); `gossip-port: auto` goes? | **Yes.** Seeds must be dialled; firewalls need numbers |
| Q11 | **End of day** as one run in `needs` order driven by the `jobs` lease, replacing *every member captures on the signal* (7.3)? | **Yes.** It is the only way to order across nodes; idempotence stays as the safety net |
| Q12 | **Promotion**: push, pull and the offline bundle, all three in i5? | **Push and the bundle in i5**, pull when a site asks; the arrival flow is the same |
| Q13 | Promotion's **automatic rollback of the canary** on a failed bake, consented by the promotion's approval? | **Yes**; a rollback after completion stays four eyes |
| Q14 | Before signing exists (j), the promotion record is protected by a **shared promotion key** between the two clusters? | **Yes**, replaced by the certificate signature in j with no change to the flow |
| Q15 | Fold the **BI run leases and the inbox lease** onto the one `Leases` interface in i5? | **Yes**, in step C3, if reading that code shows no surprise; otherwise first thing in i6 |
| Q16 | The Admin view: **A** (matrix and drawer), **B** (node cards) or **C** (the needs map)? | **A** as the page, with C's needs line inside the drawer; B not built |
| Q17 | A written **runbook for a lost majority** in `ratis` mode (rebuild the group from one surviving voter, by a CLI command run offline)? | **Yes**, written and tested in the chaos suite before the release |

## 14. Build plan

Sizes as in the live-connectors plan: S about a day, M two to three days, L about a week, for one builder. Every step ships its
documents and CHANGELOG lines, deletes what it replaces in the same step, and passes its pre-merge gate.

| Step | What | Size | After | Deletes | Docs with it |
|---|---|---|---|---|---|
| **C0** | **Spike, no product code kept**: JGroups 5 and Ratis 3 on Java 21, Linux and the Windows QA machine, offline; three nodes on one machine with real addresses; TLS on both; a view under kill and partition; the Ratis log folder on Windows; the footprints; the items marked *(spike)*. Outcome: go, or jgroups-raft | M | i4 shipped; Q1–Q5 | — | this document's table 2.2 corrected |
| **C1** | The wrapper: `Membership`, `Agreement`, `Leases`, `Register`; `LocalAgreement`, `SimMembership`; the provider contract suite; the settings and their refusals of old keys | M | C0 | `SimCluster` | CONFIGURATION (generated) |
| **C2** | `JGroupsMembership`; every consumer onto `Membership` (`NetService`, `RunCluster`, routing, drain, readiness, the digest hash and vitals, `members.json`); `drishti.py net check` | L | C1 | `Gossiper`, `MemberTable`, `GossipTransport`, `PeersFile`, `GossipSettings`, `SeedPeer`, `GossipConnector`, `GossipPath`, the gossip endpoints, `gossip.yaml`, `peers/` | CLUSTER_GUIDE 1.1–1.4, 16.4; net.examples |
| **C3** | `DatabaseAgreement` (both schema files), `RatisAgreement`, `FirstLiveAgreement`, `auto`, the witness role and its start script; `SingletonElection` on `Leases`; placement, switches and module activation on the `Register`; run and inbox leases folded (Q15) | L | C1 (beside C2) | `FirstLiveElection`, the digest's version adoption, `SwitchSync`'s transport | CLUSTER_GUIDE 1.9, 6; OPERATIONS_GUIDE (witness, lost majority) |
| **C4** | Digest format 2: `modules`, `engines`, lake roots; the vitals; drift from the register | M | C2 | the `engines` service string | CLUSTER_GUIDE 1.5 |
| **C5** | `EngineLookup`, contracts and C16, the generated stub and the one endpoint, deadlines, busy pass-through, fail-over, the circuit; `EngineGateFilter` on the lookup's routing; `EngineRegistry` (6 changes of 5.6); banking's contracts (`VarContract` and the others the proof needs) | L | C4 | `EngineCluster`'s node choice; engines' direct `VarService` and `FrtbService` injections | ENGINES_IN_THE_CLUSTER (status), ADMIN_GUIDE (engines), DEVELOPER guide (contracts) |
| **C6** | Placement per engine: `format: 2`, validation (needs, same inputs, guards per engine), plan and apply on the register, code staging on the union, moving an engine | M | C3, C5 | per-node engine switch differences | CLUSTER_GUIDE 1.9; KERNEL_AND_MODULES 4.11.2 |
| **C7** | `EngineResources`: budgets, pools, bulkheads; VaR's and exposure's memory settings folded in; the guard test on the common pool | M | C5 | the two separate budget settings | ADMIN_GUIDE; CONFIGURATION |
| **C8** | Provenance end to end (API, Explain, computed kinds, official records); routing by open dates; shards on the view; the lake-root check | M | C5 | — | VAR_GUIDE (provenance line), CLUSTER_GUIDE 5 |
| **C9** | End of day in `needs` order across nodes; the run's state in the register | M | C3, C5 | *every member captures* | CLUSTER_GUIDE 9; VAR_GUIDE |
| **C10** | Security: `[net.tls]` on the three ports, certificate identity, reload, `drishti.py net certs`, the key-rotation step in the register | M | C2, C3 | — | CLUSTER_GUIDE 1.6 |
| **C11** | Admin → Cluster (Engines, Agreement, Members' new columns), the console's `about` and notices, the live events, the measures | M | C4–C8; Q16; the Java console's Admin shell | — | ADMIN_GUIDE; screenshots |
| **C12** | Promotion: the record, push and bundle, the arrival flow, the checks of 9.4, canary and bake, receipts, Environments | L | C3, C6 | — | KERNEL_AND_MODULES 7; OPERATIONS_GUIDE 8 |
| **C13** | The chaos suite and the proof scenario (12.3, 12.4) as the release's gate; the lost-majority runbook tested | M | C5–C10 | — | BENCHMARK (fail-over timings) |
| **L10** | The live connectors' cluster step, on `Leases` and `EngineLookup` | M | C3, C5; L1–L9 | — | LIVE_CONNECTORS_DESIGN, CLUSTER_GUIDE |

**Order with three builders.** After C0 and C1: track 1 = C2 → C4 → C8; track 2 = C3 → C6 → C9 → C12; track 3 starts C5 on
`SimMembership` as soon as C1 is merged, then C7, then C10. C11 needs the console's Admin shell and the owner's mock-up choice.
C13 and L10 come last. About eight to nine builder-weeks; with three builders about four calendar weeks plus the drills (one
feature drill after C9, one hardening drill after C13 and L10).

**Dependencies outside this plan.** i4 shipped (the settings file, modules in their final layout). The Java console (the other
half of i5) for C11's pages. Release j for the promotion record's certificate signature (Q14 covers the time before).

## 15. Risks

| Risk | Mitigation |
|---|---|
| A library does not behave on Windows or Java 21 as expected | C0 before anything else, on the real QA machine; jgroups-raft behind the same wrapper as the fallback |
| Two libraries, three ports, more to configure | `auto` hides the mode; one `[net.tls]` and one key for everything; `drishti.py net check` validates a node before it starts; a single server loads none of it |
| The contract refactor of banking's engines touches VaR, FRTB-SA and SIMM | the contracts are small (scope membership, sums by bucket); the goldens run unchanged after every step; local calls stay direct Java calls |
| Remote calls add latency to chains such as FRTB-SA → VaR | local is always preferred; a site co-places engines that talk a lot (the default of 6.2 does); the measures show each hop |
| A partition with `first-live` runs a singleton twice | idempotent singletons in every mode; Health amber with the three ways out; never chosen when a database or a witness exists |
| Loss of a majority in `ratis` blocks changes | reads and engine calls never depend on it; the runbook (Q17); the database mode where a database exists |
| The register becomes a dumping ground | a fixed, documented list of keys (placement, switches, active module versions, the key epoch, end-of-day runs); a guard test on the prefixes; values small |
| Promotion checks give false confidence | the record is evidence, not approval; the target's four eyes, canary and bake decide; goldens run in the bake |
| Design and code drift apart during a long build | each step's pre-merge gate includes this document's section for it; the document is corrected in the same step |
| Other i5 work (the Java console, live L1–L9) competes for builders and drills | the tracks above use at most three builders; drills are combined with the console's where the dates allow |
