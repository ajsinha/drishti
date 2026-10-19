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
# Drishti*net: the operator's guide

Drishti*net makes several Drishti servers act as one. Each server (a **node**) tells the others, by gossip over HTTP, which packs,
kinds and data it serves. A request that one node cannot answer is answered by another, and the console never knows which: a person
connected to any node sees the same pages. The design, with the reasons behind each choice, is
[DRISHTI_NET.md](../architecture/DRISHTI_NET.md).

**It is off by default.** A server with no `config/net/` folder, or whose `node.yaml` does not say `enabled: true`, is a single server
exactly as before: no thread gossips, no peer is asked, nothing is forwarded, and the cluster's endpoints answer `404 DRS-5050`.

- [1. Before you start](#1-before-you-start)
- [2. A cluster of three](#2-a-cluster-of-three)
- [3. The files of config/net/](#3-the-files-of-confignet)
- [4. Membership: how the nodes find each other](#4-membership-how-the-nodes-find-each-other)
- [5. What each node offers: the capability digest](#5-what-each-node-offers-the-capability-digest)
- [6. Admin → Net](#6-admin--net)
- [7. Drain, maintenance and a node leaving](#7-drain-maintenance-and-a-node-leaving)
- [8. Sizing](#8-sizing)
- [9. When something is wrong](#9-when-something-is-wrong)

## 1. Before you start

| Need | Why |
|---|---|
| The same Drishti version on every node (the same Drishti*net protocol, `1`) | A node that speaks another protocol is listed but never sent a request |
| One **cluster key**, at least 32 bytes, in the environment of every node (`DRISHTI_NET_SECRET`) | Every call between nodes is signed with it; a node without it cannot join or read |
| The same **token secret** (`DRISHTI_TOKEN_SECRET`) or the same OIDC issuer on every node | A person signed in on one node is signed in on all, so a console can move between them |
| Each node reachable by the others on its own port (the address it advertises) | Gossip, digests and forwarded requests are plain HTTP(S) calls to that address |
| Clocks within 60 seconds of each other (NTP) | A signed call is refused outside a 60 s window, so a replay cannot be sent later |

Generate the key, do not invent it, and keep it where only the service user can read it:

```bash
openssl rand -hex 32          # the same value goes into DRISHTI_NET_SECRET on every node
```

The key is never written in a file of `config/net/` in clear: the file names the variable (`secret: ${DRISHTI_NET_SECRET}`), and a
file with a key in clear is refused. It is never shown in Admin → Net either, only whether it is set.

## 2. A cluster of three

Three nodes, `a`, `b` and `c`. Only `a` needs to be named anywhere: `b` and `c` are given `a` as their seed, and gossip tells every node
about every other within a round or two. The examples run the three on one machine on ports 18783, 18784 and 18785; on three machines use
each machine's own address.

**Step 1 · node.yaml on each node.** The same file on each, with its own address and tags:

```yaml
# config/net/node.yaml on node b
enabled: true
advertise-url: http://127.0.0.1:18784     # the address the other nodes and the consoles call this node at
tags: [region:emea, tier:risk]
secret: ${DRISHTI_NET_SECRET}
```

From a terminal, `drishti.py net` writes and checks the same file. It refuses `enabled: true` until the key and the address are there, as the
server does:

```text
$ drishti.py net set node.enabled true
  error   node.yaml  secret: enabled: true needs the cluster key: secret: ${DRISHTI_NET_SECRET} with the variable set (at least 32 bytes)
  error   node.yaml  advertise-url: enabled: true needs advertise-url: the address peers and consoles call this node at
drishti: node.yaml was not written: enabled: true needs the cluster key: secret: ${DRISHTI_NET_SECRET} with the variable set (at least 32 bytes)
(exit 1)

$ drishti.py net set node.secret '${DRISHTI_NET_SECRET}'
wrote ~/drishti/config/net/node.yaml
$ drishti.py net set node.advertise-url http://127.0.0.1:18784
wrote ~/drishti/config/net/node.yaml
$ drishti.py net set node.tags region:emea,tier:risk
wrote ~/drishti/config/net/node.yaml
$ drishti.py net set node.enabled true
wrote ~/drishti/config/net/node.yaml
```

In the UI: **Admin → Net → Files**, open `node.yaml`, type the settings, **Check**, **Save**.

**Step 2 · the seed.** On `b` and `c`, one peer file naming `a`:

```text
$ drishti.py net add-peer drishti-a 127.0.0.1:18783 --tags region:emea
wrote ~/drishti/config/net/peers/drishti-a.yaml

$ drishti.py net list
config/net at ~/drishti/config/net: Drishti*net is ON
  node (generated)  at http://127.0.0.1:18784  tags region:emea,tier:risk  key set
  seed drishti-a  http://127.0.0.1:18783
  node.yaml             in force
  gossip.yaml           absent (defaults)
  routing.yaml          absent (defaults)
  access.yaml           absent (defaults)
  peers/drishti-a.yaml  in force
```

```yaml
# config/net/peers/drishti-a.yaml
url: http://127.0.0.1:18783
tags: [region:emea]
```

In the UI: **Admin → Net → Members**, **Add a seed**, type `127.0.0.1:18783`, **Add seed**. The seed is saved as `peers/127-0-0-1-18783.yaml`
and tried at once; the page says whether it answered.

**Step 3 · start them.** Start the three servers (each with `DRISHTI_NET_SECRET` in its environment). A running server picks a change of the
folder up by itself within `drishti.net.poll` (5 s), so a server that is already running joins as soon as `enabled: true` is saved; switching it
back to `false` makes it leave cleanly. Each logs what it did:

```text
INFO  net: node n-1ace3ceb60 joined Drishti*net at http://127.0.0.1:18784 (incarnation 1, seeds [http://127.0.0.1:18783], 0 saved members)
INFO  net: member n-3d5a69faa3 joined
```

**Step 4 · check.** On any node, **Admin → Net → Members** lists the three, each `alive`, with its version, tags and the latency this node
measured to it. From a terminal (an administrator's token in `DRISHTI_TOKEN`):

```text
$ drishti.py net members --server http://127.0.0.1:18784
3 member(s); this node n-1ace3ceb60
  node                      state  address                 version  tags                   latency  incarnation
  n-1ace3ceb60 (this node)  alive  http://127.0.0.1:18784  2.5.0    region:emea,tier:risk  -        1
  n-3d5a69faa3              alive  http://127.0.0.1:18783  2.5.0    region:emea            0.9 ms   1
  n-8598cc5532              alive  http://127.0.0.1:18785  2.5.0    region:apac            1.1 ms   1
```

In the in-process test of three servers (`NetMembershipTest`), the three list each other 52 ms after the third has started.

## 3. The files of config/net/

Drishti*net is configured the way connectors are ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)): one folder of small YAML files,
watched, each checked against its schema before it is used. `application.yaml` holds only where the folder is and how it is watched
([CONFIGURATION.md](CONFIGURATION.md#drishtinet--drishtinet-the-cluster-folder)):

```yaml
drishti:
  net:
    dir: ${DRISHTI_NET_DIR:./config/net}     # created on first write; ready-made files in config/net.examples/
    watch: ${DRISHTI_NET_WATCH:auto}         # auto (file events, polling as a fallback) | poll | off
    poll: 5s
```

**A refused file keeps the previous settings in force.** A file with an error (a value that is not a duration, a key in clear, `enabled: true`
without a key) is refused as a whole: the settings it held before stay, Admin → Net → Files shows it **refused** with each problem and its line,
and the log names the file and the line. An unknown setting is a warning, not an error. Every write (Admin → Net or `drishti.py net`) is atomic
and keeps the previous text in `<data dir>/net/history/`; Admin → Net shows the history and a diff against the file. What gossip learns at
run time is state, not configuration: it goes to `<data dir>/net/peers.json`, never into this folder.

Ready-made files, with every setting explained, are in [config/net.examples/](../../config/net.examples/).

### node.yaml: this node

| Setting | Default | What it does | A change |
|---|---|---|---|
| `enabled` | `false` | `true` makes this server a member | `false` → `true` joins; `true` → `false` leaves cleanly (gossips `left` first) |
| `node-id` | empty | This node's id; empty: generated once (`n-` and ten hex digits) and kept in `<data dir>/net/node-id` | the node leaves and joins again with the new id |
| `advertise-url` | none (needed with `enabled: true`) | The address the other nodes and the consoles call this node at | gossiped at once |
| `tags` | `[]` | `key:value` words (`region:emea`, `tier:risk`) that `access.yaml` names | gossiped at once |
| `peer-only-packs` | `[]` | Packs this node serves to its peers only, hidden from its own users | the next request |
| `secret` | none (needed with `enabled: true`) | The cluster key, **only** as `${DRISHTI_NET_SECRET}`; at least 32 bytes | the next call |
| `previous-secret` | none | A second key also accepted (never used to sign), during a key rollover | the next call |
| `tls.*` | none | Mutual TLS between nodes: the shared TLS settings ([TLS.md](../connectors/TLS.md)) for an `https://` peer | the next call |

### peers/&lt;name&gt;.yaml: the seeds

One file per seed, the file name its logical name (lower-case letters, digits and dashes). A new file is contacted at once; a deleted file is
no longer a seed, but gossip may still know the node.

| Setting | What it does |
|---|---|
| `url` | The peer's address (`http://` or `https://`, with a host) |
| `tags` | The tags it is expected to carry |
| `tls.*` | Overrides of node.yaml's `tls.*` for this peer |

### gossip.yaml: the protocol's timings

| Setting | Default | What it does |
|---|---|---|
| `interval` | `1s` | How often a round runs: a push-pull exchange with `fanout` random members |
| `fanout` | `3` | How many members each round exchanges tables with (1 to 16) |
| `suspect-after` | `5s` | A member whose heartbeat has not risen this long is probed (at least `interval`) |
| `indirect-probes` | `2` | How many other members are asked to probe it when it does not answer directly |
| `dead-after` | `30s` | How long a member stays suspect before it is dead |
| `forget-after` | `24h` | How long a dead or left member is remembered, so a stale table cannot bring it back |
| `persist-every` | `10m` | How often `peers.json` is saved (also on every join or leave, and at shutdown) |

Applied on the next round.

### routing.yaml and access.yaml

`routing.yaml` (forward and scatter budgets, the hop limit, the peer choice weights, user state) and `access.yaml` (the nodes each role may
reach) are read and checked from the start; what they change is described with routing and access (sections to come with them).

## 4. Membership: how the nodes find each other

SWIM-style membership, carried over HTTP:

- **Every `interval`** a node raises its own **heartbeat** and does a push-pull exchange (`POST /api/v1/net/gossip`) with `fanout` random
  members: it sends its member table and merges the one it gets back. With fanout 3, news reaches 100 nodes in about five rounds.
- **The merge rule:** the higher **incarnation** wins; at equal incarnation the worse state wins (`left` > `dead` > `suspect` > `alive`); a node's
  own entry is only ever changed by itself. A node that hears it is suspected raises its incarnation and gossips `alive`.
- **Failure detection:** a member whose heartbeat has not risen for `suspect-after` is probed directly (`GET /api/v1/net/ping`), then through
  `indirect-probes` other members; still silent, it is **suspect**; suspect for `dead-after`, it is **dead**. With the defaults a crashed node is
  dead everywhere within 40 seconds (the in-process test of five nodes measures it).
- **Leaving:** a server stopping cleanly, or switched off in node.yaml, gossips `left` before it stops, so its peers drop it at once.
- **Joining:** a node contacts its seeds, and failing those the members its `peers.json` lists; one exchange gives it the whole table. With no
  seed and no file it is a cluster of one, and keeps trying.
- **Persistence:** `<data dir>/net/peers.json` holds this node's id and every member with its last-known state; it is written atomically
  (a temporary file moved over it) every `persist-every`, on every join or leave and at shutdown. A restarted node reads it, raises its
  incarnation above the saved one and rejoins from the members it lists, with no seed at all. A file that cannot be read is ignored with a
  warning (the node then joins from its seeds).
- **Forgetting:** dead and left members are kept `forget-after` (24 h), then dropped.

Every call between nodes is signed with the cluster key (an HMAC over the method, the path and query, the time, the calling node and the body)
and refused outside a 60 s window: a node without the key cannot join, gossip or read, and each refused call is listed on Admin → Net with the
address and the reason (`401 DRS-5051`).

## 5. What each node offers: the capability digest

Each node publishes a **digest** of what it serves (`GET /api/v1/net/digest`), and gossips its hash with its member entry; a peer pulls the
digest again when the hash changes (and once a minute anyway, for the load). The digest holds:

| Part | What it says |
|---|---|
| `packs` | every pack switched on: name, code, version, a hash of its files, and whether it is served to peers only |
| `kinds` | every kind of those packs: its mnemonic, its pack, and its **sources**: connector, plugin, **table identity**, the newest business date it holds, its data version, whether it keeps columns, is dated, is live |
| `load` | requests a second and the average latency over the last full minute, for choosing between peers |
| `calendar` | the business calendar (`DRISHTI_CALENDAR`): an as-of date must mean the same day on both nodes |
| `tags`, `redact`, `access` | the node's tags, the fields it masks and its nodes per role, so Admin → Net can show where nodes differ |

A node rebuilds its digest every gossip round from cheap reads (no data is read) and announces a change at once, so **a pack switched on at one
node is in every node's table within about a second** (1.0 s measured with the default 1 s round, three servers). From all the digests each node
builds the **capability table**: for each kind, the nodes that render it and the nodes that hold its data.

**Replicas and shards.** A source names where its data lives, the same on every server that reads the same data: a Delta table by the id in its
log (or, failing that, its location; a local path counts only on its own machine). Two nodes whose sources name the same table are **replicas** for
that kind: a search asks one of them. Different tables of the same kind (ENDUR's trades on one node, CALYPSO's on another) are **shards**: a search
asks every one. A source that cannot say where its data lives is taken as a shard of its node, which is always safe.

**The newest pack is served.** When nodes hold different versions of a pack, the newest is the one served; the map marks it.

## 6. Admin → Net

**Admin → Net** (`/admin/net`, administrators) is the cluster as the node the console is on sees it. Its summary line says whether the cluster is
on, this node's id, how many members are in each state, whether the key is set (never the key) and whether this node is drained.

**Members.** This node (id, address, tags, peer-only packs, version, the gossip timings and how many rounds it has run, where `peers.json` is and
when it was saved), then every member: state, address, version (and protocol when it differs), tags, packs, when it was last heard, the latency
measured to it, its incarnation. The table refreshes every 5 seconds. Actions:

| Action | What it does | API | Audited as |
|---|---|---|---|
| **Add a seed** | `host:port` or an address, saved as `peers/<host>-<port>.yaml` and tried at once | `POST /api/v1/net/seeds` | `net-seed-added` |
| **Remove** (a dead or left member) | Takes it out of the table; an alive one is refused, since the next round would bring it back | `DELETE /api/v1/net/members/{id}` | `net-member-removed` |
| **Drain** / **Undrain** | This node takes no forwarded work, and tells its peers; its own users are served as before | `POST /api/v1/net/drain`, `/undrain` | `net-drain`, `net-undrain` |
| **Leave** / **Rejoin** | Gossips `left` and serves its peers nothing, without switching the cluster off; Rejoin comes back with a higher incarnation | `POST /api/v1/net/leave`, `/rejoin` | `net-leave`, `net-rejoin` |

**Capability map.** For each kind: its mnemonic and pack (with the newest version in the cluster), the nodes that render it (the pack's version on
each, **newest** or **older**, **peer-only**, **not routed** for a node that is drained or suspect), the nodes that hold its data (connector, plugin,
**replica** or **shard**, the newest business date), and which nodes a search of it asks. Type in **Filter** to narrow it by kind, mnemonic, pack or
node. Above the table, the **warnings**: one version of a pack with different contents on different nodes, different business calendars, different
masked fields. The same at `GET /api/v1/net/capabilities`.

**Files.** Every file of the folder, **in force**, **refused** (with its problems) or **not there (defaults)**. Open one to edit it: **Check**
says what the server would make of the text without writing it; **Save** writes it with the version you read (`If-Match`), so two administrators
cannot overwrite each other; **Delete** removes it (its text stays in the history). The history lists the earlier texts; opening one shows a
line diff against the file. **New peer file** starts `peers/<name>.yaml`.

Every action and every save is in the audit log with the node it was made on.

## 7. Drain, maintenance and a node leaving

**Maintenance of one node** (an upgrade, a restart, a new disk): **Drain** it first. It keeps gossiping and keeps serving its own users, but
its peers stop sending it forwarded work, and a forwarded request that reaches it is answered `503` so the caller tries the next peer. When it
is back, **Undrain**.

**Taking a node out for a while:** **Leave**. Its peers mark it `left` at once and send it nothing; **Rejoin** brings it back. To take it out
for good, set `enabled: false` in its node.yaml (it leaves cleanly) or stop the server (the same); after `forget-after` the others forget it,
or **Remove** it from their tables at once.

**A node that crashed** is suspect after `suspect-after` and dead after `dead-after` more; when it restarts it rejoins from its `peers.json`.

## 8. Sizing

Gossip is small: one round sends the member table (about 300 bytes a member) to `fanout` members, and gets theirs back. With 10 members, the
defaults cost each node 3 exchanges a second of about 3 KB each way, and the failure detector a probe now and then. Raise `interval` (and
`suspect-after`, `dead-after` with it) on a large cluster over a slow network; lower `dead-after` to drop a crashed node sooner, at the cost of
declaring a node that paused (a long garbage collection) dead and having it refute.

## 9. When something is wrong

| Symptom | Look at | Fix |
|---|---|---|
| A node lists no members | Its log: `net: node ... joined ... seeds [...]`; Admin → Net → Files: is `node.yaml` **refused**? | The seed's address and port, the firewall, `enabled: true` with the key and the address |
| `401 DRS-5051 cluster credential refused: bad signature` in a node's log, the caller listed on Admin → Net | The two nodes' `DRISHTI_NET_SECRET` | The same key on every node (during a rollover, see the key's rotation) |
| `... signed 75 s away from this node's clock` | The two machines' clocks | NTP on every node |
| A member flaps between `alive` and `suspect` | The latency on Admin → Net, the log of the flapping node (long pauses) | Raise `suspect-after`, or fix the network or the pauses |
| `404 DRS-5050 Drishti*net is off on this node` | The node's `node.yaml` | `enabled: true` (and the node is not left: Rejoin) |

More in [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md#drishtinet).
