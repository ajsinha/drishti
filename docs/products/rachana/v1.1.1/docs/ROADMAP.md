<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary. All rights reserved. -->
# Roadmap

What is planned and not built. Everything here is a proposal: a name or syntax marked "proposed syntax" is settled only when it enters
[SPEC.md](../spec/1/SPEC.md) with a `since` level and conformance cases. Growth inside major 1 is additive: a Sutra valid today
means the same in every later minor, and the names held for later minors are listed in `spec/1/reserved.yaml`.
A proposal that would change the meaning of anything that works today is not built as an addition: it needs a new language
level and the owner's decision first. Text is measured by code point in every runtime (SPEC, decided). What exists today is in
[DESIGN.md](DESIGN.md); the decisions behind it in [DECISIONS.md](DECISIONS.md).

## 1. Language growth: Rachana-EL (expressions)

The aim: the one expression language a person or a model needs for a data screen, with the function names of Excel, the table work of
SQL and dataframes, the safety of CEL and Starlark, and what none of them has together: the same bytes in Java, JavaScript and Python,
and a value that can explain how it was computed. Built already: exact numbers, the Python-style syntax batch of level 1.1 (records,
spread, slices, pipe, `??`, lambdas, comments), `let` and `functions` (level 1.2) and library functions.

**Time and money.** Typed `date`, `time`, `timestamp`, `duration` and calendar `period` (values stay ISO 8601 text on the wire), so
date arithmetic is checked and exact; time zones from a pinned zone pack so the three runtimes never disagree; `money` as an exact
amount with an ISO 4217 code that never mixes currencies and converts only through a host `Rates` hook, so a figure cannot silently add
USD to EUR.

```text
timestamp_add($.sent, duration(minutes = 15))      date_trunc($.trade_date, 'quarter')       (proposed syntax)
money(1250.5, 'USD') + money(10, 'USD')            convert($.mtm, 'CAD')                     (proposed syntax)
```

**Ranges, types, units, vectors.** Range values `a..b` (membership and slicing without building a list); a checked-mode type language
(`list<T>`, `record{...}`, `T?`, `fn(T) -> U`) inferred, never written inside an expression, so a misspelt field is caught with a
"did you mean"; units of measure (`qty(5, 'MWh')`, adding kilograms to metres is an error) for commodity screens; `vec` and `matrix`
with element-wise operators, `dot`, `solve`, `covariance_matrix` for risk.

**Everyday helpers and honest errors.** `between` and `bucket` (the fix offered for `lo < x < hi`); a generated table of about 80
spellings from other languages, each refused with the Rachana way to write it; about 30 list and record functions (`filter`, `zip`,
`flatten`, `pluck`, ...); the 49 list functions Drishti's engine supplies today classified into Core and a `statistics` library;
`group_by` with records instead of the fixed `{label, value, count}` shape.

**Tables.** Table verbs on a `data:` table (`extend`, `select`, `top`, `append`, `lookup`), joins (`inner`, `left`, `full`, `semi`,
`anti`, bounded by the cell budget), `pivot`, `unpivot` and `rollup` with subtotals, windows (`lag`, `lead`, `rank`, `running`,
`moving_avg`) and time series (`resample`, `date_spine`), calendars and fiscal periods. A pipeline compiles to a plan that pushes
filter, sort, group and page to the host and runs the rest locally with an identical result; a verb that cannot be pushed over a table
above the client threshold is refused, never silently slow.

```text
$.trades
  |> extend({ usd: convert(@.mtm, 'USD'), limit: lookup($.limits, 'cpty', @.cpty, 'limit') })
  |> filter(r => amount(r.usd) > r.limit)                                             (proposed syntax)
$.pnl |> window('desk', 'date', { cum: running(sum(@.pnl)), rk: rank(@.pnl, desc = true) })   (proposed syntax)
```

**Statistics.** Descriptive statistics over lists and columns (`percentile`, `stdev`, `zscore`, `histogram` bins), pinned by
`CoreMath` so the three runtimes agree. **Not in the language: risk measures and pricing** (VaR and ES, bond prices and yields, option
formulas). A screen shows risk figures the server computed, with their explanation; it never computes them. Display helpers for those
figures stay in scope (formats such as `bp1`, sparklines of a P&L vector the server sent).

**Text, formats, locale, geography.** More text functions (`regex_extract_all`, `regex_split`, named groups into a record), Unicode
functions on a pinned Unicode pack, plural and locale-aware formats from pinned locale packs, formats with arguments
(`fmt(v, 'amount', decimals = 3)`; `bp1`, `multiple1`, `bytes`), and geography (`geo_distance`, `bbox`).

**Values made for the screen.** Display values the painter draws with a plain text form for exports (`spark`, `bar`, `bullet`,
`badge`, `icon`, `delta`, `heat`); tone rules as expressions returning a tone name; screen-state functions (`selection`,
`controls()`, `viewer()`, `width_class()`, `panel_rows()`) as functions, never new root words, so no data field is ever shadowed; what-if
(`what_if`, `sensitivity`, `goal_seek`) over ordinary controls; live-row state (`previous`, `changed`, `direction`, `row_age`).

**Approximation and masking.** `approx_distinct` and `approx_percentile` with pinned sketches (any input order gives the same bytes);
`is_masked`, `mask(x, 'last4')` and labels, so an author can hide more, never less.

**Explanation, budgets, tooling.** `explain` returns a tree for any cell (source span, operation, value, rows read, a sentence from
the function's template), bounded and deterministic, so it is a conformance kind: the painter offers "how was this computed" and a
model gets ground truth instead of an invented narrative. Counted budgets with a cost model; an optimiser (constant folding, common
sub-expressions, vectorised execution) that must leave the whole suite passing with it on and off. `tests:` in a Sutra or library; a
formatter and linter with one canonical style; a language server (diagnostics, completion over the real columns, hover) for editors; a
few-shot catalogue of expression examples for assistants; translators from Excel formulas (`rachana from-excel "=IFERROR(VLOOKUP(...), 0)"`).

## 2. Language growth: the Sutra document model

Every addition below is invisible to a host that did not ask for it, and an older painter either ignores it safely or refuses the file
with words; none runs code.

**Layout and composition.** A `group` container kind (nesting, and `each` to bind inner panels once per element); `overlays:` (modal,
drawer, popover) declared once and bound only while open; `layout.at` tiers by container width (`narrow`, `medium`, `wide`); a workspace
document that docks several Sutras as tiles, tabs or windows with named channels carrying state between them; sizing and density
(`height: fill`, `gap`, `density`); a bounded canvas layout and print rules for report pages; `extends` and `override` (a fixed list of
the existing editing operations, depth at most 4); a `theme:` and panel `variant:` chosen by name, never by colour.

```yaml
panels:
  - { id: lanes, kind: group, each: "$.desks", arrange: row, panels: [ { id: pnl, kind: metric, value: "sum(@.rows, 'pnl')" } ] }
overlays:
  - { id: ticket, kind: drawer, panels: [ { id: form, kind: form } ] }                        # proposed syntax
```

**State, flows, interaction.** A `flow:` state machine for wizards (flat, at most 32 states, reachability checked); a canonical
view-state document (controls, state, selections, column filters) that a host saves as a bookmark or link and every runtime writes with
the same bytes; undo and redo; the rest of the action model (`on:` triggers beyond `row-click` and `bar-click`, button `actions:`,
`when`); selections shared between panels (`selections:`, `brush`, `filter-by`, `highlight-by`); `select: multi` with `bulk:` actions;
`drill:` on charts and pivots; context menus; key chords; optimistic host actions that the painter marks pending and restores with the
host's words; tips and hover cards; alerts; drag and drop.

**Data.** Declared, typed screen arguments (`args:`) so a missing one is a state with words, not a crash; windowed tables (sort, filter and
group go to the provider, the painter asks for `{panel, offset, size}`); derived tables in a fixed order of steps; lazy panels
(`load: visible`); cache hints; streaming detail (`tail`, `flash: changes`, `throttle`). A `topic` source type for live feeds is proposed;
today a host exposes a topic as a named live table.

**Forms.** Host validation answers in one fixed shape; dependent fields and `checks:` written in Rachana-EL and evaluated by the same
compiled runtime in the browser and on the server; repeating and conditional sections; step rules; more field types (`radio`,
`checkboxes`, `slider`, `time`, `duration`, `tags`, `file` as a host handle, never content).

**Visuals.** A `chart` kind with a closed mark grammar (`bar`, `line`, `area`, `point`, `rule`, `band`, `text`, `tick`; layers; stacking);
cell rules and in-cell visuals (tone, icon, bar, colour scale, sparkline); grid depth (column groups, pinning, group-by with subtotals,
multi-sort); more kinds in batches (`treemap`, `sunburst`, `sankey`, `funnel`, `box`, `bullet`, `radar`, `calendar`, `gantt`, `image`);
map layers (points, bubbles, lines, shaded regions, no tile servers); 3D `view`; rich text that is still never HTML.

**Accessibility, languages, budgets, permissions.** `a11y:` on view and panel (label, summary template, live politeness, heading level;
a lint warning for a chart without a summary); message tables and `t('key')` with the locale from the host and right-to-left by `dir`;
a `budget:` of static and measured limits; `requires:` naming a permission the host answers per reader (Core knows no role or user);
`sensitive`, `export: false` and `copy: false` hints.

**Authoring tools.** Patch documents (the editing operations as one closed, id-addressed, atomic list, so a model edits a 60-panel screen
by sending 300 bytes); `rachana describe` (an outline under 2 KB: ids, kinds, what each reads); document tests (`rachana test`: given
data, args, controls and state, expect id-addressed paths or a snapshot) and painted snapshots; a rule engine for moving a file between
levels with `--check` for a build; `status: draft | active | deprecated`, `replaces:` and `rachana diff a b` in words for release notes.

## 3. The MCP tools and the evaluation harness

Generative UI is built on Rachana, not inside it: the language has no AI key and the compiler calls no model. The GenUI kit ships with
Rachana 1.1.0, not in this release line; this is what it still has to deliver beyond the first tools.

- The kit's **MCP tools ship only as a sajhamcpserver plugin** (`tools/sajha/`: `search_fragments`, `get_fragment`, `check_sutra`,
  `publish_ui`; owner decision, 2026-11-28). There is no standalone Rachana MCP server and no `drishti-rachana-mcp` package: the
  governed tool catalogue is the one host, and what is still to build (`describe_table`, `update_dashboard`, resources for the prompt kit,
  an MCP prompt carrying the system block) is added to that plugin.
- A **prompt kit** generated from the specification so it can never describe a language the compiler does not accept: a grammar card
  (about 1,500 tokens), one card per panel kind, a function list, reviewed examples by intent (each one a conformance case), a system
  block, a fix guide and the JSON Schema for structured output; every snippet in every card is compiled by a test.
- **Certain fixes** on the twenty most frequent problem codes (a machine-applicable `fix`), and lint rules for generated Sutras (a donut
  of more than 12 slices, a `line` whose `x` is not ordered, a metric with no `fmt`, a table of more than 20 columns).
- An **evaluation harness** (`rachana eval --provider <adapter> --model <id> --kit <version>`): about 200 tasks, each a plain-words
  question, one to three tables and assertions over the ViewModel checked by code; metrics are first-try validity, validity after
  auto-fix and within three attempts, fulfilment and the fallback rate; a prompt-kit change that lowers first-try validity on the held-out
  set by more than 2 points does not ship; recorded runs replay offline in CI. Provider adapters live outside the compiler.

## 4. Streaming and ephemeral Sutras

A defined prefix rule: a Sutra is drawable as soon as its head (identity, `data`, `args`, `state`) is complete, and each further panel
arrives as a `panel-add`, so a host paints a model's answer while it is written, with links intact. The result is an ephemeral Sutra
that a person can keep, by saving it through the host's normal path. Needs the patch documents of section 2 and the client renderer
principles: easy adoption, embedding in place with no new window unless asked.

## 5. Sutra for everyone

The idea: someone who has never seen the product writes a few lines of Sutra, in a file or a playground, and gets a terminal-grade live
screen: a virtualised grid that ticks sixty times a second with flashing cells, a computed and heat-shaded column, sorting and
filtering.

```yaml
rachana: 1
sutra: fx-quotes
data:
  quotes: { topic: "prices.fx.*", key: symbol, snapshot: /api/quotes.json }     # proposed syntax
title: FX · live quotes
panels:
  - kind: grid
    rows: $.quotes
    columns:
      - { label: Pair,          bind: "@.symbol" }
      - { label: Bid,           bind: "@.bid", fmt: price4, flash: true }       # flash: proposed syntax
      - { label: Spread (pips), bind: "(@.ask - @.bid) * 10000", fmt: amount1, tone: heat }
```

- **The language in layers.** One grammar: the portable Core (identity, `data:`, panels, controls, expressions, formats) that runs
  anywhere, and profiles that add what is bound to a product (catalogue sources, governance). A Sutra that uses only Core keys and
  references can be exported with a sample of its data and opened anywhere; a screen prototyped on a CSV file is promoted to a governed
  screen by changing where one name points, not by rewriting it. Core deliberately gets no scripts, loops, style text, HTML or addresses
  assembled by expressions.
- **Small additions that make a first screen.** A shorthand `title: "text with ${parts}"`; `flash: true` and `tone: heat` on a live
  column; `version` optional (then `1`); and **light inference**: with no `panels:`, Core infers a grid with typed, formatted columns,
  up to four headline figures and one chart from the data's shape, and shows the full Sutra it wrote, so people learn the grammar by
  editing a real screen.
- **Work where it is cheap.** The compiler plans and says what it planned: expressions, sort, filter, group and pivot in the browser up
  to a row threshold (a worker, Arrow columns), on the server above it; a panel that would need more rows than the threshold is never
  silently truncated ("this needs a server source: 3,200,000 rows"); row conditions travel as the shared filter model and as an
  expression syntax tree limited to a push-down subset, never as text to interpret.
- **Live data, precisely.** A keyed table that messages change; the compiler knows which cells depend on which fields, so a message that
  changes one field of one row re-evaluates only that row's cells; aggregates are maintained incrementally where the function allows.
- **The adoption toolkit.** A playground (three panes: the Sutra with completion, the live screen, the data; share by link with the
  Sutra in the URL fragment; copy as a tag or one self-contained HTML page; a speed bar that shows compile time, first paint and frame
  time); ten template screens of about ten lines each (a trading blotter, risk heatmap, pivot with drill, world map drill, KPI board,
  live chart, order book ladder, master and detail, timeline and network, a million rows); editor support from one language service
  (check, complete, hover, fixes, format, outline) in the Elements code editor, VS Code and the IntelliJ family, and the generated JSON
  Schema in any YAML editor; **errors that teach** (where, what in the author's words, what to write instead as pasteable text, "did
  you mean" over the nearest key, function or field of the actual data, a machine-readable fix, a stable code); a one-page grammar card
  for assistants and people; a fifteen-minute tutorial whose Sutras the test suite paints, and starter projects (static HTML, Node,
  Express, Flask, Spring) under fifty lines of host code each.
- **Speed as a gate.** Compile time, first paint and frame time are budgets in the release gate, shown on the glass in the playground.

## 6. Other planned items

- Libraries `genomics`, `economy`, `geo`, `ops` and `ai` beside `core` and `finance` ([README](../README.md#rachana-fragment-libraries)).
- The `profiles:` top-level key (SPEC 2.2), so a file says which profile it needs.
- Library gates LG6 (every runtime agrees on `rachana lib test`), LG7 (every example paints) and LG9 (a model composes screens from the
  library's fragments); `rachana lib` commands beyond the ten that exist (`new`, `add`, `update`, `test`, `doc`, a gallery).
- Desktop hosts: the same web painter inside an application's embedded browser, fed through the in-process bridge of the Data Protocol
  (Electron first, then Swing through JCEF, then .NET through WebView2); `drishti-rachana-electron` is a placeholder.
- A formatter (`rachana fmt`), `rachana explain` (the plan), and a language server, as in section 1.
