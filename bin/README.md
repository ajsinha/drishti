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
| `status` | pid, state, port, health answer and LAN address of every process started from here |
| `start-all`, `stop-all` | one server and one console on this machine |

```bash
bin/start-all.sh --demo                                   # the demo, with development secrets (not for production)
bin/start-server.sh --env-file /etc/drishti/drishti.env --background
bin/start-console.sh --server http://192.168.1.20:18480 --env-file /etc/drishti/drishti.env --background
bin/start-server.sh --node drishti-b --seed 192.168.1.20:18480 --env-file cluster.env --background
bin/status.sh
bin/stop-server.sh --all-nodes
```

```powershell
bin\start-all.cmd --demo
bin\start-server.cmd --node drishti-b --seed 192.168.1.20:18480 --env-file C:\drishti-secrets\cluster.env --background
bin\status.cmd
```

Files go under the data folder (`--data-dir`, `DRISHTI_DATA_DIR`, else `data/`): `run/server[-<node>].pid`, `run/console.pid`,
`logs/server[-<node>].log`, `logs/console.log`, `tmp/server[-<node>]`. Exit codes: 0 ok, 1 failed, 2 a wrong option, 3 already running or
the port is in use. `lib/` holds what the scripts share. Every option, the environment they read, a cluster of three on one machine and
on three machines, and the errors: [DEPLOYMENT_AND_QA_GUIDE.md › section 6](../docs/DEPLOYMENT_AND_QA_GUIDE.md#6-start-the-server-and-the-console).
