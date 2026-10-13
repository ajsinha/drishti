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
# Tutorial 14 · Python and Analyse

SQL measures cover most questions. When a figure needs a real function (a standard deviation of P&L per desk, a histogram, a model) you write it in **Python**, and Drishti runs it **sandboxed, on the rows the person looking may see**. And when you do not yet know the question, **Analyse** gives you
an extract of a model to **slice**, query with **SQL**, work on in **Python**, keep as a **notebook**, and **promote** to a measure, a visual or a report draft. This tutorial does both on the demo trades, through the
model `trades-basic@1` (2.2: every sample is a model over datasets). Every step shows it **in the UI**, **on the command line** (`python3 tools/drishti.py`, or `curl`) and **as YAML**, or says why one of the three does not apply.

## Time to complete

About 55 minutes.

## Before you start

- [Tutorial 10](10-first-bi-report.md) done (reports, workspaces, `Economics desk`). You sign in as the development administrator, who holds the `bi-author` power that Python needs (reading with **Slice** and **SQL** needs only `bi`).
- The console's Python environment from [tutorial 1](01-first-15-minutes.md) (`drishti-console/.venv`): it carries `pandas`, `numpy` and `pyarrow`, which Python items may import. Nothing else to install for the **server** target. (The **browser** target runs the same code in Pyodide, a download of
  about 340 MB made once with `tools/fetch-pyodide.sh`; this tutorial uses the server.)
- Two small files in [data/](data/): [notional-by-desk.python.yaml](data/notional-by-desk.python.yaml) (a Python visual) and [desk-python.report.yaml](data/desk-python.report.yaml) (a report on the model `trades-basic@1` that uses it).
- The command line token of tutorial 1 (`DRISHTI_TOKEN`).

## What you will learn

- the two kinds of Python item: a **measure** (`compute(table, params)`, one number per group) and a **visual** (`render(table, params)`, a chart);
- how a report uses them, where they run, and what the sandbox forbids;
- **Analyse**: the extract of a model, **Slice**, **SQL** (Drishti SQL on the server over the model, or one read-only SELECT over the extract until wave B), a **Python** cell that draws a chart, saving a **notebook**, and **promoting** work into BI items.
- **Python across models**: a **Transform** that turns the answers of two model queries into a table (a **Python dataset**, 2.2), and an Analyse notebook that joins two extracts in pandas and promotes the join to a Transform;
- **Vega-Lite** charts (written in a report, or returned by Python), the **tests** a Python item carries, the **engines** of a SQL cell and promoting into an **existing** report, **Calc's BI client** with Graphic Walker (steps 9 to 11).

## Step 1 · Python in a report: the shipped sample

Open **Catalogue → Desk P&L risk (Python)**.

![The sample Python report: P&L volatility per desk as bars, and a histogram of one-day P&L; each frame says Python, the item, v1, server, the time and 750 rows](img/t14/01-python-report.jpg)

You should see two charts. Under each is a line such as `Python · desk-pnl-volatility v1 · server · 278 ms · 750 rows`: the **item** that drew it, its version, **where it ran** (the console's sandboxed worker, `server`) and how many rows it was handed. The left chart is a **Python measure** (the volatility of one-day P&L of each desk); the right one is a **Python visual** (a histogram drawn with numpy).
Both worked on the 750 trades **as you may see them**: masks and row rules were applied before Python saw a row.

The measure's source is a short YAML item, `config/bi/python/desk-pnl-volatility.python.yaml`:

```yaml
python: desk-pnl-volatility
title: Desk P&L volatility
interface: measure
inputs: [pnl1d]                  # the columns the function reads
packages: [pandas]               # what the source imports (must be on the allow-list)
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

The **model** (`config/bi/models/trades-basic.model.yaml`) says which groups it is called for (one number per desk) in a measure that is a Python item,
not an expression:

```yaml
measures:
  Desk P&L volatility:
    python: desk-pnl-volatility      # the item
    inputs: [trade.pnl1d]            # columns of ONE dataset of the model
    by: [trade.desk]                 # one number per desk
```

**On the command line** the model lists it with its item, beside the measures that are expressions:

```text
$ python3 tools/drishti.py bi model describe trades-basic@1
Trades, the basics  (trades-basic@1)

trade  fact  trades-basic-trade 1.0.0
  trade.tradeId                      string    key
  trade.desk                         string    dimension
  ...
  trade.pnl1d                        decimal   amount        signed0

measures
  Notional                          sum(trade.notional)
  MTM                               sum(trade.mtm)
  Trades                            count(trade)
  Average MTM per trade             measure('MTM') / measure('Trades')
  Desk P&L volatility               python: desk-pnl-volatility
  Count of trade                    count(trade)  (default)
```

A model query cannot compute it (`DRS-9162`: a python visual runs a Python measure; a grouped query cannot): it is drawn by the report's python visual,
which hands the rows to the item.

## Step 2 · A Python visual of your own

A visual is a function `render(table, params)` that gets the rows of the visual's `columns` well and returns a chart **as data** (an ECharts option, SVG text or PNG bytes; never HTML or script). Here is the tutorial's, which draws the largest desks by notional:

```yaml
rachana: 1
python: notional-by-desk
title: Notional by desk chart
interface: visual
output: echarts
inputs: [desk, notional]
packages: [pandas]
target: both
params: { top: 8 }
source: |
  def render(table, params):
      top = int(params.get("top", 8))
      g = table.groupby("desk", as_index=False)["notional"].sum().sort_values("notional", ascending=False).head(top)
      return {
          "title": {"text": f"Notional of the {len(g)} largest desks", "left": "center"},
          "xAxis": {"type": "value", "name": "Notional"},
          "yAxis": {"type": "category", "data": list(g["desk"])[::-1]},
          "series": [{"type": "bar", "data": [float(v) for v in g["notional"]][::-1]}],
      }
```

A Python item is a BI item like a dataset or a report: stored, versioned, reviewed and published. Create it in your workspace. **In the UI** that is a form, and you do not start from a blank page: **My workspace → New item**, kind *python*, and **What does it make?** asks for *a measure* (one number per group), *a visual* (a chart drawn by Python) or *a table* (a Transform, which joins datasets), and for the columns it reads (or, for a table, the datasets it reads). **Create and open the editor** makes the item with a valid, commented starter body (the manifest the server checks, a function of the right shape, and for a measure a test) and opens it with its editor ready, so you change the code and press **Check the Python**.

![New item, kind python: it makes a measure, reads the column notional, titled Mean notional](img/t14/23-python-starter.jpg)

![The starter body in the item's editor: params, a test of the mean of three values, and def compute(table, params)](img/t14/24-python-starter-editor.jpg)

For this step choose *a visual* and replace the body with the tutorial's, or, **from the file**, paste the YAML from `data/notional-by-desk.python.yaml` into the same form's item page (**Edit**), the way it was done before the starter existed: kind *python*, title `Notional by desk chart`, **Create item**. (The picture below moved it into `Economics desk`.)

![The Python item's page: draft, with Check the Python, Edit, Propose for review, Publish](img/t14/02-python-item.jpg)

You should see the item as a draft with its actions. Press **Check the Python**: the console checks what only it can, that the source parses, defines its entry (`render`) and imports only what the manifest lists or the safe standard library.

![The check passes: "The Python item checks out: the manifest is valid and the source parses."](img/t14/03-check-python.jpg)

You should see `The Python item checks out: the manifest is valid and the source parses.` A mistake is reported with its code. A package that is not allowed, for example `packages: [requests]`, is refused (`DRS-9070`) with the allow-list:

```text
$ POST /bi/python/check   (packages: [requests])
{"valid": false, "problems": [
  {"level": "error", "code": "DRS-9070", "path": "packages", "message": "'requests' is not an allowed package (allowed: pandas, numpy, pyarrow, scipy, statsmodels)"},
  {"level": "error", "code": "DRS-9070", "path": "packages", "message": "'requests' is not on the console's allow-list (bi.python.packages: pandas, numpy, pyarrow, scipy, statsmodels)"}, ...
```

Press **Publish** (a Python item must be published for a report to use it). **From the command line** the same two moves (`bi item new` reads the
kind from the file name; the ids differ on your machine):

```text
$ python3 tools/drishti.py bi item new economics-desk docs/tutorials/data/notional-by-desk.python.yaml
bi-9f1278b922  python  Notional by desk chart  draft in Economics desk
$ python3 tools/drishti.py bi catalogue publish bi-9f1278b922 --note "my first Python visual"
published Notional by desk chart as version 1
```

Now the report, [data/desk-python.report.yaml](data/desk-python.report.yaml): it reads the model (`model: trades-basic@1`), a slicer, a `python` visual
that names your item, and one that uses the shipped measure.

```yaml
- id: notional-chart
  type: python
  title: "Notional by desk (my Python visual)"
  at: { x: 0, y: 2, w: 6, h: 6 }
  wells: { columns: [trade.desk, trade.notional] }       # the rows handed to Python
  format: { python: Notional by desk chart, params: { top: 8 }, run: auto }
- id: volatility
  type: python
  wells: { values: [Desk P&L volatility] }                # a Python measure of the model
  format: { run: auto }
```

Create it (New item, kind *report*, or `python3 tools/drishti.py bi item new economics-desk docs/tutorials/data/desk-python.report.yaml`) and press **Open the report**.

![Desks in Python: a currency slicer, my Python bar chart of the eight largest desks, and the shipped volatility measure](img/t14/04-desks-in-python.jpg)

You should see your chart, `Notional of the 8 largest desks`, drawn by your function, beside the shipped one; the footer of yours reads `Python · bi-... v1 · server · 320 ms · 750 rows`. Choose a currency in the slicer: the chart is asked again and Python gets only those rows (the rows are the table's own, under the page's slicers, filters and business date, at most 10,000). `format.run` is `auto` (the server first, the browser if the server cannot), `server` or `browser`.

**The sandbox.** The server target runs a short-lived worker process per call: **no network**, a scratch folder only, limits on CPU, memory, time and output, and **nothing kept between calls**, so nothing leaks between people. `import` works for the allow-listed packages. Python never reaches the server's data directly; it receives the Arrow rows the server built for the person.

## Step 3 · Analyse: an extract to explore

Choose **Analyse** in the BI rail. In **Model or dataset** choose **Trades, the basics (model 1.0.0, published)** (the list holds the models first, then your
datasets and the packs' datasets, one group per pack), tick the fields you want and press **Open the extract**.

![The Analyse page: the Extract card with the model Trades, the basics and its fields ticked, and the Open the extract button](img/t14/05-analyse-start.jpg)

You should see the fields grouped by the model's alias (`trade`) and, after **Open the extract**, the line `750 rows, 10 columns.` The extract is **one
model query** (its rows, `shape: rows`) asked as **you** (masked columns arrive empty, and the line says `Masked for you: ...`), cut to
`bi.explore.max_rows` (10,000; a cut says so). It is rebuilt on every run and never kept, so a mask or row rule changed on the server applies the next
time. Because it can be cut, Slice gives the **extract's** totals; for true totals use a SQL cell on the server (step 5) or a report. **On the command
line** the same rows are a model query, and **the YAML** of it is what Analyse sends:

```yaml
mq: 1
model: trades-basic@1
select: { fields: [trade.tradeId, trade.desk, trade.book, trade.currency, trade.notional, trade.mtm], measures: [] }
shape: rows                 # the rows themselves, not grouped
limit: 10000
```

(`python3 tools/drishti.py bi mq that.yaml` prints them.)

## Step 4 · Slice

On the **Slice** tab each field is a chip with four small buttons: `R` rows, `C` columns, `V` values, `F` filters. Press `R` on `desk`, `C` on `currency` and `V` on `notional`.

![Slice: desk down, currency across, notional summed; a pivot of the 750 trades](img/t14/06-slice.jpg)

You should see the pivot redraw at once: ten desks down, seven currencies across, notional summed in the cells (`750 of 750 rows used; 10 pivot rows`). A value takes `sum`, `avg`, `count`, `min` or `max`; a filter keeps rows equal to chosen values. It is plain JavaScript over the extract the page holds.

## Step 5 · SQL

Open the **SQL** tab. A SQL cell is **one read-only SELECT**. Its engine (the select beside **Run**) is **Server: Drishti SQL over the model** by default:
the cell is asked of the **server**, over the model the extract reads, so its answers are true totals, not the extract's. Its tables are the model's
aliases and a measure is `MEASURE('name')` ([SQL.md](../guides/SQL.md)):

```sql
SELECT trade.desk, MEASURE('Trades') AS trades, MEASURE('Notional') AS notional
FROM trade
GROUP BY trade.desk
ORDER BY notional DESC
```

Press **Run**.

![The SQL cell in Drishti SQL on the server: desks with trade counts and notional, largest first, and the line naming the model, the plan and as you](img/t14/07-sql.jpg)

You should see a table, the largest desk first (`DESK-RATES`, `156` trades, `20,281,000,000` of notional), and the line *10 rows in 7 ms · Drishti SQL,
run by the server over trades-basic@1 as you · plan p_...*: the same plan a visual of the same question would run. **From the command line** the same
statement (and what a write is told):

```text
$ python3 tools/drishti.py bi sql "SELECT trade.desk, MEASURE('Trades') AS trades, MEASURE('Notional') AS notional FROM trade GROUP BY trade.desk ORDER BY notional DESC LIMIT 3" --model trades-basic@1
trade.desk  trades  notional
----------  ------  -----------------
DESK-RATES  156     20,281,000,000.00
DESK-EQD    132     15,806,000,000.00
DESK-COMM   90      11,359,000,000.00
3 row(s)  shape mq  plan p_ea2aec6d2bb26c60

$ python3 tools/drishti.py bi sql "DROP TABLE trade" --model trades-basic@1
drishti: the server said 422 DRS-9170: DRS-9170 Drishti SQL is read-only: DROP is refused; a statement is one SELECT (or WITH ... SELECT)
```

The other engine, **Over the extract** (selectable until wave B), reads the table `extract` (the rows the page holds) in an in-memory SQLite on the
console, **read-only by construction**; a statement that reads `extract` (an older notebook's) runs there whatever the choice. Asked of the extract, the
same refusals are codes, not damage:

```text
POST /bi/explore/sql   {"sql": "DROP TABLE extract"}
403 {"code":"DRS-9073","detail":"only a single SELECT over the table extract is allowed"}
POST /bi/explore/sql   {"sql": "SELECT 1; SELECT 2"}
422 {"code":"DRS-9070","detail":"only one statement is allowed"}
```

(Text over 20,000 characters or a query over 5 seconds is `DRS-9072`; at most 5,000 rows come back.) There is no YAML for a cell on its own: it is kept
in a notebook (step 7).

## Step 6 · A Python cell

Open the **Python** tab. A cell sees three names: `df` (the extract as a pandas table), `params` and `quant` (a pricing helper). What it prints comes back as text; what it puts in `result` is **drawn**: a table as a table, a dict as an ECharts option, SVG or PNG as an image.

```python
g = df.groupby('desk', as_index=False)['notional'].sum()
result = {'title': {'text': 'Notional by desk'}, 'xAxis': {'type': 'category', 'data': list(g['desk'])}, 'yAxis': {'type': 'value'},
          'series': [{'type': 'bar', 'data': [float(v) for v in g['notional']]}]}
```

Press **Run** (the selector beside it chooses *Server (sandbox)* or *Browser (Pyodide)*).

![A Python cell and its chart: Notional by desk, ten bars; the line says Python, server, 262 ms, 750 rows](img/t14/08-python-cell.jpg)

You should see `Python · server · 262 ms · 750 rows` and the chart. The same worker as a Python visual runs it, with the same sandbox. A cell that only prints and returns a table, run on the same extract:

```text
POST /bi/explore/python   {"source": "print(len(df))\nresult = df.groupby('desk')['notional'].sum().head(3)", "target": "server"}
200 {"kind":"cell","stdout":"750\n","table":{"columns":["notional"],"rows":[[11359000000.0],[8598000000.0],[15806000000.0]],"rowCount":3},"ms":257,"inputRows":750,"masked":[],"target":"server","truncatedInput":false}
```

You should see `750` printed and a three-row table. Running Python needs `bi-author`; a person with only `bi` sees the Slice and SQL tabs work and the Python tab explain why it will not run. (Python cells have no command of their own: the call above is the console's, with your session.)

## Step 7 · Keep it as a notebook, promote it

Under **Notebook** give it a title (`Desk notional, first look`) and press **Save as a notebook**. (**My workspace → New item → notebook** only points you here: a notebook is made from the work you did, not from a form.)

![The notebook card after saving: Save as a notebook and Open the item; the Python chart above](img/t14/09-saved.jpg)

You should see a link, **Open the item**: the extract's definition, the slice and every cell are now a BI item of kind **notebook** in your workspace, with versions, diffs, review and publishing like any other. Opening it again rebuilds the extract **for whoever opens it**: a notebook shared with a colleague shows *their* rows. Outputs are not stored, only the code. **The YAML** of the notebook (the item page shows it; `bi catalogue get <id> --body` prints it):

```yaml
rachana: 1
notebook: desk-notional-first-look
title: Desk notional, first look
dataset: mq:trades-basic@1            # the model the extract reads
fields: [trade.tradeId, trade.desk, trade.book, trade.productType, trade.currency, trade.status, trade.notional, trade.mtm, trade.pnl1d, trade.mtmInMillions]
cells:
- type: slice
  slice:
    rows: [desk]
    columns: [currency]
    values:
    - {field: notional, agg: sum}
    filters: []
- type: sql
  source: |
    SELECT trade.desk, MEASURE('Trades') AS trades, MEASURE('Notional') AS notional
    FROM trade
    GROUP BY trade.desk
    ORDER BY notional DESC
- type: python
  source: |
    g = df.groupby('desk', as_index=False)['notional'].sum()
    result = {'title': {'text': 'Notional by desk'}, ...}
  target: server
```

(The SQL cell keeps no engine: a cell over a model runs on the server, and one that reads the table `extract` runs over the extract.)

To turn a cell into a reusable item press **Promote to a visual** (or **to a measure**) under the cell.

![After promoting: "Python visual draft saved in your workspace: Analyse cell (visual). The cell's df became the function's table..."](img/t14/10-promote-visual.jpg)

You should see `Python visual draft saved in your workspace: Analyse cell (visual).` and the reminder: the cell's `df` became the function's `table`, `result` is returned, a measure must return one number and a visual an ECharts option, SVG or PNG; check it with **Check the Python** on the item page, then publish. Nothing is published for you. And to turn a **slice** into a report press **Promote to a report draft**.

![After promoting a slice: "Report draft saved in your workspace: Slice of Trades, the basics." beside the button](img/t14/11-promote-slice.jpg)

You should see, beside the button, `Report draft saved in your workspace: Slice of Trades, the basics.` and **Open the draft**. The draft is a one-page
report on the same model (`model: trades-basic@1`) with a **matrix** (rows, the column field, and the model's measure that is exactly the slice's
aggregate: `sum(notional)` is `Notional`), which you open in the designer to finish ([tutorial 10](10-first-bi-report.md)).

## Step 8 · Two models: a Python Transform and a join in Analyse

Steps 1 to 7 used Python on one model. Python can also **join answers**. The sample **Desk P&L vs counterparty exposure (Python)** makes one table, P&L
next to exposure per counterparty, with a **Python Transform**: a Python item with `interface: transform` whose function `run(pnl, exposure, params)`
receives two answers as pandas frames, **asked as you**, and returns a table. Since 2.2 that table is a **Python dataset** (`source.python`), and each
input is a **model query**. Open **Models**, find **Desk P&L vs counterparty exposure (Python): the Transform's table** under the datasets.

![The Python dataset's page: made by the Python Transform pnl-vs-exposure, keyed by counterparty, snapshot, and the nine columns its Transform returns](img/t14/12-python-dataset.jpg)

You should see *Source: the Python Transform pnl-vs-exposure*, the key `counterparty` and the nine columns the Transform returns (a contract: a column it
does not return is `DRS-9075`). There is nothing to refresh: the Transform runs **when the dataset is asked**, for the person asking, and its frame is kept
for them until an input changes. **The YAML** says what it reads (`config/bi/datasets/desk-pnl-vs-exposure-py-result.dataset.yaml`):

```yaml
rachana: 2
expressions: 2
dataset: desk-pnl-vs-exposure-py-result
version: 1.0.0
source:
  python: pnl-vs-exposure
  inputs:                       # each a model query, asked as the reader (their row rules and masks)
    pnl: { model: trading-risk@1, select: { fields: [counterparties.id, counterparties.name], measures: [P&L 1d, Trades] } }
    exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id, counterparties.sector], measures: [Exposure (EE peak), Exposure (PFE peak)] } }
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
result.counterparty  result.name                   result.sector     Desk P&L    Exposure (EE peak)  P&L per exposure
-------------------  ----------------------------  ----------------  ----------  ------------------  ----------------
CP-ALDERSHOT         Aldershot Pension Trust       Pensions          946,326.00  98,882,895.00       0.01
CP-AURORA            Aurora Sovereign Wealth Fund  Government        125,106.00  67,990,537.00       0.00
CP-BRAMBLE           Bramble Asset Management      Asset management  219,599.00  75,741,467.00       0.00
3 row(s)  as of 2026-10-13  plan p_32de77bdd356d928
```

The item is `config/bi/python/pnl-vs-exposure.python.yaml`; the reference is [PYTHON_BI.md section 13](../guides/PYTHON_BI.md#13-transforms-a-table-in-a-table-out).

How would you write such a Transform? In **Analyse**, with **two extracts**. Open the notebook **Desk P&L vs counterparty exposure, in pandas** (the Notebook box in the Extract card).

![Analyse with the sample notebook: the first extract is the trades of the model trading-risk (750 rows, 3 columns); under More extracts, the second is called exposure and reads the same model's netting sets](img/t14/13-analyse-extracts.jpg)

You should see `750 rows, 3 columns.` for the first extract (the trades of the model **Trading and counterparty risk**) and, in **More extracts: join
several datasets**, a second one called `exposure` (`mq:trading-risk@1`, 3 fields: the netting sets of the same model). Each is built for you by the
server, with your row rules and masks. Open the **Python** tab and **Run** its cell.

![The join cell: df and extract("exposure") grouped and merged; the result is a table of counterparties with their P&L, exposure and P&L per exposure, largest exposure first](img/t14/14-analyse-join.jpg)

You should see the cell (`df` is the trades; `extract("exposure")` is the netting sets) and a table of counterparties: `CP-HALCYON` with P&L `-1194728`
and exposure `133327214`, then `CP-MERIDIAN` and the rest. The same join works in a SQL cell over the extract: the second extract is a table called
`exposure` (`SELECT ... FROM extract JOIN exposure ON ...`); on the server, one model already joins its facts through the counterparty (Drishti SQL
`FROM trades JOIN counterparties JOIN netting_sets`). When the pandas join is what you want to keep, press **Promote to a Transform**.

![After promoting: "Python transform draft saved in your workspace: Join of P&L and exposure (transform). Each extract("name") became a parameter of run()..."](img/t14/15-promote-transform.jpg)

You should see, beside the button, that a Python Transform draft was saved in your workspace: each `extract("name")` became a parameter of `run(...)`, the
cell's `result` is returned, and the next step is *a dataset of its table: source: { python: <this item>, inputs: { <name>: <a model query> } }, one
input per parameter of run(), in this order*. Open the draft, **Check the Python**, publish it, and make that dataset. Nothing is published by promoting.

**In the UI** the Python dataset is the dataset wizard's source **A Python Transform** ([DATASETS.md section 15](../guides/DATASETS.md#15-the-dataset-wizard-version-2-wave-a)):
**Models → New dataset**, choose the Transform by its title, give each input of `run()` its model query (a model, the fields and the measures to hand
over), and list the columns the Transform returns with their types; **Preview** runs it for you before you save. **From the YAML** it is the
`source: { python: ..., inputs: { ... } }` above; **Make it a model** on its page puts a model over it, where its measures go.

## Step 9 · Vega-Lite, and tests that ship with the item

A chart does not have to be an ECharts option. A **Vega-Lite** specification can be written in the report itself (a `vega-lite` visual: its data is the rows of its wells) or returned by a Python visual (`output: vega-lite`). Open the sample report **Desk P&L risk (Python)**, its **Vega-Lite** tab.

![A vega-lite visual: net MTM by desk, red where it is negative. The specification is in the report's YAML; the data is the visual's own rows](img/t14/16-vega-lite.jpg)

You should see bars for the desks, red for the negative ones: the colour is the specification's `condition: { test: "datum.MTM < 0" }`, evaluated by the page **without `eval`**, so the page keeps its strict content security policy. On the first tab, the bottom visual is a Python visual that returns a specification: a dot for each trade's one-day P&L by desk, a tick for each median.

![A Python visual that returns Vega-Lite: dots of one-day P&L for each desk and each desk's median as a tick](img/t14/17-python-vega.jpg)

A Python item can also carry **tests**. Open the item **Desk P&L volatility** (the Python measure), and press **Check the Python**.

![Check on the Python measure: the manifest and source checks, then its three tests, each run in the sandbox](img/t14/18-python-tests.jpg)

You should see `Tests: 3 of 3 passed.` and a line for each (`passed · the standard deviation of one desk · 1.2909944487358056`). The sample is shipped (read-only); in a copy of it in your own workspace, change a number in an `expect:` and check again: that test says `FAILED` with what it expected and got, and **publishing** through the console is refused (`DRS-9076`) until it passes. [PYTHON_BI.md](../guides/PYTHON_BI.md) sections 14 and 15 have the rules.

## Step 10 · Where a SQL cell runs, and promote into a report you already have

A SQL cell's select says **where** it runs: **Server: Drishti SQL over the model** (the default; the answer is the model's, with true totals, and the
cell is the same plan a report visual runs) or **Over the extract** (the rows the page holds; selectable until wave B, then the server is the only
engine: the browser computes nothing). When the console has DuckDB-Wasm installed (`tools/fetch-duckdb-wasm.sh`), *Over the extract* and **Slice** run in
the browser with it; without it, on the console. Above the tabs, **Promote a slice or a SQL cell to** a *new report draft* or to one of your report
drafts. Choose one, write a SQL cell and **Run** it:

![A SQL cell on the server engine: MTM by desk for the USD trades, the engine select, and the line naming the model and the plan](img/t14/19-analyse-engines.jpg)

You should see the engine *Server: Drishti SQL over the model*, ten desks (`DESK-RATES` `103040009` first, for the USD trades) and the line naming
`trades-basic@1`. Press **Promote to a report visual**:

![A SQL cell promoted into the report draft "Desk views": the note says what a report cannot say (the ORDER BY) and that the report was written again](img/t14/20-promote-existing.jpg)

You should see, beside the button, `Visual sql added to the report draft: Desk views.` and notes: the `ORDER BY` was left out (a visual sorts in its own
settings) and the report was written again by the designer's writer. Open the draft in the designer: a new `matrix` (rows `trade.desk`, values `MTM`, the
filter `trade.currency eq USD`) below what was there. The reader understands Drishti SQL (aliases and `MEASURE('...')`) and SQL over the extract
(columns and plain aggregates, matched to the model's measures). **The YAML** of the visual it added:

```yaml
      - id: sql
        type: matrix
        title: Desk views
        at: { x: 0, y: 8, w: 12, h: 6 }
        wells: { rows: [trade.desk], values: [MTM] }
        filters: [{ field: trade.currency, op: eq, values: [USD] }]
```

A slice or SQL cell of **another** model is not added to an existing report (a report reads one model: `DRS-9070` names both); promote it to a new draft.
[ANALYSE.md](../guides/ANALYSE.md) sections 13 and 14 have the details.

## Step 11 · Calc: the BI engine as a DataFrame, and Graphic Walker; a Python measure in an alert

In **Calc** (`Alt+C` on any view) the module `drishti_bi` asks the BI engine, **as you**: `query` sends one model query (a model's `name@major`; a 2.1
dataset's name reads the model the converter made of it, `trades-basic` is `trades-basic@1`):

```python
import drishti_bi
df = await drishti_bi.query_async('trades-basic@1', measures=['MTM', 'Trades'], by=['trade.desk'])
show(df, 'MTM by desk')
```

![Calc: drishti_bi.query on the model trades-basic@1 in the editor, and the DataFrame it returned, shown as a table](img/t14/21-calc-bi.jpg)

You should see ten desks with their MTM and trade counts: the same numbers a report shows, with the same masks (the command line's `bi mq --model
trades-basic@1 --select trade.desk --measures MTM,Trades` prints the same). `drishti_bi.walk(df)` explores a frame with **Graphic Walker** (PygWalker's
front end, vendored):

![Graphic Walker over the frame: fields on the left, shelves for the axes, colour and size, and the chart builder](img/t14/22-calc-walker.jpg)

Drag `trade.desk` to the X axis and `MTM` to the Y axis. The chart is drawn in a **sandboxed frame** that cannot reach the network or the console: the rows went in by message and nothing goes out ([PYTHON_CALC.md](../guides/PYTHON_CALC.md#19-the-bi-client-and-graphic-walker)).

Last, a **Python measure in an alert**. An alert on a measure of a model asks a model query, and a model query does not run Python measures (they are
drawn by a python visual), so **Alerts → New → Watch a measure of a model** on **Desk P&L volatility** is refused with the reason, captured:

```text
$ curl -s -X PUT -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' \
    -d '{"source":{"type":"model","model":"trades-basic@1","query":{"select":{"fields":["trade.desk"],"measures":["Desk P&L volatility"]}},"reduce":"max"},
         "condition":{"type":"threshold","op":"above","value":100000},"trigger":{"mode":"after-load"}}' \
    "$DRISHTI_SERVER/api/v1/me/measure-alerts/Desk%20volatility%20ceiling"
422 {"code": "DRS-9062", "detail": "you cannot use this source yourself, so it cannot be watched: DRS-9162 'Desk P&L volatility' is a Python measure (desk-pnl-volatility): a python visual runs it; a grouped query cannot"}
```

Watch a measure the model computes instead (an expression, step 1), or a visual of a report.

## Next steps

- [Tutorial 15](15-ai-in-drishti.md): ask the data in words.
- [PYTHON_BI.md](../guides/PYTHON_BI.md): the manifest, where Python runs, the sandbox in full, `DRS-9070` to `9075`, and section 13 for the Transform. [ANALYSE.md](../guides/ANALYSE.md): the page, limits and notebooks. [PYTHON_CALC.md](../guides/PYTHON_CALC.md) is Calc, the Python panel inside a screen.

## If something goes wrong

| You see | Meaning |
|---|---|
| `DRS-9001 no item 'xyz'` in a Python visual | the item is not published, or the name does not match its id, file name or title |
| `DRS-9070` | the manifest or a cell is wrong; the message names the key (a package not on the allow-list, a second SQL statement, a source that does not parse) |
| `DRS-9072` | the SQL ran too long or was too long (`bi.explore.sql_max_seconds`, `sql_max_chars`) |
| `DRS-9073` | the SQL over the extract tried to do more than read the table `extract` |
| `DRS-9170` | Drishti SQL on the server was asked to do more than one SELECT (a write, a second statement) |
| `DRS-9162` | a model query was asked for a Python measure: a python visual runs it |
| `DRS-9074` | the runner is busy or not installed (`bi.python.workers`, the pandas environment), or a browser run needs Pyodide (`tools/fetch-pyodide.sh`) |
| the Python tab will not run | you lack the `bi-author` power |
| a column is empty in the extract | it is masked for your role; the line under the form names it |
