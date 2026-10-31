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
# drishti-stack: a ready-made local Drishti*net cluster

Three Drishti servers on one machine acting as one cluster ([OPERATIONS.md](../docs/admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one)), and a console that is given the first
server alone and learns the other two from the cluster. One command starts it, one stops it, and one shows how it is doing.

```bash
drishti-stack/start.sh       # start what is not running: the three servers, then the console
drishti-stack/status.sh      # the processes and ports, the members, the console's servers
drishti-stack/stop.sh        # stop everything start.sh started (by process id, never by name)
```

Open the console at `http://<this machine's address>:17480` (start.sh prints it) and go to **Admin → Net**: the three members are alive.

## What runs where

| Process | Address | What it serves | Its data |
|---|---|---|---|
| `drishti-main` | `http://<this machine's address>:18480` (start.sh prints it) | your own stack: the seven default packs, the demo lakes, live news | the repository's `data/` and `config/connectors/` (it runs in the repository, as from the IDE) |
| `drishti-b` | `http://<this machine's address>:18791` (start.sh prints it) | synthetic ENDUR and CALYPSO trades (ids `10000000`+, `CLY-30000000`+), pack `trading` | `data/stack/nodes/drishti-b/`, lake `data/stack/lakes/shared/` |
| `drishti-c` | `http://<this machine's address>:18792` (start.sh prints it) | synthetic MUREX trades (`MX-50000000`+), packs `trading`, `counterparty-risk`, `market-risk` | `data/stack/nodes/drishti-c/`, lake `data/stack/lakes/murex/` |
| console | `http://<this machine's address>:17480` (start.sh prints it) | the console, given `drishti-main` alone; it learns `drishti-b` and `drishti-c` and fails over to them | the repository's `data/console/` |

**The nodes advertise this machine's real address, never 127.0.0.1** (members never advertise localhost, a loopback or a wildcard address:
[OPERATIONS.md](../docs/admin/OPERATIONS.md#2-a-cluster-of-three)). `start.sh` finds it (the default route's interface, never a docker bridge) and gives
it to every node as `DRISHTI_STACK_HOST`, which the checked-in `node.yaml` and seed files name (`advertise-url: http://${DRISHTI_STACK_HOST}:18480`),
so the files stay the same on every machine; the console is given `drishti-main` at that address too, so its discovered list matches what the
cluster advertises. On a machine with several interfaces, set `host:` in `stack.yaml`. `status.sh` shows each member's advertised address.
The local addresses in the table above still open the pages from this machine.

Open `/v/trade/10000001` on the console: the trade is held by `drishti-b` only, and `drishti-main` forwards the read to it
(**How this view was built** names the node that answered, for an administrator).

## The files

| File | Checked in | What it is |
|---|---|---|
| `stack.yaml` | yes | the nodes (port, heap, packs, lake, connectors switched off), the lakes, the shared settings and the console. `start.sh` reads it |
| `start.sh`, `stop.sh`, `status.sh`, `stack.py` | yes | the commands (`stack.py` does the work; the console's Python runs it) |
| `nodes/<node>/net/` | yes | each node's Drishti*net folder: `node.yaml` (its id, its address as `http://${DRISHTI_STACK_HOST}:<port>` and `secret: ${DRISHTI_NET_SECRET}`, references, never the key or this machine's IP), `gossip.yaml`, `peers/` (the seed) |
| `secrets.env.example` | yes | the names of the secrets, no values |
| `secrets.env` | **no** (git-ignored) | the secrets themselves, mode 600 |
| `run/` | **no** (git-ignored) | `server.jar` (the copy the servers run), `pids.txt`, `logs/` |
| `../data/stack/` | **no** (`data/` is git-ignored) | the nodes' data and connector folders, the synthetic lakes |

On the first start `start.sh` makes what is missing: the secrets, `run/server.jar` (a copy of the newest
`drishti-server/target/drishti-server-*-exec.jar`; it tells you to build when there is none), a node's net folder, and the two synthetic
lakes (`tools/bench/trades.py`: 1,500 trades a day per system for two business days, 300 counterparties). The servers run the copy, so a
rebuild never replaces the jar under a running server: the next `start.sh` copies a newer build, and a restarted node runs it.

## Secrets

The repository is public, so no secret is ever in it. `secrets.env` holds three:

| Name | Used by | What it signs |
|---|---|---|
| `DRISHTI_TOKEN_SECRET` | every server and the console | the tokens the console mints for its users |
| `DRISHTI_SESSION_SECRET` | every server and the console | the console's session cookie |
| `DRISHTI_NET_SECRET` | every server | every call between the nodes (the cluster key; `node.yaml` names it, `secret: ${DRISHTI_NET_SECRET}`) |

`start.sh` makes any that is missing with `openssl rand -hex 32` and keeps it, so sessions and tokens survive restarts. They reach the
processes through their environment only; no command prints them. To use your own, copy `secrets.env.example` to `secrets.env` and fill
it. Changing one signs everyone out (token, session) or splits the cluster until every node has the new key (net secret: rotate it as
[OPERATIONS.md › Drishti*net: running several servers as one, section 6](../docs/admin/OPERATIONS.md#6-security-the-key-mutual-tls-nodes-per-role), *Rotating the key with no downtime*, describes). `.gitignore` keeps `secrets.env`, any `net-key` file and `run/` out, and
`tools/test_no_hidden_names.py` fails if one of them, or any jar, is tracked.

## Restart one node

```bash
drishti-stack/stop.sh drishti-b
drishti-stack/start.sh drishti-b
```

The others see it `suspect` after 3 s and `dead` after 10 s (`gossip.yaml`), and `alive` again a second after it is back. A request for its
trades meanwhile answers "the node that holds it is down", never "not found". `console` is a name too: `stop.sh console`, `start.sh console`.

## Add a node

Add one entry under `nodes:` in `stack.yaml`, for example:

```yaml
  - name: drishti-d
    port: 18793
    heap: 1536m
    lake: shared            # a lake of stack.yaml's lakes (built when missing), or a new one you add there
    packs: trading
    off: [trade-murex]      # the connectors this node switches off
    args: [--drishti.security.registered-users-only=false]
```

Then `drishti-stack/start.sh drishti-d`. It makes `nodes/drishti-d/net/` (with the seed `drishti-main` as its peer) and its data folder,
starts it, and the cluster learns it within seconds. Commit the new net folder with the entry.

## Logs

`run/logs/<node>.log` and `run/logs/console.log`, appended on every start. Since 2.9.7 each server writes `run/logs/<node>.log` itself,
rotated at 100 MB a file and kept within 1 GB (`drishti.logging.*`, compressed `<node>.log.<date>.<n>.gz`); the JVM's own standard output
(a crash, a warning before logging starts) goes to `run/logs/<node>.out`. The oldest heap dumps beyond two go at each start
(`drishti.disk.max-dumps`). A node that does not answer within five minutes is reported by
`start.sh` with its log's path. Each JVM writes its temporary files under its own data folder (`-Djava.io.tmpdir=<data>/tmp/server`), never
the system `/tmp`.

## Stopping

`stop.sh` stops the processes in `run/pids.txt`, the console first, by process id: it checks that the id is still one of this stack's
processes (its command line names `run/server.jar` or the console) before it sends `SIGTERM`, and sends `SIGKILL` only after 60 s. Other
Java or Python processes on the machine are never touched.

## Content store

Every node of this stack uses one content store, `data/fstore` in the repository (`DRISHTI_STORE_ROOT`), so a design or other user content saved through one node opens through any other ([DATA_ARCHITECTURE.md](../docs/architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches)).
