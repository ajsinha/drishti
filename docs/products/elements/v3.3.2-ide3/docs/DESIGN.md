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
# Design

How Drishti Elements is built and the rules it keeps. How to use it is [USING.md](USING.md); every option and call is in the generated
[API.md](API.md); every component is live in the gallery.

## 1. One implementation of each thing

An application that grows screen by screen collects a grid per screen, a chart wrapper per chart, a number formatter per file, each with its own
keyboard, accessibility, bugs and look. Drishti Elements is one implementation of each: ten libraries, each with one job, and a guard that stops
a second copy from growing beside them (section 11).

## 2. The ten libraries

| Library | What it is | Requires |
|---|---|---|
| **drishti-core** | formats, theme tokens and the theme switch, the API client, the event bus, accessibility helpers, resize, the keymap, dialogs, drawers, toasts, the state kit, the command palette, the dock, the filter model every library shares, the loader | none |
| **drishti-inputs** | date and range pickers with holiday calendars, the switch, the tree picker, the multi-select, the combobox, forms from a schema with four eyes, the filter bar, the code editor, the formula input | core |
| **drishti-chart** | one engine-neutral spec over the vendored ECharts, the general kinds, the maps, the card and studio modes, Explain on any mark, the data table, export, linked selection, live updates, Vega-Lite translated into the spec | core, inputs |
| **drishti-grid** | the grid and its pivot in three layouts, tree data, ranges, cell editing, the live-set mode, export, the AG Grid-style adapter | core, inputs, chart |
| **drishti-data** | one data-source contract (memory, REST pages, server-side rows, Arrow, live), the Arrow client | core |
| **drishti-events** | events in the page, across tabs and with a server (WebSocket, Server-Sent Events): topics with wildcards, publish and subscribe, request and reply, streams, routes, reconnect and resume; the one home of every connection to a server | core |
| **drishti-tree** | hierarchies in four modes of one component: the tree grid, the org chart, the sunburst, the icicle; measures with non-additive ones asked of the host; an edit mode that proposes moves; the outline of an authoring screen | core, inputs, grid |
| **drishti-graph** | networks on a canvas for thousands of nodes: force, layered, radial, circular and grouped layouts, paths, groups, selection, an adjacency table | core, inputs, grid |
| **drishti-designer** | the designer kit: the layout canvas over a layout model, palette, inspector, outline, YAML pane, undo and redo on one command stack, the designer shell | core, inputs, tree |
| **drishti-views** | whole screens from a ViewModel: the specification's validator (contract 1.x, a pinned copy of drishti-rachana's schema), the painter (the screen, the panels' frames and states, the layout, every kind of contract 1, the frames of the live stream conflated, a live stream over drishti-events), the datasets of contract 1.1 (inline, by name, Arrow, pages, a server-side row model; rows patches by key) and the kinds that read them (`grid` on drishti-grid, `tree` on drishti-tree, `chart` on drishti-chart over a derived read), map points as bubbles, `<elements-view>`, the builders of views and frames and `sse()`, and the registry of panel kinds; a library (drishti-grid, drishti-tree, drishti-chart, drishti-graph, drishti-data) is loaded only for a view that needs it | core |

A library that draws a choice draws it with drishti-inputs' `Select`, so the grid, the chart, the graph and the tree require inputs. Nothing
depends on a host: a library names no host route and needs no host page (section 4).

## 3. The rules every library keeps

- **No framework required.** Each component is a **web component** (`<drishti-grid>`, `<drishti-chart>`, ...) and a **plain JavaScript class** with the
  same options (`new Grid(el, options)`); React, Angular and Vue use them as they use any custom element.
- **Plain ES modules, no build step**, readable as served; one entry per library, its style sheet, a `.d.ts` and a bundle. The bundle is written by
  `python3 tools/elements/build.py` (offline, no npm): a classic script that registers the library on `window.Drishti.elements` and is a CommonJS module
  under Node. A stale bundle fails the checks.
- **Vendored, never a CDN.** Engines (ECharts, CodeMirror, Arrow JS) and map shapes live under `vendor/` with their licences, listed in
  `vendor/VENDOR.md`.
- **Files under 2,000 lines**; object-oriented, configuration-driven, one namespace on `window` (`Drishti.elements`).
- **The theme is CSS custom properties** from drishti-core's tokens ([the Theming section of USING.md](USING.md#theming)); light and dark, three **densities** (comfortable,
  compact, terminal) shared by every component. Colours come from tokens only.
- **Accessibility is part of the component**: roles, names, keyboard, focus, announcements, contrast (WCAG 2.2 AA); every drawing has a table or text
  alternative; `prefers-reduced-motion` is honoured.
- **The browser computes no figure a server owns.** The grid's client-side aggregation exists for standalone use and is switched off with
  `clientAggregation: false`; every total is then the server's, and a non-additive measure (a ratio, a distinct count, a percentile) is never a sum.
- **One chrome**: the same toolbar, menus, status bar, Explain entry and export menu on the grid, the chart and the map.
- **No bespoke UI in a host**: an authoring screen is assembled from the libraries; a need they do not meet is met by extending a library.

## 4. A library names no host

Everything that belongs to the host is an option:

- **Where the libraries are.** The loader's default folder is the folder the core bundle was loaded from; an ES module import with no script tag calls
  `configure({ base })`. The map shapes are `vendor/echarts/maps/` beside the elements folder (option `mapsBase`).
- **Where the host's server is.** `configure({ endpoints: { live, changes, pivotPage, capabilities } })`; a component that needs an address it does not
  have is off and says so in words ([CONTRACTS.md](CONTRACTS.md)).
- **Libraries that live elsewhere.** `Drishti.elements.register(name, { bundle, requires, css, scripts, module, base })` adds a library to the loader, which
  orders and refuses it by the same rules as the manifest's (`module` is the host's own grouping: a host may allow only some modules).
  `python3 tools/elements/build.py --root DIR --manifest FILE --header FILE --elements DIR` bundles them.
- **Errors and headers.** The error shape is `{ code, detail }` with any code string; the as-of header's name is `asOfHeader`.
- **Explain.** An option `explain` supplies the answer; without it a component shows no Explain action.

## 5. drishti-core

Formats (numbers, money, percentages, dates, durations, "3 min ago", with half-up rounding), the themes and the switch, the API client (cancellation,
retries, an error in words), a typed event bus, accessibility helpers (live region, focus trap, roving tab index), the one resize component, the keymap
(one registry, conflicts refused), dialogs, menus, toasts and popovers, the state kit (loading, empty, error, offline blocks), chips for list
attributes ("+N more"), the command palette, the dock, the CSV writer, typed number reading, and the loader that loads other libraries with their sheets and
engines once each.

## 6. drishti-grid

**Three layouts of one component**, chosen with `layout` (they share the grid, the pivot model, the menus, the status bar and Explain):

| `layout` | Arrangement | Best at |
|---|---|---|
| `'wells'` | one line above the grid: Rows, Columns and Filters as chips, the measures as Values, a field picker behind each well | a pivot on a page among other things |
| `'panel'` | Build (the wells as lists, pivot mode, subtotal and grand-total switches, deferred update) and Fields (search, hierarchies, folders), closing to a rail | building and changing pivots all day |
| `'terminal'` | a command line, compact wells, a dense grid, Explain in a split pane below, function keys at the foot | the keyboard |

**Features.** Virtual rows and columns (millions of rows); server-side rows in windows with prefetch and cancellation; column pinning, reorder, resize,
autosize, groups, header menus; multi-column sort, Excel-style filters and a quick filter; tree data and grouping; cell ranges with a status bar (count,
sum, average, min, max, and a warning when a range mixes measures or sums a non-additive one); copy and paste; editing with validation, undo and four
eyes; conditional formatting; a context menu extendable by the host; CSV and Excel export from the browser; the keyboard of a grid with `role="grid"` or
`treegrid` and virtual `aria-rowcount`.

**The pivot.** A pivot is a view: `rows`, `columns` (dimensions, in order), `values` (a measure with its aggregation), `filters` (members, and value
filters such as "greater than" or "top 5 by"), `totals`, `expanded`. The grid draws row groups opened lazily, pivot result columns with collapsible
column groups, totals and subtotals in the server's order, a grand total row and column, `≈` beside an approximate figure and `◆` on a non-additive
measure. Two row models: **server-side** (the grid sends the view and the window it needs and the server answers rows in display order with every total
computed: non-additive measures are right at every level) and **client-side** (rows in memory, built-in aggregations sum, count, average, min, max, first,
last, distinct count and the host's registered functions; a measure marked `aggregate: 'server'` shows no client total).

**The live-set mode.** `LiveSetGrid` draws a live result set of a server (a blotter, a form or query result): the snapshot, then the changes the server
streams, ticking cells, a record panel beside the grid and the person's tray. It decides nothing: rights, masks, versions and four eyes are the server's.

**The AG Grid-style adapter.** `createGridFromAgOptions(el, gridOptions)` takes the option shapes of AG Grid Enterprise's row grouping, pivoting,
server-side row model and tree data and builds a drishti-grid. Drishti Elements never uses AG Grid: no code, package, CSS or look; the adapter was written
from AG Grid's public documentation of its option and API shapes only. Every option not taken in full is named once in the browser's console and by
`reportUnsupported()`. The mapping's one home is the code (`AG_MAPPING` in `drishti_grid-ag-map.js`); the gallery's tab and
the AG Grid section of [USING.md](USING.md#moving-an-ag-grid-application) are generated from it.

## 7. drishti-chart

**One engine-neutral spec above ECharts**; nothing in a spec names the engine:

```json
{ "kind": "line", "title": "Daily P&L", "data": { "source": "/data/pnl.json" },
  "x": { "field": "date", "type": "time" }, "y": { "format": "money" },
  "series": [{ "field": "hypothetical" }, { "field": "limit", "negate": true, "style": "dashed", "tone": "bad" }],
  "legend": "bottom", "zoom": { "window": [150, 250] }, "onClick": "explain", "export": ["png", "svg", "csv"], "a11y": { "table": true } }
```

Every chart gets the theme and density, drishti-core's formats on axes and tooltips, a crosshair tooltip, legend toggles, click to Explain, export (PNG at
2x, SVG, CSV, the spec), a **data-table alternative** and the grid's card chrome and status line. **Kinds**: line, area, bar (grouped, stacked,
horizontal), scatter, bubble, heatmap (sequential and diverging), waterfall, candlestick, histogram, box plot, treemap, sankey, graph, gauge,
sparkline, calendar, map, segmented, matrix, lollipop, 3D trace. A kind is a module that turns the spec into engine options; a domain library registers its own (`registerKind`) and it appears
in the gallery like the general ones.

**Maps.** `kind: "map"`: the world, then a country's first-level regions (US states and DC, Canadian provinces and territories, and every country with a
file), from the vendored Natural Earth shapes (`world-countries.json` at 1:110m, `world-points.json` for countries too small for that scale,
`regions/<ISO3>.json` at 1:10m simplified; public domain). A country's regions load when first opened and stay cached; nothing comes from a map service.
Codes are ISO 3166 (alpha-3 for countries, 3166-2 for regions). Projections: Equal Earth for the world, Albers for the USA (with Alaska and Hawaii
insets) and Canada, a conic fitted to the country otherwise. The grouping of countries into sub-regions and continents is one table,
`data/world-regions.yaml`, which a host's server may read too.

**Vega-Lite** is translated into the spec (`fromVegaLite`) and drawn by drishti-chart; Vega itself is not drawn: one drawing engine.

**More kinds and options**, each with its gallery page and Node tests; a domain library builds on them and draws nothing itself:

| Capability | Spec | For example |
|---|---|---|
| Heatmap **tracks** | `tracks: [{axis: 'x'\|'y', title, values \| field, type, tones, scale}]`, `trackSize` | a limit's status over desks, a region beside each row |
| Heatmap **category cells** | `value: {field, type: 'category', tones, order}`; `x.domain` (every column, an empty one too), `x.labels: false` | a status matrix (within, warning, breach) |
| Heatmap **overview strip** | `zoom: true` or `{window: [first, last]}`: a window over the columns, the tracks move with it | a wide date-by-row matrix on a phone |
| Scatter **segments** | `segments: {values: [{name, from, to}], tones}`: one value axis of named segments, alternate bands and tones | sessions of a trading day |
| Scatter **top labels** | `labelTop: {field, n}`: the tallest point of the top members named | the largest moves of the day |
| Scatter **surface** | `surface: {rows: [{x, y, value}], cell, scale}`: the server's grid of values under the points | a cost surface under the points |
| **Rectangle brush** | `brush: 'rect'`: the points inside are selected, told to the link's channel; event `brushed` {box, ids} | picking points on a scatter, a linked grid follows |
| **Frames** | `frames: {field, play, interval, mode: 'only'\|'upto', at, label, playText}`: a slider and Play (none with reduced motion); `setFrame`, `frame`, `play`, `pause`; event `frameChanged` | stepping the dates of a history |
| Box plot from the server's figures | `boxes: {low, q1, median, q3, high, n}` (field names; nothing computed in the page) | quantiles computed by a server |
| `segmented` kind | `x`, `y`, `segments: [{name, from, to}]`, `thresholds: [{value, label, tone}]` (the first colours the points past it), `labelTop` | moves through a day by session |
| `matrix` kind | `x: {field, domain}`, `y: {field, domain}`, `value: {field, tones, order}`, `tracks`: category cells in the given order, every column kept, the overview strip past 150 columns | a status matrix by row and day |
| `lollipop` kind | `x`, `y`, `colour` (a category's tones or a number's ramp with `legend` words), `length`, `bands` | events along a year |
| `trace3d` kind | `x`, `y`, `z`, `order`, `colour`, `selected`, `turn` (none with reduced motion), `fallback: 'flat'\|'table'`: points joined in order on the vendored ECharts GL, loaded on first use; where WebGL is off, drawn flat or its table shown | a path or a trajectory |
| drishti-core **`Tour`** | marks `data-tour-say`, `data-tour-steps`, controls `data-tour="back\|at\|next"`; `go`, `next`, `back`, `destroy` | a guided first visit of any board |

## 8. drishti-events

One event model for the page, its tabs and a server; the one home of every connection to a server: outside drishti-events no library
constructs a `WebSocket`, an `EventSource`, a `BroadcastChannel` or a `SharedWorker`, and what it replaced (drishti-data's live client, the grid
sink, the feed monitor, the core's selection constant) is gone with no alias (guards in `tests/test_elements_guards.py`). It names no host:
its files, the gallery's Eventing pages and the conformance vectors carry no host's routes, settings keys, header names, error codes or
vocabulary (a guard too). The wire protocol is [CONTRACTS.md](CONTRACTS.md#the-events-contract-drishti-events-protocol-version-1); how to use it
is [EVENTS.md](EVENTS.md). It is plain ES modules under `elements/drishti-events/src/`; three **parts** are files of their own beside
the bundle, loaded only when needed by drishti-core's `loadPart(library, part)`: the shared hub's worker, the mock server (demos and tests;
never needed in production) and the inspector.

### Reconnect and back-off

A dropped connection is opened again after an exponential back-off with full jitter (500 ms growing to 30 s; a server's advice in `bye` or
SSE's `retry:` is a floor), at once when the browser comes back online or the tab becomes visible, and never in a hot loop: a refusal that
trying again cannot cure (bad address, version, forbidden) stops in the state `refused` with the reason in words.

### Heartbeats

Any frame is a sign of life. With none for `heartbeatMs` (the server's figure from `welcome`, else 45 s; the watchdog allows 1.5 times) the
connection is taken as dead and opened again. On a WebSocket the client pings every third of that and the pong carries the server's time, which
gives the round-trip time and the clock offset used for latency (Metrics, below). On SSE the server sends a comment line;
an `EventSource` cannot see comment lines, so over it the watch is on only when `heartbeatMs` is given. Raw mode's watch is `heartbeatMs` itself.

### Resume and replay

A server numbers the messages of each **stream** with `seq` and names the stream's incarnation with an `epoch`. The client keeps the last
`seq` of each stream it hears (bounded: 5,000 streams, least recently heard forgotten) and, when it subscribes again after a reconnect, says
where it was. The server answers `recovered: true` and sends what was missed, or `recovered: false` (too long ago, or a new epoch): the
subscription then enters `recovering`, a `resync` notice is delivered (`byKind({ resync })`, or `m.kind === 'resync'`), retained values are
asked for when the subscription wants them, and the page shows the state again from the truth.

### Once only, and in order

- **Inbound.** Within a stream a message whose `seq` is not above the last one delivered is a repeat and is dropped; messages with no `seq` are
  deduplicated by `id` in a window of the last 2,048 ids. Order within a stream is the server's; nothing is promised across streams.
- **Outbound.** Every publish carries an id made in the page (sortable by time, random per tab). The client sends it again until acknowledged;
  the server ignores an id it has seen ([CONTRACTS.md](CONTRACTS.md)). Together: at-least-once delivery with idempotent receipt, which is exactly-once in
  effect for as long as the server remembers ids.
- A page's own publishes to one topic leave in the order they were made, including across a reconnect and a flush of the queue.

### Gaps

A `seq` that jumps is a gap. The messages after it are held (at most 5,000 per stream, at most `gapWaitMs`, default 2 s) while the client asks
the server to `replay` the missing range; when they arrive everything is delivered in order. If they cannot be had the held messages are
delivered, the gap is published on `$events.gap` with the range, and the subscription resyncs. The **feed monitor** is this same sequencer
seen from outside: `subscription.stats` (received, gaps, missed, repeats, stale) and the status badge's "Missed 3 updates" come from it.
`staleMs` on a subscription says how long silence is normal.

### Backpressure, conflation and frames

Messages can arrive faster than a page can draw. Between arrival and the listener each subscription has a small pipeline:

1. **Conflate** by key: a later message replaces an earlier undelivered one with the same key (a price tick per symbol). For grid transactions
   the rule is the grid's (an add then an update is an add of the new row, an add then a remove is nothing).
2. **Hold** in a bounded buffer; the overflow rule is the subscription's, and every message dropped is counted and recorded with its reason.
3. **Deliver** now, once per animation frame, or at most every `everyMs`, optionally as one array. A hidden tab has no animation frames: the
   pipeline then delivers on a one-second timer and keeps conflating, so memory stays bounded and the page is right when shown again.

`subscription.toGrid(grid)` is this pipeline ending in `applyTransactionAsync`, with at most one batch in flight.

Toward the server: a subscription may ask the server to conflate before the wire (`conflate` in `sub`, where the server can), publishing
pauses while the socket's `bufferedAmount` is above 1 MB, and a server that had to drop for a slow client says so with `gap`.

### The offline queue

Every publish to a server waits in the queue until the server acknowledges it, so one made while the connection is down is held and sent,
in order, when it is back. A page takes back a reload's leftovers when it persists (`outbox: { persist: true }`, a `store`, or
`outbox: { restore: true }`); they go before the page's own.

| | Default | |
|---|---|---|
| where | memory | `queue: 'persist'` on a publish, or `outbox: { persist: true }`, keeps it in IndexedDB so it survives a reload |
| bounds | 1,000 messages, 1 MB, 24 hours | past them a publish rejects in words (`outbox: { overflow: 'drop-oldest' }` to drop instead) |
| flush | after `welcome`, in order, each acknowledged before it is removed | one tab flushes at a time (a Web Lock), so tabs never send a queued message twice |
| requests | never queued across a reload | a request fails on its timeout; the asker is gone after a reload |

Persistence is off by default because the payloads rest on the person's disk (What is kept, below). Where IndexedDB is not to be had (some private
windows) the queue is memory and `events.metrics().outbox.persisted` says `false`.

### Memory bounds

Every store has a ceiling and a counter: undelivered messages per subscription (10,000), held messages per gap (5,000), streams remembered
(5,000), ids for duplicates (2,048), the queue (1,000 and 1 MB), the recorder (5,000 headers), pending requests (1,000), frame size (1 MB;
larger frames are dropped as `too-large`). `limits` changes any of them; memory is bounded by them, whatever the server sends.

### One connection for all tabs (the shared hub)

`connect(url, { share: true })`: the application's tabs share one server connection, which matters most for Server-Sent Events over HTTP/1.1,
where a browser opens six connections to a site and no more.

1. A `SharedWorker` holds the connection, parses and deduplicates off the page's thread, and posts each tab what its subscriptions want;
   each tab keeps its own pipeline (conflation, frames, buffers) and its own unacknowledged publishes (sent again, same ids, when the hub
   changes). A subscription held by several tabs is one subscription to the server; a tab that joins late is given the retained values.
2. Where there is no `SharedWorker`, the tab holding a Web Lock is the leader and relays over a `BroadcastChannel`; when it closes the next
   tab takes over and resumes by sequence from the positions the tabs report (raw mode: from the server's event id they last heard).
3. Otherwise each tab connects for itself.

The worker is its own file (`drishti_events-worker.js`), same origin, no blob and no `eval`. The tabs must belong to one signed-in person:
the `namespace` (Tabs, frames and origins, below) keeps two people's tabs apart.

### The page's life

On `pagehide` and `freeze` the connection is closed cleanly (the page stays eligible for the browser's back-forward cache) and on `pageshow`
and `resume` it is opened and resumed. A tab hidden for longer than `idleCloseMs` (off by default) gives its subscriptions back and takes
them again when shown.

### Who is connecting

`auth` is one function, asked before every connect and before a token runs out:

```js
connect(url, { auth: async ({ reason, status }) => ({ token, expiresAt }) });   // reason: 'connect' | 'expiring' | 'refused'
```

| Transport | How the token travels |
|---|---|
| `fetch` (SSE) | the `Authorization: Bearer` header; any `headers` (an object or a function) |
| WebSocket, `wsAuth: 'hello'` (default) | in the first frame; the server closes a socket that has not said hello within a few seconds |
| WebSocket, `wsAuth: 'subprotocol'` | as a second sub-protocol, `bearer.<token, base64url>`, for a gateway that must decide at the handshake |
| WebSocket or `EventSource`, `wsAuth: 'cookie'` | the site's cookie; the server must check `Origin` |

**Refresh.** A minute before `expiresAt` the client asks `auth` again and hands the new token to the open connection (an `auth` frame on a
WebSocket; on `fetch` a new stream is opened and resumed before the old one is closed, with duplicates dropped by sequence), so a session
outlives its tokens without a gap. A refusal asks `auth` once with `reason: 'refused'`; a second refusal is the state `refused`, never a loop.

### No secrets in addresses

`connect` refuses an address whose query names a secret (`token`, `access_token`, `jwt`, `key`, `apikey`, `secret`, `password`, `auth`,
`signature`) and says how to send it instead. The resume position travels in a header (`fetch`) or the `lastEventId` parameter
(`EventSource`); it is a position, not a secret. Tokens are never logged, recorded, shown in the inspector or written to the queue.

### Tabs, frames and origins

- `BroadcastChannel`, `SharedWorker` and Web Locks are same-origin by the browser's rule. On top of that the channel's name carries the
  `namespace` the host gives (an opaque string that differs per application and per signed-in person), so a second person signed in on the
  same origin shares nothing. With `share` on and no namespace, `connect` refuses, in words.
- Every message from another tab is checked before use: protocol version, envelope shape, topic grammar, size. It is data, never code or HTML.
- Only topics routed to `tabs` leave the page; `$` topics never do.

### Content-Security-Policy

No `eval`, no `new Function`, no inline script or style, no blob or data workers, nothing from another origin. A page needs `connect-src`
for its server and `worker-src 'self'` for the shared hub. The library's browser tests run under a strict policy.

### What is kept, and for how long

The queue's persisted messages are the page's own publishes; persistence is opt-in, bounded and aged (24 hours), and `events.signOut()`
clears the queue, forgets the stream positions and tells the person's other tabs to do the same. The recorder keeps headers only unless a
developer turns payloads on, and lives in memory.

### Hostile input

Topics, kinds and sizes are validated on the way in; maps are `Map`s (no prototype tricks); a frame that is not JSON, is too large, or names
an unknown type is dropped and counted; nothing received is ever put into the page as HTML by the library (the inspector draws text).

### States

```
idle ──► connecting ──► open ──► closed
              ▲           │
              │           ▼
           reconnecting ◄─┘        offline (the browser says so; waits for "online")
                                   refused (trying again cannot help; says why)
```

Each change is a message on `$events.state`: `{ state, was, attempt, waitMs, reason, transport, shared, resumed }`. Subscriptions have their
own (`pending`, `active`, `recovering`, `refused`, `closed`) on `$events.subscription`.

`statusBadge(el, events)` draws the state with drishti-core's state kit: "Live", "Reconnecting in 4 s" with a **Reconnect now** button,
"Offline: 3 changes waiting", "Missed 12 updates" with **Refresh**. It is a `role="status"` region that announces changes of state, never
each message.

### Metrics

`events.metrics()` answers one plain object, also published on `$events.metrics`:

| Group | Figures |
|---|---|
| connection | state, transport, connects, reconnects, time connected, round trip (last, p50, p99), clock offset |
| in | messages and bytes (total, per second over 1 s and 10 s), per topic pattern; delivery latency (publisher's time to listener, p50, p99); listener time (p99, the slowest pattern) |
| out | publishes, acknowledged, retried, failed; acknowledgement time; requests (sent, answered, timed out) |
| drops | by reason: `duplicate`, `conflated`, `overflow`, `invalid`, `filtered`, `too-large`, `expired`, `unrouted`, `gap` |
| stores | undelivered, held for gaps, the queue (count, bytes, persisted), pending requests, recorder |

Histograms are fixed log-scale buckets (no dependency, constant memory). `onMetrics` hands the object to a host's own telemetry.

### The inspector

`events.inspect()` opens the inspector in a drawer at the page's side (or `events.inspect(el)` in a page's own element, a dock's panel for one); the command palette has
"Open the events inspector". It is assembled from the libraries (the message list is a drishti-grid, the filter a drishti-inputs field, the
answer of "why" is drishti-core's Explain drawing) and loads only when opened.

- **Messages**: time, direction, scope, topic, kind, seq, size, latency, and what happened (delivered to 2, conflated, dropped: why); a
  filter: a topic pattern when it has `*` or `>`, otherwise words in the topic, kind or outcome; pause and resume; the message opened as JSON (its payload when the recorder keeps payloads).
- **Subscriptions**: pattern, scopes, state and counts.
- **Connection**: the figures and the state timeline, with the reasons.
- **Queue**: what waits, and since when.
- **Why**: "Why didn't I get it?", below.

Keyboard: the grid's own keys in the list, `/` to filter, `Space` to pause, `Enter` to open a message, `Esc` to close; every colour has a
word or an icon beside it; light and dark from the tokens; `prefers-reduced-motion` stops the scrolling tail.

### "Why didn't I get it?"

Every place a message can stop records a reason against its id, topic and seq in the **recorder** (a ring of the last 5,000 headers).
`events.explain({ topic, key?, id?, since? })` reads it and answers an `ExplainResult` (drishti-core's shape, drawn by `renderExplain`), a
list of steps in words, the first that failed marked:

| Step | A failing answer reads |
|---|---|
| a listener matched | "No subscription matches `prices.fx.USDCAD`. The nearest is `prices.eq.*`." |
| the route reached that scope | "`prices.>` is routed to the page only; nothing asked the server." |
| the subscription was active | "`prices.fx.*` was refused by the server at 10:42:07: forbidden." |
| the connection was open | "The connection was reconnecting from 10:41:58 to 10:42:03." |
| the message arrived | "Stream `prices.fx` jumped from 48,213 to 48,220; a replay was asked and answered `unavailable`." |
| it was valid | "Dropped as invalid: `bid` should be a number." |
| it was kept | "Conflated: replaced by seq 48,231 with the same key 8 ms later." / "Dropped: the buffer was full (10,000), rule drop-oldest." |
| the listener ran | "The listener threw: TypeError ... (see `$events.error`)." |

and when nothing failed: "Delivered at 10:42:07.118 to 2 listeners, 14 ms after it was published."

### Browsers

The current and previous major versions of Chrome, Edge, Firefox and Safari, desktop and mobile.

| Needs | Without it |
|---|---|
| WebSocket, `EventSource`, streaming `fetch`, `AbortController` | (all supported browsers have them) |
| `BroadcastChannel` | the `tabs` scope is off and says so |
| `SharedWorker` | the leader tab (Web Locks), else a connection per tab |
| IndexedDB | the queue is memory only |
| `file://` | page scope, mock server, queue and every gallery page work; real cross-tab and the shared worker depend on the browser and say so |

Under Node 22 or later the page scope, the mock and the WebSocket and `fetch` transports run as they are, which is how the tests run.

### Accessibility

- The status badge is a `role="status"` live region: it speaks changes of state and counts ("Reconnecting", "Live again, 3 updates missed"),
  rate-limited to one announcement in five seconds, and never individual messages. Its button is a real button with a name.
- The inspector meets the libraries' rule (WCAG 2.2 AA): the grid's roles and keyboard, a focus trap only when it is a dialog, names on every
  control, outcomes as words and icons beside colour, tokens for light and dark and the three densities, reduced motion honoured.
- Nothing the library does steals focus or announces by itself in a host's page; flashing cells are the grid's and follow its reduced-motion rule.

### Bytes

The bundle and the parts have byte budgets like every library (`tests/test_elements_budget.py`): each is its size when 3.0.0 was made ready
plus about 5%, so growth is a decision, not a drift.

## 9. drishti-views

The aim: **anyone's server, script or page can describe a whole screen as JSON and have Drishti Elements paint it.** A screen is a **ViewModel**:
a title line, a strip of figures, panels of many kinds, a layout, controls, datasets and, when it is live, frames of patches. The description is an
open, versioned specification with a JSON Schema; the painter is one library over the grid, the chart, the tree, the graph and the inputs that
already exist. No framework, no build step, no CDN; it runs from `file://`. How to use it is [VIEWS.md](VIEWS.md); the shapes are in
[CONTRACTS.md](CONTRACTS.md#the-viewmodel-drishti-views).

### Three things, each with one home

| Thing | What it is | Home |
|---|---|---|
| **The specification** | the ViewModel as a JSON Schema (contract 1.x), the frames and patches of a live view, and the conformance vectors; its one home is drishti-rachana's `spec/1/viewmodel.schema.json`, and drishti-views carries a byte-for-byte copy whose SHA-256 is pinned beside it | `elements/drishti-views/spec/` |
| **The painter** | `paint(el, viewModel, options)` and `<elements-view>`: draws a ViewModel, applies frames, follows a stream | `elements/drishti-views/src/` |
| **The builders** | a fluent builder of views and of frames, and `sse()`, in one source that runs in the browser and under Node (no DOM) | `elements/drishti-views/src/drishti_views-build.js` |

A ViewModel is **data, no logic**. It holds no expression, no script, no template and no style text. A client walks the JSON and draws it; a script
reads it; a test compares it; a stream patches it. That is what makes it safe to accept from a server one does not control, easy to write from any
language, and stable across versions.

drishti-views draws nothing of its own that another library draws. A grid is drishti-grid, every chart is drishti-chart (the only place an engine is
called), a tree is drishti-tree, a network is drishti-graph, a control is a drishti-inputs component, a live connection is drishti-events. It owns only
what is about a *screen*: the frame of a panel, the layout, the title line and strip, the states, the application of frames, and the specification.
A library is loaded only for a view that needs it: a view of words and tables never loads a chart engine.

### The modules

| File | Holds |
|---|---|
| `spec/drishti_viewmodel.schema.json`, `spec/drishti_viewmodel.pin.json`, `spec/drishti_vectors.json` | the pinned copy of the schema, its hash and the rachana commit it was taken at, and the conformance vectors |
| `drishti_views-schema.js` | **generated** from the copy by `python3 tools/views/gen.py` (and by the build): the compact form the validator reads |
| `drishti_views-validate.js` | the validator (the keywords the schema uses, no dependency; normal and strict modes), the bounds (`LIMITS`) and the refused keys |
| `drishti_views-build.js` | the builders of views and frames, `ViewError`, and `sse()` |
| `drishti_views-paint.js`, `-screen.js` | `paint` and the handle; the title line, the strip, the freshness line, the function keys and the cards a view shows instead of itself |
| `drishti_views-layout.js` | the 12-column flow, named areas, tabs, folds, sections, the divider, the narrow and medium forms of the container |
| `drishti_views-panels.js`, `-kinds.js` | the panel frame and its states; the registry of kinds (`registerKind`) |
| `drishti_views-cells.js`, `-words.js` | cells, tones, links and the painter's own words; the words-and-figures kinds and the markdown parser |
| `drishti_views-tables.js`, `-tabs.js`, `-charts.js` | the table, ladder and pivot kinds with the column funnels; the tabs kind; the chart and map kinds |
| `drishti_views-data.js`, `-datakinds.js` | datasets (contract 1.1) and the `grid`, `tree` and `chart` kinds that read them |
| `drishti_views-patch.js`, `-live.js` | frames, order and conflation; the stream over drishti-events |
| `drishti_views-controls.js`, `-forms.js`, `-state.js` | controls drawn with drishti-inputs; the form panel and the actions; the screen's state and effects (contract 1.2) |
| `drishti_views-element.js` | `<elements-view>` |

### The rules

- **Text or value, never guessed.** In the words, tables and figures kinds every figure is the producer's text and the painter computes and formats
  nothing. A dataset's values are raw and printed by the field's named format (drishti-core's formats, rounding half up), so a server that formats
  and a browser that formats print the same digits.
- **A tone is a word.** `pos`, `neg`, `link`, `accent`, `ok`, `warn`, `bad`, `muted`, `inactive`: the theme decides the colour and always pairs it with
  a sign or a glyph. A ViewModel never names a colour, a font or a class.
- **Failures stay local.** A panel that cannot be filled carries its state; one whose painter throws is drawn in its `error` state; the rest of the view
  is drawn. An error never blanks a view that was already painted.
- **Unknown is ignored.** A painter ignores a field it does not know, draws an unknown kind as a titled panel that says so, ignores an unknown patch,
  and refuses another major in words. Producers and painters of different minor versions work together.
- **Structure and data are separate.** Rows live in datasets, so two panels can read one dataset and a rows patch reaches both.
- **Every screen has a text alternative.** Every chart keeps a data table, closed until opened; every panel has a name; a live change is announced
  through one polite status region, and flashes stop under `prefers-reduced-motion`.

### A paint

1. Read the source (a ViewModel, an address, a `Response`, a promise of one), refuse a major the painter does not draw, validate (`warn` by default).
2. Draw the frame in one pass: the title line, the strip, the freshness line, the keys, the controls and every panel's frame in its place, each in
   its state. This first paint needs no data and no engine.
3. Draw each panel's body; a library (grid, tree, chart, graph, the chart engine) is loaded, and its sheets adopted into the view's root, the first
   time a view has a panel that needs it. Then `ready`; the stream is subscribed when the view is live.

### Live

A live view, a view that arrives in pieces and a view a page updates by hand are the same thing: a ViewModel, then **frames** of **patches** applied in
order. A frame is `{seq, generation, patches}`; the ops are `strip`, `panel`, `provenance`, `deleted`, `restored` and `rows` (a dataset's rows, by
key). A patch is addressed by identity (a strip index, a panel id, a row key), so it can be validated, conflated and explained: there is no
path-addressed "set anything".

- Frames are applied in `seq` order; a frame not newer than the last applied is dropped. Every frame that arrived since the last paint is applied
  whole, in **one animation frame**, so the reader never sees half of one; frames that arrive faster than the page paints are conflated, and a hidden
  page catches up in one paint when shown.
- A patch goes from the stream to the node that draws it through a map by identity: no validation, no deep copy and no re-layout of the view on that
  path. Validation, the builders and any mapping of a host's contract run when a view is loaded, never per patch.
- The stream is drishti-events' (the view's topic, ordered, resumed and replayed by the events protocol); a gap it cannot fill, or a new epoch, makes
  the painter read the view again. A server that speaks no protocol sends Server-Sent Events named `view` and `frame` through raw mode, and every
  reconnect of such a stream begins with a `view`. `handle.patch(frame)` is the third way, for a host's own channel.

### Security

A ViewModel may come from a server the page does not control, and frames arrive all day. The rule: **a ViewModel can make the painter draw text and
shapes and offer links and actions; it can never run code, load code, style the page, or reach an address the host did not allow.**

- Strings are written as text: the painter never assigns `innerHTML` and evaluates nothing. Markdown is built as DOM nodes by the library's own
  parser; raw HTML in it is text.
- A link's address is made safe first and a click raises a cancelable event: the host decides. An action raises an event and stops.
- The painter fetches only from the page's origin, the view's own origin and the origins the host allowed (`data.allow`).
- The validator holds a view to its bounds (panels, strip cells, nesting, inline rows) and refuses the keys `__proto__`, `constructor` and `prototype`.
- The view draws inside a shadow root by default; drawing is DOM and CSSOM, so no inline script, no inline style and no `unsafe-eval` are needed.
- Who may see what is the producer's job: a value a reader may not see is absent or masked before it is sent.

### Speed

Screens like these are used by people who act on what they see within a second, so speed is held by tests, not by intent. `tests/views_budget.json`
holds the budgets and the browser test checks each as a ratio to a calibration loop run in the same page, so a slow runner does not fail a good change:

| Budget | What it bounds |
|---|---|
| `first-paint-ms` 60 | a 12-panel view's frame drawn after its JSON arrives |
| `ready-ms` 400 | every panel drawn or in a state |
| `patch-apply-ms` 1 | one frame of strip and panel patches applied |
| `patch-to-pixel-p99-ms` 20 | from handing a frame to the painter to the next animation frame |
| `validate-200kb-ms` 15 | a 200 KB view validated under Node |

A budget is loosened only by a commit that says why. The bundle has a byte budget like every library (`tests/test_elements_budget.py`).

### A library that names no host

drishti-views contains no address, route, header name, authentication scheme, entity type, error-code prefix or settings key of any host. What a host
needs is an option (`fetch`, `links`, `data`, `host`, `messages`, `libraries`, `live`), a registered kind, or an adapter that lives in the host's own
code. The generic-library guard (`tests/test_elements_guards.py`) reads every source and style sheet of the library for domain vocabulary, and the
gallery's made-up sample data is the only place instruments and countries are named.

## 10. Packaging and versions

One distribution: the release tree `drishti-elements-<version>/` and its zip, built from a tag by `python3 tools/elements/cli.py dist` (offline, Python
only, reproducible). `cli.py pack` writes one JavaScript file and one style sheet for a page that wants two files. **One version for every library**:
`elements` in `elements/drishti_elements.json`; the bundles' headers, the loader's `ELEMENTS_VERSION`, the release tree's manifest, the catalogue and the
reference are generated from it. A released version never changes; a fault is fixed by the next patch version.

## 11. Documentation, the gallery and the checks

- **The reference** [API.md](API.md) is generated from the typed JSDoc by `python3 tools/docs/elements_api.py`; a public symbol without a typed comment
  fails the check, and so does a stale file. The same parse writes the gallery's catalogue and the AG Grid option table of USING.md.
- **The gallery** (`gallery/drishti_gallery.html`) shows every component live with its options, its events and the code to use it, in a playground and a
  documentation mode. It is static: it runs from the file system and needs no server; its stand-in data is made up. It ships in the release tree.
- **The checks** (the gate): `python3 tools/elements/build.py --check`, `python3 tools/docs/elements_api.py --check`, `python3 tools/license_headers.py`,
  `python3 tools/run_tests.py` (the Node tests and `pytest -n 8`, then the `budget` tests alone, serially). They include the byte budget of each bundle, the guard that the generic
  libraries hold no domain vocabulary, the guard that nothing loads from a CDN, one version everywhere, the licence guard of `vendor/`, and a real browser
  for every library (Chromium, under a strict Content-Security-Policy).
