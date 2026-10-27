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
# Troubleshooting

Each problem below has three parts: **what you see**, **how to check** (exact commands or pages), and **the
fix**. The commands assume the default ports: the server on `18480` and the console on `17480`, both on the
same machine. Replace `localhost` if yours run elsewhere.

**Quick index**

| Area | Problems |
|---|---|
| [First checks](#first-checks-run-these-first) | four commands that locate most problems |
| [Building and starting](#building-and-starting) | wrong JDK, port in use, packs not found, old version runs, console will not start |
| [Signing in](#signing-in) | no sign-in page, wrong password, locked, signed in but bounced back |
| [The console and the server](#the-console-and-the-server) | backend unreachable, `/readyz` says 503, the Live button has an amber ring, typing does nothing |
| [Commands and views](#commands-and-views) | cannot read command, *Nothing matches*, DRS-1001, may not open, no suggestions, No data available, pending links, blank charts |
| [Large books](#large-books-timeouts-grouped-pages-and-caches) | DRS-1004 on a trade, a counterparty or portfolio page slow or empty, grouped pages out of memory, only 1,000 trades listed, disk caches |
| [Pick lists and tables](#pick-lists-and-tables) | a pick list instead of the entity, only ids in the list, only 25 rows, keys do nothing |
| [Live and dates](#live-updates-and-business-dates) | view does not tick, no data held for a date, DRS-4003, known at has no effect, DRS-1007, DRS-1001 naming a store that holds the date |
| [Search](#search) | DRS-4004, empty results, partial results |
| [Studio and Sutras](#studio-and-sutras) | Save disabled, an edit has no effect, `.sutra.md` files after an upgrade, approval refused |
| [Packs](#packs) | mnemonics missing, a pack switched off, *not loaded*, cannot switch off, generated files out of date |
| [Connectors](#connectors) | a connector is idle, a connector failed to start, a bad connector file, TLS errors and certificate expiry, proxy errors (407, CONNECT refused, DNS), schema-registry messages skipped |
| [Monitoring endpoints](#monitoring-endpoints) | `/actuator/prometheus` answers 401 or 403, `/api/docs` answers 401 |
| [Alerts](#alerts-and-monitors) | an alert never fires |
| [Drishti*net](#drishtinet) | a node will not join, a refused key, clocks apart, a page whose node is down, a console that keeps moving between servers, a member that flaps |
| [Development](#development) | licence header test fails |

## First checks (run these first)

1. **Is the server up?**

   ```bash
   curl -s http://localhost:18480/actuator/health
   ```

   You should see `{"status":"UP","groups":["liveness","readiness"]}`. *Connection refused* means the server is
   not running, or runs on another port.

2. **Is the console up?**

   ```bash
   curl -s http://localhost:17480/healthz
   ```

   You should see `{"status":"UP"}`. This only says the console process answers.

3. **Can the console reach the server?**

   ```bash
   curl -s -w ' %{http_code}\n' http://localhost:17480/readyz
   ```

   You should see `{"status":"UP","server":"reachable"} 200`. While the console cannot reach the server it
   answers `503` with `{"status":"DOWN","server":"unreachable","detail":"…"}`; see
   [`/readyz` says 503](#readyz-answers-503-server-unreachable).

4. **What is loaded?**

   ```bash
   curl -s http://localhost:18480/api/v1/about | python3 -m json.tool | head -20
   ```

   This shows the `version`, the `java` runtime, `securityEnabled`, and (further down) every loaded Sutra, source
   and pack. For a one-page summary with problems counted, an admin opens **Admin → Health** in the console
   (`/admin/health`), or:

   ```bash
   curl -s http://localhost:18480/api/v1/admin/health | python3 -m json.tool | head -12
   ```

   You should see `"status": "OK"` and a `summary` such as
   `{"packsWithProblems": 0, "failedToStart": 0, "sourcesDown": 0, "sources": 18, "packs": 12}`.
   (With security on, this call needs an admin's token; use the console page instead.)

**Where the logs are.** Both programs log to standard output: the terminal where you started them. Under
Docker Compose, use `docker compose -f deploy/docker/compose.yaml logs -f server` (or `console`).

## Building and starting

### The build fails with "Drishti needs Java 21 or newer"

The rule checks only the version, not the vendor: Oracle JDK, Temurin and OpenJDK of version 21 or newer all pass. The message
names the Java version and folder Maven actually found (older builds printed "Drishti builds and runs on OpenJDK 25"
instead). When you have a JDK 21 or newer installed and still see it, Maven is running on another Java: usually `JAVA_HOME`
points at an older JDK, or an IDE uses its own JDK setting.


- **Check:** `./mvnw -v` prints the Java version Maven uses: it must be 21 or newer, and a JDK, not only a JRE (the build
  needs `javac`; on Ubuntu `sudo apt install openjdk-21.jdk-headless`).
- **Fix:** point Maven at a JDK 21 or newer (production runs 21) and build again:

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64      # Oracle JDK on Linux: e.g. /usr/lib/jvm/jdk-21-oracle-x64
  ./mvnw -v                                                 # must show Java version: 21 or newer and that folder
  ./mvnw -q verify
  ```

- **IntelliJ IDEA:** File → Project Structure → SDK = a JDK 21 or newer, and Settings → Build Tools → Maven → Runner → JRE =
  "Use Project JDK" (Maven run from the IDE uses that setting, not your shell's `JAVA_HOME`).

### The log says "sutra hot reload polls the Sutra files" (Health: `hotReload: POLLING`)

The operating system had no file watches left for the Sutra folders (Linux: *User limit of inotify watches reached*;
IDEs and other tools use many), so the server watches by polling every `drishti.rachana.watch` `poll-interval` (2 s)
instead. Edits are still picked up. To use file events again, raise the limit and restart:
`echo 'fs.inotify.max_user_watches=524288' | sudo tee /etc/sysctl.d/60-inotify.conf && sudo sysctl --system`.

### The server stops at once with `UnsupportedClassVersionError … class file version 65.0` (or higher)

- **Cause:** `java` on your `PATH` is older than 21.
- **Check:** `java -version`.
- **Fix:** run the jar with Java 21 or newer: `/usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar …` (on Java 25 you may add `-XX:+UseCompactObjectHeaders`; Java 21 refuses to start with it),
  or put that JDK first on your `PATH`.

### Tests on Java 21 fail with "has been compiled by a more recent version of the Java Runtime (class file version 69.0)"

- **Cause:** class files in `target/` were compiled for Java 25 by a build from before Java 21 support (when Drishti
  targeted Java 25 only). Maven's incremental build does not recompile them when only the target version changes, so Java 21
  finds Java 25 classes.
- **Fix:** once, after pulling: `./mvnw clean` (or IntelliJ IDEA: Build → Rebuild Project), then build again.

### On Windows: `HADOOP_HOME and hadoop.home.dir are unset` or `Could not locate executable winutils.exe`

A Delta connector is running on Hadoop's engine, which needs Hadoop's `winutils.exe` on Windows. Use the native
engine: unset `DRISHTI_DELTA_ENGINE` (it defaults to `native`) or set it to `native`, and remove any `engine: hadoop`
from the connector's settings. Admin → Health then shows `UP (engine: native)`. See [GETTING_STARTED.md (Windows)](GETTING_STARTED.md#drishti-on-windows).

### "Web server failed to start. Port 18480 was already in use."

- **Check:** who holds the port: `ss -ltnp | grep 18480` (or `lsof -i :18480`). Often it is an earlier Drishti
  server still running.
- **Fix:** stop the other process, or start this one on another port and tell the console where it is:

  ```bash
  DRISHTI_PORT=18481 java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.2-exec.jar
  DRISHTI_BACKEND_URL=http://127.0.0.1:18481 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
  ```

  The console's own port works the same way: `[Errno 98] address already in use` → `DRISHTI_CONSOLE_PORT=17481`.

### The server stops at start-up with "pack '…' not found at …/pack.yaml"

- **Cause:** a name in `DRISHTI_PACKS` has a typo, or the server cannot find the `config/packs/` folder. The default
  folder is `./config/packs`, relative to the directory you start the server from.
- **Check:** the message names the exact path it tried, for example
  `pack 'trading-risk' not found at /home/me/drishti/packs/trading-risk/pack.yaml`. Compare it with
  `ls config/packs/`.
- **Fix:** use the folder names exactly (comma-separated, no spaces needed), and either start the server from
  the repository root or give an absolute folder:

  ```bash
  DRISHTI_PACKS=trading,counterparty-risk DRISHTI_PACKS_DIR=$PWD/packs \
    java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.2-exec.jar
  ```

  A pack's parents load by themselves: enabling `trading` also loads `core` and `market-data`. Other
  start-up messages from the pack loader: "`… declares pack 'x', expected 'y'`" (the folder name and the `pack:`
  key in its `pack.yaml` differ), "`packs require each other in a cycle`", and "`… is defined by both pack 'a'
  and pack 'b'`" (two unrelated packs claim the same mnemonic or kind). See [PACKS.md](PACKS.md).

### A pack you expected is missing

- **Cause:** `DRISHTI_PACKS` was set for the server process to a list without it (unset, it is the seven default packs,
  `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets`), or the pack is a retired example under `examples/packs/` that `DRISHTI_PACKS_EXTRA_DIRS` does not name.
- **Check:** `curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" http://localhost:18480/api/v1/packs | python3 -c "import json,sys; print([p['name'] for p in json.load(sys.stdin)])"`.
- **Fix:** restart the server with the list you want, for example
  `DRISHTI_PACKS=core,market-data,trading,market-risk,counterparty-risk`, or press **Load** in Admin → Packs.

### An old version runs, or the wrong jar starts

- **Cause:** `drishti-server/target/` holds jars from several builds, and `java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-*-exec.jar`
  expands to all of them; Java runs the **first** (the oldest, alphabetically) and ignores the rest.
- **Check:** `curl -s http://localhost:18480/api/v1/about | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])"`
  and `ls drishti-server/target/*-exec.jar`.
- **Fix:** name the jar exactly (`drishti-server-2.9.2-exec.jar`), or delete the old ones.

### The console will not start: `ModuleNotFoundError: No module named 'fastapi'`

- **Cause:** it was started with the system Python, not the console's virtual environment.
- **Fix:**

  ```bash
  uv venv drishti-console/.venv && uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
  drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
  ```

### The console starts but cannot be opened from another machine

- **Cause:** it listens on every interface (`0.0.0.0`) by default, so the usual cause is a firewall on the console's machine, or
  `DRISHTI_CONSOLE_HOST=127.0.0.1` set somewhere (an environment file, `config/application.local.yaml`, a run configuration).
- **Fix:** open port 17480 (`sudo ufw allow 17480/tcp` on Ubuntu), or unset `DRISHTI_CONSOLE_HOST`. Open it by the machine's
  address (`http://<ip>:17480`; `hostname -I` lists them), and, for anything beyond a trial, turn security on first (see
  [OPERATIONS.md](../admin/OPERATIONS.md)).

## Things that failed to load or lost their connection

Packs, connectors, AI providers and MCP servers that fail are **tried again by themselves** (exponential backoff: 5 s, 10 s, 20 s, ... up to 5 min), so most
problems clear on their own once the cause is fixed. Admin → Packs, Connectors and AI and the Health page show *failed — next retry in Ns (attempt k)* and the last
error on the row ([Retries](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself)).

### A pack is "not loaded" and the problem names another pack

A kind, mnemonic or role that two packs both define is refused for the one that came second, and the first keeps running. Remove or rename the clash in either
pack (or unload the other); you do not need to touch the refused pack's folder or restart: it is checked again when the folder changes and on the retry schedule, and it loads as soon as
the clash is gone. To check now, press **Retry now** on Admin → Packs (`POST /api/v1/admin/retry/packs`). If it never retries, `drishti.packs.watch` is `off` (that is the timer): use Retry now.

### A connector is FAILED and "next retry in 5 min" does not change

The error was classed **permanent** (credentials refused or a setting that is wrong, three times in a row), so it is tried only every `drishti.retry.connectors.max`. Fix the
settings in the connector file (saving starts the count again) or press **Retry now** after you fixed the cause (`POST /api/v1/admin/retry/connectors?name=<connector>`).

### A connector shows "connection lost" although the store is up

The plugin reports `DOWN` in its health. After three looks it is started afresh; if it still says DOWN the plugin cannot reach the store from this machine. Test the connection from
Admin → Connectors (Test connection shows the plugin's own message).

### A connector says "no free connection in the … pool"

The full message: `no free connection in the trade-endur pool within 10000 ms (20 of 20 in use, 3 waiting, longest borrow 41500 ms); raise pool-size or find
what holds connections`. Every connection of that connector's pool (`pool-size`, 20 by default) was borrowed for longer than `pool-connection-timeout` (10 s).
Open the connector's page (Admin → Connectors): *Connection pool* shows in use, waiting and the **longest current borrow**.

- A **long borrow** (tens of seconds) is a slow query or a database that stopped answering: look at the database's own `pg_stat_activity` for the statements, or the
  connector's `Last error`. `statement-timeout` (5 minutes) ends a statement that never returns; lower it for a connector whose queries should be quick.
- **Many short borrows and many waiting** is plain load: raise `pool-size` for the connector (or `drishti.db.pool.size`), but check the database first
  ([OPERATIONS.md, Sizing the database connection pools](../admin/OPERATIONS.md#sizing-the-database-connection-pools)): `FATAL: sorry, too many clients already`
  means the database's `max_connections` is smaller than the pools together.
- `scan-threads` larger than `pool-size` makes a day's column read queue behind itself; keep `pool-size` at `scan-threads` plus the reads you expect.

### The log says "connection leak suspected in the … pool"

A connection was borrowed for longer than `pool-leak-threshold` (60 seconds) and has not been returned. The line names the thread and carries, as an exception,
the **stack of the code that borrowed it**: the first frames are the pool's, the next are the borrower's. Most often it is a legitimately long statement (a
bulk query, a maintenance task); if it is, raise `pool-leak-threshold` for that connector. If the same line keeps coming for short work, it is a bug: a borrow not
in a `try`-with-resources. Report it with the stack. The count is on the connector's page and in `drishti_db_pool_leaks`. The connection is returned
when its borrower finishes; nothing is closed from outside.

### An AI provider shows ERROR, an MCP server shows DOWN

ERROR: the provider or server could not be set up (type or transport not installed, an `${ENV}` value not set); fix it and it is built again by itself, or press **Retry now** on Admin → AI.
DOWN: the last call failed; the MCP client is closed and connected afresh on the schedule, and a provider is simply tried again by the next call. With `drishti.ai.watch: off` (or
`drishti.mcp.watch: off`) the folder is not looked at and nothing is retried: use Retry now.

### An AI provider answers "authentication failed" (HTTP 401) or "access denied" (HTTP 403)

The message is `the model endpoint answered HTTP 401 (authentication failed: check the api key and where it is read from)`; the body of the answer is never shown. Check, in this order: the
variable the file names is set **in the process that runs Drishti** (`api-key: ${GROQ_API_KEY}`; a preset also reads the vendor's own variable, and Admin → AI says `no api key: set GROQ_API_KEY` while it is
missing); the key belongs to the vendor and base URL in the file (`base-url` of an Azure resource, not a key for another); for Azure with `auth: entra` the token request is refused (`entra: the token request
was refused (HTTP 401)`: tenant, client id and secret); for Bedrock `HTTP 403 ... (authentication failed or access denied)` is the credentials, the region or the account's model access (enable the model in the
Bedrock console), and `no AWS credentials` means the SDK's chain found none (set `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, a `profile`, or run with a role). `TLS certificate CN=… (tls.cert-file) expires in N days` in the health text is
the nudge to rotate `tls.cert-file`.

### An AI provider answers "rate limited" (HTTP 429)

The call fails with kind `RATE_LIMITED` (a model-layer error, shown by the feature that called, not a DRS code of its own). It is the vendor's limit for the key, not Drishti's: raise the vendor limit, spread features over
two providers, add `retries: 2` to the provider file (a 429 is tried again, a 401 is not), or lower Drishti's own per-user limits so one person cannot use the quota
([AI_PROVIDERS.md section 4](../architecture/AI_PROVIDERS.md#4-policy)). Bedrock throttling (`ThrottlingException`) is the account's tokens-per-minute quota for the model in that region.

### An AI provider says "could not be reached" with a private CA, or "the proxy refused the request"

`tls.ca-file` names the CA that signed the **server's** certificate (not the client's); with mutual TLS add `tls.cert-file` and `tls.key-file`; a certificate with only a Common Name fails the name check, so
re-issue it with the host as a subject alternative name ([TLS.md section 11](../connectors/TLS.md#11-troubleshooting)). Behind a proxy, `proxy.url`, `proxy.username` and `proxy.password` (an `${ENV}`) are the
file's or the site's; the proxy must allow `CONNECT` to the vendor's host.

### Too many retry lines in the log

The log gets the first failure and failures number 2, 4, 8, ... of each thing, never one line per try. To retry less often, raise `drishti.retry.max`
(per area: `drishti.retry.connectors.max`); to stop retrying, set `drishti.retry.enabled: false` ([CONFIGURATION.md](../admin/CONFIGURATION.md#retry-settings)).

## Signing in

### There is no sign-in page, and no users or roles apply

- **Cause:** this is the local-development default: security is off on the server
  (`DRISHTI_SECURITY_ENABLED=false`) and sign-in is off in the console (`DRISHTI_AUTH_ENABLED=false`).
  `curl -s http://localhost:18480/api/v1/about` shows `"securityEnabled": false`.
- **Fix:** to sign in with users and roles, turn both on and share one secret:

  ```bash
  export DRISHTI_TOKEN_SECRET='at-least-32-characters-shared-secret!!'   # same value for both
  DRISHTI_SECURITY_ENABLED=true java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.2-exec.jar
  DRISHTI_AUTH_ENABLED=true DRISHTI_SESSION_SECRET='another-secret-of-32-characters-or-more' \
    drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
  ```

  Then sign in at `http://localhost:17480/login` as **`drishti-dev-admin`** / **`drishti-dev-admin123`** and
  change the password at **My account** (`/account`). The admin pages warn until you do. See
  [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

### The console will not start: "auth.session_secret must be at least 32 characters when auth is enabled"

- **Fix:** set `DRISHTI_SESSION_SECRET` to 32 characters or more. The server likewise refuses a
  `DRISHTI_TOKEN_SECRET` shorter than 32 bytes when security is on.

### "Unknown user or wrong password."

- **Check:** the user name is exact (`drishti-dev-admin`, not `admin`). An admin can see failures in
  **Admin → Audit** (`/admin/audit`).
- **Fix:** an admin uses *Reset password* in **Admin → Users** (`/admin/users`). The development admin is created
  only when there are no users yet; if someone has already changed its password, ask them.

### "This account is locked after repeated failures."

- **Cause:** 5 failed sign-ins lock an account for 15 minutes (error `DRS-6005`).
- **Fix:** wait 15 minutes, or an admin resets the password in **Admin → Users**, which also clears the lockout.

### "Sign-in unavailable: …"

- **Cause:** the console cannot reach the server, or `DRISHTI_TOKEN_SECRET` differs between them, so the server
  refuses the console's service token. Clocks more than 30 seconds apart also cause it.
- **Fix:** run the [first checks](#first-checks-run-these-first); set the same secret in both environments; check
  the clocks (NTP). More in [runbooks/sign-in.md](../admin/runbooks/sign-in.md).

### I sign in and land back on the sign-in page

- **Cause:** the session cookie is marked *secure* (HTTPS only) by default. Browsers accept it on
  `http://localhost`, but not on plain HTTP to another host name or IP address.
- **Fix:** use HTTPS (the production setup), or, for a trial on a private network only, start the console with
  `DRISHTI_SECURE_COOKIE=false`.

### API calls return `DRS-5010 sign in first`

- **Cause:** your session expired (sessions last 10 hours) or you are not signed in.
- **Fix:** sign in again at `/login`.

## The console and the server

### Pages say `DRS-5003 backend unreachable`

- **Cause:** the console cannot reach the server (not started yet, or restarting).
- **What you see:** a bar at the top of the page, "Server not reachable - retrying...", with **Retry now** (and a dismiss
  cross). Nothing to do: the page asks the console every 2 to 30 s and, when the server is back, shows "Connected again" and
  reloads by itself. A page with unsaved changes is not reloaded; the bar offers **Reload** when you are ready. The console's
  own check runs every `backend.check_seconds` (default 5); Admin > Health shows it, and `GET /healthz/backend` is the raw state.
- **Check:** `curl -s http://localhost:18480/actuator/health`. If that works from your shell, the console may be
  pointing elsewhere: the console reads `DRISHTI_BACKEND_URL` (default `http://127.0.0.1:18480`).
- **Fix:** start the server, or restart the console with the right `DRISHTI_BACKEND_URL`.

### `/readyz` answers 503 ("server": "unreachable")

- **What you see:** `curl -s http://localhost:17480/readyz` prints
  `{"status":"DOWN","server":"unreachable","detail":"…"}` with HTTP status 503, while `/healthz` still says
  `{"status":"UP"}`. A load balancer or Kubernetes takes the console out of service; pages that load show
  `DRS-5003 backend unreachable`.
- **Cause:** the console runs, but cannot reach the server. `/healthz` is the cheap *liveness* check (is the
  process alive? restarting it would not help); `/readyz` is the *readiness* check (should traffic come here?),
  and asks the server for today's business date each time.
- **Check:** the `detail` text names the problem (a refused connection, a timeout, a bad token). Then
  `curl -s http://localhost:18480/actuator/health` from the console's host.
- **Fix:** start the server, or point the console at it with `DRISHTI_BACKEND_URL`; with security on, give
  both the same `DRISHTI_TOKEN_SECRET`. The console becomes ready by itself; no restart is needed. In
  Kubernetes, use `/healthz` for the liveness probe and `/readyz` for the readiness probe (see
  [OPERATIONS.md](../admin/OPERATIONS.md)).

### The Live button has an amber ring ("Reconnecting…")

- **What you see:** the dot among the round tools of the top bar turns amber, and values stop changing.
  Hover over it, or use a screen reader, to read *Reconnecting…*.
- **Cause:** the live stream dropped (server restarted, network blip), or could not be opened within 10 seconds.
  The page itself keeps working: it never waits for live updates.
- **Fix:** nothing to do: the browser reconnects by itself and repaints from a fresh view. If it never
  recovers, run the first checks. If only an older tab stays amber after you signed in again (or picked another
  server) in another tab, that tab belongs to the previous session: reload it.

### The Live button has an amber ring after the tab was in the background ("Paused while hidden")

- **Cause:** the tab was hidden for 10 seconds and gave its live subscriptions back, to save resources.
- **Fix:** show the tab: it takes them back and repaints.

### A view says "Static" although it ticks in other tabs, with very many tabs open

- **Cause:** one browser follows at most `live.max_subscriptions` (32) live things at once, across all its tabs and
  panes; a view open in several tabs counts once. The one over the limit is told so (`DRS-5003 this browser
  already follows 32 live subscriptions`) and shows `Static`.
- **Fix:** close some live tabs or panes and reload it, or raise `live.max_subscriptions` in the console's
  configuration (keep it well below `backend.pool_size`).

### Pages do not load at all, or typing does nothing (no suggestions), with several live tabs open

- **What you see:** with several tabs on live views (several screens on a desk), a new tab of the console never
  loads, with no message, or the command line shows no suggestions; closing one live tab frees it at once.
- **Cause:** browsers open at most six connections to a site over HTTP/1.1, and older consoles held one live
  connection per tab (before 1.10, one per view and one for the bell). Now each **browser** holds one live
  connection, shared by all its tabs and panes (see [LIVE.md](../architecture/LIVE.md#one-connection-per-browser)).
- **Fix:** hard-refresh old tabs once (**Ctrl+Shift+R**) so they load the new scripts. To check, run
  `DrishtiChannel.transport()` in the browser console of any tab: `shared-worker`, `leader` or `follower` means the
  tab shares the browser's connection; the network panel shows a single `/api/channel?s=…` request for the whole
  browser (in the SharedWorker's own panel, `chrome://inspect/#workers`, when it runs there).

### The email never arrived

A share reached the bell but not the mailbox. Work down the list; each row is one cause.

| Symptom | Cause | Check and fix |
|---|---|---|
| The share answers `503 DRS-7012`, or the dialog offers no email | Email is off | It needs `drishti.collab.email.enabled`, `spring.mail.host` and `drishti.collab.console-url`, and sign-in on. `GET /api/v1/admin/collab/outbox` shows `available`. |
| A Teams, Slack or webhook channel gets nothing | The bridge is not usable, the route does not match, or the post is waiting or dead | `GET /api/v1/admin/collab/bridges`: `status` is `ok` or says which variable is unset or that the URL is not under `bridges.allow`; check the route's `packs`, `kinds` and `events`; `GET /api/v1/admin/collab/outbox` lists the bridge's rows (recipient = the bridge name) with the reason. Fix it, then `POST .../outbox/{seq}/retry`. |
| The bridge post says `the endpoint refused the post: HTTP 4xx` | The URL is wrong or revoked (a Slack app removed, a Teams workflow turned off) | Make a new incoming webhook, set the variable, restart; the row is a dead letter and is sent again with retry. |
| A post says `HTTP 3xx (a redirect)` | Redirects are never followed (the URL's secret would go to the new host) | Use the final URL in the variable. |
| The chat message shows `•••` where I typed a value | The post is written as the `bridges.render-as` role would read it (the bundled `viewer` has no `raw`); quotes are filled for that role, masked fields stay `•••` | Intended. Link people to the view; they see their own values, or name a raw role in `render-as` for a restricted channel. |
| The server stops at start with "drishti.collab.email.enabled needs drishti.security.enabled" | Sign-in is off | User names are not verified, so mail is refused. Turn security on, or email off. |
| The dialog said "2 of 8 have no address" | The person has no email in their account | An administrator sets it under Admin → Users. People cannot add their own. |
| One person never gets mail | They turned it off, or are at the hourly cap | `GET /api/v1/me/settings` shows `notify.email.share`; the cap is `limits.mails-per-recipient-per-hour` (the server log says "not queued"). |
| Rows stay `pending` with `attempts` rising and a `lastError` such as "Could not connect" | The mail server is unreachable or stalled | Fix the host, port, firewall or TLS; rows go out by themselves. Set the smtp timeouts so a stalled server cannot hold the worker. |
| A row is `dead` with `550` or "Invalid Addresses" | The server refused the address | Correct the address, then `POST /api/v1/admin/collab/outbox/{seq}/retry`. |
| A row is `dead` after the last attempt | The outage outlasted `outbox.max-attempts` of backoff | Retry it once the server is back; raise `max-attempts` or `max-backoff` if outages are long. |
| A row is `cancelled` ("can no longer open", "no valid address") | By design: the person lost the right to the view, was disabled, or the address is malformed | Nothing is sent to someone who should not see it. |
| Nothing is sent and rows stay `pending` with no attempts | No server is dispatching | `outbox.enabled` is false on every server, or the server was started with email off. |
| The mail is in spam | Missing sender authentication | Use a From address your mail server may send for (SPF/DKIM): `drishti.collab.email.from`. |
| The message has no id or note | The pack is `link-only` | `drishti.collab.packs.<pack>.email.content`, or `email.content: link-only`. |

### A shared link opened different data, or a comment shows other numbers

A share and a comment each **pin** the date you were looking at. What a reader gets depends on the source (the pin is evidence, never an address):

| What the reader sees | Cause | What to do |
|---|---|---|
| Banner "You are seeing 29 Sep as known at ..., as it was shared" | Working as designed: a source that keeps versions (Delta Lake, Iceberg) | Nothing. **Go live** drops the pin for that page only |
| Banner "the latest data for 29 Sep: this source keeps no earlier versions" | The dated store has the day but not versions (`DRS-1007`) | By design; the figures are the latest for that date |
| Banner "You are seeing ... live; the data has changed since it was shared (generation 3 to 5)", with **What changed** | The source is undated or live: the page is today's | By design; **What changed** compares with the moment it was shared |
| "No data held for 2026-09-30: no dated store has MX-20000001 for that date" | No store holds that day (the demo trade's source keeps no dates) | The sender picked a date the data never had; see the *DRS-4003* entry below for the history window |
| A comment's **Open as it was** is missing | The page already shows the comment's date and generation | By design |

### Someone was not notified, or cannot open the share

The dialog (and the reply to `POST /api/v1/shares`) lists who was skipped and why. The same reasons apply to a mention in a comment.

| Reason shown | Cause | What to do |
|---|---|---|
| "may not open trade views" | Their role does not open that kind | Give the role the kind (`roles:` in the pack, or the role under Admin). Meanwhile they get nothing, not even "someone mentioned you" |
| "does not have the pack for trade views" | The pack is not assigned to them | Assign it under Admin → Users. A pack that is assigned but switched off is delivered: the page offers **Switch on** |
| "account is disabled" | The person is disabled | Enable them, or share with someone else |
| "some members may not open ... views" | A role was addressed and some members cannot open the kind | Expected; the others were told. Under `share.undeliverable: silent` the sender is not told |
| No such name in the picker, or `DRS-7002` | The directory lists only people who share a pack with the sender, from two letters | Ask an administrator, or set `drishti.collab.directory.scope: all` |
| "A shared view you cannot open" | The recipient lost the right after the share was sent | By design: a plain page with the sender and the date, nothing about the data; the inbox row stays |
| `404` on `/share/sh_...` | The link is not for that person, or was cut in copying | Ask the sender to share it again (a stranger cannot learn that a share exists) |
| `429 DRS-7003` on a share, reply or search | Too many in a minute | Wait the `Retry-After` seconds; the limits are `drishti.collab.limits.*` |

Email is a separate channel: [The email never arrived](#the-email-never-arrived). A thread you cannot see is `DRS-7005` (a thread follows the right to
open its entity); a comment past its edit window is `DRS-7008`; the whole list is under [Error codes](#error-codes).

## Alerts on numbers

An alert that never fires, fires twice, or tells too much. Work down; each row is one cause. The alert's **history** (the edit page: `/alerts/measure/<name>`) says what every look did and why; *Check now* in the form, or `POST /api/v1/me/measure-alerts/preview`, says what the alert reads for you right now.

| Symptom | Cause | Check and fix |
|---|---|---|
| It never fires | The number is not past the limit **for you** (a row rule shows you less than you expect) | *Check now* shows your number. An alert is judged on **your** rows, not the report's author's |
| The history says `skipped: masked for you, so it cannot be judged` | The measure is masked for your role | By design: it is never judged or shown. Ask for the access, or watch another number |
| The history says `skipped: there is no earlier number to compare with yet` | A change alert's first look | It only sets the starting point; the next look compares |
| The history says `skipped: … the earlier number is zero` | A percentage change from zero | Use `abs`, or a threshold |
| The history says `skipped: no data yet (…)` or `could not be read: …` | The source returned no rows, a masked-everything answer, or an error (a dataset that no longer parses, a report you lost) | Open the report or dataset: the same message shows. The alert resumes by itself when the source reads again |
| It fired once and never again | It is **active**: it fires again only after the number is back inside the limit by the hysteresis, and after the cooldown | The list shows `active`. Lower the hysteresis, or press *Pause* then *Resume* to arm it afresh |
| The history says `held`: `held back by the cooldown until …` | A second breach inside the cooldown | It fires when the cooldown ends if the condition still holds. Set `cooldown` (`0` for none) |
| An after-load alert does not look after my load | The load was for another kind (`trigger.kind`), or the load did not finish `ready`, or `load-delay` has not passed | [DATA_LOADS.md](DATA_LOADS.md); the history's `trigger` says `load` when a load looked |
| A live alert never looks | Nothing changed, or the source is not live | The preview says `"live": false` for a source with no live table; a live alert looks `debounce` after the last change |
| A "no data" alert fired on a weekend or holiday | It cannot: it only fires on business days of the server's calendar, from `by` in the business-date zone | Check `by` and the zone ([DISTRIBUTION.md section 4.2](DISTRIBUTION.md#42-when-it-fires-the-condition)) |
| It fired but nothing is in the inbox | Inbox was off for this alert, or `inbox.keep` pruned the row | The history's `delivered.inbox` says `ok`; the *Alerts* tab of the inbox lists the rows |
| `delivered.email` says `skipped: no email address on file` | Your account has no address | An administrator sets it under Admin, Users (people cannot add their own) |
| `delivered.email` says `retrying: attempt 1 of 4 failed (…); next try in 5 s`, then `sent (attempt 3 of 4)` | The mail server was unreachable and the shared retry policy (`drishti.retry.alerts.*`) is trying again; `emailAttempts` counts them | Nothing: it settles. A restart drops a pending retry (the history keeps the last text) |
| `delivered.email` says `failed after 4 attempts: …` or `failed (permanent): … (not retried)` | The server stayed unreachable for `drishti.alerts.measure.max-attempts` tries, or refused the address (SMTP 5xx) | Fix `spring.mail.*` or the address on the account; the next firing is a new mail |
| `delivered."webhook:ops"` says `failed (permanent): … allowed-hosts` | The webhook's host is not on `drishti.notify.webhook.allowed-hosts` (checked at every send), or its URL is `http://` without `webhook.allow-http` | An administrator adds the host (`hooks.corp.example` or `*.corp.example`) |
| `delivered."webhook:ops"` says `failed (permanent): … is not set` | The `${ENV}` variable named in the webhook's `secret` or a header is not set in the server's environment | Set it and restart; the message names the variable, never a value |
| `delivered."webhook:ops"` says `failed: the webhook answered HTTP 503` (retrying) or `failed (permanent): … HTTP 403` | The receiver is down (retried), or refused the post (a 4xx other than 408 and 429 is not retried) | Check the receiver; it verifies `X-Drishti-Signature` (HMAC-SHA256 of `timestamp.body` with the secret) and the timestamp |
| `delivered.sms` says `skipped: no phone number on file` | The owner has not given a number | `PUT /api/v1/me/phone` or type it in the alert or Subscribe form; the alert cannot be saved with texts on and no number |
| `delivered.sms` says `failed (permanent): the SMS gateway answered HTTP 401` | The gateway refused the credential in `sms.header.*` | Check the `${ENV}` credential; `drishti.notify.sms.*` |
| A text is cut off | `sms.max-chars` (160) | Raise it if the gateway takes more; a text holds only a title and a link |
| `delivered.bridge` says `skipped: no bridge route lists the alert event` | No route has `events: [alert]` (or its `kinds` exclude the alert's: a Sutra kind for a field alert, `bi` for the rest) | [CONFIGURATION.md](../admin/CONFIGURATION.md#drishticollab--share-with-a-note-and-the-inbox) |
| `delivered.bridge` says `skipped: already posted for this alert within its cooldown` | One post per alert per cooldown (a minute at least) | By design: a bridge is a room |
| A board tile never notifies | `notify` needs a threshold of its level (`crit` needs `crit`), the board is not **published**, the trigger waits for a load, or **nobody can read it** | Validate the board (`POST /api/v1/bi/boards/validate`); see `/alerts/all` for the tile, who is alerted now and its history |
| The history of a board tile says `this tile would tell 412 people, more than 300` | More readers than `max-audience` | Narrow the board's audience, or raise the setting |
| The same person is told twice by a tile and their own alert | They are different alerts | Pause one |
| Several servers all look | The evaluator runs on each | `drishti.alerts.measure.enabled=false` on all but one |

## Commands and views

### "DRS-4001 cannot read command '…'; type <MNEMONIC> <ID> <GO> with a mnemonic such as …"

The message names up to six of the mnemonics the server has loaded (from its packs), and how many there are in all.
"*… but no mnemonics are configured: is a pack loaded?*" means no pack defines any.

- **Cause:** the first word is not a known mnemonic, or you typed a mnemonic with no identifier, or a bare
  identifier whose pattern no pack recognises.
- **Check:** type the first letter of the mnemonic and look at the dropdown, or ask the server:

  ```bash
  curl -s "http://localhost:18480/api/v1/command/suggest?q=TR" | python3 -m json.tool | head
  ```

  An empty list (`[]`) for every prefix you try means the pack that owns that mnemonic is not enabled, or
  not chosen (see [Packs](#packs)).
- **Fix:** use the form `MNEMONIC ID`, for example `TRD MX-20000001 <GO>` (case does not matter; `<GO>` is optional,
  Enter is enough).

### *Pick a trade*: "0 of 0 trades match" and "Nothing matches."

- **What you see:** you typed `TRD MX-29999999 <GO>` and got a page headed *Pick a trade* instead of a trade.
- **Cause:** the mnemonic is fine, but no trade has that id, no trade's id starts with it, and no title
  contains it. A command that does not name exactly one entity becomes a pick list, and this one is empty.
- **Check:** type less of the id (`TRD MX-2`) and see what the list holds; or ask the server directly:

  ```bash
  curl -s -G http://localhost:18480/api/v1/search --data-urlencode 'q=TRD MX-2 limit 5' \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["matched"], [r["ref"]["id"] for r in d["rows"]])'
  ```

  You should see `750 ['MX-20000001', 'MX-20000002', 'MX-20000003', 'MX-20000004', 'MX-20000005']` on the banking samples.
- **Fix:** correct the id. If the list is empty for every id you try, the source that holds the kind may be
  down (see [runbooks/source-down.md](../admin/runbooks/source-down.md)), or you picked a past date the source has no
  data for.

### "DRS-1001 no source holds trade/MX-29999999"

- **Cause:** you opened a view address directly (`/v/trade/MX-29999999`, a bookmark or an old share link), and no
  source has that identifier. (From the command line you get an empty pick list instead; see above.)
- **Check:** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18480/api/v1/views/trade/MX-29999999` prints `404`.
- **Fix:** correct the id. The example commands for each pack are on the terminal home and in each pack's guide
  under **Help → Domain packs**.

### "DRS-5002 … may not open … entities"

- **Cause:** your roles do not include that kind, or the pack that owns it is not among your packs.
- **Check:** `curl -s http://localhost:18480/api/v1/me/packs` lists `assigned` and `active` packs (with security
  on, check in the console's pack menu, the round box tool in the top bar). An admin also checks
  *Admin → Packs*: a pack switched **off** there is off for everyone.
- **Fix:** tick the pack in the box menu, or ask an admin to assign the pack or a role, or to switch the pack on.
  See [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).

### Suggestions do not appear

- **Check:** the server answers type-ahead directly:

  ```bash
  curl -s "http://localhost:18480/api/v1/command/suggest?q=T"
  ```

  A list means the server is fine: refresh the page (see *typing does nothing* above). An empty list means no
  enabled pack has a mnemonic starting with that text, or every source missed the 30 ms budget.
- **Fix:** for a custom source, declare `search` in the plugin and index ids in memory with `HitIndex`
  ([CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md)). `/api/v1/sources` shows `"search": true` for sources that can suggest.

### Type-ahead misses a corrected past day

- **Cause:** the connector keeps several days of ids (`index.days`) and the source was restated for a past day without a
  data-load signal; the index still holds the day as it built it. The automatic check (`index.reload-every`, 10 minutes)
  catches it within a cycle, unless it is `0` or the day's source has no readable version.
- **Check:** *Admin → Connectors → (connector) → Index* shows each day's build time and any error; *Admin → Health* shows
  `indexLastCheck` and `indexNextCheck` in the connector's cache column.
- **Fix:** click **Reload** on that day (or **Reload all**), or **Reload index** in *Admin → Health*; the row shows
  *rebuilding*, then *fresh*. A second click while one runs says it is already running. An error on the row names why the
  day could not be read; the old day stays in use. See [OPERATIONS.md section 12.2](../admin/OPERATIONS.md#122-sizing-the-multi-day-type-ahead-index).

### A panel says "No data available"

- **Cause:** the document lacks what that panel needs, or has it in another shape. The rest of the view renders
  on purpose. Real feeds are often incomplete.
- **Check:** if the panel also says "The data did not have the shape this panel expects", hover over the message
  to read the exact error. Press **F9** to see the raw JSON and compare it with the panel's `rows:` path in the
  Sutra (Studio shows it).
- **Fix:** if the data is right and the Sutra is wrong, fix the Sutra in the Build workbench; see
  [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md).

### The About drawer says nothing about a number, or shows no "What you are looking at" sentence

- **Cause:** the text is written by the domain pack, in its `config/about.yaml`. The drawer's other layers (where the data came from, why the page looks like this, where next) need no pack text. A kind the pack has not written about shows only the Sutra's `description`; a field with no glossary entry has no hint (the glossary layer needs a server with that stage of [CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md)).
- **Check:** a value reading `•••` in the sentence is masked for your role, on purpose. If the file has a mistake, the entry is left out and listed by `GET /api/v1/sutras/problems` (`DRS-2040` to `DRS-2044`, key `<pack>/<file>`) and on the admin *Sutras* page. "About this page could not be loaded" is a failed call: try again; the view itself is unaffected.
- **Fix:** add or correct the entry: [About text and glossary](PACK_MODELLING_GUIDE.md#about-text-and-glossary).

### The Ask box is missing, or says "Ask is unavailable"

- **Cause (missing):** Ask is off by default. It appears only when `drishti.explain.ask.enabled=true` **and** the page's pack is in `drishti.explain.ask.packs`.
- **Cause (unavailable, `DRS-4008`):** the model endpoint is down, slow (`timeout`), refused the key, or `endpoint`/`api`/`model` do not match what it speaks.
- **Check:** `GET /api/v1/views/{kind}/{id}/explain` shows `ask.enabled`; the server log line `ask failed for …` says why; the access log shows each question with its outcome.
- **Fix:** correct the settings in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiexplainask--ask-about-this-page-optional-off-by-default). The rest of the drawer never depends on Ask.

### A linked entity shows `pending` or `missing` instead of a badge

- **Cause:** `pending`: the linked entity did not arrive within the 40 ms link budget (`drishti.graph.link-budget`); with
  Drishti*net, one that only a peer holds within routing.yaml's `link-budget` (1 s). `missing`: no source has it.
- **Check:** `curl -s http://localhost:18480/api/v1/sources` for a source whose `health` is not `UP`.
- **Fix:** a slow source: see [runbooks/source-down.md](../admin/runbooks/source-down.md). Open the link itself to see the
  real error.

### Charts are blank

- **Cause:** the chart library did not load (a proxy, a strict browser extension, or a broken path).
- **Check:** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:17480/static/vendor/echarts/echarts.min.js`
  should print `200`. The browser's developer console (F12) shows any blocked script.
- **Fix:** allow the console's own `/static/` path through the proxy or extension; hard-refresh.

## Large books: timeouts, grouped pages and caches

### "DRS-1004 … timed out" opening a trade

- **Cause:** a single read took longer than `drishti.sources.fetch-timeout` (5 s). On a large lake the first read of a date reads
  that date's ids first; files written with large row groups, a lake on a network share or far object storage, or an antivirus
  scanning every file read (Windows) make it slower. In the console the same code comes with "the server did not answer in time".
- **Check:** Admin → Health, the connector's `p99Ms`; Admin → Health → Pages for the kind's p95; the server log for
  `… loaded, N ids in F files (T ms)`.
- **Fix:** open it again (the id map is then in memory, and with `id-map-disk.enabled`, the default, on disk for the next start);
  load the lake with `lake build` (256 KB row groups); keep the data folder on a local SSD, excluded from real-time scanning; or raise
  `drishti.sources.fetch-timeout` ([CONFIGURATION.md](../admin/CONFIGURATION.md)).

### A counterparty, portfolio, legal entity or index page (`CTRD`, `PTRD`, `LTRD`, `ITRD`) is slow or times out

- **Cause:** its grouped pages are being built: after a load (a new data version) or the first time. With `store: disk` (the trading
  pack) the old pages serve while new ones are built, and the pages of the previous run are served at once after a restart; with no
  pages yet (a new data folder, a purge, a changed definition) the first build must end, about 45 s for a million trades.
- **Check:** the server log: `derived pages trade-pages (…): reading every trade document in one pass, onto disk`, then
  `… built in N s on disk (M MB)`; Admin → Health, the `trade-pages` row.
- **Fix:** wait for the `built in` line. If every restart rebuilds, check that `<data dir>/derived/trade-pages` is kept between runs
  (the log says `pages kept from the previous run` when it is).

### Health: `trade-pages DEGRADED: out of memory building the pages`

- **Cause:** the grouped pages did not fit in the heap. With `store: heap` every page is in memory; the build stops cleanly, the old
  pages (if any) keep serving, and no reader waits.
- **Fix:** set `store: disk` on the connector (the trading pack's default: the heap keeps each group's totals only), or lower
  `max-stream`, or give the server more heap (`-Xmx`). Then **Admin → Caches → Purge** on the connector (or
  `drishti.py server caches purge trade-pages`) builds again.

### Health: `DEGRADED: partial: … built from the first 100,000 of … documents (max-stream 100,000)`

- **Cause:** `store: heap` keeps at most `max-stream` members from a one-pass read; the pages are built from those only, and a WARN
  says so in the log.
- **Fix:** `store: disk` (complete at any number of members), or raise `max-stream` with heap for it.

### A page over many trades lists only 1,000 trades

- **Not a fault.** A grouped page lists at most `max-members` (1,000 in the trading pack) members; its trade count, notional, payments
  in the next 7, 30 and 90 days and every other total cover all of them. Search for the rest (`TRD COUNTERPARTY=CP-…`) or use the
  BI reports.

### The log says "documents are not cached on disk (…): reads go on without it"

- **Cause:** a Delta connector's document cache (`<data dir>/cache/<connector>/docs`, RocksDB) could not open because another process
  holds its lock: the previous server still stopping after a restart (it can take half a minute), or a second server on the same
  data folder.
- **What happens:** reads go on without the cache (slower, more heap) and the connector tries again every 30 s for 10 minutes. The
  log then says *documents are cached on disk again*, or *not cached on disk until the next start*.
- **Fix:** after a restart nothing: wait for the INFO line. When it gave up, a second server holds the folder: give each server its
  own data folder (`DRISHTI_DATA_DIR`), or set `doc-disk-cache.enabled: false` on the connector. When restarting by hand, wait for the
  old process to end before starting the new one.

### A trade or grouped page shows data from before the lake was restored or rewritten

- **Cause:** the caches are keyed by table version, so a normal load never shows stale data; a lake copied back in place with older
  versions can.
- **Fix:** **Admin → Caches → Purge** (or `drishti.py server caches purge all`): the shared memory caches, the disk caches, the id map
  files and the grouped pages are all rebuilt from the lake.

## Pick lists and tables

### I typed an id and got a pick list instead of the entity

- **Cause:** the command did not name exactly one entity. `TRD MX-200000` is the *start* of 99 trade ids, so it
  lists them. A word after the id (`TRD MX-20000001 swap`), a `*`, or a comparison (`=`, `>`) also makes a pick
  list.
- **Fix:** type the whole id (`TRD MX-20000001`), or pick the row: click it, or select it with `↓` and press
  `Enter`. A command that names exactly one entity (`CPTY north`, `TRD productType=X` with one match) opens it
  at once.

### The pick list shows only ids, or columns I did not expect

- **Cause:** the columns are the fields the command uses, then the kind's key fields from its pack's
  `columns:` in `pack.yaml`. A kind without `columns:` shows the first plain fields of its first document
  (up to six columns), which may not be the most useful ones. Ids only: the documents of that kind have no plain
  fields at the top level, or your role masks them.
- **Check:** which columns the server chose:

  ```bash
  curl -s -G http://localhost:18480/api/v1/search --data-urlencode 'q=LCR limit 1' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["columns"])'
  ```

  You should see `['$.lcrId', '$.legalEntityName', '$.lcr', '$.hqla', '$.netOutflows', '$.minimum']`
  (automatic: the liquidity pack declares no `columns:`).
- **Fix:** add `columns:` for the kind to its pack ([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#columns-the-key-fields-of-a-pick-list)),
  through the generator for a generated pack, and restart the server. For one search, name the fields you
  want in the command: `LCR lcr < 1.2 order by lcr` puts *Lcr* first.

### A table shows only 25 rows, or keeps the wrong number of rows

- **Cause:** every table pages, 25 rows per page by default. The pager under the table reads, for example,
  `1–25 of 99 · page 1 of 4`. The rows-per-page choice (25, 50, 100, 250) is remembered **per browser**, in
  local storage, and applies to every table.
- **Fix:** use `›` and `»`, or choose more rows per page. In a private window the choice lasts only for the page.
  A pick list or search also stops at your *Search results* setting (100 by default, *My account → Settings*)
  or at `limit N`: the summary then says `…; the first 100 are shown`.

### ↑ ↓ or Enter do nothing in a table

- **Cause:** the table does not have the focus; the keys go to the page instead.
- **Fix:** click a row first (it is highlighted), or press `Tab` until the table is outlined, then use `↑`
  `↓`, `PgUp` `PgDn`, `Home` `End` and `Enter`. Keys typed inside a box or a menu in the table are left alone.

## Live updates and business dates

### A view does not tick

- **Check:** the top bar date box: a picked date (amber) is a **static snapshot**; only **Live** (green) streams.
  Then the source: `curl -s http://localhost:18480/api/v1/views/trade/MX-20000001 | python3 -c "import json,sys; print(json.load(sys.stdin)['provenance'])"`
  shows `'live': True` for an entity that ticks.
- **Fix:** press **Live**. If `live` is `False`, that entity's source does not stream (in the samples, only
  documents marked live in the `demo` source tick; the trading pack's Kafka stream needs
  `DRISHTI_STREAM_TRADING=true`). See [LIVE.md](../architecture/LIVE.md).

### "No data held for 2026-08-03: no dated store has … for that date"

- **Cause:** no dated store (the lake, a dated folder) holds the entity for the date you picked, often because the
  history does not reach back that far, so a source that keeps no history answered (for example the `demo` source).
  The view shows that source's current data as a still snapshot: it does not update, and the top bar does not say
  *Live*.
- **Check:** `ls data/delta/<domain>/<kind>/` lists the dates the lake has (`business_date=2026-09-30`, …).
- **Fix:** pick a date the lake covers, or build more history:

  ```bash
  drishti-console/.venv/bin/python tools/drishti.py lake demo                  # the sixty business days ending today
  drishti-console/.venv/bin/python tools/drishti.py lake demo --as-of 2026-10-30  # or ending on another date
  ```

### "Latest data on or before … is from …"

- **Cause:** not an error: the source has no data for that exact day, so it shows the most recent earlier day.

### "DRS-4003 business date … is before the history window" or "cannot read business date"

- **Cause:** dates may go back 5 years (`drishti.business-date.history`, `P5Y`), and must be `yyyy-MM-dd`.
- **Check:** `curl -s http://localhost:18480/api/v1/business-date` shows `earliest`, `current` and the calendar.
- **Fix:** pick a date inside the window. Weekends and holidays roll back to the business day before.
- **In the console** such a date is never taken: the top bar says *Pick a business date from <earliest> to today* and
  stays on the date you had. A date remembered from an earlier visit that the server no longer accepts is forgotten
  (the page is live and says so), so it cannot break every page until its cookie expires.

### "Known at" changes nothing

- **Cause:** only sources that keep versions (Delta Lake, Iceberg) honour it; undated sources have one version. Delta
  resolves times from the `_delta_log` files' modification times, so a lake copied without preserving times loses its
  history.
- **Fix:** copy lakes with `cp -p` or `rsync -t`. See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

### "DRS-1007 <connector> keeps no earlier versions"

- **Cause:** a *known at* time was given, and the connector that holds the date (files, PostgreSQL, DuckDB, Redis,
  MongoDB …) keeps only the current version. It refuses rather than show today's data as what was known then. A search
  is `partial` and names it in `failed`.
- **Fix:** clear *known at*, or route the kind's history to a store with time travel (Delta Lake, Iceberg).

### "DRS-1001 <connector> holds trade for <date> and does not list …"

- **Cause:** the store first in line holds that date and does not list the entity (it was dropped there). A store
  that holds a date is authoritative for it: the stores behind it are not asked, so the view agrees with a search.
- **Fix:** none needed if the drop was intended; otherwise reload that day into the first store.

## Search

### "DRS-4004 cannot read the condition: DRS-2101 unexpected '>' at 8"

- **Cause:** a typing error in the `where` part. The number is the character position inside the condition.
- **Check:** the server returns the same message:

  ```bash
  curl -s -G http://localhost:18480/api/v1/search --data-urlencode "q=TRD where mtm >> 1m"
  ```

- **Fix:** use `= != < <= > >=`, `and`, `or`, `not`, `contains`, `startswith`; quote text with single quotes; write
  amounts as `250k`, `1.5m`, `2bn`. A working example:

  ```
  TRD where mtm > 1m order by mtm desc limit 50
  ```

### "DRS-4004 the condition ends early: something is missing after '>'"

- **Cause:** the condition stops where a value is still needed (`TRD where mtm >`).
- **Fix:** finish it: `TRD where mtm > 1m`.

### "DRS-4004 no trade has a field 'nosuchfield'; did you mean …?"

- **Cause:** the search names a field that no entity of the kind has. Case does not matter (`producttype` is
  `productType`); spelling does. The names come from the kind's columns and key fields and from the documents the
  search read. When the scan was cut short (*results may be incomplete*) a name it did not meet is not reported.
- **Fix:** use a name the message suggests, or press **F9** on one entity of that kind and copy the path
  (`counterparty.name`, `legs[0].rate`).

### "DRS-4004 limit must be a whole number from 1 to 1000, not '0'"

- **Cause:** `limit` outside 1 to 1000 (`limit 0`, `limit 5000`, `limit many`).
- **Fix:** a number in range; without `limit` the page shows your default size (*My account → Settings*).

### A search returns nothing

- **Check:** a masked field (and any field under it) never matches, for any condition, `!` included. A field the kind
  does not have is an error, not an empty result (above), as is an unknown mnemonic
  (`DRS-4004 'XYZ' is neither a mnemonic nor a kind; type it alone to see suggestions`). A field some entities lack
  (an optional field) is never true on those.
- **Fix:** check the value. Text matching with `=`, `!=`, `contains` and `startswith` ignores case, so
  `currency = usd` finds `USD`.

### "… results may be incomplete"

- **Cause:** the scan stopped short: more than 20,000 entities of that kind (`drishti.search.max-scan`), or a
  source slower than 3 seconds (`drishti.search.budget`). The API answer has `"partial": true`.
- **Fix:** narrow the search, or raise the limits ([CONFIGURATION.md](../admin/CONFIGURATION.md)).

### "Incomplete: 1 source could not be read …"

- **Cause:** a source failed, did not answer in time, or could not list the kind (its type-ahead index could not be
  rebuilt), while the search ran. The banner names each source and why; the API answer has `"partial": true` and
  `"failed": [{"source", "reason"}]`. Entities that source holds are missing from the results, so "0 of 0" is not
  "nothing matched".
- **Fix:** follow the reason (a Delta table in BROTLI or LZO pages: [DELTA_CONNECTOR.md §16](../connectors/DELTA_CONNECTOR.md); LZ4 tables read normally); otherwise
  **Admin → Health** shows the source's `lastError` ([source-down runbook](../admin/runbooks/source-down.md)).

## Studio and Sutras

### Studio's Save (or Submit for review) is disabled

- **Cause:** "Saving is off here, or you are not an author".
- **Check:** `curl -s http://localhost:18480/api/v1/studio/settings` should show `"save": true`.
- **Fix:** start the server with `DRISHTI_STUDIO_SAVE=true`, and (with security on) give yourself the `author`,
  `approver` or `admin` role.

### A Sutra edit has no effect

- **Check:** `curl -s http://localhost:18480/api/v1/sutras/problems` should print `{}`. Anything else lists the
  file, line, column and code (`DRS-2001` YAML syntax, `DRS-2009` no `rachana: 1`, `DRS-2010`–`DRS-2039` grammar (`DRS-2034`–`DRS-2039`: controls), `DRS-2032` the file
  could not be read at all, `DRS-2033` a key written twice, a second YAML document or a YAML tag, `DRS-2101` expression
  syntax or an expression past the size limits).
- **Check:** as an admin, `GET /api/v1/admin/health` should show `"sutras": {"hotReload": "WATCHING", …}`. `STOPPED: <reason>`
  means the file watcher has ended (the log says why): restart the server to pick up edits again.
- **Cause and fix:** an invalid edit keeps the **last good version** live. Fix the reported line (Studio's
  Ctrl+Enter lists problems by line). With review on (the default), a saved Sutra is only a **proposal** until an
  approver approves it in **Studio → Reviews**. *How this view was built* shows the version actually used. See
  [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md).

### Studio says "line 7 is indented with a tab"

- **Cause:** YAML indents with spaces only; a tab at the start of a line is a syntax error (`DRS-2001`).
- **Fix:** replace the tab with spaces (two per level, as the rest of the file).

### "DRS-2033 duplicate key 'version' (first written at line 3)"

- **Cause:** YAML that would be read in a way you may not mean: a key written twice in one mapping (YAML keeps only the
  last), a second document after `---` (it would be ignored), or a YAML tag such as `!panel`.
- **Fix:** write each key once, keep one document per file, and drop the tag.

### "expression nested deeper than 200 levels" or "expression is longer than 10000 characters"

- **Cause:** a Rachana-EL expression (in a Sutra, an alert rule or a search condition) is past the size limits:
  nested more than `drishti.rachana.max-expression-depth` (200) levels, or longer than
  `drishti.rachana.max-expression-length` (10,000 characters). Each operand of a chain counts as a level, so a sum of
  hundreds of terms is "deep" too. The limits keep parsing and evaluation from running out of stack: in 1.13 and earlier such
  a Sutra stopped the server from starting, killed hot reload, or made its view fail with HTTP 500.
- **Fix:** simplify the expression (`sum($.legs, 'pv')` instead of a long `+` chain; fewer nested parentheses). Raise
  the settings only for a real need: within the defaults evaluation is safe on any thread. A Sutra past the limits
  is reported (`DRS-2101`) and not loaded; the others load, and its kind's views fall back to the next Sutra or to
  inference. See [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#size-limits).

### After upgrading, Sutras are missing and `problems` lists `.sutra.md` files

- **Cause:** since 1.11 a Sutra is a YAML file, `<name>.v<N>.sutra.yaml`, starting with `rachana: 1` (ADR-017).
  Markdown Sutras (`*.sutra.md`) in a site folder are no longer read; each is reported as `DRS-2004`, and so is a
  plain `.yaml` or `.yml` file in a Sutra folder. Its entities fall back to inference (or to a pack's Sutra).
- **Check:** `curl -s http://localhost:18480/api/v1/sutras/problems` names each file with the command that fixes it.
- **Fix:** convert them, from the repository root: `python3 tools/rachana/md_to_yaml.py ./sutras --delete` (a file or
  a folder). You should see one `… .sutra.md -> … .sutra.yaml` line per file, then `{}` from `problems`. Rename a
  plain YAML Sutra to `<name>.v<N>.sutra.yaml`; move other YAML files out of the folder. A file without `rachana: 1`
  at the top is `DRS-2009`: add it. See [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md).

### Approving fails with `DRS-2007` or `DRS-2006`

- **Cause:** `DRS-2007`: four eyes; nobody may approve their own proposal. `DRS-2006`: either the proposal is already
  decided, or the live Sutra changed after it was proposed ("… changed after … was proposed; reject it and propose
  again from the live version").
- **Fix:** ask another approver; for a stale proposal, reject it, open the live Sutra in the Build workbench, re-apply the change
  and submit anew.

## Packs

### A pack's mnemonics are missing from the command line

- **Check:** is it enabled? `curl -s http://localhost:18480/api/v1/packs` lists enabled packs. Is it chosen? The round
  box tool in the top bar shows ticks for your active packs.
- **Fix:** enable it on the server (`DRISHTI_PACKS`, then restart), then tick it in the box menu. An admin may
  need to assign it to you first (**Admin → Users**), or switch it on (**Admin → Packs**).

### A pack is enabled but a pack problem shows in Admin → Health

- **Check:** `curl -s http://localhost:18480/api/v1/admin/health | python3 -c "import json,sys; [print(p['name'], p['status'], p['sutraProblems'], p['connectorsDown']) for p in json.load(sys.stdin)['packs']]"`
- **Fix:** Sutra problems: see *A Sutra edit has no effect*. A connector down: see
  [runbooks/source-down.md](../admin/runbooks/source-down.md).


### A pack is *off* in Admin → Packs, or its users get `DRS-5002`

- **Cause:** an administrator switched it off for everyone. Its kinds cannot be opened, and its mnemonics and
  suggestions are gone, even for users it is assigned to.
- **Check:** *Admin → Packs* shows **off** and, under *Changed*, when and by whom. The audit log has a
  `pack-disabled` line.
- **Fix:** **Switch on**. It takes effect at everyone's next click; no restart.

### A pack says *not loaded* in Admin → Packs

- **Cause:** its folder is in `config/packs/`, but `DRISHTI_PACKS` did not name it (nor any pack that extends it), so
  the server did not load it.
- **Fix:** press **Load** on its row: the server checks it, records it in `data/packs/added.yaml` and puts it to use
  at once, with no restart. If it says *cannot load 'finance': kind trade is defined by both pack 'trading' and pack 'finance'*, the
  pack clashes with one already loaded (the retired example pack `finance` defines the same kinds as `trading`), and nothing
  changed ([PACKS.md](PACKS.md#the-packs-that-ship)).

### "Switch off" is greyed, or the API says "… is needed by …; switch those off first"

- **Cause:** another switched-on pack builds on this one (`DRS-5001 'trading' is needed by [market-risk,
  counterparty-risk]; switch those off first`).
- **Fix:** switch off the packs named first, then this one. Switching any of them on later switches this one on
  again by itself.

### The drill fails with "pack manifests out of date" or "… pack out of date; run …"

- **Cause:** a generated file (`pack.yaml`, a Sutra, a sample or a guide of a generated pack) was edited by
  hand, or the generator was changed and not run.
- **Check:** run the check that failed, for example `python3 tools/packgen/banking/make_packs.py --check`; the
  message lists the stale files, and `extra=[…]` lists files the generator did not write.
- **Fix:** move the change into the generator, run it without `--check`, and commit both
  ([PACK_DEVELOPER_GUIDE.md](PACK_GENERATORS_GUIDE.md#how-the-shipped-packs-are-generated)). To discard a hand edit instead:
  `git checkout -- config/packs/<name>/…`.

## Connectors

### A connector is listed as idle, not as failed

- **What you see:** the server log says
  `source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle`, and the
  connector is missing from `/api/v1/sources` but not under `failedToStart` in Admin → Health.
- **Cause:** a plugin is on the class path and enabled, but has no settings (Kafka without `topics`, a feed
  without `feed`). Such a plugin stays idle on purpose (`PluginNotConfigured`): it is not an error, and the
  overall status stays **OK**.
- **Fix:** nothing, if you do not use it. To use it, give it its settings under
  `settings:` in `config/connectors/<name>.yaml` (or the plugin's own `drishti.sources.plugins.<name>.settings`);
  the server applies the file without a restart. See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

### A connector does not start, or a connector file is bad

Connectors are files, one per connector, in `config/connectors/` (the file name is the connector's name); see [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).

- **What you see:** Admin → Health is **DEGRADED** with `connectors.badFiles`, or a connector shows `failed` in Admin → Connectors and in `drishti.py connector list`;
  the log says `connector file site-quotes.yaml is not usable; the last good configuration keeps running: ...`.
- **Cause:** the file is not YAML, has a top-level key other than `plugin`, `enabled`, `kinds`, `description`, `settings` (and `name`), says a `name:` different from its
  file name, uses `${VAR}` that is not set, or holds a literal credential. A bad file never stops a running connector: the last good configuration keeps running.
- **Fix:** read the message (it names the key or setting), correct the file, and the server applies it within seconds (`connectors-poll`, 5 s). Use **Test connection**
  in Admin → Connectors or `drishti.py connector test NAME` for a failure to reach the source (a TLS failure comes back in words). If edits are not picked up, check
  `connectors.watch` in Health (`STOPPED` or `OFF` means restart, or set `drishti.sources.connectors-watch`), and that the file name is lower case letters, digits and hyphens.
- **A kind answers `DRS-1011`:** the pack names a connector that has no file. Create it ([DRS-1011](#error-codes)).

### A connector failed to start

- **What you see:** Admin → Health says **DEGRADED**, and `failedToStart` names the connector with a reason.
- **Fix:** follow [runbooks/source-down.md](../admin/runbooks/source-down.md), step 2.

### A connector, AI provider or MCP server fails behind a proxy: 407, CONNECT refused, "cannot connect to the proxy", DNS

- **What you see:** health or *Test connection* says one of
  `the proxy proxy.corp.example:3128 refused the credentials (HTTP 407 Proxy Authentication Required): check proxy.username and proxy.password`
  (the JDK's own wording is `too many authentication attempts` or `Unable to tunnel through proxy. Proxy returns "HTTP/1.1 407 …"`),
  `the proxy … refused to open a tunnel (CONNECT) to the target: … 403 Forbidden`, `cannot connect to the proxy 10.1.2.3:3128 (Connection refused)`,
  `cannot resolve the name proxy.corp.example (DNS)`. *Test connection* adds a plain-words hint.
- **Cause:** 407, the proxy wants a login and the one given is wrong or missing; CONNECT refused, the proxy does not allow that host or port; cannot connect, nothing listens at
  `proxy.url` or a firewall drops it; DNS, the server cannot resolve the proxy's (or the target's) name.
- **Check:** Admin → Connectors → the connector → **Proxy** shows the proxy in effect and where it came from (connector, site default, environment). From the server's host:
  `curl -v -x http://proxy:3128 --proxy-user svc:… https://target/` (a `407` there is the same 407) and `curl -v --proxytunnel -x http://proxy:3128 https://target/` (the CONNECT).
- **Fix:** correct `proxy.username` and the environment variable or file behind `proxy.password`; ask for the target on the proxy's allow-list (usually port 443); correct `proxy.url`
  (`http://host:port`, never `https://`); add the host to `proxy.no-proxy` if it should go direct, or write `proxy: none`. A `PKIX` error through a proxy is the target's (or a
  re-signing proxy's) certificate: `tls.ca-file`. Full table: [PROXY.md, section 11](../connectors/TLS.md#proxy-11-troubleshooting).

### A connector will not connect over TLS, or stops at start with a `tls.` message

- **What you see:** the log, Admin → Packs → Data source → *Test connection*, or the source's health text names a
  setting and a file: `tls.keystore '…/client.p12': wrong password (tls.keystore-password), or the file is damaged`,
  `tls.key-file '…' does not match the certificate in tls.cert-file '…'`, `tls.ca-file '…': file not found`; or, while it
  runs, health says `DOWN: … PKIX path building failed` or `No subject alternative DNS name matching …`.
- **Cause:** a file, a password or a pairing is wrong (the connector checks them at start), the server's certificate is not
  signed by an authority the connector trusts, or its name is not in the certificate.
- **Check:** `openssl s_client -connect host:port -servername host -CAfile ca.pem -verify_hostname host < /dev/null` shows
  whether the chain and the name pass (`Verification: OK`). The exact messages and what each means:
  [TLS.md, sections 8 and 11](../connectors/TLS.md#8-start-up-checks-and-their-messages).
- **Fix:** put the issuing CA in `tls.ca-file` or `tls.truststore`; re-issue the server certificate with its names as SANs
  (last resort `tls.verify-hostname: false`, which warns at every start); give the client certificate and key for mutual
  TLS. Per connector: [Kafka](../connectors/KAFKA_CONNECTOR.md#119-common-errors-and-fixes),
  [ActiveMQ](../connectors/ACTIVEMQ_CONNECTOR.md#144-common-errors-and-fixes),
  [RabbitMQ](../connectors/RABBITMQ_CONNECTOR.md#125-common-errors-and-fixes),
  [Redis](../connectors/REDIS_CONNECTOR.md#security-tls-and-credentials),
  [REST](../connectors/REST_CONNECTOR.md#tls-for-the-rest-connector),
  [S3](../connectors/S3_CONNECTOR.md#tls-for-the-s3-connector),
  [MongoDB](../connectors/MONGODB_CONNECTOR.md#security-tls-certificate-log-in-and-credentials),
  [PostgreSQL](../connectors/POSTGRES_CONNECTOR.md#security-tls-and-credentials),
  [Aerospike](../connectors/AEROSPIKE_CONNECTOR.md#security-tls-certificate-log-in-and-credentials),
  [Delta](../connectors/DELTA_CONNECTOR.md#security-tls-and-credentials),
  [Iceberg](../connectors/ICEBERG_CONNECTOR.md#security-tls-and-credentials).
- **Also at start:** `tls.* is set but … is not https://` (or `not rediss://`, or `tls.enabled is not true`) means `tls.*`
  settings sit beside an address that is not TLS: the connector refuses to start rather than quietly connect in plain text.
  Change the address (`https://`, `rediss://`, `amqps://`, `ssl://`, `tls=true`) or remove the `tls.*` keys. Where a library
  cannot take a private CA per connector (Delta with `engine: hadoop`, Iceberg with `catalog: hadoop`, the Iceberg REST
  catalog's own connection) the message says so and names the JVM truststore, `-Djavax.net.ssl.trustStore=…`.
- **PostgreSQL connects but is not verified:** the PostgreSQL driver's default `sslmode=prefer` encrypts without checking the
  certificate. Set `tls.enabled: true` with `tls.ca-file` (verified, `verify-full`), or `sslmode=verify-full` in the URL.

### A health text says "TLS certificate … expires in N days"

- **What you see:** `UP (TLS certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22))` in Admin → Health.
- **Cause:** a certificate the connector was given (its client certificate or its CA) is within 30 days of its end date.
  The source is still up; this is the warning.
- **Fix:** rotate it and restart the source ([TLS.md, section 10](../connectors/TLS.md#10-rotation)).

### A Kafka connector skips every message of a topic written with a schema registry

- **What you see:** the connector is `UP` but no entity appears, and the server log says `a message in the Confluent wire
  format could not be read (…)`.
- **Cause:** the values start with Confluent's five-byte header (a zero byte and a schema id). Without
  `schema-registry.url` an Avro message cannot be decoded; Protobuf is not supported; or the registry refused the
  credentials (they are the registry's key, not the Kafka one).
- **Fix:** set `schema-registry.url` and `schema-registry.basic-auth: ${SR_KEY}:${SR_SECRET}`
  ([KAFKA_CONNECTOR.md, 11.5](../connectors/KAFKA_CONNECTOR.md#115-schema-registry-and-the-confluent-wire-format)).

## Monitoring endpoints

### A request under `/api` or `/actuator` answers `400 DRS-5001` "path parameters (;) are not accepted"

- **Cause:** the path in the request line is not written plainly: a `;` path parameter, a percent-encoded letter,
  digit, `- . _ ~`, `/` or `\`, a malformed escape, or a `.`, `..` or empty (`//`) segment. The server refuses these
  before any other check so that the path it checks is the path it serves.
- **Fix:** send the plain path. Percent-encode only characters that are data in a name or an id (a space, `;`, `%`,
  parentheses, non-ASCII letters), as `urllib.parse.quote(name, safe='')` or `encodeURIComponent` do.

### `/actuator/prometheus` (or `/actuator/metrics`) answers 401 or 403

- **Cause:** security is on (`DRISHTI_SECURITY_ENABLED=true`). Then only `/actuator/health` (and
  `/actuator/health/liveness`, `/actuator/health/readiness`) stay open for probes; the rest of `/actuator`
  needs a bearer token: an **admin's** token, or the **scrape token** `DRISHTI_METRICS_TOKEN`.
  - `401` with `{"title":"unauthenticated",…,"detail":"operational endpoints need an admin token or the metrics token"}`:
    no token was sent.
  - `403` with `"title":"forbidden"`: a token was sent, but it is neither the scrape token nor a valid admin
    token.
- **Check:**

  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18480/actuator/prometheus
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $DRISHTI_METRICS_TOKEN" http://localhost:18480/actuator/prometheus
  ```

  You should see `401`, then `200`.
- **Fix:** start the server with a long random `DRISHTI_METRICS_TOKEN` (for example from
  `openssl rand -hex 32`) and give Prometheus the same value:

  ```yaml
  scrape_configs:
    - job_name: drishti
      metrics_path: /actuator/prometheus
      authorization: { type: Bearer, credentials: "<the DRISHTI_METRICS_TOKEN value>" }
      static_configs: [ { targets: ["drishti-server:18480"] } ]
  ```

  With security off (local development) every endpoint is open, as before.

### `/api/docs` answers 401

- **Cause:** with security on, the API description (`/api/docs`, `/api/docs/ui`) needs any valid user token.
  The metrics token is not accepted there.
- **Fix:** send a user's token (`-H "Authorization: Bearer $TOKEN"`; see [API_GUIDE.md](API_GUIDE.md)), or read
  [API_GUIDE.md](API_GUIDE.md) instead.

## Alerts and monitors

### An alert never fires

- **Cause:** a rule fires when its condition **turns** true, once, and re-arms only when it turns false again. It is
  checked on every change of the entity it watches, so an entity that never ticks never triggers it.
- **Check:** open the entity: is it **Live** and ticking? Is the path right (`$.utilisation > 0.8`)? Press **F9**
  to read the field names.
- **Fix:** correct the path or threshold in **Alerts** (`/alerts`). See *Monitors and alerts* in help.

## Development

### `LicenseHeaderTest` fails

- **Cause:** a new file has no copyright header.
- **Fix:** `python3 tools/license_headers.py --fix`, then build again.

## Refresh, time intelligence, the map and totals (BI wave 3)

### A dataset's refresh says `retrying` or `failed`

- **Cause:** the scheduled rebuild of its layers failed: a connector that is not running (`DRS-9026`), a source that errors, a layer that could not be written. The dataset page's **Refresh** section and `GET /api/v1/bi/datasets/{id}/refresh` give the error, the failures and (`retrying`) the time of the next try.
- **Fix:** repair the connector (Admin, Connectors). A `retrying` refresh tries again by itself, with the waits of `drishti.retry.bi.*` (5 s doubling to 5 minutes); after `drishti.bi.refresh.max-attempts` (4) it is `failed` until its next scheduled time: press the refresh (`POST /api/v1/bi/datasets/{id}/refresh`) once the source is back.
  A schedule the server cannot read is `DRS-9010` at `refresh`; a state `invalid` means a stored schedule stopped reading after an upgrade.

### A dataset never refreshes at its time

- **Cause:** the dataset is a **draft** (only published and shipped datasets are scheduled), `drishti.bi.refresh.enabled` is `false`, the time is in the **business-date zone** (`drishti.business-date.zone`, not the server's), or it is `after-load` and no load for a kind it reads has arrived.
- **Fix:** read the schedule and `nextRun` in the Refresh section; the scheduler looks every `drishti.bi.refresh.tick` (30 s), so a schedule runs within that of its time.

### A year-to-date or prior-period figure is smaller than expected

- **Cause:** time intelligence adds up **the rows the query read**. If each business date's partition holds only that day's rows, a report on one date has no earlier days to add up; or the as-of date is not the one you expect (it is the end of the report's dates).
- **Fix:** give the report `dates: { from: <start of the window> }`, or read a snapshot lake on one date; check the answer's `meta.dates`. `prior_period` and `same_period_last_year` use the pack's calendar: `drishti.business-date.calendar`.

### A running total is refused (`DRS-9020`)

- **Cause:** `running(...)` runs along the date **field of the visual**: the date column must be on the visual's axis (or a field of the query), and a running total cannot be filtered on.
- **Fix:** put the date (or a part of it: year, month) on the axis; to restart each year put `year` and `month` both on it.

### A map shows fewer countries than the data has

- **Cause:** the map matches the `location` field to a vendored list of countries (ISO alpha-2 or alpha-3 code, or the English name). Small territories and codes that are not countries are not on it; the visual names them under the map (`Not on the map: KY`).
- **Fix:** put an ISO country code in the field (a calculated column in the dataset can map your own code); for points use `latitude` and `longitude`. The file is `static/vendor/echarts/maps/world-countries.json`; nothing is loaded from outside, so a map in a closed network works. A region is an ISO 3166-2 code (`AU-NSW`); one the region file of its country does not hold is named under the map in the same way.
- **A drilled map shows no figures for a country:** the field holds countries, not their regions; drilling shows the shapes of the regions and says so (*No figures at region level for Australia*). Add a region code field to the dataset if the figures are regional.

### A total shows `–` where the other cells have numbers

- **Cause:** the measure does not add up (a ratio, an average, a distinct count, ...) and the engine's total for it was refused or is masked for you; hover the cell. A matrix with no `subtotals: true` shows no totals at all.
- **Fix:** the tip says which; for a masked measure ask for the raw power; for a refusal read the visual's `totals` in the answer (`POST /api/v1/bi/reports/{id}/query`).

## Live reports, Perspective and boards (BI phase 5)

### A report over a live dataset shows no live indicator, or a tile does not update

- **Cause:** the report's dataset has no table with `mode: live` (the indicator appears only then), or the console's live channel could not be opened (see "The Live button has an amber ring"), or `drishti.bi.live.enabled` is `false` on the server (live tables are then a 5-second snapshot and nothing is streamed).
- **Fix:** open the dataset's page: its Layers table says `in memory, live` for a live table. Check `GET /api/v1/bi/datasets/{id}/layers` (`live.state`) and, as a BI administrator, `GET /api/v1/bi/engine` (`live.enabled`).

### The indicator says **Stale**

- **Cause:** either the page has heard nothing from the server (not even its 5-second `status`) for `bi.live.stale_seconds` (30), or the table's source pushes but has delivered nothing for `drishti.bi.live.stale-after` (30 s): the source system or its connector has gone quiet. Hover over the indicator for the reason.
- **Fix:** if the reason is "nothing heard from the server", reload the page; if "the source has pushed nothing", look at the connector (Admin, Connectors) and at the source. A source that changes rarely but is healthy says **Live**: stale needs a feed that pushes and then stops.

### The indicator says **Feed failing**

- **Cause:** the live table's first read failed and it holds no rows: no running connector serves the kind, or the connector is down. The queries answer with a warning that gives the connector's reason (`DRS-9026` when none serves the kind).
- **Fix:** Admin, Connectors; then reopen the report (a failed table tries again on the next resync, every 30 s, and the page redraws when it recovers).

### The indicator says **Disconnected**, or **Paused (tab in background)**

- **Cause:** *Disconnected*: the stream between the console and the browser (or the server) dropped, for example the console restarted, or `DRS-9051` refused one for too many open streams. *Paused (tab in background)*: the tab was hidden for ten seconds and gave its stream back.
- **Fix:** nothing to do for the first two: the browser reconnects by itself, and when the stream is back every visual is asked again. For `DRS-9051` close a tab or a board.

### The live table has fewer rows than the kind has entities, or a row disappeared

- **Cause:** the table follows at most `drishti.bi.live.follow-max` entities (2,000) and keeps at most the dataset's `live: { rows }` (and `drishti.bi.live.max-rows`, 50,000): the ones changed longest ago go first. `live: { age }` drops rows that have not changed for that long. A deletion pushed by the source removes the row.
- **Fix:** raise the limits, or narrow the kind. The table's state (dataset page, `GET /bi/datasets/{id}/layers`) says `entities`, `rows`, `evicted` and `deleted`; a note says when `follow-max` stopped it.

### The live table says `pushes: false`, and updates arrive only every 30 seconds

- **Cause:** no connector that serves the kind can push it. When a dataset names no `connector:`, the engine uses the connector that serves the kind best (the one with the most columns promoted): a Delta or file connector for `trade` beats the demo source, and a store does not push. The table then reads the kind again every `drishti.bi.live.resync`.
- **Fix:** name a connector that pushes in the dataset (`source: { connector: <name>, kind: trade }`: the Kafka or Redis connector, in `ticks` mode when a store serves the entities), or lower `resync`.

### A 2.1 Perspective grid, pivot or chart is a plain table (2.3.0)

- **Cause:** wave B removed WebAssembly from every BI page ([BI_DESIGN.md](../architecture/BI_DESIGN.md) principle P3: the servers compute, the browser draws).
  A `perspective-grid`, `perspective-pivot` or `perspective-chart` visual of a 2.1 report is drawn as Drishti's own grid of the server's answer.
- **Fix:** none needed; to get a chart or a pivot back, change the visual to a `matrix` or a chart type in the designer (the server groups and totals it).

### A grid (or a 2.1 Perspective grid) shows `•••` in a column

- **Cause:** that column is masked for you (Drishti's field masks, or the dataset's `security.masked` and you lack `raw`): the server replaced it before the rows left. The same mask reads in every visual of the report.
- **Fix:** nothing: ask an administrator for the `raw` power if you need the values.

### A board tile says "Not available"

- **Cause:** you may not open the report the tile shows, the report was deleted or renamed, or it no longer has that visual. The rest of the wall draws.
- **Fix:** open the board's problems (the list above the wall, or `GET /api/v1/bi/boards/{id}`: `problems` with `DRS-9052` and the path) and correct the tile's `report:` and `visual:`.

### A tile never changes colour

- **Cause:** the rule compares a number the tile does not show (`on:` names a measure that is not among its columns), the value is masked for you (a masked value is null and never crosses a threshold), or the level was already reached. `crit` is tested before `warn`.
- **Fix:** put the measure in `on:` exactly as the tile's column is named; read `data-alert` on the tile (`warn`, `crit` or empty) to see what the page decided.

### A python visual says `DRS-9074`, or never draws

- **Cause:** the console has no free worker (`bi.python.workers`, waits `bi.python.queue_seconds`), the runner is off (`bi.python.enabled: false`), `pandas` or `pyarrow` is not installed in the console's Python (`GET /bi/python/status` says `ready: false` and lists `missing`), the item lists a package that is allowed but not installed, or a browser run has no Pyodide (`tools/fetch-pyodide.sh`).
- **Fix:** read `GET /bi/python/status` (`enabled`, `ready`, `missing`, `installed`, `running`, `isolation`); `pip install -r drishti-console/requirements.txt` in the console's environment; raise `workers`; for the browser target run `tools/fetch-pyodide.sh`. With `network_namespace: require` the machine must allow `unshare --user --net`, else use `auto`.


### A Vega-Lite visual is empty, says it could not draw, or a Python visual's Vega-Lite is refused

* **"Vega-Lite could not draw this: ..."** The text is Vega's: an invalid specification (a field that is not in the rows: the rows are keyed by the text after the table's dot, `DESK` not `trade.DESK`, and a measure by its name; Check warns about a field no well supplies). Open the visual's YAML and the **Show the rows** button together to compare the names.
* **"may not load anything from a URL or name a loader".** `data.url`, `url` or `loader` in a specification: remove it; the data is the rows of the wells, or `data.values` for a Python visual. A Python visual that returns one is `DRS-9073` from the sandbox.
* **Nothing is drawn and the console shows no error.** The four scripts under `/static/vendor/vega/6.4.0/` load on first use; a proxy that blocks them (or a stale cache after an upgrade) leaves the frame empty. Reload with the cache off.
* **The page reports a policy violation naming `style-src`.** Not from Vega: vega-embed is called with `defaultStyle: false`. A violation from an `unsafe-eval` means a different Vega build was vendored: it must be the bundle with `vega-interpreter` ([VENDOR.md](../../drishti-console/web/static/vendor/VENDOR.md)).

### A visual says Stale, Not updating or Asked once (2.3.0)

* **Stale.** Nothing was heard for `bi.live.stale_seconds` (30): the console's channel dropped (it reconnects by itself) or the server's stream is quiet
  longer than its status interval. The picture is the last good answer; the chip goes back to Live with the next event.
* **Not updating.** The standing query was refused or failed: the chip's title says why (`DRS-9181`/`9182`: too many standing queries for the server or
  for you; close a tab or raise `drishti.bi.mq.max-subscriptions-per-user`; `DRS-9183`: a past as-of date never changes).
* **Asked once.** The page follows 12 visuals (a board 16) already; the others were asked once. Split the page, or open the visual alone.
* **Every few seconds.** Not a fault: windows, time intelligence and Python measures, or a model over `drishti.bi.mq.maintenance-budget`, are recomputed
  on the server's tick (`drishti.bi.mq.tick`).
* **No chip at all.** The visual reads a 2.1 dataset (no model query): it follows the report's stream as before, with the live bar above the page.

### Analyse: where the Slice, SQL and Python cells run (2.3.0)

* **Everything runs on the console and the server.** Wave B removed DuckDB-Wasm and the browser's Python: the Slice is worked out by the console
  (`POST /bi/explore/pivot`, the line under it says "Worked out by the console"), a SQL cell is Drishti SQL run by the server over the model (or, for a cell
  over the table `extract`, the console's read-only SQLite), and a Python cell runs in the console's sandbox. An older notebook cell with `target: browser`
  runs on the console.
* **"Promote ... the target is not a report draft you can edit" (`DRS-9070`).** The target must be a report item in a workspace you contribute to (not a shipped sample and not another kind). Comments in its YAML are not kept when the visual is added.

### Calc: `drishti_bi` or Graphic Walker

* **`ModuleNotFoundError: No module named 'drishti_bi'`.** The module is fetched into the worker when a cell **names** `drishti_bi`; write `import drishti_bi` in the cell that uses it (a cell that only calls `walk` through another name does not trigger it).
* **`needs JavaScript Promise Integration`.** Write `await drishti_bi.query_async(...)` (also `datasets_async`, `describe_async`).
* **`DRS-9002` or an empty frame.** The call is a BI read as you: you need the `bi` power, and a masked column comes back masked (`df.attrs["masked"]`).
* **The Graphic Walker frame stays blank.** Open the browser console: a refusal of `/static/calc/walker.html` or of its script means a proxy rewrote the policy header (the page needs the console's own `Content-Security-Policy` for that path, [PYTHON_CALC.md section 19](PYTHON_CALC.md#19-the-bi-client-and-graphic-walker)). A refused stylesheet and image from `blocked.invalid` is expected and harmless.

### Analyse: the extract is short, a SQL cell is refused, a Python cell will not run

* **"60 rows of 4,000 (cut to the row limit)".** The extract holds the first `bi.explore.max_rows` (10,000) of the rows *you* may see. Pick fewer fields, add a filter in the Slice or in SQL, or ask an administrator to raise the limit. Slice and SQL work on the cut extract, so their totals are the extract's, not the dataset's (use a report for true totals).
* **A column is empty and the info line says "Masked for you".** The server masks that column for your role; the extract carries only what it was given. SQL and Python see the same empty column. Ask the dataset's owner for access, not Analyse.
* **`DRS-9073` "only a single SELECT over the table extract is allowed".** The SQL cell is read-only: one `SELECT` (or `WITH ... SELECT`) over the table `extract`. `INSERT`, `UPDATE`, `DROP`, `ATTACH`, `PRAGMA` and `load_extension` are refused. `DRS-9070` is a syntax error, or two statements.
* **`DRS-9072` on SQL.** More than `bi.explore.sql_max_chars` characters, or the query ran past `bi.explore.sql_max_seconds` (a runaway join or recursion). Rows past `bi.explore.sql_max_rows` are cut, not refused.
* **A Python cell says `DRS-9073` "needs the bi-author power".** Running Python needs the `bi-author` power (the code runs in the console's sandbox). Slice and SQL need only `bi`.
* **`DRS-9074` on a Python cell.** The runner is off, busy or lacks `pandas`/`pyarrow`: see the Python visual entry above. The *browser* target also needs Pyodide (`tools/fetch-pyodide.sh`), and `quant` is not available there (`ValueError: quant: ... server target only`).
* **`ValueError: quant: unknown instrument`.** `quant.instruments()` lists what the engine prices; the stand-in prices `black_scholes`, `bond` and `npv`.
* **A saved notebook will not open.** `DRS-9070` names the cell: a notebook is YAML with `cells` of type `slice`, `sql` or `python`. Open the item page to see and fix the body.

### A python visual says `DRS-9071`, `DRS-9072` or `DRS-9073`

- **Cause:** `9071`: the code raised (the message names the exception; people with `bi-author` also see the traceback, whose line numbers are the item's source) or returned the wrong thing (a measure: one number per group; a visual: an ECharts option, SVG text or PNG bytes). `9072`: it ran over its `timeout` (504) or its `memory`, or the rows in or the answer out were too big (413). `9073`: it imported a package it did not list, or tried the network, a file outside its scratch folder, or a process.
- **Fix:** open the item, press **Check the Python**, and read the traceback as an author; raise `timeout` or `memory` in the manifest (up to `python-max-seconds` and `python-max-memory-mb`), or hand Python fewer rows (`limit`, filters, a narrower `columns` well; `python-rows`); list the package in `packages`, and add it to `bi.python.packages` and the server's `drishti.bi.report.python-packages` if it is not allowed.

### A window function reads `null`, or a rank is not what I expected

* **The first row of a `lag` is empty, and the last of a `lead`.** There is no row before or after: that is the answer, not a fault. A "row" is a row of the visual, so a weekend with no trades is not a row (`lag(..., 1, trades.tradeDate)` is the previous date that has trades).
* **The whole measure is empty (masked).** The window's order column (or a partition column) is masked for you: a window never runs along a column you may not see.
* **A rank is 1 to 2 for a reader and 1 to 8 for another.** The row rules apply **before** the window: a rank is among the rows the reader may see.
* **`DRS-9020` *needs that column as a field of the visual*.** Put the order column (the date) on the axis, or choose another visual. **`DRS-9020` *cannot be filtered on*:** filter the rows, or use a top-N filter on the field, not a filter on the window measure.

### A query with measures of two tables answers `DRS-9020` *no many-to-one path*

Since the several-facts query is built, measures of unrelated fact tables are asked together when the fields you group by are reachable many-to-one **from each fact** (a desk of the trades and a desk of the limits). The refusal now means: a field one fact cannot reach (group by a column of the other fact's table), a measure that mixes the facts in one expression, a window measure beside another fact, or a row rule on one fact that the other cannot honour. Group by a shared dimension, split the measure, or combine the facts in a composed dataset ([DATASETS.md section 3.1](DATASETS.md#31-several-unrelated-fact-tables-in-one-query)).

### A refresh shows `interrupted`, or runs at start-up

The state of every refresh is kept in `<BI store>/refresh/state.json`. `interrupted` is a run that was in progress when the server stopped; the next scheduled time (or **Refresh now**) clears it. A schedule whose time passed while the server was down runs once at start-up (`schedule (catch-up)` in the history). Delete the file to start the history again; an unreadable file is ignored with a warning in the log.

### A python measure is refused in a query

- **Cause:** the SQL engine cannot run Python (`DRS-9013`).
- **Fix:** show the measure in a **python visual** (`wells: { values: [<the measure>] }`); see [PYTHON_BI.md](PYTHON_BI.md) section 5.

### A Python dataset's Transform will not run

* **A Python dataset (`source.python`) answers `DRS-9074`.** The server starts the console's runner itself: set `drishti.bi.transform.python` to the Python with pandas and pyarrow (the console's `.venv/bin/python`) and `drishti.bi.transform.runner` to the console's `core/transform_cli.py`; with neither set it looks for `drishti-console/` from the working directory upwards. `DRS-9071`/`9072`/`9073` are the sandbox's, as for any Python item ([PYTHON_BI.md](PYTHON_BI.md) section 10); `DRS-9075` is a frame that lacks a declared column.
* **The Transform runs again for every person.** Its result is kept per reader and version of the inputs (`drishti.bi.transform.cache-ttl`, `cache-entries`): a different person has different rows and masks, so a different frame. After a data load the inputs' version changes and the next reader runs it again.
* **A dataset with `from:` or `derived:` says `DRS-9190`.** It is a 2.1 composed or derived dataset, removed in 2.2: `drishti.py bi migrate --models` converts it (see the migration rows further down). The 2.1 entries for them were replaced and are history, archived in [DATASETS_2_1_COMPOSED_DERIVED.md](../archive/DATASETS_2_1_COMPOSED_DERIVED.md#troubleshooting-in-21).

## Drishti*net

Several servers as one ([NET.md](../admin/NET.md)). Each node's log names what the cluster does (`net: node ... joined`, `member ... joined`,
`... is suspect`, `... is dead`, `refused a call from ...`), and **Admin → Net** shows the member table, the files and the refused calls.

### A node will not join (it lists no members)

**Check.** Admin → Net on the node: is the summary *Cluster on*? Is `node.yaml` **refused** in the Files tab (its problem says why)? The log at
start: `net: node n-... joined Drishti*net at <address> (incarnation 1, seeds [...], 0 saved members)` lists the seeds it tries. From the node's
machine, `curl -s <seed>/api/v1/net/ping` must answer `401 DRS-5051` (the seed is there and wants a cluster credential), not a connection error.

**Fix.** `enabled: true` needs `secret: ${DRISHTI_NET_SECRET}` with the variable set in the server's environment; the node advertises
`advertise-url`, or without it this machine's primary IPv4 address with the server's port (Admin → Net → Members: *detected*). The seed's
address and port must be reachable (firewall); the seed must itself be enabled. One seed is enough; a node with none and no `peers.json` stays alone.

### `... is a loopback or wildcard address: the address this node advertises must be an address other machines can call`

Since 2.6.0 members advertise only a real address: `localhost`, `127.x.x.x`, `::1`, `0.0.0.0` and `::` are refused in `advertise-url`
in any spelling (`localhost.`, `0`, `2130706433`, `0x7f000001`), with link-local and container-bridge addresses and a host name that
resolves here to one of them (`… (oryxpro is 127.0.1.1 here: loopback)`: the machine's own name in `/etc/hosts` on Debian and Ubuntu)
(node.yaml) and in a seed's `url` (`peers/*.yaml`), by `drishti.py net check|set|add-peer`, Admin → Net → Files and the server when it loads the
folder (the file is then shown **refused**). Even three nodes on one machine use the machine's address. **Fix:** the address the message names
(`this machine's address is 192.168.1.20: use http://192.168.1.20:18784`), or leave `advertise-url` out. A machine with no address but loopback
cannot be a member. A member that advertises such an address (an older node) is never kept nor passed on by the others: it is missing from
every member table but its own. The console's own `backend.urls` may still name `127.0.0.1` ([NET.md section 2](../admin/NET.md#2-a-cluster-of-three)).

### Admin → Health says *Last administrator kept*

Two administrators on two nodes disabled, demoted or deleted the last two enabled administrators at about the same moment. Each node checked
the last-administrator rule (`DRS-6006`) for its own change only; the node applying the other's change repaired it: it enabled again the
administrator enabled most recently (or kept the one taken away), sent that to every node and audited it as `last-admin-kept`. **Check:** the
audit log (Admin → Audit, action `last-admin-kept`: who was kept and why). **Fix:** nothing is broken; if the kept administrator should not be
one, make another administrator first, then disable that one ([NET.md section 7](../admin/NET.md#7-routing-where-a-request-goes)).

### The log says `refused a call from ... bad signature` (or a node is listed as refused on Admin → Net)

The two nodes do not have the same cluster key. **Fix:** the same `DRISHTI_NET_SECRET` on every node; during a key rotation, follow the three
steps of [NET.md](../admin/NET.md) so every node accepts both keys before any signs with the new one.

### The log says `signed 75 s away from this node's clock`

A call is refused outside a 60 s window. **Fix:** keep the clocks in step (NTP on every node).

### A page says `DRS-5052 ... cannot be reached now` or `DRS-5053 ... did not answer`

The page's kind (or its data) is served by another node of the cluster, and that node is down, left, drained or slow. **Check:** Admin → Net →
Members (its state) and Routing (*Explain a request* for the kind and id: which node it would go to). **Fix:** start or rejoin that node, or undrain
it; a slow node: its own Admin → Health. The page works again by itself once the node is back; nothing on this node needs to change.

### A screen says *Your selections were reset ... Pick again.*

A screen with clickable rows (the book desk, or a page the Sutra designer gave a *When I click*) keeps what you picked on the server that
drew it ([NET.md, One server per screen](../admin/NET.md#one-server-per-screen)). That server went away (*because server X went away*,
`DRS-5052` from the server), or your selections expired or that server restarted. **Fix:** click the row again; the screen carries on, on
a server that is up. Nothing is lost but the pick. If it happens often, check Admin → Net → Members for a node that keeps leaving.

### A search says it is partial and names a node (`node n-c ...` in *failed*)

That node holds data of the kind and did not answer the search in time (`scatter-budget`, 3 s), failed, or is down (`not asked: it cannot be reached
now`). The rows of every other node are there. **Check:** Admin → Net → Members (its state) and Routing (its traffic and latency). **Fix:** bring it
back; a node retired for good: **Remove** it from the member table once it is dead or left, and the searches stop naming it.

### The console keeps moving between servers

**Check:** Admin → Health, *Console servers*: the server set aside and its last error; the console's log: `server ... set aside after 2 failed requests
in a row: ...` and `console moved from ... to ...`. **Fix:** the server it leaves (its health, its network); on a flaky network raise
`backend.fail_after`. A set-aside server is used again, for new sessions, a minute after it answers again.

### A member flaps between alive and suspect

Its heartbeat stops rising for `suspect-after` (5 s) now and then: a slow network, or long pauses on that node (garbage collection, a swap).
**Check:** the latency on Admin → Net, and the flapping node's log. **Fix:** raise `suspect-after` (and `dead-after`) in `gossip.yaml`, or fix the
pauses. A suspected node refutes the suspicion by itself as soon as it hears of it.

## Error codes

| Code | Meaning |
|---|---|
| `DRS-1001` | no source holds that entity. When the kind's routed connector is off and others serve the kind, the message names the ones asked and the one that is off (`… (asked trading-store; trade-endur, which kind 'trade' is routed to, is disabled)`): the id may live in the connector that is off |
| `DRS-1002` | no source serves that kind. "connector news is disabled (kind 'news' is routed to it)": nothing else serves the kind on this server. For the news-and-markets pack, build the snapshot store (`drishti.py lake demo --only news-and-markets`): a switched-off internet connector's kinds are then read from `news-store` (Admin → Connectors: *off (served from the snapshot)*), and what it lacks is `DRS-1001` naming it. Or switch the live connector on (`DRISHTI_NEWS_ENABLED=true`) |
| `DRS-1005` / `DRS-1006` / `DRS-1007` | pasted document is not a JSON object / a connector plugin could not be loaded (`/sources` lists why) / `knownAt` asked of a store with no earlier versions |
| `DRS-1011` | a kind is routed to a connector that has no file in `config/connectors/` (HTTP 404). Cause: the pack names a connector nobody defined, or its file was deleted. Fix: create it in Admin → Connectors (the Create link on Admin → Packs → Data source pre-fills it from the pack's template) or `drishti.py connector apply NAME.yaml`; the pack itself loads and Admin → Health shows `connectorsNotConfigured` |
| `DRS-1012` | history queries: the kind keeps no daily history here. Cause: its pack says `history: {<kind>: {enabled: false}}`, `drishti.history.enabled` is off, or every connector of the kind holds today's data only (REST, a feed) and the pack declares no embedded `series:`. Fix: route the kind to a dated store (Delta Lake, a dated folder, a database with a date column) or add `series:` to the kind's `history:` block |
| `DRS-1013` | history queries: the entity has no data in the range. Fix: widen the range (`TRD X 3M`), or move the date box to a date the entity has data |
| `DRS-1014` | history queries: the connector did not answer within `drishti.history.timeout` (10 s), or `drishti.history.max-concurrent` history requests were running. With some days read the page shows them and says which days are missing. Fix: a shorter range, or raise `timeout` and look at the connector on Admin → Health |
| `DRS-1016` | a command an administrator turned off (HTTP 403): its page shows "<CMD> is not active on this server", links to it are struck through, and its panels on other pages say "not active (turned off by an administrator)". Cause: an administrator's switch on Functions (`FUNC`; or `drishti.py server commands off`). Fix: an administrator turns it on again there; the history says who turned it off, when and why. On a cluster every server follows within seconds; a server shown "catching up" on Admin → Commands has not had the change yet |
| `DRS-1015` | the connector the kind is routed to is restarting (its connector file was changed or replaced, a retry) and was not back within 30 s. A read of the kind waits that long for it, so a quick swap (Delta to Cassandra and back) is not seen; a start that takes longer answers this `503`. Fix: Admin → Connectors shows why the start is slow or failed |
| `DRS-1003` / `DRS-1004` | a source failed / timed out (a console page answers 504 for a timeout, 502 for a failed source) |
| `DRS-2001` / `DRS-2002` / `DRS-2003` | Sutra parse error / invalid / not found |
| `DRS-2005` / `DRS-2006` / `DRS-2007` | proposal not found / stale / four eyes |
| `DRS-2004`, `DRS-2009`–`DRS-2012`, `DRS-2020`–`DRS-2039` | a Sutra file failed a check: each code is explained in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#problem-codes) |
| `DRS-2032` | a Sutra file could not be read at all (the log has the stack trace); the rest load |
| `DRS-2033` | a Sutra's YAML has a key written twice, a second document or a tag |
| `DRS-2040`–`DRS-2044` | a pack's `config/about.yaml` has a problem (unknown key or bad version, kind not in the pack's lineage, a template that does not compile, `use` with no vocabulary entry, a text over the cap); listed by `GET /api/v1/sutras/problems` as `<pack>/about.yaml`; the entry is left out and the page is unaffected; see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#problem-codes) |
| `DRS-2045`–`DRS-2047` | `sutra lint` help warnings (not load errors): a Sutra binds `F1`, an about `panels.<id>` matches no panel, a shown field has no glossary entry; `--strict` fails the run on them |
| `DRS-2048` | a shared panel block cannot be used: `use:` names no block or version, `with:` names a parameter the block does not declare, a block uses another block (only a fragment may, at pinned versions, at most 3 deep, without a cycle), or a block file is misnamed ([Shared panel blocks](RACHANA_REFERENCE.md#shared-panel-blocks-use)) |
| `DRS-2055` | a fragment's `doc:` docstring is missing or incomplete (summary, description, every parameter with type, default and meaning, emits, expects, examples, since; a composite's `uses`): the fragment is not loaded and its uses fail with `DRS-2048`. For a Sutra or an older block it is a warning only (`sutra lint --docs` prints it, never fails on it) ([Fragments and docstrings](PACK_MODELLING_GUIDE.md#fragments-and-docstrings)) |
| `DRS-2056` | a child panel nested more than 3 deep (screen, child, grandchild at most) | flatten the screen: move the deepest children up a level |
| `DRS-2058` | a panel's `form:` does not read: an unknown key, a field type, a `step` or `key` naming nothing, a rule reading a field the form does not have, a pattern that does not compile | the message names the key; see [SUTRA_FORMS_GUIDE.md](../guides/SUTRA_FORMS_GUIDE.md) |
| `DRS-2057` | an event or source reference is wrong: a panel named in `@panel.event` or a `source:` that does not exist, a cycle, a panel called `parent`, or a trigger not yet supported | fix the panel name or the event; `sutra lint` names the panel and line |
| `DRS-2059` | a Sutra's `layout:` does not read: an unknown key, columns that are not the 12-unit grid, a panel placed in an area or section the picture does not have | fix the name in `layout.areas` or `layout.sections`; [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#layout-the-grid-areas-split-tabs-sections) |
| `DRS-2060` | A search form (the `form:` section of a pack, or a design's Form tab) is not valid YAML or names a field path, type or operator wrongly; the message says which field and what is allowed |
| `DRS-2061` | A pack's `history:` block is wrong (an unknown shape, more than 4 chart fields, a `headline` not among `chart`, a `max-days` above `drishti.history.max-days`, a column with both `path` and `expr`); the message names the key. The kind keeps the default history meanwhile. Fix it and run `sutra lint <pack-dir>` |
| `DRS-2101` / `DRS-2102` | expression syntax (or past the size limits) / evaluation error |
| `DRS-3001` | reserved; nothing raises it in this release |
| `DRS-4001` | command not understood |
| `DRS-4002` | building a view failed unexpectedly (the server log has the stack trace) |
| `DRS-4003` | bad business date |
| `DRS-4004` | bad search |
| `DRS-4006` | `?panel=` of the explain endpoint names no panel of the view |
| `DRS-4007` | *Ask about this page* is switched off, globally or for the page's pack (`drishti.explain.ask.enabled`, `packs`); the box is then not drawn |
| `DRS-4008` | *Ask*: the model endpoint failed, is not configured, answered something unreadable (`502`) or took longer than `drishti.explain.ask.timeout` (`504`); the server log (`drishti.ask`) says which, never the key or the prompt |
| `DRS-4009` | *Ask*: more questions than `per-user-per-minute` or `per-user-per-day`; wait and ask again |
| `DRS-4010` | A regular expression the search refuses (`matches`, the form's *matches regex*): longer than 200 characters, too large once expanded, or using back-references, look-around, atomic groups, possessive quantifiers or `\b`. The message says which; rewrite the pattern with classes, groups, alternation and `* + ? {n,m}` |
| `DRS-4011` | A regular expression ran longer than `drishti.search.regex.timeout-ms` (50) on a single value; simplify it or anchor it with `^` |
| `DRS-4015` | The AI layer or the MCP client is off: set `drishti.ai.enabled` / `drishti.mcp.enabled` and configure a provider ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md)) |
| `DRS-4016` | The AI provider or MCP server failed or timed out: Admin → AI shows its health; check its URL, key variable and TLS |
| `DRS-4017` | Over the AI rate or cost limit for your user: wait, or ask an admin to raise the policy limit |
| `DRS-4018` | The AI policy does not allow this feature for your role |
| `DRS-4019` | The model, provider, MCP server or tool named is not configured: check `config/ai/` or `config/mcp/` |
| `DRS-4020` | BI with AI: the model's answer could not be used. A draft that did not pass the report check (after one repair) or an answer that is not a query is refused, nothing is changed: say it more plainly, name the measure or field, or try a stronger model (`drishti.bi.ai.model`). With the `mock` provider this is what a question no rule matches answers ([BI_AI.md](BI_AI.md) section 7) |
| `DRS-4040` | the console: Admin → Connectors → template updates was asked an action it does not have (HTTP 404); only the actions on the page exist |
| `DRS-4021` | history queries: the range spans more business days than `max-days` (the kind's, else `drishti.history.max-days`). Fix: narrow it with `FROM … TO …` |
| `DRS-4022` | history queries: the range cannot be read: `TO` before `FROM`, a `FROM` in the future, an unreadable date or range. Use `30`, `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD` or `FROM yyyy-mm-dd [TO yyyy-mm-dd]` |
| `DRS-5001` | bad request |
| `DRS-5002` | forbidden (role or pack) |
| `DRS-5003` | raised by the console only: it cannot reach the server, a live stream was refused, or a browser is over its live-subscription limit |
| `DRS-5005` | a request body or a design was over a limit of the builder (HTTP 413): too many or too large samples, a Sutra, notes or tests over their caps (`max-sutra-kb`, `max-notes-kb`, `max-tests-kb`), or a request body over `max-total-mb`; send fewer or smaller documents, shorten the text, delete scratch designs |
| `DRS-5004` | no cache by that name (cache purge) |
| `DRS-5006` | no such Build design, or it is not yours (a design is only ever reachable by its owner) |
| `DRS-5007` | an edit built on an older revision of a design (reload it, then edit again), or an undo/redo with nothing to move to (HTTP 409) |
| `DRS-5025` | a step could not be replayed when a design was rebased onto a newer version of its base (listed in `problems` with the step and why) |
| `DRS-5020`–`DRS-5024` | one operation of a Build edit was refused (listed in `problems` with its index): malformed, no such panel, option not accepted, bad value, text not editable in place |
| `DRS-5010` | not signed in |
| `DRS-5011` | a data-load call named a pack that is not loaded (or one the caller may open nothing of): see [DATA_LOADS.md](DATA_LOADS.md#12-troubleshooting) |
| `DRS-5012` | a data-load call named a kind the pack does not own (the message lists its kinds) |
| `DRS-5201` | a row of the date's P&L vectors was rejected when the VaR engine loaded the date (wrong length, a null or NaN, not USD): the answer's provenance lists the trade and the reason; fix the row upstream and reload the date |
| `DRS-5202` | a trade has two P&L vectors on one date: the date is not loaded; write one row per trade, date and scenario set and signal the load again |
| `DRS-5203` | the date's VaR inputs are not one run (several `BATCH_ID`s, another `SCENARIO_SET_ID`, two scenario sets): the previous date is answered with a warning until the date is rewritten as one run |
| `DRS-5204` | the date's vectors exceed the VaR engine's memory budget: raise `drishti.var.memory-budget-mb` or the heap, or lower `drishti.var.hot-dates` |
| `DRS-5205` | the pre-deal what-if of the VaR basket tray (`POST /api/v1/var/what-if`) runs in the Python VaR engine (`var-whatif`, compute pool `var`): it is not running (`DRISHTI_COMPUTE_ENABLED=true` starts the compute workers) or did not answer in 30 s |
| `DRS-5206` | a trade sent as scenario PVs has a scenario that failed to price (a null or NaN in `SCENARIO_PV`): the trade is not loaded and the provenance lists it with the scenarios; reprice the scenarios upstream (or send the row with `STATUS: failed` and no PVs, counted as failed) and reload the date |
| `DRS-5207` | no FX for a currency on the date's scenarios: either a reporting currency asked with `ccy=` (the answer is refused) or a trade's PVs in that currency (the trade is not loaded). Send the currency's scenario rates in `fx-scenario`, or load enough FX history in `fx-spot` to cover every scenario day and the business day before the first; the message names the days missing |
| `DRS-5208` | a PV record does not say what it is (2.9.4): add `VALUE_TYPE`, `GRAIN`, the id, `CURRENCY` and `SOURCE_SYSTEM` at the source, or `drishti.py var convert FILE --set ...` for an older file; if every record of a date is refused, check that the connector's layout promotes those columns (a `config/connectors/risk-store.yaml` from before 2.9.4: VAR_GUIDE.md section 8.4) |
| `DRS-5209` | a record disagrees with its system's pin (`drishti.var.inputs`): the source changed what it sends, or the pin is wrong; fix one of them (the pin is read at start) |
| `DRS-5210` | one source system sent two forms or grains on one date (or a supplied position covers a trade also sent on its own): resend the date in one form and grain per system; the message names the system and what arrived |
| `DRS-5440` | the exposure engine does not answer this scope or parameter: scopes are `NSET:`, `CPTY:`, `GROUP:`, `LE:`, `DESK:`, `BOOK:`, `TRD:`, `BANK`, `WHERE:`; `q` in (0.5, 0.9999]; `collateral` lagged, current or none; `date` a primary grid date or tenor |
| `DRS-5441` | the date's exposure run is not one run (its grid, two batch ids, a duplicate row, a trade in two netting sets): the date before answers with a warning until the run is rewritten whole and its load signalled again |
| `DRS-5442` | a what-if's candidate paths are of another simulation run: send paths of the date's run (its `RUN_ID`, its number of paths) |
| `DRS-5443` | the scope sums more netting sets than an interactive answer may: read the official record (`GET /api/v1/exposure/official`), narrow the scope, or raise `drishti.ccr.exposure.max-scope-netting-sets` |
| `DRS-5444` | a row of the date's exposure paths was rejected (another length than the run's `PATHS`, NaN, a grid index outside the grid): the answer's provenance lists the trade and the reason; fix the row upstream and reload the date |
| `DRS-5445` | the date's netting-set matrices exceed the exposure engine's memory budget: raise `drishti.ccr.exposure.memory-budget-gb` or the heap, or lower `drishti.ccr.exposure.hot-dates` |
| `DRS-5446` | the exact what-if (`method: paths`) runs in the Python exposure engine (`exposure-whatif`, compute pool `exposure`, `DRISHTI_COMPUTE_ENABLED=true`): it is not running or did not answer in time; use `method: addon` (a conservative proxy) or start compute |
| `DRS-5031` | a connector file or body is not valid (HTTP 422): bad name, unknown plugin, unknown top-level key, a missing required setting, a wrong type, a `name:` that is not the file name, or a literal credential. Fix: the message names the setting; credentials are `${ENV_VAR}` or `file:/path`; see [CONNECTOR_FILES.md, section 12](../connectors/CONNECTOR_FILES.md#12-health-and-problems) |
| `DRS-5032` | a connector change conflicts (HTTP 409): the version you read is stale (reload and apply again), a file of that name exists and no `If-Match` was sent, or the change switches off or deletes a connector packs use (repeat with `confirm=true`/`--confirm` once you have read who uses it), or you tried to delete a connector a pack's template names (reset or disable it) |
| `DRS-5033` | no connector of that name (HTTP 404): check the spelling with `drishti.py connector list`; the file name is the name |
| `DRS-5040` | Admin → Artifacts: no such installed pack, connector or BI file, or no such archive (HTTP 404): check the name in the list; an archive older than `drishti.admin.uninstalled-keep` is pruned |
| `DRS-5041` | an uninstall (or switching a pack off) was refused because other things depend on it (HTTP 409): the answer lists them. Disable it instead, or tick *Also disable or uninstall what depends on it* and type the name ([ARTIFACTS.md](../admin/ARTIFACTS.md#4-uninstall)) |
| `DRS-5042` | a restore or a BI upload found an artifact of that name already there (HTTP 409): open it to compare, then choose *replace* (the one in the way is archived first) |
| `DRS-5043` | a destructive step needs the artifact's name typed (HTTP 422): uninstall with dependants, replace, delete an archive for good |
| `DRS-5044` | the action does not apply (HTTP 422): a connector a pack's template names (disable or reset it), a pack named in `drishti.packs.enabled` (take it out of the setting, or disable it), a connector with no file (it comes from `application.yaml`), or an uploaded BI file that is not valid (the problems are listed) |
| `DRS-5050` | Drishti*net is off on this node (HTTP 404): its `config/net/node.yaml` does not say `enabled: true`, or the node left (Admin → Net → Rejoin). See [Drishti*net](#drishtinet) |
| `DRS-5051` | a call between nodes was refused (HTTP 401): no cluster credential, a bad signature (another key), signed more than 60 s away from this node's clock, or an on-behalf-of token that is expired, for another node or not signed with the key. Admin → Net lists each refusal with the address and the reason |
| `DRS-5052` | the node a request was forwarded to failed or answered something that is not an answer (HTTP 502); the next node was tried once. For a screen's clicks: the node holding the reader's selections went away (*your selections were reset ... pick again*) |
| `DRS-5053` | the node a request was forwarded to did not answer within `routing.yaml`'s `forward-timeout` (HTTP 504) |
| `DRS-5054` | the caller's roles may not reach that node (`access.yaml`), or the node is drained and takes no forwarded work (HTTP 403 or 503) |
| `DRS-5055` | a file of `config/net/` was not written (HTTP 409): it changed since you read it (read it again), or it has an error (the problems are listed, with their lines) |
| `DRS-5056` | a Drishti*net management request that cannot be done (HTTP 400): an unknown member, removing a member that is alive or suspect, a seed that is not `host:port` or an address, a file that is not one of `config/net/` |
| `DRS-5057` | content conflict (HTTP 409): the item (a Design) was changed by another node or tab since you read it, or it already exists. Reload and make the change again (docs/architecture/CONTENT_STORE.md) |
| `DRS-5058` | a content-store key that is not valid (HTTP 400): empty, too long, a `..` segment or control characters |
| `DRS-5059` | the content store could not be reached (HTTP 503): its database, bucket or shared folder failed; see the server log and `config/store/store.yaml` |
| `DRS-9201` | compute is off on this server (HTTP 503): `compute.enabled` in `config/compute/compute.yaml` ([COMPUTE.md](../admin/COMPUTE.md#1-switch-it-on)) |
| `DRS-9202` | a compute request or decision refused before any work (HTTP 409): an unknown pool, requirements waiting for approval or rejected, an environment approved but not built, or four eyes (the person who asked cannot approve); the message says which ([COMPUTE.md section 10](../admin/COMPUTE.md#10-when-something-is-wrong)) |
| `DRS-9203` | a compute pool is busy (HTTP 429): its queue for the request's class is full; try again in a moment, or raise `workers.max` or the queue |
| `DRS-9204` | a computation passed its time budget and was stopped (HTTP 504): the pool's `timeout` (or `timeout-batch`), waiting for a worker included |
| `DRS-9205` | a computation failed (HTTP 502): it crashed, passed its CPU or memory limit, the sandbox refused something (a socket, a process, a write outside its scratch folder), or the engine refused in words; for any other failure the traceback is in the server's log under the request id |
| `DRS-9206` | no such compute approval request, or no such computation waiting or running on this node (HTTP 404) |
| `DRS-9301` | write-back is off on this server: an administrator sets `edit.enabled: true` in `config/edit/edit.yaml` (or `DRISHTI_EDIT_ENABLED=true`) and restarts (HTTP 403) |
| `DRS-9302` | a change was refused; the message says why: no Sutra's form with `save:` names the field, it is masked or read-only for you, a role is missing (the edit power, the field's roles, `restate` for a past date, `override` for Apply mine anyway), a reason is required, you tried to approve your own change, or a source command is not usable (its connector's writes are off or refused at load: Admin → Edits) (HTTP 403) |
| `DRS-9303` | the change is no longer awaiting approval (someone decided first), or the fields moved since you opened the record: reopen it and edit again (HTTP 409) |
| `DRS-9304` | the record's lane holds more changes than `lanes.max-lane-depth`: try again in a moment; the draft is kept (HTTP 503) |
| `DRS-9305` | no such change id (HTTP 404) |
| `DRS-9306` | edits are paused: the edit primary named by `edit.primary` is not reachable, or the cluster has no shared edit log (use PostgreSQL for the server's database, or name an edit primary); reading is not affected (HTTP 503) |
| `DRS-6001`–`DRS-6010` | user management (see [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md)); `DRS-6005` is a locked account |
| `DRS-6011` | a start-up warning in the server log, not an answer: a `drishti.security.redact` entry names no field of the loaded packs' samples, so it masks nothing. Write the field as the data spells it (masks match in any case and convention) or remove the entry; Admin → Health lists such entries under `masksMatchingNothing` |
| `DRS-7001` | a share link that is not yours, or does not exist (HTTP 404; the two look the same on purpose). Ask the sender to share it with you again |
| `DRS-7002` | a share had no recipient, or named someone you cannot address: an unknown user, one who uses none of your packs, a role that is not mentionable (HTTP 422). Search the picker for the name |
| `DRS-7003` | too many shares or directory searches in a minute (or shares today); wait the `Retry-After` seconds (HTTP 429). Limits are `drishti.collab.limits.*` |
| `DRS-7004` | sharing is off: `drishti.collab.enabled`, or `drishti.collab.packs.<pack>.share-enabled: false` for that kind's pack (HTTP 403) |
| `DRS-7005` / `DRS-7006` | no such thread or comment, **or one you may not see**: threads follow the right to open the entity (its kind and pack, and a panel's gate kind), so ask for access rather than the id (HTTP 404) |
| `DRS-7007` | the thread is locked by an administrator; ask for it to be unlocked, or start a new thread (HTTP 409) |
| `DRS-7008` | a comment can be edited only by its author and only within `drishti.collab.threads.edit-window` (default 15 minutes); after that, retract it and write another (HTTP 403) |
| `DRS-7009` | someone changed the comment since you opened it; reload it and edit again (HTTP 409) |
| `DRS-7010` | `DELETE /admin/collab/threads/{id}` of a thread a legal hold covers (HTTP 423); release the hold (`DELETE /admin/collab/holds/{id}`, compliance role) first |
| holds, exports or verify answer `403` to an administrator | they need the `compliance` power, which `admin` does not imply: grant `roles.<role>.compliance` (or the role's flag in `PUT /admin/role-definitions/{role}`) to the compliance role |
| the purge keeps something older than retention | an active legal hold covers it (`POST /admin/collab/retention/run?dryRun=true` counts `threadsHeld`/`sharesHeld`); the scope may be `user` (anything the person wrote, sent or received) or `all`. Nothing is purged at all while `retention.keep-days`, `retention.kinds` and `packs.<pack>.retention-days` are all 0 (the default) |
| an export is `failed`, or its download is `400` | `GET /admin/collab/exports/{id}` shows the error; an export is downloaded once (ask again), only by the person who asked, and is gone after `export-keep` or a server restart that interrupted it |
| `verify` reports a thread whose chain `was changed` or `does not follow` | a revision row was edited or removed outside the application; compare with the archived `chains.jsonl` of an earlier export, and restore from backup. The report names the revision |
| a mention reached nobody | a mention notifies only people who may open the entity's kind with its pack assigned; with `share.undeliverable: tell` the answer lists who was skipped and why. A name that is no one in your directory scope stays plain text |
| a comment shows `•••` where I typed a value | the value is a masked field's; readers without `raw` see the mask. Quote it with `{$.path}` instead: each reader sees their own view of it |
| `DRS-7011` | the note or comment was refused: empty, over `share.max-text`, matches `text.deny-patterns`, holds the value of a masked field under `on-masked-copy: reject`, or the page's generation is newer than the server's (HTTP 422). Reload the page and send again |
| `DRS-7012` | email was asked for but is off or unreachable (HTTP 503); the share can still be sent in Drishti only |
| `DRS-7013` | a bridge test failed, or the bridge cannot post (HTTP 503): bridges off, its `url-env` variable unset, the URL not under `bridges.allow`, or the endpoint answered an error; the message says which |
| `DRS-7014` | no bridge with that name in `drishti.collab.bridges.webhooks` (HTTP 404) |
| `DRS-7015` | a share asked for a picture where snapshots are off: `drishti.collab.snapshots.enabled` is `false`, or `drishti.collab.packs.<pack>.snapshots.enabled: false` for that kind's pack, or no recipient may open the view (HTTP 403) |
| `DRS-8001` | an embedded view or its token request was refused (HTTP 401, or 400 `invalid_grant`/`invalid_target` at the token endpoint): the embed token is missing, malformed, expired or for another audience, or embedding is off (`drishti.embed.enabled`); also the console's own check: a signature the server's key does not make (a token from another server, or the server's key changed and `embed.jwks_min_refetch_seconds` has not passed), a token for an audience not in the console's `embed.audiences`, or a live stream whose token expired and no fresh one reached `POST /embed/v1/channel/{cid}/token` within `embed.token_grace_seconds` (the element renews itself; a host whose `tokenProvider` fails or returns the same token ends the stream). The host's backend gets a new token (they last 5 minutes); check that its `audience` is one of `drishti.embed.audiences` and that the clocks agree; the server must run with a persistent `drishti.embed.signing-key` |
| `DRS-8002` | the request's `Origin` is not one of the host application's registered origins (HTTP 403), or the token endpoint was called from a browser. Register the exact origin (scheme, host, port) under the application, or call the token endpoint from the host's backend |
| `DRS-8003` | the host application is unknown or disabled, its client secret or key is wrong (token endpoint `invalid_client`, HTTP 401), or embedding is off (HTTP 403). Check the application's id and secret, whether it was disabled, whether the secret was rotated |
| `DRS-8004` | over the embed rate (HTTP 429): the application's calls per minute, one user's, or the application's token requests; `Retry-After` says when. Raise `callsPerMinute`/`userCallsPerMinute` on the application or `drishti.embed.limits` if the traffic is real. The console has its own: `embed.rate_per_minute` (calls per application) and `embed.max_streams` (live subscriptions of all embed channels together; a page that opens a channel past it is refused with `Retry-After: 5`) |
| `DRS-8005` | the host application may not show this kind or report (HTTP 400): its `kinds` list leaves it out. Add the kind (`trade`) or the report (`report:<id>`, or `report:*` for every report) to the application in Admin → Embedding, or leave the list empty. A **403** with this code from the console says the report is not published: only a published report is embedded |
| `DRS-8006` | the element's contract version is no longer served (HTTP 410, raised by the console): upgrade the element (the request's `Drishti-Embed-Api` names a major version other than 1) |
| `DRS-9001` | business intelligence: no such workspace, item or version, **or you may not know it exists** (HTTP 404). A workspace you do not belong to, an item outside its audience and another person's draft all answer this. Ask the owner to add you to the workspace or its audience |
| `DRS-9002` | business intelligence: you lack the BI power or the workspace role the action needs (HTTP 403). The detail names it: the `bi`, `bi-author`, `bi-steward` or `bi-admin` power (an administrator grants it on a role in Admin, Roles or Admin, BI settings), or contributor, member or admin in the workspace. The BI menu is also hidden without `bi` |
| `DRS-9003` | business intelligence: something you sent is not valid (HTTP 422): the body is not YAML or not a mapping, it is larger than `drishti.bi.max-body-bytes`, a tag has a bad character, an audience role, user or pack does not exist, or a name or kind is wrong |
| `DRS-9004` | business intelligence: the state forbids it (HTTP 409): the workspace id is taken, there is nothing new to publish, the item is proposed (withdraw it first), a personal workspace cannot publish or be shared (move the item to a shared workspace), the last admin cannot be removed, the workspace still holds items, or the item is shipped content (change the file in the BI folder) |
| `DRS-9005` | business intelligence: four-eyes: you proposed it, so someone else must approve or reject it (HTTP 403); withdraw it instead of rejecting your own proposal. Switch off with `drishti.governance.four-eyes` |
| `DRS-9006` | business intelligence is switched off on this server (HTTP 403, `drishti.bi.enabled`) |
| `DRS-9007` | business intelligence: a stale save (HTTP 409): someone saved the item after you opened it. The designer offers **Reload theirs**, **Save mine as a copy** or **Overwrite with mine**; through the API, read the item again (its `etag`) or send the update without `baseEtag` to overwrite |
| `DRS-9010` | a dataset's YAML or a structure in it is not valid (HTTP 422): the problem names the path of the key; check it on the dataset page or with `POST /api/v1/bi/datasets/validate`; publishing an invalid dataset is refused with this code |
| `DRS-9011` | a measure, calculated column or row rule does not parse, names a table or column the dataset does not have, aggregates where it must not (or does not where it must), uses an unknown function, or refers to measures in a circle (HTTP 422) |
| `DRS-9012` | a relationship, hierarchy, masked column, row rule or rollup points at a table or column that does not exist, or a rollup lists a measure it cannot hold (a distinct count, a percentile, a condition inside an aggregate) |
| `DRS-9013` | a Python measure named in a query: **not built** for SQL (HTTP 501 as a query; a warning for a stored Python measure). Time intelligence (`ytd`, `mtd`, `qtd`, `prior_period`, `same_period_last_year`, `running`) and the window functions (`lag`, `lead`, `rank`, `dense_rank`, `percent_rank`, `moving_*`, `cumulative`) are built; one with the wrong arguments is `DRS-9011` (it names what it needs: an `n` from 1 to 1000, an aggregate, a column), a window along a date that is not a field of the visual is `DRS-9020` (*needs that column as a field of the visual*), a window cannot be filtered on (`DRS-9020`) or asked with the measures of another fact table |
| `DRS-9014` | a composed dataset (`from:` / `join:`) names a source dataset, column, measure or join key that does not exist, joins keys of different types, or leaves a source unjoined (HTTP 422); the problem names the path ([DATASETS.md section 11](DATASETS.md#11-composed-and-derived-datasets)) |
| `DRS-9015` | an error on a composed dataset (a warning when the join says `allow-repeat: true`): a join repeats rows (many-to-many, or the source with one row per key is on the wrong end), so the measures of the repeated side add up too high; declare the `key:` of each source, group a source by the key alone, or put the unique source on the right |
| `DRS-9016` | a dataset reads itself, directly or through the datasets it is composed of or derived from (HTTP 422): break the loop by taking the column from the underlying dataset |
| `DRS-9017` | a derived dataset has no `transform:` or no `inputs:`, or declares a column that is not a usable name or type (HTTP 422); as a warning: its columns are not known yet (declare `columns:`, or press Run the Transform now on its page) |
| `DRS-9020` | a query names a field, measure, filter operator, value or date the dataset cannot take, or the model has no many-to-one path joining what it asks for (HTTP 422; a row rule's tables are joined into every query a reader runs, so a rule can make a question unanswerable: the message names it) |
| `DRS-9021` | a query filters on or sorts by a column masked for you, reads a `direct` table of a kind you may not open, or (a composed dataset) joins on a column that one of its sources masks for you (HTTP 403): a masked column holds one constant, so the join would match everything to everything |
| `DRS-9022` | the answer is over `drishti.bi.query.max-rows` (or, as JSON, `json-max-rows`) (HTTP 413): group by less, filter, set a `limit`, or ask for Arrow |
| `DRS-9023` | the Arrow answer is over `drishti.bi.query.max-bytes` (HTTP 413) |
| `DRS-9024` | the query ran longer than `drishti.bi.query.timeout` and was cancelled (HTTP 504) |
| `DRS-9025` | too many queries at once, for the server (`max-concurrent`) or for you (`max-concurrent-per-user`) (HTTP 429): try again |
| `DRS-9026` | a table's connector is not running (Admin, Connectors says why), or is not a SQL store for a `direct` table (HTTP 502) |
| `DRS-9027` | the analytic engine failed on the compiled query (HTTP 500); the message is DuckDB's. A BI author can add `?explain=true` to see the SQL |
| `DRS-9028` | the query needs more memory than the engine may use, even spilling to disk (`drishti.bi.query.memory-limit`, HTTP 413): group by fewer fields, add a filter, or ask the model owner for a rollup |
| `DRS-9029` | the planner estimated more rows than the query's budget (HTTP 413): add a date filter, ask a rollup's fields, or raise `options.budget.rows` (the message has the estimate) |
| `DRS-9030` | a report's YAML or a structure in it is not valid (HTTP 422): not YAML, an unknown visual type, a grid position that does not fit the 12 columns, a duplicate id, an operator that is not one, an image from another address. The problem names the path of the key; check it in the designer's YAML tab or with `POST /api/v1/bi/reports/validate`; publishing such a report is refused with this code |
| `DRS-9031` | a report names a field, measure, hierarchy, page, bookmark or slicer that its dataset or the report does not have (HTTP 422); the message lists what exists. A dataset that changed under a report is the usual cause |
| `DRS-9032` | a visual is built wrongly (HTTP 422): a measure in a field well or a field in a measure well, a well the visual does not have, a required well left empty, more items than the well takes |
| `DRS-9033` | the report uses a visual type the grammar reserves and this server does not build (today only `shape`; the message lists the types that are built) (HTTP 501) |
| `DRS-9034` | a report's dataset cannot be found (a catalogue id, a file name such as `trades-basic`, or the dataset's name) or has errors, so the report cannot be drawn (HTTP 422). Open the dataset's page to see its problems |
| `DRS-9035` | a visual query names a page, visual or slicer the report lacks, or its state is not valid: a drill path past the last level of the hierarchy, a selection from a visual that is not on the page, dates that are not `yyyy-mm-dd` (HTTP 422) |
| `DRS-9036` | (2.4.0) a theme item is not valid: its palette is not 3 to 12 `#rrggbb` colours, a colour is not `#rrggbb`, its font family is not one of `sans`, `serif`, `mono`, `system` (nothing is fetched), its size is not 10 to 18, or one of its `visuals:` defaults is a format key the visual family does not take or a value out of range (the format schemas, REPORTS.md section 5); on a report, a warning that the theme it names cannot be found: it draws with the default colours (HTTP 422) |
| `DRS-9037` | (2.6.0) a well names `param.<name>` that is not a field parameter of the report's model, or a field parameter whose choices do not fit the well (a field in a measure well, a measure in a field well), or the report is on a dataset, not a model (HTTP 422); asked with a value the parameter does not list, the visual answers it too. The message names the parameter, the well and what fits: put the parameter in a field or measure well, or choose one of its values ([REPORTS.md section 21](REPORTS.md#21-field-parameters-in-a-well-260)) |
| `DRS-9038` | (2.6.0) a statement is not valid (HTTP 422): a paper, orientation, margin, header or footer part, token, period or text size the report's `statement:` cannot have, a visual a statement page does not draw (a chart), a table sharing its row, a `groupBy` the band does not show; or it cannot be made: the margins leave too little paper, or it runs past `drishti.distribution.statements.max-pages` ([STATEMENTS.md section 8](STATEMENTS.md#8-checks-drs-9038)) |
| `DRS-9040` | publishing the dataset would break published reports (HTTP 409): a field, measure or hierarchy they use is removed or renamed. The message lists the reports and what each uses. Fix the reports first, publish the dataset with the names kept (add the new name beside the old), or have a `bi-admin` publish with `force` ([BI_GOVERNANCE.md](BI_GOVERNANCE.md#6-impact-what-breaks-if-i-change-this)) |
| `DRS-9041` | a BI bundle cannot be used (HTTP 422): not a bundle of this format, a file fails its checksum, it is larger than `drishti.bi.governance.bundle-max-bytes`, or an item in it is not valid. The message says which |
| `DRS-9042` | a bundle import is refused (HTTP 409): items that changed here since the bundle was made would be overwritten (conflicts), or a workspace or owner it names cannot be mapped. Run it as a dry run to see the list, then choose `overwrite` or map the names |
| `DRS-9043` | publishing a report or dataset is refused (HTTP 409) because its expected values (`tests:`) fail or cannot run. The message names the first three: *expected 100 +- 1%, got 750*, or why one could not run (a masked figure, an answer of several rows that needs a `row:`, a role you do not hold). Run them yourself with **Check** (the designer) or *Run the tests* (the dataset page) as the role the test names, fix the item or the expected value and publish again; a `bi-admin` may publish with `force`, which the audit log records (`FORCED over N failing test(s)`). `drishti.bi.governance.refuse-failing-tests: false` turns the gate off. |
| `DRS-9050` | a live table cannot be kept (HTTP 422): it is over an array (`each`: a live table follows whole entities; use `mode: cached`) or its `live:` block is wrong (`rows` a positive number, `age` such as `90s`, `15m`, `2h`). When no running connector serves its kind the query answers `DRS-9026` instead |
| `DRS-9051` | too many live streams are open (HTTP 429), for the server (`drishti.bi.live.max-streams`, 100) or for you (`max-streams-per-user`, 10). Each open live report holds one, a board one per distinct report on it: close a tab or a board. The page says **Disconnected** with the reason |
| `DRS-9052` | a board is not valid (HTTP 422): not YAML, a bad name or id, a tile off the 12-column grid, an alert threshold that is not `{ op: lt, value: 0 }`, or a tile that names a report you cannot open, a page or visual it does not have, or a slicer. The problem names the path ([BOARDS.md](BOARDS.md#7-checking-a-board-drs-9052-and-drs-9053)) |
| `DRS-9053` | a board uses a reserved setting (HTTP 501): `notify`, `notifications`, `subscriptions`. Notifications arrive with distribution (phase 7); a tile only changes colour |
| `DRS-9060` | an export could not be made (HTTP 422): exports are off (`drishti.distribution.export.enabled`), the format is not `pdf`, `png`, `xlsx` or `csv`, the report or view cannot be read, the file is over `max-bytes` (export one page or visual, or narrow the filters), or it took longer than `timeout`. The message names the setting |
| `DRS-9061` | a subscription is not valid (HTTP 422): a schedule that is not one of `daily`, `weekdays`, `business-days`, `weekly mon HH:MM`, `monthly 1\|last HH:MM` (add `business`), `hourly`, `cron`, `after-load`; a format other than `html`, `pdf`, `xlsx`; an email address (outside addresses are refused, whatever the settings); a role or another person when you are not a BI author; a target you cannot open yourself; or more than `per-user` subscriptions |
| `DRS-9062` | an alert on a number is not valid (HTTP 422): a source that is not `visual`, `dataset` or `field` or lacks its keys; a condition with an `op` that is not `above`, `below`, `between` or `outside` (or `rises`, `falls`, `moves` for a change), a limit that is not a number, a `high` that is not above `value`, a change of zero or less, a `by` that is not `HH:MM`; a trigger with a schedule that is not one of the subscription words, or a `debounce` under `min-debounce`; a severity other than `info`, `warn`, `critical`; a negative hysteresis; **a source you cannot open yourself**; `live` on a source with no live table; email asked and no address on your account (or `drishti.alerts.measure.email` off); more than `per-user` alerts; or alerts off |
| `DRS-9070` | a Python item or its use is not valid (HTTP 422): an unknown manifest key, an `interface` that is not `measure` or `visual`, a package off the allow-list, no entry function in the source, a `target: browser` item that needs a package Pyodide lacks, a `timeout` or `memory` over the settings; a python visual with neither `format.python` nor a Python measure; an item for the browser only asked to run on the server. Publishing a Python item with such a manifest is refused with it |
| `DRS-9071` | the Python code failed (HTTP 422): an exception, a measure that did not return one number per group, a visual that returned neither an ECharts option, SVG nor PNG. The traceback is shown to `bi-author` only |
| `DRS-9072` | a Python limit stopped it (HTTP 504 for time, 413 for memory, input or output size) |
| `DRS-9073` | the Python sandbox refused something (HTTP 403): an import that is not on the item's list, the network, a file outside the scratch folder, a process |
| `DRS-9074` | the Python runner is not available (HTTP 503): all workers busy, switched off, `pandas` or `pyarrow` missing in the console, a package not installed, or no Pyodide for a browser run; for a derived dataset also `drishti.bi.transform.enabled: false`, the console's `core/transform_cli.py` or its Python not found (`drishti.bi.transform.runner`, `python`), or `drishti.bi.transform.max-concurrent` runs already going |
| `DRS-9075` | a Python Transform returned what a derived dataset cannot use (HTTP 422): a column the dataset declares is missing from its frame, or the Transform's inputs differ from the dataset's |
| `DRS-9076` | a Python item's own tests failed, so it is not published (HTTP 422, raised by the console): Check shows each test (`passed`, `FAILED` with expected and got, or `could not run` with the DRS code); fix the item or the `expect:`, or a bi-admin publishes with `force`. A call made straight to the server is not gated |
| `DRS-9077` | a Vega-Lite specification could not be drawn (shown in the visual's frame): it names a `url` or a `loader` (the data is the rows of the wells, or `data.values`), or the library did not load (check `/static/vendor/vega/6.4.0/` answers) |
| `DRS-9080` | no such BI run, or not one you may see (HTTP 404): check the id; a run shared with you needs its share ([BI_RUNS.md](../architecture/BI_RUNS.md#16-error-codes)) |
| `DRS-9081` | too many runs (HTTP 429): you have `queue.per-user` (5) runs queued or running, or the node's queue is at `queue.max` (200); wait for one to finish or cancel one, then retry after `Retry-After` |
| `DRS-9082` | the question is an ad-hoc heavy run above the confirm threshold (HTTP 409): the answer carries the estimate (a range and its basis) and a `confirmToken`; confirm it, narrow the dates, or add a rollup at the question's grain |
| `DRS-9083` | the run's result is not there yet (HTTP 409): it is queued, running, failed or cancelled; wait for it, or read its reason in the Runs tab |
| `DRS-9084` | the run's result expired (HTTP 410): retention (`retention.days`, 30 after the last open) or the storage budget removed it; re-run it on the same data or the newest data |
| `DRS-9085` | the run was interrupted (HTTP 503): the server stopped while it waited or ran, and it could not start again (attempts used up, your access changed, or its model changed); re-run it, and narrow it if it happens again |
| `DRS-9086` | the run is too large (HTTP 413): its estimate exceeds every engine's limit (`engine.max-read-rows`), or its result passed `store.max-result-rows` / `max-result-bytes`; aggregate, narrow the dates, add a rollup or configure an engine |
| `DRS-9087` | the node that owns the run did not answer (HTTP 503): try again later, or *Re-run elsewhere* |
| `DRS-9088` | the run result store is full (HTTP 507) and nothing can be removed: an administrator frees space or raises `store.max-bytes` |
| `DRS-9089` | a slicer, cross-filter or drill would need a background run (HTTP 409); nothing ran: use *Run in background*, or ask the model's owner for a rollup at this grain |
| `DRS-9100` | an expression in the old syntax (`[Measure]`, `&&`, `c ? a : b`, `true`, `sum(x, cond)`): refused by default since the end of wave A (`drishti.bi.expr.legacy: refuse`; `warn` reads it while a site converts); the fix shows the DEL v2 text, and `drishti.py bi migrate --expressions FILE --write` converts whole files. Stored items are converted by the server at start; one that is still refused is listed in Admin, BI settings ([BI migrations](#bi-migrations-22-wave-a)). Also a `rachana: 2` dataset's structure (Check, or HTTP 422 when it is published): an unknown key (a warning), no `source:` or two, a name starting `pack.` (those are the packs'), a `version:` that is not semver such as `1.0.0` ([BI_MODEL_SPEC.md section 1.11](../architecture/BI_MODEL_SPEC.md#111-dataset-problems)) |
| `DRS-9101` | a dataset column (Check): an unknown type, role, aggregate or additivity, `of:` naming no column, a `sum`/`avg` default on text, a query or Python dataset that declares no columns |
| `DRS-9102` | a dataset source the server cannot use (Check): a pack or kind that is not enabled, a query parameter that is not `as-of`, `business-date`, `from` or `to`, a `:name` in the SQL that `params:` does not bind; a dataset that only the model layer reads, met where a version-1 dataset is read (ask it with `POST /api/v1/bi/mq` and `model: dataset:<name>`) |
| `DRS-9103` | a time mode the source cannot give (Check): `live` over an array (`each`) or for a query or Python dataset, `history` with no `history:` block, a bad `every`, `age`, `storage` or `as-of` |
| `DRS-9104` | the grain (Check): a key that is not a column, a query or Python dataset with no `grain:`, a `unique` test naming no column |
| `DRS-9105` | `extends` (Check): the base is not published, a narrowing dataset with its own `source:` or a different column type, `where:` without `extends`, a chain that extends itself |
| `DRS-9106` | a dataset's own security (Check): a rule naming another table's column (write it in a model), a mask naming no column |
| `DRS-9107` | a pack-generated dataset was saved (HTTP 409): `pack.<pack>.<kind>` datasets are read-only; save a dataset that `extends: pack.<pack>.<kind>@1` to narrow it or add columns |
| `DRS-9108` | an input of a Python dataset, or the query of a `model` source, is not a model query (Check); the inner query's own code follows; or, when the dataset is read, the inner query's answer has no column for one the dataset declares (name the column's `path:` after a field or measure of the inner query) |
| `DRS-9109` | a model reaches itself through a dataset whose source is a model (Check, or HTTP 422 when it is asked): the detail shows the loop, `model:a -> dataset:x -> model:a`; read the dataset from another model, or put its source's datasets into the model directly |
| `DRS-9110` | a dataset whose root cannot be traced (Check): down its `extends` chain there is no source, or the root is a pack kind no enabled pack generates. Enable the pack, or extend a dataset that is published ([DATASETS.md section 14](DATASETS.md#14-derivation-subset-filter-grain-derive-group-22-wave-a)) |
| `DRS-9120` | a data model's structure (Check, or HTTP 422 when it is published): a bad name, version, alias, lifecycle, a dataset not published at the version range it names, a rollup on no alias ([section 2.11](../architecture/BI_MODEL_SPEC.md#211-model-problems)) |
| `DRS-9121` | a dataset of a model with no relationship to the others (a warning): it can be asked alone, never with their fields |
| `DRS-9122` | a relationship naming no column, columns of two types, an unknown cardinality or direction |
| `DRS-9123` | a relationship whose one side is not unique (not the grain key, nor a pack kind's `id`): one row would meet several; join to the key |
| `DRS-9124` | two active paths between the same two datasets: make one inactive |
| `DRS-9125` | model security that reaches across or loosens: a model rule naming another alias's column, `unmask`, a mask naming no column, rules given as a list instead of per alias |
| `DRS-9126` | a breaking change published without a new major version (HTTP 409): a measure, column or relationship removed (by stable id or name), a relationship made inactive, a grain key or a column type changed; or the version goes down. Publish it as the next major (consumers bound to the old major do not move) |
| `DRS-9127` | an expression (DEL) that is not DEL v2 or does not fit where it is written: a Python construct DEL refuses (method calls, lambda, comprehensions, subscripts, assignment), a bare column outside an aggregate in a measure (*wrap it: sum(...)*), an aggregate in a derived column or rule, `x == None` (*write x is None*), a positional `where` (*name it: where=...*), types that do not mix; a model measure that does not compile (the engine's `DRS-9011` conditions, with the model's path), a measure with both `expr` and `python` or neither. Check it with `drishti.py bi expr validate` ([EXPRESSIONS.md section 7](EXPRESSIONS.md#7-problems-and-their-fixes)) |
| `DRS-9128` | a contract naming a field or measure the model lacks |
| `DRS-9129` | a key or function of a later wave used now: non-empty `calc-groups` or `parameters`, `via:`, a `bridge` role, `many-to-many` or `direction: both`; in an expression `calc`, `share_of`, `parent_of`, `as_of`, balances (`last`, `closing` ...), `fx`, `convert`, `param.x`, `ALL` (wave B; [EXPRESSIONS.md section 6](EXPRESSIONS.md#6-functions) marks each) |
| `DRS-9132` | a warning in Check: a measure hidden for some roles (`hidden-for`) reads columns those roles still see, so a default measure or a `define` over them works out the same number. Hide the columns too (the warning lists them for the model's `fields:` under `hidden-for`) |
| `DRS-9131` | a warning in Check, never a refusal: a model's row rules (its own or its datasets') leave out roles that may read the dataset, so those readers see no rows of it (their answer says *you have no access to rows of <alias> in this model*). Add a rule for them (`where: "true"` to see every row) or leave them out on purpose |
| `DRS-9130` | the security safeguard (a warning in Check, HTTP 409 when the model is published): a role restricted by a row rule elsewhere in the model would see every row of a dataset that no rule restricts. Give that dataset a rule (in the dataset or in the model's `security.rows`), or say `may-see-all: { <alias>: [<role>] }` under the model's `security` (the publish audits it as `bi-model-may-see-all`) |
| `DRS-9140` | a report and the model it reads do not fit (HTTP 422 in Check, refused on save): a `rachana: 2` report names a dataset instead of `model: <name>@<major>`, names both `model:` and `dataset:`, or carries its own measures or perspective without a model; the console's model pages answer it when the request names no model (`?ref=<model>@<major>` or `dataset:<name>`). Fix: point the report at one model |
| `DRS-9141` | a visual of a report on a model reads another dataset; add that dataset to the model, or point the visual at the model |
| `DRS-9150` | a model query's shape (HTTP 422): an unknown key, a wrong type, `mq` missing or not 1, two time forms, a bad date, `params` (wave W6), a shape or format that is not one of the listed; the `fix` shows what to set |
| `DRS-9151` | no model by that name and version that you may read (HTTP 404): check `GET /api/v1/bi/models`; a retired major, or a model whose Check has errors (the first is named) |
| `DRS-9152` | a field, measure, hierarchy or stable id the model lacks (HTTP 422); `fix` names a near match (`trades.Desk` → `trades.desk`) |
| `DRS-9153` | a field or measure outside the model's `contract: { exposes: listed }` (HTTP 422); a deprecated measure says what to use |
| `DRS-9154` | a local measure (`define`) that shadows a model measure or does not compile (HTTP 422) |
| `DRS-9155` | a filter the field's type does not allow (`contains` on a number), a value of the wrong type, an unknown relative window (`fix` lists the names), a `top` without `n` or `by`, a `having` that is not one number, a drill past the last level, a totals level that is not a field of the query (HTTP 422) |
| `DRS-9156` | a field, filter or row rule that a measure's fact does not reach many-to-one (HTTP 422): group by a field every fact reaches (`fix` lists them) or ask the measures apart |
| `DRS-9157` | the time (HTTP 422): a window longer than `drishti.bi.mq.max-days`, `live: true` on a model with no live dataset, a sliding window without a history field, an as-of after the current business date |
| `DRS-9158` | a sum that adds amounts of several currencies in one group (HTTP 422 from wave A): the message names the groups; add the currency to the fields (each group splits by currency) or filter one currency. `drishti.bi.plan.guards: warn` answers with a warning instead, as W1 did |
| `DRS-9159` | a balance (semi-additive) measure added across several dates, asked with other measures and without the business date as a field (HTTP 422 from wave A): ask the balance alone (its last date's value, answered with a warning) or group by the business date |
| `DRS-9160` | a row extract (`shape: rows`) with fields of two facts (HTTP 422): each row of one would repeat for every row of the other; ask each fact's rows apart |
| `DRS-9161` | a filter, sort or `top` on a field masked for you (HTTP 403, the body also names `DRS-9021`); `fix` offers the dataset's key, which identifies the same rows |
| `DRS-9162` | a Python measure in a grouped model query (HTTP 422): a python visual runs it |
| `DRS-9163` | a field or measure hidden for your roles (`hidden-for`, HTTP 422): it is not available to your role on any endpoint; ask the model's owner, or use another field |
| `DRS-9164` | a `params` value the model does not take (HTTP 422): the message names the parameter, its kind and range; `GET /bi/models/{id}/describe` lists the parameters. A field parameter of measures in `select.fields` (or of fields in `select.measures`) is named as itself: *'param.metric' chooses among MTM, NOTIONAL: measures, so it goes in select.measures* |
| `DRS-9165` | a calculation group or item the model does not have (HTTP 422): `describe` lists `calcGroups`; the `fix` offers the names |
| `DRS-9166` | a filter-context function cannot be planned (HTTP 422): `via=` must name a relationship id, `parent_of` a hierarchy, `as_of` a fixed date (`date('…')`, `asof()`, `shift_date(asof(), -1)`); contexts nest at most three deep |
| `DRS-9190` | the expression converter (`drishti.py bi migrate --expressions`, `POST /api/v1/bi/expr/convert`) could not keep an old expression's meaning, so it left it as it was: rewrite it in DEL v2 by hand ([EXPRESSIONS.md section 10](EXPRESSIONS.md#10-the-old-syntax-and-the-converter)) |
| `DRS-9170` | a Drishti SQL statement that is not one (HTTP 422): a syntax error at the position given, two statements, or a write (`INSERT`, `DELETE`, `CREATE`, `ATTACH`, `COPY`, `PRAGMA`, `SET` ...): the dialect is one read-only `SELECT` ([SQL.md](SQL.md)) |
| `DRS-9171` | a table that is not one of the model's aliases, or a column it lacks for you (a hidden column is not found, on purpose); a bare column in two tables (say which); a column of a table the statement does not read (`JOIN` it) |
| `DRS-9172` | a join whose `ON` is not a relationship of the model: write `JOIN t` without `ON` (the model's relationship is used), or ask the model owner for one |
| `DRS-9173` | a function Drishti SQL does not take (`random()`, a table function, a user function); the message lists the ones it does |
| `DRS-9174` | `SUM(MEASURE('x'))` (a measure is already an aggregate) or a measure in `WHERE` (use `HAVING`) |
| `DRS-9175` | a window over a dataset's rows: windows run over the answer; select what the window orders by and name it |
| `DRS-9176` | the statement names something outside its model: another model, a connector, a kind, a file or a URL |
| `DRS-9177` | two different times in one statement (comparing two dates is DEL `as_of`, wave B), or a time clause that is not valid |
| `DRS-9178` | `SELECT DISTINCT` without `GROUP BY`: write the `GROUP BY` (the grain changes on purpose, not silently) |
| `DRS-9179` | a statement the dialect does not take yet; the message says what to write instead (`SELECT *`: name the columns; an `OR` across fields: a derived column; re-aggregating answers: a measure) |
| `DRS-9180` | no standing query of that id is yours (HTTP 404): it was ended, or dropped after `drishti.bi.mq.idle-close` without a stream reading it |
| `DRS-9181` | the server holds `drishti.bi.mq.max-subscriptions` standing queries (HTTP 429) |
| `DRS-9182` | you hold `drishti.bi.mq.max-subscriptions-per-user` standing queries (HTTP 429): end one (`DELETE /api/v1/bi/mq/subscribe/{id}`) |
| `DRS-9183` | a subscription to an as-of date in the past (HTTP 422): it never changes, so ask it once |
| `DRS-9184` | with several Java nodes, the node that owns the model's standing queries could not be reached (HTTP 503): retry; an operator checks `drishti.bi.exec.nodes` and the owner's health |
| `DRS-9404` | raised by the console only (HTTP 404): an embedding page asked for a Drishti Elements module or enhancer script the console does not ship (`/embed/v1/m/<name>.js`, or `/embed/v1/js/<name>.js` for a script that is not one of the console's enhancers); check the file name and the library version in the `<script>` tag |
| `DRS-7016` | the picture could not be made (HTTP 503): it took longer than `snapshots.timeout`, is over `snapshots.max-bytes`, or the JDK has no font libraries (a minimal image: install the JDK's fontconfig and freetype packages); the message says which. Send without a picture, or raise the limit |


## A subscription, an export (Rūpaka distribution)

| You see | Why, and what to do |
|---|---|
| The history says `no SMTP host: set spring.mail.host` | the server has no mail host. Set `spring.mail.host` (and `drishti.collab.email.from`); the same settings as share mail. It is not retried, so press *Run now* after fixing it |
| A delivery says `retry`, with an attempt count | the mail server did not take it; the shared retry policy tries again (`drishti.retry.distribution.*`) up to `drishti.distribution.subscriptions.max-attempts`, then it says `failed`. A refused address is `failed` at once |
| A recipient is `skipped: no email address on file` | the user has no email in Admin, Users |
| A recipient is `skipped` with a refusal | they may not open the report or kind now (their role changed). It is by design: they are not mailed a refusal |
| An `after-load` subscription never runs | it waits for a load that **finished ready** (Admin, Data loads). With `after-load TRD` only a `TRD` load counts. The next run column says "after a data load" |
| A monthly subscription ran a day late | a holiday: with `business` after the schedule a holiday moves to the next business day (`monthly last 18:00 business` is the last business day) |
| "MTM by desk: Not available: DRS-9021 'MTM' is masked for you" in a file | that visual sorts on a column your role may not see; every other visual is still there |
| The PDF has fewer rows than the screen | PDF and PNG draw `pdf-max-rows` (500) rows a table and say so under the table; Excel holds up to `max-rows` (50,000) |
| `a view is named kind/id` | export a view as `kind/id`, for example `trade/IRS-48213` |

## Describe a report, Ask the data or Explain does nothing or is refused

* **No box, no light bulb, no "Describe a report" button:** the AI layer is off (`drishti.ai.enabled`, no provider in `config/ai/`), or you lack the power (`bi-author` to describe a report). `GET /api/v1/bi/ai/status` says what the server allows you.
* **`DRS-4018`:** the AI policy does not list your role for `bi-draft`, `bi-ask` or `bi-explain` (`drishti.ai.policy.features`). **`DRS-4017`:** over your limit.
* **"the query the model chose was refused":** the engine would not run what the model wrote (an unknown field, a masked one, you are already running the maximum number of queries): read the query shown, rephrase, or build the visual by hand.
* **Ask answers fewer rows than a colleague gets:** correct: it runs as you, with your row rules and masks. **Explain lists fewer dimensions:** a dimension masked for you is left out.
* **Explain has no wording:** the narrative is only written with `drishti.bi.ai.values: aggregates`; the lineage and the breakdown are always there.
* **A subscription's email has no written summary:** open the run's history: the delivery says `summary: skipped: …`. `the AI policy lets the model see labels only` is `drishti.bi.ai.values: labels-only` (set `aggregates` to allow it); `switched off` is `drishti.ai.enabled`; `DRS-4018` is the AI policy not opening `subscription-narrative` to that recipient's role (`drishti.ai.policy.features`); `DRS-4017` is their rate or cost limit. The mail is always sent; the summary never holds one.
* **A subscription cannot be saved with a summary, a webhook or texts:** `a narrative summary needs the AI layer` (turn `drishti.ai.enabled` on), `no webhook named …` (an administrator defines `drishti.notify.webhooks.<name>`), `text messages are not set up` (`drishti.notify.sms.url`).
* **No "Suggest visuals" or "Ask the AI helper" button:** the AI layer is off, or you do not have the `bi-author` power (`GET /bi/api/ai/status` says `assist: false`). **`DRS-4018`** after pressing it: the policy does not open `bi-suggest`, `bi-explain-checks` or `bi-helper` to your role. **`DRS-4020`**: the model's suggestion did not pass the report check twice, so nothing is shown; describe the goal in other words or use *Describe a report*.
* **The dataset helper says "Place it by hand":** the column's line in the YAML is written in a form it does not recognise; copy the YAML shown under the item into the dataset.

## Totals that were too big, history, imports and board tiles (BI wave B)

| Symptom | Cause | Check and fix |
|---|---|---|
| A figure from a "one" side table (netting-set exposure) was far bigger than the sum of its rows when asked beside a trade measure | before this fix each netting set was added once per trade that reached it | measures of different tables are now each worked out at their own table's grain and joined on the grouping ([DATASETS.md section 3](DATASETS.md#3-relationships-and-how-queries-join), *Grain*); the same query now gives the true figure. If a query is refused (`DRS-9020`, "each must be computed at its own table's grain"), a field, filter or row rule of the query is not reachable many-to-one from one measure's table: ask for the two measures in separate queries |
| A line over `history.ts` is empty, or shows one point | the live table has not been open long enough (one sample every `every`, 10 s by default), or the table was idle and closed (its history goes with it), or the sample fails | the dataset's Layers table (`GET /datasets/{id}/layers`) shows the history's `samples`, `lastSample` and `error`; keep a report open on the dataset |
| A history query is refused (`DRS-9020`, no relationship path) for a desk-head | the role has a row rule on the live table, which the history cannot honour | by design (nobody else's figures leak); give the role the rule `true` for that dataset, or chart it from the live table itself |
| A publish is refused (`DRS-9040`) although the change is only a rename | the new name was not recognised as the same thing: a column with no `path:` or `expr:` has only its name | give the column a `path:` (or keep the old name), or publish with force (bi-admin); a recognised rename shows `[renamed to ...]` and *Publish and update the reports* is offered |
| *Publish and update the reports* is not offered | a published item that loses a name is a shipped report, a composed or derived dataset, or loses another name too | the impact lists them; change those by hand, then publish |
| *Roll back this import* answers `DRS-9004` ("edited or published since") | an item the import wrote was changed afterwards | nothing was changed; export what you want to keep, then a bi-admin rolls back with force, or leave the import in place |
| No import appears under *Imports you can roll back* | the list is the newest `imports-keep` (20), or the files in `imports-dir` are gone | `drishti.bi.governance.imports-dir`; back it up with the identity database |
| A deployed pack's BI items did not appear | the import was held back (the deploy answer has `bi.loaded: false` and the reason; the audit log has `pack-bi-held`), or the files are not named `<name>.<kind>.yaml` under `config/bi/<kinds>/` of the pack | fix the file or confirm the deploy with `acceptBreaking` (bi-admin); the items are in the workspace `packs` |
| Lineage shows the connector but dots for its table or path | lineage past the connector is masked for people who are not administrators | by design |
| The report designer says a board tile "names a report or visual you cannot open" | the report is not one you may read, was renamed, or lost the visual | pick the tile's report and visual again (Add a tile), or fix the YAML |
| A tile with a hysteresis stays red after the number is normal | it drops only when the number is back inside the threshold by the hysteresis | lower `hysteresis`, or wait for the number to move further |
| A temp folder `drishti-<pid>` or `drishti-console-<pid>` is left in the temp base (`<data dir>/tmp` for the server) | the process was killed (`kill -9`, a power cut) | it is kept (never deleted automatically) and listed on Admin, Health: delete it by hand when no such process runs ([CONFIGURATION.md](../admin/CONFIGURATION.md#temporary-folders-drishtitmp-dir-and-the-consoles-tmp_dir)) |
| The temp base (`<data dir>/tmp`, or `/tmp` with an older server) fills up while DuckDB works on a big query | its spill files are in the server's temp folder | point `DRISHTI_TMP_DIR` at a disk with room and restart; Admin, Health shows the folder's size |

## BI Studio, boards as report pages, data prep and the Python console (BI wave C, 2.4.0)

| Symptom | Cause | Check and fix |
|---|---|---|
| A report that opened in 2.3 now shows warnings under **Problems** | a 2.3 `format:` key with a value the 2.4.0 schema does not take, or a key no schema knows: a warning, so a stored report never stops opening | fix the value in the Format pane (it offers only what the server accepts), or remove the key; a bad value of a key **new** in 2.4.0 is `DRS-9032` ([REPORTS.md section 5](REPORTS.md#5-format)) |
| A theme cannot be saved or published (`DRS-9036`) | its palette is not 3 to 12 `#rrggbb` colours, a font family is not `sans`, `serif`, `mono` or `system`, or a visual default is not a format key of that family | the theme designer names the path; `POST /api/v1/bi/themes/validate` checks a file |
| A 2.3 board is missing after the upgrade, or `/bi/b/<id>` opens the report viewer | stored boards became reports with board pages at the first start (`drishti.bi.migrate.on-start`, step `boards`; audited `bi-boards-migrated`), keeping their ids | Admin, BI settings lists what could not be converted; a board file still in a folder is read as a report until you run `drishti.py bi migrate --boards FILE... --write` ([BOARDS.md section 12](BOARDS.md#12-from-a-23-board-to-a-board-page)) |
| A Transform's input is refused on validate (`DRS-9102` or `DRS-9108`) | a kind no pack declares, a field the kind lacks (the message lists its columns), or a misspelt key | pick the kind and fields from the editor's lists; a connector named on the input that is not running is `DRS-9026` ([PYTHON_BI.md section 17.1](PYTHON_BI.md#171-inputs-straight-from-a-connector)) |
| A Transform's input has no rows, or a column reads `•••` | the input is read **as you**: a role that may not read the kind gets no rows, and a masked column arrives masked | by design; preview as a role that may read it, or ask for the entitlement |
| The Transform editor's step preview stops at a step | that step raised: the editor shows the step's line and the error; a run past `bi.transform.timeout_seconds` (20) or `memory_mb` (512) is stopped | fix the step (the snippet's example shows its expected input), or lower `bi.transform.preview_rows` |
| **BI → Python console** says the Python runtime is not installed | the console uses Calc's Pyodide worker, which is not in the repository | run `tools/fetch-pyodide.sh` once on the console host ([PYTHON_CALC.md section 12](PYTHON_CALC.md#12-installing-the-python-runtime)) |
| The Python console's extract is cut short | it holds at most `bi.pyconsole.max_rows` (20,000) rows | filter the extract, or raise the setting (up to 100,000) |
| *Promote to the server* is not offered, or the result is not kept | promoting needs **bi-author**; code that fails on the console backend is not kept | ask for the power; run the same code in Analyse to see the backend's error ([ANALYSE.md section 16](ANALYSE.md#16-the-browser-python-console)) |
| A form search says no data is loaded for the date | no source holds the kind on that business date (`noData`) | press **Search on** the newest date it names, or load the day ([QUERY_BY_FORM.md](QUERY_BY_FORM.md)) |

## BI migrations (2.2 wave A)

From 2.2 the old expression syntax is refused (`DRS-9100`), so the server converts the items it stores at start (`drishti.bi.migrate.on-start`,
[CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibimigrate--migrations-of-stored-bi-items)). Each change is audited and the text before is a saved
state of the item; an item it cannot convert is never changed.

| Symptom | Cause | Check and fix |
|---|---|---|
| An expression says `DRS-9100` ("old expression syntax ... refused") in Check or when a report runs | the text is in the 2.1 syntax: a shipped or site file in `drishti.bi.dir`, an item imported after the start, a saved state restored from before the migration, or an item the migration could not convert | the problem's fix is the DEL v2 text; for files run `drishti.py bi migrate --expressions DIR --write`; for stored items `drishti.py bi migrate --expressions` (bi-admin) converts them now. `drishti.bi.expr.legacy: warn` reads the old syntax again (a warning) while a site converts |
| Admin, BI settings lists **migration problems** (or `GET /api/v1/bi/migrate/problems`, or the item's page) | an expression of that item could not be converted (it does not parse in either syntax, say a missing `)`), so the item kept its text and was not changed at all | open the item, fix the expression the problem names (`where`, e.g. `measures.Per.expr`), save; then `drishti.py bi migrate --expressions` (or the next start) converts the rest and clears the entry |
| An item has new saved states by `system` with origin `migrate`, and a new published version | the start-up migration converted it | nothing to do; *History* shows the text before ("the text before the migration" or the person's last saved state) and *Restore* brings it back (it is then refused again until converted); the audit log has `bi-expressions-migrated` with the item's id |
| The start log says "BI expression migration at start-up failed" | an unexpected error stopped the run (the server starts anyway; items converted before it are kept, the rest are unchanged) | the log line has the cause; run `drishti.py bi migrate --expressions --dry-run` to see what is left, then without `--dry-run` |
| A dataset of mine is now a **model**, and new datasets `<name>-<table>` appeared in its workspace (events `migrate` by `system`) | the start-up model migration converted the `rachana: 1` dataset ([BI_MODEL_SPEC.md 8.3.1](../architecture/BI_MODEL_SPEC.md#831-as-built-22-wave-a-task-6)); its reports now say `model: <name>@<major>` | nothing to do; the item's *History* holds its 2.1 text; the audit log has `bi-model-migrated` and `bi-item-created`. Its row rules moved to the new datasets |
| After the model migration a desk-head sees **no rows** of netting sets (or another table) in a model | `CLOSED`: in 2.1 a role restricted on one table had its questions about the other tables refused; the converter keeps that by giving it `where: "False"` on the other datasets (the migration report's manual steps list each) | to let the role see every row there, change that dataset's rule to `True` and add `security.may-see-all: { <alias>: [<role>] }` to the model (BI_MODEL_SPEC.md 2.6), then publish |
| Migration problems, step `models`: "its draft is ahead of its published version" | the dataset has unpublished changes, so there are two 2.1 texts to convert | publish or discard the draft, then `drishti.py bi migrate --models` (or restart) |
| Migration problems, step `models`: `DRS-9190` (a rule names two tables, a table that groups or de-duplicates, a column that reads another table, a converted item that fails Check) or "a dataset named ... exists already" | the converter cannot keep the meaning, or the name is taken | the dataset is unchanged; fix what the problem names (split the rule; rename the other dataset), then run it again; or write the model by hand ([BI_MODEL_SPEC.md section 2](../architecture/BI_MODEL_SPEC.md#2-the-data-model)) |
| Migration problems: `DRS-9191` for a composed dataset | its sources are in different models and none of them reads the others' tables | put their datasets in one model with relationships to shared dimensions, or write the join as a Python dataset (`source.python` with model-query inputs) |
| A live report still says `dataset:` after the migration | by design: a report on a model follows live data from wave W3 (standing queries) | nothing to do; the migration report lists it as a manual step |
| A Python Transform fails after the migration with a missing column (`counterparty`, `Desk P&L`) | a Python dataset hands it model-query answers, each column named by the last part of its field or measure name (`counterparties.id` is `id`, `P&L 1d`), not the 2.1 `as` names | rename them at the top of the function (the shipped `pnl-vs-exposure` shows how) |
| `POST /api/v1/bi/datasets/{id}/query` answers `410` ("dataset query removed") | the 2.1 dataset query was removed in 2.2 | send the answer's `mq` to `POST /api/v1/bi/mq` (or `drishti.py bi mq FILE`); an Analyse extract names `mq:<model>@<major>` |
| A dataset with `from:` or `derived:` says `DRS-9190` ("composed datasets were removed" / "derived datasets were removed") | composed and derived datasets were removed in 2.2 | `drishti.py bi migrate --models` (or the next start, for a stored one) folds a composed one into its sources' model and makes a derived one a Python dataset |
| A query names a 2.1 dataset and answers `DRS-9151` "is a rachana: 1 dataset, no longer read as a model" | the compatibility reader was removed in 2.2 | convert it: `drishti.py bi migrate --models`; its model keeps its name |
| A site wants to convert stored items itself, later | — | set `drishti.bi.migrate.on-start: false` (`DRISHTI_BI_MIGRATE_ON_START=false`), keep `drishti.bi.expr.legacy: warn` meanwhile, and run `drishti.py bi migrate --expressions` when ready |
