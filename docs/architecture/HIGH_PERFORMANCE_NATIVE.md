<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# High-performance native integration and Java 25

*Revision 1.0 · 2026-11-01 · Author: Ashutosh Sinha*

Status: **proposed** (design, not built). Release placement is in [IMPLEMENTATION_PLAN.md › High-performance native integration (proposed)](IMPLEMENTATION_PLAN.md#high-performance-native-integration-proposed); this document names work only by phase (P0 to P5).

This is the roadmap for making Drishti faster where it matters: the VaR and exposure engines, the loaders, BI and the Python
console. It covers three levers, in the order they should be pulled:

1. **Pure Java first**: the allocation and algorithm fixes the profiles point at, which need no new dependency.
2. **Java 25 as the only runtime**: the Foreign Function & Memory API, compact object headers, the AOT cache, virtual threads without
   pinning, and the Vector API behind a switch.
3. **Native libraries, optional**: BLAS, compression, JSON, hashing, and embedded columnar engines (DuckDB, DataFusion, Polars), each
   behind a setting, each with pure Java (or the existing path) as the default and as the fallback.

## Contents

1. [Principles](#1-principles)
2. [Java 25 as the baseline](#2-java-25-as-the-baseline)
3. [Step 0: the memory model of mapped dates](#3-step-0-the-memory-model-of-mapped-dates)
4. [The hot paths, place by place](#4-the-hot-paths-place-by-place)
5. [Native libraries and their pure-Java rivals](#5-native-libraries-and-their-pure-java-rivals)
6. [BLAS](#6-blas)
7. [Embedded columnar analytics engines](#7-embedded-columnar-analytics-engines)
8. [The roadmap in phases](#8-the-roadmap-in-phases)
9. [Testing and operations](#9-testing-and-operations)
10. [Ranked opportunities](#10-ranked-opportunities)
11. [Decisions of the product owner](#11-decisions-of-the-product-owner-2026-11-01)

---

## 1. Principles

### 1.1 Measure first

Nothing in this roadmap is built because it sounds fast. Each item names the profile that justifies it and the benchmark row it must
move ([BENCHMARK.md](../admin/BENCHMARK.md)). What the profiles say today:

| Finding | Where measured | What it means |
|---|---|---|
| The server is CPU-bound at about 30 requests a second under the VaR mix, and the parts path took about 60% of the CPU and 93% of allocation | [BENCHMARK.md section 4](../admin/BENCHMARK.md#4-the-200000-position-var-and-cluster-run) | the first limit was **allocation**, not arithmetic: no SIMD or BLAS helps a path that is busy creating objects |
| The whole bank summed from float32 rows runs at about 60 GB/s on 24 threads | [BENCHMARK.md section 5.2](../admin/BENCHMARK.md#52-the-tiered-store-vartierbenchmark) | the scope sum is **memory-bandwidth-bound**: at 1M positions a date is about 2 GB of rows a pass |
| A date's first open is read-bound and was single-threaded | [BENCHMARK.md section 5.4](../admin/BENCHMARK.md#54-a-dates-first-open) | Parquet decoding and decompression: **compute** per byte, parallel by row group |
| The Java loader: decompress 124 MB/s, parse 30,000 rows/s, write 24,500 rows/s per thread | [BENCHMARK.md section 7.2](../admin/BENCHMARK.md#72-the-java-parallel-loader-per-stage) | three **compute-bound** stages of similar size: no single one dominates |
| The Python loader path is about 9 times slower than the Java loader (68 s against 7.7 s at 200,000 positions) | [BENCHMARK.md section 7.1](../admin/BENCHMARK.md#71-the-200000-position-var-inputs-the-python-path-and-the-java-loader) | single-threaded Python staging of JSON |
| PFE with incrementals grows a little faster than P (a sort of each date's P paths per part) | [BENCHMARK.md section 6](../admin/BENCHMARK.md#6-exposure) | an **algorithm** cost: a full sort where a selection suffices |
| Compact object headers: 5.2% less heap with two dates open | [BENCHMARK.md section 4.4](../admin/BENCHMARK.md#44-java-21-and-25-g1-and-zgc) | the heap is mostly arrays; headers matter more where small objects are many (strings, ids) |

### 1.2 Bandwidth-bound, compute-bound, allocation-bound

Each hot path is one of three kinds, and each kind has its own tools. Picking the wrong tool spends effort for nothing.

| Bound | Symptom | Helps | Does not help |
|---|---|---|---|
| **Allocation** | high allocation rate in JFR, GC CPU, `double[]`/boxed objects in the allocation profile | reuse buffers, primitive collections (fastutil), streaming instead of lists, no boxing, scoped values instead of thread-locals on virtual threads | SIMD, BLAS, native code |
| **Memory bandwidth** | throughput near the machine's GB/s; CPU at 100% but IPC low; scales until the memory channels saturate | read fewer bytes (float32 rows, aggregates, column pruning), read once for many answers (batching scopes: GEMM), keep data hot (NUMA, huge pages), `MemorySegment` without copies | faster arithmetic: SIMD on its own gains little once the bus is full |
| **Compute** | high IPC, scales with cores, far below memory bandwidth | SIMD (Vector API), better algorithms (selection over sorting), native libraries tuned per CPU (BLAS, libdeflate, simdjson, zstd) | fewer allocations alone |

A rule follows: **the VaR scope sum is bandwidth-bound**, so its big gain is reading the rows once for many scopes, not doing each
addition faster. **The loaders are compute-bound**, so native decompression and JSON parsing pay directly. **The parts path was
allocation-bound**, so its fix is pure Java.

### 1.3 Pure Java is the default; native is optional

- Every native path is **off by default**, behind a setting under `drishti.native.*` (section 9.3), with the pure-Java path as the
  default and as the fallback.
- The Windows QA machine takes **no native binaries dropped beside Drishti** (no `.exe`, no `.dll` copied in). Native code that already
  ships **inside a dependency's jar** (zstd-jni, snappy-java, lz4-java, RocksDB, DuckDB JDBC) unpacks into the server's temp folder as it
  does today ([OPERATIONS.md](../admin/OPERATIONS.md)). Anything else native is found on the host (a system BLAS, a system library) or
  not used.
- A native test **skips itself** where its library is absent, with a clear reason, and its pure-Java twin always runs.
- The answer is the same either way: the parity rules are in [section 9.1](#91-parity-native-on-against-native-off).

### 1.4 Crash isolation

Java code fails with an exception; native code can fail by killing the process. So:

| Risk | Guard |
|---|---|
| A native call reads freed or unmapped memory | every native call takes a `MemorySegment` from a confined or shared `Arena` the caller owns; the arena outlives the call (the same guard `MappedMatrix` already uses for unmapping) |
| A library crashes on some CPU or input | a **canary** at start: the library is loaded and runs a small known problem against the pure-Java result; on any mismatch or error the setting is turned off for the run and Health says why |
| A library that cannot be trusted in-process (a large engine, an untested build) | a **sidecar process** over Arrow IPC or Arrow Flight (section 7): a crash costs the sidecar, not the server |
| A native allocation the JVM does not see | Native Memory Tracking (already on in the benchmark), `drishti.native.*.max-memory-mb` per library, and the library's own allocator limits (DuckDB's `memory_limit` is already set) |
| Native code on a virtual thread | a native call pins its carrier thread for the call's length (section 2.6): native calls run on a small platform-thread pool, never on the request's virtual thread |

### 1.5 Packaging per platform

| Platform | Status | How native code arrives |
|---|---|---|
| linux-x86_64 | primary | inside dependency jars (as today), OpenBLAS included (bundled, section 6.3) |
| linux-aarch64 | supported (Graviton on AWS) | the same; every candidate below is checked for an aarch64 build before it is chosen; BLAS: Java (no bundled build yet) |
| macOS (aarch64) | developer machines | inside jars; BLAS: Java (a `macosx-arm64` build of the preset can be added later) |
| Windows x86_64 | QA and some users | inside jars only, unpacked by Java (allowed, section 11.1), OpenBLAS included |

**Checksums and licences.** Every native artefact Drishti loads itself (not one unpacked by a dependency's jar) is listed in a manifest
with its platform, version, SHA-256 and licence, checked before it is loaded; the same rules the compute environments use for jars with
native code ([OPERATIONS.md](../admin/OPERATIONS.md)) apply. `THIRD-PARTY-NOTICES.md` lists each one. Nothing is fetched at run time;
nothing comes from a CDN.

---

## 2. Java 25 as the baseline

Java 21 is being retired; Java 25 (an LTS release) becomes the only runtime. Today the build compiles with `--release 21`
(`pom.xml`), the drill verifies on 21 and runs a forward-compatibility verify on 25 (`tools/drill.sh`), and Java 25-only features are
reached at run time by reflection (`FileMaps`) or by flags added only on 25 (`tools/windows/start-server.ps1`). With 25 as the baseline
those workarounds go.

### 2.1 The features and where Drishti uses them

Statuses are those of **Java 25** (JDK 25, September 2025). "Final" means a standard feature; "preview" needs `--enable-preview` and may
change; "incubator" lives in a `jdk.incubator.*` module and may change.

| Feature | JEP and status in Java 25 | Where in Drishti | Gain |
|---|---|---|---|
| **Foreign Function & Memory API** (`MemorySegment`, `Arena`, `Linker`) | JEP 454, **final** (since Java 22) | `FileMaps` (drishti-api) maps through a shared `Arena` found by reflection and hands out a `ByteBuffer`; `MappedMatrix` (drishti-compute) maps rows in pieces of at most 2 GB because a buffer cannot be larger; `VarHotFile`, `ExposureHotFile`, `RowSpill`; native calls to BLAS and other libraries (sections 5, 6) | one mapping per matrix of any size, no 2 GB pieces, no reflection, no `sun.misc.Unsafe` cleaner fallback, reads straight from the segment without the scratch copy |
| **Vector API** (`jdk.incubator.vector`) | JEP 508, **incubator** (tenth incubation) | `MappedMatrix.addRangeInto` (float32 rows widened into float64 sums), `VarMath.addInto`, `ExposureMath.addInto`, `mean`, `positive` | fewer instructions per value; matters where a path is compute-bound (the heap tier, exposure); little once the bus is full |
| **Compact object headers** | JEP 519, **final** (a product option, off by default: `-XX:+UseCompactObjectHeaders`) | the whole server; already passed on Java 25 by `tools/windows/start-server.ps1` and the benchmark | 5.2% less heap measured at 200,000 positions; more where objects are small and many (the ids and strings of a date's skeleton) |
| **Generational ZGC** | JEP 439 (Java 21); generational is ZGC's only mode since Java 24 (JEP 490); **final** | an option for large heaps (`-XX:+UseZGC`); G1 stays the default | low pauses at large heaps; measured RSS is higher (section 2.4) |
| **AOT class loading and linking** | JEP 483, **final** (Java 24) | server and loader start-up | faster start: classes found, loaded and linked from the cache |
| **AOT command-line ergonomics** (`-XX:AOTCacheOutput`) | JEP 514, **final** (Java 25) | building the cache in one training run | one step instead of two to make the cache |
| **AOT method profiling** | JEP 515, **final** (Java 25) | warm-up: the JIT starts from profiles recorded in training | the first requests near warm speed |
| **Virtual threads without pinning on `synchronized`** | JEP 491, **final** (Java 24) | the engine's virtual-thread executors (`EngineConfiguration`, `SourceRegistry`, `ConnectionProbe`, `DerivedSourcePlugin`); code that used `ReentrantLock` only to avoid pinning (`DuckDbEngine.connection`) | `synchronized` no longer pins; native calls (JNI and FFM) still do (section 2.6) |
| **Scoped values** | JEP 506, **final** (Java 25) | per-request context now kept in `ThreadLocal`s (15 in main code, among them the scratch buffers of `MappedMatrix`), the principal and the request's deadline across virtual threads | no per-thread copies on millions of virtual threads; inherited by child tasks |
| **Structured concurrency** (`StructuredTaskScope`) | JEP 505, **preview** (fifth preview) | fan-out to shards (`VarShards`, `NetComputePeers`), a scope's parts summed in parallel | cancellation and errors handled as one unit; **not used until final**, because the build runs without `--enable-preview` |
| **Stream gatherers** (`Stream.gather`) | JEP 485, **final** (Java 24) | windowed and running computations in the stress window and the P&L path (`VarStressWindow`, `VarDeskPnl`), batching rows in the loaders | clearer code; not a speed lever on its own |
| **Class-File API** (`java.lang.classfile`) | JEP 484, **final** (Java 24) | generating compiled row evaluators for BI filters and computed attributes (`RowEval`) instead of interpreting expressions per row | an expression compiled once and JIT-compiled like hand-written code |
| **Stable values** | JEP 502, **preview** | lazily computed constants (codec tables, parsed configuration) | constant-folded by the JIT; **not used until final** |
| **Restricted native access warnings** | JEP 472, **final** (Java 24): loading a JNI library or calling a restricted FFM method warns unless the module or class path is granted native access | every jar with native code (zstd-jni, snappy-java, lz4-java, RocksDB, DuckDB) and every FFM binding | start every Java command with `--enable-native-access=ALL-UNNAMED` (or the module names) so the log stays clean and a future release that denies by default does not break Drishti |
| **JFR CPU-time sampling** | JEP 509, **experimental** (Linux only) | the benchmark's profiles | CPU time per method without safepoint bias; to verify on the benchmark machine before relying on it |
| **JFR method timing and tracing** | JEP 520, **final** | timing chosen methods (`VarViews`, `ParallelSums`) in the benchmark without code changes | precise per-method cost |
| **Generational Shenandoah** | JEP 521, **final** (a product mode) | an alternative low-pause collector | to measure beside G1 and ZGC; not a default candidate |
| **Key derivation function API** | JEP 510, **final** | the cluster key (`ClusterKey`) and content encryption if they derive keys | a standard HKDF instead of hand-written code |

**To verify** (statuses not checked against the JDK 25 release notes at the time of writing): the exact experimental status and
platform limits of JEP 509; whether JEP 516 (AOT object caching with any collector, so the AOT cache also works with ZGC) is in 25 or
a later release; the AOT cache's support in the Spring Boot line in use.

### 2.2 The Foreign Function & Memory API over the hot files

Today `FileMaps.map` returns a `ByteBuffer` (limited to 2 GB), `MappedMatrix` keeps an array of mappings and an array of `FloatBuffer`
views, and each row is copied into a per-thread scratch `float[]` before it is added:

```
floats[r / rowsPerMap].get((r % rowsPerMap) * width, s, 0, width);   // copy the row
for (int j = 0; j < width; j++) acc[j] += s[j];                      // then add it
```

With Java 25 as the baseline:

- **One segment per matrix**: `FileChannel.map(READ_ONLY, offset, length, arena)` returns a `MemorySegment` of any length; the
  `rowsPerMap` arithmetic and the array of pieces go.
- **No copy**: the sum reads `segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(LITTLE_ENDIAN), offset)` (or, with the Vector API,
  `FloatVector.fromMemorySegment`) straight from the mapped page. The scratch `ThreadLocal` goes, which also removes per-virtual-thread
  copies.
- **The arena is the guard**: closing a shared arena unmaps; a stray read afterwards throws instead of crashing. `MappedMatrix` keeps its
  reader count so a close waits for readers rather than failing them.
- **No reflection, no `sun.misc.Unsafe`**: the Java 21 branches of `FileMaps` (the cleaner through `Unsafe`, the reflective lookup of
  `Arena`) are deleted. `GET /status` then always says "shared arena".
- **Alignment is already right**: the hot file's matrices are 64-byte aligned (`VarHotFile`), so aligned vector loads are possible.
- **Huge pages and advice**: `madvise(MADV_SEQUENTIAL / MADV_WILLNEED)` on a date about to be summed whole, called through FFM on Linux
  only, optional (`drishti.native.madvise.enabled`); measured against the first touch of a date from NVMe (about 0.7 s at 1M positions,
  [BENCHMARK.md section 5.2](../admin/BENCHMARK.md#52-the-tiered-store-vartierbenchmark)).

### 2.3 The Vector API, gated

The Vector API is still an **incubator** module in Java 25. It is used only:

- in **one small module** (`drishti-simd`, proposed) compiled with `--add-modules jdk.incubator.vector`, holding kernels with a
  scalar twin of identical results: `sumRows(segment, rows, width, double[] acc)`, `addInto`, `mean`, `max0`;
- when **both** the JVM was started with `--add-modules jdk.incubator.vector` **and** `drishti.native.vector.enabled` is true;
  otherwise the scalar twin runs. The check is `ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent()`;
- with the same **summation order** as the scalar code (lane-wise accumulators combined in a fixed order), so results are reproducible
  from run to run; the scalar twin keeps the same tile and lane order, so the two are bit-exact (the parity rule, section 6.2).

The incubator warning printed at start is expected and documented. When the API leaves incubation, the module drops its flag.

### 2.4 Collectors and heap layout

| Option | Measured (two dates open, 200,000 positions) | Decision |
|---|---|---|
| G1 with compact headers | heap 820 MB, RSS 3.3 GB | the JVM's default collector; compact headers on in every start script, Dockerfile and the drill |
| generational ZGC | heap 925 MB, RSS 7.0 GB | an option for heaps above about 32 GB where pause times matter more than resident memory |
| Generational Shenandoah | not measured | to measure |

The server image (`deploy/docker/server.Dockerfile`) starts generational ZGC today and picks its flags by Java version in its entry
point (compact headers on 25, `-XX:+ZGenerational` on 21). With 25 as the only runtime the version branch goes; whether the image keeps
ZGC or moves to G1 is decided by the benchmark's users rows (p99 and RSS at the image's usual heap), not by default.

### 2.5 Start-up: the AOT cache

A training run (start the server, open the demo, run the smoke requests, stop) writes an AOT cache with `-XX:AOTCacheOutput=` (JEP 514);
production starts with `-XX:AOTCache=`. The cache is tied to the exact JDK build and class path, so it is made per image (Docker) or by
`tools/` at install, never shipped across JDK updates. Measured by the benchmark's "start to healthy" row (12.2 s single server,
[BENCHMARK.md section 4.2](../admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster)) and the first-request latency.

### 2.6 Virtual threads and native calls

JEP 491 removes pinning on `synchronized`. A virtual thread **still pins its carrier while it is inside a native method or a foreign
function**. Drishti's native calls are long (a DuckDB query, a RocksDB read, a BLAS GEMM, a zstd frame), so:

- native work runs on a **bounded platform-thread pool** (`drishti.native.threads`, default the cores), the request's virtual thread
  waits on it without pinning;
- the benchmark's "pinned virtual-thread events (≥ 20 ms)" row is the check ([BENCHMARK.md section 4.4](../admin/BENCHMARK.md#44-java-21-and-25-g1-and-zgc)).

### 2.7 What changes when Java 21 is retired

| Area | Change |
|---|---|
| Build | `maven.compiler.release` 25; `--enable-native-access=ALL-UNNAMED` in surefire and the exec jar's manifest (`Enable-Native-Access: ALL-UNNAMED`) |
| Code | `FileMaps` without reflection; `MappedMatrix` on `MemorySegment`; scoped values for request context |
| Drill | one Java stage (25); the forward-compatibility copy goes |
| Start scripts and Dockerfiles | Java 25 images; `-XX:+UseCompactObjectHeaders` always; the AOT cache where built |
| Docs | the supported runtime is Java 25 in every guide |

### 2.8 Two builds, one code base: the baseline and the accelerated path side by side

Decided with the product owner (2026-11-01): **a build made with Java 25 uses the accelerated paths automatically; a build made with Java
21 runs exactly the code of today.** Both come from the same sources, so the gain of every step is measured on the same data, and the
Java 21 build stays the reference until Java 21 is retired.

| Part | How |
|---|---|
| The seam | each hot path sits behind a small Java interface (for example `VarKernels`: sum a scope's rows, select the k-th, sum many scopes; `ExposureKernels`; `HotFileReader`; `GzipReader`; `JsonIntake`). Today's code becomes the **baseline** implementation of each, unchanged |
| The accelerated module | a module (proposed name `drishti-accel`) holds the Java 25 implementations: `MemorySegment` readers, Vector API kernels, the FFM bindings to BLAS, libdeflate and zstd. It is built only by a Maven profile active on JDK 25 (`<activation><jdk>[25,)</jdk></activation>`); a Java 21 build leaves it out, and nothing else depends on it at compile time |
| Discovery | `java.util.ServiceLoader` finds the accelerated implementations when the module is on the class path; each says what it needs (the Java version, the incubator module, a native library on disk) and is offered only when that is present |
| The choice | `drishti.native.mode`: **`auto`** (the default) uses each accelerated implementation whose needs are met and keeps the baseline for the rest; `off` uses the baseline everywhere, also on a Java 25 build; `on` fails the start when an enabled implementation cannot load. Per-path switches (section 9.3) can still turn one path off. On a machine without the native libraries (the Windows QA machine) `auto` quietly keeps the baseline for those paths and says so |
| The canary | at start each chosen implementation answers a fixed check against the baseline (the parity rule of section 6.2); a mismatch drops it back to the baseline and is logged and shown |
| What is used | Admin → Health → Native (section 9.4), `/api/v1/admin/native` and the start log list, path by path, the implementation in use and why |
| Measuring the gain | the benchmark runs the same Java 25 build twice, `drishti.native.mode=off` and `auto` (the library's effect alone, the JVM unchanged), and the Java 21 build once (the runtime's effect); [BENCHMARK.md](../admin/BENCHMARK.md) shows the three side by side |
| Parity | the drill runs the parity suite (section 9.1) with `mode=off` and `mode=auto`, on Java 25 |
| After Java 21 | when Java 21 is retired the profile is always active; the baseline implementations stay as the fallback and the `off` switch, since the Windows QA machine and any machine without the native libraries still need them |

---

## 3. Step 0: the memory model of mapped dates

Before any speed work, two faults in how a mapped date is budgeted must be fixed: they refuse dates that would fit, and re-read the lake
for nothing.

### 3.1 What is wrong

- `drishti.var.memory-budget-mb` defaults to a quarter of the heap (`VarProperties`). In the mapped tier (the default) a date's vectors
  are **not on the heap**: they are a spill file on disk while the date loads (`RowSpill`, float64) and a memory-mapped hot file after
  (float32). Yet `RowSpill.add` refuses a row once the spill passes that heap-derived budget (`VectorStore.BudgetExceeded`, then
  `DRS-5204` in `VarLoader`). A date of 1M positions (about 2 GB of rows) is therefore refused on a server of `-Xmx6g` that has the RAM
  and the disk to serve it.
- A refused date is **not remembered**: each request for it reads the lake again, spills again and is refused again, so a burst of
  requests for one date turns into a burst of lake reads.

### 3.2 The fix

| # | Change | Where |
|---|---|---|
| 0.1 | **Two budgets, by where the bytes live.** The heap budget (`memory-budget-mb`) bounds only on-heap structures: the heap tier's vectors (`tier.mode: heap`), the skeleton, aggregates and caches. The mapped tier's spill and hot file are checked against **disk** (free space in `drishti.var.tier.dir`: spill P × S × 8 bytes + hot file P × S × 4 bytes + margin) and against **physical RAM** for the pages that should stay resident (the open dates' hot files against total RAM minus the heap, the direct-memory limit and a reserve; from `OperatingSystemMXBean.getTotalMemorySize()`) | `RowSpill`, `VarLoader`, `VarTier`, `VarProperties`; the same pattern for `ExposureLoader` (`drishti.ccr.exposure.memory-budget-gb`) |
| 0.2 | **RAM is advisory, disk is a hard limit.** Mapped pages the operating system evicts are read again from the file, so a mapped date larger than the page cache still answers, more slowly; Health warns. A date whose spill or hot file does not fit on disk is refused (`DRS-5204`, with the bytes needed and free) | `VarTier`, Health |
| 0.3 | **Remember refusals.** A refused date is kept in a small table keyed by date, currency, inputs digest and the budget settings, with the reason and the time; a request for it answers `DRS-5204` at once with a `Retry-After`, without touching the lake. The entry is dropped when a date is evicted or unloaded (room was freed), when the inputs change (a new digest), when settings change, or after `drishti.var.refusal-ttl-seconds` (proposed, default 300) | `VarLoader`, `VarCaches` (listed in Admin → Caches) |
| 0.4 | **The heap formula covers the heap only.** The sizing in [RESOURCES_AND_SCALE.md section 9.5](RESOURCES_AND_SCALE.md#95-capacity-tables) and [BENCHMARK.md section 11](../admin/BENCHMARK.md#11-memory-and-heap) counts, per open date, the skeleton (about 1 KB a position at the server), the aggregates (8 × S bytes a node and currency), the standalone cache (8 bytes a position and lens) and the caches pool; the hot file is counted under page cache and local disk, never under `-Xmx` | the two documents and the status page's budget figures |

Measured by: the 1M-position date opening at `-Xmx6g` (it was refused); the count of lake reads for a refused date under 50 users (one,
not one per request); the heap after collection with the date open within the formula's figure.


### 3.3 What still lives on the heap for each open date

The mapped tier keeps the rows off the heap, but the 1M-position benchmark in its earlier configuration (1,000,000 positions, 15,000
portfolios, 509 scenarios, Java 21, G1, `-Xmx16g`; [BENCHMARK.md section 4.8](../admin/BENCHMARK.md#48-1000000-positions-the-heap-and-what-the-formula-missed))
measured large per-date structures **on** the heap:

| | Heap after collection | Of which, per open date |
|---|---|---|
| no date open | 1.1 GB | |
| one date open | 4.3 GB | `long[]` about 1.69 GB, `double[]` about 0.83 GB |
| two dates open | 7.5 GB | about 3.2 GB a date |

So a date costs about **3.2 KB of heap a position** in this mapped tier, which the heap formula does not count: at `-Xmx6g` the first date
did not fit, at `-Xmx12g` the second did not, at `-Xmx16g` both open (process memory 17.2 GB). The rows themselves were 2 GB a date in
the hot file, outside the heap.

| # | Change | Where |
|---|---|---|
| 0.5 | **Name every per-date heap structure** with its bytes a position, from a heap histogram with one and with two dates open, and record them in the sizing formula | `VarBook`, `VarTier`, `VarViews`, `ScopeIndex` and what the histogram names |
| 0.6 | **Move the large ones off the heap or shrink them.** Arrays of positions (`long[]`: ids, ordinals, membership) to `MemorySegment` in the date's tier file, or to RoaringBitmap / fastutil where they are sets or maps; float64 aggregates (`double[]`) to the hot file's aggregate section, mapped | the same classes; [section 2.2](#22-the-foreign-function--memory-api-over-the-hot-files), [section 5](#5-native-libraries-and-their-pure-java-rivals) |
| 0.8 | **Evict by bytes, not by count.** Open dates are capped today by `drishti.var.hot-dates` (the newest dates plus the one just used); each date may also hold a book for each reporting currency built ahead (`drishti.var.currencies`) and its stress book. Product owner, 2026-11-01: **a configured number of dates in memory, least recently used out first.** `drishti.var.hot-dates` becomes that number, **default 10** (two weeks of business dates; product owner, 2026-11-01) (least recently used, not newest first; the date just used and the newest business date always stay; pinned dates, such as quarter-ends and the stress window, optional and counted); a date's currency books and stress book go with it and leave before it when room is short. A byte budget over every open book is the safety net: when the dates in memory would exceed it, the least recently used leaves early, even below the count. Exposure the same | `VarService.evictOld`, `ExposureService`, `VarProperties` |
| 0.7 | **Correct the sizing formula from the measurement**, then re-measure: the target is a heap per open date of at most a few hundred bytes a position, so that 1M positions and two dates open fit in `-Xmx6g` again | RESOURCES_AND_SCALE section 9.5, BENCHMARK section 11 |

**Built (plain Java, Java 21 and 25).** Node members are runs, not bitmaps of the date (the 1.69 GB of `long[]`); the aggregates are read
from the hot file's `AGGS` section, mapped (the 642 MB of `double[]`), and written to it a batch at a time; every book reports
`heapBytes()`; dates leave least recently used first with `hot-dates` 10, a byte safety net over every open book's heap closes currency
and stress books first; refusals for room are remembered; the mapped tier is bounded by the disk, not a share of the heap; pinned dates
(VAR_GUIDE section 26.8). The per-book formula is in VAR_GUIDE [section 27.1](../guides/VAR_GUIDE.md#271-bytes-per-date): about 0.77 GB
a book at 1,000,000 positions, **confirmed**: the same benchmark at `-Xmx6g` measured 0.77 GB at start, 1.35 GB with one date, 2.2 GB
with two (0.58 and 0.86 GB a date, 0.72 on average) and 2.4 GB after 200 users, with 183, 138 and 124 requests a second at 50, 100 and
200 users and no failure; item 0.7's aim, two dates of 1M positions within `-Xmx6g`, is met (0.72 KB a position). **Still on the heap, next:** the code and number columns, the ids and
their indexes, the high-cardinality dictionaries (mapped primitive and string sections, a hot-file format 3), and the per-trade-currency
totals (`CTOT`, a fixed mapped layout); the build's per-book currency sums (`perBook`) spilled.

**Java 21 too.** Every change of this step (0.1 to 0.7) is plain Java and runs on Java 21 as well as 25: the off-heap arrays use
`MappedByteBuffer` views over the date's tier file, which Java 21 has, and fastutil and RoaringBitmap are ordinary libraries; on Java 25
the accelerated path of section 2.8 reads the same file through `MemorySegment`. Step 0 is therefore not held back by the Java 21
retirement (product owner, 2026-11-01: memory work in 2.9.9).

### 3.4 Precision of the hot file

The hot file stores each position's scenario P&L in **float32**, about 7 significant digits: half the bytes of float64, and so half the
disk, page cache and memory bandwidth. Node aggregates are kept in float64 and checked against the rows (VAR_GUIDE section 26). A scope
summed from rows (an ad-hoc scope, a basket, the bank minus a desk) therefore carries each position's value to about 7 significant
digits; the summation itself is float64 and follows the parity rule of [section 6.2](#62-precision-and-parity).

| Option | Bytes a value | Effect |
|---|---|---|
| float32 hot file (today, the default) | 4 | every scope from rows exact to about 7 significant digits a position; the fastest, least disk and memory |
| **float64 hot file** (proposed setting, `drishti.var.tier.precision: float64`) | 8 | every scope from rows exact to about 16 significant digits; twice the hot file's disk and page cache and twice the bytes read per answer (about 2 GB more a date at 1M positions) |
| float64 for nodes and baskets, float32 for the rest | 4 and 8 | the stored portfolios exact, ad-hoc scopes at float32; a middle ground |

The choice is the bank's: the float64 hot file is planned as a setting, with the benchmark measuring its cost, and the parity suite run
with both.

---

## 4. The hot paths, place by place

| Path | Classes | Bound | Today | What would move it |
|---|---|---|---|---|
| VaR scope sum (a basket, the bank minus a desk, an ad-hoc scope) | `MappedMatrix.addRangeInto`, `ParallelSums`, `SetSums`, `VarBook` | **bandwidth** (about 60 GB/s on 24 threads) | float32 rows copied to a scratch array, added into float64 | FFM without the copy (P1); many scopes in one pass (GEMM-shaped, P3); the Vector API for the widening add (P2) |
| VaR selection (VaR, ES, standalone VaR of each part) | `VarMath.var`, `worst`, `order` | compute, small | the k worst kept in a buffer of k, O(S × k); the full order only when k > 64 or a NaN | already right; a SIMD min-scan for the threshold is a marginal gain |
| VaR parts and totals (the headline and contributions) | `VarViews`, `VarSelection`, `VarPartTotals` | was **allocation** | the profiled path; the standalone values are now cached per book and lens ([VAR_GUIDE.md section 27a](../guides/VAR_GUIDE.md#27a-how-scopes-parts-and-totals-are-computed-and-why-it-scales)) | measure again first; fastutil maps and reused buffers where allocation remains |
| VaR first open (lake read, sort, sums, the hot file) | `VarLoader`, `VarIntake`, `VarHotFile`, `RowSpill`, the Delta and Iceberg readers | compute (decode, decompress) then bandwidth (sums) | decoded on several threads; written in batches of 1,024 positions | native zstd/snappy already; FFM writes; CRC32C already an intrinsic |
| Exposure scope (sets' E, N and max(V, 0) summed, statistics) | `ExposureMath.addInto`, `statistics`, `mean`, `ExposureScopes` | bandwidth, then compute per row | about 2.6 ns a value at the bank | Vector API (P2); BLAS GEMV for the means (P3) |
| Exposure PFE and incrementals | `ExposureMath.sortedRow`, `quantileSorted`, `order` | **compute and allocation** | a full `Arrays.sort` of each date's P paths per part; `order` sorts a boxed `Integer[]` | **selection** (quickselect or the k-worst buffer) for the quantile; a primitive index sort for `order` (P1) |
| Exposure load (rows into sets' value matrices, collateral) | `ExposureBook.Builder`, `ExposureMath.exposure` | compute, linear in T × G × P | about 4 ns a value | Vector API for the max/min passes (P2) |
| Java loader: decompress | `GzipMembers` (JDK `Inflater`), `Archives` | compute, 124 MB/s a thread in | the JDK's zlib | libdeflate or zlib-ng (P4) |
| Java loader: parse | `RowParser`, `ParseWorker` (Jackson streaming, fast double parser) | compute, 165 MB/s a thread | no tree, no boxing | simdjson-java (pure Java, SIMD) or native simdjson (P4) |
| Java loader: write | `DateWriter`, `DeltaTarget`, `IcebergTarget`, `NativeCodecs` (Snappy, ZSTD via JNI) | compute | Parquet encoding, BYTE_STREAM_SPLIT, ZSTD | native already for codecs; encoding is Java |
| Python loader path | `tools/lake/build.py`, `stage.py`, `jsonstream.py`, writers | **single-threaded Python** | 68 s against the Java loader's 7.7 s | make the Java loader the default where a JVM exists; Polars for the no-JVM case (section 7) |
| Lake reads (Parquet pages) | `drishti-lakeio` (`ParquetInput`, `PageCodec`, `Lz4Pages`, `NativeCodecs`), the Delta and Iceberg readers | compute (decompress, decode) | ZSTD and Snappy native (JNI), LZ4 pure Java | already native where it pays; Arrow C Data from DuckDB or DataFusion for scans (section 7) |
| Disk cache and message stores | `DiskCache`, `SortedStore` (RocksDB) | I/O and compute | RocksDB (native, JNI) | already native; its compression set to LZ4/ZSTD |
| BI queries | `DuckDbEngine`, `QueryService`, `ArrowIpcWriter`, `ArrowAnswers` | compute in DuckDB; **row-by-row JDBC** to Arrow | `ResultSet` read value by value into columns, then written as Arrow IPC | DuckDB's Arrow export (C Data Interface) straight into the Arrow answer (P4) |
| BI row evaluation | `RowEval`, `SqlCompiler` | compute | interpreted per row | compiled with the Class-File API (P2) |
| Hashing and fingerprints | `MessageDigest` (SHA-256) in 49 main classes: the inputs digest, `ShardMap`, `ShardOwners`, `Digest` (net); `CRC32C` in `VarHotFile` | compute, small volumes | JDK SHA-256 (intrinsic with SHA extensions) and CRC32C (intrinsic) | already intrinsic; xxHash only for non-cryptographic hashing of large volumes (shard keys, row fingerprints) |
| Search and regex | `RegexSampler`, `RoleDetector`, `StructuredSearch` | compute, small | `java.util.regex` | not hot; no action |
| Net and gossip | `PeerHttp` (JDK `HttpClient`), `Gossiper` | latency, small messages | JDK TLS | JDK TLS uses AES-GCM intrinsics; no action unless a profile says otherwise |
| Arrow to the browser and the console | `ArrowIpc` (hand-written over `arrow-format`), `ArrowIpcWriter` | bandwidth, small | already Arrow IPC | Arrow Flight for large transfers to Python (section 7) |

---

## 5. Native libraries and their pure-Java rivals

Each candidate is judged against the best pure-Java option for the same place. Effort: S (days), M (one to two weeks), L (more). Gain
is expected, to be measured.

### 5.1 The table

| Candidate | Kind | Where | Bound it addresses | Expected gain and why | Effort | Risk | Licence | Platforms | Fallback | Measured by |
|---|---|---|---|---|---|---|---|---|---|---|
| **Primitive selection and sorts** (pure Java) | algorithm | `ExposureMath.sortedRow`, `order`, `statistics` | compute + allocation | PFE per row O(P) instead of O(P log P); no boxed `Integer[]`: large for PFE with incrementals (106 ms at P = 500 today) | S | low | own code | all | none needed | BENCHMARK 6: "contributions by netting set of the bank: PFE with incrementals" |
| **fastutil** (pure Java) | primitive collections | maps and lists of ids, ordinals and values in `VarViews`, `VarSelection`, `VarBook`, the BI engine | allocation | no boxing; less heap and GC | S | low | Apache-2.0 | all | `java.util` | JFR allocation profile; BENCHMARK 4.3 req/s |
| **RoaringBitmap** (pure Java) | compressed bitsets | scope membership (`OrdinalSet`, bitsets of scopes and views; +87 MB of `long[]` at two dates) | memory, bandwidth | smaller sets for sparse scopes, fast unions; plain bitsets stay for dense runs | M | low | Apache-2.0 | all | `long[]` bitsets | BENCHMARK 4.6 heap histogram |
| **FFM `MemorySegment` reads** (JDK) | memory access | `MappedMatrix`, `FileMaps`, `VarHotFile`, `ExposureHotFile` | bandwidth | no per-row copy into scratch; one mapping per matrix | S–M | low | JDK | all | the `ByteBuffer` path until removed | BENCHMARK 5.2 "whole bank from its rows"; 4.5 basket |
| **Vector API** (JDK incubator) | SIMD | `drishti-simd` kernels (section 2.3) | compute | 2 to 4 times on compute-bound loops (exposure, the heap tier); small on bandwidth-bound sums | M | medium (incubator) | JDK | all (x86 AVX2/AVX-512, aarch64 NEON/SVE) | the scalar twin | BENCHMARK 5.3, 6 |
| **BLAS** (OpenBLAS, bundled through JavaCPP: section 6.3) | dense linear algebra | many scopes at once (GEMM), exposure means and aggregation (GEMV), what-if batches | bandwidth (amortised) and compute | reading a date's rows once for K scopes instead of K times (section 6) | M–L | medium | OpenBLAS BSD-3-Clause; MKL Intel Simplified Software Licence (to verify for redistribution); Accelerate part of macOS | linux-x86_64 and windows-x86_64 (built) | the Java kernels | a batch benchmark: K scopes, one pass against K passes |
| **libdeflate** or **zlib-ng** | gzip | `GzipMembers`, `Archives` (the loaders' `.gz` input) | compute | 2 to 3 times decompression per thread; the loader's decompress stage is about a fifth of its CPU | M | medium (a JNI or FFM binding to keep) | libdeflate MIT; zlib-ng zlib licence | Linux, macOS; Windows only inside a jar | the JDK `Inflater` | BENCHMARK 7.2 "decompress" row |
| **zstd** (zstd-jni) | compression | Parquet pages (`NativeCodecs`), RocksDB | compute | already native | none | low | BSD-3-Clause | all, inside the jar | the pure-Java decoder (aircompressor) if a jar is refused | BENCHMARK 5.4 |
| **lz4** native | compression | `Lz4Pages` (`drishti.lz4.decoder: fast` exists) | compute | already chooseable | none | low | Apache-2.0 / BSD-2 | all | the safe Java decoder (default) | BENCHMARK 5.4 on LZ4 files |
| **simdjson-java** (pure Java, Vector API) | JSON | `RowParser` (one JSON line into a row) | compute | 2 to 3 times parse per thread on wide numeric rows (509 doubles a row); needs the Vector API flag | M | medium (young library; the document must still be re-written as `PyJson`) | Apache-2.0 | all | Jackson | BENCHMARK 7.2 "parse" row |
| **simdjson** native through FFM | JSON | the same | compute | similar to simdjson-java, plus a native binding to keep | L | higher | Apache-2.0 | Linux, macOS | Jackson | the same |
| **Arrow C Data Interface** | zero-copy columns | DuckDB results into `ArrowIpcWriter`; DataFusion scans (section 7) | allocation, bandwidth | no value-by-value `ResultSet` read; columns handed over as buffers | M | medium (`arrow-vector` and `arrow-c-data` add jars) | Apache-2.0 | all | JDBC `ResultSet` | BENCHMARK 9 (stores compared) for DuckDB queries |
| **DuckDB** (already a plugin) | embedded SQL | `DuckDbEngine`, `drishti-plugin-duckdb` | compute | already in place; gains come from Arrow export and query shape | S | low | MIT | all, inside the jar | n/a | BENCHMARK 9 |
| **io_uring** through FFM | asynchronous reads | the first open of a date from many files; reading a hot file from NVMe | I/O latency | deeper queues on NVMe; the page cache already serves warm reads | L | high (Linux only, kernel versions, security policies often disable it) | liburing MIT or LGPL | Linux only | `FileChannel` | BENCHMARK 5.2 "the first time from NVMe" |
| **xxHash** (zero-allocation-hashing, pure Java, or native) | non-cryptographic hash | shard keys and row fingerprints where SHA-256 is not required | compute | many times faster than SHA-256 where volume is large | S | low | Apache-2.0 (Java), BSD-2 (C) | all | SHA-256 | a micro-benchmark; only where a profile shows hashing |
| **CRC32C** (JDK intrinsic) | checksum | `VarHotFile` sections | compute | already an intrinsic | none | none | JDK | all | none | none |
| **netty-tcnative / BoringSSL**, or Tomcat's OpenSSL through FFM | TLS | the server's HTTPS, peer calls | compute | small: JDK TLS uses AES-GCM intrinsics | M | medium | Apache-2.0 + OpenSSL/ISC | Linux, macOS | JDK TLS | only if a profile shows TLS above a few percent |
| **jemalloc / mimalloc** (`LD_PRELOAD`) | native allocator | the server process (RocksDB, DuckDB, glibc arenas) | resident memory | lower and flatter RSS outside the heap (PERFORMANCE.md measures 660 to 735 MB that NMT does not see) | S (operations) | low | jemalloc BSD-2; mimalloc MIT | Linux | glibc malloc, `-XX:TrimNativeHeapInterval` (already used) | RSS over the soak (BENCHMARK 13) |
| **Transparent huge pages** for the heap and mapped files | memory | `-XX:+UseTransparentHugePages`; `madvise` on hot files | bandwidth (TLB misses) | a few percent on whole-bank sums | S | low | n/a | Linux | off | BENCHMARK 5.2 |

### 5.2 What is not worth it

- **SIMD on the VaR scope sum alone.** It is bandwidth-bound; SIMD saves CPU cycles (more users served at once) but not latency once the
  bus is full. It is worth doing as part of the Vector API kernels, not on its own.
- **Native TLS, native regex, native hashing** without a profile that shows them.
- **GPU** (CUDA through FFM). A date's rows would have to cross PCIe for each query; only a batch of thousands of scopes over the same
  resident matrix would pay. Parked until a customer asks for that workload.

---

## 6. BLAS

**Kept on the roadmap at the product owner's request.** BLAS is the right tool where one pass over a large matrix answers many questions.

### 6.1 Where it fits

| Use | Shape | Today | With BLAS |
|---|---|---|---|
| **Many VaR scopes at once** (a report of every desk and portfolio, a board pack, the RO1 explanation, a regulatory run, a batch of what-ifs) | scope indicators (K × P) × position rows (P × S) = scope vectors (K × S): **GEMM** | each scope sums its rows: K passes over the rows | one pass reads the rows once for all K scopes; the bandwidth cost is shared |
| **Exposure means** (EE per grid date over P paths) | E (G × P) × ones (P): **GEMV** | `ExposureMath.mean` row by row | one call; small gain, the matrices are small |
| **Exposure aggregation** (a counterparty's or the bank's sets added) | a sum of matrices: **AXPY** | `ExposureMath.addInto` | marginal; the Vector API is enough |
| **Component VaR for many parts** | parts' vectors (K × S) × tail weights (S): **GEMV** | `VarMath.component` per part | one call |
| **Statistical factor VaR** (designed, parked: principal components of a scope's scenario P&L) | covariance and eigen-decomposition: **SYRK, SYEVD** (LAPACK) | not built | the natural tool; pure Java alternatives are slow at S = 509 |

### 6.2 Precision and parity

Drishti sums float32 rows in **float64** accumulators ([VAR_GUIDE.md section 26](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file)).
Standard BLAS has no "float32 inputs, float64 accumulation" GEMM: `sgemm` accumulates in float32 (not acceptable for a bank's VaR),
`dgemm` needs float64 inputs (double the bytes, losing the bandwidth gain). The design therefore:

- converts a **tile** of rows (for example 4,096 positions) from float32 to float64 into a reused off-heap buffer and calls `dgemm` per
  tile, accumulating tiles in a fixed order; the conversion is cheap beside the read, and the rows are still read once for all K scopes;
- accepts that BLAS's internal summation order differs from Java's, and bounds the difference by the parity rule below (not "to the
  cent"); a sharded cluster stays bit-equal across members only if every member runs the same path, so the BLAS binding is
  part of the cluster's capability digest and members with different settings refuse to combine partial sums;
- keeps the heap tier's float64 vectors as direct `dgemm` input with no conversion.

**Why sums can differ at all.** Floating-point addition is not associative: `(a + b) + c` and `a + (b + c)` can differ in the last
bit. A scope's scenario total is a sum of up to millions of rows, so any change of order (SIMD lanes, BLAS's blocking, threads) can move
its last bits. In float64 (about 16 significant digits) the size of that difference is bounded:

| Summation | Error bound on a total, relative to the sum of the absolute rows (n rows) | n = 1,000,000 |
|---|---|---|
| one row after another (worst case) | about n × 1.1e-16 | 1.1e-10 |
| one row after another (typical, errors cancel) | about √n × 1.1e-16 | 1.1e-13 |
| pairwise or in tiles (what SIMD and BLAS do) | about log2(n) × 1.1e-16 | 2.2e-15 |
| compensated (Kahan–Neumaier) | about 2 × 1.1e-16, whatever n | 2.2e-16 |

For a scope whose rows add up to 100 billion in absolute value, the worst case of plain summation is about 11 currency units and the
typical case about a hundredth; tiles or compensation take it below a millionth. Millions of records therefore matter only for plain
one-by-one summation, and the accelerated paths sum in tiles, which is *more* accurate than one by one, not less. The inputs bound
precision too: the mapped hot file stores each row in float32 (about 7 significant digits); node aggregates are kept in float64 and
checked against the rows (VAR_GUIDE section 26).

**The parity rule** (product owner, 2026-11-01: "to the cent" is not enough for millions of records):

| Path | Rule |
|---|---|
| Baseline (today's code) | unchanged and bit-exact run to run |
| Vector API (SIMD) | **bit-exact** against its scalar twin: the twin uses the same tile and lane order, so both give identical bits; the baseline itself moves to that order once, measured and recorded |
| BLAS | each scenario total within a **relative 1e-12** of the baseline's (twelve significant digits; on a total of 100 million, a ten-thousandth of a cent), tiles folded in a fixed order; the **selected scenario** (the k-th worst, hence VaR's date and every component VaR) **identical**: built as the **near-tie rule** of `VarMath.order` (`NEAR_TIE` = 1e-12): scenario P&Ls within 1e-12 of the vector's largest absolute P&L of each other (chained) are ordered by scenario date, the older first (the lower index: the scenario set's dates ascend), on every path; ES, VaR and components within the same relative bound (`NearTieTest`: the exact order flips between the two paths, the selected scenario does not) |
| Any breach | the start-up canary or the parity suite drops the path back to the baseline, logs it and shows it in Admin → Health → Native |

The selected scenario matters more than the digits: VaR as a value moves by at most the difference of the totals, but if two scenarios
are nearly tied and the order flips, the *date* of VaR and every position's component VaR would follow the other scenario. The tie rule
and the identical-scenario check prevent that.

### 6.3 How it is bound and shipped (as built)

- **One interface**, `com.ash.drishti.compute.linalg.LinearAlgebra` (`dgemm`, `dgemv`, `ddot`, `daxpy` on `double[]`, column-major as in
  Fortran BLAS), with two implementations: `JavaBlas` (pure Java, the reference loop orders, so a loop written the same way gives the same
  bits) and `NativeBlas` (OpenBLAS). Callers see only the interface; the binding is chosen once at start (`Blas`), and an ArchUnit rule
  keeps every class but `NativeBlas` away from the binding library.
- **Bundled, on Java 21**: JavaCPP's OpenBLAS preset (`org.bytedeco:openblas` 0.3.26-1.5.10 and `org.bytedeco:javacpp` 1.5.10, the
  `linux-x86_64` and `windows-x86_64` builds only, never `openblas-platform`), about 44 MB of jars. No system package, no Foreign Function
  API (that waits for a Java 25 decision). JavaCPP unpacks the library into `drishti.native.cache-dir` (default `<data>/native-cache`, set
  as `org.bytedeco.javacpp.cachedir`), never a hidden folder in a home directory.
- **Platforms**: the architecture is checked before anything is loaded: a 32-bit JVM, ARM (Linux, Windows or Apple silicon) and any
  system other than Linux and Windows on x86-64 go to Java at once, the reason naming the platform (`no bundled OpenBLAS for macosx-aarch64`).
- **Threads**: `openblas_set_num_threads(1)` once loaded; Drishti's sum pool parallelises over chunks of rows, so BLAS never
  oversubscribes the cores beside the request threads.
- **Modes** (`drishti.native.blas.mode`): `auto` (default: OpenBLAS where it fits, else Java with the warning of OPERATIONS.md section
  2.1: a WARN banner, Health DEGRADED, a notice for administrators, the alert `native-blas-fallback`, the metric
  `drishti_native_blas{path,reason}`); `java` (chosen: nothing native is loaded, nothing warns). There is no mode that refuses to start:
  nothing about BLAS can stop the server (the product owner, 2026-11-03).
- **Where it is used**: many VaR scopes at once (`MultiSums`, behind `VarBook.vectors`: a cube's cells, ad-hoc baskets, many portfolios).
  The sets no aggregate helps are summed in one pass: on Java, cache-sized tiles added into every set, **the same bits** as summing each set
  alone (`ParallelSums`' fixed order: blocks aligned to the ordinal, folded in block order); on OpenBLAS a 1,024-row tile feeding at least
  32 sets at a density of at least a quarter is one `dgemm` (rows times the sets' 0/1 indicator). Sets the hierarchy's aggregates answer
  keep their plan.

### 6.4 Measured (2026-11-03)

`ManyScopeBenchmark` (drishti-benchmarks; [BENCHMARK.md section 17](../admin/BENCHMARK.md#17-many-var-scopes-at-once-blas)): float32
hot-file rows, memory-mapped, 509 scenarios, 8 threads, best of 2. At **1M positions**:

| Shape | K sets | One set at a time | MultiSums, Java | MultiSums, OpenBLAS |
|---|---|---|---|---|
| cube cells (each row in one cell) | 100 | 291 ms | 125 ms (2.3x) | 121 ms (2.4x) |
| overlapping portfolios (each row in half the sets) | 100 | 3,210 ms | 2,645 ms (1.2x) | **1,028 ms (3.1x)** |
| desks (contiguous) | 100 | 110 ms | 33 ms (3.3x) | 35 ms (3.2x) |
| cube cells | 1,000 | 325 ms | 313 ms | 324 ms |

Parity: the Java path equals one set at a time **to the bit** (checked in the benchmark and in `MultiSumsTest`, `ManyScopeVectorsTest`
on heap, mapped float32 and grouped rows); OpenBLAS differs by at most 4.5e-16 relative to a vector's largest figure (bound 1e-12), with
the same VaR scenario. The `dgemm` thresholds (32 sets a tile, density a quarter) were **tuned on one machine** and are settings:
`drishti.native.blas.gemm-min-sets` and `drishti.native.blas.gemm-min-density`.

**Not kept** (measured, slower than the Java loops): the FRTB bucket sum K_b as a matrix-vector product (a bucket of 1,000 to 3,000
factors: 2 to 4 times slower, because building the scenario's correlation matrix costs what the fused loop costs), and exposure's means
and set additions (G x P up to 200 x 10,000: the same or slower; bandwidth-bound, and a native call copies its arrays). They stay on
the Java kernels. FRTB for many portfolios at once (correlations times K portfolios' sensitivities, a GEMM) would pay, but nothing
asks for it yet.

### 6.5 Resilience (as built)

The native library never runs unguarded (`GuardedBlas`, what `Blas.get()` hands out on OpenBLAS):

| Guard | What it does |
|---|---|
| Platform check | 32-bit, ARM, other systems: Java before anything is loaded |
| Start-up self-test | products of awkward shapes (1 x 1, tall and thin, wide, sizes not multiples of 8, both transposes, medium) against Java at 1e-12; a failure: Java with the warning |
| Argument checks | dimensions, leading dimensions, offsets and array lengths checked in Java, in `long` (no overflow), before every native call: a bad argument is an `IllegalArgumentException` and never reaches native code, where a fault would end the JVM |
| Size bound | a call whose operands pass `drishti.native.blas.max-call-mb` runs on Java; JavaCPP's own allocations bounded by `drishti.native.max-bytes-mb` |
| Per-call fallback | any throwable from a native call (a link error, an exception, an out-of-memory) is redone on Java from the caller's original output |
| Divergence check | NaN or infinity in a native result from finite inputs: redone on Java |
| Circuit breaker | either of the two above switches OpenBLAS off for the rest of the run: the banner, the alert, Health DEGRADED |
| Canary | the self-test again every `drishti.native.blas.canary-interval` (6 h); a failure trips the breaker |

A fault inside the native code itself (a crash, not an exception) cannot be caught by any JVM; the argument checks are there so that
Drishti never hands OpenBLAS an argument that could cause one.

### 6.6 One path for the cluster (as built)

Members never refuse each other over BLAS. Each publishes its path in its capability digest (service `native-blas`: `native 0.3.26`,
`java-fallback <reason>`, `java <reason>`). When **any** member runs on Java (or is an older server without the service, which sums in
Java), every member sums many scopes (`MultiSums`, through `Blas.forClusterSums()`) on Java, so the figures members combine or compare
are the same bits; the log says it in a WARN banner naming the members, Admin → Health shows each member's path and the cluster's
(`clusterPath`), and the agreement lifts by itself when every member is native again. Shard sums (`VAR_SHARDING.md`) never use BLAS:
they are `ParallelSums`' fixed-order Java sums on every member.

---

## 7. Embedded columnar analytics engines

The aim: Drishti as a **low-latency analytics platform**, where an ad-hoc question over millions of rows (a pivot, a breakdown, a Data
view filter, a BI visual) answers in tens of milliseconds. Drishti already speaks **Parquet** (its Delta and Iceberg lakes) and **Arrow**
(BI answers as Arrow IPC through `ArrowIpcWriter`; the Python console and tools use `pyarrow`), so the engines below are judged by how
well they plug into those two formats.

### 7.1 Where an engine would sit

| Place | What it means | Crash isolation | Latency cost |
|---|---|---|---|
| **In the JVM** (JNI or FFM) | the engine runs in the server's process; results handed over as Arrow buffers (C Data Interface) | none: a crash takes the server | none |
| **In the Python console or tools** | the engine is a Python wheel in the console's or the tools' environment | the console process | none within Python |
| **A sidecar process** over Arrow Flight or Arrow IPC on a local socket | its own process, started and watched by the server | full | one local hop (tens to hundreds of µs plus the bytes) |

### 7.2 The candidates

| Engine | What it is | Fits Drishti where | Placement | Expected latency gain and why | Effort | Risk | Licence | Platforms (Windows QA) | Data-format fit | Measured by |
|---|---|---|---|---|---|---|---|---|---|---|
| **DuckDB** | C++ embedded OLAP SQL, vectorised, parallel | already the BI engine (`DuckDbEngine`) and a connector plugin; pivots, the Data view, BI visuals, the stores benchmark | in the JVM (JDBC, native code inside the jar), as today | today's answers are bounded by the **JDBC row-by-row read** into Arrow columns; Arrow export removes it; reading Delta and Iceberg directly (its `delta` and `iceberg` extensions) removes a copy into staged tables | S–M | low (in use) | MIT | all, inside the jar (as today) | Parquet and Arrow native; Delta and Iceberg through extensions (vendored, since autoload is off) | BENCHMARK 9; BI query p95 |
| **Apache DataFusion** | Rust, Arrow-native SQL and DataFrame engine, extensible | an alternative to DuckDB where Arrow-native extension (custom functions over Drishti's vectors) matters | in the JVM through a Java binding, or a sidecar | similar to DuckDB on scans and aggregates; its strength is embedding custom operators, not raw speed | L | **high**: the Java binding (`datafusion-java`, a community project) is young, thinly maintained and lags DataFusion's releases (maturity to verify); otherwise a JNI or FFM shim of our own | Apache-2.0 | Linux, macOS; Windows only with a native library | Arrow in memory, Parquet native; Delta through delta-rs, Iceberg through iceberg-rust | the same queries as DuckDB, side by side |
| **Polars** | Rust DataFrame library with a lazy, multi-threaded query engine | the **Python** side: tools, the Python loader path, Python compute packs, the console's data handling | Python, in-process (first-class bindings) | multi-threaded JSON Lines and Parquet reading where the tools are single-threaded Python today; lazy plans push filters into the scan | S–M in Python | low in Python | MIT | Python wheel with native code: **a question for Windows QA** (section 11) | Arrow memory model (zero-copy to and from `pyarrow`), Parquet, Delta through delta-rs | BENCHMARK 7.1 Python path; tool run times |
| Polars **in the JVM** | the same engine reached from Java | the server | **no official Java binding**: a JNI or FFM shim over Rust (our own crate exporting an Arrow C stream), or a sidecar | none over DuckDB in the server; a second engine to maintain | L | high | MIT | needs a native library per platform | Arrow C Data | not proposed |
| **Velox** | Meta's C++ vectorised execution library (used by Presto and, via Gluten, by Spark) | none directly: it is an execution library without a SQL front end of its own | would need a JNI layer (Gluten's is Spark-specific) | comparable to DuckDB's engine; no gain for Drishti's embedded use | L+ | high (large C++ build, no standalone embedding story) | Apache-2.0 | Linux mainly | Arrow-like vectors, Parquet | not proposed |
| **chDB** | ClickHouse as an embedded library | very large, wide aggregations; ClickHouse's SQL dialect | Python in-process (first-class); Java through a community binding (to verify) or a sidecar | strong on huge scans; overlaps DuckDB in Drishti's sizes | M | medium–high (large library, few embedding users from Java) | Apache-2.0 | Linux, macOS; Windows: to verify | Parquet, Arrow output | not proposed unless a workload outgrows DuckDB |
| **Arrow Flight / Flight SQL** | gRPC protocol for Arrow record batches | moving large answers between the Java server and the Python console or a sidecar | Java (`flight-core`) and Python (`pyarrow.flight`) | removes JSON encoding for large tables: Arrow batches are sent as they are; the console's large exports and the compute packs' inputs | M | medium (gRPC and Netty add jars; one more port to secure) | Apache-2.0 | all (pure Java plus `pyarrow`) | Arrow by definition | time and bytes of a 1M-row answer from server to console, JSON against Flight |
| **Arrow Acero** | Arrow C++'s streaming execution engine | Python tools that already hold `pyarrow` tables | Python (`pyarrow.acero`) | small filters and joins without a second engine | S | low | Apache-2.0 | as `pyarrow` (already used) | Arrow | tool run times |
| **delta-rs** (`deltalake`) and **PyIceberg / iceberg-rust** | Rust (and Python) readers and writers of Delta and Iceberg | the Python loader path and lake tools (`tools/lake/`) | Python, in-process | `deltalake` is **already** a dependency of the tools; the gap to the Java loader is the single-threaded staging before it (section 7.3) | S–M | low | Apache-2.0 | Python wheels with native code (already used) | Delta, Iceberg, Parquet, Arrow | BENCHMARK 7.1 |
| **Lance** | Rust columnar format for random access and vectors | the VaR hot file's role (random access to rows) and AI embeddings | Python first-class; Java binding exists (maturity to verify) | fast random row reads; the hot file already gives that for VaR | M | medium | Apache-2.0 | Linux, macOS; Windows to verify | its own format, Arrow in memory | not proposed for VaR; a candidate for AI embeddings only |

### 7.3 The Python loader path and Polars

The Python path stages each JSON line in Python (`tools/lake/stage.py`, `jsonstream.py`) before a writer (Drishti's Java writer or
`deltalake`) commits the table; that staging is one Python thread, hence the 9 times gap. Two routes close it:

1. **Where a JVM exists** (the server's machine, the drill, AWS loaders): make `--engine java` (the parallel loader,
   [PARALLEL_LOADER.md](PARALLEL_LOADER.md)) the default for large inputs; the knob exists (`--java-above-mb`). No new dependency.
2. **Where no JVM exists** (an analyst's Python environment): a Polars staging path. `pl.scan_ndjson` reads JSON Lines on every core into
   Arrow, the reject rules of `stage.py` become Polars expressions, and `deltalake.write_deltalake` commits the Arrow table. The rules must
   give exactly the same rows and rejects as `stage.py` (a parity test over the test data). Expected: within about 2 times of the Java
   loader on the same machine.

### 7.4 Recommendation

| Layer | Choice | Why |
|---|---|---|
| In-memory and wire format | **Arrow everywhere**: Arrow IPC to the browser (as today), Arrow C Data inside the JVM between engines, Arrow Flight between the server and the Python console for large answers | one format, no re-encoding; every engine above reads and writes it |
| Storage format | **Parquet in Delta or Iceberg** (unchanged) | every engine reads it |
| VaR and exposure | **the Java engines, unchanged in role** (with the Java 25 and BLAS work above) | they hold bit-level guarantees, sharding and the hot-file design no general engine has |
| Ad-hoc analytics and BI in the server | **DuckDB**, kept as the one embedded engine, with Arrow export and direct Delta and Iceberg scans | already in place, MIT, ships inside its jar on every platform (Windows included), mature, fastest to gain; DataFusion's Java binding is not mature enough, Velox has no embedding story, chDB overlaps it |
| Python tools, loaders and compute packs | **Polars** (with `deltalake` and `pyarrow`, already present) | first-class in Python, multi-threaded, Arrow-native, MIT |
| Isolation for anything new and native | **a sidecar over Arrow Flight** | a crash or a leak costs the sidecar, not the server |

DataFusion stays a watched alternative: if its Java binding matures, or a need for custom Arrow-native operators appears, it is evaluated
against DuckDB on the stores benchmark.

---

## 8. The roadmap in phases

Prerequisite for every phase: **Java 25 as the only runtime** (section 2.7). Each phase has a measurement that decides go or no-go,
taken on the benchmark machine with the full method of [BENCHMARK.md](../admin/BENCHMARK.md), native on and off.

| Phase | Items | Prerequisites | Go / no-go measurement | Benchmark rows it should move |
|---|---|---|---|---|
| **P0: the memory model** | 0.1 to 0.4 (section 3): mapped dates budgeted against disk and RAM, refusals remembered, the heap formula for the heap only | none | a 1M-position date opens at `-Xmx6g`; one lake read per refused date; heap within the formula | BENCHMARK 11, 12.2; a new 1M row |
| **P1: Java 25 and pure-Java wins** | Java 25 baseline (build, drill, scripts, Docker, docs); compact headers everywhere; `--enable-native-access`; `FileMaps` and `MappedMatrix` on `MemorySegment` (no copy, no 2 GB pieces); selection for PFE and a primitive `order` in `ExposureMath`; fastutil where JFR shows boxing; scoped values for request context; the AOT cache for start-up | P0 | each change: no regression on any row; the PFE rows at least 2 times faster; start to healthy at least 30% faster with the AOT cache | BENCHMARK 4.2 start, 4.3 req/s, 4.5, 5.2, 6 PFE rows |
| **P2: the Vector API and compiled evaluators** | `drishti-simd` with scalar twins (section 2.3), behind `drishti.native.vector.enabled`; exposure's passes and the heap tier's sums; BI row evaluation compiled with the Class-File API | P1 | Vector kernels at least 1.5 times faster on the heap tier and exposure, no change on the mapped whole-bank sum (bandwidth); compiled evaluation at least 3 times faster on a filter over 1M rows | BENCHMARK 5.3, 6; a BI filter row |
| **P3: many scopes at once, and BLAS** | the Java multi-scope kernel (one pass, K scopes); BLAS through FFM behind `drishti.native.blas.enabled` (section 6); used by reports, board packs, regulatory runs and what-if batches | P1 (FFM), P2 helps | K = 100 scopes in under 3 times the time of one; BLAS kept only if 1.5 times faster than the Java kernel at K ≥ 100 | a new multi-scope row in BENCHMARK 5 |
| **P4: loaders and BI data paths** | libdeflate or zlib-ng behind `drishti.native.gzip`; simdjson-java behind `drishti.native.json`; DuckDB Arrow export (C Data Interface) into BI answers; DuckDB direct Delta and Iceberg scans; jemalloc as an operations option | P1; P2 for simdjson-java | each stage at least 1.5 times faster per thread, rows and table bit-identical; BI p95 lower on BENCHMARK 9 | BENCHMARK 7.2 stages, 7.1, 9 |
| **P5: the Python side and transport** | the Java loader as the default for large inputs; the Polars staging path with parity to `stage.py`; Arrow Flight between the server and the console for large answers; Polars for Python compute packs | none (Python); P1 for Flight | the Python path within 2 times of the Java loader; a 1M-row answer to the console at least 3 times faster than JSON | BENCHMARK 7.1; a new console transfer row |

Not scheduled: io_uring, native simdjson, DataFusion, chDB, Velox, Lance, GPU, native TLS. Each needs a profile that points at it first.

---

## 9. Testing and operations

### 9.1 Parity: native on against native off

Every native or SIMD path has a **twin test** that runs the same inputs through both paths:

| Path | Parity rule |
|---|---|
| FFM reads, selection algorithms, fastutil, scoped values (pure Java changes) | **bit-equal** to before: the existing golden answers (`VarParallelReadTest`, the playground's fixtures, the exposure tests) must not move |
| Vector API kernels that keep the scalar summation order | bit-equal |
| Vector API kernels with lane-wise order | **bit-exact** against the scalar twin that keeps the same order (section 6.2) |
| BLAS | every scenario total, VaR, ES, component and exposure figure within a **relative 1e-12** of the pure-Java answer, and the **same selected scenario**, on the test data and on a 200,000-position synthetic date; the largest difference reported in the test's output (section 6.2) |
| libdeflate, simdjson-java, Arrow export, Polars staging | **identical output**: the same rows, the same rejects with the same reasons, the same table bytes where the writer is the same |

The twin test **skips** (JUnit `Assumptions`, pytest `skipif`) when the native library cannot be loaded, naming what is missing; the
pure-Java half always runs. On Windows QA the native halves skip.

### 9.2 The drill

- The drill runs the full suite **twice for native paths**: once with every `drishti.native.*` off (the default, and the only run on
  Windows), once on Linux with each available library on (a profile of settings, `-Ddrishti.native.profile=all`, proposed).
- The benchmark records which native libraries were loaded beside every row, so a figure is never compared across different settings
  by mistake.

### 9.3 Settings

The BLAS settings are built (section 6.3; [CONFIGURATION.md](../admin/CONFIGURATION.md)); the others are proposed. The pure-Java accelerations follow `drishti.native.mode`.

| Setting | Default | Meaning |
|---|---|---|
| `drishti.native.mode` | `auto` | `auto`: on a Java 25 build use every accelerated implementation whose needs are met (section 2.8); `off`: today's code everywhere; `on`: fail the start if an enabled one cannot load. A Java 21 build has only today's code |
| `drishti.native.enabled` | `false` | the native libraries (BLAS, libdeflate, zstd) as a group; `auto` still uses the pure-Java accelerations (`MemorySegment`, Vector API) when this is off |
| `drishti.native.threads` | 0 (the cores) | the platform threads that run native calls (section 2.6) |
| `drishti.native.vector.enabled` | `false` | the Vector API kernels (also needs `--add-modules jdk.incubator.vector`) |
| `drishti.native.blas.mode` | `auto` | **built** (section 6.3): `auto` the bundled OpenBLAS where it fits, else Java with the warning; `java` Java, chosen |
| `drishti.native.cache-dir` | `<data>/native-cache` | **built**: where the bundled libraries are unpacked |
| `drishti.native.max-bytes-mb` | 1024 | **built**: JavaCPP's own off-heap allocations |
| `drishti.native.blas.max-call-mb` | 256 | **built**: a larger call runs on Java (section 6.5) |
| `drishti.native.blas.canary-interval` | `6h` | **built**: the self-test repeated (section 6.5) |
| `drishti.native.gzip` | `jdk` | `jdk`, `libdeflate` or `zlib-ng` |
| `drishti.native.json` | `jackson` | `jackson` or `simdjson` |
| `drishti.native.madvise.enabled` | `false` | read-ahead advice on hot files (Linux) |
| `drishti.native.canary` | `true` | run each enabled library's start-up check against the pure-Java answer |
| `drishti.native.<library>.max-memory-mb` | per library | native memory a library may take, where it can be bounded |
| `drishti.var.refusal-ttl-seconds` | 300 | how long a refused date is remembered (section 3) |

Every setting is listed in [CONFIGURATION.md](../admin/CONFIGURATION.md) when built, and shown in the UI's configuration help.

### 9.4 The admin view

**Admin → Health → Native** (proposed) lists, for this server and each cluster member:

| Column | Example |
|---|---|
| Library | OpenBLAS |
| Requested | `auto` (`drishti.native.blas.mode`) |
| Loaded | yes: `/usr/lib/x86_64-linux-gnu/libopenblas.so.0`, version 0.3.26, SHA-256 `9f2…` |
| Canary | passed (difference 0.0000 against Java on the 64-position check) |
| In use | 1,204 calls in the last hour, 3.1 s total |
| Fallback | none; or "off: the canary failed (…)", "off: not found", "off: Windows QA policy" |

Also listed: the Java runtime (25, collector, compact headers on or off, the AOT cache used or not), the Vector API module present or
not, and the libraries native code unpacked from jars (zstd-jni, snappy-java, RocksDB, DuckDB) with their versions. The same list is
in `GET /status` and in the capability digest the cluster gossips.

### 9.5 Failure and fallback

| Failure | What happens |
|---|---|
| The library is not found or fails to load | the setting is off for this run; Health shows why; one warning in the log; the pure-Java path serves |
| The canary disagrees with Java | the same, with the difference shown |
| A native call throws | the call is retried once on the pure-Java path; after three failures in an hour the library is switched off for the run and an alert fires (the platform alerts) |
| The process crashes in native code | the JVM's `hs_err` file names the library; on restart the server reads a marker it wrote before the call and starts with that library off until an administrator clears it |
| Members of a cluster differ | sums that must be bit-equal across members refuse to combine (section 6.2) and Health names the member |

---

## 10. Ranked opportunities

Ranked by expected gain over effort. Gains are estimates to be measured in the phase named.

| # | Opportunity | Where | Bound | Effort | Phase |
|---|---|---|---|---|---|
| 1 | Budget mapped dates against disk and RAM, remember refusals | `RowSpill`, `VarLoader`, `VarTier`, `VarProperties`, `ExposureLoader` | capacity (dates refused that fit; repeated lake reads) | S | P0 |
| 2 | Selection instead of a full sort for PFE; a primitive index order | `ExposureMath.sortedRow`, `statistics`, `order` | compute + allocation | S | P1 |
| 3 | `MemorySegment` reads of the hot files: no copy, no 2 GB pieces, no reflection | `FileMaps`, `MappedMatrix`, `VarHotFile`, `ExposureHotFile` | bandwidth | S–M | P1 |
| 4 | Java 25 everywhere with compact headers and the AOT cache | build, `tools/drill.sh`, start scripts, Docker | heap, start-up | S–M | P1 |
| 5 | Many scopes in one pass (Java kernel), then BLAS GEMM | `ParallelSums`, `SetSums`, `VarBook`, reports and regulatory runs | bandwidth (amortised) | M (Java), M–L (BLAS) | P3 |
| 6 | DuckDB Arrow export and direct lake scans for BI | `DuckDbEngine.read`, `ArrowIpcWriter`, `QueryService` | allocation | M | P4 |
| 7 | The Java loader as default for large inputs; Polars staging where no JVM | `tools/lake/build.py`, `stage.py` | single-threaded Python | S (default), M (Polars) | P5 |
| 8 | Vector API kernels for exposure and the heap tier | `ExposureMath`, `MappedMatrix`, `VarMath` (new `drishti-simd`) | compute | M | P2 |

Next in line: libdeflate for `.gz` input (P4), simdjson-java for parsing (P4), compiled BI evaluators (P2), Arrow Flight to the console
(P5).

---

## 11. Decisions of the product owner (2026-11-01)

1. **Native as far as possible.** Python packages with native code (Polars and the like) are accepted, on the Windows QA machine too;
   the direction is "as native as possible", always with the pure-Java or pure-Python fallback of section 1.3.
2. **OpenBLAS only.** OpenBLAS (BSD) is the one BLAS named, built and tested, so Drishti is not tied to Intel processors; Intel MKL is
   left for later. Apple Accelerate stays a macOS option to evaluate.
3. **Parity.** Not "to the cent": the rule of section 6.2 (bit-exact for SIMD; a relative 1e-12 and the identical scenario for BLAS).
4. **Java 21.** To be retired by **2026-12-25** at the latest, possibly as early as about 2026-11-11 (not final). Until then a Java 21
   build runs today's code and a Java 25 build the accelerated paths (section 2.8), which is how each step's gain is measured.

### 11.1 Decisions of the product owner (2026-11-03)

1. **BLAS on Java 21 now**, bundled: JavaCPP's `org.bytedeco:openblas` 0.3.26-1.5.10, `linux-x86_64` and `windows-x86_64` only (the
   Windows QA machine accepts DLLs Java unpacks). Not the Foreign Function API yet; not a system OpenBLAS.
2. **Never fail to start** because of BLAS: `auto` falls back to Java loudly (banner, DEGRADED, notice, alert, metric); `java` is the
   chosen, quiet Java path; no refusing mode.
3. **Resilience**: self-test, argument checks before native calls, per-call fallback, divergence check, circuit breaker, scheduled
   canary, bounded native memory (section 6.5).
