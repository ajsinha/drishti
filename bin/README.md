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
# bin: start and stop Drishti

The one place to start and stop the Drishti server (alone, or as the nodes of a cluster) and the console. Each script comes as a `.sh`
(Linux and macOS, bash 3.2 and newer), a `.ps1` (Windows PowerShell 5.1 and PowerShell 7) and a `.cmd` that runs the `.ps1` from `cmd` or
a double-click. The options are the same everywhere; on Windows `--env-file` may also be written `-EnvFile`. Every script takes `--help`.

| Script | What it does |
|---|---|
| `start-server` | Java 21+, the newest server jar, temp folder, memory settings and logs under the data folder; waits for health; prints the LAN address. `--node` starts a cluster node |
| `start-console` | the console's Python (makes `drishti-console/.venv` on first use), pointed at `--server` (one URL, or a cluster's entry nodes); waits for `/healthz` |
| `stop-server`, `stop-console` | stop by the pid file only (never by name): graceful first, forced after a timeout; `--node`, `--all-nodes` |
| `status` | pid, state, port, health answer, distribution and LAN address of every process started from here; with `--cluster FILE` also the members each node sees and the console's servers |
| `start-all`, `stop-all` | one server and one console on this machine; `--distribution risk\|finance\|genomics\|all` (or a file) for both |
| `start-cluster`, `stop-cluster` | a cluster on one machine from a cluster file (`--cluster FILE`, default `drishti-stack/stack.yaml`): the nodes, then the console (or each console of a `consoles:` list, each with its `distribution:`); or only those named |
| `drishti-node` | one node's drain before a stop and its readiness (`drain`, `undrain`, `status`, `wait-ready`, `run`): the stop hook of the service unit and the images |

```bash
bin/start-all.sh --demo                                   # the demo, with development secrets (not for production)
bin/start-all.sh --demo --distribution finance            # Drishti Finance (risk, genomics, all: docs/architecture/DISTRIBUTIONS.md)
bin/start-console.sh --server http://192.168.1.20:18480 --port 17481 --distribution finance --name finance --background   # a second console
bin/start-server.sh --env-file /etc/drishti/drishti.env --background
bin/start-console.sh --server http://192.168.1.20:18480 --env-file /etc/drishti/drishti.env --background
bin/start-server.sh --node drishti-b --seed 192.168.1.20:18480 --env-file cluster.env --background
bin/status.sh
bin/stop-server.sh --all-nodes
bin/start-cluster.sh --cluster drishti-stack/stack.yaml   # three nodes and a console from a file
bin/status.sh --cluster drishti-stack/stack.yaml          # adds the members and the console's servers
bin/stop-cluster.sh --cluster drishti-stack/stack.yaml
```

```powershell
bin\start-all.cmd --demo
bin\start-all.cmd --demo --distribution risk
bin\start-server.cmd --node drishti-b --seed 192.168.1.20:18480 --env-file C:\drishti-secrets\cluster.env --background
bin\status.cmd
```

Files go under the data folder (`--data-dir`, `DRISHTI_DATA_DIR`, else `data/`): `run/server[-<node>].pid`, `run/console[-<name>].pid`,
`logs/server[-<node>].log`, `logs/console[-<name>].log`, `tmp/server[-<node>]`. Exit codes: 0 ok, 1 failed, 2 a wrong option, 3 already running or
the port is in use. `lib/` holds what the scripts share. The server reads two more folders under the data folder: `lib/` (jars every
connector and module may use, read at start: the one place for a JDBC driver or a connector plugin; the older
`external_lib/java` and `drishti.sources.plugin-dir` are moved into it once, at the first start that finds them) and `modules/` (`.dmod` packages, auto-deployed on a single node):
[ADMIN_GUIDE.md section 22.1](../docs/guides/ADMIN_GUIDE.md#221-module-packages-auto-deploy-and-the-shared-lib-folder). Every option, the environment they read, a cluster of three on one machine and
on three machines, and the errors: [DEPLOYMENT_AND_QA_GUIDE.md › section 6](../docs/DEPLOYMENT_AND_QA_GUIDE.md#6-start-the-server-and-the-console).
