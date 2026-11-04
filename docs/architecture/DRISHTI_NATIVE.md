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

# drishti-native: native libraries behind Drishti's own abstractions, with Java fallbacks

**Status: design for 2.9.12 (product owner, 2026-11-04); QuantLib parked (section 6).** One module holds every native integration of the server, each behind a
Drishti interface with a pure-Java implementation that always works, so **Drishti runs on any environment**. It realises the seam of
[HIGH_PERFORMANCE_NATIVE.md](HIGH_PERFORMANCE_NATIVE.md) section 2.8 on Java 21 (JNI through JavaCPP) and keeps room for the Java 25
paths of that design (FFM, the Vector API) as further implementations of the same interfaces.

## 1. The rule

*Product owner, 2026-11-04: "Key is we maintain abstractions and have fallbacks so we can run on any environment."*

1. **Every native capability sits behind a Drishti interface.** Callers see only the interface; only `drishti-native` imports a native
   binding (an architecture test enforces it).
2. **Every interface has a pure-Java implementation that always works.** Native code is an optimisation chosen at run time, never a
   requirement.
3. **Automatic fallback.** A native library that is missing, built for another platform, fails its self-test, or misbehaves later is
   replaced by the Java implementation: a loud warning in the log, **DEGRADED** in Health, a banner in Admin; the server always starts.
4. **The person decides.** `drishti.native.mode` (`auto`, `off`, `on`) for everything, and a switch per capability, in configuration and
   live in Admin → Engines.
5. **Both paths are proven equal.** Tests run every capability native and Java and compare (exactly, or within a stated tolerance:
   section 8); a canary repeats a fixed check while the server runs.
6. **The drill proves it.** One drill lane runs with every native capability off.
7. **One version of everything.** No two versions of a library, no duplicate classes, one place that lists every native dependency
   (section 7).

## 2. What it holds

| Capability | Interface (planned names) | Native | Java | Today |
|---|---|---|---|---|
| **Linear algebra, level 1–3** | `LinearAlgebra` (`dgemm`, `dgemv`, `ddot`, `daxpy`) | OpenBLAS (bytedeco) | Drishti's kernels (`JavaBlas`, `MultiSums`) | built in 2.9.11.c in `drishti-compute` (`compute.linalg`) and `drishti-server` (`server.nativelib`); **moved** here |
| **Decompositions and solvers** | `Lapack` (Cholesky, LU, QR, symmetric eigen, SVD, least squares, solve) | LAPACK from the **same** OpenBLAS jar | Hipparchus | new |
| **Statistics and optimisation** | `Stats`, `Optimise`, `Interpolate`, `Distributions` | none needed | Hipparchus (pure Java; SciPy's role) | new |
| **Compression** | `Codecs` (zstd, Snappy, LZ4, gzip/deflate) | zstd-jni, snappy-java, lz4-java | aircompressor (pure Java, same formats) | natives used in five modules without a fallback |
| **Key-value disk store** | `DiskStore` (the disk cache: get, put, remove, scan in key order) | RocksDB | **H2's MVStore** (pure Java, on disk, keys in order) | RocksDB in `drishti-diskcache`, no fallback |
| **Embedded SQL engine** | `SqlEngine` (open a database or an in-memory engine, run a statement with its memory, thread and time limits, answer in Arrow) | DuckDB | **none, by decision** (section 2.1) | DuckDB used directly in `drishti-server` (BI engine, SQL service), `drishti-bicompute` (the pivot executor) and `plugin-duckdb` |

### 2.1 DuckDB: required, without a fallback

*Product owner, 2026-11-04: "For DuckDB we should not have a fallback: either we have it or we don't."* DuckDB is a **required**
component, like the JVM itself. Its binding moves into `drishti-native` behind `SqlEngine` (the only code that imports `org.duckdb`), so
there is one place that loads it, one version, one memory and thread budget, and one status in Health and Admin; the BI engine, the SQL
service, the pivot executor and the DuckDB connector call the interface. There is no second SQL engine: if DuckDB cannot load (a platform
it does not ship for), the server says so at start in plain words (what failed, the platforms supported) and does not start. The
self-test, the version check and the status follow section 4; the breaker does not switch to Java, it fails the query in words.

### 2.2 SQLite: required, without a fallback

*Product owner, 2026-11-04.* SQLite, the default database of a single server, is required in the same way as DuckDB: there is no
pure-Java SQLite, and a third schema (H2) is not added (the two complete schema files stay SQLite and PostgreSQL). Its driver is
registered with `drishti-native` for status and version; if it cannot load, the server says so at start in plain words (what failed;
use PostgreSQL, or a supported platform) and does not start.

## 3. The module

```
drishti-native/
  com.ash.drishti.nativ.core      Capability, Registry, Guard (input checks, breaker), Canary, NativeMode, the status record
  com.ash.drishti.nativ.linalg    LinearAlgebra, Lapack; JavaBlas, MultiSums, HipparchusLapack; OpenBlas, OpenLapack (JavaCPP)
  com.ash.drishti.nativ.maths     Stats, Optimise, Interpolate, Distributions (Hipparchus)
  com.ash.drishti.nativ.codec     Codecs; Zstd/Snappy/Lz4 natives; aircompressor
  com.ash.drishti.nativ.store     DiskStore; RocksDbStore; MvStore (H2 MVStore)
  com.ash.drishti.nativ.sql       SqlEngine; DuckDbEngine (required, no fallback: section 2.1)
```

- **Who depends on it:** `drishti-compute` (VaR and exposure kernels), `drishti-deltalake`, `drishti-lakeio`, `drishti-lakeload`,
  `drishti-dataload` (codecs), `drishti-diskcache` (the store), the server (Health, Admin, settings). `drishti-native` depends on
  `drishti-common` only.
- **Spring-free.** The module is plain Java (the loaders run without Spring); the server wires it from `drishti.native.*` settings.
- **The Java 25 paths** of HIGH_PERFORMANCE_NATIVE.md (FFM over the hot files, Vector API kernels, FFM bindings) become further
  implementations of these same interfaces, inside `drishti-native`: its Java 25 sources are compiled only by a Maven profile active on
  JDK 25 and found by `ServiceLoader`, as that design says. The module that design proposed, `drishti-accel`, is not made:
  `drishti-native` is the one home of every native and accelerated implementation. No second seam.

## 4. The capability contract

Every capability is the same shape:

| Part | What |
|---|---|
| **Interface** | the operations, in Drishti's types (Java arrays and buffers; never a native type) |
| **Java implementation** | always present, always correct; the reference for parity |
| **Native implementation** | loaded lazily on first use, once; declares its needs (OS and architecture, the library file, a Java version) |
| **Self-test** | at start, a fixed problem solved both ways; a mismatch keeps Java |
| **Guard** | checks each call's inputs before they reach native code (sizes, NaN/Inf where they are not allowed, array bounds), so a bad input is a Java exception, never a crash |
| **Breaker** | a native call that throws or returns a non-finite result where the Java answer is finite trips the capability to Java for the rest of the run, with a warning |
| **Canary** | every 6 hours (setting), the fixed problem again; a mismatch trips the breaker |
| **Switch** | `drishti.native.<capability>.mode`: `auto` (default), `java`, `native` (refuse to start if native cannot load: for a test bench) |
| **Status** | Health (`native.<capability>`: UP, or DEGRADED with why), Admin → Engines, `/api/v1/admin/native`, the start log, metrics (`drishti_native{capability, impl}` and call counts) |

The global `drishti.native.mode` (`auto`, `off`, `on`) applies to every capability; `off` is the drill's pure-Java lane.

## 5. Linear algebra and maths

### 5.1 What moves from 2.9.11.c

`compute.linalg` (`LinearAlgebra`, `JavaBlas`, `NativeBlas`, `GuardedBlas`, `Blas`, `Canary`, `BlasProbe`) and `server.nativelib`
(`NativeLibraries`, `OpenBlasEngine`) move into `drishti-native`, unchanged in behaviour (the near-tie rule, the cluster's one BLAS path,
`gemm-min-sets`, `gemm-min-density`, the live Admin switch). The settings keep their names (`drishti.native.blas.*`). One commit moves
them; nothing stays behind.

### 5.2 LAPACK

The bytedeco `openblas` artifact already carries LAPACK and LAPACKE for Linux and Windows: **no new dependency**. Wrapped for what the
roadmap needs:

| Operation | First use |
|---|---|
| Cholesky (`dpotrf`) | correlated Monte Carlo paths in the exposure engine (2.9.14); a positive-definiteness check of a correlation matrix |
| Symmetric eigen (`dsyevd`) | PCA of the scenario P&L matrix (which factors drive VaR); repairing a non-PSD correlation matrix |
| SVD (`dgesdd`) | low-rank views of scenario matrices |
| Least squares (`dgels`), solve (`dgesv`) | hedge ratios, curve fitting |

The Java side is Hipparchus (`CholeskyDecomposition`, `EigenDecomposition`, `SingularValueDecomposition`, `QRDecomposition`).

### 5.3 Hipparchus

The one maths library of the server (Apache Commons Math's maintained successor, Apache 2.0, pure Java): distributions (normal,
Student-t, chi-squared, with quantiles), descriptive statistics, fitting, root finding, optimisation, interpolation. It replaces any
hand-written special function where one exists (for example the parametric VaR's normal quantile), each replacement proved equal in a
test. Rachana's statistical functions (FUNCTION_CATALOGUE.md) call it through `drishti-native`, never directly.

## 6. QuantLib (parked)

*Product owner, 2026-11-04: "Let us keep QuantLib out for now. We will revisit this later."* Not part of 2.9.12: it gets a release of its own, not yet scheduled (product owner, 2026-11-04). The decisions made
before parking hold for when it is revisited: QuantLib in the Java server as the one pricing engine (QLRisk's other models through the
Python worker), a pure-Java core as its fallback (calendars, day counts, schedules, curves, Black and Bachelier, vanilla swaps, FX
forwards), sessions for thread safety, input guards, in-process by default with a worker mode, the latest stable version pinned. The route chosen (product owner, 2026-11-04): **`io.github.ralfkonrad.quantlib_for_maven:quantlib`** (Maven Central, versions 1.31.0
to 1.44.0, following QuantLib's numbers; QuantLib's SWIG Java bindings with prebuilt natives for Linux, macOS and Windows amd64; BSD-3
packaging, QuantLib's own licence), pinned and vendored in `drishti-native` with its checksum. This settles the Windows build. What
the binding requires of us: every QuantLib object is `AutoCloseable` with no finalizers, so the `quant` interface owns each object's
lifetime (callers never hold one) and a leak test guards it; the jar was checked (1.44.0): it is built **without** sessions (no `sessionId` hook), so QuantLib's evaluation date is process-wide;
**decided (product owner, 2026-11-04): pricing runs in single-threaded QuantLib worker processes** (each safe by construction; more
processes for more throughput; a native crash ends only that worker, which the server restarts while the request fails in words; the
same worker pattern as the Python worker over Arrow Flight). Linux needs glibc 2.32 or newer (Ubuntu 22.04+, RHEL 9+); the Windows DLL
needs nothing beyond `KERNEL32`. JNA and FFM were considered and rejected: QuantLib is C++, they reach C only; it has one maintainer, so our own build script stays the
fallback route.

## 7. One version of everything

| Check | How |
|---|---|
| One version per library | Maven Enforcer `dependencyConvergence` and `requireUpperBoundDeps` in the parent POM |
| No duplicate classes | Enforcer `banDuplicateClasses` (extra-enforcer-rules) |
| Natives only where allowed | an ArchUnit rule: `org.bytedeco`, `com.github.luben.zstd`, `org.xerial.snappy`, `net.jpountz`, `org.rocksdb`, `org.duckdb` are imported only in `drishti-native` |
| Platforms | only the `linux-x86_64` and `windows-x86_64` classifiers, never the `-platform` artifacts (which pull every OS) |
| One list | `docs/admin/NATIVE_DEPENDENCIES.md`, generated from the POMs by a tool and checked by a test: each native library, its version, licence, platforms, the capability it serves and its Java fallback |

## 8. Parity and tolerances

| Capability | Native against Java |
|---|---|
| BLAS sums | the near-tie rule makes VaR's order identical; sums within 1e-12 relative (summation order) |
| LAPACK | decompositions within 1e-10 relative on reconstruction; eigenvalues sorted and compared |
| Codecs | byte-identical output after a round trip; files written by either are read by both |
| Disk store | the same answers for every key; a store written natively is never read by the Java store (each keeps its own files) |

The parity suites run in the module's tests, and in the drill on both lanes (native `auto`, and `off`).

## 9. The Python worker (design only in 2.9.12)

For what exists only in Python (QLRisk's models, statsmodels, scikit-learn): a Python process beside the server, reached over **Arrow
Flight** (`DoExchange`): the server sends Arrow batches (vectors, matrices, trades), the worker answers in Arrow; NumPy and Polars read
Arrow without copying. Its own engine switch, timeout, health and canary, like any capability; when it is off or down, the features that
need it say so in words. The protocol, the packaging (a Python environment shipped beside the server, no exe on Windows) and the first
models are designed in 2.9.12 and built later, with QLRisk.

## 10. Settings, Admin and Health

| Setting | Default | Meaning |
|---|---|---|
| `drishti.native.mode` | `auto` | `auto` uses each native capability that loads; `off` uses Java everywhere; `on` refuses to start if one cannot load |
| `drishti.native.<capability>.mode` | `auto` | per capability: `auto`, `java`, `native` (`blas`, `lapack`, `codec`, `store`) |
| `drishti.native.cache-dir` | `<data>/native-cache` | where native libraries are unpacked |
| `drishti.native.canary-hours` | `6` | the canary's period |

Admin → Engines shows each capability (native or Java, why, version, calls, last canary) with its switch; Health shows `native.*`; the
console shows the DEGRADED banner when any capability fell back.

## 11. Order of work in 2.9.12

1. The module, the contract (section 4), the registry, Health and Admin; BLAS moved in (one commit), its tests green unchanged.
2. Dependency checks (section 7) and `NATIVE_DEPENDENCIES.md`.
3. Codecs with aircompressor; the disk store's Java fallback; DuckDB moved behind `SqlEngine`.
4. Hipparchus, then LAPACK with parity.
5. The drill's pure-Java lane; docs (OPERATIONS.md server requirements, CONFIGURATION.md, a native guide); the Python worker design.

Two drills (a large release); the benchmark with `drishti.native.mode` `off` against `auto` between them.

## 12. Decisions

| # | Question | Decision (product owner, 2026-11-04) |
|---|---|---|
| N1 | QuantLib's Windows build | **settled**: the quantlib_for_maven artifact ships Windows amd64 natives (section 6) |
| N2 | QuantLib version | the latest stable at build time, pinned (for when QuantLib is revisited) |
| N3 | Out-of-process QuantLib by default? | **worker processes, decided**: single-threaded QuantLib per process (the artifact has no sessions) |
| N4 | The disk store's Java fallback | **a pure-Java store on disk, H2's MVStore** (product owner: memory for a disk cache can be a problem). Pure Java, file-backed, ordered keys (the sorted store's scans), compression, concurrent readers; H2 is already a dependency (the JDBC plugin's tests), so the same library family, one pinned version. A cache written by RocksDB is not read by MVStore: each keeps its own folder, and a switch starts the other's cache empty (it is a cache) |

## 13. Phase 2: native capabilities for every industry (decided 2026-11-04, a later release)

*Product owner, 2026-11-04: Drishti serves more than banking, so any native library worth having is chosen by capability, not by
industry.* Each follows the contract of section 4 (an interface, a Java fallback or an "unavailable" answer in words, bundled for Linux
and Windows, one version), and reaches users through Rachana functions and engines, never as a new domain. Built after 2.9.11.i (the
lean platform), with "Performance II" (2.9.11.n) or a release of its own.

| # | Capability | Native | Java side | Serves | Decision |
|---|---|---|---|---|---|
| P1 | Machine-learning inference (forecasting, anomaly detection, classification) of models trained anywhere | ONNX Runtime (MIT, official Java API with bundled natives) | Tribuo (pure Java) for simple models; else "unavailable" in words | every industry: fraud and early warning, load forecasting, predictive maintenance, demand, risk scores | **adopt** |
| P2 | Optimisation: linear and mixed-integer programming, routing, scheduling | Google OR-Tools (Apache 2, Java bindings with natives) | Hipparchus simplex (linear) and Timefold (pure-Java planning) | logistics, energy dispatch, manufacturing schedules, portfolio, collateral and hedge optimisation | **adopt** |
| P3 | Signal processing, FFT | (FFTW not needed) | JTransforms (pure Java) | energy, manufacturing (vibration), telecoms, IoT | **pure Java only** |
| P4 | Faster JSON parsing | (simdjson not needed) | simdjson-java (pure Java SIMD) beside Jackson | every load | **pure Java only** |
| P5 | Geospatial formats and coordinate transforms | GDAL, PROJ | GeoTools, proj4j | logistics, energy grids, agriculture, insurance, telecoms | candidate: when a client asks |
| P6 | Hexagonal geo indexing | H3 (h3-java) | lat/long binning | insurance, logistics, retail, telecoms | candidate: when a client asks |
| P7 | Faster gzip | libdeflate / ISA-L | java.util.zip | every load and export | only if a benchmark shows gzip as the bottleneck |

Not taken: FAISS (Lucene's HNSW and hnswlib in pure Java suffice), CUDA/GPU (heavy, rarely present), a second SQL engine (DuckDB only),
QuantLib (parked, a release of its own).
