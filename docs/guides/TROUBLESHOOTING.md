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
| [The console and the server](#the-console-and-the-server) | backend unreachable, `/readyz` says 503, the live dot is amber, typing does nothing |
| [Commands and views](#commands-and-views) | cannot read command, *Nothing matches*, DRS-1001, may not open, no suggestions, No data available, pending links, blank charts |
| [Pick lists and tables](#pick-lists-and-tables) | a pick list instead of the entity, only ids in the list, only 25 rows, keys do nothing |
| [Live and dates](#live-updates-and-business-dates) | view does not tick, no data held for a date, DRS-4003, known at has no effect, DRS-1007, DRS-1001 naming a store that holds the date |
| [Search](#search) | DRS-4004, empty results, partial results |
| [Studio and Sutras](#studio-and-sutras) | Save disabled, an edit has no effect, `.sutra.md` files after an upgrade, approval refused |
| [Packs](#packs) | mnemonics missing, a pack switched off, *not loaded*, cannot switch off, generated files out of date |
| [Connectors](#connectors) | a connector is idle, a connector failed to start, TLS errors and certificate expiry, schema-registry messages skipped |
| [Monitoring endpoints](#monitoring-endpoints) | `/actuator/prometheus` answers 401 or 403, `/api/docs` answers 401 |
| [Alerts](#alerts-and-monitors) | an alert never fires |
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
Docker Compose, use `docker compose -f deploy/compose.yaml logs -f server` (or `console`).

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
from the connector's settings. Admin → Health then shows `UP (engine: native)`. See [WINDOWS.md](WINDOWS.md).

### "Web server failed to start. Port 18480 was already in use."

- **Check:** who holds the port: `ss -ltnp | grep 18480` (or `lsof -i :18480`). Often it is an earlier Drishti
  server still running.
- **Fix:** stop the other process, or start this one on another port and tell the console where it is:

  ```bash
  DRISHTI_PORT=18481 java -jar drishti-server/target/drishti-server-1.18.0-exec.jar
  DRISHTI_BACKEND_URL=http://127.0.0.1:18481 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
  ```

  The console's own port works the same way: `[Errno 98] address already in use` → `DRISHTI_CONSOLE_PORT=17481`.

### The server stops at start-up with "pack '…' not found at …/pack.yaml"

- **Cause:** a name in `DRISHTI_PACKS` has a typo, or the server cannot find the `packs/` folder. The default
  folder is `./packs`, relative to the directory you start the server from.
- **Check:** the message names the exact path it tried, for example
  `pack 'trading-risk' not found at /home/me/drishti/packs/trading-risk/pack.yaml`. Compare it with
  `ls packs/`.
- **Fix:** use the folder names exactly (comma-separated, no spaces needed), and either start the server from
  the repository root or give an absolute folder:

  ```bash
  DRISHTI_PACKS=trading,counterparty-risk DRISHTI_PACKS_DIR=$PWD/packs \
    java -jar drishti-server/target/drishti-server-1.18.0-exec.jar
  ```

  A pack's parents load by themselves: enabling `trading` also loads `banking-core` and `market-data`. Other
  start-up messages from the pack loader: "`… declares pack 'x', expected 'y'`" (the folder name and the `pack:`
  key in its `pack.yaml` differ), "`packs require each other in a cycle`", and "`… is defined by both pack 'a'
  and pack 'b'`" (two unrelated packs claim the same mnemonic or kind). See [PACKS.md](PACKS.md).

### Only the finance pack loads (or a pack you expected is missing)

- **Cause:** `DRISHTI_PACKS` was not set for the server process; its default is `finance`.
- **Check:** `curl -s http://localhost:18480/api/v1/packs | python3 -c "import json,sys; print([p['name'] for p in json.load(sys.stdin)])"`.
- **Fix:** restart the server with the list you want, for example
  `DRISHTI_PACKS=banking-core,market-data,trading,market-risk,counterparty-risk`.

### An old version runs, or the wrong jar starts

- **Cause:** `drishti-server/target/` holds jars from several builds, and `java -jar drishti-server/target/drishti-server-*-exec.jar`
  expands to all of them; Java runs the **first** (the oldest, alphabetically) and ignores the rest.
- **Check:** `curl -s http://localhost:18480/api/v1/about | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])"`
  and `ls drishti-server/target/*-exec.jar`.
- **Fix:** name the jar exactly (`drishti-server-1.18.0-exec.jar`), or delete the old ones.

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

## Signing in

### There is no sign-in page, and no users or roles apply

- **Cause:** this is the local-development default: security is off on the server
  (`DRISHTI_SECURITY_ENABLED=false`) and sign-in is off in the console (`DRISHTI_AUTH_ENABLED=false`).
  `curl -s http://localhost:18480/api/v1/about` shows `"securityEnabled": false`.
- **Fix:** to sign in with users and roles, turn both on and share one secret:

  ```bash
  export DRISHTI_TOKEN_SECRET='at-least-32-characters-shared-secret!!'   # same value for both
  DRISHTI_SECURITY_ENABLED=true java -jar drishti-server/target/drishti-server-1.18.0-exec.jar
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

- **Cause:** the console cannot reach the server.
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

### The live dot is amber ("Reconnecting…")

- **What you see:** the dot among the round tools of the top bar turns amber, and values stop changing.
  Hover over it, or use a screen reader, to read *Reconnecting…*.
- **Cause:** the live stream dropped (server restarted, network blip), or could not be opened within 10 seconds.
  The page itself keeps working: it never waits for live updates.
- **Fix:** nothing to do: the browser reconnects by itself and repaints from a fresh view. If it never
  recovers, run the first checks. If only an older tab stays amber after you signed in again (or picked another
  server) in another tab, that tab belongs to the previous session: reload it.

### The live dot is amber after the tab was in the background ("Paused while hidden")

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
- **Fix:** add or correct the entry: [About text and glossary](PACK_DEVELOPER_GUIDE.md#about-text-and-glossary).

### The Ask box is missing, or says "Ask is unavailable"

- **Cause (missing):** Ask is off by default. It appears only when `drishti.explain.ask.enabled=true` **and** the page's pack is in `drishti.explain.ask.packs`.
- **Cause (unavailable, `DRS-4008`):** the model endpoint is down, slow (`timeout`), refused the key, or `endpoint`/`api`/`model` do not match what it speaks.
- **Check:** `GET /api/v1/views/{kind}/{id}/explain` shows `ask.enabled`; the server log line `ask failed for …` says why; the access log shows each question with its outcome.
- **Fix:** correct the settings in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiexplainask--ask-about-this-page-optional-off-by-default). The rest of the drawer never depends on Ask.

### A linked entity shows `pending` or `missing` instead of a badge

- **Cause:** `pending`: the linked entity did not arrive within the 40 ms link budget (`drishti.graph.link-budget`).
  `missing`: no source has it.
- **Check:** `curl -s http://localhost:18480/api/v1/sources` for a source whose `health` is not `UP`.
- **Fix:** a slow source: see [runbooks/source-down.md](../admin/runbooks/source-down.md). Open the link itself to see the
  real error.

### Charts are blank

- **Cause:** the chart library did not load (a proxy, a strict browser extension, or a broken path).
- **Check:** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:17480/static/vendor/echarts/echarts.min.js`
  should print `200`. The browser's developer console (F12) shows any blocked script.
- **Fix:** allow the console's own `/static/` path through the proxy or extension; hard-refresh.

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
  uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/lake.py \
      --samples packs/finance/samples --root data/delta --domain finance --days 30
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
  file, line, column and code (`DRS-2001` YAML syntax, `DRS-2009` no `rachana: 1`, `DRS-2010`–`DRS-2031` grammar, `DRS-2032` the file
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

- **Cause:** its folder is in `packs/`, but `DRISHTI_PACKS` did not name it (nor any pack that extends it), so
  the server did not load it.
- **Fix:** press **Load** on its row: the server checks it, records it in `data/packs/added.yaml` and restarts in
  place. If it says *cannot load 'finance': kind trade is defined by both pack 'trading' and pack 'finance'*, the
  pack clashes with one already loaded (`finance` and the banking packs define the same kinds), and nothing
  changed ([PACKS.md](PACKS.md#the-packs-that-ship)).

### "Switch off" is greyed, or the API says "… is needed by …; switch those off first"

- **Cause:** another switched-on pack builds on this one (`DRS-5001 'trading' is needed by [market-risk,
  counterparty-risk, liquidity-risk, climate-risk]; switch those off first`).
- **Fix:** switch off the packs named first, then this one. Switching any of them on later switches this one on
  again by itself.

### The drill fails with "pack manifests out of date" or "… pack out of date; run …"

- **Cause:** a generated file (`pack.yaml`, a Sutra, a sample or a guide of a generated pack) was edited by
  hand, or the generator was changed and not run.
- **Check:** run the check that failed, for example `python3 tools/packgen/banking/make_packs.py --check`; the
  message lists the stale files, and `extra=[…]` lists files the generator did not write.
- **Fix:** move the change into the generator, run it without `--check`, and commit both
  ([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#how-the-shipped-packs-are-generated)). To discard a hand edit instead:
  `git checkout -- packs/<name>/…`.

## Connectors

### A connector is listed as idle, not as failed

- **What you see:** the server log says
  `source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle`, and the
  connector is missing from `/api/v1/sources` but not under `failedToStart` in Admin → Health.
- **Cause:** a plugin is on the class path and enabled, but has no settings (Kafka without `topics`, a feed
  without `feed`). Such a plugin stays idle on purpose (`PluginNotConfigured`): it is not an error, and the
  overall status stays **OK**.
- **Fix:** nothing, if you do not use it. To use it, give it its settings under
  `drishti.sources.connectors.<name>.settings` (or the plugin's own `drishti.sources.plugins.<name>.settings`),
  and restart. See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

### A connector failed to start

- **What you see:** Admin → Health says **DEGRADED**, and `failedToStart` names the connector with a reason.
- **Fix:** follow [runbooks/source-down.md](../admin/runbooks/source-down.md), step 2.

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
  [RabbitMQ](../connectors/RABBITMQ_CONNECTOR.md#125-common-errors-and-fixes).

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

## Error codes

| Code | Meaning |
|---|---|
| `DRS-1001` | no source holds that entity |
| `DRS-1002` | no source serves that kind |
| `DRS-1005` / `DRS-1006` / `DRS-1007` | pasted document is not a JSON object / a connector plugin could not be loaded (`/sources` lists why) / `knownAt` asked of a store with no earlier versions |
| `DRS-1003` / `DRS-1004` | a source failed / timed out (a console page answers 504 for a timeout, 502 for a failed source) |
| `DRS-2001` / `DRS-2002` / `DRS-2003` | Sutra parse error / invalid / not found |
| `DRS-2005` / `DRS-2006` / `DRS-2007` | proposal not found / stale / four eyes |
| `DRS-2004`, `DRS-2009`–`DRS-2031` | a Sutra file failed a check: each code is explained in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#problem-codes) |
| `DRS-2032` | a Sutra file could not be read at all (the log has the stack trace); the rest load |
| `DRS-2033` | a Sutra's YAML has a key written twice, a second document or a tag |
| `DRS-2040`–`DRS-2044` | a pack's `config/about.yaml` has a problem (unknown key or bad version, kind not in the pack's lineage, a template that does not compile, `use` with no vocabulary entry, a text over the cap); listed by `GET /api/v1/sutras/problems` as `<pack>/about.yaml`; the entry is left out and the page is unaffected; see [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#problem-codes) |
| `DRS-2045`–`DRS-2047` | `sutra lint` help warnings (not load errors): a Sutra binds `F1`, an about `panels.<id>` matches no panel, a shown field has no glossary entry; `--strict` fails the run on them |
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
| `DRS-6001`–`DRS-6010` | user management (see [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md)); `DRS-6005` is a locked account |
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
| `verify` reports a thread whose chain `was changed` or `does not follow` | a revision row was edited or removed outside the application; compare with the archived `chains.ndjson` of an earlier export, and restore from backup. The report names the revision |
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
| `DRS-8005` | the host application may not show this kind (HTTP 400): its `kinds` list leaves it out. Add the kind to the application, or leave the list empty |
| `DRS-8006` | the element's contract version is no longer served (HTTP 410, raised by the console): upgrade the element (the request's `Drishti-Embed-Api` names a major version other than 1) |
| `DRS-7016` | the picture could not be made (HTTP 503): it took longer than `snapshots.timeout`, is over `snapshots.max-bytes`, or the JDK has no font libraries (a minimal image: install the JDK's fontconfig and freetype packages); the message says which. Send without a picture, or raise the limit |
