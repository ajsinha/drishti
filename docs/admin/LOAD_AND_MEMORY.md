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

# Load testing and memory profiling: the strategy

Status: strategy, adopted 2026-10-08. Section 3 lists what exists today; everything marked **(to build)** is proposed
and has a step in [the build plan](#11-build-plan). Owner: the server (`drishti-server`, `drishti-engine`), the console
(`drishti-console`) and `tools/bench/`.

**What this document answers.** How many people can one Drishti server and one console serve, with how much memory,
for how long, before latency or memory gets worse; and how to find out, repeatably, before a user does.
[PERFORMANCE.md](PERFORMANCE.md) holds the numbers and the tuning knobs; [SCALE_BENCHMARK.md](SCALE_BENCHMARK.md)
holds the data-size runs. This document is the method that produces and defends those numbers.

Contents

1. [Questions we must be able to answer](#1-questions-we-must-be-able-to-answer)
2. [Principles](#2-principles)
3. [What exists today](#3-what-exists-today)
4. [The workload model](#4-the-workload-model)
5. [Five kinds of test](#5-five-kinds-of-test)
6. [Memory: where it goes and how to see it](#6-memory-where-it-goes-and-how-to-see-it)
7. [Memory profiling, step by step](#7-memory-profiling-step-by-step)
8. [Pass and fail: the gates](#8-pass-and-fail-the-gates)
9. [Where and how to run](#9-where-and-how-to-run)
10. [Reporting results](#10-reporting-results)
11. [Build plan](#11-build-plan)

## 1. Questions we must be able to answer

| # | Question | Answered by |
|---|---|---|
| Q1 | How fast is one view, one search, one live tick, with nobody else on the server? | single-user timings (exist: PERFORMANCE.md §1–§6) |
| Q2 | How many people at once, doing what people do, before the p99 passes its gate? | **capacity test** (§5.2) |
| Q3 | What happens past that point: slower for everyone, refusals with a code, or a crash? | **stress test** (§5.3) |
| Q4 | Does memory stay flat over a working day and a week of restarts-free running? | **soak test** (§5.4) with heap and RSS trends |
| Q5 | How much memory does one more user, one more open view, one more live stream, one more pack cost? | **memory budget** (§6) from controlled steps |
| Q6 | Where does the memory go, and what is holding it? | **memory profiling** (§7): histograms, heap dumps, allocation profiles |
| Q7 | Does a release make any of the above worse? | **regression gates** (§8) run before a release |

## 2. Principles

1. **Model people, not requests.** A Drishti user opens a view and leaves it open; the cost that matters is mostly
   *held* (live streams, subscriptions, caches), not *requested*. A load test that fires requests and closes
   connections measures the wrong system.
2. **Measure at the gates people feel.** Warm view p99 under 50 ms, live tick to frame p99 under 40 ms, type-ahead under
   100 ms (PERFORMANCE.md). Server-side averages are diagnostics, not results.
3. **Memory is judged after a full GC, over time.** Heap "used" moves with the collector; the live set (after a forced
   full GC) and its slope over hours are what leak or grow.
4. **One variable at a time.** Users, views per user, tick rate, data size and packs are changed one at a time, so a
   result can be attributed.
5. **Reproducible or it did not happen.** Every run records its inputs (commit, JDK, flags, data size, machine, workload
   file) next to its outputs, in `tools/bench/results/`, and a script turns them into the tables that the docs show.
6. **Safe by default.** Heavy runs never share a machine with anything that matters (section 9).

## 3. What exists today

| Tool | What it does | Where |
|---|---|---|
| `tools/bench/measure.py` | Times each question (type-ahead, open a trade first time and again, searches, desk P&L, impact) for N repetitions and with N concurrent clients; then a full GC and the live heap (`jcmd GC.run`, `GC.heap_info`) and the process RSS | PERFORMANCE.md, SCALE_BENCHMARK.md |
| `tools/bench/scale.sh` + `report.py` | One store at one data size: start the store, load it, start a server, measure, clean up; tables with medians, p95, variance and labelled extrapolations | SCALE_BENCHMARK.md |
| `tools/bench/pinload.py` + `pinning.sh` + `mqpublish.py` | A mixed concurrent read load (views, raw documents, searches, desk P&L, impact, a busy RabbitMQ source) to compare Java 21 and 25 and to provoke virtual-thread pinning (JFR, `-Djdk.tracePinnedThreads`) | PERFORMANCE.md "Java 21 and virtual-thread pinning" |
| `drishti-benchmarks` (JMH) | Micro-benchmarks of the hot path | PERFORMANCE.md §7 |
| `/actuator/prometheus` + Grafana dashboard | JVM memory, GC, threads, HTTP timings, live counters | PERFORMANCE.md §3, `deploy/` |
| Admin → Health | `server.heapUsedMb`, `heapMaxMb`, live streams, sources | the console |
| Limits | `drishti.live.max-streams` (20,000), console `embed.max_streams` (64), rate limits per feature | CONFIGURATION.md |

What is missing: a **many-user** load that holds live streams through the console as browsers do, a **soak** run,
a **memory budget** per user and per view, and **profiles** of the console process and of a long-open browser tab.

## 4. The workload model

A workload is a YAML file (**to build**, `tools/bench/workloads/*.yaml`) that says what a population of simulated users
does. The default one, `desk-day.yaml`, is the trading desk's day:

| Persona | Share | Behaviour per simulated user |
|---|---|---|
| Watcher | 60 % | signs in; opens 3–6 views (trades, a netting set, a desk P&L) in tabs and keeps them live; every 2–5 minutes opens another and closes the oldest; About this page once an hour |
| Searcher | 25 % | types 1–3 searches a minute with type-ahead (a request per keystroke after the first two), opens 1 result in 3, pivots 1 in 10 |
| Analyst | 10 % | opens Impact (F8) and Compare every few minutes; one report download an hour |
| Author | 5 % | opens the workbench, saves a design every 5 minutes, runs its tests |

Plus the **source side**: entities ticking at a set rate (`mqpublish.py`, or the demo source's tick rate), and data
loads arriving on a schedule (`drishti.py data landed`). The model is parameterised: `users`, `ramp` (users per
second), `views_per_user`, `tick_rate` (updates per second across the book), `duration`.

**Think time is real.** Each action is followed by a pause drawn from the persona's range, so 1,000 simulated users
make the request rate 1,000 people would, not 1,000 tight loops.

## 5. Five kinds of test

### 5.1 Baseline (exists)

One user, warm caches, `measure.py`. Answers Q1. Run on every release candidate; numbers go to PERFORMANCE.md.

### 5.2 Capacity (to build: `tools/bench/users.py`)

`users.py` runs the workload model with **asyncio**: each simulated user is a coroutine with its own cookie jar that
signs in through the console, opens views with `GET /v/...`, opens the tab's **live channel** (`/api/channel`, server-sent
events, kept open and read to the end), subscribes and unsubscribes views on it as a browser does, types searches into
`/api/suggest`, and records, per action, the latency people feel and, per live frame, the tick-to-frame time (the frame's arrival
against the publish time `mqpublish.py` stamps into the document it sends). Standard library plus `httpx`, which
the console already depends on.

The run **steps** users: 50, 100, 200, 400, 800 … each held for 5 minutes after a 1-minute ramp, until a gate in section
8 fails. The **capacity** is the last step that passed every gate. Output: one JSON line per step with users, p50/p95/p99
per action, frames per second, errors by `DRS` code, server and console CPU, heap after GC, RSS, open streams.

It can also drive **embedded views** (`/embed/v1/...` with a token from the exchange) and the server **API** directly
(personal tokens), to size each entry point on its own.

### 5.3 Stress (to build: a mode of `users.py`)

Keep adding users past capacity, and also push each limit on purpose: `drishti.live.max-streams`, the console's
`embed.max_streams`, rate limits, a tick storm (10× the rate), a slow source (a JDBC source with 2 s latency). What we
want to see, and test for: **graceful refusal** (`429 DRS-...` or `503` with a code and `Retry-After`), latency rising
for new work while open views keep ticking, and **recovery** to baseline within a minute after the load stops. What
fails the test: a crash, an `OutOfMemoryError`, a stuck stream, a refusal without a code, or no recovery.

### 5.4 Soak (to build: a mode of `users.py` plus `tools/bench/memwatch.py`)

Capacity × 0.7 for **8 hours** (a working day) and, before a major release, **72 hours**. Every 5 minutes
`memwatch.py` records: heap after a full GC (`jcmd <pid> GC.run` then `GC.heap_info`), RSS, native memory summary
(`jcmd <pid> VM.native_memory summary`, with `-XX:NativeMemoryTracking=summary`), thread count, open file descriptors,
RocksDB disk-cache size, the console process's RSS and Python heap (section 7.4), open streams and topics. Pass: the
**slope** of heap-after-GC and of RSS over the last 6 hours is not distinguishable from zero (section 8), latency
gates still hold in the last hour, and nothing grows with the number of views ever opened (as opposed to open now).

### 5.5 Memory steps (to build: a mode of `users.py`)

To answer Q5, hold everything still and change one thing in steps, with a full GC and a heap measure at each step:
0 → 1,000 → 5,000 → 10,000 open live views (one user each, then 10 users sharing each view); 1 → 9 → 14 packs on;
10,000 → 50,000 trades a day in the cache. A straight-line fit gives **bytes per open view**, **per shared subscription**,
**per pack**, **per cached entity**: the memory budget of section 6.

## 6. Memory: where it goes and how to see it

| Component | Grows with | How to see it | Budget (to measure in §5.5; targets) |
|---|---|---|---|
| Server: live topics (TopicHub) | distinct entities watched | live counters in Health; histogram of `TopicHub` classes | target ≤ 4 KB per topic plus its latest document |
| Server: view streams (ViewStream) | open views (per tab, per view) | `drishti.live.*` metrics; histogram | target ≤ 50 KB per open view (layout cached by shape, not per view) |
| Server: layout and view caches | distinct shapes, entities viewed | Admin → Caches (sizes); histogram | bounded by cache settings; purge returns it |
| Server: connector caches | entities per connector | Admin → Caches; Kafka state mode memory and RocksDB disk cache | bounded by each connector's `memory` / `disk-cache.max-gb` |
| Server: identity, collaboration, audit | users, comments (JPA, SQLite or PostgreSQL) | database size; Hibernate statistics | small; the database holds it, not the heap |
| Server: native memory | threads (virtual threads are cheap), direct buffers, RocksDB block cache, Delta Kernel | NMT summary; RSS minus heap | RSS ≤ heap max + 1.5 GB at capacity |
| Console: live channels and hub | open tabs | console `/healthz`, Python heap (§7.4) | target ≤ 200 KB per open tab |
| Console: template and asset caches | constant | Python heap | constant after warm-up |
| Browser: one open view | panels, ticks over time | Chrome DevTools heap snapshot; Playwright CDP `Performance.getMetrics` | flat after 1 hour of ticks (no growth per tick) |

A **memory budget** is then: `heap ≈ base + views × per-view + topics × per-topic + caches`, used to size `-Xmx`
(`MaxRAMPercentage`) for a target number of users, and printed with the measured coefficients in PERFORMANCE.md.

## 7. Memory profiling, step by step

### 7.1 JVM flags for a profiled run

```bash
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational \
  -XX:NativeMemoryTracking=summary \
  -Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20m \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps \
  -XX:StartFlightRecording=name=soak,settings=profile,maxage=6h,disk=true,filename=./dumps/soak.jfr" \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

Flight Recorder's `profile` settings sample allocations with stack traces at low overhead (a few per cent); it is fine
for a soak run, not for a latency gate run, which uses the plain flags of PERFORMANCE.md.

### 7.2 Is something growing? (cheap, any time)

```bash
jcmd <pid> GC.run && jcmd <pid> GC.heap_info                 # live set after a full GC
jcmd <pid> GC.class_histogram | head -40 > histo-$(date +%H%M).txt   # what the heap is made of
jcmd <pid> VM.native_memory summary                          # native: threads, code, GC, internal, other
```

Take a histogram at the start of a soak, after 1 hour and after 6 hours; **diff** them (`memwatch.py --diff`, to build)
and look at classes whose instance count grows with time but not with the number of open views. Those are the leak
suspects; for Drishti the likely ones are per-request objects kept in a map keyed by something that never repeats
(stream ids, session ids, generations).

### 7.3 Who is holding it? (expensive, on suspicion)

```bash
jcmd <pid> GC.heap_dump -all=false ./dumps/heap-$(date +%H%M).hprof     # live objects only; pauses the server
```

Open it in **Eclipse Memory Analyzer** (MAT): *Leak Suspects*, then the *dominator tree* and *path to GC roots* of the
suspect class (excluding weak and soft references). A heap dump of a 16 GB heap is about as large as the live set and
pauses the server for seconds: take it on a test server, never on the users' one. **async-profiler** in allocation mode
(`asprof -e alloc -d 60 -f alloc.html <pid>`) shows *who allocates* when the problem is churn (GC pressure, latency)
rather than retention; it needs `perf_event` access on Linux.

### 7.4 The console (Python)

- **Growth:** `tracemalloc` snapshots, taken by a debug-only endpoint (**to build**, `GET /debug/memory`, admin only,
  off unless `DRISHTI_CONSOLE_DEBUG_MEMORY=true`) that returns the top 30 allocation sites and the difference from the
  previous snapshot. RSS comes from `/proc/<pid>/status` (`VmRSS`).
- **Profiles:** `memray run --live drishti-console/run_drishti_web.py` on a test machine for allocation flame graphs;
  `py-spy dump --pid <pid>` for where the event loop is stuck when latency jumps.
- **What to watch:** live channels and hub subscriptions per closed tab (must return to zero), `ChannelSession` objects
  after disconnects, the embed `StreamGuard`s after token expiry, httpx connection pool size (`backend.pool_size`).

### 7.5 The browser

A view open for a working day must not grow. A Playwright test (**to build**, `tests/test_memory_soak_browser.py`,
marked `slow`, not in the drill) opens a live view with a ticking source, records `JSHeapUsedSize` and DOM node count
through CDP every minute for 60 minutes (8 hours in a soak run), with `HeapProfiler.collectGarbage` before each reading.
Pass: no upward slope after the first 10 minutes. On failure, take two heap snapshots 30 minutes apart in Chrome
DevTools and compare (*Objects allocated between snapshots*): usual suspects are detached DOM nodes from replaced
panels, ECharts instances not disposed on panel replacement, and listeners added on every patch.

## 8. Pass and fail: the gates

| Gate | Threshold | Test |
|---|---|---|
| Warm view p99 at capacity | < 50 ms | capacity |
| Live tick to frame p99 at capacity | < 40 ms | capacity |
| Type-ahead p95 at capacity | < 100 ms | capacity |
| Errors at capacity | < 0.1 % of actions, none without a `DRS` code | capacity |
| Past capacity | refusals carry a code and `Retry-After`; open views keep ticking; back to baseline within 60 s | stress |
| No crash, no `OutOfMemoryError`, no stuck stream | always | all |
| Heap after GC, slope over the last 6 h of a soak | < 1 % of heap max per hour, and the 95 % confidence interval of the slope includes 0 | soak |
| RSS slope over the last 6 h | < 2 % per hour | soak |
| Console RSS per open tab, after tabs close | returns within 10 % of the starting value | soak |
| Browser JS heap over 60 minutes of ticks | no upward slope after 10 minutes | browser memory |
| Regression against the last release | p99s not more than 10 % worse; per-view bytes not more than 15 % worse | release run |

The drill keeps its own fast gate (PERFORMANCE.md §8); the gates above run before a release and after any change to
the live path, caches or connectors, not on every commit.

## 9. Where and how to run

| Tier | Where | Data | Users | When |
|---|---|---|---|---|
| **Smoke** | a developer machine, scratch ports, with nothing else heavy running | ≤ 10,000 trades a day | ≤ 50 simulated | before merging a change to the live path |
| **Capacity and stress** | a dedicated test server, the production JVM flags, the production store | 1,000,000 trades a day | to failure | before each release |
| **Soak** | the same dedicated server | 1,000,000 trades a day | capacity × 0.7 | before a major release, and monthly |

Rules for every run:

- **Never against the users' server or console**, and never on ports you did not start. Scratch servers and consoles use
  their own ports and their own data directory under the run's scratch folder, deleted at the end.
- **One heavy job at a time** on a shared machine; a million-trade run is for a real server (a developer workstation
  was brought down by one). Locally, stay at 10,000 trades a day or less.
- **Mind `/tmp`.** On machines where `/tmp` is a RAM disk, heap dumps, JFR files and bench scratch go to a disk path
  (`DRISHTI_BENCH_DIR`), never to `/tmp`. `scale.sh` already refuses to start under 20 GB free; `users.py` and
  `memwatch.py` will do the same.
- **The load generator is not the bottleneck.** Run it on another machine for capacity and soak runs; it reports its own
  CPU and stops with a warning when it passes 70 %.
- **Containers for stores** are named `drishti-bench-*` and removed with their volumes; no other container is touched.

## 10. Reporting results

Every run appends JSON lines to `tools/bench/results/<date>-<tier>.jsonl` with the run's inputs (commit, JDK and
flags, machine, data size, workload file and its hash) and outputs. `tools/bench/report.py` (extended, **to build**)
renders: capacity per entry point (console, API, embed) with the gate that stopped it; latency against users; heap
after GC and RSS over a soak, with the fitted slope and its interval; the memory budget coefficients; and the
comparison with the previous release. PERFORMANCE.md quotes those tables and says which run they come from; a number
in the docs without a run behind it is not allowed.

## 11. Build plan

| Step | What | Size | Done when |
|---|---|---|---|
| 1 | `tools/bench/users.py` with the workload model (YAML), personas, think time, sign-in, views, the live channel, searches; capacity steps; JSON lines | M | 50 simulated users against a scratch server give per-action p50/p95/p99 and tick-to-frame |
| 2 | `memwatch.py`: periodic heap after GC, RSS, NMT, threads, fds, caches, console RSS; histogram diffs | S | a 1-hour local run plots flat lines, and a deliberately leaking test build is flagged |
| 3 | Stress mode: limits, tick storm, slow source; graceful-refusal checks | S | every refusal carries a code; recovery measured |
| 4 | Memory steps mode and the budget fit | S | bytes per view, per topic, per pack, per cached entity printed with R² |
| 5 | Console `/debug/memory` (off by default, admin only) | S | top allocation sites and their difference between two snapshots |
| 6 | Browser memory soak test (Playwright, CDP), marked slow | S | 60 minutes of ticks without growth, in Chromium |
| 7 | `report.py` extensions and a "Load and memory" section in PERFORMANCE.md fed from a real run on a dedicated server | M | capacity, soak slopes and the budget published with their run ids |

Steps 1 and 2 give answers to Q2 and Q4 within days; step 7 needs the dedicated server that IMPLEMENTATION_PLAN.md's
roadmap item "Scale on a real server" already asks for, and is best run together with it.
