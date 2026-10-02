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
| [Live and dates](#live-updates-and-business-dates) | view does not tick, not a dated source, DRS-4003, known at has no effect |
| [Search](#search) | DRS-4004, empty results, partial results |
| [Studio and Sutras](#studio-and-sutras) | Save disabled, an edit has no effect, `.sutra.md` files after an upgrade, approval refused |
| [Packs](#packs) | mnemonics missing, a pack switched off, *not loaded*, cannot switch off, generated files out of date |
| [Connectors](#connectors) | a connector is idle, a connector failed to start |
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

### The build fails with "Drishti builds and runs on OpenJDK 25"

- **Check:** `./mvnw -v` prints the Java version Maven uses: it must be 25, and a JDK, not only a JRE (the build
  needs `javac`; on Ubuntu `sudo apt install openjdk-25-jdk-headless`).
- **Fix:** point Maven at JDK 25 and build again:

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
  ./mvnw -q verify
  ```

### The server stops at once with `UnsupportedClassVersionError … class file version 69.0`

- **Cause:** `java` on your `PATH` is older than 25.
- **Check:** `java -version`.
- **Fix:** run the jar with Java 25: `/usr/lib/jvm/java-25-openjdk-amd64/bin/java -XX:+UseCompactObjectHeaders -jar …`,
  or put that JDK first on your `PATH`.

### On Windows: `HADOOP_HOME and hadoop.home.dir are unset` or `Could not locate executable winutils.exe`

A Delta connector is running on Hadoop's engine, which needs Hadoop's `winutils.exe` on Windows. Use the native
engine: unset `DRISHTI_DELTA_ENGINE` (it defaults to `native`) or set it to `native`, and remove any `engine: hadoop`
from the connector's settings. Admin → Health then shows `UP (engine: native)`. See [WINDOWS.md](WINDOWS.md).

### "Web server failed to start. Port 18480 was already in use."

- **Check:** who holds the port: `ss -ltnp | grep 18480` (or `lsof -i :18480`). Often it is an earlier Drishti
  server still running.
- **Fix:** stop the other process, or start this one on another port and tell the console where it is:

  ```bash
  DRISHTI_PORT=18481 java -jar drishti-server/target/drishti-server-1.13.0-exec.jar
  DRISHTI_BACKEND_URL=http://127.0.0.1:18481 console/.venv/bin/python console/run_drishti_web.py
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
    java -jar drishti-server/target/drishti-server-1.13.0-exec.jar
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
- **Fix:** name the jar exactly (`drishti-server-1.13.0-exec.jar`), or delete the old ones.

### The console will not start: `ModuleNotFoundError: No module named 'fastapi'`

- **Cause:** it was started with the system Python, not the console's virtual environment.
- **Fix:**

  ```bash
  uv venv console/.venv && uv pip install --python console/.venv/bin/python -r console/requirements.txt
  console/.venv/bin/python console/run_drishti_web.py
  ```

### The console starts but cannot be opened from another machine

- **Cause:** it listens on `127.0.0.1` only, by default.
- **Fix:** `DRISHTI_CONSOLE_HOST=0.0.0.0 console/.venv/bin/python console/run_drishti_web.py`, and, for anything
  beyond a trial, turn security on first (see [OPERATIONS.md](../admin/OPERATIONS.md)).

## Signing in

### There is no sign-in page, and no users or roles apply

- **Cause:** this is the local-development default: security is off on the server
  (`DRISHTI_SECURITY_ENABLED=false`) and sign-in is off in the console (`DRISHTI_AUTH_ENABLED=false`).
  `curl -s http://localhost:18480/api/v1/about` shows `"securityEnabled": false`.
- **Fix:** to sign in with users and roles, turn both on and share one secret:

  ```bash
  export DRISHTI_TOKEN_SECRET='at-least-32-characters-shared-secret!!'   # same value for both
  DRISHTI_SECURITY_ENABLED=true java -jar drishti-server/target/drishti-server-1.13.0-exec.jar
  DRISHTI_AUTH_ENABLED=true DRISHTI_SESSION_SECRET='another-secret-of-32-characters-or-more' \
    console/.venv/bin/python console/run_drishti_web.py
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
- **Cause:** the live stream dropped (server restarted, network blip).
- **Fix:** nothing to do: the browser reconnects by itself and repaints from a fresh view. If it never
  recovers, run the first checks.

### The live dot is amber after the tab was in the background ("Paused while hidden")

- **Cause:** the tab was hidden for 10 seconds and gave its connection back, to save resources.
- **Fix:** show the tab: it reconnects and repaints.

### The page looks fine but typing does nothing (no suggestions), often with several tabs open

- **Cause:** before 1.10, each view and the alerts bell held its own connection, and browsers allow only six per
  site. Each tab now uses one live connection (see [LIVE.md](../architecture/LIVE.md)).
- **Fix:** hard-refresh old tabs once (**Ctrl+Shift+R**) so they load the new scripts.

## Commands and views

### "DRS-4001 cannot read command '…'; try <MNEMONIC> <ID> <GO>"

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
  ([PLUGIN_GUIDE.md](../connectors/PLUGIN_GUIDE.md)). `/api/v1/sources` shows `"search": true` for sources that can suggest.

### A panel says "No data available"

- **Cause:** the document lacks what that panel needs, or has it in another shape. The rest of the view renders
  on purpose. Real feeds are often incomplete.
- **Check:** if the panel also says "The data did not have the shape this panel expects", hover over the message
  to read the exact error. Press **F9** to see the raw JSON and compare it with the panel's `rows:` path in the
  Sutra (Studio shows it).
- **Fix:** if the data is right and the Sutra is wrong, fix the Sutra in Studio; see
  [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md).

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
- **Fix:** add `columns:` for the kind to its pack ([PACKS.md](PACKS.md#columns-the-key-fields-of-a-pick-list)),
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

### "… is not a dated source: this shows its current data, not 2026-08-03"

- **Cause:** for the date you picked, the entity came from a source that keeps no history (for example the
  `demo` source), so you see its current data.
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

### "Known at" changes nothing

- **Cause:** only sources that keep versions (Delta Lake) honour it; others have one version. Delta resolves times
  from the `_delta_log` files' modification times, so a lake copied without preserving times loses its history.
- **Fix:** copy lakes with `cp -p` or `rsync -t`. See [PLUGIN_GUIDE.md](../connectors/PLUGIN_GUIDE.md).

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

### A search returns nothing

- **Check:** the field name: press **F9** on one entity of that kind and copy the path exactly
  (`counterparty.name`, `legs[0].rate`). A field that does not exist is simply never true. A masked field
  never matches. (An unknown mnemonic is an error, not an empty result:
  `DRS-4004 'XYZ' is neither a mnemonic nor a kind; type it alone to see suggestions`.)
- **Fix:** correct the path. Text matching with `=`, `!=`, `contains` and `startswith` ignores case, so
  `currency = usd` finds `USD`.

### "… results may be incomplete"

- **Cause:** the scan stopped short: more than 20,000 entities of that kind (`drishti.search.max-scan`), or a
  source slower than 3 seconds (`drishti.search.budget`). The API answer has `"partial": true`.
- **Fix:** narrow the search, or raise the limits ([CONFIGURATION.md](../admin/CONFIGURATION.md)).

## Studio and Sutras

### Studio's Save (or Submit for review) is disabled

- **Cause:** "Saving is off here, or you are not an author".
- **Check:** `curl -s http://localhost:18480/api/v1/studio/settings` should show `"save": true`.
- **Fix:** start the server with `DRISHTI_STUDIO_SAVE=true`, and (with security on) give yourself the `author`,
  `approver` or `admin` role.

### A Sutra edit has no effect

- **Check:** `curl -s http://localhost:18480/api/v1/sutras/problems` should print `{}`. Anything else lists the
  file, line, column and code (`DRS-2001` YAML syntax, `DRS-2009` no `rachana: 1`, `DRS-2010`–`DRS-2028` grammar, `DRS-2032` the file
  could not be read at all, `DRS-2101` expression syntax or an expression past the size limits).
- **Check:** as an admin, `GET /api/v1/admin/health` should show `"sutras": {"hotReload": "WATCHING", …}`. `STOPPED: <reason>`
  means the file watcher has ended (the log says why): restart the server to pick up edits again.
- **Cause and fix:** an invalid edit keeps the **last good version** live. Fix the reported line (Studio's
  Ctrl+Enter lists problems by line). With review on (the default), a saved Sutra is only a **proposal** until an
  approver approves it in **Studio → Reviews**. *How this view was built* shows the version actually used. See
  [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md).

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
- **Fix:** ask another approver; for a stale proposal, reject it, open the live Sutra in Studio, re-apply the change
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
  ([PACKS.md](PACKS.md#how-the-shipped-packs-are-generated)). To discard a hand edit instead:
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
  and restart. See [PLUGIN_GUIDE.md](../connectors/PLUGIN_GUIDE.md).

### A connector failed to start

- **What you see:** Admin → Health says **DEGRADED**, and `failedToStart` names the connector with a reason.
- **Fix:** follow [runbooks/source-down.md](../admin/runbooks/source-down.md), step 2.

## Monitoring endpoints

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
| `DRS-1003` / `DRS-1004` | a source failed / timed out |
| `DRS-2001` / `DRS-2002` / `DRS-2003` | Sutra parse error / invalid / not found |
| `DRS-2005` / `DRS-2006` / `DRS-2007` | proposal not found / stale / four eyes |
| `DRS-2032` | a Sutra file could not be read at all (the log has the stack trace); the rest load |
| `DRS-2101` / `DRS-2102` | expression syntax (or past the size limits) / evaluation error |
| `DRS-4001` | command not understood |
| `DRS-4003` | bad business date |
| `DRS-4004` | bad search |
| `DRS-5001` | bad request |
| `DRS-5002` | forbidden (role or pack) |
| `DRS-5003` | console cannot reach the server |
| `DRS-5010` | not signed in |
| `DRS-6001`–`DRS-6009` | user management (see [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md)); `DRS-6005` is a locked account |
