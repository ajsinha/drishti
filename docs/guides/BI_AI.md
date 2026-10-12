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

The policy settings are the AI layer's (`drishti.ai.policy.*`, [CONFIGURATION.md](../admin/CONFIGURATION.md#ai-and-mcp-policy-settings)). The features are named **`bi-draft`**, **`bi-ask`**
and **`bi-explain`**, so `drishti.ai.policy.features."[bi-ask]": {roles: [analyst], per-user-per-minute: 10}` limits who asks and how often.

## 2. Who may

Two gates, both of which must open. The **BI power** decides first (`bi-author` to describe a report, `bi` to ask or explain: [BI_GUIDE.md](BI_GUIDE.md) section 2); then the **AI policy**
(`features.<name>.roles`; masks, per-user and per-feature limits, the cost limit). An author whose role the policy does not list for `bi-draft` is refused `403 DRS-4018`. A person with no
BI power never reaches the model. Every call is audited twice: the policy's access-log row (action `ai`, feature, model, sizes, tokens, outcome, never the text) and a BI audit row
(`bi-ai-draft`, `bi-ai-ask`, `bi-ai-explain`, with the dataset or report and the outcome). Neither holds a prompt or an answer.

## 3. What the model is sent

The prompt is the **dataset model** and the person's own words, nothing else:

```
DATASET lake-report: Trades of the lake
FIELDS (write them as table.column):
  table trades: 
    trades.id string - the entity id
    trades.desk string - The desk that owns the trade
    trades.notional decimal
MEASURES (write them by name):
  Notional - Sum of the notional
HIERARCHIES:
  Desk and book: [trades.desk, trades.book]
QUESTION: notional by desk
```

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
(the visual types and their wells, the 12-column grid) and the dataset model, and answers with YAML. Drishti then:

1. strips a code fence if the model added one, and pins `dataset:` to the dataset of the report you are editing;
2. runs the **report parser and checker** on it (the same as *Check* and Save): unknown fields or measures, bad wells, a bad grid, anything;
3. if it fails, tells the model the problems (names only) and asks once more; if it still fails you get `422 DRS-4020` naming the first problems, and the draft is **not shown**;
4. shows the passing draft as text with *Use this draft*, which puts it in the YAML tab like text you typed. **Nothing is saved or published**: the designer's undo, Check and Save are unchanged,
   and publishing still needs a person and the usual review.

```
POST /api/v1/bi/ai/draft  {"dataset": "trades-basic", "request": "notional by desk as a bar chart"}      (as wes, who holds bi-author)   200
{ "yaml": "rachana: 1\nreport: drafted\nversion: 1\ntitle: Drafted from words\ndataset: trades-basic\npages:\n  - id: main\n ...",
  "valid": true, "problems": [], "dataset": "trades-basic", "saved": false }

POST /api/v1/bi/ai/draft  {"dataset": "trades-basic", "request": "x"}                                     (as rita, no bi-author)         403
{ "status": 403, "detail": "DRS-9002 rita does not hold the bi-author power (an administrator grants it on a role)", "code": "DRS-9002" }
```

![Describe a report in the designer](img/bi-ai/bi-ai-describe.png)

## 5. Ask the data

On a report page, the question box under the bar maps a question to a **query on the report's dataset**: `fields` to group by, `measures`, `filters`, `dates`, `orderBy`, `limit`: the same
document the engine's visuals use ([DATASETS.md](DATASETS.md) section 8). The model chooses only names from the model it was shown; the **engine** validates every name and binds every value, and
runs the query **as you**: your row rules and your masks apply, so two people asking the same question get the answers they are each entitled to (`BiAiTest` has a desk head and a risk
analyst ask "notional by desk": one row and three rows; and an analyst asking for a masked measure gets it masked).

The page shows the answer as a **table**, the **visual that suits it** (a card for one number, a line over dates, a bar for a few categories; chosen by Drishti from the shape of the query, not
by the model), a note of how many rows the engine answered, and **the query the model chose** (a disclosure) so you can see, and trust or doubt, what was asked. A refusal by the engine
(a field that is not there, a limit) is shown with its code (`DRS-9020`, `DRS-9025`) and "the query the model chose was refused".

```
POST /api/v1/bi/ai/ask  {"report": "file:report:trades-overview", "question": "notional by desk"}                  (as rita)   200
{ "columns": [{"name": "trade.desk", "type": "string", "masked": false}, {"name": "Notional", "type": "decimal", "masked": false}],
  "rows": [["DESK-FX", 79000000.0], ["DESK-FI", 78000000.0], ["DESK-EQD", 77000000.0]], "rowCount": 3,
  "meta": {"layer": "columns", "rows": 3, "cached": false, "dates": ["2026-10-08"], "tables": ["trade"], "masked": [], ...},
  "question": "notional by desk",
  "query": {"fields": ["trade.desk"], "measures": ["Notional"], "filters": [], "dates": {"from": "2026-10-08", "to": "2026-10-08"},
            "orderBy": [{"by": "Notional", "desc": true}], "limit": 200},
  "visual": "bar", "values": "labels-only", "summary": null }

POST /api/v1/bi/ai/ask  {"report": "file:report:trades-overview", "question": "something no rule knows"}              (the mock cannot answer)   422
{ "status": 422, "detail": "DRS-4020 the model did not answer with a query", "code": "DRS-4020" }
```

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

```
POST /api/v1/bi/ai/explain  {"report": "file:report:trades-overview", "page": "overview", "visual": "notional", "state": {"dates": {...}}}     (as rita)   200
{ "measure": "Notional",
  "lineage": { "dataset": {"name": "trades-basic", "title": "Trades, the basics", "version": 1},
               "measure": {"name": "Notional", "expr": "sum(trade.notional)", "description": "Total notional of the trades, ..."},
               "columns": [{"name": "trade.notional", "type": "decimal", "calculated": false, "expr": null}],
               "sources": [{"table": "trade", "kind": "trade", "connector": "trading-store", "mode": "cached"}] },
  "value": 234000000.0,
  "breakdown": [ {"dimension": "trade.desk", "members": [{"member": "DESK-FX", "value": 79000000.0, "share": 33.8},
                                                      {"member": "DESK-FI", "value": 78000000.0, "share": 33.3}, ...]},
                 {"dimension": "trade.book", "members": [...]}, {"dimension": "trade.productType", "members": [...]} ],
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
| `DRS-4018` (403) | the AI policy does not open `bi-draft` / `bi-ask` / `bi-explain` to your role |
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

## 9. Not built

Stage 2's other hooks stay unbuilt ([RUPAKA.md](../architecture/RUPAKA.md) section 20.1): a **narrative summary** for subscriptions, the **designer copilot** (suggestions as diffs), and the
**dataset helper**. Not built either: "why did it move" between two dates, follow-up questions that remember the last (each question is alone), streaming answers, and a model that calls Drishti's
tools itself (the MCP client exists; no BI hook uses it).
