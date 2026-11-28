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

# Integrate Drishti: embed a view, take data out, call the API

For the people who connect other systems to Drishti: a web team that wants a live view in its own application, a data engineer or
analyst who reads Drishti's data from a notebook, a JDBC or ODBC tool or Excel, and anyone who scripts the server. The guide has three
parts, one for each way in, and each part stands on its own.

| You want | Use | Part |
|---|---|---|
| a live view, panel, grid or chart in **your own web page** (no iframe) | Drishti Elements, Drishti Render | [1. Embed Drishti in your pages](#1-embed-drishti-in-your-pages) |
| Drishti's tables, BI models and VaR vectors in **a notebook, a JDBC or ODBC tool, Excel or a service**, as the person they belong to | Arrow Flight and Flight SQL | [2. Data out](#2-data-out-drishtis-data-to-any-client-with-arrow-flight) |
| to **call the server** from a script, check a deployment or see what the console does | the REST API | [3. The API](#3-the-api-calling-the-server-from-scripts-notebooks-and-excel) |

Every endpoint with a `curl` command and an abbreviated real answer is in [API_REFERENCE.md](API_REFERENCE.md); the hands-on walks are
[tutorial 20](../tutorials/20-embed-drishti.md) (embed) and [tutorial 17](../tutorials/17-data-out-with-arrow-flight.md) (Flight);
users, roles and tokens are in [ADMIN_GUIDE.md](ADMIN_GUIDE.md).


## 1. Embed Drishti in your pages

This guide is for a team that wants Drishti inside **its own web application**: a live view of a trade or a counterparty in a CRM,
a live panel of a view in a portal, or Drishti's grid and chart components in an application of its
own. Everything is drawn in your page's DOM, in a Shadow DOM, with **no iframe**: your layout, your theme tokens, your keyboard focus,
one network connection for the whole page, and your user signed in as themselves with every masked field masked.

There are three ways in:

| You want | Use | Chapter |
|---|---|---|
| Drishti views and panels as tags in your page | **Drishti Elements**: `<drishti-view>`, `<drishti-panel>` | [3](#13-five-minutes-a-static-page) to [5](#15-the-elements-one-by-one) |
| A Drishti screen drawn by one function call, with full control of navigation and live updates | **Drishti Render**: `render(container, source, options)` | [6](#16-drishti-render-one-import-one-call) |
| A grid, a chart or a picker in your own application, with or without a Drishti server | **the Elements libraries** (drishti-core, -grid, -chart, ...) | [13](#113-drishti-elements-libraries-in-your-own-app) and [14](#114-moving-an-ag-grid-enterprise-pivot-app) |

What each screen contains is the **ViewModel**, the server's JSON of a screen already laid out and bound: its reference is
[VIEWMODEL.md](../architecture/VIEWMODEL.md). How the renderer and the elements are built is
[ARCHITECTURE.md](../architecture/ARCHITECTURE.md) (section 30). The API of every Elements library is [ELEMENTS_API.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/API.md).

### 1.1 What you need

- **A Drishti console your users can reach.** The console serves the library and the embed API; the library loads nothing from a CDN,
  ever (ECharts, the map shapes and the icon font come from the console too).
- **Embedding switched on** and **your application registered** (chapter 2). Inside Drishti's own console nothing needs registering.
- **A way to say who the user is:** your backend exchanges its user for a short-lived token (the default), or, for a page with no
  backend, the person signs in through single sign-on in a small window ([4.2](#142-a-page-without-a-backend-the-sign-in-window)).
- **An evergreen browser:** Custom Elements v1, Shadow DOM, constructable style sheets, `ReadableStream` and `AbortController`. No
  polyfills.

What the elements do not do: they are a read-only window. No layout mode, no saved Pivot arrangement, no share, comments, alerts, pins,
Calc, Ask, raw JSON drawer or export; the view shows the Sutra's layout, never a person's own arrangement, so you can rely on what it
looks like.

### 1.2 Turn embedding on and register your application

#### 1.2.1 Switch embedding on

Embedding is **off by default**. The server needs `drishti.embed.enabled=true` with `drishti.security.enabled=true`, and the console
`embed.enabled=true`; with them off there is no token endpoint and every embed token is refused. The sign-in window for pages without a
backend also needs single sign-on (`auth.oidc` on the console, `drishti.security.oidc` on the server) and is on by default
(`embed.sign_in.enabled`, `drishti.embed.sign-in-window`). Every key, its default and its environment variable are in
[CONFIGURATION.md](../admin/CONFIGURATION.md#console-embed--the-consoles-side-of-embedded-views) (the console's `embed`) and
[CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiembed--embedded-views-for-other-web-applications) (the server's `drishti.embed`).

#### 1.2.2 Register a host application

An administrator registers each application in **Admin → Embedding** (`/admin/embedding`) or with
`POST /api/v1/admin/embed/apps` ([ADMIN_GUIDE.md](ADMIN_GUIDE.md#7-embedding-host-applications), which also describes
the page, rotating a secret, disabling and the audit events). What a registration holds:

| Field | Example | Meaning |
|---|---|---|
| Id | `crm` | lower-case; appears in tokens (`azp`) and in the access log as `embed:crm` |
| Name, contact | *Client CRM*, `crm-team@bank.example` | shown in the access log |
| Origins | `https://crm.bank.example` | **exact** origins (scheme, host, port; no path); no wildcards unless `drishti.embed.allow-wildcard-origins` allows a suffix for review apps |
| Client authentication | a client secret (shown **once**), a public key (JWKS JSON or URL), or both | how your backend proves it is your backend; a public key (`private_key_jwt`, RSA or ECDSA) is recommended in production |
| Scopes | `embed:view`, `embed:about` | views and panels; the About text |
| Kinds | `trade, counterparty` | what it may show; empty: everything the user's roles open. An application may narrow its users' rights, never widen them |
| Token lifetime | 300 s | at most `drishti.embed.token-max-seconds` |
| Rates | `callsPerMinute` per application, `userCallsPerMinute` per user | over them: `429 DRS-8004` with `Retry-After` |
| Enabled | yes | off stops the application at the next call |

Applications can also be declared in the server's configuration (`drishti.embed.apps`, for GitOps); the page shows them read-only.

#### 1.2.3 The CORS allow-list

The registered origins **are** the allow-list; there is no second list to keep. The console answers CORS only on its embed paths, echoes
your `Origin` only when it is a registered one (`Vary: Origin`), never sends `Access-Control-Allow-Credentials` (no cookie is ever
used), and allows the headers `Authorization`, `Content-Type`, `Drishti-Embed-Api`, `X-Drishti-As-Of` and `X-Drishti-Known-At`, methods
`GET` and `POST`. The console's own cookie routes never answer CORS. A request from an origin that is not registered gets `DRS-8002`.

### 1.3 Five minutes: a static page

1. **Switch embedding on** and **register your application** (chapter 2), with your page's exact origin and the scopes you need.
2. **Give your backend a token route** that exchanges its signed-in user for an embed token ([4.1](#141-the-token-exchange)).
3. **Add one script line, a token provider and an element:**

```html
<script type="module" src="https://drishti.bank.example/embed/v2/elements/2/drishti-elements.js" crossorigin="anonymous"></script>
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

That is all of it. `server` defaults to the origin the module was loaded from (the console), and several elements on a page share one
connection. The script is Elements **2.x** (under `/embed/v2/`): it draws `<drishti-view>` and `<drishti-panel>` (and `<drishti-screen>`, an
alias of `<drishti-view>`) with Drishti Render from the ViewModel ([5.2](#152-elements-2x-drawn-by-the-renderer)).

**A working host to copy.** `tools/elements-demo` in the repository is a complete host application with its own sign-in, a backend doing
the token exchange, a recent list and an as-of picker, served under a strict policy
(`python3 tools/elements-demo/server.py --help`; `/?tt=1` also enforces Trusted Types).

### 1.4 How a user gets in

Drishti never sees your user's password and your page never sees your secret. Whichever way the token comes, the server checks it on
**every call** (signature, audience, expiry, the application, the user, the origin, the scope and kind or report, the rates) and then
acts as that user, with their roles as they are now and with every field mask applied whatever their roles say.

| `auth` | Who vouches for the person | Use it for |
|---|---|---|
| `'token'` (the default when you give `token` / a `tokenProvider`) | your backend, by the token exchange | a portal, a CRM, any application with a server |
| `'popup'` | the bank's single sign-on, in one small window the person opens with a click | a page with no backend |
| `'session'` (the default otherwise) | the console's own sign-in cookie | the console's pages, a same-origin proxy |

#### 1.4.1 The token exchange

Your **backend** exchanges *its* credentials and *who the user is* for a **short-lived, read-only embed token** (RFC 8693,
`POST /api/v1/embed/token` on the server) and hands the token to its own page. The request (form encoded; client authentication by
`Authorization: Basic base64(id:secret)` or by a signed client assertion, `private_key_jwt`):

```
POST /api/v1/embed/token
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
subject_token=<an assertion your backend signs about the user, or the user's OIDC ID token>
subject_token_type=urn:ietf:params:oauth:token-type:jwt        (or ...:id_token)
audience=https://drishti.bank.example                           (the console's public URL)
scope=embed:view                                                (within the application's own scopes)
```

and the answer is `{"access_token": "<JWT>", "token_type": "Bearer", "expires_in": 300, "scope": "embed:view"}`. The field-by-field
reference, the claims and the refusals are in [API_REFERENCE.md](API_REFERENCE.md).

**Saying who the user is.** Either the user's **ID token** from the identity provider both applications use (needs single sign-on on
the server), or a **JWT your backend signs** about its user with its registered key (`sub`, `iat`, `exp` at most 60 s ahead, a `jti`
never used before; RS256/384/512 or ES256/384). There is no plain "username" field: a stolen client secret alone cannot make anyone
anyone. The user must already exist in Drishti and be enabled.

The backend of the demo host (Python, the standard library only, the assertion signed with RS256) is `tools/elements-demo/server.py`, the
`mint` method. The same exchange in other languages:

```javascript
// Node (an Express route; fetch is built in)
app.get('/api/drishti-token', async (req, res) => {
  const user = req.session.user;                       // who is signed in to YOUR application
  const now = Math.floor(Date.now() / 1000);
  const assertion = signJwt({ iss: 'crm', sub: user, aud: DRISHTI + '/api/v1/embed/token', iat: now, exp: now + 60, jti: crypto.randomUUID() }, MY_PRIVATE_KEY);
  const r = await fetch(DRISHTI + '/api/v1/embed/token', {
    method: 'POST', headers: { Authorization: 'Basic ' + Buffer.from('crm:' + SECRET).toString('base64'), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange', subject_token: assertion,
      subject_token_type: 'urn:ietf:params:oauth:token-type:jwt', audience: CONSOLE, scope: 'embed:view' }) });
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
        + "&subject_token_type=" + enc("urn:ietf:params:oauth:token-type:jwt") + "&audience=" + enc(console) + "&scope=embed%3Aview";
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(drishti + "/api/v1/embed/token"))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("crm:" + secret).getBytes()))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(BodyPublishers.ofString(form)).build(), BodyHandlers.ofString());
    JsonNode t = json.readTree(r.body());
    return Map.of("token", t.get("access_token").asText(), "expiresIn", t.get("expires_in").asInt());
}
```

**Tokens over time.** A token lives five minutes by default (the application's own setting, never more than
`drishti.embed.token-max-seconds`). The provider may be sync or async and return a string or `{token, expiresAt}` (else the token's own
`exp` counts). The library reuses a token until **60 seconds before it ends** (40 % of its life when that is shorter: a 30 s token is
replaced at 18 s), and asks once more after a `401`. An open live stream is handed the fresh token in place, so nothing redraws and the
connection is not reopened; a stream that gets no fresh token in time ends with `DRS-8001`. If the provider fails or there is none, the
elements say so with `drishti:token-needed` and wait; set `tokenProvider` and an element that failed with a 401 reloads by itself. A
token is never put in a URL, `localStorage`, `sessionStorage` or a cookie. Disabling the application or the user stops their tokens at
the next call (open streams within `embed.recheck_seconds`).

**Masks.** An embed call **always masks**: `drishti.security.redact` fields and a dataset's masked columns read `•••` whatever the
user's roles, so a user with the raw power sees real names in the console and `•••` in your page. `drishti:masked` tells you what was
masked. An embed token never writes.

#### 1.4.2 A page without a backend: the sign-in window

A page that has **no backend of its own** (a static intranet page, a SharePoint page, a team dashboard) cannot run the exchange. It uses
**`auth: 'popup'`**. What the person sees: in place of the screen, a card with the product's name and a **Sign in to see this** button.
Nothing opens by itself. On the click, one small window goes to the single sign-on (already signed in: it opens and closes at once,
nothing to type), and the screen is drawn in place. On a phone the window is a new tab that closes itself; where no window can open (an
embedded webview), the page goes to the sign-in and comes back.

```html
<!-- the renderer: one call (the library from the console's embed path; your page needs no server) -->
<div id="risk"></div>
<script type="module">
  import { render } from 'https://drishti.bank.example/embed/v2/render/3.3.2/drishti-render.js';
  const view = render(document.getElementById('risk'), { kind: 'trade', id: '1000008' },
                      { auth: 'popup', server: 'https://drishti.bank.example' });
  const el = view.container;
  el.addEventListener('drishti:auth-required', (e) => console.log('the button is shown', e.detail.allowed, e.detail.code));
  el.addEventListener('drishti:signed-in', (e) => console.log('signed in as', e.detail.user));
  el.addEventListener('drishti:signed-out', (e) => console.log('signed out:', e.detail.reason));   // expired | refused | host
</script>
```

```html
<!-- Elements 2.x: the same with a tag (every element of the page shares the one sign-in) -->
<script type="module" src="https://drishti.bank.example/embed/v2/elements/2/drishti-elements.js"></script>
<drishti-view auth="popup" kind="trade" entity="1000008"></drishti-view>
<!-- or for the whole page: DrishtiElements.configure({ auth: 'popup' }); DrishtiElements.signOut() signs out -->
```

- **The token** comes back to your page by `postMessage`, sent to your page's **exact origin** and accepted only from the console's own
  window with the random state the page sent: checked both ways. It is kept **in memory only**. A reload asks again (a click; already
  signed in to SSO, nothing to type).
- **Renewal** is silent while the single sign-on session lasts (the provider's refresh token, sealed by the console so the page cannot
  read or change it), shortly before the token ends, at most `embed.sign_in.max_hours`. When it can no longer be renewed:
  `drishti:signed-out` (`reason: 'expired'`) and the button comes back; a view refused with `401` does the same (`reason: 'refused'`).
  `handle.signOut()` (or `DrishtiElements.signOut()`) forgets it (`reason: 'host'`).
- **Only registered pages.** Your page's origin must be one of a host application's origins (chapter 2). Before drawing the button the
  library asks the console (`GET /embed/v2/sign-in/check`, no data, no credentials); any other page gets the button **disabled**, with
  `DRS-8002` and its origin named, and no window. The application's scopes, kinds and token life apply.
- **Take over at any point:** `drishti:auth-required` (`{server, allowed, code, detail}`) tells you the button is shown; call your own
  login and pass the token with `handle.signIn({ token, expiresIn })`. `handle.user` names who signed in; `drishti:signed-in` carries
  `{user, server}`, `drishti:signed-out` `{reason, server}`. An element waiting for the click is in the state `sign-in`.
- **The window's mode:** `signInWindow` (`auto`, the default: a new tab on a phone, a small window elsewhere; or `popup`, `tab`,
  `redirect`). With `redirect` the page goes to the sign-in and comes back with a one-time code in the fragment, taken out of the address
  at once and redeemed within two minutes, for that origin only.
- **What the administrator sets once:** single sign-on on the console and the server, the provider's client allowing the console's
  `…/embed/v2/sign-in/callback` as a redirect URI, and the host application registered
  ([ADMIN_GUIDE.md](ADMIN_GUIDE.md#51-the-sign-in-window-for-pages-that-embed-views)). Every sign-in through the window is
  audited as `embed-sign-in-window` with the page's origin, the user, the application and the time.
- **Live frames:** `render()` with `auth: 'popup'` draws the view as it is (`live` off unless you ask, because the console's own stream is
  a session route); `<drishti-view auth="popup">` is live over the page's one channel, as with a token.

#### 1.4.3 Inside Drishti's console

On the console's own origin `auth: 'session'` uses the console's sign-in: nothing to configure. A same-origin reverse proxy in front of
the console works the same way.

### 1.5 The elements, one by one

| Element | Shows |
|---|---|
| `<drishti-view kind="trade" entity="MX-20000001">` | a live view of one entity: header, key figures, every panel, as the console draws them |
| `<drishti-panel kind="trade" entity="MX-20000001" panel="legs">` | **one panel** of a view, in a card of your own |

Importing the library defines the elements once; importing twice is harmless.

#### 1.5.1 The reference

Every attribute is reflected to a property of the same name in camelCase (`as-of` is `asOf`). Events are `CustomEvent`s on the element,
`bubbles: true, composed: true`, named `drishti:<name>`. The TypeScript declarations of the renderer (`drishti-render.d.ts`) hold the same types.

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
| `masked` | `{ count?: number; panels?: string[]; columns?: string[] }` | what the user is shown has masked fields (count and panels) |
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

**The page object, `DrishtiElements`** (one per page): `configure({ server, tokenProvider, auth })` (or `element.tokenProvider = fn`),
`resolve(text)` (a typed command such as `TRD MX-20000001` to `{ref: {kind, id}, mnemonic, title}`, for your own search box),
`invalidateToken()` (your user changed: the next call asks the provider again; a token for another user closes the connection and every
element reloads), `connections()` (how many live connections the page holds: one per console), `signOut()` and `version`.

**Slots** (`<drishti-view>`, `<drishti-panel>`):

| Slot | Shown | Default |
|---|---|---|
| `loading` | while loading and no view has been painted yet | nothing (the element dims the view it has, while it reloads) |
| `empty` | no `kind`/`entity` yet (state `idle`) | nothing |
| `error` | an error with no view to keep | the DRS code and detail (`role="alert"`); with a slotted node, your content replaces it, and `drishti:error` still fires |

**Keyboard.** Shortcuts work **only while the focus is inside the element** (never on your `document`): `?` opens About, `Esc` closes
it, `Z` zooms the focused panel to the element's own box (`Esc` restores; `Shift+Z` asks for full screen), `Alt+1..9` move between
panels, and the table keys work inside a table. The console's global keys (`F1`, the command line, workspace panes, layout mode) are not
bound.

**Loading, live and reconnecting.** An element loads when it has both `kind` and `entity`; attribute changes in one task are coalesced
into one load, and an attribute changed mid-load aborts that load, so only the latest answer is ever painted. A live element applies
each tick in place (strip cells, whole panels, chart data), keeping scroll, selection, focus and zoom; it repaints (`drishti:paint`) only
on the first load, an attribute change, `refresh()` or `reload()`, the entity coming back after a deletion, or Retry after an error.
`refresh()` keeps a table's sort, page and filter where the panel did not change. When the connection drops, every live element goes to
`reconnecting` and keeps showing what it has; the connection retries with back-off (2 s doubling to 30 s, with jitter) and each element
fetches a fresh view when it is back. An element **pauses** when the page has been hidden, or the element off-screen, for 10 s: it
gives its subscription back and, on return, fetches a fresh view and subscribes again. A deleted entity greys the view under a banner.

#### 1.5.2 Elements 2.x: drawn by the renderer

Elements 2.x draws `<drishti-view>` and `<drishti-panel>` with Drishti Render from the ViewModel the embed API v2 answers as JSON. Its features:

- **`navigation`**: unset, a link changes `kind` and `entity`; `in-place` draws the linked entity in the element with a Back
  bar ("← Back to IRS-48213", Alt+← returns); `host` sends only the event.
- **`auth="popup"`** and `DrishtiElements.configure({ auth: 'popup' })` ([4.2](#142-a-page-without-a-backend-the-sign-in-window)), with the
  element state `sign-in` until the person signs in.
- **The console's behaviours inside the element**: per-column filter funnels, Chart any table, drill-downs in place, the nested pivot as
  a tree grid, zoom and the Sutra's controls. About this page is a side sheet inside the element (the `?` key or the head's About
  button).
- **`element.handle`**, the renderer's handle ([6.2](#162-sources-and-the-handle)), and the renderer's events: `drishti:about`,
  `drishti:panel`, `drishti:row-click` and `drishti:select`.
- **`<drishti-screen>`**, an alias of `<drishti-view>`.

`DrishtiElements.version` names the Elements version and
`renderer`, `contracts` and `api` the rest. A host that enforces Trusted Types names `drishti-render` beside `drishti-elements`.

### 1.6 Drishti Render: one import, one call

Drishti Render paints a Drishti screen in the browser from the server's ViewModel. It is plain ES modules with no build step, served by
the console under a versioned path, with the vendored ECharts and nothing from a CDN. The screen draws inside your container, in a
Shadow DOM, follows live updates, and navigates **in place** with its own back stack. Elements 2.x is built on it.

#### 1.6.1 The quick start

```html
<div id="risk"></div>
<script type="module">
  import { render } from 'https://drishti.bank.example/static/render/3.3.2/drishti-render.js';
  const view = render(document.getElementById('risk'), { kind: 'trade', id: 'IRS-48213' }, { behaviours: 'console', about: true });
  view.container.addEventListener('drishti:navigate', (e) => console.log('opened', e.detail.kind, e.detail.id));
</script>
```

or the tag `<drishti-screen kind="trade" entity="IRS-48213" theme="crimson-dark"></drishti-screen>` after the same import. On the
console's own origin (or a same-origin proxy) this runs as the person signed in to the console. **Another origin** imports
`/embed/v2/render/3.3.2/drishti-render.js` and gives a `token` provider ([4.1](#141-the-token-exchange)) or `auth: 'popup'`
([4.2](#142-a-page-without-a-backend-the-sign-in-window)).

**Defaults for everything.** A call with no options draws in a Shadow DOM with the library's own sheet, in the theme your custom
properties give it (`--d-*` on the container, or the default), follows the view live when the server says it is live, opens links **in
place** with a back stack, and reads as the person signed in to the console or with your token. Every option only overrides.

**Typed.** `drishti-render.d.ts` beside the entry declares every option, method, event and the ViewModel's shape.

#### 1.6.2 Sources and the handle

```js
const view = render(container, source, options);   // returns at once; view.ready settles after the first paint
await view.ready;
view.patch(frame);              // a live frame {seq, generation, patches} (VIEWMODEL.md section 5)
view.apply(message);            // a stream message (6.6)
const stop = view.subscribe();  // follow the view's stream (default: this console's relay), returns stop()
view.subscribe((on) => myChannel.subscribe(key, on));     // your own channel (on.frame, on.view, on.gone); returns stop()
view.navigate({ kind: 'counterparty', id: 'CP-NORTHBRIDGE' });   // as a click would
view.back();  view.depth;       // the in-place back stack
view.setTheme('crimson-dark');  view.reload();  view.dispose();
view.viewModel;                 // the ViewModel shown now, patches applied
view.drawPanel(panelView);  view.drawStrip(cells);  view.drawAbout({ explain, index });   // parts for a host that places them
view.drawCharts(scope);  view.about();                     // charts of what the host placed; the About sheet (about: true)
view.signIn({ token, expiresIn });  view.signOut();  view.user;   // with auth: 'popup' (4.2)
patch(view, frame); subscribe(view, source);         // the same as functions
```

**`source`** is one of: a ViewModel (the JSON of `GET /api/v1/views/{kind}/{id}`); `{kind, id, controls?}` (fetched as the person; controls
travel as `ctl.<id>` parameters, the business date as `X-Drishti-As-Of`); `{stream: url}` (a Server-Sent Events stream of whole views,
frames and stream messages); or an async iterable of stream messages. In a framework, call `dispose()` when the component goes;
`render()` into the same container replaces cleanly.

#### 1.6.3 Options

Every option has a default:

| Option | Default | Meaning |
|---|---|---|
| `shadow` | `true` | draw in a Shadow DOM with the library's sheet; `false` draws in the container and your sheets style it |
| `theme` | `'inherit'` | a console theme (`light`, `terminal`, `blue`, `green`, `crimson`, `crimson-dark`, `wallstreet`, `terminal-blue`, `terminal-green`, `terminal-crimson`) or your custom properties |
| `server` | `''` | the console that serves data and links; `''` is this page's origin |
| `navigation` | `'in-place'` | `'in-place'`, `'host'` (events only), `'new-window'`, or `'follow'` (a link is a link: `drishti:navigate` first, cancel it to stay, then the browser follows it, so Ctrl+click works) ([6.5](#165-navigation-in-place)) |
| `consoleLinks`, `outsideLinks` | `'event'` | a link to a console page (help, designer, connector) or to a page outside Drishti: an event (default), `'follow'` or `'new-window'` |
| `live`, `subscribe` | `true`, off | follow a live view; for a ViewModel source, subscribe at once (`true` or an SSE URL) |
| `token`, `credentials` | none, `same-origin` | a bearer token provider; fetch credentials (`include` with a `server`) |
| `auth`, `signInWindow` | `token` with a token, else `session`; `auto` | how the person gets in ([chapter 4](#14-how-a-user-gets-in)); the sign-in window's mode (`auto`, `popup`, `tab`, `redirect`) |
| `device` | none | `phone`, `tablet` or `desk`, sent as `X-Drishti-Device` |
| `behaviours` | `'library'` | `'library'`: the painter's own (sorting, the filter box, paging, trees, tabs, pivots, a Data table under each chart); `'console'`: those and the table tools (a funnel on each column heading: the values with counts, a search, Select all, a number condition such as `> 1m`; Chart any table: the rows as shown opened as a chart in a dialog inside the container); `'host'`: as `'library'` (renderer 1.2.0: none but the widths) |
| `about` | `false` | an About button in the head and `?`: About this page drawn from JSON in a side sheet inside the container |
| `insight` | none | `{ about(ref, generation) }` answers About this page's JSON from your own source instead of `aboutUrl` |
| `designOn` | `false` | "How this view was built" links its Sutra to the designer |
| `viewUrl`, `streamUrl`, `aboutUrl`, `recordsUrl`, `panelsUrl`, `eventSource` | the console's | where the view, its stream, About, a pivot's rows and a control's panels come from, when you proxy the console or need your own EventSource |
| `tables`, `help`, `keys`, `header`, `panels`, `label`, `asOf` | `true`, `true`, `false`, `'full'`, all, the title, live | table behaviours, the `?` help links, the function keys under the view, the head, a subset of panels, the region's name, the as-of banners |

#### 1.6.4 Events

On the container; they bubble, cross the shadow boundary and are cancelable where you may take over:

| Event | Detail | Cancel to |
|---|---|---|
| `drishti:navigate` | `{target: 'entity'\|'console'\|'outside', kind, id, href, panel, source: 'link'\|'chart'\|'key'\|'api', navigation}` | route it yourself (the renderer does nothing) |
| `drishti:row-click` | `{panel, event, row, pick, cells}` for a row or bar that carries an event | keep the row unselected |
| `drishti:select` | `{panel, control, value}` for a row that sets a control | |
| `drishti:paint`, `drishti:loaded`, `drishti:ready` | the reference, generation, back-stack depth; `ready` when a streamed screen is complete | |
| `drishti:tick`, `drishti:deleted`, `drishti:restored` | what a live frame changed | |
| `drishti:error` | `{code, detail, fix, status}` | |
| `drishti:pivot`, `drishti:key` | a Pivot opened; a function key with no in-place action | draw it yourself |
| `drishti:panel`, `drishti:about` | a panel replaced by a live frame `{panel, element}`; the About sheet opened `{open, ref}` | |
| `drishti:auth-required`, `drishti:signed-in`, `drishti:signed-out` | the sign-in window ([4.2](#142-a-page-without-a-backend-the-sign-in-window)) | |

#### 1.6.5 Navigation in place

Nothing opens a new window or leaves your page unless you want it:

| Mode (`navigation`) | An entity link, a chart point, a map place, a link key | Your page |
|---|---|---|
| `'in-place'` (default) | `drishti:navigate` first; if not cancelled, the entity's view is fetched and drawn **in the same container**, the previous screen pushed on the renderer's back stack; a Back bar ("← Back to IRS-48213") and Alt+← return | never navigates, its address never changes |
| `'host'` | only `drishti:navigate`: you route it (your own router, a deep link, another container) | as you decide |
| `'new-window'` | `drishti:navigate`, then a new window | a new window, because you asked |

- **Drill-downs, tree rows, tabs, sorting, sections and folds** happen in place, with no change to your address.
- **Links to console pages** (the `?` help, the designer, a connector's page) and **links outside Drishti** (a bare `https://` value in
  the data) cannot be drawn in place: by default they are only `drishti:navigate` events (`target: 'console'` or `'outside'`); set
  `consoleLinks` / `outsideLinks` to `'follow'` or `'new-window'` to change that.
- **About and detail sheets** render inside the container's shadow root, never over your page.
- In `'in-place'` mode no window is ever opened; the one window the library may open is the sign-in window, and only on the person's
  click.

**Names your page sees.** Everything Drishti adds keeps out of your way: one global (`window.Drishti`; Elements adds `DrishtiElements`
and, for the console's behaviour scripts, `drishtiModules`), custom events `drishti:<what>`, custom elements `drishti-<name>`, storage
keys starting `drishti`, static files named `drishti_<name>`.

#### 1.6.6 Live updates and streamed screens

**Live.** `subscribe()` follows the view's stream; `patch(frame)` applies a live frame exactly as [VIEWMODEL.md](../architecture/VIEWMODEL.md)
section 1.5 describes: `strip` (the cell rebuilt), `panel` (the panel rebuilt and re-enhanced, its grid place, phone order, tab state and
fold kept; charts redrawn), `provenance` (the banners rebuilt), `deleted` (the view greyed under a banner, later patches ignored),
`restored` (the view fetched again). An op the renderer does not know is ignored; a patch never changes the screen's structure. A
second whole `view` on the stream (a reconnect, a newer generation) repaints. A host that needs headers on the stream gives
`eventSource(url)`, or hands its own channel with `subscribe(fn)`.

**Streamed screens.** `render()` also takes a screen that arrives panel by panel (`{stream: url}` or an async iterable), through the same
paint and patch: the skeleton is painted at once in its final layout, each stub saying "Loading…", so nothing jumps. The messages (the
SSE event name is the type, or a `type` field on the default `message` event):

| Type | Payload | The renderer |
|---|---|---|
| `skeleton` | `{view}`: a ViewModel whose panels are stubs (`id`, `kind`, `title`, `area`, `span`, `height`, and the layout) | paints the final layout at once, each stub saying "Loading…" (`aria-busy` on the view) |
| `panel-add` | `{panel, after?}` | a panel the skeleton did not announce, after `after` (or at the end of its column) |
| `panel-replace` | `{panel}` | replaces the stub (or an earlier panel) with the bound panel |
| `patch` | `{frame}` | a live frame, as `patch()` |
| `done` | `{provenance?}` | the screen is complete: `aria-busy` cleared, `drishti:ready` |
| `error` | `{code, detail, fix?, panel?}` | a panel that could not bind shows its error; without `panel`, the screen does |

Every link, drill, event and action works on a streamed screen as on any other, because they are in the ViewModel. The end state equals
a one-shot render of the same screen.

**What the renderer draws.** Every panel kind of the ViewModel, its states (waiting, denied, empty, error, computing, stale, failed,
refused), the Sutra's layout grid, and every chart with its Data table, so nothing is only in the picture. The server formats every
value a person reads; the renderer prints the server's text and the server's tones. A kind it does not know becomes a titled panel saying
so. Below 640 px of the **container**, every grid is one column in the phone order, tables scroll inside themselves, nothing scrolls
sideways, and targets are 44 px (also on any coarse pointer).

#### 1.6.7 Errors

Every failure is a `drishti:error` event and, when there is nothing to show, a box in the container with the code, the words and the fix:

| Code | When | The fix it gives |
|---|---|---|
| `DRS-9101` | the container is not an element | Pass a DOM element, e.g. `document.getElementById("risk")` |
| `DRS-9102` | an unknown `navigation` | Leave it out for in-place, the default |
| `DRS-9103` | the source is neither a ViewModel, `{kind, id}`, `{stream}` nor a stream | `render(el, { kind: "trade", id: "IRS-48213" })` |
| `DRS-9104` | a reference without kind or id | the same |
| `DRS-9105` | a ViewModel speaks a contract major the library does not draw | Load the renderer version this server serves |
| `DRS-9106` | an unknown `behaviours` | Leave it out for the library's own, the default |
| `DRS-9107` | `behaviours: 'console'` could not load the console's scripts | Check that the console serves its static scripts, or use `behaviours: 'library'` |
| `DRS-9108` | an unknown `auth` | Leave it out: `token` when you give a token, else the console's session; `popup` for a page without a backend |
| `DRS-5003` | the console could not be reached | Check the server option, the network, and that the console allows this origin (CORS) |
| the server's own (`DRS-1001`, `DRS-5002`, ...) | the view was refused | the server's own `fix` when it gives one, else per code (`DRS-4023`: the edition or lens hides the kind), else per HTTP status: sign in or give a token (401), your role may not open this kind (403), no such entity here (404), retry (502, 504) |

A view refused with `DRS-4023` is no typo: the answer names the edition, or in the console the lens the person is inside ("You are inside
the Drishti World lens, which does not include trades: leave the lens (Views → Leave) or choose an entity it shows").

#### 1.6.8 The playground

Open **`/render/demo`** on your console (signed in). It renders any view you may open, or pasted ViewModel JSON, in every theme and
navigation mode, lists the events the renderer sends, and prints the quick start for what is on screen, ready to copy. It opens on an
entity your server holds and you may open (the first of a common kind, in your lens; `GET /render/demo/start`), says which lens you are
inside, and says why when there is nothing to draw.

### 1.7 React, Angular and Vue

The library is a function and custom elements, so every framework uses it as it is; no wrapper is needed. Events with a colon
(`drishti:select`) are bound with `addEventListener` in all three, because Vue and Angular read the colon in a template.

**Drishti Render:**

```jsx
// React
import { render } from 'https://drishti.bank.example/static/render/3.3.2/drishti-render.js';
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
// main.ts: import 'https://drishti.bank.example/static/render/3.3.2/drishti-render.js';
```

```vue
<!-- Vue (compilerOptions.isCustomElement: (t) => t.startsWith('drishti-')) -->
<template><drishti-screen kind="trade" :entity="id" /></template>
<script setup>import 'https://drishti.bank.example/static/render/3.3.2/drishti-render.js'; defineProps(['id']);</script>
```

A bundler that refuses URL imports copies the version's folder as it is ([10.4](#1104-the-npm-package-layout)).

**The elements.** Set attributes in JSX, bind events with a ref (React 19 passes properties to custom elements; for events use
`addEventListener`):

```jsx
function TradeView({ onState }) {
  const ref = useRef(null);
  useEffect(() => { const el = ref.current; el.addEventListener('drishti:state', onState); return () => el.removeEventListener('drishti:state', onState); }, [onState]);
  return <drishti-view ref={ref} kind="trade" entity="MX-20000001" theme="light" />;
}
```

**Angular** (add `CUSTOM_ELEMENTS_SCHEMA` to the module or component). Angular reads a colon in an event binding as a target
(`window:resize`), so bind these events with `addEventListener`:

```ts
@ViewChild('v', { static: true }) v!: ElementRef<HTMLElement>;
ngAfterViewInit() { this.v.nativeElement.addEventListener('drishti:state', (e) => this.onState(e as CustomEvent)); }
// template: <drishti-view #v kind="trade" entity="MX-20000001"></drishti-view>
```

**Vue 3**: `app.config.compilerOptions.isCustomElement = (t) => t.startsWith('drishti-')`, then
`<drishti-view kind="trade" entity="MX-20000001" @drishti:state="onState" />`.

#### 1.7.1 The sample pages

`docs/guides/examples/embed/` holds one host page for each framework, each embedding `<drishti-view>` with the page's own box for the entity, wired the way that framework wires custom elements: **React 18** (`createElement`, events bound
with a ref, the same in JSX), **Vue 3** (the runtime-only build and render functions, so no `eval`), **Angular 18** (a standalone
component with `CUSTOM_ELEMENTS_SCHEMA`, zoneless). They share `host.js` (the token provider) and `sample.css`, and run against the
elements demo host (`tools/elements-demo/server.py`), which serves them at `/samples/<framework>/` under a strict policy (script-src
`'self'` and the console, no inline script). The samples bind events in `onMounted` / `ngAfterViewInit` / a ref effect.

The framework builds are **not in the repository**. `bash docs/guides/examples/embed/fetch-vendor.sh` downloads the pinned versions once
(React 18.3.1 UMD, Vue 3.5.43 runtime build, Angular 18.2.14 ES modules, RxJS 7.8.2, tslib 2.8.1; all MIT) into
`docs/guides/examples/embed/vendor/` (ignored by git), and the pages load them from there: no CDN, offline afterwards. React 19 has no
UMD build; with a bundler the JSX is the one shown in the comments. Angular has no UMD build either, so its page uses an import map and
Angular's JIT compiler, which needs `'unsafe-eval'` and an inline import map: the demo host relaxes its policy for `/samples/angular/`
only. A real Angular application is compiled ahead of time (`ng build`) and needs neither; the component is the same. The React page,
drawn in Chromium against a real server:

![The React sample page: a search box with its results, the view and its About body](img/bi-elements/react-sample.jpg)

### 1.8 Theming and sizing

**Custom properties.** The sheets read the console's design tokens, which inherit through the shadow boundary: set them on the element
(or the render container) or any ancestor, and they win over the sheet's defaults. Charts read them when they draw, so a theme change
redraws them.

| Property | Meaning |
|---|---|
| `--d-bg`, `--d-bg-2`, `--d-surface`, `--d-surface-2` | backgrounds: page, alternate, panel, panel header |
| `--d-ink`, `--d-muted`, `--d-faint` | text: main, secondary, hints |
| `--d-border`, `--d-radius`, `--d-shadow` | frame |
| `--d-link`, `--d-accent`, `--d-accent-strong`, `--d-accent-tint`, `--d-on-accent` | links, emphasis |
| `--d-pos`, `--d-neg`, `--d-ok`, `--d-warn`, `--d-bad` | tones (gains, losses, status); charts take their palette from these |
| `--d-emph-bg`, `--d-highlight`, `--d-glow` | emphasised strip cell, live flash |
| `--d-font-sans`, `--d-font-mono` | fonts (your fonts work: the element loads none but its icon font) |

A token not listed is not part of the contract. `theme="inherit"` uses your values with `light` as the fallback; `terminal`, `light`,
`blue`, `green`, `wallstreet`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson` and `terminal-blue` are the console's
own. One override, for example: `#risk { --d-accent: #0b5cad }`.

**Parts** for spacing and borders without reaching into the shadow root: `::part(header)`, `::part(strip)`, `::part(grid)`,
`::part(panel)`. The panels' inner HTML is not a
contract: style it only through the custom properties and parts.

**Sizing.** Every element is `display: block`, as wide as its container, as tall as its content; give it a width the way you give any
block one (a grid cell, a flex item with `min-width: 0`, a fixed-width column), and `max-height` with `overflow: auto` if you want it to
scroll. Do not make it `display: inline` or shrink-wrapped: it is a **size container** with no intrinsic width of its own. The console's
width breakpoints (1700, 1400, 1100, 1000, 900, 760, 640 and 560 px) apply to **its own width**, not the page's, so an element in a
360 px sidebar of a wide page uses the phone layout (one column, a two-column key-figure strip, wrapped tab heads), and tables scroll
inside their panel and never widen your page. Charts follow the element's size, including your split panes and drawers.

**Touch.** A panel's `?` and a column or field hint open by tap. Where the element is at most 640 px wide, or the pointer is coarse, its
controls are at least 44 by 44 px. The About drawer is a right-hand side drawer sized to the element (at most 420 px) and becomes a
bottom sheet the element's full width at 640 px or narrower.

**Accessibility.** The view's title is an `h1`, every panel an `h3` that labels its section; every chart is `role="img"` with a sentence
and keeps its Data table; every control is a native button or link; tabs move with arrows, Home and End; Esc goes up a drill level;
colour is never the only signal; computing states and the pager are polite live regions, alerts use `role="alert"`, and a streamed screen
is `aria-busy` until it is done.

### 1.9 Your page's Content-Security-Policy

The elements and the renderer are written for a **strict host policy**: no inline script or style, no `frame-src`, no `'unsafe-inline'`
or `'unsafe-eval'`. A page that names the console in three directives works:

```
Content-Security-Policy: default-src 'self'; script-src 'self' https://drishti.bank.example; style-src 'self'; font-src https://drishti.bank.example;
  connect-src 'self' https://drishti.bank.example; img-src 'self' data:; object-src 'none'; base-uri 'none'
```

`style-src` does not need the console: the style sheets are adopted (`adoptedStyleSheets`), the icon font is added through the
`FontFace` API, charts are drawn inside the shadow root, and DOM is built with `createElement` and `textContent`, never from markup in
the data. A host that enforces **Trusted Types** adds `require-trusted-types-for 'script'; trusted-types drishti-elements drishti-render`
and nothing else. A view's `image` panel loads its `src` as your `img-src` allows. The
demo host runs under exactly this policy and its tests assert that no violation is reported.

**What you trust.** The shadow roots are **open**: your scripts can read inside them (and your tests can, [11](#111-cookbook)). That is why
an embed call always masks and never writes: a stolen token lets the thief read, for minutes, what the user could read on that host
anyway. Load the library by an exact version with its integrity hash (chapter 10).

### 1.10 Versions, integrity and lifecycle

#### 1.10.1 Where the library is served

| Path | What | Cache |
|---|---|---|
| `/static/render/<version>/…` | Drishti Render on the console's own origin (the console's pages, same-origin hosts) | as `/static/` |
| `/embed/v2/render/<version>/…` | the same files for other origins, with CORS for the registered origins; a partial version (`2`, `2.0`) answers `302` to the exact one | `immutable`, one year |
| `/embed/v2/elements/<2, 2.4, 2.4.0, 2.2, 2.2.0>/drishti-elements.js` | Elements 2.x by its own version: a `302` to the renderer that carries it (the renderer's version is the Elements version, 2.4.0; `2` answers the newest) | 60 s |
| `/embed/v2/elements/<library>/…` | the Elements libraries the current renderer imports (drishti-core, -grid, -chart, -data, -renderer), allow-listed | revalidated |

Served renderer version: the current one only (the renderer, like every Elements library, carries the one Elements version). The data routes the library calls (the ViewModel, About, panels, a pivot's rows, the live channel and resolve) are listed in [API_REFERENCE.md](API_REFERENCE.md).

#### 1.10.2 Integrity and SRI

A released version is **immutable**: a version always names **the same bytes**, and a change means a new version. Each version's folder
holds `integrity.json`, the SHA-384 (subresource integrity) of every distributed file, served at `/embed/v2/render/<version>/integrity.json`. Put the entry's `integrity` on the script line (SRI needs the exact version, not an alias):

```html
<script type="module" src="https://drishti.bank.example/embed/v2/render/3.3.2/drishti-render.js" integrity="sha384-…" crossorigin="anonymous"></script>
```

Because a module's own imports are not covered by the script's `integrity`, pin the rest with an import map's `integrity` (Chrome 127 and
later) listing the module files of `integrity.json`. The static files are served with `Access-Control-Allow-Origin: *` (public code, no
data), so `crossorigin` and SRI work from any page.

#### 1.10.3 Contracts and lifecycle

Three contracts, all semantic versions:

- **The element and render API** (attributes, properties, methods, events and their `detail`, options, custom properties, parts, slots)
  changes only in a major version of the library.
- **The ViewModel** carries `"contract": "1.0"`; the renderer refuses a major it does not draw (`DRS-9105`)
  ([VIEWMODEL.md](../architecture/VIEWMODEL.md)).
- **The embed API** (`/embed/v2`) changes additively within a major. The element sends `Drishti-Embed-Api: <major>.<minor>`
  and the console answers in the newest compatible shape. A major's end is announced with `Deprecation` and `Sunset` headers and a `Link`
  on every answer (the console's `embed.lifecycle`, one entry per major); from the sunset day its calls are `410 DRS-8006`.

A version this console does not serve answers `410 DRS-8006`, and the answer names the one it serves: after a console upgrade, a page
pinned to an old version tells you to move the pin.

**Retired renderer paths.** Any renderer version but the one this console serves, and any Elements 2.x version but the one it serves, answers `410` (`DRS-8006`) naming the version it serves; pin the one renderer.

**Renderer 3.3.2.** Every panel is drawn by Drishti Elements' painter (`drishti-views`); the API, the options, the events, the element
attributes and the contract are the same. What you may see: the painter's look (its title line, freshness line and function keys, a
filter box and pages on long tables, a Data table under each chart), a dash in an empty cell, a pivot's corner in its fields' own case;
`behaviours: 'console'` gives the painter's tables their tools (column funnels, Chart any table, in the container); the console's nested-pivot grid, drills in place and zoom are the painter's own pivots, trees and tabs; a GenUI stream's messages are not drawn (release i9).
The `3.1.0` paths are gone (`410` with `DRS-8006` under `/embed/v2/`): pin `3.3.2`.

#### 1.10.4 The npm package layout

Drishti Render's version folder is also laid out as an npm package, `@drishti/render`, for a host that bundles it (it is not published to
any public registry): `drishti-render.js`, `drishti-elements.js`, the modules, `drishti-render.css`, `drishti-render.d.ts`,
`integrity.json`, plus `package.json` (`"type": "module"`, `"exports": {".": "./drishti-render.js", "./elements": "./drishti-elements.js",
"./drishti-render.css": ...}`, `"types": "./drishti-render.d.ts"`) and the licence. The bytes are the same as `/embed/v2/render/<version>/`.
A host that bundles it must also serve the console's `js/`, `vendor/` and `css/` files beside it, or set `server` so the library loads
them from the console.

The Drishti Elements libraries are a separate project with
their own release tree ([section 1.13](#113-drishti-elements-libraries-in-your-own-app)).

### 1.11 Cookbook

**Master and detail: a list drives `entity`.** Set the attribute; the element reloads in place and cancels the request it was making.

```js
list.addEventListener('click', (e) => { main.setAttribute('entity', e.target.dataset.id); });
```

**Keep navigation in your application.** A linked entity inside a view fires a cancellable `drishti:navigate`; cancel it and take the
user where you like, or let it through and the element shows the entity in place.

```js
main.addEventListener('drishti:navigate', (e) => { e.preventDefault(); router.go('/trades/' + e.detail.id); });
```

**Keep counterparties in your CRM, trades in the element.**

```js
v.addEventListener('drishti:navigate', (e) => {
  if (e.detail.kind === 'counterparty') { e.preventDefault(); location.href = '/clients/' + e.detail.id; }
});
```

**Your own search box.** `DrishtiElements.resolve('TRD MX-20000001')` returns `{ref: {kind, id}}` for a typed command (`TRD MX-20000001`,
`CPTY CP-1042` or a bare id); set `kind` and `entity` from it. A resolved command is not added to the user's command history.

**A live indicator of your own.** `drishti:state` fires on every change (`loading`, `live`, `reconnecting`, `paused`, `error`): bind it to your page's own badge. `drishti:tick` says a live update was applied.

**Two elements side by side, one connection.** Elements with the same `server` share one stream; `DrishtiElements.connections()` is `1`
however many there are, and two elements on the same entity share one subscription. (Twenty elements on ten entities on one page: one
channel stream.)

**A past business date.** `<drishti-view as-of="2026-10-09">` loads a static snapshot (the console's own as-of rule); `as-of="live"` goes
back.

**A dark host.** `theme="inherit"` and your own `--d-bg`, `--d-surface`, `--d-ink` on an ancestor, or `theme="crimson-dark"`.

**One panel in a card.** `<drishti-panel kind="trade" entity="MX-20000001" panel="legs">`: the header is off unless you ask for
`header="strip"`.

**A view built with control values.** `<drishti-view kind="netting-set" entity="NS-NORTH-01" controls="legs=2">`: the element draws the
Sutra's controls inside itself; a reader's change asks the console for only the panels that read it, fires `drishti:controls` and
updates the `controls` property, and never touches your page's address.

**Testing a page that embeds Drishti.** The elements have **open** shadow roots, so a test reaches inside: Playwright's CSS selectors
pierce them (`page.locator('#view .pnl')`), and `await expect(el).toHaveAttribute('data-state', 'live')`
waits for the state machine. `drishti-console/tests/test_embed_elements2_browser.py` is a complete example: a host on its own origin under a strict policy against a
real server.

### 1.12 Troubleshooting by code

The element puts the code in `drishti:error` (`detail.code`, `status`, `detail`, `fatal`) and shows it in the element when it has nothing
else to show; the renderer's own codes are in [6.7](#167-errors). The full list is in [OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#21-troubleshooting); the ones
an embedder meets:

| Code | Meaning | What to do |
|---|---|---|
| `DRS-8001` (401) | the embed token is missing, forged, expired or for another audience | check your provider returns a fresh token and the token request's `audience` is the console's public URL; a stream ends with this after `embed.token_grace_seconds` without a renewal |
| `DRS-8002` (403) | this origin is not one of the host application's | register the exact origin (scheme, host, port) in Admin → Embedding; `null` and wildcards are refused unless `drishti.embed.allow-wildcard-origins` |
| `DRS-8003` (403) | the host application is unknown or disabled | it was disabled or deleted in Admin → Embedding |
| `DRS-8004` (429) | over the embed call rate, or the console holds `embed.max_streams` streams | the answer carries `Retry-After`; raise the application's rates or `embed.max_streams` |
| `DRS-8005` (400) | the application may not show this kind | add the kind to the application's list |
| `DRS-8006` (410) | a version or API major this console no longer serves | pin the version the answer names, or upgrade the element |
| `DRS-4023` (404) | the server's edition (or, in the console, the person's lens) does not include this kind | the answer's `fix` says which and what to do (choose an entity it shows; leave the lens; an administrator adds the command to the edition); it is no typo in the id |
| `DRS-5002` (403) | a path the embed scope does not open, or a scope the application was not given | give the application the scope (`embed:view`, `embed:about`); an embed token never writes |
| `DRS-5010` (401) | the user behind the token is disabled or gone | sign the user in again; tokens are checked against the user on every call |
| `DRS-1001` (404) | no such entity, or one the user may not reach (the two look the same) | check the kind and id, and that the user may open it in the console |
| `DRS-5003` (503) | the console cannot reach the server | retried by the element; check the server |

**Nothing at all** (the element stays in `loading`, with no event): the token provider never answered. Open the browser's network tab:
the first request is to *your* backend. `window.DrishtiElements.connections()` and `element.state` / `element.lastError` say what the
element thinks. **The sign-in button is greyed out**: your page's origin is not registered (`DRS-8002` names it).

### 1.13 Drishti Elements libraries in your own app

The libraries the console draws with (grid and pivot, charts and maps, inputs, tree, graph, the data client, the designer kit) are Drishti
Elements, a separate project by the same owner that Drishti vendors at a pinned version
([docs/architecture/ELEMENTS_IN_DRISHTI.md](../architecture/THIN_CONSOLE_DESIGN.md)). Using them in your own app, served by a host or from
the release tree, is in its own docs: [USING.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/USING.md), with the
[API reference](https://github.com/drishti-platform/drishti-elements/blob/main/docs/API.md) and the
[contracts](https://github.com/drishti-platform/drishti-elements/blob/main/docs/CONTRACTS.md) a server answers (the live set, the errors, the
data capabilities). A Drishti console serves the pinned copy under `/static/vendor/drishti-elements/<version>/` and its gallery at `/elements`.

#### Names a host page sees

The console's scripts and styles keep out of a host's way: every static file we wrote is named `drishti_<name>` (`drishti_tables.js`,
`drishti_tokens.css`), our scripts set one global, `window.Drishti`, custom events are `drishti:<what>`, custom elements `drishti-<name>`
and storage keys start `drishti`. The names a published renderer or Elements version uses are its contract and stay as they are while it
is served: the globals `drishtiModules`, `drishtiBi` and `drishtiGeo`, the classes and data- attributes of its markup, and the addresses
it loads (`/static/js/<name>.js` and the embed routes' `/js/<name>.js` answer with the `drishti_` file). The developer guide
([DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#static-asset-names-and-the-one-namespace)) has the rules.

### 1.14 Moving an AG Grid Enterprise pivot app

Drishti Elements' grid takes AG Grid Enterprise-shaped options through its adapter (`createGridFromAgOptions`); the mapping, what is
covered and what is not, is in its [MOVING_FROM_AG_GRID.md](https://github.com/drishti-platform/drishti-elements/blob/main/docs/MOVING_FROM_AG_GRID.md).

### 1.15 Use Rachana and Elements in your own app, and GenUI

This guide is Drishti's side: how a page of yours embeds a Drishti view. Using the screen language and the widget libraries in an application
that has no Drishti server at all, and GenUI (an assistant composing a screen through tools; streaming a screen to the page is planned), are the products' own guides, and they are the one
home of those subjects:

- [Rachana and Elements in your own app](https://github.com/drishti-platform/drishti-rachana/blob/v1.1.1/docs/IN_YOUR_APP.md#rachana-and-elements-in-your-own-flask-or-fastapi-application): the
  renderer and the libraries, from one import to a screen of your own, with no copy of them here.
- GenUI (a screen an assistant composes and checks through tools): the GenUI kit ships with Rachana 1.1 (planned for the platform), and Rachana's
  [ROADMAP.md](https://github.com/drishti-platform/drishti-rachana/blob/v1.1.1/docs/ROADMAP.md) lists what is planned.

Drishti uses the same renderer (section 1.6) and the same libraries (section 1.13); what Drishti adds is the server's views, its sign-in
(section 1.4) and its data.

### 1.16 Drishti Elements in React, Vue and Angular

Three host pages, one per framework, each embedding `<drishti-view>` for a signed-in user. They are the examples of [section 1.7.1](#171-the-sample-pages); `tests/test_embed_frameworks_browser.py` runs all three in Chromium.

| Folder | Framework | How it runs without a build step |
|---|---|---|
| `react/` | React 18.3.1 | the UMD builds, `createElement` (the JSX is in the comments) |
| `vue/` | Vue 3.5.43 | the runtime-only global build (no template compiler, so no `eval`), render functions |
| `angular/` | Angular 18.2.14 | the ES-module builds through an import map and the JIT compiler, a standalone zoneless component |

`host.js` (the token provider, the demo sign-in) and `sample.css` are shared.

#### Run them

1. **Fetch the framework builds** (they are not in the repository; MIT; once, needs `node`/`npm` and the npm registry; offline afterwards):

   ```bash
   bash docs/guides/examples/embed/fetch-vendor.sh              # all three; or: --only react | vue | angular
   ```

   They are written to `docs/guides/examples/embed/vendor/` (git-ignored). The pages load them from there: no CDN.

2. **Start Drishti with embedding on and the demo host**, as in [tools/elements-demo/README.md](../../tools/elements-demo/README.md) (register the host application with the scopes `embed:view embed:about`).

3. **Open** `http://127.0.0.1:17968/samples/react/`, `/samples/vue/` or `/samples/angular/` (the demo host serves this folder at `/samples/`, under a strict content-security policy: no inline script and only the console beyond itself).

The Angular page is the exception to "strict": Angular has no UMD build, so it needs an inline import map and the JIT compiler (`'unsafe-eval'`); the demo host relaxes its policy for `/samples/angular/` alone. A real Angular application is built ahead of time (`ng build`) and needs neither: the component and its template are the same (`schemas: [CUSTOM_ELEMENTS_SCHEMA]`).

#### What each page shows

* The page's own box for an entity id (the host decides what a pick means; the element just draws it).
* The view (`<drishti-view>`), with its **About this page** side sheet, with the page's own live indicator (the `drishti:state` event) and a masked-fields badge (`drishti:masked`).

Events with a colon are bound with `addEventListener` in all three (Vue and Angular read the colon in a template), in `onMounted`, `ngAfterViewInit` or an effect.


## 2. Data out: Drishti's data to any client with Arrow Flight

**Arrow Flight** is a protocol for moving columnar data (Apache Arrow record batches) between programs over gRPC. Drishti answers it on
its own port so that any Arrow Flight client (a Python notebook, a Java service, a JDBC tool, or a program in any language with a
Flight library) reads Drishti's data, as the person it
belongs to, without a JSON step and without copying the lake: the kinds of every pack, BI model queries, pivot views, VaR's scenario P&L
vectors and the in-memory lake, each with the same entitlements, masks, busy protection and audit as the screens.

The exact form of every request, its schema and its errors are in the [reference](#2-data-out-drishtis-data-to-any-client-with-arrow-flight); a hands-on walk from a token to a
pandas DataFrame is [tutorial 17](../tutorials/17-data-out-with-arrow-flight.md); the clients have their own sections
in the reference ([Python](#21471-python), [Java](#21472-java), [JDBC](#21473-jdbc-dbeaver-datagrip-any-jdbc-program), [ODBC](#21474-odbc-excel-power-bi-tableau)).
Working examples to start from, every one read only (Python notebooks from a token to VaR scenario P&L, a live stream, DuckDB and the
in-memory lake; Java; Excel through Python) are in [examples/clients](../../examples/clients/README.md). Later: C#/.NET, R and Go client sections (the service already answers any Flight client). How the service is built and why: [DATA_ARCHITECTURE.md section 11](../architecture/DATA_ARCHITECTURE.md#11-arrow-flight-drishtis-data-to-any-client).

### 2.1 When to use Flight

| You want | Use |
|---|---|
| a figure, a document, a search, from a script, a few rows at a time | the REST API ([API_REFERENCE.md](API_REFERENCE.md)) with the same token |
| a table in a browser page (Drishti's own screens, an embedded view) | Arrow over HTTP: the page asks with `Accept: application/vnd.apache.arrow.stream` ([DATA_GUIDE.md](DATA_GUIDE.md)) |
| a whole table, a model's answer, a scope's P&L vectors into pandas, polars, Spark, a Java or .NET service, a JDBC tool | **Flight** |
| live updates in a program (each intraday VaR batch, each append to an in-memory table) | **Flight**, a stream ([section 2.7](#27-live-streams-and-resuming)) |
| the lake's files themselves (you already hold lake access) | the lake directly; Flight is the governed way for everyone else |

Flight is **read only**: nothing a client sends changes Drishti's data.

### 2.2 Turning it on

Flight is an engine of its own, off as shipped. It needs a restart to turn on, because its port opens at start.

**In the UI.** Admin → Engines → **Arrow Flight** → On, with a reason. The page says *restart needed*; restart the server.

**In the file.** `config/engines/flight.yaml` (every key is in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtienginesflight)):

```yaml
drishti:
  engines:
    flight:
      enabled: true                              # or DRISHTI_FLIGHT_ENABLED=true
      port: 18815                                # its own port, gRPC over HTTP/2
      tls:                                       # always TLS: the server's certificate
        certificate: /etc/drishti/tls/server.pem
        private-key: /etc/drishti/tls/server.key
        client-ca: ""                            # a CA bundle here asks every client for a certificate (mutual TLS)
```

**The start-up option.** Arrow's memory module needs one JVM option on Java 21: `--add-opens=java.base/java.nio=ALL-UNNAMED`. The server
jar's manifest carries it (`Add-Opens: java.base/java.nio`), so `java -jar` needs nothing more; a launcher that starts the server from
its classes adds the option itself.

When the server is up, its log says `Arrow Flight on 0.0.0.0:18815 (TLS), Arrow memory cap … bytes`, and Admin → Data out shows the
service running. Without a readable certificate and key the port stays closed and the page says why; the rest of the server runs.

### 2.3 Tokens and scopes, step by step

Flight takes **API tokens only** (never a browser session). A token reads as its owner, never more.

1. Open **My account → API tokens** and make a token with the scope **`data:read`** (every domain you may read) or
   `data:read:<domain>` (one domain: a pack such as `trading`, `bi` for BI models, `var` for VaR's vectors, a lake's name). Like every
   scope beyond `read`, it must expire within 90 days.
2. Copy the token (`drk_…`); it is shown once.
3. Give it to the client: in Python `Flight(url, token=…)`; every other client sends it at the handshake as
   `authorization: Bearer <token>` (or Basic with the token as the password).
4. The handshake answers with a **session** (`authorization: Bearer dfs_…`, an hour by default) that the client sends from then on; the
   token itself is checked again when the session ends.

**An application acting for a person.** A service account (a user with the `service` role) makes a token with `data:read`; the
application names the person in the header `x-drishti-on-behalf-of` at the handshake. The person's roles, masks and row rules then
apply, and the audit records both names. An embed token (Drishti Elements, an application on behalf of a signed-in person) is accepted
the same way.

Command line: the REST call that makes a token takes the scope too (`POST /api/v1/me/tokens` with `{"name": "risk notebook", "days": 30,
"scopes": ["data:read"]}`, [API_REFERENCE.md](API_REFERENCE.md#28-personal-api-tokens)).

### 2.4 What can be read, and how each is named

A request is JSON: a **form** and the **request** in the words the rest of Drishti uses (Rachana: kinds and paths, a pivot view, a
measure call). Full detail per form: [part 2](#2-data-out-drishtis-data-to-any-client-with-arrow-flight).

| Form | Request | Answered by | Rows |
|---|---|---|---|
| `kind` | `{"kind": "trade", "paths": ["TRADE_ID", "PRODUCT_TYPE"], "where": ["PRODUCT_TYPE = IRS"], "date": "2026-09-30"}` | Export data's reader (as a download) | the kind's rows on the day, the chosen paths |
| `model` | `{"model": "trades-basic", "query": {"select": {"fields": [...], "measures": [...]}, "where": [...], "sort": [...], "limit": 50}}` | MQ, BI's model queries | the query's answer |
| `pivot` | the view, as `POST /api/v1/pivot/query` takes it | the pivot engine | one per cell and measure |
| `measure` | `measure('var.scenarios', 'DESK:RATES', date: 2026-09-30)` or `{"measure": "var.parts", "scope": "DESK:RATES", "by": "position"}` | the VaR engine, computed when asked | one per scenario; one per part with its vector |
| `lake` | `{"lake": "feeds", "kind": "news.headlines", "cursor": "SEQ"}` | the lake configuration ([DATA_LAKE_GUIDE.md section 2](DATA_LAKE_GUIDE.md#2-the-lake-file-every-key)), then the kind's reader | the table's rows; appends live |

**VaR's numbers are the engine's.** A scope's vector is float64, as the engine sums; a position's or trade's row is float32, as the hot
file keeps it ([VAR_GUIDE.md](VAR_GUIDE.md), section 26), and the schema's metadata says which (`drishti.pnl.precision`). The vectors are
computed when asked and never stored ([VAR_GUIDE.md](VAR_GUIDE.md), section 2). A client that sums the position rows gets the scope's
vector within the parity rule; it is never a substitute for the scope's own VaR, which is a quantile, not a sum.

Python, the shortest way:

```python
from drishti_flight import Flight
f = Flight("grpc+tls://drishti-1.bank.example:18815", token="drk_…", ca="bank-ca.pem")
pnl = f.measure("var.scenarios", "DESK:RATES").to_pandas()
```

```text
     SCENARIO SCENARIO_DATE          PNL
0           0    2024-09-17  11278589.52
1           1    2024-09-18  17875177.30
2           2    2024-09-19  21633915.20
3           3    2024-09-20 -61160944.54
4           4    2024-09-23  17550177.08
..        ...           ...          ...
504       504    2026-09-23  17561028.82
505       505    2026-09-24  42481517.80
506       506    2026-09-25 -12337742.74
507       507    2026-09-28 -35955103.30
508       508    2026-09-29  -2062964.68

[509 rows x 3 columns]
```

`ListFlights` lists what a token may read (names only); the action `whoami` says who the service thinks you are, your scopes and limits.

### 2.5 The request path

```text
 client (pyarrow, Java, JDBC, any Flight library)
    │  TLS, port 18815
    ▼
 ┌──────────────────────────── a Drishti node ─────────────────────────────┐
 │  handshake: the token (API token or embed token) → the token services   │
 │             scope data:read[:domain] · on behalf of a person            │
 │  GetFlightInfo: the request → its plan → endpoints (real addresses)     │
 │  DoGet:  busy protection admits ─▶ the form reads as the person ─▶      │
 │            kind ─▶ Export data reader      (entitlements, masks)          │
 │            model ─▶ MQ                    (row rules, masks)            │
 │            pivot ─▶ the pivot engine      (viewer, edition)             │
 │            measure ─▶ the VaR engine      (the person's view)           │
 │            lake ─▶ lake configuration ─▶ the kind's reader              │
 │          ─▶ Arrow record batches (official Arrow Java), limits counted  │
 │  audit: who, for whom, what, rows, bytes, node · Admin → Data out       │
 └──────────────────────────────────────────────────────────────────────────┘
```

Nothing in the Flight service computes or filters data: every check and every figure is the one the screens use.

### 2.6 The cluster: endpoints, parallel reads, a node down

`GetFlightInfo` answers a plan: one **endpoint** per part, each with the **real addresses** of the nodes that can answer it (never
`localhost` or `0.0.0.0`; the engine file's `advertise` or the node's Drishti\*net address), the preferred one first.

```text
                 GetFlightInfo(request)
 client ─────────────────────────────▶ node A
        ◀──────── endpoints ──────────
                   part 0: A, B, C       (a kind, a model: any member reads through the cluster)
                   part 1: C, B          (an in-memory table: C owns the store)
 client ══ DoGet(part 0) ══▶ A
 client ══ DoGet(part 1) ══▶ C   ✗ down
        ══ DoGet(part 1) ══▶ B   ✓      (the next address of the part)
```

The Python client reads the parts in parallel (`parallel=4`) and tries the next address of a part when a node does not answer; a node
that answers with a refusal is not retried elsewhere. Every node answers Flight on the same port.

### 2.7 Live streams and resuming

A request with `"stream": true` keeps sending batches as they come: VaR's scenario vector after each intraday batch is applied
(`measure('var.scenarios', …)`), or the rows appended to an in-memory table (`form: lake` with a `cursor`, a whole-number column that grows
with each append). Each batch carries its **sequence number** as app metadata (8 bytes, little-endian): the date's intraday stamp, or the
largest cursor value of the batch. Keep the last one; after a break, ask again with `"resume": <sequence>` (to this node or another) and
the stream continues after it, nothing missed, nothing twice.

```python
req = f.request("measure", "measure('var.scenarios', 'DESK:RATES')")
for seq, batch in f.stream(req):
    last = seq
# … later, after a break
for seq, batch in f.stream(req, resume=last):
    ...
```

With `print(seq, batch.num_rows)` in the first loop, across one intraday batch of the stand-in pricing system (its P&L vectors, then its
scenario PVs), stopped from Admin → Data out:

```text
18 509
26 509
27 509
DRS-5610: stopped by drishti-dev-admin on Admin → Data out
```

The first batch is the scope's vector as it stands, with the stream's current sequence.

### 2.8 Limits, and what a refusal says

Each read is held to its person's **role limits** (the most generous of their roles; `limits` in the engine file): rows, bytes, time, and
the **Arrow memory** it may hold at once. All Flight reads of a node together hold at most `memory.total-mb`; busy protection counts that
memory with the rest of the server (Health's busy card, `arrow-flight`) and may ask a new read to wait (`DRS-9506`).

A refusal is a Flight status whose message starts with Drishti's code, and whose metadata carries `drishti-code`:

```text
pyarrow.flight.FlightServerError: DRS-5604: the read passed 50000000 rows, the limit of your role; 49938432 rows were sent: narrow the
request or read it in parts
```

The rows limit stops a read after its last whole batch; a live stream has no time limit on its whole life, only on each batch.

### 2.9 Audit and Admin → Data out

Every read is audited when it ends: who, on behalf of whom, the token's id (never its secret), the form and the request's text, the
part, rows and bytes, milliseconds, the node, and the refusal if any (`data.flight`, `data.flight-refused`). A kind's read also writes the
Export data's own line (`data.flight` on the kind).

**Admin → Data out** shows, in two views of one screen (switch with the toggle at its top):

- **The page** (the default): the service (on, port, TLS, Arrow memory against its cap), the nodes with their real addresses and the
  forms each serves, the live reads with **Stop**, the last day's readers with their refusals and the limit hit, the tokens with a
  Flight scope (revoked on Admin → Users), and the limits per role.
- **The map**: clients on the left, nodes on the right, a line per live stream (its thickness the bytes, red when refused), the selected
  stream's detail in a panel.

Both read `GET /api/v1/admin/data-out` ([API_REFERENCE.md](API_REFERENCE.md)); Stop is `POST /api/v1/admin/data-out/streams/{id}/stop` (the client
gets `DRS-5610`).

### 2.10 Operations

**Sizing.** A read holds about two batches of Arrow memory at once (`batch-rows` × the row's width; 65,536 rows of 20 numeric columns
≈ 10 MB a batch). `memory.request-mb` caps one read, `memory.total-mb` (`DRISHTI_FLIGHT_MEMORY_MB`) the node. Arrow's buffers are direct
memory, so the shipped launchers raise the JVM's `-XX:MaxDirectMemorySize` for them at start, from `tools/lake/memory_caps.py`: 256 MB,
plus the in-memory lake's stores, plus Arrow's budget (512 MB for the compute exchange and BI answers, `DRISHTI_ARROW_EXCHANGE_MB`, plus
`memory.total-mb` when Flight is on), and set Arrow's own cap (`-Ddrishti.arrow.max-bytes`) to that budget. With Flight on and the
defaults: 256 + 512 + 4,096 = 4,864 MB. Check it with `python3 tools/lake/memory_caps.py --arrow-only`; on ECS set `DRISHTI_ARROW_MB` to
what it prints ([OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#143-direct-memory-and-the-diagnostics-of-a-long-run), section 12.1a); the host must
have that memory beside the heap. Changing `memory.total-mb` needs a restart (the limit is set at start).

Measured on one node (the test data of 2026-09-30, the defaults): `memory_caps.py` prints 4864 with Flight on (`--arrow-only` 4608);
the server's log says `Arrow memory cap 4294967296 bytes`. A desk's `var.scenarios` (509 rows) holds about 52 kB of Arrow memory
while it streams. The bank's `var.parts` by position (5,000 rows, the `drishti.var.max-parts` limit, of 509 float32 each: 10.7 MB in
the client) peaked at 17.8 MB of Arrow memory on the node (`health()`'s `arrowBytes`, sampled during the read) and took 0.1 s;
`arrowBytes` is back at 0 when the read ends.

**The port.** Open TCP 18815 between clients and every node (each node is read directly). A load balancer passes it through as TCP (TLS
ends on the node, HTTP/2 inside); it must not terminate TLS, since the endpoints name the nodes themselves.

**Certificates.** The port presents the server's certificate (PEM chain and PKCS#8 key); renew it as the HTTPS one, then restart the
node (the port reads its files at start). With `client-ca` set, every client presents a certificate signed by that CA.

**Monitoring.** Admin → Data out (live reads, refusals, memory); Health's busy card (`arrow-flight`); the audit log (`data.flight`); the
action `health` answers a node's state to a probe holding a token.

### 2.11 Troubleshooting: the ten most likely errors

| You see | Cause | Fix |
|---|---|---|
| `DRS-5601` no token | the client sent none, or a browser cookie | send the API token at the handshake (`authenticate_basic_token`, `authorization: Bearer`) |
| `DRS-5601` unknown, revoked or expired | the token ended | make a new one on My account → API tokens |
| `DRS-5602` lacks the scope data:read | the token was made without it | make a token with `data:read` |
| `DRS-5602` not data:read:trading | a domain scope that does not open this domain | a token with that domain, or `data:read` |
| `DRS-5603` form is kind, model … | the ticket is not Drishti's JSON | write it as the reference shows |
| `DRS-5604` rows limit | the read is bigger than your role allows | filter it, read fewer paths, or ask for a role with a higher limit |
| `DRS-5607` memory | a batch passed the read's Arrow memory cap | fewer paths, a narrower scope; an administrator may raise `memory.request-mb` |
| `DRS-9506` busy | the node is short of memory or busy | wait the seconds it says |
| `DRS-5608` no node answered | every address of a part is down or unreachable | check the port in the firewall; ask GetFlightInfo again |
| a TLS error (`certificate verify failed`) | the client does not trust the server's CA | give the CA file (`ca=` in Python, `trustedCertificates` in Java) |

### 2.12 Flight SQL: SQL over the BI models from JDBC and ODBC tools

**Flight SQL** is the SQL dialect of Arrow Flight that the JDBC and ODBC drivers speak. Drishti answers it on the same port, read only, as
SQL over the **BI models**: a statement is written in the server's own SQL (DSQL, [API_REFERENCE.md › Drishti SQL](API_REFERENCE.md#8-drishti-sql-apiv1bisql))
and answered by the same path as `POST /api/v1/bi/sql`, never by a second SQL engine. Each statement becomes a `form: model` read
(`{"model": …, "sql": …}`), so the token, its `data:read` scope (or `data:read:bi`), the BI power, the model's row rules and masks, busy
protection, the limits, Admin → Data out and the audit apply exactly as for any other read.

**Naming the model.** A DSQL statement reads one model, its `FROM` naming the model's aliases (`FROM trades`). Say which model in one of
two ways:

- a first line of the statement: `-- model: trades-basic@1` (or `/* model: trades-basic@1 */`), which works in any tool;
- the connection: the JDBC URL's `model=trades-basic@1` (the driver sends a property it does not know as the header `model`).

```sql
-- model: trading-risk@1
SELECT trades.DESK AS desk, MEASURE('Notional') AS notional, COUNT(*) AS n
FROM trades
GROUP BY trades.DESK
```

```text
desk	notional	n
DESK-COMM	8.850170019852502E11	5990
DESK-CREDIT	3.4835024551415E11	1172
DESK-EQD	4.3052971792252997E11	1452
DESK-FI	4.0315015623297003E11	1248
DESK-FX	3.7284221311835E11	1428
DESK-INFL	1.299456220881E11	396
DESK-MM	1.30992079528E11	480
DESK-RATES	6.0533722239374E11	1832
DESK-SFT	2.27897073212E11	672
DESK-STRUCT	1.06026316978E11	330
```

**What a tool browses.** One catalog (the product's name), a **schema per model** the person may read (`trading-risk@1`), a **table per
alias** of the model (`trades`, `counterparties`) with the columns the model describes for that person (hidden fields left out). Table
type: `TABLE`. A tool's browse never fails on a model the person may not read: it is not listed.

**What it refuses.** Anything but a query: updates, inserts, DDL, bulk ingestion, transactions, Substrait plans and statement parameters
(`UNIMPLEMENTED`, read only); `EXPLAIN` (the BI SQL console answers it); a statement that names no model (`DRS-5603`, with how to name
one). Prepared statements are read only and stateless: preparing returns the result's schema, executing reads it.

Clients: [JDBC](#21473-jdbc-dbeaver-datagrip-any-jdbc-program) (DBeaver, DataGrip, any JDBC program; the driver is one jar) and [ODBC](#21474-odbc-excel-power-bi-tableau)
(an installer, so not on machines where nothing may be installed). The statements and the catalog calls are in the
[reference](#2146-flight-sql), section 2.14.6.

### 2.13 Testing

`drishti-flight`'s `FlightServiceTest` runs the service over TLS with a stand-in backend (the handshake, a plan, batches, the limits, a
stream with resume, Stop); the server's `FlightServerTest` runs it inside the server with real tokens, entitlements and the audit; the
`FlightSqlServiceTest` runs Flight SQL in process (statements, the model by comment and header, the catalog, prepared statements,
an update refused); the console's `test_flight_client.py` runs the Python client against a stand-in service over TLS. On one node, every read form, the
stream across an intraday batch, Stop and resume are run against a real server ([tutorial 17](../tutorials/17-data-out-with-arrow-flight.md)).
The end to end on a three-node cluster (endpoints on every node, a node stopped mid-read, a VaR stream through an intraday batch) is
**not measured yet**; it is listed in [UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md).

### 2.14 Arrow Flight reference

Every request form, its JSON, its schema in Arrow, its errors, the scope it needs and its limits. How to turn Flight on, tokens, the
cluster and operations: [part 2](#2-data-out-drishtis-data-to-any-client-with-arrow-flight). The clients, one section each: [section 2.14.7](#2147-clients). Error codes with their HTTP equivalents: [API_REFERENCE.md](API_REFERENCE.md#error-code-table).

#### 2.14.1 The calls

| Call | Takes | Answers |
|---|---|---|
| Handshake | the token in the headers: `authorization: Bearer drk_…` or Basic (any user, the token as the password); `x-drishti-on-behalf-of: <person>` for an application's token | `authorization: Bearer dfs_…`, the session the client sends from then on |
| `GetFlightInfo` | a descriptor whose **command** is a request (section 2.14.2) | the schema, one endpoint per part (its ticket and the nodes' `grpc+tls://host:port`), rows and bytes expected (-1: not known) |
| `GetSchema` | the same descriptor | the schema only |
| `DoGet` | a ticket: an endpoint's, or a request written by hand (no `part`: part 0, read here) | record batches; a stream's batches carry their sequence as app metadata |
| `ListFlights` | nothing (criteria ignored) | one descriptor per kind, model, measure and lake table the token may read; no schema, no data |
| `DoAction` `health` | nothing | `{"node", "arrowBytes", "arrowCap", "live"}` |
| `DoAction` `whoami` | nothing | `{"person", "onBehalfOf", "tokenId", "roles", "scopes", "limits": {"rows", "bytes", "seconds", "memory"}}` |
| `DoPut`, `DoExchange` | | refused (`UNIMPLEMENTED`): Flight is read only |

Path descriptors are refused (`DRS-5603`): a Drishti request is always a command.

#### 2.14.2 The request

```json
{"form": "kind | model | pivot | measure | lake",
 "request": { … } or "Rachana text",
 "part": 0,            (in a ticket: which endpoint of the plan; left out: 0)
 "stream": true,       (a live stream; kinds, models and pivots are snapshots only)
 "resume": 41}         (a stream: the last sequence held)
```

At most 1 MB. A bad request is `DRS-5603` with the reason.

#### 2.14.3 The forms

##### 2.14.3.1 `kind`

```json
{"form": "kind", "request": {"kind": "trade", "paths": ["TRADE_ID", "PRODUCT_TYPE", "QUANTITY"], "where": ["PRODUCT_TYPE = IRS"], "date": "2026-09-30"}}
```

| Key | Means |
|---|---|
| `kind` | the kind, as Export data names it |
| `paths` (or `columns`) | the columns; none: every column the person may see. A masked column is left out; naming one in `where` is `DRS-5602` |
| `where` | lines as Export data takes them: `FIELD = value`, `!=`, `in`, `contains`, `starts`, `>`, `<`, `between`, `is empty`, `is not empty` |
| `date` | the business date (a date the kind holds); none: the latest |

Schema: one field per path, typed by the kind's data contract: `integer` int64; `amount`, `rate`, `number`, `decimal` float64; `boolean`
bool; anything else utf8 (a nested value as its JSON). Scope: `data:read` or `data:read:<the kind's pack>`. Audited twice: the Flight
line and Export data's `data.flight` on the kind. One part; any member may answer it.

##### 2.14.3.2 `model`

```json
{"form": "model", "request": {"model": "trades-basic", "query": {"select": {"fields": ["trade.DESK"], "measures": ["MTM"]}, "sort": [{"by": "MTM", "desc": true}], "limit": 50}}}
```

`query` is an MQ query ([README.md](bi/README.md)); the request may also be the MQ text or JSON with `model` in it, or
`{"model": …, "sql": "<a DSQL statement>"}` (what Flight SQL sends, section 2.14.6). Needs the BI power.
Schema: the answer's columns (text utf8, integers int64, decimals float64); metadata `drishti.model`, `drishti.masked`. Scope:
`data:read` or `data:read:bi`. Snapshot only.

##### 2.14.3.3 `pivot`

The request is the pivot view, exactly as `POST /api/v1/pivot/query` takes it ([PIVOT.md](bi/PIVOT.md)). Schema: `ROW`, `COLUMN`,
one utf8 field per dimension, `MEASURE`, `VALUE` (float64), `MEMBERS` (int64); metadata `drishti.provenance`, `drishti.source`. Scope:
`data:read` or `data:read:<the view's source>`. Audited as `pivot.query` too. Snapshot only.

##### 2.14.3.4 `measure`

```json
{"form": "measure", "request": "measure('var.scenarios', 'DESK:RATES', date: 2026-09-30)"}
{"form": "measure", "request": {"measure": "var.parts", "scope": "DESK:RATES", "by": "position", "ccy": "USD"}}
```

| Measure | Rows | Schema |
|---|---|---|
| `var.scenarios` | one per scenario of the scope's vector | `SCENARIO` int64 (0-based), `SCENARIO_DATE` utf8, `PNL` float64 |
| `var.parts` | one per part (`by`: child, node, position, trade, desk, book) | `KEY`, `SCOPE`, `LABEL`, `KIND` utf8; `PNL` fixed-size list of one value per scenario: float32 for positions and trades (as stored), float64 for nodes |

Metadata: `drishti.measure`, `drishti.scope` (canonical), `drishti.date`, `drishti.currency`, `drishti.var`, `drishti.es`,
`drishti.intraday` (the date's intraday stamp); for parts also `drishti.pnl.precision` and `drishti.scenario.dates`. Scope: `data:read`
or `data:read:var`. A stream (`var.scenarios` only) sends the vector again after each intraday batch; the sequence is the intraday stamp.

##### 2.14.3.5 `lake`

```json
{"form": "lake", "request": {"lake": "feeds", "kind": "news.headlines", "paths": ["SEQ", "HEADLINE"], "cursor": "SEQ"}, "stream": true, "resume": 1200}
```

The table is found in the lake configuration ([DATA_LAKE_GUIDE.md section 2](DATA_LAKE_GUIDE.md#2-the-lake-file-every-key)) by lake and kind, never through a
connector's settings; it is then read as its kind (section 3.1's keys and checks). A table of the in-memory lake is answered by the node
that owns its store. `cursor` (a stream): a whole-number column that grows with each append; the sequence of a batch is its largest
cursor value. Scope: `data:read` or `data:read:<lake>`.

#### 2.14.4 Errors

| Code | Flight status | When |
|---|---|---|
| `DRS-5601` | UNAUTHENTICATED | no token, an unknown, expired or revoked token, a disabled account, a session that ended |
| `DRS-5602` | UNAUTHORIZED | no `data:read` scope, a domain scope that does not open the request, the person may not read it, an on-behalf-of from a token that is not an application's |
| `DRS-5603` | INVALID_ARGUMENT | not a request: bad JSON, an unknown form, a kind, model, measure or table that is not there, a bad date or line |
| `DRS-5604` | RESOURCE_EXHAUSTED | the rows limit of the role |
| `DRS-5605` | RESOURCE_EXHAUSTED | the bytes limit of the role |
| `DRS-5606` | TIMED_OUT | the time limit of the role; the client did not take a batch in time |
| `DRS-5607` | UNAVAILABLE | the read's Arrow memory cap, or the node's |
| `DRS-5608` | UNAVAILABLE | no node of a part answered (the Python client), or the part is not here |
| `DRS-5609` | NOT_FOUND | a resume point older than the node can send from |
| `DRS-5610` | CANCELLED | an administrator stopped the stream |
| `DRS-9506` | UNAVAILABLE | busy protection: the read did not start |

The message is `DRS-nnnn: <words>`; the status metadata carries `drishti-code`.

#### 2.14.5 Limits

Per read, from the person's roles (`drishti.engines.flight.limits`): rows, bytes, time, Arrow memory; per node: `memory.total-mb`. Defaults:
50,000,000 rows, 8 GB, 10 minutes, 512 MB per read; 4 GB per node.

#### 2.14.6 Flight SQL

Read-only SQL over the BI models (DSQL, [API_REFERENCE.md › Drishti SQL](API_REFERENCE.md#8-drishti-sql-apiv1bisql)) on the same port; how to use it:
[section 2.12](#212-flight-sql-sql-over-the-bi-models-from-jdbc-and-odbc-tools).

| Flight SQL call | Answers |
|---|---|
| `CommandStatementQuery` (GetFlightInfo, GetSchema) | the statement as a `form: model` request `{"model": <model>, "sql": <statement>}`: its schema and one endpoint per part, the ticket a `TicketStatementQuery` whose handle is that request |
| `TicketStatementQuery` (DoGet) | the rows, read as any Flight read (limits, Data out, audit `data.flight`, form `model`) |
| `ActionCreatePreparedStatement` | the handle (the request itself: stateless), the result's schema, an empty parameter schema |
| `CommandPreparedStatementQuery` (GetFlightInfo, GetSchema, DoGet) | as a statement |
| `ActionClosePreparedStatement` | nothing to close |
| `CommandGetCatalogs` | one row: the product's name |
| `CommandGetDbSchemas` | `catalog_name`, `db_schema_name`: a row per BI model the person may read (its id, `trading-risk@1`); the filter is a LIKE pattern |
| `CommandGetTables` | `catalog_name`, `db_schema_name`, `table_name`, `table_type` (`TABLE`), and with `include_schema` the `table_schema`: a row per alias of each model, its columns as the model describes them for the person (integers int64, numbers float64, the rest utf8) |
| `CommandGetTableTypes` | `TABLE` |
| `CommandGetSqlInfo` | the server's name and version, read only, SQL yes, Substrait no, transactions none, the identifier quote `"`, the schema term `model` |
| updates, ingestion, Substrait, transactions, savepoints, statement parameters | refused: `UNIMPLEMENTED`, read only |

**The model of a statement**: its first line `-- model: <model>` or `/* model: <model> */`, else the call's header `model` (a JDBC URL's
`model=`); neither: `DRS-5603`. `EXPLAIN` is refused (`DRS-5603`; the BI SQL console answers it). The statement's own refusals are the
BI SQL codes (`DRS-9150` to `DRS-9178`, [API_REFERENCE.md](API_REFERENCE.md#error-code-table)), carried in a `DRS-5603` or `DRS-5602` message.
Scope: `data:read` or `data:read:bi`, and the BI power.

#### 2.14.7 Clients

One section per client: install, connect, read, stream, errors. Working examples, every one read only, are in
[examples/clients](../../examples/clients/README.md). C#/.NET, R and Go are not written up yet; the service answers any Flight client.

##### 2.14.7.1 Python

**Install.** `pip install pyarrow` into a Python of your own, not Drishti's (16 or newer; it brings `pyarrow.flight`), and pandas or polars if you want them. Copy
`clients/python/drishti_flight.py` next to your script or notebook: one file, no other dependency.

**Connect.**

```python
from drishti_flight import Flight
f = Flight("grpc+tls://drishti-1.bank.example:18815", token="drk_…", ca="bank-ca.pem")
f.whoami()          # {"person": …, "scopes": ["data:read"], "limits": {…}}
```

`url`, `token` and `ca` may come from `DRISHTI_FLIGHT_URL`, `DRISHTI_TOKEN` and `DRISHTI_FLIGHT_CA`. An application acting for a person
passes `on_behalf_of="alice"`; a service asking for client certificates takes `cert=` and `key=`.

**Read.**

```python
trades = f.kind("trade", paths=["TRADE_ID", "PRODUCT_TYPE", "QUANTITY"], where=["PRODUCT_TYPE = BASIS_SWAP"])
df = trades.to_pandas()                         # or polars.from_arrow(trades)
pnl = f.measure("var.scenarios", "DESK:RATES", date="2026-09-30")
parts = f.measure("var.parts", "DESK:RATES", by="position")
risk = f.model("trades-basic", {"select": {"fields": ["trade.DESK"], "measures": ["MTM"]}})
cells = f.pivot({"pivot": 1, "source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": "count()"}]})
f.catalogue()                                   # what this token may read
```

Run on the test data of 2026-09-30:

```text
>>> df.head(3); len(df)
      TRADE_ID PRODUCT_TYPE    QUANTITY
0  MX-20000013   BASIS_SWAP   7000000.0
1  MX-20000014   BASIS_SWAP  64000000.0
2  MX-20000015   BASIS_SWAP  63000000.0
72
>>> pnl.to_pandas()
     SCENARIO SCENARIO_DATE         PNL
0           0    2024-09-17  11293350.0
1           1    2024-09-18  17864629.0
...
508       508    2026-09-29  -2066043.0
[509 rows x 3 columns]
>>> parts.num_rows
1358
>>> cells.num_rows, cells.schema.names
75 ['ROW', 'COLUMN', 'PRODUCT_TYPE', 'MEASURE', 'VALUE', 'MEMBERS']
>>> len(f.catalogue())
199
```

`read` asks for the plan and reads each part from its nodes in parallel, the next node of a part when one does not answer.

**Stream.**

```python
req = f.request("measure", "measure('var.scenarios', 'DESK:RATES')")
for seq, batch in f.stream(req):                # a pyarrow.RecordBatch after each intraday batch
    print(seq, batch.num_rows)
```

Keep the last `seq`; `f.stream(req, resume=seq)` continues after it.

**The command line.**

```bash
python3 clients/python/drishti_flight.py --url grpc+tls://drishti-1.bank.example:18815 --token "$DRISHTI_TOKEN" --ca bank-ca.pem \
    kind trade --paths TRADE_ID,PRODUCT_TYPE --where "PRODUCT_TYPE = IRS" > irs.csv
python3 clients/python/drishti_flight.py --out pnl.parquet measure var.scenarios DESK:RATES
```

**Examples.** Nine notebooks, each cell explained: connect and the catalogue, a kind into pandas and polars, a BI model, a pivot view over
trade legs, a desk's VaR scenario P&L with the VaR scenario and ES tail marked, a live stream with resume, a join in DuckDB, an
in-memory lake table, and a direct lake read for users who already hold lake access
([examples/clients/python](../../examples/clients/README.md#python-notebooks-python)). Excel from Python, no add-in:
[examples/clients/excel](../../examples/clients/README.md).

**Errors.** A refusal raises `FlightRefused` with `.code` (`DRS-5604` …) and the words (section 2.14.4).

##### 2.14.7.2 Java

**Install.** Maven: `org.apache.arrow:flight-core` and `org.apache.arrow:arrow-memory-netty`, version 19.0.0 or newer (the server's own).
Start the JVM (Java 17 to 21) with `--add-opens=java.base/java.nio=ALL-UNNAMED` (Arrow's memory module needs it; Arrow 19's memory
module does not start on Java 25).

**Connect.**

```java
BufferAllocator alloc = new RootAllocator();
FlightClient client = FlightClient.builder(alloc, Location.forGrpcTls("drishti-1.bank.example", 18815))
        .trustedCertificates(Files.newInputStream(Path.of("bank-ca.pem"))).build();
CallOption session = client.authenticateBasicToken("token", System.getenv("DRISHTI_TOKEN")).orElseThrow();
```

An application acting for a person adds the header at the handshake:
`client.authenticateBasicToken("token", token, new HeaderCallOption(headers))` with `x-drishti-on-behalf-of: alice` in `headers`.

**Read.**

```java
byte[] request = """
    {"form": "measure", "request": {"measure": "var.scenarios", "scope": "DESK:RATES"}}""".getBytes(StandardCharsets.UTF_8);
FlightInfo info = client.getInfo(FlightDescriptor.command(request), session);
for (FlightEndpoint e : info.getEndpoints()) {          // each part, from the first of its nodes (try the next when one is down)
    try (FlightStream s = client.getStream(e.getTicket(), session)) {
        while (s.next()) {
            VectorSchemaRoot root = s.getRoot();
            Float8Vector pnl = (Float8Vector) root.getVector("PNL");
            // …
        }
    }
}
```

Run (the elided body printing each batch's rows, its first P&L and the worst; Java 21; the test data of 2026-09-30):

```text
1 endpoint(s)
a batch of 509 rows from grpc+tls://192.168.2.77:18825; PNL[0] = 11,293,350.00; the worst = -119,535,289.00
metadata: {drishti.scope=DESK:RATES, drishti.var=7.463368E7, drishti.es=7.5377415E7, drishti.measure=var.scenarios, drishti.date=2026-09-30, drishti.currency=USD, drishti.intraday=0}
```

A part on another node needs a client for that node's location (`e.getLocations().get(0)`), authenticated the same way.

**Stream.** Add `"stream": true` to the request and read the ticket with `getStream`; each batch's sequence is
`s.getLatestMetadata().getLong(0)`. Ask again with `"resume": <sequence>` after a break.

**Example.** `ReadKindAndVarPnl.java`, one file run with the source launcher (a kind, then a desk's VaR scenario P&L with the engine's VaR
and ES, each part from the first node that answers): [examples/clients/java](../../examples/clients/README.md).

**Errors.** A refusal is a `FlightRuntimeException`; `e.getMessage()` starts with the code (`DRS-5604: …`) and
`e.status().metadata().get("drishti-code")` carries it alone.

##### 2.14.7.3 JDBC (DBeaver, DataGrip, any JDBC program)

Flight SQL (section 2.14.6): read-only SQL over the BI models, answered by the same path as the BI SQL console.

**Install.** The Apache Arrow Flight SQL JDBC driver: one jar, `org.apache.arrow:flight-sql-jdbc-driver`, version 19.0.0 or newer. No
installer, so it runs on machines where no program may be installed. On Java 17 or later start the tool's JVM with
`--add-opens=java.base/java.nio=ALL-UNNAMED` (DBeaver: `dbeaver.ini`, a line under `-vmargs`).

**Connect.**

```text
jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token=<API token with data:read>&model=trading-risk@1
```

| Property | Means |
|---|---|
| `useEncryption=true` | TLS (always on at Drishti's Flight port) |
| `token=` | a personal API token with `data:read` (or `data:read:bi`); the driver sends it on every call, the service keeps one session for it |
| `trustStore=`, `trustStorePassword=` | the bank's CA as a Java trust store, when the JVM's own does not hold it, with `useSystemTrustStore=false` (the driver reads `trustStore` only then; `disableCertificateVerification=true` on a test machine only) |
| `model=` | the BI model statements read, unless a statement names its own with a first line `-- model: <model>` (the driver sends this property as the header `model`) |

In DBeaver: Database → New connection → **Arrow Flight SQL** (or a generic JDBC connection with the jar), the URL above. The navigator
shows one catalog, a schema per BI model you may read, and a table per alias of the model with its columns.

**Read.**

```java
try (Connection c = DriverManager.getConnection("jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token="
        + System.getenv("DRISHTI_TOKEN") + "&model=trading-risk@1");
     ResultSet rs = c.createStatement().executeQuery("SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK")) {
    while (rs.next()) {
        System.out.println(rs.getString(1) + " " + rs.getDouble(2));
    }
}
```

Run against a server with a test PKI (`&trustStore=ca.jks&trustStorePassword=…&useSystemTrustStore=false` added to the URL; Java 21; the
test data of 2026-09-30):

```text
DESK-COMM 8.850170019852502E11
DESK-CREDIT 3.4835024551415E11
DESK-EQD 4.3052971792252997E11
DESK-FI 4.0315015623297003E11
DESK-FX 3.7284221311835E11
DESK-INFL 1.299456220881E11
DESK-MM 1.30992079528E11
DESK-RATES 6.0533722239374E11
DESK-SFT 2.27897073212E11
DESK-STRUCT 1.06026316978E11
```

A prepared statement works the same way (read only, no parameters).

**Example.** `JdbcFlightSql.java`, plain JDBC with the driver jar: without arguments it lists the models you may read and their tables;
with a model and a statement it runs the statement:
[examples/clients/java](../../examples/clients/README.md).

**Errors.** The driver shows the service's message, starting with its code: `DRS-5603` (no model named, a statement DSQL refuses, with
the BI SQL code in the words), `DRS-5602` (the token or the person may not read the model), `DRS-5604` (the rows limit of your role).

##### 2.14.7.4 ODBC (Excel, Power BI, Tableau)

**Install.** An Arrow Flight SQL ODBC driver (third-party). It is an **installer**, so it cannot go on machines where no program may be
installed: there, use the JDBC jar (7.3) or the Python client (7.1), or get an Excel workbook from a Flight read through Python
([examples/clients/excel](../../examples/clients/README.md)).

**Connect.** A DSN with host `drishti-1.bank.example`, port `18815`, encryption (TLS) on, the bank's CA, and an API token with `data:read`
as the token (or as the password, with any user name). Name the BI model with the driver's extra connection property `model=<model>`
when it passes such properties as headers; otherwise begin each statement with a line `-- model: <model>`.

**Read.** In Excel: Data → Get Data → From ODBC → the DSN → a schema (a BI model) → a table (one of its aliases), or Advanced options
with a statement such as `-- model: trading-risk@1` then `SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK`. In
Power BI the same through its ODBC connector. The answer is one row per desk, the same figures as the JDBC example above (the same
service answers both); the ODBC driver itself is not part of Drishti's test runs.

**Errors.** The tool shows the service's message, starting with its code (`DRS-5603` no model named or a statement refused, `DRS-5602`
not allowed, `DRS-5604` the rows limit).


## 3. The API: calling the server from scripts, notebooks and Excel

This chapter is for anyone who calls the Drishti server directly: to script a report, feed another tool, check a deployment, or
see what the console does behind the scenes. It covers what you need before the first call, tokens and clients, authentication,
the business date, errors and Arrow answers. Every endpoint, grouped by what it is for, with a `curl` command you can paste and an
abbreviated copy of a real answer, is in [API_REFERENCE.md](API_REFERENCE.md). Live streaming in depth is in
[DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#1-live-updates); users, roles and sign-in in
[ADMIN_GUIDE.md](ADMIN_GUIDE.md).

### 3.1 Before you start

| What | Value |
|---|---|
| Server base URL | `http://localhost:18480` (port set by `server.port`) |
| API prefix | `/api/v1` — every path in the [API reference](API_REFERENCE.md) is under it unless it says otherwise |
| Format | JSON in and out (`application/json`), except Sutra text (`text/yaml`), the Rachana schema (`application/schema+json`) and live streams (`text/event-stream`) |
| Errors | RFC 7807 `application/problem+json` with a stable `code` such as `DRS-1001` |
| OpenAPI document | `http://localhost:18480/api/docs` |
| Swagger UI (try calls in a browser) | `http://localhost:18480/api/docs/ui` |

You need `curl`. `jq` is optional but makes the output readable; every example below pipes through it. On
Ubuntu/Debian: `sudo apt install curl jq`.

To save typing, set a variable once in your shell:

```bash
B=http://localhost:18480/api/v1
```

All examples below use `$B`. They were captured against a development server with security off and the
banking packs installed; your ids, numbers and times will differ.

### 3.2 Your first five calls

Work through these in order. If all five answer, the API is working for you.

**1. Is the server up and what is it running?**

```bash
curl -s $B/about | jq '{product, version, java, uptimeSeconds, securityEnabled, sutras: (.sutras | length)}'
```

You should see:

```json
{
  "product": "Drishti",
  "version": "2.5.0",
  "java": "21.0.12.1 (Ubuntu)",
  "uptimeSeconds": 6913,
  "securityEnabled": false,
  "sutras": 223
}
```

If `securityEnabled` is `true`, read [Who you are](#34-who-you-are-authentication) first: every call needs a token.

**2. What is "today"?**

```bash
curl -s $B/business-date | jq '{current, selected, live, previous, calendar, zone}'
```

```json
{ "current": "2026-09-30", "selected": "2026-09-30", "live": true,
  "previous": "2026-09-29", "calendar": "USNY", "zone": "America/New_York" }
```

**3. Find something to look at.** The type-ahead takes what you would type in the console's command line.
`TRD ` (with a trailing space) lists trades:

```bash
curl -s "$B/command/suggest?q=TRD%20&limit=2" | jq -c '.[] | {complete, subtitle}'
```

```
{"complete":"TRD MX-20000001","subtitle":"Trade · Interest rate swap (fixed/float) · Meridian Reinsurance Ltd · AUD 242m"}
{"complete":"TRD MX-20000002","subtitle":"Trade · Interest rate swap (fixed/float) · Halcyon Shipping plc · EUR 110m"}
```

**4. Open it.** A view is the screen the console draws, as data:

```bash
curl -s $B/views/trade/MX-20000001 | jq -r '.strip[] | "\(.label): \(.text)"'
```

```
Notional: AUD 242,000,000
Direction: Receive fixed
Trade date: 2025-07-25
…
MTM (USD): +1,603,277
```

**5. See the document behind it.**

```bash
curl -s $B/entities/trade/MX-20000001/raw | jq '.data | {tradeId, productType, assetClass}'
```

```json
{ "tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", "assetClass": "Rates" }
```

### 3.3 Clients: scripts, notebooks and Excel

Everything you can open in the terminal you can also read from code, as yourself, with a **personal API token**.
A token reads what you may read (your roles and packs, at the time of each call) and, unless you gave it a **write scope**,
**never changes anything**: any request other than `GET` with a read-only token is refused (`403 DRS-5002 API tokens only
read`), except the `POST`s that only read (`/pivot/query`, `/pivot/decompose`, `/pivot/estimate`, `/pivot/expand`, `/pivot/compare`, `/pivot/rows`, `/pivot/export`, `/command`, the allow-list
`drishti.security.token-read-posts`, which also lets a token ask a BI model, `POST /api/v1/bi/mq`). Tools and CI that must change things get a token with the scope for
that job; see "Personal API tokens" in [CLI_REFERENCE.md](CLI_REFERENCE.md) and below.

#### Make a token

1. Open **My account → API tokens**.
2. Give it a name that says what uses it (`Risk notebook`, `Desk P&L workbook`) and, if you like, a lifetime in days
   (1–366; blank for no expiry). Tick a **scope** only if the token must change things (the page explains each, and the [scope table](API_REFERENCE.md#28-personal-api-tokens) lists what each opens; the
   list is `drishti.security.token-scopes`). A token with a write scope must expire (90 days at most).
3. Press **Create token**. You see the token once, for example `drk_7QhK2mPq9xZa_4kq…` (`drk_`, a 12-character id, `_`,
   a 43-character secret). **Copy it now**: Drishti keeps only a hash of the secret and cannot show it again.
4. The list shows each token's id, when it was created, when it expires, when it was last used, and **Revoke**.

Send it in the `Authorization` header:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "https://drishti.bank.example:18480/api/v1/entities/trade/MX-20000001/raw" | jq .data.QUANTITY
```

You should see the trade's quantity: on the demo lakes `242000000` (the MUREX interest-rate swap's notional; the trades carry no MTM,
that is in the market-risk pack's trade valuations, `TVAL`).

Keep tokens like passwords: in an environment variable or a secrets store, never in a shared file. If one leaks,
revoke it: it stops at once. A token also stops when its owner is disabled, and when it expires. Administrators see
and revoke everyone's tokens in **Admin → Tokens**; every creation and revocation is in the audit log
(`token-created`, `token-revoked`).

#### Python

`clients/python/drishti_client.py` is one file that uses Python's standard library only: copy it next to your
notebook, or put its folder on `PYTHONPATH`.

```python
from drishti_client import Drishti

d = Drishti("https://drishti.bank.example:18480", token="drk_…")   # or set DRISHTI_URL and DRISHTI_TOKEN

d.field("trade", "MX-20000001", "QUANTITY")            # 242000000.0
d.field("trade", "MX-20000001", "LEGS[0].NOTIONAL")    # a path into the document: 242000000.0
doc = d.document("trade", "MX-20000001")               # the whole document (masked fields stay masked)

rows = d.search("TRD PRODUCT_TYPE=REPO")            # a pick list: one dict per entity, with the pack's key columns (96 repos)
# [{'kind': 'trade', 'id': 'CLY-3000265', 'title': 'CLY-3000265', 'Product type': 'REPO', 'Source system': 'CALYPSO', …, 'Quantity': 180000000, …}, …]

d.search("TRD where QUANTITY > 1bn order by QUANTITY desc limit 20", as_of="2026-10-14")   # a past business date
d.diff("trade", "MX-20000022", "2026-10-07", "2026-10-16")                                    # what changed between two dates
h = d.history("trade", "1000015", days=30)          # the entity over its last 30 business days with data (rows oldest first)
d.history("equity", "EQ-CSCA", range_="3M", fields=["PRICE"])   # 30D, 2W, 1Y, YTD, MTD, QTD; or date_from=, date_to=; diff=True
d.history_rows("trade", "1000015")                  # one flat dict per day, newest first: {'date': '2026-10-19', 'QUANTITY': 72000.0, …, 'changed': ['PRICE']}
df = d.to_pandas("CPTY RATING=BBB")                 # a pandas DataFrame, if pandas is installed: 3 rows, columns kind, id, title, Rating, Name, …
```

A refusal raises `DrishtiError` with the server's code: `e.code == "DRS-5002"` (not yours to open), `"DRS-1001"` (no
source holds it), `"DRS-5010"` (the token is unknown, revoked or expired).

From the shell, the same client writes CSV:

```bash
export DRISHTI_URL=https://drishti.bank.example:18480 DRISHTI_TOKEN=drk_…
python3 clients/python/drishti_client.py search "TRD PRODUCT_TYPE=REPO" > repos.csv
python3 clients/python/drishti_client.py field trade MX-20000001 QUANTITY          # 242000000.0
python3 clients/python/drishti_client.py --as-of 2026-10-14 document trade MX-20000001
```

#### Excel

Any search or pick list is also CSV at `GET /api/v1/search/csv?q=…`: a header row (`kind`, `id`, `title`, then the
columns) and one row per entity, values unformatted. Text that a spreadsheet would run as a formula (starting with
`=`, `+`, `-` or `@`) is prefixed with `'`.

**Power Query** (Data → Get Data → From Other Sources → Blank Query → Advanced Editor):

```
let
    Url    = "https://drishti.bank.example:18480/api/v1/search/csv",
    Token  = "drk_…",
    Source = Csv.Document(Web.Contents(Url, [Query = [q = "TRD PRODUCT_TYPE=REPO"],
                                             Headers = [Authorization = "Bearer " & Token]]),
                          [Delimiter = ",", Encoding = 65001, QuoteStyle = QuoteStyle.Csv]),
    Table  = Table.PromoteHeaders(Source, [PromoteAllScalars = true])
in
    Table
```

Press **Close & Load**: the sheet fills with the 96 repos of the demo lakes and their key columns (`kind,id,title,Product type,Source
system,Trade status,Trade date,Buy sell code,Currency,Quantity,Maturity date,Portfolio,Counterparty`); **Refresh** reads them again.
For another query change `q`; for a past business date add `Headers = [Authorization = …, #"X-Drishti-As-Of" = "2026-10-14"]`.
When Excel asks how to connect, choose **Anonymous**: the token in the header is the credential. Better than writing
the token into the query: keep it in a named cell or a Power Query parameter only you can see.

#### Any other language

It is plain HTTP and JSON; the [API reference](API_REFERENCE.md) lists every endpoint. The ones scripts use most:

| Read | `GET` |
|---|---|
| a document | `/api/v1/entities/{kind}/{id}/raw` |
| a view (strip, panels, provenance) | `/api/v1/views/{kind}/{id}` |
| a pick list or search | `/api/v1/search?q=…` (JSON) or `/api/v1/search/csv?q=…` (CSV) |
| what changed between dates | `/api/v1/history/{kind}/{id}/diff?from=…&to=…` |
| a pack's kinds | `/api/v1/packs/{code}/overview` |
| the BI catalogue, an item, a dataset or report | `/api/v1/bi/catalogue`, `/api/v1/bi/items/{id}`, `/api/v1/bi/datasets/{id}`, `/api/v1/bi/reports/{id}` |

A BI question is a model query, a `POST` with the fields, measures and filters in the body (`/api/v1/bi/mq`);
it only reads, so a read-only token may call it, and the answer carries the
owner's row rules and masks ([the model query in the API reference](API_REFERENCE.md#5-models-and-the-model-query-language-apiv1bi)):

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' "$DRISHTI_URL/api/v1/bi/mq" \
  -d '{"mq":1,"model":"trades-basic","define":{"N":"count(trade)"},"select":{"fields":["trade.SOURCE_SYSTEM"],"measures":["N"]},"time":{"as-of":"2026-10-16"}}'
# "rows":[["CALYPSO",5000],["ENDUR",5000],["MUREX",5000]]
```

Add `X-Drishti-As-Of: 2026-10-14` for a past business date and `X-Drishti-Known-At: 2026-10-14T14:30:00Z` for the
data as known at an instant (stores with time travel only; others answer `DRS-1007`). For an id with a `/` (or `\`),
put `~` in its place and the id in the query: `/api/v1/entities/trade/~/raw?id=sl%2Fash-6`; the Python client does
this by itself.

### 3.4 Who you are: authentication

Every request under `/api/v1/` passes through the server's token filter (`TokenFilter`). What it does
depends on one setting, `drishti.security.enabled` (environment variable `DRISHTI_SECURITY_ENABLED`,
default `false`).

**Paths must be written plainly.** The token filter (and the guard on `/actuator` and `/api/docs`) decides on the
path the server serves, after decoding. Under `/api` and `/actuator` a request line that spells a path in any other
form is refused, security on or off, with `400 DRS-5001` problem+json before any other check: a path parameter
(`/api/v1;x/packs`), a percent-encoded letter, digit, `- . _ ~`, `/` or `\` (`/api/%761/packs`), a malformed escape,
or a dot or empty segment (`/api/./v1/packs`, `/api/v1/../v1/packs`, `/api//v1/packs`). Encode only what is data in
a name or an id (a space as `%20`, `;` as `%3B`, `%` as `%25`, `(` `)`, non-ASCII letters): those are accepted, as
`urllib.parse.quote(name, safe='')` and `encodeURIComponent` write them. The query string is not affected.

```bash
curl -s $B';x/packs'
# {"title":"bad request","status":400,"code":"DRS-5001","detail":"path parameters (;) are not accepted"}
```

#### Security off (local development)

No token is needed. The caller's name is taken from the optional `X-Drishti-User` header (default
`anonymous`), and that caller has every role. The name only matters for per-user data: settings,
workspaces, monitors, alerts, recent entities in the type-ahead, and chosen packs.

```bash
curl -s -H "X-Drishti-User: ash" $B/me/settings
```

Pack choices still apply with security off: a kind whose pack is not active for the named user is refused
with `403 DRS-5002`.

#### Security on (production)

Every `/api/v1/` call must carry `Authorization: Bearer <token>`. The token is a compact JWT signed with
**HS256** using the shared secret `drishti.security.secret` (environment variable `DRISHTI_TOKEN_SECRET`, at
least 32 bytes; the server refuses to start with a shorter one). The server checks:

| Check | Refusal (`detail`) |
|---|---|
| header present and starts with `Bearer ` | `missing bearer token` |
| three dot-separated parts | `malformed token` |
| header `alg` is exactly `HS256` (`none` and every other algorithm are rejected) | `unsupported token algorithm` |
| signature matches (constant-time compare) | `bad token signature` |
| `exp` is present and not past (plus `drishti.security.clock-skew`, default 30 s) | `token expired` |
| `sub` is not empty | `token has no subject` |
| anything unreadable | `unreadable token` |

The claims the server reads are `sub` (the user name), `roles` (a list of role names) and `exp` (expiry,
seconds since the epoch). A refusal is a `401` answered by the filter itself:

```json
{"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 missing bearer token"}
```

#### How the console does it

The console never forwards the user's password or a long-lived credential. In `drishti-console/core/auth.py`:

1. The user signs in on the console. The console verifies the password by calling `POST /api/v1/auth/login`
   with its own **service** identity (user `console`, role `service`) — the only identity allowed to call
   that endpoint (or `POST /auth/oidc` for single sign-on).
2. The console opens a **session** on the server (`POST /api/v1/auth/sessions`, service identity) and keeps its id,
   with the user's name, in a signed cookie (`drishti_session`, HMAC-SHA256 with `auth.session_secret`). On each
   request it asks the server whether the session still stands and who the user is now
   (`GET /api/v1/auth/sessions/{id}`: enabled, roles, whether a password change is due), and reuses the answer for
   `auth.recheck_seconds` (default 10). So disabling or demoting a user takes effect within that time, and at once on
   the console the administrator used. Signing out (`POST /logout`) ends the session on the server
   (`DELETE /api/v1/auth/sessions/{id}`), so a copy of the cookie stops working. Disabling, deleting or resetting the
   password of a user ends all their sessions; enabling them again does not bring an old one back.
3. For **every** backend call it mints a fresh token with `auth.token_secret` (the same value as the server's
   `drishti.security.secret`), lifetime `auth.token_ttl_seconds` (default 300), carrying the user's **current** roles,
   and sends two headers:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJqZG9lIiwicm9sZXMiOlsidHJhZGVyIl0sImV4cCI6MTc5MDAwMDAwMH0.…
X-Drishti-User: jdoe
```

   (With security on, the server ignores `X-Drishti-User`; the token's `sub` decides.) It also sends
   `X-Drishti-As-Of` and, when set, `X-Drishti-Known-At` — see the next section.

#### Minting a token for a script

When security is on, a script can mint its own token exactly as the console does. This needs the shared
secret, so run it only where that secret is legitimately available:

```bash
TOKEN=$(DRISHTI_TOKEN_SECRET="$DRISHTI_TOKEN_SECRET" python3 - <<'PY'
import base64, hashlib, hmac, json, os, time
b = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
h = b(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
c = b(json.dumps({"sub": "report-bot", "roles": ["viewer"], "exp": int(time.time()) + 300}, separators=(",", ":")).encode())
s = b(hmac.new(os.environ["DRISHTI_TOKEN_SECRET"].encode(), f"{h}.{c}".encode(), hashlib.sha256).digest())
print(f"{h}.{c}.{s}")
PY
)
curl -s -H "Authorization: Bearer $TOKEN" $B/about | jq .version
```

You should see the version string. The roles you put in the token decide what the
script may open (roles are defined under `drishti.security.roles`; see
[ADMIN_GUIDE.md](ADMIN_GUIDE.md)). Keep the lifetime short.

#### What roles allow

| Ability | Granted by |
|---|---|
| open entities of a kind | a role whose `kinds` lists the kind or `*`, **and** the kind's pack active for the user |
| every field unmasked (otherwise the fields in `drishti.security.redact` read `•••` on every endpoint: see below) | a role with `raw: true` |
| save Sutras from Studio | a role with `author: true` (and `drishti.rachana.studio-save: true`) |
| approve or reject Sutra proposals | a role with `approve: true`, or an admin |
| user administration, audit log, health, caches | a role with `admin: true` |
| `POST /auth/login`, `POST /auth/oidc`, `/auth/sessions` | the `service` role (the console) |
| everything | the role `*` (what every caller gets with security off) |

#### Field masks

For a caller without `raw`, every field named in `drishti.security.redact` (at any depth, and everything under it)
reads `•••` in every answer that shows or is computed from its value (masking is by field; see *Copies of a masked value* below for text in other fields): `/entities/…/raw`, `/search` (rows, CSV,
compare), `/history`, `/search/columns`, the search pivot endpoints, `/views/{kind}/{id}` (strip, title, every panel,
table totals, keys and links, values a Sutra computes from the field), `/views/{kind}/{id}/explain`, the view and
monitor streams, `/me/monitors/{name}`, `/studio/preview`, `/impact/{kind}/{id}`, `/command/suggest` and `/phrase`;
alert rules are evaluated on the document as their owner may see it. The server masks the documents in one place
(`Entitlements.redactor`) before anything reads them, so nothing about a masked field can be probed: a condition on
it is never true, ordering by it does not order, the type-ahead does not match it, Impact does not list entities
tied to the analysed one only through it, and a total over it reads `•••`.
A signed token whose user was deleted or disabled is refused with `401` (`drishti.security.registered-users-only`). A personal API token only reads, so `POST /views/{kind}/{id}/ask` answers `403` for it.

Shares, their notes and inbox rows are masked answers too: a note's copies of a masked value read `•••` for a reader without `raw`, and a notice carries no data value, only who, what kind and which id.

A `redact` entry with dots names a field by the end of its path (arrays are not a step): `lifecycle.timeline.description` masks
every `description` under `lifecycle.timeline`, and no other `description`. Names match in any case and convention
(`counterpartyId` masks `COUNTERPARTY_ID`, `trader_id` masks `TRADER_ID`), so a mask written in another naming convention
keeps masking; an entry that names no field of the loaded packs' samples is logged at start-up (`DRS-6011`).

**Copies of a masked value (`drishti.security.mask-copies`, default `false`).** Masking works on fields. With the setting on, after
masking, the server also replaces every exact occurrence of a masked field's original value inside the *other* text of the same
document with `•••`: with `trader` masked, "Captured by J. Smith" reads "Captured by •••". It runs in the same place as the masks
(`Entitlements.redactor`), so it holds on every path above, including Calc columns and the builder's previews, checks and evidence.
Best effort, bounded and exact: text values of at least `mask-copies-min-length` characters (3), numbers of four digits or more
rendered as text; case, spacing and spelling variants ("j. smith", "SMITH, J.") are **not** matched; a document with more than
`mask-copies-max-nodes` nodes (50 000) is not scanned (its masked fields are still masked) and at most `mask-copies-max-values` (64)
distinct values are scrubbed per document. Mask the text fields that carry sensitive values, and fix the source so they do not.

### 3.5 Which day: the business date

Every **read** (views, raw, search, history, impact, suggestions, monitors and streams) answers for a
business date. You choose it with a header or a query parameter:

| Header | Query parameter | Value | Meaning |
|---|---|---|---|
| `X-Drishti-As-Of` | `asOf` | `live` or empty (default) | today's business date, streaming |
| `X-Drishti-As-Of` | `asOf` | `2026-09-29` | that date, as a static snapshot |
| `X-Drishti-Known-At` | `knownAt` | `2026-09-29T21:00:00Z` (ISO instant) | the data as it was known at that instant (Delta Lake and Iceberg time travel) |

The header wins when both are given. (On the console's own pages the same two values are the query parameters `asOf` and
`knownAt` of `/v/{kind}/{id}`, read for that request only and never saved: that is how a copied *Share* link pins a
date; `gen` is the generation seen, shown as evidence in the page banner and never used to read data.) Rules:

- A weekend or holiday rolls back to the previous business day of the calendar.
- `knownAt` is answered only by stores that keep versions (Delta Lake, Iceberg). A dated store without them that may
  hold the date answers `400 DRS-1007` naming the connector, never today's data; a search names it in `failed` and is
  `partial`. Undated sources have one version and answer as usual.
- A picked date is a snapshot: views say `provenance.live = false`, and a stream sends one `view` event and closes.
- A future date, or one before the history window (`earliest`), is `400 DRS-4003`.
- `provenance.businessDate` in a view is the date the source actually read; it is `null` for sources that are not dated.

Try it — Sunday 27 September rolls back to Friday 25 September:

```bash
curl -s -H "X-Drishti-As-Of: 2026-09-27" $B/business-date | jq -c '{current, selected, live}'
```

```json
{"current":"2026-09-30","selected":"2026-09-25","live":false}
```

With a "known at" instant, as query parameters this time:

```bash
curl -s "$B/business-date?asOf=2026-09-29&knownAt=2026-09-29T21:00:00Z" | jq -c '{selected, live, knownAt}'
```

```json
{"selected":"2026-09-29","live":false,"knownAt":"2026-09-29T21:00:00Z"}
```

A date in the future:

```bash
curl -s -H "X-Drishti-As-Of: 2030-01-01" $B/business-date
```

```json
{"type":"about:blank","title":"bad business date","status":400,
 "detail":"DRS-4003 business date 2030-01-01 is in the future","instance":"/api/v1/business-date","code":"DRS-4003"}
```

### 3.6 When something goes wrong: errors

Every error is RFC 7807 `application/problem+json`. The fields:

| Field | Meaning |
|---|---|
| `status` | the HTTP status, repeated in the body |
| `title` | the error's name in words (`entity not found`, `bad search`, …) |
| `code` | the stable Drishti code, `DRS-nnnn` — match on this in scripts, never on `detail` |
| `detail` | a human explanation; it starts with the code |
| `instance` | the path that was called |
| `problems` | only for Sutra errors: the list of problems, each `{code, message, location: {file, line, column}}` |

Example — an id no source knows:

```bash
curl -s -i $B/views/trade/IRS-99999 | sed -n '1p;/^{/p'
```

```
HTTP/1.1 404
{"type":"about:blank","title":"entity not found","status":404,"detail":"DRS-1001 no source holds trade/IRS-99999","instance":"/api/v1/views/trade/IRS-99999","code":"DRS-1001"}
```

Three catch-alls are worth knowing: a bad argument anywhere (`IllegalArgumentException`) becomes
`400 DRS-5001`; a body that is not JSON, is empty, or is JSON of the wrong shape (a list for an object) is
`400 DRS-5001` (`the request body is not valid JSON`, …); and a request with no caller attached becomes `401 DRS-5010`. Server errors (`5xx`) are also
logged on the server with the stack trace. The full list is in the [error code table](API_REFERENCE.md#error-code-table).

**When the server is busy.** New heavy work (a sign-in, opening a VaR or exposure date that is not open yet, an Export data download, a BI
run, a bulk load) may be asked to wait: `503` with `Retry-After` and a code `DRS-9501` to `DRS-9505`, `DRS-9506` (an Arrow Flight read) or `DRS-9507` (a large pivot). The body adds `busy: true`, `title`
and `because` (the same words the console and `drishti.py` show) and `retryAfterSeconds`. A script waits that many seconds and asks
again, a few times at most; reads of what is already open are never asked to wait. Busy protection is described in
[OPERATIONS_GUIDE.md section 12.5](OPERATIONS_GUIDE.md#147-busy-protection-refuse-politely-never-fall-over).

### 3.7 Answers as Arrow: the Accept header

Two answers come as an **Apache Arrow IPC stream** when the caller asks with `Accept: application/vnd.apache.arrow.stream`:
`POST /api/v1/bi/reports/{id}/query` (a visual's answer) and `GET /api/v1/risk/var/scope/contributions` (a contribution table). The stream is
the JSON answer, cell for cell: its table (`rows`) is the record batch, and everything else in the answer travels in the schema's
custom metadata as JSON (`drishti.answer`), with `drishti.table` saying where the table goes back (`{"shape": "rows", "at": "rows"}` for
a list of lists named by `columns`; `{"shape": "records", "at": "rows", "absent": [...]}` for a list of objects, `absent` naming the keys
some objects leave out). Text that repeats (desks, books, currencies, dates) is a dictionary column; integers are 64-bit, numbers
doubles, flags booleans. A table Arrow is not written with here (an object or a list in a cell, a column mixing text and numbers) comes
as JSON with `200`, as does every call without that `Accept`: read the `Content-Type`. The console's `/api/var/...` and `/bi/api/...`
pass the header on and answer the same way. Why these two and not others, with the measurements: [DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 2.6](DATA_GUIDE.md#26-arrow-to-the-browser-where-it-pays).

```bash
curl -s -H "Authorization: Bearer $T" -H "Accept: application/vnd.apache.arrow.stream" \
  "$B/var/scope/contributions?scope=NODE:MGMT~DESK-RATES&by=trade" -o contrib.arrow
python - <<'EOF'
import json, pyarrow as pa
t = pa.ipc.open_stream(open("contrib.arrow", "rb").read()).read_all()
answer = json.loads(t.schema.metadata[b"drishti.answer"])      # var, es, sumComponent, ... (all but the rows)
print(answer["var"], t.num_rows, t.column_names[:6])
EOF
```

`drishti-console/core/arrow_answer.py` (`decode`) rebuilds the whole JSON answer from such a stream; in a browser page,
`Drishti.table.fromArrow` (`/static/js/drishti_table.js`) does.

### 3.8 Scripting recipes

**Export a strip for a list of trades as CSV.**

```bash
for id in MX-20000001 MX-20000002 MX-20000003; do
  curl -s $B/views/trade/$id | jq -r --arg id "$id" '[$id, (.strip[] | select(.label=="MTM (USD)") | .text)] | @csv'
done
```

```
"MX-20000001","+1,603,277"
"MX-20000002","…"
"MX-20000003","…"
```

For numbers rather than formatted text, read the raw document instead:
`curl -s $B/entities/trade/MX-20000001/raw | jq .data.mtm`.

**The ten largest exposures yesterday.**

```bash
curl -s -G -H "X-Drishti-As-Of: 2026-09-29" $B/search \
  --data-urlencode "q=TRD where mtm > 0 order by mtm desc limit 10" | jq -r '.rows[] | "\(.ref.id)\t\(.values["$.mtm"])"'
```

**Fail a deployment check when anything is unhealthy.**

```bash
status=$(curl -s $B/admin/health | jq -r .status)
[ "$status" = "OK" ] || { echo "Drishti is $status"; exit 1; }
```

**Handle errors by code, not by message.**

```bash
resp=$(curl -s -w '\n%{http_code}' $B/views/trade/MX-29999999)
code=$(echo "$resp" | tail -1); body=$(echo "$resp" | head -n -1)
if [ "$code" != 200 ]; then
  case $(echo "$body" | jq -r .code) in
    DRS-1001) echo "no such trade" ;;
    DRS-1004) echo "source slow; retry later" ;;
    *)        echo "failed: $(echo "$body" | jq -r .detail)" ;;
  esac
fi
```

**Watch one value change live.**

```bash
curl -s -N $B/views/trade/MX-20000001/stream | grep --line-buffered '^data:{"seq"' \
  | sed -u 's/^data://' | jq -r --unbuffered '.patches[] | select(.op=="strip") | .cell | "\(.label) \(.text)"'
```

You should see a line such as `MTM (USD) +1,595,251` each time the value ticks.
