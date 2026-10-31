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
# Compute workers: Python and Java libraries for connectors, approved, pinned and isolated

Status: **steps C1 to C3 built and released in 2.8.0 (2026-10-26)**; C4 to C6 planned, in no release yet ([section 16](#16-build-plan)). Built: the `drishti-compute`
module (supervisor, Python pools in fork and warm mode, JSON-lines control, Arrow scratch transport, limits and the sandbox, fair
scheduling, fan-out over N workers and Drishti\*net nodes), the pure-Python worker SDK `drishti_compute` with its sample engine,
`requires:` on connector files, locked environments with manifests, Java libraries by sha256, the four-eyes approval in the Drishti
database, and Admin → Compute ([OPERATIONS.md](../admin/OPERATIONS.md#19-compute-workers), the operator's guide). Still **planned**: the pricer interface and the
stand-in (C4), QLRisk (C5, and **design only** until its author declares its API stable), the QuantLib Java worker (C6), the reactive
connectors that send work to the pools (DATA_ARCHITECTURE.md R1 to R6), the planned `drishti.py compute`, the pre-approval file and per-pool OS
users. Each section says what differs from the build where it does. Related: [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#4-reactive-connectors) (the connectors that use these workers),
[VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) (the first user), [AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed) (the earlier pricing-service proposal,
reconciled in [section 14](#14-how-this-fits-with-what-exists)), [PYTHON_BI.md](../guides/PYTHON_BI.md) (the console's Python runner,
built).

## Contents

1. [The ask](#1-the-ask)
2. [Decisions](#2-decisions)
3. [The shape](#3-the-shape)
4. [`requires:`: what a connector needs to run](#4-requires-what-a-connector-needs-to-run)
5. [Locked environments](#5-locked-environments)
6. [Approval](#6-approval)
7. [The worker pool](#7-the-worker-pool)
8. [The engine contract (Python)](#8-the-engine-contract-python)
9. [Pricing: one SPI, two engines](#9-pricing-one-spi-two-engines)
10. [Java compute libraries](#10-java-compute-libraries)
11. [Security and sandboxing](#11-security-and-sandboxing)
12. [Windows](#12-windows)
13. [Configuration](#13-configuration)
14. [How this fits with what exists](#14-how-this-fits-with-what-exists)
15. [Operations](#15-operations)
16. [Build plan](#16-build-plan)
17. [Tests](#17-tests)
18. [Open questions for the product owner](#18-open-questions-for-the-product-owner)

---

## 1. The ask

Reactive connectors ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#4-reactive-connectors)) compute their answers. The computations a bank wants are
mostly written in Python (numpy, polars, scipy, QuantLib, the product owner's QLRisk) and sometimes in Java (QuantLib's own Java
binding, a vendor or in-house pricer). This document says **where that code runs**, **how its libraries arrive** (pinned, verified,
approved by an administrator) and **how it is kept from harming the server, the data or other users**.

## 2. Decisions

| # | Decision (product owner, 2026-10-24) | Consequence |
|---|---|---|
| 1 | Python runs in **out-of-process workers**: a pool, data passed as **Arrow**, run **as the user**, from a **pinned environment**, **Windows-safe**. | A worker crash, a memory leak or a long calculation never touches the server's JVM. |
| 2 | **No FFI and no embedded CPython; Java stays on release 21.** | No Panama/FFM, no JEP or GraalPy in the server process. |
| 3 | A connector declares **`requires:`**: `python` (a locked environment with pinned wheels, including QLRisk), `java` (Maven coordinates with sha256, from the bank's repository or shipped), and `plugins`. **An administrator approves before use.** | Nothing is downloaded or run on a connector author's word alone. |
| 4 | Java pricing uses **QuantLib's own Java binding (QuantLib-SWIG Java)**: the native library built per OS, **Linux first**, packaged in a versioned jar, **extracted under the configurable data folder** (never `/tmp`), loaded **in an isolated Java worker process**. Its tests **skip on Windows** (no native binaries may be placed on the QA machine). | The server never loads a native library. |
| 5 | **QLRisk is the default pricing provider, behind a pluggable pricer SPI.** It is used once its API is declared stable; in 2.8.0 what-if pricing goes through QLRisk only if it is stable by then, otherwise in 2.8.x. | The SPI and a parity test are built in 2.8.0 whatever QLRisk's state. |

## 3. The shape

```
 Drishti server (Java 21)                                            Worker processes (children of the server's supervisor)
 ┌─────────────────────────────────────────────────────┐             ┌──────────────────────────────────────────────┐
 │ reactive connector ── ComputeEngine bridge           │  control:   │ Python worker  (env A: drishti-var, numpy,   │
 │   │                                                  │  JSON lines │   polars, qlrisk 2.4.0, QuantLib 1.x wheels) │
 │   ▼                                                  │ ──────────▶ │   one request at a time; no network          │
 │ drishti-compute (module, built: C1-C3)               │  data:      │   scratch: <data dir>/compute/scratch/<req>/ │
 │   planner · admission · routing · Arrow transport    │  Arrow IPC  ├──────────────────────────────────────────────┤
 │   result cache · stamps · audit                      │ ◀────────── │ Python worker  (env B: another lock)         │
 │   supervisor: start, watch, limit, recycle, kill     │             ├──────────────────────────────────────────────┤
 │   environment builder · approval store               │             │ Java worker (JDK 21, own classpath):         │
 └─────────────────────────────────────────────────────┘             │   QuantLib-SWIG jar + libQuantLibJNI.so from │
                                                                     │   <data dir>/compute/native/quantlib/<ver>/  │
                                                                     └──────────────────────────────────────────────┘
```

- **`drishti-compute`** (the Maven module, built) is generic: nothing in it knows about VaR. It plans and admits requests, moves data as
  Arrow, supervises workers, builds environments and records approvals.
- **The VaR maths** is a separate, versioned Python package in the repository (planned: `compute/drishti-var`, its own
  `pyproject.toml` and tests), installed into the var-engine's locked environment like any other wheel. Drishti's own compute packages
  are versioned and approved exactly like third-party ones.
- **Workers never read data themselves.** The server reads inputs through the connector router as the requesting user, masks them, and
  hands the worker Arrow batches ([DATA_ARCHITECTURE.md › Reactive connectors, section 6](DATA_ARCHITECTURE.md#6-the-request-and-its-lifecycle)).

## 4. `requires:`: what a connector needs to run

```yaml
# in config/connectors/var-engine.yaml                         (built: parsed, approved, gated; the reactive plugin is R1)
requires:
  python:
    version: "3.12"                          # the interpreter the environment is built with
    lock: requires/var-engine.lock           # the lock file, beside the connector file (below)
    index: bank-pypi                         # a named package source of config/compute/compute.yaml, or "shipped"
  java:
    - coords: org.quantlib:quantlib-java:1.36.0-drishti.1     # built by us, shipped or in the bank's repository
      sha256: 4b1f…e09a
      classifier: linux-x86_64
      source: shipped                        # shipped | bank-maven
      native: true                           # carries a native library: Java worker only, never in the server
  plugins:
    - name: drishti-plugin-delta             # a connector plugin the computation depends on (must be installed and enabled)
```

*As built:* `requires:` is a key of every connector file (`ConnectorFiles`); a connector whose requirements are not all approved stays off
and says why (state `NOT_APPROVED`, *waiting for approval of N requirements (request cr-…)*). Plugins are recorded as approval items;
checking that they are installed arrives with the reactive plugin (R1). `requested-by:` (optional) names the person who asked, for four
eyes; without it, the administrator who last saved the file through Admin → Connectors.

The **lock file** pins every wheel, including transitive ones, with its hash. It is generated once by a tool (planned:
`drishti.py compute lock`, planned), reviewed, committed with the connector, and never resolved again at install time:

```text
# requires/var-engine.lock            (planned, not built) python 3.12, platforms: manylinux_2_28_x86_64, win_amd64
drishti-var==0.1.0     --hash=sha256:0d6a…      # Drishti's own package, built from compute/drishti-var
numpy==2.1.3           --hash=sha256:a7c1…  --hash=sha256:51e9…
polars==1.12.0         --hash=sha256:3f20…
pyarrow==18.0.0        --hash=sha256:9be4…
scipy==1.14.1          --hash=sha256:c2d8…
QuantLib==1.36         --hash=sha256:77ab…
qlrisk==2.4.0          --hash=sha256:1e5f…      # from the author's wheel (qlrisk-2.4.0-py3-none-any.whl), shipped
```

- **Python packages** come from one of: `shipped` (wheels inside the Drishti distribution or a pack archive, under a `wheels/`
  folder), a **named index** the site configures (the bank's internal PyPI mirror, Artifactory, Nexus), or a **local path** an
  administrator names. Public PyPI is never used unless the site names it as an index.
- **Java libraries** are Maven coordinates with a sha256, from the bank's Maven repository (the site's `settings.xml` mirror) or
  shipped. A jar marked `native: true` (or found to contain a `.so`, `.dll` or `.dylib`) can only run in a Java worker.
- **Plugins** are Drishti connector plugins the computation needs (installed jars, as today); they are checked, not installed.
- **The same mechanism serves any connector**, not only reactive ones: a connector plugin of a vendor that needs a Java client
  library can declare it the same way.

## 5. Locked environments

| Rule | How |
|---|---|
| **One environment per lock**, shared by every connector with the same lock | the environment's id is the sha256 of (Python version, platform, lock file); two connectors with the same lock use one environment |
| **Built under the data folder**, never in a system location or `/tmp` | `<data dir>/compute/envs/<env id>/` (a venv); its wheels cache `<data dir>/compute/wheels/` |
| **Offline and exact** | `python -m venv`, then `pip install --require-hashes --no-deps --only-binary=:all:` from the approved sources; no source builds, no resolution, no network beyond the named index |
| **Read-only once built** | the environment folder is made read-only; a worker cannot install into it; a changed file is detected by a manifest of hashes checked at worker start |
| **Never changed in place** | a new lock is a new environment; the old one stays until no approved connector uses it, then is removed by `drishti.py compute gc` (planned; `Environments.gc` is built) |
| **The interpreter** | the site's Python (`compute.python`, a path; the console's Python by default). Drishti does not download interpreters. |

Building an environment is an administrator action (or automatic after approval, `compute.build: on-approval`), shown with its log in
Admin → Compute. *As built (C2):* environments of **pools** (`pools.<p>.environment`) are built and verified at every worker start; a
connector's own `requires.python` is approved now and gets its environment when the reactive plugin runs its engine (R2). Java libraries
are checked by sha256 and scanned for native code, and staged under `<data dir>/compute/jars/<sha256>/`. A build that fails (a hash mismatch, a wheel missing for the platform) leaves no environment behind and names the
wheel.

## 6. Approval

Nothing in `requires:` is fetched, built or run until an administrator approves it.

```
 author commits a connector with requires: ──▶ server loads it: "waiting for approval" (Health DOWN, panels "not approved yet")
        │
        ▼
 Admin → Compute → Requests: the diff against what is approved today, per package:
   name, version, hash, source, licence, size, native code (yes/no), known vulnerabilities (from the site's scanner feed, if set)
        │
   approve (with a reason) ──▶ environment built ──▶ connector starts        reject (with a reason) ──▶ stays off
```

- **What needs approval:** a new package, a new version, a new hash for the same version, a new source, a new Java library. Removing a
  package does not.
- **Who:** an administrator (built: any administrator, or only those holding one of `compute.approval.roles`; a dedicated
  `compute-approve` role power is planned); never the person who changed the connector file (four
  eyes, as for write-back approvals).
- **Recorded:** each decision is an audit entry (who, when, what, why), and the approved set is kept in the Drishti database
  ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built)) as rows of a planned `drishti_compute_approval` table, so every node of a cluster sees the same
  approvals.
- **Revocation:** an administrator can revoke an approval; new requests are refused at once, in-flight ones finish.
- **Signed packs:** a pack archive from the signed registry may ship wheels and jars; the signature proves where they came from, not
  that the site wants them: approval is still required.
- **A site may pre-approve** a list in `config/compute/approved.yaml` (planned, open question 5; not built) for automated deployments; the file is itself an
  administrator's change and audited when loaded.

## 7. The worker pool

### 7.1 Pools

A **pool** is a set of worker processes of one type and environment, configured once per site (built; the shipped file is
`config/compute/compute.yaml`, every key in [OPERATIONS.md › Compute workers, section 2](../admin/OPERATIONS.md#2-pools-and-how-many-workers)):

```yaml
# config/compute/compute.yaml                                  (built, 2.8.0)
compute:
  enabled: false                    # off as shipped: no worker starts, reactive connectors say "compute is off"
  python: ${DRISHTI_PYTHON:}        # the interpreter; empty: the console's Python
  pools:
    risk:
      type: python
      engines: [drishti_var.connector:VarEngine]                       # module:Class, loaded once per worker
      environment: { python: "3.12", lock: locks/risk.lock, index: shipped }   # or: system (the site's interpreter and the SDK)
      workers: { min: 2, max: 8 }   # started on demand up to max; idle ones beyond min stop after 5 min
      memory: 4GB                   # per worker (address-space limit on Linux; watched on Windows)
      cpu-seconds: 300              # per request
      recycle-after: 200            # requests, then the worker is replaced (bounds slow leaks)
      batch-share: 0.25
    pricing-java:                   # planned (C6): type java is refused until then
      type: java
      workers: { min: 0, max: 2 }
      memory: 3GB                   # -Xmx plus native headroom
      jvm-args: ["-XX:+UseZGC"]
  nodes:                            # built: a node's own worker counts, by its Drishti*net id
    drishti-big-1:
      pools:
        risk: { workers: { max: 64 } }
```

**As many workers as the site wants** (product owner, 2026-10-24, a hard requirement). The worker count is configuration only, per pool
and per node; the code has no upper bound and no part of it assumes one worker. Pools scale out by adding Drishti\*net nodes that serve
the same pool, each with its own count, and one request can be spread over all of them ([section 7.6](#76-fan-out-and-scale-out)).

### 7.2 Process model

- **One request at a time per worker.** Concurrency comes from the number of workers. A worker never holds two users' data at once.
- **Linux: fork-server.** As the console's Python runner already does ([PYTHON_BI.md](../guides/PYTHON_BI.md)), a parent imports the
  environment's heavy modules once (numpy, pyarrow, QuantLib) and **forks one child per request**; the child runs the request and exits.
  Start-up cost is paid once; isolation between requests is total (a fresh address space). *As built:* each worker is such a parent (N
  workers, N parents, each forking one child at a time), so a request's child can be killed alone at a timeout or cancel and the worker
  stays.
- **Windows: warm workers, reset between requests.** There is no `fork`; workers are started with `spawn`, kept warm, and **reset**
  between requests (module-level caches cleared by the engine contract, temporary files removed, the worker recycled after
  `recycle-after` requests or on any error). Recycling is more frequent by default on Windows (`recycle-after: 50`).
- **Java workers** are separate JVMs (`java -cp <approved jars> com.ash.drishti.compute.worker.Main`), one request at a time, recycled
  like Python workers; a native crash kills only that JVM.

### 7.3 Transport

| Channel | Carries | How |
|---|---|---|
| control | request, progress, result metadata, errors | JSON lines on the worker's standard input and output |
| data in | the inputs (Arrow record batches) | Arrow IPC files in the request's scratch folder `<data dir>/compute/scratch/<request id>/in/` (memory-mapped by the worker); small inputs inline |
| data out | large results (a 509-row path, a contribution table) | Arrow IPC files in `…/out/`, read by the server, validated, then the folder is deleted |

Files rather than sockets: they work the same on Linux and Windows, need no port, and let the worker memory-map large inputs. The
scratch folder is created per request, readable only by the server's OS user, and deleted when the request ends (also after a crash,
by the supervisor's sweep at start).

### 7.4 "As the user"

A worker runs **on behalf of** the requesting user in four ways: its inputs were read and masked as that user; the request carries the
user's identity for audit (never credentials); results are delivered only to that user's request id; and its limits count against the
user's quota. It does **not** switch operating-system user by default (that would need the server to run as root); a site that wants
OS-level separation can run each pool under its own service account (`pools.<p>.os-user`, Linux only, planned), which isolates pools
from one another and from the server's files.

### 7.5 Limits

| Limit | Linux | Windows |
|---|---|---|
| wall time | the supervisor kills the process group at the request's timeout | the supervisor kills the process at the timeout |
| CPU time | `RLIMIT_CPU` | watched by the supervisor (planned; the wall time is enforced) |
| memory | `RLIMIT_AS` | working set watched every 250 ms; killed above the limit |
| files | `RLIMIT_FSIZE`; writes only under the scratch folder (audit hook) | writes only under the scratch folder (audit hook) |
| network | none: a network namespace with no interfaces where the machine allows it, plus an audit hook refusing sockets (built: the audit hook; the namespace is planned) | an audit hook refusing sockets |
| processes | an audit hook refusing `subprocess`, `os.fork`, `os.exec*` | the same |

### 7.6 Fan-out and scale-out

Built in C1 (product owner's requirement: work fans out across N workers and nodes). A request may be **split into chunks** and gathered:
one named input is cut into chunks of `compute.fan-out.chunk-rows` rows; every other input (market data, scenarios) goes whole with each
chunk; each chunk is an ordinary request with its own id (`<parent>-c<i>`) and the parent's viewer, class and budget, so fairness,
limits and isolation apply per chunk; answers are gathered **in chunk order** (rows appended, tables concatenated). Chunks go to the
target with the lowest *(outstanding + 1) / workers*: this node's pool and every other Drishti\*net member alive that serves it (asked
for its load, cached two seconds). Chunks for another node travel signed, on behalf of the viewer, one hop (the receiving node runs them
on its own workers only). An unreachable node's chunk runs here; any other failure fails the whole request and cancels what is left. At
most twice the total worker count of chunks is outstanding (back-pressure). This is the shape what-if pricing needs: trades × 509
scenario days spread over every worker of the cluster.

### 7.7 Fair scheduling (as built)

One queue per pool and class (`interactive`, then `event`, then `batch`); `batch` holds at most `batch-share` of the workers; within a
class users take turns and none runs more than `compute.scheduling.max-inflight-per-user` at once; each class's queue is bounded
(`compute.scheduling.queue`) and a full queue refuses at once (`DRS-9203`), which a fan-out answers by waiting and retrying. Waiting for a
worker counts against the request's budget.

## 8. The engine contract (Python)

```python
# compute/drishti-var/src/drishti_var/connector.py             (planned, not built)
from drishti_compute import Engine, Request, Inputs, Progress, Result    # the worker SDK shipped with Drishti (built, pure Python)

class VarEngine(Engine):
    kinds = ("var-result", "var-contribution", "var-path", "var-history", "what-if")

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        vectors = inputs.arrow("pnl-vector")            # a pyarrow.Table, already filtered and masked as the user
                                                        # (inputs.table(...) gives the SDK's own table: no pyarrow needed)
        ...
        progress.partial({"done": 7, "of": 12, "estimate": {"VAR_99": 41_200_000}})
        return Result(rows=[...], arrow=None)           # validated against the kind's schema by the server

    def incremental(self, req, previous, events, inputs):  # optional (DATA_ARCHITECTURE.md › Reactive connectors, section 10.2)
        ...
```

- **Pure:** no network, no files outside the scratch folder, no global state that outlives a request (the SDK clears caches registered
  with `register_cache` between requests in warm mode; fork mode needs nothing).
- **Deterministic:** the same inputs and request give the same result (random numbers only from a seed in the request).
- **Typed errors:** `ComputeError("the scope is empty")` reaches the panel in words; any other exception is *the computation failed*
  with the request id, and the traceback goes to the server's log, never to the browser.

## 9. Pricing: one SPI, two engines

Base VaR never prices (it adds precomputed vectors, [VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives)). Pricing is needed for **what-if** (a hypothetical,
amended or partly unwound trade priced over the 509 scenario days) and for stresses.

### 9.1 The pricer SPI

```python
class Pricer(Protocol):                                  # planned (C4), not built, in drishti_compute.pricing
    name: str                                            # "qlrisk", "quantlib-java", "stand-in", a bank's own
    def products(self) -> set[str]: ...                  # product codes it can price (from the pack's pricer mapping)
    def price(self, trades: list[TradeDoc], market: MarketState) -> list[Valuation]: ...
    def scenario_pnl(self, trades: list[TradeDoc], base: MarketState, scenarios: ScenarioSet) -> "pyarrow.Table": ...
                                                         # one row per trade, a 509-long P&L vector each
```

- The **pack's pricer mapping** (the `quant: pricers:` block proposed in [AI.md › Server-side pricing: the quant service (proposed), section 6](../admin/AI.md#6-packs-declare-how-products-are-priced))
  maps a trade document's UPPER_SNAKE fields to a product and its parameters; it is shared by every pricer, so a product is mapped
  once.
- The connector chooses its provider: `pricing: { provider: qlrisk, fallback: none }`. **QLRisk is the default.**
- Every valuation says which provider, which version and which model priced it (in the result's stamp).

### 9.2 QLRisk (default, Python)

- **Design only until the API is declared stable** (the product owner, 2026-10-10 and 2026-10-24). Drishti neither imports nor
  modifies QLRisk until then; the adapter is the only file that will import it.
- **What 2.4.0 already offers** (read from its README, not yet relied on): `MarketRiskEngine.scenario_pnl(trades, market, scenarios)`
  returning a P&L object with `var()` and `es()`; historical, age-weighted and filtered scenarios; Euler and incremental
  decomposition; `SensitivityEngine`; `apply_shocks` for stresses; 90+ products. The adapter maps the pack's trade documents to QLRisk
  products and Drishti's scenario set to QLRisk's historical scenarios, and returns the per-trade P&L vector as Arrow.
- **Installation:** the author's wheel (`qlrisk-2.4.0-py3-none-any.whl`) is shipped or placed in the bank's index and pinned by hash
  in the lock; its dependencies (numpy, scipy, QuantLib) are pinned in the same lock.
- **Its temporary folder:** QLRisk 2.4.0 writes temporary files to `/tmp` unless told otherwise; the worker sets `QLRISK_TMP_DIR` to
  the request's scratch folder under the data folder, so nothing is written outside it (Drishti's rule: nothing at the machine's
  temporary folder).
- **Its own thread pool:** QLRisk has a multi-threaded engine (`engine.max_workers`); inside a worker it is limited to the worker's
  CPU share (default 1 thread per worker, so the pool, not the library, decides parallelism).
- **When QLRisk is not stable in time:** what-if in 2.8.0 is limited to what needs no pricing (removing or scaling a linear position,
  [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)) and the stand-in pricer for tests; full
  what-if pricing moves to 2.8.x.

### 9.3 QuantLib-SWIG Java (decided)

| Topic | Design |
|---|---|
| **Build** | QuantLib (C++) and QuantLib-SWIG's Java binding built by us from a pinned QuantLib release, per OS and architecture: **linux-x86_64 first**, then linux-aarch64; Windows and macOS later and only if wanted. The build is a script in the repository (planned: `tools/quantlib-java/build.sh`), run in a container, producing `quantlib-java-<ql version>-drishti.<n>-<classifier>.jar` with the native library (`libQuantLibJNI.so`) inside under `native/<classifier>/`. |
| **Pinned** | the QuantLib version and our build number are in the jar's version; the jar's and the native library's sha256 are in the lock; the parity test (below) runs for every new build. |
| **Extracted under the data folder** | at worker start the native library is extracted to `<data dir>/compute/native/quantlib/<version>/<sha256>/libQuantLibJNI.so` (never `/tmp`, never `java.io.tmpdir`), its sha256 checked **before** `System.load`, the folder made read-only. A second extraction of the same hash reuses it. |
| **Isolated** | loaded only in a **Java worker process** of a `type: java` pool, never in the server's JVM; a segmentation fault in native code kills that worker, the supervisor replaces it and the request fails with *the pricer crashed*. |
| **Java 21** | the worker runs on the same JDK 21 as the server; JNI, not FFM. |
| **Windows** | not built for Windows in 2.8.0; the QuantLib-Java tests **skip on Windows** with the reason *native QuantLib library: no native binaries on the Windows QA machine* ([section 12](#12-windows)). |
| **Licence** | QuantLib is BSD-licensed; the notice goes into `THIRD-PARTY-NOTICES.md` with the jar. |

### 9.4 Parity between the two

A **parity test** prices the same set of trades (one per supported product, on the sample market of a fixed date, plus the 509
scenario days for a handful) with QLRisk and with QuantLib-Java and requires agreement within a tolerance per product (default: NPV
within 1e-6 relative or 0.01 currency units; scenario P&L vectors within 1e-6 relative at every point). It runs in the build of either
engine and on every new QuantLib, QLRisk or adapter version; a failure blocks approval of the new version.

## 10. Java compute libraries

A connector may use a Java library (a vendor pricer, an in-house risk library) behind a planned Java compute SPI
(`ComputeEngine`, [DATA_ARCHITECTURE.md › Reactive connectors, section 16](DATA_ARCHITECTURE.md#16-the-spi-and-the-apis)):

| Library is | Runs | Class loading |
|---|---|---|
| pure Java, trusted, light (a few ms per call, bounded memory) | in the server process (built, R1: Drishti's own engines always; others if the site allows it, `compute.in-process-java: false` by default) | its own classloader per connector, parent = the API only (no Spring, no server classes), like plugins today |
| heavy, untrusted, or carrying native code | in a Java worker | its own JVM; the approved jars only |

## 11. Security and sandboxing

- **Inputs are already masked.** A worker never receives a field the requesting user cannot see.
- **No credentials, no network.** Workers start with an empty environment (only what the engine needs: `PYTHONHASHSEED`, `QLRISK_TMP_DIR`,
  thread limits), no tokens, and no network ([section 7.5](#75-limits)).
- **Read-only code.** The environment and the extracted native libraries are read-only and checked against their manifest at start.
- **No code from requests.** A request carries parameters, never code; the only code that runs is the approved engine. (Ad-hoc
  Python written by a person remains the console's Python runner, with its own guard, [PYTHON_BI.md](../guides/PYTHON_BI.md).)
- **Outputs are untrusted.** The server validates each result against the kind's schema and size limits before it is cached or shown;
  text in results is escaped like any data.
- **Supply chain.** Hash-pinned wheels and jars, approved sources only, a vulnerability feed shown at approval, a native-code flag per
  package, and the approval audit trail.
- **Resource fairness.** Pools, per-user in-flight limits and the batch share ([DATA_ARCHITECTURE.md › Reactive connectors, section 10](DATA_ARCHITECTURE.md#10-coalescing-incremental-recompute-and-back-pressure)).

## 12. Windows

The Windows QA machine accepts **no `.exe` and no native binary placed by hand**; Java (jars, Maven dependencies) and the console's
Python venv (pip packages) are allowed.

| Part | On Windows |
|---|---|
| Python workers | work: `spawn` instead of `fork`, warm workers reset between requests, limits watched by the supervisor ([section 7](#7-the-worker-pool)) |
| Locked environments | work: built from `win_amd64` wheels with `pip --require-hashes`, under the data folder; the lock lists both platforms |
| QuantLib for Python, QLRisk | QuantLib's Python wheel is a pip package (it contains a compiled extension); whether this counts as allowed on the QA machine is **an open question** ([section 18](#18-open-questions-for-the-product-owner)); until answered, QLRisk-based tests on Windows skip with a reason, and the stand-in pricer covers the flow |
| QuantLib-SWIG Java | not built for Windows; its tests skip with the reason *native QuantLib library: no native binaries on the Windows QA machine* |
| Network isolation | no namespace: the audit hook alone refuses sockets |
| Paths | everything under the data folder; no `C:\Temp`, no `%TEMP%` |

## 13. Configuration

| Setting (`config/compute/compute.yaml`, planned) | Default | Meaning |
|---|---|---|
| `compute.enabled` | `false` | built: master switch: off as shipped; a fresh install or an upgrade starts no worker |
| `compute.python` | `python3` (`python` on Windows) | built: the interpreter workers run and environments are built with (`DRISHTI_PYTHON`) |
| `compute.indexes.<name>.url` | — | built: a package source (the bank's mirror); `trust` and credentials planned |
| `compute.java-repository` | — | built: a folder or URL in Maven layout for `java:` requirements with `source: bank-maven` (replaces the planned `compute.maven.settings`) |
| `compute.shipped` | `<data dir>/compute/shipped` | built: wheels and jars of the `shipped` source |
| `compute.build` | `manual` | built: `manual` or `on-approval`: when environments are built |
| `compute.approval.roles` | `[]` | built: roles whose administrators may approve (empty: any administrator) |
| `compute.scheduling.max-inflight-per-user` | `4` | built: one user's requests running at once per pool |
| `compute.scheduling.queue.interactive/event/batch` | `256`, `10000`, `100000` | built: waiting requests per pool and class; full: busy |
| `compute.fan-out.chunk-rows`, `compute.fan-out.peers` | `5000`, `true` | built: chunk size, and whether chunks go to other nodes ([section 7.6](#76-fan-out-and-scale-out)) |
| `compute.limits.max-result-bytes` | `256MB` | built: the largest result file a worker may hand back |
| `compute.in-process-java` | `false` | built (R1): allow Java engines that are not Drishti's own to run in the server process (Drishti's own, `com.ash.drishti.*`, always may) |
| `compute.pools.<p>.*` | — | built: `type` (python), `engines`, `environment` (`system` or `{python, lock, index}`), `workers.min/max`, `mode`, `memory`, `cpu-seconds`, `recycle-after`, `batch-share`, `idle-stop`, `timeout`, `timeout-batch`, `start-timeout`, `python-path`; planned: `jvm-args` (C6), `os-user` |
| `compute.nodes.<node>.pools.<p>.workers` | — | built: this node's own worker counts |
| `compute.scratch` | `<data dir>/compute/scratch` | built: request folders |
| `compute.approved` | `config/compute/approved.yaml` | planned: optional pre-approvals (open question 5) |

Folders under the data folder: `compute/envs/`, `compute/shipped/`, `compute/jars/`, `compute/sdk/`, `compute/scratch/`, `compute/work/`
(built); `compute/native/`, `compute/results/` (planned).

## 14. How this fits with what exists

| Exists today | Relation |
|---|---|
| **The console's Python runner** (`drishti-console/core/pyrunner.py`, built; a fork-server, an audit hook, an import guard, Arrow in and JSON out) | It runs **people's ad-hoc code** in BI and Analyse. Compute workers run **approved engines** for connectors. They share ideas and, where possible, code (the audit hook and the limits), but they are separate: different trust (an author's snippet vs an approved package), different owner (the console vs the server's supervisor) and different environments (the console's allow-list vs a locked environment per connector). |
| **The console's `QuantEngine` slot** (`drishti-console/core/quant/`, built, with a stand-in) | It stays the Analyse cell's `quant.price(...)`. When QLRisk is stable, its adapter (AI.md › Server-side pricing: the quant service (proposed), section 16.4) and the compute pricer SPI share the pack's pricer mappings. |
| **Arrow to the browser** (2.9.7, `ArrowAnswers` and `core/arrow_answer.py`) | The same Arrow IPC stream format as the workers' transport, used where it was measured to pay: the VaR explorer's contribution tables by position and by trade; the P&L path, BI grids and search stay JSON or HTML. Where Arrow is used and where it is not, with the numbers: [DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6](../guides/DATA_GUIDE.md#6-arrow-to-the-browser-where-it-pays) |
| **The pricing-service proposal** ([AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed), ADR-019) | Superseded in its **deployment shape**: instead of a separate HTTP service, pricing runs in compute workers supervised by the server. Its other decisions stand: pricing outside the server's JVM, the `quant` power, pack pricer mappings, market data assembled by the server for a business date, caching by trade version and date. A remote pool (workers on other machines, reached over the network) is a later option and would look much like that service. |

## 15. Operations

- **Admin → Compute** (built, C3: pools, workers busy, queues, latency, environments, approval requests, recent requests; Test, Build,
  Cancel, Approve, Reject, Revoke; the per-connector view arrives with R6): the master switch; pools (workers, busy, memory, recycled, crashed); environments (id, lock, size,
  built when, used by); approval requests and decisions; native libraries extracted; the per-connector view of
  [DATA_ARCHITECTURE.md › Reactive connectors, section 15](DATA_ARCHITECTURE.md#15-observability-and-audit).
- **Health:** a pool whose workers keep crashing is `DEGRADED`; an environment that fails its manifest check is `DOWN` with the file
  named.
- **CLI** (planned): `drishti.py compute status|lock|build|approve|revoke|gc`. The HTTP API behind Admin → Compute is built
  (`/api/v1/admin/compute`, [OPERATIONS.md › Compute workers, section 7](../admin/OPERATIONS.md#7-admin--compute)).
- **Upgrades:** a Drishti upgrade never changes an approved environment; new versions of Drishti's own compute packages arrive as a
  new lock and an approval request.

## 16. Build plan

| Step | Delivers | Accepted when |
|---|---|---|
| C1 (**built**, 2.8.0) | `drishti-compute` module: supervisor, Python pools (Linux fork-server, Windows spawn), JSON-lines control, Arrow scratch transport, limits, the `drishti_compute` shim and engine contract | a test engine computes over 1 million Arrow rows in a worker; a crashing or looping engine is killed and replaced; the scratch folder is gone after every request |
| C2 (**built**, 2.8.0) | `requires:` parsing, lock files, environment builder (`--require-hashes`, offline), read-only environments with manifests | an environment builds from a local wheel folder with no network; a tampered wheel fails the build; a tampered file in a built environment stops the worker |
| C3 (**built**, 2.8.0) | approval: requests, Admin → Compute, the approval table, four eyes, revocation, audit | a connector with unapproved requirements stays off and says so; approval by its author is refused |
| C4 (planned) | the pricer SPI, the stand-in pricer, the pack pricer mappings (shared with AI.md › Server-side pricing: the quant service (proposed), section 6) | a what-if on a sample swap prices with the stand-in through the SPI |
| C5 (planned) | QLRisk provider (**only if its API is declared stable**; otherwise 2.8.x) | QLRisk prices the sample products; the parity test runs |
| C6 (planned) | QuantLib-SWIG Java: the build script (linux-x86_64), the jar, extraction under the data folder, Java worker pools, parity with QLRisk | the parity test passes on Linux; the tests skip on Windows with the stated reason |

## 17. Tests

*As built (C1 to C3):* `drishti-compute`'s `ComputePoolTest` (a million rows, crash, loop, CPU and memory limits, isolation, progress,
the sandbox, cancel), `WarmModeTest` (the Windows way, on every platform), `FanOutTest` (two nodes, a node down), `EnvironmentTest` (an
offline build from a local wheel, a tampered wheel, a tampered environment, Java libraries), `ApprovalsTest` (memory and SQLite),
`FairQueueTest`, `ComputeSettingsTest`, the configurable `ComputeLoadTest`; the SDK's pytest suite (`drishti-compute/src/test/python`);
`ComputeApiTest` in the server (a connector off until approved, four eyes, revocation) and the console's `test_admin_compute.py`. The
"nothing written outside `<data dir>/compute/`" check is by construction (temporary folders, scratch and environments are all under it).

- **Isolation:** two requests of different users on one pool never share a process at the same time; a module-level variable set by
  one request is not visible to the next (Linux: fork; Windows: reset and recycle).
- **Limits:** memory, CPU, wall time, file writes outside the scratch folder, sockets, subprocesses: each refused or killed, each with
  a clear error.
- **Supply chain:** a hash mismatch, a missing platform wheel, an unapproved version, a jar with an undeclared native library.
- **Paths:** nothing is written outside `<data dir>/compute/` (checked by listing the machine's temporary folder before and after).
- **Parity:** QLRisk and QuantLib-Java agree within tolerance (Linux only).
- **Windows:** the Python pool, environments and the stand-in pricer pass on the Windows QA machine; native tests skip with reasons.

## 18. Open questions for the product owner

1. **QuantLib's Python wheel on the Windows QA machine.** It is a pip package but contains a compiled extension (`.pyd`). Is that
   allowed (it is installed by pip into the venv, not placed by hand), or must QLRisk-based tests skip on Windows?
2. **QLRisk's stability date.** Is QLRisk's API expected to be declared stable during 2.8.0? If not, the what-if pricing step (C5)
   moves to 2.8.x as agreed; please confirm.
3. **Where our QuantLib-Java jar is published.** Shipped inside the Drishti distribution, or pushed to the bank's Maven repository by
   the bank (we provide the build script)?
4. **Operating-system users for pools.** Is per-pool OS-user separation (Linux) wanted in 2.8.0, or is the process isolation of
   [section 7](#7-the-worker-pool) enough to start?
5. **Pre-approval file.** Should automated deployments be allowed to pre-approve requirements in the planned `config/compute/approved.yaml`, or
   must every approval be made in Admin → Compute?
6. **Vulnerability feed.** Does the bank have a scanner feed (for example an OSV mirror) Drishti should show at approval time?
