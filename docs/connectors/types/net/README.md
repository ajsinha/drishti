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
connector: net
category: built-in
summary: A Drishti*net peer as a source - a read none of this node's sources answered is asked of the peer that holds it, over signed HTTP for the caller; one per peer, named net:<node>.
capabilities:
- fetch
- subscribe
- history
settings:
- node.yaml enabled
- node.yaml secret
- node.yaml previous-secret
- node.yaml advertise-url
- node.yaml tls.*
- peers/<name>.yaml url
- peers/<name>.yaml tags
- peers/<name>.yaml tls.*
- routing.yaml forward-timeout
- routing.yaml link-budget
- routing.yaml join-wait
- routing.yaml max-hops
- routing.yaml peer-protocol
- access.yaml roles.<role>.nodes
auth: The cluster key (secret as ${DRISHTI_NET_SECRET}, at least 32 bytes, the same on every node) signs every call (HMAC, 60 s window), or mutual TLS (tls.*); each read carries a 30 s on-behalf-of token for the caller.
licence: Part of drishti-server; no client library beyond the JDK's HttpClient (HTTP/2 between nodes by default).
windows: Works the same on Windows (pure Java); peers must advertise the machine's real IP, never localhost or 127.0.0.1.
doc: docs/admin/OPERATIONS.md
---
# The net connector

Built in (`drishti-server`, `com.ash.drishti.server.net.PeerSource`): when Drishti*net is on, every peer that holds data becomes a
source named `net:<node id>`, so a page renders the same whichever node holds the entity. There is no plugin to install and no
connector file: it exists while `config/net/node.yaml` says `enabled: true` and the peer is a member.

## What it reads

A `PeerSource` is not registered with the source registry: the router asks the peers only **after** its local sources, through its
fallback, and only for a read made for a person (a background read acts for nobody, so a peer is never asked for it). It asks the peers
whose capability digest says they hold the kind (and the date asked), skipping replicas of tables this node reads itself. The peer
answers from its own sources only (one hop), masked for the caller there; this node renders with its own Sutra and masks again. Three
calls, all signed with the cluster key and an on-behalf-of token:

| Read | Call to the peer |
|---|---|
| one entity, Live or on a date (with *known at*) | `GET /api/v1/net/fetch/{kind}/~?id=<id>` with `X-Drishti-As-Of` and `X-Drishti-Known-At` |
| a history over business dates, in one call | `GET /api/v1/net/history/{kind}/~?id=&from=&to=&lastDays=&dates=&fields=` |
| a live view | `GET /api/v1/net/subscribe/{kind}/~?id=`: server-sent events, one upstream per entity per role set, reopened after a second if it breaks |

It does not search or take part in BI scans: searches and type-ahead are scattered to the nodes separately, and no reverse lookups.

## Settings

The peer source has no settings of its own; it follows the Drishti*net files in `config/net/` (the folder named by `drishti.net.dir`,
ready-made copies in `config/net.examples/`). The ones that shape it:

| Setting | Type | Default | Meaning |
|---|---|---|---|
| `node.yaml` `enabled` | boolean | `false` | `true` makes this server a member; the peer sources exist only then |
| `node.yaml` `secret` | secret | none (needed with `enabled: true`) | The cluster key, only as `${DRISHTI_NET_SECRET}`; signs every call |
| `node.yaml` `previous-secret` | secret | none | A second key also accepted during a rollover |
| `node.yaml` `advertise-url` | URL | this machine's primary IPv4 address with the server's port | The address peers call this node at; `localhost`, loopback, wildcard and link-local addresses are refused |
| `node.yaml` `tls.*` | TLS settings | none | Mutual TLS between nodes ([TLS](../../TLS.md)) |
| `peers/<name>.yaml` `url` | URL | none | A seed peer's real address; one seed is enough, gossip finds the rest |
| `peers/<name>.yaml` `tags`, `tls.*` | list, TLS settings | none | The tags it should carry; TLS overrides for this peer |
| `routing.yaml` `forward-timeout` | duration | `5s` | One peer fetch (a history waits at least this, or the read's budget) |
| `routing.yaml` `link-budget` | duration | `1s` | A view's linked entity that only a peer holds |
| `routing.yaml` `join-wait` | duration | `2s` | A node still joining waits this long for its peers' digests before `DRS-5052` |
| `routing.yaml` `max-hops` | integer | `1` | A forwarded request is never forwarded again; `0` switches forwarding off |
| `routing.yaml` `peer-protocol` | `http2` or `http1` | `http2` | Data calls multiplexed over one HTTP/2 connection per peer |
| `access.yaml` `roles.<role>.nodes` | list | every node | The nodes (ids or tags) a role may reach; a kind held only elsewhere is not held |

## Data structure

- **Source name:** `net:<node id>` (the peer's `node-id`, else the id it generated once, `n-` and ten hex digits).
- **A document** comes back as `{provenance, data, deleted}`: `data` is the entity's raw document as the peer's source holds it (masked
  for the caller there); `provenance` keeps the peer's own `source`, `generation`, `fetchedAt`, `live` and `businessDate`;
  `deleted: true` is a deletion pushed to open views. `404` is not held; any other status but `200` is a failure naming the peer.
- **A history** is `{days: [{date, provenance, data, versions}], failed: {<date>: <reason>}}`; days two peers both answer keep the
  higher generation.
- **A live stream** is `event: doc` server-sent events, one JSON document of the shape above in each `data:`.
- **Id rule:** the entity's own kind and id; the id goes in the query string (`/~?id=`), so ids holding `/` travel intact.

## Examples

```yaml
# config/net/node.yaml on node b
enabled: true
advertise-url: http://192.168.1.21:18480      # the machine's real address (or leave it out to have it detected)
tags: [region:emea]
secret: ${DRISHTI_NET_SECRET}

# config/net/peers/drishti-a.yaml
url: http://192.168.1.20:18480
```

With `DRISHTI_NET_SECRET` the same on both (`openssl rand -hex 32`), node b opens a trade only node a holds as if it were local; the
view's provenance names the node for administrators, and Admin → Net → *Routing* explains where a request would go.

## Troubleshooting

- `502 DRS-5052 … cannot be reached now: n-a (left)`: the node that holds the data is down, left or refusing connections; start or rejoin it.
- `502 DRS-5052` "this node is still joining Drishti*net …": a node just started has not got its peers' digests yet (at most `join-grace`).
- `504 DRS-5053` / `DRS-1014`: the peer did not answer within `forward-timeout`.
- `401 DRS-5051 cluster credential refused: bad signature`: the nodes' `DRISHTI_NET_SECRET` differ; `… signed 75 s away`: fix NTP.
- `node.yaml was not written: 'http://127.0.0.1:…' is a loopback or wildcard address`: use the machine's real address the message names.
- Links *pending* on one node only: the peer answers slower than `link-budget`.
- Health of a peer source is always `UP (Drishti*net peer <node>)`; the cluster's state is in Admin → Net and Admin → Health.

## See also

- [Drishti*net: the operator's guide](../../../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one): a cluster of three, the files, security, routing, Admin → Net.
- [Drishti*net: the design and as built](../../../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti) and [history queries](../../../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time).
- [Connector files](../../CONNECTOR_FILES.md) (the net files are managed the same way) and [TLS](../../TLS.md).
