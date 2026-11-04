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

**Status: design for 2.9.12 (product owner, 2026-11-04).** One module holds every native integration of the server, each behind a
Drishti interface with a pure-Java implementation that always works, so **Drishti runs on any environment**. It realises the seam of
[HIGH_PERFORMANCE_NATIVE.md](HIGH_PERFORMANCE_NATIVE.md) section 2.8 on Java 21 (JNI through JavaCPP) and keeps room for the Java 25
paths of that design (FFM, the Vector API) as further implementations of the same interfaces.

## 1. The rule

*Product owner, 2026-11-04: "Key is we maintain abstractions and have fallbacks so we can run on any environment."*

1. **Every native capability sits behind a Drishti interface.** Callers see only the interface; only `drishti-native` imports a native
   binding (an architecture test enforces it).
2. **Every interface has a pure-Java implementation that always works.** Native code is an optimisation chosen at run time, never a
   requirement. Where a capability is too large to write twice (QuantLib's full model library), the Java side covers a named core and
   anything beyond it answers *needs QuantLib* in words (section 6.5).
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
| **Quant finance** | `Calendars`, `DayCounts`, `Schedules`, `Curves`, `Pricers` | QuantLib (our SWIG/JNI build) | a Java core (section 6.5) | new |
| **Compression** | `Codecs` (zstd, Snappy, LZ4, gzip/deflate) | zstd-jni, snappy-java, lz4-java | aircompressor (pure Java, same formats) | natives used in five modules without a fallback |
| **Key-value disk store** | `DiskStore` (the disk cache) | RocksDB | a pure-Java store, or memory only with a warning | RocksDB in `drishti-diskcache`, no fallback |
| **Embedded SQL engine** | registered for status only | DuckDB | the pivot engine's Java path where it answers; else the feature says it is unavailable | DuckDB in `plugin-duckdb`, `drishti-bicompute`, the server |

DuckDB stays in its modules (it is a whole engine, not a kernel); it is **registered** with `drishti-native` so its status, version and
switch appear with the others, and the dependency rule of section 7 names it.

## 3. The module

```
drishti-native/
  com.ash.drishti.nativ.core      Capability, Registry, Guard (input checks, breaker), Canary, NativeMode, the status record
  com.ash.drishti.nativ.linalg    LinearAlgebra, Lapack; JavaBlas, MultiSums, HipparchusLapack; OpenBlas, OpenLapack (JavaCPP)
  com.ash.drishti.nativ.maths     Stats, Optimise, Interpolate, Distributions (Hipparchus)
  com.ash.drishti.nativ.quant     Calendars, DayCounts, Schedules, Curves, Pricers; the Java core; QuantLibBinding (JNI)
  com.ash.drishti.nativ.codec     Codecs; Zstd/Snappy/Lz4 natives; aircompressor
  com.ash.drishti.nativ.store     DiskStore; RocksDbStore; JavaDiskStore
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

## 6. QuantLib

### 6.1 Why

The product owner's decision (2026-11-04): **QuantLib in the Java server is the one pricing engine**. Calendars, day counts, schedules,
curves and pricers are written once, by QuantLib, and the counterparty engines (2.9.14) and any pricing build on them. QLRisk's models
that QuantLib lacks come later through the Python worker (section 9).

### 6.2 The binding

- **QuantLib-SWIG's Java bindings**, built by us: `QuantLib.jar` (the Java classes SWIG generates) and the native library per platform:
  `libQuantLibJNI.so` (Linux x86-64), `QuantLibJNI.dll` (Windows x86-64; a DLL is allowed on the Windows QA machine, an exe is not).
- **One pinned QuantLib version** (and its Boost), recorded in the parent POM and in `NATIVE_DEPENDENCIES.md`; upgraded deliberately,
  with the parity suite.
- **Packaged like OpenBLAS:** a jar per platform with the library inside, unpacked at first use into `drishti.native.cache-dir`
  (default `<data>/native-cache`). Nothing is downloaded at run time.
- **Built by a script** (`tools/native/build_quantlib.sh` and `.ps1`, planned) from the pinned sources, reproducible; the built jars go
  to the repository's vendored library folder with their checksums (VENDOR.md), not to Maven Central.

### 6.3 Threads

QuantLib keeps global state: the evaluation date (`Settings`) and observers. A server pricing for many people at once must not share
it. The build enables **sessions** (`QL_ENABLE_SESSIONS`, one `Settings` per thread, the thread-id function supplied by the binding),
and each pricing call runs on a thread that sets its own evaluation date first. A test prices on 16 threads at once with different
dates and compares with the single-threaded answers.

### 6.4 Crashes

A fault inside native code ends the JVM, which a Java exception would not. So: the guard (section 4) checks every input QuantLib would
assert on (dates in range, positive tenors, sorted pillars, finite rates); a QuantLib error is caught as its Java exception, never a
crash; the canary prices a fixed book. And an **out-of-process mode** (`drishti.native.quant.process: in | worker`): pricing runs in a
separate JVM (`drishti-quant-worker`, the same jar) that the server starts and talks to over a local socket with Arrow batches; if it
dies, the server restarts it and the request fails in words, the server keeps running. `in` is the default for speed; `worker` for heavy
or untrusted models.

### 6.5 The Java core

The fallback covers the building blocks the roadmap uses first, each proved equal to QuantLib in tests (dates exact; values within
1e-10 relative):

| Area | Java core |
|---|---|
| Calendars | TARGET, the UK, the US (settlement, government bond), Canada, Japan, joint calendars; business-day adjustments |
| Day counts | Actual/360, Actual/365 Fixed, Actual/Actual (ISDA), 30/360 (bond basis, European) |
| Schedules | regular and stub schedules, end-of-month, forwards and backwards generation |
| Curves | discount curves from deposits, FRAs and swaps (bootstrap, log-linear discount interpolation); zero and forward rates |
| Pricers | Black and Bachelier (caplets, swaptions, FX and equity options: price and Greeks), vanilla interest-rate swaps, FX forwards |

Anything beyond the core (for example short-rate models, exotics, credit models) on a machine without QuantLib answers: *this needs
QuantLib, which is not available on this server (see Admin → Engines)*.

## 7. One version of everything

| Check | How |
|---|---|
| One version per library | Maven Enforcer `dependencyConvergence` and `requireUpperBoundDeps` in the parent POM |
| No duplicate classes | Enforcer `banDuplicateClasses` (extra-enforcer-rules) |
| Natives only where allowed | an ArchUnit rule: `org.bytedeco`, `com.github.luben.zstd`, `org.xerial.snappy`, `net.jpountz`, `org.rocksdb`, `org.quantlib` are imported only in `drishti-native` (DuckDB only in its listed modules) |
| Platforms | only the `linux-x86_64` and `windows-x86_64` classifiers, never the `-platform` artifacts (which pull every OS) |
| One list | `docs/admin/NATIVE_DEPENDENCIES.md`, generated from the POMs by a tool and checked by a test: each native library, its version, licence, platforms, the capability it serves and its Java fallback |

## 8. Parity and tolerances

| Capability | Native against Java |
|---|---|
| BLAS sums | the near-tie rule makes VaR's order identical; sums within 1e-12 relative (summation order) |
| LAPACK | decompositions within 1e-10 relative on reconstruction; eigenvalues sorted and compared |
| QuantLib core | dates and schedules exact; values within 1e-10 relative |
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
| `drishti.native.<capability>.mode` | `auto` | per capability: `auto`, `java`, `native` (`blas`, `lapack`, `quant`, `codec`, `store`) |
| `drishti.native.cache-dir` | `<data>/native-cache` | where native libraries are unpacked |
| `drishti.native.canary-hours` | `6` | the canary's period |
| `drishti.native.quant.process` | `in` | `in` the server, or `worker` (a separate JVM) |

Admin → Engines shows each capability (native or Java, why, version, calls, last canary) with its switch; Health shows `native.*`; the
console shows the DEGRADED banner when any capability fell back.

## 11. Order of work in 2.9.12

1. The module, the contract (section 4), the registry, Health and Admin; BLAS moved in (one commit), its tests green unchanged.
2. Dependency checks (section 7) and `NATIVE_DEPENDENCIES.md`.
3. Codecs with aircompressor; the disk store's Java fallback; DuckDB registered.
4. Hipparchus, then LAPACK with parity.
5. QuantLib: the build scripts, the binding, sessions, the guard, the worker mode; the Java core with parity.
6. The drill's pure-Java lane; docs (OPERATIONS.md server requirements, CONFIGURATION.md, a native guide); the Python worker design.

Two drills (a large release); the benchmark with `drishti.native.mode` `off` against `auto` between them.

## 12. Questions

| # | Question | Recommendation |
|---|---|---|
| N1 | Building QuantLib's Windows DLL needs a Windows build (MSVC) or a cross-compiler (MinGW) | **MinGW cross-compile on Linux** in the build script, tested on the Windows QA machine; MSVC only if MinGW's DLL misbehaves |
| N2 | QuantLib version | the latest stable at build time, pinned; upgrades only with the parity suite |
| N3 | Out-of-process QuantLib by default? | **In-process by default**, with the guard and canary; `worker` for heavy models and as the switch if a crash is ever seen |
| N4 | The disk store's Java fallback: a pure-Java store or memory only? | **Memory only with a warning** first (the cache is a cache); a pure-Java store later if measured as needed |
