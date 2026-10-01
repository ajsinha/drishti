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
| [First checks](#first-checks-run-these-first) | three commands that locate most problems |
| [Building and starting](#building-and-starting) | wrong JDK, port in use, packs not found, old version runs, console will not start |
| [Signing in](#signing-in) | no sign-in page, wrong password, locked, signed in but bounced back |
| [The console and the server](#the-console-and-the-server) | backend unreachable, Reconnecting…, typing does nothing |
| [Commands and views](#commands-and-views) | cannot read command, DRS-1001, may not open, no suggestions, No data available, pending links, blank charts |
| [Live and dates](#live-updates-and-business-dates) | view does not tick, not a dated source, DRS-4003, known at has no effect |
| [Search](#search) | DRS-4004, empty results, partial results |
| [Studio and Sutras](#studio-and-sutras) | Save disabled, an edit has no effect, approval refused |
| [Packs](#packs) | mnemonics missing, a pack not offered |
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

   You should see `{"status":"UP"}`.

3. **What is loaded?**

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

### The build fails with "Drishti builds and runs on OpenJDK 21"

- **Check:** `./mvnw -v` prints the Java version Maven uses.
- **Fix:** point Maven at JDK 21 and build again:

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  ./mvnw -q verify
  ```

### The server stops at once with `UnsupportedClassVersionError … class file version 65.0`

- **Cause:** `java` on your `PATH` is older than 21.
- **Check:** `java -version`.
- **Fix:** run the jar with JDK 21: `/usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar …`, or put that JDK first on
  your `PATH`.

### "Web server failed to start. Port 18480 was already in use."

- **Check:** who holds the port: `ss -ltnp | grep 18480` (or `lsof -i :18480`). Often it is an earlier Drishti
  server still running.
- **Fix:** stop the other process, or start this one on another port and tell the console where it is:

  ```bash
  DRISHTI_PORT=18481 java -jar drishti-server/target/drishti-server-1.9.0-exec.jar
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
    java -jar drishti-server/target/drishti-server-1.9.0-exec.jar
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
- **Fix:** name the jar exactly (`drishti-server-1.9.0-exec.jar`), or delete the old ones.

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
  beyond a trial, turn security on first (see [OPERATIONS.md](OPERATIONS.md)).

## Signing in

### There is no sign-in page, and no users or roles apply

- **Cause:** this is the local-development default: security is off on the server
  (`DRISHTI_SECURITY_ENABLED=false`) and sign-in is off in the console (`DRISHTI_AUTH_ENABLED=false`).
  `curl -s http://localhost:18480/api/v1/about` shows `"securityEnabled": false`.
- **Fix:** to sign in with users and roles, turn both on and share one secret:

  ```bash
  export DRISHTI_TOKEN_SECRET='at-least-32-characters-shared-secret!!'   # same value for both
  DRISHTI_SECURITY_ENABLED=true java -jar drishti-server/target/drishti-server-1.9.0-exec.jar
  DRISHTI_AUTH_ENABLED=true DRISHTI_SESSION_SECRET='another-secret-of-32-characters-or-more' \
    console/.venv/bin/python console/run_drishti_web.py
  ```

  Then sign in at `http://localhost:17480/login` as **`drishti-dev-admin`** / **`drishti-dev-admin123`** and
  change the password at **My account** (`/account`). The admin pages warn until you do. See
  [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

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
  the clocks (NTP). More in [runbooks/sign-in.md](runbooks/sign-in.md).

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

### "Reconnecting…" in the top bar

- **Cause:** the live stream dropped (server restarted, network blip).
- **Fix:** nothing to do: the browser reconnects by itself and repaints from a fresh view. If it never
  recovers, run the first checks.

### "Paused while hidden" in the top bar

- **Cause:** the tab was hidden for 10 seconds and gave its connection back, to save resources.
- **Fix:** show the tab: it reconnects and repaints.

### The page looks fine but typing does nothing (no suggestions), often with several tabs open

- **Cause:** before 1.10, each view and the alerts bell held its own connection, and browsers allow only six per
  site. Each tab now uses one live connection (see [LIVE.md](LIVE.md)).
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
- **Fix:** use the form `MNEMONIC ID`, for example `TRD T-10001 <GO>` (case does not matter; `<GO>` is optional,
  Enter is enough).

### "DRS-1001 no source holds trade/T-99999"

- **Cause:** the mnemonic is fine, but no source has that identifier.
- **Check:** type the mnemonic and the start of the id (`TRD T-100`) and pick from the suggestions; or
  `curl -s "http://localhost:18480/api/v1/command/suggest?q=TRD%20T-100"`.
- **Fix:** correct the id. The example commands for each pack are on the terminal home and in each pack's guide
  under **Help → Domain packs**.

### "DRS-5002 … may not open … entities"

- **Cause:** your roles do not include that kind, or the pack that owns it is not among your packs.
- **Check:** `curl -s http://localhost:18480/api/v1/me/packs` lists `assigned` and `active` packs (with security
  on, check in the console's pack menu, the box icon in the top bar).
- **Fix:** tick the pack in the box menu, or ask an admin to assign the pack or a role. See
  [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

### Suggestions do not appear

- **Check:** the server answers type-ahead directly:

  ```bash
  curl -s "http://localhost:18480/api/v1/command/suggest?q=T"
  ```

  A list means the server is fine: refresh the page (see *typing does nothing* above). An empty list means no
  enabled pack has a mnemonic starting with that text, or every source missed the 30 ms budget.
- **Fix:** for a custom source, declare `search` in the plugin and index ids in memory with `HitIndex`
  ([PLUGIN_GUIDE.md](PLUGIN_GUIDE.md)). `/api/v1/sources` shows `"search": true` for sources that can suggest.

### A panel says "No data available"

- **Cause:** the document lacks what that panel needs, or has it in another shape. The rest of the view renders
  on purpose. Real feeds are often incomplete.
- **Check:** if the panel also says "The data did not have the shape this panel expects", hover over the message
  to read the exact error. Press **F9** to see the raw JSON and compare it with the panel's `rows:` path in the
  Sutra (Studio shows it).
- **Fix:** if the data is right and the Sutra is wrong, fix the Sutra in Studio; see
  [runbooks/sutra-broken.md](runbooks/sutra-broken.md).

### A linked entity shows `pending` or `missing` instead of a badge

- **Cause:** `pending`: the linked entity did not arrive within the 40 ms link budget (`drishti.graph.link-budget`).
  `missing`: no source has it.
- **Check:** `curl -s http://localhost:18480/api/v1/sources` for a source whose `health` is not `UP`.
- **Fix:** a slow source: see [runbooks/source-down.md](runbooks/source-down.md). Open the link itself to see the
  real error.

### Charts are blank

- **Cause:** the chart library did not load (a proxy, a strict browser extension, or a broken path).
- **Check:** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:17480/static/vendor/echarts/echarts.min.js`
  should print `200`. The browser's developer console (F12) shows any blocked script.
- **Fix:** allow the console's own `/static/` path through the proxy or extension; hard-refresh.

## Live updates and business dates

### A view does not tick

- **Check:** the top bar date box: a picked date (amber) is a **static snapshot**; only **Live** (green) streams.
  Then the source: `curl -s http://localhost:18480/api/v1/views/trade/T-10001 | python3 -c "import json,sys; print(json.load(sys.stdin)['provenance'])"`
  shows `'live': True` for an entity that ticks.
- **Fix:** press **Live**. If `live` is `False`, that entity's source does not stream (in the samples, only
  documents marked live in the `demo` source tick; the trading pack's Kafka stream needs
  `DRISHTI_STREAM_TRADING=true`). See [LIVE.md](LIVE.md).

### "… is not a dated source: this shows its current data, not 2026-08-03"

- **Cause:** for the date you picked, the entity came from a source that keeps no history (for example the
  `demo` source), so you see its current data.
- **Check:** `ls data/delta/<domain>/<kind>/` lists the dates the lake has (`business_date=2026-09-30`, …).
- **Fix:** pick a date the lake covers, or build more history:

  ```bash
  uv run --with deltalake --with pyarrow python tools/samplegen/lake.py \
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
- **Fix:** copy lakes with `cp -p` or `rsync -t`. See [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md).

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

- **Check:** the mnemonic first: `XYZ where a = 1` is not an error, it is a search of a kind nobody serves
  (`"mnemonic": null`, `"scanned": 0` in the API answer). Then the field name: press **F9** on one entity of that
  kind and copy the path exactly (`counterparty.name`, `legs[0].rate`). A masked field never matches.
- **Fix:** correct the mnemonic or path. Text matching with `contains` is case-insensitive.

### "… results may be incomplete"

- **Cause:** the scan stopped short: more than 20,000 entities of that kind (`drishti.search.max-scan`), or a
  source slower than 3 seconds (`drishti.search.budget`). The API answer has `"partial": true`.
- **Fix:** narrow the search, or raise the limits ([CONFIGURATION.md](CONFIGURATION.md)).

## Studio and Sutras

### Studio's Save (or Submit for review) is disabled

- **Cause:** "Saving is off here, or you are not an author".
- **Check:** `curl -s http://localhost:18480/api/v1/studio/settings` should show `"save": true`.
- **Fix:** start the server with `DRISHTI_STUDIO_SAVE=true`, and (with security on) give yourself the `author`,
  `approver` or `admin` role.

### A Sutra edit has no effect

- **Check:** `curl -s http://localhost:18480/api/v1/sutras/problems` should print `{}`. Anything else lists the
  file, line, column and code (`DRS-2001` parse error, `DRS-2002` invalid, `DRS-2101` expression syntax).
- **Cause and fix:** an invalid edit keeps the **last good version** live. Fix the reported line (Studio's
  Ctrl+Enter lists problems by line). With review on (the default), a saved Sutra is only a **proposal** until an
  approver approves it in **Studio → Reviews**. *How this view was built* shows the version actually used. See
  [runbooks/sutra-broken.md](runbooks/sutra-broken.md).

### Approving fails with `DRS-2007` or `DRS-2006`

- **Cause:** `DRS-2007`: four eyes; nobody may approve their own proposal. `DRS-2006`: either the proposal is already
  decided, or the live Sutra changed after it was proposed ("… changed after … was proposed; reject it and propose
  again from the live version").
- **Fix:** ask another approver; for a stale proposal, reject it, open the live Sutra in Studio, re-apply the change
  and submit anew.

## Packs

### A pack's mnemonics are missing from the command line

- **Check:** is it enabled? `curl -s http://localhost:18480/api/v1/packs` lists enabled packs. Is it chosen? The box
  icon in the top bar shows ticks for your active packs.
- **Fix:** enable it on the server (`DRISHTI_PACKS`, then restart), then tick it in the box menu. An admin may
  need to assign it to you first (**Admin → Users**).

### A pack is enabled but a pack problem shows in Admin → Health

- **Check:** `curl -s http://localhost:18480/api/v1/admin/health | python3 -c "import json,sys; [print(p['name'], p['status'], p['sutraProblems'], p['connectorsDown']) for p in json.load(sys.stdin)['packs']]"`
- **Fix:** Sutra problems: see *A Sutra edit has no effect*. A connector down: see
  [runbooks/source-down.md](runbooks/source-down.md).

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
| `DRS-2101` / `DRS-2102` | expression syntax / evaluation error |
| `DRS-4001` | command not understood |
| `DRS-4003` | bad business date |
| `DRS-4004` | bad search |
| `DRS-5001` | bad request |
| `DRS-5002` | forbidden (role or pack) |
| `DRS-5003` | console cannot reach the server |
| `DRS-5010` | not signed in |
| `DRS-6001`–`DRS-6009` | user management (see [USER_MANAGEMENT.md](USER_MANAGEMENT.md)); `DRS-6005` is a locked account |
