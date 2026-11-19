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
# i9: Generative UI through sajhamcpserver: slide outline (2026-11-19)

The outline of the deck built from [I9_GENUI_SAJHA.md](I9_GENUI_SAJHA.md). Twenty-four slides. Each has a title, three to five bullets
and one visual whose content is given in full, so the deck can be built without going back to the document. Section numbers in
brackets point to the document. Chapters, not release numbers, head the slides; "i9" appears only where the plan is the subject.

Conventions for the builder of the deck: one visual per slide; code in a monospace box of at most twelve lines; diagrams as boxes
and arrows with the labels given here, word for word; no figure that is not in the document.

---

## Slide 1. Ask in words, get a live screen

- A person asks; a model writes a **Sutra**; Drishti checks it, binds it as that person and paints it
- The model runs behind **sajhamcpserver (SAJHA)**; Drishti holds the data and the access rules
- A Sutra is data, never code: the worst a wrong answer can do is show the wrong panel
- The screen runs **anywhere**: in Drishti, in the chatbot, in an intranet application, on a static page
- This deck: the architecture, the contract, the tool pack, portable views, five flows, safety, evaluation, risks, the plan

**Visual: diagram (one line, five boxes, left to right).**

```
[ words ] → [ model writes a Sutra ] → [ Drishti checks it ] → [ bound as the person ] → [ live screen ]
```

Caption under the boxes: "Nothing executes. Data never passes through the model."

## Slide 2. What this release is, and is not [2]

- Is: describe a screen · explain a number · refine by conversation · GenUI in an intranet app · a Drishti panel in the chatbot
- Is: one contract between the two products, governed, measured, optional
- Is: portable (a generated view runs on any origin, links working) and known to SAJHA (its own tools pull Drishti's inventory)
- Is not: any non-AI feature, generated code, a model computing figures, writes, rows sent to a model
- Is not: a second model gateway, loop or chatbot inside Drishti

**Visual: table, two columns.**

| In | Out |
|---|---|
| Describe a screen | Any feature that is not GenAI |
| Explain this number | Generated code, HTML, CSS, script |
| Refine by conversation | A model computing or pricing |
| GenUI through the embed | Writes from a generated Sutra |
| A Drishti panel in the chatbot | Rows of data sent to a model |
| Governed, measured, optional | A second loop, gateway or chat in Drishti |
| Portable views: any host, working links | A view that works only in Drishti's console |
| The Drishti tool pack for SAJHA (a signed plugin) | A change to SAJHA's core from Drishti's repository |

## Slide 3. The actors [3.1]

- Eight actors; each has one job
- Only the Drishti server touches data; only SAJHA touches a model
- A host paints: it supplies the painter bundle, a token provider and a link resolver, and holds no data

**Visual: table.**

| Actor | Its part |
|---|---|
| The person | asks, reads, refines, saves |
| Drishti console | the Describe box, the conversation; the first host of a generated view |
| Drishti server | the MCP server; validates, compiles, binds; the only place data lives |
| SAJHA | the tool-use loop; model providers, keys, budgets, policy, audit; Ask SAJHA |
| Rachana tool pack / Rachana MCP server | the same tools for non-Drishti data; the shared prompt kit |
| The Drishti tool pack (in SAJHA) | a signed plugin built by Drishti: the handshake, the inventory per person, the view placed in the chat |
| The model | writes Sutras and patches; reads names, types and problem lists |
| A host | any page that shows a view: console, chatbot, intranet application, desktop, static page |

## Slide 4. The architecture [3.2]
- Hosts: Drishti's console, Ask SAJHA, an intranet application, a desktop host, a static page
- Two arrows cross between the products, and both carry the person's identity
- "ask": Drishti asks SAJHA to run a turn (only when the person starts in Drishti)
- "tools": SAJHA calls Drishti's MCP server (always); in SAJHA the Drishti tool pack is the model's way to the inventory
- A third path has no model: every host reads the view from Drishti's public address with a view token

**Visual: diagram. Boxes and arrows exactly as follows.**

Top row, four boxes: `Drishti console` · `Intranet app` · `Desktop · static page` · `Ask SAJHA (chat)`.
Middle row, two large boxes: left `Drishti, at its public address` containing four lines: `MCP server (twelve tools)`,
`Rachana compiler · bind as the viewer`, `ephemeral views`, `view API · Data Protocol · events.v1`; right `SAJHA` containing six
lines: `tool-use loop + planner`, `tool catalogue + policy`, `Drishti tool pack`, `Rachana tool pack`, `model providers + budget`,
`audit`. Far right, one box: `The model (any provider)`.

Arrows:
1. Each of the four top boxes → `Drishti, at its public address`, one shared label "painter bundle + view token + absolute
   addresses + typed links".
2. `Ask SAJHA` → `SAJHA`, label "session".
3. `Drishti` → `SAJHA`, label "ask (as the person)".
4. `SAJHA` → `Drishti`, label "tools (as the person)".
5. `SAJHA` ↔ `The model`, label "chat".

Highlight arrows 3 and 4 in the accent colour, and arrow 1 in a second colour with the note "the same path for every host".

## Slide 5. Who does what to a Sutra [3.3]

- Generated by the model; everything else happens in Drishti
- Checked against **that person's** catalogue: a name they may not read is an error
- Bound as the viewer: row rules and masks apply as on any screen
- A saved Sutra has no model in its path

**Visual: pipeline diagram, seven steps left to right, each with a "where" line under it.**

| Step | Where |
|---|---|
| Ground | Drishti server (catalogue, fragments) |
| Generate | the model, called by SAJHA |
| Validate and fix | Drishti server (Rachana compiler) |
| Compile | Drishti server |
| Bind to data | Drishti server, as the viewer |
| Paint | any host (one painter, from the bundle the host serves) |
| Keep | Drishti server (ephemeral, then review) |

Colour the "Generate" step differently: it is the only step outside Drishti and the only one that costs tokens.

## Slide 6. Why the loop runs in SAJHA [3.5]

- One model gateway: keys, budgets, fallback and cache in one place, built today
- One chatbot: Ask SAJHA gets Drishti screens for the price of the tools
- One loop: planners, tool shortlist and conversation memory exist
- Cost: an identity path across two products, to be proven first
- Validation, compile and bind stay in Drishti's process

**Visual: table, two options.**

| | Loop in SAJHA (recommended) | Loop inside Drishti |
|---|---|---|
| Keys, budgets, fallback, cache | one place, built | a second set |
| Chatbot | Ask SAJHA, built | a second chat |
| Identity | two hops to prove | none |
| Without SAJHA | no GenUI | GenUI works |
| "One way per job" | kept | broken |

## Slide 7. The contract: one MCP server, twelve tools [4.1]

- Off by default; every call is a call by a person; read-only; small answers
- SAJHA sees the tools as `drishti__<tool>`
- Four tools share names and schemas with Rachana's own, so prompts and tests are reusable
- Only two tools can ever carry a value, and only if the site allows it
- The inventory tools carry a version tag: "not modified" costs almost nothing

**Visual: table.**

| Tool | Returns | Values? |
|---|---|---|
| `describe_capabilities` | the handshake: versions, features on, limits, version tags | no |
| `describe_catalogue` | tables, kinds, functions, lenses this person may use | no |
| `describe_table` | columns: name, type, description, masked | no |
| `list_fragments`, `get_fragment` | fragment cards; one fragment's page | no |
| `sutra_examples` | grammar card, examples | no |
| `validate_sutra` | ok, or problems with fixes | no |
| `open_view` | an ephemeral view as a portable descriptor: id, absolute addresses, summary; no token | no |
| `describe_view`, `patch_view` | an outline; a patch applied | no |
| `read_data` | a small masked aggregate | only if allowed |
| `explain_value` | the explanation tree of one cell | only if allowed |

## Slide 8. The contract in three small messages [4.2]

- The catalogue is already filtered to the person
- A problem has a code, a place, the expected names and a fix
- Opening a view returns a descriptor of absolute addresses and a summary: never rows, never a token

**Visual: code, three boxes side by side.**

Box 1, title "describe_catalogue →":

```json
{ "tables": [
  { "name": "risk.var_by_desk",
    "about": "1-day 99% VaR per desk",
    "rows": "small", "licence": "internal" } ],
  "libraries": ["core@1", "finance@1"] }
```

Box 2, title "validate_sutra →":

```json
{ "ok": false,
  "problems": [ { "code": "RCH-2071",
    "message": "no column 'VAR99'",
    "expected": ["VAR_99", "DESK"],
    "fix": { "title": "Use 'VAR_99'" } } ],
  "fixed": "rachana: 1 …" }
```

Box 3, title "open_view →":

```json
{ "view": "gv_9c1e",
  "url": "https://drishti…/genui/gv_9c1e",
  "descriptor": { "portable": 1,
    "viewmodel": "https://drishti…/views/gv_9c1e/viewmodel",
    "data": { "protocol": "rdp", "base": "https://drishti…/rdp/v1" } },
  "summary": { "panels": 3, "generated": true } }
```

## Slide 9. Identity: the person, end to end [4.4]

- No service user that can read for everybody, on any hop
- SAJHA calls Drishti with **that person's** Drishti credential (SAJHA's per-user modes exist today)
- Drishti calls SAJHA with **that person's** SAJHA token
- No token is passed through; each product holds only the token issued for it
- One identity provider for both makes both sign-ins silent

**Visual: diagram, a loop of four boxes with labelled arrows.**

```
[ Person ] ── signs in ──► [ Drishti ] ── the person's SAJHA token ──► [ SAJHA ]
     ▲                                                                    │
     └── view, bound as the person ── [ Drishti ] ◄── the person's Drishti token ──┘
```

Under the diagram, a two-row table:

| Mode for SAJHA → Drishti | Verdict |
|---|---|
| `oauth_user` (each person signs in once) · `token_user` (a personal API token) | allowed |
| `static` (one identity for all) · `service_with_user` (a trusted header) | refused |

## Slide 10. The Drishti tool pack for SAJHA [5.1, 5.2]

- SAJHA gets its own tools that pull Drishti's inventory, so it knows what to call and what to do
- A signed SAJHA plugin, `drishti-tools`, built in Drishti's repository; SAJHA's core is not changed
- It holds no credential and no address: it calls the proxied Drishti tools as the person
- Five tools; the Sutra jobs (validate, open, patch, explain) stay on the wire tools

**Visual: table.**

| Tool | Called by | Job |
|---|---|---|
| `drishti_capabilities` | the planner, first | the handshake: reachable, versions, features on, version tags |
| `drishti_inventory` | the model | what exists for this person on one card: functions, kinds, tables, fragments |
| `drishti_describe` | the model | one thing in detail: a table, a function, a fragment's `with:` |
| `drishti_show_view` | the model, in the chat | puts an opened view into the chat: live panel, snapshot card or link |
| `drishti_view_token` | the chat page, never a model | a view token for the live panel |

Footer line: "Built, signed and published with every Drishti release; installed through SAJHA's plugin catalogue."

## Slide 11. Inventory, change detection, and one tool per job [5.3 to 5.7]

- The inventory is cached per person in SAJHA and revalidated by a version tag on every turn
- A new library, a new fragment or a removed right is seen at the next turn, with no restart
- A stale card cannot leak: Drishti checks every name again and binds as the person
- For a Drishti screen the tools are Drishti's; for other data, the Rachana pack's; the schemas are shared
- The GenUI planner offers a turn exactly ten tools

**Visual: left, code; right, a table.**

Code, title "drishti_capabilities →":

```json
{ "ok": true, "contract": 1, "values": "none",
  "features": { "describe": true, "refine": true,
                "explain": true, "snapshot": false },
  "etags": { "catalogue": "7b1e…", "fragments": "c04a…" } }
```

| Job | For a Drishti screen | For other data |
|---|---|---|
| What exists | `drishti_inventory`, `drishti_describe` | the tools that own the data |
| Fragments | the libraries installed in Drishti | the Rachana pack's `list_fragments` |
| Check a Sutra | `drishti__validate_sutra` | the Rachana pack's `validate_sutra` |
| Show it | `drishti__open_view` + `drishti_show_view` | the Rachana pack's `render_dashboard` |

Footer line: "Asked of SAJHA's session: seven small items; the page block for the live panel is the only page change."

## Slide 12. Runs anywhere: the portable view [6.1 to 6.3]

- A generated view runs on any origin: no Drishti console, no relative address, no Drishti static file, no cookie
- The descriptor: a small document of absolute addresses, with no data and no secret
- Data through the Rachana Data Protocol, live patches over events.v1, both as the person
- The console is the first host, not a special case: what works there works elsewhere

**Visual: diagram, two boxes one above the other, three labelled arrows between them, and a code box beside them.**

Upper box: `Any host page (console · Ask SAJHA · intranet app · desktop · static page)` containing one line: `painter bundle
(vendored by the host, or from a stated origin)`, and three small tabs: `descriptor`, `token provider`, `link resolver`.
Lower box: `Drishti, at its public address` containing three lines: `view API: ViewModel, stream, follow a link, token, snapshot`,
`Rachana Data Protocol: tables, pages, queries`, `events.v1: live patches`.
Arrows from upper to lower, labelled: "absolute addresses" · "view token (header, never the address)" · "typed references".

Code, title "the descriptor":

```json
{ "portable": 1, "view": "gv_9c1e",
  "viewmodel": "https://drishti…/views/gv_9c1e/viewmodel",
  "data":  { "protocol": "rdp", "base": "https://drishti…/rdp/v1" },
  "live":  { "protocol": "events.v1", "sse": "https://drishti…/events/v1/sse" },
  "token": { "endpoint": "https://drishti…/views/gv_9c1e/token" },
  "painter": { "min": "2.6.0", "bundle": "https://drishti…/render/2.6.0/" },
  "links": { "deep": { "base": "https://drishti.bank.example" } } }
```

## Slide 13. Links that work on any host [6.5]

- A link is a typed reference, never an address: entity, view, action, console page, outside address
- The host may supply one resolver function; it answers what a reference does, or nothing
- The chain: the host's resolver → in place, in the same container → an absolute deep link to Drishti → disabled, with the reason
- Never a relative link, never a dead one; a deep link carries a kind and an id only

**Visual: a four-step chain on the left, a table on the right.**

Chain, top to bottom, each box with its condition:
`0 The host's resolver (if it answers)` → `1 In place: the linked view drawn in the same container, with Back` →
`2 Deep link: https://drishti.bank.example/open/{kind}/{id}, a new window` → `3 Disabled: readable, with a tooltip saying why`.

| Host | What a click on a counterparty does |
|---|---|
| Drishti's console | opens in place |
| SAJHA's chatbot, live panel | opens in place, inside the chat panel |
| An intranet application | its own client page, by its resolver; otherwise in place |
| A snapshot card, or offline | the absolute deep link to Drishti; actions disabled: "Open in Drishti for the live view" |

Code under the table, title "an intranet application's resolver":

```js
resolveLink: (ref) => ref.type === 'entity' && ref.kind === 'counterparty'
  ? { how: 'host', run: () => router.go('/clients/' + ref.id) }
  : undefined      // no opinion: in place, then deep link, then disabled
```

## Slide 14. Tokens, assets and security away from Drishti [6.4, 6.6, 6.8]

- The view token: one person, one view, read-only, masked, five minutes; never in an address, a tool result or a log
- Each host obtains it on its own channel; the model never receives one
- The painter bundle: complete (fonts and icons inside), versioned, integrity-hashed; vendored by the host or from a stated origin; never a CDN
- Drishti answers only registered origins, and never allows cookies across origins

**Visual: two tables side by side.**

| Host | Where its token comes from |
|---|---|
| Drishti's console | its session |
| An intranet application | its backend's token exchange |
| Ask SAJHA | the pack, with the person's Drishti credential |
| A desktop host | its privileged process |
| A static page | the single sign-on window |

| Concern | Rule |
|---|---|
| Which pages may call | the registered applications' exact origins |
| Data in addresses | none: tokens in headers, filters in request bodies |
| The host's CSP | the bundle's origin for scripts and fonts, Drishti's for connections; no inline script, no `eval` |
| A stolen token | minutes, one view, read-only, masked |

## Slide 15. When live is not possible: degraded modes and the snapshot [6.7]

- The best mode the host allows is chosen, and the screen always says which one it is
- A lost connection or an expired sign-in is marked at once; values are removed after a set time
- A snapshot card is self-contained: rendered data, its as-of and capture times, an absolute deep link
- A snapshot is a copy that outlives the access check: off by default, masked, capped, audited
- Never silently stale

**Visual: a ladder of four rows, best at the top, and a mock of the card.**

| Mode | The host can | Marked |
|---|---|---|
| Live | run the painter; reach Drishti by HTTP and a stream | "Live", as-of time |
| Live without push | run the painter; HTTP only | "Updated 10:42:05, refreshing every 30 s" |
| Snapshot card | show a self-contained card; no network, at most 2 MB | "Snapshot · as of 2026-11-18 · captured 10:42 · not live" |
| Link | show text | an absolute link and its expiry |

Mock of the card (a rounded box): a header band reading "Snapshot · as of 2026-11-18 · captured 10:42 EST · not live"; under it
the title "VaR by desk", three tiles, a table of six desks whose desk names are underlined links; at the foot a button
"Open in Drishti" with the caption "https://drishti.bank.example/genui/gv_9c1e".

## Slide 16. Flow 1: describe a screen [7.1]

- Ground (the pack's inventory), write, check, fix, open: about five tool calls
- The Sutra is nine lines: three fragments, two tables by name
- The view is ephemeral; "Save" goes through the normal review
- If it cannot be built in three attempts: the valid panels, a plain sentence, and the inferred screen as a floor

**Visual: sequence on the left, code on the right.**

Sequence (four lifelines: Person, Drishti, SAJHA, Model; messages in order):
1. Person → Drishti: "VaR by desk, with the P&L trend"
2. Drishti → SAJHA: `sajha_ask`
3. SAJHA → Model: question + tools
4. Model → SAJHA's Drishti tool pack: `drishti_inventory` (cached; the pack asks Drishti only for what changed)
5. Model → Drishti (through SAJHA): `validate_sutra` → "1 problem, fixed"
6. Model → Drishti (through SAJHA): `open_view`
7. Drishti → Person: skeleton, then panels

Code:

```yaml
rachana: 1
sutra: desk-var
title: VaR by desk
libraries: [ core@1, finance@1 ]
data:
  var: { ref: risk.var_by_desk }
  pnl: { ref: risk.desk_pnl }
panels:
  - { use: finance/var-tiles@1, with: { var: "sum($.var, 'VAR_99')" } }
  - { use: core/ranked-bars@1,  with: { rows: $.var, label: DESK, value: VAR_99 } }
  - { use: core/figure-trend@1, with: { rows: $.pnl } }
```

## Slide 17. Flow 2: explain this number [7.2]

- The tree comes from the compiler: exact, free, works with AI off
- A model only turns the tree into a sentence, where the site allows aggregates
- Every figure in the sentence must be in the tree, or the sentence is dropped

**Visual: a small tree on the left, the sentence on the right.**

Tree:

```
-1,204,300  =  percentile( scenario P&L , 0.99 )
               └─ 509 values from risk.scenario_pnl
                  └─ filter: desk RATES on 2026-11-18
```

Sentence box: "The desk's 1-day 99% VaR is the 99th percentile loss across 509 scenarios: 1.20 million."

Label under the tree: "Level 1: no model". Label under the sentence: "Level 2: a model, checked against the tree".

## Slide 18. Flow 3: refine by conversation [7.3]

- The model reads an outline, not the whole screen
- It answers with a patch document: a few hundred bytes
- Applied atomically, validated, and only the changed panels redraw
- Every revision is kept: undo is "open the previous revision"

**Visual: code, two boxes, with an arrow "the person: drop the trend, add the VaR limit" between them.**

Box 1, title "describe_view → the outline":

```json
{ "sutra": "desk-var", "revision": 1, "panels": [
  { "id": "tiles",   "kind": "strip" },
  { "id": "by-desk", "kind": "hbar" },
  { "id": "trend",   "kind": "line" } ] }
```

Box 2, title "patch_view ← the patch":

```yaml
patch: desk-var@1
ops:
  - { op: remove,    at: "panels[trend]" }
  - { op: add-panel, after: by-desk,
      panel: { id: limit, use: finance/limit-gauge@1,
               with: { used: "sum($.var, 'VAR_99')", limit: "$.limits.var" } } }
```

## Slide 19. Flows 4 and 5: inside an intranet app, and inside the chatbot [7.4, 7.5]

- Embed: a box and a place to draw; drawn in place; links resolved by the host; always masked; never writes
- One new scope, `embed:genui`, granted per application
- Chatbot: the same loop, started from Ask SAJHA
- The panel in the chat is the best mode the chat allows: a live panel, a labelled snapshot card, or an absolute link

**Visual: left, code; right, a three-step ladder.**

Code:

```html
<drishti-describe id="ask" server="https://drishti.bank.example"></drishti-describe>
<div id="here"></div>
<script type="module">
  import { render } from 'https://portal.bank.example/vendor/drishti-painter/2.6.0/drishti-painter.js';
  ask.addEventListener('drishti:describe', e =>
    render(here, e.detail.descriptor, { tokenProvider: getToken, resolveLink: myLinks }));
</script>
```

Ladder (bottom to top):

| Step | The person sees | Needs |
|---|---|---|
| A. Link | "Open in Drishti": an absolute address | nothing new; always works |
| B. Snapshot card | the view as it was, marked "Snapshot" with its times, in the chat | snapshots on for the site; a self-contained card (no network, at most 2 MB), not read by the model |
| C. Live panel | the real view in the chat, links opening in place | Ask SAJHA's page hosting the painter, with a view token from the pack (asked of SAJHA's session) |

## Slide 20. Safety: the gates before a model [8.1 to 8.5]

- Access is enforced where the data lives; nothing depends on what the model was told
- By default a model sees names, types and descriptions only
- Licensed data never goes to an outside model
- A cell is text, never an instruction
- No code, ever: the grammar has nowhere to put it

**Visual: funnel diagram, seven stages top to bottom, then a table of two rows.**

Funnel stages, in order:
`module, lens, function` → `role and row rules` → `column masks` → `data licence` → `values policy` → `size cap` → `prompt masker` →
output box `the tool result`.

| `drishti.genui.values` | The model receives |
|---|---|
| `none` (default) | names, types, descriptions, problem lists, outlines |
| `aggregates` | also small masked aggregates and the figures of an explanation |

Footer line: "Rows: never."

## Slide 21. Governance: cost, audit, and no AI [8.6 to 8.8]

- The owner names the models and the cap; SAJHA enforces budgets per person, per role and per site
- A fast model drafts, a stronger one repairs; three attempts at most
- Every turn is recorded: who, the words, the Sutra, the repairs; never row data
- AI off, SAJHA absent or a model down: only these features disappear

**Visual: table.**

| Situation | What the person sees |
|---|---|
| GenUI off | no Describe box; the explanation tree still works |
| SAJHA not reachable | the same; the designer's Describe uses its rules |
| Account not connected | a "Connect" prompt |
| Model down or budget spent | a plain sentence; open views untouched |
| A saved generated view | works always: it is an ordinary Sutra |
| A host cannot run the painter or reach Drishti | a labelled snapshot or an absolute link; never old figures without their time |

## Slide 22. Evaluation: "it works" is a number [9]

- About 120 tasks over synthetic data; one third held out
- Checked by code against the ViewModel, not by a model
- Run through SAJHA, so the planner and the identity path are measured too
- Recorded runs replay in every drill with no model call

**Visual: chart (horizontal bars with target lines) and one code box.**

Bars, in this order, each labelled with its target:

| Measure | Target |
|---|---|
| First-try validity | ≥ 85% |
| Valid after certain fixes | ≥ 93% |
| Valid within three attempts | ≥ 99% |
| Fulfilment | ≥ 85% |
| Panels from fragments | ≥ 70% |
| Correct refusals | 100% |

Show the bars as targets only (hollow), with the note "aims until the first run". Draw the refusals bar in the accent colour with
the note "not negotiable".

Code:

```yaml
id: describe-014
as: { user: rates-trader-1 }
ask: "VaR by desk, biggest first, with the P&L trend"
expect:
  - { reads: [risk.var_by_desk, risk.desk_pnl] }
  - { fragments_share: ">= 0.7" }
  - { never_reads: [hr.salary] }
```

## Slide 23. Before the release, and the risks [10, 11]

- Needed from the products release: the prompt kit, certain fixes, describe and patch, explanation, fragment libraries, the harness
- Needed from SAJHA: mostly there today, the plugin system included; seven small asks, of which the page block for the live panel is the only page change
- Three risks lead: identity across two products, unmeasured validity on Drishti's profile, the live panel in the chat
- The two additions bring two more: a console-only address in a portable view, and the pack resting on SAJHA's internals
- Each leading risk has a spike that answers it early

**Visual: risk matrix (likelihood across, impact up) with the eleven largest risks placed, and a legend.**

| Risk | Likelihood | Impact | Spike |
|---|---|---|---|
| R1 Identity end to end | Medium | High | S1, S2 |
| R2 Products release late or below its targets | Medium | High | – |
| R3 Validity on Drishti's profile unmeasured | Medium | High | S6 |
| R5 Live panel in the chat | High | Medium | S5, S7 |
| R6 The model picks the wrong tools | Medium | Medium | S4 |
| R7 Slow first panel | Medium | Medium | S3 |
| R8 A value leaks to a model | Low | High | marker test |
| R13 Scope creep | High | Medium | cut lines |
| R18 A relative address or console-only file in a portable view | Medium | High | S7 |
| R20 A view token leaks on a page Drishti does not control | Low | High | S7, S8 |
| R24 The pack rests on SAJHA internals that are not a stated interface | Medium | Medium | S8 |

## Slide 24. The plan, and what the owner decides [12, 13]

- About 160 builder-days identified; plan on about 185 (135 accepted, plus portable views and the tool pack)
- Eight spikes first, accepted to start now: S1 the thin end to end, S7 portability, S8 the tool pack
- Five milestones: the contract, describe, refine and explain, the other surfaces, proof and release
- Cut lines agreed now; a missed refusal target is never a cut line
- Twenty-two decisions: four accepted, one pending, the rest open; the ones that shape everything are on this slide

**Visual: left, a timeline; right, a table.**

Timeline, six blocks in order: `G0 Proofs (S1–S8)` → `G1 The contract` → `G2 Describe` → `G3 Refine + explain` →
`G4 Embed + chatbot` → `G5 Proof + release`. Mark G0 as "may run during the products release".

| Decision | Recommendation | Status |
|---|---|---|
| GD1 Where the model loop runs | in SAJHA; validation, compile and bind in Drishti | accepted |
| GD10 Models and the cap | the owner names them; a fast model drafts, a stronger one repairs | accepted |
| GD13 Writes from a generated view | refused | accepted for now |
| GD14 The size | about 135 builder-days; start the spikes | accepted |
| GD4 What a model may see | names only by default; aggregates by choice; rows never | pending |
| GD3 How SAJHA calls Drishti | as the person: `oauth_user`, with `token_user` as the fallback | open |
| GD15 Portable by construction | every host, the console included, paints from one descriptor | required; design to confirm |
| GD16 Where a view token travels | never in a tool result; each host fetches it as the person | open |
| GD19 Snapshots | off by default; masked, capped, labelled | open |
| GD20 The Drishti tool pack | a signed SAJHA plugin built by Drishti; no change to SAJHA's core | required; design to confirm |
| GD22 The size after the additions | about 185 builder-days | open |

---

## Appendix slides (optional, if the audience asks)

| Slide | Content | Source |
|---|---|---|
| A1 | The work items W1 to W26 with sizes | document 12.1 |
| A2 | The eight spikes with their questions | document 12.3 |
| A3 | The full risk register R1 to R27 | document 11 |
| A4 | All twenty-two decisions, with their status | document 13 |
| A5 | What exists in SAJHA today, and what is asked of it (A1 to A7) | document 5.7, 10.2 |
| A6 | The streamed-screen timeline (status, skeleton, panel-replace, done) | document 4.5 |
| A7 | The pack's folder, and the build, sign, pack and install steps | document 5.6 |
| A8 | The resolver contract in full, and the four host examples | document 6.5 |
| A9 | The hosts side by side: bundle, token, links, best mode | document 6.9 |
