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
# deploy/stack: a ready-made local Drishti*net cluster

Three Drishti servers on one machine acting as one cluster ([CLUSTER_GUIDE.md](../../docs/guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)), and a console that is given the first
server alone and learns the other two from the cluster. One command starts it, one stops it, and one shows how it is doing.

Run these from the Drishti folder (the repository root, or the install folder of a release).

```bash
python3 tools/drishti.py start cluster       # (Windows: py -3.13 tools\drishti.py ...) start what is not running: the three servers, then the console
python3 tools/drishti.py status --cluster deploy/stack/stack.yaml      # the processes and ports, the members, the console's servers
python3 tools/drishti.py stop cluster        # stop everything start cluster started (by process id, never by name)
```

Windows: `py -3.13 tools\drishti.py --settings C:\Users\you\drishti-envs\dev.ini start cluster`, `py -3.13 tools\drishti.py status --cluster deploy\stack\stack.yaml`,
`py -3.13 tools\drishti.py stop cluster` (Java and Python only). The JDK is the one lookup of every tool: `drishti.java.home` of the settings
file, then `DRISHTI_JAVA_HOME`, `JAVA_HOME`, the `PATH`; the cluster file names none.

**The settings of every node and the console**, in order, a later file winning: the files of `drishti.py --settings` (your `dev.ini`),
the cluster file's `profile:` (`demo`: `config/profiles/demo.ini`, the same file the IDE's **Drishti: run server (demo)** and
`start server --demo` read), its `settings-file:` (`stack.ini` beside it: what this lab cluster runs differently, live news) and a node's
own `settings:` (`member.ini`: the members run no data inbox). A node's `packs:` beats the profile's packs (the members serve their own);
`drishti-main` names none and serves the profile's. Each node still gets its own data folder as `-Ddrishti.data.dir`, which beats the files'
([docs/DEPLOYMENT_AND_QA_GUIDE.md › The settings file](../../docs/DEPLOYMENT_AND_QA_GUIDE.md#51-the-settings-file)).

**The data folder** of every cluster command is the one launch-roots lookup: `--data-dir`, else `drishti.data.dir` of the settings file,
else `DRISHTI_DATA_DIR`, else `<drishti>/data`.

This folder is configuration only (`stack.yaml`, `stack.ini`, `member.ini`, `nodes/`, `secrets.env`); the commands are in [bin/](../../bin/README.md). `--cluster FILE` runs any other file of this format; the default is this one. Each node is started by `drishti.py start server`, the console by `drishti.py start console`, with the settings of `stack.yaml`.

Open the console at `http://<this machine's address>:17480` (`start cluster` prints it) and go to **Admin → Cluster**: the three members are alive.

## What runs where

| Process | Address | What it serves | Its data |
|---|---|---|---|
| `drishti-main` | `http://<this machine's address>:18480` (`start cluster` prints it) | your own stack: the demo profile's packs (every module's that is on, plus us-equities, gleif and funds), the demo lake, live news, the data inbox | the repository's `data/` (as from the IDE); its connectors are the modules' templates, with any override of yours in `config/connectors/` |
| `drishti-b` | `http://<this machine's address>:18791` (`start cluster` prints it) | synthetic ENDUR and CALYPSO trades (ids `10000000`+, `CLY-30000000`+), pack `trading` | `data/stack/nodes/drishti-b/`, lake `data/stack/lakes/shared/` |
| `drishti-c` | `http://<this machine's address>:18792` (`start cluster` prints it) | synthetic MUREX trades (`MX-50000000`+), packs `trading`, `counterparty-risk`, `market-risk` | `data/stack/nodes/drishti-c/`, lake `data/stack/lakes/murex/` |
| console | `http://<this machine's address>:17480` (`start cluster` prints it) | the console, given `drishti-main` alone; it learns `drishti-b` and `drishti-c` and fails over to them | the repository's `data/console/` |

**The nodes advertise this machine's real address, never 127.0.0.1** (members never advertise localhost, a loopback or a wildcard address:
[CLUSTER_GUIDE.md](../../docs/guides/CLUSTER_GUIDE.md#2-a-cluster-of-three)). `start cluster` finds it (the default route's interface, never a docker bridge) and gives
it to every node as `DRISHTI_STACK_HOST`, which the checked-in `node.yaml` and seed files name (`advertise-url: http://${DRISHTI_STACK_HOST}:18480`),
so the files stay the same on every machine; the console is given `drishti-main` at that address too, so its discovered list matches what the
cluster advertises. On a machine with several interfaces, set `host:` in `stack.yaml`. `drishti.py status` shows each member's advertised address.
The local addresses in the table above still open the pages from this machine.

Open `/v/trade/10000001` on the console: the trade is held by `drishti-b` only, and `drishti-main` forwards the read to it
(**How this view was built** names the node that answered, for an administrator).

## The files

| File | Checked in | What it is |
|---|---|---|
| `stack.yaml` | yes | the nodes (port, heap, packs, lake, connectors switched off), the lakes, the profile and the console. `start cluster` reads it |
| `stack.ini`, `member.ini` | yes | the cluster's own settings after the profile (live news), and a member node's (no data inbox) |
| `nodes/<node>/net/` | yes | each node's Drishti*net folder: `node.yaml` (its id, its address as `http://${DRISHTI_STACK_HOST}:<port>` and `secret: ${DRISHTI_NET_SECRET}`, references, never the key or this machine's IP), `gossip.yaml`, `peers/` (the seed) |
| `secrets.env.example` | yes | the names of the secrets, no values |
| `secrets.env` | **no** (git-ignored) | the secrets themselves, mode 600 |
| `../../data/run/`, `../../data/logs/` | **no** (`data/` is git-ignored) | the process ids (`server-<node>.pid`, `console.pid`) and the logs (`server-<node>.log`, `console.log`), where `drishti.py` keeps them |
| `../../data/stack/` | **no** (`data/` is git-ignored) | `server.jar` (the copy the servers run), the nodes' data and connector folders, the synthetic lakes |

On the first start `start cluster` makes what is missing: the secrets, `data/stack/server.jar` (a copy of THE
`lib/drishti-server-*-exec.jar`; with no jar in `lib/` the start stops and tells you to run `python3 tools/drishti.py build jar`, whatever
copy is left), `data/stack/modules/` (the module packages: a copy of THE `modules/*.dmod` and `first-party.yaml`, see below), a node's net folder, and the two synthetic
lakes (`tools/bench/trades.py`, as `lakes:` in `stack.yaml` says: 1,500 trades a day per system for sixty business days, 300
counterparties). The servers run the copy, so a
rebuild never replaces the jar under a running server: every `start cluster` compares the copy with `lib/`'s jar by name, size and
SHA-256 (`data/stack/server-jar.source`), never by time, and copies it when they differ; a restarted node runs it.

The modules are pinned the same way. A module is a package (`modules/<id>-<version>.dmod`, made by `python3 tools/drishti.py build modules`; the server jar holds none), and every node is given `data/stack/modules/` as its modules folder (`DRISHTI_MODULES_HOME`), a copy of the `.dmod` files and `first-party.yaml` of `modules/`, refreshed at every `start cluster` when they differ by name, size and SHA-256 (`data/stack/modules.source`). A rebuild of the modules never changes the files under a running node, and a node restarted alone gets the same jar and the same packages, so the kernel build stamped into a package always matches the jar it runs. In a checkout `start cluster` refuses packages that are older than their module folders or stamped for another jar, naming the module and the command (`python3 tools/drishti.py build all`). Upgrading from an earlier release: stop the stack, `git pull`, `python3 tools/drishti.py build all`, start the stack.

The synthetic lakes are laid out as the demo lake's file says (`config/lakes/demo.yaml`, [DATA_LAKE_GUIDE.md](../../docs/guides/DATA_LAKE_GUIDE.md)):
`trades.py` writes through it (`lake build --lake demo --lake-root data/stack/lakes/<name>`), and a node with `lake:` reads the same file
(`LAKE_LAKES_DIR`) with its root, `${DRISHTI_DELTA_ROOT}`, set to its synthetic lake. One description of the tables for every node; only
the root differs.

A synthetic lake **ends on the cluster's business today**: the lake entry's `end:`, else `DRISHTI_BUSINESS_TODAY` (the environment's,
else the newest business date of the demo lake `data/delta/demo`, which `start cluster` finds and gives every server), else this machine's
date, rolled back to a business day. A lake that ends on another day (built before the cluster's today was fixed, or on an
earlier day) is built again beside the old one (`data/stack/lakes/<name>-next`) and swapped in on the next `start cluster` of a node
that reads it; the node must be stopped. Otherwise the node holds no trade of the day its servers ask for, and the cluster's grouped pages
(a counterparty's trades) miss its trades.

A node with `lake:` **switches off every connector whose tables its lake does not hold**: of the connectors it may run (the modules'
templates, its packs' connectors and its own connector files) each that reads the demo lake or an official results lake (a lake rooted at
`${DRISHTI_DELTA_ROOT}`) with none of its tables in the synthetic lake, with the node's `off:` list (each becomes an `enabled: false` override file in the node's own connectors folder; a `home: repo` node takes no `off:`: put `enabled: false` in `config/connectors/` instead). Those kinds are served by the nodes
that hold them ([CLUSTER_GUIDE.md](../../docs/guides/CLUSTER_GUIDE.md), "Is the pack here but not the data?"), never reported DOWN, and a
new module's connector needs no new line here. `start cluster` names them.

## Secrets

The repository is public, so no secret is ever in it. `secrets.env` holds three:

| Name | Used by | What it signs |
|---|---|---|
| `DRISHTI_TOKEN_SECRET` | every server and the console | the tokens the console mints for its users |
| `DRISHTI_SESSION_SECRET` | every server and the console | the console's session cookie |
| `DRISHTI_NET_SECRET` | every server | every call between the nodes (the cluster key; `node.yaml` names it, `secret: ${DRISHTI_NET_SECRET}`) |

`start cluster` makes any that is missing (32 random bytes from Python's `secrets` module, the same on Windows) and keeps it, so sessions and tokens survive restarts. They reach the
processes through their environment only; no command prints them. To use your own, copy `secrets.env.example` to `secrets.env` and fill
it. Changing one signs everyone out (token, session) or splits the cluster until every node has the new key (net secret: rotate it as
[CLUSTER_GUIDE.md › Security](../../docs/guides/CLUSTER_GUIDE.md#16-security-the-key-mutual-tls-nodes-per-role), *Rotating the key with no downtime*, describes). `.gitignore` keeps `secrets.env`, any `net-key` file and `run/` out, and
`tools/test_no_hidden_names.py` fails if one of them, or any jar, is tracked.

## Restart one node

```bash
python3 tools/drishti.py stop cluster drishti-b
python3 tools/drishti.py start cluster drishti-b
```

The others see it `suspect` after 3 s and `dead` after 10 s (`gossip.yaml`), and `alive` again a second after it is back. A request for its
trades meanwhile answers "the node that holds it is down", never "not found". `console` is a name too: `drishti.py stop cluster console`, `drishti.py start cluster console`.

## Upgrade

The three nodes first, one at a time (`python3 tools/drishti.py stop cluster drishti-main`, update, `python3 tools/drishti.py start cluster drishti-main`, then
`drishti-b`, then `drishti-c`), the console last. A console asks its servers for what it draws (packs, menus, the version shown); in front of an older
server it shows no packs and says on every page and on Admin → Health that the server is older than the console
([OPERATIONS_GUIDE.md section 18](../../docs/guides/OPERATIONS_GUIDE.md#18-upgrades-and-rollback)).

## Add a node

Add one entry under `nodes:` in `stack.yaml`, for example:

```yaml
  - name: drishti-d
    port: 18793
    heap: 1536m
    lake: shared            # a lake of stack.yaml's lakes (built when missing), or a new one you add there
    packs: trading
    off: [trade-murex]      # connectors this node switches off (a file with enabled: false in its connectors folder) besides those whose tables its lake does not hold
    args: [--drishti.security.registered-users-only=false]
```

Then `python3 tools/drishti.py start cluster drishti-d`. It makes `nodes/drishti-d/net/` (with the seed `drishti-main` as its peer) and its data folder,
starts it, and the cluster learns it within seconds. Commit the new net folder with the entry.

## Logs

`data/logs/server-<node>.log` and `data/logs/console.log` in the repository (`drishti.py start` keeps them there for every node, whatever its own data
folder). Each server writes its log itself, rotated at 100 MB a file and kept within 1 GB (`drishti.logging.*`, compressed
`<file>.<date>.<n>.gz`); the JVM's own standard output (a crash, a warning before logging starts) goes to `data/logs/server-<node>.out`; the
GC log to `data/logs/gc-server-<node>-<pid>.log` and a heap dump on OutOfMemoryError to `data/dumps/<node>`. The oldest heap dumps beyond
two go at each start (`drishti.disk.max-dumps`). A node that does not answer within five minutes is reported with its log's path. Each JVM
writes its temporary files under `data/tmp/server-<node>`, never the system `/tmp`.

## Stopping

`drishti.py stop cluster` stops the console first, then each node (`stop console`, `stop server --node <name>`): by the process id
in `data/run/<process>.pid`, after checking that the id is still a Drishti server or console; `SIGTERM` first, `SIGKILL` only after 60 s.
Other Java or Python processes on the machine are never touched.
## Content store

Every node of this stack uses one content store, `data/fstore` in the repository (`DRISHTI_STORE_ROOT`), so a design or other user content saved through one node opens through any other ([DATA_ARCHITECTURE.md](../../docs/architecture/DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches)).
