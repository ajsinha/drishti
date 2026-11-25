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
# The Notebook and Python

> Commands: Linux and macOS `python3 tools/drishti.py X`; Windows `py -3.13 tools\drishti.py X` (same arguments).

<!-- nav:begin -->
Business intelligence: [Index](README.md) · [Pivot](PIVOT.md) · [Pivot at scale and the risk cubes](PIVOT_SCALE_AND_CUBES.md) · [Tables and models](TABLES_AND_MODELS.md) · [Reports and the designer](REPORTS.md) · [The report file, formatting and statements](REPORT_FILE_FORMAT_STATEMENTS.md) · [Models and datasets for reports](TABLES_AND_MODELS.md#models-and-datasets-for-reports) · [Live reports, boards and background runs](LIVE_REPORTS_AND_BOARDS.md) · [Distribution, subscriptions and alerts](DISTRIBUTION_AND_ALERTS.md) · [Governing BI and AI in BI](GOVERNANCE_AND_AI.md) · **The Notebook and Python** · [Groupings and analytics](GROUPINGS_AND_ANALYTICS.md)
<!-- nav:end -->

Where code runs; the Notebook (SQL and Python cells, extract, save, promote, profile); Python measures and visuals; Transforms and the Transform editor; the command line, the sandbox, limits and how an administrator turns Python on; with the tutorial.

## The Notebook and Python

Drishti runs code people write in two places only: **SQL on the server** and **Python on the server's compute pool**. This guide is
how to use both: the **Notebook** (SQL and Python cells over the rows you may see), **Python measures and visuals** in reports,
**Transforms** and the **Transform editor** for data prep, the command line, the limits and errors, and what an administrator does to
turn Python on. How the runtime is built (the pool, its workers, the sandbox, the lock file) is in
[ARCHITECTURE.md](../../architecture/ARCHITECTURE.md) section 29. Reports themselves are in [BI_GUIDE.md](README.md); a
walk-through of everything here is [the Notebook tutorial](NOTEBOOK_AND_PYTHON.md).

### Contents

1. [Where code runs](#1-where-code-runs)
2. [The Notebook](#2-the-notebook)
3. [Python measures and visuals](#3-python-measures-and-visuals)
4. [Transforms: a table in, a table out](#4-transforms-a-table-in-a-table-out)
5. [Data prep: the Transform editor](#5-data-prep-the-transform-editor)
6. [On the command line](#6-on-the-command-line)
7. [The sandbox and who sees what](#7-the-sandbox-and-who-sees-what)
8. [Limits and errors](#8-limits-and-errors)
9. [For administrators: turning Python on](#9-for-administrators-turning-python-on)
10. [Calc: Python in your own browser](#10-calc-python-in-your-own-browser)

### 1. Where code runs

#### 1.1 One Python runtime, on the server

Every Python run in Drishti is the server's, on its compute pool **`python`**: a Notebook cell, a python visual, a Python measure of a
report, board or alert, a Transform behind a Python dataset, a Python-driven table, the Transform editor's step-by-step run, the profile
of a frame and the tests an item ships with. The console runs none and the browser runs none: the page asks and draws. The server reads
the rows **as the person running the code** (their row rules, their masks, their business date) and hands them with the code to the
pool, which runs it in a strict sandbox (no network, its own folder, time and memory limits, pinned libraries:
[section 7](#7-the-sandbox-and-who-sees-what)).

```
 report visual (type: python) ──asks──▶ console ──▶ server: the item to run, and the rows this person may see (read as them, masked)
                                                         │
                                                         ▼
                              the compute pool python: a fresh fork child per call, in the strict sandbox
                    (no network at all, files held by the kernel to its own folder, CPU, memory, time and output limits)
                                          │
                   a number per group, a chart (data, never HTML), or a table
```

| You want to | Use |
|---|---|
| explore a model with SQL and pandas, then keep what is good | the **Notebook** ([section 2](#2-the-notebook)) |
| a number the expression language cannot make (a volatility, a percentile, a fitted slope) | a **Python measure** ([section 3](#3-python-measures-and-visuals)) |
| a chart the visual catalogue does not have | a **Python visual** ([section 3](#3-python-measures-and-visuals)) |
| a join or reshaping easier in pandas, published as a dataset | a **Transform** ([section 4](#4-transforms-a-table-in-a-table-out)) |
| clean raw rows step by step, without typing YAML | the **Transform editor** ([section 5](#5-data-prep-the-transform-editor)) |
| a table whose rows a build function makes | a **Python-driven table** ([BI_GUIDE.md](TABLES_AND_MODELS.md#114-python-driven-tables-and-models)) |
| slice and total a table | the **Pivot** ([BI_GUIDE.md](PIVOT.md)), then a report |

Python runs only once an administrator has switched on the compute pool ([section 9](#9-for-administrators-turning-python-on)), and
only on a Linux server; elsewhere every page says why it cannot run.

#### 1.2 The Python console and Calc

The **Python console** is the Notebook: its address `/pyconsole` (and `/bi/explore`) opens `/bi/notebook`, where Python over a
masked extract runs on the server. Python **in your own browser** on a view's rows is a different thing: **Calc**, a scratch pad behind a
Labs switch that never publishes ([USER_GUIDE.md › Calc](#10-calc-python-in-your-own-browser)).

### 2. The Notebook

The **Notebook** (`/bi/notebook`, the BI rail's *Notebook*) is the one place for code over data. You open a model as an **extract**, the
rows *you* may see (masked columns stay masked, row rules apply), and work on it in **SQL cells**, run by the server, and **Python cells**,
run on the server's compute pool. You save the work as a **notebook** (a BI item with versions and review like any other) and **promote**
what is good: a SQL cell becomes a **visual in a report draft**, a Python cell a Python Measure, Visual or Transform draft.

**One analysis path: Pivot, then Report.** To slice and total a table, use the [Pivot](PIVOT.md) (`PIVOT <GO>`, or *Pivot* in the
BI rail); to publish, share or schedule, make a report. The Notebook is for what a grid cannot say. **Nothing of yours runs in the
console or in your browser**: SQL is the server's, Python the server's compute pool's.

#### 2.1 At a glance

```
 model ──(your identity)──▶ server: masks columns, applies your row rules ──▶ extract (at most bi.notebook.max_rows rows)
                                                                                    │
                    ┌──────────────────────────────────────┬────────────────────────┤
                  SQL cell                              Python cell              Profile
   the server: Drishti SQL over the model,      the server's compute pool    every column of the extract,
   as you (POST /bi/notebook/sql)               (POST /bi/notebook/python)   no code of yours
```

The extract is **rebuilt for you on every call** and never kept: the console holds nothing between calls, so a mask or a row rule changed
on the server applies the next time you run a cell.

#### 2.2 The extract

1. Choose a **Model or dataset** (a dataset reads as its own small model).
2. Tick the **Fields**.
3. Choose the **Time**: *Now*, *As of a date* or *A window*.
4. Press **Open the extract**.

The line under the form tells you what you got: `60 rows, 8 columns.` and, when something is masked for you, `Masked for you: MTM.` The
rows are the table's *own* rows (not grouped), cut to `bi.notebook.max_rows` (10,000); a cut says so (`60 rows of 4,000 (cut to the row
limit)`). Columns are named for the table's column (`DESK`, not `trade.DESK`); two columns of the same name keep their table
(`trade_DESK`). The console's route can also open one report visual's data as the extract (`{report, page, visual, state}`).

**More extracts.** *More extracts: join several models* (under the form) adds further extracts to the notebook, each a name, a model and
fields, built for you by the server exactly as the first is (your row rules, your masks; at most `bi.notebook.max_extracts`, 6, besides
the first). A Python cell reads one as `extract("name")`.

![The notebook's extracts: the first extract opened, and the second, exposure, under More extracts](../../tutorials/img/t14/13-analyse-extracts.jpg)

Every extract is the reader's, so a join of two extracts shows **their** rows: a join key that the server masks for them arrives masked
and the join finds nothing, which is the mask doing its job. Because the extract is cut, a cell over it gives the extract's totals, not
the model's: for true totals use the Pivot or a report.

#### 2.3 SQL cells

A SQL cell is **Drishti SQL over the model the extract reads** ([ARCHITECTURE.md](../../architecture/ARCHITECTURE.md), section 28), run by
the server **as you**, with your row rules and masks, through the same plan as the model query it equals. Its tables are the model's
aliases, a measure is `MEASURE('name')`, and `JOIN` needs no `ON` (the model's relationships are used). A new cell starts with a
statement written from the model (the first text field you ticked and the model's first measure):

```sql
SELECT trade.CURRENCY,
       MEASURE('Sum of MTM') AS "Sum of MTM"
FROM   trade
GROUP  BY trade.CURRENCY
LIMIT  20
```

The cell's footer says *3 rows in 4 ms · Drishti SQL, run by the server over sql-model@1 as you · plan p_825d3a8857*, with the masked
columns when there are any:

![A SQL cell: the statement above, Run (Drishti SQL, run by the server over the model), and three currencies with their Sum of MTM: EUR -75000, GBP 425000, USD -575000](../img/bi-languages/analyse-dsql-cell.jpg)

The console sends the cell to `POST /bi/notebook/sql` (`{extract, sql}`), which asks the server's `POST /api/v1/bi/sql` with the model and
the cell's row limit (`bi.notebook.sql_max_rows`, one more to say when it was cut); it never reads the SQL itself. One `SELECT` per cell;
text over `bi.notebook.sql_max_chars` (20,000) is `DRS-9072`. *Run all* runs the cells in order. The same SQL runs from the terminal with
`drishti.py bi sql` ([section 6](#6-on-the-command-line)).

#### 2.4 Python cells

A Python cell sees `df` (the extract as a pandas DataFrame), `extract(name)` (any other extract of the notebook, as a DataFrame for the
same reader) and `params`. What it prints comes back as text; what it puts in **`result`** is drawn: a DataFrame or Series as a table, a
dict as an ECharts option, SVG text or PNG bytes as an image, anything else as a value.

```python
g = df.groupby("DESK", as_index=False)["NOTIONAL"].sum()
result = {"title": {"text": "Notional by desk"}, "xAxis": {"type": "category", "data": list(g["DESK"])},
          "yAxis": {"type": "value"}, "series": [{"type": "bar", "data": [float(v) for v in g["NOTIONAL"]]}]}
```

**Run** sends the cell to the server (`POST /bi/notebook/python`), which reads the extract and the other extracts **as you** and runs the
cell on the compute pool `python` in its strict sandbox: no network, files held to its own folder, the cell's time and memory limits
(`bi.notebook.cell_timeout_seconds`, `cell_memory_mb`), the pinned libraries only. The line above the output says where it ran, how long
it took, how many rows it read and what was masked; what the cell printed shows first, then its `result`; a failure shows the error and,
as an author, your own code's traceback. Where the server cannot run Python safely (compute off, a Windows server), *Run* is off and says
why; you can still write, save and promote cells. Whoever opens the notebook later runs them over their own rows.

Running Python needs the **`bi-author`** power (the page says *Running Python needs the bi-author power.*); SQL needs only `bi`. The
packages a cell may import are the pinned allow-list ([section 9.3](#93-the-pinned-libraries-installing-and-locking-the-environment)).

#### 2.5 Saving a notebook

**Save as a notebook** stores the extract's definition and every cell as a BI item of kind **`notebook`**: in *My workspace* (or, if you
opened a notebook you may edit, as a new version of it). It has versions, diffs, review and publishing like any item
([BI_GUIDE.md](README.md)); *Open in the Notebook* on the item page (or `/bi/notebook?item=<id>`, or the notebook picker)
reopens it: the extract is rebuilt for whoever opens it, so **a notebook shared with someone else shows *their* rows**. Outputs are not
stored, only the code.

The body is YAML, checked by `POST /bi/notebook/check` (unknown keys and bad cells are `DRS-9070`, naming the cell):

```yaml
rachana: 1
notebook: desk-notional-first-look
title: Desk notional, first look
dataset: mq:trades-basic@1       # the model it reads (mq:<model>@<major>, or dataset:<name> for a dataset's implicit model)
fields: [trade.TRADE_ID, trade.DESK, trade.PORTFOLIO, trade.NOTIONAL, trade.MTM]
extracts:                       # optional: further extracts, read by a Python cell as extract("name")
  - { name: exposure, dataset: mq:counterparty-exposure@1, fields: [set_exposure.COUNTERPARTY, set_exposure.PEAK_EE] }
cells:
  - type: sql
    source: SELECT trade.DESK, MEASURE('Sum of MTM') FROM trade GROUP BY trade.DESK
  - type: python
    source: |
      result = df.groupby("DESK", as_index=False)["NOTIONAL"].sum()
```

A cell is `sql` or `python`; slicing is the Pivot's, so a notebook saved with a `slice` cell opens with that cell left out and a line
saying so. A notebook may also carry the catalogue's keys (`sample`, `tags`, `endorsement`, `owner`, `audience`); a shipped one is
`config/bi/notebooks/banking/pnl-vs-exposure-pandas.notebook.yaml`. A notebook's cells can also become a Python-driven table's build
function ([BI_GUIDE.md](TABLES_AND_MODELS.md#114-python-driven-tables-and-models)).

#### 2.6 Promote

Every promotion answers **beside the button you pressed** (one message, with an *Open the draft* link). Pressing the same promotion again
for the same cell does not make a duplicate: it offers **Update that draft** or **Save another**. Promoting writes a **draft** item in your
workspace; nothing is published.

* **A SQL cell to a report visual.** Choose where first, in the line *Promote a SQL cell to* above the tabs: **a new report draft**, or
  one of the report drafts you may edit (your workspace and the shared ones you contribute to), and optionally the page. Plain and
  `GROUP BY` columns become *rows*, `MEASURE('name')` and the aggregates `sum`, `avg`, `count`, `min`, `max` of a column become *values*
  when the model has that measure, and simple `WHERE` conditions (`=`, `<>`, `<`, `<=`, `>`, `>=`, `IN`, `NOT IN`, `BETWEEN`,
  `IS [NOT] NULL`, joined by `AND`) become *filters*. What a report cannot say (an expression, `LIKE`, `OR`, `HAVING`, a sub-query,
  `ORDER BY`, `LIMIT`) is left out and listed in the notes; nothing is guessed. A new report draft reads the extract's model; in an
  existing one the visual is added below what the page holds and the report is written again by the designer's writer (comments in its
  YAML are not kept; the note says so).

  ![A SQL cell promoted into an existing report draft: the note says what was left out and that the report was written again](../../tutorials/img/t14/20-promote-existing.jpg)

* **A Python cell to a Measure or a Visual** (the cell's buttons): the cell's `df` becomes the function's `table`, `result` is returned,
  and the packages the cell imports go into the manifest ([section 3.3](#33-the-manifest)). Check it on the item page, then publish.
* **A Python cell that joins extracts to a Transform** (*Promote to a Transform*, greyed with "Add a second extract first" until the
  notebook has one): each `extract("name")` becomes a parameter of `run(...)`, a use of `df` becomes the first extract, and the cell's
  `result` is returned ([section 4](#4-transforms-a-table-in-a-table-out)).

The route is the console's `POST /bi/notebook/promote` with `{to: report, name, dataset, sql, columns, qualified, target?, page?}` or
`{to: measure | visual | transform, name, source, columns, extracts?}`.

#### 2.7 Profile

The **Profile** tab shows every column of the extract: its type, what its text looks like (numbers or dates kept as text), empty values
(count and %), distinct values, whether it could be a key, min and max (and the mean of a number), the five most common values and a
histogram of a number. No code of yours runs (`POST /bi/notebook/profile` with `{extract}`), and it is drawn by the same panel as the
[Transform editor's profile](#55-profiling). A column masked for you says so and profiles as empty.

![The Notebook, Profile tab: the extract of the model prep-model as the analyst ann sees it, PRICE masked for her](../img/bi-transform/07-analyse-profile.png)

#### 2.8 Who sees what in the Notebook

The server decides, as everywhere in BI: the extract is a model query (`POST /api/v1/bi/mq`) **as you**, so the same column masks and row
rules as a report apply, and a masked column arrives empty. A SQL cell is answered by the server as you; a Python cell runs on the pool
over the extract built for you. A cell cannot widen access, and a saved notebook holds code, not data.

#### 2.9 Notebook settings, errors and routes

The console's `bi.notebook.*` settings ([CONFIGURATION.md › `bi.notebook`](../../admin/CONFIGURATION.md#binotebook)) set the most rows of an
extract (`max_rows`, 10,000), its most columns (`max_fields`), a SQL cell's limits (`sql_max_rows`, `sql_max_chars`), a Python cell's
limits (`cell_timeout_seconds`, `cell_memory_mb`, `cell_max_chars`), the most cells (`max_cells`) and the most further extracts
(`max_extracts`).

| Code | HTTP | In the Notebook |
|---|---|---|
| `DRS-9070` | 422 | the request is wrong (no fields, a bad name, a notebook that does not check, a SQL syntax error, a slice sent to promote) |
| `DRS-9072` | 413 | a limit: SQL text, cell size, extract fields or extracts |
| `DRS-9073` | 403 | refused: Python without `bi-author` |
| `DRS-9074` | 503 | Python does not run on this server now (the reason is given), or the console lacks `pyarrow` |

The server's own refusals (`DRS-9001` no such dataset, a denied model) come through unchanged; a Python cell's own failures are
`DRS-9071` to `9074` ([section 8](#8-limits-and-errors)). The routes are the console's own, JSON from its pages only (same-origin, the
session cookie): `GET /bi/notebook`, `GET /bi/notebook/status`, `POST /bi/notebook/extract`, `POST /bi/notebook/sql`,
`POST /bi/notebook/python`, `POST /bi/notebook/profile`, `POST /bi/notebook/check`, `POST /bi/notebook/promote`.

### 3. Python measures and visuals

Where the expression language and the visual catalogue stop, Drishti runs **typed Python**: a **Measure** is a Python function that turns
the rows of a group into a number (a desk's P&L volatility, a percentile no aggregate gives, a fitted slope), and a **Visual** is a Python
function that turns rows into a chart (a histogram with your own bins, a regression plot). Authors do not paste scripts into a visual: a
Python function is a **BI item** of its own (kind `python`), with a manifest, versions, review and publishing like a dataset or a report,
and a report names it.

#### 3.1 At a glance

| | Python measure | Python visual |
|---|---|---|
| function | `compute(table, params)`, or `compute_groups(frame, by, params)` once over every group | `render(table, params)` |
| called | once per group, with that group's rows | once, with all the rows handed over |
| returns | one number (or `None`) | an ECharts option, a Vega-Lite specification or a drishti-chart spec (a dict, by `output:`), SVG text or PNG bytes |
| drawn as | bars, one per group | the chart or image |

Two samples ship (`config/bi/python/`), and the sample report **Desk P&L risk (Python)** (`python-desk-risk`) shows both on the trades
model:

* `desk-pnl-volatility` (a measure): the standard deviation of one-day P&L, per desk;
* `pnl-histogram` (a visual): a histogram of one-day P&L drawn with numpy.

![The sample report, run on the server](../img/bi-python/report-server.png)

#### 3.2 The two interfaces

**Measure.** `config/bi/python/banking/desk-pnl-volatility.python.yaml`:

```yaml
rachana: 1
python: desk-pnl-volatility
title: Desk P&L volatility
interface: measure
inputs: [pnl1d]                  # the columns the function reads, as the table names them
packages: [pandas]
target: server                   # the server's compute pool
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

`table` is a **pandas DataFrame** holding that group's rows and **only the columns in `inputs`**. The function returns a number (an int or
float; `None` or NaN is shown as blank; infinity or anything else is `DRS-9071`). Which columns make a group comes from where the measure
is used ([section 3.5](#35-a-python-measure-in-a-model)): the measure's `by`.

**Visual.** `config/bi/python/banking/pnl-histogram.python.yaml`:

```yaml
interface: visual
output: echarts                  # echarts (an option), vega-lite, drishti-chart, svg or png
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

`table` is a DataFrame of the rows the visual hands over (the columns of its `columns` well; the row `id` is dropped). The function returns
**data, never markup**: an ECharts option drawn by drishti-chart (Drishti Elements' report part; any `formatter`, `valueFormatter`, `extraCssText` and
`appendToBody` key is removed: those are the places an option can carry HTML), an SVG (refused if it holds `<script`, `javascript:`,
`<foreignObject`, `<iframe`, `<image`, `xlink:href` or an `on...` handler; shown as an image, which never runs script) or PNG bytes.
Colours you leave out (text, axes, grid lines) take the page's theme, so a chart reads in the dark and the light theme alike.

#### 3.3 The manifest

The body of a `python` item is YAML:

| Key | Required | Meaning |
|---|---|---|
| `python` | yes | the item's name (letters, digits, hyphen, underscore) |
| `interface` | yes | `measure`, `visual`, `transform` ([section 4](#4-transforms-a-table-in-a-table-out)) or `column` ([section 3.7](#37-grouped-measures-and-the-column-interface)) |
| `source` | yes | the code; up to 65,536 characters; it must define the entry function |
| `entry` | no | the function's name: `compute` for a measure, `render` for a visual, `run` for a transform (a top-level function, or a method of a class `Transform`) |
| `inputs` | measure, transform: yes | a measure: the columns the function reads, as the table names them (`pnl1d`, not `trade.pnl1d`); a transform: the **datasets** it reads, in the order its function receives their frames |
| `packages` | no | what the source imports; each must be on the allow-list (`drishti.bi.report.python-packages`) **and pinned** in `config/compute/locks/python.lock` |
| `target` | no | `server` (default). `browser` and `both` are read and run on the server's pool like `server` |
| `timeout` | no | seconds, 1 to `drishti.bi.report.python-max-seconds` (60); default 10 |
| `memory` | no | megabytes the code may add to what Python needs to start, 32 to `python-max-memory-mb` (1024); default 256 |
| `output` | visual | `echarts` (default), `vega-lite`, `drishti-chart` ([section 3.8](#38-vega-lite-and-drishti-chart-output)), `svg` or `png`; a measure's output is `number`, a transform's `frame` |
| `params` | no | defaults the function reads from `params`; a visual of a report overrides them in `format.params` |
| `tests` | no | examples the item must pass: run by Check, and a failing one refuses a publish ([section 3.9](#39-tests-shipped-with-an-item)) |
| `title`, `description`, `owner`, `tags`, `endorsement`, `audience`, `sample`, `version` | no | the catalogue's keys, as for any item |

Anything else is refused with `DRS-9070` and the path of the key. The server checks the manifest (`POST /api/v1/bi/python/validate`, and
on every publish); **Check the Python**, the button on the item page (the console's `POST /bi/python/check`, the server's
`POST /api/v1/bi/python/check`), adds the checks that need Python's own parser, made on the compute pool without running anything: the
source parses, defines its entry, and imports only what it lists or the safe standard library.

![An item page with its Check](../img/bi-python/item-check.png)

#### 3.4 Python items are BI items

A Python item lives where every BI item lives: **shipped** in `config/bi/python/<id>.python.yaml` (a published, read-only item,
`file:python:<id>`, watched live), or **in a workspace** (create it with kind *python*, or `drishti.py bi item new`). Nothing about
governance is special:

* every save is a saved state (diff, restore); **publish** checks the manifest and refuses a bad one with `DRS-9070`; a workspace that
  requires review needs a second person to approve it ([BI_GUIDE.md](README.md));
* a report that names the item runs **what was published** for every viewer; someone who edits the item (a contributor or better in its
  workspace) runs the **draft**, so a change can be tried before it is published; the run line under the visual shows the version;
* it has an owner, tags, endorsement and an audience like any item, and appears in the catalogue (`kind=python`) and in bundles.

A report **names an item** by catalogue id, shipped file name (`pnl-histogram`) or title. Anyone who can open a published report can have
its Python items run for them: the report is what names the item, as it is what names the dataset.

#### 3.5 A Python measure in a model

A measure may be a **Python item** instead of an expression: `python:` names the item (catalogue id, shipped file name or title), `inputs`
the columns its function reads and `by` the columns that make a group, all `table.column` and all of **one table**:

```yaml
measures:
  Desk P&L volatility:
    python: desk-pnl-volatility       # config/bi/python/banking/desk-pnl-volatility.python.yaml
    inputs: [trade.PNL_1D]            # columns of ONE table
    by: [trade.DESK]                  # one number per desk
    description: Standard deviation of the one-day P&L per desk, computed in Python.
```

The SQL engine **cannot run it**: a query that names a Python measure is refused (`DRS-9013`), and the model says so as a warning when it is
checked, so the measure is shown by a **python visual** (below), not mixed into a bar chart's SQL measures. The server reads the rows of
`by` and `inputs` that this person may see, and the pool calls the item once per group. Checks: the item is named, `inputs` is not empty,
every name is a column, all of one table (`DRS-9012`); `expr` and `python` together are `DRS-9010`. The model is checked **without**
looking the item up (an item can be published after it), so a wrong item name appears when a report runs it (`DRS-9001`/`9070`). The
keys of a model's measures are in [BI_GUIDE.md](TABLES_AND_MODELS.md).

#### 3.6 The python visual in a report

The **`python` visual** (designer, catalogue group *Python*; wells `columns` and `values`):

```yaml
- id: volatility
  type: python
  title: Desk P&L volatility (Python measure)
  wells: { values: [Desk P&L volatility] }        # a Python measure of the model: its columns and groups come from the measure
  format: { run: auto }

- id: histogram
  type: python
  wells: { columns: [trade.DESK, trade.PNL_1D] }   # the rows handed to Python
  format: { python: pnl-histogram, params: { bins: 24 }, run: auto }
```

| Key | Meaning |
|---|---|
| `wells.values` | one Python measure of the model; the rows are its `by` and `inputs` columns |
| `wells.columns` | the columns handed to a Python item named in `format.python` (any number of fields of one table) |
| `format.python` | the item; required unless the values well holds a Python measure. With a measure item and no model measure, the groups are the columns that are not `inputs` |
| `format.params` | overrides the item's `params` |
| `format.run` | `auto` (default), `server` or `browser`: every value runs on the server's compute pool; with `auto` a server that cannot run Python safely answers `DRS-9074` and says why |

The rows are the table's own rows (not grouped), under the page's slicers, filters and business date, at most
`drishti.bi.report.python-rows` (10,000). The report **check** refuses a python visual with neither an item nor a Python measure, with a
values well that is not a Python measure, or without columns (`DRS-9032`). A python visual has no drill, selection, alert or *show the
rows* buttons; an export of it is the table of rows handed to Python. In the designer its Format pane takes the item and the parameters;
the canvas shows a placeholder, since Python runs when a report is opened, not while designing. The line under each visual says the server
ran it: `Python · desk-pnl-volatility v1 · server · 240 ms · 60 rows`.

![The sample report with its Python measure and Python visual](../img/bi-python/report-browser.png)

#### 3.7 Grouped measures and the column interface

* **`compute_groups(frame, by, params)`.** A measure may define it instead of `compute`: one vectorised call over every group's rows that
  returns a DataFrame of the group columns and one value column (`value`, else the last). Faster for many groups:

  ```python
  def compute_groups(frame, by, params):
      out = frame.groupby(by, dropna=False)["pnl1d"].std().reset_index()
      return out.rename(columns={"pnl1d": "value"})
  ```

* **The `column` interface.** `derive(frame, params)` returns one value per row (a Series, an array or a list), for a column written
  `derive: { python: … }` (a calculated column computed in Python).

#### 3.8 Vega-Lite and drishti-chart output

A visual may return a **Vega-Lite** specification instead of an ECharts option: say `output: vega-lite` in the manifest and return the
specification as a dict (`mark`, `encoding`, `layer`, `transform` ... as [Vega-Lite](https://vega.github.io/vega-lite/) has them). The sample
`config/bi/python/banking/pnl-vega.python.yaml` draws one dot for each trade's one-day P&L, by desk, with each desk's median as a tick:

```python
def render(table, params):
    rows = table.dropna(subset=["pnl1d"]).head(int(params.get("max_points", 400)))
    dots = [{"desk": str(r.desk), "pnl1d": float(r.pnl1d)} for r in rows.itertuples()]
    medians = [{"desk": str(d), "median": float(v)} for d, v in rows.groupby("desk")["pnl1d"].median().items()]
    return {"layer": [
        {"data": {"values": dots}, "mark": {"type": "circle", "opacity": 0.6, "size": 40}, "encoding": {"x": {"field": "pnl1d", "type": "quantitative"}, "y": {"field": "desk", "type": "nominal"}}},
        {"data": {"values": medians}, "mark": {"type": "tick", "thickness": 3, "color": "#d9534f"}, "encoding": {"x": {"field": "median", "type": "quantitative"}, "y": {"field": "desk", "type": "nominal"}}}]}
```

![The sample Python visual that returns Vega-Lite: a dot for each trade, a tick for each desk's median](../../tutorials/img/t14/17-python-vega.jpg)

What comes back from the worker is data, as with ECharts: `{"kind": "vega-lite", "spec": {...}, "ms": 258}`. The page has no Vega:
drishti-chart translates the specification (`fromVegaLite`) and draws it, and a feature it does not translate is refused in words with what
to use instead (`DRS-9077`; the list is with the report's own Vega-Lite visuals in [BI_GUIDE.md](README.md)). The rules:

* **The data is inside the specification** (`data.values`). A `url` or a `loader` anywhere in it is refused, in the sandbox (`DRS-9073`: "a
  Vega-Lite specification may not load anything from a URL or name a loader") and again in the page. An expression such as
  `datum.MTM < 0` is read by drishti-chart's expression language, **without `eval`**, so the page keeps its strict content security policy.
* The chart takes the theme's colours and fills its frame (`width` and `height` are ignored).

**The preferred form: a drishti-chart spec.** A visual whose manifest says `output: drishti-chart` returns a drishti-chart spec (a dict)
with its rows inline, and the page draws it as it is: every kind drishti-chart has (bar, line, area, scatter, bubble, histogram, boxplot,
heatmap, pie, donut, waterfall, treemap, sankey, gauge, candlestick, map, layers), its annotations and conditional marks, nothing to
translate and nothing refused. The answer is `{"kind": "drishti-chart", "spec": {...}}`; a spec without `kind` is a `TypeError`, and
`data.source` (an address to load) is refused in the sandbox (`DRS-9073`): put the rows in `data.rows`. The spec's language is in
[ELEMENTS_API.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/API.md) (drishti-chart).

```python
def render(table, params):
    by_desk = table.groupby("DESK", as_index=False)["PNL_1D"].sum()
    return {"kind": "bar", "title": "One-day P&L by desk", "data": {"rows": by_desk.to_dict("records")},
            "x": {"field": "DESK"}, "y": {"field": "PNL_1D", "format": "amount0"},
            "colourWhen": [{"when": "datum.PNL_1D < 0", "tone": "bad"}]}
```

The answer is at most 4 MB. A report can also hold a Vega-Lite visual with **no Python at all** (`type: vega-lite`,
[BI_GUIDE.md](README.md)).

#### 3.9 Tests shipped with an item

A Python item may carry `tests:`: examples of what its function must return. **Check** runs them on the server's compute pool, one request
each (the same as a report's run), and shows each as passed, failed or could not run; a **publish** is refused while one fails.

```yaml
tests:
  - name: the standard deviation of one desk
    input: { columns: [pnl1d], rows: [[1.0], [2.0], [3.0], [4.0]] }      # a frame written in the test
    expect: 1.2909944
    tolerance: 0.000001
  - name: a desk of its own in a grouped call
    input: { columns: [desk, pnl1d], rows: [[FX, 1.0], [FX, 3.0], [RATES, 10.0], [RATES, 14.0]] }
    by: [desk]                                                                # a measure: the group columns
    expect: { FX: 1.4142136, RATES: 2.8284271 }                              # a number for each group
    tolerance: 0.000001
```

A test may read rows of a dataset instead of a frame it carries: `sample: { dataset: trades-basic, fields: [trade.DESK, trade.PNL_1D], limit: 200 }`.

| Key | Meaning |
|---|---|
| `name` | required, unique in the item |
| `input` | the frame: `{columns: [...], rows: [[...]]}` (up to 1,000 rows). For a **transform**, a mapping of each input dataset to its frame |
| `sample` | instead of `input`: `{dataset, fields: [table.column, ...], limit}` (at most 1,000 rows). The rows are the ones the **person running the test** may see (row rules and masks apply), so such a test can pass for one person and fail for another; a sample is for a check against real data, an `input` for a stable one |
| `params` | values over the item's defaults |
| `by` | a measure: the group columns; without it the whole input is one group |
| `expect` | a **measure**: a number (no `by`) or a number for each group (`by`: no group may be missing and none may be extra). A **visual**: `{kind: echarts \| vega-lite \| svg \| png, has: {<dotted path>: <value>}}`, for example `{kind: vega-lite, has: {layer.0.mark.type: circle}}` (a path steps through keys and list indexes). A **transform**: `{columns: [...], rows: <count>}` |
| `tolerance` | absolute, default `1e-9`; for numbers in `expect` and in `has` |

The server reads the **shape** of `tests:` when the item is validated or published (`DRS-9070`, with the path of the key:
`tests[0].expect`) and runs them on its compute pool:

* **Check** (the item page's *Check the Python*) shows them, each with its result: `passed`, `FAILED` (with what it expected and got) or
  `could not run` (the code failed, a package is off the list, Python does not run on this server: the message has the `DRS` code). Body
  `{yaml, tests: false}` skips them.
* **Publish and approve** run them first, whichever way they are asked (the console, the API, the command line); a failing or unrunnable
  one refuses the publish (`DRS-9076`) with the first three: `'Vol' cannot be published: 1 of its 3 tests fails (bad: expected 9.0, got
  1.0). Fix the item or the expected value; a bi-admin may publish it with force.` A **bi-admin** may send `{"force": true}`.

Real output of Check on the two samples that ship tests:

```
desk-pnl-volatility  3 tests: passed 3
  pass  the standard deviation of one desk      1.2909944487358056
  pass  too few trades is zero                  0.0
  pass  a desk of its own in a grouped call     2 groups
pnl-vega             2 tests: passed 2
  pass  a dot for every trade and a median for every desk   vega-lite
  pass  at most max_points dots                             vega-lite
```

![Check on the sample measure: manifest and source checks, then each test with its result](../../tutorials/img/t14/18-python-tests.jpg)

#### 3.10 Python on boards and in alerts

**Boards.** A board tile may show a **python visual** of its report: a Python measure shown as bars, a Python visual that returns a
chart, or one that returns a Vega-Lite specification. The tile runs the item for the person looking, exactly as the report does, and shows
the line *Python · item · target · ms · rows* under it. The sample board `python-wall`
([config/bi/reports/banking/python-wall.report.yaml](../../../config/bi/reports/banking/python-wall.report.yaml)) has three:

```yaml
- { id: vol,   type: tile, title: Desk P&L volatility, from: { report: python-desk-risk, visual: volatility }, at: { x: 0, y: 0, w: 6, h: 6 } }
- { id: hist,  type: tile, title: One-day P&L,          from: { report: python-desk-risk, visual: histogram },  at: { x: 6, y: 0, w: 6, h: 6 } }
- { id: strip, type: tile, title: P&L by desk,           from: { report: python-desk-risk, visual: strip },      at: { x: 0, y: 6, w: 12, h: 6 } }
```

![The Python wall: a Python measure, a Python histogram and a Python visual that returns Vega-Lite, as tiles](../../tutorials/img/t12/17-python-wall.jpg)

A Python tile has **no `alert:` of its own**: the colour rule reads the numbers a query returns, and a Python tile's number is made by code
that runs for the person looking. To be told when a Python measure crosses a limit, make an **alert** on it.

**Alerts.** An alert's source may be a **Python measure**: a dataset source whose measure is one (`type: dataset`,
`measure: Desk P&L volatility`), or a visual source whose visual is a python visual that shows one. It is read **for each person it is
judged for**, on the alert's schedule, after a load or by hand:

1. the server builds **that person's rows** (their row rules and masks applied by the engine) for the measure's `inputs` and `by` columns,
   up to `drishti.bi.report.python-rows`;
2. it hands the Python item and the rows to the compute pool `python`, which runs the item in the same strict sandbox;
3. the item's number for each group comes back and is reduced to **one number**: for a dataset source the whole input is one group; for a
   visual source `reduce` (`first` by default; `sum`, `min`, `max`, `avg`, `last`, `count`) is applied to the groups.

* A column **masked for the person** makes the reading *masked*: nothing is run and no figure is shown. An alert whose number is masked
  for its owner **when it is saved** is refused (`422`, *the value this alert watches (MTM) is masked for you, so it could never be read:
  an alert only watches a value its owner may see*).
* A visual or model query that answers **several rows** needs the number chosen: one cell (a `state` that filters it to one row) or an
  aggregate (`source.reduce`); saving one without `reduce` is refused and says which to choose.
* A run that fails (the code raised, a limit, the sandbox refused something, Python not running on this server) is an **error reading**
  with the DRS code and its words in the history; an error reading never fires and never clears.
* A python visual that draws a **chart** (no measure in its wells) has no number to watch: the alert reads as an error that says to watch
  a Python measure.
* A Python alert is not cheap (it starts a process for each person each time): prefer `schedule` or `after-load` to `live`.

```
PUT /api/v1/alerts/rules/Vol
{"title": "Desk P&L volatility", "source": {"type": "dataset", "dataset": "file:dataset:trades-basic", "measure": "Desk P&L volatility"},
 "condition": {"type": "threshold", "op": "above", "value": 5000}, "trigger": {"mode": "after-load"}}
```

The rest of alerts (conditions, triggers, delivery) is in [BI_GUIDE.md](README.md).

### 4. Transforms: a table in, a table out

The third interface, after the Measure and the Visual, is the **Transform**: Python that turns the frames of one or more answers into **a
table**, which a **Python dataset** publishes as its one table (`source: { python: <item>, inputs: { <name>: <a model query> } }`), with
measures in a model over it. Use it where a join or a reshaping is easier in pandas than in the dataset language (a merge with a
tolerance, a rank, a fitted column, a pivot), and you still want the result to be a dataset every report, alert and subscription can use.

```yaml
rachana: 1
python: pnl-vs-exposure
interface: transform            # a table in, a table out
inputs: [pnl, exposure]         # the inputs of the Python dataset (model queries), in the order its function receives their frames
packages: [pandas]
target: server                  # a Transform runs on the server and its result is kept for the reader
timeout: 20
memory: 512
params: { floor: 1.0 }          # defaults; the dataset may override them
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
| function | `run(*frames)`: one **pandas DataFrame** per input, in the order of `inputs`. A function that has a parameter named `params` receives the parameters (the manifest's `params` over the dataset's); or a class named `Transform` with a method `run(self, *frames)`, which finds the parameters in `self.params` |
| frames | what the reader may see: each input is a model query asked **as the reader**, so row rules and masks are already applied and a masked column is null. Each column is named by the last part of its field or measure name (`counterparties.id` is `id`) |
| returns | a DataFrame: no more than `drishti.bi.transform.max-rows` rows; column names of letters, digits and underscore; no duplicate names. Anything else is `DRS-9071` |
| column types | typed from the frame: `int` is `integer`, `float` and decimals are `decimal`, `bool` is `boolean`, dates and timestamps are `date` and `timestamp`, everything else `string` |
| `inputs` (manifest) | the names of the Python dataset's inputs, not columns: the dataset's `source.inputs` must name the same in the same order (`DRS-9075` otherwise) |
| `target` | `server` only |

**Who sees what.** The server asks each input as the reader and hands the answers to the pool, exactly as for a Python measure. The frame
comes back as a Parquet file, is checked against the columns and types the dataset or table declares (its **contract**: a column
missing, or `string` where `decimal` is declared, is `DRS-9075` in words), and the server keeps it for **that reader** and the version of
the inputs (`drishti.bi.transform.cache-ttl`, `cache-entries`). The code never sees anything the reader may not; another reader gets their
own run. Real output of the sample's first and second run (an author who may read every row):

```
Python Transform pnl-vs-exposure: 18 rows in 218 ms (first run); the same reader again: kept, 0 ms
```

**Checks.** The server checks the manifest on validate and publish (`DRS-9070`): a transform needs `inputs` naming datasets, defines `run`
(a top-level function or the method of a class `Transform`), and `target` is `server`. Check adds the source checks on the pool (it parses,
defines its entry, imports only what it lists; nothing runs).

**From the Notebook.** A notebook that joins several extracts in pandas ([section 2.2](#22-the-extract)) can **Promote to a Transform**:
each `extract("name")` becomes a parameter of `run`, the cell's `result` is returned; the draft is then checked and published, and a
Python dataset is written over it (the dataset wizard's source *A Python Transform*), one model query per parameter of `run`.

![The sample's Python dataset: made by the Python Transform pnl-vs-exposure, keyed by counterparty, and the columns its Transform returns](../../tutorials/img/t14/12-python-dataset.jpg)

The sample is `config/bi/python/banking/pnl-vs-exposure.python.yaml` with the Python dataset `desk-pnl-vs-exposure-py-result` and the
model `desk-pnl-vs-exposure-py` over it ([the Notebook tutorial, step 8](#step-8--two-models-a-python-transform-and-a-join-in-the-notebook)).
Settings: `drishti.bi.transform.*` in
[CONFIGURATION.md](../../admin/CONFIGURATION.md#drishtibitransform--python-transforms-and-composed-datasets).

### 5. Data prep: the Transform editor

The Transform editor makes the Python path the data-prep tool a Power Query author expects, without a step language: **raw rows straight
from a connector**, a **step-by-step run with a preview and a profile after every step** and a **tested snippets library** for the common
reshapes. The steps are lines of pandas, run on the server's compute pool in the same sandbox as every Python item, on the rows the
person looking may see.

#### 5.1 Inputs straight from a connector

A Python dataset's `source.inputs` ([section 4](#4-transforms-a-table-in-a-table-out)) takes, besides a model query, a **pack kind read
through a connector**:

```yaml
source:
  python: fx-book-value-cleaned
  inputs:
    trades:                                   # the name run() receives it by
      kind: trade                             # a pack kind
      pack: trading                           # optional: which pack's definition (default: the first that declares it)
      connector: prep-store                   # optional: a connector by name (default: whichever the pack routes the kind to)
      fields: [TRADE_ID, TRADE_DATE, PORTFOLIO, CURRENCY, QUANTITY, PRICE]   # optional: the kind's columns (default: all)
      where: [{ field: PORTFOLIO, op: in, values: [BOOK-FX-1] }]             # optional: filters, as a model query's
      limit: 50000                            # optional: at most this many rows
    rates: { model: fx-rates@1, select: { fields: [rates.currency, rates.date, rates.rate], measures: [] }, shape: rows }   # a model query
```

A kind input is the rows of the kind's pack-generated dataset (`pack.trading.trade`), asked through its implicit model **as the reader**:
the kind's entitlements, their row rules and their masks apply exactly as in a report on it, and a masked column arrives masked. A role that
may not read the kind gets no rows. Naming a connector reads the same kind through that connector instead of the one the pack routes it
to; the input then asks only the fields it names (and the grain's keys), so **the connector must keep them as columns** (its `layout`),
which every lake connector does for the promoted columns. Its answers are never put in the result cache; the Transform keeps its own
result per reader.

The server checks the shape on validate and publish (`DRS-9108` with the path: `source.inputs.trades.colour`), a kind no pack declares is
`DRS-9102`, a field the kind lacks `DRS-9108` with the kind's columns, a connector that is not running `DRS-9026`. The lineage of the
dataset reaches the kind and the connector (`pack-kind`, `connector: prep-store`, `input: trades`).

**One input as you.** `POST /api/v1/bi/python/input` with `{input, limit?, asOf?}` answers the rows of one input (either form) as the
caller: Arrow with `?format=arrow` (headers `X-Bi-Rows`, `X-Bi-Masked`, `X-Bi-Model`, `X-Bi-As-Of`, the day the rows came from) or JSON
`{model, kind, columns, rows, rowCount, masked, asOf}`; `limit` defaults to 1,000 and never exceeds
`drishti.bi.transform.max-input-rows` (50,000). It needs the BI power. This is what the editor previews, and what
`drishti.py bi transform input` prints.

#### 5.2 The editor

**BI → Transforms** (`/bi/transform`, the rail's *Transforms*; also the Notebook's *Transform editor* link) builds a Transform in four
parts, with **no YAML to type**:

1. **Inputs.** Name it as the code will (`trades`), then *A pack kind, straight through its connector* (pick the kind, optionally type the
   connector, tick the fields) or *A model or a dataset* (a model query of rows). The line under the input says how many rows came, the day
   they are of and what is masked for you.

   ![The Transform editor: an input, the pack kind trade read through the connector prep-store, 60 rows of 2026-10-08](../img/bi-transform/01-input.png)

2. **Steps.** The first is *Start from trades* (`df = trades.copy()`). *Add a step from the snippets* opens a form for the snippet you
   choose ([section 5.3](#53-the-snippets-library)): the columns are those of the step before, the inputs are yours. *Write a step* takes a
   label and a few lines of pandas over `df`. A step is inserted after the one you are looking at; each can be edited (✎), moved (↑ ↓) or
   removed (×), and *Undo the last step* removes the last.
3. **Preview.** After every change the console asks the server to run the steps ([section 5.4](#54-the-step-by-step-run)) and each step
   shows its rows and columns; pick one to see its **first 20 rows** (*Rows*) or its **profile** (*Profile*,
   [section 5.5](#55-profiling)). A step that fails turns red, says its error and line, and the steps before it keep their frames.

   ![Step 2 of the sample, Filter rows by values (PORTFOLIO): 20 rows; its profile, one card per column](../img/bi-transform/02-profile.png)

   ![Four steps: start, filter, a calculated column VALUE = QUANTITY * PRICE, total VALUE per CURRENCY; the last step's two rows](../img/bi-transform/03-steps.png)

4. **Save.** Tick the column(s) that make one row (the grain; a column that was unique in the preview is offered first) and *Save as
   drafts*: a **Python item** (`interface: transform`) and a **Python dataset** that runs it, both drafts in your workspace. Check and
   publish the Python item first, then the dataset. The page's address then names both (`/bi/transform?item=<id>&dataset=<id>`), and
   opening it again opens the same steps.

The YAML is shown under *YAML: what is saved* and stays in step with the page; edit either text and *Read the YAML back* to see it as
steps again (a Transform written by hand opens too: its `# step:` comments are the steps, or its whole body is one step).

![The YAML the editor wrote: the Python item with the steps after their # step: comments, and the Python dataset with the kind input](../img/bi-transform/04-yaml.png)

What is saved, for the sample above:

```yaml
rachana: 1
python: fx-book-value-cleaned
title: FX book value, cleaned
interface: transform
inputs: [trades]
packages: [pandas, numpy]
target: server
timeout: 20
memory: 512
params: {}
source: |
  import re
  import numpy as np
  import pandas as pd

  def run(trades, params):
      # step: Start from trades
      df = trades.copy()
      # step: Filter rows by values (PORTFOLIO)
      df = df[df['PORTFOLIO'].isin(['BOOK-FX-1']) == True].reset_index(drop=True)
      # step: Calculated column (QUANTITY; PRICE)
      df['VALUE'] = (pd.to_numeric(df['QUANTITY'], errors="coerce") * pd.to_numeric(df['PRICE'], errors="coerce")).replace([np.inf, -np.inf], np.nan)
      # step: Group and total (CURRENCY; VALUE)
      df = df.groupby(['CURRENCY'], as_index=False, dropna=False)[['VALUE']].agg('sum')
      return df
```

Running the steps needs the **bi-author** power (it runs Python); without it the page still writes and reads the YAML.

#### 5.3 The snippets library

`drishti-console/config/prep-snippets.yaml` holds 21 snippets, each one step over `df` with parameters filled in a form. **A value is
always written as a Python literal** (`repr`): a column called `x"]; import os` is a column of that odd name, never code; an *input*
parameter must be one of the Transform's inputs and an *operator* one of its options (`DRS-9070` otherwise). Every snippet carries an
**example** (frames in, parameters, the frame expected out) that the test suite runs in plain pandas **and** as a step of the pool's
step-by-step run, so a snippet is correct out of the box.

| Group | Snippets |
|---|---|
| Columns | keep columns, rename a column, split a column, calculated column (`+ - * /`) |
| Clean | type fix: text to number, type fix: text to date, replace values, fill down (optionally within groups), remove duplicates |
| Rows | filter rows by values, drop rows with an empty value, sort rows |
| Combine | append another input (union), join another input, **as-of join of prices** (the latest price of the instrument on or before the row's date, within *n* days), **as-of FX conversion** (an amount to a base currency at the latest rate on or before the row's date) |
| Reshape | unpivot (columns to rows), pivot (rows to columns), group and total |
| Dates | align to a business day (following, preceding, modified following; weekends and the holidays you list), tenor bucket (`<1Y`, `1-2Y`, ..., `30Y+`) |

More snippets: a file of the same shape named by `bi.transform.snippets_file`
([CONFIGURATION.md](../../admin/CONFIGURATION.md#prep-snippetsyaml-the-transform-editors-data-prep-snippets)).

#### 5.4 The step-by-step run

`POST /bi/transform/steps` (the console) passes the page's inputs and code to the server (`POST /api/v1/bi/python/steps`): each input is
read **as you** ([section 5.1](#51-inputs-straight-from-a-connector), at most `bi.transform.preview_rows`, 1,000), then `run` runs **once**
on the compute pool (its usual sandbox and limits) with a mark after each step. The steps are the statements after each
`# step: <label>` comment of `run`'s body, or each top-level statement when there is none. After each step the worker describes the frame
that step made (the last DataFrame it assigned, else `df`; after the last step, what `run` returned): its rows, columns and types, its first
20 rows and its profile. The answer also profiles each input and gives the key hints between them. A step that raises stops the run:
`failed` has its index, label, line, error and traceback (the steps before keep their frames). Measured on the sample (60 rows, four
steps): the inputs asked in about 10 ms, the Python run about 45 to 65 ms in all.

The run is never kept: an author runs it as they edit. The Python dataset itself runs the same `run` as each reader when it is read.

#### 5.5 Profiling

The **profile** of a frame, worked out on the compute pool over the reader's rows, is one card per column: its type; **what its text looks
like** (numbers or dates kept as text, with *Fix the type*, which adds the right type-fix step; digits in a column named like an
identifier, `TRADE_ID` or `..._CODE`, are not offered a number type: ids are text by design); empty values (count and %, with a bar);
distinct values; **key?** when every row has its own value; min and max (and the mean of a number); the five most common values; a
histogram of a number. Between inputs, *looks like a key of* names a column whose values (90% or more) are the values of another input's
unique column, with how many match: the *Join* snippet joins them.

The same panel is the Notebook's **Profile** tab ([section 2.7](#27-profile)); the server's route is `POST /api/v1/bi/python/profile`. No
author code runs to profile.

### 6. On the command line

The commands run over REST as the token's user (`--server`, `--token-file`, `--user` as for every REST command;
[CLI_REFERENCE.md](../CLI_REFERENCE.md)):

Run these from the Drishti folder (the repository root, or the install folder of a release).

```
drishti.py bi sql "SELECT ..." --model M [--explain] [--format table|json|csv|jsonl]   a Notebook SQL cell's statement, from the terminal
drishti.py bi transform input FILE.dataset.yaml [--input NAME] [--limit N] [--as-of DATE] [--rows N] [--json]   each input as you: rows, masked columns, a profile
drishti.py bi transform snippets [--json]                                      the data-prep snippets
drishti.py bi transform snippet ID [NAME=VALUE ...] [--inputs a,b]             one step, its values written as literals
drishti.py bi item new WORKSPACE FILE.python.yaml                              a Python item draft (as the editor's Save makes it)
drishti.py bi item new WORKSPACE FILE.dataset.yaml                             the Python dataset draft
drishti.py bi item tests ID                                                    run an item's tests as you; exit 1 on a failure
drishti.py bi catalogue publish ID [--note TEXT]                               publish the draft (its tests run first)
```

Real output of `bi transform input` on the demo lakes
([the Notebook tutorial, step 12](#step-12--prepare-raw-trades-step-by-step-the-transform-editor)):

```
$ python3 tools/drishti.py bi transform input docs/tutorials/data/endur-trades-cleaned.dataset.yaml
trades: 1,000 rows of dataset:pack.trading.trade as of 2026-10-16
  CURRENCY                     text      empty     0  distinct      3  CAD .. USD
  MATURITY_DATE                text      empty     0  distinct     81  2024-11-01 .. 2029-10-31
  NOTIONAL                     float64   empty     0  distinct    971  2,855.8 .. 2.46923e+08
  PORTFOLIO                    text      empty     0  distinct      3  BOOK-COMM-1 .. BOOK-COMM-3
  TRADE_DATE                   text      empty     0  distinct    529  2024-10-10 .. 2026-09-25
  TRADE_ID                     text      empty     0  distinct   1000  1000005 .. 1005000
  TRADE_STATUS                 text      empty     0  distinct      5  AMENDED .. VALIDATED
$ python3 tools/drishti.py bi transform snippet filter-values column=TRADE_STATUS values=MATURED keep=false
# step: Filter rows by values (TRADE_STATUS)
df = df[df['TRADE_STATUS'].isin(['MATURED']) == False].reset_index(drop=True)
$ python3 tools/drishti.py bi transform snippet asof-fx amount=NOTIONAL currency=CURRENCY date=TRADE_DATE fx=rates --inputs trades,rates
```

`snippets` and `snippet` read the console's library (no server). The steps themselves run on the server's compute pool (the editor's
step-by-step run, and the dataset when it is read). The administrator's side (the lock file) is `tools/python_lock.py`
([section 9.3](#93-the-pinned-libraries-installing-and-locking-the-environment)).

### 7. The sandbox and who sees what

#### 7.1 What the sandbox enforces

Code people write runs only in the pool's **strict sandbox** (`isolation: strict`), and only where every part of it can be had. The walls
the kernel holds come first; Python's own guards are the second line.

| What | How |
|---|---|
| no network | the worker has a user and network namespace of its own with **no interface**, so nothing it or a library runs (Python, C or C++) can connect anywhere; the kernel also refuses TCP; a guard refuses `socket.*` in words |
| files only in its own folder | each request is held by the kernel (Landlock) to reading the Python installation, the module path, the system libraries and its own scratch folder, and to writing that folder only, so C++ code that opens a file itself (`pyarrow.fs`) is refused too |
| a separate process | a fresh process per request, started with an **empty environment** (no tokens, no proxies); nothing it leaves reaches the next request |
| no processes, no loader | `subprocess`, `os.system`, `exec*`, `spawn*`, `fork` and loading with `ctypes` are refused |
| CPU time and wall time | the item's `timeout`; past it the request is stopped: `DRS-9072` |
| memory | what Python needs to start plus the item's `memory` MB (never more than the pool's): `DRS-9072` |
| output | an answer at most 4 MB, a Transform's file at most `drishti.bi.transform.max-bytes`: `DRS-9072`; the input at most 256 MB |
| pinned libraries | the packages an item may list are the allow-list that `config/compute/locks/python.lock` pins; while the pool's interpreter carries a different version of one, every run is refused, naming the package |
| imports | the author's own `import` may name only the listed packages and a short list of safe standard-library modules (`math`, `statistics`, `json`, `datetime`, `re`, `collections`, `itertools`, `functools`, `typing`, `decimal`, `fractions`, `random`, ...); `exec`, `eval`, `compile` are not given |
| the input is what the reader may see | the server reads the rows as the person running it (row rules and masks), and the function gets only the columns of `inputs` |
| every run audited | the audit log has one line per run (`python-run`): who, what ran, on which pool, the outcome, the time and the bytes handed over |

**Where it cannot be safe, it does not run.** The strict sandbox needs Linux with Landlock (kernel 5.13 or later) and user namespaces. On
**Windows** and on a Linux without them, no code people write runs: every run answers `DRS-9074` with the reason, and the Notebook's Run
and the table editor's Sample run are off and say why. Nothing falls back to a weaker sandbox. A file or feed the code needs is read by the
server and handed in as an input; the code itself never reaches a network.

#### 7.2 Who sees what

* The rows are the server's answer for **this person**: their row rules, their masks, their business date. A masked column arrives masked
  (`X-Bi-Masked` lists it; the visual's line says *masked*); Python never receives the real value, so it cannot reveal it. The result is
  derived from those rows only.
* A **traceback** is shown to people with the `bi-author` power; everyone else sees the code and the message
  (`DRS-9071 ZeroDivisionError: division by zero`) without the source lines.
* Which item runs, and which rows it reads, are decided by the **server**; the console passes on the person's identity and decides nothing
  about access.
* Every non-GET call takes JSON from the console's own pages only (same-origin check and `application/json`, `DRS-5002` / `DRS-5001`;
  [API_REFERENCE.md](../API_REFERENCE.md)).

### 8. Limits and errors

#### 8.1 The limits

| Limit | Where it is set |
|---|---|
| rows handed to a python visual or measure | `drishti.bi.report.python-rows` (10,000) |
| an item's time and memory | its manifest's `timeout` and `memory`, capped by `drishti.bi.report.python-max-seconds` (60) and `python-max-memory-mb` (1024) |
| a Notebook extract, SQL cell and Python cell | `bi.notebook.*` ([section 2.9](#29-notebook-settings-errors-and-routes)) |
| a Transform's rows, input rows, result size and cache | `drishti.bi.transform.*` (`max-rows`, `max-input-rows`, `max-bytes`, `cache-ttl`, `cache-entries`) |
| the editor's preview rows, extra snippets | `bi.transform.*` (`preview_rows`, `snippets_file`) |
| how many run at once | the pool's workers and queue ([section 9.2](#92-workers-memory-and-time)) |

Every key, its default and its environment variable is in [CONFIGURATION.md](../../admin/CONFIGURATION.md):
[`drishti.bi.report`](../../admin/CONFIGURATION.md#drishtibireport--reports-phase-3),
[`drishti.bi.transform`](../../admin/CONFIGURATION.md#drishtibitransform--python-transforms-and-composed-datasets),
[`bi.notebook`](../../admin/CONFIGURATION.md#binotebook), [`bi.transform`](../../admin/CONFIGURATION.md#bitransform) and
[`compute`](../../admin/CONFIGURATION.md#compute-workers-configcomputecomputeyaml).

#### 8.2 Errors: DRS-9070 to DRS-9077

| Code | HTTP | Meaning | Usual cause |
|---|---|---|---|
| `DRS-9070` | 422 | a Python item or its use is not valid | a manifest key, a package off the list, no entry function, a python visual with no item |
| `DRS-9071` | 422 | the code failed | an exception (the message names it; authors also get the traceback), a measure that returned something that is not a number, a visual that returned neither an option, an SVG nor a PNG |
| `DRS-9072` | 413 or 504 | a limit stopped it | over `timeout` (504), over `memory`, input or output size (413) |
| `DRS-9073` | 403 | the sandbox refused something | an `import` that is not on the list, the network, a file outside the scratch folder, a process |
| `DRS-9074` | 503 | Python does not run here now | compute off, the pool `python` missing or not strict, the strict sandbox not available on this platform (Windows), the interpreter not carrying the pinned versions, the pool's queue full |
| `DRS-9075` | 422 | a Transform's result is not usable | a column the dataset declares is missing from the frame or of another type, or the Transform's `inputs` are not the dataset's inputs |
| `DRS-9076` | 422 | a test the item ships with fails, so it is not published | a publish (or approve) of a Python item whose `tests:` do not all pass; a bi-admin may publish with `force: true` |
| `DRS-9077` | 422 | a Vega-Lite specification could not be drawn (shown in the visual's frame; no HTTP call) | a `url` or `loader` in the specification, a feature drishti-chart does not translate (named with what to use instead), an invalid specification |

Real answers of the pool's engine in its strict sandbox (a measure on three rows, then a read the guard refuses and one only the kernel
can):

```
{"ok": true, "kind": "measure", "by": ["desk"], "rows": [["A", 3.0], ["B", 5.0]], "ms": 18}
{"ok": false, "kind": "sandbox", "error": "Denied: reading /etc/hostname is not allowed", "traceback": "  File \"<python-item>\", line 2, in render ..."}
{"ok": false, "kind": "sandbox", "error": "PermissionError: [Errno 13] Failed to open local file '/home/.../.bashrc'. Detail: [errno 13] Permission denied"}
```

The server answers them as `DRS-9071` to `9073` (the traceback for authors only).

#### 8.3 Does Python run here?

`GET /api/v1/bi/python/runtime` (the console's `GET /bi/python/status`; the Notebook's `GET /bi/notebook/status`) says whether Python
runs, and why not:

```
{"runs": false, "why": "Python runs on the server's compute pool, and compute is off (compute.enabled in config/compute/compute.yaml)", "where": "server",
 "pool": "python", "packages": ["pandas", "numpy", "pyarrow", "scipy"], "pins": {"numpy": "2.5.3", "pandas": "3.0.6", ...}, "calls": 0, "failures": 0}
```

**How fast.** With pandas loaded, the engine answers a measure, a Transform, a cell or a profile in **11 to 24 ms** inside a warm worker
(strict sandbox included); the first request of an idle pool also pays for the worker's start, about 0.2 s plus the imports. More in
[OPERATIONS_GUIDE.md](../OPERATIONS_GUIDE.md#21-troubleshooting).

### 9. For administrators: turning Python on

Python is **off as shipped**. Turning it on is three things: switch compute on, size the pool `python`, and make sure its interpreter
carries the pinned libraries. The compute workers in general (other pools, connector requirements and their approval, Admin → Compute) are
in [OPERATIONS_GUIDE.md › Compute workers](../OPERATIONS_GUIDE.md#15-compute-workers).

#### 9.1 Switch on compute and the pool `python`

1. Use a **Linux** server with Landlock (kernel 5.13 or later) and unprivileged user namespaces ([section 7.1](#71-what-the-sandbox-enforces)).
2. In `config/compute/compute.yaml` set `compute.enabled: true` (or the environment variable `DRISHTI_COMPUTE_ENABLED=true`), and point
   `compute.python` (`DRISHTI_PYTHON`) at the interpreter the workers use (empty: `python3`).
3. Keep the shipped pool `python` as it is: `engines: [drishti_compute.items:ItemsEngine]`, `isolation: strict`, `mode: fork`. A pool that
   is missing or not strict makes every run answer `DRS-9074`.
4. Restart the server, then check `GET /api/v1/bi/python/runtime` (or open the Notebook): `"runs": true`.

`drishti.bi.transform.enabled` switches every Python run off again without touching compute. The console has no Python setting: it runs
none.

#### 9.2 Workers, memory and time

The shipped pool:

```yaml
    python:                          # code people write: Python items, Python-driven tables and models, Notebook cells
      type: python
      engines: [drishti_compute.items:ItemsEngine]
      environment: system            # the site's interpreter, checked against locks/python.lock before any run; or { python: "3.13", lock: locks/python.lock, index: <name> }
      isolation: strict              # no network and files held by the kernel: Linux only; elsewhere no run
      workers:
        min: 0
        max: ${DRISHTI_COMPUTE_PYTHON_WORKERS:4}
      mode: fork                     # strict isolation needs a fresh child per request
      memory: 6GB                    # the worker's address space: pandas, NumPy and Arrow loaded (about 2.5 GB of it) plus an item's own memory
      cpu-seconds: 120
      recycle-after: 200
      batch-share: 0.5               # a Python-driven table's scheduled build is batch work
      idle-stop: 5m
      timeout: 60s                   # an item's own timeout may be shorter (drishti.bi.report.python-max-seconds caps it)
      timeout-batch: 10m
```

* **How many at once:** `workers.max` (4 as shipped; any number, per node with `compute.nodes.<node>.pools.python.workers`). A full queue
  answers `DRS-9074`; one person runs at most `compute.scheduling.max-inflight-per-user` at once.
* **Warm workers:** every call is a fresh child of a worker that imported pandas, NumPy and pyarrow once. `workers.min` (0 as shipped)
  keeps that many started, so the first call after an idle spell does not pay about 0.2 s plus the imports; isolation is unchanged.
* **Per item:** the item's `timeout` and `memory` are capped by `drishti.bi.report.python-max-seconds` and `python-max-memory-mb`; a
  Notebook cell by `bi.notebook.cell_timeout_seconds` and `cell_memory_mb`.

#### 9.3 The pinned libraries: installing and locking the environment

The libraries code people write may import are **one pinned set**: `config/compute/locks/python.in` lists the whole environment, one
`name==version` per line, transitive packages included (as shipped: numpy, pandas, pyarrow, scipy, python-dateutil, six, tzdata), and
`config/compute/locks/python.lock` pins each to the sha256 of each of its files. The manifest allow-list `drishti.bi.report.python-packages`
offers only what the lock pins (a test holds the two together).

There are two ways to give the pool that environment:

* **`environment: system`** (as shipped): the site's interpreter. Install the pinned versions into it, for example
  `python3 -m pip install --require-hashes --no-deps -r config/compute/locks/python.lock`. Before any run the server asks the pool for its
  interpreter's versions and refuses every run, naming each package, while one differs from its pin.
* **A locked environment**: `environment: { python: "3.13", lock: locks/python.lock, index: <name> }`. Drishti does not build it: the operator installs those pinned libraries into the Python the pool runs (`drishti.python`) with pip, and the pool checks them against the lock at every worker start; a missing one is an error naming it and the pip command.

**To add or change a library** (for example `statsmodels`, `scikit-learn` or `polars`):

1. add it, and every package it needs, to `config/compute/locks/python.in` with one version each;
2. write the lock on a machine that may reach the package index, and commit it:

   ```
   python tools/python_lock.py config/compute/locks/python.in --out config/compute/locks/python.lock [--index URL]
   ```

3. add the import name to `drishti.bi.report.python-packages`;
4. install it into the pool's interpreter (or rebuild the locked environment) and restart the workers.

Nothing is resolved at install time: the lock is the whole environment.

#### 9.4 Watching it

* **Admin → Compute** shows the pools, workers busy, queues, latency, environments and recent requests.
* The **audit log** has one `python-run` line per run (who, what, the pool, the outcome, the time, the bytes).
* `GET /api/v1/bi/python/runtime` says whether Python runs and why not ([section 8.3](#83-does-python-run-here)).
* On **Windows** Python runs stay off by design (no network namespace, no Landlock): every run says so with `DRS-9074`.

### 10. Calc: Python in your own browser

Calc puts a Python prompt beside every view, the way BQuant sits beside a Bloomberg screen. Press `Alt+C` on a
trade, a netting set, a VaR result or a curve: a panel slides in with an editor, and the code you write there can
read the screen you are on, search Drishti, and draw tables and charts in the terminal's own style.

**Calc is a Labs feature: a scratch pad, off as shipped.** An administrator switches it on with the Labs switch
(`labs.calc` on the console and `drishti.calc.enabled` on the server, both `DRISHTI_LABS_CALC`; [10.15](#1015-configuration-and-api)).
The drawer says *Labs · Scratch: runs in your browser*. Nothing Calc computes is published, shared, scheduled or
promoted: what you keep is your own code snippets. A figure for other people is a Pivot view or a BI report; code
over a model that others can run is a [notebook](../USER_GUIDE.md#the-notebook).

The code runs **in your browser**, in [Pyodide](https://pyodide.org) (CPython 3.14 compiled to WebAssembly), inside a
Web Worker, so a long calculation never freezes the page. numpy, pandas, scipy, statsmodels and matplotlib are
there. Nothing runs on the console or the server: every read your code makes is an ordinary read with your session,
so your roles and the field masking apply exactly as on the screen: a masked field reads `•••` in the document, the
strip, the tables, searches and columns alike.

#### 10.1 When to use Calc, and when not

| You want to… | Use |
|---|---|
| Re-price a trade under a rate move, recompute a VaR, interpolate a curve, pivot a desk's P&L your own way, once | **Calc** |
| Try an idea on what is on the screen before asking for a panel | **Calc** |
| A figure everyone sees on a view, every time | a panel in the kind's Sutra ([Sutra developer guide](../BUILD_GUIDE.md#11-what-a-sutra-is-and-when-one-applies)): computed on the server, cached, live |
| Find entities by value (`TRD where mtm > 1m`) | [search](../USER_GUIDE.md#search-by-value), on the command line |
| Be told when a figure crosses a line | an [alert](../USER_GUIDE.md#alerts): the server checks it on every change |
| The same search as a file every morning | a [scheduled report](DISTRIBUTION_AND_ALERTS.md#11-the-reports-page-and-scheduled-searches) |
| A long notebook, a model you keep in git, millions of rows, your own libraries | Python on your machine with a personal API token ([API_REFERENCE.md (clients)](../DEVELOPER_GUIDE.md#113-clients-scripts-notebooks-and-excel)) |

Calc is for questions that are yours, now, about the data in front of you. It is not a batch engine: it runs in one
browser tab, with that tab's memory, and it reads what you could read by clicking (see [Limits](#1014-limits)).

#### 10.2 Opening it

- Press **`Alt+C`** on a view, or click **Alt+C Calc** in the row of keys at the bottom of the screen. Press `Alt+C`
  or `Esc` again to close it. (On a Mac, `Option+C`.)
- The first time in a browser tab, Python starts: the status line says *Starting Python…* and then
  *Python ready · Pyodide 314.0.7 · 1.3 s*. Later opens in the same tab are instant; Python keeps running while the
  drawer is closed, and your variables are kept until you press **Stop** or leave the page.

`Alt+C` is chosen because the function keys belong to each view: a Sutra may bind any of `F2`–`F12` to its panels and
links (see [Keyboard](../USER_GUIDE.md#keyboard)), and `Alt+` keys are the terminal's own (`Alt+←` back, `Alt+0`…`Alt+4`
workspace panes).

A view offers Calc when both hold:

1. **The pack that owns the view's kind switches Calc on** (`python: { enabled: true }` in its `pack.yaml`, see
   [10.8](#108-packs-switching-calc-on-and-starter-snippets)). Of the shipped packs, core, market-data, trading, counterparty-risk and
   market-risk do (the retired example pack `finance` too).
2. **One of your roles has the `calc` power** (see [10.9](#109-roles-who-may-use-calc)).

Without the role, the key is still shown, greyed, with the reason: *You do not have access: Calc needs a role with
calc (ask an administrator)*. On a kind whose pack does not switch Calc on, there is no key. Views inside workspace
panes have no Calc; open the view on its own.

#### 10.3 The panel and its keys

```text
| Calc  Python · PNL-MX-20000274-HS-509   Done · 1.9 s · packages 373 ms · Python 1.5 s · memory 79 MB   ?  × |
| [▶ Run] [■ Stop] [Snippets ▾] Save  Delete  Clear                           Ctrl+Enter run · Esc close |
|  1 import numpy as np                                                                                 |
|  2 pnl = np.asarray(view.doc["PNL"], dtype=float)                                                     |
|  …                                                                                                    |
| Output | History                                                                                       |
| 2 rows                          [Filter rows] « ‹ 1–2 of 2 › » ▲ ▼ [25 rows]                           |
| measure       USD                                                                                     |
| VaR 99%     7,102                                                                                     |
| …                                                                                                     |
```

| Key or control | Does |
|---|---|
| `Alt+C` | Open or close Calc |
| `Ctrl+Enter` (`Cmd+Enter` on a Mac), **Run** | Run the code in the editor |
| **Stop** | End the Python worker at once (a loop that never ends, a calculation too slow) and start a fresh one. Variables from earlier runs are gone |
| `Tab`, `Shift+Tab` | Indent and outdent by four spaces |
| `Esc` | Close the panel (Python keeps running) |
| **Snippets ▾** | Load a snippet: *From the packs* for this kind, and *Mine* |
| **Save** | Save the code as one of your snippets (asks for a name; the name of the snippet you loaded is offered) |
| **Delete** | Delete the snippet of yours you loaded |
| **Clear** | Clear the output |
| **Output**, **History** | The output of the last run; your last 30 runs in this browser (click one to put its code back in the editor) |
| `?` | This guide |

The status line says what Python is doing (*Loading numpy, pandas…*, *Reading search…*, *Running…*) and, after a
run, how long it took, split into **packages** (loading libraries the code imports, the first time only) and
**Python** (your code), how many reads it made and how much they weighed, and the memory Python holds.

- **Each run clears the output** and runs the whole editor. Variables persist between runs (as in a notebook cell
  run again), so you can build on earlier results; **Stop** starts clean.
- **The last expression is shown**, as in a notebook: a DataFrame as a table, anything else as text.
- **An error** shows its message and the traceback from your code (Python's own machinery is left out), and the line
  is highlighted in the editor.
- **Your draft is kept** per kind in this browser: open Calc on another trade and the code you were writing is there.

#### 10.4 A first session: VaR from the scenarios

1. Open a trade's P&L vector, `PNLV PNL-MX-20000274-HS-509 <GO>` (a short warrant position's trade: its P&L under the 509 scenario
   days the VaR engine uses), and press `Alt+C`. Wait for *Python ready*.
2. Type in the editor:

   ```python
   import numpy as np
   import pandas as pd

   pnl = np.asarray(view.doc["PNL"], dtype=float)
   worst = np.sort(pnl)
   n = len(pnl)
   k = int(np.ceil(n * 0.01))                     # the engine's rank: the 6th worst of 509
   var99 = -worst[k - 1]
   es975 = -worst[: int(np.ceil(n * 0.025))].mean()   # the mean of the 13 worst
   show(pd.DataFrame({"measure": ["VaR 99%", "ES 97.5%"], "USD": [var99, es975]}))
   chart(pd.DataFrame({"pnl": pnl}), kind="hist", y="pnl", bins=40, title="Scenario P&L")
   ```

3. Press `Ctrl+Enter`. The first run loads numpy and pandas (about 2 s); you should see **VaR 99% 7,102** and **ES 97.5% 7,094** (the
   same rule as the engine's, so `VAR TRD MX-20000274 <GO>` shows the same figures) and a histogram of the 509 scenarios.
4. Change `0.01` to `0.025` and run again: the 97.5% VaR, in a few milliseconds this time.

The VaR of a position, a portfolio, a desk or any set of trades is the engine's ([VAR_GUIDE.md section 3](../VAR_GUIDE.md#3-the-explorer-screen-by-screen)); the stored desk
VaR records (`VAR VAR-RATES`) are not stored: those ids open the VaR explorer.

#### 10.5 The `drishti` module

Every run starts with these names defined (no import needed): `view`, `drishti`, `show` and `chart`. In your own
modules or functions, `import drishti` works too, and `from drishti import view, show, chart`.

The pricing and risk maths the snippets share (Black-Scholes, Black-76, Garman-Kohlhagen, Bachelier, curves, bonds,
VaR, CVA, FRTB aggregation, …) is a submodule: `from drishti import quant as q` ([10.17](#1017-the-drishtiquant-module)).

##### 10.5.1 `view`: the screen you ran from

| Attribute | Type | What it is |
|---|---|---|
| `view.kind`, `view.id` | str | The entity: `'pnl-vector'`, `'PNL-MX-20000274-HS-509'` |
| `view.title` | str | The title the view shows |
| `view.business_date` | str or None | The business date of the data (`'2026-09-30'`), or None for undated data |
| `view.provenance` | dict | Source, generation, business date, last update (as in the footer of the view) |
| `view.doc` | dict | The entity's document as your role may see it (the raw JSON of `F9`, masked fields read `•••`) |
| `view.strip` | dict | The strip at the top of the view: label → shown text (a masked field's text is `•••`) |
| `view.tables` | dict | Every panel that holds rows, as a pandas DataFrame, by the panel's title (masked cells and totals read `•••`) |
| `view.table(name)` | DataFrame | One panel's rows, by title or by panel id |
| `view.panels` | list | The view model's panels as the server sent them (`id`, `kind`, `title`, `data`) |

`view` is read fresh at every run: on a live view, run again and you get the latest figures.

How each panel kind becomes a DataFrame in `view.tables`:

| Panel kind | Columns |
|---|---|
| `table`, `ladder` | the panel's columns; numeric columns parsed to numbers (below) |
| `kv`, `status`, `provenance` | `label`, `value` |
| `line`, `area` | index `x`, a column per series |
| `bars` | `label`, `value` |
| `waterfall` | `label`, `value`, `from`, `to`, `total` |
| `histogram` | `from`, `to`, `count`, `label` |
| `scatter` | `label`, `group`, `x`, `y`, `size` |
| `pivot` | index the row labels, a column per pivot column |
| `candlestick` | `x`, `open`, `high`, `low`, `close`, `volume` |
| `timeline` | `date`, `label`, `detail`, `status` |
| `surface` | index `y`, a column per `x` |

Tables hold what the screen shows, so their numbers are **as formatted**: `"−1,234"` is read as `-1234.0`,
`"USD 1,300,000"` as `1300000.0`, `"1.5m"` as `1500000.0`, and `"4.08%"` as `4.08` (as written, not 0.0408).
`drishti.number(text)` does the same to any text. For exact values, read `view.doc`.

```python
trades = view.table("trades")      # NSET NS-NORTHPLAINS-NY (demo lakes): the panel titled "Member trades (698 of 698)", by its id
trades.groupby("Product")["MTM (USD)"].sum()
```

##### 10.5.2 Reading data

Each call is a request from your browser to the console with your session, exactly as when you click: your roles
decide what you may open, and fields masked for your role stay masked. A refused or failed read raises
`drishti.DrishtiError` with its `code` (`DRS-5002` not allowed, `DRS-1001` not found, …), `detail` and `status`.

**`drishti.get(kind, id, as_of=None) -> dict`**

An entity's document (as `F9` shows it to you). `as_of` is a business date (`'2026-09-25'` or a `date`); by default,
the date the screen shows.

```python
nset = drishti.get("netting-set", view.doc["nettingSet"])
nset["netMtm"], nset["utilisation"]
```

**`drishti.search(query, as_of=None) -> DataFrame`**

A structured search, as typed on the command line ([Search by value](../USER_GUIDE.md#search-by-value)): a column `id`,
`title` when it says more than the id, then the fields the query names and the kind's key fields, without `$.`.
At most 1000 rows (`limit 1000`; the default is 100). When more matched, a note says how many; `df.attrs` holds
`matched`, `scanned` and `partial`.

```python
trades = drishti.search("TRD where counterparty.id = 'CP-NORTHBRIDGE' and nettingSet != '' limit 1000")
trades.groupby("nettingSet")["mtm"].agg(["count", "sum"])
```

A field appears as a column when the query names it, so name the fields you want to group by in the condition
(`and nettingSet != ''` above).

**`drishti.columns(kind, paths, as_of=None, limit=None) -> DataFrame`**

Every entity of a kind on the business date, with the fields a source keeps as columns (a Delta table laid out by its
pack, Aerospike's promoted bins, the PostgreSQL entity table): the whole day, without reading a document. Indexed by
id; `df.attrs` holds `businessDate`, `total` and `masked` (fields your role sees masked). `drishti.columns("TRD", [])`
lists the fields kept as columns.

```python
df = drishti.columns("TRD", ["mtm", "book", "desk", "risk.dv01", "counterparty.id"])
len(df)                                                   # 10000 with python tools/drishti.py data load --store delta --trades 10000
df.groupby("desk")["risk.dv01"].sum()
```

It needs a source that keeps columns: on the demo samples (no lake) it answers *no source of trade keeps its fields
as columns here: use drishti.search()*. It returns at most `drishti.calc.max-column-rows` entities (250,000), the
first by id, with a note when cut; it needs the `calc` power and is recorded in the access log as a search.

**`drishti.history(kind, id, field, days=30) -> DataFrame`**

One field of an entity over the last `days` business days (2–260), oldest first, indexed by date: the same series as
clicking a value on a view ([A number over time](../USER_GUIDE.md#a-number-over-time)).

```python
h = drishti.history("trade", view.id, "mtm", 60)
h["mtm"].diff().describe()
```

**Async variants: they work in every browser.** `get_async`, `search_async`, `columns_async` and `history_async`
take the same arguments and are awaited: `doc = await drishti.get_async("trade", "MX-20000001")`. Top-level `await`
works in Calc, and a last line that is an un-awaited read (`drishti.get_async("trade", "MX-20000001")`) is awaited for
you. The plain functions pause Python while the page fetches, which needs JavaScript Promise Integration (JSPI):
current Chrome, Edge and Firefox have it (verified in Firefox 155), Safari does not, and a browser can have it
switched off. Without it:

- the status line says *reads need await (see help)*, and a note beside the output tabs says *This browser cannot
  pause Python for a read: write await drishti.get_async(…), search_async, columns_async or history_async. view works
  as it is.*;
- `view` (the screen's document and tables) works as it is: it is read before the code runs;
- a plain call raises an error that spells the call out with `await`, for example *drishti.get() needs JavaScript
  Promise Integration … write `await drishti.get_async('trade', 'MX-20000001')` instead*;
- every starter snippet the packs ship runs: they read with the `_async` forms (a test keeps it so).

This was run in a real Firefox 155 with JSPI switched off (`javascript.options.wasm_js_promise_integration: false`),
on the sample data: the two snippets that read (`desk_pnl_by_book.py`, `mtm_concentration.py`) and the four that use
`view` only all finished.

**`drishti.help()`** prints this summary; **`drishti.LIMITS`** is the output budget (below).

#### 10.6 Output: print, show, chart, figures

| You write | You get |
|---|---|
| `print(...)` | Text in the output (standard error in amber) |
| an expression on the last line | It is shown: a DataFrame as a table, anything else as text |
| `show(df, title=None)` | A table, sorted, filtered and paged like every Drishti table (25 rows a page, `Filter rows`, `▲ ▼`), numbers right-aligned with separators and negatives in the negative colour. A Series, a list of dicts, a dict and a numpy array work too; anything else is shown as text |
| `chart(df, kind="line", x=None, y=None, title=None, bins=30)` | A chart drawn by the page with the vendored ECharts in the current theme: `line`, `bar`, `scatter` or `hist`. `x` is a column (default the index); `y` a column or a list (default every numeric column). `hist` counts one column in `bins` bins |
| a matplotlib figure | A PNG image (the `agg` backend): every open figure is shown after the run, and closed. `show(fig)` shows one at once |

The output of one run is capped (`drishti.LIMITS`): 2,000 rows per table (and 200,000 cells), 5,000 points per chart
series (more are thinned, with a note), 8 MB in all, and 200,000 characters of text. Beyond a cap you get a note,
not an error: show a smaller piece (`df.head(50)`, a `groupby`).

#### 10.7 Packages

| Package | Version | Loads when |
|---|---|---|
| The standard library | CPython 3.14.2 | Python starts (first open) |
| numpy, pandas (with python-dateutil, pytz, six) | 3.0.2 | the code first uses data (`view.tables`, `search`, `show`, `chart`, `pd`, `np`, …) |
| scipy | 1.18.0 | the code imports it |
| statsmodels (with patsy, packaging) | 0.14.6 | the code imports it |
| matplotlib (with contourpy, cycler, fonttools, kiwisolver, pillow, pyparsing) | 3.10.8 | the code imports it |

Packages load once per tab and come from the console itself. Anything else (`import sklearn`) fails at once with
*No module named 'sklearn' (Calc has numpy, pandas, scipy, statsmodels, matplotlib and the standard library)*:
there is no `pip` and no internet in Calc.

#### 10.8 Packs: switching Calc on, and starter snippets

A pack switches Calc on for the kinds it owns, and may ship snippets, in its `pack.yaml`:

```yaml
python:                         # Calc on this pack's kinds (Alt+C on a view)
  enabled: true
  snippets:                     # optional: starters offered in Snippets ▾
    - title: MTM under parallel rate moves
      description: The swap's MTM after parallel moves of -100 to +100 bp from its DV01.
      kinds: [trade]            # the kinds it is offered on; none: every kind of the pack
      code: |
        mtm, dv01 = view.doc["mtm"], view.doc["risk"]["dv01"]
        ...
  dir: python                   # optional: where snippet files are (default python/)
```

Snippets can also be files, `modules/<module>/packs/<pack>/python/*.py`, read in name order after those in `pack.yaml`. A file starts
with comment lines naming it; what comes after them is the code (the copyright header above them is not):

```python
# title: Stress results: scenarios by desk, limits and the worst case
# description: Every stress result in one search, pivoted scenario by desk with totals; …
# kinds: stress-result, stress-scenario

import numpy as np
...
```

Rules:

- **Calc is offered on a kind's views when the pack that owns the kind enables it.** A pack's snippets are offered on
  the kinds they name (any kind, even one another pack owns) or, naming none, on the pack's own kinds; only packs
  that enable Calc offer snippets, and only the user's active packs.
- The server reads `python:` and the files at start-up (`GET /api/v1/packs` carries them to the console, with the
  pack's kinds); restart the server after changing them. A file larger than 64 KB is skipped; at most 100 snippets
  a pack.
- Snippets are starters, not programs: they are code the user sees, edits and runs, with the user's own rights.

##### 10.8.1 The shipped packs' snippets

Five of the eight shipped packs ship **63 snippets** (core 9, counterparty-risk 12, market-data 27, market-risk 6, trading 9; world-economy,
news-and-markets and us-equities none), and the retired example pack `finance` **10** (two inline in its `pack.yaml`, eight files): curves,
FX, vol surfaces, options, bonds, credit, stress, FRTB, exposure, CVA, collateral, SA-CCR,
desks, books and counterparties, each written the way a trader or a quant would use it on that screen. The
[Snippet catalogue](#1018-snippet-catalogue) lists every one, with the sample entity it was checked on and what it
computes.

The shipped ones are written by `tools/packgen/banking/make_packs.py` (the generator kept its name from the banking packs it began with) from their sources,
`tools/packgen/banking/snippets/<pack>/<name>.py` (read by `calc_snippets.py`; never edit the generated
`modules/<module>/packs/<pack>/python/` files: change the source and run the generator, see
[BUILD_GUIDE.md](../BUILD_GUIDE.md#301-how-the-shipped-packs-are-generated)). A source starts, after its copyright header, with

```python
# title: Key-rate DV01 by bump and reprice
# description: A 10Y receive-fixed swap at par (100m) repriced with each pillar moved 1 bp in turn, ...
# kinds: ir-curve
# example: CRV CRV-USD-OIS
```

`example` names the sample entity the snippet is checked on in a browser (it is not copied into the pack); the
generator refuses a source without the four lines or with more than 60 lines of code, and `make_packs.py --check`
fails when a generated file is stale or has no source. `finance` (a retired example pack) is hand-written: its files are
`examples/packs/finance/python/*.py`.

How they are written, so that each runs anywhere:

- **Reads use the `_async` forms** (`await drishti.search_async(...)`, `get_async`), so a snippet runs in every
  browser, not only those with JavaScript Promise Integration (section 10.5.2).
- **A search returns the fields its condition names**, so each names the fields it uses: `and pnl1d > -1000bn`
  names a field without dropping any row; a missing field reads as "not 0", so `and risk.dv01 != 0` keeps every trade.
- **The screen's own document first** (`view.doc`), then the entities it links to (`discountCurve`, `fxVolSurface`,
  `creditCurve`, …), then one search for book-wide work. No snippet needs a lake (`drishti.columns()`), so each runs
  on the samples as shipped.
- **Simplifications are said** in the description and a comment: single-curve swaps, flat cap vols, European pricing
  of American options, first-order stress, the FRTB and SIMM aggregation formulas without their full parameter sets.
- **The samples are synthetic**: some booked strikes sit far from spot, and some booked figures (MTM, DV01, Z-spread)
  do not follow from the market data. The option snippets then price at the money and say so; the others show their
  figure beside the booked one rather than hide the gap.

#### 10.9 Roles: who may use Calc

Calc is a power of a role, like raw JSON or authoring Sutras: **`calc`**. A person may use Calc if any of their roles
has it. What their code may read is decided as always, call by call, by the kinds their roles open and the packs
they have.

Who has it out of the box:

| Role | From | `calc` |
|---|---|---|
| `admin`, `author`, `approver` | configuration (`drishti.security.roles`) | yes |
| `viewer` | configuration | **no** |
| `trader` (`trading`), `market-risk` (`market-risk`), `credit-risk` (`counterparty-risk`) | the shipped packs | yes |
| `trader`, `risk` (`finance`) | the retired example pack `finance`, when loaded from `examples/packs` | yes |
| roles of packs that do not enable Calc (`climate-analyst`, `economist`, `scientist`, `treasury`, `oprisk`, `retail`, `analyst` of world-economy, `ops` and `shipment` of logistics, `market-analyst` and [`listed-company`](../../contracts/packs/news-and-markets.md#example-news-and-markets-listed-company) ([contract](../../contracts/packs/news-and-markets.md#co-listed-company-listed-company)) of news-and-markets, `news-analyst` and [`news-topic`](../../contracts/packs/news-and-markets.md#example-news-and-markets-news-topic) ([contract](../../contracts/packs/news-and-markets.md#topic-news-topic-news-topic)) of news-and-markets) | those packs | no (their kinds offer no Calc) |
| roles you define in Admin → Roles | the identity database | as you tick it |

Give it:

- **Admin → Roles**: tick *Use Calc: Python in the browser on what the role opens* in the role's dialog. The roles
  list shows **Calc** among each role's powers. It applies at the holder's next click (within a minute for the
  console's offer of the panel), and is audited (`role-updated … calc`).
- **Configuration or a pack**: `calc: true` on the role (`roles: { quant: { kinds: ["*"], calc: true } }`).
- **API**: `PUT /api/v1/admin/role-definitions/{name}` with `"calc": true` ([ADMIN_GUIDE.md](../ADMIN_GUIDE.md#11-the-users-and-roles-api)).

The power is kept in the identity database's `drishti_role_power` table (one row per role and power), which a
database made by an earlier release gains at the next start, without a migration.

#### 10.10 Saving your own snippets

**Save** keeps the editor's code on the server as one of your snippets: a name (1–80 letters, digits, spaces and
`. _ ( ) -`), the code (at most 50,000 characters), and the kind you wrote it on. They follow you to any browser,
are listed under **Mine** in **Snippets ▾** on every view with Calc, and only you see them. Load one, change it and
**Save** again to replace it; **Delete** removes it.

They are kept with your other saved documents (workspaces, monitors) in the identity database
(`drishti_preference`, namespace `calc-snippets`), at most 50 per person, and deleted with the person.

#### 10.11 Security model

| Question | Answer |
|---|---|
| Where does my code run? | In your browser, in a Web Worker: CPython compiled to WebAssembly. Never on the console or the server. Closing the tab ends it. `drishti.quant` is a file the worker fetches from the console's own `/static/calc/drishti_quant.py` the first time a cell names `quant`. |
| What can it read? | Only what you can: every read is a request from your page to the console with your session (`/api/view`, `/api/raw`, `/api/series`, `/api/calc/search`, `/api/calc/columns`), and the console asks the server as you. Your roles and packs decide; fields masked for your role (`drishti.security.redact`) stay masked. The worker's own reads are not requests at all: it asks the page by message, and the page fetches only those five routes, with arguments encoded into the path. Even if Calc code tried `pyfetch` or `js.fetch` to another console route, the worker's policy (next row) lets it reach nothing but static files and the runtime. |
| Can it reach the network? | No. The worker's Content Security Policy is `default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src <host>/static/ <host>/pyodide/` (the host the browser used): scripts and data from two paths of the console's own origin only, so Calc code cannot call any other console route, `/api` included, though the browser would send your session cookie with it. The runtime and its packages are served by the console under `/pyodide/<version>/`, and Pyodide is told every location explicitly (index, packages, lock file, standard library), so it never looks at its default CDN. Measured: no request left the origin in any test run (section 13). |
| Can it change data? | No more than you can by clicking. The only writes Calc adds are your own snippets. |
| What else changed in the page's security? | The page's policy adds `worker-src 'self'`; `'wasm-unsafe-eval'` (compiling WebAssembly) is granted to the Calc worker's script alone, and `'unsafe-eval'` to nothing on the page. |
| Is it recorded? | The reads are recorded as any read is ([access log](../ADMIN_GUIDE.md#8-who-looked-at-what-the-access-log)): `view`, `raw`, `history`, and `search` for searches and for `drishti.columns()` (with the fields read). The code itself is not sent anywhere unless you save it. |
| Who may use it? | Roles with `calc` ([10.9](#109-roles-who-may-use-calc)); the server checks it on its Calc endpoints, the console on its own. |
| Can one user's code affect another's? | No: each tab has its own worker and memory. A loop that never ends uses one core of your machine until you press **Stop**. |

#### 10.12 Installing the Python runtime

The runtime is not in git (53 MB, which `du -sh` shows as 51M). `python tools/drishti.py calc install` installs it once:

Run these from the Drishti folder (the repository root, or the install folder of a release).

```bash
python tools/drishti.py calc install
```

You should see:

```text
downloading https://github.com/pyodide/pyodide/releases/download/314.0.7/pyodide-314.0.7.tar.bz2 (about 340 MB, once)
verified /home/you/.cache/drishti/pyodide-314.0.7.tar.bz2 (SHA-256 192b5864e6e6d30a…)
packages: contourpy, cycler, fonttools, kiwisolver, matplotlib, numpy, packaging, pandas, patsy, pillow, pyparsing, python-dateutil, pytz, scipy, six, statsmodels
installed Pyodide 314.0.7 in …/drishti-console/web/static/vendor/pyodide: 51M on disk, 23 files
```

What it does:

1. Downloads the pinned release (Pyodide **314.0.7**, the full distribution from the official GitHub releases) into
   `~/.cache/drishti/` (`--cache` elsewhere), once.
2. Checks its **SHA-256** against the one pinned in `tools/calcruntime.py`, and refuses any other file.
3. Unpacks only the core runtime (`pyodide.mjs`, `pyodide.asm.mjs`, `pyodide.asm.wasm`, `python_stdlib.zip`) and
   numpy, pandas, scipy, statsmodels and matplotlib with everything they depend on, and writes a lock file that lists
   only those (so an import of anything else fails at once instead of asking for a file).
4. Writes `drishti-pyodide.txt` (version, SHA-256, packages) and replaces the folder as a whole.

Run it again and it says *already installed*; `--force` unpacks again. It needs only Python 3 (the standard library), on any system.

| Situation | Do |
|---|---|
| A machine with internet | `python tools/drishti.py calc install` |
| An offline machine | download `pyodide-314.0.7.tar.bz2` elsewhere, copy it over, `python tools/drishti.py calc install --tarball pyodide-314.0.7.tar.bz2` (the hash is still checked) |
| The console Docker image | nothing: `deploy/docker/console.Dockerfile` runs `tools/calcruntime.py` in a build stage (it reuses a runtime already in the build context), so the image carries the runtime and a running console needs no internet |
| Upgrading Pyodide | change `VERSION` and `SHA256` in `tools/calcruntime.py` (the release's `.tar.bz2` digest), run it, restart the console. The URL names the version, so browsers fetch the new runtime at once |

Restart the console after installing: it mounts the runtime at `/pyodide/<version>/` at start-up, served with
`Cache-Control: public, max-age=31536000, immutable` (the version is in the URL), so a browser downloads it once.

Check it:

- `curl -s localhost:17480/healthz` says `{"status":"UP","pythonRuntime":"314.0.7"}`;
- **Help → About** shows *Python runtime (Calc): Pyodide 314.0.7 · 53.2 MB, served by this console*.

Without it, views still offer Calc to those allowed, and the panel says **Python runtime not installed: run
python tools/drishti.py calc install** (Run is disabled); `/healthz` and About say the same.

#### 10.13 Sizes and performance, measured

Measured on 2026-10-01 with headless Chrome 154 on the development machine (24 cores), console and server on
localhost, the banking packs' sample data and a scratch lake of 10,000 trades a day (`python tools/drishti.py data load --store delta --trades
10000`). Each figure is a fresh browser (an empty cache) unless it says *warm*.

| What | Measured |
|---|---|
| Runtime on disk | **53.2 MB** (51 MiB), 23 files: the core 13.4 MB (`pyodide.asm.wasm` 9.6 MB, `python_stdlib.zip` 2.5 MB, `pyodide.asm.mjs` 1.3 MB), numpy and pandas 7.9 MB with their dependencies, scipy 14.0 MB, statsmodels 8.2 MB, matplotlib 9.5 MB with its dependencies |
| First open, `Alt+C` to *Python ready* (core runtime) | **1.3 s** (the worker itself: 1.1 s) |
| After **Stop** (runtime from the browser's cache) | **1.0 s** |
| First run that uses data (numpy and pandas loaded and imported) | **1.9–2.0 s** (loading 0.37 s, first `import pandas` and the run 1.5–1.6 s) |
| First run with scipy and matplotlib (the curve snippet) | **5.0 s** (loading 1.3 s, imports and run 3.7 s) |
| First run with statsmodels | **5.5 s** (loading 1.3 s, imports and run 4.2 s) |
| The same runs again (warm) | VaR **7 ms**, rate shift **7 ms**, concentration 35–49 ms (one search), desk pivot **87 ms** (one search), curve with a figure **99 ms** |
| `drishti.columns()` of 10,000 trades × 5 fields | one read of 646 KB; the whole cell (read, two groupbys, a 50-bin histogram) **92 ms** warm |
| Memory Python holds (WebAssembly heap) | 31 MB started, **79 MB** with pandas, **163 MB** with scipy, matplotlib or statsmodels |
| Requests leaving the console's origin | **none**, in every run (page and worker requests recorded) |

Speed beside native CPython 3.14 on the same machine (best of three):

| Work | Native | Calc | Calc is |
|---|---|---|---|
| A pure-Python loop of a million steps | 20.8 ms | 78.8 ms | 3.8× slower |
| numpy: sort a million floats | 6.1 ms | 78.2 ms | 13× slower |
| numpy: 500 × 500 matrix product | 1.1 ms | 72.1 ms | 65× slower (no multithreaded BLAS, no SIMD) |
| pandas: groupby-sum of 200,000 rows | 8.8 ms | 13.6 ms | 1.5× slower |

#### 10.14 Limits

- **Memory per tab.** Python's memory is WebAssembly memory in the tab: at most 4 GB, in practice 1–2 GB before the
  browser refuses to grow it. Every run's data stays until you drop it (`del df`) or press **Stop**; the status line
  shows what Python holds.
- **Speed.** Pure Python runs at a third to a quarter of native speed; vectorised pandas is close to native; heavy
  linear algebra is far slower (one thread, no SIMD). Calc is for seconds of work, not minutes.
- **Data volume.** A read moves JSON from the server through the console to the page and into Python: about 13 bytes
  a value for `drishti.columns()` (10,000 rows × 5 fields: 646 KB). 250,000 rows × 5 fields is about 16 MB, and a few
  seconds. Ask for the fields you need, aggregate with a search where you can, and use a script with a token
  ([API_REFERENCE.md (clients)](../DEVELOPER_GUIDE.md#113-clients-scripts-notebooks-and-excel)) for millions of rows.
- **Searches** return at most 1000 rows (`limit`), and read at most `drishti.search.max-scan` documents (20,000) when
  the source keeps no columns. `drishti.columns()` returns at most `drishti.calc.max-column-rows` (250,000).
- **Output** is capped per run ([10.6](#106-output-print-show-chart-figures)).
- **No network and no installs**: only the packages in [10.7](#107-packages).
- **One run at a time** per tab; **Stop** to interrupt one.
- **Browsers.** A current Chrome, Edge, Firefox or Safari runs Pyodide in a module worker; the plain (non-`await`)
  reads need JavaScript Promise Integration (current Chrome, Edge and Firefox; not Safari); the `_async` forms, and
  the starter snippets, work everywhere ([10.5.2](#1052-reading-data)).

#### 10.15 Configuration and API

| Setting | Default | Meaning |
|---|---|---|
| Server `drishti.calc.enabled` | `false` (`DRISHTI_LABS_CALC`) | The Labs switch on the server: Calc at all; off, nobody may use it and its endpoints refuse (`DRS-5002`) |
| Server `drishti.calc.max-column-rows` | `250000` | `drishti.columns()` returns at most this many entities |
| Server `drishti.calc.columns-budget` | `20s` | How long a columns read may wait for its source |
| Server `drishti.calc.max-snippet-chars` | `50000` | A saved snippet's code, at most (60,000 at most in any case) |
| Console `labs.calc` | `false` (`DRISHTI_LABS_CALC`) | The Labs switch on the console: off, no view offers Calc, whatever packs and roles say |

See [CONFIGURATION.md](../../admin/CONFIGURATION.md#drishticalc--python-in-the-browser).

Server endpoints (under `/api/v1`, with a token as usual):

| Method | Path | Does |
|---|---|---|
| `GET` | `/calc/settings` | `{"enabled", "allowed", "maxColumnRows", "maxSnippetChars"}` for the caller |
| `GET` | `/me/calc-snippets` | The caller's snippets (needs `calc`) |
| `PUT` / `DELETE` | `/me/calc-snippets/{name}` | Save `{"code", "description", "kind"}` / delete one (needs `calc`) |
| `GET` | `/search/columns/{kind}?paths=mtm,book&limit=` | Whole columns of the business date (needs `calc` and the kind); without `paths`, the fields kept as columns |
| `GET` | `/packs` | Each pack now carries `python: {enabled, snippets: [{title, description, kinds, code, file}]}` |

The console's own (same-origin, for the page): `GET /api/calc/snippets?kind=`, `PUT` / `DELETE /api/calc/snippets/{name}`,
`GET /api/calc/search?q=`, `GET /api/calc/columns/{kind}?paths=&limit=`, each refusing users without `calc` (`403
DRS-5002`), and the runtime under `/pyodide/<version>/`.

#### 10.16 Troubleshooting

| You see | Why | Do |
|---|---|---|
| No **Alt+C Calc** key on a view | The pack that owns the kind does not enable Calc, or the view is in a workspace pane | Enable it in the pack (`python: { enabled: true }`, [10.8](#108-packs-switching-calc-on-and-starter-snippets)), restart the server; open the view on its own |
| The key is greyed: *Calc needs a role with calc* | None of your roles has the `calc` power | Ask an administrator ([10.9](#109-roles-who-may-use-calc)) |
| *Python runtime not installed: run python tools/drishti.py calc install* | The console has no runtime folder | Run it on the console's machine (or rebuild the image), restart the console ([10.12](#1012-installing-the-python-runtime)) |
| *Python could not start: … Content Security Policy …* | A proxy in front of the console rewrites security headers | Let the console's own headers through for `/static/js/drishti_calc-worker.js` |
| *Python could not start: Failed to fetch* | The runtime was upgraded while the page was open | Reload the page |
| *No module named 'sklearn' (Calc has numpy, pandas, …)* | Only the packages of [10.7](#107-packages) exist | Use what is there, or a script with a token |
| *drishti.get() needs JavaScript Promise Integration … write `await drishti.get_async('trade', 'MX-20000001')` instead* | No JavaScript Promise Integration (Safari, or a browser with it switched off) | Write the call as the message says: `await drishti.get_async(...)` (and `search_async`, `columns_async`, `history_async`) |
| `DrishtiError: DRS-5002: tina may not open pnl-vector entities` | Your roles do not open that kind | As on the screen: ask for a role that opens it |
| `DrishtiError: DRS-5001: no source of trade keeps its fields as columns here` | `drishti.columns()` needs a laid-out source (a lake) | Use `drishti.search()`, or load the lake (`python tools/drishti.py data load --store delta`) |
| `DrishtiError: DRS-5001: not kept as columns for trade: [notional]` | That field is not promoted | `drishti.columns("TRD", [])` lists those that are |
| A column of `•••` | Your role sees that field masked | As on the screen: `raw` access shows it |
| *output capped at 8 MB for one run* | The output budget ([10.6](#106-output-print-show-chart-figures)) | Show less: `df.head()`, a `groupby` |
| The run never ends | A loop that does not stop, or work too heavy | **Stop**, then run something smaller |
| The tab grows slow or crashes | Python's memory (the status line shows it) | `del` large frames, **Stop**, read fewer rows |
| *search: 1,000 of 1,234 matching shown* | A search returns at most 1000 rows | Narrow the condition, or `drishti.columns()` on a lake |

#### 10.17 The `drishti.quant` module

`drishti.quant` is the pricing and risk maths the snippets share, in one file (`drishti-console/web/static/calc/drishti_quant.py`, about
780 lines of Python and numpy, no scipy, so it loads in a blink). The worker fetches it from the console the first time a
cell names `quant`; outside Calc it is an ordinary module.

```python
from drishti import quant as q

curve = q.ZeroCurve.from_points(view.doc["points"])          # CRV CRV-USD-OIS: zero rates in %, log-linear on DFs
par = q.par_swap_rate(curve, 10)                             # 0.038633: the 10Y par rate, annual fixed
q.swap_pv(curve.shifted(+1), par, 100e6, 10)                 # -84,732: receiving 100m at par loses this for +1 bp
q.black_scholes(100, 100, 1, 0.05, 0.2)                      # 10.4506
q.bs_greeks(100, 110, 0.75, 0.04, 0.3, q=0.01)["vega"]       # 33.91 per 1.00 of vol: 0.339 a vol point
q.implied_vol(12.3, lambda s: q.black_scholes(100, 105, 1, 0.03, s))   # 0.3299
q.historical_var(view.doc["PNL"], 0.99)   # PNLV PNL-MX-20000274-HS-509: 7,705 (the 5th worst of 509; the engine takes the 6th, 7,102)
```

Conventions: times in years (ACT/365F); rates, vols and yields as decimals (`0.035`, not `3.5`); zero rates
continuously compounded (`df = exp(-z t)`); spreads in bp where an argument says `_bp`. Pricing functions take
scalars or numpy arrays (broadcast) and return the same shape.

| Function | Does |
|---|---|
| **Dates and tenors** | |
| `tenor_years(tenor)` | `'3M'` 0.25, `'18M'` 1.5, `'10Y'` 10, `'1W'`, `'ON'`, `'1Y6M'`; a number is returned as it is |
| `month_code_years(code, as_of)` | A futures month code (`'X6'`: November 2026, `'Z27'`) as years from `as_of` to the middle of the month; 0 (never negative) in the contract month once its middle has passed |
| `year_fraction(start, end, basis)` | By the ISDA 2006 definitions: ACT/365F, ACT/360, 30/360 (US bond basis), 30E/360 (Eurobond: both 31sts become 30; 2026-01-15 to 2026-03-31 is 75/360), ACT/ACT ISDA (`ACT/ACT`, `AA`: each calendar year's days over 365 or 366; a calendar year is exactly 1.0) |
| **Root finding and the normal distribution** | |
| `brent(f, a, b, tol, maxiter)` | Brent's method on a bracket (raises `ValueError` if `f(a)`, `f(b)` share a sign) |
| `newton(f, fprime, x0)` | Newton's method |
| `norm_cdf(x)`, `norm_pdf(x)`, `norm_ppf(p)` | The standard normal: `math.erfc` exactly; the quantile by Acklam's approximation and a Halley step taken on the near tail (relative error below 1e-15 outside 0.4..0.6, absolute below 1e-14 everywhere, measured against 60-digit values) |
| **Curves** | |
| `ZeroCurve(times, zeros, method)` | A curve of continuously compounded zero rates: `'loglinear'` (linear in log DF, flat forwards: how OIS curves are built; the default), `'linear'` on zeros, `'cubic'` (a natural spline); flat outside the pillars |
| `ZeroCurve.from_points(points, tenor, rate, pct)` | From a document's points (`view.doc["points"]`, `["pillars"]`): tenor and zero rate in % |
| `ZeroCurve.from_dfs(times, dfs)` | From discount factors |
| `.zero(t)`, `.df(t)`, `.forward(t1, t2, simple)` | Zero rate, discount factor, forward rate (simple or continuously compounded) |
| `.shifted(bp, twist_bp, fly_bp, pivot, wing)` | A scenario curve: parallel; a twist pivoting at 5Y, linear in log maturity, scaled so 2s10s moves by `twist_bp`; a butterfly (wings up, belly down) |
| `.key_rate_bumped(i, bp)` | Pillar `i` alone moved (a triangular key-rate bump) |
| `df_from_zero`, `zero_from_df`, `forward_rate(df1, df2, t1, t2, simple)` | The conversions |
| `annuity(curve, maturity, freq, start)` | PV01 annuity of a fixed leg: sum of year fraction x DF |
| `par_swap_rate(curve, maturity, freq, start, projection)` | Par fixed rate, single-curve or with a projection curve for the forwards |
| `swap_pv(curve, fixed_rate, notional, maturity, freq, receive_fixed, start, projection)` | (fixed - par) x annuity x notional: a curve-risk tool (no stubs, fixings or accrued) |
| `bootstrap_par(maturities, par_rates, freq)` | Discount factors from par swap rates (the classic single-curve bootstrap) |
| **Bonds** | |
| `bond_cashflows(coupon, years, freq, face)` | Times, amounts and accrued of a bullet bond (a short first period when the years do not divide) |
| `bond_price(yld, coupon, years, freq, face, clean)` | Price from yield (street convention), clean by default |
| `bond_yield(price, ...)` | Yield from price (Brent) |
| `bond_risk(yld, coupon, years, freq, face)` | Macaulay and modified duration, convexity, DV01 per 100 face, dirty price |
| `z_spread(dirty_price, times, amounts, curve)` | The spread over a zero curve that reprices the cashflows |
| **Options** | |
| `black_scholes(S, K, T, r, sigma, q, call)`, `bs_greeks(...)` | Black-Scholes-Merton with a yield `q`; Greeks: price, delta, gamma, vega (per 1.00 of vol), theta (per year), rho |
| `black76(F, K, T, r, sigma, call)`, `black76_greeks(...)` | Options on forwards and futures. Black-Scholes, Black-76 and Bachelier at expiry (`T = 0`) or with no vol price the (discounted) intrinsic value of the forward, 0 at the money (not NaN) |
| `garman_kohlhagen(S, K, T, rd, rf, sigma, call)`, `gk_greeks(...)` | FX options (Black-Scholes with the foreign rate as the yield) |
| `bachelier(F, K, T, sigma_n, df, call)` | The normal model, for rates options quoted in normal vol |
| `implied_vol(price, pricer, lo, hi)` | The vol at which `pricer(vol)` equals `price` (Brent) |
| `fx_strike_from_delta(S, T, rd, rf, sigma, delta, call)` | The strike of a spot delta (premium unadjusted) |
| `smile_from_rr_bf(atm, rr, bf)` | 25-delta call and put vols from ATM, risk reversal and butterfly |
| `svi_total_variance(k, a, b, rho, m, sigma)` | Raw SVI total variance (fit it with `scipy.optimize.least_squares`) |
| **Realised volatility** | |
| `close_to_close_vol(close)`, `parkinson_vol(high, low)`, `garman_klass_vol(open, high, low, close)` | Annualised (252 days) |
| **Risk** | |
| `historical_var(pnl, confidence)`, `expected_shortfall(pnl, confidence)` | As positive losses: the k-th worst scenario, the mean of the worst |
| `parametric_var(sigma, confidence, mu, horizon_days)` | Normal VaR, square root of time |
| `kupiec_pof(exceptions, n, confidence)` | Kupiec's test: likelihood ratio, p-value, Basel traffic light scaled to 250 days |
| `max_drawdown(values)`, `sharpe(pnl, periods)`, `hhi(amounts)` | Drawdown (amount, peak, trough, series), annualised Sharpe, Herfindahl index |
| **Credit** | |
| `hazard_from_spread(spread_bp, recovery)` | The credit triangle, s / (1 - R) |
| `forward_hazards(times, term_hazards)` | Piecewise-flat hazards from term (flat-to-tenor) hazards |
| `survival(t, times, hazards)` | Survival under piecewise-flat hazards |
| `cva_from_profile(times, ee, hazard_times, hazards, recovery, discount, rate)` | (1 - R) x sum EE x DF x marginal PD, with contributions by period |
| **FRTB (sensitivities-based method)** | |
| `frtb_bucket_charge(ws, rho)` | K_b = sqrt(max(0, sum WS^2 + sum rho WS_k WS_l)) |
| `frtb_across_buckets(kb, sb, gamma)` | sqrt(sum K_b^2 + sum gamma S_b S_c), with the capped S_b when negative |

**Tests.** `drishti-console/tests/test_quant.py` checks it against textbook values: Black-Scholes 10.4506 and 5.5735 (S = K
= 100, one year, 5%, 20% vol) and Hull's 4.76 and 0.81, put-call parity with a yield, every Greek against finite
differences, Hull's Black-76 put (1.12) and Garman-Kohlhagen call (0.0430), Bachelier at the money and its parity,
implied vol round trips, a 25-delta strike's delta, curves reproducing their pillars (the natural spline against
scipy's), flat-curve par rates, annuities and swap PVs, a bootstrap of flat par rates, a par bond at 100 and the
5-year 8% bond's Macaulay duration 4.3121 and modified 3.9927, convexity and DV01 against finite differences,
Z-spread round trips, the three vol estimators on a simulated path, VaR and ES of the sample trade vector by the engine's rule, Kupiec
against the chi-squared distribution, survival, CVA and the FRTB formulas. The console's own tests skip it (the
console needs no numpy); run it with numpy and scipy installed:

```bash
python -m pytest drishti-console/tests/test_quant.py        # 34 test functions
```

#### 10.18 Snippet catalogue

Every snippet the packs ship, by pack: **Snippets ▾** lists those for the view's kind. *On* is the kinds it is offered
on and, in brackets, the sample entity it was checked on (type it, `<GO>`, `Alt+C`, pick it). *Method* names the
`drishti.quant` functions, the libraries beyond numpy and pandas, and the reads it makes (`get`, `search`, `history`).
Where a formula is simplified, *Computes* says so.

All 85 were run in headless Chrome 154 (87 runs: one `finance` snippet on three curve types) against a scratch server and console with the samples as shipped (the five
packs with snippets together, then the retired `finance` on its own), each opened on its example view and run twice: all pass. The first run,
which loads numpy and pandas (and scipy, statsmodels or matplotlib where used), took 1.9 to 6.1 s (median 2.1 s); a
warm run 6 to 830 ms (median 39 ms; 64 of 87 runs under 100 ms; the slowest read every trade of a book).

##### 10.18.1 `core` (9)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Book DV01 by currency and tenor, and the par-swap hedge** · `book_dv01_hedge.py` | `book` (`BOOK BOOK-RATES-2`) | Every trade of the book read for its bucketed DV01, summed by currency and tenor; then the hedge: for each bucket, the notional of a par swap at that tenor (on the currency's OIS curve, annual fixed, its DV01 from the annuity) that offsets the bucket's DV01, receive or pay. Each hedge is sized on its own bucket; the spill of a hedge's own risk into shorter buckets is ignored, a simplification. | quant: `ZeroCurve`, `annuity`, `par_swap_rate`, `tenor_years`; reads: search, get |
| **Counterparty capital: Basel IRB risk weight from PD** · `counterparty_irb.py` | `counterparty` (`CPTY CP-HALCYON`) | The counterparty's exposure at default from its netting sets' SA-CCR results, its one-year PD from KYC (floored at 0.03%), LGD 45% (foundation IRB, senior unsecured) and an effective maturity of 2.5 years: the Basel IRB corporate formula for capital (asset correlation, maturity adjustment, 99.9% conditional PD), risk-weighted assets, expected loss, and how capital moves with PD. The corporate curve for every counterparty, a simplification (banks and sovereigns have their own treatment). | quant: `norm_cdf`, `norm_ppf`; reads: get |
| **Currency exposure and maturity ladder** · `currency_exposure.py` | `desk`, `book`, [`legal-entity`](../../contracts/packs/core.md#example-core-legal-entity) ([contract](../../contracts/packs/core.md#le-bank-legal-entity-legal-entity)) (`BOOK BOOK-RATES-1`) | The desk's or book's trades from one search, pivoted by currency and product family: MTM and notional; notional by currency and maturity bucket (a maturity ladder, 0-1Y to 30Y+), the notional-weighted average maturity per currency, and DV01 by currency where the trades carry it. | reads: search |
| **P&L by book: a pandas pivot** · `desk_pnl_by_book.py` | `desk` (`DESK DESK-RATES`) | The desk's positions pivoted by book and product family (MTM), and today's P&L by book and currency from the trades themselves (drishti.search_async()). | reads: search |
| **Top movers and P&L contribution** · `desk_top_movers.py` | `desk`, `book` (`DESK DESK-EQD`) | The desk's trades from one search: the ten best and worst by today's P&L, P&L by product with each product's share of the absolute move, and a Pareto view (how few trades make most of the day's P&L), with the desk's total against its derived P&L. | reads: search |
| **Counterparty group: members, exposure, expected loss** · `group_exposure.py` | [`counterparty-group`](../../contracts/packs/core.md#example-core-counterparty-group) ([contract](../../contracts/packs/core.md#grp-counterparty-group-counterparty-group)) (`GRP GRP-ALDERSHOT`) | The group's members read in full: rating, sector, net MTM, peak PFE (the exposure engine's, CPTY:<id>) and one-year PD; the group's exposure against its limit, each member's share, expected loss as PD x LGD 60% x peak PFE, a rating-weighted PD for the group, and the netting sets behind each member. | reads: get |
| **Issuer: bond spreads against CDS, the basis** · `issuer_bond_cds_basis.py` | `issuer` (`ISS ISS-GRANITE`) | The issuer's bonds read in full: yield, Z-spread over their benchmark curve recomputed from price (bullet, street convention), and the CDS spread at each bond's maturity interpolated on the issuer's credit curve; the CDS-bond basis (CDS less Z-spread, negative: the bond is cheap to the CDS), with the term structures drawn. Z-spread against a government curve, not the par-equivalent spread a desk would use: an approximation. | quant: `ZeroCurve`, `bond_cashflows`, `bond_yield`, `tenor_years`, `z_spread`; reads: get |
| **Legal entity: risk against return by book and desk** · `legal_entity_risk_return.py` | `legal-entity` (`LE LE-NY`) | The legal entity's books with their VaR, today's P&L and MTM: P&L per unit of VaR by book, books grouped by desk with VaR summed (no diversification, an upper bound) and the desk's P&L over VaR, the books in a risk-return scatter, and the entity's share of P&L and VaR by desk. | pandas |
| **A trader's book: mix, P&L and hit rate** · `trader_book.py` | `trader` (`TRDR TRDR-ASHAH`) | Every trade the trader booked (one search): count, notional and MTM by product and currency, today's P&L with the share of winning trades and the average win against the average loss, the ten largest positions by MTM, and how the trader's day compares with the desk's other traders. | reads: search |

##### 10.18.2 `counterparty-risk` (12)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Collateral: haircuts, concentration and a haircut stress** · `collateral_haircuts.py` | [`collateral-balance`](../../contracts/packs/counterparty-risk.md#example-counterparty-risk-collateral-balance) ([contract](../../contracts/packs/counterparty-risk.md#coll-collateral-balance-collateral-balance)) (`COLL COLL-HALCYON-NY`) | The collateral balance's positions: market value, haircut and value after haircut (checked against the booked value), concentration of the collateral by asset (HHI), collateral issued by the counterparty itself (wrong-way collateral), and the collateral value when haircuts double and triple and market values fall 5%, against what the netting set's CSA requires (net MTM less the counterparty's threshold): the shortfall a stress would leave. | quant: `hhi`; reads: get |
| **Collateralised exposure by Monte Carlo: threshold, MTA and MPoR** · `collateral_mpor_mc.py` | `netting-set` (`NSET NS-ALDERSHOT-NY`) | A Monte Carlo of the netting set's value as a driftless Brownian motion whose volatility is read from the exposure engine's uncollateralised 1Y expected exposure (EE = sigma sqrt(t / 2 pi); exposure-result, computed from the paths), with daily margining under the CSA: the collateral follows the value less the threshold, moves only above the minimum transfer amount, and lags by the margin period of risk. EE and PFE 95% with and without collateral by tenor (2,000 paths, a fixed seed). A teaching model, not the engine's. | quant: `tenor_years`; reads: get |
| **Exposure concentration: HHI by name, sector, rating and country** · `concentration_hhi.py` | `credit-limit`, `counterparty-group`, `counterparty` (`LIM LIM-SUMMIT`) | Every counterparty's peak PFE 95% (the exposure engine's exposure-result at CPTY:<id>, computed from the paths; counterparties the run does not cover are left out) as the exposure measure: the Herfindahl-Hirschman index by counterparty, sector, rating and country (and the equivalent number of equal names, 1/HHI), the top five names' share, expected loss as PD x LGD 60% x peak PFE from each counterparty's one-year PD, and exposure by rating grade. Peak PFE overstates expected exposure; it is the limit measure. | quant: `hhi`; reads: search, get |
| **CVA from the computed exposure profile and the counterparty's spread** · `cva_from_ee.py` | `netting-set`, `exposure-result`, `cva` (`NSET NS-ALDERSHOT-NY`) | Unilateral CVA approximated as (1 - R) x sum of EE x discount factor x marginal default probability over the profile's periods, the EE being the exposure engine's (exposure-result of the netting set, computed from the paths): default probabilities from the counterparty's CDS curve when there is one (term hazards as forward hazards) or else from its one-year PD as a flat hazard; discounting on the USD OIS curve; CVA by period, its CS01 for a 1 bp spread bump, beside the stored CVA. EE at each period's end and no wrong-way risk: a simplification. | quant: `ZeroCurve`, `cva_from_profile`, `forward_hazards`, `tenor_years`; reads: get |
| **Exposure profile: EPE, effective EE and EEPE, checked** · `exposure_profile_stats.py` | `exposure-result` (`EXP NSET:NS-ALDERSHOT-FRA`) | From the exposure engine's profile of a scope (exposure-result, computed from the paths): expected exposure (EE), negative exposure and PFE at 95% and 99% by primary date; EPE (the time-weighted average of EE over the first year, or the scope's longest maturity when shorter), effective EE (EE never allowed to fall, the Basel rule) and effective EPE recomputed step by step and set beside the engine's own figures, the peak PFE and its date, and the profile drawn. | numpy, pandas |
| **Credit limit utilisation heatmap** · `limit_heatmap.py` | `credit-limit`, `counterparty` (`LIM LIM-SUMMIT`) | Every credit limit read in full: utilisation by counterparty and tenor bucket as a table and a heatmap (matplotlib), the buckets in breach (100% or more) or early warning (80% or more), headroom by bucket, and where this limit stands; limits sorted by their worst bucket. | matplotlib; reads: search, get |
| **Margin calls: status, disputes and ageing** · `margin_calls.py` | [`margin-call`](../../contracts/packs/counterparty-risk.md#example-counterparty-risk-margin-call) ([contract](../../contracts/packs/counterparty-risk.md#mc-margin-call-margin-call)), `netting-set`, `csa` (`MC MC-SUMMIT-NY-1`) | Every margin call in one search: by status, direction and margin type, the amounts called and disputed and the dispute rate, the calls still open with their age in days, the largest disputes, and this netting set's calls; a bar of called and disputed amounts by netting set. | reads: search |
| **MTM concentration by product and netting set** · `mtm_concentration.py` | `netting-set`, `counterparty` (`NSET NS-MERIDIAN-RE-NY`) | The counterparty's trades from drishti.search_async(): MTM by product and by netting set, each one's share of gross MTM, and a Herfindahl index of how concentrated it is. | reads: search |
| **Netting benefit and collateral under the CSA** · `netting_collateral.py` | `netting-set` (`NSET NS-HALCYON-NY`) | The netting set's trades: positive and negative MTM, net MTM, the netting benefit and the net-to-gross ratio; then the CSA's terms (thresholds, minimum transfer amount, independent amount) turned into the collateral either side should hold, the call due today against what is held, and the exposure left after netting and collateral. Uses the trades shown on the netting set (the largest, when it lists only some). | reads: get |
| **SA-CCR exposure at default, recomputed** · `saccr_recompute.py` | [`sa-ccr`](../../contracts/packs/counterparty-risk.md#example-counterparty-risk-sa-ccr) ([contract](../../contracts/packs/counterparty-risk.md#saccr-sa-ccr-exposure-sa-ccr)) (`SACCR SACCR-SUMMIT-NY`) | The SA-CCR calculation rebuilt from its parts: each hedging set's add-on as supervisory factor x effective notional, summed by asset class (no correlation inside a hedging set, a simplification for commodities and equities), the PFE multiplier min(1, 5% + 95% exp((V - C) / (2 x 95% x AddOn))), and EAD = alpha x (RC + multiplier x AddOn), set beside the engine's figures; the add-on by asset class drawn. | reads: get |
| **SIMM: initial margin across risk classes** · `simm_aggregation.py` | `simm` (`SIMM SIMM-SUMMIT`) | The SIMM result's initial margin by risk class, the simple sum against the reported total, the total aggregated across risk classes with a correlation matrix in the shape of ISDA SIMM's (illustrative values: use the published table of the version in force), the single flat correlation the reported total implies, the diversification benefit, and margin posted against received. | pandas |
| **Wrong-way risk flags on a counterparty's trades** · `wrong_way_flags.py` | `counterparty`, `netting-set` (`CPTY CP-SOLARIS`) | Every trade with the counterparty (search, then each trade): specific wrong-way risk where the trade references the counterparty itself or its group (as issuer, underlying or reference entity), general wrong-way risk where the reference issuer shares its sector or country, and protection sold or equity sold on names that move with it; flagged trades with their MTM, and the share of positive MTM they carry. Heuristic flags from reference data, not a model. | reads: search, get |

##### 10.18.3 `market-data` (27)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Bond yield, duration, convexity and Z-spread** · `bond_analytics.py` | `bond` (`BND BND-GBGRAN031444`) | The bond's yield to maturity solved from its clean price (street convention, bullet, coupons on a regular schedule back from maturity), Macaulay and modified duration, convexity and DV01 per 1m face, set beside the security master's yield and duration, and its Z-spread over the benchmark curve (continuously compounded), beside the master's. | quant: `ZeroCurve`, `bond_cashflows`, `bond_price`, `bond_risk`, `bond_yield`, `z_spread`; reads: get |
| **Bond P&L under yield moves: duration, convexity and full repricing** · `bond_scenarios.py` | `bond` (`BND BND-US0UST151040`) | The bond repriced at yields from -200 to +200 bp against two approximations, duration alone and duration with convexity, with the error of each; and the 1-year horizon return (coupon carry plus price change) under each move, the breakeven yield rise that wipes out a year's carry. | quant: `bond_price`, `bond_risk`, `bond_yield` |
| **Cap prices from the normal-vol surface** · `cap_pricer.py` | [`cap-vol-surface`](../../contracts/packs/market-data.md#example-market-data-cap-vol-surface) ([contract](../../contracts/packs/market-data.md#cpv-capfloor-volatility-surface-cap-vol-surface)) (`CPV CPV-USD`) | Caps of 1Y to 10Y priced as strips of quarterly caplets with the Bachelier formula: forwards from the currency's projection curve, discounting on its OIS curve, each cap at its own flat normal vol from the surface (ATM at the cap's par rate, and the fixed strikes k2 to k5 read as 2% to 5%); premium in bp of notional and per 100m. Flat cap vols, not stripped caplet vols: a simplification. | quant: `ZeroCurve`, `bachelier`, `tenor_years`; reads: get |
| **CDS par spreads, risky annuity and upfront** · `cds_pricer.py` | [`credit-curve`](../../contracts/packs/market-data.md#example-market-data-credit-curve) ([contract](../../contracts/packs/market-data.md#cds-credit-curve-credit-curve)) (`CDS CDS-GRANITE`) | Standard CDS of 1Y to 10Y priced on the curve's hazards (term hazards turned into piecewise-flat forward hazards), discounted on the currency's OIS curve: protection and premium legs (quarterly, accrual on default as half a period), RPV01, par spread against the quote, the upfront for a 100 bp (or 500 bp) coupon, and CS01 per 10m from a 1 bp spread bump (hazards moved by the credit triangle). | quant: `ZeroCurve`, `forward_hazards`, `survival`, `tenor_years`; reads: get |
| **Futures curve: contango, backwardation and roll yield** · `cmd_curve_roll.py` | [`commodity-curve`](../../contracts/packs/market-data.md#example-market-data-commodity-curve) ([contract](../../contracts/packs/market-data.md#cmdc-commodity-forward-curve-commodity-curve)) (`CMDC CMDC-WTI`) | The futures strip by contract month: each month's spread to the next and to the front, the annualised roll yield ln(F1/F2)/(T2 - T1) that a long position earns (backwardation) or pays (contango) rolling forward, the 12-month carry, and the curve drawn with open interest beneath it. | quant: `month_code_years`; matplotlib |
| **Black-76 options on each futures month** · `cmd_options_b76.py` | [`commodity-vol-surface`](../../contracts/packs/market-data.md#example-market-data-commodity-vol-surface) ([contract](../../contracts/packs/market-data.md#cmdv-commodity-volatility-surface-commodity-vol-surface)) (`CMDV CMDV-WTI`) | For every contract month on the commodity vol surface: the future from the commodity curve, Black-76 prices of the ATM straddle and of 90% puts and 110% calls at the surface's vols, the Greeks (delta, gamma, vega per vol point, theta per day) per contract, and the skew; discounting at the USD OIS curve. Options are taken to expire at the middle of the contract month, a simplification. | quant: `ZeroCurve`, `black76`, `black76_greeks`, `month_code_years`; reads: get |
| **Hazard rates, survival and default probabilities** · `credit_hazard.py` | `credit-curve` (`CDS CDS-GRANITE`) | From the CDS spreads: hazard rates by the credit triangle (spread over one minus recovery, an approximation) beside the curve's term hazards, the forward (piecewise-flat) hazards between tenors, survival probabilities, and cumulative, period and conditional default probabilities, year by year. | quant: `forward_hazards`, `hazard_from_spread`, `survival`, `tenor_years`; matplotlib |
| **Carry and roll-down of receivers** · `curve_carry_roll.py` | [`ir-curve`](../../contracts/packs/market-data.md#example-market-data-ir-curve) ([contract](../../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve)) (`CRV CRV-USD-OIS`) | Three months' carry and roll-down of receiving par swaps along the curve, in bp and per 100m, and per unit of DV01: carry is the fixed rate against the 3M forward it is funded at, roll-down the fall in par rate as the swap ages on an unchanged curve. A simplified, curve-only view (no fixing, no bid-offer). | quant: `ZeroCurve`, `annuity`, `par_swap_rate` |
| **OIS curves across currencies, and swap spreads** · `curve_compare_ccy.py` | `ir-curve` (`CRV CRV-EUR-OIS`) | Every OIS discount curve (drishti.search_async, then each curve's pillars): 2Y, 5Y, 10Y and 30Y zero rates, 2s10s and 5s30s in bp, drawn together; and for this curve's currency, the OIS curve against the government curve (the swap spread by tenor). | quant: `ZeroCurve`; reads: search, get |
| **Zero, discount and forward curves** · `curve_forwards.py` | `ir-curve` (`CRV CRV-USD-OIS`) | From the curve's pillars: discount factors, 3M simple forwards and 1Y forwards on a quarterly grid under log-linear interpolation of discount factors (flat forwards between pillars), set beside the curve's own forwards. | quant: `ZeroCurve`, `tenor_years`; matplotlib |
| **Key-rate DV01 by bump and reprice** · `curve_key_rate_dv01.py` | `ir-curve` (`CRV CRV-USD-OIS`) | A 10Y receive-fixed swap at par (100m) repriced with each pillar of the curve moved 1 bp in turn (triangular key-rate bumps): DV01 by pillar, their sum against a parallel 1 bp move, and the convexity of a 25 bp move. | quant: `ZeroCurve`, `par_swap_rate`, `swap_pv`, `tenor_years` |
| **Par swap rates, annuities and curve slopes** · `curve_par_swaps.py` | `ir-curve` (`CRV CRV-USD-OIS`) | Par fixed rates and PV01 annuities of spot-starting swaps from 1Y to 30Y off the curve (single-curve, annual fixed leg), the par curve against the zero curve, and the 2s10s, 5s30s slopes and the 2s5s10s butterfly in bp. | quant: `ZeroCurve`, `annuity`, `par_swap_rate` |
| **Parallel, twist and butterfly scenarios** · `curve_scenarios.py` | `ir-curve` (`CRV CRV-USD-OIS`) | The curve moved in parallel, steepened and flattened (a twist pivoting at 5Y) and bent (a butterfly), and the full-revaluation P&L of receiving 2Y, 5Y, 10Y and 30Y par swaps (100m each) under every scenario, with the shocked curves drawn. Illustrative shapes, not a calibrated scenario set. | quant: `ZeroCurve`, `par_swap_rate`, `swap_pv`; matplotlib |
| **Interpolate the curve at any tenor** · `curve_tenor.py` | `ir-curve` (`CRV CRV-USD-OIS`) | Zero rates at tenors the curve does not quote: linear on zero rates, log-linear on discount factors (how the curve is built) and a cubic spline (scipy), with a matplotlib figure. | scipy, matplotlib |
| **Beta, correlation and return distribution against the index** · `eq_beta_returns.py` | `equity` (`EQ EQ-HLXP`) | Daily log returns of the stock and of its index from their price histories, aligned by date: an OLS regression with statsmodels (beta, alpha, R squared, the summary), the correlation, set beside the beta the security master holds, and the returns' skew, excess kurtosis and Jarque-Bera normality test (scipy). | scipy, statsmodels; reads: get |
| **Realised volatility: close-to-close, Parkinson, Garman-Klass** · `eq_realised_vol.py` | `equity`, `commodity` (`EQ EQ-NVTK`) | Annualised realised volatility from the daily bars by three estimators (close-to-close, Parkinson's high-low, Garman-Klass's OHLC; the range estimators ignore overnight gaps), a rolling 20-day volatility, and the gap to the front implied volatility of the linked surface: the volatility risk premium. | quant: `close_to_close_vol`, `garman_klass_vol`, `parkinson_vol`; reads: get |
| **SVI fit of the smile, expiry by expiry** · `eq_svi_fit.py` | [`equity-vol-surface`](../../contracts/packs/market-data.md#example-market-data-equity-vol-surface) ([contract](../../contracts/packs/market-data.md#eqv-equity-volatility-surface-equity-vol-surface)), `commodity-vol-surface` (`EQV EQV-GRNT`) | A raw SVI curve (Gatheral) fitted to each expiry's total variance in log-moneyness with scipy's least squares: the five parameters, the fit error in vol points, a check of the wings against Lee's moment bound (total variance rising at most 2 per unit of log-moneyness), and the fitted smiles drawn over the quotes. Spot moneyness is used as forward moneyness, a simplification. | quant: `month_code_years`, `svi_total_variance`, `tenor_years`; scipy, matplotlib |
| **Equity vol surface: skew, term structure and arbitrage checks** · `eq_vol_arbitrage.py` | `equity-vol-surface` (`EQV EQV-SPX`) | The surface's smile per expiry and its ATM term structure; skew (90 less 110) and wings; a calendar check (total variance must not fall with expiry at any moneyness) and a butterfly check (undiscounted Black call prices must be convex and decreasing in strike). Moneyness is read as forward moneyness, a simplification. | quant: `black76`, `tenor_years`; matplotlib |
| **Overnight rate compounded in arrears** · `fixing_compounding.py` | [`rate-fixing`](../../contracts/packs/market-data.md#example-market-data-rate-fixing) ([contract](../../contracts/packs/market-data.md#fix-rate-index-fixings-rate-fixing)) (`FIX FIX-ESTR`) | The overnight fixings compounded in arrears (each rate weighted by the calendar days it applies, ACT/360, as SOFR and ESTR swaps and the published averages do) against their simple average, the annualised compounded rate over the window, the interest on 100m, and the rate and volume drawn. | pandas |
| **Cross rates and a triangular arbitrage check** · `fx_cross_triangle.py` | [`fx-spot`](../../contracts/packs/market-data.md#example-market-data-fx-spot) ([contract](../../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)) (`FX FX-EURJPY`) | Every FX spot read once, each pair re-derived through USD (EUR/JPY from EUR/USD and USD/JPY, ...) and compared with its own mid in pips, and a check on bids and asks: buying through the two legs and selling the direct pair (and the reverse) must not make money. | reads: search, get |
| **Forward points, implied yield differential and basis** · `fx_forward_points.py` | [`fx-forward-curve`](../../contracts/packs/market-data.md#example-market-data-fx-forward-curve) ([contract](../../contracts/packs/market-data.md#fxf-fx-forward-curve-fx-forward-curve)) (`FXF FXF-EURUSD`) | Outrights from spot and forward points, the yield differential the forwards imply (ln(F/S)/T, continuously compounded) against the two currencies' OIS curves, and the gap between them: a rough cross-currency basis (it ignores spot lag, day counts and the CSA). | quant: `ZeroCurve`, `tenor_years`; reads: get |
| **FX correlations and principal components** · `fx_pca.py` | `fx-spot` (`FX FX-EURUSD`) | Daily log returns of every currency against USD from the spot rates' price history, their correlation matrix (a heatmap), and a principal component analysis with numpy: how much the first component (the dollar factor) explains, each currency's loadings, and this pair's realised volatility. | matplotlib; reads: search, get |
| **FX smile: strikes from deltas, RR and BF checks** · `fx_smile.py` | [`fx-vol-surface`](../../contracts/packs/market-data.md#example-market-data-fx-vol-surface) ([contract](../../contracts/packs/market-data.md#fxv-fx-volatility-surface-fx-vol-surface)) (`FXV FXV-USDJPY`) | Each expiry's 10 and 25 delta vols turned into strikes (spot delta, premium unadjusted, Garman-Kohlhagen) with forwards from the forward curve and rates from the OIS curves, the quoted 25D risk reversal and butterfly checked against the vols, the ATM term structure and a calendar check on total variance; the smiles drawn against strike. | quant: `ZeroCurve`, `fx_strike_from_delta`, `tenor_years`; matplotlib; reads: get |
| **Price straddles, risk reversals and strangles on the surface** · `fx_vanilla_pricer.py` | `fx-vol-surface` (`FXV FXV-EURUSD`) | Garman-Kohlhagen premiums and Greeks, per expiry, of the ATM straddle, the 25 delta risk reversal and the 25 delta strangle on 10m of the base currency, at the surface's vols, with forwards from the forward curve and the quote currency's OIS rate: premium in quote currency, in pips and in % of base notional; delta, vega per vol point. | quant: `ZeroCurve`, `fx_strike_from_delta`, `gk_greeks`, `tenor_years`; reads: get |
| **Breakevens, forward inflation and real rates** · `inflation_breakeven.py` | [`inflation-curve`](../../contracts/packs/market-data.md#example-market-data-inflation-curve) ([contract](../../contracts/packs/market-data.md#infc-inflation-curve-inflation-curve)) (`INFC INFC-USCPI`) | The inflation curve's zero-coupon breakevens by tenor, forward breakevens between tenors (the 5y5y and its kin, from compounded breakevens), real rates as the currency's OIS zero rate less the breakeven (Fisher, ignoring the risk premium and convexity), the latest index fixings' year-on-year rate, and the seasonality the curve applies by month. | quant: `ZeroCurve`, `tenor_years`; reads: get |
| **Drawdown, worst days and the return histogram** · `price_drawdown.py` | `equity`, [`equity-index`](../../contracts/packs/market-data.md#example-market-data-equity-index) ([contract](../../contracts/packs/market-data.md#eqx-equity-index-equity-index)), `commodity`, `bond`, `fx-spot` (`EQX EQX-SPX`) | From the closes the view holds (daily bars, or the price history): cumulative return, the running drawdown from the high, the largest peak-to-trough fall and whether it has recovered, the five worst and best days, and a histogram of daily returns with the normal density of the same mean and volatility (matplotlib). | quant: `max_drawdown`, `norm_pdf`; matplotlib |
| **Swaption premiums from the normal-vol grid** · `swaption_cube.py` | [`ir-vol-cube`](../../contracts/packs/market-data.md#example-market-data-ir-vol-cube) ([contract](../../contracts/packs/market-data.md#irv-swaption-volatility-cube-ir-vol-cube)) (`IRV IRV-EUR`) | ATM payer swaptions for every expiry and swap tenor of the cube: the forward swap rate and its annuity from the currency's OIS curve (single-curve, annual fixed leg), the Bachelier premium at the grid's normal vol, in bp of notional and per 100m, and vega per bp of vol; the vol grid as a heatmap. | quant: `ZeroCurve`, `annuity`, `bachelier`, `norm_pdf`, `par_swap_rate`, `tenor_years`; matplotlib; reads: get |

##### 10.18.4 `market-risk` (6)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **FRTB GIRR delta charge from the book's trades (simplified SBM)** · `frtb_girr_delta.py` | [`frtb-sensitivity`](../../contracts/packs/market-risk.md#example-market-risk-frtb-sensitivity) ([contract](../../contracts/packs/market-risk.md#frtb-frtb-sensitivities-frtb-sensitivity)), `book` (`FRTB FRTB-RATES-1`) | The sensitivities-based method for interest-rate delta, built bottom-up: every trade of the book read, DV01 by tenor bucket summed by currency (a GIRR bucket is a currency), weighted by the Basel tenor risk weights, aggregated within each currency with the tenor correlation max(exp(-3% /Tk - Tl/ / min(Tk, Tl)), 40%) and across currencies at 50%, under the low, medium and high correlation scenarios. Simplified: one curve per currency, no basis or inflation risk factors, no reduced weights for major currencies. | quant: `frtb_across_buckets`, `frtb_bucket_charge`, `tenor_years`; reads: search, get |
| **FRTB charges across books: delta, vega, curvature** · `frtb_overview.py` | `frtb-sensitivity` (`FRTB FRTB-FX-1`) | Every book's FRTB standardised result (one search, then each document): delta, vega and curvature charges and the total by book and by risk class, each component's share, a check that the three components add up to the total, and the books ranked by charge with this one marked. | reads: search, get |
| **P&L attribution by factor across books and desks** · `pnl_attribution_mix.py` | [`pnl-explain`](../../contracts/packs/market-risk.md#example-market-risk-pnl-explain) ([contract](../../contracts/packs/market-risk.md#pnl-pl-explain-pnl-explain)) (`PNL PNL-EQD-1`) | Every book's P&L attribution read in full and pivoted: factor by book, and summed by desk (the desk taken from each book's own record), with totals; each factor's share of the absolute explained P&L, and the desks' factor mix as a heatmap (matplotlib). | matplotlib; reads: search, get |
| **P&L explain quality across books** · `pnl_explain_quality.py` | `pnl-explain` (`PNL PNL-RATES-1`) | This book's explain as steps (carry, roll-down, deltas, vega, theta, new trades, unexplained) and every book's explain from one search: the unexplained P&L as a share of actual, books over a 10% threshold flagged, and actual against explained P&L as a scatter. A day's snapshot; FRTB's P&L attribution test runs on a year of daily figures. | reads: search |
| **Stress scenario P&L rebuilt from trade sensitivities** · `stress_from_sensitivities.py` | [`stress-scenario`](../../contracts/packs/market-risk.md#example-market-risk-stress-scenario) ([contract](../../contracts/packs/market-risk.md#scn-stress-scenario-stress-scenario)) (`SCN SCN-GFC2008`) | The scenario's rate shocks applied to every trade's DV01 from the risk engine's trade valuations (one search over TVAL, every source system): a currency's rate move times the DV01 of the trades in that currency, and a 2Y/10Y twist interpolated by MATURITY_DATE; by desk and by source system, beside the engine's stress results. A trade valuation carries MTM, PNL_1D and DV01 only, so the scenario's other factors (spreads, equities, commodities, FX, vol) are listed as not rebuilt; first order, no convexity, cross effects or basis. | reads: search |
| **Stress results: scenarios by desk, limits and the worst case** · `stress_heatmap.py` | [`stress-result`](../../contracts/packs/market-risk.md#example-market-risk-stress-result) ([contract](../../contracts/packs/market-risk.md#str-stress-result-stress-result)), `stress-scenario` (`STR STR-GFC2008-RATES`) | Every stress result in one search, pivoted scenario by desk with totals; each desk's worst scenario and its use of the stress limit (results read in full for their limits), the scenarios ranked by firm-wide loss, and a heatmap of the matrix (matplotlib). | matplotlib; reads: search, get |

##### 10.18.5 `trading` (9)

Every snippet reads the production-shaped trade (the common UPPER_SNAKE schema of every source system: `LEGS`, `CASH_FLOWS`, `RESETS`, `STRIKE_PRICE`, `OPTION_TYPE` ...) and, for MTM and DV01, the risk engine's valuation `TVAL-<TRADE_ID>` (market-risk). That schema names no underlying instrument, spot or vol surface, so the model-specific option pricers of 2.2 (Black-76 on a commodity future, Black-Scholes on an equity, Garman-Kohlhagen, FX forward marking) and the P&L history snippet were retired; *Option P&L across the underlying and vol* covers every option, and *Payment ladder* replaces the P&L history.

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Bond position: yield risk and scenarios** · `bond_position.py` | `trade` (`TRD CLY-3000157`) | The bond position's yield from its booked PRICE (clean, per 100), the coupon from its first interest flow's RATE and the coupon frequency from the flows' spacing, its modified duration and convexity, DV01 of the face held (the leg's NOTIONAL, long or short by BUY_SELL_CODE) against the risk engine's DV01 (TVAL-<TRADE_ID>), and the P&L of the position for yield moves of -100 to +100 bp by full repricing. | quant: `bond_price`, `bond_risk`, `bond_yield`; reads: get |
| **CDS position: mark, CS01 and jump to default** · `cds_trade.py` | `trade` (`TRD CLY-3000085`) | The single-name CDS marked on a credit curve (term hazards as forward hazards, quarterly premium with half-period accrual on default, discounting on the leg's DISCOUNT_INDEX curve): the running coupon from the premium flows' RATE, the par spread at MATURITY_DATE, the risky annuity, the MTM for protection bought (BUY_SELL_CODE B) or sold, CS01 for a 1 bp spread bump and jump to default at the curve's RECOVERY. The common trade schema names no reference entity, so the curve is REFERENCE below, else the counterparty's own (CDS-<name>), else CDS-UST. | quant: `ZeroCurve`, `forward_hazards`, `survival`, `tenor_years`; reads: get |
| **Option P&L across the underlying and vol (full revaluation)** · `option_scenario_grid.py` | `trade` (`TRD MX-20000187`) | Any trade with an OPTION_TYPE and a STRIKE_PRICE (equity, FX, commodity or rates options of every source system): Black-76 on the forward at zero rates (a scenario shape, not a booking price), with the forward at the strike unless FORWARD is set (the common trade schema names no underlying), expiry at MATURITY_DATE and QUANTITY units long or short by BUY_SELL_CODE; the P&L on a grid of forward moves (-20% to +20%) and vol moves (-10 to +10 points) against the delta-gamma approximation along the forward axis; a heatmap. | quant: `black76`, `black76_greeks`; matplotlib |
| **MTM under parallel rate moves** · `rate_shift.py` | `trade` (`TRD MX-20000001`) | The trade's MTM after parallel moves of -100 to +100 bp, first order from the risk engine's DV01 (market-risk trade-valuation TVAL-<TRADE_ID>: MTM, DV01, PNL_1D), the trade's notional ladder by leg beside it. | pandas; reads: get |
| **Swap key-rate DV01 by bump and reprice** · `swap_key_rate.py` | `trade` (`TRD MX-20000001`) | The trade's unsettled interest flows (LEGS[].CASH_FLOWS, fixed at their RATE, floating not fixed yet projected off the leg's DISCOUNT_INDEX curve) repriced with each pillar of the curve moved 1 bp in turn (triangular bumps, projection and discounting together): key-rate DV01 by pillar from full revaluation, their sum against a parallel bump, and the risk engine's DV01 (TVAL-<TRADE_ID>) beside them. | quant: `ZeroCurve`; reads: get |
| **Swap legs repriced off the curves** · `swap_reprice.py` | `trade` (`TRD MX-20000001`) | Every unsettled cash flow of the trade's LEGS: fixed flows at their booked RATE, floating flows not fixed yet re-projected as simple forwards off the leg's DISCOUNT_INDEX curve plus FLOAT_SPREAD (single-curve; a flow already fixing keeps its booked RATE), signed by LEG_ECONOMIC_TYPE (Pay or Receive) and discounted on the same curve (ACT/365F); each leg's PV, the net against the risk engine's MTM (trade-valuation TVAL-<TRADE_ID>), and a cash-flow ladder by year. | quant: `ZeroCurve`; reads: get |
| **Swaption: Bachelier price, DV01 and vega** · `swaption_bachelier.py` | `trade` (`TRD MX-20000097`) | The European swaption priced with the Bachelier (normal) model from its own underlying swap: expiry at MATURITY_DATE, the forward swap rate and the annuity from the fixed leg's flows (LEGS[].CASH_FLOWS start and end dates) on the leg's DISCOUNT_INDEX curve (single-curve), the strike STRIKE_PRICE, payer or receiver from OPTION_TYPE, the normal vol read off the currency's vol cube (IRV-<CURRENCY>) at its expiry and swap tenor (linear in both); premium, delta as DV01, gamma and vega per bp of vol for the position (BUY_SELL_CODE, QUANTITY), against the risk engine's MTM; a ladder of the premium against forward-rate moves. | quant: `ZeroCurve`, `bachelier`, `tenor_years`; reads: get |
| **Payment ladder: what the trade pays and receives, by month** · `trade_payment_ladder.py` | `trade` (`TRD 1000001`) | Every cash flow of the trade's LEGS with a PAYMENT_DATE, signed by the leg's LEG_ECONOMIC_TYPE (Pay or Receive; a blank side reads from BUY_SELL_CODE) at NOTIONAL x RATE x the accrual for interest and price flows and NOTIONAL for principal and settlement flows (an estimate where the extract leaves the amount out); the payments due in the next 7, 30 and 90 days, pay against receive by month as bars and the net as a running total. | pandas |
| **The trade among its peers** · `trade_vs_peers.py` | `trade` (`TRD MX-20000001`) | Every valuation of the same PRODUCT_TYPE across the source systems (drishti.search_async over the risk engine's trade valuations, TVAL): NOTIONAL, MTM, PNL_1D and MTM as a share of notional; where this trade ranks (percentiles) in each, the peers by PORTFOLIO and CURRENCY, and a scatter of MTM against notional with this trade marked. | reads: search, get |

##### 10.18.6 `finance` (10, the retired example pack under `examples/packs/`)

Two inline in `pack.yaml`: *MTM under parallel rate moves* (`trade`, `TRD IRS-48213`) and *Member trades, summed
from the screen* (`netting-set`, `NS-NORTH-01`, from `view.tables`); and eight files, `examples/packs/finance/python/*.py`
(hand-written, not generated):

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Swap repriced on the curve, with key-rate DV01** · `a_swap_key_rate.py` | `trade` | The swap's projected cashflows repriced on its discount curve's pillars (log-linear on discount factors): fixed amounts as booked, floating amounts projected from the curve's simple forwards; each leg's PV against the booked one, then key-rate DV01 by bumping each pillar 1 bp in turn (full revaluation), set beside the booked DV01 by tenor. | quant: `ZeroCurve`; reads: get |
| **Swaption: Bachelier price, implied normal vol and Greeks** · `b_swaption_bachelier.py` | `trade` | The swaption repriced with the Bachelier (normal) model from its own forward swap rate, annuity and normal vol; the normal vol implied by the booked MTM (Brent); delta (DV01), gamma and vega per bp of vol; and the premium across forward-rate and vol moves. | quant: `bachelier`, `implied_vol` |
| **FX swap: points, carry and mark to market** · `c_fx_swap.py` | `trade` | The FX swap's near and far legs; the booked swap points against today's points at the far date interpolated on the forward curve, the yield differential they imply (ln(F/S)/T) against the two OIS curves, the MTM recomputed from the cashflows (each currency discounted on its own curve, converted at spot), and the MTM for spot and points moves. | quant: `ZeroCurve`; reads: get |
| **Futures position: variation margin and the curve's roll** · `d_future_margin.py` | `trade` | The futures position's daily settlements: variation margin recomputed as lots x contract size x the settlement change, checked against the booked margin and the MTM; the value of a one-dollar move; and the futures curve it sits on: contango or backwardation month by month and the annualised roll yield from this contract to the next. | reads: get |
| **Netting set: EPE, EEPE, CVA and collateral** · `e_netting_set_cva.py` | `netting-set` | The netting set's exposure profile: EPE and effective EPE over the first year, peak PFE; CVA approximated as (1 - R) x sum of EE x discount factor x marginal PD with a flat hazard from the counterparty's one-year PD (recovery 40%, USD SOFR discounting; a simplification); and the CSA's terms turned into today's collateral position against the net MTM. | quant: `ZeroCurve`, `cva_from_profile`, `tenor_years`; reads: get |
| **Overnight index: compounded in arrears and averages** · `f_sofr_compounding.py` | `index` | The index's daily fixings compounded in arrears (each rate weighted by the calendar days it applies, ACT/360) against the simple average, over the whole window and over the last 30 calendar days (as the published 30-day averages), the daily spread between the 1st and 99th percentile of transactions, and the latest fixing against the discount curve's 1-month zero rate. | quant: `ZeroCurve`; reads: get |
| **Curve analytics: rates, futures or FX forwards** · `g_curve_analytics.py` | `curve` | Whatever the curve holds: for a rates curve, zero rates, discount factors, 3M forwards and par swap rates with their PV01 per 10m (log-linear on discount factors); for a futures strip, contango or backwardation and the annualised roll yield month by month; for an FX forward curve, outrights and the yield differential the points imply (ln(F/S)/T). | quant: `ZeroCurve`, `annuity`, `par_swap_rate` |
| **Credit limit: utilisation, headroom and the PFE profile** · `h_credit_limit.py` | `credit-limit` | The limit's buckets: used against limit, utilisation against the early-warning line, headroom; and the peak PFE of the counterparty's netting sets in each bucket's tenor range (their exposure profiles, read in full), set against the bucket's limit: how much a new trade could add before a bucket breaches. | quant: `tenor_years`; reads: search, get |

#### 10.19 The BI client

Calc can ask the **BI engine** for a dataset's measures and groups and get a pandas DataFrame. `drishti_bi` is a module of the worker, like `drishti`:

```python
import drishti_bi

drishti_bi.datasets()                                   # the datasets you may query: id, title, tables, measures, description
drishti_bi.describe("file:dataset:trades-basic")         # its fields (table.column, type, masked) and measures
df = await drishti_bi.query_async("file:dataset:trades-basic", measures=["MTM", "Trades"], by=["trade.desk"])
df = await drishti_bi.query_async("file:dataset:trades-basic", measures=["MTM"], by=["trade.desk", "trade.currency"],
                                  filters={"trade.desk": ["DESK-FX", "DESK-RATES"]}, dates=("2026-10-01", "2026-10-09"), limit=500)
show(df, "MTM by desk")
```

| Call | Returns |
|---|---|
| `datasets()` / `datasets_async()` | a DataFrame of the datasets you may open |
| `describe(dataset)` / `describe_async` | a DataFrame: `kind` (`field` or `measure`), `name`, `type` (`python` for a Python measure), `masked`, `description` |
| `query(dataset, measures=None, by=None, filters=None, dates=None, limit=None)` / `query_async` | the engine's answer as a DataFrame, columns named as the engine names them (`trade.desk`, `MTM`) |

`filters` is `{"table.column": [values]}` (an `in` filter for each) or a list of `{field, op, values}` (`eq ne lt le gt ge in not-in between contains starts-with is-null not-null`); `dates` is a date or `(from, to)`, the latest business day when omitted. The plain forms need JavaScript Promise Integration, like `drishti.get`; **write `await drishti_bi.query_async(...)`** to work in every browser (the error says how when it cannot pause).

![Calc: drishti_bi.query in a cell, and the DataFrame it returned, shown as a table](../../tutorials/img/t14/21-calc-bi.jpg)

The picture is real output of that cell on the sample trades dataset: ten desks, their MTM and trade counts (for instance `DESK-COMM` 16,954,390 over 90 trades). `df.attrs` carries what the engine added: `dataset`, `masked` (the masked columns), `asOf` (the business date), `layer` and `millis`.

**As you.** Each call is a message from the worker to the page, which asks the same route a report's visual does (a model query, `POST /bi/api/mq`, the console passing your session to the server): **your row rules apply, and a column masked for you comes back masked** (its name is in `df.attrs["masked"]`; the measures that read it are masked too). You need the BI power; without it the call fails with the server's code (`DRS-9002`) as any read does. The worker still holds no credential and cannot reach the network.

## Tutorial: The Notebook and Python

SQL measures cover most questions. When a figure needs a real function (a standard deviation of P&L per desk, a histogram, a model) you write it in **Python**, and Drishti runs it **sandboxed, on the rows the person looking may see**. And when you do not yet know the question, the **Notebook** gives you
an extract of a model to query with **SQL** on the server, work on in **Python** on the server's compute pool, keep as a **notebook**, and **promote** to a measure, a visual or a report draft (slicing is the **Pivot**'s). This tutorial does both on the demo trades, through the
model `trades-basic@1` (every sample is a model over datasets). Every step shows it **in the UI**, **on the command line** (`python3 tools/drishti.py`, or `curl`) and **as YAML**, or says why one of the three does not apply.

### Time to complete

About 60 minutes (step 12: 15 minutes).

### Before you start

- The quick-start environment of [the reports tutorial](REPORTS.md#before-you-start) (the demo lakes: 15,000 trade valuations over ten desks,
  business date 2026-10-16) and tutorial 10 done (reports, workspaces, `Economics desk`). You sign in as the development administrator, who holds the `bi-author` power that Python needs (SQL cells need only `bi`).
- Python on the server: compute switched on, and the server's compute pool `python` pointed at an interpreter that carries the pinned libraries (`DRISHTI_COMPUTE_ENABLED=true`, `DRISHTI_PYTHON=<python with pandas, numpy, pyarrow and scipy at the versions of config/compute/locks/python.lock>`; [OPERATIONS_GUIDE.md](../OPERATIONS_GUIDE.md)). Every Python item runs on that pool, in a strict sandbox (no network, files held to its own folder), never in the console or your browser; on a Windows server Python is off and says why.
  the browser. **Calc** (the step that asks a BI model from a view, Alt+C) is the one exception: it is Python on the screen you are on, in your browser, so its
  runtime must be installed once on the console with `python tools/drishti.py calc install` ([OPERATIONS_GUIDE.md](../OPERATIONS_GUIDE.md#2-install-and-run-on-one-machine)); without it Calc says
  *Python runtime not installed* and Run stays disabled.
- Two small files in [data/](../../tutorials/data): [notional-by-desk.python.yaml](../../tutorials/data/notional-by-desk.python.yaml) (a Python visual) and [desk-python.report.yaml](../../tutorials/data/desk-python.report.yaml) (a report on the model `trades-basic@1` that uses it).
- The personal API token of [tutorial 1, step 9](../../tutorials/01-first-15-minutes.md#step-9--make-a-token-for-the-command-line) in `DRISHTI_TOKEN` (with `bi:write`),
  and `DRISHTI_SERVER`. The Notebook and Calc calls quoted below are the console's, made with your session: a personal token does not reach them.

### What you will learn

- the two kinds of Python item: a **measure** (`compute(table, params)`, one number per group) and a **visual** (`render(table, params)`, a chart);
- how a report uses them, where they run, and what the sandbox forbids;
- the **Notebook**: the extract of a model, **SQL** (Drishti SQL on the server over the model), a **Python** cell for the server's compute pool, saving a **notebook**, and **promoting** work into BI items; slicing in the **Pivot**.
- **Python across models**: a **Transform** that turns the answers of two model queries into a table (a **Python dataset**, 2.2), and a notebook that joins two extracts in pandas and promotes the join to a Transform;
- **Vega-Lite** charts (written in a report, or returned by Python), the **tests** a Python item carries, where a SQL cell runs and promoting into an **existing** report, **Calc's BI client** (a Labs scratch pad; steps 9 to 11).
- **Data prep**: a Transform built step by step in the **Transform editor** from raw trades read straight through a connector, with a preview
  and a profile after every step and no YAML to type (step 12).

### Step 1 · Python in a report: the shipped sample

Open **Catalogue → Desk P&L risk (Python)**.

![The sample Python report: P&L volatility per desk as bars, and a histogram of one-day P&L; each frame says Python, the item, v1, server, the time and the rows](../../tutorials/img/t14/01-python-report.jpg)

You should see two charts. Under each is a line such as `Python · desk-pnl-volatility v1 · server · 278 ms · 10000 rows`: the **item** that drew it, its version, **where it ran** (the server's compute pool, `server`) and how many rows it was handed. The left chart is a **Python measure** (the volatility of one-day P&L of each desk); the right one is a **Python visual** (a histogram drawn with numpy).
Both worked on the trades **as you may see them**: masks and row rules were applied before Python saw a row. A Python visual is handed at most 10,000
rows, so on the demo lake's 15,000 trade valuations it works on the first 10,000 (the server says so: `DRS-9022` is what a plain JSON answer of more rows
is told); a Python measure for a figure over every row belongs in a model query, or in a Transform over grouped answers (step 8).

The measure's source is a short YAML item, `config/bi/python/banking/desk-pnl-volatility.python.yaml`:

```yaml
python: desk-pnl-volatility
title: Desk P&L volatility
interface: measure
inputs: [PNL_1D]                 # the columns the function reads, as the table names them
packages: [pandas]               # what the source imports (must be on the allow-list)
target: server                   # the console runs it (there is no browser target)
timeout: 10                      # seconds
memory: 256                      # megabytes
params: { min_trades: 2 }
source: |
  def compute(table, params):
      """One number for the rows of one group."""
      pnl = table["PNL_1D"].dropna()
      if len(pnl) < int(params.get("min_trades", 2)):
          return 0.0
      return float(pnl.std(ddof=1))
```

The **model** (`config/tables/banking/trades-basic.model.yaml`) says which groups it is called for (one number per desk) in a measure that is a Python item,
not an expression:

```yaml
measures:
  Desk P&L volatility:
    python: desk-pnl-volatility      # the item
    inputs: [trade.PNL_1D]           # columns of ONE dataset of the model
    by: [trade.DESK]                 # one number per desk
```

**On the command line** the model lists it with its item, beside the measures that are expressions:

```text
$ python3 tools/drishti.py bi model describe trades-basic@1
Trades, the basics  (trades-basic@1)

trade  fact  trades-basic-trade 1.0.0
  trade.VALUATION_ID                 string    key
  trade.TRADE_ID                     string    dimension
  trade.SOURCE_SYSTEM                string    dimension
  trade.DESK                         string    dimension
  trade.PORTFOLIO                    string    dimension
  trade.PRODUCT_TYPE                 string    dimension
  trade.CURRENCY                     string    dimension
  trade.TRADE_STATUS                 string    dimension
  trade.NOTIONAL                     decimal   amount        amount0
  trade.MTM                          decimal   amount        signed0
  trade.PNL_1D                       decimal   amount        signed0
  trade.TRADE_DATE                   date      date
  trade.MTM_IN_MILLIONS              decimal   amount

measures
  Notional                          sum(trade.NOTIONAL)
  MTM                               sum(trade.MTM)
  Trades                            count(trade)
  Average MTM per trade             measure('MTM') / measure('Trades')
  Desk P&L volatility               python: desk-pnl-volatility
  Count of trade                    count(trade)  (default)
```

A model query computes it like any measure, on the server's rows (every row, not the visual's 10,000), one call of the item per group:

```text
$ python3 tools/drishti.py bi mq --model trades-basic@1 --select trade.DESK --measures "Desk P&L volatility" --limit 3
trade.DESK   Desk P&L volatility
-----------  -------------------
DESK-COMM    217,782.36
DESK-CREDIT  159,732.60
DESK-EQD     183,546.29
3 row(s)  as of 2026-10-16  plan p_8978bc854cd42c41
```

The report's python visual draws it from the rows it is handed.

### Step 2 · A Python visual of your own

A visual is a function `render(table, params)` that gets the rows of the visual's `columns` well and returns a chart **as data** (an ECharts option, SVG text or PNG bytes; never HTML or script). Here is the tutorial's, which draws the largest desks by notional:

```yaml
rachana: 1
python: notional-by-desk
title: Notional by desk chart
interface: visual
output: echarts
inputs: [DESK, NOTIONAL]
packages: [pandas]
target: server
params: { top: 8 }
source: |
  def render(table, params):
      top = int(params.get("top", 8))
      g = table.groupby("DESK", as_index=False)["NOTIONAL"].sum().sort_values("NOTIONAL", ascending=False).head(top)
      return {
          "title": {"text": f"Notional of the {len(g)} largest desks", "left": "center"},
          "xAxis": {"type": "value", "name": "Notional"},
          "yAxis": {"type": "category", "data": list(g["DESK"])[::-1]},
          "series": [{"type": "bar", "data": [float(v) for v in g["NOTIONAL"]][::-1]}],
      }
```

A Python item is a BI item like a dataset or a report: stored, versioned, reviewed and published. Create it in your workspace. **In the UI** that is a form, and you do not start from a blank page: **My workspace → New item**, kind *python*, and **What does it make?** asks for *a measure* (one number per group), *a visual* (a chart drawn by Python) or *a table* (a Transform, which joins datasets), and for the columns it reads (or, for a table, the datasets it reads). **Create and open the editor** makes the item with a valid, commented starter body (the manifest the server checks, a function of the right shape, and for a measure a test) and opens it with its editor ready, so you change the code and press **Check the Python**.

![New item, kind python: it makes a measure, reads the column NOTIONAL, titled Mean notional](../../tutorials/img/t14/23-python-starter.jpg)

![The starter body in the item's editor: params, a test of the mean of three values, and def compute(table, params)](../../tutorials/img/t14/24-python-starter-editor.jpg)

For this step choose *a visual* and replace the body with the tutorial's, or, **from the file**, paste the YAML from `data/notional-by-desk.python.yaml` into the same form's item page (**Edit**), the way it was done before the starter existed: kind *python*, title `Notional by desk chart`, **Create item**. (The picture below moved it into `Economics desk`.)

![The Python item's page: draft, with Check the Python, Edit, Propose for review, Publish](../../tutorials/img/t14/02-python-item.jpg)

You should see the item as a draft with its actions. Press **Check the Python**: the console checks what only it can, that the source parses, defines its entry (`render`) and imports only what the manifest lists or the safe standard library.

![The check passes: "The Python item checks out: the manifest is valid and the source parses."](../../tutorials/img/t14/03-check-python.jpg)

You should see `The Python item checks out: the manifest is valid and the source parses.` A mistake is reported with its code. A package that is not allowed, for example `packages: [requests]`, is refused (`DRS-9070`) with the allow-list:

```text
$ POST /bi/python/check   (packages: [requests])
{"valid": false, "problems": [
  {"level": "error", "code": "DRS-9070", "path": "packages", "message": "'requests' is not an allowed package (allowed: pandas, numpy, pyarrow, scipy)"},
  {"level": "error", "code": "DRS-9070", "path": "packages", "message": "'requests' is not on the pool's pinned allow-list (pandas, numpy, pyarrow, scipy)"}, ...
```

Press **Publish** (a Python item must be published for a report to use it). **From the command line** the same two moves (`bi item new` reads the
kind from the file name; the ids differ on your machine):

```text
$ python3 tools/drishti.py bi item new economics-desk docs/tutorials/data/notional-by-desk.python.yaml
bi-9f1278b922  python  Notional by desk chart  draft in Economics desk
$ python3 tools/drishti.py bi catalogue publish bi-9f1278b922 --note "my first Python visual"
published Notional by desk chart as version 1
```

Now the report, [data/desk-python.report.yaml](../../tutorials/data/desk-python.report.yaml): it reads the model (`model: trades-basic@1`), a slicer, a `python` visual
that names your item, and one that uses the shipped measure.

```yaml
- id: notional-chart
  type: python
  title: "Notional by desk (my Python visual)"
  at: { x: 0, y: 2, w: 6, h: 6 }
  wells: { columns: [trade.DESK, trade.NOTIONAL] }       # the rows handed to Python
  format: { python: Notional by desk chart, params: { top: 8 }, run: auto }
- id: volatility
  type: python
  wells: { values: [Desk P&L volatility] }                # a Python measure of the model
  format: { run: auto }
```

Create it (New item, kind *report*, or `python3 tools/drishti.py bi item new economics-desk docs/tutorials/data/desk-python.report.yaml`) and press **Open the report**.

![Desks in Python: a currency slicer, my Python bar chart of the eight largest desks, and the shipped volatility measure](../../tutorials/img/t14/04-desks-in-python.jpg)

You should see your chart, `Notional of the 8 largest desks`, drawn by your function, beside the shipped one; the footer of yours reads `Python · bi-... v1 · server · 320 ms · 10000 rows`. Choose a currency in the slicer: the chart is asked again and Python gets only those rows (the rows are the table's own, under the page's slicers, filters and business date, at most 10,000). `format.run` is `auto` (the server first, the browser if the server cannot), `server` or `browser`.

**The sandbox.** The server target runs a short-lived worker process per call: **no network**, a scratch folder only, limits on CPU, memory, time and output, and **nothing kept between calls**, so nothing leaks between people. `import` works for the allow-listed packages. Python never reaches the server's data directly; it receives the Arrow rows the server built for the person.

### Step 3 · The Notebook: an extract to work on

Choose **Notebook** in the BI rail (`/bi/notebook`). In **Model or dataset** choose **Trades, the basics (model 1.0.0, published)** (the list holds the
models first, then your datasets and the packs' datasets, one group per pack), tick the fields you want and press **Open the extract**.

![The Extract card with the model Trades, the basics and its fields ticked, and the Open the extract button](../../tutorials/img/t14/05-analyse-start.jpg)

You should see the fields grouped by the model's alias (`trade`) and, after **Open the extract**, the line `10000 rows, 10 columns.` with a note that the
extract was cut (the lake holds 15,000). The extract is **one model query** (its rows, `shape: rows`) asked as **you** (masked columns arrive empty, and
the line says `Masked for you: ...`), cut to `bi.notebook.max_rows` (10,000; a cut says so). It is rebuilt on every run and never kept, so a mask or row
rule changed on the server applies the next time. **On the command line** the same rows are a model query, and **the YAML** of it is what the Notebook sends:

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.TRADE_ID, trade.DESK, trade.PORTFOLIO, trade.CURRENCY, trade.NOTIONAL, trade.MTM], measures: [] }
shape: rows                 # the rows themselves, not grouped
limit: 10000
```

(`python3 tools/drishti.py bi mq that.yaml` prints them.)

### Step 4 · Slicing is the Pivot's

To put desks down, currencies across and notional in the cells, use the **Pivot**, the one grid for slicing any table: the *Pivot* link in the BI rail,
or the Notebook's own line *To slice and total a table, use the Pivot*. Its totals are the whole table's, worked out by the server
([BI_GUIDE.md](README.md)); **on the command line** `PIVOT <table> <GO>` opens it on a table, and a pivot view is saved as YAML there.
The Notebook is for what a grid cannot say: SQL over the model and Python.

You should see the Pivot open with its source picker: choose the trades' table, then drag `DESK` to Rows, `CURRENCY` to Columns and `NOTIONAL` to Values.

### Step 5 · SQL

Open the **SQL** tab (it opens first). A SQL cell is **one read-only SELECT** in **Drishti SQL over the model**, asked of the **server** as you, so its
answers are true totals, not the extract's. Its tables are the model's aliases and a measure is `MEASURE('name')`
([API_REFERENCE.md › Drishti SQL](../API_REFERENCE.md#8-drishti-sql-apiv1bisql)):

```sql
SELECT trade.DESK, MEASURE('Trades') AS trades, MEASURE('Notional') AS notional
FROM trade
GROUP BY trade.DESK
ORDER BY notional DESC
```

Press **Run**.

![The SQL cell in Drishti SQL on the server: desks with trade counts and notional, largest first, and the line naming the model, the plan and as you](../../tutorials/img/t14/07-sql.jpg)

You should see a table, the largest desk first (`DESK-COMM`, `5,990` trades, `888,803,742,774` of notional: true totals over all 15,000 rows, not the
extract's), and the line *10 rows in 7 ms · Drishti SQL, run by the server over trades-basic@1 as you · plan p_...*: the same plan a visual of the same
question would run. **From the command line** the same statement (and what a write is told):

```text
$ python3 tools/drishti.py bi sql "SELECT trade.DESK, MEASURE('Trades') AS trades, MEASURE('Notional') AS notional FROM trade GROUP BY trade.DESK ORDER BY notional DESC LIMIT 3" --model trades-basic@1
trade.DESK  trades  notional
----------  ------  ------------------
DESK-COMM   5990    888,803,742,773.65
DESK-RATES  1832    596,934,185,000.00
DESK-EQD    1452    428,542,341,000.00
3 row(s)  shape mq  plan p_3773eb23294dfb01

$ python3 tools/drishti.py bi sql "DROP TABLE trade" --model trades-basic@1
drishti: the server said 422 DRS-9170: DRS-9170 Drishti SQL is read-only: DROP is refused; a statement is one SELECT (or WITH ... SELECT)
```

(Text over 20,000 characters is `DRS-9072`; at most 5,000 rows come back.) There is no YAML for a cell on its own: it is kept in a notebook (step 7).

### Step 6 · A Python cell

Open the **Python** tab. A cell sees `df` (the extract as a pandas table), `extract("name")` (another extract of the notebook) and `params`. What it prints
comes back as text; what it puts in `result` is **drawn**: a table as a table, a dict as an ECharts option, SVG or PNG as an image.

```python
g = df.groupby('DESK', as_index=False)['NOTIONAL'].sum()
result = {'title': {'text': 'Notional by desk'}, 'xAxis': {'type': 'category', 'data': list(g['DESK'])}, 'yAxis': {'type': 'value'},
          'series': [{'type': 'bar', 'data': [float(v) for v in g['NOTIONAL']]}]}
```

The line beside **Run** says *Runs on the server's compute pool*: a cell never runs in the console or your browser. Press **Run**: the server reads the
extract as you and runs the cell in its strict sandbox. You should see the line *Ran on the server's compute pool in ... ms over 10000 rows* and the bar
chart of notional by desk under it (what the cell printed comes first, when it prints). Keep the cell in the notebook (step 7) and promote it to an item
(below): whoever opens the notebook later runs it on the pool, over their own rows. Running Python needs `bi-author`; a person with only `bi` writes and runs SQL cells.

### Step 7 · Keep it as a notebook, promote it

Under **Save** give it a title (`Desk notional, first look`) and press **Save as a notebook**. (**My workspace → New item → notebook** only points you here:
a notebook is made from the work you did, not from a form.)

![The notebook card after saving: Save as a notebook and Open the item](../../tutorials/img/t14/09-saved.jpg)

You should see a link, **Open the item**: the extract's definition and every cell are now a BI item of kind **notebook** in your workspace, with versions,
diffs, review and publishing like any other. Opening it again (**Open in the Notebook** on the item page) rebuilds the extract **for whoever opens it**: a
notebook shared with a colleague shows *their* rows. Outputs are not stored, only the code. **The YAML** of the notebook:

```yaml
rachana: 1
notebook: desk-notional-first-look
title: Desk notional, first look
dataset: mq:trades-basic@1            # the model the extract reads
fields: [trade.TRADE_ID, trade.DESK, trade.PORTFOLIO, trade.PRODUCT_TYPE, trade.CURRENCY, trade.TRADE_STATUS, trade.NOTIONAL, trade.MTM, trade.PNL_1D, trade.MTM_IN_MILLIONS]
cells:
- type: sql
  source: |
    SELECT trade.DESK, MEASURE('Trades') AS trades, MEASURE('Notional') AS notional
    FROM trade
    GROUP BY trade.DESK
    ORDER BY notional DESC
- type: python
  source: |
    g = df.groupby('DESK', as_index=False)['NOTIONAL'].sum()
    result = {'title': {'text': 'Notional by desk'}, ...}
  target: server
```

To turn a Python cell into a reusable item press **Promote to a visual** (or **to a measure**) under the cell.

![After promoting: "Python visual draft saved in your workspace: Notebook cell (visual). The cell's df became the function's table..."](../../tutorials/img/t14/10-promote-visual.jpg)

You should see `Python visual draft saved in your workspace: Notebook cell (visual).` and the reminder: the cell's `df` became the function's `table`,
`result` is returned, a measure must return one number and a visual an ECharts option, SVG or PNG; check it with **Check the Python** on the item page,
then publish. Nothing is published for you. A SQL cell becomes a **report visual** the same way (step 10).

### Step 8 · Two models: a Python Transform and a join in the Notebook

Steps 1 to 7 used Python on one model. Python can also **join answers**. The sample **Desk P&L vs counterparty exposure (Python)** makes one table, P&L
next to exposure per counterparty, with a **Python Transform**: a Python item with `interface: transform` whose function `run(pnl, exposure, params)`
receives two answers as pandas frames, **asked as you**, and returns a table. That table is a **Python dataset** (`source.python`), and each
input is a **model query**. Open **Models**, find **Desk P&L vs counterparty exposure (Python): the Transform's table** under the datasets.

![The Python dataset's page: made by the Python Transform pnl-vs-exposure, keyed by counterparty, snapshot, and the nine columns its Transform returns](../../tutorials/img/t14/12-python-dataset.jpg)

You should see *Source: the Python Transform pnl-vs-exposure*, the key `counterparty` and the nine columns the Transform returns (a contract: a column it
does not return is `DRS-9075`). There is nothing to refresh: the Transform runs **when the dataset is asked**, for the person asking, and its frame is kept
for them until an input changes. **The YAML** says what it reads (`config/tables/bi/desk_pnl_vs_exposure_py_result.table.yaml`):

```yaml
rachana: 2
expressions: 2
dataset: desk-pnl-vs-exposure-py-result
version: 1.0.0
source:
  python: pnl-vs-exposure
  inputs:                       # each a model query, asked as the reader (their row rules and masks)
    pnl: { model: trading-risk@1, select: { fields: [counterparties.id, counterparties.NAME], measures: [P&L 1d, Trades] } }
    exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id, counterparties.SECTOR], measures: [Exposure (EE peak), Exposure (PFE peak)] } }
  params: { floor: 1.0 }
grain: { keys: [counterparty] }
columns:
  counterparty: { type: string }
  ...
```

and the model `desk-pnl-vs-exposure-py@1` reads it as its one fact, with measures over it. **On the command line** ask it like any model (the
Transform runs for you on the first question):

```text
$ python3 tools/drishti.py bi mq --model desk-pnl-vs-exposure-py@1 --select result.counterparty,result.name,result.sector --measures "Desk P&L,Exposure (EE peak),P&L per exposure" --limit 3
result.counterparty  result.name                   result.sector     Desk P&L       Exposure (EE peak)  P&L per exposure
-------------------  ----------------------------  ----------------  -------------  ------------------  ----------------
CP-ALDERSHOT         Aldershot Pension Trust       Pensions          9,940,482.84   276,416,536.00      0.04
CP-AURORA            Aurora Sovereign Wealth Fund  Government        -5,475,341.95  213,663,642.00      -0.03
CP-BRAMBLE           Bramble Asset Management      Asset management  6,448,473.64   146,933,503.00      0.04
3 row(s)  as of 2026-10-26
```

The item is `config/bi/python/banking/pnl-vs-exposure.python.yaml` (the model queries hand their columns over as the models name them, `NAME` and `SECTOR`;
the Transform renames them to the table's own `name` and `sector`); the reference is [BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 13](#4-transforms-a-table-in-a-table-out).

How would you write such a Transform? In the **Notebook**, with **two extracts**. Open the notebook **Desk P&L vs counterparty exposure, in pandas** (the Notebook box in the Extract card).

![The Notebook with the sample notebook: the first extract is the trades of the model trading-risk (3 columns); under More extracts, the second is called exposure and reads the same model's computed netting-set exposure](../../tutorials/img/t14/13-analyse-extracts.jpg)

You should see `10000 rows, 3 columns.` for the first extract (cut: the lake holds 15,000 trade valuations) (the trades of the model **Trading and counterparty risk**) and, in **More extracts: join
several models**, a second one called `exposure` (`mq:trading-risk@1`, 3 fields: the netting sets' computed exposure, `set_exposure`, of the same
model: the exposure engine's official end-of-day results). Each is built for you by the
server, with your row rules and masks. Open the **Python** tab: the join cell is there (`df` is the trades; `extract("exposure")` is the
netting sets' computed exposure, `PEAK_EE`); **Run** runs it on the server's compute pool (step 6). On the server, one model
already joins its facts through the counterparty (Drishti SQL `FROM trades JOIN counterparties JOIN set_exposure`). When the pandas join is what you want to keep, press **Promote to a Transform**.

![After promoting: "Python transform draft saved in your workspace: Join of P&L and exposure (transform). Each extract("name") became a parameter of run()..."](../../tutorials/img/t14/15-promote-transform.jpg)

You should see, beside the button, that a Python Transform draft was saved in your workspace: each `extract("name")` became a parameter of `run(...)`, the
cell's `result` is returned, and the next step is *a dataset of its table: source: { python: <this item>, inputs: { <name>: <a model query> } }, one
input per parameter of run(), in this order*. Open the draft, **Check the Python**, publish it, and make that dataset. Nothing is published by promoting.

**In the UI** the Python dataset is the dataset wizard's source **A Python Transform** ([BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 15](TABLES_AND_MODELS.md#110-a-table-of-your-own-step-by-step)):
**Models → New dataset**, choose the Transform by its title, give each input of `run()` its model query (a model, the fields and the measures to hand
over), and list the columns the Transform returns with their types; **Preview** runs it for you before you save. **From the YAML** it is the
`source: { python: ..., inputs: { ... } }` above; **Make it a model** on its page puts a model over it, where its measures go.

### Step 9 · Vega-Lite, and tests that ship with the item

A chart does not have to be an ECharts option. A **Vega-Lite** specification can be written in the report itself (a `vega-lite` visual: its data is the rows of its wells) or returned by a Python visual (`output: vega-lite`). Open the sample report **Desk P&L risk (Python)**, its **Vega-Lite** tab.

![A vega-lite visual: net MTM by desk, red where it is negative. The specification is in the report's YAML; the data is the visual's own rows](../../tutorials/img/t14/16-vega-lite.jpg)

You should see bars for the desks, red for the negative ones: the colour is the specification's `condition: { test: "datum.MTM < 0" }`, evaluated **without `eval`**, so the page keeps its strict content security policy. drishti-chart draws it: the specification is translated into a drishti-chart spec, so the chart has the same card, tooltip, data table (▦) and Explain as every other chart. A feature drishti-chart does not translate (a facet, a `text` mark, a selection) is not drawn: the frame says **Not drawn** and names each feature with what to use instead. In your own Python visuals prefer `output: drishti-chart`: return a drishti-chart spec (`{"kind": "bar", "data": {"rows": [...]}, "x": ..., "y": ...}`), drawn as it is. On the first tab, the bottom visual is a Python visual that returns a specification: a dot for each trade's one-day P&L by desk, a tick for each median.

![A Python visual that returns Vega-Lite: dots of one-day P&L for each desk and each desk's median as a tick](../../tutorials/img/t14/17-python-vega.jpg)

A Python item can also carry **tests**. Open the item **Desk P&L volatility** (the Python measure), and press **Check the Python**.

![Check on the Python measure: the manifest and source checks, then its three tests, each run in the sandbox](../../tutorials/img/t14/18-python-tests.jpg)

You should see `Tests: 3 of 3 passed.` and a line for each (`passed · the standard deviation of one desk · 1.2909944487358056`). The sample is shipped (read-only); in a copy of it in your own workspace, change a number in an `expect:` and check again: that test says `FAILED` with what it expected and got, and **publishing** through the console is refused (`DRS-9076`) until it passes. [BI_GUIDE.md](#3-python-measures-and-visuals) sections 14 and 15 have the rules.

### Step 10 · Where a SQL cell runs, and promote into a report you already have

A SQL cell is **Drishti SQL over the model**, run by the server as you (the answer is the model's, with true totals, and the cell is the same plan a
report visual runs); the line beside **Run** says so. Nothing runs in the browser or the console. Above the tabs, **Promote a SQL cell to** a *new report
draft* or to one of your report drafts. Choose one, write a SQL cell and **Run** it:

![A SQL cell on the server engine: MTM by desk for the USD trades, the engine select, and the line naming the model and the plan](../../tutorials/img/t14/19-analyse-engines.jpg)

You should see ten desks (`DESK-RATES` `2,863,034,263` first, for the USD trades) and the line naming
`trades-basic@1`. **From the command line**:

```text
$ python3 tools/drishti.py bi sql "SELECT trade.DESK, MEASURE('MTM') AS mtm FROM trade WHERE trade.CURRENCY = 'USD' GROUP BY trade.DESK ORDER BY mtm DESC LIMIT 3" --model trades-basic@1
trade.DESK   mtm
-----------  ----------------
DESK-RATES   2,863,034,262.92
DESK-STRUCT  409,340,516.24
DESK-SFT     264,809,126.60
3 row(s)  shape mq  plan p_3fefc5a51db918b6
``` Press **Promote to a report visual**:

![A SQL cell promoted into the report draft "Desk views": the note says what a report cannot say (the ORDER BY) and that the report was written again](../../tutorials/img/t14/20-promote-existing.jpg)

You should see, beside the button, `Visual sql added to the report draft: Desk views.` and notes: the `ORDER BY` was left out (a visual sorts in its own
settings) and the report was written again by the designer's writer. Open the draft in the designer: a new `matrix` (rows `trade.DESK`, values `MTM`, the
filter `trade.CURRENCY eq USD`) below what was there. The reader understands Drishti SQL (aliases and `MEASURE('...')`) and plain aggregates of columns, matched to the model's measures. **The YAML** of the visual it added:

```yaml
      - id: sql
        type: matrix
        title: Desk views
        at: { x: 0, y: 8, w: 12, h: 6 }
        wells: { rows: [trade.DESK], values: [MTM] }
        filters: [{ field: trade.CURRENCY, op: eq, values: [USD] }]
```

A SQL cell of **another** model is not added to an existing report (a report reads one model: `DRS-9070` names both); promote it to a new draft.
[USER_GUIDE.md › The Notebook](../USER_GUIDE.md#the-notebook) section 6 has the details.

### Step 11 · Calc: the BI engine as a DataFrame; a Python measure in an alert

In **Calc** (`Alt+C` on any view) the module `drishti_bi` asks the BI engine, **as you**: `query` sends one model query (a model's `name@major`; a 2.1
dataset's name reads the model the converter made of it, `trades-basic` is `trades-basic@1`):

```python
import drishti_bi
df = await drishti_bi.query_async('trades-basic@1', measures=['MTM', 'Trades'], by=['trade.DESK'])
show(df, 'MTM by desk')
```

![Calc: drishti_bi.query on the model trades-basic@1 in the editor, and the DataFrame it returned, shown as a table](../../tutorials/img/t14/21-calc-bi.jpg)

You should see ten desks with their MTM and trade counts: the same numbers a report shows, with the same masks. The command line prints the same:

```text
$ python3 tools/drishti.py bi mq --model trades-basic@1 --select trade.DESK --measures MTM,Trades --limit 3
trade.DESK   MTM              Trades
-----------  ---------------  ------
DESK-COMM    -429,967,635.39  5990
DESK-CREDIT  360,552,941.89   1172
DESK-EQD     -79,395,510.75   1452
3 row(s)  as of 2026-10-16  plan p_5d2cad2dda86953b
```

Calc is a **Labs** feature (off as shipped: an administrator switches `labs.calc` on), a scratch pad in your browser that never publishes.

Last, a **Python measure in an alert**. An alert on a measure of a model asks a model query, and a model query runs Python measures (step 1), so
**Alerts → New → Watch a measure of a model** on **Desk P&L volatility** works like any other: choose the model `trades-basic@1`, the measure, *crosses a
limit*, *above* `100000`, **Save**. What the form sends, with your session (a personal API token may not call `/me/...`), and the alert at once firing
(the largest desk volatility, `1,117,385`, is above the limit), captured:

```text
PUT /api/v1/alerts/rules/Desk%20volatility%20ceiling
    {"source":{"type":"model","model":"trades-basic@1","query":{"select":{"fields":["trade.DESK"],"measures":["Desk P&L volatility"]}},"reduce":"max"},
     "condition":{"type":"threshold","op":"above","value":100000},"trigger":{"mode":"after-load"}}
200 {"name":"Desk volatility ceiling", ..., "source":{"type":"model","model":"trades-basic@1","measure":"Desk P&L volatility","reduce":"max", ...},
     "describe":"above 100,000","label":"trades-basic@1 · Desk P&L volatility","link":"/bi/i/trades-basic%401"}
POST /api/v1/alerts/rules/Desk%20volatility%20ceiling/check
200 {... "history":[{"action":"fired","trigger":"saved","value":1117385.1394432886,"asOf":"2026-10-16",
     "message":"Desk volatility ceiling — now 1,117,385.1394 (above 100,000)","delivered":{"inbox":"ok","email":"off","bridge":"off","sms":"off"}}]}
```

`reduce: max` watches the largest of the desks' figures; `first` or a filter on one desk watches one.

<a id="step-12--prepare-raw-trades-step-by-step-the-transform-editor-240"></a>

### Step 12 · Prepare raw trades step by step: the Transform editor

Step 8 made a Transform from two **models**. Before a model exists you often want to clean the **raw rows** first: drop matured trades, fix a type,
bucket a tenor, total. The **Transform editor** does that step by step, with the rows and a profile after every step, and you never type YAML.

**In the UI.** Open **BI → Transforms** (the rail). Type the title `ENDUR trades, cleaned`. Under **1. Inputs**, name the input `trades`, keep *A pack
kind, straight through its connector*, choose the kind **Trade (trading.trade)**, type the connector `trade-endur` (the ENDUR trades of the demo lake),
tick `TRADE_ID`, `TRADE_STATUS`, `PORTFOLIO`, `CURRENCY`, `NOTIONAL`, `TRADE_DATE` and `MATURITY_DATE`, and press **Add the input**.

![The input: the kind trade read through the connector trade-endur, 1,000 rows of 2026-10-16 (the preview's first thousand)](../../tutorials/img/t14/25-transform-input.jpg)

You should see the line `trades the kind trade (pack trading) through trade-endur · 7 fields 1,000 rows (the first 1,000) · as of 2026-10-16` and a first
step, **Start from trades**, with `1,000 rows · 7 cols`. The editor previews the first `bi.transform.preview_rows` rows (1,000) **as you**: your row
rules and masks apply, as when the dataset is read.

Now **Add a step from the snippets**: choose **Filter rows by values**, the column `TRADE_STATUS`, the value `MATURED`, untick *Keep them* (drop them), and
**Add the step**. Step 2 says `510 rows · 7 cols`. Press **Profile**:

![Step 2's profile: one card per column; TRADE_DATE and MATURITY_DATE are text that looks like dates, with Fix the type; TRADE_ID could be a key](../../tutorials/img/t14/26-transform-profile.jpg)

You should see `TRADE_STATUS` with four values left (`VALIDATED ×336`, `AMENDED ×64`, `NEW ×62`, `CANCELLED ×48`), `TRADE_ID` marked **key?** (every row
its own value), and *Text that looks like dates* under `TRADE_DATE` and `MATURITY_DATE`. Press **Fix the type** under `TRADE_DATE`: a step
**Type fix: text to date (TRADE_DATE)** is added. Then two more snippets: **Tenor bucket** (maturity `MATURITY_DATE`, from `TRADE_DATE`) and **Group and
total** (group by `PORTFOLIO` and `tenor_bucket`, the column `NOTIONAL`, `sum`). Press **Rows**:

![Five steps, each with its rows; the last, Group and total, nine rows: the notional of each book per tenor bucket](../../tutorials/img/t14/27-transform-steps.jpg)

You should see `Ran 5 steps on trades 1,000 rows` and nine rows, `BOOK-COMM-1 1-2Y 90,937,298.74` first. Pick any step to see its own rows again; ✎ edits
a step, ↑ ↓ move it, × removes it. Under **4. Save**, tick `PORTFOLIO` and `tenor_bucket` (*Each row is one*) and **Save as drafts**: you should see
*Saved as two drafts in your workspace: the Python item and the dataset*. Check and publish the Python item, then the dataset ([BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python,
section 13](#4-transforms-a-table-in-a-table-out)).

**The YAML** was written for you; open *YAML: what is saved* to read it (and edit it, then **Read the YAML back**, if you like). The Python item
([data/endur-trades-cleaned.python.yaml](../../tutorials/data/endur-trades-cleaned.python.yaml)) holds the steps after their `# step:` comments:

```python
def run(trades, params):
    # step: Start from trades
    df = trades.copy()
    # step: Filter rows by values (TRADE_STATUS)
    df = df[df['TRADE_STATUS'].isin(['MATURED']) == False].reset_index(drop=True)
    # step: Type fix: text to date (TRADE_DATE)
    df['TRADE_DATE'] = pd.to_datetime(df['TRADE_DATE'], format='%Y-%m-%d', errors="coerce").dt.date
    # step: Tenor bucket (MATURITY_DATE; TRADE_DATE)
    ...
    # step: Group and total (PORTFOLIO, tenor_bucket; NOTIONAL)
    df = df.groupby(['PORTFOLIO', 'tenor_bucket'], as_index=False, dropna=False)[['NOTIONAL']].agg('sum')
    return df
```

and the Python dataset ([data/endur-trades-cleaned.dataset.yaml](../../tutorials/data/endur-trades-cleaned.dataset.yaml)) reads the kind straight through the connector:

```yaml
source:
  python: endur-trades-cleaned
  inputs:
    trades:
      kind: trade
      pack: trading
      fields: [CURRENCY, MATURITY_DATE, NOTIONAL, PORTFOLIO, TRADE_DATE, TRADE_ID, TRADE_STATUS]
      connector: trade-endur
grain:
  keys: [PORTFOLIO, tenor_bucket]
```

**On the command line** the input is asked as you and profiled, the snippets are listed and written, and the drafts are saved from the files:

```text
$ python3 tools/drishti.py bi transform input docs/tutorials/data/endur-trades-cleaned.dataset.yaml --rows 2
trades: 1,000 rows of dataset:pack.trading.trade as of 2026-10-16
  CURRENCY                     text      empty     0  distinct      3  CAD .. USD
  MATURITY_DATE                text      empty     0  distinct     81  2024-11-01 .. 2029-10-31
  NOTIONAL                     float64   empty     0  distinct    971  2,855.8 .. 2.46923e+08
  PORTFOLIO                    text      empty     0  distinct      3  BOOK-COMM-1 .. BOOK-COMM-3
  TRADE_DATE                   text      empty     0  distinct    529  2024-10-10 .. 2026-09-25
  TRADE_ID                     text      empty     0  distinct   1000  1000005 .. 1005000
  TRADE_STATUS                 text      empty     0  distinct      5  AMENDED .. VALIDATED
    CAD | 2025-04-30 | 1000000.0 | BOOK-COMM-3 | 2025-03-01 | 1002153 | MATURED
    CAD | 2026-04-30 | 20000000.0 | BOOK-COMM-1 | 2025-02-06 | 1003026 | MATURED
$ python3 tools/drishti.py bi transform snippet filter-values column=TRADE_STATUS values=MATURED keep=false
# step: Filter rows by values (TRADE_STATUS)
df = df[df['TRADE_STATUS'].isin(['MATURED']) == False].reset_index(drop=True)
$ python3 tools/drishti.py bi item new me docs/tutorials/data/endur-trades-cleaned.python.yaml
bi-90b5ada37c  python  ENDUR trades, cleaned  draft in My workspace
$ python3 tools/drishti.py bi item new me docs/tutorials/data/endur-trades-cleaned.dataset.yaml
bi-04b126d3c4  dataset  ENDUR trades, cleaned  draft in My workspace
```

The steps run where every Python item runs, on the server's compute pool; the snippets write each value as a literal, never as code. The reference is
[BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17](#5-data-prep-the-transform-editor).

### Next steps

- [Tutorial 10](../../tutorials/10-ai-assistant.md): ask the data in words.
- [BI_GUIDE.md](#3-python-measures-and-visuals): the manifest, where Python runs, the sandbox in full, `DRS-9070` to `9075`, section 13 for the Transform and
  section 17 for the Transform editor and the snippets. [USER_GUIDE.md](../USER_GUIDE.md#the-notebook): the page, limits and notebooks. [USER_GUIDE.md](#10-calc-python-in-your-own-browser) is Calc, the Python panel inside a screen.

### If something goes wrong

| You see | Meaning |
|---|---|
| `DRS-9001 no item 'xyz'` in a Python visual | the item is not published, or the name does not match its id, file name or title |
| `DRS-9070` | the manifest or a cell is wrong; the message names the key (a package not on the allow-list, a second SQL statement, a source that does not parse) |
| `DRS-9072` | the SQL was too long (`bi.notebook.sql_max_chars`) |
| `DRS-9170` | Drishti SQL on the server was asked to do more than one SELECT (a write, a second statement) |
| `DRS-9074` | Python does not run on this server now: the message says why (compute off, the pool's interpreter not carrying the pinned versions, a Windows server) |
| a Python cell's Run is greyed and says *Off: ...* | the server cannot run Python safely now; the words say why (compute off, a Windows server, a pin that differs) |
| a column is empty in the extract | it is masked for your role; the line under the form names it |
| the Transform editor's steps do not run | running them needs `bi-author`; the page still writes the YAML |
| `DRS-9108 the kind 'trade' has no column [X]` | a field the kind does not have; the message lists the kind's columns |
| `DRS-9026 connector 'x' is not running` | the input names a connector that is off; leave it empty to read whichever serves the kind |

## Compute demo pack

Sample pack for reactive connectors: three books of synthetic P&L strips (250 scenario days each) and the sum of a book's paths computed when it is asked for, by a Python engine in a compute pool (path-sum) and by its Java twin in the server (path-sum-java), with progress on the screen and a state per panel. Synthetic numbers only. Not loaded unless named in DRISHTI_PACKS.

**Code:** `CMPD` · **Kinds:** 4 · enable it with `DRISHTI_PACKS=compute-demo` (a sample pack; `drishti.packs.samples` hides it from business users).

### Using it

| Type | Shows |
|---|---|
| `PBOOK BOOK-RATES <GO>` | A book's path computed twice: by the Python engine and by the Java engine, with progress |
| `PSTRIP STRIP-FX-001 <GO>` | One strip: its 250 scenario P&Ls |
| `PSUMJ BOOK-FX <GO>` | The computed answer itself, with its stamp (parameters ride on the id: BOOK-FX~delay-ms=50) |

| Command | Kind | Opens |
|---|---|---|
| `PBOOK` | `path-book` | Path book |
| `PSTRIP` | `path-strip` | P&L strip |
| `PSUM` | `path-sum` | Book path (Python) |
| `PSUMJ` | `path-sum-java` | Book path (Java) |

A book's page asks both engines for the sum of its strips' paths: the Python engine runs in the compute pool `sample` (`config/compute/compute.yaml`), the Java twin in the server; each panel shows its own progress and state. Parameters ride on the id (`BOOK-FX~delay-ms=50`). How reactive connectors work: [DATA_ARCHITECTURE.md](../../architecture/DATA_ARCHITECTURE.md#4-reactive-connectors).

### Data retrieval

Every kind's data contract and example, where its data comes from (an external source and its feed, a generator, or a Drishti engine), how it reaches the lake and how to check it are generated into the pack's README: [*Data contracts*](../../../config/packs/core/compute-demo/README.md#data-contracts) and [*Data retrieval*](../../../config/packs/core/compute-demo/README.md#data-retrieval).
