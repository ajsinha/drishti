<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.
-->

# Production-readiness audit (i8)

**When:** 2026-11-27 23:40 EST. **Kind:** read-only design audit; no code was changed and nothing was run.
**Rule audited:** "Everything must be standardised with production rollout in mind": a bank runs Drishti on Linux against its own
external lakes (known only by path and schema), its own PostgreSQL identity database, secrets, schedulers and operators.

**What was read** (with `git show` / `git grep`, no checkout touched):

| Ref | Commit | Notes |
|---|---|---|
| `origin/i8/develop` | `f389fead0` | the base of every finding unless a row says otherwise |
| `origin/i8/catalogue` | `7bd1df76d` | 4 commits ahead: the table catalogue (S1) |
| `origin/i8/home` | `0b813d6da` | 5 ahead, 26 behind: role Homes |
| `origin/r2/exposure-routes` | `7277e333b` | 44 ahead, 26 behind: R2 exposure routes |

**Limits of this audit.** Evidence is from reading sources and the guide, not from an install. Row-rule coverage (R11) was mapped by
file, not proven route by route. The products (`drishti-tools`, `-lake`, `-rachana`, `-elements`, `-risk`) were not audited beyond one
check of `drishti-lake`'s lake-file reader.

Short names used below: **app.yaml** = `kernel/drishti-server/src/main/resources/application.yaml`; **console.yaml** =
`drishti-console/config/application.yaml`; **OPS** = `docs/guides/OPERATIONS_GUIDE.md` (the section "Production deployment (Linux)"
starts at line 3528); **CONF** = `docs/admin/CONFIGURATION.md`.

## 1. Result

| Severity | Count | Sonnet lane-hours |
|---|---|---|
| Blocks rollout | 5 | 58 |
| Risky | 15 | 117 |
| Polish | 6 | 8 |
| **Total** | **26** | **183** |

What is already right, so it is not listed as a gap: temporary files, logs and dumps stay under the data folder (`TempFiles`,
`JvmTempDir`, `core/tmpdir.py`); the loaders (`tools/loaders`) signal a server only when `--signal URL` is given; cluster addresses
refuse `localhost`; CORS is an allow-list of registered origins; cookies are `Secure`, `HttpOnly`, `SameSite=Lax` by default; secrets
shorter than 32 bytes stop the start; retired setting names stop the start (`RetiredNames`); readiness, liveness, drain-on-stop and the
disk guard exist; Drishti is an MCP client only (no MCP server code in the tree); AI, MCP, Flight and the data inbox are off as shipped.

## 2. Ranked gaps

Tracks: **PROD** = a new production-standard lane of the i8 close-out; **S1** = table catalogue (`i8/catalogue`); **R2** = engines cut;
**H2** = module rationalisation; **LOAD** = `i8/loaders`; **DATA** = `i8/testdata`; **CON** = console boundary (roadmap);
**i10** = RBAC and controls.

### Blocks rollout

| # | Area | Evidence | Fix in one line | Size | Track |
|---|---|---|---|---|---|
| B1 | Config, identity: unsafe defaults and no production mode | app.yaml:377 `security.enabled` default `false`; :1055-1060 `seed-admin` default `true` with user `drishti-dev-admin` and a known password; :301 `DRISHTI_DEMO_ENABLED` default `true`; :215 and :222 no licence file means every product; :214 engine trust default `folder`; console.yaml:161 `auth.enabled` default `false`, :19 bind `0.0.0.0`. The only production check in main code is `kernel/drishti-packs/.../LaunchRoots.java:103` (refuses module dev mode). A production start is safe only if the operator sets about eight variables by hand (OPS 4.4, 4.5, 12) | One production mode (a setting, set in `prod.ini`) whose start check refuses: sign-in off on server or console, the built-in seed credentials, demo on, SQLite with a cluster, no licence file, engine trust `folder`, a console on `0.0.0.0` without TLS in front | 10 | PROD |
| B2 | Packaging: no binary release | OPS:3595 "There is no binary archive: the release is built on the machine once with `build_all.py`", as root, needing a JDK, Maven libraries and a company mirror or a filled `~/.m2` (OPS 3, step 1); `drishti-platform/make_bundle.py` builds only `drishti-platform-<release>-source.zip`. A bank's production host has no compiler, no source and no Maven | An install archive per release (server jar, `.dmod` modules, console, the operator tools, config templates, engine jars, checksums) built by CI from the tags; the guide installs it and never builds | 14 | PROD |
| B3 | Config: site configuration lives inside the release folder | CONF:443 `drishti.config.dir` default `<home>/config`; Admin writes there (app.yaml:201 engines, :205 modules, :277 net, :342 connectors; `config/engines/*.yaml` carry `changed: {by, at, reason}` "written by the server"); OPS:3695 makes the release folder root-owned and not writable by the service user, so those Admin writes cannot succeed in the guide's own layout; the upgrade is `diff -r` of two config folders (OPS:4251) and the backup is "the site's changes under the release's `config/`" (OPS:4203) | Shipped defaults read-only in the release; one site folder (`/etc/drishti/config`, named in `prod.ini`) holds every site file and every Admin write; an upgrade never touches it | 12 | PROD |
| B4 | Data: table locations are still by convention | On `i8/catalogue`: `docs/admin/TABLE_CATALOGUE.md` lists 183 tables, every one "by convention" (`<root>/<domain>/<table>`); `TableCatalogue.java` is a join helper, not a registry from symbolic name to (uri, format, contract): `of(...)` is handed `configured` by its caller; the guard `TableCataloguePathsGuardTest` matches variable names (`domain`, `namespace`) and Java only; the Python tools keep their own layout (`tools/lake/lakeconfig.py` `default_root`: `<data>/delta/<name>`, `locate`); no test binds a lake whose tables sit at arbitrary external paths and checks each schema against its contract | Make the catalogue the registry every reader and writer asks by symbolic name; add an external-lake acceptance test (per-table `uri:`, read-only, schema checked against the contract at start, mismatch shown in Admin) and the same guard for Python | 16 | S1 |
| B5 | Operations: upgrade and rollback of the Python libraries | OPS:4233-4247 runs the new release's two `pip install` commands into the one shared Python **before** the old console is stopped (step 2, stop is step 4), so the running console's libraries change under it; OPS:4267 rollback says the previous release's "environment is still in its folder", which is no longer true (no environment is made); OPS 3 says a release with different pins "changes it for the others" | One Python prefix per release (operator-made, named in `drishti.python`), installed before the switch, kept for rollback; the guide's upgrade and rollback rewritten and tried once on a scratch machine | 6 | PROD |

### Risky

| # | Area | Evidence | Fix in one line | Size | Track |
|---|---|---|---|---|---|
| R1 | Network: implicit `localhost` targets | `tools/drishti_parser.py:24` `DEFAULT_SERVER = "http://localhost:18480"`, used by every server command (`tools/drishti.py:276`); `tools/lake/demo.py:90,541` signals `localhost:18480` when the data folder is the default; `tools/launch/nodecmd.py:52` drain defaults to `localhost`; `tools/doctor.py:131,327`; console.yaml:27 backend default `http://127.0.0.1:18480` | No default target outside development: the server comes from `--server`, the one variable or the settings file, else the command stops and says so | 4 | LOAD |
| R2 | Config: five names for the server's address | `DRISHTI_SERVER` (tools), `DRISHTI_SERVER_URL` (start console), `DRISHTI_BACKEND_URL` and `_URLS` (console), `DRISHTI_NODE_URL` (node), `DRISHTI_SIGNAL_URL` (deploy/aws) — `tools/settings_schemas.yaml:486,551,561`, `deploy/aws/env.example:50`, `drishti-console/core/settings_ini.py:162` | One setting and one variable; the others become retired names that stop the start | 3 | PROD |
| R3 | Secrets: four mechanisms | environment file `secrets.env` (OPS 4.2); `secrets.ini` (OPS 4.2); `${ENV}` references inside YAML (app.yaml:768, 783, 1105); `file:/run/secrets/...` (`config/lakes.examples/other/customer-lake.yaml`); the Flight private key defaults to a file under the config folder (`config/engines/flight.yaml:10-11`); the console unit loads the server's secrets too (OPS:3969: cluster key, seed password) | One documented way (a `file:` reference or an environment reference, resolved by one reader), one secret file per service, and the hook for a bank's vault agent | 8 | PROD |
| R4 | Packaging: three layouts, one unit | guide: `/opt/drishti/<release>/drishti` and `/srv/drishti/data`; `config/settings.examples/prod.ini`: `/opt/drishti` and `/var/lib/drishti/data`; `deploy/systemd/drishti-server.service` and `deploy/jvm/server-service.args`: `/opt/drishti/data`, no settings file, no `secrets.env`, no `NoNewPrivileges`; no console unit is shipped although OPS:3917 calls the server unit "the shipped" one | One layout; ship both units, the env templates and `server.args` as files, and a docs check that the guide's blocks equal them | 4 | PROD |
| R5 | Console boundary | console.yaml:210, 219, 230, 244, 257: `../docs`, `../tools`, `../data`, `../config/packs` relative to the repository; `drishti-console/core/lakes.py:14-24,104` runs `tools/lake/build.py` on the console's machine and writes the lake itself, default root `<data>/delta/demo`; `test_thin_console_guard.py` guards resolver and manifest reads only | The roadmap items as decided: a published-API-only guard, the lake build as a server job, the console as its own package with absolute settings | 20 | CON |
| R6 | Data inside packs | about 300 data files ship in module packs (`modules/banking/packs/trading`: 136 files, 11.6 MB, `samples/trade.jsonl`, `tests/*/cases.jsonl`; market-risk 25; world-economy 25; `config/packs/core` 18); they are served by the demo plugin, which is the default route (app.yaml:297-301) | Samples move to `data/testdata` and load into the lake; packs keep declarations only; the demo plugin is off unless a demo profile turns it on | 12 | DATA |
| R7 | Kernel names engines | app.yaml:43-63 imports `engines/var.yaml`, `exposure.yaml`, `frtb-sa.yaml`, `simm.yaml`, `mr-cube.yaml` by name; :285 readiness "engines (VaR, exposure)"; :654-672 risk routes listed in the kernel's token rules; 152 main files of `kernel/drishti-server` mention VaR, exposure, FRTB or SIMM | Engine files, routes and token rules come from the modules' manifests; a guard keeps engine names out of the kernel | 16 | R2 |
| R8 | Engines: no required or optional, no version range | `modules/banking/module.yaml:90-96` lists `- id: var` ... `- id: mr-cube` with nothing else; trust default `folder` and no-licence-means-everything (app.yaml:214-215); the production section of OPS (lines 3528-4330) never mentions `lib/engines`, the licence file or `trust: pinned` | Manifest entries `{id, required, version}` checked at start; production mode needs `pinned` and a licence; one guide section "Install the engines" | 8 | R2 |
| R9 | Operations: identity database upgrade with no migrations | app.yaml:1036 the complete schema file is applied at every start (`CREATE ... IF NOT EXISTS`); `kernel/drishti-identity/.../IdentityDatabase.java:226` then validates (`hbm2ddl.auto=validate` on PostgreSQL). A release that changes an existing table fails validation at start with no stated remedy; the service role must hold DDL rights | A start check that names each difference and prints the exact statements for the DBA; a mode where the DBA applies the schema and Drishti only verifies; a test that compares the schema files of two releases | 8 | PROD |
| R10 | Operations: no production acceptance check | `tools/doctor.py:237` `ACCEPT_USER = "drishti-dev-admin"`; OPS:4160-4161 and :4329 "`doctor --acceptance` is for a demo stack only ... in production it fails by design" | A production acceptance run with a token file: sign-in on, no default administrator, every catalogue table reachable with a matching schema, engines and versions, database, disk, cluster members | 8 | PROD |
| R11 | Security: row rules not proven everywhere | Enforcement exists (19 files in `kernel/drishti-server`, 6 in `kernel/drishti-engine-host`, 5 in `compute/drishti-bicompute`, 15 in the banking module against 14 controllers), but the guard tests cover token scope only (`TokenScopeGuardTest`); nothing asserts that every data route, export, Flight read, AI answer and engine output passes the gate. **Not verified route by route in this audit** | A route-inventory guard plus one matrix test: a row-ruled, masked user against API, CSV and workbook export, Flight, Ask and engine output | 10 | i10 (run before the i8 gate) |
| R12 | Data: loader staging in the system temp folder | `tools/lake/build.py:614,745` and `tools/lake/childload.py:94` call `tempfile.mkdtemp()` with no folder, so a large load stages in `/tmp` (often memory-backed) and outside the data folder | Stage under `<data>/tmp/loaders`, or a named work folder | 2 | LOAD |
| R13 | Network: plain HTTP between hosts; one shared signing key | OPS 7.4 and 8: console to server and node to node are HTTP unless the operator adds TLS; app.yaml:377-379 one symmetric `DRISHTI_TOKEN_SECRET` on every server and console ("whoever holds it can act as any user", OPS 4.2) | The standard requires TLS between hosts, and the production mode warns without it; asymmetric token signing goes to the i10 design | 3 | PROD |
| R14 | Data: bindings by pack and by module folder | `config/settings.examples/prod.ini` `[pack.market-risk] lake=risk`; lake files under a module's name (`config/lakes/banking/var-official.yaml`); TABLE_CATALOGUE.md: "a table's entry is added from its pack the first time a writer meets its kind" (a writer changes a lake file) | Bind by table name only; lake files are read-only in production | 6 | S1 |
| R15 | Data: official results follow the demo lake's variable | `modules/banking/java/banking-service/src/main/resources/drishti/lakes/var-official.yaml:20` and `ccr-official.yaml:20`, `engines/var.yaml:146`, `engines/exposure.yaml:74`: root `${DRISHTI_DELTA_ROOT:<data>/delta}`, the variable `config/lakes/demo.yaml` defines for the demo lake | Official-result tables are catalogue entries with their own configured location, required in production mode; the demo variable is used by the demo lake only | 5 | S1 |

### Polish

| # | Area | Evidence | Fix in one line | Size | Track |
|---|---|---|---|---|---|
| P1 | Config catalogue misses the deploy variables | Not in CONF: `DRISHTI_HEAP`, `DRISHTI_NODE_ID` (`deploy/docker/ecs/server-entrypoint.sh:48,98`), `DRISHTI_NET_SEEDS`, `DRISHTI_EDITION` (`deploy/aws/ecs/register.sh:23,35`), `DRISHTI_ADMIN_USER` and `_PASSWORD` (`deploy/docker/compose.yaml:17,33`), `DRISHTI_SIGNAL_URL`, `DRISHTI_TOKEN_SECRET_ID` (`deploy/aws/delta-lake/load-day.sh:69-72`), the connector examples' password variables | The generator also scans `deploy/` and `config/*.examples`; `--check` fails on a missing name | 3 | PROD |
| P2 | Wrong ports in examples | `config/net.examples/node.yaml:21` `:8480`; console.yaml:33 `https://drishti-a:8480` | 18480 everywhere | 1 | PROD |
| P3 | Development leftovers in shipped defaults | console.yaml:99-100 `user_display: Ash`, `desk: Rates desk`; app.yaml:844 `drishti@localhost`; :311 `http://localhost:9000/api`; `prod.ini` example carries `[lake.demo]` and `kafka-dev.bank.internal` | Neutral or empty defaults; the production example holds no demo entry | 1 | PROD |
| P4 | A mock in the main jar | `kernel/drishti-ai/src/main/java/com/ash/drishti/ai/MockMcpClient.java`, `config/mcp/mock.yaml.example` | Move to test sources | 1 | R2 |
| P5 | Console log | no log file or rotation setting found for the console (standard output only; OPS 10.1 relies on the journal) | State it in the standard; add the two settings only if a bank asks | 1 | CON |
| P6 | Two routes for one job during R2 | `config/engines/exposure.yaml:24` and `var.yaml:27` (`r2/exposure-routes`): `route: in-tree` or `engine` (both are in CONF) | Remove the switch at the R2 cut, as R2_CUT_PLAN says; keep it out of the production example | 1 | R2 |

**In-flight branches.** `i8/home` adds role-Home mappings and a console Home page: no new production gap found. `r2/exposure-routes`
adds P6 only. `i8/catalogue` is the right direction for B4, R14 and R15 but does not close them.

**Suggested order.** B1 and B3 first (they define the production mode and the site folder everything else refers to), then B4 with
R14 and R15 in the S1 lane, B2 with R4, then B5, R9 and R10 together as "upgrade and acceptance". R11 before the i8 Fable gate.

## 3. The one production-standard document

**Where: `docs/admin/PRODUCTION_STANDARD.md`**, not a section of OPERATIONS_GUIDE. Reason: the guide is a procedure (how to install,
4,300+ lines already); the standard is the short list of rules a change is judged by, read by lane briefs, the Fable gate and a bank's
reviewer. One home per fact:

| Fact | Its one home | The others link |
|---|---|---|
| The rules and who enforces each | PRODUCTION_STANDARD.md | lane briefs, gate checklist |
| The install, upgrade, backup commands | OPERATIONS_GUIDE "Production deployment (Linux)" | the standard links each rule to its section |
| Every setting, default and variable | CONFIGURATION.md (generated) | both link, neither repeats a table |
| Every table, its location and schema | TABLE_CATALOGUE.md (generated) | both link |
| The security checklist | PRODUCTION_STANDARD.md (moved from OPS section 12, which becomes a link) | |

**Outline** (target: under 250 lines; each rule one line with its check):

1. **Scope and the rule.** Production is Linux; what "production mode" is and how it is switched on (B1).
2. **The rules table** (the heart of the document): rule, why, enforced by (start refusal, `doctor` production acceptance, or a named
   guard test), link to the procedure. Generated from the production-mode checks where possible, with `docs generate --check`.
   - Data: symbolic table names only; location, format and contract from the catalogue; external lakes read-only; no data in packs;
     every write under the data folder or a configured table (B4, R6, R12, R14, R15).
   - Targets: no implicit server, host or port; one name for the server's address; real addresses in a cluster (R1, R2).
   - Configuration: every setting in CONFIGURATION.md; site files in the site folder only; resolved values visible in Admin; no
     development paths, users or personas (B3, P1, P3).
   - Secrets: the one mechanism, file modes, rotation, what each service may read (R3).
   - Identity and access: sign-in on, first administrator, password and lockout values, single sign-on, cookies, TLS between hosts,
     row rules and masks on every way out, audit (B1, R11, R13).
   - Modules and engines: pack is a switch, module is the delivery unit, engines required or optional with versions, pinned trust,
     licence, the kernel names no engine, MCP only through the sajha plugin (R7, R8).
   - Console: published API only, its own deployable (R5).
3. **The supported layout.** One tree (`/opt`, `/etc`, `/srv`), the shipped unit files and templates by name (R4, B2).
4. **Lifecycle.** Install from the archive, start order, health and readiness, logs and rotation, disk guard, backups and one tried
   restore, upgrade and rollback, the database with no migrations (B5, R9).
5. **Acceptance.** The production acceptance command, what it checks, what "ready" means (R10).
6. **The lane checklist.** Ten yes/no questions every lane brief and every Fable gate copies (does it work with a lake known only by
   path and schema; does it contact anything not named; is every new setting in the catalogue; ...).
7. **Not in scope.** Windows and macOS (development and QA), the demo profile, containers and AWS (`deploy/`), each with its link.
