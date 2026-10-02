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
| Console route | anon | viewer | trader | risk (demoted to viewer) | qa-masked | author | approver | admin |
|---|---|---|---|---|---|---|---|---|
| `GET /t` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /v/trade/MX-20000001` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /v/qa-secret/S1` | 303 | 200 | 403 | 200 | 403 | 200 | 200 | 200 |
| `GET /v/counterparty/CP-MERIDIAN-RE` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /s?q=TRD%20limit%202` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /impact/counterparty/CP-MERIDIAN-RE` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /compare/trade/MX-20000001` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /w` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /w/shared/qa-author/ws1` | 303 | 200 | 404 | 404 | 200 | 200 | 404 | 404 |
| `GET /m` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /alerts` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /reports` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /account` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /studio` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /studio/reviews` | 303 | 403 | 403 | 403 | 403 | 200 | 200 | 200 |
| `GET /studio/reviews/P-000002` | 303 | 403 | 403 | 403 | 403 | 200 | 200 | 200 |
| `GET /studio/schema` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /studio/source/irs-fixfloat/1` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /admin/users` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/roles` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/packs` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/access` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/tokens` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/audit` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/health` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /admin/caches` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `GET /api/suggest?q=MX` | 401 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/view/trade/MX-20000001` | 401 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/raw/trade/MX-20000001` | 401 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/series/trade/MX-20000001?path=pnl1d` | 401 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/notes/trade/MX-20000001` | 401 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/calc/snippets?kind=trade` | 401 | 403 | 200 | 403 | 200 | 200 | 200 | 200 |
| `GET /api/calc/search?q=TRD%20limit%201` | 401 | 403 | 200 | 403 | 200 | 200 | 200 | 200 |
| `GET /api/calc/columns/trade?paths=book&limit=1` | 401 | 403 | 200 | 403 | 200 | 200 | 200 | 200 |
| `GET /api/pivot/records/netting-set/NS-MERIDIAN-RE-NY/trades` | 401 | 200 | 403 | 200 | 200 | 200 | 200 | 200 |
| `GET /api/pivot/saved/search/trade` | 401 | 404 | 404 | 404 | 404 | 404 | 404 | 404 |
| `GET /export/search.csv?q=TRD%20limit%201` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /export/trade/MX-20000001.json` | 303 | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| `GET /export/netting-set/NS-MERIDIAN-RE-NY/trades.csv` | 303 | 200 | 403 | 200 | 200 | 200 | 200 | 200 |
| `POST /admin/api/caches/engine/purge` | 303 | 403 | 403 | 403 | 403 | 403 | 403 | 200 |
| `POST /admin/api/users/qa-must/disable` | 303 | 400 | 400 | 400 | 400 | 400 | 400 | 400 |
| `POST /studio/reviews/P-000002/reject` | 303 | 500 | 500 | 500 | 500 | 403 | 400 | 400 |
