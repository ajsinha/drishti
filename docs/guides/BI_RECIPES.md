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
# BI recipes: datasets, row rules and the Model view

The tutorials teach BI in order ([tutorial 10](../tutorials/10-first-bi-report.md) to [14](../tutorials/14-python-and-analyse.md)). This page keeps the recipes they do not repeat: setting up a stack with four people
so row rules and masks can be seen at work, reading and querying the stored datasets, changing a measure in a copy, seeing a wrong dataset refused, starting a model from the packs, and drawing a relationship in
the Model view. Everything else that used to be in the BI tutorial is now a tutorial: reports and the designer are [tutorial 10](../tutorials/10-first-bi-report.md), subscriptions, alerts and export
[tutorial 11](../tutorials/11-bi-that-runs-itself.md), live BI and boards [tutorial 12](../tutorials/12-live-bi-and-boards.md), governance [tutorial 13](../tutorials/13-govern-bi.md), Python and Analyse
[tutorial 14](../tutorials/14-python-and-analyse.md). The references are [DATASETS.md](DATASETS.md), [REPORTS.md](REPORTS.md), [BOARDS.md](BOARDS.md) and [BI_GOVERNANCE.md](BI_GOVERNANCE.md).

Every output below was captured from a real server (the pictures and numbers by `drishti-console/tests/bi_data_shots.py` and `bi_report_shots.py`) over a generated Delta lake (750 trades a day, three business days).

| Sample dataset | What it teaches | Tables |
|---|---|---|
| `trades-basic` | the "hello world": one table, four measures, a hierarchy | `trade` |
| `trading-risk` | three packs, relationships from the packs' links, a rollup, a row rule, masks | `trades`, `netting_sets`, `counterparties`, `desks`, `books` |
| `trading-cashflows` | nested documents: legs and cashflows as tables, one-to-many | `trades`, `legs`, `cashflows` |

## Set up

From the repository root, after `./mvnw -q package -DskipTests` (the server jar) and `pip install -r drishti-console/requirements.txt` (or a venv, [GETTING_STARTED.md](GETTING_STARTED.md)):

```
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta --days 3
export DRISHTI_PACKS=trading,counterparty-risk DRISHTI_SECURITY_ENABLED=true DRISHTI_TOKEN_SECRET=change-me-to-a-long-random-string-0123456789
java -jar drishti-server/target/drishti-server-*-exec.jar
```

and, in a second terminal, the console with sign-in on (the same token secret, and a session secret):

```
export DRISHTI_AUTH_ENABLED=true DRISHTI_TOKEN_SECRET=change-me-to-a-long-random-string-0123456789 DRISHTI_SESSION_SECRET=another-long-random-string-0123456789abcd
python drishti-console/run_drishti_web.py
```

Open http://localhost:17480 and sign in as `drishti-dev-admin` with `drishti-dev-admin123` (change it later; [GETTING_STARTED.md](GETTING_STARTED.md)). The server reads `data/delta` by default, and `config/bi` is the BI folder, so the five datasets are there already. (The numbers in this tutorial were made on other ports and in a scratch folder; yours are the same.)

Now make four people. The role names matter: the dataset's row rules name them.

1. **Admin, Roles, New role**, four times: `bi-writer`, `risk`, `desk-head` (name, tick **Opens every kind** and **See every field unmasked**, the raw power), and `analyst` (**Opens every kind**, *not* raw).
2. **Admin, BI settings**: in the table of roles tick `bi` for `risk`, `desk-head` and `analyst`, and `bi` and `bi-author` for `bi-writer`, pressing **Save** on each row.
3. **Admin, Users, New user**, four times: `wes` with role `bi-writer`; `rita` with `risk`; `hana` with `desk-head` and **Desk** `DESK-FX`; `ann` with `analyst`. Give each the password `long-enough-pass-1`, untick *Must change at first sign-in*, and leave the packs ticked.

So: **wes** writes (`bi`, `bi-author`, raw), **rita** is risk (`bi`, raw), **hana** is a desk head (`bi`, raw, desk `DESK-FX`) and **ann** is an analyst (`bi`, *no* raw). (`drishti-console/tests/bi_data_stack.py` does exactly this by API, which is how the pictures here were made.) Sign in as `wes` throughout, switching to the others where a chapter says so; use a second browser or a private window to be two people at once.

## Open the catalogue and the datasets

Type `BI <GO>` (or `RUPAKA <GO>`) in the command line, or choose **BI** in the top bar, then **Datasets** in the rail. The five samples show as *promoted* and *sample* (the first three are the sections below; the two live ones are in [tutorial 12](../tutorials/12-live-bi-and-boards.md)).
As an author (`wes`) the page also offers **Start a model from the packs** (section "Start a model from the packs").

![the datasets page](img/bi-datasets/datasets-list.png)

Over the API: `GET /api/v1/bi/datasets` lists them with `valid`, `errors`, table, relationship and measure counts.

## `trades-basic`: read the model, run a query

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

## `trading-risk`: relationships from the packs' links

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

## Row rules and masks with two users

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

## `trading-cashflows`: legs and cashflows

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

## Change a measure in a copy

Shipped files are read-only. Copy one and edit the copy, which is private to you until you publish it.

1. In **My workspace** (rail) fill **New item**: kind **dataset**, title *My copy*, **Create item** (or `POST /api/v1/bi/workspaces/me/items`). The item's page opens with an empty body: press **Edit** and paste the YAML of `trades-basic` (from `config/bi/datasets/`) into **Body (YAML)**, changing `dataset: trades-basic` to `dataset: my-copy`.
2. Change a measure, for example add `Big trades: { expr: "sum(trade.notional, trade.notional > 100000000)" }` under `measures:`, write a note in *What changed*, and press **Save draft** (*Draft saved.*).
3. Open the item, then **Open the model and run a query**. The draft is validated as you open it (`POST /api/v1/bi/datasets/validate` does the same with no item). A mistake, say `sum(trade.nope)`, is `DRS-9011` with the path
   `measures.Big trades` and the columns the table does have.
4. Run *Big trades* by `trade.desk`. Another person cannot open your draft (`404 DRS-9001`); publish it to a shared workspace (with review, if the workspace asks) and it appears in the catalogue. Publishing an invalid dataset is refused.

## A dataset that is wrong

```
POST /api/v1/bi/datasets/validate   {"yaml": "... measures:\n  N: { expr: \"sum(t.nope)\" }\n  M: { expr: \"lag(sum(t.a))\" }"}
{"valid": false, "problems": [
  {"level":"error","code":"DRS-9011","path":"measures.N","message":"table 't' has no column 'nope' (it has a)"},
  {"level":"error","code":"DRS-9013","path":"measures.M","message":"'lag' is reserved for window functions and is not built yet (time intelligence ytd, mtd, qtd, prior_period, same_period_last_year and running are built)"}]}
```

## Start a model from the packs

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

## On a phone

The pages work at 390 px: the rail scrolls sideways, the form stacks, tables scroll inside their frame, targets are 44 px.

![phone](img/bi-datasets/dataset-phone-390.png)

## The Model view: relate tables by dragging

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

