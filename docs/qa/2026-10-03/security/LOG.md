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
# Security QA log, Build workbench and additions (2026-10-03)

Scratch: server :18961 (security on, review on, studio-save on, HS256 secret, redact trader/counterpartyId/patientName), console :17961 with sign-in; work dir in the session scratchpad (`scripts/.workdir`); start with `scripts/start.sh` (BIND=true for file binding). Users (password `qa-password-12345`): qa-admin (admin), qa-author, qa-author2 (author), qa-approver, qa-narrow (kinds counterparty+netting-set, no raw), qa-masked (trade/counterparty/netting-set, no raw => masked fields), qa-narrow-author (author, kind counterparty), qa-viewer (viewer). Requests use bearer tokens minted like the console does (`scripts/sec.py`).

| Time | Check | Request | Expected | Observed | Verdict |
|---|---|---|---|---|---|
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `PATCH /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/duplicate` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/samples` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `DELETE /api/v1/builder/designs/dfc0896a157a/samples?name=brought` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/shape` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/preview` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/preview?sample=brought` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/autodesign` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/ops` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/versions` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/versions/0` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/undo` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/redo` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/check` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/propose` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/export` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `DELETE /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `DELETE /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `POST /api/v1/builder/designs/dfc0896a157a/save-file` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `GET /api/v1/builder/designs/dfc0896a157a/sync` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation None | `DELETE /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 401 leak=False {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 mis | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `PATCH /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/duplicate` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/samples` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `DELETE /api/v1/builder/designs/dfc0896a157a/samples?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/shape` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/preview` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/preview?sample=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/autodesign` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/ops` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/versions` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/versions/0` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/undo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/redo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/check` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/propose` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/export` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `DELETE /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-author2 | `DELETE /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `POST /api/v1/builder/designs/dfc0896a157a/save-file` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-author2 | `GET /api/v1/builder/designs/dfc0896a157a/sync` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-author2 | `DELETE /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `PATCH /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/duplicate` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/samples` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `DELETE /api/v1/builder/designs/dfc0896a157a/samples?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/shape` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/preview` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/preview?sample=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/autodesign` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/ops` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/versions` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/versions/0` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/undo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/redo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/check` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/propose` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 qa-vie | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/export` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `DELETE /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-viewer | `DELETE /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `POST /api/v1/builder/designs/dfc0896a157a/save-file` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-viewer | `GET /api/v1/builder/designs/dfc0896a157a/sync` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-viewer | `DELETE /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `PATCH /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/duplicate` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/samples` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `DELETE /api/v1/builder/designs/dfc0896a157a/samples?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/shape` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/preview` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/preview?sample=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/autodesign` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/ops` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/versions` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/versions/0` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/undo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/redo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/check` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/propose` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 qa-nar | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/export` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `DELETE /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-narrow | `DELETE /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `POST /api/v1/builder/designs/dfc0896a157a/save-file` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-narrow | `GET /api/v1/builder/designs/dfc0896a157a/sync` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-narrow | `DELETE /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `PATCH /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/duplicate` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/samples` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `DELETE /api/v1/builder/designs/dfc0896a157a/samples?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/shape` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/preview` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/preview?sample=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/autodesign` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/ops` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/versions` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/versions/0` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/undo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/redo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/check` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/propose` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/export` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `DELETE /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-approver | `DELETE /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `POST /api/v1/builder/designs/dfc0896a157a/save-file` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-approver | `GET /api/v1/builder/designs/dfc0896a157a/sync` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-approver | `DELETE /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `PATCH /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/duplicate` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/samples` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `DELETE /api/v1/builder/designs/dfc0896a157a/samples?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/shape` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/preview` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/preview?sample=brought` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/autodesign` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/ops` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/versions` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/versions/0` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/undo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/redo` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/check` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/propose` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/export` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `DELETE /api/v1/builder/designs/dfc0896a157a/share` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-admin | `DELETE /api/v1/builder/designs/dfc0896a157a/bind` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `POST /api/v1/builder/designs/dfc0896a157a/save-file` | 401/403/404, no leak | 403 leak=False {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:37:05 | isolation qa-admin | `GET /api/v1/builder/designs/dfc0896a157a/sync` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:05 | isolation qa-admin | `DELETE /api/v1/builder/designs/dfc0896a157a` | 401/403/404, no leak | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a/` | no leak, not 200 with data | 404 leak=False {"timestamp":"2026-10-03T16:37:20.720+00:00","status":404,"error":"Not | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/DFC0896A157A` | no leak, not 200 with data | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail" | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a;x=1` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs;x/dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs//dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"empty  | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/%2e/dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"'.' is | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a%00` | no leak, not 200 with data | 400 leak=False <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a.json` | no leak, not 200 with data | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail" | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/../designs/dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:37:20 | iso-variant | `GET /api/%76%31/builder/designs/dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"'v' is | PASS |
| 12:37:20 | iso-variant | `GET /api/v1;x/builder/designs/dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:37:20 | iso-variant | `HEAD /api/v1/builder/designs/dfc0896a157a` | no leak, not 200 with data | 404 leak=False  | PASS |
| 12:37:20 | iso-variant | `OPTIONS /api/v1/builder/designs/dfc0896a157a` | no leak, not 200 with data | 200 leak=False  | PASS |
| 12:37:20 | iso-variant | `TRACE /api/v1/builder/designs/dfc0896a157a` | no leak, not 200 with data | 405 leak=False  | PASS |
| 12:37:20 | iso-variant | `PUT /api/v1/builder/designs/dfc0896a157a` | no leak, not 200 with data | 405 leak=False {"timestamp":"2026-10-03T16:37:20.748+00:00","status":405,"error":"Met | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a/preview/` | no leak, not 200 with data | 404 leak=False {"timestamp":"2026-10-03T16:37:20.753+00:00","status":404,"error":"Not | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/shared/dfc0896a157a` | no leak, not 200 with data | 400 leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"the | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/shared/dfc0896a157a?token=x` | no leak, not 200 with data | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail" | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a/export.zip` | no leak, not 200 with data | 404 leak=False {"timestamp":"2026-10-03T16:37:20.767+00:00","status":404,"error":"Not | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought&name=x` | no leak, not 200 with data | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail" | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a?id=dfc0896a157a&user=qa-author` | no leak, not 200 with data | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail" | PASS |
| 12:37:20 | iso-variant | `GET /api/v1/builder/designs/dfc0896a157a` | no leak, not 200 with data | 404 leak=False {"type":"about:blank","title":"design not found","status":404,"detail" | PASS |
| 12:37:20 | id-oracle | `foreign vs random vs malformed id` | same status/body shape | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 no design / 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 no design / 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 no design | PASS |
| 12:37:20 | iso-order | `POST samples refs kind=unopenable on foreign design` | 404 not 403 (no kind oracle) | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 |  |
| 12:37:20 | list | `GET /api/v1/builder/designs as qa-author2` | only own | 200 [] | PASS |
| 12:37:20 | list | `GET /api/v1/builder/designs as qa-viewer` | only own | 200 [] | PASS |
| 12:37:20 | list | `GET /api/v1/builder/designs as qa-admin` | only own | 200 [] | PASS |
| 12:37:20 | share-owner-variant | `token owner='../qa-author'` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | share-owner-variant | `token owner='qa-author/../qa-author'` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | share-owner-variant | `token owner='QA-AUTHOR'` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | share-owner-variant | `token owner='qa-author '` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | share-owner-variant | `token owner='qa-author\x00'` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | share-owner-variant | `token owner=''` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | share-owner-variant | `token owner='%2e%2e'` | 404 | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 | PASS |
| 12:37:20 | id-guess | `20 random 12-hex ids` | all 404 | [404] | PASS |
| 12:37:38 | share view as narrow user | `GET /api/v1/builder/designs/shared/dfc0896a157a?token=..` | Sutra, ops, sample names only | 200 keys=['id', 'name', 'kind', 'status', 'sutra', 'rev', 'owner', 'base', 'sampleNames', 'ops'] |  |
| 12:37:38 | share leak scan | `body scan` | no SECRET / sample content / notes | SECRET present=False; MX-20000001 present=True; CP-MERIDIAN present=True; owner present=True |  |
| 12:37:38 | share anon | `no auth` | 401 | 401 | PASS |
| 12:37:38 | share no token | `missing token param` | 400/404 no trace | 400 {"type":"about:blank","title":"bad request","status":400,"detail":"the query parameter 'token' is re |  |
| 12:37:38 | share token on owner endpoint | `GET /api/v1/builder/designs/dfc0896a157a?share=cWEtYXV0aG9y.WCWLcPLRZqDODC..` | 404 (token grants only /shared) | 404 leak=False | PASS |
| 12:37:38 | share token on owner endpoint | `GET /api/v1/builder/designs/dfc0896a157a/samples/document?name=brought&sha..` | 404 (token grants only /shared) | 404 leak=False | PASS |
| 12:37:38 | share token on owner endpoint | `GET /api/v1/builder/designs/dfc0896a157a/preview?share=cWEtYXV0aG9y.WCWLcP..` | 404 (token grants only /shared) | 404 leak=False | PASS |
| 12:37:38 | share token on owner endpoint | `GET /api/v1/builder/designs/dfc0896a157a/export?token=cWEtYXV0aG9y.WCWLcPL..` | 404 (token grants only /shared) | 404 leak=False | PASS |
| 12:37:38 | share token on owner endpoint | `GET /api/v1/builder/designs/dfc0896a157a/versions?share=cWEtYXV0aG9y.WCWLc..` | 404 (token grants only /shared) | 404 leak=False | PASS |
| 12:37:38 | share tamper | `flip last char` | 404 | 404 | PASS |
| 12:37:38 | share tamper | `truncated` | 404 | 404 | PASS |
| 12:37:38 | share tamper | `empty secret` | 404 | 404 | PASS |
| 12:37:38 | share tamper | `only owner` | 404 | 404 | PASS |
| 12:37:38 | share tamper | `padded` | 404 | 404 | PASS |
| 12:37:38 | share tamper | `space` (raw space, test artifact: HTTP request line split) | 404 | 200 | invalid test; re-run with %20/%09/%0a/%00 = 404 | PASS |
| 12:37:38 | share tamper | `double dot` | 404 | 404 | PASS |
| 12:37:38 | share token vs other design | `N id + D token` | 404 | 404 | PASS |
| 12:37:38 | unshare by non-owner | `DELETE share` | 404 | 404 | PASS |
| 12:37:38 | unshare | `DELETE share` | 200 | 200 |  |
| 12:37:38 | share after revoke | `old token` | 404 at once | 404 | PASS |
| 12:37:38 | share new token | `T2` | 200 | 200 |  |
| 12:37:38 | old token after re-share | `T1` | 404 | 404 | PASS |
| 12:37:38 | renew kills previous | `T2 after renew` | 404 | 404 | PASS |
| 12:37:38 | renewed works | `T3` | 200 | 200 |  |
| 12:37:38 | duplicate share flag | `duplicate of shared design` | shared=false | 201 shared=False | PASS |
| 12:37:38 | old token on duplicate | `T3 on dup id` | 404 | 404 | PASS |
| 12:38 | note | share view | sample names | the shared payload includes sample names `trade MX-20000001` / `counterparty CP-MERIDIAN-RE` (entity ids of kinds the link holder, qa-narrow, may not open), plus `owner` and the whole Sutra text. No sample contents, notes. | INFO (SEC3-xx) |
| 12:38:27 | masked/preview ref | `GET /api/v1/builder/designs/<D>/preview?sample=trade%20MX-20000001` | no unmasked trader | 422 leak=[] {"type":"about:blank","title":"sutra invalid","status":422," | PASS |
| 12:38:27 | masked/preview default | `GET /api/v1/builder/designs/<D>/preview` | no unmasked trader | 422 leak=[] {"type":"about:blank","title":"sutra invalid","status":422," | PASS |
| 12:38:27 | masked/shape | `POST /api/v1/builder/designs/<D>/shape` | no unmasked trader | 200 leak=['TRDR-ASHAH'] {"schema":{"$schema":"https://json-schema.org/draft/2020-12/ | INFO: only inside free text (timeline description "Captured in Murex by TRDR-ASHAH"); the masked field itself shows bullets |
| 12:38:27 | masked/check | `POST /api/v1/builder/designs/<D>/check` | no unmasked trader | 422 leak=[] {"type":"about:blank","title":"sutra invalid","status":422," | PASS |
| 12:38:27 | masked/autodesign | `POST /api/v1/builder/designs/<D>/autodesign` | no unmasked trader | 200 leak=[] {"yaml":"rachana: 1\nsutra: trade-auto\nversion: 1\ndescript | PASS |
| 12:38:27 | masked/read | `GET /api/v1/builder/designs/<D>` | no unmasked trader | 200 leak=[] {"id":"9417bddf0a5b","name":"masked","scratch":false,"kind": | PASS |
| 12:38:27 | masked/versions | `GET /api/v1/builder/designs/<D>/versions` | no unmasked trader | 200 leak=[] {"versions":[{"n":0,"at":1791045507585,"ops":"start","curren | PASS |
| 12:38:27 | masked/doc of ref | `GET /api/v1/builder/designs/<D>/samples/document?name=trade%20MX-20000001` | no unmasked trader | 400 leak=[] {"type":"about:blank","title":"bad request","status":400,"de | PASS |
| 12:38:27 | masked/ops | `POST /api/v1/builder/designs/<D>/ops` | no unmasked trader | 409 leak=[] {"type":"about:blank","title":"stale revision","status":409, | PASS |
| 12:38:27 | masked/undo | `POST /api/v1/builder/designs/<D>/undo` | no unmasked trader | 200 leak=[] {"rev":4,"yaml":"# Project Drishti · Any data. Any domain. O | PASS |
| 12:38:27 | masked/propose | `POST /api/v1/builder/designs/<D>/propose` | no unmasked trader | 422 leak=[] {"type":"about:blank","title":"sutra invalid","status":422," | PASS |
| 12:38:27 | masked/export | `GET export` | zip | 200 |  |
| 12:38:27 | masked/export contents | `masked/pack.yaml, masked/sutras/custom/masked.v1.sutra.yaml, masked/tests/masked/doc.json, masked/tests/masked/expect.yaml, masked/samples/trade/doc.json, masked/README.md` | no unmasked trader; refs not exported or masked | leak=[] files=6 | PASS |
| 12:38:53 | masked2/preview | `GET /api/v1/builder/designs/<D>/preview` | trader shown as bullets, never the value | 200 leak=True bullets=True | INFO: only inside free text (timeline description "Captured in Murex by TRDR-ASHAH"); the masked field itself shows bullets |
| 12:38:53 | masked2/check | `POST /api/v1/builder/designs/<D>/check` | trader shown as bullets, never the value | 200 leak=False bullets=False | PASS |
| 12:38:53 | masked2/shape | `POST /api/v1/builder/designs/<D>/shape` | trader shown as bullets, never the value | 200 leak=True bullets=True | INFO: only inside free text (timeline description "Captured in Murex by TRDR-ASHAH"); the masked field itself shows bullets |
| 12:38:53 | masked2/propose | `POST /api/v1/builder/designs/<D>/propose` | trader shown as bullets, never the value | 202 leak=False bullets=False | PASS |
| 12:38:53 | masked2/proposal as qa-admin | `GET proposals/P-000001` | no unmasked trader | 200 leak=False len=15469 | PASS |
| 12:38:53 | masked2/proposal as qa-approver | `GET proposals/P-000001` | no unmasked trader | 200 leak=False len=15469 | PASS |
| 12:38:53 | masked2/proposals list | `GET` | no trader | leak=False | PASS |
| 12:38:53 | masked2/export | `GET export` | no trader; names | files=['m3/pack.yaml', 'm3/sutras/studio/irs-fixfloat.v1.sutra.yaml', 'm3/README.md'] leak=False | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras [text/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras [text/plain]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras [application/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras/ [text/yaml]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.527+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras/ [text/plain]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.530+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras/ [application/yaml]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.532+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras;x=1 [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras;x=1 [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras;x=1 [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras?note=a [text/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras?note=a [text/plain]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/sutras?note=a [application/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1//sutras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1//sutras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1//sutras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/%73utras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/%73utras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `POST /api/v1/%73utras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-viewer | `PUT /api/v1/sutras` | refused | 405 {"timestamp":"2026-10-03T16:39:54.539+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-viewer | `PATCH /api/v1/sutras/qa-evil` | refused | 404 {"timestamp":"2026-10-03T16:39:54.542+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-viewer | `PUT /api/v1/sutras/qa-evil/1` | refused | 405 {"timestamp":"2026-10-03T16:39:54.543+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-viewer | `DELETE /api/v1/sutras/abs` | refused | 404 {"timestamp":"2026-10-03T16:39:54.544+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-viewer | `DELETE /api/v1/sutras/abs/1` | refused | 405 {"timestamp":"2026-10-03T16:39:54.546+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras [text/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras [text/plain]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras [application/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras/ [text/yaml]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.551+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras/ [text/plain]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.553+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras/ [application/yaml]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.554+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras;x=1 [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras;x=1 [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras;x=1 [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras?note=a [text/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras?note=a [text/plain]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/sutras?note=a [application/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1//sutras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1//sutras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1//sutras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/%73utras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/%73utras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `POST /api/v1/%73utras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-narrow | `PUT /api/v1/sutras` | refused | 405 {"timestamp":"2026-10-03T16:39:54.561+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-narrow | `PATCH /api/v1/sutras/qa-evil` | refused | 404 {"timestamp":"2026-10-03T16:39:54.563+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-narrow | `PUT /api/v1/sutras/qa-evil/1` | refused | 405 {"timestamp":"2026-10-03T16:39:54.564+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-narrow | `DELETE /api/v1/sutras/abs` | refused | 404 {"timestamp":"2026-10-03T16:39:54.565+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-narrow | `DELETE /api/v1/sutras/abs/1` | refused | 405 {"timestamp":"2026-10-03T16:39:54.566+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras [text/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras [text/plain]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras [application/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras/ [text/yaml]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.571+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras/ [text/plain]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.572+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras/ [application/yaml]` | refused | 404 {"timestamp":"2026-10-03T16:39:54.574+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras;x=1 [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras;x=1 [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras;x=1 [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras?note=a [text/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras?note=a [text/plain]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/sutras?note=a [application/yaml]` | refused | 403 {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1//sutras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1//sutras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1//sutras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/%73utras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/%73utras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `POST /api/v1/%73utras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save qa-masked | `PUT /api/v1/sutras` | refused | 405 {"timestamp":"2026-10-03T16:39:54.580+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-masked | `PATCH /api/v1/sutras/qa-evil` | refused | 404 {"timestamp":"2026-10-03T16:39:54.582+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-masked | `PUT /api/v1/sutras/qa-evil/1` | refused | 405 {"timestamp":"2026-10-03T16:39:54.583+00:00","status":405,"e | PASS |
| 12:39:54 | policy save qa-masked | `DELETE /api/v1/sutras/abs` | refused | 404 {"timestamp":"2026-10-03T16:39:54.585+00:00","status":404,"e | PASS |
| 12:39:54 | policy save qa-masked | `DELETE /api/v1/sutras/abs/1` | refused | 405 {"timestamp":"2026-10-03T16:39:54.586+00:00","status":405,"e | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras [text/yaml]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras [text/plain]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras [application/yaml]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras/ [text/yaml]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras/ [text/plain]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras/ [application/yaml]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras;x=1 [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras;x=1 [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras;x=1 [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras?note=a [text/yaml]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras?note=a [text/plain]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1/sutras?note=a [application/yaml]` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `POST /api/v1//sutras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1//sutras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1//sutras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1/%73utras [text/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1/%73utras [text/plain]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `POST /api/v1/%73utras [application/yaml]` | refused | 400 {"title":"bad request","status":400,"code":"DRS-5001","detai | PASS |
| 12:39:54 | policy save None | `PUT /api/v1/sutras` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `PATCH /api/v1/sutras/qa-evil` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `PUT /api/v1/sutras/qa-evil/1` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `DELETE /api/v1/sutras/abs` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy save None | `DELETE /api/v1/sutras/abs/1` | refused | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","d | PASS |
| 12:39:54 | policy propose qa-viewer | `POST /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 qa-viewer is not | PASS |
| 12:39:54 | policy propose qa-viewer | `POST /api/v1/builder/designs/<own>/propose/` | 403 (PATCH of own design 200) | 404 {"timestamp":"2026-10-03T16:39:54.602+00:00","status":404,"error":"Not Found","path":"/api | PASS |
| 12:39:54 | policy propose qa-viewer | `POST /api/v1/builder/designs/<own>/propose;x=1` | 403 (PATCH of own design 200) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path parameters (;) are no | PASS |
| 12:39:54 | policy propose qa-viewer | `PUT /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 405 {"timestamp":"2026-10-03T16:39:54.604+00:00","status":405,"error":"Method Not Allowed","pa | PASS |
| 12:39:54 | policy propose qa-viewer | `GET /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 405 {"timestamp":"2026-10-03T16:39:54.605+00:00","status":405,"error":"Method Not Allowed","pa | PASS |
| 12:39:54 | policy propose qa-viewer | `POST /api/v1/builder/designs/<own>/bind` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:39:54 | policy propose qa-viewer | `POST /api/v1/builder/designs/<own>/save-file` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:39:54 | policy propose qa-viewer | `PATCH /api/v1/builder/designs/<own>` | 403 (PATCH of own design 200) | 200 {"id":"c69b2aab7cdf","name":"n","scratch":false,"kind":"qaevil","status":"draft","rev":1," | PASS |
| 12:39:54 | policy propose qa-narrow | `POST /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 qa-narrow is not | PASS |
| 12:39:54 | policy propose qa-narrow | `POST /api/v1/builder/designs/<own>/propose/` | 403 (PATCH of own design 200) | 404 {"timestamp":"2026-10-03T16:39:54.613+00:00","status":404,"error":"Not Found","path":"/api | PASS |
| 12:39:54 | policy propose qa-narrow | `POST /api/v1/builder/designs/<own>/propose;x=1` | 403 (PATCH of own design 200) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path parameters (;) are no | PASS |
| 12:39:54 | policy propose qa-narrow | `PUT /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 405 {"timestamp":"2026-10-03T16:39:54.615+00:00","status":405,"error":"Method Not Allowed","pa | PASS |
| 12:39:54 | policy propose qa-narrow | `GET /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 405 {"timestamp":"2026-10-03T16:39:54.616+00:00","status":405,"error":"Method Not Allowed","pa | PASS |
| 12:39:54 | policy propose qa-narrow | `POST /api/v1/builder/designs/<own>/bind` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:39:54 | policy propose qa-narrow | `POST /api/v1/builder/designs/<own>/save-file` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:39:54 | policy propose qa-narrow | `PATCH /api/v1/builder/designs/<own>` | 403 (PATCH of own design 200) | 200 {"id":"7bda715a8d7b","name":"n","scratch":false,"kind":"qaevil","status":"draft","rev":1," | PASS |
| 12:39:54 | policy propose qa-masked | `POST /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 qa-masked is not | PASS |
| 12:39:54 | policy propose qa-masked | `POST /api/v1/builder/designs/<own>/propose/` | 403 (PATCH of own design 200) | 404 {"timestamp":"2026-10-03T16:39:54.627+00:00","status":404,"error":"Not Found","path":"/api | PASS |
| 12:39:54 | policy propose qa-masked | `POST /api/v1/builder/designs/<own>/propose;x=1` | 403 (PATCH of own design 200) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path parameters (;) are no | PASS |
| 12:39:54 | policy propose qa-masked | `PUT /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 405 {"timestamp":"2026-10-03T16:39:54.631+00:00","status":405,"error":"Method Not Allowed","pa | PASS |
| 12:39:54 | policy propose qa-masked | `GET /api/v1/builder/designs/<own>/propose` | 403 (PATCH of own design 200) | 405 {"timestamp":"2026-10-03T16:39:54.633+00:00","status":405,"error":"Method Not Allowed","pa | PASS |
| 12:39:54 | policy propose qa-masked | `POST /api/v1/builder/designs/<own>/bind` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:39:54 | policy propose qa-masked | `POST /api/v1/builder/designs/<own>/save-file` | 403 (PATCH of own design 200) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:39:54 | policy propose qa-masked | `PATCH /api/v1/builder/designs/<own>` | 403 (PATCH of own design 200) | 200 {"id":"d5deac676ccc","name":"n","scratch":false,"kind":"qaevil","status":"draft","rev":1," | PASS |
| 12:39:54 | policy approve qa-viewer | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy approve qa-viewer | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.686+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy approve qa-viewer | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-viewer | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-viewer | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.691+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy reject qa-viewer | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-viewer | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-viewer | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.701+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy withdraw qa-viewer | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 404 {"type":"about:blank","title":"proposal not found","status":404,"detai | PASS |
| 12:39:54 | policy approve qa-narrow | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy approve qa-narrow | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.706+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy approve qa-narrow | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-narrow | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-narrow | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.710+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy reject qa-narrow | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-narrow | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-narrow | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.714+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy withdraw qa-narrow | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 404 {"type":"about:blank","title":"proposal not found","status":404,"detai | PASS |
| 12:39:54 | policy approve qa-masked | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy approve qa-masked | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.719+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy approve qa-masked | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-masked | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-masked | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.724+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy reject qa-masked | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-masked | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-masked | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.727+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy withdraw qa-masked | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 404 {"type":"about:blank","title":"proposal not found","status":404,"detai | PASS |
| 12:39:54 | policy approve qa-author2 | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy approve qa-author2 | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.731+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy approve qa-author2 | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-author2 | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-author2 | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.737+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy reject qa-author2 | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-author2 | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-author2 | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.742+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy withdraw qa-author2 | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 404 {"type":"about:blank","title":"proposal not found","status":404,"detai | PASS |
| 12:39:54 | policy approve qa-mauth | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy approve qa-mauth | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.745+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy approve qa-mauth | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-mauth | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-mauth | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.748+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy reject qa-mauth | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-mauth | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-mauth | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.751+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy withdraw qa-mauth | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 404 {"type":"about:blank","title":"proposal not found","status":404,"detai | PASS |
| 12:39:54 | policy approve None | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy approve None | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy approve None | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy reject None | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy reject None | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy reject None | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy withdraw None | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy withdraw None | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy withdraw None | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:39:54 | policy approve qa-author | `POST /api/v1/sutras/proposals/P-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy approve qa-author | `POST /api/v1/sutras/proposals/P-000003/approve/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.757+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy approve qa-author | `POST /api/v1/sutras/proposals/p-000003/approve` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-author | `POST /api/v1/sutras/proposals/P-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy reject qa-author | `POST /api/v1/sutras/proposals/P-000003/reject/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.760+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy reject qa-author | `POST /api/v1/sutras/proposals/p-000003/reject` | 403 unless owner withdraw | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:39:54 | policy withdraw qa-author | `POST /api/v1/sutras/proposals/P-000003/withdraw` | 403 unless owner withdraw | 200 {"id":"P-000003","name":"abs","version":1,"note":"policy test","author | PASS |
| 12:39:54 | policy withdraw qa-author | `POST /api/v1/sutras/proposals/P-000003/withdraw/` | 403 unless owner withdraw | 404 {"timestamp":"2026-10-03T16:39:54.765+00:00","status":404,"error":"Not | PASS |
| 12:39:54 | policy withdraw qa-author | `POST /api/v1/sutras/proposals/p-000003/withdraw` | 403 unless owner withdraw | 404 {"type":"about:blank","title":"proposal not found","status":404,"detai | PASS |
| 12:39:54 | policy state | `P-000003` | pending/withdrawn only by owner | withdrawn |  |

| 12:44 | note | policy run 1 | | first run flagged HTTP 400 refusals (`;`, `//`, `%73` paths) and 409 on already-withdrawn proposals as FAIL: criteria too strict; rows replaced by the corrected rerun | |
| 12:40:38 | four-eyes self approve | `POST approve as qa-author roles=['author', 'approver']` | refused (nobody approves their own) | 403 {"type":"about:blank","title":"four eyes","status":403,"detail":"DRS-2007 four eyes: qa-au | PASS |
| 12:40:38 | four-eyes self approve | `POST approve as qa-author roles=['admin']` | refused (nobody approves their own) | 403 {"type":"about:blank","title":"four eyes","status":403,"detail":"DRS-2007 four eyes: qa-au | PASS |
| 12:40:38 | four-eyes self approve | `POST approve as qa-author roles=['author', 'approver', 'admin']` | refused (nobody approves their own) | 403 {"type":"about:blank","title":"four eyes","status":403,"detail":"DRS-2007 four eyes: qa-au | PASS |
| 12:40:38 | four-eyes self approve (valid proposal) | `approve as qa-author roles=['author', 'approver']` | 403 four eyes | 403 {"type":"about:blank","title":"four eyes","status":403,"detail":"DRS-2007 four e | PASS |
| 12:40:38 | four-eyes self approve (valid proposal) | `approve as qa-author roles=['admin']` | 403 four eyes | 403 {"type":"about:blank","title":"four eyes","status":403,"detail":"DRS-2007 four e | PASS |
| 12:40:38 | four-eyes author2 (not approver) | `approve` | 403 | 403 | PASS |
| 12:40:39 | four-eyes real approval | `qa-approver approves` | 200 | 200 {"id":"P-000007","name":"qa-flow","version":1,"note":"flow","author":"qa-author" | PASS |
| 12:40:39 | design status after approval | `GET design` | live(v1) | live(v1) |  |
| 12:40:39 | approved Sutra readable | `GET source` | 200 | 200 |  |
| 12:40:39 | edit after live | `PATCH` | status draft again | draft |  |
| 12:40:50 | binding off: status | `GET binding` | enabled=false | 200 {"enabled":false,"dirs":["/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8f | PASS |
| 12:40:50 | binding off qa-author | `POST /api/v1/builder/designs/<D>/bind {'file': 'bo.yaml'}` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-author | `POST /api/v1/builder/designs/<D>/save-file None` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-author | `GET /api/v1/builder/designs/<D>/sync None` | 403 (sync: 200 no change) | 200 {"id":"907d95f211e1","name":"bindoff","scratch":false,"kind":"x","status":"draft","rev":1, | PASS |
| 12:40:50 | binding off qa-author | `POST /api/v1/builder/designs/<D>/bind {'file': '../../etc/x.yaml'}` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-admin | `POST /api/v1/builder/designs/<D>/bind {'file': 'bo.yaml'}` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-admin | `POST /api/v1/builder/designs/<D>/save-file None` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-admin | `GET /api/v1/builder/designs/<D>/sync None` | 403 (sync: 200 no change) | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 no design | PASS (404: not their design) |
| 12:40:50 | binding off qa-admin | `POST /api/v1/builder/designs/<D>/bind {'file': '../../etc/x.yaml'}` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-viewer | `POST /api/v1/builder/designs/<D>/bind {'file': 'bo.yaml'}` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-viewer | `POST /api/v1/builder/designs/<D>/save-file None` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | binding off qa-viewer | `GET /api/v1/builder/designs/<D>/sync None` | 403 (sync: 200 no change) | 404 {"type":"about:blank","title":"design not found","status":404,"detail":"DRS-5006 no design | PASS (404: not their design) |
| 12:40:50 | binding off qa-viewer | `POST /api/v1/builder/designs/<D>/bind {'file': '../../etc/x.yaml'}` | 403 (sync: 200 no change) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 binding a design | PASS |
| 12:40:50 | mass assignment via PATCH | `PATCH {boundFile,shareHash,owner,status,rev}` | ignored | 200 bound=None status=draft rev=1 | PASS |
| 12:40:50 | mass assignment via create | `POST {owner,id,status,boundFile,shareHash}` | ignored | 201 id=52a7757e5d4e status=draft bound=None shared=False | PASS |
| 12:40:50 | mass assignment owner | `author2 list after create-with-owner` | no foreign design | ok | PASS |
| 12:42:18 | bind non-author qa-viewer | `POST bind` | 403 | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:42:18 | bind non-author qa-narrow | `POST bind` | 403 | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:42:18 | bind non-author qa-masked | `POST bind` | 403 | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5002 bindin | PASS |
| 12:42:18 | bind anon | `POST bind` | 401 | 401 | PASS |
| 12:42:18 | bind path | `file='../secret_sibling.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='../../etc/passwd.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='a/../../secret_sibling.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='sub/../../secret_sibling.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='/etc/passwd.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='/tmp/x.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='..\\secret_sibling.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='x.yaml\x00.txt'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='%2e%2e/secret_sibling.yaml'` | 400, nothing bound | 200 bound=%2e%2e/secret_sibling.yaml leak=False  | CHECK (200 bound=%2e%2e/secret_sibling.yaml) |
| 12:42:18 | bind path | `file='..%2fsecret_sibling.yaml'` | 400, nothing bound | 200 bound=..%2fsecret_sibling.yaml leak=False  | CHECK (200 bound=..%2fsecret_sibling.yaml) |
| 12:42:18 | bind path | `file='....//secret_sibling.yaml'` | 400, nothing bound | 200 bound=....//secret_sibling.yaml leak=False  | CHECK (200 bound=....//secret_sibling.yaml) |
| 12:42:18 | bind path | `file='./../secret_sibling.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='file:///etc/passwd.yaml'` | 400, nothing bound | 200 bound=file:///etc/passwd.yaml leak=False  | CHECK (200 bound=file:///etc/passwd.yaml) |
| 12:42:18 | bind path | `file='linkdir/x.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='linkdir/new.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='linkdir/../secret.yaml'` | 400, nothing bound | 200 bound=linkdir/../secret.yaml leak=False  | CHECK (200 bound=linkdir/../secret.yaml) |
| 12:42:18 | bind path | `file='x.txt'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='noext'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='.yaml'` | 400, nothing bound | 200 bound=.yaml leak=False  | CHECK (200 bound=.yaml) |
| 12:42:18 | bind path | `file='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` | 400, nothing bound | 200 bound=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | CHECK (200 bound=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.yaml) |
| 12:42:18 | bind path | `file='sub/'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='//etc/x.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='C:\\x.yaml'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='~/x.yaml'` | 400, nothing bound | 200 bound=~/x.yaml leak=False  | CHECK (200 bound=~/x.yaml) |
| 12:42:18 | bind path | `file='$HOME/x.yaml'` | 400, nothing bound | 200 bound=$HOME/x.yaml leak=False  | CHECK (200 bound=$HOME/x.yaml) |
| 12:42:18 | bind path | `file='con.yaml\n'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='x.yaml '` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind path | `file='x.YAML'` | 400, nothing bound | 400 bound=None leak=False {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'fil | PASS |
| 12:42:18 | bind symlink file -> outside | `file=link.yaml (symlink to <work>/outside/secret.yaml)` | refused (target outside Sutra dir) | 200 sutra-has-marker=True | FAIL |
| 12:42:18 | symlink read via GET design | `GET design after bind link.yaml` | no outside content | sutra-has-marker=True | FAIL |
| 12:42:18 | bind symlink inside dir -> existing.yaml | `file=sub/inner.yaml` | inside dir: allowed | 200 |  |
| 12:42:19 | bind onto another Sutra's file | `file=existing.yaml` | allowed? (author, dev setting) | 200 adopted other-live=True |  |
| 12:42:19 | save-file overwrites another Sutra file with governance ON | `POST save-file` | refused or proposal (review is on) | 200 {"id":"ba5529adfe40","name":"overwrite","scratch":false,"kind":"qabound","status |  |
| 12:42:19 | existing.yaml changed on disk | `cat existing.yaml` | unchanged if governance enforced | CHANGED | FAIL (governance bypass) |
| 12:42:22 | evil Sutra live without review | `GET sutras (hot reload)` | qa-bound-evil absent unless approved | present=False other-live present=False | PASS |
| 12:42:22 | proposal created for it? | `GET proposals` | a proposal exists for review | ['abs', 'abs', 'irs-fixfloat'] |  |
| 12:42:22 | second author binds same file | `POST bind` | any author may bind any file in dir | 200 |  |
| 12:42:22 | second author saves over first's file | `save-file` | 409 or allowed | 200 {"id":"7c33076f3ab7","name":"clash","scratch":false,"kind":"qabound","status":"d |  |
| 12:42:22 | save-file .tmp symlink pre-planted | `link tmpl.yaml.tmp -> outside/planted.txt then save-file` | does not write outside | 200 planted-outside-exists=True | FAIL |
| 12:42:22 | nested new path | `deep/a/b/c.yaml` | created under dir | 200 exists=True |  |
| 12:42:22 | sync | `GET sync` | 200 | 200 {"id":"9158bf0a75a7","name":"nested","scratch":false,"kind": |  |
| 12:42:47 | setup live Sutra | `file live-victim.v1.sutra.yaml dropped in dir` | visible | 200 |  |
| 12:42:47 | bind live file | `bind` | 200 | 200 |  |
| 12:42:47 | save-file over live v1 | `POST save-file (review on, four-eyes on)` | refused / proposal | 200 {"id":"5f909c9c79d0","name":"rewrite","scratch":false,"kind" |  |
| 12:42:50 | live v1 source after | `GET source` | unchanged unless approved | TAMPERED | FAIL (live Sutra changed without review) |
| 12:42:53 | new Sutra via save-file | `bind sneaky-new.v1.sutra.yaml + save-file` | not live without approval | 200 live=True | FAIL (live without review) |
| 12:42:53 | viewer bind | `bind` | 403 | 403 | PASS |
| 12:43:31 | import baseline | `POST import (245 B) as qa-viewer` | 201 | 201 0.0s {"designs":[{"id":"9503fb7ac84a","name":"imp1","scratch":false,"kind":"impkind","status":"draft","rev":1,"shar |  |
| 12:43:31 | import slip names | `POST import (1198 B) as qa-viewer` | 201, only b imported | 201 0.0s {"designs":[{"id":"d6cc548f52d1","name":"imp2","scratch":false,"kind":"impkind","status":"draft","rev":1,"shar |  |
| 12:43:31 | import slip result | `designs made` | only b | ['imp2'] | PASS |
| 12:43:31 | import slip files | `canary files` | absent | [False, False] | PASS |
| 12:43:31 | import zip bomb 30MB unpacked | `POST import (30972 B) as qa-viewer` | 413 | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 the zip unpacks to more than |  |
| 12:43:32 | import 200MB zeros | `POST import (203960 B) as qa-viewer` | 413 fast, no OOM | 413 0.1s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 the zip unpacks to more than |  |
| 12:43:32 | import 20000 entries | `POST import (1997802 B) as qa-viewer` | 413 over entry cap | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 the zip holds more than 1000 |  |
| 12:43:32 | import duplicate entry names | `POST import (434 B) as qa-viewer` | 201 (two designs?) or refuse | 201 0.0s {"designs":[{"id":"d6baebee12b1","name":"imp6","scratch":false,"kind":"impkind","status":"draft","rev":1,"shar |  |
| 12:43:32 | import garbage | `POST import (9 B) as qa-viewer` | 400 | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 no Sutra (*.sutra.yaml) was found  |  |
| 12:43:32 | import empty | `POST import (0 B) as qa-viewer` | 400 | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 no Sutra (*.sutra.yaml) was found  |  |
| 12:43:32 | import truncated | `POST import (60 B) as qa-viewer` | 400 | 200 0.0s  |  |
| 12:43:32 | import wrong content type json | `POST import (228 B) as qa-viewer` | 415 | 415 0.0s {"timestamp":"2026-10-03T16:43:32.500+00:00","status":415,"error":"Unsupported Media Type","path":"/api/v1/bui |  |
| 12:43:32 | import stored | `POST import (246 B) as qa-viewer` | 201 | 201 0.0s {"designs":[{"id":"5a2a193ec2db","name":"imp9","scratch":false,"kind":"impkind","status":"draft","rev":1,"shar |  |
| 12:43:32 | import yaml billion laughs | `POST import (299 B) as qa-viewer` | 201 imported-as-invalid or 4xx, no hang/500/exec | 201 0.0s {"designs":[{"id":"d408a9ec37cf","name":"a","scratch":false,"kind":"sample","status":"draft","rev":1,"shared": |  |
| 12:43:32 | import yaml deep flow nesting 5000 | `POST import (240 B) as qa-viewer` | 201 imported-as-invalid or 4xx, no hang/500/exec | 201 0.1s {"designs":[{"id":"32e2189926bc","name":"a","scratch":false,"kind":"sample","status":"draft","rev":1,"shared": |  |
| 12:43:32 | import yaml deep block map 3000 | `POST import (12373 B) as qa-viewer` | 201 imported-as-invalid or 4xx, no hang/500/exec | 201 0.0s {"designs":[{"id":"a7766cfa014d","name":"a","scratch":false,"kind":"sample","status":"draft","rev":1,"shared": |  |
| 12:43:32 | import yaml !!java tag URLClassLoader | `POST import (281 B) as qa-viewer` | 201 imported-as-invalid or 4xx, no hang/500/exec | 201 0.0s {"designs":[{"id":"2812dec7d2ff","name":"a","scratch":false,"kind":"sample","status":"draft","rev":1,"shared": |  |
| 12:43:32 | import yaml !!java ProcessBuilder | `POST import (257 B) as qa-viewer` | 201 imported-as-invalid or 4xx, no hang/500/exec | 201 0.0s {"designs":[{"id":"1bfa27b0e926","name":"a","scratch":false,"kind":"sample","status":"draft","rev":1,"shared": |  |
| 12:43:32 | import java tag canary | `/tmp/pwned_javatag` | absent | False | PASS |
| 12:43:32 | import json samples deep | `POST import (855 B) as qa-viewer` | skipped, not 500 | 201 0.0s {"designs":[{"id":"05903a3f2ea8","name":"imp11","scratch":false,"kind":"impkind","status":"draft","rev":1,"sha |  |
| 12:43:53 | hostile yaml: billion laughs | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 200 0.0s; preview 422 0.0s; check 422 0.0s; propose 422 0.0s; autodesign 200 0.0s; a566c32e3d82 200 0.0s; studio/preview 422 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:43:53 | hostile yaml: deep flow 20000 | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 200 0.0s; preview 422 0.1s; check 422 0.1s; propose 422 0.1s; autodesign 200 0.0s; 950e9982546f 200 0.0s; studio/preview 422 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:43:53 | hostile yaml: deep block 4000 | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 200 0.1s; preview 422 0.0s; check 422 0.0s; propose 422 0.0s; autodesign 200 0.1s; 6757d74acda8 200 0.0s; studio/preview 422 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:43:53 | hostile yaml: !!java ProcessBuilder | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 200 0.0s; preview 422 0.0s; check 422 0.0s; propose 422 0.0s; autodesign 200 0.0s; a5a84654d7b5 200 0.0s; studio/preview 422 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:43:53 | hostile yaml: !!java URLClassLoader | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 200 0.0s; preview 422 0.0s; check 422 0.0s; propose 422 0.0s; autodesign 200 0.0s; cb5249a1e3f2 200 0.0s; studio/preview 422 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:44:27 | hostile yaml: 20MB scalar | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 413 0.0s; preview 400 0.0s; check 400 0.0s; propose 422 0.0s; autodesign 200 0.0s; 9e2ea94ae3a1 200 0.0s; studio/preview 400 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:44:27 | hostile yaml: 5000 aliases of a string | `PATCH/preview/check/propose/autodesign/studio-preview/save` | 4xx/422, no 500, no hang, no exec | PATCH 200 0.0s; preview 422 0.1s; check 422 0.1s; propose 422 0.1s; autodesign 200 0.0s; fff815e6e295 200 0.0s; studio/preview 422 ; POST /sutras 422 / alive 200 0.0s |  |
| 12:44:27 | java tag canary | `/tmp/pwned_javatag2` | absent | False | PASS |
| 12:44:51 | limits 51 samples | `POST /api/v1/builder/designs/16e24a0f59e9/samples (2033 B)` | 400/413 DRS-5003/5005 | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a design holds 50 samples, the most allowed (dri |  |
| 12:44:51 | limits 50 samples | `POST /api/v1/builder/designs/16e24a0f59e9/samples (1993 B)` | 200 | 200 0.0s {"id":"16e24a0f59e9","name":"lim","scratch":false,"kind":"k","status":"draft","rev":0,"shared":false,"created":1791045891084,"upda |  |
| 12:44:51 | limits 51st via second call | `POST /api/v1/builder/designs/16e24a0f59e9/samples (54 B)` | refused DRS-5005 | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a design holds 50 samples, the most allowed (dri |  |
| 12:44:51 | limits 6MB document | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (6291509 B)` | 413 DRS-5003 (max-file-mb 5) | 413 0.1s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 'big' is over the limit of 5 MB per document (dr |  |
| 12:44:51 | limits depth 64 | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (430 B)` | 200 | 200 0.0s {"id":"ddbd367cb7ff","name":"lim2","scratch":false,"kind":"k","status":"draft","rev":0,"shared":false,"created":1791045891129,"upd |  |
| 12:44:51 | limits depth 70 sample | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (461 B)` | refused depth | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a document is nested deeper than 64 levels (dris |  |
| 12:44:51 | limits depth 100000 body | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (200038 B)` | 400/413 no 500/stack overflow | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a document is nested deeper than 64 levels (dris |  |
| 12:44:52 | limits 26MB body | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (27263022 B)` | 413 | 0 1.0s EXC URLError(BrokenPipeError(32, 'Broken pipe')) |  |
| 12:44:52 | limits body is array | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (7 B)` | 400 | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 the body must be a JSON object","instance":"/api/v1/bu |  |
| 12:44:52 | limits body is text/plain JSON | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (45 B)` | 415 (consumes json) | 415 0.0s {"timestamp":"2026-10-03T16:44:52.250+00:00","status":415,"error":"Unsupported Media Type","path":"/api/v1/builder/designs/ddbd367 |  |
| 12:44:52 | limits body form-encoded | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (9 B)` | 415 | 415 0.0s {"timestamp":"2026-10-03T16:44:52.255+00:00","status":415,"error":"Unsupported Media Type","path":"/api/v1/builder/designs/ddbd367 |  |
| 12:44:52 | limits NaN/duplicate keys | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (53 B)` | 400 or accepted harmlessly | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 the body is not valid JSON: Non-standard token 'NaN':  |  |
| 12:44:52 | limits sample name 10KB | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (10049 B)` | refused or fine | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 a sample's name is 1-200 characters","instance":"/api/ |  |
| 12:44:52 | limits sample name with ../ and nul | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (74 B)` | 200 (names hashed on disk) | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 a sample's name is 1-200 characters","instance":"/api/ |  |
| 12:44:52 | limits refs count 10^9 | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (48 B)` | clamped | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a design holds 50 samples, the most allowed (dri |  |
| 12:44:52 | limits refs kind with path | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (40 B)` | 400 | 400 0.0s {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'refs.kind' is required: the kind of the stored entiti |  |
| 12:44:52 | limits schema count 10^9 | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (92 B)` | clamped to 50 | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a design holds 50 samples, the most allowed (dri |  |
| 12:44:52 | limits schema recursive $ref | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (144 B)` | bounded, no stack overflow | 200 0.0s {"id":"ddbd367cb7ff","name":"lim2","scratch":false,"kind":"k","status":"draft","rev":0,"shared":false,"created":1791045891129,"upd |  |
| 12:44:59 | limits schema huge minItems | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (94 B)` | bounded (no OOM) | 500 7.2s {"timestamp":"2026-10-03T16:44:59.517+00:00","status":500,"error":"Internal Server Error","path":"/api/v1/builder/designs/ddbd367c |  |
| 12:44:59 | limits schema pattern ReDoS-ish | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (83 B)` | bounded | 500 0.1s {"timestamp":"2026-10-03T16:44:59.628+00:00","status":500,"error":"Internal Server Error","path":"/api/v1/builder/designs/ddbd367c |  |
| 12:45:01 | limits schema huge string len | `POST /api/v1/builder/designs/ddbd367cb7ff/samples (127 B)` | bounded (no OOM) | 500 1.7s {"timestamp":"2026-10-03T16:45:01.283+00:00","status":500,"error":"Internal Server Error","path":"/api/v1/builder/designs/ddbd367c |  |
| 12:45:01 | limits builder/shape 51 docs | `POST /api/v1/builder/shape (2033 B)` | DRS-5003 | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 more than 50 samples (drishti.builder.max-sample |  |
| 12:45:01 | limits builder/shape depth 70 | `POST /api/v1/builder/shape (459 B)` | DRS-5003 | 413 0.0s {"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 a document is nested deeper than 64 levels (dris |  |
| 12:45:01 | limits designs per user | `55 x POST designs as qa-masked` | stops at 50 with DRS-5005 | 201 x49; first refusal: (413, '{"type":"about:blank","title":"payload too large","status":413,"detail":"DRS-5005 you keep 50 design') |  |
| 12:45:53 | source/preview/qa-narrow-author | `GET /api/v1/builder/designs/<D>/preview` | kind trade: no access; counterparty: allowed | 200 noaccess=True  |  |
| 12:45:53 | source/preview | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=['Meridian Reinsurance'] | PASS (Meridian is the counterparty, a kind this role may open) |
| 12:45:53 | source/check/qa-narrow-author | `POST /api/v1/builder/designs/<D>/check` | kind trade: no access; counterparty: allowed | 200 noaccess=True  |  |
| 12:45:53 | source/check | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/ops-preview/qa-narrow-author | `POST /api/v1/builder/designs/<D>/ops` | kind trade: no access; counterparty: allowed | 200 noaccess=True  |  |
| 12:45:53 | source/ops-preview | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=['Meridian Reinsurance'] | PASS (Meridian is the counterparty, a kind this role may open) |
| 12:45:53 | source/studio-preview/qa-narrow-author | `POST /api/v1/studio/preview` | kind trade: no access; counterparty: allowed | 200 noaccess=True  |  |
| 12:45:53 | source/studio-preview | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=['Meridian Reinsurance'] | PASS (Meridian is the counterparty, a kind this role may open) |
| 12:45:53 | source/builder-check/qa-narrow-author | `POST /api/v1/builder/check` | kind trade: no access; counterparty: allowed | 200 noaccess=True  |  |
| 12:45:53 | source/builder-check | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/builder-edit/qa-narrow-author | `POST /api/v1/builder/edit` | kind trade: no access; counterparty: allowed | 200 noaccess=False  |  |
| 12:45:53 | source/builder-edit | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/autodesign | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/builder-design | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/suggest/qa-narrow-author | `suggest` |  | 400 {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'pat |  |
| 12:45:53 | source/suggest | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/export | `as qa-narrow-author` | kind trade: no access; counterparty: allowed | leak=[] | PASS |
| 12:45:53 | source/propose/qa-narrow-author | `propose` |  | 202 {"proposal":{"id":"P-000008","name":"probe-auto","version":1,"status":"pending"},"status": |  |
| 12:45:53 | source/preview/qa-mauth | `GET /api/v1/builder/designs/<D>/preview` | trader masked | 200 noaccess=False  |  |
| 12:45:53 | source/preview | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/check/qa-mauth | `POST /api/v1/builder/designs/<D>/check` | trader masked | 200 noaccess=False  |  |
| 12:45:53 | source/check | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/ops-preview/qa-mauth | `POST /api/v1/builder/designs/<D>/ops` | trader masked | 200 noaccess=False  |  |
| 12:45:53 | source/ops-preview | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/studio-preview/qa-mauth | `POST /api/v1/studio/preview` | trader masked | 200 noaccess=False  |  |
| 12:45:53 | source/studio-preview | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/builder-check/qa-mauth | `POST /api/v1/builder/check` | trader masked | 200 noaccess=False  |  |
| 12:45:53 | source/builder-check | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/builder-edit/qa-mauth | `POST /api/v1/builder/edit` | trader masked | 200 noaccess=False  |  |
| 12:45:53 | source/builder-edit | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/autodesign | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/builder-design | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/suggest/qa-mauth | `suggest` |  | 400 {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 'pat |  |
| 12:45:53 | source/suggest | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/export | `as qa-mauth` | trader masked | leak=[] | PASS |
| 12:45:53 | source/propose/qa-mauth | `propose` |  | 202 {"proposal":{"id":"P-000009","name":"probe-auto","version":1,"status":"pending"},"status": |  |
| 12:46:11 | demoted/read design | `GET /api/v1/builder/designs/<D>` | no trade data; 403/noAccess/skipped | 200 leak=[]   | CHECK |
| 12:46:11 | demoted/preview ref trade | `GET /api/v1/builder/designs/<D>/preview?sample=trade%20MX-20000001` | no trade data; 403/noAccess/skipped | 403 leak=[] noaccess {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:46:11 | demoted/preview default | `GET /api/v1/builder/designs/<D>/preview` | no trade data; 403/noAccess/skipped | 200 leak=[]   | CHECK |
| 12:46:11 | demoted/preview ref counterparty | `GET /api/v1/builder/designs/<D>/preview?sample=counterparty%20CP-MERIDIAN-RE` | no trade data; 403/noAccess/skipped | 200 leak=[] noaccess  | PASS |
| 12:46:11 | demoted/check | `POST /api/v1/builder/designs/<D>/check` | no trade data; 403/noAccess/skipped | 200 leak=[] noaccess  | PASS |
| 12:46:11 | demoted/shape | `POST /api/v1/builder/designs/<D>/shape` | no trade data; 403/noAccess/skipped | 200 leak=[] noaccess  | PASS |
| 12:46:11 | demoted/autodesign | `POST /api/v1/builder/designs/<D>/autodesign` | no trade data; 403/noAccess/skipped | 200 leak=[] noaccess  | PASS |
| 12:46:11 | demoted/versions | `GET /api/v1/builder/designs/<D>/versions` | no trade data; 403/noAccess/skipped | 200 leak=[]   | CHECK |
| 12:46:11 | demoted/sample document of ref | `GET /api/v1/builder/designs/<D>/samples/document?name=trade%20MX-20000001` | no trade data; 403/noAccess/skipped | 400 leak=[]  {"type":"about:blank","title":"bad request","status":400,"de | PASS |
| 12:46:11 | demoted/propose | `POST /api/v1/builder/designs/<D>/propose` | no trade data; 403/noAccess/skipped | 202 leak=[]   | PASS |
| 12:46:11 | demoted/add ref trade | `POST /api/v1/builder/designs/<D>/samples` | no trade data; 403/noAccess/skipped | 403 leak=[]  {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:46:11 | demoted/add ref trade by count | `POST /api/v1/builder/designs/<D>/samples` | no trade data; 403/noAccess/skipped | 403 leak=[]  {"type":"about:blank","title":"forbidden","status":403,"deta | PASS |
| 12:46:11 | demoted/export | `GET export` | no trade data | files=['victim/pack.yaml', 'victim/sutras/studio/trade-auto.v1.sutra.yaml', 'victim/tests/trade-auto/brought.json', 'victim/tests/trade-auto/expect.yaml', 'victim/samples/trade/brought.json', 'victim/README.md'] leak=[] | PASS |
| 12:55 | note | console run 1 | | The first console runs were against a console started WITHOUT DRISHTI_AUTH_ENABLED=true (auth.enabled defaults false: everyone is the desk user "ash" with every role); those rows were discarded and rerun below with sign-in on. | |
| 12:53:49 | console sign-in | `POST /login qa-author` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console sign-in | `POST /login qa-author2` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console sign-in | `POST /login qa-narrow` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console sign-in | `POST /login qa-approver` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console sign-in | `POST /login qa-admin` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console sign-in | `POST /login qa-viewer` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console sign-in | `POST /login qa-masked` | 303 + cookie | 303 cookie=yes | PASS |
| 12:53:49 | console create design | `POST /build/designs same-origin` | 201 | 201 {"id":"a0e273cff186","name":"csrf-target","scratch":false,"k | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `PATCH /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/duplicate` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `DELETE /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/propose` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `DELETE /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:49 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/bind` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/save-file` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/undo` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/ops` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/autodesign` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/files` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs/<D>/samples` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/import` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: https://evil.example] | `POST /build/designs` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `PATCH /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/duplicate` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `DELETE /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/propose` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `DELETE /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/bind` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/save-file` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/undo` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/ops` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/autodesign` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/files` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs/<D>/samples` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/import` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: null] | `POST /build/designs` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `PATCH /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/duplicate` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `DELETE /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/propose` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `DELETE /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/bind` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/save-file` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/undo` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/ops` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/autodesign` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/files` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs/<D>/samples` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/import` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Referer evil, no Origin] | `POST /build/designs` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `PATCH /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/duplicate` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `DELETE /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/propose` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `DELETE /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/bind` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/save-file` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/undo` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/ops` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/autodesign` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/files` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs/<D>/samples` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/import` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin with same host but other port] | `POST /build/designs` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `PATCH /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/duplicate` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `DELETE /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/propose` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `DELETE /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/bind` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/save-file` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/undo` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/ops` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/autodesign` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/files` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs/<D>/samples` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/import` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin: http://localhost:17961 (Host 127.0.0.1)] | `POST /build/designs` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `PATCH /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/duplicate` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `DELETE /build/designs/<D>` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/propose` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `DELETE /build/designs/<D>/share` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/bind` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/save-file` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/undo` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/ops` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/autodesign` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/files` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs/<D>/samples` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/import` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | csrf [Origin prefix trick] | `POST /build/designs` | 403 DRS-5002 | 403 {"code":"DRS-5002","detail":"refused: this request | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [text/plain]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [application/x-www-form-urlencoded]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [multipart/form-data; boundary=x]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [None]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [application/json; charset=utf-8]` | 415 unless JSON | 201 {"id":"578ae8928d31","name":"asplain","scratch":false,"kind" | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [application/vnd.api+json]` | 415 unless JSON | 201 {"id":"371450646d41","name":"asplain","scratch":false,"kind" | PASS |
| 12:53:50 | json-as-text | `POST /build/designs [text/json]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [text/plain]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [application/x-www-form-urlencoded]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [multipart/form-data; boundary=x]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [None]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [application/json; charset=utf-8]` | 415 unless JSON | 200 {"id":"a0e273cff186","name":"asplain","scratch":false,"kind" | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [application/vnd.api+json]` | 415 unless JSON | 200 {"id":"a0e273cff186","name":"asplain","scratch":false,"kind" | PASS |
| 12:53:50 | json-as-text | `PATCH /build/designs/<D> [text/json]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [text/plain]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [application/x-www-form-urlencoded]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [multipart/form-data; boundary=x]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [None]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [application/json; charset=utf-8]` | 415 unless JSON | 422 {"code":"DRS-2002","detail":"4 problem(s): [studio.sutra.yam | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [application/vnd.api+json]` | 415 unless JSON | 422 {"code":"DRS-2002","detail":"4 problem(s): [studio.sutra.yam | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/propose [text/json]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [text/plain]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [application/x-www-form-urlencoded]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [multipart/form-data; boundary=x]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [None]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [application/json; charset=utf-8]` | 415 unless JSON | 200 {"id":"a0e273cff186","name":"asplain","scratch":false,"kind" | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [application/vnd.api+json]` | 415 unless JSON | 200 {"id":"a0e273cff186","name":"asplain","scratch":false,"kind" | PASS |
| 12:53:50 | json-as-text | `POST /build/designs/<D>/bind [text/json]` | 415 unless JSON | 415 {"code":"DRS-5001","detail":"send JSON with Content-Type: ap | PASS |
| 12:53:50 | csrf reviews decide | `POST /build/reviews/P-000001/approve {'Origin': 'https://evil.example'}` | 403 | 403 | PASS |
| 12:53:50 | csrf reviews decide | `POST /build/reviews/P-000001/approve {'Origin': 'null'}` | 403 | 403 | PASS |
| 12:53:50 | csrf logout | `POST /logout evil origin` | 403 | 403 | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/d/<D>` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/shape` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/shape/download` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/preview` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/sample?name=brought` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/versions` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/versions/0` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/diff` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/export` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `GET /build/designs/<D>/sync` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `POST /build/designs/<D>/check` | 404/403 | 404 leak=False | PASS |
| 12:53:53 | console foreign design qa-author2 | `POST /build/designs/<D>/autodesign` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-author2 | `POST /build/designs/<D>/ops` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-author2 | `POST /build/designs/<D>/preview-file` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-author2 | `POST /build/designs/<D>/duplicate` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-author2 | `DELETE /build/designs/<D>` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-author2 | `PATCH /build/designs/<D>` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-author2 | `POST /build/designs/<D>/suggest` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/d/<D>` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/shape` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/shape/download` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/preview` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/sample?name=brought` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/versions` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/versions/0` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/diff` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/export` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `GET /build/designs/<D>/sync` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `POST /build/designs/<D>/check` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `POST /build/designs/<D>/autodesign` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `POST /build/designs/<D>/ops` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `POST /build/designs/<D>/preview-file` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `POST /build/designs/<D>/duplicate` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `DELETE /build/designs/<D>` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `PATCH /build/designs/<D>` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-narrow | `POST /build/designs/<D>/suggest` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/d/<D>` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/shape` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/shape/download` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/preview` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/sample?name=brought` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/versions` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/versions/0` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/diff` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/export` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `GET /build/designs/<D>/sync` | 404/403, no leak | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `POST /build/designs/<D>/check` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `POST /build/designs/<D>/autodesign` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `POST /build/designs/<D>/ops` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `POST /build/designs/<D>/preview-file` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `POST /build/designs/<D>/duplicate` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `DELETE /build/designs/<D>` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `PATCH /build/designs/<D>` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console foreign design qa-admin | `POST /build/designs/<D>/suggest` | 404/403 | 404 leak=False | PASS |
| 12:53:54 | console share page qa-narrow | `GET /build/d/<D>?share=..` | 200 read-only; no sample contents/notes | 200 SECRET=False notes=False sampleNames=True |  |
| 12:53:54 | console share preview stored entity qa-narrow | `POST /build/shared/<D>/preview {kind:trade,id:MX-20000001}` | narrow: no access; viewer: data | 403 {"code":"DRS-5002","detail":"DRS-5002 qa-narrow may not open trade entities"} |  |
| 12:53:54 | console share preview own doc qa-narrow | `POST preview {document}` | 200 | 200 |  |
| 12:53:54 | console share bad token qa-narrow | `POST preview bad token` | 404 | 404 | PASS |
| 12:53:54 | console share preview odd kind qa-narrow | `kind=../../x` | 4xx no trace | 403 {"code":"DRS-5002","detail":"DRS-5002 qa-narrow may not open ../../x entities"} |  |
| 12:53:54 | console share page qa-viewer | `GET /build/d/<D>?share=..` | 200 read-only; no sample contents/notes | 200 SECRET=False notes=False sampleNames=True |  |
| 12:53:54 | console share preview stored entity qa-viewer | `POST /build/shared/<D>/preview {kind:trade,id:MX-20000001}` | narrow: no access; viewer: data | 200 {"previewHtml":"\n\n<div class=\"studio-view\">\n  <div class=\"vtitle\"><span class=\"pill\">Trade · IRS_FIXFLOAT</span |  |
| 12:53:54 | console share preview own doc qa-viewer | `POST preview {document}` | 200 | 200 |  |
| 12:53:54 | console share bad token qa-viewer | `POST preview bad token` | 404 | 404 | PASS |
| 12:53:54 | console share preview odd kind qa-viewer | `kind=../../x` | 4xx no trace | 404 {"code":"DRS-1001","detail":"DRS-1001 no source holds ../../x/a/b"} |  |
| 12:53:54 | console anon | `GET /build/d/dfc0896a157a?share=cWEtYXV0aG9y.s2LgQ-HP0` | 302 to sign-in / 401 | 303  | PASS |
| 12:53:54 | console anon | `GET /build` | 302 to sign-in / 401 | 303  | PASS |
| 12:53:54 | console anon | `GET /build/reviews` | 302 to sign-in / 401 | 303  | PASS |
| 12:53:54 | console anon | `GET /build/designs/dfc0896a157a` | 302 to sign-in / 401 | 303  | PASS |
| 12:54:09 | xss page /build/d/<D> | `GET as author (text/html; charset=u)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build | `GET as author (text/html; charset=u)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/designs/<D> | `GET as author (application/json)` | payload escaped in HTML | 200 rawPayloadsInBody=4 | PASS |
| 12:54:09 | xss page /build/designs/<D>/versions | `GET as author (application/json)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/designs/<D>/diff | `GET as author (application/json)` | payload escaped in HTML | 400 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/designs/<D>/preview | `GET as author (application/json)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/designs/<D>/shape | `GET as author (application/json)` | payload escaped in HTML | 200 rawPayloadsInBody=3 | PASS |
| 12:54:09 | xss page /build/reviews | `GET as approver (text/html; charset=u)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/reviews | `GET as approver (text/html; charset=u)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/reviews/P-000011 | `GET as approver (text/html; charset=u)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss page /build/d/<D> | `GET as viewer (text/html; charset=u)` | payload escaped in HTML | 200 rawPayloadsInBody=0 | PASS |
| 12:54:09 | xss review comment | `reviews page after reject comment` | escaped | 200 raw=[] | PASS |
| 12:54:09 | xss inline init JSON | `GET workbench` | no raw </script> from data | raw_close_script_from_data=False | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/genomics/unload` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `PUT /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `GET /api/v1/admin/packs` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/../packs/genomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/genomics/load/` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/%67enomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"'g' is | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/genomics;a=b/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/GENOMICS/load` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/registry/install` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `GET /api/v1/admin/registry` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/..%2f..%2fetc/load` | refused (401/403/404/405) | 400 <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/x/load?name=genomics` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `DELETE /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `PATCH /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/users` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `PUT /api/v1/admin/role-definitions/evil` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route None | `POST /api/v1/builder/designs/import` | refused (401/403/404/405) | 401 {"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DR | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/genomics/unload` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `PUT /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `GET /api/v1/admin/packs` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/../packs/genomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/genomics/load/` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.555+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/%67enomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"'g' is | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/genomics;a=b/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/GENOMICS/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/registry/install` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.565+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-viewer | `GET /api/v1/admin/registry` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/..%2f..%2fetc/load` | refused (401/403/404/405) | 400 <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/x/load?name=genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `DELETE /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.575+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-viewer | `PATCH /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.577+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/users` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `PUT /api/v1/admin/role-definitions/evil` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-viewer | `POST /api/v1/builder/designs/import` | refused (401/403/404/405) | 415 {"timestamp":"2026-10-03T16:54:26.594+00:00","status":415,"error":"Uns | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/genomics/unload` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `PUT /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `GET /api/v1/admin/packs` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/../packs/genomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/genomics/load/` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.603+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/%67enomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"'g' is | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/genomics;a=b/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/GENOMICS/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/registry/install` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.609+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-author | `GET /api/v1/admin/registry` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/..%2f..%2fetc/load` | refused (401/403/404/405) | 400 <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/x/load?name=genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `DELETE /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.614+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-author | `PATCH /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.615+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/users` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `PUT /api/v1/admin/role-definitions/evil` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-author | `POST /api/v1/builder/designs/import` | refused (401/403/404/405) | 415 {"timestamp":"2026-10-03T16:54:26.619+00:00","status":415,"error":"Uns | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/genomics/unload` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `PUT /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `GET /api/v1/admin/packs` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/../packs/genomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/genomics/load/` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.625+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/%67enomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"'g' is | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/genomics;a=b/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/GENOMICS/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/registry/install` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.630+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-approver | `GET /api/v1/admin/registry` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/..%2f..%2fetc/load` | refused (401/403/404/405) | 400 <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/x/load?name=genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `DELETE /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.633+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-approver | `PATCH /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.634+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/users` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `PUT /api/v1/admin/role-definitions/evil` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-approver | `POST /api/v1/builder/designs/import` | refused (401/403/404/405) | 415 {"timestamp":"2026-10-03T16:54:26.638+00:00","status":415,"error":"Uns | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/genomics/unload` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `PUT /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `GET /api/v1/admin/packs` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/../packs/genomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/genomics/load/` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.644+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/%67enomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"'g' is | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/genomics;a=b/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/GENOMICS/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/registry/install` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.651+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-narrow | `GET /api/v1/admin/registry` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/..%2f..%2fetc/load` | refused (401/403/404/405) | 400 <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/x/load?name=genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `DELETE /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.658+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-narrow | `PATCH /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.659+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/users` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `PUT /api/v1/admin/role-definitions/evil` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-narrow | `POST /api/v1/builder/designs/import` | refused (401/403/404/405) | 415 {"timestamp":"2026-10-03T16:54:26.665+00:00","status":415,"error":"Uns | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/genomics/unload` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `PUT /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `GET /api/v1/admin/packs` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/../packs/genomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"dot se | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/genomics/load/` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.672+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/%67enomics/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"'g' is | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/genomics;a=b/load` | refused (401/403/404/405) | 400 {"title":"bad request","status":400,"code":"DRS-5001","detail":"path p | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/GENOMICS/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/registry/install` | refused (401/403/404/405) | 404 {"timestamp":"2026-10-03T16:54:26.677+00:00","status":404,"error":"Not | PASS |
| 12:54:26 | admin route qa-masked | `GET /api/v1/admin/registry` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/..%2f..%2fetc/load` | refused (401/403/404/405) | 400 <!doctype html><html lang="en"><head><title>HTTP Status 400 – Bad Requ | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/x/load?name=genomics` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `DELETE /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.681+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-masked | `PATCH /api/v1/admin/packs/genomics` | refused (401/403/404/405) | 405 {"timestamp":"2026-10-03T16:54:26.682+00:00","status":405,"error":"Met | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/users` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `PUT /api/v1/admin/role-definitions/evil` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/admin/packs/genomics/load` | refused (401/403/404/405) | 403 {"type":"about:blank","title":"forbidden","status":403,"detail":"DRS-5 | PASS |
| 12:54:26 | admin route qa-masked | `POST /api/v1/builder/designs/import` | refused (401/403/404/405) | 415 {"timestamp":"2026-10-03T16:54:26.687+00:00","status":415,"error":"Uns | PASS |
| 12:54:26 | header spoof | `GET admin/users {'X-Drishti-User': 'qa-root'}` | 403 | 403 | PASS |
| 12:54:26 | header spoof | `GET admin/users {'X-Drishti-Roles': 'admin'}` | 403 | 403 | PASS |
| 12:54:26 | header spoof | `GET admin/users {'X-Forwarded-User': 'qa-root'}` | 403 | 403 | PASS |
| 12:54:26 | header spoof | `GET admin/users {'X-Drishti-User': 'qa-root', 'X-Drishti-Role': 'admin'}` | 403 | 403 | PASS |
| 12:54:52 | fuzz | `POST /api/v1/builder/designs/<D>/propose body={"notes": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` | 4xx problem+json, no internals | 500 {"timestamp":"2026-10-03T16:54:52.164+00:00","status":500,"error":"Internal Server Error","path":"/a | FAIL |
| 12:54:53 | fuzz | `POST /api/v1/builder/designs/<D>/bind body={"notes": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` | 4xx problem+json, no internals | 500 {"timestamp":"2026-10-03T16:54:53.010+00:00","status":500,"error":"Internal Server Error","path":"/a | FAIL |
| 12:54:53 | fuzz version int overflow | `GET versions/99999999999` | 4xx | 400 {"type":"about:blank","title":"bad request","status":400,"detail":"the parameter 'n' has a value of  |  |
| 12:54:53 | fuzz summary | `496 requests` | no 5xx / internals | 10 flagged | FAIL |
| 12:55:34 | console upload chunked 60MB files (json) | `POST /build/designs/<D>/files chunked` | 413 early, bounded buffering | 400 {"code":"DRS-5001","detail":"the body is not JSON: Expecting value: line 1 column 1 (char  console RSS 70MB -> peak 124MB, after 178MB |  |
| 12:55:35 | console upload chunked 60MB import (zip) | `POST /build/import chunked` | 413 early, bounded buffering | 503 {"code":"DRS-5003","detail":"backend unreachable: "} console RSS 70MB -> peak 152MB, after 96MB |  |
| 12:55:35 | console upload chunked 60MB preview-file | `POST /build/designs/<D>/preview-file chunked` | 413 early, bounded buffering | 400 {"code":"DRS-5001","detail":"the body is not JSON: Expecting value: line 1 column 1 (char  console RSS 70MB -> peak 125MB, after 186MB |  |
| 12:55:57 | GET with side effects | `GET /studio?example=..&build=1 with cross-site Referer (x5)` | no design created by a GET | designs 2 -> 7 | FAIL (GET creates designs) |
| 12:56:07 | api token vs designs | `GET /api/v1/builder/designs` | GET reads; every write refused | 200 {"designs":[{"id":"dfc0896a157a","name":"victim","scratch":f | PASS |
| 12:56:07 | api token vs designs | `GET /api/v1/builder/designs/<D>` | GET reads; every write refused | 200 {"id":"dfc0896a157a","name":"victim","scratch":false,"kind": | PASS |
| 12:56:07 | api token vs designs | `GET /api/v1/builder/designs/<D>/export` | GET reads; every write refused | 200 PK  gC]               victim/pack.yamlmQ���0���pc | PASS |
| 12:56:07 | api token vs designs | `POST /api/v1/builder/designs` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `PATCH /api/v1/builder/designs/<D>` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `POST /api/v1/builder/designs/<D>/propose` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `POST /api/v1/builder/designs/<D>/share` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `DELETE /api/v1/builder/designs/<D>/share` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `POST /api/v1/builder/designs/<D>/check` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `POST /api/v1/builder/designs/<D>/autodesign` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 12:56:07 | api token vs designs | `GET /api/v1/builder/designs/<D>/sync` | GET reads; every write refused | 200 {"id":"dfc0896a157a","name":"victim","scratch":false,"kind": | PASS |
| 12:56:07 | api token vs designs | `POST /api/v1/sutras` | GET reads; every write refused | 403 {"title":"forbidden","status":403,"code":"DRS-5002","detail" | PASS |
| 13:05 | console upload | `POST /build/designs/<D>/files` 60 MB with Content-Length | 413 | connection reset by the console before any JSON problem | PASS (refused) |
| 13:05 | console upload | chunked 60 MB to `/build/designs/<D>/files`, `/build/designs/<D>/preview-file`, `/build/import` | refused early (413) | body fully buffered (console RSS 70 to 186 MB), answered 400 not-JSON after reading it all; zip import forwarded to the server and answered `503 DRS-5003 backend unreachable` (the server dropped the over-limit upload) | FINDING S2-09 |
| 13:06 | GET with side effects | `GET /studio?example=all-panels-showcase` x3, `?kind=trade&id=MX-20000001`, `?build=1`, each with a cross-site Referer | no state change | 302 each, designs of qa-author2 went 2 to 7 | FINDING S2-04 |
| 13:07 | disk accounting | PATCH notes 20 MB, Sutra 3 x 8 MB, notes 12 MB on one design | counted in quota or refused | accepted; `bytes` reports 0; 61 MB on disk for that one design | FINDING S2-03 |
| 13:08 | processes | scratch server, console stopped by PID | | stopped | ok |
| 12:55 | note | fuzz summary | | "10 flagged" = 2 real 500s (S2-08) plus 8 test-client artifacts (Python cannot send a non-ASCII request line; rows removed) | |
