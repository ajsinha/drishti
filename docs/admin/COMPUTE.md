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
# Compute workers: the administrator's guide

Compute workers run Python computations for connectors outside the server's JVM: a pool of worker processes per kind of work, data
handed to them as Arrow files already read and masked as the person who asked, from a locked environment of libraries an administrator
approved. The design, with the reasons behind each choice, is [PYTHON_COMPUTE.md](../architecture/PYTHON_COMPUTE.md); the module is
`drishti-compute` ([its README](../../drishti-compute/README.md)).

**It is off as shipped.** With `compute.enabled: false` (the default in `config/compute/compute.yaml`) no worker starts and every compute
request is refused with `DRS-9201`. Approvals work either way, so requirements can be reviewed before compute is switched on.

**Built in 2.8.0 (unreleased): steps C1 to C3.** Pools, workers, transport, limits, fan-out, environments and approval. The pricer
interface, QLRisk and the QuantLib Java worker (steps C4 to C6) and the reactive connectors that send work to the pools (R1 to R6) are
still planned.

- [1. Switch it on](#1-switch-it-on)
- [2. Pools and how many workers](#2-pools-and-how-many-workers)
- [3. Fair scheduling and back-pressure](#3-fair-scheduling-and-back-pressure)
- [4. Fan-out: one request over many workers and nodes](#4-fan-out-one-request-over-many-workers-and-nodes)
- [5. Limits and the sandbox](#5-limits-and-the-sandbox)
- [6. Requirements, environments and approval](#6-requirements-environments-and-approval)
- [7. Admin → Compute](#7-admin--compute)
- [8. Windows](#8-windows)
- [9. Measured: the load test](#9-measured-the-load-test)
- [10. When something is wrong](#10-when-something-is-wrong)

## 1. Switch it on

```yaml
# config/compute/compute.yaml
compute:
  enabled: true                 # or DRISHTI_COMPUTE_ENABLED=true
  python: /opt/python3.12/bin/python3   # or DRISHTI_PYTHON; empty: python3 (python on Windows)
```

Restart the server. Its log says `compute: on, node <name>, 1 pool(s): sample 0..4 fork`. Open **Admin → Compute** and press **Test** on
the `sample` pool: the sample engine sums four numbers on a worker and the page says which worker answered and how long it took.

Everything compute writes goes under `<data dir>/compute/`: `sdk/` (the worker SDK, extracted from the server's jar), `envs/` (locked
environments), `jars/` (staged Java libraries), `shipped/` (wheels and jars shipped with Drishti or a pack), `scratch/` (one folder per
request, removed when the request ends) and `work/` (pip's and the workers' temporary files). Nothing is written to the machine's
temporary folder.

## 2. Pools and how many workers

A pool is a set of worker processes that load the same engines in the same environment:

```yaml
compute:
  pools:
    risk:
      engines: [drishti_var.connector:VarEngine]
      environment: { python: "3.12", lock: locks/risk.lock, index: bank-pypi }   # or: system
      workers: { min: 4, max: 32 }
      memory: 4GB
      cpu-seconds: 300
      timeout: 30s
      timeout-batch: 10m
      recycle-after: 200
      batch-share: 0.25
  nodes:                         # a node's own count, by its Drishti*net id
    drishti-big-1:
      pools:
        risk: { workers: { max: 64 } }
```

| Key | Default | Meaning |
|---|---|---|
| `workers.min` | `0` | workers kept warm (started at once and replaced when one stops) |
| `workers.max` | `2` | workers at most **on this node**; there is no limit in the code: the machine's cores and memory are the limit |
| `nodes.<node>.pools.<pool>.workers` | — | this node's own `min`/`max` (a big machine runs more); a placeholder such as `${RISK_WORKERS:8}` works too |
| `mode` | `auto` | `fork` (Linux, macOS: the engines are imported once and each request runs in a fresh forked child) or `warm` (Windows: the request runs in the worker, which is reset afterwards and recycled) |
| `memory`, `cpu-seconds` | none | per worker and per request ([section 5](#5-limits-and-the-sandbox)) |
| `timeout`, `timeout-batch` | `30s`, `10m` | a request's wall-time budget by class; waiting for a worker counts |
| `recycle-after` | `200` (`50` warm) | requests before a worker is replaced |
| `batch-share` | `0.25` | the share of the workers batch requests may hold at once |
| `idle-stop` | `5m` | idle workers beyond `min` stop after this long |
| `start-timeout` | `60s` | how long a new worker may take to load its engines |
| `python-path` | — | extra folders on the workers' module path (an administrator's setting) |

**One request per worker at a time.** Concurrency is the number of workers. A worker never holds two people's data at once, and a result
goes only to the request id that asked for it.

**More workers.** Raise `workers.max` (per pool, and per node under `nodes:`); add Drishti\*net nodes that serve the same pool to scale
out ([section 4](#4-fan-out-one-request-over-many-workers-and-nodes)). Each Python worker costs its interpreter (about 15 MB) plus what
its engines import (numpy and polars: 60 to 120 MB); in fork mode the children share the parent's pages until they write to them.

## 3. Fair scheduling and back-pressure

Each pool keeps one queue per class, served in this order:

| Class | Who | Queue (`compute.scheduling.queue`) | When full |
|---|---|---|---|
| `interactive` | a person waiting on a panel or an API call | `256` | refused at once: `DRS-9203` busy, try again |
| `event` | triggers | `10000` | refused (the reactive connector coalesces by key before it gets here) |
| `batch` | schedules, fan-outs, precomputation | `100000`, and at most `batch-share` of the workers at once | refused; fan-out waits and retries ([section 4](#4-fan-out-one-request-over-many-workers-and-nodes)) |

Within a class, people take turns: the queue goes round the users with waiting work, and no user has more than
`compute.scheduling.max-inflight-per-user` (default 4) requests running on a pool at once. One person's thousand requests wait behind
another person's one.

## 4. Fan-out: one request over many workers and nodes

A request may be split into chunks (a batch of trades to price over 509 scenario days): one input is cut into chunks of
`compute.fan-out.chunk-rows` rows (default 5000), the other inputs go whole with every chunk, every chunk is an ordinary request (its own
id `<parent>-c<i>`, the parent's user, class and budget, so fairness and limits apply to each), and the answers are gathered in chunk
order. Chunks go to whichever target has the least outstanding work for its size: this node's workers and, with
`compute.fan-out.peers: true` (the default), every other Drishti\*net member alive that serves the pool, each with its own worker count.
Chunks sent to another node travel signed, on behalf of the person who asked, one hop (the other node runs them on its own workers and
never forwards them again). A node that cannot be reached is skipped and its chunk runs here.

At most twice the total worker count of chunks is outstanding at once, so a large fan-out never floods the queues.

## 5. Limits and the sandbox

| Limit | Fork mode (Linux, macOS) | Warm mode (Windows) |
|---|---|---|
| wall time | the supervisor kills the request's child at its budget; the worker stays | the supervisor kills the worker and starts another |
| CPU time | `RLIMIT_CPU` on the child: `failed: the computation passed its CPU-time limit` | not enforced in 2.8.0 (the wall time is) |
| memory | `RLIMIT_AS` on the child: `MemoryError` becomes a limit failure | the worker watches its own working set every 250 ms and stops above the limit |
| network | refused by an audit hook (sockets, address lookups) | the same |
| processes | refused (`subprocess`, `os.system`, `os.exec*`, `os.fork`) | the same |
| files | writes, renames and removals outside the request's scratch folder are refused | the same |

Workers start with an empty environment: no tokens, no credentials, only `PATH`, the SDK on `PYTHONPATH`, one thread per numeric library
(`OMP_NUM_THREADS=1` and the like: the pool decides parallelism) and temporary folders under `<data dir>/compute/work/`. An engine's
`print` goes to the server's log under the worker's name, never into the protocol. An engine's own refusal (`ComputeError("the scope is
empty")`) reaches the person in those words; any other failure is "the computation failed (request <id>)" and the traceback is in the
server's log only. A worker that crashes is replaced; in fork mode only the request's child dies.

## 6. Requirements, environments and approval

A connector names the libraries it needs in its file:

```yaml
# config/connectors/var-engine.yaml
plugin: reactive                # the reactive plugin arrives with step R1; any connector may carry requires: today
requires:
  python: { version: "3.12", lock: requires/var-engine.lock, index: shipped }
  java:
    - coords: org.quantlib:quantlib-java:1.36.0-drishti.1
      sha256: 4b1f…e09a
      classifier: linux-x86_64
      source: shipped
      native: true
  plugins: [ { name: drishti-plugin-delta } ]
```

- **The lock file** pins every wheel with its version and sha256 (`name==version --hash=sha256:...`, pip's syntax; ranges, unhashed lines
  and option lines are refused). Write the Python version in quotes (`"3.10"` is not `3.1`).
- **Sources:** `shipped` (`<data dir>/compute/shipped/`), a named index of `compute.indexes` (the bank's mirror), or `path:<folder>`.
  Public PyPI is never used unless the site names it as an index.
- **Java libraries** are checked against their sha256 and scanned for native code; a jar with a `.so`, `.dll` or `.dylib` that does not
  say `native: true` is refused. They are staged under `<data dir>/compute/jars/<sha256>/`; nothing loads them until the Java worker
  (step C6).

**Approval.** A connector (or a pool on a locked environment) whose items are not all approved **stays off**: Admin → Connectors shows it
as *waiting for approval* with the request id, and an approval request appears in Admin → Compute. An administrator other than the
person who asked approves or rejects it, with a reason; `compute.approval.roles` can narrow approvers to administrators holding given
roles. The person who asked is `requires.requested-by` when the file names one, else the administrator who last saved the file through
Admin → Connectors, else `file <name>.yaml`; a pool's environment is asked for by `compute.yaml`. Each decision is in the audit log
(`compute-request`, `compute-approve`, `compute-reject`, `compute-revoke`; a refused self-approval is `compute-approve-refused`). An approved
item serves every connector and pool that names it; a new version, hash or source is a new item. **Revoke** an approval and connectors that
need its items stop at once (running computations finish). Requests and approved items are rows of `drishti_compute_request` and
`drishti_compute_approval` in the Drishti database, so every node of a cluster sees the same approvals.

**Environments.** One per lock: its id is the sha256 of (Python version, platform, lock text). **Build** (Admin → Compute, or
`compute.build: on-approval`) runs `python -m venv` and `pip install --require-hashes --no-deps --only-binary=:all:` from the named source
only, writes a manifest of every file's sha256, and makes the folder read-only. A build that fails (a hash mismatch, a wheel missing for
the platform) leaves nothing behind and names the wheel. Every worker start checks the manifest: a changed, added or removed file stops it
with the file named. An environment is never changed in place: a new lock is a new environment.

## 7. Admin → Compute

| Part | Shows |
|---|---|
| Pools | per pool: workers busy, idle and starting here; the other nodes serving it and their workers; queued by class; served, crashed, recycled, failures by kind; run and queue latency (p50, p95); ready or why not; **Test** (the sample engine) and **Build** (a locked environment) |
| Busy now | the requests running: worker, request id, user, class, kind, for how long; **Cancel** |
| Approval requests | id, connector or pool, the items (kind, name, version, source, native code, sha256), who asked and when, the state and decision; **Approve**, **Reject**, **Revoke** (a reason each time; no buttons on your own request) |
| Environments | id, Python and platform, packages, built when and by whom, the last failure, size |
| Recent requests | the last 200 on this node: user, pool and kind, class, outcome, queued and run time, worker |

The same data is `GET /api/v1/admin/compute`; decisions are `POST /api/v1/admin/compute/requests/{id}/approve|reject|revoke` with
`{"reason": "..."}`, a build `POST /api/v1/admin/compute/pools/{pool}/build`, a test `POST .../pools/{pool}/test`, a cancel
`POST /api/v1/admin/compute/running/{id}/cancel` ([API_GUIDE.md](../guides/API_GUIDE.md)).

## 8. Windows

The Windows QA machine takes no `.exe` and no native binary placed by hand. The worker SDK is pure Python (its Arrow reader and writer
included), so pools in warm mode run on the console's Python with no extra package; the sample engine needs nothing else. Environments
build from `win_amd64` wheels. Tests that need fork skip on Windows with the reason; the QuantLib Java tests (step C6) will skip there too.

## 9. Measured: the load test

`ComputeLoadTest` (tag `load`, not in the normal build) starts N workers, sends a burst of small requests from eight users through the
fair queue, runs a closed loop of N clients, and fans out 20,000 trades × 509 doubles:

```bash
./mvnw test -pl drishti-compute -Dcompute.excluded.groups=none -Dgroups=load -Dtest=ComputeLoadTest \
    -Ddrishti.compute.load.workers=32 -Ddrishti.compute.load.requests=8000 -Ddrishti.compute.test.python=<python>
```

Measured on a 24-core Linux workstation (2026-10-24, Python 3.13 with numpy):

| Workers | Mode | Burst throughput | Closed loop: throughput, p50 / p95 / p99 (ms) | Fan-out 20,000 × 509 |
|---|---|---|---|---|
| 16 | fork | 2,607 req/s | 3,004 req/s, 5 / 7 / 9 | 65 chunks on 16 workers, 0.36 s |
| 32 | fork | 2,786 req/s | 3,506 req/s, 8 / 14 / 18 | 129 chunks on 32 workers, 0.62 s |
| 32 | warm | 2,212 req/s | 2,521 req/s, 11 / 21 / 25 | 129 chunks on 32 workers, 0.22 s |
| 64 | fork | 2,726 req/s | 3,214 req/s, 16 / 40 / 54 | 257 chunks on 64 workers, 1.17 s |

Each request is a trivial sum, so these measure the supervisor's own cost per request (a scratch folder, Arrow files, the control
message, a fork): about 3,000 requests a second on this machine whatever the worker count. Real engines spend milliseconds to seconds per
request, and throughput then grows with the workers; past the machine's cores, add nodes.

## 10. When something is wrong

| You see | It means | Do |
|---|---|---|
| `DRS-9201` compute is off | `compute.enabled` is false | switch it on ([section 1](#1-switch-it-on)) |
| a pool *not ready: waiting for approval* | its environment's lock is not approved | approve the request in Admin → Compute (another administrator than the one who asked) |
| *approved but not built* | nothing has built the environment | **Build**, or set `compute.build: on-approval` |
| a build fails *pip refused the lock* | a wheel's hash differs from the lock, or no wheel for this platform | put the right wheel in the source, or regenerate the lock; nothing was left behind |
| *a file of the environment was changed after it was built* | someone wrote into a read-only environment | treat it as tampering; remove the environment folder and build it again |
| `DRS-9203` busy | the pool's queue for that class is full | wait, raise `workers.max` or the queue, or add a node |
| `DRS-9204` the computation passed its time budget | the request ran longer than `timeout` | raise the budget, or split the work (fan-out) |
| `failed: ... sandbox refused` | the engine opened a socket, started a process or wrote outside its scratch folder | the engine must not; data arrives as inputs |
| a connector *waiting for approval* | its `requires:` is not approved | Admin → Compute → Approval requests |
| Admin → Compute shows no other nodes | Drishti\*net is off, or the other nodes do not serve the pool | [NET.md](NET.md); each node needs the pool in its own `compute.yaml` |
