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

The Drishti server exposes REST endpoints under `/api/v1`. The OpenAPI document is at `/api/docs`
and the Swagger UI at `/api/docs/ui`. Every error is RFC 7807 `problem+json` with a stable `code`
(`DRS-nnnn`). With security on (`drishti.security.enabled`), every call needs `Authorization: Bearer <HS256 token>`
minted by the console; the token's subject and roles decide what the caller may see. With security
off (local development), `X-Drishti-User` names the caller for the "recent" suggestions. User and
admin endpoints are in USER_MANAGEMENT.md; live streaming is in LIVE.md.

| Method | Path | Returns |
|---|---|---|
| `POST` | `/command` `{"text": "TRD IRS-48213 <GO>"}` | `{"ref": {"kind","id"}, "mnemonic"}`; `400 DRS-4001` if the command can't be read |
| `GET` | `/command/suggest?q=TRD%20IRS-4&limit=10` | `[{"type","mnemonic","kind","id","title","subtitle","complete"}]` |
| `GET` | `/views/{kind}/{id}` | `ViewModel` (below); `404 DRS-1001`, `504 DRS-1004` |
| `GET` | `/entities/{kind}/{id}/raw` | `{"ref","provenance","data"}`: the document as the source produced it |
| `GET` | `/sources` | the plugins, their capabilities and health, and any start failures |
| `GET` | `/sutras` · `/sutras/{name}/{version}` · `/sutras/problems` | the Sutra catalogue, one Sutra, and the load problems |
| `GET` | `/about` | version, build, Java, uptime, loaded Sutras, sources, security mode |
| `GET` / `PUT` / `DELETE` | `/me/monitors[/{name}]` | watchlists `{entities: [{kind, id}]}` (1–50); `GET /me/monitors/{name}` returns each row's title and strip |
| `GET` | `/me/monitors/{name}/stream` | SSE: one `row` event per changed entity (strip patches), multiplexed over one connection |
| `GET` / `PUT` / `DELETE` | `/me/alerts/rules[/{name}]` | alert rules `{kind, id, when, severity, message, enabled}`; `when` and `message` are Rachana-EL, checked on save (`DRS-2101`) |
| `GET` | `/me/alerts` · `/me/alerts/stream` · `/me/alerts/suggestions/{kind}` | fired alerts (newest first) · SSE `alert` events · the packs' suggested rules for a kind |
| `GET` | `/packs` | the enabled domain packs |
| `GET` / `PUT` / `DELETE` | `/me/workspaces[/{name}]` | the caller's workspaces: `{layout, panes: [{ref, follows, title}]}`; validated (known layout, 1–4 panes, entities the caller may open, no follow cycles) |

```bash
curl -s localhost:18480/api/v1/views/trade/IRS-48213 | jq '.strip[] | "\(.label): \(.text)"'
curl -s "localhost:18480/api/v1/command/suggest?q=NS-N" | jq '.[].complete'
```

## The ViewModel

The server returns **formatted text and a tone** for every value, never raw numbers to format, so all
clients show `−412,580` identically.

```jsonc
{
  "ref": {"kind": "trade", "id": "IRS-48213"}, "mnemonic": "TRD",
  "title": {"pill": "Trade · Interest rate swap", "id": "IRS-48213",
            "with": {"text": "Northbridge Capital LLP", "link": {"kind": "counterparty", "id": "CP-NORTHBRIDGE"}}},
  "strip": [{"label": "MTM (USD)", "text": "−412,580", "tone": "neg", "emphasis": true, "path": "$.mtm"}, …],
  "panels": [{"id": "cashflows", "kind": "table", "title": "Cashflows · Leg 1", "code": "CF · ladder", "key": "F3",
              "area": "main", "data": {"columns": […], "numeric": […], "rows": [{"cells": […], "path": "$.legs[0].cashflows[0]"}],
                                       "total": {…}}}, …],
  "keys": [{"key": "F7", "label": "Netting set", "action": "link", "link": {"kind": "netting-set", "id": "NS-NORTH-01", "mnemonic": "NSET"}}],
  "provenance": {"layout": "Sutra irs-vanilla v3 + inference", "fingerprint": "5343…eaf1", "source": "aero-risk", "generation": 1742, "live": true},
  "timings": {"fetch": 0.3, "layout": 0.1, "links": 1.2, "bind": 0.9, "total": 2.6}
}
```

| `data` by panel kind | Shape |
|---|---|
| `kv`, `status`, `provenance` | `{"fields": [Cell]}` |
| `table`, `ladder` | `{"columns", "numeric", "rows": [{"cells", "highlight", "path"}], "total", "more"}` |
| `tabs` | `{"layout": "tabs" \| "columns", "tabs": [{"title", "fields": [Cell]}]}` |
| `line`, `area` | `{"x", "series": [{"label", "values", "tone"}], "mark", "markText", "limit", "limitLabel", "source"}` |
| `hbar` | `{"bars": [{"label", "value", "text", "tone"}]}` |
| `links` | `{"links": [{"label", "text", "link", "badge", "status": "resolved" \| "pending" \| "missing"}]}` |

A `Cell` is `{label?, text, tone?, link?, emphasis?, path?}`. The `path` fields let live updates
patch single values. A panel whose binding failed carries `error` and no `data`; the rest
of the view is unaffected.

## Error codes

| Code | HTTP | Meaning |
|---|---|---|
| DRS-1001 | 404 | no source holds the entity |
| DRS-1002 | 404 | no source serves the kind |
| DRS-1003 | 502 | the source failed |
| DRS-1004 | 504 | the source timed out |
| DRS-2xxx | 422 | a Sutra problem (see RACHANA_REFERENCE.md) |
| DRS-4001 | 400 | the command could not be read |
| DRS-5001 | 400 | bad request |
