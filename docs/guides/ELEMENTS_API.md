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

# Drishti Elements: the API reference

Generated from the libraries' typed JSDoc by `python3 tools/docs/elements_api.py` (do not edit; `--check` refuses a stale copy or a
public symbol without a typed comment). Every library is ES modules under `drishti-console/web/static/elements/<library>/src/` and
one bundled file next to them; a page loads drishti-core and then `Drishti.elements.load('<library>')`.

| Library | Version | What it is |
|---|---|---|
| [drishti-core](#drishti-core) | 1.1.0 | Formats, theme, API client, events, accessibility, resize, keys, dialogs, drawers and toasts, the filter model every library shares, the element maker and placements, Explain's drawing, the CSV writer, reading typed numbers, loading vendored engines |
| [drishti-grid](#drishti-grid) | 0.4.0 | The grid: its engine (columns, row models, virtual scrolling, pivot, selection, export, keyboard) and its look (Grid and &lt;drishti-grid&gt; in three layouts and three densities), cell editing with undo, paste, write-back and four eyes, and the AG Grid-style adapter (createGridFromAgOptions) |
| [drishti-inputs](#drishti-inputs) | 0.1.0 | Inputs: business-date and range pickers with holiday calendars, the tree picker, the multi-select, the combobox with server type-ahead, forms from a schema with four eyes, the filter bar, the code editor over CodeMirror |
| [drishti-data](#drishti-data) | 0.1.0 | Data: one data-source contract (in memory, REST pages, server-side rows, Arrow, live), the live client over SSE and WebSocket with reconnect, heartbeat, resume and backpressure, and the Arrow client |
| [drishti-chart](#drishti-chart) | 0.2.0 | The chart: one engine-neutral spec over the vendored ECharts (the general kinds, the maps from world to every country's first-level regions, the card and studio modes, the map's ranking and table modes), Explain on any mark, the data table, export, linked selection and live updates; Vega-Lite translated into the spec |
| [drishti-risk](#drishti-risk) | 0.2.0 | The banking module's library (licensed with the module): chart kinds on drishti-chart (the P&L distribution with VaR and ES from the server's bins, the fitted normal, the backtest with its traffic light, the exposure profile with EE, PFE, EEPE and the limit, what moved VaR) and the risk grid preset on drishti-grid (terminal layout, figures never summed in the browser) |
| [drishti-renderer](#drishti-renderer) | 2.0.0 | The client renderer: Drishti screens from a static, live or streamed (GenUI) ViewModel, drawn with drishti-grid, drishti-chart, drishti-core and drishti-data; Elements 2.3 (drishti-view, drishti-panel, drishti-screen); hosts import its public entry, static/render/2.0.0/drishti-render.js |

## drishti-core

Version 1.1.0. Bundle `static/elements/drishti-core/drishti_core.js`, global `Drishti.elements.core`.

### drishti_format.js

#### `MINUS`

The minus sign every Drishti number uses (U+2212), as the server writes it.

#### `EMPTY`

What an empty or missing value reads as on a screen.

#### `MASK`

What a value masked for the person reads as (DataNode.MASK on the server).

#### `COMPACT_UNITS`

The server's compact units (FormatSpec.compact).

#### `plainDigits(a)`

The digits of a number's absolute value as a plain decimal string (never an exponent): the shortest text that reads back as the number, exactly what the server's BigDecimal.valueOf starts from.

| Parameter | Type | Meaning |
|---|---|---|
| `a` | `number` | a finite number |

Returns `string`: for example "0.000001" or "1230000000000000000000".

#### `roundHalfUp(v, decimals)`

The absolute value rounded half up to a number of decimals, as text: the decimal rule of the server (BigDecimal HALF_UP).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `number` | a finite number (its sign is ignored) |
| `decimals` | `number` | how many decimals, 0 or more |

Returns `string`: for example roundHalfUp(1.005, 2) is "1.01" and roundHalfUp(2.5, 0) is "3".

#### `group(plain, separator)`

Commas every three digits of the whole part of plain digits ("1234567.5" is "1,234,567.5").

| Parameter | Type | Meaning |
|---|---|---|
| `plain` | `string` | digits with an optional decimal point |
| `[separator=',']` | `string` | the group separator |

Returns `string`.

#### `formatNumber(v, o)`

A number formatted by the server's rules (FormatSpec.number), with the console's options.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the value: a number, or text that reads as one; anything else is shown as text |
| `[o]` | `NumberOptions` |  |

Returns `string} for example formatNumber(-1234.5, {decimals: 2`: ) is "−1,234.50".

```js
formatNumber(0.1234, {decimals: 1, scale: 100, suffix: '%'})   // "12.3%"
```

#### `formatPercent(v, o)`

A fraction as a percentage (0.1234 reads "12.3%").

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the fraction |
| `[o]` | `NumberOptions` | decimals default 1; scale default 100; suffix default "%" |

Returns `string`.

#### `formatBasisPoints(v, o)`

Basis points, as the server's bp1 format: one decimal, a sign always, " bp". The value is in basis points already; pass {scale: 10000} for a fraction (0.0012 reads "+12.0 bp").

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the value in basis points (or a fraction with scale 10000) |
| `[o]` | `NumberOptions` |  |

Returns `string`.

#### `formatCurrency(v, currency, o)`

An amount of money: the currency's usual decimals (two for USD, none for JPY) and its symbol before the digits when the locale has one ("$1,234.50"), else the code after ("1,234.50 CHF").

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the amount |
| `currency` | `string` | three letters (USD) |
| `[o]` | `NumberOptions & {symbol: boolean}` | symbol false: the code after the digits always |

Returns `string`.

#### `formatCompact(v, o)`

A large number in few characters (1,234,567 reads "1.2m"), the server's compact format by default.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the value |
| `[o]` | `CompactOptions` |  |

Returns `string`.

```js
formatCompact(-2.5e9)   // "−2.5bn"
```

#### `defineFormats(table)`

Adds or replaces named formats (a pack's or a site's formats.yaml, as the server reads them), for formatNamed.

| Parameter | Type | Meaning |
|---|---|---|
| `table` | `Object&lt;string, Object&gt;` | name to {type, decimals, grouping, sign, scale, suffix, pattern} |

Returns `void`.

#### `hasFormat(name)`

Whether a named format is known (bundled or defined).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `boolean`.

#### `namedFormat(name)`

A named format's spec (a copy), or null.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `?{type: string, decimals: number, grouping: boolean, sign: string, scale: number, suffix: ?string, pattern: ?string}`.

#### `formatNames()`

The known named formats.

Returns `string[]`.

#### `formatSpec(spec, v)`

A value by a format spec, exactly as the server's FormatSpec.format: null reads "" (the server's empty), a masked value its mask, text, date (by a Java-style pattern), compact, or a number.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `{type: string, decimals: number, grouping: boolean, sign: string, scale: number, suffix: ?string, pattern: ?string}` |  |
| `v` | `*` |  |

Returns `string`.

#### `formatNamed(name, v)`

A value by a named format (fmt: amount0, signed2, pct2, compact, dmy, ...), the server's Formats.format: an unknown or missing name shows the value as text.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | the format's name |
| `v` | `*` | the value |

Returns `string`.

```js
formatNamed('pct2', 0.01234)   // "1.23%"
```

#### `formatter(kind, options)`

A formatter function with its options fixed, for a screen's one-line helpers (const money = formatter('compact', {...})).

| Parameter | Type | Meaning |
|---|---|---|
| `kind` | `('number'\|'percent'\|'bp'\|'compact'\|'currency')` |  |
| `[options]` | `Object` | the kind's options (currency: {currency, ...}) |

Returns `function(*): string`.

#### `formatPythonFloat(x)`

Python's str() of a float, as the server's templates print a raw number: "74.0" for a whole number, exponents from 1e16 and below 1e-4 as Python writes them ("1e+17", "1e-05"). Only for a number the server sent without its text (an older server's candles).

| Parameter | Type | Meaning |
|---|---|---|
| `x` | `*` |  |

Returns `string`.

#### `BYTE_UNITS`

The binary sizes of formatBytes, largest first: a kibibyte is 1,024 bytes, as the server and the operating system count files.

#### `formatBytes(v, o)`

A size in bytes in few characters: "512 bytes", "12 KB", "3.4 MB", "1.2 GB" (kilobytes whole, larger units with one decimal, half up).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the size in bytes |
| `[o]` | `{decimals: (number\|function(number, string): number), small: string, empty: string}` | decimals: of MB and up (default 1; a function of the size and the unit); small: the word after a size under a kilobyte (default " bytes"); empty: for no size (default —) |

Returns `string`.

```js
formatBytes(3565158)   // "3.4 MB"
```

#### `formatDuration(ms, o)`

A duration in words a person reads at a glance: under a second in milliseconds ("640 ms"), else seconds with one decimal ("2.4 s"), from a minute minutes and seconds ("3 min 05 s").

| Parameter | Type | Meaning |
|---|---|---|
| `ms` | `*` | the duration in milliseconds |
| `[o]` | `{empty: string}` |  |

Returns `string`.

```js
formatDuration(2400)   // "2.4 s"
```

### drishti_dates.js

#### `parseDate(v)`

A value read as a business date: ISO text (yyyy-MM-dd, a longer timestamp's first ten characters), a Date (its local calendar day) or a BusinessDate. Anything else, or a day that does not exist (2026-02-30), is null.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `?BusinessDate`.

#### `daysInMonth(y, m)`

Days in a month.

| Parameter | Type | Meaning |
|---|---|---|
| `y` | `number` |  |
| `m` | `number` | 1 to 12 |

Returns `number`.

#### `isoDate(v)`

A business date as ISO text, yyyy-MM-dd.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | anything parseDate reads |

Returns `string`: '' when it is not a date.

#### `today(now)`

Today's business date in this browser's calendar, as ISO text.

| Parameter | Type | Meaning |
|---|---|---|
| `[now]` | `Date` | the moment (default: now) |

Returns `string`.

#### `formatDate(v, pattern, o)`

A date by a server pattern (dd MMM yyyy reads "07 Nov 2026"). yyyy-MM-dd, the default, returns the ISO text unchanged, as the server does; text that is not a date is returned as it is.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | ISO text, a Date or a BusinessDate |
| `[pattern='yyyy-MM-dd']` | `string` | DateTimeFormatter letters: yyyy yy MMMM MMM MM M dd d EEEE EEE, 'quoted text' |
| `[o]` | `{locale: string}` | month and day names in this locale (default English, as the server) |

Returns `string`.

#### `formatDateRange(from, to, o)`

A range of business dates in few words, the shared parts once: "3–7 Nov 2026", "28 Oct – 3 Nov 2026", "28 Dec 2025 – 3 Jan 2026"; one day reads as that day.

| Parameter | Type | Meaning |
|---|---|---|
| `from` | `*` |  |
| `to` | `*` |  |
| `[o]` | `{locale: string}` |  |

Returns `string`.

#### `isBusinessDay(v, o)`

Whether a day is a business day: not a Saturday or Sunday and not one of the holidays.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the date |
| `[o]` | `{holidays: string[]}` | ISO dates that are not business days |

Returns `boolean`.

#### `addBusinessDays(v, n, o)`

The business date n business days from a date (n may be negative; 0 is the date itself, rolled forward when it is not a business day).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the date |
| `n` | `number` | business days |
| `[o]` | `{holidays: string[]}` |  |

Returns `string`: ISO text.

#### `previousBusinessDay(v, o)`

The last business day before a date (a report's usual "previous close").

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `[o]` | `{holidays: string[]}` |  |

Returns `string`: ISO text.

#### `businessDaysBetween(from, to, o)`

Business days from one date to another: those after from, up to and including to (negative when to is earlier).

| Parameter | Type | Meaning |
|---|---|---|
| `from` | `*` |  |
| `to` | `*` |  |
| `[o]` | `{holidays: string[]}` |  |

Returns `number`.

### drishti_theme.js

#### `DEFAULT_THEME`

The default theme (the sheet's :root).

#### `THEME_STORAGE_KEY`

Where a person's last theme is kept in this browser (as the console's start-up script reads it).

#### `TEXT_TOKENS`

The tokens a theme uses for text, each held to 4.5:1 on every ground.

#### `GROUND_TOKENS`

The grounds text sits on.

#### `themes()`

Every theme: its name, light or dark.

Returns `Array&lt;{name: string, scheme: string}&gt;`.

#### `scheme(name)`

Light or dark.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | a theme |

Returns `('light'\|'dark')`.

#### `currentTheme(doc)`

The theme in force on a document (data-theme on &lt;html&gt;, else the default).

| Parameter | Type | Meaning |
|---|---|---|
| `[doc]` | `Document` |  |

Returns `string`.

#### `setTheme(name, o)`

Puts a theme in force: data-theme on &lt;html&gt;, kept in this browser unless persist is false; tells the page (event drishti:theme).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | a theme (themes()) |
| `[o]` | `{persist: boolean, document: Document}` |  |

Returns `void`.

#### `token(token, o)`

A token's value: in a page, as the page's style computes it (on the element given, else &lt;html&gt;); otherwise from the theme table.

| Parameter | Type | Meaning |
|---|---|---|
| `token` | `string` | "--d-ink" (the "--d-" may be left out) |
| `[o]` | `{theme: string, element: Element}` | theme: read the table for that theme, not the page |

Returns `string`: '' when unknown.

#### `tokens(name)`

Every token of a theme.

| Parameter | Type | Meaning |
|---|---|---|
| `[name]` | `string` | default: the theme in force |

Returns `Object&lt;string, string&gt;`.

#### `parseColor(c)`

A colour as red, green, blue (0 to 255) and alpha: #rgb, #rrggbb, rgb() and rgba().

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `string` |  |

Returns `?{r: number, g: number, b: number, a: number}`.

#### `luminance(c)`

WCAG relative luminance.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `string` | a colour |

Returns `number`: 0 (black) to 1 (white).

#### `contrastRatio(a, b)`

The WCAG contrast ratio of two colours, 1 to 21.

| Parameter | Type | Meaning |
|---|---|---|
| `a` | `string` |  |
| `b` | `string` |  |

Returns `number`.

#### `readableOn(ground, candidates, min)`

Of some colours, the one that reads best on a ground (the first that reaches the minimum, else the highest).

| Parameter | Type | Meaning |
|---|---|---|
| `ground` | `string` |  |
| `candidates` | `string[]` |  |
| `[min=4.5]` | `number` |  |

Returns `string`.

#### `contrastProblems(name)`

The text tokens of a theme that read under 4.5:1 on one of its grounds (opaque colours only): empty when the theme is accessible.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `Array&lt;{text: string, ground: string, ratio: number}&gt;`.

### drishti_api.js

#### `AS_OF_HEADER`

The header that carries the page's as-of date (the console's core/asof.py reads it).

#### class `ApiError`

A failed call, in words.

##### `new ApiError(message, info)`

| Parameter | Type | Meaning |
|---|---|---|
| `message` | `string` | the words a person reads |
| `[info]` | `{status: number, code: ?string, detail: ?string, body: *, issues: Array, aborted: boolean, timedOut: boolean}` |  |

#### `errorWords(body, status, fallback)`

The words of an error answer: its DRS code and the server's detail ("DRS-1001 ..."), else the fallback.

| Parameter | Type | Meaning |
|---|---|---|
| `body` | `*` | the answer's JSON ({code, detail} or {error: {code, message}}) |
| `status` | `number` |  |
| `[fallback]` | `string` | words when the server gave none (default "The server answered &lt;status&gt;.") |

Returns `{message: string, code: ?string, detail: ?string}`.

#### class `ApiClient`

An API client.

##### `new ApiClient(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `ClientOptions` |  |

##### `ApiClient.request(method, path, body, c)`

Calls the server; resolves with the answer (JSON by default; null for 204) or rejects with an ApiError in words.

| Parameter | Type | Meaning |
|---|---|---|
| `method` | `string` | GET, POST, PUT, PATCH, DELETE |
| `path` | `string` |  |
| `[body]` | `*` | sent as JSON (FormData as it is) |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

##### `ApiClient.send(method, path, body, c)`

The call's outcome as an envelope that never rejects ({ok, status, body, error}), for code that branches on the status.

| Parameter | Type | Meaning |
|---|---|---|
| `method` | `string` |  |
| `path` | `string` |  |
| `[body]` | `*` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;Envelope&gt;`.

##### `ApiClient.get(path, c)`

A GET (retried on a network failure or a busy server).

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

##### `ApiClient.post(path, body, c)`

A POST (never retried unless asked).

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |
| `[body]` | `*` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

##### `ApiClient.put(path, body, c)`

A PUT.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |
| `[body]` | `*` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

##### `ApiClient.patch(path, body, c)`

A PATCH.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |
| `[body]` | `*` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

##### `ApiClient.del(path, c)`

A DELETE.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

#### `createClient(o)`

A new API client.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `ClientOptions` |  |

Returns `ApiClient`.

#### `api` : `ApiClient`

The client a console page shares: same origin, the browser's session. In the console the as-of date travels in its cookie (the console sends it on as X-Drishti-As-Of); a page that calls a Drishti server directly makes its own client with {asOf}.

#### `call(method, url, body, c)`

One line of a screen's "call and throw in words" helper: the fetch the console's screens used to write each for itself.

| Parameter | Type | Meaning |
|---|---|---|
| `method` | `string` |  |
| `url` | `string` |  |
| `[body]` | `*` |  |
| `[c]` | `CallOptions` |  |

Returns `Promise&lt;*&gt;`.

### drishti_events.js

#### class `EventBus`

An event bus.

##### `new EventBus(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{onError: function(Error, string): void}` | who hears a listener's error (default: console.error) |

##### `EventBus.on(type, fn)`

Listens to an event ("*": every event).

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `fn` | `Listener` |  |

Returns `function(): void`: stops listening.

##### `EventBus.once(type, fn)`

Listens to the next event of a type only.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `fn` | `Listener` |  |

Returns `function(): void`: stops listening.

##### `EventBus.off(type, fn)`

Stops a listener (or, without one, every listener of the type).

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `[fn]` | `Listener` |  |

Returns `void`.

##### `EventBus.emit(type, payload)`

Tells every listener of the type, then every "*" listener.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `[payload]` | `*` |  |

Returns `number`: how many listeners heard it.

##### `EventBus.count(type)`

How many listeners a type has.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |

Returns `number`.

##### `EventBus.clear()`

Forgets every listener.

Returns `void`.

#### `createBus(o)`

A new bus.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{onError: function(Error, string): void}` |  |

Returns `EventBus`.

#### `bus`

The bus a page shares.

### drishti_a11y.js

#### `FOCUSABLE`

What can take focus, in a selector.

#### `focusable(container)`

The elements inside a container that take focus by Tab, in order (hidden ones left out).

| Parameter | Type | Meaning |
|---|---|---|
| `container` | `Element` |  |

Returns `Element[]`.

#### `trapFocus(container, o)`

Keeps Tab and Shift+Tab inside a container until released; focuses its first focusable element (or opts.initial) now, and gives focus back to what had it when released.

| Parameter | Type | Meaning |
|---|---|---|
| `container` | `Element` |  |
| `[o]` | `{initial: Element, returnFocus: boolean}` | returnFocus default true |

Returns `function(): void`: releases the trap.

#### `announce(text, o)`

Says something to a screen reader without moving focus (one shared live region per politeness, made on first use).

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `[o]` | `{politeness: ('polite'\|'assertive'), document: Document}` |  |

Returns `void`.

#### `rovingIndex(index, count, key, o)`

The index a key moves to in a roving tabindex: arrows by one (by orientation), Home and End to the ends; null when the key does not move.

| Parameter | Type | Meaning |
|---|---|---|
| `index` | `number` | the current index |
| `count` | `number` | how many items |
| `key` | `string` | KeyboardEvent.key |
| `[o]` | `{orientation: ('horizontal'\|'vertical'\|'both'), wrap: boolean, columns: number}` | columns: a grid of that many per row |

Returns `?number`.

#### `rovingTabindex(container, o)`

Makes a container's items one Tab stop: the current item has tabindex 0, the others -1; arrows (and Home, End) move between them.

| Parameter | Type | Meaning |
|---|---|---|
| `container` | `Element` |  |
| `[o]` | `{selector: string, orientation: string, wrap: boolean, columns: number, onMove: function(Element, number): void}` | selector: the items (default: [role=tab], [role=option], [role=menuitem], [role=gridcell], button) |

Returns `{focus: function(number): void, refresh: function(): void, destroy: function(): void, index: function(): number}`.

### drishti_resize.js

#### `installResize(window)`

Starts the resize component in a page (drishti-core's bundle does it on load): binds every data-resize element now and as markup appears, re-applies widths on a mode change and on crossing the phone width, and sets window.Drishti.resize. Idempotent.

| Parameter | Type | Meaning |
|---|---|---|
| `window` | `Window` | the page |

Returns `{scan: function(Element): void, handle: function(Element, Object): Element, reset: function(string): void, phone: function(): boolean}`.

#### `scanResize(root)`

Binds every data-resize element under root (done by itself on load and on DOM changes).

| Parameter | Type | Meaning |
|---|---|---|
| `[root]` | `(Element\|Document)` |  |

Returns `void`.

#### `resizeHandle(el, opts)`

The low-level handle the others are built on, for a screen with its own model of sizes.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `Element` |  |
| `opts` | `{axis: string, label: string, start: Function, move: Function, end: Function, key: Function, reset: Function, value: Function}` |  |

Returns `Element`.

#### `resetResize(key)`

Forgets a remembered width set (all modes) and re-applies the defaults.

| Parameter | Type | Meaning |
|---|---|---|
| `key` | `string` | the data-resize-key |

Returns `void`.

### drishti_keys.js

#### `WORKBENCH`

The workbench's scheme of the panel keys.

#### `LAYOUT`

Layout mode's scheme of the panel keys.

#### `interpretPanelKey(e, scheme)`

A panel key's meaning, or null: {type: 'span', delta}      width in columns             Shift+Left/Right, - and + {type: 'height', delta}    height in rows               Shift+Up/Down {type: 'natural'}          back to the content's height  A {type: 'move', step}       one place up (-1) or down (+1) in its column {type: 'column', area}     to the main or the side column {type: 'hide'}             layout mode: hide or show    H, Delete {type: 'remove'}           workbench: delete the panel  Delete {type: 'select', dir}      workbench: 'prev', 'next', 'main', 'side', 'first', 'last' (plain arrows, Home, End)

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `{key: string, shiftKey: boolean, altKey: boolean, ctrlKey: boolean, metaKey: boolean}` | a KeyboardEvent (or its fields) |
| `scheme` | `string` | LAYOUT or WORKBENCH |

Returns `?Object`.

#### `GridKeys`

The panel keys as the console's pages know them (window.Drishti.GridKeys).

#### `keyName(e)`

A key press as one name: modifiers in the order Ctrl, Alt, Shift, Meta, then the key ("Ctrl+Shift+ArrowLeft", "Space", "A"). "Mod" in a key map stands for Ctrl, or Meta on a Mac.

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `{key: string, ctrlKey: boolean, altKey: boolean, shiftKey: boolean, metaKey: boolean}` |  |

Returns `string`.

#### `normalizeKey(name, o)`

A name written by a person ("shift+left", "Mod+C") in keyName's form.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `[o]` | `{mac: boolean}` | mac: Mod is Meta (default: Ctrl) |

Returns `string`.

#### `keymap(bindings, o)`

A key map: names (in any case, "Mod" for Ctrl or Cmd) to actions; the returned function gives a key press's action, or undefined.

| Parameter | Type | Meaning |
|---|---|---|
| `bindings` | `Object&lt;string, *&gt;` | for example {'Mod+C': 'copy', 'Shift+ArrowDown': 'extendDown'} |
| `[o]` | `{mac: boolean}` |  |

Returns `function(KeyboardEvent): *`.

```js
const act = keymap({'Mod+A': 'selectAll'}); act({key: 'a', ctrlKey: true})   // 'selectAll'
```

### drishti_overlay.js

#### `openDialog(dialog, o)`

Opens a &lt;dialog&gt; as a modal (when it is not open already) and focuses its first field (or opts.focus); focus goes back to what had it when the dialog closes.

| Parameter | Type | Meaning |
|---|---|---|
| `dialog` | `HTMLDialogElement` |  |
| `[o]` | `{focus: (Element\|string), within: Element}` | focus: the element (or a selector inside the dialog) to focus; within: look for the first field only inside this part of the dialog (its body) |

Returns `HTMLDialogElement`.

#### `closeDialog(dialog, value)`

Closes a &lt;dialog&gt; when it is open.

| Parameter | Type | Meaning |
|---|---|---|
| `dialog` | `HTMLDialogElement` |  |
| `[value]` | `string` | its returnValue |

Returns `void`.

#### `openDrawer(el, o)`

Opens a drawer (any element): shown, marked open (data-open, aria-hidden false), Tab kept inside, Escape and the returned function close it; focus goes back to what had it.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` |  |
| `[o]` | `{onClose: function(): void, initial: Element, escape: boolean}` | escape default true |

Returns `function(): void`: closes the drawer.

#### class `ToastTray`

A tray of toasts.

##### `new ToastTray(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `Object` |  |
| `[o.parent]` | `Element` | where the tray goes (default: document.body) |
| `[o.className='drishti-toasts']` | `string` | the tray's classes |
| `[o.bare=false]` | `boolean` | no tray element: the toasts go straight into parent |
| `[o.max=3]` | `number` | at most this many at once; more are counted in one "more" toast |
| `[o.single=false]` | `boolean` | a new toast replaces the one shown |
| `[o.timeout=9000]` | `number` |  |
| `[o.more]` | `{href: string, className: string, html: function(number): string, timeout: number}` | the "N more" toast |
| `[o.document]` | `Document` |  |

##### `ToastTray.show(t)`

Shows a toast.

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `ToastOptions` |  |

Returns `?{element: HTMLElement, close: function(): void}`: null when the toast was counted in "more".

#### `toast(t)`

Shows a toast in the page's shared tray (made on first use).

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `(ToastOptions\|string)` | the options, or the text |

Returns `?{element: HTMLElement, close: function(): void}`.

### drishti_loader.js

#### `configure(o)`

Where the libraries are: base, the elements folder ("/static/elements/"); staticBase, the static root the style sheets are named from (default: the folder above base).

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{base: string, staticBase: string}` |  |

Returns `void`.

#### `manifest()`

The manifest: every library by name, its version, files and requirements.

Returns `{elements: string, libraries: Object&lt;string, Object&gt;}`.

#### `libraryVersion(name)`

A library's version (null when there is no such library).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | for example "drishti-grid" |

Returns `?string`.

#### `requirements(name)`

A library and every library it requires, deepest first, each once.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `string[]`.

#### `loaded(name)`

The library's API when it is loaded, else null.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `?Object`.

#### `load(names)`

Loads libraries (and what they require) into the page, each once; resolves with the API of the one named, or the APIs in order.

| Parameter | Type | Meaning |
|---|---|---|
| `names` | `(string\|string[])` | for example "drishti-grid" |

Returns `Promise&lt;(Object\|Object[])&gt;`.

```js
Drishti.elements.core.load('drishti-grid').then((grid) => grid.createGridModel({...}))
```

#### `staticUrl(path)`

The address of a file under the static root (the vendored engines: "vendor/codemirror/codemirror.js").

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` | relative to the static root |

Returns `string`.

#### `loadScript(src)`

Loads a classic script into the page once (a vendored engine a library wraps: ECharts, CodeMirror, Arrow JS); resolves when it has run. A script the page already has (the same address, the query aside) is not loaded again.

| Parameter | Type | Meaning |
|---|---|---|
| `src` | `string` | its address (staticUrl for a vendored file) |

Returns `Promise&lt;void&gt;`.

#### `loadStyle(href)`

Adds a style sheet to the page once (a vendored engine's).

| Parameter | Type | Meaning |
|---|---|---|
| `href` | `string` |  |

Returns `void`.

### drishti_filters.js

#### `isBlank(v)`

Whether a value is blank (null, undefined, '' or NaN).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `boolean`.

#### `compareValues(a, b)`

The natural order of two values (blanks are not handled here: see compareRows).

| Parameter | Type | Meaning |
|---|---|---|
| `a` | `*` |  |
| `b` | `*` |  |

Returns `number`.

#### `compareRows(model, column, value)`

A comparator of rows by a sort model: each item in turn, blanks last whatever the direction, the column's own comparator when it has one.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `SortItem[]` |  |
| `column` | `function(string): Object` | the column of an id |
| `value` | `function(Object, Object): *` | a row's value in a column |

Returns `?function(Object, Object): number`: null when the model is empty.

#### `cycleSort(model, colId, multi)`

The next sort of a column when its header is clicked: none, ascending, descending, none; with multi (Shift) the column is added to the model, else it replaces it.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `SortItem[]` |  |
| `colId` | `string` |  |
| `[multi]` | `boolean` |  |

Returns `SortItem[]`.

#### `passes(v, f)`

Whether a value passes a column's filter.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `f` | `ColumnFilter` |  |

Returns `boolean`.

#### `rowFilter(model, quick, columns, value)`

A test of rows by a filter model and a quick filter, or null when nothing filters.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `Object&lt;string, ColumnFilter&gt;` | column id to its filter |
| `quick` | `string` | words every one of which must appear in one of the columns |
| `columns` | `Object[]` | the columns the quick filter looks in (and the model's) |
| `value` | `function(Object, Object): *` |  |

Returns `?function(Object): boolean`.

#### `filterWords(model, header)`

A filter model in words, for the line above a grid ("Desk in RATES, FX; Notional &gt; 1000000").

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `Object&lt;string, ColumnFilter&gt;` |  |
| `[header]` | `function(string): string` | a column's heading |

Returns `string`.

#### `FILTER_CHANNEL`

The bus event a filter bar emits and grids and charts listen to; its payload is {model, quickFilter, source}.

#### `filterRows(rows, model, o)`

The rows that pass a filter model (and a quick filter), each filter's column read as the row's property of that name unless a value function says otherwise: what a chart, an in-memory source or a page without a grid uses.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |
| `model` | `Object&lt;string, ColumnFilter&gt;` |  |
| `[o]` | `{quickFilter: string, columns: Array&lt;{id: string, field: string}&gt;, value: function(Object, Object): *}` | columns: where the quick filter looks (default: the model's columns); value: a row's value in a column (default: row[column.field or column.id]) |

Returns `Object[]`.

#### `textSortKey(text)`

A drawn cell's sort key, read as the console's tables read it: an amount (separators, signs with the true minus or a dash, k, m, mm, b, bn, tn, a trailing % or bp) is a number; an ISO date is its time; anything else is its lower-cased text. The document table sorts by it, as the console's server tables do.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` | the cell's text as shown |

Returns `{n: number, s: string}`: n for a number or a date, else s.

#### `compareTextKeys(a, b)`

The order of two sort keys (textSortKey): numbers and dates before text, numbers by value, text by code point.

| Parameter | Type | Meaning |
|---|---|---|
| `a` | `{n: number, s: string}` |  |
| `b` | `{n: number, s: string}` |  |

Returns `number`.

### drishti_dom.js

#### `h(doc, tag, p, children)`

Makes an element: classes, text, attributes, data, CSSOM styles (never a style attribute), listeners and children.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `tag` | `string` |  |
| `[p]` | `{cls: string, text: string, attrs: Object&lt;string, string&gt;, data: Object&lt;string, string&gt;, style: Object&lt;string, string&gt;, on: Object&lt;string, function(Event): void&gt;}` |  |
| `[children]` | `Array&lt;(Node\|string\|null)&gt;` |  |

Returns `HTMLElement`.

#### `placeAt(el, x, y)`

Keeps a floating element inside the window: at (x, y), moved left or up when it would leave it.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` |  |
| `x` | `number` |  |
| `y` | `number` |  |

Returns `void`.

#### `placeUnder(el, anchor, o)`

Places a floating element (position: fixed) under an anchor, or above it when there is no room below, inside the window.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` |  |
| `anchor` | `Element` |  |
| `[o]` | `{gap: number, matchWidth: boolean}` | gap default 4 px; matchWidth: at least as wide as the anchor |

Returns `void`.

#### `attrAllowed(name)`

Whether an attribute name may carry a value from data (the allow-list, data-* and aria-*; never on*, style, src, srcdoc, formaction).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `boolean`.

#### `safeUrl(u)`

The address itself when it is safe in an href (control characters and spaces around it ignored), else null.

| Parameter | Type | Meaning |
|---|---|---|
| `u` | `*` |  |

Returns `?string`.

### drishti_parse.js

#### `parseNumberText(text, locale)`

A number from typed or pasted text: 1,250,000.5; −1.5; (2,000); 1.25m, 300k, 2bn; 12.5%; in a locale whose marks differ (de-DE: 1.250.000,5). Null when it does not read.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `[locale]` | `string` |  |

Returns `?number`.

#### `numberEditText(v, locale)`

A number as an editor shows it for changing: no grouping, every digit, the locale's decimal mark and the true minus.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `[locale]` | `string` |  |

Returns `string`.

### drishti_files.js

#### `toCsv(t, o)`

The table as CSV text.

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `ExportTable` |  |
| `[o]` | `{formatted: boolean, separator: string, bom: boolean}` | formatted: values as shown (named formats), else raw (default); separator default ","; bom default true |

Returns `string`.

#### `download(doc, name, content, type)`

Hands the person a file: text, bytes or a data: URL, through a blob link (nothing leaves the page).

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `name` | `string` | the file's name |
| `content` | `(string\|Uint8Array\|Blob)` | text, bytes, a Blob, or a data: URL (a PNG from a canvas) |
| `[type='text/plain']` | `string` | the MIME type of text or bytes |

Returns `void`.

### drishti_explain.js

#### `sparkline(doc, values, limit, prefix)`

A small line of figures over time, as SVG (the colours are the style sheet's: .&lt;prefix&gt;-spark).

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `values` | `number[]` |  |
| `[limit]` | `number` | a dashed line at the limit |
| `[prefix='dx']` | `string` | the class prefix of the component drawing it (dg: the grid, dc: the chart) |

Returns `SVGElement`.

#### `renderExplain(el, st, mode, o)`

Draws an Explain state into its place: 'drawer' and 'card' one column, 'split' three panes (the explanation, the worst scenarios, the trend).

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` | the place |
| `st` | `?ExplainState` | nothing yet: the hint of how to ask |
| `mode` | `('drawer'\|'card'\|'split')` |  |
| `[o]` | `{onClose: function(): void, explainable: boolean, prefix: string, hint: string}` | onClose: a close button; prefix: the component's class prefix (default dx); hint: the words shown before anything is asked |

Returns `void`.

### drishti_index.js

#### `install(root)`

Starts drishti-core in a page (the bundle calls it when it loads; nothing happens without a document): the resize component, and the console's names for what moved here (window.Drishti.resize, window.Drishti.GridKeys) and window.Drishti.elements.load.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.

### Types of drishti-core

#### `ClientOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[base='']` | `string` | put before every path that does not start with http(s):// or / |
| `[asOf]` | `(string\|function(): ?string)` | the as-of date sent with every call (a function: asked at each call) |
| `[headers]` | `Object&lt;string, string&gt;` | sent with every call |
| `[credentials='same-origin']` | `string` | the fetch credentials: the browser's session |
| `[retries=2]` | `number` | how many more tries a GET gets after a network failure or 502, 503, 504 |
| `[retryDelay=300]` | `number` | the first pause in ms (then doubled) |
| `[timeout=0]` | `number` | ms before a call is given up (0: none) |
| `[onUnauthorized]` | `function(ApiError): void` | told of a 401 (the session ended) |
| `[fetch]` | `function(string, Object): Promise&lt;Response&gt;` | the fetch to use (default: globalThis.fetch) |
| `[unreachable]` | `string` | the words when the server cannot be reached |

#### `CallOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[signal]` | `AbortSignal` | aborts the call |
| `[headers]` | `Object&lt;string, string&gt;` |  |
| `[asOf]` | `?string` | this call's as-of date (null: none) |
| `[retries]` | `number` | this call's retries (a POST gets none unless given) |
| `[timeout]` | `number` |  |
| `[fallback]` | `string` | the words of an error without the server's own |
| `[as='json']` | `('json'\|'text'\|'blob'\|'response')` | what the call returns |

#### `Envelope` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `ok` | `boolean` |  |
| `status` | `number` | 0 when the server was not reached |
| `body` | `*` | the JSON answer ({} when there was none); on a failure {detail} at least |
| `error` | `?ApiError` |  |

#### `BusinessDate` : `Object`

A business date: year, month (1 to 12) and day.

| Name | Type | Meaning |
|---|---|---|
| `y` | `number` |  |
| `m` | `number` | 1 to 12 |
| `d` | `number` | 1 to 31 |

#### `Listener`

A listener: the event's payload and its name.

| Name | Type | Meaning |
|---|---|---|
| `payload` | `*` |  |
| `type` | `string` |  |

#### `ExplainResult` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[title]` | `string` | what is explained ("VaR 99% 1d") |
| `[value]` | `string` | the figure as shown |
| `[scope]` | `string` | where it holds (the members, the date) |
| `[steps]` | `Array&lt;{label: string, value: string}&gt;` | how it was made, in order |
| `[parts]` | `Array&lt;{label: string, value: number, text: string}&gt;` | what it is made of (a bar each; a diversification part too) |
| `[partsTitle]` | `string` | the parts' heading ("Stand-alone by Book") |
| `[scenarios]` | `Array&lt;{label: string, value: string}&gt;` | the worst scenarios (the terminal's middle pane) |
| `[trend]` | `number[]` | the figure over time (the terminal's right pane) |
| `[limit]` | `number` | a limit drawn on the trend |
| `[links]` | `Array&lt;{label: string, href: string}&gt;` | where to go next |
| `[note]` | `string` | a last word (an approximation, a fallback) |

#### `ExplainState` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `row` | `number` |  |
| `col` | `number` |  |
| `loading` | `boolean` |  |
| `result` | `?(ExplainResult\|string\|Node)` |  |
| `error` | `?Error` |  |

#### `ExportTable` : `Object`

A table to export.

| Name | Type | Meaning |
|---|---|---|
| `columns` | `Array&lt;{header: string, type: string, format: *, width: number}&gt;` |  |
| `rows` | `Array&lt;Array&lt;*&gt;&gt;` | raw values, one array per row, in the columns' order |
| `[levels]` | `number[]` | each row's outline level (0: none), for group rows and their members |
| `[bold]` | `boolean[]` | rows drawn bold (group rows and totals) |

#### `SortItem` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `sort` | `('asc'\|'desc')` |  |

#### `ColumnFilter` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `type` | `('text'\|'number'\|'date'\|'set'\|'blank')` |  |
| `[op]` | `string` | text: contains, notContains, equals, notEquals, startsWith, endsWith; number and date: equals, notEquals, lt, lte, gt, gte, between; blank: blank, notBlank |
| `[value]` | `*` |  |
| `[valueTo]` | `*` | the upper end of between (inclusive) |
| `[values]` | `Array&lt;*&gt;` | set: the members kept |
| `[excluded]` | `Array&lt;*&gt;` | set: the members left out |

#### `NumberOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[decimals=0]` | `number` | decimals shown (half up); with maxDecimals, the fewest shown |
| `[maxDecimals]` | `number` | the most decimals shown: trailing zeros beyond decimals are dropped (1.50 reads 1.5) |
| `[grouping=true]` | `boolean` | commas every three digits |
| `[sign='auto']` | `('auto'\|'always'\|'never')` | auto: a minus when negative; always: "+" on a positive value too; never: no sign |
| `[minus='−']` | `string` | the minus sign (the console's few ASCII-minus charts pass '-') |
| `[scale=1]` | `number` | multiplied in first (100 for a percentage of a fraction) |
| `[prefix='']` | `string` | before the digits, after the sign ("$") |
| `[suffix='']` | `string` | after the digits ("%", " bp") |
| `[empty='—']` | `string` | what null, undefined, NaN and the infinities read as |
| `[locale]` | `string` | a BCP 47 locale for the group and decimal separators (default: the server's: comma and point) |

#### `CompactOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[decimals=1]` | `(number\|function(number, string): number)` | decimals, or a function of (the absolute value, the unit) |
| `[trim=false]` | `(boolean\|string)` | drop trailing zeros (1.50m reads 1.5m); 'small': only below the smallest unit |
| `[units]` | `Array&lt;Array&lt;*&gt;&gt;` | [suffix, size, from] largest first; from (default size) is where the unit starts (the server's: bn, m, k; pass [['tn', 1e12], ...] for trillions) |
| `[smallDecimals]` | `(number\|null)` | decimals below the smallest unit (default: decimals) |
| `[smallGrouping=true]` | `boolean` | commas below the smallest unit |
| `[sign='auto']` | `('auto'\|'always'\|'never')` |  |
| `[minus='−']` | `string` |  |
| `[prefix='']` | `string` | before the digits ("$") |
| `[empty='—']` | `string` |  |

#### `ToastOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[text]` | `string` | the message, as text |
| `[html]` | `string` | the message as markup (escape what is not yours) |
| `[href]` | `string` | the toast is a link |
| `[className]` | `string` | the toast's classes |
| `[attrs]` | `Object&lt;string, string&gt;` | more attributes (data-toast ...) |
| `[action]` | `{label: string, run: function(): void, className: string}` | a button in the toast; a click runs it and closes the toast |
| `[timeout]` | `number` | ms before it goes (the tray's default; 0: it stays) |

## drishti-grid

Version 0.4.0. Bundle `static/elements/drishti-grid/drishti_grid.js`, global `Drishti.elements.grid`; requires drishti-core.

### drishti_columns.js

#### `ADDITIVE_FUNCS`

Aggregation functions the browser computes from members (never from other totals).

#### `normalizeColumn(def, index)`

A column definition with every default filled in.

| Parameter | Type | Meaning |
|---|---|---|
| `def` | `ColumnDef` |  |
| `[index]` | `number` |  |

Returns `Object`.

#### `getValue(row, col)`

A column's value in a row.

| Parameter | Type | Meaning |
|---|---|---|
| `row` | `Object` |  |
| `col` | `Object` | a normalized column |

Returns `*`.

#### `formatValue(v, col, row)`

A value as the column shows it.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `col` | `Object` | a normalized column |
| `[row]` | `Object` |  |

Returns `string`.

#### class `ColumnModel`

The columns of a grid: their order, widths, pinning and visibility, and the field list.

##### `new ColumnModel(defs)`

| Parameter | Type | Meaning |
|---|---|---|
| `defs` | `ColumnDef[]` |  |

##### `ColumnModel.setColumns(defs)`

Replaces every column.

| Parameter | Type | Meaning |
|---|---|---|
| `defs` | `ColumnDef[]` |  |

Returns `void`.

##### `ColumnModel.all()`

Every column, in order (hidden ones too).

Returns `Object[]`.

##### `ColumnModel.get(id)`

A column by id.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `?Object`.

##### `ColumnModel.visible()`

The shown columns in display order: pinned left, the middle, pinned right.

Returns `Object[]`.

##### `ColumnModel.layout()`

Where each part of the shown columns sits: the pinned ones and the middle, each column's left edge within its part, and widths.

Returns `{left: Object[], center: Object[], right: Object[], centerOffsets: Float64Array, centerWidth: number, leftWidth: number, rightWidth: number}`.

##### `ColumnModel.setWidth(id, width)`

Sets a column's width, within its least and most.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `width` | `number` | px |

Returns `number`: the width set.

##### `ColumnModel.setVisible(id, shown)`

Shows or hides a column.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `shown` | `boolean` |  |

Returns `void`.

##### `ColumnModel.pin(id, side)`

Pins a column left or right, or unpins it (null).

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `side` | `('left'\|'right'\|null)` |  |

Returns `void`.

##### `ColumnModel.move(id, to)`

Moves a column to a place in the order of all columns.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `to` | `number` | the new index |

Returns `void`.

##### `ColumnModel.fields(search)`

The field list a person picks from to build a view: every column with what it can be used for, filtered by words (all of them, in the header, the id or the description; case and accents ignored), grouped columns kept together.

| Parameter | Type | Meaning |
|---|---|---|
| `[search]` | `string` |  |

Returns `Array&lt;{id: string, header: string, type: string, group: ?string, rowGroup: boolean, pivot: boolean, value: boolean, additive: boolean, description: string}&gt;`.

##### `ColumnModel.getState()`

The columns' state, to keep and give back (widths, order, pinning, visibility).

Returns `Array&lt;{id: string, width: number, pinned: ?string, hidden: boolean}&gt;`.

##### `ColumnModel.setState(state)`

Gives back a kept state (columns it does not name keep theirs, after the named ones).

| Parameter | Type | Meaning |
|---|---|---|
| `state` | `Array&lt;{id: string, width: number, pinned: ?string, hidden: boolean}&gt;` |  |

Returns `void`.

### drishti_aggregate.js

#### `AGG_FUNCS`

The functions by name.

#### `aggFunc(name)`

A function by its name or a usual alias (average, distinct), or a registered one; null when the browser does not compute it.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `?AggFunc`.

#### `registerAggFunc(name, fn, o)`

Registers a host's own aggregation for client-side rows (the AG Grid-style adapter's aggFuncs): it is given every member's value of a cell or total and answers the figure; the state is the list of values, so a total is still made from its own members, never from other cells' figures. A built-in name cannot be replaced. The name must be a plain word (it goes into a measure's text, name(FIELD)).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `fn` | `function(Array&lt;*&gt;): *` | the figure of the members' values |
| `[o]` | `{label: string, numeric: boolean}` |  |

Returns `AggFunc`.

#### `unregisterAggFunc(name)`

Forgets a registered aggregation (a grid that registered it is gone).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `boolean`: whether it was registered.

#### `parseMeasure(measure)`

A measure's text read: func(field), or count() for the members; null when it is not that simple form (var(99, 1d), ratio(...)).

| Parameter | Type | Meaning |
|---|---|---|
| `measure` | `string` | for example "sum(NOTIONAL_USD)" |

Returns `?{func: string, field: ?string}`.

#### `isServerMeasure(measure, column)`

Whether a measure must be asked of the server for every cell and total: the browser does not compute its function, or its field is marked non-additive (VaR, ES, a ratio: their totals are never sums of the parts).

| Parameter | Type | Meaning |
|---|---|---|
| `measure` | `string` |  |
| `[column]` | `function(string): ?Object` | a field's column ({additive}) |

Returns `boolean`.

#### `aggregate(values, func)`

The aggregate of some rows' values by a function (a one-off: a selection's figure, a group's).

| Parameter | Type | Meaning |
|---|---|---|
| `values` | `Iterable&lt;*&gt;` |  |
| `func` | `string` |  |

Returns `*`.

### drishti_pivot.js

#### `SEP`

The unit separator joining a path's values in a key.

#### `BLANK`

A blank member's text, as the server writes it.

#### `memberText(v)`

A value as a member: text; null, missing and empty text are "(blank)"; whole numbers without ".0" (the server's and the console's).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

#### `valueLabel(v)`

A value's label (its as, else the measure's text).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `PivotValue` |  |

Returns `string`.

#### `normalizeView(view)`

A view with every default filled in, checked: rows and columns do not repeat a dimension, at least one value.

| Parameter | Type | Meaning |
|---|---|---|
| `view` | `PivotView` |  |

Returns `PivotView`.

#### `serverRequest(view, o)`

The body of a server pivot request (POST /api/pivot/query on the console): {view} in the server's PivotView shape. Only the server's fields go (valueFilters are the browser's).

| Parameter | Type | Meaning |
|---|---|---|
| `view` | `PivotView` |  |
| `[o]` | `{only: string[]}` | only: the labels of the values to ask (the non-additive ones a local pivot left) |

Returns `{view: Object}`.

#### `memberFilters(model)`

Translates a grid filter model into the server's member filters ({in, notIn} per dimension); a filter the server cannot take (text contains, number ranges) is listed in `unsupported`, in words, never dropped silently.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `Object&lt;string, Object&gt;` | column id to a grid filter |

Returns `{filters: Object&lt;string, {in: string[], notIn: string[]}&gt;, unsupported: string[]}`.

#### class `PivotState`

The open state of a pivot's nodes, rows and columns: which members are expanded (by path), what level every node is opened to, and the change of either; toView() puts it in a view. A collapsed column group shows only its total.

##### `new PivotState(view)`

| Parameter | Type | Meaning |
|---|---|---|
| `[view]` | `{expanded: Array, expandTo: Object}` | the state a view holds |

##### `PivotState.isOpen(axis, path)`

Whether a node is open.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `path` | `string[]` | the node's member values from the top |

Returns `boolean`.

##### `PivotState.toggle(axis, pairs, open, by)`

Opens or closes a node.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `pairs` | `Array&lt;Array&lt;string&gt;&gt;` | the node's path as (dimension, value) pairs |
| `[open]` | `boolean` | default: the other state |
| `[by]` | `string` | expand by this dimension (expand by..., per member) |

Returns `boolean`: the new state.

##### `PivotState.expandToLevel(axis, level)`

Opens every node of an axis down to a level (1: only level one shown), forgetting single opens and closes on that axis.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `level` | `number` |  |

Returns `void`.

##### `PivotState.toView(known)`

The state in a view's terms (expanded, expandTo). A node closed below expandTo has no server form: the view opens to level one and names the open nodes it knows (from an answer's entries).

| Parameter | Type | Meaning |
|---|---|---|
| `[known]` | `{rows: Array, columns: Array}` | the entries of the last answer, to name the nodes expandTo opened |

Returns `{expanded: Array, expandTo: {rows: number, columns: number}}`.

#### `pivotLocal(records, view, o)`

A pivot of rows the page holds, answered in the server's shape.

| Parameter | Type | Meaning |
|---|---|---|
| `records` | `Object[]` | the rows |
| `view` | `PivotView` |  |
| `[o]` | `Object` |  |
| `[o.value]` | `function(Object, string): *` | a record's value of a field (default: record[field]) |
| `[o.column]` | `function(string): ?Object` | a field's column, for its additive flag |
| `[o.state]` | `PivotState` | the open state (default: the view's) |

Returns `Object} {measures, rowDimensions, columnDimensions, rows, columns, cells, pending, computedBy: 'browser', notes`.

### drishti_pivot-answer.js

#### `pivotApplyUpdates(answer, updates)`

Changes a local pivot's figures for rows that changed (pivotLocal's answer, in place): each changed row's old values out of every cell it is in, its new ones in, by difference: exact for sums, counts and averages. The other cases (a row moved to another member, a min or max, top N, value filters) answer false: compute the pivot again. Non-additive measures are untouched: ask the server again.

| Parameter | Type | Meaning |
|---|---|---|
| `answer` | `Object` | pivotLocal's answer |
| `updates` | `Array&lt;{old: Object, value: Object}&gt;` | each changed row before and after |

Returns `{applied: boolean, cells: string[]}`: applied false: compute again; cells: the keys of the cells whose figures changed.

#### `mergeAnswer(local, server)`

Puts a server answer's figures into a local answer's pending cells, matched by coordinates; the cells the server did not answer keep their note. Returns how many figures came in.

| Parameter | Type | Meaning |
|---|---|---|
| `local` | `Object` | pivotLocal's answer (changed in place) |
| `server` | `Object` | the server's answer to serverRequest(view, {only: the pending labels}) |

Returns `number`.

#### `applyValueFilters(answer, valueFilters)`

Hides the rows of a server answer whose members fail a value filter (with their children); the totals still include them and the answer's notes say so.

| Parameter | Type | Meaning |
|---|---|---|
| `answer` | `Object` | a server answer (changed in place) |
| `valueFilters` | `Array&lt;{dim: string, by: string, op: string, value: *, valueTo: *}&gt;` |  |

Returns `number`: how many rows were hidden.

#### `resultColumns(answer, o)`

The grid's columns of a pivot answer: the row heading column, then one column per (column entry, value): a column member's figures, a collapsed group's total, the grand total; each with its heading path (the members from the top, then the value's label).

| Parameter | Type | Meaning |
|---|---|---|
| `answer` | `Object` |  |
| `[o]` | `{hideTotals: boolean}` | hideTotals: leave out the subtotal and grand total columns |

Returns `Array&lt;{id: string, colKey: string, measure: string, headerPath: string[], kind: string, depth: number, server: boolean}&gt;`.

#### `cellValue(answer, rowKey, colKey, measure)`

A cell's figure in an answer.

| Parameter | Type | Meaning |
|---|---|---|
| `answer` | `Object` |  |
| `rowKey` | `string` |  |
| `colKey` | `string` |  |
| `measure` | `string` | the value's label |

Returns `{value: *, note: ?string, members: number}`.

#### `measureText(v, m)`

A server answer's figure as the pivot shows it, by the answer's own measure fields (it formats, never computes): a text answer (string_agg, the mode of names) as its text; percentages for a pct show-as; whole numbers for a rank, an ntile bucket and a measure whose unit is count; bn and m for large figures; two decimals otherwise. A grid takes it as its pivotFormat (answerFormat).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `[m]` | `{id: string, unit: ?string, showAs: ?string}` | the answer's measure |

Returns `string`.

#### `answerFormat(m)`

The format of an answer's measure for GridOptions.pivotFormat: measureText bound to the measure.

| Parameter | Type | Meaning |
|---|---|---|
| `m` | `{id: string, unit: ?string, showAs: ?string}` |  |

Returns `function(*): string`.

### drishti_wells.js

#### `WELLS`

The wells, in the ribbon's order.

#### `VALUE_FUNCS`

The aggregations a value can take in the browser (a non-additive column's value is the server's measure).

#### class `WellsModel`

The wells of a pivot.

##### `new WellsModel(columns, initial)`

| Parameter | Type | Meaning |
|---|---|---|
| `columns` | `import('./drishti_columns.js').ColumnModel` |  |
| `[initial]` | `{rows: string[], columns: string[], values: WellValue[], filters: Array&lt;{colId: string, filter: Object}&gt;}` |  |

##### `WellsModel.funcs()`

The aggregations a value may take: the browser's (VALUE_FUNCS), then the server's own (GridOptions.serverFuncs).

Returns `Array&lt;{name: string, label: string, server: boolean}&gt;`.

##### `WellsModel.funcLabel(name)`

An aggregation's label (Sum, Median), or its name when it is not known.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `string`.

##### `WellsModel.fieldList(search)`

The field list, searched (ColumnModel.fields), each field with the wells it is in.

| Parameter | Type | Meaning |
|---|---|---|
| `[search]` | `string` |  |

Returns `Array&lt;Object&gt;`.

##### `WellsModel.has(well, colId)`

Whether a field is in a well.

| Parameter | Type | Meaning |
|---|---|---|
| `well` | `string` |  |
| `colId` | `string` |  |

Returns `boolean`.

##### `WellsModel.canDrop(colId, well)`

Whether a field may be dropped in a well, and if not, why (in words).

| Parameter | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `well` | `string` |  |

Returns `{ok: boolean, why: ?string}`.

##### `WellsModel.drop(colId, well, index)`

Drops a field in a well at a place (moving it from Rows to Columns or back; a field may be a value and a filter as well).

| Parameter | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `well` | `string` |  |
| `[index]` | `number` | the place (default: the end) |

Returns `void`.

##### `WellsModel.remove(well, which)`

Takes a field out of a well (a value by its place, since a field may be a value twice).

| Parameter | Type | Meaning |
|---|---|---|
| `well` | `string` |  |
| `which` | `(string\|number)` | the field, or a value's index |

Returns `void`.

##### `WellsModel.reorder(well, from, to)`

Moves an item within its well.

| Parameter | Type | Meaning |
|---|---|---|
| `well` | `string` |  |
| `from` | `number` |  |
| `to` | `number` |  |

Returns `void`.

##### `WellsModel.setAggregation(index, func)`

Sets a value's aggregation. A non-additive field (VaR, ES) keeps its server measure whatever is asked, and says so.

| Parameter | Type | Meaning |
|---|---|---|
| `index` | `number` | the value's place |
| `func` | `string` |  |

Returns `{ok: boolean, why: ?string}`.

##### `WellsModel.setFilter(colId, filter)`

Sets a filter field's member filter ({type: 'set', values} or {type: 'set', excluded}).

| Parameter | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `filter` | `?Object` |  |

Returns `void`.

##### `WellsModel.getState()`

The wells' state, to keep and give back.

Returns `{rows: string[], columns: string[], values: WellValue[], filters: Array&lt;{colId: string, filter: Object}&gt;}`.

##### `WellsModel.setState(s)`

Gives back a kept state.

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `{rows: string[], columns: string[], values: WellValue[], filters: Array}` |  |

Returns `void`.

##### `WellsModel.measureOf(v)`

A value's measure text: the column's own server measure when it has one or is not additive, else func(field).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `WellValue` |  |

Returns `string`.

##### `WellsModel.toView(extra)`

The pivot view the wells make (with the member filters of the Filters well and of any filter given).

| Parameter | Type | Meaning |
|---|---|---|
| `[extra]` | `Object` | more of the view (expanded, sort, top, subtotals, valueFilters, source) |

Returns `import('./drishti_pivot.js').PivotView`.

### drishti_client-rows.js

#### class `ClientRowModel`

The client-side row model.

##### `new ClientRowModel(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{columns: import('./drishti_columns.js').ColumnModel, rows: Object[], getRowId: function(Object, number): string, clientAggregation: boolean, treeData: boolean, getDataPath: function(Object): string[], groupDefaultExpanded: number}` | clientAggregation false: no group figure is computed here, every one is left for the server (the console's policy); treeData: the rows make a tree by their paths (drishti_tree-data.js); groupDefaultExpanded: open groups and tree nodes down to this level |

##### `ClientRowModel.setRows(rows)`

Replaces every row.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |

Returns `void`.

##### `ClientRowModel.changesOf(before, after)`

A row's changed cells: column id to the old and new value and the way a number moved (for a cell's flash).

| Parameter | Type | Meaning |
|---|---|---|
| `before` | `Object` |  |
| `after` | `Object` |  |

Returns `Object&lt;string, {old: *, value: *, dir: ?string}&gt;`.

##### `ClientRowModel.applyTransaction(t)`

Adds, updates (by id) and removes (by id) rows. Updates alone are applied in place: a row whose sort or filter values changed is taken out of the order and merged back where it now belongs (ties keep the rows' order, so the order is stable), and the figures of its groups follow by difference (sums and counts) or from their members (the others); adds and removes refresh the whole view.

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `{add: Object[], update: Object[], remove: Array&lt;(Object\|string)&gt;}` |  |

Returns `{added: number, updated: number, removed: number, changes: Array&lt;{id: string, cells: Object}&gt;, staleGroups: string[][]}`.

##### `ClientRowModel.setGroupServerValues(path, values)`

Puts the server's figures of a group in (its non-additive columns: VaR, ES): they are no longer waiting.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string[]` | the group's members from the top |
| `values` | `Object&lt;string, *&gt;` | column id to the server's figure |

Returns `boolean`: whether the group exists.

##### `ClientRowModel.serverGroupColumns()`

The groups and the columns whose figures the server gives (non-additive, or every one when clientAggregation is off).

Returns `Array&lt;{path: string[], columns: string[]}&gt;`.

##### `ClientRowModel.setSortModel(m)`

Sorts by a sort model.

| Parameter | Type | Meaning |
|---|---|---|
| `m` | `Array&lt;{colId: string, sort: string}&gt;` |  |

Returns `void`.

##### `ClientRowModel.setFilterModel(m)`

Filters by a filter model (column id to its filter).

| Parameter | Type | Meaning |
|---|---|---|
| `m` | `Object&lt;string, Object&gt;` |  |

Returns `void`.

##### `ClientRowModel.setQuickFilter(q)`

Filters by words across the shown columns.

| Parameter | Type | Meaning |
|---|---|---|
| `q` | `string` |  |

Returns `void`.

##### `ClientRowModel.setGroupBy(colIds)`

Groups rows by columns (outermost first); [] for none.

| Parameter | Type | Meaning |
|---|---|---|
| `colIds` | `string[]` |  |

Returns `void`.

##### `ClientRowModel.setExpanded(path, open)`

Opens or closes a group.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string[]` | its members from the top |
| `[open]` | `boolean` | default: the other state |

Returns `void`.

##### `ClientRowModel.isExpanded(path)`

Whether a group is open.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string[]` |  |

Returns `boolean`.

##### `ClientRowModel.expandToLevel(level)`

Opens every group down to a level (0: all closed; Infinity: all open).

| Parameter | Type | Meaning |
|---|---|---|
| `level` | `number` |  |

Returns `void`.

##### `ClientRowModel.refresh(reason)`

Filters, sorts, groups and flattens again.

| Parameter | Type | Meaning |
|---|---|---|
| `[reason]` | `string` |  |

Returns `void`.

##### `ClientRowModel.rowCount()`

How many rows are displayed (groups and the rows of open groups).

Returns `number`.

##### `ClientRowModel.filteredCount()`

How many data rows pass the filters.

Returns `number`.

##### `ClientRowModel.rowAt(i)`

The row at a display index.

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |

Returns `?DisplayRow`.

##### `ClientRowModel.indexOfId(id)`

The display index of a row id (-1 when it is filtered out or in a closed group).

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `number`.

##### `ClientRowModel.getRowById(id)`

A row's data by its id.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `?Object`.

##### `ClientRowModel.filteredRows()`

The rows passing the filters, in order (for export and selection of all).

Returns `Object[]`.

##### `ClientRowModel.members(colId, limit)`

The distinct members of a column among all rows (for a member filter's list), sorted, blanks last, with counts.

| Parameter | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `[limit=10000]` | `number` |  |

Returns `Array&lt;{value: *, text: string, count: number}&gt;`.

### drishti_server-rows.js

#### class `ServerRowModel`

The server-side row model.

##### `new ServerRowModel(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{datasource: Datasource, blockSize: number, maxBlocks: number, getRowId: function(Object, number, string[]): string}` | blockSize default 100; maxBlocks default 50 (5,000 rows held) |

##### `ServerRowModel.setSortModel(m)`

Sorts by a sort model: what is in flight is aborted, the blocks asked again.

| Parameter | Type | Meaning |
|---|---|---|
| `m` | `Array` |  |

Returns `void`.

##### `ServerRowModel.setFilterModel(m)`

Filters by a filter model: what is in flight is aborted, the blocks asked again.

| Parameter | Type | Meaning |
|---|---|---|
| `m` | `Object` |  |

Returns `void`.

##### `ServerRowModel.setQuickFilter(q)`

Filters by words (the server decides where it looks).

| Parameter | Type | Meaning |
|---|---|---|
| `q` | `string` |  |

Returns `void`.

##### `ServerRowModel.setGrouping(s)`

Sets the row groups, values and pivot the requests carry.

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `{rowGroupCols: string[], valueCols: Array, pivotCols: string[], pivotMode: boolean}` |  |

Returns `void`.

##### `ServerRowModel.setExpanded(keys, open)`

Opens or closes a group row (its level loads when it comes into view).

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` | the group's members from the top |
| `[open]` | `boolean` |  |

Returns `void`.

##### `ServerRowModel.refresh(o)`

Drops cached blocks and asks again: every level, or one level and those under it.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{keys: string[], purge: boolean}` | keys: the level; purge default true (forget the row count too) |

Returns `void`.

##### `ServerRowModel.invalidateBlock(keys, block)`

Forgets one block (its rows are asked again when they are in view); the row count is kept.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` | the level's group keys ([] for the top) |
| `block` | `number` |  |

Returns `boolean`: whether the block was held.

##### `ServerRowModel.purge()`

Forgets everything held (blocks, counts, open groups' levels kept open) and aborts what is in flight: the next draw asks again.

Returns `void`.

##### `ServerRowModel.dataChanged(notice)`

A server's "data changed" notice (drishti-data's live feed): {purge: true} forgets everything; {keys} a level (and those under it); {keys, blocks} only those blocks; {rows: [ids]} the blocks holding those rows. The rows on screen are asked again at the next draw.

| Parameter | Type | Meaning |
|---|---|---|
| `notice` | `{purge: boolean, keys: string[], blocks: number[], rows: string[]}` |  |

Returns `string`: what was done: purge, level, blocks, rows or nothing.

##### `ServerRowModel.rowCount()`

How many rows are displayed (an estimate while a level's count is not known).

Returns `number`.

##### `ServerRowModel.rowAt(i)`

The row at a display index: the data when its block is in, else a placeholder ({loading: true}); asks for the block.

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |

Returns `?Object} {kind: 'group'\|'leaf'\|'loading', id, depth, row, keys, expanded, loading`.

##### `ServerRowModel.ensureRange(first, last)`

Asks for the blocks of a range of display rows (a renderer calls it with the viewport's rows).

| Parameter | Type | Meaning |
|---|---|---|
| `first` | `number` |  |
| `last` | `number` | inclusive |

Returns `Promise&lt;void&gt;`: when those blocks are in (or failed).

##### `ServerRowModel.ensureBlock(levelKey, block)`

Loads one block of a level (once; a block in flight is shared).

| Parameter | Type | Meaning |
|---|---|---|
| `levelKey` | `string` | the level's group keys joined |
| `block` | `number` |  |

Returns `Promise&lt;void&gt;`.

##### `ServerRowModel.cacheStats()`

How many blocks are held, and how many are being asked for.

Returns `{blocks: number, inFlight: number}`.

#### `memoryDatasource(rows, o)`

A data source over rows held in memory (tests, demos, and a page that wants the server-side model's paging over local data): sorts, filters and groups as a server would, with an optional delay.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |
| `[o]` | `{delay: number, columns: import('./drishti_columns.js').ColumnModel}` |  |

Returns `Datasource & {calls: BlockRequest[]}`.

### drishti_viewport.js

#### `MAX_SCROLL_HEIGHT`

The tallest scrolled height used (px); Firefox's limit is near 17.9 million.

#### `columnAt(offsets, x)`

The first index whose left edge is past x (binary search of the edges, offsets[0] = 0, offsets[n] = the total).

| Parameter | Type | Meaning |
|---|---|---|
| `offsets` | `(Float64Array\|number[])` | n + 1 edges |
| `x` | `number` |  |

Returns `number`: the column holding x.

#### class `Viewport`

The scroll arithmetic of a grid's body.

##### `new Viewport(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{rowHeight: number, height: number, width: number, overscanRows: number, overscanColumns: number, maxScrollHeight: number}` | rowHeight default 28 (the design's densities pass theirs), overscan: rows and columns drawn beyond the view (default 6 and 2) |

##### `Viewport.resize(width, height)`

Sets the size of the view (px).

| Parameter | Type | Meaning |
|---|---|---|
| `width` | `number` |  |
| `height` | `number` |  |

Returns `void`.

##### `Viewport.setRowCount(n)`

Sets how many rows there are.

| Parameter | Type | Meaning |
|---|---|---|
| `n` | `number` |  |

Returns `void`.

##### `Viewport.contentHeight()`

The real height of every row (px).

Returns `number`.

##### `Viewport.scrollHeight()`

The height the scroller is given (the real one, capped).

Returns `number`.

##### `Viewport.maxScrollTop()`

The largest scroll position.

Returns `number`: px.

##### `Viewport.scrollTo(top, left)`

Scrolls (clamped).

| Parameter | Type | Meaning |
|---|---|---|
| `top` | `number` |  |
| `[left]` | `number` |  |

Returns `void`.

##### `Viewport.rows()`

The rows to draw now: first to last (inclusive, overscan included), and where the first goes (px from the scroller's top).

Returns `{first: number, last: number, offset: number, firstVisible: number, lastVisible: number}`.

##### `Viewport.scrollToRow(index)`

The scrollTop that shows a row (the least scroll that brings it into view; null when it is in view already).

| Parameter | Type | Meaning |
|---|---|---|
| `index` | `number` |  |

Returns `?number`.

##### `Viewport.columns(offsets, pinnedWidth)`

The middle columns to draw for the scroll position, given their edges (ColumnModel.layout().centerOffsets) and the room the pinned columns take.

| Parameter | Type | Meaning |
|---|---|---|
| `offsets` | `(Float64Array\|number[])` |  |
| `[pinnedWidth=0]` | `number` |  |

Returns `{first: number, last: number}`: inclusive; last &lt; first when there are none.

### drishti_selection.js

#### class `SelectionModel`

Rows and cell ranges selected.

##### `new SelectionModel(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{mode: ('single'\|'multiple'\|'none')}` | row selection mode (default multiple) |

##### `SelectionModel.clickRow(index, idAt, mods)`

A click on a row: alone it selects only that row; ctrl toggles it; shift selects the run from the anchor.

| Parameter | Type | Meaning |
|---|---|---|
| `index` | `number` | the display index |
| `idAt` | `function(number): ?string` | the id of a display index (null for a group or a placeholder) |
| `[mods]` | `{ctrl: boolean, shift: boolean}` |  |

Returns `void`.

##### `SelectionModel.selectAll(ids)`

Selects every given id (the filtered rows'), or clears.

| Parameter | Type | Meaning |
|---|---|---|
| `[ids]` | `Iterable&lt;string&gt;` | none: clear |

Returns `void`.

##### `SelectionModel.isSelected(id)`

Whether a row is selected.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `boolean`.

##### `SelectionModel.selectedIds()`

The selected ids, in the order they were selected.

Returns `string[]`.

##### `SelectionModel.startRange(row, col, mods)`

Starts a cell range at a cell (ctrl: as another range, else replacing).

| Parameter | Type | Meaning |
|---|---|---|
| `row` | `number` |  |
| `col` | `number` |  |
| `[mods]` | `{ctrl: boolean}` |  |

Returns `void`.

##### `SelectionModel.extendRange(row, col)`

Stretches the last range from its anchor to a cell (a drag, or Shift+click, or Shift+arrows).

| Parameter | Type | Meaning |
|---|---|---|
| `row` | `number` |  |
| `col` | `number` |  |

Returns `void`.

##### `SelectionModel.clearRanges()`

Forgets every range.

Returns `void`.

##### `SelectionModel.inRange(row, col)`

Whether a cell is in a range.

| Parameter | Type | Meaning |
|---|---|---|
| `row` | `number` |  |
| `col` | `number` |  |

Returns `boolean`.

#### `rangeStats(ranges, valueAt, columnAt)`

The figures of the selected cells for the status line: how many cells, how many numbers, and their sum, average, min and max. When a number comes from a non-additive column (VaR, ES, a ratio) the sum and average are not given (null) and `warning` says why, in words.

| Parameter | Type | Meaning |
|---|---|---|
| `ranges` | `CellRange[]` |  |
| `valueAt` | `function(number, number): *` | the raw value at (display row, shown column) |
| `columnAt` | `function(number): {header: string, additive: boolean}` | the shown column at an index |

Returns `{cells: number, count: number, sum: ?number, avg: ?number, min: ?number, max: ?number, nonAdditive: string[], warning: ?string}`.

#### `toTsv(grid)`

Cells as TSV, Excel's paste format: rows by line, cells by tab; a cell with a tab, a line break or a quote is quoted ("" for a quote).

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `Array&lt;Array&lt;*&gt;&gt;` | rows of cells (already the text to copy) |

Returns `string`.

#### `rangeCells(ranges, textAt, o)`

The cells of the ranges as rows of text (each range's rows under the previous), for a copy.

| Parameter | Type | Meaning |
|---|---|---|
| `ranges` | `CellRange[]` |  |
| `textAt` | `function(number, number): string` | the copied text at (display row, shown column): raw by default for numbers |
| `[o]` | `{headers: function(number): string}` | headers: a first line of the columns' headings |

Returns `Array&lt;Array&lt;string&gt;&gt;`.

### drishti_export.js

#### `crc32(b)`

CRC-32 of bytes (the zip's checksum).

| Parameter | Type | Meaning |
|---|---|---|
| `b` | `Uint8Array` |  |

Returns `number`.

#### `zipStore(files)`

A zip archive of files, stored (no compression).

| Parameter | Type | Meaning |
|---|---|---|
| `files` | `Array&lt;{name: string, data: (Uint8Array\|string)}&gt;` |  |

Returns `Uint8Array`.

#### `excelFormat(c)`

A column's Excel number format: from its named format (decimals, grouping, a sign always, a percentage), or its options.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `{type: string, format: *}` |  |

Returns `?string`: null: General.

#### `toXlsx(t, o)`

The table as an Excel workbook (.xlsx bytes).

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `ExportTable` |  |
| `[o]` | `{sheetName: string, title: string}` | sheetName default "Data" |

Returns `Uint8Array`.

```js
const bytes = toXlsx(table); new Blob([bytes], {type: XLSX_TYPE})
```

#### `XLSX_TYPE`

The media type of an .xlsx file.

### drishti_keyboard.js

#### class `GridNavigator`

The focused cell and the keys' meaning.

##### `new GridNavigator(grid)`

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `{rowCount: function(): number, columnCount: function(): number, pageSize: function(): number, isGroup: function(number): boolean, isExpanded: function(number): boolean, explain: boolean}` |  |

##### `GridNavigator.setFocus(row, col)`

Puts the focus on a cell (clamped), or nowhere (null).

| Parameter | Type | Meaning |
|---|---|---|
| `row` | `?number` |  |
| `[col]` | `number` |  |

Returns `?{row: number, col: number}`.

##### `GridNavigator.handleKey(e)`

What a key does.

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `{key: string, ctrlKey: boolean, metaKey: boolean, shiftKey: boolean, altKey: boolean}` |  |

Returns `KeyResult`.

##### `static GridNavigator.help()`

The keys as words for a help line ("Arrows: move · Shift+Arrows: select a range · ..."), the same in every layout.

Returns `Array&lt;{keys: string, does: string}&gt;`.

#### `keyLabel(e)`

The name of a key press as the help line writes it (drishti-core's keyName).

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `KeyboardEvent` |  |

Returns `string`.

### drishti_grid.js

#### class `GridModel`

The grid model.

##### `new GridModel(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `GridOptions` |  |

##### `GridModel.rowCount()`

How many rows are shown (groups and pivot rows included).

Returns `number`.

##### `GridModel.rowAt(i)`

The row at a display index: a leaf or group of the row model, or a pivot entry ({kind: 'pivot', entry}).

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |

Returns `?Object`.

##### `GridModel.shownColumns()`

The shown columns: in grid mode the visible columns (pinned first); in pivot mode the row heading column, then the pivot's result columns (one per column member, total and value).

Returns `Object[]`.

##### `GridModel.cellValue(r, c)`

A cell's raw value (a group's figure; a pivot cell's figure).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` | display row |
| `c` | `number` | shown column |

Returns `*`.

##### `GridModel.cellText(r, c)`

A cell's text as shown (formatted; a group's first cell its member and count; a pending figure "…").

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `string`.

##### `GridModel.setSortModel(model)`

Sorts by a sort model.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `Array&lt;{colId: string, sort: string}&gt;` |  |

Returns `void`.

##### `GridModel.toggleSort(colId, multi)`

A header click: none, ascending, descending (Shift: adds to the sort).

| Parameter | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `[multi]` | `boolean` |  |

Returns `Array&lt;{colId: string, sort: string}&gt;`: the new model.

##### `GridModel.setFilterModel(model)`

Filters by a filter model.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `Object&lt;string, Object&gt;` |  |

Returns `void`.

##### `GridModel.setQuickFilter(q)`

Filters by words across the shown columns.

| Parameter | Type | Meaning |
|---|---|---|
| `q` | `string` |  |

Returns `void`.

##### `GridModel.followFilters(bus, o)`

Follows a filter bar (drishti-inputs) or any page part that emits the filter model on a bus under FILTER_CHANNEL: each payload's filters on this grid's columns (by id or field) become its filter model, and its quick filter the grid's; filters on fields this grid has not are left out.

| Parameter | Type | Meaning |
|---|---|---|
| `bus` | `EventBus` | drishti-core's bus (the page's shared bus, or the filter bar's) |
| `[o]` | `{channel: string, source: string}` | channel default FILTER_CHANNEL; source: follow only the bar of this id |

Returns `function(): void`: stops following.

##### `GridModel.setRowGroups(colIds)`

Groups rows (grid mode).

| Parameter | Type | Meaning |
|---|---|---|
| `colIds` | `string[]` |  |

Returns `void`.

##### `GridModel.toggleRow(r, open)`

Opens or closes the group at a display row (a row group or a pivot row).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `[open]` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModel.idAt(r)`

The id of a display row: a leaf's id, a pivot row's (pivot:&lt;kind&gt;:&lt;key&gt;, so the rows of a pivot may be selected too); null for a group, a pivot's heading line or a placeholder.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |

Returns `?string`.

##### `GridModel.key(e)`

A key press on the grid: moves the focus, changes the selection and runs the action the layout needs no part in; returns what the layout does (copy text to the clipboard, start an edit, show an Explain).

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `KeyboardEvent` |  |

Returns `{handled: boolean, action: ?string, focus: ?Object, copy: ?string}`.

##### `GridModel.selectAll()`

Selects every row that passes the filters (client-side), or clears.

Returns `void`.

##### `GridModel.copy(o)`

The selected cells as TSV (the ranges, else the focused cell): numbers raw, so Excel reads them as numbers.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{headers: boolean}` |  |

Returns `string`.

##### `GridModel.rangeStats()`

The figures of the selected range for the status line (count, sum, average, min, max; a non-additive column warns instead of summing).

Returns `Object`.

##### `GridModel.cellContext(r, c)`

A cell's context for Explain: the row (or a pivot cell's coordinates), the column or measure, the value, and whether the figure is additive.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `?Object`.

##### `GridModel.explain(r, c)`

Explains a cell through the grid's Explain hook (rejects when the grid has none).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `Promise&lt;*&gt;`.

##### `GridModel.on(name, fn)`

Listens to a typed event (GRID_EVENTS) or to changed.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `fn` | `function(Object, string): void` |  |

Returns `function(): void`: stops listening.

##### `GridModel.setEventTarget(el)`

The element the grid's events are dispatched on as DOM CustomEvents (drishti:cell-clicked ...): the web component's host.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `?EventTarget` |  |

Returns `void`.

##### `GridModel.cellClicked(r, c, mods)`

A click on a cell (a layout calls it): focus, the range, the row's selection, and cellClicked.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |
| `[mods]` | `{ctrl: boolean, shift: boolean}` |  |

Returns `void`.

##### `GridModel.cellDoubleClicked(r, c)`

A double click on a cell: cellDoubleClicked (a layout starts an edit or opens a group).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `void`.

##### `GridModel.setCellValue(r, c, value)`

An edit: a leaf's value of an editable column set, validated, as an edit transaction (rows updated by id, cellValueChanged and editTransaction told, kept for undo); the write-back hook, when there is one, hears it.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |
| `value` | `*` |  |

Returns `boolean`: whether the value was set (false: the cell may not be edited, or the value is not valid).

##### `GridModel.applyTransaction(tx)`

Adds, updates and removes rows by id now (rows in the page); rowDataUpdated tells what changed.

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: string[]}` |  |

Returns `{added: number, updated: number, removed: number, changes: Array}`.

##### `GridModel.applyTransactionAsync(tx)`

Queues a transaction with the others of the next few milliseconds (a live feed's thousands of updates a second, one refresh).

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: string[]}` |  |

Returns `Promise&lt;Object&gt;`: the batch's result.

##### `GridModel.flushTransactions()`

Applies the queued transactions now.

Returns `?Object`.

##### `GridModel.flashOf(rowId, colId)`

A cell's flash after a live change: up, down or null.

| Parameter | Type | Meaning |
|---|---|---|
| `rowId` | `string` |  |
| `colId` | `string` |  |

Returns `?string`.

##### `GridModel.connectLive(feed)`

Connects a live feed (drishti_live.js: Server-Sent Events, a WebSocket, or drishti-data's live sources).

| Parameter | Type | Meaning |
|---|---|---|
| `feed` | `{subscribe: function(function(Object): void): function(): void}` |  |

Returns `function(): void`: disconnects.

#### `createGridModel(o)`

A new grid model.

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `GridOptions` |  |

Returns `GridModel`.

```js
const g = createGridModel({columns: [{field: 'DESK'}, {field: 'PNL', type: 'number', format: 'amount0'}], rows: data});
```

### drishti_grid-model-base.js

#### `LAYOUTS`

The layouts a grid is drawn in (the visual shell; the model is the same).

#### `DENSITIES`

The densities, with their row heights in px.

#### `placeGrandRow(answer, where)`

Puts a pivot answer's grand total row where a view asks (its pivotOptions' grandTotalRow): 'top', 'bottom', or 'none' (left out); anything else keeps the answer's own place (with the subtotals: below them at the bottom, above them at the top).

| Parameter | Type | Meaning |
|---|---|---|
| `answer` | `?Object` | a pivot answer (its rows changed in place) |
| `[where]` | `('top'\|'bottom'\|'none')` |  |

Returns `void`.

#### class `GridModelBase`

The pivot, the export and the kept state of a grid model; GridModel adds the rest (rows, columns, selection, keys, editing).

##### `GridModelBase.setPivotMode(on)`

Turns pivot mode on or off; on, the wells' view is computed (or asked).

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModelBase.setPivotOptions(extra)`

Sets the view's other parts (sort per level, top N, subtotals above or below, value filters, source, provider options).

| Parameter | Type | Meaning |
|---|---|---|
| `extra` | `Object` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModelBase.pivotView()`

The pivot view now: the wells, the grid's member filters, the open state and the options.

Returns `Object`.

##### `GridModelBase.runPivot()`

Computes the pivot: in the browser for client-side rows (the non-additive measures then asked of the server, when serverPivot is given), else asked of the server. A value filter is applied either way.

Returns `Promise&lt;void&gt;`.

##### `GridModelBase.showAnswer(answer, o)`

Shows a pivot answer the host fetched itself (a progressive answer's first, approximate one; a live patch; an answer a page holds), in the server's PivotAnswer shape. A request in flight is left alone when `o.keepAsking` (a progressive answer's exact one follows). With `o.patched` only figures moved: they flash, the open state and the columns stay.

| Parameter | Type | Meaning |
|---|---|---|
| `answer` | `Object` |  |
| `[o]` | `{patched: boolean, keepAsking: boolean}` |  |

Returns `void`.

##### `GridModelBase.setOpenState(view)`

Sets the pivot's open state from a view (its expanded nodes and expandTo), without computing: a saved view given back.

| Parameter | Type | Meaning |
|---|---|---|
| `view` | `{expanded: Array, expandTo: Object}` |  |

Returns `void`.

##### `GridModelBase.togglePivotNode(axis, path, open)`

Opens or closes a pivot row or column group, and computes again.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `path` | `Array&lt;Array&lt;string&gt;&gt;` | the node's (dimension, value) pairs |
| `[open]` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModelBase.expandPivotTo(axis, level)`

Opens every pivot node of an axis to a level.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `level` | `number` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModelBase.dataChanged(notice)`

A server's "data changed" notice: server-side rows are asked again (all, a level, blocks, or the blocks holding some rows); a server pivot is computed again.

| Parameter | Type | Meaning |
|---|---|---|
| `notice` | `{purge: boolean, keys: string[], blocks: number[], rows: string[]}` |  |

Returns `string`: what was done.

##### `GridModelBase.applyPivotUpdates(pairs)`

Changes the local pivot's figures for changed rows by difference (false: compute it again).

| Parameter | Type | Meaning |
|---|---|---|
| `pairs` | `Array&lt;{old: Object, value: Object}&gt;` |  |

Returns `boolean`.

##### `GridModelBase.refreshServerMeasures()`

Asks the server again for the pivot's non-additive measures (every cell), and puts the figures in.

Returns `Promise&lt;number&gt;`: how many figures came in.

##### `GridModelBase.toTable()`

The grid as shown, as a table to export: shown columns; in grid mode every filtered row (or the groups and their rows), in pivot mode the pivot's rows, with outline levels.

Returns `import('./drishti_export.js').ExportTable`.

##### `GridModelBase.exportCsv(o)`

CSV text of the grid as shown.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{formatted: boolean}` |  |

Returns `string`.

##### `GridModelBase.exportXlsx(o)`

An Excel workbook of the grid as shown.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{sheetName: string}` |  |

Returns `Uint8Array`.

##### `GridModelBase.setDensity(d)`

Sets the density (the row height follows).

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `('comfortable'\|'compact'\|'terminal')` |  |

Returns `void`.

##### `GridModelBase.setLayout(l)`

Sets the layout's name (the shell draws it; nothing in the model changes).

| Parameter | Type | Meaning |
|---|---|---|
| `l` | `('wells'\|'panel'\|'terminal')` |  |

Returns `void`.

##### `GridModelBase.getState()`

Everything a person set, to keep with a view or a link: columns, sort, filters, wells, pivot mode and open state, layout, density.

Returns `Object`.

##### `GridModelBase.setState(s)`

Gives back a kept state.

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `Object` |  |

Returns `Promise&lt;void&gt;`.

### drishti_grid-live.js

#### `GRID_EVENTS`

Every event a grid emits, by name.

#### `domEventName(name)`

The DOM name of a grid event: "drishti:" and the name in kebab case (cellClicked: drishti:cell-clicked).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `string`.

#### class `GridLive`

The live side of a grid model: its events, transactions, batching, flashes, the feed and the server's totals.

##### `new GridLive(grid, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `Object` | the GridModel |
| `[o]` | `{asyncWaitMs: number, flashMs: number, serverDebounceMs: number, now: function(): number}` | asyncWaitMs default 50; flashMs default 1000 (how long a change is a flash); serverDebounceMs default 250 (updates gathered before the server is asked again) |

##### `GridLive.emit(name, detail)`

Emits a typed event on the bus and, when there is one, on the grid's element.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | one of GRID_EVENTS |
| `[detail]` | `Object` |  |

Returns `void`.

##### `GridLive.apply(tx)`

Applies a transaction now and tells everyone (rowDataUpdated, with every changed cell).

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: string[]}` |  |

Returns `{added: number, updated: number, removed: number, changes: RowChange[]}`.

##### `GridLive.applyAsync(tx)`

Queues a transaction to apply with the others that arrive within asyncWaitMs (one refresh for many updates; a row's last update wins; adds and removes keep their order).

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: string[]}` |  |

Returns `Promise&lt;Object&gt;`: the batch's result, when applied.

##### `GridLive.flush()`

Applies the queued transactions now as one.

Returns `?Object`: the batch's result (null when nothing was queued).

##### `GridLive.flashOf(rowId, colId)`

A cell's flash: up or down while the change is younger than flashMs, else null.

| Parameter | Type | Meaning |
|---|---|---|
| `rowId` | `string` |  |
| `colId` | `string` |  |

Returns `?string`.

##### `GridLive.askServer()`

Asks the server now for the non-additive figures that went stale (group totals through serverGroupTotals; a pivot's through serverPivot).

Returns `Promise&lt;void&gt;`.

##### `GridLive.connect(feed)`

Connects a live feed (drishti_live.js): transactions are batched into the rows in the page, dataChanged notices refresh server-side rows, a rows message replaces every row.

| Parameter | Type | Meaning |
|---|---|---|
| `feed` | `{subscribe: function(function(Object): void): function(): void}` |  |

Returns `function(): void`: disconnects.

### drishti_grid-edit.js

#### `EDITORS`

The editors a column may name; a column without one gets its type's.

#### `editorOf(col)`

A column's editor: its editor, else checkbox for a boolean, date for a date, number for a number, select when it has options, text.

| Parameter | Type | Meaning |
|---|---|---|
| `col` | `Object` | a normalized column |

Returns `string`.

#### `parseCellText(col, text, o)`

Text read as a column's value: {value} or {error} in words.

| Parameter | Type | Meaning |
|---|---|---|
| `col` | `Object` |  |
| `text` | `string` |  |
| `[o]` | `{locale: string}` |  |

Returns `{value: *, error: ?string}`.

#### `validateCell(col, v, row)`

A value's problem in words for a column, or null: required, min, max, maxLength, options, and the column's validate(value, row) (which answers a message, true or null for fine, false for "is not valid").

| Parameter | Type | Meaning |
|---|---|---|
| `col` | `Object` |  |
| `v` | `*` |  |
| `row` | `Object` | the row as it would be |

Returns `?string`.

#### `parseTsv(text)`

Tab-separated text (what Excel and the grid copy) as rows of cells: quoted cells may hold tabs, new lines and doubled quotes; a final line break is not an empty row.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `string[][]`.

#### class `GridEditing`

The editing of a grid model: what may change, transactions, undo and redo, paste, write-back and four eyes.

##### `new GridEditing(grid, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `Object` | the GridModel |
| `[o]` | `{writeBack: function(EditTransaction): Promise&lt;{status: string, proposalId: string, message: string}&gt;, fourEyes: boolean, locale: string, undoLimit: number}` | writeBack: the server hears each transaction (applied, refused, or proposed); fourEyes: every transaction is a proposal until resolveProposal; undoLimit default 100 |

##### `GridEditing.canEdit(r, c)`

Whether a shown cell may be edited.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `boolean`.

##### `GridEditing.markOf(rowId, colId)`

A cell's mark: edited (changed here), proposed (waiting for a second person), or null.

| Parameter | Type | Meaning |
|---|---|---|
| `rowId` | `string` |  |
| `colId` | `string` |  |

Returns `?string`.

##### `GridEditing.plan(cells)`

The edits of shown cells as a transaction's cells, validated: {edits, problems}.

| Parameter | Type | Meaning |
|---|---|---|
| `cells` | `Array&lt;{r: number, c: number, value: *}&gt;` |  |

Returns `{edits: CellEdit[], problems: Array&lt;{r: number, c: number, message: string}&gt;}`.

##### `GridEditing.commit(cells, source)`

Applies cell edits as one transaction (validated first: any problem refuses them all), tells the write-back hook.

| Parameter | Type | Meaning |
|---|---|---|
| `cells` | `Array&lt;{r: number, c: number, value: *}&gt;` |  |
| `[source='edit']` | `string` |  |

Returns `Promise&lt;EditTransaction&gt;`: rejects with .problems when a cell is not valid, or the server's refusal.

##### `GridEditing.apply(edits, source)`

Applies prepared edits (by row and column id) as one transaction.

| Parameter | Type | Meaning |
|---|---|---|
| `edits` | `CellEdit[]` |  |
| `source` | `string` |  |

Returns `Promise&lt;EditTransaction&gt;`.

##### `GridEditing.undo()`

Takes the last transaction back (a new transaction, source undo).

Returns `Promise&lt;?EditTransaction&gt;`.

##### `GridEditing.redo()`

Does the last undone transaction again.

Returns `Promise&lt;?EditTransaction&gt;`.

##### `GridEditing.history()`

Whether there is something to undo and to redo.

Returns `{undo: number, redo: number}`.

##### `GridEditing.resolveProposal(proposalId, decision)`

A four-eyes proposal decided: approved keeps the values (marked edited), rejected puts the old ones back.

| Parameter | Type | Meaning |
|---|---|---|
| `proposalId` | `string` |  |
| `decision` | `('approved'\|'rejected')` |  |

Returns `boolean`: whether there was such a proposal.

##### `GridEditing.paste(text, at)`

Pasted text (tab-separated) from a cell down and right; one value over a larger range fills it. Cells that cannot be edited are skipped; values that do not read or are not valid are left out and listed. One transaction.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `at` | `{r: number, c: number, r2: number, c2: number}` | the focus or the range (r2, c2: its far corner) |

Returns `Promise&lt;{transaction: ?EditTransaction, pasted: number, skipped: number, problems: Array&lt;{r: number, c: number, message: string}&gt;}&gt;`.

##### `GridEditing.clear(range)`

Empties the editable cells of a range (Delete); a required cell is left as it is and listed.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | `{r1: number, c1: number, r2: number, c2: number}` |  |

Returns `Promise&lt;{transaction: ?EditTransaction, problems: Array}&gt;`.

### drishti_grid-editors.js

#### class `CellEditor`

The floating cell editor of a drawn grid.

##### `new CellEditor(view)`

| Parameter | Type | Meaning |
|---|---|---|
| `view` | `Object` | the Grid (drishti_grid-view.js) |

##### `CellEditor.isActive()`

Whether a cell is being edited.

Returns `boolean`.

##### `CellEditor.start(r, c, o)`

Starts editing a cell (when it may be edited); a checkbox turns at once.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |
| `[o]` | `{initial: string}` | initial: a typed character that replaces the value |

Returns `boolean`: whether editing started (or the checkbox turned).

##### `CellEditor.place()`

Puts the editor over its cell again (after the rows were drawn or scrolled); hidden while the cell is out of view.

Returns `void`.

##### `CellEditor.commit(move, leaving)`

Saves the editor's value (validated; a problem keeps the editor open) and moves the focus.

| Parameter | Type | Meaning |
|---|---|---|
| `move` | `?{dr: number, dc: number}` | where the focus goes after (null: it stays) |
| `[leaving]` | `boolean` | the focus left: a value that is not valid is given up, not kept open |

Returns `Promise&lt;boolean&gt;`: whether it was saved.

##### `CellEditor.cancel()`

Leaves the cell as it was.

Returns `void`.

### drishti_live.js

#### `manualFeed()`

A feed a page pushes by hand (tests, a page that already has its own connection): push(message) sends one.

Returns `LiveFeed & {push: function(LiveMessage): void}`.

### drishti_plain-render.js

#### `renderWindow(grid)`

The window the viewport draws: its rows and columns and every cell's text.

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `import('./drishti_grid.js').GridModel` |  |

Returns `{first: number, last: number, columns: Object[], cells: string[][], offset: number}`.

#### `renderText(grid)`

The window as text, a line per row, cells by " \| ", headings first.

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `import('./drishti_grid.js').GridModel` |  |

Returns `string`.

#### `renderHtml(grid)`

The window as plain table markup (escaped), with the grid roles a screen reader needs.

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `import('./drishti_grid.js').GridModel` |  |

Returns `string`.

### drishti_doc-table.js

#### `docTable(doc, d, hooks)`

A table panel's table (the macro table): plain, or a tree when the data says expand (rows with children, open to that depth).

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `d` | `Object` | the panel's data: columns, numeric, rows ({cells, highlight, path, pick, ev, children}), total, more, expand, search |
| `[hooks]` | `DocTableHooks` |  |

Returns `HTMLElement`: div.tbl-wrap holding the table.

#### `docPivotable(doc, panel, hooks)`

A table that offers Pivot (the macro pivotable): the table, and under it the link to the pivot screen on the panel's rows when the data names a pivot source.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `panel` | `{id: string, title: string, data: Object}` |  |
| `[hooks]` | `DocTableHooks` |  |

Returns `(HTMLElement\|HTMLElement[])`: the table, or the table and the Pivot link.

#### `docDrill(doc, panel, hooks)`

A table whose data has levels (the macro drill): one level shows at a time, the breadcrumb above it, a row opens the rows under it. The behaviour is enhanceDocTables'.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `panel` | `{id: string, title: string, data: Object}` | data: columns, numeric, rows (with children and crumb), levels ({title, columns, numeric} per level under the first), total, more, root, search |
| `[hooks]` | `DocTableHooks` |  |

Returns `HTMLElement`: div.drill.

#### `heatBucket(v, lo, hi)`

The heat bucket 0..9 of a figure between the smallest and the largest (5 when the scale is flat), rounded half up as the server rounds.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `number` |  |
| `lo` | `number` |  |
| `hi` | `number` |  |

Returns `number`.

#### `docPivot(doc, d, hooks)`

The pivot kind's table (the macros pivot and pivot_table): rows by one field, or nested by several (the plain table, which a host's pivot grid may redraw from data-pv-tree), with heat buckets, row totals and the totals row.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `d` | `Object` | the panel's data: by, columns, rows ({label, path, group, cells, values, total}), heat, min, max, totals, more, levels |
| `[hooks]` | `DocTableHooks` |  |

Returns `HTMLElement`.

#### `docPlainTable(doc, headRow, rows, o)`

The plain Data table a chart keeps (nothing is only in the picture): in a details element whose summary is "Data", a table that is never sorted (data-plain). A heading is a string (numeric after the first) or {text, num}; a row's first cell is its row heading (o.rowHeader draws it, e.g. an entity link), every other cell a figure (td.mono.num) or {content, cls} for a cell of another class (cls null: none). A row heading that is a Node is drawn as it is.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `headRow` | `Array&lt;(string\|{text: string, num: boolean})&gt;` |  |
| `rows` | `Array&lt;Array&lt;*&gt;&gt;` |  |
| `[o]` | `{summary: string, details: boolean, rowHeader: function(*, number): (Node\|string\|Array)}` | details false: the div.tbl-wrap alone, without the details and its summary |

Returns `HTMLElement`: details.xchart-data (or div.tbl-wrap).

### drishti_doc-behave.js

#### `treeVisibility(depths, opens, matches)`

What a tree table shows: per row, whether it is visible and whether it is drawn open. With a query (matches given) a row shows when it or a row under it matches, and a row is open when a row under it matches; without one, a row shows when its parent is shown and open.

| Parameter | Type | Meaning |
|---|---|---|
| `depths` | `number[]` | each row's depth, rows in document order (a row's children follow it) |
| `opens` | `boolean[]` | whether each row is open (by the person) |
| `matches` | `?boolean[]` | whether each row matches the query, or null when there is no query |

Returns `{visible: boolean[], open: boolean[]}`.

#### `pageInfo(total, page, size)`

A pager's state and words: the page kept inside the pages, the rows it shows, "1–25 of 60" or "No rows match".

| Parameter | Type | Meaning |
|---|---|---|
| `total` | `number` | the rows that pass the filter |
| `page` | `number` | the page asked for (0 first) |
| `size` | `number` | rows a page |

Returns `{page: number, pages: number, first: number, last: number, text: string}`: first and last are row indexes (last exclusive).

#### `enhanceDocTables(scope, o)`

Enhances the document tables under a scope (a whole view, or one panel drawn again): tree tables, drills and, with tables on, sorting, filtering and paging on every plain table (not a tree, a drill's table, a pivot or a Data table). Each element once.

| Parameter | Type | Meaning |
|---|---|---|
| `scope` | `(Element\|DocumentFragment\|ShadowRoot)` |  |
| `[o]` | `{tables: boolean, sort: boolean, pageSize: number}` | tables default true (false: no sorting, filter boxes or pages at all); sort default true (false: plain tables are left as drawn, no heading buttons, filter box or pager, for a host whose own table tools take them; trees keep their filter box and drills work); pageSize default 25 |

Returns `void`.

### drishti_doc-console.js

#### `consoleTables(root, options)`

The console's table behaviours on every table.tbl under a root (the document or a ShadowRoot), now and as panels are swapped in.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `(Document\|ShadowRoot)` |  |
| `[options]` | `{scope: string, chart: boolean, openChart: function(HTMLTableElement, {visible: function(): HTMLTableRowElement[], filtered: boolean}, HTMLElement): void, columnFilters: Object, storageKey: string}` | scope: the page for remembered state (default the path); chart: offer Chart any table inside a shadow root too; openChart: opens a table as a chart (none: no chart button); columnFilters: the funnels ({attach, say, close}); storageKey: where the rows per page are remembered |

Returns `{scan: function(Node): void, dispose: function(): void}`.

### drishti_grid-ui.js

#### `VIEW_EVENTS`

The view's own events (the engine's are GRID_EVENTS): dispatched on the element as drishti:&lt;kebab-name&gt; and on the grid's bus.

#### `PHONE_WIDTH`

Below this width (px) the grid is drawn for a phone (ELEMENTS.md O2).

#### `FUNCTION_KEYS`

The terminal layout's function keys, each an action of the view.

#### `CF_TYPES`

The conditional formats a column may take.

#### `headerSpans(labels)`

The heading rows of columns given their labels by level: consecutive columns with the same labels down to a level share one span at that level (a member over its children, the value's name at the foot).

| Parameter | Type | Meaning |
|---|---|---|
| `labels` | `Array&lt;string[]&gt;` | per column, its label at each level (all the same length) |

Returns `Array&lt;HeaderSpan[]&gt;`: per level, the spans in order.

#### `pivotColumnLabels(col, entry, levels)`

A pivot result column's labels by level: its members from the top (a collapsed group "Total" below its name, an open group's total "Total" under the group), "Grand total" at the top for the grand total, the value's name at the foot.

| Parameter | Type | Meaning |
|---|---|---|
| `col` | `{kind: string, colKey: string, measure: string}` | a shown pivot column |
| `entry` | `?Object` | its column entry of the answer (path, kind, depth) |
| `levels` | `number` | how many column dimensions the pivot has |

Returns `string[]`: levels + 1 labels.

#### `markGroups(spans, entries)`

Marks the spans that open and close a pivot column group: the span at a level whose members name a group that has children.

| Parameter | Type | Meaning |
|---|---|---|
| `spans` | `Array&lt;HeaderSpan[]&gt;` | headerSpans of the pivot columns |
| `entries` | `Array&lt;?Object&gt;` | per column, its column entry |

Returns `Array&lt;HeaderSpan[]&gt;`: the same spans, with group set where collapsible.

#### `isTotalCell(rowKind, colKind, expandable)`

Whether a figure is a total: a pivot row that is a grand total, a subtotal or a group's own line, a total column, a row group.

| Parameter | Type | Meaning |
|---|---|---|
| `rowKind` | `string` | the row's kind (leaf, group, pivot) or the pivot entry's |
| `colKind` | `?string` | the pivot column's kind (node, total, grand, others) or null |
| `[expandable]` | `boolean` | a pivot row with children |

Returns `boolean`.

#### `nonAdditiveNote(header, text)`

The hover note of a non-additive total: the figure is the server's own for the whole group, never the sum of its parts.

| Parameter | Type | Meaning |
|---|---|---|
| `header` | `string` | the measure's name |
| `text` | `string` | the figure as shown |

Returns `string`.

#### `colourScaleAlpha(v, min, max)`

How strong a colour-scale cell is: 0.08 at the least figure, 0.58 at the most (a line of one figure: the middle).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `number` |  |
| `min` | `number` |  |
| `max` | `number` |  |

Returns `number`: the share of the colour, 0 to 1.

#### `dataBarWidth(v, maxAbs)`

A data bar's length: the figure's share of the largest magnitude, as a percentage of the cell (at most 92, so the figure stays readable).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `number` |  |
| `maxAbs` | `number` |  |

Returns `number`: 0 to 92.

#### `cfBackground(rule, v, range)`

The background of a conditionally formatted cell (a CSS background-image; the colours are the theme's tokens), or null.

| Parameter | Type | Meaning |
|---|---|---|
| `rule` | `ConditionalFormat` |  |
| `v` | `*` | the figure |
| `range` | `{min: number, max: number, maxAbs: number}` | the column's figures |

Returns `?string`.

#### `figureRange(values)`

The least, most and largest magnitude of figures (non-numbers left out); null when there are none.

| Parameter | Type | Meaning |
|---|---|---|
| `values` | `Array&lt;*&gt;` |  |

Returns `?{min: number, max: number, maxAbs: number}`.

#### `ruleFor(rules, col)`

The rule that formats a column, if any.

| Parameter | Type | Meaning |
|---|---|---|
| `rules` | `ConditionalFormat[]` |  |
| `col` | `{id: string, measure: string, source: string}` | a shown column (a pivot column: its value's label and source column) |

Returns `?ConditionalFormat`.

#### `phoneColumns(display, pivot, measure)`

The columns a phone shows (ELEMENTS.md O2): a grid keeps every column with the first one pinned (it scrolls across); a pivot collapses to its row headings and one value column, the grand total of the measure picked in the switcher.

| Parameter | Type | Meaning |
|---|---|---|
| `display` | `Array&lt;{col: Object, i: number, pin: ?string}&gt;` | the shown columns with their pinning |
| `pivot` | `boolean` |  |
| `[measure]` | `string` | the measure picked (default: the first) |

Returns `Array&lt;{col: Object, i: number, pin: ?string}&gt;`.

#### `pivotMeasures(cols)`

The measures of pivot columns, in order, once each.

| Parameter | Type | Meaning |
|---|---|---|
| `cols` | `Object[]` | shown pivot columns |

Returns `string[]`.

#### `columnMenuItems(c)`

The column menu's items: sort, filter (members, top N, a value filter), aggregation, hide, autosize, pin, Explain.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `Object` | what the menu is for |
| `c.header` | `string` | the column's heading |
| `c.pivot` | `boolean` | a pivot column |
| `c.sort` | `?string` | its sort now: asc, desc or null |
| `c.numeric` | `boolean` |  |
| `c.additive` | `boolean` |  |
| `[c.aggs]` | `Array&lt;{name: string, label: string}&gt;` | the aggregations it may take |
| `[c.aggFunc]` | `string` | its aggregation now |
| `[c.pinned]` | `?string` | left, right or null |
| `[c.pinTotals]` | `boolean` | the pivot's totals are pinned |
| `[c.explain]` | `boolean` | the grid has an Explain hook |
| `[c.rowHeader]` | `boolean` | the pivot's row heading column |

Returns `MenuItem[]`.

#### `cellMenuItems(c)`

The context menu's items for a cell: Explain, copy, export, chart the range, the rows behind it, pin, group.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `Object` | what the menu is for |
| `c.explain` | `boolean` | the grid has an Explain hook |
| `c.pivot` | `boolean` |  |
| `c.cells` | `number` | cells in the range |
| `c.drill` | `boolean` | there are rows behind the cell to show |
| `c.groupBy` | `?string` | the field rows would be grouped by (null: none) |
| `c.pinned` | `?string` | the column's pinning (grid mode) |
| `c.pinTotals` | `boolean` | the pivot's totals are pinned |

Returns `MenuItem[]`.

#### `commandText(wells)`

The terminal's command line for wells: PIVOT ROWS a b BY c VALUES d:avg FILTER e.

| Parameter | Type | Meaning |
|---|---|---|
| `wells` | `{rows: string[], columns: string[], values: Array&lt;{colId: string, aggFunc: string}&gt;, filters: Array&lt;{colId: string}&gt;}` |  |

Returns `string`.

#### `parseCommand(text, fields)`

Reads a command line back into wells: words name fields by id or heading (case ignored; "quoted words" for a heading with spaces); ROWS (or no keyword), BY (columns), VALUES (field:aggregation), FILTER; PIVOT and GO are skipped.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `fields` | `Array&lt;{id: string, header: string}&gt;` |  |

Returns `{wells: ?Object, error: ?string}`.

#### `parseCondition(text)`

Reads a value filter's condition as a person types it: "&gt; 400k", "&gt;= 1.5m", "&lt; 0", "= 12"; null when it is not one.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `?{op: string, value: number}`.

#### `conditionText(f, fmt)`

A value filter as words: "VaR &gt; 400,000".

| Parameter | Type | Meaning |
|---|---|---|
| `f` | `{by: string, op: string, value: *, valueTo: *}` |  |
| `[fmt]` | `function(*): string` | the figure's format |

Returns `string`.

#### `statusItems(s, fmt)`

The status bar's figures of a range, as words: count, sum, average, min and max (each formatted), or one cell's figure; a warning when a non-additive figure is in it (a sum of VaR is not a VaR).

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `{cells: number, count: number, sum: ?number, avg: ?number, min: ?number, max: ?number, warning: ?string, nonAdditive: string[]}` |  |
| `fmt` | `function(number): string` |  |

Returns `Array&lt;{label: string, value: string, warn: boolean}&gt;`.

#### `cellLook(col, value, row)`

A row cell's own classes and style from its column's cellClass and cellStyle (ColumnDef): class names of letters, digits, - and _ only, and only the safe style properties (colour, background, font weight and style, alignment, decoration; no url(), no expression).

| Parameter | Type | Meaning |
|---|---|---|
| `col` | `Object` | a normalized column |
| `value` | `*` |  |
| `row` | `Object` |  |

Returns `{cls: string, style: Object&lt;string, string&gt;}`.

#### class `FlashTracker`

Remembers the figures drawn and tells which changed after live data came in: up or down, while the change is younger than flashMs.

##### `new FlashTracker(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{flashMs: number, now: function(): number}` | flashMs default 1000 |

##### `FlashTracker.arm()`

Live data came in: the next figures drawn that differ from the last ones flash.

Returns `void`.

##### `FlashTracker.seen(key, value)`

A figure drawn at a key (a row's identity and a column): returns its flash ({dir, age} or null).

| Parameter | Type | Meaning |
|---|---|---|
| `key` | `string` |  |
| `value` | `*` |  |

Returns `?{dir: string, age: number}`.

##### `FlashTracker.settle()`

The drawing is over: changes are no longer expected until arm() again.

Returns `void`.

##### `FlashTracker.active()`

Whether some flash is still running (the view draws again when it ends).

Returns `boolean`.

##### `FlashTracker.reset()`

Forgets every figure (a new pivot, new columns).

Returns `void`.

### drishti_grid-dom.js

#### class `Menu`

A menu: items (drishti_grid-ui.js MenuItem) as role menu, opened at a point or under an element; the arrows move, Enter or Space runs, Escape and a click outside close; focus goes back to where it was.

##### `new Menu(doc, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `[o]` | `{className: string, onClose: function(): void}` | className: the classes of the menu (default dg-menu) |

##### `Menu.isOpen()`

Whether the menu is open.

Returns `boolean`.

##### `Menu.open(items, at)`

Opens the menu.

| Parameter | Type | Meaning |
|---|---|---|
| `items` | `Array&lt;Object&gt;\|function(): Array&lt;Object&gt;` | the items, or a function giving them (asked again after a kept item runs) |
| `at` | `{x: number, y: number, anchor: Element, label: string, run: function(string, *): void, theme: Element}` | where (a point, or under an anchor), its name for a reader, and what runs an item (its id and the field's value) |

Returns `HTMLElement`: the menu.

##### `Menu.close(refocus)`

Closes the menu (focus back where it was when asked).

| Parameter | Type | Meaning |
|---|---|---|
| `[refocus]` | `boolean` |  |

Returns `void`.

#### class `Notes`

The hover note (a tooltip: role tooltip, described by the cell) and the short message at the grid's foot (role status).

##### `new Notes(host, uid)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` | the grid's element (the message goes in it) |
| `uid` | `string` | the grid's id prefix |

##### `Notes.showTip(text, el)`

Shows the note under an element.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `el` | `Element` |  |

Returns `void`.

##### `Notes.hideTip()`

Hides the note.

Returns `void`.

##### `Notes.say(text)`

Says something briefly at the grid's foot.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `void`.

##### `Notes.destroy()`

Takes the note away from the page.

Returns `void`.

### drishti_grid-explain.js

#### `sparkline(doc, values, limit)`

A small line of figures over time, as SVG (the colours are the style sheet's: .dg-spark).

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `values` | `number[]` |  |
| `[limit]` | `number` | a dashed line at the limit |

Returns `SVGElement`.

#### `renderExplain(el, st, mode, o)`

Draws an Explain state into its place: 'drawer' and 'card' one column, 'split' three panes (the explanation, the worst scenarios, the trend).

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` | the place |
| `st` | `?ExplainState` | nothing yet: the hint of how to ask |
| `mode` | `('drawer'\|'card'\|'split')` |  |
| `[o]` | `{onClose: function(): void, explainable: boolean}` | onClose: a close button |

Returns `void`.

### drishti_grid-actions.js

#### `localDrill(v, ctx)`

The rows in the page behind a cell's context (a pivot cell's members, a group's path).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `Object` | the Grid |
| `ctx` | `Object` | GridModel.cellContext |

Returns `Object[]`.

### drishti_grid-base.js

#### class `GridBase`

What a layout asks of its grid: the columns as drawn and the changes a person makes in the chrome.

##### `GridBase.displayColumns()`

The columns as drawn: each shown column (its index in the model's shown columns, its width, its pane), split into the pinned left, the middle (with each one's left edge) and the pinned right; a phone's subset (O2).

Returns `{all: Object[], left: Object[], center: Object[], right: Object[], offs: Float64Array, leftW: number, centerW: number, rightW: number, byIndex: Object, leftOffset: Object, rightOffset: Object}`.

##### `GridBase.emptyText()`

Why nothing is drawn, in words (null when there are rows).

Returns `?string`.

##### `GridBase.grouped()`

Whether rows are grouped (grid mode): the first column then holds the tree.

Returns `boolean`.

##### `GridBase.checkboxes()`

Whether the rows carry checkboxes.

Returns `boolean`.

##### `GridBase.sortOf(d)`

A shown column's sort as drawn: asc, desc or null (a pivot column: its measure's order of the rows).

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `{col: Object}` |  |

Returns `?string`.

##### `GridBase.sourceOf(col)`

A pivot column's source field (the value's column id), for its conditional format.

| Parameter | Type | Meaning |
|---|---|---|
| `col` | `Object` |  |

Returns `?string`.

##### `GridBase.headerOf(id)`

A column's heading by id.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `string`.

##### `GridBase.fmtMeasure(label, v)`

A figure of a measure (a pivot value's label) as its column shows it.

| Parameter | Type | Meaning |
|---|---|---|
| `label` | `string` |  |
| `v` | `*` |  |

Returns `string`.

##### `GridBase.levelFigures(dim, by)`

The middle and the top quarter of a measure's figures at one level of the pivot's rows (for a value filter's presets).

| Parameter | Type | Meaning |
|---|---|---|
| `dim` | `string` | the row field |
| `by` | `string` | the measure's label |

Returns `?{median: number, p75: number}`.

##### `GridBase.membersOf(colId)`

A field's members for its filter: the host's (members option) or the rows in the page.

| Parameter | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |

Returns `Array&lt;{text: string, count: number}&gt;`.

##### `GridBase.activeWells()`

The wells being edited: the live ones, or the draft while updates are deferred (panel layout).

Returns `WellsModel`.

##### `GridBase.wellsChanged(what)`

The wells changed: the pivot is on when there is a value; the chrome is drawn again; the change is said.

| Parameter | Type | Meaning |
|---|---|---|
| `what` | `?string` | the change in words |

Returns `void`.

##### `GridBase.applyDraft()`

Applies the deferred wells (the panel's Update).

Returns `void`.

##### `GridBase.panelSwitch(id, on)`

A switch of the panel layout: pivot mode, the grand total column, subtotals above, deferred update.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` | pivot, grand, above or defer |
| `on` | `boolean` |  |

Returns `void`.

##### `GridBase.setPivotOptions(extra, what)`

Sets more of the pivot's view (sort, top N, subtotals, value filters) and computes it again.

| Parameter | Type | Meaning |
|---|---|---|
| `extra` | `Object` |  |
| `[what]` | `string` | the change in words |

Returns `Promise&lt;void&gt;`.

##### `GridBase.setValueFilters(list)`

Replaces the pivot's value filters.

| Parameter | Type | Meaning |
|---|---|---|
| `list` | `Array&lt;{dim: string, by: string, op: string, value: number}&gt;` |  |

Returns `Promise&lt;void&gt;`.

##### `GridBase.sortColumn(c, multi)`

Sorts by a shown column (a heading's click): grid mode none, ascending, descending (Shift adds); a pivot orders its rows by the measure (largest first, then smallest, then by name).

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `number` | the shown column |
| `[multi]` | `boolean` |  |

Returns `void`.

##### `GridBase.setColumnWidth(d, w)`

Sets a shown column's width (grid mode: the model's column; a pivot column: kept by the view).

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `{col: Object, i: number}` |  |
| `w` | `number` | px |

Returns `void`.

##### `GridBase.autosize(c)`

Sizes a column to its widest text among the heading and the first rows (all columns when c is -1).

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `number` | the shown column, or -1 |

Returns `void`.

##### `GridBase.switchMeasure(label)`

The phone's measure switcher: the one value column shows this measure.

| Parameter | Type | Meaning |
|---|---|---|
| `label` | `string` |  |

Returns `void`.

##### `GridBase.measures()`

The measures a phone may switch between.

Returns `string[]`.

### drishti_grid-view.js

#### class `Grid`

The grid, drawn: a layout around the engine's model, with the plain JavaScript API every host uses (the web component wraps it).

##### `new Grid(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` | the element the grid fills |
| `options` | `GridViewOptions` |  |

##### `Grid.refresh()`

Draws everything again now (the chrome and the rows in view).

Returns `void`.

##### `Grid.renderChrome()`

Draws the chrome again: toolbar, wells, command line, function keys, status bar and the Explain area.

Returns `void`.

##### `Grid.afterFocus(scroll)`

The focus moved (a click or a key): the rows drawn again, the status bar, and Explain following the selection where it is open.

| Parameter | Type | Meaning |
|---|---|---|
| `scroll` | `boolean` | scroll the focus into view |

Returns `void`.

##### `Grid.afterRange()`

A drag across cells ended.

Returns `void`.

##### `Grid.announceRow(r)`

Says a row's new open state to a screen reader.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |

Returns `void`.

##### `Grid.explain(r, c)`

Explains a cell through the Explain hook, in the layout's place for it (drawer, card or split pane).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `Promise&lt;*&gt;`.

##### `Grid.explainFocused()`

Explains the focused cell.

Returns `Promise&lt;*&gt;`.

##### `Grid.closeExplain()`

Closes Explain.

Returns `void`.

##### `Grid.say(text)`

Says something at the grid's foot (a live region).

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `void`.

##### `Grid.openColumnMenu(c, anchor)`

Opens the column menu of a shown column.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `number` |  |
| `anchor` | `Element` |  |

Returns `void`.

##### `Grid.openCellMenu(r, c, at)`

Opens the context menu of a cell.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |
| `at` | `{x: number, y: number}` |  |

Returns `void`.

##### `Grid.runAction(id, arg)`

Runs one of the view's actions by id (a toolbar button, a function key, a menu item): explain, copy, copy-headers, export-csv, export-xlsx, chart, drill, group, pin-totals, expand-all, collapse-all, toggle-pivot, save-view, ...

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `[arg]` | `Object` |  |

Returns `*`.

##### `Grid.startEditing(r, c, o)`

Starts editing a cell (Enter, F2, a double click or typing do it); a checkbox turns at once.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |
| `[o]` | `{initial: string}` | a typed character that replaces the value |

Returns `boolean`: whether editing started.

##### `Grid.stopEditing(cancel)`

Stops editing: saves the value (cancel false) or leaves the cell as it was.

| Parameter | Type | Meaning |
|---|---|---|
| `[cancel]` | `boolean` |  |

Returns `Promise&lt;boolean&gt;`: whether a value was saved.

##### `Grid.undo()`

Takes the last edit transaction back.

Returns `Promise&lt;?Object&gt;`.

##### `Grid.redo()`

Does the last undone edit transaction again.

Returns `Promise&lt;?Object&gt;`.

##### `Grid.pasteText(text)`

Pastes tab-separated text (from Excel) at the focus, or fills the selected range with one value; one transaction.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `Promise&lt;Object&gt;} {transaction, pasted, skipped, problems`.

##### `Grid.resolveProposal(proposalId, decision)`

A four-eyes proposal of an edit was decided: approved keeps the values, rejected puts them back.

| Parameter | Type | Meaning |
|---|---|---|
| `proposalId` | `string` |  |
| `decision` | `('approved'\|'rejected')` |  |

Returns `boolean`.

##### `Grid.followFilters(bus, o)`

Follows a filter bar's filter model on a bus (GridModel.followFilters).

| Parameter | Type | Meaning |
|---|---|---|
| `bus` | `Object` | drishti-core's EventBus |
| `[o]` | `{channel: string, source: string}` |  |

Returns `function(): void`: stops following.

##### `Grid.setLayout(layout)`

Changes the layout: 'wells', 'panel' or 'terminal'.

| Parameter | Type | Meaning |
|---|---|---|
| `layout` | `('wells'\|'panel'\|'terminal')` |  |

Returns `void`.

##### `Grid.setDensity(density)`

Changes the density: 'comfortable', 'compact' or 'terminal'.

| Parameter | Type | Meaning |
|---|---|---|
| `density` | `('comfortable'\|'compact'\|'terminal')` |  |

Returns `void`.

##### `Grid.setPivotMode(on)`

Turns the pivot on or off.

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `Grid.setPivot(wells)`

Sets the wells and turns the pivot on.

| Parameter | Type | Meaning |
|---|---|---|
| `wells` | `{rows: string[], columns: string[], values: Array&lt;{colId: string, aggFunc: string}&gt;, filters: Array}` |  |

Returns `Promise&lt;void&gt;`.

##### `Grid.setConditionalFormats(rules)`

Replaces the conditional formats.

| Parameter | Type | Meaning |
|---|---|---|
| `rules` | `import('./drishti_grid-ui.js').ConditionalFormat[]` |  |

Returns `void`.

##### `Grid.setRows(rows)`

Replaces the rows (client-side).

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |

Returns `void`.

##### `Grid.applyTransaction(tx)`

Adds, updates and removes rows by id now; changed figures flash.

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: string[]}` |  |

Returns `Object`.

##### `Grid.applyTransactionAsync(tx)`

Queues a transaction with the others of the next few milliseconds (a live feed).

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: string[]}` |  |

Returns `Promise&lt;Object&gt;`.

##### `Grid.connectLive(feed)`

Connects a live feed (drishti_live.js).

| Parameter | Type | Meaning |
|---|---|---|
| `feed` | `{subscribe: function(function(Object): void): function(): void}` |  |

Returns `function(): void`: disconnects.

##### `Grid.focusCell(r, c)`

Puts the focus on a cell (and the range there) and scrolls it into view.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |

Returns `void`.

##### `Grid.getState()`

Everything a person set, to keep and give back: the model's state and the view's (pinned totals, hidden columns, widths, formats).

Returns `Object`.

##### `Grid.setState(s)`

Gives back a kept state.

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `Object` |  |

Returns `Promise&lt;void&gt;`.

##### `Grid.on(name, fn)`

Listens to a grid event (GRID_EVENTS) or a view event (VIEW_EVENTS).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `fn` | `function(Object, string): void` |  |

Returns `function(): void`: stops listening.

##### `Grid.emitView(name, detail)`

Emits a view event on the grid's bus and as a DOM event on the element (drishti:&lt;kebab-name&gt;).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | one of VIEW_EVENTS |
| `[detail]` | `Object` |  |

Returns `void`.

##### `Grid.destroy()`

Takes the grid out of its element: listeners, menus, notes.

Returns `void`.

### drishti_grid-element.js

#### `ELEMENT_NAME`

The element's tag name.

#### `defineGridElement(registry, win)`

Defines &lt;drishti-grid&gt; in a registry (once; the bundle does it when it loads in a page).

| Parameter | Type | Meaning |
|---|---|---|
| `[registry]` | `CustomElementRegistry` | default: the window's |
| `[win]` | `Window` | the window whose HTMLElement the element extends (default: the page's) |

Returns `boolean`: whether it was defined now (false: no registry, or defined already).

### drishti_grid-ag-map.js

#### `AG_MAPPING`

The adapter's mapping, one row per group of AG Grid Enterprise options (public API shapes): its state (mapped, partial, unsupported) and its drishti-grid form.

#### `AG_NOT_COVERED`

What version 1 leaves out, in words (ELEMENTS.md section 6, the migration guide).

#### `AG_GRID_OPTIONS`

Grid options the adapter takes (translated or carried): any other name is reported.

#### `AG_COLUMN_KEYS`

Column definition keys the adapter takes.

#### `AG_UNSUPPORTED`

Options the adapter does not cover, each with its drishti-grid way (a warning names both).

#### `AG_NO_OP`

Options that need nothing in drishti-grid (said once, as information).

#### `AG_EVENTS`

The AG Grid events the adapter raises (onXxx options and addEventListener), each from drishti-grid's typed events.

#### `agFilterToDrishti(f, colType, warn)`

One column's AG Grid-shaped filter as drishti-grid's ColumnFilter (text, number, date, set; blank). A filter with two conditions keeps the first; what cannot be kept is said through warn.

| Parameter | Type | Meaning |
|---|---|---|
| `f` | `Object` | the AG-shaped filter ({filterType, type, filter, filterTo, dateFrom, dateTo, values, operator, conditions}) |
| `[colType]` | `string` | the column's type, when the filter does not say its kind |
| `[warn]` | `function(string): void` |  |

Returns `?Object`: the drishti filter, or null when there is none.

#### `drishtiFilterToAg(f)`

One drishti ColumnFilter in AG Grid's shape (what getFilterModel and a server-side request carry).

| Parameter | Type | Meaning |
|---|---|---|
| `f` | `Object` |  |

Returns `?Object`.

#### `translateFilterModel(model, toDrishti, typeOf, warn)`

A whole filter model translated: AG Grid's shape to drishti-grid's (toDrishti true) or back.

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `Object&lt;string, Object&gt;` | column id to a filter |
| `toDrishti` | `boolean` |  |
| `[typeOf]` | `function(string): ?string` | a column's type by id |
| `[warn]` | `function(string): void` |  |

Returns `Object&lt;string, Object&gt;`.

### drishti_grid-ag.js

#### `AG_GROUP_COLUMN`

The adapter's group column (added when rows may be grouped or make a tree): its id.

#### class `AgReport`

The adapter's report: each option said once in the console (a warning, or information for one that needs nothing) and kept.

##### `new AgReport(out)`

| Parameter | Type | Meaning |
|---|---|---|
| `[out]` | `{warn: function(string): void, info: function(string): void}` | default: the console |

##### `AgReport.add(option, state, words)`

Says and keeps one item (once per option and state).

| Parameter | Type | Meaning |
|---|---|---|
| `option` | `string` |  |
| `state` | `('partial'\|'unsupported'\|'no-op'\|'unknown')` |  |
| `words` | `string` |  |

Returns `void`.

#### `agAggFunc(agg, id, ctx)`

An aggregation as the AG option names it, as drishti-grid's: a built-in name, a function or an aggFuncs name (registered), a non-additive server measure ('drishti:server', 'drishti:server:var(99, 1d)'), or another name (the server's measure).

| Parameter | Type | Meaning |
|---|---|---|
| `agg` | `*` | the colDef's aggFunc |
| `id` | `string` | the column's id |
| `ctx` | `Object` | the translation's context (aggFuncs, registered names, the report) |

Returns `{aggFunc: ?string, additive: (boolean\|undefined), measure: (string\|undefined), agName: (string\|undefined)}`.

#### `translateAgOptions(go, o)`

The translation: AG Grid-shaped grid options as drishti-grid's options, the pivot's wells, the start state and the report.

| Parameter | Type | Meaning |
|---|---|---|
| `go` | `Object` | the AG Grid-shaped grid options |
| `[o]` | `{report: AgReport, api: Object}` | report: where options not covered are said; api: the handle (params.api) |

Returns `{options: Object, wells: Object, rowGroups: string[], sort: Array, flex: Object&lt;string, number&gt;, expand: {rows: number, columns: number}, autoHeight: boolean, events: Object&lt;string, function&gt;, aggNames: Object&lt;string, string&gt;, registered: string[], report: AgReport}`.

### drishti_grid-ag-server.js

#### `callGetRows(ds, request, ctx, signal, parentNode)`

Calls an AG-style getRows once and answers a promise: success({rowData, rowCount, pivotResultFields}) or the older successCallback(rows, lastRow) resolve it; fail() or failCallback() reject it. An abort rejects it at once.

| Parameter | Type | Meaning |
|---|---|---|
| `ds` | `{getRows: function(Object): void}` |  |
| `request` | `Object` | the AG-shaped request |
| `ctx` | `AgServerContext` |  |
| `[signal]` | `AbortSignal` |  |
| `[parentNode]` | `Object` |  |

Returns `Promise&lt;{rows: Object[], rowCount: ?number, pivotResultFields: ?string[]}&gt;`.

#### `agRequest(req, ctx)`

The AG-shaped request of a drishti block request (columns as objects, the filter model in AG's shape).

| Parameter | Type | Meaning |
|---|---|---|
| `req` | `import('./drishti_server-rows.js').BlockRequest` |  |
| `ctx` | `AgServerContext` |  |

Returns `Object`.

#### `agDatasource(ds, ctx)`

drishti-grid's Datasource over an application's serverSideDatasource (or an infinite model's datasource).

| Parameter | Type | Meaning |
|---|---|---|
| `ds` | `{getRows: function(Object): void}` |  |
| `ctx` | `AgServerContext` |  |

Returns `import('./drishti_server-rows.js').Datasource & {calls: Object[]}`.

#### `parseResultField(field, valueIds, sep, depth)`

A pivot result field's keys: the field less its value column (and the separator before it), split by the separator.

| Parameter | Type | Meaning |
|---|---|---|
| `field` | `string` | e.g. "EMEA_EUR_MTM" |
| `valueIds` | `string[]` | the value columns' ids |
| `sep` | `string` |  |
| `depth` | `number` | how many pivot columns |

Returns `?{keys: string[], valueId: string}`.

#### `agServerPivot(ds, ctx)`

drishti-grid's serverPivot over an application's serverSideDatasource: the view's top level, each open row node's level and, when asked, the grand total row and the rows' totals, each one getRows call in pivot mode; the answer in the pivot answer's shape.

| Parameter | Type | Meaning |
|---|---|---|
| `ds` | `{getRows: function(Object): void}` |  |
| `ctx` | `AgServerContext` |  |

Returns `function({view: Object}, AbortSignal): Promise&lt;Object&gt;`.

### drishti_grid-ag-events.js

#### class `AgEvents`

The events, nodes, columns and widths of an AgGridApi.

##### `new AgEvents(api)`

| Parameter | Type | Meaning |
|---|---|---|
| `api` | `Object` | the AgGridApi |

##### `AgEvents.start()`

Starts raising events: drishti-grid's own, the wells', pivot mode; then gridReady and, when the first rows are in, firstDataRendered.

Returns `void`.

##### `AgEvents.stop()`

Stops raising events.

Returns `void`.

##### `AgEvents.setHandler(name, fn)`

Sets an onXxx handler (setGridOption).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | the event's name |
| `fn` | `?function(Object): void` |  |

Returns `void`.

##### `AgEvents.add(type, fn)`

Adds a listener of an AG-named event.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `fn` | `function(Object): void` |  |

Returns `void`.

##### `AgEvents.remove(type, fn)`

Removes a listener.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `fn` | `function(Object): void` |  |

Returns `void`.

##### `AgEvents.fire(type, params)`

Raises an AG-named event: its handler (onXxx) and its listeners, given {type, api, context} and the event's own parameters.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `params` | `Object` |  |

Returns `void`.

##### `AgEvents.nodeOf(data, id, x, rowIndex)`

A row node as an application reads it: id, data, group, key, level, expanded, rowIndex, setSelected and isSelected.

| Parameter | Type | Meaning |
|---|---|---|
| `data` | `?Object` |  |
| `id` | `?string` |  |
| `[x]` | `Object` | the display row (a group's key and level) |
| `[rowIndex]` | `number` |  |

Returns `Object`.

##### `AgEvents.nodeAt(i)`

The row node at a display index (a leaf, a group, a pivot row).

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |

Returns `?Object`.

##### `AgEvents.leaves(filtered)`

The leaf nodes: every row of the data, or (filtered) those that pass the filters in display order.

| Parameter | Type | Meaning |
|---|---|---|
| `filtered` | `boolean` |  |

Returns `Object[]`.

##### `AgEvents.columnOf(c)`

A column as an application reads it: {colId, colDef, getColId, isVisible, getActualWidth, pinned}.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `?Object` | a drishti column |

Returns `?Object`.

##### `AgEvents.columnState()`

Each column's state.

Returns `Array&lt;Object&gt;`.

##### `AgEvents.applyColumnState(p)`

Gives back column states (see AgGridApi.applyColumnState).

| Parameter | Type | Meaning |
|---|---|---|
| `p` | `{state: Array&lt;Object&gt;, applyOrder: boolean, defaultState: Object}` |  |

Returns `boolean`: whether every state named a column.

##### `AgEvents.sizeToFit()`

Shares the element's width among the shown columns: by flex where given (a flex column takes its share of what the others leave), else every column in proportion to its width.

Returns `void`.

##### `AgEvents.fitHeight()`

domLayout autoHeight: the element's height follows the rows shown (heading and status bar included), now and as rows change.

Returns `void`.

##### `AgEvents.download(name, data, type)`

Hands the person a file: the host's drishti.download hook, else a link clicked (with an element).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `data` | `(string\|Uint8Array)` |  |
| `type` | `string` |  |

Returns `void`.

### drishti_grid-ag-columns.js

#### class `AgColumnsApi`

The filter, column, grouping and pivot calls of the adapter's handle (it holds go, t, report, model, grid and events).

##### `AgColumnsApi.setFilterModel(model)`

Filters by an AG-shaped filter model (null clears).

| Parameter | Type | Meaning |
|---|---|---|
| `model` | `?Object` |  |

Returns `void`.

##### `AgColumnsApi.getFilterModel()`

The filter model in AG's shape.

Returns `Object`.

##### `AgColumnsApi.isAnyFilterPresent()`

Whether any column is filtered (or the quick filter).

Returns `boolean`.

##### `AgColumnsApi.getColumnState()`

Each column's state: width, hidden, pinned, sort, row group, pivot and aggregation.

Returns `Array&lt;Object&gt;`.

##### `AgColumnsApi.applyColumnState(p)`

Gives back column states (by colId): width, hide, pinned, sort and sortIndex, rowGroup and rowGroupIndex, pivot and pivotIndex, aggFunc; applyOrder moves the columns to the state's order; defaultState applies to the columns not named.

| Parameter | Type | Meaning |
|---|---|---|
| `p` | `{state: Array&lt;Object&gt;, applyOrder: boolean, defaultState: Object}` |  |

Returns `boolean`.

##### `AgColumnsApi.resetColumnState()`

The column state the grid was made with.

Returns `void`.

##### `AgColumnsApi.sizeColumnsToFit()`

Shares the element's width among the shown columns (by flex where given, else by width).

Returns `void`.

##### `AgColumnsApi.autoSizeAllColumns()`

Sizes every shown column to its content.

Returns `void`.

##### `AgColumnsApi.autoSizeColumns(keys)`

Sizes some columns to their content.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` | column ids |

Returns `void`.

##### `AgColumnsApi.setColumnsVisible(keys, visible)`

Shows or hides columns.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |
| `visible` | `boolean` |  |

Returns `void`.

##### `AgColumnsApi.setColumnsPinned(keys, pinned)`

Pins columns left or right, or unpins them.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |
| `pinned` | `?string` |  |

Returns `void`.

##### `AgColumnsApi.setColumnWidths(list)`

Sets columns' widths.

| Parameter | Type | Meaning |
|---|---|---|
| `list` | `Array&lt;{key: string, newWidth: number}&gt;` |  |

Returns `void`.

##### `AgColumnsApi.moveColumns(keys, toIndex)`

Moves columns to a place in the order.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |
| `toIndex` | `number` |  |

Returns `void`.

##### `AgColumnsApi.getColumnDefs()`

The column definitions as the application gave them.

Returns `Object[]`.

##### `AgColumnsApi.getColumns()`

The columns (not the adapter's group column) as {colId, colDef, visible, pinned}.

Returns `Object[]`.

##### `AgColumnsApi.setPivotMode(on)`

Turns pivot mode on or off.

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `AgColumnsApi.isPivotMode()`

Whether the grid is a pivot.

Returns `boolean`.

##### `AgColumnsApi.setRowGroupColumns(keys)`

Sets the row group columns, in order.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |

Returns `void`.

##### `AgColumnsApi.addRowGroupColumns(keys)`

Adds row group columns at the end.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |

Returns `void`.

##### `AgColumnsApi.removeRowGroupColumns(keys)`

Takes columns out of the row groups.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |

Returns `void`.

##### `AgColumnsApi.getRowGroupColumns()`

The row group columns.

Returns `Object[]`.

##### `AgColumnsApi.setPivotColumns(keys)`

Sets the pivot columns, in order.

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `string[]` |  |

Returns `void`.

##### `AgColumnsApi.getPivotColumns()`

The pivot columns.

Returns `Object[]`.

##### `AgColumnsApi.setValueColumns(keys)`

Sets the value columns (ids take their column's aggFunc; {colId, aggFunc} sets it).

| Parameter | Type | Meaning |
|---|---|---|
| `keys` | `Array&lt;(string\|{colId: string, aggFunc: string})&gt;` |  |

Returns `void`.

##### `AgColumnsApi.getValueColumns()`

The value columns.

Returns `Object[]`.

##### `AgColumnsApi.getPivotResultColumns()`

The pivot's result columns: {colId, colDef: {headerName, pivotKeys, pivotValueColumn}}.

Returns `?Object[]`.

##### `AgColumnsApi.expandAll()`

Opens every group (and every pivot column group).

Returns `Promise&lt;void&gt;`.

##### `AgColumnsApi.collapseAll()`

Closes every group.

Returns `Promise&lt;void&gt;`.

##### `AgColumnsApi.refreshServerSide(p)`

Asks the server again: every level (or the route's level and those under it); purge forgets the row counts too. A server pivot is computed again.

| Parameter | Type | Meaning |
|---|---|---|
| `[p]` | `{route: string[], purge: boolean}` |  |

Returns `void`.

##### `AgColumnsApi.purgeServerSideCache()`

Forgets every server-side block and asks again (the older name of refreshServerSide({purge: true})).

Returns `void`.

### drishti_grid-ag-api.js

#### class `AgGridApi`

The handle an application calls as it called AG Grid's api: the common calls, the events, and reportUnsupported().

##### `new AgGridApi(el, gridOptions, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `?HTMLElement` | the element to draw in (null: the model alone) |
| `gridOptions` | `Object` | the AG Grid-shaped options |
| `[o]` | `{out: {warn: function(string): void, info: function(string): void}}` | out: where options not covered are said (default: the console) |

##### `AgGridApi.getGridOption(key)`

An option as the application last set it.

| Parameter | Type | Meaning |
|---|---|---|
| `key` | `string` |  |

Returns `*`.

##### `AgGridApi.setGridOption(key, value)`

Changes one option at run time (rowData, pivotMode, quickFilterText, columnDefs, groupDefaultExpanded, the totals, sideBar, serverSideDatasource, domLayout, context and the onXxx events); any other is reported.

| Parameter | Type | Meaning |
|---|---|---|
| `key` | `string` |  |
| `value` | `*` |  |

Returns `void`.

##### `AgGridApi.updateGridOptions(options)`

Changes several options at once.

| Parameter | Type | Meaning |
|---|---|---|
| `options` | `Object` |  |

Returns `void`.

##### `AgGridApi.setRowData(rows)`

The older name of setGridOption('rowData', rows).

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |

Returns `void`.

##### `AgGridApi.applyTransaction(t)`

Adds, updates and removes rows by id (client-side rows).

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `{add: Object[], update: Object[], remove: Object[]}` |  |

Returns `{add: Object[], update: Object[], remove: Object[]}`: the row nodes.

##### `AgGridApi.applyTransactionAsync(t, callback)`

Queues a transaction with the others of the next few milliseconds (one refresh for a live feed); the callback hears its nodes.

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `{add: Object[], update: Object[], remove: Object[]}` |  |
| `[callback]` | `function(Object): void` |  |

Returns `Promise&lt;Object&gt;`.

##### `AgGridApi.flushAsyncTransactions()`

Applies the queued transactions now.

Returns `void`.

##### `AgGridApi.getSelectedRows()`

The selected rows' data.

Returns `Object[]`.

##### `AgGridApi.getSelectedNodes()`

The selected row nodes.

Returns `Object[]`.

##### `AgGridApi.selectAll()`

Selects every row that passes the filters.

Returns `void`.

##### `AgGridApi.deselectAll()`

Clears the row selection.

Returns `void`.

##### `AgGridApi.forEachNode(fn)`

Calls back with every row node of the data (client-side rows; the loaded rows on the server side).

| Parameter | Type | Meaning |
|---|---|---|
| `fn` | `function(Object, number): void` |  |

Returns `void`.

##### `AgGridApi.forEachNodeAfterFilter(fn)`

Every row node that passes the filters, in the data's order.

| Parameter | Type | Meaning |
|---|---|---|
| `fn` | `function(Object, number): void` |  |

Returns `void`.

##### `AgGridApi.forEachNodeAfterFilterAndSort(fn)`

Every row node that passes the filters, in the sort's order.

| Parameter | Type | Meaning |
|---|---|---|
| `fn` | `function(Object, number): void` |  |

Returns `void`.

##### `AgGridApi.forEachLeafNode(fn)`

Every leaf row node (no groups).

| Parameter | Type | Meaning |
|---|---|---|
| `fn` | `function(Object, number): void` |  |

Returns `void`.

##### `AgGridApi.getDisplayedRowCount()`

How many rows are shown (groups and pivot rows included).

Returns `number`.

##### `AgGridApi.getDisplayedRowAtIndex(i)`

The row node at a display index.

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |

Returns `?Object`.

##### `AgGridApi.getRowNode(id)`

A row node by its id.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `?Object`.

##### `AgGridApi.getDataAsCsv(p)`

The grid as shown as CSV text.

| Parameter | Type | Meaning |
|---|---|---|
| `[p]` | `{columnSeparator: string}` |  |

Returns `string`.

##### `AgGridApi.getDataAsExcel(p)`

The grid as shown as an Excel workbook's bytes.

| Parameter | Type | Meaning |
|---|---|---|
| `[p]` | `{sheetName: string}` |  |

Returns `Uint8Array`.

##### `AgGridApi.exportDataAsCsv(p)`

Hands the person a CSV file of the grid as shown (drishti.download, else a link clicked).

| Parameter | Type | Meaning |
|---|---|---|
| `[p]` | `{fileName: string, columnSeparator: string}` |  |

Returns `void`.

##### `AgGridApi.exportDataAsExcel(p)`

Hands the person an Excel workbook of the grid as shown (values and outline).

| Parameter | Type | Meaning |
|---|---|---|
| `[p]` | `{fileName: string, sheetName: string}` |  |

Returns `void`.

##### `AgGridApi.refreshCells()`

Draws the cells again.

Returns `void`.

##### `AgGridApi.redrawRows()`

Draws the rows again.

Returns `void`.

##### `AgGridApi.ensureIndexVisible(i)`

Scrolls a display row into view.

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |

Returns `void`.

##### `AgGridApi.setFocusedCell(rowIndex, colKey)`

Focuses a cell.

| Parameter | Type | Meaning |
|---|---|---|
| `rowIndex` | `number` |  |
| `colKey` | `string` |  |

Returns `void`.

##### `AgGridApi.getFocusedCell()`

The focused cell, or null.

Returns `?{rowIndex: number, column: Object}`.

##### `AgGridApi.getCellRanges()`

The cell ranges selected.

Returns `Array&lt;Object&gt;`.

##### `AgGridApi.clearCellSelection()`

Clears the cell ranges.

Returns `void`.

##### `AgGridApi.startEditingCell(p)`

Starts editing a cell (with an element).

| Parameter | Type | Meaning |
|---|---|---|
| `p` | `{rowIndex: number, colKey: string}` |  |

Returns `void`.

##### `AgGridApi.stopEditing(cancel)`

Stops editing (cancel: the value is not kept).

| Parameter | Type | Meaning |
|---|---|---|
| `[cancel]` | `boolean` |  |

Returns `void`.

##### `AgGridApi.undoCellEditing()`

Undoes the last edit.

Returns `void`.

##### `AgGridApi.redoCellEditing()`

Redoes the last edit undone.

Returns `void`.

##### `AgGridApi.addEventListener(type, fn)`

Listens to an AG-named event (AG_EVENTS).

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `fn` | `function(Object): void` |  |

Returns `void`.

##### `AgGridApi.removeEventListener(type, fn)`

Stops listening.

| Parameter | Type | Meaning |
|---|---|---|
| `type` | `string` |  |
| `fn` | `function(Object): void` |  |

Returns `void`.

##### `AgGridApi.reportUnsupported()`

Every option the adapter did not take in full: its name, its state (partial, unsupported, no-op, unknown) and drishti-grid's way.

Returns `import('./drishti_grid-ag.js').AgReportItem[]`.

##### `AgGridApi.destroy()`

Takes the grid away (its element emptied, its aggregations forgotten).

Returns `void`.

##### `AgGridApi.isDestroyed()`

Whether destroy() was called.

Returns `boolean`.

#### `createGridFromAgOptions(el, gridOptions, o)`

A drishti-grid from AG Grid Enterprise-shaped grid options: draws it in the element (or, without one, holds the model alone) and answers the handle an application calls as it called AG Grid's api.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `?HTMLElement` |  |
| `gridOptions` | `Object` |  |
| `[o]` | `{out: {warn: function(string): void, info: function(string): void}}` |  |

Returns `AgGridApi`.

```js
const api = createGridFromAgOptions(document.getElementById('grid'), { columnDefs, rowData, pivotMode: true, sideBar: true });
```

### drishti_index.js

#### `install(root)`

Starts drishti-grid in a page (the bundle calls it when it loads; nothing happens without a document): defines &lt;drishti-grid&gt;.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.

### Types of drishti-grid

#### `AggFunc` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `label` | `string` |  |
| `init` | `function(): *` |  |
| `add` | `function(*, *): *` | the state with one more value (returns the state) |
| `merge` | `function(*, *): *` | two states as one |
| `result` | `function(*): *` | the figure |
| `numeric` | `boolean` | whether the values are numbers |

#### `DisplayRow` : `Object`

A row as the grid sees it at a display index.

| Name | Type | Meaning |
|---|---|---|
| `kind` | `('leaf'\|'group')` |  |
| `id` | `string` | the row's id (a group's: "group:" and its path) |
| `depth` | `number` | 0 for a row without groups; a group's level from 0; a leaf under groups: the number of groups |
| `row` | `?Object` | the data (leaves) |
| `[index]` | `number` | the row's number in the data |
| `[field]` | `string` | a group's column |
| `[value]` | `*` | a group's member |
| `[path]` | `string[]` | a group's members from the top |
| `[count]` | `number` | a group's rows |
| `[expanded]` | `boolean` |  |
| `[aggregates]` | `Object&lt;string, *&gt;` | a group's figures by column id |
| `[pendingServer]` | `string[]` | a group's non-additive columns, left for the server |

#### `ColumnDef` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[id]` | `string` | the column's id (default: field) |
| `[field]` | `string` | the row's property |
| `[header]` | `string` | the heading (default: the field) |
| `[type='text']` | `('text'\|'number'\|'date'\|'boolean')` |  |
| `[format]` | `(string\|Object\|function(*, Object): string)` | a named format (amount0, pct2, compact, dmy), number options, or a function |
| `[valueGetter]` | `function(Object): *` | the value from the row (default: row[field]) |
| `[comparator]` | `function(*, *): number` | the order of two values |
| `[width=120]` | `number` |  |
| `[minWidth=40]` | `number` |  |
| `[maxWidth]` | `number` |  |
| `[pinned]` | `('left'\|'right'\|null)` |  |
| `[hidden=false]` | `boolean` |  |
| `[sortable=true]` | `boolean` |  |
| `[filterable=true]` | `boolean` |  |
| `[resizable=true]` | `boolean` |  |
| `[editable=false]` | `boolean` | a person may change its values (setCellValue) |
| `[aggFunc]` | `string` | the aggregation of a value column (sum, count, min, max, avg, first, last, distinct, or a server measure) |
| `[additive]` | `boolean` | whether its figures may be summed: false for VaR, ES, ratios (a total is asked of the server); default: true for numbers |
| `[aggregate]` | `string` | 'server': the same as additive false (ELEMENTS.md's name) |
| `[measure]` | `string` | the server's measure text when it is not func(field) (var(99, 1d)) |
| `[enableRowGroup]` | `boolean` | may be a row group (default: text and date columns) |
| `[enablePivot]` | `boolean` | may be a column pivot (default: text and date columns) |
| `[enableValue]` | `boolean` | may be a value (default: numbers) |
| `[group]` | `string` | a heading above several columns |
| `[description]` | `string` | words for the field list |
| `[cellClass]` | `(string\|function(*, Object): (string\|string[]))` | classes on a row's cell (letters, digits, - and _ only; others are dropped): a name, or a function of the value and the row |
| `[cellStyle]` | `(Object\|function(*, Object): Object)` | a row's cell's style, limited to colour, background, font weight and style, text alignment and decoration (anything else is dropped): an object, or a function of the value and the row |
| `[system=false]` | `boolean` | a column the grid adds (the AG Grid-style adapter's group column): never in the field list |
| `[cellRenderer]` | `function({value: *, text: string, row: Object, doc: Document}): (Node\|string\|null)` | a leaf's cell content drawn by the host (a link, a button); null keeps the formatted text. Copy, export and sort still use the value and its text. |

#### `DocTableHooks` : `Object`

What the host draws in a document table: a cell's content, its tone class, a row's extra attributes.

| Name | Type | Meaning |
|---|---|---|
| `[content]` | `function(Object): (Node\|string\|Array)` | a cell's drawn value (default: its text) |
| `[tone]` | `function(Object): string` | a cell's tone class, with its leading space (default: ' t-&lt;tone&gt;' or '') |
| `[rowAttrs]` | `function(Object, number): Object` | extra attributes on a plain table's row (null and false are left out) |

#### `ExportTable` : `import('../../drishti-core/src/drishti_files.js').ExportTable`

#### `AgServerContext` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `column` | `function(string): ?Object` | a drishti column by id |
| `aggOf` | `function(string): ?string` | the AG aggFunc name of a value column (what the application gave) |
| `[api]` | `Object` | the adapter's handle (params.api) |
| `[context]` | `*` | the application's context (params.context) |
| `[separator='_']` | `string` | serverSidePivotResultFieldSeparator |
| `[levelLimit=100000]` | `number` | rows asked of one pivot level |
| `[grandRow=false]` | `boolean` | ask the grand total row |
| `[rowTotals=false]` | `boolean` | ask each row's total across the pivot columns |
| `[infinite=false]` | `boolean` | the infinite row model's datasource: getRows({startRow, endRow, sortModel, filterModel, successCallback, failCallback}) |

#### `AgReportItem` : `Object`

What the adapter did with one option: mapped in part, not supported, nothing to do, or a name it does not know.

| Name | Type | Meaning |
|---|---|---|
| `option` | `string` | the option's name (a column's as columnDefs[ID].name) |
| `state` | `('partial'\|'unsupported'\|'no-op'\|'unknown')` |  |
| `words` | `string` | what happens instead (drishti-grid's way) |

#### `CellEdit` : `Object`

One cell's change.

| Name | Type | Meaning |
|---|---|---|
| `rowId` | `string` |  |
| `colId` | `string` |  |
| `field` | `string` |  |
| `old` | `*` |  |
| `value` | `*` |  |

#### `EditTransaction` : `Object`

An edit transaction.

| Name | Type | Meaning |
|---|---|---|
| `id` | `number` |  |
| `source` | `('edit'\|'paste'\|'clear'\|'undo'\|'redo'\|'api')` |  |
| `edits` | `CellEdit[]` |  |
| `status` | `('applied'\|'proposed'\|'refused'\|'pending')` |  |
| `proposalId` | `?string` | the four-eyes proposal, when one was made |

#### `ExplainState` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `row` | `number` |  |
| `col` | `number` |  |
| `loading` | `boolean` |  |
| `result` | `?(Object\|string\|Node)` | an ExplainResult (drishti-core), words or an element |
| `error` | `?Error` |  |

#### `RowChange` : `Object`

A row's update as the grid tells it: the row's id and each changed cell's old and new value and direction.

| Name | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `cells` | `Object&lt;string, {old: *, value: *, dir: ?string}&gt;` |  |

#### `HeaderSpan` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `start` | `number` | the first column (an index into the list given) |
| `end` | `number` | the last column (inclusive) |
| `label` | `string` |  |
| `group` | `?Object` | the pivot column entry it opens and closes (null: not collapsible) |

#### `ConditionalFormat` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[column]` | `string` | the column's id (or a pivot value's source column) |
| `[measure]` | `string` | a pivot value's label |
| `type` | `('colourScale'\|'dataBar')` |  |
| `[colour]` | `string` | a theme token for the scale or the bar (default --d-accent for scales, --d-pos and --d-neg for bars) |

#### `MenuItem` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[id]` | `string` | the action (absent on headings and separators) |
| `[label]` | `string` |  |
| `[key]` | `string` | the shortcut, as words |
| `[icon]` | `string` |  |
| `[checked]` | `boolean` | a check or radio item that is on |
| `[disabled]` | `boolean` |  |
| `[why]` | `string` | why it is disabled, in words |
| `[heading]` | `string` | a heading line instead of an item |
| `[sep]` | `boolean` | a separator |
| `[keep]` | `boolean` | the menu stays open after it runs (a member's check) |
| `[input]` | `{placeholder: string, value: string}` | an item with a field and an Apply button |

#### `GridViewOptions` : `Object`

Everything GridModel takes (columns, rows or datasource, serverPivot, explain, clientAggregation, live ...), and:

| Name | Type | Meaning |
|---|---|---|
| `[layout='wells']` | `('wells'\|'panel'\|'terminal')` | the arrangement (VaR screens: terminal) |
| `[density='compact']` | `('comfortable'\|'compact'\|'terminal')` |  |
| `[title]` | `string` | the toolbar's title |
| `[subtitle]` | `string` | words after it (the source, the date) |
| `[label]` | `string` | the grid's name for a screen reader (default: the title) |
| `[pivot]` | `{rows: string[], columns: string[], values: Array&lt;{colId: string, aggFunc: string}&gt;, filters: Array}` | start as a pivot of these wells |
| `[pivotOptions]` | `Object` | the view's other parts at the start (sort, top, valueFilters, subtotals) |
| `[pinTotals=true]` | `boolean` | the pivot's grand total columns pinned on the right |
| `[grandColumn=true]` | `boolean` | the pivot's grand total column (the total of each row across the column members) |
| `[columnGroupTotals=true]` | `boolean` | the total column of each open pivot column group |
| `[conditionalFormats]` | `import('./drishti_grid-ui.js').ConditionalFormat[]` | colour scales and data bars |
| `[checkboxes=true]` | `boolean` | row checkboxes (when rows may be selected) |
| `[drillThrough]` | `function(Object): (Promise&lt;Object[]&gt;\|Object[])` | the rows behind a cell (default: the rows in the page that match the cell's members) |
| `[members]` | `function(string): (Promise&lt;Array&lt;{text: string, count: number}&gt;&gt;\|Array)` | a field's members for its filter (default: the rows in the page) |
| `[phoneWidth=640]` | `number` | below this width the grid is drawn for a phone |
| `[fitRows]` | `number` | the host as tall as its rows, up to this many (then it scrolls): a screen's small table |
| `[pinnedTopRows]` | `Object[]` | rows kept under the headings as the others scroll (a spreadsheet's header rows): drawn by the columns' formats, classes and renderers, not sorted, filtered or counted; a click is pinnedCellClicked |
| `[getRowClass]` | `function(Object): ?string` | more classes for a leaf's row from its data |
| `[getRowData]` | `function(Object): ?Object&lt;string, *&gt;` | data-* attributes for a leaf's row ({key: 'A-1'}: data-key) |
| `[chrome=true]` | `boolean` | false: the grid without its layout's chrome (no command line, toolbar, wells or function keys; the status bar and Explain stay), for a small table inside a screen that has its own command line and keys |
| `[model]` | `GridModel` | draw this model rather than make one |
| `[chrome=true]` | `boolean` | false: the grid alone, without toolbar, wells, status bar or Explain drawer (a panel inside a page that has its own; the keys, menus and Explain hook still work) |

#### `GridOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `columns` | `import('./drishti_columns.js').ColumnDef[]` |  |
| `[rows]` | `Object[]` | client-side rows |
| `[datasource]` | `import('./drishti_server-rows.js').Datasource` | server-side rows (instead of rows) |
| `[getRowId]` | `function(Object, number): string` |  |
| `[layout='wells']` | `('wells'\|'panel'\|'terminal')` |  |
| `[density='compact']` | `('comfortable'\|'compact'\|'terminal')` |  |
| `[serverPivot]` | `function({view: Object}, AbortSignal, Object[]): Promise&lt;Object&gt;` | asks a server pivot (POST /api/pivot/query's body; the third argument: the rows in the page, for an inline pivot); needed for a pivot of server-side rows, for the non-additive totals of a local pivot, and for every pivot when clientAggregation is false |
| `[clientAggregation=true]` | `boolean` | false: the browser computes no figure (the console's policy, ELEMENTS.md section 3): group figures and every pivot are the server's |
| `[explain]` | `function(Object): Promise&lt;*&gt;` | the Explain hook: given a cell's context, the explanation (a layout shows it) |
| `[rowSelection='multiple']` | `('single'\|'multiple'\|'none')` |  |
| `[blockSize]` | `number` | server-side rows per request |
| `[serverGroupTotals]` | `function({groupBy: string[], groups: Array&lt;{path: string[], columns: string[]}&gt;}): Promise&lt;Array&lt;{path: string[], values: Object}&gt;&gt;` | the server's figures of row groups for their non-additive columns (VaR, ES), asked when grouping and again after updates (debounced) |
| `[live]` | `{asyncWaitMs: number, flashMs: number, serverDebounceMs: number}` | the live data's timings (drishti_grid-live.js) |
| `[editing]` | `{writeBack: function(Object): Promise&lt;Object&gt;, fourEyes: boolean, undoLimit: number}` | cell editing (drishti_grid-edit.js): the server's write-back hook (each edit transaction; it answers applied, refused or proposed), four eyes |
| `[locale]` | `string` | how typed and pasted numbers are read (de-DE: 1.250,5) |
| `[serverFuncs]` | `Array&lt;(string\|{name: string, label: string})&gt;` | aggregations only the server computes (median, var_samp, stddev_samp), offered in the wells and menus beside the browser's; a value with one is always asked of the server |
| `[valueFuncs]` | `string[]` | the browser's aggregations offered in the wells and menus (default: every one of VALUE_FUNCS; a server pivot offers those its server knows) |
| `[pivotFormat]` | `function(Object): ?(string\|Object\|function(*, Object): string)` | a pivot measure's format, given the answer's measure ({label, id, kind, unit, showAs}); default: its value column's |
| `[decorateCell]` | `function(Object): ?{cls: string, title: string, style: Object&lt;string, string&gt;, before: Node, content: Node, after: (string\|Node)}` | a host's marks on a cell, given its context (cellContext with value and text): classes (a comment, a change since a date), a hover note, colours (color, background-color, font-weight, font-style: a conditional format), a node before the figure (an icon), a node in its place (a link holding the same text), and a node or words after it (a sparkline, a delta); it shows, it never changes a figure |
| `[treeData=false]` | `boolean` | the rows make a tree by their paths (getDataPath): nodes open and close, a node no row names carries its figures as a group (the server's when clientAggregation is false); the first shown column holds the node's key |
| `[getDataPath]` | `function(Object): string[]` | a row's path from the top (tree data) |
| `[groupDefaultExpanded=0]` | `number` | groups and tree nodes open down to this level (-1: all) |

#### `KeyResult` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `focus` | `?{row: number, col: number}` | the new focus (null: unchanged) |
| `action` | `?string` | what the layout does: select-row, toggle-row, select-all, extend-range, copy, edit, open, expand, collapse, expand-all, collapse-all, explain, clear, null |
| `handled` | `boolean` | whether the key was the grid's (the layout then prevents the browser's default) |

#### `LiveMessage` : `Object`

A feed message.

| Name | Type | Meaning |
|---|---|---|
| `type` | `('transaction'\|'dataChanged'\|'rows')` |  |
| `[add]` | `Object[]` |  |
| `[update]` | `Object[]` |  |
| `[remove]` | `Array&lt;string&gt;` |  |
| `[rows]` | `Object[]` |  |
| `[purge]` | `boolean` |  |
| `[keys]` | `string[]` |  |
| `[blocks]` | `number[]` |  |

#### `LiveFeed` : `Object`

A feed: subscribe(onMessage) gives every message and returns the function that stops it.

| Name | Type | Meaning |
|---|---|---|
| `subscribe` | `function(function(LiveMessage): void): function(): void` |  |

#### `PivotValue` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `measure` | `string` | the function's text: sum(NOTIONAL), count(), var(99, 1d) |
| `[as]` | `string` | its label (default: the text) |

#### `PivotView` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[source]` | `string` | the server's source (a local pivot does not need it) |
| `rows` | `string[]` | row dimensions, outermost first |
| `columns` | `string[]` | column dimensions |
| `values` | `PivotValue[]` |  |
| `[filters]` | `Object&lt;string, {in: string[], notIn: string[]}&gt;` | member filters |
| `[expanded]` | `Array&lt;{axis: string, path: Array&lt;Array&lt;string&gt;&gt;, by: ?string}&gt;` | the open nodes |
| `[expandTo]` | `{rows: number, columns: number}` | every node open down to this level (1: level one only) |
| `[sort]` | `Object&lt;string, {by: string, desc: boolean}&gt;` | per dimension: by "label" or a measure's label |
| `[top]` | `Object&lt;string, {n: number, by: string}&gt;` | per dimension: its top n, the rest in Others |
| `[subtotals='below']` | `('below'\|'above')` |  |
| `[valueFilters]` | `Array&lt;{dim: string, by: string, op: string, value: *, valueTo: *}&gt;` | keep a level's members whose figure passes |
| `[options]` | `Object&lt;string, string&gt;` | the server provider's options |

#### `CellRange` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `r1` | `number` | first display row (inclusive) |
| `r2` | `number` | last display row (inclusive) |
| `c1` | `number` | first shown column |
| `c2` | `number` | last shown column |

#### `BlockRequest` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `startRow` | `number` | the first row, inclusive |
| `endRow` | `number` | the last, exclusive |
| `sortModel` | `Array&lt;{colId: string, sort: string}&gt;` |  |
| `filterModel` | `Object&lt;string, Object&gt;` |  |
| `quickFilter` | `string` |  |
| `rowGroupCols` | `string[]` | the row group columns, outermost first |
| `groupKeys` | `string[]` | the members of the level asked for (its parents' values); [] for the top |
| `valueCols` | `Array&lt;{colId: string, aggFunc: string}&gt;` |  |
| `pivotCols` | `string[]` |  |
| `pivotMode` | `boolean` |  |
| `[signal]` | `AbortSignal` |  |

#### `BlockAnswer` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` | the rows of the block (a group level's rows are its members: {[groupCol]: value, childCount, ...figures}) |
| `[rowCount]` | `number` | how many rows the level has, when known (else the model grows as blocks come back short) |

#### `Datasource` : `Object`

A data source: the one function the model calls.

| Name | Type | Meaning |
|---|---|---|
| `getRows` | `function(BlockRequest): Promise&lt;BlockAnswer&gt;` |  |

#### `TreeNode` : `Object`

A tree node.

| Name | Type | Meaning |
|---|---|---|
| `id` | `number` |  |
| `path` | `string[]` | the keys from the top |
| `depth` | `number` | path length less one |
| `row` | `number` | the row number it carries, or -1 for a node no row names |
| `children` | `TreeNode[]` |  |
| `aggregates` | `Object&lt;string, *&gt;` | a node without a row: its figures by column id |
| `serverCols` | `string[]` | the columns whose figures are the server's |
| `pendingServer` | `string[]` | those still waiting |

#### `WellValue` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `colId` | `string` |  |
| `aggFunc` | `string` |  |
| `[as]` | `string` | its label |
| `[showAs]` | `string` | how the server shows it (pct-grand, rank, diff:1:MONTH) |

## drishti-inputs

Version 0.1.0. Bundle `static/elements/drishti-inputs/drishti_inputs.js`, global `Drishti.elements.inputs`; requires drishti-core.

### drishti_calendar.js

#### `CALENDAR_RULES`

The bundled market calendars as rules (illustrative; a site's own calendars replace them through defineCalendar).

#### `easterSunday(y)`

Easter Sunday of a year (the Gregorian computus).

| Parameter | Type | Meaning |
|---|---|---|
| `y` | `number` |  |

Returns `string`: ISO date.

#### `ruleDate(r, y)`

The day a rule falls on in a year (null when it does not hold that year).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `HolidayRule` |  |
| `y` | `number` |  |

Returns `?string`: ISO date.

#### class `HolidayCalendar`

A business calendar: its weekend, its holidays (from rules and from dates), and the business dates asked of it.

##### `new HolidayCalendar(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{name: string, rules: HolidayRule[], dates: Array&lt;(string\|{date: string, name: string})&gt;, weekend: number[]}` | weekend: the days that are never business days (default [0, 6], Sunday and Saturday); dates: holidays as given (the server's list) |

##### `HolidayCalendar.holidays(y)`

The holidays of a year in order, each with its name.

| Parameter | Type | Meaning |
|---|---|---|
| `y` | `number` |  |

Returns `Array&lt;{date: string, name: string}&gt;`.

##### `HolidayCalendar.holidayName(v)`

A holiday's name, or null when the day is not a holiday (weekends are not holidays).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | a date |

Returns `?string`.

##### `HolidayCalendar.isBusinessDay(v)`

Whether a day is a business day: not a weekend day and not a holiday.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `boolean`.

##### `HolidayCalendar.isWeekend(v)`

Whether a day is a weekend day of this calendar.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `boolean`.

##### `HolidayCalendar.addBusinessDays(v, n)`

The business date n business days away (n negative goes back; 0 rolls a holiday forward to the next business day).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `n` | `number` |  |

Returns `string`.

##### `HolidayCalendar.tMinus(v, n)`

T-n: the n-th business day before a date (T-1 is the previous close).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` | the day T (default today) |
| `[n=1]` | `number` |  |

Returns `string`.

##### `HolidayCalendar.onOrBefore(v)`

The business day on or before a date (a date that is not a business day rolls back).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.onOrAfter(v)`

The business day on or after a date.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.monthEnd(v)`

The last business day of the month a date is in (month-end).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.monthStart(v)`

The first business day of the month a date is in.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.quarterEnd(v)`

The last business day of the quarter a date is in.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.yearEnd(v)`

The last business day of the year a date is in.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.previousMonthEnd(v)`

The last month-end before a date (the latest month-end that has happened by it, the date itself when it is one).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

##### `HolidayCalendar.businessDaysBetween(from, to)`

Business days after from, up to and including to (negative when to is earlier).

| Parameter | Type | Meaning |
|---|---|---|
| `from` | `*` |  |
| `to` | `*` |  |

Returns `number`.

#### `defineCalendar(name, spec)`

Defines (or replaces) a named calendar: rules, the server's dates, a weekend.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `spec` | `{rules: HolidayRule[], dates: Array&lt;(string\|{date: string, name: string})&gt;, weekend: number[]}` |  |

Returns `HolidayCalendar`.

#### `calendar(name)`

A calendar by name ("US", "UK", "TARGET", "CA", a defined one, "weekends", or a union "US+UK": a day is a holiday in the union when it is one in any), or the calendar itself when one is given.

| Parameter | Type | Meaning |
|---|---|---|
| `[name='weekends']` | `(string\|HolidayCalendar)` |  |

Returns `HolidayCalendar`.

#### `DATE_PRESETS` : `Array&lt;{id: string, label: string}&gt;`

The date-range presets, in the order a picker shows them.

#### `resolvePreset(id, o)`

A preset as dates, from a business date and a calendar: {from, to, label}.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` | one of DATE_PRESETS |
| `[o]` | `{asOf: string, calendar: (string\|HolidayCalendar)}` | asOf: the business date (default today, rolled back to a business day) |

Returns `{from: string, to: string, label: string}`.

#### `parseDateInput(text, o)`

Reads what a person typed as a business date: ISO (2026-11-06), "6 Nov 2026", "Nov 6 2026", T (the business date), T-3 or T+2 (business days), ME, QE, YE (this month's, quarter's, year's last business day), PME (the previous month-end). Null when it does not read.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `[o]` | `{asOf: string, calendar: (string\|HolidayCalendar)}` |  |

Returns `?string`: ISO date.

#### `monthGrid(y, m, weekStart)`

The days a month view shows: six weeks of seven days from the week the month starts in.

| Parameter | Type | Meaning |
|---|---|---|
| `y` | `number` |  |
| `m` | `number` | 1 to 12 |
| `[weekStart=1]` | `number` | 0 Sunday, 1 Monday |

Returns `Array&lt;Array&lt;{iso: string, day: number, inMonth: boolean, weekday: number}&gt;&gt;`.

#### `shiftDate(v, n, unit)`

A date moved by whole days, months or years (a month that is shorter keeps its last day: 31 Jan + 1 month is 28 or 29 Feb).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `n` | `number` |  |
| `unit` | `('day'\|'month'\|'year')` |  |

Returns `string`.

### drishti_inputs-dom.js

#### `uid(prefix)`

A new id for an element of a component ("di-&lt;prefix&gt;-&lt;n&gt;").

| Parameter | Type | Meaning |
|---|---|---|
| `prefix` | `string` |  |

Returns `string`.

#### `debounce(fn, ms)`

A function that runs fn once calls stop for ms milliseconds (the last call's arguments); .cancel() drops a pending call, .flush() runs it now.

| Parameter | Type | Meaning |
|---|---|---|
| `fn` | `function(...*): void` |  |
| `ms` | `number` |  |

Returns `function(...*): void`.

#### class `InputBase`

The base of every input component: the host element, the options, typed events on a bus and on the element.

##### `new InputBase(host, options, cls)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` | the element the component fills |
| `options` | `Object` |  |
| `cls` | `string` | the component's class on the host (di-date, di-multiselect ...) |

##### `InputBase.on(name, fn)`

Listens to one of the component's events.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `fn` | `function(Object, string): void` |  |

Returns `function(): void`: stops listening.

##### `InputBase.emit(name, detail)`

Emits an event on the bus and as a DOM event drishti:&lt;kebab-name&gt; on the host (bubbling).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `[detail]` | `Object` |  |

Returns `void`.

##### `InputBase.el(tag, p, children)`

Makes an element of this document.

| Parameter | Type | Meaning |
|---|---|---|
| `tag` | `string` |  |
| `[p]` | `Object` | as drishti-core's h |
| `[children]` | `Array` |  |

Returns `HTMLElement`.

##### `InputBase.destroy()`

Takes the component out of its element.

Returns `void`.

#### class `Popover`

A floating panel under an anchor (position fixed, in the page's body): a click outside and Escape close it; focus goes back to where it was.

##### `new Popover(doc, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `[o]` | `{cls: string, label: string, role: string, onClose: function(): void, theme: Element}` | theme: the element whose density the panel copies |

##### `Popover.isOpen()`

Whether it is open.

Returns `boolean`.

##### `Popover.open(anchor, content, o)`

Opens the panel under an anchor with its content.

| Parameter | Type | Meaning |
|---|---|---|
| `anchor` | `Element` |  |
| `content` | `Node` |  |
| `[o]` | `{focus: boolean}` | focus: keep the focus where it is (a combobox's field keeps it) |

Returns `HTMLElement`.

##### `Popover.place()`

Places the panel again (its content changed size).

Returns `void`.

##### `Popover.close(refocus)`

Closes the panel.

| Parameter | Type | Meaning |
|---|---|---|
| `[refocus]` | `boolean` | give the focus back to what had it |

Returns `void`.

#### class `Listbox`

Options in a list (role listbox): drawn up to a cap, the active one kept by aria-activedescendant on the element that keeps the focus.

##### `new Listbox(doc, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `o` | `{multiple: boolean, cap: number, label: string, render: function(Object, HTMLElement): void, onPick: function(Object, Event): void}` | cap: options drawn at most (default 300); render: draws an option's content into its element (default: its label and detail) |

##### `Listbox.render(items, selected, o)`

Draws options.

| Parameter | Type | Meaning |
|---|---|---|
| `items` | `Array&lt;{value: *, label: string, detail: string, disabled: boolean, group: string}&gt;` |  |
| `selected` | `function(Object): boolean` | whether an option is selected |
| `[o]` | `{more: number, empty: string}` | more: how many matched beyond these; empty: the words when there are none |

Returns `void`.

##### `Listbox.setActive(i, owner)`

Makes an option the active one (scrolled into view) and tells the focused element.

| Parameter | Type | Meaning |
|---|---|---|
| `i` | `number` |  |
| `[owner]` | `Element` | the element with the focus (its aria-activedescendant) |

Returns `void`.

##### `Listbox.key(e, owner)`

A key from the element with the focus: moves the active option or picks it. Returns whether the key was the list's.

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `KeyboardEvent` |  |
| `owner` | `Element` |  |

Returns `boolean`.

#### `normalizeOptions(list)`

Options as the components take them: strings, numbers, or {value, label, detail, disabled, group}.

| Parameter | Type | Meaning |
|---|---|---|
| `list` | `Array&lt;(string\|number\|Object)&gt;` |  |

Returns `Array&lt;{value: *, label: string, detail: string, disabled: boolean, group: string}&gt;`.

#### `searchOptions(items, q)`

Options that match a search, best first: the label starting with it, then a word of it starting with it, then containing it; the detail last. Every word of the search must match.

| Parameter | Type | Meaning |
|---|---|---|
| `items` | `Array&lt;{label: string, detail: string}&gt;` |  |
| `q` | `string` |  |

Returns `Array&lt;Object&gt;`.

### drishti_date-picker.js

#### class `CalendarView`

One or more months of days as a grid (role grid), keyed by the business calendar: what the pickers draw in their popovers.

##### `new CalendarView(doc, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `o` | `{calendar: Object, months: number, weekStart: number, businessOnly: boolean, min: string, max: string, focus: string, range: function(): {from: ?string, to: ?string, hover: ?string}, selected: function(string): boolean, onPick: function(string): void, onHover: function(string): void}` | months: how many months side by side (default 1); range: the range to shade (range picker) |

##### `CalendarView.refusal(d)`

Why a day may not be picked, or null when it may.

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `string` |  |

Returns `?string`.

##### `CalendarView.render()`

Draws the months from the focused day's.

Returns `void`.

##### `CalendarView.move(n, unit)`

Moves the focused day (and the months shown) and focuses its button.

| Parameter | Type | Meaning |
|---|---|---|
| `n` | `number` |  |
| `unit` | `('day'\|'month'\|'year')` |  |

Returns `void`.

##### `CalendarView.focusDay()`

Puts the keyboard focus on the focused day's button.

Returns `void`.

#### class `DatePicker`

A business-date picker: a field and a calendar. Events: change {value}, invalid {text, reason}, open, close.

##### `new DatePicker(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `[options]` | `DatePickerOptions` |  |

##### `DatePicker.refusal(d)`

Why a date may not be picked, or null.

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `string` | ISO |

Returns `?string`.

##### `DatePicker.open()`

Opens the calendar on the value (or the business date).

Returns `void`.

##### `DatePicker.close()`

Closes the calendar.

Returns `void`.

##### `DatePicker.getValue()`

The date (ISO), or null.

Returns `?string`.

##### `DatePicker.setValue(v, emit)`

Sets the date (ISO, a Date or null); change is emitted when a person changed it (or when emit is true).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `[emit]` | `boolean` |  |

Returns `void`.

##### `DatePicker.validate()`

Whether the field holds a date that may be picked (and one when it is required).

Returns `boolean`.

##### `DatePicker.focus()`

Puts the keyboard focus in the field.

Returns `void`.

### drishti_date-range.js

#### class `DateRangePicker`

A date-range picker with presets. Events: change {from, to, preset}, invalid {reason}, open, close.

##### `new DateRangePicker(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `[options]` | `DateRangeOptions` |  |

##### `DateRangePicker.presets()`

The presets this picker offers.

Returns `Array&lt;{id: string, label: string}&gt;`.

##### `DateRangePicker.text()`

The range in words: "3–7 Nov 2026 · Month to date".

Returns `string`.

##### `DateRangePicker.refusal(from, to)`

Why a range may not be kept, or null.

| Parameter | Type | Meaning |
|---|---|---|
| `from` | `?string` |  |
| `to` | `?string` |  |

Returns `?string`.

##### `DateRangePicker.open()`

Opens the panel: presets, two months, the two ends.

Returns `void`.

##### `DateRangePicker.apply()`

Keeps the panel's range (when it may be kept) and closes it.

Returns `boolean`: whether it was kept.

##### `DateRangePicker.close()`

Closes the panel without keeping it.

Returns `void`.

##### `DateRangePicker.getValue()`

The range ({from, to, preset}); a preset's dates as of the business date.

Returns `DateRange`.

##### `DateRangePicker.setValue(v, emit)`

Sets the range: {from, to}, or {preset} (its dates from the business date), or a preset's id.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `(DateRange\|string)` |  |
| `[emit]` | `boolean` | tell listeners (change) |

Returns `void`.

##### `DateRangePicker.setAsOf(asOf)`

Moves the business date: a preset's dates follow it.

| Parameter | Type | Meaning |
|---|---|---|
| `asOf` | `string` |  |

Returns `void`.

### drishti_tree.js

#### class `TreeModel`

A tree with lazy children, tri-state checks and search.

##### `new TreeModel(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{nodes: TreeNode[], loadChildren: function(TreeNode): Promise&lt;TreeNode[]&gt;, multiple: boolean, value: string[]}` | multiple default true (false: one node is picked) |

##### `TreeModel.setNodes(nodes)`

Replaces the roots (and everything under them).

| Parameter | Type | Meaning |
|---|---|---|
| `nodes` | `TreeNode[]` |  |

Returns `void`.

##### `TreeModel.node(id)`

A node by id.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `?TreeNode`.

##### `TreeModel.isBranch(n)`

Whether a node has, or may have, children.

| Parameter | Type | Meaning |
|---|---|---|
| `n` | `TreeNode` |  |

Returns `boolean`.

##### `TreeModel.ancestors(id)`

The ids from the root down to a node's parent.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `string[]`.

##### `TreeModel.pathLabel(id)`

The path of labels to a node ("Rates › Swaps").

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `string`.

##### `TreeModel.toggle(id, open)`

Opens or closes a node; opening an unloaded branch asks the loader for its children.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `[open]` | `boolean` | default: the other way |

Returns `Promise&lt;void&gt;`.

##### `TreeModel.ensureChildren(n)`

Loads a node's children when they are not known yet.

| Parameter | Type | Meaning |
|---|---|---|
| `n` | `TreeNode` |  |

Returns `Promise&lt;void&gt;`.

##### `TreeModel.stateOf(id)`

A node's state: checked (it or an ancestor is), mixed (something under it is), unchecked.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `('checked'\|'mixed'\|'unchecked')`.

##### `TreeModel.check(id, on)`

Checks or unchecks a node (single: picks it alone). The selection stays compact: a parent whose children are all checked becomes the parent; unchecking under a checked parent checks the parent's other children instead.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `[on]` | `boolean` | default: the other way (a mixed node becomes checked) |

Returns `void`.

##### `TreeModel.checkAll(on)`

Checks every root (everything), or nothing.

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `void`.

##### `TreeModel.getValue(o)`

The selection: compact (a checked node stands for everything under it; the default) or the loaded leaves under it.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{mode: ('compact'\|'leaves')}` |  |

Returns `string[]`.

##### `TreeModel.setValue(ids)`

Sets the selection (ids; ids not loaded yet are kept and apply when they load).

| Parameter | Type | Meaning |
|---|---|---|
| `ids` | `string[]` |  |

Returns `void`.

##### `TreeModel.search(q, server)`

Searches: the loaded nodes whose label or detail holds every word, with their ancestors opened. With a server hook, its answer (nodes with their paths) is merged in first.

| Parameter | Type | Meaning |
|---|---|---|
| `q` | `string` |  |
| `[server]` | `function(string): Promise&lt;Array&lt;{node: TreeNode, path: TreeNode[]}&gt;&gt;` |  |

Returns `Promise&lt;number&gt;`: how many matched.

##### `TreeModel.rows()`

The visible rows, in order: the roots and the children of open nodes (only the search's matches and their ancestors while searching).

Returns `TreeRow[]`.

##### `TreeModel.summary(o)`

The selection in words: "Rates, FX › Spot" or "3 selected" past a few; "Everything" when every root is checked.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{max: number, none: string}` | max: names shown before a count (default 2) |

Returns `string`.

### drishti_tree-picker.js

#### class `TreePicker`

A tree picker. Events: change {value, leaves}, open, close, loadFailed {id, error}.

##### `new TreePicker(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `options` | `TreePickerOptions` |  |

##### `TreePicker.open()`

Opens the panel (focus in the search field).

Returns `void`.

##### `TreePicker.close()`

Closes the panel.

Returns `void`.

##### `TreePicker.render()`

Draws the tree, the count and the button's words.

Returns `void`.

##### `TreePicker.expand(id, open)`

Opens or closes a node (children from the server the first time).

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `[open]` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `TreePicker.checkAll(on)`

Checks everything or nothing.

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `void`.

##### `TreePicker.getValue(o)`

The checked ids (compact), or the loaded leaves under them.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{mode: ('compact'\|'leaves')}` |  |

Returns `string[]`.

##### `TreePicker.setValue(ids)`

Sets the checked ids.

| Parameter | Type | Meaning |
|---|---|---|
| `ids` | `string[]` |  |

Returns `void`.

##### `TreePicker.setNodes(nodes)`

Replaces the roots.

| Parameter | Type | Meaning |
|---|---|---|
| `nodes` | `import('./drishti_tree.js').TreeNode[]` |  |

Returns `void`.

### drishti_multiselect.js

#### class `MultiSelect`

A multi-select with search, chips and select-all. Events: change {value, added, removed}, open, close.

##### `new MultiSelect(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `options` | `MultiSelectOptions` |  |

##### `MultiSelect.open()`

Opens the list.

Returns `void`.

##### `MultiSelect.close()`

Closes the list.

Returns `void`.

##### `MultiSelect.toggle(v)`

Picks or unpicks one value.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `void`.

##### `MultiSelect.selectAll(on)`

Picks every option the search matches (or every option), or none (Clear: every pick, matching or not).

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `void`.

##### `MultiSelect.render()`

Draws the chips.

Returns `void`.

##### `MultiSelect.getValue()`

The picked values, in the options' order.

Returns `Array&lt;*&gt;`.

##### `MultiSelect.setValue(values)`

Sets the picked values.

| Parameter | Type | Meaning |
|---|---|---|
| `values` | `Array&lt;*&gt;` |  |

Returns `void`.

##### `MultiSelect.setOptions(options)`

Replaces the options (picks that are no longer options are dropped).

| Parameter | Type | Meaning |
|---|---|---|
| `options` | `Array` |  |

Returns `void`.

##### `MultiSelect.focus()`

Puts the keyboard focus in the field.

Returns `void`.

### drishti_combobox.js

#### class `TypeAhead`

Options from a source as a person types: debounced, cached, aborted and in order.

##### `new TypeAhead(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{source: (Array\|function(string, AbortSignal): Promise&lt;Array&gt;), debounceMs: number, minChars: number, cacheSize: number, onResult: function({query: string, options: Array, error: ?Error, loading: boolean}): void}` | debounceMs default 200 (0 for a list); minChars default 1 (0 for a list); cacheSize default 50 queries |

##### `TypeAhead.query(q)`

Asks for the options of a query (after the debounce); the answer goes to onResult.

| Parameter | Type | Meaning |
|---|---|---|
| `q` | `string` |  |

Returns `void`.

##### `TypeAhead.abort()`

Aborts the request in flight.

Returns `void`.

##### `TypeAhead.cancel()`

Stops: no answer is given after this.

Returns `void`.

#### class `Combobox`

A combobox with type-ahead. Events: change {value, option}, search {query}, error {error}.

##### `new Combobox(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `options` | `ComboboxOptions` |  |

##### `Combobox.pick(it)`

Picks an option.

| Parameter | Type | Meaning |
|---|---|---|
| `it` | `{value: *, label: string}` |  |

Returns `void`.

##### `Combobox.getValue()`

The value ({value, label}) or null.

Returns `?{value: *, label: string}`.

##### `Combobox.setValue(v, emit, option)`

Sets the value.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `?{value: *, label: string}` |  |
| `[emit]` | `boolean` |  |
| `[option]` | `Object` | the option picked (passed on in change) |

Returns `void`.

##### `Combobox.focus()`

Puts the keyboard focus in the field.

Returns `void`.

##### `Combobox.destroy()`

Stops the type-ahead and takes the combobox out.

Returns `void`.

### drishti_schema.js

#### `words(key)`

A key in words: tradeDate and TRADE_DATE read "Trade date".

| Parameter | Type | Meaning |
|---|---|---|
| `key` | `string` |  |

Returns `string`.

#### `pathParts(path)`

A path's parts: "legs[1].notional" is ['legs', 1, 'notional'].

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |

Returns `Array&lt;(string\|number)&gt;`.

#### `getPath(obj, path)`

The value at a path.

| Parameter | Type | Meaning |
|---|---|---|
| `obj` | `Object` |  |
| `path` | `string` |  |

Returns `*`.

#### `setPath(obj, path, value)`

A copy of obj with the value at a path set (objects and arrays on the way are copied, never changed).

| Parameter | Type | Meaning |
|---|---|---|
| `obj` | `Object` |  |
| `path` | `string` |  |
| `value` | `*` |  |

Returns `Object`.

#### `conditionHolds(cond, values, path)`

Whether a condition holds for a field at a path: {field, equals \| notEquals \| in \| notIn \| gt \| gte \| lt \| lte \| truthy \| falsy \| empty}, {all: [...]}, {any: [...]}, {not: {...}}. field is a sibling's key, or "/path" from the root.

| Parameter | Type | Meaning |
|---|---|---|
| `cond` | `Object` |  |
| `values` | `Object` | the whole form's value |
| `path` | `string` | the field the condition belongs to |

Returns `boolean`.

#### `fieldsOf(schema, base)`

The fields of an object schema in order, each with its key, path, label and whether it is required.

| Parameter | Type | Meaning |
|---|---|---|
| `schema` | `FieldSchema` | an object schema |
| `[base]` | `string` | the object's path |

Returns `Array&lt;{key: string, path: string, field: FieldSchema, label: string, required: boolean}&gt;`.

#### `defaults(schema)`

A schema's starting value: every default, objects filled in, arrays at their minItems.

| Parameter | Type | Meaning |
|---|---|---|
| `schema` | `FieldSchema` |  |

Returns `*`.

#### `fieldProblem(field, v, c)`

One field's problem in words, or null (its own rules; not the fields inside it).

| Parameter | Type | Meaning |
|---|---|---|
| `field` | `FieldSchema` |  |
| `v` | `*` |  |
| `c` | `{label: string, required: boolean, values: Object, path: string, labelOf: function(string): string}` |  |

Returns `?string`.

#### `validate(schema, values)`

Validates a value against a schema: every shown field's problem, by path.

| Parameter | Type | Meaning |
|---|---|---|
| `schema` | `FieldSchema` | the form's object schema |
| `values` | `Object` |  |

Returns `{ok: boolean, errors: Object&lt;string, string&gt;}`.

#### `visibleValue(schema, values)`

The value without the fields that are hidden (what a form submits).

| Parameter | Type | Meaning |
|---|---|---|
| `schema` | `FieldSchema` |  |
| `values` | `Object` |  |

Returns `Object`.

#### `changes(before, after, base)`

What changed between two values, leaf by leaf (a four-eyes proposal's diff).

| Parameter | Type | Meaning |
|---|---|---|
| `before` | `*` |  |
| `after` | `*` |  |
| `[base]` | `string` |  |

Returns `Array&lt;{path: string, old: *, value: *}&gt;`.

#### `parseField(field, text, label)`

Reads typed text as a field's value: numbers with grouping, a true minus, k, m and bn, percentages; integers; booleans; dates (ISO). Returns {value} or {error} in words.

| Parameter | Type | Meaning |
|---|---|---|
| `field` | `FieldSchema` |  |
| `text` | `string` |  |
| `[label]` | `string` |  |

Returns `{value: *, error: ?string}`.

### drishti_form.js

#### class `Form`

A form from a schema. Events: change {path, value, values}, invalid {errors}, submitted {values, result}, proposed {proposal, result}, reset.

##### `new Form(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `options` | `FormOptions` |  |

##### `Form.render()`

Draws the form again (after an array changed shape); the focus goes back to the field that had it.

Returns `void`.

##### `Form.refresh()`

Shows and hides fields by their conditions, and shows each touched field's problem.

Returns `void`.

##### `Form.focusField(path)`

Puts the focus on a field.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` |  |

Returns `void`.

##### `Form.validate()`

Validates every shown field (and shows every problem).

Returns `{ok: boolean, errors: Object&lt;string, string&gt;}`.

##### `Form.submit()`

Submits: validates, asks the server's checks, then hands the value to onSubmit, or files a proposal when the form has four eyes.

Returns `Promise&lt;boolean&gt;`: whether it was submitted (or proposed).

##### `Form.resolveProposal(decision, note)`

The proposal's decision came back: approved keeps the value as the new start; rejected puts the start back. Either way the form is editable again.

| Parameter | Type | Meaning |
|---|---|---|
| `decision` | `('approved'\|'rejected')` |  |
| `[note]` | `string` |  |

Returns `void`.

##### `Form.getValue()`

The value: the shown fields only.

Returns `Object`.

##### `Form.setValue(v)`

Replaces the value (and the start Reset goes back to).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `Object` |  |

Returns `void`.

##### `Form.reset()`

Puts the start value back.

Returns `void`.

##### `Form.setErrors(errors)`

Problems from elsewhere (a server's answer), by path.

| Parameter | Type | Meaning |
|---|---|---|
| `errors` | `Object&lt;string, string&gt;` |  |

Returns `void`.

### drishti_filter-bar.js

#### class `FilterModelBar`

The filter bar's model: fields, the filter model, the chips' words and every edit.

##### `new FilterModelBar(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{fields: FilterField[], model: Object, quickFilter: string}` |  |

##### `FilterModelBar.field(id)`

A field by id.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |

Returns `?FilterField`.

##### `FilterModelBar.chips()`

The chips: each filtered field and its words.

Returns `Array&lt;{id: string, label: string, text: string}&gt;`.

##### `FilterModelBar.set(id, filter)`

Sets one field's filter (null takes it away).

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `filter` | `?Object` | a ColumnFilter |

Returns `void`.

##### `FilterModelBar.setMembers(id, values)`

Keeps a set's members (none: the filter goes).

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `values` | `Array` |  |

Returns `void`.

##### `FilterModelBar.setCondition(id, op, text, textTo)`

A number or text condition; a value that does not read takes nothing and says why.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `op` | `string` |  |
| `text` | `string` |  |
| `[textTo]` | `string` | between's upper end |

Returns `?string`: the problem in words, or null.

##### `FilterModelBar.setDates(id, r)`

A date range: a preset (it moves with the business date) or two ends as typed.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `r` | `{preset: string, from: string, to: string, asOf: string, calendar: string}` |  |

Returns `?string`: the problem in words, or null.

##### `FilterModelBar.clear()`

Takes every filter away (and the quick filter).

Returns `void`.

##### `FilterModelBar.payload()`

What the bar emits: the model (a copy), the quick filter.

Returns `{model: Object, quickFilter: string}`.

#### class `FilterBar`

The filter bar. Events: change {model, quickFilter}; the same payload on the bus under the channel.

##### `new FilterBar(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `options` | `FilterBarOptions` |  |

##### `FilterBar.render()`

Draws the chips.

Returns `void`.

##### `FilterBar.edit(id, anchor)`

Opens a field's editor under an anchor.

| Parameter | Type | Meaning |
|---|---|---|
| `id` | `string` |  |
| `[anchor]` | `Element` |  |

Returns `Promise&lt;void&gt;`.

##### `FilterBar.getValue()`

The filter model and the quick filter.

Returns `{model: Object, quickFilter: string}`.

##### `FilterBar.setValue(v)`

Sets the filters (and tells the bus).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `{model: Object, quickFilter: string}` |  |

Returns `void`.

### drishti_code-modes.js

#### `rachanaMode()`

The Rachana expression mode.

Returns `{startState: function(): Object, token: function(Object, Object): ?string, lineComment: string}`.

#### `yamlMode()`

The Rachana YAML mode: comments, keys, quoted strings, numbers, booleans, the Rachana-EL markers ($, @, #index), F1 to F12.

Returns `{startState: function(): Object, token: function(Object, Object): ?string, lineComment: string}`.

#### `MODES`

The languages by name: the CodeMirror mode each is, and whether it is ours (defined here) or vendored (loaded).

#### `defineModes(CM)`

Defines our modes on a CodeMirror (once each).

| Parameter | Type | Meaning |
|---|---|---|
| `CM` | `Object` | the CodeMirror global |

Returns `void`.

#### class `MiniStream`

A stream over one line with CodeMirror's StringStream methods (what a mode's token reads).

##### `new MiniStream(line)`

| Parameter | Type | Meaning |
|---|---|---|
| `line` | `string` |  |

##### `MiniStream.sol()`

At the line's start.

Returns `boolean`.

##### `MiniStream.eol()`

At the line's end.

Returns `boolean`.

##### `MiniStream.peek()`

The next character, not taken.

Returns `?string`.

##### `MiniStream.next()`

Takes the next character.

Returns `?string`.

##### `MiniStream.eatSpace()`

Takes white space.

Returns `boolean`: whether any was taken.

##### `MiniStream.skipToEnd()`

Takes the rest of the line.

Returns `void`.

##### `MiniStream.match(pattern, consume)`

Takes text that matches (a string or a regular expression anchored with ^), unless consume is false.

| Parameter | Type | Meaning |
|---|---|---|
| `pattern` | `(string\|RegExp)` |  |
| `[consume=true]` | `boolean` |  |

Returns `*`: the match, or false/null.

##### `MiniStream.current()`

The text taken since the token started.

Returns `string`.

#### `tokens(lang, text)`

Text as tokens by one of our modes, line by line: [{text, style}] per line (style null for plain text).

| Parameter | Type | Meaning |
|---|---|---|
| `lang` | `string` | rachana or yaml (python and text come back plain) |
| `text` | `string` |  |

Returns `Array&lt;Array&lt;{text: string, style: ?string}&gt;&gt;`.

### drishti_code-text.js

#### `wordAt(line, ch)`

The word at a cursor: letters, digits, _, . and a leading $ or @ (a Rachana path); from and to are its columns.

| Parameter | Type | Meaning |
|---|---|---|
| `line` | `string` |  |
| `ch` | `number` | the cursor's column |

Returns `{word: string, from: number, to: number}`.

#### `completions(catalogue, word, limit)`

What completes a word, best first (at most limit): the name starting with it, then a dotted or camel-case part starting with it, then the name containing it; ties by name. The word is matched without case.

| Parameter | Type | Meaning |
|---|---|---|
| `catalogue` | `CatalogueEntry[]` |  |
| `word` | `string` |  |
| `[limit=50]` | `number` |  |

Returns `CatalogueEntry[]`.

#### `insertText(e)`

The text an entry puts in: its insert, or its name with "()" for a function (the cursor goes between them: caret is its place).

| Parameter | Type | Meaning |
|---|---|---|
| `e` | `CatalogueEntry` |  |

Returns `{text: string, caret: number}`.

#### `lineDiff(a, b)`

The lines that changed from a to b (Myers): equal, delete (a line of a only) and insert (a line of b only), in order.

| Parameter | Type | Meaning |
|---|---|---|
| `a` | `string` | the original text |
| `b` | `string` | the new text |

Returns `Array&lt;{op: ('equal'\|'delete'\|'insert'), a: ?number, b: ?number, text: string}&gt;`: a and b are 1-based line numbers.

#### `sideBySide(ops)`

A diff as side-by-side rows: equal lines on both sides, a run of deletions paired with the insertions after it (a changed line), the rest alone. Each side {n, text, kind} or null.

| Parameter | Type | Meaning |
|---|---|---|
| `ops` | `Array&lt;{op: string, a: ?number, b: ?number, text: string}&gt;` | lineDiff's answer |

Returns `Array&lt;{left: ?{n: number, text: string, kind: string}, right: ?{n: number, text: string, kind: string}}&gt;`.

#### `diffStats(ops)`

How many lines a diff adds, removes and keeps.

| Parameter | Type | Meaning |
|---|---|---|
| `ops` | `Array&lt;{op: string}&gt;` |  |

Returns `{added: number, removed: number, same: number}`.

### drishti_code-editor.js

#### `ensureCodeMirror(given)`

CodeMirror on the page: the one given, the page's, or the vendored one loaded now (with its style sheet).

| Parameter | Type | Meaning |
|---|---|---|
| `[given]` | `Object` | a CodeMirror to use |

Returns `Promise&lt;Object&gt;`.

#### class `CodeEditor`

The code editor. Events: change {value}, ready, lint {problems}, completed {entry}.

##### `new CodeEditor(host, options)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `[options]` | `CodeEditorOptions` |  |

##### `CodeEditor.refresh()`

Lays the editor out again (after it was hidden: a drawer or a tab opened).

Returns `void`.

##### `CodeEditor.complete(forced)`

Opens the completions at the cursor (forced: even for an empty word).

| Parameter | Type | Meaning |
|---|---|---|
| `[forced]` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `CodeEditor.accept(entry)`

Puts a completion in place of the word at the cursor.

| Parameter | Type | Meaning |
|---|---|---|
| `entry` | `import('./drishti_code-text.js').CatalogueEntry` |  |

Returns `void`.

##### `CodeEditor.runLint()`

Asks the lint hook now and draws its problems.

Returns `Promise&lt;LintProblem[]&gt;`.

##### `CodeEditor.setProblems(problems)`

Draws problems: marks under the text, signs in the gutter, the list under the editor.

| Parameter | Type | Meaning |
|---|---|---|
| `problems` | `LintProblem[]` |  |

Returns `void`.

##### `CodeEditor.goTo(line, ch)`

Puts the cursor at a line (1-based) and column.

| Parameter | Type | Meaning |
|---|---|---|
| `line` | `number` |  |
| `[ch]` | `number` |  |

Returns `void`.

##### `CodeEditor.getValue()`

The text.

Returns `string`.

##### `CodeEditor.setValue(text)`

Replaces the text.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `void`.

##### `CodeEditor.setReadOnly(on)`

Makes it read-only, or editable.

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `void`.

##### `CodeEditor.focus()`

Puts the keyboard focus in the editor.

Returns `void`.

##### `CodeEditor.destroy()`

Takes the editor out.

Returns `void`.

#### class `CodeDiff`

A diff of two texts, side by side or unified, highlighted by the language; no CodeMirror needed. Events: none (read-only).

##### `new CodeDiff(host, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `o` | `{original: string, value: string, mode: string, layout: ('side'\|'unified'), labels: string[]}` | labels: the two sides' names (default Before, After) |

##### `CodeDiff.render()`

Draws the diff again (after setTexts or a layout change).

Returns `void`.

##### `CodeDiff.setTexts(original, value)`

Shows two other texts.

| Parameter | Type | Meaning |
|---|---|---|
| `original` | `string` |  |
| `value` | `string` |  |

Returns `void`.

##### `CodeDiff.stats()`

How many lines were added, removed and kept.

Returns `{added: number, removed: number, same: number}`.

### drishti_inputs-element.js

#### `INPUT_ELEMENTS`

Every element of drishti-inputs and the class it draws.

#### `defineInputElements(registry, win)`

Defines the elements (once each) on a registry.

| Parameter | Type | Meaning |
|---|---|---|
| `registry` | `CustomElementRegistry` |  |
| `win` | `Window` |  |

Returns `void`.

### drishti_index.js

#### `install(root)`

Starts drishti-inputs in a page (the bundle calls it when it loads; nothing happens without a document): defines its elements.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.

### Types of drishti-inputs

#### `HolidayRule` : `Object`

A holiday rule. One of: {fixed: 'MM-DD', roll}, {nth, weekday, month} (nth -1: the last), {before: 'MM-DD', weekday} (the weekday strictly before a day: Victoria Day), {easter: offset} (days from Easter Sunday: -2 Good Friday, 1 Easter Monday). roll for a fixed day on a weekend: 'nearest' (Saturday to Friday, Sunday to Monday), 'monday' (to the next Monday, or Tuesday when Monday is taken), 'none'. from and to bound the years the rule holds.

| Name | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `[fixed]` | `string` |  |
| `[roll='none']` | `('nearest'\|'monday'\|'none')` |  |
| `[nth]` | `number` |  |
| `[weekday]` | `number` | 0 Sunday to 6 Saturday |
| `[month]` | `number` | 1 to 12 |
| `[before]` | `string` |  |
| `[easter]` | `number` |  |
| `[from]` | `number` | the first year |
| `[to]` | `number` | the last year |

#### `LintProblem` : `Object`

A lint problem.

| Name | Type | Meaning |
|---|---|---|
| `line` | `number` | 1-based |
| `[from]` | `number` | the first column (0-based; default the line's start) |
| `[to]` | `number` | after the last column (default the line's end) |
| `[severity='error']` | `('error'\|'warning'\|'info')` |  |
| `message` | `string` | in words |

#### `CodeEditorOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[value]` | `string` |  |
| `[mode='rachana']` | `('rachana'\|'yaml'\|'python'\|'text')` |  |
| `[readOnly=false]` | `boolean` |  |
| `[lineNumbers=true]` | `boolean` |  |
| `[height=200]` | `number` | px |
| `[catalogue]` | `import('./drishti_code-text.js').CatalogueEntry[]` | the functions completion offers |
| `[complete]` | `function({word: string, line: string, ch: number, mode: string}): (Promise&lt;Array&gt;\|Array)` | the completion hook (the server's catalogue); answers CatalogueEntry objects |
| `[lint]` | `function(string): (Promise&lt;LintProblem[]&gt;\|LintProblem[])` | the lint hook |
| `[lintDelay=400]` | `number` | ms after typing stops |
| `[label]` | `string` |  |
| `[indentUnit=2]` | `number` | spaces an indent is (Python: 4) |
| `[keys]` | `Object&lt;string, function(): *&gt;` | the page's own keys in the editor, CodeMirror's names ('Ctrl-Enter': run, Esc: close); Tab and Shift-Tab indent and dedent a selection |
| `[CodeMirror]` | `Object` | a CodeMirror to use (default: the vendored one) |

#### `CatalogueEntry` : `Object`

An entry of the function catalogue (the server's: GET /api/v1/functions, or a page's own).

| Name | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `[signature]` | `string` | "var(confidence, horizon)" |
| `[description]` | `string` |  |
| `[kind]` | `string` | function, field, keyword, measure |
| `[insert]` | `string` | the text put in (default: the name, with "(" for a function) |

#### `ComboboxOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `source` | `(Array\|function(string, AbortSignal): Promise&lt;Array&gt;)` | the options, or a server search: (text, signal) to options |
| `[value]` | `{value: *, label: string}` |  |
| `[debounceMs=200]` | `number` |  |
| `[minChars=1]` | `number` |  |
| `[allowFree=false]` | `boolean` | typed text that is not an option is a value of its own |
| `[label]` | `string` |  |
| `[placeholder]` | `string` |  |
| `[renderOption]` | `function(Object, HTMLElement): void` | draws an option's content |

#### `DatePickerOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[value]` | `string` | the date (ISO) |
| `[calendar='weekends']` | `(string\|Object)` | the business calendar: US, UK, TARGET, CA, US+UK, a defined one, or a HolidayCalendar |
| `[asOf]` | `string` | the business date T (default today rolled back to a business day) |
| `[businessOnly=true]` | `boolean` | only business days may be picked |
| `[min]` | `string` |  |
| `[max]` | `string` |  |
| `[format='d MMM yyyy']` | `string` | how the field shows the date |
| `[weekStart=1]` | `number` | 0 Sunday, 1 Monday |
| `[shortcuts=true]` | `boolean` | the business date, T-1 and the previous month-end under the calendar |
| `[required=false]` | `boolean` |  |
| `[label]` | `string` | the field's name for a reader |
| `[placeholder]` | `string` |  |

#### `DateRange` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `from` | `?string` | ISO |
| `to` | `?string` | ISO |
| `preset` | `?string` | the preset's id when the range is one (it moves with the business date) |

#### `DateRangeOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `[value]` | `DateRange` |  |
| `[calendar='weekends']` | `(string\|Object)` |  |
| `[asOf]` | `string` | the business date the presets count from |
| `[presets=true]` | `(boolean\|string[])` | true: every preset; or the ids to offer |
| `[businessOnly=false]` | `boolean` | the ends must be business days |
| `[min]` | `string` |  |
| `[max]` | `string` |  |
| `[maxDays]` | `number` | the longest range in calendar days |
| `[weekStart=1]` | `number` |  |
| `[label]` | `string` |  |

#### `FilterField` : `Object`

A field the bar may filter.

| Name | Type | Meaning |
|---|---|---|
| `id` | `string` | the column id the model uses |
| `[label]` | `string` |  |
| `[type='set']` | `('set'\|'number'\|'text'\|'date')` |  |
| `[options]` | `Array` | a set's members (strings or {value, label}) |
| `[members]` | `function(string): (Promise&lt;Array&gt;\|Array)` | a set's members from a server, by search text |

#### `FilterBarOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `fields` | `FilterField[]` |  |
| `[model]` | `Object` | the filters at the start |
| `[bus]` | `Object` | the drishti-core EventBus it emits on (default: the page's shared bus) |
| `[channel]` | `string` | the event name (default FILTER_CHANNEL) |
| `[quick=true]` | `boolean` | the quick-search field |
| `[asOf]` | `string` | the business date date presets count from |
| `[calendar]` | `string` | the business calendar of date fields |
| `[label='Filters']` | `string` |  |

#### `FourEyes` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `submit` | `function({values: Object, changes: Array, comment: string, approver: *}): Promise&lt;{id: string, status: string}&gt;` | files the proposal (the server's approvals); its answer is shown |
| `[approvers]` | `(Array\|function(string, AbortSignal): Promise&lt;Array&gt;)` | who may approve (a list or a server search); none: any approver |
| `[requireComment=true]` | `boolean` |  |

#### `FormOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `schema` | `import('./drishti_schema.js').FieldSchema` | an object schema |
| `[value]` | `Object` | the starting value (default: the schema's defaults) |
| `[onSubmit]` | `function(Object): (Promise&lt;*&gt;\|*)` | the value, when it is valid (and there are no four eyes) |
| `[check]` | `function(Object): Promise&lt;Object&lt;string, string&gt;&gt;` | the server's checks: problems by path |
| `[fourEyes]` | `FourEyes` |  |
| `[submitLabel='Save']` | `string` |  |
| `[readOnly=false]` | `boolean` |  |
| `[layout='stacked']` | `('stacked'\|'columns')` |  |
| `[locale]` | `string` | how numbers are read and shown |

#### `MultiSelectOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `options` | `Array&lt;(string\|number\|{value: *, label: string, detail: string, group: string, disabled: boolean})&gt;` |  |
| `[value]` | `Array&lt;*&gt;` | the picked values |
| `[maxChips=3]` | `number` | chips shown before "+N" |
| `[max]` | `number` | the most that may be picked |
| `[selectAll=true]` | `boolean` | the Select all and Clear buttons |
| `[label]` | `string` |  |
| `[placeholder='Any']` | `string` |  |

#### `FieldSchema` : `Object`

A field of a form schema.

| Name | Type | Meaning |
|---|---|---|
| `type` | `('string'\|'number'\|'integer'\|'boolean'\|'date'\|'daterange'\|'array'\|'object')` |  |
| `[title]` | `string` | the label (default: the key in words) |
| `[description]` | `string` | help under the field |
| `[default]` | `*` |  |
| `[enum]` | `Array` | the values allowed |
| `[enumLabels]` | `string[]` | words for each value |
| `[minimum]` | `number` |  |
| `[maximum]` | `number` |  |
| `[multipleOf]` | `number` |  |
| `[minLength]` | `number` |  |
| `[maxLength]` | `number` |  |
| `[pattern]` | `string` | a regular expression the whole text must match |
| `[patternMessage]` | `string` | the words when it does not ("a book code such as RT-123") |
| `[format]` | `string` | number: a named display format; string: email, textarea, code |
| `[after]` | `string` | date: a field it must be after |
| `[before]` | `string` | date: a field it must be before |
| `[when]` | `Object` | the condition that shows it |
| `[items]` | `FieldSchema` | array: each item's schema |
| `[minItems]` | `number` |  |
| `[maxItems]` | `number` |  |
| `[uniqueItems]` | `boolean` |  |
| `[properties]` | `Object&lt;string, FieldSchema&gt;` | object: its fields |
| `[required]` | `string[]` | object: the keys that must have a value |
| `[order]` | `string[]` | object: the order of its fields (default: as written) |
| `[widget]` | `string` | how it is drawn: text, textarea, select, radio, checkbox, switch, multiselect, combobox, tree, date, daterange, code |
| `[options]` | `Object` | the widget's options (a combobox's source, a tree's nodes, a code editor's mode) |
| `[readOnly]` | `boolean` |  |
| `[placeholder]` | `string` |  |

#### `TreePickerOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `nodes` | `import('./drishti_tree.js').TreeNode[]` | the roots (with children, or hasChildren for the loader) |
| `[loadChildren]` | `function(Object): Promise&lt;Array&gt;` | a node's children from the server, the first time it opens |
| `[search]` | `function(string): Promise&lt;Array&lt;{node: Object, path: Object[]}&gt;&gt;` | search on the server (else the loaded levels) |
| `[multiple=true]` | `boolean` |  |
| `[value]` | `string[]` | the checked ids (compact: a node stands for everything under it) |
| `[inline=false]` | `boolean` | the tree in the page rather than in a panel |
| `[label]` | `string` |  |
| `[placeholder='Everything']` | `string` | the words when nothing is checked |

#### `TreeNode` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `id` | `string` | unique across the tree |
| `label` | `string` |  |
| `[detail]` | `string` | words after the label (a count, a code) |
| `[children]` | `TreeNode[]` | the children when known |
| `[hasChildren]` | `boolean` | the node has children the loader brings (lazy) |
| `[disabled]` | `boolean` |  |

#### `TreeRow` : `Object`

A visible row of the tree.

| Name | Type | Meaning |
|---|---|---|
| `node` | `TreeNode` |  |
| `depth` | `number` | 0 for the roots |
| `branch` | `boolean` | whether it has (or may have) children |
| `expanded` | `boolean` |  |
| `state` | `('checked'\|'mixed'\|'unchecked')` |  |
| `status` | `?string` | 'loading' or the error's words while its children are asked for |
| `match` | `boolean` | whether it matched the search |

## drishti-data

Version 0.1.0. Bundle `static/elements/drishti-data/drishti_data.js`, global `Drishti.elements.data`; requires drishti-core.

### drishti_backoff.js

#### class `Backoff`

Exponential back-off with full jitter: attempt n waits a random time up to min(max, base * factor^n).

##### `new Backoff(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{baseMs: number, maxMs: number, factor: number, jitter: boolean, random: function(): number}` | baseMs default 500; maxMs default 30,000; factor default 2; jitter default true; random: for tests |

##### `Backoff.next()`

The wait before the next attempt (and the attempt counted).

Returns `number`: ms.

##### `Backoff.reset()`

A connection held: the next wait starts small again.

Returns `void`.

#### class `Heartbeat`

A watchdog: beat() on every message; when none comes for timeoutMs, onTimeout runs (once until the next beat).

##### `new Heartbeat(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{timeoutMs: number, onTimeout: function(): void, setTimeout: Function, clearTimeout: Function}` | the timer functions: for tests |

##### `Heartbeat.beat()`

A sign of life: the watch starts again.

Returns `void`.

##### `Heartbeat.stop()`

Stops watching.

Returns `void`.

### drishti_backpressure.js

#### class `TransactionSink`

Feeds a grid from a live feed with backpressure.

##### `new TransactionSink(target, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `target` | `{applyTransactionAsync: function(Object): Promise, setRows: function(Object[]): void, dataChanged: function(Object): void}` | a drishti-grid Grid or GridModel (or anything with those methods) |
| `[o]` | `{getRowId: function(Object): string, maxInFlight: number, highWater: number, onStats: function(Object): void}` | getRowId default row.id; maxInFlight default 1; highWater: pending rows past which the sink says it is behind (default 10,000) |

##### `TransactionSink.push(msg)`

One message of the feed: {type: transaction, add, update, remove} \| {type: rows, rows} \| {type: dataChanged, ...}.

| Parameter | Type | Meaning |
|---|---|---|
| `msg` | `Object` |  |

Returns `void`.

##### `TransactionSink.drained()`

Resolves when everything received has reached the grid.

Returns `Promise&lt;void&gt;`.

#### `pipeToGrid(feed, grid, o)`

Wires a feed (a LiveClient, or any {subscribe}) into a grid through a TransactionSink.

| Parameter | Type | Meaning |
|---|---|---|
| `feed` | `{subscribe: function(function(Object): void): function(): void}` |  |
| `grid` | `Object` | a drishti-grid Grid or GridModel |
| `[o]` | `{getRowId: function(Object): string, maxInFlight: number, highWater: number, onStats: function(Object): void}` |  |

Returns `{sink: TransactionSink, stop: function(): void}`.

### drishti_live-client.js

#### `LIVE_STATES`

The client's states.

#### class `LiveClient`

A live connection with reconnect, heartbeat and resume.

##### `new LiveClient(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `LiveClientOptions` |  |

##### `LiveClient.on(name, fn)`

Listens to the client's own events: state, error, message.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `fn` | `function(Object, string): void` |  |

Returns `function(): void`.

##### `LiveClient.subscribe(fn)`

Receives every message (the connection opens with the first subscriber and closes with the last).

| Parameter | Type | Meaning |
|---|---|---|
| `fn` | `function(Object): void` |  |

Returns `function(): void`: unsubscribes.

##### `LiveClient.open()`

Opens the connection (subscribe does it).

Returns `void`.

##### `LiveClient.reconnect()`

Opens again now (a person pressed Reconnect).

Returns `void`.

##### `LiveClient.close()`

Closes the connection and stops trying.

Returns `void`.

##### `LiveClient.destroy()`

Closes and forgets the page's online listener.

Returns `void`.

#### `parseSse(text)`

Reads Server-Sent Events from text (the fetch transport, and anything else that has the raw stream): the whole events and what is left for the next chunk. An event: its name (default message), its data lines joined by new lines, its id; a comment line alone is a sign of life.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `{events: Array&lt;{event: string, data: string, id: ?string, comment: boolean}&gt;, rest: string}`.

### drishti_arrow.js

#### `ARROW_MEDIA`

The media type of an Arrow IPC stream.

#### `ARROW_VENDOR`

The vendored Arrow JS, relative to the static root.

#### `arrowLib(given)`

Arrow JS: the one given, the page's, or the vendored one loaded now.

| Parameter | Type | Meaning |
|---|---|---|
| `[given]` | `Object` |  |

Returns `Promise&lt;Object&gt;`.

#### `readColumn(vec, field, A)`

One Arrow vector as a column: data (the Arrow buffer where it can be: a Float64Array with no nulls), get(i) (the JSON value), and its type for a grid (number, text, date, boolean).

| Parameter | Type | Meaning |
|---|---|---|
| `vec` | `Object` | an Arrow Vector |
| `field` | `Object` | its Field |
| `A` | `Object` | Arrow JS |

Returns `{data: (Array\|Float64Array), get: function(number): *, type: string, copied: boolean}`.

#### class `DataFrame`

A table, column by column, from Arrow or from JSON: the same API either way.

##### `new DataFrame(p)`

| Parameter | Type | Meaning |
|---|---|---|
| `p` | `{format: string, names: string[], length: number, columns: Object&lt;string, {data: *, get: function(number): *, type: string}&gt;, meta: Object, bytes: number}` |  |

##### `DataFrame.column(name)`

A column's data (a typed array for numbers where it can be).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `*`.

##### `DataFrame.value(name, i)`

One cell, as JSON would have it.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `i` | `number` |  |

Returns `*`.

##### `DataFrame.fields()`

The columns' types for a grid.

Returns `Array&lt;{name: string, type: string}&gt;`.

##### `DataFrame.rows()`

The rows as views: each holds its row number and reads the columns when a property is asked (row.MTM); toObject() makes a plain copy.

Returns `Object[]`.

##### `DataFrame.objects()`

The rows as plain objects (a copy).

Returns `Object[]`.

##### `DataFrame.toGrid(o)`

What a drishti-grid needs: column definitions from the types and the row views.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{id: string, formats: Object&lt;string, string&gt;, headers: Object&lt;string, string&gt;}` | id: the column that identifies a row |

Returns `{columns: Object[], rows: Object[], getRowId: ?function(Object): string}`.

#### `fromArrow(buf, A)`

A frame from Arrow IPC bytes (the metadata drishti.answer, when the server wrote one, is its meta).

| Parameter | Type | Meaning |
|---|---|---|
| `buf` | `(ArrayBuffer\|Uint8Array)` |  |
| `A` | `Object` | Arrow JS |

Returns `DataFrame`.

#### `fromArrowTable(tb, A, bytes)`

A frame from an Arrow Table.

| Parameter | Type | Meaning |
|---|---|---|
| `tb` | `Object` |  |
| `A` | `Object` |  |
| `[bytes]` | `number` |  |

Returns `DataFrame`.

#### `fromJson(json, bytes)`

A frame from JSON rows: records ([{a: 1}]) or a {columns, rows} answer.

| Parameter | Type | Meaning |
|---|---|---|
| `json` | `(Object[]\|{columns: Array, rows: Array})` |  |
| `[bytes]` | `number` |  |

Returns `DataFrame`.

#### `readResponse(r, o)`

Reads a fetch Response as a frame: Arrow when it is Arrow (batch by batch to onBatch when asked), JSON otherwise; a problem (status 400 and up) rejects with the server's words (.status, .code, .detail).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `Response` |  |
| `[o]` | `{Arrow: Object, onBatch: function(number, number): void}` | onBatch(rowsSoFar, batches) |

Returns `Promise&lt;DataFrame&gt;`.

#### `fetchFrame(url, o)`

Fetches a table: Arrow over HTTP where the server can, JSON where it cannot.

| Parameter | Type | Meaning |
|---|---|---|
| `url` | `string` |  |
| `[o]` | `{method: string, body: Object, headers: Object, fetch: Function, Arrow: Object, signal: AbortSignal, onBatch: function(number, number): void}` |  |

Returns `Promise&lt;DataFrame&gt;`.

#### class `ArrowClient`

A server's data, read the best way it offers: Flight over HTTP (a ticket POSTed to its do-get address) when its capabilities say so, Arrow over HTTP otherwise.

##### `new ArrowClient(o)`

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{capabilitiesUrl: string, flight: (boolean\|{doGet: string}), fetch: Function, Arrow: Object, headers: Object}` | capabilitiesUrl: where the server says what it offers ({flightHttp: {doGet: url}}); flight: false never asks, an object names it |

##### `ArrowClient.capabilities()`

What the server offers: {flightHttp: ?{doGet: string}} (asked once; a server that does not answer offers Arrow over HTTP only).

Returns `Promise&lt;{flightHttp: ?{doGet: string}}&gt;`.

##### `ArrowClient.read(req)`

Reads a request: {ticket} through Flight over HTTP when offered, else {url} (and body) as Arrow over HTTP.

| Parameter | Type | Meaning |
|---|---|---|
| `req` | `{ticket: Object, url: string, method: string, body: Object, onBatch: function(number, number): void, signal: AbortSignal}` |  |

Returns `Promise&lt;DataFrame & {via: string}&gt;`.

### drishti_sources.js

#### `rowRequest(r)`

A request with every default filled in.

| Parameter | Type | Meaning |
|---|---|---|
| `[r]` | `Object` |  |

Returns `RowRequest`.

#### `memorySource(rows, o)`

Rows in the page as a source: filtered, sorted, grouped (a level's members with their counts and sums) and windowed here.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |
| `[o]` | `{delay: number, columns: Array&lt;{id: string, field: string, comparator: Function}&gt;}` | delay: ms before answering (a fixture that behaves like a server); columns: what the quick filter searches (default every property of the first row) |

Returns `DataSource & {calls: RowRequest[], setRows: function(Object[]): void}`.

#### `restSource(url, o)`

A REST service as a source, a page at a time.

| Parameter | Type | Meaning |
|---|---|---|
| `url` | `string` |  |
| `[o]` | `{method: ('POST'\|'GET'), fetch: Function, headers: Object, map: function(Object, RowRequest): {rows: Object[], rowCount: number}}` | POST (default): the request as JSON; GET: offset, limit, sort (colId:asc,colId:desc) and filter (JSON) as query parameters; map: the answer to {rows, rowCount} (default: {rows, rowCount} or {items, total}) |

Returns `DataSource`.

#### `serverSideSource(url, o)`

Drishti's server-side rows as a source: the grid's request POSTed as it is, {rows, rowCount} back (groups and pivots on the server).

| Parameter | Type | Meaning |
|---|---|---|
| `url` | `string` |  |
| `[o]` | `{fetch: Function, headers: Object}` |  |

Returns `DataSource`.

#### `arrowSource(src, o)`

An Arrow table as a source: fetched once (or given as a DataFrame) and served from memory as row views; with paged, each window is its own request (offset and limit parameters) answered as Arrow.

| Parameter | Type | Meaning |
|---|---|---|
| `src` | `(string\|Object)` | a url or a DataFrame |
| `[o]` | `{paged: boolean, fetch: Function, Arrow: Object, rowCount: function(Object): number}` | rowCount: from a page's frame (default its meta.rowCount) |

Returns `DataSource & {frame: function(): Promise&lt;Object&gt;}`.

#### `liveSource(source, feed)`

A source with a live feed: getRows from the source, subscribe from the feed (a LiveClient), so a grid connects both.

| Parameter | Type | Meaning |
|---|---|---|
| `source` | `DataSource` |  |
| `feed` | `{subscribe: function(function(Object): void): function(): void}` |  |

Returns `DataSource`.

#### `readAll(source, req, o)`

Every row a request matches, a page at a time up to a limit (what a chart reads from a source).

| Parameter | Type | Meaning |
|---|---|---|
| `source` | `DataSource` |  |
| `[req]` | `Object` | the sort, filter and groups (startRow and endRow are set here) |
| `[o]` | `{pageSize: number, limit: number}` | pageSize default 5,000; limit default 100,000 rows |

Returns `Promise&lt;{rows: Object[], rowCount: number, truncated: boolean}&gt;`.

### drishti_index.js

#### `live(o)`

A live client (new LiveClient(o)).

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `import('./drishti_live-client.js').LiveClientOptions` |  |

Returns `import('./drishti_live-client.js').LiveClient`.

### Types of drishti-data

#### `LiveClientOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `url` | `(string\|function(): string)` | the feed's address (http(s) for SSE, ws(s) for a WebSocket), or a function asked at every connect |
| `[transport]` | `('sse'\|'websocket'\|'fetch')` | default: websocket for ws:// and wss://, else sse; fetch: SSE read with fetch |
| `[headers]` | `(Object&lt;string, string&gt;\|function({status: number}): (Object&lt;string, string&gt;\|Promise&lt;Object&lt;string, string&gt;&gt;))` | fetch: the request's headers, or a function of the last failure (status 401 after a refused token) that answers them |
| `[credentials='same-origin']` | `string` | fetch: the request's credentials |
| `[eventField]` | `string` | each message gets its SSE event name under this field (when it has none of its own) |
| `[fetch]` | `Function` | fetch: for tests |
| `[heartbeatMs=45000]` | `number` | no message for this long: reconnect (0: no watchdog) |
| `[pingMs]` | `number` | WebSocket: send {"type":"ping"} this often (default heartbeatMs / 3) |
| `[backoff]` | `{baseMs: number, maxMs: number, factor: number, jitter: boolean}` |  |
| `[resumeParam='lastEventId']` | `string` | SSE: the parameter that carries the last event id |
| `[events]` | `string[]` | SSE event names that carry messages (default ['message']) |
| `[withCredentials=false]` | `boolean` |  |
| `[protocols]` | `string[]` | WebSocket sub-protocols |
| `[EventSource]` | `Function` | for tests or a polyfill |
| `[WebSocket]` | `Function` | for tests |
| `[window]` | `Object` | the window (online and offline events; default the page's) |
| `[setTimeout]` | `Function` | the clock's timer functions (a fake clock in tests; default the page's or the worker's) |
| `[clearTimeout]` | `Function` |  |

#### `RowRequest` : `Object`

A request for rows (the grid's BlockRequest).

| Name | Type | Meaning |
|---|---|---|
| `startRow` | `number` |  |
| `endRow` | `number` | exclusive |
| `[sortModel]` | `Array&lt;{colId: string, sort: string}&gt;` |  |
| `[filterModel]` | `Object&lt;string, Object&gt;` |  |
| `[quickFilter]` | `string` |  |
| `[rowGroupCols]` | `string[]` |  |
| `[groupKeys]` | `string[]` |  |
| `[valueCols]` | `Array&lt;{colId: string, aggFunc: string}&gt;` |  |
| `[signal]` | `AbortSignal` |  |

#### `DataSource` : `Object`

A data source.

| Name | Type | Meaning |
|---|---|---|
| `getRows` | `function(RowRequest): Promise&lt;{rows: Object[], rowCount: number}&gt;` |  |
| `capabilities` | `{sort: boolean, filter: boolean, group: boolean, pivot: boolean, live: boolean}` |  |
| `[subscribe]` | `function(function(Object): void): function(): void` | live messages (a liveSource) |

## drishti-chart

Version 0.2.0. Bundle `static/elements/drishti-chart/drishti_chart.js`, global `Drishti.elements.chart`; requires drishti-core.

### drishti_chart-spec.js

#### `registerKind(name, def, o)`

Registers a kind (a domain library's too). A name already taken is refused unless replace is said.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |
| `def` | `KindDef` |  |
| `[o]` | `{replace: boolean}` |  |

Returns `void`.

#### `kindOf(name)`

A registered kind, or null.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `?KindDef`.

#### `kindList()`

The registered kinds in the order they were registered: name, label and family.

Returns `Array&lt;{name: string, label: string, family: string, cartesian: boolean}&gt;`.

#### `KIND_ALIASES`

The aliases a spec may use for a kind.

#### `checkSpec(spec)`

A spec's problems, each with its path: an unknown kind, a missing field, a condition that does not read, a value of the wrong kind.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |

Returns `Array&lt;{path: string, message: string}&gt;`.

#### `normalizeSpec(spec)`

A spec ready to draw: the kind's alias resolved, x, y and value as objects, the defaults filled in (legend at the bottom, click to Explain, every export, the data table on). The spec given is not changed.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |

Returns `Object`.

#### `specText(spec, o)`

The spec as people read it: two-space JSON with the keys in a stable order (kind, title, data first); inline rows shortened to their count so the spec stays readable beside the chart.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `[o]` | `{rows: boolean}` | rows: keep inline rows whole (copy spec does) |

Returns `string`.

### drishti_chart-data.js

#### `rowsOf(data)`

The rows of a spec's data as objects.

| Parameter | Type | Meaning |
|---|---|---|
| `data` | `*` | an array of objects, {rows: [...]} (objects, or arrays with columns), {values: [...]} or a server answer {columns: [names or {name}], rows: [[...]]} |

Returns `Object[]`.

#### `fieldOf(enc)`

The name of the field an encoding names: "DESK", {field: "DESK"}, or null.

| Parameter | Type | Meaning |
|---|---|---|
| `enc` | `*` |  |

Returns `?string`.

#### `num(v)`

A number, or null when the value is not one.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `?number`.

#### `members(rows, field, order)`

The members of a field in the order they first appear (or the order given; members not in it follow).

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |
| `field` | `string` |  |
| `[order]` | `string[]` |  |

Returns `string[]`.

#### `sumBy(rows, key, field)`

The sum of a field over rows grouped by keys (a wide table from long rows): {key: sum}.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |
| `key` | `function(Object): string` |  |
| `field` | `string` |  |

Returns `Map&lt;string, number&gt;`.

#### `bin(values, o)`

The bins of a histogram: equal widths over the values' range (or the extent given), each with its count.

| Parameter | Type | Meaning |
|---|---|---|
| `values` | `number[]` |  |
| `[o]` | `{bins: number, extent: number[]}` | bins default 20 (Sturges' rule when "auto") |

Returns `Array&lt;{x0: number, x1: number, count: number}&gt;`.

#### `quantile(sorted, p)`

A quantile of sorted numbers (linear between the two nearest, as R's type 7).

| Parameter | Type | Meaning |
|---|---|---|
| `sorted` | `number[]` | ascending |
| `p` | `number` | 0 to 1 |

Returns `?number`.

#### `boxStats(values)`

The five figures of a box (whiskers at the furthest values within 1.5 IQR) and the values beyond them.

| Parameter | Type | Meaning |
|---|---|---|
| `values` | `number[]` |  |

Returns `?{box: number[], outliers: number[], n: number}`: box: [low whisker, Q1, median, Q3, high whisker].

#### `quantileBreaks(values, classes)`

The class breaks of a quantile scale: classes - 1 breaks, each the value at its quantile of the values with a figure.

| Parameter | Type | Meaning |
|---|---|---|
| `values` | `Array&lt;?number&gt;` | null: no data |
| `[classes=5]` | `number` |  |

Returns `{breaks: number[], min: ?number, max: ?number}`.

#### `classOf(v, breaks)`

The class of a value under quantile breaks: 0 to breaks.length, or -1 for no data.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |
| `breaks` | `number[]` |  |

Returns `number`.

#### `compileCondition(text)`

Reads a condition into a function of a row: comparisons (&lt; &lt;= &gt; &gt;= == != === !==) of numbers, strings and fields (a bare name, or datum.name and datum['name'] as Vega-Lite writes them), arithmetic (+ - * /, unary minus), !, && and \|\|, and brackets. Nothing else (no calls, no assignments): an unreadable condition is an error with its place.

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` | for example "hypothetical &lt; -var99" or "datum.MTM &lt; 0" |

Returns `function(Object): *`.

#### `conditionFields(text)`

The fields a condition reads (for the data table and the spec's checks).

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `string[]`.

### drishti_chart-theme.js

#### `CHART_DENSITIES`

The densities: font size, the chart's inner padding, the symbol size and the line width.

#### `blend(a, b, f)`

A colour between two (each a hex or rgb() colour), as rgb().

| Parameter | Type | Meaning |
|---|---|---|
| `a` | `string` |  |
| `b` | `string` |  |
| `f` | `number` | 0 is a, 1 is b |

Returns `string`.

#### `alpha(c, a)`

A colour with an alpha, as rgba().

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `string` |  |
| `a` | `number` | 0 to 1 |

Returns `string`.

#### `chartTheme(o)`

The chart's theme: the tokens it draws with, the categorical palette, the tones a spec names, the ramps of scales and the density.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{element: Element, theme: string, density: string, tokens: Object&lt;string, string&gt;}` | element: read the page's computed tokens on it; theme: read a theme of the table instead; tokens: given outright (tests) |

Returns `ChartTheme`.

#### `toneColour(th, tone, i)`

A tone a spec names (good, bad, warn, accent, muted ...) as its colour, or the colour itself, or the palette's nth.

| Parameter | Type | Meaning |
|---|---|---|
| `th` | `ChartTheme` |  |
| `tone` | `*` | a name, a colour, or a number (the palette's index) |
| `[i]` | `number` | the palette's index when tone is empty |

Returns `string`.

#### `rampColour(ramp, v, lo, hi)`

The colour of a value on a ramp (five colours) between lo and hi.

| Parameter | Type | Meaning |
|---|---|---|
| `ramp` | `string[]` |  |
| `v` | `number` |  |
| `lo` | `number` |  |
| `hi` | `number` |  |

Returns `string`.

#### `formatterFor(f)`

A formatter for a spec's format: a drishti-core named format (amount0, pct2, compact ...), money (compact, two decimals), number, integer, percent, bp, date, {currency: 'USD'}, or a function. Nothing given: a compact figure for large values, else up to two decimals.

| Parameter | Type | Meaning |
|---|---|---|
| `f` | `*` |  |

Returns `function(*): string`.

#### `textStyle(th, colour)`

The text style of the chart's words at the density.

| Parameter | Type | Meaning |
|---|---|---|
| `th` | `ChartTheme` |  |
| `[colour]` | `string` | default: faint |

Returns `Object`.

#### `axisOf(th, base, fmt)`

An axis of the theme: its line, ticks, split lines and labels with their formatter.

| Parameter | Type | Meaning |
|---|---|---|
| `th` | `ChartTheme` |  |
| `base` | `Object` | the ECharts axis (type, data, name ...) |
| `[fmt]` | `function(*): string` | the labels' formatter (value axes) |

Returns `Object`.

### drishti_chart-kinds.js

#### `rowId(spec, row, i)`

The id of a row (the spec's id field, else its index).

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `row` | `Object` |  |
| `i` | `number` |  |

Returns `string`.

#### `seriesDefs(spec, rows)`

The series a spec draws over x: a list of fields (wide rows), one series per member of a field ({by}: long rows, y.field the value), or y's field alone.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `rows` | `Object[]` |  |

Returns `Array&lt;{name: string, field: string, test: ?function(Object): boolean, def: Object}&gt;`.

#### `annotationMarks(spec, rows, th, o)`

The annotations of a spec as ECharts mark lines, mark areas and mark points of a series (the first one drawn).

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `rows` | `Object[]` |  |
| `th` | `Object` | the chart theme |
| `o` | `{horizontal: boolean, fmt: function(*): string, xfmt: function(*): string}` |  |

Returns `Object} {markLine, markArea, markPoint`: for the series.

#### `buildSeriesChart(spec, rows, env)`

Line, area and bar: categories (or time, or values) on one axis, one series per field or member, with stacks, percent stacks, negated series, dashed styles, a right-hand axis, conditional marks and annotations.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `rows` | `Object[]` |  |
| `env` | `{theme: Object, fmt: function(*): string, xfmt: function(*): string}` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildScatter(spec, rows, env)`

Scatter and bubble: two value axes, a series per colour member, a size field scaled by area for bubbles.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildHistogram(spec, rows, env)`

Histogram: the values of a field in equal bins (a value axis, so lines at a value, VaR's for one, sit where they belong), with the tail beyond a value in its own tone.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | bins (a count or "auto"), binned (the server's bins {edges, counts}: drawn as they are, no rows needed), tail {below\|above: a number or a field of the first row, tone} |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildWaterfall(spec, rows, env)`

Waterfall: each step's change from the one before, as floating bars, with totals (the first, the last, or rows marked by a field) drawn from zero; increases and decreases in their tones.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | category, value, totals (['first', 'last'] or {field}), up and down (tones; default good and bad) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildBoxplot(spec, rows, env)`

Box plot: the five figures of a field's values per category (whiskers at 1.5 IQR) and the values beyond them as points.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | category, value |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildHeatmap(spec, rows, env)`

Heatmap: a value at each (x, y) pair of categories, coloured on a sequential or diverging scale (diverging is centred on mid, 0 by default), with the legend's scale under it.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | x, y, value, scale {type: 'sequential'\|'diverging', mid}, labels |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildCandlestick(spec, rows, env)`

Candlestick: open, close, low and high of each date, up in the good tone and down in the bad one.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | x (the date), open, close, low, high (fields) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildLayers(spec, rows, env, kindOf, rowsOfSpec)`

Layers: several cartesian specs over the same axes (a strip of dots with a tick at each median; bars with a line), the data and the encodings a layer leaves out taken from the spec around it, categories the union of every layer's.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | layers: [specs] |
| `rows` | `Object[]` | the spec's rows (a layer with no data of its own uses them) |
| `env` | `Object` |  |
| `kindOf` | `function(string): ?Object` | the kind registry's lookup |
| `rowsOfSpec` | `function(Object): Object[]` | a layer's rows |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `registerAxisKinds()`

Registers the kinds of this module (the library's entry calls it once).

Returns `void`.

### drishti_chart-kinds2.js

#### `buildPie(spec, rows, env)`

Pie and donut: a value per category (summed), the largest first unless the spec keeps its order; small slices beyond top folded into "Other".

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | category, value, top (default 12), sort (false: as given) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildGauge(spec, rows, env)`

Gauge: one figure against its range, with bands in tones (a limit's green, amber and red).

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | value ({field} of the first row, or {value}), min, max, bands [{to, tone}], format |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildTreemap(spec, rows, env)`

Treemap: a value nested by levels of fields (desk, then book), each top-level member in its palette colour.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | levels (fields, outermost first) or category, value |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `buildSankey(spec, rows, env)`

Sankey: flows from a source to a target with a value (the links summed), nodes in palette colours.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | source, target, value (fields) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `import('./drishti_chart-spec.js').BuiltChart`.

#### `registerOtherKinds()`

Registers the kinds of this module (the library's entry calls it once).

Returns `void`.

### drishti_chart-geo.js

#### `equalEarth(lon, lat)`

The Equal Earth projection (Šavrič, Patterson and Jenny, 2018): equal area, the world's shapes kept pleasant.

| Parameter | Type | Meaning |
|---|---|---|
| `lon` | `number` | degrees |
| `lat` | `number` | degrees |

Returns `number[]`: [x, y] (y down).

#### `albers(o)`

An Albers equal-area conic projection with two standard parallels.

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{p1: number, p2: number, p0: number, l0: number}` | standard parallels, the origin's latitude and the central meridian |

Returns `function(number, number): number[]`: (lon, lat) to [x, y] (y down).

#### `plateCarree(midLat, l0)`

A plate carrée scaled at a latitude (for a country where a conic does not fit).

| Parameter | Type | Meaning |
|---|---|---|
| `midLat` | `number` |  |
| `l0` | `number` | the central meridian |

Returns `function(number, number): number[]`.

#### `projectGeometry(g, fn)`

A projected copy of a GeoJSON geometry (Polygon, MultiPolygon, Point).

| Parameter | Type | Meaning |
|---|---|---|
| `g` | `Object` |  |
| `fn` | `function(number, number): number[]` |  |

Returns `Object`.

#### `bounds(pts)`

The box of points [[x, y], ...]: [minX, minY, maxX, maxY].

| Parameter | Type | Meaning |
|---|---|---|
| `pts` | `number[][]` |  |

Returns `number[]`.

#### `coreExtent(features)`

The extent a country's map is fitted to (lon/lat): its rings near the area-weighted centre, so far-away parts (France's overseas regions) do not shrink the mainland; they stay on the map, reachable by panning.

| Parameter | Type | Meaning |
|---|---|---|
| `features` | `Object[]` | GeoJSON features (polygons; points ignored) |

Returns `{lon0: number, lat0: number, lat1: number, lonMin: number, lonMax: number}`.

#### `countryProjection(iso3, features)`

The projection of a country's regions: Albers for the United States and Canada (Statistics Canada's parallels), a conic fitted to the country's core extent otherwise, a scaled plate carrée where a conic cannot fit (the parallels on both sides of the equator).

| Parameter | Type | Meaning |
|---|---|---|
| `iso3` | `string` |  |
| `features` | `Object[]` |  |

Returns `{name: string, project: function(number, number): number[], extent: Object}`.

#### `US_INSETS`

The insets of the United States: Alaska and Hawaii, each in its own Albers, scaled and set under the lower 48's south-west.

#### `prepareRegions(geo)`

A country's regions projected: polygons named by their ISO 3166-2 code (so the engine's map matches data by code), the small ones as points, the extent to fit, and for the United States Alaska and Hawaii moved into insets with their frames.

| Parameter | Type | Meaning |
|---|---|---|
| `geo` | `Object` | regions/&lt;ISO3&gt;.json |

Returns `RegionGeo`.

#### `prepareWorld(geo, pts)`

The world projected (Equal Earth): countries named by ISO alpha-3, the small ones (world-points.json) as points.

| Parameter | Type | Meaning |
|---|---|---|
| `geo` | `Object` | world-countries.json |
| `[pts]` | `Array&lt;{iso3: string, iso2: string, name: string, lon: number, lat: number}&gt;` | world-points.json |

Returns `RegionGeo`.

#### `findPlace(geo, code)`

The place a code or name means on a level: an ISO alpha-3 or alpha-2 country, an ISO 3166-2 region ("US-CA"), a region's code without its country ("CA" on the United States' map) or an English name; null when the map has no such place.

| Parameter | Type | Meaning |
|---|---|---|
| `geo` | `RegionGeo` |  |
| `code` | `*` |  |

Returns `?{code: string, name: string}`.

#### `createGeoLoader(o)`

A loader of the shapes: the world once, each country's regions the first time they are asked, cached for the session (a failed load is forgotten, so it can be asked again).

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{base: string, fetchJson: function(string): Promise&lt;Object&gt;}` | base: the maps folder ("/static/vendor/echarts/maps/"); fetchJson: how to read a file (default: fetch, same origin) |

Returns `{world: function(): Promise&lt;RegionGeo&gt;, regions: function(string): Promise&lt;RegionGeo&gt;, hasRegions: function(string): boolean, loads: string[]}`.

#### `holdAntimeridian(g)`

A copy of a geometry whose rings cross the antimeridian (a ring spanning more than 180 degrees of longitude, as Fiji's and Russia's do at 1:110m) held on the side most of the ring is on, so no ring draws a line across the whole map.

| Parameter | Type | Meaning |
|---|---|---|
| `g` | `Object` |  |

Returns `Object`.

#### `fitView(geo, width, height)`

The view that shows a level's fit box in a drawing of a size: the centre and zoom of the map, whose unzoomed view is the box of everything drawn fitted to the drawing (the aspect kept).

| Parameter | Type | Meaning |
|---|---|---|
| `geo` | `RegionGeo` |  |
| `[width=1]` | `number` | the drawing's width |
| `[height=1]` | `number` | the drawing's height |

Returns `{center: number[], zoom: number}`.

### drishti_chart-map.js

#### `mapMetrics(spec)`

The metrics a map offers (its picker): the spec's metrics, or its value alone.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |

Returns `Array&lt;{field: string, title: string, format: *}&gt;`.

#### `placeValues(spec, rows, geo, metric)`

Each place's value on a level: the rows matched to places (by the place field: an ISO code or a name) and summed per place; places without a figure keep null (no data); rows that match no place are counted.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `rows` | `Object[]` |  |
| `geo` | `import('./drishti_chart-geo.js').RegionGeo` |  |
| `metric` | `{field: string}` |  |

Returns `{places: Array&lt;{code: string, name: string, point: boolean, v: ?number, r: number[], ids: string[]}&gt;, unmatched: number}`.

#### `mapScale(places, th, scale)`

The choropleth's scale: quantile classes (each with its range, colour and count of places, and the no-data count) or a continuous ramp; colourOf gives a value's colour, classOf its class.

| Parameter | Type | Meaning |
|---|---|---|
| `places` | `Array&lt;{v: ?number}&gt;` |  |
| `th` | `Object` | the chart theme |
| `[scale]` | `{type: ('quantile'\|'continuous'), classes: number}` |  |

Returns `{type: string, classes: Array&lt;{from: number, to: number, colour: string, count: number}&gt;, nodata: number, min: ?number, max: ?number, colourOf: function(?number): ?string, classOf: function(?number): number}`.

#### `ranking(places, n)`

The top places by value (largest first), with their share of the level's total.

| Parameter | Type | Meaning |
|---|---|---|
| `places` | `Array&lt;{code: string, name: string, v: ?number}&gt;` |  |
| `[n=10]` | `number` |  |

Returns `Array&lt;{code: string, name: string, v: number, share: number, rank: number}&gt;`.

#### `buildMap(spec, rows, geo, env)`

The map as the engine draws it, with what the view shows around it.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | normalised |
| `rows` | `Object[]` |  |
| `geo` | `import('./drishti_chart-geo.js').RegionGeo` | projected shapes of the level |
| `env` | `{theme: Object, mapName: string, metric: Object, highlight: ?number, selected: string[], hatch: *, width: number, height: number}` | mapName: the name the shapes are registered under; highlight: a legend class to keep bright; selected: place codes outlined; hatch: the no-data fill (a pattern; default the theme's no-data colour); width and height: the drawing's size in pixels (the view fits the level to it) |

Returns `Object} {option, places, scale, ranking, table, summary, unmatched, legend`.

#### `registerMapKind()`

Registers the map kind: its build needs the shapes, so the view draws it (drishti_map-view.js); the registry lists it for the studio and the spec's checks.

Returns `void`.

### drishti_chart-vega.js

#### `colourTone(c)`

A colour as the tone people meant: red is bad, green good, orange or amber accent, grey muted, anything else link.

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `string` | a CSS colour (hex or a name) |

Returns `string`.

#### `d3Format(f)`

A d3 number format as drishti-core's named format (or a format object): ",.0f" amount0, ",.2f" amount2, ".0%" pct0, ".2%" pct2, "~s" and ".2s" compact, "$,.0f" amount0; null when there is no near match.

| Parameter | Type | Meaning |
|---|---|---|
| `f` | `string` |  |

Returns `*`.

#### `fromVegaLite(vl, o)`

A Vega-Lite specification as a drishti-chart spec, with every part that did not translate.

| Parameter | Type | Meaning |
|---|---|---|
| `vl` | `Object` | the Vega-Lite specification (data inline; a url is refused) |
| `[o]` | `{rows: Object[], title: string}` | rows: the data when the specification has none (a BI visual's own rows) |

Returns `{spec: ?Object, unsupported: string[], refused: ?string}`: refused: why nothing is drawn (a url, nothing to draw, or the features that do not translate, each with what to use instead); a spec is only given when the whole specification translated.

#### `vegaLiteAlternative(path)`

What to use instead of an unsupported Vega-Lite feature, from its path in the specification.

| Parameter | Type | Meaning |
|---|---|---|
| `path` | `string` | a path fromVegaLite listed ("$.facet", "$.layer[1].encoding.text", "$.transform[0]", "$.mark (text)") |

Returns `string`: the alternative, in words.

#### `vegaLiteRefusal(unsupported)`

The sentence a refused specification shows: each unsupported feature with what to use instead.

| Parameter | Type | Meaning |
|---|---|---|
| `unsupported` | `string[]` | the paths fromVegaLite listed |

Returns `string`.

#### `vegaLiteIn(host, vl, o)`

A Vega-Lite specification drawn in a host by drishti-chart, or refused there in words (each unsupported feature with what to use instead): never drawn wrong. The refusal is a status paragraph (class dc-refused, data-refused holding the unsupported paths).

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `vl` | `Object` | the Vega-Lite specification |
| `[o]` | `Object` | rows (the data when the specification has none), title, and chartIn's options |

Returns `{chart: ?Object, refused: ?string, unsupported: string[], spec: ?Object}`.

### drishti_chart-parts.js

#### `valueText(p, fmt)`

A mark's figure in words, by its series type (a bar's value, a box's five figures, a bin's range and count, a flow's value).

| Parameter | Type | Meaning |
|---|---|---|
| `p` | `Object` | an engine event's params (seriesType, value, data, percent) |
| `fmt` | `function(*): string` | the value format |

Returns `string`.

#### `tooltipElement(doc, params, o)`

The tooltip: a heading (the axis value or the mark's name) and one line per series with its swatch; an element, built with the DOM.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `params` | `(Object\|Object[])` | the engine's params (an array for an axis tooltip) |
| `o` | `{fmt: function(*): string, xfmt: function(*): string, names: function(Object): string}` |  |

Returns `HTMLElement`.

#### `dataTable(doc, table, caption, o)`

The data table: a caption (the chart's description), the columns' headings and every row formatted as on the chart; the first column is the row's heading.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `table` | `{columns: Array&lt;{header: string, format: *}&gt;, rows: Array&lt;Array&lt;*&gt;&gt;}` |  |
| `caption` | `string` |  |
| `[o]` | `{limit: number}` | limit: the most rows drawn (default 2,000; the CSV has every row) |

Returns `HTMLElement`.

#### `legendElement(doc, entries, o)`

The legend's entries: buttons that hide and show their series (aria-pressed), or plain keys where the entries are not series.

| Parameter | Type | Meaning |
|---|---|---|
| `doc` | `Document` |  |
| `entries` | `Array&lt;{name: string, colour: string, style: string}&gt;` |  |
| `o` | `{toggles: boolean, hidden: Set&lt;string&gt;, onToggle: function(string): void}` |  |

Returns `HTMLElement`.

#### `markSelection(option, ids, th)`

Marks the selected rows' marks: an outline over each (a cartesian chart), or the slices selected (a pie). Rows are matched by id.

| Parameter | Type | Meaning |
|---|---|---|
| `option` | `Object` | the engine's option (changed in place) |
| `ids` | `Set&lt;string&gt;` | the selected row ids |
| `th` | `Object` | the chart theme |

Returns `number`: how many marks are marked.

#### `addZoom(option, spec, built, th)`

The overview window of a cartesian chart: an 'inside' zoom on the wheel and a slider with the series' shape under the chart (the card adds drag-to-zoom: dragBrush). A window of indexes ([150, 250]) or values.

| Parameter | Type | Meaning |
|---|---|---|
| `option` | `Object` | changed in place |
| `spec` | `Object` |  |
| `built` | `Object` | the kind's answer (its table gives an index's value) |
| `th` | `Object` |  |

Returns `boolean`: whether the chart zooms.

#### `dragBrush(engine, active, colour)`

Drag-to-zoom on a chart's plot: a drag across draws a band and zooms the x axis to it when the mouse is let go (a click, with no drag, stays a click on a mark).

| Parameter | Type | Meaning |
|---|---|---|
| `engine` | `Object` | the chart's engine instance |
| `active` | `function(): boolean` | whether the chart zooms now |
| `colour` | `string` | the band's fill |

Returns `void`.

#### `svgOf(E, option, width, height)`

The chart as SVG text, drawn off screen by the engine's server-side renderer at the card's size (the export's SVG).

| Parameter | Type | Meaning |
|---|---|---|
| `E` | `Object` | the engine (echarts) |
| `option` | `Object` |  |
| `width` | `number` |  |
| `height` | `number` |  |

Returns `string`.

### drishti_map-view.js

#### `MAP_MODES`

A map's modes: ranking (g3 A, the default) and table (g3 B).

#### `geoLoader(base, fetchJson)`

The shared loader of a maps folder (one per folder in a page: every map of the page shares its cache).

| Parameter | Type | Meaning |
|---|---|---|
| `base` | `string` |  |
| `[fetchJson]` | `function(string): Promise&lt;Object&gt;` |  |

Returns `Object`.

#### class `MapView`

The map's view inside a Chart: the level, its shapes and rows, the legend, ranking, table, breadcrumb and drill.

##### `new MapView(chart)`

| Parameter | Type | Meaning |
|---|---|---|
| `chart` | `Object` | the Chart it draws in |

##### `MapView.remount()`

Attaches to the card again (after the chart's mode changed): the breadcrumb, the metric picker, the side column or the table, events.

Returns `MapView`.

##### `MapView.metric()`

The metric shown (the picker's).

Returns `Object`.

##### `MapView.load()`

Loads the spec's level (the world, or a country's regions) and draws it.

Returns `Promise&lt;void&gt;`.

##### `MapView.drill(iso3)`

Opens a country's first-level regions (loaded once, then from the cache).

| Parameter | Type | Meaning |
|---|---|---|
| `iso3` | `string` |  |

Returns `Promise&lt;void&gt;`.

##### `MapView.say(text)`

A short message at the card's foot (a drill's regions loaded, a country without a regions file).

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |

Returns `void`.

##### `MapView.back()`

Back to the world.

Returns `Promise&lt;void&gt;`.

##### `MapView.draw()`

Draws the level: the map, the legend, the ranking or the table, the breadcrumb and the status line.

Returns `void`.

##### `MapView.pick(code, o)`

Selects a place: its figure in the side column or card, its rows to a linked grid.

| Parameter | Type | Meaning |
|---|---|---|
| `code` | `string` |  |
| `[o]` | `{explain: boolean}` |  |

Returns `void`.

##### `MapView.setMode(mode)`

Switches the mode: ranking (g3 A) or table (g3 B).

| Parameter | Type | Meaning |
|---|---|---|
| `mode` | `('ranking'\|'table')` |  |

Returns `void`.

##### `MapView.table()`

The table of places (the CSV export).

Returns `{columns: Object[], rows: Array&lt;Array&lt;*&gt;&gt;}`.

##### `MapView.svgOption()`

What the SVG export changes: the hatching (a canvas pattern) drawn as the plain no-data colour.

Returns `Object`.

### drishti_chart-studio.js

#### class `Studio`

The studio mode's frame around a Chart: the kind rail, the table under the chart, the spec and its options.

##### `new Studio(chart)`

| Parameter | Type | Meaning |
|---|---|---|
| `chart` | `Object` | the Chart (its card goes in the middle) |

##### `Studio.update(built)`

Draws the rail, the options and the spec for what the chart shows now.

| Parameter | Type | Meaning |
|---|---|---|
| `built` | `Object` | the kind's answer (its table goes under the chart) |

Returns `void`.

##### `Studio.destroy()`

Leaves the studio (the chart goes back to a card).

Returns `void`.

### drishti_chart-view.js

#### `CHART_EVENTS`

Every event a chart emits (on chart.events and as DOM events drishti:&lt;kebab-name&gt; on its element).

#### `CHART_MODES`

The modes of a chart: card (g2 A, the default) and studio (g2 B).

#### `SELECTION_EVENT`

The selection's event on drishti-core's bus: {channel, ids, source}.

#### class `Chart`

A chart: the spec drawn in its card (or studio), with its tools, Explain, selection, export and live data.

##### `new Chart(el, spec, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` | where the chart lives |
| `spec` | `Object` | the chart's spec (drishti_chart-spec.js) |
| `[o]` | `ChartOptions` |  |

##### `Chart.setSpec(spec, o)`

Replaces the spec (the studio's kind rail does; a page may too) and draws it.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` |  |
| `[o]` | `{draw: boolean}` | draw default true |

Returns `Promise&lt;void&gt;`.

##### `Chart.draw()`

Draws the chart again from its rows (after a theme, density, data or selection change; live updates come here throttled).

Returns `void`.

##### `Chart.emit(name, detail)`

Emits an event on chart.events and as a DOM event drishti:&lt;kebab-name&gt; on the element.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` | one of CHART_EVENTS |
| `[detail]` | `Object` |  |

Returns `void`.

##### `Chart.markContext(p)`

The context of a mark, as the Explain hook gets it.

| Parameter | Type | Meaning |
|---|---|---|
| `p` | `Object` | the engine's event params (seriesIndex, dataIndex, data, name, value) |

Returns `Object} {kind, title, series, field, value, text, category, rows, ids`.

##### `Chart.setSelection(ids, o)`

Selects rows by id: their marks are outlined; tells the linked grid (unless silent).

| Parameter | Type | Meaning |
|---|---|---|
| `ids` | `string[]` |  |
| `[o]` | `{silent: boolean}` | silent: a selection that came from the link (not sent back) |

Returns `void`.

##### `Chart.selection()`

The ids selected.

Returns `string[]`.

##### `Chart.explain(ctx)`

Explains a mark through the hook, in the side sheet (the grid's drawing of an ExplainResult).

| Parameter | Type | Meaning |
|---|---|---|
| `ctx` | `Object` | a mark's context (markContext) |

Returns `Promise&lt;*&gt;`.

##### `Chart.toggleTable(open)`

Shows or hides the data table (the accessible alternative) in place of the drawing.

| Parameter | Type | Meaning |
|---|---|---|
| `[open]` | `boolean` | default: the other state |

Returns `void`.

##### `Chart.toggleSeries(name)`

Hides or shows a series (a legend entry).

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `void`.

##### `Chart.setMode(mode)`

Switches the mode: card (g2 A) or studio (g2 B).

| Parameter | Type | Meaning |
|---|---|---|
| `mode` | `('card'\|'studio')` |  |

Returns `void`.

##### `Chart.setMapMode(mode)`

Switches a map's mode: ranking (g3 A) or table (g3 B); nothing for other kinds.

| Parameter | Type | Meaning |
|---|---|---|
| `mode` | `('ranking'\|'table')` |  |

Returns `void`.

##### `Chart.setDensity(d)`

Switches the density: comfortable, compact or terminal (the grid's three).

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `('comfortable'\|'compact'\|'terminal')` |  |

Returns `void`.

##### `Chart.destroy()`

Stops the chart: the engine, the observers, the link.

Returns `void`.

### drishti_chart-io.js

#### class `ChartIO`

A chart's export and live data; every member works on Chart's own fields.

##### `ChartIO.export(what)`

Exports the chart: a PNG at twice the pixels, an SVG drawn off screen, the data as CSV, or the spec (copied, and handed back).

| Parameter | Type | Meaning |
|---|---|---|
| `what` | `('png'\|'svg'\|'csv'\|'spec')` |  |

Returns `string`: the content (a data URL for PNG).

##### `ChartIO.apply(tx)`

Applies a transaction by row id (the spec's id field): rows added, updated (merged) and removed; drawn again, throttled.

| Parameter | Type | Meaning |
|---|---|---|
| `tx` | `{add: Object[], update: Object[], remove: Array&lt;(string\|Object)&gt;}` |  |

Returns `{added: number, updated: number, removed: number}`.

##### `ChartIO.append(rows)`

Appends rows (a feed's new points), drawn again throttled; the spec's live.keep caps how many stay.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |

Returns `void`.

##### `ChartIO.replace(rows)`

Replaces every row.

| Parameter | Type | Meaning |
|---|---|---|
| `rows` | `Object[]` |  |

Returns `void`.

##### `ChartIO.pointAt(index)`

Shows the crosshair and the tooltip at a row (a page's selected row followed by the chart); it comes back there after a resize.

| Parameter | Type | Meaning |
|---|---|---|
| `index` | `number` | the row's index in the chart's rows (the category's, for one series by category) |

Returns `void`.

### drishti_chart-link.js

#### `linkGrid(grid, o)`

Links a drishti-grid (the Grid, the element's grid, or its GridModel) to a selection channel: its row selection is published, and a selection published by a chart selects the grid's rows.

| Parameter | Type | Meaning |
|---|---|---|
| `grid` | `Object` |  |
| `o` | `{channel: string, bus: Object}` | the channel the charts share; bus default drishti-core's |

Returns `function(): void`: unlinks.

#### `publishSelection(channel, ids, o)`

Publishes a selection on a channel (a page's own control, a test).

| Parameter | Type | Meaning |
|---|---|---|
| `channel` | `string` |  |
| `ids` | `string[]` |  |
| `[o]` | `{bus: Object, source: *}` |  |

Returns `void`.

### drishti_chart-panels.js

#### `PANEL_KINDS`

The panel kinds drawn with the engine (VIEWMODEL.md section 4).

#### `escapeTip(s)`

Text made safe for a tooltip (the engine writes tooltips as markup).

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `*` |  |

Returns `string`.

#### `panelCompact(v)`

An axis tick as the console's charts write it: 1.75m, 250k, −1.2bn (at most two decimals for bn, none past 100m, one for k, two below 10,000; never grouped, as the console never groups a tick; trailing zeros dropped).

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `number` |  |

Returns `string`.

#### `panelLineCompact(v)`

The line and area charts' own ticks (the console's view.js): fixed decimals (2.0bn, 1.5m, 250k, 12.5k), a plain minus, and at most two decimals below 10,000.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `number` |  |

Returns `string`.

#### `panelTokens(el)`

The theme's colours as the console names them (--d-ink, --d-link, ...) and its mono font, read from an element of the view.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `Element` |  |

Returns `Object&lt;string, string&gt;`: ink, muted, faint, border, link, accent, neg, pos, ok, warn, bad, surface, bg-2, mono.

#### `labelFit(steps, size)`

Where a waterfall's step labels go: flat above the bars, turned up, or none, from the step count and the chart's size.

| Parameter | Type | Meaning |
|---|---|---|
| `steps` | `Array&lt;{text: string}&gt;` |  |
| `[size]` | `{width: number, height: number}` |  |

Returns `{mode: ('flat'\|'up'\|'none'), top: number}`.

#### `panelOption(kind, data, tokens, size, view)`

A panel's chart option: the console's, from the panel's data (VIEWMODEL.md section 4) and the theme's tokens (panelTokens).

| Parameter | Type | Meaning |
|---|---|---|
| `kind` | `string` | one of PANEL_KINDS but map (line, area, waterfall, histogram, scatter, candlestick, graph, donut, surface) |
| `data` | `Object` | the panel's data |
| `tokens` | `Object&lt;string, string&gt;` | panelTokens of the view |
| `[size]` | `{width: number, height: number}` | the drawing's size (the waterfall's labels and the graph's layers use it) |
| `[view]` | `('heatmap'\|'3d')` | the surface's view (default heatmap) |

Returns `Object`: the engine's option.

### drishti_chart-panel-draw.js

#### `drawMap(E, G, el, d, t, onLink, chartsOut)`

Draws a map panel from the console's shapes; resolves with its clickable places and its note.

| Parameter | Type | Meaning |
|---|---|---|
| `E` | `Object` | the engine (echarts) |
| `G` | `Object` | the console's map helper (drishti_geomap.js: world, region, find, option, codeOf) |
| `el` | `HTMLElement` | the chart's container |
| `d` | `Object` | the map panel's data (mode, region, highlight, shade, link, unit) |
| `t` | `Object&lt;string, string&gt;` | panelTokens of the view |
| `onLink` | `function(Object): void` | a clicked place that names an entity ({kind, id}) |
| `chartsOut` | `Object[]` | the engine instances drawn (the map's is added) |

Returns `Promise&lt;?{places: Array&lt;{name: string, link: Object, text: string}&gt;, note: string}&gt;`: null when a later draw replaced this one.

#### class `PanelCharts`

The panel charts of one rendered view (a shadow root or an element): draw, redraw after a theme change, dispose.

##### `static PanelCharts.state(el)`

Whether a chart container is drawn, still animating, or not drawn yet (a page's settled signal asks; the engine is the page's).

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` |  |

Returns `('drawn'\|'animating'\|'none')`.

##### `new PanelCharts(root, o)`

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `(ShadowRoot\|Element)` | where the view is drawn |
| `o` | `PanelChartsOptions` |  |

##### `PanelCharts.draw(scope)`

Draws every chart container in a scope (the whole view by default, or one panel a live frame replaced).

| Parameter | Type | Meaning |
|---|---|---|
| `[scope]` | `(ShadowRoot\|Element)` |  |

Returns `Promise&lt;void&gt;`.

##### `PanelCharts.redraw()`

Draws every chart again (a theme change: the colours are read anew).

Returns `Promise&lt;void&gt;`.

##### `PanelCharts.update(el)`

Moves a drawn chart to its container's new data without drawing it anew (a live panel's next point): the option is merged.

| Parameter | Type | Meaning |
|---|---|---|
| `el` | `HTMLElement` | a chart container of this view |

Returns `Promise&lt;void&gt;`.

##### `PanelCharts.instances()`

The engine instances drawn now (tests read their options).

Returns `Object[]`.

##### `PanelCharts.dispose()`

Stops listening and disposes of every chart.

Returns `void`.

### drishti_chart-element.js

#### `CHART_ELEMENT`

The element's tag name.

#### `defineChartElement(registry, win)`

Defines &lt;drishti-chart&gt; in a registry (once; the bundle does it when it loads in a page).

| Parameter | Type | Meaning |
|---|---|---|
| `[registry]` | `CustomElementRegistry` | default: the window's |
| `[win]` | `Window` | the window whose HTMLElement the element extends |

Returns `boolean`: whether it was defined now.

#### `chartIn(host, spec, o)`

A chart in a host element: a &lt;drishti-chart&gt; made there once (the host's content replaced), its spec set, and set again on the next call. What a page that draws a spec it was given (a report visual, a Python answer) calls instead of building a chart itself.

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `spec` | `Object` | a drishti-chart spec |
| `[o]` | `Object` | density (default compact), height (px; default: the host's height less the card's chrome, at least 140), and any Chart option |

Returns `Object`: the Chart (el.chart), its ready promise as chart.ready.

### drishti_index.js

#### `install(root)`

Starts drishti-chart in a page (the bundle calls it when it loads; nothing happens without a document): defines &lt;drishti-chart&gt;.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.

### Types of drishti-chart

#### `RegionGeo` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `level` | `string` | 'world' or the country's ISO alpha-3 |
| `projection` | `string` | equal-earth, albers-usa, albers-canada, albers-fitted or plate-carree |
| `project` | `function(number, number): number[]` | (lon, lat) to the map's projected [x, y] |
| `shapes` | `Object[]` | projected GeoJSON features named by place code |
| `points` | `Array&lt;{code: string, name: string, xy: number[]}&gt;` | the places drawn as points |
| `by` | `{code: Object, name: Object, short: Object}` | lookups (a country's iso3 and iso2 for the world) |
| `list` | `Array&lt;{code: string, name: string, point: boolean}&gt;` | every place |
| `fit` | `number[]` | [minX, minY, maxX, maxY] the view fits |
| `all` | `number[]` | [minX, minY, maxX, maxY] of everything drawn |
| `frames` | `Array&lt;{code: string, box: number[]}&gt;` | inset frames (Alaska, Hawaii) |

#### `PanelChartsOptions` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `engine` | `function(): Promise&lt;Object&gt;` | loads the engine (ECharts) and resolves with it (the host's loader: its own policy for scripts) |
| `[gl]` | `function(): Promise&lt;void&gt;` | loads ECharts GL (the surface in 3D); without it a 3D surface is drawn as its heatmap |
| `[geo]` | `function(): Promise&lt;Object&gt;` | the console's map helper (drishti_geomap.js); without it a map says it did not load |
| `[onLink]` | `function(Object, HTMLElement): void` | a point, a node or a place that names an entity was clicked ({kind, id}, the container) |
| `[onPlaces]` | `function(HTMLElement, Array&lt;{name: string, link: Object, text: string}&gt;, string): void` | a map's clickable places and its note, for the host to list |
| `[selector='[data-r-chart]` | `string` | '] the chart containers |
| `[specOf]` | `function(HTMLElement): ?{kind: string, data: Object}` | a container's kind and data (default el.__drishti) |
| `[tokensFrom]` | `function(): Element` | the element the colour tokens are read from (default the root's .view, its host, or the page) |
| `[attr='data-r-chart']` | `string` | the attribute that marks a container still to draw (taken off one that cannot be drawn) |
| `[relayout=false]` | `boolean` | a change of size draws again (the waterfall's labels are fitted to the width), not only resizes |

#### `KindDef` : `Object`

A kind: how a spec of that kind is drawn.

| Name | Type | Meaning |
|---|---|---|
| `build` | `function(Object, Object[], Object): BuiltChart` | the spec (normalised), its rows and the environment ({theme, fmt}) to what the engine draws |
| `[label]` | `string` | the kind's name in the studio's rail |
| `[family='general']` | `('general'\|'risk'\|'map'\|string)` | the rail's group (a domain library's name for its kinds) |
| `[cartesian]` | `boolean` | x and y axes (zoom and the crosshair apply) |
| `[sample]` | `function(): Object` | a small spec with data, for the studio and the gallery |
| `[studio]` | `string[]` | the studio's own options the kind offers beyond the general ones: 'log' (a log x axis), 'zoom' |

#### `BuiltChart` : `Object`

What a kind builds.

| Name | Type | Meaning |
|---|---|---|
| `option` | `Object` | the drawing engine's option (ECharts) |
| `table` | `{columns: Array&lt;{header: string, format: *}&gt;, rows: Array&lt;Array&lt;*&gt;&gt;}` | the data table and the CSV |
| `legend` | `Array&lt;{name: string, colour: string, style: string}&gt;` | the entries (style: line, dash, box, dot) |
| `summary` | `string` | one line a screen reader reads ("Line chart, 3 series, 250 points, from 2025-11-11 to 2026-11-06") |
| `[badge]` | `Object` | a status the card shows beside its title ({tone, text}: the backtest's zone) |

#### `ChartTheme` : `Object`

| Name | Type | Meaning |
|---|---|---|
| `dark` | `boolean` |  |
| `tokens` | `Object&lt;string, string&gt;` | the token values by name without --d- (ink, muted, link ...) |
| `palette` | `string[]` | the categorical colours, in order |
| `density` | `string` |  |
| `size` | `{font: number, pad: number, symbol: number, line: number, title: number}` |  |
| `tones` | `Object&lt;string, string&gt;` | good, bad, warn, accent, link, pos, neg, muted, faint, ink |
| `sequential` | `string[]` | five colours from faint to strong |
| `diverging` | `string[]` | five colours, negative to positive through the ground |
| `nodata` | `string` |  |

#### `ChartOptions` : `Object`

The options of a chart (the spec says what to draw; these say how it lives in its page).

| Name | Type | Meaning |
|---|---|---|
| `[mode='card']` | `('card'\|'studio')` |  |
| `[density='compact']` | `('comfortable'\|'compact'\|'terminal')` |  |
| `[explain]` | `function(Object): Promise&lt;*&gt;` | the Explain hook (the grid's): a mark's context ({kind, title, series, field, value, category, rows, ids}) to an ExplainResult, words or an element |
| `[link]` | `{channel: string, bus: Object}` | linked selection: the bus (default drishti-core's) and the channel a grid shares |
| `[fetch]` | `function(string): Promise&lt;Object&gt;` | reads a spec's data.source (default: fetch, same origin, JSON) |
| `[echarts]` | `Object` | the engine (default window.echarts) |
| `[mapsBase]` | `string` | the folder of the map shapes (default /static/vendor/echarts/maps/) |
| `[placeData]` | `function(string): Promise&lt;Object[]&gt;` | a map's rows of a level (a country's ISO alpha-3) when it is drilled |
| `[samples]` | `Object&lt;string, Object&gt;` | the studio's spec of each kind |
| `[mapMode='ranking']` | `('ranking'\|'table')` | a map's mode (g3 A or B) |
| `[download]` | `function(string, (string\|Blob), string): void` | where exports go (default: a download) |
| `[height]` | `number` | the drawing's height in pixels (default 300; a map 440) |
| `[asOf]` | `string` | the data's date, for the status line |

## drishti-risk

Version 0.2.0. Bundle `static/elements/drishti-risk/drishti_risk.js`, global `Drishti.elements.risk`; requires drishti-core, drishti-grid, drishti-chart.

### drishti_risk-kinds.js

#### `TRAFFIC_LIGHT`

The Basel backtest zones over 250 days: exceptions to the zone, and the plus factor of each count.

#### `trafficLight(n)`

The zone and plus factor of a count of exceptions.

| Parameter | Type | Meaning |
|---|---|---|
| `n` | `number` | exceptions in the window |

Returns `{zone: ('green'\|'amber'\|'red'), plus: number}`.

#### `tailFigures(pnl, varConf, esConf)`

VaR and ES of scenario P&L (positive numbers: losses), as a page with no server computes them: VaR the loss at the quantile, ES the mean of the losses beyond it. Inside Drishti these figures come from the server.

| Parameter | Type | Meaning |
|---|---|---|
| `pnl` | `number[]` |  |
| `varConf` | `number` | for example 0.99 |
| `esConf` | `number` | for example 0.975 |

Returns `{var: ?number, es: ?number}`.

#### `tenorText(t)`

A tenor in years as people write it (0.25: 3M, 1: 1Y, 0.02: 1W).

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `?number` |  |

Returns `string`.

#### `eepeOf(tenors, ee)`

Effective EPE: the time-weighted mean of effective EE (its running maximum) over the first year, as a page with no server computes it. Inside Drishti the server's figure comes in the spec.

| Parameter | Type | Meaning |
|---|---|---|
| `tenors` | `number[]` | in years, ascending |
| `ee` | `number[]` |  |

Returns `?number`.

#### `buildPnlDistribution(spec, rows, env)`

The P&L distribution with VaR and ES.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | value (the P&L field), var and es (the server's figures), confidence {var, es}, bins, binned (the server's bins {edges, counts}: then var and es are the server's too), parametric {var, curve: {x, y}, gap, gapPct, describe} (the server's fitted normal) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `Object`.

#### `buildBacktest(spec, rows, env)`

The backtest: P&L against minus VaR, exceptions marked, the traffic light on the card.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | x (the date field), pnl (default hypothetical), var (the VaR field; drawn negated), actual (another P&L field), window (default 250), light (the server's {exceptions, zone, plus, multiplier}: shown as given; figures.counted is the page's own count of the rows, for a check), marks (more marks: another P&L's exceptions) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `Object`.

#### `buildExposureProfile(spec, rows, env)`

The exposure profile: EE and PFE over the tenors (a log axis by default), EEPE over the first year, the limit, a breach of it marked.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | x (tenor in years), ee, pfe, eepe (the server's), limit, log (default true), more (further series drawn as given: the uncollateralised EE, the paths' fan) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `Object`.

#### `buildVarChange(spec, rows, env)`

What moved VaR from one day to the next: a waterfall where an increase is bad and a decrease good.

| Parameter | Type | Meaning |
|---|---|---|
| `spec` | `Object` | category (the step), value (the change; the first and last rows are the two days' VaR) |
| `rows` | `Object[]` |  |
| `env` | `Object` |  |

Returns `Object`.

#### `registerRiskKinds()`

Registers the risk kinds in drishti-chart's registry (the library does it when it loads, and only where the banking module is licensed: the console serves this library to no one else).

Returns `string[]`: the kinds registered now.

### drishti_risk-grid.js

#### `RISK_GRID_DEFAULTS`

What every risk table starts from.

#### `TONES`

The tones a column's cellClass gives: a loss or a breach is bad, a gain good (the console's tone classes in the grid).

#### `figureColumn(field, header, o)`

A number column of a risk figure: right-aligned, formatted by name, never summed in the browser when it is a VaR or an ES.

| Parameter | Type | Meaning |
|---|---|---|
| `field` | `string` |  |
| `header` | `string` |  |
| `[o]` | `Object` | format (default 'compact'), additive (default false: VaR, ES, a limit use), width, tone (a function of the value giving a key of TONES), and any other ColumnDef property |

Returns `Object`: a ColumnDef.

#### `riskGrid(host, options)`

Draws a risk table: a &lt;drishti-grid&gt; in host (made once and kept: a second call gives it new columns and rows).

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` | the element the grid fills |
| `options` | `Object` | columns, rows, title, subtitle, getRowId, fitRows (rows shown before it scrolls), onRow(row, event), explain, and anything a Grid takes (chrome: true for the terminal's command line and keys) |

Returns `Object`: the Grid (its plain API: setRows, on, getState ...).

### drishti_risk-chart.js

#### `riskChart(host, spec, o)`

Draws a risk chart in host.

| Parameter | Type | Meaning |
|---|---|---|
| `host` | `HTMLElement` |  |
| `spec` | `Object` | a drishti-chart spec (a risk kind or any other) |
| `[o]` | `Object` | onPick(row, ctx), height (the drawing's, px, default 220), density (default terminal), explain, and any Chart option |

Returns `Object`: the Chart.

### drishti_risk-columns.js

#### `textColumn(field, header, o)`

A label column.

| Parameter | Type | Meaning |
|---|---|---|
| `field` | `string` |  |
| `header` | `string` |  |
| `[o]` | `Object` | any ColumnDef property (width, pinned, cellRenderer ...) |

Returns `Object`: a ColumnDef.

#### `moneyColumn(field, header, fmt, o)`

A money column: the server's figure written by the screen's rule; an empty figure of a total row is left empty.

| Parameter | Type | Meaning |
|---|---|---|
| `field` | `string` |  |
| `header` | `string` |  |
| `fmt` | `function(*): string` | the screen's money rule |
| `[o]` | `Object` | figureColumn's options (additive, tone, width ...); a format here wins |

Returns `Object`: a ColumnDef.

### drishti_risk-format.js

#### `riskMoney(v)`

Compact money: 1.23m, -412k, 950; an em dash for no figure.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `?number` |  |

Returns `string`.

#### `riskSigned(v)`

Compact money with its sign: +1.23m, -412k.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `?number` |  |

Returns `string`.

#### `riskInt(v)`

Whole units with grouping: 1,234,567 and -12,000; an em dash for no figure.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `?number` |  |

Returns `string`.

#### `riskPct(v)`

A share with one decimal: 0.4567 is 45.7%; an em dash for no figure.

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `?number` |  |

Returns `string`.

### drishti_index.js

#### `RISK_KINDS`

The kinds this library adds to drishti-chart.

#### `install(root)`

Starts drishti-risk in a page (the bundle calls it): the kinds are registered already, when the module is read.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.

## drishti-renderer

Version 2.0.0. Bundle `static/elements/drishti-renderer/drishti_renderer.js`, global `Drishti.elements.renderer`; requires drishti-core, drishti-grid, drishti-chart, drishti-data.

### drishti_render.js

#### `VERSION`

The renderer's version (Elements library drishti-renderer).

#### `CONTRACTS`

The ViewModel contract majors this renderer draws (VIEWMODEL.md section 8: a ViewModel without a contract is 1).

#### `render(container, source, options)`

Paints a screen and returns its handle; handle.ready settles when the first paint is done. Rendering into a container again replaces what it showed.

| Parameter | Type | Meaning |
|---|---|---|
| `container` | `HTMLElement` | where the screen is drawn |
| `source` | `(Object\|{kind: string, id: string, controls: Object}\|{stream: string}\|AsyncIterable&lt;Object&gt;)` | a ViewModel (the JSON of GET /api/v1/views/{kind}/{id}); {kind, id} to fetch it as the person; {stream: url} for a streamed (GenUI) or live screen; or an async iterable of stream messages |
| `[options]` | `Object` | see drishti-render.d.ts (every option has a default: shadow true, navigation 'in-place', live true, theme 'inherit') |

Returns `Handle`.

#### `patch`

patch(handle, frame): applies a live frame {seq, generation, patches} (VIEWMODEL.md section 5).

#### `subscribe`

subscribe(handle, source?): follows the view's stream (default: this console's relay); returns the function that stops it.

#### `defineScreen(win)`

Defines &lt;drishti-screen&gt; on render() (once; a page with Elements 2.x has it as the alias of &lt;drishti-view&gt;).

| Parameter | Type | Meaning |
|---|---|---|
| `win` | `Window` |  |

Returns `void`.

#### `installRender(win)`

The renderer's page-wide names: window.DrishtiRender (version, render, patch, subscribe, KINDS, CONTRACTS, trustedTypes) and, after the module graph has run, &lt;drishti-screen&gt;.

| Parameter | Type | Meaning |
|---|---|---|
| `win` | `Window` |  |

Returns `void`.

### drishti_kinds.js

#### `KINDS` : `ReadonlyArray&lt;string&gt;`

The kinds this renderer draws (VIEWMODEL.md section 4): all twenty-two.

#### `tableHooks(R)`

The renderer's cells as drishti-grid's document table hooks: a cell's value (links, inactive commands), its tone, a row's attributes.

| Parameter | Type | Meaning |
|---|---|---|
| `R` | `Object` | the screen's rendering context |

Returns `{content: function(Object): *, tone: function(Object): string, rowAttrs: function(Object, number): Object}`.

### drishti_assets.js

#### `setAssetBase(o)`

Where the console's static files and the renderer's sheet are (the public entry calls it with addresses made from its own URL).

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `{statics: string, sheet: string}` | statics: the console's static root ("https://drishti.example/static/"); sheet: the sheet's address |

Returns `void`.

#### `statics(rel)`

The address of a file under the console's static root.

| Parameter | Type | Meaning |
|---|---|---|
| `rel` | `string` | for example "vendor/echarts/echarts.min.js" |

Returns `string`.

#### `trustedTypesActive`

Whether script URLs go through the renderer's Trusted Types policy.

### drishti_dom.js

#### `safeAttrs(attrs)`

The attributes that may be set, values as text (null, undefined and false are left out; true is an empty attribute); a refused name or an unsafe link is dropped and said once in the browser's console.

| Parameter | Type | Meaning |
|---|---|---|
| `attrs` | `?Object&lt;string, *&gt;` |  |

Returns `Object&lt;string, (string\|boolean)&gt;`.

#### `h(tag, attrs, ...children)`

One element: h(tag, attrs, ...children). attrs: allowed attributes; children: strings, numbers, nodes, arrays of those, or null.

| Parameter | Type | Meaning |
|---|---|---|
| `tag` | `string` |  |
| `attrs` | `?Object&lt;string, *&gt;` |  |
| `children` | `...*` |  |

Returns `HTMLElement`.

#### `str(v)`

Jinja's {{ x }} of a value: the macros never print a missing cell, so null is "".

| Parameter | Type | Meaning |
|---|---|---|
| `v` | `*` |  |

Returns `string`.

#### `toneClass(c)`

The tone class of a cell as the macros write it (" t-&lt;tone&gt;"), or "" for none: the nine tones of the contract are drawn, any other word is a class the sheet ignores (the value is untoned).

| Parameter | Type | Meaning |
|---|---|---|
| `c` | `?{tone: string}` |  |

Returns `string`.

### drishti_net.js

#### `fixFor`

The fix for a refusal: the server's own words first (the console knows the person's lens), then the code's, then the status's.

#### `problem(code, detail, fix, status = 0)`

A problem: {code, status, detail, fix}, also an Error (so it can be thrown and logged).

| Parameter | Type | Meaning |
|---|---|---|
| `code` | `string` |  |
| `detail` | `string` |  |
| `[fix]` | `string` |  |
| `[status]` | `number` |  |

Returns `Error`.

#### `viewUrl(o, ref)`

The address of a view: options.viewUrl(ref) when the host has its own, else the console's /api/view/{kind}/{id}.

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `Object` |  |
| `ref` | `{kind: string, id: string, controls: Object}` |  |

Returns `string`.

#### `streamUrl(o, ref)`

The default stream: the console's relay of the server's stream, raw (the ViewModel's own frames, not the console's HTML).

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `Object` |  |
| `ref` | `(string\|{kind: string, id: string})` |  |

Returns `string`.

#### `fetchView(o, ref)`

Fetches a ViewModel for {kind, id} as the person (the console's cookie on its own origin, or the host's bearer token).

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `Object` |  |
| `ref` | `{kind: string, id: string}` |  |

Returns `Promise&lt;Object&gt;`.

#### `openStream(o, refOrUrl, on)`

Follows a stream of Server-Sent Events with drishti-data's live client: `view` (a whole ViewModel), `frame` (live patches), `message` (a GenUI stream message: skeleton, panel-add, panel-replace, patch, done, error) and `gone`. options.eventSource(url) makes the EventSource when the host needs its own (headers, a polyfill). A stream that is gone is closed; a dropped one is opened again after a back-off (from 3 s, as a browser's own EventSource waits) and resumes from the last event id.

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `Object` |  |
| `refOrUrl` | `(string\|{kind: string, id: string})` |  |
| `on` | `{view: function(Object): void, frame: function(Object): void, message: function(Object): void, gone: function(Error): void}` |  |

Returns `function(): void`: closes it.

#### `personFetch(o)`

A fetch as the person: the host's bearer token when it gave a token provider, the console's session otherwise.

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `Object` | the screen's options |

Returns `function(string, Object): Promise&lt;Response&gt;`.

### drishti_nested.js

#### `nestedPivotOptions(d, title)`

The grid's options for a nested pivot's data (the ViewModel's pivot panel: by, levels, columns, rows with paths, totals).

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `Object` | the panel's data |
| `title` | `string` | the panel's title (the grid's name for a screen reader) |

Returns `Object`: drishti-grid options (columns, rows, treeData, getDataPath, groupDefaultExpanded, clientAggregation false).

### drishti_embed.js

#### `ELEMENTS_VERSION`

The Elements version this renderer carries.

#### `API`

The embed API the elements read.

#### `cfg`

The page's settings (DrishtiElements.configure): the console, the token provider, auth, the live connection's waits.

#### `serverOf`

The console the elements read when no server is named: the one that serves the library.

#### `usePopup(server)`

auth 'popup' (configure({ auth: 'popup' }) or the auth="popup" attribute): the page's token comes from the console's sign-in window (CLIENT_RENDERER.md 10.1) instead of a host backend; every element and the live channel share it.

| Parameter | Type | Meaning |
|---|---|---|
| `[server]` | `string` | the console (default the one that serves the library) |

Returns `Object`: the sign-in of that console.

#### `getToken(force)`

The page's bearer token: the one kept while it is fresh, else the provider's (it waits briefly for a host that sets it late).

| Parameter | Type | Meaning |
|---|---|---|
| `[force]` | `boolean` | ask the provider even when the kept token is fresh (after a 401) |

Returns `Promise&lt;?string&gt;`.

#### `invalidateToken`

Forgets the page's token: the next call asks the provider again (the host's user changed, or the server said so).

#### `providerChanged`

The host set its provider (configure, or the property): a load waiting for one goes on.

#### `resolve(text, opts = {})`

A typed command ("TRD 1000008") to the entity it names: {ref, ...} or {ref: null, candidates} (the host's search box).

| Parameter | Type | Meaning |
|---|---|---|
| `text` | `string` |  |
| `[opts]` | `{server: string, signal: AbortSignal}` |  |

Returns `Promise&lt;Object&gt;`.

#### `connectionFor`

The page's one connection to a server's channel.

#### `connectionCount`

How many connections the page holds (one per server).

### drishti_elements.js

#### `elementClasses(win)`

The element classes for a window: DrishtiView (&lt;drishti-view&gt;, &lt;drishti-screen&gt;) and DrishtiPanel (&lt;drishti-panel&gt;).

| Parameter | Type | Meaning |
|---|---|---|
| `win` | `Window` |  |

Returns `{DrishtiView: Function, DrishtiPanel: Function}`.

#### `define(win)`

Defines the tags (once per page; a page that loaded Elements 1.x keeps its own).

| Parameter | Type | Meaning |
|---|---|---|
| `win` | `Window` |  |

Returns `{DrishtiView: Function, DrishtiPanel: Function}`.

### drishti_index.js

#### `install(root)`

Starts drishti-renderer in a page (the bundle calls it when it loads): window.DrishtiRender and &lt;drishti-screen&gt;.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.
