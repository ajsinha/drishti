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
# Sutra Next: one grammar for every screen

Status: **phases 1 to 3 built (2.7.0)**: the fragment library, the `doc:` docstring and its lint, composite fragments, migrated
screens (ir-curve and the seven world-economy economics screens) and the library read API (phase 1); per-panel sources over any
pack's kinds and events evaluated on the server (phase 2, [section 6](#6-sources-over-several-packs), [7](#7-events)); nested child
panels (phase 3, [section 8](#8-nested-child-panels)); the worked screen is the trading pack's **book desk**. Phases 4 to 8 are
**design, not built**. Direction of the product owner, 2026-10-22:
the board/chip paradigm (UI_BOARD.md, parked on branch `ui-board-preview`) is dropped; Sutra itself grows from an entity browser
into the UI library, in ONE grammar, built on what Sutras already have (panels, shared blocks, `use:`/`with:`, controls, masks).

## Contents

1. [Principles](#1-principles)
2. [Where we start: today's Sutra and blocks](#2-where-we-start-todays-sutra-and-blocks)
3. [Fragments: the library](#3-fragments-the-library)
   - [3a. The Drishti UI kit](#3a-the-drishti-ui-kit)
4. [The doc: docstring](#4-the-doc-docstring)
5. [Composite fragments](#5-composite-fragments)
6. [Sources over several packs](#6-sources-over-several-packs)
7. [Events](#7-events)
8. [Nested child panels](#8-nested-child-panels)
9. [Live panels, refresh control and freshness](#9-live-panels-refresh-control-and-freshness)
10. [Forms and data entry](#10-forms-and-data-entry)
11. [Actions that write (2.8.0 write-back)](#11-actions-that-write-280-write-back)
12. [Dynamic screens](#12-dynamic-screens)
13. [Layout: placing panels](#13-layout-placing-panels)
14. [Lint: what is checked](#14-lint-what-is-checked)
15. [Security: masks and roles through events, children and writes](#15-security-masks-and-roles-through-events-children-and-writes)
16. [The editor](#16-the-editor)
17. [Phases](#17-phases)
18. [Open questions](#18-open-questions)

## 1. Principles

- **One grammar.** A screen, a fragment, a form, a child panel and a dynamic screen are all Sutra YAML (Rachana). No second model
  (no boards, chips or traces); everything new is a key on what exists, read by the same parser, linted by the same lint.
- **Secure as the viewer.** Every value on a screen is bound as the person looking: their roles, their field masks, their kinds.
  An event, a child panel, a form default or a write never sees more than the viewer may; a masked value is never passed on.
- **Plain to read.** A business analyst reads a Sutra top to bottom and knows what the screen shows. Names say what a thing shows
  (`curve-table`, `ranked-bars`), never which pack it came from. Every fragment and Sutra explains itself in its `doc:`.
- **Beautiful by default.** No geometry needed: panels flow into a sensible layout, formats come from the vocabulary, tones from
  the data. Placement is optional and in grid units, never pixels.
- **Honest UI.** Every screen says where its data came from, how fresh it is, what was masked and what was inferred
  (`data-provenance`). A paused feed says it is paused; a stale value says it is stale; a refused write says why.
- **Testable.** A Sutra is a pure function from a record (and the viewer) to a view model. Every new capability keeps that: events,
  children and refresh are inputs; the view model is compared in tests, as the ir-curve migration proves.

## 2. Where we start: today's Sutra and blocks

A Sutra (`<name>.v<N>.sutra.yaml`) has `match:`, `title:`, `strip:`, `controls:`, `panels:` and `keys:`. Since 2.3 a pack may keep
panels it repeats in a **shared block** (`<name>.v<N>.block.yaml`, `SutraBlocks`): `params:` with defaults, `panels:` whose text reads
`%{param}`, used as `{ use: deal-ticket@1, with: { rate-fmt: pct4 } }`. The use is replaced by the block's panels, parameters filled,
**before** the Sutra is checked, so lint, matching, masks and drawing see ordinary panels. The trading pack's 130 deal Sutras are
built from six blocks. Sutra Next builds on exactly this mechanism: a fragment is a block with a docstring, in a shared library.

## 3. Fragments: the library

A **fragment** is `<name>.v<N>.fragment.yaml` with `fragment: <name>`; it is used like a block. The shared library is the
**Drishti UI kit**'s `fragments/` folder (`config/ui-kit/fragments`, [section 3a](#3a-the-drishti-ui-kit)): platform UI vocabulary,
not domain content, which the server and the CLI `sutra lint|test` ALWAYS load whatever packs are enabled, so every Sutra may use
it. A pack may add its own fragments in its Sutra folders, namespaced by the pack (`trading/deal-ticket@1`); kit fragments are
unqualified (`series-line@1`). Later the content store (CONTENT_STORE.md) holds workspace fragments the same way (phase 7).

```yaml
# config/ui-kit/fragments/series-line.v1.fragment.yaml
rachana: 1
fragment: series-line
version: 1
description: "A line chart of one series read from a list of rows: x along the bottom, y up the side."
params: { id: series, title: Series, rows: $.POINTS, x: TENOR, y: VALUE, fmt: price2, code: '', key: '', area: '' }
doc: { ... }                                   # section 4
panels:
  - id: %{id}
    kind: line
    title: "%{title}"
    code: %{code}                              # an empty optional parameter leaves the key out (YAML null)
    key: %{key}
    area: %{area}
    rows: %{rows}
    x: %{x}
    y: %{y}
    fmt: %{fmt}
```

**Versioning.** `name@N` pins a version; `use: name` takes the newest. A change that would alter a screen is a new file
(`series-line.v2.fragment.yaml`); Sutras move one at a time. Since the doc's `since:` and the file's version travel together, a
browser can show "since 2.7.0, v1".

**Phase 1 library** (14 fragments, all `@1`; the last three arrived with the economics screens below):

| Fragment | Panel kind | What it shows |
|---|---|---|
| `series-line` | line | one series from a list of rows: x along the bottom, y up the side |
| `curve-table` | table | a curve's pillars: tenor, instrument, quote, zero rate, discount factor |
| `curve-overview` | composite | `series-line` then `curve-table` over the same rows (section 5) |
| `field-list` | kv | every field of one object as label and value |
| `ranked-bars` | hbar | one bar a row, labelled and sized by a value |
| `cashflow-ladder` | ladder | cash flows by date: date, type, signed amount, currency |
| `event-timeline` | timeline | dated events with a label, a detail line and a status |
| `key-figure` | metric | one headline number with its label and a caption |
| `linked-entities` | links | what the record refers to and what refers to it |
| `data-provenance` | provenance | how the view was built: source, freshness, layout, masks |
| `explainer-note` | markdown | a short note telling the reader how to read the figures |
| `measure-table` | table | a name column and two measures, the first totalled |
| `region-map` | map | the record's country on a world map, every country shaded by one figure |
| `source-footer` | composite | data source and licence, how the view was built, linked entities |

Phase 2 added `row-picker` (table): a table of records whose rows, clicked, fire an event with `{ id, ref }` (both typed `id`), the
master of a master-detail screen (section 7).

**The migrated screen.** `market-data/ir-curve` (the test page `/v/ir-curve/CRV-USD-OIS`) is the first screen built from the
library: `curve-overview` (line and pillars), `series-line` (1Y forwards, right), `data-provenance`, `linked-entities`. It was chosen
because it is small, used daily, uses five panel kinds' worth of generic shapes (line, table, provenance, links), has masked-field
cases (zero rates, instruments) and its fragments fit a dozen other market-data screens. `SutraNextEquivalenceTest` draws it with
the old inline layout and with the fragments, for a full and a masked reader, and the view models are identical (values, masks,
titles, order); only the layout's own version in "How this view was built" differs.

**The economics screens (phase 1 progress).** The world-economy pack's seven economics Sutras (`country-group`,
`economic-forecast`, `economy`, `fiscal-position`, `labour-market`, `macro-indicator`, `region-economy`) are built from the library
too, each with a complete `doc:`. They needed three more generic shapes, added to the library:

| Fragment | Panel kind | What it shows |
|---|---|---|
| `measure-table` | table | a list as a table: a name column and two measures, the first totalled at the foot, the second toned by sign when asked |
| `region-map` | map | a world map with the record's country highlighted and every country shaded by one figure, each a click away |
| `source-footer` | composite | `field-list` (data source and licence, right), then `data-provenance` and `linked-entities`: the foot of any screen over published data |

Every economics screen ends with `source-footer`; lines and paths use `series-line`, bars `ranked-bars`, facts `field-list`, the
economy's map `region-map`, the largest economies and the jobs by sector `measure-table`. One panel stays written in place: the IMF
projections table (nine columns), because a fragment's columns are fixed (a parameter is text, not a list). `EconomicsSutraNextEquivalenceTest`
draws all seven on the pack's committed samples (`ECG-SAS`, `FCST-JPN-WEO`, `ECON-IND`, `FISC-USA`, `LABR-NGA`,
`MACRO-BRA-GDP-GROWTH`, `REGE-AU-NSW`) with the old generated layouts (test data, as version 2) and with the fragments, for a full
reader and a masked one (`GDP_USD_BN`, `GROWTH`, `DEBT`, `UNEMPLOYMENT`, `LATEST`, `ISO3` redacted): identical but for the layout's
own version. The generator leaves them alone (`hand_written` in `tools/packgen/world/make.py`). world-economy extends nothing:
the fragments come from the Drishti UI kit, which loads whatever packs are enabled (the test enables world-economy alone).

## 3a. The Drishti UI kit

**Decision (product owner, 2026-10-22).** The fragment library is platform UI vocabulary, not domain content. It is the **Drishti
UI kit**, which the server always loads whatever packs are enabled, so any screen, dynamic screen or Gen UI stream can rely on it.
It also holds the assets and the brand Gen UI decorates with. Built in 2.7.0: the folder, the loaders, the read API; Gen UI
streaming of the kit comes later (2.9.0, AI).

```
config/ui-kit/                       drishti.ui-kit.dir (DRISHTI_UI_KIT_DIR), default ./config/ui-kit
  fragments/                         <name>.v<N>.fragment.yaml: series-line, curve-table, key-figure, source-footer, row-picker, ... (15)
  assets/
    icons/                           favicon.svg, favicon-32.png, icon-192.png, icon-512.png
    images/                          pictures a screen or a Gen UI answer may show
    logos/                           drishti-logo.png, drishti-mark.svg, drishti-icon.png
  brand.yaml                         name, tagline, logo and icon refs, colours per theme
<data folder>/ui-kit/                drishti.ui-kit.site-dir (DRISHTI_UI_KIT_SITE_DIR): the site's overrides
  brand.yaml                         its keys win over the kit's, map by map
  assets/                            a file here wins over the kit's file of the same path
```

| Part | What it is | How it is read |
|---|---|---|
| **Fragments** | the generic panels of section 3, unqualified (`series-line@1`) | loaded first by every Sutra load (server: `SutraRegistry`; CLI: `sutra lint|test`, which finds `config/ui-kit` from the working folder or above the paths it checks) |
| **Theme tokens** | the colours of the console's base themes (`terminal`, `light`), named after its `--d-*` tokens (`bg`, `surface`, `ink`, `muted`, `accent`, `link`, `pos`, `neg`) | `brand.yaml` `themes:`; a Gen UI renderer maps them onto its own surface |
| **Icons, images, logos** | files under `assets/` | `GET /api/v1/ui-kit/assets/{path}`, content-hash URLs |
| **Brand** | `name`, `tagline`, `logos: {full, mark, icon}`, `icons: {...}`, `themes:` | `GET /api/v1/ui-kit` `brand` (with `urls`: each logo and icon's versioned URL, and `overridden`) |

**Pack-namespaced fragments.** A pack may still ship fragments (and the older blocks) in its Sutra folders. Each is namespaced by
its pack's name (`trading/deal-ticket@1`) and also answers to its plain name (`deal-ticket@1`), so today's pack Sutras keep working.
On a clash with a kit fragment's name the kit wins: the plain name is the kit's, the pack's answers only qualified, and the load
warns (`DRS-2048`, a warning: in the server log and on the CLI's error output). A composite may pin a qualified inner fragment
(`use: trading/legs@1`). Between packs, a later (more specific) pack folder still wins a plain name.

**Brand overrides.** A site does not edit the kit: it writes `<data folder>/ui-kit/brand.yaml` with the keys it changes
(`name: Acme Risk`, `themes: { light: { accent: "#0b5" } }`); maps merge key by key and anything else is the site's value. A site
logo goes in `<data folder>/ui-kit/assets/logos/` and `brand.yaml` points at it. The data folder (not the content store) is used
because the brand is per deployment and read at every request; a cluster gives each node the same data folder or the same file.

**The read API.** `GET /api/v1/ui-kit` returns `{fragments, packFragments, assets, brand, streaming}`: the kit's fragments with
their docstrings (`origin: kit`), the enabled packs' own (`origin: <pack>`), every asset (`path`, `kind`, `type`, `size`, `hash`,
`origin: kit|site`, `url`), the merged brand. `GET /api/v1/ui-kit/assets/{path}` serves one file with a strong ETag (its SHA-256)
and a 304 for a matching `If-None-Match`; the URL the listing gives carries the hash (`?v=<16 hex>`) and is cached for a year
(`immutable`), any other URL for five minutes. A path with `..`, a hidden name, a backslash or a link out of the folder is a 404; an
SVG is served with a sandboxing Content-Security-Policy. `GET /api/v1/sutras/library` stays as the fragments-only alias.

**Gen UI streaming (later).** A Gen UI answer (AI_PROVIDERS.md, 2.9.0) will stream screens as fragment uses (`{ use: key-figure@1,
with: {...} }`) checked by the same lint and masks, decorated with the kit's brand and assets by URL; the kit is what the model is
told it may use (the docstrings), and `streaming: false` in the summary says it is not yet on.

## 4. The doc: docstring

Every fragment and every Sutra carries a `doc:` block, like a Python docstring. The component browser (phase 8) and an AI agent
(2.9.0) read it through `GET /api/v1/ui-kit` (or its fragments-only alias `GET /api/v1/sutras/library`); nothing else is needed to use a fragment correctly.

```yaml
doc:
  summary: "A line chart of one series read from a list of rows: x along the bottom, y up the side."   # one line, ≤ 120 chars
  description: >-                                   # what it shows, when to use it, when not to
    Draws one series (a curve's zero rates, a price history, forward points) from a list of rows, one point a row ...
  params:                                           # every params: key, nothing else
    rows: { type: expression, default: $.POINTS, meaning: "The list of rows to draw, e.g. $.POINTS" }
    fmt:  { type: format, default: price2, meaning: "The named format of the y values (price2, pct4, rate5)" }
    key:  { type: key, default: '', meaning: "The function key that brings the panel forward; none when empty" }
  uses: [series-line@1, curve-table@1]              # composites only: the inner fragments, pinned (section 5)
  emits: []                                         # events it fires (phase 2), each { name, payload, when }
  expects: []                                       # what it reads from a parent (phase 3), each { name, type, meaning }
  examples:                                         # at least one; each a working use of this fragment
    - { use: series-line@1, with: { id: curve, title: "Zero rates (%)", rows: $.POINTS, x: TENOR, y: ZERO_RATE } }
  since: "2.7.0"                                    # the release it arrived in
```

- **Parameter types:** `text`, `expression` (Rachana-EL), `field` (a field name of a row), `format` (a named format), `key` (a code
  or F-key), `area`, `id`, `number`, `boolean`, `kind` (an entity kind).
- **A Sutra's doc** has the same keys; `params:` describes its `controls:` (may be `{}`), and `examples:` are URLs or commands that
  open it (`/v/ir-curve/CRV-USD-OIS`, `CRV CRV-AUD-OIS`). Required: `summary`, `description`, `examples`, `since`.
- From phase 2, `emits:` and `expects:` are filled where a fragment fires or reads events:

```yaml
  emits:
    - { name: select, payload: { tenor: field, zero: number }, when: "a row of the pillars table is clicked" }
  expects:
    - { name: curve, type: { kind: ir-curve }, meaning: "the curve whose pillars are shown, from @parent" }
```

## 5. Composite fragments

A fragment's `panels:` may `use:` other library fragments (built in phase 1; blocks still may not use blocks).

```yaml
# config/ui-kit/fragments/curve-overview.v1.fragment.yaml
fragment: curve-overview
version: 1
params: { rows: $.POINTS, tenor: TENOR, zero: ZERO_RATE, zero-fmt: price2, curve-id: curve, curve-title: Zero rates (%),
          curve-code: '', curve-key: '', table-id: points, table-title: Pillars, table-key: '' }
doc: { uses: [series-line@1, curve-table@1], ... }
panels:
  - use: series-line@1
    with: { id: "%{curve-id}", title: "%{curve-title}", code: "%{curve-code}", key: "%{curve-key}",
            rows: "%{rows}", x: "%{tenor}", y: "%{zero}", fmt: "%{zero-fmt}" }
  - use: curve-table@1
    with: { id: "%{table-id}", title: "%{table-title}", key: "%{table-key}", rows: "%{rows}", tenor: "%{tenor}",
            zero: "%{zero}", zero-fmt: "%{zero-fmt}" }
```

- **Explicit pass-through.** Parameters reach an inner fragment only through its `with:` (`title: "%{curve-title}"`); nothing is
  inherited. A parameter not passed takes the inner fragment's own default (here `df-fmt: df4`).
- **Pinned.** An inner use names its version (`series-line@1`); `use: series-line` inside a fragment is `DRS-2048`.
- **Depth and cycles.** At most three fragments deep (a Sutra's use of A, A's of B, B's of C); a fourth level or a cycle
  (`a > b > a`) is `DRS-2048`, found when the library loads, not only when a Sutra uses it.
- **Doc.** `doc.uses` lists exactly the inner fragments with versions (`DRS-2055` otherwise); the component browser expands them.
- **Where used** (phase 8) indexes both levels: `series-line@1` is used by `curve-overview@1` and, through it, by `ir-curve@1`.
- The view model is flat: the panels of a composite are ordinary panels of the screen (masks, keys and ids apply as if written in
  place, and two composites on one screen must be given distinct ids).

## 6. Sources over several packs

Today a panel reads the matched record (`$`) and, through links, entities of other kinds. Phase 2 lets a panel name its own source:
any kind of any enabled pack, looked up by an expression over the screen's record, the viewer's controls or a parent's event.

```yaml
panels:
  - use: series-line@1
    with: { id: usd-ois, title: USD OIS, rows: $src.POINTS, x: TENOR, y: ZERO_RATE }
    source: { kind: ir-curve, id: "'CRV-USD-OIS'" }                       # market-data pack
  - use: ranked-bars@1
    with: { id: desk-dv01, title: DV01 by book, rows: $src.BY_BOOK, label: BOOK, value: DV01 }
    source: { kind: desk-risk, id: "$.DESK", as-of: ctl.asof }            # market-risk pack
```

- `source:` takes `kind`, `id` (an expression), optional `as-of` (history queries, HISTORY_QUERIES.md) and `pack` (only when two
  packs serve the same kind). Inside the panel `$src` is that record; `$` stays the screen's record.
- Each source is fetched once per screen however many panels read it, concurrently, through the connectors and routes that serve the
  kind; a source the viewer may not open (`kinds` of their role) leaves the panel out with "not available to your role".
- `data-provenance` lists every source of the screen with its freshness.

**As built (phase 2).** `source:` written as a mapping is the new form; written as text (`source: "link($.CURVE, 'ir-curve')"`) it
is today's option, unchanged (the panel reads that linked entity as `$`).

| Key | Meaning |
|---|---|
| `kind` | the entity kind read (required); any enabled pack's |
| `id` | an expression for one entity's id: `$src` is that record |
| `where` | instead of `id`: a condition over each entity of the kind (`@` the entity as the viewer sees it); `$src` is the list of those it keeps, at most `limit`; the first 500 entities of the kind are read (`ScreenWire.WHERE_SCAN`): use `search` for large kinds |
| `search` | instead of `id`: an expression for the text searched in the kind; `$src` is the list of the entities found, at most `limit` |
| `limit` | 1 to 200 (default 20), for `where` and `search` |
| `as-of` | an expression for the business date read (`YYYY-MM-DD`); the screen's date when absent |
| `pack` | the pack serving the kind: documentation and lint today (a kind is served by one connector; routing is by kind) |
| `model` | a BI model source: refused (`DRS-2057`), not built yet |

- **As the viewer.** A kind the viewer's roles may not open is never fetched: the panel says "no access to <kind>". The documents
  are masked by the viewer's field masks before the panel binds them (`$src`), so a masked field reads `•••` in every cell, and a
  `where` condition over a masked field never holds (a hidden value never selects a record).
- **Once per request.** Panels naming the same source share one read (per kind, id or condition, date).
- A **group of children** may name the source its panels read (section 8); a panel's own `source:` wins over its group's.
- A **use** may carry the source: `{ use: field-list@1, with: { rows: $src }, source: { kind: credit-limit, id: "..." } }` puts it on
  every panel the fragment brings (also `when` and `waits`; `emits`, `children`, `span`, `height` and `area` only on a use that brings
  one panel, else `DRS-2048`).
- A `kv` panel with `rows` and no columns lists the plain fields of the record its rows name at bind time (`field-list@1` over `$src`),
  since layout inference cannot see a record read at bind time.
- **Another Sutra of the kind.** `?sutra=<name>` on a view (`GET /api/v1/views/{kind}/{id}?sutra=book-desk`, the console's
  `/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk`) draws it with that Sutra (the newest version; it must match the kind,
  else `DRS-5001`); the screen's events and children use the same one. This is how a screen that is not the kind's default is opened
  before dynamic screens (section 12).

## 7. Events

A panel **emits** named events with a payload; other panels on the screen read them as `@<panel>.<event>.<path>`. A child panel
reads its parent's as `@parent.<event>.<path>`. Events are the only way panels talk; there are no global variables.

```yaml
panels:
  - use: curve-table@1
    with: { id: points, rows: $.POINTS }
    emits:
      - { name: select, on: row-click, payload: { tenor: "@.TENOR", zero: "@.ZERO_RATE" } }
  - id: pillar-detail
    kind: kv
    title: "Pillar ${@points.select.tenor}"
    when: "@points.select != null"                      # shown once a row is picked
    columns:
      - { label: Zero, bind: "@points.select.zero", fmt: price2 }
      - { label: DF at pillar, bind: "lookup($.POINTS, 'TENOR', @points.select.tenor).DF", fmt: df4 }
```

- **Triggers** (`on:`): `row-click`, `point-click` (charts), `bar-click`, `select` (a control), `submit` (forms), `load` (fires once
  the panel has data), `tick` (a live update). Today's `on-select: { by, field, filter }` (hbar filtering a table) becomes a sugar
  for `emits: [{ name: select, on: bar-click }]` plus `filter: "@by-asset.select.ASSET"` on the target; both read the same.
- **Payloads** are expressions over the clicked row (`@.FIELD`) or the record, evaluated **server-side, as the viewer**; a masked
  field in a payload is the mask (`•••`) and can never be used to look anything up (section 15).
- **Events are state of the view, not of the data:** the server recomputes the affected panels (`POST /views/{kind}/{id}/events`
  with `{panel, event, payload-ref}`), returns only the panels that read the event, and the URL keeps the last selection so a link
  reproduces the screen.
- A fragment declares what it can emit in `doc.emits`; a Sutra may only wire events a fragment declares (lint).

**As built (phase 2).**

- **Grammar.** `emits: [{ name, on, payload }]` on a panel: `name` lower-case kebab (by convention `select`, `open`, `drill`,
  `filter`, `change`), `on` one of `row-click` (a table or ladder) and `bar-click` (an hbar); `point-click`, `select`, `submit`,
  `load` and `tick` are refused as later phases (`DRS-2057`). Each payload field is an expression over the clicked row
  (`trade: "@.TRADE_ID"`) or `{ bind, type }` with `type` one of `id`, `text`, `number`, `date`, `bool` (`any` when unwritten).
- **Reading.** Rachana-EL reads `@<panel>.<event>.<field>` (panel ids may be kebab: `@by-asset.select.ASSET`) and, in a child,
  `@parent.<event>.<field>`; an event not fired reads as nothing. `$src` is the panel's source record (section 6).
- **Waiting.** A panel that reads an event not yet fired is drawn waiting: no data, its `waits:` text (or its group's, else "Pick a
  row in <panel> to fill this panel"). A `when:` over an event does not hide the panel at first paint; it waits, and after the event
  a `when` that is not true makes it wait again.
- **On the server, per click.** `POST /api/v1/views/{kind}/{id}/events` with `{panel, event, row, selections}` (`row` is the row's
  `ev`: its place in the panel's data, drawn on each clickable row and bar). The server rebuilds the clicked panel as the viewer, checks
  the row is one the viewer is shown (its `where` and controls), evaluates the payload twice: over the row as the viewer sees it
  (**seen**: what titles, cells and `where` filters read, a masked field `•••`) and over the stored row (**raw**: kept on the server,
  read only to look up a source's `id`, `where` or `search`). The answer: `selection` (`panel`, `event`, `token`, the seen
  `payload`), `panels` (every panel that reads the event, directly or through another, rebound; nothing else), `children` (the
  clicked panel's groups, section 8) and `cleared` (panels whose own selections the click made stale: their children close).
- **Selections are tokens** (`IdTokens`, ported from the UI Board): the browser holds `tok_…` tokens, never a payload; each request
  sends the tokens it holds; only the same person resolves a token, only on the same screen (kind and id), only while it lives
  (`drishti.views.selection-ttl`, default 30 minutes, renewed on use). Anyone else, another screen, a forged or expired token:
  `403 DRS-5002`. Tokens live in the memory of the node that issued them: a screen's requests go to the node that drew it.
- **Lint** (`DRS-2057`, every Sutra at load): a read of a panel the screen does not have, of an event it does not emit or of a
  payload field the event does not carry; a panel reading its own event; panels that wait on each other in a cycle (through events
  or as parent and child); `$src` in a panel with no source; a source `id` reading a payload field typed `number`, `date` or `bool`;
  a panel called `parent`. Fragments: a fragment whose panels emit lists each event in `doc.emits` (`{ name, on, payload }`), and
  one whose panels read `@parent` says so in `doc.expects` (`DRS-2055`).
- The console (`static/js/sutra-wire.js`, `static/css/wire.css`): a clickable row or bar takes a click, Enter or Space; the picked
  one is marked (`aria-selected`, `aria-pressed`); the listening panels are swapped in place and announced; the clicked table is not
  redrawn. The kit fragment `row-picker@1` is a table that emits on row-click with `{ id, ref }`, both typed `id`.

## 8. Nested child panels

A panel may have **children**: panels that populate when the parent fires, placed relative to it.

```yaml
panels:
  - id: trades
    kind: table
    title: Trades on the desk
    rows: $.TRADES
    columns: [ { label: Trade, bind: "@.TRADE_ID" }, { label: Product, bind: "@.PRODUCT_TYPE" }, { label: MTM, bind: "@.MTM", fmt: signed0 } ]
    emits: [ { name: open, on: row-click, payload: { trade: "@.TRADE_ID", system: "@.SOURCE_SYSTEM" } } ]
    children:
      - place: detail-right                       # expand-row | detail-right | detail-below | tabs
        show: on-select                           # on-select (default) | always | on-expand
        source: { kind: trade, id: "@parent.open.trade" }
        panels:
          - { use: field-list@1, with: { id: terms, title: Terms, rows: $src } }
          - use: cashflow-ladder@1
            with: { id: flows, rows: $src.CASHFLOWS }
            children:                              # depth 2: a flow's fixings below it
              - place: expand-row
                source: { kind: fixing, id: "@parent.select.FIXING_ID" }
                panels: [ { use: series-line@1, with: { id: fixings, rows: $src.HISTORY, x: DATE, y: RATE } } ]
```

- **Lazy.** A child is fetched only when its parent fires (`show: on-select`) or is expanded (`on-expand`); `always` loads with the
  parent. The console asks `GET /views/{kind}/{id}/panels/{panel}/children?event=...`; the result is an ordinary list of panel views.
- **Depth limit 3** (screen panel, child, grandchild, great-grandchild refused, `DRS-2056` planned). A child of a composite counts
  its own nesting, not the fragment depth.
- **Placement**: `expand-row` (inside the parent table, under the clicked row), `detail-right` / `detail-below` (a pane beside or
  under the parent, replaced on each selection), `tabs` (each child a tab of the pane). Section 13 places these within the grid.
- A fragment whose panels read `@parent` declares it in `doc.expects`; lint refuses using it where no parent provides the event.

**As built (phase 3).**

- **Grammar.** `children:` written as a list of groups `{ place, show, source, waits, panels }` (written as text it stays a tree
  table's `children: "@.KIDS"`). `place` defaults to `detail-below`, `show` to `on-select`; `expand-row` only under a table or ladder
  (`DRS-2056`). Child panels need ids, unique on the whole screen (`DRS-2024`), may use fragments, and may have their own sources,
  events and children. A panel inside a tab cannot be wired.
- **Lazy.** Nothing of a child is read at first paint. `on-select` groups are filled by the click's answer; `always` groups by
  `GET /api/v1/views/{kind}/{id}/panels/{panel}/children?sel=<token>...` when the page loads; `on-expand` groups by the same call
  when the reader opens the panel's Details button. A group whose parent has no selection comes back `hidden`.
- **Replace and clear.** A new pick replaces the children; a pick that makes another panel's selection stale closes that panel's
  children (`cleared`); closing a container forgets the selections of the panels inside it.
- **Depth 3.** Screen panel, child, grandchild; a great-grandchild is `DRS-2056`; so is `@parent` in a panel that is not a child.
- **Other packs.** A child reads any kind through its or its group's `source:` (the book desk's cash flows read the trade).
- **Placement in the console.** `expand-row`: a row under the picked one holds the panels; `detail-right`: the parent and a detail
  pane side by side (stacked below 1100 px); `detail-below`: a pane under the parent; `tabs`: a pane with one tab per panel (arrow
  keys move between them). Below 700 px a pane opens as a full-width sheet with a Back button. Theme tokens only, so every theme
  draws them.

**The worked screen: the book desk** (`config/packs/trading/sutras/pages/book-desk.v1.sutra.yaml`, opened as
`/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk`): the book's trades (`row-picker@1`, `open` with the trade id and the
counterparty); a click opens the trade's cash flows under the row (`cashflow-ladder@1` over the `trade` kind, trading pack) and fills
the right column: the counterparty (core pack, by id) and its credit limit with utilisation by tenor (`ranked-bars@1`, counterparty-risk
pack, by `where`). For a reader with `COUNTERPARTY` masked the payload's counterparty is `•••`, the counterparty panel still follows it
(the same reader, by token; the id is never sent) and the limit panel shows nothing (a masked value selects no record).
`SutraNextEventsTest` (server) and `test_sutra_wire_browser.py` (console) exercise it.

## 9. Live panels, refresh control and freshness

Today the live stream (LIVE.md) updates a whole page. Phase 4 makes refresh a property of each panel, under the reader's control.

```yaml
panels:
  - use: series-line@1
    with: { id: curve, rows: $.POINTS, x: TENOR, y: ZERO_RATE }
    refresh: { mode: live }                          # live (pushed) | every | manual
  - use: ranked-bars@1
    with: { id: by-book, rows: $src.BY_BOOK, label: BOOK, value: PNL }
    source: { kind: desk-pnl, id: "$.DESK" }
    refresh: { mode: every, interval: 30s, min: 5s, user-can-change: true }
    stale-after: 2m                                  # past this the panel shows "stale" in amber
```

- **The reader controls it**: each refreshing panel shows a freshness chip ("live", "updated 12 s ago", "paused", "stale 3 min")
  with pause, resume and an interval picker within `min`; the choice is remembered per person and screen, never in the Sutra.
- `every` runs on the server's bounded scheduler (no per-viewer threads); identical requests from many viewers share one fetch
  (the view cache already keys by record, Sutra and mask set).
- Freshness comes from the source's own timestamps (`fetchedAt`, `updatedAt`, `staleAfter` already in the view's provenance); a
  panel never claims to be live when its source is not.
- The whole-page pause (one control) pauses every panel; a paused page says so in the title strip.

## 10. Forms and data entry

A form is a panel of kind `form`: fields with types, defaults and validation, and a submit that emits (phase 5) or writes (phase 6).

```yaml
panels:
  - id: new-limit
    kind: form
    title: Request a limit change
    fields:
      - { id: counterparty, label: Counterparty, type: entity, kind: counterparty, default: "$.COUNTERPARTY", required: true }
      - { id: limit, label: New limit (USD), type: number, fmt: amount0, min: 0, max: 5e9, required: true }
      - { id: until, label: Valid until, type: date, min: today, max: "today + 365d" }
      - { id: reason, label: Reason, type: text, max-length: 500, required: true }
    validate:
      - { rule: "limit > $.CURRENT_LIMIT * 0.5", message: "A cut of more than half needs the credit desk: call them" }
    submit: { label: Send for approval, emits: request }
```

- **Field types**: `text`, `number`, `date`, `datetime`, `boolean`, `choice` (options as for controls), `entity` (a type-ahead over
  a kind, as the viewer may see it), `money` (amount and currency).
- **Validation** runs twice: in the browser for speed, and on the server as the authority (the same Rachana-EL rules). Errors are
  shown beside the field, in plain words.
- **Defaults** are expressions over the record, bound as the viewer; a masked field cannot be a default (it would leak), lint warns.
- Drafts are kept per person in the browser only (an unsent form survives a reload); nothing is saved until submit.

## 11. Actions that write (2.8.0 write-back)

An action writes through a connector's `writes:` template, exactly as WRITE_BACK.md designs it: a change request, an edit lane with
one owner, the edit log, approvals, off by default, permitted by role.

```yaml
panels:
  - id: limit
    kind: kv
    columns: [ { label: Limit, bind: $.LIMIT, fmt: amount0, edit: { action: set-limit } } ]
actions:
  set-limit:
    label: Change limit
    write: { connector: credit-store, template: update-limit }       # the connector's writes: entry
    with: { id: $.LIMIT_ID, limit: "@form.limit", reason: "@form.reason" }
    roles: [credit-officer]
    approval: four-eyes                                                # none | four-eyes (WRITE_BACK.md section 9)
    confirm: "Change the limit of ${$.COUNTERPARTY} to ${fmt(@form.limit, 'amount0')}?"
```

- **Off by default**: a Sutra's actions do nothing until `drishti.writeback.enabled` and the connector's `writes:` are on, and the
  viewer holds a role named in `roles:`. Otherwise the edit control is not drawn (honest UI: a read-only screen looks read-only).
- **Lane owner and edit log**: every action becomes a change request in the edit log, ordered in its lane, performed by the lane's
  owner (WRITE_BACK.md sections 6 to 8); the screen shows "pending", "approved", "written" or "refused, because ...".
- **Pushed back live**: when the write lands, the updated record is published on the live stream; every viewer of that record sees
  the new value (with their own masks) and a short "changed by A. Sinha just now" mark.
- A write can only carry values the viewer could see and type; masked fields are never sent (section 15).

## 12. Dynamic screens

A **dynamic screen** is a Sutra written at runtime (by a person in the editor, or by an AI agent from the docstrings) rather than read
from a pack: the same YAML, checked by the same parser and lint, rendered as the viewer, and saved to the person's workspace in the
content store (CONTENT_STORE.md) when they keep it.

```yaml
rachana: 1
sutra: my-usd-rates
version: 1
match: { kind: screen }                     # a screen of its own, not bound to one record
doc: { summary: "USD curves side by side", description: "...", examples: ["/s/my-usd-rates"], since: "2.7.0" }
panels:
  - use: series-line@1
    with: { id: ois, title: USD OIS, rows: $src.POINTS, x: TENOR, y: ZERO_RATE }
    source: { kind: ir-curve, id: "'CRV-USD-OIS'" }
  - use: series-line@1
    with: { id: govt, title: USD govt, rows: $src.POINTS, x: TENOR, y: ZERO_RATE }
    source: { kind: ir-curve, id: "'CRV-USD-GOVT'" }
```

- `POST /api/v1/screens/render` renders a Sutra text (not saved) as the viewer; `POST /api/v1/screens` saves it to the workspace;
  `/s/<name>` opens it. A saved screen keeps its fragment versions pinned.
- The same limits as a pack Sutra: size, expression depth, number of sources and panels (`drishti.screens.max-*`), and nothing a
  dynamic screen can do that a pack Sutra cannot (no new kinds, no writes without the action rules).

## 13. Layout: placing panels

Today placement is light: `area: right` (62 uses), `span: 4` (12), `height` (2) and a few `layout: tree|columns`; the rest flows.

**Automatic stays the default.** A Sutra with no geometry flows: main panels in order down the wide column, `area: right` panels
down the narrow column, charts and tables sized by their content. Nothing below is needed for a good screen.

**An optional `layout:` on the screen** gives a 12-column grid and, if wanted, named areas (like CSS grid-template-areas). Sizes are
grid units (columns and row units), never pixels.

```yaml
# simple: the grid, panels with span / rows / order
layout: { columns: 12 }
panels:
  - { use: key-figure@1, with: { id: ten-y, value: $.TEN_Y, label: 10Y, fmt: pct4 }, span: 3, rows: 2 }
  - { use: key-figure@1, with: { id: slope, value: $.SLOPE_2S_10S, label: 2s10s, fmt: bp1 }, span: 3, rows: 2 }
  - { use: curve-overview@1, span: 12 }
  - { use: data-provenance@1, span: 12, order: 99 }
```

```yaml
# master-detail: named areas, a resizable split and tabs
layout:
  areas:
    - "header header"
    - "trades detail"
    - "trades limits"
  columns: [7, 5]                                   # grid units per area column (sum 12)
  split: { between: [trades, detail], resizable: true, remember: user }
  sticky: header
  user-can-arrange: true
  phone: [header, detail, trades, limits]           # stacking order below the phone breakpoint
panels:
  - { use: field-list@1, with: { id: summary, rows: $.SUMMARY }, area: header }
  - id: trades
    kind: table
    area: trades
    rows: $.TRADES
    emits: [ { name: open, on: row-click, payload: { trade: "@.TRADE_ID" } } ]
  - id: detail
    kind: tabs
    area: detail
    show: on-select
    panels:
      - { use: field-list@1, with: { id: terms, title: Terms, rows: $src }, source: { kind: trade, id: "@trades.open.trade" } }
      - { use: cashflow-ladder@1, with: { id: flows, rows: $src.CASHFLOWS }, source: { kind: trade, id: "@trades.open.trade" } }
  - { use: ranked-bars@1, with: { id: limits, rows: $.LIMITS, label: LIMIT, value: USED }, area: limits, collapsible: true }
```

- **Grid**: per panel `span` (1 to 12 columns), `rows` (height in row units, today's `height`), `order` (sort key within the flow).
  With `areas`, a panel's `area:` names its area (today's `area: main|right` stays as the two built-in areas of the default flow).
- **Responsive rules**: below the tablet breakpoint the right column drops under the main one; below the phone breakpoint every
  panel stacks full width in `order` (or the `phone:` list when given); charts keep their aspect, tables scroll inside themselves
  (never the page). Per-breakpoint overrides (`tablet: {...}`, `phone: {...}`) only when the default stacking is wrong.
- **Containers**: `tabs` (exists), `split` (two areas with a draggable divider, the position remembered per user), `collapsible: true`
  on a panel or a section (collapsed state remembered per user), `sticky` (an area that stays at the top while the page scrolls).
- **Children within the grid**: `expand-row` stays inside its table; `detail-right` / `detail-below` take the area to the right of or
  under the parent (or the area named by `into: detail`); `tabs` children fill a `tabs` container. On a phone a detail opens as a
  full-width sheet over the parent with a back control.
- **User arrangement**: with `user-can-arrange: true` a reader may drag, resize, hide and restore panels; the arrangement is stored
  per person and screen (content store), never changes the shared Sutra, and "Reset layout" returns to the author's.
- **Lint**: every `area:` names an area of `layout.areas`; areas form rectangles (no L-shapes); no two panels pin the same cell;
  spans fit the 12 columns; a `split` names two adjacent areas; `phone:` lists only panels or areas that exist.

## 14. Lint: what is checked

| Check | Code | Severity | Phase |
|---|---|---|---|
| A fragment's `doc:`: summary (one line, ≤ 120), description, every declared parameter with type, default (equal to the declared one) and meaning, nothing undeclared, `emits`, `expects`, at least one example that uses this fragment with declared parameters, `since` | `DRS-2055` | error: the fragment is not loaded | 1 (built) |
| A composite's `doc.uses` equals the inner fragments it uses | `DRS-2055` | error | 1 (built) |
| A Sutra's `doc:`: summary, description, examples, since | `DRS-2055` | warning (`sutra lint --docs` counts Sutras without one and prints an incomplete one) | 1 (built) |
| An older block (`.block.yaml`) without a complete doc | `DRS-2055` | warning | 1 (built) |
| Every fragment and example expands into panels the Sutra parser accepts | `DRS-2048` and the panel codes | error (tests: `SutraFragmentsTest`) | 1 (built) |
| An inner use without a version, deeper than 3, a cycle | `DRS-2048` | error | 1 (built) |
| An event read (`@panel.event.field`) of a panel, event or field the screen does not have; a panel reading its own event; a cycle; `$src` without a source; a source id reading a `number`, `date` or `bool` field; a trigger not built; a source that names none or several of id, where, search | `DRS-2057` | error | 2 (built) |
| A fragment whose panels emit without `doc.emits` entries (`{ name, on, payload }`), or read `@parent` without `doc.expects` | `DRS-2055` | error: the fragment is not loaded | 2, 3 (built) |
| A child reading `@parent` where no parent provides it or an event it does not emit; children deeper than 3; `expand-row` under a panel without rows | `DRS-2056` | error | 3 (built) |
| A form default reading a masked field; a write action naming a missing connector template | `DRS-2058` | warning / error | 5, 6 |
| Layout: unknown areas, overlaps, spans over 12, non-rectangular areas | `DRS-2059` | error | 7 |

The server reports errors per file (the problems the catalogue and Studio show), `sutra lint` prints errors and the help warnings, `sutra lint --docs` adds the
docstring warnings; neither fails on a docstring warning yet (it will, under `--strict`, once every pack Sutra carries one).

## 15. Security: masks and roles through events, children and writes

- **Everything is bound as the viewer.** A panel, a source, a child and a dynamic screen are rendered by the server with the viewer's
  roles and masks; the browser never receives an unmasked value to hide.
- **Events carry what the viewer saw** (as built, phase 2). A payload evaluated over a masked field is `•••` in everything drawn
  (titles, cells, `where` filters: a masked value never selects a record). The stored value is kept on the server with the selection
  and is read only to look up a source by `id`, `where` or `search`, for the same person only (the UI Board's rule for masked ids,
  decision 14.1 #10): the person may follow a row they were shown to its detail without ever being sent the masked id, and the detail
  is read as that person (kinds, masks). The browser holds an opaque token per selection (`IdTokens`) that no one else can resolve and
  that names no value, so a client cannot forge a payload it was not shown. A pack that masks an id should mask the matching id field
  of the kinds it leads to as well (the followed record's own id is shown unless masked). Where a source's id is itself masked at the
  source, the panel says "not available to your role".
- **Sources and children check `kinds`.** A source of a kind the viewer may not open leaves the panel out and says so; links to it are
  not drawn (as today's links panel).
- **Forms and writes** never prefill or send masked values; an action is drawn only for a role it names, re-checked on the server when
  submitted, and performed by the lane owner (WRITE_BACK.md) with the edit log recording who asked and who approved.
- **Live pushes** are masked per subscriber, as the live stream is today.
- **Dynamic screens** have no more power than a pack Sutra; an AI agent composing one acts as the person asking.

## 16. The editor

The editor comes last and is built from the product owner's own mock-ups (none exist yet). What it will at least do: browse the
library by its docstrings (search, the doc, the examples rendered on sample data, where used, composite expansion); drop a fragment
on the grid and fill its parameters from the doc's types (a field picker for `field`, a format list for `format`); wire events by
picking from `emits`; show lint as you type; save a dynamic screen to the workspace or a Sutra file to a pack (`studio-save`).

## 17. Phases

| Phase | What | Release |
|---|---|---|
| 1 | The fragment library (the Drishti UI kit, `config/ui-kit`, with its assets, brand and `GET /api/v1/ui-kit`), the `doc:` docstring and its lint, composite fragments, migrated screens (ir-curve and the seven economics screens, proved identical), `GET /api/v1/sutras/library` | 2.7.0 (**built**) |
| 2 | Events (`emits`, `@panel.event.path`) and multi-pack sources (`source:`) | 2.7.0 (**built**) |
| 3 | Nested children (`children:`, `place`, `show`, lazy, depth 3); the book desk | 2.7.0 (**built**) |
| 4 | Refresh control and freshness per panel | 2.7.x |
| 5 | Forms and validation | 2.8.0 |
| 6 | Actions and write-back through connectors' `writes:` (WRITE_BACK.md) | 2.8.0 |
| 7 | Dynamic screens saved to the workspace; the `layout:` grid; workspace fragments | 2.8.x / 2.9.0 |
| 8 | The editor, from the product owner's mock-ups; the component browser | after the mock-ups |

Each phase migrates screens only where the equivalence test proves the view unchanged; nothing breaks a pack Sutra written today.

## 18. Open questions

1. **Library home.** *Answered (product owner, 2026-10-22):* the library is platform vocabulary, the Drishti UI kit
   (`config/ui-kit`, `drishti.ui-kit.dir`), loaded whatever packs are enabled; packs keep their own fragments, namespaced by pack
   ([section 3a](#3a-the-drishti-ui-kit)).
2. **Docstrings for today's Sutras.** 180 pack Sutras have no `doc:` (warning only). Generate them from the taxonomy (packgen) or
   write them by hand as screens migrate? When does `--strict` start failing on them?
3. **Strip fragments.** The strip (key figures in the title band) is not a panel list, so fragments cannot fill it yet. Extend `use:`
   to `strip:` (a `key-figures` fragment), or move headline figures into panels (`key-figure`)?
4. **Generated screens and hand-written ones.** ir-curve is now hand-written (`HAND_WRITTEN` in make_sutras.py). Should the
   generators emit fragment uses instead, so the other market-data screens migrate without leaving the generator?
5. **Event round trips.** Server-side events keep masks exact but cost a request per click. Is a client-side evaluation of
   payloads over already-masked view data acceptable for the simple cases (filtering a table already on screen)?
6. **Events in the URL.** Should a shared link reproduce selections (and children opened), or only the record and controls?
7. **Refresh cost.** Who may set `every` below 30 s on a shared server: the author, the viewer within `min`, or an administrator?
8. **Workspace fragments.** May a person publish a fragment from their workspace to the shared library, and who approves it
   (governance as for Sutras)?
9. **Layout and today's `area: right`.** Keep `area: main|right` forever as the default flow's two areas, or migrate them into
   `layout.areas` when a screen opts in?
10. **AI composition (2.9.0).** Is the docstring enough for an agent, or does each fragment also need sample data so an agent can
    preview what it composes?
