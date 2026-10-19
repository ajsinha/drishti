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
# AI management: one console for every AI setting, use and review

Status: **design, not built.** Planned for **2.7.0** (wave I, with the AI features it manages; see
[BI_DESIGN.md §12.2](BI_DESIGN.md#122-the-three-waves)). Asked by the product owner on 2026-10-18: "for AI in general we
will need an elaborate UI to manage". Names marked **(new)** do not exist today.

## 1. Where we are

The AI layer is complete underneath ([AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md)): providers
and models from `config/ai/*.yaml` (21 types: OpenAI, Anthropic, Azure OpenAI, Gemini, Mistral, Cohere, Bedrock, Ollama,
the OpenAI-compatible presets and the mock), a policy (`drishti.ai.policy.*`: masks, limits, cost per user and day, roles
per feature, audit), MCP servers and their tools, and features that use them (`ask`, the seven BI features, the API).
But managing it is files and environment variables: **Admin → AI** is a read-only page (on or off, providers with
health, MCP servers, a *Test connection* button), and the policy is configuration only ("Admin does not edit these").
Wave I (2.7.0) adds more that needs managing: AI grounding and golden questions, AI-drafted measures, reports and Transforms
under review, the Sajha MCP connector, and GenUI ([GENUI.md](GENUI.md)).

## 2. Principles

- **Everything in the UI, files stay the truth.** Each change made in the console is written to an overlay file under
  `<data dir>/ai/` (new; as `packs/added.yaml` is for packs), audited, and read without a restart. Shipped files and
  environment variables stay valid; the page shows where each value comes from (file, overlay, default) and a value from
  the environment cannot be overridden in the UI.
- **No secret is typed into the console.** A key is named by its environment variable (`${OPENAI_API_KEY}`) or a secret
  reference; the page shows whether it is set and works, never its value.
- **See what is sent.** Every place that changes what reaches a model offers a preview of the masked prompt for a chosen
  role.
- **Least surprise.** Policy changes that widen access (a feature opened to a new role, a limit raised, values mode moved
  from names to aggregates) can require a second administrator's approval (`drishti.ai.manage.four-eyes`, new, default on
  in production profiles).

## 3. Admin → AI, rebuilt (administrators; `ai:admin`)

| Tab | What it shows | What it changes |
|---|---|---|
| **Overview** | on or off; calls a minute and errors (an activity chart like Admin → Health's), cost today against budgets, providers up or down, the next retries, open reviews | the master switch (`drishti.ai.enabled`, when not set by the environment) |
| **Providers and models** | each provider: type, base URL, proxy, TLS, health, latency, the key's state (set, working, last used); its models with context size, prices, capabilities (chat, tools, JSON, embeddings) | add a provider from a form generated from its type's settings (the same `SettingSpec` style as Admin → Connectors), edit, disable, **Test connection**; set prices; a fallback chain per model (try B when A is down) |
| **Features** | every feature (`ask`, `bi-draft`, `bi-ask`, `bi-explain`, `bi-suggest`, `bi-explain-checks`, `bi-helper`, `subscription-narrative`, GenUI's `genui`, `api:*`) with its model, roles, limits, values mode, calls and cost | the model per feature, roles, per-minute and per-day limits, the cost cap, the values mode (`names` or `aggregates`), on or off per feature |
| **Data protection** | the masked fields (`drishti.security.redact` plus `ai.redact`), the extra patterns, a **masked-prompt preview** for any page or dataset and role | extra mask patterns; test a pattern against sample text; which features may send aggregates |
| **MCP servers and tools** | servers (stdio and HTTP), health, their tools with descriptions and schemas, calls a minute | add, edit, test a server; allow or deny each tool, per role; the Sajha MCP connector's settings |
| **Agents and GenUI** | the agents and MCP clients that call Drishti's own tools (`compose_ui` and the BI tools), what they did | which agents may call which tools; GenUI's policy is edited on Admin → GenUI and linked from here |
| **Usage and cost** | calls, tokens and cost by user, feature, model and day; the top users; trends; refusals by code (`DRS-4017`, `DRS-4018`) | budgets per user, role or feature, and alerts when a budget nears its end (to the Alerts mailbox) |
| **Quality** | golden questions per provider and model: pass rate, answers that changed since the last run, the measures each answer used | add or edit a golden question (question, expected measures or answer), run them on demand or on a schedule, compare two models side by side |
| **Audit** | one row per call (user, feature, model, sizes, tokens, cost, outcome, time), searchable and exportable; never the prompt or the answer by default | an opt-in to keep masked prompts and answers for N days (`drishti.ai.audit.keep-text`, new, off), with its own retention and a banner on every AI surface while it is on |
| **Playground** | a chat through the policy **as a chosen role**: the masked prompt actually sent, the model's answer, tokens, cost, latency | nothing kept (audited as `ai-test`); used to try a model or a prompt before a feature uses it |

## 4. Review → AI drafts (authors and approvers)

One queue (new) for everything AI writes that becomes part of Drishti: measures from words, reports from intent,
AI-drafted Transforms, GenUI promotions, help text. Each draft shows the request, the model, the masked prompt (when kept),
the draft as YAML and as a preview, a diff against what it replaces, its checks (validator, tests, golden questions); the
actions are edit, approve (four eyes as for Sutras), reject with a reason, and *ask again* with a note. Approved drafts go
through the existing Build workbench propose → approve flow; nothing an AI writes is published without it.

## 5. For everyone: My AI (new, in the user menu)

What the person may use (features, models, limits left today), their recent AI calls (feature, time, outcome; their own
prompts only if text is kept), and their drafts waiting for review.

## 6. APIs (new; `ai:admin` unless noted)

`GET/PUT /api/v1/admin/ai/providers[/{name}]`, `POST /api/v1/admin/ai/providers/{name}/test`,
`GET/PUT /api/v1/admin/ai/features[/{feature}]`, `GET/PUT /api/v1/admin/ai/protection`, `POST /api/v1/admin/ai/preview`
(masked prompt for a role), `GET/PUT /api/v1/admin/mcp/servers[/{name}]`, `PUT /api/v1/admin/mcp/servers/{name}/tools`,
`GET /api/v1/admin/ai/usage`, `GET/PUT /api/v1/admin/ai/budgets`, `GET/POST /api/v1/admin/ai/golden[/{id}/run]`,
`GET /api/v1/admin/ai/audit`, `POST /api/v1/admin/ai/playground`; `GET /api/v1/ai/drafts` and
`POST /api/v1/ai/drafts/{id}/approve|reject|retry` (authors, approvers); `GET /api/v1/me/ai` (signed in). Every write is
audited, versioned in the overlay and refused when the value comes from the environment.

## 7. Build plan (2.7.0, console lane with its server endpoints)

| Step | Scope | Accepted when |
|---|---|---|
| M1 | Overlay store under `<data dir>/ai/`, reload without restart, source of every value, four-eyes for widening changes | a provider added in the UI answers a Test within 5 s with no restart; a widening change waits for a second administrator |
| M2 | Overview, Providers and models (forms from settings, fallback chains), Features | an administrator adds a provider, points `bi-ask` at it, limits it to one role, all without editing a file |
| M3 | Data protection with the masked-prompt preview; MCP servers and tools with per-role tool rules | the preview for a role without `raw` shows every redacted field masked; a denied tool is refused (`DRS-4018`) |
| M4 | Usage and cost, budgets and alerts; Audit (opt-in text retention) | a budget at 90 % sends an alert; the audit exports a day's calls as CSV |
| M5 | Quality: golden questions per model, scheduled runs, side-by-side | a model change that breaks a golden question is shown before it is made the default |
| M6 | Playground; Review → AI drafts; My AI | an AI-drafted measure is reviewed, edited and approved in the queue and appears in the model |
| M7 | Docs and tutorial (UI, CLI, YAML), browser tests for every tab | a new administrator sets up AI from the tutorial alone |

## 8. Tests

Browser tests per tab (Chromium, Firefox, WebKit, in the style of the console's `*_browser.py`), all against the mock
provider (`type: mock`) so no network is used; server tests for the overlay (source precedence, environment wins,
audit rows, four-eyes), the preview's masking against the policy's own masking (one implementation, one answer), and the
budget and alert path.
