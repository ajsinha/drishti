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

# The production standard

The short list of rules a change is judged by: lane briefs, the gate review and a bank's reviewer read this page. Each rule names the
check that enforces it. The install, upgrade and backup commands are in
[OPERATIONS_GUIDE.md, Production deployment (Linux)](../guides/OPERATIONS_GUIDE.md); every setting, default and variable is in
[CONFIGURATION.md](CONFIGURATION.md). This page repeats neither.

Production is Linux. This first section is the production profile; the other sections of the standard (data, targets, configuration,
secrets, modules and engines, layout, lifecycle, acceptance) are added as their rules are built.

## 1. The production profile

### 1.1 What it is and how it is switched on

One setting names the profile of a server and of its console: `drishti.profile` (`DRISHTI_PROFILE`). Put it in the settings file
(`drishti.profile=production`, see `config/settings.examples/prod.ini`); the console reads the same file.

| Profile | For | What it does |
|---|---|---|
| `production` | a bank's servers | safe defaults (below); **refuses to start** while any rule is broken |
| `demo` | a demonstration or a developer's machine, never a shared server | today's open defaults, the development administrator and three demo users (below) |
| none (blank) | development | the open defaults, no demo users |

Under `production` a setting you leave out takes the safe value, and a setting you set to an unsafe value is **refused, never
overridden**. The server collects every broken rule and stops with one line each, so one start shows all of them:

```
Drishti will not start under the production profile (docs/admin/PRODUCTION_STANDARD.md): 2 settings are unsafe
  - drishti.security.enabled is off: no one signs in. Fix: set DRISHTI_SECURITY_ENABLED=true (or remove it: production defaults to on)
  - drishti.sources.plugins.demo.enabled is on: the demo source would serve sample data. Fix: set DRISHTI_DEMO_ENABLED=false (or remove it: production defaults to off)
```

Every line names the setting and the fix; the console does the same (`The console will not start under the production profile ...`).

### 1.2 The rules

| # | Rule | Safe default under `production` | Refused when | Enforced by |
|---|---|---|---|---|
| P1 | Sign-in is on at the server | `drishti.security.enabled=true` | it is `false` | `ProductionProfile` start check; `ProductionProfileTest` |
| P2 | The token secret is long and shared | none: you set `DRISHTI_TOKEN_SECRET` | missing or under 32 bytes (server and console) | server check; `core/production.py`; `test_production_profile.py` |
| P3 | No built-in administrator | `drishti.identity.seed-admin=false` | seeding is on with the built-in name `drishti-dev-admin` or the built-in password; a first administrator of your own (`DRISHTI_SEED_USERNAME`, a password from the secrets file, `DRISHTI_SEED_ADMIN=true`) is allowed | server check; `ProductionProfileTest`, `ProductionProfileStartTest` (seeds nobody) |
| P4 | No demo users | none are created | `drishti.identity.seed-demo-users` is `true` | server check; `DemoUsersTest` (the demo profile makes them, production never) |
| P5 | The demo source is off | `drishti.sources.plugins.demo.enabled=false` | it is `true` | server check |
| P6 | No development switches | off | `drishti.modules.dev`, `drishti.rachana.studio-save` or the API description and its UI (`springdoc.api-docs.enabled`) is on | server check |
| P7 | Engines are pinned and licensed | none: `drishti.engine-lib.trust=pinned`, the licence files installed | trust is `folder`; the engines' licence file (`drishti.engine-lib.licence`) or the access licence (`<drishti.access.dir>/licence.yaml`) is not a file | server check |
| P8 | One identity database per cluster | PostgreSQL | SQLite while this server is a node of a cluster (`<net dir>/node.yaml` says `enabled: true`) | server check |
| P9 | TLS is stated | none: you state it | the server speaks plain HTTP and `drishti.production.tls=proxy` is not declared (server TLS: `server.ssl.enabled=true`) | server check |
| P10 | The console requires sign-in | `auth.enabled=true` | it is `false` | `core/production.py` |
| P11 | The console's cookies are secure | `Secure` (`auth.secure_cookie=true`), `HttpOnly` and `SameSite=Lax` (fixed in code) | `auth.secure_cookie` is `false` | `core/production.py`; `auth_routes.py` sets the other two |
| P12 | The console is not on the network without TLS in front | bind `127.0.0.1` behind a proxy | `server.host` is not a loopback address and `production.tls=proxy` is not declared | `core/production.py` |
| P13 | Cross-origin calls are an allow-list | the registered embedding origins only | there is no wildcard setting to refuse | by design: `embed` origins are registered, never `*` |

The checks run before any bean is built (`kernel/drishti-server`, `com.ash.drishti.server.profile.ProductionProfile`, run by
`ProductionProfileProcessor`) and before the console's first request (`drishti-console/core/production.py`, called by
`run_drishti_web.py`); a server and a console on the same settings file agree on the profile.
Rules that need a place to look (a licence file, the cluster folder) read the folders the settings name; they never guess a path.

### 1.3 The demo profile

`drishti.profile=demo` (the committed `config/profiles/demo.ini` sets it) keeps today's behaviour and, on the start that creates the
first administrator (an empty user store), also creates three demo users with their roles and packs, each with a published password:

| User | Password | Role (kinds of) | Packs | Home |
|---|---|---|---|---|
| `drishti-dev-admin` | `drishti-dev-admin123` | `admin` | all | every Home |
| `demo-risk` | `demo-risk-pass2026` | `risk-trading` (trading, market-data, market-risk, counterparty-risk, core) | `core`, `trading`, `market-data`, `market-risk`, `counterparty-risk` | the risk manager's, then the trader's |
| `demo-genomics` | `demo-genomics-pass2026` | `genomics-reader` (genomics, genomics-showcase, protein-engineering) | `genomics`, `genomics-showcase`, `protein-engineering` | none yet (no genomics Home is shipped) |
| `demo-market` | `demo-market-pass2026` | `market-data-reader` (market-data) | `market-data` | none |

Admin shows the same red *default password* warning, naming every account that still signs in with a published password, until each is
changed. A pack that is not installed is left out of its role and user; a user none of whose packs is installed is not created.
`DRISHTI_SEED_DEMO_USERS=false` turns the three off under the demo profile; `true` turns them on without it. Never in production (P4).
How to make the same three users by hand: [ADMIN_GUIDE.md, Three demo users](../guides/ADMIN_GUIDE.md#27-three-demo-users).

### 1.4 Not yet in this section

The licence and edition rules above are the start check only; a production acceptance run (`doctor --acceptance` with a token file),
asymmetric token signing and TLS between hosts are later sections of the standard.
