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
# Tutorial: learn BI from the sample datasets and reports

Three sample datasets ship with Drishti, built on the trades, so you can learn what a **dataset** is by reading and querying real ones, and three sample **reports** on them, so you can learn what a report
is by using them and then **building one from scratch in the designer**. Chapters 0 to 9 open each dataset, read its model, run preview queries, change a measure in a copy of your own, and show row rules and masks with two users;
chapters 10 to 15 open the reports, build one step by step, draw a relationship in the Model view, and use a report on a phone. Every output below was
captured from a real server over a generated Delta lake (750 trades a day, three business days: well under the 10,000-a-day local limit). Reference:
[DATASETS.md](DATASETS.md) and [REPORTS.md](REPORTS.md); the pages themselves: [BI_GUIDE.md](BI_GUIDE.md).

| Sample | What it teaches | Tables |
|---|---|---|
| `trades-basic` | the "hello world": one table, four measures, a hierarchy | `trade` |
| `trading-risk` | the real one: three packs, relationships from the packs' links, a rollup, a row rule, masks | `trades`, `netting_sets`, `counterparties`, `desks`, `books` |
| `trading-cashflows` | nested documents: legs and cashflows as tables, one-to-many | `trades`, `legs`, `cashflows` |

| Sample report | What it teaches | Dataset |
|---|---|---|
| `trades-overview` | cards, a bar that drills from desk to book, a line, a table, a slicer, a matrix; the first report to read | `trades-basic` |
| `counterparty-risk` | a cross-pack report: a top-10 filter, report-wide slicers, drill-through to a page about one counterparty, row rules and masks at work | `trading-risk` |
| `cashflow-ladder` | deep trades: a date hierarchy to drill from year to day, a matrix over legs | `trading-cashflows` |

They are marked `sample: true`: with **Admin, Packs, hide samples** set to developers-only or hidden, business users do not see them (as with sample packs).

## 0. Set up

```
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta --days 3
DRISHTI_PACKS=trading,counterparty-risk DRISHTI_SECURITY_ENABLED=true DRISHTI_TOKEN_SECRET=... java -jar drishti-server/target/drishti-server-*-exec.jar
python drishti-console/run_drishti_web.py
```

Create four people in **Admin, Users** (or let `drishti-console/tests/bi_data_stack.py` do it, as it does for the pictures here): `wes` with a role holding `bi` and `bi-author`;
`rita` with a role named **`risk`** (`bi`, and the **raw** power); `hana` with a role named **`desk-head`** (`bi`, raw) and **desk `DESK-FX`** on her profile; `ann` with a role named
**`analyst`** (`bi`, *no* raw). The role names matter: the dataset's row rules name them. Assign them the `trading` and `counterparty-risk` packs.

## 1. Open the catalogue and the datasets

Type `BI <GO>` (or `RUPAKA <GO>`) and choose **Datasets** in the rail, or open the catalogue and filter by kind **dataset**. The three samples show as *promoted* and *sample*.
As an author (`wes`) the page also offers **Start a model from the packs** (step 8).

![the datasets page](img/bi-datasets/datasets-list.png)

Over the API: `GET /api/v1/bi/datasets` lists them with `valid`, `errors`, table, relationship and measure counts.

## 2. `trades-basic`: read the model, run a query

Open **Trades, the basics**. The page has the model (tables with every column, its document path and type; relationships; measures with their expressions; security; the layers on disk) and a
**query preview**. Read the YAML first: `config/bi/datasets/trades-basic.dataset.yaml` explains each part in comments (table, connector by logical name, `mode: cached`, columns, a calculated
column `mtmInMillions`, four measures including one made of two others, a hierarchy).

![the model page](img/bi-datasets/dataset-basic.png)

Preview: group by `trade.desk`, measures *Notional*, *MTM*, *Trades*, *Average MTM per trade*, **Run the query**.

![the preview grid](img/bi-datasets/preview-basic.png)

The same call over the API (`format=json` is the default; `format=arrow` returns an Arrow IPC stream for Perspective or DuckDB-Wasm):

```
POST /api/v1/bi/datasets/file:dataset:trades-basic/query
{"fields":["trade.desk"],"measures":["Notional","MTM","Trades","Average MTM per trade"]}

DESK-COMM    11,359,000,000   16,954,390    90     188,382.11
DESK-CREDIT   8,598,000,000    7,948,456    72     110,395.22
DESK-EQD     15,806,000,000   -6,433,670   132     -48,739.92
DESK-FI      10,346,000,000   -2,977,676    78     -38,175.33
DESK-FX       8,485,000,000  -15,759,419    84    -187,612.13
...                                                            (10 rows)
meta: layer=columns, 8.8 ms, dates=[2026-09-30], cached=false
```

What happened: the first query **built the columns layer** for the table: the Delta connector scanned the declared columns (no document was parsed) and the engine wrote
`columns/file_dataset_trades-basic/trade/date=2026-09-30/part.parquet`. Run it again and the answer comes from the **result cache** (`cached: true`, in microseconds). The date is the
newest the lake holds (the data here ends on 2026-09-30); give `dates: {"from": "2026-09-29"}` for another day, or a range.

As an author add `?explain=true` to see the SQL the server compiled (see [DATASETS.md section 4](DATASETS.md#4-measures-and-the-expression-language)): names quoted from the model, **no value in the text**.

## 3. `trading-risk`: relationships from the packs' links

Open **Trading and counterparty risk**. Its five tables read three connectors in three packs (`trading-store` of trading, `reference-store` of banking-core, `credit-store` of counterparty-risk). Its five
relationships are the links the packs declare (a trade's `desk`, `book`, `counterparty.id` and `nettingSet`; a netting set's `counterparty`), so the tables are already joined.

![the model page of trading-risk](img/bi-datasets/model-risk.png)

Group by `desks.name` (a column of another pack's table) and ask for *MTM*, *Notional*, *Trades*, *Counterparties* (a distinct count):

```
Bonds and securitised          -2,977,676   10,346,000,000    78   13
Commodities                    16,954,390   11,359,000,000    90   18
Credit trading                  7,948,456    8,598,000,000    72   17
Equity derivatives             -6,433,670   15,806,000,000   132   18
G10 and EM FX                 -15,759,419    8,485,000,000    84   17
...                                                                (10 rows)
```

The join was `trades -> desks` (many to one), so no trade is counted twice. Now **exposure by counterparty**, a measure of the *netting sets* grouped by a column of the counterparties:

```
{"fields":["counterparties.name"],"measures":["Exposure (EE peak)","Netting sets"],"orderBy":[{"by":"Exposure (EE peak)","desc":true}],"limit":5}
Halcyon Shipping plc      133,327,214   3
Meridian Life Assurance   117,599,671   4
Harbor Point Bank         114,560,550   4
Aldershot Pension Trust    98,882,895   4
Meridian Reinsurance Ltd   92,583,568   3
```

The reference kinds here promote no columns, so the server read their documents once (a warning in `meta.warnings` says so); `trades` scanned columns.

**A rollup.** The dataset declares `by_desk_currency`. A query that groups by `trades.desk` and `trades.currency` and asks for rollup measures is answered from it:

```
{"fields":["trades.desk","trades.currency"],"measures":["MTM","Notional","Trades"]}
meta: layer=rollup, rollup=by_desk_currency, 4.6 ms
```

Add `"layer":"columns"` and you get the same numbers from the columns layer. At 750 rows a day the two cost the same (a few milliseconds each, section 11 of
[RUPAKA_POC.md](../architecture/RUPAKA_POC.md) has the numbers); a rollup is 18 times smaller on disk and is for millions of rows.

## 4. Row rules and masks with two users

The dataset says (`security:`): `desk-head` sees `trades.desk in user.desks`; `risk` and `analyst` see everything; `counterpartyName`, `counterparties.name` and `counterparties.rating` are masked
for readers without the raw power; and the server's own redact list masks `counterpartyId`. Run the **same** query, group by `desks.name`, measures *MTM*, *Trades*, *Counterparties*, as three people.

**rita (`risk`, raw)**: all ten desks.

![risk](img/bi-datasets/preview-risk-risk.png)

**hana (`desk-head`, desk `DESK-FX`)**: one row, her desk. She did not filter; the rule did it, on the server, and she cannot remove it.

![desk head](img/bi-datasets/preview-risk-deskhead.png)

```
G10 and EM FX   -15,759,419   84   17
```

**ann (`analyst`, no raw)**: every desk, but *Counterparties* (a distinct count of a masked column) reads null and the badge says why. Group by `counterparties.name` and the names do not appear at all: one row
`•••` with the 750 trades in it, because a masked field is not grouped on, so the 18 names cannot be told apart.

![analyst](img/bi-datasets/preview-risk-analyst.png)

```
ann: {"measures":["Trades"],"filters":[{"field":"counterparties.name","op":"eq","values":["Halcyon Shipping plc"]}]}
403 DRS-9021 'counterparties.name' is masked for you: it cannot be filtered on
```

Last, hana asks for exposure by counterparty. The rule names `trades`, and every query she runs joins it; netting sets do not reach trades many-to-one, so the question is **refused rather than answered
with other desks' data**:

```
422 DRS-9020 the model has no relationship path joining [counterparties, netting_sets, trades] to the measures' table without repeating its rows (each join must go many-to-one);
your row rule reads [trades] and is applied to every query you run, so the table the measures aggregate must reach it many-to-one
```

## 5. `trading-cashflows`: legs and cashflows

Open **Trade legs and cashflows**. A trade document holds arrays; `source: { each: schedule }` makes each element of the payment schedule a row of the table `cashflows`, whose `id` is the trade's id. Cashflow amount by pay month:

```
{"fields":["cashflows.payDate.month"],"measures":["Cashflow amount","Cashflows"]}
 1   138,560,000   300       5   172,620,000   339       9   205,500,000   470
 2   239,020,000   406       6    87,640,000   371      10   125,040,000   293
 3   204,000,000   483       7   149,800,000   315      11   173,500,000   347
 4   116,340,000   288       8   222,360,000   458      12    87,840,000   373
```

![cashflows by month](img/bi-datasets/preview-cashflows.png)

Group cashflows by a column of the trade (`trades.desk`): the join goes cashflows to trades, many to one. Legs and cashflows are siblings under the trade; asking for a leg column with a cashflow measure would repeat each cashflow once per
leg, and the server refuses (`DRS-9020`). Because arrays cannot come from promoted columns, this dataset reads the trade documents once per business date (the warning says so).

## 6. Change a measure in a copy

Shipped files are read-only. Copy one and edit the copy, which is private to you until you publish it.

1. In **My workspace** choose **New item**, kind **dataset**, title *My copy*, and paste the YAML of `trades-basic` into the body (or `POST /api/v1/bi/workspaces/me/items`).
2. Change a measure, for example add `Big trades: { expr: "sum(trade.notional, trade.notional > 100000000)" }`, and **Save draft**.
3. Open the item, then **Open the model and run a query**. The draft is validated as you open it (`POST /api/v1/bi/datasets/validate` does the same with no item). A mistake, say `sum(trade.nope)`, is `DRS-9011` with the path
   `measures.Big trades` and the columns the table does have.
4. Run *Big trades* by `trade.desk`. Another person cannot open your draft (`404 DRS-9001`); publish it to a shared workspace (with review, if the workspace asks) and it appears in the catalogue. Publishing an invalid dataset is refused.

## 7. A dataset that is wrong

```
POST /api/v1/bi/datasets/validate   {"yaml": "... measures:\n  N: { expr: \"sum(t.nope)\" }\n  M: { expr: \"ytd(sum(t.a))\" }"}
{"valid": false, "problems": [
  {"level":"error","code":"DRS-9011","path":"measures.N","message":"table 't' has no column 'nope' (it has a)"},
  {"level":"error","code":"DRS-9013","path":"measures.M","message":"'ytd' is reserved for time intelligence and window functions and is not built yet (RUPAKA.md section 20.2)"}]}
```

## 8. Start a model from the packs

As an author, on the Datasets page choose kinds (here `trade`, `desk`, `counterparty`, `netting-set`) and **Start the model**. Each kind becomes a table (columns from the connector's promoted columns, typed from the data, else from the pack's
sample documents); **the packs' link graph becomes relationships, across packs**; relationships the data suggests (a column whose values are ids of another kind) are *proposed* with the match rate; the YAML is a draft you can save to your workspace.

![start from the packs](img/bi-datasets/start-from-packs.png)

```
relationships read from the packs (all cross-pack):
  trade.counterpartyId -> counterparty      many-to-one
  trade.desk           -> desk              many-to-one
  trade.nettingSet     -> netting_set       many-to-one
  netting_set.counterparty -> counterparty  many-to-one
proposed from the data: none (the packs declare every link between these four)
warnings: desk, counterparty, netting_set: the connector promotes no columns to read here, so the types are guesses from the pack's sample documents
```

Inference matters for kinds with no declared link: for each text column of one table and each other table's ids it reports *"3 of 3 distinct values are ids of region"* (rate, count, cardinality, a few unmatched values) when the rate is at least
`drishti.bi.query.infer-min-match` (0.8). It is profiling, not AI, and nothing is accepted on its own.

## 9. On a phone

The pages work at 390 px: the rail scrolls sideways, the form stacks, tables scroll inside their frame, targets are 44 px.

![phone](img/bi-datasets/dataset-phone-390.png)

## 10. Open the sample reports: Trades overview

The set-up of chapter 0 is all there is (the same people, the same lake); the reports ship in `config/bi/reports` and appear in the catalogue. The pictures and captured output of chapters 10 to 15 were made from a real stack by
`drishti-console/tests/bi_report_shots.py` (ports 18990 and 17990).

Choose **Catalogue**, filter by kind **report**, and open **Trades overview** (or type `BI trades overview <GO>`: a report opens drawn). Its YAML, `config/bi/reports/trades-overview.report.yaml`, is commented line by line; each visual says where it
sits (`at: { x, y, w, h }` on the 12-column grid) and what it shows (`wells`). The page shows the day's 750 trades: 92,558,000,000 notional, +138,562,638 mark to market, an average of +184,750 a trade.

![Trades overview](img/bi-reports/viewer-overview.png)

Under every visual is the line the server gave back: rows, milliseconds (or "from the cache") and the layer that answered. Each visual is **one dataset query, as you**: the page asks for them three at a time. The bar's request is
`POST /api/v1/bi/reports/file:report:trades-overview/query` with `{"page": "overview", "visual": "by-desk", "state": {...}}` and its answer is the dataset engine's, plus the query the server built:

```
"rowCount": 10, "visual": "by-desk",
"query": {"fields": ["trade.desk"], "measures": ["MTM"], "filters": [], "orderBy": ["MTM desc"], "limit": 500}
rows: DESK-RATES 161,766,771 | DESK-COMM 16,954,390 | DESK-STRUCT 15,784,130 | ...
meta: layer columns, 10 rows, answered from the cache the second time
```

**Click a bar.** DESK-COMM was clicked: every other visual now answers for that desk (90 trades, +16,954,390) and the bar dims the others. The request of the card now carries the selection:

```
{"page": "overview", "visual": "mtm", "state": {"carry": [], "slicers": {},
  "selections": [{"visual": "by-desk", "field": "trade.desk", "values": ["DESK-COMM"], "highlight": false}], "drill": []}}
```

![A selected bar filters the page](img/bi-reports/viewer-select.png)

Switch *Click a mark to* **highlight** and a bar or column chart keeps the whole answer and draws the chosen share over it (the cards and the line are filtered, since they cannot draw a highlight).

**Drill down.** With the bar selected, the drill-down button moves the axis from the desk to the book (`hierarchy: Desk and book` in the YAML); the title shows the path and the up button comes back.

![From a desk to its books](img/bi-reports/viewer-drilldown.png)

**Show the rows.** The list button replaces the visual with the rows behind it: every column of the trade table for the selected desk, under the same filters (`"drill": ["DESK-COMM"], "rows": true`).

![The rows behind the bar](img/bi-reports/viewer-rows.png)

Open **By product** for a donut, a **matrix** with subtotals and a stacked column, and the bookmark *FX desk only* in the toolbar. Press `?` for the keys: `Tab` walks the visuals, in a chart the arrows move between marks and `Enter`
selects, `t` shows the visual as a table, `r` shows its rows.

## 11. Counterparty risk: slicers, drill-through, row rules

Open **Counterparty risk** (`trading-risk`: trades from the trading pack, counterparties from banking-core, netting sets from counterparty-risk). The two slicers at the top have `scope: report`: a choice filters every page and the visuals
reach it **through the relationships** (a sector chosen on counterparties filters the trades and the netting sets). The bar is the ten counterparties with the highest expected exposure:
`filters: [{ field: counterparties.id, op: top, n: 10, by: Exposure (EE peak) }]`; the server ranks the counterparties first, in the context of the slicers, and keeps ten.

![Counterparty risk for a risk person](img/bi-reports/viewer-counterparty.png)

Select **Halcyon Shipping plc**'s bar. The visual's buttons now offer **Drill through to Counterparty**, because the page `counterparty` says `drillthrough: { fields: [counterparties.name] }`.

![A selected counterparty](img/bi-reports/viewer-mark.png)

The page opens with the name carried as a chip and in the URL, so the link can be shared, and **Back** returns. Its trades table has a key column: a trade opens in the terminal.

```
/bi/r/file:report:counterparty-risk/counterparty?carry=%5B%7B%22field%22%3A%22counterparties.name%22%2C%22values%22%3A%5B%22Halcyon%20Shipping%20plc%22%5D%7D%5D
```

![The counterparty's own page](img/bi-reports/viewer-drillthrough.png)

**Row rules at work.** Open the same report as `hana` (role `desk-head`, desk `DESK-FX`). The dataset's rule `trades.desk in user.desks` is on the trades and applies to every query she runs. The trades table
shows only her desk. A netting set does not reach the trades many-to-one, so the exposure visuals **cannot be answered under her rule and say so** rather than answer too much:

```
ee: DRS-9020 the model has no relationship path joining [netting_sets, trades] to the measures' table without repeating its rows
    (each join must go many-to-one); your row rule reads [trades] and is applied to every query you run, so the table the measures aggregate must reach it many-to-one
```

![The report for a desk head](img/bi-reports/viewer-counterparty-deskhead.png)

As `ann` (role `analyst`, no raw power) the counterparty name is masked by the dataset: the bar has **one** category, `•••`, and the visual's line says *masked*. A filter on a masked column would be refused
(`DRS-9021`), which is why the top-10 ranks by `counterparties.id`.

![The same report for an analyst](img/bi-reports/viewer-counterparty-analyst.png)

## 12. Cashflow ladder: a hierarchy and a matrix

**Cashflow ladder** (`trading-cashflows`: each trade document holds legs and a payment schedule, both tables of the dataset). The column chart starts at the pay **year** and `hierarchy: Pay date` goes year, quarter, month,
day. Select a year and press drill down: the axis becomes its quarters and the title shows the path. A visual filter, `{ field: cashflows.payDate, op: not-null }`, keeps the cashflows with no date off the ladder.

![A year drilled into its quarters](img/bi-reports/viewer-ladder.png)

The **Legs** page is a matrix: rows are pay or receive, columns the rate type, the values the number of legs and their notional, with subtotals (`format: { subtotals: true }`). A total is the sum of the shown figures, so it is shown for a
count and a sum and as `–` for a measure that does not add up.

![Legs by pay or receive and rate type](img/bi-reports/viewer-matrix.png)

## 13. Build a report from scratch in the designer

As an author (`wes`), choose **Designer** in the rail. Choose the dataset **Trades, the basics**, a title and **Start designing**. A draft report appears in your workspace.

![The designer's start](img/bi-reports/design-start.png)

1. **The empty canvas.** Four panes: the canvas (with the page tabs and the Canvas and YAML views), **Filters**, **Visualizations** and **Fields**. The Fields pane is the dataset: the table `trade` with its columns (a date opens into its
   parts), the four measures, the hierarchy.

   ![The empty designer](img/bi-reports/design-empty.png)

2. **Drag a measure onto the canvas.** *MTM* becomes a **card**, with the real figure from the dataset and the query time under it. A measure alone is a card; a column alone would be a table. Drag *Notional* beside it for a second card.

   ![A card from a measure](img/bi-reports/design-card.png)

3. **Drag a column onto a visual to improve it.** Drag `trade.desk` onto the canvas (a table), then *MTM* onto that table: a category with a measure becomes a **bar**. **Build** shows the wells: `axis` holds `trade.desk`, `values` holds
   `MTM`. Add *Notional* with the select under `values` (no dragging needed). Add `trade.tradeDate` and *Notional* the same way for a **line**.

   ![The bar and its wells](img/bi-reports/design-bar.png)

4. **A slicer.** With nothing selected (click an empty part of the canvas) choose **slicer** in Visualizations, add `trade.currency` to its `field` well, and in Format set the mode to **dropdown**; drag it by its header to the top right. The visual snaps
   to the grid and what it would overlap is pushed down.

   ![A dropdown slicer, in Format](img/bi-reports/design-format.png)

5. **Check.** The button validates the draft and adds the accessibility and layout checks. None of the visuals has a title yet:

   ```
   0 errors, 4 warnings. | A11Y pages[0].visuals[0]: the card has no title and no alt text: a screen reader announces nothing about it || A11Y pages[0].visuals[1]: the card has no title and no alt text: a screen reader announces nothing about it || A11Y pages[0].visuals[2]: the bar has no title and no alt text: a screen reader announces nothing about it || A11Y pages[0].visuals[3]: the line has no title and no alt text: a screen reader announces nothing about it
   ```

   ![Check](img/bi-reports/design-check.png)

   Select each visual and give it a **Title** in Format (*Mark to market*, *Notional*, *MTM and notional by desk*, *Notional by trade date*, *Currency*).

6. **Filter.** Select the bar and, under *On this visual*, **Add a filter**: field `trade.desk`, type **Top N**, 5 by *Notional*. The bar keeps the five largest desks; the other filter types are Basic (tick values, or *Pick from the data*) and Advanced
   (a condition). Tick *Data labels* in Format.

   ![A top-5 filter](img/bi-reports/design-filter.png)

7. **The result.** Two cards, a bar with its labels, a line, a slicer, each with its title and its real data:

   ![The finished canvas](img/bi-reports/design-two-visuals.png)

8. **The YAML.** The tab shows the same draft as text, written as the sample reports are. Edit the text and the canvas follows; a mistake is reported with its code. A strip of suggestions follows the cursor.

   ![The YAML tab](img/bi-reports/design-yaml.png)

   ```yaml
   rachana: 1
   report: desk-overview
   version: 1
   title: Desk overview
   dataset: "file:dataset:trades-basic"
   pages:
     - id: page1
       title: Page 1
       visuals:
         - id: card-1
           type: card
           title: Mark to market
           at: { x: 0, y: 0, w: 3, h: 2 }
           wells: { values: [MTM] }
         - id: card-2
           type: card
           title: Notional
           at: { x: 3, y: 0, w: 3, h: 2 }
           wells: { values: [Notional] }
         - id: bar-1
           type: bar
           title: MTM and notional by desk
           at: { x: 0, y: 2, w: 6, h: 5 }
           wells: { axis: [trade.desk], values: [MTM, Notional] }
           filters:
             - { field: trade.desk, op: top, n: 5, by: Notional, dir: desc }
           format: { labels: true }
         - id: line-1
           type: line
           title: Notional by trade date
           at: { x: 6, y: 2, w: 6, h: 5 }
           wells: { axis: [trade.tradeDate], values: [Notional] }
         - id: slicer-1
           type: slicer
           title: Currency
           at: { x: 9, y: 0, w: 3, h: 2 }
           wells: { field: [trade.currency] }
           format: { mode: dropdown }
   ```

9. **Check again, save, view.** Check now says *Nothing to report: the draft is valid and passes the basic accessibility checks.* **Save** keeps the draft (`Ctrl+S`); **View** draws it as a reader sees it, with the same interactions:

   ![The saved draft in the viewer](img/bi-reports/design-viewer.png)

10. **Publish** from the item page: propose, review and approve if the workspace asks for it, and choose the audience. A report with errors is refused at publishing with `DRS-9030`.

**A mistake, with its code.** The validate endpoint returns every problem at once, with the path. This body has a grid that is too wide, a column that does not exist, a field where a measure belongs, and a visual that is reserved:

```
POST /api/v1/bi/reports/validate   {"yaml": "report: x\ndataset: trades-basic\npages:\n  - id: p\n    visuals:\n      - { id: a, type: bar, at: {x: 0, y: 0, w: 14, h: 4}, wells: { axis: [trade.nope], values: [trade.mtm] } }\n      - { id: b, type: map, at: {x: 0, y: 4, w: 4, h: 4} }\n"}
valid: false
DRS-9030 pages[0].visuals[0].at           x is 0 to 11 and w is 1 to 12, and x + w is at most 12 (the grid has 12 columns); this is x=0, w=14
DRS-9033 pages[0].visuals[1].type         the visual 'map' is reserved and not built yet (RUPAKA.md section 20.3); built: bar, column, line, area, combo, scatter, ...
DRS-9031 pages[0].visuals[0].wells.axis   table 'trade' has no column 'nope' (it has tradeId, desk, book, productType, currency, status, notional, mtm, pnl1d, tradeDate, mtmInMillions)
DRS-9032 pages[0].visuals[0].wells.values 'trade.mtm' is a field; the values well takes measures (Notional, MTM, Trades, Average MTM per trade)
```

## 14. The Model view: relate tables by dragging

On a dataset's page choose **Model view** (authors). Open **Trading and counterparty risk**: five tables as boxes, five relationships as lines with `1` and `*` at the ends (hover a line for its cardinality and direction). A shipped dataset opens
read-only; **Save a copy** makes a draft of your own.

![The cross-pack model](img/bi-reports/model-shipped.png)

**Remove one and draw it back.** In the list on the right remove `trades.book -> books.id`: the line goes, the YAML loses the line, and `books` is related to nothing, so it moves to a band of its own. Now drag the column `book` of `trades` onto
`id` of `books`: the form fills in, with a many-to-one guessed from the keys. Nothing is written until **Add relationship**.

![Dragging trades.book onto books.id](img/bi-reports/model-relate.png)

![Related again; the YAML has the line](img/bi-reports/model-related.png)

```
relationships:
  - { from: trades.desk,    to: desks.id,          cardinality: many-to-one }
  - { from: trades.book,    to: books.id,          cardinality: many-to-one }
  ...
```

**Proposals from the data.** For tables with no declared link, **Look for relationships in the data** finds columns whose values are ids of another table. Here a draft with the trades and the desk P&L (two kinds the packs do not link) gets a
proposal with its match rate; nothing changes until you **Accept**:

![A proposal from the data](img/bi-reports/model-proposals.png)

```
trades.desk → desk_pnl.id  100% of 10 values match (many-to-one)
accepted: { from: trades.desk, to: desk_pnl.id, cardinality: many-to-one }   (written into the YAML; the model checks as valid)
```

Drag a box by its header and its place is kept in the YAML under `layout:`. Only the `relationships:` and `layout:` blocks are rewritten; the rest of the file, comments included, stays as you wrote it.

## 15. A report on a phone

At 390 px a report is one column in reading order, the buttons are 44 px, and nothing scrolls sideways. The slicers, the cross-filter and the drill buttons work by touch; a chart's marks are tapped.

![Trades overview on a phone](img/bi-reports/viewer-phone-390.png)

## 16. What to look at next

The engine's counters and limits (`GET /api/v1/bi/engine`, BI administrators): in this walk-through 17 queries, 1 cache hit, 9 layer builds, answered `columns` 12 and `rollup` 1. Reports, the designer and the Model view are phase 3 ([RUPAKA.md](../architecture/RUPAKA.md) section 20.3: what is built and what is not). Publishing and lineage are phase 4, live visuals phase 5.
What is not built yet for datasets is listed in [DATASETS.md section 10](DATASETS.md#10-what-is-not-built), for reports in [REPORTS.md section 12](REPORTS.md#12-built-and-not-built).
