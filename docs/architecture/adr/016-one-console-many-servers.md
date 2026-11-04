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
# ADR-016: One console, many servers; the user picks one and signs in to it

| Status | Date | Decider |
|---|---|---|
| Accepted (built in 1.12) | 2026-10-01 | Ashutosh Sinha |

## Context
Today one console talks to exactly one server: `backend.url`, one token secret shared with that server, and caches
(packs, settings, business dates) keyed by user name alone. Organisations want several Drishti servers side by side:
an open one most staff use, and exclusive ones (a restricted desk's data, a regulator-facing environment, a
pre-production copy) that only some people may enter. The console (the user experience) should stay open to all;
each server decides who gets in.

## Decision
1. **A catalogue of servers in the console's configuration** (`servers:`): for each, an id, a name and description,
   its URL, its own token secret (from the environment), its own OpenID Connect settings if any, and a `listed`
   flag (an unlisted server is reachable only by a direct link, `/connect/<id>`). The single `backend.url` becomes
   the one-entry form of the catalogue, so existing installations keep working unchanged.
2. **The user picks a server, then signs in to it.** After choosing, the console shows that server's sign-in
   (password or its single sign-on). Each server keeps its own users, roles, packs and audit (its own identity
   database), so it authenticates again and decides alone whether to admit the user. Nothing is shared between
   servers: a session on one gives nothing on another.
3. **The session is bound to one server.** The session cookie carries the server id beside the user; every call,
   every cache key (packs, settings, business dates) and every live channel is scoped by (server, user). Switching
   server ends the current session for that tab after asking, and starts sign-in on the other.
4. **Links name their server.** Shared links and bookmarks carry the server id (`/s/<server>/v/trade/MX-20000001`, or a
   `srv=` parameter), so a link opens on the right server, asking to sign in there if needed.
5. **The picker shows each server's state** from its public `/public/about` and `/readyz`-style health: name,
   version, packs, up or down, and whether this user already holds a session there.
6. **Exclusive servers stay exclusive** by their own rules: their users and roles, single sign-on group mapping,
   optional network restrictions (they may sit on a network only some consoles reach). The console never lists a
   server's data before sign-in.

## Consequences
- **Console**: a server registry; one pooled HTTP client per server; the sign-in, OIDC callback and session code
  take a server id; every `request.app.state` cache becomes per (server, user); `/api/channel` subscriptions are per
  server; the top bar shows the current server (name and a colour) and a *Switch server* menu.
- **Server**: unchanged in principle. It already authenticates on its own and trusts only tokens signed with its own
  secret. Small additions: a public, unauthenticated `/public/about` (outside `/api/v1`) (name, version, banner text, sign-in
  methods) for the picker.
- **Security**: one secret per server (a leaked secret opens one server, not all); cookies never carry a token for
  another server; the console refuses a server id not in its catalogue (no open redirect or server-side request
  forgery through it).
- **Operations**: each server is deployed, backed up and upgraded on its own; the console needs every server's URL
  and secret in its configuration.

## Alternatives considered
- **One sign-in for all servers (federated session).** Simpler for users, but it makes the console a trust broker
  between servers and defeats exclusive servers; single sign-on already gives most of the convenience, since the
  provider's session makes each server's sign-in one click.
- **A console per server.** Works today without code, but users must know several addresses and nothing shows them
  which servers exist.
- **One server with exclusive packs.** Admin → Packs and roles already restrict what people see on one server, but
  the data, configuration and operators stay shared; it does not isolate a restricted environment.

## As built (2026-10-01)
- `servers:` in the console's configuration, as above; without it `backend.url` is the one server, `default`.
- The chosen server is kept **per browser** (cookie `drishti_server`), not per tab: the console renders its pages on
  the server side and a cookie is shared by all tabs. Links name their server instead: `/connect/<id>?next=…` and
  `?srv=<id>` on any page, so a shared link opens on the right server.
- Each server's session lives in its own cookie (`drishti_session_<id>`, signed with the server id inside, so it
  cannot be moved to another server). Switching therefore does not end the other sessions: switching back needs no
  new sign-in. Signing out ends the current server's session only.
- The server's public `GET /public/about` (outside `/api/v1`, no token) gives name, version, notice and sign-in
  methods, and nothing about data, packs or users. The picker (`/servers`) reads it from every server concurrently.
- In the console, `app.state.backend` and `app.state.auth` are switches resolved per request from a context
  variable, and the per-user caches key by (server, user); routes did not change.
- Work that outlives a request acts for that request, never for the console's first server. The middleware's
  request scope (the server, the session's pin in a pool of servers, the lens, the share a view was opened
  through, the business date and "known at") is captured by `core/request_context.py`, and every task started for
  the person runs in it: each subscription of a live channel (views, alerts, BI live tables, live pivots, computed
  panels) and a pack job. A live channel also pins its server's backend client when it opens. A call relayed to
  another worker of the console carries the scope, and the worker that holds the channel or job acts in it; a
  relayed call naming no known server is refused. Everything a person owns in the console (a live channel, a pack
  job, a lake upload or build, AI call limits) is owned by (server, user): the same name on two servers is two
  people. Open channels, the relay and the console's other state belong to one app, never to the process.
- A console live channel re-checks its session every `auth.recheck_seconds` (the embed stream's guard, with the
  session as its check): a signed-out, disabled or deleted user's channel ends with `gone` `DRS-5010`; a valid one
  carries on with the identity re-minted (current roles, a fresh token). Signing out ends that session's channels
  on this console at once.

