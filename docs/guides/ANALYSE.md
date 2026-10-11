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
(a BI item with versions and review like any other) and **promote** what is good: a Python cell becomes a Python Measure or Visual draft, a slice becomes a report draft.

This is the reference. A short walk-through is [BI_TUTORIAL.md](BI_TUTORIAL.md) chapter 25; the design is [RUPAKA.md](../architecture/RUPAKA.md) section 20.9; the quant engine behind
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

## 1. At a glance

```
 dataset ──(your identity)──▶ server: masks columns, applies your row rules ──▶ extract (at most bi.explore.max_rows rows)
                                                                                    │
        ┌──────────────────────────────┬────────────────────────────────────────────┤
     Slice                           SQL                                         Python
  pivot in the page             console, SQLite in memory,                console's sandboxed worker (server)
  (plain JavaScript)            one read-only SELECT                      or Pyodide in the page (browser)
```

The extract is **rebuilt for you on every run** and never kept: the console holds nothing between calls, so a mask or a row rule changed on the server applies the next time you run a cell.

![The Slice tab](img/analyse/slice.png)

## 2. The extract

Choose a dataset, tick the fields (columns of its tables; `id` is added for you and dropped from the extract), and **Open the extract**. The line under the form tells you what you got:
`60 rows, 8 columns.` and, when something is masked for you, `Masked for you: mtm.` The rows are the table's *own* rows (not grouped), cut to `bi.explore.max_rows`
(10,000); a cut says so (`60 rows of 4,000 (cut to the row limit)`). Columns are named for the table's column (`desk`, not `trade.desk`); two columns of the same name keep their
table (`trade_desk`).

Because the extract is cut, **Slice and SQL give the extract's totals, not the dataset's**. For true totals use a report.

An extract can also be a **report visual's data** (`POST /bi/explore/extract` with `{report, page, visual, state}`), the same rows the visual drew, masked the same way; the page's
form opens datasets, and a link from a report is planned (section 12).

## 3. Slice

Fields appear as chips. Add one to **Rows**, **Columns**, **Values** or **Filters** with its small buttons (`R`, `C`, `V`, `F`, which work from the keyboard and on a phone) or drag it to a zone.
A value takes an aggregation (`sum`, `avg`, `count`, `min`, `max`; a text field starts as `count`); a filter keeps rows where the field equals one of its values. The pivot redraws at once.
It is plain JavaScript over the extract the page holds; **DuckDB-Wasm is not vendored**, so there is no WebAssembly on this page and nothing to configure
(its slot is in section 12). The table shows the first 500 pivot rows.

## 4. SQL

A SQL cell is **one `SELECT`** (or `WITH ... SELECT`) over the table **`extract`**:

```sql
SELECT desk, COUNT(*) AS trades, SUM(notional) AS notional
FROM extract GROUP BY desk ORDER BY notional DESC
```

It runs on the console in an in-memory SQLite database that is **read-only by construction**: an authorizer refuses everything but `SELECT`, reads and plain functions (so `INSERT`,
`UPDATE`, `DELETE`, `DROP`, `CREATE`, `ATTACH`, `PRAGMA` and `load_extension` are `DRS-9073`), a second statement is `DRS-9070`, text over `bi.explore.sql_max_chars` (20,000) and a query
running past `bi.explore.sql_max_seconds` (5) are `DRS-9072`, and at most `bi.explore.sql_max_rows` (5,000) rows come back (more are cut, and the result says so). Dialect: SQLite's
(`date()`, `strftime`, `||`, `LIMIT`). *Run all* runs the cells in order.

![A SQL cell](img/analyse/sql.png)

## 5. Python

A Python cell sees three names:

| Name | What |
|---|---|
| `df` | the extract as a pandas DataFrame |
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

Notebooks are **not run by the server** and have no lineage ([BI_GOVERNANCE.md](BI_GOVERNANCE.md) section 10); at most `bi.explore.max_cells` (60) cells.

## 7. Promote

Promoting writes a **draft** item in your workspace; nothing is published.

* **A Python cell to a Measure or a Visual** (the cell's buttons): the cell's `df` becomes the function's `table`, `result` is returned, the packages the cell imports go into the manifest
  ([PYTHON_BI.md](PYTHON_BI.md) section 3). Check it with *Check the Python* on the item page, edit the entry's contract (a measure returns one number per group, a visual an
  ECharts option, SVG or PNG), then publish. A measure also needs a dataset measure `{ python, inputs, by }` that names it.
* **A slice to a report draft** ("Promote to a report draft"): a one-page report on the same dataset with a **matrix** (rows, one column field, values) or, when no value matches a dataset
  measure, a **table** of the fields. A value becomes a dataset *measure* only when one exists with the same expression (`sum(trade.notional)`); the rest are listed in the notes so
  you add the measure to the dataset. Open it in the designer to finish.

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
| `quant_engine` | `stand-in` | the engine behind `quant.price` |

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

**Not built, and not faked.**

* **DuckDB-Wasm in the page.** The design (RUPAKA 20.9) wanted Slice and SQL in the browser; DuckDB-Wasm is not vendored in this build (`tools/fetch-duckdb-wasm.sh` fetches it for the phase 0 proof of
  concept only), so Slice is plain JavaScript and SQL runs on the console over the same extract (SQLite, not DuckDB: a different dialect). Moving them in-browser needs the narrow wasm
  policy on this page; it is the planned next step, not done.
* **Extracts of a million rows**, Perspective in Analyse, Graphic Walker, a *Data* tab (editing the extract), *Notes* cells, outputs saved with a notebook, scheduled notebook runs, parameters
  and widgets, sharing a notebook's outputs, a link *Analyse this* from a report visual or from a terminal view, a "promote to a Transform", cell tests.
* **Pivot extras**: grand totals and subtotals in the Slice (use a matrix in a report for true totals), sorting by a value, number formats.
* **Quant:** the real engine (QLRisk); `quant` in the browser target.
