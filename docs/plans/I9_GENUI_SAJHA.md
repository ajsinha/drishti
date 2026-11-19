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

**Amended on 2026-11-19** after the owner's review: five decisions are recorded in section 13, and two requirements were added
and are designed here. A generated view must be **portable**: it must run on any origin, with links that keep working (section
6). And SAJHA must have **its own tools that pull Drishti's inventory**, so that it knows what to call and what to do (section
5). For these, SAJHA's plugin system was also read (`sajha/core/plugins.py`, `plugin_catalog.py`, `plugin_signing.py`,
`sajha/tools/base_mcp_tool.py`, its tutorials 4 and 49), the Rachana Data Protocol (`spec/1/data-protocol/PROTOCOL.md`) and
EMBED_GUIDE's chapters on tokens, navigation and integrity.

## Contents

1. [The answer in one page](#1-the-answer-in-one-page)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [The architecture](#3-the-architecture)
4. [The contract between Drishti and SAJHA](#4-the-contract-between-drishti-and-sajha)
5. [The Drishti tool pack for SAJHA](#5-the-drishti-tool-pack-for-sajha)
6. [Portable views: a generated view runs anywhere](#6-portable-views-a-generated-view-runs-anywhere)
7. [The five flows, with examples](#7-the-five-flows-with-examples)
8. [Safety and governance](#8-safety-and-governance)
9. [Evaluation](#9-evaluation)
10. [What must exist before i9](#10-what-must-exist-before-i9)
11. [The risk register](#11-the-risk-register)
12. [The i9 plan](#12-the-i9-plan)
13. [Decisions for the owner](#13-decisions-for-the-owner)

## 1. The answer in one page

| Question | Answer |
|---|---|
| What is GenUI in Drishti? | A person asks in words; a model writes a **Sutra** (data, never code); Drishti checks it, compiles it, binds it to data **as that person** and paints it. The person keeps talking to refine it, and may save it through the normal review |
| Where does the model run? | Behind **SAJHA**. SAJHA holds the model providers, the keys, the budgets, the tool-use loop and the chatbot. Drishti holds no model key for GenUI |
| What does Drishti give SAJHA? | **One MCP server with twelve tools**: describe its capabilities, describe the catalogue, describe a table, list and get fragments, examples, read data, validate a Sutra, open a view, describe and patch an open view, explain a value |
| What does SAJHA have on its side? | **The Drishti tool pack**: a signed SAJHA plugin, built in Drishti's repository, whose tools pull Drishti's inventory for the person (functions, kinds, tables, fragment libraries), cache it, notice when it changed, and put an opened view into the chat. SAJHA's core is not changed (section 5) |
| Who is the caller? | Always the person. SAJHA calls Drishti with that person's own credential (SAJHA's per-user modes for proxied servers exist today). There is no service user that can read for everybody |
| What does the model see? | By default **names, types and descriptions only**: no row, no figure. Figures reach the screen from Drishti's server, never through the model |
| Where is a Sutra checked? | In Drishti's server, by the Rachana compiler, against **that person's catalogue**. Whatever the model wrote, a name the person may not read is an error |
| Where can a generated view run? | **Anywhere**: Drishti's console, SAJHA's chatbot, an intranet application, a desktop host, a static page. A view is **portable**: absolute addresses, a painter bundle the host serves, a short-lived token for one person and one view, links as typed references the host resolves, and a labelled snapshot where nothing live is possible (section 6) |
| What if there is no model, or no SAJHA? | The "Describe" box is absent, or falls back to today's rule-based Describe. Nothing else changes |
| How big is i9? | About **160 builder-days identified, plan on about 185** (section 12). The owner accepted about 135 on 2026-11-19; the two requirements added that day (portable views, the tool pack) bring about 44 more (decision GD22). The "about 8 days" in the i7b plan covered an in-process loop only |
| What reduces the risk? | Eight short **spikes** (section 12.3), accepted to start now: the first is one thin path from words to an opened view; S7 paints the same generated view in Drishti, on a static page of another origin and as a snapshot card, with working links; S8 installs the tool pack into a scratch SAJHA |

**The three largest risks** (the register is section 11): the identity path between the two products (R1); first-try validity on
Drishti's own profile and fragments, which nobody has measured yet (R3); and showing a *live* Drishti panel inside the chatbot, which
SAJHA's view sandbox does not allow as it stands (R5). The two additions bring their own
leading risks: a relative address or a console-only file slipping into a portable view (R18), and the pack depending on parts of
SAJHA that are not a stated plugin interface (R24).

## 2. Goals and non-goals

i9 is **GenAI integration, exclusively**. Nothing else rides in it.

### 2.1 Goals

| # | Goal | Proven by |
|---|---|---|
| G1 | **Describe a screen**: words in, an opened, live, linked Drishti view out, composed from fragments | flow 7.1; the evaluation of section 9 |
| G2 | **Explain this number**: any cell of any view explains itself, in a tree and in a sentence | flow 7.2 |
| G3 | **Refine by conversation**: "make the map Canada only" changes the open view by a small patch, with undo | flow 7.3 |
| G4 | **GenUI inside an intranet application** through the embed, in place, links intact | flow 7.4 |
| G5 | **The chatbot shows a Drishti panel**: Ask SAJHA answers with a Drishti view the person can open | flow 7.5 |
| G6 | **One contract** between the two products: a Drishti MCP server, called as the person | section 4; the contract test |
| G7 | **Governed**: masks, roles, row rules and data licences apply before anything reaches a model; cost capped; every call audited | section 8; the adversarial tests |
| G8 | **Measured**: quality is a number on a held-out task set, and a release gate | section 9 |
| G9 | **Optional**: with AI off, SAJHA absent or a model down, only these features disappear | section 8.8 |
| G10 | **Portable**: a generated view runs on any origin with working links, and says so when it is a snapshot or stale | section 6; spike S7; the cross-origin tests |
| G11 | **SAJHA knows Drishti**: SAJHA's own tools pull the inventory of functions, kinds, tables and fragments for the person, and know what to call next | section 5; spike S8 |

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
| Changing SAJHA's core from this repository | the Drishti tool pack is a plugin SAJHA loads; what its core is asked for is listed in 5.7 and 10.2 and built by SAJHA's own session |

## 3. The architecture

### 3.1 The actors

| Actor | What it is | Its part in GenUI |
|---|---|---|
| **The person** | a signed-in user with roles, a lens, row rules and masks | asks, reads, refines, saves |
| **Drishti console** | the thin console: pages, the embed library, the renderer | the Describe box, the conversation beside a view; **the first host** of a generated view, through the same descriptor as any other host |
| **Drishti server** | kernel, engines, the Rachana compiler (Java), `drishti-ai` | the MCP server; validates, compiles, binds and serves views; the only place data lives |
| **SAJHA** (sajhamcpserver) | the owner's MCP server: a governed tool catalogue, the intelligence layer, Ask SAJHA | runs the tool-use loop; holds the model providers, keys, budgets, policy and audit of model calls |
| **The Drishti tool pack** (in SAJHA) | a signed SAJHA plugin built in Drishti's repository | SAJHA's side of the contract: the handshake, the inventory per person with its cache, one thing in detail, the view placed into the chat (section 5) |
| **The Rachana tool pack** (in SAJHA) and **the generic Rachana MCP server** | the same four tools on two hosts (`data_to_table`, `sutra_examples`, `validate_sutra`, `render_dashboard`) | dashboards over **non-Drishti** data in the chatbot; the prompt kit both products share |
| **The model** | any provider SAJHA has configured | writes Sutras and patch documents; reads names, types and problem lists |
| **A host** | any page or program that shows a generated view: the console, Ask SAJHA, an intranet application, a desktop host, a static page | supplies the painter bundle, a token provider and, if it wishes, a link resolver; holds no data of its own (section 6) |

### 3.2 The picture

```
                              the person
     ┌───────────────┬────────────┴────────┬─────────────────────┐
     ▼               ▼                     ▼                     ▼
┌──────────┐  ┌──────────────┐   ┌──────────────────┐   ┌──────────────────┐
│ Drishti  │  │ intranet app │   │ desktop · static │   │ Ask SAJHA (chat) │
│ console  │  │              │   │ page             │   │                  │
└────┬─────┘  └──────┬───────┘   └────────┬─────────┘   └────────┬─────────┘
     │  every host: the painter bundle + a view token            │ session
     │  + absolute addresses + typed links (section 6)           ▼
     ▼               ▼                     ▼            ┌──────────────────────────┐
┌─────────────────────────────────────────────┐   ask   │ SAJHA                    │
│ Drishti, at its public address              │────────►│  tool-use loop, planner  │
│                                             │         │  tool catalogue, policy  │
│  MCP server (twelve tools) ◄────────────────┼─────────│  Drishti tool pack ──────┼──► the model
│  Rachana compile · bind as the viewer       │  tools  │  Rachana tool pack       │◄── (any provider)
│  ephemeral views                            │  as the │  model providers, budget │
│  view API · Data Protocol · events.v1       │  person │  audit                   │
└─────────────────────────────────────────────┘         └──────────────────────────┘
```

Two arrows cross the boundary between the products, and both carry the person's identity:

- **ask**: Drishti's server asks SAJHA to run a GenUI conversation for this person (only when the person started in Drishti).
- **tools**: SAJHA calls Drishti's MCP server for this person (always, whichever surface the person started on).

A third path carries no model at all: a **host** reads the view (ViewModel, tables, live patches) from Drishti's public address
with a view token for the person. It is the same path for every host, the console included.

### 3.3 Who does what to a Sutra

| Step | Where | By | Note |
|---|---|---|---|
| **Ground** (what may be used) | Drishti server | `describe_catalogue`, `describe_table`, `list_fragments`, `sutra_examples` | filtered to the person before the model sees a name |
| **Generate** | the model, called by SAJHA | SAJHA's governed model (`llm_factory().model(alias)`) | the only step that costs tokens |
| **Validate and fix** | Drishti server | the Rachana compiler in process, profiles `core` + `drishti`, catalogue mode | certain fixes are applied by the compiler, not the model |
| **Compile** | Drishti server | the same compiler | only a valid Sutra compiles |
| **Bind to data** | Drishti server | the engine's binder, **as the viewer** | row rules and masks here, as for every hand-written Sutra |
| **Paint** | any host | the one painter, from the bundle the host serves (section 6) | paints only what the ViewModel says; every address in it is absolute, every link a typed reference |
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
| A host page on another origin | supplying the bundle, a token provider and a link resolver | anything more than the person may read there: a view on a host is always masked, read-only and bound to one view |
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
| 1 | `describe_catalogue` | the tables, entity kinds, functions (mnemonics) and lenses **this person** may use: name, one-line description, row-count class, licence class | no |
| 2 | `describe_table` | columns: name, type, description, `masked`, unit; a column profile only where the values policy allows | no (by default) |
| 3 | `list_fragments` | fragment cards of the installed libraries, ranked by fit to a named table | no |
| 4 | `get_fragment` | one fragment's page and the schema of its `with:` | no |
| 5 | `sutra_examples` | the grammar card, the Drishti profile's card, reviewed examples by intent | no |
| 6 | `read_data` | a small, masked, aggregated result and a handle to it, as the person | **yes, only if the site allows** (GD4) |
| 7 | `validate_sutra` | `ok`, or problems with code, position, expected names and a fix; the fixed text when every fix is certain | no |
| 8 | `open_view` | an ephemeral view for this person as a **portable view descriptor** (section 6.3): id, expiry, absolute addresses, an absolute deep link, and a summary without values. No token | no |
| 9 | `describe_view` | the outline of an open view: ids, kinds, titles, what each panel reads (Rachana M2) | no |
| 10 | `patch_view` | a patch document applied atomically and re-validated; the new revision, or the problem list (Rachana M1) | no |
| 11 | `explain_value` | the explanation tree of one cell of an open view (Rachana X44), masked values as `•••` | figures the person already sees, only under `aggregates` |
| 12 | `describe_capabilities` | the handshake: Drishti's version, the contract version, the features that are on, the values policy, limits, the painter version, and the version tag of each part of the inventory | no |

Tools 3, 4, 5 and 7 carry **the same names and schemas** as Rachana's planned tools, so a prompt, a planner and an evaluation task
written for one host work on the other. `open_view` is Drishti's form of `render_dashboard` with `output: link`. Tools 1 to 5 and 12 are the **inventory**: each returns an `etag` and accepts
`if_none_match`, answering `{ "not_modified": true }` when nothing changed for this person. In SAJHA they are read by the Drishti
tool pack (section 5), not offered to a model.

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
  "descriptor": { "portable": 1, "viewmodel": "https://drishti.bank.example/api/genui/v1/views/gv_9c1e/viewmodel",
                  "data": { "protocol": "rdp", "v": 1, "base": "https://drishti.bank.example/api/rdp/v1" }, "…": "section 6.3" },
  "summary": { "panels": [ { "id": "tiles", "kind": "strip" }, { "id": "by-desk", "kind": "hbar", "rows": 6 } ],
               "reads": ["risk.var_by_desk", "risk.desk_pnl"], "generated": true } }
```

`url` is an absolute address into Drishti's console, from configuration. The descriptor is what any other host paints from; it
holds no token (section 6.3).

The handshake (tool 12), as the pack reads it at the start of a turn:

```json
{ "drishti": "2.9.11.i_9", "contract": 1, "values": "none", "painter": "2.6.0",
  "features": { "describe": true, "refine": true, "explain": true, "snapshot": false, "live_panel": true },
  "etags": { "catalogue": "7b1e…", "fragments": "c04a…", "kit": "19d2…" } }
```

### 4.3 What Drishti calls on SAJHA

Only one thing, and only when the person starts in Drishti: **"run this GenUI turn for this person"**.

| Call | What it is | Status in SAJHA |
|---|---|---|
| `sajha_ask` over `/mcp` | the Ask service as an MCP tool: `question`, `conversation_id`, `model`; runs as the MCP caller with that caller's tool access; progress events on the stream | exists (the tool ships disabled: `ai.ask.mcp_tool_enabled`) |
| a **GenUI planner file** | pins the turn to the ten tools of section 5.5 (the pack's four and six `drishti__` tools) and the Rachana pack, runs the handshake first, sets the step limit and the model aliases (a fast one to draft, a stronger one to repair) | planner files exist; this file is new and ships with the Drishti tool pack |
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
  "steps": [ { "name": "drishti_inventory", "ok": true }, { "name": "drishti__validate_sutra", "ok": true },
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
- **Scopes.** A Drishti token for SAJHA carries `genui:use` only: it reads through the twelve tools and asks the view API for a
  view token or a snapshot (section 6.4), never the rest of the API. The MCP endpoint and the view API are one protected
  resource, so one audience covers both.
- **Revocation.** Disabling the person in either product, or disconnecting, ends that person's tokens at the next call.

### 4.5 Streaming

| Leg | Carries | Form |
|---|---|---|
| SAJHA → Drishti server | the turn's progress: tool called, tool finished, answer text | progress events on the MCP call's stream (SAJHA sends these when the call asks for them) |
| Drishti server → browser | the screen | the embed's streamed-screen messages, which exist: `skeleton`, `panel-add`, `panel-replace`, `patch`, `done`, `error`. On any host they are read at the descriptor's absolute `stream` address, under the view token |
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
against Rachana's own tool pack to show that the shared tools answer alike. The Drishti tool pack has its own
vectors: its five tools against the same stub, as two users, from a signed package installed in the scratch SAJHA.

## 5. The Drishti tool pack for SAJHA

The owner's requirement (2026-11-19): SAJHA must have a set of tools that pull the inventory of fragments and of Drishti's other
capabilities, so that it knows what to call and what to do. This section designs that set as a **SAJHA plugin**, built in
Drishti's repository and installed through SAJHA's plugin catalogue. **Nothing in SAJHA's core is changed by it.**

### 5.1 What it is

| | |
|---|---|
| Name | `drishti-tools`: a SAJHA plugin (a folder with `plugin.json`, tool definitions and one Python module of `BaseMCPTool` classes), signed by the Drishti publisher key |
| Where its source lives | Drishti's repository, `integrations/sajha/drishti-tools/`, with its own tests and README |
| Where it runs | inside SAJHA, loaded by SAJHA's plugin manager; SAJHA's policy, rate limits, audit, cache and circuit breaker apply to its tools as to any tool (all of this exists in SAJHA today) |
| What it holds | no credential, no key, no address of its own, no model call. Its one setting is the id of the proxied server it speaks to (`drishti`) |
| How it reaches Drishti | only through the proxied `drishti__*` tools of section 4, called **in the caller's context**, so SAJHA attaches that person's own Drishti credential exactly as it does when the model calls them. The identity rules of 4.4 are unchanged |
| What it is not | a second contract. The MCP server of section 4 stays the one wire between the products; the pack is SAJHA's side of it |

Why a pack and not only the proxied tools: four jobs can only be done on SAJHA's side. Keeping an **inventory per person** in
SAJHA's cache and noticing when it changed; turning several grounding calls into **one prompt-sized card** (the largest saving
for the first panel, risk R7); checking, before a turn spends a token, that **this pack and this Drishti speak the same
contract**; and placing an opened view **into the chat** without its data or its token passing through the model.

### 5.2 The tools

| # | Tool | Called by | Job | Uses on the wire |
|---|---|---|---|---|
| P1 | `drishti_capabilities` | the planner, first step of a turn | **The handshake.** Is Drishti reachable for this person; contract version; which features are on (describe, refine, explain, snapshot, live panel); the values policy; limits; the painter version; the inventory's version tags | `describe_capabilities` |
| P2 | `drishti_inventory` | the model | **What Drishti can do, for this person, on one card**: functions and mnemonics, entity kinds, tables, lenses, the installed fragment libraries with their fragments, the grammar card. Sections can be chosen and filtered by a query. Cached per person; revalidated on every turn | `describe_catalogue`, `list_fragments`, `sutra_examples` |
| P3 | `drishti_describe` | the model | **One thing in detail**: a table (columns, types, units, masked flags, its contract), a function or mnemonic (what it takes and gives), a kind, a library, a fragment (its page and the schema of its `with:`) | `describe_table`, `get_fragment`, `describe_catalogue` |
| P4 | `drishti_show_view` | the model, in the chat only | **Puts an opened view into the chat** in the best form the chat supports: live panel, snapshot card or link (section 6.7). The model receives only `{ view, title, shown, as_of }` | the view API of 6.3: snapshot |
| P5 | `drishti_view_token` | Ask SAJHA's page, never a model | a view token for the live panel (section 6.4), fetched as the person | the view API of 6.3: token |

The jobs the pack does **not** wrap, because the wire tool already does them and the pack would add nothing: `validate_sutra`,
`open_view`, `describe_view`, `patch_view`, `explain_value` and `read_data`. The model calls these as `drishti__<tool>`.

### 5.3 The handshake, the cache and change detection

Every inventory tool on the wire (tools 1 to 5 and 12 of section 4.1) returns an `etag` and accepts `if_none_match`. The tag is a
hash of what **this person** may use, the installed libraries and their versions, the contract version and the values policy.

```json
{ "name": "drishti_capabilities", "arguments": { "pack": "1.0.0", "contracts": [1] } }
```

```json
{ "ok": true, "drishti": "2.9.11.i_9", "contract": 1,
  "features": { "describe": true, "refine": true, "explain": true, "snapshot": false, "live_panel": true },
  "values": "none", "limits": { "sutra_kb": 64, "panels": 60, "attempts": 3 }, "painter": "2.6.0",
  "etags": { "catalogue": "7b1e…", "fragments": "c04a…", "kit": "19d2…" } }
```

```json
{ "name": "drishti_inventory", "arguments": { "sections": ["tables", "fragments"], "query": "var desk", "max": 20 } }
```

```json
{ "tables":    [ { "name": "risk.var_by_desk", "about": "1-day 99% VaR per desk and business date", "rows": "small" } ],
  "fragments": [ { "use": "finance/var-tiles@1", "about": "VaR headline tiles", "fits": "a VaR figure" },
                 { "use": "core/ranked-bars@1",  "about": "a ranking as bars",  "fits": "rows, a label, a value" } ],
  "more": false, "etag": "7b1e…", "from_cache": true }
```

| Rule | |
|---|---|
| **Per person** | the cache key is the person and the tag; one person's inventory is never served to another (SAJHA's per-user cache exists) |
| **Revalidated every turn** | the handshake carries the current tags; a section whose tag is unchanged is served from the cache, a changed one is fetched. A new library, a new fragment or a right taken away is therefore seen at the next turn, with no restart on either side |
| **Names only** | the inventory holds what section 8.2 allows under `none`: names, types, descriptions. Nothing in it needs the `aggregates` setting |
| **A stale card cannot leak** | Drishti checks every name again at `validate_sutra` and binds as the person; a stale inventory can only produce a problem the model then repairs |
| **A version mismatch is an answer** | if Drishti's contract is outside `contracts`, the tool answers `ok: false` with one sentence ("this pack speaks contract 1, this Drishti speaks 2: update the pack") and the turn stops before any model call |
| **Not connected is an answer** | SAJHA's "connect your Drishti account" reply passes through unchanged |

### 5.4 One tool per job: the pack, the wire and the Rachana pack

Three sets of tools are near each other. Each job has exactly one tool in a given turn.

| Job | For a Drishti screen | For other data in SAJHA | Why not the other |
|---|---|---|---|
| What exists (tables, kinds, functions, lenses) | `drishti_inventory`, `drishti_describe` | the tools that own that data | the Rachana pack knows no catalogue |
| Which fragments exist, and one fragment's `with:` | `drishti_inventory`, `drishti_describe` (from the libraries installed **in Drishti**) | the Rachana pack's `list_fragments`, `get_fragment` (the libraries installed in SAJHA) | the two installations may differ in libraries and versions; a screen must use the ones of the host that will compile it |
| Grammar card and examples | `drishti_inventory` (section `kit`, with the Drishti profile) | the Rachana pack's `sutra_examples` (Core) | the Drishti profile's names exist only in Drishti |
| Check a Sutra | `drishti__validate_sutra` | the Rachana pack's `validate_sutra` | a Drishti Sutra is checked against the person's catalogue, which only Drishti has |
| Show it | `drishti__open_view`, then `drishti_show_view` | the Rachana pack's `render_dashboard` | Drishti binds as the person; SAJHA never holds the rows |
| Refine, explain | `drishti__describe_view`, `drishti__patch_view`, `drishti__explain_value` | the Rachana pack's own, when it has them | the open view lives in Drishti |
| Turn a tool's result into a table | not used | the Rachana pack's `data_to_table` | values would pass through SAJHA (GD12) |

**Shared schemas, not copies.** The request and result schemas of the jobs both hosts have (fragment card, fragment page,
problem list, examples) are Rachana's published schema files. Drishti's MCP server and the pack are generated from those files at
build time, and the contract test of 4.6 compares the three.

**The wire's inventory tools stay in the contract** (`describe_catalogue`, `describe_table`, `list_fragments`, `get_fragment`,
`sutra_examples`, `describe_capabilities`) for the pack and for any other MCP client, but in SAJHA they are **not offered to a
model**: there the pack's tools are the one way to the inventory.

### 5.5 How SAJHA's planner and the model find them

| Mechanism | What it does | Status in SAJHA |
|---|---|---|
| **The GenUI planner file** (shipped in the pack's folder) | offers a turn exactly ten tools: P1 to P4 and the six `drishti__` tools of 5.2; runs P1 as a fixed first step; names the drafting and the repairing model; sets the step limit | planner files exist; this one is new |
| **`ai.ask.mcp_allowed_tools`** | the same narrowing for a turn that Drishti starts over `sajha_ask` | exists |
| **Tool descriptions written as prompts** | each says when to call it, what to call next and what it never returns. They are tested in spike S4 and scored in the evaluation | new text |
| **The recipe inside the handshake** | P1's answer ends with the order of work for the features that are on (below), so the general planner can also follow it when a person asks for a Drishti screen in an ordinary chat | new |
| **Policy** | a policy file denies P5 and the wire's inventory tools to model turns; SAJHA leaves a refused tool out of what a model sees | policy rules exist; the file is new |

The recipe, as the model reads it:

```
To build a screen: drishti_inventory (what exists) → drishti_describe (only for what you will use)
  → drishti__validate_sutra (repeat until ok, at most 3) → drishti__open_view → drishti_show_view.
To change an open screen: drishti__describe_view → drishti__patch_view.
To explain a figure: drishti__explain_value.
Never invent a table, a column or a fragment: use only names the inventory returned.
```

### 5.6 Packaging, signing and publishing

```
integrations/sajha/drishti-tools/
├── plugin.json            name, version, min_sajha_version, the five tools
├── tools/drishti_tools.py the five tool classes (standard library and SAJHA's own modules only)
├── planners/genui.yaml    the GenUI planner            ┐ installed by the administrator,
├── policy/drishti.yaml    the policy for the upstream  ┘ or by the plugin (ask A5 of 5.7)
├── README.md              install, trust the key, connect, verify
└── plugin.sig             written by the signing step; never edited
```

| Step | How | Note |
|---|---|---|
| Build | `python tools/drishti.py sajha-pack build`: writes the schemas from the shared files, stamps the version, runs the pack's tests | no `requirements.txt`: nothing is installed by `pip` on the bank's SAJHA |
| Sign | `… sajha-pack sign` runs SAJHA's own signer (`plugin_signing sign`, Ed25519, a SHA-256 of every file) | the private key is the owner's, kept outside every repository; the public key `drishti.pub` ships with the release |
| Pack and publish | `… sajha-pack pack` writes `drishti-tools-<version>.zip`; it is placed in a source of SAJHA's plugin catalogue (a folder, a storage prefix or an index address) | part of Drishti's release checklist |
| Install | the SAJHA administrator trusts `drishti.pub`, reviews the package in the plugin catalogue and approves it | SAJHA's review-and-approve gate exists |
| Production rule | `plugins.allow_unsigned: false` in SAJHA, so an unsigned or changed pack does not load | exists; the deployment guide says so |
| Versions | the pack's version is its own; `contracts` says which contract versions it speaks; `min_sajha_version` is the version spike S8 proved | a pack and a Drishti of different releases either work or say why (5.3) |

### 5.7 What is asked of SAJHA's core

The pack is designed to need **no change**. These are confirmations and small additions for SAJHA's own session, in order of need.

| # | Ask | Needed for | Without it |
|---|---|---|---|
| A1 | State as supported for plugins what the pack uses today: calling another registered tool in the caller's context, the per-user cache, returning a view | every pack tool | it works, but a refactoring in SAJHA can break the pack (risk R24) |
| A2 | Confirm that a tool's view is shown to the person and is **not** part of what the model reads | P4's snapshot card | the chat shows the link only |
| A3 | A tool may be marked "never offered to a model" and "result never recorded" (cache, replay, audit payload) | P5 | the live panel in the chat waits; link and card are unaffected |
| A4 | A tool may ask for the caller's credential of a proxied server in both per-user modes (it exists for `oauth_user`) | P4, P5 (the view API is not an MCP tool, section 6.4) | `token_user` sites get the link only |
| A5 | A plugin may carry planner and policy files | one-step install | the administrator copies two files, as the README says |
| A6 | Ask SAJHA's page can host the painter for a "portable view" block, with the bundle vendored by SAJHA | the live panel (step C of 7.5) | snapshot card or link |
| A7 | A card in SAJHA's view sandbox can open an absolute link (directly, or by a message to the chat page) | the deep links of a snapshot card | the address is shown as text to copy |

## 6. Portable views: a generated view runs anywhere

The owner's requirement (2026-11-19): *generated UI will run just about anywhere, not necessarily on Drishti's web server. Links
must be built so the UI remains functional. The chatbot UI may not have these links locally hosted.*

### 6.1 The rule

A generated view must work when it is painted inside SAJHA's chatbot, an intranet application, a desktop host or a static page:
on **any origin**, where Drishti's console, its relative addresses, its static files and its sign-in cookie do not exist.

| A portable view never | It always |
|---|---|
| contains a relative address, or an address built by the painter from the page it runs on | names every endpoint as an **absolute** address taken from Drishti's configuration |
| loads a script, a style, a font, an icon or a map from a Drishti-relative path | takes every asset from **the painter bundle**, wherever that bundle is hosted |
| relies on a cookie | sends a **view token** for one person, one view, read-only, for minutes |
| carries a link as an address | carries a link as a **typed reference** that the host resolves, with a defined fallback |
| shows old data as if it were current | shows its as-of time, and says so when it is a snapshot or has lost its connection |

**The console is the first host, not a special case.** Drishti's own console opens a generated view through the same descriptor,
the same token and the same link resolver as every other host (decision GD15). What works in the console is then what works
elsewhere, and the difference between hosts is reduced to three things the host supplies: where the painter bundle is, how a
token is obtained, and what a link does.

### 6.2 What a portable view is

```
   any host page (console · Ask SAJHA · intranet app · desktop · static page)
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  painter bundle  (vendored by the host, or from a stated origin; SRI)    │
  │      ▲ descriptor            ▲ token provider        ▲ link resolver     │
  └──────┼───────────────────────┼───────────────────────┼───────────────────┘
         │ absolute addresses    │ view token            │ typed references
         ▼                       ▼ (header, never URL)   ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │ Drishti, at its public address                                           │
  │   view API: ViewModel, stream, follow a link, token, snapshot            │
  │   Rachana Data Protocol: tables, pages, queries   (as the person)        │
  │   events.v1 over SSE or WebSocket: live patches   (as the person)        │
  └──────────────────────────────────────────────────────────────────────────┘
```

| Part | What it is | Comes from | Status |
|---|---|---|---|
| **The descriptor** | a small JSON document: the view's id and the absolute addresses of everything below. No data, no secret | `open_view` (section 4.2), or the view API | new in i9 |
| **The ViewModel** | the compiled view: layout, panels, typed links, small bound content. Compiled and bound in Drishti's server as the person, as section 3.3 says | the view API, with the token | the ViewModel exists; the absolute, token-only route is new |
| **The data** | the tables the panels read: rows, pages, sorts, pivots | the **Rachana Data Protocol** at the descriptor's base, with the token. Row rules and masks are applied there, per call | the protocol is Rachana's (draft 1); Drishti serving it is due from i7b (B7) |
| **Live updates** | keyed patches on topics `rdp.<table>` | **events.v1** over Server-Sent Events or WebSocket at the descriptor's address. The token travels in the request header or in the `hello` / `auth` frame, never in the address | the events protocol and its vendored client exist; over the protocol's topics it is due from i7b |
| **The painter** | Elements Views (`<elements-view>` paints a ViewModel; `<rachana-view>` is the same painter with the compiler, for hosts that also run their own Sutras) | the painter bundle (section 6.6) | due from i7b (B3, B6); Drishti Render does this job in the embed today |
| **The view token** | a short-lived bearer token for one person and one view (section 6.4) | the host's token provider | embed tokens exist; the view claim is new |
| **The link resolver** | a function the host may supply (section 6.5) | the host; the painter has a built-in chain | navigation events exist; the contract is new |

The Data Protocol already has the rules this needs: a runtime calls only its configured base and the host's allow-list, and a
Sutra cannot introduce an origin; the token never goes in an address; `rdp.auth.required` and `rdp.unavailable` are defined
panel states. A portable view adds nothing to the protocol: it uses it at an absolute base.

**Drishti's public address** is configuration, never guessed: the API base (`drishti.embed.issuer`, `DRISHTI_PUBLIC_URL`,
which exists) and the console's base for deep links (`DRISHTI_CONSOLE_URL`). It is never taken from a request header and never
`localhost`, `127.0.0.1` or `0.0.0.0`. Without it, Drishti issues descriptors to its own console only, and Admin → Health says why.

### 6.3 The descriptor

`open_view` returns it, and any host can fetch it again from the view API. Addresses are examples; the shape is the contract.

```json
{ "portable": 1, "view": "gv_9c1e", "revision": 1, "title": "VaR by desk",
  "as_of": "2026-11-18", "expires": "2026-11-20T14:00:00Z", "generated": true,
  "api":       "https://drishti.bank.example/api/genui/v1",
  "viewmodel": "https://drishti.bank.example/api/genui/v1/views/gv_9c1e/viewmodel",
  "stream":    "https://drishti.bank.example/api/genui/v1/views/gv_9c1e/stream",
  "data":      { "protocol": "rdp", "v": 1, "base": "https://drishti.bank.example/api/rdp/v1" },
  "live":      { "protocol": "events.v1", "sse": "https://drishti.bank.example/api/events/v1/sse",
                 "ws": "wss://drishti.bank.example/api/events/v1/ws" },
  "token":     { "endpoint": "https://drishti.bank.example/api/genui/v1/views/gv_9c1e/token", "ttl_seconds": 300 },
  "painter":   { "api": 2, "min": "2.6.0", "bundle": "https://drishti.bank.example/embed/v2/render/2.6.0/",
                 "integrity": "https://drishti.bank.example/embed/v2/render/2.6.0/integrity.json" },
  "links":     { "follow": "https://drishti.bank.example/api/genui/v1/views/gv_9c1e/links",
                 "deep": { "base": "https://drishti.bank.example",
                           "view": "/genui/{view}", "entity": "/open/{kind}/{id}", "page": "/open/page/{page}" } },
  "snapshot":  { "endpoint": "https://drishti.bank.example/api/genui/v1/views/gv_9c1e/snapshot", "max_bytes": 2000000 } }
```

| Rule | |
|---|---|
| **No secret in it** | the descriptor may travel through a tool result, a model's context, a chat transcript and a log. It holds ids and addresses. The view id is unguessable **and** bound to the person: knowing it grants nothing (risk R11) |
| **No token in it** | a tool result is read by a model and recorded by SAJHA; rule 4.4 says no Drishti token reaches either. The descriptor says where a token is obtained; the host obtains it on its own channel (section 6.4) |
| **Every address absolute** | a guard test fails on any relative address in a descriptor or in a ViewModel served by the view API |
| **`painter.bundle` is a default** | a host that vendors the bundle ignores it and checks only `api` and `min` |
| **Versioned** | `portable: 1`; a painter that does not know the version shows the deep link instead of guessing |

### 6.4 The view token

**A view token is an embed token narrowed to one view.** Embed tokens exist: a signed token with an audience, an expiry, the
application, the person, the origin and the scope, checked on every call, always masked, never writing. i9 adds the scope
`embed:genui` and a `view` claim.

| Claim | Value | Effect |
|---|---|---|
| person | the signed-in user | every read is bound as that person, with their roles as they are **now** |
| view | the root view's id | the token reads this view and the views opened from it by following its links (6.5), nothing else |
| scope | `embed:genui` | read-only (GD13): no write, no save, no action that changes data |
| origin | the host page's origin, when the host is a browser page | a call from another origin is refused |
| application | the registered host application | its kinds, rates and licence allowances apply; it can narrow, never widen |
| expiry | 300 seconds by default (`drishti.embed.token-max-seconds`) | a stolen token is useful for minutes |

How a host obtains one. In each case the host proves who the person is on **its own channel**; the model and the tool results
are never part of it.

| Host | The token provider | Status |
|---|---|---|
| Drishti's console | the console's session, same origin | the session exists; the endpoint is new |
| An intranet application with a backend | its backend's token exchange | exists; needs the `embed:genui` scope |
| Ask SAJHA | the pack's `drishti_view_token` (section 5.2), which calls the descriptor's token endpoint with that person's Drishti credential from SAJHA's vault | new; needs asks A3 and A4 |
| A desktop host | its privileged process, after the person's sign-in; its pages are served from a registered origin (its application scheme) | the registration exists; `null` origins stay refused |
| A static page with no backend | the embed's sign-in window (`auth: 'popup'`): the bank's single sign-on, the token returned to the page's exact origin | exists |

**Refresh.** The painter asks the provider again one minute before the expiry, and once more on `rdp.auth.required`. On the
live stream it sends the new token in an `auth` frame; subscriptions are kept. If no token comes, every panel is marked "Signed
out: sign in again" with the as-of time of what it shows, and after `drishti.genui.portable.stale-clear-seconds` (default 900)
the values are removed. A token lives in memory only: never in an address, in browser storage, or in a log.

### 6.5 Links

**A link in a generated view is a typed reference, never an address.** Rachana's `link(id, kind, label)` already says "what it
opens is the host's"; i9 makes that the whole rule for everything a person can click.

| Type | Carries | Example |
|---|---|---|
| `entity` | a kind and an id | `{ "type": "entity", "kind": "counterparty", "id": "CP-NORTHBRIDGE" }` |
| `view` | a view id, and arguments | `{ "type": "view", "view": "gv_9c1e", "args": { "desk": "RATES" } }` |
| `action` | a read-only action of the view | `{ "type": "action", "action": "export", "params": { "format": "xlsx" } }` (also `refresh`, `explain`, `drill`) |
| `page` | a page that exists only in Drishti's console | `{ "type": "page", "page": "help", "topic": "VAR" }` |
| `outside` | an address that was found in the data | `{ "type": "outside", "url": "https://…" }` |

The resolver contract. The host may supply one function; it answers what to do with a reference, or nothing.

```ts
type Ref = { type: 'entity', kind: string, id: string }   | { type: 'view', view: string, args?: object }
         | { type: 'action', action: string, params?: object } | { type: 'page', page: string, topic?: string }
         | { type: 'outside', url: string };

type Resolution =
  | { how: 'in-place' }                              // draw it in the same container, with a Back bar
  | { how: 'host', run: () => void }                 // the host routes it: its own page, another tile, a chat message
  | { how: 'open', url: string, target?: 'new-window' | 'same-window' }   // an absolute address
  | { how: 'disabled', why: string };                // shown, not clickable, with the reason as its tooltip

type Context = { view: string, panel: string, source: 'link' | 'chart' | 'map' | 'key',
                 canInPlace: boolean, deepLink?: string, online: boolean, snapshot: boolean };

resolveLink?: (ref: Ref, ctx: Context) => Resolution | undefined;   // undefined: "no opinion"
```

**The fallback chain**, applied by the painter in this order:

| Step | What happens | When it is skipped |
|---|---|---|
| 0. **The host's resolver** | whatever it answers | none supplied, or it answered nothing |
| 1. **In place** (preferred) | the painter posts the reference to `links.follow` under the view token; Drishti checks the person's rights at that moment, opens the linked view as a child of this one and answers its descriptor; the painter draws it **in the same container** and pushes the previous screen on its back stack. No new window (the owner's embed-in-place rule) | the type is `page` or `outside`; the view is a snapshot; Drishti is unreachable; the application's kinds exclude it; the person may not read it |
| 2. **Deep link** | an absolute address into Drishti's console, built from the descriptor's `links.deep` (the public base from configuration); it opens in a new window, where the person signs in as themselves. The link grants nothing | no public address is configured; the site set `drishti.genui.links.deep: off`; the type is `action` or `outside` |
| 3. **Disabled** | the label stays readable, is not clickable (`aria-disabled`), and its tooltip says why: "Opens only in Drishti, which this page cannot reach" | never: it is the floor |

| Type | In place | Deep link | Otherwise |
|---|---|---|---|
| `entity` | yes | `/open/{kind}/{id}` | disabled |
| `view` | yes | `/genui/{view}` | disabled |
| `action` | runs in the painter against the view API | none | disabled ("Not available in a snapshot") |
| `page` | never | `/open/page/{page}` | disabled |
| `outside` | never | none | disabled unless the host's resolver opens it: an address from data is not followed on Drishti's say-so |

Rules: the painter never writes a relative `href` and never `#`. A deep link carries a kind and an id, never a value, a filter or
a token, and opens with `rel="noopener noreferrer"`. `drishti:navigate` still fires first and stays cancellable; today's
`navigation`, `consoleLinks` and `outsideLinks` options become named presets of the resolver, so there is one mechanism.

Lightweight examples, one per host:

```js
// 1. In Drishti's console: no resolver. Entities and views open in place; pages go to the console's own router.
render(el, descriptor, { tokenProvider: sessionToken, resolveLink: consolePreset });

// 2. In SAJHA's chatbot (live panel): stay in the chat panel; a console page opens Drishti in a new window.
render(el, descriptor, { tokenProvider: () => sajha.tool('drishti_view_token', { view: descriptor.view }),
  resolveLink: (ref, ctx) => ref.type === 'page' ? { how: 'open', url: ctx.deepLink, target: 'new-window' } : undefined });

// 3. In an intranet application: clients open in the application's own client page; everything else in place.
render(el, descriptor, { tokenProvider: getToken,
  resolveLink: (ref) => ref.type === 'entity' && ref.kind === 'counterparty'
      ? { how: 'host', run: () => router.go('/clients/' + ref.id) } : undefined });

// 4. Offline or a snapshot: no resolver can go in place. ctx.snapshot is true; the chain gives the deep link, else disabled.
//    In a snapshot card the deep links are written into the card when it is captured: <a href="https://drishti.bank.example/open/desk/RATES">
```

### 6.6 Assets: the painter bundle

| Rule | How |
|---|---|
| **One bundle, complete** | the painter, the Data Protocol and events clients, the vendored chart library, the styles, the fonts and the icons, in one versioned folder. Icons are inline in the modules; fonts are files of the bundle with a system-font fallback |
| **Addressed from itself** | every file the bundle loads is addressed relative to the bundle's own module address, so it works wherever the folder is placed. Nothing points at `/static/…` of a console |
| **Versioned and immutable** | a version always names the same bytes (this is the embed's rule today) |
| **Integrity-hashed** | `integrity.json` holds the SHA-384 of every file; the host pins the script with `integrity=` and the module graph with an import map's `integrity` (both exist for Drishti Render) |
| **Hosted by the host, or by a stated origin** | (a) **vendored**: the release ships `drishti-painter-<version>.zip` with `integrity.json`; the host serves it from its own origin. (b) **a configured absolute origin**: Drishti's own `/embed/v2/render/<version>/` (exists: public code, no data, immutable) or the bank's static server |
| **Never a CDN** | the owner's rule. No address of the bundle, its fonts or its maps points outside the bank |
| **Version check, not a broken paint** | the descriptor's `painter.api` and `painter.min` are checked first. An older bundle shows "This view needs painter 2.6.0; this page has 2.4.0" with the deep link. A newer ViewModel field an older painter does not know is ignored where the grammar marks it ignore-safe |
| **Maps and other large shapes** | files of the bundle, loaded on first use from the bundle's own folder; a host that vendors a subset gets the panel's data table in place of the map, with a line saying so |
| **Nothing remote in a generated Sutra** | no remote image, no `url:` source, no address built by an expression (section 8.4): a view cannot ask the host page to fetch from anywhere but Drishti |

### 6.7 Degraded modes, and the snapshot card

The painter and the pack choose the best mode the host allows, in this order, and always say which one is on the screen.

| Mode | The host can | The person sees | Marked |
|---|---|---|---|
| **Live** | run the painter; reach Drishti by HTTP and a stream | the live, linked view | as-of time; "Live" |
| **Live without push** | run the painter; reach Drishti by HTTP only (a proxy blocks the stream) | the same view, read again every 30 seconds | "Updated 10:42:05, refreshing every 30 s" |
| **Snapshot card** | show a self-contained card; no network (SAJHA's view sandbox: no network, at most 2 MB) | the view as it was, with absolute deep links | "**Snapshot** · as of 2026-11-18 · captured 10:42 EST · not live" |
| **Link** | show text | one sentence and an absolute link to the view in Drishti | the link's expiry |

While a view is on the screen: a lost connection marks every panel "Last updated 10:42 · reconnecting" at once; a token that
cannot be renewed is the "Signed out" state of 6.4. **A portable view is never silently stale.**

The snapshot card:

| | |
|---|---|
| What it is | one self-contained HTML document made by Drishti from the bound ViewModel: titles, tiles, tables and charts as they were, with the as-of and capture times and an "Open in Drishti" deep link. No script that calls anywhere, no external file |
| Two fidelities, chosen by size | **painted**: a frozen painter and the ViewModel inline (spike S5 says whether it fits 2 MB). **plain**: static markup, where a chart is shown as its data table with a line saying so. The card states which |
| How it reaches the chat | the pack's `drishti_show_view` fetches it from the descriptor's snapshot endpoint, as the person, and attaches it as the tool's view. **It is not part of what the model reads** (ask A2); the model gets `{ view, title, shown: "card", as_of }` |
| Links in it | written when it is captured, as absolute deep links (step 2 of the chain); in-place links and actions are shown disabled with "Open in Drishti for the live view" |
| What it must admit | it is a **copy**. It outlives the access check, like an export or a screenshot, and SAJHA keeps it with the conversation |
| So it is governed like an export | off by default (`drishti.genui.snapshot`, decision GD19); a permission `genui:snapshot`; always masked; a row cap per table ("first 200 of 5,312 rows"); the licence gate of 8.3; the person's name and the capture time on the card; an audit row |
| Its age | the absolute times are printed. A painted card also shows "Snapshot from 3 days ago" once it is older than a day |

### 6.8 Security of a portable view

| Concern | Rule |
|---|---|
| Who may read | the view token: one person, one root view and what its links open, read-only, masked, 300 seconds (6.4). Every call re-checks the person as they are now |
| Which pages may call | the **origin allow-list** is the registered applications' exact origins (the embed's list: no second list). Drishti answers CORS only on the portable routes, echoes only a registered origin, never allows credentials (so a cookie can never ride), and refuses `null` and wildcards |
| What the origin check is worth | it stops another website from using a token in a browser. It does not stop a script that stole a token; that is what the short life, the single view, read-only and masking are for |
| No data in addresses | tokens travel in the `Authorization` header or the stream's `hello` / `auth` frame (the stream is read with `fetch`, not `EventSource`, so a header can be sent). A query with filter values is a `POST` body (the Data Protocol's query document). A deep link carries a kind and an id only. Answers are `Cache-Control: no-store` |
| The host page can read the view | shadow roots are open, as in the embed. That is why a portable view always masks and never writes: a host script sees what the person could see on that host anyway |
| The view cannot run code in the host | a ViewModel is data; strings are painted as text; the painter evaluates nothing (section 8.5) |
| CSP for hosts | `script-src` the bundle's origin (or `'self'` when vendored), with no `unsafe-inline` and no `unsafe-eval`; `connect-src` Drishti's API origin, `https:` and `wss:`; `font-src` and `img-src` the bundle's origin; nothing else. The exact policy is the one the demo host runs under in EMBED_GUIDE; spike S7 runs the generated view under it |
| Revocation | disabling the person, removing the application or closing the view ends its tokens at the next call |
| Audit | Drishti records each token issued (person, application, origin, view), each link followed and each snapshot taken; never a token, never row data |

### 6.9 The hosts side by side

| Host | Painter bundle from | Token from | Links | Best mode |
|---|---|---|---|---|
| Drishti's console | its own origin | the session | in place; pages by the console's router | live |
| Ask SAJHA, page hosting the painter | vendored by SAJHA | `drishti_view_token` | in place inside the chat; pages as deep links | live |
| Ask SAJHA, view sandbox | none (the card is self-contained) | none | absolute deep links | snapshot card, else link |
| An intranet application | vendored, or Drishti's bundle address | its backend's exchange | its own resolver, then in place | live |
| A desktop host | vendored in the application | its privileged process | its own windows or in place | live |
| A static page on another origin | Drishti's bundle address, with SRI | the sign-in window | in place, then deep links | live |
| Anything that shows only text | none | none | the absolute link | link |

## 7. The five flows, with examples

### 7.1 Describe a screen

```
person        console        Drishti server            SAJHA                 model
  │ "VaR by desk,  │               │                      │                     │
  │  with P&L trend"──────────────►│── sajha_ask ────────►│── question + tools ►│
  │                │               │◄─ describe_catalogue ─┤◄─ drishti_inventory ┤
  │                │               │◄─ list_fragments ─────┤  (the pack, cached) │
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
through the normal proposal and review, after which it is an ordinary Sutra. The console is one host among others here:
it receives the descriptor that `open_view` returned and paints through it (section 6).

If three attempts do not produce a valid Sutra, the person gets the panels that were valid, a plain sentence about what could not
be built, and the inferred screen for the same tables as a floor. A broken screen is never shown.

### 7.2 Explain this number

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

### 7.3 Refine by conversation

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

### 7.4 GenUI inside an intranet application

The host page adds a box and a place to draw. The application's backend already exchanges its user for a short-lived Drishti embed
token (this exists). i9 adds one element, `<drishti-describe>`, and one scope, `embed:genui`, that an administrator grants per
application. The sketch shows the intended shape; the names are settled with the mock-ups.

```html
<drishti-describe id="ask" server="https://drishti.bank.example" placeholder="Describe the screen you need"></drishti-describe>
<div id="here"></div>
<script type="module">
  // the painter bundle, vendored by the host application on its own origin and pinned by its integrity hash (section 6.6)
  import { render } from 'https://portal.bank.example/vendor/drishti-painter/2.6.0/drishti-painter.js';
  const ask = document.getElementById('ask'), here = document.getElementById('here');
  ask.addEventListener('drishti:describe', e =>
    render(here, e.detail.descriptor, { tokenProvider: getToken, resolveLink: myLinks }));
</script>
```

- The screen is drawn **in place**; no new window opens unless the host asks for one.
- Every link, drill and event works on the application's origin, because each is a typed reference in the ViewModel that the
  host resolves: in place by default, the application's own page where its resolver says so, an absolute deep link otherwise
  (section 6.5).
- `server` is always stated. Nothing is taken from the address of the page or of the bundle, so the application may serve the
  bundle itself.
- An embed call always masks, whatever the person's roles; an embed token never writes; a view generated in an embed cannot be
  saved from the embed.
- The application can narrow what its users may ask about (its registered kinds), never widen it.
- With GenUI off, `<drishti-describe>` renders nothing and says so in an event.

### 7.5 The chatbot shows a Drishti panel

The person is in Ask SAJHA and asks "show me VaR by desk". The loop is the same as 7.1, started from SAJHA's side. Ask SAJHA's
page is not Drishti's: nothing Drishti serves is local to it, so everything the chat shows comes from the portable view.

```
person ─► Ask SAJHA ─► model ─► drishti_inventory … drishti__validate_sutra ─► drishti__open_view ─► { view, descriptor }
                                └► drishti_show_view { view } ─► the pack picks the mode, attaches the panel
person ◄─ answer: "I opened VaR by desk (6 desks)."  +  the panel  +  [ Open in Drishti ]   (an absolute link)
```

How the panel appears: the degraded modes of section 6.7, best first (decision GD5).

| Step | What the person sees | What it needs |
|---|---|---|
| **C. A live panel** | the real view inside the chat: live, linked, links opening in place inside the panel | Ask SAJHA's page hosts the painter from a bundle SAJHA vendors, with `drishti_view_token` as its token provider and SAJHA registered as a host application with its origin (asks A3, A4, A6 of 5.7) |
| **B. A snapshot card** | the view as it was, drawn inside the chat, marked "Snapshot" with its as-of and capture times, with absolute deep links | snapshots switched on for the site (GD19); the card fetched by the pack as the person, shown to the person and not read by the model (ask A2). SAJHA's views get no network and at most 2 MB, so the card cannot be live |
| **A. A link** | one sentence and a button that opens the view in Drishti, signed in as themselves | nothing new in SAJHA: it always works |

The model never sees the card or a token: `drishti_show_view` answers it `{ "view": "gv_9c1e", "title": "VaR by desk", "shown":
"card", "as_of": "2026-11-18" }`.

For a question about **non-Drishti** data in the same chat ("chart the FRED series"), the Rachana tool pack answers with
`render_dashboard`. The person sees one chat; the two kinds of dashboards look alike because one painter draws both.

## 8. Safety and governance

### 8.1 The order of the gates

Everything that can leave Drishti for a model passes these, in this order, before it leaves:

```
person's request
   └► module on? lens offered? function entitled? ─► role and row rules ─► column masks ─► data licence
        └► values policy (none | aggregates) ─► size cap ─► prompt masker (redact names and patterns) ─► the tool result
```

A model therefore never sees a table the person cannot open, a column that is masked for them, or a row their rules hide. The
gates are Drishti's existing ones; GenUI adds the last three.

### 8.2 What reaches a model

| Setting `drishti.genui.values` | The model receives | For |
|---|---|---|
| `none` (the default) | names, types, descriptions, units, row-count classes, problem lists, panel outlines | describing and refining screens: flows 7.1, 7.3, 7.4, 7.5 work fully |
| `aggregates` | also small aggregated results (`read_data`) and the figures of an explanation tree, masked, capped | the sentence of 7.2, "what stands out here" |
| rows | **never in i9** | |

A masked column has no profile, no examples and no value in any tool result under either setting. This is the rule the BI features
already follow ("the model, never a row"; narrative only in `aggregates` mode).

A **descriptor** holds addresses and ids only and may be read by a model. A **snapshot card** and a **view token** are for the
person and their host, never for a model: they travel on the host's own channel (sections 5.2, 6.4, 6.7) and are in no tool
result a model reads.

### 8.3 Data licences

Every external feed already records its licence (`redistribution: allowed | attribution | internal-only`). GenUI uses it:

- The catalogue carries the licence class of each table.
- Under `aggregates`, a table whose source is `internal-only` or not redistributable is **refused by `read_data`** unless the model
  that would receive it is marked local to the bank in SAJHA. Its names may still be used to build a screen, and the person still
  sees its values on that screen, as today.
- An embedded GenUI view shows such a table only if the application's registration allows it. IMF forecasts, Yahoo prices and news
  headlines stay internal demonstrations until the owner says otherwise.
- A generated view's provenance lists the sources and their attribution lines, as any view does.

### 8.4 Prompt injection from data

| Path | Defence |
|---|---|
| A cell says "ignore your instructions and …" | under `none`, no cell reaches a model at all. Under `aggregates`, values travel in a typed `data` field, truncated, and both SAJHA's system text and the planner state that tool output is data, never instructions |
| A description or a pack README carries an instruction | descriptions come from reviewed contracts and READMEs in the repository, not from loaded rows; the catalogue tools strip control text and cap length |
| The person's own words | the only free text; it can only produce a Sutra or a patch, which the compiler checks |
| A poisoned answer steers the model to another tool | a turn started for GenUI may call only the Drishti tools and the Rachana pack; every Drishti tool is read-only; SAJHA refuses a call to a tool that was not offered |
| A Sutra tries to leave | no `url:` source, no built address, no remote image, no script: refused by the grammar and by the "refs only" check for generated Sutras |

### 8.5 No code, ever

A Sutra has nowhere to put code: no script, HTML, CSS or handler text; expressions are bounded and total; strings are painted as
text. The compiler checks every key, every expression and every name against the person's catalogue before anything is bound. The
worst a wrong or hostile Sutra can do is show the wrong panel of data the person may already read.

Limits on a generated Sutra: 64 KB, 60 panels, the language's expression budgets, three validation attempts, sources by row-count
class, and refs only. Actions that write are refused (GD13).

### 8.6 Cost, model choice and limits

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

### 8.7 Audit

| Record | Written by | Holds | Never holds |
|---|---|---|---|
| The model call | SAJHA | user, model, tokens, outcome | |
| The tool call | SAJHA | user, tool, which identity was used for the proxied call | the token |
| The MCP call | Drishti (`mcp` rows) | user, tool, sizes, outcome, milliseconds | arguments or results |
| The GenUI turn | Drishti (new) | user, surface, the words, the catalogue hash, the Sutra or patch produced, the repairs, the view id | row data |
| The view | Drishti | "generated", by which model, from which words, when: in the provenance panel and, once saved, in the Sutra's `doc:` | |
| The view token | Drishti (new) | the person, the application, the origin, the view, when | the token |
| A link followed, a snapshot taken | Drishti (new) | the person, the view, the reference or the card's size and fidelity | row data |
| The pack's tools | SAJHA, as for any tool | the person, the tool, served from cache or fetched | the inventory |

A reviewer can replay any generated screen from its record: the same Sutra over the same catalogue gives the same screen.

### 8.8 Without AI

| Situation | What the person sees |
|---|---|
| `drishti.ai.enabled` or `drishti.genui.enabled` is off | no Describe box, no chat beside a view, no "?" sentence. The explanation tree still works. Admin → Health says "GenUI: off" |
| SAJHA is not configured or not reachable | the same, with "GenUI: SAJHA not reachable" in Health; the designer's Describe uses its rules, as today |
| The person has not connected their SAJHA account | a "Connect" prompt in the box |
| The model is down or the budget is spent | the turn ends with a plain sentence; open views are untouched |
| A view that was generated and saved | works always: it is an ordinary Sutra |
| A host cannot run the painter, or cannot reach Drishti | the degraded modes of 6.7: live without push, a labelled snapshot card, or an absolute link; never a blank panel and never old figures without their time |
| The Drishti tool pack is not installed in SAJHA, or speaks another contract version | the turn stops at the handshake with one sentence; nothing is generated |

No start-up step, test, drill or page outside GenUI depends on SAJHA or a model. Tests use SAJHA's mock provider and Drishti's mock
MCP transport, on loopback only.

## 9. Evaluation

"It works" is a number. Rachana's harness (due from i7b) runs tasks through the real tools and checks the result by code, not by a
model. i9 adds Drishti's own task set and runs the harness **through SAJHA**, so the planner, the tool descriptions and the identity
path are measured too.

### 9.1 The task set

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

### 9.2 Targets

| Measure | Target for i9 | Source of the target |
|---|---|---|
| First-try validity (no repair at all) | ≥ 85% | Rachana D30 |
| Valid after the compiler's certain fixes | ≥ 93% | AI_GENUI 6.1 |
| Valid within three attempts | ≥ 99% | Rachana D30 |
| Fulfilment (the assertions hold) | ≥ 85% | AI_GENUI 6.1 |
| Panels that come from fragments | ≥ 70% | Rachana L-18 |
| Correct refusals (access, masks, injected text) | **100%** | a security property, not a quality one |
| A patch touches only what was asked | ≥ 95% | new |
| Sentences of "explain" whose figures are all in the tree | 100% shown (others are dropped) | section 7.2 |
| Tokens and seconds per turn; time to first panel | reported per model | |

The percentages are aims until the first run (spike S6); the refusal row is not negotiable.

### 9.3 Regression

- **Recorded runs replay in the drill with no model call**: they protect the tools, the planner file, the patch path and the
  stream, and cost nothing.
- **A live run on the held-out set** before the release, and whenever the prompt kit, a fragment library, the planner, a tool
  description or the model changes. A drop of more than two points in first-try validity blocks the change.
- **The problem-code histogram** from each run says what to improve next: a clearer message, a certain fix, or a line on a card.
- **Two users, one question**: every access task runs as two users with different rights and must give different catalogues.

## 10. What must exist before i9

### 10.1 From i7b (Rachana and Elements)

| Needed by i9 | i7b item | Milestone | If it is late |
|---|---|---|---|
| The prompt kit, generated, with the Drishti profile's card | X49, AI_GENUI G1 | M2 (part I), M4 | no generation: i9 cannot start |
| Certain fixes on the frequent problem codes | B14 | M4 | lower validity, more model calls; i9 starts but its targets move |
| The validate-and-fix contract in the Java API | AI_GENUI 3 | M4 | i9 would build it: +5 days |
| `rachana describe` (M2) and patch documents (M1) | Sutra R1 and R2 | M2, M3 | flow 7.3 is cut to "regenerate the screen" |
| Explanation (X44) | EL phase 2 | M3 | flow 7.2 is cut |
| Fragment libraries `core`, `finance`, `ai` with cards, `with` schemas, `list_fragments` and `get_fragment` | B7, L-18 | M3, M5 | the model writes panels: more tokens, lower validity |
| Drishti's own fragments as libraries | Part C | M7 | generated screens do not look like the product's |
| The evaluation harness and its first task set, with published figures | B14, D30 | M4, M6 | i9 would build a harness: +6 days |
| The tool pack for SAJHA (Python on WebAssembly) and the generic server | B14, D31, D32 | M4 | flow 7.5 for non-Drishti data waits; Drishti's own flows do not |
| The renderer's streamed screens; `ref:` and `data:` in the server's pipeline | Elements, Part C | M1, M7 | exists for the stream; `ref:` is needed for any generated Sutra |
| Model access and a spending cap | PD15 | by M3 | no evaluation figures: the kit ships as preview |
| The painter (Elements Views), `<rachana-view>`, the Data Protocol client | B2, B3, B6 | M2 | portable views are painted by today's Drishti Render with the same descriptor, token and resolver (cut line C7) |
| Drishti serves the Data Protocol; live patches on `rdp.<table>` | B7 | with Part C | a portable view reads its tables through the embed's existing data routes at absolute addresses; the protocol follows |
| The shared schema files of the tools both hosts have | AI_GENUI 3 | M4 | the pack and the MCP server carry copies, compared by the contract test |

The i7b plan's own cut line applies: if D30's figures are not met, the kit ships marked preview, and i9 then ships GenUI marked
preview and off by default.

### 10.2 From SAJHA

| Needed | Status on 2026-11-19 |
|---|---|
| Proxied MCP servers with per-user credentials (`oauth_user`, `token_user`), the "connect your account" answer, the vault | exists |
| Proxied names `<prefix>__<tool>`; per-caller tool access; policy rules, rate limits, approvals; audit | exists |
| Model providers behind `LLMProvider` / `LLMModel`, governed: role policy, token budgets, cache, retries, fallback | exists |
| Ask SAJHA with a streamed answer; `sajha_ask` over MCP as the caller; planner files; the mock provider | exists |
| OAuth on `/mcp` (built-in authorization server or an external identity provider) | exists |
| The plugin system: `plugin.json`, tool definitions and Python tool classes, publisher signatures (Ed25519) with the verdicts trusted, untrusted, unsigned and invalid, the plugin catalogue with review and approval, `plugins.allow_unsigned` | exists |
| A tool running with the caller's own token (`auth.connected_account`), including the per-user sign-in of a proxied server | exists for `oauth_user`; to confirm for `token_user` (ask A4) |
| The Rachana tool pack installed in its catalogue | due from i7b (Rachana's repository) |
| **The Drishti tool pack** (`drishti-tools`) with its GenUI planner file and policy file | new, **built in Drishti's repository** (W24 to W26); installed by SAJHA's administrator from the plugin catalogue |
| The `drishti` proxied-server entry in a per-user mode; Drishti's publisher key trusted; SAJHA registered in Drishti as a host application with Ask SAJHA's origin | configuration, by the administrators of both |
| The asks A1 to A7 of section 5.7 | asked of SAJHA's session: A1 and A2 are confirmations; A3, A4 and A6 are needed only for the live panel; A5 and A7 are conveniences |

### 10.3 In Drishti already

The AI layer with SAJHA's abstractions and canonical types; the MCP client with policy, masking and audit; `BaseMcpTool`; the prompt
masker; the embed with token exchange, scopes and streamed screens; personal API tokens; data contracts per mnemonic and pack
READMEs (the source of the catalogue); roles, row rules, masks, lenses, function switches; the access log.

## 11. The risk register

Likelihood and impact are High, Medium or Low. "Early sign" is what to watch for; "Spike" names the proof of section 10.3.

| # | Risk | L | I | Mitigation | Early sign | Spike |
|---|---|---|---|---|---|---|
| R1 | **The identity path does not hold end to end**: Drishti is not yet an OAuth-protected MCP resource; two sign-ins confuse people; a token is bound to the wrong server | M | H | `token_user` with a personal API token works on day one; `oauth_user` proven against a stub before the server is built; one identity provider for both products makes both sign-ins silent | the stub's discovery fails in SAJHA's connect page | S1, S2 |
| R2 | **i7b's AI deliveries are late or below D30** | M | H | i9's contract work (MCP server, catalogue, identity, surfaces) does not need the kit and goes first; the mock provider scripts the model meanwhile; preview label as the cut line | M4's exit gate slips | – |
| R3 | **Validity on the Drishti profile and fragments is unmeasured** and may be well under the Core figures | M | H | measure twenty tasks early; fragments first; schema-constrained output for `with:`; grow certain fixes from the histogram | first-try under 70% in S6 | S6 |
| R4 | **SAJHA changes under us**: it is developed elsewhere, and names or behaviours move | M | M | the contract test of 4.6 runs against a pinned scratch copy and against its head; Drishti depends on MCP and on four documented behaviours only (per-user modes, `sajha_ask`, planner files, progress) | the contract test fails on head | S1 |
| R5 | **The Drishti panel in the chat is less than live**: SAJHA's view sandbox has no network and a 2 MB cap, and a live panel needs Ask SAJHA's page to host the painter | H | M | the degraded modes of 6.7: the absolute link always works; a labelled snapshot card where the site allows snapshots; the live panel is a portable view in the chat page, needing asks A3, A4 and A6 | the card exceeds 2 MB with the painter inline; SAJHA's session declines the page block | S5, S7 |
| R6 | **The model does not pick Drishti's tools** among several hundred in SAJHA's catalogue | M | M | a GenUI planner that offers only the Drishti tools and the Rachana pack; descriptions written and tested like prompts | wrong tools in S4's shortlist | S4 |
| R7 | **Slow first panel**: two products, several tool calls, then generation | M | M | catalogue and cards cached per person and hash; one combined grounding call if needed; a fast drafting model; status lines from the first 200 ms | more than 6 s to the skeleton in S3 | S3 |
| R8 | **A value leaks to a model** through a profile, an example, an error message or a summary | L | H | default `none`; one function builds every tool result and applies the gates of 8.1; a test that seeds marker values in masked columns and fails if any appears in any tool result, log or audit row | the marker test | – |
| R9 | **Prompt injection** through a description or, under `aggregates`, a value | M | M | section 8.4; read-only tools; a turn limited to the Drishti tools; an injection corpus in the evaluation with a 100% target | a refusal task fails | S6 |
| R10 | **Cost runs away**: loops, long catalogues, retries | M | M | step and attempt limits, budgets per user and per site, small cards, patches for refinement, recorded replays in tests | tokens per turn above 12,000 in S6 | S6 |
| R11 | **One person's view or conversation reaches another** (shared caches, the ephemeral store, a cluster) | L | H | view ids are unguessable **and** bound to the person; every read re-checks the person; no per-user state in globals; the two-user tests; the cluster test opens a view on another node | the session-isolation review | – |
| R12 | **Generated screens mislead** (a wrong chart, a sum of mixed units) | M | M | fragments carry the product's own choices; the `ai` lint rules; the Sutra and the data table behind every chart; the "generated" mark; review before saving | fulfilment under target | S6 |
| R13 | **Scope creep**: i9 is the first release with a visible AI feature and attracts additions | H | M | the non-goals of 2.2; the cut lines of 12.5 agreed now | new items appear in the plan | – |
| R14 | **A second way appears**: an in-process loop "for when SAJHA is absent", or a second chat in the console | M | M | decision GD1 recorded; a guard test that no GenUI code path calls `LLMFactory` directly | a builder brief that names a provider | – |
| R15 | **The stateless MCP era is not what SAJHA's proxy speaks best to an upstream**, or the cluster needs sessions | L | M | proven in S1 against a stub on both eras before the server is written | S1 | S1 |
| R16 | **Licensed data reaches an outside model** under `aggregates` | L | H | the licence gate of 8.3 is in the same function as the masks; internal-only sources refused unless the model is local; default `none` | the licence test | – |
| R17 | **Drill time and stability**: live model calls in tests | M | M | no live call in any drill: recorded replays and mocks only; the live evaluation is a separate, capped job | a test that needs a key | – |
| R18 | **A relative address or a console-only file slips into a portable view**: a link, an icon, a font, a map or a data route that only resolves on Drishti's console | M | H | the console is itself a host of the descriptor (GD15); a guard test refuses a relative address in any descriptor or ViewModel of the view API; the cross-origin test host runs with the console's static paths and cookie unavailable | any request to the host's own origin in S7 | S7 |
| R19 | **The host's network or policy blocks part of it**: a strict CSP, a proxy that cuts streams, a font or map that may not load | M | M | the modes of 6.7 (live without push, card, link); system-font fallback; a data table in place of a map; no inline script and no `eval` in the bundle | a CSP violation in S7 | S7 |
| R20 | **A view token leaks or is misused** on a page Drishti does not control | L | H | one person, one view, read-only, masked, 300 seconds, a registered origin, memory only; never in an address, a tool result or a log; the model never receives one | a token in any recorded payload in S8 | S7, S8 |
| R21 | **A snapshot outlives the access it was made under**, or is read as current | M | M | off by default; a permission; always masked; a row cap; the label, the as-of and capture times and the person's name on the card; an audit row; the link beside it | cards in transcripts where the site did not ask for them | S5 |
| R22 | **The host's vendored painter is older than the ViewModel needs** | M | M | `painter.api` and `painter.min` in the descriptor; a plain message with the deep link instead of a broken paint; ignore-safe additions; the bundle in every release with its version | a host report of a blank panel | S7 |
| R23 | **The public address is missing or wrong**, so deep links and descriptors point nowhere | M | M | configuration only, never the request; a start-up check and a Health line; descriptors issued to the console only until it is set; a link test in the deployment guide | a `localhost` address in any descriptor | S7 |
| R24 | **The pack depends on parts of SAJHA that are not a stated plugin interface** (calling a registered tool as the caller, the per-user cache, views) | M | M | ask A1; `min_sajha_version`; the pack's tests run against a pinned scratch SAJHA and against its head; the pack uses four entry points only | the pack's tests fail on SAJHA's head | S8 |
| R25 | **A stale inventory** in SAJHA's cache: a right was removed, a library changed | L | M | revalidated by tag on every turn; per person; Drishti checks every name again at validation and binds as the person, so a stale card cannot show data | a validation problem naming something the inventory offered | S8 |
| R26 | **The pack's signing key is mishandled**, or a site loads an unsigned or altered pack | L | H | the private key outside every repository, held by the owner; SAJHA's `plugins.allow_unsigned: false` in production; the tampered-package refusal is part of S8 and of the release checklist | a pack loaded with the verdict "unsigned" | S8 |
| R27 | **Three near sets of tools confuse the model**: the pack, the wire tools, the Rachana pack | M | M | one tool per job (5.4); the planner offers ten named tools; the wire's inventory tools are not offered in SAJHA; descriptions say what to call next | a wire inventory tool in S4's shortlist | S4, S8 |

## 12. The i9 plan

### 12.1 Work items

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
| W11 | The embed: `<drishti-describe>` with a stated `server`, the `embed:genui` scope, the stream and the descriptor under an embed token | 5 | W8, W18 |
| W12 | SAJHA's side, by its session and its administrator: the `drishti` upstream entry, the pack installed and its key trusted, the Rachana pack installed, the asks A1 to A7 of 5.7 (the page block for the live panel is the only real page change) | 5 | W1, W24 |
| **C. Governance** | | | |
| W13 | The gates of 8.1 in one function; the licence gate; the injection defences; limits; the GenUI audit record | 6 | W3 |
| W14 | Off and absent behaviour, Health, the read-only Admin → AI lines, every setting in the configuration document | 3 | – |
| **D. Proof and release** | | | |
| W15 | The evaluation: Drishti's task set, the harness run through SAJHA, recorded replays in the drill, the mock scripts | 8 | i7b M4 |
| W16 | Adversarial tests and the security review: the marker test, the two-user tests, the injection corpus, session isolation, the threat model | 5 | W13 |
| W17 | Documents: the GenUI part of AI_GUIDE; EMBED_GUIDE with a chapter for hosts (the descriptor, the token, the resolver, the bundle, CSP); one guide with its tutorial (console, CLI where there is one, YAML); the pack's README; CONFIGURATION; the deck; screenshots | 8 | – |
| **E. Portable views** | | | |
| W18 | The view API at Drishti's public address: the descriptor, the ViewModel and the stream at absolute addresses; the view token (the embed token with a `view` claim, its endpoint, three ways to prove the person, refresh); CORS and the origin check on these routes; the public-address settings and their Health line | 5 | W1, W4 |
| W19 | Links: typed references in every ViewModel the view API serves; the resolver contract and its presets in the painter; the fallback chain; "follow a link" under the view token; deep-link templates and the console's stable `/open/…` addresses | 6 | W18 |
| W20 | The painter bundle made portable: every asset addressed from the bundle, fonts and icons inside it, the release zip with `integrity.json`, the version check, no inline script and no `eval` | 3 | i7b M2 |
| W21 | The snapshot: the capture endpoint, the two fidelities, the label and times, the cap, masks, the licence gate, the permission and the switch, the audit row | 5 | W18 |
| W22 | The degraded modes and their states: the choice of mode, live without push, "reconnecting", "signed out", the clearing of values, the words for each | 3 | W18 |
| W23 | Portability tests: a test host on another origin under a strict CSP with the console's paths and cookie unavailable; the link matrix (five types, four hosts); the guard against relative addresses; the snapshot's size and label | 4 | W19, W21 |
| **F. The Drishti tool pack for SAJHA** | | | |
| W24 | The pack: the five tools of 5.2, the per-person cache with revalidation, the handshake and the version check, the planner and policy files, the tool descriptions; its tests on a scratch SAJHA with the mock provider | 7 | W25, S8 |
| W25 | On the wire for the pack: `describe_capabilities`; an `etag` and `if_none_match` on the inventory tools; kinds and functions in the catalogue; schemas generated from the shared files | 3 | W3, W5 |
| W26 | Build, sign, pack and publish: `tools/drishti.py sajha-pack …`, the publisher key's handling, the catalogue entry, the README, the step in the release checklist | 2 | W24 |
| | The spikes of 12.3 | 12.5 | – |
| | **Identified** | **about 160** | |
| | **Plan on** (15% for what is not yet seen) | **about 185** | |

Of the 160.5: 117 is the plan the owner accepted on 2026-11-19 (as "about 135" with its allowance); 30 is portable views (W18 to
W23, two days of documents in W17 and spike S7); 13.5 is the tool pack (W24 to W26 and spike S8). Much of what
a portable view needs exists in the embed today (registered origins, short-lived tokens, the integrity file, in-place
navigation), which is why the addition is not larger.

### 12.2 Order and milestones

| Milestone | Holds | Exit |
|---|---|---|
| **G0 Proofs** (may run during i7b; accepted to start now) | S1 to S8 | each spike's question answered in writing; the open decisions of section 13 taken |
| **G1 The contract** | W1, W2, W3, W5, W13, W14, W18, W25, the inventory tools of W24 | the contract test passes three ways; two users get two inventories through the pack; the marker test passes; a descriptor holds only absolute addresses; a view token is refused from an origin that is not registered |
| **G2 Describe, in the console** | W4, W8, W9, W19, W20 | flow 7.1 on the mock and on one real model; a generated view saved through review; **the same view opens on the test host of another origin, with every link doing what 6.5 says** |
| **G3 Refine and explain** | the patch half of W4, W6, W7, W10 | flows 7.2 and 7.3; undo |
| **G4 The other surfaces** | W11, W12, W21, W22, the rest of W24, W26 | flows 7.4 and 7.5 (steps A and B; C where SAJHA's page allows it); the pack installed from a catalogue, signed, into a SAJHA that refuses unsigned packs |
| **G5 Proof and release** | W15, W16, W17, W23 | the targets of 9.2 on the held-out set, or the preview label; the security review, which includes the portable view's token, origins and snapshot; one drill, a second if the first needed fixes |

Why this order: the contract and the identity path carry the largest risks and need nothing from the prompt kit, so they go first
and can start while i7b's last milestones finish. Describe comes before refine because refine edits what describe makes. The other
surfaces come last because they add reach, not proof, and are the first to cut. Portability is not a surface and is not
last: the descriptor, the token and the typed links are built with the contract and with the first screen, so that the console
never has a private way of painting a generated view.

### 12.3 Spikes to run early

Each is throw-away, time-boxed, on a scratch copy of SAJHA on a spare port, with synthetic data.

| # | Spike | Question it answers | bd | Can run |
|---|---|---|---|---|
| **S1** | **The thin end to end.** A stub MCP endpoint in Drishti with `describe_catalogue`, `validate_sutra` (today's checker) and `open_view` (a fixed link); SAJHA proxies it with `token_user` and a personal API token; SAJHA's mock provider scripts the three calls; two users | Do the two products talk? Does each user get their own catalogue? Which MCP era? Is the link opened as the person? | 2 | now |
| S2 | **Identity.** The stub publishes protected-resource metadata; SAJHA's `oauth_user` connects to it; the mirror sign-in from Drishti to SAJHA's `/mcp` | Which authorization server signs Drishti's tokens (the bank's identity provider or Drishti)? How many clicks does a person need? | 2 | now |
| S3 | **The stream.** `sajha_ask` over MCP with progress; Drishti relays it as `skeleton` and `panel-replace` | Do progress events arrive in order and in time? Seconds to the skeleton? | 1.5 | after S1 |
| S4 | **Tool choice.** The real SAJHA catalogue, a draft GenUI planner, twenty questions on the mock and on one model | Does the turn stay on the Drishti tools? What must the descriptions say? | 1 | after S1 |
| S5 | **The chat panel.** The snapshot card of S7 inside SAJHA's view sandbox, painted and plain | Does it fit SAJHA's limits? Is the card kept out of what the model reads? Can it open an absolute link? What would a live panel need from SAJHA's page? | 1 | after S7 |
| S6 | **First figures.** Twenty Drishti tasks on one model with a small cap, through the generic Rachana server proxied by SAJHA | First-try validity on the Drishti profile; tokens and cost per turn; can SAJHA cap in money? | 1.5 | after i7b M2, with model access |
| **S7** | **Portability.** One generated view (tiles, a table, a chart; links of every type) painted three ways from one descriptor: in Drishti's console; on a plain static HTML page served from another origin under a strict CSP, with the console's static paths and cookie unavailable; and as a snapshot card opened from a file | Does every link do the right thing in each place (in place, deep link, disabled with its reason)? Does anything ask for a relative or console address? Which addresses, headers and CSP lines are needed? How large is the card? | 2 | now, with Drishti Render and a hand-made descriptor; again with the painter after i7b M2 |
| **S8** | **The tool pack.** A first `drishti-tools` with the handshake and the inventory, signed with a scratch key, packed, installed from a scratch catalogue into a scratch SAJHA that refuses unsigned packs; SAJHA's mock provider calls both tools as two users against S1's stub | Does a plugin tool call a proxied tool as the caller? Is the cache per person, and is the second call "not modified"? Is a changed package refused? Which of the asks A1 to A7 are real? | 1.5 | after S1 |

S1, S2 and S7 need nothing from i7b and answer R1, R4, R15 and R18: they are the cheapest risk reduction available today. S8
follows S1 by a day and answers R24 before the pack is built.

### 12.4 Tests

| Layer | What | Model calls |
|---|---|---|
| Unit | each tool against fixtures; the gates function; the patch path | none |
| Contract | the vectors of 4.6, three ways | none (mock) |
| Flow | 7.1 to 7.5 in the browser on the mock provider, in the page walk and layout checks | none (scripted) |
| Security | marker values, two users, the injection corpus, the shared-mode refusal, cluster isolation | none |
| Portability | the descriptor's guard; the cross-origin test host under a strict CSP; the link matrix; token expiry, refresh and "signed out"; a lost stream; the snapshot's label, size and masks | none |
| The pack | its five tools on a scratch SAJHA: two users, the cache and "not modified", a contract mismatch, not connected, a tampered and an unsigned package refused | none (mock) |
| Evaluation, recorded | the task set replayed | none |
| Evaluation, live | the held-out set, capped | yes: outside the drill |

### 12.5 Cut lines, agreed now

| # | If | Then |
|---|---|---|
| C1 | the targets of 9.2 are not met | GenUI ships marked preview, off by default; the release is not held |
| C2 | X44 is late | flow 7.2 moves out; the "?" is absent |
| C3 | M1 patches are late | refine regenerates the screen (slower, dearer) and is marked so |
| C4 | `oauth_user` is not proven by G1 | i9 ships with `token_user` only, documented as the supported mode |
| C5 | time runs short | W11 (embed) goes first, then the live panel in the chat, then the snapshot card. The absolute link stays |
| C6 | the refusal target (100%) is missed | **no release**: this one is not a cut line |
| C7 | the painter of i7b is late | portable views are painted by today's Drishti Render, with the same descriptor, token and resolver; the painter replaces it without a change for hosts |
| C8 | SAJHA's session does not deliver asks A2 to A7 | the chat shows the link (without A2), has no live panel (without A3, A4 or A6); the inventory tools are unaffected |
| C9 | a generated view works only in Drishti's console: a relative address, a console file, a cookie | **a defect, never a cut**: absolute addresses, typed links and the token rules are not negotiable |

## 13. Decisions for the owner

| # | Decision | Recommendation | Why | The other way | Status |
|---|---|---|---|---|---|
| **GD1** | Where does the model loop run? | **In SAJHA.** Validation, compile and bind stay in Drishti's process | one model gateway, one chatbot, one loop; all built in SAJHA today | a loop inside Drishti (Rachana's G6 as written): works without SAJHA, but a second gateway and a second chat | **Accepted** (owner, 2026-11-19) |
| **GD2** | Do Drishti's own model providers remain for GenUI? | **No.** GenUI never calls `LLMFactory`. The older features (Ask about this page, the BI hooks) stay off in i9 and, when they return, use one provider that points at SAJHA | one place for keys, cost and audit | two places to configure and cap | Open |
| **GD3** | How does SAJHA call Drishti as the person? | **`oauth_user`** as the supported mode, with `token_user` for development and as the fallback (C4). Shared modes refused | each person's own rights; both modes exist in SAJHA | a token-exchange mode like the embed's: neat, but new in SAJHA | Open |
| **GD4** | What may a model see? | **`none` by default; `aggregates` per site by choice; rows never in i9** | the screen flows need no value; a bank can say yes to names long before figures | aggregates by default: better sentences, harder approval | **Pending**: the lead explains it to the owner first |
| **GD5** | The Drishti panel in the chat | **The modes of 6.7, best first: a live panel where Ask SAJHA's page hosts the painter (asked of SAJHA's session); a labelled snapshot card where the site allows snapshots; the absolute link always** | the link needs nothing; the card and the live panel are the same portable view at two strengths | wait for the live panel: flow 7.5 slips | Open |
| **GD6** | Streaming | **Progressive paint in i9** (skeleton when valid, panels as bound); painting while the model types later | no change to SAJHA's loop; most of the feeling of speed | full M3 now: needs partial tool arguments through SAJHA | Open |
| **GD7** | Keeping a generated screen | **Ephemeral for 24 hours, per person; saved only through the normal proposal and review** | a generated screen that people use becomes an ordinary reviewed Sutra | save directly: unreviewed screens in packs | Open |
| **GD8** | Which MCP era does Drishti's server speak? | **The stateless one (2026-07-28) only**, confirmed in S1 | the person's token rides on each call; any node of a cluster answers; no session state | both eras: twice the surface | Open |
| **GD9** | The quality gate | **Section 9.2's targets on Drishti's held-out set; refusals at 100%; otherwise preview** | "it works" as a number | ship on demonstrations | Open |
| **GD10** | Models and the cap | The owner names the models and a monthly cap; suggested shape: a fast model to draft, a stronger one to repair, a per-person daily token budget, a site ceiling; a separate small cap for evaluation | cost is known before the first user | one model for everything | **Accepted** (owner, 2026-11-19) |
| **GD11** | Surfaces in i9 | **Console (7.1 to 7.3) and the chat link are the release; the embed and the card are wanted and first to cut** | proof before reach | everything or nothing | Open |
| **GD12** | One screen over Drishti data and other SAJHA tools' data | **Not in i9** | two access models in one view need their own design | allow through `read_data` → `data_to_table`: values would pass through SAJHA | Open |
| **GD13** | Actions that write, in generated Sutras | **Refused in i9** | read-only keeps the worst case at "the wrong panel" | allow with confirmation: SAJHA has approvals, but it is a second design | **Accepted "for now"** (owner, 2026-11-19): read-only in i9; to be looked at again afterwards |
| **GD14** | The size | **Accept about 135 bd for i9**, with G0's spikes starting now | the 8 days noted in the i7b plan were the in-process loop alone | keep 8 days: it buys the loop and none of sections 4, 8 and 9 | **Accepted** (owner, 2026-11-19): about 135 bd and the spikes start. The two additions of the same day are GD22 |
| **GD15** | Is a generated view portable by construction? | **Yes: every host, Drishti's console included, paints it from the same descriptor, with a view token and typed links** (section 6) | the owner's requirement; and what works in the console is then what works elsewhere | the console keeps its relative addresses and cookie, and other hosts get an adapted copy: two ways, and the second one breaks unnoticed | **Required** (owner, 2026-11-19); the design to confirm |
| **GD16** | Where does a view token travel? | **Never in a tool result. The descriptor names a token endpoint; each host obtains the token on its own channel as the person** (6.4) | a tool result is read by a model and recorded by SAJHA; rule 4.4 keeps Drishti's tokens from both | the token inside `open_view`'s result: one step fewer, and a credential in a model's context | Open |
| **GD17** | What does a link do when it cannot open in place? | **The host's resolver, then in place, then an absolute deep link to Drishti's console, then a disabled link that says why.** Deep links on by default; they carry a kind and an id only | functional everywhere, in place where possible, never a dead link | deep links off by default: nothing leaves the host, and more links are disabled | Open |
| **GD18** | Who serves the painter bundle? | **The host vendors it, or loads it from Drishti's own versioned address; pinned by its integrity hash; never a CDN** | no dependence on Drishti's console pages; the owner's vendoring rule | only from Drishti: simpler, but a host that cannot reach that address at page load has no painter | Open |
| **GD19** | Snapshots | **Off by default per site; a permission; always masked; a row cap; labelled with as-of and capture times** | a snapshot is a copy of data that outlives the access check, and SAJHA keeps it with the conversation | on by default: a richer chat on day one, and copies of figures in transcripts nobody decided to keep | Open |
| **GD20** | How does SAJHA learn what Drishti can do? | **The Drishti tool pack: a signed SAJHA plugin built in Drishti's repository; its tools hold the inventory and place views in the chat; the Sutra jobs stay on the wire tools; SAJHA's core is not changed** (section 5) | the owner's requirement; one tool per job; a pack can be versioned and released with Drishti | wrap all twelve wire tools in the pack: one name style, twice the code and two tools per job | **Required** (owner, 2026-11-19); the design to confirm |
| **GD21** | The pack's signing key | **One Drishti publisher key, held by the owner outside every repository; production SAJHA refuses unsigned packs** | SAJHA runs a plugin's code in its own process | unsigned in production: any change to the folder would load | Open |
| **GD22** | The size after the two additions | **About 160 bd identified, plan on about 185** (GD14's 135, plus about 30 for portable views and about 13.5 for the pack, with the allowance) | both additions are designed here and sized item by item (12.1) | keep 135: portable views or the pack would have to displace the embed, the snapshot and part of the evaluation | Open |

Also to settle with SAJHA's session, not by the owner alone: the name of the GenUI planner; whether `sajha_ask`'s result will carry
the steps' structured results (Drishti reads the view id and the descriptor from one); and the asks A1 to A7 of section 5.7, of
which the page block for the live panel (A6) is the only change to a page of SAJHA.
