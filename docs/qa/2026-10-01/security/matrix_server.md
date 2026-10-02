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
| Endpoint | Documented rule | anon | viewer | trader | market-risk | qa-masked | author | approver | admin | API token (author) |
|---|---|---|---|---|---|---|---|---|---|---|
| `GET /api/v1/about` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/packs` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/packs` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sources` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras/irs-fixfloat/1` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras/irs-fixfloat/1/source` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras/problems` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/rachana/schema` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/business-date` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/health/live` | any token | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `POST /api/v1/command` | kind open (trade) | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `GET /api/v1/command/suggest?q=MX` | any; filtered | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/command/history` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/command/aliases` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/phrase?q=trades%20over%201m` | any | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 | 401 DRS-5010 |
| `GET /api/v1/views/trade/MX-20000001` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/views/counterparty/CP-MERIDIAN-RE` | kind counterparty | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/views/netting-set/NS-MERIDIAN-RE-NY/panels/trades/records` | kind netting-set | 401 DRS-5010 | 200 | 403 DRS-5002 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/entities/trade/MX-20000001/raw` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/history/trade/MX-20000001/diff` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/history/trade/MX-20000001/series?path=pnl1d` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/impact/counterparty/CP-MERIDIAN-RE` | kind counterparty | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/search?q=TRD%20limit%201` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/search?q=CPTY%20limit%201` | kind counterparty | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/search/csv?q=TRD%20limit%201` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/search/compare?from=2026-09-28&q=TRD%20MX-20000001` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/search/columns/trade?paths=book&limit=1` | calc + kind | 401 DRS-5010 | 403 DRS-5002 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `POST /api/v1/search/pivot/trade` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `POST /api/v1/search/pivot/trade/drill` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `POST /api/v1/search/pivot/trade/values` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `GET /api/v1/calc/settings` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/calc-snippets` | calc | 401 DRS-5010 | 403 DRS-5002 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `PUT /api/v1/me/calc-snippets/m-x` | calc | 401 DRS-5010 | 403 DRS-5002 | 200 1 | 200 1 | 200 1 | 200 1 | 200 1 | 200 1 | 403 DRS-5002 |
| `DELETE /api/v1/me/calc-snippets/m-x` | calc | 401 DRS-5010 | 403 DRS-5002 | 204 | 204 | 204 | 204 | 204 | 204 | 403 DRS-5002 |
| `GET /api/v1/me/layouts` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/layouts/irs-fixfloat/trade` | layout | 401 DRS-5010 | 403 DRS-5002 | 404 DRS-1001 | 404 DRS-1001 | 404 DRS-1001 | 200 | 404 DRS-1001 | 404 DRS-1001 | 200 |
| `PUT /api/v1/me/layouts/irs-fixfloat/trade` | layout + kind | 401 DRS-5010 | 403 DRS-5002 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `DELETE /api/v1/me/layouts/irs-fixfloat/trade` | layout | 401 DRS-5010 | 403 DRS-5002 | 204 | 204 | 204 | 204 | 204 | 204 | 403 DRS-5002 |
| `GET /api/v1/me/layouts/irs-fixfloat/trade/promotion` | author+studio-save | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 404 DRS-1001 | 404 DRS-1001 | 404 DRS-1001 | 404 DRS-1001 |
| `GET /api/v1/me/pivots` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/pivots/search/trade` | kind | 401 DRS-5010 | 404 DRS-1001 | 404 DRS-1001 | 404 DRS-1001 | 404 DRS-1001 | 200 | 404 DRS-1001 | 404 DRS-1001 | 200 |
| `PUT /api/v1/me/pivots/search/trade` | kind | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `DELETE /api/v1/me/pivots/search/trade` | kind | 401 DRS-5010 | 204 | 204 | 204 | 204 | 204 | 204 | 204 | 403 DRS-5002 |
| `GET /api/v1/me/pivots/panel/netting-set/trades/promotion` | author | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 404 DRS-1001 | 404 DRS-1001 | 200 |
| `GET /api/v1/me/settings` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `PATCH /api/v1/me/settings` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 403 DRS-5002 |
| `GET /api/v1/me/workspaces` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/monitors` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/alerts/rules` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/alerts` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/alerts/suggestions/trade` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/reports` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/tokens` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/me/studio-tests/irs-fixfloat` | any? | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 200 | 200 | 200 |
| `GET /api/v1/workspaces/shared` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/workspaces/shared/qa-author/ws1` | shared-with only | 401 DRS-5010 | 200 | 404 DRS-1001 | 404 DRS-1001 | 200 | 200 | 404 DRS-1001 | 404 DRS-1001 | 200 |
| `GET /api/v1/notes/trade/MX-20000001` | kind trade | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/studio/settings` | any | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `POST /api/v1/studio/preview` | kind (doc: authors use Studio) | 401 DRS-5010 | 422 DRS-2002 | 422 DRS-2002 | 422 DRS-2002 | 422 DRS-2002 | 422 DRS-2002 | 422 DRS-2002 | 422 DRS-2002 | 403 DRS-5002 |
| `GET /api/v1/studio/inferred/trade/MX-20000001` | kind | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras/proposals` | any (visible) | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras/proposals/P-000001` | visible | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 200 | 200 | 200 |
| `GET /api/v1/sutras/book/history` | any | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 200 | 200 | 200 |
| `GET /api/v1/packs/trading/overview` | any | 401 DRS-5010 | 200 TRDS | 200 TRDS | 200 TRDS | 200 TRDS | 200 TRDS | 200 TRDS | 200 TRDS | 200 TRDS |
| `GET /api/v1/auth/me` | signed-in | 401 DRS-5010 | 200 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `POST /api/v1/auth/login` | service only | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 |
| `POST /api/v1/auth/oidc` | service only | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 |
| `GET /api/v1/admin/users` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/users/qa-viewer` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/roles` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/role-definitions` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/role-definitions/qa-masked` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/audit?limit=1` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/status` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/caches` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/health` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/access?limit=1` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/access/stats` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/tokens` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/reports` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/packs` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `GET /api/v1/admin/registry` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | 403 DRS-5002 |
| `POST /api/v1/admin/users` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `PUT /api/v1/admin/users/qa-viewer` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/admin/users/qa-viewer/enabled` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/admin/users/qa-viewer/password` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `DELETE /api/v1/admin/users/qa-viewer` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `PUT /api/v1/admin/role-definitions/qa-evil` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `DELETE /api/v1/admin/role-definitions/qa-masked` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/admin/caches/engine/purge` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `PUT /api/v1/admin/packs/trading` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/admin/packs/trading/unload` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/admin/registry/x/1.0.0/install` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/admin/registry/x/rollback` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `DELETE /api/v1/admin/tokens/T39vAZnQ8BQd` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `DELETE /api/v1/admin/reports/qa-author/none` | admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | skip | 403 DRS-5002 |
| `POST /api/v1/sutras/proposals/P-000001/approve` | approver/admin, not own | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 422 DRS-2002 | skip | 403 DRS-5002 |
| `POST /api/v1/sutras/proposals/P-000001/reject` | approver/admin | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 200 | skip | 403 DRS-5002 |
| `POST /api/v1/sutras/proposals/P-000001/withdraw` | author of proposal only | 401 DRS-5010 | 409 DRS-2006 | 409 DRS-2006 | 409 DRS-2006 | 409 DRS-2006 | 409 DRS-2006 | 409 DRS-2006 | skip | 403 DRS-5002 |
| `POST /api/v1/sutras?note=x` | author+studio-save | 401 DRS-5010 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 403 DRS-5002 | 422 DRS-2002 | 422 DRS-2002 | skip | 403 DRS-5002 |
