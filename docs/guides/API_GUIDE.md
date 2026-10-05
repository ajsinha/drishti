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
# API guide

This guide is for anyone who wants to call the Drishti server directly: to script a report, feed another
tool, check a deployment, or simply understand what the console does behind the scenes. Every endpoint the
server has is listed here, grouped by what it is for, with a `curl` command you can paste and an abbreviated
copy of a real answer.

Live streaming is covered briefly here and in depth in [LIVE.md](../architecture/LIVE.md). Users, roles and sign-in are
covered in depth in [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

## Contents

1. [Before you start](#before-you-start)
2. [Your first five calls](#your-first-five-calls)
3. [Who you are: authentication](#who-you-are-authentication)
4. [Which day: the business date](#which-day-the-business-date)
5. [When something goes wrong: errors](#when-something-goes-wrong-errors)
6. [Endpoint reference](#endpoint-reference)
   - [Command line and type-ahead](#command-line-and-type-ahead)
   - [Views](#views)
   - [Raw documents, history and impact](#raw-documents-history-and-impact)
   - [Structured search](#structured-search)
   - [The Pivot tab: panel rows, search pivots, saved pivots](#the-pivot-tab-panel-rows-search-pivots-saved-pivots)
   - [Live streams](#live-streams)
   - [Personal: settings, packs, workspaces, monitors, alerts](#personal-settings-packs-workspaces-monitors-alerts)
   - [Catalogue: about, packs, sources, Sutras](#catalogue-about-packs-sources-sutras)
   - [Sutra Studio and governance](#sutra-studio-and-governance)
   - [Sign-in and user administration](#sign-in-and-user-administration)
   - [Administration: health and caches](#administration-health-and-caches)
   - [Outside /api/v1: OpenAPI and actuator](#outside-apiv1-openapi-and-actuator)
7. [The ViewModel in detail](#the-viewmodel-in-detail)
8. [Error code table](#error-code-table)
9. [Scripting recipes](#scripting-recipes)

## Before you start

| What | Value |
|---|---|
| Server base URL | `http://localhost:18480` (port set by `server.port`) |
| API prefix | `/api/v1` — every path in this guide is under it unless it says otherwise |
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

## Your first five calls

Work through these in order. If all five answer, the API is working for you.

**1. Is the server up and what is it running?**

```bash
curl -s $B/about | jq '{product, version, java, uptimeSeconds, securityEnabled, sutras: (.sutras | length)}'
```

You should see:

```json
{
  "product": "Drishti",
  "version": "1.16.0",
  "java": "25.0.4.1 (Ubuntu)",
  "uptimeSeconds": 6913,
  "securityEnabled": false,
  "sutras": 223
}
```

If `securityEnabled` is `true`, read [Who you are](#who-you-are-authentication) first: every call needs a token.

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

## Who you are: authentication

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

### Security off (local development)

No token is needed. The caller's name is taken from the optional `X-Drishti-User` header (default
`anonymous`), and that caller has every role. The name only matters for per-user data: settings,
workspaces, monitors, alerts, recent entities in the type-ahead, and chosen packs.

```bash
curl -s -H "X-Drishti-User: ash" $B/me/settings
```

Pack choices still apply with security off: a kind whose pack is not active for the named user is refused
with `403 DRS-5002`.

### Security on (production)

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

### How the console does it

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

### Minting a token for a script

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

You should see the version string, for example `"1.16.0"`. The roles you put in the token decide what the
script may open (roles are defined under `drishti.security.roles`; see
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md)). Keep the lifetime short.

### What roles allow

| Ability | Granted by |
|---|---|
| open entities of a kind | a role whose `kinds` lists the kind or `*`, **and** the kind's pack active for the user |
| every field unmasked (otherwise the fields in `drishti.security.redact` read `•••` on every endpoint: see below) | a role with `raw: true` |
| save Sutras from Studio | a role with `author: true` (and `drishti.rachana.studio-save: true`) |
| approve or reject Sutra proposals | a role with `approve: true`, or an admin |
| user administration, audit log, health, caches | a role with `admin: true` |
| `POST /auth/login`, `POST /auth/oidc`, `/auth/sessions` | the `service` role (the console) |
| everything | the role `*` (what every caller gets with security off) |

### Field masks

For a caller without `raw`, every field named in `drishti.security.redact` (at any depth, and everything under it)
reads `•••` in every answer that shows or is computed from its value (masking is by field; see *Copies of a masked value* below for text in other fields): `/entities/…/raw`, `/search` (rows, CSV,
compare), `/history`, `/search/columns`, the search pivot endpoints, `/views/{kind}/{id}` (strip, title, every panel,
table totals, keys and links, values a Sutra computes from the field), `/views/…/panels/…/records`, `/views/{kind}/{id}/explain`, the view and
monitor streams, `/me/monitors/{name}`, `/studio/preview`, `/impact/{kind}/{id}`, `/command/suggest` and `/phrase`;
alert rules are evaluated on the document as their owner may see it. The server masks the documents in one place
(`Entitlements.redactor`) before anything reads them, so nothing about a masked field can be probed: a condition on
it is never true, ordering by it does not order, the type-ahead does not match it, Impact does not list entities
tied to the analysed one only through it, and a total over it reads `•••`.
A signed token whose user was deleted or disabled is refused with `401` (`drishti.security.registered-users-only`). A personal API token only reads, so `POST /views/{kind}/{id}/ask` answers `403` for it.

Shares, their notes and inbox rows are masked answers too: a note's copies of a masked value read `•••` for a reader without `raw`, and a notice carries no data value, only who, what kind and which id.

A `redact` entry with dots names a field by the end of its path (arrays are not a step): `lifecycle.timeline.description` masks
every `description` under `lifecycle.timeline`, and no other `description`. The shipped setting masks it, because the trading
data's timeline spells the trader out ("Captured in Murex by TRDR-ASHAH").

**Copies of a masked value (`drishti.security.mask-copies`, default `false`).** Masking works on fields. With the setting on, after
masking, the server also replaces every exact occurrence of a masked field's original value inside the *other* text of the same
document with `•••`: with `trader` masked, "Captured by J. Smith" reads "Captured by •••". It runs in the same place as the masks
(`Entitlements.redactor`), so it holds on every path above, including Calc columns and the builder's previews, checks and evidence.
Best effort, bounded and exact: text values of at least `mask-copies-min-length` characters (3), numbers of four digits or more
rendered as text; case, spacing and spelling variants ("j. smith", "SMITH, J.") are **not** matched; a document with more than
`mask-copies-max-nodes` nodes (50 000) is not scanned (its masked fields are still masked) and at most `mask-copies-max-values` (64)
distinct values are scrubbed per document. Mask the text fields that carry sensitive values, and fix the source so they do not.

## Which day: the business date

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

## When something goes wrong: errors

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
logged on the server with the stack trace. The full list is in the [error code table](#error-code-table).

## Endpoint reference

Conventions in the tables: `{kind}` is an entity kind such as `trade` or `counterparty`; `{id}` is its id;
"as-of" means the endpoint follows the business date described above; "admin" means the caller needs a role
with `admin: true` (otherwise `403 DRS-5002`).

**An id a path cannot carry.** Every endpoint that names an entity as `{kind}/{id}` (views and their panel
records and streams, raw documents, history, impact, notes, Studio's inferred Sutra) also takes `~` in place of the id
and the id as the `id` query parameter. Use it for an id with a `/` or `\` (the server refuses them percent-encoded in a
path, as `%2F`, and so do servlet containers), or an id that is `.` or `..`; any id works this way:

```bash
curl -s -G "$B/entities/trade/~/raw" --data-urlencode "id=sl/ash-6" | jq -c .ref     # {"kind":"trade","id":"sl/ash-6"}
curl -s -G "$B/views/trade/~" --data-urlencode "id=sl/ash-6" | jq -c .ref
```

`~` without an `id` parameter is the id `~` itself. The console and the Python client switch to this form by
themselves when an id needs it.

### Command line and type-ahead

| Method | Path | Notes |
|---|---|---|
| `POST` | `/command` | body `{"text": "TRD MX-20000001 <GO>"}` → `{"ref": {"kind", "id"}, "mnemonic"}`; `400 DRS-4001` if the text cannot be read; `403` if the caller may not open the kind |
| `GET` | `/command/suggest?q=&limit=` | as-of. Up to `limit` suggestions (default `drishti.commands.suggest-limit`, 25; at most 50), filtered to kinds the caller may open. For a caller with [field masks](#field-masks), each entity's `title` and `subtitle` are its view title as the caller sees it (`Rates · Interest rate swap (fixed/float) · •••`), and an entity is offered only when the text typed is in its id or in that subtitle |

The command is what the console's command line sends when you press Enter. The mnemonic (`TRD`) is mapped to
a kind (`trade`); the id is matched against the configured id patterns. `<GO>` is optional.

```bash
curl -s -X POST $B/command -H 'Content-Type: application/json' -d '{"text": "TRD MX-20000001 <GO>"}'
```

```json
{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD"}
```

Type-ahead: each suggestion is either a mnemonic (`id` is null) or an entity. `complete` is the text to put
in the command line when the user picks it.

```bash
curl -s "$B/command/suggest?q=trd&limit=3" | jq -c '.[]'
```

```
{"type":"mnemonic","mnemonic":"TRD","kind":"trade","id":null,"title":"TRD","subtitle":"Trade","complete":"TRD "}
{"type":"mnemonic","mnemonic":"TRDR","kind":"trader","id":null,"title":"TRDR","subtitle":"Trader","complete":"TRDR "}
{"type":"entity","mnemonic":"TRDR","kind":"trader","id":"TRDR-ASHAH","title":"TRDR-ASHAH","subtitle":"Trader · A. Shah","complete":"TRDR TRDR-ASHAH"}
```

Entities the caller opened recently (per `X-Drishti-User` or token subject) are offered first.

### Views

| Method | Path | Notes |
|---|---|---|
| `GET` | `/views/{kind}/{id}` | as-of. The `ViewModel`, with the caller's [field masks](#field-masks) (`"text":"•••"`); `404 DRS-1001` (no such entity), `404 DRS-1002` (no source serves the kind), `502 DRS-1003` (source failed), `504 DRS-1004` (source timed out); `403 DRS-5002` if the caller may not open the kind |
| `GET` | `/views/{kind}/{id}/panels/{panel}/records` | as-of. Every row of a table or ladder whose Sutra says `pivot:`, as raw values of its pivot's fields (masked fields `"•••"`), for the [Pivot tab](#the-pivot-tab-panel-rows-search-pivots-saved-pivots); `404 DRS-1001` when the view has no such panel or the panel offers no pivot |
| `POST` | `/views/{kind}/{id}/ask` | Ask about this page, optional and off by default. Body `{question, locale?}`; answer `{answer, sources[]}` (plain text). `404 DRS-4007` when Ask is off globally or for the page's pack, `502`/`504 DRS-4008` when the model endpoint fails or is slow, `429 DRS-4009` over the per-user rate, `400` for an empty or too long question, `403 DRS-5002` as the view. See [Ask about this page](#ask-about-this-page) |
| `GET` | `/views/{kind}/{id}/explain` | as-of. About this page, layers 3 and 4 ([design](../architecture/CONTEXT_HELP.md)): where the data came from and why the page looks as it does, for exactly what the caller sees. Query `panel` (narrows to a panel of the view, else `404 DRS-4006`) and `generation` (the generation your page shows; a newer one on the server adds `"newer":true`). Same rules as the view: `403 DRS-5002`, `404 DRS-1001`, `400 DRS-4003`. See [Explaining a view](#explaining-a-view) |

Opening a view also records it in the caller's recent list. A view is formatted for display: every value
comes as text with a tone, so all clients show `−1,403,091` the same way.

```bash
curl -s $B/views/trade/MX-20000001 | jq -c '{ref, mnemonic, title, strip: .strip[0:2], provenance, timings}'
```

```json
{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD",
 "title":{"pill":"Rates · Interest rate swap (fixed/float)","id":"MX-20000001",
          "with":{"text":"Meridian Reinsurance Ltd","link":{"kind":"counterparty","id":"CP-MERIDIAN-RE","mnemonic":"CPTY"}}},
 "strip":[{"label":"Notional","text":"AUD 242,000,000","path":"$.currency"},
          {"label":"Direction","text":"Receive fixed","path":"$.direction"}],
 "provenance":{"layout":"Sutra irs-fixfloat v1 + inference","fingerprint":"b7df…1372","source":"murex-rates",
               "generation":1674,"fetchedAt":"2026-10-01T00:58:57.229603068Z","live":true,"businessDate":null,
               "sutra":"irs-fixfloat"},
 "timings":{"fetch":0.15,"layout":0.11,"links":0.15,"bind":0.22,"total":0.64}}
```

List the panels:

```bash
curl -s $B/views/trade/MX-20000001 | jq -c '.panels[] | {id, kind, title, key, area}'
```

```
{"id":"terms","kind":"kv","title":"Terms","key":"F2","area":"main"}
{"id":"legs","kind":"tabs","title":"Legs","key":null,"area":"main"}
{"id":"schedule","kind":"ladder","title":"Cashflows","key":"F3","area":"main"}
{"id":"built","kind":"provenance","title":"How this view was built","key":null,"area":"main"}
{"id":"marketData","kind":"line","title":"Interest rate curve: Zero rates (%)","key":"F4","area":"right"}
{"id":"sensitivities","kind":"hbar","title":"DV01 by bucket (USD)","key":null,"area":"right"}
{"id":"pnl","kind":"line","title":"Daily P&L, last 20 days (USD)","key":null,"area":"right"}
{"id":"refs","kind":"links","title":"Linked entities","key":null,"area":"right"}
```

The full shape is in [The ViewModel in detail](#the-viewmodel-in-detail).

### Explaining a view

`GET /views/{kind}/{id}/explain` answers *can I trust this page, and why does it look like this* (the data behind the
[About this page](../architecture/CONTEXT_HELP.md) drawer: [how a user reads it](USER_GUIDE.md#about-this-page), [how it works inside](../architecture/HOW_IT_FITS.md#39-about-this-page-end-to-end)). It rebuilds the view for the caller, with the same field masks, the
same right to open kinds and the same restricted links, and derives the answer from that, so it tells nothing the view does not:
a masked field's value is in no part of it, and a panel the caller may not open is named only by its title and the kind of
entity it names. Every block is omitted when empty; answers are cached per user, page and generation for
`drishti.explain.cache-ttl` (60 s; `/admin/caches` purges them). Nothing is computed while a view is built.

```bash
curl -s $B/views/trade/IRS-48213/explain | jq -c '{data, layout: (.layout | {label, sutra, candidates, noData, masked, noAccess})}'
```

| Block | Fields |
|---|---|
| `ref`, `mnemonic`, `locale`, `generation`, `newer` | the page; `newer` is present (true) only when the server holds a newer generation than `?generation=` |
| `about` | the pack's words, rendered over the document the caller may see (a field masked for them reads `•••` in `text`): `pack` (`name`, `title`), `kindTitle`, `text`, `sutraDescription` (the Sutra's `description` as written, never evaluated) and `panels` (`id`, `title`, `description`). Authored in the pack's `config/about.yaml` ([pack guide](PACK_DEVELOPER_GUIDE.md#about-text-and-glossary)); absent when neither the pack nor the Sutra wrote anything |
| `data` | `source`, `generation`, `fetchedAt`, `businessDate`, `current`, `live`, `updatedAt`, `staleAfter`, `stale`, `health` (`up`, `degraded` or `down`, as the admin health page reduces it) and `linked` (`fetched`, `pending`, `denied`, `budgetMs`) |
| `layout.sutra` | the Sutra chosen: `name`, `version`, `priority`, `where` (its source text), `description` (the author's plain text) |
| `layout.candidates` | the other Sutras of the kind in priority order, each with its `where` and `result`: `true`, `false`, `error`, or `masked` (the answer depends on a field the caller may not see, so it is not told) |
| `layout.label`, `fingerprint`, `inferred`, `inferredPanels` | how the layout was built, and the panels inference added |
| `layout.noData`, `errors` | panels with nothing to show (`why`: `missing`, `null`, `empty list`, `masked`, `no values`, `no data`; and the `path` looked at) and panels that failed |
| `layout.masked` | fields shown as `•••` for the caller: `key` (document path), `label`, `panels` |
| `layout.noAccess` | panels the caller may not open: `title` and `kind` only |
| `next` | `keys` (the view's function keys, links restricted) and `panelKinds` (kinds of the panels the caller can see) |
| `timings` | `view` and `explain`, milliseconds |

The explain answer carries `"ask": {"enabled": true|false}`: whether the box may be drawn for the page's pack.

#### Ask about this page

`POST /views/{kind}/{id}/ask` with `{"question": "…"}` answers `{"answer": "…", "sources": ["this page's context", "the glossary (5 entries)", "the pack guide"]}`.
It is off unless an administrator sets `drishti.explain.ask.enabled` and lists the page's pack. The server builds the prompt from the
caller's own explain answer (so field masks are already applied: a masked value is never sent), the glossary and the pack's guide
text, and nothing else; labels only unless `values: shown`. The model gets no tools. Limits are per user (`DRS-4009`). Questions are
recorded in the access log (action `ask`); answers are not stored. Settings: [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiexplainask--ask-about-this-page-optional-off-by-default).

Planned, not yet in the server (the design, [CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md#api)): a `glossary` block (the pack's entry for each field the page shows: term, meaning, unit, sign) and, for authors, `POST /studio/explain`, `GET /builder/designs/{id}/explain` and `POST /builder/about/preview` for the workbench. The optional *Ask about this page* endpoint is described [below](#ask-about-this-page). The pack's own text problems (`DRS-2040` to `DRS-2044`) are load problems, listed by `GET /sutras/problems`; they never fail an explain call.

### Raw documents, history and impact

| Method | Path | Notes |
|---|---|---|
| `GET` | `/entities/{kind}/{id}/raw` | as-of. `{ref, provenance, data}`: the document as the source produced it (F9 in the console). Fields listed in `drishti.security.redact` read `•••` unless the caller has `raw` |
| `GET` | `/history/{kind}/{id}/diff?from=&to=&fromKnownAt=&toKnownAt=` | what changed between two points. `to` defaults to the request's as-of; `from` to the business day before `to`. At most 2,000 changes (`truncated: true` beyond) |
| `GET` | `/impact/{kind}/{id}` | as-of. F8: what depends on the entity, grouped by level and kind. With [field masks](#field-masks): entities tied in only through a masked field are left out; a masked measure (`measure`) and its group's `total` read `•••` |

Raw:

```bash
curl -s $B/entities/trade/MX-20000001/raw | jq -c '{ref, provenance, data: (.data | {tradeId, productType})}'
```

```json
{"ref":{"kind":"trade","id":"MX-20000001"},
 "provenance":{"source":"murex-rates","generation":1674,"fetchedAt":"2026-10-01T00:59:06.608972976Z","live":true,"businessDate":null},
 "data":{"tradeId":"MX-20000001","productType":"IRS_FIXFLOAT"}}
```

History — what changed in the trade since yesterday:

```bash
curl -s $B/history/trade/MX-20000001/diff | jq -c '{from: .from.businessDate, to: .to.businessDate, added, removed, changed, first: .changes[0:2]}'
```

```json
{"from":"2026-09-29","to":"2026-09-30","added":0,"removed":1,"changed":137,
 "first":[{"path":"mtm","label":"MTM (USD)","kind":"changed","before":1886961,"after":1603277,"delta":-283684.0},
          {"path":"risk.dv01","label":"DV01","kind":"changed","before":-156726,"after":-155245,"delta":1481.0}]}
```

Each change has `kind` `added`, `removed` or `changed`; `delta` is set for numbers. Each side (`from`, `to`)
carries its `businessDate`, `knownAt` and `provenance`. Two explicit dates:

```bash
curl -s "$B/history/trade/MX-20000001/diff?from=2026-09-28&to=2026-09-29" | jq -c '{from: .from.businessDate, to: .to.businessDate, changed}'
```

To see a restatement — what we knew about 29 September at 18:00 versus now — fix the date and vary "known at":

```bash
curl -s "$B/history/trade/MX-20000001/diff?from=2026-09-29&to=2026-09-29&fromKnownAt=2026-09-29T22:00:00Z" | jq '.changed'
```

Impact — what depends on a counterparty:

```bash
curl -s $B/impact/counterparty/CP-MERIDIAN-RE | jq -c '{groups: [.groups[0:2][] | {level, kind, mnemonic, first: .items[0].ref, hidden}], elapsedMs}'
```

```json
{"groups":[{"level":1,"kind":"agreement","mnemonic":"AGR","first":{"kind":"agreement","id":"AGR-MERIDIAN-RE-ISDA"},"hidden":0},
           {"level":1,"kind":"climate-profile","mnemonic":"CLIM","first":{"kind":"climate-profile","id":"CLIM-MERIDIAN-RE"},"hidden":0}],
 "elapsedMs":105.93}
```

Level 1 is what refers to the entity directly; level 2 is what those roll into. Each item is
`{ref, via, measure}`. Kinds the caller may not open are not listed, only counted in `hidden`.

### Structured search

| Method | Path | Notes |
|---|---|---|
| `GET` | `/search?q=` | as-of. `q` is `<MNEMONIC or kind> [where <condition>] [order by <field> [asc\|desc]] [limit n]`. Default limit 100, maximum 1000. `400 DRS-4004` if `q` cannot be read; `403` if the caller may not open the kind |
| `GET` | `/search/csv?q=` | the same as CSV for spreadsheets: `kind,id,title`, then the columns' labels; values unformatted; formula-like text prefixed with `'` |
| `GET` | `/search/compare?q=&from=&to=` | the search on two business dates: the later date's entities, each column as `{from, to, delta}` (delta for numbers), entities on one date only marked `added` or `removed` |

The condition runs on each document *as the caller may see it* ([field masks](#field-masks)), so a masked field
cannot be probed: a condition on it is never true, ordering by it does not order.
`order by` sorts numbers by value and text ignoring case, numbers before text, and entities without the field last
in either direction; equal values are ordered by id, so `limit` keeps the same rows on every store and every run.
Without `order by`, rows come in id order.
Numbers accept `k`, `m` and `bn` (or `b`) suffixes (`1m` = 1,000,000). URL-encode `q`; `curl -G --data-urlencode` does it for you:

```bash
curl -s -G $B/search --data-urlencode "q=TRD where mtm > 1m order by mtm desc limit 3" \
  | jq -c '{kind, condition, orderBy, descending, limit, columns, labels, rows: .rows[0:1], scanned, matched, partial, failed, elapsedMs}'
```

```json
{"kind":"trade","condition":"$.mtm > 1000000","orderBy":"$.mtm","descending":true,"limit":3,
 "columns":["$.mtm"],"labels":{"$.mtm":"MTM (USD)"},
 "rows":[{"ref":{"kind":"trade","id":"MX-20000043"},"title":"MX-20000043","values":{"$.mtm":71490903}}],
 "scanned":750,"matched":163,"partial":false,"failed":[],"elapsedMs":6.85}
```

`scanned` is how many documents were read, `matched` how many passed, and `partial: true` means the answer may
be missing rows: the scan limit was reached, or a source could not be read completely. `failed` names each source
that failed or did not answer in time, and why (any entry makes the search partial). A failing source never
looks like "nothing matched":

```json
"scanned":0,"matched":0,"partial":true,
"failed":[{"source":"trading-store","reason":"trade 2026-09-30 cannot be read: the native Delta engine does not decompress BROTLI Parquet pages (it reads Snappy, ZSTD, GZIP, LZ4, LZ4_RAW and uncompressed); rewrite the date with Snappy or ZSTD (tools/lake/maintain.py relayout --force --dates <date>)"}]
```

The reason is the connector's own when it says what to do (an unsupported codec, a day with unreadable lines);
otherwise only the kind of failure (`failed (SQLException; the server log and Admin → Health say more)`), since
driver and I/O messages can carry host names and paths; `did not answer within 3000 ms` for a timeout.
`/search/compare` is partial when either date is, with both dates' `failed`. A condition that does not parse:

```bash
curl -s -G $B/search --data-urlencode "q=TRD where mtm >"
```

```json
{"type":"about:blank","title":"bad search","status":400,
 "detail":"DRS-4004 the condition ends early: something is missing after '>' (for example mtm > 1m)","instance":"/api/v1/search","code":"DRS-4004"}
```

Field names are read in any case (`producttype` is `productType`). A field no entity of the kind has is
`400 DRS-4004 no trade has a field 'nosuchfield'; did you mean …?` (names are checked against the kind's columns and
key fields, then the documents the search read; when the scan was partial a name it did not meet is not reported). A
`limit` outside 1 to 1000 is `400 DRS-4004 limit must be a whole number from 1 to 1000, not '0'`.

The condition language is Rachana-EL; see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md).

When the kind's pack opts its results into a Pivot tab (`pivot:` in `pack.yaml`), the answer also carries `"pivot"`: the
fields on offer, each with `label`, `fmt` and `promoted` (whether a source keeps it as a column, so the server can
pivot it from columns), the arrangement it opens with, and the limits (`maxRowKeys`, `maxColumnKeys`,
`documentScan`). For other kinds `"pivot"` is `null`.

### The Pivot tab: panel rows, search pivots, saved pivots

The console's Pivot tab ([USER_GUIDE.md](USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)) is offered only where a
Sutra (`pivot:` on a table or ladder) or a pack (`pivot:` beside a kind's `columns:`) says so, and these endpoints refuse
anything else. All follow the business date (`X-Drishti-As-Of`), and the caller must be able to open the kind
(`403 DRS-5002`). `drishti.pivot.enabled: false` switches them all off.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/views/{kind}/{id}/panels/{panel}/records` | as-of. The panel's rows as raw values (below), up to `drishti.pivot.max-records` |
| `POST` | `/search/pivot/{kind}` | as-of. The cube of a search's matches: body `{"q", "rows", "columns", "values", "filters", "documents"}` |
| `POST` | `/search/pivot/{kind}/drill` | as-of. The entities behind one cell, a page at a time: the same body plus `"cell": {"rows": [...], "columns": [...]}`, `"offset"`, `"size"` (at most `drishti.pivot.drill-page`) |
| `POST` | `/search/pivot/{kind}/values` | as-of. A field's values among the matches, with counts (a filter's pick list): `{"q", "field", "documents"}` |
| `GET` | `/me/pivots` | the caller's saved pivots `{enabled, promote, review, pivots}`; each still valid against its Sutra or pack; `promote` is `author` with Studio saving on |
| `GET` · `PUT` · `DELETE` | `/me/pivots/panel/{sutra}/{panel}` | the caller's arrangement of a panel's pivot. `PUT` body `{"rows", "columns", "values", "filters", "heat", "chart"}`, checked against the fields the Sutra offers (`400 DRS-5001` with the reason); `DELETE` (back to the Sutra's) answers `204`; `GET` `404 DRS-1001` when none |
| `GET` · `PUT` · `DELETE` | `/me/pivots/search/{kind}` | the same for a kind's search results (`{kind}` may be a mnemonic) |
| `GET` | `/me/pivots/panel/{sutra}/{panel}/promotion` | what promoting the saved pivot would propose: `{sutra, panel, kind, fromVersion, version, base, text, changes, review}`, the next version with only the panel's `pivot:` rewritten. Needs `author` and `drishti.rachana.studio-save` |
| `POST` | `/me/pivots/panel/{sutra}/{panel}/promotion` | body `{"note": "…"}`: proposes it for review (`202` `{proposal}`), or with review off saves it (`200` `{saved}`) |

There is no separate power: keeping a pivot needs only the right to open the kind.

**A panel's rows.** The table data of a view carries the offer (`"pivot": {"fields": [...], "rows": [...], …,
"maxRows": 50000}`); the rows come separately, all of them, whatever the table's `limit`:

```bash
curl -s $B/views/netting-set/NS-NORTH-01/panels/trades/records | jq -c '.rows |= .[0:2]'
```

```json
{"panel":"trades","fields":[{"name":"product","label":"Product","fmt":null},{"name":"currency","label":"Currency","fmt":null},
  {"name":"maturity","label":"Maturity","fmt":"date"},{"name":"notional","label":"Notional","fmt":null},
  {"name":"mtm","label":"MTM (USD)","fmt":"signed0"},{"name":"trade","label":"Trade","fmt":null,"kind":"trade"}],
 "rows":[["Interest rate swap","USD","2031-10-02",50000000,-412580,"IRS-48213"],
         ["Interest rate swap","USD","2033-03-15",80000000,1106420,"IRS-47102"]],
 "total":14,"truncated":false,"limit":50000}
```

(finance pack). `kind` on a field says which entity its values open.

**A search's cube.** The server aggregates every entity the search matches on the date (not only a page; `limit`
and `order by` in `q` are ignored), over the day's promoted columns, and returns only the cells (a laid-out test table of 40 trades; numbers abridged):

```bash
curl -s $B/search/pivot/TRD -H 'Content-Type: application/json' -d '{"q": "TRD where mtm != null",
  "rows": ["book"], "columns": ["currency"], "values": [{"field": "mtm", "agg": "sum"}, {"field": "book", "agg": "count"}]}'
```

```json
{"rows":["book"],"columns":["currency"],
 "values":[{"field":"mtm","agg":"sum","show":"value","label":"Sum of MTM (USD)"},{"field":"book","agg":"count","show":"value","label":"Count of Book"}],
 "rowKeys":[["BOOK-A"],["BOOK-B"]],"columnKeys":[["EUR"],["USD"]],
 "cells":{"\u001e":[-1240500,40],"BOOK-A\u001e":[-18900,19],"BOOK-A\u001eEUR":[402100,6],"BOOK-A\u001eUSD":[-421000,13],
          "\u001eEUR":[…],"\u001eUSD":[…],"BOOK-B\u001e":[…],"BOOK-B\u001eEUR":[…],"BOOK-B\u001eUSD":[…]},
 "count":40,"total":40,"partial":false,"moreRows":false,"moreColumns":false,"masked":[],"source":"columns","elapsedMs":3.1}
```

- `rowKeys` and `columnKeys` are the innermost groups, full depth, in natural order (numbers by value, `2-5Y` before
  `10Y+`, `(blank)` last). Keys are text; a missing value is `(blank)`.
- `cells` has one entry for **every** combination of a row-key prefix and a column-key prefix, so subtotals and totals
  are there too: the key is the row keys joined by U+001F, then U+001E, then the column keys joined by U+001F
  (`"\u001e"` alone is the grand total, `"BOOK-A\u001e"` the row total of `BOOK-A`). Each holds one number per value
  (`null` when no row had a number). `agg` is `sum`, `count` (rows with a value), `avg`, `min`, `max` or `distinct`;
  `show` (`value`, `pctRow`, `pctColumn`, `pctTotal`) is applied by the client from these cells.
- `filters` are `{"field", "values": [...]}` (keys kept) or `{"field", "min", "max"}` (inclusive; numbers, or ISO
  dates as text); a bare field name keeps everything.
- At most `drishti.pivot.max-row-keys` (2,000) innermost row groups and `max-column-keys` (200) column groups are
  returned (`moreRows`, `moreColumns`); further groups still count in the totals.
- `masked` lists the fields the caller's role sees masked, as in a search (a field under a masked one too:
  `counterparty.name` when `counterparty` is masked): they group under `•••` and are never added up.
- A field that no source keeps as a column is refused, unless the body says `"documents": true`:

```json
{"type":"about:blank","title":"bad request","status":400,"code":"DRS-5001",
 "detail":"DRS-5001 not kept as columns for trade: [productType]; these are: [book, counterparty.id, mtm, nettingSet]. Ask for a document read (documents: true) to read up to 20000 trade documents instead; the result is then partial if there are more"}
```

  With `"documents": true` the documents are read (at most `drishti.pivot.document-scan`), `source` is `documents`
  and `partial` is `true` when there were more. An arrangement naming a field the pack does not offer is
  `400 DRS-5001 pivot rows name 'desk', which is not one of its fields (…)`; a kind whose pack offers no pivot is
  `400 DRS-5001 … search results do not offer a pivot`.

**A cell's entities.** `POST /search/pivot/TRD/drill` with `"cell": {"rows": ["BOOK-A"], "columns": []}` (empty
prefixes are totals), `"offset": 0`, `"size": 50`:

```json
{"fields":["book","mtm","nettingSet","counterparty.id"],"labels":{"book":"Book","mtm":"MTM (USD)","nettingSet":"Netting set","counterparty.id":"Counterparty id"},
 "rows":[{"id":"T-001","kind":"trade","values":{"book":"BOOK-A","mtm":-18900,"nettingSet":"NS-1","counterparty.id":"CP-1"}}, …],
 "total":20,"offset":0,"size":50,"partial":false,"masked":[],"source":"columns"}
```

**A field's values.** `POST /search/pivot/TRD/values` with `{"q": "TRD", "field": "currency"}` answers
`{"field": "currency", "values": [{"value": "EUR", "count": 14}, …], "more": false, "numeric": false, "min": null,
"max": null, "partial": false, "source": "columns"}` (at most 500 values; `min` and `max` for numbers).

### Live streams

All three are Server-Sent Events (`text/event-stream`). Use `curl -N` (no buffering). Details, event
shapes and reconnection rules are in [LIVE.md](../architecture/LIVE.md).

| Method | Path | Events |
|---|---|---|
| `GET` | `/views/{kind}/{id}/stream` | as-of. `view` (the full ViewModel, id `0`), then `frame` (patches, id = sequence), both with the caller's [field masks](#field-masks). For a past date or a non-live source: one `view`, then the stream closes |
| `GET` | `/me/monitors/{name}/stream` | as-of. `hello` `{rows}`, then one `row` event `{kind, id, patches, p99Ms}` per changed entity (strip patches only) |
| `GET` | `/me/alerts/stream` | `hello` `{user}`, then `alert` events (id = sequence) |
| `GET` | `/health/live` | not a stream: `{streams, topics, frames, p50Ms, p99Ms}` for the whole server |

All three send a comment line `:hb` when quiet (every `drishti.live.heartbeat`, default 15 s, for views;
every 15 s for monitors and alerts). View and monitor streams count against `drishti.live.max-streams`
(default 20000); beyond it a new stream is refused with `400 DRS-5001 too many live streams on this server`.

```bash
curl -s -N $B/views/trade/MX-20000001/stream
```

You should see (data lines shortened):

```
event:view
id:0
data:{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD","title":{…},"strip":[…],"panels":[…],…}

event:frame
id:1
data:{"seq":1,"generation":1686,"patches":[{"op":"strip","index":4,"cell":{"label":"MTM (USD)","text":"+1,595,251","tone":"pos","emphasis":true,"path":"$.mtm"}},{"op":"panel",…},{"op":"provenance",…}],"latencyMs":…,"p99Ms":…}
```

Press Ctrl+C to stop. Snapshot of a past date — one event and the stream ends by itself:

```bash
curl -s -N -H "X-Drishti-As-Of: 2026-09-29" $B/views/trade/MX-20000001/stream | head -c 300
```

The server-wide live figures:

```bash
curl -s $B/health/live
```

```json
{"streams":0,"topics":0,"frames":11680,"p50Ms":0.886,"p99Ms":1.917}
```

### Personal: settings, packs, workspaces, monitors, alerts

Everything under `/me` belongs to the caller (the token's subject, or `X-Drishti-User` with security off).
It is kept on the server, so it follows the user to any browser.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/me/settings` | `{theme, landing, clockZone, density, flash, searchLimit, pinned, notify: {email: {share, mention, reply}}}` with defaults filled in (the notify events are emailed when true) |
| `PATCH` | `/me/settings` | change only the fields named; `null` resets one; unknown field or bad value → `400 DRS-5001` |
| `GET` | `/admin/collab/shares?user=&kind=&id=&from=&to=&limit=&after=` · `/admin/collab/threads?…` | `admin` or `compliance`: search, metadata only (no note text), `{items[], next}` paged by id (`after` = the last id); `user` matches a sender or recipient, or a thread's writers |
| `GET` | `/admin/collab/threads/{tid}` | `compliance`: the thread as the record has it (hidden and retracted comments, every revision, unscrubbed) with the chain result; audited |
| `DELETE` | `/admin/collab/threads/{tid}` | `admin`: remove a whole thread for good; `423 DRS-7010` while a legal hold covers it; audited |
| `GET` · `POST` | `/admin/collab/holds[?active=true]` | `compliance`: list; place `{scope: entity\|kind\|user\|thread\|all, kind?, id?, user?, thread?, from?, to?, reason}` → `201` (`400` for a missing field, `DRS-7011` without a reason, `DRS-7005` for an unknown thread) |
| `DELETE` | `/admin/collab/holds/{id}` | `compliance`: release (`400` if already released); the hold stays in the list |
| `POST` | `/admin/collab/retention/run?dryRun=` | `admin`: run retention now; `{dryRun, threadsPurged, sharesPurged, threadsHeld, sharesHeld}` |
| `POST` | `/admin/collab/exports` `{from?, to?, kind?, id?, user?, includeShares?, includeThreads?}` | `compliance`: `202` with the job `{id, state, counts, error}`; `GET /admin/collab/exports/{id}` polls it; `GET /admin/collab/exports/{id}/download` is the zip, once, for the requester only (shares, threads, chains, holds, inbox notices and outbox delivery rows, each with its checksum in `manifest.json`) ([OPERATIONS](../admin/OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export)) |
| `GET` | `/admin/collab/verify?thread=` or `?kind=&id=&maxProblems=` | `compliance`: one chain `{ok, steps, firstHash, lastHash, problem}`, or every thread and share `{threads, threadsOk, shares, sharesOk, holds, holdsOk, auditOk, ok, problems[], truncated}` (problems also for changed live comment rows, removed newest revisions, holds and audit rows; an unreadable row is a problem, not an error) |
| `GET` | `/admin/collab/outbox?state=&limit=` | `admin`: `{enabled, available, dispatching, counts, items[]}`; rows name recipient, template, share, attempts, next attempt and last error, never the message |
| `POST` | `/admin/collab/outbox/{seq}/retry` | `admin`: send a dead or cancelled delivery again (`400` for any other state) |
| `GET` | `/admin/collab/bridges` | `admin`: `{enabled, renderAs, perMinute, bridges[]}`; each bridge `{name, format, usable, status, host, routes[], outbox{state: count}}`; never the URL path or the secret |
| `POST` | `/admin/collab/bridges/{name}/test` | `admin`: post a data-free test message to the bridge now; `{sent, bridge, status}`, `503 DRS-7013` with the reason when it fails, `404 DRS-7014` for an unknown name |
| `POST` | `/admin/collab/mail-test` | `admin`: mail a test message to your own address now; `503 DRS-7012` with the SMTP reason when it fails |
| `GET` | `/me/packs` | `{assigned, active}` |
| `PUT` | `/me/packs` | body `{"active": [...]}`; must be a non-empty subset of `assigned` (`403 DRS-5002` otherwise) |
| `GET` | `/me/workspaces` | names of saved workspaces |
| `GET` · `PUT` · `DELETE` | `/me/workspaces/{name}` | one workspace `{layout, panes, sizes}`; `sizes` (optional) is `{"cols": [1.4, 0.6], "rows": [1, 1]}`, one weight from 0.1 to 10 per column and row of the layout, where its dividers were dragged; `404 DRS-1001` if missing; `DELETE` answers `204` |
| `GET` | `/me/layouts` | personal layouts: `{enabled, allowed, promote, review, layouts}`; `allowed` is the `layout` power, `promote` adds `author`; each layout is read against its Sutra as it is now |
| `GET` · `PUT` · `DELETE` | `/me/layouts/{sutra}/{kind}` | the caller's layout of a Sutra. `PUT` body `{"panels": [{"id": "cashflows", "area": "main", "span": 8, "height": 10}, {"id": "built", "hidden": true}]}` in display order: only panel ids the Sutra has, `area` `main`/`right`, `span` 1-12, `height` 1-24, else `400 DRS-5001`; the Sutra must lay out `kind`. `GET` drops panels the Sutra no longer has and appends new ones (`added: true`). `DELETE` (back to the Sutra) answers `204`. `403 DRS-5002` without the `layout` power |
| `GET` | `/me/layouts/{sutra}/{kind}/promotion?dropHidden=false` | what promoting the layout would propose: `{sutra, kind, fromVersion, version, base, text, changes, review}`, the next version written from the latest one (order, `area`, `span`, `height`; the rest of the text kept). Needs `author` |
| `POST` | `/me/layouts/{sutra}/{kind}/promotion` | body `{"note": "…", "dropHidden": false}`: proposes it for review (`202` `{proposal}`), or with review off saves it (`200` `{saved}`) |
| `GET` | `/me/monitors` | names of saved monitors (watchlists) |
| `PUT` · `DELETE` | `/me/monitors/{name}` | body `{"entities": [{"kind","id"}, …]}`, 1 to 50 entities; `DELETE` answers `204` |
| `GET` | `/me/monitors/{name}` | as-of. One row per entity: `{ref, mnemonic, title, strip, live}` or `{ref, error}` |
| `GET` | `/me/alerts/rules` | the caller's alert rules |
| `PUT` · `DELETE` | `/me/alerts/rules/{name}` | body `{kind, id, when, severity, message, enabled}`; `kind`, `id`, `when` required; `severity` defaults to `warn`; `enabled` to `true`; `DELETE` answers `204` |
| `GET` | `/me/alerts?limit=50` | alerts that fired for the caller, newest first |
| `GET` | `/me/alerts/suggestions/{kind}` | rules the installed packs suggest for a kind |

**Settings.** The values allowed:

| Field | Default | Allowed values |
|---|---|---|
| `theme` | `null` (console default) | `terminal`, `light`, `wallstreet`, `blue`, `green`, `crimson`, `crimson-dark` |
| `landing` | `/t` | `/t`, `/help`, `/w/<workspace>`, `/m/<monitor>`, `/v/<kind>/<id>` |
| `clockZone` | `null` | any Java time-zone id, e.g. `Europe/London` |
| `density` | `comfortable` | `comfortable`, `compact` |
| `flash` | `true` | `true`, `false` |
| `searchLimit` | `100` | 10 to 1000 |
| `pinned` | `[]` | up to 20 `{"kind","id"}` |

```bash
curl -s -H "X-Drishti-User: ash" $B/me/settings
```

```json
{"theme":null,"landing":"/t","clockZone":null,"density":"comfortable","flash":true,"searchLimit":100,"pinned":[]}
```

Change two settings and reset the clock zone (this writes; run it against your own account):

```bash
curl -s -X PATCH -H "X-Drishti-User: ash" -H 'Content-Type: application/json' $B/me/settings \
  -d '{"theme": "light", "density": "compact", "clockZone": null}'
```

You should see the full settings back, with `"theme":"light"` and `"density":"compact"`. A bad value:
`{"theme": "pink"}` → `400` with `detail` `DRS-5001 theme is one of [blue, crimson, crimson-dark, green, light, terminal, wallstreet]`.

**Packs.**

```bash
curl -s $B/me/packs | jq -c .
```

```json
{"assigned":["banking-core","market-data","trading","market-risk", …],"active":["banking-core","market-data","trading","market-risk", …]}
```

**Workspaces.** A layout (`2col`, `3col`, `2x2` or `1+2`) and 1 to 4 panes. Each pane has `ref` (an entity or
`null`), `follows` (the index of another pane whose selection it follows, or `null`) and `title` (cut to 60
characters). Panes may not follow themselves or each other in a circle.

```bash
curl -s -X PUT -H "X-Drishti-User: ash" -H 'Content-Type: application/json' $B/me/workspaces/rates \
  -d '{"layout": "2col", "panes": [
        {"ref": {"kind": "trade", "id": "MX-20000001"}, "title": "Swap"},
        {"ref": null, "follows": 0, "title": "Counterparty"}]}'
```

You should see the stored workspace:
`{"layout":"2col","panes":[{"ref":{"kind":"trade","id":"MX-20000001"},"follows":null,"title":"Swap"},{"ref":null,"follows":0,"title":"Counterparty"}]}`.

**Monitors.**

```bash
curl -s -X PUT -H "X-Drishti-User: ash" -H 'Content-Type: application/json' $B/me/monitors/swaps \
  -d '{"entities": [{"kind": "trade", "id": "MX-20000001"}, {"kind": "trade", "id": "MX-20000002"}]}'
curl -s -H "X-Drishti-User: ash" $B/me/monitors/swaps | jq -c '.[] | {ref, mnemonic, live, strip: (.strip | length)}'
```

Each row is the entity's title and strip; a row that cannot be built carries `error` (a string that starts with
the error code, for example `DRS-1001` for an entity no source holds) and the other rows are unaffected.

**Alerts.** `when` and `message` are Rachana-EL, checked when the rule is saved (`422 DRS-2101` if `when`
does not parse). `severity` is `info`, `warn` or `critical`.

```bash
curl -s -X PUT -H "X-Drishti-User: ash" -H 'Content-Type: application/json' $B/me/alerts/rules/mtm-drop \
  -d '{"kind": "trade", "id": "MX-20000001", "when": "$.mtm < 1500000", "severity": "warn",
       "message": "${$.tradeId}: MTM ${fmt($.mtm, '"'"'signed0'"'"')}"}'
curl -s -H "X-Drishti-User: ash" "$B/me/alerts?limit=5"
```

A fired alert looks like `{"seq", "at", "user", "rule", "kind", "id", "severity", "message", "generation"}`.
Suggestions come from the `alerts:` list in installed packs' `pack.yaml`; for example the finance pack
suggests, for `trade`:
`{"kind":"trade","name":"MTM below −450k","when":"$.mtm < -450000","severity":"warn","message":"${$.tradeId}: MTM ${fmt($.mtm, 'signed0')}"}`.
The answer is `[]` when no installed pack suggests rules for the kind.

### Catalogue: about, packs, sources, Sutras

| Method | Path | Notes |
|---|---|---|
| `GET` | `/about` | `{product, version, built, java, uptimeSeconds, sutras, sources, securityEnabled, packs, copyright}` |
| `GET` | `/packs` | every installed pack `{name, version, title, description, console, kinds, assigned, active}` (assigned/active for the caller) |
| `GET` | `/sources` | `{sources: [{name, version, kinds, live, search, reverseLookup, health}], failures: {name: reason}}`; `kinds: []` means the source serves any kind |
| `GET` | `/sutras` | every loaded Sutra `{name, latest, versions, domain, kind, where, priority}` |
| `GET` | `/sutras/{name}/{version}` | one parsed Sutra as JSON `{name, version, domain, match, title, strip, panels, keys, location}`; `404 DRS-2003` |
| `GET` | `/sutras/{name}/{version}/source` | the Sutra file as written (`text/yaml`, the whole `.sutra.yaml` file with its comments); `404 DRS-2003` |
| `GET` | `/sutras/problems` | `{file: [{code, message, location}]}` for Sutras that failed to load, and for files in a Sutra folder that are not Sutras (`DRS-2004`); `{}` when all is well |
| `GET` | `/rachana/schema` | the JSON Schema (draft 2020-12, `application/schema+json`) of a Sutra in Rachana language 1, generated from the grammar, with this server's entity kinds and formats filled in and the Rachana-EL functions under `x-rachana-functions`; for editor completion and checking |

```bash
curl -s $B/packs | jq -c '.[0:2][] | {name, version, title, kinds: .kinds[0:3], assigned, active}'
```

```
{"name":"banking-core","version":"1.0.0","title":"Banking core","kinds":["counterparty","counterparty-group","issuer"],"assigned":true,"active":true}
{"name":"market-data","version":"1.0.0","title":"Market data","kinds":["ir-curve","repo-curve","fx-spot"],"assigned":true,"active":true}
```

```bash
curl -s $B/sources | jq -c '.sources[0:2][], .failures'
```

```
{"name":"demo","version":"1.0","kinds":[],"live":true,"search":true,"reverseLookup":true,"health":"UP"}
{"name":"file","version":"1.0","kinds":[],"live":false,"search":true,"reverseLookup":false,"health":"UP"}
{}
```

```bash
curl -s $B/sutras | jq -c 'length, .[0]'
```

```
223
{"name":"abs","latest":1,"versions":[1],"domain":"fixed-income","kind":"trade","where":"$.productType == 'ABS'","priority":10}
```

```bash
curl -s $B/sutras/irs-fixfloat/1/source | sed -n '15,19p'
```

```
# Generated by tools/packgen/banking/make_sutras.py from the taxonomy. Edit the taxonomy, not this file.
rachana: 1
sutra: irs-fixfloat
version: 1
description: Exchanges fixed for floating RFR-compounded payments.
```

The first 13 lines are the copyright comment. Every Sutra is one YAML file that starts with `rachana: 1`
(the Rachana language version); see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md).

The schema of the language, for an editor or a linter:

```bash
curl -s $B/rachana/schema | jq -c '{title, required, kinds: (.properties.match.properties.kind.enum | length),
  panelKinds: ."$defs".panel.properties.kind.enum, functions: (."x-rachana-functions" | keys | .[0:5])}'
```

You should see `"title":"Rachana Sutra, language 1"`, `"required":["rachana","sutra","version","match"]`, the
number of entity kinds the enabled packs serve, the twenty-one panel kinds (`kv`, `table`, … `metric`) and the first
functions (`abs`, `coalesce`, …). Any editor that reads JSON Schema can use it: in VS Code with the YAML
extension, save it next to your Sutras and add `# yaml-language-server: $schema=rachana.schema.json` as the first
line of a Sutra to get completion and checking as you type. Sutra Studio uses the same schema.

### Sutra Studio and governance

| Method | Path | Notes |
|---|---|---|
| `GET` | `/studio/settings` | `{save, review, approve}`: may the caller save, is review on, may the caller approve |
| `POST` | `/studio/preview` | as-of. Body `{yaml, kind, id}` renders an unsaved Sutra against a real entity; or `{yaml, kind, id, document}` against pasted JSON. Answers a `ViewModel`; `422 DRS-2001`/`DRS-2002` with `problems` if the Sutra is bad; `400 DRS-5001` when `yaml` or `kind` is missing or not text, `id` is missing without a `document`, or the body is not a JSON object |
| `GET` | `/studio/inferred/{kind}/{id}?name=` | as-of. A starter Sutra (`text/yaml`, beginning `rachana: 1`) from what inference makes of the entity; name defaults to `<kind>-custom` |
| `POST` | `/studio/inferred` | body `{kind, id, document, name}`: the same (`text/yaml`) from pasted JSON (`422 DRS-1005` if `document` is not a JSON object) |
| `POST` | `/sutras?note=` | body: the Sutra YAML, `Content-Type: text/yaml` (or `application/yaml`, `text/plain`). It is saved as `<domain>/<name>.v<N>.sutra.yaml`. Needs `drishti.rachana.studio-save: true` and an `author` role (`403` otherwise). With governance on: `202 {"proposal": {id, name, version, status}}`; off: `200` with the saved Sutra's `{name, latest, versions, …}` |
| `GET` | `/sutras/proposals?status=&name=` | `{enabled, proposals: [...]}` visible to the caller |
| `GET` | `/sutras/proposals/{id}` | one proposal plus `text` (proposed), `baseText` (what it was based on) and `liveText` (live now); `404 DRS-2005` |
| `POST` | `/sutras/proposals/{id}/approve` | body `{"comment": "..."}` optional. Publishes the Sutra. `403 DRS-2007` for your own proposal (four eyes); `409 DRS-2006` if already decided or the live Sutra changed since |
| `POST` | `/sutras/proposals/{id}/reject` | body `{"comment": "..."}` — comment required |
| `POST` | `/sutras/proposals/{id}/withdraw` | the author withdraws a pending proposal |
| `GET` | `/sutras/{name}/history` | every proposal for a Sutra, newest first |

A proposal summary: `{id, name, version, note, author, createdAt, status, reviewer, reviewedAt, comment,
newVersion, stale, mayApprove, mayWithdraw}`. `stale: true` means the live Sutra changed after it was proposed.

```bash
curl -s $B/studio/settings; echo
curl -s $B/sutras/proposals | jq -c .
```

```
{"approve":true,"review":true,"save":true}
{"enabled":true,"proposals":[]}
```

Start a new Sutra from an entity and preview it without saving (preview does not change anything on the
server):

```bash
curl -s "$B/studio/inferred/trade/MX-20000001?name=my-swap" > my-swap.v1.sutra.yaml
head -6 my-swap.v1.sutra.yaml
jq -n --rawfile y my-swap.v1.sutra.yaml '{yaml: $y, kind: "trade", id: "MX-20000001"}' \
  | curl -s -X POST $B/studio/preview -H 'Content-Type: application/json' -d @- | jq -c '{title, panels: [.panels[].id]}'
```

You should see the draft begin with `# Started from what inference makes of trade MX-20000001. Edit freely.`, then
`rachana: 1`, `sutra: my-swap`, `version: 1`, a `description:` line and `match: { kind: trade, priority: 1 }`;
then the preview's title and panel ids.

Propose it (with governance on, an approver then approves it in **Build → Govern → Reviews**):

```bash
curl -s -X POST "$B/sutras?note=first%20draft" -H 'Content-Type: text/yaml' --data-binary @my-swap.v1.sutra.yaml
```

You should see `{"proposal":{"id":"…","name":"my-swap","version":1,"status":"…"}}` with HTTP `202`.

### Using the shape API

The Screen Builder's first step turns sample JSON documents into one JSON Schema (draft 2020-12) and tells you what each
field is for. It needs the `author` role (as Studio), reads and writes nothing in Drishti, and keeps no copy of what you send.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/builder/shape` | body `{"samples": [{"name": "a.json", "document": {...}}, ...]}`; answers `{schema, roles, report}` |

- `schema`: the merged JSON Schema. A field in every document is `required`; in some, optional with
  `x-drishti.presence` (the share of its records that hold it); `null` in some makes the type nullable; different
  types become `oneOf` and are flagged as a conflict; ids as keys become `additionalProperties` (a map); a list of
  records holding a list of the same records is a recursive `$ref` (a tree). Text with few repeating values gets an
  `enum`; text that is always a date, date-time, uuid, e-mail or ISO currency gets a `format`; numbers get
  `minimum`/`maximum`, and `integer` when none was fractional. Every path may carry `x-drishti.role` and
  `x-drishti.reason`.
- `roles`: `{path: {role, reason, kind}}`. Roles: `id`, `link`, `measure`, `dimension`, `status`, `date`, `series`,
  `ohlc`, `distribution`, `grid`, `steps`, `graph`, `tree`, `events`, `text`, `table` (other lists of records), `plain`.
  The reason says why, for example "named like an id, and no value repeats".
- `report`: `{samples, files, conflicts, rare, paths}`; per path the type, role, reason, presence, up to three example
  values (a masked value stays masked) and, for fields not in every file, which files had them. Conflicts come first,
  then rare fields.
- Limits (`drishti.builder.*`): 50 samples, 5 MB per document, 25 MB per request, 64 levels deep. Over a limit is
  `413 DRS-5005` naming the limit (and the file); a body that is not the right shape is `400 DRS-5001`; a caller who is
  not an author gets `403 DRS-5002`.

```bash
curl -s -X POST $B/builder/shape -H 'Content-Type: application/json' \
  -d "{\"samples\":[{\"name\":\"pnl-explain.json\",\"document\":$(cat docs/guides/examples/pnl-explain.json)}]}" \
  | jq -c '.roles'
```

#### Operations and the check matrix (stateless)

Two endpoints for the Build workbench and the CLI, open to every signed-in user, writing nothing:

- `POST /api/v1/builder/edit` with `{yaml, ops: [...]}` answers `{yaml, applied, problems}`. Operations are applied in order to the
  Sutra's text, which keeps its comments, quoting and key order wherever an operation does not touch them. After each operation the
  result is parsed as a Sutra; an operation that fails, or would make an invalid Sutra, is skipped with a located problem
  `{op, name, code, message, line}` (`op` is its index in the list) and the text stays as it was: the answer is never a corrupted Sutra.
- `POST /api/v1/builder/check` with `{yaml, kind?, samples: [{name, document} | {name, ref: {kind, id}}]}` answers the *check matrix*:
  `{ok, samples: [{name, layout, status, message?}], panels: [{id, cells: [{status, message?}], counts}], strip: [{label, blank}], counts}`.
  A cell is `ok`, `empty` (the document lacks what the panel asks for), `error` (with the panel's message) or `noAccess` (a reference to
  a kind you may not open, or a panel whose `source` is one). References are read again with your rights and masks. An invalid
  Sutra is the usual `422` with its problems. Studio's test and auto-design's pruning use the same checker, so they agree.

The operations (`{"op": ..., ...}`, fields not listed are refused with the operation's index in the message):

| `op` | Fields | Does |
|---|---|---|
| `addPanel` | `kind`, `id?`, `at?: {area, before \| after, span, height}`, `options?` | adds a panel of one of the 21 kinds; a kind's required options (such as `rows`) must be in `options`; without `at` it goes at the end of the main column; no `id` makes one from the kind |
| `move` | `panel`, `area?`, `before? \| after?`, `span?`, `height?` | moves (and resizes) a panel through the layout editor; `span: 12` is the whole column, `height: 0` as tall as the content; omitted sizes stay |
| `setOption` | `panel`, `option`, `value` | sets one option or panel key (`title`, `key`, `code`, `area`, `span`, `height`, `columns`, the kind's options); `null` removes it; checked against the kind's options and their allowed values |
| `bind` | `panel`, `path`, `role?` | binds a path or field in a role the kind takes: its options (`rows`, `x`, `y`, `value`, `by`, `across`...) or `column` (table, ladder), `field` (kv, status), `series` (area), which append; no role: the kind's next unfilled one |
| `remove` | `panel` | removes a panel with the comment just above it |
| `setTitle` | `title: {pill, id, with?}` | the title line |
| `setStrip` | `items: [{label, bind, fmt?, tone?, emphasis?}]` | the header key figures; `[]` removes the strip |
| `setKeys` | `keys: {F2: ...}` | the function keys; `{}` removes them |
| `setMatch` | `match: {kind, where?, priority?}` | which entities the Sutra applies to |
| `text` | `yaml` | replaces the whole text (the YAML tab); comments and order are then the new text's |

Operation codes: `DRS-5020` malformed, `DRS-5021` no such panel, `DRS-5022` kind or option not accepted, `DRS-5023` value not valid,
`DRS-5024` text cannot be edited in place; a result that is not a valid Sutra keeps the parser's `DRS-2nnn`.

#### Designs: your work, kept on the server

`/api/v1/builder/designs` keeps a user's samples, Sutra and notes (the Build workbench's *My designs*). Open to every signed-in
user; a design is reachable only by its owner (anyone else gets `404 DRS-5006`); limits answer `413 DRS-5005`
(`drishti.builder.designs.*`). Sample contents are never logged.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/builder/designs` | `{designs: [...], limits}`: the caller's designs, with sample names and types, size and `expiresAt` (no Sutra text) |
| `POST` | `/builder/designs` | body `{name, kind, base, sutra, notes}`, all optional (no name: a scratch design, forgotten after a day); `base` as `name@version` without `sutra` copies that Sutra; `201` with the design |
| `GET` / `PATCH` / `DELETE` | `/builder/designs/{id}` | read (touches it), change `name`, `kind`, `notes`, `sutra` (a new text is a new `rev`), `tests`; delete (and its samples) |
| `POST` | `/builder/designs/{id}/duplicate` | body `{name}` optional; a named copy with its samples |
| `POST` | `/builder/designs/{id}/propose` | body `{note}` optional; author right and `studio-save`. Runs the check and proposes the design's Sutra **with evidence** (matrix, sample names, notes; `GET /sutras/proposals/{id}` returns it as `evidence`). `202 {proposal, status}`; with review off `200 {name, version, status}`. The design's `status` becomes `proposed(P-n)`, then `live(vN)` on approval |
| `GET` / `PUT` | `/builder/designs/{id}/about` | the design's About text (the pack's `config/about.yaml`) and its card over `?sample=` (`{rev, about, kind, card: {about, glossary, errors}, panels, problems, lint, coverage}`: `problems` are `DRS-2040`-`2044` with line and column, `lint` is `DRS-2045`-`2047`); `PUT {baseRev, text}` keeps the text as a step of the log (a new `rev`; `409 DRS-5007` when stale; counted with `max-notes-kb`) |
| `POST` | `/builder/designs/{id}/about/preview` | body `{text, sample?}`: the same answer for the text in the editor, nothing kept |
| `GET` | `/builder/designs/{id}/export` | a zip pack fragment: `pack.yaml` stub, the Sutra, `tests/<sutra>/*.json` + `expect.yaml`, three samples, README, and `config/about.yaml` when the design has About text |
| `POST` | `/builder/designs/import` | body a zip (`application/zip`); one design per `*.sutra.yaml`, with the samples of `tests/<sutra>/` and `samples/<kind>/`; `201 {designs, skipped}` |
| `POST` / `DELETE` | `/builder/designs/{id}/share` | makes (renews) or revokes the read-only link; `{token, path}` is shown once |
| `GET` | `/builder/designs/shared/{id}?token=` | what a link holder sees: Sutra, operations, sample names (never contents or notes); a bad or revoked token is `404 DRS-5006` |
| `GET` | `/builder/designs/binding` | `{enabled, dir}`: whether `drishti.builder.file-binding` is on and the name of your development folder (never an absolute path; administrators also get `absoluteDir`) |
| `POST` / `DELETE` | `/builder/designs/{id}/bind` | body `{file}` (a path in your own development folder, `drishti.builder.dev-dir/<user>`); binds or unbinds (development servers; `403` when off; a symbolic link, `..` or a name the file system refuses is `400`) |
| `POST` | `/builder/designs/{id}/save-file` | writes the Sutra to the bound file in your development folder (`409 DRS-5007` if the file changed on disk first); never makes it live, that is `propose` |
| `POST` | `/builder/designs/{id}/sync` | reads the bound file: an outside edit becomes a step of the design (`changed: true`). A POST because it changes the design: personal API tokens (read-only) cannot call it |
| `POST` | `/builder/designs/{id}/rebase` | body `{baseRev}`; the design's base `name@v` has a newer live version (`baseMoved` on the design, on `check` and on `propose`): replays the design's operations on it. Answers the design, `replayed` and `problems` (steps that no longer apply, text edits that cannot be replayed; each says which step and why). `400` when the base has not moved |
| `DELETE` | `/builder/designs?scratch=true` | deletes all your scratch (unnamed) designs; `{deleted}` |
| `POST` | `/builder/designs/{id}/samples` | `{samples: [{name, document}]}` and/or `{refs: {kind, ids \| count}}` (stored entities, kept as references) and/or `{schema, count}` (synthetic documents generated from a JSON Schema or shape.json, labelled `synthetic`); a sample of an existing name replaces it |
| `DELETE` | `/builder/designs/{id}/samples?name=` | remove a sample by name |
| `GET` | `/builder/designs/{id}/samples/document?name=` | a kept sample document (a reference keeps none) |
| `POST` | `/builder/designs/{id}/shape` | the shape of the samples, as `/builder/shape`, plus `skipped` (references that could not be read, with the reason) |
| `POST` | `/builder/designs/{id}/ops` | body `{baseRev, ops, sample?}`: applies operations (as `/builder/edit`) to the design's Sutra and appends the ones that applied to its log; answers `{rev, yaml, status, problems, applied, preview}` (`preview` of `sample`, default the first; `previewError` if it cannot be drawn). `baseRev` is the `rev` you built on: a stale one is `409 DRS-5007` and nothing changes. The log keeps `drishti.builder.designs.max-ops` steps |
| `POST` | `/builder/designs/{id}/undo`, `/redo` | body `{baseRev?}`: moves back or forward one step along the log (a new `rev` with the earlier or later text); `409 DRS-5007` when there is no step to move to or `baseRev` is stale. A new operation after an undo drops what redo would have brought back; a text change made with `PATCH` is a step too |
| `GET` | `/builder/designs/{id}/versions`, `/versions/{n}` | the Sutra texts the design's log passes through: `{versions: [{n, at, ops, current}]}` (0 is the text before the first step the log still holds, `n` the text after step `n`), and one text as `{n, yaml}`; `404 DRS-5006` for a number the log does not hold. The workbench diffs these and restores one as a `text` operation |
| `POST` | `/builder/designs/{id}/check` | the check matrix (as `/builder/check`) over all the design's samples, plus the `rev` checked; a green matrix marks the design `checked` until the next edit |
| `GET` | `/builder/designs/{id}/preview?sample=` | the Sutra against one sample (default the first). A reference is read again through the sources with the caller's rights and masks; a kind the caller may not open is `403 DRS-5002` "no access" |
| `POST` | `/builder/designs/{id}/autodesign` | drafts a Sutra from the samples as `/builder/design` does, keeps it as the design's Sutra and answers the draft with `rev` |

#### Drafting a screen: design and suggest

| Method | Path | Notes |
|---|---|---|
| `POST` | `/builder/design` | body `{"samples": [...], "kind": "trade"}` (or `{"shape": {schema, roles}, "samples": [...]}`); answers `{yaml, reasons, alternatives, pruned, preview, samples}` |
| `POST` | `/builder/suggest` | body `{"shape": {schema, roles}, "path": "$.profile", "at": "$.rows[].mtm"}` (or `samples` instead of `shape`); answers `{path, at, suggestions}` |

Both need the `author` role, write nothing, keep no copy and take the same limits as `/builder/shape`
(`413 DRS-5005`, `400 DRS-5001`, `403 DRS-5002`).

- `design`: `yaml` is a complete Sutra (title, strip of at most six figures, panels chosen from the fields' roles, keys
  for links, layout by the inference packing rules). `reasons` says why for `title`, `strip`, each `strip.<label>` and
  each panel id. `alternatives` maps a panel id to its runner-up kinds, each `{kind, score, reason, area, title, options,
  columns}` with the options already filled. With `samples`, the draft is rendered against each of them (Studio's preview
  path, with your masks) and `pruned` lists the panels and strip figures dropped or demoted because they were empty or
  failed for more than `drishti.builder.prune-share` of the samples, each `{panel, kind, action, to, bad, of, reason}`.
  `preview` is the view of the first sample (the view model Studio shows), `null` without samples. `kind` (default
  `sample`) is the entity kind the Sutra matches; you need to be allowed to open it.
- `suggest`: the panel kinds that suit the field at `path`, best first, each `{kind, score, reason, area, title,
  options, columns}`. With `at`, the second field of the same rows (a dimension and a measure, in either order, or two
  measures), the suggestions are for the pair: a pivot with row groups, bars, a scatter. An unknown path is `400
  DRS-5001` ("no such path in the shape").

```bash
curl -s -X POST $B/builder/design -H 'Content-Type: application/json' \
  -d "{\"kind\":\"trade\",\"samples\":[{\"name\":\"showcase.json\",\"document\":$(cat docs/guides/examples/all-panels-showcase.json)}]}" \
  | jq -r '.yaml'
```

### Sign-in and user administration

These are summarised here; [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) explains users, roles, password rules,
lockout and the audit log in full. User records are held by the server's identity store; the console never
sees password hashes.

| Method | Path | Who | Notes |
|---|---|---|---|
| `POST` | `/auth/login` | console service only | body `{username, password}` → the user; `401 DRS-6004` wrong credentials, `423 DRS-6005` locked |
| `POST` | `/auth/oidc` | console service only | body `{idToken, nonce}`: single sign-on; the server verifies the provider's token and maps groups to roles; `401 DRS-6004` when refused; `403` if SSO is off |
| `POST` | `/auth/sessions` | console service only | body `{username, seconds}` (60 s to 7 days): opens a sign-in session for a user just verified → `201 {id, expiresAt, user}`; `403 DRS-5002` if the user is disabled. Only a hash of the id is stored |
| `GET` | `/auth/sessions/{id}` | console service only | the session and its user as they are now → `{expiresAt, user}` (roles, `enabled`, `mustChangePassword`); `401 DRS-5010` when it ended (signed out, expired, user disabled or deleted) |
| `DELETE` | `/auth/sessions/{id}` | console service only | ends it (sign-out) → `204`. Disabling, deleting or resetting the password of a user ends all their sessions |
| `GET` | `/auth/me` | any signed-in user | the caller's own record; `404 DRS-6001` if the caller has no user record (e.g. `anonymous` with security off) |
| `POST` | `/auth/password` | any signed-in user | body `{current, next}`; `422 DRS-6003` if `next` is too weak |
| `GET` | `/admin/users?q=` | admin | users, optionally filtered |
| `POST` | `/admin/users` | admin | body `{username, displayName, email, desk, roles, enabled, password, mustChangePassword, packs}` → `201`; `409 DRS-6002` exists |
| `GET` · `PUT` · `DELETE` | `/admin/users/{username}` | admin | read, update the profile, delete (`204`; `409 DRS-6006` for the last admin) |
| `POST` | `/admin/users/{username}/enabled` | admin | body `{"enabled": false}` |
| `POST` | `/admin/users/{username}/password` | admin | body `{"password": "..."}` |
| `GET` | `/admin/audit?limit=200&subject=` | admin | audit events `{at, actor, action, subject, detail}`, newest first |
| `GET` | `/admin/roles` | admin | role names users may be given |
| `GET` | `/admin/status` | admin | `{defaultAdminPasswordInUse, users, forceChangeOnCreate, installedPacks}` |

A user record looks like:

```json
{"username":"drishti-dev-admin","displayName":"Drishti dev admin","email":"","desk":"Administration","roles":["admin"],
 "enabled":true,"mustChangePassword":false,"locked":false,"createdAt":"2026-09-30T17:45:57.569465251Z",
 "updatedAt":"2026-09-30T17:45:57.569465251Z","lastLoginAt":null,"passwordChangedAt":"2026-09-30T17:45:57.569465251Z","packs":null}
```

```bash
curl -s $B/admin/status | jq -c .
```

```json
{"forceChangeOnCreate":false,"installedPacks":["banking-core","market-data","trading", …],"defaultAdminPasswordInUse":true,"users":1}
```

`defaultAdminPasswordInUse: true` is a warning: change the seeded admin's password before production.

### Administration: health and caches

| Method | Path | Notes |
|---|---|---|
| `GET` | `/admin/health` | admin. Everything Drishti depends on, in one answer. Cheap enough to poll every few seconds |
| `GET` | `/admin/caches` | admin. Each cache: `{name, type, stats}` — `engine` (layouts and shape fingerprints) and every connector that caches |
| `POST` | `/admin/caches/{name}/purge` | admin. Purge one cache by name, or every cache with `all`; `{purged, elapsedMs}`; `404 DRS-5004` for an unknown name. Recorded in the audit log |

`status` is `OK`, `DEGRADED` (a source is down or degraded, a plugin failed to start, a pack has broken Sutras or a
down connector, or the Sutra watcher has stopped) or `DOWN` (no source is up). A source's `status` is `UP`,
`DEGRADED` (it serves, but some of its data cannot be read: `health` names the table and date and why, e.g.
`DEGRADED: cannot read trade 2026-09-30: … BROTLI …`) or `DOWN`; down sources are listed first, then degraded ones.
`summary.sourcesDegraded` counts them. `sutras` is `{"hotReload": "WATCHING" | "OFF" | "STOPPED: <reason>",
"problemFiles": <n>}`.

```bash
curl -s $B/admin/health | jq -c '{status, summary, server, live}'
```

```json
{"status":"OK",
 "summary":{"packsWithProblems":0,"failedToStart":0,"sourcesDown":0,"sourcesDegraded":0,"sources":18,"packs":12},
 "server":{"version":"1.16.0","uptimeSeconds":6953,"java":"25.0.4.1","heapUsedMb":146,"heapMaxMb":15640,"threads":75,"cpus":24},
 "live":{"frames":11680,"p50Ms":0.886,"streams":0,"p99Ms":1.917,"topics":0,"droppedFrames":0}}
```

One source row and one pack row:

```bash
curl -s $B/admin/health | jq -c '.sources[0], .packs[0]'
```

```
{"name":"civic-store","version":"1.0","status":"UP","health":"UP","kinds":["bill","candidate","election", …],"live":false,"dated":true,"search":true,"reads":{"reads":0},"cache":{"tables":8,"partitions":8,"timeTravel":0}}
{"name":"banking-core","title":"Banking core","version":"1.0.0","extends":[],"overrides":[],"kinds":12,"sutras":12,"sutraProblems":[],"connectors":["reference-store"],"connectorsDown":[],"connectorsOff":[],"status":"OK"}
```

A source's `reads` grows read counts, errors, p50/p99 and the last error once it has served traffic.
`connectorsOff` lists connectors a pack expects that are disabled or failed to start.

Caches:

```bash
curl -s $B/admin/caches | jq -c '.[0:2][]'
```

```
{"name":"engine","type":"Layouts and shape fingerprints","stats":{"layouts":24,"fingerprints":8818,"layoutHitRate":0.999}}
{"name":"market-store","type":"Connector","stats":{"tables":20,"partitions":24,"timeTravel":0}}
```

Purging (writes; do it when a source's data changed under the server and you want it re-read now):

```bash
curl -s -X POST $B/admin/caches/market-store/purge
```

You should see `{"purged":["market-store"],"elapsedMs":…}`.

### Outside /api/v1: OpenAPI and actuator

These paths are not under `/api/v1/`, so the token filter does not apply to them. With security on, a
separate guard does: only `/actuator/health` (and its probes) is open, `/api/docs` needs any valid token, and the
rest of `/actuator` needs an admin token or the scrape token `DRISHTI_METRICS_TOKEN` ([OPERATIONS §8.1](../admin/OPERATIONS.md#81-what-to-expose)). Like `/api`,
they must be written plainly (`/actuator;x/prometheus` or `/actuator/%70rometheus` → `400 DRS-5001`). Still keep
them on your monitoring network in production.

| Path | What |
|---|---|
| `/api/docs` | the OpenAPI 3 document (JSON) for every endpoint |
| `/api/docs/ui` | Swagger UI (redirects to the UI page) |
| `/actuator/health` | `{"status":"UP","groups":["liveness","readiness"]}`; probes at `/actuator/health/liveness` and `/actuator/health/readiness` |
| `/actuator/info` | build information |
| `/actuator/metrics` · `/actuator/metrics/{name}` | Micrometer metrics |
| `/actuator/prometheus` | the same in Prometheus text format |

```bash
curl -s http://localhost:18480/actuator/prometheus | grep -E '^drishti_(view|live)'
```

```
drishti_live_frames_total 11680.0
drishti_live_latency_p99_milliseconds 0.0
drishti_live_streams 0.0
drishti_live_topics 0.0
drishti_view_seconds{quantile="0.5"} 0.0
drishti_view_seconds{quantile="0.99"} 0.0
drishti_view_seconds_count 129
drishti_view_seconds_sum 0.324467028
drishti_view_seconds_max 0.017122086
```

## The ViewModel in detail

The server returns **formatted text and a tone** for every value, never raw numbers to format, so every
client shows `−1,403,091` identically. The top level:

| Field | Meaning |
|---|---|
| `ref` | `{kind, id}` |
| `mnemonic` | the kind's command-line code (`TRD`) |
| `title` | `{pill, id, with}` — `with` is an optional related entity `{text, link}` |
| `strip` | the headline values: a list of `Cell` |
| `panels` | the panels: `{id, kind, title, code, key, area, inferred, explanation?, data, error?, empty}` |
| `keys` | function keys: `{key, label, action, panel?, link?}`; `action` is `panel` (jump to a panel), `link` (open another entity) or `denied` |
| `provenance` | `{layout, fingerprint, source, generation, fetchedAt, live, businessDate}` — which Sutra (or inference) laid it out, which source, which version of the data |
| `timings` | milliseconds for `fetch`, `layout`, `links`, `bind` and `total` |

A **Cell** is `{label?, text, tone?, link?, emphasis?, path?}`. `tone` is `pos`, `neg` or another named tone;
`path` (a JSON path such as `$.mtm`) is what live `frame` patches address. A panel whose binding failed carries
`error` and no `data`; the rest of the view is unaffected. `empty: true` means the document lacks what the panel
asks for.

`data` by panel kind, with real examples from `MX-20000001`:

| Panel `kind` | `data` shape |
|---|---|
| `kv`, `status`, `provenance` | `{"fields": [Cell]}` |
| `table`, `ladder` | `{"columns", "numeric", "rows": [{"cells", "highlight", "path"}], "total", "more"}` |
| `tabs` | `{"layout": "tabs" \| "columns", "tabs": [{"title", "fields": [Cell]}]}` |
| `line`, `area` | `{"x", "series": [{"label", "values", "tone"}], "mark", "markText", "limit", "limitLabel", "source"}` |
| `hbar` | `{"bars": [{"label", "value", "text", "tone"}]}` |
| `links` | `{"links": [{"label", "text", "link", "badge", "status": "resolved" \| "pending" \| "missing" \| "denied"}]}` |

```bash
curl -s $B/views/trade/MX-20000001 > t.json
jq -c '.panels[] | select(.id=="terms").data.fields[0:2]' t.json
jq -c '.panels[] | select(.id=="schedule").data | {columns, numeric, row: .rows[0], total}' t.json
jq -c '.panels[] | select(.id=="sensitivities").data.bars[0:2]' t.json
jq -c '.panels[] | select(.id=="refs").data.links[0:2]' t.json
```

```
[{"label":"Fixed rate","text":"4.0829%","path":"$.terms.fixedRate"},{"label":"Pay frequency","text":"Annual","path":"$.terms.payFrequency"}]
{"columns":["Pay date","Leg","Type","Rate","Amount","PV"],"numeric":[false,false,false,true,true,true],
 "row":{"cells":[{"label":"Pay date","text":"2025-09-29"},{"label":"Leg","text":"2"},{"label":"Type","text":"Float"},{"label":"Rate","text":"3.5377%"},{"label":"Amount","text":"−1,403,091","tone":"neg"},{"label":"PV","text":"0"}],"highlight":false,"path":"$.schedule[0]"},
 "total":{"cells":[{},{},{},{},{"text":"Total"},{"text":"+1,875,863","tone":"pos"}],"highlight":false,"path":null}}
[{"label":"3M","value":-5544.0,"text":"−5,544","tone":"neg"},{"label":"6M","value":-11089.0,"text":"−11,089","tone":"neg"}]
[{"label":"Counterparty","text":"CP-MERIDIAN-RE","link":{"kind":"counterparty","id":"CP-MERIDIAN-RE","mnemonic":"CPTY"},"badge":"A","status":"resolved"},
 {"label":"Netting set","text":"NS-MERIDIAN-RE-NY","link":{"kind":"netting-set","id":"NS-MERIDIAN-RE-NY","mnemonic":"NSET"},"badge":"PFE 46.0m","status":"resolved"}]
```

**Entitlements in a view.** If the caller may not open a linked kind, the link is still shown but disabled:
in a `links` panel the item has `status: "denied"` and `badge: "no access"`; a function key's label gets
` (no access)` and `action: "denied"`; the title's `with` loses its link. People learn the entity exists
without seeing it.

## Error code table

The complete list (from `ErrorCode` in `drishti-common`). The first digit groups them: 1 sources and data,
2 Sutras and expressions, 3 inference, 4 engine, 5 API, 6 identity, 7 collaboration. Codes are never reused.

| Code | HTTP | Name (`title`) | When you see it |
|---|---|---|---|
| DRS-1001 | 404 | entity not found | no source holds the entity; also a missing workspace, monitor or alert rule. A store that holds the date and does not list the entity is named (`DRS-1001 recent-files holds trade for 2026-09-29 and does not list trade/MX-30000006`): the stores behind it are not asked |
| DRS-1002 | 404 | no source for kind | no source serves the kind |
| DRS-1003 | 502 | source failed | the source answered with an error, could not be reached, or holds the data but cannot read it; `detail` names the connector (`DRS-1003 trading-store failed reading trade/MX-1`) and, when the connector says what to do, why (`…: trade 2026-09-30 cannot be read: the native Delta engine does not decompress BROTLI Parquet pages …`). The next connector is not asked: another store's data is never shown in place of a failing store's |
| DRS-1004 | 504 | source timeout | the source took longer than `drishti.sources.fetch-timeout` (default 2 s) |
| DRS-1005 | 422 | invalid json | a document (e.g. sample JSON pasted into Studio) is not valid JSON or not an object |
| DRS-1006 | 500 | plugin load failed | a connector plugin could not be loaded (see `/sources` → `failures`) |
| DRS-1007 | 400 | no time travel | `knownAt` asked of a dated store that keeps no earlier versions (only Delta Lake and Iceberg do); `detail` names the connector (`DRS-1007 recent-files keeps no earlier versions, so a read 'as known at' an instant cannot be answered from it …`). Today's data is never shown in its place; a structured search names it in `failed` and is `partial` |
| DRS-2001 | 422 | sutra parse | the Sutra text cannot be parsed |
| DRS-2002 | 422 | sutra invalid | the Sutra parsed but has problems (listed in `problems`) |
| DRS-2003 | 404 | sutra not found | no Sutra with that name and version |
| DRS-2005 | 404 | proposal not found | no Sutra proposal with that id |
| DRS-2006 | 409 | proposal conflict | the proposal was already decided, or the live Sutra changed after it was proposed |
| DRS-2007 | 403 | four eyes | the author may not approve their own proposal |
| DRS-2101 | 422 | el syntax | a Rachana-EL expression does not parse (alert rules, Sutra expressions, search conditions) |
| DRS-2102 | 422 | el eval | a Rachana-EL expression failed while evaluating |
| DRS-3001 | 500 | inference failed | reserved: the code exists, but nothing in this release raises it (a document with no Sutra is laid out by inference, which does not fail) |
| DRS-4001 | 400 | command unknown | the command line text cannot be read |
| DRS-4002 | 500 | view failed | building the view failed unexpectedly |
| DRS-4003 | 400 | bad business date | unreadable, in the future, or before the history window |
| DRS-4004 | 400 | bad search | a structured search cannot be read (`detail` says where), names a field the kind does not have, or has a `limit` outside 1 to 1000 |
| DRS-4006 | 404 | no such panel | `?panel=` of `/views/{kind}/{id}/explain` names no panel of the view |
| DRS-4007 | 404 | ask off | Ask about this page is switched off, globally or for the page's pack |
| DRS-4008 | 502 / 504 | ask failed | the model endpoint failed, is not configured, or took longer than `drishti.explain.ask.timeout` |
| DRS-4009 | 429 | ask rate limited | over `per-user-per-minute` or `per-user-per-day` questions |
| DRS-5001 | 400 | bad request | an invalid argument or body; a path not written plainly (`;`, a needless `%`-escape, a dot or empty segment); also "too many live streams on this server" |
| DRS-5002 | 403 | forbidden | the caller lacks the role, the pack is not active for them, or the feature is off |
| DRS-5005 | 413 | too large | builder samples over `drishti.builder.max-samples`, `max-file-mb`, `max-total-mb` or `max-depth`; any `/api/v1/builder/**` request body over `max-total-mb` (answered before the body is read: `detail` gives the size and the limit, the connection is not reset; `drishti.http.request-limits`, `server.tomcat.max-swallow-size`); designs over `drishti.builder.designs.*` (`detail` names the limit and the file) |
| DRS-5004 | 404 | cache not found | no cache by that name (cache purge) |
| DRS-5006 | 404 | design not found | no Build design with that id, or it belongs to someone else |
| DRS-5007 | 409 | stale revision | an edit built on an older `rev` of a design than the server holds, or an undo or redo with nothing to move to |
| DRS-5010 | 401 | unauthenticated | missing, bad or expired bearer token |
| DRS-6001 | 404 | user not found | no such user |
| DRS-6002 | 409 | user exists | a user with that name already exists |
| DRS-6003 | 422 | weak password | the password does not meet the password rules |
| DRS-6004 | 401 | bad credentials | wrong user name or password, or single sign-on refused |
| DRS-6005 | 423 | account locked | too many failed sign-ins; locked for a while |
| DRS-6006 | 409 | last admin | the change would leave no enabled administrator |
| DRS-6007 | 422 | invalid user | a user record is invalid (for example an unknown pack) |
| DRS-6008 | 404 | role not found | no such role |
| DRS-6009 | 409 | role in use | the role is held by a user; take it away from them first |
| DRS-6010 | 403 | password change due | (console) the user must choose a new password on My account before anything else |
| DRS-7001 | 404 | share not found | no share with that id, or you are not its sender, a recipient it reached, or `compliance` (never a 403: the share's existence is not revealed) |
| DRS-7002 | 422 | bad recipients | no recipient, an unknown user (or one outside your packs), a role that may not be addressed, or over `share.max-recipients`, `share.max-expanded` or `max-group-size` |
| DRS-7003 | 429 | collab rate limited | over `limits.shares-per-minute`, `shares-per-day` or `directory-per-minute`; `Retry-After` says when to try again |
| DRS-7004 | 403 | sharing off | collaboration (`drishti.collab.enabled`), or sharing for the kind's pack (`packs.<pack>.share-enabled`), is switched off |
| DRS-7005 | 404 | thread not found | no thread, or not visible to you (the thread follows the right to open its entity, and a panel's gate kind) |
| DRS-7006 | 404 | comment not found | no comment, or not visible to you |
| DRS-7007 | 409 | thread locked | a comment on a locked thread |
| DRS-7008 | 403 | not editable | not the author, or the edit window (`threads.edit-window`) has passed, or the comment is retracted or hidden |
| DRS-7009 | 409 | stale comment | an edit built on an older revision |
| DRS-7010 | 423 | on hold | `DELETE /admin/collab/threads/{id}` for a thread a legal hold covers (retention skips held items without an error) |
| DRS-7011 | 422 | text refused | an empty or too long note or comment, a `text.deny-patterns` match, a masked value with `text.on-masked-copy: reject`, or a bad pin (a generation newer than the server holds) |
| DRS-7012 | 503 | mail unavailable | email was asked for explicitly while it is off, or SMTP failed at once |
| DRS-7013 | 503 | bridge unavailable | a bridge test while bridges are off, the bridge cannot post, or the endpoint refused |
| DRS-7014 | 404 | no such bridge | the bridge name is not in `drishti.collab.bridges.webhooks` |
| DRS-7015 | 403 | picture refused | a picture was asked for where `drishti.collab.snapshots.enabled` (or the kind's pack) is off, or no recipient may open the view |
| DRS-7016 | 503 | picture failed | the picture took longer than `snapshots.timeout`, is larger than `snapshots.max-bytes`, or could not be drawn |

`DRS-5003` (503, "backend unreachable") is raised by the console, never by the server, so it is not in this table. Sutra load problems listed by `/sutras/problems` and in `problems` use their own finer `DRS-2xxx` codes
(for example `DRS-2004` for a `.sutra.md` or plain `.yaml` file in a Sutra folder, `DRS-2009` for a missing or
unknown `rachana:` version); see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#problem-codes).

## Scripting recipes

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

## Personal API tokens

A person makes tokens on **My account → API tokens** for scripts, notebooks and spreadsheets
([CLIENTS.md](CLIENTS.md)). Send one as `Authorization: Bearer drk_<id>_<secret>`. It acts as its owner (their roles
and packs at the time of each call), only while the owner is enabled. A token made without scopes (`read`) only reads: any
other method answers `403 DRS-5002 API tokens only read`, except the `POST`s that only read (pivot of a search, `/command`: the
allow-list `drishti.security.token-read-posts`). An unknown, wrong, revoked or expired token answers `401`.

**Write scopes.** A token may also be made with scopes (`drishti.security.token-scopes`); each opens a set of `METHOD path`
patterns, and the server decides in one filter (`TokenScopes`):

| Scope | Opens | The user must also hold |
|---|---|---|
| `read` | `GET`, `HEAD` and the read-only `POST`s (the default; what tokens always had) | - |
| `design:write` | `POST/PUT/PATCH/DELETE /builder/**` (Designs, shape, suggest, edit, check, propose, share, bind, import), `POST /studio/**`, `POST /sutras`, `POST /sutras/proposals/{id}/withdraw` | `author` |
| `design:approve` | `POST /sutras/proposals/{id}/approve` and `/reject` | `approve` or admin (not your own proposal, four-eyes) |
| `packs:admin` | `POST /admin/packs/{name}/load` and `/unload`, `PUT /admin/packs/{name}`, `POST /admin/registry/**` | `admin` |

There is no `admin` scope and no `data:admin`: the server has no data-loading endpoint (data is loaded by `drishti.py data`
or the pack's sources), and users, roles, tokens, sign-in, caches, the audit log, shares, comments, notes, workspaces and
every `/me/**` write are **never** open to a token (`drishti.security.token-never`). A write that no scope opens answers
`403 DRS-5002 this token lacks the scope design:write` (or `API tokens only read`); one that is never open, `API tokens may not
call this endpoint`. The effective right is the scope **and** the user's roles at the time of the call, so a token never exceeds
its user; a disabled or deleted user's token answers `401`. A token with a write scope must expire (`token-write-max-days`,
90 by default). Tokens are bearer credentials, so CSRF does not apply to them. Every write is audited as `token-write`
(`token <id> POST /path -> 201`, the id and never the secret) and every refusal as `token-denied`; tokens made before scopes
existed keep reading only.

| Method | Path | Does |
|---|---|---|
| `GET` | `/me/tokens` | your tokens: id, name, created, expires, last used, revoked, active (never the secret) |
| `GET` | `/me/tokens/scopes` | the write scopes on offer, in words, and `writeMaxDays` |
| `POST` | `/me/tokens` `{name, days, scopes}` | makes one; `201` with `{token, secret}`: the secret appears only here. `days` 1–366 or null; `scopes` blank or `["read"]` for read-only, else names from `/me/tokens/scopes`, which require `days` ≤ `writeMaxDays`; at most 20 active per person; `403 DRS-5002` for a disabled account (a token cannot make tokens) |
| `DELETE` | `/me/tokens/{id}` | revokes yours |
| `GET` | `/admin/tokens` | (admin) everyone's |
| `DELETE` | `/admin/tokens/{id}` | (admin) revokes anyone's |

## A field over time

`GET /api/v1/history/{kind}/{id}/series?path=$.mtm&days=30[&to=2026-09-30]`: the field (any Rachana-EL expression
over the document) on each of the last `days` business days (2–260) up to `to` (default: the request's business
date), oldest first: `{ref, path, label, dated, points: [{date, value, dataDate, source}]}`. `dataDate` earlier than
`date` means the source carried an older day; `dated: false` means no dated source holds the entity (one value).
A path that is not an expression answers `400 DRS-5001`.

## How fresh a view is

Every view's `provenance` has `updatedAt` (when its source last received new data, or null when the source cannot
tell), `staleAfter` (the source's `stale-after` as an ISO-8601 duration, or null) and `stale` (true when nothing new
arrived within `staleAfter`; always false on a picked business date). Admin health carries the same per connector as
`lastUpdate`, `staleAfter` and `stale`.

## Notes (deprecated)

**Deprecated.** Notes are now comment threads ([below](#comment-threads)); `/notes` stays for one release as a facade with the old shape and
numbers. Existing notes were imported once as single-comment threads (on the whole view, or on the field of `path`; no business date,
generation 0). Differences: the text is scrubbed for readers without `raw`, an edit stops after `threads.edit-window` (`403 DRS-7008`),
and a `DELETE` retracts (the author) or hides (an administrator) instead of erasing: the text stays in the thread's revisions. Writes need
`collaborate` and `drishti.collab.enabled`. Only notes made here or imported are listed; notes made through `/threads` are not.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/notes/{kind}/{id}` | the entity's notes, oldest first: `[{id, kind, entityId, path, author, body, createdAt, updatedAt}]` |
| `POST` | `/notes/{kind}/{id}` | adds one: `{"body": "…", "path": "$.mtm"}` (`path` optional; at most 2000 characters, 200 notes per entity); `201` |
| `PUT` | `/notes/{noteId}` | edits the text: the author only (`403` otherwise) |
| `DELETE` | `/notes/{noteId}` | the author or an administrator; `204` |

Reading and adding need the right to open the kind (`403 DRS-5002` otherwise). Personal API tokens only read.

## Share with a note, the directory and the inbox

Design: [COLLABORATION.md](../architecture/COLLABORATION.md). What travels is the sender's words and a pinned link, never data;
what a person receives is computed for that person, when they look, from what they may see now. Everything below needs the
`collaborate` power (every role has it unless an administrator took it away) and `drishti.collab.enabled` (otherwise
`403 DRS-7004`). Personal API tokens read only: they cannot send.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/collab` | what the console needs to draw the dialog: `{enabled, store, email, maxText, maxRecipients, undeliverable, onMaskedCopy, postToThread, minQuery, collaborate, compliance}`. Answers even when collaboration is off (`enabled: false`) |
| `GET` | `/directory?q=ra&limit=10&kind=trade` | the people picker: `[{type: "user", name, displayName, desk, email: true}, {type: "role", name, size}]`. `q` needs `directory.min-query` characters (`400` otherwise). Only enabled users; with `directory.scope: shared-packs` (default) only users who share at least one assigned pack with you, and roles by the number of those people they hold (`admin` and `service` are never offered). `email` says whether an address exists, never the address. With `kind` and `share.undeliverable: tell`, each user also has `reach: true/false`: may they open that kind. Limited to `limits.directory-per-minute` |
| `POST` | `/shares` | sends a share. Body `{"kind": "trade", "id": "MX-20000001", "panel": "cashflows", "gateKind": "trade", "generation": 1674, "note": "…", "to": {"users": ["ravi"], "roles": ["risk"]}, "channels": {"inApp": true, "email": false}, "live": false, "postToThread": false, "picture": false}` (`picture: true` adds a watermarked PNG, see below; `postToThread` left out: the server's `share.post-to-thread` default applies); the as-of headers (or `asOf`/`knownAt`) say which date the page showed. `201 {id, link, pin, delivered, skipped: [{name, reason}], warnings: []}`. See below |
| `POST` | `/shares/preview-picture` | the body of `POST /shares`; answers the PNG that share would carry for the recipients it would reach (nothing is sent or stored). `403 DRS-7015` when pictures are off for the kind or no recipient may open it; `503 DRS-7016` when it cannot be drawn |
| `GET` | `/shares/{id}/picture` | the share's watermarked PNG, for its sender, a recipient it reached or `compliance`, `Cache-Control: no-store`; `404 DRS-7001` for anyone else and for a share without a picture. `GET /collab?kind=` answers `snapshots: true` when a picture may be offered for that kind |
| `GET` | `/shares/{id}` | opens a share, for its sender, a recipient it reached, or a role with `compliance`; anyone else `404 DRS-7001` (the same as no such share). See below |
| `POST` | `/shares/{id}/replies` | a reply from the sender or a recipient it reached: `{"note": "…"}`; `201` with the comment. The other party is told (inbox `reply`). Anyone else, `404 DRS-7001`; you must still be able to open the shared view. Same text rules as a comment; replies are private to the share's parties (they appear in `GET /shares/{id}` as `replies`, never in the entity's discussion) |
| `GET` | `/me/shares?box=received\|sent&limit=50&before=` | your shares, newest first: `[{id, createdAt, sender, senderName, kind, entityId, panel, pin, excerpt, access, recipients}]` (`recipients` for the sent box; `entityId`, `pin` and `excerpt` are left out when you may no longer open the kind) |
| `GET` | `/me/inbox?type=&unread=false&limit=50&before=` | your notices, newest first, rendered now for your rights: `[{seq, at, type, actor, actorName, kind, id, panel, shareId, threadId, commentId, read, access, title, excerpt}]`. When you can no longer open the kind, `access` is `false`, `id`, `panel` and `excerpt` are `null` and `title` reads `(no access) Ann shared a trade view` |
| `GET` | `/me/inbox/count` | `{unread}`, for the bell at page load |
| `POST` | `/me/inbox/read` | `{"seqs": [12, 13]}` or `{"upTo": 40}`; answers `{changed, unread}` |
| `GET` | `/me/alerts/stream` | the one live stream (SSE): beside `alert` it carries `notice`, one inbox row as `GET /me/inbox` renders it, the moment it is written (from another server within `inbox.poll`) |

**Sending.** The sender must open the kind (and a shared panel's `gateKind`: `403 DRS-5002` otherwise). `to.users` must be in your
directory scope and `to.roles` mentionable (`DRS-7002` otherwise: an unknown user and one outside your packs look the same); at
most `share.max-recipients` names and `share.max-expanded` people once roles are expanded. Each person is checked at delivery:
their roles open the kind **and** the kind's pack is assigned to them (a pack they switched off still delivers, and the page offers
to switch it on). `delivered` counts those notified; `skipped` says who was not and why (`may not open trade views`,
`does not have the pack for trade views`), by name only for people in your directory scope (others are one line per role: `some
members may not open trade views`); under `undeliverable: silent` it is empty. The note is plain text, 1 to `share.max-text`
characters, and refused (`422 DRS-7011`) when it matches `text.deny-patterns`, or contains the value of a masked field and
`text.on-masked-copy` is `reject` (for an author who holds `raw`: an author without it gets the same answer whether the text copies a hidden value or not, and reads their own text as written); otherwise the value's positions are recorded and every reader without `raw` sees `•••` there,
and the sender gets a `warnings` entry. The **pin** `{businessDate, live, knownAt, generation, source}` is the page's: the date from
the as-of headers, the generation you send (at most what the server holds now, else `422 DRS-7011`; `live: true` sends a live
link). The share, its recipients, the inbox rows and the access-log `share` row are written in one transaction. More than
`limits.shares-per-minute` or `limits.shares-per-day` is `429 DRS-7003` with `Retry-After`. Asking for `channels.email` while email
is off is `503 DRS-7012`.

**Opening.** For a recipient who may open the shared view, `GET /shares/{id}` answers `{id, access: true, role, sender, senderName,
createdAt, kind, entityId, panel, pin, note}` (the note rendered for them: masked spans as `•••` unless their role has `raw`) and sets
their first-open time; the sender and `compliance` also get `recipients: [{name, addressed, state, openedAt}]`. For a recipient who
may not open it (their roles stopped opening the kind, or the pack is off) it answers `{id, access: false, reason: "no-access" |
"no-pack" | "pack-off", pack, sender, senderName, createdAt, kind}` and nothing about the entity: no id, no title, no note, no pin.
The link opens the view with `asOf` and `knownAt` taken from `pin` as request parameters (never the recipient's saved date) and the
header `X-Drishti-Share: sh_…`, which the access log records as `share:sh_…` on the `view` row.

## Comment threads

Design: [COLLABORATION.md](../architecture/COLLABORATION.md). A thread is a conversation about an entity: on the whole view
(`anchor: entity`), on a panel (`panel`, with the `gateKind` of the panel's source) or on a field (`field`, with a `path` such as
`$.mtm`). **Who sees a thread is who may open the entity**: its kind and its pack, and for a panel the gate kind too (otherwise the
thread is not listed, not counted and `404 DRS-7005`). Every comment is stored as written, with a **pin** (business date, "known at",
generation, source) and the ranges that copy a masked field's value; each reader gets the text computed for them. Reads take the
as-of headers; writes need `collaborate` and `drishti.collab.enabled` (`403 DRS-7004`); personal API tokens read only.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/threads/{kind}/{id}?anchor=&panel=&path=&state=&limit=&before=` | the threads you may see, most recent activity first, each with its first page of comments (`threads.page-size`): `[{id, anchor, panel, path, label, state, createdBy, createdByName, comments, following, muted, items: [comment], more}]`. `before` is the last thread id of the previous page |
| `GET` | `/threads/{kind}/{id}/counts` | `{entity: n, panels: {panelId: n}, fields: {path: n}}` for the badges, from the threads you may see |
| `POST` | `/threads/{kind}/{id}` | a new thread with its first comment: `{"anchor": "panel", "panel": "cashflows", "gateKind": "trade", "path": "$.mtm", "label": "Cashflows", "generation": 1674, "body": "…"}`; `201 {threadId, comment, notified, skipped: [{name, reason}], warnings: []}` |
| `POST` | `/threads/{tid}/comments` | a reply: `{"generation": 1674, "body": "…"}`; `201` as above; `409 DRS-7007` when the thread is locked |
| `PATCH` | `/comments/{cid}` | edit: `{"body": "…", "revision": 1}`. The author only, within `threads.edit-window` (`403 DRS-7008`), on the current revision (`409 DRS-7009`) |
| `POST` | `/comments/{cid}/retract` | the author withdraws a comment at any time: readers see "retracted", the text stays in the record |
| `GET` | `/comments/{cid}/revisions` | the history `[{revision, at, actor, action, body, reason}]`; `action` is `created`, `edited`, `retracted`, `hidden` or `unhidden`. Bodies are given for a live comment (scrubbed for you) and to administrators and `compliance`, never for a retracted or hidden one |
| `POST` | `/threads/{tid}/state` | `{"state": "open" \| "resolved" \| "locked"}`: the thread's participants and administrators resolve and reopen; `locked` and unlocking are for administrators |
| `PUT` · `DELETE` | `/threads/{tid}/follow` | follow (`{"muted": false}`; `true` keeps the thread but stops reply notices) or stop. Commenting follows automatically |
| `GET` | `/me/mentions?limit=&before=` | live comments that mention you or one of your roles, where you may open the entity: `[{commentId, threadId, kind, entityId, panel, author, authorName, at, excerpt}]` |
| `POST` | `/admin/collab/comments/{cid}/hide` · `/unhide` | administrators only: `{"reason": "client name"}` (required, at most 400 characters); readers see "Hidden by a moderator: client name" and no text |

A **comment** as you read it: `{id, threadId, author, authorName, createdAt, editedAt, revision, pin, state, stateReason, body, parts,
edited, mine, editable}`. `state` is `live`, `retracted` or `hidden`; `body` and `parts` are left out for a reader who may not see the
text. `parts` is the text as structured pieces so a console never builds markup: `{t: "text", v}`, `{t: "mention", v: "@ravi", target:
"user:ravi"}` and `{t: "quote", v: "•••", path: "$.mtm"}`.

**Text.** Plain text, 1 to `threads.max-text` characters, no control characters; refused with `422 DRS-7011` when it matches
`text.deny-patterns`, when `generation` is newer than the server holds, or (with `text.on-masked-copy: reject`) when it copies a masked
value. Otherwise a masked value typed into the text is recorded as a range and every reader without `raw` sees `•••`, and the author
gets a `warnings` entry. To cite a figure safely write a **quote**, `{$.mtm}`: each reader sees the value from *their own* view of the
document at the comment's pin (`•••` when their view masks it, `—` when the path is gone). Values known only from elsewhere are not
detectable: use `text.deny-patterns`.

**Mentions.** `@ravi` is a user in your directory scope; `@risk` is a mentionable role (the roles are the groups). A name that is
neither stays plain text. A mention notifies a person **only if they may reach the entity's kind** (their roles open it and its pack is
assigned): someone who cannot see the entity is told nothing, not even that they were mentioned. Under `share.undeliverable: tell` the
response's `skipped` lists who was not told and why; under `silent` it is empty. Replies notify the thread's followers who are not the
author, not already mentioned and not muted. Each notice is an inbox row (`mention` or `reply`) rendered when read, for the reader's
rights now. More than `limits.comments-per-minute` is `429 DRS-7003`.

**The record.** Every action (`created`, `edited`, `retracted`, `hidden`, `unhidden`) is an immutable revision chained with SHA-256 per
thread; nothing is erased. Each is also in the audit log (`collab.comment.add`, `.edit`, `.retract`, `.hide`, `.unhide`,
`collab.thread.<state>`, `collab.share.reply`).

## Shared workspaces

| Method | Path | What it does |
|---|---|---|
| `PUT` | `/me/workspaces/{name}/share` | shares one of yours: `{"everyone": true}` or `{"roles": ["risk"], "users": ["ravi"]}` |
| `GET` | `/me/workspaces/{name}/share` | who it is shared with, or `404` |
| `DELETE` | `/me/workspaces/{name}/share` | stops sharing (deleting the workspace does too) |
| `GET` | `/workspaces/shared` | the workspaces shared with you: `[{owner, name, sharedAt}]` |
| `GET` | `/workspaces/shared/{owner}/{name}` | one, as its owner keeps it now: `readOnly: true`, and panes on kinds you may not open as `{"ref": null, "hidden": true}` |

## Scheduled reports

| Method | Path | What it does |
|---|---|---|
| `GET` | `/me/reports` | your reports, each with `nextRun` and its last runs (`runs`: `at`, `trigger`, `status`, `rows`, `target` or `error`) |
| `PUT` | `/me/reports/{name}` | saves one: `{"query": "TRD where mtm < 0", "schedule": "business-days 18:30", "deliver": "folder" \| "webhook", "webhook": "https://…", "date": "today" \| "previous", "enabled": true}` |
| `POST` | `/me/reports/{name}/run` | runs it now; answers with the run |
| `DELETE` | `/me/reports/{name}` | deletes it |
| `GET` | `/admin/reports` | every report on the server (administrators) |
| `DELETE` | `/admin/reports/{owner}/{name}` | an administrator removes someone's report |

A schedule that does not parse, a query that does not parse, or a webhook outside `drishti.reports.webhooks` is
`400 DRS-5001` with the reason. CSV numbers are written in full (`199000000`, never `1.99E8`).

## The access log (administrators)

`GET /admin/access?user=&action=&kind=&id=&from=&to=&limit=` lists answered reads, newest first:
`[{at, user, action, kind, entityId, detail, businessDate}]`. `action` is `view`, `raw`, `history`, `search`, `export` or
`share`; `detail` is the search text or the history field; a `share` row (written at once, never dropped) says `sh_… to 8 (user:ravi, role:risk)`, and a `view` opened through a share's link says `share:sh_…`; `from`/`to` take a date (`2026-09-30`) or an instant.
`limit` is 1-5000 (default 200). `GET /admin/access/stats` gives `written`, `dropped`, `queued` and `keepDays`.



## A phrase as a search

`GET /phrase?text=live trades over 5m, biggest first` answers `{query, kind, steps: [{words, meaning}], ignored,
problem}`: the structured search the words make (it does not run it; send `query` to `/search`), how each part was
read, the words not understood, and `problem` when the words name no kind. The vocabulary is the kinds the caller
may open and each kind's fields, learned from a sample of its documents (kept five minutes).
