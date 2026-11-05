<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The browser and renderer test harness

How the console's browser tests are run so that their time grows with the machine, not with the number of pages: where the time
went, the design that replaced it, the numbers it must hold, how it was validated, and the rules every new browser test follows. The
testing architecture as a whole (the test pyramid, the drill's stages) is in [ARCHITECTURE.md, "Testing architecture"](ARCHITECTURE.md#22-testing-architecture);
the drill's every option is in [PACK_DEVELOPER_GUIDE.md section 2.6](../guides/PACK_DEVELOPER_GUIDE.md#26-the-drill-toolsdrillsh).

## Contents

1. [The problem](#1-the-problem)
2. [Where the time went](#2-where-the-time-went)
3. [The design](#3-the-design)
4. [Numbers to hold](#4-numbers-to-hold)
5. [Validation](#5-validation)
6. [Rules for browser tests](#6-rules-for-browser-tests)
7. [Measuring a run](#7-measuring-a-run)
8. [Next candidates](#8-next-candidates)

## 1. The problem

Every page a release adds is drawn by a browser test, often at two widths and in two render modes. When a file's pages are drawn one
after another inside one test, on one pytest worker, the file's time grows linearly with the pages, and the release drill waits for
its slowest file. More workers do not help a file that is one test. The harness therefore has to (a) cut what one page costs,
(b) hand the work to the workers in small pieces, and (c) size the workers to the machine without running it out of memory.

## 2. Where the time went

Measured on the release machine (Ryzen AI 9 HX 370, 12 cores / 24 threads, 61 GB), before the redesign.

**The lane.** The drill's browser lane ran 159 files on 3 pytest-xdist workers with `--dist loadfile` (a worker takes a whole file):
58 min 42 s of wall time for 10,535 s of work. Two files were 2,704 s of it, each a single test on a single worker:

| File | Seconds (sum of its tests) | What it is |
|---|---:|---|
| `test_render_console_browser.py` | 1,686 | about 824 pages: every pack Sutra, server and client render, 2 widths, in one test |
| `test_render_parity_browser.py` | 1,018 | about 800 pages: the same ViewModels, console macros against the client library |
| `test_mr_cube_browser.py` | 519 | 4 tests on one server over a VaR demo lake built for the file |
| `test_embed_elements_browser.py` | 514 | 35 tests on one host, console and server, once per browser engine |
| `test_page_widths_browser.py` | 324 | a sweep of every page at two widths |
| `test_bi_live_browser.py` | 262 | |
| `test_topbar_menus_phone_browser.py` | 238 | |
| `test_funds_browser.py` | 237 | |
| `test_var_currency_browser.py` | 216 | |
| `test_var_explorer_browser.py` | 210 | |

20 files took over 100 s; 129 files took 60 s or less (2,947 s together).

**One console page** (the console render suite's flow: a fresh browser context, the console page, its waits, the read), 56 pages,
seconds per page:

| Phase | Mean |
|---|---:|
| a fresh context and page | 0.049 |
| the page loaded (`load`) | **1.828** |
| the first panel attached | 0.117 |
| the three settle waits (charts, aria-busy, discussion badges) | 0.079 |
| the fixed `wait_for_timeout(400)` | **0.416** |
| two animation frames | 0.033 |
| the read (text, cells, links, tones, charts, accessible tree) | 0.112 |
| closing the context | 0.011 |
| **per page** | **2.645** |

Of the 1.8 s load, 0.9 to 1.7 s was the console building the HTML. Profiling that request showed 80 calls of `yaml.safe_load`: the
console parsed every enabled pack's `pack.yaml`, workspaces and help file on every page whose per-user pack cache was cold, and the
stand-in server (which has no pack list) never let the cache fill. With the parsed packs kept while their files are unchanged
(`core/packs.py`), the same page loads in 0.54 s and the page costs 1.14 s. This is a product fix: a real console paid the same
second of event loop for every user whose ten-second cache had expired.

**One parity page** (the render parity suite's flow: a fresh page, every request answered by a Python route handler, network idle,
two frames and a fixed 150 ms), 32 pages: 1.431 s per page, of which the network-idle wait 0.387 s and the frames with the pause
0.182 s. Network idle is by definition at least 500 ms after the last request.

**The rest of the lane.** A file that starts a real server pays the JVM's start (about 20 to 40 s on an idle machine, minutes on a
loaded one) once per file. Servers piled up during a run (17 server JVMs and 16 GB at once in one drill): every worker kept its
session server for its whole life, and the Java verify ran beside the lane.

## 3. The design

### 3.1 Distribution by test

The browser lane runs with `--dist loadgroup` (`drishti-console/tests/harness_plugin.py`, its hooks imported by `conftest.py`):

- a test marked **`spread`** is a work unit of its own: the workers take such tests one by one;
- every other test is grouped with the rest of its file (`xdist_group` = the file), so a file that starts its own server or keeps state
  between its tests runs on one worker, in order, as before;
- the file groups are queued first (the drill's planner orders the files longest first), the spread tests after them: they are
  seconds each and fill the workers at the end.

Under any other `--dist` (the other lane, the gate, a single run) the marks change nothing.

The two render suites are spread: one test per pack Sutra (both widths, both modes or both renderers in it), per edge case, per
captured view and per theme.

### 3.2 Per-worker fixtures

Each is session-scoped, so under pytest-xdist it is made once per worker, the first time a test asks, kept and closed when the worker
ends (`worker_fixtures.py`, imported by `conftest.py` so every file sees one definition):

| Fixture | What |
|---|---|
| `browsers` | one Chromium for the worker, driven through Playwright's async API on a thread of its own (`browser_harness.Browsers`) |
| `render_site` | the render harness's made-up origin `http://render.test`, served over HTTP on the worker's port (`render_harness.SiteServer`) |
| `render_console` | the render suite's stand-in console (an in-process uvicorn) |
| `pack_views` | every pack Sutra bound on its sample by a real server: once per run for all workers (below) |

**Pack views once per run.** Binding about 200 Sutras needs a real server. The first worker that asks starts one (through the server
pool), binds them, writes the answer to the run's shared folder (the parent of the workers' pytest temporary folders) under a file
lock, and stops the server; every other worker reads the file (`browser_harness.shared_once`).

### 3.3 Pages at once

Inside a test the pages it compares are drawn at the same time (`asyncio.gather` on the worker's browser): the console page and the
library page of a ViewModel, at both widths, are four pages at once. `browser_harness.PAGES_AT_ONCE` (default 4,
`DRISHTI_BROWSER_PAGES_AT_ONCE`) bounds the pages a worker has open. Measured on the 32 parity tests of the edge cases (128 pages,
one worker, a loaded machine): 181.9 s one page at a time, 59.0 s four at a time.

The sync API stays for the other files: the async browser lives on its own thread, so a file that opens `sync_playwright()` on the
main thread is unaffected.

### 3.4 The page-settled signal

`static/js/drishti_settled.js` (loaded by every console page; the per-worker browser also puts it on every page as an init script)
defines `Drishti.settled(options)`, a promise that resolves when the page is ready to be read:

- the document loaded and its fonts in;
- every panel chart and map drawn (an ECharts instance on every `.chart` and `.xchart` that is not `.pnl-empty`, in the page and in any
  open shadow root) and, unless `animations: false`, its animation finished (ECharts' renderer says so);
- nothing `aria-busy` in the view or a render host;
- the Discussion's badges placed when the Discussion is on;
- under automation (`navigator.webdriver`), no fetch of the page's own still unanswered;
- then no change to the page outside the charts for `quiet` ms (default 50), and two frames.

It rejects after `timeout` ms naming what is still pending ("2 chart(s) not drawn", "1 request(s) unanswered"), so a test that waits
says why. It is inert for people: nothing runs until something calls it, and the fetch count exists only under automation. A test that
reads a chart's option and the page's text, never its pixels, passes `animations: false` and does not wait for a chart to finish moving.

On a parity page the signal costs 0.14 s where network idle, two frames and the fixed pause cost 0.57 s.

### 3.5 Contexts reused, reset between tests

`Browsers.page(width)` lends a browser context of that width from a pool and resets it when the test hands it back (every page closed,
local and session storage cleared, cookies cleared, the phone display's *show everything* cookie set again): the next test starts as on
a fresh context, with the HTTP cache warm. A page whose requests Chromium cancelled because the machine's network changed
(`net::ERR_NETWORK_CHANGED`: Docker starting or stopping a container) is loaded once more with the device cookie cleared
(`browser_harness.open_settled`, through `wb_live.should_reload`); any other failure is the page's own.

### 3.6 The render origin over HTTP

The parity suite draws pages on a made-up origin. They were answered by a Python route handler, one round trip through Playwright per
request and no browser cache. `render_harness.SiteServer` serves the same answers over HTTP on the worker's scratch port; the
per-worker browser resolves `render.test` to it and every other name to nothing (`--host-resolver-rules`), so nothing leaves the
machine without an interceptor, static files are cached for the run, and the page records every request it made (one off the origin
fails the test).

### 3.7 The server pool

Every real server a browser test starts through `wb_live._stack` passes through the worker's `StackPool`: it times each start
(`DRISHTI_BROWSER_PROFILE`), keeps at most one idle server, and stops it before any other start, so servers do not pile up on a worker.
A file that asks with `shared=True` leaves its server idle when it ends, and the next file on that worker asking for the same settings
(environment, sign-in, heap) gets it without a start. The `*_stack` modules (BI, news, Drishti*net, artifacts) start their own fixed
stacks on the worker's ports, one file at a time.

### 3.8 Ports

`scratch_ports.worker_port(base)` gives each worker a block of its own: the base plus 1,250 per worker. Every base lies in one window
(17750 to 18999), so no two workers' ports can meet whatever the bases; the last block ends below Linux's ephemeral ports (32768),
which allows 12 workers (`scratch_ports.MAX_WORKERS`). `tests/test_scratch_ports.py` checks every base is in the window, used by one
file, and written with the offset.

### 3.9 Worker sizing

`tools/drillkit/drill_plan.py workers`: the memory available (`MemAvailable`) less what runs beside the lane (in the drill: the Java
verify's 20 GB, 1 GB per worker of the other lane and 4 GB to spare), divided by one browser worker's budget (2.5 GB: its Chromium,
its stand-in console, its session server's JVM and the JVM of the file it runs), at most the CPU threads less two and at most 12 (the
ports), at least 1. `DRILL_BROWSER_WORKERS` overrides it.

### 3.10 The drill's lane

`tools/drill.sh` runs the browser lane with `--dist loadgroup --no-loadscope-reorder` on the workers above; samples the lane's
processes and memory every 5 s into `<data>/drill/monitor-browser.csv` and prints the peaks (`tools/drillkit/drill_monitor.py`); and
after the lane flags every file over the per-file budget (section 4) in `<data>/drill/budget-browser.txt` and in the drill's
summary (`drill_plan.py budget`). A file listed in `tools/drill-budgets.txt` is reported with its reason; a spread file has no file
budget. The re-run of failed tests is unchanged (once, file by file).

## 4. Numbers to hold

| What | Number |
|---|---|
| the browser lane, in the drill, on the release machine | ≤ 20 min |
| the two render suites together (wall time in the lane) | ≤ 8 min |
| one browser file's tests together (unless spread or listed in `tools/drill-budgets.txt`) | ≤ 300 s |
| a fixed pause in a browser test without a reason | none over 100 ms |
| memory | the lane's peak ≤ its workers × 2.5 GB plus the shared Chromium processes; at most two server JVMs per worker |

## 5. Validation

The plan: the whole browser lane run three times on the redesign, each compared with develop's lane run on the same machine with the
same workers: the same tests pass and fail (each difference listed with its cause), no new flaky test, and the wall time, the
worker-time per file and the memory peaks recorded here and in [BENCHMARK.md section 18](../admin/BENCHMARK.md#18-the-browser-test-lane).

Measured so far, on the release machine while other jobs shared it (load average about 25 on 24 threads):

| Check | Before | After |
|---|---|---|
| the 32 parity tests of the edge cases and captured views (128 pages), one process | 168.7 s | 59.0 s (181.9 s one page at a time) |
| one parity page, one at a time | 1.43 s | 1.16 to 1.24 s |
| one console render page, one at a time (the pack parse fixed) | 2.65 s | 1.14 s, before the signal and the reused contexts |
| both render suites (485 tests), 4 workers, the pack views bound by one server | 2,704 s of work on one worker each | 353 s wall (469 passed, 16 skipped: Sutras the preview does not bind, a stand-in without layout mode) |
| the edge-case and behaviour tests of both render suites | | 56 passed, as on develop |

The three full lane runs and their comparison with develop's lane are to be measured.

## 6. Rules for browser tests

`drishti-console/tests/test_browser_rules.py` checks rules 2 to 4 on the source; a file written before the rules carries its count of
old pauses and starts in the test's `BASELINE`, which may only go down.

1. **Small and independent.** One behaviour per test. A test that needs nothing but per-worker fixtures and can run alone on any
   worker in any order is marked `@pytest.mark.spread` (or the file's `pytestmark`), and the workers share it out one by one.
2. **Spread tests ask only for per-worker fixtures** (`browsers`, `render_site`, `pack_views`, a session fixture of their file) and
   their own function fixtures. A module fixture would start again on every worker the file's tests land on.
3. **No fixed pause over 100 ms.** Wait on the page's settled signal (`await browser_harness.settled(page)`, or
   `page.evaluate("(o) => window.Drishti.settled(o)", {...})` on the sync API) or on the condition itself (`wait_for_function`,
   `wait_for_selector`). A pause that is the behaviour under test (a debounce, a countdown) carries its reason on the line:
   `# wait-ok: <why>`.
4. **Real servers come from the pool.** A fixture that starts one is module- or session-scoped and goes through `wb_live._stack`
   (or a `*_stack` module); nothing starts a server per test. Ask with `shared=True` when the file leaves the server as it found it.
5. **Ports through `scratch_ports.worker_port`**, with a base in the window (17750 to 18999) that no other file uses.
6. **300 s per file.** A file whose tests take longer together is split into spread tests, or listed in `tools/drill-budgets.txt`
   with the reason it cannot be yet; the drill flags any other.
7. **One reload, for one cause.** A page is loaded again only when Chromium cancelled its requests because the network changed
   (`wb_live.should_reload`, once per page); any other timeout is a failure, never retried.
8. **Docker and Chrome do not share the machine.** A Docker container starting or stopping changes the network interfaces and
   Chromium cancels the requests in flight: never run browser tests while the Docker-based Maven suites (the JDBC, MongoDB, Redis,
   Cassandra and Aerospike plugins) or a drill run.
9. **Read what the user reads.** Charts are compared by their ECharts option, never pixels; such a test passes
   `animations=False` to the settled wait. A screenshot test waits for the animations (the default).
10. **Start clean.** A test never relies on cookies or storage an earlier test left: the per-worker contexts are reset between tests.

## 7. Measuring a run

- `DRISHTI_BROWSER_PROFILE=<file.jsonl>`: every page of the render suites writes its phases (page, load, rendered, settled, read,
  release) and every server start its seconds, one JSON line each (`browser_harness.Phases`).
- `python3 tools/drillkit/drill_monitor.py watch --out m.csv --under <pid>` samples a run's server JVMs, other JVMs, Chromium processes,
  consoles and pytest processes with their resident memory and the machine's available memory; `summary m.csv` prints the peaks.
- `pytest --durations=60 --durations-min=5` lists the slowest set-ups (a module fixture's start is its first test's set-up).

## 8. Next candidates

- `test_page_widths_browser.py`: one test per page and width instead of a sweep per width (it is listed in `tools/drill-budgets.txt`).
- `test_embed_elements_browser.py` and `test_mr_cube_browser.py`: their stack is built once per file; a session stack per worker
  through the pool would let their tests spread.
- The files that start the same signed-in server (`DRISHTI_SECURITY_ENABLED`, the test token secret) could ask with `shared=True` once
  each is shown to leave the server as it found it.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
