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
# Runbook: live latency high, or live views stop ticking

Use this runbook when live views are slow, late or frozen. How live updates work is explained in
[LIVE.md](../../architecture/LIVE.md); the normal numbers are in [PERFORMANCE.md](../PERFORMANCE.md).

The examples use `http://localhost:18480` for the server and `http://localhost:17480` for the console.
Replace them with your hosts. With security on, add `-H "Authorization: Bearer $TOKEN"` to server calls
(see [API_GUIDE.md](../../guides/API_GUIDE.md)). `/actuator/health` needs no token; the rest of `/actuator`
(`/actuator/prometheus`, `/actuator/metrics`) needs the scrape token `DRISHTI_METRICS_TOKEN` or an admin's token
when security is on:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_METRICS_TOKEN" http://localhost:18480/actuator/prometheus | grep '^drishti_live'
```

## Symptoms

Any of these:

- `p99Ms` from `GET /api/v1/health/live` stays above 40 for more than a minute (normal: single digits). (The
  console's live dot does not show the p99 on screen; screen readers announce it as `Live, p99 N ms`.)
- The Grafana panel or alert on `drishti_live_latency_p99_milliseconds` is above 40.
- Values change in bursts, or seconds late, compared with the source.
- The live dot in the top bar stays **amber** (*Reconnecting…*), or values stop changing although the source
  is ticking.
- New live views fail to open with `DRS-5001 too many live streams on this server`.

## Diagnosis

Work through the steps in order; each one tells you where to go next.

### Step 1. Read the live counters

```bash
curl -s http://localhost:18480/api/v1/health/live
```

A healthy server with some viewers:

```json
{"streams":12,"topics":5,"frames":11652,"p50Ms":0.886,"p99Ms":1.917}
```

| What you see | Meaning | Go to |
|---|---|---|
| `p50Ms` and `p99Ms` both high (tens of ms) | Every frame is slow: the server is short of CPU or a view is expensive to rebuild. | Step 2 |
| `p50Ms` low, only `p99Ms` high | Occasional stalls: GC pauses, or a burst of streams opening at once. | Step 3 |
| Both `0.0` while people say views are live | No frame was built in the last 30 s: nothing is ticking. | Step 4 |
| `streams` close to `drishti.live.max-streams` (20,000 by default) | The server is full. | Fix C |

### Step 2. Find out why frames are slow

1. Check CPU and heap. In the console open **Admin → Health** (`/admin/health`), or:

   ```bash
   curl -s http://localhost:18480/api/v1/admin/health | python3 -c 'import json,sys; print(json.load(sys.stdin)["server"])'
   ```

   ```text
   {'version': '1.16.0', 'uptimeSeconds': 6945, 'java': '25.0.4.1', 'heapUsedMb': 127, 'heapMaxMb': 15640, 'threads': 75, 'cpus': 24}
   ```

   `heapUsedMb` near `heapMaxMb` means the heap is too small (Fix D). Check host CPU with `top` or your monitoring.

2. Check how long one view takes to build. A live frame is a rebuild of the view, so a slow view is a slow frame.
   Take an entity that is slow on screen, for example `trade/END-1000008`:

   ```bash
   curl -s http://localhost:18480/api/v1/views/trade/END-1000008 | python3 -c 'import json,sys; print(json.load(sys.stdin)["timings"])'
   ```

   ```text
   {'fetch': 0.09, 'layout': 0.13, 'links': 0.21, 'bind': 0.15, 'total': 0.57}
   ```

   Times are in milliseconds. A `bind` of tens of milliseconds points at a heavy Sutra (very large tables, many
   expressions); a `fetch` or `links` near their budgets points at a slow source
   ([source-down.md](source-down.md)).

3. Check the server-wide view time:

   ```bash
   curl -s http://localhost:18480/actuator/prometheus | grep -E '^drishti_view_seconds|^drishti_live'
   ```

   `drishti_view_seconds{quantile="0.99"}` above 0.040 (seconds) confirms that views themselves are slow.

4. Count the topics. Many topics (hundreds of distinct live entities), each ticking fast, multiply the work.
   Go to Fix A or Fix B.

### Step 3. Find occasional stalls

1. GC pauses. The server should run generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`, as in
   `deploy/server.Dockerfile`), which keeps pauses below a millisecond. Check the flags of the running process:

   ```bash
   ps -o args= -C java | grep drishti-server
   ```

   If ZGC is missing, see Fix D. With Prometheus, `jvm_gc_pause_seconds_max` shows the longest recent pause.

2. A burst of streams. Many people opening workspaces at once (a morning start, a reconnect after a network
   blip) builds many first views together. `drishti_live_streams` jumping by hundreds at the time of the spike
   confirms it. This settles by itself within one 30 s window; if it happens daily, see Fix A and Fix C.

### Step 4. Nothing ticks

1. Is the source live?

   ```bash
   curl -s http://localhost:18480/api/v1/sources | python3 -c 'import json,sys; [print(s["name"], s["live"], s["health"]) for s in json.load(sys.stdin)["sources"] if s["live"]]'
   ```

   ```text
   demo True UP
   ```

   Only sources listed here can tick. If yours is missing or not `UP`, follow [source-down.md](source-down.md).

2. Is the user on today's business date? A picked past date never streams; the live dot is then grey
   (*Static*), and the date box is amber.

3. Stream the entity straight from the server, bypassing the console:

   ```bash
   curl -N http://localhost:18480/api/v1/views/trade/END-1000008/stream
   ```

   You should see `event:view` and then `event:frame` lines as the source ticks. Press Ctrl+C to stop.

   - Frames arrive here but not in the browser: the problem is between the server and the browser. Go to step 5.
   - Only `event:view`, then the response ends: the entity is not live (static source or past date).
   - Only `event:view` and `:hb` lines every 15 s: the stream is open but the source sends nothing.
     Check the source (for Kafka, that messages are arriving on the topic).

4. Is the demo source frozen? `drishti.sources.plugins.demo.settings.ticking: false` stops every demo entity.

### Step 5. The browser does not receive frames

1. Stream through the console, as the browser does:

   ```bash
   curl -N "http://localhost:17480/api/channel?s=view:trade/END-1000008"
   ```

   (With console sign-in on, add `-b "drishti_session=<your cookie>"`.) You should see `event: channel`,
   `event: view` and then `event: frame` lines.

2. Frames arrive straight from the console but not through your reverse proxy: the proxy buffers the stream.
   In nginx, set `proxy_buffering off;` and `proxy_read_timeout` above 15 s for the console's `/api/channel`.
   See [OPERATIONS.md](../OPERATIONS.md).

3. The tab says `Paused while hidden`: the tab was hidden for 10 s and gave its subscriptions back. It takes them
   again when shown. This is expected.

4. Several console processes behind a load balancer, and panes of a workspace (or further tabs) never start
   ticking: sessions are not sticky. Each browser holds one channel for all its tabs and adds subscriptions to it
   with `POST /api/channel/{id}`, and only the process that holds the channel knows it. Turn on sticky sessions
   (Fix E).

5. One view says `Static` while the same view ticks elsewhere, in a browser with very many live tabs: that browser
   reached `live.max_subscriptions` (32, console configuration). See
   [LIVE.md](../../architecture/LIVE.md#settings).

## Fixes

**Fix A. Coalesce more.** Raise the frame interval, so a busy entity costs at most one frame per interval.
Values become up to that much staler.

```yaml
# application.local.yaml next to the server jar
drishti:
  live:
    frame: 100ms
```

**Fix B. Make the expensive view cheaper.** Open the Sutra named in the view's provenance
(`"layout":"Sutra cmd-forward v1 + inference"`) in the Build workbench (**Build → New screen → an existing Sutra**) and reduce what it binds: fewer columns
in large tables, fewer rows, simpler expressions. See [RACHANA_REFERENCE.md](../../guides/RACHANA_REFERENCE.md).

**Fix C. Add capacity or cap streams.** Run another server behind the load balancer; topics and streams are per
server, so load spreads by user. To protect a server from a burst, lower the cap. Requests over it get
`DRS-5001` and the browser retries:

```bash
java -jar drishti-server-1.16.0-exec.jar --drishti.live.max-streams=5000
```

**Fix D. JVM.** Give the server ZGC and enough heap:

```bash
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational" java -jar drishti-server-1.16.0-exec.jar
```

**Fix E. Sticky sessions** for several console processes. In nginx, for example, `ip_hash;` in the console's
`upstream` block.

Every change to the server's configuration needs a restart of the server. Live views reconnect by themselves.

## Verification

1. Within one 30 s window after the fix, `curl -s http://localhost:18480/api/v1/health/live` shows `p99Ms`
   below 40 (normally single digits).
2. In the console, the live dot glows (not amber) on `TRD END-1000008`, and `MTM (USD)` changes every few hundred
   milliseconds.
3. `drishti_live_latency_p99_milliseconds` in Prometheus stays below 40 for the next hour.
