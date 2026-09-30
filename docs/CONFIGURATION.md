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
| `drishti.rachana.dirs` | `./sutras` (`DRISHTI_SUTRAS`) | where Sutra files live |
| `drishti.rachana.hot-reload` / `reload-debounce` | `true` / `250ms` | reload Sutras when they are saved |
| `drishti.rachana.formats-file` | — | a site file that overrides or adds named formats |
| `drishti.rachana.expression-cache-size` | `10000` | compiled expressions kept in memory |
| `drishti.rachana.studio-save` | `false` (`DRISHTI_STUDIO_SAVE`) | let Studio write Sutra files (authors only) |
| `drishti.security.enabled` / `secret` | `false` / — (`DRISHTI_TOKEN_SECRET`) | require HS256 bearer tokens; the secret is shared with the console |
| `drishti.security.roles.<role>` | trader, risk, author | `{kinds, raw, author}` |
| `drishti.identity.users-file` / `audit-file` | `./data/identity/users.json` / `audit.jsonl` | user store and audit log |
| `drishti.identity.iterations / min-password-length` | `240000 / 10` | hashing and password policy |
| `drishti.identity.max-failed-attempts / lockout` | `5 / 15m` | lockout |
| `drishti.identity.seed-admin` | `true` (`DRISHTI_SEED_ADMIN`) | create `drishti-dev-admin` on an empty store |
| `drishti.identity.force-password-change-on-create / -on-reset` | `false / false` | force a password change at next sign-in |
| `drishti.security.redact` | `[trader, counterpartyId]` | fields masked in raw JSON for roles without `raw` |
| `drishti.live.frame / heartbeat / max-streams / window` | `50ms / 15s / 20000 / 30s` | live updates |
| `drishti.sources.plugins.rest.settings.*` | | `base-url`, `path`, `kinds`, `header.<Name>`, `timeout-ms`, `generation-header` |
| `drishti.sources.plugins.jdbc.settings.*` | | `url`, `user`, `password`, `pool-size`, `query.<kind>` |
| `drishti.inference.semantics-file` | — | a site file that replaces the semantic hints |
| `drishti.engine.layout-cache-size` | `10000` | effective layouts (one per Sutra version × shape) |
| `drishti.engine.fingerprint-cache-size` | `100000` | fingerprints (one per entity × generation) |
| `drishti.engine.bind-parallelism` | `0` (cores) | threads binding panels |
| `drishti.commands.mnemonics.<CODE>` | 12 built in | `{kind, label}` |
| `drishti.commands.suggest-limit / suggest-budget / recent-size` | `10 / 30ms / 20` | type-ahead |
| `drishti.graph.id-patterns` | trade, NS-, CSA-, … | identifier regex → kind |
| `drishti.graph.fields.<field>` | nettingSet, csa, … | a document field that references `{kind, label}` |
| `drishti.graph.badges.<kind>` | | Rachana-EL over the linked document, shown beside the link |
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
| `ui.default_theme` | `terminal` | `terminal`, `light`, `wallstreet`, `blue` or `green` |
| `ui.user`, `ui.user_display`, `ui.desk` | `ash`, `Ash`, `Rates desk` | the acting user when `auth.enabled` is false (local development) |
| `ui.clock_tz` / `ui.clock_label` | `America/New_York` / `NY` | the top-bar clock |
| `auth.enabled` | `false` (`DRISHTI_AUTH_ENABLED`) | require sign-in |
| `auth.session_secret` / `token_secret` | from the environment | cookie signing; server tokens (shared with the server) |
| `auth.token_ttl_seconds` / `session_hours` / `secure_cookie` | `300` / `10` / `true` | token and session lifetimes |
