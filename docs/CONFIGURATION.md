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

Configuration is layered: the bundled file, then a git-ignored local file, then environment variables,
then command-line arguments. Tracked files never hold secrets.

## Server (`drishti-server/src/main/resources/application.yaml`)

The local override is `./application.local.yaml`. Environment variables use Spring's relaxed names
(`DRISHTI_SOURCES_FETCH_TIMEOUT=3s`), and arguments are `--drishti.sources.fetch-timeout=3s`.

| Key | Default | Meaning |
|---|---|---|
| `server.port` | `18480` (`DRISHTI_PORT`) | HTTP port |
| `drishti.sources.fetch-timeout` | `2s` | the longest a single source read may take |
| `drishti.sources.default-route` / `routes.<kind>` | `demo` | which plugin serves a kind (see PLUGIN_GUIDE) |
| `drishti.sources.plugin-dir` | — | extra plugin jars, each in its own class loader |
| `drishti.sources.plugins.<name>.enabled / settings` | | plugin switch and settings |
| `drishti.sources.connectors.<name>` | `finance-lake` | a named instance of a plugin: `{plugin, enabled, kinds, settings}`; run a plugin several times (a Delta Lake per domain). `source-name` defaults to the connector's name |
| `drishti.sources.connectors.*.settings` for `delta` | | `root` (`./data/delta`, `DRISHTI_DELTA_ROOT`), `domain` (sub-folder), `kinds`, `mode.<kind>` (`snapshot` or `effective`), `lookback-days` (10), `refresh-seconds` (10), `cache-mb` (512), `id-column` / `doc-column` / `date-column` |
| `SPRING_PROFILES_ACTIVE` | — | `postgres` or `aerospike`: the banking packs' data domains (`<domain>-store`) read PostgreSQL (`DRISHTI_PG_URL`, `DRISHTI_PG_USER`, `DRISHTI_PG_PASSWORD`) or Aerospike (`DRISHTI_AEROSPIKE_HOSTS`, `DRISHTI_AEROSPIKE_NAMESPACE`) instead of Delta Lake |
| `DRISHTI_FEED_NYFED_SOFR`, `DRISHTI_FEED_ECB_ESTR`, `DRISHTI_FEED_ECB_FX`, `DRISHTI_FEED_US_TREASURY`, `DRISHTI_FEED_FRED` | `false` | switch each public data feed on (market-data pack); FRED also needs `FRED_API_KEY` and takes `DRISHTI_FRED_SERIES` |
| `DRISHTI_STREAM_TRADING`, `DRISHTI_KAFKA_BOOTSTRAP`, `DRISHTI_TRADING_TOPIC` | `false`, `localhost:9092`, `drishti.trading.trades` | the trading pack's live Kafka stream |
| `drishti.business-date.calendar` | `USNY` (`DRISHTI_CALENDAR`) | business-day calendar for the default date: `USNY`, `GBLO`, `EUTA`, `JPTO`, or joint (`USNY+GBLO`) |
| `drishti.business-date.zone` / `history` | `America/New_York` / `P5Y` | whose "today", and how far back users may go |
| `drishti.packs.default-for-users` | every installed pack (`DRISHTI_DEFAULT_PACKS`) | the packs a user gets until an admin assigns them |
| `drishti.packs.dir` / `enabled` | `./packs` / `finance` (`DRISHTI_PACKS_DIR`, `DRISHTI_PACKS`) | domain packs to load, in order (see PACKS.md). One folder per pack. `DRISHTI_PACKS_DIR` sets the folder for both server and console, so give it an absolute path. The defaults are the repository's `packs/`: relative to the working directory for the server, and to `console/` for the console. |
| `drishti.rachana.dirs` | `./sutras` (`DRISHTI_SUTRAS`) | site Sutra directories, in addition to the packs' |
| `drishti.rachana.hot-reload` / `reload-debounce` | `true` / `250ms` | reload Sutras when they are saved |
| `drishti.rachana.formats-file` | — | a site file that overrides or adds named formats |
| `drishti.rachana.expression-cache-size` | `10000` | compiled expressions kept in memory |
| `drishti.rachana.studio-save` | `false` (`DRISHTI_STUDIO_SAVE`) | let Studio write Sutra files (authors only) |
| `drishti.security.enabled` / `secret` | `false` / — (`DRISHTI_TOKEN_SECRET`) | require HS256 bearer tokens; the secret is shared with the console |
| `drishti.security.roles.<role>` | viewer, author, admin + packs' (finance: trader, risk; logistics: ops) | `{kinds, raw, author, admin}` |
| `drishti.identity.users-file` / `audit-file` | `./data/identity/users.json` / `audit.jsonl` | user store and audit log |
| `drishti.identity.iterations / min-password-length` | `240000 / 10` | hashing and password policy |
| `drishti.identity.max-failed-attempts / lockout` | `5 / 15m` | lockout |
| `drishti.identity.seed-admin` | `true` (`DRISHTI_SEED_ADMIN`) | create `drishti-dev-admin` on an empty store |
| `drishti.identity.force-password-change-on-create / -on-reset` | `false / false` | force a password change at next sign-in |
| `drishti.security.redact` | `[trader, counterpartyId]` | fields masked in raw JSON for roles without `raw` |
| `drishti.live.frame / heartbeat / max-streams / window` | `50ms / 15s / 20000 / 30s` | live updates |
| `drishti.sources.plugins.rest.settings.*` | | `base-url`, `path`, `kinds`, `header.<Name>`, `timeout-ms`, `generation-header` |
| `drishti.sources.plugins.jdbc.settings.*` | | `url`, `user`, `password`, `pool-size`, `query.<kind>` (with `?` or named `:id` and `:asOf`) |
| `drishti.sources.plugins.file.settings.lookback-days` | `10` | dated folders `<root>/<yyyy-MM-dd>/<kind>/`: how far back to look for a date's file |
| `drishti.inference.semantics-file` | — | a site file that replaces the semantic hints |
| `drishti.engine.layout-cache-size` | `10000` | effective layouts (one per Sutra version × shape) |
| `drishti.engine.fingerprint-cache-size` | `100000` | fingerprints (one per entity × generation) |
| `drishti.engine.bind-parallelism` | `0` (cores) | threads binding panels |
| `drishti.commands.mnemonics.<CODE>` | from the enabled packs | `{kind, label}`; a site entry overrides a pack's |
| `drishti.commands.suggest-limit / suggest-budget / recent-size` | `10 / 30ms / 20` | type-ahead |
| `drishti.graph.id-patterns` | from the enabled packs | identifier regex → kind (a site list replaces the packs' list) |
| `drishti.graph.fields.<field>` | from the enabled packs | a document field that references `{kind, label}` |
| `drishti.graph.badges.<kind>` | from the enabled packs | Rachana-EL over the linked document, shown beside the link |
| `drishti.graph.link-budget` | `40ms` | linked entities slower than this show as *pending* |

Actuator: `/actuator/health`, `/actuator/prometheus` (timer `drishti.view` with p50/p99).

## Console (`console/config/application.yaml`)

The local override is `console/config/application.local.yaml`. Environment variables use
`DRISHTI_CONSOLE__SECTION__KEY=value`, and arguments are `--section.key=value`.

| Key | Default | Meaning |
|---|---|---|
| `server.host` / `server.port` | `127.0.0.1` / `17480` | where the console listens |
| `backend.url` | `http://127.0.0.1:18480` (`DRISHTI_BACKEND_URL`) | the Drishti server |
| `backend.timeout_seconds` / `pool_size` | `5` / `64` | the pooled HTTP client |
| `ui.default_theme` | `terminal` | `terminal`, `light`, `wallstreet`, `blue`, `green`, `crimson` or `crimson-dark` |
| `ui.user`, `ui.user_display`, `ui.desk` | `ash`, `Ash`, `Rates desk` | the acting user when `auth.enabled` is false (local development) |
| `ui.clock_tz` / `ui.clock_label` | `America/New_York` / `NY` | the top-bar clock |
| `packs.dir` / `packs.enabled` | `../packs` / `finance` | where pack content lives; the enabled list is asked of the server, and this one is used only when the server can't be reached |
| `auth.enabled` | `false` (`DRISHTI_AUTH_ENABLED`) | require sign-in |
| `auth.session_secret` / `token_secret` | from the environment | cookie signing; server tokens (shared with the server) |
| `auth.token_ttl_seconds` / `session_hours` / `secure_cookie` | `300` / `10` / `true` | token and session lifetimes |
