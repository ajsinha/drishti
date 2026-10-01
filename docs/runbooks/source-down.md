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
# Runbook: a source is down or slow

A *source* is where Drishti reads entities from: a Delta Lake domain, a database, Kafka, a REST service, S3,
a feed. Each one is a plugin instance, usually a named connector declared by a pack (for example the `trading`
pack's `trading-store`). When one fails, the others keep serving; only the kinds it holds are affected.

The examples use `http://localhost:18480`. With security on, add `-H "Authorization: Bearer $TOKEN"`
(an admin's token for `/api/v1/admin/*`; see [API_GUIDE.md](../API_GUIDE.md)).

## Symptoms

Any of these:

- A view shows an error with `DRS-1003` (the source failed) or `DRS-1004` (the source timed out).
- A view opens, but linked entities (counterparty, curves) show *pending* or *missing*.
- `DRS-1001 no source holds trade/T-10452` for an entity that exists, or `DRS-1002` (no source for the kind).
- **Admin → Health** (`/admin/health` in the console) shows a status of `DEGRADED` or `DOWN`, a source with
  status `DOWN`, or a pack with connectors down.
- Search results say they are partial, or type-ahead stops suggesting entities of one kind.

## Diagnosis

### Step 1. Which sources are unhealthy?

```bash
curl -s http://localhost:18480/api/v1/admin/health | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("status:", d["status"], d["summary"])
print("failed to start:", d["failedToStart"])
for s in d["sources"]:
    if s["status"] != "UP": print("DOWN:", s["name"], s["health"], s["kinds"])
for p in d["packs"]:
    if p["connectorsDown"] or p["connectorsOff"]: print("pack", p["name"], "down:", p["connectorsDown"], "off:", p["connectorsOff"])'
```

On a healthy server you should see:

```text
status: OK {'packsWithProblems': 0, 'failedToStart': 0, 'sourcesDown': 0, 'sources': 18, 'packs': 12}
failed to start: {}
pack market-data down: [] off: ['ecb-estr-feed', 'fred-feed']
pack trading down: [] off: ['trading-stream']
```

How to read it:

| Field | Meaning | Next |
|---|---|---|
| `status` | `OK`; `DEGRADED` (something is down, failed to start, or a pack has problems); `DOWN` (every source is down). | — |
| `failedToStart` | `{"<source>": "<reason>"}` for plugins that threw while starting. They serve nothing until the server restarts. | Step 2 |
| a source with `status: DOWN` | It started but its health check fails now. `health` holds the reason the plugin gave. | Step 3 |
| `connectorsDown` of a pack | Connectors of that pack that are `DOWN`. | Step 3 |
| `connectorsOff` of a pack | Connectors the pack declares that are not running: switched off (for example `trading-stream` until `DRISHTI_STREAM_TRADING=true`) or failed to start. Off by design is normal. | Step 2 if you expected it on |

Without admin rights, `GET /api/v1/sources` lists every running source with its `health`, and `failures` (the
plugins that failed to start):

```bash
curl -s http://localhost:18480/api/v1/sources | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["failures"]); [print(s["name"], s["health"]) for s in d["sources"]]'
```

### Step 2. Why did it fail to start?

Search the server log. The server logs to standard output; under systemd use `journalctl`, under Docker
Compose `docker compose logs`:

```bash
journalctl -u drishti-server --since "1 hour ago" | grep -E "failed to start|failed reading"
```

You should find a line like `source plugin trading-store failed to start` followed by the cause (a missing
directory, a refused connection, bad credentials, a missing setting). The usual causes:

| Cause | What the cause usually mentions | Fix |
|---|---|---|
| Wrong path | a file or directory that does not exist | Correct `root` (`DRISHTI_DELTA_ROOT` for the packs' lake connectors). |
| Database or broker unreachable | `Connection refused`, an unknown host | Start it, or correct the URL. |
| Wrong credentials | authentication or password | Correct the user and password variables. |
| Plugin switched on without its settings | the setting's name | Add the missing setting under `settings:`. |

The same reason, without the stack trace, is in `failedToStart`.

Connector settings are listed per plugin in [CONFIGURATION.md](../CONFIGURATION.md).

### Step 3. Is it down, or only slow?

1. Time one view of an affected kind and look at where the time goes (milliseconds):

   ```bash
   curl -s http://localhost:18480/api/v1/views/trade/T-10001 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["timings"], d["provenance"]["source"])'
   ```

   ```text
   {'fetch': 0.11, 'layout': 0.14, 'links': 0.16, 'bind': 0.21, 'total': 0.62} murex-rates
   ```

   A `fetch` close to 2,000 means reads are hitting `drishti.sources.fetch-timeout` (2 s). A `links` close to 40
   means linked entities are hitting `drishti.graph.link-budget` (40 ms) and show as *pending*.

2. If the view fails instead, the error tells you which:

   ```json
   {"type":"about:blank","title":"source timeout","status":504,"detail":"DRS-1004 …","code":"DRS-1004"}
   ```

   `DRS-1004` (HTTP 504) is a timeout, `DRS-1003` (HTTP 502) a failure; the `detail` names the source and entity.

3. Look at the source's read counters in Admin → Health (`reads` per source), or the log for
   `<source> failed reading <kind>/<id>`.

4. Try the source directly, outside Drishti: for a database, run the connector's query in a SQL client; for
   REST, `curl` the `base-url`; for Kafka, check the topic with your Kafka tools; for a lake, list the domain
   folder (`ls ./data/delta/trading/trade`).

## Fixes

**The source is down.** Bring it back; nothing in Drishti needs to change. Running connectors reconnect on the
next read, and views of the kind work again. Until then, other kinds keep working, and links to the affected
kinds show *pending* or *missing*.

**A plugin failed to start.** Correct its settings, then restart the server (a plugin that failed at start-up is
not retried). The other plugins are unaffected by the restart apart from the brief outage.

**The source is healthy but slow.** Give it more time, in `application.local.yaml` next to the server jar:

```yaml
drishti:
  sources:
    fetch-timeout: 5s        # default 2s; views wait at most this long for a read
  graph:
    link-budget: 100ms       # default 40ms; links slower than this show as pending
```

Then restart the server. Raising the link budget makes every view with slow links wait longer before it
appears, so raise it only as far as needed. For lake and stream connectors a bigger cache also helps (`cache-mb`;
see [PERFORMANCE.md](../PERFORMANCE.md#example-3-connector-caches)).

**You want a connector off while its source is out for a long time.** Switch it off with its variable (for
example `DRISHTI_LAKE_ENABLED=false` turns off the packs' lake connectors) or with
`drishti.sources.connectors.<name>.enabled: false`, and restart. Views of its kinds then fail at once
(`DRS-1001`, or `DRS-1002` when no other source serves the kind) instead of waiting for timeouts.

## Verification

1. Admin → Health shows `status: OK`, `sourcesDown: 0`, `failedToStart: 0`, and no pack lists the connector
   under `connectorsDown`.
2. A view of an affected kind loads, its provenance names the source, and `timings.fetch` is well below the
   timeout:

   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18480/api/v1/views/trade/T-10001
   ```

   You should see `200`.
3. In the console, the view's linked entities show values instead of *pending*.
