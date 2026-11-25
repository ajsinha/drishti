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
# Architecture decision records

| ADR | Title |
|---|---|
| [001](#adr-001-backend-is-a-spring-boot-application) | Backend is a Spring Boot application |
| [002](#adr-002-layout-is-sutra-plus-inference) | Layout is Sutra plus inference |
| [003](#adr-003-sutra-is-yaml-with-a-closed-expression-language) | Sutra is YAML with a closed expression language |
| [004](#adr-004-server-returns-a-view-model) | Server returns a view model |
| [005](#adr-005-server-sent-events-for-live-updates) | Server sent events for live updates |
| [006](#adr-006-vendored-front-end-without-a-build-pipeline) | Vendored front end without a build pipeline |
| [007](#adr-007-build-gates-in-tests) | Build gates in tests |
| [008](#adr-008-rachana-is-the-grammar-a-sutra-is-a-layout) | Rachana is the grammar, a Sutra is a layout |
| [009](#adr-009-users-live-in-the-server) | Users live in the server |
| [010](#adr-010-industries-are-domain-packs) | Industries are domain packs |
| [012](#adr-012-every-read-is-for-a-business-date-history-lives-in-delta-lake) | Business dates and Delta Lake |
| [013](#adr-013-sutras-go-live-through-review) | Sutras go live through review |
| [014](#adr-014-single-sign-on-verified-by-the-server) | Single sign-on, verified by the server |
| [015](#adr-015-packs-inherit-and-the-more-specific-pack-wins) | Packs inherit, and the more specific pack wins |
| [016](#adr-016-one-console-many-servers-the-user-picks-one-and-signs-in-to-it) | One console, many servers |
| [017](#adr-017-sutras-are-yaml-through-and-through) | Sutras are YAML, through and through (supersedes 011) |
| [018](#adr-018-packs-come-from-a-signed-versioned-registry) | Packs come from a signed, versioned registry |
| [019](#adr-019-pricing-is-a-quantlib-service-behind-the-drishti-server) | Pricing is a QuantLib service behind the Drishti server (proposed) |
| [020](#adr-020-collaboration-records-live-in-the-identity-database-the-notice-is-a-link-never-data) | Collaboration records live in the identity database; the notice is a link, never data |
| [021](#adr-021-embedding-is-html-over-the-wire-for-views-and-the-viewers-own-code-for-reports) | Embedding is HTML over the wire for views, and the viewer's own code for reports (rendering part superseded by 022) |
| [022](#adr-022-one-renderer-in-the-browser-supersedes-adr-021s-rendering-decision) | One renderer, in the browser (supersedes 021's rendering decision) |

ADRs are amended, never rewritten. Each decision below keeps its number; `DECISIONS.md#adr-NNN-...` is the address.

## ADR-001: Backend is a Spring Boot application

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
The Drishti backend could be shipped as an embeddable engine plus a server (the Pravaha model) or only as a server.

### Decision
Drishti ships **only** as the Spring Boot application `drishti-server`. It is never embedded. Maven modules are internal building blocks and each contributes its beans through its own `@Configuration`. Domain classes stay constructor-injected, so they unit-test without a Spring context. `drishti-api` (the plugin SPI) stays Spring-free.

### Consequences
There is one deployable, and Spring configuration, metrics and lifecycle are available everywhere. We give up embeddability, which no user needs. ArchUnit enforces one-way module dependencies and a Spring-free SPI.

## ADR-002: Layout is Sutra plus inference

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Hand-built screens per product type do not scale, and new products appear faster than screens are written.

### Decision
A view layout is the merge of a declarative Sutra (optional) and inferred panels, cached by the pair (sutra@version, shape fingerprint).

### Consequences
Unknown data renders on day one. The inference cost is paid once per data shape, and warm requests only bind values.

## ADR-003: Sutra is YAML with a closed expression language

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Layouts need bindings, conditions and small computations without becoming programs.

### Decision
Sutra files are YAML. Bindings use Rachana-EL, a closed, side-effect-free expression language (paths, filters, ternary, arithmetic, a few functions) compiled to closures.

### Consequences
Layouts are reviewable, diffable and safe. Adding a function needs an ADR amendment.


**Amended by [ADR-017](DECISIONS.md):** the Markdown wrapper of ADR-011 is gone; a Sutra is again one YAML file (`<name>.v<N>.sutra.yaml`), now starting with `rachana: 1`.

## ADR-004: Server returns a view model

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
The server could render HTML or return data.

### Decision
The server returns a JSON ViewModel: the bound layout. The console renders it with one Jinja macro and one JS renderer per panel kind.

### Consequences
The same model feeds the console, API clients and golden tests, and live patches address ViewModel paths.

The ViewModel's structure, standard and use are defined in [VIEWMODEL.md](VIEWMODEL.md), with its JSON Schema at
`GET /api/v1/viewmodel/schema`.

## ADR-005: Server-sent events for live updates

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Live views need push from server to browser.

### Decision
Use Server-Sent Events with `Last-Event-ID` resume, not WebSockets.

### Consequences
The channel is one-way and proxy-friendly, and browsers reconnect automatically. The client never needs to push over it.

## ADR-006: Vendored front end without a build pipeline

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Desks can be air-gapped, and a JS build chain adds weight.

### Decision
The console is FastAPI + Jinja2 with vendored Bootstrap, Bootstrap Icons and ECharts. There is no CDN, no npm and no inline script (strict CSP). The file layout follows MAYA.

### Consequences
The console works offline. Upgrades mean copying files deliberately.

## ADR-007: Build gates in tests

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Header and file-size rules must not depend on developer discipline.

### Decision
`drishti-it` holds `LicenseHeaderTest`, `SourceFileSizeTest` (2000 lines, UX exempt) and `ArchitectureRulesTest`. `tools/license_headers.py --fix` inserts headers. The parent POM is `spring-boot-starter-parent`, which aligns versions, so there is no separate BOM module. Formatting follows `.editorconfig`; Spotless and Error Prone are deferred.

### Consequences
A plain `./mvnw verify` enforces the rules. The formatter gate may be added later if drift appears.

### Amendment (1.10.3): Error Prone and Spotless are on
- **Error Prone** runs in every compile (`maven-compiler-plugin`, `.mvn/jvm.config` opens the compiler on the JDK). Its
  default error-level checks fail the build; `NonAtomicVolatileUpdate`, `LockNotBeforeTry` and
  `JavaTimeDefaultTimeZone` are raised to errors too. Style-level findings stay warnings. An intentional exception
  carries `@SuppressWarnings("<Check>")` with the reason beside it.
- **Spotless** checks at `verify`, enforcing what `.editorconfig` promises: no unused imports, no trailing
  whitespace, a final newline, spaces not tabs. `./mvnw spotless:apply` fixes a failure. A full reformatter
  (google-java-format) is deliberately not used: it would rewrite the code base's wider-line style.

## ADR-008: Rachana is the grammar, a Sutra is a layout

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
The product concept names the declarative screen grammar **Rachana** (रचना, *composition*): "so no product ever gets its own coded screen". The reference mockups label each view's layout **Sutra** (`Layout: Sutra irs-vanilla v3 + inference`). Waves 4–7 were built under the name Sutra alone.

### Decision
- **Rachana** is the grammar: the language, its schema, its expression language (**Rachana-EL**) and the module `drishti-rachana` (packages `com.ash.drishti.rachana`), configured under `drishti.rachana.*`, documented in `RACHANA_REFERENCE.md`.
- A **Sutra** is one layout written in Rachana: a versioned file `sutras/<domain>/<name>.v<N>.sutra.md` (Markdown with one `sutra` block; ADR-011), modelled by `Sutra` and loaded by `SutraRegistry`.

### Consequences
Both names mean what the concept and the mockups say. The views keep the mockups' wording (`Sutra irs-vanilla v3 + inference`). Renaming touched only module, package, configuration and document names; no behaviour changed.

**Amended by [ADR-017](DECISIONS.md):** the file is `sutras/<domain>/<name>.v<N>.sutra.yaml`, one YAML document starting with `rachana: 1`; the Markdown form of ADR-011 is no longer read.

## ADR-009: Users live in the server

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Wave 10 signed people in against a users file read by the console. Full user management (create,
edit, disable, reset, audit, lockout) needs one shared, writable source of truth, even when there are
several consoles.

### Decision
Users live in the server, in the `drishti-identity` module. The store is behind the `UserStore`
interface; the default `FileUserStore` writes JSON atomically with owner-only permissions. Hashes are
PBKDF2-HMAC-SHA256.

The console verifies a sign-in through `POST /api/v1/auth/login` using a service token, and only
receives the profile. Admin operations go through `/api/v1/admin/**`, which requires the `admin` role.
Every event is audited.

On an empty store, a development admin (`drishti-dev-admin`) is seeded, and the console warns until its
password changes. Forced password changes are off unless configured.

### Consequences
The console's users file and hashing tool are retired. A database-backed `UserStore` can replace the
file without changing the API, and OIDC can later map external identities onto these users and roles.

## ADR-010: Industries are domain packs

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
The engine was always domain-neutral, but the finance vocabulary had spread into the core
configuration: mnemonics, reference patterns, badges, roles, semantic hints, formats, samples, starters,
tutorials and placeholders. "Any domain" was true of the engine and not of the product.

### Decision
Everything industry-specific lives in a **domain pack** (`modules/<module>/packs/<name>/`). A `pack.yaml` declares the
vocabulary, next to the pack's Sutras, hints, formats, samples, starters and guides.

The `drishti-packs` module turns the enabled packs into the lowest-precedence property source through
an `EnvironmentPostProcessor`, so no core module depends on packs and site configuration always
overrides them. Pack clashes are start-up errors. The console asks the server which packs are
enabled.

Finance and logistics ship as packs; the second one proves the core is neutral.

### Consequences
- A new industry is content and configuration, with no code.
- The core semantic hints and formats are neutral, so an unrecognised domain still renders sensibly
  but more plainly until its pack adds vocabulary.
- Deployments must ship the `config/packs/` folder (both images copy it).

## ADR-012: Every read is for a business date; history lives in Delta Lake

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Risk and trading users look at end-of-day data: yesterday's MTM, last Friday's exposure, the curve on a given
date. Views were "whatever the source has now". Sources hold history very differently: a lake keeps
partitions and versions, a database has a date column, a feed drops a folder per day.

### Decision
- **Live or a picked date.** The top bar carries a business date. **Live** (the default) is the current business
  date on the configured calendar (USNY: weekends and holidays roll back to the previous business day), and
  views stream. **A picked date is a static snapshot**, even when it is today. The console only stores the
  choice (a cookie) and sends it as `X-Drishti-As-Of`. It never reads the lake or any data files.
- **One SPI type.** `AsOf(businessDate, knownAt, live)` reaches every read: `fetch`, `reverse` and `search` have
  dated overloads that default to the undated methods, so existing plugins keep working. A source declares
  `SourceCapabilities.dated`. Dated sources stamp `Provenance.businessDate`; undated ones leave it empty, and the
  view says "No data held for <date>" instead of passing current data off as history.
- **Routing.** For a picked date, dated sources are tried first; for live, the configured order stands (so live
  ticks come from live sources and history comes from the lake).
- **Delta Lake through Delta Kernel** (Java, no Spark), the `delta` plugin: `<root>/<domain>/<kind>/`, one table
  per kind, `(id, doc)` rows partitioned by `business_date`. Snapshot tables take the newest partition on or
  before the date (within a lookback), effective tables the last change on or before it. `knownAt` uses Delta
  time travel ("as known at", before a restatement). The root is configurable (default each lake's own folder, `./data/delta/<lake>`); each domain
  or pack keeps its own folder.
- **Named connectors** (one file `config/connectors/<name>.yaml` with `plugin`, `settings` and `kinds`; formerly `drishti.sources.connectors.<name>`) run a plugin several times,
  such as one lake per domain.
- The file plugin reads `<root>/<yyyy-MM-dd>/<kind>/<id>.json`; the JDBC plugin binds `:asOf` in its queries.

### Consequences
History costs no extra code per domain. A pack writes its data as dated Delta tables, and every Sutra, link,
impact, suggestion and monitor follows the date. Live streaming is untouched. The Delta plugin brings Hadoop's
client libraries (shaded) into the server; Unity Catalog's client is excluded because its log4j binding
conflicts with Logback. Maya's `maya_delta` (Python: delta-rs plus a pure fallback) inspired the layout and the
time-travel semantics. Drishti's reader is Java, because the backend owns all data access.

## ADR-013: Sutras go live through review

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
A Sutra decides what every user sees for a family of entities: which figures lead, which are hidden, how values are
formatted. Studio could write a Sutra straight to disk, and hot reload made it live for everyone at once. In a bank a
screen change of that reach needs a second pair of eyes and a record of who changed what, when and why.

### Decision
- **A save is a proposal.** With governance on (the default), saving in Studio validates the Sutra and records a
  proposal: the text, the author's note, and the live text it was written against. Nothing changes for users.
- **Approvers decide.** A role with `approve: true` (or an admin) approves (the Sutra is written and hot-reloaded)
  or rejects with a reason. Authors may withdraw their own.
- **Four eyes.** Nobody approves their own proposal (`drishti.governance.four-eyes`, on by default; it applies when
  security is on, since without it everyone is the same anonymous user).
- **No stale approvals.** Approval is refused when the live Sutra changed after the proposal was made: the reviewer's
  diff would no longer be what gets published.
- **A record.** Proposals are kept as JSON files (`drishti.governance.dir`) and every step is audited
  (`sutra-proposed`, `sutra-approved`, `sutra-rejected`, `sutra-withdrawn`); each Sutra has a history.
- Sutras committed to a pack or site directory through version control are unaffected: that review happens there.

### Consequences
- A change to a screen takes two people. Single-person authoring setups switch four-eyes off, or governance off
  (`DRISHTI_SUTRA_REVIEW=false`), which restores direct saving.
- Decisions are serialised per store, so two approvers cannot both decide one proposal.

## ADR-014: Single sign-on, verified by the server

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Banks sign staff in through a corporate identity provider (Keycloak, Microsoft Entra ID, Okta, Ping) and manage
access through its groups. Drishti had its own users and passwords only. The console is the browser-facing part;
the server holds users, roles and the audit log, and trusts the console only to verify sign-ins.

### Decision
- **OpenID Connect, authorization code flow with PKCE.** The console redirects to the provider with `state`, `nonce`
  and a PKCE challenge kept in a short-lived signed cookie, and exchanges the code for an ID token (as a confidential
  client with `client_secret_basic` or `client_secret_post`, or a public client with PKCE alone).
- **The server verifies the ID token, not the console.** The console hands the token and the nonce to
  `POST /api/v1/auth/oidc` (callable by the console's service identity only). The server checks the signature
  against the provider's published keys (JWKS, discovered from the issuer, refreshed on an unknown key id at most
  once a minute), then issuer, audience and authorised party, expiry, not-before, issued-at and nonce. A console
  that is compromised still cannot sign anyone in without a token the provider signed.
- **JDK cryptography only.** RS256/384/512, PS256/384/512 and ES256/384/512 through `java.security`; `none` and
  shared-secret HMACs are never accepted, whatever the configuration says. No JOSE library is added.
- **Groups become roles** through `drishti.security.oidc.role-map`; users whose groups map to nothing are refused
  (or get `default-roles`). Users are created on first sign-in, with no usable password; the provider's groups
  replace their roles at each sign-in (`roles-from-provider`), except that the last enabled admin is never demoted.
- **Drishti still decides.** A user disabled in Drishti stays out whatever the provider says. Every sign-in,
  provisioning and refusal is audited (`login-sso`, `user-provisioned`, `login-sso-refused`).
- Password sign-in stays available (for break-glass admins); sites may keep only SSO users in practice.

### Consequences
- Operators configure the provider twice: the console (`auth.oidc.*`: issuer, client id and secret) and the server
  (`drishti.security.oidc.*`: issuer, client id, claims, role map). Both read the same environment variables.
- Provider URLs must be https (localhost excepted for tests).

## ADR-015: Packs inherit, and the more specific pack wins

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

### Context
Packs already built on each other (`requires:`): a parent loaded first, and its kinds could be opened from the child.
But any two packs defining the same mnemonic, role, route, connector or Sutra stopped the server, so a risk pack
could not adjust what it builds on (another label for MTM, a trader role that also sees VaR, the trading domain read
from PostgreSQL). Real domains layer: counterparty risk *is* trading and market data, with more.

### Decision
- **`extends: [a, b]`** (with `requires:` read the same way) makes a pack inherit everything its parents bring.
- **Precedence is C3 linearisation**, reading the parents rightmost first: the child wins over its parents, the
  rightmost parent over those to its left, and an ancestor shared through several parents counts once, after all of
  them. The server-wide order is the linearisation of every enabled leaf pack together, which C3 keeps consistent
  with each pack's own; an inconsistent order (two packs listing the same parents in opposite orders) or a cycle
  stops the server.
- **Overrides are global**: the winning definition applies to every user, whichever packs they have active (the
  user chose this over per-user layering). Every override is logged and shown under Admin → Health.
- **Strict where it matters**: a kind belongs to exactly one pack and is never overridden; unrelated packs may not
  define the same thing differently.
- Sutras: a more specific pack's Sutra with the same `name@version` replaces the parent's; site Sutra directories
  still take precedence over every pack.

### Consequences
- The banking risk packs declare `extends: [market-data, trading]`.
- A pack author can specialise a parent without forking it; a reader finds what won, and why, in one place.

## ADR-016: One console, many servers; the user picks one and signs in to it

| Status | Date | Decider |
|---|---|---|
| Accepted (built in 1.12) | 2026-10-01 | Ashutosh Sinha |

### Context
Today one console talks to exactly one server: `backend.url`, one token secret shared with that server, and caches
(packs, settings, business dates) keyed by user name alone. Organisations want several Drishti servers side by side:
an open one most staff use, and exclusive ones (a restricted desk's data, a regulator-facing environment, a
pre-production copy) that only some people may enter. The console (the user experience) should stay open to all;
each server decides who gets in.

### Decision
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

### Consequences
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

### Alternatives considered
- **One sign-in for all servers (federated session).** Simpler for users, but it makes the console a trust broker
  between servers and defeats exclusive servers; single sign-on already gives most of the convenience, since the
  provider's session makes each server's sign-in one click.
- **A console per server.** Works today without code, but users must know several addresses and nothing shows them
  which servers exist.
- **One server with exclusive packs.** Admin → Packs and roles already restrict what people see on one server, but
  the data, configuration and operators stay shared; it does not isolate a restricted environment.

### As built (2026-10-01)
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

## ADR-017: Sutras are YAML, through and through

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

Supersedes ADR-011. Amends ADR-003 and ADR-008 (file name).

### Context
ADR-011 made the standard Sutra a Markdown document (`*.sutra.md`) with one fenced `sutra` block holding the
YAML, so that each layout could carry the *why* beside the *what*. Plain `*.yaml` Sutras still loaded. In practice
that left two dialects of one language:

- People had to learn which one to write, and tools, tests and generators had to handle both.
- Ordinary YAML tooling did not see a Markdown Sutra as YAML: no JSON Schema completion, no YAML linter, no `yq`,
  no YAML highlighting in a code review. Studio needed its own Markdown editor to make up for it.
- The prose drifted. Much of it restated the layout ("Applies to", a "Panels" table, the strip) and went stale the
  first time someone changed the block without touching the text around it.
- Nothing in a file said which version of the language it was written in, so the grammar could not evolve safely.

### Decision
- **One dialect.** A Sutra is one YAML file, `<name>.v<N>.sutra.yaml`. Markdown Sutras are no longer read.
- **A language version.** The first key is `rachana: 1`. A file without it, or with a version this server does not
  read, is refused (`DRS-2009`). A future, incompatible grammar becomes `rachana: 2`, and a server can say exactly
  which files it does not understand instead of misreading them.
- **Prose has a place in the file.** Optional plain-text keys: `description` (one paragraph) and `notes` (longer, a
  `|` block) at the top, and `description` on any panel. They are text for people: never evaluated and not part of
  the view. What can be derived from the layout (which kind it applies to, its panels, its keys) is not written
  down at all; tools derive it, so it never drifts.
- **Strict parsing, unchanged.** Every unknown key is an error with its line and column (`DRS-2011`), so a typo in
  a key cannot pass silently. A `.sutra.md` or a plain `.yaml`/`.yml` file in a Sutra folder is reported
  (`DRS-2004`) with the fix, rather than skipped.
- **The schema is served.** `GET /api/v1/rachana/schema` returns the JSON Schema (draft 2020-12) of the language,
  generated from the grammar with this server's entity kinds and formats. Studio becomes a YAML editor that
  completes and checks against it; any editor that reads JSON Schema can do the same.
- **A one-step migration.** `python3 tools/rachana/md_to_yaml.py <file-or-folder> --delete` converts old files:
  the block becomes the file, `rachana: 1` is added, and the prose that is not derivable becomes `notes`. All 228
  shipped Sutras were converted this way and checked to build the same layouts. The pack generators write YAML.
- **Documentation follows.** A complete Sutra in a document is a ```` ```yaml ```` block whose first line (after
  comments) is `rachana: 1`; the test suite parses every one, so readers can paste them.

### Consequences
- One format to learn, teach, generate and review. Editors, linters and AI assistants treat a Sutra as the YAML it
  is; diffs in review are diffs of the layout.
- Prose is plain text, not rendered Markdown: no headings, tables or images in a Sutra. Longer design notes, and
  pictures, belong in the pack's guides, which can link to the Sutra.
- Anyone with `*.sutra.md` files (a site directory, a private pack) must convert them once; until then the server
  reports each as `DRS-2004` with the command to run, and keeps serving the rest.
- The schema and the parser come from the same grammar tables, so completion never offers what the parser refuses.
  The parser stays the authority: it also compiles every expression, which a schema cannot.
- Changing the grammar incompatibly now means a new `rachana:` version, with a converter, and an ADR.

## ADR-018: Packs come from a signed, versioned registry

| Status | Date | Decider |
|---|---|---|
| Accepted (built in 1.12) | 2026-10-01 | Ashutosh Sinha |

### Context
Packs were folders in the server's `config/packs/` directory: copied by hand, with no version history on the server, no
way to know who made a pack, and no rollback. A pack carries Sutras, vocabulary, connectors and roles, so a pack
from the wrong hands can point a connector at the wrong data or widen a role.

### Decision
1. **A registry is plain files**: `index.json` and one zip per pack version, served over HTTPS or read as a folder.
   No registry server to run; any web server or shared drive will do.
2. **Every archive is signed with Ed25519** by its publisher (`tools/packreg`, standard library and `openssl`). A
   server lists the publishers it trusts (`drishti.packs.registry.trusted-keys`: name to public key). Anything not
   signed by a trusted key is refused.
3. **Installing checks before it changes anything**: size and SHA-256 against the index, the signature, that every
   file stays inside the pack and the unpacked size is bounded, and that `pack.yaml` names the same pack and version.
   It then goes to its own folder (`drishti.packs.installed-dir`), looked in before the shipped packs, and the
   server's usual pack check and in-place restart follow (with the files put back if the server cannot start).
4. **Versions are kept**: the version an install replaces is kept, and **Roll back** puts it back.
5. **Archives are reproducible**: the same pack folder makes the same archive (fixed order and times), so a
   reviewer can rebuild and compare.

### Consequences
- Publishers must keep their private keys safe; a stolen key lets someone publish as them until servers drop it
  from `trusted-keys`. Rotate by adding the new key, republishing, then removing the old one.
- Plain `http:` registries are refused (signatures would still hold, but the index could be replayed); a test-only
  switch allows it.
- Installs are audited (`pack-installed`, `pack-upgraded`, `pack-rolled-back`) with the publisher and SHA-256.

### Alternatives considered
- **Packages in Maven or PyPI**: heavy for content that is mostly YAML, and their trust model is the repository's,
  not the site's.
- **Git submodules or a pack repository**: good for authors, but servers would need git and credentials, and a
  checkout is not a signed release.
- **Checksums without signatures**: protects against corruption, not against a changed index.

## ADR-019: Pricing is a QuantLib service behind the Drishti server

| Status | Date | Decider |
|---|---|---|
| Proposed (a later candidate, not in a planned release) | 2026-10-01 | Ashutosh Sinha |

### Context
Users want proper pricing on what Drishti shows: a model NPV beside the booked MTM, Greeks, scenarios, implied vols.
Calc runs Python in the browser with numpy and scipy and a small analytics library (`drishti.quant`), which covers
screen-sized analysis but not curve building, calibration or the wider product range QuantLib handles. QuantLib has
no WebAssembly build, and running it in a browser would mean a large download and a laptop's CPU for calibrations.

### Decision
1. **Pricing runs in a separate service, `drishti-quant`**: Python with QuantLib's published wheels and a small
   HTTP API, stateless, scaled by replicas, reachable only from the Drishti server.
2. **The Drishti server is the only client**: it checks a new `quant` role power and the user's right to open the
   entity, applies redaction, assembles market data for the business date from its own sources, caches results by
   (entity version, date, model, scenario), caps work, and records each pricing in the access log.
3. **Packs declare how products are priced** (`quant:` in `pack.yaml`: product → adapter, and where each input comes
   from in the trade and the market), so a new product is configuration plus, at most, an adapter in the service.
4. **The UX reaches it through the server**: `drishti.pricer` in Calc, a Valuation panel bound by Sutras, and a
   what-if drawer; later, overnight batches into the lake.

Design: [AI_GUIDE.md](../guides/AI_GUIDE.md#4-server-side-pricing-the-quant-service-proposed).

### Consequences
- One more container to deploy; without it, pricing is reported as not configured and nothing else changes.
- A crash or a long calibration in native QuantLib cannot affect the Drishti server.
- Every result names its model, market inputs and QuantLib version, so it can be reproduced.
- The same interface can later front ORE or a bank's own pricers.

### Alternatives considered
- **QuantLib's Java bindings inside the server**: one process, but native builds per platform and native crashes in
  the shared server.
- **QuantLib in WebAssembly in Calc**: no build exists; tens of MB more per user; rebuilt for every Pyodide version.
- **Only `drishti.quant` in the browser**: kept for analysis, but it is not a pricing library.

## ADR-020: Collaboration records live in the identity database; the notice is a link, never data

| Status | Date | Decider |
|---|---|---|
| Accepted (built through step 7; bridges and snapshots are phase 2) | 2026-10-05 | Ashutosh Sinha |

**Update:** the *where* part was revised: collaboration records now live in the content store by default
(`drishti.collab.store: content`; `jpa`, the identity database, or `file` remain for one node), so every Drishti\*net member sees
them ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#5-the-content-store-user-content-every-node-reaches) section 10.3). The link-not-data rule stands.

### Context
People who look at a trade or a gene variant in Drishti talk about it elsewhere (chat, email), losing the date and the data they were looking at, and
sometimes leaking a value the reader may not see. We added *share with a note* and comment threads anchored to the data, with an inbox and email.
Four questions had more than one reasonable answer: where the records live, how a notification is delivered, what a notification carries, and what
the records prove.

### Decision
1. **The records live in the identity database** (`drishti-identity`, package `collab`): SQLite by default, PostgreSQL in production, or a file store
   for one server (`drishti.collab.store`). They are users' words about data, not data, so they sit with users, roles and the audit trail, share its
   backup and transactions, and need no new store. One contract test runs against both implementations.
2. **Delivery is a transactional outbox.** A share or comment writes the record, the in-app notices, the email rows and the access-log row in one
   transaction; a dispatcher on any server claims rows under a lease, renders each mail for the recipient *at send time*, retries with backoff and keeps
   dead letters visible. A crash never loses a notice nor sends one for a rolled-back share. `Notifier` is an interface collected by Spring, so a new
   channel (a chat bridge, phase 2) is one more bean.
3. **A notice is a link, not data.** A share stores the sender's note and a **pin** (business date, "known at", generation, source), never a copy of
   the document. Whoever opens it gets the view built for them, with their own roles and masks, at the pinned date. A value of a masked field typed
   into a note or comment is found at write time and recorded as spans, so every reader without `raw` sees `•••`; a quote (`{$.mtm}`) is rendered
   per reader. Email carries the kind, the id and the note as that recipient may read it, and nothing else (`link-only` per pack for sensitive ids).
4. **Edits are history, and history is chained.** A comment is never overwritten: each revision is a row, hashed with the one before it (SHA-256,
   per thread). Retract and hide change what readers see, not the record. Retention removes whole threads, never one held by a legal hold; a compliance
   export is a zip with a manifest of hashes; `verify` recomputes the chains. The chain is evidence of tampering, not prevention.

### Consequences
- The identity database now grows with discussion; retention (off by default) and its backup are an operations matter
  ([OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md#123-retention-legal-holds-and-the-compliance-export)).
- Mail already sent cannot be recalled: that is why it carries no values. A reader who loses a right sees the notice stop showing the title and
  excerpt at once, because every read renders for current rights.
- Replacing an email provider, adding a bridge or moving the records to PostgreSQL touches configuration or one bean, not the services.
- Considered and rejected: a separate collaboration service and store (a second thing to back up, with no gain at this size); storing a rendered
  snapshot with the share (it would freeze rights at send time and leak to later readers); an in-memory queue (loses mail on a crash).

Design and as-built notes: [ARCHITECTURE.md §31](ARCHITECTURE.md#31-collaboration-write-back-and-approvals). The whole path worked through on one trade:
[HOW_IT_FITS.md §3.10](ARCHITECTURE.md#3111-share-and-discussion-end-to-end).

## ADR-021: Embedding is HTML over the wire for views, and the viewer's own code for reports

| Status | Date | Decider |
|---|---|---|
| Accepted (built: `<drishti-view>`, `<drishti-panel>`, `<drishti-report>`); decision 1 superseded by [ADR-022](DECISIONS.md) (2026-10-30) | 2026-10-11 | Ashutosh Sinha |

### Context
Host applications (a CRM, an intranet, a risk portal) want Drishti's live views and BI reports inside their own pages, signed in as their own users, themed by their
own tokens, without an iframe (an iframe cannot share a layout, a theme, focus or one network connection, and a strict host policy usually forbids it). Two things
had to be decided: **where the rendering happens**, and **how the code reaches the host**.

### Decision
1. **A view is rendered by the console and sent as HTML**; the element is a thin client. The console already turns a ViewModel into a screen with the panel macros
   (tables, trees, pivots, strips, About, masks, number formats, the *No data* rules). The embed API (`/embed/v1/views/...`) returns those fragments and live patches,
   and the element swaps them into a Shadow DOM after a sanitiser has rebuilt them from an allow-list. A second renderer in JavaScript would be a second
   implementation of every panel kind and every accessibility detail, for ever. We accept larger payloads (3 to 6 times before compression) and that a host
   cannot restyle a panel's inner structure beyond the custom properties and parts we expose.
2. **A report is drawn in the browser by the console viewer's own code**, because a report is not server-rendered HTML: it is a model plus one query per visual, drawn by
   `drishti_bi-visuals.js` and friends. `<drishti-report>` loads that same code (served with CORS under `/embed/v1/js/`), builds its DOM with `textContent` only, and asks the
   embed API for the model and the rows. There is one renderer of a report, shared by the viewer, the element and the designer's preview.
3. **The embed token is the identity, on every call.** The host's backend exchanges its credentials and its user's identity for a short-lived, read-only token
   (RFC 8693) at the Drishti server; the server verifies it on every call, applies the user's row rules and **always masks**. Which hosts may show which kinds and
   which reports is the host application's `kinds` list (`report:<id>`, `report:*`).
4. **The console serves the library; the npm package is the same bytes.** An exact semantic version is immutable with an `integrity.json` of SHA-384 hashes, a partial
   version redirects, and a committed record fails the build when a module changed under an unchanged version. Sheets, fonts and chart code come from the console that
   serves the data, so the library and the data always agree. The element API (attributes, properties, methods, events) and the embed API (`/embed/v1`) are two
   semantic-version contracts; a major of the embed API is announced with `Deprecation` and `Sunset` and then `410 DRS-8006`.

### Consequences
- Every fix to a panel or a visual reaches embedded views and reports with the console, with no second change.
- A report in an element cannot do what needs WebAssembly (Perspective) under a strict host policy, nor the viewer's navigation to other console pages: those are
  listed, not faked ([INTEGRATION_GUIDE.md, section 1.5](../guides/INTEGRATION_GUIDE.md#15-the-elements-one-by-one)).
- The console is a dependency of the host page (it must be reachable from the browser and named in the host's policy); the element has no offline mode.
- Considered and rejected: an iframe (the reasons above); a JavaScript renderer of ViewModels (a second renderer); a library bundled with every host (it would not match
  the console's data); embedding by the host's server fetching HTML (no live updates, no per-user token on every call).

Design and as-built notes: [INTEGRATION_GUIDE.md](ARCHITECTURE.md#30-the-client-renderer-and-drishti-elements). Developer guide: [guides/INTEGRATION_GUIDE.md](../guides/INTEGRATION_GUIDE.md#15-the-elements-one-by-one).

## ADR-022: One renderer, in the browser (supersedes ADR-021's rendering decision)

| Status | Date | Decider |
|---|---|---|
| Accepted (phase 1 built: the library and its parity tests; phase 2 built: renderer 1.0.0, the console's entity page drawn by it behind a client mode (since retired), Elements 2.0 and the embed API v2 beside v1; retiring the macros is the release after) | 2026-10-30 | Ashutosh Sinha |

### Context
[ADR-021](DECISIONS.md) chose to render a view on the console and send HTML to `<drishti-view>`, because a
JavaScript renderer would be "a second implementation of every panel kind and every accessibility detail, for ever". Since then the
ViewModel has become a published contract with a JSON Schema ([VIEWMODEL.md](VIEWMODEL.md)), hosts want Drishti screens without
the console's HTML (their own pages, phones, frameworks), GenUI needs a screen that arrives panel by panel, and the product owner made
a client-side renderer a first item. The reason ADR-021 gave stands: two renderers would drift. Its conclusion does not
follow from it: one renderer can live in the browser.

### Decision
1. **Views are rendered in the browser from the ViewModel by one library, Drishti Render** (`static/render/<version>/drishti-render.js`):
   plain ES modules, no build step, vendored dependencies only, nothing from a CDN. It renders every panel kind, state, chart, layout and
   behaviour the console's macros do.
2. **There is only ever one renderer.** Phase 1 builds the library and proves parity with the macros, ViewModel by ViewModel; phase 2
   moves the console's pages, `<drishti-view>` and `<drishti-panel>` onto it behind a setting, and then deletes the panel macros and the
   HTML path. Until a page moves, it keeps the server path unchanged.
3. **The sanitiser's rules are kept, applied while building**: no markup is ever parsed from data; elements, attributes and links pass
   the same allow-lists ADR-021's sanitiser used; no `eval`; the console's CSP unchanged; one Trusted Types policy (`drishti-render`), for
   script URLs only.
4. **Embeddable in place**: navigation, drill-downs and sheets stay in the host's container by default, with a back stack and a
   cancelable `drishti:navigate` event; nothing opens a window unless the host asks.
5. **Built for streaming**: the same `render()` draws a static, a live and a streamed ViewModel (skeleton, panel-add, panel-replace,
   patch, done, error), which is how GenUI's ephemeral screens will arrive.
6. ADR-021's decisions 2 (reports drawn by the viewer's code), 3 (the embed token on every call) and 4 (the console serves the library,
   immutable versions with SRI) are unchanged and apply to the renderer.

### Consequences
- The payload is the ViewModel, smaller than the HTML; hosts restyle through the same custom properties; screens answer to their
  container's width.
- Parity is a test, not a promise: `tests/test_render_parity_browser.py` draws every pack Sutra on its sample both ways and compares the
  accessible tree, text, cells, links, tones and chart series.
- During phase 2 both paths exist; the setting and the parity suite keep them equal until the macros are deleted.
- First paint needs the library and the ViewModel in the browser; the console will embed the ViewModel in its page.
- Considered and rejected: keeping HTML over the wire (no GenUI streaming of JSON, no host-side frameworks, payloads 3 to 6 times larger);
  a renderer bundled with a build toolchain (ADR-006: vendored, no build pipeline); a renderer per host framework (many renderers).

Design: [INTEGRATION_GUIDE.md](../guides/INTEGRATION_GUIDE.md). Developer guide: [guides/INTEGRATION_GUIDE.md](../guides/INTEGRATION_GUIDE.md#16-drishti-render-one-import-one-call).
