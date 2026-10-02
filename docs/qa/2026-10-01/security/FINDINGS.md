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
# Security QA findings — Drishti 1.13.0 (2026-10-01)

Scratch instance: server :18981 with security on, console :17981 with sign-in. Roles exercised: anonymous, viewer,
trader, market-risk (as "risk"), qa-masked (custom: trade, counterparty, netting-set, qa-doc, desk-pnl; calc; no raw),
author, approver, admin, an author's API token. Field masks (global setting): trader, counterpartyId, patientName, mtm,
counterparty. Access-control matrices: matrix_server.md (101 endpoint/method rows, 35 controllers), matrix_console.md
(42 routes × anonymous + 7 sessions); raw output in matrix.txt, console_matrix.txt, input.txt; activity in LOG.md.

| Id | Severity | Finding | Where | Reproduced |
|---|---|---|---|---|
| SEC-01 | High | Disabling or demoting a user does not end their console session: the cookie carries roles for 10 h and the server trusts token roles without a user lookup. Disabled `qa-trader`'s old cookie still opened views (200) and created API tokens; `qa-risk` demoted to viewer still read Calc columns (200). Personal API tokens were refused at once (401). Fix: check enabled flag and current roles per request (cached) or a per-user session generation; refuse token creation for disabled users. | console/core/auth.py:101-123; TokenVerifier.java:76 | yes |
| SEC-02 | Medium | The token filter tests the raw URI, so `/api/v1;x/…` and `/api/%761/…` skip it. Unauthenticated 200s: about, packs, sources, Sutra list/definition/source/problems, Rachana schema, business date, live stats, alert suggestions. Identity-requiring endpoints still failed closed (401), no entity data leaked. Fix: decide on Spring's resolved path or an explicit allow-list; reject `;` and encoded characters under /api. | TokenFilter.java:49-51 | yes |
| SEC-03 | Medium | Field masks apply to raw JSON, search, CSV, compare, history, Calc columns and pivots, but not to views: header strip, title, tables, panel records, the live stream and the console's panel CSV export show masked values (e.g. raw MTM `•••`, view `MTM (USD) +1,875,863`, "Meridian Reinsurance Ltd"). CONFIGURATION says raw only; USER/API/Calc guides imply everywhere. Mask in the view pipeline or correct the docs. | view pipeline; docs | yes |
| SEC-04 | Medium | Impact (F8) never masks: for qa-masked, a search for trades of CP-MERIDIAN-RE returns 0 but Impact on it lists 200 trades with MTM and total −1,047,715,039. | ImpactService.java:161-230 | yes |
| SEC-05 | Medium | Sign-out only deletes the browser cookie; a copy of the old cookie still returned 200. | auth_routes.py:97-102 | yes |
| SEC-06 | Medium | "Must change password at first sign-in" not enforced: after the redirect to /account?must=1 the user can open /t and /api/view (200) with the initial password. | console auth | yes |
| SEC-07 | Low | Type-ahead subtitles show masked counterparty names; typing "Meridian" finds MX-20000001 through the masked field. | CommandController.java:132-136 | yes |
| SEC-08 | Low | Console state-changing routes have no CSRF token or Origin check and accept JSON as text/plain; a request with `Origin: evil.example` created an API token (201, revoked afterwards). Only SameSite=Lax protects (not sibling subdomains), undocumented; /logout is a GET. | console routes | yes |
| SEC-09 | Low | File connector path check confines ids to the feed root but allows `../<other kind>/<id>`; over HTTP ids with `/` are rejected earlier, so not reproduced — hardening item. | FileSourcePlugin.java:155-158 | code reading |
| SEC-10 | Low | Inconsistent errors: missing query parameter → 401 "no principal" instead of 400; `/search/compare?from=xx` → 500 with a stack trace in the server log; malformed JSON → Spring's default body, not an RFC 7807 problem; very long URL → Tomcat HTML 400; console review reject by a non-reviewer → 500 instead of 403. No stack traces or secrets in HTTP responses or logs. | ApiExceptionHandler.java:52-56; SearchController.java:136; studio_routes.py:96-97 | yes |
| SEC-11 | Low | Sutra problem details include absolute server paths (/home/…/packs/…). | Sutra problems | yes |
| SEC-12 | Low | The console's /readyz always reports DOWN with security on (calls the server without a token), so a load balancer set up per OPERATIONS §13.1 never routes. | home_routes.py:46-52 | yes |
| SEC-13 | Info | A token signed with the shared secret is accepted for any user name (even nonexistent) and any role (`*`, admin): by design and documented, but the secret is a master key. | TokenVerifier | yes |
| SEC-14 | Info | A pivot on a field whose parent is masked groups under `(blank)` rather than the documented `•••`; count/distinct count masked values. Nothing revealed. | pivot engine | yes |
| SEC-15 | Info | Personal API tokens are refused on read-only POST endpoints (pivot, command). | token filter | yes |
| SEC-16 | Info | Risky production defaults (all warned in docs): security off by default; dev admin with default password (compose.yaml does not switch it off); demo source on; compose publishes 18480; PostgreSQL password `drishti`; empty metrics token. | config, deploy | yes |
| SEC-17 | Info | The Calc worker's requests carry the user's session cookie, so Calc code can call any console route as the user; the Calc guide's "holds no credential" may overstate the sandbox. | calc-worker.js:64,82 | code reading |

## Checks that passed
Tokens (altered, alg none, HS512, wrong secret, expired beyond 30 s skew, no subject, malformed → 401 with documented
reason); cookies HttpOnly + SameSite=Lax (Secure off only for local HTTP), tampered cookie → sign-in; every /admin/**
read and write refuses non-admins and API tokens (403 DRS-5002); /auth/login and /auth/oidc refuse users; four-eyes
(no self-approval, DRS-2007; authors cannot approve; only the proposer withdraws); powers (`calc`, `layout`, pivots need
only the kind); ownership of notes, tokens, workspaces, layouts, pivots, snippets, monitors, alert rules; shared
workspaces hide panes of kinds the reader cannot open; API tokens read-only and cut off at once on disable; masks
where applied cannot be probed by conditions or ordering; pivot filters, drill and values masked; malformed search,
YAML, kinds, ids, dates and oversize inputs → clean 4xx with documented codes; no off-site landing or `next` redirect;
JDBC binds every value and checks identifiers; notes, entity fields and Sutra text escaped; page and Calc-worker CSP as
documented; no CORS headers; actuator endpoints need a token (path variants too); no secrets in logs.

## Not exercised
Monitor/alert live streams per role; single sign-on with a real provider; report runs (reports disabled); installs from
a real pack registry.

## Note on the run
Another client used the scratch instance during the run (audit log: user `qa-lock` created and deleted, `qa-admin`
locked by five failed sign-ins, `qa-trader` switched off and on, extra proposals, a `drishti-dev-admin` sign-in at
00:52:24Z) and files `t_*.py`, `build.log` appeared in the folder. No finding depends on that activity.
