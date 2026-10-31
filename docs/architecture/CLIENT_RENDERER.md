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
# The client-side renderer: one renderer, in the browser

**Status: phase 1 and phase 2 built (2026-10-30, for 2.9.7); the console's entity page draws with it behind `ui.render` (default
`server` this release); retiring the macros is the release after.** The first item of 2.9.7 (product owner, 2026-10-30), both
phases in 2.9.7 ([IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#297-future-product-owner-2026-10-28-in-progress), row 2). The decision:
[ADR-022](adr/022-one-renderer-in-the-browser.md), which supersedes the rendering part of
[ADR-021](adr/021-embedding-is-html-over-the-wire.md). The contract it draws: [VIEWMODEL.md](VIEWMODEL.md). The developer guide:
[guides/ELEMENTS.md, "Client-side renderer and Elements 2.0"](../guides/ELEMENTS.md#client-side-renderer-and-elements-20).

**In one paragraph.** Drishti Render is a JavaScript library that paints a Drishti screen in the browser from the server's
ViewModel JSON, the same JSON the console's Jinja macros paint today. It is plain ES modules with no build step, served by the
console under a versioned path, with the vendored ECharts and nothing from a CDN. A host developer adds one `<script type="module">`
and one call (`render(el, { kind: 'trade', id: 'IRS-48213' })`); the screen draws inside the host's own container, in a Shadow DOM,
follows live updates, and navigates **in place** with its own back stack. It also accepts a **streamed** ViewModel, panel by panel,
which is how GenUI's ephemeral screens will reach the browser. Phase 1 (built, 0.1.0) is the library and the evidence that it draws
exactly what the macros draw; phase 2 (built, **1.0.0**) moves the console's entity page onto it (`ui.render: client`, page by page),
brings the console's own behaviours into the library, and puts Elements 2.0 on it, so there is never a second renderer.

## Contents

1. [Goals, and the decision against ADR-021](#1-goals-and-the-decision-against-adr-021)
2. [Adoption in minutes](#2-adoption-in-minutes)
3. [The library's shape](#3-the-librarys-shape)
4. [The public API](#4-the-public-api)
5. [What it loads, and from where](#5-what-it-loads-and-from-where)
6. [Formats and tones](#6-formats-and-tones)
7. [How every panel kind maps](#7-how-every-panel-kind-maps)
8. [The layout grid, phones and phone tiers](#8-the-layout-grid-phones-and-phone-tiers)
9. [Security](#9-security)
10. [Data, identity and live patches](#10-data-identity-and-live-patches)
11. [Built for GenUI: the streamed ViewModel](#11-built-for-genui-the-streamed-viewmodel)
12. [Embeddable in place: navigation inside the container](#12-embeddable-in-place-navigation-inside-the-container)
13. [The Elements, versioning and distribution](#13-the-elements-versioning-and-distribution)
14. [Parity: the method and the results](#14-parity-the-method-and-the-results)
15. [Accessibility parity](#15-accessibility-parity)
16. [Phase 2: the console drawn by the renderer](#16-phase-2-the-console-drawn-by-the-renderer)
17. [Risks](#17-risks)
18. [Decisions for the product owner](#18-decisions-for-the-product-owner)

---

## 1. Goals, and the decision against ADR-021

**Goals.**

| # | Goal | How it is met |
|---|---|---|
| G1 | Other applications show Drishti screens while the backend stays in one place | the library renders the ViewModel the server already answers; no Drishti code in the host but one module import |
| G2 | One renderer, not two | phase 2 moves the console onto the library; the Jinja panel macros are retired then (section 16) |
| G3 | Parity first | phase 1 proves, per ViewModel, that the library draws what the macros draw (section 14) |
| G4 | Adoption in minutes | one import, one call or one tag, defaults for everything (section 2) |
| G5 | GenUI-ready | `render()` takes a static, a live or a streamed ViewModel through one paint and one patch path (section 11) |
| G6 | Embeddable in place | navigation, drills and sheets stay in the host's container unless the host says otherwise (section 12) |
| G7 | Safe under a strict policy | no markup from data, no eval, the console's CSP unchanged, vendored code only (section 9) |
| G8 | Phones | 390 px with no sideways scroll, 44 px targets, the phone order, the phone tiers hook (section 8) |

**What ADR-021 decided, and what changes.** ADR-021 chose to render a view on the console and send HTML to `<drishti-view>`, because
"a second renderer in JavaScript would be a second implementation of every panel kind and every accessibility detail, for ever". That
reason stands; its conclusion does not have to. The answer is not two renderers but **one, in the browser**: the JavaScript library
becomes the renderer, and the console moves onto it. [ADR-022](adr/022-one-renderer-in-the-browser.md) records it:

| ADR-021 | Under ADR-022 |
|---|---|
| Decision 1: views are rendered by the console and sent as HTML | **Superseded.** Views are rendered in the browser from the ViewModel by Drishti Render; the console's pages, `<drishti-view>` and `<drishti-panel>` all use it (phase 2). Until phase 2 lands, the HTML path stays as it is. |
| Decision 2: a report is drawn by the viewer's own code | Unchanged (`<drishti-report>` already draws in the browser). |
| Decision 3: the embed token is the identity on every call | Unchanged: the renderer sends the host's token on every call (section 10). |
| Decision 4: the console serves the library; versions are immutable with SRI | Unchanged and applied to the renderer: `static/render/<version>/` with `integrity.json` (section 13). |
| The sanitiser (allow-listed elements and attributes, safe URLs, one Trusted Types policy) | **Kept as rules**, applied when the DOM is built instead of after it is parsed: the library never parses markup, so the same allow-lists live in `m/dom.js` (section 9). |
| "Larger payloads (3 to 6 times before compression)" | Gone: the ViewModel is smaller than the HTML. |

## 2. Adoption in minutes

The product owner's first priority: a host developer gets a Drishti screen into their page in minutes.

**The whole quick start** (plain HTML, nothing to install):

```html
<div id="risk"></div>
<script type="module">
  import { render } from 'https://drishti.bank.example/static/render/0.1.0/drishti-render.js';
  render(document.getElementById('risk'), { kind: 'trade', id: 'IRS-48213' });
</script>
```

or the tag: `<drishti-screen kind="trade" entity="IRS-48213"></drishti-screen>` after the same import. With Elements 2.0 (section 13)
the tag is `<drishti-view>` and `<drishti-screen>` its alias.

**Defaults for everything.** A call with no options draws in a Shadow DOM with the library's own sheet, in the theme the host's
custom properties give it (`--d-*` on the container, or the default), follows the view live when the server says it is live, opens
links **in place** with a back stack, reads as the person signed in to the console (its session, on the console's own origin) or with
the host's token when `token` is given (the token exchange of [ELEMENTS.md section 8](ELEMENTS.md#8-embed-tokens-the-token-exchange)).
Every option only overrides (section 4).

**Any framework, no wrapper needed.** The library is a function and a custom element, so every framework uses it as it is:

```jsx
// React
import { render } from 'https://drishti.bank.example/static/render/0.1.0/drishti-render.js';
export function Trade({ id }) {
  const ref = useRef(null);
  useEffect(() => { const h = render(ref.current, { kind: 'trade', id }); return () => h.dispose(); }, [id]);
  return <div ref={ref} />;
}
```

```ts
// Angular (CUSTOM_ELEMENTS_SCHEMA in the component)
@Component({ selector: 'app-trade', template: '<drishti-screen kind="trade" [attr.entity]="id"></drishti-screen>', schemas: [CUSTOM_ELEMENTS_SCHEMA] })
export class TradeComponent { @Input() id = 'IRS-48213'; }
// main.ts: import 'https://drishti.bank.example/static/render/0.1.0/drishti-render.js';
```

```vue
<!-- Vue (compilerOptions.isCustomElement: (t) => t.startsWith('drishti-')) -->
<template><drishti-screen kind="trade" :entity="id" @drishti:navigate="onNavigate" /></template>
<script setup>import 'https://drishti.bank.example/static/render/0.1.0/drishti-render.js'; defineProps(['id']);</script>
```

```html
<!-- Plain HTML, the tag -->
<script type="module" src="https://drishti.bank.example/static/render/0.1.0/drishti-render.js"></script>
<drishti-screen kind="netting-set" entity="NS-NORTH-01" theme="crimson-dark"></drishti-screen>
```

A bundler that refuses URL imports copies the version's folder as it is (the npm package `@drishti/render` is laid out in section 13;
publishing it is not in 2.9.7).
Framework wrappers are optional and thin: the React sample above is the whole of one.

**Typed, versioned, stable.** `drishti-render.d.ts` beside the entry declares every option, method, event and the ViewModel's shape;
JSDoc on every export. The API is a semantic-version contract: 0.1.0 was the preview; **1.0.0** (phase 2, the console draws its own
page with it) is the first stable version, and from then on it breaks only on a major. 0.1.0 stays served, unchanged.

**Good errors.** Every failure is a `drishti:error` event and, when there is nothing to show, a box in the container with the code,
the words and the fix:

| Code | When | The fix it gives |
|---|---|---|
| `DRS-9101` | the container is not an element | Pass a DOM element, e.g. `document.getElementById("risk")` |
| `DRS-9102` | an unknown `navigation` | Leave it out for in-place, the default |
| `DRS-9103` | the source is neither a ViewModel, `{kind, id}`, `{stream}` nor a stream | `render(el, { kind: "trade", id: "IRS-48213" })` |
| `DRS-9104` | a reference without kind or id | the same |
| `DRS-9105` | a ViewModel speaks a contract major the library does not draw | Load the renderer version this server serves |
| `DRS-9106` | an unknown `behaviours` | Leave it out for the library's own, the default |
| `DRS-9107` | `behaviours: 'console'` could not load the console's scripts | Check that the console serves its static scripts, or use `behaviours: 'library'` |
| `DRS-5003` | the console could not be reached | Check the server option, the network, and that the console allows this origin (CORS) |
| the server's own (`DRS-1001`, `DRS-5002`, ...) | the view was refused | per HTTP status: sign in or give a token (401), your role may not open this kind (403), check the id (404), retry (502, 504) |

**The playground.** `/render/demo` (the static `static/render/0.1.0/demo.html`) renders any view of the console you are signed in to,
or pasted ViewModel JSON, in every theme and navigation mode, lists the events the renderer sends, and prints the quick start for what
is on screen, ready to copy.

## 3. The library's shape

| Rule | As built |
|---|---|
| Plain ES modules, no build toolchain | `drishti-render.js` imports `m/*.js`; served as they are, readable, no bundler, no transpiler |
| One entry plus CSS | `drishti-render.js`, `drishti-render.css` (generated, section 5), `drishti-render.d.ts`, `integrity.json` |
| Vendored dependencies only | ECharts 6.1.0 and ECharts GL (`static/vendor/echarts*`), the Natural Earth shapes through `static/js/geomap.js`, Bootstrap Icons' font; Arrow JS 21.1.0 through the console's `drishtiFetchTable` where a table is read (section 10) |
| No CDN, ever | a test reads every file for an outside URL (`test_render_library.py`); the parity suite fails any request off the console's origin |
| Files under 1,500 lines | the largest module is about 300 lines (2,220 in all) |

**The modules** (`drishti-console/web/static/render/0.1.0/`):

| Module | What it holds |
|---|---|
| `drishti-render.js` | the entry: `render`, `patch`, `subscribe`, `VERSION`, `CONTRACTS`, `KINDS`, the `Handle`, `<drishti-screen>`, `window.DrishtiRender` |
| `m/dom.js` | the only way DOM is made: `h()`, the attribute allow-list, safe URLs (section 9) |
| `m/format.js` | the tones, Python's rounding and float printing, axis ticks, shares, heat buckets (section 6) |
| `m/cells.js` | cells, fields, entity links (the macros `cell_text`, `tone`, `fields`, `entity`) |
| `m/tables.js` | tables, trees, drill-downs, the Table/Pivot switch, the pivot kind |
| `m/kinds.js` | the panel frame, its states and every other kind |
| `m/charts.js`, `m/draw.js` | the ECharts options per chart kind (ported from `view.js` and `charts.js`), drawing, resizing, clicks |
| `m/behave.js` | widths, tabs, tree rows, drill levels, sorting, filtering, paging, row and bar events |
| `m/grid.js` | the screen grid: `arrange` (a port of `core/screen_grid.py`) and the picture, phone order, tabs, folds and split of `screen-grid.js` |
| `m/view.js` | the banners, the head (title and strip), the panels in their places, the optional function keys |
| `m/screen.js` | one screen in one container: paint, patch, stream messages, in-place navigation and its back stack, events |
| `m/net.js` | fetching a ViewModel and opening its stream, as the person, with problems that carry a DRS code and a fix |
| `m/assets.js` | the sheet, the icon font, ECharts and the other vendored scripts, the `drishti-render` Trusted Types policy |
| `m/console.js` (1.0.0) | `behaviours: 'console'`: the console's own scripts (funnels and Chart any table, the Pivot grid, the nested-pivot grid, the drill-down, zoom, the controls), loaded from beside the library and started on the screen's root; the person's fetch for the Pivot tab's rows |
| `m/about.js` (1.0.0) | About this page drawn from the explanation's JSON (a port of `_macros/about.html`): the console's drawer body on the pages the library draws, and the library's own side sheet (`about: true`) |
| `m/elements.js`, `drishti-elements.js` (1.0.0) | Elements 2.0: `<drishti-view>`, `<drishti-panel>`, `<drishti-screen>` on `render()`, the token provider and one live connection per page (section 13) |

**Size.** 0.1.0: JavaScript 135 KB, **49 KB gzip** across 14 modules. 1.0.0: 184 KB, **67 KB gzip** across 19 files with Elements 2.0
(the renderer alone, without `m/elements.js` and `drishti-elements.js`, about 57 KB gzip); the sheet 149 KB, **27 KB gzip** in both. ECharts (1 MB, 330 KB gzip) is
loaded only when a screen has a chart, and only once per page; a host page that already has `window.echarts` uses its own.

## 4. The public API

```js
const view = render(container, source, options);   // returns at once; view.ready settles after the first paint
await view.ready;
view.patch(frame);              // a live frame {seq, generation, patches} (VIEWMODEL.md section 5)
view.apply(message);            // a stream message (section 11)
const stop = view.subscribe();  // follow the view's stream (default: this console's relay), returns stop()
view.navigate({ kind: 'counterparty', id: 'CP-NORTHBRIDGE' });   // as a click would
view.back();  view.depth;       // the in-place back stack
view.setTheme('crimson-dark');  view.reload();  view.dispose();
view.viewModel;                 // the ViewModel shown now, patches applied
view.subscribe((on) => myChannel.subscribe(key, on));     // 1.0.0: the host's own channel (on.frame, on.view, on.gone); returns stop()
view.drawPanel(panelView);  view.drawStrip(cells);  view.drawAbout({ explain, index });   // 1.0.0: parts for a host that places them
view.drawCharts(scope);  view.about();                     // 1.0.0: charts of what the host placed; the About sheet (about: true)
patch(view, frame); subscribe(view, source);         // the same as functions
```

**`source`** is one of: a ViewModel (the JSON of `GET /api/v1/views/{kind}/{id}`); `{kind, id, controls?}` (fetched as the person);
`{stream: url}` (a Server-Sent Events stream of whole views, frames and stream messages); or an async iterable of stream messages.

**`options`** (every one has a default):

| Option | Default | Meaning |
|---|---|---|
| `shadow` | `true` | draw in a Shadow DOM with the library's sheet; `false` draws in the container and the host's sheets style it (the console's own pages, phase 2) |
| `theme` | `'inherit'` | a console theme (`light`, `terminal`, `blue`, `green`, `crimson`, `crimson-dark`, `wallstreet`, `terminal-blue`, `terminal-green`, `terminal-crimson`) or the host's custom properties |
| `server` | `''` | the console that serves data and links; `''` is this page's origin |
| `navigation` | `'in-place'` | `'in-place'`, `'host'` (events only) or `'new-window'` (section 12) |
| `consoleLinks`, `outsideLinks` | `'event'` | a link to a console page (help, designer, connector) or to a page outside Drishti: an event (default), `'follow'` or `'new-window'` |
| `live`, `subscribe` | `true`, off | follow a live view; for a ViewModel source, subscribe at once (`true` or an SSE URL) |
| `token`, `credentials` | none, `same-origin` | a bearer token provider; fetch credentials (`include` with a `server`) |
| `device` | none | `phone`, `tablet` or `desk`, sent as `X-Drishti-Device` (section 8) |
| `viewUrl`, `streamUrl`, `eventSource` | the console's | when the host proxies the console, or needs its own EventSource |
| `designOn` | `false` | "How this view was built" links its Sutra to the designer (the console sets it by lens: `design_on()`) |
| `behaviours` (1.0.0) | `'library'` | `'library'`: the library's light ones (sorting, one filter box, paging, trees, drills, tabs); `'console'`: the console's own (per-column funnels, Chart any table, the Pivot tab's grid, the nested-pivot grid, the drill-down, zoom, the controls), loaded and started inside the container; `'host'`: none but the widths, the host page's scripts enhance the DOM (the console's own page) |
| `about` (1.0.0) | `false` | an About button in the head and `?`: About this page drawn from JSON in a side sheet inside the container |
| `navigation: 'follow'` (1.0.0) | | a link is a link: `drishti:navigate` first (cancel it to stay), then the browser follows it, so Ctrl+click and "open in a new tab" work (the console's own page) |
| `viewUrl`, `aboutUrl`, `recordsUrl`, `panelsUrl` (1.0.0) | the console's | where the view, About, a pivot's rows and a control's panels come from (Elements 2.0 points them at the embed API v2) |
| `chrome`, `bare`, `grid`, `backToLive`, `beforeCharts`, `onReplace` (1.0.0) | off | for the console as host: its per-panel affordances (download menu, the wiring, refresh, provenance, forms, computing), drawing into its own `.view`, the grid with the reader's own arrangement, the "Back to live" link, charts after its layout script, and what it keeps in a panel through a live tick |
| `tables`, `help`, `keys`, `header`, `panels`, `label`, `asOf` | `true`, `true`, `false`, `'full'`, all, the title, live | table behaviours, the `?` help links, the function keys, the head, a subset of panels, the region's name, the as-of banners |

**Themes through custom properties.** The sheet is the console's own tokens (`--d-ink`, `--d-surface`, `--d-link`, `--d-accent`,
`--d-pos`, `--d-neg`, `--d-ok`, `--d-warn`, `--d-bad`, `--d-border`, `--d-font-mono`, ...), rewritten for a shadow root. `theme` sets
`data-theme` on the container; a host restyles by setting the same properties on the container (`#risk { --d-accent: #0b5cad }`), which
win over the sheet's `:host` defaults. Charts read the tokens when they draw, so `setTheme()` redraws them.

**Events out** (on the container; they bubble, cross the shadow boundary and are cancelable where the host may take over):

| Event | Detail | Cancel to |
|---|---|---|
| `drishti:navigate` | `{target: 'entity'\|'console'\|'outside', kind, id, href, panel, source: 'link'\|'chart'\|'key'\|'api', navigation}` | route it yourself (the renderer does nothing) |
| `drishti:row-click` | `{panel, event, row, pick, cells}` for a row or bar that carries an event | keep the row unselected |
| `drishti:select` | `{panel, control, value}` for a row that sets a control (`selects`) | |
| `drishti:paint`, `drishti:loaded`, `drishti:ready` | the reference, generation, back-stack depth; `ready` when a streamed screen is complete | |
| `drishti:tick`, `drishti:deleted`, `drishti:restored` | what a live frame changed | |
| `drishti:error` | `{code, detail, fix, status}` | |
| `drishti:pivot`, `drishti:key` | a Pivot tab opened; a function key with no in-place action (raw, impact) | draw it yourself |
| `drishti:panel`, `drishti:about` (1.0.0) | a panel replaced by a live frame `{panel, element}`; the About sheet opened `{open, ref}` | |

## 5. What it loads, and from where

Everything comes from the console that serves the library, found from the module's own URL (`import.meta.url`): the sheet
`drishti-render.css`, the icon font `static/vendor/bootstrap-icons/fonts/bootstrap-icons.woff2` (registered with the `FontFace` API,
because an `@font-face` inside a shadow root is not used), `static/vendor/echarts/echarts.min.js` on the first chart,
`static/vendor/echarts-gl/echarts-gl.min.js` on the first 3D surface, and `static/js/geomap.js` (which fetches the Natural Earth shapes
from beside itself; one line of it changed so it finds them on a console that is not the page's origin).

**The sheet** is generated by `tools/render_sheet.py` (`core/render_dist.py`) from the console's own sheets, in the order the view page
loads them (tokens, theme, terminal, layout, gradients, pivot, colfilter, controls, about, touch, drill, wire, refresh, compute,
screen-grid), with Elements' rewriting (`:root`, `html` and `body` become `:host`, width media queries become container queries on the
host, so a screen answers to its container's width, not the window's), the glyphs the macros and modules use, and a short addendum
(box sizing and `[hidden]`, which the console gets from Bootstrap's reboot; the sort buttons, the pager, the back bar; 44 px targets
below 640 px and on coarse pointers). It is adopted as a constructable sheet, so a host with `style-src 'self'` needs nothing more.

## 6. Formats and tones

The server formats every value a person reads (VIEWMODEL.md, "Formatted text plus a tone"); the renderer formats nothing of the
data. It computes only what the macros compute, and does it the way Python and Jinja do, so both print the same characters:

| What | Rule (`m/format.js`) |
|---|---|
| A bar's width, a gauge's level, a donut's share, a heat bucket, a progress percentage | Python's `round()` (half to even, on the double's exact decimal digits; `pyRound`) |
| The few raw numbers a chart's Data table prints (candle prices and volumes) | Python's `str(float)`: `74.0`, `1e+17`, `1e-05` (`pyFloat`) |
| A tone | the word becomes the class `t-<tone>`; the nine tones of the contract are drawn, any other word is written as a class the sheet ignores (the macros' behaviour), so the value is untoned |
| Axis ticks | the console's own (`compact`: 1.75m, 250k, −1.2bn; the line chart keeps its slightly different one) |

A finding for the contract: the candlestick's Data table prints raw numbers (`74.0`), the only place a client must format a number
the server did not. Proposed for the ViewModel's next minor: `openText`, `highText`, ... beside the numbers, as points have `xText`.

## 7. How every panel kind maps

### 7.1 The kinds

All 22 kinds of [VIEWMODEL.md section 4](VIEWMODEL.md#4-panels) are ported, each from its macro in `_macros/panels.html`:

| Kind | Macro | Library (`m/`) | Drawn by |
|---|---|---|---|
| `kv`, `status` | `fields` | `cells.fields` (four columns in main, two in right) | DOM |
| `provenance` | inline `dl.prov` | `kinds.provenance`: the Sutra links to the designer only when `designOn` (the lens-aware `design_on()` rule, passed in), the connector to its page | DOM |
| `table`, `ladder` | `pivotable`, `table`, `tree_rows`, `drill` | `tables.pivotable`, `table` (plain or tree), `drill` | DOM |
| `tabs` | `tabs`, `tab_inner` | `kinds.tabs` (tabs or columns; inner panels through the same body) | DOM |
| `line`, `area` | `chart` | `kinds.chart` + `charts.line` (the options of `view.js`) | ECharts |
| `hbar` | `bars`, `donut` | `kinds.bars`, `kinds.donut` + `charts.donut` | DOM, ECharts |
| `surface` | `surface` | `kinds.surface` + `charts.surface` (heatmap; 3D with ECharts GL, the heatmap when WebGL fails) | ECharts |
| `links` | `links` | `kinds.links` (every status and badge) | DOM |
| `markdown` | `<p>` | text, never markup | DOM |
| `waterfall`, `histogram`, `scatter`, `candlestick`, `graph` | one each, with a Data table | `kinds.*` + `charts.*` (the options of `charts.js`) | ECharts |
| `timeline` | `timeline` | `kinds.timeline` | DOM |
| `map` | `geomap` | `kinds.geomap` + `charts.drawMap` (shapes through `geomap.js`; the clickable places as a list of links) | ECharts |
| `pivot` | `pivot`, `pivot_table` | `tables.pivot` (heat buckets, totals, more; nested rows drawn as the plain table) | DOM |
| `metric` | inline | `kinds.metric` | DOM |
| `gauge` | inline | `kinds.gauge` | DOM |
| a kind the client does not know | (nothing) | a titled panel that says "This panel kind is not supported by this client (sankey)" (VIEWMODEL.md section 8) | DOM |

### 7.2 The states

In the macros' order: `wire.waits` (its words, `pnl-wait`), then `empty` or no data: `denied` (the four texts: no access to a kind,
hidden for you, not available to your role, not active), else `emptyLabel` or "No data available" ("No linked entities" for links),
with "The data did not have the shape this panel expects" under an `error`; then the kind. A computed panel (`compute`) shows its state
first: computing with its progress, stale, failed with the connector and the request, refused, each over the last result when
`hasResult`. A panel streamed in a skeleton and not yet bound says "Loading…" (section 11).

### 7.3 Charts

The options are the console's, ported line for line, so the same data gives the same series and points (the parity suite compares the
ECharts option, section 14). Colours are the theme's tokens, read from the rendered view. A chart that cannot be drawn says "No data
available" and leaves the others; every chart keeps its Data table (or Entities, or the places), so nothing is only in the picture. A
click on a point, a node or a place that names an entity opens it in place.

### 7.4 Behaviours

| Behaviour | Console today | Library |
|---|---|---|
| bar and gauge widths | `view.js` (`data-w`, CSSOM) | `behave.widths`, the same |
| tabs | `view.js` (click) | click, and arrows, Home and End |
| tree rows | `tree-rows.js` | open and close, the filter box keeping ancestors |
| drill-down | `drill.js` (levels, crumbs, Esc; the address `?dr.<panel>=` and Back) | levels, crumbs, Up and Esc **in place**: the host's address never changes |
| sorting, filtering, paging | `tables.js` + `colfilter.js` (funnels, per-column filters, the chart button, the address) | `'library'`: heading buttons (ascending, descending, the server's order, the console's value rules), one filter box, 25 a page with a pager; **`'console'` (1.0.0): the console's funnels, per-column filters and Chart any table** (the chart dialog opens over the host's page, never a window) |
| the Pivot tab | `pivot.js` (1,000 lines, its records through the panel's records URL) | `'library'`: the switch and a `drishti:pivot` event; **`'console'`: the console's grid** (`pivot-engine.js`, `pivot-grid.js`, `pivot.js`), its rows read as the person (`recordsUrl`; the embed API v2 for Elements 2.0); saving an arrangement stays a console feature |
| nested pivot rows | `tree-rows.js` redraws them with the Pivot grid | the macro's plain table; **`'console'`: the Pivot grid** (`tree-rows.js` from `data-pv-tree`) |
| the drill's address | `drill.js` keeps `?dr.<panel>=` and Back | the console's page: the same (`behaviours: 'host'`); another host: `drill.js` in place, the host's address untouched (`address: false`) |
| About this page | `about.js` fetches the drawer's HTML | **drawn by the library from JSON** (`?format=json`, `m/about.js`): the console's drawer on its client pages, a side sheet in the container elsewhere |
| row and bar events (`ev`, `pick`) | `sutra-wire.js`, `controls.js` | `drishti:row-click` and `drishti:select`; the host (or phase 2's port of `sutra-wire.js`) asks the server for the panels that follow |
| function keys | the console's footer | `keys: true` draws them under the view; panel and link keys act in place, the rest are `drishti:key` events |

## 8. The layout grid, phones and phone tiers

Without a `layout` the panels flow in two columns, main and right, sized by `span` (1 to 12) and `height` (grid rows), as
`terminal/view.html` does. With a Sutra `layout:` ([SUTRA_NEXT.md section 13](SUTRA_NEXT.md#13-layout-placing-panels)) `m/grid.js`
ports `core/screen_grid.py`'s `arrange` (areas in the picture, `order`, sections, collapsible panels, the split, the phone order) and
`screen-grid.js`'s drawing: the picture's template set through CSSOM (no style attributes), the tablet picture (the right column under
the main one), tabs in a tabbed area, folding sections and panels, and the split's divider (drag, or arrows) without saving. The
reader's own arrangement (Customize, `user-can-arrange`) stays a console feature.

**Phones.** Below 640 px of the **container** (container queries), every grid is one column in the phone order, gutters shrink,
tables scroll inside themselves, and no screen scrolls sideways; the parity suite checks 390 and 1280 px in every theme. Targets are
44 px on a narrow container and on any coarse pointer.

**The phone tiers hook** ([MOBILE_DISPLAY.md](MOBILE_DISPLAY.md)). Designed for now, built when tiers are: the renderer sends
`X-Drishti-Device` when the host gives `device` (the server will then bind only must and glance panels); a panel's resolved tier
(`phone: {tier, from}`) is written as `data-tier` on its frame; a panel the server sends as a stub (more, desk) is a panel with `wire.waits`
today ("Loading…") and becomes, with tiers, a row of "More on this page" that loads `GET …/panels/<id>` on tap through the same
`replacePanel` a stream uses (section 11). Nothing in the API changes for it.

## 9. Security

| Rule | How it holds |
|---|---|
| No markup from data | the renderer never parses a string as HTML: elements by `createElement`, text by text nodes, attributes by `setAttribute` through an allow-list (`m/dom.js`: the macros' attributes, `data-*`, `aria-*`; never `on*`, `style`, `src`, `srcdoc`, `formaction`) |
| No unsafe link | an `href` is set only when it is a path on this origin, a query, a fragment or `http(s)://` (control characters stripped first): `javascript:`, `data:`, `vbscript:` and `//host` are dropped. Entity links are built from `kind` and `id`, each URL-encoded |
| No eval | no `eval`, `new Function`, string timers, `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` or `createContextualFragment` anywhere in the library (a test reads the sources) |
| The same CSP | the console's `default-src 'self'; script-src 'self'; style-src 'self'; ...` unchanged: styles are a constructable sheet or CSSOM properties, scripts are files of this console |
| Trusted Types | one policy, `drishti-render`, for script URLs only, accepting this console's `/static/` and nothing else; with no markup there is nothing for `createHTML` to do |
| Vendored libraries' paths | **ECharts**: one `new Function`, in the GeoJSON loader when a map is given as a string and `JSON.parse` is missing: never reached (geomap.js passes objects). Its tooltips are written as markup by ECharts itself: every formatter in `m/charts.js` escapes what it prints. Its SVG painter empties nodes with `innerHTML = ''` on dispose, which is not data. **ECharts GL**: patched (no `new Function`, `vendor/echarts-gl/PATCHED.md`). **Arrow JS**: one `new Function`, in building vectors with `nullValues`, never reached by reading (`VENDOR.md`) |
| Masks and roles | decided on the server, per viewer, before the JSON leaves (VIEWMODEL.md section 6); the renderer cannot unmask and needs no rule to hide |

What ADR-021's sanitiser did after parsing (rebuild from allow-lists, drop unsafe URLs, one Trusted Types policy) the renderer does
while building, with the same lists; the sanitiser stays for the HTML path until phase 2 retires it. A hostile ViewModel (markup and
script in titles, strip, fields, panel ids, links whose kind is `javascript:`) draws as text, with no element, handler or script URL
from it (`test_a_hostile_view_model_draws_text_only`).

## 10. Data, identity and live patches

**Where a ViewModel comes from.** `{kind, id}` is fetched from the console's `GET /api/view/{kind}/{id}` (the person's session) or from
`options.viewUrl(ref)`; with `token` the request carries `Authorization: Bearer` (the token exchange of ELEMENTS.md section 8: the
host's backend exchanges its user for a short-lived token; every call is verified and masked as that user). Phase 2 adds
`GET /embed/v1/views/{kind}/{id}?format=viewmodel` for cross-origin hosts, so the embed API's CORS allow-list, token and usage counts
apply unchanged. Controls travel as `ctl.<id>` parameters; the business date as `X-Drishti-As-Of`.

**Tables through `drishtiFetchTable`.** The renderer reads no table of its own today: every panel's rows are in the ViewModel. The one
place a panel fetches rows, the Pivot tab (`…/panels/{panel}/records`), is read with the console's `drishtiFetchTable` (Arrow when the
vendored Arrow JS is on the page, JSON otherwise; [DATA_FORMATS.md section 6](../guides/DATA_FORMATS.md#6-arrow-to-the-browser-where-it-pays)),
when phase 2 ports it.

**Live patches.** `patch(frame)` applies VIEWMODEL.md section 5 exactly: in list order, `strip` (the cell rebuilt, marked `live-cell`),
`panel` (the panel rebuilt and re-enhanced, its grid place, phone order, tab state and fold kept; charts redrawn), `provenance` (the
banners rebuilt), `deleted` (the view greyed under a banner, later patches ignored), `restored` (the view fetched again). An op the
client does not know is ignored. The structure never changes through a patch.

**The stream.** `subscribe()` opens an `EventSource` on the console's `GET /api/stream/{kind}/{id}?raw=1`. `raw=1` (new, one parameter
on an existing route) relays the server's own events unchanged: the whole ViewModel, then frames whose panel patches are panels; without
it the route answers as before (panel HTML for the console's pages). A second `view` event (a reconnect, a newer generation) repaints;
frames patch. A host that needs headers on the stream gives `eventSource(url)`.

### 10.1 Signing in on another site: the token exchange, and an optional sign-in window (design, product owner 2026-10-30)

**The default stays silent.** A host with a backend (a portal, a CRM, a Java, Python or Node application) uses the token exchange
([ELEMENTS.md](../guides/ELEMENTS.md#how-a-user-gets-in-the-token-exchange)): its server vouches for its signed-in user and passes the
renderer a short-lived token through `options.token`. The person signs in once, to the host; no window opens.

**The option: `auth: 'popup'`** (not built; planned with the renderer follow-ups). For a host **without a backend of its own** (a static
intranet page, a SharePoint page, a team dashboard), which cannot run the exchange:

```js
render(el, { kind: 'trade', id: '1000001', auth: 'popup' })      // 'token' (default when options.token is given) | 'popup' | 'session'
```

1. **Never automatic.** Without a token, the renderer draws a **Sign in to see this** button in place (with the product's name and why),
   never a window on page load: browsers block windows not opened by a click, and an unasked window breaks embed-in-place (section 12).
2. **One small window, on that click,** to the bank's single sign-on: OpenID Connect with PKCE, through the server's existing OIDC
   settings (`drishti.security.oidc`). Already signed in to SSO, the window opens and closes at once with nothing to type.
3. **The token comes back to the page** by `postMessage` to the exact embedding origin (checked both ways), is kept in memory only (never
   in storage or a cookie), and the screen is drawn in place. It is refreshed in the same way, silently while the SSO session lasts; when
   it cannot be, the button comes back.
4. **Only registered hosts.** The embedding origin must be on the embed allow-list (`drishti.embed`); any other site gets the button
   disabled with `DRS-5011`-style text naming the origin, and no window.
5. **Events for the host:** `drishti:auth-required` (the button is shown; a host may call its own login instead and pass a token),
   `drishti:signed-in`, `drishti:signed-out`, so a host can take over at any point.
6. **On phones and in embedded webviews** the window is a new tab that returns to the page; the same flow.
7. **Audit:** sign-ins through the window are recorded as such (the host origin, the user, the time), like the token exchange.

**Inside Drishti's own console** nothing changes: `auth: 'session'` uses the console's sign-in.

## 11. Built for GenUI: the streamed ViewModel

The product owner's second priority: an **ephemeral Sutra**, crafted on the server by AI or a person, is bound into a ViewModel and
**streamed** to the client, so the screen appears panel by panel. The renderer is built for it now; the AI is not
([GENUI.md](GENUI.md); dynamic screens, [SUTRA_NEXT.md section 12](SUTRA_NEXT.md#12-dynamic-screens), `/s/<name>`).

**One path for three sources.** `render()` takes a static ViewModel, a live one (`subscribe`) and a streamed one (`{stream: url}` or an
async iterable) through the **same** paint and the same patch: a streamed screen is a skeleton painted like any ViewModel, then panel
replacements exactly like live `panel` patches.

**The messages** (Server-Sent Events: the SSE event name is the type, or the default `message` event with a `type` field; the same
objects over the console's channel):

| Type | Payload | The renderer | In VIEWMODEL.md terms |
|---|---|---|---|
| `skeleton` | `{view}`: a ViewModel whose panels are stubs (`id`, `kind`, `title`, `area`, `span`, `height`, and the layout) | paints the final layout at once, each stub saying "Loading…" (`aria-busy` on the view), so nothing jumps | a ViewModel with `wire.waits` on every panel |
| `panel-add` | `{panel, after?}` | a panel the skeleton did not announce, after `after` (or at the end of its column; a grid lays out again) | new: structure grows, which live patches never do |
| `panel-replace` | `{panel}` | replaces the stub (or an earlier panel) with the bound panel | a `panel` patch |
| `patch` | `{frame}` | a live frame, as `patch()` | section 5's frame |
| `done` | `{provenance?}` | the screen is complete: `aria-busy` cleared, `drishti:ready` | the first `view`'s provenance |
| `error` | `{code, detail, fix?, panel?}` | a panel that could not bind shows its error; without `panel`, the screen does | a panel `error`, or a Problem |

GENUI.md section 9.2 maps onto these one for one (`skeleton` → `skeleton`, `panel` → `panel-replace`, `view` → `done`, `frame` → `patch`,
`gone` → `error`), with one change under ADR-022: the stream carries **panels as JSON**, not the console's HTML (GENUI.md 9.1 and 9.5
assumed ADR-021). Masks hold the same: every panel is bound as the viewer on the server; the client trusts nothing in the stream but
draws it as text.

**Every link, drill, event and action stays intact** on an ephemeral screen, because they are in the ViewModel, not in the page: a
row's entity link opens the entity in place; a drill opens its child rows; a click that fires an event (`ev`, `@panel.event`) becomes
`drishti:row-click` and, with the server's event route, re-binds only the panels that follow it, against the same screen id; provenance
and About name the ephemeral Sutra. **What the server must give an ephemeral screen** (GENUI.md section 7): an id (`g_…`) that links,
drills, events and About resolve against (`/api/v1/genui/{id}/…` beside `/views/{kind}/{id}/…`), a lifetime (`expires`, the planned `410 DRS-2051`
after it), the viewer's identity on every call, and the contract version. None of it changes the renderer's API.

**Tested in phase 1.** `test_a_streamed_view_model_ends_equal_to_a_one_shot_render` streams a skeleton, every panel in reverse order,
two panels the skeleton did not announce and `done` into `render()`, for the showcase (flow), a Sutra grid with areas, tabs and a split,
and every panel state; the end state equals a one-shot render in the accessible tree, the text, the cells, the links, the tones and
every chart's series.

## 12. Embeddable in place: navigation inside the container

The product owner's third priority: nothing opens a new window or leaves the host's page unless the host wants it.

| Mode (`navigation`) | An entity link, a chart point, a map place, a link key | The host's page |
|---|---|---|
| `'in-place'` (default) | `drishti:navigate` first; if not cancelled, the entity's view is fetched and drawn **in the same container**, the previous screen pushed on the renderer's own back stack; a Back bar ("← Back to IRS-48213") and Alt+← return | never navigates, its address never changes |
| `'host'` | only `drishti:navigate`: the host routes it (its own router, a deep link into its URL, another container) | as the host decides |
| `'new-window'` | `drishti:navigate`, then `window.open(href)` | a new window, because the host asked |

- **Drill-downs, tree rows, tabs, sorting, sections and folds** happen in place, with no address change (the console's drill keeps
  `?dr.<panel>=` in its own address; the renderer does not touch the host's).
- **Links to console pages** (the `?` help, the designer, a connector's page) and **links outside Drishti** (a bare `https://` value in
  the data) cannot be drawn in place: by default they are only `drishti:navigate` events (`target: 'console'` or `'outside'`), and the
  host decides; `consoleLinks` / `outsideLinks` set `'follow'` or `'new-window'` per host.
- **About and detail sheets** render inside the container's shadow root, never over the host's page: About comes as JSON and is drawn
  by the renderer as a side sheet within the container (`about: true`, 1.0.0).
- `window.open` is never called in `'in-place'` mode; a test counts it.
- **The console is one host** (phase 2, built): its page uses `navigation: 'follow'`, `consoleLinks: 'follow'` and
  `outsideLinks: 'new-window'`, so a link is a link (Ctrl+click opens a tab), the address, Back and breadcrumbs behave as today, and an
  outside URL opens in a new tab as the macros' does.
- **Other hosts keep "event only"** for console pages and outside URLs (the product owner's decision 2): `consoleLinks` / `outsideLinks`
  open them in a new tab when the host asks (`'new-window'`) or follow them (`'follow'`).

## 13. The Elements, versioning and distribution

**Versions.** One folder per version: `drishti-console/web/static/render/<version>/`: **0.1.0** (the preview, unchanged) and **1.0.0**
(phase 2). A released folder is immutable: `integrity.json` holds the SHA-384 (subresource integrity) of every distributed file, and
`tests/test_render_library.py` and `test_render_phase2.py` fail when a file of any version changed without a new record, so a change means
a new folder and a new version (`tools/render_sheet.py` writes the current version's sheet and record). The playground (`demo.html`,
`demo.js`, `demo.css`) is served beside it but is not part of the record.

**The final paths** (decided in phase 2; ELEMENTS.md section 19):

| Path | What | Cache |
|---|---|---|
| `/static/render/<version>/…` | the library on the console's own origin (the console's pages, same-origin hosts) | as `/static/` |
| `/embed/v2/render/<version>/…` | the same files for other origins: CORS for the origins of the registered host applications; a partial version (`1`, `1.0`) answers `302` to the exact one; a version this console does not serve answers `410 DRS-8006` | `immutable`, one year |
| `/embed/v2/elements/<2, 2.0, 2.0.0>/drishti-elements.js` | Elements 2.x by its own version: a `302` to `/embed/v2/render/<renderer>/drishti-elements.js` (Elements 2.0.0 is carried by renderer 1.0.0) | 60 s |
| `/embed/v2/js/<name>.js`, `/embed/v2/vendor/…`, `/embed/v2/css/table-chart.css` | what the library loads from beside itself (it finds `/embed/v2/` from its own URL as it finds `/static/` on the console): the console's behaviour scripts, ECharts and ECharts GL, the map shapes, the icon font, the chart dialog's sheet; allow-listed | revalidated |
| `/embed/v2/views/{kind}/{id}` (+ `/about`, `/panels`, `/panels/{panel}/records`), `/embed/v2/channel`, `/embed/v2/resolve` | the embed API v2: the ViewModel as JSON (with its contract), About and panels as JSON, a pivot's rows, the live channel (`vm:` subscriptions: panels as JSON) | `no-store` |
| `/embed/v1/…` | Elements 1.x and the embed API v1 (HTML), **unchanged**, beside v2 for one release (`embed.lifecycle."1"` announces its end) | as before |

The phase 1 plan named `/embed/v1/render/<version>/`; it is not built: the renderer belongs to the API that answers it JSON, v2, and v1
stays exactly as hosts use it.

**The npm package** (product owner's decision 4: documented, not published in 2.9.7). `@drishti/render`, the version's folder as it is:
`drishti-render.js`, `drishti-elements.js`, `m/*.js`, `drishti-render.css`, `drishti-render.d.ts`, `integrity.json`, plus `package.json`
(`"type": "module"`, `"exports": {".": "./drishti-render.js", "./elements": "./drishti-elements.js", "./drishti-render.css": ...}`,
`"types": "./drishti-render.d.ts"`) and the licence. The same bytes as `/embed/v2/render/<version>/`; a host that bundles it must also
serve the console's `js/`, `vendor/` and `css/` files beside it, or set `server` so the library loads them from the console.

**Elements 2.0** (product owner's decision 3: beside 1.x for one release, 1.x unchanged). `drishti-elements.js` in the renderer's folder
defines `<drishti-view>`, `<drishti-panel>` and `<drishti-screen>` (an alias of `<drishti-view>`, decision 5) on `render()`: the ViewModel
from `/embed/v2/views/{kind}/{id}` with the host's bearer token, `behaviours: 'console'`, `about: true`, live frames over one connection
per page (`/embed/v2/channel`, a `vm:` subscription whose panel patches are panels), and Elements 1.x's API: the attributes (`kind`,
`entity`, `as-of`, `known-at`, `theme`, `density`, `header`, `live`, `server`, `panels`, `panel`, `label`, `controls`, and `navigation`),
`DrishtiElements.configure({ tokenProvider })` and `resolve()`, `reload()` and `refresh()`, `state`, `view`, `lastError`, the
`drishti:*` events (`state`, `loaded`, `paint`, `tick`, `navigate`, `error`, `masked`, `token-needed`) and the `loading`, `empty` and
`error` slots. A link is a `drishti:navigate` the host may cancel; otherwise the element shows that entity (its `kind` and `entity` change,
as 1.x); `navigation="in-place"` draws it in place with a Back bar instead. `<drishti-report>` is unchanged (1.x). A page uses one
major or the other.

## 14. Parity: the method and the results

**The method** (`tests/test_render_parity_browser.py`, `render_harness.py`, `render_cases.py`; Playwright and Chromium in the console's
venv; no server and no port for the first three groups). Each ViewModel is drawn two ways: (a) the console's server HTML, by the
console's own macros (`_macros/view.html` banners and head, `panels.html`, `terminal/_grid.html`) on a page with the console's sheets and
its first-paint scripts (`view.js`, `charts.js`, `geomap.js`, `screen-grid.js`), and (b) the library into a blank page. Playwright serves
both from one made-up origin by request interception, with the console's Content-Security-Policy on every answer. Compared:

| Compared | How |
|---|---|
| the accessible tree | `aria_snapshot()` of the view: roles, names, headings, levels, states |
| the visible text | `innerText` line by line |
| every cell | the text of every visible `td` and `th`, in order |
| every link and its target | text and resolved `href` of every visible link |
| every tone | each element's `t-*` class with its text |
| every chart | the series and points of each chart's ECharts option (type, name, data), not pixels |
| the page | no sideways scroll at 390 and 1280 px; no CSP violation; no request off the console's origin; no script error |

**The ViewModels.** The captured views (`tests/fixtures/view_*.json`: four trades and a netting set); the all-panels showcase (all 22
kinds) in **all 11 themes** at both widths; seven edge-case ViewModels built from VIEWMODEL.md (every kind empty; error, the four denied
texts, waits, the computing/stale/failed/refused states, a missing `data`, an unknown kind; every link and tone: entity, inactive,
outside URL, title `with`, every links status; a masked view with the partial and stale banners; shapes the showcase lacks: a drill
table, tabs as columns with inner panels, a donut, bars and rows with events, a stepped line with mark, source and limit, a heatmap, a
region map with shading and links, grouped and sized points, candles without volume and with extreme numbers, a force graph, a heat
pivot, a long table; a Sutra grid with areas, tabs, a split and a sticky area; the flow grid with sections) in two themes at both
widths; the showcase with the designer off and in light DOM; and **every Sutra of every shipped pack**, bound on its own sample
document by a real server (`POST /api/v1/studio/preview`, the server jar run by `wb_live.py`) plus the documented examples, at both
widths.

**The results (2026-10-30):** 476 pairs, no difference but the intentional ones ([section 14.2](#142-results)).

### 14.1 Intentional differences

| Difference | Why |
|---|---|
| An unknown kind says "This panel kind is not supported by this client (…)"; the macros draw an empty body | VIEWMODEL.md section 8 asks a client to say so |
| A panel header in a tabbed grid area is a `banner` landmark on the console page and generic in the library | the console's script turns the section into a tabpanel after parsing and Chromium then exposes the header as a banner; the library sets the role before the panel is in the page, and HTML-AAM says a header inside a section is generic |
| An outside URL in the data: the console opens it in a new tab (`target="_blank"`); the library sends `drishti:navigate` | in place by default (section 12); `outsideLinks: 'new-window'` gives the console's behaviour |
| Console-only affordances: the download menu, Customize, the arrange handles, the pivot's saved arrangements, the address state of drills and filters | console features, not part of a screen; kept by the console when it is the host (phase 2) |
| A nested pivot is the plain table, and the Pivot tab's grid is not built by the library | `pivot.js`, `pivot-grid.js` and `tree-rows.js`'s pivot redraw are phase 2 (section 7.4); the parity page draws neither, the console page with them redraws the same rows as a collapsible grid |
| Tables sort with heading buttons, one filter box and a pager, not the console's per-column funnels and chart button | phase 1 ports the sort rules exactly (`test_a_column_sorts_as_the_console_sorts_it` compares the order with `tables.js`'s); the funnels and Chart any table come with phase 2 |

### 14.2 Results

| Group | ViewModels | Kinds | Themes, widths | Differences beyond 14.1 |
|---|---|---|---|---|
| the showcase | 1 | all 22 | 11 themes x 390 and 1280 px (22 pairs), plus the designer off and light DOM (4) | none |
| edge cases from VIEWMODEL.md | 7 | all 22 and an unknown one; every state | 2 themes x 2 widths (28 pairs) | none |
| captured views | 5 | kv, status, table, ladder, tabs, line, area, hbar, links, provenance | 2 widths (10 pairs) | none |
| every pack Sutra on its own sample, and the documented examples (real server) | 206 of 231 Sutras (not bound: 17 whose kind has no sample document; 8 the preview refuses on a plain server, 422, among them `us-equities/us-company` with its `layout:`) | all 22 | 2 widths (412 pairs) | none in the tree, text, cells, links, tones or charts |
| phase 2: the console's own page, server against library (section 16.4) | 14 + 206 | all 22 | 2 widths (28 + 412 pairs, 880 pages) | none (a freshness age normalised) |

In all, **476 pairs compared, no difference** in any compared part but the intentional ones (phase 1; the same on 1.0.0), and 440 more on
the console's own page in phase 2. No CSP violation, no request off the
console's origin, no script error, and the library never scrolls sideways at 390 or 1280 px. The console's own page did, on three
pack views (section 14.3), until phase 2 fixed it.

### 14.3 Findings for the contract and the console (all three fixed in phase 2)

- A panel with no `data` key and `empty: false` made the console's macros raise (Jinja's undefined `p.data.fields`). **Fixed**: the
  macro tests `p.data is not defined` (and the provenance kind reads `(p.data or {}).fields`); such a panel says "No data available" in
  both renderers (`test_a_panel_with_no_data_and_not_empty_says_no_data`).
- The candlestick's Data table was the only place a client printed a raw number (section 6). **Fixed**: the server sends `openText`,
  `highText`, `lowText`, `closeText` (the panel's `fmt`) and `volumeText` (`amount0`) on every candle; the macro and the library print
  them, and print the numbers only for a ViewModel from an older server (`ViewModelSchemaTest`, `test_the_candles_print_the_servers_texts`).
- **The console's page scrolled sideways** on three pack views: `funds/fund` (167 px at 390), `trading/murex/xccy-swap` (26 px) and
  `world-economy/politics-and-society/region` (934 px). The cause, found by measuring each text node in the page, was the same in all
  three: an `hbar` bar list, whose label column was `minmax(3.4rem, max-content)` with `white-space: nowrap` (a long government type:
  934 px) and whose value column was a fixed 5.5rem (`−28,313,431,259`: 26 px). The library did not scroll only because its sheet clips
  the screen to its container (`contain: content`), so the labels were cut there too. **Fixed in the console's sheet, so in both**:
  the label column is `fit-content(40%)` with an ellipsis (the whole label in its `title`), the value column `minmax(5.5rem,
  max-content)`. The three views no longer scroll sideways at 390 or 1280 px (`test_a_bar_list_never_widens_the_page`, and the parity
  suite's console side).
- `None` prints as "None" in Jinja where a field is `null` rather than missing (e.g. a gauge's `label: null` is never printed, but a
  future field could be): the renderer prints nothing.

## 15. Accessibility parity

The accessible tree is compared node for node (section 14), so what the macros give, the renderer gives:

- **Headings and labels**: the title as `h1`, every panel an `h3` that labels its `section` (`aria-labelledby`), tab parts `h4`,
  section folds `h2`; every chart is `role="img"` with a sentence (`"15 · histogram: 300 values in 17 bins"`) and keeps its Data table;
  a metric is a labelled group; the strip and fields are definition lists; drill tables are labelled and captioned.
- **Keyboard**: every control is a native button or link; tabs move with arrows, Home and End; tree toggles and drill links take Enter;
  Esc goes up a drill level; Alt+← goes back; the split moves with arrows; rows and bars with events take Enter and Space.
- **Focus**: a panel is focusable (`tabindex="-1"`) for function keys; a toggled tree row keeps the focus on its toggle; the drill keeps
  the focus in the drill.
- **Targets**: 44 px on a container narrower than 640 px and on any coarse pointer (`test_targets_are_44_px_on_a_phone`).
- **Colour is never the only signal**: every tone pairs with a sign or a word from the server (`−412,580`, `Pending`), statuses have
  text, chart series have legends and Data tables, the heat pivot keeps its numbers.
- **Live regions**: computing states and the pager are polite live regions; alerts (failed, partial, stale) use `role="alert"`; a
  streamed screen is `aria-busy` until `done`.

## 16. Phase 2: the console drawn by the renderer

**Built 2026-10-30 for 2.9.7** behind `ui.render` (CONFIGURATION.md): `ui.render.mode` is `server` (the macros, the default this release)
or `client` (the library), applied page by page to `ui.render.pages` (`view`, `pane`), so a page can be turned back without a release;
`?render=server|client` on an address overrides both for one request (a comparison; both draw the same ViewModel with the same masks).

### 16.1 The pages: what moved, what stays

| Page | Drawn by | Why |
|---|---|---|
| The entity page `/v/{kind}/{id}` (`terminal/view.html`): what `/go` opens, a shared link (`?share=`), a pinned or history day (`?asOf=&knownAt=&gen=`) | **the library** when `view` is listed | the page the design names first; every one of these is the same route and template |
| The same page in a workspace pane (`/v/{kind}/{id}?embed=1`, a workspace tile) | **the library** when `pane` is listed | the same template; its links still become the workspace's selections (`view.js`) |
| `<drishti-view>`, `<drishti-panel>` on other sites | **the library**, in Elements 2.0 (section 13); Elements 1.x unchanged | the product owner's decision 3 |
| The Studio and Build previews (`studio/_preview.html`: Studio, the Build workbench's ops, Ship, the pack wizard) | the server | the preview is a fragment the workbench's canvas decorates (select a panel, its outline and handles, `build/canvas.js`) and four routes answer it as `previewHtml`; moving it means those routes and the canvas together. Next, with the macros' retirement |
| Compare (`/compare/{kind}/{id}`) | the server | it draws a diff (two dates, field by field), not a ViewModel's panels |
| Search results (`/s`), monitors (`/monitor`) | the server | rows of a search and a monitor's state, not ViewModels (they use only the cell and tone macros) |
| Exports (`/dist/view/...` PDF, PNG, Excel, CSV) and share pictures | the server | made by the server for each recipient; they move with the macros' retirement (16.6) |
| `/g/{id}` (GenUI) | not built | GENUI.md section 9 now streams the JSON this library draws |

### 16.2 How the console is a host

The page keeps its chrome server-drawn (the top bar, the share, pin and as-of banners, the tools beside the title, the function keys,
About and Discussion, Customize, layout mode) and embeds its ViewModel **as this page arranges it** (the reader's own layout, saved pivots
and grid) in `data-render-boot` on an empty host (`div.view-r`, `display: contents`, inside the page's own `.view`). `static/js/view-render.js`
(a module) draws the banners, the head and the panels into that `.view` with `render(host, vm, {bare: true, shadow: false, behaviours:
'host', chrome: true, navigation: 'follow', consoleLinks: 'follow', outsideLinks: 'new-window', grid, asOf, businessDate, designOn})`,
then moves the breadcrumbs and the tools into the drawn head. The page's other scripts are `defer`red after that module: modules and
deferred scripts run in document order once the page is parsed, and the paint completes in microtasks, so **every script of the page
meets the drawn view exactly as it meets the server's HTML**: one implementation of each behaviour, unchanged. First paint needs no
second request.

- `chrome: true` draws what the console's scripts read from a panel's frame: the download menu, `data-wire`, `data-refresh`, `data-prov`,
  `data-form`, `data-compute-*`, the grid's `data-sg` and arrange bar, the Pivot tab's `data-pivot`, the nested pivot's `data-pv-tree`.
- **Live** goes through the library's `subscribe()`, handed the tab's one channel (`channel.js`) with a `vm:` key: the console sends that
  subscription's panel patches as panels (JSON) with no HTML (`core/channel.py`); the library applies strip and panel patches, keeping
  the reader's sizes, a header's controls, the chosen tab and the layout handles (`onReplace`); `deleted`, `restored`, the provenance and
  the Live button's state are the console's, as `live.js` does on a server page.
- **Panels the page's own scripts swap in** (a control: `controls.js`; a row's event or children: `sutra-wire.js`; a refresh or a computed
  result: `refresh.js`, `compute.js`) are drawn by the library from the panel's JSON, which `/v/.../panels`, `/events` and `/children`
  now answer beside the HTML (`window.drishtiPanelNode`); a control's strip comes as cells (`stripCells`, `drishtiStripNode`).
- **About this page** is fetched as JSON (`/v/{kind}/{id}/about?format=json`) and drawn by the library (`m/about.js`) into the console's
  own drawer (`drishtiAboutNode`); the panel hints read the same index.
- Charts are drawn by the library after the page's scripts have laid the grid out (`beforeCharts`), so each chart has its final width.

### 16.3 The behaviours phase 1 left out

| Behaviour | The console's page (`behaviours: 'host'`) | Another host (`behaviours: 'console'`, Elements 2.0) | Tested |
|---|---|---|---|
| Per-column filter funnels | `colfilter.js`, `tables.js` on the drawn tables; the filters in the address (`?f.<panel>.<column>=`) | the same scripts inside the container; state kept in place | both modes: same rows, same address; library: in a shadow root |
| Chart any table | `tables.js`'s chart button, `table-chart*.js` | the same (`tables.js` `chart: true` in a shadow root); the dialog opens over the host's page, never a window | both modes: the same chart; library |
| The drill's address state | `drill.js`: `?dr.<panel>=` and Back | `drill.js` with `address: false`: in place, the host's address untouched | both modes: the same address, Back goes up; library: in place |
| Customize and layout mode | `designer.js`, `layout.js` find the drawn panels | not offered (a console feature) | layout mode in both modes; Customize in the client-mode run of `test_designer_browser.py` |
| The download menu | drawn by the library (`chrome: true`), named by `view.js` | not offered (a console feature) | both modes: the same files |
| About this page | drawn by the library from JSON in the console's drawer | drawn by the library in a side sheet in the container (`about: true`) | both modes: the same text, JSON asked on the client page; library; Elements 2.0 |
| The Pivot tab's grid | `pivot-engine.js`, `pivot-grid.js`, `pivot.js` | the same, its rows read as the person (`recordsUrl`; the embed API v2) | both modes: the same grid; library; Elements 2.0 |
| The nested-pivot grid | `tree-rows.js` from `data-pv-tree` | the same | both modes; library |
| Controls, row events, refresh, computing | `controls.js`, `sutra-wire.js`, `refresh.js`, `compute.js`; swapped panels drawn from JSON | controls (`panelsUrl`); row events as `drishti:row-click` | both modes: a tick and a control move the same panels |

`drill.js` gained a root-scoped `init(root, {address})`, `tables.js` a `chart` option and a base for its chart kit beside itself,
`geomap.js` its shapes beside any `/js/geomap.js`, `about.js` a JSON path, `controls.js`, `sutra-wire.js` and `refresh.js` the
library-drawn panel; each server page is unchanged (its browser tests unchanged and passing).

### 16.4 Parity in phase 2 (2026-10-30)

| Suite | What | Result |
|---|---|---|
| `test_render_parity_browser.py` (phase 1's, now on 1.0.0) | the macros against the library in a blank page: the showcase in 11 themes, the edge cases, the captured views, every pack Sutra on its sample (206 of 231 bound by a real server) at 390 and 1280 px | 64 tests, **476 pairs, no difference** but section 14.1's; the console's own page no longer scrolls sideways on any of them (the three of 14.3 fixed) |
| `test_render_console_browser.py`, the pages | **the console's own entity page drawn by the server and by the library** (`?render=server` and `?render=client`): 6 captured and stand-in views and the 8 edge cases at 390 and 1280 px (28 pairs), and every pack Sutra on its sample (206 views, 412 pairs, 824 pages), compared as phase 1 compares (tree, text, cells, links, tones, chart series), with no script error, no CSP violation and no sideways scroll | **no difference**, after one fix: `funds/fund` scrolled sideways by 46 px at 1280 in both modes (a narrow metric's header tools; the panel header now wraps). Normalised: a panel's freshness age ("updated 4 s ago"), read from the clock when each page loads |
| `test_render_console_browser.py`, the behaviours | each used in both modes: the funnels (same rows, `?f.` in the address), Chart any table (the same chart), a drill (the same `?dr.` address, Back goes up), the download menu (the same files), About (the same text; JSON asked only on the client page), the Pivot tab (the same grid), the nested pivot (the same grid), layout mode (the drawn panels decorated), a live tick and a control (the same panels move; the client draws them from JSON), the embedded ViewModel (no second request) | 9 tests, the same outcome in both modes |
| `test_render_behaviours_browser.py` | the library on a page that is not the console: `behaviours: 'console'` (funnels, Chart any table, the Pivot grid from records read as the person, the nested pivot, a drill in place), `about: true`, `navigation: 'follow'`, `subscribe(fn)`, the candle texts | 8 tests |
| `test_embed_elements2_browser.py` | Elements 2.0 on the demo host with a real server: live under the host's CSP from the ViewModel, masks, a link the host takes over, the behaviours and About in the element, one `vm:` connection, the versioned paths and bytes | 5 tests |

**Intentional differences on the console's own page: none.** The page keeps its chrome, its behaviours are the same scripts, and
section 14.1's differences were all about a page without the console's scripts.

### 16.5 The console's whole browser suite with `ui.render.mode: client`

Every browser file of the console (128, all but `test_render_console_browser.py`, which compares both modes itself) was run once with
`DRISHTI_UI_RENDER=client` (`ui.render.mode: client` for `view` and `pane`), on four workers: **902 passed, 9 skipped, 15 failed, 9
errors** in 38 minutes. Every failure was then run again on its own, and in server mode where it still failed:

| Test | Cause | Now |
|---|---|---|
| `test_designer_browser.py` (4: Customize, a linked record, key figures) and, once, `test_designer_grid_browser.py` | **client mode.** After a step Customize fetches the page again and takes its `.vgrid`; a client page has none (its panels are drawn from the embedded ViewModel), so it reloaded the page | **fixed**: `designer.js`, `designer-grid.js` and `designer-outline.js` ask the page's library to draw the fetched page's body (`drishtiFreshGrid`, `handle.drawBody`); all pass in both modes |
| `test_about_ask_browser.py` (3) | **client mode.** The library's About lacked the *Ask about this page* layer | **fixed**: ported (`m/about.js`); all pass in both modes |
| `test_reactive_panels_browser.py` (1) | **the test**: it read a chart's data from the macros' `data-chart` attribute; the library keeps it on the element | **fixed in the test** (reads either); passes in both modes |
| `test_collab_polish_browser.py::test_the_parties_of_a_share_reply_in_the_banner` | **client mode, open.** The first client-drawn page a new browser context opens from the inbox's link (with `?gen=&share=`) stalls before its module runs: every file answered 200, no script error, `readyState` stays `interactive`, so neither the library nor the deferred scripts (`share-reply.js`) run. Opening the same address again in the same tab, or in a new one, draws normally; a stand-in console does not reproduce it from `/t`, `/inbox` or `/help` | **not fixed**; passes in server mode (the default). To be found before `client` becomes a default |
| `test_live_tabs_browser.py::...[leader]` | **not client mode**: fails the same in server mode. When the tab holding the channel closes, the new leader's channel sends `view` again and every page that already had one reloads (`live.js` and `view-render.js` share that rule), so the test's evaluate meets a navigation | unchanged (both modes) |
| `test_bi_wave_b_browser.py` (5 setup errors) | **not client mode**: BI pages; its report never reaches *Published* in server mode either on this machine | unchanged (both modes) |
| `test_var_currency_browser.py` (4 setup errors), `test_bi_runs_browser.py`, `test_bi_wizard_and_controls_browser.py`, `test_w1_model_designer_browser.py`, `test_write_back_browser.py::test_the_sheet_at_phone_width` | **load**: a lake command's time limit and clicks under four workers; each passes on its own in client mode | pass on a re-run |

The touched browser files, one at a time in server mode (the default): colfilter, table_chart, controls, sutra_wire, refresh, screen_grid,
about, about_hints, about_ask, maps, zoom, embed_elements (1.x; one WebKit tap test failed once and passes on its own), embed_controls,
designer, designer_grid, reactive_panels, render_library, render_behaviours, embed_elements2, deep_trade, live_toggle, provenance_links,
share_view_state, workspace_keys, record_forms, write_back and render_console: all pass.

### 16.6 Still to come

- **Retiring the macros** (the release after 2.9.7, product owner): the panel macros, `_panel_html`, the channel's HTML, the embed API v1's
  HTML path, once `ui.render.mode: client` has run a release; then the Studio and Build previews and the exports move.
- The contract on live frames and in the schema's `$id`; publishing `@drishti/render` (section 13); GenUI's stream (GENUI.md 9).

Rendering first paint in the browser costs one script run more and no second request (the ViewModel is in the page); ECharts loads only
for charts. Measuring the heaviest pages (a netting set, a deep trade) on a slow phone before the default turns to `client` is part of
the retirement step.

## 17. Risks

| Risk | Answer |
|---|---|
| Drift between the library and the macros during the two phases | the parity suite runs on every pack view in the drill; phase 2 deletes the macros, ending drift |
| First paint later than server HTML on slow phones | the ViewModel embedded in the page; ECharts loaded only for charts; measure on the heaviest pages (netting set, deep trade) before switching each page |
| Behaviour ports (tables, pivot) are large | ported one by one behind the setting, each with its existing browser tests |
| A host page's CSS or a framework re-rendering the container | Shadow DOM by default; `render()` into the same container replaces cleanly; `dispose()` for frameworks |
| A host without a session or a token | a clear `DRS` error with the fix; the playground shows the token option |
| ECharts tooltips write markup | every formatter escapes; a test with hostile labels |
| Cross-origin hosts before `/embed/v1/render/` exists | phase 1 is for the console's origin and same-origin proxies; cross-origin in phase 2 with CORS and the embed token |
| Charts' axis ticks depend on pixel width | compared as series, not pictures; screenshots stay the console's |

## 18. Decisions for the product owner

Asked with phase 1; **answered by the product owner for phase 2** and built as answered:

| # | Question | Answer |
|---|---|---|
| 1 | A setting for phase 2 | **Yes**: `ui.render.mode` (`server` \| `client`) page by page (`ui.render.pages: [view, pane]`), default `server` for 2.9.7, so it can be turned back (CONFIGURATION.md) |
| 2 | Links that cannot be drawn in place | In the **console** (a host like any other) console links navigate as the console does today. For **other hosts**, "event only" stays the default for console pages and outside URLs, with `consoleLinks` / `outsideLinks` to open them in a new tab when the host asks |
| 3 | Moving `<drishti-view>` onto the renderer | **Elements 2.0 / embed API v2 beside v1 for one release**, v1 unchanged (section 13; ELEMENTS.md section 19) |
| 4 | npm publishing | **Not in this phase**: the package layout is documented (section 13) |
| 5 | `<drishti-screen>` | **Yes**: an alias of `<drishti-view>` in Elements 2.0 |
| 6 | GenUI's stream format | **Yes**: GENUI.md section 9 amended to stream the JSON messages of section 11 instead of HTML (design text only) |
| 7 | The contract version | **Yes**: the server stamps `"contract": "1.0"` on every ViewModel (`ViewModel.CONTRACT`, `ViewModelSchemaTest`); the library refuses an unknown major (`DRS-9105`) |
