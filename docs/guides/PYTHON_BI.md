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
# Python in BI: measures and visuals written in Python

Drishti Rūpaka runs **typed Python** where the expression language and the visual catalogue stop: a **Measure** is a Python function that turns the rows of a group into a number
(a desk's P&L volatility, a percentile no aggregate gives, a fitted slope), and a **Visual** is a Python function that turns rows into a chart (a histogram with your own bins, a
regression plot). Authors do not paste scripts into a visual: a Python function is a **BI item** of its own, with a manifest, versions, review and publishing like a dataset or a
report, and a report names it. It runs **sandboxed on the console**, or **in the viewer's browser** (Pyodide), always on **the rows the person looking may see**.

A third interface, the **Transform**, turns the frames of several datasets into a table that a **derived dataset** publishes (section 13): it is how Python joins datasets.

This is the reference. For a walk-through see [tutorial 10](../tutorials/10-first-bi-report.md) chapter 24 and [tutorial 14](../tutorials/14-python-and-analyse.md); the design is [RUPAKA.md](../architecture/RUPAKA.md) section 13, what is built is its section 20.8.
Python in the *terminal* (Calc) is a different thing: [PYTHON_CALC.md](PYTHON_CALC.md).

## Contents

1. [At a glance](#1-at-a-glance)
2. [The two interfaces](#2-the-two-interfaces)
3. [The manifest](#3-the-manifest)
4. [Python items are BI items](#4-python-items-are-bi-items)
5. [Using them: a Python measure and the python visual](#5-using-them-a-python-measure-and-the-python-visual)
6. [Where it runs](#6-where-it-runs)
7. [The sandbox, exactly](#7-the-sandbox-exactly)
8. [Who sees what](#8-who-sees-what)
9. [Settings](#9-settings)
10. [Errors: DRS-9070 to DRS-9074](#10-errors-drs-9070-to-drs-9074)
11. [Measured](#11-measured)
12. [Built and not built](#12-built-and-not-built)
13. [Transforms: a table in, a table out](#13-transforms-a-table-in-a-table-out)

## 1. At a glance

```
 report visual (type: python) ──asks──▶ console ──▶ server: what to run (item + manifest), and the rows this person may see (masked Arrow)
                                          │
              ┌───────────────────────────┴───────────────────────────┐
         server target                                            browser target
   a short-lived worker process                         Pyodide in a Web Worker of the page
   (no network, scratch folder, limits)                 (the runtime Calc ships; loaded on first use)
              └──────────── a number per group, or a chart (data, never HTML) ────────────┘
```

| | Python measure | Python visual |
|---|---|---|
| function | `compute(table, params)` | `render(table, params)` |
| called | once per group, with that group's rows | once, with all the rows handed over |
| returns | one number (or `None`) | an ECharts option (a dict), SVG text or PNG bytes |
| drawn as | bars, one per group | the chart or image |

Two samples ship (`config/bi/python/`), and a sample report **Desk P&L risk (Python)** shows both on the trades model:

* `desk-pnl-volatility` (a measure): the standard deviation of one-day P&L, per desk;
* `pnl-histogram` (a visual): a histogram of one-day P&L drawn with numpy.

![The sample report, run on the server](img/bi-python/report-server.png)

## 2. The two interfaces

**Measure.** Read `config/bi/python/desk-pnl-volatility.python.yaml`:

```yaml
rachana: 1
python: desk-pnl-volatility
title: Desk P&L volatility
interface: measure
inputs: [pnl1d]                  # the columns the function reads, as the table names them
packages: [pandas]
target: both                     # server, browser or both
timeout: 10                      # seconds
memory: 256                      # megabytes
params: { min_trades: 2 }
source: |
  def compute(table, params):
      """One number for the rows of one group."""
      pnl = table["pnl1d"].dropna()
      if len(pnl) < int(params.get("min_trades", 2)):
          return 0.0
      return float(pnl.std(ddof=1))
```

`table` is a **pandas DataFrame** holding that group's rows and **only the columns in `inputs`**. The function returns a number (an int or float; `None` or NaN is shown as blank; infinity or
anything else is `DRS-9071`). Which columns make a group comes from where the measure is used (section 5): the dataset's `by`.

**Visual.** `config/bi/python/pnl-histogram.python.yaml`:

```yaml
interface: visual
output: echarts                  # echarts (an option), svg or png
inputs: [pnl1d]
packages: [numpy]
params: { bins: 20 }
source: |
  import numpy as np

  def render(table, params):
      pnl = table["pnl1d"].dropna().to_numpy(dtype=float)
      counts, edges = np.histogram(pnl, bins=int(params.get("bins", 20)))
      ...
      return {"title": {...}, "xAxis": {...}, "yAxis": {...}, "series": [{"type": "bar", "data": [int(c) for c in counts]}]}
```

`table` is a DataFrame of the rows the visual hands over (the columns of its `columns` well; the row `id` is dropped). The function returns **data, never markup**: an ECharts option
drawn by the console's own ECharts (any `formatter`, `valueFormatter`, `extraCssText` and `appendToBody` key is removed: those are the places an option can carry HTML), an SVG (refused
if it holds `<script`, `javascript:`, `<foreignObject`, `<iframe`, `<image`, `xlink:href` or an `on...` handler; shown as an image, which never runs script) or PNG bytes. Colours you leave
out (text, axes, grid lines) take the page's theme, so a chart reads in the dark and the light theme alike. *Vega-Lite specs are not built (section 12).*

## 3. The manifest

The body of a `python` item is YAML:

| Key | Required | Meaning |
|---|---|---|
| `python` | yes | the item's name (letters, digits, hyphen, underscore) |
| `interface` | yes | `measure`, `visual` or `transform` (section 13) |
| `source` | yes | the code; up to 65,536 characters; it must define the entry function |
| `entry` | no | the function's name: `compute` for a measure, `render` for a visual, `run` for a transform (a top-level function, or a method of a class `Transform`) |
| `inputs` | measure: yes; transform: yes | a measure: the columns the function reads, as the table names them (`pnl1d`, not `trade.pnl1d`); a transform: the **datasets** it reads, in the order its function receives their frames (section 13) |
| `packages` | no | what the source imports; each must be on the allow-list (`drishti.bi.report.python-packages`, console `bi.python.packages`) |
| `target` | no | `server` (default), `browser` or `both`. A browser target cannot need a package Pyodide lacks (it has numpy, pandas, scipy, statsmodels, not pyarrow) |
| `timeout` | no | seconds, 1 to `drishti.bi.report.python-max-seconds` (60); default 10 |
| `memory` | no | megabytes the code may add to what Python needs to start, 32 to `python-max-memory-mb` (1024); default 256 |
| `output` | visual | `echarts` (default), `svg` or `png`; a measure's output is `number`, a transform's `frame` |
| `params` | no | defaults the function reads from `params`; a visual of a report overrides them in `format.params` |
| `title`, `description`, `owner`, `tags`, `endorsement`, `audience`, `sample`, `version` | no | the catalogue's keys, as for any item |

Anything else is refused with `DRS-9070` and the path of the key. The server checks the manifest ( `POST /api/v1/bi/python/validate`, and on every publish); the **console** adds the checks
that need Python: the source parses, defines its entry, and imports only what it lists or the safe standard library (`POST /bi/python/check`, the **Check the Python** button on the item page):

![An item page with its Check](img/bi-python/item-check.png)

## 4. Python items are BI items

A Python item lives where every BI item lives: **shipped** in `config/bi/python/<id>.python.yaml` (a published, read-only item, `file:python:<id>`, watched live), or **in a workspace** (create
it with kind *python*). Nothing about governance is special:

* every save is a saved state (diff, restore); **publish** checks the manifest and refuses a bad one with `DRS-9070`; a workspace that requires review needs a second person to approve it
  ([BI_GOVERNANCE.md](BI_GOVERNANCE.md));
* a report that names the item runs **what was published** for every viewer; someone who edits the item (a contributor or better in its workspace) runs the **draft**, so a change can be tried
  before it is published; the run line under the visual shows the version;
* it has an owner, tags, endorsement and an audience like any item, and appears in the catalogue (`kind=python`) and in bundles ([BI_GOVERNANCE.md](BI_GOVERNANCE.md) section 7).

A report **names an item** by catalogue id, shipped file name (`pnl-histogram`) or title. Anyone who can open a published report can have its Python items run for them: the report is what
names the item, as it is what names the dataset.

## 5. Using them: a Python measure and the python visual

**A Python measure in a dataset** ([DATASETS.md](DATASETS.md) section 4.2): a measure whose definition is not an expression but an item, the columns it reads and the groups it makes:

```yaml
measures:
  Desk P&L volatility:
    python: desk-pnl-volatility      # the item
    inputs: [trade.pnl1d]            # columns of ONE table
    by: [trade.desk]                 # one number per desk
```

The model checks the item is named, `inputs` is not empty, every name is a column, and all of them are of one table (`DRS-9012`); it also warns (`DRS-9013`) that **a query that names the measure is
refused**: the SQL engine cannot run Python, so the measure is shown by a python visual (below), not mixed into a bar chart's SQL measures.

**The `python` visual** (catalogue group *Python*; wells `columns` and `values`):

```yaml
- id: volatility
  type: python
  title: Desk P&L volatility (Python measure)
  wells: { values: [Desk P&L volatility] }        # a Python measure of the dataset: its columns and groups come from the dataset
  format: { run: auto }

- id: histogram
  type: python
  wells: { columns: [trade.desk, trade.pnl1d] }   # the rows handed to Python
  format: { python: pnl-histogram, params: { bins: 24 }, run: auto }
```

| Key | Meaning |
|---|---|
| `wells.values` | one Python measure of the dataset; the rows are its `by` and `inputs` columns |
| `wells.columns` | the columns handed to a Python item named in `format.python` (any number of fields of one table) |
| `format.python` | the item; required unless the values well holds a Python measure. With a measure item and no dataset measure, the groups are the columns that are not `inputs` |
| `format.params` | overrides the item's `params` |
| `format.run` | `auto` (default), `server` or `browser` (section 6) |

The rows are the table's own rows (not grouped), under the page's slicers, filters and business date, at most `drishti.bi.report.python-rows` (10,000). The report **check** (`DRS-9030` to `9035`)
refuses a python visual with neither an item nor a Python measure, with a values well that is not a Python measure, or without columns. In the designer the visual is under *Python*; its Format pane takes the item, *Run on* and the
parameters; the canvas shows a placeholder, since Python runs when a report is opened, not while designing.

## 6. Where it runs

| `format.run` | What happens |
|---|---|
| `server` | the console's runner (below) |
| `browser` | Pyodide in a Web Worker of the page: the item must allow it (`target: browser` or `both`) |
| `auto` | the server first; when the server cannot run it (the runner is off or not installed, `DRS-9074`, or the item is for the browser only) and the browser can, the browser |

**Server.** The console starts a **worker subprocess per call** (`python -I core/pyrunner_worker.py`), at most `bi.python.workers` (2) at once; a call waits `bi.python.queue_seconds` (5) for a place,
then `DRS-9074`. The worker reads the Arrow stream the server built for this person, runs the function, and writes a small JSON answer, size-limited. Nothing is kept between calls: no worker idles,
and there is no state to leak between people. Nothing extra is deployed: it is the console's own Python, with `pandas`, `numpy` and `pyarrow` installed (`requirements.txt`).

**Browser.** The page loads Calc's worker (`/static/js/calc-worker.js`, Pyodide from `/pyodide/<version>/`, on this origin, never a CDN) **the first time a browser run is needed**, fetches the visual's own
JSON query (the rows this person already may see), and runs `static/calc/bi_driver.py` with the item in it. The **report page keeps the strict content-security policy**: only the worker's response
carries `'wasm-unsafe-eval'` (never `'unsafe-eval'`), as for Calc. Pyodide must be installed (`tools/fetch-pyodide.sh`); otherwise a browser run answers `DRS-9074` naming it. The browser's own
sandbox is the boundary; the manifest's `memory` is not enforced there, and `timeout` stops the worker.

![The same report, run in the browser](img/bi-python/report-browser.png)

The line under each visual says where it ran: `Python · desk-pnl-volatility v1 · browser · 3279 ms · 60 rows` (the first browser run loads the interpreter; later ones are as quick as the server's).

## 7. The sandbox, exactly

The server target is **defence in depth, not a kernel sandbox**; the author is a reviewed colleague, the code is published through review, and the boundary stops the common ways to hurt or leak. What is enforced:

| What | How | Test |
|---|---|---|
| a separate process | `python -I` (isolated mode), a new session so a timeout kills the whole group, an **empty environment** (no tokens, no proxies; only `PATH`, a scratch `HOME` and thread limits) | `test_pyrunner.py` |
| no network | the worker runs in a **network namespace with no interfaces** when the machine allows it (`unshare --user --net`; `network_namespace: auto`, `require` refuses to run without it), and an **audit hook** refuses every `socket.*` event whatever the namespace says | `test_no_network`, `test_a_socket_is_refused_even_through_a_library` |
| a scratch folder only | the working directory is a fresh temporary folder, removed afterwards; the audit hook refuses a **write** anywhere else, and a **read** outside the Python installation and that folder (so not `/etc/passwd`, not the console's files) | `test_files_outside_the_scratch_folder_are_refused` |
| no processes, no loader | the audit hook refuses `subprocess`, `os.system`, `exec*`, `spawn*`, `fork`, `ctypes` loading | `test_processes_and_other_packages_are_refused` |
| CPU time | `RLIMIT_CPU` = `timeout` seconds (after start-up), and the parent kills the group at `timeout + 3` s wall clock: `DRS-9072` (HTTP 504) | `test_wall_clock_and_cpu_limits_kill_the_worker` |
| memory | `RLIMIT_AS` = what Python needs to start + `memory` MB: allocation fails or the process dies: `DRS-9072` (413) | `test_memory_limit` |
| output | the answer is at most `bi.python.max_output_bytes` (1,000,000), files at most four times that: `DRS-9072` (413); the input at most `max_input_bytes` | `test_output_limit`, `test_input_limit_and_busy_pool_and_switched_off` |
| packages | the manifest's `packages` must be on the console's allow-list (and installed) **before a worker starts**; the author's own `import` goes through a guard that allows only those and a short list of safe standard-library modules (`math`, `statistics`, `json`, `datetime`, `re`, `collections`, `itertools`, `functools`, `typing`, `decimal`, `fractions`, `random`, ...); `exec`, `eval`, `compile` are not given | `test_package_allow_list_is_enforced_before_a_worker_starts` |
| the input is what the reader may see | the worker receives only the Arrow stream the server built for this person, and the function only the columns of `inputs` | `test_the_function_sees_only_the_declared_columns` |
| a pool | `bi.python.workers` at once, a bounded wait, `DRS-9074` when full | `test_input_limit_and_busy_pool_and_switched_off` |

Honestly: pure-Python guards (the import guard, the audit hook) can be got round by determined hostile code (the tests include one that reaches `__import__` through a library's own builtins and is still
stopped by the hook). That is why review before publish matters, why the process has nothing to steal (no environment, no network, no files), and why a site that wants a harder wall runs the console in a
container with the usual limits. `GET /bi/python/status` says which isolation is on (`network namespace + audit hook`, or `audit hook`).

## 8. Who sees what

* The rows are the server's answer for **this person**: their row rules, their masks, their business date. A masked column arrives masked (`X-Bi-Masked` lists it; the visual's line says *masked*); Python never receives the real value, so it
  cannot reveal it. The result is derived from those rows only.
* A **traceback** is shown to people with the `bi-author` power; everyone else sees the code and the message (`DRS-9071 ZeroDivisionError: division by zero`) without the source lines.
* Which item runs is decided by the **server** (the report's own dataset and item resolution, as the dataset is); the console passes on the person's identity and decides nothing about access.
* Every non-GET call takes JSON from this console's own pages only (same-origin check and `application/json`, `DRS-5002` / `DRS-5001`): [API_GUIDE.md](API_GUIDE.md).

## 9. Settings

**Server** (`drishti.bi.report.*`, [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibireport--reports-phase-3)): `python-rows` (10,000), `python-packages` (the manifest allow-list), `python-max-seconds` (60), `python-max-memory-mb` (1024).

**Console** (`bi.python.*` in `application.yaml`, [CONFIGURATION.md](../admin/CONFIGURATION.md)):

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_BI_PYTHON_ENABLED`) | off: the server target answers `DRS-9074`; the browser target still works |
| `workers` | `2` | processes at once |
| `queue_seconds` | `5` | how long a call waits for a place |
| `packages` | pandas, numpy, pyarrow, scipy, statsmodels | the allow-list; one not installed is refused when used. Keep it equal to the server's `python-packages` |
| `max_timeout_seconds` / `max_memory_mb` | `60` / `1024` | the most a manifest may ask for |
| `max_input_bytes` / `max_output_bytes` | `16000000` / `1000000` | the size of the rows in and the answer out |
| `max_groups` | `5000` | most groups a measure is called for |
| `network_namespace` | `auto` | `auto`, `require`, `off` |
| `scratch_dir` | system temp | where scratch folders are made |

## 10. Errors: DRS-9070 to DRS-9074

| Code | HTTP | Meaning | Usual cause |
|---|---|---|---|
| `DRS-9070` | 422 | a Python item or its use is not valid | a manifest key, a package off the list, no entry function, a python visual with no item, an item for the browser only asked to run on the server |
| `DRS-9071` | 422 | the code failed | an exception (the message names it; authors also get the traceback), a measure that returned something that is not a number, a visual that returned neither an option, an SVG nor a PNG |
| `DRS-9072` | 413 or 504 | a limit stopped it | over `timeout` (504), over `memory`, input or output size (413) |
| `DRS-9073` | 403 | the sandbox refused something | an `import` that is not on the list, the network, a file outside the scratch folder, a process |
| `DRS-9075` | 422 | a Transform's result is not usable | a column the derived dataset declares is missing from the frame, or the Transform's `inputs` are not the derived dataset's inputs (a Transform only: section 13) |
| `DRS-9074` | 503 | no runner | all workers busy for `queue_seconds`, the runner switched off, `pandas` or `pyarrow` not installed in the console's environment, a package not installed, Pyodide not installed for a browser run |

Real output (a measure on 60 trades, then the failures, from `core/pyrunner.py`):

```
{"kind": "measure", "by": ["desk"], "rows": [["DESK-EQD", 0.0], ["DESK-FI", 2043.0156738209967], ["DESK-FX", 2043.0156738209967]], "ms": 213}
DRS-9071 422 ZeroDivisionError: division by zero
  File "<python-item>", line 2, in compute
DRS-9072 504 'desk-pnl-volatility' used more than its 1 s of CPU time and was stopped
DRS-9073 403 Denied: import of 'socket' is not allowed here (this item may use: __future__, bisect, ..., pandas, ...); list the package in the manifest's packages
```

`GET /bi/python/status`:

```
{"enabled": true, "ready": true, "missing": [], "workers": 2, "running": 0, "packages": ["pandas", "numpy", "pyarrow", "scipy", "statsmodels"],
 "installed": ["pandas", "numpy", "pyarrow", "scipy"], "calls": 0, "failures": 0, "isolation": "network namespace + audit hook", "browser": {...}}
```

More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## 11. Measured

On the development machine (a laptop, Python 3.14, pandas 3, 60 rows): a **server call takes about 220 to 320 ms** (a process start and the `pandas` import dominate; the first is the slowest), 214 to 250 ms warm. The design target was
under 50 ms: **not met**, because each call starts a worker; a warm pool is the way to get there and is not built (section 12). A **browser run** loads the interpreter, numpy and pandas on the first use: both visuals of the sample report showed about 3.3 s on a first load here (the second waits for the interpreter the first loads; the
runs themselves are the same code as the server's, on 60 rows). The Chromium test ([test_bi_python_browser.py](../../drishti-console/tests/test_bi_python_browser.py)) checks that both targets give the same figures as a computation from the same trades.

## 12. Built and not built

Built: the Measure and Visual interfaces; Python items as BI items (versions, review, publish, shipped files, bundles); the manifest and its checks (server and console); Python measures in datasets; the
`python` visual in reports and the designer; the sandboxed server runner with a pool; the browser target in Calc's Pyodide worker; the shipped samples.

Built with several datasets (section 13): the **Transform** interface, run on the server as the reader's masked frames and published as a derived dataset ([DATASETS.md section 11.2](DATASETS.md#112-derived-datasets-a-python-transform)), kept per reader and version of the inputs; Analyse promotes a join cell to one.

Not built, and not faked: the **Source** interface (RUPAKA.md section 13.1; rows from a Python-only source such as an API); a Python measure **inside** a SQL query or a bar chart's measures (a query that names one is `DRS-9013`);
**tests shipped with an item** and run by the check (the check parses and lints, it does not run your examples); **Vega-Lite** specs; Python on **boards**, in **alerts**, **subscriptions** and **embedded** reports (an export of a python visual is the table of rows handed to Python); a
**warm worker pool** and a **separate runner service** (`bi.python.runner-url`: RUPAKA.md section 17); a **kernel-level** sandbox (seccomp, containers: use the platform's); the `drishti_bi` client, **PygWalker** and notebooks in Calc; **R**; `scikit-learn` and `polars` in the
allow-list (add them to `bi.python.packages` and the server's `python-packages` when installed).


## 13. Transforms: a table in, a table out

The third interface, after the Measure and the Visual, is the **Transform**: Python that turns the frames of one or more datasets into **a table**, which a **derived dataset** ([DATASETS.md section 11.2](DATASETS.md#112-derived-datasets-a-python-transform)) publishes as its one table, with measures declared in YAML over it. Use it where a join or a reshaping is easier in pandas than in the dataset language (a merge with a tolerance, a rank, a fitted column, a pivot), and you still want the result to be a dataset every report, alert and subscription can use. The design is [RUPAKA.md sections 13.1 and 20.10](../architecture/RUPAKA.md).

```yaml
rachana: 1
python: pnl-vs-exposure
interface: transform            # a table in, a table out
inputs: [trading-risk, counterparty-exposure]     # the DATASETS it reads, in the order its function receives their frames
packages: [pandas]
target: server                  # a Transform runs on the server and its result is kept for the reader
timeout: 20
memory: 512
params: { floor: 1.0 }          # defaults; the derived dataset may override them (derived.params)
source: |
  def run(pnl, exposure, params):
      m = pnl.merge(exposure, on="counterparty", how="left")
      m = m.rename(columns={"Desk P&L": "desk_pnl", "Exposure (EE peak)": "ee_peak"})
      m["pnl_per_exposure"] = m["desk_pnl"] / m["ee_peak"].clip(lower=float(params.get("floor", 1.0)))
      return m[["counterparty", "name", "sector", "desk_pnl", "ee_peak", "pnl_per_exposure"]]
```

**The contract.**

| | |
|---|---|
| function | `run(*frames)`: one **pandas DataFrame** per input, in the order of `inputs`. A function that has a parameter named `params` receives the parameters (the manifest's `params` over the derived dataset's); or a class named `Transform` with a method `run(self, *frames)`, which finds the parameters in `self.params` |
| frames | what the reader may see: each input is a question put to a dataset **as the reader** (`by` + `measures`, or `fields`), so row rules and masks are already applied and a masked column is null. Columns are named by the derived dataset's `inputs` (`as`, else the last part of the field) |
| returns | a DataFrame: no more than `drishti.bi.transform.max-rows` rows; column names of letters, digits and underscore; no duplicate names. Anything else is `DRS-9071` |
| column types | typed from the frame: `int` is `integer`, `float` and decimals are `decimal`, `bool` is `boolean`, dates and timestamps are `date` and `timestamp`, everything else `string` |
| `inputs` (manifest) | datasets (a catalogue id, a file name, the dataset's name), not columns: the derived dataset's inputs must be the same datasets in the same order (`DRS-9075` otherwise) |
| `target` | `server` only: the browser has no business making a table the server keeps |

**Where it runs, and who sees what.** The server asks each input as the reader, writes the answers as Arrow files and starts the console's runner (`drishti-console/core/transform_cli.py`, which uses `core/pyrunner.py` exactly as a Python measure does): `python -I`, an empty environment, a scratch folder, no network, CPU, memory, wall-clock and output limits, an audit hook, the import guard. The frame comes back as a Parquet file the server keeps for **that reader** and the version of the inputs (`drishti.bi.transform.cache-ttl`, `cache-entries`). The code never sees anything the reader may not, and the result is derived only from it; another reader gets their own run. Real output of the sample's first and second run (an author who may read every row):

```
Python Transform pnl-vs-exposure: 18 rows in 218 ms (first run); the same reader again: kept, 0 ms
```

**Checks.** The server checks the manifest on validate and publish (`DRS-9070`): `interface` is `measure`, `visual` or `transform`; a transform needs `inputs` naming datasets, defines `run` (a top-level function or the method of a class `Transform`), and `target` is `server`. The console adds the source checks (`POST /bi/python/check`: it parses, defines its entry, imports only what it lists). `DRS-9071` to `9074` are the run's, as for any Python item (section 10); `DRS-9075` is a frame that lacks a column the derived dataset declares or inputs that do not match.

**From Analyse.** A notebook that joins several extracts in pandas ([ANALYSE.md section 2](ANALYSE.md#2-the-extract)) can **Promote to a Transform**: each `extract("name")` becomes a parameter of `run`, the cell's `result` is returned, and the inputs are the extracts' datasets in order; the draft is then checked and published, and a derived dataset is written over it.

![The derived sample dataset: its sources, Run the Transform now and the columns it returned](../tutorials/img/t14/12-derived-dataset.jpg)

Settings: `drishti.bi.transform.*` in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibitransform--python-transforms-and-composed-datasets). The sample is `config/bi/python/pnl-vs-exposure.python.yaml` with the derived dataset `desk-pnl-vs-exposure-py`.
