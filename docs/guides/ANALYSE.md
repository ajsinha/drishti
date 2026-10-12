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
# Analyse: slice, query and script an extract

**Analyse** (`/bi/explore`, the rail's *Analyse*) is the analyst's bench of Drishti BI. You open a dataset as an **extract**, the rows *you* may see (masked columns stay masked, row rules
apply), and work on it three ways: **Slice** (a drag-and-drop pivot), **SQL** (cells over the extract) and **Python** (pandas cells, with charts). You save the work as a **notebook**
(a BI item with versions and review like any other) and **promote** what is good: a Python cell becomes a Python Measure or Visual draft, a slice or a SQL cell becomes a **visual in a new or an existing report draft**.
**Slice and SQL run in your browser** (DuckDB-Wasm) on the extract the page already holds, with the console as the fallback ([section 13](#13-in-the-browser-duckdb-wasm)).

This is the reference. A walk-through is [tutorial 14](../tutorials/14-python-and-analyse.md); the design is [RUPAKA.md](../architecture/RUPAKA.md) section 20.9; the quant engine behind
`quant.price(...)` is [QUANT_SERVICE.md](../architecture/QUANT_SERVICE.md#16-the-consoles-quantengine-slot-built-lean).

## Contents

1. [At a glance](#1-at-a-glance)
2. [The extract](#2-the-extract)
3. [Slice](#3-slice)
4. [SQL](#4-sql)
5. [Python](#5-python)
6. [Notebooks](#6-notebooks)
7. [Promote](#7-promote)
8. [Who sees what](#8-who-sees-what)
9. [Settings](#9-settings)
10. [Errors](#10-errors)
11. [The routes](#11-the-routes)
12. [Built and not built](#12-built-and-not-built)
13. [In the browser: DuckDB-Wasm](#13-in-the-browser-duckdb-wasm)
14. [Promote to a report visual](#14-promote-to-a-report-visual)

## 1. At a glance

```
 dataset ──(your identity)──▶ server: masks columns, applies your row rules ──▶ extract (at most bi.explore.max_rows rows)
                                                                                    │
        ┌──────────────────────────────┬────────────────────────────────────────────┤
     Slice                           SQL                                         Python
  DuckDB-Wasm in the page       DuckDB-Wasm in the page                   console's sandboxed worker (server)
  (else plain JavaScript)       (else the console: SQLite,                or Pyodide in the page (browser)
                                one read-only SELECT)
```

The extract is **rebuilt for you on every run** and never kept: the console holds nothing between calls, so a mask or a row rule changed on the server applies the next time you run a cell.

![The Slice tab](img/analyse/slice.png)

## 2. The extract

Choose a dataset, tick the fields (columns of its tables; `id` is added for you and dropped from the extract), and **Open the extract**. The line under the form tells you what you got:
`60 rows, 8 columns.` and, when something is masked for you, `Masked for you: mtm.` The rows are the table's *own* rows (not grouped), cut to `bi.explore.max_rows`
(10,000); a cut says so (`60 rows of 4,000 (cut to the row limit)`). Columns are named for the table's column (`desk`, not `trade.desk`); two columns of the same name keep their
table (`trade_desk`).

**More than one extract.** *More extracts: join several datasets* (under the form) adds further extracts to the notebook, each a name, a dataset and fields, built for you by the server exactly as the first is (your row rules, your masks; `bi.explore.max_extracts`, 6, besides the first). A **Python** cell reads one as `extract("name")` (a pandas DataFrame; a copy, so changing it changes nothing else), or by the dataset it came from, and joins them in pandas; a **SQL** cell reads each as a table of that name. Real output of the sample notebook (below), with two extracts, the trades of `trading-risk` as `df` and the netting sets of `counterparty-exposure` as `exposure`, joined in a Python cell:

![The notebook's extracts: the first extract opened, and the second, exposure, under More extracts](../tutorials/img/t14/13-analyse-extracts.jpg)

```python
pnl = df.groupby("counterpartyId", as_index=False)["pnl1d"].sum().rename(columns={"counterpartyId": "counterparty", "pnl1d": "desk_pnl"})
ee = extract("exposure").groupby("counterparty", as_index=False)["eePeak"].sum().rename(columns={"eePeak": "ee_peak"})
joined = pnl.merge(ee, on="counterparty", how="left")
joined["pnl_per_exposure"] = joined["desk_pnl"] / joined["ee_peak"]
result = joined.sort_values("ee_peak", ascending=False).head(15)
```

![The join cell's result: counterparties with their P&L, expected exposure and P&L per exposure; the line says Python, server, 220 ms, 750 rows](../tutorials/img/t14/14-analyse-join.jpg)

Every extract is the reader's, so a join of two extracts shows **their** rows: the join key that the server masks for them (the counterparty id, for a reader without the raw power) arrives masked and the join finds nothing, which is the mask doing its job. Each extract counts toward the answer's `masked`, and each is cut to `bi.explore.max_rows` separately. Python in the browser cannot read further extracts (Pyodide gets the first only): a cell that calls `extract(...)` runs on the server.

Because the extract is cut, **Slice and SQL give the extract's totals, not the dataset's**. For true totals use a report.

An extract can also be a **report visual's data** (`POST /bi/explore/extract` with `{report, page, visual, state}`), the same rows the visual drew, masked the same way; the page's
form opens datasets, and a link *Analyse this* from a report is not built (section 12).

## 3. Slice

Fields appear as chips. Add one to **Rows**, **Columns**, **Values** or **Filters** with its small buttons (`R`, `C`, `V`, `F`, which work from the keyboard and on a phone) or drag it to a zone.
A value takes an aggregation (`sum`, `avg`, `count`, `min`, `max`; a text field starts as `count`); a filter keeps rows where the field equals one of its values. The pivot redraws at once.
The pivot is worked out by **DuckDB-Wasm in your browser** over the extract the page holds (one `GROUP BY`; [section 13](#13-in-the-browser-duckdb-wasm)), or, when it is not installed or cannot start, by plain JavaScript over the same
rows: the two give the same table (a Chromium test compares them). The line under the table says which: *Worked out by DuckDB in this browser* or *Worked out in this page*. The table shows the first 500 pivot rows.

![The Slice tab worked out in the browser: the line under the choice of engine and under the table says so](../tutorials/img/t14/19-analyse-browser.jpg)

## 4. SQL

A SQL cell is **one `SELECT`** (or `WITH ... SELECT`) over the table **`extract`** (and over a table for each further extract of the notebook, named as it is, so `SELECT ... FROM extract JOIN exposure ON ...`):

```sql
SELECT desk, COUNT(*) AS trades, SUM(notional) AS notional
FROM extract GROUP BY desk ORDER BY notional DESC
```

It runs **in your browser** in DuckDB-Wasm when that is installed (section 13), else on the console in an in-memory SQLite database that is **read-only by construction**: an authorizer refuses everything but `SELECT`, reads and plain functions (so `INSERT`,
`UPDATE`, `DELETE`, `DROP`, `CREATE`, `ATTACH`, `PRAGMA` and `load_extension` are `DRS-9073`), a second statement is `DRS-9070`, text over `bi.explore.sql_max_chars` (20,000) and a query
running past `bi.explore.sql_max_seconds` (5) are `DRS-9072`, and at most `bi.explore.sql_max_rows` (5,000) rows come back (more are cut, and the result says so). **The dialect depends on where it ran**: the console's is SQLite's
(`date()`, `strftime`, `||`, `LIMIT`), the browser's is DuckDB's (`strftime(date, '%Y-%m')`, `date_trunc`, `list_agg`, `QUALIFY`, window functions, `FROM extract` without a `SELECT`); the line under the result says which engine ran it
(`DuckDB in this browser` or `run by the console (SQLite)`), and the engine choice above the tabs forces one. A query that uses a dialect feature only one of them has fails on the other: write for the engine you run. *Run all* runs the cells in order.

![A SQL cell](img/analyse/sql.png)

## 5. Python

A Python cell sees three names:

| Name | What |
|---|---|
| `df` | the extract as a pandas DataFrame |
| `extract(name)` | any other extract of the notebook (by its name, or by the dataset it came from) as a pandas DataFrame, for the same reader; `KeyError` names the extracts there are. **Server target only** |
| `params` | a dict (empty unless the call sends some) |
| `quant` | `quant.price(instrument, **parameters)`, `quant.instruments()`: the console's quant engine ([QUANT_SERVICE.md](../architecture/QUANT_SERVICE.md#16-the-consoles-quantengine-slot-built-lean)). **Server target only** |

What the cell prints (`print`) comes back as text. What it puts in **`result`** is drawn: a DataFrame or Series as a table (the first 200 rows), a dict as an **ECharts option**
(formatter keys are removed, as for a Python visual), SVG text or PNG bytes as an image, anything else as a value. `import` works for the packages on the allow-list
(`bi.python.packages`) that are installed, plus the safe standard library; the cell runs in the **same sandbox as a Python visual** ([PYTHON_BI.md](PYTHON_BI.md) section 7: no network, a
scratch folder, CPU, memory, time and output limits).

```python
g = df.groupby("desk", as_index=False)["notional"].sum()
print(round(quant.price("black_scholes", spot=100, strike=100, rate=0.05, vol=0.2, years=1), 4))   # 10.4506
result = {"title": {"text": "Notional by desk"}, "xAxis": {"type": "category", "data": list(g["desk"])},
          "yAxis": {"type": "value"}, "series": [{"type": "bar", "data": [float(v) for v in g["notional"]]}]}
```

![A Python cell with a chart](img/analyse/python.png)

**Where it runs** (the cell's selector): **Server (sandbox)**, the default, or **Browser (Pyodide)**, Calc's runtime loaded on first use (needs `tools/fetch-pyodide.sh`; it has numpy, pandas,
scipy and statsmodels, not pyarrow; `quant` raises a clear `ValueError` there). The Analyse page keeps the **strict content-security policy** (no WebAssembly): browser Python runs in
Calc's own Web Worker, whose response alone carries `'wasm-unsafe-eval'`, exactly as for a Python visual. **Running Python needs the `bi-author` power**; Slice and SQL need only `bi`.

## 6. Notebooks

**Save as a notebook** stores the extract's definition, the slice and every cell as a BI item of kind **`notebook`**: in *My workspace* (or, if you opened a notebook you may edit, as a new version
of it). It has versions, diffs, review and publishing like any item ([BI_GOVERNANCE.md](BI_GOVERNANCE.md)); *Open in Analyse* on the item page (or `/bi/explore?item=<id>`, or the
notebook picker) reopens it: the extract is rebuilt for whoever opens it, so **a notebook shared with someone else shows *their* rows**. Outputs are not stored, only the code.

The body is YAML, checked by `POST /bi/explore/notebook/check` (unknown keys and bad cells are `DRS-9070`, naming the cell):

```yaml
rachana: 1
notebook: desk-notional-first-look
title: Desk notional, first look
dataset: file:dataset:trades-basic
fields: [trade.tradeId, trade.desk, trade.book, trade.notional, trade.mtm]
extracts:                       # optional: further extracts, read by a cell as extract("name") and in SQL as a table of that name
  - { name: exposure, dataset: file:dataset:counterparty-exposure, fields: [netting_sets.counterparty, netting_sets.eePeak] }
cells:
  - type: slice
    slice: { rows: [desk], columns: [], values: [{ field: notional, agg: sum }], filters: [] }
  - type: sql
    source: SELECT desk, SUM(notional) FROM extract GROUP BY desk
  - type: python
    source: |
      result = df.groupby("desk", as_index=False)["notional"].sum()
    target: server
```

A notebook may also carry the catalogue's keys (`sample`, `tags`, `endorsement`, `owner`, `audience`); a shipped one is `config/bi/notebooks/pnl-vs-exposure-pandas.notebook.yaml`. Notebooks are **not run by the server** and have no lineage ([BI_GOVERNANCE.md](BI_GOVERNANCE.md) section 10); at most `bi.explore.max_cells` (60) cells.

## 7. Promote

Promoting writes a **draft** item in your workspace; nothing is published.

* **A Python cell that joins extracts to a Transform** (*Promote to a Transform*, shown when the notebook has further extracts): each `extract("name")` becomes a parameter of `run(...)`, a use of `df` becomes the first extract, the cell's `result` is returned, and the draft's `inputs` are the extracts' datasets, in order. It is a draft **Python item with `interface: transform`** ([PYTHON_BI.md section 13](PYTHON_BI.md#13-transforms-a-table-in-a-table-out)); check it, publish it, then write the derived dataset that names it ([DATASETS.md section 11.2](DATASETS.md#112-derived-datasets-a-python-transform)): the promotion says so in its notes. `result` must be a pandas DataFrame with column names of letters, digits and underscore.

![After promoting the join cell: "Python transform draft saved in your workspace: ..."](../tutorials/img/t14/15-promote-transform.jpg)

* **A Python cell to a Measure or a Visual** (the cell's buttons): the cell's `df` becomes the function's `table`, `result` is returned, the packages the cell imports go into the manifest
  ([PYTHON_BI.md](PYTHON_BI.md) section 3). Check it with *Check the Python* on the item page, edit the entry's contract (a measure returns one number per group, a visual an
  ECharts option, SVG or PNG), then publish. A measure also needs a dataset measure `{ python, inputs, by }` that names it.
* **A slice or a SQL cell to a report visual** (section 14): a visual, with its dataset, wells and filters, in a new report draft or added to one you already have.

## 8. Who sees what

The server decides, as everywhere in BI: the extract request goes to `POST /api/v1/bi/datasets/{id}/query` (or the report's query) **as you**, so the same column masks and row rules as a
report apply, and a masked column arrives empty. SQL and Python only ever see that extract; the sandbox has no network and no way back to the server. A cell cannot widen access, and a
saved notebook holds code, not data. Python needs `bi-author` because arbitrary code runs in the console's worker pool (`bi.python.workers`).

## 9. Settings

The console's `bi.explore.*` ([CONFIGURATION.md](../admin/CONFIGURATION.md)):

| Setting | Default | Meaning |
|---|---|---|
| `max_rows` (`DRISHTI_BI_EXPLORE_MAX_ROWS`) | `10000` | most rows of an extract; 10 to 100,000 |
| `max_fields` | `60` | most columns of an extract |
| `sql_max_rows`, `sql_max_seconds`, `sql_max_chars` | `5000`, `5`, `20000` | the SQL cell's limits |
| `cell_timeout_seconds`, `cell_memory_mb`, `cell_max_chars` | `20`, `512`, `65536` | a Python cell's limits (also capped by `bi.python.max_timeout_seconds`, `max_memory_mb`) |
| `max_cells` | `60` | most cells in a notebook |
| `max_extracts` | `6` | most further extracts in a notebook, besides the first (`DRS-9072`) |
| `quant_engine` | `stand-in` | the engine behind `quant.price` |
| `browser_engine` | `auto` | `auto`: Slice and SQL run in the browser with DuckDB-Wasm when it is installed (`tools/fetch-duckdb-wasm.sh`), the console otherwise; `server`: always on the console (and the choice of engine is not offered) |
| `browser_start_seconds` | `30` | how long DuckDB-Wasm has to start in the browser (the first use fetches about 36 MB of WebAssembly) before the console takes over; 3 to 300 |

The Python runner's own `bi.python.*` settings (workers, packages, limits) apply to cells too. **The server's `drishti.bi.report.python-packages` and the console's
`bi.python.packages` must list the same packages** (a test, `test_python_packages_agree_between_the_server_and_the_console`, fails the build otherwise).

## 10. Errors

Analyse uses the Python items' codes ([API_GUIDE.md](API_GUIDE.md) table; [TROUBLESHOOTING.md](TROUBLESHOOTING.md)):

| Code | HTTP | In Analyse |
|---|---|---|
| `DRS-9070` | 422 | the request is wrong (no fields, a bad name, a notebook that does not check, a SQL syntax error, two statements, a cell that does not parse) |
| `DRS-9071` | 422 | the Python cell raised (the traceback is shown to `bi-author`) |
| `DRS-9072` | 413 or 504 | a limit: SQL text, SQL time, cell size, memory, time, output, extract input |
| `DRS-9073` | 403 | refused: SQL that is not a plain SELECT, Python that needs the network, a file or a package it did not list, or Python without `bi-author` |
| `DRS-9074` | 503 | the runner is off, busy or lacks `pandas`/`pyarrow`; or no Pyodide for a browser run |

The server's own refusals (`DRS-9001` no such dataset, `DRS-9020` series, a denied dataset) come through unchanged.

## 11. The routes

All are the console's own, JSON from its pages only (same-origin, `application/json`, the session cookie; no personal token): `GET /bi/explore`, `GET /bi/explore/status`, `POST /bi/explore/extract`,
`POST /bi/explore/sql`, `POST /bi/explore/python`, `POST /bi/explore/notebook/check`, `POST /bi/explore/promote`. Tables: [API_GUIDE.md](API_GUIDE.md) (console routes of Python in BI). Saving
uses the existing `POST /api/v1/bi/workspaces/me/items` and `PUT /api/v1/bi/items/{id}`.

## 12. Built and not built

**Built (phase 9, lean).** The extract, Slice, SQL cells, Python cells on the server and in the browser, notebooks as items, promotion of cells and slices, the `QuantEngine` slot with a stand-in.

**Built with several datasets (section 2, RUPAKA.md section 20.10).** Several masked extracts in one notebook, `extract("name")` in a Python cell and a table per extract in SQL, the notebook's `extracts:`, and the promotion of a join cell to a Python Transform draft.

**Not built, and not faked.**

* **Further extracts in the browser engine.** The page holds the first extract only; a notebook with further extracts (a SQL cell that joins them) runs on the console. A SQL cell the page cannot read as a query over `extract` is promoted as far as it can be read (section 14).
* **Extracts of a million rows**, Perspective in Analyse, Graphic Walker over an extract (it is in Calc: [PYTHON_CALC.md](PYTHON_CALC.md#19-the-bi-client-and-graphic-walker)), a *Data* tab (editing the extract), *Notes* cells, outputs saved with a notebook, scheduled notebook runs, parameters
  and widgets, sharing a notebook's outputs, a link *Analyse this* from a report visual or from a terminal view, cell tests, further extracts in the **browser** target (they need the server's sandbox), and an `extract(...)` of a **report visual** (further extracts are datasets).
* **Pivot extras**: grand totals and subtotals in the Slice (use a matrix in a report for true totals), sorting by a value, number formats.
* **Quant:** the real engine (QLRisk); `quant` in the browser target.

## 13. In the browser: DuckDB-Wasm

Slice and SQL run on the extract **your page already holds**, with [DuckDB-Wasm](https://duckdb.org/docs/api/wasm/overview) (MIT, vendored; the build is 36 MB, so it is installed by `tools/fetch-duckdb-wasm.sh` and not committed:
[VENDOR.md](../../drishti-console/web/static/vendor/VENDOR.md)). The rows are the ones the server built for you (row rules and masks applied); DuckDB runs on a copy in the tab's memory and nothing is sent back.

**The policy.** The page keeps the **strict** content security policy (no `unsafe-eval`, no `wasm-unsafe-eval`, no inline style): the page only imports DuckDB's script and starts its worker, and **the worker** is what compiles WebAssembly, under the policy the console puts on that one file
(`script-src 'self' 'wasm-unsafe-eval'`, `connect-src` to the console's static files only: `core/app.py`, `worker_csp`). A Chromium test loads the page, runs both tabs and asserts that no violation was reported.

**What DuckDB is allowed to do.** The extract is the only table. Once it is loaded, `SET enable_external_access = false` and `SET lock_configuration = true` close the doors: a query cannot read a file or a URL (`read_csv('http://...')` fails) or change a setting. The page also refuses what is not one read-only statement
(`SELECT`, `WITH`, `FROM`, `VALUES`, `DESCRIBE`, `SUMMARIZE`; `COPY`, `ATTACH`, `INSTALL` and a second statement are `DRS-9073`, as on the console; DuckDB's own errors are `DRS-9070`). A query cannot be interrupted inside DuckDB, so one that runs past `bi.explore.sql_max_seconds` has its worker **ended** (`DRS-9072`) and the next run starts a fresh one. Reading a file counts as external access, so a different extract starts a new engine.

**When the console does it instead.** DuckDB-Wasm not installed, `bi.explore.browser_engine: server`, a browser without WebAssembly, an engine that did not start within `bi.explore.browser_start_seconds`, or a notebook with further extracts. Slice then uses the JavaScript pivot and SQL the console's SQLite route; nothing else changes
(a Chromium test blocks the engine and checks both fall back). A refusal of the query itself (`DRS-9070`, `9072`, `9073`) is **not** a reason to fall back: it is shown.

## 14. Promote to a report visual

*Promote to a report draft* on the Slice tab and *Promote to a report visual* on a SQL cell make a **visual** from what you did. Choose where first, in the line above the tabs: **a new report draft**, or one of the report drafts you may edit (your workspace and the shared ones you contribute to), and optionally the page.

![A SQL cell promoted into an existing report draft: the note says what was left out and that the report was written again](../tutorials/img/t14/20-promote-existing.jpg)

| From | The visual |
|---|---|
| a **slice** | a **matrix** (rows, one column field, values) when a value matches a dataset measure, else a **table** of the fields, else a **card**; the slice's **filters** become the visual's `filters` (`field`, `op: in`, `values`) |
| a **SQL cell** | read by a small reader (`core/promote_report.py`), not a SQL engine: the plain columns and `GROUP BY` columns become *rows* (or the table's columns), the aggregates `sum`, `avg`, `count`, `min`, `max` of a column become *values*, and simple `WHERE` conditions (`col = 'x'`, `<>`, `<`, `<=`, `>`, `>=`, `IN (...)`, `NOT IN`, `BETWEEN a AND b`, `IS [NOT] NULL`, joined by `AND`) become *filters* |

* **Values are measures.** A report shows the dataset's measures, so an aggregate becomes a value only when the dataset has a measure that is exactly that (`sum(trade.mtm)`; `count(*)` is the measure `count(trade)`); the notes name each one that is missing so you add it to the dataset. Names are mapped from the extract's (`desk`) back to the dataset's (`trade.desk`).
* **What cannot be said is said.** A join or a sub-query, an expression (`notional * 2`), `LIKE`, `OR`, `HAVING`, `count(distinct ...)` are left out and listed in the notes; `ORDER BY` and `LIMIT` are left out too (a visual sorts and limits in its own settings). Nothing is guessed.
* **The dataset.** The visual reads the dataset the extract was opened from. In an existing report that reads another one, the visual names its own `dataset:` and the note says so (a `link` in the report connects their slicers: [REPORTS.md](REPORTS.md)).
* **An existing report** is read through the server's checker (`POST /bi/api/reports/validate`), the visual is added **below** what the page holds with a free id (`slice`, `sql`, then `slice-2` ...), and the report is written again by the designer's writer (`/reports/format`) and saved as the item's **draft** (`PUT /bi/api/items/<id>`). Comments in the report's YAML are **not kept** (the note says so); nothing is published. The target must be a report item you may edit: a shipped one, or one that is not a report, is `DRS-9070`.
* In both cases the draft is yours to finish in the designer, then check and publish like any report.

The route is the console's `POST /bi/explore/promote` with `{to: report, name, dataset, slice, filters}` or `{to: report, name, dataset, sql}`, plus `columns` and `qualified` (the extract's names and the dataset's), and `target` and `page` for an existing report; it answers `{kind: report, title, yaml, notes, target, visual}`.
