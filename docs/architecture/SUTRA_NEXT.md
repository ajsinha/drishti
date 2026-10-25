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

Status: **phases 1 to 5 built and released in 2.7.0** (2026-10-24; write-back, phase 6, is 2.8.0; see [CHANGELOG.md](../../CHANGELOG.md)): the fragment library, the `doc:` docstring and its lint, composite fragments, migrated
screens (ir-curve and the seven world-economy economics screens) and the library read API (phase 1); per-panel sources over any
pack's kinds and events evaluated on the server (phase 2, [section 6](#6-sources-over-several-packs), [7](#7-events)); nested child
panels (phase 3, [section 8](#8-nested-child-panels)); the worked screen is the trading pack's **book desk**. Also built since:
refresh and freshness (phase 4, [section 9](#9-live-panels-refresh-control-and-freshness)), forms and drafts (phase 5,
[section 10](#10-forms-and-data-entry)), the `layout:` grid ([section 13](#13-layout-placing-panels)), every pack screen on
fragments, and the Sutra designer's four modes behind `ui.designer` ([section 16](#16-the-editor)). Write-back (phase 6) and dynamic
screens saved to the workspace (phase 7) are **design, not built**. Direction of the product owner, 2026-10-22:
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

**Migration progress (the generators emit fragments; open question 4).** The pack generators now write the Sutra Next form
themselves: each panel a kit fragment draws exactly becomes its use (`series-line`, `ranked-bars`, which gained an optional
`tone`, `field-list`, `curve-table` or `curve-overview`, `data-provenance`, `linked-entities`, `source-footer`), the rest stays
written in place, and every Sutra gets a generated `doc:` (`tools/packgen/banking/kit_sutras.py`; PACK_DEVELOPER_GUIDE, Generated
Sutras are fragment Sutras). ir-curve is generated again (its docstring kept by hand in `DOCS`, `HAND_WRITTEN` is empty). Each
pack is proved by `PackSutraNextEquivalence` (one subclass per pack): every migrated screen drawn on the pack's committed samples
with the old layout (test data, version 2) and the new, for a full and a masked reader, identical but for the layout's version.

| Pack | Screens migrated | How | Layout lines before → after (no header, no doc) | Proof |
|---|---|---|---|---|
| market-data | 20 of 20 | generated (`make_sutras.py`) | 655 → 520 (19) + ir-curve | `MarketDataSutraNextEquivalenceTest`, `SutraNextEquivalenceTest` |
| world-economy | 13 of 13 (7 economics by hand, 6 politics generated) | `packbuild.py` (`fragments=True`) | 238 → 144 (politics) | `WorldEconomySutraNextEquivalenceTest`, `EconomicsSutraNextEquivalenceTest` |
| counterparty-risk | 10 of 10 | generated | 328 → 295 | `CounterpartyRiskSutraNextEquivalenceTest` |
| market-risk | 6 of 6 | generated | 194 → 157 | `MarketRiskSutraNextEquivalenceTest` |
| core | 10 of 10 (docs and footer; tables and lists stay in place) | generated | 330 → 330 | `CoreSutraNextEquivalenceTest` |
| news-and-markets | 4 of 4 (news-topic, listed-company; quote and news on the kit and the pack's own fragments) | by hand | 232 → 163 | `NewsAndMarketsSutraNextEquivalenceTest` (quote and news on a synthetic set) |
| trading | 134 of 134 (130 deal screens, 4 derived pages) | generated (`trades/sutras.py`): the blocks are pack fragments `trading/<name>@1` with docs, the footer the kit's; the derived pages `trading/payments-metrics@1`, `trading/trade-table@1` (its columns a parameter) and the kit's `data-provenance` | 4,055 → 4,078 (the deal footer is two uses) | `TradingSutraNextEquivalenceTest` (the derived pages on a synthetic page each) |

**No screen is left to migrate (2.7.x).** Every pack Sutra is built from the Drishti UI kit and its pack's own fragments where one
draws the panel exactly, each proved identical to its inline layout. The last six:

- **The trading pack's four derived pages** (`counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades`): the
  payments metrics are `trading/payments-metrics@1`, the trades table `trading/trade-table@1` with its columns passed as a parameter
  (a fragment parameter may now be a list: `type: columns`, filled in as YAML flow where `%{columns}` stands; PACK_DEVELOPER_GUIDE,
  A list as a parameter), the footer the kit's `data-provenance@1`. The generator writes them (`trades/sutras.py`, `page_fragments`,
  `make_sutras.py --check`). Proved on a committed synthetic derived page per kind (`drishti-server/src/test/resources/sutra-next-data/trading`),
  served in the test by a file connector of its own with the derived connector off (`PackSutraNextEquivalence.lake`): the same page
  through the same data path both ways.
- **news-and-markets `quote` and `news`**: `series-line@1` (the closing price), `field-list@1` (the data source and terms),
  `ranked-bars@1` (headlines by source), `data-provenance@1`, and the pack's own `quote-ranges@1`, `price-bars@1`, `headlines@1`,
  `headline-pipeline@1` and `snapshot-note@1` (`sutras/common/`). Proved on a synthetic quote and headline set kept with the test
  resources (`sutra-next-data/news-and-markets`, a made-up ticker, not the pack's samples, so the demo lake never sees it), served by a
  file connector in `NewsAndMarketsSutraNextEquivalenceTest`.

Panels still written in place inside migrated screens, because no fragment says them more simply: the derived pages' ladder over a
computed path and their `group()` bars (`shape: donut`, no label and value fields), surfaces, candlesticks, scatters, areas, graphs,
waterfalls, pivots, panels with `when:` over a record's own fields, the IMF projections table. Every pack Sutra carries a `doc:`.

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
| `params` | (2.8.0, with `id`) a computed kind's typed parameters, `name: expression` (a number or true/false is its own literal): the server evaluates them as the viewer and reads the kind with the id `ID~name=value`; the reactive connector checks them and the panel shows *computing*, *stale*, *failed* or *refused* in place ([REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md#reading-a-computed-kind)); `DRS-2012` for a bad shape, `DRS-2057` with `where` or `search` |
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
  `403 DRS-5002`. Tokens live in the memory of the node that issued them.
- **One server per screen (sticky routing).** In a cluster each token names its node (`tok_<node>~<random>`; a single server's
  names none). The console sends a click or a panel's children that carry tokens to that node while it is usable, whatever server
  the session is on (`ServerPool.for_tokens`, `core/cluster.py`); a request that reaches another node is forwarded whole to the
  issuing node for the caller, one hop, as a run's calls are (`SelectionForwardFilter`). When that node is gone the answer is
  `502 DRS-5052` *your selections were reset because server X went away*; the page says it in words above the panels
  (`Your selections were reset because server X went away. Pick again.`), forgets the selections, closes their children and
  unmarks the rows, and the reader picks again. An expired token (`DRS-5002`, the server restarted) is said the same way.
  `NetSelectionTest` (two in-process servers: a token issued on A used through B, then A stopped) and `test_cluster_failover.py`
  prove it; DRISHTI_NET.md 11.1.
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
- **Depth limit 3** (screen panel, child, grandchild, great-grandchild refused, `DRS-2056`). A child of a composite counts
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
- **Limitation: pack screens nest at most two levels** of panels a click opens (a child and its child). The designer's Grid
  edits those two levels as cells (SCREEN_DESIGNER.md section 31, Grid); anything deeper is edited in Outline or Customize.
- **Other packs.** A child reads any kind through its or its group's `source:` (the book desk's cash flows read the trade).
- **Placement in the console.** `expand-row`: a row under the picked one holds the panels; `detail-right`: the parent and a detail
  pane side by side (stacked below 1100 px); `detail-below`: a pane under the parent; `tabs`: a pane with one tab per panel (arrow
  keys move between them). Below 700 px a pane opens as a full-width sheet with a Back button. Theme tokens only, so every theme
  draws them.

**The worked screen: the book desk** (`config/packs/trading/sutras/pages/book-desk.v1.sutra.yaml`, opened as
`/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk`): the book's trades (`row-picker@1`, `open` with the trade id and the
counterparty); a click opens the trade's cash flows under the row (`cashflow-ladder@1` over the `trade` kind, trading pack) and fills
the right column: the counterparty (core pack, by id) and its credit limit with utilisation by tenor (`ranked-bars@1`, counterparty-risk
pack, by `where`). For a reader with `COUNTERPARTY` masked the payload's counterparty is `•••`, and the counterparty, limit and
utilisation panels, which follow it, show nothing but *Hidden for you* (section 15).
`SutraNextEventsTest` (server) and `test_sutra_wire_browser.py` (console) exercise it.

## 9. Live panels, refresh control and freshness

**Built (phase 4, 2.7.x).** A panel, a use, or the whole Sutra says how often it is fetched again; the reader controls it within that,
and every panel says how fresh it is.

```yaml
refresh: 60                                          # the whole screen: every top-level panel without its own refresh
panels:
  - use: row-picker@1
    with: { id: trades, rows: $.TRADES }
    refresh: 10                                      # a use: every panel it brings
  - id: limits
    kind: kv
    source: { pack: counterparty-risk, kind: credit-limit, id: "@trades.open.ref" }
    refresh: { every: 30, user-can-pause: false }    # the reader may slow it down, not pause it
```

- **Grammar.** `refresh:` is a whole number of seconds (1 to 86400) or `{ every: N, user-can-pause: true|false }` (default `true`), on a
  top-level panel, a use (`use:` carries it to every panel it brings) or the Sutra itself. A panel inside a tab cannot refresh on its own
  (put it on the tabs panel). Anything else is `DRS-2012` with its line, in the editor, `drishti lint` and at load; the JSON schema
  (`/api/v1/rachana/schema`) describes it. The server's view model carries it on each panel as `refresh: {every, userCanPause}`.
- **Only the refreshing panels move.** The console (`static/js/refresh.js`) fetches the panels that are due through
  `/v/<kind>/<id>/panels?panels=a,b` (one request for all that are due, with the controls' values, `?sutra=` and `?design=`) and swaps each
  one in place only when its HTML changed: the old panel's height is held until the new one is drawn (no flicker), the page's and the panel's
  scroll are kept. A panel a selection drives (SUTRA_NEXT.md 7) is refreshed by sending that selection again (`POST .../events`, only its
  listening panels swapped), so a refresh never undoes a pick; the picked row stays picked and what it opened (under the row, beside or
  below the panel, tabs) stays open.
- **Never too fast.** No panel refreshes faster than the console's `views.refresh_min_seconds` (default 5) or than its Sutra's `every`:
  the reader may choose a slower interval or pause, never a faster one. Identical requests share the server's view cache.
- **The reader controls it.** Each refreshing panel has a pause/resume button and an interval picker in its header; the page has
  **Pause all** above the panels (a paused page says "Refresh paused on this page"). The choices are kept per person and per screen
  (`<kind>/<sutra>`) in the user's settings (`PATCH /api/v1/me/settings {"refresh": {"trade/my-desk": {"all": false, "panels":
  {"curve": {"paused": true, "every": 60}}}}}`), never in the Sutra. A hidden browser tab fetches nothing and catches up when shown.
- **Freshness on every panel.** Each panel's header says "updated 12 s ago" (ticking, from the view's `fetchedAt`, then from each
  refresh); "stale" in amber when a refreshing panel is older than twice its interval or the source is behind (the provenance's `stale`,
  `updatedAt` and `staleAfter`); when a refresh fails, the source is named in words: "trade-endur isn't answering; showing data from
  10:42". A page the live channel updates (LIVE.md) says "live" on every panel instead of a timer, and runs no timers of its own.
- **The designer.** Describe's `refresh 30s` writes `refresh: 30` and the page acts on it; Customize has **Refresh** in its side panel
  ("Refresh <part> every <interval>"), which writes `refresh:` on that part (off removes it).

- **Freshness per panel source (2.7.x).** A panel with its own `source:` (a mapping, section 6, or a linked entity written as text)
  carries the provenance of what it read, apart from the page's: `provenance: {source, kind, fetchedAt, updatedAt, staleAfter, stale}`
  on the panel in the view model (`ViewModel.PanelProvenance`; the connector that answered, the one the document names or else the
  kind's first route, with that connector's last update and `stale-after`), on the panel's `data-prov` and in the panels endpoint's
  answer (`/v/<kind>/<id>/panels`: each panel's `provenance` beside its HTML). `refresh.js` says that panel's "updated N s ago" from
  its own `fetchedAt` at first paint, marks it stale in amber by its own source ("stale · limits-store last had new data 10:40") and,
  when a refresh fails, names its own source ("limits-store isn't answering; showing data from 10:42"); a panel of the page's record
  goes by the page's provenance as before. A source read as a list (`where:`, `search:`) has no single document: its `fetchedAt` is
  left out and the kind's connector answers for the rest. `PanelProvenanceTest`, `test_refresh.py`, `test_refresh_browser.py`.

Still to come: a server-side `every` scheduler pushing over the live channel to many viewers at once (today each page asks).

## 10. Forms and data entry

**Built (phase 5, 2.7.x; drafts only, the write is phase 6).** A panel (or a use, which carries it to the one panel it brings) says how
people edit its records or start new ones with `form:`. The worked guide is [SUTRA_FORMS_GUIDE.md](../guides/SUTRA_FORMS_GUIDE.md); every
key is in [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#forms-form-on-a-panel).

```yaml
panels:
  - use: row-picker@1
    with: { id: trades, rows: $.TRADES, key: TRADE_ID, ... }
    form:
      key: trade_id
      edit: beside                      # beside: a form beside the table | cells: the table's cells
      new: drawer                       # New opens a guided drawer
      steps: [ { id: who, label: Who }, { id: what, label: What }, { id: check, label: Check } ]
      fields:
        - { id: trade_id, bind: TRADE_ID, readonly: true, reason: "set by the booking system", new: false }
        - id: counterparty
          bind: COUNTERPARTY
          type: lookup
          required: true
          step: who
          options: { source: { pack: counterparty-risk, kind: credit-limit, where: "nonblank(@.COUNTERPARTY)" },
                     value: COUNTERPARTY, label: COUNTERPARTY_NAME, carry: [LIMIT, USED] }
        - { id: notional, bind: NOTIONAL, type: number, min: 0, required: true, step: what }
        - { id: trade_status, bind: TRADE_STATUS, type: enum, options: [PENDING, VALIDATED], editable-by: [middle-office] }
      rules:
        - { rule: "notional <= 30000000", severity: warn, fields: [notional], message: "Above the desk limit of 30,000,000: a second approver is needed." }
      hints:
        - { label: "PFE limit after this trade", value: "counterparty.USED + notional", of: "counterparty.LIMIT", step: what }
```

- **Field types**: `text`, `number`, `money`, `date`, `datetime`, `entity`, `enum` (`options:` values or `{value, label}`), `bool`, `textarea`, `lookup` (choices read
  from a `source:` of a kind with `where` or `search`, as the viewer may see it; `carry:` names the values hints read as
  `counterparty.LIMIT`). Each field: `label`, `bind` (the record field; the id when absent), `required`, `min`/`max` (a number, a date or
  `today`), `pattern`, `max-length`, `editable-by` roles, `readonly` with a `reason`, `step`, `placeholder`, `fmt`, `new: false`.
- **Rules** (`rules:`) are expressions in a small language the browser and the server share (`FormExpr`; fields by id, `+ - * /`,
  comparisons, `and or not`, `empty len abs min max`, `today`): `severity: error` (the default) refuses the draft, `warn` is said and kept.
  The browser checks as people type; the server checks every draft again as the authority. A rule reading a lookup's carried value is
  checked in the browser only until the write (phase 6) re-reads the record.
- **Modes**: `edit: beside` (a click on a row opens a form beside the table), `edit: cells` (a field edits the column whose `bind` names
  its record field), `new: drawer` (steps, Next checks the step, the hints of the step, Save as draft from any step, checked up to it).
- **Masks and roles decide editability.** The view model carries the form as the viewer may use it: a masked field is marked, left out of
  every row's values and offers no lookup choices (the console says *Hidden for you*, never an input); a field whose `editable-by` (or the
  form's `roles`) names roles the viewer does not hold is `locked` ("locked: you can view, not change"); a lookup never offers an entity
  whose value is masked for the viewer. With sign-in off every role is held.
- **Drafts, not writes.** Saving keeps a draft per person in the content store through `/api/v1/drafts` (CRUD, per user, checked again
  with the same rules, a masked or locked field refused, each save and discard audited); the page lists them ("You have 2 drafts on this
  page") to resume or discard, and warns before leaving with changes not saved. A panel being edited is not refreshed under the person.
- **Lint**: `DRS-2058` for every form problem, in words (an unknown key, a type, a `step` or `key` naming nothing, a rule reading a field
  the form does not have, a pattern that does not compile); the JSON schema describes `form:`.
- **The designer**: Customize's *Let people edit* (a table, beside or cells, which columns, New in a drawer) and Describe's
  `edit trades beside|in the cells` and `new trade in a drawer` write it (SCREEN_DESIGNER.md section 31).
- **On the packs**: the book desk's trades (beside and a New trade drawer) and a credit limit's buckets (cells).

- **The richer fields (built, 2.7.x)** ([SUTRA_FORMS_GUIDE.md, money, a date and time, a linked record](../guides/SUTRA_FORMS_GUIDE.md#money-a-date-and-time-a-linked-record-a-worked-out-default)):
  `money` is an amount typed as `1.5m`, `250k` or `1,000,000` in a fixed currency (`currency: USD`) or the one another field holds
  (`currency: ccy`); `datetime` is a date and a time in a named time zone (`tz`, UTC when absent); `entity` links a record of any
  pack's kind (`options: { source: { pack, kind }, label }`), its id kept and its label shown, found by a live search
  (`/api/v1/forms/lookup`, read as the viewer: a masked value is never offered or matched, the limit capped, a short budget) with the
  page's preloaded choices as the fallback; a draft's entity id is read once on the server and an unknown one refused in words.
  `default:` starts a new record with a value or an expression (`=notional * 0.001`) worked out as people type, and by the server
  when a new record leaves the field empty. Describe writes a book's notional as `money` in the trades' currency field, else the
  record's currency.

Still to come: `submit` emitting an event (the write itself is section 11).

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

- **Off by default**: a Sutra's actions will do nothing until write-back is switched on (a planned setting, WRITE_BACK.md) and the connector's `writes:` are on, and the
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

**As built (2.8).** `layout:` is read by `LayoutReader` into `ScreenLayout` (rachana) and checked against the panels (`DRS-2059`);
the view model carries it (`ViewModel.layout`: the layout's keys and each top-level panel's `place`: area, order, section,
collapsible; `ScreenGrid`), and the console draws it (`core/screen_grid.py`, `terminal/_grid.html`, `static/js/screen-grid.js`,
`screen-grid.css`, every theme). Differences from the sketch above:

- Height stays `height` (grid rows). `rows: <n>` was not taken: `rows` is the rows a table reads, and a number there stays an error.
- `tabs:` lists **areas** whose panels show as tabs (one tab a panel, two or more), rather than a tabs panel with `show: on-select`
  (which still works as a panel kind).
- **Sections** (`layout.sections: [{ id, title, collapsed }]`, `section:` on a panel) arrange the flow grid; a screen with areas uses
  tabs or `collapsible: true` instead (lint says so). Panels in no section stay above the sections.
- **Remembered per reader**: the split's position (when `remember: user`), what is folded (sections and collapsible panels), and,
  with `user-can-arrange: true`, each panel's area, width and order: one small document per reader and Sutra in the preference
  store (`GET/PUT/DELETE /api/v1/me/arrange/<sutra>`, `MyArrangementController`; the console's `/me/arrange/<sutra>`), never the
  shared Sutra. *Reset layout* deletes it. A screen with `layout:` does not offer layout mode (Alt+L); the grid replaces it.
- **Responsive**: below 1000 px the Sutra's `tablet:` picture, else the areas one a row; below 640 px one column in the `phone:`
  order (areas or panel ids; the rest as the picture reads), 16 px gutters, no sideways scroll; an area shown as tabs stays together.
- **Children within the grid** (`into:`) and per-breakpoint panel overrides are not built yet; children open where `place:` says,
  inside the parent's area.
- The picture's template is set by script (the console's CSP allows no style attributes); without script the areas stack in order.

## 14. Lint: what is checked

| Check | Code | Severity | Phase |
|---|---|---|---|
| A fragment's `doc:`: summary (one line, ≤ 120), description, every declared parameter with type, default (equal to the declared one) and meaning, nothing undeclared, `emits`, `expects`, at least one example that uses this fragment with declared parameters, `since` | `DRS-2055` | error: the fragment is not loaded | 1 (built) |
| A composite's `doc.uses` equals the inner fragments it uses | `DRS-2055` | error | 1 (built) |
| A Sutra's `doc:`: summary, description, examples, since | `DRS-2055` | warning (`sutra lint --docs` counts Sutras without one and prints an incomplete one) | 1 (built) |
| An older block (`.block.yaml`) without a complete doc | `DRS-2055` | warning | 1 (built) |
| `sutra lint config/packs` (the packs root): each pack's fragments load under its own namespace, so `trading/deal-ticket@1` resolves as on the server; `<pack>/tests` is skipped and each pack's `history:` is checked | — | — | 3 (built) |
| Every fragment and example expands into panels the Sutra parser accepts | `DRS-2048` and the panel codes | error (tests: `SutraFragmentsTest`) | 1 (built) |
| An inner use without a version, deeper than 3, a cycle | `DRS-2048` | error | 1 (built) |
| An event read (`@panel.event.field`) of a panel, event or field the screen does not have; a panel reading its own event; a cycle; `$src` without a source; a source id reading a `number`, `date` or `bool` field; a trigger not built; a source that names none or several of id, where, search | `DRS-2057` | error | 2 (built) |
| A fragment whose panels emit without `doc.emits` entries (`{ name, on, payload }`), or read `@parent` without `doc.expects` | `DRS-2055` | error: the fragment is not loaded | 2, 3 (built) |
| A child reading `@parent` where no parent provides it or an event it does not emit; children deeper than 3; `expand-row` under a panel without rows | `DRS-2056` | error | 3 (built) |
| A form: an unknown key, a type, a `step` or `key` naming nothing, a rule reading a field the form does not have, a pattern that does not compile (5, built); a write action naming a missing connector template (6) | `DRS-2058` | error | 5 (built), 6 |
| Layout: unknown areas, overlaps (non-rectangular areas), rows of different widths, column widths not summing to 12, a split of areas not side by side, tabs or sticky or phone naming nothing, tabs over fewer than two panels, sections with areas, a bad `order` or `section` | `DRS-2059` | error | 7 (built, 2.8) |

The server reports errors per file (the problems the catalogue and Studio show), `sutra lint` prints errors and the help warnings, `sutra lint --docs` adds the
docstring warnings; neither fails on a docstring warning yet (it will, under `--strict`, once every pack Sutra carries one).

## 15. Security: masks and roles through events, children and writes

- **Everything is bound as the viewer.** A panel, a source, a child and a dynamic screen are rendered by the server with the viewer's
  roles and masks; the browser never receives an unmasked value to hide.
- **Events carry what the viewer saw** (as built, phase 2). A payload evaluated over a masked field is `•••` in everything drawn
  (titles, cells, `where` filters: a masked value never selects a record). The browser holds an opaque token per selection
  (`IdTokens`) that no one else can resolve and that names no value, so a client cannot forge a payload it was not shown.
- **A followed masked id shows nothing (decided, 2.7.0).** A panel whose `source:` (its `id`, `where`, `search` or `as-of`) reads an
  event value the reader was shown masked is not looked up at all: it shows no data and says, in words, **Hidden for you** (*It
  follows a value that is masked for you, so it shows nothing; pick a row whose value you can see.*), never an empty panel or "No
  data available". The stored value kept with the selection is never used to follow a masked one (this replaces the UI Board's
  rule of following a masked id for the same person, decision 14.1 #10). Where a source's id is itself masked at the source, the
  panel says "Not available to your role". `SutraNextEventsTest` (the book desk: the counterparty and limit panels of a reader with
  `COUNTERPARTY` masked) and `test_wire_masks.py` (the words in the console) prove it.
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

**As built: the Sutra designer's four modes** (SCREEN_DESIGNER.md section 31, behind `ui.designer`). Customize, Describe, Outline and
Grid edit ONE Sutra: a draft Design of the person's own, drawn by the server as the viewer after every step (a step the server
refuses is undone and said in words); the mode switcher carries the page and the draft, so a switch loses nothing. Each person picks
the mode they open first (`designerMode` in `/me/settings`: `customize`, `describe`, `outline` or `grid`).

- **Grid** (`core/designer_grid.py`, `routes/designer_grid_routes.py`, `static/js/designer-grid.js`): `layout.areas` as a sheet,
  columns A, B, C and rows 1, 2, 3, an area a cell or a merged range (a screen without areas: one column, a panel a row). A cell's
  panels are formulas: `name@version(with parameters; panel settings)` for a fragment, `kind(settings)` for an inline panel, one a
  line; `@<cell>.<event>.<field>` stands for `@<panel>.<event>.<field>` of the panel alone in that cell. What a formula cannot say
  in one line (`columns`, `emits`, `children`, `panels`, `form`, nested parameters) is kept when a formula is typed; the grid keys
  (`area`, `span`, `order`, `section`, `height`, `collapsible`) are the cell's. Steps: a formula typed (a line more adds a panel,
  an empty cell gets an area of its own), merge (a range covering whole areas), split, insert and delete rows and columns (areas
  stay rectangles; `columns` re-shared), move (swap, or into an empty cell), make it a grid, nest under a table (Outline's). The
  translation round-trips exactly: Sutra → cells → Sutra gives the same YAML on every pack Sutra and on screens with areas
  (`test_designer_grid.py`); a formula that does not read is refused with the part to fix (`start`, `end`).

## 17. Phases

| Phase | What | Release |
|---|---|---|
| 1 | The fragment library (the Drishti UI kit, `config/ui-kit`, with its assets, brand and `GET /api/v1/ui-kit`), the `doc:` docstring and its lint, composite fragments, migrated screens (ir-curve and the seven economics screens, proved identical), `GET /api/v1/sutras/library` | 2.7.0 (**built**) |
| 2 | Events (`emits`, `@panel.event.path`) and multi-pack sources (`source:`) | 2.7.0 (**built**) |
| 3 | Nested children (`children:`, `place`, `show`, lazy, depth 3); the book desk | 2.7.0 (**built**) |
| 4 | Refresh control and freshness per panel | 2.7.x (**built**) |
| 5 | Forms and validation; drafts per person (`/api/v1/drafts`) | 2.7.x (**built**; the write is phase 6) |
| 6 | Actions and write-back through connectors' `writes:` (WRITE_BACK.md) | 2.8.0 |
| 7 | Dynamic screens saved to the workspace; the `layout:` grid; workspace fragments | the `layout:` grid 2.7.x (**built**); the rest 2.8.x / 2.9.0 |
| 8 | The editor, from the product owner's mock-ups; the component browser | the Sutra designer (Customize, Describe, Outline, Grid) 2.7.x (**built**, behind `ui.designer`); the component browser after the mock-ups |

Each phase migrates screens only where the equivalence test proves the view unchanged; nothing breaks a pack Sutra written today.

## 18. Open questions

1. **Library home.** *Answered (product owner, 2026-10-22):* the library is platform vocabulary, the Drishti UI kit
   (`config/ui-kit`, `drishti.ui-kit.dir`), loaded whatever packs are enabled; packs keep their own fragments, namespaced by pack
   ([section 3a](#3a-the-drishti-ui-kit)).
2. **Docstrings for today's Sutras.** *Partly answered (2.7.0):* the generators write them from the taxonomy (191 of 197 pack
   Sutras now carry one; [migration progress](#3-fragments-the-library)). Still open: when does `--strict` start failing on them?
3. **Strip fragments.** The strip (key figures in the title band) is not a panel list, so fragments cannot fill it yet. Extend `use:`
   to `strip:` (a `key-figures` fragment), or move headline figures into panels (`key-figure`)?
4. **Generated screens and hand-written ones.** *Answered (2.7.0):* the generators emit fragment uses and a `doc:`, so generated
   screens migrate without leaving the generator; ir-curve is generated again (`HAND_WRITTEN` is empty). The seven economics
   screens stay hand-written (their generator's panels carry options the fragments do not say one for one).
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
