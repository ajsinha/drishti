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
# drishti-compute: compute workers

The supervisor of out-of-process Python workers for computed connectors, with the worker SDK they run, the locked environments they run
in and the approval that gates them. The workers are generic: nothing in them knows about VaR or pricing. Design:
[PYTHON_COMPUTE.md](../docs/architecture/PYTHON_COMPUTE.md); operating it: [COMPUTE.md](../docs/admin/COMPUTE.md). Built in 2.8.0
(steps C1 to C3). Beside them, the compute that stays in the server's JVM next to the data: the in-memory **vector store** and the
**VaR maths** (packages `vector` and `var`, the VaR engine's waves V0 to V3, [VAR_ENGINE.md section 18](../docs/architecture/VAR_ENGINE.md#18-as-built-in-280-waves-v0-to-v3)):
base VaR is additions and sorts over stored vectors, so it needs no worker; what-if pricing will send trades to the workers.

## What is in it

| Package | Does |
|---|---|
| `com.ash.drishti.compute` | `ComputeService` (the supervisor: pools, submit, fan-out, cancel, Admin's overview), `ComputeSettings` and `PoolSettings` (`config/compute/compute.yaml`), `ComputeRequest` (correlation id, pool, engine, kind, params, Arrow inputs, viewer, class, budget, seed), `ComputeResult`, `ComputeHandle`, `ComputeException` (off, refused, busy, timeout, cancelled, crashed, limit, engine, failed, invalid, unavailable), `ProgressSink`; `Pool` (one pool's workers and fair queue) |
| `…compute.worker` | `WorkerProcess` (one process, JSON-lines control, wall-time and cancel from outside), `Scratch` (a request's folder: inputs in `in/`, results in `out/`, removed at the end), `Sdk` (extracts the Python SDK from this jar), `ReadOnly` |
| `…compute.sched` | `FairQueue` (classes in order, users in turn, per-user in-flight limit, batch share, bounded queues), `Latencies` |
| `…compute.cluster` | `FanOut` (split, spread by load over this node and peers, gather in order), `PeerTransport` (the server implements it over Drishti*net) |
| `…compute.arrow` | `ArrowTable` (int64, float64, bool, utf8, fixed-size vectors of float64), `ArrowIpc` (the IPC stream format from `arrow-format` alone) |
| `…compute.env` | `Requires` (`requires:` of a connector), `LockFile` (pins and hashes), `Environments` (venv + `pip --require-hashes`, offline, manifest, read-only), `Manifest`, `JavaLibraries` (sha256, native-code scan, staging) |
| `…compute.vector` | `VectorStore` (vectors of one width by ordinal, row-major `double[]` chunks under a memory budget, range and row sums, a permutation into hierarchy order), `OrdinalSet` (exact union, difference and intersection of row ordinals), `ParallelSums` (sums on a bounded pool, slices added in a fixed order) |
| `…compute.var` | `VarBook` (one business date: trade vectors in hierarchy order, positions and books as runs, columnar attributes, node aggregates, a planner), `ScopeExpr` and `ScopeResolver` (the scope grammar to ordinals), `PositionRule` (rules over positions' columns), `VarMath` and `VarCalc` (VaR, ES, components, incremental and marginal VaR, histograms), `Backtest` (exceptions, Basel light, Kupiec, Christoffersen), `Columns`; `VarScaleBenchmark` in the tests (opt-in) |
| `…compute.approval` | `Approvals` (request, approve, reject, revoke; four eyes; audit), `ApprovalStore` (memory, or the Drishti database's `drishti_compute_request` and `drishti_compute_approval`) |
| `…compute.reactive` | the built-in connector plugin `reactive` (R1 to R4, [REACTIVE_CONNECTOR.md](../docs/connectors/REACTIVE_CONNECTOR.md)): `ReactiveSourcePlugin`, `ReactiveConnector` (authorise, parameters, pin, key with the view signature, cache, single flight, admission, inputs read as the viewer, engine, validate, stamp, audit), `ReactiveSpec` and `ParamSpec` (the connector file's computation), `PythonRunner` (a pool, with fan-out and `Reduce`) and `JavaRunner` (a `ComputeEngine` in the server), `Triggers`, `Coalescer`, `SeenEvents`, `Watermark`, `ResultCache`, `Sandboxes`, `RequestEvents` (a request's events to its owner only), `ReactiveHub` and `ReactiveHost` (what the server provides); `samples.VectorSumEngine` (the Java twin of the sample `sum-vectors`) |
| `src/main/python/drishti_compute` | the worker SDK, pure Python (standard library only): `engine` (the contract: `Engine`, `Request`, `Inputs`, `Progress`, `Result`, `ComputeError`, `register_cache`), `arrow` (the Arrow stream format in pure Python, zero-copy reads), `guard` (the audit-hook sandbox and limits), `worker` (the loop the supervisor starts), `samples.vectors` (echo, sum, sum of vectors), `samples.paths` (`PathSumEngine`: the sample reactive connector's sum of P&L paths, the shape of its Java twin) |

## The worker protocol

The supervisor starts `python -s -B -m drishti_compute.worker --engines <module:Class,...> --mode fork|warm --memory <bytes>
--cpu-seconds <n> --worker <id>` with an empty environment (no credentials) and the SDK on `PYTHONPATH`. One JSON object per line:

| Direction | Message |
|---|---|
| worker → server | `ready` (pid, mode, Python, SDK version, each engine's kinds), once |
| server → worker | `run` (`id`, `engine`, `kind`, `params`, `user`, `roles`, `viewSignature`, `cls`, `seed`, `scratch`, `inputs`: name → `in/<name>.arrows`, `chunk`, `cpuSeconds`) |
| worker → server | `started` (the pid running it: the fork child, which the supervisor kills alone at a timeout or cancel) |
| worker → server | `progress` (`data`: the engine's partial result), any number |
| worker → server | `result` (`rows`, `tables`: name → `out/<name>.arrows`, `meta`) or `error` (`code`: `engine`, `limit` or `failed`; `message`) |
| worker → server | `done` (the worker is free again; in fork mode with the child's exit code or signal) |
| server → worker | `shutdown` |

## An engine

```python
from drishti_compute import ComputeError, Engine, Result

class SumEngine(Engine):
    kinds = ("sum",)

    def compute(self, req, inputs, progress):
        t = inputs.table("values")              # the SDK's table; inputs.arrow("values") gives a pyarrow.Table when pyarrow is installed
        if t.rows == 0:
            raise ComputeError("nothing to sum")    # shown to the person in these words
        x = t.column("X").data                  # a zero-copy memoryview of float64
        progress.fraction(1, 1)
        return Result(rows=[{"sum": sum(x)}])
```

## Tests

```bash
./mvnw clean test -pl drishti-compute -am -Ddrishti.compute.test.python=<python 3.10+>   # Java: pools, warm mode, fan-out, environments, approvals
<python> -m pytest drishti-compute/src/test/python                                       # the SDK: codec (and pyarrow agreement), sample engine, worker protocol
./mvnw test -pl drishti-compute -Dcompute.excluded.groups=none -Dgroups=load -Dtest=ComputeLoadTest -Ddrishti.compute.load.workers=32
```

Tests that need a worker look for Python in `-Ddrishti.compute.test.python`, `DRISHTI_PYTHON`, the console's `.venv`, then `python3`;
without one they skip. `EnvironmentTest` also needs `venv` with `ensurepip`. Fork-mode tests skip on Windows.
