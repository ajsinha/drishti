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
| [drishti-grid](#drishti-grid) | 0.1.0 | The grid engine: columns, row models (client and server), virtual scrolling, pivot, selection, export and keyboard |

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

Version 0.1.0. Bundle `static/elements/drishti-grid/drishti_grid.js`, global `Drishti.elements.grid`; requires drishti-core.

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

##### `ClientRowModel.applyTransaction(t)`

Adds, updates (by id) and removes (by id) rows, then refreshes.

| Parameter | Type | Meaning |
|---|---|---|
| `t` | `{add: Object[], update: Object[], remove: Array&lt;(Object\|string)&gt;}` |  |

Returns `{added: number, updated: number, removed: number}`.

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
