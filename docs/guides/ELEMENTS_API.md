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
| [drishti-core](#drishti-core) | 1.0.0 | Formats, theme, API client, events, accessibility, resize, keys, dialogs, drawers and toasts |
| [drishti-grid](#drishti-grid) | 0.2.0 | The grid: its engine (columns, row models, virtual scrolling, pivot, selection, export, keyboard) and its look (Grid and &lt;drishti-grid&gt; in three layouts and three densities) |

## drishti-core

Version 1.0.0. Bundle `static/elements/drishti-core/drishti_core.js`, global `Drishti.elements.core`.

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

The words of an error answer: its DRS code and the server's detail ("DRS-4005 ..."), else the fallback.

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

### drishti_index.js

#### `install(root)`

Starts drishti-core in a page (the bundle calls it when it loads; nothing happens without a document): the resize component, and the console's names for what moved here (window.Drishti.resize, window.Drishti.GridKeys) and window.Drishti.elements.load.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.

### Types of drishti-core

#### `Listener`

A listener: the event's payload and its name.

| Name | Type | Meaning |
|---|---|---|
| `payload` | `*` |  |
| `type` | `string` |  |

## drishti-grid

Version 0.2.0. Bundle `static/elements/drishti-grid/drishti_grid.js`, global `Drishti.elements.grid`; requires drishti-core.

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

### drishti_sort-filter.js

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

### drishti_aggregate.js

#### `AGG_FUNCS`

The functions by name.

#### `aggFunc(name)`

A function by its name or a usual alias (average, distinct), or null when the browser does not compute it.

| Parameter | Type | Meaning |
|---|---|---|
| `name` | `string` |  |

Returns `?AggFunc`.

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
| `o` | `{columns: import('./drishti_columns.js').ColumnModel, rows: Object[], getRowId: function(Object, number): string, clientAggregation: boolean}` | clientAggregation false: no group figure is computed here, every one is left for the server (the console's policy) |

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

#### `toCsv(t, o)`

The table as CSV text.

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `ExportTable` |  |
| `[o]` | `{formatted: boolean, separator: string, bom: boolean}` | formatted: values as shown (named formats), else raw (default); separator default ","; bom default true |

Returns `string`.

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

#### `LAYOUTS`

The layouts a grid is drawn in (the visual shell; the model is the same).

#### `DENSITIES`

The densities, with their row heights in px.

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

##### `GridModel.setRowGroups(colIds)`

Groups rows (grid mode).

| Parameter | Type | Meaning |
|---|---|---|
| `colIds` | `string[]` |  |

Returns `void`.

##### `GridModel.setPivotMode(on)`

Turns pivot mode on or off; on, the wells' view is computed (or asked).

| Parameter | Type | Meaning |
|---|---|---|
| `on` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModel.setPivotOptions(extra)`

Sets the view's other parts (sort per level, top N, subtotals above or below, value filters, source, provider options).

| Parameter | Type | Meaning |
|---|---|---|
| `extra` | `Object` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModel.pivotView()`

The pivot view now: the wells, the grid's member filters, the open state and the options.

Returns `Object`.

##### `GridModel.runPivot()`

Computes the pivot: in the browser for client-side rows (the non-additive measures then asked of the server, when serverPivot is given), else asked of the server. A value filter is applied either way.

Returns `Promise&lt;void&gt;`.

##### `GridModel.togglePivotNode(axis, path, open)`

Opens or closes a pivot row or column group, and computes again.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `path` | `Array&lt;Array&lt;string&gt;&gt;` | the node's (dimension, value) pairs |
| `[open]` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModel.expandPivotTo(axis, level)`

Opens every pivot node of an axis to a level.

| Parameter | Type | Meaning |
|---|---|---|
| `axis` | `('rows'\|'columns')` |  |
| `level` | `number` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModel.toggleRow(r, open)`

Opens or closes the group at a display row (a row group or a pivot row).

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `[open]` | `boolean` |  |

Returns `Promise&lt;void&gt;`.

##### `GridModel.idAt(r)`

The id of a display row, or null for a group, a pivot row or a placeholder.

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

An edit: a leaf's value of an editable column set (the row replaced by id, as a transaction) and cellValueChanged.

| Parameter | Type | Meaning |
|---|---|---|
| `r` | `number` |  |
| `c` | `number` |  |
| `value` | `*` |  |

Returns `boolean`: whether the value was set.

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

##### `GridModel.dataChanged(notice)`

A server's "data changed" notice: server-side rows are asked again (all, a level, blocks, or the blocks holding some rows); a server pivot is computed again.

| Parameter | Type | Meaning |
|---|---|---|
| `notice` | `{purge: boolean, keys: string[], blocks: number[], rows: string[]}` |  |

Returns `string`: what was done.

##### `GridModel.applyPivotUpdates(pairs)`

Changes the local pivot's figures for changed rows by difference (false: compute it again).

| Parameter | Type | Meaning |
|---|---|---|
| `pairs` | `Array&lt;{old: Object, value: Object}&gt;` |  |

Returns `boolean`.

##### `GridModel.refreshServerMeasures()`

Asks the server again for the pivot's non-additive measures (every cell), and puts the figures in.

Returns `Promise&lt;number&gt;`: how many figures came in.

##### `GridModel.toTable()`

The grid as shown, as a table to export: shown columns; in grid mode every filtered row (or the groups and their rows), in pivot mode the pivot's rows, with outline levels.

Returns `import('./drishti_export.js').ExportTable`.

##### `GridModel.exportCsv(o)`

CSV text of the grid as shown.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{formatted: boolean}` |  |

Returns `string`.

##### `GridModel.exportXlsx(o)`

An Excel workbook of the grid as shown.

| Parameter | Type | Meaning |
|---|---|---|
| `[o]` | `{sheetName: string}` |  |

Returns `Uint8Array`.

##### `GridModel.setDensity(d)`

Sets the density (the row height follows).

| Parameter | Type | Meaning |
|---|---|---|
| `d` | `('comfortable'\|'compact'\|'terminal')` |  |

Returns `void`.

##### `GridModel.setLayout(l)`

Sets the layout's name (the shell draws it; nothing in the model changes).

| Parameter | Type | Meaning |
|---|---|---|
| `l` | `('wells'\|'panel'\|'terminal')` |  |

Returns `void`.

##### `GridModel.getState()`

Everything a person set, to keep with a view or a link: columns, sort, filters, wells, pivot mode and open state, layout, density.

Returns `Object`.

##### `GridModel.setState(s)`

Gives back a kept state.

| Parameter | Type | Meaning |
|---|---|---|
| `s` | `Object` |  |

Returns `Promise&lt;void&gt;`.

#### `createGridModel(o)`

A new grid model.

| Parameter | Type | Meaning |
|---|---|---|
| `o` | `GridOptions` |  |

Returns `GridModel`.

```js
const g = createGridModel({columns: [{field: 'DESK'}, {field: 'PNL', type: 'number', format: 'amount0'}], rows: data});
```

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

### drishti_live.js

#### `eventSourceFeed(url, o)`

A feed over Server-Sent Events: each event's data is a message (event name: message, or opts.event).

| Parameter | Type | Meaning |
|---|---|---|
| `url` | `string` |  |
| `[o]` | `{event: string, withCredentials: boolean, EventSource: Function, onError: function(Error): void}` | EventSource: for tests or a polyfill |

Returns `LiveFeed`.

#### `webSocketFeed(url, o)`

A feed over a WebSocket: each text frame is a message.

| Parameter | Type | Meaning |
|---|---|---|
| `url` | `string` |  |
| `[o]` | `{WebSocket: Function, protocols: string[], onError: function(Error): void}` |  |

Returns `LiveFeed`.

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

### drishti_index.js

#### `install(root)`

Starts drishti-grid in a page (the bundle calls it when it loads; nothing happens without a document): defines &lt;drishti-grid&gt;.

| Parameter | Type | Meaning |
|---|---|---|
| `root` | `Window` |  |

Returns `void`.
