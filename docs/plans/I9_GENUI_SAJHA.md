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
# i9: Generative UI in Drishti through sajhamcpserver: architecture, design and plan (2026-11-19)

**Design only: no code is written by this document.** The owner's request: an architecture, a design and a plan for integrating
Generative UI (GenUI) through sajhamcpserver (SAJHA), with lightweight examples, so that release i9 (GenAI integration, and nothing
else) is prepared and its risk is reduced. The slide outline built from this document is
[I9_GENUI_SAJHA_SLIDES.md](I9_GENUI_SAJHA_SLIDES.md).

What was read for it: SAJHA's code and guides as they stand on 2026-11-19 (it is developed in another session and nothing of it was
changed); Drishti's AI layer ([AI_GUIDE.md](../guides/AI_GUIDE.md)), embed ([EMBED_GUIDE.md](../guides/EMBED_GUIDE.md)) and access
model ([ACCESS_MODEL.md](../architecture/ACCESS_MODEL.md)); Rachana's `AI.md`, `design/AI_GENUI.md`, `design/LIBRARIES.md`,
`design/LIBRARY_CATALOGUE.md`, `design/SUTRA_EXPANSION.md`, `design/EL_EXPANSION.md` and `design/DECISIONS.md`; the i7b products plan.
Every statement about SAJHA or Rachana below says whether the thing **exists today**, is **due from i7b**, or is **new work in i9**.

## Contents

1. [The answer in one page](#1-the-answer-in-one-page)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [The architecture](#3-the-architecture)
4. [The contract between Drishti and SAJHA](#4-the-contract-between-drishti-and-sajha)
5. [The five flows, with examples](#5-the-five-flows-with-examples)
6. [Safety and governance](#6-safety-and-governance)
7. [Evaluation](#7-evaluation)
8. [What must exist before i9](#8-what-must-exist-before-i9)
9. [The risk register](#9-the-risk-register)
10. [The i9 plan](#10-the-i9-plan)
11. [Decisions for the owner](#11-decisions-for-the-owner)

## 1. The answer in one page

| Question | Answer |
|---|---|
| What is GenUI in Drishti? | A person asks in words; a model writes a **Sutra** (data, never code); Drishti checks it, compiles it, binds it to data **as that person** and paints it. The person keeps talking to refine it, and may save it through the normal review |
| Where does the model run? | Behind **SAJHA**. SAJHA holds the model providers, the keys, the budgets, the tool-use loop and the chatbot. Drishti holds no model key for GenUI |
| What does Drishti give SAJHA? | **One MCP server with eleven tools**: describe the catalogue, describe a table, list and get fragments, examples, read data, validate a Sutra, open a view, describe and patch an open view, explain a value |
| Who is the caller? | Always the person. SAJHA calls Drishti with that person's own credential (SAJHA's per-user modes for proxied servers exist today). There is no service user that can read for everybody |
| What does the model see? | By default **names, types and descriptions only**: no row, no figure. Figures reach the screen from Drishti's server, never through the model |
| Where is a Sutra checked? | In Drishti's server, by the Rachana compiler, against **that person's catalogue**. Whatever the model wrote, a name the person may not read is an error |
| What if there is no model, or no SAJHA? | The "Describe" box is absent, or falls back to today's rule-based Describe. Nothing else changes |
| How big is i9? | About **117 builder-days identified, plan on about 135** (section 10). The "about 8 days" in the i7b plan covered an in-process loop only: no MCP server, no identity work, no surfaces, no evaluation |
| What reduces the risk? | Six short **spikes** that can run before i9 starts (section 10.3), the first being one thin path from words to an opened view, and fourteen decisions taken now (section 11) |

**The three largest risks** (the register is section 9): the identity path between the two products (R1); first-try validity on
Drishti's own profile and fragments, which nobody has measured yet (R3); and showing a *live* Drishti panel inside the chatbot, which
SAJHA's view sandbox does not allow as it stands (R5).

## 2. Goals and non-goals

i9 is **GenAI integration, exclusively**. Nothing else rides in it.

### 2.1 Goals

| # | Goal | Proven by |
|---|---|---|
| G1 | **Describe a screen**: words in, an opened, live, linked Drishti view out, composed from fragments | flow 5.1; the evaluation of section 7 |
| G2 | **Explain this number**: any cell of any view explains itself, in a tree and in a sentence | flow 5.2 |
| G3 | **Refine by conversation**: "make the map Canada only" changes the open view by a small patch, with undo | flow 5.3 |
| G4 | **GenUI inside an intranet application** through the embed, in place, links intact | flow 5.4 |
| G5 | **The chatbot shows a Drishti panel**: Ask SAJHA answers with a Drishti view the person can open | flow 5.5 |
| G6 | **One contract** between the two products: a Drishti MCP server, called as the person | section 4; the contract test |
| G7 | **Governed**: masks, roles, row rules and data licences apply before anything reaches a model; cost capped; every call audited | section 6; the adversarial tests |
| G8 | **Measured**: quality is a number on a held-out task set, and a release gate | section 7 |
| G9 | **Optional**: with AI off, SAJHA absent or a model down, only these features disappear | section 6.8 |

### 2.2 Non-goals

| Not in i9 | Why |
|---|---|
| Any feature that is not GenAI (role Homes, K5, the market-risk expansion, new engines, new packs) | the owner's rule for i9 |
| Generated code, HTML, CSS or script of any kind | a Sutra is data; that is the whole safety argument |
| A model computing or pricing anything | every figure comes from Drishti's engines on the server; Drishti is not a valuation engine and a model is even less one |
| Writes: a generated Sutra that edits a record or calls an action that changes data | read-only first (decision GD13) |
| A second tool-use loop, a second model gateway or a second chatbot inside Drishti | one way per job; SAJHA has all three |
| Rows of data sent to a model | default `none`; aggregates only where a site opts in (GD4) |
| Screens that mix Drishti's data with other SAJHA tools' data in one view | two access models in one screen; later (GD12) |
| Token-by-token streaming of a Sutra as the model types it (Rachana M3 in full) | i9 paints progressively once the Sutra is valid (GD6) |
| Fine-tuning, retrieval over documents, agents that act on a schedule | not needed for G1 to G5 |
| Changing SAJHA from this repository | SAJHA's own session builds what section 8.2 asks of it |

## 3. The architecture

### 3.1 The actors

| Actor | What it is | Its part in GenUI |
|---|---|---|
| **The person** | a signed-in user with roles, a lens, row rules and masks | asks, reads, refines, saves |
| **Drishti console** | the thin console: pages, the embed library, the renderer | the Describe box, the conversation beside a view, the painter; calls only Drishti's server |
| **Drishti server** | kernel, engines, the Rachana compiler (Java), `drishti-ai` | the MCP server; validates, compiles, binds and serves views; the only place data lives |
| **SAJHA** (sajhamcpserver) | the owner's MCP server: a governed tool catalogue, the intelligence layer, Ask SAJHA | runs the tool-use loop; holds the model providers, keys, budgets, policy and audit of model calls |
| **The Rachana tool pack** (in SAJHA) and **the generic Rachana MCP server** | the same four tools on two hosts (`data_to_table`, `sutra_examples`, `validate_sutra`, `render_dashboard`) | dashboards over **non-Drishti** data in the chatbot; the prompt kit both products share |
| **The model** | any provider SAJHA has configured | writes Sutras and patch documents; reads names, types and problem lists |

### 3.2 The picture

```
                 the person
        ┌────────────┴─────────────┐
        ▼                          ▼
┌──────────────────┐      ┌──────────────────┐        ┌───────────────┐
│ Drishti console  │      │ Ask SAJHA (chat) │        │ intranet app  │
│ Describe · view  │      │                  │        │ with embed    │
└────────┬─────────┘      └────────┬─────────┘        └───────┬───────┘
         │ session                 │ session                  │ embed token
         ▼                         ▼                          │
┌──────────────────┐  ask   ┌──────────────────────────┐      │
│ Drishti server   │───────►│ SAJHA                    │      │
│                  │        │  tool-use loop, planner  │      │
│  MCP server ◄────┼────────│  tool catalogue, policy  │─────►│ the model
│  Rachana compile │ tools  │  model providers, budget │ chat │ (any provider)
│  bind as viewer  │ as the │  Rachana tool pack       │◄─────│
│  ephemeral views │ person │  audit                   │      │
└────────┬─────────┘        └──────────────────────────┘      │
         │ ViewModel stream (skeleton, panels, patches)       │
         └────────────────────────────────────────────────────┘
                  to whichever surface asked
```

Two arrows cross the boundary between the products, and both carry the person's identity:

- **ask**: Drishti's server asks SAJHA to run a GenUI conversation for this person (only when the person started in Drishti).
- **tools**: SAJHA calls Drishti's MCP server for this person (always, whichever surface the person started on).

### 3.3 Who does what to a Sutra

| Step | Where | By | Note |
|---|---|---|---|
| **Ground** (what may be used) | Drishti server | `describe_catalogue`, `describe_table`, `list_fragments`, `sutra_examples` | filtered to the person before the model sees a name |
| **Generate** | the model, called by SAJHA | SAJHA's governed model (`llm_factory().model(alias)`) | the only step that costs tokens |
| **Validate and fix** | Drishti server | the Rachana compiler in process, profiles `core` + `drishti`, catalogue mode | certain fixes are applied by the compiler, not the model |
| **Compile** | Drishti server | the same compiler | only a valid Sutra compiles |
| **Bind to data** | Drishti server | the engine's binder, **as the viewer** | row rules and masks here, as for every hand-written Sutra |
| **Paint** | the browser | the one renderer (console, embed, or a card) | paints only what the ViewModel says |
| **Keep** | Drishti server | the ephemeral view store, then the normal proposal and review | a saved Sutra has no model in its path |

For data that is **not** Drishti's (a SAJHA market-data tool, a SQL connector), the Rachana tool pack does the same job inside SAJHA
with the `core` profile and table handles. The two paths share the prompt kit, the problem format and the tool names.

### 3.4 Boundaries and trust

| Boundary | Trusted for | Never trusted for |
|---|---|---|
| The model | nothing | everything it writes is checked as strictly as a file from a stranger |
| SAJHA | running the loop, model governance, saying which of its users is calling (through that user's own credential) | deciding what a person may read in Drishti: Drishti decides on every call |
| Drishti server | data, access, compile, bind | calling a model for GenUI |
| The browser | painting | holding a model key, a SAJHA credential or an unmasked value it was not sent |
| Rows of data | being shown to the person entitled to them | being instructions: a cell is text, in the page and in every tool result |

Drishti enforces access where the data lives. Nothing about security depends on what SAJHA or the model was told, asked or wrote.

### 3.5 Why the loop runs in SAJHA

Rachana's design lists Drishti as a host that runs "the same loop in process". This document recommends moving only the **model loop**
to SAJHA and keeping validation, compile and bind in Drishti's process (decision GD1).

| | Loop in SAJHA (recommended) | Loop in Drishti's process |
|---|---|---|
| Model keys, budgets, fallback, cache | one place: SAJHA's governor, built today | a second set: Drishti's layer has policy and limits but no fallback, cache, budget or breaker |
| The chatbot | Ask SAJHA, built today, gets Drishti screens for the price of the tools | a second chat in the console |
| Planners, tool shortlist, conversation memory | built today | to be written |
| Identity | two hops to prove (R1) | one product, no hop |
| Without SAJHA | no GenUI | GenUI still works |
| Rule "one way per job" | kept | broken on the day the chatbot also wants Drishti screens |

## 4. The contract between Drishti and SAJHA

### 4.1 The Drishti MCP server (new in i9)

One endpoint on the Drishti server, off by default, switched by `drishti.genui.enabled` (which needs `drishti.ai.enabled`). Tools
are written on `BaseMcpTool`, the tool base class `drishti-ai` already has with the signatures of SAJHA's `BaseMCPTool`. SAJHA
registers the endpoint as a proxied server with prefix `drishti`, so the model sees `drishti__<tool>` (SAJHA's two-underscore rule
for proxied names, which exists today).

| # | Tool | Returns | Sends values? |
|---|---|---|---|
| 1 | `describe_catalogue` | the tables, functions and lenses **this person** may use: name, one-line description, row-count class, licence class | no |
| 2 | `describe_table` | columns: name, type, description, `masked`, unit; a column profile only where the values policy allows | no (by default) |
| 3 | `list_fragments` | fragment cards of the installed libraries, ranked by fit to a named table | no |
| 4 | `get_fragment` | one fragment's page and the schema of its `with:` | no |
| 5 | `sutra_examples` | the grammar card, the Drishti profile's card, reviewed examples by intent | no |
| 6 | `read_data` | a small, masked, aggregated result and a handle to it, as the person | **yes, only if the site allows** (GD4) |
| 7 | `validate_sutra` | `ok`, or problems with code, position, expected names and a fix; the fixed text when every fix is certain | no |
| 8 | `open_view` | an ephemeral view for this person: id, link, expiry, and a summary without values | no |
| 9 | `describe_view` | the outline of an open view: ids, kinds, titles, what each panel reads (Rachana M2) | no |
| 10 | `patch_view` | a patch document applied atomically and re-validated; the new revision, or the problem list (Rachana M1) | no |
| 11 | `explain_value` | the explanation tree of one cell of an open view (Rachana X44), masked values as `•••` | figures the person already sees, only under `aggregates` |

Tools 3, 4, 5 and 7 carry **the same names and schemas** as Rachana's planned tools, so a prompt, a planner and an evaluation task
written for one host work on the other. `open_view` is Drishti's form of `render_dashboard` with `output: link`.

Rules of the server:

- **Every call is a call by a person.** No tool runs without a resolved, enabled Drishti user. `tools/list` itself is filtered: a
  person without the `genui:use` permission sees no tool.
- **Read-only.** No tool writes a record, a Sutra file, a setting or a connector.
- **Small answers.** Every result has a size cap; a list says `more: true` instead of growing.
- **Text is marked as data.** Descriptions come from contracts and pack READMEs, never from rows.
- **Same errors as the product.** A refusal carries Drishti's code (`DRS-…`) and a sentence the model can act on.

### 4.2 Lightweight schemas

Ask for the catalogue:

```json
{ "name": "describe_catalogue", "arguments": { "lens": "risk", "query": "var desk", "max": 20 } }
```

```json
{ "tables": [
    { "name": "risk.var_by_desk", "about": "1-day 99% VaR per desk and business date", "rows": "small",  "licence": "internal" },
    { "name": "risk.desk_pnl",    "about": "Daily P&L per desk",                       "rows": "medium", "licence": "internal" } ],
  "functions": ["VAR", "EXP"], "libraries": ["core@1", "finance@1", "ai@1"], "more": false, "catalogue_hash": "7b1e…" }
```

Describe one table (no values: the policy is `none`):

```json
{ "table": "risk.var_by_desk", "columns": [
    { "name": "DESK",          "type": "string",  "about": "Desk name" },
    { "name": "BUSINESS_DATE", "type": "date" },
    { "name": "VAR_99",        "type": "decimal", "unit": "USD" },
    { "name": "TRADER_NAME",   "type": "string",  "masked": true } ] }
```

Validate (the result is Rachana's contract, unchanged):

```json
{ "ok": false, "attempt": { "n": 1, "max": 3 },
  "problems": [ { "code": "RCH-2071", "message": "risk.var_by_desk has no column 'VAR99'",
                  "path": "/panels/1/with/value", "expected": ["VAR_99", "DESK", "BUSINESS_DATE"],
                  "fix": { "title": "Use 'VAR_99'" } } ],
  "fixed": "rachana: 1\nsutra: desk-var\n…",
  "summary": "1 error, fixed: 'VAR99' is 'VAR_99'." }
```

Open the view:

```json
{ "name": "open_view", "arguments": { "sutra": "rachana: 1\n…", "title": "VaR by desk", "ttl_minutes": 1440 } }
```

```json
{ "view": "gv_9c1e", "url": "https://drishti.bank.example/genui/gv_9c1e", "expires": "2026-11-20T14:00:00Z",
  "summary": { "panels": [ { "id": "tiles", "kind": "strip" }, { "id": "by-desk", "kind": "hbar", "rows": 6 } ],
               "reads": ["risk.var_by_desk", "risk.desk_pnl"], "generated": true } }
```

### 4.3 What Drishti calls on SAJHA

Only one thing, and only when the person starts in Drishti: **"run this GenUI turn for this person"**.

| Call | What it is | Status in SAJHA |
|---|---|---|
| `sajha_ask` over `/mcp` | the Ask service as an MCP tool: `question`, `conversation_id`, `model`; runs as the MCP caller with that caller's tool access; progress events on the stream | exists (the tool ships disabled: `ai.ask.mcp_tool_enabled`) |
| a **GenUI planner file** | pins the turn to the Drishti tools and the Rachana pack, sets the step limit and the model aliases (a fast one to draft, a stronger one to repair) | planner files exist; this file is new, in SAJHA's `config/planners` |
| `ai.ask.mcp_allowed_tools` | narrows what a turn started from Drishti may call (for example `drishti__*` and the Rachana tools) | exists |

Drishti reaches SAJHA with the MCP client it already has (`McpGateway`, `StreamableHttpMcpClient`), so the call passes Drishti's own
MCP policy, masking and audit.

```json
{ "name": "sajha_ask", "arguments": {
    "question": "Describe a screen: VaR by desk with the P&L trend, biggest first",
    "conversation_id": "new" } }
```

```json
{ "answer": "I opened 'VaR by desk': tiles, a ranking of six desks and the P&L trend.",
  "steps": [ { "name": "drishti__describe_catalogue", "ok": true }, { "name": "drishti__validate_sutra", "ok": true },
             { "name": "drishti__open_view", "ok": true } ],
  "conversation_id": "c_41f2", "stopped_by": "answer", "usage": { "total_tokens": 6140 } }
```

Drishti reads the view id from the `drishti__open_view` step and opens it. It does not parse the model's sentence.

### 4.4 Identity: the person, end to end

No hop uses a shared identity that could read for everyone.

```
person ── signs in ──► Drishti ── as the person (their SAJHA token) ──► SAJHA
                                                                         │
person ◄── view, bound as the person ── Drishti ◄── as the person (their Drishti token) ──┘
```

| Hop | Mechanism | Status |
|---|---|---|
| Person → Drishti | the console's sign-in (single sign-on where the site has it) | exists |
| Person → SAJHA (chat surface) | SAJHA's sign-in (OpenID Connect single sign-on exists) | exists |
| **SAJHA → Drishti** | SAJHA's per-user credential modes for proxied servers. **`oauth_user`**: each person signs in to Drishti once from SAJHA's "Server connections"; SAJHA keeps that person's token in its vault and sends it on that person's calls only. **`token_user`**: the person pastes a Drishti personal API token (these exist, are read-only and scoped) | both modes exist in SAJHA; Drishti must become an OAuth-protected MCP resource for `oauth_user` (new) |
| **Drishti → SAJHA** | the mirror image: Drishti holds the person's SAJHA access token, obtained by an authorization-code sign-in with PKCE against SAJHA's `/mcp` (SAJHA's OAuth for `/mcp` exists) | Drishti's side is new |

Rules:

- **No token passthrough.** A SAJHA token is never sent to Drishti and a Drishti token never to SAJHA or a model. Each product
  holds only the token issued *for it* (resource indicators bind a token to one server).
- **`static` and `service_with_user` are refused** for the Drishti upstream: the first is one identity for all, the second trusts
  a header. A guard in the upstream's configuration check and a line in the deployment guide say so.
- **Not connected** is an answer, not an error: "connect your Drishti account" with a link (SAJHA does this today for proxied
  servers), and the same in the other direction in the console.
- **With one identity provider for both products**, both sign-ins are silent after the first.
- **Scopes.** A Drishti token for SAJHA carries `genui:use` only: it reads through the eleven tools, never the rest of the API.
- **Revocation.** Disabling the person in either product, or disconnecting, ends that person's tokens at the next call.

### 4.5 Streaming

| Leg | Carries | Form |
|---|---|---|
| SAJHA → Drishti server | the turn's progress: tool called, tool finished, answer text | progress events on the MCP call's stream (SAJHA sends these when the call asks for them) |
| Drishti server → browser | the screen | the embed's streamed-screen messages, which exist: `skeleton`, `panel-add`, `panel-replace`, `patch`, `done`, `error` |
| Drishti server → browser | the conversation | status lines ("reading the catalogue", "checking", "fixed 1 problem") and the answer text |

```
t=0.0s  status    reading the catalogue
t=1.9s  status    writing the screen
t=4.2s  skeleton  { view: gv_9c1e, panels: [tiles, by-desk, trend] }      ← the layout appears, each panel "Loading…"
t=4.4s  panel-replace  tiles
t=4.6s  panel-replace  by-desk
t=5.1s  panel-replace  trend
t=5.1s  done
```

In i9 the skeleton appears when the Sutra is valid, and panels fill as they bind. Painting while the model is still typing
(Rachana M3) needs the model's partial tool arguments to travel through SAJHA's loop; that is left for later (GD6).

### 4.6 The contract test

One file of request and response vectors per tool, run three ways: against Drishti's MCP server directly; through a scratch copy of
SAJHA on a spare port with its mock provider (the way `SajhaMcpLiveTest` already reaches SAJHA); and, for tools 3, 4, 5 and 7,
against Rachana's own tool pack to show that the shared tools answer alike.

## 5. The five flows, with examples

### 5.1 Describe a screen

```
person        console        Drishti server            SAJHA                 model
  │ "VaR by desk,  │               │                      │                     │
  │  with P&L trend"──────────────►│── sajha_ask ────────►│── question + tools ►│
  │                │               │◄─ describe_catalogue ─┤◄─ call ─────────────┤
  │                │               │◄─ list_fragments ─────┤◄─ call ─────────────┤
  │                │               │                      │◄─ Sutra (as an argument of validate_sutra)
  │                │               │◄─ validate_sutra ─────┤                     │
  │                │               │── 1 problem, fixed ──►│                     │
  │                │               │◄─ open_view ──────────┤                     │
  │                │◄─ skeleton, panels (bound as the person)                    │
  │◄── the screen ─┤               │── answer ────────────►│                     │
```

The Sutra the model writes: nine lines, three fragments, two tables by name. No row, no address, no style.

```yaml
rachana: 1
sutra: desk-var
title: VaR by desk
libraries: [ core@1, finance@1 ]
data:
  var: { ref: risk.var_by_desk }
  pnl: { ref: risk.desk_pnl }
panels:
  - { use: finance/var-tiles@1,  with: { var: "sum($.var, 'VAR_99')" } }
  - { use: core/ranked-bars@1,   with: { rows: $.var, label: DESK, value: VAR_99 } }
  - { use: core/figure-trend@1,  with: { rows: $.pnl } }
```

What the person gets: the screen; beside it, folded, the Sutra and the words that made it; a mark "generated" with the time and the
model; links and drills that work as on any view. The view is **ephemeral** (kept for the person for a day), and "Save" sends it
through the normal proposal and review, after which it is an ordinary Sutra.

If three attempts do not produce a valid Sutra, the person gets the panels that were valid, a plain sentence about what could not
be built, and the inferred screen for the same tables as a floor. A broken screen is never shown.

### 5.2 Explain this number

The person presses "?" on a cell. No Sutra is written; the model only puts the compiler's explanation into a sentence.

```json
{ "name": "explain_value", "arguments": { "view": "gv_9c1e", "target": "panels[tiles].cells[var]" } }
```

```json
{ "value": "-1,204,300", "tree":
  { "op": "percentile", "source": "percentile($.pnl[*].v, 0.99)", "read": { "table": "risk.scenario_pnl", "rows": 509 },
    "says": "the 99th percentile loss of 509 scenario P&L values",
    "inputs": [ { "op": "filter", "says": "scenarios of desk RATES on 2026-11-18", "rows": 509 } ] } }
```

Two levels, in this order:

1. **The tree, with no model.** Rachana's explanation (X44) is drawn by `ai/explanation-tree@1`. It is exact, free and works with
   AI off. This is the answer.
2. **A sentence, with a model, when the site allows aggregates.** "The desk's 1-day 99% VaR is the 99th percentile loss across 509
   scenarios, 1.20 million." The sentence is checked: every figure in it must be a value of the tree, or the sentence is dropped
   and the tree stands alone.

### 5.3 Refine by conversation

The model does not rewrite the screen. It reads an outline and sends a patch.

```json
{ "name": "describe_view", "arguments": { "view": "gv_9c1e" } }
```

```json
{ "sutra": "desk-var", "revision": 1, "panels": [
    { "id": "tiles",   "kind": "strip", "reads": ["var.VAR_99"] },
    { "id": "by-desk", "kind": "hbar",  "reads": ["var.DESK", "var.VAR_99"] },
    { "id": "trend",   "kind": "line",  "reads": ["pnl.BUSINESS_DATE", "pnl.PNL"] } ] }
```

The person says "drop the trend, add the VaR limit, and show the bars as a table". The patch document:

```yaml
patch: desk-var@1
ops:
  - { op: remove,    at: "panels[trend]" }
  - { op: add-panel, after: by-desk, panel: { id: limit, use: finance/limit-gauge@1, with: { used: "sum($.var, 'VAR_99')", limit: "$.limits.var" } } }
  - { op: set,       at: "panels[by-desk].kind", value: table }
```

`patch_view` applies it atomically, validates the result, and answers the new revision or the problem list. The browser receives a
`panel-replace` and a `panel-add`; nothing else redraws. Each revision is kept, so **Undo** is "open revision 1". A 60-panel screen
is edited by a few hundred bytes of output, which is what makes refinement fast and cheap.

### 5.4 GenUI inside an intranet application

The host page adds a box and a place to draw. The application's backend already exchanges its user for a short-lived Drishti embed
token (this exists). i9 adds one element, `<drishti-describe>`, and one scope, `embed:genui`, that an administrator grants per
application. The sketch shows the intended shape; the names are settled with the mock-ups.

```html
<drishti-describe id="ask" placeholder="Describe the screen you need"></drishti-describe>
<div id="here"></div>
<script type="module">
  const ask = document.getElementById('ask'), here = document.getElementById('here');
  import { render } from 'https://drishti.bank.example/embed/v2/render/<version>/drishti-render.js';
  ask.addEventListener('drishti:describe', e =>
    render(here, { stream: e.detail.streamUrl }, { tokenProvider: getToken }));
</script>
```

- The screen is drawn **in place**; no new window opens unless the host asks for one.
- Every link, drill and event works, because they are in the ViewModel.
- An embed call always masks, whatever the person's roles; an embed token never writes; a view generated in an embed cannot be
  saved from the embed.
- The application can narrow what its users may ask about (its registered kinds), never widen it.
- With GenUI off, `<drishti-describe>` renders nothing and says so in an event.

### 5.5 The chatbot shows a Drishti panel

The person is in Ask SAJHA and asks "show me VaR by desk". The loop is the same as 5.1, started from SAJHA's side.

```
person ─► Ask SAJHA ─► model ─► drishti__describe_catalogue … drishti__open_view ─► { view, url, summary }
person ◄─ answer: "I opened VaR by desk (6 desks)."  [ Open in Drishti ]  +  the panel
```

How the panel appears, in three steps of rising ambition (decision GD5):

| Step | What the person sees | What it needs |
|---|---|---|
| **A. A link** | a button that opens the view in Drishti, signed in as themselves | nothing new in SAJHA |
| **B. A snapshot card** | the view drawn inside the chat from a ViewModel captured at that moment, marked with its time, with the link beside it | a self-contained card (painter and ViewModel inline). SAJHA's views get no network access and at most 2 MB, so the card cannot be live |
| **C. A live panel** | the real view inside the chat, live and linked | Ask SAJHA's page hosting Drishti's embed element with SAJHA registered as an embed application: a change in SAJHA's page, asked of its session |

For a question about **non-Drishti** data in the same chat ("chart the FRED series"), the Rachana tool pack answers with
`render_dashboard`. The person sees one chat; the two kinds of dashboards look alike because one painter draws both.

## 6. Safety and governance

### 6.1 The order of the gates

Everything that can leave Drishti for a model passes these, in this order, before it leaves:

```
person's request
   └► module on? lens offered? function entitled? ─► role and row rules ─► column masks ─► data licence
        └► values policy (none | aggregates) ─► size cap ─► prompt masker (redact names and patterns) ─► the tool result
```

A model therefore never sees a table the person cannot open, a column that is masked for them, or a row their rules hide. The
gates are Drishti's existing ones; GenUI adds the last three.

### 6.2 What reaches a model

| Setting `drishti.genui.values` | The model receives | For |
|---|---|---|
| `none` (the default) | names, types, descriptions, units, row-count classes, problem lists, panel outlines | describing and refining screens: flows 5.1, 5.3, 5.4, 5.5 work fully |
| `aggregates` | also small aggregated results (`read_data`) and the figures of an explanation tree, masked, capped | the sentence of 5.2, "what stands out here" |
| rows | **never in i9** | |

A masked column has no profile, no examples and no value in any tool result under either setting. This is the rule the BI features
already follow ("the model, never a row"; narrative only in `aggregates` mode).

### 6.3 Data licences

Every external feed already records its licence (`redistribution: allowed | attribution | internal-only`). GenUI uses it:

- The catalogue carries the licence class of each table.
- Under `aggregates`, a table whose source is `internal-only` or not redistributable is **refused by `read_data`** unless the model
  that would receive it is marked local to the bank in SAJHA. Its names may still be used to build a screen, and the person still
  sees its values on that screen, as today.
- An embedded GenUI view shows such a table only if the application's registration allows it. IMF forecasts, Yahoo prices and news
  headlines stay internal demonstrations until the owner says otherwise.
- A generated view's provenance lists the sources and their attribution lines, as any view does.

### 6.4 Prompt injection from data

| Path | Defence |
|---|---|
| A cell says "ignore your instructions and …" | under `none`, no cell reaches a model at all. Under `aggregates`, values travel in a typed `data` field, truncated, and both SAJHA's system text and the planner state that tool output is data, never instructions |
| A description or a pack README carries an instruction | descriptions come from reviewed contracts and READMEs in the repository, not from loaded rows; the catalogue tools strip control text and cap length |
| The person's own words | the only free text; it can only produce a Sutra or a patch, which the compiler checks |
| A poisoned answer steers the model to another tool | a turn started for GenUI may call only the Drishti tools and the Rachana pack; every Drishti tool is read-only; SAJHA refuses a call to a tool that was not offered |
| A Sutra tries to leave | no `url:` source, no built address, no remote image, no script: refused by the grammar and by the "refs only" check for generated Sutras |

### 6.5 No code, ever

A Sutra has nowhere to put code: no script, HTML, CSS or handler text; expressions are bounded and total; strings are painted as
text. The compiler checks every key, every expression and every name against the person's catalogue before anything is bound. The
worst a wrong or hostile Sutra can do is show the wrong panel of data the person may already read.

Limits on a generated Sutra: 64 KB, 60 panels, the language's expression budgets, three validation attempts, sources by row-count
class, and refs only. Actions that write are refused (GD13).

### 6.6 Cost, model choice and limits

The owner supplies model access and a spending cap (accepted in the i7b plan); SAJHA enforces it.

| Control | Where | Status |
|---|---|---|
| Which models GenUI may use; a fast alias to draft, a stronger one to repair | SAJHA: aliases and role policy; the GenUI planner names the aliases | exists (the planner file is new) |
| Tokens per user per day, per role per day | SAJHA: budgets | exists |
| Calls per minute per user, per tool | SAJHA: policy rate limits; Drishti: limits on its MCP tools and on the ask | exists in both layers |
| Steps and attempts per turn | the planner's step limit; three validation attempts | exists / due from i7b |
| A ceiling for the whole site per day | a SAJHA policy rule; Drishti also stops asking when its own count for the day is reached | to configure; confirm in spike S6 that SAJHA can express it in money as well as tokens |
| What a turn cost | SAJHA's usage record; Drishti shows "this turn: n tokens" from the answer's `usage` | exists |

Expected size of a turn, to be measured in spike S6: about 2,000 tokens of fixed kit, 1,000 to 3,000 of catalogue and fragment
cards, a few hundred of output; a refinement is far smaller because the model reads an outline and writes a patch.

### 6.7 Audit

| Record | Written by | Holds | Never holds |
|---|---|---|---|
| The model call | SAJHA | user, model, tokens, outcome | |
| The tool call | SAJHA | user, tool, which identity was used for the proxied call | the token |
| The MCP call | Drishti (`mcp` rows) | user, tool, sizes, outcome, milliseconds | arguments or results |
| The GenUI turn | Drishti (new) | user, surface, the words, the catalogue hash, the Sutra or patch produced, the repairs, the view id | row data |
| The view | Drishti | "generated", by which model, from which words, when: in the provenance panel and, once saved, in the Sutra's `doc:` | |

A reviewer can replay any generated screen from its record: the same Sutra over the same catalogue gives the same screen.

### 6.8 Without AI

| Situation | What the person sees |
|---|---|
| `drishti.ai.enabled` or `drishti.genui.enabled` is off | no Describe box, no chat beside a view, no "?" sentence. The explanation tree still works. Admin → Health says "GenUI: off" |
| SAJHA is not configured or not reachable | the same, with "GenUI: SAJHA not reachable" in Health; the designer's Describe uses its rules, as today |
| The person has not connected their SAJHA account | a "Connect" prompt in the box |
| The model is down or the budget is spent | the turn ends with a plain sentence; open views are untouched |
| A view that was generated and saved | works always: it is an ordinary Sutra |

No start-up step, test, drill or page outside GenUI depends on SAJHA or a model. Tests use SAJHA's mock provider and Drishti's mock
MCP transport, on loopback only.

## 7. Evaluation

"It works" is a number. Rachana's harness (due from i7b) runs tasks through the real tools and checks the result by code, not by a
model. i9 adds Drishti's own task set and runs the harness **through SAJHA**, so the planner, the tool descriptions and the identity
path are measured too.

### 7.1 The task set

About 120 tasks at first, over the synthetic demo lakes only; one third is **held out** and never used while tuning prompts, the
planner or the tool descriptions.

| Family | Tasks | Examples |
|---|---|---|
| Describe from fragments | 45 | "a desk risk page from these four tables" |
| Refine by patch | 25 | "drop the trend, add the limits" |
| Explain | 15 | the sentence agrees with the tree |
| Must refuse or narrow | 20 | a table the test user may not read; a masked column; "email this"; an instruction hidden in a description |
| Hard shapes | 15 | wide tables, misleading column names, dates, nulls, a lens with few tables |

One task:

```yaml
id: describe-014
as: { user: rates-trader-1 }
ask: "VaR by desk, biggest first, with the P&L trend"
expect:
  - { reads: [risk.var_by_desk, risk.desk_pnl] }
  - { panel: { use: core/ranked-bars@1 }, rows: 6, first: { label: RATES } }
  - { fragments_share: ">= 0.7" }
  - { never_reads: [hr.salary] }
```

### 7.2 Targets

| Measure | Target for i9 | Source of the target |
|---|---|---|
| First-try validity (no repair at all) | ≥ 85% | Rachana D30 |
| Valid after the compiler's certain fixes | ≥ 93% | AI_GENUI 6.1 |
| Valid within three attempts | ≥ 99% | Rachana D30 |
| Fulfilment (the assertions hold) | ≥ 85% | AI_GENUI 6.1 |
| Panels that come from fragments | ≥ 70% | Rachana L-18 |
| Correct refusals (access, masks, injected text) | **100%** | a security property, not a quality one |
| A patch touches only what was asked | ≥ 95% | new |
| Sentences of "explain" whose figures are all in the tree | 100% shown (others are dropped) | section 5.2 |
| Tokens and seconds per turn; time to first panel | reported per model | |

The percentages are aims until the first run (spike S6); the refusal row is not negotiable.

### 7.3 Regression

- **Recorded runs replay in the drill with no model call**: they protect the tools, the planner file, the patch path and the
  stream, and cost nothing.
- **A live run on the held-out set** before the release, and whenever the prompt kit, a fragment library, the planner, a tool
  description or the model changes. A drop of more than two points in first-try validity blocks the change.
- **The problem-code histogram** from each run says what to improve next: a clearer message, a certain fix, or a line on a card.
- **Two users, one question**: every access task runs as two users with different rights and must give different catalogues.

## 8. What must exist before i9

### 8.1 From i7b (Rachana and Elements)

| Needed by i9 | i7b item | Milestone | If it is late |
|---|---|---|---|
| The prompt kit, generated, with the Drishti profile's card | X49, AI_GENUI G1 | M2 (part I), M4 | no generation: i9 cannot start |
| Certain fixes on the frequent problem codes | B14 | M4 | lower validity, more model calls; i9 starts but its targets move |
| The validate-and-fix contract in the Java API | AI_GENUI 3 | M4 | i9 would build it: +5 days |
| `rachana describe` (M2) and patch documents (M1) | Sutra R1 and R2 | M2, M3 | flow 5.3 is cut to "regenerate the screen" |
| Explanation (X44) | EL phase 2 | M3 | flow 5.2 is cut |
| Fragment libraries `core`, `finance`, `ai` with cards, `with` schemas, `list_fragments` and `get_fragment` | B7, L-18 | M3, M5 | the model writes panels: more tokens, lower validity |
| Drishti's own fragments as libraries | Part C | M7 | generated screens do not look like the product's |
| The evaluation harness and its first task set, with published figures | B14, D30 | M4, M6 | i9 would build a harness: +6 days |
| The tool pack for SAJHA (Python on WebAssembly) and the generic server | B14, D31, D32 | M4 | flow 5.5 for non-Drishti data waits; Drishti's own flows do not |
| The renderer's streamed screens; `ref:` and `data:` in the server's pipeline | Elements, Part C | M1, M7 | exists for the stream; `ref:` is needed for any generated Sutra |
| Model access and a spending cap | PD15 | by M3 | no evaluation figures: the kit ships as preview |

The i7b plan's own cut line applies: if D30's figures are not met, the kit ships marked preview, and i9 then ships GenUI marked
preview and off by default.

### 8.2 From SAJHA

| Needed | Status on 2026-11-19 |
|---|---|
| Proxied MCP servers with per-user credentials (`oauth_user`, `token_user`), the "connect your account" answer, the vault | exists |
| Proxied names `<prefix>__<tool>`; per-caller tool access; policy rules, rate limits, approvals; audit | exists |
| Model providers behind `LLMProvider` / `LLMModel`, governed: role policy, token budgets, cache, retries, fallback | exists |
| Ask SAJHA with a streamed answer; `sajha_ask` over MCP as the caller; planner files; the mock provider | exists |
| OAuth on `/mcp` (built-in authorization server or an external identity provider) | exists |
| The Rachana tool pack installed in its catalogue | due from i7b (Rachana's repository) |
| A GenUI planner file and a policy file for the `drishti` upstream | new: configuration in SAJHA, asked of its session |
| Ask SAJHA's page showing a Drishti view (steps A to C of 5.5) | A needs nothing; B and C are asked of its session |

### 8.3 In Drishti already

The AI layer with SAJHA's abstractions and canonical types; the MCP client with policy, masking and audit; `BaseMcpTool`; the prompt
masker; the embed with token exchange, scopes and streamed screens; personal API tokens; data contracts per mnemonic and pack
READMEs (the source of the catalogue); roles, row rules, masks, lenses, function switches; the access log.

## 9. The risk register

Likelihood and impact are High, Medium or Low. "Early sign" is what to watch for; "Spike" names the proof of section 10.3.

| # | Risk | L | I | Mitigation | Early sign | Spike |
|---|---|---|---|---|---|---|
| R1 | **The identity path does not hold end to end**: Drishti is not yet an OAuth-protected MCP resource; two sign-ins confuse people; a token is bound to the wrong server | M | H | `token_user` with a personal API token works on day one; `oauth_user` proven against a stub before the server is built; one identity provider for both products makes both sign-ins silent | the stub's discovery fails in SAJHA's connect page | S1, S2 |
| R2 | **i7b's AI deliveries are late or below D30** | M | H | i9's contract work (MCP server, catalogue, identity, surfaces) does not need the kit and goes first; the mock provider scripts the model meanwhile; preview label as the cut line | M4's exit gate slips | – |
| R3 | **Validity on the Drishti profile and fragments is unmeasured** and may be well under the Core figures | M | H | measure twenty tasks early; fragments first; schema-constrained output for `with:`; grow certain fixes from the histogram | first-try under 70% in S6 | S6 |
| R4 | **SAJHA changes under us**: it is developed elsewhere, and names or behaviours move | M | M | the contract test of 4.6 runs against a pinned scratch copy and against its head; Drishti depends on MCP and on four documented behaviours only (per-user modes, `sajha_ask`, planner files, progress) | the contract test fails on head | S1 |
| R5 | **A live Drishti panel cannot be shown inside the chat**: SAJHA's views have no network and a 2 MB cap | H | M | the link works today; a snapshot card next; the live panel is a named request to SAJHA's session | the card exceeds 2 MB with the painter inline | S5 |
| R6 | **The model does not pick Drishti's tools** among several hundred in SAJHA's catalogue | M | M | a GenUI planner that offers only the Drishti tools and the Rachana pack; descriptions written and tested like prompts | wrong tools in S4's shortlist | S4 |
| R7 | **Slow first panel**: two products, several tool calls, then generation | M | M | catalogue and cards cached per person and hash; one combined grounding call if needed; a fast drafting model; status lines from the first 200 ms | more than 6 s to the skeleton in S3 | S3 |
| R8 | **A value leaks to a model** through a profile, an example, an error message or a summary | L | H | default `none`; one function builds every tool result and applies the gates of 6.1; a test that seeds marker values in masked columns and fails if any appears in any tool result, log or audit row | the marker test | – |
| R9 | **Prompt injection** through a description or, under `aggregates`, a value | M | M | section 6.4; read-only tools; a turn limited to the Drishti tools; an injection corpus in the evaluation with a 100% target | a refusal task fails | S6 |
| R10 | **Cost runs away**: loops, long catalogues, retries | M | M | step and attempt limits, budgets per user and per site, small cards, patches for refinement, recorded replays in tests | tokens per turn above 12,000 in S6 | S6 |
| R11 | **One person's view or conversation reaches another** (shared caches, the ephemeral store, a cluster) | L | H | view ids are unguessable **and** bound to the person; every read re-checks the person; no per-user state in globals; the two-user tests; the cluster test opens a view on another node | the session-isolation review | – |
| R12 | **Generated screens mislead** (a wrong chart, a sum of mixed units) | M | M | fragments carry the product's own choices; the `ai` lint rules; the Sutra and the data table behind every chart; the "generated" mark; review before saving | fulfilment under target | S6 |
| R13 | **Scope creep**: i9 is the first release with a visible AI feature and attracts additions | H | M | the non-goals of 2.2; the cut lines of 10.5 agreed now | new items appear in the plan | – |
| R14 | **A second way appears**: an in-process loop "for when SAJHA is absent", or a second chat in the console | M | M | decision GD1 recorded; a guard test that no GenUI code path calls `LLMFactory` directly | a builder brief that names a provider | – |
| R15 | **The stateless MCP era is not what SAJHA's proxy speaks best to an upstream**, or the cluster needs sessions | L | M | proven in S1 against a stub on both eras before the server is written | S1 | S1 |
| R16 | **Licensed data reaches an outside model** under `aggregates` | L | H | the licence gate of 6.3 is in the same function as the masks; internal-only sources refused unless the model is local; default `none` | the licence test | – |
| R17 | **Drill time and stability**: live model calls in tests | M | M | no live call in any drill: recorded replays and mocks only; the live evaluation is a separate, capped job | a test that needs a key | – |

## 10. The i9 plan

### 10.1 Work items

Sizes are builder-days (bd), in the sense the i7b plan uses. Items marked ★ need a mock-up chosen by the owner before they are built.

| # | Item | bd | Needs |
|---|---|---|---|
| **A. The contract** | | | |
| W1 | The Drishti MCP server: the endpoint, `tools/list` and `tools/call` per person, the tool registry on `BaseMcpTool`, the switch, any node of a cluster | 8 | S1 |
| W2 | Identity both ways: Drishti as an OAuth-protected MCP resource (metadata, token checks, the `genui:use` scope); Drishti as a client of SAJHA's `/mcp` (connect, keep, refresh, revoke); the refusal of shared modes | 10 | S2 |
| W3 | The catalogue tools: `describe_catalogue`, `describe_table`, from contracts, virtual tables and pack READMEs; filtered by module, lens, function switch, role; licence class; masked flags; cached by hash | 7 | – |
| W4 | The Sutra tools: `validate_sutra`, `open_view`, `describe_view`, `patch_view` on the Rachana Java API; the ephemeral view store (per person, expiry, revisions, cluster) | 9 | i7b M3, M4 |
| W5 | `list_fragments`, `get_fragment`, `sutra_examples` for the installed libraries and the Drishti profile | 3 | i7b M3 |
| W6 | `read_data`, the values policy, result handles | 5 | W3 |
| W7 | `explain_value` on X44 | 4 | i7b M3 |
| **B. The flows** | | | |
| W8 | The GenUI turn in the server: the call to `sajha_ask`, progress relayed, conversation ids, the stream endpoint that emits `skeleton` to `done` | 8 | W1, W4, S3 |
| W9 ★ | The console: the Describe box, the generated view with its Sutra and conversation beside it, refine, undo, save through review, the "generated" provenance. Built only from the `ai` library's fragments and Elements, no bespoke UI | 12 | W8 |
| W10 ★ | "Explain this number": the tree, and the checked sentence | 4 | W7 |
| W11 | The embed: `<drishti-describe>`, the `embed:genui` scope, the stream under an embed token | 5 | W8 |
| W12 | SAJHA's side, by its session: the `drishti` upstream entry, the GenUI planner file, the policy file, the Rachana pack installed, the view link in Ask SAJHA (step A), the snapshot card (step B) | 5 | W1 |
| **C. Governance** | | | |
| W13 | The gates of 6.1 in one function; the licence gate; the injection defences; limits; the GenUI audit record | 6 | W3 |
| W14 | Off and absent behaviour, Health, the read-only Admin → AI lines, every setting in the configuration document | 3 | – |
| **D. Proof and release** | | | |
| W15 | The evaluation: Drishti's task set, the harness run through SAJHA, recorded replays in the drill, the mock scripts | 8 | i7b M4 |
| W16 | Adversarial tests and the security review: the marker test, the two-user tests, the injection corpus, session isolation, the threat model | 5 | W13 |
| W17 | Documents: the GenUI part of AI_GUIDE, EMBED_GUIDE, one guide with its tutorial (console, CLI where there is one, YAML), CONFIGURATION, the deck, screenshots | 6 | – |
| | The spikes of 10.3 | 9 | – |
| | **Identified** | **117** | |
| | **Plan on** (15% for what is not yet seen) | **about 135** | |

### 10.2 Order and milestones

| Milestone | Holds | Exit |
|---|---|---|
| **G0 Proofs** (may run during i7b) | S1 to S6 | each spike's question answered in writing; decisions GD1 to GD14 taken |
| **G1 The contract** | W1, W2, W3, W5, W13, W14 | the contract test passes three ways; two users get two catalogues through SAJHA; the marker test passes |
| **G2 Describe, in the console** | W4, W8, W9 | flow 5.1 on the mock and on one real model; a generated view saved through review |
| **G3 Refine and explain** | the patch half of W4, W6, W7, W10 | flows 5.2 and 5.3; undo |
| **G4 The other surfaces** | W11, W12 | flows 5.4 and 5.5 (steps A and B) |
| **G5 Proof and release** | W15, W16, W17 | the targets of 7.2 on the held-out set, or the preview label; the security review; one drill, a second if the first needed fixes |

Why this order: the contract and the identity path carry the largest risks and need nothing from the prompt kit, so they go first
and can start while i7b's last milestones finish. Describe comes before refine because refine edits what describe makes. The other
surfaces come last because they add reach, not proof, and are the first to cut.

### 10.3 Spikes to run early

Each is throw-away, time-boxed, on a scratch copy of SAJHA on a spare port, with synthetic data.

| # | Spike | Question it answers | bd | Can run |
|---|---|---|---|---|
| **S1** | **The thin end to end.** A stub MCP endpoint in Drishti with `describe_catalogue`, `validate_sutra` (today's checker) and `open_view` (a fixed link); SAJHA proxies it with `token_user` and a personal API token; SAJHA's mock provider scripts the three calls; two users | Do the two products talk? Does each user get their own catalogue? Which MCP era? Is the link opened as the person? | 2 | now |
| S2 | **Identity.** The stub publishes protected-resource metadata; SAJHA's `oauth_user` connects to it; the mirror sign-in from Drishti to SAJHA's `/mcp` | Which authorization server signs Drishti's tokens (the bank's identity provider or Drishti)? How many clicks does a person need? | 2 | now |
| S3 | **The stream.** `sajha_ask` over MCP with progress; Drishti relays it as `skeleton` and `panel-replace` | Do progress events arrive in order and in time? Seconds to the skeleton? | 1.5 | after S1 |
| S4 | **Tool choice.** The real SAJHA catalogue, a draft GenUI planner, twenty questions on the mock and on one model | Does the turn stay on the Drishti tools? What must the descriptions say? | 1 | after S1 |
| S5 | **The chat panel.** A self-contained card with the painter and one ViewModel inline | Does it fit SAJHA's limits? What would a live panel need from SAJHA? | 1 | after i7b M1 |
| S6 | **First figures.** Twenty Drishti tasks on one model with a small cap, through the generic Rachana server proxied by SAJHA | First-try validity on the Drishti profile; tokens and cost per turn; can SAJHA cap in money? | 1.5 | after i7b M2, with model access |

S1 and S2 need nothing from i7b and answer R1, R4 and R15: they are the cheapest risk reduction available today.

### 10.4 Tests

| Layer | What | Model calls |
|---|---|---|
| Unit | each tool against fixtures; the gates function; the patch path | none |
| Contract | the vectors of 4.6, three ways | none (mock) |
| Flow | 5.1 to 5.5 in the browser on the mock provider, in the page walk and layout checks | none (scripted) |
| Security | marker values, two users, the injection corpus, the shared-mode refusal, cluster isolation | none |
| Evaluation, recorded | the task set replayed | none |
| Evaluation, live | the held-out set, capped | yes: outside the drill |

### 10.5 Cut lines, agreed now

| # | If | Then |
|---|---|---|
| C1 | the targets of 7.2 are not met | GenUI ships marked preview, off by default; the release is not held |
| C2 | X44 is late | flow 5.2 moves out; the "?" is absent |
| C3 | M1 patches are late | refine regenerates the screen (slower, dearer) and is marked so |
| C4 | `oauth_user` is not proven by G1 | i9 ships with `token_user` only, documented as the supported mode |
| C5 | time runs short | W11 (embed) goes first, then step B of the chat panel |
| C6 | the refusal target (100%) is missed | **no release**: this one is not a cut line |

## 11. Decisions for the owner

| # | Decision | Recommendation | Why | The other way |
|---|---|---|---|---|
| **GD1** | Where does the model loop run? | **In SAJHA.** Validation, compile and bind stay in Drishti's process | one model gateway, one chatbot, one loop; all built in SAJHA today | a loop inside Drishti (Rachana's G6 as written): works without SAJHA, but a second gateway and a second chat |
| **GD2** | Do Drishti's own model providers remain for GenUI? | **No.** GenUI never calls `LLMFactory`. The older features (Ask about this page, the BI hooks) stay off in i9 and, when they return, use one provider that points at SAJHA | one place for keys, cost and audit | two places to configure and cap |
| **GD3** | How does SAJHA call Drishti as the person? | **`oauth_user`** as the supported mode, with `token_user` for development and as the fallback (C4). Shared modes refused | each person's own rights; both modes exist in SAJHA | a token-exchange mode like the embed's: neat, but new in SAJHA |
| **GD4** | What may a model see? | **`none` by default; `aggregates` per site by choice; rows never in i9** | the screen flows need no value; a bank can say yes to names long before figures | aggregates by default: better sentences, harder approval |
| **GD5** | The Drishti panel in the chat | **Link and snapshot card in i9; the live panel asked of SAJHA's session** | A and B need nothing risky; C is the right end state | wait for C: flow 5.5 slips |
| **GD6** | Streaming | **Progressive paint in i9** (skeleton when valid, panels as bound); painting while the model types later | no change to SAJHA's loop; most of the feeling of speed | full M3 now: needs partial tool arguments through SAJHA |
| **GD7** | Keeping a generated screen | **Ephemeral for 24 hours, per person; saved only through the normal proposal and review** | a generated screen that people use becomes an ordinary reviewed Sutra | save directly: unreviewed screens in packs |
| **GD8** | Which MCP era does Drishti's server speak? | **The stateless one (2026-07-28) only**, confirmed in S1 | the person's token rides on each call; any node of a cluster answers; no session state | both eras: twice the surface |
| **GD9** | The quality gate | **Section 7.2's targets on Drishti's held-out set; refusals at 100%; otherwise preview** | "it works" as a number | ship on demonstrations |
| **GD10** | Models and the cap | The owner names the models and a monthly cap; suggested shape: a fast model to draft, a stronger one to repair, a per-person daily token budget, a site ceiling; a separate small cap for evaluation | cost is known before the first user | one model for everything |
| **GD11** | Surfaces in i9 | **Console (5.1 to 5.3) and the chat link are the release; the embed and the card are wanted and first to cut** | proof before reach | everything or nothing |
| **GD12** | One screen over Drishti data and other SAJHA tools' data | **Not in i9** | two access models in one view need their own design | allow through `read_data` → `data_to_table`: values would pass through SAJHA |
| **GD13** | Actions that write, in generated Sutras | **Refused in i9** | read-only keeps the worst case at "the wrong panel" | allow with confirmation: SAJHA has approvals, but it is a second design |
| **GD14** | The size | **Accept about 135 bd for i9**, with G0's spikes starting now | the 8 days noted in the i7b plan were the in-process loop alone | keep 8 days: it buys the loop and none of sections 4, 6 and 7 |

Also to settle with SAJHA's session, not by the owner alone: the name of the GenUI planner; whether `sajha_ask`'s result will carry
the steps' structured results (Drishti reads the view id from one); and step C of the chat panel.
