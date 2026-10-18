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
# BI with AI: report from words, ask the data, explain this number

Rūpaka stage 2 ([RUPAKA.md](../architecture/RUPAKA.md) section 20.1) puts a language model beside business intelligence, in three places and no more. It is **off by default**, it is the
AI layer's ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md)) and no other, and the model is never trusted with anything a person could not do by hand: **it writes a draft or a query, and
Drishti checks it, runs it as the reader, and shows it.**

| Where | What you do | What the model does | What Drishti does |
|---|---|---|---|
| Designer | **Describe a report** | drafts report YAML over the report's dataset | checks it with the report checker before you see it; puts it in the YAML tab; saves nothing |
| A report page | **Ask the data** (a question box) | maps the question to a dataset query (fields, measures, filters, dates) | the engine runs it as you (row rules, masks), shows a table and the visual that suits it, and the query used |
| A visual's header | **Explain** (the light bulb) | in `aggregates` mode only: words the breakdown | works out the lineage and the breakdown of the number by its main dimensions on the engine |

![Ask the data on the Trades overview report](img/bi-ai/bi-ai-ask.png)

## 1. Turn it on

Two switches: the AI layer, with a provider, and (optionally) the BI settings. Nothing else changes for people who do not use it: the buttons are hidden and every call answers
`404 DRS-4015` while `drishti.ai.enabled` is false.

```yaml
# config/ai/mock.yaml  (a deterministic provider for trying it; use config/ai/openai.yaml.example or anthropic.yaml.example for a real model)
type: mock
models:
  - name: m
```

```
DRISHTI_AI_ENABLED=true  DRISHTI_AI_DEFAULT_MODEL=mock/m
```

| Setting (`drishti.bi.ai.*`) | Default | Meaning |
|---|---|---|
| `model` | blank | `provider/model` the hooks use; blank is `drishti.ai.default-model` |
| `values` | `labels-only` | `labels-only`: the model sees names, types and descriptions, **never a value**. `aggregates`: it may also word the reader's own masked aggregate results (section 5) |
| `max-question-chars` | `600` | longest question or description |
| `max-tokens` | `2000` | longest answer asked of the model |
| `timeout-seconds` | `30` | one model call |
| `ask-rows` | `200` | most rows an answer holds, and the most the model sees of them in `aggregates` mode |
| `explain-dimensions` | `3` | dimensions a number is broken down by |
| `explain-members` | `5` | members listed per dimension |
| `suggestions` | `6` | most visuals the designer copilot proposes at once (section 10) |
| `helper-items` | `8` | most items the dataset helper proposes per kind (section 11) |

The policy settings are the AI layer's (`drishti.ai.policy.*`, [CONFIGURATION.md](../admin/CONFIGURATION.md#ai-and-mcp-policy-settings)). The features are named **`bi-draft`**, **`bi-ask`**
and **`bi-explain`**, and for the helpers of sections 9 to 11 **`subscription-narrative`**, **`bi-suggest`**, **`bi-explain-checks`** and **`bi-helper`**, so `drishti.ai.policy.features."[bi-ask]": {roles: [analyst], per-user-per-minute: 10}` limits who asks and how often.

## 2. Who may

Two gates, both of which must open. The **BI power** decides first (`bi-author` to describe a report, `bi` to ask or explain: [BI_GUIDE.md](BI_GUIDE.md) section 2); then the **AI policy**
(`features.<name>.roles`; masks, per-user and per-feature limits, the cost limit). An author whose role the policy does not list for `bi-draft` is refused `403 DRS-4018`. A person with no
BI power never reaches the model. Every call is audited twice: the policy's access-log row (action `ai`, feature, model, sizes, tokens, outcome, never the text) and a BI audit row
(`bi-ai-draft`, `bi-ai-ask`, `bi-ai-explain`, with the dataset or report and the outcome). Neither holds a prompt or an answer.

## 3. What the model is sent

The prompt is the **model** the report reads (its describe, as the person reads it) and the person's own words, nothing else. For *Ask the data* on the
shipped report **Trades overview** (model `trades-basic@1`) the message is, word for word (rendered from the model as the server reads it):

```text
TASK: ask-query
DATASET trades-basic: The simplest useful model: one table (the trading pack's trade) with four measures (Notional, MTM, Trades and average MTM per trade) and a desk-to-book hierarchy. Start here.
FIELDS (write them as table.column):
  table trade: the dataset trades-basic-trade 1.0.0, a fact
    trade.VALUATION_ID string
    trade.TRADE_ID string
    trade.SOURCE_SYSTEM string
    trade.DESK string
    trade.PORTFOLIO string
    trade.PRODUCT_TYPE string
    trade.CURRENCY string
    trade.TRADE_STATUS string
    trade.NOTIONAL decimal
    trade.MTM decimal
    trade.PNL_1D decimal
    trade.TRADE_DATE date
    trade.MTM_IN_MILLIONS decimal - MTM in millions of USD
MEASURES (write them by name):
  Notional - Total notional of the trades, in the trade's own currency units.
  MTM - Mark-to-market in USD, signed.
  Trades - Number of trades.
  Average MTM per trade - MTM divided by the number of trades (a measure built from measures).
  Desk P&L volatility - Standard deviation of the one-day P&L per desk, computed in Python. Shown by a python visual (docs/guides/PYTHON_BI.md); a query cannot use it.
  Count of trade
HIERARCHIES:
  Desk and book: [trade.DESK, trade.PORTFOLIO]
QUESTION: notional by desk
```

Each table is an alias of the model, described by its dataset and role; each field is `alias.COLUMN type`, with its description when the model gives one.
(Until 2.3.0 a question asked **on a report over a model** sent the model's name and measures but not its fields; it now sends them, as a question asked
of the model by name always did.)

What is **not** in it, in either mode: a row, a value of a field, a measure's definition, a connector or a path, and any field **masked for the reader** (it is left out, so the model cannot be
told it exists). The AI policy then masks `drishti.security.redact` fields and the extra patterns in the prompt as it does for every call. `BiAiTest` records the prompts and checks all of this.

| | `labels-only` (default) | `aggregates` |
|---|---|---|
| Report from words | the model | the model (no change: a draft never involves values) |
| Ask the data | the model maps the question; **the answer is never sent to it** | then a second call words the reader's aggregate result (unmasked columns only, at most `ask-rows` rows) |
| Explain | no model call at all: lineage and breakdown only | one call words the breakdown (totals and members of the main dimensions, as the reader sees them) |

`aggregates` sends **the reader's own figures** to the model's provider. Use it only with a provider you may send such figures to (a model on your own network, or one whose terms allow it);
masked columns and fields hidden by row rules never go, because the engine has already applied them. That is why it is a setting an administrator makes, and not a button.

## 4. Report from words

In the designer, **Describe a report** opens a box: say what you want ("notional by desk as a bar chart and the total as a card"), press **Draft it**. The model is told the report grammar
(the visual types and their wells, the 12-column grid) and the dataset model (for a model of the model layer its `describe` as you read it: each alias
a table with its dataset and version, the fields you may see, the measures not hidden from you), and answers with YAML. `dataset` in the request names
a model (`world-economics@1`, its item id), a dataset of the model layer (`dataset:<name>`) or a version 1 dataset. Drishti then:

1. strips a code fence if the model added one, and pins the draft to what the report you are editing reads: on a **model** (2.2) `rachana: 2` and
   `model: <name>@<major>` in place of any `dataset:`, on a version 1 dataset `dataset:` to it;
2. runs the **report parser and checker** on it (the same as *Check* and Save): unknown fields or measures, bad wells, a bad grid, anything;
3. if it fails, tells the model the problems (names only) and asks once more; if it still fails you get `422 DRS-4020` naming the first problems, and the draft is **not shown**;
4. shows the passing draft as text with *Use this draft*, which puts it in the YAML tab like text you typed. **Nothing is saved or published**: the designer's undo, Check and Save are unchanged,
   and publishing still needs a person and the usual review.

```text
POST /api/v1/bi/ai/draft  {"dataset": "world-economics@1", "request": "GDP by region"}                (as an administrator)   200
{ "yaml": "rachana: 2\nreport: gdp-by-region\nversion: 1\ntitle: GDP by region\nmodel: world-economics@1\npages:\n  - id: main\n ...",
  "valid": true, "problems": [], "dataset": "world-economics@1", "model": "world-economics@1", "saved": false }

POST /api/v1/bi/ai/draft  {"dataset": "trades-basic@1", "request": "notional by desk as a bar chart"}   (as an administrator)   422
{ "status": 422, "code": "DRS-4020", "detail": "DRS-4020 the model's draft did not pass the report check, so it is not shown:
  pages[0].visuals[0].wells.values: no measure 'GDP (USD bn)' in dataset 'trades-basic' (measures: Notional, MTM, Trades, Average MTM per trade,
  Desk P&L volatility, Count of trade); pages[0].visuals[1].wells.axis: 'economies.REGION_NAME' is not a table.column of this dataset (tables: trade); ..." }

POST /api/v1/bi/ai/draft  {"dataset": "trades-basic@1", "request": "x"}                                (as rita, no bi-author)   403
{ "status": 403, "code": "DRS-9002", "detail": "DRS-9002 rita does not hold the bi-author power (an administrator grants it on a role)" }
```

(The second call is the checker at work: tutorial 15's mock answers every draft with the same world-economics report, which names nothing of
`trades-basic@1`; it was told the problems once, answered the same, and the draft was refused, not shown.)

![Describe a report in the designer](img/bi-ai/bi-ai-describe.png)

## 5. Ask the data

On a report page, the question box under the bar maps a question to a **query on the report's dataset**: `fields` to group by, `measures`, `filters`, `dates`, `orderBy`, `limit`: the same
document the engine's visuals use ([DATASETS.md](DATASETS.md) section 8). The model chooses only names from the model it was shown; the **engine** validates every name and binds every value, and
runs the query **as you**: your row rules and your masks apply, so two people asking the same question get the answers they are each entitled to (`BiAiTest` has a desk head and a risk
analyst ask "notional by desk": one row and three rows; and an analyst asking for a masked measure gets it masked).

The page shows the answer as a **table**, the **visual that suits it** (a card for one number, a line over dates, a bar for a few categories; chosen by Drishti from the shape of the query, not
by the model), a note of how many rows the engine answered, and **the query the model chose** (a disclosure) so you can see, and trust or doubt, what was asked. A refusal by the engine
(a field that is not there, a limit) is shown with its code (`DRS-9020`, `DRS-9025`) and "the query the model chose was refused".

```text
POST /api/v1/bi/ai/ask  {"report": "file:report:trades-overview", "question": "notional by desk"}          (as ann, bi-writer)   200
{ "columns": [{"type": "string", "masked": false, "name": "trade.DESK"}, {"type": "decimal", "masked": false, "name": "Notional"}],
  "rows": [["DESK-COMM", 8.888037427736503E11], ["DESK-RATES", 5.96934185E11], ["DESK-EQD", 4.28542341E11], ...], "rowCount": 10,
  "meta": {"layer": "columns", "rows": 10, "cached": false, "dates": ["2026-10-16"], "tables": ["trade"], "masked": [], ...},
  "question": "notional by desk",
  "query": {"fields": ["trade.DESK"], "measures": ["Notional"], "filters": [], "orderBy": [{"desc": true, "by": "Notional"}], "limit": 200},
  "visual": "bar", "values": "labels-only", "summary": null }

POST /api/v1/bi/ai/ask  {"report": "file:report:trades-overview", "question": "something no rule knows"}   (the mock cannot answer)   422
{ "status": 422, "code": "DRS-4020", "detail": "DRS-4020 the model did not answer with a query" }

POST /api/v1/bi/ai/ask  {"report": "file:report:trades-overview", "question": "notional by desk"}          (as rita, risk)   403
{ "status": 403, "code": "DRS-4018", "detail": "DRS-4018 feature 'bi-ask' is not open to your roles" }
```

(Captured on tutorial 15's stack, whose AI policy opens `bi-ask` to `admin` and `bi-writer` only, hence rita's refusal; with no policy entry for the
feature she would be answered, with her own row rules and masks.)

The body names a `report` (the question is asked of the dataset that report reads, with the report's grants) or a `dataset` (a catalogue id). In `aggregates` mode `summary` is the model's
wording of the result; in `labels-only` it is `null`. A question the model cannot turn into a query on this dataset is `DRS-4020`, not a guess.

## 6. Explain this number

The light-bulb button in a visual's header (visuals that show a measure) explains the **first measure** of the visual under the **page's state**: its slicers, selections and date, so the
number explained is the number on the screen. You get:

* **Lineage**, from the dataset model and the packs, no model involved: the dataset, the measure's definition, the columns it reads, and the **sources**: each table's pack kind, connector
  and mode.
* **The breakdown**, computed by the engine as you: the total, then the value by up to `explain-dimensions` dimensions (the visual's own fields first, then the text columns of the tables the
  measure reads), each with its top `explain-members` members and their **share**. A dimension masked for you, or one the engine will not group by, is left out.
* **The narrative** (`aggregates` mode only): three sentences wording that breakdown, from numbers the model was given and told not to add to.

```text
POST /api/v1/bi/ai/explain  {"report": "file:report:trades-overview", "page": "overview", "visual": "notional", "measure": "Notional", "state": {}}   (as rita)   200
{ "measure": "Notional",
  "lineage": { "dataset": {"name": "trades-basic", "title": "Trades, the basics", "version": "1.0.0"}, "model": {"name": "trades-basic", "version": "1.0.0"},
               "measure": {"name": "Notional", "expr": "sum(trade.NOTIONAL)", "description": "Total notional of the trades, in the trade's own currency units."},
               "columns": [{"name": "trade.NOTIONAL", "type": "decimal", "calculated": false, "expr": null}],
               "sources": [{"table": "trade", "dataset": "trades-basic-trade", "version": "1.0.0", "kind": "trade-valuation", "connector": "risk-store", "mode": "cached"}] },
  "value": 3.6170686197736504E12,
  "breakdown": [ {"dimension": "trade.TRADE_ID", "members": [{"member": "1001777", "value": 1.53911817E10, "share": 0.4}, ...]},
                 {"dimension": "trade.SOURCE_SYSTEM", "members": [{"member": "MUREX", "value": 1.56906739E12, "share": 43.4},
                                                                {"member": "CALYPSO", "value": 1.468877391E12, "share": 40.6},
                                                                {"member": "ENDUR", "value": 5.791238387736503E11, "share": 16.0}]},
                 {"dimension": "trade.DESK", "members": [{"member": "DESK-COMM", "value": 8.888037427736503E11, "share": 24.6}, ...]} ],
  "values": "labels-only", "narrative": null }
```

![Explain this number](img/bi-ai/bi-ai-explain.png)

Explain explains **what a number is made of**, not why it moved over time (no comparison between dates is made; that is "not built", section 9).

## 7. Errors

| Code | When |
|---|---|
| `DRS-4015` (404) | the AI layer is off |
| `DRS-4016` (502 / 504) | the provider failed or did not answer in time |
| `DRS-4017` (429) | over a per-user, per-feature or cost limit of the AI policy |
| `DRS-4018` (403) | the AI policy does not open `bi-draft` / `bi-ask` / `bi-explain` / `bi-suggest` / `bi-explain-checks` / `bi-helper` to your role (for a subscription's narrative, `subscription-narrative` to the recipient: the summary is left out and the delivery says so) |
| `DRS-4019` (404) | the configured model is not found |
| **`DRS-4020`** (422) | the model's answer could not be used: a draft that did not pass the report check after one repair, or an answer that is not a query. Nothing was changed |
| `DRS-9002` (403) | no BI power for the hook (`bi-author` to draft, `bi` to ask and explain) |
| `DRS-9020` / `DRS-9021` / `DRS-9025` | the engine refused the query the model chose (an unknown field or measure; a masked field you may not group by; you already have the maximum number of queries running) |
| `DRS-9030` .. `DRS-9035` | the report or visual named in an Ask or Explain is not there or does not check |

## 8. Tests

`BiAiTest` (server, mock provider only, security on): the draft prompt carries the model and **no row, value or measure definition**; a draft is shown only if the checker passes it (a bad one is
`DRS-4020`); an author the policy does not list is refused; two readers asking the same question get different answers; a masked measure stays masked; the ask prompt in `labels-only` has no
row and in `aggregates` only the reader's own unmasked aggregates; explain's lineage, sources and breakdown; the hooks off with the layer. `tests/test_bi_ai.py` (console routes and the script's
hygiene: no `innerHTML`, no inline handler, same origin) and `tests/test_bi_ai_browser.py` (Chromium, real server and console, the mock provider: ask, explain, describe).
Sections 9 to 11 are tested in `BiAiTest` (suggestions are checked and rename taken ids, explanations pair with the checker's problems and no problem means no model call, the helper drops a name that is
not in the model, a relationship inside one table, a measure over an unknown column and a duplicate; the narrative needs `aggregates`), `SubscriptionServiceTest` (a narrative from each recipient's own
render, left out for a masked one, refused at save while the AI layer is off) and `tests/test_wave_d.py` (the routes and the scripts' hygiene); `tests/test_wave_d_browser.py` runs the
copilot and the helper in Chromium.

## 9. A written summary at the top of a subscription's email

A subscription with `narrative: true` (a checkbox on the Subscribe form: *Open the email with a short written summary*) opens each email with two or three sentences that word the report for **that
recipient**. It is the same model call as the others, under the feature name **`subscription-narrative`**, made **as the recipient** (their roles, their per-user limits, `features."[subscription-narrative]".roles`),
and the facts it is given are the recipient's own render of the report: the title, the as-of date and the tables as they see them, **every column masked for them left out**, at most
`drishti.distribution.subscriptions.narrative-rows` (15) rows of each. The prompt asks for three sentences at most and no number that is not in the facts.

It is written only when the AI policy lets the model see values: **`drishti.bi.ai.values: aggregates`**. Under `labels-only` the model never sees a figure, so the email goes without a summary and the delivery
in the run's history says `summary: skipped: the AI policy lets the model see labels only`. It is the same when the AI layer is off, when the policy refuses the feature to the recipient, or when
the provider fails: the email is sent without it (a summary is never a reason to hold a report back) and the history says why. A saved subscription with `narrative: true` is refused while the AI layer is
off (`422 DRS-9061`). The summary is marked as written by an AI model; it is not stored anywhere but in the email, and the audit row (`ai-subscription-narrative`) holds no text.

![Subscribe form with the summary and the channels](img/bi-dist/subscribe-form-channels.png)

## 10. Designer copilot

In the report designer, **Suggest visuals** (beside *Check*; hidden unless the AI layer is on and you have `bi-author`) takes a goal in your words ("where the exposure sits, and a way to filter it by
desk") and the dataset the report reads, and proposes visuals, their wells and filters. The model is sent the dataset's model (names, types and descriptions, never a row) and the titles of what the report
already has; it writes a report in YAML, the report checker runs on it as it does on a hand-written one (one repair attempt, then `DRS-4020`), and each visual in it comes back as a card with its type, its
title, a sentence on why it is useful (the visual's `alt`) and **the YAML that would be added**. A slicer is a *filter*. Nothing is applied: **Add to this page** puts that visual at the bottom of the
page you are on as one undoable edit of the draft (`Ctrl+Z` takes it back), and Save is still yours. An id the report already uses is renamed (`by-desk-2`). `POST /api/v1/bi/ai/suggest`,
feature `bi-suggest`.

**Explain in plain words** is a button in the Check panel. The checker's own problems (codes, paths and messages, nothing else; the dataset's names help the explanation) go to the model, which answers
each with what it means and what to change, using the dataset's names. They are shown under the problems, marked as written by a model, and change nothing. With no problem the model is not called.
`POST /api/v1/bi/ai/explain-checks`, feature `bi-explain-checks`.

![Suggest visuals in the designer](img/bi-ai/designer-suggest.jpg)

![The Check panel explained in plain words](img/bi-ai/check-explained.jpg)

## 11. Dataset helper

In the **model designer** (2.2; and on the Model view of a version 1 dataset), **Ask the AI helper** proposes what a model usually lacks, from its names, types and descriptions alone (a column masked for you is left out; no value, no row):

* **relationships** between columns of the same type in different tables, with a cardinality and a reason;
* **measures** with a name, an expression (one aggregate over a column the model has, or `count(table)`) and a one-sentence description;
* **descriptions** for the columns that have none.

Each item is checked against the model before it is shown, and a name that is not there, a relationship inside one table or one that exists, a measure over an unknown column or with a name taken, and a
description for a column that has one are all dropped. Each is shown with the YAML it would add and an **Accept** button of its own: a relationship is added to the diagram and the `relationships:` block, a
measure to the `measures:` block, a description into the column's line (whichever of the three ways it is written; when the line cannot be found the item says *Place it by hand* and the YAML is shown
to copy). Nothing is saved until you press Save, and the draft is checked as you type like any other edit. `POST /api/v1/bi/ai/dataset-helper`, feature `bi-helper`.
On a model the items are written for the model: a measure under `measures:`, a field's description under `fields:` (`economies.regionName: { description: "..." }`,
kind `field`), a relationship under `relationships:`, each Accept one undoable edit of the designer's draft; the answer names the model (`"model": "my-world-economics@1"`).

![The dataset helper on the Model view](img/bi-ai/dataset-helper.jpg)

## 12. Not built

The narrative, the designer copilot and the dataset helper are built (sections 9 to 11). Not built: "why did it move" between two dates, follow-up questions that remember the last (each question is alone), streaming answers, and a model that calls Drishti's
tools itself (the MCP client exists; no BI hook uses it).
