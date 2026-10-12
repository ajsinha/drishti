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
# Tutorial 15 · AI in Drishti

Drishti can put a language model beside BI in three places and no more: **describe a report** in words, **ask the data** a question, **explain this number**. It is **off by default**, it can only do what a person could do by hand (**it writes a draft or a query; Drishti checks it, runs it as the reader and shows it**),
and by default the model is never sent a row of data, only names. In this tutorial you turn it on with the **mock provider**, a deterministic stand-in with no network and no key, so you can see everything it does and everything it is stopped from doing, then you meet the AI **policy**, **Test connection** and the real providers.

## Time to complete

About 30 minutes.

## Before you start

- [Tutorial 10](10-first-bi-report.md) done (the World economics report), [tutorial 13](13-govern-bi.md) for the people `ann` (a writer) and `sue` (a steward).
- Nothing to buy and no key: the mock provider is part of Drishti. The two files used are in [data/ai/](data/ai/): `mock.yaml` and `local-model.yaml`.

## What you will learn

- how to switch the AI layer on **safely**: one folder of provider files, one setting, the mock provider;
- what the model is sent (names, never values) and who may use each feature (the BI power, then the **AI policy**);
- **Describe a report**, **Ask the data**, **Explain this number**, each with the thing the model cannot do;
- **Test connection** for a provider, and what the audit trail records;
- the provider types Drishti has, and where real keys go.

## Step 1 · Turn it on, with the mock provider

Providers are files in one folder, `config/ai/`, one YAML per provider; the AI layer is off until a setting turns it on. Copy the tutorial's mock provider into the folder:

```bash
cp docs/tutorials/data/ai/mock.yaml config/ai/mock.yaml
```

```yaml
# config/ai/mock.yaml (shortened)
type: mock
default-model: m
models:
  - name: m
mock:
  rules:
    - match: "TASK: draft-report"          # "Describe a report": answer with this YAML
      reply: |
        ```yaml
        rachana: 1
        report: gdp-by-region
        ...
    - match: "(?i)QUESTION: [^\\n]*per capita"     # "Ask the data": answer with a query (names only)
      reply: '{"fields":["economies.regionName"],"measures":["GDP per capita (USD)"],...}'
```

The mock answers from the **first rule whose regular expression is found in the last message**; with no rule it echoes. That makes the features repeatable, and it is exactly what you want to see before a real model is behind them. Now switch the layer on, on the **server** (it runs the BI hooks) and on the **console** (it shows Admin → AI and
serves other features); both read the same folder:

```bash
# before starting the server, and before starting the console
export DRISHTI_AI_ENABLED=true DRISHTI_AI_DEFAULT_MODEL=mock/m
```

Restart both. (`DRISHTI_AI_DIR` moves the folder; the default is `./config/ai`. Without `DRISHTI_AI_ENABLED` nothing is read and every AI call answers `404 DRS-4015`.) Then open **Admin → AI**.

![Admin, AI: the console's layer is ON, a list of the provider types, and three providers with status, models and detail, each with a Test connection button](img/t15/01-admin-ai.jpg)

You should see `AI layer: ON`, the folder (`./ai` here; `./config/ai` for you), and **the provider types this build knows** (listed below), then one row per provider file: `mock` with its model `mock/m` and the status `UNKNOWN` (`configured; no call yet`: **health never calls the network**), `local-model` (a provider you will test in step 2) and `fast`, a vendor provider
**switched off** (`enabled: false`). Every provider is off until its file says `enabled: true`.

## Step 2 · Test connection

`UNKNOWN` is honest: nothing has called the provider. **Test connection** makes **one cheap live call** (a model list, or Ollama's tag list) to the provider you choose, and shows the answer **inline**. Press it for each row.

![The three results: fast says "Not OK: enabled: false in the provider file"; local-model says "Not OK: http://127.0.0.1:18945 is not answering"; mock says "OK: configured; this type makes no live call (0 ms)"](img/t15/02-test-connection.jpg)

You should see three different, plain answers:

| Provider | Answer | Why |
|---|---|---|
| `fast` (Groq, switched off) | `Not OK: enabled: false in the provider file` | nothing was called: it is off |
| `local-model` (an Ollama address where nothing runs: [data/ai/local-model.yaml](data/ai/local-model.yaml)) | `Not OK: http://127.0.0.1:18945 is not answering (the model endpoint could not be reached or answered something unreadable)` | the endpoint was **really asked** and did not answer |
| `mock` | `OK: configured; this type makes no live call (0 ms)` | the mock needs no network |

For a real provider that works you would read `OK: the model list answered` (a type with a listing also shows the models the endpoint serves now). The button is **governed**: administrators only, one test per provider every few seconds, 15 seconds at most per test, and the answer never carries a key. And **audited**: every test is a line
in the console's log (and the layer's own recent list), who, which provider, the outcome, how long, never a key or a prompt:

```text
2026-10-12 00:15:58 INFO drishti.ai.audit: ai-audit {"detail": "configured; this type makes no live call", "feature": "test-connection", "millis": 0, "outcome": "ok", "provider": "mock", "user": "drishti-dev-admin"}
2026-10-12 00:15:58 INFO drishti.ai.audit: ai-audit {"detail": "http://127.0.0.1:18945 is not answering (the model endpoint could not be reached or answered something unreadable)", "feature": "test-connection", "millis": 4, "outcome": "failed", "provider": "local-model", "user": "drishti-dev-admin"}
2026-10-12 00:15:58 INFO drishti.ai.audit: ai-audit {"detail": "enabled: false", "feature": "test-connection", "outcome": "not-usable", "provider": "fast", "user": "drishti-dev-admin"}
```

You should see one line for each press. (The same page shows the **server's** layer below the console's; the server has its own list. Keys live there: the recommended console provider is `type: server`, which hands the call to the server so the key stays in one place.)

## Step 3 · What the model is sent, and who may ask

Before using a feature, know the two gates and the contents. **Gate one**: the BI power (`bi-author` to describe a report, `bi` to ask or explain). **Gate two**: the **AI policy**, per feature and role. And the prompt carries **the dataset's model and the person's own words, nothing else**:

```text
DATASET world-economics: World economics
FIELDS (write them as table.column):
  table economies:
    economies.regionName string - the World Bank region
    economies.gdpUsdBn decimal
MEASURES (write them by name):
  GDP (USD bn) - Gross domestic product at current prices, USD billions.
QUESTION: GDP by region
```

Not in it, in either mode: a row, a value of a field, a measure's definition, a connector or a path, and any field **masked for the reader** (it is left out, so the model cannot be told it exists). The setting `drishti.bi.ai.values` is `labels-only` by default; `aggregates` additionally lets the model word the reader's own masked
aggregate result, and is an administrator's decision, not a button.

The policy decides who may use each feature, how often and how much. This stack was started with the policy for **ask** open to `admin` and `bi-writer` only, and 30 calls a minute per user:

```yaml
drishti:
  ai:
    policy:
      per-user-per-minute: 30                       # DRS-4017 when exceeded
      features:
        "[bi-ask]": { roles: [admin, bi-writer] }   # bi-draft and bi-explain are named the same way; a feature with no entry is open to anyone who passes gate one
```

You should see it work in step 6: a person the policy does not list is refused before any model is called.

## Step 4 · Report from words

Open **Designer**, start a report on **World economics** (as in tutorial 10) and press **Describe a report**.

![Describe a report: the box with "GDP by region as a bar chart and the total as a card", the line "The draft passed the report check. Nothing is saved.", the YAML, and Draft it, Use this draft and Close](img/t15/03-describe.jpg)

Type `GDP by region as a bar chart and the total as a card` and press **Draft it**. You should see `The draft passed the report check. Nothing is saved.` and the **YAML of the draft**. What happened: the model (the mock here) was told the report grammar and the dataset model and answered with YAML; Drishti **stripped a code fence, pinned `dataset:` to your dataset, and ran
the report checker** on it, the same one **Check** and **Save** use. A draft that fails is repaired once (the model is told the problems, names only) and, if it still fails, refused as `DRS-4020` and **not shown**. Press **Use this draft**.

![The designer after using the draft: a GDP card with 117,327 and a bar chart of GDP for each region](img/t15/04-draft-used.jpg)

You should see the draft in the designer as a card (`117,327`) and a bar of GDP by region, with real data. It is a draft in your editor like text you typed: **nothing was saved or published**, undo, Check and Save are unchanged, and publishing needs a person and the usual review ([tutorial 13](13-govern-bi.md)).
The API behind the button:

```text
POST /api/v1/bi/ai/draft  {"dataset": "file:dataset:world-economics", "request": "GDP by region"}
200 {"yaml": "rachana: 1\nreport: gdp-by-region\nversion: 1\ntitle: GDP by region\ndataset: file:dataset:world-economics\npages:\n  - id: main\n ...", "valid": true, "problems": [], "dataset": "file:dataset:world-economics", "saved": false}
```

## Step 5 · Ask the data, and explain this number

Open the **World economics** report. Under the toolbar is the question box. Type `GDP by region` and press **Ask**.

![Ask the data: the answer as a bar chart and a table of seven regions, "Shown as bar, 7 rows, answered by the engine for you", and a disclosure "The query the model chose"](img/t15/05-ask.jpg)

You should see the answer as a **table** and **the visual that suits it** (Drishti chooses it from the shape of the query, not the model: a card for one number, a line over dates, a bar for a few categories), a line `7 rows, answered by the engine for you` and **The query the model chose**. Open that disclosure.

![The query the model chose, as JSON: fields economies.regionName, measures GDP (USD bn), orderBy GDP descending, limit 200](img/t15/06-ask-query.jpg)

You should see the query: `fields`, `measures`, `filters`, `orderBy`, `limit`. The model chose **names**; the **engine** validated every name, bound every value and ran the query **as you**: your row rules and your masks applied, so two people asking the same question get the answers they may each see. A query the model got wrong is refused by the engine with its code (`DRS-9020`) and shown as "the query the model chose was refused"; a question the model cannot turn into a query is `DRS-4020`, not a guess.

```text
POST /api/v1/bi/ai/ask  {"report": "file:report:world-economics", "question": "GDP per capita by region"}
200 {"columns": [{"name": "economies.regionName", ...}, {"name": "GDP per capita (USD)", ...}],
     "rows": [["North America", 86306.87], ["Europe & Central Asia", 34597.99], ["East Asia & Pacific", 13973.21], ...], "rowCount": 7,
     "query": {"fields": ["economies.regionName"], "measures": ["GDP per capita (USD)"], "filters": [], "orderBy": [{"desc": true, "by": "GDP per capita (USD)"}], "limit": 200},
     "visual": "bar", "values": "labels-only", "summary": null}
POST /api/v1/bi/ai/ask  {"report": "...", "question": "something the mock has no rule for"}
422 {"code": "DRS-4020", "detail": "the model did not answer with a query"}
```

You should see the answer, the query and `"summary": null` (in `labels-only` the model never saw the answer, so it wrote nothing about it). Now **Explain this number**: press the light bulb in the header of the **GDP (USD bn)** card.

![Explain this number: GDP (USD bn) = 117,326.82; Dataset World economics, Measure GDP (USD bn) = sum(economies.gdpUsdBn), Reads economies.gdpUsdBn, Sources economies (economy, macro-store); then the top members by name with their share](img/t15/07-explain.jpg)

You should see the **lineage** (the dataset, the measure's definition, the columns it reads, the sources: the pack kind and connector) and the **breakdown** the engine computed for you: the total, then its main dimensions with their top members and **share** (United States 30,769.7, 26.2%; China 19,498.0, 16.6%). In `labels-only` mode no model is called at all for Explain: it is lineage and arithmetic. Explain says **what a number is made of**, not why it moved over time.

## Step 6 · The policy says no

Sign in as **sue** (a steward: she holds `bi`, but the policy opens `bi-ask` only to `admin` and `bi-writer`) and ask the same question.

![Sue asks: DRS-4018 feature 'bi-ask' is not open to your roles](img/t15/08-policy-refusal.jpg)

You should see `DRS-4018 feature 'bi-ask' is not open to your roles`: the model was never called. Every call that is made, or refused, is audited twice (the policy's access-log row: who, feature, model, sizes, tokens, outcome; and a BI audit row such as `bi-ai-ask`), and **neither holds a prompt or an answer**. The codes you can meet:

| Code | Meaning |
|---|---|
| `DRS-4015` | the AI layer is off |
| `DRS-4016` | the provider failed or did not answer in time |
| `DRS-4017` | over a per-user, per-feature or cost limit |
| `DRS-4018` | the policy does not open this feature to your role |
| `DRS-4020` | the model's answer could not be used: nothing was changed |
| `DRS-9002` | no BI power for the feature |

## Step 7 · The providers, and real keys

Drishti has **21 provider types**: the four basics and **seventeen vendor types**. Each is a file in `config/ai/`; every one ships as `config/ai/<type>.yaml.example`, switched off. To use one: copy it to `config/ai/<name>.yaml`, set `enabled: true`, and put the key in the **environment**, never in the file (a literal key in a file is refused at load, naming the setting).

| `type:` | What it is | Key or credential |
|---|---|---|
| `mock` | the deterministic stand-in of this tutorial | none |
| `openai` | OpenAI, or any server speaking its API | `OPENAI_API_KEY` |
| `anthropic` | Anthropic's Messages API | `ANTHROPIC_API_KEY` |
| `server` | the console's provider that **delegates to the server**, so keys stay there | none (the server's) |
| `groq`, `together`, `fireworks`, `deepseek`, `xai`, `openrouter`, `perplexity`, `huggingface`, `mistral` | hosted, OpenAI-compatible, each with a preset address | `GROQ_API_KEY`, `TOGETHER_API_KEY`, `FIREWORKS_API_KEY`, `DEEPSEEK_API_KEY`, `XAI_API_KEY`, `OPENROUTER_API_KEY`, `PERPLEXITY_API_KEY`, `HF_TOKEN`, `MISTRAL_API_KEY` |
| `vllm`, `lmstudio`, `openai_compatible` | your own server speaking the OpenAI API (give `base-url`) | optional |
| `azure_openai` | Azure OpenAI | `AZURE_OPENAI_API_KEY`, a bearer token, or Microsoft Entra ID |
| `gemini` | Google Gemini (AI Studio) | `GEMINI_API_KEY` or `GOOGLE_API_KEY` |
| `cohere` | Cohere | `COHERE_API_KEY` |
| `ollama` | a local Ollama, no key | none (`base-url`, default `http://localhost:11434`) |
| `bedrock` | Amazon Bedrock | the AWS credential chain, a region |

A real provider file is short:

```yaml
# config/ai/fast.yaml
type: groq
enabled: true
api-key: ${GROQ_API_KEY}              # an ${ENV_NAME} placeholder; export GROQ_API_KEY before starting
default-model: llama-3.3-70b-versatile
```

Everything about each type (settings, model catalogue, proxy, TLS, retries, errors that never carry a response body) is in [AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md) section 11; the BI side (what the hooks send, `aggregates` mode, limits) is [BI_AI.md](../guides/BI_AI.md). Then use **Test connection** on the new row: the first honest answer tells you whether the key, the address and the network are right,
before anyone asks the data a question. You should see `OK` or the reason on the new row.

## Step 8 · Suggestions, plain-words checks and the dataset helper

Three more hooks help an **author** (they need `bi-author`, and the policy features `bi-suggest`, `bi-explain-checks` and `bi-helper`). In the designer, **Suggest visuals** takes a goal in your words and proposes visuals, wells and filters for the report's dataset. Each has passed the report checker and shows the YAML it would add; nothing is applied until you press **Add to this page** (one undoable edit):

![Suggest visuals: three suggestions with their YAML and an Add button](img/t15/09-suggest.jpg)

You should see a bar, a slicer marked *filter* and a card, each with a sentence on why. Press **Add to this page** on one and it appears at the bottom of the canvas, selected; **Undo** takes it back. In the **Check** panel, **Explain in plain words** answers each problem with what it means and what to change:

![Check, explained in plain words](img/t15/10-check-explained.jpg)

On the **Model view** of a dataset, **Ask the AI helper** proposes relationships, measures (with descriptions) and column descriptions from the dataset's names and types, never a value. Accept the items one by one; each is written into the YAML and you Save when you are done:

![The dataset helper: a measure and a column description, each with Accept](img/t15/11-dataset-helper.jpg)

You should see only items that fit the model: a name that is not in it is never offered. The same layer writes the **summary at the top of a subscription's email** ([tutorial 11](11-bi-that-runs-itself.md), step 8). [BI_AI.md sections 9 to 11](../guides/BI_AI.md#9-a-written-summary-at-the-top-of-a-subscriptions-email).

## Next steps

- [Tutorial 16](16-embed-drishti.md): put a view or a report on another web page.
- [BI_AI.md](../guides/BI_AI.md), [AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md) and the policy settings in [CONFIGURATION.md](../admin/CONFIGURATION.md#ai-and-mcp-policy-settings).

## If something goes wrong

| You see | Meaning |
|---|---|
| no light bulb, no question box, no Describe button | the AI layer is off (`DRISHTI_AI_ENABLED`) or no model is configured; the buttons are hidden and the API says `DRS-4015` |
| `DRS-4019` | the configured model is not found: check `drishti.ai.default-model` against the provider file |
| Admin → AI shows `ERROR` for a provider | its file could not be set up (a literal key, a missing variable, an unknown type); the detail says which, and it is retried |
| Test connection says `Not OK: ... is not answering` | the address, the port or the network is wrong, or the server is down |
| Test connection says `(authentication failed ...)` | the key is wrong or missing; the message names the variable to set |
| `DRS-4020` on Describe | the draft did not pass the report check even after one repair; nothing was changed |
| a question is answered with a different column than you expect | read **The query the model chose**: the model maps words to names, and you can see which |
