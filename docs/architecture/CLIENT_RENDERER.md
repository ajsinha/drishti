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
[ADR-021](adr/021-embedding-is-html-over-the-wire.md). The contract it draws: [VIEWMODEL.md](VIEWMODEL.md) (its own reference, [section 22](#22-the-viewmodel-contract-its-own-reference)).
Since 2.9.7 this one document also holds the **Drishti Elements developer guide** ([section 19](#19-drishti-elements-developer-guide); its
renderer chapter is ["Client-side renderer and Elements 2.0"](#client-side-renderer-and-elements-20)), **the Elements design**
([section 20](#20-drishti-elements-the-design)) and **GenUI**, design, not built ([section 21](#21-genui-screens-composed-for-an-ai-agent-design-not-built)).

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
19. [Drishti Elements: developer guide](#19-drishti-elements-developer-guide)
    - [Five minutes: a static page](#five-minutes-a-static-page)
    - [How a user gets in: the token exchange](#how-a-user-gets-in-the-token-exchange)
    - [Loading the library: versions, SRI and npm](#loading-the-library-versions-sri-and-npm)
    - [Policy: what your page's CSP needs](#policy-what-your-pages-csp-needs)
    - [The elements, one by one](#the-elements-one-by-one)
    - [`<drishti-report>`](#drishti-report-1)
    - [`<drishti-about>` and `<drishti-search>`](#drishti-about-and-drishti-search)
    - [React, Angular and Vue sample pages](#react-angular-and-vue-sample-pages)
    - [Theming and sizing](#theming-and-sizing)
    - [Cookbook](#cookbook)
    - [Troubleshooting by code](#troubleshooting-by-code)
    - [Client-side renderer and Elements 2.0](#client-side-renderer-and-elements-20)
20. [Drishti Elements: the design](#20-drishti-elements-the-design)
    - [1. Why](#1-why)
    - [2. Goals and non-goals](#2-goals-and-non-goals)
    - [3. What the code already gives us](#3-what-the-code-already-gives-us)
    - [4. Architecture](#4-architecture)
    - [5. The element API](#5-the-element-api)
    - [6. The embed API](#6-the-embed-api)
    - [7. Loading, reloading, refreshing, reconnecting](#7-loading-reloading-refreshing-reconnecting)
    - [8. Embed tokens: the token exchange](#8-embed-tokens-the-token-exchange)
    - [9. Registering a host application](#9-registering-a-host-application)
    - [10. Security: threats and answers](#10-security-threats-and-answers)
    - [11. Performance](#11-performance)
    - [12. Console scripts as root-scoped modules](#12-console-scripts-as-root-scoped-modules)
    - [13. Distribution and versioning](#13-distribution-and-versioning)
    - [14. Testing](#14-testing)
    - [15. Documentation plan](#15-documentation-plan)
    - [16. Build plan](#16-build-plan)
    - [17. Decisions for the product owner](#17-decisions-for-the-product-owner)
    - [18. `<drishti-report>`: a published report in another web application](#18-drishti-report-a-published-report-in-another-web-application)
    - [19. Elements 2.0: drawn by the client-side renderer](#19-elements-20-drawn-by-the-client-side-renderer)
21. [GenUI: screens composed for an AI agent (design, not built)](#21-genui-screens-composed-for-an-ai-agent-design-not-built)
    - [1. The ask](#1-the-ask)
    - [2. The idea in one picture](#2-the-idea-in-one-picture)
    - [3. Principles and non-goals](#3-principles-and-non-goals)
    - [4. What the code already gives us](#4-what-the-code-already-gives-us)
    - [5. The fragment catalogue: blocks with typed slots and a data contract](#5-the-fragment-catalogue-blocks-with-typed-slots-and-a-data-contract)
    - [6. The composer: one tool, no written code](#6-the-composer-one-tool-no-written-code)
    - [7. Ephemeral Sutras and live URLs](#7-ephemeral-sutras-and-live-urls)
    - [8. Data wiring](#8-data-wiring)
    - [9. Streaming to the host application](#9-streaming-to-the-host-application)
    - [10. Dynamic forms](#10-dynamic-forms)
    - [11. Security and governance](#11-security-and-governance)
    - [12. Configuration](#12-configuration)
    - [13. APIs, tools and error codes](#13-apis-tools-and-error-codes)
    - [14. Build plan for 2.9.0](#14-build-plan-for-290)
    - [15. Tests](#15-tests)
    - [16. Documentation to write or update](#16-documentation-to-write-or-update)
    - [17. Risks](#17-risks-1)
    - [18. Open questions](#18-open-questions)
22. [The ViewModel contract: its own reference](#22-the-viewmodel-contract-its-own-reference)

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
the host's token when `token` is given (the token exchange of [section 19, its section 8](#8-embed-tokens-the-token-exchange)).
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
`terminal/view.html` does. With a Sutra `layout:` ([RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, section 13](../guides/RACHANA_REFERENCE.md#13-layout-placing-panels)) `m/grid.js`
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
`options.viewUrl(ref)`; with `token` the request carries `Authorization: Bearer` (the token exchange of section 19, its section 8: the
host's backend exchanges its user for a short-lived token; every call is verified and masked as that user). Phase 2 adds
`GET /embed/v1/views/{kind}/{id}?format=viewmodel` for cross-origin hosts, so the embed API's CORS allow-list, token and usage counts
apply unchanged. Controls travel as `ctl.<id>` parameters; the business date as `X-Drishti-As-Of`.

**Tables through `drishtiFetchTable`.** The renderer reads no table of its own today: every panel's rows are in the ViewModel. The one
place a panel fetches rows, the Pivot tab (`…/panels/{panel}/records`), is read with the console's `drishtiFetchTable` (Arrow when the
vendored Arrow JS is on the page, JSON otherwise; [DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6](../guides/DATA_GUIDE.md#6-arrow-to-the-browser-where-it-pays)),
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
([section 19](#how-a-user-gets-in-the-token-exchange)): its server vouches for its signed-in user and passes the
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
([section 21](#21-genui-screens-composed-for-an-ai-agent-design-not-built); dynamic screens, [RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, section 12](../guides/RACHANA_REFERENCE.md#12-dynamic-screens), `/s/<name>`).

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

section 21, its section 9.2 maps onto these one for one (`skeleton` → `skeleton`, `panel` → `panel-replace`, `view` → `done`, `frame` → `patch`,
`gone` → `error`), with one change under ADR-022: the stream carries **panels as JSON**, not the console's HTML (section 21 9.1 and 9.5
assumed ADR-021). Masks hold the same: every panel is bound as the viewer on the server; the client trusts nothing in the stream but
draws it as text.

**Every link, drill, event and action stays intact** on an ephemeral screen, because they are in the ViewModel, not in the page: a
row's entity link opens the entity in place; a drill opens its child rows; a click that fires an event (`ev`, `@panel.event`) becomes
`drishti:row-click` and, with the server's event route, re-binds only the panels that follow it, against the same screen id; provenance
and About name the ephemeral Sutra. **What the server must give an ephemeral screen** (section 21, its section 7): an id (`g_…`) that links,
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

**The final paths** (decided in phase 2; section 19, its section 19):

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
| `/g/{id}` (GenUI) | not built | section 21, its section 9 now streams the JSON this library draws |

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
- The contract on live frames and in the schema's `$id`; publishing `@drishti/render` (section 13); GenUI's stream (section 21 9).

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
| 3 | Moving `<drishti-view>` onto the renderer | **Elements 2.0 / embed API v2 beside v1 for one release**, v1 unchanged (section 13; section 19, its section 19) |
| 4 | npm publishing | **Not in this phase**: the package layout is documented (section 13) |
| 5 | `<drishti-screen>` | **Yes**: an alias of `<drishti-view>` in Elements 2.0 |
| 6 | GenUI's stream format | **Yes**: section 21, its section 9 amended to stream the JSON messages of section 11 instead of HTML (design text only) |
| 7 | The contract version | **Yes**: the server stamps `"contract": "1.0"` on every ViewModel (`ViewModel.CONTRACT`, `ViewModelSchemaTest`); the library refuses an unknown major (`DRS-9105`) |

## 19. Drishti Elements: developer guide

Drishti Elements put Drishti's live views and BI reports inside **your own web application** as Web Components with a Shadow DOM and **no iframes**: your layout, your theme tokens, your keyboard focus, one network connection for the whole page, and your user signed in as themselves, with every
masked field masked. There are five elements, one library, and one contract for tokens.

| Element | Shows |
|---|---|
| `<drishti-view kind="trade" entity="MX-20000001">` | a live view of one entity: header, key figures, every panel, as the console draws them |
| `<drishti-panel kind="trade" entity="MX-20000001" panel="legs">` | **one panel** of a view, in a card of your own |
| `<drishti-report report="file:report:trades-overview">` | a published **BI report**, one page of it or one visual of it, with its slicers, cross-filter and live updates |
| `<drishti-about kind="trade" entity="MX-20000001">` | the **About this page** layers of a view (where each number comes from, the masks and rules that applied to this reader) in a panel of your own |
| `<drishti-search>` | **search across Drishti** from your page: a box and the entities that match, masked for the signed-in user |

This is the developer's reference and cookbook. The design and its history are in [section 20](#20-drishti-elements-the-design) and [ADR-021](adr/021-embedding-is-html-over-the-wire.md); the administrator's side (registering a host application) is [Admin → Embedding](../guides/USER_GUIDE.md) and
[API_GUIDE.md](../guides/API_GUIDE.md#embedding-views-in-another-web-application); the settings are in [CONFIGURATION.md](../admin/CONFIGURATION.md).

### Five minutes: a static page

1. **Switch embedding on** (it is off by default): the server needs `drishti.embed.enabled=true` and `drishti.security.enabled=true`, and the console `embed.enabled=true` ([CONFIGURATION.md](../admin/CONFIGURATION.md#console-embed--the-consoles-side-of-embedded-views)).
2. **Register your application** in *Admin → Embedding* (or `POST /api/v1/admin/embed/apps`): an id, its **exact origins** (`https://crm.bank.example`, no path), how it signs in (a client secret shown **once**, a public key, or both), the **scopes** it may use (`embed:view` for views and panels,
   `embed:about` for the About text and `<drishti-about>`, `embed:report` for BI reports, `embed:search` for `<drishti-search>`) and the **kinds and reports** it may show (`trade`, `counterparty`, `report:<id>`, `report:*`; empty: everything the user's roles open).
3. **Give your page a token provider** that asks *your own backend* for a token (section 2), and add the one script line and an element:

```html
<script type="module" src="https://drishti.bank.example/embed/v1/elements/1.4.3/drishti-elements.js" crossorigin="anonymous"></script>
<script type="module">
  DrishtiElements.configure({
    tokenProvider: async () => {                      // your backend, not Drishti: it holds your client secret
      const r = await fetch('/api/drishti-token', { cache: 'no-store' });
      const b = await r.json();
      return { token: b.token, expiresAt: Date.now() + b.expiresIn * 1000 };
    }
  });
</script>

<drishti-view kind="trade" entity="MX-20000001" theme="light"></drishti-view>
```

That is all of it. `server` defaults to the origin the module was loaded from (the console), and several elements on a page share one connection. A working host application with its own sign-in, search box and a report page is `tools/elements-demo` in the repository
(`python3 tools/elements-demo/server.py --help`).

### How a user gets in: the token exchange

Drishti never sees your user's password and your page never sees your secret. Your **backend** exchanges *its* credentials and *who the user is* for a **short-lived, read-only embed token** at the Drishti server (RFC 8693, `POST /api/v1/embed/token`), and hands the token to the page. The server checks the token
on **every call** (signature, audience, expiry, the application, the user, the origin, the scope and kind or report, the rates) and then acts as that user, with their roles as they are now and with every field mask applied whatever their roles say.

The request your backend makes (form encoded; client authentication by `Authorization: Basic base64(id:secret)` or by a signed client assertion):

```
POST /api/v1/embed/token
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
subject_token=<an assertion your backend signs about the user, or the user's OIDC ID token>
subject_token_type=urn:ietf:params:oauth:token-type:jwt        (or ...:id_token)
audience=https://drishti.bank.example                           (the console's public URL)
scope=embed:report                                              (within the application's own scopes)
```

and the answer is `{"access_token": "<JWT>", "token_type": "Bearer", "expires_in": 300, "scope": "embed:report"}`. The field-by-field reference, the claims and the refusals are in [API_GUIDE.md](../guides/API_GUIDE.md#embedding-views-in-another-web-application).
The backend of the demo host (Python, no library but the standard one, the user's assertion signed with RS256) is `tools/elements-demo/server.py`, the `mint` method; the same exchange in other languages:

```javascript
// Node (an Express route; fetch is built in)
app.get('/api/drishti-token', async (req, res) => {
  const user = req.session.user;                       // who is signed in to YOUR application
  const now = Math.floor(Date.now() / 1000);
  const assertion = signJwt({ iss: 'crm', sub: user, aud: DRISHTI + '/api/v1/embed/token', iat: now, exp: now + 60, jti: crypto.randomUUID() }, MY_PRIVATE_KEY);
  const r = await fetch(DRISHTI + '/api/v1/embed/token', {
    method: 'POST', headers: { Authorization: 'Basic ' + Buffer.from('crm:' + SECRET).toString('base64'), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange', subject_token: assertion,
      subject_token_type: 'urn:ietf:params:oauth:token-type:jwt', audience: CONSOLE, scope: 'embed:report' }) });
  const t = await r.json();
  res.json({ token: t.access_token, expiresIn: t.expires_in });
});
```

```java
// Java (Spring MVC; java.net.http; signJwt is your JWT library's call)
@GetMapping("/api/drishti-token")
Map<String, Object> token(Principal who) throws Exception {
    long now = Instant.now().getEpochSecond();
    String assertion = signJwt(Map.of("iss", "crm", "sub", who.getName(), "aud", drishti + "/api/v1/embed/token", "iat", now, "exp", now + 60, "jti", UUID.randomUUID().toString()));
    String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:token-exchange") + "&subject_token=" + enc(assertion)
        + "&subject_token_type=" + enc("urn:ietf:params:oauth:token-type:jwt") + "&audience=" + enc(console) + "&scope=embed%3Areport";
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(drishti + "/api/v1/embed/token"))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("crm:" + secret).getBytes()))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(BodyPublishers.ofString(form)).build(), BodyHandlers.ofString());
    JsonNode t = json.readTree(r.body());
    return Map.of("token", t.get("access_token").asText(), "expiresIn", t.get("expires_in").asInt());
}
```

A token lives five minutes by default (the application's own setting, never more than `drishti.embed.token-max-seconds`). The library asks your provider again **60 seconds before it ends** (40 % of its life when that is shorter) and once more after a `401`; an open live stream is given the fresh token in place, so
nothing redraws. If the provider fails or there is none, the elements tell you with `drishti:token-needed` and wait.

### Loading the library: versions, SRI and npm

The console serves the library at an **exact semantic version**, immutably, and publishes the SHA-384 of every file:

| URL | What |
|---|---|
| `/embed/v1/elements/1.4.3/drishti-elements.js` | the entry (imports `./m/<name>.js`, which are served beside it) |
| `/embed/v1/elements/1.4.3/integrity.json` | `{"name", "version", "api", "files": {"drishti-elements.js": {"integrity": "sha384-…", "bytes": 2500}, "m/report.js": …}}` |
| `/embed/v1/elements/1/…`, `/embed/v1/elements/1.1/…` | `302` to the exact version (for a host that accepts updates; SRI needs the exact one) |
| `/embed/v1/drishti-elements.js` | the console's current library, revalidated on each load (a host that follows the console) |

A version this console does not hold is `410 DRS-8006` (the answer says which one it serves): after a console upgrade, a page pinned to the old version tells you to move the pin, and a version always names **the same bytes**. Put the entry's `integrity` on the script line, and, because a module's own imports are not
covered by it, pin the rest with an import map's `integrity` (Chrome 127 and later) listing the `m/…` files of `integrity.json`.

**npm.** `@drishti/elements` is the same files (`drishti-console/web/elements/package/`, made by `python3 tools/elements_package.py`; publishing is the release build's `npm publish`): `import '@drishti/elements'` defines the elements, `types/index.d.ts` declares them and their events, `types/react.d.ts` adds the React `JSX.IntrinsicElements`.
From `node_modules` the module's own origin is not the console, so **set `server`**: `DrishtiElements.configure({ server: 'https://drishti.bank.example', tokenProvider })`. The sheets, the icon font and the chart code are fetched from that console, so they always match the data it serves.

```ts
import '@drishti/elements';
import type { DrishtiReport, ReportFilterChangedDetail } from '@drishti/elements';

const rep = document.querySelector('drishti-report')!;            // typed: HTMLElementTagNameMap knows the tag
rep.addEventListener('drishti:filter-changed', (e) => console.log(e.detail.selections));   // typed: HTMLElementEventMap knows the event
```

**Two contracts, both semver.** The **element API** (this guide's tables: attributes, properties, methods, events and their `detail`) changes only in a major version of the library. The **embed API** (`/embed/v1`) changes additively within `v1`; a major is announced with `Deprecation` and `Sunset`
headers (the console's `embed.lifecycle`), served beside its successor for two releases, and then `410 DRS-8006`. The element sends `Drishti-Embed-Api: 1.0` and the console answers in the newest compatible shape.

### Policy: what your page's CSP needs

The elements are written for a **strict host policy**: no inline script or style, no `frame-src`. A page that names the console in three directives works:

```
Content-Security-Policy: default-src 'self'; script-src 'self' https://drishti.bank.example; style-src 'self'; font-src https://drishti.bank.example;
  connect-src 'self' https://drishti.bank.example; img-src 'self' data:; object-src 'none'; base-uri 'none'
```

The element's style sheets are adopted (`adoptedStyleSheets`), the icon font is added through the `FontFace` API, the charts are drawn on a canvas, a report's DOM is built with `createElement` and `textContent`, and markup a view receives is rebuilt from an allow-list before it is inserted. A host that enforces **Trusted Types** adds
`require-trusted-types-for 'script'; trusted-types drishti-elements` and nothing else (the one policy the library creates). A report's `image` visual loads its `src` as your `img-src` allows. The demo host runs under exactly this policy and its tests assert that no violation is reported and no report reaches its `report-uri`.

### The elements, one by one

Every attribute is reflected to a property of the same name in camelCase (`as-of` is `asOf`). Events are `CustomEvent`s on the element, `bubbles: true, composed: true`, named `drishti:<name>`. This reference is **generated** from `drishti-console/core/element_api.py`, the same source as the TypeScript declarations,
and a test checks it against the elements, so it cannot drift.

##### `<drishti-view>`

A live Drishti view of one entity.

| Attribute | Type | Meaning |
|---|---|---|
| `server` | `string` | the console's base URL; default: the origin the module was loaded from. Elements sharing a `server` share one connection |
| `kind` | `string` | an entity kind (`trade`, `counterparty`, ...); required |
| `entity` | `string` | the entity id; change it to show another entity (reloads in place); required |
| `as-of` | `string` | `live` (default) or a business date `YYYY-MM-DD` (a past date is a static snapshot) |
| `known-at` | `string` | an ISO instant: bitemporal "as known at" |
| `theme` | `string` | `inherit`, or a Drishti theme: `terminal`, `light` (default), `blue`, `green`, `wallstreet`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson`, `terminal-blue` |
| `density` | `'compact' | 'comfortable'` | row padding of tables |
| `header` | `'full' | 'strip' | 'none'` | title and strip, strip only, or panels only (default `full`; `none` for `<drishti-panel>`) |
| `panels` | `string` | comma-separated panel ids to show (default all) |
| `live` | `'auto' | 'off'` | `off` loads once and never subscribes |
| `label` | `string` | the element's accessible name (`role="region"`); default the view's title |
| `controls` | `string` | the Sutra's controls as `legs=2&when=2026-10-01..2026-12-31` (the `ctl.` prefix is optional) |

| Property | Type | Meaning |
|---|---|---|
| `tokenProvider` | `() => string | { token: string; expiresAt?: number } | Promise<string | { token: string; expiresAt?: number }>` | set on any element: one provider per page (also `DrishtiElements.configure({tokenProvider})`) |
| `state` (read-only) | `'idle' | 'loading' | 'live' | 'static' | 'reconnecting' | 'paused' | 'deleted' | 'error'` | the state machine |
| `view` (read-only) | `{ ref: { kind: string; id: string }; title: unknown; generation: number; provenance: unknown; panels: { id: string; kind: string; title: string }[] } | null` | once loaded |
| `lastError` (read-only) | `{ code: string; status: number; detail: string } | null` | the last error |
| `controls` | `string` | the controls' values now |

| Method | Effect |
|---|---|
| `refresh()` | fetch the view again (same entity), keep the subscription, swap what changed |
| `reload()` | full reload: cancel in-flight requests, drop the subscription, fetch, subscribe |
| `pause()` | give the subscription back (the element also does this itself when hidden) |
| `resume()` | take it again |

| Event (`drishti:`) | `detail` | When |
|---|---|---|
| `controls` | `{ query: string; changed: string[] }` | a reader changed a control |
| `error` | `{ code: string; status: number; detail: string; fatal?: boolean; retryInMs?: number }` | a DRS code; `fatal: false` while the element retries by itself or a single visual failed |
| `loaded` | `{ ref: { kind: string; id: string }; generation: number; title: unknown; live: boolean }` | a view was loaded and painted |
| `masked` | `{ count?: number; panels?: string[]; columns?: string[] }` | what the user is shown has masked fields (a view: count and panels; a report: the columns) |
| `navigate` | `{ kind: string; id: string; href: string; panel?: string; source: 'link' | 'chart' | 'graph' }` | **cancellable**: the user activated a linked entity; not cancelled, the element shows it in place |
| `paint` | `{ ref: { kind: string; id: string }; generation: number }` | the shadow root was rebuilt |
| `state` | `{ state: string; previous: string }` | every state change (drives a host's own live indicator) |
| `tick` | `{ generation: number; seq?: number; latencyMs?: number; changed?: { strip: number[]; panels: string[] }; at?: string }` | live data was applied |
| `token-needed` | `{ reason: 'missing' | 'refused' | 'provider-failed' }` | no token provider, the token was refused twice, or the provider threw: sign the user in again and set `tokenProvider` |

##### `<drishti-panel>`

One panel of a view: `<drishti-view>` with the `panel` attribute and the header off.

| Attribute | Type | Meaning |
|---|---|---|
| `panel` | `string` | the panel id to show (the same as `panels="<id>"`); required |

| Property | Type | Meaning |
|---|---|---|
| `panel` | `string` | the attribute of the same name |

##### `<drishti-report>`

A published BI report, or one page or visual of it, with its slicers, cross-filter and live updates.

| Attribute | Type | Meaning |
|---|---|---|
| `server` | `string` | the console's base URL; default: the origin the module was loaded from |
| `report` | `string` | the id of a **published** report, e.g. `file:report:trades-overview`; required. The host application's `kinds` must list `report:<id>` or `report:*` |
| `page` | `string` | the page to show first (default: the first page that is not a drill-through target) |
| `visual` | `string` | show only this visual (its page is found for you): a card, a chart or a table in a card of the host's own |
| `filters` | `string` | initial filters as JSON: `{"slicers": {"<slicer id>": ["FX"]}, "dates": "2026-10-09", "selections": [{"visual": "by-desk", "field": "trade.DESK", "values": ["FX"]}], "mode": "highlight"}` |
| `chrome` | `'full' | 'bare'` | `full` (default): title, page tabs, business date, bookmarks, filter chips, live bar. `bare`: the visuals only (the default when `visual` is set) |
| `theme` | `string` | as `<drishti-view>` |
| `live` | `'auto' | 'off'` | `off` draws once and never follows the report's live tables |
| `label` | `string` | the element's accessible name |

| Property | Type | Meaning |
|---|---|---|
| `tokenProvider` | `() => string | { token: string; expiresAt?: number } | Promise<string | { token: string; expiresAt?: number }>` | as `<drishti-view>` |
| `state` (read-only) | `'idle' | 'loading' | 'live' | 'static' | 'stale' | 'failed' | 'reconnecting' | 'paused' | 'error'` | the state machine |
| `page` | `string` | the page shown (assign to change it) |
| `filters` | `{ page?: string; slicers?: Record<string, unknown>; dates?: string | { from?: string; to?: string } | null; selections?: { visual: string; field: string; values: unknown[] }[]; carry?: unknown[]; mode?: 'filter' | 'highlight' }` | the filters in force; assign the same shape to set them (anything left out is cleared) |
| `lastError` (read-only) | `{ code: string; status: number; detail: string } | null` | the last error |

| Method | Effect |
|---|---|
| `reload()` | full reload: fetch the report again and redraw |
| `refresh()` | ask every visual again, quietly |
| `selectPage(id` | show another page (its filters are cleared, as when a reader clicks the tab) |
| `clearFilters()` | clear slicers, selections and the date (the Reset button) |
| `pause()` | give the live stream back |
| `resume()` | take it again |

| Event (`drishti:`) | `detail` | When |
|---|---|---|
| `error` | `{ code: string; status: number; detail: string; fatal?: boolean; retryInMs?: number }` | a DRS code; `fatal: false` while the element retries by itself or a single visual failed |
| `filter-changed` | `{ page: string; slicers: Record<string, unknown>; dates: unknown; selections: { visual: string; field: string; values: unknown[] }[]; carry: unknown[]; mode: string; source: 'user' | 'api' }` | a reader (or the host, `source: 'api'`) changed a slicer, a selection, the date or the page |
| `loaded` | `{ report: string; title: string; page: string; visuals: string[]; live: boolean }` | the report was drawn (every visual of the first page has answered or failed) |
| `masked` | `{ count?: number; panels?: string[]; columns?: string[] }` | what the user is shown has masked fields (a view: count and panels; a report: the columns) |
| `page-changed` | `{ page: string; title: string; visuals: string[]; phone: boolean }` | a page was drawn (first draw, a tab, `selectPage`, or a swap to or from the phone layout) |
| `state` | `{ state: string; previous: string }` | every state change (drives a host's own live indicator) |
| `tick` | `{ generation: number; seq?: number; latencyMs?: number; changed?: { strip: number[]; panels: string[] }; at?: string }` | live data was applied |
| `token-needed` | `{ reason: 'missing' | 'refused' | 'provider-failed' }` | no token provider, the token was refused twice, or the provider threw: sign the user in again and set `tokenProvider` |


##### `<drishti-about>`

The "About this page" layers of one view (sources, lineage, the masks and rules that applied to this reader) in a panel of the host's own.

| Attribute | Type | Meaning |
|---|---|---|
| `server` | `string` | the console's base URL; default: the origin the module was loaded from |
| `kind` | `string` | an entity kind (`trade`, `counterparty`, ...); required |
| `entity` | `string` | the entity id; change it to show another entity's About; required |
| `theme` | `string` | as `<drishti-view>` |
| `label` | `string` | the element's accessible name (default `About this page`) |

| Property | Type | Meaning |
|---|---|---|
| `tokenProvider` | `() => string | { token: string; expiresAt?: number } | Promise<string | { token: string; expiresAt?: number }>` | as `<drishti-view>` (the application's scopes must include `embed:about`) |
| `state` (read-only) | `'idle' | 'loading' | 'loaded' | 'error'` | the state machine |
| `lastError` (read-only) | `{ code: string; status: number; detail: string } | null` | the last error |

| Method | Effect |
|---|---|
| `reload()` | ask the console for the About body again |

| Event (`drishti:`) | `detail` | When |
|---|---|---|
| `error` | `{ code: string; status: number; detail: string; fatal?: boolean; retryInMs?: number }` | a DRS code; `fatal: false` while the element retries by itself or a single visual failed |
| `loaded` | `{ kind: string; entity: string }` | the About body was drawn |
| `state` | `{ state: string; previous: string }` | every state change (drives a host's own live indicator) |
| `token-needed` | `{ reason: 'missing' | 'refused' | 'provider-failed' }` | no token provider, the token was refused twice, or the provider threw: sign the user in again and set `tokenProvider` |

##### `<drishti-search>`

Search across Drishti from a host page: a box and the entities that match, masked for the signed-in user.

| Attribute | Type | Meaning |
|---|---|---|
| `server` | `string` | the console's base URL; default: the origin the module was loaded from |
| `theme` | `string` | as `<drishti-view>` |
| `placeholder` | `string` | the box's placeholder (default `Search`) |
| `label` | `string` | the box's accessible name (default the placeholder) |
| `limit` | `number` | most results asked for (1 to 50, default 10; the server caps it at `drishti.embed.search.max-results`) |
| `min-chars` | `number` | shortest query searched (default 2) |
| `delay` | `number` | milliseconds of quiet after the last key before a search is made (default 250) |
| `target` | `string` | a CSS selector of a `<drishti-view>` or `<drishti-panel>`: picking a result points it at that entity (unless `drishti:select` is cancelled) |

| Property | Type | Meaning |
|---|---|---|
| `tokenProvider` | `() => string | { token: string; expiresAt?: number } | Promise<string | { token: string; expiresAt?: number }>` | as `<drishti-view>` (the application's scopes must include `embed:search`) |
| `value` | `string` | the text in the box (assign to search for it) |
| `results` (read-only) | `{ type: string; kind: string; id: string; mnemonic: string; title: string; subtitle: string }[]` | the results now shown |
| `state` (read-only) | `'idle' | 'loading' | 'error'` | the state machine |
| `lastError` (read-only) | `{ code: string; status: number; detail: string } | null` | the last error |

| Method | Effect |
|---|---|
| `search(q?` | search now (the box shows `q`); resolves with the results |
| `clear(box?` | empty the list, and the box unless `box` is false |

| Event (`drishti:`) | `detail` | When |
|---|---|---|
| `error` | `{ code: string; status: number; detail: string; fatal?: boolean; retryInMs?: number }` | a DRS code; `fatal: false` while the element retries by itself or a single visual failed |
| `results` | `{ query: string; results: { kind: string; id: string; mnemonic: string; title: string; subtitle: string }[] }` | a search answered |
| `select` | `{ kind: string; id: string; mnemonic: string; title: string; type: string }` | **cancellable**: the user picked a result; not cancelled, the `target` view (if any) is pointed at it |
| `state` | `{ state: string; previous: string }` | every state change (drives a host's own live indicator) |
| `token-needed` | `{ reason: 'missing' | 'refused' | 'provider-failed' }` | no token provider, the token was refused twice, or the provider threw: sign the user in again and set `tokenProvider` |


**The token provider** (all elements; one per page): `DrishtiElements.configure({ server, tokenProvider })` or `element.tokenProvider = fn`. **`DrishtiElements`** also has `resolve(text)` (a typed command such as `TRD MX-20000001` to `{ref: {kind, id}}`, for your own search box), `invalidateToken()` (your user changed: the next call asks the
provider again), `connections()` (how many live connections the page holds: one per console) and `version`.

### `<drishti-report>`

A **published** report, one **page** of it or one **visual** of it, in a Shadow DOM, drawn with the console viewer's own code. What is in it: the page tabs, the slicers, the business date, bookmarks, *click a mark to filter or highlight*, **cross-filtering** between the visuals, *show as a table*, and, for a report over a live table, a live
bar and redraws as the data ticks (each tick asks the visuals again, as the person looking, so their row rules and masks apply to every update). What is not: drill down and through, *Alert me*, *Show the rows behind this visual*, Export and Subscribe. A 2.1 `perspective-*` visual is drawn as in the viewer, Drishti's own grid of the server's answer (since 2.3.0 no BI page, and no element, loads WebAssembly).
The design is [section 19, its section 18](#18-drishti-report-a-published-report-in-another-web-application).

```html
<drishti-report id="rep" report="file:report:live-trading-desk"
  filters='{"selections":[{"visual":"by-desk","field":"trade.DESK","values":["DESK-RATES"]}]}'></drishti-report>
<drishti-report report="file:report:live-trading-desk" visual="by-desk" chrome="bare"></drishti-report>
```

**`filters`** is the initial state as JSON (and the property reads and sets the same shape): `slicers` (a slicer visual's id to the values it selects, or `{from, to}` for a range), `dates` (`"2026-10-09"` or `{from, to}`: the business date), `selections` (the marks "clicked": `{visual, field, values}`), `carry` (values carried by a drill-through) and `mode` (`filter` or
`highlight`). Anything left out is cleared. Assigning it does what a click would: the visuals ask again, and `drishti:filter-changed` comes back with `source: "api"`.

**`chrome`**: `full` (default) shows the title, page tabs, business date, bookmarks, the filter chips, Reset and the live bar; `bare` shows the visuals only. With `visual` set the element is that one visual, found on whichever page holds it, in a card, and the chrome is off.

**What the host hears** (captured from a real page, the demo host with the *Live trading desk* report; the last two are the report telling its host which columns it masked and what a click selected):

```
page-changed rep  {"page":"desk","title":"Desk","visuals":["trades","notional","mtm","losses","pnl","by-desk","rolling","movers","latest"],"phone":false}
loaded       rep  {"title":"Live trading desk","page":"desk"}
masked       rep  {"columns":["trade.trader"]}
filter-changed rep {"page":"desk","slicers":{},"dates":null,"selections":[{"visual":"by-desk","field":"trade.DESK","values":["DESK-RATES"]}],"carry":[],"mode":"filter","source":"user"}
```

**Masks.** An embedded report **always masks**: `drishti.security.redact` fields and the dataset's masked columns read `•••` whatever the user's roles, so a user with the raw power sees real names in the console and `•••` in your page. `drishti:masked` lists the columns.

**Which reports a host may show.** The host application's **kinds and reports** list (Admin → Embedding): `report:file:report:live-trading-desk` allows one report, `report:*` every report; a host whose list holds only entity kinds may show none; an empty list means everything the user's roles open. Only a **published**
report is embedded. A refusal is `DRS-8005` (`drishti:error`, `fatal: true`, the element shows the code).

**On a phone.** The element measures **its own width**: at 640 px or narrower it draws the report's `layouts.phone` for the page when the report has one ([REPORTS.md section 14](../guides/REPORTS.md#14-phone-and-tablet-layouts)), else the automatic single column, and swaps back as it grows, with no reload. `drishti:page-changed` carries `phone: true` when the arranged page is on screen.

![The report in a host's card at 390 px, with its phone layout](../guides/img/bi-elements/host-phone-390.png)

### `<drishti-about>` and `<drishti-search>`

Two small elements for a host page that has its own layout around a view.

**`<drishti-about>`** is the *About this page* body of a view without the view: where each number comes from (the sources, the lineage, the layers), what is masked for **this** reader and why, and the rules that applied. It asks the console for the same body the console's own drawer shows (`GET /embed/v1/views/{kind}/{id}/about`), draws it sanitised in a Shadow DOM and
opens its links in a new tab on the console. The application needs the scope `embed:about`. Point it at the same entity as a view and put it where your page has room:

```html
<drishti-view id="v" kind="trade" entity="MX-20000001"></drishti-view>
<drishti-about kind="trade" entity="MX-20000001" label="Where these numbers come from"></drishti-about>
```

**`<drishti-search>`** is a search box and a short list of what matches, the console's own type-ahead for the signed-in user: kinds they may not open are left out, fields masked for them (and, because it is an embed call, every field `drishti.security.redact` names) are neither shown nor matched, and the list is cut to the kinds
the host application may show. The application needs the scope **`embed:search`**; the call is `GET /embed/v1/search?q=` (`GET /api/v1/embed/search` on the server), it reads and writes nothing, and it is rate-limited per user and application (`drishti.embed.limits.searches-per-user-per-minute`, default 30 a minute, on top of the call rates; `DRS-8004`
with `Retry-After`). A query shorter than `drishti.embed.search.min-chars` (2) is not searched; at most `drishti.embed.search.max-results` (10) results come back. Results are 44 px targets and everything is drawn as text. Picking one fires the cancellable `drishti:select`; with `target="#v"` the element also points that
`<drishti-view>` at the pick:

```html
<drishti-search placeholder="Find a trade or counterparty" target="#v"></drishti-search>
<drishti-view id="v" kind="trade" entity="MX-20000001"></drishti-view>
<script type="module">
  document.querySelector('drishti-search').addEventListener('drishti:select', (e) => {
    // e.detail = { kind: 'trade', id: 'MX-20000001', mnemonic: 'TRD', title: 'MX-20000001', type: 'entity' }
    e.preventDefault();                       // take it over yourself instead of letting `target` follow
    openInMyApp(e.detail.kind, e.detail.id);
  });
</script>
```

### React, Angular and Vue sample pages

`docs/guides/examples/embed/` holds one host page for each framework, each embedding `<drishti-search>`, `<drishti-view>`, `<drishti-about>` and `<drishti-report>`, wired the way that framework wires custom elements: **React 18** (`createElement`, events bound with a ref, the same in JSX), **Vue 3** (the runtime-only build and render functions, so no
`eval`), **Angular 18** (a standalone component with `CUSTOM_ELEMENTS_SCHEMA`, zoneless). They share `host.js` (the token provider) and `sample.css`, and run against the elements demo host (`tools/elements-demo/server.py`), which serves them at `/samples/<framework>/` under a strict policy (script-src `'self'` and the console, no inline script).

The framework builds are **not in the repository**. `bash docs/guides/examples/embed/fetch-vendor.sh` downloads the pinned versions once (React 18.3.1 UMD, Vue 3.5.43 runtime build, Angular 18.2.14 ES modules, RxJS 7.8.2, tslib 2.8.1; all MIT) into `docs/guides/examples/embed/vendor/` (ignored by git), and the pages load them from there: no CDN, offline afterwards. React 19 has no UMD build; with a bundler the JSX is the one shown in the
comments. Angular has no UMD build either, so its page uses an import map and Angular's JIT compiler, which needs `'unsafe-eval'` and an inline import map: the demo host relaxes its policy for `/samples/angular/` only. A real Angular application is compiled ahead of time (`ng build`) and needs neither; the component is the same.

Events with a colon (`drishti:select`) are bound with `addEventListener` in all three, because Vue and Angular read the colon in a template; the samples bind in `onMounted` / `ngAfterViewInit` / a ref effect. `tests/test_embed_frameworks_browser.py` loads the React and Vue pages (and the Angular one) in Chromium against a real server and checks that each draws the view, the report and the About body, that a search pick changes the view, and that the policy reports no violation;
it skips when the vendor folder is not there. The React page, drawn in Chromium against a real server:

![The React sample page: a search box with its results, the view and its About body](../guides/img/bi-elements/react-sample.jpg)

### Theming and sizing

**Custom properties.** The element's sheets read the console's design tokens, which inherit through the shadow boundary: set them on the element or any ancestor.

| Property | Meaning |
|---|---|
| `--d-bg`, `--d-bg-2`, `--d-surface`, `--d-surface-2` | backgrounds: page, alternate, panel, panel header |
| `--d-ink`, `--d-muted`, `--d-faint` | text: main, secondary, hints |
| `--d-border`, `--d-radius`, `--d-shadow` | frame |
| `--d-link`, `--d-accent`, `--d-accent-strong`, `--d-accent-tint`, `--d-on-accent` | links, emphasis |
| `--d-pos`, `--d-neg`, `--d-ok`, `--d-warn`, `--d-bad` | tones (gains, losses, status); charts take their palette from these |
| `--d-font-sans`, `--d-font-mono` | fonts (the host's fonts work: the element loads none but its icon font) |

`theme="inherit"` uses your values with `light` as the fallback; `terminal`, `light`, `blue`, `green`, `wallstreet`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson` and `terminal-blue` are the console's own. Parts for spacing and borders without reaching into the shadow root: `::part(header)`, `::part(strip)`, `::part(grid)`, `::part(panel)` (views) and
`::part(report)`, `::part(tabs)`, `::part(chips)`, `::part(grid)` (reports).

**Sizing.** Every element is `display: block`, as wide as its container, as tall as its content; give it a width the way you give any block one (a grid cell, a flex item with `min-width: 0`). It is a **size container**: the console's width breakpoints (760 px, 640 px and others) apply to **its own width**, not the page's, so an element in a 360 px
sidebar of a wide page uses the phone layout, and tables scroll inside their panel and never widen your page.

### Cookbook

**Master and detail: a list drives `entity`.** Set the attribute; the element reloads in place and cancels the request it was making.

```js
list.addEventListener('click', (e) => { main.setAttribute('entity', e.target.dataset.id); });
```

**Keep navigation in your application.** A linked entity inside a view fires a cancellable `drishti:navigate`; cancel it and take the user where you like, or let it through and the element shows the entity in place.

```js
main.addEventListener('drishti:navigate', (e) => { e.preventDefault(); router.go('/trades/' + e.detail.id); });
```

**Your own search box.** `DrishtiElements.resolve('TRD MX-20000001')` returns `{ref: {kind, id}}` for a typed command; set `kind` and `entity` from it.

**A live indicator of your own.** `drishti:state` fires on every change (`loading`, `live`, `reconnecting`, `paused`, `error`, and for a report `stale` or `failed`): bind it to your page's own badge. `drishti:tick` says a live update was applied.

**Two elements side by side, one connection.** Elements with the same `server` share one stream; `DrishtiElements.connections()` is `1` however many there are. (A test puts twenty elements on ten entities on one page: one channel stream.)

**A past business date.** `<drishti-view as-of="2026-10-09">` loads a static snapshot (the console's own as-of rule); `as-of="live"` goes back.

**A dark host.** `theme="inherit"` and your own `--d-bg`, `--d-surface`, `--d-ink` on an ancestor, or `theme="crimson-dark"`.

**One panel in a card.** `<drishti-panel kind="trade" entity="MX-20000001" panel="legs">`: the header is off unless you ask for `header="strip"`.

**A report in a card, driven by your filters.** Set `filters` when your own control changes; the visuals ask again:

```js
deskPicker.addEventListener('change', () => {
  rep.filters = { selections: [{ visual: 'by-desk', field: 'trade.DESK', values: [deskPicker.value] }] };
});
rep.addEventListener('drishti:filter-changed', (e) => { if (e.detail.source === 'user') { deskPicker.value = (e.detail.selections[0] || { values: [''] }).values[0]; } });
```

**React.** Elements are plain DOM: set attributes in JSX, bind events with a ref (React 19 passes properties to custom elements; for events use `addEventListener`):

```jsx
function DeskReport({ onFilter }) {
  const ref = useRef(null);
  useEffect(() => { const el = ref.current; el.addEventListener('drishti:filter-changed', onFilter); return () => el.removeEventListener('drishti:filter-changed', onFilter); }, [onFilter]);
  return <drishti-report ref={ref} report="file:report:live-trading-desk" theme="light" />;
}
```

**Angular** (add `CUSTOM_ELEMENTS_SCHEMA` to the module or component). Angular reads a colon in an event binding as a target (`window:resize`), so bind these events with `addEventListener`:

```ts
@ViewChild('rep', { static: true }) rep!: ElementRef<HTMLElement>;
ngAfterViewInit() { this.rep.nativeElement.addEventListener('drishti:filter-changed', (e) => this.onFilter(e as CustomEvent)); }
// template: <drishti-report #rep report="file:report:live-trading-desk"></drishti-report>
```

**Vue 3**: `app.config.compilerOptions.isCustomElement = (t) => t.startsWith('drishti-')`, then `<drishti-report report="file:report:live-trading-desk" @drishti:filter-changed="onFilter" />`. *These recipes follow the frameworks' documented custom-element support; the [sample pages](#react-angular-and-vue-sample-pages) run all three in Drishti's own browser tests.*

**Testing a page that embeds Drishti.** The elements have **open** shadow roots, so a test reaches inside: Playwright's CSS selectors pierce them (`page.locator('#rep [data-visual="trades"] .bi-card-value')`), and `await expect(el).toHaveAttribute('data-state', 'live')` waits for the state machine. A report sets `data-ready="yes"` on itself when its first page has drawn.
`tests/test_embed_report_browser.py` is a complete example: a host on its own origin under a strict policy against a real server.

### Troubleshooting by code

The element puts the code in `drishti:error` (`detail.code`, `status`, `detail`, `fatal`) and shows it in the element when it has nothing else to show. The full list is in [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md); the ones an embedder meets:

| Code | Meaning | What to do |
|---|---|---|
| `DRS-8001` (401) | the embed token is missing, forged, expired or for another audience | check your provider returns a fresh token and the token request's `audience` is the console's public URL; a stream ends with this after `embed.token_grace_seconds` without a renewal |
| `DRS-8002` (403) | this origin is not one of the host application's | register the exact origin (scheme, host, port) in Admin → Embedding; `null` and wildcards are refused unless `drishti.embed.allow-wildcard-origins` |
| `DRS-8003` (403) | the host application is unknown or disabled | it was disabled or deleted in Admin → Embedding |
| `DRS-8004` (429) | over the embed call rate, or the console holds `embed.max_streams` streams | the answer carries `Retry-After`; raise the application's rates or `embed.max_streams` |
| `DRS-8005` (400, or 403 for an unpublished report) | the application may not show this kind or this report | add the kind, `report:<id>` or `report:*` to the application's list; publish the report |
| `DRS-8006` (410) | a version or API major this console no longer serves | pin the version the answer names, or upgrade the element |
| `DRS-5002` (403) | a path the embed scope does not open (for example the report's authoring paths), or a scope the application was not given | give the application the scope (`embed:report` for a report); an embed token never writes |
| `DRS-5010` (401) | the user behind the token is disabled or gone | sign the user in again; tokens are checked against the user on every call |
| `DRS-9001` (404) | no such report for this user (the id is wrong, or it is not in a workspace they can read) | check the id (`file:report:<name>` for shipped content) and that the user may open it in the console |
| `DRS-9031` / `DRS-9034` (422) | the report has errors and cannot be drawn | open it in the designer; the answer names the first error |

Nothing at all (the element stays in `loading`, with no event): the token provider never answered. Open the browser's network tab: the first request is to *your* backend. `window.DrishtiElements.connections()` and `element.state` / `element.lastError` say what the element thinks.

### Client-side renderer and Elements 2.0

**Drishti Render 1.0.0** (2.9.7) paints a Drishti screen **in the browser** from the server's ViewModel JSON, instead of swapping in
the console's HTML (this document, [ADR-022](adr/022-one-renderer-in-the-browser.md)).
It draws every panel kind as the console does (a parity suite compares them on every pack Sutra), with no build step and nothing from a
CDN, and the console's own entity page can draw with it (`ui.render`). **Elements 2.0** is `<drishti-view>` and `<drishti-panel>` on it,
beside the Elements 1.x above (unchanged) for one release ([section 19, its section 19](#19-elements-20-drawn-by-the-client-side-renderer)).

**Elements 2.0, the same page as above with one line changed:**

```html
<script type="module" src="https://drishti.bank.example/embed/v2/elements/2/drishti-elements.js" crossorigin="anonymous"></script>
<script type="module">
  DrishtiElements.configure({ tokenProvider: async () => { const r = await fetch('/api/drishti-token', { cache: 'no-store' });
    const b = await r.json(); return { token: b.token, expiresAt: Date.now() + b.expiresIn * 1000 }; } });
</script>
<drishti-view kind="trade" entity="MX-20000001" theme="light"></drishti-view>
```

Everything else is as in 1.x: the token exchange, the attributes, the events and the slots. What changes inside: the view is the ViewModel
(`/embed/v2/views/...`, with `"contract": "1.0"`), About this page is a side sheet in the element (the `?` key or the head's About
button), the console's column funnels, Chart any table, the Pivot tab and drill-downs work in the element, and `navigation="in-place"`
draws a linked entity in the element with a Back bar. Pin an exact version for subresource integrity:
`/embed/v2/render/1.0.0/drishti-elements.js` (`integrity.json` beside it). A host that enforces Trusted Types names `drishti-render`
as well as `drishti-elements`. Try it with the demo host: `python3 tools/elements-demo/server.py ...`, then `/?elements=2`.

**Try the library:** open **`/render/demo`** on your console (signed in). It renders any view, or pasted ViewModel JSON, in every theme
and navigation mode, lists the events, and prints the quick start for what is on screen.

**One import, one call** (on the console's origin; another origin imports `/embed/v2/render/1.0.0/drishti-render.js` with a token):

```html
<div id="risk"></div>
<script type="module">
  import { render } from '/static/render/1.0.0/drishti-render.js';
  const view = render(document.getElementById('risk'), { kind: 'trade', id: 'IRS-48213' }, { behaviours: 'console', about: true });
  view.container.addEventListener('drishti:navigate', (e) => console.log('opened', e.detail.kind, e.detail.id));
</script>
```

or the tag `<drishti-screen kind="trade" entity="IRS-48213" theme="crimson-dark"></drishti-screen>` after the import.

| API | What it does |
|---|---|
| `render(container, source, options)` | draws `source` (a ViewModel, `{kind, id}`, `{stream: url}` or an async iterable of stream messages) and returns a handle; `handle.ready` settles after the first paint |
| `handle.patch(frame)` / `patch(handle, frame)` | applies a live frame (`strip`, `panel`, `provenance`, `deleted`, `restored`; VIEWMODEL.md section 5) |
| `handle.subscribe()` / `subscribe(handle, url)` | follows the view's stream (default: the console's `/api/stream/{kind}/{id}?raw=1`); with a function `(on) => stop`, your own channel; returns `stop()` |
| `handle.apply(message)` | a streamed screen's message: `skeleton`, `panel-add`, `panel-replace`, `patch`, `done`, `error` |
| `handle.navigate(ref)`, `back()`, `depth` | in-place navigation and its back stack |
| `handle.drawPanel(p)`, `drawStrip(cells)`, `drawAbout(got)`, `drawCharts(scope)`, `about()` | parts for a host that places them itself; the About sheet |
| `handle.setTheme(name)`, `reload()`, `dispose()`, `viewModel` | |

**Options** (all optional): `theme` (a console theme or `inherit`), `navigation` (`in-place` default, `host`, `new-window`, `follow`),
`behaviours` (`library` default; `console`: the console's funnels, Chart any table, the Pivot grid, drills; `host`: your page's own
scripts), `about` (an About sheet in the container), `shadow` (default `true`), `live`, `token` (a bearer token provider), `device`
(`phone`, `tablet`, `desk`), `tables`, `help`, `keys`, `header`, `panels`, `consoleLinks` and `outsideLinks` (`event` default, `follow`,
`new-window`), `server`, `viewUrl`, `streamUrl`, `aboutUrl`, `recordsUrl`, `panelsUrl`. Types: `/static/render/1.0.0/drishti-render.d.ts`.

**Events** on the container: `drishti:navigate` (cancel it to route the link yourself; nothing opens a window or leaves your page
unless you ask), `drishti:row-click`, `drishti:select`, `drishti:loaded`, `drishti:tick`, `drishti:ready`, `drishti:panel`,
`drishti:about`, `drishti:error` (with `code`, `detail` and `fix`).

**Versions:** one immutable folder per version (`/static/render/<version>/`, and `/embed/v2/render/<version>/` for other origins; `1`
or `1.0` answers `302` to the exact one) with `integrity.json` (SHA-384 of every file) for subresource integrity:
`<script type="module" src="/static/render/1.0.0/drishti-render.js" integrity="sha384-…">`. 0.1.0, the preview, stays served unchanged.

## 20. Drishti Elements: the design

Status: steps 0 to 15 of [the build plan](#16-build-plan) are built except step 15 (each has an "as built" note there, saying what differs from this design; steps 11 to 15 are in [one note](#steps-11-to-15-as-built-2026-10-11)):
`<drishti-view>`, `<drishti-panel>` and `<drishti-report>` ([section 18](#18-drishti-report-a-published-report-in-another-web-application)) are built, the library is versioned with an `integrity.json` and an npm package folder with TypeScript declarations, and the developer guide ([section 19](#19-drishti-elements-developer-guide)) and
[ADR-021](adr/021-embedding-is-html-over-the-wire.md) are written. `<drishti-about>`, `<drishti-search>` and the React, Vue and Angular sample pages with their smoke test came with library 1.4.0 ([step 14](#steps-11-to-15-as-built-2026-10-11)). **Not built:** workspaces on `<drishti-view>` and the SharedWorker (step 15). This document is the design and the as-built record; the guide is the reference for a developer using the elements. The decisions marked **Decision:** were taken as recommended unless an "as built" note says
otherwise. Owner: the console (`drishti-console/`), the server's security layer and view pipeline.

### 1. Why

Teams want a Drishti view (a trade, a counterparty, a patient's case) *inside* the application they already use: a
booking screen, a CRM, a case-management tool. Today the only way is an iframe of `/v/{kind}/{id}?embed=1`
(`drishti-console/routes/terminal_routes.py`, `view()`; the workspace panes use exactly that, `workspace.js`, `src()`).
That does not work in many enterprises:

- **Iframes are disallowed** by policy (`frame-src 'none'` in the host's Content-Security-Policy), and the console itself
  forbids being framed by anything but itself (`frame-ancestors 'self'` and `X-Frame-Options: SAMEORIGIN`,
  `drishti-console/core/app.py`, `CSP` and `SecurityHeaders`).
- An iframe carries **the console's session cookie**: third-party cookies are blocked by default in current browsers,
  so the frame shows the sign-in page, and where they are not, the host has handed its page a credential it cannot see.
- An iframe has its **own size** (the host guesses a height), its own scroll, its own focus trap and keyboard; it cannot
  take the host's theme or fonts, and its links open inside the frame.

*Drishti Elements* is a set of standard Web Components the host page puts in its own DOM:

```html
<drishti-view server="https://drishti.bank.example" kind="trade" entity="END-1000008"></drishti-view>
```

It draws the same view as the console, live, with the same masks and permissions, inside a Shadow DOM, sized by the
host's layout, styled by the host's theme variables, and it tells the host what happened through DOM events.

### 2. Goals and non-goals

#### Goals

1. **One custom element per surface**, framework-neutral: `<drishti-view>` first; later `<drishti-panel>` (one panel of
   a view), `<drishti-search>` (the type-ahead and result list), `<drishti-about>` (the *About this page* body).
   Usable from plain HTML, React, Angular and Vue without wrappers (thin optional wrappers may come later).
2. **The same pixels as the console**: the element shows exactly what `/v/{kind}/{id}` shows for the same user, date
   and generation: same panels, same numbers, same masks (`drishti.security.redact`), same refusals.
3. **Live**: the element ticks like the console (DATA_ARCHITECTURE.md); it pauses when nobody can see it and resumes with fresh data.
4. **Dynamic**: changing an attribute (`entity`, `as-of`, `theme`) reloads in place, cancelling what was in flight;
   `refresh()` re-fetches; a dropped connection reconnects with backoff and repaints from a fresh view.
5. **No cookies, no shared secrets in the browser**: the host's backend gets a short-lived, audience-bound, read-only
   embed token for *its* user, server to server, modelled on OAuth 2.0 Token Exchange (RFC 8693).
6. **The host stays in charge**: navigation, errors and masks are events the host may act on; it decides what a click on
   a linked entity does.
7. **Accountable**: every view shown through an element is in the access log with the host application that showed it.

#### Non-goals (for version 1)

- **Writing anything**: no layout mode, no saving a Pivot arrangement, no share, comments, alerts, pins, Calc, Ask
  (the *Ask* box of About this page costs model calls and has its own rate limits), no raw JSON drawer (F9), no export.
  The element is a read-only window. Each can be added later behind its own scope.
- **A second renderer**: see below.
- **Personal arrangements**: the element shows the Sutra's layout, not the user's own (`core/layouts.py` already turns
  them off for `embed`), so a host that places the element can rely on what it will look like.
- **Offline** use, or showing data without a Drishti server.
- **Old browsers**: Custom Elements v1, Shadow DOM, constructable stylesheets, `ReadableStream` and `AbortController`
  are required (every evergreen browser since 2023). No polyfills.

#### Why not an iframe (and what we give up)

| | iframe of `?embed=1` | Drishti Elements |
|---|---|---|
| Allowed by enterprise CSP | often not (`frame-src`) | yes: `script-src` + `connect-src` + `font-src` for the console's origin |
| Credentials | console session cookie (third-party, often blocked) | a bearer embed token from the host's backend |
| Sizing | fixed height, inner scroll | flows in the host layout, `ResizeObserver` |
| Theme, fonts | console's | host's, through `--d-*` custom properties |
| Isolation of host and Drishti code | complete (separate origin) | **partial**: same page, separate shadow root |
| Effort | exists today | this document |

The real price is isolation. An iframe on another origin is a hard wall; a shadow root is a style and DOM-query wall
only: the host's scripts run in the same page and can read the element's open shadow DOM, and Drishti's script runs
with the host's privileges. [Section 10](#10-security-threats-and-answers) takes that seriously: the element trusts
the host page (it is the host's user looking at it), and the host must trust the script it loads from Drishti (SRI).

#### Why not a second renderer (and what we give up)

A JavaScript renderer for the ViewModel (the JSON `/api/v1/views/{kind}/{id}` returns, defined in [VIEWMODEL.md](VIEWMODEL.md)) would make the element
independent of the console. We do not do it:

- **Twenty-two panel kinds, tree rows, pivots, tabs, gauges, surfaces** are rendered by one set of Jinja macros
  (`drishti-console/web/templates/_macros/panels.html`, `panel()` and its helpers). Live frames already reuse those macros
  (`_panel_html` in `drishti-console/routes/api_routes.py`) so that "live and static views look identical". A second
  renderer would be a second implementation of every panel kind, every accessibility detail and every fix, forever.
- The console is where masked text, number formats and the *No data available* rules are already settled.

What we give up: the element needs a console (not just a server) to talk to, payloads are HTML rather than compact JSON
(about 3 to 6 times larger before compression, see [section 11](#11-performance)), and the host cannot restyle a
panel's inner structure beyond the custom properties and parts we expose. We accept all three.

**Decision 1:** the embed API lives on the **console**, which renders panels to HTML with the existing macros (HTML over
the wire), and the element is a thin client that swaps server-rendered fragments and draws charts from data.
Recommended. The alternative (a ViewModel renderer in JavaScript) is rejected above.

### 3. What the code already gives us

The direction was checked against the code. The parts the element reuses, and the gaps:

| Piece | Where | What it gives | Gap for embedding |
|---|---|---|---|
| Panel HTML | `_macros/panels.html` `panel(p)` | every panel kind, server-rendered, no inline styles or scripts (the console's CSP is `style-src 'self'; script-src 'self'`) | links are relative (`/v/…`, `/help/rachana-reference#…`, `/export/…`); some affordances need cookies |
| Live frames as HTML | `api_routes.py` `_view_event`, `CHART_KINDS = {"line", "area"}` | `frame` patches: `strip`, `panel` (with `html`, or chart `data` for line/area), `provenance`, `deleted`, `restored` | none: reused unchanged |
| The `view` event | `_view_event` | on a (re)connection, reduced to `{"generation": n}`; pages repaint by reloading (`live.js`: `location.reload()`) | the element must fetch a fresh view itself: the embed API's `GET view` (no replay) |
| One channel per browser | `api_routes.py` `channel()`, `channel_change()`, `CHANNELS` | `{ch, d}` multiplexing, add/remove without reconnecting, heartbeat `: hb`, `end`, `gone` with DRS code, per-channel cap (`live.max_subscriptions`) | cookie identity (`ident(request)`), owner check by user only; the logic is one 160-line closure |
| The hub | `live-hub.js` `Hub(opts)` | reconnect with backoff (2 s doubling to 30 s), resync of keys, connect timer; takes `EventSource` and `fetch` **as options** | a SharedWorker must be same-origin with the page, so cross-origin it cannot run in one; `EventSource` cannot send `Authorization` |
| Patch application | `live.js` | strip cells, panel swap (keeps tab choice, user's span), flash, deleted banner | `document.querySelector`, globals `window.drishti`, `window.DrishtiChannel`, `location.reload()` |
| Charts | `view.js` (line/area: `drawCharts`, `updateChart`), `charts.js` (waterfall, histogram, scatter, candlestick, graph from `data-xchart`), surfaces (ECharts GL loaded on demand) | ECharts with SVG renderer; colours from `--d-*` tokens | `getComputedStyle(document.documentElement)`, `document.querySelectorAll`, `window.resize`, links built as `/v/…` |
| Panel zoom | `zoom.js` (root-scoped `init(root)`): a `pnl-zoom` class lifts one panel over the view; in the element the host (`contain: content`) makes it fill the element's own box; no hash, no page scroll lock there | CSS classes and attributes only | `document` listeners and `location.hash` are used only when `root` is the document |
| Table behaviour | `tables.js` (sort, filter, page, keys), `tree-rows.js` | everything a table does | a `MutationObserver` on `document.body`: it never sees inside a shadow root |
| Pivots | `pivot-engine.js` (pure), `pivot-grid.js` (DOM, mostly local), `pivot.js` (975 lines; `fetch('/api/pivot/…')`, saved arrangements) | the Pivot tab | relative URLs, cookie fetches, writes |
| About | `about.js`, `about-hints.js`, `GET /v/{kind}/{id}/about` (fragment) | the drawer, panel `?` popovers, field hints | `document.getElementById('aboutDrawer')`, document-wide `keydown` |
| Masks | server: `Entitlements.redactor`, `DataNode.masked()`; config `drishti.security.redact` | masked fields read `•••` everywhere, for roles without `raw` | the ViewModel does not say *how many* fields were masked |
| Tokens | server: `TokenVerifier` (console HS256), `TokenScopes` (personal `drk_` tokens; GET always readable), `IdTokenVerifier` (OIDC) | three kinds of bearer today | no audience-bound, host-bound token kind |
| Access log | `AccessRecorder` (`/api/v1/views/{kind}/{id}` → `view`, detail `share:sh_…` for shares) | who looked at what | no host application in the detail |
| Error codes | `drishti-common/.../ErrorCode.java` | DRS registry; `DRS-5003` is the console's "backend unreachable" (API_GUIDE.md) | no embed codes; `DRS-8xxx` is free |
| Asset versions | `core/app.py`, `ASSET_V` (hash of js/css) | cache-busting `?v=` | not immutable URLs; no SRI |
| CORS | nowhere (grep finds none) | — | needed, per host application |

**A spike validated the riskiest browser assumptions** (Chromium, a host page on `127.0.0.1:28611` with
`script-src 'self' http://localhost:28612; style-src 'self'; connect-src http://localhost:28612; font-src
http://localhost:28612`, an element served from `localhost:28612`; scratch code, not committed):

| Assumption | Result |
|---|---|
| CSS fetched from the console and adopted with `new CSSStyleSheet().replaceSync()` styles the shadow root, also fragments swapped in later | yes, even with the console **not** in the host's `style-src`: constructable sheets are not subject to `style-src` in Chromium |
| `@font-face` inside a shadow root's sheet loads the icon font | **no**: fonts must be registered on the document (`document.fonts.add(new FontFace(…))`, which works under `font-src`) |
| `fetch` + `ReadableStream` reads an SSE stream cross-origin with `Authorization` (CORS preflight) | yes; three frames parsed and swapped |
| ECharts (vendored `echarts.min.js`, SVG renderer) draws inside a shadow root under a strict CSP | yes |
| A click on a fragment's `<a href="/v/…">` can be turned into a composed, cancellable `drishti:navigate` | yes |

Firefox and WebKit were not installed: step 0 repeats the spike in both ([section 16](#16-build-plan)).

### 4. Architecture

```
 HOST APPLICATION (https://crm.bank.example)                    DRISHTI (https://drishti.bank.example)
 ┌───────────────────────────────────────────────┐
 │ host backend                                  │  1 POST /api/v1/embed/token  (RFC 8693, client auth)
 │  (knows its signed-in user)  ─────────────────┼──────────────────────────────────────┐
 │                               ◄───────────────┼── {access_token (5 min), expires_in} │
 └───────────────▲───────────────────────────────┘                                      ▼
                 │ 2 token to its own page                              ┌──────────────────────────────┐
 ┌───────────────┴───────────────────────────────┐                      │ drishti-server               │
 │ host page (browser)                           │                      │  EmbedTokenService (mint)    │
 │  <script type=module                          │                      │  TokenFilter: embed tokens   │
 │    src=…/elements/1.0.0/drishti-elements.js   │ 3 module + CSS +     │   (aud, app, origin, scopes, │
 │    integrity=sha384-…>                        │   fonts (immutable)  │    user's roles now, masks)  │
 │                                               │◄─────────────────┐   │  ViewStream, TopicHub, …     │
 │  <drishti-view kind entity as-of theme>       │                  │   │  AccessRecorder: embed:<app> │
 │   └ #shadow-root (open)                       │                  │   └──────────────▲───────────────┘
 │      adoptedStyleSheets: tokens+panels (one   │                  │                  │ bearer = embed token
 │      shared sheet), header, strip, panels     │ 4 GET /embed/v1/views/{kind}/{id}   │ (passed through)
 │      ECharts (lazy)                           │──────────────────┼──►┌──────────────┴───────────────┐
 │  <drishti-view …> (a second one)              │   html + chart   │   │ drishti-console              │
 │                                               │   data + gen     │   │  /elements/** static, CORS   │
 │  DrishtiElements connection (one per page):   │                  │   │  /embed/v1/** EmbedAuth:     │
 │   fetch-stream "EventSource" + Hub            │ 5 GET /embed/v1/channel?s=view:…    │   verify, CORS per app,      │
 │   (live-hub.js Hub, injected transport)       │◄═════════════════╧═══│   rate limits                │
 │   keys: view:trade/A, view:trade/B            │   event: frame {ch,d} (same frames  │  ChannelSession (shared with │
 │                                               │   as /api/channel; panels as HTML)  │   /api/channel)              │
 │  events → host: drishti:loaded, :tick,        │ 6 POST /embed/v1/channel/{id}       │  _macros/panels.html         │
 │   :navigate, :error, :masked, :state          │   {add, remove}                     │  _view_event (unchanged)     │
 └───────────────────────────────────────────────┘                     └──────────────────────────────┘
```

Three rules hold the design together:

1. **The server is the only judge of what a user may see.** The console never decides rights for an embed call; it passes
   the embed token through to the server, which checks it on every call ([section 8](#8-embed-tokens-the-token-exchange)).
2. **The console renders, the element places.** Every byte of panel HTML comes from `panel(p)`; the element's own DOM is
   the frame around it (header, strip, grid, banners), built from the same template parts as `view.html`.
3. **One connection per page.** However many elements a page holds, they share one channel, as the console's tabs do.

### 5. The element API

#### 5.1 Loading the library

```html
<script type="module" src="https://drishti.bank.example/embed/v1/elements/1.3.0/drishti-elements.js"
        integrity="sha384-…" crossorigin="anonymous"></script>
```

or from npm (`npm i @drishti/elements`, `import '@drishti/elements'`; set `server`, because the module's own origin is then not the console); the npm package and the console serve the same
build ([section 13](#13-distribution-and-versioning)). The path is `/embed/v1/elements/<version>/drishti-elements.js`; `…/1/…` and `…/1.1/…` redirect to the exact version, whose SRI value is in `…/integrity.json`. Importing defines the elements (`customElements.define`) once;
importing twice is harmless.

#### 5.2 `<drishti-view>` attributes

| Attribute | Values | Default | Notes |
|---|---|---|---|
| `server` | the console's base URL | the origin the module was loaded from | every element on a page that shares a `server` shares one connection |
| `kind` | an entity kind (`trade`, `counterparty`, …) | required | |
| `entity` | the entity id | required | change it to show another entity: reload in place |
| `as-of` | `live` or a business date `YYYY-MM-DD` | `live` | a past date is a static snapshot (as in the console); sent as `X-Drishti-As-Of` |
| `known-at` | an ISO instant | none | bitemporal "as known at" (`X-Drishti-Known-At`) |
| `theme` | `inherit`, or a Drishti theme: `terminal`, `light`, `blue`, `green`, `wallstreet`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson`, `terminal-blue` | `inherit` | `inherit` uses the host's `--d-*` values, falling back to `light` |
| `header` | `full`, `strip`, `none` | `full` | title + strip, strip only, or panels only |
| `density` | `compact`, `comfortable` | the sheet's | row padding of tables (a host-level choice; the view's own look otherwise) |
| `panel` | a panel id | none | the attribute of `<drishti-panel>` (step 14, built): accepted and ignored by `<drishti-view>` (use `panels` to show some) |
| `panels` | comma-separated panel ids | all | show only these panels (the view is still built whole; see `<drishti-panel>` for one panel) |
| `live` | `auto`, `off` | `auto` | `off` loads once and never subscribes |
| `locale` | a BCP 47 tag | the browser's | pack text (`?locale=` of About) |
| `label` | text | the view's title | the element's accessible name (`role="region"`) |
| `controls` | `legs=2&when=2026-10-01..2026-12-31` | none | the values of the Sutra's [controls](RUPAKA.md#81-interactive-sutras-and-slicers-controls-view-state-and-filters) (`ctl.<id>`; the prefix is optional): the view is built with them, and a change reloads. The element draws the controls itself inside its shadow root; a reader's change updates the property (below) and never touches the host page's address |

Attributes are reflected to properties of the same name in camelCase (`asOf`, `knownAt`).

#### 5.3 Properties

| Property | Type | Notes |
|---|---|---|
| `tokenProvider` | `() => Promise<string \| {token, expiresAt}>` | **required** when the console has security on. Called before the first request, before expiry (60 s early), and once after a `401 DRS-8001`. Set it on the element, or once for the page: `DrishtiElements.configure({server, tokenProvider})` |
| `state` | read-only string | `idle`, `loading`, `live`, `static`, `reconnecting`, `paused`, `deleted`, `error` (the state machine, [section 7](#7-loading-reloading-refreshing-reconnecting)) |
| `view` | read-only object | `{ref, title, generation, provenance, panels: [{id, kind, title}]}` once loaded |
| `lastError` | read-only `{code, status, detail}` or null | |
| `controls` | string | the controls' values now, as the attribute writes them (`legs=2`): what the reader set, or the attribute's value until they do |
| `theme`, `density` | string | the attributes of the same name |

#### 5.4 Methods

| Method | Effect |
|---|---|
| `refresh()` | fetch the view again (same entity), keep the subscription, swap what changed; returns a promise for `loaded` |
| `reload()` | full reload: cancel in-flight requests, drop the subscription, fetch, subscribe (what an attribute change does) |
| `pause()` / `resume()` | give the subscription back / take it again (the element also does this itself when hidden) |
| `openAbout(panelId?)` | open the About drawer (inside the element), optionally at a panel |
| `focusPanel(id)` | move the focus to a panel (as `Alt+1..9` do in the console) |

#### 5.5 Events

All are `CustomEvent`s dispatched on the element, `bubbles: true, composed: true`.

| Event | `detail` | Cancellable | When |
|---|---|---|---|
| `drishti:loaded` | `{ref: {kind, id}, generation, title, live}` | no | a view was loaded and painted (first load, reload, refresh) |
| `drishti:paint` | `{ref: {kind, id}, generation}` | no | the shadow root was rebuilt (every `loaded` is preceded by one; a live resync after the stream's first look or a reconnect is a `tick`, not a `paint`: [7.3a](#73a-live-updates-in-the-element-what-repaints-and-what-patches)) |
| `drishti:tick` | `{generation, seq, latencyMs, changed: {strip: [index], panels: [id]}}` | no | a live frame was applied |
| `drishti:navigate` | `{kind, id, href, panel, source: "link" \| "chart" \| "graph"}` | **yes** | the user activated a linked entity. Default (not cancelled): set `entity` (and `kind`) and reload in place. `href` is the absolute console URL, for a host that prefers to open the console |
| `drishti:error` | `{code, status, detail, fatal, retryInMs}` | no | a DRS code from [section 6.7](#67-errors); `fatal: false` while the element retries by itself |
| `drishti:controls` | `{query: "ctl.legs=2&ctl.when=...", changed: [id]}` | no | a reader changed a control (also dispatched by the console page on `document`). The element asks the console for only the panels that read it (`GET .../panels`, [6.8](#68-get-embedv1viewskindidpanels--some-panels-with-controls)), moves its live subscription to `view:kind/id?ctl.…` and updates `controls`. `drishti:controls-done` (`{changed, panels, ms}`) follows when the panels are in |
| `drishti:masked` | `{count, panels: [id]}` | no | the view the user is shown has masked fields (after load and when it changes) |
| `drishti:state` | `{state, previous}` | no | every state change (drives a host's own live indicator) |
| `drishti:token-needed` | `{reason: "missing" \| "refused" \| "provider-failed"}` | no | there is no token provider, the server refused the token twice, or the provider threw/rejected: the host signs the user in again and sets `tokenProvider` (an element in `error` after a 401 reloads by itself when the provider is set) |

#### 5.6 Styling

**CSS custom properties.** The element's sheet reads the console's design tokens (`drishti-console/web/static/css/tokens.css`),
which inherit through the shadow boundary, so a host sets them on the element or any ancestor:

| Property | Meaning |
|---|---|
| `--d-bg`, `--d-bg-2`, `--d-surface`, `--d-surface-2` | backgrounds: page, alternate, panel, panel header |
| `--d-ink`, `--d-muted`, `--d-faint` | text: main, secondary, hints |
| `--d-border`, `--d-radius`, `--d-shadow` | panel frame |
| `--d-link`, `--d-accent`, `--d-accent-strong`, `--d-accent-tint`, `--d-on-accent` | links, emphasis |
| `--d-pos`, `--d-neg`, `--d-ok`, `--d-warn`, `--d-bad` | tones (gains, losses, status); charts take their palette from these |
| `--d-emph-bg`, `--d-highlight`, `--d-glow` | emphasised strip cell, live flash |
| `--d-font-sans`, `--d-font-mono` | fonts (the host's fonts work: the element does not load fonts other than its icon font) |

The full list (27 tokens) is generated into the developer guide from `tokens.css`; a token not listed there is not part
of the contract.

**Parts.** `::part(header)`, `::part(title)`, `::part(strip)`, `::part(grid)`, `::part(panel)`, `::part(panel-header)`,
`::part(banner)` let a host adjust spacing and borders without reaching into the shadow root.

**Sizing.** The element is `display: block`, as wide as its container; its height is its content (`max-height` plus
`overflow: auto` on the host if it wants a scroll). The panel grid uses the Sutra's spans (`c-span-N`) relative to the
element's width (container queries, not the viewport).

**Responsive behaviour (container queries).** The element is a size container (`container: drishti / inline-size` on the host),
and the generated sheet turns each width `@media` rule of the console into `@container drishti (...)` (same breakpoints:
1700, 1400, 1100, 1000, 900, 760, 640 and 560 px). So a view in a 360 px sidebar of a wide desktop page uses the phone layout
(one column, a two-column key-figure strip, wrapped tab heads), and a full-width view on a phone does the same; the host
page's viewport is never consulted. Non-width media (colour scheme, print, hover, pointer, motion, contrast) are kept.
Hosts size the element with ordinary layout: give it a width (a grid cell, a flex item with `min-width: 0`, a fixed-width
column); do not make it `display: inline` or shrink-wrapped, because a size container has no intrinsic width of its own
(`display: block`, as wide as its container, is the default). Give a flex or grid cell `min-width: 0` so a wide table
cannot widen the column.

*Touch.* A panel's `?` and a column or field hint open by tap (a tap pins the hint; no hover is needed). Where the element is
at most 640 px wide, or the device's primary pointer is coarse, the element's controls (`?`, page buttons, filter buttons,
About, the drawer's close and "more") are at least 44 by 44 px. Tables scroll sideways inside their panel and never widen the
host page. The About drawer is a right-hand side drawer sized to the element (at most 420 px, 92 % of the element's width) and
becomes a bottom sheet the element's full width when the element is 640 px or narrower; as it is positioned inside the
element, opening it scrolls the host page so the element's bottom edge is in view. Charts resize with the element
(`ResizeObserver`). Tested in Chromium, Firefox and WebKit: a 390 px touch host with a full-width element, a 1800 px host with the
element in a 360 px sidebar, and taps on `?`, a field hint and About (`test_embed_elements_browser.py`).

#### 5.7 Slots

| Slot | Shown | Default |
|---|---|---|
| `loading` | while loading and no view has been painted yet | nothing (the element dims the view it has, while it reloads) |
| `empty` | no `kind`/`entity` yet (state `idle`) | nothing |
| `error` | an error with no view to keep | the DRS code and detail (`role="alert"`); with a slotted node, the host's content replaces it, and `drishti:error` still fires |
| `toolbar` | **not built** (reserved) | |

#### 5.8 Keyboard

Shortcuts work **only while the focus is inside the element** (listeners on the shadow root, never on `document`):
`?` opens About, `Esc` closes it, `Z` zooms the focused panel to the element's own box (`Esc` restores; `Shift+Z` asks for full screen), `Alt+1..9` move between panels, the table keys of `tables.js` inside a table. The
console's global keys (`F1` guide, the command line, `Alt+0..4` workspace panes, `Alt+L` layout mode) are not bound.

#### 5.9 Examples

Plain HTML:

```html
<script type="module" src="https://drishti.bank.example/elements/1.0.0/drishti-elements.js" integrity="sha384-…" crossorigin="anonymous"></script>
<drishti-view id="trade" kind="trade" entity="END-1000008"></drishti-view>
<script type="module">
  DrishtiElements.configure({
    server: 'https://drishti.bank.example',
    tokenProvider: async () => (await fetch('/drishti-token', { credentials: 'same-origin' })).json(),  // your backend
  });
  const v = document.getElementById('trade');
  v.addEventListener('drishti:navigate', e => {            // keep the user in the CRM for counterparties
    if (e.detail.kind === 'counterparty') { e.preventDefault(); location.href = '/clients/' + e.detail.id; }
  });
  document.querySelectorAll('.trade-row').forEach(r => r.addEventListener('click', () => { v.entity = r.dataset.id; }));
</script>
```

React (19 sets properties on custom elements natively; a `drishti:` event name cannot be written as a JSX prop,
so listeners go through a `ref`):

```jsx
import '@drishti/elements';
export function TradeView({ id, onNavigate }) {
  const ref = useRef(null);
  useEffect(() => {
    const el = ref.current, on = e => onNavigate(e);
    el.addEventListener('drishti:navigate', on);
    return () => el.removeEventListener('drishti:navigate', on);
  }, [onNavigate]);
  return <drishti-view ref={ref} kind="trade" entity={id} theme="inherit" tokenProvider={getDrishtiToken} />;
}
```

(React 18 also sets `tokenProvider` in the `useEffect`; the developer guide has the snippet.)

Angular (add `CUSTOM_ELEMENTS_SCHEMA` to the component; Angular reads `(a:b)` as an event *target*, so `drishti:`
events are bound in code):

```html
<drishti-view #view kind="trade" [attr.entity]="tradeId" [tokenProvider]="tokens.get"></drishti-view>
```

```ts
@ViewChild('view') view!: ElementRef<HTMLElement>;
ngAfterViewInit() { this.view.nativeElement.addEventListener('drishti:navigate', e => this.onNavigate(e as CustomEvent)); }
```

Vue 3 (`compilerOptions.isCustomElement: tag => tag.startsWith('drishti-')`):

```html
<drishti-view kind="trade" :entity="tradeId" .tokenProvider="getToken" @drishti:loaded="ready = true" />
```

### 6. The embed API

Served by the console under `/embed/v1/`. Every call carries `Authorization: Bearer <embed token>` and is subject to
CORS ([section 10](#10-security-threats-and-answers)). Every response says its contract version in
`Drishti-Embed-Api: 1.0` and has `Cache-Control: no-store` (views are per user).

#### 6.1 `GET /embed/v1/views/{kind}/{id}` — the initial view

Query: `asOf`, `knownAt`, `panels` (ids), `header` (`full|strip|none`), `locale`, and `ctl.<id>` (the values of the Sutra's controls, as in
`GET /api/v1/views/{kind}/{id}?ctl.legs=2`). The console asks the server for the
ViewModel (`backend.view`, the same call `/v/{kind}/{id}` makes), renders the frame parts and each panel with the
existing macros, and answers:

```json
{
  "api": "1.0",
  "ref": {"kind": "trade", "id": "END-1000008"},
  "mnemonic": "TRD",
  "title": {"pill": "Commodity · Commodity forward", "id": "END-1000008", "with": {"text": "Harbor Point Bank", "link": {"kind": "counterparty", "id": "CP-1042"}}},
  "head": "<section class=\"vhead\">…</section>",
  "strip": [{"label": "Notional", "text": "USD 231,000,000", "path": "$.currency"},
            {"label": "MTM (USD)", "text": "+2,148,146", "tone": "pos", "emphasis": true, "path": "$.mtm"}],
  "banners": ["<p class=\"asof-banner stale-banner\" role=\"alert\">…</p>"],
  "panels": [
    {"id": "cashflows", "kind": "table", "area": "main", "span": 8, "height": 0, "html": "<section class=\"pnl c-span-8\" id=\"p-cashflows\" …>…</section>"},
    {"id": "mtm-history", "kind": "line", "area": "main", "span": 12, "height": 0, "html": "<section class=\"pnl\" id=\"p-mtm-history\" …><div class=\"chart\" data-chart=\"…\"></div>…</section>"},
    {"id": "built", "kind": "provenance", "area": "right", "span": 12, "height": 0, "html": "…"}
  ],
  "provenance": {"layout": "Sutra cmd-forward v1 + inference", "source": "endur-comm", "generation": 29,
                 "fetchedAt": "2026-10-01T01:01:40.877Z", "live": true, "businessDate": null, "stale": false, "masked": 2},
  "masked": {"count": 2, "panels": ["parties"]},
  "subscribe": "view:trade/END-1000008",
  "controls": [{"id": "legs", "kind": "multi-select", "placement": "header", "panel": "schedule", "value": "2", "reads": ["schedule"], "...": "..."}],
  "selects": [{"panel": "byleg", "by": "legs", "filter": ["schedule"]}],
  "assets": {"css": "/elements/1.0.0/drishti-view.css", "charts": "/elements/1.0.0/charts.js", "echarts": "/static/vendor/echarts/echarts.min.js?v=…"},
  "timings": {"fetch": 0.09, "layout": 0.13, "links": 0.21, "bind": 0.15, "total": 0.57, "render": 1.8}
}
```

- `head` and the `strip` cells: `head` is the `vhead` section of `view.html` rendered with `embed=True` (no Alert, Pin,
  Share, Export, Print or admin links); `strip` is also given as data because live `strip` patches update cells by index
  (`live.js`, `applyStrip`).
- `subscribe` is the channel key, or `null` for a static view (a past `asOf`, a source that is not live): the element
  then does not subscribe, as `live.js` does not (`data-static`).
- `provenance.masked` is new on the server ([step 3](#16-build-plan)): the number of masked values in the ViewModel.
- Links inside fragments stay console-relative (`/v/counterparty/CP-1042`); the element resolves them ([section 7.5](#75-links-and-navigation)).

#### 6.2 `GET /embed/v1/channel?s=…` — the live stream

Exactly the frames of `/api/channel` ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#one-connection-per-browser)): `text/event-stream`, events
`channel` (`{"ch": "", "d": {"id": "<cid>", "subs": [...]}}`, no `who`), `view` (`{"ch": "view:trade/X", "d": {"generation": 30}}`),
`frame`, `gone`, `end`, heartbeats `: hb`. Only `view:` keys are accepted (no `alerts`, no `monitor:`). A `frame`:

```json
{"ch": "view:trade/END-1000008",
 "d": {"seq": 2, "generation": 31, "latencyMs": 1.19, "p99Ms": 1.2,
       "patches": [{"op": "strip", "index": 4, "cell": {"label": "MTM (USD)", "text": "+2,095,229", "tone": "pos", "emphasis": true, "path": "$.mtm"}},
                   {"op": "panel", "panel": {"id": "built", "kind": "provenance"}, "html": "<section class=\"pnl\" id=\"p-built\" …>…</section>"},
                   {"op": "panel", "panel": {"id": "mtm-history", "kind": "line", "data": {…}}},
                   {"op": "provenance", "provenance": {"generation": 31, "live": true, "masked": 2}}]}}
```

`deleted` (`{"op": "deleted", "at": "…"}`) and `restored` patches as today. The element reads the stream with `fetch`
and a `ReadableStream` (it must send `Authorization`; `EventSource` cannot, and a token in the URL would land in proxy
logs). `Last-Event-ID` is not honoured: a reconnection starts afresh (no replay), as the console does.

#### 6.3 `POST /embed/v1/channel/{cid}` — add and remove

`{"add": ["view:trade/B"], "remove": ["view:trade/A"]}` → `{"ok": true}`, as `channel_change` today. The owner check is
the user **and** the host application of the token (a token of another app for the same user gets `404 DRS-5001`).

#### 6.4 `GET /embed/v1/views/{kind}/{id}/about` — the About body

The HTML fragment `GET /v/{kind}/{id}/about` returns today (`terminal/_about.html`), without the *Ask* layer
(`layer_ask`) and without the Discussion tab. Query `generation`, `locale`, `panel`.

#### 6.5 `GET /embed/v1/views/{kind}/{id}/panels/{panel}/records` — rows for a Pivot

The rows the Pivot tab works on (`/api/pivot/records/…` today, the server's `…/panels/{panel}/records`), so pivots work
in the element; arrangements live in the element's memory only (no `PUT /saved`).

#### 6.8 `GET /embed/v1/views/{kind}/{id}/panels` — some panels, with controls

`?panels=schedule,legs&ctl.legs=1`: the panels named, as ready HTML built with the controls' values (`panels=-`: none), the strip, and the
`controls` (their options and values as the server resolved them: a cascading child's options move with its parent). The server builds only
those panels (`GET /api/v1/views/{kind}/{id}?panels=...`); the document is fetched and the layout resolved each time. What the element asks
for when a reader changes a control; the same answer as the console's own `GET /v/{kind}/{id}/panels`, for the embed caller's masked
identity. Measured on a warm local server, `MX-20000001`: the whole view 17.0 ms and 12.8 KB; `panels=schedule` 10.1 ms and 5.5 KB; no panel
8.2 ms and 2.5 KB.

The element's controls are `static/js/controls.js` started on its shadow root (a `controls` enhancer, allow-listed in `routes/embed_routes.py`);
its sheet is `css/controls.css`, part of `drishti-view.css`. Tested in Chromium, Firefox and WebKit: `drishti-console/tests/test_embed_controls_browser.py`.

#### 6.6 `GET /embed/v1/resolve?text=…` — a typed command to an entity

What the console's `POST /api/resolve` does (`backend.command`, the server's `POST /api/v1/command`), for a host that
offers its own search box: `TRD MX-20000001`, `CPTY CP-1042` or a bare id answer
`{"ref": {"kind": "trade", "id": "MX-20000001"}, "mnemonic": "TRD", "title": "…"}`, or `404 DRS-1001` / `400 DRS-4001`.
The server allows `POST /api/v1/command` to an embed token as a read and does not add it to the user's command
history. (Optional for hosts: one that already knows kind and id sets the attributes directly.) The element exposes it
as `DrishtiElements.resolve(text, {server})`.

#### 6.7 Errors

A problem body as everywhere in Drishti, `{"code": "DRS-…", "detail": "…"}`, with the HTTP status of the registry
(`drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java`). In a stream, a `gone` event carries the same body.

| Code | Status | When | Element |
|---|---|---|---|
| `DRS-1001` | 404 | no such entity (or one the user may not reach: the two look the same) | `error` (fatal), `error` slot |
| `DRS-1002`, `DRS-1003`, `DRS-1004` | 404, 502, 504 | no source for the kind, the source failed or timed out | `error` (not fatal for 502/504: retries with backoff) |
| `DRS-1007` | 400 | `known-at` on a store with no earlier versions | `error` (fatal) |
| `DRS-4002` | 500 | the view could not be built | `error` |
| `DRS-4003` | 400 | bad `as-of` | `error` (fatal) |
| `DRS-5002` | 403 | the user's roles do not open the kind | `error` (fatal) |
| `DRS-5003` | 503 | the console cannot reach the server (the console's own code), or a channel over `live.max_subscriptions` | retry |
| `DRS-5010` | 401 | the user behind the token is disabled or gone | `error` (fatal) |
| `DRS-8001` **new** | 401 | embed token missing, malformed, expired, wrong audience | ask `tokenProvider` once, then `error` |
| `DRS-8002` **new** | 403 | the request's `Origin` is not one of the host application's origins | `error` (fatal) |
| `DRS-8003` **new** | 403 | the host application is unknown or disabled | `error` (fatal) |
| `DRS-8004` **new** | 429 | over the host application's or the user's embed rate (`Retry-After` set) | retry after |
| `DRS-8005` **new** | 400 | the kind is not allowed for this host application | `error` (fatal) |
| `DRS-8006` **new** | 410 | the element's contract version is no longer served (`Drishti-Embed-Api` request header) | `error` (fatal): upgrade |

The token endpoint ([section 8](#8-embed-tokens-the-token-exchange)) answers in RFC 6749 §5.2 form
(`{"error": "invalid_client", "error_description": "…", "code": "DRS-8003"}`), so OAuth client libraries understand it.

### 7. Loading, reloading, refreshing, reconnecting

#### 7.1 States

```
                 connectedCallback / attribute set (kind, entity both present)
   idle ───────────────────────────────────────────────► loading ──── GET view ok, subscribe=null ──► static
     ▲                                                     │  │                                         │
     │ disconnectedCallback (abort all, unsubscribe)       │  └─ GET view failed ─► error ◄─────────────┤ fatal
     │ from any state                                      │        (fatal: stay;  │                    │
     │                                                     │         else retry ───┘ backoff)           │
     │                         GET view ok, subscribe=key  ▼                                            │
     │      ┌───────────────────────────────────────────► live ◄──────── resume: GET view, subscribe ───┤
     │      │ channel back: GET view (fresh), swap          │  │  │                                     │
     │      │                                               │  │  └─ hidden 10 s / off-screen ─► paused ─┘
     │  reconnecting ◄── channel error, heartbeat lost 30 s ┘  │
     │      (backoff 2 s → 30 s, jitter)                       └─ patch op=deleted ─► deleted (restored: reload)
     │
     └── attribute change in any state: reload (abort in flight → loading)
```

- **Load.** Needs `kind` and `entity`. One `AbortController` per load: an attribute changed mid-load aborts the
  `GET view` and its token request, and the stale answer is never painted (each load has a sequence number; only the
  latest may paint). Attribute changes in the same task are coalesced (a microtask), so setting `kind` then `entity`
  loads once.
- **Paint.** Build the frame (`head`, `banners`, grid with `vmain` and `vright` as in `view.html`), insert the panel
  fragments, run the enhancers on the shadow root ([section 12](#12-console-scripts-as-root-scoped-modules)), draw charts.
  Then `drishti:loaded`, and `drishti:masked` when `masked.count > 0`.
- **Subscribe.** Add the view's key to the page connection. Frames before the paint is done are applied after it; a
  frame whose `generation` is not newer than the painted view's is dropped.
- **Refresh** (`refresh()`). `GET view` again, keep the subscription; panels whose HTML is unchanged are not touched
  (compare a hash), so a table's sort, page and filter survive; changed panels are swapped as live frames are.
- **Reload** (an attribute change, `reload()`, a `restored` patch). Abort, unsubscribe the old key, load from scratch.
  `theme` alone is not a reload: it switches the theme sheet and redraws charts (`drishti:theme` today).
- **Reconnect.** The connection reports an error or 30 s without bytes (two missed heartbeats): every live element goes
  to `reconnecting` and keeps showing what it has. The hub retries with backoff (2 s doubling to 30 s, the console's
  `Hub.retry`, plus ±20 % jitter so a thousand host pages do not return in step). When the channel is back, each element
  does a fresh `GET view` and swaps (the channel's `view` event marks the moment; there is no replay).
- **Pause.** The element stops listening when the page has been hidden for 10 s (`visibilitychange`, the console's
  `HIDDEN_GRACE_MS`) **or** the element has been off-screen for 10 s (`IntersectionObserver`, threshold 0): it gives its
  key back (`remove`), shows its last state, and on return does a fresh `GET view` and subscribes again. A page whose
  elements are all paused closes the channel.
- **Cleanup.** `disconnectedCallback` aborts in-flight requests, removes the key, disconnects its observers
  (`ResizeObserver`, `IntersectionObserver`), disposes the ECharts instances (`echarts.dispose`) and drops its sheet
  reference. An element moved within the page (disconnect then connect in one task) is not reloaded: cleanup waits for
  a microtask and is cancelled by a reconnect.

#### 7.2 Tokens over time

The connection asks `tokenProvider` for a token before the first request and keeps it until 60 s before `expiresAt`
(from the provider's answer, or the token's `exp`). A live stream opened with a token **outlives the token**: the
console checks the token when the stream opens and then every `embed.recheck_seconds` (default 60) against the server
(user still enabled, host application still enabled, roles still open the kind); when the token has expired the
console sends `event: token` (`{"ch": "", "d": {"expiresIn": 0}}`) and the connection answers with a fresh token on
`POST /embed/v1/channel/{cid}/token` within 30 s, or the stream ends with `gone` `DRS-8001`. A token is never sent in a URL.

**Decision 2:** streams are re-authorised in place (`event: token`) rather than by reconnecting every five minutes.
Recommended: a reconnect repaints every element on the page, which users would see.

#### 7.3 One connection per page

`DrishtiElements` keeps one `Connection` per `server` per page. It is the console's `Hub` (`live-hub.js`) built with an
injected transport: the hub already takes `EventSource` and `fetch` as options (`new Hub({EventSource, fetch})`), so the
element passes a `FetchEventSource` (an `EventSource`-shaped class over `fetch` + `ReadableStream`, sending
`Authorization`) and an authorised `fetch`. Keys from all elements are deduplicated by the hub (two elements on the same
trade share one subscription). Across **tabs** of the host application there is no sharing in version 1: a SharedWorker
must have the page's origin, so the console's worker cannot be used, and the host would have to serve one.

**Decision 3:** one connection per page in version 1 (not per browser), and the console served over **HTTP/2** for
embedding, where the six-connections-per-site limit of HTTP/1.1 (QA finding UX-01) does not apply. Recommended. A
host-served SharedWorker (`DrishtiElements.useWorker('/drishti-worker.js')`) can come later for hosts on HTTP/1.1.

#### 7.3a Live updates in the element: what repaints and what patches

A repaint (`drishti:paint`: dispose every enhancer, rebuild the shadow root, boot again) happens only for a real reason:
the first load, `entity`/`kind`/`as-of`/`known-at`/`header`/`panels`/`live`/`server` changing, `refresh()` or `reload()`
called by the host, the entity coming back after a deletion, and the Retry after an error. Even then a zoomed panel of the
same entity stays zoomed. Everything else is a patch applied in place, so scroll, selection, focus and zoom stay:

| Event from the stream | What the element does |
|---|---|
| `frame` (every tick) | strip cells and whole panels are patched; chart data is updated in place; `drishti:tick` |
| first `view` of a subscription, newer than the fetched page | the page is a moment old, so the element fetches the view again and applies only the differences (strip cells, changed panels) as patches: a `tick`, no `paint` |
| `view` equal to or older than the page | ignored |
| `view` after a reconnect (`reconnected`) | the same resync, then ticks continue; no `paint` |
| `deleted` / `restored` | the banner / a reload of the entity (the one repaint a deletion causes) |

The console's own page (`live.js`) does the same: the first `view` after load only turns the badge to Live. There are no
periodic `view` events: the stream sends one per subscription and then frames. Measured over ten seconds of ticks in
Chromium, Firefox and WebKit (`test_a_live_element_paints_once_and_then_only_patches`): one `drishti:paint` per element.

#### 7.4 Charts in a shadow root

- ECharts is loaded on first need, once per page (`assets.echarts`), and ECharts GL only for a 3D surface (as `view.js`
  does today).
- Every chart is `echarts.init(el, null, {renderer: 'svg'})` on an element inside the shadow root (the spike drew one
  under a strict CSP). Colours come from `getComputedStyle(hostElement)` (the custom properties inherit into the shadow
  root), not from `document.documentElement`.
- One `ResizeObserver` per element watches the grid; a panel whose width changed resizes its charts in the next
  animation frame (the console listens to `window.resize`, which misses a host's split panes and drawers).
- A chart in a panel that is `hidden` (tabs) is drawn when it is shown (zero size otherwise).
- Tooltips stay inside the shadow root (ECharts' default `appendToBody: false`) and so take the element's styles.

#### 7.5 Links and navigation

Panel HTML holds console-relative links. One click (and `keydown` Enter) listener on the shadow root resolves them:

| Link | Becomes |
|---|---|
| `/v/{kind}/{id}` (strip cells, tables, graph nodes, chart points: `charts.js` `open()`) | `drishti:navigate`; default: reload in place on that entity |
| `/help/rachana-reference#…` (the panel `?`) | the panel's About popover (`about-hints.js`), not a console page |
| anything else console-relative | absolute console URL, `target="_blank" rel="noopener"` |
| console-only affordances (export, layout, share, pin) | not rendered: the macros get `embed=True` and leave them out |

### 8. Embed tokens: the token exchange

#### 8.1 Flow

```
 User      Host page            Host backend                      drishti-server                    drishti-console
  │  open CRM  │                      │                                  │                                  │
  │──────────► │ GET /drishti-token   │                                  │                                  │
  │            │ (host session cookie)│                                  │                                  │
  │            │────────────────────► │ POST /api/v1/embed/token         │                                  │
  │            │                      │  Authorization: Basic app:secret │                                  │
  │            │                      │   (or client_assertion, RFC 7523)│                                  │
  │            │                      │  grant_type=…:token-exchange     │                                  │
  │            │                      │  subject_token=<the user's ID    │                                  │
  │            │                      │   token, or an assertion>        │                                  │
  │            │                      │  subject_token_type=…:id_token   │                                  │
  │            │                      │  audience=https://drishti…       │                                  │
  │            │                      │  scope=embed:view embed:about    │                                  │
  │            │                      │────────────────────────────────► │ verify app + subject; user       │
  │            │                      │                                  │ enabled; scopes ⊆ app's; mint    │
  │            │                      │ ◄──────────────────────────────── │ {access_token, expires_in: 300}  │
  │            │ ◄──────────────────── │ (to its own page only)           │ audit: embed-token app user      │
  │            │ <drishti-view> GET /embed/v1/views/trade/X  Authorization: Bearer …, Origin: https://crm…    │
  │            │─────────────────────────────────────────────────────────────────────────────────────────────►│
  │            │                      │                                  │ ◄── GET /api/v1/views/trade/X ───│ verify token
  │            │                      │                                  │     Bearer (passed through)      │ (sig, aud, exp,
  │            │                      │                                  │ TokenFilter: embed token → user, │  origin ∈ app)
  │            │                      │                                  │ roles now, masks, kind allowed;  │
  │            │                      │                                  │ AccessRecorder: embed:crm        │
  │            │ ◄───────────────────────────────────────────────────── html + data ─────────────────────────│
```

#### 8.2 The request (RFC 8693)

```
POST /api/v1/embed/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(crm:<client secret>)

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<token>
&subject_token_type=urn:ietf:params:oauth:token-type:id_token
&audience=https://drishti.bank.example
&scope=embed:view embed:about
```

Client authentication, per host application: `client_secret_basic` (a secret shown once, stored as a hash, as personal
tokens are, `ApiTokenStore`), or `private_key_jwt` (`client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`,
a JWT signed with the app's key, its public JWKS registered or at a URL). Subject tokens:

| `subject_token_type` | Meaning | Verified by |
|---|---|---|
| `urn:ietf:params:oauth:token-type:id_token` | the user's ID token from the identity provider both apps use | the existing OIDC verifier (`IdTokenVerifier`, `drishti.security.oidc`: issuer, signature, `exp`, username claim) |
| `urn:ietf:params:oauth:token-type:jwt` | a JWT the host signs about its user (`sub`, `iat`, `exp` ≤ 60 s, `jti`) with its registered key | the app's JWKS; `jti` not seen before |

The user must exist in Drishti (or be created by the OIDC rules for sign-in) and be enabled; their **roles at the time of
each call** decide what they see, as for every other token.

**Decision 4:** both subject token types, with the ID token preferred, and **no "trust me" username field**: a host
that cannot get its user's ID token signs an assertion with its own key, so a stolen client secret alone does not let
anyone be anyone. Recommended. (Allowing `client_secret` + plain username would be simpler for hosts and much weaker.)

#### 8.3 The response and the token

```json
{"access_token": "eyJhbGciOiJFUzI1NiIsImtpZCI6ImVtYmVkLTIwMjYtMTAifQ…",
 "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
 "token_type": "Bearer", "expires_in": 300, "scope": "embed:view embed:about"}
```

The token is a JWT signed by the server with an **ES256 key of its own** (published at `GET /api/v1/embed/jwks`, rotated
by `kid`), not the console's shared HS256 secret (`auth.token_secret`), so the console can verify it without being able
to mint it. Claims:

```json
{"iss": "https://drishti.bank.example", "aud": "https://drishti.bank.example", "sub": "ravi", "azp": "crm",
 "scope": "embed:view embed:about", "origins": ["https://crm.bank.example"], "iat": 1791000000, "exp": 1791000300,
 "jti": "emb_01J…", "typ": "drishti-embed+jwt"}
```

- Lifetime `expires_in` from the app's registration, default 300 s, at most `drishti.embed.token-max-seconds` (900).
- Scopes: `embed:view` (views, the channel, panel records), `embed:about` (the About body). Nothing writes; the server
  refuses any non-GET with an embed token, and any GET outside `drishti.embed.allow` (a list like `token-scopes`:
  `GET /api/v1/views/*/*`, `GET /api/v1/views/*/*/stream`, `GET /api/v1/views/*/*/explain`, `GET /api/v1/views/*/*/panels/*/records`, and `POST /api/v1/command` to resolve a typed command, recorded in no history).
- Masks: an embed token **never carries `raw`**, whatever the user's roles: masked fields stay masked
  (`drishti.embed.mask: always`, per app it may only be made stricter).

**Decision 5:** embed calls always mask, even for users whose roles have `raw`. Recommended: the host page is a place
Drishti does not control (its scripts can read the open shadow root, [section 10](#10-security-threats-and-answers)).

#### 8.4 How the console and the server use it

- The console's `EmbedAuth` (new, `drishti-console/core/embed.py`) verifies the signature (JWKS cached, refreshed on an
  unknown `kid`), `aud` = its own public URL, `exp`, `typ`, and that the request's `Origin` is in `origins`; it builds an
  `Identity(user=sub, token=<the embed token>, app=azp)` for the request. Backend calls send the embed token itself.
- The server's `TokenFilter` recognises `typ: drishti-embed+jwt`, verifies it the same way, checks the app is enabled and
  the kind allowed, loads the user's current roles minus `raw`, applies `drishti.embed.allow`, and sets the access-log
  detail `embed:<app>` (`AccessRecorder`, beside `share:sh_…`).
- Revocation: disabling a host application, or the user, takes effect on the next call (the server checks both on every
  request; the console's stream re-check, [section 7.2](#72-tokens-over-time), ends open streams within
  `embed.recheck_seconds`). Individual tokens are not revoked: they live five minutes.

### 9. Registering a host application

#### 9.1 Admin UI: Admin → Embedding

A page like Admin → Data loads (`admin-loads.js`): the list of host applications (name, origins, status, tokens issued
and views shown in the last 24 h, last use), and *New host application*:

| Field | Example | Notes |
|---|---|---|
| Id | `crm` | lower-case, appears in tokens (`azp`) and the access log |
| Name, contact | *Client CRM*, `crm-team@bank.example` | shown in the access log and to users in About (*Shown in Client CRM*) |
| Origins | `https://crm.bank.example` | exact origins (scheme, host, port); no wildcards except a configured suffix for review apps (`https://*.preview.crm.bank.example` only if `drishti.embed.allow-wildcard-origins: true`) |
| Client authentication | secret / public key (JWKS JSON or URL) | the secret is shown once; *Rotate* keeps the old one for a grace period |
| Subject tokens | ID token / signed assertion | |
| Kinds | `trade, counterparty` or all the users' roles open | an app may narrow, never widen |
| Scopes | `embed:view`, `embed:about` | |
| Token lifetime | 300 s | ≤ `token-max-seconds` |
| Rates | 600 views/min for the app, 60 per user | [section 11](#11-performance) |
| Enabled | yes | off revokes at once |

Host applications live in the identity database (a table beside the API tokens, `drishti-identity`), changes are audited
(`AuditLog`: who added, changed, rotated, disabled), and only `admin` may change them.

#### 9.2 Configuration

Server (`drishti-server/src/main/resources/application.yaml`):

```yaml
drishti:
  embed:
    enabled: ${DRISHTI_EMBED_ENABLED:false}       # off by default: no token endpoint, embed tokens refused
    issuer: ${DRISHTI_PUBLIC_URL:}
    audiences: [${DRISHTI_CONSOLE_URL:}]           # the consoles that may accept embed tokens
    signing-key: ${DRISHTI_EMBED_KEY_FILE:}       # PEM (ES256); empty = generated at start (one server only)
    token-max-seconds: 900
    mask: always
    allow:                                         # what an embed token may GET
      - GET /api/v1/views/*/*
      - GET /api/v1/views/*/*/stream
      - GET /api/v1/views/*/*/explain
      - GET /api/v1/views/*/*/panels/*/records
      - POST /api/v1/command                       # resolve a typed command (read: no history recorded)
    apps: []                                       # optional: apps declared in config (read-only in the UI), for GitOps
    limits: { tokens-per-minute: 120, views-per-minute: 600, views-per-user-per-minute: 60 }
```

Console (`drishti-console/config/application.yaml`):

```yaml
embed:
  enabled: ${DRISHTI_EMBED_ENABLED:false}
  public_url: ${DRISHTI_CONSOLE_URL:}       # the audience its tokens must name
  recheck_seconds: 60
  max_streams: ${DRISHTI_EMBED_MAX_STREAMS:256}   # upstream view streams all embed channels may hold together
  max_subscriptions: 16                     # per page connection
```

The console learns the host applications' origins (for CORS) from the server (`GET /api/v1/embed/apps/origins`, service
token, cached 60 s), so the allow-list has one source.

### 10. Security: threats and answers

**Trust model.** The *user* trusts the host application (they signed in to it). The *host page* is trusted to show the
user's own data to that user, and nothing more: it gets no cookie, no long-lived credential, no write. *Drishti* trusts
the host's **backend** to say who the user is, only as far as its registration allows (client authentication plus a
verifiable subject token). The *panel HTML* is Drishti's own output, produced by Jinja with autoescaping from data that is
itself untrusted (source documents), exactly as in the console.

| Threat | Answer |
|---|---|
| **Token theft** (XSS on the host, a log, a browser extension) | five-minute lifetime; audience-bound (only this Drishti accepts it); origin-bound (the console refuses another `Origin`, and CORS stops browsers elsewhere reading responses); read-only and masked; never in a URL; the element keeps it in a closure, not in the DOM or storage. A stolen token lets the thief read, for minutes, what the user could read on that host anyway. `jti` and `azp` are in the access log for tracing. |
| **Stolen client secret** | useless without a subject token the host's IdP signed (Decision 4); rotate in Admin → Embedding; the token endpoint is rate-limited per app and logs every exchange. `private_key_jwt` recommended for production. |
| **Replay of a subject assertion** | `exp` ≤ 60 s and `jti` remembered until it expires. |
| **XSS through fragments** | the HTML is server-generated by the same macros as the console, with Jinja autoescaping on (`core/app.py` templates); data never becomes markup (`Jinja2Templates` autoescapes `.html` templates). The fragments contain no scripts and no inline styles or event handlers (the console's own CSP, `script-src 'self'; style-src 'self'`, would already break them; step 7 adds a test that renders every panel kind of `docs/guides/examples` and asserts it). The element inserts fragments with `<template>.innerHTML` (scripts in a template never run), and, as defence in depth, a sanitiser pass drops any `script`, `iframe`, `object`, `on*` attribute and `javascript:` URL before insertion (a few dozen lines, no library: the allowed vocabulary is the macros'). Trusted Types: the element creates one policy, `drishti-elements`, so hosts that enforce `require-trusted-types-for 'script'` add `trusted-types drishti-elements`. |
| **Host scripts reading the shadow DOM** | expected and accepted: the shadow root is `open` (closed roots stop nothing a determined script on the page cannot do, and break host testing tools). It is why embed calls always mask (Decision 5) and never write. Documented in the guide. |
| **A malicious host** | can show the data of *its own* signed-in users only, within the kinds it was registered for; every view is logged with `embed:<app>`; disabling the app revokes at once. |
| **Clickjacking** | not applicable: nothing is framed; the console keeps `frame-ancestors 'self'`. |
| **CORS misconfiguration** | the console answers CORS only on `/embed/v1/**` and `/elements/**`, echoing the `Origin` only when it is a registered origin (`Vary: Origin`), never `*` with credentials (and no credentials are used: `Access-Control-Allow-Credentials` is never sent); `Access-Control-Allow-Headers: Authorization, Content-Type, Drishti-Embed-Api, X-Drishti-As-Of, X-Drishti-Known-At`; methods `GET, POST`. The rest of the console (cookie routes) never answers CORS. A test asserts both. Static assets under `/elements/**` are `Access-Control-Allow-Origin: *` (they are public code, no data) so `crossorigin` + SRI work. |
| **Rate abuse** | per app and per user (`drishti.embed.limits`), on the token endpoint, `GET view` and channel opens; `429 DRS-8004` with `Retry-After`; the console's `embed.max_streams` caps upstream streams so embedding cannot starve the console's own users of `backend.pool_size`. |
| **Supply chain** (the host loads our script) | versioned immutable URLs and published SRI hashes; the npm package is the same build with the same hashes; no CDN, no third-party code beyond vendored ECharts (`THIRD-PARTY-NOTICES.md`). |
| **Cross-user mix-ups in one page** | one connection per (server, token subject): if `tokenProvider` returns a token for another user (the host switched user), the connection closes and every element reloads. |

**CSP the host needs** (validated in the spike for Chromium):

```
script-src  'self' https://drishti.bank.example;
connect-src 'self' https://drishti.bank.example;
font-src    'self' https://drishti.bank.example;
img-src     'self' data:;
```

`style-src` does not need the console (constructable stylesheets; the element never adds `<style>` or `<link>` to the
document), and no `'unsafe-inline'` or `'unsafe-eval'` anywhere. `frame-src` is not needed.

### 11. Performance

- **Payloads** (measured in step 0, commodity forward, 8 panels): the embed view is 18.7 KB of JSON, 3.7 KB gzipped (the
  first estimate, 90 KB, was high); a live frame is about 1.5 KB (a strip cell and the provenance panel). The console
  compresses `/embed/v1/**` (gzip; brotli where available), but **not** the stream (compression buffers SSE). First paint
  from navigation start is 0.3 to 0.6 s with every asset cold (Chromium, Firefox, WebKit); a switch of entity takes about
  60 ms at the median, none over 110 ms.
- **Static assets.** `drishti-elements.js` (the element, connection, enhancers, sanitiser) target ≤ 60 KB gzipped
  without ECharts; the view sheet (tokens, panels, tables, pivot, about; Bootstrap excluded: the panel macros do not use
  its classes, only Bootstrap Icons) ≤ 25 KB gzipped; ECharts (1 MB, about 330 KB gzipped) loaded only when a chart panel
  is on screen; ECharts GL only for 3D. All under `/elements/<version>/` with `Cache-Control: public, max-age=31536000,
  immutable`.
- **One sheet per page.** The element's `CSSStyleSheet` is built once and adopted by every element (the spike shared one).
- **Shared connection, deduplicated keys**, as above: ten elements on five trades are one stream and five upstream
  subscriptions.
- **Upstream cost.** Each subscribed view is one console→server stream (`backend.stream`, from the console's pool of
  `backend.pool_size`, default 64) and one `ViewStream` on the server (frames are built per user, because masks and
  roles differ). Embedding multiplies viewers: the console's `embed.max_streams` and the per-page `max_subscriptions`
  bound it, and *pause when hidden or off-screen* keeps it to what people can see. A deployment expecting many host
  users runs more console instances (any console serves any user, `core/auth.py`) and raises `pool_size`.
- **Render cost.** Panel HTML for a frame is rendered once per frame per subscriber, as today.

### 12. Console scripts as root-scoped modules

The scripts that make a view interactive are classic scripts that query `document` and publish globals. For the element
each becomes an ES module exporting `enhance(root, ctx)` (and a `dispose(root)` where it holds observers), where `root`
is a `Document` or a `ShadowRoot` and `ctx` gives `{host, resolveUrl, fetch, tokens(), emit(event, detail)}`. The
console keeps working by calling them with `document` and the console's context from a small loader
(`static/js/view-boot.js`); classic `<script>` tags in `view.html` are replaced by `<script type="module">` (allowed by
`script-src 'self'`).

| Script | Lines | What changes | Risk |
|---|---|---|---|
| `live.js` | 134 | split: `applyFrame(root, frame, ctx)` (strip, panel swap, deleted/restored, provenance) becomes a module used by both; the console wrapper keeps the top-bar state and `location.reload()` | low: tested by `test_live_tabs_browser.py` |
| `view.js` | 317 | the reusable half (`widths`, `tabsIn`, line/area `drawCharts`/`updateChart`, surfaces) moves to `view-panels.js` taking `root`; tokens from `getComputedStyle(ctx.host)`; `getElementById` → `root.getElementById` (exists on shadow roots); clock, F-keys, raw drawer, breadcrumbs, share, print stay console-only | medium: many behaviours, but each is local |
| `charts.js` | 298 | `draw(root)` already takes a root; change `tokens()` to `ctx.host`, `open()` to `ctx.emit('navigate', link)` (console: the hidden-link click it does now), the `drishti:theme` listener to the module's caller | low |
| `tables.js` | 252 | the `MutationObserver` on `document.body` becomes `observe(root)`; `document.querySelectorAll('table.tbl')` (index for remembered state) → the root; the page-size event goes to the root; remembered page size in `localStorage` is namespaced per origin anyway | medium: `MutationObserver` must also watch swapped panels (it does when attached to the root) |
| `tree-rows.js` | 163 | same `MutationObserver` change | low |
| `pivot-engine.js` | 290 | none (pure, no DOM) | none |
| `pivot-grid.js` | 331 | `document.createElement` is fine in a shadow root; no change but module export | low |
| `pivot.js` | 975 | `json()` through `ctx.fetch` with URLs from `ctx.resolveUrl('records', …)`; saving arrangements behind `ctx.canSave` (false in the element); the modal shade and `keydown` capture (`document.addEventListener('keydown', esc, true)`) attached to the root; grid export (`/export/grid.*`) hidden when not offered | **high**: largest file, near the 1,500-line cap; split while refactoring (engine, grid, chooser, persistence) |
| `about.js` | 183 | the drawer is found in `root` (the element renders its own drawer markup); fetch through `ctx`; the document-wide `?`/`F1`/`Esc` keys go on the root; F1 (screen guide) not bound in the element | medium |
| `about-hints.js` | 229 | panel `?` popovers and field hints: `document` queries and two `keydown` listeners → root; it is fed by the same About fetch | medium |
| `layout.js` | — | not used by the element (layout mode is a non-goal); only its `decorate()` hook in `live.js` becomes optional | none |

Not touched: `channel.js` (tabs and panes of the console), `live-hub.js` except exporting `Hub` as a module as well as
running as a worker (one file, both uses; the worker branch at the end stays), `app.js`, `command.js`, collaboration
scripts.

**How the console benefits.** One code path for "enhance this fragment" instead of `window.drishti.enhance` and globals
wired in a fixed script order; workspaces could later drop their same-origin iframes (`workspace.js`) for
`<drishti-view>` elements in one document (one live connection, no frame per pane, shared ECharts); `pivot.js` gets split
before it reaches the line cap; scripts become testable in isolation (a `ShadowRoot` in a test page is a perfect sandbox).

**Decision 6:** refactor the console's own scripts to the root-scoped modules (one copy, used by the console and the
element) rather than fork copies for the element. Recommended, with the console's browser tests as the safety net and
the refactor landing *before* the element uses each script.

**CSS.** The element sheet is generated (at console start, cached by `ASSET_V`; and by the release build for npm) from
`tokens.css`, `theme.css`, `terminal.css` (the view parts), `layout.css`, `pivot.css`, `about.css` and
`bootstrap-icons.css`, with `:root` → `:host`, `:root[data-theme="x"]` → `:host([theme="x"])`, `body`/`html` rules
dropped, and `@font-face` rules removed from the sheet and registered on the document through the `FontFace` API (the
spike showed `@font-face` in a shadow sheet does not load). A test fails when a selector in the source sheets cannot be
rewritten.

#### Step 6 as built (root-scoped modules)

The scripts stayed classic scripts (the console's pages and `script-src 'self'` are unchanged); each is an IIFE that registers
`window.drishtiModules.<name> = { init }` and starts `init(document)` itself unless its `<script>` has `data-manual`, which the
element sets when it loads them (`GET /embed/v1/js/<name>.js`, a whitelist in `embed_routes.ENHANCERS`). `drishtiModules` is
the only global the element adds to the host page; with `data-manual` the engine, grid, charts, About and Pivot globals
(`window.drishtiPivot`, `drishtiAbout`, `drishtiCharts`, `drishti`, ...) are not set. A real ES-module wrapper stays for step 9.

| Module | `init(root, options)` | Notes |
|---|---|---|
| `view.js` | bars, tabs, line/area charts, surfaces; returns `{enhance, redraw, updateChart, dispose}` | clock, F-keys, raw drawer, share, breadcrumbs stay console-only (`auto` only); `options.glUrl: null` = no 3D (heatmap); sizing by `ResizeObserver` on the host in a shadow root |
| `charts.js` | the xcharts; theme and size handling; returns `{draw, dispose}` | tokens from the host; `options.navigate(link)` optional (default: hidden link click) |
| `geomap.js` | no `init`: defines `window.drishtiGeo` (the world's countries and a country's regions, the map option builder) for the `map` panel (`charts.js`) and the BI map visual | the shapes load from `/embed/v1/world-countries.json`, `world-points.json` and `regions/<ISO3>.json` on the same server (vendored Natural Earth: nothing leaves the network); loaded before `charts.js` and `bi-visuals.js` (library 1.2.0) |
| `tables.js` | sort, filter, paging, keys | `MutationObserver` on the root; `options.scope` namespaces remembered state; page-size event on the root |
| `tree-rows.js` | `▸/▾` rows and tree pivots | engine and grid found in the registry |
| `pivot.js` | the Pivot tab | `options.fetch/url` (records go to `/embed/v1/poc/records/...`), `save:false` (no Save, Reset, Promote; the saved arrangement is ignored: layouts are the Sutra's only, Decision 10), `exports:false`; dialogs, drag ghost and Esc capture inside root; fixed boxes corrected for the host element |
| `about.js` | the drawer | element renders the drawer (About tab only), fetches `/embed/v1/poc/about/...`; `?` and Esc on the root; `guideKey:false`; `drishti:frame` dispatched on the root by the element |
| `about-hints.js` | `?` popovers, field hints | popovers inside the root; `refresh()` after panel swaps |

Not done here: `live.js` split and `view-panels.js` (the element still applies frames itself; its own copy of the line/area
option builder is gone, it uses `view.js`), `pivot.js` split (998 lines, under the cap). The element's `.view` takes focus on
click (`tabindex=-1`) so `?` works inside it; a key pressed elsewhere on the host page does nothing (tested). The sheet now also
carries `pivot.css` and `about.css`. Tests: `test_embed_elements_browser.py` (Chromium, Firefox, WebKit): sort and paging, tree
row, pivot regroup with no PUT, panel `?` popover, About drawer, key scoping; the console's own browser tests pass unchanged.

After the merge with steps 1-3: the embed routes `await` the now-async `cors`, `send`, `refuse` and `identity` helpers (the enhancer
scripts, `/poc/records` and `/poc/about` returned unawaited coroutines, a 500), and the panel `?` link (`a.pnl-help`) is rendered in
embedded views again, because `about-hints.js` wires its popover to it.

### 13. Distribution and versioning

- **Served by the console**: `/elements/<semver>/drishti-elements.js`, `…/drishti-view.css`, `…/charts.js`, fonts, and
  `/elements/<semver>/integrity.json` (`{"drishti-elements.js": "sha384-…", …}`). Immutable. `/elements/1/…` and
  `/elements/1.2/…` are aliases answering `302` to the newest matching version (short cache) for hosts that accept
  updates; SRI needs the exact version.
- **npm**: `@drishti/elements` with the same files and an `exports` map; TypeScript declarations (`HTMLElementTagNameMap`
  entries, event detail types, a `JSX.IntrinsicElements` augmentation for React) generated from one source. Published
  from the release build; the package is the same bytes as the console's path for that version.
- **Two contracts, both semver**:
  1. the **element API** (attributes, properties, methods, events and their `detail`, CSS custom properties, parts,
     slots): a removal or meaning change is a major version;
  2. the **embed API** (`/embed/v1/**` payloads and frames): additive changes (a new field, a new patch `op` the element
     ignores when unknown) are minor; anything else is `/embed/v2/`, and the console serves the previous major for at
     least two releases with `Deprecation` and `Sunset` headers, then `410 DRS-8006`.
  The element sends `Drishti-Embed-Api: 1.<minor>`; the console answers in the newest compatible shape.
- Panel **HTML is not a contract**: hosts style it only through custom properties and parts; a test lists the parts.

**As built (step 12):** see [Steps 11 to 15 as built](#steps-11-to-15-as-built-2026-10-11). The paths are `/embed/v1/elements/<version>/...` (under the embed API's own prefix, not `/elements/...`), the package is a folder made by `tools/elements_package.py`, and `Deprecation`/`Sunset` are configured by `embed.lifecycle`.

**Decision 7:** the console serves the library and the npm package mirrors it (rather than npm only). Recommended:
a host on a locked-down network fetches from Drishti, which it must reach anyway, and the version always matches the
console it talks to.

### 14. Testing

- **Unit (console, pytest)**: `EmbedAuth` (signature, `aud`, `exp`, `typ`, origin, unknown `kid` refetch, clock skew),
  CORS (registered origin echoed, other origins and cookie routes get nothing, preflight), the embed view payload (every
  panel kind of `docs/guides/examples` renders, `embed=True` drops console-only links), the channel session shared with
  `/api/channel` (both pass the existing `test_live*.py`), the CSS rewrite, the sanitiser on hostile fragments.
- **Unit (server, JUnit)**: token exchange (each `error` of RFC 6749 §5.2, both subject token types, `jti` replay,
  scopes ⊆ app, kinds narrowed), embed tokens in `TokenFilter` (GET allow-list, non-GET refused, `raw` removed, disabled
  app or user), `AccessRecorder` `embed:<app>`, `provenance.masked` counts, `ErrorCode` new codes.
- **Unit (JavaScript)**: the root-scoped modules run in a test page against a `ShadowRoot` (Playwright, as the console's
  browser tests do); the `FetchEventSource` parser (split chunks, comments, multi-line `data:`).
- **The foreign-origin host page (Playwright)**: `tools/elements-demo/` (as built; designed as a folder under the console's tests) is a tiny host app on a second
  port with a strict CSP (section 10), its own "backend" doing the token exchange against a scratch server with the
  banking packs; tests: a live trade ticks; masked fields read `•••` for a viewer and a user with `raw`; clicking a
  counterparty fires `drishti:navigate` and, not cancelled, reloads in place (cancelled, the host's search box takes it
  and switches); typing in the host's search box switches the view; the as-of picker reloads as static and back; ten
  rapid switches with out-of-order responses paint only the last; `refresh()` keeps a table's sort; killing the scratch server shows `reconnecting` then repaints; hiding the
  page gives the subscription back; two elements share one connection (count requests); token expiry mid-stream renews
  without a repaint; another origin is refused (`DRS-8002`); a CSP violation report endpoint receives nothing.
- **Frameworks smoke** (built: `test_embed_frameworks_browser.py`): sample pages for React 18, Vue 3 and Angular 18 (`docs/guides/examples/embed/`, builds fetched once and not committed), each binding `entity`, `tokenProvider` and one event.
- **Load (local, small, per the house rule)**: 20 elements, 10 entities, one page; then 50 pages: upstream stream count,
  console memory, frame latency (`p99Ms`) against the console-only baseline.

### 15. Documentation plan

*Written (step 13, 2026-10-11): `docs/architecture/CLIENT_RENDERER.md` is the developer guide, with the reference tables generated from `core/element_api.py`; ADR-021 records Decision 1; the items below are what it holds.*

- **`docs/architecture/CLIENT_RENDERER.md`, the developer guide** (also in the console help centre, `help.yaml`): what Drishti Elements
  are; five-minute start (register a host app, mint a token with `curl`, a static page); the element reference
  (generated tables from the TypeScript declarations); token exchange for the host backend with snippets (Java Spring,
  Node, Python); CSP and SRI; theming; troubleshooting by DRS code. **Cookbook**: master-detail (a list drives `entity`),
  keep navigation in the host (`drishti:navigate`), a host live indicator from `drishti:state`, two views side by side
  (one connection), dark host theme, a past business date, React/Angular/Vue recipes, a panel in a card
  (`<drishti-panel>`, phase 2), testing a host page that embeds Drishti.
- **`docs/admin/CONFIGURATION.md`**: `drishti.embed.*`, console `embed.*`, Admin → Embedding.
- **`docs/guides/API_GUIDE.md`**: `/api/v1/embed/token`, `/api/v1/embed/jwks`, the new DRS codes; the console's
  `/embed/v1/**` with payloads.
- **This document** stays the design record; an ADR (`adr/021-embedding-is-html-over-the-wire.md`) records Decision 1.
- `docs/README.md`, CHANGELOG, RELEASE_NOTES at each shipped step.

### 16. Build plan

Sizes: **S** ≤ 1 day, **M** 2–4 days, **L** a week or more. Steps in the same parallel group can run at once (at most
five agents, heavy test runs one at a time).

#### Step 0: proof of concept (M/L) — before anything else

A throwaway-quality but real path: a sample host app (`tools/elements-demo/`, its own origin, its own tiny backend) shows
a live trade from a scratch Drishti with masks, and switches views dynamically. The host page has **its own search box**
(type a command or an id: `TRD MX-20000001`, `CPTY …`, resolved through `GET /embed/v1/resolve`), a short list of
**recent entities**, an **as-of date picker**, one main `<drishti-view>` and a **second, smaller element** (a second
`<drishti-view kind="counterparty" header="strip">`, or `<drishti-panel>` if step 14's attribute is cheap to add) on
the same page. Minimal pieces: a dev-only embed token
(server `drishti.embed.enabled` with one config-declared app and the signed-assertion subject type), the console's
`GET /embed/v1/views/…` and `GET /embed/v1/channel` (reusing `_view_event` and the channel logic by copy if need be),
`GET /embed/v1/resolve`, an element with `FetchEventSource`, the shared page connection, adopted sheet, panel swap,
line/area charts, link → `drishti:navigate`, the load sequence number and `AbortController` of section 7.1.

Acceptance criteria:

1. The host page on `http://127.0.0.1:<port A>` with the CSP of section 10 (no `'unsafe-inline'`, no `frame-src`) shows
   `TRD END-1000008` from a scratch server and console on `<port B>`; the MTM cell ticks at the demo rate; no CSP reports.
2. Signed in on the host as a `viewer`, the masked fields (`trader`, `counterpartyId`) read `•••`; as an `author`
   (roles with `raw`) they still read `•••` (Decision 5).
3. **Search drives the element.** Typing `TRD MX-20000001` in the host's search box (or picking a recent entity) sets
   the element's `kind`/`entity` (attribute and, in a second test, property); the DOM reacts: the in-flight `GET view`
   is aborted, the old key is removed from the channel (`remove` seen), the new view is fetched, rendered within 500 ms
   and goes live (ticks). The recent list gains the entity.
4. **Navigation round-trips.** Clicking a linked entity inside the view (a counterparty) raises `drishti:navigate`; the
   host cancels it, writes the entity into its search box and switches the view the same way as in 3.
5. **As-of reloads.** Picking a past date in the host's date picker sets `as-of`; the element reloads as a static
   snapshot (`state` = `static`, no subscription); picking *live* again resubscribes.
6. **Last request wins.** Typing fast and clicking recent entities quickly (a test sets ten entities within 200 ms, with
   the scratch console's view responses delayed out of order) never paints a stale view: the element ends on the last
   entity, `drishti:loaded` fires for it only, and no frame of an earlier entity is applied.
7. **One connection for both elements.** The main view and the smaller element tick together over **one** channel
   request (the test counts `GET /embed/v1/channel`: exactly one), and switching the main one does not disturb the other.
8. Stopping and restarting the scratch console: the element shows `reconnecting`, then repaints and ticks again.
9. Works in Chromium, Firefox and WebKit (repeating the spike's checks: adopted sheets under the host CSP, icon font via
   `FontFace`, streamed fetch with `Authorization`, ECharts in a shadow root).
10. Measured: embed view payload size and gzip size, first paint time, frame size; written into section 11.

If 9 fails for a browser, the design changes before step 1 (for example `<link rel=stylesheet>` in the shadow root, with
the console in the host's `style-src`).

##### Step 0 results (2026-10-06)

**Built.** `tools/elements-demo/` (the host: a stdlib Python server on its own origin with the strict CSP of section 10,
its own sign-in, its own `/api/drishti-token` that asks the console for a token server to server, a search box, a recent
list, an as-of picker, a main `<drishti-view>` and a second, smaller one), `drishti-console/web/embed/drishti-elements.js`
(the element and one `Connection` per page and server, about 540 lines), `routes/embed_routes.py` and `core/embed_poc.py`
(the embed endpoints and the DEV-ONLY token path, which was **off** unless `embed.poc.enabled`; signing key, per-app secret and exact
origins in config; this PoC token path was later replaced by the server's embed tokens, `core/embed_auth.py`, and `embed.enabled`). Run it with [the demo's README](../../tools/elements-demo/README.md).
Acceptance: `drishti-console/tests/test_embed_elements_browser.py` (Playwright, Chromium, Firefox and WebKit, against a real
scratch server, the console and the host on three ports) and `test_embed_poc.py` (CORS, masking, "nothing else changed"; it was later replaced by `test_embed.py`).
**All ten criteria passed in all three browsers** (40 tests passed). The rapid-switching test was checked by mutation:
with the sequence guard removed it fails (a stale entity is painted).

| # | Criterion | Chromium | Firefox | WebKit |
|---|---|---|---|---|
| 1 | live trade, cross-origin, strict host CSP, ticks, no CSP violation or report | pass | pass | pass |
| 2 | masks for `viewer` and `author` (`•••`, no trader name) | pass | pass | pass |
| 3 | search, recent entity and property drive the element: abort, `remove`, new view, ticks, recent list | pass | pass | pass |
| 4 | `drishti:navigate` from a linked counterparty fills the host's search box and switches | pass | pass | pass |
| 5 | as-of makes it `static` with no ticks; Live resubscribes | pass | pass | pass |
| 6 | ten switches in 200 ms with answers delayed in reverse: only the last paints, `loaded` once, only its key subscribed | pass | pass | pass |
| 7 | two elements, exactly one `GET /embed/v1/channel`, both tick, switching one does not disturb the other | pass | pass | pass |
| 8 | console killed and restarted: `reconnecting`, then repaint and ticks | pass | pass | pass |
| 9 | adopted sheets under the host CSP, icon font via `FontFace`, streamed fetch with `Authorization`, composed events, ECharts in the shadow root, no style leak either way | pass | pass | pass |
| 10 | measured (below) | | | |

Also covered: pause when the page is hidden (no ticks while paused, resumes live), a request aborted in flight
(`AbortController` seen), no `unsafe-inline` and no `frame-src` in the host's CSP.

**Measured** (loopback, this machine, scratch trading pack; the tests record them):

| What | Chromium | Firefox | WebKit |
|---|---|---|---|
| first `drishti:loaded`, from navigation start (module, sheet, font, ECharts, view, paint) | 314 to 344 ms | 451 ms | 539 to 619 ms |
| the same, from the element's own load to paint | 240 to 295 ms | 306 to 349 ms | 507 to 573 ms |
| switch (attribute set to `drishti:loaded`), median / p95 / max of 10 | 58 / 78 / 106 ms | 51 / 73 / 76 ms | 69 / 82 / 93 ms |
| search box to loaded (resolve, view, paint) | 54 to 136 ms | 88 ms | 138 ms |

| Payload | Raw | gzip |
|---|---|---|
| embed view, commodity forward (8 panels, head, strip) | 18.7 KB | 3.7 KB |
| embed view, counterparty | 13.1 KB | 2.7 KB |
| a live frame (strip cell and provenance panel), median of 12 | 1.5 KB | not compressed (SSE) |
| `drishti-elements.js` (POC) | 30 KB | 9.8 KB |
| view sheet (tokens, theme, terminal, layout, gradients, Bootstrap Icons rules); POC, built per request | 163.5 KB | 30.5 KB |
| view sheet as built (step 8: comments dropped, 9 glyphs) | 80.2 KB | 15.7 KB |
| `echarts.min.js` / `charts.js` | 1.12 MB / 19.5 KB | 369 KB / 6.7 KB |
| icon font (woff2) | 92 KB | already compressed |

**What held.** HTML over the wire from the existing macros works as is: every panel of the trade rendered from `panel(p)`
with no change to the macros. The console's sheets, with `:root`, `html` and `body` rewritten to `:host` by a few regular
expressions (`shadow_css`), style the shadow root, and the tokens do not leak into the host page nor the host's styles into
the element. Constructable sheets, `FontFace` and ECharts SVG all work under a CSP without `'unsafe-inline'` in all three
browsers. `fetch` with a `ReadableStream` and `Authorization` streams cross-origin in all three (CORS needs a preflight for
`Authorization` and for the JSON `POST`s; the allow-list answers it). Events cross the shadow boundary (`composed`) and a
`drishti:navigate` cancelled by the host did the round trip. Switching is fast (about 60 ms median).

**What did not hold, or surprised; adjustments to the plan.**

1. **Payload estimates were high.** The embed view is 18.7 KB (3.7 KB gzip) for 8 panels, not 90 KB (14 KB): section 11
   now carries these numbers. The sheet is 30 KB gzipped, over the 25 KB target, almost all of it Bootstrap Icons' 2000
   glyph rules: step 8 ships only the glyphs the macros use (or a subset font).
2. **Hub is not reused.** `live-hub.js`'s `Hub` is a tab multiplexer (`hello`, `who`, `sub`, replay, `stale`); in a page
   with no tabs to serve none of that applies, and its injectable `EventSource` is not enough reason to carry it. The POC's
   `Connection` (about 90 lines: one stream, key diff by `POST`, backoff with plus or minus 20 % jitter, a silence watchdog,
   `reconnected` per key) replaces "Hub with `FetchEventSource`" in steps 9 and 15; it shares the Hub's constants only.
3. **Two counters, not one.** The load sequence (who may paint) and the **subscription epoch** (whose frames are applied)
   must be separate: `refresh()` and the repaint after a reconnect take a new load sequence but must keep the subscription,
   or every frame after the first refresh is dropped (found by test 8). Section 7.1's Refresh and Reconnect rules hold only
   with that split.
4. **The last key leaving closes the channel** (after a 1.5 s grace) instead of sending a `remove`: there is nothing left to
   keep it open for. `remove` is sent while other keys remain. Criterion 3's "`remove` seen" holds with the second element on
   the page, as in real use.
5. **A live second element.** The demo's counterparty is not a live kind (it never ticks), so the second element watches
   another trade; `panels="none"` (new, cheap) shows a header alone, which covers the "smaller element" until
   `<drishti-panel>` (step 14).
6. **Token timing.** Modules run in order and elements upgrade (and load) as soon as the module has run, before a host's own
   script sets `tokenProvider`: the connection waits up to 3 s for the provider rather than failing with 401. Section 5.3
   should say to set it before, or right after, the import.
7. **Enhancers are partial.** Done: bars (`data-w`), tabs, line and area charts, the xcharts through `charts.js` (made
   root-aware: `tokens(root)` reads the theme from the host element), links, flash, panel swap, deleted and restored. Not
   done (steps 6 and 9): table sort and paging, tree rows, pivot, About, layout. `charts.js` still loads as a classic script
   into the host page (`window.drishtiCharts`, a `resize` listener on the host's `window`): the root-scoped modules of step 6
   must not touch host globals.
8. **Chart animation on every tick.** The waterfall panel changes each tick, so it is swapped and its ECharts animation
   restarts each time; in a screenshot taken mid-animation the bars are missing (WebKit more often). The console has the same
   behaviour. Step 9: update the chart in place, or `animation: false` on updates.
9. **WebKit** under strict CSP prints "Refused to apply a stylesheet" on the console's own pages as well (ECharts SVG); no
   `securitypolicyviolation` event and no CSP report fire, and everything draws. Worth a look in step 11 with
   `Content-Security-Policy-Report-Only` on a real host. Playwright's `wait_for_function` uses `eval`, which the host's CSP
   blocks: the tests use locator expectations and `evaluate` (step 11 should too, rather than `bypass_csp`).
10. **Environment.** Playwright's WebKit (WPE) needed `libevent`, `libavif`, `libmanette`, `libgav1` and `libhidapi-hidraw`
    on this machine (not installable without root here: copied next to the browser from the packages); Firefox and Chromium
    needed nothing.
11. **Left as POC, to be replaced as planned.** Dev tokens and a role map instead of the RFC 8693 exchange and the server's
    `raw` removal (steps 1 and 2); `provenance.masked` counted from `•••` in the HTML (step 3); the channel owner checked by
    user only, not by host application, and a stream that outlives its token (no `event: token`) (steps 4 and 7); unversioned
    asset URLs (`no-cache`, ETag) and view JSON not gzip-compressed (step 8); no Trusted Types or sanitiser yet. The
    console's normal pages are unchanged: `/embed/` is excluded from the cookie identity, the as-of cookie logic and the
    cross-site `POST` check, nothing else (`test_embed_poc.py` asserts the CSP, `X-Frame-Options`, no CORS header and a
    foreign-origin `POST` still refused on the console's own paths).

No browser failed criterion 9, so the `<link rel=stylesheet>` fallback is not needed. **Step 1 may start.**

##### Steps 1 to 3 as built (2026-10-06)

**Built.** Server: `drishti-identity` `EmbedAppStore` (table `drishti_embed_app` in both schema files, cached snapshot, refreshed with the roles and packs every `drishti.identity.refresh-seconds`), and in `drishti-server` the package `server/embed`: `EmbedTokenService` (the exchange and the per-call checks), `EmbedKeys` (ES256 key from a PEM file or made at start, JWKS), `Jws` (JDK-only RSA and ECDSA verification and JWK reading for what hosts present), `RateWindows`, `EmbedController` (token endpoint, `jwks`, `check`, `apps/origins`, the admin registry), a branch in `TokenFilter`, `Principal.embedApp` (which makes `Entitlements.masks` true whatever the roles), `AccessRecorder` detail `embed:<app>`, `IdTokenVerifier.verifyForExchange`, `ErrorCode` `DRS-8001` to `DRS-8006`, and `provenance.masked` / `maskedPanels` (`engine/view/MaskCount`, counted on the built view). Console: `core/embed.py` replaces the proof of concept's token code. Tests: `EmbedTokenTest` (18: both client authentications, both subject kinds, every refusal, masking whatever the roles, kinds, rates, renewal, audit rows, the registry), `EmbedOffByDefaultTest`, console `test_embed.py`, and the step 0 browser acceptance re-run against the real flow (all 14 Chromium tests pass; the stack registers the host through the admin API and the host's backend signs its user assertion with an RSA key and exchanges it at the server).

**Decisions as taken.** Decision 2: the server side of renewal is that a fresh exchange gives a fresh token for the same `sub` and `azp`, and `GET /api/v1/embed/check` tells the console in one call whether a token still passes every check (user, application, scopes), which is what its stream re-check and `event: token` handling (step 7) use; the server itself checks a token when a call or stream *opens*. Decision 4: there is no plain-username path; both subject kinds exist and ID tokens need single sign-on enabled. Decision 5: `Principal.embedApp != null` masks every `redact` field regardless of `raw` (`drishti.embed.mask: always` is the only value).

**Adjustments to the plan.**

1. **The console does not verify embed tokens yet** (done in step 7: `EmbedAuth`, below). It reads the token's claims to route the call, takes the CORS allow-list from `GET /api/v1/embed/apps/origins` (service identity, cached `embed.origins_ttl_seconds`), and sends the embed token itself, plus the browser's `Origin`, to the server, which verifies everything on every call. The server's answers (`DRS-8001` to `DRS-8005`, `Retry-After`) are passed on unchanged. Console-side signature checks add defence in depth, not the decision.
2. **Scopes are a map, not one `allow` list:** `drishti.embed.scopes` names each scope's `METHOD path` patterns (`embed:view` opens views, the stream, panel rows, `GET /api/v1/embed/check` and the read-only `POST /api/v1/command`; `embed:about` opens `explain`), so a token's scopes decide the allow-list. `apps:` declared in configuration (GitOps) is not built: applications are registered through the admin API only (Admin → Embedding is step 10).
3. **`private_key_jwt` and signed user assertions accept RSA (RS256/384/512) and ECDSA (ES256/384) keys** given as a JWK set; the demo host signs RS256 in 20 lines of standard-library Python (`tools/elements-demo/demo-host-key.json` is a demo key).
4. **The user must already exist** in Drishti for an exchange (the OIDC rules create users at sign-in, not at exchange); an ID token's user is named as the sign-in names them (`UserService.federatedName`).
5. **Rates count every embed call** (views, streams, panel rows, `explain`, `resolve`) per application and per user, in a one-minute window held in memory per server; with several servers each counts its own calls. The per-application `callsPerMinute` and `userCallsPerMinute` replace the design's `views` wording.
6. **`provenance.masked` is counted on the built view** (strip, title and every panel's data, text containing the mask), only when a mask changed the document; `maskedPanels` lists the panel ids. It travels in live `provenance` patches.
7. **A typed command resolved for a host is not remembered** in the user's command history (the section 6.6 promise, now enforced on the server).
8. **The ErrorCode registry holds `DRS-8006`** though only the console raises it (step 7), like `DRS-5003` the other way round.
9. **Tokens that outlive a restart** need `drishti.embed.signing-key`; without it the key is made at start, a warning is logged, and a second server cannot verify the tokens of the first.
10. **Not built here:** nothing left from this group: usage counters came with step 10, and the console's `EmbedAuth`, `event: token` and `/channel/{cid}/token` came in step 7.

##### As built: steps 4, 5 and 8 (group B, 2026-10-06)

**Step 4, the channel as a class.** `drishti-console/core/channel.py` `ChannelSession` holds what `api_routes.channel()` used to
keep in closures: the subscriptions and their upstream responses, the queue, the detached tasks, the watchdog and the SSE text
(`events(asked, is_disconnected)`). It knows no request or cookie: the identity to run as, the `render(event, data)` that turns
an upstream event into what the caller wants (`_view_event`), the limit (`live.max_subscriptions`), the `who` fingerprint, the
owning application and the registry dict are constructor arguments. The owner of a channel is the **user plus the host
application** (`owned_by(user, app)`; empty for the console): `POST /api/channel/{cid}` refuses a channel opened by an embed
caller, `POST /embed/v1/channel/{cid}` refuses one opened by the console or another application. `/api/channel` is a few lines
over it and its behaviour is unchanged (`test_live_hub.py`, `test_live_deleted.py` and `test_terminal.py` pass untouched; the
`CHANNELS` registry keeps its shape). `tests/test_channel_session.py` runs two users on two channels, the owner rule, the
told (not silent) limit, the registry clean-up and `change(add, remove)`. The embed stream passes `embed=True` to
`_view_event`, so live panel patches carry the same no-console-links markup as the first paint.

**Step 5, the view parts as macros.** `templates/_macros/view.html`: `vtitle`, `strip`, `vhead` (title plus strip, the head of an
embedded view), `panels(items, embed)` and `provenance_banners(vm, asof, business_date, embed, back_href)` (no data held for the
date, the date actually shown, a late source). `terminal/view.html` calls them and `routes/embed_routes.py` `_render` calls the
same macros, so there is no second template (`templates/embed/vhead.html` is gone). `panel(p, embed=false)` in `panels.html`
drops, for `embed=true`, the two things that mean nothing in a host page: the help link to the console's `/help/rachana-reference#panels-the-twenty-two-kinds-and-developing-a-new-one`
and the CSV download. The console page itself still renders with `embed=false` (also in its `?embed` iframe mode, unchanged).
The payload's `banners` is now filled (it was `[]`) and the head carries no console-only affordances (alert, pin, share, JSON,
print, About, Discussion, Compare stay in `view.html`). `tests/test_view_macros.py` proves a panel's HTML is the same on the
console page and in the embed payload for every panel (after removing only the help and download links), that the strip is the
same bytes, and that the banners come from the macro.

**Step 8, the element stylesheet.** `python3 tools/elements_sheet.py` (library: `core/element_sheet.py`) writes
`drishti-console/web/elements/drishti-view.css` and `drishti-view.manifest.json` (version = first 12 hex digits of the sheet's
hash, source list, glyph to file map, size, gzip). Adjustment 1 is applied: of Bootstrap Icons only the base `.bi::before` rule
and the 9 glyphs the macros and the element's scripts name are kept, comments and the licence block of each source are
dropped (the generated file carries one header), and the result is **80.2 KB, 15.7 KB gzipped** (POC: 30.5 KB;
target 25 KB). The sheet has no `@font-face`: the element registers the font with `new FontFace(...)` and `document.fonts.add`
(the spike finding; it already did). The font file itself is still the full 92 KB woff2 (no `fontTools` here to subset it; a
subset font is the follow-up if the 92 KB matters). Served at `/embed/v1/drishti-view.css` (was `/poc/...` until step 7; now the committed file, not rewritten per
request) and at `/embed/v1/elements/<version>/drishti-view.css` with `Cache-Control: public, max-age=31536000, immutable` and a 404
for any other version. The guard, `tests/test_element_sheet.py`, fails when the committed files differ from a fresh build, when a
macro names a glyph the sheet lacks, when the sheet holds one nothing uses, or over the gzip target. Step 7 will serve the element
script, font and `integrity.json` under the same `/elements/<version>/` path. `test_embed_elements_browser.py` passes in Chromium
(14 tests) with the generated sheet.

##### Step 7 as built: the console's embed API (2026-10-06)

The proof of concept's `/embed/v1/poc/*` routes are now the versioned API under `/embed/v1` (the `poc` aliases are gone; the demo,
the element and the tests use the new paths). `core/embed_auth.py` (`EmbedAuth`), `core/embed_limits.py` (`EmbedLimits`),
`core/embed_stream.py` (`StreamGuard`), `core/embed_wire.py` (compression), `core/embed.py` (`EmbedHosts`, origins, `EmbedError`) and
`routes/embed_routes.py`.

| Route | What |
|---|---|
| `GET /views/{kind}/{id}` | the HTML-over-the-wire payload (section 6.1) |
| `GET /views/{kind}/{id}/about` | the About body (HTML), for the caller's masked identity |
| `GET /views/{kind}/{id}/panels/{panel}/records` | the rows a Pivot works on |
| `GET /resolve?text=` | a typed command or id to `{ref, mnemonic, title}`: the server's read-only `POST /command`, as the embed token's scope allows, no history |
| `GET /channel?s=view:...` | the live stream (one per page; only `view:` keys), with `event: token` |
| `POST /channel/{cid}` | `{add, remove}`; owner = user and host application |
| `POST /channel/{cid}/token` | a fresh embed token in the `Authorization` header (never the URL) for an open stream |
| `GET /drishti-elements.js`, `/drishti-view.css`, `/elements/{version}/drishti-view.css`, `/icons.woff2`, `/charts.js`, `/echarts.js`, `/js/{name}.js` | the element and what it loads |

**Decisions as taken.**

1. **`EmbedAuth` verifies every token before any work**: ES256 signature against the server's key set (`GET /api/v1/embed/jwks`, cached
   `embed.jwks_ttl_seconds`, 300), `typ` `drishti-embed+jwt`, `exp` (5 s leeway), `sub` and `azp` present, the audience against
   `embed.audiences` (empty accepts what the server issued; the server enforces its own list), and the browser's `Origin` against the
   token's own `origins` claim (`403 DRS-8002`) as well as against the registered origins of all applications (the CORS allow-list from
   `GET /api/v1/embed/apps/origins`, `embed.origins_ttl_seconds`). **Key rotation:** a token naming a `kid` the cache lacks makes one refetch,
   at most every `embed.jwks_min_refetch_seconds` (10), so forged key ids cannot make the console hammer the server; an unknown key is
   `401 DRS-8001`. The token is then passed to the server untouched; the server still decides on every call. Needs the `cryptography`
   package (in `requirements.txt`).
2. **Token life of a stream** (`StreamGuard`, Decision 2): at the token's `exp` the console sends `event: token`
   (`{"ch": "", "d": {"expiresIn": 0}}`); the element has already been renewing 45 s before expiry (`Connection.renew`: the host's
   `tokenProvider`, then `POST /channel/{cid}/token`), so normally nothing is seen. The fresh token must be for the same `sub` and `azp`
   and extend the expiry, else `401 DRS-8001`; later upstream calls use it; no stream is dropped and no element repaints. With no fresh
   token within `embed.token_grace_seconds` (30) the stream ends with `gone` `DRS-8001` and `end`: expiry is enforced. Every
   `embed.recheck_seconds` (60) the console asks the server's `GET /embed/check` and ends the stream with the server's code when the
   user or the application was disabled.
3. **Limits** (`EmbedLimits`): `embed.rate_per_minute` (600) calls per host application, a sliding window, `429 DRS-8004` with
   `Retry-After`; `embed.max_streams` (64) upstream subscriptions across all embed channels (a channel open past it is `429 DRS-8004`,
   `Retry-After: 5`; a subscription added to an open channel past it gets `gone` `DRS-5003`), so embedding cannot take the pool of
   `backend.pool_size` from the console's own users. `0` means unlimited for both.
4. **`Drishti-Embed-Api`**: a request header naming a major version other than 1 is `410 DRS-8006` (raised by the console only).
5. **Compression**: view, About, rows and assets are sent Brotli (when the browser accepts it and the optional `brotli` package is installed)
   or gzip above `embed.compress_min_bytes` (512), with `Vary: Accept-Encoding, Origin`; the event stream is never compressed.
6. **Problems** are `{"code": "DRS-…", "detail": "…"}` as before, now also with `Retry-After` for every `429`.

**Tests.** `tests/test_embed_auth.py` (good, expired, wrong audience, wrong origin, tampered, forged signature, unknown and rotated `kid`,
refetch throttling, TTL, server away; the guard: `event: token`, a fresh token keeps the stream open, silence ends it with `DRS-8001`,
foreign or older tokens refused, the server re-check ends it), `tests/test_embed.py` (CORS per origin and per application, rate, stream
cap, contract version, compression, forged tokens never reach the server) and, in `test_embed_elements_browser.py`,
`test_step7_two_elements_one_channel_on_the_versioned_api` (acceptance row 7: Chromium, Firefox, WebKit). **Not done here:** the full element (step 9);
a browser test of the live renewal (the element renews 45 s before a 5-minute token ends; covered by the unit tests of the guard).

#### Step 9 as built: the full `<drishti-view>` (2026-10-06)

**Code layout.** `web/embed/drishti-elements.js` is now the entry (41 lines: defines the element, publishes `window.DrishtiElements`
with `configure`, `resolve`, `invalidateToken`, `connections`, `trustedTypes`). The library is ES modules under `web/embed/`, served
as `GET /embed/v1/m/<name>.js` (CORS like the entry; only files that exist there, never the entry itself) and imported with
relative `./m/` URLs, so one `<script type="module">` line still loads everything: `config` (settings, `esc`, `problem`), `tokens`
(provider, expiry, `authed`, `token-needed`), `sanitize` (allow-list and the Trusted Types policy), `assets` (the adopted sheet, the
icon font through `FontFace`, the scripts), `connection` (one per page and server), `enhancers` (the console's scripts started on
the shadow root), `frames` (live patches), `element` (the state machine). Each file is under 310 lines. The console's enhancer
scripts stay classic scripts on the console's own pages (a module conversion would change their load order for no gain under
`script-src 'self'`); `enhancers.js` is their ES-module face for the element (load once, `init(shadowRoot, options)`, dispose). The one
change inside them: `about.js` takes `options.setHtml`, so the About body is written through the element's sanitiser (the console's
default is `innerHTML`, unchanged).

**State machine** as in section 7 (last request wins: one sequence number and `AbortController` per load; attribute changes in one
task coalesce into one load; a refresh keeps the subscription, a reload ends its epoch). Added in this step: the `loading`, `empty`
and `error` slots, `density`, the reserved `panel`, `drishti:token-needed`, and a token provider set after an element failed with
a 401 reloads it. `entity` stays the attribute for the entity id (the design's name; `id` is the page's own DOM id attribute and
would turn every `<drishti-view id="main">` into a request for an entity called `main`).

**Tokens.** The provider may be sync or async and return a string or `{token, expiresAt}` (else the JWT's `exp`). A token is reused
until 60 s before it ends, or until 40 % of its life remains when that is shorter (a 30 s token is replaced at 18 s); one new token
on a 401. The open channel gets the fresh token by `POST /embed/v1/channel/{cid}/token` before the old one ends (the same margin), and
at once on `event: token`; nothing repaints and the channel is not reopened.

**Sanitiser and Trusted Types.** Every string of markup from the server (header, panels, live patches, the About body, strip cells) is
parsed inertly in a `<template>` and rebuilt from an allow-list: the macros' elements (tables, definition lists, buttons, `svg`
primitives, headings...) and attributes (`class`, `id`, `role`, `title`, `href`, `data-*`, `aria-*`, table and svg geometry...);
`script`, `style`, `iframe`, `object`, `embed`, `link`, `meta`, `img`, `use`, `foreignObject`, `template`, comments, every `on*` and
`style` attribute and any `href` that is not relative, `#`, `http(s)` or `mailto` are removed; unknown elements are unwrapped. The
single policy `drishti-elements` (`createHTML` sanitises; `createScriptURL` accepts only `/embed/v1/` paths) is created when the
browser has Trusted Types, so a host sends `require-trusted-types-for 'script'; trusted-types drishti-elements` and nothing more
(`/?tt=1` of the demo host does). No other sink is used: the element builds its own markup with `setHtml`. ECharts, tables, tree
rows, the pivot and About ran under it unchanged.

**Other behaviour kept from step 6/7:** pause when the page is hidden or the element scrolled away (grace `hiddenGraceMs`, then
resume reloads and resubscribes), the enhancers' `ResizeObserver` on the host (charts follow a host's split panes), links as
composed cancellable `drishti:navigate`, `?`/`Esc`/`Alt+n` only while the focus is inside the element.

**Tests** (`test_embed_elements_browser.py`, Chromium, Firefox, WebKit; the installed Firefox and WebKit have no Trusted Types, that test skips there):
rapid switching last-wins and request aborts, two elements one channel (step 7's versioned-API variant too), hidden tab pauses then
resumes, navigate through the host, `test_the_sanitiser_strips_scripts_and_handlers_from_a_payload` (the response is tampered with in
flight: script, iframe, img, `onerror`, `onclick`, `javascript:` and inline style all gone, `data-*` kept, nothing runs),
`test_trusted_types_enforced_host_still_works`, `test_token_renewal_keeps_the_stream_open_past_the_tokens_expiry` (a 30 s token: the
provider is asked again, renewals go to the open channel, one channel only, still ticking after the first token's expiry),
`test_slots_show_host_content_and_a_missing_token_is_an_event`; `test_embed.py` serves the modules. Acceptance row 9 passes in the three
browsers: adopted sheets under the host CSP, icon font via `FontFace`, streamed fetch with `Authorization`, composed events, ECharts in
the shadow root, no style leak either way.

**Not built:** the `toolbar` slot, `openAbout(panelId)` and `focusPanel(id)` methods, a `locale` attribute
(the sheet has no locale yet); `live.js`/`view-panels.js` splits of step 6 remain not done (the element applies frames itself in `frames.js`).

##### Step 10 as built: Admin → Embedding (2026-10-06)

**Server.** `EmbedUsage` (one bean, `server/embed`) holds per registered application, lock-free (`LongAdder`): tokens issued, embed calls, refusals by DRS code, live streams open and the last use; only ids the registry knows are counted (the rest share the `(unknown)` bucket), so memory is bounded by the registry. `EmbedTokenService` records an issued token, a refused exchange and, around `authorize`, every call and every refusal (the application is taken from the token once its signature has been checked). `StreamController` counts a live stream open for an embed principal and closes it with the stream. The numbers are also Micrometer meters `drishti.embed.tokens`, `.calls`, `.refusals{code}` and `.streams`, tagged `app`. `GET /api/v1/admin/embed/usage` returns them with the registration form's choices (`settings`: the scopes this server defines, the limits, whether wildcard origins are allowed). Counters start again at a restart; there is no retention to configure, because nothing grows with time. **Disable** is `POST .../{id}/disable` and `/enable` (audited `embed-app-disabled` / `-enabled`), distinct from delete.

**Config apps.** `drishti.embed.apps` is a list of registrations (`secret-sha256`, never a secret; or `jwks`). `EmbedAppStore.setConfigured` validates each with the same rules as a registration (a bad entry stops the start, naming the id), keeps them in the snapshot beside the stored ones (`fromConfig: true`, last use in memory only, nothing written to the identity database) and refuses `create`, `update`, rotate, disable and delete for those ids with `409`. A configured id wins over a stored one of the same id.

**Console.** `/admin/embedding` (`routes/embed_admin_routes.py`, `admin/embedding.html`, `admin-embedding.js`, `admin-embedding.css`) in the Admin menu and the Admin tabs: the list with usage, *New application* (id follows the name; scopes come from the server), the secret dialog (shown once, forgotten on close, `no-store`), rotate with a grace period, disable and enable, delete behind a confirm that focuses *Keep it*; keys `n`, `/`, `Esc`; 44 px targets under `pointer: coarse`; the page is in the 390/1600/2560 sweep. The help `?` opens *Embedding host applications* in the Users and roles guide, which F1 on any admin page reaches.

**Not built:** editing an existing registration in the page (origins, scopes and limits change through `PUT /api/v1/admin/embed/apps/{id}`; rotate, disable and delete are in the page), and apps declared in a pack's own files (only the server's configuration, which a pack's settings can feed through the environment).

#### Steps 11 to 15 as built (2026-10-11)

**Step 11, tests.** The foreign-origin host application exists since step 0 and is what the browser tests run against: `tools/elements-demo` on its own port, its own sign-in and backend doing the RFC 8693 exchange, the page served with a strict policy (no inline, no frames, only the console beyond itself;
`/?tt=1` also enforces Trusted Types). The tests of section 14 are in `test_embed_elements_browser.py` and `test_embed_controls_browser.py` (Chromium, Firefox and WebKit): the live trade, masks per user, the search box, recent list, as-of picker, `drishti:navigate`, rapid switching, two elements on one connection, token
expiry and renewal, a console restart, the sanitiser, Trusted Types, slots. New in this step: the **small load test** (`test_twenty_elements_on_ten_entities_cost_the_page_one_connection`: 20 elements, 10 entities, one channel stream, every element live, frame latency recorded) and the host page of `<drishti-report>`
(`tools/elements-demo/static/report.html`, `test_embed_report_browser.py`). The frameworks smoke test was first not built (the builds were not vendored); it is built now as the React 18, Vue 3 and Angular 18 sample pages with `test_embed_frameworks_browser.py` (builds fetched by `fetch-vendor.sh`, never committed: [section 19](#react-angular-and-vue-sample-pages)).

*Since 2.4.0* the demo host's two elements show **trade valuations** (`TVAL-1000008`, `TVAL-1000002`, the market-risk pack): since 2.3.0 a trade is
static (it changes when its source books a new version) and the valuations tick. The suites follow: the masks are checked on the trade the
valuation values, the tables, pivot and a live subscription built with control values on a netting set, the controls of a trade on its *Events*
panel, `drishti:navigate` from the trade linked in the strip. With the console's several workers (`server.workers`) the channel's id names the
worker that holds the stream, and a change or a fresh token that reaches another worker is passed on to it (`core/channel_relay.py`), so the
page keeps its one connection.

**Step 12, distribution.** `core/element_dist.py` holds the library's **semantic version** (`VERSION`, `1.3.0`; the entry reports it as `DrishtiElements.version`). The console serves it **exactly and immutably** at `/embed/v1/elements/1.3.0/drishti-elements.js` and `.../m/<name>.js`
(`Cache-Control: public, max-age=31536000, immutable`, `ETag` = the file's SRI value) with `.../integrity.json` (`{"name", "version", "api", "files": {<path>: {"integrity": "sha384-…", "bytes"}}}`); a partial version (`/embed/v1/elements/1/…`, `/1.1/…`) answers `302` to the exact one (`max-age=60`, with CORS, so a module graph follows it);
a version this console does not hold is `410 DRS-8006` (it says which one it serves), a malformed one `404`. `web/elements/integrity.json` is the committed record and `tests/test_element_package.py` fails when a module changed and `VERSION` did not, so **a version names the same bytes for ever**. The unversioned
`/embed/v1/drishti-elements.js` (revalidated each time) stays for hosts that follow the console. **The npm package** `@drishti/elements` is a folder, `drishti-console/web/elements/package/`, made by `python3 tools/elements_package.py` (no network, no Node): `package.json` (an `exports` map, `type: module`), `dist/` (the same modules byte for byte,
in the layout the console serves), `types/index.d.ts` and `types/react.d.ts` (**generated from one source**, `core/element_api.py`, which also generates the reference tables and the package README, and is checked against the real elements by a test), `integrity.json` and `README.md`. The sheets, the icon font and the chart code are not in
the package: they are versioned with the console that serves the data and the library fetches them from `server`. Publishing to a registry (`npm pack`, `npm publish`) is the release build's job and is not done here. **`Deprecation` and `Sunset`**: `embed.lifecycle` in the console's settings (`{"1": {deprecation, sunset, link}}`) adds
`Deprecation: @<epoch>` (RFC 9745), `Sunset: <HTTP date>` (RFC 8594) and a `Link` to every `/embed/v1` answer from the day it is announced, and from the sunset day the embed calls are `410 DRS-8006`. Nothing is announced by default; there is no `/embed/v2/` yet, so the mechanism is tested with a configured date, not in use.

**Step 13, documentation.** The developer guide [section 19](#19-drishti-elements-developer-guide) (also in the help centre): the five-minute start, the element reference (generated tables), the token exchange for the host backend, CSP and SRI, theming, a cookbook, troubleshooting by DRS code; `CONFIGURATION.md` (`embed.lifecycle`, the scope),
`API_GUIDE.md` (the report paths), the help-centre entry, and [ADR-021](adr/021-embedding-is-html-over-the-wire.md) for Decision 1 (and what `<drishti-report>` does instead, where there is no server-rendered HTML to put over the wire).

**Step 14, phase 2 elements.** **`<drishti-panel>`** is built: `<drishti-view>` with the `panel` attribute (`panel="legs"` is `panels="legs"`) and the header off unless asked for (`web/embed/panel.js`, 40 lines; a Chromium test). **`<drishti-about>` (scope `embed:about`) and `<drishti-search>` (scope `embed:search`, `GET /api/v1/embed/search`, masked and cut to the application's kinds) are built** in library 1.4.0 (wave D: [RUPAKA.md section 20.14](RUPAKA.md#2014-ai-distribution-and-embedding-wave-d-as-built)).

**Step 15, optional.** Not built: workspaces on `<drishti-view>` in place of iframes, and a host-served SharedWorker for HTTP/1.1 hosts. Neither is needed by anything else, and both are larger than their value until a host asks.

#### Steps

| # | Step | Size | Group | Depends on |
|---|---|---|---|---|
| 1 | **Server: host applications and token exchange**: identity table and store, `EmbedTokenService` (RFC 8693, both client auths, both subject types, `jti`), ES256 key and `/embed/jwks`, `drishti.embed.*` config, audit events, rate limits on the endpoint, `DRS-8001…8006` in `ErrorCode` | L | A | 0 |
| 2 | **Server: embed tokens on every call**: `TokenFilter` branch, GET allow-list, `raw` removed, kinds narrowed, app/user checked per call, `AccessRecorder` `embed:<app>`, `GET /embed/apps/origins` for the console | M | A (after 1's token format is fixed) | 1 |
| 3 | **Server: `provenance.masked`** in the ViewModel and in frames (count, panel ids) | S | A | 0 |
| 4 | **Console: channel as a class**: extract `ChannelSession` from `api_routes.channel()` (owner = user + app), `/api/channel` unchanged on top of it; existing live tests pass untouched | M | B | 0 |
| 5 | **Console: view parts as macros**: `vhead`, strip, banners out of `view.html` into `_macros/view.html`, with `embed=True` dropping console-only affordances in panels too; `view.html` uses them | S | B | 0 |
| 6 | **Console: root-scoped modules**: `live.js` split, `view-panels.js`, `charts.js`, `tables.js`, `tree-rows.js`, then `about.js`/`about-hints.js`, `pivot.js` split; `view-boot.js` loader; console browser tests green after each | L | B | 0 |
| 7 | **Console: embed API**: `EmbedAuth`, CORS per app, `/embed/v1/views`, `/channel` (+ `/token` re-auth), `/about`, `/records`, `/resolve`, rate limits, `embed.max_streams`, compression | L | C | 2, 4, 5 |
| 8 | **Element sheet build**: CSS rewrite (`:root` → `:host`, themes), icon font via `FontFace`, served at `/elements/<v>/` with immutable caching, integrity.json | M | B | 0 |
| 9 | **`<drishti-view>`**: element, state machine (attribute/property changes abort and reload, last request wins), `Connection` (Hub with `FetchEventSource`, one per page), `DrishtiElements.resolve`, token provider, pause/visibility, `ResizeObserver`, links → events, sanitiser, Trusted Types policy, keyboard scoping, slots and parts | L | C | 6, 7, 8 |
| 10 | **Admin → Embedding** page and API (list, new, rotate, disable, usage) | M | C | 1, 2 |
| 11 | **Tests**: foreign-origin host app in Playwright (grown from step 0's sample: search box, recent list, as-of picker, navigate round-trip, rapid switching, two elements on one connection), frameworks smoke in three browsers, small load test | M | D | 9 |
| 12 | **Distribution**: npm package build, TypeScript declarations, version aliases, SRI publication, `Deprecation`/`Sunset` | M | D | 9 |
| 13 | **Docs**: `docs/architecture/CLIENT_RENDERER.md` with the cookbook, CONFIGURATION, API_GUIDE, help centre entry, ADR-021 | M | D | 9, 10 |
| 14 | **Phase 2 elements**: `<drishti-panel>` (one panel, same API with `panel` attribute), `<drishti-about>`, `<drishti-search>` (the type-ahead: needs a search scope `embed:search`) | L | E | 11 |
| 15 | **Optional**: workspaces on `<drishti-view>` instead of iframes; a host-served SharedWorker for HTTP/1.1 hosts | M | E | 9 |

Critical path: 0 → 1 → 2 → 7 → 9 → 11. Groups A and B run in parallel after step 0; C after both; D in parallel after 9.

### 17. Decisions for the product owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | Where rendering happens | **HTML over the wire from the console** with the existing macros; no JavaScript renderer |
| 2 | Long-lived streams and five-minute tokens | **re-authorise in place** (`event: token`), not reconnect |
| 3 | Connection sharing | **one connection per page** in v1, console on **HTTP/2** for embedding; host-served SharedWorker later |
| 4 | How the host says who the user is | **ID token or a host-signed assertion**; no plain username with a client secret |
| 5 | Masks for users with `raw` | **always mask** in embedded views |
| 6 | Console scripts | **refactor the console's own scripts** into root-scoped modules used by both |
| 7 | Distribution | **served by the console**, mirrored to npm, exact versions with SRI |
| 8 | Embedding switched on | **off by default** (`DRISHTI_EMBED_ENABLED`), per deployment; host apps registered by an administrator only |
| 9 | Shadow root mode | **open**: closed protects nothing on a shared page and hurts host testing |
| 10 | Personal arrangements in embedded views | **not shown**: the Sutra's layout only, predictable for host layouts |
| 11 | Event names | **keep `drishti:*`** (the console's convention: `drishti:frame`, `drishti:theme`); React and Angular bind them with `addEventListener` (section 5.9), optional thin wrappers later map them to framework outputs |

### 18. `<drishti-report>`: a published report in another web application

Built in Rupaka phase 7 ([RUPAKA.md section 20.6, part 3](RUPAKA.md#206-part-3-embedding-and-phone-layouts-as-built-phase-7-complete)). The reference tables of every attribute, property, method and event are in the developer guide ([section 19](#19-drishti-elements-developer-guide)) and generated from
`drishti-console/core/element_api.py`; this section is the design.

#### 18.1 What it is

A published report, one **page** of it or one **visual** of it, in a Shadow DOM with no iframe, drawn with the **console viewer's own code**, so a visual looks and behaves as it does in the console: the 12-column grid, the page tabs, slicers, the business date,
bookmarks, "click a mark to filter or highlight", **cross-filtering** between the visuals, "show as a table", the live indicator and live redraws. The difference is the transport and the chrome:

| | Console viewer (`bi-report.js`) | `<drishti-report>` (`report.js`, `report-page.js`) |
|---|---|---|
| Calls | same-origin `/bi/api/...` with the session cookie | `GET/POST /embed/v1/reports/...` with the host's embed token |
| Live | `bilive:<id>` on the console's channel (`channel.js`) | `bilive:<id>` on the page's one embed channel (`connection.js`) |
| Phone layout | the screen's width (`matchMedia`, 640 px) | **the element's own width** (`ResizeObserver`, 640 px) |
| Colours | the page's `--d-*` tokens | the element's tokens (the host sets them; the theme attribute) |
| Drill down, drill through, alerts, rows behind a visual, Export, Subscribe, Perspective | yes | **no** (section 18.5) |

The code is shared, not copied: `bi-lib.js`, `bi-phone.js`, `bi-visuals.js`, `bi-grids.js`, `bi-frame.js` and `bi-live.js` are the console's classic scripts, served to the element by `GET /embed/v1/js/<name>.js` (the allow-list `ENHANCERS` of `routes/embed_routes.py`) and
loaded once per page into `window.drishtiBi`; ECharts is `GET /embed/v1/echarts.js`. The element builds its DOM with `createElement` and `textContent` only: **no markup from the server is parsed**, so there is no sanitiser step and nothing a Trusted Types host would refuse.
Its style sheet is the view sheet plus a second one generated the same way from `bi.css`, `bi-report.css` and `bi-board.css` (`drishti-report.css`, 26 KB, 5.5 KB gzipped; `tools/elements_sheet.py`), adopted after it.

Attributes: `report` (required), `page`, `visual`, `filters` (initial state as JSON), `chrome` (`full` or `bare`), `theme`, `live`, `server`, `label`. With `visual` set the element is that one visual in a card (chrome `bare`), found on whichever page holds it;
that is the `<drishti-visual>` of the first plan. Events (all `drishti:`, composed): `loaded`, `page-changed`, `filter-changed` (`source: 'user'` or `'api'`), `masked`, `tick`, `state`, `error`, `token-needed`. The `filters` property reads the filters in force and assigns them.

#### 18.2 The embed API for a report

All under `/embed/v1`, CORS and tokens as section 6; `routes/embed_report_routes.py`.

| Call | Does | Server path (embed scope `embed:report`) |
|---|---|---|
| `GET /reports/catalogue` | the visual types and their groups (the element needs to know which are static, slicers or Perspective) | `GET /api/v1/bi/reports/catalogue` |
| `GET /reports/{id}` | the report: its model (`report`), the dataset's summary (`dataset`), `title`, `description`, `problems` (errors only) and `valid`; **never the YAML**, the warnings or the authoring flags; `403 DRS-8005` unless the report is **published** | `GET /api/v1/bi/reports/{id}` |
| `POST /reports/{id}/query` | one visual's rows: `{page, visual, state: {slicers, selections, dates, carry, drill}}`, 64 KB at most | `POST /api/v1/bi/reports/{id}/query` |
| the channel key `bilive:<id>` | `hello`, `tick`, `status` of the report's live tables (no data: a tick makes the element ask its visuals again, as the person looking) | `GET /api/v1/bi/reports/{id}/live` |

The server sees the embed caller's own token on every one (a `Principal` with `embedApp`), so **masks and row rules are the token's user's**, and an embedded call masks `drishti.security.redact` fields and the dataset's own masked columns whatever the user's roles (Decision 5): the same user's trader column
reads `•••` in the host's card and the real name in the console. The query engine's per-person limit (three at a time) applies; the element queues its visuals three at a time and retries a `429`.

**Which hosts may embed which reports.** `embed:report` opens only the three paths above (and `GET .../catalogue`); the paths that author or validate a report (`dataset`, `validate`, `format`, a draft's `query`) are refused to an embed token (`403 DRS-5002`) by name. Which reports a host may show is the host
application's existing **`kinds` list**, entered in Admin → Embedding: `report:<id>` allows one report (`report:file:report:trades-overview`), `report:*` every report, and an application whose list holds only entity kinds (`trade`, `counterparty`) may show no report (`400 DRS-8005`); an empty list means everything the
user's roles open, as for views. The check is in the server's `EmbedTokenService` (the kind of a `/api/v1/bi/reports/{id}` call is `report:<id>`), on every call. That a report is published is checked by the console on the definition (an unpublished report is `403 DRS-8005`); the server does not know the
difference for a direct call, so the `kinds` list is the boundary that is enforced.

#### 18.3 Phone layout in the element

The element measures **its own width** (`ResizeObserver` on the host), not the page's: at 640 px or narrower, and when the report declares `layouts.phone` for the page, it draws the arranged page (`order`, `hide`, `span`: [REPORTS.md](../guides/REPORTS.md#14-phone-and-tablet-layouts)) and swaps back to the grid when it grows, with no reload. A hidden visual is not queried.
Without a layout the container rule of the sheet (`@container drishti (max-width: 760px)`) stacks the grid into one column as for any report. `drishti:page-changed` carries `phone: true` when the arranged layout is on screen.

#### 18.4 Security and host policy

The host's policy needs what `<drishti-view>` needs ([section 10](#10-security-threats-and-answers)) and nothing more: `script-src` and `connect-src` and `font-src` name the console. A report's **image** visual loads its `src` as the host's `img-src` allows (a host that wants none shows the alt text only).
Perspective needs WebAssembly under a narrow policy of its own and is not loaded: the element shows the designer's note instead. Tokens are handled as for views (renewal in place on the open channel; the stream ends with `DRS-8001` after the grace period).

#### 18.5 Not built

Drill down and through, "alert me", "show the rows behind this visual" and Export and Subscribe (the console viewer's chrome); Perspective visuals; cross-filtering **between** two elements; bookmarks are applied but not created. They are listed in [RUPAKA.md section 20.6, part 3](RUPAKA.md#206-part-3-embedding-and-phone-layouts-as-built-phase-7-complete).

#### 18.6 Tests

`test_embed_report.py` (the console routes: trimmed definition, published only, token passed on, the 64 KB limit, CORS, modules, the sheet), `test_bi_phone.py` (the shared layout code, in Node), `EmbedTokenTest` (the scope, the allow-list, the authoring paths, `report:*`), `ReportParserTest` (the layout), and
`test_embed_report_browser.py` (Chromium, a host on its own origin under a strict policy against a real server and console: the live report with a ticking source, masks versus the console for the same user, a click on a bar telling the host, the `filters` property, the allow-list refusing another report,
one visual without chrome, the phone layout in the element and the console, the designer's Phone tab). Pictures and captured output: `tests/embed_report_shots.py`.

### 19. Elements 2.0: drawn by the client-side renderer

**Built 2026-10-30 for 2.9.7, beside Elements 1.x for one release** ([ADR-022](adr/022-one-renderer-in-the-browser.md),
[section 13](#13-the-elements-versioning-and-distribution)). Elements 1.x (this document's sections 1
to 18) is unchanged: HTML over `/embed/v1/`, version 1.4.3. Elements 2.0 keeps 1.x's element API and changes only the inside: the element
draws with Drishti Render 1.0.0 from the ViewModel the embed API v2 answers as JSON. A page uses one major or the other.

| | Elements 1.x | Elements 2.0 |
|---|---|---|
| Import | `/embed/v1/drishti-elements.js` (or `/embed/v1/elements/1.4.3/…`) | `/embed/v2/elements/2/drishti-elements.js` (a `302` to `/embed/v2/render/1.0.0/drishti-elements.js`) |
| The view | `GET /embed/v1/views/{kind}/{id}`: head, banners and panels as the console's HTML, sanitised | `GET /embed/v2/views/{kind}/{id}`: the ViewModel (`"contract": "1.0"`), drawn by the renderer; `Drishti-Subscribe` names its live key |
| Parts | `…/panels` and `…/about` as HTML | `…/panels` (panels and strip cells) and `…/about` (`{explain, index}`) as JSON; `…/panels/{panel}/records` as in 1.x |
| Live | `/embed/v1/channel`, `view:` keys, panel patches as HTML | `/embed/v2/channel` (the same channel code), `vm:` keys, panel patches as panels |
| Behaviours | the console's scripts on the shadow root (`enhancers.js`) | the same scripts, started by the renderer (`behaviours: 'console'`): funnels, Chart any table, the Pivot grid, the nested-pivot grid, drills in place, zoom, controls |
| About | the console's drawer HTML | drawn by the renderer from JSON, a side sheet inside the element |
| Tags | `<drishti-view>`, `<drishti-panel>`, `<drishti-report>`, `<drishti-about>`, `<drishti-search>` | `<drishti-view>`, `<drishti-panel>`, `<drishti-screen>` (an alias of `<drishti-view>`); `<drishti-report>` stays 1.x |

**The same API.** Attributes `kind`, `entity`, `as-of`, `known-at`, `theme`, `density`, `header`, `live`, `server`, `panels`, `panel`,
`label`, `controls`, and one new, `navigation` (unset: a link changes `kind` and `entity` as in 1.x; `in-place`: drawn in place with a
Back bar; `host`: only the event). `DrishtiElements.configure({ tokenProvider })`, `resolve()`, `invalidateToken()`, `connections()`;
`reload()`, `refresh()`, `state`, `view`, `lastError`, `handle` (the renderer's handle); the events `state`, `loaded`, `paint`, `tick`,
`navigate` (cancelable), `error`, `masked`, `token-needed`, plus the renderer's (`about`, `panel`, `row-click`, `select`); the `loading`,
`empty` and `error` slots. `window.DrishtiElements.version` is `2.0.0` (and `renderer`, `contracts`, `api`).

**Security, unchanged in kind.** The bearer token on every call (never the cookie), CORS by the exact origins of the registered host
applications, masks decided by the server, no markup from data (the renderer builds the DOM; there is nothing to sanitise), no eval. A
host that enforces Trusted Types names `drishti-render` (script URLs of the console only) beside `drishti-elements`.

**Ending 1.x.** `embed.lifecycle."1"` announces it (Deprecation, Sunset, then `410 DRS-8006`), as section 13 describes; v2 has its own
(`embed.lifecycle."2"`). The demo host (`tools/elements-demo`, `/?elements=2`) runs the same page with Elements 2.0.

**Tests.** `test_embed_elements2_browser.py` (a real server, the console and the host on their own ports, Chromium: live under the host's
CSP from the ViewModel, masks, a link the host takes over, the console's behaviours and About inside the element, one connection with
`vm:` keys, the versioned paths and the bytes against the integrity record); `test_render_phase2.py` (the v2 routes, CORS, the contract,
the partial versions, Elements 2's address); Elements 1.x's own tests unchanged.

## 21. GenUI: screens composed for an AI agent (design, not built)

Status: **design, not built.** Not in a release yet: planned for 2.9.0 (wave I, AI) until 2026-10-25, then parked (2.9.0 to 2.9.5 shipped without it; the plan files AI and GenUI under 2.12.0; see [BI_DESIGN.md §12.2](BI_DESIGN.md#122-the-three-waves)
and [IMPLEMENTATION_PLAN.md › Roadmap](IMPLEMENTATION_PLAN.md#roadmap)). Off by default when built.
Names marked **(new)** do not exist in the code today; every other class, file and endpoint named here was checked
against the repository on 2026-10-18.

### 1. The ask

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
[Drishti Elements](#20-drishti-elements-the-design), inside other web applications. GenUI adds a third source of screens next to written
Sutras and inferred ones: **composed** ones.

### 2. The idea in one picture

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

### 3. Principles and non-goals

#### Principles

1. **Composed, not generated.** A GenUI screen is a Sutra assembled from fragments that people wrote and reviewed. No
   code an agent wrote ever runs in a browser or on the server. This is the line between GenUI and "let the model emit
   a web page".
2. **One renderer.** Composed screens are drawn by the same renderer as every other view: the console's Jinja macros
   (`drishti-console/web/templates/_macros/panels.html`, `panel(p)`), shipped as HTML over the wire to Elements
   ([ADR-021](adr/021-embedding-is-html-over-the-wire.md), [section 19 Decision 1](#why-not-a-second-renderer-and-what-we-give-up)).
   That is what makes the UX "fully consistent": it is literally the same output.
3. **The server judges what a viewer may see,** at view time, with the viewer's own rights, whoever composed the screen.
4. **Deterministic and reviewable.** The same request on the same catalogue gives byte-identical Sutra YAML and the same
   content hash. Every composition can be read as YAML, diffed, audited and promoted into a saved Sutra through the normal
   review flow.
5. **Data never reaches the agent.** Composition tools answer with labels, types and slots, never rows. The data goes
   from Drishti straight to the viewer's screen.
6. **Off by default** (`drishti.genui.enabled: false` (planned)), like AI and MCP ([AI.md](../admin/AI.md#1-model-providers)).

#### Non-goals (2.9.0)

| Not in scope | Why |
|---|---|
| Agent-written HTML, CSS, JavaScript or Rachana-EL text | the security model rests on it (section 11); an agent fills *slots*, it does not write expressions |
| Write-back (forms that create or change records) | separately governed; GenUI forms only search (section 10) |
| A JavaScript ViewModel renderer | rejected in [section 19](#why-not-a-second-renderer-and-what-we-give-up) (ADR-021), then built as the one renderer under [ADR-022](adr/022-one-renderer-in-the-browser.md): Drishti Render draws the streamed JSON (section 9) |
| Drishti calling an LLM to decide the layout | the agent decides; Drishti composes and checks. The console's Ask may *be* such an agent (section 6.5) |
| Pixel-level freedom | layout is the Sutra grid (`span`, `area`, tabs); styling is the design tokens |

### 4. What the code already gives us

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
| Elements | `drishti-console/web/embed/element.js`, `panel.js`, `report.js`, `search.js`, `about.js`; `routes/embed_routes.py` (`/embed/v1/views/{kind}/{id}`, `/embed/v1/channel`); library 1.4.3 (`web/elements/package/package.json`) | the host-side renderer, styles (`web/static/css/tokens.css`, `theme.css`, `--d-*` properties), charts, CSP and SRI story | no element for a composed screen |
| Embed tokens | `EmbedTokenService`, `EmbedController` (`POST /api/v1/embed/token`, RFC 8693), `EmbedProperties` (scopes to `METHOD path` patterns), `RateWindows`, `EmbedUsage`; console `core/embed_auth.py`, `core/embed_limits.py` | scoping a host application | no `embed:genui` scope |
| Query by form | `FormController` (`GET /api/v1/forms/{pack}`, `POST /api/v1/forms/{pack}/{kind}/compile`), `FormSpec`, `FormField`, `FormCompiler`; the pack's `form:` section; console `core/forms.py`, `routes/form_routes.py`, `css/form.css` | form fragments compile to the same search command | the form is a page, not a panel |
| Search | `SearchController` (`GET /api/v1/search`, `/csv`, `/compare`) | result rows for a search binding | rows are not an `EntityDocument` |
| BI | `MqController` (`POST /api/v1/bi/models/{id}/query`), `DatasetController`; consumer envelope ([BI_DESIGN.md §5.3](BI_DESIGN.md#53-model-read-build-and-the-consumer-envelope)) | a dataset or model binding | no Sutra panel reads MQ results today |
| Review | Build workbench Designs: `DesignController` (`POST /api/v1/builder/designs`), `DesignShipController` (`POST /api/v1/builder/designs/{id}/propose`); `SutraGovernance`, `ProposalStore`; `GovernanceController` (`/api/v1/sutras/proposals/{id}/approve`) | promoting a composed screen into a saved Sutra | none |
| Field knowledge | the pack's `form:` fields, `GlossaryResolver` / `CatalogGlossaryLookup` (pack `about.yaml`), `ShapeService` (shape learnt from samples) | checking every path a slot names | no single "fields of a kind" call for an agent |
| AI layer | Java `drishti-ai`: `LlmGateway`, `LlmPolicy`, `PromptMasker`, `ToolSpec`, `ToolCall`, `llm/BaseMcpTool`; Python `drishti-console/core/ai/mcp_tool.py` (`BaseMCPTool`), `gateway.py`, `policy.py`, `proxy.py` | the tool base classes for `compose_ui`; masking of the intent text; policy and proxy | Drishti is an MCP *client* only ([AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)); it serves no MCP endpoint yet |
| Audit | `AuditLog.record(actor, action, subject, detail)` (`FileAuditLog`, `JpaAuditLog`), `AccessRecorder` | one row per composition and per view | none |

The conclusion of this check: **about four fifths of GenUI exists.** The new parts are slots and contracts on blocks, the
composer and its store, a rebuild path in `ViewStream` for an unregistered Sutra, a stream with a skeleton, one element,
a form panel, and the tools.

### 5. The fragment catalogue: blocks with typed slots and a data contract

#### 5.1 Why blocks are the right unit

A block is already "a templatised Sutra fragment": panels written once, with parameters, versioned by file name, used by
many Sutras (`config/packs/trading/sutras/common/*.block.yaml`: `deal-ticket`, `legs`, `flow-schedule`, `events`,
`payments`, `deal-footer`). It is expanded *before* the Sutra is checked (`SutraBlocks.expand`), so whatever an agent
composes is linted, compiled and drawn exactly as if a person had written it in place.

What a block lacks for an agent is **types**. Today `with: { rate-fmt: pct4 }` is replaced as text wherever
`%{rate-fmt}` appears. A person writing a Sutra is trusted to put a format name there; an agent is not. Without types,
a `with:` value could carry an expression (`pct4" } , { label: x, bind: "secret(…)`) into the Sutra.

#### 5.2 Slots (new)

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
that fails is a problem with the slot's name (`DRS-2049` (planned), new), and nothing is composed.

#### 5.3 The data contract (new)

`contract:` says what a fragment needs from the data: which kinds it fits (`any` or a list) and which paths must exist,
after slots are filled. The composer checks the contract against the binding's kind; the catalogue uses it to answer
"which fragments fit a `trade`?". Existing pack blocks get contracts as part of making them GenUI-ready (for
`flow-schedule@1`: `kinds: [trade]`, `requires: ["$.LEGS[*].CASH_FLOWS"]`).

#### 5.4 The fields of a kind

Paths are checked against one merged list per kind and per caller (new: `KindFields`, in `drishti-engine`):

1. the pack's `form:` fields for the kind (`FormSpec`, `FormField`: path, label, type);
2. the glossary (`GlossaryResolver`, the pack's `config/about.yaml`);
3. the shape learnt from samples (`ShapeService`, `Shape`), for paths the first two do not name.

For the caller: fields the caller may not see by a hidden rule are **absent**; masked fields are **listed and marked**
(`masked: true`), as BI_DESIGN does for `describe` ([§9](BI_DESIGN.md#9-ai-architecture)). A slot naming an unknown path is refused
(`DRS-2050` (planned), new); naming a masked path is allowed (the viewer sees `•••`), because hiding it would make the screen differ
by viewer in shape, not only in values.

#### 5.5 Where fragments live, versions and governance

| Source | Folder | Governed by |
|---|---|---|
| Pack blocks with `genui.allowed: true` | the pack's Sutra folders, as today | the pack's review, signature and deploy (`PackDeployController`, signed registry, [ADR-018](adr/018-signed-pack-registry.md)) |
| Generic GenUI blocks (new) | `config/genui/blocks/` (planned) (new), loaded into the same `SutraBlocks` library | the same review as Sutras; shipped with the product |

A fragment is always named with its version (`amount-table@1`); the composer refuses a use without one (an agent must not
get a different screen because a block moved on). A block marked `deprecated: true` (new) is still composable but not
listed.

The generic set shipped in 2.9.0 (new, section 14): `kv@1`, `amount-table@1`, `metric@1`, `metric-row@1`, `line@1`,
`bar@1`, `ladder@1`, `tree@1`, `notes@1` (text from the glossary), `search-form@1` and `search-results@1` (section 10).

#### 5.6 Listing

`GET /api/v1/genui/fragments?kind=trade&tag=amounts` (new) answers, per fragment: `ref`, `description`, `tags`, `slots`
(name, kind, label, default, allowed values), `contract`, the panel kinds it brings (`BlockUses.Brought`), and `fits`
(true when the contract matches the kind for this caller). No text of the block unless `?text=1` and the caller may author
(`Entitlements.mayAuthor`). `GET /api/v1/genui/kinds/{kind}/fields` (new) answers 5.4.

### 6. The composer: one tool, no written code

#### 6.1 The request

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

#### 6.2 What the server does, in order

1. **Gate:** `drishti.genui.enabled` (planned); the caller may compose (`roles`, token scope `genui:compose`, new); rate limits.
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

#### 6.3 Deterministic and idempotent

The id is random, but the **hash** is of the canonical request (sorted keys, the resolved block versions and the
catalogue's version). The same hash from the same user within its TTL returns the existing screen (`200` instead of
`201`), so an agent that retries does not fill the store. Same input, same YAML: a test (section 15) holds it.

#### 6.4 Refusals an agent can act on

A refusal names the fragment, the slot and a fix, once, so an agent can correct itself without guessing:

```json
{"code": "DRS-2050", "error": "unknown field", "fragment": 2, "block": "amount-table@1", "slot": "amount-field",   (planned)
 "value": "NOTIONAL_USD", "near": ["NOTIONAL", "NOTIONAL_CCY"], "fix": "use a field of $.LEGS[*].CASH_FLOWS"}
```

`near` lists only fields the caller may see.

#### 6.5 The tool layer: Java, Python and MCP

The composer is one service (`GenUiComposer`, new, `drishti-server/.../genui/` (planned)). It is offered to agents as tools written on
the existing base classes, so the same tool is described once ([AI.md §10](../admin/AI.md#1-model-providers),
[AI.md §7](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)):

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
- **The console's Ask** ([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help)) may use `compose_ui` as a tool with feature `genui`
  (`drishti.ai.policy.features.genui.roles`), so "show me this trade's resets by month" answers with a screen.

### 7. Ephemeral Sutras and live URLs

#### 7.1 The store (new)

`GenUiStore` (new) keeps each composition: id, hash, owner, audience, embed app (when composed through an embed token),
binding, Sutra text, compiled `Sutra`, created, expires, view count. In memory with a size cap, and, when
`store: file`, one JSON file per screen under `<data dir>/genui/` (visible names only, under the configured data folder),
so a restart keeps live links. Expired screens are removed by a sweep on the server's virtual-thread executor.

The compiled Sutra is **never registered** in `SutraRegistry`: it can never match a normal view, appear in Studio, or be
changed by a pack reload. When a pack reload changes a block a screen used, the screen keeps the Sutra it was composed
with (it is a snapshot); its `get_ui` says `stale: true` and the agent may compose again.

#### 7.2 URLs

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

#### 7.3 Sharing

`audience` decides who may open the screen; **what** they see is always decided by their own rights at view time
(section 8.2). A screen can be shared with the existing share-with-a-note flow ([COLLABORATION.md](COLLABORATION.md)) once
promoted; an ephemeral screen is shared only by its link, because shares pin to a Sutra version and a business date and an
ephemeral one has no version that outlives it.

#### 7.4 Promote to a saved Sutra

A useful composed screen should become a real one. `promote_ui` (and **Promote** on `/g/{id}` for authors) creates a
Build workbench Design from the screen's Sutra text (`POST /api/v1/builder/designs`, with the binding's entity as its
sample), and the author proposes it as today (`POST /api/v1/builder/designs/{id}/propose`), which goes through
`SutraGovernance` four-eyes review (`GovernanceController` approve or reject). Provenance on the proposal:
`genui:{id, hash, intent title}`. Nothing an agent does saves a Sutra on its own.

### 8. Data wiring

#### 8.1 Bindings

| `binding.type` | Data | How it is drawn | Phase |
|---|---|---|---|
| `entity` (`kind`, `id`) | the entity's document, as every view | `ViewPipeline.preview(sutra, EntityRef.of(kind, id), asOf, redactor, mayOpen, controls)` | G3 |
| `search` (`q`, a search command; or `form` criteria) | the result rows of `SearchController`'s search | a search document (new `SearchDocument`: `{ROWS: [...], COUNT, QUERY}`) given to `ViewPipeline.builtPreview(Optional.of(sutra), doc, redact, mayOpen)` | G7 |
| `dataset` (`model`, `mq`) | an MQ result | a model document (new) from `MqController`'s query path as the viewer, under the consumer envelope | G9, may slip to a later release |
| `compose` (several of the above, named) | one document per name, `$.<name>…` in slots | the bindings resolve in parallel; each panel waits only for its own | G9 |

Bindings name data; they never carry it. An agent cannot inject rows.

#### 8.2 Per viewer

Every render runs as the **viewer**: `entitlements.redactor(viewer)`, `k -> entitlements.mayOpen(viewer, k)`, and, for an
embed token, never `raw` ([section 19 Decision 5](#83-the-response-and-the-token)). A viewer who may not open
the binding's kind gets `403` from `requireOpen`, whoever composed the screen. Masked values read `•••`;
`provenance.masked` counts them, as for any view. Links inside panels (`link(@.TRADE_ID, 'trade')`) open normal views,
under the same rights.

#### 8.3 As of, time travel, controls

`asOf` and `knownAt` work as on any view (`AsOf`). A past business date gives one view and no ticks
(`provenance.live == false`, as `StreamController` does today). Blocks that declare `controls:` keep them; a composed
screen's controls are those of its fragments.

### 9. Streaming to the host application

**Amended 2026-10-30 under [ADR-022](adr/022-one-renderer-in-the-browser.md)** (the product owner's answer 6 in
[section 18](#18-decisions-for-the-product-owner); design text only, nothing of GenUI is built):
the stream carries **the ViewModel as JSON**, message by message, and the browser draws it with Drishti Render, the one renderer; it
no longer carries the console's panel HTML (ADR-021). The renderer that draws it is built and tested for exactly this
(CLIENT_RENDERER.md section 11).

#### 9.1 What is streamed: the ViewModel, never the agent's output

The stream carries **panels as JSON** (`PanelView`, VIEWMODEL.md section 4), bound on the server as the viewer (masks applied there),
with the contract they speak (`"contract": "1.0"`). The agent's only contribution is the choice of fragments and slot values, both
checked. No script, style or markup from the agent, or from anywhere, is ever sent: the renderer builds the DOM itself and writes every
value as text, and the console's CSP (`script-src 'self'; style-src 'self'`) holds unchanged.

This is how the agreed design's "ViewModel plus our renderer" lands: the ViewModel is computed on the server, and "our renderer" is
Drishti Render in the browser, the same library the console's own entity page and Elements 2.0 draw with.

#### 9.2 Order of events

Server `GET /api/v1/genui/{id}/stream` (new, SSE, beside `StreamController`'s view stream) and console
`GET /embed/v2/genui/{id}/stream` (new, `fetch` + `ReadableStream`, `Authorization` header, as the Elements 2.0 channel). The messages
are the renderer's stream messages (CLIENT_RENDERER.md section 11), one for one:

| Event (message) | When | Payload |
|---|---|---|
| `skeleton` | at once, before any data is fetched | `{view}`: a ViewModel whose panels are stubs (id, kind, title, area, span, height, the layout), `subscribe` key, `expires`; the renderer draws the final layout with a "Loading…" in each, so nothing jumps |
| `panel-replace` | as each panel's data is ready | `{panel}`: the bound `PanelView` (data, tones, links, its masked count) |
| `panel-add` | a panel the skeleton did not announce (a `compose` binding that grew) | `{panel, after?}` |
| `done` | after the last panel | `{provenance}`: the full first view's provenance (generation, `live`, `masked`), timings |
| `patch` | on each change, while live | `{frame}`: the same frames as a live view (`strip`, `panel` with panels as JSON, `provenance`, `deleted`, `restored`) |
| `: hb` | every `drishti.live.heartbeat` | — |
| `error` | a panel that could not bind (`{panel, code, detail}`), or the screen expired, the app or user was disabled, or rights changed (`{code, detail}`) | `DRS` code |

For an `entity` binding the pipeline builds the whole ViewModel in one pass today (`ViewPipeline.preview`), so in G4 the
`panel-replace` messages follow each other within milliseconds of the build; the skeleton still arrives first, which is what the
viewer notices. For `compose` bindings (G9) each binding resolves on its own and its panels are sent when it is ready:
true progressive arrival. A host that already holds the renderer applies the stream with `render(el, { stream: url })` or
`handle.apply(message)`; every link, drill, event and action of an ephemeral screen stays intact, because they are in the ViewModel.

#### 9.3 Live ticks on a composed screen

`ViewStream` gains a constructor (new) that takes the composed `Sutra` and rebuilds with `pipeline.preview(sutra, …)`
instead of `pipeline.view(ref, …)`; everything else (topics on `TopicHub`, chart sources, `FrameMailbox`, coalescing,
`LiveStreamSlots` caps, `EmbedUsage` counts) is reused. The console's channel accepts `genui:{id}` keys (new) beside
`view:` and `vm:` (`core/channel.py`; `vm:` sends panel patches as panels, which is what a GenUI screen needs), so a host page with three
composed screens and two ordinary views still holds **one** connection.

#### 9.4 `<drishti-genui>` (new element)

```html
<script type="module" src="https://drishti.bank.example/embed/v2/elements/2/drishti-elements.js"></script>
<drishti-genui genui="g_7QX4M2" theme="inherit"></drishti-genui>
<script type="module">
  const el = document.querySelector('drishti-genui');
  el.tokenProvider = () => fetch('/my-backend/drishti-token').then(r => r.text());
  el.addEventListener('drishti:expired', () => el.remove());
</script>
```

It is Elements 2.0's `<drishti-view>` with a different source (`render(el, { stream })` instead of `{kind, id}`): same shadow root,
same adopted sheet, same connection, same charts, zoom, tables and keyboard (`behaviours: 'console'`). Attributes: `genui`, `ref` (rebind, 7.2), `as-of`,
`known-at`, `panels`, `header`, `theme`. Events: those of `<drishti-view>` (`drishti:loaded`, `:tick`, `:navigate`,
`:error`, `:masked`, `:state`) plus `drishti:skeleton` and `drishti:expired` (new). Library version: the next minor of
Elements 2 (2.1.0), with `integrity.json` and TypeScript declarations as for every element.

The design tokens (`--d-*`) are the consistency contract: a host restyles every composed screen at once, exactly as it
restyles its `<drishti-view>`s.

#### 9.5 Hosts with their own renderer

Since the stream is JSON (9.1), a host with its own renderer (a native desktop or mobile application) reads the same messages; nothing
is opt-in any more. A web host uses Drishti Render, which draws them exactly as the console does; a host that draws them itself accepts
that its screens will not look like Drishti's.

#### 9.6 Hosts without JavaScript

`/g/{id}` renders the whole screen server side, like `/v/{kind}/{id}`, with a `<meta http-equiv="refresh">` only when
`?refresh=30` is asked; it is the fallback for e-mail links, wikis and tools that cannot run modules. It is static (no
ticks) without JavaScript and live with it (the console's own `live.js`).

### 10. Dynamic forms

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
corrections) need their own design: commands, four-eyes, idempotency, source-system ownership. They are not part of 2.9.0.

### 11. Security and governance

#### 11.1 Threats and answers

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

#### 11.2 Masks before render

Masking happens in the pipeline (`redactor`) before any panel is rendered or any frame is built, as for every view. GenUI
adds no path that renders before masking.

#### 11.3 Audit

Every composition: `AuditLog.record(user, "genui-compose", "genui:<id>", detail)` with detail
`{intent (masked by PromptMasker, at most 500 characters, or omitted when audit-intent: none), binding kind and id, fragments
with versions and slot values, hash, outcome, problems, app}`. Every view: an `AccessRecorder` row, action `view`, detail
`genui:<id>` (and `embed:<app>` for embeds), as for shares. Every promotion: the proposal's provenance. Admin → GenUI (new)
lists live screens, their owners, fragments and views, and can expire one.

#### 11.4 Which fragments an agent may use

```yaml
drishti.genui.fragments:
  allow: ["genui/*", "trading/common/*"]     # pack/folder patterns of block files
  deny:  ["trading/common/deal-footer"]
  roles: { "trading/common/payments": [ops, risk] }   # roles allowed per fragment; absent = every user who may compose
```

A fragment needs `genui.allowed: true` in its file *and* to pass these lists. Packs keep control of their own blocks;
the site keeps control of what agents may do with them.

#### 11.5 Limits

`per-user-per-minute` compositions, `max-live-per-user`, `max-fragments` per composition (12), `max-panels` after expansion
(24), `ttl-default` and `ttl-max`, `store-max`; live streams count against `LiveStreamSlots` like any view. Over a limit:
`429`, `DRS-4017` (the AI layer's code), with `Retry-After`.

#### 11.6 Embed tokens

A new scope `embed:genui` (new) in `EmbedProperties.scopes`, opening `GET /api/v1/genui/*`, `GET /api/v1/genui/*/stream`
and the fragment listing, and, only when the app's registration says `genui-compose: true` (new), `POST /api/v1/genui`
(the one non-GET an embed token may make, so an embedded agent can compose for its user). Compositions made with an embed
token have `audience: app` and the app recorded. When embed tokens are bound to consumer envelopes (planned after 3.0.0;
[BI_DESIGN.md §5.3](BI_DESIGN.md#53-model-read-build-and-the-consumer-envelope)), a `dataset` binding is limited to the
token's envelope.

#### 11.7 Off by default, policies honoured

`drishti.genui.enabled: false` (planned) answers `404` (`DRS-2052`, new) on every GenUI call and hides Admin → GenUI. The console's Ask
uses GenUI only with `drishti.ai.enabled` and the `genui` feature allowed. The outbound proxy and TLS settings of the AI
layer apply to any provider call; GenUI itself makes no outbound call.

#### 11.8 Managing GenUI in the console (new)

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
- **Policy:** the switch (`drishti.genui.enabled` (planned)), which fragments agents may use (allow list, deny list, per role), the
  limits (screens per user and minute, TTL default and maximum, panels per screen), the AI providers and MCP clients
  allowed to call `compose_ui`; changes are audited and take effect without a restart.
- **Hosts:** the embed applications allowed to show GenUI (origins, `embed:genui` scope, token lifetime), with a snippet
  to paste (`<drishti-genui>` and its script tag, integrity hash included).
- **Activity:** compositions and views a minute (the Admin → Health activity chart, filtered to GenUI), refusals by code
  (`DRS-2049` (planned)…), slowest screens, audit search by user, agent or fragment.

**Build → Compose** (authors and administrators; a playground):

- Pick fragments, fill their slots from field pickers, choose a binding, and see the screen build live: the same
  `POST /api/v1/genui` an agent sends, shown as the request, the screen and the stream's events. Used to try fragments, to
  reproduce what an agent did (open any audited composition here), and to write the golden examples the agent tools are
  tested with.

APIs behind them (all new): `GET/PUT /api/v1/admin/genui/policy`, `GET /api/v1/admin/genui/screens`,
`POST /api/v1/admin/genui/screens/{id}/expire|extend|promote`, `GET /api/v1/admin/genui/activity`, and the Build workbench's
existing design endpoints for fragments.

### 12. Configuration

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
```

All settings are read without a restart, as every pack-contributed setting is today. Admin → Configuration shows them.

### 13. APIs, tools and error codes

#### 13.1 REST (all new)

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

#### 13.2 Error codes (new; numbers confirmed against `ErrorCode.java` when built)

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-2049` (planned) | 400 | a slot value of the wrong type, or a parameter that is not a slot |
| `DRS-2050` (planned) | 400 | a path or field the kind does not have, for this caller |
| `DRS-2051` (planned) | 410 | the screen expired |
| `DRS-2052` (planned) | 404 | GenUI is off |
| `DRS-2053` (planned) | 403 | the fragment is not allowed for this caller |
| `DRS-2054` (planned) | 400 | the fragment does not fit the binding's kind (contract) |
| `DRS-2048` (existing) | 400 | the block is unknown or badly written |
| `DRS-4017` (existing) | 429 | over a limit |

#### 13.3 Command line (new)

`drishti.py genui (planned) fragments [--kind trade]`, `drishti.py genui compose request.json`, `drishti.py genui show <id>`,
`drishti.py genui (planned) promote <id> --name …`, beside the existing `sutra`, `pack` and `view` commands.

### 14. Build plan for 2.9.0

Sizes: S up to two days, M up to a week, L more. Each step ends with its tests green and its docs written.

| Step | Scope | Size | Accepted when |
|---|---|---|---|
| **G1 Slots and contracts** | `slots:`, `contract:`, `genui:` in block files; `SutraBlocks.Block` carries them; typed substitution; `RachanaSchema` and `RACHANA_REFERENCE.md` updated; contracts on the six trading blocks | M | an injection attempt in a slot is refused with `DRS-2049` (planned); every existing Sutra and block checks unchanged (pack tests green); a slot rename (`amount-field: MTM`) draws the right column |
| **G2 Fields and catalogue** | `KindFields` (form + glossary + shape, per caller); `GET /api/v1/genui/fragments`, `/kinds/{kind}/fields`; generic blocks in `config/genui/blocks/` (planned) | M | a role without access to a hidden field never sees it listed; masked fields are marked; every generic block has a sample test |
| **G3 Composer, store, URL** | `GenUiComposer`, `GenUiStore`, `GenUiController`, `GenUiProperties` (new, `drishti-server/.../genui/` (planned)); `POST /api/v1/genui`, `GET /api/v1/genui/{id}`; console `/g/{id}`; audit; limits; off by default | L | the same request twice gives the same YAML and hash; a second user opens a `link` screen and sees their own masks; expiry answers `410`; off answers `404` |
| **G4 Streaming and live** | `ViewStream` rebuild with a given Sutra; `GET /api/v1/genui/{id}/stream` (skeleton, panel, view, frame); `genui:` channel keys; `/embed/v1/genui/{id}` and its stream; scope `embed:genui` | M | a tick on the bound trade reaches a composed screen within the live p99 of ordinary views; skeleton first in every run; one connection for mixed `view:` and `genui:` keys |
| **G5 `<drishti-genui>`** | the element in `web/embed/`, library 1.5.0, `integrity.json`, TypeScript declarations, React/Vue/Angular samples | M | Chromium, Firefox and WebKit draw a composed screen under the strict host CSP of the Elements spike; host tokens restyle it; no inline script or style |
| **G6 Tools and MCP** | `list_fragments`, `describe_fields`, `compose_ui`, `get_ui`, `promote_ui` on `BaseMcpTool` (Java) and `BaseMCPTool` (Python); `POST /mcp`; Ask feature `genui` | M | the mock provider (`type: mock`, `rules` with `tool` + `tool-args`) composes a screen end to end with no network; an MCP client (SAJHA) lists and calls the tools |
| **G7 Forms** | panel kind `form`; `search-form@1`, `search-results@1`; `search` binding with `SearchDocument`; criteria in the URL | M | a composed form pre-filled for a counterparty finds the same trades as `FORM trading` with the same criteria |
| **G8 Promote and admin** | `promote_ui` → Design → propose; the management UI of 11.8: Admin → GenUI (live screens, policy, hosts, activity), Build → Fragments (slot editor, contract, live preview, *Test with* a role, review), Build → Compose (playground) | M | a promoted screen goes through four-eyes review and then opens as a normal Sutra; an administrator changes the agent allow list and the TTL in the UI and the next composition obeys without a restart; an author makes a fragment from a Sutra's panels, fills its slots and previews it without typing YAML; browser tests for the three pages |
| **G9 More bindings** (may move to a later release) | `compose` (named bindings, progressive panels), `dataset` (MQ, envelope) | L | three bindings arrive in the order they resolve; a dataset panel equals the MQ answer per user, masked |
| **G10 Docs, tutorials, UX pass** | section 16; one hands-on tutorial with UI, CLI and YAML for every step | M | a new reader composes, embeds and promotes a screen from the tutorial alone |

Order: G1 → G2 → G3 → (G4 ∥ G6) → G5 → G7 → G8 → G9 → G10. G1–G8 are the 2.9.0 commitment; G9 is a stretch.

**GenUI is accepted for 2.9.0 when:** (1) an agent using only `compose_ui` produces a live screen of a trade from four
fragments with renamed fields, in under 200 ms to the answer (p50, 10K trades locally); (2) that screen, embedded with
`<drishti-genui>` in a host page, shows its skeleton first and ticks live; (3) two viewers with different roles see the
same screen with their own masks; (4) every composition and view is in the audit; (5) nothing changes for a site that does
not turn it on.

### 15. Tests

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

### 16. Documentation to write or update

| Document | Change |
|---|---|
| `docs/guides/GENUI.md` (planned) (new) | the developer guide: five-minute start, the request, tools, the element, cookbook, troubleshooting by code |
| a new tutorial in `docs/tutorials/` | compose, embed, promote: every step in the UI, the CLI and YAML |
| `docs/guides/RACHANA_REFERENCE.md` | `slots:`, `contract:`, `genui:` in block files; panel kind `form` |
| `docs/guides/RACHANA_REFERENCE.md` | the `form` panel |
| `docs/architecture/CLIENT_RENDERER.md`, [section 19](#20-drishti-elements-the-design) | `<drishti-genui>`, the `genui:` channel key, `embed:genui` |
| `docs/guides/API_GUIDE.md` | section 13.1; the `genui:compose` token scope |
| `docs/admin/CONFIGURATION.md` | section 12 |
| [AI.md](../admin/AI.md#1-model-providers), [AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy) | feature `genui`; the server's own MCP endpoint |
| `docs/guides/CLI_GUIDE.md` | `drishti.py genui` (planned) |
| `docs/guides/USER_GUIDE.md` | opening a composed screen, promoting it |
| a new ADR in `adr/` | "Agents compose screens from governed fragments; no generated code is sent or run" |
| [docs/README.md](../README.md), `CHANGELOG.md`, `RELEASE_NOTES.md` | entries |

### 17. Risks

| Risk | Mitigation |
|---|---|
| Typed substitution breaks existing blocks | slots are opt-in; blocks with only `params:` keep text substitution; G1 runs every pack test |
| Agents compose poor screens (ten tables, wrong order) | a small, good generic catalogue; `tags` and `description` written for agents; `max-fragments`; warnings for empty panels; promotion means a person reviews before anything lasts |
| The store grows | TTL, `store-max`, idempotent hashing, per-user live caps |
| A pack reload changes a block under live screens | screens are snapshots; `stale: true`; tests for reload during a stream |
| The skeleton promise is weak for single-entity screens | it is honest about it (9.2); progressive panels come with `compose` bindings |
| HTML over the wire is larger than JSON | measured in section 19 §11 (3 to 6 times before compression); accepted there, and the same here |
| One more MCP surface to secure | one endpoint for GenUI and BI tools, personal tokens with scopes, the same policy and audit |

### 18. Open questions

1. **Where generic blocks live:** `config/genui/blocks/` (planned) shipped with the product, or a `genui` pack that sites can extend
   and sign like any pack? (Recommendation: a pack, so the signed registry and deploy flow cover it.)
2. **Audience `link`:** is a link anyone in the firm may open (rights still apply) acceptable to compliance, or must every
   screen be `me` or `app` unless promoted?
3. **Intent in the audit:** keep the masked intent text (useful for review, a privacy risk) or only a hash and title?
4. **Rebinding** (`?ref=`): on by default for screens bound to one entity, or only when the agent asks?
5. **Dataset bindings** (G9): in 2.9.0 or later, given the consumer-envelope work they depend on?
6. **The console's Ask as an agent:** should Ask be allowed to compose by default once AI is on, or only with the
   `genui` feature granted per role?
7. **Write-back forms:** decided: write-back is its own release, 2.8.0 ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons); 2.7.0 when decided); GenUI's forms use it.

## 22. The ViewModel contract: its own reference

The renderer, the console, Drishti Elements, a script, a notebook and a golden test all read the same JSON: the **ViewModel**, the
screen already laid out and bound. It is a public contract that other teams code against and version against (its top level, the
common types, every panel's shape, the live patches, masks in a view, compatibility rules and the JSON Schema), so it stays **its own
reference**, [VIEWMODEL.md](VIEWMODEL.md), like the data contracts and the API references, rather than a section of this design: a
change to it is a contract change, reviewed and versioned on its own. This document says how the renderer draws it
([section 7](#7-how-every-panel-kind-maps), [section 10](#10-data-identity-and-live-patches)); VIEWMODEL.md says what it is.
