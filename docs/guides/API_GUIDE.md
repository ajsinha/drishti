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
   - [Explaining a view](#explaining-a-view)
   - [Raw documents, history and impact](#raw-documents-history-and-impact)
   - [Structured search](#structured-search)
   - [Search by form (`/forms`)](#search-by-form-forms)
   - [The Pivot tab: panel rows, search pivots, saved pivots](#the-pivot-tab-panel-rows-search-pivots-saved-pivots)
   - [Live streams](#live-streams)
   - [Personal: settings, packs, workspaces, monitors, alerts](#personal-settings-packs-workspaces-monitors-alerts)
   - [Python in the browser (Calc) and test entities](#python-in-the-browser-calc-and-test-entities)
   - [Catalogue: about, packs, sources, Sutras](#catalogue-about-packs-sources-sutras)
   - [Sutra Studio and governance](#sutra-studio-and-governance)
   - [Using the shape API](#using-the-shape-api)
   - [Sign-in and user administration](#sign-in-and-user-administration)
   - [Deploying a pack archive and the pack's connectors](#deploying-a-pack-archive-and-the-packs-connectors)
   - [Packs from a signed registry](#packs-from-a-signed-registry)
   - [Connectors (`/api/v1/admin/connectors`)](#connectors-apiv1adminconnectors)
   - [Administration: Artifacts (`/api/v1/admin/artifacts`)](#administration-artifacts-apiv1adminartifacts)
   - [Data loads: telling Drishti a batch landed](#data-loads-telling-drishti-a-batch-landed)
   - [Administration: health and caches](#administration-health-and-caches)
   - [Drishti*net (`/api/v1/net`)](#drishtinet-apiv1net)
   - [Outside /api/v1: OpenAPI and actuator](#outside-apiv1-openapi-and-actuator)
7. [The ViewModel in detail](#the-viewmodel-in-detail)
8. [Error code table](#error-code-table)
9. [Business intelligence (Rupaka): `/api/v1/bi`](#business-intelligence-rupaka-apiv1bi)
10. [Datasets and the query engine: `/api/v1/bi` (phase 2)](#datasets-and-the-query-engine-apiv1bi-phase-2)
11. [Reports: `/api/v1/bi/reports` (phase 3)](#reports-apiv1bireports-phase-3)
12. [Boards: `/api/v1/bi/boards` (phase 5)](#boards-apiv1biboards-phase-5)
13. [Publishing and governance: `/api/v1/bi` (phase 4)](#publishing-and-governance-apiv1bi-phase-4)
14. [AI and MCP: `/api/v1/ai` and `/api/v1/admin/ai`](#ai-and-mcp-apiv1ai-and-apiv1adminai)
15. [Embedding views in another web application](#embedding-views-in-another-web-application)
16. [The console's own JSON routes](#the-consoles-own-json-routes)
17. [Scripting recipes](#scripting-recipes)
18. [Personal API tokens](#personal-api-tokens)
19. [A field over time](#a-field-over-time)
20. [How fresh a view is](#how-fresh-a-view-is)
21. [Notes (deprecated)](#notes-deprecated)
22. [Share with a note, the directory and the inbox](#share-with-a-note-the-directory-and-the-inbox)
23. [Comment threads](#comment-threads)
24. [Shared workspaces](#shared-workspaces)
25. [Scheduled reports](#scheduled-reports)
26. [The access log (administrators)](#the-access-log-administrators)
27. [A phrase as a search](#a-phrase-as-a-search)

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
  "version": "2.3.0",
  "java": "21.0.12.1 (Ubuntu)",
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

You should see the version string, for example `"2.3.0"`. The roles you put in the token decide what the
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
every `description` under `lifecycle.timeline`, and no other `description`. Names match in any case and convention
(`counterpartyId` masks `COUNTERPARTY_ID`, `trader_id` masks `TRADER_ID`), so a mask written before the 2.3.0 UPPER_SNAKE rename
keeps masking; an entry that names no field of the loaded packs' samples is logged at start-up (`DRS-6011`).

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
| `GET` | `/command/history` | your recent commands, newest first (the up arrow on the command line): a JSON array of strings; an embedded host never writes it |
| `GET` · `PUT` | `/command/aliases` | your aliases, short words for longer commands: `{"MYBOOK": "BOOK BOOK-RATES-1"}`. `PUT` replaces the whole set with the body; a word that is a mnemonic or a pack code is refused. Never open to a personal API token (`token-never`) |

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
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" $B/views/trade/1000001/explain | jq -c '{data, layout: (.layout | {label, sutra, candidates, noData, masked, noAccess})}'
# captured on the demo lakes (abridged):
# {"data":{"source":"trade-endur","generation":34,"businessDate":"2026-10-16","current":true,"live":false,"stale":false,"health":"up",
#          "linked":{"fetched":4,"pending":0,"denied":0,"budgetMs":40}},
#  "layout":{"label":"Sutra endur-comswap v1 + inference","sutra":{"name":"endur-comswap","version":1,"pack":"trading","priority":10,
#            "where":"$.SOURCE_SYSTEM == 'ENDUR' && $.PRODUCT_TYPE == 'ComSwap'",...},
#            "candidates":[{"name":"abs","where":"$.SOURCE_SYSTEM == 'CALYPSO' && $.PRODUCT_TYPE == 'ABS'","result":"false","chosen":false}, ...]}}
```

| Block | Fields |
|---|---|
| `ref`, `mnemonic`, `locale`, `generation`, `newer` | the page; `newer` is present (true) only when the server holds a newer generation than `?generation=` |
| `about` | the pack's words, rendered over the document the caller may see (a field masked for them reads `•••` in `text`): `pack` (`name`, `title`), `kindTitle`, `text`, `sutraDescription` (the Sutra's `description` as written, never evaluated) and `panels` (`id`, `title`, `description`). Authored in the pack's `config/about.yaml` ([pack guide](PACK_DEVELOPER_GUIDE.md#about-text-and-glossary)); absent when neither the pack nor the Sutra wrote anything |
| `data` | `source`, `generation`, `fetchedAt`, `businessDate`, `current`, `live`, `updatedAt`, `staleAfter`, `stale`, `health` (`up`, `degraded` or `down`, as the admin health page reduces it) and `linked` (`fetched`, `pending`, `denied`, `budgetMs`) |
| `layout.sutra` | the Sutra chosen: `name`, `version`, `priority`, `where` (its source text), `description` (the author's plain text) |
| `layout.candidates` | every Sutra of the kind in priority order, **the chosen one included** (`chosen: true`; a view that no Sutra matched has none and is inferred), each with its `where` and `result`: `true`, `false`, `error`, or `masked` (the answer depends on a field the caller may not see, so it is not told; the chosen Sutra's own `where` is already in `layout.sutra`) |
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

The explain answer also carries `glossary`: an entry for each field the page shows, `[{key, label, shownIn[], term, means, origin}]` (`origin` says where the words came from: `core:vocabulary.rate` is the built-in vocabulary, a pack's own entry names the pack). For authors, the same card for a design in the Build workbench is `GET` and `PUT /builder/designs/{id}/about` and `POST .../about/preview` ([Designs](#designs-your-work-kept-on-the-server)); the design is [CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md#api). The pack's own text problems (`DRS-2040` to `DRS-2044`) are load problems, listed by `GET /sutras/problems`; they never fail an explain call.

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
"failed":[{"source":"trade-endur","reason":"trade 2026-09-30 cannot be read: the native Delta engine does not decompress BROTLI Parquet pages (it reads Snappy, ZSTD, GZIP, LZ4, LZ4_RAW and uncompressed); rewrite the date with Snappy or ZSTD (tools/lake/maintain.py relayout --force --dates <date>)"}]
```

The reason is the connector's own when it says what to do (an unsupported codec, a day with unreadable lines);
otherwise only the kind of failure (`failed (SQLException; the server log and Admin → Health say more)`), since
driver and I/O messages can carry host names and paths; `did not answer within 3000 ms` for a timeout.
When nothing was read because no source holds the kind on the business date (a date not loaded yet, or before the
first), the answer adds `noData` with that date and the newest date a source holds, so a caller can offer it:

```json
"scanned":0,"matched":0,"partial":true,"failed":[],
"noData":{"businessDate":"2026-08-03","latestDate":"2026-10-16"}
```

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

### Search by form (`/forms`)

The same search as the console's `FORM <pack>` page. The server describes a kind's form and compiles criteria to a command of the search
grammar; the command is then run by `GET /search`, so masks, kinds per role, packs per user and sample-pack visibility apply as for any search.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/forms/{pack}?kind=` | The form of a pack's kind (the first kind you may open when `kind` is left out): `{pack, code, title, kinds[{kind, mnemonic, label, declared}], kind, mnemonic, form{kind, calendar?, inferred, fields[]}}`. A field is `{path, label, type, operators[], default?, order?, group?, help?, required?, hidden?, values?[], indexed}`. `indexed` is true when the kind's source keeps the field as a column; the others are read from the documents ("slower: not indexed"). A pack without a `form:` for the kind gets fields inferred from its search columns, the Sutra's match fields and strip fields (`inferred: true`). `400` when the pack is not switched on for you, `403 DRS-5002` when you may open none of its kinds |
| `POST` | `/forms/{pack}/{kind}/compile` | Body `{criteria[{path, op, value?, value2?, values?[], case?}], orderBy?, descending?, limit?}` answers `{command}`, for example `TRD where (mtm >= 1m and mtm <= 5m) and book = 'BOOK-FI-1' order by mtm desc limit 50`. A criterion with no value is left out. `400 DRS-4004` for a field not in the form, an operator its type lacks, or a value that cannot be read (the message says which); `400 DRS-4010` for a refused regular expression |

Operators by type: `text` equals, contains, starts, regex (with `case: true` to match case); `number` and `amount` `=`, `between`, `>`, `<`,
`>=`, `<=` (values may be `250k`, `1.5m`, `2bn`); `date` on, between, relative (`last 5 business days`, `next 3 business days`, `last 30 days`,
`this week`, `month to date`, ...on the pack's calendar), before, after; `enum` and `id` in (a list, or for ids a pasted list, with `*` as a wildcard);
`bool` is (`true` or `false`).

The grammar gained two functions for regular expressions, usable in any search: `matches(field, 'pattern')` (ignores case) and
`matchesCase(field, 'pattern')`, and the infix `field matches 'pattern'`. They run on a linear-time engine (a Thompson NFA, so no pattern
can take exponential time): classes, groups, alternation, `* + ? {n,m}`, `^`, `$`, a leading `(?i)`. Back-references, look-around, atomic
groups, possessive quantifiers and `\b` are refused with `DRS-4010`, as are patterns over 200 characters or whose expansion passes 4,000 steps;
a value is cut to 8,192 characters and a match that takes over 50 ms is `DRS-4011`. The caps are the system properties
`drishti.search.regex.max-pattern`, `.max-program`, `.max-input` and `.timeout-ms`. The form's other limits are
`drishti.search.form.max-values` (500, an `in` list), `.max-inferred-fields` (14), `.enum-max` (12), `.sample-rows` (200) and `.enum-scan-rows` (1,000: how many rows of a kind are read to find the values of a dropdown, up to the search's own limit; the first 200 of a kind can all be one product group, such as the rates trades, which left the other products out of the list).

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
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" $B/views/netting-set/NS-NORTHPLAINS-NY/panels/trades/records | jq -c '.rows |= .[0:2]'
```

```json
{"panel":"trades","fields":[{"name":"PRODUCT","label":"Product","fmt":null},{"name":"ASSET_CLASS","label":"Asset class","fmt":null},
  {"name":"CURRENCY","label":"Currency","fmt":null},{"name":"BOOK","label":"Book","fmt":null},{"name":"MATURITY_BUCKET","label":"Maturity bucket","fmt":null},
  {"name":"MATURITY","label":"Maturity date","fmt":"date"},{"name":"NOTIONAL","label":"Notional","fmt":"compact"},
  {"name":"MTM","label":"MTM (USD)","fmt":"signed0"},{"name":"TRADE_ID","label":"Trade","fmt":null}],
 "rows":[["ENGY-SWAP","Commodity","USD","BOOK-COMM-1","1-2Y","2028-03-31",11332443120.0,-150005504,"1003930"],
         ["ENGY-SWAP","Commodity","USD","BOOK-COMM-3","1-2Y","2027-10-31",3073970268.0,-138590967,"1000505"]],
 "total":698,"truncated":false,"limit":50000}
```

(the counterparty-risk pack's netting set of the demo lakes, 698 member trades). `kind` on a field, when the panel declares one, says which entity its values open.

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
| `GET` | `/admin/collab/threads/hidden?limit=&after=` | `admin` or `compliance`: the hidden comments, `{items: [{commentId, threadId, kind, entityId, author, createdAt, reason}], next}` (never the text), paged in the scan order of `/admin/collab/threads` |
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
{"assigned":["core","market-data","trading","market-risk", …],"active":["core","market-data","trading","market-risk", …]}
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
Suggestions come from the `alerts:` list in installed packs' `pack.yaml`; none of the seven shipped packs declares one, so the answer is `[]`
for every kind until your pack does. An entry has the shape of a rule, for instance (from the retired example pack `examples/packs/finance`):
`{"kind":"trade","name":"MTM below −450k","when":"$.mtm < -450000","severity":"warn","message":"${$.tradeId}: MTM ${fmt($.mtm, 'signed0')}"}`.

### Python in the browser (Calc) and test entities

[PYTHON_CALC.md](PYTHON_CALC.md). The Python runs in the user's browser; the server only says who may use it, keeps the user's snippets and serves whole columns.
Calc needs the `calc` power and `drishti.calc.enabled`.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/calc/settings` | `{enabled, allowed, maxColumnRows, maxSnippetChars}`: whether Calc is on and whether the caller may use it |
| `GET` | `/me/calc-snippets` | your saved snippets `[{name, description, kind, code, updatedAt}]` |
| `PUT` · `DELETE` | `/me/calc-snippets/{name}` | save `{code, description?, kind?}` (the name is 1 to 80 letters, digits, spaces and `. _ ( ) -`; `code` at most `calc.max-snippet-chars`) → the snippet; delete → `204`, `404 DRS-1001` when there is none |
| `GET` | `/search/columns/{kind}?paths=mtm,book&limit=` | as-of. Whole columns of a kind on the business date, as the source that keeps the fields as columns holds them, masked as a search is; without `paths`, the fields kept as columns. `400 DRS-5001` when no source of the kind keeps columns. Needs Calc and the kind |
| `GET` · `PUT` | `/me/studio-tests/{sutra}` | the entities you test a Sutra against in Studio, `[{kind, id}]` (at most 30; `PUT` replaces the list; `400 DRS-5001` for an entry without a kind or id) |

```bash
curl -s $B/calc/settings
```

```json
{"enabled":true,"allowed":true,"maxColumnRows":250000,"maxSnippetChars":50000}
```

### Catalogue: about, packs, sources, Sutras

| Method | Path | Notes |
|---|---|---|
| `GET` | `/about` | `{product, version, built, java, uptimeSeconds, sutras, sources, securityEnabled, packs, copyright}` |
| `GET` | `/packs` | every installed pack `{name, version, title, description, console, kinds, assigned, active}` (assigned/active for the caller) |
| `GET` | `/packs/{name}/overview` | as-of. One pack's start page: `{name, code, title, description, version, extends, kinds: [{kind, mnemonic, label, count, more, failed, example, columns}]}`, only the kinds the caller may open (`name` may be the pack code, `FIN`); `400` when the pack is not switched on for the caller |
| `GET` | `/packs/{name}/coverage` | as-of; `kind` (optional). Which Sutra lays out which data: for each kind with Sutras, the fields they match on and show panels by (`when:`), and every combination of them in the data (every connector serving the kind): `{pack, kinds: [{kind, fields, combinations, none, several, failed, rows: [{values, count, example, sutras, shown, state}]}]}`, `state` `one`, `none` (inferred) or `several`. Admin → Packs → a pack shows it as *Sutra coverage* |
| `GET` | `/sources` | `{sources: [{name, version, kinds, live, search, reverseLookup, health}], failures: {name: reason}}`; `kinds: []` means the source serves any kind |
| `GET` | `/sutras` | every loaded Sutra `{name, latest, versions, domain, kind, where, priority}` |
| `GET` | `/sutras/{name}/{version}` | one parsed Sutra as JSON `{name, version, domain, match, title, strip, panels, keys, location}`; `404 DRS-2003` |
| `GET` | `/sutras/{name}/{version}/source` | the Sutra file as written (`text/yaml`, the whole `.sutra.yaml` file with its comments); `404 DRS-2003` |
| `GET` | `/sutras/problems` | `{file: [{code, message, location}]}` for Sutras that failed to load, and for files in a Sutra folder that are not Sutras (`DRS-2004`); `{}` when all is well |
| `GET` | `/rachana/schema` | the JSON Schema (draft 2020-12, `application/schema+json`) of a Sutra in Rachana language 1, generated from the grammar, with this server's entity kinds and formats filled in and the Rachana-EL functions under `x-rachana-functions`; for editor completion and checking |
| `GET` | `/rachana/blocks`, `/rachana/blocks/{name}/{version}` | the shared panel blocks loaded (`<name>.v<N>.block.yaml`): name, version, description, params, file; one block adds its `text` (404 `DRS-2003` when unknown) |

```bash
curl -s $B/packs | jq -c '.[0:2][] | {name, version, title, kinds: .kinds[0:3], assigned, active}'
```

```
{"name":"core","version":"1.0.0","title":"Banking core","kinds":["counterparty","counterparty-group","issuer"],"assigned":true,"active":true}
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
number of entity kinds the enabled packs serve, the twenty-two panel kinds (`kv`, `table`, … `metric`) and the first
functions (`abs`, `coalesce`, …). Any editor that reads JSON Schema can use it: in VS Code with the YAML
extension, save it next to your Sutras and add `# yaml-language-server: $schema=rachana.schema.json` as the first
line of a Sutra to get completion and checking as you type. Sutra Studio uses the same schema.

### Sutra Studio and governance

| Method | Path | Notes |
|---|---|---|
| `GET` | `/studio/settings` | `{save, review, approve, author}`: may the caller save, is review on, may the caller approve, does the caller's role have the author power (whether or not saving is switched on) |
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
field is for. It is open to every signed-in user (designing is not saving; saving, proposing and shipping a Design need the `author` role), reads and writes nothing in Drishti, and keeps no copy of what you send.

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
| `addPanel` | `kind`, `id?`, `at?: {area, before \| after, span, height}`, `options?` | adds a panel of one of the 22 kinds; a kind's required options (such as `rows`) must be in `options`; without `at` it goes at the end of the main column; no `id` makes one from the kind |
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
| `GET` | `/builder/designs/{id}/blocks` | `{uses: [{item, at, line, ref, name, version, description, params, with, source, panels: [{id, kind, title, when}], problems}]}`: the shared blocks the design's Sutra uses and the panels each brings (`DRS-2048` problems when one does not resolve). `POST .../samples {pack: true}` adds the first record of the base Sutra's pack samples its match holds for, named `pack sample <id>` (404 when there is none) |
| `POST` | `/builder/designs/{id}/propose` | body `{note}` optional; author right and `studio-save`. Runs the check and proposes the design's Sutra **with evidence** (matrix, sample names, notes; `GET /sutras/proposals/{id}` returns it as `evidence`). `202 {proposal, status}`; with review off `200 {name, version, status}`. The design's `status` becomes `proposed(P-n)`, then `live(vN)` on approval |
| `GET` / `PUT` | `/builder/designs/{id}/about` | the design's About text (the pack's `config/about.yaml`) and its card over `?sample=` (`{rev, about, kind, card: {about, glossary, errors}, panels, problems, lint, coverage}`: `problems` are `DRS-2040`-`2044` with line and column, `lint` is `DRS-2045`-`2047`); `PUT {baseRev, text}` keeps the text as a step of the log (a new `rev`; `409 DRS-5007` when stale; counted with `max-notes-kb`) |
| `POST` | `/builder/designs/{id}/about/preview` | body `{text, sample?}`: the same answer for the text in the editor, nothing kept |
| `GET` / `PUT` | `/builder/designs/{id}/form` | the design's search form (the Build workbench's Form tab; the pack's `form:` section, [PACKS.md](PACKS.md)): `GET` answers the saved text with the field palette of the design's samples; `PUT` body `{baseRev, text}` keeps the text as a step of the design's log (`409 DRS-5007` on a stale revision; `422 DRS-2060` for a form that is not valid). A form change is proposed with the Sutra and travels with the proposal. Owner only |
| `POST` | `/builder/designs/{id}/form/preview` | body `{text}`: the same answer for the text in the editor, nothing kept |
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

Both are open to every signed-in user, write nothing, keep no copy and take the same limits as `/builder/shape`
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
| `GET` | `/admin/role-definitions` | admin | every role with what it allows: `{name, description, kinds[], raw, author, approve, admin, calc, layout, collaborate, compliance, bi[], builtIn, updatedAt, updatedBy, users}` |
| `GET` · `PUT` · `DELETE` | `/admin/role-definitions/{name}` | admin | read; create or replace a role you define (body: `description`, `kinds`, the powers above, and `bi`, a list of `bi`, `bi-author`, `bi-steward`, `bi-admin`: leave it out to keep the role's current ones; `422 DRS-9003` for an unknown power); delete one you defined (`204`; built-in roles come from configuration and packs and are read-only). Audited. [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md#2-roles) has the powers in words |
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
{"forceChangeOnCreate":false,"installedPacks":["core","market-data","trading", …],"defaultAdminPasswordInUse":true,"users":1}
```

`defaultAdminPasswordInUse: true` is a warning: change the seeded admin's password before production.

### Deploying a pack archive and the pack's connectors

Admin → Packs from a script ([how it works, with pictures](OPERATIONALISING.md#17-deploy-from-admin--packs-connectors-history-and-roll-back);
the CLI wraps these: `drishti.py server packs deploy|history|rollback|datasource get`). Every call needs an administrator, and a personal token the `packs:admin` scope.
Every change is audited (`pack-upload-checked`, `pack-deployed`, `pack-rolled-back`; connector changes are listed [below](#connectors-apiv1adminconnectors)). The settings of a pack's connectors are not in the archive: see [Connectors](#connectors-apiv1adminconnectors).

| Method | Path | Notes |
|---|---|---|
| `POST` | `/admin/packs/deploy` | The archive (`.tar.gz` from `pack bundle`, or `.zip`) **as the raw request body** (`Content-Type: application/octet-stream`). Optional headers: `X-Drishti-Filename`, `X-Drishti-Sha256` (the expected checksum), `X-Drishti-Signature` + `X-Drishti-Publisher` (Ed25519 over the archive bytes, base64, by a key in `drishti.packs.registry.trusted-keys`). Verifies and previews; **changes nothing**. `200` with `{ok, file, size, sha256, pack, version, checks[], preview, bi, uploadId, expiresAt}` (`bi`: the pack's `config/bi` content, `{items, kinds, counts, plan}` with a dry run of its import, [BI_GOVERNANCE.md](BI_GOVERNANCE.md#bi-content-in-a-pack-archive)): `ok` is false when any check failed (and then there is no `uploadId`). `413 DRS-5005` above `drishti.packs.deploy.max-archive-mb`; `400` for an empty body |
| `POST` | `/admin/packs/deploy/{uploadId}?acceptBreaking=false` | Deploys the verified upload: swaps the files into `drishti.packs.installed-dir` (keeping the replaced version), checks every loaded pack with it, then puts the new version to use at once, with no restart (`restarting` is `false`), putting the old files back if the pack cannot be used. The answer carries `bi: {items, loaded, importId, counts}` or `{loaded: false, problem}` when the pack held BI content (a refusal of that import does not undo the deploy; `acceptBreaking=true` also forces a dataset change that breaks others' reports, bi-admin). `409` when the preview has breaking changes and `acceptBreaking` is not `true`; `404` for an unknown or expired upload (it is used once) |
| `DELETE` | `/admin/packs/deploy/{uploadId}` | Throws a verified upload away: `{discarded}` |
| `GET` | `/admin/packs/samples` | Who sees sample packs: `{mode (visible, developers, hidden), configured, overridden, modes, samples[]}`. Admin. |
| `PUT` | `/admin/packs/samples` | `{"mode": "visible" \| "developers" \| "hidden" \| "default"}`: saves the mode (it overrides `drishti.packs.samples`; `default` removes the saved setting), applies it at once (`hidden` unloads the sample packs) and audits it (`pack-samples`). `400` for any other mode. |
| `GET` | `/admin/retry` | What is failing and will be tried again, by area: `{packs{name{attempt, nextRetryInSeconds, nextRetryAt, lastError, permanent, failingSince}}, connectors{...}, ai{...}, mcp{...}, policy}`. Admin. |
| `POST` | `/admin/retry/{area}` | **Retry now**: `area` is `packs`, `connectors` (optional `?name=` for one), `ai`, `mcp` or `all`. Tries at once and answers what was tried plus the new state; audited (`pack-retry-now`, `connector-retry-now`, `ai-retry-now`, `mcp-retry-now`). Admin; a token needs the `packs:admin` scope. `400 DRS-5001` for another area. |
| `POST` | `/admin/packs/{name}/load`, `/unload` | Loads or unloads a pack at once, with no restart: `{name, added, loaded[], reloaded[], unloaded[], problems{}, restartFor[], restarting: false, note}`. `restartFor` is always empty (kept for compatibility): roles, graph, search columns, formats, hints, About pages, routes and sample folders are swapped in live too. `400` with the reason when the pack does not check out, or when an unload is refused because a loaded pack extends it. |
| `GET` | `/admin/packs` | Admin. Every pack, loaded or only on disk: `{name, title, description, version, loaded, enabled, added, sample, problem, retry, connectors[], extends[], requiredBy[], kinds[], mnemonics[], updatedAt, updatedBy}` |
| `GET` | `/admin/packs/{name}/detail` | Admin. What a loaded pack brings, for Admin → Packs → the pack: `{name, code, routes, kinds: [{kind, mnemonic, label, declaredForm, formFields[], about, glossaryTerms}], sutras, connectors, about…}`. `404 DRS-1001` for a pack that is not loaded; it reads no data, so it works while the pack is off |
| `GET` | `/admin/packs/history?pack=&limit=` | Newest first: `history[]` of `{at, action (deploy, rollback, reverted), pack, version, previous, by, detail, sha256?}`, and `kept{pack: [{version, keptAt}]}` (the versions a rollback can go to; `shipped` when an installed copy hides the one that ships with the server) |
| `POST` | `/admin/packs/{name}/rollback?version=` | Goes back to a kept version (omitted: the newest kept; `shipped`: remove the installed copy). Same safe restart. `400` when there is no such version. `{restored, replaced, restarting, note}` |
| `GET` | `/admin/packs/{name}/datasource` | **Read only.** A loaded pack's connectors: `{pack, directory, connectors[], missing[]}`, each connector `{name, state, health, plugin, kinds (from this pack), origin (file, pack, application), defined, problems[]}`; `missing` names the connectors the pack uses that nothing defines. The old `PUT`, `DELETE` and `.../test` on this path are removed: use `/admin/connectors` |
| `POST` | `/admin/packs/deploy/{uploadId}/connectors` | The **Create connectors** step: writes `config/connectors/<name>.yaml` from the pack's templates for the connectors the site lacks (never overwrites an existing file); audited as `connector-generated`. The upload answer of `POST /admin/packs/deploy` carries `connectors: {names, missing, creatable}` so a script can see them first; deploying without this step is fine, the pack's templates are written at the next start anyway |

```bash
curl -s -X POST $B/admin/packs/deploy -H 'Content-Type: application/octet-stream' -H 'X-Drishti-Filename: my-bank-1.1.0.tar.gz' \
     --data-binary @dist/my-bank-1.1.0.tar.gz | jq -c '{ok, uploadId, checks: [.checks[] | {name, ok}], counts: .preview.counts}'
curl -s -X POST "$B/admin/packs/deploy/$ID?acceptBreaking=true" | jq -c '{deployed, previous, restarting}'
```

### Packs from a signed registry

Admin → Packs → Registry from a script ([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#a-signed-pack-registry-publishing-and-installing) publishes to one;
[ADR 018](../architecture/adr/018-signed-pack-registry.md) explains the signing). Administrators only; a personal token needs `packs:admin` for the `POST`s.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/admin/registry` | `{url, configured, packs: [{name, version, title, description, requires, publisher, trusted, size, installedVersion, loadedVersion}]}`, by pack name and newest version first. With no registry configured (`drishti.packs.registry.url` empty) it is `{"url": "", "configured": false, "packs": []}` |
| `POST` | `/admin/registry/{name}/{version}/install` | downloads the archive, checks the publisher's signature against `drishti.packs.registry.trusted-keys` and the sha256, installs it into `drishti.packs.installed-dir` and loads it with no restart; audited as `pack-installed` |
| `POST` | `/admin/registry/{name}/rollback` | goes back to the version installed before the current one |

### Connectors (`/api/v1/admin/connectors`)

A **connector** is a site resource: one YAML file in `config/connectors/` whose file name is the connector's name. A pack names the connectors it reads
through; it does not carry their settings ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) is the reference; the CLI wraps these calls:
`drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`). Every call needs an administrator, and a personal token the
`packs:admin` scope for the writes (the reads are administrator-only). From the console the same-origin and CSRF rules apply. Every change is audited, with setting
*names* and never values: `connector-saved`, `connector-deleted`, `connector-reset`, `connector-enabled`, `connector-disabled`, `connector-restored`,
`connector-tested`, `connector-generated` (a file written from a pack's template at start) and `connector-migrated` (an old data-source override moved into a file).

| Method | Path | Notes |
|---|---|---|
| `GET` | `/admin/connectors` | Every connector: `origin` (`file`, `pack`, `application`), `plugin`, `kinds`, `enabled`, `state`, `health`, `lastUpdate`, `updated`, `etag`, `problems`, `usedBy` (the packs and kinds); plus `directory`, `watch`, `misnamed`, `fileProblems`, `deprecated`, `knownKinds` |
| `GET` | `/admin/connectors/plugins` | The plugins and the settings each reads: `name`, `type`, `required`, `default`, `description`, `secret`, `group`; `tls` says whether the shared TLS section applies |
| `GET` | `/admin/connectors/{name}` | The effective settings (credentials masked), the file `text`, `etag` (also the `ETag` header), `usedBy`, `packDefault` (the pack's template), `history`. `404 DRS-5033` for an unknown name |
| `GET` | `/admin/connectors/{name}/suggestion` | What a loaded pack suggests for a connector of that name, to pre-fill **New connector**; `404 DRS-5033` when no pack does |
| `PUT` | `/admin/connectors/{name}` | Create or change. Body `{"text": "<yaml>"}` or the form's fields. An existing file needs `If-Match: <etag>`; `?confirm=true` after switching off a connector packs use. Validates, writes atomically (the old text goes to `<data dir>/connectors/history/`), applies to that one connector without a restart. `422 DRS-5031` when invalid; `409 DRS-5032` for a stale `If-Match`, an existing file without one, or an unconfirmed change to a connector in use |
| `DELETE` | `/admin/connectors/{name}` | Delete a file no pack's template suggests (`If-Match`; `?confirm=true` when packs use it). The text is kept in `<data dir>/connectors/history/`. `409 DRS-5032` for a connector a template names: reset or disable it |
| `POST` | `/admin/connectors/{name}/enabled` `{"enabled": false}` | Switch on or off, keeping the rest of the file (`?confirm=true` when packs use it) |
| `POST` | `/admin/connectors/{name}/reset` | Put the pack's template back as the file; your text goes to the history. A file generated from a template and never edited is regenerated by itself when the pack is redeployed with a changed template; an edited one is kept and its `templateChanged` field (also in `problems` and on Health) says so |
| `POST` | `/admin/connectors/{name}/test` | Try a draft (the same bodies as `PUT`) or, with no body, the saved file: a throwaway instance is started, asked what it holds per kind (business dates and counts), and closed. Nothing that runs is changed. Failures come back in words, with a hint for TLS and network errors |
| `POST` | `/admin/connectors/{name}/validate`, `/render`, `/parse` | The findings for a draft; the YAML of the form's fields; the form's fields of a YAML. Nothing is saved |
| `GET` | `/admin/connectors/{name}/history/{id}` | The text of a kept version |
| `POST` | `/admin/connectors/{name}/restore/{id}` | Put a kept version back (and apply it) |

Validation: the name rule (lower case letters, digits and hyphens, at most 64; `plugins` and `history` are reserved); the plugin exists; required settings are present; types
(`int`, `boolean`, enums); a credential setting is `${ENV_VAR}` or `file:/path` (never a literal, a default inside the reference or a password inside a URL). Unknown
settings and unknown kinds are **warnings**, not refusals. A stale version:

```
$ curl -i -X PUT $B/admin/connectors/site-quotes -H 'If-Match: "0000000000000000"' -d '{"text":"plugin: file\n..."}'
HTTP/1.1 409
{"type":"about:blank","title":"connector conflict","status":409,"detail":"DRS-5032 connector 'site-quotes' changed since you read it (now 3e4b312691e95f5c, you have 0000000000000000); reload it and apply your edit again","code":"DRS-5032"}
```

A kind routed to a connector that has no file answers `404 DRS-1011` and the pack still loads (Admin → Health shows `connectorsNotConfigured`):

```
HTTP/1.1 404
{"title":"connector not configured","status":404,"detail":"DRS-1011 connector desk-archive is not configured: kind 'quote-archive' is routed to it, but no connector file desk-archive.yaml exists (create it in Admin -> Connectors)","instance":"/api/v1/views/quote-archive/Q-1","code":"DRS-1011"}
```

### Administration: Artifacts (`/api/v1/admin/artifacts`)

Everything installed (packs, connector files, shipped BI files) in one list, what depends on each, and the safe ways out: disable, uninstall to an archive, restore,
delete for good, and a BI file upload with a dry run. Guide: [ARTIFACTS.md](../admin/ARTIFACTS.md). Every call needs an administrator, and **no personal token may call
any of it** (`* /api/v1/admin/artifacts/**` is in `drishti.security.token-never`). Every action and every refusal is audited (`artifact-disabled`, `artifact-enabled`,
`artifact-uninstalled`, `artifact-restored`, `artifact-purged`, `artifact-pruned`, `artifact-installed`, `artifact-refused`).

| Method | Path | Notes |
|---|---|---|
| `GET` | `/admin/artifacts` | `{artifacts[], uninstalledDir, keepDays}`; each `{kind (pack, connector, bi), subtype (BI: dataset, report, board, notebook, python), name, title, state (on, off, not loaded, problem), origin (shipped, archive, file, ui, template, application), version, modified, problems[], link}` |
| `GET` | `/admin/artifacts/impact?kind=&subtype=&name=` | What depends on it: `{total, blocked, summary, templated, templatePack, notes[], groups[{category, label, count, more, items[{name, owner, detail}]}]}`; the first 25 names per group, the counts complete. `404 DRS-5040` |
| `POST` | `/admin/artifacts/disable` `{kind, subtype, name, cascade}` | Switches it off and keeps it (a pack off for everyone, a connector `enabled: false`, a BI file hidden from the catalogue). `409 DRS-5041` with `impact` for a pack that enabled packs extend, unless `cascade` (they go off first) |
| `POST` | `/admin/artifacts/enable` `{kind, subtype, name}` | Switches it back on |
| `POST` | `/admin/artifacts/uninstall` `{kind, subtype, name, cascade, confirm}` | Moves it to `drishti.admin.uninstalled-dir`. `409 DRS-5041` (with `impact`) while anything depends on it; `cascade` takes the dependants along (extending packs uninstalled, connectors and BI files switched off) and needs `confirm` = the name (`422 DRS-5043`); `422 DRS-5044` for a connector a pack's template names, a connector with no file, or a pack named in `drishti.packs.enabled`. `{ok, impact, archive, also[], note}` |
| `GET` | `/admin/artifacts/uninstalled` | `{archives[{id, kind, subtype, name, area, rel, at, by, summary, bytes}], directory, keepDays}`, newest first |
| `POST` | `/admin/artifacts/uninstalled/{id}/restore` `{replace, confirm}` | Moves it back. `409 DRS-5042` when the name exists; `replace` archives the one in the way first and needs `confirm` = the name |
| `DELETE` | `/admin/artifacts/uninstalled/{id}?confirm=<name>` | Deletes the archive for good; `422 DRS-5043` without the name |
| `POST` | `/admin/artifacts/bi/check?file=<name>.<kind>.yaml` | The file as the raw body. The dry run: `{valid, name, subtype, problems[], warnings[], exists, replacesImpact}`; writes nothing |
| `POST` | `/admin/artifacts/bi?file=&replace=false&confirm=` | The same body: checks, archives what it replaces (`replace=true`, `confirm` = the name), places the file atomically in `config/bi`. `422 DRS-5044` invalid, `409 DRS-5042` name in use |

### Data loads: telling Drishti a batch landed

Your ETL loads the data; one call makes Drishti act on it (refresh, verify, evaluate alert rules, tell people) and expectations flag a batch
that did not come. Everything about it (every field, the idempotency rules, the steps, recipes for curl, Airflow, cron and DishtaYantra) is in
[DATA_LOADS.md](DATA_LOADS.md); the CLI is `drishti.py data landed` and `data loads`.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/packs/{pack}/loads` | body `{kind, businessDate (or asOf), status: ready\|failed, rows?, rejected?, source?, batchId?, note?}`; an administrator, or a token with the `loads:write` scope whose user's roles open the kind. `201` with the load record (`steps`, `verified`, `alerts`, `notices`, `summary`), or `200` with `duplicate: true` for the same pack, kind, date and batch announced with the same outcome again. `404 DRS-5011` unknown pack, `422 DRS-5012` unknown kind, `400 DRS-4003` bad or future date, `400 DRS-5001` bad status, counts or batch id |
| `GET` | `/packs/{pack}/loads?kind=&date=&status=&limit=` | the pack's history, newest first (administrators, or users whose roles open a kind of the pack: only those kinds) |
| `GET` | `/packs/{pack}/loads/{id}` | one load |
| `GET` | `/packs/{pack}/loads/expectations` | what the pack expects and its state: `pending`, `on-time`, `landed-late`, `late`, `missing`, `failed` |
| `GET` | `/admin/packs/{pack}/data` | **Load data** (Admin → Packs → a pack): the kinds the browser can load and where each goes: `{pack, maxMb, kinds: [{kind, connector, folder, key, date, snapshot}]}` (a kind the File connector does not serve carries `problem`). Admin |
| `POST` | `/admin/packs/{pack}/data?kind=&date=&file=` | body: the whole file, a JSON array or JSON Lines (`application/octet-stream`), streamed: one document in memory at a time, up to `drishti.loads.upload-max-mb` (2048). Writes each document as the File connector's row under `<root>/<domain>/<business date>/<kind>.jsonl` (the date field of `ingest.<kind>` in pack.yaml, or `date` / today for a `snapshot` kind; a day loaded again is replaced, atomically), checks every row against the pack's Sutras for the kind (types with no Sutra, fields no Sutra shows), then announces a load per day (refresh, verify, alerts). `{pack, kind, connector, rows, rejected, rejections, days: {date: rows}, files, checks: [{level, type, rows, message}], loads, bytes, millis}`. `400 DRS-5001` a kind the pack does not load here (no `ingest`, not a File connector), not JSON, `413 DRS-5005` over the limit, `409 DRS-5032` another load running. Admin; a token needs `packs:admin`; audited (`data-load-upload`) |
| `GET` `PUT` `DELETE` | `/admin/loads/{pack}/config` | the expectation settings in force; save or remove the administrator's override (administrators; never a token) |

Audited as `data-load-ready`, `data-load-failed`, `data-load-config-changed` and `data-load-config-reset`. `GET /admin/health` lists
`dataLate` and is `DEGRADED` while it is not empty.

### Administration: health and caches

| Method | Path | Notes |
|---|---|---|
| `GET` | `/admin/health` | admin. Everything Drishti depends on, in one answer, including `tmp` (`{base, path, bytes, files}`: the server's temp folder `drishti-<pid>`, [CONFIGURATION.md](../admin/CONFIGURATION.md#temporary-folders-drishtitmp-dir-and-the-consoles-tmp_dir)). Cheap enough to poll every few seconds |
| `GET` | `/admin/caches` | admin. Each cache: `{name, type, stats}` — `engine` (layouts and shape fingerprints) and every connector that caches |
| `GET` | `/admin/connectors/{name}/index` | admin. The connector's multi-day type-ahead index: `{enabled, reload: {running, trigger, total, done, current, startedAt, finishedAt, error}, auto: {enabled, everySeconds, lastCheck, nextCheck, lastChange, lastChangeWhat}, days: [{kind, date, ids, tier (heap, disk, none), bytes, builtAt, building, lastError}]}`; `enabled` is false when the connector keeps one day. `404 DRS-5033` for no such running connector |
| `POST` | `/admin/connectors/{name}/index/reload` | admin, console only (never a personal API token). Body (all optional) `{"kind": "trade", "date": "2026-10-05"}`: `date` is a business date in the window or `all`; no kind is every kind. Rebuilds those days from the source in the background (the old day answers until the new one swaps in) and answers the status plus `queued`. `409 DRS-5032` when a reload of this connector is already running, `400 DRS-5001` for a bad date, an unknown kind or a day outside the window. Audited as `index-reload` (and `index-reload-finished` with the result) |
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
 "server":{"version":"2.3.0","uptimeSeconds":6953,"java":"21.0.12.1","heapUsedMb":146,"heapMaxMb":15640,"threads":75,"cpus":24},
 "live":{"frames":11680,"p50Ms":0.886,"streams":0,"p99Ms":1.917,"topics":0,"droppedFrames":0}}
```

One source row and one pack row:

```bash
curl -s $B/admin/health | jq -c '.sources[0], .packs[0]'
```

```
{"name":"civic-store","version":"1.0","status":"UP","health":"UP","kinds":["bill","candidate","election", …],"live":false,"dated":true,"search":true,"reads":{"reads":0},"cache":{"tables":8,"partitions":8,"timeTravel":0}}
{"name":"core","title":"Banking core","version":"1.0.0","extends":[],"overrides":[],"kinds":12,"sutras":12,"sutraProblems":[],"connectors":["reference-store"],"connectorsDown":[],"connectorsOff":[],"status":"OK"}
```

A source's `reads` grows read counts, errors, p50/p99 and the last error once it has served traffic.
`connectorsOff` lists connectors a pack expects that are disabled or failed to start.
`connectorsNotConfigured` lists connectors a pack names that no file defines (the pack is then `DEGRADED` and its kinds answer `DRS-1011`). The top level carries a
`connectors` block: `{count, failed, badFiles, problems, watch, directory, deprecated}`; `watch` is `WATCHING`, `POLLING`, `OFF` or `STOPPED: <reason>`, and a bad connector file
in `badFiles` (the last good configuration keeps running) makes the overall status `DEGRADED`.

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

### Drishti*net (`/api/v1/net`)

The cluster's endpoints ([NET.md](../admin/NET.md), the design [DRISHTI_NET.md](../architecture/DRISHTI_NET.md) section 15). While the cluster is
off on a node they answer `404 DRS-5050` (`/members` answers `{"enabled": false, "members": []}`). Two kinds of caller:

- **Another node.** Every call between nodes carries `X-Drishti-Net-Node` (the caller's id), `X-Drishti-Net-Time` (epoch milliseconds) and
  `X-Drishti-Net-Signature`: an HMAC-SHA256, base64url, with the cluster key over `METHOD\npath?query\ntime\nnode\nsha256(body)`. A call
  without it, signed with another key or more than 60 s away from the node's clock is refused `401 DRS-5051`. The node-only endpoints below
  take nothing else.
- **An administrator** (a session token with the admin power; never a personal API token: `* /api/v1/net/**` is in `token-never`), for Admin → Net.

A request one node forwards to another for a person (an ordinary `/api/v1/**` request) is signed the same way and carries two more headers:
`X-Drishti-Net-Hop: 1` (the receiving node answers only from itself: one hop at most) and `X-Drishti-Net-On-Behalf-Of`, a token signed with the
cluster key that names the user, the roles, the embed application, the forwarding node (`origin`), the request id, the node it is for (`aud`) and
its expiry (30 s). The receiving node serves the request as that user, refuses a token for another node or an expired one (`401 DRS-5051`), and
refuses one whose roles may not reach it (`403 DRS-5054`). None of these headers ever reaches a browser.

A request a node's own client makes for a kind another node serves (its pack is not here, is older here, or is served to peers only here) is forwarded
whole, and the client gets that node's answer unchanged: the same status, body and headers (none of the cluster's). When the node that serves it cannot
be reached the answer is `502 DRS-5052` or `504 DRS-5053`, naming it. Admin → Health's answer (`GET /api/v1/admin/health`) has a `net` part:
`enabled`, `running`, `nodeId`, `members` by state, `drained`, `last10Minutes` (`forwarded`, `failed`).

| Method | Path | Who | Notes |
|---|---|---|---|
| `POST` | `/net/gossip` `{"members": [...]}` | node | Push-pull: the caller's member table is merged, this node's is the answer `{"members": [...]}`; the first entry is the node itself. Each member: `nodeId`, `url`, `incarnation`, `heartbeat`, `state` (`alive`, `suspect`, `dead`, `left`), `digestHash`, `version`, `protocol`, `tags`, `since`, `drained` |
| `GET` | `/net/ping` | node | A direct probe: `{"nodeId", "state"}`. `?target=<a member's url>`: an indirect probe, `{"answered": true}` when this node reached the target (only a member's address is probed: `400 DRS-5056` otherwise) |
| `GET` | `/net/digest` | node | This node's capability digest: `nodeId`, `url`, `version`, `protocol`, `packs` (`name`, `code`, `version`, `hash`, `peerOnly`), `kinds` (`kind`, `mnemonic`, `label`, `pack`, `renders`, `sources`: `connector`, `type`, `table`, `latestDate`, `dataVersion`, `columnar`, `dated`, `live`), `load` (`requestsPerSecond`, `latencyMs`), `calendar`, `tags`, `redact`, `access`, `reports`, `builtAt`. Peers pull it when the `digestHash` in gossip changes |
| `GET` | `/net/capabilities` | node, admin | The capability table as Admin → Net's map shows it: `kinds` (each with `renderers`: `nodeId`, `version`, `newest`, `peerOnly`, `routable`; `holders`: `nodeId`, `connector`, `type`, `table`, `shared` (a replica), `latestDate`, `dataVersion`; `scatter`: the peers a search asks), `nodes`, `warnings` (`what`: `pack-content`, `calendar`, `redact`, `protocol`; `text`) |
| `GET` | `/net/fetch/{kind}/{id}` (`~` and `?id=` for an id with a slash) | node, for a user | One entity document as the on-behalf-of user may see it here (masked by this node's rules), read from this node's own sources only, with the request's `X-Drishti-As-Of` and `X-Drishti-Known-At`: `{"kind", "id", "provenance": {source, generation, fetchedAt, live, businessDate}, "data"}`. `404` when not held here. The asking node renders it and masks it again |
| `GET` | `/net/explain?kind=&id=&q=&role=&dated=` | admin | Where a request would go from this node and why: `route` (`local`, `view`, `none`), `reason`, `forwardTo` (in order), `localPack`, `dataPeers` (where its document would be read from), `scatter` (the peers a search asks), `unreachable` (nodes the role may not reach), `down` |
| `GET` | `/net/traffic?minutes=60` | admin | What this node sent to its peers: `minutes` (per peer, a row a minute: `ok`, `not-held`, `refused`, `failed`, `timeout`, `avgMs`), `slowest`, `recent` (the latest calls), `totals` (the last ten minutes) |
| `POST` | `/net/console-event` `{"from", "to", "why", "console"}` | the console, admin | A console that moved from one server to another says so; recorded in the audit log as `net-console-failover` |
| `GET` | `/net/members` | node, admin, anyone signed in | The member table. A node or an administrator gets every member with what this node knows of it (`lastHeard`, `latencyMs`), this node (`node`: id, address, tags, peer-only packs, whether the key is set, drain, the gossip timings and rounds), the seeds, `peersFile` and the files' `problems`. Anyone else (the console's discovery) gets `{"enabled", "self", "members": [{nodeId, url, state, tags, packs}]}` |
| `POST` | `/net/seeds` `{"url": "host:port", "name"?}` | admin | Saves `peers/<name>.yaml` (default name from the host and port) and tries it at once: `{"file", "url", "answered"}` |
| `DELETE` | `/net/members/{id}` | admin | Removes a dead or left member; `400 DRS-5056` for an alive or suspect one |
| `POST` | `/net/drain`, `/net/undrain` | admin | This node takes no forwarded work (its peers are told), or takes it again |
| `POST` | `/net/leave`, `/net/rejoin` | admin | Gossips `left` and serves its peers nothing; back alive with a higher incarnation |
| `GET` | `/net/access` | admin | Nodes per role: `roles` (as access.yaml says), `byRole` (each role with its `nodes`, `limited`, and the members it `reaches` now), this node's `tags` and `peerOnlyPacks`, `choices` (the members' ids and tags), the two files' `etags` |
| `PUT` | `/net/access` `{"roles": {role: [ids or tags]}, "tags": [...], "peerOnlyPacks": [...]}` | admin | Writes access.yaml (whole) and node.yaml's two settings (the rest kept), checked first (`409 DRS-5055` and nothing written when one is refused), applied at once; answers as `GET`. Audited `net-access-saved` |
| `GET` | `/net/security` | admin | `keySet`, `previousKeySet`, `previousUses` (each peer that signed with the previous key, and when), `rollover` (in words), `tls` (mutual TLS: `enabled`, `certificates` with `subject`, `source`, `notAfter`, `daysLeft`, `expired`; `warnings`), `refusals` (`at`, `address`, `node`, `reason`). Never a key |
| `GET` | `/net/files` | admin | Every file of the folder: `id`, `exists`, `etag`, `bytes`, `refused`, `problems` (`file`, `level`, `field`, `line`, `message`); `dir`, `watch` |
| `GET` | `/net/file?id=node.yaml` | admin | One file: `text`, `etag`, `problems`, `history` (`id`, `at`, `bytes`) |
| `GET` | `/net/file/revision?id=&rev=` | admin | The text of a kept earlier version |
| `POST` | `/net/file/check?id=` `{"text"}` | admin | What the server would make of the text, without writing it: `{"ok", "problems"}` |
| `PUT` | `/net/file?id=` `{"text"}` | admin | Writes the file (atomically, the previous text kept in `<data dir>/net/history/`) and applies it. `If-Match: <etag>` (`""` for a new file) refuses a stale edit; a file with an error is not written: `409 DRS-5055` with `problems` |
| `DELETE` | `/net/file?id=` | admin | Removes the file (`If-Match`); its settings go back to their defaults |

Every management call is audited (`net-seed-added`, `net-member-removed`, `net-drain`, `net-undrain`, `net-leave`, `net-rejoin`, `net-file-saved`,
`net-file-deleted`) with the node it was made on. A refused file, from the API:

```text
$ curl -s -X PUT "$B/net/file?id=gossip.yaml" -H 'If-Match: 3a1f0c9e2b7d4c51' -d '{"text":"fanout: lots\n"}'
{"code":"DRS-5055","detail":"gossip.yaml was not written: gossip.yaml:1 fanout: 'lots' is not a whole number","problems":[{"file":"gossip.yaml","level":"error","field":"fanout","line":1,"message":"'lots' is not a whole number"}]}
```

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
| `/public/about` | no sign-in needed: `{product, version, notice, signIn: {required, password, singleSignOn}}`, what the console's sign-in page shows |
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
| DRS-1003 | 502 | source failed | the source answered with an error, could not be reached, or holds the data but cannot read it; `detail` names the connector (`DRS-1003 trade-murex failed reading trade/MX-20000001`) and, when the connector says what to do, why (`…: trade 2026-09-30 cannot be read: the native Delta engine does not decompress BROTLI Parquet pages …`). The next connector is not asked: another store's data is never shown in place of a failing store's |
| DRS-1004 | 504 | source timeout | the source took longer than `drishti.sources.fetch-timeout` (default 5 s) |
| DRS-1005 | 422 | invalid json | a document (e.g. sample JSON pasted into Studio) is not valid JSON or not an object |
| DRS-1006 | 500 | plugin load failed | a connector plugin could not be loaded (see `/sources` → `failures`) |
| DRS-1007 | 400 | no time travel | `knownAt` asked of a dated store that keeps no earlier versions (only Delta Lake and Iceberg do); `detail` names the connector (`DRS-1007 recent-files keeps no earlier versions, so a read 'as known at' an instant cannot be answered from it …`). Today's data is never shown in its place; a structured search names it in `failed` and is `partial` |
| DRS-1011 | 404 | connector not configured | a kind is routed to a connector that has no file in the connector folder; the pack loads, the kind does not; create it in Admin → Connectors or `drishti.py connector apply` |
| DRS-2001 | 422 | sutra parse | the Sutra text cannot be parsed |
| DRS-2002 | 422 | sutra invalid | the Sutra parsed but has problems (listed in `problems`) |
| DRS-2003 | 404 | sutra not found | no Sutra with that name and version |
| DRS-2005 | 404 | proposal not found | no Sutra proposal with that id |
| DRS-2006 | 409 | proposal conflict | the proposal was already decided, or the live Sutra changed after it was proposed |
| DRS-2007 | 403 | four eyes | the author may not approve their own proposal |
| DRS-2060 | 422 | form invalid | a search form that is not valid YAML or names a field path, type or operator wrongly; the Form tab lists each problem, `pack check` reports the pack's |
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
| DRS-4010 | 400 | regex refused | a regular expression the search will not run: over 200 characters, too large once expanded, back-references, look-around, atomic groups, possessive quantifiers or word boundaries (see [Regular expressions in search](#search-by-form-forms)) |
| DRS-4011 | 408 | regex timeout | a regular expression ran longer than `drishti.search.regex.timeout-ms` (50) on one value |
| DRS-4015 | 404 | AI off | the AI layer or the MCP client is switched off (`drishti.ai.enabled`, `drishti.mcp.enabled`); AI_PROVIDERS.md |
| DRS-4016 | 502 / 504 | AI call failed | the provider or MCP server failed (502) or did not answer in time (504); the message never carries a key or a prompt |
| DRS-4017 | 429 | AI rate limited | over a per-user rate or cost limit of the AI policy |
| DRS-4018 | 403 | AI policy | the AI policy refuses the call for this user, role or feature |
| DRS-4019 | 404 | AI not found | no such model, provider, MCP server or tool |
| DRS-4020 | 422 | AI answer unusable | BI with AI: the model's draft did not pass the report check (after one repair), or its answer is not a query; nothing was changed ([BI_AI.md](BI_AI.md)) |
| DRS-5001 | 400 | bad request | an invalid argument or body; a path not written plainly (`;`, a needless `%`-escape, a dot or empty segment); also "too many live streams on this server" |
| DRS-5002 | 403 | forbidden | the caller lacks the role, the pack is not active for them, or the feature is off |
| DRS-5005 | 413 | too large | builder samples over `drishti.builder.max-samples`, `max-file-mb`, `max-total-mb` or `max-depth`; any `/api/v1/builder/**` request body over `max-total-mb` (answered before the body is read: `detail` gives the size and the limit, the connection is not reset; `drishti.http.request-limits`, `server.tomcat.max-swallow-size`); designs over `drishti.builder.designs.*` (`detail` names the limit and the file) |
| DRS-5004 | 404 | cache not found | no cache by that name (cache purge) |
| DRS-5006 | 404 | design not found | no Build design with that id, or it belongs to someone else |
| DRS-5007 | 409 | stale revision | an edit built on an older `rev` of a design than the server holds, or an undo or redo with nothing to move to |
| DRS-5010 | 401 | unauthenticated | missing, bad or expired bearer token |
| DRS-5011 | 404 | load pack not found | a data-load call names a pack that is not loaded, or one none of whose kinds the caller may open |
| DRS-5012 | 422 | load kind unknown | a data-load call names a kind the pack does not own |
| DRS-5031 | 422 | connector invalid | a connector file or body is not valid: bad name, unknown plugin or top-level key, missing required setting, wrong type, a `name:` that is not the file name, or a credential that is not an `${ENV_VAR}` or `file:` reference; `detail` names the setting |
| DRS-5032 | 409 | connector conflict | the `If-Match` version is stale (someone changed it since you read it), a file of that name exists and no `If-Match` was sent, or the change switches off or deletes a connector packs use and `confirm=true` was not sent; also deleting a connector a pack's template names |
| DRS-5033 | 404 | no such connector | no connector of that name (a file, a pack template or the server's configuration) |
| DRS-5040 | 404 | artifact not found | Admin → Artifacts: no such installed pack, connector or BI file, or no such archive |
| DRS-5041 | 409 | artifact in use | an uninstall (or a pack's disable) was refused because other things depend on the artifact; the answer carries the `impact`. Disable it instead, or choose to take the dependants along |
| DRS-5042 | 409 | artifact conflict | a restore or an upload found an artifact of that name already in place; choose *replace* |
| DRS-5043 | 422 | name not typed | a destructive step (uninstall with dependants, replace, delete an archive for good) needs the artifact's name typed |
| DRS-5044 | 422 | artifact invalid | does not apply: a connector a pack's template names, a pack named in `drishti.packs.enabled`, a connector with no file, or an uploaded BI file that is not valid |
| DRS-5050 | 404 | net off | Drishti*net is off on this node, or the node left: the cluster's endpoints and its management are not served |
| DRS-5051 | 401 | net refused | a call between nodes without a valid cluster credential: no signature, a bad one, outside the 60 s window, or a bad on-behalf-of token |
| DRS-5052 | 502 | net peer failed | the node a request was forwarded to failed; the next one was tried once |
| DRS-5053 | 504 | net peer timeout | the node a request was forwarded to did not answer within `forward-timeout` |
| DRS-5054 | 403 | net not reachable | the caller's roles may not reach the node (`access.yaml`), or the node is drained (then `503`) |
| DRS-5055 | 409 | net file refused | a `config/net/` file changed since it was read (`If-Match`), or has an error (`problems`) |
| DRS-5056 | 400 | net bad request | an unknown member, removing one that is alive, a malformed seed, a file that is not one of the folder |
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
| DRS-6011 | 500 | mask matches nothing | (start-up warning in the log, never an answer) a `drishti.security.redact` entry names no field of the loaded packs' samples, so it masks nothing; Admin → Health lists it under `masksMatchingNothing`. Masks match in any case and convention (`counterpartyId` masks `COUNTERPARTY_ID`) |
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
| DRS-8001 | 401 | embed token refused | an embedded-view call with a missing, malformed, expired or wrong-audience embed token, or embedding is off (`drishti.embed.enabled`) |
| DRS-8002 | 403 | origin not allowed | the request's `Origin` is not one of the host application's registered origins (also: the token endpoint called from a browser) |
| DRS-8003 | 403 | host application refused | the host application is unknown or disabled on an embed call (`403`), or at the token endpoint (`401 invalid_client`) it is unknown or disabled or its secret or key is wrong; embedding off is `403` |
| DRS-8004 | 429 | embed rate limit | over the host application's or the user's call rate, or the application's token requests per minute; `Retry-After` says when |
| DRS-8005 | 400 / 403 | kind or report not allowed | the host application may not show this kind or this report (its `kinds` list: `trade`, `report:<id>`, `report:*`); the console answers 403 for a report that is not published |
| DRS-8006 | 410 | embed contract gone | the element's contract version is no longer served (raised by the console, not the server) |
| DRS-9001 | 404 | BI not found | Business intelligence: no such workspace, item or version, or the caller may not know it exists (workspaces they do not belong to and items outside their audience answer this, never 403) |
| DRS-9002 | 403 | BI not allowed | Business intelligence: the caller lacks the BI power (`bi`, `bi-author`, `bi-steward`, `bi-admin`) or the workspace role the action needs; the detail says which |
| DRS-9003 | 422 | BI invalid | Business intelligence: a name, kind, body (not YAML, not a mapping, over `drishti.bi.max-body-bytes`), tag, audience (unknown role, user or pack), endorsement or setting that is not valid; also an unknown power on a role |
| DRS-9004 | 409 | BI conflict | Business intelligence: the state forbids it: workspace id taken, nothing new to publish, already proposed, edit while proposed, personal workspace (private: cannot publish, be shared or deleted this way), last admin, workspace not empty, shipped content is read-only |
| DRS-9005 | 403 | BI four-eyes | Business intelligence: with `drishti.governance.four-eyes` on (and security on), the proposer may not approve or reject their own proposal |
| DRS-9006 | 403 | BI off | Business intelligence is switched off on this server (`drishti.bi.enabled`) |
| DRS-9007 | 409 | BI stale save | `PUT /api/v1/bi/items/{id}` named a `baseEtag` that is no longer the item's `etag`: someone saved since. Read the item again, or send the update without `baseEtag` to overwrite |
| DRS-9010 | 422 | Dataset invalid | The dataset YAML or one of its structures is not valid (the problem names the path of the key) |
| DRS-9011 | 422 | Dataset expression | A measure, calculated column or row rule does not parse, names something the model lacks, misuses an aggregate or refers to measures in a circle |
| DRS-9012 | 422 | Dataset reference | A relationship, hierarchy, masked column, row rule or rollup points at something that does not exist |
| DRS-9013 | 501 | Dataset not built | The document uses a feature the grammar reserves and this server does not build yet (a Python measure named in a query; time intelligence and the window functions are built) |
| DRS-9014 | 422 | Dataset composition | A composed dataset (`from:`, `join:`) names a source dataset, column, measure or join key that does not exist, joins keys of different types, or leaves a source unjoined ([DATASETS.md section 11](DATASETS.md#11-composed-and-derived-datasets)) |
| DRS-9015 | 422 | Dataset fan-out | An error of the check (so publish is refused): a join repeats rows (many-to-many, or the unique side on the wrong end), so measures of the repeated side add up too high; with `allow-repeat: true` on the join it is a warning |
| DRS-9016 | 422 | Dataset cycle | A dataset reads itself, directly or through the datasets it is composed of or derived from |
| DRS-9017 | 422 | Derived dataset | A derived dataset (`derived:`) names no Transform or no inputs, or declares a column it cannot have; a warning when its columns are not known yet |
| DRS-9020 | 422 | Query invalid | The query names a field, measure, operator, value or date the dataset cannot take, or the model cannot join what it asks |
| DRS-9021 | 403 | Query masked | The query filters or sorts on a column masked for the caller, reads a `direct` table of a kind they may not open, or (a composed dataset) joins on a column a source masked for them |
| DRS-9022 | 413 | Query rows | The answer is over `drishti.bi.query.max-rows` (or, as JSON, over `json-max-rows`) |
| DRS-9023 | 413 | Query bytes | The Arrow answer is over `drishti.bi.query.max-bytes` |
| DRS-9024 | 504 | Query timeout | The query ran past `drishti.bi.query.timeout` and was cancelled |
| DRS-9025 | 429 | Query busy | Too many queries at once for the server (`max-concurrent`) or the user (`max-concurrent-per-user`) |
| DRS-9026 | 502 | Query source | A table's connector is not running, or is not a SQL store for a `direct` table |
| DRS-9027 | 500 | Query engine | The engine failed on the compiled query; the message says how |
| DRS-9028 | 413 | Query memory | (wave B scale) The query needs more memory than the engine may use, even spilling to disk (`drishti.bi.query.memory-limit`): group by less, filter, or ask a rollup's fields |
| DRS-9029 | 413 | Query cost | (wave B scale) The planner's estimate of rows read is over the query's budget (`options.budget.rows`, else its work class's); the message has the estimate and the fix (a date filter, a rollup) |
| DRS-9030 | 422 | Report invalid | The report YAML is malformed, or a key, id, grid position, filter or setting is not valid (the problem names the path of the key); publishing such a report is refused with it |
| DRS-9031 | 422 | Report reference | A field, measure, hierarchy, page, bookmark or slicer the report names does not exist in its dataset or in the report |
| DRS-9032 | 422 | Report visual | A visual is built wrongly: a well it does not have, a measure in a field well or a field in a measure well, a required well empty, too many items in a well |
| DRS-9033 | 501 | Report not built | The report uses a visual type, slicer mode or setting the grammar reserves and this server does not build yet |
| DRS-9034 | 422 | Report dataset | The report's dataset cannot be found or has errors, so the report cannot be drawn |
| DRS-9035 | 422 | Report state | A visual query names a page, visual or slicer the report lacks, or its state (slicers, selections, drill path, carried values, dates) is not valid |
| DRS-9036 | 422 | Theme | (2.4.0) A theme item is not valid (its palette, a colour, its font or a visual default the format schemas refuse), or a report names a theme that does not exist (a warning there: the report draws with the default colours) |
| DRS-9040 | 409 | Breaks reports | Publishing a dataset would break published reports (a field, measure or hierarchy they use is removed or renamed); a `bi-admin` may force it |
| DRS-9041 | 422 | Bundle invalid | A BI bundle is not readable, fails its checksums, is too large or holds an invalid item |
| DRS-9043 | 409 | Failing tests | A report or dataset declares expected values (`tests:`) and one fails or cannot run, so publishing is refused; a `bi-admin` may publish with `force` |
| DRS-9042 | 409 | Import conflict | A bundle import would overwrite content that changed since the export, or names a workspace or owner that cannot be mapped |
| DRS-9050 | 422 | Live table | A live table cannot be kept: it is over an array (`each`) or its `live:` block is wrong |
| DRS-9051 | 429 | Live streams | Too many live streams are open for the server or for this user (`drishti.bi.live.max-streams`, `max-streams-per-user`) |
| DRS-9052 | 422 | Board invalid | A board is malformed, or names a report, page or visual the caller cannot open or that does not exist, a tile off the grid, a slicer, or a threshold that is not `{op, value}` |
| DRS-9053 | 501 | Board not built | A board uses a setting the grammar still reserves: `notifications` or `subscriptions` (`notify` is built) |
| DRS-9060 | 422 | Export failed | An export is off, names an unknown format or a target that cannot be read, is over `max-bytes`, or took longer than `timeout` |
| DRS-9061 | 422 | Subscription not valid | A subscription's target, schedule, format, date rule or recipients are wrong (an email address is refused: users and roles only), there are too many, or subscriptions are off |
| DRS-9062 | 422 | Alert not valid | An alert on a number has a wrong source, condition, trigger, delivery, severity, hysteresis or cooldown; the person has too many; the source cannot be opened by them; `live` is asked of a source with no live table; email is asked and there is no address (or email is off); or alerts are off |
| DRS-9070 | 422 | Python item not valid | A Python item's manifest has a bad key, interface, package (off the allow-list), target, limit or source (no entry function), a python visual names no item, or an item for the browser only is asked to run on the server |
| DRS-9071 | 422 | Python code failed | The code raised an error, or returned something that is not a number per group (measure) or an ECharts option, SVG or PNG (visual); the traceback is in the console's answer for `bi-author` only |
| DRS-9072 | 413 or 504 | Python limit | The call ran over its `timeout` (504), memory, or the size of its input or output (413) |
| DRS-9073 | 403 | Python sandbox refusal | The code imported a package it did not list, or tried the network, a file outside its scratch folder, or a process |
| DRS-9074 | 503 | Python runner unavailable | All workers busy for `queue_seconds`, the runner switched off (`bi.python.enabled`), `pandas` or `pyarrow` missing from the console, or Pyodide missing for a browser run; for a Transform, also `drishti.bi.transform.enabled: false`, the console's `core/transform_cli.py` or its Python not found, or all `drishti.bi.transform.max-concurrent` runs busy |
| DRS-9075 | 422 | Transform result | A Python Transform returned what a derived dataset cannot use: a column the dataset declares is missing from its frame, or the Transform's inputs do not match the dataset's (a different count, or a different dataset in the same place) |
| DRS-9076 | 422 | Python tests failed | A Python item that ships `tests:` is published (or approved) through the console while one of them fails or cannot run; a bi-admin may send `force: true` ([PYTHON_BI.md section 15](PYTHON_BI.md#15-tests-shipped-with-an-item)). Raised by the console only |
| DRS-9077 | 422 | Vega-Lite not drawn | Shown in the visual's frame by the page, not an HTTP answer: a Vega-Lite specification that names a `url` or a `loader`, or a library that did not load ([REPORTS.md section 3.5](REPORTS.md#35-vega-lite-visuals)) |
| DRS-9100 | 422 | Layer dataset invalid | Publishing a `rachana: 2` dataset whose Check has an error (the problem list uses `DRS-9100` to `DRS-9108`, [BI_MODEL_SPEC.md section 1.11](../architecture/BI_MODEL_SPEC.md#111-dataset-problems)) |
| DRS-9102 | 422 | Dataset source | A dataset's source cannot run when it is read: its connector is not running or has no SQL target (connector queries run in DuckDB or PostgreSQL connectors), or the query failed |
| DRS-9107 | 409 | Generated dataset | Saving a dataset that a pack generates (`pack.<pack>.<kind>`, or one with `generated:`): it is read-only; save one that `extends` it |
| DRS-9108 | 422 | Source query | The query of a dataset whose source is a model does not validate (Check: its own code follows), or its answer lacks a column the dataset declares |
| DRS-9109 | 422 | Source cycle | A model that reaches itself through a dataset whose source is a model (Check, the bind of a query, or at run time): `model:a -> dataset:x -> model:a` |
| DRS-9120 | 422 | Model invalid | Publishing a data model whose Check has an error (`DRS-9120` to `DRS-9129` in the problem list, [BI_MODEL_SPEC.md section 2.11](../architecture/BI_MODEL_SPEC.md#211-model-problems)) |
| DRS-9126 | 409 | Breaking change | A published model or dataset loses a measure, column or relationship (by stable id or name), makes a relationship inactive or changes a grain key without a new major version; or the version goes down |
| DRS-9127 | 422 | Expression | An expression that is not DEL v2 (a construct Python has and DEL refuses, the old syntax once refused), or that does not fit its scope, its types or the function catalogue; a model measure that does not compile ([EXPRESSIONS.md](EXPRESSIONS.md#7-problems-and-their-fixes)) |
| DRS-9129 | 422 | Later wave | A key, function or value a later wave builds (user functions `fn.x`: wave C) |
| DRS-9132 | (warning) | Hidden measure still reachable | Check: a measure hidden for some roles reads columns they still see (a default measure or a `define` reaches the number); the message lists the columns to hide too |
| DRS-9163 | 422 | Not available to your role | A field or measure hidden for the caller's roles (`hidden-for`), on every endpoint (a report keeps this code); its definition, columns and who may see it are never said |
| DRS-9131 | (warning) | Roles without a rule | Check names the readers of a dataset that no row rule of the model names: they see no rows (`meta.noAccess` in their answers) |
| DRS-9130 | 409 | Security safeguard | A model where a role restricted by a row rule elsewhere would see every row of a dataset with no rule: give that dataset a rule, or write `may-see-all: { <alias>: [<role>] }` (audited `bi-model-may-see-all`) |
| DRS-9150 | 422 | Model query shape | An unknown key, a wrong type, `mq` missing or not 1 or 2, two time forms, `params`, `calc` or `perspective` with `mq: 1`, an `options.budget` that is not whole numbers from 1 |
| DRS-9151 | 404 | Model not found | No model (or dataset, for `dataset:<name>`) of that name and version the caller may read, a retired major, or a model whose Check has errors |
| DRS-9152 | 422 | Unknown name | A field, measure, hierarchy or stable id the model lacks (with `fix` when a near name exists) |
| DRS-9153 | 422 | Outside the contract | A field or measure the model's `contract` does not expose (with the deprecation and `fix` when deprecated) |
| DRS-9154 | 422 | Local measure | A `define` measure that shadows a model measure or does not compile |
| DRS-9155 | 422 | Filter | An operator the field's type does not allow, a value of the wrong type, an unknown relative window, a `top` without `n`/`by`, a `having` that is not one number, a drill past the last level |
| DRS-9156 | 422 | Not reachable | A field, filter or row rule not reachable many-to-one from a measure's fact (with `fix`: fields every fact reaches) |
| DRS-9157 | 422 | Time | A window over `drishti.bi.mq.max-days`, `live` with no live dataset, a sliding window with no history, an as-of after the business date |
| DRS-9158 | 422 | Several currencies | A sum adding rows of several currencies in a group: refused from wave A (fix: add the currency to the fields or filter one); a warning in `meta.warnings` with `drishti.bi.plan.guards: warn` (W1) |
| DRS-9159 | 422 | Semi-additive | A balance measure added across a window of dates (asked with other measures, without the date as a field): refused from wave A; asked alone it is answered as the last date's value with a warning |
| DRS-9160 | 422 | Repeats rows | A row extract (`shape: rows`) with fields of two facts |
| DRS-9161 | 403 | Masked field | A filter, sort or `top` on a field masked for the caller (the body also names `DRS-9021`; `fix` offers the dataset's key) |
| DRS-9162 | 422 | Python measure | A Python measure in a grouped query when the server has no Python runner, or one the query sorts on, filters on (`having`) or ranks by (`top`): it is worked out on the console after the query (wave B) |
| DRS-9164 | 422 | Parameter | (`mq: 2`) A `params` value the model does not take: an unknown parameter, the wrong kind, outside `min`..`max`, not one of its `values`, a field parameter's value not one of its `fields`, or `param.x` in `select.fields` that is not a field parameter |
| DRS-9165 | 422 | Calculation group | (`mq: 2`) A `calc` group or item the model does not have (with `fix`: its groups or items), or a Python measure under a calculation group |
| DRS-9166 | 422 | Filter context | A filter-context or time function that cannot be planned: `via=` names no relationship, `parent_of` no hierarchy, `as_of` a date that is not fixed before the query (`date('…')`, `asof()`, `shift_date(asof(), n)`), contexts nested more than three deep through the measures they name, a context where none can be (a rollup, a live aggregate) |
| DRS-9190 | 422 | Not converted | The expression converter could not keep the meaning of an old-syntax expression; it is left as it was (`POST /bi/expr/convert` lists it under `problems`) |
| DRS-9170 | 422 | SQL syntax | Not a Drishti SQL statement: a syntax error (with its position), more than one statement, or DDL/DML/`ATTACH`/`COPY`/`PRAGMA`/`SET` (the dialect is read-only) |
| DRS-9171 | 422 | SQL name | A table that is not an alias of the model, a column it does not have for the caller (a hidden column is not found), a column of a table the statement does not read, or a bare name in two tables |
| DRS-9172 | 422 | SQL join | A join whose `ON` is not a relationship of the model (or a comma, `RIGHT`, `FULL`, `CROSS` or `NATURAL` join): write `JOIN t` without `ON`, or ask the model owner for a relationship |
| DRS-9173 | 422 | SQL function | A function the dialect does not take: table functions, user functions, `random()` |
| DRS-9174 | 422 | SQL measure | An aggregate over a measure (`SUM(MEASURE('x'))`), or a measure or aggregate in `WHERE` (use `HAVING`) |
| DRS-9175 | 422 | SQL window | A window over a dataset's rows (inside an aggregate, or naming a column the `SELECT` does not select): windows run over the answer |
| DRS-9176 | 422 | SQL scope | Another model, a connector, a kind, a file or a URL named (`read_parquet`, `pack.x.y`): one model per statement |
| DRS-9177 | 422 | SQL time | Two different times in one statement, a time clause outside a `SELECT` over the model's tables, or a date that is not `YYYY-MM-DD` |
| DRS-9178 | 422 | SQL distinct | `SELECT DISTINCT` on a fact without `GROUP BY` (a silent de-duplication changes the grain) |
| DRS-9179 | 422 | SQL shape | A statement the dialect does not take (yet): `SELECT *`, a computed row column, an `OR` across fields in `WHERE`, aggregating answers again, `IN (SELECT ...)`; the message says what to write |
| DRS-9180 | 404 | No such subscription | A standing query id the caller does not hold |
| DRS-9181 | 429 | Server subscriptions | `drishti.bi.mq.max-subscriptions` standing queries are open already |
| DRS-9182 | 429 | Your subscriptions | `drishti.bi.mq.max-subscriptions-per-user` standing queries of yours are open already |
| DRS-9183 | 422 | Past as-of | Subscribing to a query as of a past date, which never changes: ask it once |
| DRS-9184 | 503 | Shard owner unreachable | (wave B scale, several Java nodes) The node that owns the model's shard could not be reached to forward a subscription; retry after `Retry-After` |

`DRS-5003` (503, "backend unreachable") and `DRS-9404` (404, an unknown Drishti Elements module or script) are raised by the console, never by the server, so they are not in this table. `DRS-8006` is in the table for completeness: the console raises it, the server never does. Sutra load problems listed by `/sutras/problems` and in `problems` use their own finer `DRS-2xxx` codes
(for example `DRS-2004` for a `.sutra.md` or plain `.yaml` file in a Sutra folder, `DRS-2009` for a missing or
unknown `rachana:` version); see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#problem-codes).

## Business intelligence (Rupaka): `/api/v1/bi`

Phase 1 of Drishti Rūpaka ([BI_GUIDE.md](BI_GUIDE.md), [RUPAKA.md](../architecture/RUPAKA.md)): workspaces, typed items with a YAML body
(stored and versioned; a `dataset` body is validated and queried, see the next section; the others are **not executed**), publish and review, endorsement and the catalogue. There is no admin path: the BI powers on the
caller's roles and the workspace roles decide every call, from the identity on the request. A caller without the `bi` power gets `403 DRS-9002`
on every call; with `drishti.bi.enabled` off, `403 DRS-9006`. Items and workspaces the caller may not see answer `404 DRS-9001`. Writes by a
personal token need the scopes `bi:write`, `bi:approve` and `bi:admin` (the table under [tokens](#personal-api-tokens)). Every change is audited
(`bi-*`). Bodies are JSON; an item's `body` is a YAML string (at most `drishti.bi.max-body-bytes`, a mapping at the top, or empty).
Item ids are `bi-<10 hex>` (shipped files: `file:<kind>:<name>`); `me` stands for the caller's personal workspace.

**Saving safely (since 2.1.0).** `GET /api/v1/bi/items/{id}` carries `etag` (the saved state the reader has). An update may send it back as `baseEtag`: when the item has been saved since, the answer is `409 DRS-9007` and nothing is written; send the update without `baseEtag` to overwrite on purpose. Every editor does this for you (the report and board designers, the Model view, the wizard in edit mode, the item page's Edit form): it offers **Reload theirs**, **Save mine as a copy** and **Overwrite with mine**. `POST /items/{id}/propose`, `/approve` and `/publish` refuse a **report that cannot be drawn** (its dataset is missing, a visual names a field that is gone) with `422 DRS-9030` listing the errors; a bi-admin may send `"force": true` (the audit trail records `FORCED over n drawing error(s)`). A draft that cannot be drawn can still be saved. `GET /api/v1/bi/datasets?drafts=1` also lists the caller's own unpublished datasets, each with its `status` and `personal`, so a picker can grey them out with the reason. A visual that has an error of its own answers `422 DRS-9034` for itself only: the other visuals of the report are still drawn.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/bi/me` | `{enabled, powers, user, personalWorkspace, workspaceCreation, mayCreateWorkspace, kinds, roles}` |
| `GET` | `/bi/home` | `{recent, favourites, certified, shared, counts}`: lists of item cards the caller may open |
| `GET` | `/bi/catalogue` | published items the caller may open. Query: `q` (every word must match title, description, tags, owner, kind or workspace name), `kind`, `workspace`, `owner`, `endorsement` (`none`, `promoted`, `certified`), `tag`, `pack`, `fresh` (published within N days), `sort` (`use` default, `date`, `title`), `limit`. Answer `{total, items, facets, workspaceNames}`; certified first unless `sort=title` |
| `GET` `PUT` | `/bi/settings` | `{workspaceCreation: authors\|stewards\|admins, fourEyes, dir, shippedItems, powers, roles}`; `PUT {workspaceCreation}`; needs `bi-admin` |
| `GET` `POST` | `/bi/workspaces` | the caller's workspaces (personal, member of, shipped; a `bi-admin` sees all shared) with `role` and `items`; `POST {name, description?, id?, reviewRequired?}` creates a shared one (`201`; the caller becomes its admin; the policy decides who may) |
| `GET` `PUT` `DELETE` | `/bi/workspaces/{id}` | one workspace with `members`; `PUT {name?, description?, reviewRequired?}` and `DELETE` (empty workspaces only; `204`) need the workspace admin |
| `PUT` `DELETE` | `/bi/workspaces/{id}/members/{user}` | `PUT {role: viewer\|contributor\|member\|admin}` adds or changes a member; `DELETE` removes one; never the last admin (`409`) |
| `GET` `POST` | `/bi/workspaces/{id}/items` | the items the caller may see in it; `POST {kind: dataset\|report\|board\|notebook, title, description?, tags?, body?, audienceRoles?, audienceUsers?, audiencePacks?}` creates a draft (`201`; contributor and `bi-author`) |
| `GET` `PUT` `DELETE` | `/bi/items/{id}` | the item card with `body` (the draft for contributors and above, else the published version), `bodyIs`, `draftChanges` and `can{edit, propose, publish, approve, withdraw, promote, certify, delete, move}`; `PUT` takes any of the create fields; `DELETE` is `204` |
| `POST` | `/bi/items/{id}/open` | as `GET`, and counts a view and a Recent entry (published items) |
| `POST` | `/bi/items/{id}/propose` `/withdraw` `/approve` `/reject` | review: `{note}` or `{reason}` (required to reject); approve needs a workspace member with the `approve` power |
| `POST` | `/bi/items/{id}/publish` | `{note?, force?, applyRenames?}`: the next version; not in a personal workspace, not when the workspace requires review. `applyRenames` (a dataset whose draft renames fields or measures the same way, [BI_GOVERNANCE.md section 6](BI_GOVERNANCE.md#renames)) rewrites the stored reports that use the old names, in the same step |
| `POST` | `/bi/items/{id}/move` | `{workspace}`: move to another workspace (`me` for personal) |
| `POST` | `/bi/items/{id}/endorse` | `{level: none\|promoted\|certified}` on a published item |
| `PUT` | `/bi/items/{id}/favourite` | `{on: true\|false}` for the caller |
| `GET` | `/bi/items/{id}/published` | the published version as readers get it, for an author looking at a newer draft: `{id, kind, title, version, publishedAt, body, draftDiffers}`; `DRS-9001` when the item was never published. Any `/bi/reports/...` or `/bi/boards/...` call with `?as=published` reads the published version of what it opens (masks and row rules still the caller's) |
| `GET` | `/bi/items/{id}/versions`, `/versions/{n}` | the published versions (newest first), one with its `body` |
| `POST` | `/bi/items/{id}/versions/{n}/restore` | makes version `n` the draft |

A catalogue answer, as the server sent it for a reader (trimmed):

```
$ curl -s -H "Authorization: Bearer $TOKEN" "$SERVER/api/v1/bi/catalogue?q=exposure"
{"total": 1, "items": [{"id": "bi-cd5f8b2cc5", "workspace": "rates-desk", "workspaceName": "Rates Desk", "kind": "board",
  "title": "Counterparty exposure", "description": "Live exposure by counterparty against limits", "owner": "ann", "tags": ["exposure", "credit"],
  "status": "published", "publishedVersion": 1, "endorsement": "promoted", "endorsedBy": "ann", "audience": {"roles": ["bi-reader"], "users": [], "packs": []},
  "publishedAt": "2026-10-10T20:26:22.088Z", "views": 0, "viewsDays": 30, "favourite": false, "shipped": false, "role": "viewer",
  "can": {"edit": false, "propose": false, "publish": false, "approve": false, "withdraw": false, "promote": false, "certify": false, "delete": false, "move": false}}],
 "facets": {"kind": {"board": 1, "dataset": 1, "report": 3}, "endorsement": {"certified": 1, "none": 2, "promoted": 2}, "...": "..."},
 "workspaceNames": {"rates-desk": "Rates Desk", "shipped": "Shipped content"}}
```

and the refusals, with their codes:

```
$ curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' "$SERVER/api/v1/bi/items/bi-27def70913/publish"
{"type":"about:blank","title":"bi forbidden","status":403,"detail":"DRS-9002 cat does not hold the bi-author power (an administrator grants it on a role)","instance":"/api/v1/bi/items/bi-27def70913/publish","code":"DRS-9002"}
$ curl -s -H "Authorization: Bearer $NOBI_TOKEN" "$SERVER/api/v1/bi/home"
{"type":"about:blank","title":"bi forbidden","status":403,"detail":"DRS-9002 nobi does not hold the bi power (an administrator grants it on a role)","instance":"/api/v1/bi/home","code":"DRS-9002"}
```

Roles carry the BI powers: `GET` and `PUT /admin/role-definitions/{name}` have a `bi` list (`bi`, `bi-author`, `bi-steward`, `bi-admin`; an
unknown name is `422 DRS-9003`; a `PUT` that does not name `bi` keeps the powers the role had). From the console the same calls are under
`/bi/api/...` (same-origin JSON, passed on under the signed-in user). The command line wraps the reads and publish:
[CLI_GUIDE.md](CLI_GUIDE.md#bi-workspaces-and-the-catalogue-rupaka).

### The phase 0 proof of concept: removed

The throw-away `/api/v1/bi/poc` endpoints (and `drishti.bi.poc`) were removed in 2.2: the model layer replaced what they proved
([RUPAKA_POC.md](../architecture/RUPAKA_POC.md) keeps the findings).

## Datasets and the query engine: `/api/v1/bi` (phase 2)

Phase 2 of Rūpaka ([BI_GUIDE.md](BI_GUIDE.md) section 13, [DATASETS.md](DATASETS.md), [tutorial 10](../tutorials/10-first-bi-report.md)). A dataset is a BI item of kind `dataset` (id `bi-<hex>`, or `file:dataset:<name>` for a shipped file); who may read the item is
decided as for any item (`404 DRS-9001` otherwise), what the answer shows is decided by the dataset's row rules and Drishti's masks for the caller. A personal token reaches the queries and the model reads (they change nothing); `POST .../refresh` needs `bi:write` (it is in the `bi:write` scope's allow-list; `GET .../refresh` only reads).

| Method | Path | What it does |
|---|---|---|
| `GET` | `/bi/datasets` | `{datasets: [{id, title, description, endorsement, tags, sample, shipped, valid, errors, tables, measures, relationships}]}`: the published datasets the caller may see (samples are left out for those who may not see sample content) |
| `GET` | `/bi/datasets/{id}` | the model for this caller: `{id, title, name, version, valid, problems[{level, code, path, message}], tables[{name, connector, kind, each, mode, access, columns[{name, path, type, key, calculated, expr, masked}]}], relationships[], measures[{name, expr, rollups}], hierarchies, security{rows[{role, where, appliesToYou}], masked, rowRulesEnforced}, rollups, layers}`; a composed or derived dataset ([DATASETS.md section 11](DATASETS.md#11-composed-and-derived-datasets)) also has `composition: {kind, sources[{alias, dataset, by, fields, measures, filters}], joins[{left, right, on, type}], frame[{name, from, type, measure}] \| transform, params}` |
| `POST` | `/bi/datasets/validate` | `{yaml}` (needs `bi-author`): `{valid, problems[], tables, measures, model}` without storing anything; `model` (when the structure parsed) is the shape the Model view draws: `{tables[{name, kind, columns[...]}], relationships[{from, to, cardinality, direction, active}], measures[], hierarchies}` |
| `POST` | `/bi/datasets/{id}/query` | **removed in 2.2: `410`** with `code: DRS-9150` and `mq`, the equivalent model query (the fields, measures, filters, dates, sort and limit of the body, on the model the converter made of a 2.1 dataset, `<name>@1`, or `dataset:<name>` for a `rachana: 2` one); send it to `POST /bi/mq` ([BI_MODEL_SPEC.md 8.5](../architecture/BI_MODEL_SPEC.md#85-what-breaks-on-purpose)) |
| `GET` | `/bi/datasets/{id}/layers` | what the BI store holds per table: dates, rows, how each was read; for a live table (phase 5) `partitions` is empty and `live` holds its counters (below) |
| `POST` | `/bi/datasets/{id}/transform/run` | refused since 2.2 (`422`): derived datasets were removed; a Python dataset (`source.python`) runs its Transform when it is asked |
| `GET` | `/bi/datasets/{id}/refresh` | the refresh state (any reader of the dataset): `{enabled, scheduled, zone, calendar, dataset: {schedule, status, trigger, lastRun, nextRun, lastDurationMs, lastRows, lastDate, tables[], failures, retryAt, error, runs, history: [{at, trigger, status, rows, durationMs, date, error}]}, tables: [{table, ...the same}]}`; `history` is the last `drishti.bi.refresh.history` runs, newest first, kept in the BI store so it survives a restart; `status` is `never`, `waiting`, `running`, `ok`, `retrying`, `failed`, `invalid` or `interrupted` (the server stopped during the run); the dataset's `refresh:` schedule ([DATASETS.md section 7.2](DATASETS.md#72-scheduled-refresh)) |
| `POST` | `/bi/datasets/{id}/refresh` | (needs `bi-author`; a token needs `bi:write`) clears the current business date's layers of this dataset and reads them again in the background; answers `{dataset, date, refreshing[], state}` at once (the state is the `GET`'s `dataset` object, `running`) and audits `bi-dataset-refresh` |
| `GET` | `/bi/model/kinds` | every kind of every pack the caller may open as a table: `{tables: [{table, pack, kind, connector, data, source, columns[{name, path, type, promoted}]}]}` |
| `POST` | `/bi/model/start` | `{kinds: [...], name?}` (needs `bi-author`): `{yaml, tables, relationships[], proposals[], warnings[]}`: a draft dataset, the links the packs declare (`crossPack` marks those across packs), and relationships proposed from the data |
| `POST` | `/bi/model/infer` | `{kinds: [...]}` (needs `bi-author`): `{proposals: [{fromTable, fromColumn, toTable, toColumn, cardinality, matchRate, matched, sampled, unmatched[], basis}]}` |
| `GET` | `/bi/engine` | (needs `bi-admin`) counters (`queries`, `cacheHits`, `rejectedBusy`, `layerBuilds`, `byLayer`), the limits in force, since phase 5 `live: {tables, rows, enabled}` and `transforms: {enabled, runs, cacheHits, failures, kept, maxConcurrent}` (the Python Transforms of derived datasets) |

**The query body of 2.1** (what a `410` translates into a model query; still the body of a report visual's `query` answer): `{"fields": ["trades.DESK", "trades.TRADE_DATE.month"], "measures": ["Notional", "MTM"], "filters": [{"field": "trades.CURRENCY", "op": "in", "values": ["USD", "EUR"]}], "dates": {"from": "2026-10-01", "to": "2026-10-09"}, "orderBy": [{"by": "MTM", "desc": true}], "limit": 100, "layer": "auto"}`.
Fields are `table.column` or `table.column.part` (`year quarter month day week`); filter `op` is `eq ne in not-in lt le gt ge between contains starts-with is-null not-null`; a filter whose field is a measure name filters the aggregate;
`dates` omitted means the latest business date; `layer` is `auto`, `columns` or `rollup`. Every name is checked against the model, **every value is a bound parameter**.

**The JSON answer**, today through `POST /bi/mq` (captured on the demo lakes, trimmed; `hana` is a desk head of `DESK-FX` without the raw power,
`Counterparties` a distinct count of a masked column):

```
POST /api/v1/bi/datasets/file:model:trading-risk/query   {"fields":["desks.NAME"],"measures":["MTM","Trades","Counterparties"]}
410 {"title": "dataset query removed", "code": "DRS-9150", "detail": "POST /api/v1/bi/datasets/{id}/query was removed in 2.2: ask the model with POST /api/v1/bi/mq ...",
     "mq": {"mq": 1, "model": "file:model:trading-risk", "select": {"measures": ["MTM", "Trades", "Counterparties"], "fields": ["desks.NAME"]}}}

POST /api/v1/bi/mq   as hana   {"mq": 1, "model": "trading-risk@1", "select": {"fields": ["desks.NAME"], "measures": ["MTM", "Trades", "Counterparties"]}}
{"columns": [{"name": "desks.NAME", "kind": "field", "type": "string", "masked": false}, {"name": "MTM", "kind": "measure", "type": "decimal", "format": "signed0", "masked": false},
             {"name": "Trades", "kind": "measure", "type": "integer", "masked": false}, {"name": "Counterparties", "kind": "measure", "type": "decimal", "additive": false, "masked": true}],
 "rows": [["G10 and EM FX", -669248939.51, 1428, null]], "rowCount": 1,
 "meta": {"asOf": "2026-10-16", "layer": "columns", "rollup": null, "millis": 6.0, "dates": ["2026-10-16"], "tables": ["trades", "desks"],
          "masked": ["Counterparties"], "warnings": ["measure Counterparties is masked"], "generation": "g-0"}}
```

**The Arrow answer** (`format=arrow`): `Content-Type: application/vnd.apache.arrow.stream` (an Arrow IPC stream: text, 64-bit integer and double columns with nulls; pyarrow, pandas and DuckDB read it as it is), with `X-Bi-Layer`, `X-Bi-Rollup`,
`X-Bi-Rows`, `X-Bi-Cached`, `X-Bi-Masked` (the masked columns) and `Server-Timing`. Masks are applied before the bytes are written. A JSON answer holds at most `drishti.bi.query.json-max-rows` rows (`413 DRS-9022` otherwise: ask for Arrow).

Refusals are the standard problem body with the code: `403 DRS-9021` (filtering a masked column), `422 DRS-9020`, `413 DRS-9022`/`9023`, `504 DRS-9024`, `429 DRS-9025`, `502 DRS-9026`, `500 DRS-9027`, and `422 DRS-9010` when the dataset itself has errors.
Every query is audited (`bi-query`: the counts, the rows, the layer). From the console the calls are under `/bi/api/datasets/...` and `/bi/api/model/...`.

## Models and the model query language: `/api/v1/bi` (wave W1)

Release 2.2 wave W1 ([BI_MODEL_SPEC.md](../architecture/BI_MODEL_SPEC.md)): a **dataset** is one governed table (`rachana: 2`, item kind `dataset`), a **data model** puts
datasets in a star with relationships, measures, model security (only tighter) and a semver contract (item kind `model`, shipped files in `config/bi/models/<name>.model.yaml`),
and the **model query** (`mq: 1`, YAML or JSON) is the one request for data. Every pack kind gets a read-only dataset `pack.<pack>.<kind>` without anyone writing it.
A query names its model as `name@major` (`desk-trading@1`), a catalogue id, `dataset:<name>` (the **implicit model** of one dataset: its default measures and, for a
pack dataset, the pack dimensions its columns reference, one hop), or the name of a model the converter made of a 2.1 dataset (the same name). A `rachana: 1` dataset is no longer read as a model
(the compatibility reader was removed in 2.2: `DRS-9151` says to run `drishti.py bi migrate --models`); `POST /bi/datasets/{id}/query` answers `410` with the
equivalent model query.

**Every consumer asks through the model query language** (section 5.9 of the spec). A report visual (and with it exports, subscriptions, board tiles, live tiles
and the embedded report element), an Analyse extract (a model query, `mq:<model>@<major>`), an alert, a dataset test and an AI answer build the request they always
built; the server turns it into a model query on the dataset's model (the implicit model of a `rachana: 2` dataset; a 2.1 live dataset's reports
are asked through the engine as before until wave W3) and runs it through the same validator and compiler as `POST /bi/mq`, as the reader. Their answers keep their shapes; `POST /bi/reports/{id}/query`
adds `mq`, the canonical model query the visual ran, and the query's warnings are added to `meta.warnings`. A refusal keeps the endpoint's code (`DRS-9020` for a
query the engine would not take, `DRS-9021` for a masked field) and carries the model query's problems with their own codes, paths and fixes (captured):

```
POST /api/v1/bi/reports/file:report:acc-report/query  as rita   {"page":"main","visual":"by-desk","state":{"dates":{"from":"2026-10-08","to":"2026-10-08"}}}
{"columns":[{"name":"trades.desk",...},{"name":"Notional",...},{"name":"MTM",...}], "rows":[["DESK-C",108000.0,-100.0],["DESK-B",100000.0,100.0],["DESK-A",92000.0,-200.0]],
 ..., "query":{"fields":["trades.desk"],"measures":["Notional","MTM"],"filters":[],"orderBy":["Notional desc"],"limit":500},
 "mq":{"limit":500,"model":"file:dataset:acc-trades","mq":1,"options":{"format":"json","layer":"auto"},"select":{"fields":["trades.desk"],"measures":["Notional","MTM"]},
       "shape":"grouped","sort":[{"by":"Notional","desc":true}],"time":{"as-of":"2026-10-08"}}}

POST /api/v1/bi/mq   as hana (no raw)   {"mq":1,"model":"trading-risk@1","select":{"measures":["Trades"]},"where":[{"field":"trades.COUNTERPARTY","op":"eq","values":["CP-AURORA"]}]}
403 {"title":"mq masked","detail":"DRS-9161 'trades.COUNTERPARTY' is masked for you: it cannot be filtered on","code":"DRS-9161","path":"where[0].field",
     "fix":{"replace":"where[0].field","with":["trades.id"]},"problems":[{"code":"DRS-9161","path":"where[0].field",...}]}
```

**A report bound to a model** (`model: trades@1` in place of `dataset:`) is read by every report endpoint: validate, query (with `mq`), publish, exports,
subscriptions, board tiles and embeds. Publishing it needs the model published and passing its Check and the security safeguard (`422 DRS-9030` naming why
otherwise). **Connector-query and Python datasets** are read too: the SQL runs in its SQL-capable connector with only the four named dates bound (`DRS-9102`
when the connector is not running or has no SQL target), the Transform over its input model queries asked as the reader.

**A dataset whose source is another model's answer** (`source: { model: acc-model@1, query: { select: ... } }`, spec section 1.2 D) is filled, for each query that
reads it, by its inner query asked **as the reader** (at the outer query's date when it names none); its declared columns are filled by name (`path:` naming a field
or measure of the inner query, else the column's name). A model that reaches itself through such datasets is refused by Check, by the bind and at run time
(`DRS-9109`); an inner answer without a declared column is `DRS-9108`. Captured, the desk-head `hana` asking a model over a dataset read from `acc-model`:

```
POST /api/v1/bi/mq  {"mq":1,"model":"desk-view@1","select":{"fields":["books.desk"],"measures":["Book notional","Book trades"]},"time":{"as-of":"2026-10-07"}}
{"mq":1,"model":{"id":"file:model:desk-view","name":"desk-view","version":"1.0.0","implicit":false,"compat":false}, ..., "rows":[["DESK-A",92000.0,8]],
 "meta":{"asOf":"2026-10-07","dates":["2026-10-07"],"layer":"columns","tables":["books"],...}}

POST /api/v1/bi/mq  {"mq":1,"model":"loop-model@1","select":{"measures":["Total"]}}
422 {"title":"layer source cycle","detail":"DRS-9109 a model cannot read its own answer: model:loop-model -> dataset:loop-a -> model:loop-model","code":"DRS-9109","path":"model",...}
```

| Method | Path | What it does |
|---|---|---|
| `POST` | `/bi/mq` | asks a model a model query (the body is the query, or `{yaml: "<the query as YAML>"}`); `?format=json` (default) or `arrow` (also `options.format`); `options.explain: true` adds the SQL for BI authors. Answers the shape below; refusals are `422`/`403`/`404` with `code`, `path`, `fix` and `problems` |
| `POST` | `/bi/models/{id}/query` | the same with the model in the path (the body has no `model`; `mq` may be left out) |
| `POST` | `/bi/mq/validate` | validation steps 1 to 9 without reading data: `{valid, model, warnings, currencyGuard, query, request, totals}` (`request` is the engine request it would run: a drill already a field and `eq` filters) or the refusal |
| `GET` | `/bi/models` | the models the caller may read, their own draft models included: `{models: [{id, name, version, title, status, kind: model \| implicit \| compat, draft?, owner?, workspace?, query?}]}` (`draft`, `owner` and `workspace` for model items; `query` is what to put in `model:` for a dataset) |
| `GET` | `/bi/models/{id}/describe` | what a designer, an AI or a client needs to write a query, as the caller sees it: `{id, name, version, implicit, compat, title, lifecycle, datasets[{alias, dataset, version, role, mode, fields[{field, id, type, role, meaning, references, unit, format, masked, description}]}], relationships[], measures[{name, id, expr, format, additive, currency, implicit, masked}], hierarchies, calcGroups[{name, precedence, description, items[{name, expr}]}], parameters[{name, kind, title, description, default, min, max, step, values, fields}], calendar{holidays, fiscalYearStart}, fx{rates, from, to, rate, date, base}, contract, ai}` (wave B: `calcGroups`, `parameters`, `calendar`, `fx`); masked fields marked, hidden ones (`hidden-for`) absent |
| `GET` | `/bi/models/{id}/consumers` | what reads the model (`{id}` an item id, `name@major` or `dataset:name`): `{consumers[{kind, id, title, status, owner, workspace}], hidden}` (the ones the caller may not see are counted in `hidden`); the same list is `usedBy` in `describe` |
| `POST` | `/bi/models/{ref}/preview-as` | body `{role, query?}` (`query` a model query without `model`; default: no fields, no measures): the answer as a reader holding **only that role** would get it (the role's access to the model, row rules, masks by its raw power, hidden fields; no user attributes, so a rule on `user.desks` gives no rows), plus `previewAs: {role, attributes, by}`; a refusal the role would get is `refused: [{code, path, message}]` with no rows (never the asker's own answer). A bi-admin or the model's owner only (`403 DRS-9002` otherwise); audited `bi-model-preview-as` |
| `GET` | `/bi/models/roles` | the roles that may use BI, for the designer's role picker; `?model=<ref>` keeps only the roles that can read that model's datasets |
| `GET` | `/bi/layer-datasets/{id}` | a dataset item as the model layer reads it: a narrowed dataset folded with its base (columns, grain, keys, meanings, row rules) |
| `POST` | `/bi/models/validate` | (needs `bi-author`) body `{yaml}` of a model or a `rachana: 2` dataset: Check without storing: `{kind, name, version, valid, problems[{level, code, path, message}], datasets[], measures[], exposures[{dataset, role}]}` (`exposures`: the security safeguard's findings) |
| `GET` | `/bi/pack-datasets` | the pack-generated datasets of the kinds the caller may open: `{datasets: [{name, id, title, pack, kind, version, mode, endorsement, columns, query}]}` |
| `GET` | `/bi/pack-datasets/{name}` | one generated dataset's YAML (read-only): `{name, version, readOnly: true, yaml}` |
| `POST` | `/bi/mq/subscribe` | a standing query: body `{query, throttle: "1s", deliver: rows \| diff \| value}`; answers `{subscription: "sq_…", throttle, deliver, answer, cursor, group: "sg_…", mode: incremental \| recompute \| tick, subscribers}` (wave B: subscriptions with the same plan and security signature are one group, evaluated once; a second subscriber gets the group's answer without an evaluation) |
| `GET` | `/bi/mq/stream` | `?ids=sq_a,sq_b` (all of the caller's when absent): server-sent events, `answer` (`{subscription, cursor, rows \| diff{upsert, delete} \| value, totals?, mode}`) when a subscription's answer moved on, at most one per its throttle (wave B: pushed by the generation bus when the model's data moves; the latest state wins), and `status` (`{subscription, state: live \| throttled \| tick \| failed, throttle, cursor, mode, group, lagMillis}`) every `drishti.bi.live.status` |
| `DELETE` | `/bi/mq/subscribe/{id}` | ends a subscription of the caller (`204`; `404 DRS-9180` for one they do not hold) |

**The query** (every key, [BI_MODEL_SPEC.md section 5.1](../architecture/BI_MODEL_SPEC.md#51-the-object)): `mq: 1`, `model`, `define` (local measures), `select: {fields, measures}`
(names or stable ids), `where` (the dataset query's operators plus `relative` with a `window` such as `last-30-days` and `top` with `n`, `by`, `dir`), `having` (a measure
against one number), `time` (one of `as-of`, `window: {from, to}`, `window: {last: 1h}`, `live: true`), `sort`, `limit` (default `drishti.bi.mq.grouped-limit`), `totals`
(`grand`, `subtotals` or `{levels}`: only the measures that do not add up are asked per level), `drill: {hierarchy, path}`, `shape: grouped | rows`,
`options: {layer, explain, format}`. **`mq: 2`** (wave B, [BI_DESIGN.md section 3.6](../architecture/BI_DESIGN.md#36-mq-the-one-query-object-and-where-all-four-languages-meet))
adds `params: {name: value}` (what-if values bound as `param.name` in the model's measures, and field parameters: `param.breakdown` in `select.fields` is the field
the value names; defaults when left out; `DRS-9164`), `calc: {Group: Item}` (calculation groups applied to every selected measure, the answer keeps the measure's
name; `DRS-9165`), `perspective: <name>` (names outside it are `DRS-9153`) and `options.budget: {millis, rows}`; `mq: 1` with one of these keys is `DRS-9150`. The
parameters' values, the items applied and the local measures are part of the plan hash, so of every cache key. Validation runs ten steps in order before any data is read; the first step that fails answers with all its problems
([section 5.3](../architecture/BI_MODEL_SPEC.md#53-validation-before-any-data-is-read)). In W1 the currency guard (`DRS-9158`) and the semi-additive rule (`DRS-9159`) are warnings in
`meta.warnings`, not refusals.

**Explain and Python measures (wave B).** With `options: { explain: true }` the answer's `meta` has `explain` (the plan as a tree, each node with its
`tier`: `source` for a fragment a SQL store runs, `java`, `console` for a `PyAggregate`; each scan with its `layer`: `columns`, `live`, `history`, `direct`,
`rollup <name>`), `tiers` (nodes per tier), `timings` (`engineMillis`, `pythonMillis`, `layer`, `sourceOrJavaMillis`) and `explainText` (the indented text of
[BI_DESIGN.md §4.7](../architecture/BI_DESIGN.md#47-explain)). A Python measure (`python: <item>`, `inputs`, `by`) may be asked in any grouped query: the
SQL measures run first, then the rows of its inputs with the answer's fields are read as the reader and the Python runner gives one value per group (a
masked input leaves the column `None`, said in `warnings`); it has no total. The runner is the console's **Py-node endpoint** when
`drishti.bi.transform.console-url` is set (else the server runs the console's `transform_cli.py` as before):

| Call (server to console) | Body | Answer |
|---|---|---|
| `POST {console-url}/internal/py/transform` | `{manifest, params, inputs: [base64 Arrow IPC], max_rows, max_bytes}` | `200` the result as Parquet (`application/vnd.apache.parquet`), headers `X-Drishti-Rows`, `X-Drishti-Ms`, `X-Drishti-Columns` (`[{name, type}]`) |
| `POST {console-url}/internal/py/measure` | `{manifest, params, group_by: [names], columns: [names], rows: [[...]]}` | `200 {ok: true, by, rows: [[group values..., value]], ms, cached}` (the item's `compute` per group, or `compute_groups(frame, by, params)` once over every group) |
| `POST {console-url}/internal/py/column` | `{manifest, params, columns: [names], rows: [[...]]}` | `200 {ok: true, type, values: [one per row], ms, cached}` (the `column` interface: `derive(frame, params)`, plan node `PyColumn`) |
| a refusal (either) | | `{ok: false, code: DRS-9070..9076, status, detail}` (JSON) |

Both carry `X-Drishti-Runner-Token` (`drishti.bi.transform.console-token`); the console checks it. At most `drishti.bi.transform.max-concurrent` calls at
once (`503 DRS-9074` after `queue-wait`), each at most `timeout` (`504 DRS-9072`).

**An answer** (captured from a scratch server with the trading pack and its small file lake; the model `desk-trading` has a desk-head rule on its one dataset,
`pack.trading.trade`). As `rita` (risk) and then as `hana` (desk-head of DESK-FX), the same query:

```
POST /api/v1/bi/mq   {"mq":1, "model":"desk-trading@1", "select":{"fields":["trades.desk"], "measures":["MTM","Trades","MTM per trade"]}, "sort":[{"by":"MTM","desc":true}]}
rita: {"mq": 1, "model": {"id": "file:model:desk-trading", "name": "desk-trading", "version": "1.0.0", "implicit": false, "compat": false},
       "columns": [{"name": "trades.desk", "id": "ds_pack_trading_trade/c_desk", "kind": "field", "type": "string", "masked": false},
                   {"name": "MTM", "id": "m_mtm", "kind": "measure", "type": "decimal", "format": "signed0", "additive": true, "masked": false},
                   {"name": "Trades", "id": "m_trades", "kind": "measure", "type": "integer", "format": null, "additive": true, "masked": false},
                   {"name": "MTM per trade", "id": "m_avg", "kind": "measure", "type": "decimal", "format": null, "additive": false, "masked": false}],
       "rows": [["DESK-EQD", 425000.0, 20, 21250.0], ["DESK-FI", -75000.0, 20, -3750.0], ["DESK-FX", -575000.0, 20, -28750.0]], "rowCount": 3,
       "meta": {"asOf": "2026-10-08", "dates": ["2026-10-08"], "layer": "columns", "rollup": null, "parts": 1, "tables": ["trades"], "masked": [], "warnings": [],
                "cached": false, "millis": 227.9, "generation": "g-0", "query": {"model": "desk-trading@1", "mq": 1, "options": {"format": "json", "layer": "auto"},
                "select": {"fields": ["trades.desk"], "measures": ["MTM", "Trades", "MTM per trade"]}, "shape": "grouped", "sort": [{"by": "MTM", "desc": true}]}, "sql": null}}
hana: ... "rows": [["DESK-FX", -575000.0, 20, -28750.0]], "rowCount": 1, ...
```

The implicit model of a pack dataset needs no model at all (`dataset:pack.trading.trade`; its default measures are named `Count of trade`, `Sum of
QUANTITY`, ...: one `Sum of` per decimal column; `drishti.py bi model describe dataset:pack.trading.trade` lists them). Captured on the demo lakes:

```
POST /api/v1/bi/mq   {"mq":1, "model":"dataset:pack.trading.trade", "select":{"fields":["trade.CURRENCY"], "measures":["Count of trade","Sum of QUANTITY"]}, "limit":3}
{"mq": 1, "model": {"id": "implicit:ds_pack_trading_trade@1", "name": "pack.trading.trade", "version": "1.0.0", "implicit": true, "compat": false},
 "rows": [["AUD", 421, 131332429161.5], ["CAD", 681, 191199328261.2], ["CHF", 590, 139266649156.07]], ...}
```

**A refusal** carries the step's code, the path in the query and a `fix` where one exists (the designer shows it as a one-click change; the AI repair loop sends it back once):

```
POST /api/v1/bi/mq   {"mq":1, "model":"trading-risk@1", "select":{"fields":["trades.Desk"], "measures":["MTM"]}}
422 {"title": "mq name", "status": 422, "code": "DRS-9152", "path": "select.fields[0]",
     "detail": "DRS-9152 table 'trades' has no column 'Desk' (it has VALUATION_ID, TRADE_ID, SOURCE_SYSTEM, DESK, PORTFOLIO, COUNTERPARTY, NETTING_SET, PRODUCT_TYPE, PRODUCT_GROUP, CURRENCY, TRADE_STATUS, NOTIONAL, MTM, PNL_1D, DV01, TRADE_DATE, MATURITY_DATE)",
     "fix": {"replace": "select.fields[0]", "with": ["trades.DESK"]}, "problems": [ ...the same, one per problem of the step... ]}
```

**The plan, the security signature and the result cache** (release 2.2 wave A, [BI_DESIGN.md §3.6, §4, §5.4, §6.1](../architecture/BI_DESIGN.md#36-mq-the-one-query-object-and-where-all-four-languages-meet)).
Every question, whatever asked it (this endpoint, a report visual, an alert, an Analyse slice, Drishti SQL), becomes one **logical plan**: a `SecureScan` per
dataset read (the reader's row rules ANDed, masks, hidden columns, the query's filters pushed in), a `SemiJoin` where a dimension's security reaches a fact,
an `Aggregate` per fact grain (met on the shared fields when there are several), then `Window`, `Having`, `Sort`, `Limit`, `Answer`. `meta.plan` is the
**plan hash**: the same question in any language has the same hash (names as stable ids, keys sorted, relative dates resolved; security is not in it).
`meta.signature` is the reader's **security signature**, an opaque id of their effective security on the model (resolved predicates, masks, hidden columns,
kinds they may not open; a model that reads answers asked as the reader is personal). Answers are cached by plan, signature and the data's generation, so
two readers with identical effective security share an entry and readers with different security never do; `meta.cached` says so. With `options.explain:
true`, `meta.explain` is the plan as a tree: a scan says *that* rules applied (`rules: 1`) and which columns are masked, never a rule's text. Captured
(`hana`, then `hugo`, both desk-heads of DESK-A; then `hana` with explain):

```
hana: "meta": {"asOf": "2026-10-08", ..., "cached": false, "plan": "p_2dab3b12b3d48475", "signature": "s_f82a28ede96c9a86", "millis": 217.2, ...}
hugo: "meta": {"asOf": "2026-10-08", ..., "cached": true,  "plan": "p_2dab3b12b3d48475", "signature": "s_f82a28ede96c9a86", "millis": 2.2, ...}
"explain": {"op": "Answer", "columns": ["trades.desk", "Notional"], "shape": "grouped", "inputs": [{"op": "Limit", "n": 500, "inputs": [{"op": "Aggregate",
  "by": ["trades.desk"], "fact": "trades", "measures": ["Notional"], "inputs": [{"op": "SecureScan", "alias": "trades", "columns": ["currency", "desk", "notional"],
  "dataset": "lang-trade", "pushed": [["trades.currency", "eq", ["USD"]]], "time": "as-of 2026-10-08", "version": "1.0.0", "rules": 1, "masked": []}]}]}]}
```

`POST /bi/mq/validate` also returns `plan`, `signature` and `explain` (without reading data). From wave A the guards refuse what W1 only warned about
(`drishti.bi.plan.guards: refuse`): a sum adding several currencies in a group (`422 DRS-9158`, fix: add the currency to the fields or filter one) and a
balance added across dates (`422 DRS-9159`); a balance asked alone over a window is still answered as its last date's value, with the warning.

**Model semantics and access** (wave A, [BI_DESIGN.md §2.2, §5.1, §5.3](../architecture/BI_DESIGN.md#5-security-architecture)). A model may carry
**field properties** every consumer inherits, **perspectives** and **object-level security**:

```yaml
fields:
  trades.desk:     { display: Desk, folder: Organisation, description: The trading desk, category: none, sort-by: trades.deskOrder }
  trades.notional: { display: Notional amount, format: amount0, folder: Amounts, summarise: sum, hidden: false }
measures:
  MTM: { expr: "sum(trades.mtm)", display: Mark to market, folder: Risk, hidden: false }
perspectives:
  desk-view: { title: Desk view, fields: [trades.desk, trades.notional, desks.*], measures: [Notional, Trades] }
security:
  hidden-for: { roles: [bi-analyst], fields: [trades.currency], measures: [Open MTM] }   # or a list of these
```

`GET /models/{id}/describe` gives each field its `display`, `folder`, `hidden` (not listed; still usable), `sortBy`, `category` (one of `none`, `country`,
`region`, `state`, `city`, `postcode`, `address`, `latitude`, `longitude`, `place`, `url`, `image-url`, `currency`, `percentage`, `identifier`), `summarise`
(`sum`, `avg`, `min`, `max`, `count`, `distinct`, `none`), and `format`/`description` when the model says them; each measure its `display`, `folder` and
`hidden`; and `perspectives` (`name`, `title`, `description`). `?perspective=<name>` keeps only that perspective's fields and measures (an unknown one is
`422 DRS-9153`). A field or measure **hidden for** a role the caller holds, and every measure that reads a hidden column, do not exist for them: absent from
`describe`, unknown to DEL validation and Drishti SQL (`DRS-9171`), and a model query naming one is `422 DRS-9152` "no field" / "no measure", exactly as a
typo (captured: `{"code": "DRS-9152", "detail": "no field 'trades.currency' in this model"}`). Captured field of `describe`:
`{"field": "trades.notional", "type": "decimal", "format": "amount0", "display": "Notional amount", "folder": "Amounts", "hidden": false, "sortBy": null,
"category": null, "summarise": "sum", ...}`.

**Reports shared without the model (the envelope).** A reader who may view a published report but has no read on its model (not in the model's workspace or
audience) can use the report: its visuals' model queries run as them (their row rules and masks) but only inside the report's **envelope**: the fields,
measures and filterable fields its visuals, slicers, drills, filters, bookmarks and links can produce. Anything else, such as a carried filter the report
never makes, is refused (captured):

```
POST /api/v1/bi/reports/file:report:lang-guarded-report/query   (hana: a viewer of the report, no read on lang-guarded)
     {"page": "main", "visual": "by-desk", "state": {"carry": [{"field": "trades.currency", "op": "eq", "values": ["USD"]}], ...}}
404 {"code": "DRS-9151", "path": "where[0].field", "detail": "DRS-9151 this report cannot ask that: it does not filter on 'trades.currency'", ...}
```

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/v1/bi/reports/{id}/envelope` | what the report's readers without read on its model may ask (any reader of the published report may get it; `GET /reports/{id}` opens for them too, its `dataset` the model described within this envelope, `envelope: true`): `{consumer, fields, measures, filters}` (captured: `{"consumer": "file:report:lang-guarded-report", "fields": ["trades.desk"], "measures": ["Notional"], "filters": ["trades.desk"]}`) |
| `GET` | `/api/v1/bi/models/{id}/impact?target=<field or measure>` | the consumers whose envelopes use it: `{model, target, consumers: [{kind, id, title, status, owner, workspace}], unknown: [...]}` (`unknown`: consumers with no envelope, such as alerts) |

**Breaking changes** (wave A, full): publishing a model needs a new major version (`409 DRS-9126`) when a measure is removed or **changes meaning** (its
expression compiles to another tree, or its currency or additivity changes; a reformatting or the converter's DEL v2 text does not count), a field
consumers could use is removed or retyped, a relationship is removed or made inactive, a dataset leaves the model, or a dataset's grain changes.

**Lineage** (wave A): `GET /api/v1/bi/lineage/{ref}` (a report id, a model reference, or `dataset:<name>`): report to model to each dataset by alias,
down its `extends` chain to its root (a pack kind and its connector, a connector query, a Python item, another model, a SQL view), as far as the caller may
read; `problems` lists a root that cannot be traced (`DRS-9110`). Example in [DATASETS.md section 14](DATASETS.md#14-derivation-subset-filter-grain-derive-group-22-wave-a).

**Check and the security safeguard.** Security binds to each dataset: a rule on trades no longer hides another dataset of the model. So that this is never a surprise,
Check flags every dataset that no row rule restricts while a role is restricted elsewhere in the same model (`DRS-9130`, a warning in Check) and publishing the model is
refused (`409 DRS-9130`) until the dataset gets a rule or the model says `may-see-all: { <alias>: [<role>] }` under `security` (the publish records it, audited
`bi-model-may-see-all`). Captured (a second dataset added without a rule):

```
POST /api/v1/bi/models/validate   {"yaml": "...the desk-trading model with a second dataset desks..."}
{"kind": "model", "name": "desk-trading-2", "valid": true, "exposures": [{"role": "desk-head", "dataset": "desks"}],
 "problems": [{"level": "warning", "code": "DRS-9121", "path": "datasets.desks", "message": "'desks' has no relationship to the other datasets: ..."},
              {"level": "warning", "code": "DRS-9130", "path": "security", "message": "the role 'desk-head' is restricted elsewhere in this model, but no row rule restricts 'desks'
               (pack.trading.trade): that role sees every one of its rows. Add a rule for it on 'desks', or say so with `may-see-all: { desks: [desk-head] }` under security;
               until then the model cannot be published"}]}
```

Publishing a model or a `rachana: 2` dataset also refuses a breaking change without a new major version (`409 DRS-9126`: a measure, column or relationship removed by
its stable id, a relationship made inactive, a grain key changed). Saving one writes the stable ids it lacks into its YAML (`id: md_…`, `c_<column>`, `m_<measure>`,
`r_<n>`; existing ids are never changed); saving a generated dataset is `409 DRS-9107`.

**A standing query** (captured): `POST /api/v1/bi/mq/subscribe {"query": {"mq":1, "model":"desk-trading@1", "select":{"measures":["Trades"]}}, "throttle":"1s", "deliver":"value"}`
answers `{"subscription": "sq_29e4946cc8662be8", "throttle": "1000ms", "deliver": "value", "answer": {...the answer, "rows": [[60]]...}, "cursor": "g-0"}`; its stream
(`GET /api/v1/bi/mq/stream?ids=sq_29e4946cc8662be8`) opens with `event:status data:{"subscriptions":["sq_29e4946cc8662be8"]}` and, while the data does not move,
says every five seconds `event:status data:{"throttle":"1000ms","state":"live","cursor":"g-0","subscription":"sq_29e4946cc8662be8"}`; `DELETE
/api/v1/bi/mq/subscribe/sq_29e4946cc8662be8` answers `204` and the stream ends. In W1 a subscription is evaluated again, in full and as its owner, when a dataset of its model changes data
generation (a load, a refresh, a live batch); the incremental rules are wave W3.

**Tokens.** `POST /bi/mq`, `/bi/models/{id}/query`, `/bi/mq/validate`, `/bi/models/validate` and `/bi/mq/subscribe` are reads (`token-read-posts`); ending a subscription
(`DELETE /bi/mq/subscribe/{id}`) needs `bi:write`. Every model query is audited: `bi-mq` for a model, `bi-query` for a version-1 file.

## Expressions: `/api/v1/bi/expr`

Release 2.2 wave A ([EXPRESSIONS.md](EXPRESSIONS.md), [BI_DESIGN.md §3.3](../architecture/BI_DESIGN.md#33-del-the-drishti-expression-language-python-like)): the
Drishti expression language, DEL v2 (Python-like), has **one parser, on the server**; the console's expression editor completes from the catalogue and
checks through `validate`, it never parses for meaning. All three need the `bi` power; none reads data or stores anything (the two POSTs are open to a
read-only token).

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/v1/bi/expr/functions` | the function catalogue (`?scope=row\|rule\|aggregate` keeps the functions allowed there): each function's `name`, `family`, `signature`, `params` (`name`, `type`, `default`, `keywordOnly`, `variadic`), `returns`, `scopes`, `status` (`built` or the wave that builds it), `since`, `legacy` (its old name), `description`, `examples` (`expr`, `says`), `edgeCases`; plus `keywords`, `operators`, `literals`, `scopes` and `legacy` (the `drishti.bi.expr.legacy` mode) |
| `POST` | `/api/v1/bi/expr/validate` | body `{expr, scope?, model?, yaml?, alias?}`: checks one expression. `scope` is `row`, `rule` or `aggregate` (default); `model` a model reference (`name@major`) or `dataset:<name>` — the fields and measures are the model's **as the caller sees them** (hidden columns absent, masked ones marked); `alias` the dataset whose row is in context in row and rule scope (default: the only one). Without `model`, only the language and the catalogue are checked. `yaml` instead of `model`: the designer's **unsaved** model (`model:`) or `rachana: 2` dataset (its implicit model), its datasets resolved as published, read as the caller; the answer has `draft: true`. A measure on a model is also compiled the way publishing would, and planned the way a model query would be: a measure whose aggregates join facts with no path between them is `DRS-9156` while it is written. Always `200` |
| `POST` | `/api/v1/bi/expr/convert` | body `{expr}`: the expression in DEL v2 (`changed: false` when it already was); or `{yaml}`: a whole item (dataset, model, report, board, notebook) with every expression converted and `expressions: 2` set, comments and layout kept, with `changes` (`path`, `from`, `to`) and `problems` (expressions it could not convert, `DRS-9190`, left as they were) |

The validate answer (captured; `rita` on the test model `lang-model`):

```json
{"expr": "sum(trades.notional, where=trades.desk == 'DESK-A') / measure('m_trades')", "scope": "aggregate", "type": "decimal",
 "uses": {"functions": ["sum", "measure"], "fields": ["trades.notional", "trades.desk"], "measures": ["Trades"]},
 "syntax": "del2", "valid": true, "problems": [],
 "model": {"id": "file:model:lang-model", "name": "lang-model", "version": "1.0.0", "implicit": false, "compat": false}}
```

A problem has `code`, `level` (`error` or `warning`), `message`, `fix` (or null) and `at` (`start`, `end`: character offsets into `expr`), so the editor
underlines it and offers the fix as a button (captured):

```json
{"expr": "count(trades, where=trades.mtm < 0)", "scope": "aggregate", "type": "integer", "syntax": "del2", "valid": false,
 "problems": [{"code": "DRS-9161", "level": "error", "message": "'trades.mtm' is masked for you: a filter on it (<) is refused",
               "fix": "filter on a field that is not masked", "at": {"start": 20, "end": 30}}], ...}
{"expr": "[Notional] / [Trades]", "scope": "aggregate", "type": "decimal", "syntax": "legacy", "converted": "measure('Notional') / measure('Trades')", "valid": true,
 "problems": [{"code": "DRS-9100", "level": "warning", "message": "old expression syntax (read until the end of wave A, then refused): run the converter",
               "fix": "write: measure('Notional') / measure('Trades')", "at": {"start": 0, "end": 21}}], ...}
```

`syntax` is `del2` or `legacy` (the old syntax: refused with `DRS-9100` and `valid: false` by default, with `converted` holding the DEL v2 text; while a
site sets `drishti.bi.expr.legacy: warn` it is a warning and the rest of the check runs on the converted text). The codes are in [EXPRESSIONS.md section 7](EXPRESSIONS.md#7-problems-and-their-fixes). The CLI:
`drishti.py bi expr functions|validate|convert` and `drishti.py bi migrate --expressions FILE... [--write] [--check]`
([CLI_GUIDE.md](CLI_GUIDE.md)).

## BI migrations: `/api/v1/bi/migrate`

Release 2.2 wave A ([BI_MODEL_SPEC.md section 8](../architecture/BI_MODEL_SPEC.md#8-migration)): migrations of the server's **stored** BI items. The
server runs them once at start (`drishti.bi.migrate.on-start`, [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibimigrate--migrations-of-stored-bi-items));
these endpoints let a **bi-admin** see what would change and run them again. Every run is idempotent and audited; the text before stays in each item's
history; an item that cannot be converted is never changed and is listed in `problems`.

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/v1/bi/migrate/expressions` | body `{dryRun?}`: converts every stored item whose expressions are in the old syntax to DEL v2 (draft and published text; the published one becomes the next version). Answer: `step`, `dryRun`, `scanned`, `converted` (or `wouldConvert`), `problems` (a count) and `items`: per item `id`, `title`, `kind`, `workspace`, `status`, `state` (`converted`, `would convert`, `problem`), `draft` and `published` (the changes: `path`, `from`, `to`), `problems` (`path`, `from`, `problem`), and after a run `before` (the saved state holding the text before) and `publishedVersion` |
| `POST` | `/api/v1/bi/migrate/models` | body `{dryRun?}`: the stored `rachana: 1` datasets to datasets and models and their reports rebound ([BI_MODEL_SPEC.md 8.3.1](../architecture/BI_MODEL_SPEC.md#831-as-built-22-wave-a-task-6)); restricted roles are never widened (`CLOSED`). Answer: `step` (`models`), `dryRun`, `scanned`, `converted` (or `wouldConvert`), `problems`, `items` (per item `key` (its id), `kind`, `name`, `state`, `became`, `outputs`, `changes`, `notes`, `manual`, `problem`; a converted dataset also `created`, the ids of its new dataset items) and `report` (the plan in Markdown) |
| `POST` | `/api/v1/bi/migrate/models/convert` | the converter over files, nothing stored (`bi` power; what `drishti.py bi migrate --models DIR` sends): body `{files: [{path, yaml}], exposure?: note\|closed}` with paths under the BI folder (`datasets/trading-risk.dataset.yaml`). Answer: `writes` (path to text), `removes` (old dataset files), `items`, `converted`, `problems`, `report` |
| `POST` | `/api/v1/bi/migrate/boards` | (2.4.0) body `{dryRun?}`: the stored 2.3 boards to reports with board pages ([BOARDS.md section 12](BOARDS.md#12-from-a-23-board-to-a-board-page)); the id is kept. Answer: `step` (`boards`), `dryRun`, `scanned`, `converted` (or `wouldConvert`), `problems` (a count), `items` (per board `id`, `title`, `kind`, `workspace`, `status`, `state`; after a run `before` and `publishedVersion`) |
| `GET` | `/api/v1/bi/migrate/problems` | the stored items a migration left as they were: `count` and `problems` (`step`, `item`, `title`, `kind`, `where`, `problem`, `since`). The same list is `migrationProblems` in `GET /bi/settings` and, for one item, in `GET /bi/items/{id}` |

The problems answer (captured):

```json
{"count": 1, "problems": [{"step": "expressions", "item": "it-broken", "title": "it-broken", "kind": "dataset", "where": "measures.Per.expr",
  "problem": "not converted, the item keeps its text: DRS-9127 expected ')' to close the call of sum(), but the expression ends there (sum(trades.mtm)",
  "since": "2026-10-13T02:35:52.717930195Z"}]}
```

## Drishti SQL: `/api/v1/bi/sql`

Release 2.2 wave A ([SQL.md](SQL.md), [BI_DESIGN.md §3.4](../architecture/BI_DESIGN.md#34-drishti-sql-dsql-read-only-sql-over-models)): one read-only
statement over one model, as the caller. An MQ-shaped statement is exactly one model query (same plan hash, same cache entry as the MQ); a composite one
(`WITH`, `UNION`, a subquery, a window over the answer) runs its model queries as the caller and the rest over their answers. Both need the `bi` power and
read on the model; neither changes anything (open to a read-only token). Every statement is audited as `bi-sql` with its text.

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/v1/bi/sql` | body `{model, sql, format?}`: `model` is `name@major` or `dataset:<name>`; `format` `json` (default), `csv` or `arrow`. The answer: `sql: 1`, `model`, `columns` (each with `name`, `type`, `masked`, and for an MQ-shaped statement `kind`, `id`, `source`: the model query's column), `rows`, `rowCount`, `meta` (`shape`: `mq` or `composite`; for `mq` the model query's meta: `query`, `plan`, `signature`, `cached`, `warnings`, `asOf`, ...; for `composite` `leaves`, the leaves' plan hashes). `EXPLAIN SELECT ...` answers `{explain: true, shape, plan, signature, queries, outer (composite), tree, text}` instead |
| `POST` | `/api/v1/bi/sql/validate` | body `{model, sql}`: `{valid, shape, queries, columns: [{name, source, kind, type}] (type: a field's column type; a count, `count()` or `distinct()` an `integer`, any other measure a `decimal`), plan, signature, problems: []}` without reading data; or `valid: false` with `problems` (`code`, `message`, `fix`, `at: {start, end}`). Always `200` |

A refusal is `422` with `code` (`DRS-9170` to `DRS-9179`, or the model query's own), `path: "sql@<offset>"` and `fix: {at, write}` (captured):

```
POST /api/v1/bi/sql {"model": "lang-model@1", "sql": "SELECT trades.desk FROM trades JOIN desks ON trades.desk = desks.x"}
422 {"title": "sql name", "status": 422, "code": "DRS-9171", "path": "sql@36", "fix": {"at": 36, "write": "FROM trades"},
     "detail": "DRS-9171 no table 'desks' in this model: its tables are [trades]", "problems": [...]}
```

The answer of an MQ-shaped statement and of a composite one are shown in [SQL.md sections 3 and 4](SQL.md#3-how-a-statement-compiles). The CLI:
`drishti.py bi sql "SELECT ..." --model M [--explain] [--format table|json|csv]`.

## Reports: `/api/v1/bi/reports` (phase 3)

Phase 3 of Rūpaka ([BI_GUIDE.md](BI_GUIDE.md) sections 14 to 16, [REPORTS.md](REPORTS.md), [tutorial 10](../tutorials/10-first-bi-report.md)). A report is a BI item of kind `report` (id `bi-<hex>`, or `file:report:<name>` for a shipped file). Who may read the item is decided as for
any item (`404 DRS-9001` otherwise); what a visual shows is the dataset engine's answer for the caller (their row rules and masks). A personal token reaches the reads and the queries (they change nothing).

| Method | Path | What it does |
|---|---|---|
| `GET` | `/bi/reports/{id}` | the report for this caller: `{id, title, description, status, shipped, sample, valid, problems[{level, code, path, message}], report, dataset, mayEdit}`; `report` is the model (the YAML's structure with defaults filled in), `dataset` the model of the dataset it reads (tables with columns, measures, hierarchies, relationships; no row rules); when visuals name datasets of their own (`dataset:`), `datasets` maps each such name to that dataset's model, and `report.links` are the fields declared to mean the same across datasets ([REPORTS.md section 16](REPORTS.md#16-reports-over-several-datasets)) |
| `POST` | `/bi/reports/{id}/query` | one visual's data: body `{page, visual, state}`; the answer is the dataset query's JSON answer plus `highlight`, `visual`, `query` (what was asked) and `mq` (the model query it ran as), `limit` (the rows it asked for; a `state.limit` above the visual's own raises it to at most 5,000: "Show all"), `period` (a time series asked by `week`, `month`, `quarter` or `year` so its whole range fits; the axis column keeps its name) or `truncated: true` with `total` (the rows in all, at most 50,000, `totalMore` past it), and, over a dataset with a live table, `live` (phase 5, below); `?explain=true` adds the SQL for BI authors; `?format=arrow` answers an Apache Arrow IPC stream (a Perspective visual's rows, masked for the caller) with `X-Bi-Rows`, `X-Bi-Masked` and `X-Bi-Live` headers |
| `GET` | `/bi/reports/{id}/live` | **SSE** (phase 5): the report's live tables as they change: `hello`, `tick`, `status` (below); `429 DRS-9051` when too many streams are open |
| `POST` | `/bi/reports/query` | the same for a draft (needs `bi-author`): `{report \| yaml, page, visual, state}` |
| `POST` | `/bi/reports/validate` | `{yaml, quality?}` (needs `bi-author`): `{valid, problems[], report}`; `quality: true` adds the accessibility, layout checks |
| `POST` | `/bi/reports/format` | `{report: {...}}`: the model written as YAML the way a person writes it: `{yaml}` |
| `GET` | `/bi/reports/catalogue` | `{visuals: {type: {group, wells: {name: {kind, required, max}}}}, reserved[], slicerModes[]}`; since wave 3 the visuals include `bubble`, `funnel`, `histogram`, `box` and `map`, and `reserved` is `entity-view`, `shape`; phase 6 adds the `python` visual (group `python`); 2.4.0 adds the `tile` visual (group `board`), the `split` well of bar, column, line and area, and `families` (each type's format family) |
| `GET` | `/bi/reports/format-schemas` | (2.4.0) the typed format of each visual family: `types` (type to family), `families` (per family its `types`, `order` of sections and `properties`, every `$ref` resolved, each with `type`, `enum`, `minimum`, `maximum`, `x-label`, `x-hint`, `x-group`, `x-hook`, `x-control`, `x-types`) and `schemas` (the files as written) ([REPORTS.md section 5](REPORTS.md#5-format)) |
| `GET` | `/bi/themes` | (2.4.0) the theme items you may use: `[{id, name, title, palette, background, shipped, status}]` (published ones and your own drafts) |
| `GET` | `/bi/themes/{name}` | one theme (`rachana, theme, version, title, palette, background, foreground, font, good, bad, visuals`); `404 DRS-9001` when there is none |
| `POST` | `/bi/themes/validate` | `{yaml}` (needs `bi-author`): `{valid, problems[], theme}`; a bad palette, colour, font or visual default is `DRS-9036` ([REPORTS.md section 5.2](REPORTS.md#52-themes)) |
| `GET` | `/bi/reports/dataset?ref=` | (needs `bi-author`) the model of the dataset a report names (a catalogue id, a file name or the dataset's name): what the designer's Fields pane shows |
| `POST` | `/bi/reports/{id}/python` | (phase 6) body `{page, visual}`: what the console's runner runs for one python visual: `{id, title, version, status, draft, manifest, measure, params, run, visual}`; `manifest` holds the Python item as the caller reads it (the published version, or the draft for those who edit it) with its `source`; `measure` is `{name, inputs, by}` for a Python measure or null. A read: on the token read allow-list. `422 DRS-9035` (no such python visual), `DRS-9070` (the item is not valid) |

**The state** of a visual query is what the person has done on the page: `{"slicers": {"currency": ["USD"], "notional": {"from": 1000000, "to": 3000000}}, "selections": [{"visual": "by-desk", "field": "trade.desk", "values": ["DESK-FX"], "highlight": false}],
"drill": ["DESK-FX"], "carry": [{"field": "counterparties.name", "op": "in", "values": ["Orbital"]}], "dates": {"from": "2026-10-01", "to": "2026-10-08"}, "rows": true, "limit": 200}`. Slicers are by the slicer's visual id (a list, or `{from, to}`); selections are the marks selected on
other visuals (`highlight: true` asks a bar or column chart for the highlighted answer as well); `drill` is the values clicked down the visual's hierarchy; `carry` is what a drill-through or a bookmark carries; `rows` shows the rows behind the visual
(`true`, or `{field, values}` for one mark). Anything wrong is `422 DRS-9035`.

Captured from a real server (the bar of *Trades overview*; `rita` holds the raw power):

```
POST /api/v1/bi/reports/file:report:trades-overview/query   {"page": "overview", "visual": "by-desk", "state": {"selections": [], "drill": []}}
200  {"columns": [{"name": "trade.desk", "type": "string", "masked": false}, {"name": "MTM", "type": "decimal", "masked": false}],
      "rows": [["DESK-RATES", 161766771.0], ["DESK-COMM", 16954390.0], ["DESK-STRUCT", 15784130.0], ...], "rowCount": 10,
      "meta": {"layer": "columns", "rows": 10, "millis": 0.0, "cached": true, "dates": ["2026-09-30"], "tables": ["trade"], "masked": [], "warnings": []},
      "highlight": null, "visual": "by-desk",
      "query": {"fields": ["trade.desk"], "measures": ["MTM"], "filters": [], "orderBy": ["MTM desc"], "limit": 500}}
```

**True totals and distributions (wave 3).** The answer of a **matrix** with `format.subtotals` or a **table** with `format.totals` carries `totals`: `[{by: [fields], columns: [names], masked: [names], rows: [[...]]}]`, one entry per level, for the measures that do not add up
(a ratio, an average, a distinct count, a percentile, a minimum, a maximum, a time-intelligence measure), worked out by the engine as the caller ([REPORTS.md section 3.2](REPORTS.md#32-true-totals) has a captured answer). A **histogram** answers with the columns `bin`, `count`, `from`, `to` and a **box plot** with the category, `min`, `q1`, `median`, `q3`, `max`, `count`, `outliers`: the server
asks the engine for the measure of every member of the axis (at most `drishti.bi.report.distribution-rows`) and bins or summarises it, so `meta.members` says how many it read; a masked measure has no rows and `meta.masked` names it. A **map**'s answer is an ordinary one: the location (or latitude and longitude) columns and the measure. Nothing here is a new endpoint
and nothing a token cannot already do: these are the existing reads (`POST /bi/reports/{id}/query` is on the token read allow-list).

**Live reports (phase 5).** A report over a dataset with a `mode: live` table ([DATASETS.md](DATASETS.md#71-live-tables-phase-5), [REPORTS.md](REPORTS.md#13-live-visuals-and-perspective-phase-5)) has a stream, `GET /bi/reports/{id}/live` (`Accept: text/event-stream`; with security on, the caller needs the right to read the report):

| Event | Data |
|---|---|
| `hello` | the live state when the stream opens: `{generation, lastUpdate, state, at, tables: [...]}`; `state` is `live`, `stale`, `starting`, `failed`, `closed` or `none` (a report with no live table: the stream ends after it) |
| `tick` | the same, whenever the tables changed, at most one per `drishti.bi.live.frame` (250 ms): the **latest** state, so a slow client skips ticks and never queues them. It carries **no data**: the client asks its visuals again, as itself |
| `status` | the same, every `drishti.bi.live.status` (5 s) when nothing changed or the state changed; also the heartbeat |

Each entry of `tables` is `{table, kind, state, rows, entities, subscribed, pushes, seq, epoch, generation, lastUpdate, lastSeen, startedAt, applied, skipped, evicted, deleted, error}`: `seq` is the table's change counter (`updateSeq` of its newest row), `epoch` grows when rows leave it,
`generation` grows with every change. Captured from a real server (the demo source ticking its 360 live trades of 750, a batch about twice a second; `curl -N` shows the same):

```
GET /api/v1/bi/reports/file:report:live-trading-desk/live
event:hello  data:{"generation":95,"lastUpdate":"2026-10-11T11:50:03.684098666Z","state":"live","at":"2026-10-11T11:50:04.732755792Z","tables":[{"table":"trade","kind":"trade","state":"live","rows":750,"entities":750,"subscribed":360,"pushes":true,"seq":33503,"epoch":0,"generation":95,...}]}
event:tick   data:{"generation":96,"lastUpdate":"2026-10-11T11:50:04.545007543Z","state":"live",...,"tables":[{"table":"trade",...,"seq":33863,"epoch":0,"generation":96,...}]}
```

A JSON answer over such a dataset also carries `live` (the same object, read **before** the query ran, so asking "since `seq`" misses nothing): `"meta": {"layer": "live", ...}`, `"live": {"generation": 99, "state": "live", "tables": [{"table": "trade", "seq": 34943, "epoch": 0, ...}]}`.
The state's `since` (a number) asks for only the rows whose `updateSeq` is above it (2.1 Perspective visuals read this way; since 2.3.0 a `perspective-*` visual is drawn as Drishti's grid from the JSON answer). A read with `?format=arrow` names the columns as the table names them, plus `id` and, for a live table, `updateSeq`. Captured on the demo lakes (the
administrator; for a reader without `raw` the `TRADER_ID` values are `•••` in the bytes and `X-Bi-Masked` names the column):

```
POST /api/v1/bi/reports/file:report:live-trading-desk/query?format=arrow   {"page": "grid", "visual": "all-trades", "state": {"limit": 3}}
200  X-Bi-Rows: 3   X-Bi-Live: {"generation":10,"state":"live","tables":[{"table":"trade","kind":"trade-valuation","rows":2000,"seq":2000,...}]}
     columns: TRADE_ID, DESK, PORTFOLIO, PRODUCT_TYPE, CURRENCY, BUY_SELL_CODE, TRADER_ID, NOTIONAL, MTM, PNL_1D, id, updateSeq
     ["1000001", "DESK-COMM", "BOOK-COMM-2", "ComSwap", "USD", "S", "TRDR-FROSSI", 167969743.2, 4121280.0, -277804.0, "TVAL-1000001", 1]
     ["1000002", "DESK-COMM", "BOOK-COMM-1", "ComSwap", "USD", "B", "TRDR-TBRENNAN", 22128720.0, 349620.0, -22270.0, "TVAL-1000002", 2]
POST ...   {"page": "grid", "visual": "all-trades", "state": {"since": 1990}}   the rows whose updateSeq is above 1990
```

A live table in `GET /bi/datasets/file:dataset:live-trading/layers` (captured): `{"table": "trade", "mode": "live", "partitions": [], "live": {"kind":
"trade-valuation", "state": "live", "rows": 2000, "entities": 2000, "subscribed": 2000, "pushes": true, "seq": 2000, "epoch": 0, "applied": 2000,
"error": "following the first 2000 trade-valuation entities of more (drishti.bi.live.follow-max)", "aggregates": [{"rollup": "byDeskBook", "groups": 32,
...}], ...}}`, then the `history` table (`every: PT10S`, `keep: 360`). The `error` there is a note, not a failure: the table follows at most
`drishti.bi.live.follow-max` (2,000) entities of the demo lakes' 14,849 trade valuations.

Refusals are the standard problem body with the code: `422 DRS-9030` to `9032` (the report), `501 DRS-9033` (not built), `422 DRS-9034` (the dataset), `422 DRS-9035` (the state), and every dataset query code (`9020` to `9027`, `403 DRS-9021` for a masked column,
`422 DRS-9020` when a row rule makes a visual unanswerable). Every query is audited like a dataset query. From the console the calls are under `/bi/api/reports/...`; the console's page asks three queries at a time (the server allows three per person) and retries a `429`.

**Python items (phase 6, [PYTHON_BI.md](PYTHON_BI.md)).** A Python item is a BI item of kind `python` (the ordinary item calls above), so it is versioned, reviewed and published by them; publishing checks its manifest and refuses a bad one (`422 DRS-9070`).
The server never runs Python; these calls tell the console's runner what to run, and the console's routes run it:

| Call | Needs | What it does | Token rule |
|---|---|---|---|
| `POST /api/v1/bi/python/validate` | `bi` | body `{yaml}`: `{valid, problems[{level, code, path, message}], manifest, packages}`: every problem in a manifest, nothing stored | non-GET, on `token-read-posts` (a read: a personal token may call it) |
| `POST /api/v1/bi/python/input` | `bi` | body `{input, limit?, asOf?}`, `input` a Python dataset's input (a model query, or a pack kind read through a connector `{kind, pack?, connector?, each?, fields?, where?, limit?}`, 2.4.0): that input's rows **as the caller** (the kind's entitlements, row rules and masks), cut to `limit` (1,000) and `drishti.bi.transform.max-input-rows`; `?format=arrow` answers Arrow IPC with `X-Bi-Rows`, `X-Bi-Masked`, `X-Bi-Model`, `X-Bi-As-Of`, else `{model, kind, columns, rows, rowCount, masked, asOf}`. `DRS-9108` a bad input (with its path), `DRS-9102` a kind no pack declares, `DRS-9026` a connector not running ([PYTHON_BI.md section 17.1](PYTHON_BI.md#171-inputs-straight-from-a-connector)) | non-GET, on `token-read-posts` (a read) |
| `GET /api/v1/bi/python/{ref}` | `bi`, and the right to read the item | the item (catalogue id, shipped file name or title) as the caller reads it: `{id, title, version, status, draft, manifest` (the manifest now carries `tests`)`; `404 DRS-9001` | GET: read |
| `POST /api/v1/bi/reports/{id}/python` | `bi`, and the right to read the report | body `{page, visual}`: what to run for one python visual (see the reports table above) | non-GET, on `token-read-posts` (a read) |
| `GET /bi/python/status` (console) | signed in | the runner: `{enabled, ready, missing, workers, running, packages, installed, calls, failures, isolation, browser}` | the console's own route: session cookie, no token |
| `POST /bi/python/run` (console) | signed in; the server decides access | body `{report, page, visual, state}`: runs the visual's Python item on the server, on the rows the server builds for this person; answers `{kind: measure, by, rows}`, `{kind: echarts, option}`, `{kind: svg, svg}` or `{kind: png, png}` plus `ms`, `inputRows`, `masked`, `item`, `version`, `measure`, `target`; a failure is `{code, detail}` (+ `traceback` for `bi-author`) with `422 DRS-9071`, `403 DRS-9073`, `413`/`504 DRS-9072`, `503 DRS-9074` | JSON from the console's own pages only: `Origin`/`Referer` same-origin and `application/json`, else `403 DRS-5002` / `DRS-5001`; no personal token (the console's session cookie) |
| `POST /bi/python/check` (console) | signed in; the server decides access | body `{yaml[, tests]}`: the server's manifest checks plus the console's source checks (parses, defines its entry, imports only what it lists), and **the item's `tests:` run in the sandbox** (`tests: false` skips them): `{valid, problems, manifest, tests: {total, passed, failed, errors, ok, results[{name, status: pass \| fail \| error, message}]}}` | as `/bi/python/run` |
| `POST /bi/api/items/{id}/publish`, `.../approve` (console) | as the server's | for an item of kind `python` that carries `tests:`, the console runs them first and answers **`DRS-9076`** (422) when one fails or cannot run, before the call reaches the server; `{force: true}` passes it for a bi-admin. A call made straight to the server's `/api/v1/bi/items/{id}/publish` is not gated (the sandbox is the console's) | the server's, plus `DRS-9076` |
| `GET /bi/explore` (console) | signed in with the `bi` power | the Analyse page ([ANALYSE.md](ANALYSE.md)); `?item=<notebook id>` opens a saved notebook | GET: a page |
| `GET /bi/explore/status` (console) | signed in | `{limits, python{...runner status, browser, mayRun}, quant{engine, instruments}}` | GET: read |
| `POST /bi/explore/extract` (console) | signed in; the server decides what rows | body `{dataset, fields[, filters, dates]}` or `{report, page, visual, state}`: the reader's masked rows, cut to `bi.explore.max_rows`: `{columns[{name, type, masked}], rows, rowCount, total, truncated, masked}`; `422 DRS-9070` | the console's own route: same-origin JSON and the session cookie, else `403 DRS-5002` / `DRS-5001`; no personal token. Reads through the server's dataset query (`token-read-posts`) |
| `POST /bi/explore/sql` (console) | signed in | body `{extract: {...as above}, extracts?: [{name, dataset, fields}], sql}`: one read-only `SELECT` over the table `extract` and a table for each further extract, named as it is: `{columns, rows, rowCount, truncated, ms, inputRows, masked}`; `403 DRS-9073` (not a plain SELECT), `422 DRS-9070`, `413`/`504 DRS-9072` (text, time) | as `/bi/explore/extract` |
| `POST /bi/explore/python` (console) | signed in with `bi-author` | body `{extract, extracts?: [{name, dataset, fields}], source[, params]}`: runs one Python cell in the sandboxed worker with `df`, `extract("name")` (any further extract of the notebook, as the same person sees it) and `quant`: `{kind: cell, stdout, table \| option \| svg \| png \| value, ms, inputRows, masked, target}`; `403 DRS-9073`, `422 DRS-9070` / `DRS-9071` (+ `traceback`), `413`/`504 DRS-9072`, `503 DRS-9074` | as `/bi/explore/extract` |
| `POST /bi/explore/notebook/check` (console) | signed in | body `{yaml}` or `{notebook}`: `{valid, problems, cells, notebook, yaml}`: the page's way to read and write a `notebook` item's body | as `/bi/explore/extract` |
| `POST /bi/explore/promote` (console) | signed in | body `{to: measure \| visual, name, source, columns}` (a Python item draft), `{to: transform, name, source, extracts}` (a Python Transform draft from a cell that joins extracts) or `{to: report, name, dataset, slice, filters?}` / `{to: report, name, dataset, sql}` (a report visual, with `columns` and `qualified` to map the extract's names to the dataset's, and `target` and `page` to add it to an existing report draft you may edit): `{kind, title, yaml, notes, target, visual}`; nothing is stored, the page saves the draft with `POST /api/v1/bi/workspaces/me/items` (new) or `PUT /api/v1/bi/items/{id}` (existing) ([ANALYSE.md section 14](ANALYSE.md#14-promote-to-a-report-visual)) | as `/bi/explore/extract` |
| `POST /bi/explore/profile` (console) | signed in | body `{extract: {...as above}}`: the extract's profile, worked out in the console's sandbox (no code of yours), `{kind: profile, rows, columns: [{name, type, looksLike?, nulls, nullPct, distinct, unique, min?, max?, mean?, top, histogram?}], inputRows, masked, ms}` (2.4.0, [ANALYSE.md section 15](ANALYSE.md#15-profile)) | as `/bi/explore/extract` |
| `GET /bi/transform` (console) | signed in with the `bi` power | the Transform editor ([PYTHON_BI.md section 17](PYTHON_BI.md#17-data-prep-the-transform-editor-240)); `?item=<python item>&dataset=<dataset item>` opens saved drafts | GET: a page |
| `GET /bi/transform/snippets`, `POST /bi/transform/snippet` (console) | signed in | the snippets library; body `{id, params, inputs}`: the step a snippet makes, `{label, code, snippet}`, each value written as a literal (`DRS-9070` a bad value) | read |
| `POST /bi/transform/compose`, `POST /bi/transform/parse` (console) | signed in | body `{spec}`: `{spec, source, python, dataset}` (the Python item's and the Python dataset's YAML); body `{python, dataset?}`: `{spec}` (YAML read back as steps) | read |
| `POST /bi/transform/steps` (console) | signed in with `bi-author` | body `{spec, asOf?}`: each input asked of the server as you (`/api/v1/bi/python/input`, at most `bi.transform.preview_rows`), then the step-by-step run in the sandbox: `{inputs: [{name, rows, columns, head, profile, masked, asOf, cut}], keys, steps: [{index, label, first, last, rows, columns, head, profile, ms, variable}], planned, complete, failed?: {index, label, line, error, traceback}, askedMs, ms, source}` | as `/bi/explore/python` |
| `GET /pyconsole` (console) | signed in with the `bi` power | the browser Python console ([ANALYSE.md section 16](ANALYSE.md#16-the-browser-python-console)); not a BI page, the strict policy (Calc's worker compiles the WebAssembly) | GET: a page |
| `POST /pyconsole/extract` (console) | signed in | body `{extract: {dataset, fields, dates?}}`: the reader's masked rows for the browser, cut to `bi.pyconsole.max_rows`, dates as text | as `/bi/explore/extract` |
| `POST /pyconsole/promote` (console) | signed in with `bi-author` | body `{extract, code, title}`: the code run on the console backend over the extract rebuilt for you, kept as a notebook draft: `{answer, item: {id, href, page}, inputRows, masked, target: server}`; a failing code is not kept | as `/bi/explore/python` |

```
POST /bi/api/reports/file:report:python-desk-risk/python   {"page": "risk", "visual": "histogram"}                    (as rita)   200
  {"id": "file:python:pnl-histogram", "title": "P&L histogram", "version": 1, "status": "published", "draft": false, "params": {"bins": 24}, "run": "auto", "measure": null,
   "manifest": {"name": "pnl-histogram", "interface": "visual", "entry": "render", "inputs": ["pnl1d"], "packages": ["numpy"], "target": "both", "timeout": 10, "memory": 256, "output": "echarts", ...}}
POST /bi/python/run   {"report": "file:report:python-desk-risk", "page": "risk", "visual": "volatility", "state": {}}                  (as rita)   200
  {"kind": "measure", "by": ["desk"], "rows": [["DESK-EQD", 5803.810000880093], ["DESK-FI", 5803.810000880093], ["DESK-FX", 5803.810000880093]], "ms": 283, "measure": "Desk P&L volatility", "target": "server", "inputRows": 60, ...}
```

## Boards: `/api/v1/bi/boards` (phase 5)

Phase 5 of Rūpaka ([BOARDS.md](BOARDS.md) is the reference). A board is a BI item of kind `board` (id `bi-<hex>`, or `file:board:<name>` for a shipped file); who may read it is decided as for any item. A board holds no data: each tile names a report and a visual, and its data and its live stream are the
report's (`POST /bi/reports/{id}/query`, `GET /bi/reports/{id}/live`).

| Method | Path | What it does |
|---|---|---|
| `GET` | `/bi/boards/{id}` | `{id, title, description, status, shipped, sample, valid, problems[{level, code, path, message}], board, tiles, mayEdit}`; `board` is the model (pages, tiles with `at` and `alert`, `rotate`), `tiles` maps `"<page id>/<tile id>"` to `{report, reportTitle, page, type, live}` (`report` is the item id the tile resolved to for **this caller**, or null) |
| `GET` | `/bi/boards/reports` | (needs `bi-author`) the reports a tile can name: the published ones you may open and your own drafts, `[{id, title, draft, status}]`. A board that names a draft report cannot be published or proposed (`DRS-9052`) |
| `POST` | `/bi/boards/validate` | `{yaml}` (needs `bi-author`): `{valid, problems[], board, tiles}`: the structure and what each tile names inside its report (`tiles` maps `"<page>/<tile>"` to `{report, reportTitle, page, type}`) |
| `POST` | `/bi/boards/convert` | (2.4.0) `{yaml}` of a 2.3 board (needs `bi-author`): `{converted: true, yaml}`, the report with board pages it becomes (nothing is stored), or `{converted: false, problems}` |
| `POST` | `/bi/boards/format` | `{board}` (the model, as `board` in the answers above): `{yaml}`, the board written the way a person writes it, one tile to a line; read back by `validate` to the same board. A tile's `alert` may carry `hysteresis` (a number of 0 or more): how far back inside a threshold the value must be before the tile drops a level ([BOARDS.md section 4](BOARDS.md#4-alerts-a-tile-changes-colour)) |

Captured from a real server, validating a board with a tile off the grid, a reserved key, a visual that does not exist and a report that does not exist:

```
POST /api/v1/bi/boards/validate   {"yaml": "board: bad\npages:\n  - id: p\n    tiles:\n      - { report: live-trading-desk, visual: nope, at: { x: 0, y: 0, w: 13, h: 3 } }\n      - { report: no-such, visual: v, at: { x: 0, y: 3, w: 4, h: 3 }, alert: { warn: { op: lt, value: 0 }, notify: { email: a@b.c } } }\n"}
200  {"valid": false, "problems": [
       {"level": "error", "code": "DRS-9052", "path": "pages[0].tiles[0].at", "message": "x is 0 to 11 and w is 1 to 12, and x + w is at most 12 (the grid has 12 columns); this is x=0, w=13"},
       {"level": "error", "code": "DRS-9053", "path": "pages[0].tiles[1].alert.notify", "message": "'notify' is reserved and not built yet: a tile only changes colour; notifications arrive with distribution (phase 7)"},
       {"level": "error", "code": "DRS-9052", "path": "pages[0].tiles[0].visual", "message": "report 'Live trading desk' has no visual 'nope'"},
       {"level": "error", "code": "DRS-9052", "path": "pages[0].tiles[1].report", "message": "no report 'no-such' that you can open (a catalogue id, a file name such as live-trading-desk, or the report's name)"}]}
```

Publishing a board (`POST /bi/items/{id}/publish`) checks its structure and refuses `422 DRS-9052`. From the console the calls are under `/bi/api/boards/...` and the stream is the channel subscription `bilive:<report id>` ([LIVE.md](../architecture/LIVE.md#live-bi-a-reports-live-tables)).

## Export and subscriptions: `/api/v1`

Distribution ([DISTRIBUTION.md](DISTRIBUTION.md) is the reference). The file or the mail is made for the **caller** (or, for a subscription, each recipient): row rules and masks apply as for the screen.
Errors are `DRS-9060` (an export) and `DRS-9061` (a subscription); an unreadable report is `403 DRS-9002` or `404 DRS-9001` as everywhere in BI.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/distribution/status` | what the caller can do: `{export: {enabled}, subscriptions: {enabled, perUser, mine}}`: whether exports are on, whether subscriptions are on, how many the caller owns and the most they may. The report page asks it to show Export and Subscribe only when they work and to say why when they do not. Any token (read) |
| `POST` | `/export/{format}` | `format` is `pdf`, `png`, `xlsx` or `csv`. Body `{type: "report", id, page?, visual?, state?, date?}` (`state` is the report's view state: `slicers`, `selections`, `drill`, `carry`, `dates`) or `{type: "view", id: "kind/id", date?, view?}` (`view` is the query text of the control values and column filters, `ctl.x=1&f.panel.col=gt:5`). Answers the file with `Content-Disposition: attachment` and `X-Export-Rows`. `422 DRS-9060`: exports off, an unknown format, a target that does not exist, over `max-bytes`, over `timeout` |
| `GET` | `/me/subscriptions` | your subscriptions: `{name, owner, title, target, schedule, date, format, recipients, narrative, webhooks[], sms, enabled, nextRun, loadDate?, runs[]}`; a run is `{id, at, trigger, status, sent, skipped, failed, retrying, deliveries[{to, status, attempts, channel?, user?, narrative?, error?, nextAttempt?}]}` (`channel` is absent for a mail, `webhook` or `sms`; `to` is then `webhook:NAME` or `sms:USER`; `narrative` on a mail is `written` or `skipped: why`) |
| `GET` | `/me/subscriptions/{name}` | one |
| `PUT` | `/me/subscriptions/{name}` | save: `{target: {type, id, page?, visual?, state?, view?}, schedule, format: html\|pdf\|xlsx, recipients: [user \| role:NAME], date?: today\|previous\|load, enabled?, title?, narrative?: bool, webhooks?: [name], sms?: bool}`. `narrative: true` opens each email with a short written summary of **that recipient's own** figures (needs the AI layer on, else `422`; written only when the AI policy lets the model see values, otherwise left out and the delivery says why); `webhooks` are names an administrator defined (`GET /notify/options`; unknown: `422`); `sms: true` texts each recipient who gave a number (needs a gateway, else `422`). `422 DRS-9061` says what is wrong |
| `DELETE` | `/me/subscriptions/{name}` | delete (`204`) |
| `POST` | `/me/subscriptions/{name}/run` | run now: one render and one mail per recipient, as that recipient; answers the run |
| `POST` | `/me/subscriptions/{name}/pause` · `/me/subscriptions/{name}/resume` | pause or resume (a resumed one is scheduled from now) |
| `POST` | `/subscriptions/preview` | `{schedule}`: `{next: [3 instants]}` (empty for `after-load`) |
| `GET` | `/notify/options` | what alerts and subscriptions may use besides mail: `{webhooks: [name], sms: {configured, maxChars, phone}}` (`phone` is the caller's own number with all but its last two digits hidden, or empty). The URLs and secrets of the webhooks are never shown |
| `PUT` | `/me/phone` | body `{phone}`: the caller's own mobile number for texts (`+` and digits, spaces, dashes or brackets; blank removes it); answers `{phone}` hidden as above. Another person's number cannot be set or read. Like every `/me/**` call it is never open to a personal API token (`token-never`) |
| `GET` | `/admin/subscriptions` | everyone's (administrators) |
| `POST` | `/admin/subscriptions/{owner}/{name}/pause` · `/admin/subscriptions/{owner}/{name}/resume` | administrators |
| `DELETE` | `/admin/subscriptions/{owner}/{name}` | administrators (`204`) |

From the console: `GET /dist/bi/<report id>.<format>?page=&visual=&state=&date=` and `GET /dist/view/<kind>/<id>.<format>` download the files; the forms are on `/reports`.

Captured from a real server: exporting the sample page as Excel, and what the first lines of the response are:

```
POST /api/v1/export/xlsx  {"type":"report","id":"file:report:trades-overview","page":"overview","state":{"slicers":{}}}
200  Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
     Content-Disposition: attachment; filename="Trades overview.xlsx"
     6362 bytes, sheets: About, Trades, Notional, Mark to market, Average MTM per trade, MTM by desk (click a bar to fil, Notional by trade date, Trades by desk and currency
```

## Alerts on numbers: `/api/v1`

Phase 7, part 2 ([DISTRIBUTION.md section 4](DISTRIBUTION.md#4-alerts-on-numbers) is the reference). An alert watches one number and is **evaluated as its owner**: their row rules and masks apply to every reading, and a number masked for
them is never judged or put in a message. Errors are `422 DRS-9062`; an alert that does not exist is `404 DRS-1001`. These calls are **never open to a personal API token** (`/me/**` and `/admin/measure-alerts/**` are in `token-never`).

| Method | Path | What it does |
|---|---|---|
| `GET` | `/me/measure-alerts` | your alerts: the stored alert (below) plus `describe` (the condition in words), `label` (what it watches) and `link` (the console path that opens it) |
| `GET` | `/me/measure-alerts/{name}` | one |
| `PUT` | `/me/measure-alerts/{name}` | save (create, or change: an edited alert is armed afresh and keeps its history): `{title?, severity?: info\|warn\|critical, enabled?, source: {type: visual, report, page, visual, measure?, reduce?: first\|last\|sum\|min\|max\|avg\|count, state?} \| {type: dataset, dataset, measure, filters?: [{field, op, values}]} \| {type: model, model, query: <a model query that selects one measure; `model` may be left out>, reduce?} \| {type: field, kind, id, field}, condition: {type: threshold, op: above\|below\|between\|outside, value, high?} \| {type: change, mode: pct\|abs, op: rises\|falls\|moves, value, against: previous-run\|previous-day} \| {type: nodata, by: "HH:MM"}, trigger: {mode: schedule, schedule} \| {mode: after-load, kind?} \| {mode: live, debounce?}, hysteresis?, cooldown?, delivery?: {inbox, email, bridge, webhooks?: [name], sms?: bool}}`. `webhooks` are names an administrator defined (`GET /notify/options`; at most 5; an unknown one is `422 DRS-9062`), `sms` needs a gateway and your own number on file (`PUT /me/phone`). A failed email, webhook or text is tried again by the shared retry policy (`drishti.retry.alerts.*`, `drishti.alerts.measure.max-attempts`); each history entry's `delivered` says what happened per channel (`email: "sent (attempt 3 of 4)"`, `emailAttempts: 3`, `webhook:ops: "sent"`, `sms: "failed after 4 attempts: ..."`). The alert is looked at once as you save it |
| `DELETE` | `/me/measure-alerts/{name}` | delete (`204`) |
| `POST` | `/me/measure-alerts/{name}/check` | look now, as you; answers the alert with its new state and history |
| `POST` | `/me/measure-alerts/{name}/pause` · `/me/measure-alerts/{name}/resume` | pause, or resume (armed afresh, and looked at once) |
| `POST` | `/me/measure-alerts/preview` | the same body as a save: `{status: ok\|masked\|no_data\|error, value, asOf, note, condition, holds?, live}` for **you, now**; saves and sends nothing |
| `GET` | `/admin/measure-alerts` | everyone's alerts (administrators) |
| `GET` | `/admin/measure-alerts/boards` | the board tiles that notify (administrators): the tile, its colour rule, `activeFor` (how many people are alerted now) and the history |
| `POST` | `/admin/measure-alerts/{owner}/{name}/pause` · `/admin/measure-alerts/{owner}/{name}/resume` | administrators |
| `DELETE` | `/admin/measure-alerts/{owner}/{name}` | administrators (`204`) |

A stored alert is `{name, owner, title, severity, enabled, source, condition, trigger, hysteresis, cooldown, delivery, createdAt, state, history}`: `state` maps a user to `{active, firedAt?, lastValue?, lastEval, note?}`; a `history`
entry is `{at, user, action: fired\|cleared\|held\|skipped, severity, trigger: saved\|schedule\|load\|live\|by hand\|resumed, value?, asOf?, reference?, delta?, message, id?, delivered?: {inbox, email, bridge}}` (newest first; `delivered` says `ok`, `sent`,
`queued for 1 bridge`, `skipped: …`, `failed: …` or `off`). An alert's firing is an inbox row of type `alert` (`kind: alert`, `id` the console path it opens, `title` the one line), read from `GET /me/inbox?type=alert` ([the inbox](#share-with-a-note-the-directory-and-the-inbox)).
A board tile with `notify` is an alert whose key is `board:<board id>/<page>/<tile>`; its state and history are under `/admin/measure-alerts/boards`.

From the console: `/alerts` (the list, beside the older rules), `/alerts/new` (the form: `?source=visual&report=&page=&visual=&state=`, `?source=dataset&dataset=&measure=`, `?source=field&kind=&id=&field=`), `/alerts/measure/<name>` (edit and history),
`/alerts/all` (administrators) and `POST /alerts/preview` (the form's *Check now*).

Captured from a real server. *Check now* for the MTM card, before and after ten losing trades and a data load:

```
POST /api/v1/me/measure-alerts/preview  {"source":{"type":"visual","report":"file:report:trades-overview","page":"overview","visual":"mtm"},"condition":{"type":"threshold","op":"below","value":-300000},"trigger":{"mode":"after-load"}}
200  {"status": "ok", "value": -725000.0, "asOf": "2026-10-08", "note": "", "condition": "below -300,000", "holds": true, "live": false}
```

and a refusal:

```
PUT /api/v1/me/measure-alerts/Bad  {"source":{"type":"visual","report":"file:report:trades-overview","visual":"mtm"},"condition":{"type":"threshold","op":"over","value":1},"trigger":{"mode":"schedule"}}
422  {"type": "about:blank", "title": "alert invalid", "status": 422, "detail": "DRS-9062 condition.op is above, below, between or outside", "instance": "/api/v1/me/measure-alerts/Bad", "code": "DRS-9062"}
```

## Publishing and governance: `/api/v1/bi` (phase 4)

Phase 4 of Rūpaka ([BI_GOVERNANCE.md](BI_GOVERNANCE.md) is the reference; [BI_GUIDE.md](BI_GUIDE.md) section 17 the pages). Every answer is as the caller: drafts and saved states are for people who may edit the item (and a bi-steward while it is proposed), the published text for everyone who may read it, and a report is named in a list only if the caller could open it. Writes by a personal token need `bi:write` (restore, impact of a text), `bi:approve` (approve, reject, endorse, bundle import) as in [tokens](#personal-api-tokens).

| Method | Path | What it does |
|---|---|---|
| `GET` | `/bi/items/{id}/history` | `{revisions: [{seq, message, author, createdAt, origin, publishedVersion, draft, bytes}], events: [{seq, action, actor, note, revision, at}], canRestore}`, newest first; `origin` is `create`, `save`, `restore`, `publish`, `import`, `rename` or `rollback`; `events` is the review record (`request`, `withdraw`, `approve`, `reject`, `certify`, `uncertify`, `promote`, `unpromote`, `revoke`, `import`, `rename`, `rollback`) |
| `GET` | `/bi/items/{id}/history/{n}` | one saved state with its `body` |
| `POST` | `/bi/items/{id}/history/{n}/restore` | the saved state becomes the draft, as a new saved state; the item as `GET /bi/items/{id}`; `409 DRS-9004` while proposed |
| `GET` | `/bi/items/{id}/diff?from=&to=` | side by side: `{from: {ref, label}, to, identical, added, removed, changed, rows: [{op: same\|added\|removed\|changed, left, right, leftNo, rightNo}]}`; a side is `draft`, `published`, `vN` or `rN` (default `published` against `draft`); `422 DRS-9003` for another |
| `GET` | `/bi/items/{id}/review` | `{request, events, diff, check: {ok, problems}, impact, preview, mayDecide}`; `preview` is `{available, label, columns, rows, rowCount, meta}` or `{available: false, reason}`, answered with the caller's row rules and masks |
| `GET` | `/bi/items/{id}/lineage` | dataset: `{upstream: [{table, kind, packs, connector, source: {connector, plugin, masked, declared: {root\|table\|topic\|path\|...}}, each, mode}], downstream: [{id, title, status, endorsement, uses}], hidden}`; report: `{dataset, uses, upstream}` |
| `GET` | `/bi/items/{id}/impact[?remove=a,b]` | what breaks: `{change: {breaking, removed*, added*, renames: [{kind, from, to}], alters: [{kind, name, what, from, to}]}, breaksPublished, published: [{visible, id, title, uses}], drafts, altered: [...], renames, renamable, refused, canForce, wouldRevokeCertification, hypothetical}`: a removed name that a new one replaces with the same meaning is a rename (`uses` then say `[renamed to ...]`), and `altered` lists the published reports that use a name whose type or meaning changed ([BI_GOVERNANCE.md section 6](BI_GOVERNANCE.md#6-impact-what-breaks-if-i-change-this)) |
| `POST` | `/bi/items/{id}/impact` | the same for `{yaml}` not yet saved |
| `GET` | `/bi/items/{id}/dependants` | what depends on an item, for a delete confirmation: `{id, kind, count, dependants: [{id, kind, title, status}]}`. A dataset has the reports that read it and the composed or derived datasets built on it; a report has the boards that show it; other kinds have none. Only items the caller may read are counted and named. Any token that may read the item (no write scope); `403 DRS-9002` / `404 DRS-9001` as everywhere in BI |
| `POST` | `/bi/items/{id}/tests` | `{role?, yaml?}`: runs the expected values (`tests:`) of a report or dataset ([REPORTS.md section 17](REPORTS.md#17-expected-values-tests)) as you, or as a role you hold (a `bi-steward` as any); `yaml` is a report draft not yet saved (needs `bi-author`). Answers `{item, kind, role, total, passed, failed, errors, ok, results: [{name, kind, target, expect, actual, status: pass\|fail\|error, message, role}]}`; queries only, stores nothing, audited `bi-tests-run`. Token: on `token-read-posts` (a read: a personal token may call it) |
| `POST` | `/bi/items/{id}/publish`, `/approve` | `{note, force, applyRenames}`: `applyRenames` rewrites the reports that use renamed names; `force` publishes over broken reports or over failing tests and needs `bi-admin`; the item plus `warnings` (what broke, which certification was revoked); `409 DRS-9040` without it for a dataset that breaks published reports, `409 DRS-9043` without it when a report's or dataset's expected values fail or cannot run |
| `GET` | `/bi/bundle?items=a,b&workspace=w&dependencies=true` | a bundle (`drishti-bi-bundle`, format 1): items with published versions, sample flags, owner, audience and checksums, plus `skipped`; needs `bi-author` and the contributor role |
| `POST` | `/bi/bundle/import` | `{bundle, dryRun (default true), overwrite, force, workspace, workspaces: {from: to}, owners: {from: to}}`; needs `bi-steward`; `{dryRun, ok, applied, counts, items: [{id, action, workspace, owner, reasons, warnings, blocks}]}`; `422 DRS-9041`, `409 DRS-9042` |
| `GET` | `/bi/imports`, `/bi/imports/{id}` | needs `bi-steward`: the imports whose replaced states are kept (`{id, at, by, source, items, created, replaced, rolledBack}`, newest first); one with its `entries` (`{id, title, kind, created, restoresVersion, changedSince}`). `source` is `bundle` or `pack <name> <version>` |
| `POST` | `/bi/imports/{id}/rollback` | `{force}` (optional; `force` needs `bi-admin`): puts back what the import replaced and removes what it created; `{id, restored, removed, forced}`; `409 DRS-9004` when an item was edited or published since (unless forced) or the import was rolled back already |

Review notices reach the inbox ([the inbox](#share-with-a-note-the-directory-and-the-inbox)) as rows of type `bi-review` (to reviewers), `bi-decision` (to the author) and `bi-certification` (to the owner and the certifier); `kind` is `bi`, `id` the item and `title` a one-line summary. Alerts on numbers arrive as rows of type `alert` ([Alerts on numbers](#alerts-on-numbers-apiv1)). Refusals: `DRS-9040`, `9041`, `9042` and the phase 1 codes (`DRS-9001` to `9005`).

## AI and MCP: `/api/v1/ai` and `/api/v1/admin/ai`

Off by default (`drishti.ai.enabled`, `drishti.mcp.enabled`); [AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md) and [MCP_CLIENT.md](../architecture/MCP_CLIENT.md) are the references. While a layer is off every call below answers
`404 DRS-4015`. Every call goes through the AI policy (masking, per-user limits, audit); a model or tool failure is `502 DRS-4016`, a timeout `504 DRS-4016`, a limit `429 DRS-4017`, a refusal by the policy `403 DRS-4018`, an unknown model or server `404 DRS-4019`. A personal token needs the scope `ai:use` (or `ai:admin` for the test call).

| Method | Path | Notes |
|---|---|---|
| `GET` | `/ai/models` | any signed-in user: the models features may name, `[{name, provider, id, kind, capabilities, maxTokens, …}]` |
| `POST` | `/ai/chat` | body `{model?, system?, messages: [{role, content}] or prompt, temperature?, maxTokens?, jsonMode?, timeoutSeconds? (1-120, default 30), feature?}` → the answer with `text`, `model` and `usage`. `feature` is recorded as `api:<feature>` |
| `POST` | `/ai/mcp/{server}/tools/{tool}` | body `{arguments?, feature?}` → the tool's result, if the server and tool are allowed for the caller's roles |
| `GET` | `/admin/ai` | admin: `{enabled, dir, types, defaultModel, providers[{name, type, enabled, baseUrl, keySet, health, retry, models}], fileErrors, mcpEnabled, mcpDir, mcpServers[], mcpFileErrors}` (never a key) |
| `POST` | `/admin/ai/test` | admin; body `{model?, prompt?}`: one real call → `{ok, text, model, usage, millis}`; audited as `ai-test` |
| `GET` | `/admin/mcp/{server}/tools` | admin: the tools an MCP server offers |

```bash
curl -s $B/admin/ai
```

```json
{"enabled":false,"dir":"./config/ai","types":["openai","anthropic","mock"],"defaultModel":"","providers":[],"fileErrors":{},"mcpEnabled":false,"mcpDir":"./config/mcp","mcpServers":[],"mcpFileErrors":{}}
```

```bash
curl -s -X POST $B/ai/chat -H 'Content-Type: application/json' -d '{"prompt":"hi"}'
```

```json
{"type":"about:blank","title":"ai off","status":404,"detail":"DRS-4015 the AI layer is switched off (drishti.ai.enabled)","instance":"/api/v1/ai/chat","code":"DRS-4015"}
```


## Embedding views in another web application

For `<drishti-view>` ([ELEMENTS.md](../architecture/ELEMENTS.md)): a host application shows live Drishti views in its own pages, and Drishti decides who may see what. Off by default; switch it on with `drishti.embed.enabled` (needs `drishti.security.enabled`; settings in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiembed--embedded-views-for-other-web-applications)).

**1. Register the host application** (an administrator; audited as `embed-app-created`, `embed-app-changed`, `embed-app-disabled`, `embed-app-secret-rotated`):

| Method and path | What it does |
|---|---|
| `GET /api/v1/admin/embed/apps` and `/{id}` | the registered applications (never a secret) |
| `POST /api/v1/admin/embed/apps` | `{id, name, contact, origins[], kinds[], scopes[], subjectTypes[], subjectAudiences[], jwks, secret, tokenSeconds, callsPerMinute, userCallsPerMinute, enabled}`. `id`: lower-case letters, digits, hyphens. `origins`: exact (`https://crm.bank.example`; no path; wildcards only with `drishti.embed.allow-wildcard-origins`). `scopes`: `embed:view`, `embed:about`, `embed:report`. `kinds`: empty = every kind and report the user's roles open; an application may only narrow; a report is `report:<id>` and every report `report:*`. `subjectTypes`: `id_token`, `jwt` (both by default). `jwks`: the host's public keys as JSON text (RSA or P-256/P-384). `secret: false` registers a key-only application. The answer holds the client `secret` **once** |
| `PUT /api/v1/admin/embed/apps/{id}` | change any of those (`null` keeps; `enabled: false` revokes at once) |
| `POST /api/v1/admin/embed/apps/{id}/rotate-secret` | `{graceSeconds}` (default 3600): a new secret, shown once; the old one works for the grace period |
| `DELETE /api/v1/admin/embed/apps/{id}` | forget the application |
| `POST /api/v1/admin/embed/apps/{id}/disable`, `/enable` | stop or resume the application without forgetting it (audited as `embed-app-disabled` / `-enabled`) |
| `GET /api/v1/admin/embed/usage` | `{since, enabled, settings, apps: {id: {tokensIssued, calls, refusals, refusalsByCode, streamsOpen, lastUsedAt}}}`: counters since this server started, and what the registration form offers (`settings.scopes`, limits) |

An application declared in `drishti.embed.apps` has `fromConfig: true` and answers `409` to every change; see [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiembed--embedded-views-for-other-web-applications). The same pages are in the console at **Admin → Embedding**.

**2. The host's backend buys a token** (RFC 8693; never from a browser: a request with an `Origin` header is refused `403 DRS-8002`):

```
POST /api/v1/embed/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(crm:<client secret>)           # or client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=<JWT>

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<the user's ID token, or a JWT the host signs>
&subject_token_type=urn:ietf:params:oauth:token-type:id_token   (or ...:jwt)
&audience=https://console.bank.example                           # one of drishti.embed.audiences; default: the first
&scope=embed:view embed:about                                    # within the application's; default: all of them
```

- **Client authentication:** `client_secret_basic`, or `private_key_jwt`: a JWT signed with a key in the application's `jwks` with `iss` = `sub` = the application id, `aud` = the server's issuer or `…/api/v1/embed/token`, `iat`, `exp` at most 60 s later (`drishti.embed.assertion-max-age`) and a `jti` that is used once.
- **Who the user is** (there is no plain "username" field: a stolen client secret alone makes nobody): the user's **ID token** from the identity provider (verified like a sign-in: signature, issuer, expiry, audience = Drishti's OIDC client id or one of the application's `subjectAudiences`; the user is `usernameClaim` as the sign-in names them), or a **JWT the host signs** with its registered key: `iss` = the application id, `sub` = the Drishti user name, `aud` as above, `iat`, `exp` within 60 s, `jti` once. The user must exist in Drishti and be enabled.
- **Answer:** `{"access_token": "<JWT>", "issued_token_type": "urn:ietf:params:oauth:token-type:access_token", "token_type": "Bearer", "expires_in": 300, "scope": "embed:view embed:about"}`. The token is ES256, signed by the server's own key (`GET /api/v1/embed/jwks`, public), with `iss`, `aud`, `sub` (the user), `azp` (the application), `scope`, `origins`, `iat`, `exp` (at most `drishti.embed.token-max-seconds`), `jti` and `typ: drishti-embed+jwt`. It carries no roles: they are read at every call.
- **Refusals** are in RFC 6749 form `{"error": "invalid_client", "error_description": "...", "code": "DRS-8003"}`: `invalid_client` 401 `DRS-8003` (unknown or disabled application, wrong secret or key), `invalid_grant` 400 `DRS-8001` (the subject token is expired, unsigned by a registered key, used before, not for this server) or `DRS-5010` (no such user, or disabled), `invalid_target` 400 `DRS-8001` (an audience this server makes no tokens for), `invalid_scope` 400, `slow_down` 429 `DRS-8004` (`drishti.embed.limits.tokens-per-minute` per application; `Retry-After`), `access_denied` 403 `DRS-8002` (called from a browser) or `DRS-8003` (embedding is off). Every exchange is in the audit log (`embed-token`, `embed-token-refused`).

**3. Every embed call** (`Authorization: Bearer <embed token>`; the console's `/embed/v1/...` passes it on) is checked, in this order, before anything is read: the signature, `typ`, audience and expiry (`401 DRS-8001`); the host application exists and is enabled (`403 DRS-8003`); the user exists and is enabled (`401 DRS-5010`); the `Origin`, when the request has one, is one of the application's (`403 DRS-8002`); the scopes open the method and path (`403 DRS-5002`): only `GET`s of views, their stream, panel rows and `explain` (`embed:about`), the read-only `POST /api/v1/command`, and `GET /api/v1/embed/check`, as `drishti.embed.scopes` lists; the kind is one the application may show (`400 DRS-8005`); the rates of the application and of the user (`429 DRS-8004`, `Retry-After`). The caller is then the user **with their roles as they are now and with every field mask applied, whatever the roles say** (Decision 5): a user with `raw` still sees `•••` through an embedded view. A typed command resolved for a host does not enter the user's command history. The access log records each view as the user's, with the detail `embed:<application>`.

**Search (`embed:search`).** `GET /api/v1/embed/search?q=&limit=` is the search of `<drishti-search>`: the console's type-ahead for the token's user, read-only and masked (kinds the user may not open are left out; masked fields are neither shown nor matched; an embed call always masks), cut to the kinds the application may show, to `drishti.embed.search.max-results` (10) results and to queries of `min-chars` (2) or more characters (shorter: `{results: [], note}`). Answer: `{query, results[{type, kind, id, mnemonic, title, subtitle}]}`, `Cache-Control: no-store`. Besides the call rates, each user may search `drishti.embed.limits.searches-per-user-per-minute` times a minute per application (`429 DRS-8004`, `Retry-After`). Without the scope `403 DRS-5002`; the scope opens this path and no other. The console's `GET /embed/v1/search?q=&limit=` passes it on.

Other endpoints: `GET /api/v1/embed/check` answers `{app, user, jti, scopes, expiresAt, expiresIn}` for a token that passes every check just now (the console re-checks a long-lived stream with it every `embed.recheck_seconds`); `GET /api/v1/embed/apps/origins` (the console's service identity only) lists the origins of the enabled applications, which is the console's CORS allow-list.

**Renewal.** Tokens are short (default 5 minutes) and never refreshed by the browser: when one is about to expire the element asks the host page, whose backend repeats the exchange above for the same user (a fresh `jti`, a later `exp`); the console accepts it on the open stream only when `sub` and `azp` are the same (ELEMENTS.md, section 7.2). Disabling the application or the user takes effect at the next call, and on an open stream at the next re-check.

**4. The console's embed API** (`/embed/v1`, served when the console has `embed.enabled`; the element calls it, a host rarely does): `GET /views/{kind}/{id}` (head, strip and panels as HTML), `GET /views/{kind}/{id}/about` (scope `embed:about`; `<drishti-about>`), `GET /search?q=&limit=` (scope `embed:search`; `<drishti-search>`), `GET /views/{kind}/{id}/panels/{panel}/records`, `GET /resolve?text=` (a typed command or id to `{ref, mnemonic, title}`, read-only), `GET /channel?s=view:{kind}/{id}` (server-sent events: `channel`, `view`, `frame`, `gone`, `end`, `token`), `POST /channel/{cid}` (`{add, remove}`) and `POST /channel/{cid}/token`. Every call carries `Authorization: Bearer <embed token>`; CORS answers only the registered origins. The console verifies the token's signature (the server's key from `/api/v1/embed/jwks`, cached, re-read when a key id is new), expiry, audience and origin, then passes it to the server. A stream outlives its token: at expiry the console sends `event: token`, the element posts a fresh token to `/channel/{cid}/token` (the same user and application, a later expiry) and the stream carries on; with none within `embed.token_grace_seconds` it ends with `gone` `DRS-8001`. Problems are `{"code", "detail"}` (`DRS-8001` to `DRS-8006`; `429` carries `Retry-After`). Responses are gzip or Brotli compressed when accepted. A report is `GET /reports/{id}`, `POST /reports/{id}/query` and the channel key `bilive:{id}`, below. The library itself is served at `GET /embed/v1/elements/{version}/drishti-elements.js`, `.../m/{name}.js` and `.../integrity.json` (an exact semantic version, immutable; `1` and `1.1` answer `302` to it; a version this console does not hold is `410 DRS-8006`; with `embed.lifecycle` set every answer carries `Deprecation` and `Sunset`). Console settings: [CONFIGURATION.md](../admin/CONFIGURATION.md#console-embed--the-consoles-side-of-embedded-views).

**What a view says about masks.** `provenance.masked` (a number) and `provenance.maskedPanels` (panel ids) in every ViewModel and live `provenance` patch count the values the caller's masks replaced; both are `0` and `[]` when nothing is masked for the caller.

## Embedding a report (`/embed/v1/reports`)

For `<drishti-report>` ([ELEMENTS.md section 18](../architecture/ELEMENTS.md#18-drishti-report-a-published-report-in-another-web-application), the developer guide [ELEMENTS.md](ELEMENTS.md#drishti-report)): a published BI report, one page or one visual of it, in a host application's page. The token is the one of the section above, with the scope **`embed:report`** and a host application whose `kinds` list holds `report:<id>` (or `report:*`). Every call carries `Authorization: Bearer <embed token>`; the user's row rules and masks apply, and the masks of an embedded call never lift (a user with `raw` still reads `•••`).

| Console call | Server call (the embed scope opens exactly these) | Answer |
|---|---|---|
| `GET /embed/v1/reports/catalogue` | `GET /api/v1/bi/reports/catalogue` | `{visuals: {<type>: {group, wells}}, reserved, slicerModes}` |
| `GET /embed/v1/reports/{id}` | `GET /api/v1/bi/reports/{id}` | `{id, title, description, sample, shipped, valid, problems (errors only), report (the model: pages, visuals, bookmarks, layouts), dataset (tables, measures, hierarchies)}`; `403 DRS-8005` unless the report is published; never the YAML |
| `POST /embed/v1/reports/{id}/query` | `POST /api/v1/bi/reports/{id}/query` | one visual's rows, as the console viewer asks: body `{page, visual, state: {slicers, selections, dates, carry, drill}}` (64 KB at most); a read: it changes nothing |
| `GET /embed/v1/channel?s=bilive:{id}` and `POST /embed/v1/channel/{cid}` `{add: ["bilive:{id}"]}` | `GET /api/v1/bi/reports/{id}/live` | events `hello`, `tick`, `status` for the report's live tables; they carry state, never data |

The authoring paths of the same server resource (`GET .../dataset`, `POST .../validate`, `.../format`, a draft's `POST .../query`) are never open to an embed token (`403 DRS-5002`); a report the host's `kinds` list does not name is `400 DRS-8005`. The model carries `layouts: {phone: {<page id>: {order, hide, span}}}` when the report declares phone layouts ([REPORTS.md](REPORTS.md#14-phone-and-tablet-layouts)); the same key is in `GET /api/v1/bi/reports/{id}` and in the YAML the designer saves.

## The console's own JSON routes

Everything above is the **server's** API, on port 18480. The **console** (port 17480) has a second set of JSON routes that its pages call with `fetch`;
they are not a public API and have no stability promise, but a script that must act exactly as a browser does can use them. What they have in common:

- They are same-origin and ride the console's session cookie (`drishti_session`). A `POST`, `PUT`, `PATCH` or `DELETE` must come from a page of the console
  (its `Origin` or `Referer` names the console or an entry of `auth.allowed_origins`; a request with neither header, such as `curl`, passes) and a body must be
  sent as `application/json`; otherwise `403 DRS-5002`.
- Each one asks the server for the same thing a script would, as the signed-in user (the console adds that user's identity to the call), so the user's roles,
  packs, masks and business date apply as for the server's own endpoints. A server refusal comes back with its status and `DRS-` code; a console that cannot
  reach the server answers `503 DRS-5003`.

| Routes | What they do | Server calls behind them |
|---|---|---|
| `GET /api/suggest`, `POST /api/resolve`, `GET /api/history`, `PUT /api/aliases` | the command line: type-ahead, run a command, recent commands, aliases | `/command/suggest`, `/command`, `/command/history`, `/command/aliases` |
| `GET /api/view/{kind}/{id}`, `GET /api/raw/{kind}/{id}`, `GET /api/series/{kind}/{id}` | a view, the raw document, a field over time | `/views/{kind}/{id}`, `/entities/{kind}/{id}/raw`, `/history/{kind}/{id}/series` |
| `GET /api/stream/{kind}/{id}`, `GET /api/channel`, `GET /api/channel/who`, `POST /api/channel/{cid}` | live updates: one stream per browser (the channel) to which views subscribe | `/views/{kind}/{id}/stream`, `/me/alerts/stream`, `/me/monitors/{name}/stream` |
| `GET /api/monitor-stream/{name}`, `GET /api/alerts/stream` | a monitor's and the user's alert stream | `/me/monitors/{name}/stream`, `/me/alerts/stream` |
| `PATCH /api/settings`, `POST /api/packs`, `POST /api/tokens`, `DELETE /api/tokens/{id}` | the user's settings, active packs and personal API tokens | `/me/settings`, `/me/packs`, `/me/tokens` |
| `GET` and `POST /api/notes/{kind}/{id}`, `PUT` and `DELETE /api/notes/{noteId}` | notes (deprecated) | `/notes/...` |
| `PUT` and `DELETE /api/layout/{sutra}/{kind}`, `GET` and `POST .../promotion` | personal layouts and promoting one to a Sutra | `/me/layouts/...` |
| `GET /api/pivot/records/{kind}/{id}/{panel}`, `POST /api/pivot/search/{kind}` and `/drill` and `/values`, `GET`, `PUT`, `DELETE /api/pivot/saved/panel/{sutra}/{panel}` and `/saved/search/{kind}`, `GET` and `POST .../promotion` | the Pivot tab | `/views/.../panels/{panel}/records`, `/search/pivot/...`, `/me/pivots/...` |
| `GET /api/calc/snippets`, `PUT` and `DELETE /api/calc/snippets/{name}`, `GET /api/calc/search`, `GET /api/calc/columns/{kind}` | Calc | `/me/calc-snippets`, `/search`, `/search/columns/{kind}` |
| `GET /api/collab`, `GET /api/directory`, `POST /api/share`, `POST /api/share/preview-picture`, `GET /api/share/{id}/picture`, `POST /api/share/{id}/replies`, `GET /api/inbox/count`, `POST /api/inbox/read` | share with a note, the directory, the inbox | `/collab`, `/directory`, `/shares...`, `/me/inbox...` |
| `GET` and `POST /api/threads/{kind}/{id}`, `GET /api/thread-counts/{kind}/{id}`, `POST /api/thread/{tid}/comments`, `/state`, `PUT` and `DELETE /api/thread/{tid}/follow`, `PATCH /api/comment/{cid}`, `POST .../retract`, `/hide`, `/unhide`, `GET .../revisions` | comment threads | `/threads/...`, `/comments/...`, `/admin/collab/comments/{cid}/hide` |
| `POST /api/ai/chat` | the console's AI layer ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md)) | none, or `/ai/chat` for the `server` provider; `404 DRS-4015` when AI is off |
| `GET /bi/python/status`, `POST /bi/python/run`, `POST /bi/python/check` | the Python runner of BI: its state, running a python visual's item on the server, checking a Python item | `/bi/reports/{id}/python`, `/bi/reports/{id}/query?format=arrow`, `/bi/python/validate` |
| `/admin/api/...`, `/admin/connectors/api/...`, `/admin/collab/api/...`, `/admin/embedding/api/...`, `/admin/ai/api/overview` | the Admin pages' actions: users, roles, packs (load, unload, deploy, roll back, registry, sample mode), loads configuration, token revoke, cache purge, retry now, connectors, collaboration compliance, embedded applications | the matching `/admin/...` endpoints; administrators only |
| `/build/pack/api/...` | New pack from schemas (plan, jobs, drafts, preview, bundle) | none: the console builds the pack and hands it to the Build workbench or Deploy |
| `POST /m/api/{name}`, `/m/api/{name}/delete`, `POST /alerts/api/{name}`, `/alerts/api/{name}/delete`, `POST /w/api/{name}`, `/w/api/{name}/share`, `/w/api/{name}/delete` | save and delete monitors, alert rules and workspaces | `/me/monitors/...`, `/me/alerts/rules/...`, `/me/workspaces/...` |

The BI, Build, Studio, Forms, Reports and Export pages have JSON routes of their own beside their pages (under `/bi/`, `/build/`, `/studio/`, `/forms/`, `/reports/` and `/export/`);
they follow the same rules and each maps to one endpoint of the BI, Designs, Sutra or search sections above.

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
other method answers `403 DRS-5002 API tokens only read`, except the `POST`s that only read (pivot of a search, `/command`, BI queries, exports and subscription previews: the
allow-list `drishti.security.token-read-posts`). An unknown, wrong, revoked or expired token answers `401`.

**Write scopes.** A token may also be made with scopes (`drishti.security.token-scopes`); each opens a set of `METHOD path`
patterns, and the server decides in one filter (`TokenScopes`):

| Scope | Opens | The user must also hold |
|---|---|---|
| `read` | `GET`, `HEAD` and the read-only `POST`s (the default; what tokens always had) | - |
| `design:write` | `POST/PUT/PATCH/DELETE /builder/**` (Designs, shape, suggest, edit, check, propose, share, bind, import), `POST /studio/**`, `POST /sutras`, `POST /sutras/proposals/{id}/withdraw` | `author` |
| `design:approve` | `POST /sutras/proposals/{id}/approve` and `/reject` | `approve` or admin (not your own proposal, four-eyes) |
| `loads:write` | `POST /packs/{pack}/loads` (announce that a batch landed or failed; [DATA_LOADS.md](DATA_LOADS.md)) | the roles that open the kind (an administrator needs no more) |
| `bi:write` | Business intelligence: `POST /bi/datasets/{id}/refresh`, `POST /bi/datasets/{id}/transform/run`, `POST /bi/workspaces`, `POST /bi/workspaces/{id}/items`, `POST /bi/items/{id}/open`, `/move`, `/propose`, `/withdraw`, `/publish`, `/versions/{n}/restore`, `/history/{seq}/restore`, `/impact`, `PUT /bi/items/{id}` and `/favourite`, `DELETE /bi/items/{id}` | the BI power and workspace role each call needs (`bi-author`, contributor or member) |
| `bi:approve` | `POST /bi/items/{id}/approve`, `/reject` and `/endorse`, `POST /bi/bundle/import` and `POST /bi/imports/{id}/rollback` | `approve` and workspace member for approving (never your own proposal, four-eyes); promote: owner or member; certify: `bi-steward` |
| `bi:admin` | `PUT` and `DELETE /bi/workspaces/{id}`, `PUT` and `DELETE /bi/workspaces/{id}/members/{user}`, `PUT /bi/settings` | workspace admin, or `bi-admin` |
| `packs:admin` | `POST /admin/packs/{name}/load` and `/unload`, `PUT /admin/packs/{name}`, `POST /admin/registry/**` (install and roll back), `POST /admin/connectors/**`, deploy an archive (`POST /admin/packs/deploy`, `POST` and `DELETE /admin/packs/deploy/{id}`), `POST /admin/packs/{name}/rollback`, **Load data** (`POST /admin/packs/{name}/data`), the create-connectors step of a deploy (`POST /admin/packs/deploy/{id}/connectors`), and every write under `/admin/connectors` (`PUT`, `DELETE`, `.../enabled`, `.../reset`, `.../test`, `.../restore/{id}`), and **Retry now** (`POST /admin/retry/{area}`) | `admin` |
| `ai:use` | `POST /ai/chat`, `POST /ai/mcp/{server}/tools/{tool}` ([AI](#ai-and-mcp-apiv1ai-and-apiv1adminai)) and the BI hooks `POST /bi/ai/draft`, `/bi/ai/ask`, `/bi/ai/explain`, `/bi/ai/suggest`, `/bi/ai/explain-checks`, `/bi/ai/dataset-helper` ([BI with AI](#bi-with-ai-apiv1biai)) | any signed-in user, under the AI policy (and the BI power each hook needs) |
| `ai:admin` | `POST /admin/ai/test` | `admin` |

There is no `admin` scope and no `data:admin`: the server has no data-loading endpoint (data is loaded by your ETL, or staged by
`drishti.py data`; `loads:write` only *announces* that a batch landed), and users, roles, tokens, sign-in, caches, installing and uninstalling artifacts (`/admin/artifacts`), the audit log, shares, comments, notes, workspaces and
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
| `GET` | `/me/token` | **the token this call used**: `{id, user, scopes, expiresAt}` (`expiresAt` null for a read token with no expiry); `404` when the call was made with a session, not a `drk_` token. `drishti.py doctor` uses it to show the exact scopes in force |
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
| `POST` | `/shares` | sends a share. Body `{"kind": "trade", "id": "MX-20000001", "panel": "cashflows", "gateKind": "trade", "generation": 1674, "note": "…", "to": {"users": ["ravi"], "roles": ["risk"]}, "channels": {"inApp": true, "email": false}, "live": false, "postToThread": false, "picture": false, "view": "ctl.legs=2&f.cashflows.Rate=gt%3A1m"}` (`view` is the sender's view state as query text: the `ctl.*` control values and the `f.<panel>.<column>` column filters, nothing else is kept, at most `share.max-view-state` characters or `400 DRS-5001`; the recipient opens the view with them and the picture shows them; `GET /shares/{id}` answers it as `view`, absent for a share sent without; `picture: true` adds a watermarked PNG, see below; `postToThread` left out: the server's `share.post-to-thread` default applies); the as-of headers (or `asOf`/`knownAt`) say which date the page showed. `201 {id, link, pin, delivered, skipped: [{name, reason}], warnings: []}`. See below |
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

## BI with AI: `/api/v1/bi/ai`

Report from words, ask the data, explain this number and the authoring helpers ([BI_AI.md](BI_AI.md)). Off with the AI layer (`404 DRS-4015`); every call goes through the AI policy under the feature names `bi-draft`,
`bi-ask`, `bi-explain`, `bi-suggest`, `bi-explain-checks` and `bi-helper` (a subscription's narrative summary is `subscription-narrative`; masks, limits, roles, audit: `403 DRS-4018`, `429 DRS-4017`, `502`/`504 DRS-4016`); an answer that cannot be used is `422 DRS-4020`; a query the engine refuses keeps the engine's
code (`DRS-9020`, `DRS-9021`, `DRS-9025`). Responses are `Cache-Control: no-store`.

| Call | Needs | What it does | Token rule |
|---|---|---|---|
| `GET /api/v1/bi/ai/status` | signed in | `{enabled, values, draft, ask, explain, assist}`: what the page may show (`values` is `labels-only` or `aggregates`; `draft` and `assist` need `bi-author`) | GET: read |
| `POST /api/v1/bi/ai/draft` | `bi-author` | body `{dataset, request}`: `{yaml, valid, problems, dataset, saved: false}`; the YAML passed the report checker; nothing is stored | non-GET, not on `token-read-posts`: scope `ai:use` |
| `POST /api/v1/bi/ai/ask` | `bi`, and the right to read the report | body `{report \| dataset, question}`: `{columns, rows, rowCount, meta, question, query, visual, values, summary}`, run by the engine as the caller | non-GET: scope `ai:use` |
| `POST /api/v1/bi/ai/suggest` | `bi-author` | body `{dataset, goal, yaml?}`: `{dataset, suggestions[{id, kind: visual\|filter, type, title, why, visual, yaml}], applied: false}`; each suggestion passed the report checker and carries the YAML to add; ids the draft in `yaml` already uses are renamed; nothing is stored or applied | non-GET: scope `ai:use` |
| `POST /api/v1/bi/ai/explain-checks` | `bi-author` | body `{yaml}`: `{valid, problems[{level, code, path, message, explain?, fix?}], explained}`: the report checker's own problems, each with a plain explanation and a fix; with no problem the model is not called | non-GET: scope `ai:use` |
| `POST /api/v1/bi/ai/dataset-helper` | `bi-author` | body `{dataset}`: `{dataset, items[{id, kind: relationship\|measure\|column, label, why, yaml, data}], applied: false}`: relationships, measures (with descriptions) and column descriptions, each checked against the dataset's model before it is shown; nothing is stored | non-GET: scope `ai:use` |
| `POST /api/v1/bi/ai/explain` | `bi`, and the right to read the report | body `{report, page, visual, measure?, state?}`: `{measure, lineage{dataset, measure, columns, sources}, value, breakdown[{dimension, members[{member, value, share}]}], values, narrative}` | non-GET: scope `ai:use` |

```
POST /bi/ai/ask  {"report": "file:report:trades-overview", "question": "notional by desk"}     (as rita)   200
{"columns": [...], "rows": [["DESK-FX", 79000000.0], ["DESK-FI", 78000000.0], ["DESK-EQD", 77000000.0]], "rowCount": 3,
 "query": {"fields": ["trade.desk"], "measures": ["Notional"], "filters": [], "dates": {...}, "orderBy": [{"by": "Notional", "desc": true}], "limit": 200},
 "visual": "bar", "values": "labels-only", "summary": null}
```

The console passes `/bi/api/ai/*` to these (the same JSON, the signed-in identity).

## Scheduled reports

| Method | Path | What it does |
|---|---|---|
| `GET` | `/me/reports` | your reports, each with `nextRun` and its last runs (`runs`: `at`, `trigger`, `status`, `rows`, `target` or `error`) |
| `GET` | `/me/reports/{name}` | one of your reports with its `nextRun` and `runs`; `404` when you have none by that name |
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
