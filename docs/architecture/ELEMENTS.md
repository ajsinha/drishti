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
# Drishti Elements: Drishti's views inside other web applications, without iframes

Status: steps 0 to 10 of [the build plan](#16-build-plan) are built (each has an "as built" note there, saying what differs from
this design); steps 11 to 15 are not. The decisions marked **Decision:** were taken as recommended unless an "as built" note says
otherwise. Owner: the console (`drishti-console/`), the server's security layer and view pipeline.

Contents

1. [Why](#1-why)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [What the code already gives us](#3-what-the-code-already-gives-us)
4. [Architecture](#4-architecture)
5. [The element API](#5-the-element-api)
6. [The embed API](#6-the-embed-api)
7. [Loading, reloading, refreshing, reconnecting](#7-loading-reloading-refreshing-reconnecting)
8. [Embed tokens: the token exchange](#8-embed-tokens-the-token-exchange)
9. [Registering a host application](#9-registering-a-host-application)
10. [Security: threats and answers](#10-security-threats-and-answers)
11. [Performance](#11-performance)
12. [Console scripts as root-scoped modules](#12-console-scripts-as-root-scoped-modules)
13. [Distribution and versioning](#13-distribution-and-versioning)
14. [Testing](#14-testing)
15. [Documentation plan](#15-documentation-plan)
16. [Build plan](#16-build-plan)
17. [Decisions for the product owner](#17-decisions-for-the-product-owner)

## 1. Why

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

## 2. Goals and non-goals

### Goals

1. **One custom element per surface**, framework-neutral: `<drishti-view>` first; later `<drishti-panel>` (one panel of
   a view), `<drishti-search>` (the type-ahead and result list), `<drishti-about>` (the *About this page* body).
   Usable from plain HTML, React, Angular and Vue without wrappers (thin optional wrappers may come later).
2. **The same pixels as the console**: the element shows exactly what `/v/{kind}/{id}` shows for the same user, date
   and generation: same panels, same numbers, same masks (`drishti.security.redact`), same refusals.
3. **Live**: the element ticks like the console (LIVE.md); it pauses when nobody can see it and resumes with fresh data.
4. **Dynamic**: changing an attribute (`entity`, `as-of`, `theme`) reloads in place, cancelling what was in flight;
   `refresh()` re-fetches; a dropped connection reconnects with backoff and repaints from a fresh view.
5. **No cookies, no shared secrets in the browser**: the host's backend gets a short-lived, audience-bound, read-only
   embed token for *its* user, server to server, modelled on OAuth 2.0 Token Exchange (RFC 8693).
6. **The host stays in charge**: navigation, errors and masks are events the host may act on; it decides what a click on
   a linked entity does.
7. **Accountable**: every view shown through an element is in the access log with the host application that showed it.

### Non-goals (for version 1)

- **Writing anything**: no layout mode, no saving a Pivot arrangement, no share, comments, alerts, pins, Calc, Ask
  (the *Ask* box of About this page costs model calls and has its own rate limits), no raw JSON drawer (F9), no export.
  The element is a read-only window. Each can be added later behind its own scope.
- **A second renderer**: see below.
- **Personal arrangements**: the element shows the Sutra's layout, not the user's own (`core/layouts.py` already turns
  them off for `embed`), so a host that places the element can rely on what it will look like.
- **Offline** use, or showing data without a Drishti server.
- **Old browsers**: Custom Elements v1, Shadow DOM, constructable stylesheets, `ReadableStream` and `AbortController`
  are required (every evergreen browser since 2023). No polyfills.

### Why not an iframe (and what we give up)

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

### Why not a second renderer (and what we give up)

A JavaScript renderer for the ViewModel (the JSON `/api/v1/views/{kind}/{id}` returns) would make the element
independent of the console. We do not do it:

- **Twenty panel kinds, tree rows, pivots, tabs, gauges, surfaces** are rendered by one set of Jinja macros
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

## 3. What the code already gives us

The direction was checked against the code. The parts the element reuses, and the gaps:

| Piece | Where | What it gives | Gap for embedding |
|---|---|---|---|
| Panel HTML | `_macros/panels.html` `panel(p)` | every panel kind, server-rendered, no inline styles or scripts (the console's CSP is `style-src 'self'; script-src 'self'`) | links are relative (`/v/…`, `/help/panel-kinds#…`, `/export/…`); some affordances need cookies |
| Live frames as HTML | `api_routes.py` `_view_event`, `CHART_KINDS = {"line", "area"}` | `frame` patches: `strip`, `panel` (with `html`, or chart `data` for line/area), `provenance`, `deleted`, `restored` | none: reused unchanged |
| The `view` event | `_view_event` | on a (re)connection, reduced to `{"generation": n}`; pages repaint by reloading (`live.js`: `location.reload()`) | the element must fetch a fresh view itself: the embed API's `GET view` (no replay) |
| One channel per browser | `api_routes.py` `channel()`, `channel_change()`, `CHANNELS` | `{ch, d}` multiplexing, add/remove without reconnecting, heartbeat `: hb`, `end`, `gone` with DRS code, per-channel cap (`live.max_subscriptions`) | cookie identity (`ident(request)`), owner check by user only; the logic is one 160-line closure |
| The hub | `live-hub.js` `Hub(opts)` | reconnect with backoff (2 s doubling to 30 s), resync of keys, connect timer; takes `EventSource` and `fetch` **as options** | a SharedWorker must be same-origin with the page, so cross-origin it cannot run in one; `EventSource` cannot send `Authorization` |
| Patch application | `live.js` | strip cells, panel swap (keeps tab choice, user's span), flash, deleted banner | `document.querySelector`, globals `window.drishti`, `window.DrishtiChannel`, `location.reload()` |
| Charts | `view.js` (line/area: `drawCharts`, `updateChart`), `charts.js` (waterfall, histogram, scatter, candlestick, graph from `data-xchart`), surfaces (ECharts GL loaded on demand) | ECharts with SVG renderer; colours from `--d-*` tokens | `getComputedStyle(document.documentElement)`, `document.querySelectorAll`, `window.resize`, links built as `/v/…` |
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

## 4. Architecture

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

## 5. The element API

### 5.1 Loading the library

```html
<script type="module" src="https://drishti.bank.example/elements/1.0.0/drishti-elements.js"
        integrity="sha384-…" crossorigin="anonymous"></script>
```

or from npm (`npm i @drishti/elements`, `import '@drishti/elements'`); the npm package and the console serve the same
build ([section 13](#13-distribution-and-versioning)). Importing defines the elements (`customElements.define`) once;
importing twice is harmless.

### 5.2 `<drishti-view>` attributes

| Attribute | Values | Default | Notes |
|---|---|---|---|
| `server` | the console's base URL | the origin the module was loaded from | every element on a page that shares a `server` shares one connection |
| `kind` | an entity kind (`trade`, `counterparty`, …) | required | |
| `entity` | the entity id | required | change it to show another entity: reload in place |
| `as-of` | `live` or a business date `YYYY-MM-DD` | `live` | a past date is a static snapshot (as in the console); sent as `X-Drishti-As-Of` |
| `known-at` | an ISO instant | none | bitemporal "as known at" (`X-Drishti-Known-At`) |
| `theme` | `inherit`, or a Drishti theme: `terminal`, `light`, `blue`, `green`, `wallstreet`, `crimson`, `crimson-dark` | `inherit` | `inherit` uses the host's `--d-*` values, falling back to `light` |
| `header` | `full`, `strip`, `none` | `full` | title + strip, strip only, or panels only |
| `density` | `compact`, `comfortable` | the sheet's | row padding of tables (a host-level choice; the view's own look otherwise) |
| `panel` | a panel id | none | **reserved** for `<drishti-panel>` (phase 2): accepted and ignored by `<drishti-view>` (use `panels` to show some) |
| `panels` | comma-separated panel ids | all | show only these panels (the view is still built whole; see `<drishti-panel>` for one panel) |
| `live` | `auto`, `off` | `auto` | `off` loads once and never subscribes |
| `locale` | a BCP 47 tag | the browser's | pack text (`?locale=` of About) |
| `label` | text | the view's title | the element's accessible name (`role="region"`) |

Attributes are reflected to properties of the same name in camelCase (`asOf`, `knownAt`).

### 5.3 Properties

| Property | Type | Notes |
|---|---|---|
| `tokenProvider` | `() => Promise<string \| {token, expiresAt}>` | **required** when the console has security on. Called before the first request, before expiry (60 s early), and once after a `401 DRS-8001`. Set it on the element, or once for the page: `DrishtiElements.configure({server, tokenProvider})` |
| `state` | read-only string | `idle`, `loading`, `live`, `static`, `reconnecting`, `paused`, `deleted`, `error` (the state machine, [section 7](#7-loading-reloading-refreshing-reconnecting)) |
| `view` | read-only object | `{ref, title, generation, provenance, panels: [{id, kind, title}]}` once loaded |
| `lastError` | read-only `{code, status, detail}` or null | |
| `theme`, `density` | string | the attributes of the same name |

### 5.4 Methods

| Method | Effect |
|---|---|
| `refresh()` | fetch the view again (same entity), keep the subscription, swap what changed; returns a promise for `loaded` |
| `reload()` | full reload: cancel in-flight requests, drop the subscription, fetch, subscribe (what an attribute change does) |
| `pause()` / `resume()` | give the subscription back / take it again (the element also does this itself when hidden) |
| `openAbout(panelId?)` | open the About drawer (inside the element), optionally at a panel |
| `focusPanel(id)` | move the focus to a panel (as `Alt+1..9` do in the console) |

### 5.5 Events

All are `CustomEvent`s dispatched on the element, `bubbles: true, composed: true`.

| Event | `detail` | Cancellable | When |
|---|---|---|---|
| `drishti:loaded` | `{ref: {kind, id}, generation, title, live}` | no | a view was painted (first load, reload, refresh, reconnect) |
| `drishti:tick` | `{generation, seq, latencyMs, changed: {strip: [index], panels: [id]}}` | no | a live frame was applied |
| `drishti:navigate` | `{kind, id, href, panel, source: "link" \| "chart" \| "graph"}` | **yes** | the user activated a linked entity. Default (not cancelled): set `entity` (and `kind`) and reload in place. `href` is the absolute console URL, for a host that prefers to open the console |
| `drishti:error` | `{code, status, detail, fatal, retryInMs}` | no | a DRS code from [section 6.7](#67-errors); `fatal: false` while the element retries by itself |
| `drishti:masked` | `{count, panels: [id]}` | no | the view the user is shown has masked fields (after load and when it changes) |
| `drishti:state` | `{state, previous}` | no | every state change (drives a host's own live indicator) |
| `drishti:token-needed` | `{reason: "missing" \| "refused" \| "provider-failed"}` | no | there is no token provider, the server refused the token twice, or the provider threw/rejected: the host signs the user in again and sets `tokenProvider` (an element in `error` after a 401 reloads by itself when the provider is set) |

### 5.6 Styling

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

### 5.7 Slots

| Slot | Shown | Default |
|---|---|---|
| `loading` | while loading and no view has been painted yet | nothing (the element dims the view it has, while it reloads) |
| `empty` | no `kind`/`entity` yet (state `idle`) | nothing |
| `error` | an error with no view to keep | the DRS code and detail (`role="alert"`); with a slotted node, the host's content replaces it, and `drishti:error` still fires |
| `toolbar` | **not built** (reserved) | |

### 5.8 Keyboard

Shortcuts work **only while the focus is inside the element** (listeners on the shadow root, never on `document`):
`?` opens About, `Esc` closes it, `Alt+1..9` move between panels, the table keys of `tables.js` inside a table. The
console's global keys (`F1` guide, the command line, `Alt+0..4` workspace panes, `Alt+L` layout mode) are not bound.

### 5.9 Examples

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

## 6. The embed API

Served by the console under `/embed/v1/`. Every call carries `Authorization: Bearer <embed token>` and is subject to
CORS ([section 10](#10-security-threats-and-answers)). Every response says its contract version in
`Drishti-Embed-Api: 1.0` and has `Cache-Control: no-store` (views are per user).

### 6.1 `GET /embed/v1/views/{kind}/{id}` — the initial view

Query: `asOf`, `knownAt`, `panels` (ids), `header` (`full|strip|none`), `locale`. The console asks the server for the
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

### 6.2 `GET /embed/v1/channel?s=…` — the live stream

Exactly the frames of `/api/channel` ([LIVE.md](LIVE.md#one-connection-per-browser)): `text/event-stream`, events
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

### 6.3 `POST /embed/v1/channel/{cid}` — add and remove

`{"add": ["view:trade/B"], "remove": ["view:trade/A"]}` → `{"ok": true}`, as `channel_change` today. The owner check is
the user **and** the host application of the token (a token of another app for the same user gets `404 DRS-5001`).

### 6.4 `GET /embed/v1/views/{kind}/{id}/about` — the About body

The HTML fragment `GET /v/{kind}/{id}/about` returns today (`terminal/_about.html`), without the *Ask* layer
(`layer_ask`) and without the Discussion tab. Query `generation`, `locale`, `panel`.

### 6.5 `GET /embed/v1/views/{kind}/{id}/panels/{panel}/records` — rows for a Pivot

The rows the Pivot tab works on (`/api/pivot/records/…` today, the server's `…/panels/{panel}/records`), so pivots work
in the element; arrangements live in the element's memory only (no `PUT /saved`).

### 6.6 `GET /embed/v1/resolve?text=…` — a typed command to an entity

What the console's `POST /api/resolve` does (`backend.command`, the server's `POST /api/v1/command`), for a host that
offers its own search box: `TRD MX-20000001`, `CPTY CP-1042` or a bare id answer
`{"ref": {"kind": "trade", "id": "MX-20000001"}, "mnemonic": "TRD", "title": "…"}`, or `404 DRS-1001` / `400 DRS-4001`.
The server allows `POST /api/v1/command` to an embed token as a read and does not add it to the user's command
history. (Optional for hosts: one that already knows kind and id sets the attributes directly.) The element exposes it
as `DrishtiElements.resolve(text, {server})`.

### 6.7 Errors

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

## 7. Loading, reloading, refreshing, reconnecting

### 7.1 States

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

### 7.2 Tokens over time

The connection asks `tokenProvider` for a token before the first request and keeps it until 60 s before `expiresAt`
(from the provider's answer, or the token's `exp`). A live stream opened with a token **outlives the token**: the
console checks the token when the stream opens and then every `embed.recheck_seconds` (default 60) against the server
(user still enabled, host application still enabled, roles still open the kind); when the token has expired the
console sends `event: token` (`{"ch": "", "d": {"expiresIn": 0}}`) and the connection answers with a fresh token on
`POST /embed/v1/channel/{cid}/token` within 30 s, or the stream ends with `gone` `DRS-8001`. A token is never sent in a URL.

**Decision 2:** streams are re-authorised in place (`event: token`) rather than by reconnecting every five minutes.
Recommended: a reconnect repaints every element on the page, which users would see.

### 7.3 One connection per page

`DrishtiElements` keeps one `Connection` per `server` per page. It is the console's `Hub` (`live-hub.js`) built with an
injected transport: the hub already takes `EventSource` and `fetch` as options (`new Hub({EventSource, fetch})`), so the
element passes a `FetchEventSource` (an `EventSource`-shaped class over `fetch` + `ReadableStream`, sending
`Authorization`) and an authorised `fetch`. Keys from all elements are deduplicated by the hub (two elements on the same
trade share one subscription). Across **tabs** of the host application there is no sharing in version 1: a SharedWorker
must have the page's origin, so the console's worker cannot be used, and the host would have to serve one.

**Decision 3:** one connection per page in version 1 (not per browser), and the console served over **HTTP/2** for
embedding, where the six-connections-per-site limit of HTTP/1.1 (QA finding UX-01) does not apply. Recommended. A
host-served SharedWorker (`DrishtiElements.useWorker('/drishti-worker.js')`) can come later for hosts on HTTP/1.1.

### 7.4 Charts in a shadow root

- ECharts is loaded on first need, once per page (`assets.echarts`), and ECharts GL only for a 3D surface (as `view.js`
  does today).
- Every chart is `echarts.init(el, null, {renderer: 'svg'})` on an element inside the shadow root (the spike drew one
  under a strict CSP). Colours come from `getComputedStyle(hostElement)` (the custom properties inherit into the shadow
  root), not from `document.documentElement`.
- One `ResizeObserver` per element watches the grid; a panel whose width changed resizes its charts in the next
  animation frame (the console listens to `window.resize`, which misses a host's split panes and drawers).
- A chart in a panel that is `hidden` (tabs) is drawn when it is shown (zero size otherwise).
- Tooltips stay inside the shadow root (ECharts' default `appendToBody: false`) and so take the element's styles.

### 7.5 Links and navigation

Panel HTML holds console-relative links. One click (and `keydown` Enter) listener on the shadow root resolves them:

| Link | Becomes |
|---|---|
| `/v/{kind}/{id}` (strip cells, tables, graph nodes, chart points: `charts.js` `open()`) | `drishti:navigate`; default: reload in place on that entity |
| `/help/panel-kinds#…` (the panel `?`) | the panel's About popover (`about-hints.js`), not a console page |
| anything else console-relative | absolute console URL, `target="_blank" rel="noopener"` |
| console-only affordances (export, layout, share, pin) | not rendered: the macros get `embed=True` and leave them out |

## 8. Embed tokens: the token exchange

### 8.1 Flow

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

### 8.2 The request (RFC 8693)

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

### 8.3 The response and the token

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

### 8.4 How the console and the server use it

- The console's `EmbedAuth` (new, `drishti-console/core/embed.py`) verifies the signature (JWKS cached, refreshed on an
  unknown `kid`), `aud` = its own public URL, `exp`, `typ`, and that the request's `Origin` is in `origins`; it builds an
  `Identity(user=sub, token=<the embed token>, app=azp)` for the request. Backend calls send the embed token itself.
- The server's `TokenFilter` recognises `typ: drishti-embed+jwt`, verifies it the same way, checks the app is enabled and
  the kind allowed, loads the user's current roles minus `raw`, applies `drishti.embed.allow`, and sets the access-log
  detail `embed:<app>` (`AccessRecorder`, beside `share:sh_…`).
- Revocation: disabling a host application, or the user, takes effect on the next call (the server checks both on every
  request; the console's stream re-check, [section 7.2](#72-tokens-over-time), ends open streams within
  `embed.recheck_seconds`). Individual tokens are not revoked: they live five minutes.

## 9. Registering a host application

### 9.1 Admin UI: Admin → Embedding

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

### 9.2 Configuration

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

## 10. Security: threats and answers

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

## 11. Performance

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

## 12. Console scripts as root-scoped modules

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

### Step 6 as built (root-scoped modules)

The scripts stayed classic scripts (the console's pages and `script-src 'self'` are unchanged); each is an IIFE that registers
`window.drishtiModules.<name> = { init }` and starts `init(document)` itself unless its `<script>` has `data-manual`, which the
element sets when it loads them (`GET /embed/v1/poc/js/<name>.js`, a whitelist in `embed_routes.ENHANCERS`). `drishtiModules` is
the only global the element adds to the host page; with `data-manual` the engine, grid, charts, About and Pivot globals
(`window.drishtiPivot`, `drishtiAbout`, `drishtiCharts`, `drishti`, ...) are not set. A real ES-module wrapper stays for step 9.

| Module | `init(root, options)` | Notes |
|---|---|---|
| `view.js` | bars, tabs, line/area charts, surfaces; returns `{enhance, redraw, updateChart, dispose}` | clock, F-keys, raw drawer, share, breadcrumbs stay console-only (`auto` only); `options.glUrl: null` = no 3D (heatmap); sizing by `ResizeObserver` on the host in a shadow root |
| `charts.js` | the xcharts; theme and size handling; returns `{draw, dispose}` | tokens from the host; `options.navigate(link)` optional (default: hidden link click) |
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

## 13. Distribution and versioning

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

**Decision 7:** the console serves the library and the npm package mirrors it (rather than npm only). Recommended:
a host on a locked-down network fetches from Drishti, which it must reach anyway, and the version always matches the
console it talks to.

## 14. Testing

- **Unit (console, pytest)**: `EmbedAuth` (signature, `aud`, `exp`, `typ`, origin, unknown `kid` refetch, clock skew),
  CORS (registered origin echoed, other origins and cookie routes get nothing, preflight), the embed view payload (every
  panel kind of `docs/guides/examples` renders, `embed=True` drops console-only links), the channel session shared with
  `/api/channel` (both pass the existing `test_live*.py`), the CSS rewrite, the sanitiser on hostile fragments.
- **Unit (server, JUnit)**: token exchange (each `error` of RFC 6749 §5.2, both subject token types, `jti` replay,
  scopes ⊆ app, kinds narrowed), embed tokens in `TokenFilter` (GET allow-list, non-GET refused, `raw` removed, disabled
  app or user), `AccessRecorder` `embed:<app>`, `provenance.masked` counts, `ErrorCode` new codes.
- **Unit (JavaScript)**: the root-scoped modules run in a test page against a `ShadowRoot` (Playwright, as the console's
  browser tests do); the `FetchEventSource` parser (split chunks, comments, multi-line `data:`).
- **The foreign-origin host page (Playwright)**: `drishti-console/tests/elements/host/` is a tiny host app on a second
  port with a strict CSP (section 10), its own "backend" doing the token exchange against a scratch server with the
  banking packs; tests: a live trade ticks; masked fields read `•••` for a viewer and a user with `raw`; clicking a
  counterparty fires `drishti:navigate` and, not cancelled, reloads in place (cancelled, the host's search box takes it
  and switches); typing in the host's search box switches the view; the as-of picker reloads as static and back; ten
  rapid switches with out-of-order responses paint only the last; `refresh()` keeps a table's sort; killing the scratch server shows `reconnecting` then repaints; hiding the
  page gives the subscription back; two elements share one connection (count requests); token expiry mid-stream renews
  without a repaint; another origin is refused (`DRS-8002`); a CSP violation report endpoint receives nothing.
- **Frameworks smoke**: three static pages (React 19, Angular, Vue 3) built once into `tests/elements/frameworks/`
  (vendored builds, no CDN), each binding `entity`, `tokenProvider` and one event; run in Chromium, Firefox and WebKit.
- **Load (local, small, per the house rule)**: 20 elements, 10 entities, one page; then 50 pages: upstream stream count,
  console memory, frame latency (`p99Ms`) against the console-only baseline.

## 15. Documentation plan

- **`docs/guides/ELEMENTS.md`, the developer guide** (also in the console help centre, `help.yaml`): what Drishti Elements
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

## 16. Build plan

Sizes: **S** ≤ 1 day, **M** 2–4 days, **L** a week or more. Steps in the same parallel group can run at once (at most
five agents, heavy test runs one at a time).

### Step 0: proof of concept (M/L) — before anything else

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

#### Step 0 results (2026-10-06)

**Built.** `tools/elements-demo/` (the host: a stdlib Python server on its own origin with the strict CSP of section 10,
its own sign-in, its own `/api/drishti-token` that asks the console for a token server to server, a search box, a recent
list, an as-of picker, a main `<drishti-view>` and a second, smaller one), `drishti-console/web/embed/drishti-elements.js`
(the element and one `Connection` per page and server, about 540 lines), `routes/embed_routes.py` and `core/embed_poc.py`
(the embed endpoints and the DEV-ONLY token path, **off** unless `embed.poc.enabled`; signing key, per-app secret and exact
origins in config). Run it with [the demo's README](../../tools/elements-demo/README.md).
Acceptance: `drishti-console/tests/test_embed_elements_browser.py` (Playwright, Chromium, Firefox and WebKit, against a real
scratch server, the console and the host on three ports) and `test_embed_poc.py` (CORS, masking, "nothing else changed").
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

#### Steps 1 to 3 as built (2026-10-06)

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

#### As built: steps 4, 5 and 8 (group B, 2026-10-06)

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
drops, for `embed=true`, the two things that mean nothing in a host page: the help link to the console's `/help/panel-kinds`
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

#### Step 7 as built: the console's embed API (2026-10-06)

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

### Step 9 as built: the full `<drishti-view>` (2026-10-06)

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

**Not built:** the `toolbar` slot, `openAbout(panelId)` and `focusPanel(id)` methods, `<drishti-panel>` (phase 2), a `locale` attribute
(the sheet has no locale yet); `live.js`/`view-panels.js` splits of step 6 remain not done (the element applies frames itself in `frames.js`).

#### Step 10 as built: Admin → Embedding (2026-10-06)

**Server.** `EmbedUsage` (one bean, `server/embed`) holds per registered application, lock-free (`LongAdder`): tokens issued, embed calls, refusals by DRS code, live streams open and the last use; only ids the registry knows are counted (the rest share the `(unknown)` bucket), so memory is bounded by the registry. `EmbedTokenService` records an issued token, a refused exchange and, around `authorize`, every call and every refusal (the application is taken from the token once its signature has been checked). `StreamController` counts a live stream open for an embed principal and closes it with the stream. The numbers are also Micrometer meters `drishti.embed.tokens`, `.calls`, `.refusals{code}` and `.streams`, tagged `app`. `GET /api/v1/admin/embed/usage` returns them with the registration form's choices (`settings`: the scopes this server defines, the limits, whether wildcard origins are allowed). Counters start again at a restart; there is no retention to configure, because nothing grows with time. **Disable** is `POST .../{id}/disable` and `/enable` (audited `embed-app-disabled` / `-enabled`), distinct from delete.

**Config apps.** `drishti.embed.apps` is a list of registrations (`secret-sha256`, never a secret; or `jwks`). `EmbedAppStore.setConfigured` validates each with the same rules as a registration (a bad entry stops the start, naming the id), keeps them in the snapshot beside the stored ones (`fromConfig: true`, last use in memory only, nothing written to the identity database) and refuses `create`, `update`, rotate, disable and delete for those ids with `409`. A configured id wins over a stored one of the same id.

**Console.** `/admin/embedding` (`routes/embed_admin_routes.py`, `admin/embedding.html`, `admin-embedding.js`, `admin-embedding.css`) in the Admin menu and the Admin tabs: the list with usage, *New application* (id follows the name; scopes come from the server), the secret dialog (shown once, forgotten on close, `no-store`), rotate with a grace period, disable and enable, delete behind a confirm that focuses *Keep it*; keys `n`, `/`, `Esc`; 44 px targets under `pointer: coarse`; the page is in the 390/1600/2560 sweep. The help `?` opens *Embedding host applications* in the Users and roles guide, which F1 on any admin page reaches.

**Not built:** editing an existing registration in the page (origins, scopes and limits change through `PUT /api/v1/admin/embed/apps/{id}`; rotate, disable and delete are in the page), and apps declared in a pack's own files (only the server's configuration, which a pack's settings can feed through the environment).

### Steps

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
| 13 | **Docs**: `docs/guides/ELEMENTS.md` with the cookbook, CONFIGURATION, API_GUIDE, help centre entry, ADR-021 | M | D | 9, 10 |
| 14 | **Phase 2 elements**: `<drishti-panel>` (one panel, same API with `panel` attribute), `<drishti-about>`, `<drishti-search>` (the type-ahead: needs a search scope `embed:search`) | L | E | 11 |
| 15 | **Optional**: workspaces on `<drishti-view>` instead of iframes; a host-served SharedWorker for HTTP/1.1 hosts | M | E | 9 |

Critical path: 0 → 1 → 2 → 7 → 9 → 11. Groups A and B run in parallel after step 0; C after both; D in parallel after 9.

## 17. Decisions for the product owner

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
