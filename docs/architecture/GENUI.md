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
# GenUI: screens composed for an AI agent, bound to live data, streamed to another application

Status: **design, not built.** Planned for **2.6.0** (wave D, see [BI_DESIGN.md §12.2](BI_DESIGN.md#122-the-three-waves)
and [IMPLEMENTATION_PLAN.md › Roadmap](IMPLEMENTATION_PLAN.md#roadmap)). Off by default when built.
Names marked **(new)** do not exist in the code today; every other class, file and endpoint named here was checked
against the repository on 2026-10-18.

## Contents

1. [The ask](#1-the-ask)
2. [The idea in one picture](#2-the-idea-in-one-picture)
3. [Principles and non-goals](#3-principles-and-non-goals)
4. [What the code already gives us](#4-what-the-code-already-gives-us)
5. [The fragment catalogue: blocks with typed slots and a data contract](#5-the-fragment-catalogue-blocks-with-typed-slots-and-a-data-contract)
6. [The composer: one tool, no written code](#6-the-composer-one-tool-no-written-code)
7. [Ephemeral Sutras and live URLs](#7-ephemeral-sutras-and-live-urls)
8. [Data wiring](#8-data-wiring)
9. [Streaming to the host application](#9-streaming-to-the-host-application)
10. [Dynamic forms](#10-dynamic-forms)
11. [Security and governance](#11-security-and-governance)
12. [Configuration](#12-configuration)
13. [APIs, tools and error codes](#13-apis-tools-and-error-codes)
14. [Build plan for 2.6.0](#14-build-plan-for-260)
15. [Tests](#15-tests)
16. [Documentation to write or update](#16-documentation-to-write-or-update)
17. [Risks](#17-risks)
18. [Open questions](#18-open-questions)

---

## 1. The ask

The product owner, verbatim:

> i have a need where i can save templatized sutra fragments and then based on some conclusion by an agent sutras will
> be arranged - even variable names changed etc - into a new sutra basically creating a form dynamically in response to
> an AI agent's ask. After that we need to wire data into it automatically and resulting HTML/JS needs to be streamed to
> the calling application with fully consistent UX experience which feels like a generated UI - i call it genui - with
> full bindings to data and having fully live URL etc. how our architecture can support it - basically now drishti
> becomes a server to generate UX and stream that for display somewhere else

Put another way: an AI agent, working in some other application (a chat assistant, a CRM, a risk workflow), decides that
its user needs to *see* something: "the cash flows of this swap with resets as percentages, next to the counterparty's
limits". Instead of answering in text, or writing HTML of its own, it asks Drishti for a screen. Drishti puts that screen
together from **governed, saved fragments**, binds it to **live data the user may see**, gives it a **URL**, and
**streams** it into the calling application, where it looks and behaves like any other Drishti view.

Drishti becomes a **UX server**: it already draws screens for its own console and, through
[Drishti Elements](ELEMENTS.md), inside other web applications. GenUI adds a third source of screens next to written
Sutras and inferred ones: **composed** ones.

## 2. The idea in one picture

```
 AGENT (anywhere: chat assistant, workflow, the console's Ask)             DRISHTI
 ┌─────────────────────────────────────────┐
 │ 1 list_fragments(kind="trade")           │──── tools over MCP or REST ──►┌──────────────────────────────────────┐
 │   describe_fields(kind="trade")          │◄─── labels, slots, contracts ─│ Fragment catalogue (new)             │
 │                                          │     (never rows)              │  SutraBlocks library + slots:        │
 │ 2 compose_ui(intent, fragments[], bind)  │──────────────────────────────►│ Composer (new)                       │
 │                                          │                               │  assemble YAML → SutraBuilder        │
 │                                          │                               │  → ElCompiler → check paths, masks,  │
 │                                          │◄── {id, url, embed, problems} │  fragment policy → audit             │
 │ 3 hands the URL or the element to its    │                               │ Ephemeral store (new), TTL           │
 │   host page                              │                               └──────────────────┬───────────────────┘
 └─────────────────────────────────────────┘                                                  │
                                                                                               │ Sutra (not registered)
 HOST PAGE (browser)                                                                           ▼
 ┌─────────────────────────────────────────┐   4 GET /embed/v1/genui/{id}    ┌──────────────────────────────────────┐
 │ <drishti-genui genui="g_7Q…" (new)>      │──────────────────────────────►│ ViewPipeline.preview(sutra, ref, …)  │
 │   skeleton → panels → live ticks         │◄═ skeleton, panel, frame ═════│  as the VIEWER: redactor, mayOpen    │
 │   same CSS tokens, same panel HTML       │   (HTML from panel(p) macros) │ ViewStream (rebuilds with the Sutra) │
 └─────────────────────────────────────────┘                                └──────────────────────────────────────┘
```

The agent never writes YAML, HTML or JavaScript. It picks fragments by name, fills their typed slots, and names what the
screen is bound to. Everything after that is Drishti's ordinary machinery.

## 3. Principles and non-goals

### Principles

1. **Composed, not generated.** A GenUI screen is a Sutra assembled from fragments that people wrote and reviewed. No
   code an agent wrote ever runs in a browser or on the server. This is the line between GenUI and "let the model emit
   a web page".
2. **One renderer.** Composed screens are drawn by the same renderer as every other view: the console's Jinja macros
   (`drishti-console/web/templates/_macros/panels.html`, `panel(p)`), shipped as HTML over the wire to Elements
   ([ADR-021](adr/021-embedding-is-html-over-the-wire.md), [ELEMENTS.md Decision 1](ELEMENTS.md#why-not-a-second-renderer-and-what-we-give-up)).
   That is what makes the UX "fully consistent": it is literally the same output.
3. **The server judges what a viewer may see,** at view time, with the viewer's own rights, whoever composed the screen.
4. **Deterministic and reviewable.** The same request on the same catalogue gives byte-identical Sutra YAML and the same
   content hash. Every composition can be read as YAML, diffed, audited and promoted into a saved Sutra through the normal
   review flow.
5. **Data never reaches the agent.** Composition tools answer with labels, types and slots, never rows. The data goes
   from Drishti straight to the viewer's screen.
6. **Off by default** (`drishti.genui.enabled: false`), like AI and MCP ([AI_PROVIDERS.md](AI_PROVIDERS.md)).

### Non-goals (2.6.0)

| Not in scope | Why |
|---|---|
| Agent-written HTML, CSS, JavaScript or Rachana-EL text | the security model rests on it (section 11); an agent fills *slots*, it does not write expressions |
| Write-back (forms that create or change records) | separately governed; GenUI forms only search (section 10) |
| A JavaScript ViewModel renderer | rejected in [ELEMENTS.md](ELEMENTS.md#why-not-a-second-renderer-and-what-we-give-up); an opt-in JSON stream is offered for hosts that render on their own (section 9.5), not a second Drishti renderer |
| Drishti calling an LLM to decide the layout | the agent decides; Drishti composes and checks. The console's Ask may *be* such an agent (section 6.5) |
| Pixel-level freedom | layout is the Sutra grid (`span`, `area`, tabs); styling is the design tokens |

## 4. What the code already gives us

| Piece | Where | What GenUI reuses | Gap |
|---|---|---|---|
| Shared blocks | `drishti-rachana/.../parse/SutraBlocks.java`: `<name>.v<N>.block.yaml`, `params:` defaults, `%{param}` substitution, `{ use: name@N, with: {...} }`, error `DRS-2048`; library in a static `AtomicReference` | the fragment unit, its versions, its expansion before checking | `with:` values are untyped strings substituted as text into expressions; no data contract; no opt-in for agent use |
| Block uses | `parse/BlockUses.java` (`Use`, `Brought`) | what a composed Sutra brought from which block, for review and audit | none |
| Blocks API | `RachanaController`: `GET /api/v1/rachana/blocks`, `GET /api/v1/rachana/blocks/{name}/{version}`; `GET /api/v1/rachana/schema` | listing fragments | no slots, contract or GenUI filter in the answer |
| Sutra check | `SutraRegistry.check(yaml)`, `SutraBuilder`, `ElCompiler` (`rachana/el`), `SutraProblem` | parse, lint and compile every expression | none |
| Rendering an unsaved Sutra | `ViewPipeline.preview(Sutra, EntityRef, AsOf, redact, mayOpen, controls)`, used by `StudioController` (`POST /api/v1/studio/preview`) and `DesignController` (`GET /api/v1/builder/designs/{id}/preview`) | rendering a composed Sutra without registering it | none |
| Masks and rights | `Entitlements`: `redactor(p)`, `mayOpen(p, kind)`, `requireOpen`, `masked(p, field)`, `restrict` | per-viewer ViewModel | none |
| Time travel | `AsOf(businessDate, knownAt, live)`, `AsOfResolver` | `asOf` and `knownAt` on a GenUI URL | none |
| Live views | `StreamController` (`GET /api/v1/views/{kind}/{id}/stream`, SSE: `view`, `frame`, `: hb`), `ViewStream`, `TopicHub`, `FrameMailbox`, `LiveStreamSlots` | ticks on a composed screen | `ViewStream` rebuilds with `pipeline.view(ref, …)` or `pipeline.build(main, …)`, i.e. with the *registered* Sutra (`ViewStream.java` line 144): a composed Sutra needs a rebuild that keeps it |
| Console channel | `drishti-console/core/channel.py` (`view:kind/id?ctl.…` keys), `routes/api_routes.py` `channel()`, `_panel_html` | one connection per page; panels as HTML | only `view:` keys |
| Elements | `drishti-console/web/embed/element.js`, `panel.js`, `report.js`, `search.js`, `about.js`; `routes/embed_routes.py` (`/embed/v1/views/{kind}/{id}`, `/embed/v1/channel`); library 1.4.2 (`web/elements/package/package.json`) | the host-side renderer, styles (`web/static/css/tokens.css`, `theme.css`, `--d-*` properties), charts, CSP and SRI story | no element for a composed screen |
| Embed tokens | `EmbedTokenService`, `EmbedController` (`POST /api/v1/embed/token`, RFC 8693), `EmbedProperties` (scopes to `METHOD path` patterns), `RateWindows`, `EmbedUsage`; console `core/embed_auth.py`, `core/embed_limits.py` | scoping a host application | no `embed:genui` scope |
| Query by form | `FormController` (`GET /api/v1/forms/{pack}`, `POST /api/v1/forms/{pack}/{kind}/compile`), `FormSpec`, `FormField`, `FormCompiler`; the pack's `form:` section; console `core/forms.py`, `routes/form_routes.py`, `css/form.css` | form fragments compile to the same search command | the form is a page, not a panel |
| Search | `SearchController` (`GET /api/v1/search`, `/csv`, `/compare`) | result rows for a search binding | rows are not an `EntityDocument` |
| BI | `MqController` (`POST /api/v1/bi/models/{id}/query`), `DatasetController`; consumer envelope ([BI_DESIGN.md §5.3](BI_DESIGN.md#53-model-read-build-and-the-consumer-envelope)) | a dataset or model binding | no Sutra panel reads MQ results today |
| Review | Build workbench Designs: `DesignController` (`POST /api/v1/builder/designs`), `DesignShipController` (`POST /api/v1/builder/designs/{id}/propose`); `SutraGovernance`, `ProposalStore`; `GovernanceController` (`/api/v1/sutras/proposals/{id}/approve`) | promoting a composed screen into a saved Sutra | none |
| Field knowledge | the pack's `form:` fields, `GlossaryResolver` / `CatalogGlossaryLookup` (pack `about.yaml`), `ShapeService` (shape learnt from samples) | checking every path a slot names | no single "fields of a kind" call for an agent |
| AI layer | Java `drishti-ai`: `LlmGateway`, `LlmPolicy`, `PromptMasker`, `ToolSpec`, `ToolCall`, `llm/BaseMcpTool`; Python `drishti-console/core/ai/mcp_tool.py` (`BaseMCPTool`), `gateway.py`, `policy.py`, `proxy.py` | the tool base classes for `compose_ui`; masking of the intent text; policy and proxy | Drishti is an MCP *client* only ([MCP_CLIENT.md](MCP_CLIENT.md)); it serves no MCP endpoint yet |
| Audit | `AuditLog.record(actor, action, subject, detail)` (`FileAuditLog`, `JpaAuditLog`), `AccessRecorder` | one row per composition and per view | none |

The conclusion of this check: **about four fifths of GenUI exists.** The new parts are slots and contracts on blocks, the
composer and its store, a rebuild path in `ViewStream` for an unregistered Sutra, a stream with a skeleton, one element,
a form panel, and the tools.

## 5. The fragment catalogue: blocks with typed slots and a data contract

### 5.1 Why blocks are the right unit

A block is already "a templatised Sutra fragment": panels written once, with parameters, versioned by file name, used by
many Sutras (`config/packs/trading/sutras/common/*.block.yaml`: `deal-ticket`, `legs`, `flow-schedule`, `events`,
`payments`, `deal-footer`). It is expanded *before* the Sutra is checked (`SutraBlocks.expand`), so whatever an agent
composes is linted, compiled and drawn exactly as if a person had written it in place.

What a block lacks for an agent is **types**. Today `with: { rate-fmt: pct4 }` is replaced as text wherever
`%{rate-fmt}` appears. A person writing a Sutra is trusted to put a format name there; an agent is not. Without types,
a `with:` value could carry an expression (`pct4" } , { label: x, bind: "secret(…)`) into the Sutra.

### 5.2 Slots (new)

A block may declare `slots:` (new) beside `params:`. A slot is a parameter with a type that the composer checks before
substitution. `params:` keeps working unchanged for people; a block an agent may use must declare every parameter as a
slot.

```yaml
# config/genui/blocks/amount-table.v1.block.yaml   (new folder, section 5.5)
rachana: 1
block: amount-table
version: 1
description: "A table of rows under one array of the document, with an id, a date and an amount column, totalled."
genui: { allowed: true, tags: [table, amounts] }          # new: opt-in for agents
contract:                                                  # new: what the bound document must have
  kinds: [any]                                             # or a list: [trade, portfolio-trades]
  requires: ["%{rows}"]                                    # paths that must exist in the kind's fields
slots:                                                     # new: typed parameters
  rows:     { kind: path, label: "Rows (an array)", array: true, default: "$.TRADES" }
  id-field: { kind: field, of: rows, label: "Id column", default: TRADE_ID }
  date-field: { kind: field, of: rows, label: "Date column", types: [date], default: MATURITY_DATE }
  amount-field: { kind: field, of: rows, label: "Amount column", types: [number], default: NOTIONAL }
  amount-fmt: { kind: fmt, label: "Amount format", default: amount0 }
  title:    { kind: label, label: "Panel title", max: 80, default: "Amounts" }
  link-kind: { kind: kind, label: "Id links to", optional: true }
panels:
  - id: amounts
    kind: table
    title: "%{title}"
    rows: "%{rows}"
    columns:
      - { label: Id, bind: "@.%{id-field}" }
      - { label: Date, bind: "@.%{date-field}", fmt: date }
      - { label: Amount, bind: "@.%{amount-field}", fmt: "%{amount-fmt}", total: true }
```

This is how "variable names changed" works safely: the agent renames *which field* a column shows (`amount-field:
MTM`), never the expression around it.

| Slot `kind` | Accepts | Checked against | Substituted as |
|---|---|---|---|
| `path` | a document path (`$.LEGS[*].CASH_FLOWS`) | the grammar `$(\.[A-Z0-9_]+(\[\*\]|\[\d+\])?)+` and the kind's fields (5.4) | the path text |
| `field` | a field name under another slot's rows (`of:`) | `[A-Z0-9_]+`, the kind's fields under that path, optional `types:` | the name |
| `fmt` | a format name | `formats.yaml` names (`RachanaSchema.build(formats, kinds)`) | the name |
| `label` | plain text | length `max`, no `${`, `%{`, quotes or control characters | a quoted YAML scalar |
| `kind` | an entity kind | the loaded packs' kinds | the name |
| `enum` | one of `values:` | the list | the value |
| `number`, `bool` | a literal | type and `min`/`max` | the literal |

Rules: a slot value is checked before it is substituted; substitution produces a YAML scalar, never structure; a value
that fails is a problem with the slot's name (`DRS-2049`, new), and nothing is composed.

### 5.3 The data contract (new)

`contract:` says what a fragment needs from the data: which kinds it fits (`any` or a list) and which paths must exist,
after slots are filled. The composer checks the contract against the binding's kind; the catalogue uses it to answer
"which fragments fit a `trade`?". Existing pack blocks get contracts as part of making them GenUI-ready (for
`flow-schedule@1`: `kinds: [trade]`, `requires: ["$.LEGS[*].CASH_FLOWS"]`).

### 5.4 The fields of a kind

Paths are checked against one merged list per kind and per caller (new: `KindFields`, in `drishti-engine`):

1. the pack's `form:` fields for the kind (`FormSpec`, `FormField`: path, label, type);
2. the glossary (`GlossaryResolver`, the pack's `config/about.yaml`);
3. the shape learnt from samples (`ShapeService`, `Shape`), for paths the first two do not name.

For the caller: fields the caller may not see by a hidden rule are **absent**; masked fields are **listed and marked**
(`masked: true`), as BI_DESIGN does for `describe` ([§9](BI_DESIGN.md#9-ai-architecture)). A slot naming an unknown path is refused
(`DRS-2050`, new); naming a masked path is allowed (the viewer sees `•••`), because hiding it would make the screen differ
by viewer in shape, not only in values.

### 5.5 Where fragments live, versions and governance

| Source | Folder | Governed by |
|---|---|---|
| Pack blocks with `genui.allowed: true` | the pack's Sutra folders, as today | the pack's review, signature and deploy (`PackDeployController`, signed registry, [ADR-018](adr/018-signed-pack-registry.md)) |
| Generic GenUI blocks (new) | `config/genui/blocks/` (new), loaded into the same `SutraBlocks` library | the same review as Sutras; shipped with the product |

A fragment is always named with its version (`amount-table@1`); the composer refuses a use without one (an agent must not
get a different screen because a block moved on). A block marked `deprecated: true` (new) is still composable but not
listed.

The generic set shipped in 2.6.0 (new, section 14): `kv@1`, `amount-table@1`, `metric@1`, `metric-row@1`, `line@1`,
`bar@1`, `ladder@1`, `tree@1`, `notes@1` (text from the glossary), `search-form@1` and `search-results@1` (section 10).

### 5.6 Listing

`GET /api/v1/genui/fragments?kind=trade&tag=amounts` (new) answers, per fragment: `ref`, `description`, `tags`, `slots`
(name, kind, label, default, allowed values), `contract`, the panel kinds it brings (`BlockUses.Brought`), and `fits`
(true when the contract matches the kind for this caller). No text of the block unless `?text=1` and the caller may author
(`Entitlements.mayAuthor`). `GET /api/v1/genui/kinds/{kind}/fields` (new) answers 5.4.

## 6. The composer: one tool, no written code

### 6.1 The request

```json
POST /api/v1/genui                               (new)
{
  "intent": "Cash flows of this swap with rates as percentages, and its legs",
  "title": "Swap cash flows",
  "binding": {"type": "entity", "kind": "trade", "id": "MX-200114"},
  "fragments": [
    {"block": "legs@1",          "with": {"rate-fmt": "pct4"}},
    {"block": "flow-schedule@1", "with": {"rate-fmt": "pct4"}, "span": 12},
    {"block": "amount-table@1",  "with": {"rows": "$.LEGS[*].CASH_FLOWS", "id-field": "FLOW_ID",
                                          "date-field": "PAYMENT_DATE", "amount-field": "NOTIONAL", "title": "Notional by flow"}}
  ],
  "strip": [{"field": "NOTIONAL", "fmt": "compact"}, {"field": "MATURITY_DATE", "fmt": "date"}],
  "ttl": "PT2H",
  "audience": "me"
}
```

| Field | Meaning |
|---|---|
| `intent` | the agent's reason, in words; stored for audit after `PromptMasker` (section 11.3); never interpreted |
| `title` | a `label` slot of the screen |
| `binding` | what the data is (section 8): `entity`, `search`, `dataset` |
| `fragments` | ordered uses; `with` fills slots; optional `span`, `area` (`main`/`right`), `tab` (a group name: fragments with the same `tab` become one tabs panel) |
| `strip` | optional strip cells, each a `field` slot plus a `fmt` slot |
| `ttl` | lifetime, within `ttl-max` |
| `audience` | `me` (only the composer may open it), `link` (anyone with the link who may open the kind), `app` (the embed app's users) |

### 6.2 What the server does, in order

1. **Gate:** `drishti.genui.enabled`; the caller may compose (`roles`, token scope `genui:compose`, new); rate limits.
2. **Policy:** every `block` is in the allow list and not in the deny list (section 11.4), and has `genui.allowed`.
3. **Slots:** each `with` value is checked by its slot type (5.2) against the binding's kind and the caller's fields (5.4).
4. **Contract:** each fragment fits the binding's kind (5.3).
5. **Assemble:** a Sutra text is written by `SutraWriter` (existing) from a fixed template: `rachana: 1`,
   `sutra: genui-<id>`, `version: 1`, `match: { kind: <kind>, priority: -1 }` (never chosen for normal views; it is not
   registered anyway), `title`, `strip`, and `panels:` as `{ use: <block>@<N>, with: {...} }` lines, in request order.
   Panel ids are made unique by a suffix when two fragments bring the same id (`flows`, `flows-2`).
6. **Check:** `SutraRegistry.check(yaml)` (parse, `SutraBlocks.expand`, lint, `ElCompiler`). Any problem refuses the
   composition with the `SutraProblem` list, mapped back to the fragment and slot that caused it.
7. **Dry run:** the Sutra is rendered once for the composer, `ViewPipeline.preview(…)` with the composer's redactor, to
   catch run-time emptiness (`Emptiness`) and to fill `warnings` ("panel `flows` has no rows for this trade"). No data is
   returned to the caller.
8. **Store** (section 7) and **audit** (section 11.3).
9. **Answer:**

```json
{"id": "g_7QX4M2", "hash": "sha256:9f1c…", "expires": "2026-10-18T14:05:00Z",
 "url": "https://drishti.bank.example/g/g_7QX4M2",
 "embed": {"element": "<drishti-genui genui=\"g_7QX4M2\"></drishti-genui>", "api": "/embed/v1/genui/g_7QX4M2"},
 "panels": [{"id": "legs", "kind": "table", "from": "legs@1"}, {"id": "flows", "kind": "table", "from": "flow-schedule@1"},
            {"id": "amounts", "kind": "table", "from": "amount-table@1"}],
 "warnings": [], "sutra": "rachana: 1\nsutra: genui-g_7QX4M2\n…"}
```

`sutra` (the YAML) is returned only to callers who may author (`mayAuthor`); others get it from Admin → GenUI.

### 6.3 Deterministic and idempotent

The id is random, but the **hash** is of the canonical request (sorted keys, the resolved block versions and the
catalogue's version). The same hash from the same user within its TTL returns the existing screen (`200` instead of
`201`), so an agent that retries does not fill the store. Same input, same YAML: a test (section 15) holds it.

### 6.4 Refusals an agent can act on

A refusal names the fragment, the slot and a fix, once, so an agent can correct itself without guessing:

```json
{"code": "DRS-2050", "error": "unknown field", "fragment": 2, "block": "amount-table@1", "slot": "amount-field",
 "value": "NOTIONAL_USD", "near": ["NOTIONAL", "NOTIONAL_CCY"], "fix": "use a field of $.LEGS[*].CASH_FLOWS"}
```

`near` lists only fields the caller may see.

### 6.5 The tool layer: Java, Python and MCP

The composer is one service (`GenUiComposer`, new, `drishti-server/.../genui/`). It is offered to agents as tools written on
the existing base classes, so the same tool is described once ([AI_PROVIDERS.md §10](AI_PROVIDERS.md),
[MCP_CLIENT.md §7](MCP_CLIENT.md)):

| Tool (new) | Input | Output |
|---|---|---|
| `list_fragments` | `kind?`, `tag?` | 5.6 |
| `describe_fields` | `kind` | 5.4, as the caller |
| `compose_ui` | 6.1 | 6.2 step 9, without `sutra` unless the caller may author |
| `get_ui` | `id` | status, expiry, panels, warnings; never data |
| `promote_ui` | `id`, `name`, `note` | a Design id and its proposal (section 7.4) |

- **Java:** each tool a `BaseMcpTool` subclass (`com.ash.drishti.ai.llm`), with `getInputSchema` as JSON Schema and
  `additionalProperties: false`, so `validate_arguments` refuses unknown names before the composer sees them.
- **Python:** the same tools as `BaseMCPTool` subclasses (`drishti-console/core/ai/mcp_tool.py`) that call the server's REST
  API with the user's identity, for the console's Ask and for Python agents.
- **MCP (new endpoint):** the server serves these tools at `POST /mcp` (new, Streamable HTTP, bearer = a personal token with
  `genui:compose`, or the user's session). Today Drishti is only an MCP client; this endpoint is the same one
  [BI_DESIGN.md §9](BI_DESIGN.md#9-ai-architecture) plans for `list_models`, `query_model` and the rest, so it is built once for both.
- **The console's Ask** ([CONTEXT_HELP.md](CONTEXT_HELP.md)) may use `compose_ui` as a tool with feature `genui`
  (`drishti.ai.policy.features.genui.roles`), so "show me this trade's resets by month" answers with a screen.

## 7. Ephemeral Sutras and live URLs

### 7.1 The store (new)

`GenUiStore` (new) keeps each composition: id, hash, owner, audience, embed app (when composed through an embed token),
binding, Sutra text, compiled `Sutra`, created, expires, view count. In memory with a size cap, and, when
`store: file`, one JSON file per screen under `<data dir>/genui/` (visible names only, under the configured data folder),
so a restart keeps live links. Expired screens are removed by a sweep on the server's virtual-thread executor.

The compiled Sutra is **never registered** in `SutraRegistry`: it can never match a normal view, appear in Studio, or be
changed by a pack reload. When a pack reload changes a block a screen used, the screen keeps the Sutra it was composed
with (it is a snapshot); its `get_ui` says `stale: true` and the agent may compose again.

### 7.2 URLs

| URL (all new) | Served by | Answers |
|---|---|---|
| `/g/{id}` | console | a full page, the console's view chrome without the command line; works with no JavaScript (section 9.6) |
| `/g/{id}?ref=trade/MX-200115` | console | the same screen bound to another entity of the same kind, when `binding.rebind: true` and the contract fits |
| `/g/{id}?asOf=2026-10-15&knownAt=…&ctl.status=LIVE` | console | time travel and controls, as `/v/{kind}/{id}` (`AsOfResolver`, `ControlValues`) |
| `GET /api/v1/genui/{id}` | server | the ViewModel for the caller, as `GET /api/v1/views/{kind}/{id}` |
| `GET /api/v1/genui/{id}/stream` | server | SSE, section 9 |
| `GET /embed/v1/genui/{id}` and channel key `genui:{id}` | console | the Elements API, section 9 |

A link to an expired screen answers `410 Gone` (`DRS-2051`, new) with the intent's title and a "compose again" hint, never a
blank page.

### 7.3 Sharing

`audience` decides who may open the screen; **what** they see is always decided by their own rights at view time
(section 8.2). A screen can be shared with the existing share-with-a-note flow ([COLLABORATION.md](COLLABORATION.md)) once
promoted; an ephemeral screen is shared only by its link, because shares pin to a Sutra version and a business date and an
ephemeral one has no version that outlives it.

### 7.4 Promote to a saved Sutra

A useful composed screen should become a real one. `promote_ui` (and **Promote** on `/g/{id}` for authors) creates a
Build workbench Design from the screen's Sutra text (`POST /api/v1/builder/designs`, with the binding's entity as its
sample), and the author proposes it as today (`POST /api/v1/builder/designs/{id}/propose`), which goes through
`SutraGovernance` four-eyes review (`GovernanceController` approve or reject). Provenance on the proposal:
`genui:{id, hash, intent title}`. Nothing an agent does saves a Sutra on its own.

## 8. Data wiring

### 8.1 Bindings

| `binding.type` | Data | How it is drawn | Phase |
|---|---|---|---|
| `entity` (`kind`, `id`) | the entity's document, as every view | `ViewPipeline.preview(sutra, EntityRef.of(kind, id), asOf, redactor, mayOpen, controls)` | G3 |
| `search` (`q`, a search command; or `form` criteria) | the result rows of `SearchController`'s search | a search document (new `SearchDocument`: `{ROWS: [...], COUNT, QUERY}`) given to `ViewPipeline.builtPreview(Optional.of(sutra), doc, redact, mayOpen)` | G7 |
| `dataset` (`model`, `mq`) | an MQ result | a model document (new) from `MqController`'s query path as the viewer, under the consumer envelope | G9, may slip to 2.7.0 |
| `compose` (several of the above, named) | one document per name, `$.<name>…` in slots | the bindings resolve in parallel; each panel waits only for its own | G9 |

Bindings name data; they never carry it. An agent cannot inject rows.

### 8.2 Per viewer

Every render runs as the **viewer**: `entitlements.redactor(viewer)`, `k -> entitlements.mayOpen(viewer, k)`, and, for an
embed token, never `raw` ([ELEMENTS.md Decision 5](ELEMENTS.md#83-the-response-and-the-token)). A viewer who may not open
the binding's kind gets `403` from `requireOpen`, whoever composed the screen. Masked values read `•••`;
`provenance.masked` counts them, as for any view. Links inside panels (`link(@.TRADE_ID, 'trade')`) open normal views,
under the same rights.

### 8.3 As of, time travel, controls

`asOf` and `knownAt` work as on any view (`AsOf`). A past business date gives one view and no ticks
(`provenance.live == false`, as `StreamController` does today). Blocks that declare `controls:` keep them; a composed
screen's controls are those of its fragments.

## 9. Streaming to the host application

### 9.1 What is streamed: our renderer's output, never the agent's

The stream carries **panel HTML rendered by the console's own macros** (`panel(p)`, through `_panel_html`) and chart data,
exactly the payloads `<drishti-view>` already applies ([ELEMENTS.md §6.1–6.2](ELEMENTS.md#61-get-embedv1viewskindid--the-initial-view)).
The agent's only contribution is the choice of fragments and slot values, both checked. No script, style or markup from the
agent is ever sent; the console's CSP (`script-src 'self'; style-src 'self'`) holds unchanged.

This is how the agreed design's "ViewModel plus our renderer" lands in the code: the ViewModel is computed on the server, and
"our renderer" is the existing server-side one, per ADR-021.

### 9.2 Order of events

Server `GET /api/v1/genui/{id}/stream` (new, SSE, beside `StreamController`'s view stream) and console
`GET /embed/v1/genui/{id}/stream` (new, `fetch` + `ReadableStream`, `Authorization` header, as the Elements channel):

| Event | When | Payload |
|---|---|---|
| `skeleton` (new) | at once, before any data is fetched | title, strip labels, the grid (each panel's id, kind, span, area, tab, title), `subscribe` key, `expires`; the element draws placeholders in the final layout, so nothing jumps |
| `panel` (new) | as each panel's data is ready | `{id, html}` or `{id, kind, data}` for charts; masked count per panel |
| `view` | after the last `panel` | the full first view's provenance (generation, `live`, `masked`), timings |
| `frame` | on each change, while live | the same frames as today (`strip`, `panel`, `provenance`, `deleted`, `restored`) |
| `: hb` | every `live.heartbeat` | — |
| `gone` | the screen expired, the app or user was disabled, or rights changed | `DRS` code |

For an `entity` binding the pipeline builds the whole ViewModel in one pass today (`ViewPipeline.preview`), so in G4 the
`panel` events follow each other within milliseconds of the build; the skeleton still arrives first, which is what the
viewer notices. For `compose` bindings (G9) each binding resolves on its own and its panels are sent when it is ready:
true progressive arrival.

### 9.3 Live ticks on a composed screen

`ViewStream` gains a constructor (new) that takes the composed `Sutra` and rebuilds with `pipeline.preview(sutra, …)`
instead of `pipeline.view(ref, …)`; everything else (topics on `TopicHub`, chart sources, `FrameMailbox`, coalescing,
`LiveStreamSlots` caps, `EmbedUsage` counts) is reused. The console's channel accepts `genui:{id}` keys (new) beside
`view:` (`core/channel.py`), so a host page with three composed screens and two ordinary views still holds **one**
connection.

### 9.4 `<drishti-genui>` (new element)

```html
<script type="module" src="https://drishti.bank.example/elements/1.5.0/drishti-elements.js" integrity="sha384-…"></script>
<drishti-genui genui="g_7QX4M2" theme="inherit"></drishti-genui>
<script type="module">
  const el = document.querySelector('drishti-genui');
  el.tokenProvider = () => fetch('/my-backend/drishti-token').then(r => r.text());
  el.addEventListener('drishti:expired', () => el.remove());
</script>
```

It is `<drishti-view>` with a different source: same shadow root, same adopted sheet (`tokens.css`, `theme.css`, the panel
CSS), same hub and channel, same charts, zoom, tables and keyboard. Attributes: `genui`, `ref` (rebind, 7.2), `as-of`,
`known-at`, `panels`, `header`, `theme`. Events: those of `<drishti-view>` (`drishti:loaded`, `:tick`, `:navigate`,
`:error`, `:masked`, `:state`) plus `drishti:skeleton` and `drishti:expired` (new). Library version: the next minor after
1.4.2 (1.5.0), with `integrity.json` and TypeScript declarations as for every element.

The design tokens (`--d-*`) are the consistency contract: a host restyles every composed screen at once, exactly as it
restyles its `<drishti-view>`s.

### 9.5 A JSON stream for hosts with their own renderer (opt-in)

`?format=viewmodel` (new) on the server stream sends the masked ViewModel and its frames as NDJSON instead of HTML. It is
for hosts such as native desktop or mobile applications. It is off unless `drishti.genui.stream.viewmodel: true`, and
Drishti ships no renderer for it: hosts that use it accept that their screens will not look like Drishti's.

### 9.6 Hosts without JavaScript

`/g/{id}` renders the whole screen server side, like `/v/{kind}/{id}`, with a `<meta http-equiv="refresh">` only when
`?refresh=30` is asked; it is the fallback for e-mail links, wikis and tools that cannot run modules. It is static (no
ticks) without JavaScript and live with it (the console's own `live.js`).

## 10. Dynamic forms

A **form fragment** lets an agent build a search form on the fly: "find this counterparty's live rates trades maturing
next year" becomes a form with Counterparty, Product type, Status and Maturity, pre-filled, and a results table under it.

- New panel kind `form` (new, Rachana grammar and `panels.html`): a list of the pack's `form:` fields by path, each with an
  optional preset value and operator. It reuses `FormSpec`/`FormField` for labels, types, operators, enum values and
  `depends`, and the console's form rendering (`core/forms.py`, `css/form.css`), so it looks like `FORM <pack>`.
- `search-form@1` (generic block): slots `pack` (`kind`), `fields` (a list of `field` slots, each checked against the pack's
  form fields for the caller), `presets` (values checked by the field's type and, for enums, against the viewer's visible
  values).
- `search-results@1`: a table bound to the form's search.
- Submitting the form calls `POST /api/v1/forms/{pack}/{kind}/compile` (unchanged) and then `GET /api/v1/search` as the
  viewer; the results panel is swapped in place (a `panel` patch), and the URL gets the criteria
  (`/g/{id}?f.COUNTERPARTY=CP-1042`), so a filled form is a link too.

**Write-back is out of scope.** A form fragment only searches. Forms that create or change data (approvals, amendments,
corrections) need their own design: commands, four-eyes, idempotency, source-system ownership. They are not part of 2.6.0.

## 11. Security and governance

### 11.1 Threats and answers

| Threat | Answer |
|---|---|
| An agent smuggles code (script, style, Rachana-EL) | no free text reaches an expression; slots are typed and quoted (5.2); assembly uses a fixed template (6.2); the stream carries only our renderer's HTML (9.1) |
| An agent reads data it should not | tools answer labels and types, never rows (principle 5); `describe_fields` hides hidden fields; the dry run returns no data |
| A viewer sees more than their rights | every render is as the viewer; embed never `raw`; `requireOpen` on the binding's kind (8.2) |
| A composer learns masked values through warnings | warnings name panels and counts only ("no rows"), never values |
| A link leaks | `audience` (7.1), TTL, `410` after expiry, rights at view time; ids are 128-bit random, not guessable |
| A flood of compositions | per-user and per-app limits, a per-user live cap, a store cap (11.5) |
| A fragment is unsafe or not ready | opt-in (`genui.allowed`), allow and deny lists, roles per fragment (11.4) |
| Agent prompts carry data to a model | not Drishti's call path: Drishti's tools return no rows; the console's Ask is under `LlmPolicy` and `PromptMasker` as today |

### 11.2 Masks before render

Masking happens in the pipeline (`redactor`) before any panel is rendered or any frame is built, as for every view. GenUI
adds no path that renders before masking.

### 11.3 Audit

Every composition: `AuditLog.record(user, "genui-compose", "genui:<id>", detail)` with detail
`{intent (masked by PromptMasker, at most 500 characters, or omitted when audit-intent: none), binding kind and id, fragments
with versions and slot values, hash, outcome, problems, app}`. Every view: an `AccessRecorder` row, action `view`, detail
`genui:<id>` (and `embed:<app>` for embeds), as for shares. Every promotion: the proposal's provenance. Admin → GenUI (new)
lists live screens, their owners, fragments and views, and can expire one.

### 11.4 Which fragments an agent may use

```yaml
drishti.genui.fragments:
  allow: ["genui/*", "trading/common/*"]     # pack/folder patterns of block files
  deny:  ["trading/common/deal-footer"]
  roles: { "trading/common/payments": [ops, risk] }   # roles allowed per fragment; absent = every user who may compose
```

A fragment needs `genui.allowed: true` in its file *and* to pass these lists. Packs keep control of their own blocks;
the site keeps control of what agents may do with them.

### 11.5 Limits

`per-user-per-minute` compositions, `max-live-per-user`, `max-fragments` per composition (12), `max-panels` after expansion
(24), `ttl-default` and `ttl-max`, `store-max`; live streams count against `LiveStreamSlots` like any view. Over a limit:
`429`, `DRS-4017` (the AI layer's code), with `Retry-After`.

### 11.6 Embed tokens

A new scope `embed:genui` (new) in `EmbedProperties.scopes`, opening `GET /api/v1/genui/*`, `GET /api/v1/genui/*/stream`
and the fragment listing, and, only when the app's registration says `genui-compose: true` (new), `POST /api/v1/genui`
(the one non-GET an embed token may make, so an embedded agent can compose for its user). Compositions made with an embed
token have `audience: app` and the app recorded. When embed tokens are bound to consumer envelopes (a wave D item;
[BI_DESIGN.md §5.3](BI_DESIGN.md#53-model-read-build-and-the-consumer-envelope)), a `dataset` binding is limited to the
token's envelope.

### 11.7 Off by default, policies honoured

`drishti.genui.enabled: false` answers `404` (`DRS-2052`, new) on every GenUI call and hides Admin → GenUI. The console's Ask
uses GenUI only with `drishti.ai.enabled` and the `genui` feature allowed. The outbound proxy and TLS settings of the AI
layer apply to any provider call; GenUI itself makes no outbound call.

### 11.8 Managing GenUI in the console (new)

Asked by the product owner (2026-10-18): everything GenUI keeps or allows is managed in the console, never only in files.
Three places, each with the usual roles (administrators manage; authors edit fragments; everyone sees their own screens):

**Build → Fragments** (authors; the Build workbench's review applies):

- The catalogue as a searchable list: block, version, kinds it fits (its contract), slots, whether agents may use it, where
  it is used (Sutras and live screens).
- A fragment's page: its slots as a form (name, type, field picker over the kind's fields, format, label, default), the
  contract (kinds and required fields), a **live preview** on a chosen entity and date, the YAML side by side (round trip,
  comments kept), and *Test with* any role (what that role would see, masked).
- New fragment from an existing Sutra's panels ("Make a fragment of these panels"), new version, deprecate, retire; every
  change goes through propose → four-eyes approve, as Sutras do.

**Admin → GenUI** (administrators):

- **Live screens:** each composed screen with its owner (agent and user), intent, fragments, binding, created, expires,
  views, the last viewers; open it, copy its URL, extend or **expire now**, **promote** (to the review of 7.4), or see the
  exact request and the YAML it made.
- **Policy:** the switch (`drishti.genui.enabled`), which fragments agents may use (allow list, deny list, per role), the
  limits (screens per user and minute, TTL default and maximum, panels per screen), the AI providers and MCP clients
  allowed to call `compose_ui`; changes are audited and take effect without a restart.
- **Hosts:** the embed applications allowed to show GenUI (origins, `embed:genui` scope, token lifetime), with a snippet
  to paste (`<drishti-genui>` and its script tag, integrity hash included).
- **Activity:** compositions and views a minute (the Admin → Health activity chart, filtered to GenUI), refusals by code
  (`DRS-2049`…), slowest screens, audit search by user, agent or fragment.

**Build → Compose** (authors and administrators; a playground):

- Pick fragments, fill their slots from field pickers, choose a binding, and see the screen build live: the same
  `POST /api/v1/genui` an agent sends, shown as the request, the screen and the stream's events. Used to try fragments, to
  reproduce what an agent did (open any audited composition here), and to write the golden examples the agent tools are
  tested with.

APIs behind them (all new): `GET/PUT /api/v1/admin/genui/policy`, `GET /api/v1/admin/genui/screens`,
`POST /api/v1/admin/genui/screens/{id}/expire|extend|promote`, `GET /api/v1/admin/genui/activity`, and the Build workbench's
existing design endpoints for fragments.

## 12. Configuration

```yaml
drishti:
  genui:
    enabled: false                 # everything below is inert while false
    roles: []                      # who may compose; empty = every user with genui:compose or a session
    ttl-default: PT1H
    ttl-max: PT24H
    per-user-per-minute: 10
    max-live-per-user: 50
    max-fragments: 12
    max-panels: 24
    store: memory                  # memory | file (<data dir>/genui/)
    store-max: 10000
    audit-intent: masked           # masked | none
    blocks-dir: config/genui/blocks
    fragments: { allow: ["genui/*"], deny: [], roles: {} }
    stream:
      viewmodel: false             # 9.5
```

All settings are read without a restart, as every pack-contributed setting is today. Admin → Configuration shows them.

## 13. APIs, tools and error codes

### 13.1 REST (all new)

| Call | Who | What |
|---|---|---|
| `GET /api/v1/genui/fragments` | `genui:compose` | the catalogue, 5.6 |
| `GET /api/v1/genui/kinds/{kind}/fields` | `genui:compose` | fields as the caller, 5.4 |
| `POST /api/v1/genui` | `genui:compose` | compose, 6.1–6.2; `201` new, `200` same hash |
| `GET /api/v1/genui/{id}` | audience | the ViewModel as the caller |
| `GET /api/v1/genui/{id}/stream` | audience | SSE, 9.2 |
| `GET /api/v1/genui/{id}/meta` | owner, admin | status, expiry, fragments, YAML for authors |
| `POST /api/v1/genui/{id}/promote` | `mayAuthor` | a Design and its proposal, 7.4 |
| `DELETE /api/v1/genui/{id}` | owner, admin | expire now |
| `GET /api/v1/admin/genui` | admin | live screens, counts, limits |
| `POST /mcp` | `genui:compose` | the MCP endpoint, 6.5 |
| console `/g/{id}`, `/embed/v1/genui/{id}`, `/embed/v1/genui/{id}/stream`, channel key `genui:{id}` | audience | 7.2, 9 |

### 13.2 Error codes (new; numbers confirmed against `ErrorCode.java` when built)

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-2049` | 400 | a slot value of the wrong type, or a parameter that is not a slot |
| `DRS-2050` | 400 | a path or field the kind does not have, for this caller |
| `DRS-2051` | 410 | the screen expired |
| `DRS-2052` | 404 | GenUI is off |
| `DRS-2053` | 403 | the fragment is not allowed for this caller |
| `DRS-2054` | 400 | the fragment does not fit the binding's kind (contract) |
| `DRS-2048` (existing) | 400 | the block is unknown or badly written |
| `DRS-4017` (existing) | 429 | over a limit |

### 13.3 Command line (new)

`drishti.py genui fragments [--kind trade]`, `drishti.py genui compose request.json`, `drishti.py genui show <id>`,
`drishti.py genui promote <id> --name …`, beside the existing `sutra`, `pack` and `view` commands.

## 14. Build plan for 2.6.0

Sizes: S up to two days, M up to a week, L more. Each step ends with its tests green and its docs written.

| Step | Scope | Size | Accepted when |
|---|---|---|---|
| **G1 Slots and contracts** | `slots:`, `contract:`, `genui:` in block files; `SutraBlocks.Block` carries them; typed substitution; `RachanaSchema` and `RACHANA_REFERENCE.md` updated; contracts on the six trading blocks | M | an injection attempt in a slot is refused with `DRS-2049`; every existing Sutra and block checks unchanged (pack tests green); a slot rename (`amount-field: MTM`) draws the right column |
| **G2 Fields and catalogue** | `KindFields` (form + glossary + shape, per caller); `GET /api/v1/genui/fragments`, `/kinds/{kind}/fields`; generic blocks in `config/genui/blocks/` | M | a role without access to a hidden field never sees it listed; masked fields are marked; every generic block has a sample test |
| **G3 Composer, store, URL** | `GenUiComposer`, `GenUiStore`, `GenUiController`, `GenUiProperties` (new, `drishti-server/.../genui/`); `POST /api/v1/genui`, `GET /api/v1/genui/{id}`; console `/g/{id}`; audit; limits; off by default | L | the same request twice gives the same YAML and hash; a second user opens a `link` screen and sees their own masks; expiry answers `410`; off answers `404` |
| **G4 Streaming and live** | `ViewStream` rebuild with a given Sutra; `GET /api/v1/genui/{id}/stream` (skeleton, panel, view, frame); `genui:` channel keys; `/embed/v1/genui/{id}` and its stream; scope `embed:genui` | M | a tick on the bound trade reaches a composed screen within the live p99 of ordinary views; skeleton first in every run; one connection for mixed `view:` and `genui:` keys |
| **G5 `<drishti-genui>`** | the element in `web/embed/`, library 1.5.0, `integrity.json`, TypeScript declarations, React/Vue/Angular samples | M | Chromium, Firefox and WebKit draw a composed screen under the strict host CSP of the Elements spike; host tokens restyle it; no inline script or style |
| **G6 Tools and MCP** | `list_fragments`, `describe_fields`, `compose_ui`, `get_ui`, `promote_ui` on `BaseMcpTool` (Java) and `BaseMCPTool` (Python); `POST /mcp`; Ask feature `genui` | M | the mock provider (`type: mock`, `rules` with `tool` + `tool-args`) composes a screen end to end with no network; an MCP client (SAJHA) lists and calls the tools |
| **G7 Forms** | panel kind `form`; `search-form@1`, `search-results@1`; `search` binding with `SearchDocument`; criteria in the URL | M | a composed form pre-filled for a counterparty finds the same trades as `FORM trading` with the same criteria |
| **G8 Promote and admin** | `promote_ui` → Design → propose; the management UI of 11.8: Admin → GenUI (live screens, policy, hosts, activity), Build → Fragments (slot editor, contract, live preview, *Test with* a role, review), Build → Compose (playground) | M | a promoted screen goes through four-eyes review and then opens as a normal Sutra; an administrator changes the agent allow list and the TTL in the UI and the next composition obeys without a restart; an author makes a fragment from a Sutra's panels, fills its slots and previews it without typing YAML; browser tests for the three pages |
| **G9 More bindings** (may move to 2.7.0) | `compose` (named bindings, progressive panels), `dataset` (MQ, envelope) | L | three bindings arrive in the order they resolve; a dataset panel equals the MQ answer per user, masked |
| **G10 Docs, tutorials, UX pass** | section 16; one hands-on tutorial with UI, CLI and YAML for every step | M | a new reader composes, embeds and promotes a screen from the tutorial alone |

Order: G1 → G2 → G3 → (G4 ∥ G6) → G5 → G7 → G8 → G9 → G10. G1–G8 are the 2.6.0 commitment; G9 is a stretch.

**GenUI is accepted for 2.6.0 when:** (1) an agent using only `compose_ui` produces a live screen of a trade from four
fragments with renamed fields, in under 200 ms to the answer (p50, 10K trades locally); (2) that screen, embedded with
`<drishti-genui>` in a host page, shows its skeleton first and ticks live; (3) two viewers with different roles see the
same screen with their own masks; (4) every composition and view is in the audit; (5) nothing changes for a site that does
not turn it on.

## 15. Tests

| Area | Tests (new, beside the existing suites) |
|---|---|
| Slots (`drishti-rachana`) | each slot type accepts and refuses; quoting; a value holding `${`, `%{`, `"`, newline or `]` is refused; slots in tabs panels; unchanged behaviour for `params:` only blocks |
| Contracts | fits and does not fit; `any`; paths after slot filling |
| Composer (`drishti-server`) | determinism (byte-identical YAML, same hash, golden files); idempotent retry; unique panel ids; every refusal names fragment and slot; policy allow/deny/roles; limits (`429`); off (`404`) |
| Security | viewer masks differ by role; embed never `raw`; hidden fields absent from `describe_fields` and `near`; `requireOpen` on the binding; expired `410`; `audience: me` refuses others; warnings carry no values |
| Live | a property test like the view stream's: random changes to the bound entity, the composed stream's last frame equals a fresh render |
| Stream | event order (skeleton before any panel, `view` after the last panel); heartbeats; `gone` on expiry |
| Console (`drishti-console/tests`) | `/g/{id}` page; `genui:` channel keys; embed routes with CORS and `embed:genui`; browser tests in the style of `test_embed_controls_browser.py` in Chromium, Firefox and WebKit |
| Tools | Java and Python tools' schemas equal; mock provider end to end; MCP `tools/list` and `tools/call` |
| Forms | composed form criteria equal `FORM` criteria; enum presets the viewer may not see are refused |
| Performance | compose p50 and p99; time to skeleton; 50 users opening composed screens beside ordinary ones, within the existing local rules (10K trades at most, one heavy job at a time) |

## 16. Documentation to write or update

| Document | Change |
|---|---|
| `docs/guides/GENUI.md` (new) | the developer guide: five-minute start, the request, tools, the element, cookbook, troubleshooting by code |
| a new tutorial in `docs/tutorials/` | compose, embed, promote: every step in the UI, the CLI and YAML |
| `docs/guides/RACHANA_REFERENCE.md` | `slots:`, `contract:`, `genui:` in block files; panel kind `form` |
| `docs/guides/PANELS.md` | the `form` panel |
| `docs/guides/ELEMENTS.md`, [ELEMENTS.md](ELEMENTS.md) | `<drishti-genui>`, the `genui:` channel key, `embed:genui` |
| `docs/guides/API_GUIDE.md` | section 13.1; the `genui:compose` token scope |
| `docs/admin/CONFIGURATION.md` | section 12 |
| [AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md) | feature `genui`; the server's own MCP endpoint |
| `docs/guides/CLI_GUIDE.md` | `drishti.py genui` |
| `docs/guides/USER_GUIDE.md` | opening a composed screen, promoting it |
| a new ADR in `adr/` | "Agents compose screens from governed fragments; no generated code is sent or run" |
| [docs/README.md](../README.md), `CHANGELOG.md`, `RELEASE_NOTES.md` | entries |

## 17. Risks

| Risk | Mitigation |
|---|---|
| Typed substitution breaks existing blocks | slots are opt-in; blocks with only `params:` keep text substitution; G1 runs every pack test |
| Agents compose poor screens (ten tables, wrong order) | a small, good generic catalogue; `tags` and `description` written for agents; `max-fragments`; warnings for empty panels; promotion means a person reviews before anything lasts |
| The store grows | TTL, `store-max`, idempotent hashing, per-user live caps |
| A pack reload changes a block under live screens | screens are snapshots; `stale: true`; tests for reload during a stream |
| The skeleton promise is weak for single-entity screens | it is honest about it (9.2); progressive panels come with `compose` bindings |
| HTML over the wire is larger than JSON | measured in ELEMENTS.md §11 (3 to 6 times before compression); accepted there, and the same here |
| One more MCP surface to secure | one endpoint for GenUI and BI tools, personal tokens with scopes, the same policy and audit |

## 18. Open questions

1. **Where generic blocks live:** `config/genui/blocks/` shipped with the product, or a `genui` pack that sites can extend
   and sign like any pack? (Recommendation: a pack, so the signed registry and deploy flow cover it.)
2. **Audience `link`:** is a link anyone in the firm may open (rights still apply) acceptable to compliance, or must every
   screen be `me` or `app` unless promoted?
3. **Intent in the audit:** keep the masked intent text (useful for review, a privacy risk) or only a hash and title?
4. **Rebinding** (`?ref=`): on by default for screens bound to one entity, or only when the agent asks?
5. **Dataset bindings** (G9): in 2.6.0 or 2.7.0, given the consumer-envelope work they depend on?
6. **The console's Ask as an agent:** should Ask be allowed to compose by default once AI is on, or only with the
   `genui` feature granted per role?
7. **Write-back forms:** a separate design for 2.6.0 or 3.0?
