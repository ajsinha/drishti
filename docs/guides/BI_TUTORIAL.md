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
# Tutorial: learn BI from the sample datasets

Three sample datasets ship with Drishti, built on the trades, so you can learn what a **dataset** is by reading and querying real ones. This walk-through opens
each, reads its model, runs preview queries, changes a measure in a copy of your own, and shows row rules and masks with two users. Every output below was
captured from a real server over a generated Delta lake (750 trades a day, three business days: well under the 10,000-a-day local limit). Reference:
[DATASETS.md](DATASETS.md); the page itself: [BI_GUIDE.md](BI_GUIDE.md).

| Sample | What it teaches | Tables |
|---|---|---|
| `trades-basic` | the "hello world": one table, four measures, a hierarchy | `trade` |
| `trading-risk` | the real one: three packs, relationships from the packs' links, a rollup, a row rule, masks | `trades`, `netting_sets`, `counterparties`, `desks`, `books` |
| `trading-cashflows` | nested documents: legs and cashflows as tables, one-to-many | `trades`, `legs`, `cashflows` |

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

## 10. What to look at next

The engine's counters and limits (`GET /api/v1/bi/engine`, BI administrators): in this walk-through 17 queries, 1 cache hit, 9 layer builds, answered `columns` 12 and `rollup` 1. The designer, reports and the Model diagram are phase 3
([RUPAKA.md](../architecture/RUPAKA.md) section 20). What is not built yet is listed in [DATASETS.md section 10](DATASETS.md#10-what-is-not-built).
