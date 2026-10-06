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
# Configuration

This is the complete reference for configuring Drishti: every `drishti.*` setting of the server, every
setting of the web console, the environment variables that feed them, and how the layers combine. Each
section gives the default, what the setting does, and an example you can copy.

If you only want to get something running, read [How configuration works](#how-configuration-works) and
[Common recipes](#common-recipes) first, then look keys up in the reference as you need them.

Contents

1. [How configuration works](#how-configuration-works)
2. [Placeholders: `${NAME:default}`](#placeholders-namedefault) and [every placeholder of `application.yaml`](#every-placeholder-in-the-servers-applicationyaml)
3. [Value formats](#value-formats)
4. [Common recipes](#common-recipes)
5. [Server reference](#server-reference) (`drishti.*`)
6. [Connector settings, plugin by plugin](#connector-settings-plugin-by-plugin)
7. [Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles)
8. [Spring settings you may need](#spring-settings-you-may-need)
9. [Console reference](#console-reference)
10. [Environment variable index](#environment-variable-index)

---

## How configuration works

Drishti has two programs and each has its own configuration:

| Program | Bundled file | Local override (git-ignored) | Env-var form | Argument form |
|---|---|---|---|---|
| Server (Java, port 18480) | `drishti-server/src/main/resources/application.yaml` (inside the jar) | `./application.local.yaml` in the server's **working directory** | `DRISHTI_SOURCES_FETCHTIMEOUT=3s` | `--drishti.sources.fetch-timeout=3s` |
| Console (Python, port 17480) | `drishti-console/config/application.yaml` | `drishti-console/config/application.local.yaml` | `DRISHTI_CONSOLE__BACKEND__TIMEOUT_SECONDS=10` | `--backend.timeout_seconds=10` |

Secrets never go in a tracked file. Put them in environment variables (or in the git-ignored local file on a
developer machine).

### Precedence

Lowest to highest; a later layer wins:

```
pack.yaml contributions  <  application.yaml  <  application.local.yaml  <  environment variables  <  --arguments
```

* **Packs** (`packs/<name>/pack.yaml`) contribute mnemonics, roles, connectors, routes, link fields and so on.
  They are added as the lowest-precedence source, so anything the site writes in its own configuration
  overrides a pack.
* **`application.yaml`** is the bundled default.
* **`application.local.yaml`** is loaded by the bundled file itself
  (`spring.config.import: optional:file:./application.local.yaml`). The path is relative to the directory you
  start the server from. If the file does not exist nothing happens (`optional:`).
* **Environment variables** override both files.
* **Command-line arguments** (`--key=value` after the jar name) override everything.

### Worked example: four layers, one key

The longest a source read may take is `drishti.sources.fetch-timeout`, `2s` in the bundled file.

1. Create `application.local.yaml` in the directory you start the server from (the repository root when
   you run `java -jar drishti-server/target/drishti-server-1.17.0-exec.jar`):

   ```yaml
   drishti:
     sources:
       fetch-timeout: 3s
   ```

   Now the effective value is `3s`.

2. Start the server with an environment variable as well:

   ```bash
   DRISHTI_SOURCES_FETCHTIMEOUT=4s java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
   ```

   The effective value is `4s`: the environment beats the local file.

3. Add an argument:

   ```bash
   DRISHTI_SOURCES_FETCHTIMEOUT=4s java -jar drishti-server/target/drishti-server-1.17.0-exec.jar \
       --drishti.sources.fetch-timeout=5s
   ```

   The effective value is `5s`: arguments beat everything.

### Two kinds of environment variable

The server understands environment variables in two ways. Knowing the difference saves confusion.

1. **Relaxed names.** Any key can be set from the environment by upper-casing it, turning `.` into `_` and
   dropping `-`: `drishti.sources.fetch-timeout` becomes `DRISHTI_SOURCES_FETCHTIMEOUT`. Spring Boot also
   accepts the older form with `-` turned into `_` (`DRISHTI_SOURCES_FETCH_TIMEOUT`). Relaxed names work for
   every key, whether or not the YAML mentions a variable.
2. **Named placeholders.** Some values in `application.yaml` and in the packs are written as
   `${DRISHTI_TOKEN_SECRET:}` or `${DRISHTI_PACKS:finance}`: "use this variable, else the default after the
   colon". These short names (`DRISHTI_PACKS`, `DRISHTI_TOKEN_SECRET`, `DRISHTI_DELTA_ROOT`) are the documented
   ones; they are listed in the [index](#environment-variable-index).

One consequence: a placeholder only lives in the file that contains it. If your `application.local.yaml` sets
`drishti.security.secret: something`, then `DRISHTI_TOKEN_SECRET` is no longer consulted for that key (the
local file replaced the line holding the placeholder), while the relaxed name `DRISHTI_SECURITY_SECRET` would
still win. Keep secrets out of files and you never meet this.

Maps and lists (`drishti.sources.routes`, `drishti.security.roles`, `drishti.graph.id-patterns`) are much
easier to write in YAML than as environment variables. Put them in `application.local.yaml`.

### Seeing what is in effect

There is no endpoint that dumps the configuration, but most settings show up somewhere you can read:

| To check | Ask | Look for |
|---|---|---|
| business date, calendar, zone, history | `curl -s localhost:18480/api/v1/business-date` | `"calendar":"USNY","zone":"America/New_York","earliest":"2021-09-30"` |
| Studio save, governance | `curl -s localhost:18480/api/v1/studio/settings` | `{"approve":true,"review":true,"save":true}` |
| which sources and connectors started | `curl -s localhost:18480/api/v1/sources` | one entry per started plugin or connector, with `"health":"UP"` |
| packs loaded | `curl -s localhost:18480/api/v1/packs` | one entry per pack |
| everything at once | `curl -s localhost:18480/api/v1/admin/health` (admin only when security is on) | `"summary":{"packsWithProblems":0,"failedToStart":0,"sourcesDown":0,"sourcesDegraded":0,…}` |

A connector that failed to start appears in the `failedToStart` map, name to reason (for example
`"ops-lake": "no plugin named 'delta2'"`), which is usually a mistyped plugin name or setting.

---

## Placeholders: `${NAME:default}`

Many values in `application.yaml` are written `${NAME:default}`, for example `port: ${DRISHTI_PORT:18480}`.

**The server (Spring).** These are Spring property placeholders. At start Spring looks for a property called `NAME`
in every property source, and uses the first it finds, else the text after the colon:

| Where `NAME` is set | Example | Consulted |
|---|---|---|
| a command-line argument | `--DRISHTI_PORT=18481` | yes (highest) |
| a Java system property | `java -DDRISHTI_PORT=18481 -jar …` | yes |
| an environment variable | `DRISHTI_PORT=18481 java -jar …` | yes |
| none of them | | the default, `18480` |

Each row was tried against a scratch server. Points to know:

* **An empty default.** `${DRISHTI_PLUGIN_DIR:}` has an empty default: nothing is set when the variable is absent. A variable that is
  *set to empty* (`DRISHTI_PACKS=`) gives the empty string, not the default.
* **Which applies, the placeholder or the relaxed name?** Both exist for a key such as `drishti.packs.enabled`
  (written `${DRISHTI_PACKS:finance}` in the file). The placeholder is how the *file's own value* is computed.
  The relaxed name (`DRISHTI_PACKS_ENABLED`, [above](#two-kinds-of-environment-variable)) overrides the key itself, so
  with both set the relaxed one wins: `DRISHTI_PACKS=genomics DRISHTI_PACKS_ENABLED=economics` loads only `economics`.
  A key with no placeholder in the file has only the relaxed name.
* **Precedence** of the sources, highest first: command-line arguments, Java system properties, environment
  variables, config files (a profile's file such as `application-files.yaml` over `application.yaml`). So
  `--server.port=18989` beats `DRISHTI_PORT=18988` (tried: the server listened on 18989). The placeholder
  is resolved against all of them at once, so `DRISHTI_PORT` as an argument, property or environment variable is
  the same placeholder.
* **Nested.** The default may itself be a placeholder: `${A:${B:18480}}` uses `A`, else `B`, else `18480` (tried
  with `--server.port='${NOPE_A:${NOPE_B:18989}}'`). A placeholder may also sit inside a longer value
  (`optional:file:${DRISHTI_PACKS_OVERLAY:./data/packs/added.yaml}`, `s3a://${LAKE_BUCKET:bank-lake}/drishti`).
  The bundled file nests none.
* **Your own files** can use the syntax anywhere (`password: ${TRADES_DB_PASSWORD}`).

**The console (Python).** `drishti-console/core/config.py` reads the same syntax, with differences: `NAME` is an
**environment variable only** (no system properties, no other config keys); the default after the colon is text up to
the first `}` (no nesting); a missing variable with no default is empty; and the result is typed (`true`/`false`
become booleans, digits numbers). Precedence, lowest first: `drishti-console/config/application.yaml`, `drishti-console/config/application.local.yaml`,
then `DRISHTI_CONSOLE__A__B` environment variables, then `--a.b=value` arguments. The console has no flag
for choosing another config *directory*; see [QUICKSTART](../guides/QUICKSTART.md#build-and-run-without-the-wrapper-or-from-an-ide).

### Every placeholder in the server's `application.yaml`

Generated from `drishti-server/src/main/resources/application.yaml` (and checked against it by
`drishti-console/tests/test_docs_placeholders.py`, so the table cannot drift). "Sets" is the key the placeholder fills. Profile
files (`application-files.yaml`, `application-postgres.yaml`, …) and packs have placeholders of their own, listed in
[Environment variables used by the packs and profiles](#environment-variables-used-by-the-packs-and-profiles).

| Variable | Sets | Default | What it does |
|---|---|---|---|
| `DRISHTI_PACKS_OVERLAY` | `spring.config.import` | `./data/packs/added.yaml` | The file of packs an administrator loaded from Admin → Packs; imported at start (`spring.config.import`) and named again by `drishti.packs.overlay`. Written by the server, not by hand. |
| `DRISHTI_PORT` | `server.port` | `18480` | The server's HTTP port. |
| `DRISHTI_PRODUCT` | `drishti.branding.product` | `Drishti` | The product name shown in pages and messages. |
| `DRISHTI_PLUGIN_DIR` | `drishti.sources.plugin-dir` | empty | A directory of extra plugin jars; empty: none. |
| `DRISHTI_DEMO_ENABLED` | `drishti.sources.plugins.demo.enabled` | `true` | Run the demo source (the packs' sample data). |
| `DRISHTI_FEEDS` | `drishti.sources.plugins.file.settings.root` | `./data/feeds` | The folder the bundled `file` source reads. |
| `DRISHTI_REST_ENABLED` | `drishti.sources.plugins.rest.enabled` | `false` | Run the `rest` source. |
| `DRISHTI_REST_URL` | `drishti.sources.plugins.rest.settings.base-url` | `http://localhost:9000/api` | The REST source's base URL. |
| `DRISHTI_JDBC_ENABLED` | `drishti.sources.plugins.jdbc.enabled` | `false` | Run the `jdbc` source as itself (one instance). |
| `DRISHTI_JDBC_URL` | `drishti.sources.plugins.jdbc.settings.url` | empty | The JDBC URL of that instance. |
| `DRISHTI_JDBC_USER` | `drishti.sources.plugins.jdbc.settings.user` | empty | Its database user. |
| `DRISHTI_JDBC_PASSWORD` | `drishti.sources.plugins.jdbc.settings.password` | empty | Its database password. |
| `DRISHTI_ACTIVEMQ_ENABLED` | `drishti.sources.plugins.activemq.enabled` | `false` | Run the `activemq` source as itself. |
| `DRISHTI_RABBITMQ_ENABLED` | `drishti.sources.plugins.rabbitmq.enabled` | `false` | Run the `rabbitmq` source as itself. |
| `DRISHTI_S3_ENABLED` | `drishti.sources.plugins.s3.enabled` | `false` | Run the `s3` source as itself. |
| `DRISHTI_CALENDAR` | `drishti.business-date.calendar` | `USNY` | The holiday calendar that rolls the business date back (`USNY`, `GBLO`, `EUTA`, `JPTO`, or joined with `+`). |
| `DRISHTI_SECURITY_ENABLED` | `drishti.security.enabled` | `false` | Turn sign-in and role checks on. |
| `DRISHTI_TOKEN_SECRET` | `drishti.security.secret` | empty | The token signing secret (at least 32 bytes), shared with the console. |
| `DRISHTI_METRICS_TOKEN` | `drishti.security.metrics-token` | empty | A bearer token for Prometheus to scrape `/actuator/prometheus` with security on. |
| `DRISHTI_OIDC_ENABLED` | `drishti.security.oidc.enabled` | `false` | Verify single-sign-on ID tokens. |
| `DRISHTI_OIDC_ISSUER` | `drishti.security.oidc.issuer` | empty | The OIDC issuer URL. |
| `DRISHTI_OIDC_CLIENT_ID` | `drishti.security.oidc.client-id` | empty | The OIDC client id. |
| `DRISHTI_ACCESS_LOG` | `drishti.access-log.enabled` | `true` | Record who looked at what (Admin → Access). |
| `DRISHTI_COLLAB_ENABLED` | `drishti.collab.enabled` | `true` | Share with a note, the directory and the inbox ([COLLABORATION.md](../architecture/COLLABORATION.md)). |
| `DRISHTI_COLLAB_DIR` | `drishti.collab.dir` | `./data/collab` | Where the file store keeps shares and inbox rows (`drishti.collab.store: file`), and exports. |
| `DRISHTI_CONSOLE_URL` | `drishti.collab.console-url` | empty | The console's address, put in front of `/share/sh_…` in the links shares carry; email stays off while it is empty. |
| `DRISHTI_MAIL_FROM` | `drishti.collab.email.from` | `drishti@localhost` | The From address of collaboration email. |
| `DRISHTI_MASK_COPIES` | `drishti.security.mask-copies` | `false` | Also scrub exact copies of a masked value inside other text of the same document. |
| `DRISHTI_MAX_SWALLOW` | `server.tomcat.max-swallow-size` | `64MB` | How much of an oversized body Tomcat drains after answering 413, so the client reads the answer. |
| `DRISHTI_REPORTS_ENABLED` | `drishti.reports.enabled` | `true` | Run the scheduled-reports scheduler on this server. |
| `DRISHTI_REPORTS_DIR` | `drishti.reports.folder` | `./data/reports` | Where report files are written. |
| `DRISHTI_SUTRA_WATCH` | `drishti.rachana.watch` | `auto` | How Sutra hot reload notices edits: `auto` (file events, polling when they are unavailable) or `poll`. |
| `DRISHTI_CALC_ENABLED` | `drishti.calc.enabled` | `true` | Allow Calc (Python in the browser). |
| `DRISHTI_LAYOUTS_ENABLED` | `drishti.layouts.enabled` | `true` | Allow personal layouts. |
| `DRISHTI_PIVOT_ENABLED` | `drishti.pivot.enabled` | `true` | Allow the Pivot tab. |
| `DRISHTI_ASK_ENABLED` | `drishti.explain.ask.enabled` | `false` | Turn on Ask about this page (also list packs in `drishti.explain.ask.packs`). |
| `DRISHTI_ASK_ENDPOINT` | `drishti.explain.ask.endpoint` | empty | The model endpoint URL for Ask. |
| `DRISHTI_ASK_MODEL` | `drishti.explain.ask.model` | empty | The model name sent for Ask. |
| `DRISHTI_ASK_KEY` | `drishti.explain.ask.api-key` | empty | The model endpoint credential (environment only; never logged). |
| `DRISHTI_IDENTITY_DB_URL` | `drishti.identity.database-url` | `jdbc:sqlite:./data/identity/drishti.db` | The identity database (users, roles, workspaces, audit); SQLite by default, or `jdbc:postgresql://…`. |
| `DRISHTI_IDENTITY_DB_USER` | `drishti.identity.database-user` | empty | Its user. |
| `DRISHTI_IDENTITY_DB_PASSWORD` | `drishti.identity.database-password` | empty | Its password. |
| `DRISHTI_USERS_FILE` | `drishti.identity.users-file` | `./data/identity/users.json` | The pre-1.10 users file, imported once into an empty database. |
| `DRISHTI_AUDIT_FILE` | `drishti.identity.audit-file` | `./data/identity/audit.jsonl` | The pre-1.10 audit file, imported once. |
| `DRISHTI_FORCE_PW_CHANGE_ON_CREATE` | `drishti.identity.force-password-change-on-create` | `false` | Force a password change for newly created users. |
| `DRISHTI_FORCE_PW_CHANGE_ON_RESET` | `drishti.identity.force-password-change-on-reset` | `false` | Force a password change after an administrator reset. |
| `DRISHTI_SEED_ADMIN` | `drishti.identity.seed-admin` | `true` | Create the development admin on first start with no users; `false` for production. |
| `DRISHTI_SEED_USERNAME` | `drishti.identity.seed-username` | `drishti-dev-admin` | The seeded administrator's user name; with `DRISHTI_SEED_PASSWORD` it is the first administrator you chose (`deploy/compose.yaml` requires both). |
| `DRISHTI_SEED_PASSWORD` | `drishti.identity.seed-password` | `drishti-dev-admin123` | Its password. Only the built-in default shows the "default password" warning. |
| `DRISHTI_API_DOCS` | `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled` | `true` | The OpenAPI description and its UI; set explicitly so a start does not warn they are on by default. |
| `DRISHTI_SUTRAS` | `drishti.rachana.dirs` | `./sutras` | Site Sutra directories, scanned recursively, beside the packs' Sutras. |
| `DRISHTI_STUDIO_SAVE` | `drishti.rachana.studio-save` | `false` | Let Sutra Studio save files (authors only). |
| `DRISHTI_SUTRA_REVIEW` | `drishti.governance.enabled` | `true` | A Studio save is a proposal an approver makes live. |
| `DRISHTI_SUTRA_FOUR_EYES` | `drishti.governance.four-eyes` | `true` | Nobody approves their own proposal (with security on). |
| `DRISHTI_GOVERNANCE_DIR` | `drishti.governance.dir` | `./data/governance` | Where proposals are kept. |
| `DRISHTI_PACKS_DIR` | `drishti.packs.dir` | `./packs` | The packs directory. |
| `DRISHTI_PACKS_OVERLAY` | `drishti.packs.overlay` | `./data/packs/added.yaml` | The file of packs an administrator loaded from Admin → Packs; imported at start (`spring.config.import`) and named again by `drishti.packs.overlay`. Written by the server, not by hand. |
| `DRISHTI_PACKS_INSTALLED` | `drishti.packs.installed-dir` | `./data/packs/installed` | Where packs installed from a registry are kept. |
| `DRISHTI_PACKS_SETTINGS` | `drishti.packs.settings-dir` | `./data/packs/settings` | The administrator's per-pack data-source override files (Admin → Packs → Data source). |
| `DRISHTI_PACKS_DEPLOY_HISTORY` | `drishti.packs.deploy.history-file` | `./data/packs/deploy-history.jsonl` | The history of archive deployments and rollbacks. |
| `DRISHTI_PACK_REGISTRY` | `drishti.packs.registry.url` | empty | A signed pack registry (a folder, `file:` or `https:` URL with `index.json`); empty: none. |
| `DRISHTI_PACKS` | `drishti.packs.enabled` | `finance` | The packs to load (comma list); a pack's parents load with it. |
| `DRISHTI_DEFAULT_PACKS` | `drishti.packs.default-for-users` | empty | Packs new users get; empty: every installed pack. |
| `DRISHTI_SUGGEST_LIMIT` | `drishti.commands.suggest-limit` | `25` | Entries in the command line's dropdown. |

### Every placeholder in the console's `application.yaml`

From `drishti-console/config/application.yaml`, resolved from environment variables only.

| Variable | Sets | Default | What it does |
|---|---|---|---|
| `DRISHTI_CONSOLE_HOST` | `server.host` | `127.0.0.1` | The address the console listens on. |
| `DRISHTI_CONSOLE_PORT` | `server.port` | `17480` | The console's port. |
| `DRISHTI_BACKEND_URL` | `backend.url` | `http://127.0.0.1:18480` | Where the server is. |
| `DRISHTI_PRODUCT` | `ui.product` | `Drishti` | The product name shown in pages. |
| `DRISHTI_USER` | `ui.user` | `ash` | The acting user while sign-in is off. |
| `DRISHTI_ABOUT_PREFETCH` | `ui.about_prefetch` | `true` | Fetch *About this page* when a view goes idle, so labels with a glossary entry are underlined at once; costs one explain per page view. |
| `DRISHTI_AUTH_ENABLED` | `auth.enabled` | `false` | Turn console sign-in on. |
| `DRISHTI_SESSION_SECRET` | `auth.session_secret` | empty | The session cookie secret (at least 32 characters); environment only. |
| `DRISHTI_TOKEN_SECRET` | `auth.token_secret` | empty | The token secret shared with the server's `drishti.security.secret`. |
| `DRISHTI_SESSION_RECHECK_SECONDS` | `auth.recheck_seconds` | `10` | How often a session is re-checked against the server. |
| `DRISHTI_SECURE_COOKIE` | `auth.secure_cookie` | `true` | Mark cookies `Secure` (`false` only for plain-HTTP development with sign-in on). |
| `DRISHTI_OIDC_ENABLED` | `auth.oidc.enabled` | `false` | Run the single-sign-on flow. |
| `DRISHTI_OIDC_ISSUER` | `auth.oidc.issuer` | empty | The OIDC issuer URL. |
| `DRISHTI_OIDC_CLIENT_ID` | `auth.oidc.client_id` | empty | The OIDC client id. |
| `DRISHTI_OIDC_CLIENT_SECRET` | `auth.oidc.client_secret` | empty | The OIDC client secret; empty for a public client (PKCE alone). |
| `DRISHTI_OIDC_REDIRECT_URI` | `auth.oidc.redirect_uri` | empty | The redirect URI; empty: `<console>/auth/oidc/callback`. |
| `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | `live.max_subscriptions` | `32` | Live subscriptions one browser may hold. |
| `DRISHTI_CALC_ENABLED` | `calc.enabled` | `true` | Offer Calc. |
| `DRISHTI_LAYOUTS_ENABLED` | `layouts.enabled` | `true` | Offer layout mode. |
| `DRISHTI_PACKS_DIR` | `packs.dir` | `../packs` | The packs directory (a relative path resolves from `drishti-console/`). |

---

## Value formats

| Type | Written as | Examples |
|---|---|---|
| Duration | a number with a unit: `ms`, `s`, `m`, `h`, `d` (or ISO-8601 `PT2S`) | `50ms`, `2s`, `15m` |
| Period (dates) | ISO-8601 period | `P5Y` (five years), `P18M`, `P90D` |
| Boolean | `true` / `false` | `true` |
| List | YAML list, or a comma-separated string (handy in env vars) | `[finance, trading]` or `finance,trading` |
| Map | YAML mapping | `routes: { trade: trading-store }` |
| Plugin and connector settings | always strings; quote numbers if you like | `pool-size: "4"` or `pool-size: 4` |

---

## Common recipes

### Change the port

```bash
DRISHTI_PORT=18490 java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
```

You should see `Tomcat started on port 18490` in the log. Point the console at it with
`DRISHTI_BACKEND_URL=http://127.0.0.1:18490`.

### Load more packs

```bash
DRISHTI_PACKS=finance,trading,market-data java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
```

Packs a listed pack `extends:` (or the older `requires:`) are loaded too, dependencies first. Then
`curl -s localhost:18480/api/v1/packs` lists each loaded pack. If the console runs from another directory,
set `DRISHTI_PACKS_DIR` to an absolute path so both programs find the same folder.

### Turn security on (production)

```bash
export DRISHTI_SECURITY_ENABLED=true
export DRISHTI_TOKEN_SECRET="$(openssl rand -base64 48)"   # at least 32 bytes; give the console the same value
export DRISHTI_SEED_ADMIN=false
java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
```

The console needs `DRISHTI_AUTH_ENABLED=true`, the same `DRISHTI_TOKEN_SECRET`, and its own
`DRISHTI_SESSION_SECRET` (at least 32 characters). With security on, a call without a token is refused:

```bash
curl -s localhost:18480/api/v1/packs
```

You should see an RFC 7807 problem with status 401. A secret shorter than 32 bytes stops the server at
start-up with `drishti.security.secret must be at least 32 bytes when security is enabled`.

### Send one kind to a different source

```yaml
# application.local.yaml
drishti:
  sources:
    routes:
      trade: trading-store       # a connector or plugin name
```

### Add a site Delta Lake connector

```yaml
drishti:
  sources:
    connectors:
      ops-lake:
        plugin: delta
        kinds: [shipment]
        settings:
          root: /data/lake          # or s3a://my-bucket/lake
          domain: logistics
          mode.shipment: effective
```

Restart, then `curl -s localhost:18480/api/v1/sources` shows an entry named `ops-lake`.

### Use a different holiday calendar

```bash
DRISHTI_CALENDAR=USNY+GBLO java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
```

`curl -s localhost:18480/api/v1/business-date` then shows `"calendar":"USNY+GBLO"`.

---

## Server reference

Each table lists the key relative to its section, the default (and the named environment variable, if any),
and what it does. "From packs" means the bundled file leaves it empty and the enabled packs fill it in.

### `drishti.sources` — where data comes from

```yaml
drishti:
  sources:
    fetch-timeout: 2s
    plugin-dir: /opt/drishti/plugins
    default-route: demo
    routes: { trade: trading-store }
    plugins:
      rest: { enabled: true, settings: { base-url: "https://risk.internal/api" } }
    connectors:
      risk-lake: { plugin: delta, settings: { root: /data/lake, domain: risk } }
```

| Key | Default | Meaning |
|---|---|---|
| `fetch-timeout` | `2s` | The longest a single source read may take. Slower reads show the entity as unavailable instead of holding the view. |
| `plugin-dir` | empty (`DRISHTI_PLUGIN_DIR`) | A directory of extra plugin jars. Each jar gets its own class loader. Plugins on the class path are always found. |
| `default-route` | `demo` | The plugin or connector tried first for a kind that has no route. |
| `routes.<kind>` | `{}` plus from packs | Which plugin or connector serves a kind. Packs route their kinds to their connectors (`trade: trading-store`). |
| `plugins.<name>.enabled` | `true` for any plugin not listed | Whether the plugin runs as itself (one instance). A plugin used only through `connectors` does not also run as itself unless it is listed here. |
| `plugins.<name>.settings.*` | per plugin | Settings for that single instance; see [connector settings](#connector-settings-plugin-by-plugin). |
| `connectors.<name>.plugin` | required | A named instance of a plugin (`delta`, `jdbc`, `file`, `rest`, `kafka`, `aerospike`, `redis`, `mongodb`, `iceberg`, `duckdb`, `activemq`, `rabbitmq`, `s3`, `feed`). Run a plugin as often as you like. |
| `connectors.<name>.enabled` | `true` | Switch one connector off without deleting it. |
| `connectors.<name>.kinds` | what the plugin reports | Restrict or declare the kinds it serves. |
| `connectors.<name>.settings.*` | per plugin | Settings for this instance. `source-name` defaults to the connector's name. |

What the bundled file sets under `plugins`:

| Plugin | `enabled` default | Bundled settings |
|---|---|---|
| `demo` | `true` (`DRISHTI_DEMO_ENABLED`) | sample directories come from the packs |
| `file` | `true` | `root: ${DRISHTI_FEEDS:./data/feeds}`, `source-name: feed-file`, `rescan-seconds: 30` |
| `rest` | `false` (`DRISHTI_REST_ENABLED`) | `base-url: ${DRISHTI_REST_URL:http://localhost:9000/api}`, `path: /{kind}/{id}`, `source-name: rest`, `timeout-ms: 2000` |
| `jdbc` | `false` (`DRISHTI_JDBC_ENABLED`) | `url`, `user`, `password` from `DRISHTI_JDBC_URL`, `DRISHTI_JDBC_USER`, `DRISHTI_JDBC_PASSWORD`; `source-name: jdbc`, `pool-size: 4` |
| `delta` | `false` | runs only as named connectors (the packs declare one per data domain) |
| `aerospike` | `false` | runs only as named connectors (profile `aerospike`) |
| `activemq` | `false` (`DRISHTI_ACTIVEMQ_ENABLED`) | configure it, or use it as a named connector |
| `rabbitmq` | `false` (`DRISHTI_RABBITMQ_ENABLED`) | configure it, or use it as a named connector |
| `s3` | `false` (`DRISHTI_S3_ENABLED`) | configure it, or use it as a named connector |

### `drishti.business-date` — which day you are looking at

Every read is for a business date. The default date is "today" in `zone`, rolled back over weekends and
holidays of `calendar`. Users can pick another date in the top bar.

| Key | Default | Meaning |
|---|---|---|
| `calendar` | `USNY` (`DRISHTI_CALENDAR`) | `USNY`, `GBLO`, `EUTA`, `JPTO`, `WEEKENDS` (weekends only), or a joint calendar such as `USNY+GBLO`. An unknown name stops start-up with `unknown calendar …`. |
| `zone` | `America/New_York` | Whose clock decides what "today" is. Any Java zone id (`Europe/London`). |
| `history` | `P5Y` | How far back users may pick a date. |

Example: a London desk.

```yaml
drishti:
  business-date:
    calendar: GBLO
    zone: Europe/London
    history: P2Y
```

### `drishti.packs` — domain packs

| Key | Default | Meaning |
|---|---|---|
| `dir` | `./packs` (`DRISHTI_PACKS_DIR`) | The folder holding one sub-folder per pack. Relative to the server's working directory. The console reads the same variable (its default `../packs` is relative to `drishti-console/`), so use an absolute path when you set it. |
| `enabled` | `finance` (`DRISHTI_PACKS`) | Comma-separated packs to load, in order. Packs they `extends:` (or the older `requires:`) are loaded as well. A missing pack stops start-up with a clear message. |
| `default-for-users` | empty = every installed pack (`DRISHTI_DEFAULT_PACKS`) | The packs a user sees until an admin assigns packs to them. |

The pack loader also writes some keys for the rest of the server (`drishti.packs.loaded`,
`drishti.packs.kinds.*`, `drishti.packs.overrides`, `drishti.rachana.pack-dirs`,
`drishti.rachana.pack-formats-files`, `drishti.inference.pack-semantics-files`,
`drishti.sources.plugins.demo.settings.dirs`). Do not set them yourself. See [PACKS.md](../guides/PACKS.md).

### `drishti.packs.registry` — the signed pack registry

| Key | Default | Meaning |
|---|---|---|
| `drishti.packs.installed-dir` | `./data/packs/installed` (`DRISHTI_PACKS_INSTALLED`) | Where registry installs go; packs here win over `drishti.packs.dir`. Replaced versions are kept under `.previous/`. |
| `url` | empty (`DRISHTI_PACK_REGISTRY`) | A folder, `file:` or `https:` URL holding `index.json` and the archives. Empty: no registry. |
| `trusted-keys.<publisher>` | none | The publisher's Ed25519 public key (base64 DER, as `tools/packreg/packreg.py keygen` prints it). Packs signed by anyone else are refused. |
| `max-archive-mb` / `max-unpacked-mb` | `50` / `200` | Size limits for an archive and for what it unpacks to. |
| `allow-http` | `false` | Accept plain `http:` registries. For tests only. |

See [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#a-signed-pack-registry-publishing-and-installing).

### `drishti.packs.deploy` and `drishti.packs.settings-dir` — deploy an archive, and the data-source overrides

Admin → Packs → **Deploy an archive** and **Data source** ([OPERATIONALISING.md, section 17](../guides/OPERATIONALISING.md#17-deploy-from-admin--packs-change-a-data-source-history-and-roll-back)).
An uploaded archive (the pack only, never data) is checked, previewed against the running version, swapped into `drishti.packs.installed-dir` and loaded with the in-place restart.

| Key | Default | Meaning |
|---|---|---|
| `drishti.packs.settings-dir` | `./data/packs/settings` (`DRISHTI_PACKS_SETTINGS`) | One `<pack>.yaml` per pack: the administrator's data-source override (connector `enabled` and `settings`), written by Admin → Packs → Data source and merged over the pack's own connector settings at every load. A redeploy of the pack keeps it. **Precedence, highest first: the site (environment variables and `application.yaml`, e.g. `DRISHTI_SOURCES_CONNECTORS_<CONNECTOR>_SETTINGS_<KEY>`), the override file, the pack.** Credentials may only be `${ENV_NAME}` references. |
| `drishti.packs.deploy.max-archive-mb` | `50` | The largest archive accepted (HTTP 413 above it, decided from the declared length before the body is read). The console's own limit is `packs.deploy_max_mb` in its `application.yaml`; keep it at or under this. |
| `drishti.packs.deploy.max-unpacked-mb` / `max-files` | `200` / `10000` | What an archive may unpack to (a zip bomb stops here). |
| `drishti.packs.deploy.keep-versions` | `5` | Previous versions kept per pack under `<installed-dir>/.previous/<pack>/` for a rollback; older ones are deleted. |
| `drishti.packs.deploy.require-signature` | `false` | `true`: only archives signed by a publisher in `drishti.packs.registry.trusted-keys` (Ed25519 over the archive bytes; headers `X-Drishti-Signature`, `X-Drishti-Publisher`). Unsigned archives are otherwise accepted with a NOTE. |
| `drishti.packs.deploy.require-manifest` | `true` | An archive must carry `MANIFEST.json` (`drishti.py pack bundle` writes it) so every file's checksum is checked. |
| `drishti.packs.deploy.staging-minutes` | `30` | How long a verified upload waits for its confirmation before it is discarded. |
| `drishti.packs.deploy.history-file` | `./data/packs/deploy-history.jsonl` (`DRISHTI_PACKS_DEPLOY_HISTORY`) | Every deployment, rollback and reverted attempt, one JSON object per line. |
| `drishti.packs.deploy.probe-dates` / `probe-timeout-seconds` | `3` / `30` | Test connection: business dates listed per kind, and how long to wait for one source. |

The token scope that opens these writes to a personal API token is `packs:admin` (`drishti.security.token-scopes`); the user still needs the `admin` role.

### `drishti.rachana` — Sutras

| Key | Default | Meaning |
|---|---|---|
| `dirs` | `./sutras` (`DRISHTI_SUTRAS`) | Site Sutra directories, scanned recursively for `*.sutra.yaml`, in addition to the packs' Sutras (any other `.yaml`, `.yml` or `.sutra.md` file there is reported as `DRS-2004`). Studio saves into the first one, as `<domain>/<name>.v<N>.sutra.yaml`. Several: `DRISHTI_SUTRAS=/srv/sutras,/srv/more`. |
| `hot-reload` | `true` | Watch the directories; an edited Sutra is used by the next view. An invalid edit keeps the last good version. |
| `reload-debounce` | `250ms` | Quiet time after a burst of file events before one reload. |
| `watch` | `auto` (`DRISHTI_SUTRA_WATCH`) | How hot reload notices edits. `auto` uses the operating system's file events and, when they are not available (Linux: *User limit of inotify watches reached*), polls instead; `poll` always polls (network file systems, where file events are unreliable). Admin → Health shows `hotReload: WATCHING` or `POLLING`; both pick up edits. |
| `poll-interval` | `2s` | How often polling looks at the Sutra files (paths, sizes, modification times). |
| `formats-file` | none | A site file that overrides or adds named formats (on top of the packs' `config/formats.yaml`). |
| `expression-cache-size` | `10000` | Compiled Rachana-EL expressions kept in memory. |
| `max-expression-depth` | `200` | The deepest a Rachana-EL expression may nest (parentheses, calls, and each operand of a chain such as `a + b + c`). Deeper is `DRS-2101`: a Sutra is not loaded, an alert rule or search condition is refused. It keeps parsing and evaluation within the stack; raise it only for a real need. Applies to Sutras, alert rules, search conditions, history paths and derived kinds. |
| `max-expression-length` | `10000` | The longest a Rachana-EL expression may be, in characters. Longer is `DRS-2101`. |
| `studio-save` | `false` (`DRISHTI_STUDIO_SAVE`) | Let Sutra Studio save files (authors only). Turn on in authoring environments. With it off, a save returns 403 `saving from Studio is disabled (drishti.rachana.studio-save)`. |

### `drishti.governance` — reviewing Sutra changes

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_SUTRA_REVIEW`) | A Studio save becomes a proposal; an approver (a role with `approve`, or an admin) makes it live. Off: a save goes live at once. |
| `four-eyes` | `true` (`DRISHTI_SUTRA_FOUR_EYES`) | Nobody approves their own proposal. Applies only with security on (with security off everyone is the same anonymous user). |
| `dir` | `./data/governance` (`DRISHTI_GOVERNANCE_DIR`) | Where proposals and their history are kept. Back it up. |

Check: `curl -s localhost:18480/api/v1/studio/settings` returns `{"approve":…,"review":…,"save":…}`.

### `drishti.branding` — the product's name and notices

| Key | Default | Meaning |
|---|---|---|
| `product` | `Drishti` (`DRISHTI_PRODUCT`) | The product name in the About answer and messages. |
| `tagline` | `Any data. Any domain. One grammar.` | The line under the name. |
| `owner` | `Ashutosh Sinha` | The owner named in the About answer. |
| `copyright` | the Drishti copyright line | Returned by `GET /api/v1/about`. |
| `notice` | `Unauthorised copying, use or distribution is prohibited.` | The legal notice beside it (also in the public `/public/about`). |

The console has its own copy under `ui:` (`product`, `tagline`, `copyright`, `notice`; see [`ui`](#ui)): set both when you rebrand.

### `drishti.identity` — users, passwords and the identity database

Users, roles, saved workspaces and the audit trail live in one database (SQLite by default, PostgreSQL for several
servers). The environment variables of this section are in the [placeholder table](#every-placeholder-in-the-servers-applicationyaml).

| Key | Default | Meaning |
|---|---|---|
| `database-url` / `database-user` / `database-password` | `jdbc:sqlite:./data/identity/drishti.db` / empty / empty (`DRISHTI_IDENTITY_DB_URL`, `_USER`, `_PASSWORD`) | The database; `jdbc:postgresql://host:5432/drishti` for PostgreSQL. |
| `database-pool-size` | `8` | Connections in the pool (SQLite allows one writer at a time whatever the size). |
| `refresh-seconds` | `15` | How soon servers sharing one database see each other's role and pack changes. |
| `iterations` | `240000` | PBKDF2-HMAC-SHA256 iterations for new password hashes. |
| `min-password-length` | `10` | The shortest password accepted. |
| `max-failed-attempts` / `lockout` | `5` / `15m` | Failed sign-ins before an account locks, and for how long. |
| `force-password-change-on-create` / `-on-reset` | `false` / `false` (`DRISHTI_FORCE_PW_CHANGE_ON_CREATE`, `_ON_RESET`) | Require a new password at the first sign-in of a new user, or after an administrator's reset. |
| `seed-admin` | `true` (`DRISHTI_SEED_ADMIN`) | On first start with no users, create the development administrator. `false` in production. |
| `seed-username` / `seed-password` | `drishti-dev-admin` / the built-in development password (`DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD`) | Its sign-in. Change the password at once; the console warns until you do. |
| `seed-roles` / `seed-display-name` | `[admin]` / `Drishti dev admin` | Its roles and the name shown for it. |
| `users-file` / `audit-file` / `preferences-dir` | `./data/identity/users.json` / `./data/identity/audit.jsonl` / `./data/identity/preferences` | The files of releases before 1.10, imported once into an empty database and renamed `*.imported`. |

### `drishti.security` — tokens, roles, field masks

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_SECURITY_ENABLED`) | Require a signed bearer token (HS256) on every `/api/**` call. Off is for local development only. |
| `secret` | empty (`DRISHTI_TOKEN_SECRET`) | The HS256 key, shared with the console's `auth.token_secret`. At least 32 bytes when security is on. |
| `clock-skew` | `30s` | Tolerated clock difference when checking a token's expiry. |
| `roles.<role>.kinds` | see below | Entity kinds the role may open; `"*"` means all. |
| `roles.<role>.raw` | `false` | Sees every field: nothing in `redact` is masked for the role, anywhere. |
| `roles.<role>.author` | `false` | May save Sutras from Studio (a proposal when governance is on). |
| `roles.<role>.approve` | `false` | May approve or reject proposed Sutras. |
| `roles.<role>.admin` | `false` | May manage users, read the audit log, approve Sutras. |
| `roles.<role>.calc` | `false` | May use Calc, Python in the browser on what the role opens ([PYTHON_CALC.md](../guides/PYTHON_CALC.md#9-roles-who-may-use-calc)). |
| `roles.<role>.layout` | `true` | May customise layouts: layout mode (`Alt+L`) and personal layouts ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)). On unless set to `false`; the bundled `viewer` sets it to `false`. |
| `roles.<role>.collaborate` | `true` | May share, comment and mention ([COLLABORATION.md](../architecture/COLLABORATION.md)). On unless set to `false`; a role saved by an earlier release keeps it without a migration. |
| `roles.<role>.compliance` | `false` | May read any share, place and release legal holds, export the collaboration record and verify hash chains (`/admin/collab/holds`, `/exports`, `/verify`). Not implied by `admin`: grant it to the compliance role. Moderation, retention runs and permanent removal are `admin`. |
| `registered-users-only` | `true` | A signed bearer token is refused (`401`) when its user was deleted or disabled in the identity store, so a token no longer outlives the account; the console's own service identity is exempt. `false` trusts the token alone. |
| `mask-copies` | `false` | Opt-in (`DRISHTI_MASK_COPIES`): after masking, exact copies of a masked field's value inside the other text of the same document also read `•••` ("Captured by J. Smith" with `trader` masked). Exact and case-sensitive; best effort. |
| `mask-copies-min-length` | `3` | Shortest text value scrubbed as a copy; numbers are scrubbed from four digits. |
| `mask-copies-max-nodes` | `50000` | A document with more nodes is not scanned for copies (its masked fields are still masked). |
| `mask-copies-max-values` | `64` | Most distinct masked values scrubbed per document. |
| `redact` | `[trader, counterpartyId, patientName, lifecycle.timeline.description]` | Field names masked for roles without `raw`, on every path that shows or reads their values ([field masks](#field-masks)). |

The bundled roles; packs add domain roles (finance: `trader`, `risk`; logistics: `ops`):

```yaml
drishti:
  security:
    roles:
      viewer:   { kinds: ["*"], layout: false }
      author:   { kinds: ["*"], raw: true, author: true, calc: true }
      approver: { kinds: ["*"], raw: true, author: true, approve: true, calc: true }
      admin:    { kinds: ["*"], raw: true, author: true, admin: true, calc: true }
```

Example: a role that sees only trades and books, with no raw access.

```yaml
drishti:
  security:
    roles:
      sales: { kinds: [trade, book] }
```

#### Field masks

For a role without `raw`, every field named in `redact` reads `•••` wherever the user could see or infer its value.
An entry with dots (`lifecycle.timeline.description`) masks a field by the end of its path; arrays are not a step.
A name matches at any depth (`trader` masks `$.trader` and `$.confirmation.trader`), and the mask covers everything
under the field (with `counterparty` listed, `counterparty.name` and `counterparty.id` read `•••` too). The masking is
done once, on the server, before anything reads the document, so it is the same on every path:

- raw JSON (`F9`), search rows, search CSV, compare, history and its diff, Calc's columns and documents;
- pivots: a masked field groups under `•••` and is never added up;
- views: the header strip, the title, tables and their totals, key/value panels, tabs, charts, links and function keys,
  the panel records behind the Pivot tab, and any value a Sutra computes from a masked field (`fmt($.mtm, …)`,
  `$.mtm / 1e6`, a link built from a masked id); a chart leaves masked points out;
- the live stream, monitors, the console's panel CSV export and Sutra Studio previews;
- Impact (`F8`): an entity tied to the analysed one only through a masked field is not listed (a search on that field
  finds nothing either); a masked measure and the total of a group that holds one read `•••`;
- the type-ahead: entities are described from their view title as the user sees it, and a masked value is neither
  shown nor matched; phrase search names no values of a masked field;
- alert rules: a rule sees the document as its owner may (owner's current roles), so a rule on a masked field never
  fires and a message shows `•••`.

A masked field cannot be probed: a condition on it is never true (`mtm > 0` and `!(mtm > 0)` alike), ordering by it
does not order, and the type-ahead does not match it. A mask hides the field it names, nothing else: a value the
document also holds under another name (cash-flow PVs that add up to a masked MTM, say) stays visible unless that field
is listed too.

### `drishti.security.oidc` — single sign-on

The console runs the browser sign-in flow; the server verifies the provider's ID token itself (signature,
issuer, audience, expiry, nonce). Both halves must be configured (see [console `auth.oidc`](#auth)).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_OIDC_ENABLED`) | Accept sign-ins from the provider. |
| `issuer` | empty (`DRISHTI_OIDC_ISSUER`) | The provider's issuer URL; its discovery document is `<issuer>/.well-known/openid-configuration`. A trailing `/` is removed. |
| `client-id` | empty (`DRISHTI_OIDC_CLIENT_ID`) | This application's client id (the ID token's audience). |
| `jwks-uri` | discovered | The provider's key set, when it should not be discovered. |
| `username-claim` | `preferred_username` | The claim that becomes the Drishti user name (`email` is common). |
| `display-claim` | `name` | The claim shown as the user's name. |
| `groups-claim` | `groups` | The claim listing the user's groups. A dotted path works (`realm_access.roles`). |
| `role-map` | `{}` | Provider group to Drishti roles. A role the map names that `drishti.security.roles` does not define is logged as a warning. |
| `default-roles` | `[]` | Roles for a user none of whose groups map. Empty refuses such users. |
| `roles-from-provider` | `true` | The provider's groups replace the user's roles at every sign-in (false: only at first sign-in). |
| `algorithms` | `[RS256, ES256]` | Accepted signature algorithms. Only `RS256/384/512`, `PS256/384/512`, `ES256/384/512` are allowed; anything else stops start-up. |
| `clock-skew` | `60s` | Tolerance for `exp`, `iat` and `nbf`. |

Example (Keycloak):

```yaml
drishti:
  security:
    oidc:
      enabled: true
      issuer: https://login.bank.example/realms/staff
      client-id: drishti
      groups-claim: realm_access.roles
      role-map:
        desk-rates: [trader]
        market-risk: [risk]
      default-roles: [viewer]
```

### Identity

Users, passwords, lockout, the audit log and the identity database are configured under
`drishti.identity.*`. See [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

### `drishti.engine` — the view pipeline

| Key | Default | Meaning |
|---|---|---|
| `layout-cache-size` | `10000` | Effective layouts kept, one per (Sutra version, kind, data shape). |
| `fingerprint-cache-size` | `100000` | Data-shape fingerprints kept, one per (entity, generation). |
| `bind-parallelism` | `0` (one thread per CPU core) | Threads binding panels. `0` or a negative value means one per core. |

Raise the cache sizes when many distinct entities are viewed and the cache hit rates in **Admin → Health** are
low. See [PERFORMANCE.md](PERFORMANCE.md).

### `drishti.explain` — About this page

`GET /api/v1/views/{kind}/{id}/explain` answers the page's **?** and F1 drawer: where the data came from, why the page
looks the way it does, and the pack's text about it ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md)). It is derived
only when asked, by the same code as the view, for the caller (so masks and rights hold), and kept a short while.

| Key | Default | Meaning |
|---|---|---|
| `cache-size` | `2000` | Answers kept, one per user, page, business date and generation. |
| `cache-ttl` | `60s` | How long an answer is served before it is derived again. **Admin → Health → purge** of the `engine` caches clears it too. |

#### `drishti.explain.ask` — Ask about this page (optional, off by default)

A question box in the drawer that answers from the page's own explanation with a language model you run or rent. **Nothing is sent
anywhere unless you turn it on**, and then only for the packs you list. The server makes the call (the console is a proxy), the key
comes from the environment only, and the model gets no tools. See [the privacy note](OPERATIONS.md#ask-about-this-page-and-your-data).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_ASK_ENABLED`) | The global switch. While `false` no network call is ever attempted and `POST …/ask` answers `404 DRS-4007`. |
| `packs` | `[]` | The packs where the box is drawn (per-pack opt-in); empty means none. `DRISHTI_EXPLAIN_ASK_PACKS=market-risk,genomics`. |
| `endpoint` | empty (`DRISHTI_ASK_ENDPOINT`) | The model's URL: an OpenAI-compatible chat completions URL, or an Anthropic Messages URL. |
| `api` | `openai` | `openai` or `anthropic`: the request and response shape. |
| `model` | empty (`DRISHTI_ASK_MODEL`) | The model name sent to the endpoint. |
| `api-key` | empty (`DRISHTI_ASK_KEY`) | Sent as `Authorization: Bearer` (`openai`) or `x-api-key` (`anthropic`). Environment only; never logged or returned. |
| `values` | `labels-only` | `labels-only` sends labels, definitions and structure, never a rendered value of the page. `shown` also sends the page's rendered sentence (its figures, as the asking user may see them). A value masked for the user is never sent in either mode. |
| `max-question-chars` | `500` | Longer questions are refused (`400`). |
| `max-prompt-kb` | `24` | The whole prompt; the pack guide text is cut first, then glossary entries. |
| `max-guide-chars` | `6000` | The most of the pack's guide section put in the prompt. |
| `max-answer-tokens` / `max-answer-chars` | `400` / `2000` | The model's output cap and the cut applied to what comes back. |
| `timeout` | `15s` | Slower than this is `504 DRS-4008`. |
| `per-user-per-minute` / `per-user-per-day` | `6` / `100` | Over either is `429 DRS-4009`. A call that fails (the model errors or times out) still uses one of the allowance. |
| `log-questions` | `true` | The question text goes to the [access log](OPERATIONS.md#9b-the-access-log) (action `ask`, with the outcome). `false` records only its length. The answer is never stored. |
| `anthropic-version` | `2023-06-01` | The `anthropic-version` header sent when `api: anthropic`. |
| `log-answers` | `false` | `true` also writes the answer text to the server log (`drishti.ask`). |

### `drishti.about` — the packs' About text

Each pack may carry `config/about.yaml`: what a kind means, and notes per panel, written with Sutra expressions over
the page's own values ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md#pack-schema-additions)).

| Key | Default | Meaning |
|---|---|---|
| `max-text` | `600` | Longest About text a pack may write for one entry, in characters; a longer one is a load problem (`DRS-204x`). |
| `max-rendered` | `1000` | Longest text after its expressions are filled in; the rest is cut with `…`. |
| `packs` | set by the server | The packs' About files and their `extends` lineage, written by the pack loader. Do not set it. |

### `drishti.builder` — the Screen Builder's shape extractor

`POST /api/v1/builder/shape` merges sample JSON documents into one JSON Schema with roles. Input over a limit is refused
with `413 DRS-5005` before it is all parsed.

| Key | Default | Meaning |
|---|---|---|
| `max-samples` | `50` | Documents in one request. |
| `max-file-mb` | `5` | Largest single document, in megabytes. |
| `max-total-mb` | `25` | Largest request body, in megabytes. A body over it is answered `413` `DRS-5005` with its size and the limit before it is read (a chunked body: on overflow), by the `drishti.http.request-limits` rule `/api/v1/builder/` = this value; `server.tomcat.max-swallow-size` (`DRISHTI_MAX_SWALLOW`, 64MB) lets Tomcat drain the rest so the client reads the answer, not a reset. |
| `max-depth` | `64` | Deepest nesting of one document. |
| `enum-max-distinct` | `12` | Text with at most this many distinct values is an `enum` ... |
| `enum-min-seen` | `3` | ... when it was seen at least this many times and some value repeats. |
| `examples` | `3` | Example values per path in the report (masked values stay masked). |
| `rare-below` | `0.5` | A field present in fewer than this share of its records is listed as rare; auto-design also leaves such a field out of the strip and the Details panel. |
| `prune-share` | `0.5` | Auto-design (`POST /builder/design`): a panel or strip figure empty or in error for more than this share of the samples is dropped, or demoted to its first alternative. |
| `strip-max` | `6` | Most figures in a drafted strip. |
| `max-panels`, `max-side-panels` | `16`, `4` | Most panels auto-design puts in the main and the right column (the links panel is extra); the rest are dropped and listed. |
| `line-min-points` | `5` | Dated rows shorter than this are drafted as a ladder, not a line. |
| `max-alternatives` | `2` | Runner-up kinds kept with each drafted panel. |
| `sample-max-items` | `50` | The synthetic sampler (a Design started from a JSON Schema) generates arrays up to this long; a schema asking for more (`minItems: 2000000000`) is clamped and the answer lists a problem. Nothing beyond the limits is allocated. |
| `sample-max-string` | `1000` | ... strings up to this many characters. |
| `sample-max-depth` | `8` | ... nesting up to this deep. |
| `sample-max-kb` | `256` | ... and at most this many kilobytes in one generated document. |
| `code-names` | `year, version, seq, sequence, no, num, number, code, zip, postcode` | Last word of a whole-number field's name that makes it a code (a label, not an amount: not summed, no thousands separator). |
| `fraction-names` | `utilisation, utilization, usage, used, ratio, share, coverage, fill, pct, percent` | Words in the name of a number between 0 and 1 that make auto-design draft a gauge of 1. |
| `limit-names` | `limit, max, cap, threshold, budget, capacity` | Field names that hold a limit for a measure beside them (a gauge's maximum). |
| `status-words`, `status-names`, `id-names`, `label-names`, `ohlc-names`, `graph-node-names`, `graph-edge-names`, `long-text-chars` | built in | The vocabulary the role rules read: values that are states, field names that hold a state or an id, names of a row's label, the four candle fields, the lists of a graph, the length of prose. Set one to replace its list. |

### `drishti.builder.designs` — the Build workbench's Designs

A Design (`/api/v1/builder/designs`, **Build → My designs**) keeps a user's samples, Sutra and notes on the server, so work
survives a console restart and shows on every console. Only its owner can reach it (anyone else gets `404 DRS-5006`).
Sample documents are never logged; deleting a Design deletes its samples at once. A limit answers `413 DRS-5005`.

| Key | Default | Meaning |
|---|---|---|
| `store` | `file` | `file`: `data/designs/<user>/<id>/design.json` and `samples/`, created readable by the server's account only (`rwx------`). `jpa`: the identity database (`drishti_design`, `drishti_design_sample`), for several server replicas. |
| `dir` | `./data/designs` | The file store's directory. |
| `max-per-user` | `50` | Named Designs one user keeps. Scratch (unnamed) Designs do not count toward it. |
| `max-samples` | `50` | Samples in one Design. |
| `max-mb` | `25` | Megabytes in one Design: its sample documents, Sutra, notes and tests (a reference to a stored entity counts nothing). |
| `max-user-mb` | `250` | Megabytes of samples, Sutra, notes and tests across one user's Designs. |
| `max-sutra-kb` | `1024` | Largest Sutra text of one Design (`413 DRS-5005` beyond it). |
| `max-notes-kb` | `256` | Largest notes text. |
| `max-tests-kb` | `1024` | Largest tests (the JSON of the list). |
| `max-scratch` | `10` | Scratch (unnamed) Designs one user keeps. Opening an example, a Help link or a `/studio` address makes one; when the cap is reached the oldest scratch Design is deleted to make room. **My designs** has *Delete scratch designs* (`DELETE /api/v1/builder/designs?scratch=true`). |
| `scratch-ttl` | `1d` | An unnamed (scratch) Design is deleted this long after it was last touched. |
| `named-ttl` | `90d` | A named Design is deleted this long after it was last touched (an edit, or opening it, touches it). |
| `warn-after` | `75d` | A named Design untouched this long is listed with a warning that it will expire. |
| `sweep-interval` | `1h` | How often expired Designs are deleted. |
| `max-ops` | `100` | Steps of the operation log a Design keeps for undo and redo (the oldest are dropped). |

### `drishti.builder.file-binding` and `dev-dir` — development file binding

| Key | Default | Meaning |
|---|---|---|
| `drishti.builder.file-binding` (`DRISHTI_BUILDER_FILE_BINDING`) | `false` | Let a Design be bound to a file in the author's **own development folder** (**Ship -> Bind to a file** in the workbench; `POST /api/v1/builder/designs/{id}/bind`). Saving writes that file and an edit made in an IDE is read back (`POST .../sync`). Needs the author right and `drishti.rachana.studio-save`. The folder is never one the registry loads, so **Save to file never makes anything live**: going live is always Ship -> Propose, then an approver. With it off every bind answers `403 DRS-5002`. |
| `drishti.builder.dev-dir` (`DRISHTI_BUILDER_DEV_DIR`) | `./dev-sutras` | Where bound files live, one folder per user (`<dev-dir>/<user>/`). It must lie **outside** `drishti.rachana.dirs` (otherwise a saved file would be hot-reloaded and go live without review; binding then answers `403`). Names that are symbolic links, or go through one, are refused; a file is written through a new temporary file renamed into place. Only administrators are shown the absolute path (`GET /builder/designs/binding`). |

For development servers: leave binding off on authoring and production servers.

Read-only share links (`POST /api/v1/builder/designs/{id}/share`) need no setting: the link shows a Design's Sutra, operations and
sample names, never sample contents, and its owner can revoke it. Proposals made from the workbench carry the check matrix, the
sample names and the notes to the reviewer (`GET /api/v1/sutras/proposals/{id}` returns them as `evidence`).

### `drishti.commands` — the command line

| Key | Default | Meaning |
|---|---|---|
| `mnemonics.<CODE>.kind` / `.label` | from packs | A mnemonic (`TRD`) opens this kind; the label shows in suggestions. A site entry overrides a pack's. |
| `suggest-limit` | `25` (`DRISHTI_SUGGEST_LIMIT`) | Entries in the command line's dropdown; the list scrolls. This default is not capped; a request's own `limit` is, at 50. |
| `suggest-budget` | `30ms` | Type-ahead answers with what the sources returned by then. |
| `recent-size` | `20` | Recently opened entities remembered per user. |

Example: add a site mnemonic.

```yaml
drishti:
  commands:
    mnemonics:
      SHP: { kind: shipment, label: Shipment }
```

### `drishti.search` — structured search

Not in the bundled file; the defaults apply until you set them.

| Key | Default | Meaning |
|---|---|---|
| `max-scan` | `20000` | At most this many entities of a kind are read per search; the result says when it stopped short. |
| `budget` | `3s` | Time a search may take to list and read; slower sources are left out. |
| `columns.<kind>` | from packs | A kind's key fields in pick lists and searches (`columns:` in `pack.yaml`). |
| `pivot.<kind>` | from packs | A Pivot tab on the kind's results: `true`, or the pivot as a JSON string (`pivot:` in `pack.yaml`, [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#pivot-a-pivot-tab-on-search-results)). |

### `drishti.calc` — Python in the browser

Calc ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)) runs Python in the user's browser; the server never runs the code. It
decides who may use it (roles with `calc`), keeps each user's snippets, and serves whole columns of a day.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_CALC_ENABLED`) | Calc at all. Off: nobody may use it, and its endpoints refuse with `403 DRS-5002`. The console has its own switch, `calc.enabled`, read from the same variable. |
| `max-column-rows` | `250000` | `drishti.columns()` (`GET /api/v1/search/columns/{kind}`) returns at most this many entities, the first by id; the browser holds them all. |
| `columns-budget` | `20s` | How long a columns read may wait for its source (the first read of a business day loads its columns). |
| `max-snippet-chars` | `50000` | A saved snippet's code, at most (never more than 60,000: a saved document is at most 64 KB). Each user keeps at most 50 snippets. |

Which views offer Calc is a pack's choice (`python: { enabled: true }` in `pack.yaml`), not configuration; see
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#calc-python-snippets).

### `drishti.layouts` — personal layouts

Layout mode ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)): each user's arrangement of
a Sutra's panels, kept per user and Sutra in the preference store (namespace `layouts`, at most 50 per user) and
applied by the console when it draws that user's views. Who may is a role power (`roles.<role>.layout`, above).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_LAYOUTS_ENABLED`) | Layout mode at all. Off: nobody may keep a layout, `/api/v1/me/layouts/**` refuses with `403 DRS-5002`, and `GET /api/v1/me/layouts` says `enabled: false`. The console has its own switch, `layouts.enabled`, read from the same variable. |

Promoting a layout to a Sutra also needs `drishti.rachana.studio-save: true` and a role with `author`; it is reviewed
as any Studio save (`drishti.governance`).

### `drishti.pivot` — the Pivot tab

The Pivot tab ([USER_GUIDE.md](../guides/USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)) appears only where a
Sutra (`pivot:` on a table or ladder) or a pack (`pivot:` beside a kind's `columns:`) opts in; these keys bound it.
There is no role power: anyone who may open a kind may pivot and save; promoting a pivot to a Sutra needs `author`,
`drishti.rachana.studio-save` and goes through review. Saved pivots live in the preference store (namespace `pivots`).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_PIVOT_ENABLED`) | Off: no Pivot tab anywhere (the view's table data and search results no longer carry `pivot`), and `/api/v1/views/…/records`, `/api/v1/search/pivot/**` and `/api/v1/me/pivots/**` refuse. |
| `max-records` | `50000` | Rows of one panel sent to the browser to pivot, whatever the table's `limit`; beyond it the pivot says *the first 50000 of N rows*. |
| `max-row-keys` | `2000` | Innermost row groups a pivot shows; further groups are left out of the grid but counted in the totals (`moreRows`). |
| `max-column-keys` | `200` | Column groups a pivot shows (`moreColumns`). |
| `document-scan` | `20000` | Entities a search pivot reads as documents, when a field is not kept as a column and the user asks for a document read; more makes the result partial. |
| `drill-page` | `200` | Underlying entities one drill-down page of a search pivot returns, at most. |
| `budget` | `20s` | How long a search pivot waits for a business day's columns (the first read of a day loads them) or for its documents. |

### `drishti.panels` — chart and aggregate panel limits

Not in the bundled file; the defaults apply until you set them. They bound how much of one document the
`histogram`, `pivot`, `scatter`, `candlestick`, `waterfall`, `graph` and `timeline` panels read, so a very long list
cannot make a view slow or a page heavy. A panel that stops short says so (*N more*, *N left out*). See
[PANEL_KINDS.md](../guides/PANEL_KINDS.md#how-a-panel-behaves).

| Key | Default | Meaning |
|---|---|---|
| `max-values` | `100000` | Numbers a histogram bins, and rows a pivot aggregates. |
| `max-points` | `5000` | Points a scatter draws, bars a waterfall draws, and bars a candlestick keeps (the latest). |
| `max-nodes` | `300` | Nodes a graph draws; edges are capped at twice this. |
| `max-events` | `500` | Events a timeline lists (the latest). |
| `pivot-rows` | `200` | Row keys a pivot shows; the rest are counted under the table. |
| `pivot-columns` | `40` | Column keys a pivot shows; values under further keys still count in the row totals. |
| `tree-depth` | `12` | Levels a table or ladder with `children` descends; deeper rows are left out. |
| `tree-rows` | `10000` | Rows in all (every level) one such table builds; further rows are left out. |

### `drishti.alerts` — alert history

| Key | Default | Meaning |
|---|---|---|
| `keep` | `1000` | Fired alerts kept per user, in the identity database (`drishti_alert`), so they survive restarts. Older ones are pruned as new ones arrive. |

### `drishti.access-log` — who looked at what

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_ACCESS_LOG`) | Record every answered view, raw document, history read, search and CSV export in `drishti_access` (Admin → Access). |
| `keep-days` | `90` | Older events are pruned once a day. |
| `queue` | `100000` | Events waiting to be written. Recording never slows a read: events are written in batches every second, and when the queue is full they are dropped and counted (Admin → Access says how many). |

### `drishti.collab` — share with a note, and the inbox

Design: [COLLABORATION.md](../architecture/COLLABORATION.md). A person writes a note on a view, picks people and roles from the
directory, and Drishti notifies them in the app (always) and by email (off until configured); the link reopens the view
pinned to the same business date and "known at" instant, with the **recipient's** rights. Shares, recipients and inbox rows are in
the identity database (`drishti_share`, `drishti_share_recipient`, `drishti_inbox`, `drishti_outbox`, the thread, comment, revision, mention
and follow tables, `drishti_collab_hold`; back them up with the rest of the identity database, [OPERATIONS.md](OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export)). Who may share is the role power `roles.<role>.collaborate`; who may read any share is `compliance` (see
[`drishti.security`](#drishtisecurity--tokens-roles-field-masks)).

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_COLLAB_ENABLED`) | Everything below. Off: every collaboration endpoint answers `403 DRS-7004`, and `GET /api/v1/collab` says `enabled: false`. |
| `store` | `jpa` | Where shares and inbox rows are kept: `jpa` (the identity database, SQLite or PostgreSQL) or `file` (`dir`, one server only). The server refuses to start with `file` while `drishti.identity.database-url` is PostgreSQL. |
| `dir` | `./data/collab` (`DRISHTI_COLLAB_DIR`) | The file store's folder, and exports. |
| `console-url` | empty (`DRISHTI_CONSOLE_URL`) | The console's address; a share's `link` is this plus `/share/sh_…` (relative when empty). Email stays off while it is empty. |
| `directory.scope` | `shared-packs` | `shared-packs`: the picker lists only users who share at least one assigned pack with the caller (and roles by those people); `all`: every enabled user, for small firms. |
| `directory.min-query` | `2` | Fewest characters a directory search needs. |
| `directory.limit` | `10` | Most entries one search returns. |
| `mentionable-roles` | `["*"]` | Roles usable as groups (`*` for all); `admin` and `service` never are. |
| `max-group-size` | `200` | Largest role a share may expand to. |
| `share.max-recipients` | `25` | Names (people and roles) in one share. |
| `share.max-expanded` | `200` | People once roles are expanded. |
| `share.max-text` | `2000` | Longest note, in characters. |
| `share.undeliverable` | `tell` | `tell`: the sender learns who was not notified and why (they learn a colleague lacks a right, never data); `silent`: they do not. |
| `share.post-to-thread` | `false` | The default for also posting the note to the discussion (a thread with the note as its first comment, by the sender); `GET /api/v1/collab` reports it. The console's share dialog has a tick *Also start a discussion on the view*, ticked on opening when this key is `true`. The server applies the key when a share request does not say: a request that sets `postToThread` (true or false) always wins. |
| `threads.max-text`, `threads.max-per-entity`, `threads.edit-window`, `threads.page-size` | `4000`, `500`, `15m`, `50` | Comment thread limits: longest comment (characters), threads per entity, how long after writing the author may edit (after it, retract only; every revision is kept), and comments per page. |
| `text.on-masked-copy` | `warn` | A masked field's value typed into a note: `warn` tells the sender, `reject` refuses it (`DRS-7011`), `allow` says nothing. Whatever is chosen, the value reads `•••` for every reader without `raw` except its author, who reads their own text as written. **A sender without `raw` is never warned or refused** (a signal would let them test guesses of a hidden value): each such hit is written to the audit log as `collab.masked-copy`. Only the document's own masked values are found, whole, after normalising (case, spacing, zero-width characters, NFKC, common Greek and Cyrillic look-alikes); a part of a value, or a look-alike outside that table, is not found; use `text.deny-patterns` for values known from elsewhere. |
| `text.deny-patterns` | `[]` | Regular expressions refused in notes and comments (for example a medical record number format). |
| `limits.shares-per-minute` | `5` | Shares one user sends a minute (`429 DRS-7003`, with `Retry-After`). |
| `limits.shares-per-day` | `100` | Shares one user sends in 24 hours (counted in the store, so it survives restarts and holds across servers). |
| `limits.comments-per-minute` | `10` | Comments (and share replies) one user writes a minute (`429 DRS-7003`). |
| `limits.directory-per-minute` | `60` | Directory searches one user makes a minute. |
| `limits.mails-per-recipient-per-hour` | `30` | Emails queued for one recipient in an hour; more are not queued (the bell still rings) and a warning is logged. |
| `inbox.keep` | `1000` | Newest inbox rows kept per user. |
| `inbox.keep-days` | `180` | Days an inbox row is kept; older rows are removed every `retention.interval` (and at start). |
| `inbox.poll` | `5s` | How often a server looks for rows another server wrote to the shared database, only while a stream is open, so a notice reaches a browser on any server within seconds. |
| `inbox.coalesce` | `60s` | Several notices to one person about one thread within this long become one email. Applies to thread notices (mentions and replies); a share is always one email. |
| `email.enabled` | `false` | The email channel; also needs `spring.mail.host`, `console-url` and `drishti.security.enabled` (with sign-in off the server refuses to start with this on: identities are not real). Asking for email while it is off is `503 DRS-7012`. See [Email](#email-springmail-and-the-outbox). |
| `email.content` | `comment` | `comment`: the product, the sender, the kind and id, the panel, the date and the note as that recipient may read it: copies of masked values `•••`, and `{$.path}` quotes filled with the value from the recipient's own view (`•••` where their role masks the field). So an email can carry figures; a pack that must not sends `link-only`. `title`: the same without the note. `link-only`: the sender's name and the link only, no value. Per pack: `packs.<pack>.email.content`. |
| `email.from` | `drishti@localhost` (`DRISHTI_MAIL_FROM`) | The From address. |
| `email.coalesce-window` | `10s` | Notices for one recipient inside this window are sent as one **digest** email (template `digest.*`), each notice still checked against the person's opt-outs, rights and masking when it is sent. `0` turns it off: every notice is its own email, sent at once. A lone notice goes out after this short delay (a first-send delay); only notices that arrive for the same person meanwhile join it, so one notice is never held for minutes. |
| `email.templates-dir` | empty | A folder with `share.subject`, `share.txt`, `share.html` (and `mention.*`, `reply.*`, `digest.*`, `test.*`) that replace the built-in ones, file by file. Variables: `${product}`, `${headline}`, `${detail}`, `${link}`; nothing else, and a note is never read as a template. |
| `outbox.enabled` | `true` | This server sends the email outbox; turn it off on servers of a group that should not (the rows are still written, any dispatching server sends them). |
| `outbox.tick`, `outbox.batch`, `outbox.max-attempts`, `outbox.backoff`, `outbox.max-backoff`, `outbox.lease`, `outbox.keep-sent-days` | `2s`, `50`, `8`, `30s`, `1h`, `60s`, `30` | The dispatcher: how often it looks, rows per tick, attempts before a row is dead, the retry delay (doubling to the maximum), how long a claimed row is held, and days a sent row is kept. |
| `retention.keep-days` | `0` | Days shares and threads are kept (a thread by its last activity, a share by its creation); `0` keeps them forever, so no record is destroyed by default. A legal hold always wins. |
| `retention.kinds` | `{}` | Days per entity kind, by kind name in configuration (`{ trade: 2555, gene: 0 }`); beats the pack's days and the default; `0` keeps that kind forever. |
| `packs.<pack>.retention-days` | unset | Days for every kind the pack owns (a number); beats the default. Order, most specific first: `retention.kinds.<kind>`, `packs.<pack>.retention-days`, `retention.keep-days`. |
| `retention.interval` | `24h` | How often the purge runs. It starts only when some retention above 0 is configured, so a default install never runs it. See [OPERATIONS.md](OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export). |
| `export-keep` | `24h` | How long a finished compliance export is kept before it is deleted (it is also deleted when downloaded: once). Files are written under `<dir>/exports`. |
| `packs` | `{}` | Per-pack overrides by pack name, in configuration: `packs.genomics.share-enabled: false` switches sharing off for that pack's kinds (`403 DRS-7004`); `packs.genomics.email.content: link-only` makes email about that pack's kinds carry neither the id nor the note. |
| `bridges.enabled` | `false` | Chat bridges (build step 9): post "comment + link" to Teams, Slack or a signed webhook for the events a route lists. Posts go through the outbox (retries, backoff and dead letters as for email) and never carry a data value. See [OPERATIONS.md](OPERATIONS.md#9a-4-chat-bridges-teams-slack-webhooks). |
| `bridges.render-as` | `viewer` | The role whose view of masked values the posted note follows: the note is written as a person with only that role would read it, so with the bundled `viewer` (no `raw`) a value copied from a masked field reads `•••`. Value quotes (`{$.mtm}`) are filled from that role's view of the document (a masked field stays `•••`), so posts can carry figures; an administrator may name a raw role for a restricted channel, and a `link-only` pack posts only the link. |
| `bridges.allow` | `[]` | URL prefixes a bridge's URL must start with (written out in full, no wildcards: `[https://hooks.slack.com/services/, https://contoso.webhook.office.com/]`). Empty means no bridge can post: configuration alone cannot send data to a host nobody approved. |
| `bridges.per-minute` | `30` | Posts per bridge per minute; the rest wait in the outbox and go a minute later (no attempt is counted). |
| `bridges.timeout` | `10s` | Connect and response time for one post. Redirects are never followed. |
| `bridges.max-note` | `500` | Characters of the note or comment a post carries (cut with an ellipsis). |
| `bridges.webhooks` | `[]` | The bridges, a list of `{name, format, url-env, secret-env, routes}`: `name` (letters, digits, `-`, `_`; it is the outbox recipient), `format` (`json`: signed generic webhook; `teams`: incoming webhook or Workflows; `slack`: incoming webhook), `url-env` (the **environment variable** holding the URL: Teams and Slack URLs are secrets, so the URL is never in configuration), `secret-env` (the variable holding the HMAC key, `json` only), and `routes`: a list of `{packs, kinds, events}` where `events` are `share`, `comment`, `mention` and an empty `packs` or `kinds` means any. A typo in a name, format or event stops the server at start. Example: `[{name: risk-desk, format: teams, url-env: BRIDGE_RISK_DESK_URL, routes: [{packs: [market-risk], events: [share, comment]}]}]`. |
| `snapshots.enabled` | `false` | Watermarked snapshots (build step 10): a share may include a PNG of the view, drawn on the server (Java2D, headless, the JDK's own fonts; no browser, nothing downloaded). Off by default. A pack overrides it: `packs.genomics.snapshots.enabled: false` forbids pictures of that pack's kinds (`403 DRS-7015`); `true` allows them for a pack when the global setting is off. The picture is made for the **most restrictive recipient** (any recipient without `raw` masks it for all; a panel any recipient may not open is left out) and rides the share's page and, under `email.content: comment`, its email as an attachment. |
| `snapshots.width` | `960` | Pixels wide (480 to 2400). |
| `snapshots.max-height` | `1800` | Most pixels tall (200 to 8000); panels that do not fit are replaced by one line saying how many were left out. |
| `snapshots.max-panels`, `snapshots.max-rows` | `8`, `12` | Most panels drawn, and most rows of a table, list or timeline. |
| `snapshots.max-bytes` | `1500000` | Largest PNG; a larger one refuses the share (`503 DRS-7016`). |
| `snapshots.timeout` | `5s` | Longest one drawing may take (`503 DRS-7016`). Two pictures are drawn at a time. |
| `snapshots.cache-size`, `snapshots.cache-ttl` | `50`, `10m` | Views and pictures kept, by view, generation and rights profile (and the share for the picture). |
| `snapshots.recipients` | `count` | What the watermark says of the people: `count` ("3 recipients") or `names` (up to five display names). |

#### Email (`spring.mail`) and the outbox

Email is off until three things are set: `drishti.collab.email.enabled: true`, an SMTP host, and `drishti.collab.console-url` (the links). The
SMTP client is Spring Boot's: the standard `spring.mail.*` keys (or `SPRING_MAIL_HOST` and so on in the environment); no mail client exists while
`spring.mail.host` is unset.

| Key | Default | Meaning |
|---|---|---|
| `spring.mail.host` | unset | The SMTP server. Setting it switches the client on. |
| `spring.mail.port` | `25` (Spring's) | Usually `587`. |
| `spring.mail.username`, `spring.mail.password` | unset | Sign-in to the server, when it needs one; keep the password in the environment, not the file. |
| `spring.mail.properties.mail.smtp.starttls.required` | `false` (Jakarta Mail's) | `true` refuses to send unless the connection is upgraded to TLS. Set it on for any server reached over a network. |
| `spring.mail.properties.mail.smtp.connectiontimeout`, `...smtp.timeout` | none (Jakarta Mail's: wait forever) | Milliseconds to connect and to wait for a reply. Set both (`10000`); otherwise a stalled server holds the dispatcher. |
| `management.health.mail.enabled` | `false` (set by Drishti) | The mail health check is off, so an unreachable mail server never turns `/readyz` red; the outbox retries instead. |

Each queued email is a row in `drishti_outbox` (or `outbox/{pending,sent,dead}/` for the file store) naming the recipient, the template and the share;
the message itself is built when it is sent, for the recipient's rights then. Metrics: `drishti.collab.mail{outcome=sent|retry|dead|cancelled}`
(counters) and `drishti.collab.outbox{state=pending|dead}` (gauges). Operating it: [OPERATIONS.md](OPERATIONS.md#9a-2-collaboration-email-and-the-outbox).
A person chooses which events are emailed under `notify.email.{share,mention,reply}` in `PATCH /api/v1/me/settings` (all default to on).

### `drishti.reports` — scheduled reports

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` (`DRISHTI_REPORTS_ENABLED`) | The scheduler. With several servers on one identity database, turn it on for one only, or a report runs once per server. Reports can always be run by hand. |
| `folder` | `./data/reports` (`DRISHTI_REPORTS_DIR`) | Folder deliveries: `<folder>/<user>/<report>/<report>-<business date>-<HHmm>.csv`, written atomically. |
| `webhooks` | `[]` | URL prefixes a report may post to (`https://hooks.bank.example/drishti/`). Empty: no webhooks. A report cannot post anywhere else, so it cannot be used to reach internal addresses. |
| `per-user` | `20` | Reports a person may keep. |
| `tick` | `30s` | How often the scheduler looks for due reports. |
| `keep-runs` | `20` | Runs remembered per report (time, rows, where it went, or the error). |

### `drishti.live` — live updates

| Key | Default | Meaning |
|---|---|---|
| `frame` | `50ms` | Ticks arriving within one frame are merged; the latest wins. |
| `heartbeat` | `15s` | Interval of the SSE keep-alive comment, so proxies do not close idle streams. Keep it below your proxy's idle timeout. |
| `max-streams` | `20000` | Live streams (views and monitors) the server keeps open at once; further requests are refused. |
| `window` | `30s` | Rolling window for the live latency p99 shown in the top bar. |

See [LIVE.md](../architecture/LIVE.md).

### `drishti.graph` — links between entities

| Key | Default | Meaning |
|---|---|---|
| `id-patterns[]` (`pattern`, `kind`) | from packs | Identifier regex to kind, tried in order, for values whose kind is not stated. A site list replaces the packs' list. |
| `fields.<field>` (`kind`, `label`) | from packs | A document field that references another entity. |
| `badges.<kind>` | from packs | Rachana-EL over the linked document, shown beside the link (`EE 4.1m`). |
| `link-budget` | `40ms` | Linked entities slower than this show as *pending* and fill in later. |
| `impact.follow` | from packs | Reference fields followed upward to find what dependents roll into (impact analysis). |
| `impact.measures.<kind>` | from packs | Rachana-EL summed per group (`$.mtm`). |
| `impact.formats.<kind>` | from packs | The format for that measure. |

Example:

```yaml
drishti:
  graph:
    link-budget: 80ms
    fields:
      carrierId: { kind: carrier, label: Carrier }
    badges:
      carrier: "$.rating"
```

### `drishti.inference` — views for kinds without a Sutra

| Key | Default | Meaning |
|---|---|---|
| `semantics-file` | none | A site file that replaces the bundled semantic hints (`inference/semantics.yaml`). Packs' `config/semantics.yaml` files are tried first. See [INFERENCE.md](../architecture/INFERENCE.md). |

---

## Connector settings, plugin by plugin

These go under `drishti.sources.plugins.<plugin>.settings` (single instance) or
`drishti.sources.connectors.<name>.settings` (named instance). Every plugin takes `source-name`, the name shown
in provenance and health (default: the connector's name, or the plugin's name). Writing a plugin of your own is
described in [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

Every plugin also takes **`stale-after`**: a duration (`15m`, `2h`, `4d`). When the connector has received nothing new
for longer than that, it is *stale*: Admin → Health shows it in amber and turns the overall status `DEGRADED`, and
views of its entities show a banner saying the data may be out of date. Without it a connector is never stale. It
only means something for sources that know when they last got data (Kafka, ActiveMQ, RabbitMQ, Delta, file, feeds,
demo). The packs set it for the trading stream (`DRISHTI_STREAM_STALE_AFTER`, default `15m`) and the daily public
feeds (`4d`, which covers a weekend and a holiday).

```yaml
drishti:
  sources:
    connectors:
      trading-stream:
        settings:
          stale-after: 5m        # trades arrive all day: five quiet minutes means something is wrong
```

### `delta` — Delta Lake

Layout: `<root>/<domain>/<kind>/business_date=yyyy-MM-dd/` holding `(id, doc)` rows.

| Setting | Default | Meaning |
|---|---|---|
| `root` | `./data/delta` (packs use `DRISHTI_DELTA_ROOT`) | Local directory (`C:\lakes\drishti` on Windows), or a URI such as `s3a://bucket/lake`, `s3://…`, `abfs://…`, `gs://…`. |
| `engine` | `DRISHTI_DELTA_ENGINE`, else `native` | Which Delta Kernel engine reads the lake. `native`: no Hadoop; local disk through `java.nio` and S3 (`s3://`, `s3a://`) through the AWS SDK; the one that works on Windows without `winutils.exe`. `hadoop`: Kernel's default engine over Hadoop's file systems; also reads `abfs://`, `gs://` and HDFS. `auto`: `native` on Windows, `hadoop` elsewhere. A `native` connector on an `abfs://` or `gs://` root refuses to start and says to use `hadoop`. Health shows `UP (engine: native)`; the cache stats carry `engine`. See [DELTA_CONNECTOR.md › Engines](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop). |
| `domain` | empty | The data-domain sub-folder (`finance`, `trading`). |
| `kinds` | every table found | Comma list. |
| `mode.<kind>` | `snapshot` | `snapshot`: a full copy every business date, read within `lookback-days`. `effective`: a row only when the entity changes, no limit. |
| `lookback-days` | `10` | How far back a snapshot read looks for the latest partition. |
| `id-column` / `doc-column` / `date-column` | `id` / `doc` / `business_date` | Column names. |
| `refresh-seconds` | `10` | How often each table's latest version is checked. |
| `cache-mb` | `512` | Whole days kept in memory, by size, for small tables that are not laid out. |
| `layout.<kind>.columns` | none | Document paths stored as columns beside the document (see [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#large-kinds-the-lake-layout)); searches, pick lists, derived kinds and impact read them instead of documents. `layout.<kind>.sort-by`, `file-rows`, `row-group-rows` tell the writers how to lay the table out. |
| `id-map-mb` | `1024` | Each day's ids and the file each is in, read from the id column alone, kept by size. |
| `doc-cache-mb` | `256` | Single documents read recently. |
| `columns-cache-mb` | `1024` | A day's promoted columns, read once and kept by size (the newest day is loaded in the background). |
| `max-concurrent-reads` | `16` | Single-document reads at once (each decodes one row group). |
| `max-load-rows` | `200000` | A table without columns has a whole day loaded for reverse lookups only up to this many rows. |
| `warm-dates` | `3` | After start, in the background, each table's newest N dates get their id map and one document read, so the first read of a recent past date is not the slow one. `0` turns it off. |
| `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`, `s3.path-style` | empty, empty, empty, empty, `true` with an endpoint | Shorthands for an `s3a://` (or, native engine, `s3://`) root; both engines read them. Without keys the AWS chain is used (environment, profile, instance role). |
| `s3.read-block-kb` | `1024` | Native engine: the smallest ranged GET (a Parquet footer, a deletion vector); larger reads are one GET of their range. |
| `hadoop.<key>` | none | Passed to Hadoop as `<key>` (any `fs.s3a.*` option); with the native engine, Kernel's options (`delta.kernel.default.parquet.reader.batch-size`, …) are read from here or from `kernel.<key>`. |

```yaml
drishti:
  sources:
    connectors:
      risk-lake:
        plugin: delta
        settings:
          root: s3a://bank-lake/drishti
          domain: risk
          s3.endpoint: https://minio.internal:9000
          s3.access-key: ${LAKE_ACCESS_KEY}
          s3.secret-key: ${LAKE_SECRET_KEY}
```

### `jdbc` — a database

The JDBC driver jar goes on the class path or in `plugin-dir`.

| Setting | Default | Meaning |
|---|---|---|
| `url`, `user`, `password` | empty | Connection. Keep the password in the environment. |
| `pool-size` | `4` | Connections kept. A broken connection is reopened; the connector starts even if the database is down. |
| `query.<kind>` | none | Query mode: SQL with one `?` for the id, or named `:id` and `:asOf` (the business date). The first row is the document: each column a field, or a column named `json` as the whole document; optional `generation` and `business_date` columns. A `json`/`jsonb` column becomes nested data. |
| `query.<kind>.<part>`, `part-shape.<kind>.<part>` | none, `list` | Query mode: more of the entity from other tables; its rows become the field `<part>` (a list, or with `object` the first row). Parts run at once with the entity's query. |
| `ids.<kind>` | none | Query mode: a day's ids (and optionally a title) for type-ahead, read every `refresh-seconds`. |
| `columns.<kind>` | none | Query mode: a day's id and promoted fields (`:asOf`), for searches, pick lists, derived kinds, impact and reverse lookups without documents. |
| `reverse.<kind>` | none | Query mode: the ids of the kind that reference `:target`. All query-mode keys: [JDBC_QUERIES.md](../connectors/JDBC_QUERIES.md). |
| `json-columns` | none | Query mode: comma list of text columns that hold JSON (`legs, extras`), parsed into nested data like a `json`/`jsonb` column; a cell that is not JSON stays text. |
| `table` | empty | Table mode: every kind of a domain in one PostgreSQL table of `(kind, id, business_date, doc jsonb, <promoted columns>)` rows, partitioned by month ([POSTGRES_CONNECTOR.md](../connectors/POSTGRES_CONNECTOR.md)). |
| `mode.<kind>` | `snapshot` | Table mode: `snapshot` or `effective`. |
| `kinds`, `kind-column`, `id-column`, `doc-column`, `date-column`, `lookback-days` | the kinds the table holds, `kind`, `id`, `doc`, `business_date`, `10` | Table mode column names and look-back. |
| `layout.<kind>.columns` | none | Table mode: the paths the pack promotes, each a column of the table, read by searches, pick lists, derived kinds, impact and reverse lookups instead of documents. |
| `refresh-seconds` | `60` | Table mode: how often each kind's dates and the newest day's ids (type-ahead, kept in memory) are re-read. |
| `scan-threads` | `4` | Table mode: id ranges of a day read at once (on as many pooled connections) when reading a day's columns. |
| `columns-cache-mb`, `columns-seconds` | `1024`, `300` | Table mode: memory for days of promoted columns, and how long a day is kept (a new load clears them). |
| `max-load-rows`, `reverse-index` | `200000`, `true` | Table mode: the most documents a reverse lookup reads for a kind without promoted link columns; `false` turns reverse lookups off. |

```yaml
drishti:
  sources:
    plugins:
      jdbc:
        enabled: true
        settings:
          url: jdbc:postgresql://db:5432/trades
          user: drishti
          password: ${TRADES_DB_PASSWORD}
          query.trade: >-
            SELECT json FROM trades WHERE trade_id = :id AND business_date =
            (SELECT MAX(business_date) FROM trades WHERE trade_id = :id AND business_date <= :asOf)
```

### `file` — JSON or CSV files on disk

Layout: `<root>/<kind>/<id>.json` (or `.csv`), and dated `<root>/<yyyy-MM-dd>/<kind>/<id>.json`.

| Setting | Default | Meaning |
|---|---|---|
| `root` | `./data/feeds` in the bundled file (`DRISHTI_FEEDS`) | The folder. |
| `rescan-seconds` | `30` | How often the search index and dated folders are refreshed. |
| `lookback-days` | `10` | How far back a dated read looks. |

### `rest` — an HTTP/JSON service

| Setting | Default | Meaning |
|---|---|---|
| `base-url` | required | `https://risk.internal/api` |
| `path` | `/{kind}/{id}` | Appended to the base URL. |
| `kinds` | empty = any | Comma list. |
| `timeout-ms` | `2000` | Both the connect timeout and the request timeout. |
| `generation-header` | `ETag` | A response header holding a numeric version; otherwise the fetch time. |
| `header.<Name>` | none | Request headers, e.g. `header.Authorization: Bearer ${SERVICE_TOKEN}`. |
| `source-name` | `rest` (a connector: its name) | The name shown in provenance and Health. |

A 404 answer means "not held here". Any other status below 400 is the document (`204`, other `2xx` and `3xx` bodies
are parsed; redirects are not followed; an empty body is an empty document, found); 400 or more is a failed read.
Full detail: [REST_CONNECTOR.md](../connectors/REST_CONNECTOR.md).

### `kafka` — a live stream

| Setting | Default | Meaning |
|---|---|---|
| `bootstrap-servers` | `localhost:9092` | Brokers. |
| `topics` | empty | Comma list. |
| `kind`, `id-field` | none, `id` | Mapped messages: the whole value is the document of this kind, keyed by this field. Per topic: `kind.<topic>`, `id-field.<topic>`. Without them each message is an envelope `{"kind","id","doc"}`. |
| `mode` | `state` | `state`: the stream is the store (memory: about 0.4–0.5 GB per million entities for the index and type-ahead, estimated, plus `cache-mb`). `ticks`: another store serves documents and the stream only ticks open views; it keeps nothing, but still replays the topic from the beginning at start. |
| `cache-mb` | `256` | Recently read documents kept in memory. |
| `search` | `true` | Keep identifiers for type-ahead. |
| `poll-ms` | `200` | Consumer poll interval. |
| `client.<property>` | none | Any Kafka consumer property, e.g. `client.security.protocol: SASL_SSL`. |
| `disk-cache.enabled` | `false` | Also write every message to a RocksDB store on local disk. |
| `disk-cache.root` / `disk-cache.dir` | `./data/cache` / `<root>/<connector>` | Where the store lives. |
| `disk-cache.max-gb` | `10` | Size budget. |
| `disk-cache.reset-at` / `disk-cache.zone` | `02:00` / `America/New_York` | Nightly clearing time. |
| `source-name` | `kafka` (a connector: its name) | The name shown in provenance and Health. |

Only a null value (a tombstone), or an envelope with `"doc": null`, deletes; an empty value does not. A delete is
pushed to open views (they say the entity was deleted, and when) and takes the id out of type-ahead at once. Full detail:
[KAFKA_CONNECTOR.md](../connectors/KAFKA_CONNECTOR.md).

### `activemq` and `rabbitmq` — message queues

Both keep the latest document of every entity in their own RocksDB store, because a queue delivers each
message once.

| Setting | Plugin | Default | Meaning |
|---|---|---|---|
| `broker-url` | activemq | `failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=30000` | The failover transport reconnects by itself. |
| `max-redeliveries` | activemq | `-1` | Redeliveries of a message the state store could not keep before the broker dead-letters it; `-1` is without limit. |
| `destinations` | activemq | empty | `queue:trades,topic:quotes`; a bare name is a queue. Topics use durable subscriptions, which stay on the broker after a topic is removed here. Each idle destination costs a 50 ms wait per polling loop. |
| `user`, `password`, `client-id` | activemq | none, none, `drishti-<source-name>` | |
| `uri` | rabbitmq | `amqp://guest:guest@localhost:5672/%2f` | |
| `queues` | rabbitmq | empty | `trades,quotes`, all on one channel. Empty: `UP`, consuming nothing. |
| `declare` | rabbitmq | `true` | Declare the queues durable. |
| `bind.<queue>` | rabbitmq | none | `exchange:routing.key` to bind a declared queue. |
| `prefetch`, `heartbeat-seconds`, `recovery-interval-ms` | rabbitmq | `100`, `20`, `2000` | |
| `kind`, `kind.<destination>`, `id-field`, `id-field.<destination>` | both | none, `id` | As for Kafka. |
| `cache-mb` | both | `128` | Memory cache. |
| `state.root` / `state.dir` | both | `./data/state` / `<root>/<source-name>` | The RocksDB store, one per connector. Back it up: it survives restarts and is never cleared unless configured. It keeps the latest value of each entity (level compaction), so its size follows the entities held, not the messages. |
| `state.durability` | both | `sync` | How a write is kept before the message is acknowledged. `sync`: the write-ahead log is synced on every write, so neither a crash nor a power loss loses an acknowledged message (one disk sync per message: typically thousands a second on an SSD). `wal`: the log is not synced (survives a crash of the process; a power loss can lose the last moments). `none`: no log (fastest; a crash can lose up to the 32 MB write buffer). Any other value fails the connector's start. |
| `state.max-gb` | both | `10` | This connector's disk budget (decimal allowed), compared with the store's size (`stateMb`: table files plus write buffers). Each connector has its own. |
| `state.when-full` | both | `evict-oldest` | Past the budget. `evict-oldest`: the entities written longest ago are removed until the store is under 90% of the budget, then it is compacted; each eviction is logged at WARN and counted (`evicted`). `warn`: nothing is removed; health reads `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)`. Any other value stops the connector at start (`failedToStart`), so a typo never evicts. |
| `state.check-seconds` | both | `60` | How often the store's size is checked against the budget. |
| `state.reset-at` / `state.zone` | both | `never` / `America/New_York` | Optional daily clearing time; clears the disk store only. |
| `source-name` | both | `activemq` / `rabbitmq` (a connector: its name) | The name shown in provenance and Health; names the default state folder. |

A message is acknowledged only after the state store has kept it. A message the store cannot keep (disk full, an I/O
error) is not acknowledged: RabbitMQ requeues it and ActiveMQ redelivers it a second later (without limit: `max-redeliveries`, `-1`; a number
sends the message to `ActiveMQ.DLQ` after that many), and health reads
`DOWN: <reason> (messages are not acknowledged and come again)` until one is kept again. A message the connector
cannot read (not JSON, no kind or id) is acknowledged, counted in `rejected` and dropped: there is no dead-lettering.
Cache figures add `durability`, `budgetMb` and `evicted`. Full detail:
[ACTIVEMQ_CONNECTOR.md](../connectors/ACTIVEMQ_CONNECTOR.md), [RABBITMQ_CONNECTOR.md](../connectors/RABBITMQ_CONNECTOR.md).

### `s3` — documents in S3 or an S3-compatible store

Layout as the file connector's per-entity form: `<prefix><kind>/<id>.json` and `<prefix><yyyy-MM-dd>/<kind>/<id>.json`.

| Setting | Default | Meaning |
|---|---|---|
| `bucket` | required | |
| `prefix` | empty | e.g. `risk/` |
| `region` | `us-east-1` | |
| `endpoint` | empty | For S3-compatible stores (MinIO, Ceph); path-style addressing then (`path-style`, `true`). |
| `path-style` | `true` | Path-style addressing, used with `endpoint`. |
| `access-key`, `secret-key` | empty | Static credentials, with no session token (temporary STS keys do not work here); otherwise the AWS credential chain. |
| `rescan-seconds` | `60` | How often identifiers and dates are listed. |
| `cache-seconds`, `cache-entries` | `30`, `10000` | Read cache (misses are cached too). |
| `lookback-days` | `10` | How far back an older date folder may answer for an entity missing from newer ones. |
| `source-name` | `s3` (a connector: its name) | The name shown in provenance and Health. |

There is no `mode.<kind>`: a picked date reads the newest date folder on or before it that holds the entity (within
`lookback-days`), then the undated object. A folder named like a date that is not a real one is ignored. Without
`kinds`, the kinds served are those of the last successful listing, and an empty listing serves every kind. Connect
(5 s) and socket (20 s) timeouts are fixed in the code. Full detail: [S3_CONNECTOR.md](../connectors/S3_CONNECTOR.md).

### `iceberg` — Apache Iceberg

Path-based tables or a REST catalog (Polaris, Snowflake Open Catalog, Glue), in the same layout as Delta Lake. Design,
loading and maintenance: [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `catalog` | `hadoop` | `hadoop` (path-based tables) or `rest` |
| `root` | none (`root` or `uri` is required; the plugin stays idle without one; the `iceberg` profile sets `./data/iceberg`) | `hadoop`: the lake, a folder or `s3a://…` (`abfs://`, `gs://`, `hdfs://`) |
| `domain` | empty | the data domain: the folder under `root`, or the REST namespace |
| `namespace` | the domain | `rest`: the namespace (dots separate levels) |
| `uri` | none | `rest`: the catalog's URI |
| `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`, `prefix`, `io-impl` | none | `rest`: passed to the catalog |
| `catalog.<key>` | — | `rest`: any other catalog property (`catalog.rest.sigv4-enabled` …) |
| `s3.endpoint`, `s3.access-key`, `s3.secret-key`, `s3.region`, `s3.path-style` | — | object storage for S3A and S3FileIO (`s3.path-style` defaults to `true` with an endpoint) |
| `hadoop.<key>` | — | passed to Hadoop as `<key>` |
| `kinds` | every table found (new ones too) | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | promoted paths ([ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md#5-declaring-the-layout-in-a-pack)) |
| `refresh-seconds` | `10` | how often each table's current snapshot is checked |
| `layout-cache` | `64` | planned snapshots kept |
| `id-map-mb` | `1024` | memory for days' id maps |
| `columns-cache-mb` | `1024` | memory for days' column sets |
| `doc-cache-mb` | `256` | memory for recently read documents |
| `cache-mb` | `512` | memory for small days read whole for reverse lookups |
| `max-concurrent-reads` | `16` | single-document reads at once |
| `max-load-rows` | `200000` | the largest day read whole for reverse lookups when a table has no promoted columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | the connector's name | the name shown in provenance and Health |
| `stale-after` | none | warn when no new data arrived for this long (engine setting) |

### `duckdb` — one embedded DuckDB file

Every data domain in one DuckDB file (a schema per domain, `<domain>.entities`), read in-process with no database
server; a load writes a new file and renames it over the old one while the server keeps answering. Design, loading,
sizing and measurements: [DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `path` | required | the DuckDB file; connectors naming the same path share one read-only DuckDB instance |
| `table` | required | `schema.entities`, the data domain this connector serves |
| `memory-limit` | `1GB` | DuckDB's memory limit for the shared instance (native memory, outside the Java heap); the first connector on a file sets it |
| `threads` | every core | DuckDB's threads for the shared instance; the first connector on a file sets it |
| `pool-size` | `4` | reads of this connector at once; at least `scan-threads` plus the concurrent reads you expect |
| `kinds` | the kinds the file holds | comma list of kinds to serve |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | how far back a snapshot read looks for the newest day on or before the date asked |
| `layout.<kind>.columns` | none | promoted paths ([DUCKDB_CONNECTOR.md](../connectors/DUCKDB_CONNECTOR.md#3-declaring-the-promoted-columns)) |
| `refresh-seconds` | `10` | how often the file is checked for a new load (one `stat` when nothing changed) |
| `scan-threads` | `4` | id ranges of a day read at once |
| `columns-cache-mb` | `1024` | memory for days of promoted columns |
| `columns-seconds` | `300` | how long a day's columns are kept (a new file clears them at once) |
| `max-load-rows` | `200000` | the most documents a reverse lookup reads for a kind without promoted link columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `source-name` | `duckdb` (a connector: its name) | the name shown in provenance and Health |

Retention is the loader's `--keep-days N` (`tools/load-duckdb.sh`); the connector has no retention setting.

### `mongodb`

A document per entity per business day, in a collection per data domain. Design, sizing and loader options:
[MONGODB_CONNECTOR.md](../connectors/MONGODB_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `uri` | none (required; the plugin stays idle without it; the `mongodb` profile sets `mongodb://localhost:27017`) | connection string: hosts, replica set, credentials, TLS |
| `database` | `drishti` | the database |
| `collection` | the connector's `domain`, else `drishti` | the domain's collection (`<collection>_columns` beside it) |
| `read-preference` | `primary` | `primary`, `primaryPreferred`, `secondary`, `secondaryPreferred` or `nearest` |
| `kinds`, `mode.<kind>`, `lookback-days` | what the collection holds, `snapshot`, `10` | As for Delta. |
| `layout.<kind>.columns` | none | the promoted fields |
| `refresh-seconds` | `60` | how often the kinds' dates and the ids are re-read |
| `read-threads`, `heavy-reads`, `batch-size` | `8`, `2`, `5000` | id ranges read at once for a day's columns; day reads at once; documents per cursor batch |
| `columns-cache-mb`, `columns-seconds` | `1024`, `300` | memory for days' column sets; after this a day is re-read in the background |
| `max-load-rows`, `reverse-index` | `200000`, `true` | the most documents a reverse lookup reads without promoted fields; `false` turns reverse lookups off |
| `connect-timeout-ms` | `3000` | connection and server-selection timeout |

### `redis`

Today and recent days in memory, with live updates; history behind it in Delta Lake. Design, sizing and every option:
[REDIS_CONNECTOR.md](../connectors/REDIS_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `uri` | none (required; the plugin stays idle without it) | `redis://host:6379`; `rediss://` for TLS; credentials in the URI; several comma-separated for Cluster seeds |
| `cluster` | `false` | Redis Cluster |
| `user`, `password` | none | credentials (override the URI's) |
| `domain` | `drishti` | the data domain: the key prefix |
| `kinds`, `mode.<kind>`, `lookback-days` | what `<domain>:kinds` lists, `snapshot`, `10` | As for Delta. |
| `layout.<kind>.columns` | none | the promoted fields, read from each day's column hash |
| `refresh-seconds` | `60` | how often the kinds' days and the ids are re-read |
| `columns-cache-mb`, `heavy-reads` | `1024`, `2` | memory for days' column sets; days read at once |
| `max-load-rows`, `reverse-index` | `200000`, `true` | the most documents a reverse lookup reads without promoted fields; `false` turns reverse lookups off |
| `live` | `true` | subscribe to `<domain>:changes` and push changed entities to open views |
| `timeout-ms` | `5000` | connect and command timeout |

### `aerospike`

A data domain is three sets: `<set>` (a record per entity per business date, key `kind/id/yyyyMMdd`, bins `kind`,
`id`, `date`, `doc` and the promoted fields), `<set>_ix` (a record per entity, key `kind/id`, bin `dates`) and
`<set>_kinds` (a record per kind, bin `dates`). See the [connector guide](../connectors/AEROSPIKE_CONNECTOR.md#walk-through-step-by-step)
and [AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md).

| Setting | Default | Meaning |
|---|---|---|
| `hosts` | `localhost:3000` | Seed hosts, `host:port`, comma-separated. |
| `namespace` | `test` | |
| `set` | the connector's `domain`, else `drishti` | The domain's set; the index and kinds sets add `_ix` and `_kinds`. |
| `kinds` | what the kinds set lists | Kinds served. |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective`, as for Delta. |
| `lookback-days` | `10` | How far back a snapshot kind's newest date may fall. |
| `refresh-seconds` | `60` | How often the kinds' dates and the ids (the index set) are re-read. |
| `reverse-index` | `true` | `false` turns reverse lookups off. |
| `layout.<kind>.columns` | none | Fields promoted to bins (usually from the pack); searches, pick lists, derived kinds, impact and reverse lookups read a day's bins instead of its documents. |
| `scan-threads` | `8` | Partition ranges scanned at once when a day is scanned. |
| `columns-cache-mb` | `1024` | Memory for days of promoted bins. |
| `columns-seconds` | `300` | A day's bins are read again after this. |
| `max-load-rows` | `200000` | Record limit of a reverse-lookup scan over documents (kinds without promoted bins). |
| `connect-timeout-ms` | `3000` | |
| `user`, `password` | none | Security-enabled clusters. |
| `source-name` | `aerospike` (a connector: its name) | Provenance source. |

Retention is the records' time to live, set by the loader (`tools/load-aerospike.sh --ttl-days N`); the connector
has no retention setting.

### `feed` — public data feeds

One connector per feed (the market-data pack declares them, all off by default).

| Setting | Default | Meaning |
|---|---|---|
| `feed` | required | `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury`, `fred` |
| `refresh-minutes` | `60` | |
| `timeout-seconds` | `20` | Request timeout. The connect timeout is 10 s, fixed in the code. |
| `url` | the feed's own | Override; `file:` URLs are read directly. |
| `api-key`, `series` | empty, `DGS10,DFF` | FRED only. |
| `user-agent` | `public-data-feed-connector` | The `User-Agent` header; the market-data pack sets `<product> public data feed connector`. |
| `source-name` | the feed's name (a connector: its name) | The name shown in provenance and Health. |

The history for picked dates is the window the last fetch returned. A failed fetch, or one that yields no series at all,
keeps the last good data (`DOWN: the feed returned no data (serving the last data)`); a single-series feed answering
with no rows replaces its data with none and stays `UP`. A purge clears the data, then
refetches. `stale-after` does not notice a publisher that stopped publishing, since every successful parse counts as
new data. Full detail: [FEEDS_CONNECTOR.md](../connectors/FEEDS_CONNECTOR.md).

### `derived` — kinds computed from other kinds

| Setting | Default | Meaning |
|---|---|---|
| `<kind>.from` | required | The kind it is built from. One `<kind>.from` per derived kind the connector serves. |
| `<kind>.group-by` | required | Rachana-EL over a member: its group (the derived entity's id). |
| `<kind>.where` | none | Rachana-EL: only members for which it is true count. |
| `<kind>.id-field` | `id` | A field that holds the key besides `id`. |
| `<kind>.members` | `members` | The field listing the members' ids. |
| `<kind>.fields.<name>` | none | `count`, or `sum`/`avg`/`min`/`max`/`distinct`/`first` followed by an expression. |
| `<kind>.rows.<column>` | none | One row per member with these columns (field `rows`, or `<kind>.rows-field`). |
| `refresh` | `30s` | How long a computation is reused per business date. |
| `max-scan` | `50000` | Members read at most. |

In a pack the settings may be nested (`book-pnl: { from: trade, fields: { mtm: sum $.mtm } }`): nested maps become
dotted keys. Worked example: [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds).

### `demo` — sample data

| Setting | Default | Meaning |
|---|---|---|
| `dirs` | from packs | Sample directories. |
| `ticking` | `true` | Live documents move while someone watches. |
| `tick-ms` | `400` | Tick interval. |

The shipped `application.yaml` sets only `drishti.sources.plugins.demo.enabled` (`DRISHTI_DEMO_ENABLED`); `ticking`
and `tick-ms` are the code's defaults. Full detail: [DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md).

---

## Environment variables used by the packs and profiles

Packs declare their connectors in `pack.yaml` with placeholders, so you switch them with environment variables:

| Variable | Default | Pack | Effect |
|---|---|---|---|
| `DRISHTI_DELTA_ROOT` | `./data/delta` | every shipped pack | Root of every pack's Delta Lake connector. |
| `DRISHTI_DELTA_ENGINE` | `native` | every Delta connector without its own `engine` | `native`, `hadoop` or `auto` (see [`delta`](#delta--delta-lake)). |
| `DRISHTI_LAKE_ENABLED` | `true` | every shipped pack | Switch every pack's data connector off (the demo samples still answer). Leave it `true` with the `postgres`, `aerospike` or `duckdb` profile: those profiles change the plugin of the same connectors, and this switch would turn them off too. |
| `DRISHTI_STREAM_TRADING` | `false` | trading | Turn on the `trading-stream` Kafka connector. |
| `DRISHTI_KAFKA_BOOTSTRAP` | `localhost:9092` | trading | Its brokers. |
| `DRISHTI_TRADING_TOPIC` | `drishti.trading.trades` | trading | Its topic. |
| `DRISHTI_STREAM_DISK_CACHE` | `true` | trading | Its disk cache on or off. |
| `DRISHTI_CACHE_ROOT` | `./data/cache` | trading | Disk cache root (one sub-directory per connector). |
| `DRISHTI_STREAM_CACHE_GB` | `10` | trading | Disk cache size. |
| `DRISHTI_CACHE_RESET_AT` | `02:00` | trading | Nightly clearing time (New York). |
| `DRISHTI_FEED_NYFED_SOFR`, `DRISHTI_FEED_ECB_ESTR`, `DRISHTI_FEED_ECB_FX`, `DRISHTI_FEED_US_TREASURY`, `DRISHTI_FEED_FRED` | `false` | market-data | Turn each public feed on. |
| `FRED_API_KEY`, `DRISHTI_FRED_SERIES` | empty, `DGS10,DFF` | market-data | FRED needs a key. |

Example: the trading pack with its live Kafka stream.

```bash
DRISHTI_PACKS=finance,trading DRISHTI_STREAM_TRADING=true DRISHTI_KAFKA_BOOTSTRAP=kafka:9092 \
  java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
```

`curl -s localhost:18480/api/v1/sources` should then list `trading-stream`.

### Profiles: another store instead of Delta Lake

`SPRING_PROFILES_ACTIVE=<profile>` (`postgres`, `aerospike`, `duckdb`, `files`, `iceberg`, `mongodb` or `redis`) loads
`application-<profile>.yaml`, which redefines the banking data-domain connectors (`reference-store`,
`market-store`, `trading-store`, `risk-store`, `credit-store`, `collateral-store`) to read a database instead of
the lake. The packs still decide kinds, routes and modes.

| Profile | Variables | Defaults |
|---|---|---|
| `postgres` | `DRISHTI_PG_URL`, `DRISHTI_PG_USER`, `DRISHTI_PG_PASSWORD` | `jdbc:postgresql://localhost:5432/drishti`, `drishti`, `drishti` (tables `<domain>.entities`) |
| `aerospike` | `DRISHTI_AEROSPIKE_HOSTS`, `DRISHTI_AEROSPIKE_NAMESPACE` | `localhost:3000`, `test` |
| `files` | `DRISHTI_FILES_ROOT` | `./data/files` (`<root>/<domain>/<date>/<kind>.jsonl`) |
| `iceberg` | `DRISHTI_ICEBERG_ROOT`, `DRISHTI_ICEBERG_CATALOG`, `DRISHTI_ICEBERG_URI`, `DRISHTI_ICEBERG_WAREHOUSE`, `DRISHTI_ICEBERG_CREDENTIAL` | `./data/iceberg`, `hadoop` (path-based tables); for a REST catalog `rest` and its URI, warehouse and credential |
| `mongodb` | `DRISHTI_MONGODB_URI`, `DRISHTI_MONGODB_DATABASE` | `mongodb://localhost:27017`, `drishti` |
| `redis` | `DRISHTI_REDIS_URI`, `DRISHTI_REDIS_CLUSTER` | `redis://localhost:6379`, `false` |
| `duckdb` | `DRISHTI_DUCKDB_PATH`, `DRISHTI_DUCKDB_MEMORY` | `data/duckdb/drishti.duckdb`, `1GB` (one file, a schema per domain, `<domain>.entities`; `memory-limit` of the shared DuckDB instance) |

```bash
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PG_URL=jdbc:postgresql://db:5432/drishti \
  DRISHTI_PG_PASSWORD="$PG_PASSWORD" java -jar drishti-server/target/drishti-server-1.17.0-exec.jar
```

---

## Spring settings you may need

| Key | Bundled value | Meaning |
|---|---|---|
| `server.port` | `${DRISHTI_PORT:18480}` | HTTP port. |
| `server.shutdown` | `graceful` | Finish requests in flight on stop. |
| `server.compression` | on for JSON and `text/event-stream` | |
| `spring.threads.virtual.enabled` | `true` | Every request runs on a virtual thread. Leave it on. |
| `spring.profiles.active` | none (`SPRING_PROFILES_ACTIVE`) | `postgres`, `aerospike` or `duckdb`, above. |
| `spring.config.additional-location` | none (`SPRING_CONFIG_ADDITIONAL_LOCATION`) | Another config file or folder read **in addition to** the bundled one, e.g. `file:/etc/drishti/site.yaml`. See [QUICKSTART](../guides/QUICKSTART.md#supplying-a-different-application-config). |
| `spring.config.location` | none | Config files read **instead of** the defaults: the bundled `application.yaml` is then not read unless you list `classpath:/application.yaml` first. |
| `spring.config.import` | the local file and the packs overlay (bundled) | `optional:file:./application.local.yaml` and `optional:file:${DRISHTI_PACKS_OVERLAY:./data/packs/added.yaml}`. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Actuator endpoints: `/actuator/health` (with `/liveness` and `/readiness` probes), `/actuator/prometheus` (timer `drishti.view`, gauges `drishti.live.*`). |
| `drishti.security.token-read-posts` | `/api/v1/search/pivot/**`, `/api/v1/command` | The `POST` paths (ant patterns) a personal API token may call because they only read. Every other non-`GET` request stays refused for a token. |
| `drishti.security.token-scopes` | `design:write`, `design:approve`, `packs:admin` | The write scopes a personal API token may be given: for each, a `description` (shown on the account page) and the `allow` list of `METHOD path` patterns it opens (`*` for any method). Keys with a colon are written `"[design:write]":`. A scope is only a door: the controller still checks the user's roles at the time of each call. A write no scope opens is refused. |
| `drishti.security.token-never` | users, roles, tokens, sign-in, caches, reports, collaboration, `/me/**` | `METHOD path` patterns no token may ever write, whatever its scopes. `TokenScopeGuardTest` fails the build when a non-`GET` endpoint is in none of `token-read-posts`, a scope or this list. |
| `drishti.security.token-write-max-days` | `90` | The longest life, in days, of a token with a write scope (which must expire). Read-only tokens keep 1-366 days or no expiry. |
| `springdoc.api-docs.path` / `springdoc.swagger-ui.path` | `/api/docs` / `/api/docs/ui` | The OpenAPI description and its UI. |
| `logging.level.<package>` | Spring default (`INFO`) | e.g. `--logging.level.com.ash.drishti=DEBUG` |

---

## Console reference

File: `drishti-console/config/application.yaml`. Local override: `drishti-console/config/application.local.yaml`.
Precedence (low to high): the file, the local file, `DRISHTI_CONSOLE__…` variables, `--key=value` arguments.
`${VAR:default}` placeholders in the files are resolved from the environment.

The environment form is `DRISHTI_CONSOLE__` followed by the key with `.` written as `__`, in any case:

```bash
DRISHTI_CONSOLE__SERVER__PORT=17481 python drishti-console/run_drishti_web.py
# same as
python drishti-console/run_drishti_web.py --server.port=17481
```

You should see uvicorn report `Uvicorn running on http://127.0.0.1:17481`.

### `server`, `backend`

| Key | Default | Meaning |
|---|---|---|
| `server.host` | `127.0.0.1` (`DRISHTI_CONSOLE_HOST`) | Listening address. Use `0.0.0.0` in a container or behind a proxy on another host. |
| `server.port` | `17480` (`DRISHTI_CONSOLE_PORT`) | Listening port. |
| `backend.url` | `http://127.0.0.1:18480` (`DRISHTI_BACKEND_URL`) | The Drishti server. |
| `backend.timeout_seconds` | `5` | Per call to the server. |
| `backend.name` | the product name (`ui.product`) | The name of the single server when `servers` is not set; the picker and the top bar show it. |
| `backend.pool_size` | `64` | Pooled HTTP connections to the server (per server, when there are several). |
| `collab.bridge_test_timeout_seconds` | `30` | How long Admin > Collaboration waits for a bridge **Test** (the server waits for the bridge's own `drishti.collab.bridges.timeout`, default 10 s, so keep this longer). When it runs out the page says `DRS-7013` and names `drishti.collab.bridges.timeout`. |

### `servers` — one console, many servers

Leave it out and the console has one server, `backend.url`. List several and people pick one, then sign in to it
(ADR-016). Each server keeps its own users, roles, packs and data: a session on one gives nothing on another, and
each server trusts only tokens signed with its own secret.

```yaml
servers:
  - id: open                       # lower-case letters, digits, dashes; appears in links (/connect/open, ?srv=open)
    name: Drishti
    url: http://drishti-a:18480
    description: Everyone's server
    color: "#2a9d8f"               # the dot beside the name in the top bar
    token_secret: ${DRISHTI_TOKEN_SECRET_OPEN:}     # that server's drishti.security.secret (default: auth.token_secret)
  - id: rates-desk
    name: Rates desk
    url: https://drishti-rates.internal:18480
    color: "#e76f51"
    token_secret: ${DRISHTI_TOKEN_SECRET_RATES:}
  - id: preprod
    name: Pre-production
    url: http://drishti-pre:18480
    listed: false                  # not in the picker; reachable by the link /connect/preprod
```

| Key | Default | Meaning |
|---|---|---|
| `servers[].id` | required | Its name in links and cookies. Only ids in this list are ever reached: a link cannot point the console at another address. |
| `servers[].name`, `description`, `color` | id, empty, the link colour | What the picker shows. |
| `servers[].url` | required | The server's address. |
| `servers[].token_secret` | `auth.token_secret` | The secret shared with that server (`drishti.security.secret` there). Give each server its own: a leaked secret then opens one server, not all. |
| `servers[].listed` | `true` | `false` hides it from the picker; `/connect/<id>` still reaches it. |

The first server is the default for a browser that has not chosen one. The choice is kept per browser (cookie
`drishti_server`, a year), and each server's session in its own cookie (`drishti_session_<id>`), so you can stay
signed in to several and switch without signing in again. Signing out signs out of the current server only. Single
sign-on settings (`auth.oidc`) apply to every server; each server verifies the ID token itself.

### `ui`

| Key | Default | Meaning |
|---|---|---|
| `default_theme` | `terminal` | `terminal`, `light`, `wallstreet`, `blue`, `green`, `crimson`, `crimson-dark`. Users can choose their own. |
| `product` / `tagline` | `Drishti` / `Any data. Any domain. One grammar.` | Shown in the top bar and sign-in page. |
| `user`, `user_display`, `desk` | `ash` (`DRISHTI_USER`), `Ash`, `Rates desk` | The acting user when `auth.enabled` is false (local development only). |
| `about_prefetch` | `true` (`DRISHTI_ABOUT_PREFETCH`) | Fetch the page's *About this page* answer once the view is idle, so table headers, strip and key-value labels that have a glossary entry are underlined (with a hint) before the drawer is opened. It costs the server one explain per page view: the view is derived again for the caller and kept `drishti.explain.cache-ttl`, so opening the drawer afterwards is free. Set `false` on a busy installation; the labels are then underlined when the drawer is first opened ([PERFORMANCE.md](PERFORMANCE.md#about-this-page-and-its-cost)). |
| `clock_tz` / `clock_label` | `America/New_York` / `NY` | The top-bar clock. |
| `landing_examples` | `4` | How many example commands the landing page plays. They are the example commands (`console.examples`) of the packs switched on, so the landing page never names an entity of a pack that is not installed; with none, it shows the form `<MNEMONIC> <ID> <GO>`. Studio's first preview is the first of these examples too. |
| `product_native` / `product_meaning` | `दृष्टि` / `Drishti (दृष्टि) means <em>sight</em>.` | The product name in its own script (blank for none) and one line on its meaning, on the About page. |
| `copyright` / `notice` | the Drishti copyright and a ban on unauthorised copying | The legal lines in the footer and on the About page. Change them here, never in code; the server has the same under `drishti.branding`. |
| `error_advice` | a short text for each of `DRS-1001`, `DRS-1002`, `DRS-1003`, `DRS-1004`, `DRS-1007`, `DRS-4003`, `DRS-4004`, and `default` | What an error page tells the user to do, by the code the server answered with. `{kind}` and `{id}` in a text become the entity asked for; a code with no entry gets `default`. Edit it so the advice fits your site (who to ask, which channel). |
| `showcase` | four finance-pack screenshots | The landing page's pictures: `{slug, title, cmd, sutra}` each, the image being `web/static/img/shot-<slug>.png`. A caption names its `cmd` only when that command is one of the packs' examples. |

### `auth`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` (`DRISHTI_AUTH_ENABLED`) | Require sign-in. |
| `session_secret` | empty (`DRISHTI_SESSION_SECRET`) | Signs the session cookie. At least 32 characters when auth is on, or the console will not start. |
| `token_secret` | empty (`DRISHTI_TOKEN_SECRET`) | Signs the short-lived tokens sent to the server; must equal the server's `drishti.security.secret`. |
| `token_ttl_seconds` | `300` | Lifetime of each server token. |
| `session_hours` | `10` | Session lifetime (the cookie's and the server session's). |
| `recheck_seconds` | `10` (`DRISHTI_SESSION_RECHECK_SECONDS`) | How long the console reuses the server's answer to "does this session still stand, and who is the user now?" (enabled, roles, password change due). Disabling, demoting or signing out takes effect within this time, at once on the console an administrator used. `0` asks on every request. |
| `allowed_origins` | `[]` | Origins besides the console's own (the request's `Host`) allowed to send state-changing requests; for a proxy that changes the `Host` header. Requests from any other origin are refused (`403 DRS-5002`). |
| `secure_cookie` | `true` (`DRISHTI_SECURE_COOKIE`) | Cookie sent over HTTPS only. Set `false` only for plain-HTTP testing. |
| `oidc.enabled` | `false` (`DRISHTI_OIDC_ENABLED`) | Show the single sign-on button. |
| `oidc.issuer` | empty (`DRISHTI_OIDC_ISSUER`) | Same issuer as the server. |
| `oidc.client_id` | empty (`DRISHTI_OIDC_CLIENT_ID`) | Same client id as the server. |
| `oidc.client_secret` | empty (`DRISHTI_OIDC_CLIENT_SECRET`) | Empty for a public client (PKCE alone). |
| `oidc.token_auth` | `client_secret_basic` | Or `client_secret_post`. |
| `oidc.scopes` | `openid profile email` | |
| `oidc.label` | `Sign in with single sign-on` | Button text. |
| `oidc.redirect_uri` | `<console>/auth/oidc/callback` (`DRISHTI_OIDC_REDIRECT_URI`) | Register this at the provider. Set it explicitly behind a proxy. |

Note that `DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID` and `DRISHTI_TOKEN_SECRET` are
read by both programs, so one set of variables configures both halves.

### `packs`, `help`

| Key | Default | Meaning |
|---|---|---|
| `packs.dir` | `../packs` (`DRISHTI_PACKS_DIR`), relative to `drishti-console/` | Where pack content (examples, guides) is read. |
| `packs.enabled` | `finance` | Used only when the server cannot be asked which packs are enabled. |
| `help.docs_dir` | `../docs` | The documents rendered in the help centre's reference section. |
| `studio.examples_dir` | `../docs/guides/examples` | The Rachana examples (`<name>.sutra.yaml`, `<name>.json`, `<name>.md`) that the Build workbench (**Build → New screen → Examples**, the File menu) and **Help → Examples** offer. Only names of complete example sets present there are served. |
| `ui.studio_example` | `all-panels-showcase` | The example Studio opens on when no entity or Sutra is asked for. Blank, or a name that is not there: Studio opens on the first example entity of the user's packs, as before. |
| `builder.max_samples` | `50` | Build workbench, **New** (`/build/new`): files in one upload, and samples in all (a `.jsonl` line is one). Over it, the whole upload is refused (`413 DRS-5005`). Keep it at or under the server's `drishti.builder.max-samples`. |
| `builder.max_file_mb` | `5` | Largest single file; a larger one is left out and reported, the rest are shaped. |
| `builder.max_total_mb` | `25` | All files together; over it the upload is refused. |
| `builder.studio_kind` | `sample` | The kind a new Design and the workbench preview a brought document as (a pasted document has no kind of its own). |

`config/workspaces.yaml` holds the console's own starter workspaces (`templates`, none by default; packs add theirs)
and `blank`, what a new workspace starts as (*New workspace* on `/w`): a `layout` (`2col`, `3col`, `2x2`, `1+2`) and one
to four empty `panes`.

The file also holds `studio.enabled: true`, which the current console does not read; whether Studio can save
is decided by the server (`drishti.rachana.studio-save`).

### `calc`

| Key | Default | Meaning |
|---|---|---|
| `calc.enabled` | `true` (`DRISHTI_CALC_ENABLED`) | Off: no view offers Calc and the console's `/api/calc/*` routes refuse, whatever packs and roles say ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)). |

### `layouts`

| Key | Default | Meaning |
|---|---|---|
| `layouts.enabled` | `true` (`DRISHTI_LAYOUTS_ENABLED`) | Off: no view offers layout mode and saved personal layouts are not applied, whatever roles say ([USER_GUIDE.md](../guides/USER_GUIDE.md#layout-mode-arrange-a-view-your-way)). |
| `layouts.min_span` | `3` | The narrowest width, in columns of the 12-column grid (1 to 12), that layout mode lets a user give a panel. |

### `live`

| Key | Default | Meaning |
|---|---|---|
| `live.max_subscriptions` | `32` (`DRISHTI_LIVE_MAX_SUBSCRIPTIONS`) | Live subscriptions one browser's channel carries at most: views across all its tabs and workspace panes, the alerts bell, monitors (a view open in several tabs counts once). Each holds one stream to the server from `backend.pool_size`, so keep it well below that. One over the limit is answered with `gone`, `DRS-5003`, and the view shows `Static` ([LIVE.md](../architecture/LIVE.md#one-connection-per-browser)). |

The Python runtime is a folder, not a setting: `drishti-console/web/static/vendor/pyodide/`, installed by
`tools/fetch-pyodide.sh` and served at `/pyodide/<version>/` when present
([PYTHON_CALC.md](../guides/PYTHON_CALC.md#12-installing-the-python-runtime)).

---

## Environment variable index

Server (S), console (C), or both.

| Variable | Used by | Sets |
|---|---|---|
| `DRISHTI_PORT` | S | `server.port` |
| `DRISHTI_PACKS` | S | `drishti.packs.enabled` |
| `DRISHTI_PACKS_DIR` | S, C | `drishti.packs.dir`, `packs.dir` |
| `DRISHTI_DEFAULT_PACKS` | S | `drishti.packs.default-for-users` |
| `DRISHTI_PLUGIN_DIR` | S | `drishti.sources.plugin-dir` |
| `DRISHTI_DEMO_ENABLED` | S | `drishti.sources.plugins.demo.enabled` |
| `DRISHTI_FEEDS` | S | `drishti.sources.plugins.file.settings.root` |
| `DRISHTI_REST_ENABLED`, `DRISHTI_REST_URL` | S | the `rest` plugin |
| `DRISHTI_JDBC_ENABLED`, `DRISHTI_JDBC_URL`, `DRISHTI_JDBC_USER`, `DRISHTI_JDBC_PASSWORD` | S | the `jdbc` plugin |
| `DRISHTI_ACTIVEMQ_ENABLED`, `DRISHTI_RABBITMQ_ENABLED`, `DRISHTI_S3_ENABLED` | S | those plugins |
| `DRISHTI_CALENDAR` | S | `drishti.business-date.calendar` |
| `DRISHTI_SUTRAS` | S | `drishti.rachana.dirs` |
| `DRISHTI_STUDIO_SAVE` | S | `drishti.rachana.studio-save` |
| `DRISHTI_SUTRA_REVIEW`, `DRISHTI_SUTRA_FOUR_EYES`, `DRISHTI_GOVERNANCE_DIR` | S | `drishti.governance.*` |
| `DRISHTI_SECURITY_ENABLED` | S | `drishti.security.enabled` |
| `DRISHTI_CALC_ENABLED` | S, C | `drishti.calc.enabled`, `calc.enabled` |
| `DRISHTI_LIVE_MAX_SUBSCRIPTIONS` | C | `live.max_subscriptions` |
| `DRISHTI_TOKEN_SECRET` | S, C | `drishti.security.secret`, `auth.token_secret` |
| `DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID` | S, C | `drishti.security.oidc.*`, `auth.oidc.*` |
| `DRISHTI_OIDC_CLIENT_SECRET`, `DRISHTI_OIDC_REDIRECT_URI` | C | `auth.oidc.*` |
| `DRISHTI_SEED_ADMIN` and other identity variables | S | see [USER_MANAGEMENT.md](USER_MANAGEMENT.md) |
| `DRISHTI_DELTA_ROOT`, `DRISHTI_DELTA_ENGINE`, `DRISHTI_LAKE_ENABLED`, `DRISHTI_STREAM_*`, `DRISHTI_KAFKA_BOOTSTRAP`, `DRISHTI_TRADING_TOPIC`, `DRISHTI_CACHE_*`, `DRISHTI_FEED_*`, `FRED_API_KEY`, `DRISHTI_FRED_SERIES` | S (packs) | [pack connectors](#environment-variables-used-by-the-packs-and-profiles) |
| `SPRING_PROFILES_ACTIVE`, `DRISHTI_PG_*`, `DRISHTI_AEROSPIKE_*`, `DRISHTI_DUCKDB_*`, `DRISHTI_FILES_ROOT`, `DRISHTI_ICEBERG_*`, `DRISHTI_MONGODB_*`, `DRISHTI_REDIS_*` | S | [profiles](#profiles-another-store-instead-of-delta-lake) |
| `DRISHTI_CONSOLE_HOST`, `DRISHTI_CONSOLE_PORT`, `DRISHTI_BACKEND_URL`, `DRISHTI_USER` | C | `server.*`, `backend.url`, `ui.user` |
| `DRISHTI_AUTH_ENABLED`, `DRISHTI_SESSION_SECRET`, `DRISHTI_SECURE_COOKIE` | C | `auth.*` |
| `DRISHTI_CONSOLE__<SECTION>__<KEY>` | C | any console key |
| `DRISHTI_<RELAXED_KEY>` (e.g. `DRISHTI_LIVE_HEARTBEAT=20s`) | S | any server key |

The Docker compose files in `deploy/` show these variables in use; see [OPERATIONS.md](OPERATIONS.md).
