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
# bin: where the commands are

Starting, stopping and checking Drishti is one Python command, `tools/drishti.py` (on Windows `py -3.13 tools\drishti.py ...`), the same on
Linux, macOS and Windows; no shell script. `bin/` holds only this README. A server drains itself when it is stopped (the service unit, the
images, a terminal's Ctrl+C: `drishti.node.drain.on-stop`); `drishti.py node drain|undrain|status|wait-ready` drains one ahead of a stop.

## The command

| Command | What it does |
|---|---|
| `setup [--check]` | checks the Python you run Drishti with (its libraries), writes the settings file and makes the data folder; `--check` only reports |
| `build jar`, `build modules`, `build all` | the server's executable jar into `<DRISHTI_HOME>/lib`; the module packages (`.dmod`) and `first-party.yaml` into `<DRISHTI_HOME>/modules` (the jar holds no module); both in one Maven run |
| `start server\|console\|all\|cluster` | Java 21+, the one server jar in `<DRISHTI_HOME>/lib` (more than one is refused), temp folder, memory settings and logs under the data folder; waits for health; prints the LAN address. `--node` starts a cluster node; `all` is one server and one console on this machine; `cluster --cluster FILE` starts a cluster from a file (default `deploy/stack/stack.yaml`) |
| `stop server\|console\|all\|cluster` | stops by the pid file only (never by name): graceful first, forced after `--timeout`; `--node`, `--all-nodes` |
| `status` | pid, state, port, health answer, distribution and LAN address of every process started from here, and of every server or console of this install started elsewhere (an IDE); with `--cluster FILE` also the members each node sees and the console's servers |
| `node drain\|undrain\|status\|wait-ready` | one server's drain before a stop and its readiness (`--server`, `DRISHTI_NODE_URL`; the token `DRISHTI_DRAIN_TOKEN`) |
| `launchers [--check\|--machine]` | the IDE launchers from `tools/launch/launchers.yaml`; this machine's JVM options files for them |

Run them from the Drishti folder (the repository root, or the install folder of a release). Every command takes `--help`.

```bash
python tools/drishti.py setup
python tools/drishti.py build all
python tools/drishti.py start all --demo                  # the demo, with development secrets (not for production)
python tools/drishti.py start all --demo --distribution finance   # risk, genomics, finance, all: docs/architecture/DISTRIBUTIONS.md
python tools/drishti.py start server --env-file /etc/drishti/drishti.env --background
python tools/drishti.py start console --server http://192.168.1.20:18480 --port 17481 --distribution finance --name finance --background
python tools/drishti.py start server --node drishti-b --seed 192.168.1.20:18480 --env-file cluster.env --background
python tools/drishti.py status
python tools/drishti.py stop server --all-nodes
python tools/drishti.py start cluster --cluster deploy/stack/stack.yaml   # three nodes and a console from a file
python tools/drishti.py status --cluster deploy/stack/stack.yaml          # adds the members and the console's servers
python tools/drishti.py stop cluster --cluster deploy/stack/stack.yaml
```

```powershell
py -3.13 tools\drishti.py start all --demo
py -3.13 tools\drishti.py start all --demo --distribution risk
py -3.13 tools\drishti.py start server --node drishti-b --seed 192.168.1.20:18480 --env-file C:\drishti-secrets\cluster.env --background
py -3.13 tools\drishti.py status
```

A settings file goes before the command: `python tools/drishti.py --settings FILE start server`.

Files go under the data folder (`--data-dir`, `DRISHTI_DATA_DIR`, else `data/`): `run/server[-<node>].pid`, `run/console[-<name>].pid`,
`logs/server[-<node>].log`, `logs/console[-<name>].log`, `tmp/server[-<node>]`. Exit codes: 0 ok, 1 failed, 2 a wrong option, 3 already running or
the port is in use.

All jars live under the home. Drishti's own jar (the server's executable jar, exactly one) is in `<DRISHTI_HOME>/lib`. Your jars live in
`<DRISHTI_HOME>/lib/ext` (`drishti.lib.path`, `DRISHTI_LIB_PATH`): the jars every connector and module may use, read at start, the one place for a JDBC
driver or a connector plugin; an upgrade keeps them. The data folder holds `run`, `logs`, `tmp`, `dumps`, `delta` and `modules/` (the `.dmod` cache and leftovers; the packages themselves sit in `<home>/modules`):
[ADMIN_GUIDE.md section 22.1](../docs/guides/ADMIN_GUIDE.md#221-module-packages-and-the-shared-lib-folder). Every option, the environment they read, a cluster of three on one machine and
on three machines, and the errors: [DEPLOYMENT_AND_QA_GUIDE.md section 7](../docs/DEPLOYMENT_AND_QA_GUIDE.md#7-start-the-server-and-the-console).
