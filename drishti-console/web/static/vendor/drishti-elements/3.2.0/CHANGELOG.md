<!--
  Drishti Elements

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY.

  This file is the proprietary property of Ashutosh Sinha.
  Copying, use, modification or distribution of this file, via any medium,
  is not permitted except with the express prior written permission of the
  copyright holder.

  See the LICENSE file in the root of this repository.
-->
# Changelog

Each release lists what changed, by library. One version for every library: the `elements` key of `elements/drishti_elements.json`.

## Unreleased

- **Report charts** (drishti-chart, a new part `report`: `drishti_chart-report.js`, loaded beside the bundle or by `loadPart('drishti-chart', 'report')`):
  a report page's chart visuals drawn from the server's answers, moved in from Drishti's BI so that a host draws no chart of its own.
  `reportOption(visual, answer, env)` builds bar, column, line, area, combo (a second axis), small multiples, pie, donut, waterfall, treemap,
  heatmap, scatter, bubble, gauge, funnel, histogram (the server's bins), box plot (the server's figures) and the rolling line of a live visual,
  with stacks and 100%, the highlighted part over the faded whole, the host's conditional colours and the format's axes, labels, legend and
  series colours; `drawReport` and `mountReport` draw it with the keyboard's marks, hover, click, live redraw in place and the range brush
  (`reportBrush`); `reportMap` draws countries and a country's regions over the geo part's helper; `sparkline`, `pythonOption` (a program's
  option made safe and themed), `measureBarsOption`, `drawOption`, `tableChartOption` (Chart any table's option, every colour a token) and
  `OptionChart` (one engine instance: draw, resize, tip, SVG, PNG). The part's budget is 70,000 bytes (66,198 when written).
- **A link whose target is missing is still a link** (drishti-views, links): its `missing` chip says so; only a `denied` target is drawn as words.
- **Table tools for embeds** (drishti-views, drishti-chart): the paint option `tableTools: {filters, chart}` gives a table a funnel on each column
  heading (values with counts, search, Select all, a number condition such as `> 1m`; filters combine across columns and survive a patch) and Chart any
  table (the rows as shown opened as a chart in a dialog beside the table: kind, across, series; the card's data table and export). drishti-chart's new
  `TableChart`, `openTableChart`, `tableChartSpec` and `tableChartData` chart any rows of text; a grid panel's "Chart the range" opens the same dialog.
  drishti-views' size budget is 255,000 bytes (243,130 with them; was 232,000).
- **The host styles and loads** (drishti-views): `sheets` (constructable sheets adopted by the view's root; the painter links no stylesheet) and
  `engine` (the host loads ECharts its own way); without `engine` the painter loads the vendored chart engine itself the first time a view has a chart.
- **A candlestick's data table prints the producer's texts** (drishti-views): `openText`, `highText`, `lowText`, `closeText` (the raw number only
  when a text is absent), as the contract carries them.
- **`complete` gives a ViewModel without a contract the painter's** (drishti-views): a document with no `contract` speaks contract 1.

- **Quoted-phrase search in all ten search sites** (decision accepted): a "quoted phrase" is one word in the grid, tree, inputs, designer and core
  filter searches, one reader (`searchWords`), no site keeps its own. **Core's size budget is 190,000** bytes (was 180,000; deliberate, owner: bigger
  is fine when deliberate).
- **Button and ButtonGroup** (drishti-inputs, new): the one way a screen draws a button. `button(doc, {label, action, variant, pressed, toggle, role,
  selected, current, data, attrs, onClick})`, `<drishti-button>`, `<drishti-button-group mode="single">`. The gallery's 35 hand-made buttons (the toolbars, the
  tabs, the mode segment, the navigator, the link button) and the chart studio's kind rail now use it; a guard fails on a new hand-made button in the
  gallery or the studio, and the libraries' own internal buttons (167) are counted and may only fall.
- **One name per thing** (breaking): chart kinds lose their aliases (`column`, `columns`, `bars`, `lines`, `point`, `points`, `doughnut`, `box`, `box-plot`,
  `hist`, `choropleth`, `geo`, `candles`; `KIND_ALIASES` is gone) and the engine's aggregations lose theirs (the views keep the ViewModel's `distinct`, translated at their own boundary by `engineAgg`) (`average`, `mean`, `distinct`, `countDistinct`: write `bar`,
  `line`, `scatter`, `donut`, `boxplot`, `histogram`, `map`, `candlestick`, `avg`, `count_distinct`). A spec with an old kind is refused by its name.
- **Known duplicates folded into drishti-core** (all libraries): `isObject`, `clamp`, `pad`, `copyText`, `fetcher` and `now` (one copy each, the local ones removed);
  `searchWords` and `hasWords`, the one quick-search reader (a "quoted phrase" is one word) used by the grid, tree, inputs, designer and core filter;
  the aggregation engine (`aggFunc`, `aggregate`, `registerAggFunc`, `isMasked`) moved to drishti-core with the masked rule, used by the grid, the views and the
  Vega-Lite chart (`median` stays in the chart); `formatCount` for counts. The grid, views and chart keep their public names.

## 3.2.0 (2026-11-21)

- **The painter in a shadow root takes a pack's stylesheet** (drishti-views): when the page links `drishti-elements.css` (a pack), the view's shadow root links it once
  instead of asking for one file per library (404s in a pack, an unstyled grid); the loader adds no per-library sheet to a page that links the pack's.
- **The grid's live-mode edit UI** (drishti-grid, drishti-views): for a live table whose declaration says `edit: cells` or `beside`, the grid reads the
  schema's `write` (only those columns, their rules, the reason mode; no `write`, no edit control) and edits it: cells mode an editor by each rule
  (number, text, pick list, date), beside mode a record panel (a drishti-inputs form) with Save and Cancel; a masked cell is never editable. The
  page checks the rules first, then `write({id: ulid, op: update, key, basedOn: the row's version, set, reason})`, the same id on a retry; the row is
  never changed locally: the cell is marked sending, queued or held (with the host's message) until the table's patch arrives. 409 says who changed
  the row and when, the value now, and offers Edit again; 422 puts each message on its column; 403 and 405 make the row read only; 404 drops it.
  The held-changes tray decides in cells (`state`: released or rejected, a rejection asks its reason, `basedOn` held). Enter or F2 edits, Escape
  cancels, Tab goes to the next editable cell; every editor has a name. The paused and stale banners come from the schema, "N new" counts rows that
  arrive above a scrolled page. The page's `options.tables(ref)` answers `schema()` as well as `page`, `follow` and `write`. Fix found by it: the
  grid in a shadow root looked its cells up in the document (an editor opened hidden).
- **G2 golden: budgets alone, browsers required, the pack's own version** (tests, tools): the performance-budget tests (pytest marker `budget`,
  Node tests named `budget: ...`) run serially after the parallel run, in the standard command `python3 tools/run_tests.py`, so load cannot fail
  them; Playwright is pinned (1.64.0) and a missing Playwright or Chromium fails the run with the install command (no silent skips); the run
  fails on any skipped test. `tools/elements/cli.py pack --label <version>` makes the pack say the version of the folder a host pins it in
  (`3.2.0-dev+<commit>`), in its record and headers. The fire fixture is regenerated against Rachana's latest `g2/integration`.
- **A surface's point clicked in Chromium, heatmap and 3D** (tests): the 3D view drawn by the vendored ECharts GL, each point clicked
  where the camera shows it, fires `point-click` on that mark and hands `{panel, ev, set}` to the host; on the heatmap an empty point is
  no mark. Fix found by it (drishti-chart): the surface heatmap's tooltip no longer asks for ECharts' arrow (`position: 'top'`), whose
  inline style a strict CSP (`style-src-attr`) refused.
- **Test pins and flake** (G2 gate F15, F18): `tests/support/fire_cases.json` carries the drishti-rachana commit and the hash of the `fire` case
  files it was made from (`tools/views/fire_fixture.mjs` writes both); `tests/test_views_spec.py` reads Rachana's files by that commit, never by the
  branch the sibling checkout has out, and fails when the cases at the pinned commit differ from the fixture, naming the one command that moves the pin: `node tools/views/fire_fixture.mjs --pin <commit>` (checks the commit out, builds its runtime, rewrites the fixture). No moving ref is compared. The ViewModel schema pin is read the same way.
  The Node timing budgets measure CPU time, and the 200 KB validation budget takes the least of its rounds, so a parallel run no longer fails them
  (160 runs of the Node suite, eight at a time: no failure).

- **Painter `requires` fails closed** (G2 gate F10, owner O8): an action button that names a permission is enabled only when `options.host.allowed(name)`
  answers exactly `true`; with no hook, any other answer, or a hook that throws, it is disabled. A page that wants every button passes
  `allowed: () => true`. Elements still runs no host action.

- **Pivot cells and surface points are marks** (drishti-views, drishti-chart; ViewModel contract 1.2: `Cell.ev`, `set`, `on`,
  `Surface.ev`, `set`, `on`): a pivot cell with a figure is clickable (keyboard too), runs its effects as `point-click` and hands its
  `ev` to the host as the `point` event; a surface point (heatmap and 3D) does the same through the chart. `marksOf` counts a pivot's
  cells and a surface's points row by row; the schema copy synced with drishti-rachana 19b16da; fire fixture regenerated.
- **Formats `pct4`, `bp1`, `df4`, `price4` bundled** (drishti-core, `data/formats.yaml`): Rachana's language level 1.2 promotes these four
  names from Drishti's packs into Core with Drishti's definitions, so the shared table carries them (`since: "1.2"`, kept in
  `SERVER_FORMATS`); a painter draws them from any view. **`pack --data`** puts `data/formats.yaml` and `data/world-regions.yaml` in the
  pack (hashed in its manifest), so a host pins the painter and the tables it formats with in one folder.
- **Forms, actions and control changes** (drishti-views, drishti-inputs; ViewModel contract 1.2): the `form` panel drawn by drishti-inputs' Form (types, options, required, min and max, starting values, Submit and Cancel; a refused submit shows the messages the host's `check` hook answers, nothing is evaluated in the browser); panel `actions:` buttons in the header (confirm in a dialog, `requires` through `host.allowed`); `handle.change(control, value)` for a control's `on.change`; effects read `event` (a submitted value, the new control value). `Form` option `cancelLabel` and `onCancel`. The `fire/forms`, `fire/actions` and `fire/change` cases of drishti-rachana run through the painter in Chromium and on the store under Node.

- **Charts, views**: a chart drawn before its box had a height (the engine at 0 px high) now follows its own box: each chart container is observed, not only the view, so the chart-point click is steady under parallel load (the browser test waits for the chart to be sized to its box).
- **IDE launchers**: `run-configs/` (PyCharm configurations for the checks and the pytest suites; Maven launchers of `pom.xml` for the Node tests and the gallery server, so IntelliJ Community on Windows needs no bash) and `drishti-elements.code-workspace` for VS Code; README, Build and run in the IDE.

- **Treemap and bullet panels** (contract 1.2): `treemap` drawn by drishti-chart's panel charts (cells nested by group, toned by sign,
  the groups' labels on top, no zoom; a cell that carries its row is a `point` event) with its data table; `bullet` drawn by drishti-views
  as elements (the zones, the bounds, the figure's mark toned by its zone, the bounds' words, a meter for assistive technology).
- **Several series on a line panel** (drishti-chart's panel charts, drishti-views; contract 1.2 `Series.mark`, `axis`, `ev`): a series'
  mark (bar, area, point; a line by default), a right-hand axis when a series asks for it, the palette's colours past the first, and a
  legend drawn by drishti-views as elements (the engine's legend writes inline styles); a click on a mark that carries its row (a series'
  `ev`, else the chart's) is the view's new event `point` (`{panel, ev, series?, set?}`; `PanelCharts` option `onPoint`, `pointOf`).
- **The live kind** (drishti-views; ViewModel contract 1.2, the pin moved to drishti-rachana g3/kinds): a grid over a live table of the
  Data Protocol. The dataset's `live` reference (table, window, top, filter) is read through `options.tables(ref)`, the page's live tables
  (drishti-rachana's `liveTables` over its DataClient: `page(query)` and `follow(after, keys, top, onPatch, onGap)`): the first window in the
  panel's order, a windowed subscription with its keys, each patch applied (reset, remove by key lists, update with ticking cells, add,
  `top` inserts first, the total), rows past the window leave from the bottom and the window follows, a gap reads the page again; "n of N
  rows, live" under the grid; without `options.tables` the panel says so. Cell bars: a column's `bar` (grid and live) draws drishti-grid's
  `barCell` against the column's largest magnitude. Browser tests with an in-page live table.
- **Donut clicks and tooltips under a strict CSP** (drishti-views, drishti-chart): the donut shape of an hbar fires `bar-click` on the slice's bar exactly as the
  bar form does (`set` and `on` lists, contract 1.2; each slice carries its bar). The panel charts' tooltips are elements built with the DOM
  (`elementTooltips`; no markup string, no style attribute), so no chart tooltip needs `'unsafe-inline'` for styles; the chart-click browser test
  now asserts zero CSP violations instead of skipping the check.

- **State and effects** (drishti-views, ViewModel contract 1.2, the pin moved to drishti-rachana `g2/integration` c89e068; the painter's side of
  Rachana's I1): the painter keeps `state[]`; a click on a table or tree row (depth first through `children`), an hbar bar, a timeline event, a
  figure (metric, gauge, kv, status) or a chart point (scatter, waterfall, candlestick, a line or area's x position) runs the mark's `set` or `on`
  list exactly as the `fire` conformance cases say: `set`, `toggle` and `clear` on the state, `notify` and `host` handed to `options.host` (never run
  by Elements), the state sent back through `options.host.bind` for the next bind, and only the panels the changed states' `reads` name drawn again.
  `handle.fire(panel, trigger, mark)`, `handle.stateValues`, the events `state-change`, `notify`, `host` and `bound`; a trigger the screen does not
  have answers `RCH-5001`. Evaluation stays with Rachana: nothing is evaluated here. The bundle's budget moves to 209,000 (198,954 measured, plus
  about 5%). Tests: the 13 `fire` cases through the painter in Chromium and on the store under Node (`tests/support/fire_cases.json`, made by
  `tools/views/fire_fixture.mjs`).
- **Studio widgets** (drishti-inputs, for Rachana Studio's screens; no bespoke UI): `SplitPane` (horizontal or vertical, minimum and maximum sizes,
  sizes kept as shares, the separator by pointer and by keyboard: arrows, Shift, Home, End, Enter; remembered with `persistKey`; `<drishti-split>`);
  `Tabs` (tablist, roving arrows, disabled tabs skipped, badge, closable, a panel made when first opened; `<drishti-tabs>`); `NavList` (a searchable
  list under headings that nests into a tree; search by label, detail and group keeps a match's parents; keyboard on `aria-activedescendant`;
  `<drishti-nav-list>`); `ColumnMap` and the form field type `columnmap` (a table of local columns, each a select of the caller's columns, the
  same names mapped at the start, required, type and uniqueness problems in words; `identityMap`, `mapProblems`, `typesFit`; `<drishti-column-map>`).
  drishti-inputs' bundle budget raised to 310,000 bytes for them (295,747 measured).
- **Fixed** `searchOptions` threw on options that were plain strings, numbers or `{value}` alone (so a list of strings never filtered); it now reads
  them as labels (and returns the items as given).
- **Fixed** drishti-views: `complete()` filled `ref` and `provenance` with `null` (a record behind a reference), so a minimal view still failed
  validation with "ref is null; it should be an object"; it now fills the record's own required fields. New painter option `complete` (and the
  `complete` attribute of `<elements-view>`) completes a view on a copy before checking, so a producer that sends the minimum draws without the console warning.
- **Documented**: an enum is radios up to three values and a select above; `widget: 'select'` (or `'radio'`) forces either whatever the count.

## 3.1.0

Released 2026-11-21. drishti-views, the tenth library: whole screens from a ViewModel of contract 1 (drishti-rachana's
`spec/1/viewmodel.schema.json`, pinned): the validator, the painter with every kind of contract 1, live frames (conflated; over
drishti-events or a plain stream), `<elements-view>`, and the builders of views and frames with `sse()`; and contract 1.1's datasets with
the `grid`, `tree` and `chart` kinds. Still 3.1.0: nothing was tagged since, and every addition is new API (the versioning rules make an
addition a minor, and 3.1.0 is that minor).

- **Datasets, grids, trees and charts** (drishti-views, VIEWS_DESIGN step 5; ViewModel contract 1.1, the pin moved to drishti-rachana
  `g1/integration` 52e112f): a view's `datasets` by name in six forms (`values` with `fields`, `rows`, `name` handed by the page through
  `options.data`, `arrow` by address or base64, REST `pages` read a window at a time, a `server`-side row model), each address passing the
  data allow-list (the page's origin, the view's origin, `options.data.allow`); raw values formatted by the field's `fmt`, the mask as the
  mask; the kinds `grid` (drishti-grid: virtual rows, typed columns, groups, an order, a pivot in the browser, a sign tone, pinned and
  hidden columns, group figures), `tree` (drishti-tree, four modes, measures rolled up) and `chart` (drishti-chart: bar, line, area, scatter,
  pie, donut, heatmap, histogram over a derived read: rows combined per `x` and `by`, the largest categories kept); panels share a dataset;
  the frame op `rows` (reset, remove, update, add by key) reaches every panel that reads it (a grid by transaction, a tree and a chart in the
  next animation frame); each library loaded and its sheets adopted into the shadow root only when a view needs it; `handle.dataset(name)`,
  the events `select` and `drawn`; map `points` drawn as the geo helper's bubbles (a bubble with a link opens it; listed in the map's data
  table). Builders: `dataset(name, rows, o)`, `grid`, `tree`, `chart`, `frame().rows(...)`, `stampOf(view)`: a view is stamped `1.1` only when
  it uses what 1.1 adds. The validator bounds inline rows (`limits.rows`, 50,000). Gallery page *Datasets: grids, trees and charts* with
  made-up data from a seeded generator (a blotter of 10,000 trades that can tick live, a risk hierarchy of 64 books, a year of daily P&L by
  desk with VaR, DV01 by tenor, 3,000 trades by counterparty, and a pivot of 10,000 positions); *Maps in a view* gains trading hubs as
  points. Node tests (`views-data.test.mjs`) and browser tests (`test_views_datasets_browser.py`: 10,000 rows virtual, patches through a
  grid and a chart, a tree and charts of derived reads, pages, a server, rows by name, a refused origin, map points, every gallery
  example). The bundle's budget moves to 192,000 (182,651 measured, plus about 5%: the datasets, three kinds and a schema 13 definitions
  larger).

- **Maps out of the box** (drishti-chart, drishti-views): drishti-chart's **geo part** (`drishti_chart-geohelper.js`,
  `loadPart('drishti-chart', 'geo')`): the map helper of a view's map panels (`world`, `region`, `find`, `place`, `project`, `bounds`,
  `option`, `codeOf`) over the vendored Natural Earth shapes (the world's countries, every country's first-level regions: the United
  States' states with Alaska and Hawaii as insets, Canada's provinces and territories) and the Elements projections, with a layer of
  bubbles at places or at a longitude and latitude. drishti-views uses it by default: a map panel draws with no host helper (a host's
  `geo` still overrides it; `geo: false` turns maps off in words; `mapsBase` names another folder), loading the part and only the
  shapes that map needs the first time a view has a map. `locate(geo, lon, lat)` places a point in the United States' insets (the
  chart's map kind's bubbles use it too); maps keep their shape in any box (`preserveAspect: 'contain'`, also the map kind). `cli.py pack
  --maps` packs the part with the shapes. Gallery pages *Maps in a view* and *The map helper: bubbles*; USING's Maps section; CONTRACTS'
  map helper; Node and browser tests (a world choropleth, a US states map, a Canada map, a bubbles layer).
- **drishti-views** (new, being built in the steps of `docs/VIEWS_DESIGN.md`; steps 1 to 3 here): the ViewModel specification as a
  pinned copy of drishti-rachana's `spec/1/viewmodel.schema.json` with its compact form (`tools/views/gen.py`, written by the build) and
  conformance vectors; `validate(view, { strict })` (normal mode ignores unknown fields and words as the specification asks a consumer to;
  strict mode refuses them; the bounds and the keys that would reach a prototype are refused in both); `paint(el, source, options)` and its
  handle: the title line, the strip, the freshness line and the function keys, the panels' frames with their states, the layout (two
  columns, named areas, tabs, folds, sections, a sticky area, the divider; the container's medium and narrow forms in the phone order),
  frames of the live stream applied in one animation frame, the cards drawn instead of a view, navigation in place; `registerKind`.
  Speed budgets in `tests/views_budget.json`.
- **drishti-views** (step 4, the kinds of words and tables): `kv` and `provenance` (label and value pairs), `status` (the values as
  toned chips), `metric` (one large figure, its change, unit and caption; updated in place by a patch), `markdown` (a basic markup of
  headings, emphasis, code, lists and links built as DOM nodes; a tag in the text stays text, an address an href may not carry stays
  text), `links` (with badges and statuses; a denied or missing one is not a link), `timeline` (oldest first, "3 more events"),
  `table` (sort by a column, a numeric column ordered by its text's number; a filter box; rows a hundred at a time; rows with children
  opened and closed, a level's own column heads, subtotals and a total; a row with a `pick` raises the handle's new `pick` event),
  `ladder` (the current row marked `aria-current`), `pivot` (rows nested by their path, groups opened and closed, the column across,
  row and grand totals, heat over the values) and `tabs` (fields and whole panels per tab, as tabs or columns, nesting at most four
  deep). A patch that replaces a table keeps its sort, filter and open rows. The ViewModel schema re-synced from drishti-rachana's
  g1/integration (only its `$id` changed).
- **drishti-views** (step 6, the kinds of charts): `line`, `area`, `waterfall`, `histogram`, `scatter`, `candlestick`, `surface`,
  `graph`, `map` and `hbar`'s donut drawn by drishti-chart's panel charts (a graph by drishti-graph), loaded through drishti-core's
  loader the first time a view has such a panel (a page of words and tables loads no chart engine; options `libraries`, `geo` and `gl`
  for a host that gives its own); `hbar`'s bars and `gauge` (a `meter`) drawn as elements; every chart carries its data table, closed
  until opened; a patch moves a drawn chart to its new data; the charts are disposed with the view. Every kind of contract 1 is now
  drawn; datasets and the `grid`, `tree` and `chart` kinds wait for the specification (contract 1 has no `datasets`).
- **drishti-views** (step 7, patches): frames that arrive faster than the page paints are conflated (`conflate(patches)`: the newest
  strip cell per index, panel per id and provenance win, in the order of their last arrival, never across `deleted` or `restored`;
  `metrics().conflated` counts them); a hidden page paints nothing, keeps only the conflated pending state and catches up in one paint
  when shown; a view that arrives in pieces (every panel computing, then a `panel` patch each) ends as a one-shot paint of it.
- **drishti-views** (step 8, live): `paint(el, view, { live: { events | url, topic?, raw?, resync?, connect? } })` subscribes the view's
  topic (default `views.<the view's id>`) through the page's Events or one connected for it (drishti-events loaded only then); a
  message of kind `view` paints the whole view again, `view.frame` applies a frame, `view.piece` one patch; a gap drishti-events cannot
  fill (a resync notice, a new epoch) reads the view again (`resync()`, else the address it was painted from) and paints it
  (`metrics().resyncs`); a plain stream (Server-Sent Events named `view` and `frame`) through raw mode; the connection's state is a chip
  in the status line and the view is marked while the line is down; the subscription and its connection stop with the view.
- **drishti-views** (steps 12 and 13, the element and the builders): `<elements-view>` (`define({ tag })`, installed by the bundle;
  `viewModel` and `options` properties, `src`, an inline JSON script, the attributes `density`, `header`, `panels`, `locale`,
  `validate`, `label` and `live`; disposed when it leaves the page; `elements-view:painted`); `view(title)` with one method per kind
  of contract 1 (`provenancePanel` for that kind), `panel(obj)`, `ref`, `title`, `strip`, `key`, `provenance`, `layout`, ids guessed
  from titles and the fields contract 1 requires filled from the schema (`complete`), each panel checked when added and the whole by
  `toJSON()` (a `ViewError` names the path and the fix); `frame(seq)` with `strip`, `panel`, `provenance`, `deleted`, `restored`;
  `sse(write, { topic, raw })` writes a view's stream as the read-only events protocol or the plain form; no DOM touched, so Node runs
  them. Budget 156,000 (measured plus about 5%). USING has a drishti-views section; CONTRACTS the live stream.
- **drishti-events**: a stream born after its subscription was answered (the server named no streams yet) is now resynced too when
  the server restarts: the resync notice reaches every subscription whose pattern hears the stream.

## 3.0.0

Released 2026-11-21. drishti-events, the ninth library, complete (steps E1 to E12): events in the page, across tabs and with a server, one
connection shared by every tab, an offline queue that survives a reload, the mock server and its faults, metrics, "why didn't I get it?",
the status badge and the inspector. Its wire protocol is in docs/CONTRACTS.md. Breaking since 2.4.0: see docs/MIGRATING_TO_3.md (every removal with its replacement).

- **drishti-events** (eventing step E12, documentation and guards): the wire protocol, version 1, is "The events contract" in
  docs/CONTRACTS.md (with `epoch: null` in `after` and `replay`, retained values outside the numbering, and raw mode's resume); how it keeps
  its promises (reliability, security, observability, browsers, accessibility) is in docs/DESIGN.md; docs/USING.md's drishti-events section
  is current (`namespace` is required with `share`, the inspector's drawer and keys, the queue and the badge); the design document is
  folded into them and removed. The release tree carries a `.d.ts` for each part a page imports (`drishti-events-mock.d.ts`,
  `drishti-events-inspector.d.ts`). Byte budgets settled at the measured size plus about 5%. Two guards: what drishti-events replaced is
  gone everywhere and its timing and stream parsing have one home; drishti-events names no host (routes, settings keys, header names,
  error codes, vocabulary).
- **drishti-events** (eventing step E11, the gallery and the browser tests): the gallery's Eventing pages on the mock server (Connection,
  Ticker, Chat, Across tabs, Offline, Unreliable network, Why, Inspector), each with the status badge and "Open the inspector"; the
  library's fixture harness (`drishti_harness.html`: the shared hub, the queue across a reload, the inspector, the ticker) and its browser
  tests over a real local HTTP server (three tabs on one stream in the SharedWorker and in the leader tab, the leader's hand-over, five
  queued edits sent once in order after a reload, the inspector's names and keys, the badge, 10,000 messages a second into a grid). The
  shared hub in raw mode resumes from the server's event id the tabs last heard when a new leader takes over (it started the feed again).
- **drishti-events** (eventing step E10, the inspector): `events.inspect(el?)` opens the inspector (its own file and sheet, loaded when
  opened, with drishti-grid): Messages (time, direction, scope, topic, kind, seq, latency, what happened; a filter by pattern or words;
  pause), Subscriptions, Connection (figures and the state timeline), Queue, Why (`explain` drawn); keys `/`, Space, Enter, Escape;
  `inspectorCommand(events)` for the command palette. **drishti-core**: `loadPart(library, part)`; the manifest names each library's parts.
- **drishti-events** (eventing step E9, observability): `metrics()` in groups (`connection`, `in` with rates over 1 s and 10 s, bytes,
  counts by pattern, delivery latency and listener time; `out` with acknowledgement time and requests; `drops` by reason; `stores`),
  fixed log-scale histograms (`Histogram`, `Rate`); `$events.metrics` once a second while someone listens, and `onMetrics`; the recorder
  (the last 5,000 headers, `recorder: { max, payloads }`, `false` to turn it off); `events.explain({ topic, key, id, since })` answers
  "why didn't I get it?" in steps, the first that failed marked (drawn with `renderExplain`); `statusBadge(el, events)` ("Live",
  "Reconnecting in 4 s" with Reconnect now, "Offline: 3 changes waiting", "Missed 12 updates" with Refresh), announcing at most once in
  five seconds; the library's sheet `drishti_events.css`.
- **drishti-events** (eventing step E8, the mock server): `MockServer` speaks the protocol through an in-memory transport (`connect(server)`
  or `connect('mock:')`): numbered streams, retained values, a replay log, acknowledged and deduplicated publishes, `reply(pattern, fn)`,
  `every(ms, fn)`, and faults on purpose (`drop`, `partition(ms)`, `lose(n)`, `duplicate(n)`, `delay(ms)`, `reorder(n)`, `refuse(what)`,
  `restart()` with a new epoch, `slow(n)` with a gap). The mock and `MockTabs` are their own file, `drishti_events-mock.js`
  (`Drishti.elements.events_mock`), not in the bundle. The conformance vectors (`tests/events/protocol-v1.json`) and their runner
  (`node tools/events/conformance.mjs --url <address>` or `--mock`). The client: a retained value bypasses the numbering; streams a server
  no longer names after `recovered: false` start again with a resync; `after` carries the positions of streams the pattern names.

## 3.0.0-rc.1

The first release candidate of 3.0.0: drishti-events steps E1 to E7 (the kernel, the connection, order and gaps, flow, the offline queue, the tabs and the shared hub). Breaking: see drishti-core and drishti-data below.

- **drishti-events** (eventing step E7, tabs): the `tabs` scope over a `BroadcastChannel` named by the `namespace` (frames checked before
  use; `$` topics never leave), request and reply across tabs (the first tab that answers; a cancel reaches it), `signOut()` told to every
  tab. The shared hub: `connect(url, { share: true, namespace })` gives all the tabs one server connection, held by a `SharedWorker`
  (`drishti_events-worker.js`, option `worker`) or else the leader tab (a Web Lock; the next tab takes over and resumes by sequence), else
  each tab connects for itself; one server subscription per pattern, retained values for a late tab, publishes and requests relayed,
  `auth` asked of a tab; `$events.state` says `shared` and `mode`. `MockTabs` joins several `Events` in one page as tabs (channel, locks,
  worker). The build writes a library's **parts** (files beside its bundle, from the manifest's `parts`); the release tree ships them.
- **drishti-events** (step E4): the Sequencer is wired into the connection (order, repeats, gaps with a `replay` frame, `after` on a `sub`, resume,
  `$events.gap`, a `resync` notice with the subscription `recovering`); `Sequencer` and `SEQUENCE_LIMITS` are exported.
- **drishti-data**: `FeedMonitor` and `feedBadge` are removed (the sequencer's `stats` replace the monitor; the badge returns as `statusBadge` in step 9).
- **drishti-core** (eventing step E1): `EventBus` listens to patterns over dotted topics (`*` one segment, a last `>` the rest; exact
  listeners are told first, then patterns, then a lone `*`); new topic helpers `isTopic`, `isPattern`, `topicMatches`, `segment`,
  `unsegment`, `selectionTopic`, `filterTopic`. Linked selection is the topic `ui.selection.<channel>` and the filter model the topic
  `ui.filter.<channel>`: `SELECTION_EVENT` and `FILTER_CHANNEL` are removed (no aliases); chart, graph, tree, selection chip, grid
  (`followFilters`) and filter bar (`channel` is now a channel name, default `default`) moved in the same change.
- **drishti-events** (new, the ninth library; eventing step E2, the kernel): `createEvents(options)` and `connect()` (no server yet: the
  page and its tabs), `Events` with routes (the most specific pattern wins; `share(pattern, { to })` adds a scope), the page scope as
  drishti-core's shared bus itself, `on`, `subscribe` (a `Subscription`: state, `ready`, `stats`, `latest(key)`, a feed through
  `subscribe(fn)`, `byKind(handlers)` for a live-set channel, an async iterator), `once`, `off`, `publish` (a `Receipt`), `request` and
  `reply` (timeouts, `AbortSignal`, `no.responder`), `stream`, typed `topic(name, { kind, validate })`, `define(kind, validate)` and
  `shape({...})`, `events.page` / `events.tabs` / `events.server` pinned to a scope, the library's own events as `$events.*` topics
  (`$events.error`, `$events.drop`, `$events.subscription`), `EventError`, `TimeoutError` and `AbortError`, one clock for every timer
  (`systemClock()`, `testClock({ seed })`). Budget 110,000 bytes; no domain words (the generic libraries' guard).
- **drishti-events** (eventing step E3, the connection): `connect(url, options)` opens a connection to any server that speaks the events
  protocol, version 1 (`events.v1`: hello, welcome, sub, subd, msg, pub, ok, req, res, cancel, err, gap, auth, ping, pong, bye), over a
  WebSocket, Server-Sent Events by `EventSource` (subscriptions and the resume position in the address), or Server-Sent Events read with
  `fetch` (the token in `Authorization`, hello and subscriptions in the body, `Last-Event-ID`); clients' frames over SSE go to
  `<events>/send`. `protocol: 'raw'` reads a server's own JSON (`topicOf`, `seqOf`, `idOf`, `keyOf`, `kindOf`). The state machine
  (`idle`, `connecting`, `open`, `reconnecting`, `offline`, `refused`, `closed`, each change on `$events.state`), back-off with full jitter
  on the clock (a server's advice a floor), the heartbeat watchdog and WebSocket pings (round trip and clock offset), refusals that stop
  (close 4400 and 4403, HTTP 400, 403, 404, version, `bye` without reconnect), `auth` before every connect, once more after a refusal and
  a minute before a token runs out (an `auth` frame, or a new stream resumed before the old one closes), `wsAuth` (`hello`,
  `subprotocol`, `cookie`), the address guard (no secret in a query), subscriptions sent on open and after every reconnect, publishes
  held in order while down and sent again until acknowledged, requests with `cancel`, hostile frames dropped and counted. `parseSse`
  (now with `retry:`) and `readSse(response)`; `Backoff` and `Heartbeat` on the clock. `connect()` with no address uses the endpoint
  `configure({ endpoints: { events } })` names.
- **drishti-data** (breaking, 3.0.0): `LiveClient`, `LIVE_STATES`, `live()`, `parseSse`, `Backoff` and `Heartbeat` removed: connections
  are drishti-events' (`connect(url, { protocol: 'raw' })` reads the same feeds; a subscription is a feed for `pipeToGrid`,
  `liveSource` and `grid.connectLive`). Its budget is lowered to 42,000 bytes. The guard that only one library opens a WebSocket or an
  `EventSource` now names drishti-events (and `BroadcastChannel`, `SharedWorker`). The gallery's live page is drishti-events'
  "Connection" page; the inputs harness feeds its live grid through a drishti-events connection.
- **drishti-events** (eventing step E5, flow): every subscription has a pipeline between arrival and its listeners: `conflate` (`true`:
  the message's key; `{ key, merge }`: a field of the message or of its data, or a function), a bounded `buffer` (`max`, `overflow`:
  `conflate`, `drop-oldest`, `drop-newest`, `error`), and `deliver` (`'now'`, `'frame'`, `{ everyMs }`) with `batch`; a hidden page
  delivers once a second and keeps conflating; every drop is counted with its reason. `Conflater` (the latest value by key) and
  `GridSink`; `subscription.toGrid(grid, { getRowId, onStats })` feeds a grid with at most one batch in flight (folding the grid's way).
  The page's life: `pagehide` and `freeze` close the connection cleanly (back-forward cache), `pageshow` and `resume` open and resume
  it, `idleCloseMs` gives it back while hidden. Publishing pauses while a socket holds more than 1 MB unsent. `conflate: { server: true }`
  asks a server whose welcome offers `conflate` to conflate before the wire. The bundle's budget is 150,000 bytes for now (step 12
  settles it).
- **drishti-data** (breaking): `TransactionSink` and `pipeToGrid` removed: `subscription.toGrid(grid)` (drishti-events) is the one way.
- **drishti-events** (eventing step E6, the offline queue): every publish to a server waits in `Outbox` until acknowledged (sent again
  after a reconnect, in order); bounded (`outbox: { max, maxBytes, maxAgeMs }`, defaults 1,000 messages, 1 MB, 24 hours; past a bound
  the publish is refused in words, or `overflow: 'drop-oldest'`), aged (a publish's `ttlMs`, dropped as `expired` even while nothing
  else happens); `queue: false` fails at once when not connected; an abort takes back a publish not yet sent. Persistence by choice
  (`queue: 'persist'`, `outbox: { persist: true }`): IndexedDB (`indexedDbStore`, named by the `namespace`), or `memoryStore()`;
  without IndexedDB the queue is memory and `metrics().outbox.persisted` says `false`. What a reload left is sent first, by one tab at
  a time (a Web Lock). `events.flush()`, `events.clearOutbox()` and `events.signOut()` (the queue and the disk emptied, stream positions
  and the session forgotten).
- **drishti-grid**: the grid's style sheet restored in full (a change to it had kept only its last block, so the grid, its pivot wells and
  its side panel were drawn without their look); a guard test now fails when a library's class names lose their rules.
- **drishti-core**: the loader adds a library's style sheets even when its bundle is on the page already (drishti-core by its script
  tag: the resize and chips sheets), and knows a sheet the page links by a relative address as the same sheet.
- **drishti-chart**: the map shapes are not asked for from a page opened from the file system (the browser refuses it); the map says so.
- **Gallery**: the States entry draws again (its compact switch); the documentation's Playground page works from the file system too.
- **Brand**: a light logo for dark backgrounds (`brand/drishti_elements-logo-light.svg`), used by the README in dark mode.

## 2.4.0

The first version of Drishti Elements as its own project: the libraries, the gallery, the build and the tests in one repository, one release
tree built from a tag (`python3 tools/elements/cli.py dist`).

- **drishti-core 2.4.0**: formats and the themes' tokens, the API client with its error contract, events, accessibility helpers, resize, keymap, dialogs, drawers and toasts, the state kit, the command palette and the dock, the filter model every library shares, the loader. New: `configure({ endpoints })` and `endpoint(name)` name the addresses of the host's server (live, changes, pivotPage, capabilities); `register(name, options)` adds a library that lives outside the manifest to the loader; `ApiClient` takes `asOfHeader`. The loader needs no built-in folder: it uses the folder the core bundle was loaded from, or `configure({ base })`.
- **drishti-inputs 2.4.0**: date and range pickers with holiday calendars, the multi-select, the combobox, forms from a schema with four eyes, the filter bar, the code editor over CodeMirror.
- **drishti-grid 2.4.0**: the grid and its pivot in three layouts and three densities, cell editing, the live-set mode, export, the AG Grid-style adapter. New: the live-set client and the pivot link read their addresses from the configured endpoints (`base`, `changesUrl` and `pivotPage`); without one the feature says so in words.
- **drishti-chart 2.4.0**: one engine-neutral spec over ECharts, the general kinds, the maps from the world to every country's first-level regions, Explain on any mark, export, linked selection and live updates. New: the maps folder is required when the core bundle gives no folder.
- **drishti-data 2.4.0**: one data-source contract (memory, REST pages, server-side rows, Arrow, live), the live client over SSE and WebSocket, the Arrow client. New: `capabilitiesUrl` defaults to the configured endpoint `capabilities`; with none the client uses Arrow or JSON over HTTP only.
- **drishti-tree 2.4.0**: hierarchies as an indented tree grid, an org chart, a sunburst and an icicle, with measures, search, an edit mode that proposes moves, and the outline of an authoring screen.
- **drishti-graph 2.4.0**: networks on a canvas with force, layered, radial, circular and grouped layouts, paths, groups, selection and an adjacency table.
- **drishti-designer 2.4.0**: the designer kit: the layout canvas, palette, inspector, outline, YAML pane, undo and redo, and the designer shell.

Also in 2.4.0:

- **drishti-chart**: heatmap tracks, category cells and the overview strip; a scatter's segments, top labels and surface; the
  rectangle brush; frames with a slider and Play; box plots from given figures; the kinds `segmented`, `matrix`, `lollipop` and `trace3d`
  (on the vendored ECharts GL, loaded the first time, drawn flat or as its table without WebGL). Each has a gallery page and Node tests.
- **drishti-core**: `Tour`, a page walked in steps (marks for what each step says and shows, back, at and next controls).
- **drishti-grid**: Escape closes Explain whatever came before it (a pending focus follow never reopens a closed Explain).
- **drishti-inputs**: the query builder's choices on drishti-inputs' Select; the selection chip's history as buttons.
- **Build**: a host's own libraries (`build.py --root --manifest`) require this project's bundles under Node by their path from the
  library's folder, and a host's manifest may leave the version out (this project's is used).
- **Vendor**: ECharts GL 2.1.0 (BSD-3-Clause, one local change without eval, `vendor/echarts-gl/PATCHED.md`).
- **drishti-grid**: the grid's style sheet restored in full (a change to it had kept only its last block, so the grid, its pivot wells and
  its side panel were drawn without their look); a guard test now fails when a library's class names lose their rules.
- **drishti-core**: the loader adds a library's style sheets even when its bundle is on the page already (drishti-core by its script
  tag: the resize and chips sheets), and knows a sheet the page links by a relative address as the same sheet.
- **drishti-chart**: the map shapes are not asked for from a page opened from the file system (the browser refuses it); the map says so.
- **Gallery**: the States entry draws again (its compact switch); the documentation's Playground page works from the file system too.
- **Brand**: a light logo for dark backgrounds (`brand/drishti_elements-logo-light.svg`), used by the README in dark mode.
