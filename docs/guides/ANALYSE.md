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
**Nothing runs in your browser** (wave B, 2.3.0): the Slice is worked out by the console, SQL is Drishti SQL run by the server over the model, Python runs in
the console's sandbox ([section 13](#13-in-the-browser-duckdb-wasm)). The page asks and draws. A **Profile** tab (2.4.0, [section 15](#15-profile)) shows
every column of the extract; your own Python **in the browser** is the [Python console](#16-the-browser-python-console) (2.4.0), which is not a BI page.

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
15. [Profile](#15-profile)
16. [The browser Python console](#16-the-browser-python-console)

## 1. At a glance

```
 dataset ──(your identity)──▶ server: masks columns, applies your row rules ──▶ extract (at most bi.explore.max_rows rows)
                                                                                    │
        ┌──────────────────────────────┬────────────────────────────────────────────┤
     Slice                           SQL                                         Python
  the console                   the server: Drishti SQL over the model    the console's sandbox
  (POST /bi/explore/pivot)      (a cell over `extract`: the console,       (a fork-server child per call)
                                one read-only SELECT in SQLite)
```

The extract is **rebuilt for you on every run** and never kept: the console holds nothing between calls, so a mask or a row rule changed on the server applies the next time you run a cell.

![The Slice tab](img/analyse/slice.png)

## 2. The extract

Choose a dataset, tick the fields (columns of its tables; `id` is added for you and dropped from the extract), and **Open the extract**. The line under the form tells you what you got:
`60 rows, 8 columns.` and, when something is masked for you, `Masked for you: MTM.` The rows are the table's *own* rows (not grouped), cut to `bi.explore.max_rows`
(10,000); a cut says so (`60 rows of 4,000 (cut to the row limit)`). Columns are named for the table's column (`DESK`, not `trade.DESK`); two columns of the same name keep their
table (`trade_DESK`).

**More than one extract.** *More extracts: join several datasets* (under the form) adds further extracts to the notebook, each a name, a dataset and fields, built for you by the server exactly as the first is (your row rules, your masks; `bi.explore.max_extracts`, 6, besides the first). A **Python** cell reads one as `extract("name")` (a pandas DataFrame; a copy, so changing it changes nothing else), or by the dataset it came from, and joins them in pandas; a **SQL** cell reads each as a table of that name. Real output of the sample notebook (below), with two extracts, the trades of `trading-risk` as `df` and the netting sets' computed exposure of `counterparty-exposure` (the official results, `set_exposure`) as `exposure`, joined in a Python cell:

![The notebook's extracts: the first extract opened, and the second, exposure, under More extracts](../tutorials/img/t14/13-analyse-extracts.jpg)

```python
pnl = df.groupby("COUNTERPARTY", as_index=False)["PNL_1D"].sum().rename(columns={"COUNTERPARTY": "counterparty", "PNL_1D": "desk_pnl"})
ee = extract("exposure").groupby("counterparty", as_index=False)["PEAK_EE"].sum().rename(columns={"PEAK_EE": "ee_peak"})
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
The pivot is worked out by **the console** over your extract (`POST /bi/explore/pivot`: the extract is rebuilt for you, grouped, and the table and the
filters' values come back; at most 2,000 row or column keys, `DRS-9072` beyond). The line under the table says *Worked out by the console*. The table shows the
first 500 pivot rows. (Until 2.2 the pivot was DuckDB-Wasm or JavaScript in the page; the browser computes nothing since wave B.)

![A SQL cell on the server engine: Drishti SQL over the model, the engine select, and the line naming the model and the plan](../tutorials/img/t14/19-analyse-engines.jpg)

## 4. SQL

**Since 2.2.0 a SQL cell runs on the server by default** ([BI_DESIGN.md §3.4](../architecture/BI_DESIGN.md#34-drishti-sql-dsql-read-only-sql-over-models)):
the cell's engine choice says *Server: Drishti SQL over the model*, and the statement is **Drishti SQL over the model the extract reads** (a model, or a
dataset's implicit model), not over the extract. Its tables are the model's aliases, a measure is `MEASURE('name')`, `JOIN` needs no `ON` (the model's
relationships are used), and the server answers **as you**, with your row rules and masks, through the same plan as the model query it equals. A new
cell starts with a statement written from the model (the first text field you ticked and the model's first measure). Captured for an extract of the
model *SQL model* (trades and desks, one measure *Sum of MTM*):

```sql
SELECT trade.CURRENCY,
       MEASURE('Sum of MTM') AS "Sum of MTM"
FROM   trade
GROUP  BY trade.CURRENCY
LIMIT  20
```

The cell's footer says *3 rows in 4 ms · Drishti SQL, run by the server over sql-model@1 as you · plan p_825d3a8857*, with the masked columns when
there are any:

![A SQL cell: the statement above, Run (Drishti SQL, run by the server over the model), and three currencies with their Sum of MTM: EUR -75000, GBP 425000, USD -575000](img/bi-languages/analyse-dsql-cell.jpg) The console sends the cell to
`POST /bi/explore/dsql` (`{extract, sql}`), which asks the server's `POST /api/v1/bi/sql` with the model and the cell's row limit
(`bi.explore.sql_max_rows`, one more to say when it was cut); it never reads the SQL itself. Every source has a model since 2.2: a notebook saved
earlier that names a 2.1 dataset (`file:dataset:trades-basic`) reads the model the converter made of it (`trades-basic@1`), and so does its extract,
which is one model query (shape rows) like every other. **Over the extract** stays selectable in each cell until wave B, and a statement that reads the table `extract` (an older notebook)
still runs there whatever the choice.

Over the extract, a SQL cell is **one `SELECT`** (or `WITH ... SELECT`) over the table **`extract`** (and over a table for each further extract of the notebook, named as it is, so `SELECT ... FROM extract JOIN exposure ON ...`):

```sql
SELECT DESK, COUNT(*) AS trades, SUM(NOTIONAL) AS notional
FROM extract GROUP BY DESK ORDER BY notional DESC
```

A cell over the model is **Drishti SQL, run by the server** as you (section 4's first half). A cell that reads the table `extract` (a notebook of 2.2) runs
on the console in an in-memory SQLite database that is **read-only by construction**: an authorizer refuses everything but `SELECT`, reads and plain functions (so `INSERT`,
`UPDATE`, `DELETE`, `DROP`, `CREATE`, `ATTACH`, `PRAGMA` and `load_extension` are `DRS-9073`), a second statement is `DRS-9070`, text over `bi.explore.sql_max_chars` (20,000) and a query
running past `bi.explore.sql_max_seconds` (5) are `DRS-9072`, and at most `bi.explore.sql_max_rows` (5,000) rows come back (more are cut, and the result says so). A cell over `extract` speaks SQLite's dialect (`date()`, `strftime`, `||`, `LIMIT`); the line under the result says which ran it (*Drishti SQL, run by the
server over …* or *over the extract, run by the console*). *Run all* runs the cells in order.

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
g = df.groupby("DESK", as_index=False)["NOTIONAL"].sum()
print(round(quant.price("black_scholes", spot=100, strike=100, rate=0.05, vol=0.2, years=1), 4))   # 10.4506
result = {"title": {"text": "Notional by desk"}, "xAxis": {"type": "category", "data": list(g["DESK"])},
          "yAxis": {"type": "value"}, "series": [{"type": "bar", "data": [float(v) for v in g["NOTIONAL"]]}]}
```

![A Python cell with a chart](img/analyse/python.png)

**Where it runs:** the console's sandbox, always (the line beside **Run** says *Runs on the console (sandbox)*): a child of the console's fork-server, with no
network, a scratch folder and CPU, memory, time and output limits. A notebook of 2.2 whose cell says `target: browser` runs there too. The Analyse page keeps
the **strict content-security policy**, with no WebAssembly. **Running Python needs the `bi-author` power**; Slice and SQL need only `bi`.

## 6. Notebooks

**Save as a notebook** stores the extract's definition, the slice and every cell as a BI item of kind **`notebook`**: in *My workspace* (or, if you opened a notebook you may edit, as a new version
of it). It has versions, diffs, review and publishing like any item ([BI_GOVERNANCE.md](BI_GOVERNANCE.md)); *Open in Analyse* on the item page (or `/bi/explore?item=<id>`, or the
notebook picker) reopens it: the extract is rebuilt for whoever opens it, so **a notebook shared with someone else shows *their* rows**. Outputs are not stored, only the code.

The body is YAML, checked by `POST /bi/explore/notebook/check` (unknown keys and bad cells are `DRS-9070`, naming the cell):

```yaml
rachana: 1
notebook: desk-notional-first-look
title: Desk notional, first look
dataset: mq:trades-basic@1       # the model it reads (mq:<model>@<major>, or dataset:<name> for a dataset's implicit model)
fields: [trade.TRADE_ID, trade.DESK, trade.PORTFOLIO, trade.NOTIONAL, trade.MTM]
extracts:                       # optional: further extracts, read by a cell as extract("name") and in SQL as a table of that name
  - { name: exposure, dataset: mq:counterparty-exposure@1, fields: [set_exposure.COUNTERPARTY, set_exposure.PEAK_EE] }   # the netting sets' computed exposure (2.9.0)
cells:
  - type: slice
    slice: { rows: [DESK], columns: [], values: [{ field: NOTIONAL, agg: sum }], filters: [] }
  - type: sql
    source: SELECT DESK, SUM(NOTIONAL) FROM extract GROUP BY DESK
  - type: python
    source: |
      result = df.groupby("DESK", as_index=False)["NOTIONAL"].sum()
    target: server
```

A notebook may also carry the catalogue's keys (`sample`, `tags`, `endorsement`, `owner`, `audience`); a shipped one is `config/bi/notebooks/pnl-vs-exposure-pandas.notebook.yaml`. Notebooks are **not run by the server** and have no lineage ([BI_GOVERNANCE.md](BI_GOVERNANCE.md) section 10); at most `bi.explore.max_cells` (60) cells.

## 7. Promote

Every promotion answers **beside the button you pressed** (one message, with an *Open the draft* link), not at the top of the page. Pressing the same promotion again for the same cell or slice does not make a duplicate: it offers **Update that draft** (the draft is written again) or **Save another**. A SQL cell promoted to a new report is titled *<dataset title> SQL* and gets a readable lower-case id. A notebook has one title: the item's title and the `title:` of its YAML always agree, whichever you rename; opening a notebook restores the Dataset and Notebook choices and the ticked fields.

Promoting writes a **draft** item in your workspace; nothing is published.

* **A Python cell that joins extracts to a Transform** (*Promote to a Transform*, shown greyed with "Add a second extract first" until the notebook has further extracts): each `extract("name")` becomes a parameter of `run(...)`, a use of `df` becomes the first extract, the cell's `result` is returned, and the draft's `inputs` are its parameters' names, in order (the inputs of the Python dataset that runs it). It is a draft **Python item with `interface: transform`** ([PYTHON_BI.md section 13](PYTHON_BI.md#13-transforms-a-table-in-a-table-out)); check it, publish it, then write the Python dataset that names it (`source: { python: <item>, inputs: { <name>: <a model query> } }`, [BI_MODEL_SPEC.md section 1.2](../architecture/BI_MODEL_SPEC.md#12-sources)): the promotion says so in its notes. `result` must be a pandas DataFrame with column names of letters, digits and underscore.

![After promoting the join cell: "Python transform draft saved in your workspace: ..."](../tutorials/img/t14/15-promote-transform.jpg)

* **A Python cell to a Measure or a Visual** (the cell's buttons): the cell's `df` becomes the function's `table`, `result` is returned, the packages the cell imports go into the manifest
  ([PYTHON_BI.md](PYTHON_BI.md) section 3). Check it with *Check the Python* on the item page, edit the entry's contract (a measure returns one number per group, a visual an
  ECharts option, SVG or PNG), then publish. A measure also needs a dataset measure `{ python, inputs, by }` that names it.
* **A slice or a SQL cell to a report visual** (section 14): a visual, with its dataset, wells and filters, in a new report draft or added to one you already have.

## 8. Who sees what

The server decides, as everywhere in BI: the extract request is a model query, `POST /api/v1/bi/mq` (or the report's query), **as you**, so the same column masks and row rules as a
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
| `DRS-9074` | 503 | the runner is off, busy or lacks `pandas`/`pyarrow` |

The server's own refusals (`DRS-9001` no such dataset, `DRS-9020` series, a denied dataset) come through unchanged.

## 11. The routes

All are the console's own, JSON from its pages only (same-origin, `application/json`, the session cookie; no personal token): `GET /bi/explore`, `GET /bi/explore/status`, `POST /bi/explore/extract`,
`POST /bi/explore/sql`, `POST /bi/explore/python`, `POST /bi/explore/profile` (2.4.0), `POST /bi/explore/notebook/check`, `POST /bi/explore/promote`; and the
Python console's `GET /pyconsole`, `POST /pyconsole/extract`, `POST /pyconsole/promote` (2.4.0). Tables: [API_GUIDE.md](API_GUIDE.md) (console routes of Python in BI). Saving
uses the existing `POST /api/v1/bi/workspaces/me/items` and `PUT /api/v1/bi/items/{id}`.

## 12. Built and not built

**Built (phase 9, lean).** The extract, Slice, SQL cells, Python cells on the server (in the browser too until 2.3.0), notebooks as items, promotion of cells and slices, the `QuantEngine` slot with a stand-in.

**Built with several datasets (section 2, RUPAKA.md section 20.10).** Several masked extracts in one notebook, `extract("name")` in a Python cell and a table per extract in SQL, the notebook's `extracts:`, and the promotion of a join cell to a Python Transform draft.

**Not built, and not faked.**

* **Promoting a SQL cell the page cannot read** as a query over `extract` goes only as far as it can be read (section 14). (Until 2.3.0 the browser engine held the first extract only; since then every extract is the console's.)
* **Extracts of a million rows**, Perspective in Analyse, Graphic Walker over an extract (it is in Calc: [PYTHON_CALC.md](PYTHON_CALC.md#19-the-bi-client-and-graphic-walker)), a *Data* tab (editing the extract), *Notes* cells, outputs saved with a notebook, scheduled notebook runs, parameters
  and widgets, sharing a notebook's outputs, a link *Analyse this* from a report visual or from a terminal view, cell tests, and an `extract(...)` of a **report visual** (further extracts are datasets).
* **Pivot extras**: grand totals and subtotals in the Slice (use a matrix in a report for true totals), sorting by a value, number formats.
* **Quant:** the real engine (QLRisk).

## 13. In the browser: DuckDB-Wasm

*Removed in 2.3.0; kept as history.* Wave B made the servers compute every number and the browser draw ([BI_DESIGN.md](../architecture/BI_DESIGN.md) principle P3): DuckDB-Wasm
and the browser's Python (Pyodide) are no longer loaded by Analyse or any BI page, `bi.explore.browser_engine` and `browser_start_seconds` are gone (a
setting left in a configuration is ignored), and `tools/fetch-duckdb-wasm.sh` is gone. The Slice is the console's, SQL the server's (or the console's
for a cell over `extract`), Python the console's sandbox. Calc (the terminal's Python) still uses Pyodide in its own worker, and so does the
browser Python console of 2.4.0 ([section 16](#16-the-browser-python-console)), which is not a BI page.

## 14. Promote to a report visual

*Promote to a report draft* on the Slice tab and *Promote to a report visual* on a SQL cell make a **visual** from what you did. Choose where first, in the line above the tabs: **a new report draft**, or one of the report drafts you may edit (your workspace and the shared ones you contribute to), and optionally the page.

![A SQL cell promoted into an existing report draft: the note says what was left out and that the report was written again](../tutorials/img/t14/20-promote-existing.jpg)

| From | The visual |
|---|---|
| a **slice** | a **matrix** (rows, one column field, values) when a value matches a measure of the model, else a **table** of the fields, else a **card**; the slice's **filters** become the visual's `filters` (`field`, `op: in`, `values`) |
| a **SQL cell** | read by a small reader (`core/promote_report.py`), not a SQL engine: the plain columns and `GROUP BY` columns become *rows* (or the table's columns), the aggregates `sum`, `avg`, `count`, `min`, `max` of a column become *values*, and simple `WHERE` conditions (`col = 'x'`, `<>`, `<`, `<=`, `>`, `>=`, `IN (...)`, `NOT IN`, `BETWEEN a AND b`, `IS [NOT] NULL`, joined by `AND`) become *filters* |

* **Values are measures.** A report shows the model's measures (`GET /models/<ref>/describe`), so an aggregate becomes a value only when the model has a measure that is exactly that (`sum(trade.MTM)`; `count(*)` is the measure `count(trade)`); the notes name each one that is missing so you add it to the model. Names are mapped from the extract's (`desk`) back to the model's (`trade.desk`).
* **What cannot be said is said.** A join or a sub-query, an expression (`NOTIONAL * 2`), `LIKE`, `OR`, `HAVING`, `count(distinct ...)` are left out and listed in the notes; `ORDER BY` and `LIMIT` are left out too (a visual sorts and limits in its own settings). Nothing is guessed.
* **The model.** A new report draft is `rachana: 2` with `model:` the model the extract was opened from (`trades-basic@1`). A report reads one model, so a slice of another model is not added to an existing report: `DRS-9070` says which model each reads; promote it to a new report instead.
* **An existing report** is read through the server's checker (`POST /bi/api/reports/validate`), the visual is added **below** what the page holds with a free id (`slice`, `sql`, then `slice-2` ...), and the report is written again by the designer's writer (`/reports/format`) and saved as the item's **draft** (`PUT /bi/api/items/<id>`). Comments in the report's YAML are **not kept** (the note says so); nothing is published. The target must be a report item you may edit: a shipped one, or one that is not a report, is `DRS-9070`.
* In both cases the draft is yours to finish in the designer, then check and publish like any report.

The route is the console's `POST /bi/explore/promote` with `{to: report, name, dataset, slice, filters}` or `{to: report, name, dataset, sql}`, plus `columns` and `qualified` (the extract's names and the dataset's), and `target` and `page` for an existing report; it answers `{kind: report, title, yaml, notes, target, visual}`.

## 15. Profile

*2.4.0.* The **Profile** tab shows every column of the extract: its type, what its text looks like (numbers or dates kept as text), empty values (count
and %), distinct values, whether it could be a key (every row its own value), min and max (and the mean of a number), the five most common values and a
histogram of a number. It is worked out by the console in its sandbox over the rows you may see (`POST /bi/explore/profile` with `{extract}`; no code of
yours runs), and drawn by the same panel as the [Transform editor](PYTHON_BI.md#175-profiling)'s. A column masked for you says so and profiles as empty.

![Analyse, Profile tab: the extract of the model prep-model as the analyst ann sees it, PRICE masked for her](img/bi-transform/07-analyse-profile.png)

## 16. The browser Python console

*2.4.0* ([BI_DESIGN.md section 12.2, wave C](../architecture/BI_DESIGN.md#wave-c--authoring-and-analysis--240)). **BI → Python console** (`/pyconsole`)
is personal, just-in-time analysis: **your own Python, in your browser**, on a masked extract of a model, with nothing kept or shared until you promote a
result. It is deliberately **not a BI page** (principle P3: no BI page computes): Analyse and every `/bi` page still load no WebAssembly; the console is to a
model what Calc is to a terminal view, and runs your code in **Calc's Web Worker** (Pyodide, the one script allowed to compile WebAssembly; the page keeps
the strict policy).

1. **Extract.** Choose a model or a dataset, tick the fields, optionally a date, and *Open the extract*. The console asks the server for the rows **as
   you** (a model query of rows: your row rules; a masked column arrives masked and is named), at most `bi.pyconsole.max_rows` (20,000), and hands them to
   the page (`POST /pyconsole/extract`). The worker gets them once; it never fetches data and holds no credential.
2. **Python.** `df` is the extract as a pandas frame (fresh on every run; `df.attrs["masked"]` names the masked columns). `show(x, title)` draws a table,
   `chart(df, kind="bar", x=..., y=...)` a chart (line, bar, scatter, hist), `print` prints, and a value in `result` is shown when nothing else is.
   *Run in the browser* (Ctrl+Enter); *Stop* ends the worker. numpy, pandas, scipy, statsmodels and matplotlib load on first use.

   ![The browser Python console: an extract of 60 rows, a cell that totals QUANTITY by book, its table and chart, run in the browser](img/bi-transform/05-browser-console.png)

3. **Promote to the server.** When a result is worth keeping, give it a title and *Promote to the server* (bi-author): the console rebuilds the extract
   **for you** on the server side and runs the **same code on the console backend**, in the Analyse cell sandbox ([section 5](#5-python); `show()` is the
   cell's answer when it sets no `result`, `chart()` is skipped there), then compares the two answers (*The same answer as in your browser*, or not) and
   keeps the code as a **notebook draft** in your workspace (`POST /pyconsole/promote`). Open it in Analyse, share it, review and publish it like any
   notebook: whoever opens it gets **their own** rows. Code that fails on the backend is not kept.

   ![Promoted: run on the console backend over the 60 rows rebuilt for you, the same answer as in the browser, saved as a notebook draft](img/bi-transform/06-promoted.png)

| Setting (console) | Default | Meaning |
|---|---|---|
| `bi.pyconsole.enabled` | `true` (`DRISHTI_BI_PYCONSOLE_ENABLED`) | false: no `/pyconsole` |
| `bi.pyconsole.max_rows` | `20000` (`DRISHTI_BI_PYCONSOLE_MAX_ROWS`) | most rows of an extract handed to the browser (10 to 100,000) |

It needs the BI power and Calc's runtime installed on the console (`tools/fetch-pyodide.sh`; no internet afterwards); promoting needs **bi-author**. What
the browser computes stays in that tab: a number shown to someone else always comes from the server or the console backend.
