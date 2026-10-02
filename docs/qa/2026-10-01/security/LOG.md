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
# Drishti security QA — activity log

Scratch instance: server :18981 (security on, HS256 secret 48 chars, identity db run/id.db, packs market-risk,counterparty-risk, redact = trader,counterpartyId,patientName,mtm,counterparty), console :17981 (auth on, secure_cookie false). Server java PID 1940679, console PID 1945782.

| Time | Check | Request / code location | Expected | Observed | Verdict |
|---|---|---|---|---|---|
| 2026-10-01 20:41:55 | setup | `PUT /api/v1/admin/role-definitions/qa-masked` | 200 | 200 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-admin roles=['admin']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-author roles=['author']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-author2 roles=['author']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-approver roles=['approver']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-trader roles=['trader']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-risk roles=['market-risk']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-viewer roles=['viewer']` | 201 | 201 |  |
| 2026-10-01 20:41:55 | setup | `POST /api/v1/admin/users qa-masked roles=['qa-masked']` | 201 | 201 |  |
| resume | Opus resumed; prior incarnation left secure server :18981 + console :17981 running | `GET /public/about`=200, `GET /api/v1/admin/status`=200 (9 users) | live | live | ok |
| redact | trade `$.mtm` masked in search for viewer | `GET /api/v1/search?q=trade` as qa-viewer | mtm=••• | mtm=••• | masking works (neg result) |
| redact | trade raw mtm/trader/counterparty masked for viewer | `GET /api/v1/entities/trade/BBG-60000001/raw` as qa-viewer | ••• | all=••• | masking works (neg) |
| LEAK | panel records endpoint returns `mtm` UNMASKED for viewer | `GET /api/v1/views/netting-set/NS-ALDERSHOT-FRA/panels/trades/records` as qa-viewer | mtm masked as in search | mtm=-708216 plaintext, no ••• | **SEC-01 records bypasses redact** (ViewController.java:69-74) |
| 2026-10-01 20:43:15 | setup | `POST /login (console) x8 users` | 303 + session cookie | 303, drishti_session HttpOnly; SameSite=lax; Max-Age=36000; no Secure (DRISHTI_SECURE_COOKIE=false) | PASS |
| 2026-10-01 20:43:15 | 4 readyz | `GET :17981/readyz` | UP with security on | {"status":"DOWN","server":"unreachable","detail":"DRS-5010 missing bearer token"} | FAIL (SEC: readiness wrong with security on) |
| 2026-10-01 20:43:15 | 1 path bypass | `GET /api/v1/sutras (no token)` | 401 DRS-5010 | 401 | PASS |
| 2026-10-01 20:43:15 | 1 path bypass | `GET /api/v1;x/sutras , /api/%761/sutras (no token)` | 401 | 200 with full Sutra list | FAIL |
| 2026-10-01 20:43:15 | 1 path bypass | `GET /api/v1;x/{about,packs,sources,sutras/problems,sutras/irs-fixfloat/1,sutras/irs-fixfloat/1/source,rachana/schema,business-date,health/live} no token` | 401 | 200 all (data returned) | FAIL |
| 2026-10-01 20:43:15 | 1 path bypass | `GET /api/v1;x/{views,raw,search,admin/users,me/settings,command/*,phrase,workspaces/shared}` | 401 | 401 no principal on request (fails closed via @RequestAttribute) | PASS (defence in depth) |
| 2026-10-01 20:43:15 | 1 path bypass | `/actuator;x/prometheus, /actuator/%70rometheus, /actuator/health/..;/prometheus, /api/docs;x` | 401/404 | 401 or 404 | PASS |
| 2026-10-01 20:43:15 | 1 path bypass | `/api//v1/sutras, /API/v1/sutras, /api/x/../v1/sutras` | not served | 404 | PASS |
| 2026-10-01 20:45:44 | setup | `PUT role qa-masked kinds +qa-doc,+desk-pnl; feeds qa-doc/D1.json, qa-secret/S1.json` | 200 | 200 |  |
| 2026-10-01 20:46:01 | 3 mask raw | `GET /api/v1/entities/trade/MX-20000001/raw as qa-masked` | mtm, counterparty, trader = ••• | all three •••; pnl1d, nettingSet clear | PASS |
| 2026-10-01 20:46:01 | 3 mask view | `GET /api/v1/views/trade/MX-20000001 as qa-masked` | masked fields not shown (per task); docs: redact applies to raw JSON only | strip MTM (USD) +1,875,863; title.with Meridian Reinsurance Ltd; terms panel Counterparty = Meridian Reinsurance Ltd | FAIL vs task / doc-ambiguous |
| 2026-10-01 20:46:01 | 3 mask search rows | `GET /search?q=TRD MX-20000001 as qa-masked` | $.mtm ••• | ••• | PASS |
| 2026-10-01 20:46:01 | 3 mask search cond | `TRD where mtm > 1m ; counterparty.name == ...; counterparty.id == ...` | 0 matches (cannot probe) | 0 matches (admin: 2152 / 533) | PASS |
| 2026-10-01 20:46:01 | 3 mask order | `TRD order by mtm desc` | order not by hidden value | id order (BBG-60000001..), admin gets MX-30000042 first | PASS |
| 2026-10-01 20:46:01 | 3 mask csv | `GET /search/csv?q=TRD MX-20000001` | MTM ••• | ••• | PASS |
| 2026-10-01 20:46:01 | 3 mask compare | `GET /search/compare?from=2026-09-28&q=TRD MX-20000001` | from/to ••• | from/to ••• | PASS |
| 2026-10-01 20:46:01 | 3 mask history | `GET /history/trade/MX-20000001/series?path=mtm ; counterparty.name` | ••• | mtm •••; counterparty.name null | PASS |
| 2026-10-01 20:46:01 | 3 mask diff | `GET /history/trade/MX-20000001/diff (and from=09-01)` | masked | no field changes in sample data (only businessDate) - not conclusive; code redacts both sides (HistoryController:170) | PASS (code) |
| 2026-10-01 20:46:01 | 3 mask impact | `GET /impact/counterparty/CP-MERIDIAN-RE ; /impact/netting-set/NS-MERIDIAN-RE-NY as qa-masked` | trade measure ($.mtm) masked; trades of a counterparty not revealed when counterparty is masked | trade items measure −2,085,900 / +2,819,294 and total −1,047,715,039 shown; 200 trades listed as depending on CP-MERIDIAN-RE (search on counterparty.id returns 0) | FAIL |
| 2026-10-01 20:46:01 | 3 mask columns | `GET /search/columns/trade?paths=mtm,counterparty.id,counterparty.name,book` | masked | mtm •••, counterparty.* null, masked list given | PASS |
| 2026-10-01 20:46:01 | 3 mask pivot | `POST /search/pivot/trade rows counterparty.name values mtm sum/max/count/distinct` | masked group under •••, never added | rowKeys [(blank)] (not •••), sum/max null, count 10000, distinct 1; masked listed | PASS (minor doc mismatch: (blank) instead of •••) |
| 2026-10-01 20:46:01 | 3 mask pivot filter | `pivot filters mtm min 1m ; counterparty.name values [Meridian...] ; q mtm>0` | no probing | count 0 each (admin 2152) | PASS |
| 2026-10-01 20:46:01 | 3 mask drill/values | `POST /search/pivot/trade/drill, /values field counterparty.name, mtm` | masked | drill empty for ••• cell; values (blank)/••• | PASS |
| 2026-10-01 20:46:01 | 3 mask records | `GET /views/netting-set/NS-MERIDIAN-RE-NY/panels/trades/records as qa-masked` | mtm masked (task) / as view (docs) | mtm values -68527810 ... clear | FAIL vs task (consistent with view) |
| 2026-10-01 20:46:01 | 3 mask view table | `GET /views/netting-set/NS-MERIDIAN-RE-NY trades table` | — | MTM column −68,527,810 clear | FAIL vs task |
| 2026-10-01 20:46:01 | 3 mask suggest | `GET /command/suggest?q=MX-2000000 ; q=Meridian` | subtitles not reveal masked counterparty; masked text not matched | subtitles "... · Meridian Reinsurance Ltd"; q=Meridian returns trade MX-20000001 (match on masked counterparty name) | FAIL |
| 2026-10-01 20:46:01 | 3 mask SSE | `GET /views/trade/MX-20000001/stream as qa-masked` | masked | view event: MTM +1,875,863 and Meridian Reinsurance Ltd | FAIL vs task (consistent with view) |
| 2026-10-01 20:48:51 | 4 session | `admin disables qa-trader; console cookie GET /api/view/trade/MX-20000001` | 401/redirect (user disabled) | 200 (session continues until cookie expiry, 10h) | FAIL |
| 2026-10-01 20:48:51 | 4 session | `admin PUT qa-risk roles=[viewer]; old console cookie GET /api/calc/columns/trade` | 403 (viewer lacks calc) | 200 data (old roles market-risk kept in cookie) | FAIL |
| 2026-10-01 20:48:51 | 4 session | `disabled qa-trader JWT POST /api/v1/me/tokens` | refused | 201 new API token minted (token then refused while disabled) | FAIL (minor) |
| 2026-10-01 20:48:51 | 4 session | `disabled user API token GET /views` | 401 | 401 "API token unknown, revoked or expired" while disabled; 200 after re-enable | PASS |
| 2026-10-01 20:48:51 | 4 session | `GET /logout then replay old drishti_session cookie` | session ended | Set-Cookie clears cookie but old value still 200 on /api/view | FAIL |
| 2026-10-01 20:48:51 | 4 session | `tampered cookie payload r=[admin] with old sig, GET /admin/users` | redirect to login | 303 to /login | PASS |
| 2026-10-01 20:48:51 | 4 session | `mustChangePassword user: POST /login then GET /t and /api/view` | forced to /account until changed | login redirects to /account?must=1 but /t 200 and /api/view 200 | FAIL |
| 2026-10-01 20:48:51 | 4 token | `altered roles / alg none / HS512 / wrong secret / expired 1h / expired 40s / no exp / no sub / garbage` | 401 with documented detail | 401 each, details as documented; exp-10s accepted (30s skew documented) | PASS |
| 2026-10-01 20:48:51 | 4 token | `JWT with roles [*] or sub of non-existent user (needs shared secret)` | — | 200 /admin/status: server trusts token roles, no user lookup (design, see SEC finding) | INFO |
| 2026-10-01 20:51:12 | note | `audit log review` | only my actions | 00:48:55 user-disabled/enabled qa-trader pair and 00:49:45-47 create qa-lock, 5 failed logins qa-lock, 5 failed logins locking qa-admin, delete qa-lock were NOT issued by this run: another client is exercising this scratch server concurrently. Their actions left qa-admin locked for password sign-in (JWT tests unaffected). | INFO |
| 2026-10-01 20:51:12 | 1 matrix | `matrix.py: 85 server endpoints x 9 callers (anon, viewer, trader, market-risk, qa-masked, author, approver, admin, API token)` | per docs | see matrix.txt / FINDINGS.md section C; anon 401 everywhere; admin-only endpoints 403 DRS-5002 for all non-admins and for API tokens | PASS |
| 2026-10-01 20:51:12 | 1 governance | `approver/admin self-approve own proposal (four-eyes on)` | 403 DRS-2007 | 403 DRS-2007 for both | PASS |
| 2026-10-01 20:51:12 | 1 governance | `author approve any proposal` | 403 | 403 DRS-5002 | PASS |
| 2026-10-01 20:51:12 | 1 governance | `withdraw someone else's proposal` | 403 | 403 DRS-5002 only <author> can withdraw | PASS |
| 2026-10-01 20:51:12 | 1 governance | `approve a proposal that collides with a pack Sutra` | 422 | 422; detail discloses absolute server path /home/ashutosh/IdeaProjects/drishti/packs/... | FAIL (info leak, low) |
| 2026-10-01 20:51:12 | 1 phrase | `GET /api/v1/phrase?q=... (param is text)` | 400 DRS-5001 missing parameter | 401 DRS-5010 no principal on request (ApiExceptionHandler maps every ServletRequestBindingException to 401) | FAIL (low) |
| 2026-10-01 20:51:12 | 2 ownership | `qa-author2 vs qa-author: PUT/DELETE note, DELETE token, GET/share workspace, GET layout/pivot/snippet/monitor` | refused / not found | 403 note edit & delete; 400 no active token of yours; 404 for workspace, layout, pivot, monitor; own empty lists | PASS |
| 2026-10-01 20:51:12 | 2 shared ws | `GET /workspaces/shared/qa-author/ws1 as non-recipient / as qa-masked` | 404 / pane on qa-secret hidden | 404 trader/risk/approver/admin; qa-masked sees ref null hidden true | PASS |
| 2026-10-01 20:51:12 | 1 console | `console_matrix.txt: 42 console routes x 8 callers` | per docs | anon 303/401; admin pages 403 for non-admin; calc 403 without calc; reviews 403 for non-authors | PASS |
| 2026-10-01 20:51:12 | 1 console | `POST /studio/reviews/P-000002/reject as viewer/trader/masked` | 403 | 500 Internal Server Error (error branch re-fetches proposal, which raises again: studio_routes.py:96-97) | FAIL (low) |
| 2026-10-01 20:51:12 | 5 escaping | `note body <script>, entity field <img onerror>, Sutra title html via /studio/summary` | escaped | escaped (&lt;...); notes.js uses textContent | PASS |
| 2026-10-01 20:51:12 | 5 CSP | `headers on /login, /help, /static/js/calc-worker.js` | as OPERATIONS 8.4 / PYTHON_CALC 11 | page CSP script-src self no inline; worker CSP default-src none; script-src self wasm-unsafe-eval; connect-src self; nosniff, SAMEORIGIN, same-origin referrer | PASS |
| 2026-10-01 20:51:12 | 5 CORS | `OPTIONS with Origin evil.example to server and console` | no ACAO | no Access-Control-* headers | PASS |
| 2026-10-01 20:51:12 | 5 input | `bad search (mtm >, ((((, unknown kind, empty)` | 400 DRS-4004 | 400 DRS-4004 problem+json | PASS |
| 2026-10-01 20:51:12 | 5 input | `invalid Sutra YAML (3 variants), bad preview YAML` | 422 DRS-2002 | 422 DRS-2002 with line/col | PASS |
| 2026-10-01 20:51:12 | 5 input | `unknown kind / id; 5000-char id; bad asOf; future asOf; bad diff from` | 404 DRS-1001 / 400 DRS-4003 | as expected | PASS |
| 2026-10-01 20:51:12 | 5 input | `GET /search/compare?q=TRD&from=xx` | 400 DRS-4003 | 500 Spring default JSON; DateTimeParseException stack in server log (SearchController.java:136 LocalDate.parse) | FAIL (low) |
| 2026-10-01 20:51:12 | 5 input | `20,000-char and 10,000-paren search query` | 400 problem+json | 400 Tomcat HTML error page (header too large); no stack to client | PASS (cosmetic) |
| 2026-10-01 20:51:12 | 5 input | `malformed JSON bodies POST /command, /search/pivot/trade` | 400 problem+json | 400 Spring default {timestamp,status,error,path} (not RFC 7807, no code) | FAIL (cosmetic) |
| 2026-10-01 20:51:12 | 5 input | `landing https://evil.example, //evil, /\evil via PATCH /me/settings` | 400 | 400 DRS-5001 | PASS |
| 2026-10-01 20:51:12 | 5 input | `oversize snippet name/code, note 3000 chars` | 400 DRS-5001 | 400 DRS-5001 | PASS |
| 2026-10-01 20:51:12 | 5 file path | `GET /views/qa-doc/..%2Fx ; /views/qa-doc/../x` | refused | 400 (Tomcat rejects encoded slash) ; 404 | PASS |
| 2026-10-01 20:51:12 | 5 logs | `grep token secret, test password, drk_ secrets, eyJ JWTs in server.log/console.log` | absent | absent | PASS |
| 2026-10-01 20:52:15 | 4 redirect | `POST /login next=/\example.invalid, /%5C..., //example.invalid, https://...` | stay on site | Location /%5Cexample.invalid (path, encoded) or /t | PASS |
| 2026-10-01 20:52:15 | 4 CSRF | `POST :17981/api/tokens with Origin https://evil.example, Content-Type text/plain, qa-author cookie` | refused (Origin / token check) | 201 token created (body parsed as JSON whatever the content type); only SameSite=Lax protects; probe token revoked afterwards | FAIL (defence in depth) |
| 2026-10-01 20:52:15 | 4 CSRF | `GET /logout` | POST + check | state change on GET (logout) - cross-site navigation can sign a user out | INFO |
| 2026-10-01 20:52:15 | 5 code jdbc | `plugins/drishti-plugin-jdbc EntityTable.java:42-57, 74-310; QueryMode.java:77-110; TableCatalog.java:249-253` | bound params, checked identifiers | values bound with ?; table/column config identifiers checked by IDENT; promoted column names quoted ".." without escaping but only config-defined names reach SQL (requested paths filtered by containsAll) | PASS (note) |
| 2026-10-01 20:52:15 | 5 code file | `plugins/drishti-plugin-file FileSourcePlugin.java:155-158` | id confined to <root>/<kind>/ | normalize()+startsWith(base) confines to the root, but an id like ../<otherkind>/<id> would resolve inside a sibling kind folder (allowed by the check); over HTTP an id with / is rejected by Tomcat (encoded slash 400) so not reproduced through the REST path | FAIL (code reading, hardening) |
| 2026-10-01 20:52:15 | 5 code templates | `grep |safe / Markup in console templates; innerHTML sinks in web/static/js` | user text escaped | |safe only on config text (PRODUCT_MEANING, competitive.yaml) and rendered help markdown (docs + pack guides); JS sinks use esc() or textContent | PASS |
| 2026-10-01 20:52:15 | 5 CSP worker | `calc-worker.js fetches with credentials: same-origin (calc-worker.js:64,82)` | worker holds no credential (PYTHON_CALC 11) | worker can make any same-origin request with the session cookie (CSP connect-src self); doc wording "holds no credential" is literally true but slightly misleading | INFO |
| 2026-10-01 20:52:15 | 6 config | `application.yaml: security.enabled false, seed-admin true with drishti-dev-admin123, demo true, metrics-token empty; console auth.enabled false` | production-safe or warned | all warned in OPERATIONS 6.1/6.2/6.3 and section 9 checklist; compose.yaml sets security on but not DRISHTI_SEED_ADMIN=false and publishes 18480 (warned in OPERATIONS 4.4 item 5, 4.5) | INFO |
| 2026-10-01 20:52:15 | 6 config | `compose.data.yaml / application-postgres.yaml default PG password drishti` | warned | documented in OPERATIONS 6.5/6.7 (dev data stores) | INFO |
| 2026-10-01 20:52:15 | 6 CORS | `server + console` | no permissive CORS | no CORS headers | PASS |
| 2026-10-01 20:54:50 | end | `processes` | stop by PID | NOT stopped: another client is still active on :18981 (drishti-dev-admin login 00:52:24Z) and files t_*.py, build.log appeared in this folder that this run did not write. Left running for the caller: server java 1940679 (shell 1940676), console python 1945782 (shell 1945778). FINDINGS.md could not be written (tool refused report files); findings returned in the final message; matrices in matrix_server.md / matrix_console.md. | INFO |

## Resumed run (Opus 5.5) — independent re-verification of key findings

| Time | Check | Request / code | Expected | Observed | Verdict |
|---|---|---|---|---|---|
| resume | Verified prior run's server+console still live, then it was shut down (graceful, 20:55:58); restarted a fresh server pid 2011809 on :18981, same config | start.sh | up | up in 1s | ok |
| auth | JWT battery: alg=none/empty+text sig, RS256 confusion (HMAC-signed), wrong secret, expired >skew, no-exp, tampered roles->admin, missing, 2-part | mint variants -> /api/v1/admin/status | all 401/403 | all rejected; valid=200; within-skew expired=200 | PASS (neg) |
| auth | clock skew 30s honored; far-future exp accepted; roles=['*'] token = full admin (needs secret) | mint | - | exp uncapped + '*' wildcard honored (needs secret) | SEC-13 (info) |
| authz | disable not enforced server-side: disabled qa-trader's pre-existing token still 200 on /auth/me and /search | POST /admin/users/qa-trader/enabled{false} then reuse token | 401/403 | 200 (not enforced) | confirms SEC-01 |
| BYPASS | TokenFilter raw-URI bypass CONFIRMED anon (no token): /api/%761/packs=200, /api/v1;x/packs=200 (baseline /api/v1/packs=401) | GET w/o Authorization | 401 | 200 pack data | confirms SEC-02 |
| BYPASS scope | anon via /api/%761/: /sources=200, /business-date=200, /sutras=200, /rachana/schema=200; /views,/search,/raw=401 "no principal" | - | - | metadata/config leak; entity data fails closed | confirms SEC-02 (medium) |
| redact | /views/netting-set/NS-ALDERSHOT-FRA/panels/trades/records as qa-viewer returns mtm=-708216 unmasked; search/raw mask mtm | GET records | masked like search | unmasked | confirms SEC-03 (records path) |
| four-eyes | approver self-approve=403 DRS-2007; ADMIN self-approve=403 DRS-2007 (admin NOT exempt); author approve=403 DRS-5002 | governance flow | blocked | blocked | PASS (neg) |
| policy | weak pw (short/no-digit/no-letter) 422 DRS-6003; lockout at 6th attempt 423 DRS-6005; correct-while-locked 423 | /auth/login | enforced | enforced | PASS (neg) |
| yaml | billion-laughs 422 0.01s; deep-nest-5000 422 0.07s; 5MB scalar 422 2.2s (via /studio/preview, reachable by viewer not just author) | POST /studio/preview | no DoS | bounded 4xx | PASS (neg); note preview only requires requireOpen |
| registry | code review: Ed25519 sig + SHA256 before unpack; name/file regex; Zip Slip + 10k-file + unpacked-MB caps | PackRegistryClient.java | safe | safe | PASS (neg); downgrade allowed for admin (info) |
| errors | /public/about no secrets; malformed JSON 400; broken EL 400 DRS-4004; path-traversal id 400; no stack traces/secrets in HTTP | various | clean | clean | PASS (neg) |
| docker | compose.yaml security on but no DRISHTI_SEED_ADMIN=false -> seed admin drishti-dev-admin/drishti-dev-admin123 logs in (200 admin) on security-on server; compose.data.yaml PG pw defaults 'drishti' | login | - | known-cred admin live | SEC-16 (confirmed) |
